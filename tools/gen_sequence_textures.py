# Generate simple placeholder textures for new items/blocks.
# 序列装配样板终端方块 / 单元样板 / 装配样板 贴图生成
import os
from PIL import Image, ImageDraw
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets",
                   "rs_create_compat", "textures")

def save(img, rel):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    print("wrote", rel)

def hline(d, x0, x1, y, c):
    d.line([(x0, y), (x1, y)], fill=c)

def vline(d, x, y0, y1, c):
    d.line([(x, y0), (x, y1)], fill=c)

# ---------- 方块：序列装配样板终端 ----------
# 深灰蓝机壳 + 中央"样板纸"（浅色）+ 序列步骤圆点
b = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(b)
for y in range(16):
    for x in range(16):
        c = (34, 40, 48, 255)          # 深灰底
        if x < 1 or x > 14 or y < 1 or y > 14:
            c = (24, 28, 34, 255)      # 外框
        elif x < 2 or x > 13 or y < 2 or y > 13:
            c = (56, 66, 78, 255)      # 边框亮边
        elif 5 <= x <= 10 and 4 <= y <= 11:
            c = (214, 206, 188, 255)   # 中央样板纸
        elif 3 <= x <= 12 and (y == 2 or y == 13):
            c = (90, 104, 120, 255)    # 上下装饰条
        b.putpixel((x, y), c)
# 样板纸上的序列步骤圆点（4 个小点）
for (px, py) in [(6, 5), (9, 5), (6, 8), (9, 8)]:
    for dx in range(-1, 2):
        for dy in range(-1, 2):
            if dx * dx + dy * dy <= 2:
                b.putpixel((px + dx, py + dy), (255, 168, 0, 255))
# 纸面连接线（表示流程）
for x in range(7, 9):
    b.putpixel((x, 6), (120, 110, 90, 255))
    b.putpixel((x, 7), (120, 110, 90, 255))
save(b, os.path.join("block", "sequence_pattern_terminal.png"))

# ---------- 物品：单元样板（纸卷 + 操作箭头） ----------
u = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(u)
# 纸卷
for y in range(2, 15):
    for x in range(3, 13):
        c = (226, 220, 205, 255)
        if x == 3 or x == 12:
            c = (150, 138, 116, 255)
        elif y == 2 or y == 14:
            c = (196, 188, 168, 255)
        elif x == 7:
            c = (206, 198, 180, 255)  # 中线
        u.putpixel((x, y), c)
# 冲压箭头（向下箭头，橙色）
for i in range(4):
    u.putpixel((7, 4 + i), (255, 168, 0, 255))
    if i < 3:
        u.putpixel((6, 5 + i), (255, 168, 0, 255))
        u.putpixel((8, 5 + i), (255, 168, 0, 255))
save(u, os.path.join("item", "sequence_unit_pattern.png"))

# ---------- 物品：装配样板（纸卷 + 链条） ----------
a = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(a)
for y in range(2, 15):
    for x in range(3, 13):
        c = (226, 220, 205, 255)
        if x == 3 or x == 12:
            c = (150, 138, 116, 255)
        elif y == 2 or y == 14:
            c = (196, 188, 168, 255)
        elif x == 7:
            c = (206, 198, 180, 255)
        a.putpixel((x, y), c)
# 链条：两个圆环
for (cx, cy, r) in [(5, 8, 2), (10, 8, 2)]:
    for dx in range(-r, r + 1):
        for dy in range(-r, r + 1):
            dist = dx * dx + dy * dy
            if 1 <= dist <= r * r:
                a.putpixel((cx + dx, cy + dy), (90, 104, 120, 255))
# 连接
for x in range(7, 9):
    for y in range(7, 10):
        if x == 7 or x == 8:
            a.putpixel((x, y), (90, 104, 120, 255))
save(a, os.path.join("item", "sequence_assembly_pattern.png"))
