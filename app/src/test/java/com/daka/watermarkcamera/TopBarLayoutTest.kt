package com.daka.watermarkcamera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 顶部栏不许被地点文字顶爆。
 *
 * 线上现象：地点一长（`星海市云山区·湖滨社区风和苑商业广场`），
 * 右侧的 ⚙ 设置按钮被挤出屏幕，只显示一半。
 *
 * 根因是地点胶囊写的是 `wrap_content` + `maxWidth="190dp"`：
 * LinearLayout 遇到「子项需求宽度之和 > 容器宽」时**不会压缩前面的子项**，
 * 只会把后面的子项摆到容器外面去 —— 于是最后一个控件正好被屏幕裁掉。
 *
 * 这种问题看代码很难发现（每一行的宽度都是合理的），只能**真的量一遍**。
 * 所以这里 inflate 真实布局、按真实屏幕宽度 measure + layout，
 * 断言右边三个控件的右边界都在栏内。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
class TopBarLayoutTest {

    /**
     * inflate 真实布局、填上地点、按给定屏幕尺寸量一遍。
     * 同时把代码里 applyInsets() 会加的左右 14dp 内边距也模拟上 ——
     * 漏掉它就会比真实情况宽松 28dp，等于白测。
     */
    private fun layoutWithAddress(addr: String, widthPx: Int, heightPx: Int): ViewGroup {
        val ctx = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null) as ViewGroup
        root.findViewById<TextView>(R.id.gpsTxt).text = addr

        val topBar = root.findViewById<ViewGroup>(R.id.topBar)
        val h = (14 * ctx.resources.displayMetrics.density).toInt()
        topBar.setPadding(h, topBar.paddingTop, h, topBar.paddingBottom)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, widthPx, heightPx)
        return root
    }

    private fun assertNothingSticksOut(root: ViewGroup, scene: String) {
        val topBar = root.findViewById<ViewGroup>(R.id.topBar)
        val gps = root.findViewById<View>(R.id.gpsPill)
        val cnt = root.findViewById<View>(R.id.cntPill)
        val flash = root.findViewById<View>(R.id.btnFlash)
        val settings = root.findViewById<View>(R.id.btnSettings)

        println(
            "$scene  屏宽=${root.width} 栏宽=${topBar.width} " +
                "地点右=${gps.right} 张数右=${cnt.right} 闪光右=${flash.right} 设置右=${settings.right}"
        )

        assertTrue(
            "$scene：设置按钮右边界 ${settings.right} 超出顶栏宽度 ${topBar.width}",
            settings.right <= topBar.width
        )
        assertTrue(
            "$scene：闪光灯按钮右边界 ${flash.right} 超出顶栏宽度 ${topBar.width}",
            flash.right <= topBar.width
        )
        assertTrue(
            "$scene：张数胶囊右边界 ${cnt.right} 超出顶栏宽度 ${topBar.width}",
            cnt.right <= topBar.width
        )
        /* 地点再长也只能占自己那份，不许压在张数胶囊上 */
        assertTrue("$scene：地点胶囊压住了张数胶囊", gps.right <= cnt.left)
    }

    /** 顶栏本身确实满屏宽 —— 否则「不溢出」是假的 */
    @Test
    fun 顶栏本身是满屏宽() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val root = layoutWithAddress("已定位", dm.widthPixels, dm.heightPixels)
        assertEquals(root.width, root.findViewById<ViewGroup>(R.id.topBar).width)
    }

    /** 这就是线上翻车时的那个地点，原样复现 */
    @Test
    fun 地点超长时_右侧按钮仍然完整在栏内() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val root = layoutWithAddress(
            "星海市云山区·湖滨社区风和苑商业广场写字楼B座2单元",
            dm.widthPixels, dm.heightPixels
        )
        assertNothingSticksOut(root, "地点超长")
    }

    /** 地点短的时候也不能出事（回归：别为了修长文案把短文案搞坏） */
    @Test
    fun 地点很短时_布局照常() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val root = layoutWithAddress("已定位", dm.widthPixels, dm.heightPixels)
        assertNothingSticksOut(root, "地点很短")
    }

    /**
     * 最窄的老机型也别溢出。320dp 是常见下限，比参考机更窄，用它卡住下限。
     */
    @Test
    fun 窄屏_320dp也放得下() {
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        val w = (320 * dm.density).toInt()
        val root = layoutWithAddress("星海市云山区·湖滨社区风和苑商业广场", w, dm.heightPixels)
        assertNothingSticksOut(root, "窄屏 320dp")
    }
}
