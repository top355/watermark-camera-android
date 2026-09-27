#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""sfnt（TTF/OTF）字体探针 —— 只读，不依赖 fontTools。

用途：确认「今日水印相机字体」目录里哪个文件是什么、覆盖哪些字，
以及后面取字形轮廓（glyf 二次曲线 / CFF Type2 三次曲线）时需要的表是否齐。

用法：
  python detect_font.py info  <font> [<font> ...]
  python detect_font.py chars <font> 今日水印相机真实可验
"""
import struct
import sys

TAG = lambda b: b.decode("latin-1")


def u16(b, o):
    return struct.unpack_from(">H", b, o)[0]


def s16(b, o):
    return struct.unpack_from(">h", b, o)[0]


def u32(b, o):
    return struct.unpack_from(">I", b, o)[0]


class Sfnt:
    """把 sfnt 拆成 表字典 + 常用表的访问器。TTC 只取第 0 张脸。"""

    def __init__(self, data):
        self.d = data
        off = 0
        if data[:4] == b"ttcf":
            n = u32(data, 8)
            off = u32(data, 12)           # 第 0 张脸
            self.ttc_faces = n
        else:
            self.ttc_faces = 1
        self.flavor = data[off:off + 4]   # 0x00010000 / true / OTTO
        ntables = u16(data, off + 4)
        self.tables = {}
        for i in range(ntables):
            p = off + 12 + i * 16
            self.tables[TAG(data[p:p + 4])] = (u32(data, p + 8), u32(data, p + 12))

    def table(self, name):
        if name not in self.tables:
            return None
        o, n = self.tables[name]
        return self.d[o:o + n]

    # ---- 常用字段 ----
    def units_per_em(self):
        return u16(self.table("head"), 18)

    def num_glyphs(self):
        return u16(self.table("maxp"), 4)

    def face_names(self):
        """name 表里挑几个有意义的 ID：1 家族 2 子族 4 全名 6 PostScript 13 授权"""
        t = self.table("name")
        if not t:
            return {}
        cnt, strOff = u16(t, 2), u16(t, 4)
        want = {1: "family", 2: "subfamily", 4: "full", 6: "ps", 13: "license"}
        out = {}
        for i in range(cnt):
            p = 6 + i * 12
            pid, eid, lid, nid = u16(t, p), u16(t, p + 2), u16(t, p + 4), u16(t, p + 6)
            ln, lo = u16(t, p + 8), u16(t, p + 10)
            if nid not in want:
                continue
            raw = t[strOff + lo:strOff + lo + ln]
            try:
                # 3/1 与 0/* 是 UTF-16BE；3/0 是 symbol，也按 UTF-16BE 解
                s = raw.decode("utf-16-be") if pid in (0, 3) else raw.decode("latin-1")
            except Exception:
                s = repr(raw)
            out.setdefault(want[nid], s)
        return out

    def cmap(self):
        """返回 {codepoint: glyph_id}。合 format 0/4/6/12，后读的覆盖先读的。"""
        t = self.table("cmap")
        if not t:
            return {}
        n = u16(t, 2)
        best = {}
        for i in range(n):
            p = 4 + i * 8
            sub = u32(t, p + 4)
            fmt = u16(t, sub)
            if fmt == 4:
                segX2 = u16(t, sub + 6)
                seg = segX2 // 2
                ends = [u16(t, sub + 14 + j * 2) for j in range(seg)]
                starts = [u16(t, sub + 16 + segX2 + j * 2) for j in range(seg)]
                deltas = [s16(t, sub + 16 + segX2 * 2 + j * 2) for j in range(seg)]
                rangeOffBase = sub + 16 + segX2 * 3
                for j in range(seg):
                    for c in range(starts[j], min(ends[j], 0xFFFF) + 1):
                        if c == 0xFFFF:
                            continue
                        ro = u16(t, rangeOffBase + j * 2)
                        if ro == 0:
                            g = (c + deltas[j]) & 0xFFFF
                        else:
                            gp = rangeOffBase + j * 2 + ro + (c - starts[j]) * 2
                            g = u16(t, gp)
                            if g:
                                g = (g + deltas[j]) & 0xFFFF
                        if g:
                            best[c] = g
            elif fmt == 12:
                ngroups = u32(t, sub + 12)
                for j in range(ngroups):
                    q = sub + 16 + j * 12
                    s, e, g0 = u32(t, q), u32(t, q + 4), u32(t, q + 8)
                    for c in range(s, e + 1):
                        best[c] = g0 + (c - s)
            elif fmt == 6:
                first, cnt = u16(t, sub + 6), u16(t, sub + 8)
                for k in range(cnt):
                    best[first + k] = u16(t, sub + 10 + k * 2)
            elif fmt == 0:
                for k in range(256):
                    best[k] = t[sub + 6 + k]
        return best

    def hmtx(self):
        """返回 [(advance, lsb), ...]，长度 = numGlyphs（后段用最后一个 advance 补齐）"""
        t = self.table("hmtx")
        n = self.num_glyphs()
        hm = self.table("hhea")
        numH = u16(hm, 34) if hm else n
        out = []
        lastAdv = 0
        for i in range(n):
            if i < numH:
                adv = u16(t, i * 4)
                lsb = s16(t, i * 4 + 2)
                lastAdv = adv
            else:
                adv = lastAdv
                lsb = s16(t, numH * 4 + (i - numH) * 2)
            out.append((adv, lsb))
        return out


def info(path):
    d = open(path, "rb").read()
    f = Sfnt(d)
    nm = f.face_names()
    cm = f.cmap()
    cjk = [c for c in cm if 0x4E00 <= c <= 0x9FFF]
    flavor = f.flavor.decode("latin-1") if f.flavor != b"\x00\x01\x00\x00" else "truetype(00010000)"
    print("=" * 78)
    print("文件      %s  (%d B)" % (path, len(d)))
    print("外壳      %s   faces=%d" % (flavor, f.ttc_faces))
    print("表        %s" % " ".join(sorted(f.tables)))
    print("家族      %s" % nm.get("family", "?"))
    print("全名      %s" % nm.get("full", "?"))
    print("PostScript %s" % nm.get("ps", "?"))
    if "license" in nm:
        lic = nm["license"].replace("\n", " ")
        print("授权      %s" % (lic[:150] + ("…" if len(lic) > 150 else "")))
    print("upem      %d   glyphs=%d   映射码位=%d   CJK汉字=%d"
          % (f.units_per_em(), f.num_glyphs(), len(cm), len(cjk)))
    # 探针字：这几个字到位了才谈得上替换右下角
    for probe in ("今日水印相机真实可验"):
        g = cm.get(ord(probe))
        print("    %s U+%04X -> %s" % (probe, ord(probe), ("gid %d" % g) if g else "**缺失**"))
    has_cff = "CFF " in f.tables or "CFF2" in f.tables
    print("轮廓       %s" % ("CFF/Type2（三次曲线）" if has_cff else "glyf（二次曲线）"))
    return f


def chars(path, s):
    f = Sfnt(open(path, "rb").read())
    cm = f.cmap()
    for ch in s:
        g = cm.get(ord(ch))
        print("%s U+%04X gid=%s" % (ch, ord(ch), g))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "info":
        for p in sys.argv[2:]:
            info(p)
    else:
        chars(sys.argv[2], sys.argv[3])
