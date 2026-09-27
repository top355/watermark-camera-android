package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯逻辑，不需要 Robolectric。
 *
 * 横屏适配最容易错的不是「代码写不出来」，而是把竖屏的比例直接套到横屏容器里 ——
 * 那样水印浮层会算成一个居中的竖条，跟画面完全对不上。
 * 这里把关键换算钉死，并留一条「按旧逻辑会算错」的回归护栏。
 */
class PreviewGeometryTest {

    private fun assertF(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test
    fun 传感器分辨率_转成竖屏时的画面比例() {
        /* 传感器吐出来永远是横向的：1440x1080 → 竖屏显示成 3:4 */
        assertF(0.75f, PreviewGeometry.sensorAspect(1440, 1080))
        /* 万一顺序给反了，结果必须一致 */
        assertF(0.75f, PreviewGeometry.sensorAspect(1080, 1440))
        /* 16:9 传感器 */
        assertF(0.5625f, PreviewGeometry.sensorAspect(1920, 1080))
    }

    @Test
    fun 竖屏_比例不变() {
        assertF(0.75f, PreviewGeometry.displayAspect(0.75f, landscape = false))
    }

    @Test
    fun 横屏_比例取倒数() {
        assertF(4f / 3f, PreviewGeometry.displayAspect(0.75f, landscape = true))
    }

    @Test
    fun 竖屏容器_内接3比4画面() {
        /* 屏幕 1080x1920：画面 3:4 撑满宽度 */
        val r = PreviewGeometry.fitRect(1080, 1920, 0.75f)
        assertEquals(1080, r[0])
        assertEquals(1440, r[1])
    }

    @Test
    fun 横屏容器_内接4比3画面() {
        /* 屏幕 2400x1080：画面 4:3 撑满高度，左右各留 480px 黑边 */
        val aspect = PreviewGeometry.displayAspect(0.75f, landscape = true)
        val r = PreviewGeometry.fitRect(2400, 1080, aspect)
        assertEquals(1440, r[0])
        assertEquals(1080, r[1])
        assertEquals(480, (2400 - r[0]) / 2)
    }

    /**
     * 回归护栏：这就是「横屏照抄竖屏比例」的后果。
     * 画面本该占 1440x1080，却会算成 810x1080 的竖条 —— 水印浮层跑到画面中间去了。
     */
    @Test
    fun 横屏若误用竖屏比例_会得到错误矩形() {
        val wrong = PreviewGeometry.fitRect(2400, 1080, 0.75f)
        assertEquals(810, wrong[0])
        assertEquals(1080, wrong[1])

        val right = PreviewGeometry.fitRect(
            2400, 1080,
            PreviewGeometry.displayAspect(0.75f, landscape = true)
        )
        assertEquals(true, right[0] > wrong[0])
    }

    @Test
    fun 退化输入不崩() {
        assertF(0.75f, PreviewGeometry.sensorAspect(0, 0))
        assertF(0.75f, PreviewGeometry.sensorAspect(-1, -1))
        assertF(0.75f, PreviewGeometry.displayAspect(0f, landscape = false))
        assertF(4f / 3f, PreviewGeometry.displayAspect(0f, landscape = true))

        /* 容器尺寸非法 → 返回 0，调用方会直接跳过（不要拿 0 去设 layoutParams） */
        assertEquals(0, PreviewGeometry.fitRect(0, 0, 0.75f)[0])
        assertEquals(0, PreviewGeometry.fitRect(-5, -5, 0.75f)[1])

        /* aspect 非法 → 回退到 3:4，而不是崩或返回 0 */
        val fb = PreviewGeometry.fitRect(1080, 1920, 0f)
        assertEquals(1080, fb[0])
        assertEquals(1440, fb[1])
    }
}
