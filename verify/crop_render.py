#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把渲染图的水印区域裁出来放大，好一眼看清版式。

为什么需要它：1440x1920 的渲染图直接看时水印只占画面下缘一小条，
"右边到底有没有压进品牌区""底边到底齐没齐"这种问题在上面根本量不出来。
裁出来放大 2 倍之后，"差 3 像素"和"刚好贴边"是看得出区别的。

用法：
    python crop_render.py M-pressed-addr.png                 # 默认裁下缘，放大 2 倍
    python crop_render.py M-plain.png out.png --box 0,1650,760,1900 --scale 3
"""
import argparse
import os
import sys

from PIL import Image


def parse_box(s):
    parts = [int(v) for v in s.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("--box 要 4 个数：x0,y0,x1,y1")
    return tuple(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="源 PNG")
    ap.add_argument("dst", nargs="?", help="输出 PNG，默认 <src 同名>_crop.png")
    ap.add_argument("--box", type=parse_box, default=None,
                    help="裁剪矩形 x0,y0,x1,y1；默认取画面下方 32%%")
    ap.add_argument("--scale", type=int, default=2, help="放大倍数，默认 2")
    args = ap.parse_args()

    if not os.path.isfile(args.src):
        print("找不到 " + args.src, file=sys.stderr)
        return 1

    im = Image.open(args.src).convert("RGB")
    w, h = im.size
    box = args.box or (0, int(h * 0.68), w, h)
    im = im.crop(box).resize(
        ((box[2] - box[0]) * args.scale, (box[3] - box[1]) * args.scale),
        Image.NEAREST,
    )

    dst = args.dst or os.path.splitext(args.src)[0] + "_crop.png"
    im.save(dst)
    print("%s  %s  ->  %s  (裁剪 %s, 放大 %dx)" % (args.src, Image.open(args.src).size, dst, box, args.scale))
    return 0


if __name__ == "__main__":
    sys.exit(main())
