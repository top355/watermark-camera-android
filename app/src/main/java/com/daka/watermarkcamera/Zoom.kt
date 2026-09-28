package com.daka.watermarkcamera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 相机放大倍数的**纯逻辑**：这台设备该给用户哪几档、怎么显示、手势停下时归到哪一档。
 *
 * 不碰任何 View —— 于是能用普通 JUnit 跑（毫秒级），不必起 Robolectric。
 * 建 View 的那部分在 [ZoomBar]。
 *
 * ## 为什么档位必须按设备现算，不能写死 1x/2x/3x
 *
 * `CameraInfo.zoomState` 给的范围各机型差得很远：有的只有 `1..4`（纯数码变焦裁切），
 * 有的 `0.6..30`（超广角 + 潜望长焦）。写死三档的后果分别是：
 * · 在只能放到 2x 的机器上，点 3x 那格**毫无反应**（相机直接拒），用户以为坏了；
 * · 在 30x 的机器上，只给到 3x 是白扔了硬件；
 * · 带超广角的机器不给 0.6x，等于永远拍不到那张广角。
 *
 * 所以 [presets] 是**设备能力的函数**，越界的档位一个都不会生成。
 */
internal object Zoom {

    /**
     * 候选倍数（不含 1x 与下限）。
     *
     * 挑的是「人一眼能说出用途」的档位，不是等比的：2x 是人像、3x 是近景文字、
     * 5x 是隔条马路的门牌、10x 是够不着的铭牌。等比的话会冒出 1.6x / 2.6x 这种
     * 说不出干什么用的档，还占满一行。
     */
    private val CANDIDATES = listOf(2f, 3f, 5f, 10f)

    /** 最多给几档。再多在 320dp 窄屏上排不下，信息密度也不划算 */
    const val MAX_CHIPS = 5

    /**
     * 低于这个上限就当作「不能变焦」，整行藏掉。
     *
     * 不是 1.0：有些设备报 1.05 / 1.1（裁切式"变焦"），给它一行 1x / 1.1x
     * 既没用又占地方。1.25 以上才值得让用户看见。
     */
    private const val MIN_USEFUL_MAX = 1.25f

    /** 低于这个下限（超广角）才有意义单列一档；0.3 以下当异常值丢掉 */
    private const val MIN_USEFUL_MIN = 0.30f

    /**
     * 这台设备该显示哪几档。返回**严格升序**、非空时必定含 1f。
     *
     * 返回空列表 = 这个摄像头不值得给倍数条，调用方应当整行藏掉（[ZoomBar] 会做）。
     */
    fun presets(minZoom: Float, maxZoom: Float): List<Float> {
        /* NaN 也要挡住：NaN 的所有比较都是 false，不显式判会一路穿到 setZoomRatio */
        if (maxZoom.isNaN() || maxZoom < MIN_USEFUL_MAX) return emptyList()

        val out = mutableListOf<Float>()

        /*
         * 下限档：向上取整到 0.1 —— 只能往 1 那边靠。
         * 向下取整会得到一个**小于相机下限**的值，setZoomRatio 直接抛。
         * 并且要求结果真的 < 1：0.94 这种"下限"取上来就是 1，会和下面那档重复。
         */
        val lo = ceil1(minZoom)
        if (minZoom in MIN_USEFUL_MIN..0.94f && lo < 0.99f) out.add(lo)
        out.add(1f)

        /*
         * 比较用**精确的 <=**，不留浮点余量。
         *
         * 留余量（比如 `it <= maxZoom * 1.02f`）看着更"稳健"，其实是在造越界值：
         * 上限真报 1.99 的机器会因此拿到一个 2x 档，点下去 setZoomRatio(2.0)
         * 直接被相机拒 —— 而"点了没反应"正是这个功能最不该有的毛病。
         * 代价是万一某台机器把 2.0 报成 1.9999999，它会显示 1.9x 而不是 2x，
         * 只是不好看，不会坏。**宁可少一档，不可多一档。**
         */
        val picked = CANDIDATES.filter { it <= maxZoom }.toMutableList()

        /*
         * 上限本身也单列一档，但**向下**取整到 0.1（同上的理由，只是方向相反）。
         * 4x 的机器没有它，用户就只能停在 3x；而 3.97 这种上限，向下取整得 3.9，
         * 用 4.0 去 setZoomRatio 会被相机拒绝。
         */
        val top = floor1(maxZoom)
        if (top > 1f && (picked.isEmpty() || top > picked.last() + 0.05f)) picked.add(top)
        out.addAll(picked)

        /*
         * 超了就从尾巴砍。砍掉的是"上限"那档而不是中间的 —— 30x 的设备留下
         * 1/2/3/5/10 比留下 1/2/3/5/30 有用得多，打卡场景根本用不到 30x。
         */
        return out.take(MAX_CHIPS)
    }

    /**
     * 档位文字：`1x` / `2x` / `0.6x` / `10x`。
     *
     * 整数不写小数点（是 `2x` 不是 `2.0x`）—— 一行里两种写法混排看着不齐。
     */
    fun label(r: Float): String {
        if (r <= 0f || r.isNaN()) return "1x"
        val n = r.roundToInt()
        val s = if (abs(r - n) < 0.05f) n.toString() else String.format(Locale.US, "%.1f", r)
        return s + "x"
    }

    /**
     * [ratio] 落在哪一档上（相对误差 ±[tolerance]）。都不沾就返回 **-1**。
     *
     * 相对误差而不是绝对误差：2.0 与 2.16 差 0.16 算近，1.0 与 1.16 也差 0.16
     * 却已经是"看得出的变焦"了。用同一把绝对尺子，宽的那头会粘得太松。
     *
     * -1 是有意义的返回值，别当成"没找到就回 0"：捏合出来的 1.37x 不属于任何一档，
     * 这时**一个档位都不该高亮** —— 假高亮会让用户以为自己选的是 1x。
     */
    fun indexOf(presets: List<Float>, ratio: Float, tolerance: Float = 0.08f): Int {
        if (ratio.isNaN()) return -1
        var idx = -1
        var best = Float.MAX_VALUE
        presets.forEachIndexed { i, p ->
            if (p <= 0f) return@forEachIndexed
            val d = abs(p - ratio) / p
            if (d < best) {
                best = d
                idx = i
            }
        }
        return if (best <= tolerance) idx else -1
    }

    /**
     * 捏合松手时吸附：落在某档 ±[tolerance] 内就归到那一档，否则**原样保留**。
     *
     * 不做"一律吸附到最近档"：从 1x 捏到 1.37x，松手被拽回 1x，
     * 用户只会觉得"我这下捏了等于没捏"。只吃掉小幅偏差，保留真实意图。
     */
    fun snap(presets: List<Float>, ratio: Float, tolerance: Float = 0.08f): Float {
        val i = indexOf(presets, ratio, tolerance)
        return if (i >= 0) presets[i] else ratio
    }

    /** 向上取整到 0.1（保证结果 ≥ 输入） */
    private fun ceil1(v: Float): Float = ceil(v * 10f) / 10f

    /** 向下取整到 0.1（保证结果 ≤ 输入） */
    private fun floor1(v: Float): Float = floor(v * 10f) / 10f
}
