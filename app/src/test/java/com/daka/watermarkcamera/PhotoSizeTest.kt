package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * 出土尺寸的纯逻辑测试。
 *
 * 这一层是**唯一**需要穷举的地方：`planDecode` 里那个"2 的幂先粗缩、再精确缩放"
 * 的循环，边界全在数字上（4608 缩到 2304 还是 1152、1M 和 2M 会不会撞成同一档），
 * 而这些错误到了真机上只表现为"照片比选的那档模糊一点"—— 肉眼根本发现不了，
 * 却会让用户觉得这个设置没用。所以这里用扫描式断言把不变量钉死。
 *
 * 全屏档的比例来自屏幕，所以这里喂**假屏幕比例**（[FAKE_SPAN]）来覆盖它 ——
 * 这也是把 [PhotoRatio] 设计成"自己不存比例、由调用方传进来"的全部意义：
 * 这个枚举完全不碰 Android API，于是能在普通 JUnit 里跑。
 *
 * 普通 JUnit，不起 Robolectric（位图那条路在 [PhotoSizeBitmapTest] 里单独测）。
 */
class PhotoSizeTest {

    /**
     * 假屏幕比例：18:9。选它是因为它既不等于 4:3 也不等于 16:9 ——
     * 用 16:9 当假值会让"全屏档其实读错了档位、退回 16:9"这种 bug 照样全绿。
     */
    private val FAKE_SPAN = 2.0

    /* ------------------------- 档位 : 尺寸表 ------------------------- */

    @Test
    fun 三档在4比3下的尺寸() {
        val r = PhotoRatio.R4_3
        val span = r.span(FAKE_SPAN)
        assertEquals(1152, SaveSize.M1.longSide(r, span))
        assertEquals(1600, SaveSize.M2.longSide(r, span))
        assertEquals(2304, SaveSize.M4.longSide(r, span))
        assertEquals(864, SaveSize.M1.shortSide(r))
        assertEquals(1200, SaveSize.M2.shortSide(r))
        assertEquals(1728, SaveSize.M4.shortSide(r))
    }

    @Test
    fun 三档在16比9下的尺寸() {
        val r = PhotoRatio.R16_9
        val span = r.span(FAKE_SPAN)
        assertEquals(1280, SaveSize.M1.longSide(r, span))
        assertEquals(1920, SaveSize.M2.longSide(r, span))
        assertEquals(2560, SaveSize.M4.longSide(r, span))
        /* 16:9 的短边就是全屏档借用的那个 baseShort */
        assertEquals(720, SaveSize.M1.shortSide(r))
        assertEquals(1080, SaveSize.M2.shortSide(r))
        assertEquals(1440, SaveSize.M4.shortSide(r))
    }

    /**
     * 全屏档：短边跟 16:9 一样，长边按屏幕比例延伸。
     *
     * 屏幕取 1080×2400（常见的 20:9），span = 2400/1080 = 2.2222…
     */
    @Test
    fun 全屏档按屏幕比例延伸长边() {
        val r = PhotoRatio.R_FULL
        val span = screenSpan(1080, 2400)
        assertEquals(2400.0 / 1080.0, span, 1e-9)

        assertEquals(720, SaveSize.M1.shortSide(r))
        assertEquals(1080, SaveSize.M2.shortSide(r))
        assertEquals(1440, SaveSize.M4.shortSide(r))

        /* 顺手把具体数值也钉住：2M 在 1080×2400 的屏上应该正好是满屏像素 */
        assertEquals(1600, SaveSize.M1.longSide(r, span))
        assertEquals(2400, SaveSize.M2.longSide(r, span))
        assertEquals(3200, SaveSize.M4.longSide(r, span))
    }

    /**
     * 屏幕正好是 16:9 时，全屏档必须和 16:9 档给出**完全一样**的尺寸。
     *
     * 这条是两条独立表的交叉验证：全屏档的短边借用 16:9 那一列，
     * 万一有人改了其中一张表而忘了另一张，这里立刻红 ——
     * 而在真机上这种不一致只表现为"选 16:9 和选全屏出来的图差不多"，没人看得出来。
     */
    @Test
    fun 十六比九的屏幕下_全屏与十六比九应当完全一致() {
        val r16 = PhotoRatio.R16_9
        val rFull = PhotoRatio.R_FULL
        /* 1920×1080 的屏幕，span 正好 16/9 */
        val span = screenSpan(1920, 1080)
        assertEquals(16.0 / 9.0, span, 1e-9)

        for (size in SaveSize.entries) {
            assertEquals(size.longSide(r16, span), size.longSide(rFull, span))
            assertEquals(size.shortSide(r16), size.shortSide(rFull))
            assertArrayEqualsInt(
                size.output(r16, span, landscape = true),
                size.output(rFull, span, landscape = true)
            )
        }
    }

    /** 横图给长宽、竖图给宽长 —— 同一档位下两个朝向必须是对调的 */
    @Test
    fun 横图给长宽_竖图给宽长() {
        val span = PhotoRatio.R4_3.span(FAKE_SPAN)
        assertArrayEqualsInt(
            intArrayOf(1600, 1200),
            SaveSize.M2.output(PhotoRatio.R4_3, span, true)
        )
        assertArrayEqualsInt(
            intArrayOf(1200, 1600),
            SaveSize.M2.output(PhotoRatio.R4_3, span, false)
        )
        assertArrayEqualsInt(
            intArrayOf(1920, 1080),
            SaveSize.M2.output(PhotoRatio.R16_9, span, true)
        )
        assertArrayEqualsInt(
            intArrayOf(1080, 1920),
            SaveSize.M2.output(PhotoRatio.R16_9, span, false)
        )
        /* 全屏档同理：竖屏时是 短×长 */
        val fs = PhotoRatio.R_FULL.span(FAKE_SPAN)
        assertArrayEqualsInt(
            intArrayOf(1440, 720),
            SaveSize.M1.output(PhotoRatio.R_FULL, fs, true)
        )
        assertArrayEqualsInt(
            intArrayOf(720, 1440),
            SaveSize.M1.output(PhotoRatio.R_FULL, fs, false)
        )
    }

    /** 九种组合的长宽比都对得上（3 档位 × 3 比例，两种朝向都算） */
    @Test
    fun 九种组合的长宽比都对得上() {
        for (ratio in PhotoRatio.entries) {
            val span = ratio.span(FAKE_SPAN)
            for (size in SaveSize.entries) {
                for (landscape in listOf(true, false)) {
                    val o = size.output(ratio, span, landscape)
                    assertTrue("$ratio $size 尺寸不能非正", o[0] > 0 && o[1] > 0)
                    /* 朝向要对：横图的宽必须大于高 */
                    if (landscape) {
                        assertTrue("$ratio $size 横图应当宽>高", o[0] >= o[1])
                    } else {
                        assertTrue("$ratio $size 竖图应当高>=宽", o[1] >= o[0])
                    }
                    /* 实际宽高比贴近 span（标准尺寸带来的四舍五入允许一点偏差） */
                    val got = max(o[0], o[1]).toDouble() / minOf(o[0], o[1])
                    assertTrue(
                        "$ratio $size 比例偏了：$got vs $span",
                        abs(got - span) < 0.02
                    )
                }
            }
        }
    }

    /* ------------------------- 屏幕比例 ------------------------- */

    @Test
    fun 屏幕比例恒大于等于1_且与朝向无关() {
        assertEquals(2400.0 / 1080.0, screenSpan(1080, 2400), 1e-9)
        assertEquals(2400.0 / 1080.0, screenSpan(2400, 1080), 1e-9)
        assertEquals(1.0, screenSpan(1200, 1200), 1e-9)
        /* 读不到尺寸时返回 0，交给 PhotoRatio.span 兜底 */
        assertEquals(0.0, screenSpan(0, 2400), 1e-9)
        assertEquals(0.0, screenSpan(1080, -5), 1e-9)
    }

    /**
     * 屏幕比例是外部输入，必须夹住。
     *
     * 分屏、折叠屏内屏、开发者选项里的奇葩分辨率都会给出偏离常识的值；
     * 不夹的话"全屏"照片真会变成方的，或者变成一条 4:1 的细缝。
     */
    @Test
    fun 全屏档的屏幕比例被夹住_异常输入退回16比9() {
        val r = PhotoRatio.R_FULL
        /* 正常值原样通过 */
        assertEquals(2.0, r.span(2.0), 1e-9)
        /* 上限夹到 3:1 */
        assertEquals(PhotoRatio.MAX_FULL_SPAN, r.span(9.0), 1e-9)
        /* 下限夹到 1:1（方形屏，比如折叠屏内屏接近正方形） */
        assertEquals(1.0, r.span(0.4), 1e-9)
        assertEquals(1.0, r.span(1.0), 1e-9)
        /* 读不到（0 / 负 / NaN / 无穷）一律退回 16:9 */
        assertEquals(PhotoRatio.DEFAULT_FULL_SPAN, r.span(0.0), 1e-9)
        assertEquals(PhotoRatio.DEFAULT_FULL_SPAN, r.span(-2.0), 1e-9)
        assertEquals(PhotoRatio.DEFAULT_FULL_SPAN, r.span(Double.NaN), 1e-9)
        assertEquals(PhotoRatio.DEFAULT_FULL_SPAN, r.span(Double.POSITIVE_INFINITY), 1e-9)
    }

    /** 固定档的比例不能被屏幕影响 —— 传什么都不该变 */
    @Test
    fun 固定档的比例不受屏幕影响() {
        for (noise in listOf(0.0, 1.0, 2.0, 9.0, Double.NaN)) {
            assertEquals(4.0 / 3.0, PhotoRatio.R4_3.span(noise), 1e-9)
            assertEquals(16.0 / 9.0, PhotoRatio.R16_9.span(noise), 1e-9)
        }
    }

    /**
     * 长边绝不能小于短边。
     *
     * 垃圾输入（0 / 负数 / NaN）进到长边计算里时，`roundToInt` 可能给出 0 或负值，
     * 那会让后面的裁剪框算成负数、`createBitmap` 直接抛异常。
     */
    @Test
    fun 全屏档的长边不小于短边_垃圾输入也不() {
        val r = PhotoRatio.R_FULL
        for (garbage in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY)) {
            for (size in SaveSize.entries) {
                val l = size.longSide(r, garbage)
                val s = size.shortSide(r)
                assertTrue("$size 长边 $l 不能小于短边 $s（输入 $garbage）", l >= s)
                assertTrue("$size 尺寸必须为正", l > 0 && s > 0)
            }
        }
    }

    /* ------------------------- 居中裁剪框 ------------------------- */

    @Test
    fun 比目标更宽时_裁两边留高度() {
        /* 400×300（4:3）裁成 1:1 → 高度留满，宽度裁到 300 */
        assertEquals(300 to 300, cropOf(400, 300, 1.0))
    }

    @Test
    fun 比目标更高时_裁上下留宽度() {
        /* 300×400 裁成 1:1 → 宽度留满，高度裁到 300 */
        assertEquals(300 to 300, cropOf(300, 400, 1.0))
        /* 400×300 裁成 16:9 → 宽度留满，高度裁到 225 */
        assertEquals(400 to 225, cropOf(400, 300, 16.0 / 9.0))
    }

    @Test
    fun 比例正好时_一个像素都不裁() {
        assertEquals(400 to 300, cropOf(400, 300, 4.0 / 3.0))
        assertEquals(
            cropOf(1920, 1080, 16.0 / 9.0),
            1920 to 1080
        )
    }

    @Test
    fun 裁剪框绝不超出原图() {
        for (w in 1..40) {
            for (h in 1..40) {
                for (want in listOf(0.5, 1.0, 4.0 / 3.0, 16.0 / 9.0)) {
                    val (cw, ch) = cropOf(w, h, want)
                    assertTrue("$w x $h want=$want 裁出 ${cw}x${ch} 超界", cw <= w && ch <= h)
                    assertTrue("$w x $h want=$want 裁出负尺寸或零", cw >= 1 && ch >= 1)
                }
            }
        }
    }

    @Test
    fun 非法尺寸或比例_不崩() {
        assertEquals(1 to 1, cropOf(0, 100, 1.0))
        assertEquals(1 to 1, cropOf(100, -5, 1.0))
        assertEquals(1 to 1, cropOf(100, 100, 0.0))
    }

    /* ------------------------- 解码计划 ------------------------- */

    /*
     * 下面几个用例名以数字开头（2M / 1M / 4M / 1比1），**必须用反引号包起来**：
     * Kotlin 的普通标识符允许 Unicode 字母（所以「三档在4比3下的尺寸」不用包），
     * 但**不允许以数字开头**，否则报 "Function declaration must have a name"。
     */
    @Test
    fun `2M_4比3_从16MP缩到精准尺寸`() {
        val r = PhotoRatio.R4_3
        val p = planDecode(4608, 3456, quarterTurn = false, size = SaveSize.M2, ratio = r, span = r.span(FAKE_SPAN))
        assertNotNull(p)
        p!!
        assertEquals("应采 1/2", 2, p.sample)
        assertEquals(1600, p.outW)
        assertEquals(1200, p.outH)
        assertEquals("裁剪区就是缩完的整幅（4:3 无需裁）", 2304 to 1728, p.cropW to p.cropH)
    }

    @Test
    fun `1M_与2M_不能撞成同一个尺寸`() {
        val r = PhotoRatio.R4_3
        val span = r.span(FAKE_SPAN)
        val a = planDecode(4608, 3456, false, SaveSize.M1, r, span)!!
        val b = planDecode(4608, 3456, false, SaveSize.M2, r, span)!!
        assertEquals(1152 to 864, a.outW to a.outH)
        assertEquals(1600 to 1200, b.outW to b.outH)
        assertTrue("1M 和 2M 必须真的不一样", a.outW != b.outW)
    }

    @Test
    fun `4M_4比3_采用1比2正好命中`() {
        val r = PhotoRatio.R4_3
        val p = planDecode(4608, 3456, false, SaveSize.M4, r, r.span(FAKE_SPAN))!!
        assertEquals(2, p.sample)
        assertEquals(2304, p.outW)
        assertEquals(2304 to 1728, p.cropW to p.cropH)
    }

    /**
     * 全屏档从 4:3 的源里裁：**宽留满、砍上下**。
     *
     * 屏幕取 18:9（span = 2.0），源 4608×3456（4:3 = 1.333）。
     * 2.0 比 1.333 更"宽"，所以要保持宽度、把高度砍到 2304/2 = 1152。
     * 这与 `PhotoSizeBitmapTest.裁16比9_砍掉的是上下而不是左右` 是同一个几何事实。
     */
    @Test
    fun 全屏档_从4比3的源里裁掉上下() {
        val r = PhotoRatio.R_FULL
        val span = r.span(FAKE_SPAN)
        assertEquals(2160, SaveSize.M2.longSide(r, span))
        assertEquals(1080, SaveSize.M2.shortSide(r))

        val p = planDecode(4608, 3456, false, SaveSize.M2, r, span)!!
        assertEquals(2160 to 1080, p.outW to p.outH)
        /* 裁完仍是 2:1 —— 说明裁的是比例，不是随便砍一刀 */
        assertEquals(2.0, p.cropW.toDouble() / p.cropH, 0.01)
        /*
         * cropW/cropH 是**降采样之后**那个坐标系里的（sample=2 → 全部减半），
         * 不是原图坐标。所以是在 2304×1728 上裁。
         */
        assertEquals(2, p.sample)
        assertEquals("宽度留满", 2304, p.cropW)
        assertEquals("高度被砍到一半", 1152, p.cropH)
        assertTrue("裁剪区不能小于目标，否则就是在放大", p.cropW >= p.outW && p.cropH >= p.outH)
    }

    @Test
    fun 转90度时_按摆正之后的宽高算() {
        /* 传感器给的横向帧 3456x4608，要转 90 度显示 —— 摆正后是 4608x3456（横图），
         * 所以输出也该是横的。忘了折这一下就会把竖图裁成横的。 */
        val r = PhotoRatio.R4_3
        val span = r.span(FAKE_SPAN)
        val p = planDecode(3456, 4608, quarterTurn = true, size = SaveSize.M2, ratio = r, span = span)!!
        assertEquals(1600, p.outW)
        assertEquals(1200, p.outH)

        val q = planDecode(3456, 4608, quarterTurn = false, size = SaveSize.M2, ratio = r, span = span)!!
        assertEquals("不转的时候是竖图", 1200, q.outW)
        assertEquals(1600, q.outH)
    }

    /**
     * 扫描式不变量：10 种源尺寸 × 3 比例 × 3 档位 × 2 个旋转。
     *
     * 四条一起验 —— 单看任何一条都能被"随便返回一个尺寸"骗过去：
     * ① 采样是 2 的幂且 ≤ 64；② 裁剪区不超出解码出来的图；
     * ③ 裁剪区的比例贴近目标比例；④ 要放大时就不能再降采样。
     */
    @Test
    fun 扫描_四种源尺寸下的全部组合都守规矩() {
        val sources = listOf(
            4608 to 3456, 4000 to 3000, 8000 to 6000, 1600 to 1200,
            3456 to 4608, 3000 to 4000, 2400 to 1080, 1080 to 2400,
            4032 to 3024, 1920 to 1080
        )
        var checked = 0
        for ((sw, sh) in sources) {
            for (ratio in PhotoRatio.entries) {
                val span = ratio.span(FAKE_SPAN)
                for (size in SaveSize.entries) {
                    for (turn in listOf(false, true)) {
                        val p = planDecode(sw, sh, turn, size, ratio, span)
                            ?: throw AssertionError("$sw x $sh 应该能算出计划")
                        val tag = "$sw x $sh turn=$turn $ratio $size"

                        assertTrue("$tag 采样必须是 2 的幂：${p.sample}", isPow2(p.sample))
                        assertTrue("$tag 采样过大：${p.sample}", p.sample <= 64)

                        val dw = max(1, (if (turn) sh else sw) / p.sample)
                        val dh = max(1, (if (turn) sw else sh) / p.sample)

                        assertTrue("$tag 裁剪超界", p.cropW <= dw && p.cropH <= dh)

                        val gotRatio = p.cropW.toDouble() / p.cropH
                        val wantRatio = p.outW.toDouble() / p.outH
                        assertTrue(
                            "$tag 裁剪比例偏了：$gotRatio vs $wantRatio",
                            abs(gotRatio - wantRatio) < 0.01
                        )

                        /* 源图比目标还小的组合**允许放大**（1600×1200 选 2M/16:9 要
                         * 1920×1080：裁完只剩 1600×900，原图就没有 1920 列），
                         * 因为用户选的档位和设置页那行尺寸是承诺，少给等于设置失效。
                         *
                         * 判据不能只看短边够不够 —— 裁剪会砍掉面积：1600×1200 的短边
                         * 1200 > 1080 看着"够"，裁成 16:9 后宽度却掉到 1600 < 1920。
                         * 所以这里直接比**裁剪区**与目标。
                         *
                         * 该守的不变量是：既然要放大，就不能再降采样。先缩后放
                         * 是白丢像素，那才是真会糊的写法。 */
                        if (p.cropW < p.outW || p.cropH < p.outH) {
                            assertEquals(
                                "$tag 要走放大就不能降采样，否则先缩后放丢细节",
                                1, p.sample
                            )
                        }
                        checked++
                    }
                }
            }
        }
        println("扫描组合数 = $checked")
        assertEquals(10 * 3 * 3 * 2, checked)
    }

    /** 采样倍数必须已经取到最大 —— 再缩一级就会小于目标。取小了等于白解码一堆像素 */
    @Test
    fun 扫描_采样倍数已取到最大() {
        for ((sw, sh) in listOf(4608 to 3456, 4000 to 3000, 8000 to 6000)) {
            for (ratio in PhotoRatio.entries) {
                val span = ratio.span(FAKE_SPAN)
                for (size in SaveSize.entries) {
                    val p = planDecode(sw, sh, false, size, ratio, span)!!
                    val nk = p.sample * 2
                    if (nk > 64) continue
                    val (cw, ch) = cropOf(max(1, sw / nk), max(1, sh / nk), p.outW.toDouble() / p.outH)
                    assertTrue(
                        "$sw x $sh $ratio $size：采样还能从 ${p.sample} 提到 $nk",
                        cw < p.outW || ch < p.outH
                    )
                }
            }
        }
    }

    @Test
    fun 非法源尺寸返回null() {
        val r = PhotoRatio.R4_3
        val span = r.span(FAKE_SPAN)
        assertNull(planDecode(0, 100, false, SaveSize.M2, r, span))
        assertNull(planDecode(100, 0, false, SaveSize.M2, r, span))
        assertNull(planDecode(-1, 100, false, SaveSize.M2, r, span))
    }

    /* ------------------------- 编码与兜底 ------------------------- */

    @Test
    fun 编码是稳定值_不跟着声明顺序走() {
        assertEquals(0, PhotoRatio.R4_3.code)
        assertEquals(1, PhotoRatio.R16_9.code)
        /* 编码 2 原本是「1:1」，改用全屏后编号不变（老设置读出来还是同一档位置） */
        assertEquals(2, PhotoRatio.R_FULL.code)
        assertEquals(0, SaveSize.M1.code)
        assertEquals(1, SaveSize.M2.code)
        assertEquals(2, SaveSize.M4.code)
    }

    @Test
    fun 认不出的编码_兜到默认档而不是崩() {
        assertEquals(PhotoRatio.R4_3, PhotoRatio.of(99))
        assertEquals(PhotoRatio.R4_3, PhotoRatio.of(-3))
        /* 默认档是 1M（见 SaveSize.of 的说明） */
        assertEquals(SaveSize.M1, SaveSize.of(99))
        assertEquals(SaveSize.M1, SaveSize.of(-3))
    }

    @Test
    fun 竖屏画幅比() {
        /* 固定档不受屏幕影响 */
        assertEquals(0.75f, PhotoRatio.R4_3.portraitAspect(FAKE_SPAN), 1e-6f)
        assertEquals(0.5625f, PhotoRatio.R16_9.portraitAspect(FAKE_SPAN), 1e-6f)
        /* 全屏档 = 1 / 屏幕比例；屏幕 2:1 时竖屏画幅就是 1:2 */
        assertEquals(0.5f, PhotoRatio.R_FULL.portraitAspect(2.0), 1e-6f)
        /* 并且横竖屏必须互为倒数（取景框摆放依赖这一条）。
         * 容差给 1e-3：portraitAspect 是 Float，倒数再转回 Double 会带上
         * Float 的 7 位精度误差（约 2e-5），卡 1e-5 会假红。 */
        val a = PhotoRatio.R_FULL.portraitAspect(2400.0 / 1080.0)
        assertEquals(2400.0 / 1080.0, 1.0 / a, 1e-3)
    }

    private fun isPow2(n: Int): Boolean = n >= 1 && (n and (n - 1)) == 0

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals("长度不同：${expected.toList()} vs ${actual.toList()}", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("第 $i 项：${expected.toList()} vs ${actual.toList()}", expected[i], actual[i])
        }
    }
}
