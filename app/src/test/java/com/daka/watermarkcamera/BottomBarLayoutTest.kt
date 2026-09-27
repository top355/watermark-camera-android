package com.daka.watermarkcamera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
}
