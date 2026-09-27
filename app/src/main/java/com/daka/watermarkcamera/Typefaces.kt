package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * 水印里用到的非系统字体。
 *
 * 目前只有一款：**PT Mono Bold**（`res/font/pt_mono_bold.ttf`），
 * 专供右下角那行防伪码的 ASCII 部分。
 *
 * ## 为什么要为这一行专门嵌字体
 *
 * 防伪码是 14 位数字与大小写字母的混排。用系统默认字体渲染有两个问题：
 * 1. **宽度参差** —— 默认是比例字体，`I` 和 `M` 宽窄差一倍，一串码看起来是歪的；
 * 2. **换机就变** —— 各家 ROM 的默认字体不同（MiSans / OPPO Sans / HarmonyOS Sans），
 *    同一串码在两台手机上长得不一样。等宽 + 固定字体才能保证"码对齐、跨机一致"。
 *
 * ## 授权（已核实，见 assets/licenses/PTMono.txt）
 *
 * `OS/2.fsType = 0x0000`（Installable Embedding），字体自带的授权声明明确允许
 * "embedding in documents and Web pages, bundling with commercial and non
 * commercial products"。版权声明与许可文本随包放在 `assets/licenses/PTMono.txt`。
 *
 * ## 中文不能用它
 *
 * 它没有中文字形（cmap 里 CJK 区段数为 0）。所以品牌区那一行是**分段**绘制的：
 * 「防伪」两个中文用系统字体、后面那 14 位用这个等宽字体。
 * 见 [WatermarkRenderer.BrandSeg]。
 */
object Typefaces {

    @Volatile
    private var monoFont: Typeface? = null

    /** 真正从资源里加载出来的那个（null = 没加载到）。测试用它断言字体确实生效了 */
    val mono: Typeface?
        get() = monoFont

    /**
     * 绘制时用它。加载失败就退回系统等宽 —— 宁可字丑一点，
     * 也不能让防伪码变成一堆豆腐块或者整块水印画不出来。
     */
    val monoForDraw: Typeface
        get() = monoFont ?: Typeface.MONOSPACE

    fun install(ctx: Context) {
        if (monoFont != null) return
        monoFont = try {
            ResourcesCompat.getFont(ctx, R.font.pt_mono_bold)
        } catch (t: Throwable) {
            /* 资源被裁掉、字体损坏都会走到这里。不崩，退回系统等宽 */
            null
        }
    }
}
