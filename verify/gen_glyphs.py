#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把要用的汉字轮廓一次性导成 JSON，供 verify/glyph_parity.js 与代码生成使用。

为什么要拆成独立一步：这台机器上从 Node 里 spawn 任何子进程都会 EBUSY
（README 里记过，Chrome 也是这样），所以 python 必须由外层 shell 调用。

用法：
  python verify/gen_glyphs.py                 # 只导出 JSON + 打印统计
  python verify/gen_glyphs.py --kt            # 顺便生成 Kotlin 常量片段
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glyph_out import measure  # noqa: E402

FONT = "Y:/今日水印相机字体/AlimamaShuHeiTi-Bold.ttf"
# 品牌区那两行固定文字。改动这里就等于改水印上的字，Kotlin 侧不用手改。
STRINGS = ["今日水印", "相机真实可验"]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_glyphs.json")


def main():
    if not os.path.exists(FONT):
        raise SystemExit("找不到字体：%s" % FONT)
    data = []
    for s in STRINGS:
        gs, total = measure(FONT, s)
        data.append({"s": s, "total": total, "glyphs": gs})
        print("%s  共 %d 字，整串宽 %.0f/1000em，路径合计 %d 字节"
              % (s, len(gs), total, sum(len(g["path"]) for g in gs)))
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print("写出 %s (%d 字节)" % (os.path.relpath(OUT), os.path.getsize(OUT)))

    if "--kt" in sys.argv:
        for d in data:
            print("\n/* ---- %s ---- */" % d["s"])
            for g in d["glyphs"]:
                print('    // %s  adv=%.1f' % (g["ch"], g["adv"]))
                print('    "%s",' % g["path"])


if __name__ == "__main__":
    main()
