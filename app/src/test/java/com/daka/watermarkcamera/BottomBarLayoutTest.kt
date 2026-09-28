package com.daka.watermarkcamera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageButton
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 底栏不许被新按钮顶爆。
 *
 * 这一版在缩略图右边加了「相册加水印」入口。底栏中间是两个 `weight=1` 的
 * 撑杆、两边是固定宽度的按钮 —— 固定项的总宽一旦超过屏宽，撑杆会被压到 0，
 * 然后最右边的按钮被挤到屏幕外（和顶栏当初"⚙ 显示不全"是同一个机理）。
 * 所以加了控件就得**真的量一遍**，别等用户截图说右边少了个东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
class BottomBarLayoutTest {

    /** 按真实屏幕尺寸量一遍，并把代码里 applyInsets() 会加的右侧内边距也补上 */
    private fun layout(widthPx: Int, heightPx: Int): ViewGroup {
        val ctx = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null) as ViewGroup
        val bottom = root.findViewById<ViewGroup>(R.id.bottomBar)
        val h = (14 * ctx.resources.displayMetrics.density).toInt()
        bottom.setPadding(bottom.paddingStart, bottom.paddingTop, h, h)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, widthPx, heightPx)
        return root
    }

    /**
     * 相册入口必须真的拿得到。
     *
     * ViewBinding 把它生成了**可空字段**（别的按钮都不是这样），主界面因此用的是
     * 安全调用 —— 万一真的为 null，点下去毫无反应且不报错，属于最难查的一类。
     * 这里绕过 binding、直接从布局里把 view 捞出来盯着：真丢了，测试先红。
     */
    @Test
    fun 相册入口真的存在() {
        val root = layout(1080, 1920)
        assertNotNull(
            "底栏的相册按钮没找到，从相册加水印的入口会静默失效",
            root.findViewById<ImageButton>(R.id.btnGallery)
        )
    }

    private fun assertBarFits(root: ViewGroup, scene: String) {
        val bar = root.findViewById<ViewGroup>(R.id.bottomBar)
        val thumb = root.findViewById<View>(R.id.thumb)
        val gallery = root.findViewById<View>(R.id.btnGallery)
        val shutter = root.findViewById<View>(R.id.shutter)
        val flip = root.findViewById<View>(R.id.btnFlip)

        println(
            "$scene  屏宽=${root.width} 栏宽=${bar.width} " +
                "缩略图=${thumb.left}..${thumb.right} 相册=${gallery.left}..${gallery.right} " +
                "快门=${shutter.left}..${shutter.right} 翻转=${flip.left}..${flip.right}"
        )

        listOf("缩略图" to thumb, "相册" to gallery, "快门" to shutter, "翻转" to flip)
            .forEach { (name, v) ->
                assertTrue("$scene：$name 左边越出屏幕 (${v.left})", v.left >= 0)
                assertTrue("$scene：$name 右边越出屏幕 (${v.right} > ${bar.width})", v.right <= bar.width)
            }
        assertTrue("$scene：相册按钮压住了缩略图", gallery.left >= thumb.right)
        assertTrue("$scene：快门压住了相册按钮", shutter.left >= gallery.right)
        assertTrue("$scene：翻转按钮压住了快门", flip.left >= shutter.right)
    }

    @Test
    fun 常规屏_底栏不溢出() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        assertBarFits(layout(dm.widthPixels, dm.heightPixels), "360dp")
    }

    /** 320dp 是常见的老机型下限，比目标机更窄，用它卡住底线 */
    @Test
    fun 窄屏_320dp也放得下() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val w = (320 * dm.density).toInt()
        assertBarFits(layout(w, dm.heightPixels), "窄屏 320dp")
    }

    /**
     * 加了倍率档位行之后，操作栏不许被顶出屏幕、倍率行也不许压到它。
     *
     * 倍率行是**竖着叠在操作栏上面**的（布局里的 `bottomStack`），所以它的高度
     * 会把"底部这一坨"整体往上推。推本身推不坏，但如果哪天有人把它改回
     * `layout_gravity` + 写死的 marginBottom，快门和倍率行就会压在一起 ——
     * 那种毛病看代码看不出来，只能真的量一遍。
     *
     * 用**五档 + 最宽的标签**（0.6x 那格比 1x 宽）当最坏情况。
     */
    @Test
    fun 五档倍率行_不把操作栏顶出屏幕也不压住它() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null) as ViewGroup
        val row = root.findViewById<LinearLayout>(R.id.zoomBar)
        val wrap = root.findViewById<View>(R.id.zoomScroll)
        ZoomBar(ctx, row, wrap).bind(0.6f, 8f)   // 超广角 + 8x = 五档，最宽的一组

        val bar = root.findViewById<ViewGroup>(R.id.bottomBar)
        val inset = (14 * ctx.resources.displayMetrics.density).toInt()
        bar.setPadding(bar.paddingStart, bar.paddingTop, inset, inset)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)

        val shutter = root.findViewById<View>(R.id.shutter)
        /*
         * 档位的实际占地：第一格没有 marginStart，所以末格右边界就是内容总宽。
         * 不能用 row.width —— 外面 fillViewport 会把它拉满视口，量出来永远是视口宽。
         */
        val chipsW = row.getChildAt(row.childCount - 1).right - row.getChildAt(0).left
        val viewport = wrap.width - wrap.paddingLeft - wrap.paddingRight
        println(
            "五档倍率  屏=${w}x${h} 倍率行=${wrap.top}..${wrap.bottom} 视口=$viewport " +
                "档位总宽=$chipsW 底栏=${bar.top}..${bar.bottom} 快门=${shutter.top}..${shutter.bottom}"
        )

        assertEquals("最坏情况该有五格", 5, row.childCount)
        assertEquals("有档位时倍率行必须是可见的", View.VISIBLE, wrap.visibility)
        assertTrue("倍率行压在操作栏上 (${wrap.bottom} > ${bar.top})", wrap.bottom <= bar.top)
        assertTrue("操作栏底边越出屏幕 (${bar.bottom} > $h)", bar.bottom <= h)
        assertTrue("快门底边越出屏幕 (${shutter.bottom} > $h)", shutter.bottom <= h)
        assertTrue("快门被顶到屏幕上方了 (${shutter.top})", shutter.top >= 0)

        assertTrue("倍率行比屏幕还宽 (${wrap.width} > $w)", wrap.width <= w)
        /*
         * 外面套了 HorizontalScrollView 是兜底，"能拖"不代表"该拖" ——
         * 五档（含最宽的 0.6x）在 360dp 上就该一屏放得下。
         * 真要拖才能看全，说明档位给多了，该回去调 Zoom.MAX_CHIPS。
         */
        assertTrue("五档在 360dp 上排不下，得靠拖 (${chipsW} > ${viewport})", chipsW <= viewport)
    }

    /**
     * 从根一路加上去的**绝对**坐标 `[left, top, right, bottom]`。
     *
     * 横屏那份布局里，操作栏和倍率行都嵌在靠右站的 `bottomStack` 里，
     * 直接读 `bar.right` 得到的是**相对 bottomStack** 的数（实测 492），
     * 拿去和屏宽 1280 比会得出"操作栏没贴右边"的假结论 —— 量错了对象。
     * 竖屏那份因为是 `match_parent`，局部坐标恰好等于绝对坐标，所以一直没暴露这个坑。
     */
    private fun absRect(v: View): IntArray {
        var l = v.left
        var t = v.top
        var p: ViewParent? = v.parent
        while (p is View) {
            l += p.left
            t += p.top
            p = p.parent
        }
        return intArrayOf(l, t, l + v.width, t + v.height)
    }

    /**
     * 横屏那份布局也要真的 inflate 一遍。
     *
     * 横屏是**另一套 XML**（`layout-land/activity_main.xml`），加倍率行时它被整个重排过
     * （操作栏 + 倍率行叠进 `bottomStack`、操作栏改成靠右）—— 而在此之前
     * **没有任何测试在看这个文件**。竖屏那份已经有几条护栏，横屏这边一旦 id 写错、
     * 少一个 view、或者叠错方向，只有真机上把手机转一下才会发现。
     *
     * 横屏最容易踩的坑是"右侧那排按钮被导航栏压住"，所以四个边界都要量。
     *
     * 限定符顺序**不能随便写**：Android 规定 orientation 排在 w/h **之后**，
     * 写成 `land-w640dp-h360dp` 会被 QualifierParser 直接抛
     * IllegalArgumentException（报在 QualifierParser.java，看不出是我们写错了）。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-xhdpi")
    fun 横屏布局_倍率行与操作栏都在_且不越界() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null) as ViewGroup
        val row = root.findViewById<LinearLayout>(R.id.zoomBar)
        val wrap = root.findViewById<View>(R.id.zoomScroll)
        ZoomBar(ctx, row, wrap).bind(1f, 8f)

        val bar = root.findViewById<ViewGroup>(R.id.bottomBar)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)

        val shutter = root.findViewById<View>(R.id.shutter)
        val flash = root.findViewById<View>(R.id.btnFlash)
        println(
            "横屏  屏=${w}x$h " +
                "倍率行=${absRect(wrap).joinToString(",")} " +
                "底栏=${absRect(bar).joinToString(",")} " +
                "快门=${absRect(shutter).joinToString(",")}"
        )

        assertEquals("横屏也该有五个档位", 5, row.childCount)
        listOf("倍率行" to wrap, "操作栏" to bar, "快门" to shutter, "闪光灯" to flash)
            .forEach { (name, v) ->
                val r = absRect(v)
                assertTrue("横屏：$name 左边越出屏幕 (${r[0]})", r[0] >= 0)
                assertTrue("横屏：$name 右边越出屏幕 (${r[2]} > $w)", r[2] <= w)
                assertTrue("横屏：$name 底边越出屏幕 (${r[3]} > $h)", r[3] <= h)
                assertTrue("横屏：$name 顶边跑到屏幕上方了 (${r[1]})", r[1] >= 0)
            }
        /* 这两个是同一个父（bottomStack）下的兄弟，局部坐标同源，可以直接比 */
        assertTrue("横屏：倍率行压在操作栏上", wrap.bottom <= bar.top)
        /* 操作栏必须靠右站（横屏的 4:3 画面居中、左右留黑边，按钮收在右黑边上） */
        assertTrue("横屏：操作栏没贴右边 (${absRect(bar)[2]} vs $w)", absRect(bar)[2] > w * 0.8f)
    }
}
