package com.daka.watermarkcamera

import android.app.Application

/**
 * 只做一件事：趁进程启动把非系统字体读进来。
 *
 * 为什么放 Application 而不是某个 Activity：字体是全局资源，而水印有**三条**
 * 绘制入口 —— 预览浮层、拍照合成、相册加水印。挂在某一个 Activity 上，
 * 另外两条就会静默地少一份字体（"设置页看着对、拍出来不一样"那种最难查的问题）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Typefaces.install(this)
    }
}
