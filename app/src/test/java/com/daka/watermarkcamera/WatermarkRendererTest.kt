package com.daka.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * 用 Robolectric 的 NATIVE 图形模式跑真实的 android.graphics，
 * 把 WatermarkRenderer 的输出渲成 PNG 落到 app/build/test-render/。
 *
 * 目的不是断言像素，而是**真的把水印画出来看一眼**：
 * 编译通过 ≠ 画得对，版式/位置/遮挡只有渲出来才知道。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkRendererTest {

    private val outDir = File("build/test-render").apply { mkdirs() }

    /** 造一张 1440x1920 的假“现场照片” */
    private fun scene(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val g = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#8FC0E0"), Color.parseColor("#E2D6B4"), Color.parseColor("#6D7D5F")),
            null, Shader.TileMode.CLAMP
        )
        p.shader = g
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        p.color = Color.parseColor("#C3D0AB")
        c.drawRect(0f, h * 0.61f, w.toFloat(), h.toFloat(), p)
        p.color = Color.parseColor("#CFD6DD")
        c.drawRect(w * 0.10f, h * 0.30f, w * 0.40f, h * 0.61f, p)
        p.color = Color.parseColor("#A8B2BA")
        c.drawRect(w * 0.47f, h * 0.42f, w * 0.67f, h * 0.61f, p)
        p.color = Color.parseColor("#3A4450")
        c.drawRect(w * 0.72f, h * 0.50f, w * 0.88f, h * 0.61f, p)
        return bmp
    }

    /** 画一帧，但不落盘 —— 用来做两张图的像素对比 */
    private fun frame(
        cfg: WatermarkConfig,
        logoData: Bitmap? = null,
        landscape: Boolean = false,
        loc: LocInfo? = null,
        highlight: WatermarkRenderer.TapTarget = WatermarkRenderer.TapTarget.NONE
    ): Bitmap {
        val w = if (landscape) 1920 else 1440
        val h = if (landscape) 1440 else 1920
        val bmp = scene(w, h)
        WatermarkRenderer.draw(
            Canvas(bmp),
            w.toFloat(),
            h.toFloat(),
            cfg,
            logoData,
            Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 27, 10, 15, 33) },
            loc,
            highlight
        )
        return bmp
    }

    private fun save(name: String, bmp: Bitmap) {
        val f = File(outDir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("渲染输出: " + f.absolutePath + " (" + f.length() + " bytes)")
        assertTrue("PNG 应该非空", f.length() > 1000L)
    }

    private fun render(
        name: String,
        cfg: WatermarkConfig,
        logoData: Bitmap? = null,
        landscape: Boolean = false,
        loc: LocInfo? = null,
        highlight: WatermarkRenderer.TapTarget = WatermarkRenderer.TapTarget.NONE
    ) {
        save(name, frame(cfg, logoData, landscape, loc, highlight))
    }

    private fun base() = WatermarkConfig(
        cardTitle = "打卡",
        unit = "示例物业",
        addr = "星海市云山区·风和苑",
        note = "设备运行正常",
        name = "张三",
        showCard = true,
        showTime = true,
        showSec = false,
        showDate = true,
        showAddr = true,
        showNote = false,
        showName = false,
        showCoord = false,
        showAcc = false,
        showAlt = false,
        posTop = false
    )

    private fun fakeLogo(): Bitmap {
        val b = Bitmap.createBitmap(520, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#18A058")
        c.drawCircle(100f, 100f, 78f, p)
        p.color = Color.WHITE
        p.textSize = 90f
        p.textAlign = Paint.Align.CENTER
        c.drawText("W", 100f, 132f, p)
        p.color = Color.parseColor("#14532D")
        p.textAlign = Paint.Align.LEFT
        p.textSize = 86f
        c.drawText("示例物业", 210f, 130f, p)
        return b
    }

    @Test
    fun 默认版式_底部卡片_文字单位() {
        render("A-bottom-text-unit.png", base())
    }

    @Test
    fun 底部卡片_Logo图片_优先于文字() {
        render("B-bottom-logo.png", base(), fakeLogo())
    }

    @Test
    fun 切到画面顶部() {
        render("C-top.png", base().copy(posTop = true), fakeLogo())
    }

    @Test
    fun 关卡片时时间退化成文字行() {
        render("D-no-card.png", base().copy(showCard = false), fakeLogo())
    }

    @Test
    fun 打开经纬度_无定位时应写未取得定位() {
        render("E-with-coord.png", base().copy(showCoord = true, showAcc = true))
    }

    @Test
    fun 时间到秒_附加备注与打卡人() {
        render(
            "F-sec-note-name.png",
            base().copy(showSec = true, showNote = true, showName = true)
        )
    }

    @Test
    fun 横屏_底部卡片_文字单位() {
        render("G-landscape-bottom.png", base(), landscape = true)
    }

    @Test
    fun 横屏_顶部卡片_Logo图片() {
        render("H-landscape-top.png", base().copy(posTop = true), fakeLogo(), landscape = true)
    }

    /* ---------------- 定位文案：断言文本比看图可靠（小数点位数在图上根本看不出来） ---------------- */

    private val fixedNow = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 27, 10, 15, 33)
    }
    private val fixedLoc = LocInfo(lat = 30.274083, lon = 120.155092, accuracy = 8, altitude = 12.4)

    @Test
    fun 定位文案_经纬度四位小数_精度与海拔合并成一行() {
        val texts = WatermarkRenderer.lineTexts(
            base().copy(showCoord = true, showAcc = true, showAlt = true),
            fixedNow,
            fixedLoc
        )
        println("水印文案 = $texts")

        val coord = texts.firstOrNull { it.contains("°N") }
        assertNotNull("应该有坐标行", coord)
        assertEquals("30.2741°N  120.1551°E", coord)

        assertTrue("精度与海拔必须同一行，实际: $texts", texts.contains("±8m  海拔12m"))
        assertFalse("不该再出现「定位精度 N 米」这种长写法", texts.any { it.contains("定位精度") })
        assertFalse("不该再出现「海拔 N 米」这种长写法", texts.any { it.contains(" 米") })
    }

    @Test
    fun 定位文案_只开精度时就是短格式() {
        val texts = WatermarkRenderer.lineTexts(base().copy(showAcc = true), fixedNow, fixedLoc)
        println("水印文案 = $texts")
        assertTrue(texts.contains("±8m"))
    }

    @Test
    fun 定位文案_没定位上要如实说_不能编一个坐标() {
        val texts = WatermarkRenderer.lineTexts(base().copy(showCoord = true), fixedNow, null)
        println("水印文案 = $texts")
        assertTrue(texts.contains("未取得定位"))
        assertFalse("没定位就不该出现任何数字坐标", texts.any { it.contains("°N") || it.contains("°E") })
    }

    /** 精简后的定位信息长这样，渲出来看一眼 */
    @Test
    fun 已定位_经纬度四位小数_精度海拔合并一行() {
        render(
            "I-loc-compact.png",
            base().copy(showCoord = true, showAcc = true, showAlt = true),
            loc = fixedLoc
        )
    }

    /* ---------------- 条幅（白卡片）宽度：必须内容自适应，不能撑满整行 ---------------- */

    @Test
    fun 条幅宽度_跟着内容走_不撑满整行() {
        val plain = WatermarkRenderer.cardMetrics(
            1440f, 1920f, base().copy(unit = ""), null, fixedNow
        )
        val withLogo = WatermarkRenderer.cardMetrics(1440f, 1920f, base(), fakeLogo(), fixedNow)
        val w = plain[1]
        val pad = plain[2]
        val maxW = w - pad * 2f
        println(
            "纯打卡条宽=${plain[0]}  带Logo条宽=${withLogo[0]}  " +
                "上限=$maxW  占满宽比例=${"%.1f".format(withLogo[0] / maxW * 100)}%"
        )

        assertTrue("有 logo 时卡片应该更宽（宽度跟着内容走）", withLogo[0] > plain[0])
        assertTrue(
            "卡片不该撑满整行: ${withLogo[0]} vs $maxW",
            withLogo[0] < maxW * 0.75f
        )
    }

    @Test
    fun 条幅宽度_内容过长时缩时间字号_而不是溢出屏幕() {
        val m = WatermarkRenderer.cardMetrics(
            1440f,
            1920f,
            base().copy(showSec = true, cardTitle = "打卡打卡打卡打卡打卡打卡", unit = "星海市示例物业管理有限公司"),
            null,
            fixedNow
        )
        val maxW = m[1] - 2f * m[2]
        println("超长内容 条宽=${m[0]}  上限=$maxW  时间字号=${m[3]}")
        assertTrue("卡片宽不能超过 画面宽-2*边距", m[0] <= maxW + 0.5f)
        /*
         * 基准值从 TIME_EM 取，不写死 —— 原来这里写的是 `78f * 1440f / 1080f`，
         * 主代码把基准从 78 调到 68 之后，这条断言会**照旧通过**（因为缩过的字号当然小于旧基准），
         * 于是"有没有真的缩"就悄悄失去了意义。
         */
        val baseline = WatermarkRenderer.TIME_EM * 1440f / 1080f
        assertTrue("应该已经缩过时间字号（基准 $baseline → 现在 ${m[3]}）", m[3] < baseline - 0.5f)
    }

    /* ---------------- 备注 / 打卡人：两行必须一眼能分清 ---------------- */

    @Test
    fun 备注与打卡人_各自带彩色标签() {
        val texts = WatermarkRenderer.lineTexts(
            base().copy(showNote = true, showName = true), fixedNow, null
        )
        println("水印文案 = $texts")

        assertTrue("备注行要带「备注」标签: $texts", texts.any { it.startsWith("备注 ") })
        assertTrue("打卡人行要带「打卡人」标签: $texts", texts.any { it.startsWith("打卡人 ") })
        assertFalse(
            "「打卡人：」这种硬拼进正文的前缀不该再出现（它现在是独立标签）: $texts",
            texts.any { it.contains("打卡人：") }
        )
    }

    @Test
    fun 渲染_备注打卡人与右下角品牌() {
        render(
            "J-brand-note-name.png",
            base().copy(showNote = true, showName = true),
            fakeLogo(),
            loc = fixedLoc
        )
    }

    /**
     * 用**真的内置 Logo**（`res/drawable/logo_placeholder.xml`，手写矢量）渲一张出来。
     *
     * 这一张的意义和前几张不同：前面都用 [fakeLogo] 这个程序画的假图，
     * 只能验证「版式给右侧块留了位置」。内置 Logo 是唯一一条
     * 「矢量资源 → 栅格化 → 贴进卡片」的完整链路，任何一环断了
     * （资源没打进去、矢量不受支持、比例算错）都只有真渲才能发现。
     */
    @Test
    fun 渲染_内置占位Logo() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val builtin = LogoStore.defaultLogo(ctx)
        assertNotNull("内置 Logo 应能栅格化", builtin)
        render(
            "K-builtin-logo.png",
            base().copy(showNote = true, showName = true, unit = ""),
            builtin,
            loc = fixedLoc
        )
    }

    /* ---------------- 右下角品牌区与防伪码 ---------------- */

    @Test
    fun 品牌区_贴右下角且不溢出画面() {
        val m = WatermarkRenderer.brandMetrics(1440f, 1920f, base(), fixedNow, fixedLoc)
        val brandW = m[0]
        val right = m[1]
        val w = m[2]
        val pad = m[3]
        println("品牌区宽=$brandW 右边界=$right 画布宽=$w 边距=$pad")

        assertEquals("右边界应贴住右侧边距", w - pad, right, 0.5f)
        assertTrue("品牌区宽度不能超过内容区", brandW <= w - 2f * pad + 0.5f)
        assertTrue("左边界不能越出画面", right - brandW >= pad - 0.5f)
    }

    /** 品牌区是独立一行，所以备注写多长都不该跟它抢水平位置 */
    @Test
    fun 品牌区_不跟长备注抢位置() {
        val longNote = base().copy(
            showNote = true,
            note = "设备运行正常，已完成今日巡检并拍照留档，无异常情况需要上报处理"
        )
        val plain = WatermarkRenderer.brandMetrics(1440f, 1920f, base(), fixedNow, fixedLoc)
        val withNote = WatermarkRenderer.brandMetrics(1440f, 1920f, longNote, fixedNow, fixedLoc)
        println("品牌区宽 无备注=${plain[0]} 带备注=${withNote[0]}  右边界=${plain[1]}/${withNote[1]}")

        /* 右对齐，边界钉死在右侧边距上，不随左侧任何内容移动 */
        assertEquals("右边界不该动", plain[1], withNote[1], 0.5f)
        /*
         * 宽度**不该有抖动** —— 防伪码把备注也算进种子，备注一变码就变，但码是
         * 等宽画出来的（PT Mono，加载失败退回 Typeface.MONOSPACE 也是等宽），
         * 14 个字符的宽度恒定。实测两值完全相等。
         *
         * 阈值留 20px 不是"允许抖动"，是给不同 ROM 的中文标签/空格留余量；
         * 真正的护栏是**上界**：一旦有人把码换成比例字体，或者让备注挤进品牌区，
         * 这个差值会直接跨过 20。
         */
        assertTrue(
            "宽度不该被备注影响: ${plain[0]} vs ${withNote[0]}",
            Math.abs(plain[0] - withNote[0]) < 20f
        )
    }

    @Test
    fun 品牌区_三行_品牌名标语与防伪码() {
        val texts = WatermarkRenderer.brandTexts(base(), fixedNow, fixedLoc)
        println("品牌区文案 = $texts")
        assertEquals(3, texts.size)
        assertEquals("第一行是品牌名", "今日水印", texts[0])
        assertEquals("第二行是标语", "相机真实可验", texts[1])
        /* 标签是「防伪」，不是「防伪码」—— 空格已经排除了「防伪码 」蒙混过关 */
        assertTrue("第三行标签应为「防伪」: ${texts[2]}", texts[2].startsWith("防伪 "))
        assertFalse("标签后面不该再挂一个「码」字: ${texts[2]}", texts[2].startsWith("防伪码"))
    }

    /** 三行字号必须递减 —— 同一字号排三行会糊成一坨，层级全靠字号差撑起来 */
    @Test
    fun 品牌区_三行字号逐行变小() {
        val sizes = WatermarkRenderer.brandRowSizes(1440f, 1920f, base(), fixedNow, fixedLoc)
        println("品牌区三行字号 = ${sizes.toList()}")
        assertEquals(3, sizes.size)
        assertTrue("品牌名应大于标语: ${sizes.toList()}", sizes[0] > sizes[1])
        assertTrue("标语应大于防伪码: ${sizes.toList()}", sizes[1] > sizes[2])
    }

    @Test
    fun 防伪码_十四位连排_不含横线与易混字符() {
        val code = AntiFake.code(base(), fixedNow, fixedLoc)
        println("防伪码 = $code")
        assertTrue(
            "应为 14 位连续大写字母数字（对齐参考图位数），实际: $code",
            Regex("^[0-9A-Z]{14}$").matches(code)
        )
        assertFalse("不该再出现分隔横线: $code", code.contains("-"))
        assertFalse("0/O/1/I 抄下来必错，不该出现在码里: $code", code.any { it in "0O1I" })
    }

    /**
     * **跨实现对拍的基准值** —— 网页版 `verify_brand_parity.js` 里那份期望值就是从这里来的。
     *
     * 单独列一条是为了让那两处**同源**：网页版自己算一遍再跟自己比是没有意义的，
     * 必须拿 JDK 的 MessageDigest 跑出来的结果当基准。改了字段或位数时，
     * 重跑本测试，把这两行抄进对拍脚本即可。
     */
    @Test
    fun 防伪码_跨实现对拍的基准值() {
        val withLoc = AntiFake.code(base(), fixedNow, fixedLoc)
        val noLoc = AntiFake.code(base(), fixedNow, null)
        println("BASE_WITH_LOC=$withLoc")
        println("BASE_NO_LOC=$noLoc")
        assertEquals("基准值必须是 14 位", 14, withLoc.length)
        assertEquals("基准值必须是 14 位", 14, noLoc.length)
        assertNotEquals("有定位与无定位必须是不同的码", withLoc, noLoc)
    }

    @Test
    fun 防伪码_同输入必同码_改一处就变() {
        val a = AntiFake.code(base(), fixedNow, fixedLoc)
        assertEquals("同输入必须算出同一个码，否则校验方复现不出来", a, AntiFake.code(base(), fixedNow, fixedLoc))

        assertFalse(
            "改了备注，码必须跟着变（否则它证明不了水印没被改）",
            a == AntiFake.code(base().copy(note = "设备运行异常"), fixedNow, fixedLoc)
        )
        assertFalse(
            "换了机型，码必须不同",
            a == AntiFake.code(base().copy(deviceTag = "另一台设备"), fixedNow, fixedLoc)
        )
        assertFalse(
            "地点变了，码必须不同",
            a == AntiFake.code(base().copy(addr = "星海市云山区·别的小区"), fixedNow, fixedLoc)
        )
    }

    /** 预览每 250ms 重绘一次：码若算到秒，用户看到的码和出图的码会不一样 */
    @Test
    fun 防伪码_只算到分钟_同分钟内稳定() {
        val t1 = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 27, 10, 15, 1) }
        val t2 = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 27, 10, 15, 59) }
        assertEquals(
            "同一分钟内预览与出图必须得到同一个码",
            AntiFake.code(base(), t1, fixedLoc),
            AntiFake.code(base(), t2, fixedLoc)
        )
    }

    /* ---------------- 手动指定的水印日期时间 ---------------- */

    @Test
    fun 手动时间_日期时间都用指定的那一刻() {
        val ms = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 3, 8, 5, 0)
        }.timeInMillis
        val cfg = base().copy(manualTime = true, manualTimeMs = ms)

        assertEquals("时间应取手动值", "08:05", WatermarkRenderer.timeText(cfg, cfg.now()))
        assertEquals("日期也应取手动值", "2026.01.03 星期六", WatermarkRenderer.dateText(cfg.now()))
    }

    @Test
    fun 手动时间_关掉后回到系统时钟() {
        val cfg = base()
        val delta = Math.abs(cfg.now().timeInMillis - System.currentTimeMillis())
        assertTrue("未锁定时应取当前时刻，实际偏差 $delta ms", delta < 5000L)
    }

    /** 时间变了防伪码就得跟着变 —— 它绑定的正是"照片上写着的那组信息" */
    @Test
    fun 手动时间_改变时刻会改变防伪码() {
        val a = base().copy(manualTime = true, manualTimeMs = 1000000000000L)
        val b = base().copy(manualTime = true, manualTimeMs = 1000000060000L)
        assertFalse(
            "差一分钟就该是不同的码",
            AntiFake.code(a, a.now(), null) == AntiFake.code(b, b.now(), null)
        )
    }

    /* ---------------- 正文过长自动缩字号 ---------------- */

    @Test
    fun 普通长度地点_不动字号() {
        val sizes = WatermarkRenderer.lineSizes(1440f, 1920f, base(), fixedNow, fixedLoc)
        val s = 1440f / 1080f
        assertEquals("正常长度的地点不该被动过", 42f * s, sizes[0], 0.5f)
    }

    /**
     * 长地名：**先小幅缩字号，再截字** —— 二选一的顺序不能反。
     *
     * 用户的原话：「要不就不缩那么小了，小区名字截取几个字算了，
     * 看离横幅最右边可以放几个字。」所以判据从"字号越小越好"换成了：
     * 字号最多只小两成（[WatermarkRenderer.MIN_LINE_SCALE]），放不下的部分从**尾巴**截掉。
     *
     * 断言截字必须用 [WatermarkRenderer.lineDrawTexts]（图上看到的），
     * 不能用 lineTexts（截断前的原文）—— 拿原文比永远相等，等于没测。
     */
    @Test
    fun 超长地点_小幅缩字号_放不下的字从尾巴截掉() {
        val long = base().copy(
            addr = "云岭省星海市云山区城东街道办事处风和苑小区三期十二栋二单元",
            note = "正常",
            showNote = true
        )
        val drawn = WatermarkRenderer.lineDrawTexts(1440f, 1920f, long, null, fixedNow, fixedLoc)
        val sizes = WatermarkRenderer.lineSizes(1440f, 1920f, long, fixedNow, fixedLoc)
        val ws = WatermarkRenderer.lineWidths(1440f, 1920f, long, null, fixedNow, fixedLoc)
        val m = WatermarkRenderer.layoutMetrics(1440f, 1920f, long, null, fixedNow, fixedLoc)
        println("图上行文案 = $drawn")
        println("行字号 = ${sizes.toList()}  行宽 = ${ws.toList()}  宽度上界 = ${m[0]}")
        println("地点原文 ${long.addr.length} 字 → 图上显示 ${drawn[0].length - 1} 字 + 省略号")
        render("O-long-addr.png", long, loc = fixedLoc)

        val s = 1440f / 1080f
        assertTrue("地点该被截短（末尾带省略号），实际: ${drawn[0]}", drawn[0].endsWith("…"))
        assertTrue(
            "截短后应该还是原文的**前缀**（截的是尾巴，不是中间）: ${drawn[0]}",
            long.addr.startsWith(drawn[0].removeSuffix("…"))
        )
        assertTrue("截掉之后必须真的放得下: 行宽=${ws[0]} 上界=${m[0]}", ws[0] <= m[0] + 0.5f)

        /* 缩字号的幅度被收住了 —— 这是这次改动的重点，不能再一路缩到 0.36 */
        val floor = 42f * s * WatermarkRenderer.MIN_LINE_SCALE
        assertTrue("不能缩成蚂蚁字，下限是名义的 ${WatermarkRenderer.MIN_LINE_SCALE}: ${sizes[0]}", sizes[0] >= floor - 0.5f)
        assertTrue("地点那行该比名义 42f 小一点: ${sizes[0]}", sizes[0] < 42f * s - 0.5f)

        /* 备注那行很短，既不缩也不截 —— 否则"截字/缩字"就变成了全局行为 */
        val noteIdx = drawn.indexOfFirst { it.startsWith("备注") }
        assertTrue("备注行不该被截: ${drawn[noteIdx]}", !drawn[noteIdx].endsWith("…"))
        assertTrue("备注行应保持名义 34f: ${sizes[noteIdx]}", sizes[noteIdx] > 34f * s - 0.5f)
    }

    /**
     * 顺序钉死：**先缩、缩不动了才截**。
     *
     * 反过来的话，只超出一点点的地方（比如 14 个字）会先被砍掉一个字，
     * 而不是整体小一成 —— 而"字一个不少、只是略小"显然是更好的结果。
     * 这条和上面那条合起来才是完整规则：小幅缩 → 缩到下限 → 才截尾。
     */
    @Test
    fun 只超一点的地点_缩字号就够_不到截字那一步() {
        val cfg = base().copy(addr = "云岭省星海市云山区风和苑三期")   // 14 字，超出上界约一成
        val drawn = WatermarkRenderer.lineDrawTexts(1440f, 1920f, cfg, null, fixedNow, fixedLoc)
        val sizes = WatermarkRenderer.lineSizes(1440f, 1920f, cfg, fixedNow, fixedLoc)
        val ws = WatermarkRenderer.lineWidths(1440f, 1920f, cfg, null, fixedNow, fixedLoc)
        val m = WatermarkRenderer.layoutMetrics(1440f, 1920f, cfg, null, fixedNow, fixedLoc)
        println("14 字地点 → 图上「${drawn[0]}」字号=${sizes[0]} 行宽=${ws[0]} 上界=${m[0]}")

        val s = 1440f / 1080f
        assertTrue("这一步还不到截字的地步，应该一个字不少: ${drawn[0]}", drawn[0] == cfg.addr)
        assertTrue("但字号该小幅收一点: ${sizes[0]}", sizes[0] < 42f * s - 0.5f)
        assertTrue(
            "也不该直接掉到下限: ${sizes[0]}",
            sizes[0] > 42f * s * WatermarkRenderer.MIN_LINE_SCALE + 0.5f
        )
        assertTrue("必须真的放得下: 行宽=${ws[0]} 上界=${m[0]}", ws[0] <= m[0] + 0.5f)
    }

    /* ================= 新版式：底边对齐 + 点水印改内容 =================
     *
     * 对着用户提的三件事逐条断言，一条一个测试，别混在一起：
     * 1) 左下正文块与右下品牌区**底边对齐**（要的是同一个 y，不是"看着差不多"）
     * 2) 地点那行**不许比上面的时间条更宽**
     * 3) 地点 / 日期时间**点得中**（点不中就谈不上"在拍摄界面点开修改"）
     *
     * layoutMetrics 的下标表：0 maxTextW / 1 cardW / 2 brandLeft / 3 brandW /
     * 4 linesTop / 5 linesBottom / 6 brandTop / 7 brandBottom / 8 cardTop / 9 cardBottom /
     * 10 canvasH / 11 pad。缺的那块是 -1。
     */

    private val mW = 1440f
    private val mH = 1920f
    private val mPad get() = WatermarkRenderer.PAD * (mW / 1080f)

    @Test
    fun 底部对齐_正文块与品牌区共用同一个底边() {
        val cfg = base()
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        println(
            "正文块 [${m[4]}, ${m[5]}]  品牌区 [${m[6]}, ${m[7]}]  " +
                "卡片 [${m[8]}, ${m[9]}]  底边距=${m[10] - m[5]}"
        )

        assertEquals("正文块与品牌区必须底边对齐", m[5], m[7], 0.5f)
        assertTrue("底边应落在画布高 - 边距 上: ${m[5]} vs ${m[10] - m[11]}", Math.abs(m[5] - (m[10] - m[11])) <= 0.5f)
        /* 卡片仍在其上，不能和正文块叠在一起 */
        assertTrue("卡片不能在正文块下方: card=${m[9]} linesTop=${m[4]}", m[9] <= m[4] + 0.5f)
        assertTrue("卡片顶边不能在画面外: ${m[8]}", m[8] >= 0f)
    }

    /**
     * 卡片要**紧贴**下面那行文字，不能因为品牌区高就被顶上去。
     *
     * 这两句其实是一件事：以前卡片一律按 `min(正文顶, 品牌顶)` 让位，
     * 而品牌区（三行 + 一个灰块）比正文块高，于是卡片实际离正文比 GAP_CARD 远得多
     * （1440 宽下 59px，而不是 GAP_CARD×s 的 37px）。现在只在**横向真的撞上**
     * 品牌区那一列时才让位 —— 卡片从左边起、品牌区贴右边界，正常内容下两者根本不重合。
     *
     * 品牌区刚被调大，这一条更要钉住：不然每次把品牌名字号往上提，
     * 卡片都会被顺带顶高一截，而"更远"这个副作用跟品牌字号八竿子打不着。
     */
    @Test
    fun 卡片_紧贴正文块顶边_不被品牌区顶高() {
        val cfg = base()
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        val gap = m[4] - m[9]
        val expect = WatermarkRenderer.GAP_CARD * (mW / 1080f)
        val cardRight = mPad + m[1]
        println(
            "卡片底=${m[9]} 正文顶=${m[4]} → 间距=$gap（应为 $expect）  " +
                "卡片右边界=$cardRight 品牌区左边界=${m[2]} 品牌区顶=${m[6]}"
        )

        assertEquals("卡片底边到正文块顶边应正好是 GAP_CARD", expect, gap, 0.5f)
        /* 材质上不能撞：卡片右边界还在品牌区左边界左边，所以本来就不该让位 */
        assertTrue("这一例里两者横向不该撞上: $cardRight vs ${m[2]}", cardRight < m[2])
        assertTrue(
            "卡片底边该落到品牌区顶边**之下**（说明没为它让位）: ${m[9]} vs ${m[6]}",
            m[9] > m[6]
        )
    }

    /** 反过来：卡片真伸进品牌区那一列时，还得照旧让位，否则白条会压在品牌名上 */
    @Test
    fun 卡片_伸进品牌区那一列时仍然让位() {
        val cfg = base().copy(
            showSec = true,
            cardTitle = "打卡打卡打卡打卡打卡打卡",
            unit = "星海市示例物业管理有限公司"
        )
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        val cardRight = mPad + m[1]
        println("长内容 卡片右边界=$cardRight 品牌区左边界=${m[2]} 卡片底=${m[9]} 品牌区顶=${m[6]}")

        assertTrue("这一例就是冲着撞上品牌区去的: $cardRight vs ${m[2]}", cardRight > m[2])
        assertTrue("撞上时卡片底边必须让到品牌区顶之上: ${m[9]} <= ${m[6]}", m[9] <= m[6] + 0.5f)
    }

    @Test
    fun 底部对齐_长地名与关掉卡片时同样成立() {
        /* 底边对齐不能只在"正好那一种配置"下成立 —— 行高会随字号缩、卡片有无会换分支 */
        val cases = listOf(
            "常规" to base(),
            "长地名" to base().copy(addr = "云岭省星海市云山区城东街道办事处风和苑小区三期十二栋二单元"),
            "无卡片" to base().copy(showCard = false),
            "带坐标" to base().copy(showCoord = true, showAcc = true, showAlt = true),
            "带备注" to base().copy(showNote = true, note = "设备运行正常，已完成今日巡检并拍照留档")
        )
        for ((name, cfg) in cases) {
            val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
            println("[$name] 正文底=${m[5]} 品牌底=${m[7]} 卡片底=${m[9]}")
            assertEquals("[$name] 正文块与品牌区底边必须对齐", m[5], m[7], 0.5f)
            assertTrue("[$name] 底下那一块不能出画: ${m[5]}", m[5] <= mH)
        }
    }

    @Test
    fun 顶部模式_顺序仍是品牌区在最上_没被这次改动打乱() {
        val m = WatermarkRenderer.layoutMetrics(mW, mH, base().copy(posTop = true), null, fixedNow, fixedLoc)
        println("顶部模式 品牌=[${m[6]}, ${m[7]}] 卡片=[${m[8]}, ${m[9]}] 正文=[${m[4]}, ${m[5]}]")
        assertTrue("品牌区应在最上", m[6] < m[8])
        assertTrue("卡片应在正文块之上", m[8] < m[4])
        assertTrue("整块不能出画", m[5] <= mH)
    }

    @Test
    fun 地点行_不比上面的时间条更宽() {
        val cfg = base()
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        val ws = WatermarkRenderer.lineWidths(mW, mH, cfg, null, fixedNow, fixedLoc)
        val texts = WatermarkRenderer.lineTexts(cfg, fixedNow, fixedLoc)
        println("时间条宽=${m[1]}  行宽上界=${m[0]}  逐行宽=${ws.toList()}  品牌区左边界=${m[2]}")

        assertTrue("卡片该有宽度", m[1] > 0f)
        assertTrue("正文行宽上界不许超过时间条: ${m[0]} > ${m[1]}", m[0] <= m[1] + 0.5f)

        /* 真的画出来的每一行，右边界都得在品牌区左边界里面 —— 这是"不撞车"的硬保证 */
        for (i in ws.indices) {
            val right = m[11] + ws[i]
            println("  第 $i 行「${texts[i]}」右边界=$right")
            assertTrue("第 $i 行伸进品牌区了: 右=$right 品牌区左=${m[2]}", right <= m[2] + 0.5f)
        }
    }

    @Test
    fun 关掉卡片时间_短地名不该被窄卡片挤小() {
        /*
         * showCard=true 而 showTime=false 时，卡片只剩「打卡」标签（1440 宽下约 219px）。
         * 要是照旧拿卡片宽去卡正文，正常的短地名也会被逼到最小字号 —— 这条守住那个退化。
         */
        val cfg = base().copy(showTime = false)
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        val sizes = WatermarkRenderer.lineSizes(mW, mH, cfg, fixedNow, fixedLoc)
        println("卡片宽=${m[1]} 行宽上界=${m[0]} 行字号=${sizes.toList()}")
        assertEquals("短地名不该因为卡片没时间而被缩", 42f * (mW / 1080f), sizes[0], 0.5f)
    }

    /* ---------------- 行用途标记：反推是行不通的，得显式带上 ---------------- */

    @Test
    fun 行用途标记_地点行是ADDR_日期行是DATE_其余是TEXT() {
        val cfg = base()
        val pts = WatermarkRenderer.lineHitPoints(mW, mH, cfg, null, fixedNow, fixedLoc)
        val texts = WatermarkRenderer.lineTexts(cfg, fixedNow, fixedLoc)
        assertEquals("命中点应与正文行一一对应", texts.size, pts.size)

        for (i in texts.indices) {
            val want = when {
                texts[i] == cfg.addr -> WatermarkRenderer.KIND_ADDR
                texts[i].startsWith("2026.") -> WatermarkRenderer.KIND_DATE
                else -> WatermarkRenderer.KIND_TEXT
            }
            assertEquals("第 $i 行「${texts[i]}」的用途标记", want, pts[i][2].toInt())
        }
    }

    /* ---------------- 点击命中：点水印上改内容的前提 ---------------- */

    @Test
    fun 点击_地点行给ADDR_日期行给DATETIME_其余行不吃事件() {
        val cfg = base()
        val pts = WatermarkRenderer.lineHitPoints(mW, mH, cfg, null, fixedNow, fixedLoc)
        val texts = WatermarkRenderer.lineTexts(cfg, fixedNow, fixedLoc)
        println("行命中点 = ${pts.map { it.toList() }}  文案 = $texts")

        for (i in texts.indices) {
            val got = WatermarkRenderer.hitTest(mW, mH, cfg, null, fixedNow, fixedLoc, pts[i][0], pts[i][1])
            val want = when {
                texts[i] == cfg.addr -> WatermarkRenderer.TapTarget.ADDR
                texts[i].startsWith("2026.") -> WatermarkRenderer.TapTarget.DATETIME
                else -> WatermarkRenderer.TapTarget.NONE
            }
            println("  第 $i 行「${texts[i]}」→ $got（期望 $want）")
            assertEquals("第 $i 行「${texts[i]}」", want, got)
        }
    }

    @Test
    fun 点击_卡片里的时间大字也算DATETIME() {
        val cfg = base()
        val pt = WatermarkRenderer.cardTimeHitPoint(mW, mH, cfg, null, fixedNow, fixedLoc)
        assertEquals("卡片有时间大字时该给得出中心点", 2, pt.size)
        val got = WatermarkRenderer.hitTest(mW, mH, cfg, null, fixedNow, fixedLoc, pt[0], pt[1])
        println("卡片时间命中点 = ${pt.toList()} → $got")
        assertEquals(WatermarkRenderer.TapTarget.DATETIME, got)
    }

    @Test
    fun 点击_品牌区和空白都不吃事件() {
        val cfg = base()
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)

        /* 品牌区正中心：宁可不响应，也不要在用户想点品牌名时弹一个跟品牌无关的编辑器 */
        val bx = m[2] + m[3] / 2f
        val by = (m[6] + m[7]) / 2f
        assertEquals(
            "品牌区不该响应点击",
            WatermarkRenderer.TapTarget.NONE,
            WatermarkRenderer.hitTest(mW, mH, cfg, null, fixedNow, fixedLoc, bx, by)
        )

        /* 画面中上部的大片空白：浮层必须放行，否则预览区就"死"了（点哪都不对焦） */
        assertEquals(
            "空白处不该吃事件",
            WatermarkRenderer.TapTarget.NONE,
            WatermarkRenderer.hitTest(mW, mH, cfg, null, fixedNow, fixedLoc, mW / 2f, mH * 0.4f)
        )
    }

    @Test
    fun 点击_命中区比看到的略大一圈_手指差十几像素也点得中() {
        val cfg = base()
        val pts = WatermarkRenderer.lineHitPoints(mW, mH, cfg, null, fixedNow, fixedLoc)
        val ws = WatermarkRenderer.lineWidths(mW, mH, cfg, null, fixedNow, fixedLoc)
        val addrIdx = WatermarkRenderer.lineTexts(cfg, fixedNow, fixedLoc).indexOfFirst { it == cfg.addr }
        val s = mW / 1080f
        val pt = pts[addrIdx]
        val rightEdge = mPad + ws[addrIdx]

        /* 刚出可见文字右边界一点点：在 slack（12 基准 × s ≈ 16px）之内，仍应命中 */
        val inside = WatermarkRenderer.hitTest(mW, mH, cfg, null, fixedNow, fixedLoc, rightEdge + 6f * s, pt[1])
        /* 再远出 slack 之外：必须放行，否则浮层会吃掉本该落到预览区的手势 */
        val outside = WatermarkRenderer.hitTest(mW, mH, cfg, null, fixedNow, fixedLoc, rightEdge + 40f * s, pt[1])
        println("行右边界=$rightEdge  +6s→$inside  +40s→$outside")

        assertEquals("刚出边界 6 基准像素该还在命中区里", WatermarkRenderer.TapTarget.ADDR, inside)
        assertEquals("出了 slack 就该放行", WatermarkRenderer.TapTarget.NONE, outside)
    }

    /* ---------------- 按下反馈：水印上"这里能点"的唯一可见提示 ---------------- */

    /**
     * 两张图的差异像素数 + 差异包围盒 `[n, x0, y0, x1, y1]`。
     *
     * 用像素差异而不是"看渲染图"来判断反馈有没有画出来：底是半透明的，
     * 压在渐变遮罩上，肉眼在一张 1440x1920 的图里根本分不出"亮了一点"和"没亮"。
     */
    private fun diffBox(a: Bitmap, b: Bitmap): IntArray {
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var n = 0
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (i in pa.indices) {
            if (pa[i] == pb[i]) continue
            val x = i % w
            val y = i / w
            n++
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        return intArrayOf(n, x0, y0, x1, y1)
    }

    @Test
    fun 按下反馈_只亮在被点的那一条带子上_而且不碰品牌区() {
        val cfg = base()
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        val ws = WatermarkRenderer.lineWidths(mW, mH, cfg, null, fixedNow, fixedLoc)
        val sizes = WatermarkRenderer.lineSizes(mW, mH, cfg, fixedNow, fixedLoc)
        val texts = WatermarkRenderer.lineTexts(cfg, fixedNow, fixedLoc)
        val addrIdx = texts.indexOfFirst { it == cfg.addr }

        val plain = frame(cfg, loc = fixedLoc)
        val pressed = frame(cfg, loc = fixedLoc, highlight = WatermarkRenderer.TapTarget.ADDR)
        render("M-pressed-addr.png", cfg, loc = fixedLoc, highlight = WatermarkRenderer.TapTarget.ADDR)
        render("M-plain.png", cfg, loc = fixedLoc)

        val d = diffBox(pressed, plain)
        println("地点行按下反馈：差异像素=${d[0]} 包围盒=(${d[1]}, ${d[2]})-(${d[3]}, ${d[4]})")

        assertTrue("按住时该有可见的一层底：差异像素只有 ${d[0]}", d[0] > 2000)

        /* 包围盒必须收在地点行自己那条带子里（含按下时往外放的半圈 slack） */
        val slack = 12f * (mW / 1080f) * 0.5f + 2f
        val lh = sizes[addrIdx] * 1.34f
        assertTrue("左边溢出到 ${d[1]}，行起点是 ${m[11]}", d[1] >= m[11] - slack)
        assertTrue("右边到 ${d[3]}，行右边界是 ${m[11] + ws[addrIdx]}", d[3] <= m[11] + ws[addrIdx] + slack)
        assertTrue("上边到 ${d[2]}，行上沿是 ${m[4]}", d[2] >= m[4] - slack)
        assertTrue("下边到 ${d[4]}，行下沿是 ${m[4] + lh}", d[4] <= m[4] + lh + slack)

        /* 这条最关键：反馈不能糊到品牌区上去（用户这次要修的正是那一片区域） */
        assertTrue("按下反馈越过了品牌区左边界 ${m[2]}：右到 ${d[3]}", d[3] < m[2])
    }

    @Test
    fun 按下反馈_卡片时间大字也有一层底() {
        val cfg = base()
        val m = WatermarkRenderer.layoutMetrics(mW, mH, cfg, null, fixedNow, fixedLoc)
        val plain = frame(cfg, loc = fixedLoc)
        val pressed = frame(cfg, loc = fixedLoc, highlight = WatermarkRenderer.TapTarget.DATETIME)
        render("N-pressed-card-time.png", cfg, loc = fixedLoc, highlight = WatermarkRenderer.TapTarget.DATETIME)

        val d = diffBox(pressed, plain)
        println(
            "卡片时间按下反馈：差异像素=${d[0]} 包围盒=(${d[1]}, ${d[2]})-(${d[3]}, ${d[4]}) " +
                "卡片=[${m[8]}, ${m[9]}] 正文块=[${m[4]}, ${m[5]}]"
        )

        assertTrue("卡片时间的反馈该有可见的一层底：差异像素只有 ${d[0]}", d[0] > 2000)

        /*
         * 下面这几条断言不能写成"包围盒 == 卡片那一块"：正文里的**日期行**和卡片里的
         * **时间大字**是同一个值（都归 TapTarget.DATETIME），这一下会同时亮两处 ——
         * 这是有意的，它们改的本来就是同一个东西，亮哪一处都不算撒谎。
         * 于是包围盒是两块并起来的外框：上沿顶到卡片上沿，下沿落到日期行下沿。
         */
        val slack = 12f * (mW / 1080f) * 0.5f + 3f
        assertTrue("包围盒上沿 ${d[2]} 不该高过卡片上沿 ${m[8]}", d[2] >= m[8] - slack)
        assertTrue("包围盒下沿 ${d[4]} 不该超过正文块下沿 ${m[5]}", d[4] <= m[5] + slack)
        assertTrue("反馈越过了品牌区左边界 ${m[2]}：右到 ${d[3]}", d[3] < m[2])
    }

    /* ---------------- 等宽字体（防伪码专用） ---------------- */

    /**
     * 字体必须**真的**加载到了。
     *
     * 加载失败时代码会静默退回系统等宽（不能崩、也不能出豆腐块），
     * 于是"回退生效"和"字体正常"在画面上几乎看不出区别 ——
     * 只有直接问 [Typefaces.mono] 才知道到底加载没有。
     */
    @Test
    fun 等宽字体_PTMono_已加载() {
        assertNotNull(
            "PT Mono 没加载到：防伪码会退回系统字体，各 ROM 上长得都不一样",
            Typefaces.mono
        )
    }

    /** 等宽的意义就在这：I 和 M 必须同宽，否则 14 位码在视觉上是歪的 */
    @Test
    fun 等宽字体_I与M同宽() {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typefaces.monoForDraw
        p.textSize = 40f
        assertEquals(
            "等宽字体的 I 与 M 必须同宽",
            p.measureText("IIIIIIIIII"),
            p.measureText("MMMMMMMMMM"),
            0.01f
        )
    }

    /* ================= 品牌区矢量稿 =================
     *
     * 「今日水印 / 相机真实可验」是**标识**，不能跟着系统字体变样，所以走轮廓路径。
     * 这一组测试盯三件事：宽度走的是 BrandMark 而不是字体度量、改了文字能退回字体、
     * 以及矢量**真的画到了画布上**（解析器写错的话宽度照样对，但一个字也不会出现）。
     */

    /** 只留品牌区的一帧：关卡片、清空正文行，方便按像素找品牌块 */
    private fun brandOnly(cfg: WatermarkConfig): Bitmap {
        val w = 1440
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.BLACK
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        WatermarkRenderer.draw(
            c, w.toFloat(), h.toFloat(),
            cfg.copy(showCard = false, showAddr = false, showDate = false, showTime = false),
            null, fixedNow, fixedLoc
        )
        return bmp
    }

    @Test
    fun 品牌区_矢量行宽度由BrandMark给出() {
        val s = 1440f / 1080f
        val widths = WatermarkRenderer.brandRowWidths(1440f, s, base(), fixedNow, fixedLoc)
        val nameW = BrandMark.nameWidth(BrandMark.NAME_EM * s)
        val sloganW = BrandMark.sloganWidth(BrandMark.SLOGAN_EM * s)
        println("逐行宽 = ${widths.toList()}  期望 品牌名=$nameW 标语=$sloganW")

        assertEquals("品牌区应有三行", 3, widths.size)
        /* 逐行比，不是比整块 —— 整块是三行取最大，用整块比会因为防伪码那行最宽而"假通过" */
        assertEquals("品牌名那行应等于 BrandMark 的宽度", nameW, widths[0], 0.5f)
        assertEquals("标语那行应等于 BrandMark 的宽度（含灰块）", sloganW, widths[1], 0.5f)
        assertTrue("防伪码那行应该是文字渲染出来的", widths[2] > 0f)
    }

    /** 两行字号必须差 1.40 倍 —— 这个比例是从参考图逐字反解出来的，不是拍脑袋 */
    @Test
    fun 品牌区_两行字号比与参考图一致() {
        assertEquals(1.40f, BrandMark.NAME_EM / BrandMark.SLOGAN_EM, 0.02f)
        println("行1/行2 字号比 = " + BrandMark.NAME_EM / BrandMark.SLOGAN_EM)
    }

    @Test
    fun 品牌区_改了名字就退回字体渲染() {
        val s = 1440f / 1080f
        val vectorW = BrandMark.nameWidth(BrandMark.NAME_EM * s)
        val w = WatermarkRenderer.brandRowWidths(
            1440f, s, base().copy(brandName = "示例水印"), fixedNow, fixedLoc
        )
        println("改名字后 品牌名行宽=${w[0]}（矢量稿是 $vectorW）")
        assertTrue("改名后那一行不该再报矢量稿的宽度", Math.abs(w[0] - vectorW) > 0.5f)
        assertTrue("改名后仍应量到一个正的行宽", w[0] > 0f)
    }

    /** 矢量真的画出来了：整块只留品牌区，画布上必须有墨 */
    @Test
    fun 矢量品牌名_真的画到了画布上() {
        val bmp = brandOnly(base())
        var ink = 0
        var rightMost = -1
        var leftMost = Int.MAX_VALUE
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (Color.red(bmp.getPixel(x, y)) > 200) {
                    ink++
                    if (x > rightMost) rightMost = x
                    if (x < leftMost) leftMost = x
                }
            }
        }
        println("品牌区墨迹 $ink px  左=$leftMost 右=$rightMost  画布宽=${bmp.width}")
        assertTrue("品牌区一个白像素都没有 —— 矢量路径没画出来", ink > 3000)
        /* s=1.333、PAD=26 → 单侧边距 ≈ 34.7 → 右边界应贴住 1440-34.7 ≈ 1405 */
        val pad = WatermarkRenderer.PAD * (1440f / 1080f)
        assertEquals("品牌区应右对齐贴住右侧边距", bmp.width - pad, rightMost.toFloat(), 3f)
        assertTrue("左边界不能顶出画面", leftMost > 200)

        File(outDir, "I-brand-vector.png").also {
            FileOutputStream(it).use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) }
        }
    }

    /**
     * 灰块必须真的是一块浅灰，**而且块里真的有深色字**。
     *
     * 只断言"有东西被画出来"远远不够：字的颜色漏设时字会和底同色，
     * 只剩一圈阴影 —— 那在像素统计上"有东西"，在画面上却是看不清的。
     * 所以两样都要数：浅灰底、以及块范围内的深色墨迹。
     */
    @Test
    fun 标语行的浅灰块_底色与块里的深字都在() {
        val bmp = brandOnly(base())

        /*
         * 数**精确色**，不用宽容差。
         *
         * 宽容差（R 225..240 / G 230..245 / B 235..250）会把白字的抗锯齿边缘也算进来 ——
         * 那是纯白到纯黑的中性渐变，中途必然穿过这个浅灰区间。后果是两条断言**同时失效**：
         *   · "灰块范围"被撑大（实测 x 从 1207 起，而块真正的左边界在 1266）
         *   · "块内深色像素"里混进大片黑色背景（实测 dark=7507，其中 5625 是背景黑）
         * 数字都很好看，但一个字不画它也会通过 —— 等于没写。
         *
         * 块底色和块里的字色都是**不透明纯色填充**，直接比精确值。
         */
        var box = 0
        var dark = 0
        var bx0 = Int.MAX_VALUE
        var by0 = Int.MAX_VALUE
        var bx1 = -1
        var by1 = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                when (bmp.getPixel(x, y)) {
                    BrandMark.BOX_COLOR -> {
                        box++
                        if (x < bx0) bx0 = x
                        if (y < by0) by0 = y
                        if (x > bx1) bx1 = x
                        if (y > by1) by1 = y
                    }
                    /* 深字是带蓝调的 (38,43,51)。白字的抗锯齿边缘只能产生灰度(R=G=B)，
                       凑不出这个值，所以这份计数不会被白字污染。 */
                    BrandMark.BOX_TEXT_COLOR -> dark++
                }
            }
        }

        /*
         * 先落盘、再断言。
         *
         * 上一轮这里是反过来的（断言在前、落盘在后），结果断言一挂就抛异常，
         * PNG 停在修复**之前**的那一版 —— 之后再拿这张图去"看图排查"，
         * 看到的根本不是当前代码画的东西，白烧了一轮。证据必须先于结论产生。
         */
        File(outDir, "J-brand-box.png").also {
            FileOutputStream(it).use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) }
        }

        println("灰块纯色 ≈ $box px，范围 x $bx0..$bx1  y $by0..$by1")
        println("块内深字纯色 ≈ $dark px")

        /* 块 = 4.22em × 1.23em，em ≈ 28.57 → 120.6×35.1 ≈ 4230px²；扣掉字墨迹与
           抗锯齿边缘后实测纯色约 1387。下限 800 留了 1.7 倍余量，字重变化吃得下。 */
        assertTrue("没找到浅灰块（精确块色 $box px）—— 底色没铺上或者块没画", box > 800)
        assertTrue("灰块里的字没画出来（精确深字色 $dark px）—— 或者被画成了和底同色", dark > 500)
    }

    /* ---------------- 路径解析器 ---------------- */

    @Test
    fun 矢量路径解析_四种命令与隐式续写() {
        assertEquals(4, BrandMarkPaths.NAME.size)
        assertEquals(6, BrandMarkPaths.SLOGAN.size)
        /* 生成器只产 M / L / Q / Z，且都是绝对坐标 —— 出现别的字母就是生成器变了 */
        for (d in BrandMarkPaths.NAME + BrandMarkPaths.SLOGAN) {
            val cmds = d.filter { it.isLetter() }.toSet()
            assertTrue("路径里出现了预期外的命令 $cmds", cmds.all { it in "MLQZ" })
        }
        println("路径总长 " +
            (BrandMarkPaths.NAME + BrandMarkPaths.SLOGAN).sumOf { it.length } + " 字符")
    }

    /** 两个字面宽高比来自字体，不是随手填的常量 —— 填错会让灰块明显偏大或偏小 */
    @Test
    fun 矢量路径_灰块用的墨迹并集在合理范围() {
        val l = BrandMarkPaths.SLOGAN_INK_LEFT
        val r = BrandMarkPaths.SLOGAN_INK_RIGHT
        val t = BrandMarkPaths.SLOGAN_INK_TOP
        val b = BrandMarkPaths.SLOGAN_INK_BOTTOM
        println("真实可验 墨迹并集 x=$l..$r  y=$t..$b")
        assertTrue("4 个汉字横向应占 3.5~4.0 em，实际 ${r - l}", r - l in 3.5f..4.0f)
        assertTrue("汉字墨迹高应在 0.8~1.0 em，实际 ${b - t}", b - t in 0.8f..1.0f)
        assertTrue("墨迹顶应在基线上方", t < 0f)
        assertTrue("墨迹底应在基线下方", b > 0f)
    }
}
