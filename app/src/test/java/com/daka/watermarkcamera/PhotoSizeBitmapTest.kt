package com.daka.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [cropTo] / [cropRatio] 的**真实像素**验证。
 *
 * 为什么非得起 Robolectric：[PhotoSizeTest] 那套纯逻辑只算得出「裁剪框是多少」，
 * 算不出「到底裁的是哪一块」。`createBitmap` 的源区域给成从左上角起，
 * 所有尺寸断言照样全绿，而用户拍出来会发现每张照片都偏了一角 —— 这种错
 * 只能靠**在图上取点看颜色**才能抓住。
 *
 * 用的都是纯色块，所以断言是确定性的，不依赖抗锯齿的具体实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoSizeBitmapTest {

    /**
     * 一张 400×300 的图：蓝底 + 正中一块 200×200 的红方块。
     * 中心在 (200,150)，红方块覆盖 x∈[100,300)、y∈[50,250)。
     */
    private fun bullseye(): Bitmap {
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLUE)
        c.drawRect(100f, 50f, 300f, 250f, Paint().apply { color = Color.RED })
        return b
    }

    /**
     * 裁正方形时取的是**中间**，不是左上角。
     *
     * 红方块在正中，所以裁完的正方形应该四角是蓝、中心是红。
     * 如果源区域算成从 (0,0) 起，四角就会有一个是红的。
     */
    @Test
    fun 裁剪取的是正中_不是左上角() {
        val src = bullseye()
        val out = cropTo(src, 200, 200)

        assertEquals(200, out.width)
        assertEquals(200, out.height)
        assertEquals("左上角该是蓝底", Color.BLUE, out.getPixel(0, 0))
        assertEquals("右上角该是蓝底", Color.BLUE, out.getPixel(out.width - 1, 0))
        assertEquals("左下角该是蓝底", Color.BLUE, out.getPixel(0, out.height - 1))
        assertEquals("右下角该是蓝底", Color.BLUE, out.getPixel(out.width - 1, out.height - 1))
        assertEquals("正中间该是红方块", Color.RED, out.getPixel(100, 100))

        src.recycle()
        out.recycle()
    }

    /**
     * 16:9 从 4:3 里裁，砍掉的必须是**上下**。
     *
     * 上下各有一条醒目色带：裁完两边都不该再出现。
     * 同时左右两个边缘要还是蓝的 —— 说明宽度是整幅保留、没有被横向裁掉。
     */
    @Test
    fun 裁16比9_砍掉的是上下而不是左右() {
        val src = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        c.drawColor(Color.BLUE)
        c.drawRect(0f, 0f, 400f, 20f, Paint().apply { color = Color.RED })
        c.drawRect(0f, 280f, 400f, 300f, Paint().apply { color = Color.GREEN })

        val out = cropTo(src, 320, 180)
        assertEquals(320, out.width)
        assertEquals(180, out.height)

        assertEquals("顶部色带应该被裁掉了", Color.BLUE, out.getPixel(160, 0))
        assertEquals("底部色带应该被裁掉了", Color.BLUE, out.getPixel(160, out.height - 1))
        assertEquals("左边没被横向裁", Color.BLUE, out.getPixel(0, 90))
        assertEquals("右边没被横向裁", Color.BLUE, out.getPixel(out.width - 1, 90))

        src.recycle()
        out.recycle()
    }

    /** 1:1 从竖图里裁，砍掉的应该是**上下**（竖图比正方形高） */
    @Test
    fun 裁正方形从竖图_砍掉上下() {
        val src = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        c.drawColor(Color.BLUE)
        c.drawRect(0f, 0f, 200f, 40f, Paint().apply { color = Color.RED })
        c.drawRect(0f, 360f, 200f, 400f, Paint().apply { color = Color.GREEN })

        val out = cropTo(src, 100, 100)
        assertEquals(100, out.width)
        assertEquals(100, out.height)
        assertNotEquals("顶部色带不该还在", Color.RED, out.getPixel(50, 0))
        assertNotEquals("底部色带不该还在", Color.GREEN, out.getPixel(50, out.height - 1))

        src.recycle()
        out.recycle()
    }

    /** 输出尺寸必须精确等于目标 —— 差一像素在大图上无所谓，但它会让比例断言失效 */
    @Test
    fun 输出尺寸精确等于目标() {
        val src = Bitmap.createBitmap(4032, 3024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        for ((w, h) in listOf(1600 to 1200, 1920 to 1080, 1440 to 1440, 1152 to 864)) {
            val out = cropTo(src, w, h)
            assertEquals("目标 $w x $h", w, out.width)
            assertEquals("目标 $w x $h", h, out.height)
            out.recycle()
        }
        src.recycle()
    }

    /**
     * `cropTo` 的契约是**永远返回新图**，绝不复用入参。
     *
     * 尺寸已经相等时复用同一张能省一次拷贝，但那种"有时是同一张、有时不是"的返回值
     * 会被调用方多回收一次 —— 而 recycle 之后的位图再被画就是
     * `Canvas: trying to use a recycled bitmap`，崩在场面上离真因很远。
     */
    @Test
    fun 尺寸已相等时也不复用入参() {
        val src = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = cropTo(src, 1600, 1200)
        assertTrue("必须是另一张图", out !== src)
        assertTrue("源图必须还是可用的", !src.isRecycled)
        assertEquals("内容要一样", Color.BLUE, out.getPixel(800, 600))
        src.recycle()
        out.recycle()
    }

    /** `cropRatio` 只裁不缩：验完整链路里"预览"那一半不会顺手改分辨率 */
    @Test
    fun 只裁不缩_保持原分辨率() {
        val src = bullseye()
        val out = cropRatio(src, 1.0)
        assertEquals("1:1 从 400x300 裁出来是 300x300", 300, out.width)
        assertEquals(300, out.height)
        assertEquals("四角仍是蓝底", Color.BLUE, out.getPixel(1, 1))
        assertEquals("中心是红方块", Color.RED, out.getPixel(150, 150))
        src.recycle()
        out.recycle()
    }

    /**
     * 端到端对一次账：源 3200×2400 选 2M/4:3，最后应当**正好**得到 1600×1200。
     *
     * 这是"档位真的生效"的直接证据 —— 只测 planDecode 的话，
     * cropTo 里把 outW/outH 传反了都发现不了。
     */
    @Test
    fun 端到端_3200x2400出2M4比3得到1600x1200() {
        val src = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val ratio = PhotoRatio.R4_3
        val plan = planDecode(
            3200, 2400,
            quarterTurn = false,
            size = SaveSize.M2,
            ratio = ratio,
            /* 4:3 是固定档，屏幕比例传什么都没影响 */
            span = ratio.span(0.0)
        )!!
        /* 按计划应当采 1/2（1600×1200 已经正好是目标），这里直接用原图模拟解码结果 */
        assertEquals(2, plan.sample)

        val decoded = Bitmap.createScaledBitmap(src, 3200 / plan.sample, 2400 / plan.sample, true)
        val out = cropTo(decoded, plan.outW, plan.outH)

        assertEquals(1600, out.width)
        assertEquals(1200, out.height)
        assertEquals(Color.BLUE, out.getPixel(800, 600))

        src.recycle()
        decoded.recycle()
        out.recycle()
    }
}
