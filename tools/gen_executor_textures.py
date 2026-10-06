# Generate textures for the SequenceAssemblyExecutor.
# - block/sequence_assembly_executor.png  : 16x16 machine with orange feed arrow
# - gui/sequence_assembly_executor.png    : copy of quantity_keeper GUI (same geometry) + accent tint
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


def draw_arrow(dr, cx, cy, color):
    # 5 wide right arrow centered (cx,cy)
    for i in range(5):
        dr.point((cx + i, cy), fill=color)
        if i < 4:
            dr.point((cx + i + 1, cy - 1), fill=color)
            dr.point((cx + i + 1, cy + 1), fill=color)


img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
for y in range(16):
    for x in range(16):
        c = (38, 46, 62, 255)          # 深蓝灰机壳
        if x < 1 or x > 14 or y < 1 or y > 14:
            c = (22, 27, 38, 255)      # 外框
        elif x < 2 or x > 13 or y < 2 or y > 13:
            c = (62, 74, 96, 255)      # 边框亮边
        elif y == 2 or y == 13:
            c = (52, 120, 160, 255)    # 上下青色饰条
        img.putpixel((x, y), c)
dr = ImageDraw.Draw(img)
draw_arrow(dr, 6, 8, (255, 168, 0, 255))       # 中左橙色进料箭头
draw_arrow(dr, 12, 8, (120, 220, 200, 255))    # 右前青色出料箭头（回流）
# 中央“合成”圆点（目标产物标记）
for dy in range(-1, 2):
    for dx in range(-1, 2):
        if dx * dx + dy * dy <= 2:
            img.putpixel((8 + dx, 5 + dy), (255, 168, 0, 255))
save(img, os.path.join("block", "sequence_assembly_executor.png"))

# GUI：复制 quantity_keeper 背景并叠加醒目色带，槽位几何完全一致
src_gui = os.path.join(OUT, "gui", "quantity_keeper.png")
if os.path.exists(src_gui):
    g = Image.open(src_gui).convert("RGBA")
    px = g.load()
    w, h = g.size
    for x in range(w):
        for y in range(6):
            r0, g0, b0, a0 = px[x, y]
            px[x, y] = (int(r0 * 0.5 + 40 * 0.5), int(g0 * 0.5 + 90 * 0.5), int(b0 * 0.5 + 130 * 0.5), a0)
    # 主区标题下方一条橙色分隔虚线（避免与任何槽位重叠；槽位起点 y>=18）
    for x in range(9, 170, 3):
        px[x, 17] = (255, 168, 0, 255)
    save(g, os.path.join("gui", "sequence_assembly_executor.png"))
else:
    print("WARN quantity_keeper.png not found, GUI texture skipped")
