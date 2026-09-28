package com.daka.watermarkcamera

import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * 保存链路的**分段计时**。
 *
 * ## 为什么要它
 *
 * 一张照片从按快门到落进相册要过六步：解码原图 → 建全尺寸画布 → 旋转/镜像绘制
 * → 打水印 → JPEG 重编码 → 写 MediaStore。用户说「3 秒左右」时，**卡住的是哪一步
 * 只能测出来** —— 读代码猜很容易猜错方向（例如去优化水印绘制，而瓶颈其实在编码）。
 *
 * 而且耗时**几乎完全由像素数决定**：同一台手机上，一张 4000×3000 和一张 8000×6000
 * 差 4 倍。所以日志里一定带上分辨率 —— 不带分辨率的「慢」是无意义的数字。
 *
 * ## 怎么读
 *
 * ```bash
 * adb logcat -s WMCSave
 * ```
 *
 * 形如：
 * ```
 * 拍照 4000x3000 (12.0MP) 合计=2413ms | 解码=520ms 画布=180ms 水印=140ms 建条目=40ms 编码=1180ms 提交=55ms 缩略图=110ms | jpg入=4820KB
 * ```
 *
 * 先看分辨率与「合计」，再看最大的一项。
 *
 * ## 边界
 *
 * - **release 包也打**：用户装的很可能是 release，那才是真实数据。
 * - **不改变任何行为**：只读时钟、只拼字符串。计时本身出问题也不会影响拍照。
 * - 时钟**可注入**：单测用假时钟精确断言；否则真时钟跑起来全是 0ms，测不出东西。
 */
internal class SaveProfiler(
    /** 区分两条路径：「拍照」与「相册加字」 */
    private val kind: String,
    /** 注入点。生产用单调时钟 —— 用户改系统时间不会把它搞乱 */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val stages = ArrayList<Pair<String, Long>>()
    private var last = clock()
    private val begin = last

    /** 记下「上一步到这里」的耗时。名称会原样进日志，用两字短词。 */
    fun stage(name: String) {
        val now = clock()
        stages.add(name to (now - last))
        last = now
    }

    /** 从开始到现在的合计毫秒数 */
    fun totalMs(): Long = clock() - begin

    /** 最慢的一步。一步都没记过时返回 null。 */
    fun slowest(): Pair<String, Long>? = stages.maxByOrNull { it.second }

    /** 完整分段，形如 `解码=520ms 编码=1180ms` */
    fun breakdown(): String = stages.joinToString(" ") { "${it.first}=${it.second}ms" }

    /**
     * 拼日志正文。**抽成纯函数**，这样格式化逻辑能在普通 JUnit 里直接断言，
     * 不必为了测一行字符串去起 Robolectric（一轮好几分钟）。
     */
    fun line(w: Int, h: Int, extra: String = ""): String {
        val mp = w.toLong() * h / 1_000_000.0
        return String.format(
            Locale.US, "%s %dx%d (%.1fMP) 合计=%dms | %s%s",
            kind, w, h, mp, totalMs(), breakdown(),
            if (extra.isEmpty()) "" else " | $extra"
        )
    }

    fun log(w: Int, h: Int, extra: String = "") {
        try {
            Log.i(TAG, line(w, h, extra))
        } catch (e: Exception) {
            // 记账不该把拍照带崩
        }
    }

    /**
     * 给用户看的一句话（只在 debug 包拼进提示条）。
     *
     * 只写**最慢的那一步** —— 完整分段去 logcat 看，提示条放不下。
     * 这样用户不必接 adb 就能回答「3 秒花在哪」。
     */
    fun summary(w: Int, h: Int): String {
        val mp = w.toLong() * h / 1_000_000.0
        val sec = String.format(Locale.US, "%.1fs", totalMs() / 1000.0)
        val s = slowest() ?: return String.format(Locale.US, "%s · %.1fMP", sec, mp)
        return String.format(Locale.US, "%s · %.1fMP · 最慢 %s %dms", sec, mp, s.first, s.second)
    }

    companion object {
        /** `adb logcat -s WMCSave` 用的标签 */
        const val TAG = "WMCSave"
    }
}
