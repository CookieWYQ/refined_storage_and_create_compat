# -*- coding: utf-8 -*-
"""实测本模组既有方块贴图的调色板 / 尺寸 / 模式（只读，不写任何贴图）。

用途：给「新方块贴图」任务提供**可复跑的调色板事实**，而不是靠肉眼猜。
输出：
  1. 逐文件的 尺寸 / PNG 模式 / alpha 情况 / 唯一色数 / 主色 top-8；
  2. 全量色彩直方图 → 全工程共用的「机壳灰阶」；
  3. 每个方块的「强调色」（最饱和且够亮的像素）= 该机器在家族里的辨识色；
  4. RS 参考机壳 tmp_rs_ref2/block/side.png 的像素直方图（新贴图的底图来源）。

用法：
    python tools/analyze_block_palette.py            # 打印报告
    python tools/analyze_block_palette.py --md FILE  # 同时写 markdown 报告
"""
import argparse
import os
import sys
from collections import Counter

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX_BLOCK = os.path.join(ROOT, "src", "main", "resources", "assets",
                         "rs_create_compat", "textures", "block")
RS_BASE = os.path.join(ROOT, "tmp_rs_ref2", "block", "side.png")
RS_AUTOCRAFTER = os.path.join(ROOT, "tmp_rs_ref2", "block", "autocrafter")
RS_MANAGER = os.path.join(ROOT, "tmp_rs_ref2", "block", "autocrafter_manager")


def hexc(c):
    return "#%02X%02X%02X" % (c[0], c[1], c[2])


def open_rgba(path):
    return Image.open(path).convert("RGBA")


def histogram(img):
    """(count, color) 列表 —— 用 getcolors 而不是已废弃的 getdata。"""
    return Counter(dict((c, n) for (n, c) in img.getcolors(maxcolors=1 << 24)))


def sat_of(c):
    return max(c[0], c[1], c[2]) - min(c[0], c[1], c[2])


def accent_of(img):
    """最饱和且够亮的像素 = 该图的强调色（与 tools/gen_block_face_split.py 的口径一致）。"""
    best, best_sat = None, 0
    for (r, g, b, a), _n in histogram(img).items():
        if a < 128:
            continue
        hi = max(r, g, b)
        sat = hi - min(r, g, b)
        if sat > best_sat and hi > 90:
            best_sat, best = sat, (r, g, b, 255)
    return best


def block_id_of(name):
    """去掉 _active/_inactive/_front/... 后缀，得到方块 id。"""
    stem = name[:-4]  # .png
    for suffix in ("_active", "_inactive"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
    for face in ("_front", "_back", "_left", "_right", "_side", "_top", "_bottom"):
        if stem.endswith(face):
            stem = stem[: -len(face)]
    return stem


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--md", default=None, help="同时写一份 markdown 报告到该路径")
    args = ap.parse_args()

    lines = []

    def w(s=""):
        print(s)
        lines.append(s)

    names = sorted(n for n in os.listdir(TEX_BLOCK) if n.endswith(".png"))
    w("# 既有方块贴图调色板实测报告（由 tools/analyze_block_palette.py 生成）")
    w()
    w("机器可读结论：全部 `%d` 张方块贴图，尺寸 / 模式 / alpha 如下。" % len(names))
    w()

    global_hist = Counter()
    per_block_accent = {}
    problems = []
    flat_only = []

    w("## 1. 逐文件实测")
    w()
    w("| 文件 | 尺寸 | PNG 模式 | alpha 非 0 像素 | 唯一色数 | 主色 top-4 | 强调色 |")
    w("| --- | --- | --- | --- | --- | --- | --- |")
    for n in names:
        p = os.path.join(TEX_BLOCK, n)
        raw = Image.open(p)
        mode = raw.mode
        img = raw.convert("RGBA")
        cols = img.getcolors(maxcolors=1 << 24)
        hist = Counter(dict((c, n) for (n, c) in cols))
        global_hist.update(hist)
        opaque = sum(n for (n, c) in cols if c[3] > 0)
        top = [c for (_n, c) in sorted(cols, reverse=True)[:4]]
        acc = accent_of(img)
        tops = " ".join(hexc(t) for t in top)
        w("| `%s` | %d×%d | %s | %d | %d | %s | %s |" % (
            n, img.width, img.height, mode, opaque, len(hist), tops,
            hexc(acc) if acc else "—"))
        if img.size != (16, 16):
            problems.append("%s 尺寸不是 16×16（实测 %d×%d）" % (n, img.width, img.height))
        if mode != "RGBA":
            problems.append("%s PNG 模式不是 RGBA（实测 %s）" % (n, mode))
        if len(hist) < 2:
            # separation_frame / camouflage_frame 是**刻意**的纯色装饰框（既有贴图），
            # 不是本任务的产出，只作信息报告，不算失败。
            flat_only.append(n)
        bid = block_id_of(n)
        if acc is not None:
            per_block_accent.setdefault(bid, set()).add(acc)

    w()
    w("## 2. 全工程共用灰阶（色彩直方图 top-16）")
    w()
    w("| 色值 | 出现次数 | 角色（人工判断） |")
    w("| --- | --- | --- |")
    roles = {
        hexc((20, 20, 20)): "描边 DARK（gen_block_face_split 常量）",
        hexc((17, 19, 22)): "凹槽 RECESS",
        hexc((122, 122, 122)): "高光 LIGHT",
        hexc((34, 34, 34)): "次暗 DARK2",
    }
    for px, cnt in global_hist.most_common(16):
        w("| `%s` | %d | %s |" % (hexc(px), cnt, roles.get(hexc(px), "")))

    w()
    w("## 3. 每个方块自己的强调色（最饱和像素）")
    w()
    w("| 方块 id | 强调色候选（active / inactive 合并去重） |")
    w("| --- | --- |")
    for bid in sorted(per_block_accent):
        cols = sorted(per_block_accent[bid])
        w("| `%s` | %s |" % (bid, " ".join("`%s`" % hexc(c) for c in cols)))

    w()
    w("## 4. RS 参考机壳（新贴图的底图来源）")
    w()
    w("| 文件 | 尺寸 | 模式 | 主色 top-8 |")
    w("| --- | --- | --- | --- |")
    for p in [RS_BASE,
              os.path.join(RS_AUTOCRAFTER, "side.png"),
              os.path.join(RS_AUTOCRAFTER, "top.png")] + [
              os.path.join(RS_MANAGER, f) for f in
              ("front.png", "back.png", "left.png", "right.png", "top.png")]:
        if not os.path.exists(p):
            w("| `%s` | 缺失 | — | — |" % os.path.relpath(p, ROOT).replace(os.sep, "/"))
            continue
        raw = Image.open(p)
        img = raw.convert("RGBA")
        hist = Counter(img.getdata())
        tops = " ".join(hexc(px) for px, _c in hist.most_common(8))
        w("| `%s` | %d×%d | %s | %s |" % (
            os.path.relpath(p, ROOT).replace(os.sep, "/"),
            img.width, img.height, raw.mode, tops))

    w()
    w("## 5. 自动断言")
    w()
    if problems:
        w("发现 %d 处异常：" % len(problems))
        for s in problems:
            w("- %s" % s)
    else:
        w("全部 %d 张：尺寸 16×16、模式 RGBA。**0 异常**。" % len(names))
    if flat_only:
        w()
        w("仅信息报告（既有装饰框，刻意单色，不计失败）：%s"
          % "、".join("`%s`" % n for n in flat_only))

    if args.md:
        out = os.path.abspath(args.md)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8", newline="\n") as h:
            h.write("\n".join(lines) + "\n")
        print("\n[OK] 报告已写出 %s" % out)

    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
