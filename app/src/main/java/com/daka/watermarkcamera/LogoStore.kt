package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 公司 Logo 的本地存取：用户选图后压到 400px 宽，存成 PNG（保留透明底）。
 *
 * 除了用户自选的图，这里还负责**内置占位 Logo** —— 默认就启用，
 * 用户没自选时兜底，免得卡片右侧永远是空的。优先级规则集中在 [pick]。
 *
 * 这里有两个必须小心的点，都是实测踩出来的：
 *
 * 1. **只读一次流**。相册、云盘、第三方文件管理器给的 `openInputStream` 未必可重复打开，
 *    有的第二次直接返回 null 或空流。所以先把整张图读进内存（ByteArray），
 *    之后量尺寸、解码都在内存里做 —— 这也是 BitmapFactory 官方推荐的用法
 *    （`decodeStream` 对不可 mark/reset 的流行为不稳定）。
 * 2. **解码优先走 ImageDecoder**（API 28+）。它能解 HEIC/HEIF（现在相册里手机拍的照片
 *    默认就是这种格式，`BitmapFactory` 在部分设备上解不出来），并自动应用 EXIF 方向。
 *    但必须指定 `ALLOCATOR_SOFTWARE` —— 默认出的是 HARDWARE bitmap，
 *    既不能 `compress()` 成 PNG，也不能画到软件 Canvas 上（会直接抛异常）。
 */
object LogoStore {

    private const val FILE_NAME = "logo.png"
    private const val TARGET_W = 400

    /** 原图上限。超过就没必要读了，也避免把内存吃光 */
    private const val MAX_BYTES = 24 * 1024 * 1024

    /**
     * 内置占位 Logo（`res/drawable/logo_placeholder.xml`）。
     *
     * 手写的矢量：一个中性灰蓝圆角方块 + 白色快门环，**不含任何公司标识** ——
     * 它的作用只是"别让卡片右侧空着"，谁都可以直接换成自己的图（设置页自选即覆盖）。
     * 同一份图形在 `watermark-camera/assets/logo-placeholder.svg` 与
     * `index.html` 的内联 SVG 里还各有一份，用 `verify/logo-parity.js` 查一致性。
     *
     * 因为是矢量，放大不会糊、包体只多 1KB 出头。
     *
     * 上一版这里内置的是从参考图描摹来的位图 Logo。**换内置 Logo 要一起动的有四处**：
     * 本常量、上面列的三份资产、[sourceLabel] 的文案、以及 `LogoStoreTest` 里
     * 按像素验收那两条断言（它们认的是填充色，图形一换就得跟着换）。
     */
    private const val DEFAULT_RES = R.drawable.logo_placeholder

    /** 栅格化后的内置 Logo 宽度。水印里 logo 显示高度约 100px，400 已经足够 */
    private const val DEFAULT_W = 400

    /** 纯资源函数，栅格化一次就够；主界面每次 onResume 都会要一次，别重复画 */
    private var defaultCache: Bitmap? = null

    /** 保存结果。失败时带上具体原因，便于用户回报问题 */
    sealed class SaveResult {
        data class Ok(val path: String, val width: Int, val height: Int) : SaveResult()
        data class Fail(val reason: String) : SaveResult()
    }

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context, path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.exists() || f.length() <= 0L) return null
        return try {
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply {
                    /* 不按资源密度二次缩放，拿到多少像素就是多少 */
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 内置 Logo，栅格化成 [Bitmap]。
     *
     * 为什么要栅格化：水印整幅是画在**软件 Canvas** 上的（渲染产物要给
     * `Bitmap.compress` 编码），没法把 Drawable 直接丢进去。所以这里把
     * VectorDrawable 按固定宽度画进一张 Bitmap —— 注意别用 `intrinsicWidth`
     * 当像素宽，它带 density 缩放（340dp 在 3x 屏上是 1020px），
     * 只拿它算**宽高比**，宽度自己定。
     */
    fun defaultLogo(ctx: Context): Bitmap? {
        defaultCache?.let { if (!it.isRecycled) return it }
        val bmp = try {
            val d = ContextCompat.getDrawable(ctx, DEFAULT_RES)
            if (d == null) {
                null
            } else {
                val iw = d.intrinsicWidth.coerceAtLeast(1)
                val ih = d.intrinsicHeight.coerceAtLeast(1)
                val w = DEFAULT_W
                val h = max(1, Math.round(ih.toFloat() * w.toFloat() / iw.toFloat()))
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, w, h)
                d.draw(Canvas(b))
                b
            }
        } catch (e: Throwable) {
            null
        }
        defaultCache = bmp
        return bmp
    }

    /**
     * 优先级规则本体：有自选就用自选，否则按开关决定要不要内置。
     *
     * [custom] 由调用方先加载好传进来 —— 主界面在 onResume 里已经为了检查
     * 「文件是否还在」解过一次图了，再解一次没必要。
     * 单独抽出来是为了让「设置页预览」和「拍照」用的是同一份规则。
     *
     * 返回 null 时渲染器会回落到「卡片右侧显示单位文字」。
     */
    fun pick(ctx: Context, cfg: WatermarkConfig, custom: Bitmap?): Bitmap? =
        custom ?: if (cfg.useBuiltinLogo) defaultLogo(ctx) else null

    /** [pick] 的便捷版：自己先把自选图读出来 */
    fun resolve(ctx: Context, cfg: WatermarkConfig): Bitmap? =
        pick(ctx, cfg, load(ctx, cfg.logoPath))

    /** 给设置页显示「当前用的是哪一个」，避免用户以为设置没生效 */
    fun sourceLabel(cfg: WatermarkConfig, hasCustomFile: Boolean): String = when {
        hasCustomFile -> "自定义图片"
        cfg.useBuiltinLogo -> "内置占位 Logo"
        else -> "不使用 Logo（卡片右侧显示单位文字）"
    }

    fun save(ctx: Context, uri: Uri): SaveResult {
        /* ---- 1. 一次性把原图读进内存 ---- */
        val bytes = try {
            readAll(ctx, uri)
        } catch (e: SecurityException) {
            return SaveResult.Fail("没有读取该文件的权限")
        } catch (e: TooLargeException) {
            return SaveResult.Fail("图片超过 24MB，请先压缩再选")
        } catch (e: Exception) {
            return SaveResult.Fail("打不开这个文件")
        } ?: return SaveResult.Fail("打不开这个文件")
        if (bytes.isEmpty()) return SaveResult.Fail("文件是空的")

        /* ---- 2. 解码并按宽度压到 400px ---- */
        val decoded = decodeScaled(bytes)
            ?: return SaveResult.Fail("这个格式解不了（试试 PNG / JPG）")
        val dw = decoded.width
        val dh = decoded.height
        if (dw <= 0 || dh <= 0) {
            decoded.recycle()
            return SaveResult.Fail("图片尺寸异常")
        }

        /* ---- 3. 原子写入：先写临时文件，成功再改名 ---- */
        val out = file(ctx)
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        return try {
            FileOutputStream(tmp).use { fos ->
                if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    tmp.delete()
                    return SaveResult.Fail("图片编码失败")
                }
                fos.flush()
            }
            if (out.exists() && !out.delete()) {
                tmp.delete()
                return SaveResult.Fail("替换旧 Logo 失败")
            }
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return SaveResult.Fail("写入失败，存储空间可能不足")
            }
            SaveResult.Ok(out.absolutePath, dw, dh)
        } catch (e: Exception) {
            tmp.delete()
            SaveResult.Fail("写入失败：" + (e.message ?: "未知"))
        } finally {
            decoded.recycle()
        }
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 原图超出 MAX_BYTES 时抛出，好让上层给出准确原因而不是笼统的"打不开" */
    private class TooLargeException : IOException("logo exceeds MAX_BYTES")

    /** 读完整流。只调用一次 —— 见类注释第 1 条 */
    private fun readAll(ctx: Context, uri: Uri): ByteArray? {
        val ins = ctx.contentResolver.openInputStream(uri) ?: return null
        ins.use { input ->
            val bos = ByteArrayOutputStream(128 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    break
                }
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw TooLargeException()
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        }
    }

    /** 解码 + 缩放到 TARGET_W。返回的 Bitmap 一定不是 hardware 位图 */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        /* 先看尺寸；目标尺寸只有这一处算法，两条解码路径必须给出同样的结果 */
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null

        val tw = if (sw > TARGET_W) TARGET_W else sw
        val th = max(1, Math.round(sh.toFloat() * tw.toFloat() / sw.toFloat()))
        if (tw <= 0 || th <= 0) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val out: Bitmap? = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(tw, th)
                }
                if (out != null) return out
            } catch (e: Throwable) {
                /* 落到 BitmapFactory 再试一次 */
            }
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sw, sh, tw)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width == tw) return bmp

        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** 宽高同时降到 ~target 附近，避免长截图之类把内存吃爆 */
    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        var cw = w
        var ch = h
        while (cw / 2 >= target && ch / 2 >= target) {
            cw /= 2
            ch /= 2
            s *= 2
        }
        return s
    }
}
