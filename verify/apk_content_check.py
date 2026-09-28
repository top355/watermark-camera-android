"""验收 APK 内容：直接读 classes*.dex 与 resources.arsc 的字节，比时间戳硬、
比 aapt dump 直接、比装机试快。**出包后必跑**（它是发布清单的一步，不是临时脚本）。

硬判据（本工程专用）：
  · 水印第三行的**标签字面量**是「防伪 」而不是「防伪码 」—— 带上那个尾空格才算真改了。
    注意：裸的「防伪码」在设置页说明文案里是个正常名词，不该卡它，
    只有 AntiFake.line() 拼出来的那个字面量才是水印上印的东西。
  · 绝不能出现任何公司标识（旺德府 / WDF / 长沙 / 和苑天辰 …）
  · 新版式的弹出编辑器（布局名 + 文案）确实打进了包里 ——
    资源改名/被 shrink 掉时，编译不报错，只有在这里才看得出来。
"""
import os
import sys
import zipfile

APKS = [
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/build/outputs/apk/release/app-release.apk",
]

MUST_HAVE = [
    "防伪 ",
    "使用内置占位 Logo",
    # 弹出式编辑器：布局名与界面文案
    "popup_card",
    "popup_datetime",
    "时间格式：时:分",
    "水印上会印：",
    "回到当前时间",
    # 相机放大倍数：档位条的两个背景 + 无障碍文案。
    # 资源名在 resources.arsc 里是明文，所以改名/被 shrink 掉在这里看得见。
    "bg_zoom_chip",
    "zoom_chip_desc",
    "原始倍率，不放大",
    # 出土比例与分辨率：两个单选组的 id + 设置分区标题 + 一句关键文案。
    # 新增档位最容易出的事是"UI 改了但资源没打进包"，在 arsc 里一眼可见。
    "rgRatio",
    "rgSize",
    "sec_output",
    "出土照片",
    "黑边里的内容不会被拍进去",
    # 第三档是「全屏」（跟随屏幕比例），不再是 1:1 —— 这条同时盯住
    # "档位换了但文案没换"，以及"全屏档没被打进包"
    "全屏",
    "rbRatioFull",
    # 同时保存原图：开关 id + 标签 + 落盘文件名前缀
    "swSaveOriginal",
    "同时保存原图",
    "原图_",
    # 顶栏那条右对齐撑杆。横屏收窄地点胶囊靠的就是它
    # （屏够宽时由 HudSize.applyTopBarWidths 给它 weight=1），
    # 少了它胶囊倒是会收窄，但右侧的闪光/设置会被一起拽到屏幕中间。
    "topSpacer",
    # 相册入口。**两份布局里都必须有它**：代码只认 id，而 ViewBinding 对
    # "只在部分变体里存在"的 id 会生成可空字段 —— 横屏冷启动那份漏了它，
    # 表现是"点了没反应也不报错"。单测那边由 LayoutParityTest 逐条盯着，
    # 这里再在**打好的包里**确认一次它的名字真的被编进了资源表。
    "btnGallery",
]
MUST_NOT_HAVE = ["防伪码 ", "旺德府", "WDF", "长沙市", "长沙县", "和苑天辰", "湖南省"]

fails = 0
for apk in APKS:
    print("==== %s (%.2f MB) ====" % (os.path.basename(apk), os.path.getsize(apk) / 1048576.0))
    z = zipfile.ZipFile(apk)
    dex = b""
    for n in z.namelist():
        if n.endswith(".dex"):
            dex += z.read(n)
    arsc = z.read("resources.arsc") if "resources.arsc" in z.namelist() else b""
    both = dex + arsc

    for s in MUST_HAVE:
        hit = s.encode("utf-8") in both
        print("   %s 含 %r" % ("ok  " if hit else "FAIL", s))
        if not hit:
            fails += 1
    for s in MUST_NOT_HAVE:
        hit = s.encode("utf-8") in both
        print("   %s 不含 %r" % ("ok  " if not hit else "FAIL", s))
        if hit:
            fails += 1

    # 签名（现代 AGP 走 v2/v3，签名字段在中央目录里，不在 META-INF）
    names = z.namelist()
    print("   META-INF 里的签名文件: %s" % [n for n in names if n.upper().startswith("META-INF/")
                                            and n.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))])
    print("   dex 个数: %d   资源表: %d 字节" % (
        sum(1 for n in names if n.endswith(".dex")), len(arsc)))

print()
print("结果：%s" % ("全部通过" if not fails else "%d 项失败" % fails))
sys.exit(1 if fails else 0)
