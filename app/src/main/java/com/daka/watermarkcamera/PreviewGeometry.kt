package com.daka.watermarkcamera

import kotlin.math.max
import kotlin.math.min

/**
 * 预览画面的几何换算（纯函数，可单测）。
 *
 * 相机传感器吐出的分辨率永远是「横向」的（如 1440x1080），
 * 但屏幕上要显示成什么比例，取决于设备当前是竖屏还是横屏：
 *   竖屏 → 3:4（宽/高 = 0.75）
 *   横屏 → 4:3（宽/高 ≈ 1.333）
 *
 * 这一条搞错，横屏时水印浮层会算成一个居中的竖条，跟画面完全对不上 ——
 * 所以这里单独拆出来并留了回归护栏测试。
 */
object PreviewGeometry {

    private const val FALLBACK = 3f / 4f

    /** 传感器分辨率 → 画面在竖屏时的宽高比（短边/长边，恒 ≤ 1） */
    fun sensorAspect(w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return FALLBACK
        return min(w, h).toFloat() / max(w, h).toFloat()
    }

    /** 竖屏时的画面比例 → 当前屏幕方向下的显示比例 */
    fun displayAspect(sensor: Float, landscape: Boolean): Float {
        val base = if (sensor > 0f) sensor else FALLBACK
        return if (landscape) 1f / base else base
    }

    /**
     * 在 cw x ch 的容器里按 fitCenter 摆一个 aspect 比例的画面，
     * 返回画面实际占用的像素尺寸 [宽, 高]（外侧留黑边）。
     */
    fun fitRect(cw: Int, ch: Int, aspect: Float): IntArray {
        if (cw <= 0 || ch <= 0) return intArrayOf(0, 0)
        val a = if (aspect > 0f) aspect else FALLBACK
        val container = cw.toFloat() / ch.toFloat()
        return if (container > a) {
            intArrayOf((ch * a).toInt(), ch)
        } else {
            intArrayOf(cw, (cw / a).toInt())
        }
    }
}
