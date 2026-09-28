package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏胶囊与提示条的宽度策略。
 *
 * 这两个数字是「横屏下提示条有多长」的唯一来源，而它恰恰最容易失控：
 * 屏幕上多出来的宽度会被"吃满剩余宽度"的视图**全部吸收掉**。
 *
 * 门槛两侧都要钉住 —— 差 1dp 就换一支代码路径，肉眼看代码看不出来。
 */
class HudSizeTest {

    /** 1080p 手机常见的 density */
    private val d = 2.75f

    private fun px(dp: Int) = (dp * d).toInt()

    /* ------------------------- 顶栏胶囊 ------------------------- */

    @Test
    fun 手机竖屏都走吃满剩余宽度那一支() {
        listOf(320, 360, 393, 411, 430).forEach { w ->
            assertFalse(
                "${w}dp 的竖屏不该按内容收 —— 那样地点一长，右侧按钮会被摆到屏幕外",
                HudSize.gpsCanHug(px(w), d)
            )
        }
    }

    @Test
    fun 横屏和平板竖屏走按内容收那一支() {
        listOf(520, 600, 640, 800, 915, 1280).forEach { w ->
            assertTrue("${w}dp 该按内容收，否则胶囊会横贯整屏", HudSize.gpsCanHug(px(w), d))
        }
    }

    @Test
    fun 门槛正好卡在520dp() {
        assertFalse("519dp 还差一点", HudSize.gpsCanHug(px(519), d))
        assertTrue("520dp 就到了", HudSize.gpsCanHug(px(520), d))
    }

    /**
     * 关键不变量：走「按内容收」那一支的屏幕，必须真的放得下
     * 「收窄后的胶囊 + 其余控件」。
     *
     * 放不下就说明门槛定低了，而那一支**没有压缩机制**（见 gpsCanHug 的说明）——
     * 表现会变成"胶囊不再横贯整屏，但设置图标被挤出屏幕"，
     * 也就是把旧 bug 换成了另一个旧 bug。
     */
    @Test
    fun 门槛屏放得下收窄后的胶囊和其余控件() {
        /* 除胶囊之外的固定占位（最坏）：两侧内边距 24 + 张数胶囊 80
           + 间距 8 + 闪光 38 + 间距 8 + 设置 38 */
        val reserve = 200
        assertTrue(
            "门槛 ${HudSize.GPS_HUG_MIN_SCREEN_DP}dp 放不下 " +
                "${HudSize.gpsMaxDp}dp 的胶囊 + ${reserve}dp 的其余控件",
            HudSize.GPS_HUG_MIN_SCREEN_DP >= HudSize.gpsMaxDp + reserve
        )
    }

    @Test
    fun 胶囊上限是文字上限加装饰占位() {
        assertEquals(258, HudSize.gpsMaxDp)
        assertTrue("胶囊上限该明显短于最窄机型的屏宽", HudSize.gpsMaxDp < 320)
    }

    /* ------------------------- 提示条 ------------------------- */

    private val side = HudSize.SNACK_SIDE_DP
    private val cap = HudSize.SNACK_MAX_DP

    @Test
    fun 提示条_竖屏拿到的是屏宽减两侧留白() {
        assertEquals(px(360 - side * 2), HudSize.snackWidthPx(px(360), px(side), px(cap)))
        assertTrue(
            "360dp 竖屏本来就够不到上限，这一支改了不该有变化",
            360 - side * 2 < cap
        )
    }

    @Test
    fun 提示条_横屏封在上限() {
        listOf(640, 800, 915, 1280).forEach { w ->
            assertEquals(
                "${w}dp 的横屏该封在上限",
                px(cap),
                HudSize.snackWidthPx(px(w), px(side), px(cap))
            )
        }
    }

    /**
     * 极窄屏（分屏、折叠机外屏）不能让宽度落到 0 以下 ——
     * `ViewGroup.LayoutParams` 收到负数另有含义（-1 = MATCH_PARENT），
     * 会得到一个比预期还满屏的条。
     */
    @Test
    fun 提示条_再窄也不会给出负宽度() {
        assertEquals(0, HudSize.snackWidthPx(px(20), px(side), px(cap)))
        assertEquals(0, HudSize.snackWidthPx(0, px(side), px(cap)))
    }

    @Test
    fun 提示条_扫一遍所有宽度都不越界() {
        var w = 0
        while (w <= px(1400)) {
            val got = HudSize.snackWidthPx(w, px(side), px(cap))
            assertTrue("屏宽 $w 时给出 $got，比屏幕还宽", got <= w)
            assertTrue("屏宽 $w 时给出 $got，超过上限 ${px(cap)}", got <= px(cap))
            assertTrue("屏宽 $w 时给出负数 $got", got >= 0)
            w += px(7)
        }
    }
}
