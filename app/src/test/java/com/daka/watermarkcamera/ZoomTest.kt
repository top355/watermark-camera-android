package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倍率档位的选择逻辑。
 *
 * 这里**不跑 Robolectric** —— [Zoom] 是纯函数，普通 JUnit 毫秒级跑完。
 * 建按钮、点按钮那部分在 ZoomBarTest。
 *
 * 重点不是"某台机器给哪几档"（那是随时会调的），而是那条**不变量**：
 * 任何一档都必须在相机报的区间内。越界的档位点下去毫无反应，
 * 属于"用户天天抱怨、看代码又看不出"的那类毛病。
 */
class ZoomTest {

    /* ------------------------- 该给哪几档 ------------------------- */

    @Test
    fun 不能变焦的摄像头_一档都不给() {
        assertTrue("1x..1x 就该整行藏掉", Zoom.presets(1f, 1f).isEmpty())
        assertTrue(
            "1.2x 这种裁切式变焦不值得占一行",
            Zoom.presets(1f, 1.2f).isEmpty()
        )
        assertTrue("NaN 也不能一路穿到 setZoomRatio", Zoom.presets(1f, Float.NaN).isEmpty())
    }

    @Test
    fun 上限四倍_给到四倍而不是停在三倍() {
        assertEquals(listOf(1f, 2f, 3f, 4f), Zoom.presets(1f, 4f))
    }

    @Test
    fun 上限八倍_跳过中间那些说不出用途的档() {
        /* 4x / 6x / 7x 不生成；但上限本身要留，否则用户只能停在 5x */
        assertEquals(listOf(1f, 2f, 3f, 5f, 8f), Zoom.presets(1f, 8f))
    }

    @Test
    fun 上限刚好十倍_不重复追加一次上限() {
        assertEquals(listOf(1f, 2f, 3f, 5f, 10f), Zoom.presets(1f, 10f))
    }

    @Test
    fun 三十倍_砍掉的是上限那一档而不是中间档() {
        /* 打卡场景用不到 30x；留 1/2/3/5/10 比留 1/2/3/5/30 有用得多 */
        val p = Zoom.presets(1f, 30f)
        assertEquals(listOf(1f, 2f, 3f, 5f, 10f), p)
        assertEquals(Zoom.MAX_CHIPS, p.size)
    }

    @Test
    fun 带超广角_首档就是它的下限() {
        val p = Zoom.presets(0.6f, 8f)
        assertEquals(0.6f, p.first(), 1e-4f)
        assertEquals(Zoom.MAX_CHIPS, p.size)
    }

    @Test
    fun 下限接近1_不生成和1x重复的那一格() {
        /*
         * 0.94 向上取整是 1.0，会和「1x」那格一模一样 ——
         * 一行里两个 1x，点哪个都一样，看着像 bug。
         */
        val p = Zoom.presets(0.94f, 4f)
        assertEquals(listOf(1f, 2f, 3f, 4f), p)
    }

    @Test
    fun 上限带小数_档位向下取整不越过上限() {
        /* 3.97 不能给一档 4x：setZoomRatio(4.0) 会被相机拒 */
        val p = Zoom.presets(1f, 3.97f)
        assertEquals(3.9f, p.last(), 1e-4f)
        assertTrue(p.last() <= 3.97f)
    }

    @Test
    fun 上限一点九九_不给二倍() {
        assertTrue(
            "2.0 > 1.99，给了就是一格点了没反应的按钮",
            Zoom.presets(1f, 1.99f).none { it == 2f }
        )
    }

    /* ------------------------- 不变量 ------------------------- */

    /**
     * 拿一串真实机型会出现的 min/max 组合扫一遍。
     *
     * 这条比上面每一个具体用例都重要：具体档位随时会调，
     * 但"不许越界 / 严格升序 / 含 1x / 不超过五档"四条任何情况下都得成立。
     */
    @Test
    fun 扫一遍常见区间_档位永远在区间内且严格升序() {
        val mins = listOf(0.5f, 0.6f, 0.7f, 0.94f, 1f, 1f, 1f)
        val maxs = listOf(
            1f, 1.2f, 1.25f, 1.9f, 2f, 2.5f, 3f, 3.97f, 4f, 4.5f,
            5f, 6f, 8f, 9.9f, 10f, 12f, 20f, 30f, 50f, 100f
        )
        for (mn in mins) {
            for (mx in maxs) {
                val p = Zoom.presets(mn, mx)
                val tag = "$mn..$mx"
                if (p.isEmpty()) {
                    assertTrue("$tag 不该给档位", mx < 1.25f)
                    continue
                }
                assertTrue("$tag 必须含 1x: $p", p.any { it == 1f })
                assertTrue("$tag 有档位超过上限: $p", p.all { it <= mx })
                assertTrue("$tag 有档位低于下限: $p", p.all { it >= mn })
                assertTrue("$tag 不是严格升序: $p", (1 until p.size).all { p[it] > p[it - 1] })
                assertTrue("$tag 超过五档: $p", p.size <= Zoom.MAX_CHIPS)
            }
        }
    }

    /* ------------------------- 文字 ------------------------- */

    @Test
    fun 档位文字_整数不写小数点() {
        assertEquals("1x", Zoom.label(1f))
        assertEquals("2x", Zoom.label(2f))
        assertEquals("10x", Zoom.label(10f))
        /* 一行里 "2x" 和 "2.0x" 混排看着是不齐的 */
        assertEquals("0.6x", Zoom.label(0.6f))
        assertEquals("1.9x", Zoom.label(1.9f))
        /* 异常值兜底：不许出现 "NaNx" 或 "0.0x" 这种 */
        assertEquals("1x", Zoom.label(Float.NaN))
        assertEquals("1x", Zoom.label(0f))
    }

    /* ------------------------- 高亮与吸附 ------------------------- */

    @Test
    fun 高亮_只认真正落在档位上的值() {
        val p = listOf(1f, 2f, 3f, 5f, 8f)
        assertEquals(1, Zoom.indexOf(p, 2f))
        assertEquals("相机回 2.05 也算 2x", 1, Zoom.indexOf(p, 2.05f))
        assertEquals("捏出来的 4x 不属于任何一档", -1, Zoom.indexOf(p, 4f))
        assertEquals(-1, Zoom.indexOf(emptyList(), 1f))
    }

    @Test
    fun 相对误差而不是绝对误差() {
        val p = listOf(1f, 10f)
        /*
         * 偏移同样是 0.5：对 1x 来说是"放大了一半"，对 10x 只是 5%。
         * 用同一把绝对尺子，宽的那头会粘得莫名其妙地松、窄的那头却永远高亮不上。
         *
         * 别拿 10.8 当例子 —— 那正好是 8% 的容差边界，0.8f/10f 在浮点里是
         * 0.080000001，比 0.08f 大一点点，测试会时绿时红。
         */
        assertEquals("离 1x 差 0.5，已经算看得出的变焦了", -1, Zoom.indexOf(p, 1.5f))
        assertEquals("离 10x 差 0.5 只是 5%，该算 10x", 1, Zoom.indexOf(p, 10.5f))
    }

    @Test
    fun 松手吸附_小幅偏差归位_大幅偏差原样保留() {
        val p = listOf(1f, 2f, 3f, 5f, 8f)
        assertEquals("捏歪一点点，回 1x", 1f, Zoom.snap(p, 1.05f), 1e-4f)
        assertEquals("快要到 2x 了，归到 2x", 2f, Zoom.snap(p, 1.9f), 1e-4f)
        /*
         * 从 1x 认真捏到 4x 却被打回 3x 或 5x，用户只会觉得"我这下白捏了"。
         * 差得多的必须原样留着。
         */
        assertEquals(4f, Zoom.snap(p, 4f), 1e-4f)
    }
}
