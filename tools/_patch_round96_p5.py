# -*- coding: utf-8 -*-
"""第 96 轮复核 P5：把 round96 自检的「每页 8 条」几何口径改成「每页 12 条 + 不与其它控件相交」。

改动点：
1. B1 从「2 列 × 4 行 = 8 条」改成「2 列 × 6 行 = 12 条（行高 12px / 图标 12px）」，
   并把 ROW_H / ICON_SIZE / ICON_SCALE / NAME_X / SCROLLBAR_H 的推导一并断言；
2. 新增 B6：**几何不相交断言**（条目区 vs 命名框 / 确定按钮 /「取全部」按钮 / 升级槽 / 滚动条 /
   背包标签 / 面板边界）+ 可读性断言（行高 ≥ 图标、图标 == 12、名称可用宽度够）。
   全部用源码里的坐标常量算，不跑游戏；
3. B4（列表渲染）的绘制断言跟着改成「缩放 pose + 悬停高亮」的新写法；
4. 反例（MUTATIONS）相应更新：行数 < 12 ⇒ 判负；条目区下移压背包 ⇒ 判负；行高 < 图标 ⇒ 判负。
"""
import io
import re

P = "tools/selfcheck_round96_receiver.py"
text = io.open(P, encoding="utf-8").read()


def replace_check(src, cid, new_block):
    start = src.index('@check("%s",' % cid)
    nxt = src.find("\n@check(", start + 1)
    end = nxt if nxt != -1 else len(src)
    return src[:start] + new_block.rstrip("\n") + "\n\n" + src[end + 1:]


B1 = '''@check("B1", "菜单：每页 2 列 × 6 行 = 12 条（行高 12px / 图标 12px）；固定 16 格实现已彻底移除")
def check_b1(src, zh, en):
    menu = src["menu_r"]
    consts = int_consts(menu)
    need = {
        "PAGE_COLS = 2": r"public static final int PAGE_COLS = 2;",
        "PAGE_ROWS = 6": r"public static final int PAGE_ROWS = 6;",
        "PAGE_SIZE 由行列推导": r"public static final int PAGE_SIZE = PAGE_COLS \\* PAGE_ROWS;",
        "ROW_H = 12": r"public static final int ROW_H = 12;",
        "ICON_SIZE = 12": r"public static final int ICON_SIZE = 12;",
        "ICON_SCALE = ICON_SIZE / 16": r"public static final float ICON_SCALE = ICON_SIZE / 16\\.0F;",
        "NAME_X = ICON_SIZE + 3": r"public static final int NAME_X = ICON_SIZE \\+ 3;",
        "SCROLLBAR_H = 行数 × 行高": r"public static final int SCROLLBAR_H = PAGE_ROWS \\* ROW_H;",
        "PLAYER_START 由升级槽推导": r"public static final int PLAYER_START = UPGRADE_START \\+ UPGRADE_COUNT;",
    }
    gone = [name for name, pattern in need.items() if not has(menu, pattern)]
    if gone:
        return False, "菜单分页常量缺：" + "、".join(gone)
    size = consts.get("PAGE_COLS", 0) * consts.get("PAGE_ROWS", 0)
    if size < 12:
        return False, ("每页条目数 = %s × %s = %s < 12（第 96 轮复核 P5 的下限："
                       "缓存容量 1728 件、种类无上限，8 条/页要翻 200+ 页）"
                       % (consts.get("PAGE_COLS"), consts.get("PAGE_ROWS"), size))
    if consts.get("PAGE_SIZE") not in (None, size):
        return False, "PAGE_SIZE 常量（%s）与 PAGE_COLS × PAGE_ROWS（%s）不一致" % (
            consts.get("PAGE_SIZE"), size)
    for stale in ("CACHE_VIEW_COUNT", "CACHE_COLS", "CacheSlot", "SimpleMirrorContainer"):
        if has(menu, r"\\b" + stale + r"\\b"):
            return False, "仍残留旧 16 格固定实现的 %s（第 96 轮要求整段替换）" % stale
    return True, "%d 列 × %d 行 = %d 条/页（行高 %s px）；旧固定格实现零残留" % (
        consts["PAGE_COLS"], consts["PAGE_ROWS"], size, consts.get("ROW_H"))'''


B4 = '''@check("B4", "列表覆盖全部种类：逐行渲染（缩放图标 + 名称 + 数量）+ 悬停高亮 + 四个滚动入口")
def check_b4(src, zh, en):
    scr = src["scr_r"]
    need = {
        "逐行渲染": r"for \\(int i = 0; i < entries\\.size\\(\\); i\\+\\+\\)",
        "图标按 ICON_SCALE 缩放": r"pose\\.scale\\(NetworkResourceReceiverMenu\\.ICON_SCALE,",
        "物品图标": r"guiGraphics\\.renderItem\\(entry\\.item\\(\\), 0, 0\\)",
        "流体图标": r"GhostMarkerRenderer\\.renderFluid\\(guiGraphics, 0, 0, entry\\.fluid\\(\\)\\)",
        "数量文本": r"RsccNumberField\\.compact\\(entry\\.amount\\(\\)\\)",
        "名称按 NAME_X 偏移": r"x \\+ NetworkResourceReceiverMenu\\.NAME_X",
        "名称按像素宽裁剪": r"trim\\(name == null \\? Component\\.empty\\(\\) : name, nameMax\\)",
        "行悬停高亮": r"if \\(i == hovered\\)",
        "行 tooltip（完整名称）": r"private void renderEntryTooltip",
        "滚动条": r"new ScrollbarWidget\\(",
        "滚轮翻页": r"requestPage\\(page - \\(int\\) Math\\.signum\\(scrollY\\)\\)",
        "页码箭头可点": r"final int arrow = pageArrowAt\\(mouseX, mouseY\\);",
    }
    gone = [name for name, pattern in need.items() if missing(scr, pattern)]
    if gone:
        return False, "接收器界面缺少：" + "、".join(gone)
    labels = re.search(r"protected void renderLabels\\(.*?\\n    \\}", scr, re.S)
    if labels is None or missing(labels.group(0), r"renderEntries\\(guiGraphics, mouseX, mouseY\\);") \\
            or missing(labels.group(0), r"renderPageHeader\\(guiGraphics\\);"):
        return False, "renderLabels 没有真正调用 renderEntries / renderPageHeader（列表画不出来）"
    return True, "逐行（缩放图标 + 名称 + 数量）+ 悬停高亮 + 滚动条 / 滚轮 / 箭头 / 行 tooltip"


B6 = '''@check("B6", "几何：条目区不与命名框 / 确定 / 「取全部」/ 升级槽 / 滚动条 / 背包标签 / 面板相交，"
             "且行高放得下 12px 图标 + 9px 文字")
def check_b6(src, zh, en):
    menu = src["menu_r"]
    scr = src["scr_r"]
    m = int_consts(menu)
    s = int_consts(scr)
    need_keys = ["LIST_X0", "LIST_Y0", "ROW_H", "PAGE_COLS", "PAGE_ROWS", "COL_W", "COL_GAP",
                 "ICON_SIZE", "NAME_X", "SCROLLBAR_X", "SCROLLBAR_Y", "SCROLLBAR_H",
                 "SCROLLBAR_W", "INV_Y0", "PANEL_W", "PANEL_H", "UPGRADE_X"]
    missing_menu = [k for k in need_keys if k not in m]
    need_screen = ["BOX_X", "BOX_Y", "BOX_W", "BOX_H", "OK_X", "OK_W",
                   "TAKE_ALL_X", "TAKE_ALL_Y", "TAKE_ALL_W", "TAKE_ALL_H"]
    missing_scr = [k for k in need_screen if k not in s]
    if missing_menu or missing_scr:
        return False, "几何常量缺失：menu=%s / screen=%s" % (missing_menu, missing_scr)

    # 条目区（最后一个行 / 列的真实边界，不含 gap）
    rx0 = m["LIST_X0"]
    ry0 = m["LIST_Y0"]
    rx1 = m["LIST_X0"] + m["PAGE_COLS"] * (m["COL_W"] + m["COL_GAP"]) - m["COL_GAP"]
    ry1 = m["LIST_Y0"] + m["PAGE_ROWS"] * m["ROW_H"]
    rows = (rx0, ry0, rx1, ry1)

    def overlaps(a, b):
        return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]

    conflicts = []
    boxes = {
        "命名输入框": (s["BOX_X"], s["BOX_Y"], s["BOX_X"] + s["BOX_W"], s["BOX_Y"] + s["BOX_H"]),
        "确定按钮": (s["OK_X"], s["BOX_Y"], s["OK_X"] + s["OK_W"], s["BOX_Y"] + s["BOX_H"]),
        "「取全部」按钮": (s["TAKE_ALL_X"], s["TAKE_ALL_Y"],
                    s["TAKE_ALL_X"] + s["TAKE_ALL_W"], s["TAKE_ALL_Y"] + s["TAKE_ALL_H"]),
        "玩家背包（含标签行）": (0, m["INV_Y0"] - 10, m["PANEL_W"], m["PANEL_H"]),
    }
    for name, box in boxes.items():
        if overlaps(rows, box):
            conflicts.append("条目区 %s 与「%s」%s 相交" % (rows, name, box))
    # 滚动条：必须在条目区右侧、与条目区同源、不越出面板
    if not rx1 < m["SCROLLBAR_X"]:
        conflicts.append("条目区右边界 %s 未在滚动条（x=%s）左侧" % (rx1, m["SCROLLBAR_X"]))
    if m["SCROLLBAR_X"] + m["SCROLLBAR_W"] > m["PANEL_W"]:
        conflicts.append("滚动条右边界越出面板宽度 %s" % m["PANEL_W"])
    if m["SCROLLBAR_Y"] != m["LIST_Y0"] or m["SCROLLBAR_H"] != m["PAGE_ROWS"] * m["ROW_H"]:
        conflicts.append("滚动条几何（y=%s/h=%s）与条目区（y=%s/h=%s）不同源"
                         % (m["SCROLLBAR_Y"], m["SCROLLBAR_H"], m["LIST_Y0"],
                            m["PAGE_ROWS"] * m["ROW_H"]))
    # 升级槽栏：条目区整体在它左边
    if not rx1 < m["UPGRADE_X"]:
        conflicts.append("条目区右边界 %s 压到升级槽栏（x=%s）" % (rx1, m["UPGRADE_X"]))
    # 面板边界
    if rx1 >= m["PANEL_W"] or ry1 >= m["PANEL_H"]:
        conflicts.append("条目区 %s 越出面板 %s×%s" % (rows, m["PANEL_W"], m["PANEL_H"]))
    # 可读性：行高必须放得下「图标 + 9px 文字」；名称要有可用宽度
    if m["ICON_SIZE"] > m["ROW_H"]:
        conflicts.append("图标 %spx 比行高 %spx 还高（会压到下一行）"
                         % (m["ICON_SIZE"], m["ROW_H"]))
    if m["ROW_H"] < 11:
        conflicts.append("行高 %spx 放不下 9px 字体 + 上下留白" % m["ROW_H"])
    if m["COL_W"] - m["NAME_X"] < 60:
        conflicts.append("名称可用宽度只有 %spx（图标 + 数量之后会挤成 1~2 个字）"
                         % (m["COL_W"] - m["NAME_X"]))
    if conflicts:
        return False, "；".join(conflicts)
    return True, ("条目区 %s = %s 条（%s×%s，行高 %s）与 6 个相邻控件零相交；"
                  "图标 %spx ≤ 行高 %spx；名称可用宽度 %spx"
                  % (rows, m["PAGE_COLS"] * m["PAGE_ROWS"], m["PAGE_COLS"], m["PAGE_ROWS"],
                     m["ROW_H"], m["ICON_SIZE"], m["ROW_H"], m["COL_W"] - m["NAME_X"]))'''

for cid, block in (("B1", B1), ("B4", B4)):
    text = replace_check(text, cid, block)

# B6 插在 B5 之后（同类：界面几何）
anchor = '\n\n# ===========================================================================\n# ② 界面：流体可取 + 无上限可滚动资源列表'
assert anchor in text
text = text.replace(anchor, "\n\n" + B6.rstrip("\n") + "\n" + anchor, 1)

# ---------------- 常量解析工具（放在 has/missing 之后） ----------------
tool_anchor = '''def body_of(text, header_pattern):'''
tool_new = '''def int_consts(source_text):
    """把源码里的 `static final int NAME = <数字>;` 抽成 dict（只用于几何断言，不支持表达式）。"""
    found = {}
    for match in re.finditer(
            r"static\\s+final\\s+int\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*(-?\\d+)\\s*;", source_text):
        found[match.group(1)] = int(match.group(2))
    return found


def body_of(text, header_pattern):'''
assert tool_anchor in text
text = text.replace(tool_anchor, tool_new, 1)

# ---------------- 反例更新 ----------------
old_mut_b1 = '''    ("B1", "menu_r", [(r"public static final int PAGE_ROWS = 4;", "public static final int PAGE_ROWS = 3;")],
     "每页行数退回 3（覆盖不全，用户「格子太少」原样复现）"),'''
new_mut_b1 = '''    ("B1", "menu_r", [(r"public static final int PAGE_ROWS = 6;",
                       "public static final int PAGE_ROWS = 5;")],
     "每页行数退回 5（每页只剩 10 条 < 12，P5 的容量口径不达标）"),'''
assert old_mut_b1 in text
text = text.replace(old_mut_b1, new_mut_b1, 1)

old_mut_b4 = '''    ("B4", "scr_r", [(r"guiGraphics\\.renderItem\\(entry\\.item\\(\\), x, y\\);", "// mutated")],
     "物品行不再画图标"),'''
new_mut_b4 = '''    ("B4", "scr_r", [(r"guiGraphics\\.renderItem\\(entry\\.item\\(\\), 0, 0\\);", "// mutated")],
     "物品行不再画图标"),'''
assert old_mut_b4 in text
text = text.replace(old_mut_b4, new_mut_b4, 1)

# 追加 3 条 B6 反例（放在 B1 反例之后）
anchor_mut = '     "每页行数退回 5（每页只剩 10 条 < 12，P5 的容量口径不达标）"),'
add_mut = anchor_mut + '''
    ("B6", "menu_r", [(r"public static final int LIST_Y0 = 88;",
                       "public static final int LIST_Y0 = 120;")],
     "条目区整体下移 32px（会压到玩家背包标签 / 背包槽）"),
    ("B6", "menu_r", [(r"public static final int ROW_H = 12;",
                       "public static final int ROW_H = 10;")],
     "行高压到 10px（9px 字体 + 留白放不下）"),
    ("B6", "menu_r", [(r"public static final int ICON_SIZE = 12;",
                       "public static final int ICON_SIZE = 16;")],
     "图标保持 16px 却仍用 12px 行高（图标会压到下一行）"),'''
assert anchor_mut in text
text = text.replace(anchor_mut, add_mut, 1)

io.open(P, "w", encoding="utf-8").write(text)
print("patched round96 selfcheck (P5 geometry)")
