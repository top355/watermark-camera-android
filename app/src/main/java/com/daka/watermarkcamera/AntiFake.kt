package com.daka.watermarkcamera

import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

/**
 * 防伪码 —— **由这张照片的水印内容算出来的真摘要**，不是随机数、更不是占位文字。
 *
 * 输入 = 时间（到分钟）+ 地点 + 备注 + 打卡人 + 单位 + 机型 + 经纬度。
 * 同一组输入必然得到同一个码，所以码和照片上的文字是**绑定**的：
 * 谁改了水印内容，重算就对不上。
 *
 * ## 它能证什么、不能证什么（这点必须诚实）
 *
 * - **能证**：照片上的水印字段自洽 —— 拿图上的文字重算，能复现出同一个码。
 *   谁改过字，码就不对了。
 * - **不能证**：这张照片确实是某台设备在某个时刻拍的。因为校验方没有留底，
 *   任何人拿到这些字段都能自己算出同一个码。
 *
 * 真正「可验证」需要服务端在拍摄时留一份摘要存档（本项目的下一步）。在做到那一步之前，
 * 本地算的码是**把这件事提前准备好**，而不是假装已经做到了 —— 所以 UI 上的说明文字
 * 只写它能证的，不写它不能证的。
 */
object AntiFake {

    /**
     * 取 32 个字符，正好对上 `and 0x1F` 的 5 bit 取值，不会越界也不会偏分布。
     * 剔掉 `0 / O / 1 / I` —— 这四个抄下来最容易错（数字字母混排时几乎必错）。
     */
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /**
     * 码长 14 位（= 70 bit 摘要）。
     *
     * 对齐参考图（今日水印相机）的位数 —— 用户按图数过，就是 14 位。
     *
     * **一口气连排，不加分隔符** —— 参考图里也是这么排的。加横线有两个坏处：
     * 一是在水印那种小字号下横线几乎看不见、却白白多占三个字符位；
     * 二是抄写时人会自动忽略横线，反而更容易看错。
     */
    const val LEN = 14

    /** 字符集，供测试断言"没有易混字符"用 */
    internal const val CHARSET = ALPHABET

    /**
     * 时间只取到**分钟**：预览每 250ms 重绘一次，若把秒也算进去，
     * 用户看到的预览码和按下快门后出图的码会不一样 —— 那才是真的"防伪不了"。
     */
    fun code(cfg: WatermarkConfig, now: Calendar, loc: LocInfo?): String {
        val seed = buildString(96) {
            append(
                String.format(
                    Locale.US, "%04d%02d%02d%02d%02d",
                    now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH),
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE)
                )
            )
            append('|').append(cfg.addr)
            append('|').append(cfg.note)
            append('|').append(cfg.name)
            append('|').append(cfg.unit)
            append('|').append(cfg.deviceTag)
            if (loc != null) {
                append('|').append(String.format(Locale.US, "%.4f,%.4f", loc.lat, loc.lon))
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))

        val sb = StringBuilder(LEN)
        var buf = 0
        var bits = 0
        var i = 0
        while (sb.length < LEN && i < digest.size) {
            buf = (buf shl 8) or (digest[i].toInt() and 0xFF)
            bits += 8
            i++
            while (bits >= 5 && sb.length < LEN) {
                bits -= 5
                sb.append(ALPHABET[(buf ushr bits) and 0x1F])
            }
        }
        return sb.toString()
    }

    /**
     * 水印上那一行完整文字，供渲染与测试共用。
     *
     * 标签是「防伪」而不是「防伪码」—— 后面的 14 位就是码本身，再挂一个「码」字是重复。
     * 字少一个，那行在小字号下也更好读。
     */
    fun line(cfg: WatermarkConfig, now: Calendar, loc: LocInfo?): String =
        "防伪 " + code(cfg, now, loc)
}
