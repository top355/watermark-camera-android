package com.daka.watermarkcamera

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max
import kotlin.math.min

/**
 * 取景界面上那些浮层（HUD）的宽度策略。
 *
 * 为什么值得单独一个文件：这些数字决定"提示条有多长"，而它恰恰是最容易失控的
 * 地方 —— 屏幕上多出来的宽度会被"吃满剩余宽度"的视图**全部吸收掉**。
 * 顶栏的地点胶囊和提示条(Snackbar)都是这种视图，竖屏看着正常，
 * 到横屏（屏宽 800dp 起）就变成横贯全屏的一条。
 *
 * 数字集中在这一处，纯粹的部分（[HudSize.gpsCanHug] / [HudSize.snackWidthPx]）
 * 可以拿普通 JUnit 跑，不需要 Robolectric —— 门槛差 1dp 就换一支代码路径，
 * 用肉眼看是看不出来的。
 */
internal object HudSize {

    /* ------------------------- 提示条 Snackbar ------------------------- */

    /**
     * 提示条宽度上限。
     *
     * 不封顶的话，横屏会得到一条铺满整宽的条：十几个字挤在正中间，
     * 左右各几百 dp 全是空的。400dp 是"一行中文 + 一个按钮"舒服的宽度，
     * 也差不多是竖屏本来就会拿到的宽度（360dp 屏减两侧 16dp = 328dp），
     * 于是横屏竖屏看起来是同一个东西。
     */
    const val SNACK_MAX_DP = 400

    /** 提示条两侧留白。封顶之后必须左右居中，否则会贴着屏幕左边 */
    const val SNACK_SIDE_DP = 16

    /* ------------------------- 顶栏地点胶囊 ------------------------- */

    /**
     * 胶囊里文字的宽度上限（12.5sp 中文约 16 个字）。
     *
     * 超出就省略号。地址的全文在水印上，顶栏只是让人一眼确认"定的是这里"，
     * 没必要把一整条路名铺开。
     */
    const val GPS_TEXT_MAX_DP = 200

    /** 胶囊的"装饰"占位：左右内边距 26 + 圆点 7 + 间距 7 + 图标 13 + 间距 5 */
    const val GPS_CHROME_DP = 58

    /**
     * 屏宽到这个数才让胶囊"按内容收"。
     *
     * 下限是算出来的：胶囊最多 [gpsMaxDp]（258dp），顶栏里除它之外的固定占位
     * 最坏约 200dp（两侧内边距 24 + 张数胶囊 80 + 间距 8 + 闪光 38 + 间距 8 + 设置 38），
     * 加起来 458dp。取 520dp 留 60dp 安全边际 —— 张数变成三位数也不会顶出去。
     *
     * 手机竖屏（320~430dp）都落在这一支下面，所以这个数字实际只影响
     * **横屏**和**平板竖屏**。
     */
    const val GPS_HUG_MIN_SCREEN_DP = 520

    /** 胶囊最多能有多宽 */
    val gpsMaxDp: Int get() = GPS_TEXT_MAX_DP + GPS_CHROME_DP

    /**
     * 屏够不够宽，让胶囊"按内容收"。
     *
     * 不够宽时必须反过来让胶囊**吃掉剩余宽度** —— 那是窄屏上唯一的活路：
     * LinearLayout 遇到「子项需求宽度之和 > 容器宽」时**不会压缩前面的子项**，
     * 只会把后面的子项摆到容器外面去，于是最后一个控件被屏幕裁掉
     * （线上就是这么把 ⚙ 挤没的，见 `TopBarLayoutTest`）。
     */
    fun gpsCanHug(screenWidthPx: Int, density: Float): Boolean =
        screenWidthPx / density >= GPS_HUG_MIN_SCREEN_DP

    /**
     * 提示条宽度：不超上限，且两侧各留 [sidePx]。
     *
     * 屏窄到连两侧留白都塞不下时返回 0 —— 不要让宽度变成负数，
     * `ViewGroup.LayoutParams` 收到负值另有含义（-1 = MATCH_PARENT，会得到一个满屏的条）。
     */
    fun snackWidthPx(screenWidthPx: Int, sidePx: Int, capPx: Int): Int =
        min(capPx, max(0, screenWidthPx - sidePx * 2))
}

/**
 * 顶栏地点胶囊的宽度策略。
 *
 * ## 为什么必须在代码里算，而不是在布局里写死
 *
 * Activity 声明了 `configChanges="orientation|screenSize"`，**旋转时不会重建、
 * 也不会重新充气布局** —— 屏幕上还是竖屏那套 View，只是被量成了横屏的宽度。
 * 所以 `layout-land` 只在"横着启动 App"那一次用得上；转到横屏时用的还是竖屏这份，
 * 于是「吃满剩余宽度」的胶囊跟着屏幕一起长到 680dp。
 *
 * ## 两支
 *
 * - **屏够宽**：胶囊 `wrap_content`（按内容收，最多 [HudSize.gpsMaxDp]），
 *   撑杆 [gap] 拿 `weight=1` 吃掉剩余宽度 —— 右侧三个控件仍然贴右边，和原来一样。
 * - **屏不够宽**：胶囊 `0dp + weight=1` 吃满剩余（见 [HudSize.gpsCanHug] 的说明），撑杆归零。
 *
 * XML 里写的是第二支，代码按当前屏宽切。
 *
 * ## 为什么参数长这样
 *
 * 都是现成的 View 而不是 Activity —— 布局测试 inflate 真实 XML 之后直接调它，
 * **测的就是线上跑的那一段代码**，不是复制品。
 *
 * [gap] 可空：横屏那份布局（`layout-land`）的顶栏本来就是 `wrap_content`，
 * 不需要这个撑杆，所以那个文件里没有这个 id（ViewBinding 因此生成可空字段）。
 */
internal fun applyTopBarWidths(
    pill: View,
    gap: View?,
    text: TextView,
    screenWidthPx: Int,
    density: Float
) {
    /*
     * 文字上限和"哪一支"是两件事，都要按当前屏宽重算 ——
     * 旋转回来时如果不复位，竖屏会留着横屏那次的 200dp 上限，
     * 胶囊没占满的部分会变成一段空白。
     */
    text.maxWidth = (HudSize.GPS_TEXT_MAX_DP * density).toInt()

    val pillLp = pill.layoutParams as? LinearLayout.LayoutParams ?: return
    val hug = HudSize.gpsCanHug(screenWidthPx, density)

    pillLp.width = if (hug) LinearLayout.LayoutParams.WRAP_CONTENT else 0
    pillLp.weight = if (hug) 0f else 1f
    pill.layoutParams = pillLp

    /*
     * 撑杆只在"屏够宽"那一支干活。写成局部变量再做空判断，
     * 是因为 `gap?.layoutParams` 推不出 `gap != null`（编译器不会跨这一步智能转换），
     * 而 `gap!!` 又会把"横屏那份布局里没有这个 id"变成一次崩溃。
     */
    val g = gap
    val gapLp = g?.layoutParams as? LinearLayout.LayoutParams
    if (g != null && gapLp != null) {
        gapLp.width = 0
        gapLp.weight = if (hug) 1f else 0f
        g.layoutParams = gapLp
    }
}

/**
 * 把提示条收窄并居中。
 *
 * Snackbar 默认铺满父容器，而父容器是**满屏**的：竖屏 360dp 还能看，
 * 横屏 800dp 以上就是一条又长又空的白条，字挤在正中间。
 *
 * 必须在 `show()` **之前**调。理由是 Material 只在
 * `onAttachedToWindow` / `onMeasure` 里改 **margin**（bottomMargin 让开导航栏），
 * 以及一个只做"上限再压小"的 `maxWidth` —— 都不会覆盖我们设的宽度；
 * 而 `show()` 之后视图立刻进入测量/布局，再改就是下一帧的事了。
 */
internal fun resizeSnack(snackbar: Snackbar, screenWidthPx: Int, density: Float) {
    val lp = snackbar.view.layoutParams ?: return
    lp.width = HudSize.snackWidthPx(
        screenWidthPx,
        (HudSize.SNACK_SIDE_DP * density).toInt(),
        (HudSize.SNACK_MAX_DP * density).toInt()
    )
    /*
     * 收窄之后必须以中线对齐，否则会贴在屏幕左边。
     * 只看 FrameLayout.LayoutParams：Snackbar 挂到 CoordinatorLayout 下时
     * 用的是另一套参数（那种情况由 Material 自己摆位，我们不去动它）。
     */
    if (lp is FrameLayout.LayoutParams) {
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }
    snackbar.view.layoutParams = lp
}
