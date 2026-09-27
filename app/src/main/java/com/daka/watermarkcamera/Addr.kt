package com.daka.watermarkcamera

/**
 * 地点文案精简。
 *
 * 参考图（今日水印相机）底部只有「星海市云山区·风和苑」一行；
 * 而系统 Geocoder 常常给出「云岭省星海市云山区湖滨街道风和苑3栋2单元501室」。
 * 后者会把水印那一行顶满、字号被压小，所以在这里削掉行政冗余。
 *
 * 原则：**只削，不猜**。认不出行政层级就原样返回 ——
 * 宁可长一点，也不能把一个用户没写过的地名编出来。
 */
object Addr {

    private const val SEP = "·"

    /** 省 / 自治区 / 特别行政区 */
    private val PROVINCE = Regex("^(?:中国)?[\\u4e00-\\u9fa5]{2,8}?(?:省|自治区|特别行政区)")
    /** 地级：市 / 自治州 / 地区 / 盟 */
    private val CITY = Regex("^[\\u4e00-\\u9fa5]{2,8}?(?:自治州|地区|盟|市)")
    /** 县级：区 / 县 / 县级市 / 旗 */
    private val DISTRICT = Regex("^[\\u4e00-\\u9fa5]{2,10}?(?:区|县|市|旗)")

    /**
     * 上面那个正则会匹配到、但其实**不是行政区**的词尾。
     * 「一个特别特别长的小区」曾经被当成「…区」剥掉，把小区名吃了个干净。
     */
    private val NOT_ADMIN = Regex("(小区|园区|厂区|社区|校区|景区|街区|片区|院区|库区)$")

    /** 每轮剥离前先吃掉前导的分隔符与空白（`·云岭省…` → `云岭省…`） */
    private val PREFIX_SEP = Regex("^[·•\\-—\\s]+")

    /**
     * 居委会层级：`湖滨社区`、`幸福村委会`、`前进居委会`。
     *
     * Geocoder 常回「云山区青溪镇湖滨社区风和苑商业广场」，
     * 这里的「湖滨社区」是居委会而不是小区名，留着会把真正的小区名挤到截断。
     *
     * 只在它后面**还有汉字**时才削 —— 整串就叫「阳光社区」的，一个字都不能动。
     * 也正是因此要放在 [NOISE] 之后：`阳光社区2栋` 会先被削成 `阳光社区`，
     * 那时后面已经没有字了，就不会被继续削空。
     */
    private val COMMUNITY = Regex("^.{1,8}?(?:社区|居委会|村委会|村民委员会)(?=[\\u4e00-\\u9fa5])")

    /** 详细地址里属于"太细"的尾巴，削掉 */
    private val NOISE = listOf(
        /* 湖滨街道风和苑 → 风和苑（街道后面还有内容才削，别把"XX街道"本身削没了） */
        Regex("^.{1,8}?(?:街道|镇|乡|办事处)(?=[\\u4e00-\\u9fa5])"),
        Regex("[（(][^)）]*[)）]"),
        Regex("[0-9０-９]+(?:号楼|栋|幢|单元|室|层|楼).*$"),
        Regex("[0-9０-９]+号.*$"),
        Regex("(?:东北|东南|西北|西南|正东|正南|正西|正北)[0-9０-９]*米?$"),
        Regex("附近$")
    )

    /**
     * @param detailMax 小区名保留的最大字数，超了截断（带省略号，让人看得出被截了）
     */
    fun compact(raw: String, detailMax: Int = 10): String {
        val src = raw.trim()
        if (src.isEmpty()) return ""

        val s = src.replace('\u3000', ' ')
            .replace(Regex("[\\s,，]+"), "")
            .removePrefix("中国")
            /* 上一次截断留下的省略号别当成地名 */
            .removeSuffix("…")

        /*
         * 套娃修复：线上出现过 `星海市云山区·云岭省星海市云山区黄…`，
         * 即「上一次的精简结果」被拼上了「这次的原始长串」。
         * 这时分隔符前面那截只是复述，丢掉它、只保留后面那截。
         * （正常输出 `星海市云山区·风和苑` 不受影响：末段里没有完整的 市+区县。）
         */
        val segs = s.split(Regex("[·•]")).filter { it.isNotBlank() }
        val body = if (segs.size > 1 && startsWithCityAndDistrict(segs.last())) segs.last() else s

        /*
         * 反复剥行政前缀，直到剥不动为止。
         * 只剥一遍是不够的：套娃串里会有「省 市 区县」出现两次，
         * 第二遍才能把它们清干净、把真正的小区名露出来。上限 4 轮防死循环。
         */
        var rest = body
        var city = ""
        var district = ""
        for (i in 0 until 4) {
            rest = rest.replace(PREFIX_SEP, "")
            var changed = false
            PROVINCE.find(rest)?.let { rest = rest.removeRange(it.range); changed = true }
            CITY.find(rest)?.let { city = it.value; rest = rest.removeRange(it.range); changed = true }
            DISTRICT.find(rest)?.let {
                if (!NOT_ADMIN.containsMatchIn(it.value)) {
                    district = it.value
                    rest = rest.removeRange(it.range)
                    changed = true
                }
            }
            if (!changed) break
        }

        var detail = rest
        val tail = detail
        NOISE.forEach { detail = detail.replace(it, "") }
        /* 楼栋门牌削完之后再削居委会层级，顺序不能反（见 [COMMUNITY]） */
        detail = detail.replace(COMMUNITY, "")

        /* 既没有市/区县，细节也一点没削掉 → 这是用户自己写的短名，一个字都别动 */
        if (city.isEmpty() && district.isEmpty() && detail == tail) return src

        if (detail.length > detailMax) detail = detail.substring(0, detailMax) + "…"

        val head = city + district
        return when {
            detail.isEmpty() -> head.ifEmpty { src }
            head.isEmpty() -> detail
            else -> head + SEP + detail
        }
    }

    /**
     * 这一段开头是不是「市 + 区县」两级都齐全 —— 用来判断它是不是一份完整的地址。
     * 只要市是不够的：「一个特别特别长的小区」里的「小区」会被县级正则误认为「区」，
     * 所以要求两级都在，才认它是"完整的那一份"。
     */
    private fun startsWithCityAndDistrict(seg: String): Boolean {
        val c = CITY.find(seg) ?: return false
        val after = seg.removeRange(c.range)
        val d = DISTRICT.find(after) ?: return false
        return !NOT_ADMIN.containsMatchIn(d.value)
    }
}
