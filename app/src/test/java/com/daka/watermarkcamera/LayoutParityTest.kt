package com.daka.watermarkcamera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 两份布局的 id 集合必须一致 —— 代码只认 id，不认排布。
 *
 * ## 这条测试是补一个真 bug 的护栏
 *
 * 这个 App 有两份 `activity_main.xml`：`layout/`（竖屏形态）和 `layout-land/`（横屏形态，
 * 冷启动横屏时用）。主界面从不关心自己在哪一份上面，它只按 id 找 view。
 *
 * 危险在于**漏 id 不会报错**：ViewBinding 发现某个 id 只在部分变体里存在时，
 * 会把生成的字段标成**可空**，而主界面对可空字段写的是安全调用 ——
 * 于是「点了毫无反应，logcat 也干干净净」。
 * 上一版就是这样：`layout-land` 漏了 `btnGallery`，**横着启动 App 点不到「从相册加水印」**，
 * 而且只有真机上横着开机才会遇到（旋转进横屏走的是竖屏那份布局，见 README §3.2）。
 *
 * 所以这里不看代码、不看注释，**直接把两份布局都 inflate 出来逐条找 id**。
 * 将来往任何一份里加控件，只要另一份忘了，这条就红。
 *
 * ## 两个提醒
 *
 * - 每条用例都用限定符**确认自己真的测到了要测的那份**：`topSpacer` 只有竖屏那份有，
 *   于是"竖屏该有、横屏该没有"正好当了试纸。少了这一步，限定符写错时
 *   两条用例会双双在测同一份布局、一起绿，等于什么都没测。
 * - 限定符顺序不能乱：Android 规定 `land` 要排在 `w`/`h` **之后**，
 *   写成 `land-w640dp-h360dp` 会被 QualifierParser 直接抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutParityTest {

    /**
     * 代码（`MainActivity` / `HudSize` / 其它测试）会按 id 去找的 view。
     *
     * **唯一不在这个表里的是 `topSpacer`**：它对代码是可选参数
     * （`HudSize.applyTopBarWidths` 收 `View?`），横屏那份的顶栏本来就不吃满宽度、
     * 不需要那根撑杆，所以允许只存在于竖屏那份 —— 上面那两条试纸断言就是拿它做的。
     */
    private val 代码要用的id = listOf(
        R.id.root,
        R.id.previewBox,
        R.id.previewView,
        R.id.overlay,
        R.id.topBar,
        R.id.gpsPill,
        R.id.gpsDot,
        R.id.gpsTxt,
        R.id.cntPill,
        R.id.bottomStack,
        R.id.bottomBar,
        R.id.zoomScroll,
        R.id.zoomBar,
        R.id.btnFlash,
        R.id.btnSettings,
        R.id.btnFlip,
        R.id.shutter,
        R.id.thumb,
        R.id.btnGallery
    )

    private fun inflate(): ViewGroup =
        LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.activity_main, null) as ViewGroup

    private fun assertAllIdsPresent(scene: String, root: ViewGroup) {
        val ctx = RuntimeEnvironment.getApplication()
        val missing = 代码要用的id
            .filter { root.findViewById<View>(it) == null }
            .map { ctx.resources.getResourceEntryName(it) }

        assertTrue(
            "$scene 少了代码要用的 ${missing.size} 个 id：$missing —— " +
                "缺一个，ViewBinding 就会把它生成成可空字段，主界面用安全调用，" +
                "表现为「点了没反应也不报错」",
            missing.isEmpty()
        )
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
    fun 竖屏那份布局_代码要用的id一个都不少() {
        val root = inflate()
        assertAllIdsPresent("layout/activity_main.xml", root)

        assertNotNull(
            "限定符没选中 layout/activity_main.xml？topSpacer 是竖屏那份独有的",
            root.findViewById<View>(R.id.topSpacer)
        )
    }

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-xhdpi")
    fun 横屏那份布局_代码要用的id一个都不少() {
        val root = inflate()
        assertAllIdsPresent("layout-land/activity_main.xml", root)

        /* 试纸：真测到了 layout-land 才会没有 topSpacer */
        assertNull(
            "限定符没选中 layout-land/activity_main.xml？topSpacer 只有竖屏那份才有",
            root.findViewById<View>(R.id.topSpacer)
        )

        /*
         * 这一条单独再点一遍名：它是这个测试类诞生的原因，
         * 而"相册入口"又是最容易被落下的一类 —— 它不在顶栏、也不在快门旁边，
         * 是后加的功能，加的时候很容易只往竖屏那份塞。
         */
        assertNotNull(
            "横屏那份没有相册入口，横着启动 App 就点不到「从相册加水印」",
            root.findViewById<View>(R.id.btnGallery)
        )
    }
}
