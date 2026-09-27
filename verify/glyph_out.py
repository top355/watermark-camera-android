#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从 TrueType(glyf) 字体里取指定汉字的轮廓，转成路径数据 —— 不依赖 fontTools。

为什么要自己做而不是直接嵌字体：
  1. 嵌 AlimamaShuHeiTi-Bold.ttf 要 +1.4MB；只取这 10 个字大约 6KB。
  2. 矢量路径不经过字体引擎，任何 ROM 上画出来都一模一样 —— 不存在"字体没加载上
     就悄悄退回系统字体"这种事（PT Mono 那次就是靠 check() 才发现没生效的）。

轮廓是**二次曲线**（quadratic）：TrueType 用 off-curve 点做控制点，连续两个 off-curve
点之间隐含一个连接它们的 on-curve 中点，展开时必须补出来，否则字形会缺角。

用法：
  python glyph_out.py svg  <font.ttf> 今日水印           > out.svg
  python glyph_out.py kt   <font.ttf> 今日水印相机真实可验 > out.kt
  python glyph_out.py dump <font.ttf> 今                  # 看单个字轮廓点数
"""
import struct
import sys


def u16(b, o):
    return struct.unpack_from(">H", b, o)[0]


def s16(b, o):
    return struct.unpack_from(">h", b, o)[0]


def u32(b, o):
    return struct.unpack_from(">I", b, o)[0]


class Glyf:
    def __init__(self, data):
        self.d = data
        assert data[:4] in (b"\x00\x01\x00\x00", b"true"), "只处理 TrueType(glyf) 外壳"
        n = u16(data, 4)
        self.t = {}
        for i in range(n):
            p = 12 + i * 16
            tag = data[p:p + 4].decode("latin-1")
            self.t[tag] = (u32(data, p + 8), u32(data, p + 12))
        self.upem = u16(self.tab("head"), 18)
        self.num_glyphs = u16(self.tab("maxp"), 4)
        self.loca_fmt = s16(self.tab("head"), 50)
        # loca 里的偏移是**相对 glyf 表起点**的，不是相对文件起点。
        # 忘了加这个基址会读到别的表的字节 —— 表现是 nc 读出一堆乱七八糟的数。
        self.glyf_off = self.t["glyf"][0]
        self.loca = self._loca()
        self.cmap = self._cmap()
        self._hmtx_adv = self._advances()

    def tab(self, name):
        o, n = self.t[name]
        return self.d[o:o + n]

    def _loca(self):
        raw = self.tab("loca")
        out = []
        for i in range(self.num_glyphs + 1):
            out.append(u16(raw, i * 2) * 2 if self.loca_fmt == 0 else u32(raw, i * 4))
        return out

    def _advances(self):
        raw = self.tab("hmtx")
        hhea = self.tab("hhea")
        numH = u16(hhea, 34)
        adv = []
        last = 0
        for i in range(self.num_glyphs):
            if i < numH:
                last = u16(raw, i * 4)
            adv.append(last)
        return adv

    def _cmap(self):
        raw = self.tab("cmap")
        n = u16(raw, 2)
        best = {}
        for i in range(n):
            p = 4 + i * 8
            sub = u32(raw, p + 4)
            fmt = u16(raw, sub)
            if fmt == 4:
                segX2 = u16(raw, sub + 6)
                seg = segX2 // 2
                ends = [u16(raw, sub + 14 + j * 2) for j in range(seg)]
                starts = [u16(raw, sub + 16 + segX2 + j * 2) for j in range(seg)]
                deltas = [s16(raw, sub + 16 + segX2 * 2 + j * 2) for j in range(seg)]
                base = sub + 16 + segX2 * 3
                for j in range(seg):
                    for c in range(starts[j], min(ends[j], 0xFFFF) + 1):
                        if c == 0xFFFF:
                            continue
                        ro = u16(raw, base + j * 2)
                        if ro == 0:
                            g = (c + deltas[j]) & 0xFFFF
                        else:
                            g = u16(raw, base + j * 2 + ro + (c - starts[j]) * 2)
                            if g:
                                g = (g + deltas[j]) & 0xFFFF
                        if g:
                            best[c] = g
            elif fmt == 12:
                for j in range(u32(raw, sub + 12)):
                    q = sub + 16 + j * 12
                    s, e, g0 = u32(raw, q), u32(raw, q + 4), u32(raw, q + 8)
                    for c in range(s, e + 1):
                        best[c] = g0 + (c - s)
        return best

    def gid(self, ch):
        return self.cmap.get(ord(ch))

    # ---- 轮廓 ----
    def contours(self, gid, dx=0, dy=0, sx=1.0, sy=1.0, depth=0):
        """返回 [[(x,y,on), ...], ...]，坐标为原始字体单位（y 向上）。"""
        if gid is None or gid >= self.num_glyphs or depth > 5:
            return []
        start, end = self.loca[gid], self.loca[gid + 1]
        if end <= start:
            return []                       # 空字形，如空格
        g = self.d[self.glyf_off + start:self.glyf_off + end]
        nc = s16(g, 0)
        if nc >= 0:
            return self._simple(g, nc, dx, dy, sx, sy)
        return self._composite(g, dx, dy, sx, sy, depth)

    def _simple(self, g, nc, dx, dy, sx, sy):
        ends = [u16(g, 10 + i * 2) for i in range(nc)]
        npts = ends[-1] + 1
        p = 10 + nc * 2
        ilen = u16(g, p)
        p += 2 + ilen
        flags = []
        while len(flags) < npts:
            f = g[p]
            p += 1
            flags.append(f)
            if f & 8:
                rep = g[p]
                p += 1
                flags.extend([f] * rep)
        xs = []
        x = 0
        for f in flags:
            if f & 2:
                v = g[p]
                p += 1
                x += v if f & 16 else -v
            elif not f & 16:
                x += s16(g, p)
                p += 2
            xs.append(x)
        ys = []
        y = 0
        for f in flags:
            if f & 4:
                v = g[p]
                p += 1
                y += v if f & 32 else -v
            elif not f & 32:
                y += s16(g, p)
                p += 2
            ys.append(y)
        pts = [(xs[i] * sx + dx, ys[i] * sy + dy, bool(flags[i] & 1)) for i in range(npts)]
        out = []
        s = 0
        for e in ends:
            out.append(pts[s:e + 1])
            s = e + 1
        return out

    def _composite(self, g, dx, dy, sx, sy, depth):
        out = []
        p = 10
        while True:
            flags = u16(g, p)
            gi = u16(g, p + 2)
            p += 4
            argsAreWords = flags & 1
            if argsAreWords:
                a1, a2 = s16(g, p), s16(g, p + 2)
                p += 4
            else:
                a1, a2 = struct.unpack_from(">bb", g, p)
                p += 2
            ddx = ddy = 0
            if flags & 2:                       # ARGS_ARE_XY_VALUES
                ddx, ddy = a1, a2
            nsx, nsy = 1.0, 1.0
            if flags & 8:                       # SCALE
                nsx = nsy = s16(g, p) / 16384.0
                p += 2
            elif flags & 64:                    # X_AND_Y_SCALE
                nsx = s16(g, p) / 16384.0
                nsy = s16(g, p + 2) / 16384.0
                p += 4
            elif flags & 128:                   # 2x2
                nsx = s16(g, p) / 16384.0
                nsy = s16(g, p + 6) / 16384.0
                p += 8
            sub = self.contours(gi, dx + ddx * sx, dy + ddy * sy, sx * nsx, sy * nsy, depth + 1)
            out.extend(sub)
            if not flags & 32:                  # MORE_COMPONENTS
                break
        return out

    def outline(self, ch):
        """返回 (contours, advance)。contours 是 [[(x,y,on), ...], ...]，
        坐标为字体单位、y 向上、原点在基线左端。"""
        g = self.gid(ch)
        if g is None:
            return [], 0
        return self.contours(g), self._hmtx_adv[g]


def to_path(contours, upem, flipsign=-1.0):
    """二次轮廓 -> SVG 路径串。y 翻转（字体 y 向上，画布 y 向下）。
    连续 off-curve 点之间补隐含中点，这是 TrueType 的规则，漏了会缺角。"""
    def P(x, y):
        return "%.0f %.0f" % (x * 1000.0 / upem, y * flipsign * 1000.0 / upem)
    out = []
    for c in contours:
        if not c:
            continue
        n = len(c)
        # 起点：第一个 on-curve 点；若一个都没有，取最后两点中点
        st = None
        for i, pt in enumerate(c):
            if pt[2]:
                st = i
                break
        if st is None:
            mx = (c[-1][0] + c[0][0]) / 2.0
            my = (c[-1][1] + c[0][1]) / 2.0
            seq = [(mx, my, True)] + list(c)
        else:
            seq = list(c[st:]) + list(c[:st])
        d = ["M" + P(seq[0][0], seq[0][1])]
        i = 1
        while i < len(seq):
            pt = seq[i]
            if pt[2]:
                d.append("L" + P(pt[0], pt[1]))
                i += 1
            else:
                nxt = seq[(i + 1) % len(seq)] if i + 1 < len(seq) else seq[0]
                if nxt[2]:
                    d.append("Q" + P(pt[0], pt[1]) + " " + P(nxt[0], nxt[1]))
                    i += 2
                else:
                    mx = (pt[0] + nxt[0]) / 2.0
                    my = (pt[1] + nxt[1]) / 2.0
                    d.append("Q" + P(pt[0], pt[1]) + " " + P(mx, my))
                    i += 1
        d.append("Z")
        out.append("".join(d))
    return "".join(out)


def measure(font, s):
    """整串的排布：每个字的路径 + 前进宽度（em=1000 归一）"""
    f = Glyf(open(font, "rb").read())
    upem = f.upem
    glyphs = []
    x = 0.0
    for ch in s:
        cs, adv = f.outline(ch)
        if f.gid(ch) is None:
            raise SystemExit("字体里没有这个字：%s U+%04X" % (ch, ord(ch)))
        glyphs.append({"ch": ch, "path": to_path(cs, upem),
                       "adv": adv * 1000.0 / upem,
                       "x": x, "contours": len(cs),
                       "pts": sum(len(c) for c in cs)})
        x += adv * 1000.0 / upem
    return glyphs, x


if __name__ == "__main__":
    cmd, font = sys.argv[1], sys.argv[2]
    s = sys.argv[3]
    gs, total = measure(font, s)

    if cmd == "json":
        import json
        print(json.dumps({"total": total, "glyphs": gs}, ensure_ascii=False))
    elif cmd == "dump":
        for g in gs:
            print("%s  adv=%.1f  轮廓=%d  点=%d  path=%d 字符"
                  % (g["ch"], g["adv"], g["contours"], g["pts"], len(g["path"])))
        print("整串宽 = %.1f (em=1000)" % total)
    elif cmd == "svg":
        print('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 %.0f %.0f 1200" '
              'width="%.0f" height="1200">' % (-900, total, total))
        print('<rect x="0" y="-900" width="%.0f" height="1200" fill="#101216"/>' % total)
        print('<g fill="#fff">')
        for g in gs:
            if g["path"]:
                print('<path transform="translate(%.0f,0)" d="%s"/>' % (g["x"], g["path"]))
        print('</g></svg>')
    elif cmd == "kt":
        for g in gs:
            print('// %s  adv=%.1f' % (g["ch"], g["adv"]))
