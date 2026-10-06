# -*- coding: utf-8 -*-
"""rs_create_compat 发布图标合成脚本（纯拼贴：素材全部取自本仓库，无联网、无外绘图）。

设计要点
  * 逻辑画布 = 64x64（1x）；512/256/128/64 全部用 **NEAREST 整数倍**重采样
    （8x / 4x / 2x / 1x）—— 5 张图是**完全同一张像素画**，没有平滑/抗锯齿。
  * 对角线二分：左上 = 机械动力（黄铜机壳 + 铜齿轮），右下 = 精致存储
    （暗色机壳 + 青色电路 + 青色分界线）。
  * 主体 = 本模组最有代表性的「序列执行仓」真实方块贴图，等轴测拼出三面；
    刻意放大到约占画布宽 2/3，压在分界线上，因此 64px 下依然认得出。
  * 配色取自仓库内已有贴图的真实像素值（十六进制见 icon/icon_README.md）。

用法：python icon/make_icons.py
"""
import math
import os
import sys
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "icon")
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures")

# ------------------------------------------------------------------ 调色板（真实贴图采样）
BRASS_BASE = (114, 71, 49)      # create textures/block/brass_casing.png
BRASS_DARK = (58, 36, 17)       # 同上
BRASS_MID = (95, 59, 40)        # 同上
BRASS_LIGHT = (215, 170, 94)    # create textures/block/brass_block.png
BRASS_GLEAM = (255, 235, 140)   # create textures/block/brass_encased_cogwheel_side.png
COG = (104, 78, 46)             # create textures/block/cogwheel.png
COG_DARK = (66, 48, 28)
COG_LIGHT = (140, 104, 60)

RS_CYAN = (2, 221, 230)         # 执行仓 ACTIVE 强调色 #02DDE6
RS_CYAN_SOFT = (0, 150, 160)
RS_CYAN_PALE = (150, 250, 255)
CIRCUIT_BASE = (52, 120, 160)   # block/sequence_assembly_executor.png 的青色饰条
CIRCUIT_DARK = (18, 22, 28)
RS_SLAB_A = (22, 27, 34)
RS_SLAB_B = (28, 34, 42)
DARK = (16, 16, 16)
HOT = (255, 168, 0)             # block/sequence_assembly_executor.png 橙色进料箭头

S = 64

# ------------------------------------------------------------------ 工具
def new_mask():
    return Image.new("1", (S, S), 0)


def cut_corners(mask, fill_value, r):
    """矩形四角按 r 级阶梯切角（1=填 / 0=挖）。"""
    px = mask.load()
    for i in range(r):
        for j in range(r - i):
            for (x, y) in ((i, j), (S - 1 - i, j), (i, S - 1 - j), (S - 1 - i, S - 1 - j)):
                px[x, y] = fill_value
    return mask


def paste_mask(img, mask, color):
    img.paste(color + (255,), (0, 0), mask)


def brighten(img, mask, mul, add):
    px = img.load()
    for y in range(S):
        for x in range(S):
            if mask.getpixel((x, y)):
                r, g, b, a = px[x, y]
                px[x, y] = (min(255, int(r * mul) + add), min(255, int(g * mul) + add),
                            min(255, int(b * mul) + add), a)


# ------------------------------------------------------------------ 背景
bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
px = bg.load()

# 右下 RS 侧：暗色机壳 + 2px 棋盘微纹理
for y in range(S):
    for x in range(S):
        if x + y > S - 2:
            px[x, y] = (RS_SLAB_A if ((x // 2 + y // 2) % 2 == 0) else RS_SLAB_B) + (255,)

# 左上 Create 侧：黄铜机壳（噪点 + 板缝）
for y in range(S):
    for x in range(S):
        if x + y <= S - 2:
            n = (x * 7 + y * 13) % 11
            c = BRASS_BASE
            if n == 0:
                c = BRASS_LIGHT
            elif n == 3:
                c = BRASS_DARK
            elif n == 7:
                c = BRASS_MID
            px[x, y] = c + (255,)
for d in (11, 30):
    for t in range(S):
        for (x, y) in ((d, t), (t, d)):
            if x < S and y < S and x + y <= S - 2:
                px[x, y] = BRASS_DARK + (255,)

# 左上主齿轮（大部分被主体方块遮住 → "咬合"感）
def draw_cog(cx, cy, rad, hub, teeth_n):
    disc, teeth = [], set()
    for y in range(S):
        for x in range(S):
            if (x - cx) ** 2 + (y - cy) ** 2 <= rad * rad:
                disc.append((x, y))
    for i in range(teeth_n):
        a = 2 * math.pi * i / teeth_n + math.pi / teeth_n
        for rr in (rad, rad + 1, rad + 2):
            for dt in (-0.5, 0.0, 0.5):
                teeth.add((int(round(cx + rr * math.cos(a + dt / rr))),
                           int(round(cy + rr * math.sin(a + dt / rr)))))
    ring = set(disc) | teeth
    for (x, y) in list(ring):
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                nx, ny = x + dx, y + dy
                if 0 <= nx < S and 0 <= ny < S and (nx, ny) not in ring:
                    px[nx, ny] = COG_DARK + (255,)
    for (x, y) in sorted(teeth):
        px[x, y] = (COG_LIGHT if (x + y) % 2 == 0 else COG) + (255,)
    for (x, y) in sorted(disc):
        d = math.hypot(x - cx, y - cy)
        if d <= hub - 1:
            c = BRASS_DARK
        elif d <= hub + 0.5:
            c = COG_DARK
        elif d >= rad - 1.6 and (x - cx) + (y - cy) < 0:
            c = COG_LIGHT
        else:
            c = COG
        px[x, y] = c + (255,)
    for y in range(S):
        for x in range(S):
            if (x - cx) ** 2 + (y - cy) ** 2 <= 1.4:
                px[x, y] = DARK + (255,)


draw_cog(14, 14, 13, 4, 8)

# 右下 RS 侧：**两条**粗青色电路（暗边 + 亮芯），其余留空 → 64px 不糊
def trace(x0, y0, x1, y1):
    for off, col, half in ((3, CIRCUIT_DARK, 2), (1, RS_CYAN_SOFT, 1), (0, RS_CYAN, 0)):
        for t in range(-half, half + 1):
            if x0 == x1:
                for y in range(min(y0, y1) - off, max(y0, y1) + off + 1):
                    x = x0 + t
                    if 0 <= x < S and 0 <= y < S and x + y > S - 2:
                        px[x, y] = col + (255,)
            else:
                for x in range(min(x0, x1) - off, max(x0, x1) + off + 1):
                    y = y0 + t
                    if 0 <= x < S and 0 <= y < S and x + y > S - 2:
                        px[x, y] = col + (255,)


trace(48, 30, 61, 30)
trace(55, 30, 55, 61)
trace(36, 50, 61, 50)
for (nx, ny) in ((55, 30), (48, 30)):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            x, y = nx + dx, ny + dy
            if 0 <= x < S and 0 <= y < S and x + y > S - 2:
                px[x, y] = (RS_CYAN_PALE if (dx == 0 and dy == 0) else RS_CYAN) + (255,)

# 对角分界线：暗 / 青 / 暗 硬边 —— 左右分区在 64px 下依然一眼可读
for y in range(S + 1):
    x = S - 2 - y
    for (dx, col) in ((-1, CIRCUIT_DARK), (0, RS_CYAN), (1, (10, 64, 70))):
        xx = x + dx
        if 0 <= xx < S and 0 <= y < S:
            px[xx, y] = col + (255,)

# ------------------------------------------------------------------ 主体：序列执行仓（等轴测三面）
F_TOP = Image.open(os.path.join(TEX, "block", "unit_pattern_manager_top_active.png")).convert("RGBA")
F_LEFT = Image.open(os.path.join(TEX, "block", "sequence_execution_chamber_front_active.png")).convert("RGBA")
F_RIGHT = Image.open(os.path.join(TEX, "block", "sequence_execution_chamber_back_active.png")).convert("RGBA")

TX, TY, HW, QV, DZ = 32, 22, 16, 9, 22
TOP = [(TX, TY - QV), (TX + HW, TY), (TX, TY + QV), (TX - HW, TY)]

m_top, m_left, m_right = new_mask(), new_mask(), new_mask()


def fill_rhombus(mask, cx, cy, hw, qh, tex, lighten=0):
    """等轴测菱形铺贴图：NEAREST 取样，硬边。lighten>0 时额外提亮（顶面受光）。"""
    mp, tp = mask.load(), tex.load()
    tw, th = tex.size
    for y in range(S):
        for x in range(S):
            dx, dy = x - cx, y - cy
            s, t = dx / hw + dy / qh, -dx / hw + dy / qh
            if -1.0 <= s <= 1.0 and -1.0 <= t <= 1.0:
                u = min(tw - 1, max(0, int((s + 1) * 0.5 * tw)))
                v = min(th - 1, max(0, int((t + 1) * 0.5 * th)))
                r, g, b, a = tp[u, v]
                if lighten:
                    r = min(255, r + lighten)
                    g = min(255, g + lighten)
                    b = min(255, b + lighten)
                mp[x, y] = 1
                px[x, y] = (r, g, b, a)


def fill_quad(mask, p0, p1, p2, p3, tex):
    """竖直四边形（侧面）：逐行线性插值，NEAREST 取样，硬边。"""
    mp, tp = mask.load(), tex.load()
    tw, th = tex.size
    for y in range(min(p[1] for p in (p0, p1, p2, p3)), max(p[1] for p in (p0, p1, p2, p3)) + 1):
        v = min(1.0, max(0.0, (y - p0[1]) / float(DZ)))
        xl = p0[0] + (p3[0] - p0[0]) * v
        xr = p1[0] + (p2[0] - p1[0]) * v
        xa, xb = int(round(min(xl, xr))), int(round(max(xl, xr)))
        for x in range(xa, xb + 1):
            if 0 <= x < S and 0 <= y < S:
                u = 0.0 if xb == xa else (x - xa) / float(xb - xa)
                mp[x, y] = 1
                px[x, y] = tp[min(tw - 1, int(u * tw)), min(th - 1, int(v * th))]


# 先侧面后顶面（顶面覆盖接缝）
fill_quad(m_left, TOP[3], TOP[2], (TOP[2][0], TOP[2][1] + DZ), (TOP[3][0], TOP[3][1] + DZ),
          F_LEFT.resize((HW, DZ), Image.NEAREST))
fill_quad(m_right, TOP[2], TOP[1], (TOP[1][0], TOP[1][1] + DZ), (TOP[2][0], TOP[2][1] + DZ),
          F_RIGHT.resize((HW, DZ), Image.NEAREST))
fill_rhombus(m_top, TX, TY, HW, QV, F_TOP.resize((HW * 2, QV * 2), Image.NEAREST), lighten=30)

brighten(bg, m_left, 0.68, 0)
brighten(bg, m_right, 1.32, 18)

# 方块整体黑描边 → 换到任何背景上都能"抠"出来
solid = new_mask()
for m in (m_top, m_left, m_right):
    solid.paste(m, (0, 0), m)
edge = new_mask()
sp, ep = solid.load(), edge.load()
for y in range(S):
    for x in range(S):
        if sp[x, y]:
            continue
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                nx, ny = x + dx, y + dy
                if 0 <= nx < S and 0 <= ny < S and sp[nx, ny]:
                    ep[x, y] = 1
paste_mask(bg, edge, DARK)

# 顶面朝右上的棱加一条更亮的 1px 高光，把方块从暗色 RS 背景里"顶"出来
for i in range(0, HW + 1):
    x, y = TX + i, TY - (QV * i) // HW
    if 0 <= x < S and 0 <= y < S and sp[x, y]:
        r, g, b, a = px[x, y]
        px[x, y] = (min(255, int(r * 1.15) + 22), min(255, int(g * 1.15) + 22),
                    min(255, int(b * 1.15) + 22), a)

# 正面接口的青色发光点缀 + 一枚橙色进料标记（取自 sequence_assembly_executor 的配色）
for (gx, gy, col) in ((TX - 12, TY + 8, RS_CYAN), (TX - 12, TY + 9, RS_CYAN),
                      (TX - 11, TY + 8, RS_CYAN), (TX - 11, TY + 9, RS_CYAN),
                      (TX + 11, TY + 6, RS_CYAN), (TX + 11, TY + 7, HOT)):
    if 0 <= gx < S and 0 <= gy < S and sp[gx, gy]:
        px[gx, gy] = col + (255,)

# ------------------------------------------------------------------ 外框：圆角 + 暗描边 + 青色内亮线
# 商店页缩略图背景有深有浅：把圆角切掉的部分显式填成深色描边色（不透明圆角方块），
# 比透明圆角更稳，也不会出现"方形底 + 圆角框"的割裂感。
corner = new_mask()
cp = corner.load()
for i in range(7):
    for j in range(7 - i):
        for (x, y) in ((i, j), (S - 1 - i, j), (i, S - 1 - j), (S - 1 - i, S - 1 - j)):
            cp[x, y] = 1
paste_mask(bg, corner, DARK)
px = bg.load()
# 圆角外沿全部压平为同一深色（避免对角分界线/底色渗进圆角造成花边）
for i in range(7):
    for j in range(7 - i):
        for (x, y) in ((i, j), (S - 1 - i, j), (i, S - 1 - j), (S - 1 - i, S - 1 - j)):
            px[x, y] = DARK + (255,)

ring = new_mask()
rp = ring.load()
for y in range(S):
    for x in range(S):
        if x < 2 or y < 2 or x > S - 3 or y > S - 3:
            rp[x, y] = 1
cut_corners(ring, 0, 7)
paste_mask(bg, ring, DARK)

inner = new_mask()
ip = inner.load()
for y in range(S):
    for x in range(S):
        if x == 2 or y == 2 or x == S - 3 or y == S - 3:
            ip[x, y] = 1
cut_corners(inner, 0, 8)
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
paste_mask(glow, inner, RS_CYAN_SOFT)
glow.putalpha(glow.getchannel("A").point(lambda a: 120 if a else 0))
bg.alpha_composite(glow)

# 四角黄铜铆钉（机械动力侧的"铆接"语汇，同时把外框钉住）
for (rx, ry) in ((6, 6), (S - 7, 6), (6, S - 7), (S - 7, S - 7)):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if 0 <= rx + dx < S and 0 <= ry + dy < S:
                px[rx + dx, ry + dy] = BRASS_DARK + (255,)
    px[rx, ry] = BRASS_GLEAM + (255,)

master = bg

# ------------------------------------------------------------------ 导出（整数倍 NEAREST）
written = []
for s in (512, 256, 128, 64):
    f = s // S
    assert s % S == 0 and f in (1, 2, 4, 8), s
    out = master.resize((s, s), Image.NEAREST)
    assert out.size == (s, s) and out.mode == "RGBA"
    path = os.path.join(OUT, "icon_%d.png" % s)
    out.save(path, "PNG", optimize=True)
    written.append((path, out.size, out.mode, os.path.getsize(path)))

for path, size, mode, n in written:
    print("wrote %-24s %s %s %d bytes" % (os.path.relpath(path, ROOT), size, mode, n))
