#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 126 轮自检：GUI 槽位 ↔ 背景槽框「对位」（用户反馈：槽位和背景存在错误的偏差）。

**约定（唯一权威）**：背景贴图里 17×17 槽框的左上角 = **Menu 槽位坐标 − 1**（`OFFSET = -1`）。
来源见 `tools/gui_slot_registry.py` 顶部注释（`tools/make_gui_gui.py` 第 21/905/1365/1495 行 +
4 组实证抽样），Runtime 侧同一条约定（`leftPos + slot.x - 1`）。

本脚本 = **规格守卫**：只读 PNG + 只读 `src/main/java`，不改 `src/**` 一个字节（写作用域只到
`tools/`、`docs/`）。数据源全部在 `tools/gui_slot_registry.py`（登记表），本文件只做判定。

四层判据
--------
A. **PNG 槽框对位**（`PAINTED`）：用 `pic/slot.png` 左上 17×17 做模板，在每张 GUI 背景上做精确
   模板匹配，聚成槽框集合；断言 `槽框集合 == {(Menu 坐标) + OFFSET}`。
   * `plain`：常规界面，逐格比对，打印「缺哪些 / 多哪些 / 整体偏几像素」；
   * `stretching`：RS 九段式拉伸界面（`AbstractStretchingScreen`），底图只烘焙了底部整块，
     期望的**贴图坐标**要按 `src_v + (menu_y - 1) - (top_h + 18*visible_rows)` 换算；
   * `orphan`：零引用的历史副本，判据 = 与 `mirror` 指向的 PNG 逐格相同 + 全仓库零引用。
B. **登记完整性**：
   * `textures/gui/**` 下任何 PNG 出现槽框但不在 `PAINTED`/`DECORATIVE` 里 ⇒ FAIL
     （文案「新界面必须登记并自检」——这就是用户要的「以后新增的自动纳入」）；
   * `src/main/java/**/*Menu.java` 任何一支不在 `MENUS` 里 ⇒ FAIL；
   * `DECORATIVE` 里声明 `frames=0` 的图真的出现槽框 ⇒ FAIL（说明该改成正式登记）。
C. **代码自绘槽框**（`CODE_FRAMES`）：`McGui.slotFrame(...)` 与 `blitSprite(Sprites.SLOT, ...)`
   的调用点必须逐个登记；与 Menu 槽位同源的必须 x/y 各带 `- 1`（`menu_slot=True`）。
   `McGui.slotFrame(` 的**总调用点数**必须等于登记数（新增自绘点没登记 ⇒ FAIL）。
D. **锚点锁**：`PAINTED[*].anchors` / `MENUS` 相关源码片段必须仍在（坐标数字逐字在其中）——
   防止「Menu 改了坐标、登记表没改」这种静默过期。

反例自证（本脚本被改坏时必须抓到自己，共 7 条）
------------------------------------------------
1. `OFFSET` 改成 0（=把约定改成「槽框 = Menu 坐标」）⇒ 6 个 plain 界面必须判负；
2. 删掉 `quantity_keeper.png` 的登记 ⇒ 必须报「有槽框但未登记」；
3. 把 `quantity_keeper` 玩家背包的 `x` 加 1 ⇒ 必须报缺 9 格 / 多 9 格；
4. 把 `unit_pattern_manager`（stretching）的 `visible_rows` 改错 ⇒ 必须判负；
5. 从 `MENUS` 删掉一支 Menu ⇒ 必须报「Menu 未登记」；
6. 把某条 `CODE_FRAMES.pattern` 里的 `- 1` 去掉 ⇒ 源码不再匹配 ⇒ 必须判负；
7. 模板改成 `pic/slot.png` 的 (1,1) 起 17×17 ⇒ 检出槽框数变化 ⇒ 必须判负。

纪律：不跑 gradlew / javac（Lead 统一编译）、不 git、不改 `src/**`、不改 docs 正文（只新建/修订
本任务的三份文件）。**任何判据与实现写法冲突时：先确认约定（−1）不变，再改锚点，绝不放宽判据。**

用法：`python tools/selfcheck_gui_slot_alignment.py`
退出码：0 = 全部通过；最后一行：ALL_PASS / FAILED。
"""

from __future__ import annotations

import copy
import glob
import io
import os
import re
import sys
import types

import numpy as np
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import gui_slot_registry as REG  # noqa: E402

JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
DOC = "docs/ROUND126_GUI_SLOT_ALIGNMENT.md"

FAILS = []


# ======================================================================================
# 基础工具
# ======================================================================================

def p(*parts):
    return os.path.join(ROOT, *parts)


def read_text(rel):
    try:
        with io.open(p(rel.replace("/", os.sep)), "r", encoding="utf-8", errors="replace") as f:
            return f.read()
    except IOError:
        return ""


_ALL_JAVA = None
_SLOTFRAME_COUNT = None


def all_java_text():
    global _ALL_JAVA
    if _ALL_JAVA is None:
        _ALL_JAVA = "\n".join(java_text(f) for f in java_files_rel())
    return _ALL_JAVA


def slot_frame_call_count():
    global _SLOTFRAME_COUNT
    if _SLOTFRAME_COUNT is None:
        _SLOTFRAME_COUNT = sum(len(re.findall(r"McGui\.slotFrame\(", java_text(f)))
                               for f in java_files_rel())
    return _SLOTFRAME_COUNT


_JAVA_CACHE = {}
_JAVA_FILES = None


def java_text(rel):
    if rel in _JAVA_CACHE:
        return _JAVA_CACHE[rel]
    path = os.path.join(JAVA_REL, rel.replace("/", os.sep))
    text = ""
    if os.path.isfile(path):
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read()
    _JAVA_CACHE[rel] = text
    return text


def java_files_rel():
    global _JAVA_FILES
    if _JAVA_FILES is None:
        base = p(JAVA_REL)
        out = []
        for path in glob.glob(os.path.join(base, "**", "*.java"), recursive=True):
            out.append(os.path.relpath(path, base).replace(os.sep, "/"))
        _JAVA_FILES = sorted(out)
    return _JAVA_FILES


# ======================================================================================
# PNG 槽框检测（精确模板匹配，numpy；不装任何额外依赖）
# ======================================================================================

def load_template(spec):
    im = Image.open(p(spec["png"].replace("/", os.sep))).convert("RGBA")
    a = np.array(im)
    return np.ascontiguousarray(a[spec["y"]:spec["y"] + spec["h"],
                                   spec["x"]:spec["x"] + spec["w"]])


def template_signature(tpl):
    """从模板推出「边框色 / 填充色」，并校验模板结构：顶行+左列同为边框色、内部全为填充色。

    返回 `(border_rgba, fill_rgba)`；结构不对（比如模板被改坏 / 取错位置）→ `None`。
    这样「把模板改坏」会直接让所有界面的检出结果变空 ⇒ 判负，而不是静默匹配不到。
    """
    colors = np.unique(tpl.reshape(-1, 4), axis=0)
    if len(colors) != 2:
        return None
    border = tpl[0, 0].copy()
    fill = tpl[1, 1].copy()
    if not (tpl[0, :, :] == border).all():
        return None
    if not (tpl[:, 0, :] == border).all():
        return None
    if not (tpl[1:, 1:, :] == fill).all():
        return None
    return border, fill


def detect_frames(png_rel, sig):
    """返回 PNG 内所有「17×17 = 槽框模板」的左上角坐标（按 y,x 排序）。

    模板只有 2 色：上/左各 1px 边框色 + 内部填充色，全部不透明。
    因此「精确匹配」等价于三条矩形全命中：顶行全边框、左列全边框、内部 16×16 全填充。
    用积分图做，26 张 PNG 全跑 < 2s。模板结构非法（sig is None）⇒ 一个都检不出。
    """
    if sig is None:
        return []
    border, fill = sig
    path = p(REG.GUI_ROOT, png_rel.replace("/", os.sep))
    if not os.path.isfile(path):
        return None
    img = np.array(Image.open(path).convert("RGBA"))
    h = w = REG.TEMPLATE["w"]
    H, W = img.shape[0], img.shape[1]
    if H < h or W < w:
        return []
    dark = np.all(img == border, axis=2)
    fillmask = np.all(img == fill, axis=2)

    def rect_all(mask, hh, ww):
        """所有 hh×ww 窗口「全 True」的位置图（左上角对齐）。"""
        ii = np.pad(mask.astype(np.int32), ((1, 0), (1, 0))).cumsum(0).cumsum(1)
        return (ii[hh:, ww:] - ii[:-hh, ww:] - ii[hh:, :-ww] + ii[:-hh, :-ww]) == hh * ww

    top = rect_all(dark, 1, w)          # (H, W-w+1)
    left = rect_all(dark, h, 1)         # (H-h+1, W)
    inner = rect_all(fillmask, h - 1, w - 1)  # (H-h+1, W-w+1)
    ok = np.zeros((H - h + 1, W - w + 1), dtype=bool)
    span = W - w + 1
    for y in range(H - h + 1):
        # 内部 16×16 的窗口左上角在 (y+1, x+1)，所以取 inner 的 y+1 行 / x+1 列
        row = top[y, :span] & left[y, :span] & inner[y + 1, 1:span + 1]
        if row.any():
            ok[y] = row
    ys, xs = np.nonzero(ok)
    return sorted((int(x), int(y)) for y, x in zip(ys, xs))


def gui_pngs():
    """`textures/gui/**` 下所有 PNG（相对路径，`/` 分隔，排序稳定）。"""
    base = p(REG.GUI_ROOT)
    out = []
    for path in glob.glob(os.path.join(base, "**", "*.png"), recursive=True):
        out.append(os.path.relpath(path, base).replace(os.sep, "/"))
    return sorted(out)


def detect_all(sig):
    """扫**全部** `textures/gui/**` PNG（不只看登记过的）——新增贴图也逃不掉。"""
    frames = {}
    for rel in gui_pngs():
        fr = detect_frames(rel, sig)
        frames[rel] = fr if fr is not None else []
    return frames


# ======================================================================================
# 期望槽框：由登记表（Menu 坐标）展开，再按约定 −1
# ======================================================================================

def expand_group(g, stretch, offset):
    px = g.get("pitch_x", REG.PITCH)
    py = g.get("pitch_y", REG.PITCH)
    out = []
    for r in range(g["rows"]):
        for c in range(g["cols"]):
            mx = g["x"] + c * px
            my = g["y"] + r * py
            fx = mx + offset
            if stretch is None:
                fy = my + offset
            else:
                bottom_top = stretch["top_h"] + REG.PITCH * stretch["visible_rows"]
                fy = stretch["src_v"] + (my + offset) - bottom_top
            out.append((int(fx), int(fy)))
    return out


def expected_frames(entry, offset):
    stretch = entry.get("stretch")
    out = set()
    for g in entry.get("groups", ()):
        if not g.get("painted", True):
            continue
        out.update(expand_group(g, stretch, offset))
    return out


def group_slot_sum(entry):
    total = 0
    for g in entry.get("groups", ()):
        total += g.get("slots", g["cols"] * g["rows"])
    return total


def painted_count(entry):
    return sum(g["cols"] * g["rows"] for g in entry.get("groups", ()) if g.get("painted", True))


# ======================================================================================
# 判定核心（纯函数：registry 数据 + 已检出槽框 ⇒ 结果列表；反例直接喂改坏的数据）
# ======================================================================================

def _fmt(seq, limit=6):
    seq = sorted(seq)
    head = " ".join("(%d,%d)" % xy for xy in seq[:limit])
    return head + (" …共 %d 个" % len(seq) if len(seq) > limit else "")


def verdict_painted(entry, frames, offset):
    png = entry["png"]
    actual = set(frames.get(png, []))
    kind = entry["kind"]
    if kind == "orphan":
        mirror = set(frames.get(entry["mirror"], []))
        if actual == mirror:
            return True, "orphan：%d 格与 %s 逐格相同" % (len(actual), entry["mirror"])
        return False, ("orphan 副本漂移：本图 %d 格 / %s %d 格，缺 %s ｜ 多 %s"
                       % (len(actual), entry["mirror"], len(mirror),
                          _fmt(mirror - actual), _fmt(actual - mirror)))
    exp = expected_frames(entry, offset)
    if actual == exp:
        return True, "%d 格（Menu−1 全对）" % len(actual)
    missing, extra = exp - actual, actual - exp
    hint = ""
    for dx, dy in ((1, 1), (-1, -1), (1, 0), (0, 1), (-1, 0), (0, -1)):
        shifted = set((x + dx, y + dy) for x, y in actual)
        if shifted == exp:
            hint = " → 整体偏 (%+d,%+d)" % (dx, dy)
            break
    detail = "%d/%d 格对不上" % (len(exp) - len(missing), len(exp))
    if missing:
        detail += "；缺 %s" % _fmt(missing)
    if extra:
        detail += "；多 %s" % _fmt(extra)
    return False, detail + hint


def results(reg, frames):
    """reg: 携带 PAINTED / MENUS / CODE_FRAMES / DECORATIVE / ORPHAN / OFFSET 的数据对象。"""
    out = []
    offset = reg.OFFSET

    # ---- A. PNG 槽框对位 + 槽数账目 ----
    for e in reg.PAINTED:
        ok, detail = verdict_painted(e, frames, offset)
        png = e["png"]
        if e["kind"] != "orphan":
            if e.get("menu_slots") is not None and group_slot_sum(e) != e["menu_slots"]:
                ok = False
                detail += "；槽数账目错（分组合计 %d ≠ menu_slots %d）" % (
                    group_slot_sum(e), e["menu_slots"])
            exp_n = painted_count(e)
            act_n = len(set(frames.get(png, [])))
            if ok is False and exp_n != act_n and "缺" not in detail:
                detail += "；烘焙格数 %d ≠ 检出 %d" % (exp_n, act_n)
        out.append(("PNG:" + png, ok, detail))

    # ---- B1. 未登记 / 未分类的贴图（新增贴图必须逐张分类，不许静默跳过） ----
    known = (set(e["png"] for e in reg.PAINTED) | set(e["png"] for e in reg.DECORATIVE)
             | set(e["png"] for e in reg.ORPHAN))
    for png in sorted(frames):
        if png in known:
            continue
        if frames[png]:
            out.append(("未登记:" + png, False,
                        "检出 %d 个槽框但登记表里没有 → 新界面必须登记并自检"
                        "（tools/gui_slot_registry.py 的 PAINTED）" % len(frames[png])))
        else:
            out.append(("未分类:" + png, False,
                        "新贴图（0 槽框）必须分类登记：有槽框 → PAINTED；没有 → DECORATIVE（写清 why）"))

    # ---- B2. 声明「无槽框」的图真的没有 ----
    for e in reg.DECORATIVE:
        got = len(frames.get(e["png"], []))
        ok = got == e["frames"]
        out.append(("DECOR:" + e["png"], ok,
                    "期望 %d 个槽框，实得 %d 个" % (e["frames"], got) if not ok else "0 槽框（如声明）"))

    # ---- B3. 零引用贴图 ----
    all_java = all_java_text()
    for e in reg.ORPHAN:
        name = e["png"]
        ok = name not in all_java
        out.append(("ORPHAN:" + name, ok,
                    "零引用（如声明）" if ok else "已被源码引用 → 必须改成正式登记并自检"))

    # ---- C. Menu 覆盖 ----
    registered = set(m["file"] for m in reg.MENUS)
    found = set(f for f in java_files_rel() if f.rsplit("/", 1)[-1].endswith("Menu.java"))
    for f in sorted(found - registered):
        out.append(("Menu未登记:" + f, False,
                    "Menu 类没有登记背景类型（bg= png/vanilla/code/none）→ "
                    "新界面必须登记并自检（tools/gui_slot_registry.py 的 MENUS）"))
    for f in sorted(registered - found):
        out.append(("Menu登记过期:" + f, False, "登记表里的 Menu 文件已不存在"))
    for m in reg.MENUS:
        if m["bg"] == "png":
            hit = [e for e in reg.PAINTED if e["png"] == m.get("png")]
            if not hit:
                out.append(("Menu背景未登记:" + m["file"], False,
                            "bg=png 但 %s 不在 PAINTED 里" % m.get("png")))
                continue
            if m["file"] not in hit[0].get("menus", ()):
                out.append(("Menu背景不一致:" + m["file"], False,
                            "%s 的 menus 列表里没有这支 Menu" % m.get("png")))
        elif m["bg"] in ("code", "none", "vanilla", "rs_framework"):
            out.append(("Menu背景:" + m["file"], True, "%s（无 PNG 槽背景）" % m["bg"]))

    # ---- D. 锚点锁 ----
    for e in reg.PAINTED:
        png = e["png"]
        for rel, pattern in e.get("anchors", ()):
            text = java_text(rel)
            if not text:
                out.append(("锚点:" + png, False, "源文件缺失 %s" % rel))
                continue
            ok = re.search(pattern, text) is not None
            out.append(("锚点:" + png + "|" + rel, ok,
                        "登记表与源码不一致（锚点失效：%s）→ 改坐标就要改登记表" % pattern
                        if not ok else "ok"))

    # ---- E. 代码自绘槽框 ----
    slot_frame_calls = slot_frame_call_count()
    reg_calls = sum(e["count"] for e in reg.CODE_FRAMES if e.get("api") == "slotFrame")
    out.append(("CODE:调用点总数", slot_frame_calls == reg_calls,
                "源码 %d 处 / 登记 %d 处 → 新的自绘槽框必须登记" % (slot_frame_calls, reg_calls)))
    for e in reg.CODE_FRAMES:
        text = java_text(e["file"])
        n = len(re.findall(e["pattern"], text))
        ok = n == e["count"]
        detail = "%d/%d 处命中" % (n, e["count"])
        if e["menu_slot"] and ok:
            if "- 1" not in e["pattern"] and not any("- 1" in x for x in e.get("extra", ())):
                ok = False
                detail += "；与 Menu 槽位同源却没写 `- 1`（约定：槽框 = Menu 坐标 − 1）"
        if e.get("extra"):
            for x in e["extra"]:
                if re.search(x, text) is None:
                    ok = False
                    detail += "；附加锚点失效 %s" % x
        out.append(("CODE:" + e["file"], ok, detail))
    return out


# ======================================================================================
# 输出
# ======================================================================================

def print_section(title, rows, always_show=False, summary=""):
    fails = [r for r in rows if not r[1]]
    print("\n=== %s ===" % title)
    if always_show:
        for name, ok, detail in rows:
            print("  %s %-40s %s" % ("OK  " if ok else "FAIL", name[4:][:40], detail))
        return fails
    if fails:
        for name, _, detail in fails:
            print("  FAIL %-44s %s" % (name[:44], detail))
    elif summary:
        print("  OK   %d 项全通过：%s" % (len(rows), summary))
    return fails


def compact(detail, limit=150):
    return detail if len(detail) <= limit else detail[:limit] + " …"


def group_counterexamples(data, tpl, sig, real_frames):
    print("\n=== 反例自证（把自检自己改坏 ⇒ 期望判负） ===")

    def run(mutate_data=None, mutate_tpl=None):
        d = copy.deepcopy(data) if mutate_data else data
        if mutate_data:
            mutate_data(d)
        frames = real_frames
        if mutate_tpl:
            frames = detect_all(template_signature(mutate_tpl(tpl)))
        return [r for r in results(d, frames) if not r[1]]

    def check_case(label, bad, prefix):
        hit = [r for r in bad if r[0].startswith(prefix)]
        print("  %s %-52s %s" % ("OK  " if hit else "FAIL", label[:52],
                                 ("判负 %d 条（如 %s）" % (len(hit), hit[0][0])) if hit
                                 else "没判负 —— 自检坏了"))
        if not hit:
            FAILS.append("反例:" + label)

    # C-1 约定被改成 0
    def c1(d):
        d.OFFSET = 0
    check_case("C-1 OFFSET 改成 0（把「−1」约定改成「=Menu 坐标」）", run(mutate_data=c1), "PNG:")

    # C-2 删登记
    def c2(d):
        d.PAINTED = [e for e in d.PAINTED if e["png"] != "quantity_keeper.png"]
    check_case("C-2 删掉 quantity_keeper.png 的登记", run(mutate_data=c2), "未登记:quantity_keeper.png")

    # C-3 坐标 +1
    def c3(d):
        for e in d.PAINTED:
            if e["png"] == "quantity_keeper.png":
                for g in e["groups"]:
                    if g["name"] == "玩家背包":
                        g["x"] += 1
    check_case("C-3 玩家背包 x 多算 1px", run(mutate_data=c3), "PNG:quantity_keeper.png")

    # C-4 拉伸换算改错
    def c4(d):
        for e in d.PAINTED:
            if e["png"] == "unit_pattern_manager.png":
                e["stretch"]["visible_rows"] = 2
    check_case("C-4 stretching 的 visible_rows 改错", run(mutate_data=c4), "PNG:unit_pattern_manager.png")

    # C-5 脱册一支 Menu
    def c5(d):
        d.MENUS = [m for m in d.MENUS if m["file"] != "menu/DecompositionGeneratorMenu.java"]
    check_case("C-5 从 MENUS 删掉一支 Menu", run(mutate_data=c5), "Menu未登记:")

    # C-6 代码自绘去掉 −1
    # （第 128 轮：IntermediateCacheScreen 已改成工具生成底图、不再有 slotFrame，
    #   反例对象换成仍自绘的 ChamberUnitsSummaryScreen —— 判据本身没放宽）
    def c6(d):
        for e in d.CODE_FRAMES:
            if e["file"] == "client/screen/ChamberUnitsSummaryScreen.java":
                e["pattern"] = e["pattern"].replace("slot\\.x - 1", "slot\\.x")
    check_case("C-6 代码自绘 `slot.x - 1` 去掉 −1", run(mutate_data=c6),
               "CODE:client/screen/ChamberUnitsSummaryScreen.java")

    # C-7 模板错位
    check_case("C-7 模板改成 slot.png 的 (1,1) 起 17×17",
               run(mutate_tpl=lambda t: t[1:18, 1:18]), "PNG:")


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 126 轮自检：GUI 槽位 ↔ 背景槽框对位（约定：槽框左上角 = Menu 坐标 − 1）")
    print("登记表：tools/gui_slot_registry.py   说明：%s" % DOC)
    if not os.path.isfile(p(REG.TEMPLATE["png"].replace("/", os.sep))):
        print("FAIL 模板缺失：%s" % REG.TEMPLATE["png"])
        return 1
    tpl = load_template(REG.TEMPLATE)
    print("模板：%s 取 (%d,%d) 起 %d×%d（%d 种颜色）"
          % (REG.TEMPLATE["png"], REG.TEMPLATE["x"], REG.TEMPLATE["y"],
             REG.TEMPLATE["w"], REG.TEMPLATE["h"],
             len(np.unique(tpl.reshape(-1, 4), axis=0))))

    sig = template_signature(tpl)
    if sig is None:
        print("FAIL 模板结构不对：%s 的 (%d,%d) 起 %d×%d 不是「上/左 1px 同色 + 内部同色」的槽框"
              % (REG.TEMPLATE["png"], REG.TEMPLATE["x"], REG.TEMPLATE["y"],
                 REG.TEMPLATE["w"], REG.TEMPLATE["h"]))
        return 1
    print("模板结构：边框 RGBA%s / 填充 RGBA%s" % (tuple(int(v) for v in sig[0]),
                                                  tuple(int(v) for v in sig[1])))

    data = types.SimpleNamespace(
        OFFSET=REG.OFFSET, PAINTED=REG.PAINTED, MENUS=REG.MENUS,
        CODE_FRAMES=REG.CODE_FRAMES, DECORATIVE=REG.DECORATIVE, ORPHAN=REG.ORPHAN,
    )
    frames = detect_all(sig)
    rows = results(data, frames)
    n_png = len([r for r in rows if r[0].startswith("PNG:")])
    n_menu = len([r for r in rows if r[0].startswith("Menu")])
    n_code = len([r for r in rows if r[0].startswith("CODE:")])
    n_anchor = len([r for r in rows if r[0].startswith("锚点:")])
    print("\n覆盖：%d 张烘焙槽框的背景 / %d 支 Menu / %d 处代码自绘 / %d 条锚点"
          % (n_png, len(data.MENUS), n_code, n_anchor))

    bad = []
    bad += print_section("A 背景贴图槽框对位（槽框左上角 = Menu 坐标 − 1）",
                         [r for r in rows if r[0].startswith("PNG:")], always_show=True)
    bad += print_section("B 登记完整性（PNG 槽框 / Menu 覆盖 / 声明无槽框 / 零引用）",
                         [r for r in rows if r[0].startswith(("未登记:", "DECOR:", "ORPHAN:", "Menu"))],
                         summary="%d 张声明无槽框的图真的没有槽框；%d 支 Menu 全部登记；零引用贴图如声明"
                                 % (len(data.DECORATIVE), len(data.MENUS)))
    bad += print_section("C 代码自绘槽框（调用点必须登记；与槽位同源的必须 −1）",
                         [r for r in rows if r[0].startswith("CODE:")],
                         summary="%d 个自绘点全登记（共 %d 处 slotFrame + %d 处 slotSprite），"
                                 "%d 处与 Menu 槽位同源且都写了 `- 1`"
                                 % (len(data.CODE_FRAMES),
                                    sum(e["count"] for e in data.CODE_FRAMES
                                        if e.get("api") == "slotFrame"),
                                    sum(e["count"] for e in data.CODE_FRAMES
                                        if e.get("api") == "slotSprite"),
                                    len([e for e in data.CODE_FRAMES if e["menu_slot"]])))
    bad += print_section("D 锚点锁（登记表坐标 vs 源码）",
                         [r for r in rows if r[0].startswith("锚点:")],
                         summary="登记表里的坐标/常量在源码里逐字仍在（改坐标必须同步改登记表）")

    group_counterexamples(data, tpl, sig, frames)

    total = len(rows) + 7
    print("\n断言 %d 条（其中反例 7 条），失败 %d 条" % (total, len(bad)))
    if bad:
        print("FAILED:")
        for name, _, detail in bad:
            print("  - %s：%s" % (name, compact(detail)))
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
