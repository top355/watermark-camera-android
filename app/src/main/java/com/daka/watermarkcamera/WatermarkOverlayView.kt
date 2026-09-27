package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * 相机预览上的水印浮层。
 * 尺寸由 MainActivity 按 [PreviewGeometry] 算出的「画面实际显示矩形」设定，
 * 绘制逻辑与出图共用 [WatermarkRenderer]，所以「看到的」就是「拍到的」。
 *
 * 它还负责**在水印上点一下改内容**：点地点开地点编辑器、点时间开日期时间滚轮。
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var config: WatermarkConfig = WatermarkConfig()
    var logo: Bitmap? = null
    var loc: LocInfo? = null

    /**
     * 点到水印上可编辑的块时回调。
     *
     * **只有命中才回调**；点空白处 [onTouchEvent] 直接返回 false，
     * 事件继续往下走落到预览区 —— 那里还担着点屏对焦/测光，浮层不能把它吃掉。
     */
    var onTap: ((WatermarkRenderer.TapTarget) -> Unit)? = null

    /**
     * 手指正按着的目标。只在按住时非 NONE。
     *
     * 它是"这里能点"的唯一可见提示：水印上没有任何按钮，不亮一下没人知道能点。
     * 出图那条路（[ImageWatermarker]）不经过这个 View，所以成图上没有这一层。
     */
    private var pressedTarget: WatermarkRenderer.TapTarget = WatermarkRenderer.TapTarget.NONE

    /** 画布本地坐标命中了哪一块。浮层尺寸就是画面显示矩形，所以本地坐标即画布坐标 */
    private fun targetAt(x: Float, y: Float): WatermarkRenderer.TapTarget =
        WatermarkRenderer.hitTest(
            width.toFloat(),
            height.toFloat(),
            config,
            logo,
            /* 必须走 config.now()：锁了时间时预览与出图都用同一个值 */
            config.now(),
            loc,
            x,
            y
        )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = targetAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hit == WatermarkRenderer.TapTarget.NONE) return false
                setPressedTarget(hit)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                /*
                 * 手指滑出去就把按下反馈收掉：亮着却松手不触发，比不亮更让人困惑。
                 * 但事件继续吃 —— 中途放弃的话，这一下会被预览区当成一次点击。
                 */
                setPressedTarget(if (hit == pressedTarget) pressedTarget else WatermarkRenderer.TapTarget.NONE)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val p = pressedTarget
                setPressedTarget(WatermarkRenderer.TapTarget.NONE)
                /*
                 * 松手这一下也得命中才算数：按下地点、滑到别处再松手，
                 * 用户的意思显然不是"我要改地点"。
                 */
                if (p != WatermarkRenderer.TapTarget.NONE && hit == p) onTap?.invoke(p)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                setPressedTarget(WatermarkRenderer.TapTarget.NONE)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun setPressedTarget(t: WatermarkRenderer.TapTarget) {
        if (pressedTarget == t) return
        pressedTarget = t
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        WatermarkRenderer.draw(
            canvas,
            width.toFloat(),
            height.toFloat(),
            config,
            logo,
            /* 必须走 config.now()：锁了时间时预览与出图都用同一个值 */
            config.now(),
            loc,
            /* 按下反馈只画在预览里；出图不经过这里 */
            highlight = pressedTarget
        )
        /* 时间要跟着走，250ms 重绘一次足够，别用 onDraw 自激 60fps 白耗电 */
        postInvalidateDelayed(250L)
    }
}
