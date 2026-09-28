package com.daka.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 倍率档位条：档位对不对、藏得对不对、高亮亮在哪一格、胶囊有没有真的画出来。
 *
 * 用真实布局（[R.layout.activity_main] 里的 `zoomBar` / `zoomScroll`），
 * 因为"档位条被包在滚动容器里、没档位时整行要藏"这两件事只有连着布局看才算数。
 *
 * `NATIVE` 图形模式只为了最后那条**数像素**的用例（胶囊的实心色与居中）。
 * 注意它**画不出 TextView 的字** —— 详见那条用例的注释，别指望在这儿存预览图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomBarTest {

    private fun labels(r: LinearLayout): List<String> =
        (0 until r.childCount).map { (r.getChildAt(it) as TextView).text.toString() }

    private fun selected(r: LinearLayout): List<Boolean> =
        (0 until r.childCount).map { r.getChildAt(it).isSelected }

    /** 用真实布局造一条档位条，连同包着它的滚动容器一起 */
    private fun newBar(): Pair<ZoomBar, LinearLayout> {
        val ctx = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null) as ViewGroup
        val r = root.findViewById<LinearLayout>(R.id.zoomBar)
        return ZoomBar(ctx, r, root.findViewById(R.id.zoomScroll)) to r
    }

    @Test
    fun 不能变焦的摄像头_整行藏起来() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 1f)
        assertEquals(0, r.childCount)
        assertEquals("连外层滚动容器一起藏 —— 只藏 row 会留下它的内边距", View.GONE, r.visibility)
        assertTrue(ctl.presets.isEmpty())
    }

    @Test
    fun 八倍设备_五格_文字与档位一致() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        assertEquals(listOf("1x", "2x", "3x", "5x", "8x"), labels(r))
        assertEquals(View.VISIBLE, r.visibility)
        assertEquals(1f, ctl.min, 1e-4f)
        assertEquals(8f, ctl.max, 1e-4f)
    }

    /**
     * 相机能力没变就不许重建按钮。
     *
     * `zoomState` 是每帧都可能回调的 LiveData，捏合时一秒回几十次；
     * 每次都 removeAllViews + 新建 5 个 TextView，既疯狂分配，
     * 又会把用户按下的那一格直接换掉 —— 表现是"捏合之后点档位经常不灵"。
     */
    @Test
    fun 相机能力没变_不重建按钮() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        val first = r.getChildAt(0)
        ctl.bind(1f, 8f)
        ctl.bind(1f, 8f)
        assertSame("档位没变就不该重建", first, r.getChildAt(0))
    }

    @Test
    fun 换了摄像头_能力变了才重建() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        val first = r.getChildAt(0)
        /* 切前摄：很多前摄只到 1x，档位条该整个消失 */
        ctl.bind(1f, 1f)
        assertEquals(View.GONE, r.visibility)
        ctl.bind(1f, 4f)
        assertNotSame(first, r.getChildAt(0))
        assertEquals(listOf("1x", "2x", "3x", "4x"), labels(r))
    }

    @Test
    fun 高亮只落在当前倍数那一格() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        ctl.markSelected(3f)
        assertEquals(listOf(false, false, true, false, false), selected(r))
        ctl.markSelected(8f)
        assertEquals(listOf(false, false, false, false, true), selected(r))
    }

    /**
     * 捏合出来的值落在两档之间时，**一格都不该亮**。
     *
     * 留着上一次的高亮不改是这类 UI 最常见的谎：用户捏到了 4x，
     * 界面上 3x 还亮着，他会以为捏合没生效。
     */
    @Test
    fun 捏出来的倍数不在档位上_一格都不亮() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        ctl.markSelected(3f)
        ctl.markSelected(4f)
        assertTrue("4x 不属于任何一档，不该假高亮", selected(r).none { it })
    }

    @Test
    fun 点某一格_回调它自己的倍数() {
        val (ctl, r) = newBar()
        var picked = -1f
        ctl.onPick = { picked = it }
        ctl.bind(1f, 8f)
        r.getChildAt(4).performClick()
        assertEquals(8f, picked, 1e-4f)
        r.getChildAt(0).performClick()
        assertEquals(1f, picked, 1e-4f)
    }

    @Test
    fun 点完之后立刻高亮_不等相机回调() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        /* onPick 回调里做的事和 MainActivity.setZoom 一样：先亮起来，手感才不粘 */
        ctl.onPick = { ctl.markSelected(it) }
        r.getChildAt(1).performClick()
        assertEquals(listOf(false, true, false, false, false), selected(r))
    }

    /** 每一格都该有可读的无障碍描述 —— 只写"1x"对读屏用户等于没信息 */
    @Test
    fun 每一格都有无障碍描述() {
        val (ctl, r) = newBar()
        ctl.bind(1f, 8f)
        val descs = (0 until r.childCount).map { r.getChildAt(it).contentDescription?.toString() }
        println("倍率条无障碍描述 = $descs")
        assertEquals(
            listOf("原始倍率，不放大", "放大 2x", "放大 3x", "放大 5x", "放大 8x"),
            descs
        )
    }

    @Test
    fun 带超广角_零点六倍那格也在() {
        val (ctl, r) = newBar()
        ctl.bind(0.6f, 8f)
        assertEquals(listOf("0.6x", "1x", "2x", "3x", "5x"), labels(r))
    }

    /**
     * 胶囊**真的画出来了**，且选中态就是那个强调色。
     *
     * ## 为什么是数像素，不是存一张 PNG 给人看
     *
     * Robolectric 的 NATIVE 图形能画 `Canvas.drawText`（水印那 18 张渲染图就是走这条路），
     * 但 **`TextView` 走的 `Layout.draw` 在它下面不落墨** —— 实测：
     * 未选中胶囊区域里最亮的像素只到 `(64,68,75)`（= 背景灰 + 12% 浅色），
     * 没有任何接近文字色 `(232,236,241)` 的像素。存出来的图会是"五个空胶囊"，
     * 看着像字体丢了，其实是模拟器的限制 —— 这种图交出去比不交还糟。
     *
     * 所以这里只核对**胶囊本身**：圆角矩形有没有真的画出来、选中的那格是不是 accent。
     * 这两件事恰好是"资源写错/选了不存在的 drawable/背景没生效"这类毛病唯一能自动抓的地方。
     * 字形长什么样只能真机看，这一点如实写在 README §五。
     */
    @Test
    fun 胶囊真的画出来了_选中态是强调色() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null) as ViewGroup
        val row = root.findViewById<LinearLayout>(R.id.zoomBar)
        val wrap = root.findViewById<View>(R.id.zoomScroll)
        val ctl = ZoomBar(ctx, row, wrap)
        ctl.bind(1f, 8f)          // 1x 2x 3x 5x 8x
        ctl.markSelected(3f)      // 第 3 格

        root.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 720, 1280)

        /* 垫一层纯色当"相机画面"，胶囊底是半透明的，铺在透明底上量不出合成色 */
        val d = ctx.resources.displayMetrics.density
        val padBottom = (10 * d).toInt()
        val chipH = (30 * d).toInt()
        val backdrop = 0xFF2A2F36.toInt()
        val bmp = Bitmap.createBitmap(wrap.width, wrap.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(backdrop)
        c.translate(-wrap.left.toFloat(), -wrap.top.toFloat())
        wrap.draw(c)

        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        fun at(x: Int, y: Int) = px[y * bmp.width + x]

        /* 沿胶囊竖直中点横扫一行，按"不是背影色"切出每一格 */
        val midY = bmp.height - padBottom - chipH / 2
        val runs = mutableListOf<IntArray>()
        var s = -1
        for (x in 0 until bmp.width) {
            val on = at(x, midY) != backdrop
            if (on && s < 0) s = x
            if (!on && s >= 0) {
                runs.add(intArrayOf(s, x - 1))
                s = -1
            }
        }
        if (s >= 0) runs.add(intArrayOf(s, bmp.width - 1))

        println(
            "档位条像素扫描  图=${bmp.width}x${bmp.height} 扫描行y=$midY " +
                "胶囊=${runs.joinToString(" ") { "${it[0]}..${it[1]}" }}"
        )

        assertEquals("该切出五格", 5, runs.size)

        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val pill = at(runs[1][0] + 4, midY)   // 未选中那格靠近左边缘的实心处

        runs.forEachIndexed { i, r ->
            val cx = (r[0] + r[1]) / 2
            val got = at(cx, midY)
            if (i == 2) {
                assertEquals(
                    "选中那格该是纯 accent 实心 (runs[$i]=${r[0]}..${r[1]})",
                    accent,
                    got
                )
            } else {
                assertTrue(
                    "未选中那格该是半透明黑压在画面上（expected≈${Integer.toHexString(pill)}，" +
                        "got=${Integer.toHexString(got)}）",
                    Math.abs((got and 0xFF) - (pill and 0xFF)) <= 4 &&
                        Math.abs((got shr 8 and 0xFF) - (pill shr 8 and 0xFF)) <= 4 &&
                        Math.abs((got shr 16 and 0xFF) - (pill shr 16 and 0xFF)) <= 4
                )
            }
            /* 每格都该是 30dp 高，说明行高没被压扁 */
            var top = -1
            var bottom = -1
            for (y in 0 until bmp.height) {
                if (at(cx, y) != backdrop) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            assertEquals("第 ${i + 1} 格高度该是 30dp", chipH, bottom - top + 1)
        }

        /*
         * 档位整组是**水平居中**的，所以首格左边距 = 16dp 内边距 + 居中偏移。
         * 实测 126 = 32 + 94，其中 94 = (656 视口 − 468 内容) / 2 —— 一次把
         * 内边距与居中两件事都验到。
         *
         * 别断言"首格在 32"：那会把正确的居中实现误判成 bug（我第一版就是这么写的）。
         */
        val padPx = (16 * d).toInt()
        val leftGap = runs[0][0] - padPx
        val rightGap = (bmp.width - padPx) - runs.last()[1]
        val contentW = runs.last()[1] - runs[0][0] + 1
        println(
            "档位整组居中  左边距=$leftGap 右边距=$rightGap 内容宽=$contentW " +
                "视口=${bmp.width - padPx * 2} 共${runs.size}格"
        )
        assertTrue(
            "档位整组该水平居中（左 $leftGap / 右 $rightGap）",
            abs(leftGap - rightGap) <= 1
        )
        /* 左右都有余量 = 五档一屏放得下，HorizontalScrollView 只是兜底 */
        assertTrue("五档在 360dp 上没排下（左边距 $leftGap），得靠横向拖", leftGap > 0)
    }
}
