# -*- coding: utf-8 -*-
"""第 90 轮修订：高级资源定量保持器「行内控件几何 / 流体贴图渲染 / 两行列标签 / 无重复数字」静态自检。

本文件是第 84 轮那份自检的<b>原地升级</b>（没有新建平行脚本），对应本轮用户逐字反馈的三条：

⑦ 「岩浆必须显示为液体/流体渲染，不能显示为桶状……之前的方向部分正确，但当前实现完全错误。」
   —— 判据（源码级，只认真正的代码、不认注释）：
     · 界面 `renderSlot` 必须调<b>工程既有的</b>流体渲染工具
       `GhostMarkerRenderer.toFluidStack(...)` + `GhostMarkerRenderer.renderFluid(...)`，
       并且保留 `super.renderSlot(...)` 作为物品 / 空槽 / 降级路径；
     · 界面源码<b>不得</b>出现桶转换 / 自绘物品符号：`getFilledBucket` / `FluidUtil` /
       `renderItem(` / `fluidBucketStack`；
     · 方块实体 `setFluidMarker` 必须把标记槽写成 `ItemStack.EMPTY`（数据层绝不放桶），
       整个方块实体文件不得再出现 `FluidUtil` / `getFilledBucket` / `fluidBucketStack`；
     · 旧存档清理：`clearLegacyFluidSlotItems()` 存在且在读档 / 拆下再放下两处都被调用；
     · 标记语义仍只看 `fluidMarkerIds[] / fluidForms[]`（getSlotConfig 先判流体、
       matchesItemMarker 排除流体槽、networkResourceKey 先判流体标记）；
     · **反例自证**：把「setFluidMarker 写桶」「renderSlot 只写 super」「renderSlot 先做桶转换
       再 renderItem」分别喂给同一判据 ⇒ 必须判负。

⑧ 「不能只显示『自动和销毁』……应显示『自动合成』和『过量销毁』。一行显示不下、字太小时，应显示为两行。」
   —— 判据：
     · 语言键 `column_autocraft` + `column_autocraft.2` 拼起来 = 「自动合成」（en: Auto + Craft），
       `column_overflow` + `column_overflow.2` = 「过量销毁」（en: Over + flow）；中英成对、非空、两侧不同；
     · 两行各 9px、标签块 18px、块宽 ≤ COLUMN_LABEL_MAX_W(26)；块心 == 开关列心；
       两标签块缝 ≥ 4px；标签块不压第一行任何控件、不压标题带（x ≤ TITLE_BAND_W）、
       不压插件槽列、不越面板；
     · 源码：4 个 static final 缓存 Component、renderLabels 只调 2 次 drawColumnLabel、
       不在 renderLabels 里拼 Component、无阴影；
     · **反例自证**：把旧短词「自动 / 销毁」（只有一行）喂给同一个「完整词」判据 ⇒ 判负；
       把标签挪到第一行资源上 / 两标签同文本 ⇒ 判负。

① 「删除 + 按钮右侧重复显示的数字……将输入框拉大一些。」
   —— 判据（双重）：
     · 几何：`[+]` 右缘到自动合成列左沿只有 4px（< 12px = 最窄两位数宽）⇒ 结构上放不下任何数字；
     · 源码：整个界面文件的 `drawString(` 只允许出现在 drawTitle / drawColumnLabel 两个方法里
       （行内一个文字都不画；「已存 / 目标」那段已删）；
     · 输入框变宽变高：ROW_BOX_W = 74 > 70、ROW_BOX_H = 14 > 12，文本区 65px ≥ 58px；
     · **反例自证**：往 renderLabels 里塞一句画目标值的 drawString ⇒ 判负。

⑥ 「74 是不是随手写的 / 为什么必须两行」——把上面两条几何结论变成**可复算的数值证明**
   （否则「拉大」与「两行」只是两个碰巧成立的数字，改一行常量就可能悄悄退化）：
     · 单行完整词：zh「自动合成 / 过量销毁」各 36px、en「Auto-craft / Over-flow」54 / 50px，
       两列列距只有 29px ⇒ 单行两块必然相交（缝 < 4px）；两行后每块 ≤ 26px ⇒ 缝 4px。两行是唯一解；
     · 输入框上限：右留白 8px + 列间距 13px + 四个 4px 缝 ⇒ `ROW_BOX_W` 的最大可行值恰好 =
       74（由同一批常量推导，不是字面量）；再 +2（76）右留白只剩 6px、再 +4（78）英文「Over」
       标签块右缘 188 会压到插件槽列 187 ⇒ 两个独立判据各自把 76 / 78 判负。

【反例自证】每个判据都能被明确的反例触发（旧短词、单行完整词、标签压第一行、标签不对齐、
两列同文本、`setFluidMarker` 写桶、`renderSlot` 只写 super、槽里转桶再 renderItem、
`[+]` 旁留 12px 画数字、输入框 76 / 78）—— 见各 section 末尾的「反例自证」行（全部为 [OK]）。

用法：python tools/selfcheck_round84_adv_keeper_layout.py
退出码：0 = 全部通过；1 = 有问题（逐条打印）。
"""
import io
import itertools
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
WIDGET = os.path.join(JAVA, "client", "widget")
SCREEN = os.path.join(JAVA, "client", "screen")

GEOMETRY = os.path.join(WIDGET, "RsccKeeperGeometry.java")
ADV_SCREEN = os.path.join(SCREEN, "AdvancedQuantityKeeperScreen.java")
BASIC_SCREEN = os.path.join(SCREEN, "QuantityKeeperScreen.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

problems = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(ok, message, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + message + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(message)


def section(title):
    print("\n===== %s =====" % title)


def java_int_const(source, name):
    match = re.search(r"int\s+%s\s*=\s*(-?\d+)\s*;" % name, source)
    if not match:
        raise SystemExit("找不到 Java 常量: " + name)
    return int(match.group(1))


def method_body(source, signature):
    start = source.index(signature)
    open_index = source.index("{", start)
    depth = 0
    for i in range(open_index, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[open_index:i + 1]
    raise SystemExit("方法体未闭合: " + signature)


def body_or_empty(source, signature):
    """取方法体；签名不存在时返回空串（让「找不到方法」变成可读的判负，而不是抛异常）。"""
    if signature not in source:
        return ""
    return method_body(source, signature)


def strip_java_comments(source):
    """去掉 // 与 /* */ 注释（判据只看真正的代码，避免注释里提到 renderFluid 就误判）。"""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//[^\n]*", "", source)
    return source


def rect(x, y, w, h):
    return (x, y, x + w, y + h)


def overlap(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


# 与工程其它自检同一套原版字宽表：ASCII 缺省 6px，CJK 一律 9px。
MC_ADVANCE = {
    " ": 4, "!": 2, "'": 2, ".": 2, ",": 2, ":": 2, ";": 2, "i": 2, "|": 2,
    "(": 4, ")": 4, "[": 4, "]": 4, "f": 4, "k": 4, "l": 4, "t": 4, "I": 4,
    "<": 5, ">": 5, "*": 5, '"': 5, "`": 5, "{": 5, "}": 5, "~": 5,
    "\\": 6, "@": 7,
}


def char_width(ch):
    return 9 if ord(ch) >= 128 else MC_ADVANCE.get(ch, 6)


def text_width(text):
    return sum(char_width(c) for c in text)


# =====================================================================
# ① 行内控件几何：固定 5 个控件、两两不重叠、间距 >= 4px、不越界
# =====================================================================
ROW_FIRST_Y = 22
ROW_STEP = 24
SLOT = (9, 3, 16, 16)            # Menu 坐标（内容区），烘焙在背景 PNG 里
BAND_LEFT = 25                   # 槽框右沿（精灵 8..25）
BAND_RIGHT = 188                 # 插件槽列（Menu x = 188）
MIN_GAP = 4
# 文本区 = 宽 − 9（RsccNumberField.textWindow 的唯一口径）
TEXT_WINDOW_INSET = 9


def row_controls(spec, row_y):
    """spec: dict(name -> (x, y_offset, w, h))；返回 {name: (x0, y0, x1, y1)}（含标记槽）。"""
    out = {"标记槽": (SLOT[0], row_y + SLOT[1], SLOT[0] + SLOT[2], row_y + SLOT[1] + SLOT[3])}
    for name, (x, dy, w, h) in spec.items():
        out[name] = (x, row_y + dy, x + w, row_y + dy + h)
    return out


def geometry_problems(spec):
    """行内几何判据（第 90 轮布局必须 0 问题；旧布局必须被判负）。"""
    problems = []
    for row in range(4):
        row_y = ROW_FIRST_Y + row * ROW_STEP
        items = row_controls(spec, row_y)
        names = list(items)
        for a, b in itertools.combinations(names, 2):
            if overlap(items[a], items[b]):
                problems.append("row%d %s %s 与 %s %s 重叠" % (row, a, items[a], b, items[b]))
        ordered = [("标记槽", items["标记槽"]), ("[-]", items["[-]"]),
                   ("输入框", items["输入框"]), ("[+]", items["[+]"]),
                   ("自动合成", items["自动合成"]), ("过量销毁", items["过量销毁"])]
        for (an, ar), (bn, br) in zip(ordered, ordered[1:]):
            gap = br[0] - ar[2]
            if gap < MIN_GAP:
                problems.append("row%d %s 右缘 %d 与 %s 左沿 %d 间距 %d < %dpx"
                                % (row, an, ar[2], bn, br[0], gap, MIN_GAP))
        for name, r in items.items():
            if name == "标记槽":
                continue  # 标记槽框烘焙在背景 PNG 里（x=9..25），不参与「控件带」判定
            if r[0] < BAND_LEFT or r[2] > BAND_RIGHT:
                problems.append("row%d %s %s 越出控件带 %d..%d"
                                % (row, name, r, BAND_LEFT, BAND_RIGHT))
    return problems


def spec_from_java(geom, screen):
    """从 Java 常量还原「第 90 轮」的行布局（几何常量 + 界面字面量必须同值）。"""
    spec = {
        "[-]": (java_int_const(geom, "ROW_MINUS_X"), java_int_const(geom, "ROW_BOX_DY"),
                java_int_const(geom, "ROW_BTN_W"), java_int_const(geom, "ROW_BTN_H")),
        "输入框": (java_int_const(geom, "ROW_BOX_X"), java_int_const(geom, "ROW_BOX_DY"),
                 java_int_const(geom, "ROW_BOX_W"), java_int_const(geom, "ROW_BOX_H")),
        "[+]": (java_int_const(geom, "ROW_PLUS_X"), java_int_const(geom, "ROW_BOX_DY"),
                java_int_const(geom, "ROW_BTN_W"), java_int_const(geom, "ROW_BTN_H")),
        "自动合成": (java_int_const(geom, "AUTOCRAFT_BTN_X"), java_int_const(geom, "TOGGLE_DY"),
                  java_int_const(geom, "TOGGLE_BTN_W"), java_int_const(geom, "TOGGLE_BTN_H")),
        "过量销毁": (java_int_const(geom, "OVERFLOW_BTN_X"), java_int_const(geom, "TOGGLE_DY"),
                  java_int_const(geom, "TOGGLE_BTN_W"), java_int_const(geom, "TOGGLE_BTN_H")),
    }
    # 界面里的字面量必须与几何常量同值（旧校验脚本按字面量读）
    for screen_name, geom_name in (("UNIT_SPRITE_X", "ROW_UNIT_X"),
                                   ("AUTOCRAFT_BTN_X", "AUTOCRAFT_BTN_X"),
                                   ("OVERFLOW_BTN_X", "OVERFLOW_BTN_X")):
        check(java_int_const(screen, screen_name) == java_int_const(geom, geom_name),
              "界面字面量 %s = %d 与几何常量 %s 同值"
              % (screen_name, java_int_const(screen, screen_name), geom_name))
    return spec


# 第 84 轮（上一版）布局：用于反例自证「旧几何必须判负」
OLD_SPEC = {
    "单位文字": (26, 7, 24, 9),
    "[-]": (54, 4, 10, 12),
    "输入框": (66, 4, 70, 12),
    "[+]": (138, 4, 10, 12),
    "自动合成": (148, 3, 16, 12),
    "过量销毁": (170, 3, 16, 12),
}


def widened_problems(spec):
    """输入框必须真的变宽变高（用户原话「将输入框拉大一些」）。"""
    out = []
    box = spec["输入框"]
    if box[2] <= 70:
        out.append("输入框宽 %d ≤ 70（没有比上一轮 70 更宽）" % box[2])
    if box[3] <= 12:
        out.append("输入框高 %d ≤ 12（没有比上一轮 12 更高）" % box[3])
    window = box[2] - TEXT_WINDOW_INSET
    if window < text_width("100,000,000"):
        out.append("输入框文本区 %dpx < 「100,000,000」%dpx" % (window, text_width("100,000,000")))
    return out


def duplicate_number_region(spec, row_y):
    """`[+]` 右缘 → 自动合成列左沿 的「重复数字区」（几何上应当窄到放不下数字）。"""
    plus = spec["[+]"]
    auto = spec["自动合成"]
    return rect(plus[0] + plus[2], row_y + spec["输入框"][1],
                auto[0] - (plus[0] + plus[2]), spec["输入框"][3])


def duplicate_number_geometry_problems(spec):
    out = []
    for row in range(4):
        row_y = ROW_FIRST_Y + row * ROW_STEP
        region = duplicate_number_region(spec, row_y)
        width = region[2] - region[0]
        # 最窄的「两位数」= 2 × 6px = 12px；这一带必须比它还窄 ⇒ 结构上画不下数字
        if width >= 12:
            out.append("row%d [+]/开关 之间的缝 %dpx ≥ 12px —— 有空间再画出重复数字" % (row, width))
        if width < MIN_GAP:
            out.append("row%d [+]/开关 间距 %d < %dpx" % (row, width, MIN_GAP))
    return out


def section_geometry():
    section("① 行内控件：固定 5 个、两两不重叠、间距 >= 4px、输入框更宽更高")
    geom = read(GEOMETRY)
    screen = read(ADV_SCREEN)
    check(java_int_const(geom, "ROW_FIRST_Y") == ROW_FIRST_Y
          and java_int_const(geom, "ROW_STEP") == ROW_STEP,
          "行距 22 / 24 未变（槽位位置烘焙在背景 PNG：槽框外框 y=24/48/72/96）")
    spec = spec_from_java(geom, screen)
    print("       第 90 轮的行布局（x / y偏移 / 宽 / 高）：")
    for name, (x, dy, w, h) in spec.items():
        print("         %-5s x=%3d..%3d  y=行顶+%d..%+d  (%d×%d)"
              % (name, x, x + w - 1, dy, dy + h - 1, w, h))
    bad = geometry_problems(spec)
    check(not bad, "新布局：4 行 × 5 控件两两不重叠、相邻间距 >= %dpx、都在 x=%d..%d 内"
          % (MIN_GAP, BAND_LEFT, BAND_RIGHT), str(bad[:3]))

    bad = widened_problems(spec)
    box = spec["输入框"]
    check(not bad, "输入框确实变宽变高：%d×%d（上一轮 70×12），文本区 %dpx ≥ 「100,000,000」%dpx"
          % (box[2], box[3], box[2] - TEXT_WINDOW_INSET, text_width("100,000,000")), str(bad))

    # 反例自证：旧布局必须被判负（+ 与自动合成列 0px、两列只隔 6px、多一个单位文字）
    old_bad = geometry_problems(OLD_SPEC)
    check(bool(old_bad), "反例自证：第 84 轮之前的几何被同一判据判负（%d 条，例如 %s）"
          % (len(old_bad), old_bad[0] if old_bad else ""))
    check(any("间距 0" in b for b in old_bad),
          "反例自证：旧布局「[+] 右缘 148 与自动合成列 148 零间隙」被判出")

    # 反例自证：把输入框塞回 70 宽 ⇒ 「必须变宽」判据判负
    narrow = {name: tuple(v) for name, v in spec.items()}
    x, dy, _w, h = narrow["输入框"]
    narrow["输入框"] = (x, dy, 70, h)
    check(bool(widened_problems(narrow)), "反例自证：输入框塞回 70×%d ⇒ 「必须变宽」判据判负" % h,
          str(widened_problems(narrow)[:2]))

    # 开关两个功能的横向距离：必须 >= 12px（第 84 轮之前只有 6px，视觉上像重复按钮）
    auto = spec["自动合成"]
    over = spec["过量销毁"]
    gap = over[0] - (auto[0] + auto[2])
    check(gap >= 12, "两个开关之间留 %dpx（>= 12：两个不同功能，不能贴在一起）" % gap)
    check(over[0] + over[2] <= BAND_RIGHT - 8,
          "过量销毁右缘 %d 距插件槽列 %d 仍留 %dpx（第 84 轮的 8px 口径未放松）"
          % (over[0] + over[2], BAND_RIGHT, BAND_RIGHT - (over[0] + over[2])))
    return spec


# =====================================================================
# ② 每行控件数量与功能集合固定；开关必须是统一 ✓/✗ 样式（第 86 轮改回）
# =====================================================================
TOGGLE_GLYPHS = ("§a✓", "§c✗")


def toggle_style_problems(source):
    """返回「开关不是 McGui.toggle 的 ✓/✗ 统一风格」的问题列表（第 86 轮之后必须为空）。"""
    out = []
    if "RsccKeeperToggleIcon" in source:
        out.append("界面仍引用 RsccKeeperToggleIcon（功能图标 + 状态边框样式没撤掉）")
    body = method_body(source, "protected void init(")
    uses_mcgui = body.count("McGui.toggle(") == 2
    has_literals = all(glyph in body for glyph in TOGGLE_GLYPHS)
    if not uses_mcgui and not has_literals:
        out.append("两个开关既不是 McGui.toggle，也没有 §a✓ / §c✗ 文本（只剩图标 ⇒ 看不出开/关）")
    if uses_mcgui and source.count("McGui.refreshToggle(") < 2:
        out.append("McGui.refreshToggle 少于 2 处（开关状态不会跟随服务端权威值）")
    if "Component.empty()" in body:
        out.append("开关按钮消息是空的（只剩图标，没有 ✓/✗）")
    adds = body.count("addRenderableWidget(")
    if adds != 5:
        out.append("init() 里 addRenderableWidget 次数 %d != 5（每行必须恰好 5 个控件）" % adds)
    if body.count("toggleAutocraft(") != 1 or body.count("toggleOverflow(") != 1:
        out.append("开关回调不是各一处（同一功能被建了两遍？）")
    if "§7✗" not in source:
        out.append("没有禁用态灰色 ✗（§7✗）：没装自动合成升级时与「关闭」分不开")
    return out


def source_row_controls_ok(source):
    """每行控件「功能集合」固定：− / 输入框 / + / 自动合成 / 过量销毁，一样不能多。"""
    body = method_body(source, "protected void init(")
    expected = [("new EditBox(", 1), ('Component.literal("-")', 1), ('Component.literal("+")', 1),
                ("toggleAutocraft(", 1), ("toggleOverflow(", 1)]
    bad = []
    for token, want in expected:
        got = body.count(token)
        if got != want:
            bad.append("init() 里 %s 出现 %d 次（应为 %d）" % (token, got, want))
    return bad


# 两个同形 ✓/✗ 开关的「可分辨性」契约：每一列都有自己独立的一整套文案
TOOLTIP_KEYS = (
    "autocraft_label", "autocraft_header.tip", "autocraft_on.tip", "autocraft_off.tip",
    "autocraft_no_upgrade.tip",
    "overflow_label", "overflow_header.tip", "overflow_on.tip", "overflow_off.tip",
    "overflow_shared.tip",
)


def tooltip_distinguishability_problems(source):
    """两个开关同形时，玩家只能靠 tooltip 分辨 —— 所以两套文案一个都不许少。"""
    missing = [token for token in TOOLTIP_KEYS if (('LANG + "%s"' % token) not in source)]
    out = []
    if missing:
        out.append("开关 tooltip 缺少文案：%s" % ", ".join(missing))
    for token in ("autocraft_label", "overflow_label"):
        count = source.count('LANG + "%s"' % token)
        if count != 1:
            out.append("LANG + \"%s\" 出现 %d 次（应为 1：两个开关不能共用同一个名字）"
                       % (token, count))
    return out


# 反例：第 84~85 轮的「功能图标 + 状态边框」写法（消息恒空、没有 ✓/✗ 文本）。
OLD_SOURCE_ICON = """
    protected void init() {
        for (int row = 0; row < 4; row++) {
            autocraftButtons[row] = addRenderableWidget(new Button.Builder(Component.empty(),
                btn -> toggleAutocraft(r, !menu.isAutoCraft(r)))
                .bounds(leftPos + AUTOCRAFT_BTN_X, topPos + rowY + TOGGLE_DY,
                    TOGGLE_BTN_W, TOGGLE_BTN_H).build());
            overflowButtons[row] = addRenderableWidget(new Button.Builder(Component.empty(),
                btn -> toggleOverflow(r, !menu.isOverflowDestroy(r)))
                .bounds(leftPos + OVERFLOW_BTN_X, topPos + rowY + TOGGLE_DY,
                    TOGGLE_BTN_W, TOGGLE_BTN_H).build());
            autocraftButtons[row] = addRenderableWidget(targetBoxes[row]);
            autocraftButtons[row] = addRenderableWidget(minusButtons[row]);
            autocraftButtons[row] = addRenderableWidget(plusButtons[row]);
        }
    }
    // 图标 + 状态边框：RsccKeeperToggleIcon.render(...)
"""


def section_controls():
    section("② 每行控件数量 / 功能集合固定；两个开关保持统一 ✓/✗ 样式")
    screen = read(ADV_SCREEN)
    bad = toggle_style_problems(screen)
    check(not bad, "两个开关是 McGui.toggle（§a✓ / §c✗），状态每帧回写，每行恰好 5 个控件", str(bad))
    bad = source_row_controls_ok(screen)
    check(not bad, "每行功能集合 = [−] / 输入框 / [+] / 自动合成 / 过量销毁（各 1）", str(bad))
    bad = tooltip_distinguishability_problems(screen)
    check(not bad, "两个开关各有一整套独立 tooltip（功能名 + 点击语义 + 状态 + 仲裁规则）", str(bad))

    old_bad = toggle_style_problems(OLD_SOURCE_ICON)
    check(bool(old_bad), "反例自证：只有图标没有 ✓/✗ 的开关被判负（%d 条）" % len(old_bad),
          old_bad[0] if old_bad else "")

    basic = read(BASIC_SCREEN)
    for token in ('Component.literal("-")', 'Component.literal("+")',
                  "RsccNumberField.bucketTooltip("):
        check(token in basic and token in screen, "与基础版同一口径：%s 两边都有" % token)
    check("§7✗" in basic, "与基础版同一口径：没装自动合成升级时都画灰色 §7✗")
    check("unit_mb" not in screen and "unit_item" not in screen,
          "高级版不再画「个 / mB」单位文字（与基础版同一条口径）")


# =====================================================================
# ③ 流体 / 气体标记槽 = 流体贴图渲染（绝不是桶）
# =====================================================================
FLUID_RENDER_TOKENS = ("GhostMarkerRenderer.toFluidStack(", "GhostMarkerRenderer.renderFluid(",
                       "menu.getMarkerForm(", "menu.markerRowOf(", "super.renderSlot(")
BUCKET_TOKENS = ("getFilledBucket", "FluidUtil", "fluidBucketStack", "renderItem(")


def fluid_render_problems(screen_source, be_source):
    """返回「流体标记槽没有走工程既有流体贴图渲染 / 或又退回桶」的问题列表。"""
    out = []
    screen = strip_java_comments(screen_source)
    be = strip_java_comments(be_source)

    body = body_or_empty(screen, "protected void renderSlot(")
    if not body:
        out.append("找不到 renderSlot 覆写")
    else:
        for token in FLUID_RENDER_TOKENS:
            if token not in body:
                out.append("renderSlot 缺少 %s（流体槽没走既有流体渲染 / 或丢了原版路径）" % token)
        if body.find("GhostMarkerRenderer.renderFluid(") > body.find("super.renderSlot("):
            out.append("renderSlot 先走 super 再画流体（物品/桶会先被画出来）")
        for bad in ("getFilledBucket", "renderItem("):
            if bad in body:
                out.append("renderSlot 里出现桶转换 / 自绘物品符号 %s" % bad)
    for bad in BUCKET_TOKENS:
        if bad in screen:
            out.append("界面源码仍出现桶相关符号 %s" % bad)

    # 数据层：标记槽绝不能放桶
    if "fluidBucketStack" in be:
        out.append("方块实体仍有 fluidBucketStack（桶换算没有从数据层删掉）")
    if "getFilledBucket" in be or "FluidUtil" in be:
        out.append("方块实体仍引用 FluidUtil / getFilledBucket（标记槽还在放桶）")
    set_fluid = body_or_empty(be, "public void setFluidMarker(")
    if not set_fluid:
        out.append("找不到 AdvancedQuantityKeeperBlockEntity#setFluidMarker")
    elif "inventory.setItem(slot, ItemStack.EMPTY);" not in set_fluid:
        out.append("setFluidMarker 没有把标记槽写成 ItemStack.EMPTY（数据层仍可能放物品）")
    if be.count("clearLegacyFluidSlotItems();") < 2:
        out.append("clearLegacyFluidSlotItems 未在读档 / 拆下再放下两处都调用（旧存档的桶会残留）")

    # 标记槽的每一次写入都要审计：只允许 setItemMarker 写「物品标记」，其余一律空栈。
    # （否则「槽里放真实桶」会从别的方法悄悄回来 —— 上一轮就是这么退化的。）
    clear_body = body_or_empty(be, "public void clearSlot(")
    legacy_body = body_or_empty(be, "private void clearLegacyFluidSlotItems(")
    for method_name, method_src in (("setFluidMarker", set_fluid), ("clearSlot", clear_body),
                                    ("clearLegacyFluidSlotItems", legacy_body)):
        if not method_src:
            out.append("找不到 %s（标记槽写入无法审计）" % method_name)
            continue
        for line in method_src.splitlines():
            if "inventory.setItem(" in line and "ItemStack.EMPTY" not in line:
                out.append("%s 往标记槽写了非空栈：%s" % (method_name, line.strip()))
    marker_body = body_or_empty(be, "public void setItemMarker(")
    if "inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));" not in marker_body:
        out.append("setItemMarker 不再写物品标记槽（物品标记会失效）")
    if be.count("inventory.setItem(") != 4:
        out.append("inventory.setItem 调用点 %d != 4（新增了未审计的标记槽写入）"
                   % be.count("inventory.setItem("))

    # 标记语义只由 fluidMarkerIds[] 判定
    config = body_or_empty(be, "public KeeperSlotConfig getSlotConfig(")
    if not config:
        out.append("找不到 getSlotConfig")
    else:
        pos_fluid = config.find("fluidMarkerIds[slot]")
        pos_item = config.find("inventory.getItem(slot)")
        if pos_fluid < 0:
            out.append("getSlotConfig 没有先看 fluidMarkerIds[]（标记类型判据缺失）")
        elif pos_item < 0 or pos_fluid > pos_item:
            out.append("getSlotConfig 先读槽内物品再判标记类型（会被残留物品误导）")
    match = body_or_empty(be, "public boolean matchesItemMarker(")
    if "fluidMarkerIds[slot] != null" not in match:
        out.append("matchesItemMarker 没有排除流体标记槽")
    key = body_or_empty(be, "public ResourceKey networkResourceKey(")
    if "hasDirectFluidMarker(slot)" not in key:
        out.append("networkResourceKey 没有先判流体标记")

    # tooltip：名字走既有工具 / 不用 literal 硬拼
    if "GhostMarkerRenderer.renderFluidTooltip(" not in screen:
        out.append("流体标记槽没有 tooltip（hover 岩浆格会完全没有提示）")
    if re.search(r'Component\.literal\("[^"]*(熔岩|岩浆)', screen):
        out.append("界面用 Component.literal 硬拼「熔岩 / 岩浆」")
    return out


# 反例用「变异源码」而不是另写整份假类：直接对真实源码做定点破坏，证明判据不是空的。
def mutated(source, old, new):
    if old not in source:
        raise SystemExit("反例变异失败（找不到锚点）: " + old[:60])
    return source.replace(old, new, 1)


def section_fluid():
    section("③ 流体 / 气体标记槽 = 流体贴图渲染（不是桶、不是 super 画桶）")
    screen = read(ADV_SCREEN)
    be = read(ADV_BE)
    bad = fluid_render_problems(screen, be)
    check(not bad, "流体槽走 GhostMarkerRenderer.renderFluid 贴图；数据层标记槽为空栈、无桶换算", str(bad[:3]))

    body = strip_java_comments(screen)
    body = method_body(body, "protected void renderSlot(")
    for token in ("GhostMarkerRenderer.toFluidStack(", "GhostMarkerRenderer.renderFluid(",
                  "super.renderSlot(guiGraphics, slot);"):
        check(token in body, "renderSlot 证据：%s" % token)
    check("protected void renderSlot(" in strip_java_comments(screen),
          "存在 renderSlot 覆写（流体渲染有唯一入口）")
    check("public static void renderFluid(" in read(os.path.join(WIDGET, "GhostMarkerRenderer.java")),
          "共享渲染代码未被改动：GhostMarkerRenderer.renderFluid 仍在（直接复用，不另写一套）")
    check("GhostMarkerRenderer.renderFluid(" in read(BASIC_SCREEN),
          "基础版保持器仍用同一套流体渲染（两版口径一致）")

    # 反例自证一：数据层写桶 ⇒ 判负
    be_bad = mutated(be, "inventory.setItem(slot, ItemStack.EMPTY);",
                     "inventory.setItem(slot, fluidBucketStack(id));")
    res = fluid_render_problems(screen, be_bad)
    check(bool(res) and any("EMPTY" in item or "桶" in item for item in res),
          "反例自证：setFluidMarker 写桶 ⇒ 判负", str(res[:2]))

    # 反例自证二：renderSlot 只剩 super（= 上一轮「不放桶就什么都不画」）⇒ 判负
    super_only = screen.replace(
        "GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, fluid);", "")
    res = fluid_render_problems(super_only, be)
    check(bool(res) and any("renderFluid" in item for item in res),
          "反例自证：renderSlot 不画流体贴图 ⇒ 判负", str(res[:2]))

    # 反例自证三：先桶转换再自绘物品 ⇒ 判负
    bucket_draw = screen.replace(
        "GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, fluid);",
        "guiGraphics.renderItem(FluidUtil.getFilledBucket(fluid), slot.x, slot.y);")
    res = fluid_render_problems(bucket_draw, be)
    check(bool(res) and any("getFilledBucket" in item or "renderItem(" in item for item in res),
          "反例自证：槽里转桶再 renderItem ⇒ 判负", str(res[:2]))

    # 反例自证四：旧存档清理改回「写桶」⇒ 判负（标记槽写入审计，专治「桶从别的方法悄悄回来」）
    be_legacy = mutated(be, "                inventory.setItem(i, ItemStack.EMPTY);",
                        "                inventory.setItem(i, legacyBucket);")
    res = fluid_render_problems(screen, be_legacy)
    check(bool(res) and any("clearLegacyFluidSlotItems" in item for item in res),
          "反例自证：旧存档清理往标记槽写桶 ⇒ 判负", str(res[:2]))


# =====================================================================
# ④ 两列列标签（两行完整词）：文本键 / 对齐 / 不相交 / 可区分 + 反例自证
# =====================================================================
LANG_ADV = "gui.rs_create_compat.advanced_quantity_keeper."
COLUMN_LABEL_KEYS = ("column_autocraft", "column_overflow")


def load_lang():
    tables = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            tables[name] = json.load(handle)
    return tables


def label_lines(tables, lang, key):
    """两行列标签的两行文本（key / key.2）。"""
    return (tables[lang].get(LANG_ADV + key, ""), tables[lang].get(LANG_ADV + key + ".2", ""))


def label_spec(geom, tables, lang, columns=None):
    """从 Java 常量 + 语言文件还原两个列标签块（公式 = RsccKeeperGeometry.columnLabelX）。

    {@code columns} 可覆盖两列左沿（默认取 Java 常量）：用于「假设输入框再加宽 2 / 4px，
    开关列与列标签一起右移」的极限反例 —— 证明 74 已顶到列标签与右留白两条独立判据。
    """
    g = {name: java_int_const(geom, name) for name in (
        "ROW_FIRST_Y", "ROW_BOX_DY", "ROW_MINUS_X", "ROW_BOX_X", "ROW_BOX_W", "ROW_PLUS_X",
        "ROW_BTN_W", "ROW_BTN_H", "ROW_BOX_H", "AUTOCRAFT_BTN_X", "OVERFLOW_BTN_X",
        "TOGGLE_BTN_W", "TOGGLE_BTN_H", "TOGGLE_DY", "COLUMN_LABEL_Y", "COLUMN_LABEL_LINE_H",
        "COLUMN_LABEL_H", "COLUMN_LABEL_MAX_W", "TITLE_BAND_W", "TITLE_Y", "TITLE_LINE_H")}
    auto_column = g["AUTOCRAFT_BTN_X"] if columns is None else columns[0]
    over_column = g["OVERFLOW_BTN_X"] if columns is None else columns[1]
    items = {}
    for name, key, column_x in (("autocraft", COLUMN_LABEL_KEYS[0], auto_column),
                                ("overflow", COLUMN_LABEL_KEYS[1], over_column)):
        line1, line2 = label_lines(tables, lang, key)
        widths = (text_width(line1), text_width(line2))
        block = max(widths)
        # 与 RsccKeeperGeometry.columnLabelX 逐字同式（Java 的 int 除法向零截断；这里都是非负）
        x = column_x + g["TOGGLE_BTN_W"] // 2 - block // 2
        line1_x = x + (block - widths[0]) // 2
        line2_x = x + (block - widths[1]) // 2
        items[name] = {"key": key, "lines": (line1, line2), "widths": widths,
                       "column_x": column_x, "width": block,
                       "rect": rect(x, g["COLUMN_LABEL_Y"], block, g["COLUMN_LABEL_H"]),
                       "line_rects": (rect(line1_x, g["COLUMN_LABEL_Y"], widths[0], g["COLUMN_LABEL_LINE_H"]),
                                      rect(line2_x, g["COLUMN_LABEL_Y"] + g["COLUMN_LABEL_LINE_H"],
                                           widths[1], g["COLUMN_LABEL_LINE_H"]))}
    return g, items


def full_word_problems(items, lang):
    """完整词判据：两行拼起来必须是「自动合成 / 过量销毁」（en: autocraft / overflow）。"""
    out = []
    expect = {"autocraft": "自动合成" if lang == "zh_cn.json" else "autocraft",
              "overflow": "过量销毁" if lang == "zh_cn.json" else "overflow"}
    for name, item in items.items():
        joined = "".join(item["lines"]).strip()
        if not joined:
            out.append("%s %s 列标签为空" % (lang, name))
            continue
        want = expect[name]
        got = joined if lang == "zh_cn.json" else joined.lower().replace(" ", "")
        if got != want:
            out.append("%s %s 列标签两行拼起来是 %r，应为 %r（不能只显示半截词）"
                       % (lang, name, joined, want))
    if items["autocraft"]["lines"] == items["overflow"]["lines"]:
        out.append("%s 两个列标签完全相同 ⇒ 分不出哪列管什么" % lang)
    return out


def column_label_problems(g, items, title_lines, panel_w=210, panel_h=210):
    """几何判据：两行 / 列心对齐 / 宽度上限 / 互不重叠 / 不压第一行 / 标题带 / 插件槽列 / 面板。"""
    out = []
    # ① 两行：行高 9、块高 18；两行矩形都在块内且互不重叠
    for name, item in items.items():
        x0, y0, x1, y1 = item["rect"]
        h = y1 - y0
        if h != g["COLUMN_LABEL_H"] or h != 2 * g["COLUMN_LABEL_LINE_H"]:
            out.append("%s 标签块高 %d != 2×%d（必须两行）"
                       % (name, h, g["COLUMN_LABEL_LINE_H"]))
        r1, r2 = item["line_rects"]
        if overlap(r1, r2):
            out.append("%s 两行文字相交：%s × %s" % (name, r1, r2))
        for r in (r1, r2):
            if r[0] < x0 or r[2] > x1 or r[1] < y0 or r[3] > y1:
                out.append("%s 某一行 %s 越出标签块 %s" % (name, r, item["rect"]))
            if r[3] - r[1] != g["COLUMN_LABEL_LINE_H"]:
                out.append("%s 行高 %d != %d" % (name, r[3] - r[1], g["COLUMN_LABEL_LINE_H"]))
        # ② 居中于对应开关列 + 宽度上限
        want = item["column_x"] + g["TOGGLE_BTN_W"] / 2.0
        got = x0 + item["width"] / 2.0
        if abs(got - want) > 0.5:
            out.append("%s 标签块心 %.1f 与开关列 %d 的列心 %.1f 不对齐（偏 %+.1fpx）"
                       % (name, got, item["column_x"], want, got - want))
        if item["width"] > g["COLUMN_LABEL_MAX_W"]:
            out.append("%s 标签块宽 %d > 上限 %d" % (name, item["width"], g["COLUMN_LABEL_MAX_W"]))
    # ③ 两标签块互不重叠，缝 >= 4px
    a = ("autocraft", items["autocraft"]["rect"])
    b = ("overflow", items["overflow"]["rect"])
    left, right = (a, b) if a[1][0] <= b[1][0] else (b, a)
    if overlap(left[1], right[1]):
        out.append("两个列标签矩形相交：%s %s × %s %s" % (left[0], left[1], right[0], right[1]))
    elif right[1][0] - left[1][2] < 4:
        out.append("两个列标签之间只有 %dpx < 4px" % (right[1][0] - left[1][2]))
    # ④ 不压第一行的任何控件 / 标题带 / 插件槽列 / 面板边界
    row0 = g["ROW_FIRST_Y"]
    forbidden = {
        "第一行标记槽": rect(9, row0 + 3, 16, 16),
        "第一行[−]": rect(g["ROW_MINUS_X"], row0 + g["ROW_BOX_DY"], g["ROW_BTN_W"], g["ROW_BTN_H"]),
        "第一行输入框": rect(g["ROW_BOX_X"], row0 + g["ROW_BOX_DY"], g["ROW_BOX_W"], g["ROW_BOX_H"]),
        "第一行[+]": rect(g["ROW_PLUS_X"], row0 + g["ROW_BOX_DY"], g["ROW_BTN_W"], g["ROW_BTN_H"]),
        "第一行自动合成开关": rect(g["AUTOCRAFT_BTN_X"], row0 + g["TOGGLE_DY"],
                                 g["TOGGLE_BTN_W"], g["TOGGLE_BTN_H"]),
        "第一行过量销毁开关": rect(g["OVERFLOW_BTN_X"], row0 + g["TOGGLE_DY"],
                                 g["TOGGLE_BTN_W"], g["TOGGLE_BTN_H"]),
        "插件槽列": rect(187, 6, 17, 107),
    }
    if title_lines:
        forbidden["标题带"] = rect(8, g["TITLE_Y"], g["TITLE_BAND_W"],
                                  g["TITLE_LINE_H"] * len(title_lines))
    for name, item in items.items():
        r = item["rect"]
        for other, orect in forbidden.items():
            if overlap(r, orect):
                out.append("%s 列标签 %s 与%s %s 相交" % (name, r, other, orect))
        if r[0] < 4 or r[1] < 4 or r[2] > panel_w - 4 or r[3] > panel_h - 4:
            out.append("%s 列标签 %s 越出面板内容区 4..%d / 4..%d"
                       % (name, r, panel_w - 4, panel_h - 4))
    # ⑤ 标签块底 <= 第一行控件顶（不压第一行）
    row0_top = g["ROW_FIRST_Y"] + min(g["ROW_BOX_DY"], g["TOGGLE_DY"])
    for name, item in items.items():
        if item["rect"][3] > row0_top:
            out.append("%s 标签块底 %d > 第一行控件顶 %d" % (name, item["rect"][3], row0_top))
    return out


def wrap_title(title, width):
    """复刻 Font#split 的贪心按词折行（只用于断言标题不会压到列标签带）。"""
    lines, current = [], ""
    for word in title.split(" "):
        candidate = word if not current else current + " " + word
        if current and text_width(candidate) > width:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


def column_label_source_problems(screen, geom):
    """源码判据：4 个缓存 Component（两行各一）、只画 2 次、走唯一几何实现、无阴影。"""
    out = []
    for key in COLUMN_LABEL_KEYS:
        for suffix in ("", ".2"):
            token = 'LANG + "%s%s"' % (key, suffix)
            if screen.count(token) != 1:
                out.append("界面里 %s 出现 %d 次（应为恰好 1 处缓存字段）"
                           % (token, screen.count(token)))
    for field in ("COLUMN_LABEL_AUTOCRAFT_1", "COLUMN_LABEL_AUTOCRAFT_2",
                  "COLUMN_LABEL_OVERFLOW_1", "COLUMN_LABEL_OVERFLOW_2"):
        if not re.search(r"private static final Component %s\s*=\s*Component\.translatable\(" % field,
                         screen):
            out.append("列标签 Component 不是 static final 缓存字段：%s" % field)
    body = method_body(screen, "protected void renderLabels(")
    if "Component.translatable" in body or "Component.literal" in body:
        out.append("renderLabels 每帧拼 Component（应只画缓存字段）")
    if body.count("drawColumnLabel(") != 2:
        out.append("renderLabels 里 drawColumnLabel 调用 %d 次（应为 2）"
                   % body.count("drawColumnLabel("))
    helper = method_body(screen, "private void drawColumnLabel(")
    if "RsccKeeperGeometry.columnLabelX(" not in helper:
        out.append("列标签 x 没走唯一几何实现 RsccKeeperGeometry.columnLabelX")
    for token in ("COLUMN_LABEL_Y", "COLUMN_LABEL_LINE_H"):
        if token not in helper:
            out.append("列标签没引用 %s（两行行距必须与几何常量同源）" % token)
    if helper.count(", false);") < 2:
        out.append("列标签某一行有阴影（drawString 末参不是 false）")
    gbody = method_body(geom, "public static int columnLabelX(")
    if "columnX + TOGGLE_BTN_W / 2 - textWidth / 2" not in gbody:
        out.append("RsccKeeperGeometry.columnLabelX 的居中公式被改动")
    if "private static final int COLUMN_LABEL_Y = RsccKeeperGeometry.COLUMN_LABEL_Y;" not in screen:
        out.append("界面 COLUMN_LABEL_Y 没有直接引用几何常量（出现第二处魔法数字）")
    return out


def copy_items(items):
    return {name: dict(item) for name, item in items.items()}


OLD_SHORT_LABELS = ("自动", "销毁")


def section_column_labels():
    section("④ 两列列标签：两行、完整词（自动合成 / 过量销毁）、列心对齐、不相交")
    geom = read(GEOMETRY)
    screen = read(ADV_SCREEN)
    tables = load_lang()

    # 文本键：中英成对、非空
    for key in COLUMN_LABEL_KEYS:
        for suffix in ("", ".2"):
            k = LANG_ADV + key + suffix
            zh = tables["zh_cn.json"].get(k)
            en = tables["en_us.json"].get(k)
            check(bool(zh) and bool(en), "列标签键中英成对且非空：%s（zh=%r / en=%r）"
                  % (key + suffix, zh, en))

    for lang in ("zh_cn.json", "en_us.json"):
        g, items = label_spec(geom, tables, lang)
        title = tables[lang].get("block.rs_create_compat.advanced_quantity_keeper", "")
        title_lines = wrap_title(title, g["TITLE_BAND_W"])
        print("       [%s] 列标签几何（精灵坐标，两行）：" % lang)
        for name in ("autocraft", "overflow"):
            item = items[name]
            x0, y0, x1, y1 = item["rect"]
            print("         %-10s x=%3d..%3d  y=%2d..%2d  (%d×%d) 行1=%r 行2=%r 列心 %d = 开关列 %d 的列心 %d"
                  % (name, x0, x1 - 1, y0, y1 - 1, item["width"], y1 - y0,
                     item["lines"][0], item["lines"][1], x0 + item["width"] // 2,
                     item["column_x"], item["column_x"] + g["TOGGLE_BTN_W"] // 2))
        bad = (full_word_problems(items, lang)
               + column_label_problems(g, items, title_lines))
        check(not bad, "[%s] 两行完整词 %r / %r：两行不重叠、列心对齐、缝 ≥ 4px、不压第一行 / 标题带 / 插件槽列"
              % (lang, items["autocraft"]["lines"], items["overflow"]["lines"]), str(bad[:3]))
        print("       标题折行（≤ %dpx）：%s" % (g["TITLE_BAND_W"], title_lines))
        check(max(text_width(line) for line in title_lines) <= g["TITLE_BAND_W"],
              "[%s] 标题折行后最宽 %dpx ≤ 标题带 %dpx（不会压到列标签）"
              % (lang, max(text_width(line) for line in title_lines), g["TITLE_BAND_W"]))

    g, items = label_spec(geom, tables, "zh_cn.json")
    check(g["COLUMN_LABEL_Y"] + g["COLUMN_LABEL_H"] <= g["ROW_FIRST_Y"] + g["ROW_BOX_DY"],
          "纵向：标签块 %d..%d ≤ 第一行控件顶 %d（不压第一行）"
          % (g["COLUMN_LABEL_Y"], g["COLUMN_LABEL_Y"] + g["COLUMN_LABEL_H"],
             g["ROW_FIRST_Y"] + g["ROW_BOX_DY"]))
    print("       纵向：标题 6..23（折行后可两行）/ 列标签 4..21 / 第一行控件 26 起 ⇒ 无重叠")

    bad = column_label_source_problems(screen, geom)
    check(not bad, "界面：4 个 static final 缓存 Component、两行各画一次、x 走 columnLabelX、无阴影", str(bad))

    # 反例自证一：旧的单行短词「自动 / 销毁」⇒ 完整词判据判负
    short = copy_items(items)
    short["autocraft"]["lines"] = (OLD_SHORT_LABELS[0], "")
    short["overflow"]["lines"] = (OLD_SHORT_LABELS[1], "")
    res = full_word_problems(short, "zh_cn.json")
    check(bool(res) and any("应为" in item for item in res),
          "反例自证：只显示「自动 / 销毁」⇒ 完整词判据判负", str(res[:2]))

    # 反例自证二：标签压到第一行资源 / 控件 ⇒ 判负
    bad_items = copy_items(items)
    x0, _y0, x1, _y1 = bad_items["autocraft"]["rect"]
    bad_items["autocraft"]["rect"] = (x0, g["ROW_FIRST_Y"] + 8, x1, g["ROW_FIRST_Y"] + 8 + g["COLUMN_LABEL_H"])
    res = column_label_problems(g, bad_items, [])
    check(any("第一行" in item for item in res),
          "反例自证：标签压到第一行 ⇒ 判负（%d 条）" % len(res), str(res[:1]))

    # 反例自证三：标签与开关列不对齐（x 右移 5px）⇒ 判负
    bad_items = copy_items(items)
    x0, y0, x1, y1 = bad_items["autocraft"]["rect"]
    bad_items["autocraft"]["rect"] = (x0 + 5, y0, x1 + 5, y1)
    res = column_label_problems(g, bad_items, [])
    check(any("不对齐" in item for item in res), "反例自证：列标签与开关列不对齐 ⇒ 判负",
          str(res[:1]))

    # 反例自证四：两个标签同文本 ⇒ 判负
    same = copy_items(items)
    same["overflow"]["lines"] = same["autocraft"]["lines"]
    res = full_word_problems(same, "zh_cn.json")
    check(any("完全相同" in item for item in res), "反例自证：两个列标签同文本 ⇒ 判负", str(res[:2]))


# =====================================================================
# ⑤ 「[+] 右侧无重复数字」+ 行内零文字（源码级 + 几何级 + 反例自证）
# =====================================================================
DRAW_METHODS = ("drawTitle", "drawColumnLabel")


def draw_string_sites(source):
    """返回 [(method_name, line_text)]：整份界面源码里所有 drawString( 调用及其所在方法。"""
    code = strip_java_comments(source)
    out = []
    for method_name in DRAW_METHODS + ("renderLabels", "render", "drawBlockedOutline", "init"):
        signature = "private void %s(" % method_name
        if method_name == "renderLabels":
            signature = "protected void renderLabels("
        elif method_name == "render":
            signature = "public void render("
        elif method_name == "init":
            signature = "protected void init("
        elif method_name == "drawTitle":
            signature = "private void drawTitle("
        if signature not in code:
            continue
        body = method_body(code, signature)
        for line in body.split("\n"):
            if "drawString(" in line:
                out.append((method_name, line.strip()))
    return out


def row_text_problems(source):
    """行内（+ 右侧 / 每行）不许有任何文字：drawString 只允许出现在 drawTitle / drawColumnLabel。"""
    out = []
    sites = draw_string_sites(source)
    code = strip_java_comments(source)
    total = code.count("drawString(")
    if len(sites) != total:
        out.append("有 drawString 出现在未知方法里（%d/%d）：行内文字会变成重复数字"
                   % (len(sites), total))
    for method_name, line in sites:
        if method_name not in DRAW_METHODS:
            out.append("%s 里出现 drawString：%s" % (method_name, line[:70]))
    # 行内数值 / 目标值绝不能进 renderLabels
    body = method_body(strip_java_comments(source), "protected void renderLabels(")
    for token in ("getTarget(", "currentValue(", "RsccNumberField", "targetBoxes"):
        if token in body:
            out.append("renderLabels 引用了 %s（行内会出现重复的目标值）" % token)
    return out


def section_no_duplicate_number(spec):
    section("⑤ [+] 右侧无重复数字 + 行内零文字（源码级 + 几何级）")
    screen = read(ADV_SCREEN)
    bad = row_text_problems(screen)
    check(not bad, "界面 drawString 只出现在 drawTitle / drawColumnLabel；renderLabels 不碰任何数值",
          str(bad[:3]))
    check(screen.count("drawString(") == 3,
          "整份界面只有 3 处 drawString（标题 1 + 两列标签各 1）—— 行内一个文字都没有",
          str(screen.count("drawString(")))

    bad = duplicate_number_geometry_problems(spec)
    region = duplicate_number_region(spec, ROW_FIRST_Y)
    check(not bad, "[+] 右缘 %d 到自动合成列 %d 只有 %dpx（< 12px 的两位数宽）⇒ 结构上画不下重复数字"
          % (region[0], region[0] + (region[2] - region[0]), region[2] - region[0]), str(bad))

    # 反例自证：往 renderLabels 里塞一句「画当前目标值」⇒ 判负
    fake = screen.replace(
        "        drawTitle(guiGraphics);",
        "        drawTitle(guiGraphics);\n"
        "        guiGraphics.drawString(font, Component.literal(String.valueOf(menu.getTarget(0))),\n"
        "            PLUS_SPRITE_X + NUM_BTN_W + 2, ROW_Y + 6, COLOR_TITLE, false);")
    if fake == screen:
        raise SystemExit("反例变异失败（找不到 renderLabels 锚点）")
    res = row_text_problems(fake)
    check(bool(res) and any("renderLabels" in item for item in res),
          "反例自证：保留「[+] 右侧重复数字」的写法 ⇒ 判负", str(res[:2]))

    # 反例自证：几何上给重复数字留出 12px 的带 ⇒ 判负
    wide = {name: tuple(v) for name, v in spec.items()}
    _px, pdy, pw, ph = wide["[+]"]
    ax, _ady, _aw, _ah = wide["自动合成"]
    wide["[+]"] = (ax - 12 - pw, pdy, pw, ph)  # 把 [+] 左移，缝变成 12px
    res = duplicate_number_geometry_problems(wide)
    check(bool(res), "反例自证：给重复数字留出 12px 缝隙 ⇒ 判负", str(res[:2]))


# =====================================================================
# ⑥ 上限证明：74 是行内最大宽（右留白 / 列标签两条独立判据）；单行完整词必然相交
# =====================================================================
# 插件槽列（Menu 187..204，y 6..113）：列标签块绝不能碰（背景 PNG 的槽框在那里）
PLUG_COLUMN = (187, 6, 204, 113)
# 第 84 轮定的口径：右列开关右缘距插件槽列至少 8px（本轮几何放大的前提之一是「不放松它」）
RIGHT_RESERVE = 8
# 单行完整词（若两行合并成一行要画的文本；en 用连字符拼写，与「Auto-craft / Over-flow」一致）
FULL_WORDS = {
    "zh_cn.json": {"autocraft": "自动合成", "overflow": "过量销毁"},
    "en_us.json": {"autocraft": "Auto-craft", "overflow": "Over-flow"},
}


def geom_subset(geom):
    """⑥ 只用到的常量（与界面 / 几何类同源）。"""
    return {name: java_int_const(geom, name) for name in (
        "ROW_MINUS_X", "ROW_BOX_X", "ROW_BOX_W", "ROW_BOX_H", "ROW_PLUS_X", "ROW_BTN_W",
        "ROW_BTN_H", "ROW_BOX_DY", "AUTOCRAFT_BTN_X", "OVERFLOW_BTN_X", "TOGGLE_BTN_W",
        "TOGGLE_BTN_H", "TOGGLE_DY", "COLUMN_LABEL_MAX_W")}


def row_layout(g, box_w):
    """按同一批常量推导一整行（唯一公式）：− / 框 / + / 自动合成 / 过量销毁。"""
    btn_w = g["ROW_BTN_W"]
    minus_x = g["ROW_MINUS_X"]
    box_x = minus_x + btn_w + MIN_GAP
    plus_x = box_x + box_w + MIN_GAP
    auto_x = plus_x + btn_w + MIN_GAP
    column_gap = g["OVERFLOW_BTN_X"] - (g["AUTOCRAFT_BTN_X"] + g["TOGGLE_BTN_W"])
    over_x = auto_x + g["TOGGLE_BTN_W"] + column_gap
    return {
        "[-]": (minus_x, g["ROW_BOX_DY"], btn_w, g["ROW_BTN_H"]),
        "输入框": (box_x, g["ROW_BOX_DY"], box_w, g["ROW_BOX_H"]),
        "[+]": (plus_x, g["ROW_BOX_DY"], btn_w, g["ROW_BTN_H"]),
        "自动合成": (auto_x, g["TOGGLE_DY"], g["TOGGLE_BTN_W"], g["TOGGLE_BTN_H"]),
        "过量销毁": (over_x, g["TOGGLE_DY"], g["TOGGLE_BTN_W"], g["TOGGLE_BTN_H"]),
    }


def derived_max_width(g):
    """由「右留白 8px + 列间距 13px + 四个 4px 缝」反解输入框最大宽度（唯一公式）。"""
    column_gap = g["OVERFLOW_BTN_X"] - (g["AUTOCRAFT_BTN_X"] + g["TOGGLE_BTN_W"])
    btn_w = g["ROW_BTN_W"]
    return ((BAND_RIGHT - RIGHT_RESERVE)
            - g["TOGGLE_BTN_W"] - column_gap - g["TOGGLE_BTN_W"]
            - MIN_GAP - btn_w - MIN_GAP
            - (g["ROW_MINUS_X"] + btn_w + MIN_GAP))


def ceiling_problems(geom, tables, box_w):
    """给定输入框宽度（开关列 + 列标签一起右移）后仍必须满足的判据（空 = 可行）。"""
    g = geom_subset(geom)
    layout = row_layout(g, box_w)
    out = []
    over_x, _ody, over_w, _oh = layout["过量销毁"]
    over_right = over_x + over_w
    if over_right > BAND_RIGHT - RIGHT_RESERVE:
        out.append("过量销毁右缘 %d 距插件槽列只剩 %dpx < %dpx（右留白被吃掉）"
                   % (over_right, BAND_RIGHT - over_right, RIGHT_RESERVE))
    columns = (layout["自动合成"][0], over_x)
    for lang in ("zh_cn.json", "en_us.json"):
        _lg, items = label_spec(geom, tables, lang, columns=columns)
        left = items["autocraft"]["rect"]
        right = items["overflow"]["rect"]
        seam = right[0] - left[2]
        if seam < MIN_GAP:
            out.append("[%s] 两列标签缝 %dpx < %dpx（%s × %s）"
                       % (lang, seam, MIN_GAP, left, right))
        for name, item in items.items():
            if overlap(item["rect"], PLUG_COLUMN):
                out.append("[%s] %s 列标签 %s 压到插件槽列 %s"
                           % (lang, name, item["rect"], PLUG_COLUMN))
    return out


def section_ceiling():
    section("⑥ 上限证明：ROW_BOX_W=74 是最大值；单行完整词必然相交（两行是唯一解）")
    geom = read(GEOMETRY)
    tables = load_lang()
    g = geom_subset(geom)

    bad = ceiling_problems(geom, tables, g["ROW_BOX_W"])
    check(not bad,
          "现有宽度 %d×%d 满足：右留白 ≥ %dpx、两列标签缝 ≥ %dpx、不压插件槽列 %d..%d"
          % (g["ROW_BOX_W"], g["ROW_BOX_H"], RIGHT_RESERVE, MIN_GAP,
             PLUG_COLUMN[0], PLUG_COLUMN[2]), str(bad[:3]))

    max_w = derived_max_width(g)
    check(max_w == g["ROW_BOX_W"],
          "反解上限 %d == ROW_BOX_W %d（不是随手写的数：右留白 %d + 列间距 %d + 四个 %dpx 缝）"
          % (max_w, g["ROW_BOX_W"], RIGHT_RESERVE,
             g["OVERFLOW_BTN_X"] - (g["AUTOCRAFT_BTN_X"] + g["TOGGLE_BTN_W"]), MIN_GAP))
    check(g["ROW_BOX_X"] == g["ROW_MINUS_X"] + g["ROW_BTN_W"] + MIN_GAP
          and g["ROW_PLUS_X"] == g["ROW_BOX_X"] + g["ROW_BOX_W"] + MIN_GAP,
          "ROW_BOX_X=%d / ROW_PLUS_X=%d 仍由「4px 缝」链式推出（没有第二套布局公式）"
          % (g["ROW_BOX_X"], g["ROW_PLUS_X"]))

    layout = row_layout(g, g["ROW_BOX_W"])
    column_gap = g["OVERFLOW_BTN_X"] - (g["AUTOCRAFT_BTN_X"] + g["TOGGLE_BTN_W"])
    print("       行内宽度账（控件带 %d..%d）：左留白 %d + [−] %d + 缝 %d + 框 %d + 缝 %d"
          " + [+] %d + 缝 %d + 自动合成 %d + 列间距 %d + 过量销毁 %d + 右留白 %d"
          % (BAND_LEFT, BAND_RIGHT,
             layout["[-]"][0] - BAND_LEFT, g["ROW_BTN_W"], MIN_GAP, g["ROW_BOX_W"], MIN_GAP,
             g["ROW_BTN_W"], MIN_GAP, g["TOGGLE_BTN_W"], column_gap, g["TOGGLE_BTN_W"],
             BAND_RIGHT - (layout["过量销毁"][0] + g["TOGGLE_BTN_W"])))

    # 反例自证：+2 ⇒ 右留白不够；+4 ⇒ 英文「Over」标签块压插件槽列（两条独立判据）
    for extra, expect in ((2, "右留白"), (4, "插件槽列")):
        bad = ceiling_problems(geom, tables, max_w + extra)
        check(bool(bad) and any(expect in item for item in bad),
              "反例自证：输入框 %d（+%d）⇒ 判负（%s）"
              % (max_w + extra, extra, "；".join(bad[:2])), str(bad[:3]))

    # 单行完整词必然相交 ⇒ 两行不是「想拆」，而是唯一解（数值证明）
    pitch = g["OVERFLOW_BTN_X"] - g["AUTOCRAFT_BTN_X"]
    for lang in ("zh_cn.json", "en_us.json"):
        widths = [text_width(FULL_WORDS[lang][key]) for key in ("autocraft", "overflow")]
        seam = pitch - (widths[0] + widths[1]) / 2.0
        check(seam < MIN_GAP,
              "[%s] 单行完整词 %r(%dpx) / %r(%dpx)，两列列距 %d ⇒ 缝 %+.0fpx < %dpx（必然相交）"
              % (lang, FULL_WORDS[lang]["autocraft"], widths[0],
                 FULL_WORDS[lang]["overflow"], widths[1], pitch, seam, MIN_GAP))
        _lg, two = label_spec(geom, tables, lang)
        two_seam = two["overflow"]["rect"][0] - two["autocraft"]["rect"][2]
        check(two_seam >= MIN_GAP and two["autocraft"]["width"] <= g["COLUMN_LABEL_MAX_W"]
              and two["overflow"]["width"] <= g["COLUMN_LABEL_MAX_W"],
              "[%s] 拆两行后块宽 %d / %d ≤ %d ⇒ 缝 %dpx ≥ %dpx（这就是「放不下就两行」的数值）"
              % (lang, two["autocraft"]["width"], two["overflow"]["width"],
                 g["COLUMN_LABEL_MAX_W"], two_seam, MIN_GAP))


if __name__ == "__main__":
    spec = section_geometry()
    section_controls()
    section_fluid()
    section_column_labels()
    section_no_duplicate_number(spec)
    section_ceiling()
    print("\n" + "=" * 68)
    if problems:
        for item in problems:
            print("  [X] %s" % item)
    print("问题总数: %d" % len(problems))
    sys.exit(1 if problems else 0)
