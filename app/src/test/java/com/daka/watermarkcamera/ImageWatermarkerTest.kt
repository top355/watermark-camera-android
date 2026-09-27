package com.daka.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * 相册图片加水印。
 *
 * 这里刻意**不测 decode 的成功路径** —— 那需要伪造一个带 EXIF 方向的真实 JPEG
 * 塞进 ContentResolver，测出来的是 Robolectric 的解码实现，不是我们的逻辑。
 * decode 只钉住一条：**读不了的时候返回 null 而不是抛异常**
 * （相册里什么都有：损坏文件、云盘占位图、别的 App 的私有 URI）。
 *
 * 真正要测的是 [ImageWatermarker.render]：尺寸不变、源图不被改动、
 * 水印只落在该落的那一半画面里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageWatermarkerTest {

    private val outDir = File("build/test-render").apply { mkdirs() }

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 27, 10, 6, 0)
    }
    private val loc = LocInfo(lat = 28.2379, lon = 112.9768, accuracy = 12, altitude = 46.0)

    private fun base() = WatermarkConfig(
        cardTitle = "打卡",
        unit = "",
        addr = "星海市云山区·风和苑",
        showCard = true,
        showTime = true,
        showDate = true,
        showAddr = true
    )

    /** 一张纯灰的"相册照片" */
    private fun gray(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }

    @Test
    fun 读不了的图_返回null而不是抛异常() {
        val ctx = RuntimeEnvironment.getApplication()
        val ghost = Uri.parse("content://media/external/images/media/987654321")
        assertNull("读不到就该安静地返回 null", ImageWatermarker.decode(ctx, ghost))
    }

    @Test
    fun 加水印后_尺寸不变_源图不被改动() {
        val src = gray(1600, 1200)
        val out = ImageWatermarker.render(src, base(), null, now, loc)

        assertEquals("尺寸必须原样保留", src.width, out.width)
        assertEquals("尺寸必须原样保留", src.height, out.height)
        assertEquals("源图不该被画花", Color.GRAY, src.getPixel(800, 300))

        val f = File(outDir, "L-album-photo.png")
        FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("相册加水印输出: ${f.absolutePath} (${f.length()} bytes)")
        assertTrue("PNG 应该非空", f.length() > 1000L)

        src.recycle()
        out.recycle()
    }

    /**
     * 水印落点必须对。
     *
     * 底部模式只应影响画面下方（遮罩上限是 62% 高），上面那截要一个像素都不动 ——
     * 这条断言能同时抓住两类事故：水印画到了顶部，或者渐变遮罩铺满了整张图。
     */
    @Test
    fun 水印只画在下方_上半部分原样保留() {
        val src = gray(1000, 1000)
        val out = ImageWatermarker.render(src, base(), null, now, loc)

        val topClean = (0 until out.width step 7).all { x -> out.getPixel(x, 10) == Color.GRAY }
        assertTrue("顶部区域不该被动过", topClean)

        val bottomTouched = (0 until out.width step 7)
            .count { x -> out.getPixel(x, out.height - 10) != Color.GRAY }
        assertTrue("底部必须有水印（遮罩至少要压暗它）", bottomTouched > 0)

        /* 中间偏上（约 30% 处）也应该是干净的 */
        val upperClean = (0 until out.width step 7).all { x ->
            out.getPixel(x, (out.height * 0.30f).toInt()) == Color.GRAY
        }
        assertTrue("画面 30% 处不该有遮罩", upperClean)

        src.recycle()
        out.recycle()
    }

    /** 和拍照那条路画的是同一套水印 —— 同一份配置在同样大小的画布上，结果必须一致 */
    @Test
    fun 与直接渲染的结果一致() {
        val w = 1200
        val h = 1600
        val src = gray(w, h)
        val out = ImageWatermarker.render(src, base(), null, now, loc)

        val manual = gray(w, h)
        WatermarkRenderer.draw(Canvas(manual), w.toFloat(), h.toFloat(), base(), null, now, loc)

        var diff = 0
        for (y in 0 until h step 13) {
            for (x in 0 until w step 13) {
                if (out.getPixel(x, y) != manual.getPixel(x, y)) diff++
            }
        }
        assertEquals("两条路必须画出同一个水印", 0, diff)

        src.recycle()
        out.recycle()
        manual.recycle()
    }
}
