package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 底部那一行「1x 2x 3x …」倍率档位条。
 *
 * ## 为什么整行都在代码里建
 *
 * 档位是**设备能力的函数**（见 [Zoom.presets]）：只能放到 2x 的机器要给两格，
 * 带超广角的机器要多一格 0.6x，30x 的机器要给五格。在 XML 里摆占位再按情况隐藏，
 * 要么藏出空位、要么得为每种组合写一套 —— 不如把这一行整个交给这里管，
 * 布局里只留一个空 LinearLayout 当容器。
 *
 * ## 两个可见状态
 *
 * 选中态不只是好看：它是"现在到底几倍"的唯一显示。相机自己也会改倍数
 * （有些 ROM 在暗光下会自己往里裁），所以高亮一律以相机回调的值为准，
 * 见 [markSelected] 与 MainActivity 里的 zoomState 观察者。
 */
internal class ZoomBar(
    private val ctx: Context,
    private val row: LinearLayout,
    /** 包着 [row] 的滚动容器。没档位时连它一起藏 —— 只藏 row 会留下它的内边距 */
    private val wrapper: View
) {

    /** 点某一档。参数是倍数（可能是 0.6 这种小数）。 */
    var onPick: ((Float) -> Unit)? = null

    var min: Float = 1f
        private set
    var max: Float = 1f
        private set

    /** 当前这几档，升序。空 = 这个摄像头没法变焦 */
    var presets: List<Float> = emptyList()
        private set

    private var chips: List<TextView> = emptyList()

    /**
     * 是否已经绑过一次。
     *
     * **不能拿 `presets` 有没有值当"第一次"** —— 它初值就是空表，
     * 前摄（只支持 1x）第一次 bind 得到的也是空表，于是"没变就跳过"会在
     * 第一次就短路，整行**从来没被藏起来**，白留一条高度。
     */
    private var bound = false

    /**
     * 按相机报上来的能力重建档位。
     *
     * **相同就什么都不做**：zoomState 是每帧都可能回调的 LiveData，
     * 每次都 removeAllViews + 新建 5 个 TextView 会在捏合时疯狂分配、还打断点击。
     */
    fun bind(minZoom: Float, maxZoom: Float) {
        val p = Zoom.presets(minZoom, maxZoom)
        if (bound && p == presets) return
        bound = true

        min = if (minZoom > 0f && !minZoom.isNaN()) minZoom else 1f
        max = if (maxZoom >= min && !maxZoom.isNaN()) maxZoom else min
        presets = p

        row.removeAllViews()
        chips = p.mapIndexed { i, ratio -> chip(ratio, first = i == 0) }
        chips.forEach { row.addView(it) }

        val vis = if (p.isEmpty()) View.GONE else View.VISIBLE
        wrapper.visibility = vis
        row.visibility = vis
    }

    /**
     * 当前倍数落在哪一档就亮哪一档；都不沾（捏出来的 1.37x）**一个都不亮**。
     *
     * 留着上一次的高亮不改，是这类 UI 最常见的谎：用户明明捏到了 1.37x，
     * 界面上 1x 还亮着，他会以为捏合没生效。
     */
    fun markSelected(ratio: Float) {
        val on = Zoom.indexOf(presets, ratio)
        chips.forEachIndexed { i, tv ->
            val sel = i == on
            tv.isSelected = sel
            tv.setBackgroundResource(
                if (sel) R.drawable.bg_zoom_chip_on else R.drawable.bg_zoom_chip
            )
            tv.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (sel) R.color.zoom_chip_text_on else R.color.zoom_chip_text
                )
            )
        }
    }

    private fun chip(ratio: Float, first: Boolean): TextView {
        val tv = TextView(ctx)
        tv.text = Zoom.label(ratio)
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.isSingleLine = true
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.zoom_chip_text))
        tv.setBackgroundResource(R.drawable.bg_zoom_chip)
        tv.isClickable = true
        tv.isFocusable = true

        /*
         * 最小宽度而不是固定宽度：`0.6x` / `10x` 比 `1x` 宽，固定宽度会把
         * 长的那几个挤成两行或截断。42dp 是「1x」也撑得住的圆润胶囊宽度。
         */
        tv.minWidth = dp(42)
        tv.setPadding(dp(9), 0, dp(9), 0)

        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30)
        ).also {
            /*
             * 间距用第 2 格起的 marginStart，**不用每格的 marginEnd** ——
             * 后者会在行尾留一格间距，整行居中时看着偏左 3dp。
             */
            if (!first) it.marginStart = dp(6)
        }

        tv.contentDescription = ctx.getString(
            if (ratio == 1f) R.string.zoom_chip_desc_1x else R.string.zoom_chip_desc,
            Zoom.label(ratio)
        )
        tv.setOnClickListener { onPick?.invoke(ratio) }
        return tv
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
