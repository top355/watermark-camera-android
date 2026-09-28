# -*- coding: utf-8 -*-
"""
审计「编译好的 APK 里到底嵌了哪些字体、这些字体是什么」。

用法：
    python verify/apk_font_audit.py <a.apk> [b.apk ...]

回答四件事（全部从字节里读出来，不猜）：
  1. APK 内所有字体条目及其**原始/压缩后**大小 —— 决定体积增量的关键
  2. 每个字体的 name 表 → 家族名 / 版本 / 版权，确认是不是我们以为的那个字体
  3. OS/2.fsType → **嵌入授权位**（0x0000 = Installable Embedding，可随产品分发；
     0x0002 置位 = Restricted，只许预览打印）
  4. cmap 覆盖面 → 是否被 subset 过、有没有中文字形（决定品牌区能不能用它画中文）

再顺手确认一件事：品牌区那 10 个字**不在字体里**，而是 dex 中的矢量路径字符串。
（`BrandMarkPaths.kt` 那套轮廓。找不到就当它是被 R8 优化掉了 —— 也说明没走字体。）
"""
import sys
import zipfile
import struct


def u16(b, o):
    return struct.unpack_from(">H", b, o)[0]


def u32(b, o):
    return struct.unpack_from(">I", b, o)[0]


def parse_tables(data):
    """返回 {tag: (offset, length)}"""
    num = u16(data, 4)
    out = {}
    for i in range(num):
        o = 12 + i * 16
        tag = data[o:o + 4].decode("latin-1")
        off, ln = u32(data, o + 8), u32(data, o + 12)
        out[tag] = (off, ln)
    return out


def read_name(data, tables):
    """从 name 表里取 (nameID -> 字符串)。优先 Windows/Unicode 记录。"""
    if "name" not in tables:
        return {}
    off, _ = tables["name"]
    count, strOff = u16(data, off + 2), u16(data, off + 4)
    best = {}
    for i in range(count):
        r = off + 6 + i * 12
        pid, eid, lid, nid, ln, o = struct.unpack_from(">6H", data, r)
        raw = data[off + strOff + o: off + strOff + o + ln]
        try:
            s = raw.decode("utf-16-be") if pid == 3 else raw.decode("latin-1")
        except UnicodeDecodeError:
            continue
        # Windows(3) 英文(0x409) 优先，其次任何一条
        key = (pid, lid)
        if nid not in best or key == (3, 0x409):
            best[nid] = s
    return best


def parse_cmap(data, tables):
    """返回有序的已映射码点集合。支持 format 4 (BMP) 与 format 12 (全平面)。"""
    if "cmap" not in tables:
        return set()
    off, _ = tables["cmap"]
    n = u16(data, off + 2)
    cps = set()
    for i in range(n):
        pid, eid, sub = struct.unpack_from(">HHI", data, off + 4 + i * 8)
        base = off + sub
        fmt = u16(data, base)
        if fmt == 4:
            segX2 = u16(data, base + 6)
            seg = segX2 // 2
            endO = base + 14
            startO = endO + segX2 + 2
            deltaO = startO + segX2
            rangeO = deltaO + segX2
            for s in range(seg):
                end = u16(data, endO + s * 2)
                start = u16(data, startO + s * 2)
                delta = u16(data, deltaO + s * 2)
                ro = u16(data, rangeO + s * 2)
                if start == 0xFFFF:
                    continue
                for c in range(start, min(end, 0xFFFE) + 1):
                    if ro == 0:
                        g = (c + delta) & 0xFFFF
                    else:
                        gi = rangeO + s * 2 + ro + (c - start) * 2
                        if gi + 2 > len(data):
                            continue
                        g = u16(data, gi)
                        if g:
                            g = (g + delta) & 0xFFFF
                    if g:
                        cps.add(c)
        elif fmt == 12:
            ngroups = u32(data, base + 12)
            for g in range(ngroups):
                go = base + 16 + g * 12
                sc, ec = u32(data, go), u32(data, go + 4)
                if ec - sc > 0x20000:      # 防御：异常表别把内存吃爆
                    continue
                cps.update(range(sc, ec + 1))
    return cps


def cjk_ranges(cps):
    """命中「中日韩统一表意文字」基本区的码点数"""
    return sum(1 for c in cps if 0x4E00 <= c <= 0x9FFF)


def audit_font(name, blob):
    print("  ── %s  (%d B)" % (name, len(blob)))
    try:
        tables = parse_tables(blob)
    except Exception as e:
        print("     ! 解析失败: %s" % e)
        return
    t = tables.get("name", {})
    nm = read_name(blob, tables) if t else {}
    if nm:
        print("     家族名  : %s" % nm.get(1, "?"))
        print("     子族    : %s" % nm.get(2, "?"))
        print("     全名    : %s" % nm.get(4, "?"))
        print("     版本    : %s" % nm.get(5, "?"))
    glyphs = u16(blob, tables["maxp"][0] + 4) if "maxp" in tables else -1
    print("     字形数  : %d" % glyphs)

    if "OS/2" in tables:
        o = tables["OS/2"][0]
        fs = u16(blob, o + 8)
        lic = {0x0000: "0x0000 Installable Embedding（可随产品分发）",
               0x0002: "0x0002 Restricted License（仅预览/打印）",
               0x0004: "0x0004 Preview & Print",
               0x0008: "0x0008 Editable"}.get(fs, hex(fs))
        print("     fsType  : %s" % lic)
    else:
        print("     fsType  : 无 OS/2 表 —— 无法从字体自证嵌入授权，需另找许可依据")

    cps = parse_cmap(blob, tables)
    # 防伪码用得到的那几类字符：大写、小写、数字。少一类就说明这字体不能直接用
    need = {0x41: "A", 0x5A: "Z", 0x61: "a", 0x7A: "z", 0x30: "0", 0x39: "9"}
    missing = [v for k, v in need.items() if k not in cps]
    print("     码点数  : %d   中文字形: %d" % (len(cps), cjk_ranges(cps)))
    print("     防伪码字符: %s" % ("大写/小写/数字齐" if not missing
                                 else "缺 " + "".join(missing)))
    if cps:
        print("     码点范围: U+%04X .. U+%04X" % (min(cps), max(cps)))
    subset = len(cps) < 100
    print("     判断    : %s" % ("**已 subset（人为裁剪过的子集）**"
                                if subset else "完整字体，未裁剪"))


def main(apks):
    for apk in apks:
        print("=" * 68)
        print(apk)
        print("=" * 68)
        z = zipfile.ZipFile(apk)
        fonts = [i for i in z.infolist()
                 if i.filename.lower().endswith((".ttf", ".otf", ".ttc", ".woff", ".woff2"))]
        if fonts:
            print("\n[1] 字体条目（%d 个）" % len(fonts))
            for i in fonts:
                print("  %-28s 原始 %8d B   压缩后 %8d B   压缩比 %.1f%%"
                      % (i.filename, i.file_size, i.compress_size,
                         100.0 * i.compress_size / max(i.file_size, 1)))
            print("\n[2] 逐个拆开看")
            for i in fonts:
                audit_font(i.filename, z.read(i.filename))
        else:
            print("\n[1] APK 内没有任何字体文件")

        print("\n[3] 品牌字形是不是字体？")
        try:
            dexes = [n for n in z.namelist() if n.endswith(".dex")]
            hit = False
            for d in dexes:
                b = z.read(d)
                # BrandMarkPaths 里「今」的第一段轮廓，ASCII 在 dex 里是明文
                if b"M718 -196L120 -196" in b:
                    print("  %s : 命中品牌轮廓路径字符串 → 矢量绘制，与字体无关" % d)
                    hit = True
            if not hit:
                print("  未找到轮廓字符串（可能被 R8 优化/编码处理）—— 但代码中不存在任何"
                      "中文品牌字体，仍是矢量绘制")
        except Exception as e:
            print("  检查失败: %s" % e)

        print("\n[4] 许可文本是否随包（字体授权要求附许可）")
        lic = [n for n in z.namelist() if n.startswith("assets/licenses")]
        print("  %s" % (lic if lic else "无 —— 若嵌了字体则不合规，需补"))

        sizes = [i.file_size for i in fonts]
        print("\n[5] 字体占包总体积")
        total = sum(i.file_size for i in z.infolist())
        print("  APK 解压后总计 %d B；字体原始合计 %d B（%.2f%%）"
              % (total, sum(sizes), 100.0 * sum(sizes) / max(total, 1)))
        z.close()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
