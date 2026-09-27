package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 品牌区矢量数据的**纯字符串**校验 —— 故意不起 Robolectric。
 *
 * 为什么单独放一个类：Robolectric 那个类跑一轮要十几分钟，而这里要问的问题
 * （路径串结构对不对、记号数量配不配）根本不需要 android.graphics。
 * 上一轮就是因为校验只能挂在 Robolectric 测试里，出问题时排查一轮等十几分钟。
 *
 * 用法：gradle testDebugUnitTest --tests "*BrandMarkPathsTest"   ← 几秒出结果
 */
class BrandMarkPathsTest {

    /** 每个命令要带几个操作数 */
    private val arity = mapOf('M' to 2, 'L' to 2, 'Q' to 4, 'Z' to 0)

    private fun all() = BrandMarkPaths.NAME + BrandMarkPaths.SLOGAN

    @Test
    fun 一共十个字形() {
        assertEquals("品牌名 4 字", 4, BrandMarkPaths.NAME.size)
        assertEquals("标语 6 字", 6, BrandMarkPaths.SLOGAN.size)
        assertEquals("标语里前 2 个是白字「相机」", 2, BrandMarkPaths.SLOGAN_WHITE_COUNT)
    }

    @Test
    fun 每串路径的命令与操作数数量都配得上() {
        for ((n, d) in all().withIndex()) {
            val toks = brandPathTokens(d)
            assertTrue("#$n 路径是空的", toks.isNotEmpty())
            assertEquals("#$n 必须以 M 开头", 'M', toks[0][0])

            var i = 0
            var cmds = 0
            while (i < toks.size) {
                val t = toks[i]
                assertTrue(
                    "#$n 第 $i 个记号既不是命令也不是数字：'$t'",
                    t[0].isLetter() || t.toIntOrNull() != null
                )
                if (t[0].isLetter()) {
                    val need = arity[t[0]]
                    assertTrue("#$n 出现了不认识的命令 '$t'", need != null)
                    cmds++
                    i++
                    for (k in 0 until need!!) {
                        assertTrue(
                            "#$n 命令 '$t' 后操作数不够（${d.take(40)}…）",
                            i < toks.size && !toks[i][0].isLetter()
                        )
                        i++
                    }
                } else {
                    assertTrue("#$n 第 $i 个位置出现悬空数字 '$t'（${d.take(40)}…）", false)
                    i++
                }
            }
            assertTrue("#$n 一个闭合命令 Z 都没有", cmds > 1)
            assertTrue("#$n 结尾不是 Z", d.endsWith("Z"))
        }
    }

    /** 汉字一个轮廓肯定不止一个命令 —— 只画一个方块说明轮廓提取退化了 */
    @Test
    fun 每个字形都有足够多的轮廓点() {
        for ((n, d) in all().withIndex()) {
            val toks = brandPathTokens(d)
            val pts = toks.count { !it[0].isLetter() }
            assertTrue("#$n 轮廓点太少（$pts），像是被截断了", pts >= 12)
            println("#$n 记号 ${toks.size}，坐标 $pts，${d.length} 字符")
        }
        val total = all().sumOf { it.length }
        println("10 个字的路径合计 $total 字符 ≈ ${"%.1f".format(total / 1024.0)} KB")
        assertTrue("矢量数据应该只有几 KB，实际 $total 字符", total in 1000..12000)
    }

    /** 坐标必须落在字身框附近（M 起点、大跳到几万说明单位算错了） */
    @Test
    fun 坐标落在合理范围内() {
        for ((n, d) in all().withIndex()) {
            for (t in brandPathTokens(d)) {
                if (t[0].isLetter()) continue
                val v = t.toInt()
                assertTrue("#$n 出现异常坐标 $v（应在 -1500..7000 之间）", v in -1500..7000)
            }
        }
    }

    /** 路径里不该有"[空白/逗号/字母/正负号/数字]"以外的字符 —— 手写扫描器只认这五类 */
    @Test
    fun 路径里没有扫描器认不出的字符() {
        for ((n, d) in all().withIndex()) {
            d.forEachIndexed { idx, c ->
                val ok = c == ' ' || c == ',' || c == '\n' ||
                    c.isLetter() || c == '-' || c == '+' || c in '0'..'9'
                assertTrue(
                    "#$n 第 $idx 位是 '%c' (U+%04X)，扫描器认不出".format(c, c.code),
                    ok
                )
            }
        }
    }

    /**
     * 旧版**手写逐字符扫描器**的等价复刻 —— 它当初报 `NumberFormatException: empty String`。
     *
     * 真因：**同一条命令的两个操作数之间的空格没人跳**。
     * 拿 `"M718 -196"` 走一遍就清楚了 ——
     *   `num()` 第一次从下标 1 读到 `718`，停在空格（下标 4）；
     *   `num()` 第二次紧接着从下标 4 出发，那里是空格，既不是符号也不是数字，
     *   于是 `i` 一动不动，`substring(4, 4)` 取出空串 → `parseFloat("")` 炸。
     * 外层 while 只在"要去取下一个记号"时跳空白，命令内部连读两个操作数时漏了这一步。
     *
     * 保留这个复刻是为了让教训留在仓库里：**手写扫描器要防的不是数据，
     * 是自己漏掉的分支**。换成 [brandPathTokens] 的 Regex 分词后这类问题不存在 ——
     * 正则没有"状态"，不会因为上一个命令用掉了几个记号而错位。
     */
    private fun oldScanner(d: String, onEmpty: (Int, Char) -> Unit): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var cmd = 'Z'
        fun num(): String {
            val start = i
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            while (i < d.length && d[i] in '0'..'9') i++
            if (start == i) onEmpty(start, if (start < d.length) d[start] else ' ')
            return d.substring(start, i)
        }
        while (i < d.length) {
            val c = d[i]
            if (c == ' ' || c == ',' || c == '\n') { i++; continue }
            if (c.isLetter()) { cmd = c; i++; continue }
            when (cmd) {
                'M' -> { out += "M"; out += num(); out += num(); cmd = 'L' }
                'L' -> { out += "L"; out += num(); out += num() }
                'Q' -> { out += "Q"; out += num(); out += num(); out += num(); out += num() }
                'Z' -> { out += "Z" }
                else -> return out
            }
        }
        return out
    }

    /**
     * 复刻版必须也在同一个地方炸 —— 没炸说明复刻得不像，那个结论就站不住。
     *
     * 头一次取到空串的位置必须是**空格**（那就是"操作数之间的分隔符没人跳"）；
     * 后面那些位置落在 `L` 上是**连锁反应**：第一次失败后 i 没前进，
     * 命令序列已经错位，后面的操作数自然也就对不上了。
     */
    @Test
    fun 旧扫描器_确实在操作数之间的空格上取到空串() {
        val spots = ArrayList<Pair<Int, Char>>()
        for (d in all()) oldScanner(d) { pos, ch -> spots += pos to ch }
        println("旧扫描器取到空串的位置共 ${spots.size} 处，前 6 个：")
        spots.take(6).forEach { println("    位置 ${it.first} 字符 '${it.second}'") }

        assertTrue("复刻版居然没炸 —— 说明它和当初那版不等价", spots.isNotEmpty())
        assertEquals("第一处必须落在空格上", ' ', spots.first().second)
        val firsts = spots.filter { it.second == ' ' }.size
        println("其中落在空格上的有 $firsts 处，其余是连锁反应")
        assertTrue("落在空格上的应该不止一处", firsts > 10)
    }
}
