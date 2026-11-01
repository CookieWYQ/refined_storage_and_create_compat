# -*- coding: utf-8 -*-
"""网络资源接收器 / 网络资源请求器 的方块贴图生成（幂等，可复跑）。

本轮口径（用户拍板，逐字见 docs / tmp_textures 交付文档）：
  * **网络资源接收器 = 有朝向**，贴图/模型**照 `sequence_execution_chamber` 那一套**：
    模型以「前端面 = 模型 UP 面」烘焙，blockstate 用 ``x: 90`` 系列旋转把前端面转到 ``facing``；
    于是**四个竖直侧面共用一张带指向性的 `_side`**（箭头恒指向 `facing`，任何侧面都看得见朝向），
    `_front`（模型 UP）与 `_back`（模型 DOWN）各自独立成图 —— 共 3 张 × 2 态 = 6 张。
    （上一版把方向画在 `_front` 并靠 `y` 旋转表达，侧面看不到方向，**不符**，本轮废弃。）
  * **网络资源请求器 = 没有朝向**（它只是发送者）：blockstate **不含 `facing`**，
    模型不加任何 `x`/`y` 旋转；6 面各自独立成图（照 `unit_pattern_manager` 的最严命名口径），
    且**每一面的图形都是无方向的**（没有箭头 / 没有指向某一侧的射线）。
  * 红石触发 = 请求器的两种触发之一（红石上升沿 + 界面手动按钮），**不是第二个方块** ——
    因此原先挂在「右面」上的「红石脉冲刻度」说明改为「请求器的被动输入指示」，图形本身不变。

绘画依据（本工程既有成文规则）：
  * ``docs/DESIGN_DECISIONS_ROUND4.md:3360-3368``（朝向手法：前端面 = UP + `x:90`）、
    ``:3380-3386``（16×16、RS 灰底、互不共用）、``:3421-3422``（一图多用的判据与豁免）；
  * ``icon/icon_README.md:140-156``（色板：``#02DDE6`` 主强调色、``#0096A0`` 柔色、
    ``#494949``/``#858585`` 机壳灰、``#FFA800`` 唯一暖色点缀；**无平滑渐变、无抗锯齿**）；
  * ``icon/icon_README.md:197-198``（像素纪律：只用 1px 硬边，不模糊、不外发光、不羽化）；
  * ``tmp_textures/REDSTONE_AND_TEXTURE_ROUND5.md:76-82``（拆面模板：顶 = 螺钉 + 指示灯条，
    底 = 散热格栅）。

沿用 ``tools/gen_chamber_faces.py`` / ``tools/gen_block_face_split.py`` 的**同一套底图与常量**：
  * 底图 = ``tmp_rs_ref2/block/side.png``（RS 机器通用灰色机壳，16×16）；
  * DARK ``#141414`` / DARK2 ``#222222`` / RECESS ``#111316`` / LIGHT ``#7A7A7A``。

两个方块的家族内区分（用户原话：接收器偏「收」、请求器偏「发」）：
  * **接收器** 强调色 = 青 ``#02DDE6``；`_side` = 方口 + **向内的实心箭头**（收）+
    底缘接缝；`_front` = 方口 + 向内四角括号 + 中心触点；`_back` = 散热格栅。
  * **请求器** 强调色 = 琥珀 ``#FFA800``；6 面共用同一套「无方向」语汇（方口 / 射线 / 梳齿 /
    铆钉），彼此靠**面板细节**区分，**任何一面都不指方向**。

输出：``src/main/resources/assets/rs_create_compat/textures/block/``
      + ``tmp_textures/network_io/`` 预览留档（运行时不读取）。

用法：``python tools/gen_network_io_textures.py``
"""
import os
import sys

import numpy as np
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_SIDE = os.path.join(ROOT, "tmp_rs_ref2", "block", "side.png")
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                   "textures", "block")
PREVIEW = os.path.join(ROOT, "tmp_textures", "network_io")

# ---------------------------------------------------------------- 常量 / 色板
DARK = (20, 20, 20, 255)        # 描边（与既有生成脚本同值）
DARK2 = (34, 34, 34, 255)
RECESS = (17, 19, 22, 255)      # 凹槽
LIGHT = (122, 122, 122, 255)    # 高光边

# 两个方块的强调色（主 / 柔），以及未通电时的压暗系数（照 gen_chamber_faces.py 的口径）
ACCENTS = {
    "network_resource_receiver": ((2, 221, 230, 255), (0, 150, 160, 255)),    # #02DDE6 / #0096A0
    "network_resource_requester": ((255, 168, 0, 255), (170, 112, 0, 255)),   # #FFA800 / #AA7000
}
DIM_MAIN = 0.35
# 注：既有工程用 0.45 压暗「柔强调色」，但那是在柔色单独出现时。本任务让柔色（口的内环）
# 与主色（口内的字形）**相邻**，0.45 会让两态下的两层糊成一块，故柔色压到 0.25 保住层次。
DIM_SOFT = 0.25

RECEIVER = "network_resource_receiver"
REQUESTER = "network_resource_requester"
BLOCKS = (RECEIVER, REQUESTER)
STATES = (False, True)          # False = inactive, True = active

# ---------------------------------------------------------------- 布局（贴图 ←→ 模型面）
# 接收器：照执行舱 —— 四个竖直侧面共用 `_side`，`_front` 烘焙在模型 UP，`_back` 烘焙在模型 DOWN。
CHAMBER_LAYOUT = {              # 模型面 -> 贴图后缀
    "north": "side", "east": "side", "south": "side", "west": "side",
    "up": "front", "down": "back",
}
# 请求器：没有朝向 —— 6 面各自独立，模型不加任何旋转。
REQUESTER_LAYOUT = {f: f for f in ("front", "back", "left", "right", "top", "bottom")}

LAYOUTS = {RECEIVER: CHAMBER_LAYOUT, REQUESTER: REQUESTER_LAYOUT}
# 需要生成的贴图后缀（去重后）——接收器 3 张、请求器 6 张
SUFFIXES = {b: tuple(dict.fromkeys(LAYOUTS[b].values())) for b in BLOCKS}

# 上一版（接收器按 6 面独立画）留下的、本轮不再生成的旧文件 —— 见 cleanup_stale()。
# `_front` 仍在新布局里（改画法但仍叫 front），**不能**列入清理。
STALE_TEXTURES = [
    "network_resource_receiver_%s_%s.png" % (face, state)
    for face in ("bottom", "left", "right", "top")
    for state in ("inactive", "active")
]


def dim(c, factor):
    return (int(c[0] * factor), int(c[1] * factor), int(c[2] * factor), 255)


def accent_pair(block, active):
    """(主强调色, 柔强调色) —— 未通电时压暗（与既有机器同一套「灰/亮」两态口径）。"""
    on, soft = ACCENTS[block]
    if active:
        return on, soft
    return dim(on, DIM_MAIN), dim(soft, DIM_SOFT)


def allowed_colors():
    """本脚本可能写出的**全部**颜色 —— 供校验脚本做「调色板落在既有范围」判定。

    = 既有 RS 机壳底图里的像素 + 既有生成脚本的四个常量 + 两个方块的强调色（明/暗两态）。
    """
    palette = set()
    img = Image.open(BASE_SIDE).convert("RGBA")
    for y in range(img.height):
        for x in range(img.width):
            palette.add(img.getpixel((x, y)))
    for c in (DARK, DARK2, RECESS, LIGHT):
        palette.add(c)
    for block in BLOCKS:
        for active in STATES:
            main, soft = accent_pair(block, active)
            palette.add(main)
            palette.add(soft)
    return palette


def tex_name(block, suffix, active):
    return "%s_%s%s.png" % (block, suffix, "_active" if active else "_inactive")


def expected_textures():
    """本脚本负责的全部产物文件名（去重排序）——接收器 6 张 + 请求器 12 张 = 18 张。"""
    return [tex_name(b, s, a) for b in BLOCKS for a in STATES for s in SUFFIXES[b]]


# ---------------------------------------------------------------- 像素工具
def base():
    """RS 灰色机壳底图（16×16 RGBA）。"""
    return np.array(Image.open(BASE_SIDE).convert("RGBA")).copy()


def setpx(arr, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        arr[y, x] = c


def fill_rect(arr, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            setpx(arr, x, y, c)


def rect_outline(arr, x0, y0, x1, y1, c, skip=()):
    for x in range(x0, x1 + 1):
        if (x, y0) not in skip:
            setpx(arr, x, y0, c)
        if (x, y1) not in skip:
            setpx(arr, x, y1, c)
    for y in range(y0, y1 + 1):
        if (x0, y) not in skip:
            setpx(arr, x0, y, c)
        if (x1, y) not in skip:
            setpx(arr, x1, y, c)


def sym_h(arr, y, xs, c):
    """在 y 行点亮一组 x（关于中轴 7/8 左右镜像，供「无方向」图形使用）。"""
    for x in xs:
        setpx(arr, x, y, c)


def put_all(arr, shape, color):
    for (x, y) in sorted(shape):
        setpx(arr, x, y, color)


def rivets(arr):
    """四角螺钉（与既有拆面模板同一套位置）。"""
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        setpx(arr, x, y, DARK)


# =================================================================
# 接收器 —— 照执行舱：`_side`（N/E/S/W 共用，箭头恒指 `facing`）
# =================================================================
def receiver_side(active):
    """四个竖直侧面共用：**方形插口 + 向内的折线箭嘴**（"收"）+ 底缘接缝。

    朝向表达方式与执行舱**逐字一致**（前端面 = 模型 UP，blockstate 用 ``x: 90`` 系列旋转）：
    折线箭嘴在贴图里**朝上（模型 UP 方向）**，转到 ``facing`` 后恒指向朝向那一面，
    且**四个竖直侧面都看得见**——这正是执行舱的做法，也是本轮修正上一版的原因。

    图形与执行舱刻意区分（都是"朝上的箭头"但不撞图）：
      * 执行舱 = 细描边的**实心三角箭头**（头 2→8 逐行变宽）+ 箭杆；
      * 接收器 = **方形插口**（强调色方口 + 两侧开口短横）+ **3 行折线**箭嘴（无箭杆）。
    """
    arr = base()
    acc, _soft = accent_pair(RECEIVER, active)
    rect_outline(arr, 2, 2, 13, 13, DARK)      # 面板硬边外框（16px 满格面板）
    fill_rect(arr, 3, 3, 12, 12, RECESS)       # 面板凹槽
    for x in range(4, 12):                     # 方形插口的上下两条边
        setpx(arr, x, 4, acc)
        setpx(arr, x, 11, acc)
    for y in range(4, 12):                     # 左右两条边
        setpx(arr, 4, y, acc)
        setpx(arr, 11, y, acc)
    for x in (5, 6, 9, 10):                    # 插口两侧的开口短横（"这是个口"）
        setpx(arr, x, 5, acc)
        setpx(arr, x, 10, acc)
    # 向内的折线箭嘴（宽 6 / 4 / 2，指向模型 UP）—— 插口本身左右 + 上下都镜像对称，
    # **只有这三行箭嘴上下不对称** ⇒ 方向感完全由它承担（自检里逐像素验证）。
    chevron = set()
    for x in range(5, 11):
        chevron.add((x, 6))
    for x in range(6, 10):
        chevron.add((x, 7))
    for x in (7, 8):
        chevron.add((x, 8))
    put_all(arr, chevron, acc)
    rivets(arr)                                # 四角螺钉（与既有机器同一套模板）
    for x in range(4, 12):                     # 底缘接缝（强调色，与执行舱呼应）
        setpx(arr, x, 14, acc)
    return arr


# =================================================================
# 接收器 —— `_front`（模型 UP = 前端面，指向 `facing`）
# =================================================================
def receiver_front(active):
    """前端面：方口 + 凹槽 + 柔色内环 + **中央实心方块**（"收进来"）+ 四角 1px 触点。

    没有方向性（它贴在模型 UP 上，方向由 `_side` 的折线箭嘴表达）。
    与 `_side` 的区别：侧面是"插口 + 折线箭嘴"，正面是"中央实心方块 + 四角触点"；
    与请求器正面的区别：接收器**中央是实心的**、四角有触点（收）；请求器**中央是亮芯 +
    四向等长射线**、四角留空（发）。
    """
    arr = base()
    acc, soft = accent_pair(RECEIVER, active)
    rect_outline(arr, 2, 2, 13, 13, DARK)      # 外框
    fill_rect(arr, 3, 3, 12, 12, RECESS)       # 凹槽
    rect_outline(arr, 4, 4, 11, 11, soft)      # 方口内环
    fill_rect(arr, 5, 5, 10, 10, RECESS)       # 口底
    fill_rect(arr, 6, 6, 9, 9, acc)            # 中央实心方块（"收"）
    for (x, y) in ((5, 5), (10, 5), (5, 10), (10, 10)):
        setpx(arr, x, y, acc)                  # 四角 1px 触点
    return arr


# =================================================================
# 接收器 —— `_back`（模型 DOWN = 背板）
# =================================================================
def receiver_back(active):
    """背板：散热格栅 + 四角螺钉 + **居中的强调色接缝**（与执行舱 `_back` 同一套模板）。

    镜像轴是 16px 画布的**真中线 7.5**：每一行都必须与 ``15 - y`` 行成对，否则就是"方向"。
    这里格栅取镜像对 ``5|10``、``6|9``，强调色接缝取**正中的 ``7`` 与 ``8``**（7 ↦ 8）——
    与 `_side` 的"折线箭嘴朝 UP"形成对照：背板刻意做成完全无方向。
    """
    arr = base()
    acc, _soft = accent_pair(RECEIVER, active)
    for y in range(5, 11):                     # 一道完整散热格栅：5|10 与 6|9 两两成镜像对
        for x in range(3, 13):
            setpx(arr, x, y, RECESS if y in (5, 10) else LIGHT)
    rivets(arr)
    for y in (7, 8):                           # 7 ↦ 8：强调色接缝落在正中间（最后画，压住格栅）
        for x in range(4, 12):
            setpx(arr, x, y, acc)
    return arr


# =================================================================
# 请求器 —— 6 面各自独立，**每一面都无方向**
# =================================================================
def requester_front(active):
    """正面：方口 + 柔色内环 + 中央 2×2 亮芯 + **四向等长射线**（发）。

    四根射线十字对称、等长，且**四角留空** ⇒ 图形既不指任何方向，又恰好与接收器正面的
    「中央实心方块 + 四角触点」互为负形（收 / 发 一眼可分）。
    """
    arr = base()
    acc, soft = accent_pair(REQUESTER, active)
    rect_outline(arr, 2, 2, 13, 13, DARK)      # 外框
    fill_rect(arr, 3, 3, 12, 12, RECESS)       # 凹槽
    rect_outline(arr, 4, 4, 11, 11, soft)      # 方口内环
    fill_rect(arr, 5, 5, 10, 10, RECESS)       # 口底
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        setpx(arr, x, y, acc)                  # 中央亮芯（2×2，居中）
    # 四向**等长**射线：左右取 5,6 与 9,10，上下取 5,6 与 9,10 —— 全部关于中线 7.5 镜像
    rays = set()
    for y in (7, 8):
        for x in (5, 6, 9, 10):
            rays.add((x, y))
    for x in (7, 8):
        for y in (5, 6, 9, 10):
            rays.add((x, y))
    put_all(arr, rays, acc)
    return arr


def requester_back(active):
    """背面：两道水平散热缝 + **对角线方向的两个强调色短齿** + 四角凹槽 + 四角螺钉。

    用"四向都自镜像"的跨度搭（这样最容易保证镜像闭合，也不会被螺钉削掉）：
      * 缝 ``x ∈ 3..12`` 走 ``y = 3`` 与 ``y = 12``（3 ↦ 12）；
      * 强调色短齿 ``x ∈ 4..5`` 与 ``x ∈ 10..11`` 走 ``y = 4`` 与 ``y = 5``（4 ↦ 11、5 ↦ 10），
        但只在**左上 / 右下**两块上色 —— 两块关于中心 (7.5, 7.5) 中心对称，因此掩膜仍然
        左右 + 上下都精确闭合（"斜对角的两个齿"是最省像素的对称做法）。
    """
    arr = base()
    acc, _soft = accent_pair(REQUESTER, active)
    for y in (3, 12):
        for x in range(3, 13):
            setpx(arr, x, y, RECESS)
    for (x0, x1) in ((4, 5), (10, 11)):
        for y in (4, 11):                      # 4 ↦ 11、5 ↦ 10：上下 + 左右都自镜像
            for x in range(x0, x1 + 1):
                setpx(arr, x, y, acc)
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        setpx(arr, x, y, DARK)                 # 四角螺钉
    return arr


def requester_left(active):
    """左面：**两条宽强调色竖带**夹一条居中的凹槽接缝 + 四角螺钉。

    竖带取 ``x ∈ 4,5`` 与 ``x ∈ 10,11``（4|11、5|10 互为镜像；凹槽 6..9 也居中），
    行取 ``3..12``（3 ↦ 12）。注意**不能**用 ``4/6/9/11`` 这种"缝 + 1px 高光"的写法：
    那样第 12 列的强调色会镜像到第 3 列的空白上 → 图形变成不对称（本轮踩过这个坑）。
    """
    arr = base()
    acc, _soft = accent_pair(REQUESTER, active)
    for x in (4, 11):                          # 两条 1px 强调色竖缝（4 ↦ 11）：居中、细
        for y in range(3, 13):
            setpx(arr, x, y, acc)
    for x in range(5, 11):                     # 中央凹槽面板（5..10，5|10、6|9、7|8 都成对）
        for y in range(3, 13):
            setpx(arr, x, y, RECESS)
    for y in (4, 11):                          # 面板里的两条横向亮条（4 ↦ 11）
        for x in range(6, 10):
            setpx(arr, x, y, acc)
    rivets(arr)
    return arr


def requester_right(active):
    """右面：横向电路走线 + **居中的方形灯芯**（左右 + 上下都严格闭合）。

    请求器 = **红石上升沿触发**（它的两种触发之一：红石脉冲 / 界面手动按钮），
    因此这里画"脉冲指示灯"；图形本身**不表达朝向**。
    所有元素都成镜像对：走线 ``3|12`` 与 ``4|11``、柔色高光 ``5|10``、角凹槽 ``4|11``、
    灯芯 ``6..8 × 6..8``（正中心 3×3）—— 与 `requester_front` 的"2×2 芯 + 十字射线"不同，
    满足"6 面互不共用"。
    """
    arr = base()
    acc, soft = accent_pair(REQUESTER, active)
    for x in range(3, 13):                     # 走线：3 ↦ 12（凹槽）+ 4 ↦ 11（柔色高光）
        setpx(arr, x, 3, RECESS)
        setpx(arr, x, 12, RECESS)
        setpx(arr, x, 4, soft)
        setpx(arr, x, 11, soft)
    for (x, y) in ((5, 5), (10, 5), (5, 10), (10, 10)):
        setpx(arr, x, y, RECESS)               # 四角凹槽（5|10 × 5|10）
    # 中央"灯"：外圈 6×6 凹槽（5..10）已经就位，这里用 6×6 的**两侧竖条 + 上下横条**
    # 围出一个 4×4 的凹槽腔，再把 2×2 灯芯放在正中心 —— 每个元素都关于 7.5 四向自镜像。
    for y in range(6, 10):                     # 腔体：6..9 正方形
        for x in range(6, 10):
            setpx(arr, x, y, RECESS)
    for (x, y) in ((6, 6), (9, 6), (6, 9), (9, 9)):
        setpx(arr, x, y, RECESS)               # 腔体四角也压成凹槽（6|9 × 6|9，四向自镜像）
    for x in (7, 8):                           # 2×2 灯芯（正中心）
        for y in (7, 8):
            setpx(arr, x, y, acc)
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        setpx(arr, x, y, DARK)                 # 四角螺钉
    return arr


def requester_top(active):
    """顶面：中央方口环 + 脉冲亮芯（照拆面模板的螺钉位）。

    只有"环 + 芯"这一组居中同心方环 ⇒ 无方向；与 `requester_front` 的区别：
    正面 = **实心中央亮芯 + 四向等长射线**（发），顶面 = **空心方环 + 中心亮芯**（口）。
    """
    arr = base()
    acc, _soft = accent_pair(REQUESTER, active)
    rect_outline(arr, 4, 4, 11, 11, DARK)
    fill_rect(arr, 5, 5, 10, 10, RECESS)
    rect_outline(arr, 6, 6, 9, 9, acc)         # 空心方环（8×8 框线，居中）
    for x in (7, 8):
        for y in (7, 8):
            setpx(arr, x, y, acc)              # 脉冲亮芯
    rivets(arr)
    return arr


def requester_bottom(active):
    """底面：两道上下镜像的横向短槽 + 四个 2×2 强调色角标 + 四角螺钉（上下镜像 ⇒ 无方向）。"""
    arr = base()
    acc, _soft = accent_pair(REQUESTER, active)
    for y in (5, 10):                          # 上下两道横向短槽（关于水平中线镜像）
        fill_rect(arr, 5, y, 10, y, RECESS)
    for (x0, y0) in ((3, 4), (11, 4), (3, 10), (11, 10)):
        fill_rect(arr, x0, y0, x0 + 1, y0 + 1, acc)   # 四角 2×2 角标（左右 + 上下都镜像）
    rivets(arr)
    return arr


# 后缀 -> 画法
RECEIVER_FUNCS = {"side": receiver_side, "front": receiver_front, "back": receiver_back}
REQUESTER_FUNCS = {
    "front": requester_front, "back": requester_back, "left": requester_left,
    "right": requester_right, "top": requester_top, "bottom": requester_bottom,
}
FUNCS = {RECEIVER: RECEIVER_FUNCS, REQUESTER: REQUESTER_FUNCS}


# ---------------------------------------------------------------- 输出
def save(arr, name, rows):
    img = Image.fromarray(arr, "RGBA")
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(PREVIEW, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))
    img.save(os.path.join(PREVIEW, name + ".png"))   # 留档，运行时不读取
    rows.append((name, img))
    print("[OK] block/%s.png" % name)


def strip(rows, path, scale=4):
    img = Image.new("RGBA", (len(rows) * 16 * scale, 16 * scale), (0, 0, 0, 0))
    for i, (_n, tile) in enumerate(rows):
        img.paste(tile.resize((16 * scale, 16 * scale), Image.NEAREST),
                  (i * 16 * scale, 0))
    img.save(path)
    print("[OK] %s" % os.path.relpath(path, ROOT).replace(os.sep, "/"))


def cleanup_stale():
    """删除上一版接收器的旧贴图（本轮不再生成、且没有任何模型引用）。

    **防呆**：STALE 里若混进了本轮产物，这里会拒绝删除（而不是把刚写好的图删掉）——
    这条断言在第一次跑本脚本时就抓到过 ``_back`` 被误列入 STALE 的 bug。
    """
    keep = set(expected_textures())
    for name in STALE_TEXTURES:
        assert name not in keep, "STALE_TEXTURES 混进了本轮产物：%s" % name
        for d in (OUT, PREVIEW):
            path = os.path.join(d, name)
            if os.path.exists(path):
                os.remove(path)
                print("[OK] 清理旧贴图 %s" % os.path.relpath(path, ROOT).replace(os.sep, "/"))


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    rows = []
    for block in BLOCKS:
        for active in STATES:
            for suffix in SUFFIXES[block]:
                arr = FUNCS[block][suffix](active)
                save(arr, tex_name(block, suffix, active)[:-4], rows)

    # 预览：总条（18 张）+ 每个方块一条（inactive 在前 active 在后）
    strip(rows, os.path.join(PREVIEW, "network_io_preview.png"), scale=4)
    for block in BLOCKS:
        sub = [(n, i) for (n, i) in rows if n.startswith(block)]
        strip(sub, os.path.join(PREVIEW, "%s_preview.png" % block), scale=6)

    cleanup_stale()
    print("共 %d 张（接收器 3 后缀 × 2 态 = 6，请求器 6 后缀 × 2 态 = 12）" % len(rows))


if __name__ == "__main__":
    main()
