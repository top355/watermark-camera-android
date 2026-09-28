package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SaveProfiler] 的逻辑测试。
 *
 * **用注入的假时钟断言，不用真时钟** —— 真时钟跑起来每段耗时都是 0ms，
 * 那样一个断言都立不起来（也正因如此，`SaveProfiler` 把 clock 做成了构造参数）。
 *
 * 普通 JUnit，不起 Robolectric 就能跑 —— 拼字符串的逻辑没必要付那个代价。
 */
class SaveProfilerTest {

    /** 只前进、不后退的假时钟 */
    private class FakeClock {
        var now = 0L
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun prof(kind: String = "拍照", c: FakeClock) = SaveProfiler(kind) { c.now }

    @Test
    fun 一步都没记时_合计为零且没有最慢项() {
        val c = FakeClock()
        val p = prof(c = c)
        assertEquals(0L, p.totalMs())
        assertNull(p.slowest())
        assertEquals("", p.breakdown())
    }

    @Test
    fun 每段耗时等于两次记录之间的时钟差() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(120)
        p.stage("解码")
        c.advance(45)
        p.stage("水印")
        c.advance(900)
        p.stage("编码")
        assertEquals("解码=120ms 水印=45ms 编码=900ms", p.breakdown())
    }

    /**
     * 这条是**语义**测试：合计必须是「从开始到现在」，不是「最后一段」。
     * 写错这一个词，日志里的合计就只反映最后一小步，整条日志失去意义。
     */
    @Test
    fun 合计是累计而不是最后一段() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(120)
        p.stage("解码")
        c.advance(900)
        p.stage("编码")
        assertEquals(1020L, p.totalMs())
    }

    @Test
    fun 最慢的一段_取最大而不是最后一段() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(10)
        p.stage("解码")
        c.advance(900)
        p.stage("编码")
        c.advance(50)
        p.stage("提交")
        assertEquals("编码" to 900L, p.slowest())
    }

    @Test
    fun 并列最慢时_取先记录的那一段() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(50)
        p.stage("甲")
        c.advance(50)
        p.stage("乙")
        assertEquals("甲" to 50L, p.slowest())
    }

    @Test
    fun 日志正文_含分辨率_像素数与每个分段() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(520)
        p.stage("解码")
        c.advance(1180)
        p.stage("编码")
        assertEquals(
            "拍照 4000x3000 (12.0MP) 合计=1700ms | 解码=520ms 编码=1180ms | jpg入=4820KB",
            p.line(4000, 3000, extra = "jpg入=4820KB")
        )
    }

    /** extra 为空时结尾不能留下一个孤零零的 " | " */
    @Test
    fun 日志正文_没有额外信息时不留分隔符() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(300)
        p.stage("解码")
        assertEquals("拍照 4000x3000 (12.0MP) 合计=300ms | 解码=300ms", p.line(4000, 3000))
    }

    /** 像素数保留一位小数 —— 16MP 那种会踩到四舍五入 */
    @Test
    fun 像素数保留一位小数() {
        val c = FakeClock()
        val p = prof(c = c)
        assertTrue(p.line(4608, 3456).contains("(15.9MP)"))
    }

    @Test
    fun 提示条摘要_写出合计与最慢的那一步() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(520)
        p.stage("解码")
        c.advance(1180)
        p.stage("编码")
        c.advance(713)
        p.stage("提交")
        assertEquals("2.4s · 12.0MP · 最慢 编码 1180ms", p.summary(4000, 3000))
    }

    @Test
    fun 提示条摘要_没有分段时只写合计与像素数() {
        val c = FakeClock()
        val p = prof(c = c)
        assertEquals("0.0s · 12.0MP", p.summary(4000, 3000))
    }

    /**
     * 记账**绝不允许**把拍照带崩。
     *
     * 普通 JUnit 里 `android.util.Log` 是没实现的桩，调用会抛
     * `RuntimeException: Method i in android.util.Log not mocked` ——
     * 正好拿来验证 [SaveProfiler.log] 把这个异常吞住了。
     */
    @Test
    fun 打日志失败也不能抛出去() {
        val c = FakeClock()
        val p = prof(c = c)
        c.advance(100)
        p.stage("解码")
        p.log(4000, 3000) // 不抛即通过
    }
}
