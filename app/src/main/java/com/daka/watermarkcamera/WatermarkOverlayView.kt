package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/**
 * 相机预览上的水印浮层。
 * 尺寸由 MainActivity 按 [PreviewGeometry] 算出的「画面实际显示矩形」设定，
 * 绘制逻辑与出图共用 [WatermarkRenderer]，所以「看到的」就是「拍到的」。
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var config: WatermarkConfig = WatermarkConfig()
    var logo: Bitmap? = null
    var loc: LocInfo? = null

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
            loc
        )
        /* 时间要跟着走，250ms 重绘一次足够，别用 onDraw 自激 60fps 白耗电 */
        postInvalidateDelayed(250L)
    }
}
