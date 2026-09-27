#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成安卓侧的 BrandMarkPaths.kt —— 品牌区那几个字的矢量轮廓。

生成物是**纯数据**（路径串 + 度量），逻辑在 BrandMark.kt 里手写。
两者分开是为了：重跑这个脚本不会覆盖掉手写的绘制逻辑。

依赖 verify/gen_glyphs.py 产出的 _glyphs.json（本身又依赖 glyph_out.py）。

用法：
  python verify/gen_glyphs.py && python verify/gen_brand_kt.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(HERE, "_glyphs.json")
DST = os.path.join(ROOT, "app", "src", "main", "java", "com", "daka", "watermarkcamera",
                   "BrandMarkPaths.kt")

HEAD = '''package com.daka.watermarkcamera

/*
 * 品牌区「今日水印 / 相机真实可验」的**矢量轮廓**与字身度量。
 *
 * ⚠ 本文件由 verify/gen_brand_kt.py 自动生成，不要手改。
 *    改字体或改字：python verify/gen_glyphs.py && python verify/gen_brand_kt.py
 *    版式（字号、字距、灰块留白）在 BrandMark.kt 里，不在本文件。
 *
 * 为什么把字做成轮廓、而不是把字体文件放进 res/font：
 *   1. 只取用到的 10 个字约 %(kb).1f KB，整包字体要 1.4 MB。
 *   2. 矢量不经过字体引擎 —— 不会出现"字体没加载上就悄悄退回系统字体"
 *      （PT Mono 那次就是靠 document.fonts.check 才发现根本没生效）。
 *
 * 坐标系：**1 个字身 = 1000 单位**，原点在该字基线的左端，y 向下（已翻转）。
 *    画的时候 canvas.translate(x, baselineY); canvas.scale(em/1000, em/1000)
 *
 * 来源字体：AlimamaShuHeiTi-Bold.ttf（阿里妈妈数黑体 Bold，upem=1000）
 *   选它的依据：参考图里逐字量出的"墨宽/墨高"比与它一致（±4%%），
 *   是「今日水印相机字体」目录里唯一覆盖这 10 个字的 TrueType 字。
 *   参考图反解过程见 verify/solve_brand.py。
 */
internal object BrandMarkPaths {

    /** 一个字身 = 1000 单位 */
    const val EM = 1000f

    /** 品牌名那一行：4 个字。顺序 = 今日水印 */
    val NAME: List<String> = listOf(
%(name)s    )

    /** 标语那一行：前 [SLOGAN_WHITE_COUNT] 个是白字「相机」，后面几个是灰块里的深字「真实可验」 */
    val SLOGAN: List<String> = listOf(
%(slogan)s    )

    /** 「相机」占前几个字形 */
    const val SLOGAN_WHITE_COUNT = 2

    /** 品牌名末字（印）的墨迹右边界 ÷ em —— 右对齐要用它，用字身宽右边会空一块 */
    const val NAME_INK_RIGHT = 0.960f

    /** 「真实可验」4 个字的墨迹并集，相对这 4 个字的原点（单位 ÷ em，y 已翻转） */
    const val SLOGAN_INK_LEFT = %(sxmin).3ff
    const val SLOGAN_INK_RIGHT = %(sxmax).3ff
    const val SLOGAN_INK_TOP = %(sytop).3ff
    const val SLOGAN_INK_BOTTOM = %(sybot).3ff
}
'''


def main():
    if not os.path.exists(SRC):
        raise SystemExit("先跑：python verify/gen_glyphs.py")
    data = json.load(open(SRC, encoding="utf-8"))
    by = {d["s"]: d for d in data}

    name = by["今日水印"]
    slog = by["相机真实可验"]

    def fmt(d):
        out = []
        for g in d["glyphs"]:
            out.append('        // %s\n        "%s",\n' % (g["ch"], g["path"]))
        return "".join(out)

    # 「真实可验」这 4 个字（SLOGAN 的后 4 个）的墨迹并集，用它定灰块大小。
    # 各字原点 = i × 1em（advance=1000），所以第 i 字的局部坐标要加 i*1000。
    tail = slog["glyphs"][2:]

    def ink_of(g):
        import re
        nums = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?", g["path"])]
        px = nums[0::2]
        py = nums[1::2]
        return min(px), max(px), min(py), max(py)

    boxes = []
    for i, g in enumerate(tail):
        x0, x1, y0, y1 = ink_of(g)
        boxes.append((x0 + i * 1000, x1 + i * 1000, y0, y1))
    sxmin = min(b[0] for b in boxes) / 1000.0
    sxmax = max(b[1] for b in boxes) / 1000.0
    sytop = min(b[2] for b in boxes) / 1000.0      # 已翻转：负值 = 基线以上
    sybot = max(b[3] for b in boxes) / 1000.0

    src = HEAD % {
        "name": fmt(name), "slogan": fmt(slog),
        "sxmin": sxmin, "sxmax": sxmax, "sytop": sytop, "sybot": sybot,
        "kb": (len("".join(g["path"] for g in name["glyphs"] + slog["glyphs"])) / 1024.0),
    }
    with open(DST, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    print("写出 %s (%d 字节)" % (os.path.relpath(DST, ROOT), os.path.getsize(DST)))
    print("  品牌名 4 字；标语 6 字")
    print("  「真实可验」墨迹并集  x %.3f..%.3f em   y %.3f..%.3f em" % (sxmin, sxmax, sytop, sybot))


if __name__ == "__main__":
    main()
