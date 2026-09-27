package com.daka.watermarkcamera

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.daka.watermarkcamera.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var cfg = WatermarkConfig()
    private var logoPath: String? = null

    /** 地址框里当前的值是不是「按定位反查」填进去的（决定要不要标记成用户手输） */
    private var fromGeo = false

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            /* 大图解码可能要几百毫秒，放后台线程做，避免界面看起来卡住 */
            binding.btnLogoPick.isEnabled = false
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { LogoStore.save(this@SettingsActivity, uri) }
                binding.btnLogoPick.isEnabled = true
                when (res) {
                    is LogoStore.SaveResult.Ok -> {
                        logoPath = res.path
                        refreshLogoPreview()
                        toast("Logo 已更新（${res.width}×${res.height}）")
                    }
                    /* 把具体原因说出来，别只丢一句"失败" */
                    is LogoStore.SaveResult.Fail -> toast("Logo 读取失败：" + res.reason)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cfg = Prefs.load(this)
        logoPath = cfg.logoPath

        binding.toolbar.setNavigationOnClickListener { finish() }

        bindTextFields()
        bindSwitches()
        bindAddress()
        bindLogo()
        bindManualTime()
        binding.doneButton.setOnClickListener { finish() }

        refreshLogoPreview()
    }

    /**
     * 刷新预览。
     *
     * 解析走 [LogoStore.resolve]，和主界面/拍照是同一条路 —— 内置 Logo 也在这里体现出
     * 「选了自定义图就用自定义、没选就兜到内置」，所以预览和实际拍出来的必然一致。
     * 底部那行文字顺带说清当前用的是哪个来源。
     */
    private fun refreshLogoPreview() {
        val custom = LogoStore.load(this, logoPath)
        /*
         * 配置里记着路径、文件却没了（清理软件、卸载重装都会这样）。
         * 这时把陈旧路径丢掉，否则会一直"设置了 Logo 但照片上没有"。
         */
        if (custom == null && !logoPath.isNullOrBlank() && !LogoStore.file(this).exists()) {
            logoPath = null
        }
        cfg.logoPath = logoPath
        cfg.useBuiltinLogo = binding.swUseBuiltinLogo.isChecked

        val bmp = LogoStore.pick(this, cfg, custom)
        if (bmp != null) {
            binding.logoPreview.setImageBitmap(bmp)
            binding.logoPreview.visibility = android.view.View.VISIBLE
        } else {
            binding.logoPreview.setImageDrawable(null)
            binding.logoPreview.visibility = android.view.View.GONE
        }
        binding.logoSource.text = "当前使用：" + LogoStore.sourceLabel(cfg, custom != null)
    }

    private fun bindTextFields() {
        binding.etCardTitle.setText(cfg.cardTitle)
        binding.etUnit.setText(cfg.unit)
        binding.etAddr.setText(cfg.addr)
        binding.etNote.setText(cfg.note)
        binding.etName.setText(cfg.name)
        binding.etBrandName.setText(cfg.brandName)
        binding.etBrandSlogan.setText(cfg.brandSlogan)
    }

    private fun bindSwitches() {
        binding.swShowCard.isChecked = cfg.showCard
        binding.swUseBuiltinLogo.isChecked = cfg.useBuiltinLogo
        binding.swShowTime.isChecked = cfg.showTime
        binding.swShowSec.isChecked = cfg.showSec
        binding.swShowDate.isChecked = cfg.showDate
        binding.swShowAddr.isChecked = cfg.showAddr
        binding.swAutoAddr.isChecked = cfg.autoAddr
        binding.swShowNote.isChecked = cfg.showNote
        binding.swShowName.isChecked = cfg.showName
        binding.swShowCoord.isChecked = cfg.showCoord
        binding.swShowAcc.isChecked = cfg.showAcc
        binding.swShowAlt.isChecked = cfg.showAlt
        binding.swShowBrand.isChecked = cfg.showBrand
        binding.swShowCode.isChecked = cfg.showCode
        binding.swMirrorSave.isChecked = cfg.mirrorSave
        if (cfg.posTop) binding.rbTop.isChecked = true else binding.rbBottom.isChecked = true
    }

    private fun bindAddress() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            Prefs.addresses(this)
        )
        binding.etAddr.setAdapter(adapter)
        binding.etAddr.threshold = 0

        /* 用户一动手打字就不算"反查来的"了（反查回填也会触发 watcher，所以要用 suppress 挡） */
        var suppress = false
        binding.etAddr.doAfterTextChanged { if (!suppress) fromGeo = false }

        binding.btnGeo.setOnClickListener {
            val l = Geo.bestLastKnown(this)
            if (l == null) {
                toast("还没拿到定位，请先回主界面等一下定位成功")
                return@setOnClickListener
            }
            toast("正在反查地点…")
            lifecycleScope.launch {
                val name = Geo.reverse(this@SettingsActivity, l.latitude, l.longitude)
                if (name.isNullOrBlank()) {
                    toast("没查到，请手填")
                } else {
                    suppress = true
                    binding.etAddr.setText(name)
                    suppress = false
                    fromGeo = true
                    toast("已填入地点")
                }
            }
        }
    }

    private fun bindLogo() {
        binding.btnLogoPick.setOnClickListener { pickLogo.launch("image/*") }
        binding.btnLogoClear.setOnClickListener {
            LogoStore.clear(this)
            logoPath = null
            refreshLogoPreview()
            /* 清掉自定义图后不一定就是"没有 Logo"了 —— 内置默认开着的话会兜过去 */
            toast(
                if (binding.swUseBuiltinLogo.isChecked) "已清除自定义 Logo，改用内置 Logo"
                else "已清除 Logo"
            )
        }
        binding.swUseBuiltinLogo.setOnCheckedChangeListener { _, _ -> refreshLogoPreview() }
    }

    /** 手动指定的水印时刻。开关关着时它只是"下次要用什么值" */
    private var manualMs = 0L

    /**
     * 手动指定水印时间。
     *
     * 拆成「先日期、后时间」两步：系统没有内置的日期+时间合并对话框，
     * 自己拼一个在低版本上很容易变形。两步走完一样清楚。
     */
    private fun bindManualTime() {
        binding.swManualTime.isChecked = cfg.manualTime
        manualMs = if (cfg.manualTimeMs > 0L) cfg.manualTimeMs else System.currentTimeMillis()
        paintManualTime()

        binding.btnPickTime.setOnClickListener {
            val base = Calendar.getInstance().apply { timeInMillis = manualMs }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    TimePickerDialog(
                        this,
                        { _, hh, mm ->
                            manualMs = Calendar.getInstance().apply {
                                set(y, m, d, hh, mm, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            /*
                             * 选完就顺手把开关打开 —— 刚挑好具体时刻、却因为开关没开
                             * 而看不到效果，是最容易让人以为"坏了"的一种情况。
                             */
                            binding.swManualTime.isChecked = true
                            paintManualTime()
                        },
                        base.get(Calendar.HOUR_OF_DAY),
                        base.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                base.get(Calendar.YEAR),
                base.get(Calendar.MONTH),
                base.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    /** 显示当前手动时间。每次重建 SimpleDateFormat 无所谓 —— 一个设置页点不了几次 */
    private fun paintManualTime() {
        binding.tvManualTime.text =
            SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(Date(manualMs))
    }

    override fun onPause() {
        super.onPause()
        cfg.cardTitle = binding.etCardTitle.text?.toString().orEmpty().trim().ifBlank { "打卡" }
        cfg.unit = binding.etUnit.text?.toString().orEmpty().trim()
        /* 地点统一精简成「市+区县·小区」，太长会把水印那行顶满（见 Addr） */
        val newAddr = Addr.compact(binding.etAddr.text?.toString().orEmpty())
        if (newAddr != cfg.addr) {
            /*
             * 地址真被改过才动「是否手输」这个标记 —— 只是进设置页逛一圈不该改变它。
             * 反查填入的仍算自动模式，手输才算用户的固定选择。
             */
            Prefs.setAddrManual(this, newAddr.isNotEmpty() && !fromGeo)
        }
        cfg.addr = newAddr

        /*
         * 「原来空、现在填了东西」就顺手把开关打开 —— 和地点那条逻辑一致：
         * 填了却看不见，用户只会以为没生效。
         *
         * 只在**从空变非空**这一刻自动开：这样用户之后主动关掉它，
         * 下次进来保存不会被重新打开（否则这个开关就等于关不掉）。
         */
        val newNote = binding.etNote.text?.toString().orEmpty().trim()
        val newName = binding.etName.text?.toString().orEmpty().trim()
        if (cfg.note.isBlank() && newNote.isNotBlank()) binding.swShowNote.isChecked = true
        if (cfg.name.isBlank() && newName.isNotBlank()) binding.swShowName.isChecked = true
        cfg.note = newNote
        cfg.name = newName

        cfg.brandName = binding.etBrandName.text?.toString().orEmpty().trim()
        cfg.brandSlogan = binding.etBrandSlogan.text?.toString().orEmpty().trim()
        cfg.logoPath = logoPath
        cfg.useBuiltinLogo = binding.swUseBuiltinLogo.isChecked

        cfg.showCard = binding.swShowCard.isChecked
        cfg.showTime = binding.swShowTime.isChecked
        cfg.showSec = binding.swShowSec.isChecked
        cfg.showDate = binding.swShowDate.isChecked
        cfg.showAddr = binding.swShowAddr.isChecked
        cfg.autoAddr = binding.swAutoAddr.isChecked
        cfg.showNote = binding.swShowNote.isChecked
        cfg.showName = binding.swShowName.isChecked
        cfg.showCoord = binding.swShowCoord.isChecked
        cfg.showAcc = binding.swShowAcc.isChecked
        cfg.showAlt = binding.swShowAlt.isChecked
        cfg.showBrand = binding.swShowBrand.isChecked
        cfg.showCode = binding.swShowCode.isChecked
        cfg.mirrorSave = binding.swMirrorSave.isChecked
        cfg.posTop = binding.rbTop.isChecked
        /*
         * 开关处于"关"时也把那个时刻存下去：用户下次再打开，
         * 看到的还是上次挑好的时间，不用重新选一遍。
         */
        cfg.manualTime = binding.swManualTime.isChecked
        cfg.manualTimeMs = manualMs

        Prefs.save(this, cfg)
        Prefs.rememberAddress(this, cfg.addr)
    }

    private var t: android.widget.Toast? = null
    private fun toast(msg: String) {
        t?.cancel()
        t = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT)
        t?.show()
    }
}
