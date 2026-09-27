package com.daka.watermarkcamera

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar

/** 定位结果，只用来反查地点，默认不上水印 */
data class LocInfo(
    val lat: Double,
    val lon: Double,
    val accuracy: Int,
    val altitude: Double?
)

/**
 * 水印配置。字段名与 Web 版一一对应，方便两边行为一致。
 */
data class WatermarkConfig(
    var cardTitle: String = "打卡",
    var unit: String = "",
    var logoPath: String? = null,
    /**
     * 没自选 Logo 时用内置的占位 Logo。
     * 默认开 —— 不然卡片右侧永远是空的，而绝大多数用户不会去相册翻一张公司 logo 出来。
     * 关掉才回落到 [unit] 文字。优先关系见 [LogoStore.resolve]。
     */
    var useBuiltinLogo: Boolean = true,
    var addr: String = "",
    var note: String = "",
    var name: String = "",
    var showCard: Boolean = true,
    var showTime: Boolean = true,
    var showSec: Boolean = false,
    var showDate: Boolean = true,
    var showAddr: Boolean = true,
    var showNote: Boolean = false,
    var showName: Boolean = false,
    var showCoord: Boolean = false,
    var showAcc: Boolean = false,
    var showAlt: Boolean = false,
    var posTop: Boolean = false,
    var mirrorSave: Boolean = false,
    /** 定位成功后自动反查地名并填入（用户自己填过的地点不会被覆盖，见 Prefs.addrManual） */
    var autoAddr: Boolean = true,
    /**
     * 右下角品牌区的三行：品牌名 / 标语 / 防伪码。
     * 三行右对齐、字号逐行变小，位置固定贴右下角（不跟 [posTop] 走），
     * 所以单独占水印块的一段，不会和左侧的地点/备注挤在同一条水平带上。
     */
    var showBrand: Boolean = true,
    var brandName: String = "今日水印",
    var brandSlogan: String = "相机真实可验",
    var showCode: Boolean = true,
    /**
     * 机型，作为防伪码的一段输入（同地点同时刻的两台设备不该算出同一个码）。
     * 这是运行状态而非用户配置，所以 [Prefs] 不读写它，由 Activity 启动时填。
     */
    var deviceTag: String = "",
    /**
     * 手动指定的水印时间。开着时日期/时间取 [manualTimeMs]，不再跟手机时钟走 ——
     * 补拍、补录、事后根据记录填时间都要用得上。
     *
     * 防伪码把时间算进种子，所以锁定时间后码会跟着变 —— 这是对的：
     * 它绑定的本来就是「照片上写着的那组信息」，而不是「机器的时钟」。
     */
    var manualTime: Boolean = false,
    var manualTimeMs: Long = 0L
) {
    /**
     * 水印上要用哪个时刻。
     *
     * 所有绘制入口都必须走这里，不要自己 `Calendar.getInstance()` ——
     * 预览浮层、拍照、相册加水印三处只要有一处漏了，
     * 就会出现「预览显示 10:06、拍出来却是 19:40」这种最难解释的不一致。
     */
    fun now(): Calendar {
        val c = Calendar.getInstance()
        if (manualTime && manualTimeMs > 0L) c.timeInMillis = manualTimeMs
        return c
    }
}

object Prefs {
    private const val NAME = "wmc"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): WatermarkConfig {
        val p = sp(ctx)
        val d = WatermarkConfig()
        return WatermarkConfig(
            cardTitle = p.getString("cardTitle", d.cardTitle) ?: d.cardTitle,
            unit = p.getString("unit", d.unit) ?: d.unit,
            logoPath = p.getString("logoPath", null),
            useBuiltinLogo = p.getBoolean("useBuiltinLogo", d.useBuiltinLogo),
            /* 老版本可能存着没精简过的长地名（甚至套娃串），读出来先修一遍 */
            addr = Addr.compact(p.getString("addr", d.addr) ?: d.addr),
            note = p.getString("note", d.note) ?: d.note,
            name = p.getString("name", d.name) ?: d.name,
            showCard = p.getBoolean("showCard", d.showCard),
            showTime = p.getBoolean("showTime", d.showTime),
            showSec = p.getBoolean("showSec", d.showSec),
            showDate = p.getBoolean("showDate", d.showDate),
            showAddr = p.getBoolean("showAddr", d.showAddr),
            showNote = p.getBoolean("showNote", d.showNote),
            showName = p.getBoolean("showName", d.showName),
            showCoord = p.getBoolean("showCoord", d.showCoord),
            showAcc = p.getBoolean("showAcc", d.showAcc),
            showAlt = p.getBoolean("showAlt", d.showAlt),
            posTop = p.getBoolean("posTop", d.posTop),
            mirrorSave = p.getBoolean("mirrorSave", d.mirrorSave),
            autoAddr = p.getBoolean("autoAddr", d.autoAddr),
            showBrand = p.getBoolean("showBrand", d.showBrand),
            /*
             * 「打卡水印」是旧版的默认品牌名。用户要求默认印「今日水印」，
             * 所以把这个**旧默认值**迁过去 —— 只认这一个字符串，
             * 用户自己填过的任何别的名字都不动（想改回来自填即可）。
             */
            brandName = (p.getString("brandName", d.brandName) ?: d.brandName)
                .let { if (it == "打卡水印") d.brandName else it },
            brandSlogan = p.getString("brandSlogan", d.brandSlogan) ?: d.brandSlogan,
            showCode = p.getBoolean("showCode", d.showCode),
            manualTime = p.getBoolean("manualTime", d.manualTime),
            manualTimeMs = p.getLong("manualTimeMs", d.manualTimeMs)
        )
    }

    fun save(ctx: Context, c: WatermarkConfig) {
        sp(ctx).edit {
            putString("cardTitle", c.cardTitle)
            putString("unit", c.unit)
            putString("logoPath", c.logoPath)
            putBoolean("useBuiltinLogo", c.useBuiltinLogo)
            putString("addr", c.addr)
            putString("note", c.note)
            putString("name", c.name)
            putBoolean("showCard", c.showCard)
            putBoolean("showTime", c.showTime)
            putBoolean("showSec", c.showSec)
            putBoolean("showDate", c.showDate)
            putBoolean("showAddr", c.showAddr)
            putBoolean("showNote", c.showNote)
            putBoolean("showName", c.showName)
            putBoolean("showCoord", c.showCoord)
            putBoolean("showAcc", c.showAcc)
            putBoolean("showAlt", c.showAlt)
            putBoolean("posTop", c.posTop)
            putBoolean("mirrorSave", c.mirrorSave)
            putBoolean("autoAddr", c.autoAddr)
            putBoolean("showBrand", c.showBrand)
            putString("brandName", c.brandName)
            putString("brandSlogan", c.brandSlogan)
            putBoolean("showCode", c.showCode)
            putBoolean("manualTime", c.manualTime)
            putLong("manualTimeMs", c.manualTimeMs)
        }
    }

    /**
     * 地点是不是"用户自己填的"。
     * true  = 手输过 → 自动反查不许覆盖它；
     * false = 可以自动填（点「清空」就会回到自动模式）。
     * 这是运行状态而不是水印配置，所以没塞进 [WatermarkConfig]。
     */
    fun addrManual(ctx: Context): Boolean = sp(ctx).getBoolean("addrManual", false)

    fun setAddrManual(ctx: Context, v: Boolean) = sp(ctx).edit { putBoolean("addrManual", v) }

    /**
     * 闪光灯档位，三态循环：0 关 / 1 拍照时闪 / 2 常亮补光。
     *
     * 为什么要三态：只给「开/关」时，用户点了「开」但在某些 ROM 上灯就是不亮，
     * 没有任何办法判断是没生效还是没触发。加一档**常亮**（走 CameraControl.enableTorch，
     * 与拍照闪光是两条独立的底层路径）后，用户一按就能肉眼确认灯到底能不能亮，
     * 而且拍暗处补光常亮本来就比瞬间闪更好用。
     *
     * 选择要记住 —— 上次开了，下次打开相机还应该开着。
     */
    fun flashMode(ctx: Context): Int {
        val p = sp(ctx)
        if (p.contains("flashMode")) return p.getInt("flashMode", 0).coerceIn(0, 2)
        /* 旧版本只存了布尔值，迁移一次，别让用户已经做过的选择凭空丢 */
        return if (p.getBoolean("flashOn", false)) 1 else 0
    }

    fun setFlashMode(ctx: Context, v: Int) = sp(ctx).edit { putInt("flashMode", v) }

    fun shotCount(ctx: Context): Int = sp(ctx).getInt("count", 0)
    fun setShotCount(ctx: Context, n: Int) = sp(ctx).edit { putInt("count", n) }

    /** 常用地点，最多 12 个 */
    fun addresses(ctx: Context): MutableList<String> {
        val raw = sp(ctx).getString("addrs", "") ?: ""
        return if (raw.isBlank()) mutableListOf() else raw.split("\u0001").toMutableList()
    }

    fun rememberAddress(ctx: Context, a: String) {
        if (a.isBlank()) return
        val list = addresses(ctx)
        list.remove(a)
        list.add(0, a)
        while (list.size > 12) list.removeAt(list.size - 1)
        sp(ctx).edit { putString("addrs", list.joinToString("\u0001")) }
    }
}
