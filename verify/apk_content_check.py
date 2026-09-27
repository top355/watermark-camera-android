"""验收 APK 内容：直接读 classes*.dex 与 resources.arsc 的字节，比时间戳硬、
比 aapt dump 直接、比装机试快。用完可删。

硬判据（本工程专用）：
  · 水印第三行的**标签字面量**是「防伪 」而不是「防伪码 」—— 带上那个尾空格才算真改了。
    注意：裸的「防伪码」在设置页说明文案里是个正常名词，不该卡它，
    只有 AntiFake.line() 拼出来的那个字面量才是水印上印的东西。
  · 绝不能出现任何公司标识（旺德府 / WDF / 长沙 / 和苑天辰 …）
"""
import os
import sys
import zipfile

APKS = [
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/build/outputs/apk/release/app-release.apk",
]

MUST_HAVE = ["防伪 ", "使用内置占位 Logo"]
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
