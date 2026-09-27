package com.daka.watermarkcamera

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.daka.watermarkcamera.databinding.PopupCardBinding
import com.google.android.material.button.MaterialButton

/**
 * 弹出式卡片：一层半透明遮罩 + 一张居中圆角卡（标题 / 副标题 / 内容 / 右下角按钮排）。
 *
 * **为什么不用 MaterialAlertDialogBuilder**：地点编辑和日期时间两个弹窗必须长得一模一样
 * ——同一张卡、同一个位置、同一排按钮。AlertDialog 的按钮区只有"取消/确定"两个位置，
 * 想加第三个就得自己写 layout；而且它的 window 只有卡片那么大，点不到"卡片外面"，
 * 也就做不出点遮罩关闭。与其在两个地方各绕一遍，不如把"卡片本身"做成可复用的壳。
 *
 * 用法：
 * ```
 * CardPopup(this)
 *     .title("填写地点")
 *     .body(view)
 *     .action("清除") { it.dismiss() }
 *     .action("完成", primary = true) { save() }
 *     .show()
 * ```
 *
 * 窗口主题见 `Theme.WatermarkCamera.Popup`（**Light**，不跟 DayNight 走）——
 * 卡片永远是浅底深字，因为它永远压在深色的相机预览上。
 */
class CardPopup(context: Context) {

    private val b = PopupCardBinding.inflate(LayoutInflater.from(context))
    private val dlg = Dialog(context, R.style.Theme_WatermarkCamera_Popup)
    private val density = context.resources.displayMetrics.density

    /** 右上角之外的关闭（点遮罩 / 返回键）也会走到这里，用来回收监听器之类 */
    var onDismiss: (() -> Unit)? = null

    init {
        dlg.setContentView(b.root)
        dlg.setCancelable(true)
        /*
         * 系统那套"点外面关闭"是按 **window 边界** 判的，而这扇窗被设成了满屏
         * （windowIsFloating=false，见主题注释），于是它会认为"点哪儿都是外面"，
         * 卡片里点一下就把窗关了。所以关掉它，自己在遮罩根节点上判。
         */
        dlg.setCanceledOnTouchOutside(false)

        b.popupRoot.setOnClickListener { dismiss() }
        dlg.setOnDismissListener { onDismiss?.invoke() }
    }

    /**
     * 让卡片躲开软键盘。**只有带输入框的弹窗需要调**（日期时间那六个滚轮不弹键盘）。
     *
     * 为什么不用窗口的 `SOFT_INPUT_ADJUST_RESIZE`：那个标志 API 30 起已废弃，
     * 而本工程开了 edge-to-edge、目标 SDK 35，这个标志在上面直接不生效 ——
     * 表现是键盘弹起来盖住卡片下半截，连「完成」都按不到。
     * 于是自己听 IME 的 inset，把它加在根节点下内边距上：
     * 卡片是居中的，可用高度一变就整体上移，始终留在键盘上方。
     */
    fun avoidIme(): CardPopup {
        /* 基准值只取一次 —— 每次都从 v.paddingBottom 起算会越加越多 */
        val basePadBottom = b.popupRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.popupRoot) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadBottom + ime)
            insets
        }
        return this
    }

    fun title(text: CharSequence?): CardPopup {
        b.tvPopupTitle.text = text
        return this
    }

    /** 说明性小字。不调用就一直 GONE，不给版式留空位 */
    fun subtitle(text: CharSequence?): CardPopup {
        b.tvPopupSub.text = text
        b.tvPopupSub.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        return this
    }

    /** 放进卡片中部的编辑器视图（滚轮、输入框……） */
    fun body(view: View): CardPopup {
        b.popupBody.removeAllViews()
        b.popupBody.addView(view)
        return this
    }

    /**
     * 追加一个右下角按钮。**返回按钮本身**，因为有些按钮的文字要跟着状态变
     * （比如「时间格式：时:分:秒」切一下就得改字），拿不到引用就只能靠 index 去猜。
     *
     * @param primary 主操作（保存 / 完成）传 true，画成实心按钮，一眼能分出哪个是"就这样"。
     */
    fun action(
        text: CharSequence,
        primary: Boolean = false,
        onClick: (CardPopup) -> Unit
    ): MaterialButton {
        val btn = MaterialButton(
            b.root.context,
            null,
            if (primary) R.style.PopupActionPrimary else R.style.PopupActionText
        )
        btn.text = text
        btn.minHeight = (36 * density).toInt()
        btn.isAllCaps = false
        btn.setOnClickListener { onClick(this) }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = (6 * density).toInt()
        b.popupActions.addView(btn, lp)
        b.popupActions.visibility = View.VISIBLE
        return btn
    }

    fun isShowing(): Boolean = dlg.isShowing

    fun show() = dlg.show()

    fun dismiss() {
        if (dlg.isShowing) dlg.dismiss()
    }
}
