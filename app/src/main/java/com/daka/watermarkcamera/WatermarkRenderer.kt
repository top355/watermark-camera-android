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
     * 「打卡」标签的配色：琥珀黄底 + 深色字。
     * 黄底上压白字对比度只有 1.9:1，基本看不清，所以字必须跟着改深色。
     */
    private const val LABEL_BG = 0xFFFFC107.toInt()
    private const val LABEL_FG = 0xFF14181D.toInt()
    private const val DARK = 0xFF14181D.toInt()

    /**
     * 卡片高度（1080 基准）。从 150 压到 116：参考图里这条白条相对画面宽度只有约 10% 高，
     * 150 会显得又高又空，时间字反而显小。
     */
    private const val CARD_H = 116f
    private val WEEK = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /**
     * 一行水印文字。
     *
     * [label] 是行首的彩色小标签（`备注` / `打卡人`）。加它的原因很实际：
     * 之前两行都是 34f 常规体纯白字，唯一区别只有「打卡人：」这个前缀，
     * 在图上根本分不出哪行是哪行。有了琥珀黄的标签，层级一眼可辨，
     * 而且不用把「备注」「打卡人」这几个字塞进正文里、变成内容的一部分。
     */
    private class Line(
        val text: String,
        val size: Float,
        val bold: Boolean,
        val label: String? = null
    )

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ) {
        if (w <= 1f || h <= 1f) return

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)

        /*
         * 归一化基准取「短边」而不是宽度：
         * 竖屏时短边就是宽度，行为与旧版逐像素一致（零回归）；
         * 横屏时若仍按宽度算，s 会从 1.33 涨到 1.78，水印被放大近 40% 且糊出屏幕。
         */
        val s = min(w, h) / BASE
        val pad = 40f * s

        val lines = buildLines(cfg, now, loc)
        /*
         * 每行各自缩到「放得下」为止。这是「地点那行太长」的根治办法：
         * 以前长地名会直接铺到画面外（右边被切掉），现在只缩这一行的字号，
         * 下限 0.66 倍 —— 版面骨架、行距关系全都不动，只有超标的那行变小。
         */
        val maxTextW = w - pad * 2f
        val fs = FloatArray(lines.size) { fitLineSize(text, lines[it], s, maxTextW) }
        val lineH = FloatArray(lines.size) { fs[it] * 1.34f }
        val textH = lineH.fold(0f) { a, b -> a + b }
        val cardH = if (cfg.showCard) CARD_H * s else 0f
        val gap = if (cardH > 0f && lines.isNotEmpty()) 28f * s else 0f

        /*
         * 品牌区**单独占一行**，不跟左侧的地点/备注挤在同一条水平带上。
         * 试过"与左侧文字同层"（更接近参考图），但左侧最长那行是地点（42f 粗体），
         * 长备注能到 25 字以上，两者必然在窄屏上撞车 —— 要么给左侧缩字号（伤主体信息），
         * 要么给品牌缩到看不清。垂直分开后两边各自完整，零碰撞。
         */
        val brand = measureBrand(w, s, cfg, now, loc, text)
        val brandH = brand?.h ?: 0f
        val hasBody = cardH > 0f || lines.isNotEmpty()
        val gapBrand = if (brand != null && hasBody) 18f * s else 0f

        val total = cardH + gap + textH + gapBrand + brandH
        if (total <= 0f) return

        val top = cfg.posTop
        val y0 = if (top) pad else h - pad - total

        /* 渐变遮罩：保证白字在亮背景上也读得清 */
        val scrimH = min(h * 0.62f, total + pad * 2.2f)
        val yStart = if (top) 0f else h - scrimH
        val yEnd = if (top) scrimH else h
        val colors = if (top) {
            intArrayOf(0xA8000000.toInt(), 0x00000000)
        } else {
            intArrayOf(0x00000000, 0xCC000000.toInt())
        }
        fill.shader = LinearGradient(0f, yStart, 0f, yEnd, colors, null, Shader.TileMode.CLAMP)
        fill.color = Color.WHITE
        canvas.drawRect(0f, yStart, w, yEnd, fill)
        fill.shader = null

        var y = y0
        /* 顶部模式时品牌区在最上（左上角），其余顺序与底部模式一致 */
        if (top && brand != null) {
            drawBrand(canvas, brand, s, y, text, fill)
            y += brandH + gapBrand
        }
        if (cardH > 0f) {
            drawCard(canvas, w, s, y, cardH, pad, logo, cfg, now, fill, text)
            y += cardH + gap
        }

        if (lines.isNotEmpty()) {
            val save = canvas.save()
            text.setShadowLayer(11f * s, 0f, 2f * s, 0xB3000000.toInt())
            text.textAlign = Paint.Align.LEFT
            var yy = y + lineH[0] * 0.78f
            for (i in lines.indices) {
                val l = lines[i]
                var x = pad
                if (l.label != null) {
                    text.typeface = Typeface.DEFAULT_BOLD
                    text.textSize = fs[i]
                    text.color = LABEL_BG
                    canvas.drawText(l.label, x, yy, text)
                    x += text.measureText(l.label) + 12f * s
                }
                text.typeface = if (l.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                text.textSize = fs[i]
                text.color = Color.WHITE
                canvas.drawText(l.text, x, yy, text)
                yy += lineH[i]
            }
            text.clearShadowLayer()
            canvas.restoreToCount(save)
            y += textH
        }

        if (!top && brand != null) {
            drawBrand(canvas, brand, s, y + gapBrand, text, fill)
        }
    }

    /** 一行文字的实际宽度（含行首标签与标签后的间隔），单位与画布一致 */
    private fun lineWidth(p: Paint, l: Line, fs: Float, s: Float): Float {
        var x = 0f
        if (l.label != null) {
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = fs
            x += p.measureText(l.label) + 12f * s
        }
        p.typeface = if (l.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textSize = fs
        return x + p.measureText(l.text)
    }

    /** 放得下就用名义字号，放不下逐磅缩，最低到名义字号的 66% */
    private fun fitLineSize(p: Paint, l: Line, s: Float, maxW: Float): Float {
        val nominal = l.size * s
        var fs = nominal
        while (fs > nominal * 0.66f && lineWidth(p, l, fs, s) > maxW) fs -= 1f * s
        return fs
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

        val pad = 40f * s
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
                    BrandRow(listOf(BrandSeg(it, false)), 30f * s, true)
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
                        17f * s,
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
        val x: Float,
        val y: Float,
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
        cardY: Float,
        cardH: Float,
        pad: Float,
        logo: Bitmap?,
        cfg: WatermarkConfig,
        now: Calendar,
        text: Paint
    ): CardLayout {
        val cardX = pad
        val maxW = w - pad * 2f
        val ip = 20f * s
        val radius = 18f * s
        val gapIn = 24f * s
        val gapRight = 24f * s

        /* 1) 黄色标签：高度贴着卡片（上下各留 8s），接近参考图的方角块 */
        val title = cfg.cardTitle.trim().ifBlank { "打卡" }
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 42f * s
        val tagW = text.measureText(title) + 40f * s
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
            var fs = 40f * s
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
            timeFs = 78f * s
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
            x = cardX, y = cardY, w = cardW, h = cardH, ip = ip, radius = radius,
            gapIn = gapIn, title = title, tagW = tagW, tagH = tagH, tagFs = 42f * s,
            rightW = rightW, logoDH = logoDH, unitFs = unitFs, timeFs = timeFs, timeW = timeW
        )
    }

    private fun drawCard(
        canvas: Canvas,
        w: Float,
        s: Float,
        cardY: Float,
        cardH: Float,
        pad: Float,
        logo: Bitmap?,
        cfg: WatermarkConfig,
        now: Calendar,
        fill: Paint,
        text: Paint
    ) {
        val L = measureCard(w, s, cardY, cardH, pad, logo, cfg, now, text)

        /* 底：白卡片 */
        fill.shader = null
        fill.color = 0xF7FFFFFF.toInt()
        fill.setShadowLayer(20f * s, 0f, 5f * s, 0x6B000000)
        canvas.drawRoundRect(L.x, L.y, L.x + L.w, L.y + L.h, L.radius, L.radius, fill)
        fill.clearShadowLayer()

        /* 黄色标签 */
        val tagX = L.x + L.ip
        val tagY = L.y + (L.h - L.tagH) / 2f
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
            val top2 = L.y + (L.h - L.logoDH) / 2f
            canvas.drawBitmap(
                logo, null,
                RectF(rightEdge - L.rightW, top2, rightEdge, top2 + L.logoDH), fill
            )
        } else if (L.rightW > 0f) {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = L.unitFs
            text.color = DARK
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(cfg.unit, rightEdge, centerBaseline(text, L.y + L.h / 2f), text)
        }

        /* 时间大字 */
        if (cfg.showTime) {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = L.timeFs
            text.color = DARK
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(
                timeText(cfg, now), tagX + L.tagW + L.gapIn,
                centerBaseline(text, L.y + L.h / 2f), text
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
        if (cfg.showAddr && cfg.addr.isNotBlank()) out.add(Line(cfg.addr, 42f, true))
        if (cfg.showDate) out.add(Line(dateText(now), 36f, false))
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
     * 正文每行的实际字号（真实像素值，不是基准值）。
     *
     * 用来断言「超长的地点只缩自己那一行、别的行不动」——
     * 渲染图里哪个字大了哪个字小了根本量不准，只有把字号本身拿出来比才靠得住。
     */
    internal fun lineSizes(
        w: Float,
        h: Float,
        cfg: WatermarkConfig,
        now: Calendar,
        loc: LocInfo?
    ): FloatArray {
        val s = min(w, h) / BASE
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val maxW = w - 40f * s * 2f
        return buildLines(cfg, now, loc).map { fitLineSize(p, it, s, maxW) }.toFloatArray()
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
            ?: return floatArrayOf(0f, w, w, 40f * s)
        return floatArrayOf(b.w, b.right, w, 40f * s)
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
        val pad = 40f * s
        val cardH = if (cfg.showCard) CARD_H * s else 0f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val l = measureCard(w, s, 0f, cardH, pad, logo, cfg, now, p)
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
