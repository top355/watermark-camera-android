#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
量一件事：**正文块和品牌区的"可见墨迹"底边到底齐不齐。**

layoutMetrics 断言的是布局框底边（严格相等）。但肉眼看到的是字形的墨迹 ——
两个块的字号不同，字号不同则 descent 不同，于是"框对齐"未必等于"看着齐"。
这个脚本直接把渲染图上两块各自的最后一行有墨迹的行号找出来，差多少一看就知道。

用法：
    python ink_align.py ../app/build/test-render/A-bottom-text-unit.png
"""
import sys

from PIL import Image

# 采样范围（1440x1920 的渲染图，按比例给）：左边是正文块，右边是品牌区
def probe(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()

    def ink_rows(x0, x1, y0, y1):
        """返回该区域里"明显亮于背景"的像素行号集合（白字在暗遮罩上，取亮度阈值）"""
        rows = set()
        for y in range(y0, y1):
            for x in range(x0, x1):
                r, g, b = px[x, y]
                if r > 200 and g > 200 and b > 200:
                    rows.add(y)
                    break
        return rows

    left = ink_rows(int(w * 0.03), int(w * 0.55), int(h * 0.80), h)
    right = ink_rows(int(w * 0.80), int(w * 0.97), int(h * 0.80), h)

    def spans(rows):
        """把行号并成连续段，用来分辨"哪几行字"以及最后一段落在哪"""
        out = []
        for y in sorted(rows):
            if out and y - out[-1][1] <= 2:
                out[-1][1] = y
            else:
                out.append([y, y])
        return out

    ls, rs = spans(left), spans(right)
    print("%s  %dx%d" % (path, w, h))
    print("  正文块（左）墨迹行段: %s" % ls)
    print("  品牌区（右）墨迹行段: %s" % rs)
    if ls and rs:
        print("  正文块最后一行墨迹下沿 = %d" % ls[-1][1])
        print("  品牌区最后一行墨迹下沿 = %d" % rs[-1][1])
        print("  → 差 %+d px（正数=品牌区更低）" % (rs[-1][1] - ls[-1][1]))


if __name__ == "__main__":
    for p in sys.argv[1:] or ["../app/build/test-render/A-bottom-text-unit.png"]:
        probe(p)
