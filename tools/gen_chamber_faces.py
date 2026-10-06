# -*- coding: utf-8 -*-
"""序列执行舱贴图重做（照 RS 自动合成舱 Autocrafter 的结构）：

* **四个侧面**（模型 N/E/S/W）：共用**一张带指向性**的贴图 —— 中央一枚**指向前端面**的箭头，
  模型按 RS/原版「前端面 = 模型 UP 面」的约定烘焙（见 blockstates 的 x:90 系列旋转），
  因此箭头在游戏内始终指向「机器侧 / 朝向」。
* **前端面**（模型 UP，指向机器）：独立贴图 —— 带发光方形接口的「连接口」。
* **背面**（模型 DOWN）：独立贴图 —— 散热格栅。
* active / inactive 各一套（原版网络 ticker 的 ACTIVE 属性），共 6 张，**互不共用**。

底图沿用工程内其它方块的 RS 灰色机壳（tmp_rs_ref2/block/side.png），保证与 RS 自动合成舱协调。
输出：src/main/resources/assets/rs_create_compat/textures/block/  +  tmp_textures/chamber_faces/ 预览。

用法：python tools/gen_chamber_faces.py
"""
import os
import numpy as np
from PIL import Image
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_SIDE = os.path.join(ROOT, "tmp_rs_ref2", "block", "side.png")
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "block")
PREVIEW = os.path.join(ROOT, "tmp_textures", "chamber_faces")

NS = "rs_create_compat:block/"

# ---- 调色板（灰阶沿用 RS 机壳；强调色 = 本模组执行舱的青色） ----
DARK = (20, 20, 20, 255)
DARK2 = (34, 34, 34, 255)
RECESS = (17, 19, 22, 255)
LIGHT = (122, 122, 122, 255)

ACCENT_ON = (2, 221, 230, 255)
ACCENT_ON_SOFT = (0, 150, 160, 255)


def dim(c, factor):
    return (int(c[0] * factor), int(c[1] * factor), int(c[2] * factor), 255)


ACCENT_OFF = dim(ACCENT_ON, 0.35)
ACCENT_OFF_SOFT = dim(ACCENT_ON_SOFT, 0.45)


def base():
    """RS 灰色机壳底图（16×16）。"""
    return np.array(Image.open(BASE_SIDE).convert("RGBA")).copy()


def setpx(arr, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        arr[y, x] = c


def outline(arr, shape, color):
    """给 shape 像素集合外侧描一圈 dark 边（不在 shape 内、且尚未被 shape 覆盖的 8 邻域）。"""
    out = set()
    for (x, y) in shape:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                p = (x + dx, y + dy)
                if p not in shape:
                    out.add(p)
    for (x, y) in out:
        setpx(arr, x, y, color)


# =================================================================
# 侧面：一枚指向前端面（模型 UP）的箭头 —— 四个侧面共用这一张
# =================================================================
def side_tex(active):
    arr = base()
    accent = ACCENT_ON if active else ACCENT_OFF
    shape = set()
    # 箭头头部（向上，逐行加宽）
    for x in (7, 8):
        shape.add((x, 3))
    for x in range(6, 10):
        shape.add((x, 4))
    for x in range(5, 11):
        shape.add((x, 5))
    for x in range(4, 12):
        shape.add((x, 6))
    # 箭杆
    for y in range(7, 11):
        shape.add((7, y))
        shape.add((8, y))
    outline(arr, shape, DARK)
    for (x, y) in sorted(shape, key=lambda p: (p[1], p[0])):
        setpx(arr, x, y, accent)
    # 底部一道机壳接缝（有指向性但左右对称，不与箭头冲突）
    for x in range(4, 12):
        setpx(arr, x, 12, DARK2)
    return arr


# =================================================================
# 前端面（模型 UP，指向机器）：发光方形接口
# =================================================================
def front_tex(active):
    arr = base()
    accent = ACCENT_ON if active else ACCENT_OFF
    core = ACCENT_ON if active else dim(ACCENT_ON, 0.55)
    # 凹槽外框
    for x in range(3, 13):
        setpx(arr, x, 3, DARK)
        setpx(arr, x, 12, DARK)
    for y in range(3, 13):
        setpx(arr, 3, y, DARK)
        setpx(arr, 12, y, DARK)
    # 凹槽内部
    for y in range(4, 12):
        for x in range(4, 12):
            setpx(arr, x, y, RECESS)
    # 发光方环（内侧 1px）
    for x in range(5, 11):
        setpx(arr, x, 5, accent)
        setpx(arr, x, 10, accent)
    for y in range(5, 11):
        setpx(arr, 5, y, accent)
        setpx(arr, 10, y, accent)
    # 中心触点
    for y in range(7, 9):
        for x in range(7, 9):
            setpx(arr, x, y, core)
    return arr


# =================================================================
# 背面（模型 DOWN）：散热格栅
# =================================================================
def back_tex(active):
    arr = base()
    del active  # 背面没有发光元件：active / inactive 只靠机壳亮度区分，故两态同色但各自独立成文件
    for sy in (4, 7, 10):
        for x in range(3, 13):
            setpx(arr, x, sy, RECESS)
            setpx(arr, x, sy + 1, LIGHT)
    # 四角螺钉
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        setpx(arr, x, y, DARK)
    return arr


def back_tex_active(active=True):
    """背面 active 版：格栅加一条浅青反光，与 inactive 区分（各自独立文件）。"""
    arr = back_tex(False)
    accent = ACCENT_ON_SOFT if active else ACCENT_OFF_SOFT
    for x in range(3, 13):
        setpx(arr, x, 5, accent)
    return arr


def save(arr, name, preview_rows):
    img = Image.fromarray(arr, "RGBA")
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))
    # tmp_textures/ 同步留档（本目录只做预览 / 生成记录，运行时不读取）
    img.save(os.path.join(PREVIEW, name + ".png"))
    preview_rows.append((name, img))
    print("[OK] block/%s.png" % name)


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    rows = []
    save(side_tex(False), "sequence_execution_chamber_side_inactive", rows)
    save(side_tex(True), "sequence_execution_chamber_side_active", rows)
    save(front_tex(False), "sequence_execution_chamber_front_inactive", rows)
    save(front_tex(True), "sequence_execution_chamber_front_active", rows)
    save(back_tex(False), "sequence_execution_chamber_back_inactive", rows)
    save(back_tex_active(True), "sequence_execution_chamber_back_active", rows)

    # 预览条（4 倍放大，横向拼接）
    scale = 4
    strip = Image.new("RGBA", (len(rows) * 16 * scale, 16 * scale), (0, 0, 0, 0))
    for i, (_name, img) in enumerate(rows):
        strip.paste(img.resize((16 * scale, 16 * scale), Image.NEAREST), (i * 16 * scale, 0))
    strip.save(os.path.join(PREVIEW, "chamber_faces_preview.png"))
    print("[OK] tmp_textures/chamber_faces/chamber_faces_preview.png")


if __name__ == "__main__":
    main()
