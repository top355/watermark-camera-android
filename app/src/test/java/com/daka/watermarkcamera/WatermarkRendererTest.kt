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

    private fun render(
        name: String,
        cfg: WatermarkConfig,
        logoData: Bitmap? = null,
        landscape: Boolean = false,
        loc: LocInfo? = null
    ) {
        val w = if (landscape) 1920 else 1440
        val h = if (landscape) 1440 else 1920
        val bmp = scene(w, h)
        val c = Canvas(bmp)
        val now = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 27, 10, 15, 33) }
        WatermarkRenderer.draw(c, w.toFloat(), h.toFloat(), cfg, logoData, now, loc)

        val f = File(outDir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("渲染输出: " + f.absolutePath + " (" + f.length() + " bytes)")
        assertTrue("PNG 应该非空", f.length() > 1000L)
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
        assertTrue("应该已经缩过时间字号（基准 ${78f * 1440f / 1080f} → 现在 ${m[3]}）", m[3] < 78f * 1440f / 1080f)
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

    @Test
    fun 超长地点_只缩自己那一行() {
        val long = base().copy(
            addr = "云岭省星海市云山区城东街道办事处风和苑小区三期十二栋二单元",
            note = "正常",
            showNote = true
        )
        val texts = WatermarkRenderer.lineTexts(long, fixedNow, fixedLoc)
        val sizes = WatermarkRenderer.lineSizes(1440f, 1920f, long, fixedNow, fixedLoc)
        println("行文案 = $texts")
        println("行字号 = ${sizes.toList()}")

        val s = 1440f / 1080f
        assertTrue("地点那行必须缩到小于名义 42f: ${sizes[0]}", sizes[0] < 42f * s - 0.5f)
        assertTrue("但不能缩过头，下限是名义的 66%: ${sizes[0]}", sizes[0] >= 42f * s * 0.66f - 0.5f)

        /* 备注那行很短，不该跟着一起缩 —— 否则缩字号就变成了全局缩小 */
        val noteIdx = texts.indexOfFirst { it.startsWith("备注") }
        assertTrue("备注行应保持名义 34f: ${sizes[noteIdx]}", sizes[noteIdx] > 34f * s - 0.5f)
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
        /* s=1.333，pad = 40*s ≈ 53.3 → 右边界应贴住 1440-53.3 ≈ 1387 */
        val pad = 40f * (1440f / 1080f)
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
