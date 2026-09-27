package com.daka.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 水印绘制。所有尺寸以「1080 宽度」为基准做归一化（s = 宽 / 1080），
 * 因此不管出图是 1440x1920 还是 1080x1440、也不管预览多大，
 * 水印的相对位置和比例完全一致 —— 预览与成图所见即所得。
 *
 * 参数与已验证的 Web 版（watermark-camera/index.html）逐条对应，改动请两边同步。
 */
object WatermarkRenderer {

    private const val BASE = 1080f

    /**
     * 水印距画面**左/右/下**三边的边距（1080 基准）。三者共用一个值。
     *
     * 从 40 收到 26（-35%）是照着"再靠边一点、再靠下一点"改的。
     * 原来 40 是按网页版的呼吸感定的，但网页版是**看**的（图片周围是浏览器留白），
     * 相机是**拍**的 —— 成图四周没有留白可言，水印该像官方水印那样压到边上去一点。
     *
     * 不分开成"左右一个、下方一个"：这个值同时决定左边正文块的起点、右边品牌区的终点、
     * 以及整个下方块的基线（`h - pad`）。拆成两个值就会出现"左右收窄了、底边还是老位置"
     * 或者反过来的半吊子状态，改版式时还得记住两个数的关系。
     *
     * 注意：卡片内部那几个数（[CARD_IP] / [TAG_PAD_X] / [UNIT_EM] …）是**别的东西**，
     * 别跟着改 —— 它们已经在下面各自命名了，就是为了避免"看见 40 就一起动"。
     */
    /* internal 而非 private：单测要按同一个数算期望值，写死一份迟早对不上 */
    internal const val PAD = 26f

    /**
     * 「打卡」标签的配色：琥珀黄底 + 深色字。
     * 黄底上压白字对比度只有 1.9:1，基本看不清，所以字必须跟着改深色。
     */
    private const val LABEL_BG = 0xFFFFC107.toInt()
    private const val LABEL_FG = 0xFF14181D.toInt()
    private const val DARK = 0xFF14181D.toInt()

    /**
     * 卡片高度（1080 基准）。从 150 压到 116：参考图里这条白条相对画面宽度只有约 10% 高，
     * 150 会显得又高又空，时间字反而显小。
     *
     * **116 不动。** 它是照参考图的比例定下来的（116/1080 ≈ 10.7%），高度本身是对的；
     * 「看着像一条长横幅」的成因在**横向**——宽度 4.9 倍于高度。所以收窄卡片只动下面的
     * 横向那几个值，纵向一个都不碰，宽高比才会真的改善（4.94 → 4.44）。
     */
    private const val CARD_H = 116f

    /* ---------- 卡片内部尺寸（1080 基准）：收窄卡片全靠这几个数 ---------- */

    /** 卡片左右内边距（20 → 16） */
    private const val CARD_IP = 16f

    /** 「打卡」标签与时间之间（24 → 20） */
    private const val CARD_GAP_IN = 20f

    /** 时间与右侧块之间（24 → 20） */
    private const val CARD_GAP_RIGHT = 20f

    /** 「打卡」标签的字号（42 → 38）与左右内边距（40 → 32） */
    private const val TAG_EM = 38f
    private const val TAG_PAD_X = 32f

    /** 卡片右侧那块单位文字的字号（没 Logo 时才用得上） */
    private const val UNIT_EM = 40f

    /**
     * 卡片里那串时间大字的基准字号（1080 基准）。
     *
     * 78 → 68：**卡片宽度的最大一块就是它**（1440 宽下时间占约 268px、整卡约 764px）。
     * 卡片是按内容自适应的、没有多余留白，所以「太宽」这个观感只能从字号上收 ——
     * 内边距 + 标签 + 两处间隙这几项全加起来也就 60px 上下。
     *
     * 68 之后时间仍是卡片里最大的东西（相对地点行 42 是 1.6 倍），层级没塌。
     *
     * internal 而非 private：单测要拿它当「没缩过」的基准值，
     * 原来测试里写死的 `104f`（= 78 × s）改主代码时不报错、只会悄悄量错东西。
     */
    internal const val TIME_EM = 68f

    private val WEEK = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /**
     * 一行水印文字。
     *
     * [label] 是行首的彩色小标签（`备注` / `打卡人`）。加它的原因很实际：
     * 之前两行都是 34f 常规体纯白字，唯一区别只有「打卡人：」这个前缀，
     * 在图上根本分不出哪行是哪行。有了琥珀黄的标签，层级一眼可辨，
     * 而且不用把「备注」「打卡人」这几个字塞进正文里、变成内容的一部分。
     *
     * [kind] 说明这一行是干什么的 —— 只有地点行和日期行能被点开编辑（见 [hitTest]）。
     * 靠文字内容反推是行不通的：地点是用户随手填的，日期文案也可能被改。
     */
    private class Line(
        val text: String,
        val size: Float,
        val bold: Boolean,
        val label: String? = null,
        val kind: Int = KIND_TEXT
    )

    /* internal 而非 private：单测要拿它断言「地点行的标记确实是 ADDR」 */
    internal const val KIND_TEXT = 0
    internal const val KIND_ADDR = 1
    internal const val KIND_DATE = 2

    /**
     * 防伪码那行的基准字号（1080 基准）—— 品牌区三行里最小的那一行。
     *
     * 直接由 [BrandMark.NAME_EM] 推出来，而不是再写一个独立的数：
     * 层级比 1 : 0.714 : **0.575** 是量出来的（23/40），品牌区是一个整体，
     * 以后调大小只该动 [BrandMark.NAME_EM] 一处 —— 上一轮就是先定了 40 再手算 23，
     * 两个数各自"看着对"、却没人保证它们还成比例。
     */
    internal const val BRAND_CODE_EM = BrandMark.NAME_EM * 0.575f

    /** 正文块与品牌区之间至少留的横向间隙（1080 基准） */
    private const val BRAND_GAP_X = 16f

    /**
     * 卡片与下方正文块之间的纵向间隙（1080 基准）。
     *
     * 28 → 14：卡片和地点/日期本来就是一组的（一个是时间条、一个是它下面的信息），
     * 28 那点空当加上正文行本身的上行留白，看着像两块各自为政的东西。
     *
     * **这个值只管"卡片底边到正文块顶边"**；卡片还额外让开品牌区那一块的高度
     * （见 [place]），而品牌区比正文块高，所以以前实际看到的间距比 28 还大一截。
     *
     * internal 而非 private：单测要按同一个数算"卡片离正文多远"的期望值。
     */
    internal const val GAP_CARD = 14f

    /** 正文块/卡片与品牌区之间的纵向间隙（1080 基准） */
    private const val GAP_BRAND = 18f

    /**
     * 命中判定往外放的一圈（1080 基准）。
     * 12 * s 在 1440 宽的画面上是 16px —— 手指的抖动差不多就是这个量级。
     */
    private const val HIT_SLACK = 12f

    /** 按下反馈的底色：半透明白。只在按住时出现，成图上没有 */
    private const val HIGHLIGHT_BG = 0x59FFFFFF.toInt()

    /**
     * 点水印上的哪一块。
     *
     * 地点和日期时间分开，是因为它们该开不同的编辑器；
     * [NONE] 表示这一下跟水印无关，浮层不该把事件吃掉（否则预览区就"死"了）。
     */
    enum class TapTarget { NONE, ADDR, DATETIME }

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?,
        /**
         * 按下的目标（只有预览浮层会传）。默认 [TapTarget.NONE] ——
         * 出图走 [ImageWatermarker]，永不传它，所以成图上不会有按下反馈那一层。
         */
        highlight: TapTarget = TapTarget.NONE
    ) {
        if (w <= 1f || h <= 1f) return

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)

        val g = measure(w, h, cfg, logo, now, loc, text)
        if (g.total <= 0f) return
        val pos = place(h, g, cfg.posTop)

        /* 渐变遮罩：保证白字在亮背景上也读得清 */
        val scrimH = min(h * 0.62f, pos.total + g.pad * 2.2f)
        val yStart = if (cfg.posTop) 0f else h - scrimH
        val yEnd = if (cfg.posTop) scrimH else h
        val colors = if (cfg.posTop) {
            intArrayOf(0xA8000000.toInt(), 0x00000000)
        } else {
            intArrayOf(0x00000000, 0xCC000000.toInt())
        }
        fill.shader = LinearGradient(0f, yStart, 0f, yEnd, colors, null, Shader.TileMode.CLAMP)
        fill.color = Color.WHITE
        canvas.drawRect(0f, yStart, w, yEnd, fill)
        fill.shader = null

        /* 按下反馈画在遮罩之上、文字之下 —— 盖在文字上就把字遮了 */
        if (highlight != TapTarget.NONE) drawHighlight(canvas, g, pos, highlight, fill)

        /* 顶部模式时品牌区在最上（右上角），其余顺序与底部模式一致 */
        if (cfg.posTop && g.brand != null) {
            drawBrand(canvas, g.brand, g.s, pos.brandTop, text, fill)
        }
        if (g.card != null) {
            drawCard(canvas, g.card, g.s, pos.cardTop, pad = g.pad, logo = logo, cfg = cfg, now = now, fill = fill, text = text)
        }
        if (g.lines.isNotEmpty()) {
            drawLines(canvas, g, pos.linesTop, text)
        }
        if (!cfg.posTop && g.brand != null) {
            drawBrand(canvas, g.brand, g.s, pos.brandTop, text, fill)
        }
    }

    /* ------------------- 几何：量一次，绘制与命中判定共用 ------------------- */

    /**
     * 量出来的尺寸。**位置不在这里** —— 位置由 [place] 按 [WatermarkConfig.posTop] 决定。
     *
     * 量一次、摆两套，是为了让「底部对齐」和「顶部堆叠」共用同一组字号；
     * 分成两处各量一遍的话，同一行字在两种模式下的宽度会悄悄漂开。
     */
    private class Geometry(
        val s: Float,
        val pad: Float,
        val cardH: Float,
        val card: CardLayout?,
        val brand: BrandLayout?,
        val lines: List<Line>,
        val lineFs: FloatArray,
        val lineLh: FloatArray,
        /** 每行实测总宽（含行首标签）—— 命中区靠它，别拿字号×字数估 */
        val lineW: FloatArray,
        /**
         * 每行**最终画在图上**的文案。缩到下限还放不下的行会在这里被截短（末尾带省略号）。
         *
         * 单独留一份，而不是往 [Line] 里回写：几何是"量一次、三处共用"的，
         * 往输入里塞输出，下一次 [measure] 就会拿着上一轮的截短结果再量一遍。
         */
        val lineShow: Array<String>,
        /** 每行顶部相对正文块顶部的偏移 */
        val lineDy: FloatArray,
        /**
         * 正文每行的宽度上界。留着它不是为了画图（画图看 [lineW]），
         * 而是为了让测试能断言「地点没伸进品牌区那一列」——
         * 没有这个数，就只能靠渲染图目测，而目测分不清"刚好贴边"和"压过去 3px"。
         */
        val maxTextW: Float,
        val textH: Float,
        val brandH: Float,
        /** 卡片与下方正文之间（两者都在时才有） */
        val gapCard: Float,
        /** 正文/卡片块与品牌区之间 */
        val gapBrand: Float,
        val total: Float
    )

    /** 三块各自的顶部 y。缺的那块是 0，用之前先看对应对象是不是 null */
    private class Placement(
        val linesTop: Float,
        val cardTop: Float,
        val brandTop: Float,
        val topMost: Float,
        val total: Float
    )

    /**
     * 量字号与各处间隙。绘制、命中判定、单测的几何断言都走这里 ——
     * 一份算式三个用途，改了不会只改到一半。
     *
     * 正文每行的最大宽度**先收在品牌区左边界之内，再收在卡片之内**：
     *
     * 1. 品牌区右对齐、正文左对齐，**底部对齐之后两者落在同一条水平带上** ——
     *    不加限制的话，长地名会直接压到品牌名上。原来靠"品牌区另起一段"躲开这个问题，
     *    现在两段并排了，就得靠宽度约束。
     * 2. 地点不该比上面那条时间条更宽（用户明确要的）。
     *
     * 两条都只是**上界**：够宽时字号一动不动，只有真超标的那一行才缩（下限 0.66 倍）。
     */
    private fun measure(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?,
        text: Paint
    ): Geometry {
        /*
         * 归一化基准取「短边」而不是宽度：
         * 竖屏时短边就是宽度，行为与旧版逐像素一致（零回归）；
         * 横屏时若仍按宽度算，s 会从 1.33 涨到 1.78，水印被放大近 40% 且糊出屏幕。
         */
        val s = min(w, h) / BASE
        val pad = PAD * s
        val cardH = if (cfg.showCard) CARD_H * s else 0f
        val lines = buildLines(cfg, now, loc)

        val brand = measureBrand(w, s, cfg, now, loc, text)
        val brandH = brand?.h ?: 0f

        /* 上界一：不许伸进品牌区那一列 */
        val brandLeft = if (brand != null) brand.right - brand.w else w - pad
        val card = if (cardH > 0f) measureCard(w, s, cardH, pad, logo, cfg, now, text) else null
        /* 上界二：不超过卡片。关掉卡片时退回旧行为（整幅内容区宽） */
        /*
         * 卡片这条上界只在**卡片真有时间大字**时才算数：
         * 用户的原话是「地点不要超过上面的时间条」，对齐的对象是那条时间；
         * 而卡片宽度是内容自适应的，关掉时间之后它会缩到只剩「打卡」标签那么宽
         * （1440 宽下约 219px），照它去卡地点会把正常的短地名也逼到最小字号。
         */
        val timeBarW = if (card != null && cfg.showTime) card.w else null
        val maxTextW = min(brandLeft - BRAND_GAP_X * s - pad, timeBarW ?: (w - pad * 2f))
            .coerceAtLeast(1f)

        /*
         * 每行各自缩到「放得下」为止。这是「地点那行太长」的根治办法：
         * 以前长地名会直接铺到画面外（右边被切掉），现在只缩这一行的字号，
         * 版面骨架、行距关系全都不动，只有超标的那行变小。
         *
         * 缩到 [MIN_LINE_SCALE] 还放不下就**改截字**（见 [fitLineText]）——
         * 一直缩下去的结果是"字都在、但谁都看不见"，不如留几个字看得清清楚楚。
         */
        val fs = FloatArray(lines.size) { fitLineSize(text, lines[it], s, maxTextW) }
        val show = Array(lines.size) { fitLineText(text, lines[it], fs[it], s, maxTextW) }
        val lh = FloatArray(lines.size) { fs[it] * 1.34f }
        val lw = FloatArray(lines.size) { lineWidth(text, lines[it], show[it], fs[it], s) }
        val dy = FloatArray(lines.size)
        for (i in 1 until lines.size) dy[i] = dy[i - 1] + lh[i - 1]
        val textH = lh.fold(0f) { a, b -> a + b }

        val hasBody = card != null || lines.isNotEmpty()
        return Geometry(
            s = s, pad = pad, cardH = cardH, card = card, brand = brand,
            lines = lines, lineFs = fs, lineLh = lh, lineW = lw, lineShow = show, lineDy = dy,
            maxTextW = maxTextW, textH = textH, brandH = brandH,
            gapCard = if (card != null && lines.isNotEmpty()) GAP_CARD * s else 0f,
            gapBrand = if (brand != null && hasBody) GAP_BRAND * s else 0f,
            total = cardH + (if (card != null && lines.isNotEmpty()) GAP_CARD * s else 0f) +
                textH + (if (brand != null && hasBody) GAP_BRAND * s else 0f) + brandH
        )
    }

    /**
     * 按 [top] 把三块摆到画布上。
     *
     * 底部模式**以 `h - pad` 为共同基线**：正文块和品牌区各自按自己的高度往上让，
     * 于是两者**底边严格对齐**（要的就是这个 —— 原来品牌区被摆在正文下方，
     * 防伪码那行比日期低一截，看着像没对齐）。
     *
     * 卡片则贴在正文块顶上（间隔 [GAP_CARD]）。**只在横向真的撞上时**才额外让开品牌区：
     * 卡片从左边起、品牌区贴右边界，正常内容下两者的横向区间根本不重合，
     * 让开就是白让 —— 品牌区越高卡片被顶得越高，离下面的地点/日期反而越远。
     */
    private fun place(h: Float, g: Geometry, top: Boolean): Placement {
        if (top) {
            val brandTop = if (g.brand != null) g.pad else 0f
            var y = g.pad
            if (g.brand != null) y += g.brandH + g.gapBrand
            val cardTop = if (g.card != null) y else 0f
            if (g.card != null) y += g.cardH + g.gapCard
            val linesTop = if (g.lines.isNotEmpty()) y else 0f
            val bottomMost = when {
                g.lines.isNotEmpty() -> linesTop + g.textH
                g.card != null -> cardTop + g.cardH
                g.brand != null -> brandTop + g.brandH
                else -> g.pad
            }
            return Placement(linesTop, cardTop, brandTop, g.pad, bottomMost - g.pad)
        }

        val bottom = h - g.pad
        val linesTop = if (g.lines.isNotEmpty()) bottom - g.textH else bottom
        val brandTop = if (g.brand != null) bottom - g.brandH else bottom
        var cardTop = 0f
        if (g.card != null) {
            /*
             * 卡片要躲的不是「品牌区在不在」，而是「品牌区**挡不挡路**」。
             *
             * 卡片从左边起、品牌区贴着右边界；正常内容下两者的横向区间根本不重合
             * （1440 宽下卡片右边界约 721、品牌区左边界约 1022，中间隔着 300px）。
             * 以前一律按 min(linesTop, brandTop) 让开，等于为一块不在同一列的东西白让一次，
             * 而且**品牌区越高、卡片被顶得越高、离下面的地点/日期就越远** ——
             * 品牌区刚被调大，这个副作用只会更明显（正是这次要修的那个"太远"）。
             *
             * 只有卡片真伸进品牌区那一列（超长单位名把卡片撑宽）时才照旧纵向避让。
             */
            val brandInTheWay = g.lines.isNotEmpty() && g.brand != null &&
                (g.card.x + g.card.w) > g.brand.right - g.brand.w
            val above = when {
                brandInTheWay -> min(linesTop, brandTop)
                g.lines.isNotEmpty() -> linesTop
                else -> brandTop
            }
            val gap = if (g.lines.isNotEmpty()) g.gapCard else g.gapBrand
            cardTop = above - gap - g.cardH
        }
        var topMost = bottom
        if (g.card != null) topMost = min(topMost, cardTop)
        if (g.lines.isNotEmpty()) topMost = min(topMost, linesTop)
        if (g.brand != null) topMost = min(topMost, brandTop)
        return Placement(linesTop, cardTop, brandTop, topMost, bottom - topMost)
    }

    /**
     * 点到水印上的哪一块。坐标是**画布本地坐标**（左上角为原点），
     * 也就是浮层自己的坐标系 —— MainActivity 保证浮层的尺寸就是相机画面的显示矩形。
     *
     * 命中区每边比看到的再放一圈（[HIT_SLACK]）：小字号下手指差十几个像素太正常，
     * 点不中只会让人以为"这功能没有"，而不是"我偏了一点"。
     *
     * 顺带把「品牌区整块」也放进来做未来扩展的落点？**不放** ——
     * 宁可不响应，也不要在用户想点品牌名时弹出一个跟品牌无关的编辑器。
     */
    internal fun hitTest(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?,
        x: Float,
        y: Float
    ): TapTarget {
        if (w <= 1f || h <= 1f) return TapTarget.NONE
        val g = measure(w, h, cfg, logo, now, loc, Paint(Paint.ANTI_ALIAS_FLAG))
        if (g.total <= 0f) return TapTarget.NONE
        val pos = place(h, g, cfg.posTop)
        val slack = HIT_SLACK * g.s

        for (i in g.lines.indices) {
            val t = pos.linesTop + g.lineDy[i]
            if (x >= g.pad - slack && x <= g.pad + g.lineW[i] + slack &&
                y >= t - slack && y <= t + g.lineLh[i] + slack
            ) {
                when (g.lines[i].kind) {
                    KIND_ADDR -> return TapTarget.ADDR
                    KIND_DATE -> return TapTarget.DATETIME
                }
            }
        }

        /* 卡片里那串时间大字。量的是时间本身的宽度，不含标签和右侧的 Logo/单位 */
        val c = g.card
        if (c != null && c.timeW > 0f) {
            val left = c.x + c.ip + c.tagW + c.gapIn
            if (x >= left - slack && x <= left + c.timeW + slack &&
                y >= pos.cardTop - slack && y <= pos.cardTop + g.cardH + slack
            ) return TapTarget.DATETIME
        }
        return TapTarget.NONE
    }

    /**
     * 按下反馈：在被点的那一块后面垫一层半透明圆角底。
     *
     * 没有它，用户不会知道水印上的地点和时间可以点 —— 一个看不见的入口等于没有。
     * 它只在手指按住时出现，松手就没；出图那条路（[ImageWatermarker]）不传 highlight，
     * 所以成图上永远不会有这一层。
     */
    private fun drawHighlight(
        canvas: Canvas,
        g: Geometry,
        pos: Placement,
        target: TapTarget,
        fill: Paint
    ) {
        fill.shader = null
        fill.color = HIGHLIGHT_BG
        val slack = HIT_SLACK * g.s * 0.5f
        val r = 12f * g.s
        for (i in g.lines.indices) {
            val t = when (g.lines[i].kind) {
                KIND_ADDR -> TapTarget.ADDR
                KIND_DATE -> TapTarget.DATETIME
                else -> TapTarget.NONE
            }
            if (t != target) continue
            val top = pos.linesTop + g.lineDy[i]
            canvas.drawRoundRect(
                g.pad - slack, top - slack,
                g.pad + g.lineW[i] + slack, top + g.lineLh[i] + slack,
                r, r, fill
            )
        }
        val c = g.card
        if (target == TapTarget.DATETIME && c != null && c.timeW > 0f) {
            val left = c.x + c.ip + c.tagW + c.gapIn
            canvas.drawRoundRect(
                left - slack, pos.cardTop - slack,
                left + c.timeW + slack, pos.cardTop + g.cardH + slack,
                r, r, fill
            )
        }
    }

    /** 画正文块：每行按 [Geometry.lineDy] 落位，基线取行高的 0.78 */
    private fun drawLines(canvas: Canvas, g: Geometry, top: Float, text: Paint) {
        val save = canvas.save()
        text.setShadowLayer(11f * g.s, 0f, 2f * g.s, 0xB3000000.toInt())
        text.textAlign = Paint.Align.LEFT
        for (i in g.lines.indices) {
            val l = g.lines[i]
            val fs = g.lineFs[i]
            val yy = top + g.lineDy[i] + g.lineLh[i] * 0.78f
            var x = g.pad
            if (l.label != null) {
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = fs
                text.color = LABEL_BG
                canvas.drawText(l.label, x, yy, text)
                x += text.measureText(l.label) + 12f * g.s
            }
            text.typeface = if (l.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = fs
            text.color = Color.WHITE
            canvas.drawText(g.lineShow[i], x, yy, text)
        }
        text.clearShadowLayer()
        canvas.restoreToCount(save)
    }

    /**
     * 一行的实际宽度（含行首标签与标签后的间隔），单位与画布一致。
     *
     * [value] 单独传而不直接用 [Line.text]：截字要量的正是"截短之后的那个候选文案"。
     */
    private fun lineWidth(p: Paint, l: Line, value: String, fs: Float, s: Float): Float {
        var x = 0f
        if (l.label != null) {
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = fs
            x += p.measureText(l.label) + 12f * s
        }
        p.typeface = if (l.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textSize = fs
        return x + p.measureText(value)
    }

    /**
     * 正文行缩字号时的**终点**（相对名义字号）。缩到这里还放不下，就改为截字，
     * 不再往下缩。
     *
     * 0.36 → 0.80：0.36 是"缩到放得下为止"那套思路的产物，实际操作下来 29 个字的地名
     * 会被缩到 22.7px（1440 宽的图上）—— 字倒是全在，可已经不像水印上该有的字号了。
     * 用户的原话是「要不就不缩那么小了，小区名字截取几个字算了」：
     * 于是把缩字号收成"小幅微调"（最多小两成），再放不下就归 [fitLineText] 去截字。
     *
     * 这两个值是一对：**先缩、缩不动了才截**。顺序反过来的话，
     * 稍微长一点的地名（13 个字）会先被截掉一个字，而不是整体小 6% —— 那显然更差。
     */
    internal const val MIN_LINE_SCALE = 0.80f

    /** 截断标记。中文排版用「…」而不是「...」——后者在等宽/比例字体下宽度不一致 */
    private const val ELLIPSIS = "…"

    /** 放得下就用名义字号，放不下逐磅缩 —— 缩到 [MIN_LINE_SCALE] 为止，再放不下交给 [fitLineText] 截字 */
    private fun fitLineSize(p: Paint, l: Line, s: Float, maxW: Float): Float {
        val nominal = l.size * s
        val floor = nominal * MIN_LINE_SCALE
        var fs = nominal
        while (fs > floor && lineWidth(p, l, l.text, fs, s) > maxW) fs -= 1f * s
        /* 步长是 1*s，最后一步会掉到 floor 下面去一点 —— 夹回来，下限才是下限 */
        return max(fs, floor)
    }

    /**
     * 在**当前字号**下截到放得下为止：从尾巴一个字一个字往外扔，末尾补「…」。
     *
     * 为什么截尾而不是截头：地址是层级信息 —— 「省市区街道」决定这是哪儿，
     * 「几栋几单元」只是细节，尾巴掉的损失最小。所以留前缀。
     *
     * 标签（`备注` / `打卡人`）不参与截断：它是行的用途标记，截掉了整行就看不出是什么。
     */
    private fun fitLineText(p: Paint, l: Line, fs: Float, s: Float, maxW: Float): String {
        if (l.text.isEmpty()) return l.text
        if (lineWidth(p, l, l.text, fs, s) <= maxW) return l.text
        for (n in l.text.length - 1 downTo 1) {
            /* 别从代理对中间劈开（emoji 会画成一个空心方框） */
            if (l.text[n - 1].isHighSurrogate()) continue
            val cand = l.text.substring(0, n) + ELLIPSIS
            if (lineWidth(p, l, cand, fs, s) <= maxW) return cand
        }
        return ELLIPSIS
    }

    /**
     * 一行的**一段**。会分段只有一个原因：字体。
     *
     * 防伪码那行是「中文标签 + 等宽数字」，而 PT Mono 没有中文字形 ——
     * 所以中文用系统字体、后面那 14 位用等宽，各画各的。
     */
    private class BrandSeg(val text: String, val mono: Boolean)

    /**
     * 品牌区的一行。三行各自字号不同，所以尺寸跟着行走，不跟块走。
     *
     * [art] 非 0 时这一行走**矢量稿**（见 [BrandMark]），[segs] 只是给人看/给测试用的
     * 文本表示：矢量稿与文本稿的宽度算法完全不同，不能混在一起量。
     */
    private class BrandRow(
        val segs: List<BrandSeg>,
        val size: Float,
        val bold: Boolean,
        val art: Int = ART_NONE
    ) {
        val text: String get() = segs.joinToString("") { it.text }
    }

    /**
     * 品牌区（右下角 / 右上角）：**三行右对齐**，字号逐行变小 ——
     * 品牌名 → 标语 → 防伪码。
     *
     * 为什么不排一行：三样东西性质完全不同（主标识 / 说明 / 校验码），
     * 挤成一行会变成一串读不出重点的长字（"今日水印  相机真实可验  防伪码 XXXX"）。
     * 分行 + 递减字号本身就是层级，一眼能分清哪是名字、哪是那串码。
     *
     * 三行**各自的字号逐行缩**（30 / 22 / 17 基准，照参考图的比例取的）：
     * 只有超长的那行会变小，层级关系不会被一张长标语搅乱。
     */
    private class BrandLayout(
        val right: Float,
        val h: Float,
        val rows: List<BrandRow>,
        val sizes: FloatArray,
        /** 每行的**实测总宽**（分段各量各的再相加），右对齐要靠它反推左起点 */
        val widths: FloatArray,
        val lh: FloatArray,
        val w: Float
    )

    private fun measureBrand(
        w: Float,
        s: Float,
        cfg: WatermarkConfig,
        now: Calendar,
        loc: LocInfo?,
        text: Paint
    ): BrandLayout? {
        if (!cfg.showBrand) return null

        val pad = PAD * s
        val maxW = w - pad * 2f

        val rows = ArrayList<BrandRow>(3)
        /*
         * 品牌名/标语是**用户能改**的，而矢量稿只有内置那一份（10 个字就 3KB，
         * 不可能把任意中文都做进去）。所以规则是：文字没被改过 → 走矢量；
         * 改过 → 整行退回字体渲染。判断放在行级，改标语不影响品牌名那行走矢量。
         *
         * 注意：走矢量时 **segs 仍然要填** —— 它负责 [BrandRow.text]，
         * 测试与调试都靠它读"这一行是什么"。宽度不由它算（见 [rowWidth]），
         * 所以留着不会让右对齐偏掉。
         */
        cfg.brandName.trim().takeIf { it.isNotBlank() }?.let {
            rows.add(
                if (it == BrandMark.NAME) {
                    BrandRow(listOf(BrandSeg(it, false)), BrandMark.NAME_EM * s, true, ART_NAME)
                } else {
                    /* 改过名字就退回字体渲染，但字号跟矢量稿保持一致 —— 换字体不该顺手换大小 */
                    BrandRow(listOf(BrandSeg(it, false)), BrandMark.NAME_EM * s, true)
                }
            )
        }
        cfg.brandSlogan.trim().takeIf { it.isNotBlank() }?.let {
            rows.add(
                if (it == BrandMark.SLOGAN) {
                    BrandRow(listOf(BrandSeg(it, false)), BrandMark.SLOGAN_EM * s, false, ART_SLOGAN)
                } else {
                    BrandRow(listOf(BrandSeg(it, false)), BrandMark.SLOGAN_EM * s, false)
                }
            )
        }
        if (cfg.showCode) {
            val code = AntiFake.code(cfg, now, loc)
            if (code.isNotBlank()) {
                rows.add(
                    BrandRow(
                        /* 标签就两个字「防伪」—— 后面跟的就是码本身，再写一个「码」字是重复。 */
                        listOf(BrandSeg("防伪 ", false), BrandSeg(code, true)),
                        BRAND_CODE_EM * s,
                        false
                    )
                )
            }
        }
        if (rows.isEmpty()) return null

        val sizes = FloatArray(rows.size) { fitBrandRow(text, rows[it], s, maxW) }
        val widths = FloatArray(rows.size) { rowWidth(text, rows[it], sizes[it]) }
        val lh = FloatArray(rows.size) { sizes[it] * 1.32f }

        return BrandLayout(
            right = w - pad,
            h = lh.fold(0f) { a, b -> a + b },
            rows = rows,
            sizes = sizes,
            widths = widths,
            lh = lh,
            w = widths.maxOrNull() ?: 0f
        )
    }

    /** 一段用哪个字体：等宽段走 [Typefaces]，其余按粗细取系统字体 */
    private fun segTypeface(seg: BrandSeg, bold: Boolean): Typeface =
        if (seg.mono) Typefaces.monoForDraw
        else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    /**
     * 一行文字的实测总宽：**逐段按各自字体量完再相加**。
     *
     * 不能笼统地用「字数 × 字号」估 —— 等宽段和中文段的度量方式根本不同，
     * 估出来的宽度会让右对齐偏掉几个像素，右边那截就直接出画面了。
     */
    private fun rowWidth(p: Paint, row: BrandRow, fs: Float): Float {
        /* 矢量行不走字体度量：它的宽度写在 BrandMark 里（含字距和灰块留白），
           用 measureText 量出来的一定是错的 —— 那行根本没有文字可量。 */
        when (row.art) {
            ART_NAME -> return BrandMark.nameWidth(fs)
            ART_SLOGAN -> return BrandMark.sloganWidth(fs)
        }
        var w = 0f
        for (seg in row.segs) {
            p.typeface = segTypeface(seg, row.bold)
            p.textSize = fs
            w += p.measureText(seg.text)
        }
        return w
    }

    /** 单行放不下就缩它自己，下限 0.62 倍基准且不小于 13s —— 再小就没法读了 */
    private fun fitBrandRow(p: Paint, row: BrandRow, s: Float, maxW: Float): Float {
        val nominal = row.size
        var fs = nominal
        while (fs > nominal * 0.62f && fs > 13f * s) {
            if (rowWidth(p, row, fs) <= maxW) break
            fs -= 1f * s
        }
        return fs
    }

    private fun drawBrand(
        canvas: Canvas,
        b: BrandLayout,
        s: Float,
        y: Float,
        text: Paint,
        fill: Paint
    ) {
        /*
         * 右对齐得靠"先算行宽、再从左边界起画"来实现 —— 分段绘制用不了
         * Paint.Align.RIGHT（对齐是整段对齐，段与段之间会各对各的）。
         */
        text.textAlign = Paint.Align.LEFT
        text.setShadowLayer(11f * s, 0f, 2f * s, 0xB3000000.toInt())
        /* 矢量稿也吃同一层阴影，否则字是贴上去的，背景一乱就浮不起来 */
        fill.setShadowLayer(11f * s, 0f, 2f * s, 0xB3000000.toInt())
        /* 灰块是实心浅色，同样要影子才立得起来 */
        boxPaint.setShadowLayer(11f * s, 0f, 2f * s, 0xB3000000.toInt())
        var yy = y
        for (i in b.rows.indices) {
            val row = b.rows[i]
            val baseline = yy + b.lh[i] * 0.78f
            val left = b.right - b.widths[i]
            /* 越往下越淡：主名最亮，防伪码只要看得清、不该跟地点抢视线 */
            val alpha = when (i) {
                0 -> Color.WHITE
                1 -> 0xF0FFFFFF.toInt()
                else -> 0xD8FFFFFF.toInt()
            }
            when (row.art) {
                ART_NAME -> {
                    fill.color = alpha
                    BrandMark.drawName(canvas, b.right, baseline, b.sizes[i], fill)
                }
                ART_SLOGAN -> {
                    fill.color = alpha
                    /* 灰块用一支独立画笔：它是实心浅色，不跟着「越往下越淡」那套透明度走，
                       块里的深字由 BrandMark 内部的画笔负责（不带阴影）。 */
                    BrandMark.drawSlogan(canvas, b.right, baseline, b.sizes[i], fill, boxPaint)
                }
                else -> {
                    text.textSize = b.sizes[i]
                    text.color = alpha
                    var x = left
                    for (seg in row.segs) {
                        text.typeface = segTypeface(seg, row.bold)
                        canvas.drawText(seg.text, x, baseline, text)
                        x += text.measureText(seg.text)
                    }
                }
            }
            yy += b.lh[i]
        }
        text.clearShadowLayer()
        fill.clearShadowLayer()
        boxPaint.clearShadowLayer()
    }

    /*
     * 矢量行的行标。做成常量而不是枚举，是因为 BrandLayout 会被测试直接读，
     * 枚举值在测试里要 import，多一层噪音。
     */
    private const val ART_NONE = 0
    private const val ART_NAME = 1
    private const val ART_SLOGAN = 2

    /** 灰块专用画笔：块和块里的深字都不走"越往下越淡"，所以不能复用 [fill] 的颜色 */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private class CardLayout(
        /** 左边距。**不含纵坐标** —— 卡片纵向落在哪由 [place] 算、经参数传给 [drawCard] */
        val x: Float,
        val w: Float,
        val h: Float,
        val ip: Float,
        val radius: Float,
        val gapIn: Float,
        val title: String,
        val tagW: Float,
        val tagH: Float,
        val tagFs: Float,
        val rightW: Float,
        val logoDH: Float,
        val unitFs: Float,
        val timeFs: Float,
        val timeW: Float
    )

    /**
     * 卡片宽度**按内容自适应**，不再撑满整行 —— 对齐参考图（今日水印相机）的版式：
     * 一条贴住内容的紧凑白条，而不是一条从左通到右的长横幅。
     *
     * 内容宽 = 内边距 + 标签 + 时间 + 右侧块 + 内边距；
     * 超出 maxW 时先缩时间字号（下限 30s），而不是让卡片溢出屏幕。
     *
     * 测量与绘制拆开，是为了让测试能直接断言「卡片宽 < 画面宽 - 2*pad」，
     * 这比盯着 PNG 目测靠得住。
     */
    private fun measureCard(
        w: Float,
        s: Float,
        cardH: Float,
        pad: Float,
        logo: Bitmap?,
        cfg: WatermarkConfig,
        now: Calendar,
        text: Paint
    ): CardLayout {
        val cardX = pad
        val maxW = w - pad * 2f
        val ip = CARD_IP * s
        val radius = 18f * s
        val gapIn = CARD_GAP_IN * s
        val gapRight = CARD_GAP_RIGHT * s

        /* 1) 黄色标签：高度贴着卡片（上下各留 8s），接近参考图的方角块 */
        val title = cfg.cardTitle.trim().ifBlank { "打卡" }
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = TAG_EM * s
        val tagW = text.measureText(title) + TAG_PAD_X * s
        val tagH = cardH - 16f * s

        /* 2) 右侧块：Logo 图优先，否则单位文字。宽度上限看 maxW，不再依赖整行宽 */
        val maxRight = maxW * 0.40f
        var rightW = 0f
        var logoDH = 0f
        var unitFs = 0f
        if (logo != null && logo.width > 0 && logo.height > 0) {
            val maxH = cardH - 32f * s
            val ar = logo.width.toFloat() / logo.height.toFloat()
            var dh = maxH
            var dw = dh * ar
            if (dw > maxRight) {
                dw = maxRight
                dh = dw / ar
            }
            rightW = dw
            logoDH = dh
        } else if (cfg.unit.isNotBlank()) {
            var fs = UNIT_EM * s
            while (true) {
                text.textSize = fs
                if (text.measureText(cfg.unit) <= maxRight || fs <= 20f * s) break
                fs -= 2f * s
            }
            unitFs = fs
            rightW = min(text.measureText(cfg.unit), maxRight)
        }

        /* 3) 时间字号：缩到「卡片宽刚好放得下」为止 —— 这是自适应宽度的关键一步 */
        val t = timeText(cfg, now)
        var timeFs = 0f
        var timeW = 0f
        if (cfg.showTime) {
            timeFs = TIME_EM * s
            while (true) {
                text.textSize = timeFs
                timeW = text.measureText(t)
                val cw = contentWidth(ip, tagW, gapIn, timeW, rightW, gapRight)
                if (cw <= maxW || timeFs <= 30f * s) break
                timeFs -= 2f * s
            }
        }

        val cardW = min(contentWidth(ip, tagW, gapIn, timeW, rightW, gapRight), maxW)
        return CardLayout(
            x = cardX, w = cardW, h = cardH, ip = ip, radius = radius,
            gapIn = gapIn, title = title, tagW = tagW, tagH = tagH, tagFs = TAG_EM * s,
            rightW = rightW, logoDH = logoDH, unitFs = unitFs, timeFs = timeFs, timeW = timeW
        )
    }

    /**
     * 画卡片。**尺寸是量好传进来的**（[measure] 已经量过一次）。
     *
     * 原来这里自己再 `measureCard` 一遍：两处各量一次，只要有一处的入参不同
     * （比如宽度上界算得不一样），画出来的卡片和按 [cardMetrics] 断言的就不是同一个东西，
     * 而两者看上去都"对"。现在只量一次，[CardLayout.x] 是左边距、[cardY] 是这次落位算出的顶部。
     */
    private fun drawCard(
        canvas: Canvas,
        L: CardLayout,
        s: Float,
        cardY: Float,
        pad: Float,
        logo: Bitmap?,
        cfg: WatermarkConfig,
        now: Calendar,
        fill: Paint,
        text: Paint
    ) {
        /* 底：白卡片 */
        fill.shader = null
        fill.color = 0xF7FFFFFF.toInt()
        fill.setShadowLayer(20f * s, 0f, 5f * s, 0x6B000000)
        canvas.drawRoundRect(L.x, cardY, L.x + L.w, cardY + L.h, L.radius, L.radius, fill)
        fill.clearShadowLayer()

        /* 黄色标签 */
        val tagX = L.x + L.ip
        val tagY = cardY + (L.h - L.tagH) / 2f
        fill.color = LABEL_BG
        canvas.drawRoundRect(tagX, tagY, tagX + L.tagW, tagY + L.tagH, 11f * s, 11f * s, fill)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = L.tagFs
        text.color = LABEL_FG
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(L.title, tagX + L.tagW / 2f, centerBaseline(text, tagY + L.tagH / 2f), text)

        /* 右侧块：贴卡片右内边距，而不是贴屏幕右边 */
        val rightEdge = L.x + L.w - L.ip
        if (logo != null && L.rightW > 0f) {
            val top2 = cardY + (L.h - L.logoDH) / 2f
            canvas.drawBitmap(
                logo, null,
                RectF(rightEdge - L.rightW, top2, rightEdge, top2 + L.logoDH), fill
            )
        } else if (L.rightW > 0f) {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = L.unitFs
            text.color = DARK
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(cfg.unit, rightEdge, centerBaseline(text, cardY + L.h / 2f), text)
        }

        /* 时间大字 */
        if (cfg.showTime) {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = L.timeFs
            text.color = DARK
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(
                timeText(cfg, now), tagX + L.tagW + L.gapIn,
                centerBaseline(text, cardY + L.h / 2f), text
            )
        }
    }

    private fun contentWidth(
        ip: Float,
        tagW: Float,
        gapIn: Float,
        timeW: Float,
        rightW: Float,
        gapRight: Float
    ): Float {
        var x = ip + tagW
        if (timeW > 0f) x += gapIn + timeW
        if (rightW > 0f) x += gapRight + rightW
        return x + ip
    }

    /** 让文字在竖直方向以 cy 为中线 */
    private fun centerBaseline(p: Paint, cy: Float): Float {
        val fm = p.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    private fun buildLines(cfg: WatermarkConfig, now: Calendar, loc: LocInfo?): List<Line> {
        val out = ArrayList<Line>(8)
        /* 关掉卡片时时间退化成一行文字，避免"关了卡片时间就没了" */
        if (cfg.showTime && !cfg.showCard) out.add(Line(timeText(cfg, now), 52f, true))
        /* 只有这两行能被点开编辑，标记随行走在 [Line.kind] 里 */
        if (cfg.showAddr && cfg.addr.isNotBlank()) {
            out.add(Line(cfg.addr, 42f, true, kind = KIND_ADDR))
        }
        if (cfg.showDate) out.add(Line(dateText(now), 36f, false, kind = KIND_DATE))
        if (cfg.showNote && cfg.note.isNotBlank()) {
            out.add(Line(cfg.note, 34f, false, label = "备注"))
        }
        if (cfg.showName && cfg.name.isNotBlank()) {
            out.add(Line(cfg.name, 34f, false, label = "打卡人"))
        }
        if (cfg.showCoord) {
            val t = if (loc != null) {
                /* 4 位小数 ≈ 11 米，打卡够用；6 位太占地方，一行顶过去 */
                String.format(
                    Locale.US, "%.4f°%s  %.4f°%s",
                    abs(loc.lat), if (loc.lat >= 0) "N" else "S",
                    abs(loc.lon), if (loc.lon >= 0) "E" else "W"
                )
            } else {
                "未取得定位"
            }
            out.add(Line(t, 32f, false))
        }
        /* 精度和海拔合成一行 —— 分开写会白占两行，而它们本来就属于同一件事 */
        val bits = ArrayList<String>(2)
        if (cfg.showAcc && loc != null) bits.add("±${loc.accuracy}m")
        if (cfg.showAlt && loc?.altitude != null) bits.add("海拔${Math.round(loc.altitude)}m")
        if (bits.isNotEmpty()) out.add(Line(bits.joinToString("  "), 30f, false))
        return out
    }

    /**
     * 供测试断言水印文案用（渲染图看不出小数点位数）。
     * 带标签的行按「标签 正文」拼出来 —— 这正是图上肉眼看到的顺序。
     */
    internal fun lineTexts(cfg: WatermarkConfig, now: Calendar, loc: LocInfo?): List<String> =
        buildLines(cfg, now, loc).map {
            if (it.label == null) it.text else it.label + " " + it.text
        }

    /** 品牌区三行文字（品牌名 / 标语 / 防伪码），供断言防伪码格式与行数 */
    internal fun brandTexts(cfg: WatermarkConfig, now: Calendar, loc: LocInfo?): List<String> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val b = measureBrand(BASE, 1f, cfg, now, loc, p) ?: return emptyList()
        return b.rows.map { it.text }
    }

    /**
     * 品牌区**逐行**的绘制宽度。
     *
     * 断言"矢量行走的是 BrandMark 的宽度"必须看单行 —— 整块宽是三行取最大，
     * 防伪码那行通常最宽，于是无论品牌名那行走没走矢量，整块宽都一样，
     * 断言就会"因为错误的原因通过"。
     */
    internal fun brandRowWidths(
        w: Float,
        s: Float,
        cfg: WatermarkConfig,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        return measureBrand(w, s, cfg, now, loc, p)?.widths ?: FloatArray(0)
    }

    /** 品牌区三行各自的**实际**字号，用来断言「逐行变小」这个层级确实成立 */
    internal fun brandRowSizes(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = min(w, h) / BASE
        return measureBrand(w, s, cfg, now, loc, p)?.sizes ?: FloatArray(0)
    }

    /**
     * 正文每一行的**中心点**，附带它是哪一类行：`[cx, cy, kind]`。
     *
     * 给测试用来真点一下。**不让测试自己算**（`pad + 字号 × 字数 / 2` 这类式子）——
     * 那样量的就不是渲染器认的那块矩形了，命中断言会变成"我算的点在我算的框里"，
     * 恒真。这里的 `cx/cy` 和 [hitTest] 判的是同一组数。
     */
    internal fun lineHitPoints(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ): List<FloatArray> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = measure(w, h, cfg, logo, now, loc, p)
        val pos = place(h, g, cfg.posTop)
        return g.lines.indices.map { i ->
            floatArrayOf(
                g.pad + g.lineW[i] / 2f,
                pos.linesTop + g.lineDy[i] + g.lineLh[i] / 2f,
                g.lines[i].kind.toFloat()
            )
        }
    }

    /**
     * 卡片里那串时间大字的中心点 `[cx, cy]`；没有时间大字时返回空数组。
     * 卡片的时间是**另一个**日期时间入口（和正文里的日期行等价），也要能点。
     */
    internal fun cardTimeHitPoint(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = measure(w, h, cfg, logo, now, loc, p)
        val c = g.card ?: return FloatArray(0)
        if (c.timeW <= 0f) return FloatArray(0)
        val pos = place(h, g, cfg.posTop)
        return floatArrayOf(
            c.x + c.ip + c.tagW + c.gapIn + c.timeW / 2f,
            pos.cardTop + g.cardH / 2f
        )
    }

    /**
     * 正文每行的实际字号（真实像素值，不是基准值）。
     *
     * 用来断言「超长的地点只缩自己那一行、别的行不动」——
     * 渲染图里哪个字大了哪个字小了根本量不准，只有把字号本身拿出来比才靠得住。
     *
     * **走 [measure] 而不是自己再算一遍上界**：宽度上界有两层（品牌区左边界 + 卡片宽），
     * 这里若还用「整幅内容区宽」去量，断言的是一份画不出来的版式 ——
     * 测试全绿，真机上长地名照样撞进品牌区。
     */
    internal fun lineSizes(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        now: Calendar,
        loc: LocInfo?,
        logo: Bitmap? = null
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        return measure(w, h, cfg, logo, now, loc, p).lineFs
    }

    /**
     * 版式几何，供测试断言。一次把三块的边界都拿出来，避免测试各拼一半算式。
     *
     * 返回 `[maxTextW, cardW, brandLeft, brandW, linesTop, linesBottom,
     *        brandTop, brandBottom, cardTop, cardBottom, canvasH, pad]`
     *
     * 缺的那块用 `-1` 表示（`brandTop`/`cardTop` 之类），不要拿 0 当"没有"——
     * 0 是画布顶端，是合法坐标。
     */
    internal fun layoutMetrics(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = measure(w, h, cfg, logo, now, loc, p)
        val pos = place(h, g, cfg.posTop)
        val brandLeft = if (g.brand != null) g.brand.right - g.brand.w else -1f
        val hasLines = g.lines.isNotEmpty()
        return floatArrayOf(
            g.maxTextW,
            g.card?.w ?: -1f,
            brandLeft,
            g.brand?.w ?: -1f,
            if (hasLines) pos.linesTop else -1f,
            if (hasLines) pos.linesTop + g.textH else -1f,
            if (g.brand != null) pos.brandTop else -1f,
            if (g.brand != null) pos.brandTop + g.brandH else -1f,
            if (g.card != null) pos.cardTop else -1f,
            if (g.card != null) pos.cardTop + g.cardH else -1f,
            h,
            g.pad
        )
    }

    /** 正文每行的**实测总宽**（含行首标签与标签后的间隔），供断言「没伸进品牌区那一列」 */
    internal fun lineWidths(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        return measure(w, h, cfg, logo, now, loc, p).lineW
    }

    /**
     * 正文各行**最终画在图上**的文案（长了会被截短、带省略号）。
     *
     * 和 [lineTexts] 只差一件事：那个回答「这一行装的是什么」（截断前的原文），
     * 这个回答「图上看到的是什么」。断言截字必须用这个 —— 拿原文去比永远相等。
     */
    internal fun lineDrawTexts(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ): List<String> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = measure(w, h, cfg, logo, now, loc, p)
        return g.lines.indices.map { i ->
            if (g.lines[i].label == null) g.lineShow[i] else g.lines[i].label + " " + g.lineShow[i]
        }
    }

    /**
     * 品牌区几何：返回 [品牌区宽, 右边界, 画布宽, 单侧边距]。
     * 用来断言「品牌区贴右下角、且没溢出画面」——这比盯着 PNG 目测靠得住。
     */
    internal fun brandMetrics(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = min(w, h) / BASE
        val b = measureBrand(w, s, cfg, now, loc, p)
            ?: return floatArrayOf(0f, w, w, PAD * s)
        return floatArrayOf(b.w, b.right, w, PAD * s)
    }

    /** 供测试断言卡片版式：返回 [卡片宽, 画布宽, 单侧边距, 时间字号] */
    internal fun cardMetrics(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar
    ): FloatArray {
        val s = min(w, h) / BASE
        val pad = PAD * s
        val cardH = if (cfg.showCard) CARD_H * s else 0f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val l = measureCard(w, s, cardH, pad, logo, cfg, now, p)
        return floatArrayOf(l.w, w, pad, l.timeFs)
    }

    fun timeText(cfg: WatermarkConfig, now: Calendar): String {
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        return if (cfg.showSec) {
            String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, now.get(Calendar.SECOND))
        } else {
            String.format(Locale.CHINA, "%02d:%02d", h, m)
        }
    }

    fun dateText(now: Calendar): String {
        return String.format(
            Locale.CHINA, "%04d.%02d.%02d 星期%s",
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
            WEEK[now.get(Calendar.DAY_OF_WEEK) - 1]
        )
    }
}
