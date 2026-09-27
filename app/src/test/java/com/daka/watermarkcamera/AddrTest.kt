package com.daka.watermarkcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 地点精简的断言。纯字符串逻辑，不需要 Robolectric。
 * 目标样式来自参考图：`星海市云山区·风和苑`。
 */
class AddrTest {

    @Test
    fun 典型长地址_削成市区县加小区() {
        assertEquals(
            "星海市云山区·风和苑",
            Addr.compact("云岭省星海市云山区湖滨街道风和苑3栋2单元501室")
        )
    }

    @Test
    fun 已精简的再精简一次_不变() {
        val once = "星海市云山区·风和苑"
        assertEquals(once, Addr.compact(once))
        assertEquals(once, Addr.compact(Addr.compact("云岭省星海市云山区湖滨街道风和苑3栋")))
    }

    @Test
    fun 直辖市_没有省这一层() {
        assertEquals("北京市朝阳区·望京SOHO", Addr.compact("北京市朝阳区望京SOHO"))
        assertEquals("上海市浦东新区·张江镇", Addr.compact("上海市浦东新区张江镇"))
    }

    @Test
    fun 只有市没有区县() {
        assertEquals("星海市·风和苑", Addr.compact("星海市风和苑"))
    }

    @Test
    fun 只有小区名_一个字都不动() {
        assertEquals("风和苑", Addr.compact("风和苑"))
        assertEquals("DEMO PARK", Addr.compact("DEMO PARK"))
    }

    @Test
    fun 只有街道加小区_街道也要削掉() {
        assertEquals("风和苑", Addr.compact("湖滨街道风和苑"))
    }

    @Test
    fun 省略号_超长小区名截断并标记() {
        val out = Addr.compact("云岭省星海市云山区湖滨街道一个特别特别长的小区名字ABCDE")
        println("截断结果 = $out")
        assertEquals("星海市云山区·一个特别特别长的小区…", out)
    }

    /* ---------- 居委会层级：Geocoder 常回「xx社区风和苑商业广场」 ---------- */

    /**
     * 线上真实现场：地址栏显示成 `星海市云山区·湖滨社区风和苑商业…`。
     * 「湖滨社区」是居委会不是小区名，留着会把真正的小区名挤到截断。
     */
    @Test
    fun 居委会层级要削掉_小区名才留得下() {
        assertEquals(
            "星海市云山区·风和苑商业广场",
            Addr.compact("云岭省星海市云山区青溪镇湖滨社区风和苑商业广场")
        )
        assertEquals(
            "星海市云山区·风和苑",
            Addr.compact("星海市云山区湖滨社区风和苑")
        )
    }

    /** 整串就叫「阳光社区」的，这是小区名，一个字都不能动 */
    @Test
    fun 整串就是社区名_一个字都不动() {
        assertEquals("阳光社区", Addr.compact("阳光社区"))
        assertEquals("幸福村委会", Addr.compact("幸福村委会"))
    }

    /** 削的顺序：先削楼栋门牌，剩下的 `阳光社区` 后面没字了，就不该再被削 */
    @Test
    fun 社区名加楼栋_先削楼栋再判社区_不许削空() {
        assertEquals("星海市洛川区·阳光社区", Addr.compact("星海市洛川区阳光社区2栋3单元501室"))
    }

    @Test
    fun 空串_不炸() {
        assertEquals("", Addr.compact(""))
        assertEquals("", Addr.compact("   "))
    }

    @Test
    fun 空格与逗号会被清掉() {
        assertEquals("星海市云山区·风和苑", Addr.compact("云岭省 星海市，云山区 湖滨街道 风和苑"))
    }

    /* ---------- 套娃：线上真实翻车现场（截图上显示成 星海市云山区·云岭省星海市云山区黄…） ---------- */

    @Test
    fun 套娃串_旧结果拼新结果_只留信息全的那截() {
        assertEquals(
            "星海市云山区·翠湖新村",
            Addr.compact("星海市云山区·云岭省星海市云山区青溪镇翠湖新村")
        )
        /* 前段是纯小区名也要能修 */
        assertEquals(
            "星海市云山区·翠湖新村",
            Addr.compact("风和苑·云岭省星海市云山区青溪镇翠湖新村")
        )
    }

    @Test
    fun 套娃三轮也不怕() {
        val once = "星海市云山区·翠湖新村"
        var s = "云岭省星海市云山区青溪镇翠湖新村"
        repeat(3) { s = once + "·" + s }
        assertEquals(once, Addr.compact(s))
        assertEquals(once, Addr.compact(Addr.compact(s)))
    }

    @Test
    fun 幂等_反复精简结果不变() {
        val inputs = listOf(
            "云岭省星海市云山区湖滨街道风和苑3栋2单元501室",
            "北京市朝阳区望京SOHO",
            "上海市浦东新区张江镇",
            "星海市风和苑",
            "湖滨街道风和苑",
            "星海市云山区·风和苑"
        )
        for (i in inputs) {
            val once = Addr.compact(i)
            assertEquals("二次精简必须和一次一致：$i", once, Addr.compact(once))
        }
    }

    /**
     * 截断本身会丢信息（长度按截断后的算），所以"一次→二次"允许变化；
     * 但不许越来越差，也不许把小区名当成"区"吃掉。
     */
    @Test
    fun 截断与套娃_二次之后必须收敛且不能吃空() {
        val inputs = listOf(
            "云岭省星海市云山区湖滨街道一个特别特别长的小区名字ABCDE",
            "星海市云山区·一个特别特别长的小区…",
            "星海市云山区·云岭省星海市云山区青溪镇翠湖新村",
            "星海市云山区·翠湖新村"
        )
        for (i in inputs) {
            val a = Addr.compact(i)
            val b = Addr.compact(a)
            val c = Addr.compact(b)
            println("$i  →  $a  →  $b  →  $c")
            assertEquals("二次之后必须收敛：$i", b, c)
            assertFalse("不许出现被吃空的怪结果：$i → $b", b.endsWith("·") || b.endsWith("·…"))
        }
    }
}
