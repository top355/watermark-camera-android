package com.daka.watermarkcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Logo 存取的端到端验证。
 *
 * 这里对应一个真实故障：选完图提示「Logo 读取失败」。根因是旧实现**对同一个 URI 开了两次流**
 * （一次量尺寸、一次解码），而相册/云盘/第三方文件管理器给的流未必可重复打开 ——
 * 第二次拿到空流，`decodeStream` 安静地返回 null，日志里连异常都没有。
 *
 * 所以本测试用两种假 provider 把它钉死：
 * - [registerCounted] —— 能数出流到底被打开了几次，直接断言 **== 1**；
 * - [registerOneShot] —— 同一个流实例，关闭后再读就抛异常，把旧行为复现成失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoStoreTest {

    private lateinit var ctx: Context
    private val openCount = AtomicInteger(0)

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        LogoStore.clear(ctx)
        openCount.set(0)
    }

    /** 一次性流：关闭后再读就抛异常 */
    private class SingleUseStream(data: ByteArray) : ByteArrayInputStream(data) {
        private var closed = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("stream already consumed")
            return super.read(b, off, len)
        }

        override fun read(): Int {
            if (closed) throw IOException("stream already consumed")
            return super.read()
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawCircle(
            w / 2f, h / 2f, minOf(w, h) / 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
        )
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    /** 每次打开都给一条**新**流，并记录打开次数 */
    private fun registerCounted(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://daka.test/$name")
        shadowOf(ctx.contentResolver).registerInputStreamSupplier(uri) {
            openCount.incrementAndGet()
            SingleUseStream(bytes)
        }
        return uri
    }

    /** 每次打开给**同一个**流实例 —— 模拟只能打开一次的 provider */
    private fun registerOneShot(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://daka.test/$name")
        shadowOf(ctx.contentResolver).registerInputStream(uri, SingleUseStream(bytes))
        return uri
    }

    @Test
    fun 大图压到400宽_并且流只被打开一次() {
        val uri = registerCounted("big.png", pngBytes(1200, 800))
        val res = LogoStore.save(ctx, uri)

        assertTrue("保存应该成功，实际: $res", res is LogoStore.SaveResult.Ok)
        res as LogoStore.SaveResult.Ok
        println("保存结果: $res, openCount=${openCount.get()}")

        assertEquals("同一个 URI 只允许开一次流", 1, openCount.get())
        assertEquals("宽度应压到 400", 400, res.width)
        /* ImageDecoder 的 setTargetSize 语义是「至少这么大」，允许 1px 误差 */
        assertTrue("高度应保持 3:2 比例，实际 ${res.height}", res.height in 260..272)
        assertTrue("文件应该落盘", File(res.path).exists())

        val back = LogoStore.load(ctx, res.path)
        assertNotNull("应该能读回来", back)
        assertEquals(400, back!!.width)
    }

    @Test
    fun 只能打开一次的provider_也要能保存() {
        /* 这就是当初报「读取失败」的场景：第二次 openInputStream 拿到已耗尽的流 */
        val uri = registerOneShot("oneshot.png", pngBytes(1000, 700))
        val res = LogoStore.save(ctx, uri)
        println("一次性流保存结果: $res")
        assertTrue("一次性流必须也能保存成功，实际: $res", res is LogoStore.SaveResult.Ok)
    }

    @Test
    fun 小图不放大() {
        val uri = registerOneShot("small.png", pngBytes(200, 100))
        val res = LogoStore.save(ctx, uri)
        assertTrue(res is LogoStore.SaveResult.Ok)
        res as LogoStore.SaveResult.Ok
        assertEquals("比 400 小的图不该被放大", 200, res.width)
        assertEquals(100, res.height)
    }

    @Test
    fun 不是图片_失败且给出原因() {
        val uri = registerOneShot("junk.png", "this is definitely not an image".toByteArray())
        val res = LogoStore.save(ctx, uri)
        assertTrue("应该失败", res is LogoStore.SaveResult.Fail)
        res as LogoStore.SaveResult.Fail
        println("失败原因: ${res.reason}")
        assertTrue("原因不能是空的", res.reason.isNotBlank())
        assertTrue("应指出是格式问题，实际: ${res.reason}", res.reason.contains("格式"))
    }

    @Test
    fun 空文件_失败且给出原因() {
        val uri = registerOneShot("empty.png", ByteArray(0))
        val res = LogoStore.save(ctx, uri)
        assertTrue(res is LogoStore.SaveResult.Fail)
        println("失败原因: " + (res as LogoStore.SaveResult.Fail).reason)
    }

    @Test
    fun 打不开的URI_失败且给出原因() {
        val uri = Uri.parse("content://daka.test/not-registered.png")
        val res = LogoStore.save(ctx, uri)
        assertTrue("应该失败", res is LogoStore.SaveResult.Fail)
        val reason = (res as LogoStore.SaveResult.Fail).reason
        println("失败原因: $reason")
        assertTrue("原因不能是空的", reason.isNotBlank())
    }

    @Test
    fun 清除后读不回来() {
        val uri = registerOneShot("ok.png", pngBytes(600, 400))
        val res = LogoStore.save(ctx, uri)
        assertTrue(res is LogoStore.SaveResult.Ok)
        val path = (res as LogoStore.SaveResult.Ok).path
        assertNotNull(LogoStore.load(ctx, path))

        LogoStore.clear(ctx)
        assertNull("清掉之后不该还能读出来", LogoStore.load(ctx, path))
        assertFalse(LogoStore.file(ctx).exists())
    }

    @Test
    fun 重复保存会覆盖而不是堆积文件() {
        val a = LogoStore.save(ctx, registerOneShot("a.png", pngBytes(900, 600)))
        val b = LogoStore.save(ctx, registerOneShot("b.png", pngBytes(1500, 500)))
        assertTrue(a is LogoStore.SaveResult.Ok && b is LogoStore.SaveResult.Ok)
        assertEquals(
            "两次保存必须落在同一个文件上",
            (a as LogoStore.SaveResult.Ok).path,
            (b as LogoStore.SaveResult.Ok).path
        )
        assertEquals(400, b.width)
    }

    /* ==================== 内置 Logo（占位稿） ====================
     *
     * 内置 Logo 是**矢量**（`res/drawable/logo_placeholder.xml` ← `assets/logo-placeholder.svg`），
     * 但水印是画在软件 Canvas 上的，所以必须栅格化成 Bitmap。这里真的把它画出来，
     * 再按像素颜色确认「画出来的确实是那个灰蓝方块 + 白色快门环」，而不是一张空白图。
     *
     * 断言用「颜色占比」而不是精确像素 —— 抗锯齿会改边缘像素值，
     * 但填充色内部是大片纯色，(62,76,89) / (255,255,255) 一定有足够多的点。
     *
     * **这两条断言认的是填充色**。占位稿是中性灰蓝 `#3E4C59` + 白 `#FFFFFF`；
     * 换图形（尤其换成有品牌色的图）就得连这里一起改 —— 见 [LogoStore] 里
     * `DEFAULT_RES` 那段「换内置 Logo 要一起动的有四处」。
     */

    /** 统计与 [r],[g],[b] 三个分量各自相差不超过 [tol] 的不透明像素数 */
    private fun countNear(bmp: Bitmap, r: Int, g: Int, b: Int, tol: Int = 40): Int {
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 200) continue
                if (Math.abs(Color.red(p) - r) <= tol &&
                    Math.abs(Color.green(p) - g) <= tol &&
                    Math.abs(Color.blue(p) - b) <= tol
                ) n++
            }
        }
        return n
    }

    @Test
    fun 内置Logo_能栅格化_且保持声明比例() {
        val bmp = LogoStore.defaultLogo(ctx)
        assertNotNull("内置 Logo 必须能画出来（资源缺失 / 矢量不受支持都会是 null）", bmp)
        bmp!!
        assertEquals("宽度按约定压到 400", 400, bmp.width)
        /* 占位稿声明 340×275（viewport 2720×2200 同比例）→ 400 宽对应 323.5 高，允许 1px 取整差 */
        assertTrue("应保持 340:275 的比例，实际 ${bmp.width}×${bmp.height}", bmp.height in 322..325)
        println("内置 Logo 尺寸: ${bmp.width}×${bmp.height}")
    }

    @Test
    fun 内置Logo_画出来是灰蓝主体加白色快门环_不是空白或纯白() {
        val bmp = LogoStore.defaultLogo(ctx)!!
        val total = bmp.width * bmp.height
        val slab = countNear(bmp, 62, 76, 89)
        val white = countNear(bmp, 255, 255, 255)
        println("内置 Logo 像素占比: 灰蓝=${"%.2f".format(slab * 100.0 / total)}% " +
            "白=${"%.2f".format(white * 100.0 / total)}%")

        assertTrue("灰蓝主体应占相当比例，实际 $slab/$total", slab * 100.0 / total > 45.0)
        assertTrue("白色快门环不能丢，实际 $white/$total", white * 100.0 / total > 5.0)
        /* 背景必须是透明的 —— 否则贴到照片上会是一块白方块 */
        var opaque = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (Color.alpha(bmp.getPixel(x, y)) > 200) opaque++
        }
        assertTrue(
            "Logo 应保留透明底（不透明像素占比 ${"%.1f".format(opaque * 100.0 / total)}% 应明显小于 100%）",
            opaque * 100.0 / total < 95.0
        )
    }

    @Test
    fun 内置Logo_栅格化结果会复用_不是每次重画() {
        assertSame(
            "同一进程内应复用同一张位图 —— 主界面每次 onResume 都要一次",
            LogoStore.defaultLogo(ctx),
            LogoStore.defaultLogo(ctx)
        )
    }

    @Test
    fun 选Logo的优先级_自选大于内置大于无() {
        val c = WatermarkConfig()
        assertTrue("内置 Logo 应默认开启 —— 否则卡片右侧永远是空的", c.useBuiltinLogo)

        val custom = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertSame("有自选时必须用自选的", custom, LogoStore.pick(ctx, c, custom))

        val fallback = LogoStore.pick(ctx, c, null)
        assertNotNull("没自选时应兜到内置", fallback)
        assertEquals(400, fallback!!.width)

        assertNull(
            "关掉内置又没自选 → 没有 Logo，渲染器回落成卡片右侧的单位文字",
            LogoStore.pick(ctx, c.copy(useBuiltinLogo = false), null)
        )
    }

    @Test
    fun 内置Logo开关_能存能读() {
        assertTrue("默认应开启", Prefs.load(ctx).useBuiltinLogo)
        val c = Prefs.load(ctx)
        Prefs.save(ctx, c.copy(useBuiltinLogo = false))
        assertFalse("关掉之后读回来也该是关的", Prefs.load(ctx).useBuiltinLogo)
    }
}
