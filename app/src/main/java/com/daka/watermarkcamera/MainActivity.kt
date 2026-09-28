package com.daka.watermarkcamera

import android.Manifest
import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.daka.watermarkcamera.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    /** 闪光灯能力要从 CameraInfo 问，ImageCapture 上没有 hasFlashUnit() */
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private var cfg = WatermarkConfig()
    private var logo: Bitmap? = null

    private var loc: LocInfo? = null
    private var locError: String? = null

    /*
     * 自动反查地名的节流状态。
     * 定位是每 2~5 秒回调一次的，不能每次都去调 Geocoder ——
     * 所以只在「首次拿到定位」和「首次拿到高精度定位」各查一次，总共最多两次。
     */
    private var autoAddrCount = 0
    private var autoAddrAcc = Int.MAX_VALUE
    private var autoAddrBusy = false

    /**
     * 闪光灯档位：见 [FLASH_OFF] / [FLASH_SHOT] / [FLASH_TORCH]。
     * 必须是**字段**而不是临时变量：build ImageCapture 时要按它写进 session 配置
     * （见 [startCamera]），运行时的 setFlashMode 只是补充。
     */
    private var flashMode = FLASH_OFF

    /** 传感器画面在「竖屏」下的宽高比；横屏取其倒数。由 resolveAspect() 填充 */
    private var sensorAspect = 0f

    /* ------------------------- 放大倍数 ------------------------- */

    private lateinit var zoomCtl: ZoomBar

    /**
     * 当前倍数。**只在内存里，不落盘。**
     *
     * 闪光灯档位是落盘的（那是个"设置"：我就要补光），倍数不是 —— 它是**取景**。
     * 落到盘上会变成：昨天为了拍远处那块铭牌拉到 8x，今天打开相机对准人，
     * 画面糊成一团而屏幕上没有任何东西提示"为什么"。
     * 主流相机（Google / 三星）开相机也一律回 1x。
     *
     * 但**内存里必须留住**：切前后摄、开关闪光灯都会重建 session，新 session 倍数归 1，
     * 不记住的话"换个摄像头刚才的 3x 就没了"。
     */
    private var zoomRatio = 1f

    /** 相机报的可用区间，用来夹 [zoomRatio]。默认 1..1 = 不能变焦 */
    private var zoomMin = 1f
    private var zoomMax = 1f

    /**
     * 下一次 zoomState 回调时要不要把 [zoomRatio] 重新推给相机。
     *
     * 刚 bind 完必为 true：新 session 的倍数一定是 1，不推下去用户选的档就白选了。
     * 推完立刻置 false —— 否则用户往后每捏一下都会被这里拽回旧值。
     */
    private var zoomNeedsApply = false

    /**
     * 相机自己也会改倍数（捏合是我们发的，但有些 ROM 在暗光下会自己往里裁），
     * 所以**高亮一律以这个回调为准**，而不是以我们发出去的值。
     *
     * 见 [ZoomBar.markSelected]：相机报的值不在任何一档上时，一个都不高亮。
     */
    private val zoomObserver = Observer<ZoomState> { zs ->
        zoomMin = if (zs.minZoomRatio > 0f && !zs.minZoomRatio.isNaN()) zs.minZoomRatio else 1f
        zoomMax = if (zs.maxZoomRatio >= zoomMin && !zs.maxZoomRatio.isNaN()) {
            zs.maxZoomRatio
        } else {
            zoomMin
        }
        zoomCtl.bind(zoomMin, zoomMax)

        if (zoomNeedsApply) {
            zoomNeedsApply = false
            setZoom(zoomRatio)
        } else {
            /* 相机是权威：它夹过、或用户捏过，都按它报的来 */
            zoomRatio = zs.zoomRatio.coerceIn(zoomMin, zoomMax)
            zoomCtl.markSelected(zs.zoomRatio)
        }
    }

    /**
     * 预览区上的捏合缩放。
     *
     * 逐次乘 `scaleFactor` 而不是「起手倍数 × 累计系数」：`scaleFactor` 是**相对上一次
     * 回调**的增量比，用起手倍数去乘会得到指数级放大（捏一下冲到上限）。
     */
    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoomRatio * d.scaleFactor)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                /*
                 * 松手时吸一下：小幅偏差归到最近的档（捏歪到 1.05x 就该回 1x），
                 * 差得多的原样留着 —— 见 Zoom.snap
                 */
                setZoom(Zoom.snap(zoomCtl.presets, zoomRatio))
            }
        })
    }

    private var shotCount = 0
    private var lastUri: Uri? = null
    private var busy = false

    /**
     * 相册里选中的那张图。只记 URI、**不缓存位图**：一张 3072 长边的 ARGB_8888
     * 就是 30MB，用户在相册里来回换几张就能把内存顶满。
     * 全尺寸解码推迟到"点保存"那一刻，用完立刻回收 ——
     * 代价是保存时多花几百毫秒，换来的是挑图全程只占一张缩略图的内存。
     */
    private var pendingUri: Uri? = null

    /** 上次已经提示过的手动时间值。只在它变化时弹 Snackbar，免得每次回界面都弹一遍 */
    private var hintedManualMs = 0L

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            loc = LocInfo(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = if (location.hasAccuracy()) Math.round(location.accuracy) else 0,
                altitude = if (location.hasAltitude()) location.altitude else null
            )
            locError = null
            paintGps()
            maybeAutoAddress()
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                toast("没有相机权限，无法拍照")
            }
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
                locError = getString(R.string.gps_denied)
                paintGps()
            } else {
                startLocation()
            }
        }

    /**
     * 从相册选一张图加水印。
     *
     * 用 PickVisualMedia 而不是 GET_CONTENT：Android 13+ 上它走系统照片选择器，
     * **不需要任何存储权限**；老系统自动回落到 GET_CONTENT，代码只有一份。
     */
    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onPickedImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()
        wireUi()

        /* 必须在 startCamera() 之前读出来：ImageCapture 构建时要按它写进 session 配置 */
        flashMode = Prefs.flashMode(this)

        if (hasCameraPermission()) {
            startCamera()
            startLocation()
        } else {
            permLauncher.launch(neededPermissions())
        }
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 竖屏时上下留出状态栏/导航栏；横屏时状态栏与导航栏可能跑到左右两侧，
     * 所以改成给左右留位 —— 否则右侧那排按钮会被导航栏压住点不到。
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val d = resources.displayMetrics.density
            val v = (10 * d).toInt()
            val h = (14 * d).toInt()
            if (isLandscape) {
                binding.topBar.updatePadding(top = v, left = sb.left + h, bottom = 0)
                binding.bottomBar.updatePadding(top = 0, bottom = h, right = sb.right + h)
            } else {
                binding.topBar.updatePadding(top = sb.top + v, left = h, bottom = 0)
                binding.bottomBar.updatePadding(top = 0, bottom = sb.bottom + h, right = h)
            }
            /*
             * 倍数行也要躲系统栏：横屏时导航栏可能竖在右边，不躲的话
             * 最右那一档会被压在导航栏底下点不到。16dp 是 XML 里定的视觉边距，
             * 这里要**加上**系统栏而不是替换掉它（updatePadding 只改传进去的那几个边）。
             */
            val zp = (16 * d).toInt()
            binding.zoomScroll.updatePadding(left = sb.left + zp, right = sb.right + zp)
            insets
        }
    }

    private fun wireUi() {
        /* 档位是相机能力的函数，所以整行交给 ZoomBar 在代码里建，布局里只留容器 */
        zoomCtl = ZoomBar(this, binding.zoomBar, binding.zoomScroll)
        zoomCtl.onPick = { setZoom(it) }

        /*
         * 预览区自己吃掉所有触摸 —— 捏合需要**完整的手势流**：
         * DOWN 若不消费，后面的 MOVE / POINTER_DOWN 根本不会派发到这个 View 上，
         * 表现就是"捏合时灵时不灵"。
         *
         * 代价是预览空白处的点击不再往下传。那里的点击本来就什么也不做
         * （水印浮层没命中的点击落到这里，原来也是"没反应"），所以不吃亏。
         * 将来要做点屏对焦，请**加进这一个 listener**，不要再 setOnTouchListener 一次：
         * 后者会把这里整个顶掉，而且不会报任何错。
         */
        binding.previewView.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            true
        }

        binding.shutter.setOnClickListener { takePhoto() }
        binding.btnFlip.setOnClickListener { flipCamera() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.thumb.setOnClickListener {
            lastUri?.let { viewPhoto(it) } ?: toast("还没有拍过照片")
        }
        /* 拍照界面直接改地点 */
        binding.gpsPill.setOnClickListener { editAddress() }

        /*
         * 点水印**上的字**就地改：地点行 → 地点编辑器，日期行 / 卡片里那串时间 → 日期时间滚轮。
         *
         * 顶栏那个胶囊仍然保留（两条路都要，用户不必先知道"能点水印"）。
         * 点空白处浮层不放行之外的任何东西 —— 见 WatermarkOverlayView.onTouchEvent，
         * 那里没命中就直接返回 false，事件继续落到预览区做对焦。
         */
        binding.overlay.onTap = { target ->
            when (target) {
                WatermarkRenderer.TapTarget.ADDR -> editAddress()
                WatermarkRenderer.TapTarget.DATETIME -> editDateTime()
                WatermarkRenderer.TapTarget.NONE -> Unit
            }
        }

        binding.btnFlash.setOnClickListener { toggleFlash() }
        /* 相册里的图也能加水印 —— 补拍、事后补录的场景全靠它 */
        /*
         * 这里有个 `?.`：ViewBinding 把 btnGallery 生成了**可空字段**
         * （生成代码里它没有 missingId 检查，别的按钮都有），而布局本身是对的。
         * 不猜原因、也不让它静默失效 —— 用 `BottomBarLayoutTest.相册入口真的存在`
         * 盯着它：真拿不到的话测试先红，而不是等用户点了没反应才发现。
         */
        binding.btnGallery?.setOnClickListener { pickPhotoNow() }

        /* 分屏 / 折叠 / 旋转导致预览区尺寸变化时，重算水印浮层的贴合矩形 */
        binding.previewBox.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) layoutOverlay()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        /*
         * 因为声明了 configChanges，Activity 不会重建 —— 也就没人帮我们更新相机方向。
         * 不显式改 targetRotation 的话，横屏拍出的照片会是横躺的。
         */
        val r = displayRotation()
        previewUseCase?.targetRotation = r
        imageCapture?.targetRotation = r

        applyInsets()
        binding.previewBox.post {
            resolveAspect()
            layoutOverlay()
        }
    }

    private fun displayRotation(): Int =
        binding.previewView.display?.rotation ?: Surface.ROTATION_0

    /* ------------------------- 相机 ------------------------- */

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(displayRotation())
                    .build()
                preview.surfaceProvider = binding.previewView.surfaceProvider

                val capture = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(displayRotation())
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    /*
                     * 闪光灯在**构建 use case 时就带上**，而不是只靠 bind 之后的
                     * imageCapture.flashMode = …。
                     *
                     * 这是「开了闪光灯却没闪」的关键修复：部分 ROM / 兼容层只在
                     * session 配置阶段读一次闪光灯参数，之后单独 setFlashMode
                     * 不触发 session 重建，参数就白设了（图标亮了、灯不亮）。
                     */
                    .setFlashMode(
                        if (flashMode == FLASH_SHOT) {
                            ImageCapture.FLASH_MODE_ON
                        } else {
                            ImageCapture.FLASH_MODE_OFF
                        }
                    )
                    .build()
                imageCapture = capture
                previewUseCase = preview

                provider.unbindAll()
                /* 记住 Camera：只有 CameraInfo 上有 hasFlashUnit()，ImageCapture 上没有 */
                val cam = provider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview,
                    capture
                )
                camera = cam
                applyFlashState()
                observeZoom(cam.cameraInfo)
                resolveAspect()
            } catch (e: Exception) {
                toast("相机启动失败：" + (e.message ?: "未知错误"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    /* ------------------------- 闪光灯 ------------------------- */

    /**
     * 单击循环三档：关 → 拍照时闪 → 常亮补光 → 关。
     *
     * 不做「自动」档：打卡场景要的是确定性，自动档什么时候闪说不准，
     * 拍完才发现没闪就晚了。
     *
     * 不是简单开关、而是三档循环，是为了解决「开了灯不亮却无从判断」：
     * 常亮档走 [androidx.camera.core.CameraControl.enableTorch]，和拍照闪光
     * 不是同一条底层路径。它能亮 → 硬件与权限没问题，且真的能当补光用；
     * 它也亮不了 → 就是这台设备/这个摄像头确实没有可用的闪光灯。
     */
    private fun toggleFlash() {
        /* 正在合成上一张时别重启相机，会把拍照打断 */
        if (busy) return

        flashMode = when (flashMode) {
            FLASH_OFF -> FLASH_SHOT
            FLASH_SHOT -> FLASH_TORCH
            else -> FLASH_OFF
        }
        Prefs.setFlashMode(this, flashMode)

        applyFlashState()
        /*
         * 重建一次 session。闪光灯档位是写进 ImageCapture 的构建参数里的，
         * 只有重新 bind 才能保证底层真的收到它 —— 这正是「点了开、图标亮了、
         * 拍照却不闪」的根因所在（见 startCamera）。预览会黑一下，是刻意的。
         */
        startCamera()

        toast(
            when (flashMode) {
                FLASH_SHOT -> getString(R.string.flash_shot)
                FLASH_TORCH -> getString(R.string.flash_torch)
                else -> getString(R.string.flash_off)
            }
        )
    }

    /**
     * 把 [flashMode] 应用到相机上。
     *
     * 每次 startCamera 之后都要重新应用一遍：切前后摄、重建 session 都会建新的
     * ImageCapture 实例，不重设就退回默认档（关），表现为「切一下摄像头闪光灯就自己关了」。
     *
     * 能力探测只能问 [androidx.camera.core.CameraInfo].hasFlashUnit() ——
     * ImageCapture 上根本没有这个方法。
     */
    private fun applyFlashState() {
        val ic = imageCapture ?: return
        val cam = camera
        val has = cam?.cameraInfo?.hasFlashUnit() == true

        binding.btnFlash.isEnabled = has
        binding.btnFlash.alpha = if (has) 1f else 0.35f

        if (!has) {
            cam?.cameraControl?.enableTorch(false)
            binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
            binding.btnFlash.contentDescription = getString(R.string.flash_unsupported)
            return
        }

        /*
         * torch（常亮）与 flashMode（拍照时闪）是互斥的：一个开着，另一个就会被忽略。
         * 所以每次都要**成对**设置 —— 只设其中一个，从常亮档退回关档时灯会一直亮着。
         */
        cam?.cameraControl?.enableTorch(flashMode == FLASH_TORCH)
        ic.flashMode = if (flashMode == FLASH_SHOT) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }

        binding.btnFlash.setImageResource(
            when (flashMode) {
                FLASH_SHOT -> R.drawable.ic_flash_on
                FLASH_TORCH -> R.drawable.ic_torch
                else -> R.drawable.ic_flash_off
            }
        )
        binding.btnFlash.contentDescription = getString(R.string.flash_toggle)
    }

    /* ------------------------- 放大倍数 ------------------------- */

    /**
     * 订阅这台相机的倍数能力。
     *
     * 必须先 removeObservers 再 observe：`CameraInfo.zoomState` 在多次 bind 之间
     * **可能是同一个 LiveData 实例**，重复 observe 会让回调走两遍、档位条白建两次。
     * 换摄像头时旧实例被丢掉，挂在上面的观察者随之失效，不用管。
     */
    private fun observeZoom(info: CameraInfo) {
        zoomNeedsApply = true
        info.zoomState.removeObservers(this)
        info.zoomState.observe(this, zoomObserver)
    }

    /**
     * 改倍数。点档位、捏合、重建 session 后重推 —— 全部走这里，
     * 于是「夹进合法区间」和「同步高亮」只有一份实现。
     *
     * 前摄经常只支持 1x：后摄拉到 8x 再切前摄，不夹一下会直接抛
     * IllegalArgumentException。
     */
    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        if (ratio.isNaN()) return
        val r = ratio.coerceIn(zoomMin, zoomMax)
        zoomRatio = r

        /*
         * 先按用户的意思亮起来，不等相机回调 —— 从点击到回亮隔着一帧以上，
         * 手感上就是"点了没反应"。相机若否掉它，下面的回调会把高亮拨回真实值。
         */
        zoomCtl.markSelected(r)

        try {
            val f = cam.cameraControl.setZoomRatio(r)
            f.addListener({
                try {
                    f.get()
                } catch (t: Throwable) {
                    /*
                     * 个别 ROM 在切摄像头那一瞬会拒绝改倍数。不值得为此崩掉，
                     * 但界面不能继续显示一个并不存在的倍数。
                     */
                    zoomCtl.markSelected(cam.cameraInfo.zoomState.value?.zoomRatio ?: r)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            /* 同步抛出（区间不对、相机已解绑）也一样：这是取景辅助功能，不该能崩掉相机 */
        }
    }

    /**
     * 相机分辨率只有在 bind 之后才拿得到。拿不到就先按 4:3 兜底。
     * resolutionInfo 给的是**传感器**分辨率（永远是横向的，如 1440x1080），
     * 屏幕上是竖着显示还是横着显示由设备方向决定，见 [PreviewGeometry]。
     */
    private fun resolveAspect(tries: Int = 0) {
        val info = imageCapture?.resolutionInfo
        if (info != null) {
            val r = info.resolution
            sensorAspect = PreviewGeometry.sensorAspect(r.width, r.height)
            layoutOverlay()
        } else if (tries < 12) {
            binding.previewBox.postDelayed({ resolveAspect(tries + 1) }, 150L)
        }
    }

    /** 把水印浮层摆到「相机画面实际显示的那块矩形」上，保证所见即所得 */
    private fun layoutOverlay() {
        val cw = binding.previewBox.width
        val ch = binding.previewBox.height
        if (cw <= 0 || ch <= 0) return

        val aspect = PreviewGeometry.displayAspect(sensorAspect, isLandscape)
        val size = PreviewGeometry.fitRect(cw, ch, aspect)
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) return

        val lp = binding.overlay.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.CENTER
        binding.overlay.layoutParams = lp
    }

    override fun onResume() {
        super.onResume()
        cfg = Prefs.load(this)
        /* 防伪码要把机型算进去：同地点同一分钟的两台设备不该得到同一个码 */
        cfg.deviceTag = Build.MODEL ?: ""
        val customLogo = LogoStore.load(this, cfg.logoPath)
        /* Logo 文件没了就清掉陈旧路径，否则会一直"设了 Logo 但照片上没有" */
        if (customLogo == null && !cfg.logoPath.isNullOrBlank()) {
            cfg.logoPath = null
            Prefs.save(this, cfg)
        }
        /* 用户自选 > 内置占位 > 无（无则卡片右侧回落成单位文字） */
        logo = LogoStore.pick(this, cfg, customLogo)
        binding.overlay.config = cfg
        binding.overlay.logo = logo
        binding.overlay.loc = loc
        shotCount = Prefs.shotCount(this)
        binding.cntPill.text = "已拍 $shotCount 张"
        paintGps()

        flashMode = Prefs.flashMode(this)
        applyFlashState()

        /*
         * 锁了水印时间就提示一句。它是个"看不见的开关"：用户上次设成 10:06，
         * 今天打开相机拍出来还是 10:06 —— 没有提示的话第一反应是"这 App 坏了"。
         * 只在时间值变化时弹，免得每次回到界面都弹一遍。
         */
        if (cfg.manualTime) {
            if (cfg.manualTimeMs != hintedManualMs) {
                hintedManualMs = cfg.manualTimeMs
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.manual_time_locked,
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                            .format(Date(cfg.now().timeInMillis))
                    ),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.btn_pick_time) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }.show()
            }
        } else {
            hintedManualMs = 0L
        }

        /* 从设置页回来允许再自动反查一次（用户可能刚打开「自动获取地点」） */
        autoAddrCount = 0
        autoAddrAcc = Int.MAX_VALUE

        binding.previewBox.post {
            resolveAspect()
            layoutOverlay()
        }
    }

    override fun onPause() {
        super.onPause()
        /*
         * 常亮档必须在离开界面时关掉 —— 否则切后台、锁屏之后手电筒还亮着，
         * 既耗电又尴尬（用户只会以为手机坏了）。回到前台由 onResume 恢复。
         * 拍照时闪那一档（flashMode）不用管，它本来只在快门瞬间起作用。
         */
        camera?.cameraControl?.enableTorch(false)
    }

    /* --------------- 拍照界面直接改地点 / 日期时间（弹出式） --------------- */

    /**
     * 地点的弹出式编辑器。顶栏那颗胶囊和**水印上的地点行**都会走到这里。
     *
     * 带常用地点下拉（填过的自动记住），以及「用当前定位反查地名」。
     * 壳用的是 [CardPopup] 而不是 AlertDialog：见那边的注释 ——
     * 主要原因是它和日期时间编辑器要长得一模一样、且要能点遮罩关闭。
     */
    private fun editAddress() {
        val view = layoutInflater.inflate(R.layout.dialog_addr, null)
        val et = view.findViewById<AutoCompleteTextView>(R.id.etAddr)
        val tvState = view.findViewById<TextView>(R.id.tvAddrState)
        val btnGeo = view.findViewById<MaterialButton>(R.id.btnGeoLookup)

        et.setText(cfg.addr)
        et.setSelection(et.text?.length ?: 0)

        /*
         * 这次编辑的结果到底是「用户手输」还是「反查填入」，决定以后要不要自动覆盖它。
         * suppress 用来区分：反查回填也是 setText，会触发 watcher。
         */
        var fromGeo = false
        var suppress = false
        et.doAfterTextChanged { if (!suppress) fromGeo = false }

        val history = Prefs.addresses(this)
        et.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, history))
        if (history.isNotEmpty()) {
            et.setOnClickListener { et.showDropDown() }
        }

        fun refreshState() {
            tvState.text = when {
                loc != null -> getString(R.string.addr_geo_ready, loc!!.accuracy)
                locError != null -> getString(R.string.addr_geo_denied)
                else -> getString(R.string.addr_edit_helper)
            }
        }
        refreshState()

        val popup = CardPopup(this)
            .title(getString(R.string.addr_edit_title))
            .body(view)
            /* 有输入框，必须自己躲键盘 —— 见 CardPopup.avoidIme 的注释 */
            .avoidIme()

        /* 「清空」不关窗 —— 用户多半是想重填，关掉了还得再点一遍 */
        popup.action(getString(R.string.btn_clear)) {
            et.setText("")
            et.requestFocus()
            refreshState()
        }
        popup.action(getString(R.string.btn_done), primary = true) {
            applyAddress(et.text?.toString() ?: "", fromGeo)
            it.dismiss()
        }

        /* 「反查」同样不关窗：回填后还要让用户确认/微调 */
        btnGeo.setOnClickListener {
            val l = loc
            if (l == null) {
                tvState.text = getString(R.string.geo_need_gps)
                return@setOnClickListener
            }
            btnGeo.isEnabled = false
            tvState.text = getString(R.string.geo_locating_hint)
            lifecycleScope.launch {
                val name = Geo.reverse(this@MainActivity, l.lat, l.lon)
                btnGeo.isEnabled = true
                if (name.isNullOrBlank()) {
                    tvState.text = getString(R.string.geo_no_result)
                } else {
                    suppress = true
                    et.setText(name)
                    suppress = false
                    fromGeo = true
                    et.setSelection(name.length)
                    refreshState()
                }
            }
        }

        popup.show()
    }

    /**
     * 日期时间的弹出式滚轮。**上面日期（年/月/日）、下面时间（时/分/秒）**，
     * 水印上的日期行和卡片里那串时间都会走到这里。
     *
     * 一屏六条滚轮，而不是系统那套「先 DatePickerDialog、再 TimePickerDialog」两步走：
     * 用户要的是"一眼看全、直接拨"，系统那两步要过两道窗点两次确定。
     * 设置页里那份两步走的入口保留着（它是给"顺手改一下"用的，不必拨滚轮）。
     *
     * 选完的效果**立刻落到水印上**（保存后 config.manualTime 一开，
     * 预览与出图都按这个时刻走），所以标题里那行就是"印出来会是什么样"。
     */
    private fun editDateTime() {
        val view = layoutInflater.inflate(R.layout.popup_datetime, null)
        val npY = view.findViewById<NumberPicker>(R.id.npYear)
        val npMo = view.findViewById<NumberPicker>(R.id.npMonth)
        val npD = view.findViewById<NumberPicker>(R.id.npDay)
        val npH = view.findViewById<NumberPicker>(R.id.npHour)
        val npMi = view.findViewById<NumberPicker>(R.id.npMinute)
        val npS = view.findViewById<NumberPicker>(R.id.npSecond)
        val btnUseNow = view.findViewById<MaterialButton>(R.id.btnUseNow)

        /* 起点：已经锁了就用锁住那一刻，没锁就用现在 */
        val base = cfg.now()

        npY.minValue = 2000
        npY.maxValue = 2099
        npMo.minValue = 1
        npMo.maxValue = 12
        npD.minValue = 1
        npD.maxValue = 31
        npH.minValue = 0
        npH.maxValue = 23
        npMi.minValue = 0
        npMi.maxValue = 59
        npS.minValue = 0
        npS.maxValue = 59

        /* 月/日/时/分/秒 补零成两位，滚轮才对得齐（"9" 和 "12" 混排看着是歪的） */
        npMo.setFormatter { String.format(Locale.CHINA, "%02d", it) }
        npD.setFormatter { String.format(Locale.CHINA, "%02d", it) }
        npH.setFormatter { String.format(Locale.CHINA, "%02d", it) }
        npMi.setFormatter { String.format(Locale.CHINA, "%02d", it) }
        npS.setFormatter { String.format(Locale.CHINA, "%02d", it) }

        for (np in listOf(npY, npMo, npD, npH, npMi, npS)) {
            /*
             * 关掉"点一下就弹出软键盘改数字"：滚轮本来就是拨的，
             * 弹一次键盘会把整张卡顶上去，还得先收键盘才能接着拨。
             */
            np.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            np.setWrapSelectorWheel(false)
        }
        npY.value = base.get(Calendar.YEAR)
        npMo.value = base.get(Calendar.MONTH) + 1
        npD.value = base.get(Calendar.DAY_OF_MONTH)
        npH.value = base.get(Calendar.HOUR_OF_DAY)
        npMi.value = base.get(Calendar.MINUTE)
        npS.value = base.get(Calendar.SECOND)

        var fmtSec = cfg.showSec
        var suppressDay = false

        fun pickCalendar(): Calendar {
            val c = Calendar.getInstance()
            c.set(
                npY.value, npMo.value - 1, npD.value,
                npH.value, npMi.value, npS.value
            )
            c.set(Calendar.MILLISECOND, 0)
            return c
        }

        /**
         * 2 月的 30 号这种日期不该存在。改年月时把当月的最大天数重新钉一遍，
         * 超了就把日往回拉 —— 否则滚轮会停在一个 Calendar 会静默进位到 3 月的值上，
         * 用户看到「2月30日」却印出「3月2日」。
         */
        fun clampDay() {
            val c = Calendar.getInstance()
            c.set(npY.value, npMo.value - 1, 1)
            val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
            suppressDay = true
            npD.maxValue = max
            if (npD.value > max) npD.value = max
            suppressDay = false
        }

        val popup = CardPopup(this).body(view)

        fun refresh() {
            val c = pickCalendar()
            val t = if (fmtSec) {
                String.format(Locale.CHINA, "%02d:%02d:%02d", npH.value, npMi.value, npS.value)
            } else {
                String.format(Locale.CHINA, "%02d:%02d", npH.value, npMi.value)
            }
            popup.title(WatermarkRenderer.dateText(c))
            popup.subtitle(getString(R.string.watermark_will_print, "${WatermarkRenderer.dateText(c)}  $t"))
            btnUseNow.visibility = if (cfg.manualTime) View.VISIBLE else View.GONE
        }

        val listener = NumberPicker.OnValueChangeListener { _, _, _ ->
            if (!suppressDay) clampDay()
            refresh()
        }
        for (np in listOf(npY, npMo, npD, npH, npMi, npS)) np.setOnValueChangedListener(listener)
        clampDay()
        refresh()

        /*
         * 这个按钮的文字要跟着当前格式变（"时:分" ↔ "时:分:秒"），
         * 所以得先有个能引用到自己的名字 —— 用局部 lateinit var，别在初始化式里自引用。
         */
        lateinit var btnFmt: MaterialButton
        btnFmt = popup.action(
            getString(if (fmtSec) R.string.fmt_time_format_on else R.string.fmt_time_format_off)
        ) {
            fmtSec = !fmtSec
            btnFmt.text =
                getString(if (fmtSec) R.string.fmt_time_format_on else R.string.fmt_time_format_off)
            refresh()
        }

        popup.action(getString(R.string.btn_save), primary = true) {
            val c = pickCalendar()
            cfg.manualTime = true
            cfg.manualTimeMs = c.timeInMillis
            cfg.showSec = fmtSec
            Prefs.save(this, cfg)
            binding.overlay.config = cfg
            binding.overlay.invalidate()
            /*
             * 顺手把「时间已锁」那条提醒标记成"已经说过了"：
             * 用户刚刚亲手拨的时间，回到预览再弹一句"时间已锁定"是多余的。
             */
            hintedManualMs = cfg.manualTimeMs
            toast(getString(R.string.datetime_saved, WatermarkRenderer.dateText(c) + "  " + WatermarkRenderer.timeText(cfg, c)))
            it.dismiss()
        }

        /* 「回到当前时间」= 关掉手动锁，水印重新跟手机时钟走 */
        btnUseNow.setOnClickListener {
            cfg.manualTime = false
            Prefs.save(this, cfg)
            binding.overlay.config = cfg
            binding.overlay.invalidate()
            hintedManualMs = 0L
            toast(getString(R.string.datetime_now_restored))
            popup.dismiss()
        }

        popup.show()
    }

    /**
     * 保存地点并立刻生效。用户既然主动填了地点，
     * 就顺手把「显示地点」打开 —— 否则填了却看不见，只会让人以为没生效。
     *
     * 存之前过一遍 [Addr.compact]：短名/自定义名会原样返回，
     * 只有「省市区+街道+楼栋」这种长串才会被削成 `市+区县·小区`。
     *
     * @param fromGeo 这次的值是「反查填入」的。反查来的仍算自动模式（以后还会跟着定位更新），
     *                手输才算用户的固定选择。于是「清空」就等于「回到自动获取地点」。
     */
    private fun applyAddress(raw: String, fromGeo: Boolean = false) {
        val v = Addr.compact(raw)
        cfg.addr = v
        if (v.isNotEmpty()) {
            cfg.showAddr = true
            Prefs.rememberAddress(this, v)
        }
        Prefs.setAddrManual(this, v.isNotEmpty() && !fromGeo)
        Prefs.save(this, cfg)
        binding.overlay.config = cfg
        paintGps()
        toast(if (v.isEmpty()) getString(R.string.addr_cleared) else getString(R.string.addr_saved))
    }

    /* ------------------------- 定位 ------------------------- */

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            locError = getString(R.string.gps_denied)
            paintGps()
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                @Suppress("DEPRECATION")
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, locListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                @Suppress("DEPRECATION")
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, locListener)
            }
            @Suppress("DEPRECATION")
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { locListener.onLocationChanged(it) }
        } catch (e: SecurityException) {
            locError = getString(R.string.gps_denied)
            paintGps()
        } catch (e: Exception) {
            locError = getString(R.string.gps_bad)
            paintGps()
        }
    }

    /**
     * 定位到了就自动把地名填上 —— 不用每次手点「用当前定位反查」。
     *
     * 三条约束，缺一条都会出问题：
     * 1. **用户自己填过就不许覆盖**（[Prefs.addrManual]）。清空即回到自动模式。
     * 2. **总共最多自动查两次**：首次拿到定位 + 首次高精度（≤100m）。
     *    定位每 2~5 秒回调一次，不节流会把 Geocoder 拖死。
     * 3. 查到地名的字面值和当前一样就不写盘，免得白刷一遍 UI。
     */
    private fun maybeAutoAddress() {
        val l = loc ?: return
        if (!cfg.autoAddr || Prefs.addrManual(this) || autoAddrBusy) return
        if (isFinishing || isDestroyed) return

        val first = autoAddrCount == 0
        val nowPrecise = l.accuracy in 1..100
        val wasCoarse = autoAddrAcc > 100
        if (!first && !(nowPrecise && wasCoarse)) return

        autoAddrBusy = true
        autoAddrCount++
        autoAddrAcc = l.accuracy

        lifecycleScope.launch {
            val name = Geo.reverse(this@MainActivity, l.lat, l.lon)
            autoAddrBusy = false
            if (name.isNullOrBlank() || name == cfg.addr) return@launch
            cfg.addr = name
            Prefs.save(this@MainActivity, cfg)
            binding.overlay.config = cfg
            paintGps()
        }
    }

    /**
     * 顶部胶囊同时表达两件事：小圆点=定位状态（绿/黄/红），文字=当前地点。
     * 文案一律走短词 —— 这行是常驻的，写长了会挤掉地点本身。
     */
    private fun paintGps() {
        binding.overlay.loc = loc

        val dot = when {
            loc != null -> R.drawable.dot_ok
            locError != null -> R.drawable.dot_err
            else -> R.drawable.dot_wait
        }
        binding.gpsDot.setBackgroundResource(dot)

        val addr = cfg.addr.trim()
        binding.gpsTxt.text = when {
            addr.isNotEmpty() -> shortenAddress(addr)
            loc != null -> getString(R.string.gps_ready)
            locError != null -> locError
            else -> getString(R.string.gps_locating)
        }
    }

    /** 地点过长就截断 —— 完整内容在点开的地点框里能看到 */
    private fun shortenAddress(s: String, max: Int = 12): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"

    /* ------------------------- 相册图片加水印 ------------------------- */

    /**
     * 打开系统照片选择器。
     *
     * 正在处理上一张时不放行：选图 → 解码 → 保存这条链是异步的，
     * 并发进来两张会让 [pendingUri] 互相覆盖，最后保存的可能根本不是
     * 用户在预览里看过的那张 —— 这种错一旦发生极难查。
     */
    private fun pickPhotoNow() {
        if (busy) {
            toast(getString(R.string.pick_photo_busy))
            return
        }
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /**
     * 选好图之后先按**缩略图**渲染一版给用户过目。
     *
     * 缩略图预览是安全的：水印归一化按短边算，所有元素都乘同一个 s，
     * 所以短边 1080 的预览和短边 3000 的成图在**版式上完全等价**，
     * 差别的只有像素多少。于是挑图阶段内存里始终只有一张小图。
     */
    private fun onPickedImage(uri: Uri) {
        if (busy) return
        busy = true
        val cfgSnap = cfg.copy()
        val logoS = logo
        val locS = loc

        lifecycleScope.launch {
            val src = withContext(Dispatchers.IO) {
                ImageWatermarker.decode(this@MainActivity, uri, PREVIEW_SIDE)
            }
            if (src == null) {
                busy = false
                toast(getString(R.string.pick_photo_fail))
                return@launch
            }
            val prev = withContext(Dispatchers.Default) {
                ImageWatermarker.render(src, cfgSnap, logoS, cfgSnap.now(), locS)
            }
            src.recycle()
            busy = false
            pendingUri = uri
            showPhotoPreview(prev)
        }
    }

    /**
     * 预览确认框。**点了保存才落盘** —— 从相册选图比拍照更容易挑错，
     * 直接存进去用户还得自己回相册删一张，很不友好。
     */
    private fun showPhotoPreview(preview: Bitmap) {
        val maxH = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(preview)
            /* 高度封顶：竖图不封顶会把下面的按钮直接挤出屏幕 */
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_photo_preview_title)
            .setMessage(R.string.pick_photo_preview_helper)
            .setView(iv, 0, 0, 0, 0)
            .setPositiveButton(R.string.pick_photo_save) { _, _ -> savePicked() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingUri = null }
            .setNeutralButton(R.string.pick_photo_again, null)
            .create()

        dlg.setOnShowListener {
            /* 「换一张」是重选、不是结束，所以不能让它顺手把窗关了 */
            dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                dlg.dismiss()
                pickPhotoNow()
            }
        }
        dlg.setOnDismissListener { preview.recycle() }
        dlg.show()
    }

    /**
     * 保存：到这一刻才按**全尺寸**重新解码一次。
     *
     * 不在选图时就留着原图，是因为一张 3072 长边的 ARGB_8888 就是 30MB，
     * 挑图期间一直占着、换几张就容易 OOM。重新解码多花几百毫秒，
     * 换的是挑图全程只有一张缩略图的内存占用。
     */
    private fun savePicked() {
        val uri = pendingUri ?: return
        val cfgSnap = cfg.copy()
        val logoS = logo
        val locS = loc
        busy = true
        toast(getString(R.string.pick_photo_working))

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val src = ImageWatermarker.decode(this@MainActivity, uri) ?: return@withContext null
                val out = ImageWatermarker.render(src, cfgSnap, logoS, cfgSnap.now(), locS)
                src.recycle()
                val name = "水印_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".jpg"
                val u = saveBitmap(out, name)
                val tw = 220
                val thumb = if (u != null) {
                    Bitmap.createScaledBitmap(out, tw, max(1, tw * out.height / out.width), true)
                } else {
                    null
                }
                out.recycle()
                if (u != null && thumb != null) Shot(u, thumb) else null
            }
            busy = false
            pendingUri = null
            if (saved == null) {
                toast("照片生成失败")
            } else {
                lastUri = saved.uri
                binding.thumb.setImageBitmap(saved.thumb)
                shotCount++
                Prefs.setShotCount(this@MainActivity, shotCount)
                binding.cntPill.text = "已拍 $shotCount 张"
                Snackbar.make(binding.root, R.string.pick_photo_saved, Snackbar.LENGTH_LONG)
                    .setAction("分享") { share(saved.uri) }
                    .show()
            }
        }
    }

    /* ------------------------- 拍照 / 合成 ------------------------- */

    private class Shot(val uri: Uri, val thumb: Bitmap)

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        binding.shutter.isEnabled = false

        val cfgSnap = cfg.copy()
        val logoS = logo
        val locS = loc

        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bytes: ByteArray = try {
                        val buf = image.planes[0].buffer
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    } catch (e: Exception) {
                        ByteArray(0)
                    } finally {
                        image.close()
                    }

                    lifecycleScope.launch {
                        val shot = withContext(Dispatchers.Default) {
                            renderAndSave(bytes, rotation, cfgSnap, logoS, locS)
                        }
                        busy = false
                        binding.shutter.isEnabled = true
                        if (shot == null) {
                            toast("照片生成失败")
                        } else {
                            lastUri = shot.uri
                            binding.thumb.setImageBitmap(shot.thumb)
                            shotCount++
                            Prefs.setShotCount(this@MainActivity, shotCount)
                            binding.cntPill.text = "已拍 $shotCount 张"
                            Snackbar.make(binding.root, "已保存到相册", Snackbar.LENGTH_LONG)
                                .setAction("分享") { share(shot.uri) }
                                .show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    binding.shutter.isEnabled = true
                    toast("拍照失败：" + (exception.message ?: "未知错误"))
                }
            }
        )
    }

    private fun renderAndSave(
        bytes: ByteArray,
        rotation: Int,
        cfgForShot: WatermarkConfig,
        logoForShot: Bitmap?,
        locForShot: LocInfo?
    ): Shot? {
        if (bytes.isEmpty()) return null
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val srcW = src.width
        val srcH = src.height
        val swap = rotation == 90 || rotation == 270
        val outW = if (swap) srcH else srcW
        val outH = if (swap) srcW else srcH

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val m = Matrix()
        when (rotation) {
            90 -> {
                m.postRotate(90f)
                m.postTranslate(srcH.toFloat(), 0f)
            }
            180 -> {
                m.postRotate(180f)
                m.postTranslate(srcW.toFloat(), srcH.toFloat())
            }
            270 -> {
                m.postRotate(270f)
                m.postTranslate(0f, srcW.toFloat())
            }
        }

        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT && cfgForShot.mirrorSave
        val save = canvas.save()
        if (mirror) canvas.scale(-1f, 1f, outW / 2f, outH / 2f)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(save)
        src.recycle()

        WatermarkRenderer.draw(
            canvas,
            outW.toFloat(),
            outH.toFloat(),
            cfgForShot,
            logoForShot,
            /* 走 config.now()：锁了水印时间时，这里必须和预览用的是同一个值 */
            cfgForShot.now(),
            locForShot
        )

        val name = "打卡_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".jpg"
        val uri = saveBitmap(out, name)
        if (uri == null) {
            out.recycle()
            return null
        }
        val tw = 220
        val th = max(1, tw * outH / outW)
        val thumb = Bitmap.createScaledBitmap(out, tw, th, true)
        out.recycle()
        return Shot(uri, thumb)
    }

    /** API 29+ 走 scoped storage 的 RELATIVE_PATH；26~28 写公共目录再通知媒体扫描 */
    private fun saveBitmap(bmp: Bitmap, name: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/打卡水印相机"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val os = resolver.openOutputStream(uri) ?: return null
                os.use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "打卡水印相机"
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val f = File(dir, name)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun share(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "分享打卡照片"))
        } catch (e: Exception) {
            toast("没有可用的分享应用")
        }
    }

    private fun viewPhoto(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            toast("没有可用的看图应用")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locListener)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private var toastRef: android.widget.Toast? = null
    private fun toast(msg: String) {
        toastRef?.cancel()
        toastRef = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT)
        toastRef?.show()
    }

    private companion object {
        /** 关：flashMode 与 torch 都置 OFF */
        const val FLASH_OFF = 0
        /** 拍照瞬间闪一次：走 ImageCapture.FLASH_MODE_ON */
        const val FLASH_SHOT = 1
        /** 常亮补光：走 CameraControl.enableTorch(true) —— 与闪光灯是两条独立的底层路径 */
        const val FLASH_TORCH = 2

        /**
         * 相册图的**预览**解码上限（长边）。
         *
         * 预览只要版式对就够了 —— 水印按短边归一化，所有元素乘同一个 s，
         * 所以这张小图与全尺寸成图在版式上完全等价，内存却只有后者的十分之一。
         */
        const val PREVIEW_SIDE = 1440
    }
}
