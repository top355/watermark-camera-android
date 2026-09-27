#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从参考图反解品牌区的版式参数（字号、字距、灰块几何）。

参考图只有 360x175，逐字量出来的东西误差不小，所以不靠"看上去差不多"，
而是**解方程**：同一字体下，每个字的墨迹宽 = 字号 × (该字墨宽/em)，
已知字体里的墨宽、又量到了图上的墨宽，就能把每行的字号单独解出来；
再用各字的左边界差解出步进（字距）。多字冗余，误差能互相抵掉。

关键前提：两行用的是**同一份字体**。判断依据是逐字的"墨宽/墨高"比 ——
行1 四个字的比值与 AMS 一致（±4%），所以行1 可以放心套 AMS 的度量。

用法： python verify/solve_brand.py
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glyph_out import Glyf  # noqa: E402

FONT = "Y:/今日水印相机字体/AlimamaShuHeiTi-Bold.ttf"

# 参考图上逐字量到的墨迹框（x0,x1,x2,y0,y1），单位 px
#   x0..x1 = 墨迹左右边界，y0..y1 = 上下边界
ROW1 = [("今", 17, 84, 23, 86), ("日", 107, 160, 25, 85),
        ("水", 182, 249, 22, 87), ("印", 271, 335, 23, 87)]
ROW2 = [("相", 17, 60, 110, 159), ("机", 67, 113, 110, 160)]
# 灰块（含在行2 里的「真实可验」）：整块的外框，误差大，只用来定上下高度和左右的加边
BOX = (125, 337, 106, 167)


def bbox(f, ch):
    cs, adv = f.outline(ch)
    xs = [p[0] for c in cs for p in c]
    ys = [p[1] for c in cs for p in c]
    return min(xs), max(xs), min(ys), max(ys), adv


def solve(f, row, label):
    """一行里每个字单独解出一个字号，再取中位数（抗一个字的量测噪声）"""
    es = []
    for ch, x0, x1, y0, y1 in row:
        bx0, bx1, by0, by1, adv = bbox(f, ch)
        e_w = (x1 - x0) * 1000.0 / (bx1 - bx0)
        e_h = (y1 - y0) * 1000.0 / (by1 - by0)
        es.append((ch, e_w, e_h))
    print("%s  逐字解出的 em（px）：" % label)
    for ch, ew, eh in es:
        print("    %s  按墨宽 %.1f   按墨高 %.1f" % (ch, ew, eh))
    e = sorted((ew + eh) / 2 for _, ew, eh in es)[len(es) // 2]
    print("    → em = %.1f" % e)

    # 步进：以「墨迹左边界 - 左边界留白×em」为各字的原点，相邻原点差就是步进
    orgs = []
    for ch, x0, x1, y0, y1 in row:
        bx0, bx1, by0, by1, adv = bbox(f, ch)
        orgs.append(x0 - bx0 * e / 1000.0)
    steps = [orgs[i + 1] - orgs[i] for i in range(len(orgs) - 1)]
    print("    各原点 %s  步进 %s" % ([round(o, 1) for o in orgs], [round(s, 1) for s in steps]))
    a = sum(steps) / len(steps)
    print("    → 步进 A = %.1f px，即 %.3f em（字距 = %+.3f em）"
          % (a, a / e, a / e - 1))
    return e, a


def main():
    f = Glyf(open(FONT, "rb").read())
    print("字体 %s   upem=%d" % (os.path.basename(FONT), f.upem))
    print()
    e1, a1 = solve(f, ROW1, "行1 今日水印")
    print()
    e2, a2 = solve(f, ROW2, "行2 相机")
    print()
    print("两行字号比 e1/e2 = %.3f" % (e1 / e2))
    print()

    # 灰块：以行2「相」字的原点为基准，换算成 em
    org2 = ROW2[0][1] - bbox(f, "相")[0] * e2 / 1000.0
    print("行2 相字原点 x = %.1f" % org2)
    bl, br, bt, bb = BOX
    print("灰块  左 %.3f em  右 %.3f em  上 %.3f em  下 %.3f em （相对相字原点）"
          % ((bl - org2) / e2, (br - org2) / e2, (bt - org2 * 0) / e2, (bb) / e2))
    print("     左右加边 %.3f em + %.3f em"
          % ((bl - org2) / e2 - 2 * a2 / e2, (br - org2) / e2 - 2 * a2 / e2 - 4 * a2 / e2))

    # 竖向上把墨迹换成"相对基线"的位置：拿行1 的水字（ymin 最低）反推基线
    bx0, bx1, by0, by1, adv = bbox(f, "水")
    # 水 墨迹 y 范围 22..87，字体里 by0..by1 → 基线 = y1 - by1*e/1000
    base1 = ROW1[2][4] - by1 * e1 / 1000.0
    print()
    print("行1 基线 y ≈ %.1f（由 水 字反推）" % base1)
    bx0, bx1, by0, by1, adv = bbox(f, "相")
    base2 = ROW2[0][4] - by1 * e2 / 1000.0
    print("行2 基线 y ≈ %.1f（由 相 字反推）" % base2)
    print("灰块相对行2基线：上 %.3f em  下 %.3f em  高 %.3f em"
          % ((bt - base2) / e2, (bb - base2) / e2, (bb - bt) / e2))
    print()
    print("两行基线间距 %.3f em(行1)" % ((base2 - base1) / e1))


if __name__ == "__main__":
    main()
