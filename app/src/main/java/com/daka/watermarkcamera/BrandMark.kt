package com.daka.watermarkcamera

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 品牌区那两行固定词条的**矢量绘制** —— 右下角（或右上角）的
 * 「今日水印」和「相机真实可验」。
 *
 * ## 为什么不直接 drawText
 *
 * 这两行是**标识**，不是信息。信息（时间/地点/备注）用系统字体没问题，各手机
 * 长得不一样也无所谓；标识不行 —— 换个 ROM 换套中文字体，同一张水印就变了样。
 * 所以这两行走**轮廓路径**：跟着 APK 走，跟系统装了什么字体完全无关，
 * 而且不会出现"字体没加载上就悄悄退回系统字体"（PT Mono 那次就是踩了这个）。
 *
 * 用到的 10 个字约 3 KB（[BrandMarkPaths]），比塞一份 1.4 MB 的字体包省得多。
 *
 * ## 它是谁的字
 *
 * 轮廓取自 `AlimamaShuHeiTi-Bold.ttf`（阿里妈妈数黑体 Bold），来自
 * `Y:\今日水印相机字体\` —— 就是从「今日水印相机」安装包里扒出来的那份。
 * 选它的依据是**量出来的**：参考图里逐字量出的"墨宽/墨高"比与它一致（±4%），
 * 是那个目录里唯一覆盖这 10 个字的 TrueType 字（另两个 CJK 是 CFF 的汉仪旗黑，
 * 而且渲出来的字重明显偏轻）。
 *
 * ## 版式参数怎么来的
 *
 * 参考图只有 360×175（还没我们自己出图的一个字大），肉眼比不出来。所以是**解方程**：
 * 字体里每个字的墨迹宽高是已知的，图上量到的是像素，两者一比就得到每行的字号；
 * 各字左边界之差再给出步进（= 字宽 + 字距）。反解过程见 `verify/solve_brand.py`。
 *
 * 结果：
 * - 行1 字号 : 行2 字号 = **1.40**（图上 72.1px : 51.5px）
 * - 行1 字距 **+0.164 em**（这个词条是放松排的，不是默认字宽）
 * - 行2 后面那个灰块 = 「真实可验」的**墨迹框四周各留 0.15 em**，
 *   不是按基线对齐 —— 按基线算出来的位置与图上差了 9px。
 *
 * **参考图给不出绝对大小**，只能给比例：那块裁切在原照片里占多宽无从得知。
 * 所以字号是单独定的 —— 最初沿用 30s（与旧版一致），后来按观感两度放大到 48s，
 * 依据见 [NAME_EM] 的注释。
 */
internal object BrandMark {

    /** 内置矢量稿对应的两个词条。用户改了设置里的名字/标语就退回字体渲染 */
    const val NAME = "今日水印"
    const val SLOGAN = "相机真实可验"

    /**
     * 行1 基准字号（×s，s 是水印的归一化系数）。
     *
     * 30 → 40 → **48**。30 是"旧版就是这个数"，不是从参考图量出来的 ——
     * 参考图（360×165 的品牌区裁切）只能反解**行与行的比例**（1.40），
     * 给不出绝对大小：那块裁切在原照片里占多宽，无从得知。
     * 所以当年比例对了、绝对大小却一直偏小，观感上明显不如官方水印压得住。
     *
     * 40 取的是"与左侧地点行（42f）齐平略小"；再提到 48 是因为**地点那行还在 42**，
     * 品牌名（含矢量字距）实测只跟它一样宽 —— 而品牌区是三行、还带一个灰块，
     * 视觉重量本就该压过下面那两行信息一点。
     *
     * 行2/行3 自动跟随（÷1.40 与 [WatermarkRenderer.BRAND_CODE_EM]），层级关系不变。
     */
    const val NAME_EM = 48f

    /** 行2 基准字号 = 行1 ÷ 1.40 */
    const val SLOGAN_EM = NAME_EM / 1.40f

    /* ---- 版式（单位都是 em）---- */

    /** 行1 的字距：步进 = 1 + 这个值 */
    private const val NAME_TRACK = 0.164f

    /** 「相机」推进完之后，到「真实可验」原点之间的距离 */
    private const val SLOGAN_GAP = 0.238f

    /** 灰块在「真实可验」墨迹框四周的留白 */
    private const val BOX_PAD = 0.15f

    /** 灰块圆角 */
    private const val BOX_RADIUS = 0.12f

    /** 灰块底色：要压得住深色字，又不能像纯白那样抢主体的注意力 */
    const val BOX_COLOR = 0xFFE9ECEF.toInt()

    /** 灰块里的字色 */
    const val BOX_TEXT_COLOR = 0xFF262B33.toInt()

    /** 「真实可验」4 个字的原点（em）：前面是「相机」两个字 */
    private const val SLOGAN_TAIL_X = 2f + SLOGAN_GAP

    /** 行1 的绘制宽度（em）= 3 个步进 + 末字墨迹右边界 */
    val NAME_W: Float = 3f * (1f + NAME_TRACK) + BrandMarkPaths.NAME_INK_RIGHT

    /** 行2 的绘制宽度（em）= 到灰块右边缘为止 */
    val SLOGAN_W: Float = SLOGAN_TAIL_X + BrandMarkPaths.SLOGAN_INK_RIGHT + BOX_PAD

    /* 路径对象只建一次：预览是每帧重绘的，每帧重新解析路径串会白烧 CPU */
    private val namePaths: List<Path> by lazy { BrandMarkPaths.NAME.map { parsePath(it) } }
    private val sloganPaths: List<Path> by lazy { BrandMarkPaths.SLOGAN.map { parsePath(it) } }

    private val box = RectF()

    /**
     * 灰块里那 4 个深字专用的画笔，**不带阴影**。
     *
     * 白字那层阴影是 11×s 的模糊（s=1.33 时约 15px）。白字压深色遮罩需要它，
     * 但深字是压在**不透明的浅色块**上的 —— 同一层阴影会糊在块里，
     * 表现是字看不清、块也脏。所以这里另起一支干净画笔。
     *
     * 由 BrandMark 自己持有而不让调用方传第三支画笔：颜色、有无阴影都是这个稿子的
     * 内部约定，漏传一处就会退化成"字和底同色、只剩一圈阴影"。
     */
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BOX_TEXT_COLOR
    }

    /** 行1 与行2 的绘制宽度（像素） */
    fun nameWidth(em: Float): Float = NAME_W * em

    fun sloganWidth(em: Float): Float = SLOGAN_W * em

    /**
     * 画「今日水印」。[right] 是这一行的右边界，[baselineY] 是基线。
     */
    fun drawName(canvas: Canvas, right: Float, baselineY: Float, em: Float, paint: Paint) {
        val k = em / BrandMarkPaths.EM
        val save = canvas.save()
        canvas.translate(right - NAME_W * em, baselineY)
        canvas.scale(k, k)
        val step = (1f + NAME_TRACK) * BrandMarkPaths.EM
        for (i in namePaths.indices) {
            val s = canvas.save()
            canvas.translate(i * step, 0f)
            canvas.drawPath(namePaths[i], paint)
            canvas.restoreToCount(s)
        }
        canvas.restoreToCount(save)
    }

    /**
     * 画「相机 ✓真实可验」—— 白字 + 浅灰圆角块里的深字。
     *
     * [right] 是**灰块的右边缘**（整行最右），[baselineY] 是基线。
     * 块要先画：它是背景，画晚了会把字盖掉。
     *
     * 颜色由本函数自己设：块底色用 [boxPaint]（调用方只需把阴影配好），
     * 块里的深字用内部的 [darkPaint]（不带阴影），白字那支 [paint] 的颜色由调用方给。
     * 之所以这样分：两边都改颜色很容易漏一处，表现就是"字和底同色、只剩一圈阴影"，
     * 看上去像没渲染。
     */
    fun drawSlogan(
        canvas: Canvas,
        right: Float,
        baselineY: Float,
        em: Float,
        paint: Paint,
        boxPaint: Paint
    ) {
        val k = em / BrandMarkPaths.EM
        val save = canvas.save()
        canvas.translate(right - SLOGAN_W * em, baselineY)
        canvas.scale(k, k)

        /* 灰块在「真实可验」之前画 —— 它是底，字要压在它上面 */
        box.set(
            (SLOGAN_TAIL_X + BrandMarkPaths.SLOGAN_INK_LEFT - BOX_PAD) * BrandMarkPaths.EM,
            (BrandMarkPaths.SLOGAN_INK_TOP - BOX_PAD) * BrandMarkPaths.EM,
            (SLOGAN_TAIL_X + BrandMarkPaths.SLOGAN_INK_RIGHT + BOX_PAD) * BrandMarkPaths.EM,
            (BrandMarkPaths.SLOGAN_INK_BOTTOM + BOX_PAD) * BrandMarkPaths.EM
        )
        val r = BOX_RADIUS * BrandMarkPaths.EM
        boxPaint.color = BOX_COLOR
        canvas.drawRoundRect(box, r, r, boxPaint)

        val white = BrandMarkPaths.SLOGAN_WHITE_COUNT
        for (i in sloganPaths.indices) {
            /* 前两个字从原点起按 1em 步进；后四个整体挪到灰块的左内边 */
            val isWhite = i < white
            val x = if (isWhite) i * BrandMarkPaths.EM
                    else (SLOGAN_TAIL_X + (i - white)) * BrandMarkPaths.EM
            val s = canvas.save()
            canvas.translate(x, 0f)
            canvas.drawPath(sloganPaths[i], if (isWhite) paint else darkPaint)
            canvas.restoreToCount(s)
        }
        canvas.restoreToCount(save)
    }

    /**
     * 把 `glyph_out.py` 生成的路径串解成 [Path]。
     *
     * 只认 M / L / Q / Z 四个命令，而且是**绝对整数坐标** —— 生成器就只产这四种，
     * 所以不需要通用 SVG 路径解析器（也就不需要 androidx 的 PathParser，
     * 那个类在不同版本间 API 变过好几次，靠它反而容易出意外）。
     *
     * `M` 后面如果跟着多组坐标，按 SVG 规则当成隐式的 `L` 续写。
     */
    private fun parsePath(d: String): Path {
        val p = Path()
        val toks = brandPathTokens(d)
        var i = 0
        var cmd = 'Z'

        /* 取一个操作数。序列不规范时宁可给 0 也不抛异常 ——
           抛出去整张水印就没了，而画歪一点至少还看得见。 */
        fun n(): Float {
            if (i >= toks.size) return 0f
            val t = toks[i]
            if (t[0].isLetter()) return 0f
            i++
            return t.toFloat()
        }

        while (i < toks.size) {
            val t = toks[i]
            if (!t[0].isLetter()) {
                /* 悬空数字：只有 L / Q 会带操作数，其余说明数据对不上，跳过别死循环 */
                when (cmd) {
                    'L' -> p.lineTo(n(), n())
                    'Q' -> {
                        val qx = n(); val qy = n()
                        p.quadTo(qx, qy, n(), n())
                    }
                    else -> i++
                }
                continue
            }
            cmd = t[0]
            i++
            when (cmd) {
                'Z' -> p.close()
                /* M 之后的多组坐标按 SVG 规则是隐式的 L，所以立刻把 cmd 改成 L */
                'M' -> {
                    p.moveTo(n(), n())
                    cmd = 'L'
                }
                'L', 'Q' -> Unit          // 操作数在下一轮循环里吃
                else -> return p          // 认不出的命令：停下，别把后面的数字当坐标硬塞
            }
        }
        return p
    }
}

/**
 * 路径串 → 记号序列（`M` / `L` / `Q` / `Z` 与整数交替）。
 *
 * 刻意做成**不碰 android.graphics 的纯字符串函数**：这样可以在普通 JVM 测试里
 * 直接跑（不起 Robolectric，几秒就出结果），数据写坏了立刻能发现。
 * 之前那版是手写的逐字符扫描器，出问题时要等一整个 Robolectric 测试类跑完
 * 才知道结果 —— 排查一轮十几分钟，代价太大。
 */
internal val BRAND_PATH_TOKEN = Regex("[MLQZ]|-?\\d+")

internal fun brandPathTokens(d: String): List<String> =
    BRAND_PATH_TOKEN.findAll(d).map { it.value }.toList()
