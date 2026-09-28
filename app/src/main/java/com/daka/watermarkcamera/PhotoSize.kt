package com.daka.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 出土（存进相册）照片的**比例**与**像素数**。
 *
 * ## 为什么要单独一套
 *
 * 原先是「相机给多大就存多大」：`setTargetAspectRatio(4:3)` 不限制分辨率，
 * CameraX 会挑该比例下的最大尺寸，于是 16MP 手机存出来就是 8MB 一张。
 * 而相册那条路早就有上限（见 [ImageWatermarker.MAX_SIDE] 的注释：
 * 「再大对打卡照片没有意义」）—— 两边不一致，等于拍照那条路漏了。
 *
 * 现在两件事都由用户定：[PhotoRatio] 决定形状，[SaveSize] 决定像素数。
 *
 * ## 比例是「自己裁」而不是「让相机给」
 *
 * 全屏（跟随屏幕比例）这类档位很多机型根本不暴露（传感器一般只出 4:3 和 16:9），
 * 而且就算暴露也给不出"屏幕的形状"。交给 CameraX 去协商，失败时它**静默退回**
 * 一个支持的比例 —— 用户选了全屏却拿到 4:3，而且没有任何提示。
 * 所以这里一律按 4:3 采集，裁剪自己做：结果在什么机器上都一样，
 * 而且**切比例不需要重启相机会话**。
 *
 * 取景框那边用 `fillCenter` 露出**同一块居中区域**（见 MainActivity.layoutOverlay），
 * 于是"取景框里看到的"和"存下来的"始终是同一个裁剪。
 * （`fillCenter` 不是笔误：`PreviewView` 的 scaleType 枚举里**没有 centerCrop**，
 * `fill*` 才是"填满并居中裁剪"，`fit*` 是留黑边的 letterbox。）
 *
 * ## 为什么比值要由调用方传进来
 *
 * 4:3 和 16:9 是常量，但**全屏档的比例取决于屏幕**（20:9 的屏和 16:9 的屏不一样），
 * 它还会随横竖屏、分屏变化。所以 [PhotoRatio] 自己不算比例，只声明"我是哪一档"；
 * 比值由调用方用 [screenSpan] 解析好传进来（[span] / [portraitAspect]）。
 * 这样做的好处是这个枚举仍然**完全不碰 Android API**，纯逻辑测试里
 * 喂一个假屏幕比例就能把全屏档的每条边界跑完。
 */
internal enum class PhotoRatio(val label: String, val code: Int) {
    R4_3("4:3", 0),
    R16_9("16:9", 1),
    R_FULL("全屏", 2);

    /** 全屏档：比例由屏幕决定，不是固定值 */
    val isFullScreen: Boolean get() = this == R_FULL

    /** 固定档的长短边比。全屏档返回 0，因为它的值只有屏幕知道 */
    val fixedSpan: Double
        get() = when (this) {
            R4_3 -> 4.0 / 3.0
            R16_9 -> 16.0 / 9.0
            R_FULL -> 0.0
        }

    /**
     * 解析成实际的长短边比（长边 ÷ 短边，恒 ≥ 1）。
     *
     * [screenSpan] 是屏幕的长短边比，**只有全屏档用得到**，其它档传什么都无所谓
     * （传 0 也行）。屏幕比例来自系统，属于外部输入：分屏、折叠屏内屏、
     * 开发者选项里的奇葩分辨率都会让它偏离常识值，所以这里要夹一道 ——
     * 否则一块接近正方形的内屏会让"全屏"照片真的变成方的，
     * 一块 4:1 的分屏会让它变成一条细缝。
     */
    fun span(screenSpan: Double): Double {
        if (!isFullScreen) return fixedSpan
        if (!screenSpan.isFinite() || screenSpan <= 0.0) return DEFAULT_FULL_SPAN
        return screenSpan.coerceIn(1.0, MAX_FULL_SPAN)
    }

    /**
     * 画面在**竖屏**下的宽高比（短边/长边，恒 ≤ 1）。
     * 取景浮层就是按这个值摆放的，改了它会直接影响所见即所得。
     */
    fun portraitAspect(screenSpan: Double): Float = (1.0 / span(screenSpan)).toFloat()

    companion object {
        /** 读不到屏幕尺寸时全屏档退回 16:9 —— 手机上最常见的一档 */
        const val DEFAULT_FULL_SPAN = 16.0 / 9.0

        /** 全屏档的比例上限。真实设备最宽的长屏也不到 3:1，超出的都是异常输入 */
        const val MAX_FULL_SPAN = 3.0

        /**
         * 按稳定编码取值。认不出来就回 4:3 ——
         * 老版本存的、或将来删掉的档位留下的野值都不该让 App 崩或显示空白。
         *
         * **不要改成 `entries[code]`**：那是拿声明顺序当编码，
         * 将来往中间插一档，所有老用户已经选好的比例会静默错位。
         *
         * 注：编码 2 曾经是「1:1」，因为方形照片不好看、实际没人用，
         * 已改成「全屏」。这一档还没发布过，所以直接改了语义没有做迁移。
         */
        fun of(code: Int): PhotoRatio = entries.firstOrNull { it.code == code } ?: R4_3
    }
}

/**
 * 屏幕像素 → 长短边比（≥ 1），给「全屏」档用。
 *
 * 放成独立函数而不是塞进枚举，是为了让"屏幕比例从哪来"这件事留在
 * [PhotoRatio.span] 之外 —— 那边只管夹范围，这边只管取尺寸。
 * 读不到有效尺寸时返回 0，交给 [PhotoRatio.span] 兜到 16:9。
 */
internal fun screenSpan(w: Int, h: Int): Double {
    if (w <= 0 || h <= 0) return 0.0
    return max(w, h).toDouble() / min(w, h).toDouble()
}

/**
 * 出土像素档位。
 *
 * 三档的尺寸用的是**相机上常见的标准尺寸**，不是按像素数硬算出来的：
 * 硬算 2M/16:9 会得到 1886×1061 这种零头，而 1920×1080 人人都认识，
 * 而且投屏、传群、上传考勤系统都不会被挑刺。代价是实际像素数与标签
 * 有 ±10~15% 的出入（1920×1080 是 2.07MP），这在相机行业本来就是惯例。
 *
 * 全屏档没有"标准尺寸"可抄（屏幕比例是任意值），所以它的像素由
 * [baseShort] 推出来 —— 见那里的说明。
 */
internal enum class SaveSize(val label: String, val code: Int) {
    M1("1M", 0),
    M2("2M", 1),
    M4("4M", 2);

    /**
     * 「全屏」档的**短边**：直接借用同档 16:9 的短边。
     *
     * 为什么不是按长边、也不是按像素数硬算：全屏在观感上就是"16:9 再往上多给一点"
     * （20:9 的屏就是 16:9 加上额头下巴），所以短边跟 16:9 那一档保持一致、
     * 长边按屏幕比例延伸，像素数自然落在同一个量级上：
     * ```
     * 屏幕上 1080×2400（20:9）        1M    2M     4M
     * 16:9 档        1280×720   1920×1080   2560×1440
     * 全屏档         720×1600   1080×2400   1440×3200   ← 短边一样，长边按屏延伸
     * ```
     * 若改用长边对齐，全屏/1M 会变成 1280×2844（2.3MP，标签就名不副实了）。
     */
    private val baseShort: Int
        get() = when (this) {
            M1 -> 720
            M2 -> 1080
            M4 -> 1440
        }

    /** 短边像素。4:3 与 16:9 用标准尺寸，全屏用 [baseShort] */
    fun shortSide(ratio: PhotoRatio): Int = when (ratio) {
        PhotoRatio.R4_3 -> when (this) {
            M1 -> 864
            M2 -> 1200
            M4 -> 1728
        }
        PhotoRatio.R16_9 -> baseShort
        PhotoRatio.R_FULL -> baseShort
    }

    /**
     * 长边像素。[span] 只有全屏档用得到（其它档的比例是常量，见 [PhotoRatio.fixedSpan]）。
     *
     * 全屏档取 `max(短边, 短边 × span)`：正常 span ≥ 1 时就是后者，
     * 而这个 `max` 是为了**万一拿到垃圾输入也不产出小于短边的长边** ——
     * 长边比短边小会让后面的裁剪框算出 0 或负数，`createBitmap` 直接抛。
     * （正常路径上 span 已被 [PhotoRatio.span] 夹到 [1, 3]，这里只是兜底。）
     */
    fun longSide(ratio: PhotoRatio, span: Double): Int = when (ratio) {
        PhotoRatio.R4_3 -> when (this) {
            M1 -> 1152
            M2 -> 1600
            M4 -> 2304
        }
        PhotoRatio.R16_9 -> when (this) {
            M1 -> 1280
            M2 -> 1920
            M4 -> 2560
        }
        PhotoRatio.R_FULL -> {
            val s = baseShort
            max(s, if (span.isFinite()) (s * span).roundToInt() else s)
        }
    }

    /**
     * 输出尺寸。横图返回 `[长, 短]`，竖图返回 `[短, 长]` —— 方向必须跟着原图走，
     * 否则竖着拍的照片会被存成横的。
     */
    fun output(ratio: PhotoRatio, span: Double, landscape: Boolean): IntArray {
        val l = longSide(ratio, span)
        val s = shortSide(ratio)
        return if (landscape) intArrayOf(l, s) else intArrayOf(s, l)
    }

    companion object {
        /**
         * 同 [PhotoRatio.of]：按稳定编码取，认不出来就回默认档。
         * 默认 1M —— 打卡照片的用途是"证明谁在什么时候到过哪"，
         * 1M（1152×864，约 0.3MB）认脸、看水印、看环境都够，传群和上传也最快。
         */
        fun of(code: Int): SaveSize = entries.firstOrNull { it.code == code } ?: M1
    }
}

/**
 * 解码 + 裁剪 + 缩放的计划。
 *
 * - [sample] 交给 `BitmapFactory.Options.inSampleSize`（**唯一真正被用来控制解码的字段**）
 * - [cropW]/[cropH] 按 [sample] 缩完之后、在摆正后坐标系里的居中裁剪框。
 *   **只是预测值，不参与实际裁剪** —— 真正裁剪时 `cropTo` 是拿"解码出来的那张图"
 *   的真实宽高重算的（解码器的取整方式与 `w / sample` 未必完全一致，
 *   用预测值去 `createBitmap` 有越界的风险）。这两个字段的用处是**决定 [sample]**
 *   （见 [planDecode] 的循环），以及测试里核对这个决定合不合理。
 * - [outW]/[outH] 最终像素尺寸，恒等于用户选的那一档。
 */
internal data class DecodePlan(
    val sample: Int,
    val cropW: Int,
    val cropH: Int,
    val outW: Int,
    val outH: Int
)

/**
 * 居中裁剪框：把 w×h 裁成宽高比 want，取最大的一块居中区域。
 * 只会缩不会放 —— 某一维算出超过原尺寸时夹回去（四舍五入可能多出一像素）。
 */
internal fun cropOf(w: Int, h: Int, want: Double): Pair<Int, Int> {
    if (w <= 0 || h <= 0 || want <= 0.0) return 1 to 1
    return if (w.toDouble() / h > want) {
        /* 比目标更宽 → 裁两边，高度留满 */
        min(w, max(1, (h * want).roundToInt())) to h
    } else {
        /* 比目标更高 → 裁上下，宽度留满 */
        w to min(h, max(1, (w / want).roundToInt()))
    }
}

/**
 * 算一份计划。源尺寸非法时返回 null。
 *
 * [quarterTurn] 表示原图还需要旋转 90/270 度摆正 —— 那种情况下裁剪是在
 * **摆正后**的坐标系里算的，所以这里要先把它折进来（宽高对调），
 * 否则会把竖图裁成横的。
 *
 * [span] 是这一档实际的长短边比，只有全屏档不是常量（见 [PhotoRatio.span]）。
 * 横竖由**摆正后的源尺寸**决定，不由调用方传：调用方在"要不要转 90 度"这件事上
 * 已经够容易错了，再让它判一次方向只会多一个错处。
 *
 * ## 为什么必须两段缩（2 的幂 + 精确缩放）
 *
 * `inSampleSize` 只能取 2 的幂。源长边 4608 时：
 * ```
 * k=1 → 4608        k=2 → 2304（正好命中 4M）      k=4 → 1152
 * ```
 * 1M 要 1152、2M 要 1600 —— 两档都只能落到 k=4 → 1152，
 * 也就是**选了 1M 和选了 2M 会输出一模一样的尺寸**。
 * 所以 2 的幂只负责"粗粗缩掉一大半"（省内存、省后续耗时），
 * 最后一定再走一次精确缩放把尺寸落到目标上。
 *
 * ## 小图会被放大 —— 这是有意的
 *
 * 源图 1600×1200 选 2M + 16:9 时，裁到 16:9 只剩 1600×900，而目标要 1920×1080：
 * 原图就 1600 列像素，够不出 1920 列，**只能放大**。
 *
 * 这里选择照放大（而不是"缩不上去就把尺寸降下来"），因为用户选的档位、
 * 设置页写的那行「横着拍 1920×1080」都是承诺；悄悄给个小尺寸等于设置失效。
 * 相机实际给的图远大于此（8MP 起步），真正会碰到这条的只有"从相册挑一张小图"。
 *
 * 但有一条**绝不能破**：既然要放大，[sample] 就必须是 1 ——
 * 先降采样再放大回来是白丢细节，那才是真会糊的写法。
 * [planDecode] 的循环天然满足这一点（裁剪后不够大时就停在当前 k），
 * 并由 `PhotoSizeTest` 的扫描断言钉住。
 */
internal fun planDecode(
    srcW: Int,
    srcH: Int,
    quarterTurn: Boolean,
    size: SaveSize,
    ratio: PhotoRatio,
    span: Double
): DecodePlan? {
    if (srcW <= 0 || srcH <= 0) return null

    /* 摆正后的尺寸 */
    val spanW = if (quarterTurn) srcH else srcW
    val spanH = if (quarterTurn) srcW else srcH

    val out = size.output(ratio, span, spanW >= spanH)
    val outW = out[0]
    val outH = out[1]
    val want = outW.toDouble() / outH

    /*
     * 找最大的 2 的幂，使「缩完再裁剪」得到的那块**仍然不小于**目标。
     * 判据必须放在裁剪之后：裁剪会砍掉一整个方向，只看原图的长边会把自己骗过去 ——
     * 4:3 的源裁成 16:9（或更宽的全屏）是**宽留满、高被砍掉约四分之一**，
     * 所以真正会先不够用的那一维是短边，不是长边。
     */
    var k = 1
    while (k * 2 <= 64) {
        val nk = k * 2
        val (cw, ch) = cropOf(max(1, spanW / nk), max(1, spanH / nk), want)
        if (cw < outW || ch < outH) break
        k = nk
    }

    val (cw, ch) = cropOf(max(1, spanW / k), max(1, spanH / k), want)
    return DecodePlan(k, cw, ch, outW, outH)
}

/**
 * 只按比例居中裁剪，**不缩放**。给相册水印的预览用 ——
 * 预览要的是「版式和成品一致」，不需要尺寸也一致。
 */
internal fun cropRatio(src: Bitmap, want: Double): Bitmap {
    val (cw, ch) = cropOf(src.width, src.height, want)
    val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
    val x = (src.width - cw) / 2
    val y = (src.height - ch) / 2
    Canvas(out).drawBitmap(
        src,
        Rect(x, y, x + cw, y + ch),
        Rect(0, 0, cw, ch),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )
    return out
}

/**
 * 居中裁到目标比例、再缩到目标尺寸。裁剪与缩放**一次画完**，
 * 所以中间不会多出一张"裁完还没缩"的图。
 *
 * **一定返回新图，绝不复用 [src]**：调用方要按"两张都得回收"处理。
 * 允许在尺寸已经相等时返回同一张的写法看着能省一次拷贝，但那种
 * "有时是同一张、有时不是"的返回值迟早会被某处多回收一次。
 */
internal fun cropTo(src: Bitmap, outW: Int, outH: Int): Bitmap {
    val out = Bitmap.createBitmap(max(1, outW), max(1, outH), Bitmap.Config.ARGB_8888)
    val (cw, ch) = cropOf(src.width, src.height, outW.toDouble() / outH)
    val x = (src.width - cw) / 2
    val y = (src.height - ch) / 2
    Canvas(out).drawBitmap(
        src,
        Rect(x, y, x + cw, y + ch),
        Rect(0, 0, out.width, out.height),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )
    return out
}
