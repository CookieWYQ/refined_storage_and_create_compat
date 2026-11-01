#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 73 轮：终端「模式切换」后控件/框错位的**布局一致性**自检。

<h2>本轮用户实机反馈（逐字）</h2>
<p>「你那个选择框那个东西其实他点击那些按钮然后选择的时候那个选择框有的时候也会错位。比如说我点了某个界面，
他切换过去但是按钮还是显示在那里；有的时候我的另外一个界面按钮却在另外一个地方。就比如说有的时候我点击合成终端，
但是呢它框还停留在样板终端那里，类似于这样子。」</p>

<h2>根因（源码级事实，本脚本逐条钉住）</h2>
<ol>
  *   <li><b>本地模式写到了「已脱离背包的 ItemStack」上</b>：界面初始化时捕获的是那一刻的 {@code ItemStack} 引用，
      而服务端在开屏包之后紧接着发 {@code ClientboundContainerSetContentPacket}
      （{@code AbstractContainerMenu#initializeContents} → {@code Slot#set} → {@code Inventory#setItem}）
      会把背包里每一个 {@code ItemStack} <b>换成新实例</b>。旧引用随即失效 ⇒ 往它身上写模式等于没写
      ⇒ 新界面 {@code Init.Post} 读到旧模式 ⇒ 高亮画在<b>旧 Tab</b> 上（用户说的「框还停留在样板终端」）。</li>
  *   <li><b>高亮是构造期快照</b>：{@code selected = (currentMode == mode)} 在建控件时算好并塞进字段，
      服务端稍后把新模式同步下来时没有任何人重算 ⇒ 框一直停在旧位置，直到某次 {@code rebuildWidgets()}
      才偶然恢复（用户说的「有的时候」）。</li>
  *   <li><b>一排 Tab 的几何只在 Init.Post 里按「那一个界面」的面板算、且没有夹进屏幕</b>：各模式面板尺寸不同
      （176 / 193 / 254 / 256 宽；序列样板终端固定 324 高），所以一排按钮在不同模式里本来就落在不同位置；
      面板比屏幕高时最下面两颗 Tab 直接被画到<b>屏幕外</b>（看得见、点不到）。</li>
  *   <li><b>自己维护的控件列表没清</b>：{@code SequencePatternTerminalScreen#scrollbars} 是 {@code final} 列表，
      {@code init()} 每次 {@code rebuildWidgets()}（窗口缩放 / 从子界面返回）都往里再塞 4 个；
      而这条列表<b>同时驱动绘制与命中</b> ⇒ 旧实例按旧坐标/旧滑块偏移再画一份，且命中按列表顺序先撞旧实例
      ⇒ 「框还停留在原来那里」+「点到的和看到的不是同一个」。</li>
</ol>

<h2>本脚本断言的四类判据（都是「源码形状 + 行为镜像」，不是恒真断言）</h2>
<ol>
  *   ① 多模式界面在切换时<b>必须重建/重排控件</b>：切换 = 服务端重开界面（{@code handle → openModeScreen}）；
      每次 {@code Init.Post} 都重建这一排，且几何按<b>本次</b>界面现算（含夹进屏幕）；</li>
  *   ② <b>旧控件被清除</b>：{@code removeListener} 摘旧排 + 自维护列表 {@code clear()} 后再 add；</li>
  *   ③ <b>点击命中几何与绘制几何同源</b>：Tab 走控件自身的 {@code getX/getY}（无第二套坐标）；
      各界面自绘元素的命中判定与绘制共用同一批 helper（{@code rowRect} / {@code rowControlY} /
      {@code getScrollbarOffset} / {@code cellRect}）；</li>
  *   ④ <b>反例自证</b>：把修前写法喂给同一批判据函数，必须判 FAIL（证明判据抓得住旧写法）。</li>
</ol>

用法：python tools/selfcheck_round73_screen_mode_layout.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

OVERLAY = "client/TerminalModeTabOverlay.java"
SPT = "client/screen/SequencePatternTerminalScreen.java"
UNIT_MANAGER = "client/screen/UnitPatternManagerScreen.java"
SUMMARY = "client/screen/ChamberUnitsSummaryScreen.java"
CHILD = "client/screen/ChildConfigScreen.java"
FACE_CFG = "client/screen/ChamberFaceConfigScreen.java"
BUS_CFG = "client/screen/BusCategoryConfigScreen.java"
SWITCH_PACKET = "network/SwitchTerminalModePacket.java"
TERMINAL_ITEM = "item/AdvancedRemoteTerminalItem.java"

# 「承载多模式 / 可反复重进」的界面：这些文件里的控件只允许在 init() 里建（绝不能每帧累积）
MULTI_MODE_SCREENS = [
    OVERLAY,
    SPT,
    UNIT_MANAGER,
    SUMMARY,
    "client/screen/BusCategoryConfigScreen.java",
    "client/screen/ChamberUnitsSummaryScreen.java",
    "client/screen/CollectionCacheScreen.java",
    "client/screen/AdvancedSchematicLoaderScreen.java",
    "client/screen/AdvancedQuantityKeeperScreen.java",
    "client/screen/SequenceExecutionChamberScreen.java",
    "client/screen/SequenceAssemblyExecutorScreen.java",
    "client/screen/UnitPatternConfigScreen.java",
    "client/screen/StepMachineSelectScreen.java",
    "client/screen/AssemblyPatternRebindScreen.java",
    "client/screen/ChamberBindingConfigScreen.java",
    "client/screen/ChamberFaceConfigScreen.java",
    "client/screen/CollectionMarkerConfigScreen.java",
    "client/screen/CollectionRangeConfigScreen.java",
    "client/screen/CollectionInputFaceConfigScreen.java",
    "client/screen/CollectionDeleteModeConfirmScreen.java",
    "client/screen/SequenceResultConfigScreen.java",
]

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def section(title):
    print("")
    print("=" * 78)
    print(title)
    print("=" * 78)


def read(rel):
    path = os.path.join(SRC, rel)
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """剥掉 // 与 /*...*/ 注释（字符串字面量保留：判据本身就在代码里）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            if j < 0:
                break
            out.append(" " * (j - i))
            i = j
            continue
        if two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def method_name_at(text, index):
    """向上找最近的「方法声明」行，返回方法名（找不到返回 None）。

    只服务于「这次 add* 是在哪个方法里」这一个用途：本工程格式化一致，
    {@code private/protected/public [static] [final] Ret name(args) {} 这样的行就是方法头。
    """
    head = text[:index]
    lines = head.split("\n")
    decl = re.compile(r"^\s{0,8}(?:(?:public|protected|private|static|final|synchronized|abstract|default)\s+)+"
                      r"(?:<[^>]*>\s*)?[\w$.<>\[\], ?]+\s+(\w+)\s*\(")
    for line in reversed(lines):
        m = decl.match(line)
        if m:
            return m.group(1)
    return None


def _helper_defined_inside_init(code, name):
    """name 的每一次出现（声明 + 调用）是否都落在 init() 里。

    <p>工程里有 {@code init() → numberBox(...) → addRenderableWidget(...)} 这种一层包装：
    控件仍然「每次 init 建一套」，不是每帧累积，因此必须放行；
    判据是<b>该名字的每一处出现（含声明行）所在的方法都得是 init</b> ——
    {@code render(GuiGraphics,...)} 这种声明落在别的方法后面，自然判负。</p>
    """
    hits = list(re.finditer(r"\b%s\s*\(" % re.escape(name), code))
    if not hits:
        return False
    return all(method_name_at(code, m.start()) == "init" for m in hits)


def add_calls_outside_init(text):
    """列出所有「不在 init()/init(int) 里」的控件注册调用（每帧累积的典型写法）。"""
    code = strip_comments(text)
    bad = []
    for m in re.finditer(r"\b(?:addRenderableWidget|addWidget)\s*\(", code):
        name = method_name_at(code, m.start())
        if name in ("init",):
            continue
        if name is not None and _helper_defined_inside_init(code, name):
            continue  # init() → 私有小工厂 → add(...)：仍是「一次 init 建一套」
        bad.append("%s @%d" % (name, m.start()))
    return bad


# ======================================================================================
# 判据函数（同一批函数既跑真实源码，也跑反例片段）
# ======================================================================================

def pred_tab_writes_live_stack(src):
    """① Tab 点击必须把模式写到「存活栈」上（现取），不得写初始化时捕获的那一份。"""
    code = strip_comments(src)
    writes_live = "setMode(state.liveStack()" in code
    legacy_write = bool(re.search(r"setMode\(\s*terminalStack\b", code))
    # 旧实现里那个「初始化时捕获、之后一直被当成物品本体」的局部量必须消失
    legacy_capture = bool(re.search(r"final\s+ItemStack\s+terminalStack\s*=", code))
    return writes_live and not legacy_write and not legacy_capture


def pred_highlight_is_live(src):
    """② 高亮必须是「物品当前模式的函数」，不得是构造期快照。"""
    code = strip_comments(src)
    live_read = bool(re.search(r"state\.mode\(\)\s*==\s*mode", code))
    no_baked_field = not re.search(r"private\s+final\s+boolean\s+selected\s*;", code)
    no_ctor_snapshot = not re.search(r"boolean\s+selected\s*,", code)
    per_frame = "ScreenEvent.Render.Pre" in code and bool(
        re.search(r"public\s+static\s+void\s+onScreenRenderPre\s*\([^)]*Render\.Pre", code)) and "state.refresh()" in code
    return live_read and no_baked_field and no_ctor_snapshot and per_frame


def pred_geometry_live_and_clamped(src):
    """③ 几何必须按「本次界面」现算，并整体夹进屏幕矩形（否则面板比屏幕高时会画到屏幕外）。"""
    code = strip_comments(src)
    from_panel = "getGuiLeft() + guiW + SCREEN_MARGIN" in code and "getGuiTop() + guiH - columnH" in code
    clamped = ("clamp(containerScreen.getGuiLeft()" in code
               and "clamp(containerScreen.getGuiTop()" in code)
    screen_size = "getGuiScaledWidth()" in code and "getGuiScaledHeight()" in code
    return from_panel and clamped and screen_size


def pred_old_row_removed(src):
    """④ 旧控件不得残留：同一界面再次初始化时先摘掉上一排。"""
    code = strip_comments(src)
    return ("removeRow(event)" in code
            and "event.removeListener(" in code
            and "ROW_WIDGETS.clear()" in code)


def pred_same_screen_not_expired(src):
    """⑤ 「同一界面的重复初始化」不得被 3 秒意图时限判死（否则缩放一下整排消失）。"""
    code = strip_comments(src)
    identity = "rowOwner.get() == screen" in code
    guarded = bool(re.search(r"if\s*\(\s*!sameScreen\s*&&\s*!TerminalOpenIntent\.consume\(\s*screen\s*\)", code))
    return identity and guarded


def pred_hit_and_draw_same_source(src):
    """⑥ 命中几何 = 绘制几何：Tab 一律用控件自身的 getX/getY，文件里不得出现第二套鼠标包围盒。"""
    code = strip_comments(src)
    uses_widget_rect = "final int x = getX();" in code and "final int y = getY();" in code
    # 任何「自己拿 mouseX 比手算坐标」的写法都是第二套几何（实测坐标漂移的唯一来源）
    manual_bounds = bool(re.search(r"mouseX\s*>=\s*(?:px|leftPos|[a-zA-Z]+X)\s*\+", code))
    return uses_widget_rect and not manual_bounds


def pred_list_cleared_before_add(src, list_name):
    """⑦ 自维护控件列表必须「先清后建」：init 里 clear() 必须出现在第一次 add() 之前。"""
    code = strip_comments(src)
    init_at = code.find("protected void init()")
    if init_at < 0:
        return False
    add_at = code.find("%s.add(" % list_name, init_at)
    if add_at < 0:
        return False
    clear_at = code.find("%s.clear();" % list_name, init_at)
    return 0 <= clear_at < add_at


def pred_widgets_only_in_init(src):
    """⑧ 控件只允许在 init() 里建：不允许在 render / mouseXxx 里 add（每帧累积 = 残影 + 错位）。"""
    return not add_calls_outside_init(src)


# ---- 通用判据：init() 里往「类级集合字段」add 之前必须先 clear()（覆盖全工程屏幕） --------------

# 类级集合字段（4 空格缩进 = 字段；8 空格 = init 内部的局部变量，不算）
COLLECTION_FIELD = re.compile(
    r"^\s{4}(?:(?:public|protected|private|static|final|transient|volatile)\s+)*"
    r"(?:java\.util\.)?(?:List|Set|Map|Deque|Queue|Collection|ArrayList|HashSet|HashMap|LinkedList)"
    r"\s*<[^;{}]*?>\s+(\w+)\s*(?:=|;)",
    re.M,
)


def method_body_after(code, decl_at):
    """从 decl_at 起第一个 '{' 开始做配对扫描，返回方法体（跳过字符串/字符字面量里的括号）。"""
    start = code.find("{", decl_at)
    if start < 0:
        return None
    depth = 0
    quote = None
    i = start
    while i < len(code):
        ch = code[i]
        if quote is not None:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in ("\"", "'"):
            quote = ch
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return code[start + 1:i]
        i += 1
    return None


def init_method_body(src):
    """取 init() 的方法体（含 protected/public 修饰；找不到返回 None）。"""
    code = strip_comments(src)
    for sig in ("void init()", "void init(int"):
        at = code.find(sig)
        if at >= 0:
            return method_body_after(code, at)
    return None


def member_lists_added_in_init(src):
    """init() 里被 add/addAll 的类级集合字段名（按首次出现排序）。"""
    code = strip_comments(src)
    body = init_method_body(src)
    if body is None:
        return []
    found = []
    for m in COLLECTION_FIELD.finditer(code):
        name = m.group(1)
        if re.search(r"\b%s\s*\.\s*(?:add|addAll)\s*\(" % re.escape(name), body):
            found.append(name)
    return sorted(set(found))


def pred_member_lists_clear_before_add(src):
    """init() 里凡是往类级集合字段 add 的地方，同一字段的 clear() 必须先于第一次 add。

    这是 ③-a（只盯 SPT 的 scrollbars）的<b>通用化</b>版本：判据不看具体文件名或字段名，
    因此任何界面只要复刻「final 列表 + init 只 add 不 clear」都会被抓到。
    返回 (ok, offenders)；offenders 里每项 = (字段名, 首行号, 是否出现过 clear)。
    """
    code = strip_comments(src)
    body = init_method_body(src)
    if body is None:
        return True, []
    offenders = []
    body_line0 = code.find(body) if body else 0
    for name in member_lists_added_in_init(src):
        first = re.search(r"\b%s\s*\.\s*(?:add|addAll)\s*\(" % re.escape(name), body)
        clear = re.search(r"\b%s\s*\.\s*clear\s*\(\s*\)\s*;" % re.escape(name), body)
        if first is not None and not (clear is not None and clear.start() < first.start()):
            line = code[: body_line0 + first.start()].count("\n") + 1
            offenders.append((name, line, clear is not None))
    return not offenders, offenders


def pred_no_duplicate_widget_list(src, widget_type):
    """⑨ 同一种控件的自维护列表只能有一条（两条 = 两套绘制/命中几何，必错位）。"""
    code = strip_comments(src)
    decls = re.findall(r"List<\s*%s\s*>\s+(\w+)" % re.escape(widget_type), code)
    return len(decls) == 1


# ======================================================================================
# ① 模式切换链路：切换 = 服务端重开界面（新 Screen ⇒ 控件集合天然重建）
# ======================================================================================

section("① 模式切换链路（切换 = 服务端重开界面；每次 Init.Post 都重建这一排）")

packet = read(SWITCH_PACKET)
item = read(TERMINAL_ITEM)
overlay = read(OVERLAY)

# 第 100 轮：openModeScreen 多了一个「返回时回到哪一档」的实参（能耗页的返回键），
# 因此这里不能匹配整段调用文本；判据改成「setMode(...) 之后、在同一处理体里调用了
# openModeScreen(player, stack, packet.slotReference(), …)」——它抓的仍然是同一个 bug：
# 切了模式却没重开界面（新 Screen 没建 ⇒ 控件集合不重建 ⇒ 点击无效）。
_packet_code = strip_comments(packet)
_has_set_mode = "AdvancedRemoteTerminalItem.setMode(stack, packet.mode())" in _packet_code
_has_reopen = "openModeScreen(player, stack, packet.slotReference()," in _packet_code
check("①-a 切换包把模式写回物品后重开界面（openModeScreen 在同一处理体里）",
      _has_set_mode and _has_reopen
      and _packet_code.find("setMode(stack, packet.mode())") < _packet_code.find("openModeScreen(player, stack"),
      "setMode=%s reopen=%s" % (_has_set_mode, _has_reopen))
modes = re.findall(r"case MODE_\w+ ->", strip_comments(item))
check("①-b 6 个模式各有自己的打开路径（每个模式都是一个新界面 ⇒ 新 init()）",
      len(modes) >= 5, "case 数=%d" % len(modes))
check("①-c 一排 Tab 在 Init.Post 里重建（不是只在第一次打开时建）",
      "public static void onScreenInit(final ScreenEvent.Init.Post event)" in strip_comments(overlay)
      and "event.addListener(button)" in strip_comments(overlay))
check("①-d 几何在 Init.Post 里按本次界面的面板现算（不是常量、不是缓存）",
      pred_geometry_live_and_clamped(overlay))

# ======================================================================================
# ② 根因判据：存活栈写入 + 每帧高亮 + 旧控件清除 + 意图不看时间
# ======================================================================================

section("② 根因判据（写存活栈 / 高亮每帧 / 旧控件清除 / 同一界面不按时限判死）")

check("②-a Tab 点击写的是「存活栈」（现取 SlotReference），不再写初始化时捕获的那一份",
      pred_tab_writes_live_stack(overlay))
check("②-b 高亮 = 物品当前模式的函数（每帧 Render.Pre 重读），不是构造期快照",
      pred_highlight_is_live(overlay))
check("②-c 几何夹进屏幕矩形（面板比屏幕高时不会把下面两颗 Tab 画到屏幕外）",
      pred_geometry_live_and_clamped(overlay))
check("②-d 旧控件不得残留：同一界面重建前先 removeListener 摘掉上一排",
      pred_old_row_removed(overlay))
check("②-e 同一界面的重复初始化不受 3 秒意图超时影响（缩放 / 子界面返回不丢这一排）",
      pred_same_screen_not_expired(overlay))
check("②-f 命中几何与绘制几何同源（Tab 用控件自身 getX/getY，无第二套包围盒）",
      pred_hit_and_draw_same_source(overlay))

# ======================================================================================
# ③ 自维护列表：先清后建（SPT 的 4 条滚动条）
# ======================================================================================

section("③ 自维护控件列表：先清后建（SequencePatternTerminalScreen#scrollbars）")

spt = read(SPT)
check("③-a scrollbars 在 init() 里先 clear() 再 add（旧实例不得跨重建存活）",
      pred_list_cleared_before_add(spt, "scrollbars"))
check("③-b 滚动条列表只有一条（不存在平行的第二条列表 = 两套几何）",
      pred_no_duplicate_widget_list(spt, "SptScrollbarWidget"))
loop_count = len(re.findall(r"for\s*\(\s*final\s+SptScrollbarWidget\s+\w+\s*:\s*scrollbars\s*\)",
                            strip_comments(spt)))
check("③-c 绘制与命中确实都遍历同一条列表（≥4 处：render / click / move / drag / release）",
      loop_count >= 4, "遍历点=%d" % loop_count)
check("③-d 滚动条控件自身的命中判定与绘制同源（inBounds 用 getX/getY/getWidth/getHeight）",
      "getX() + getWidth()" in read("client/widget/SptScrollbarWidget.java")
      and "getY() + getHeight()" in read("client/widget/SptScrollbarWidget.java"))

# ======================================================================================
# ④ 全量核查：多模式 / 可重进界面里控件只在 init() 建（不每帧累积）
# ======================================================================================

section("④ 全量核查：多模式 / 可重进界面的控件只在 init() 里建")

offenders = []
for rel in MULTI_MODE_SCREENS:
    text = read(rel)
    if not text:
        offenders.append("%s(缺失)" % rel)
        continue
    bad = add_calls_outside_init(text)
    if bad:
        offenders.append("%s:%s" % (rel, ",".join(bad[:3])))
check("④-a 全部多模式界面都不在 render/mouse 里注册控件", not offenders, "; ".join(offenders[:6]))

# 子界面返回父界面 = 原版重新 init()（所以父界面必须能在 init() 里完整重建自己）
check("④-b 子界面返回走 setScreen(parent)（父界面因此重新 init，控件由原版 clearWidgets 清理）",
      "Minecraft.getInstance().setScreen(parent)" in strip_comments(read(CHILD)))
check("④-c 子界面自己不在 render 里加控件（否则每次进出都叠一层）",
      pred_widgets_only_in_init(read("client/screen/StepMachineSelectScreen.java"))
      and pred_widgets_only_in_init(read("client/screen/AssemblyPatternRebindScreen.java")))

# ======================================================================================
# ⑤ 自绘元素的「命中 = 绘制」同源（逐界面取证）
# ======================================================================================

section("⑤ 自绘元素：命中判定与绘制共用同一批 helper（逐界面）")

code_spt = strip_comments(spt)
check("⑤-a 序列样板终端：行内机器控件命中与绘制共用 rowControlY/rowControlX（同一批 helper）",
      code_spt.count("rowControlY(") >= 2 and code_spt.count("rowControlX(") >= 2
      and "machineStripHitAt" in code_spt)
summary = strip_comments(read(SUMMARY))
check("⑤-b 执行舱总览：行矩形 rowRect(row) 同时用于绘制 / hover / 点击",
      summary.count("rowRect(") >= 3 and "private static int[] rowRect(" in summary)
unitmgr = strip_comments(read(UNIT_MANAGER))
check("⑤-c 单元样板管理：分组标题带的命中与绘制共用 BAND_X 与滚动偏移 getScrollbarOffset()",
      "sectionBandAt" in unitmgr and unitmgr.count("getScrollbarOffset()") >= 2
      and unitmgr.count("BAND_X") >= 3)
face = strip_comments(read(FACE_CFG))
check("⑤-d 面配置子界面：格子命中 cellAt() 与绘制 cellRect() 同一套矩形",
      "cellAt(" in face and "cellRect(" in face and face.count("cellRect(") >= 2)
bus = strip_comments(read(BUS_CFG))
check("⑤-e 总线类别配置：切页只改 activeTab（控件集合与标签页无关，不会残留上一页的控件）",
      "private void switchTab(" in bus and "activeTab = tabKey;" in bus
      and "addRenderableWidget" not in bus.split("private void switchTab(")[1].split("}\n")[0])

# ======================================================================================
# ⑥ 反例自证：修前写法喂给同一批判据 ⇒ 必须判负
# ======================================================================================

section("⑥ 反例自证（修前写法必须被同一批判据判负）")

# 6.1 旧 Tab 回调：把初始化时捕获的 ItemStack 当成物品本体去写模式
legacy_click = """
    private static void legacyClick(final Player player, final ItemStack terminalStack, final int mode) {
        AdvancedRemoteTerminalItem.setMode(terminalStack, mode);
    }
"""
check("⑥-a 反例：旧写法「setMode(terminalStack, ...)」被判据 ②-a 判负",
      pred_tab_writes_live_stack(legacy_click) is False)

# 6.2 旧高亮：selected 在构造期算好并塞进字段
legacy_baked = """
    private final boolean selected;
    TerminalModeTabButton(int mode, boolean selected) { this.selected = selected; }
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        if (selected) { g.fill(getX(), getY(), getX() + 24, getY() + 22, 0xFFFFFFFF); }
    }
"""
check("⑥-b 反例：旧写法「构造期快照 selected」被判据 ②-b 判负",
      pred_highlight_is_live(legacy_baked) is False)

# 6.3 旧几何：贴着面板右下、不夹屏
legacy_geometry = """
    final int x = containerScreen.getGuiLeft() + guiW + 2;
    final int y = containerScreen.getGuiTop() + guiH - (TAB_H * count) + (TAB_H * i);
"""
check("⑥-c 反例：旧写法「不夹屏的固定右下角」被判据 ②-c 判负",
      pred_geometry_live_and_clamped(legacy_geometry) is False)

# 6.4 旧控件残留：没有 removeListener
legacy_no_remove = """
    for (int i = 0; i < count; i++) { event.addListener(new TerminalModeTabButton(x, y, i)); }
"""
check("⑥-d 反例：旧写法「只加不摘」被判据 ②-d 判负",
      pred_old_row_removed(legacy_no_remove) is False)

# 6.5 旧意图判定：先看时间，同一界面超过 3 秒就丢
legacy_timeout = """
    if (!TerminalOpenIntent.consume(screen)) { return; }
"""
check("⑥-e 反例：旧写法「只看 3 秒时限」被判据 ②-e 判负",
      pred_same_screen_not_expired(legacy_timeout) is False)

# 6.6 旧 SPT init：不 clear 就 add
legacy_spt_init = """
    protected void init() {
        super.init();
        arrangementScrollbar = new SptScrollbarWidget(leftPos + 153, topPos + 32, Type.SMALL, 152);
        scrollbars.add(arrangementScrollbar);
        resultScrollbar = new SptScrollbarWidget(leftPos + 246, topPos + 64, Type.SMALL, 52);
        scrollbars.add(resultScrollbar);
    }
"""
check("⑥-f 反例：旧写法「init 里只 add 不 clear」被判据 ③-a 判负",
      pred_list_cleared_before_add(legacy_spt_init, "scrollbars") is False)

# 6.7 在 render 里加控件（每帧累积）
legacy_render_add = """
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        addRenderableWidget(new Button.Builder(label, b -> {}).bounds(0, 0, 10, 10).build());
    }
"""
check("⑥-g 反例：旧写法「render 里 addRenderableWidget」被判据 ④-a 判负",
      pred_widgets_only_in_init(legacy_render_add) is False)

# 6.8 两套几何：绘制用控件坐标、命中用手算坐标
legacy_two_geometries = """
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        final int x = getX();
        final int y = getY();
        g.fill(x, y, x + 24, y + 22, 0xFF202020);
        if (mouseX >= px + 4 && mouseY >= py + 6) { tooltip = true; }
    }
"""
check("⑥-h 反例：旧写法「绘制一套、命中原点另算一套」被判据 ②-f 判负",
      pred_hit_and_draw_same_source(legacy_two_geometries) is False)

# 6.9 平行列表（第二条滚动条列表 = 第二条几何来源）
legacy_twin_list = """
    private final List<SptScrollbarWidget> scrollbars = new ArrayList<>();
    private final List<SptScrollbarWidget> scrollbarsMirror = new ArrayList<>();
"""
check("⑥-i 反例：旧写法「再维护一条平行控件列表」被判据 ③-b 判负",
      pred_no_duplicate_widget_list(legacy_twin_list, "SptScrollbarWidget") is False)

# 6.10 行为镜像：确认这两个字段语义确实是「先清后建」而不是「先建后清」
mirror_add_then_clear = "scrollbars.add(a);\nscrollbars.clear();"
mirror_clear_then_add = "scrollbars.clear();\nscrollbars.add(a);"
check("⑥-j 行为镜像：判据 ③-a 的时序是「clear 必须在 add 之前」",
      pred_list_cleared_before_add("protected void init() {\n" + mirror_clear_then_add + "\n}", "scrollbars") is True
      and pred_list_cleared_before_add("protected void init() {\n" + mirror_add_then_clear + "\n}", "scrollbars") is False)

# ======================================================================================
# ⑦ 全工程：init() 里往「成员列表」add 之前必须先 clear（CollectionCacheScreen 同源 bug）
# ======================================================================================

section("⑦ 全工程 client/screen/*.java：成员列表必须先清后建（绘制 / 命中同源列表不得跨重建累积）")

CCS = "client/screen/CollectionCacheScreen.java"
ccs = read(CCS)

# 7.1 同源 bug 的第二个受害者：CollectionCacheScreen 与 SPT 结构完全一致（final 列表 + 绘制/命中共用）
check("⑦-a CollectionCacheScreen#scrollbars 在 init() 里先 clear() 再 add（与 SPT 同一修法）",
      pred_list_cleared_before_add(ccs, "scrollbars"))
# 通用判据必须真的在这两个文件上「看见」这条列表，否则「没有 offender」可能只是判据空转
identified = {f: member_lists_added_in_init(read(f)) for f in (SPT, CCS)}
check("⑦-b 通用判据在 SPT / CollectionCacheScreen 上都识别到 scrollbars（判据非空转）",
      all("scrollbars" in names for names in identified.values()),
      str(identified))

# 7.3 全量枚举所有 client/screen/*.java，而不是白名单：新增界面只要复刻这个写法就会被抓
screen_dir = os.path.join(SRC, "client", "screen")
scan_offenders = []
scanned = 0
for name in sorted(os.listdir(screen_dir)):
    if not name.endswith(".java"):
        continue
    rel = "client/screen/" + name
    text = read(rel)
    if not text:
        continue
    scanned += 1
    ok, offenders = pred_member_lists_clear_before_add(text)
    if not ok:
        scan_offenders.append("%s:%s" % (rel, ",".join("%s@%d" % (f, ln) for f, ln, _c in offenders)))
check("⑦-c 全工程 %d 个屏幕：所有「init 里 add 成员列表」的字段都有前置 clear()" % scanned,
      scanned >= 20 and not scan_offenders, "; ".join(scan_offenders[:6]))

# 7.4 反例自证：把 CollectionCacheScreen 的修前写法（只 add 不 clear）喂给同一批通用判据 ⇒ 必须判负
legacy_ccs_init = """
public class CollectionCacheScreen extends AbstractContainerScreen<CollectionCacheMenu> {
    private final List<SptScrollbarWidget> scrollbars = new ArrayList<>();

    @Override
    protected void init() {
        super.init();
        matchScrollbar = new SptScrollbarWidget(leftPos + 142, topPos + 28, SptScrollbarWidget.Type.NORMAL, 120, 10);
        scrollbars.add(matchScrollbar);
        cacheScrollbar = new SptScrollbarWidget(leftPos + 142, topPos + 188, SptScrollbarWidget.Type.NORMAL, 120, 10);
        scrollbars.add(cacheScrollbar);
    }
}
"""
legacy_ok, legacy_bad = pred_member_lists_clear_before_add(legacy_ccs_init)
check("⑦-d 反例：修前 CollectionCacheScreen（final 列表只 add 不 clear）被判据 ⑦-c 判负",
      legacy_ok is False and any(f == "scrollbars" for f, _ln, _c in legacy_bad),
      str(legacy_bad))

# 7.5 反例时序：clear 写在 add 之后（= 清掉刚建的、上一批照旧残留）同样必须判负
legacy_clear_last = """
    private final List<SptScrollbarWidget> scrollbars = new ArrayList<>();
    protected void init() {
        super.init();
        scrollbars.add(matchScrollbar);
        scrollbars.clear();
    }
"""
check("⑦-e 反例：clear 写在 add 之后（时序错）被判据 ⑦-c 判负",
      pred_member_lists_clear_before_add(legacy_clear_last)[0] is False)

# ======================================================================================
# ⑧ 范围充电器「查看充电范围」按钮 + Create 蓝图同款框选（本轮需求）
#
# 用户原话：「当前 XYZ 范围包括充电范围。需要添加一个按钮，用于查看充电范围。查看时使用蓝图框选功能
# 将范围框起来。框选颜色需要与蓝图不同。」
#
# 本节的判据都盯「源码形状」，并且用反例自证：用 rangeX 当半径 / 用蓝图颜色 / 没有移除路径 /
# 在客户端拿 getChargerPos 赌坐标 —— 四种修前写法喂给同一批函数必须判负。
# ======================================================================================

section("⑧ 范围充电器：查看充电范围按钮 + 蓝图同款框选（直径口径 / 异色 / 可移除 / 坐标不赌）")

CHARGER_SCREEN = "client/screen/RangeChargerScreen.java"
SINK = "client/ClientPayloadSink.java"
RANGE_PACKET = "network/ShowChargerRangePacket.java"
BLUEPRINT_OUTLINE_COLOR = 0x6886C5  # Create 蓝图框选 SchematicAndQuillHandler/SchematicHandler 的颜色

charger_screen = read(CHARGER_SCREEN)
charger_sink = read(SINK)
range_packet = read(RANGE_PACKET)


def pred_range_button(src):
    """按钮存在、只在 init() 里建（不每帧累积）、点击走原版菜单按钮通道（C2S 请求）。"""
    code = strip_comments(src)
    body = init_method_body(src)
    return ("gui.rs_create_compat.range_charger.show_range" in code
            and "RangeChargerMenu.BUTTON_SHOW_RANGE" in code
            and "handleInventoryButtonClick" in code
            and body is not None
            and "addRenderableWidget(Button.builder(" in body)


def pred_range_button_geometry(src):
    """按钮落在「+ 按钮（128..148）」与「插件槽背景（x=187 起）」之间的空位，且与 ± 按钮同高。"""
    code = strip_comments(src)
    m = re.search(r"bounds\(leftPos \+ (\d+), topPos \+ (\d+), (\d+), (\d+)\)", code)
    if not m:
        return False
    x, y, w, h = (int(v) for v in m.groups())
    plus_ok = re.search(r"new RepeatButton\(leftPos \+ 128, topPos \+ y, 20, 14", code) is not None
    return plus_ok and x >= 150 and x + w <= 187 and y == 20 and h == 14


def pred_range_diameter_geometry(src):
    """三轴都按「直径」口径（half = range / 2），且用蓝图同款 expandTowards(1,1,1) 包住整格。"""
    code = strip_comments(src)
    halves = re.findall(r"half([XYZ])\s*=\s*packet\.range([XYZ])\(\)\s*/\s*2", code)
    halves_ok = len(halves) == 3 and all(a == b for a, b in halves)
    axes_ok = all(("pos.get%s() - half%s" % (ax, ax)) in code
                  and ("pos.get%s() + half%s" % (ax, ax)) in code for ax in "XYZ")
    no_radius_use = not re.search(r"pos\.get[XYZ]\(\)\s*[+-]\s*packet\.range[XYZ]\(\)", code)
    return (halves_ok and axes_ok and no_radius_use
            and "new AABB(" in code and ".expandTowards(1, 1, 1)" in code)


def pred_range_color_not_blueprint(src):
    """颜色常量存在、真的被 colored() 使用、不是蓝图色，且不能带 alpha（colored 只吃 RGB）。"""
    code = strip_comments(src)
    m = re.search(r"RANGE_OUTLINE_COLOR\s*=\s*(0x[0-9A-Fa-f]+)", code)
    if not m or ".colored(RANGE_OUTLINE_COLOR)" not in code:
        return False
    value = int(m.group(1), 16)
    return value != BLUEPRINT_OUTLINE_COLOR and value <= 0xFFFFFF


def pred_range_removal(src):
    """removed()（关界面）不得无条件清框：未到期必须按剩余 TTL 重新武装，到期才真正清。

    <p>判据（方法体切片，不做完整语法分析）：</p>
    <ol>
        <li>{@code Outliner.remove(RANGE_OUTLINE_SLOT)} 只出现在统一的 {@code hideChargerRange()} 里；</li>
        <li>{@code removed()} 体内必须<b>同时</b>出现「重新武装」证据（{@code remaining} + {@code showAABB}）
        与 {@code hideChargerRange();}；</li>
        <li>{@code showAABB} 排在 {@code hideChargerRange();} 之前，且 {@code hideChargerRange();}
        出现在 {@code else}（「剩余已过」分支）之后 —— 即清框只能走「到期」分支。</li>
    </ol>
    <p>必须判负的两种历史坏写法：M1 {@code removed()} 里直接 {@code Outliner.remove}；
    M2 {@code removed()} 里无分支地只调 {@code hideChargerRange()}。两者都等于「玩家一关 GUI
    框立刻消失 ⇒ 看不到」，正是本轮用户抱怨的那个旧行为。</p>
    """
    code = strip_comments(src)
    body = method_body_after(code, code.find("public void removed()"))
    if body is None:
        return False
    # 1) 重新武装那半段：既有 remaining，也要真的再喂一次 showAABB
    if "remaining" not in body or "showAABB" not in body:
        return False
    # 2) 清框调用必须存在
    if "hideChargerRange();" not in body:
        return False
    hide_at = body.index("hideChargerRange();")
    # 3) 顺序：先按剩余 TTL 重新武装（showAABB），再在 else 分支里清
    if not (body.index("showAABB") < hide_at):
        return False
    if "else" not in body or body.index("else") > hide_at:
        return False
    return "Outliner.getInstance().remove(RANGE_OUTLINE_SLOT)" in code


def pred_range_no_client_pos_guess(src):
    """客户端不赌 getChargerPos（客户端菜单恒 null）：坐标来自 S2C 载荷的 packet.pos()。"""
    code = strip_comments(src)
    return ("getChargerPos" not in code
            and "ShowChargerRangePacket" in code
            and "packet.pos()" in code)


def pred_range_sink_guarded(src):
    """S2C 只写回「正开着、且菜单 id 对得上」的界面（防止别的界面 / 已关闭界面被画框）。"""
    code = strip_comments(src)
    return ("instanceof RangeChargerScreen screen" in code
            and "screen.getMenu().containerId == packet.containerId()" in code)


def pred_range_lang_pair(zh, en, key):
    return bool(zh.get(key)) and bool(en.get(key))


check("⑧-a 按钮存在、在 init() 里建、点击发 C2S 菜单按钮请求（BUTTON_SHOW_RANGE）",
      pred_range_button(charger_screen))
check("⑧-b 按钮几何：+ 按钮（128..148）与插件槽（187 起）之间的空位，36x14 与 ± 按钮同高",
      pred_range_button_geometry(charger_screen))
check("⑧-c 画框按「直径」口径（三轴 half = range / 2）+ 蓝图同款 expandTowards(1,1,1)",
      pred_range_diameter_geometry(charger_screen))
check("⑧-d 框选颜色与蓝图不同（0xFF9F1C ≠ 0x6886c5），且不带 alpha",
      pred_range_color_not_blueprint(charger_screen))
check("⑧-e removed() 不得无条件清框：未到期必须按剩余 TTL 重新武装（要求出现 remaining + showAABB），"
      "到期才 hideChargerRange()",
      pred_range_removal(charger_screen))
check("⑧-f 客户端坐标不赌：界面不调用 getChargerPos，坐标来自 S2C 的 packet.pos()",
      pred_range_no_client_pos_guess(charger_screen))
check("⑧-g S2C 只写回容器 id 对得上的 RangeChargerScreen",
      pred_range_sink_guarded(charger_sink))
check("⑧-h S2C 载荷自带坐标（BlockPos）+ 三轴范围（服务端权威快照）",
      "BlockPos pos" in strip_comments(range_packet)
      and "BlockPos.STREAM_CODEC" in strip_comments(range_packet))

_zh = json.load(io.open(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                                     "lang", "zh_cn.json"), "r", encoding="utf-8"))
_en = json.load(io.open(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                                     "lang", "en_us.json"), "r", encoding="utf-8"))
BUTTON_KEY = "gui.rs_create_compat.range_charger.show_range"
TIP_KEY = "gui.rs_create_compat.range_charger.show_range.tip"
check("⑧-i 按钮 / tooltip 的中英语言键都存在且非空",
      pred_range_lang_pair(_zh, _en, BUTTON_KEY) and pred_range_lang_pair(_zh, _en, TIP_KEY))

# ---- 反例自证：各类「修前 / 错误」写法必须被同一批判据判负 ----

RADIUS_AS_DIAMETER = """
    final int halfX = packet.rangeX();
    final AABB box = new AABB(pos.getX() - halfX, pos.getY() - halfY, pos.getZ() - halfZ,
        pos.getX() + halfX, pos.getY() + halfY, pos.getZ() + halfZ).expandTowards(1, 1, 1);
"""
BLUEPRINT_COLORED = """
    private static final int RANGE_OUTLINE_COLOR = 0x6886c5;
    Outliner.getInstance().showAABB(RANGE_OUTLINE_SLOT, box, 100).colored(RANGE_OUTLINE_COLOR);
"""
NO_REMOVAL = """
    private void hideChargerRange() {
        rangeOutlineShown = false;
    }
"""
GUESS_CLIENT_POS = """
    final BlockPos pos = menu.getChargerPos();
    Outliner.getInstance().showAABB(RANGE_OUTLINE_SLOT, new AABB(pos), 100).colored(0xFF9F1C);
"""

check("⑧-j 反例：把 rangeX 当半径（`half = packet.rangeX()`）⇒ 判据 ⑧-c 判负",
      pred_range_diameter_geometry(RADIUS_AS_DIAMETER) is False)
check("⑧-k 反例：用蓝图颜色 0x6886c5 ⇒ 判据 ⑧-d 判负",
      pred_range_color_not_blueprint(BLUEPRINT_COLORED) is False)
check("⑧-l 反例：没有 Outliner.remove（只改本地标志）⇒ 判据 ⑧-e 判负",
      pred_range_removal(NO_REMOVAL) is False)
UNCONDITIONAL_HIDE = """
    private void hideChargerRange() {
        Outliner.getInstance().remove(RANGE_OUTLINE_SLOT);
    }

    public void removed() {
        hideChargerRange();
        super.removed();
    }
"""
check("⑧-l2 反例：removed() 无条件 hideChargerRange()（丢掉剩余 TTL 重新武装）⇒ 判据 ⑧-e 判负",
      pred_range_removal(UNCONDITIONAL_HIDE) is False)
check("⑧-m 反例：客户端拿 getChargerPos 赌坐标 ⇒ 判据 ⑧-f 判负",
      pred_range_no_client_pos_guess(GUESS_CLIENT_POS) is False)
check("⑧-n 反例：按钮放到 + 按钮身上（x=128）⇒ 判据 ⑧-b 判负",
      pred_range_button_geometry(
          "addRenderableWidget(Button.builder(l, b -> {}).bounds(leftPos + 128, topPos + 20, 36, 14).build());\n"
          "new RepeatButton(leftPos + 128, topPos + y, 20, 14, m, b -> {});") is False)
check("⑧-o 反例：语言的值为空 ⇒ 判据 ⑧-i 判负",
      pred_range_lang_pair({BUTTON_KEY: ""}, {BUTTON_KEY: "Show"}, BUTTON_KEY) is False)

# 行为镜像：几何口径与方块实体扫描逐格一致（偶数 + 奇数各验一次）
def _mirror_ui_width(rx):
    """界面框宽度：min = pos - range/2，max 角点 = pos + range/2，再 expandTowards(1) ⇒ 2*(range/2)+1。"""
    lo = 0 - (rx // 2)
    hi = (rx // 2) + 1
    return hi - lo


def _mirror_scan_width(rx):
    """RangeChargerBlockEntity#scanPlayers / #scanItems 的 AABB 宽度（含整格）。"""
    return 2 * (rx // 2) + 1


check("⑧-p 行为镜像：框宽 == 扫描宽（偶数 50 → 51 格；绝不是把直径当半径的 101 格）",
      all(_mirror_ui_width(r) == _mirror_scan_width(r) for r in (1, 2, 3, 37, 50, 51, 100))
      and _mirror_ui_width(50) == 51 and _mirror_ui_width(50) != 101)

# ======================================================================================
# 结果
# ======================================================================================

print("")
print("断言总数 %d" % CHECKS[0])
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
