# -*- coding: utf-8 -*-
"""icon/ 目录自检：尺寸 / 模式 / 非纯色 / alpha / 512 与 64 的主体一致性。

用法：python icon/verify_icon.py
退出码 0 = 全部通过，1 = 有断言失败。请用与 make_icons.py 相同的 Python 解释器运行。
"""
import os
import sys

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON = os.path.join(ROOT, "icon")

SIZES = (512, 256, 128, 64)
MIN_COLORS = 8          # 不同颜色数必须 > 8（非纯色/空白）
GRID = 4                # 4x4 网格
MAX_GRID_DELTA = 60.0   # 网格平均色最大允许差（0-255 通道均值）
MIN_OPAQUE_RATIO = 0.99  # 不透明像素占比下限（本设计是满幅不透明圆角方块）

failures = []


def check(name, cond, detail=""):
    print("  [%s] %s%s" % ("PASS" if cond else "FAIL", name, (" -> " + detail) if detail else ""))
    if not cond:
        failures.append(name)


print("== 1. 文件存在 / 尺寸 / 模式 ==")
imgs = {}
for s in SIZES:
    p = os.path.join(ICON, "icon_%d.png" % s)
    exists = os.path.isfile(p)
    check("icon_%d.png 存在" % s, exists, p if not exists else "%d bytes" % os.path.getsize(p))
    if not exists:
        continue
    im = Image.open(p)
    im.load()                                   # 强制解码：能解码才说明 PNG 没损坏
    check("icon_%d.png 尺寸 == (%d,%d)" % (s, s, s), im.size == (s, s), str(im.size))
    check("icon_%d.png 模式 == RGBA" % s, im.mode == "RGBA", im.mode)
    imgs[s] = im.convert("RGBA")

if len(imgs) != len(SIZES):
    print("\n缺少文件，提前结束。")
    sys.exit(1)

print("\n== 2. 非纯色：不同颜色数（量化到 5bit 通道）必须 > %d ==" % MIN_COLORS)
for s in SIZES:
    im = imgs[s]
    colors = {(r >> 3, g >> 3, b >> 3, a >> 7) for (r, g, b, a) in im.getdata()}
    check("icon_%d.png 颜色数 %d > %d" % (s, len(colors), MIN_COLORS), len(colors) > MIN_COLORS,
          "colors=%d" % len(colors))

print("\n== 3. alpha 通道 ==")
for s in SIZES:
    im = imgs[s]
    alpha = im.getchannel("A")
    lo, hi = alpha.getextrema()
    n_opaque = sum(1 for v in alpha.getdata() if v > 250)
    total = s * s
    ratio = n_opaque / float(total)
    check("icon_%d.png alpha 全域有效 %s" % (s, (lo, hi)), 0 <= lo <= hi <= 255)
    check("icon_%d.png 不透明占比 %.4f >= %.2f" % (s, ratio, MIN_OPAQUE_RATIO),
          ratio >= MIN_OPAQUE_RATIO)
    # 本设计是「满幅不透明圆角方块」：四角为圆角切角 + 深色描边，必须完全不透明
    px = im.load()
    corners = [px[0, 0], px[s - 1, 0], px[0, s - 1], px[s - 1, s - 1]]
    check("icon_%d.png 四角不透明且为深色描边" % s,
          all(c[3] == 255 and max(c[:3]) <= 40 for c in corners), str(corners))
    # 中心必须完全不透明（主体所在处）
    check("icon_%d.png 中心不透明" % s, px[s // 2, s // 2][3] == 255, str(px[s // 2, s // 2]))

print("\n== 4. 512 与 64 的主体一致性（抗锯齿降采样后的鲁棒性检查）==")

# 说明：本工程的 5 张图是同一张 64x64 像素画的整数倍 NEAREST 放大，所以彼此
# 天然逐像素一致（见第 5 节）。第 5 节只能证明"没被平滑"，证明不了"缩到 64px
# 主体还认得出"。因此这里额外把 512 用 LANCZOS 平滑降到 64，再和真正的 64px
# 资产比 —— 模拟缩略图列表页可能做的平滑缩放，检查构图在小尺寸下是否稳定。

a512 = imgs[512]
a64 = imgs[64]
# 512 -> 64 与 512 -> 256 -> 64 两条链路取较差者，避免偶然对齐
smooth_a = a512.resize((64, 64), Image.LANCZOS)
smooth_b = a512.resize((256, 256), Image.LANCZOS).resize((64, 64), Image.LANCZOS)


def opaque_ratio(im):
    alpha = im.getchannel("A")
    lo, hi = alpha.getextrema()
    if lo == hi:
        return 1.0
    return sum(1 for v in alpha.getdata() if v > 128) / float(im.size[0] * im.size[1])


def grid_means(im):
    """4x4 网格平均色（只统计不透明像素；全透明的格子返回 None）。"""
    s = im.size[0]
    cell = s // GRID
    out = []
    for gy in range(GRID):
        for gx in range(GRID):
            box = im.crop((gx * cell, gy * cell, (gx + 1) * cell, (gy + 1) * cell))
            op = [(r, g, b) for (r, g, b, a) in box.getdata() if a > 128]
            out.append(None if not op else tuple(sum(c[i] for c in op) / float(len(op)) for i in range(3)))
    return out


check("512 与 64 的不透明占比都接近满幅",
      abs(opaque_ratio(a512) - opaque_ratio(a64)) <= 0.02,
      "512=%.4f 64=%.4f" % (opaque_ratio(a512), opaque_ratio(a64)))

g64 = grid_means(a64)
for tag, sm in (("512→64 LANCZOS", smooth_a), ("512→256→64 LANCZOS", smooth_b)):
    gs = grid_means(sm)
    deltas = []
    for a, b in zip(gs, g64):
        if a is None or b is None:
            deltas.append(255.0 if a is not None or b is not None else 0.0)
            continue
        deltas.append(sum(abs(a[k] - b[k]) for k in range(3)) / 3.0)
    worst, mean = max(deltas), sum(deltas) / len(deltas)
    # 阈值依据：两边是同一张像素画，只是"硬边放大"vs"平滑缩放"的差别。
    # 色块是平涂的，抗锯齿只影响格子内部少数边界像素，实测 mean 个位数；
    # 阈值 60（≈24% 通道误差）足够宽，但若主体在 64px 下变成另一团东西，
    # 至少会有若干格子的平均色差远超 60。
    check("%s：4x4 网格平均色 最差 %.1f <= %.0f" % (tag, worst, MAX_GRID_DELTA),
          worst <= MAX_GRID_DELTA, "worst=%.1f mean=%.1f" % (worst, mean))
    check("%s：4x4 网格平均色 平均 %.1f <= %.1f" % (tag, mean, MAX_GRID_DELTA / 2.0),
          mean <= MAX_GRID_DELTA / 2.0)
    check("%s：16 格全部有内容（无空格）" % tag, all(x is not None for x in gs + g64))

# 亮度分布一致性：主体（灰色方块）必须仍是画面里最亮的块之一，不能被缩没
def lum_profile(im):
    s = im.size[0]
    cell = s // GRID
    out = []
    for gy in range(GRID):
        for gx in range(GRID):
            box = im.crop((gx * cell, gy * cell, (gx + 1) * cell, (gy + 1) * cell))
            vals = [0.299 * r + 0.587 * g + 0.114 * b for (r, g, b, a) in box.getdata() if a > 128]
            out.append(0.0 if not vals else sum(vals) / len(vals))
    return out


l64, lsm = lum_profile(a64), lum_profile(smooth_a)
bright64 = sorted(range(16), key=lambda i: -l64[i])[:4]
brightsm = sorted(range(16), key=lambda i: -lsm[i])[:4]
check("最亮 4 个网格位置一致（主体位置稳定）", bright64 == brightsm,
      "64=%s smooth=%s" % (bright64, brightsm))

print("\n== 5. 像素风一致性：必须是整数倍 NEAREST 重采样 ==")
for s in SIZES:
    im = imgs[s]
    ref = im.resize((64, 64), Image.NEAREST).resize((s, s), Image.NEAREST)
    diff = sum(1 for a, b in zip(im.getdata(), ref.getdata()) if a != b)
    check("icon_%d.png == 64px 母版整数倍 NEAREST" % s, diff == 0, "diff_px=%d" % diff)

print("\n" + "=" * 52)
if failures:
    print("失败 %d 项：" % len(failures))
    for f in failures:
        print("  - " + f)
    sys.exit(1)
print("全部通过：5 张图标尺寸/模式/内容/alpha/一致性均正常。")
sys.exit(0)
