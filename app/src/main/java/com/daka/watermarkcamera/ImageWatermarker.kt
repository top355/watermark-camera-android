package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Calendar
import kotlin.math.max

/**
 * 给**相册里的现成图片**加水印。
 *
 * 与拍照那条路的区别只有两处，其余（水印绘制、保存、分享）完全复用：
 * 1. 图片来源是 URI 而不是相机的 JPEG 字节，所以要先解码 + 按 EXIF 摆正；
 * 2. **不做镜像**。镜像保存是给前置摄像头自拍用的选项，
 *    相册里的图是别的时刻、可能别的设备拍的，镜像它等于伪造。
 *
 * 水印绘制走的是同一个 [WatermarkRenderer]，所以从相册加的图与拍出来的图
 * 版式完全一致 —— 这也是"归一化按短边算"这个设计的收益：任意尺寸的图进来，
 * 水印的相对位置和比例都不变。
 */
object ImageWatermarker {

    /**
     * 解码上限（长边）。再大对打卡照片没有意义 ——
     * 3072 长边的 ARGB_8888 就已经是 30MB 量级，一张图两份（源 + 输出）
     * 正好卡在低端机的痛点上。
     */
    const val MAX_SIDE = 3072

    /**
     * 解码一张相册图片。失败返回 null，不抛异常 ——
     * 相册里的东西什么都有（损坏文件、云盘占位图、别的 App 的私有 URI），
     * 让上层统一按"读不了"处理即可。
     */
    fun decode(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) modern(ctx, uri, maxSide)
        else legacy(ctx, uri, maxSide)
    } catch (t: Throwable) {
        null
    }

    /** API 28+：ImageDecoder 会**自动按 EXIF 摆正**，不用自己读方向 */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun modern(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val k = sampleFor(info.size.width, info.size.height, maxSide)
            if (k > 1) dec.setTargetSampleSize(k)
            /*
             * 必须显式要**软件**位图。默认给的是 HARDWARE 位图，
             * 它不能当绘制源以外的用途（取像素、再缩放都受限），
             * 而这里要的是一张能当 Canvas 底、也能被再次处理的普通位图。
             */
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** API 26/27：ImageDecoder 还没有，只能自己读 EXIF 方向再转 */
    private fun legacy(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = ctx.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val deg = ctx.contentResolver.openInputStream(uri)?.use { input ->
            when (
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0

        if (deg != 0) {
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(deg.toFloat()) }, true
            )
            /* createBitmap 在不需要变换时会原样返回同一个对象，别把它回收了 */
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    /**
     * 采样倍数只能是 2 的幂，取「缩完长边仍不小于 maxSide」的最大值。
     *
     * 注意是"不小于"而不是"不大于"：宁可多缩一级也别缩过头 ——
     * 缩过头会让水印相对变小，从相册加的水印就和拍出来的看起来不一样了。
     * （严格说归一化保证的是**比例**不变，但保住分辨率总没坏处。）
     */
    private fun sampleFor(w: Int, h: Int, maxSide: Int): Int {
        if (w <= 0 || h <= 0 || maxSide <= 0) return 1
        var k = 1
        while (k < 64 && max(w, h) / k > maxSide) k *= 2
        return k
    }

    /**
     * 把水印画到图上，**不改动传入的 src**，返回一张新图。
     *
     * 调用方负责回收 src 和返回值：一张 3072 长边的 ARGB_8888 约 30MB，
     * 源 + 输出两份同时存在，低端机上不回收真的会 OOM。
     */
    fun render(
        src: Bitmap,
        cfg: WatermarkConfig,
        logo: Bitmap?,
        now: Calendar,
        loc: LocInfo?
    ): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        WatermarkRenderer.draw(
            c,
            src.width.toFloat(),
            src.height.toFloat(),
            cfg,
            logo,
            now,
            loc
        )
        return out
    }
}
