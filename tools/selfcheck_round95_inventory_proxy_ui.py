#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 95 轮 WS-B 自检：容器代理器（inventory_proxy）的界面 / 收纳袋 / 背包快捷键说明。

只钉第 95 轮 WS-B 的四件事（用户第 8 / 9 / 10 / 11 条），不重复 round89/93 已有的断言：

  ① 界面 tooltip 必须手动绘制（用户第 8 条）
     `InventoryProxyScreen` 必须存在一条从 `render(...)` 出发、真正到达
     `renderTooltip(...)` 的调用路径 —— 不是「字符串出现过」，而是按方法体解析出的可达调用。
     反例：把 `renderTooltip` 调用整行删掉 / 只留在一个从不被调用的方法里 ⇒ 判负。

  ② 常驻的「双计警告」已删除，说明改由悬停六面按钮的 tooltip 承载（第 95 轮用户第 10 条 → 第 97 轮第 3 条）
     第 95 轮把长文压成一行；第 97 轮用户实机说「字太小了，而且（就算）修复的话，也不喜欢那行字了」
     —— 那一行被整行等比缩小到约 0.4 倍。现在钉：`renderWarning` / `WARNING_*` / `COLOR_WARNING` /
     `pose().scale(...)` 全部不存在；源码按 `ProxyFaceRole#id()` 取 `face_tip.*` 并用 `renderTooltip`
     画出来。**注意**：状态文字（status.*）现在走 `font.split(..., 98)` 折行，这是布局口径（见
     `tools/selfcheck_round97_proxy_layout.py`），与「常驻警告不许折行」不是同一件事。
     反例：把 `renderWarning(...)` 调用注入回 `renderLabels` ⇒ 判负。

  ③ 收纳袋（minecraft:bundle）必须有组件式 handler 且守恒（用户第 9 条）
     源码级：`InventoryProxyHandlers.BundleContentsView implements IItemHandler` 存在；
     从 `DataComponents.BUNDLE_CONTENTS` 读；写回走 `copy() → set(组件) → setProxyStack`；
     `insertItem` 用原版 `BundleContents.Mutable#tryInsert` 并把「没插进去的」原样退回；
     `extractItem` 部分取出时把剩余写回（`items.set(slot, ...)`），且只在非 simulate 时写；
     空袋给虚位 `Math.max(MIN_SLOTS, contents.size())`（0 槽会让所有插入方永远插不进第一个物品）。
     行为级：一个与 Java 逻辑一一对应的可复跑模型，跑「插入/取出/部分取出/放回」序列，
     断言「进袋 + 退回 = 输入」「取出 + 剩袋 = 取前」守恒；再注入两种 bug 模型必须判负。
     反例：读恒空 / 取出不写回 / 去掉虚位 / BE 不接线 ⇒ 判负。

  ④ 背包快捷键（用户第 11 条）——本模组侧只能做「说明 + 替代路径」，不能替 SB 打开菜单
     （SB 客户端 `sendBackpackOpenOrCloseMessage` 硬判 `slot.container instanceof Inventory`，
     服务端只按 PlayerInventoryHandler 的 main/offhand/armor 解析 ⇒ 结构上不支持任意容器槽）。
     自检钉住：`getTooltipFromContainerItem` 在代理槽是精妙背包时追加一行提示，
     判据只看注册命名空间 `sophisticatedbackpacks`（不 import 对方类，SB 缺席时一条提示都不出现）。
     反例：删掉提示行 / 改成 Shift 才显示 ⇒ 判负。

  ⑤ Jade 依赖点（用户第 4 条）：代理器不再自己写「Shift 详情」
     `InventoryProxyBlockEntity` 同时注册 `Capabilities.ItemHandler.BLOCK` / `FluidHandler.BLOCK`
     （Jade Universal 的 `ItemStorageProvider`/`FluidStorageProvider` 用
     `Level.getCapability(..., context = null)` 查询，未知面 = 内容物面 ⇒ 自动显示内容物）；
     `client/jade/RsccJadePlugin.java` 里没有为代理器写任何自定义 provider / Shift 判定。
     反例：把 BE 的 ItemHandler.BLOCK 注册删掉 ⇒ 判负。

用法：python tools/selfcheck_round95_inventory_proxy_ui.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SCREEN = os.path.join(JAVA, "client", "screen", "InventoryProxyScreen.java")
HANDLERS = os.path.join(JAVA, "support", "InventoryProxyHandlers.java")
BE = os.path.join(JAVA, "block", "entity", "InventoryProxyBlockEntity.java")
JADE = os.path.join(JAVA, "client", "jade", "RsccJadePlugin.java")

PROBLEMS = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % name)
    else:
        PROBLEMS.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments_and_strings(text):
    """注释 + 字符串字面量换成等长空白（行号不变）。判「代码里有没有调用」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:j]))
            i = j
        elif ch == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def block_at(text, open_index):
    depth = 0
    for j in range(open_index, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:j]
    return ""


def method_bodies(code):
    """{方法名: 方法体}。手写扫描（不用复杂正则），与 Lead 的 round95 自检同一套思路。"""
    result = {}
    n = len(code)
    for index in range(n):
        if code[index] != "{":
            continue
        j = index - 1
        while j >= 0 and code[j].isspace():
            j -= 1
        if j < 0 or code[j] != ")":
            continue
        depth = 0
        k = j
        while k >= 0:
            if code[k] == ")":
                depth += 1
            elif code[k] == "(":
                depth -= 1
                if depth == 0:
                    break
            k -= 1
        if k <= 0:
            continue
        m = k - 1
        while m >= 0 and (code[m].isalnum() or code[m] in "_$"):
            m -= 1
        name = code[m + 1:k]
        if not name or not (name[0].isalpha() or name[0] in "_$"):
            continue
        body = block_at(code, index)
        if name not in result or len(body) > len(result[name]):
            result[name] = body
    return result


TOOLTIP_CALL = re.compile(r"\brenderTooltip\s*\(")
# 槽位物品提示那一版（AbstractContainerScreen#renderTooltip(GuiGraphics, int, int)）
SLOT_TOOLTIP_CALL = re.compile(r"\brenderTooltip\s*\(\s*guiGraphics\s*,\s*mouseX\s*,\s*mouseY\s*\)")


def reachable_tooltip(methods):
    """从 render 出发 0~2 跳能否到达 renderTooltip(...)。返回 (bool, 路径)。"""
    render_body = methods.get("render")
    if render_body is None:
        return False, "没有 render 覆写"
    if TOOLTIP_CALL.search(render_body):
        return True, "render"
    tooltip_methods = [name for name, body in methods.items() if TOOLTIP_CALL.search(body)]
    for name in tooltip_methods:
        if re.search(r"\b" + re.escape(name) + r"\s*\(", render_body):
            return True, "render -> " + name
    for mid, mid_body in methods.items():
        if mid == "render" or not re.search(r"\b" + re.escape(mid) + r"\s*\(", render_body):
            continue
        if TOOLTIP_CALL.search(mid_body):
            return True, "render -> " + mid
        for name in tooltip_methods:
            if re.search(r"\b" + re.escape(name) + r"\s*\(", mid_body):
                return True, "render -> " + mid + " -> " + name
    return False, "render 里没有可达的 renderTooltip"


# ==================================================================== ① tooltip
def tooltip_ok(screen_source):
    methods = method_bodies(strip_comments_and_strings(screen_source))
    ok, _ = reachable_tooltip(methods)
    return ok


# ==================================================================== ② 常驻警告已删除
def face_tip_warning_ok(screen_source):
    """常驻红字（第 95/96 轮那条「一行说明」）已删除，信息改由悬停按钮的 face_tip tooltip 承载。

    只钉「旧实现彻底没了 + 新承载点存在」，不钉措辞/几何（那是 Lead 的 B1/B2 口径，
    由 `tools/selfcheck_round97_proxy_layout.py` 钉）。这里故意**不**禁止 `font.split`：
    状态文字现在按 98px 窄列折行，是布局要求，与「常驻警告不许折行」无关。
    """
    code = strip_comments_and_strings(screen_source)
    return (
        "renderWarning" not in code
        and 'LANG + "warning"' not in screen_source
        and "WARNING_X" not in code
        and "WARNING_Y" not in code
        and "COLOR_WARNING" not in code
        and "pose().scale(" not in code
        # 新承载点：按角色 id 取 face_tip.*，并用 tooltip 系列画出来
        and 'LANG + "face_tip."' in screen_source
        and "renderTooltip" in code
    )


# ==================================================================== ③ 收纳袋 handler
def bundle_handler_ok(handlers_source, be_source):
    code = strip_comments_and_strings(handlers_source)
    be = strip_comments_and_strings(be_source)
    return (
        "public static final class BundleContentsView implements IItemHandler {" in code
        and "DataComponents.BUNDLE_CONTENTS" in code
        # 读：内容物来自组件，不是靠能力
        and "stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)" in code
        and "contents.getItemUnsafe(slot).copy()" in code
        # 写：copy → set 组件 → setProxyStack（绝不原地改旧 stack）
        and "final ItemStack updated = stack.copy();" in code
        and "updated.set(DataComponents.BUNDLE_CONTENTS, contents);" in code
        and "proxy.setProxyStack(updated);" in code
        # 插入：原版 tryInsert + remainder 由真实插入量算
        and "final BundleContents.Mutable mutable = new BundleContents.Mutable(contents);" in code
        and "final int inserted = mutable.tryInsert(stack.copy());" in code
        and "stack.copyWithCount(stack.getCount() - inserted)" in code
        and "write(mutable.toImmutable());" in code
        # 取出：部分取出把剩余写回同一槽位，且整体写回组件
        and "items.set(slot, inSlot.copyWithCount(inSlot.getCount() - take));" in code
        and "write(new BundleContents(items));" in code
        # 容量规则走原版（不自己加禁令）
        and "!stack.canFitInsideContainerItems()" in code
        # 空袋虚位
        and "Math.max(MIN_SLOTS, contents.size())" in code
        # BE 接线：isProxyable 认收纳袋 + 内层解析建组件视图
        # 第 108 轮：resolveInner 里两路能力各查各的之后局部变量改名（innerItem → item），
        # 判据仍钉「认收纳袋 + 内层解析真的建组件视图」，只是跟着现状的写法走。
        and "stack.getItem() instanceof BundleItem" in be
        and "stack.has(DataComponents.BUNDLE_CONTENTS)" in be
        and "hasBundleContents(stack)" in be
        and "item = new InventoryProxyHandlers.BundleContentsView(this)" in be
    )


def bundle_slots_ok(handlers_source):
    """空袋虚位：getSlots() 必须给至少 1，否则所有 IItemHandler 插入方都插不进第一个物品。"""
    code = strip_comments_and_strings(handlers_source)
    index = code.find("class BundleContentsView")
    if index < 0:
        return False
    section = code[index:]
    body = ""
    marker = "public int getSlots()"
    start = section.find(marker)
    if start >= 0:
        brace = section.find("{", start)
        if brace >= 0:
            body = block_at(section, brace)
    return "Math.max(MIN_SLOTS, contents.size())" in body or (
        "Math.max(1, contents.size())" in body
    )


# ---- 与 Java 一一对应的可复跑模型（重量容量 = 64；1 个物品占 64/maxStack） ----
BUNDLE_CAPACITY = 64


def unit_weight(max_stack):
    return max(1, BUNDLE_CAPACITY // max(1, max_stack))


def model_width(state):
    return sum(unit_weight(m) * c for _, c, m in state)


def model_try_insert(state, item, count, max_stack):
    """返回 (inserted, new_state)。与 BundleContents.Mutable#tryInsert 的语义一致：
    「同物品合并、容量按重量算、插入量受剩余容量限制」。"""
    if count <= 0:
        return 0, [list(x) for x in state]
    room_units = BUNDLE_CAPACITY - model_width(state)
    if room_units <= 0:
        return 0, [list(x) for x in state]
    unit = unit_weight(max_stack)
    max_by_weight = room_units // unit
    if max_by_weight <= 0:
        return 0, [list(x) for x in state]
    take = min(count, max_by_weight, max_stack)
    new_state = [list(x) for x in state]
    for entry in new_state:
        if entry[0] == item:
            entry[1] += take
            return take, new_state
    new_state.insert(0, [item, take, max_stack])
    return take, new_state


def model_insert(state, item, count, max_stack, simulate=False):
    """对应 BundleContentsView#insertItem：返回 (remainder, new_state)。"""
    inserted, new_state = model_try_insert(state, item, count, max_stack)
    if inserted <= 0:
        return count, [list(x) for x in state]
    if simulate:
        return count - inserted, [list(x) for x in state]
    return count - inserted, new_state


def model_extract(state, index, amount, simulate=False):
    """对应 BundleContentsView#extractItem：返回 (taken, remainder_in_slot, new_state)。"""
    if amount <= 0 or index < 0 or index >= len(state):
        return 0, 0, [list(x) for x in state]
    item, count, max_stack = state[index]
    take = min(amount, count)
    if simulate:
        return take, count - take, [list(x) for x in state]
    new_state = [list(x) for x in state]
    if take >= count:
        new_state.pop(index)
    else:
        new_state[index] = [item, count - take, max_stack]
    return take, count - take, new_state


def total_items(state):
    return sum(c for _, c, _ in state)


def conservation_model_ok():
    """可复跑序列：进袋 + 退回 = 输入；取出 + 剩袋 = 取前；放回后回到取前。"""
    ok = True
    problems = []

    state = []
    # 1) 空袋插入 32 个钻石（maxStack 64 ⇒ 每个重 1）
    remainder, state = model_insert(state, "diamond", 32, 64)
    if remainder != 0 or total_items(state) != 32:
        ok = False
        problems.append("insert-32")
    # 2) 再插 40 个同物品：容量只够 32 ⇒ 退回 8（不截断、不复制）
    remainder, state = model_insert(state, "diamond", 40, 64)
    if remainder != 8 or total_items(state) != 64:
        ok = False
        problems.append("insert-overflow remainder=%d total=%d" % (remainder, total_items(state)))
    # 3) 满袋插入别的物品：插不进，原样退回
    remainder, state2 = model_insert(state, "apple", 1, 64)
    if remainder != 1 or total_items(state2) != 64:
        ok = False
        problems.append("insert-full")
    # 4) 部分取出 10 个：拿走 10、袋里剩 54
    before = total_items(state)
    taken, left_in_slot, state = model_extract(state, 0, 10)
    if taken != 10 or left_in_slot != 54 or total_items(state) != before - 10:
        ok = False
        problems.append("extract-partial")
    # 5) 把拿出来的 10 个放回去：回到取前
    remainder, state = model_insert(state, "diamond", taken, 64)
    if remainder != 0 or total_items(state) != before:
        ok = False
        problems.append("reinsert")
    # 6) simulate 不得改状态
    snapshot = [list(x) for x in state]
    _, _ = model_insert(state, "apple", 1, 64, simulate=True)
    taken, _, _ = model_extract(state, 0, 5, simulate=True)
    if [list(x) for x in state] != snapshot or taken != 5:
        ok = False
        problems.append("simulate-mutates")
    # 7) 取空最后一个条目：槽位减少，但「取出 + 剩袋 = 取前」
    taken, _, state = model_extract(state, 0, 64)
    if taken != 64 or state != []:
        ok = False
        problems.append("extract-all taken=%d" % taken)
    # 8) 不同最大堆叠物品的重量差（maxStack 1 的剑占满 64）
    state = []
    remainder, state = model_insert(state, "netherite_sword", 1, 1)
    if remainder != 0 or total_items(state) != 1:
        ok = False
        problems.append("heavy-item")
    remainder, state = model_insert(state, "diamond", 1, 64)
    if remainder != 1:
        ok = False
        problems.append("heavy-full")
    return ok, problems


def conservation_negative_controls():
    """bug 模型必须判负（否则上面的模型没有判别力）。返回 (bug1_detected, bug2_detected)。"""
    # bug1：插入时把「请求数量」当成「真实插入数量」报告（remainder 恒为 0）⇒ 复制
    def buggy_insert(state, item, count, max_stack):
        _, new_state = model_try_insert(state, item, count, max_stack)
        return 0, new_state

    remainder, state = buggy_insert([], "diamond", 100, 64)
    bug1 = not (remainder == 0 and total_items(state) == 64 and 0 == 100 - 64)

    # bug2：取出部分数量但把整条删掉（丢失剩余）⇒ 不守恒
    def buggy_extract(state, index, amount):
        item, count, max_stack = state[index]
        take = min(amount, count)
        new_state = [list(x) for x in state]
        new_state.pop(index)
        return take, new_state

    state = [["diamond", 64, 64]]
    taken, new_state = buggy_extract(state, 0, 10)
    bug2 = not (taken == 10 and total_items(new_state) == 64 - 10)
    return bug1, bug2


# ==================================================================== ④ 背包快捷键说明
def backpack_hint_ok(screen_source):
    raw_code = screen_source
    code = strip_comments_and_strings(screen_source)
    methods = method_bodies(raw_code)
    body = methods.get("getTooltipFromContainerItem", "")
    return (
        "getTooltipFromContainerItem" in methods
        and 'lines.add(Component.translatable(LANG + "hint.sb_hotkey"))' in body
        # 必须用「菜单槽位对象相等」判断，不能用 Slot#index（玩家快捷栏第 1 格 index 也是 0）
        and "hoveredSlot == menu.getSlot(InventoryProxyMenu.PROXY_SLOT_INDEX)" in body
        and '"sophisticatedbackpacks"' in raw_code
        and "BuiltInRegistries.ITEM.getKey" in code
        # 说明必须常显（hover 到就看得到），不能又要 Shift
        and "hasShiftDown" not in code
        and "isShiftKeyDown" not in code
    )


# ==================================================================== ⑤ Jade 依赖点
def jade_capability_ok(be_source, jade_source, proxy_provider_source="", payload_source=""):
    """⑤ Jade 依赖点（第 108 轮加强，不是放宽）。

    第 95 轮口径：内容物交给 Jade 自己按 BLOCK 能力显示，本模组不写自定义 provider。
    第 108 轮用户第 108 条「只显示流体、物品不显示」之后**新增了一条只报事实的 provider**：
    它补「代理器实际认到几路 / 每路多少格」，内容物仍然完全交给 Jade。所以判据钉得更细：

      * BE 仍然同时注册 ItemHandler.BLOCK / FluidHandler.BLOCK（Jade Universal 靠它显示内容物）；
      * Jade 插件 / 客户端 provider 里不许出现 Shift 判定（用户第 4 条：不要"按住 Shift 看明细"）；
      * 载荷类不许自己搬内容物（不许出现 getStackInSlot / IFluidHandler 遍历）；
      * 客户端 provider 不许碰方块实体（getBlockEntity）。
    """
    be = strip_comments_and_strings(be_source)
    jade = strip_comments_and_strings(jade_source)
    provider = strip_comments_and_strings(proxy_provider_source)
    payload = strip_comments_and_strings(payload_source)
    return (
        "Capabilities.ItemHandler.BLOCK" in be
        and "Capabilities.FluidHandler.BLOCK" in be
        and "isShiftKeyDown" not in jade
        and "hasShiftDown" not in jade
        and "isShiftKeyDown" not in provider
        and "hasShiftDown" not in provider
        and "getStackInSlot" not in payload
        and "IFluidHandler" not in payload
        and "getBlockEntity" not in provider
    )


# ==================================================================== 主流程
screen_source = read(SCREEN)
handlers_source = read(HANDLERS)
be_source = read(BE)
jade_source = read(JADE) if os.path.isfile(JADE) else ""
# 第 108 轮新增的两条 Jade 接入文件（只报事实：认到几路 / 每路多少格）。
JADE_PROXY_PROVIDER = os.path.join(
    JAVA, "client", "jade", "RsccProxyJadeProvider.java")
JADE_PROXY_PAYLOAD = os.path.join(JAVA, "support", "InventoryProxyJadePayload.java")
jade_proxy_provider = read(JADE_PROXY_PROVIDER) if os.path.isfile(JADE_PROXY_PROVIDER) else ""
jade_proxy_payload = read(JADE_PROXY_PAYLOAD) if os.path.isfile(JADE_PROXY_PAYLOAD) else ""

print("=" * 78)
print("round95 WS-B：容器代理器（tooltip / 单行警告 / 收纳袋 / 背包快捷键说明 / Jade）")
print("=" * 78)

# ---------------------------------------------------------------- ① tooltip
check("①a InventoryProxyScreen 有 render(...) 覆写", "public void render(final GuiGraphics guiGraphics" in strip_comments_and_strings(screen_source))
ok, route = reachable_tooltip(method_bodies(strip_comments_and_strings(screen_source)))
check("①b render → renderTooltip 调用可达（不是只出现过字符串）", ok, route)
check("①c tooltip 调用不受 Shift 条件控制（用户第 4 条：Shift 详情归 Jade）",
      "hasShiftDown" not in strip_comments_and_strings(screen_source))

# ---------------------------------------------------------------- ② 常驻警告已删除
check("②a 常驻红字彻底删除（无 renderWarning / WARNING_* / COLOR_WARNING / pose 缩放）",
      face_tip_warning_ok(screen_source))
check("②b 面说明改由悬停按钮的 face_tip tooltip 承载，且 tooltip 只画一处（不会出现多段拼接）",
      'LANG + "face_tip."' in screen_source
      and len(re.findall(r'LANG \+ "face_tip\."', screen_source)) == 1)

# ---------------------------------------------------------------- ③ 收纳袋
check("③a BundleContentsView 存在且 implements IItemHandler", bundle_handler_ok(handlers_source, be_source))
check("③b 空袋给虚位（getSlots ≥ 1，否则插入方永远插不进第一个物品）", bundle_slots_ok(handlers_source))
ok, problems = conservation_model_ok()
check("③c 守恒模型（插入/满袋退回/部分取出/放回/simulate/取空/重物）", ok, ",".join(problems))
bug1, bug2 = conservation_negative_controls()
check("③d 反例模型「插入虚报数量 ⇒ 复制」判负", bug1)
check("③e 反例模型「部分取出删整条 ⇒ 丢剩余」判负", bug2)

# ---------------------------------------------------------------- ④ 背包快捷键说明
check("④a 代理槽里的精妙背包有常显说明（快捷键只认玩家背包栏 + 内容物面可读写）",
      backpack_hint_ok(screen_source))

# ---------------------------------------------------------------- ⑤ Jade
check("⑤ Jade 依赖点：BE 注册 ItemHandler.BLOCK / FluidHandler.BLOCK，且没有自定义 Shift 详情",
      jade_capability_ok(be_source, jade_source, jade_proxy_provider, jade_proxy_payload))

# ---------------------------------------------------------------- 反例自证（源码级）
print("-" * 78)
print("[反例自证] 把修复退回 / 判据写错，上面的检测必须判负")
print("-" * 78)


def audit_screen(mutated):
    """① 的判据（第 97 轮加强反例的判别力，不改 ①b 本身的口径）。

    第 97 轮 `render` 里多了「悬停六面按钮的 face_tip tooltip」，于是「render 可达某个
    renderTooltip」会被它满足 —— 反例「删掉槽位物品提示」就不再判负。这里额外要求
    **`render` 方法体里直接**有槽位那一版调用 `renderTooltip(guiGraphics, mouseX, mouseY)`：
    语义仍是第 95 轮那句「tooltip 必须手动绘制（不是只出现在字符串里）」，只是把「哪一条」
    钉清楚了（槽位物品提示）。
    """
    code = strip_comments_and_strings(mutated)
    ok, _ = reachable_tooltip(method_bodies(code))
    render_body = method_bodies(code).get("render") or ""
    return ok and bool(SLOT_TOOLTIP_CALL.search(render_body))


def audit_warning(mutated):
    return face_tip_warning_ok(mutated)


def audit_bundle(mutated_handlers, mutated_be):
    return bundle_handler_ok(mutated_handlers, mutated_be)


# 4a. 删掉 render 里的 tooltip 调用
victim = screen_source.replace(
    "        renderTooltip(guiGraphics, mouseX, mouseY);\n", "", 1)
check("反例 a（删掉 renderTooltip 调用）判负", victim != screen_source and not audit_screen(victim))

# 4b. 把 tooltip 调用挪进一个从不被调用的方法
moved = screen_source.replace(
    "        renderTooltip(guiGraphics, mouseX, mouseY);\n", "", 1)
moved = moved.replace(
    "\n}\n",
    "\n    private void deadTooltip(GuiGraphics g, int x, int y) {\n"
    "        renderTooltip(g, x, y);\n    }\n}\n", 1)
check("反例 b（tooltip 挪进从不被调用的方法）判负", moved != screen_source and not audit_screen(moved))

# 4c. 常驻红字回归（第 97 轮用户明确否掉那行字）
warning_back = screen_source.replace(
    "        guiGraphics.drawString(font, title, 8, titleLabelY, COLOR_TITLE, false);",
    "        guiGraphics.drawString(font, title, 8, titleLabelY, COLOR_TITLE, false);\n"
    "        renderWarning(guiGraphics);", 1)
check("反例 c（把常驻警告 renderWarning 调用注入回 renderLabels）判负",
      warning_back != screen_source and not audit_warning(warning_back))

# 4d. 收纳袋读恒空
read_null = handlers_source.replace("return contents.getItemUnsafe(slot).copy();",
                                    "return ItemStack.EMPTY;", 1)
check("反例 d（收纳袋读恒空）判负", read_null != handlers_source and not audit_bundle(read_null, be_source))

# 4e. 取出不写回（守恒断链）
no_write = handlers_source.replace("                write(new BundleContents(items));\n", "", 1)
check("反例 e（取出不写回组件）判负", no_write != handlers_source and not audit_bundle(no_write, be_source))

# 4f. 去掉空袋虚位
no_phantom = handlers_source.replace("Math.max(MIN_SLOTS, contents.size())", "contents.size()", 1)
check("反例 f（空袋 0 槽）判负", no_phantom != handlers_source and not audit_bundle(no_phantom, be_source))

# 4g. BE 不接线（容量面永远读不到收纳袋）
# 第 108 轮：resolveInner 里的局部变量改名（innerItem → item），变异点跟着改；
# 若哪天写法又变，兜底再按「不带缩进的同一句」变异一次，避免反例自证变成空转。
no_wire = be_source.replace(
    "                item = new InventoryProxyHandlers.BundleContentsView(this);\n", "", 1)
if no_wire == be_source:
    no_wire = be_source.replace(
        "item = new InventoryProxyHandlers.BundleContentsView(this);", "", 1)
check("反例 g（BE 不建组件视图）判负", no_wire != be_source and not audit_bundle(handlers_source, no_wire))

# 4h. 说明删掉
no_hint = screen_source.replace(
    "            lines.add(Component.translatable(LANG + \"hint.sb_hotkey\"));\n", "", 1)
check("反例 h（删掉背包快捷键说明）判负", no_hint != screen_source and not backpack_hint_ok(no_hint))

# 4h2. 提示判定退回 Slot#index（玩家快捷栏第 1 格 index 也是 0 ⇒ 提示挂错槽）
wrong_slot = screen_source.replace(
    "hoveredSlot == menu.getSlot(InventoryProxyMenu.PROXY_SLOT_INDEX)",
    "hoveredSlot.index == InventoryProxyMenu.PROXY_SLOT_INDEX", 1)
check("反例 h2（用 Slot#index 判代理槽）判负",
      wrong_slot != screen_source and not backpack_hint_ok(wrong_slot))

# 4i. Jade 依赖点被破坏（删掉 ItemHandler.BLOCK 注册）
no_jade_cap = be_source.replace("Capabilities.ItemHandler.BLOCK", "Capabilities.ItemHandler.ITEM", 1)
check("反例 i（删掉方块 ItemHandler.BLOCK 注册）判负",
      no_jade_cap != be_source and not jade_capability_ok(no_jade_cap, jade_source))

print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 10 个源码级反例 + 2 个守恒模型反例）；"
      "容器代理器 tooltip/常驻警告已改 face_tip tooltip/收纳袋组件式 handler/背包快捷键说明/Jade 依赖点全部成立"
      % CHECKS[0])
sys.exit(0)
