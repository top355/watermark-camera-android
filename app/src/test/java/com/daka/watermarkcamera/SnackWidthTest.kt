package com.daka.watermarkcamera

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.snackbar.Snackbar
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 提示条的宽度 —— 真的把 Snackbar 建出来、show 出来、量一遍。
 *
 * 为什么不满足于只测 [HudSize.snackWidthPx] 那点算术：宽度是我们**绕过 Material
 * 自己塞进 layoutParams** 的，而 Material 在 attach / measure 时还会再动一次这套参数
 * （让开导航栏的 bottomMargin、以及一个"只把上限压更小"的 maxWidth）。
 * 它到底是"只改 margin"还是"顺带把宽度重置回 match_parent"，
 * 只看代码是看不出来的 —— 而线上表现就是"加了封顶但提示条还是满屏"，
 * 不报任何错、也不会有任何日志。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
class SnackWidthTest {

    /**
     * 必须**显式套一层 App 主题**，不能用 `getApplication()` 直接充气。
     *
     * Robolectric 里 Application 的主题是**平台默认**的
     * `Theme.DeviceDefault.Light.DarkActionBar`，不带 manifest 里那个 Material3 主题。
     * 而 Snackbar 会在内部再叠一层主题 overlay 去充气自己的布局（`design_layout_snackbar_include`），
     * 少了 Material3 的 `?attr/textAppearance*` 就会在 **inflate 阶段**抛
     * `UnsupportedOperationException: Failed to resolve attribute at index 6`，
     * 报出来是 `InflateException: Error inflating class android.widget.TextView` ——
     * 看着像"Snackbar 用不了"，其实是主题没给对。
     *
     * 真机上不存在这个坑：Activity 的 Context 本来就带着这个主题。
     */
    private val ctx: Context
        get() = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WatermarkCamera
        )

    private val d: Float get() = ctx.resources.displayMetrics.density

    private fun px(dp: Int) = (dp * d).toInt()

    /** 在一个给定尺寸的父容器里真的弹一条，摆好之后返回它 */
    private fun show(msg: String, screenW: Int, screenH: Int): Snackbar {
        val root = FrameLayout(ctx)
        val sb = Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
        resizeSnack(sb, screenW, d)
        sb.show()
        shadowOf(Looper.getMainLooper()).idle()

        /*
         * 先确认它真的挂上去了 —— 不然所有宽度断言都会在"宽度=0"上失败，
         * 报出来的是"提示条太窄"，而真实原因是 show() 没生效，查起来会绕远路。
         */
        assertTrue("提示条没被挂到父容器里，show() 没生效", sb.view.parent != null)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, screenW, screenH)
        return sb
    }

    /**
     * 横屏：不许铺满整宽。
     *
     * 这正是「下面的提示条也拉这么长」：Snackbar 默认铺满父容器，
     * 而父容器是满屏的 —— 横屏 800dp 上就是一条横贯全屏的白条。
     */
    @Test
    fun 横屏下提示条不铺满整宽() {
        val w = px(800)
        val sb = show("已保存到相册", w, px(360))
        val cap = px(HudSize.SNACK_MAX_DP)
        println("横屏 屏宽=$w 提示条=${sb.view.left}..${sb.view.right} 宽=${sb.view.width} 上限=$cap")

        assertTrue("提示条还是横贯整屏（${sb.view.width} / 屏宽 $w）", sb.view.width < w)
        assertTrue("提示条 ${sb.view.width} 超过上限 $cap", sb.view.width <= cap + 1)
    }

    /** 竖屏：本来就是"屏宽减两侧留白"，这条是防止封顶把竖屏改坏 */
    @Test
    fun 竖屏下两侧留白而不是顶到屏幕边() {
        val w = px(360)
        val sb = show("已保存到相册", w, px(640))
        val side = px(HudSize.SNACK_SIDE_DP)
        println("竖屏 屏宽=$w 提示条=${sb.view.left}..${sb.view.right} 留白=$side")

        assertTrue("左边没留白（${sb.view.left}）", sb.view.left >= side - 1)
        assertTrue("右边没留白（右边界 ${sb.view.right} / 屏宽 $w）", sb.view.right <= w - side + 1)
    }

    /** 收窄之后必须居中 —— 不然就是"左边一条短的、右边一大片空白" */
    @Test
    fun 收窄之后仍然居中() {
        val w = px(800)
        val sb = show("已保存到相册", w, px(360))
        val leftGap = sb.view.left
        val rightGap = w - sb.view.right
        println("横屏 左空=$leftGap 右空=$rightGap")

        assertTrue("没居中（左空 $leftGap / 右空 $rightGap）", abs(leftGap - rightGap) <= 2)
    }

    /** 提示条得待在底部（收窄不该把它挪到屏幕中间去） */
    @Test
    fun 仍然贴在底部() {
        val h = px(360)
        val sb = show("已保存到相册", px(800), h)
        val gap = h - sb.view.bottom
        println("横屏 提示条=${sb.view.top}..${sb.view.bottom} / 屏高 $h 距底=$gap")

        /*
         * 注意**不是**"严丝合缝贴到底"：Material 自己会留一点边距
         * （实测 8dp，来自它自己的 snackbar 布局/inset 处理）——
         * 那是既有行为，不是这次收窄引入的，所以别拿 0 当期望值。
         * 这里要卡的是"别被挪到屏幕中间去"：真挪中间了距底会是半个屏高。
         */
        assertTrue("提示条离底部太远（距底 $gap px，屏高 $h）", gap <= px(24))
        assertTrue("提示条顶边跑到屏幕上方了（${sb.view.top}）", sb.view.top >= 0)
    }
}
