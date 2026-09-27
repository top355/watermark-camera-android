package com.daka.watermarkcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object Geo {

    /** 拿最近一次已知位置（不新开监听，设置页里用够了） */
    fun bestLastKnown(ctx: Context): Location? {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            @Suppress("DEPRECATION")
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: Exception) {
                null
            } ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        return best
    }

    /**
     * 反查地名。走系统 Geocoder —— 国内 ROM 一般接的是厂商自己的服务，能出中文；
     * 拉不到就返回 null，让用户手填。绝不自造一个看起来对的地名。
     *
     * 返回前统一精简成「市+区县·小区」，见 [Addr.compact]。
     */
    suspend fun reverse(ctx: Context, lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(ctx, Locale.CHINA).getFromLocation(lat, lon, 1)
                list?.firstOrNull()?.let { a ->
                    val parts = listOfNotNull(
                        a.adminArea,
                        a.locality,
                        a.subLocality,
                        a.featureName
                    ).distinct()
                    Addr.compact(parts.joinToString("")).ifBlank { null }
                }
            } catch (e: Exception) {
                null
            }
        }
}
