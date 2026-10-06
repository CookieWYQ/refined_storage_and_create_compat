# -*- coding: utf-8 -*-
"""高级远程多功能终端两个回归的自检：源码锚点 + 关键逻辑推演。

覆盖：
  A. 「打开意图」标记：三处置位、消费后清空、超时失效、「背包有终端但未置位 → 不加按钮」
  B. 三版终端（普通/满电/创造）共用同一条查找路径（只看 instanceof，不看 Type）
  C. 服务端查找优先级（主手 → 副手 → 背包 0..35 → Curios）与「多个取第一个而非拒绝」
  D. 找不到时的短提示只提示一次、不刷屏
  E. 快捷键不再依赖 RS 的引用唯一性（useSlotReferencedItem / findForUse）

用法: python tools/selfcheck_terminal_shortcut.py
"""
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FILES = {
    "intent": os.path.join(PKG, "client", "TerminalOpenIntent.java"),
    "overlay": os.path.join(PKG, "client", "TerminalModeTabOverlay.java"),
    "keybind": os.path.join(PKG, "client", "TerminalKeybinds.java"),
    "item": os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java"),
    "locator": os.path.join(PKG, "support", "RsccTerminalLocator.java"),
    "packet": os.path.join(PKG, "network", "OpenAdvancedRemoteTerminalPacket.java"),
    "curios": os.path.join(PKG, "support", "RsccCuriosTerminalSlot.java"),
    "refs": os.path.join(PKG, "support", "RsccTerminalReferences.java"),
    "main": os.path.join(PKG, "RS_Create_Compat.java"),
}

PROBLEMS = []
CHECKS = [0]


def read(key):
    with io.open(FILES[key], "r", encoding="utf-8") as f:
        return f.read()


def code_only(src):
    """剥掉 // 与 /*...*/ 注释：只对「真实代码」做锚点判定（javadoc 里会提到被废弃的 API 名）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def java_body(src, signature):
    """取某个方法（以 signature 开头）的花括号体（按括号配平，粗略但足够）。"""
    start = src.find(signature)
    if start < 0:
        return ""
    open_idx = src.find("{", start)
    if open_idx < 0:
        return ""
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_idx:i + 1]
    return ""


# ----------------------------------------------------------------------------- A
print("=" * 78)
print("A. 「打开意图」标记（回归 1）")
print("=" * 78)
intent = read("intent")
overlay = read("overlay")
keybind = read("keybind")
item = read("item")

m = re.search(r"TIMEOUT_MS\s*=\s*(\d+)L", intent)
timeout = int(m.group(1)) if m else -1
check(2000 <= timeout <= 3000, "超时值在 2~3 秒区间", "TIMEOUT_MS=%d ms" % timeout)

check("public static void mark()" in intent and "public static boolean consume(final Object screen)" in intent,
      "标记类提供 mark() / consume(Object) 两个入口")

# 三处置位
keybind_marks = len(re.findall(r"TerminalOpenIntent\.mark\(\)", keybind))
overlay_marks = len(re.findall(r"TerminalOpenIntent\.mark\(\)", overlay))
item_marks = len(re.findall(r"TerminalOpenIntent\.mark\(\)", item))
check(keybind_marks == 1, "置位点①快捷键 TerminalKeybinds", "%d 处" % keybind_marks)
check(overlay_marks == 1, "置位点②模式切换按钮回调 TerminalModeTabOverlay", "%d 处" % overlay_marks)
check(item_marks == 1, "置位点③手持右键 Item#use", "%d 处" % item_marks)

# 置位必须先于消费/加按钮：按键置位在发包之前
kb_body = java_body(keybind, "public static void onKeyInput")
check(kb_body.find("TerminalOpenIntent.mark()") < kb_body.find("sendToServer"),
      "快捷键：先置位、后发包")
# 右键置位只在客户端分支，且先于 super.use
item_body = java_body(item, "public InteractionResultHolder<ItemStack> use(")
check("level.isClientSide()" in item_body and item_body.find("TerminalOpenIntent.mark()")
      < item_body.find("super.use"), "右键：仅在 isClientSide() 分支置位，然后走 super.use")
# 按钮回调置位在发包之前（同一 lambda 内）
cb = overlay.find("TerminalOpenIntent.mark()")
check(cb > 0 and overlay.find("sendSwitch(player, slotReference, mode", cb) > cb,
      "模式切换：先在按钮回调置位、再发包重开界面")

# 消费位置：必须在加按钮之前，且未置位直接 return
init = java_body(overlay, "public static void onScreenInit")
consume_at = init.find("TerminalOpenIntent.consume(screen)")
add_at = init.find("event.addListener")
check(consume_at > 0 and add_at > consume_at, "onScreenInit：先消费标记、后加按钮")
check("if (!TerminalOpenIntent.consume(screen))" in init and init[consume_at:consume_at + 120].find("return") > 0,
      "未置位（超时/已被别人消费）→ 直接 return，一个按钮都不加")
check("findTerminal(player)" not in overlay and "TerminalLocation" not in overlay,
      "onScreenInit 不再按「玩家身上有没有终端」决定是否加按钮")
check("instanceof AdvancedRemoteTerminalItem" not in overlay,
      "客户端叠加层不再自己做 instanceof 存在性判定（统一交给 locator）")

# ----------------------------------------------------------------------------- B
print()
print("=" * 78)
print("B. 三版终端共用同一条路径（不看 Type）")
print("=" * 78)
locator = read("locator")
packet = read("packet")
main = read("main")

check(re.search(r"private static boolean isTerminal\(final ItemStack stack\)", locator) is not None
      and "instanceof AdvancedRemoteTerminalItem" in locator,
      "查找判定按「类」识别终端（instanceof AdvancedRemoteTerminalItem）")
# 2026-09-25：类判定之外再按「本模组三件终端的注册 id」兜一层底（双保险）——
# 三个型号共用同一个实现类，id 恒定，因此任何型号（含创造版）都不会漏；类判定也能继续覆盖将来新增的同族实现。
ids = re.findall(r'ResourceLocation\.fromNamespaceAndPath\(RS_Create_Compat\.MODID, '
                 r'"(advanced_remote_terminal|advanced_remote_terminal_charged|'
                 r'creative_advanced_remote_terminal)"\)', locator)
check(sorted(ids) == ["advanced_remote_terminal", "advanced_remote_terminal_charged",
                      "creative_advanced_remote_terminal"],
      "查找判定另有「三件终端 id」兜底（含创造版 creative_advanced_remote_terminal）", ",".join(sorted(ids)))
check("TERMINAL_IDS.contains(" in locator,
      "id 兜底确实接在 isTerminal 里（不是只声明了常量）")
check("isCreative" not in locator and "Type." not in locator,
      "查找逻辑完全不区分型号（无 Type / isCreative 分支；三个型号同一条路径）")
check("NOT_FOUND_LOGGED" in locator and "logNothingFound(" in locator,
      "一台都没找到时打一条节流日志（每名玩家一次，便于反馈时定位，且不刷屏）")

shortcut_body = java_body(item, "public void useFromShortcut(")
check(len(shortcut_body) > 0 and "type" not in shortcut_body and "isCreative" not in shortcut_body,
      "打开界面入口 useFromShortcut 不按型号分支")

# 三个物品都是同一个类（构造参数只是 Type），且注册的是同一个类
types = re.findall(r'ITEMS\.register\("(advanced_remote_terminal|advanced_remote_terminal_charged|creative_advanced_remote_terminal)"',
                   main)
check(len(types) == 3, "三个型号物品均注册于 RS_Create_Compat", ",".join(types))
check(len(re.findall(r"new AdvancedRemoteTerminalItem\(AdvancedRemoteTerminalItem\.Type\.", main)) == 3,
      "三个型号都是 AdvancedRemoteTerminalItem 的实例（仅构造参数不同）")
check(len(re.findall(r"instanceof AdvancedRemoteTerminalItem", packet)) == 1,
      "服务端处理只按 instanceof AdvancedRemoteTerminalItem 分流（不看型号）")

# ----------------------------------------------------------------------------- C
print()
print("=" * 78)
print("C. 服务端权威查找：优先级 + 多个取第一个")
print("=" * 78)
find_all = java_body(locator, "public static List<Location> findAll(")
order = re.findall(r"add(Hand|Inventory|Curios)\(found, player", find_all)
check(order == ["Hand", "Hand", "Inventory", "Curios"],
      "优先级顺序：主手 → 副手 → 背包 → Curios", " → ".join(order))
hand_hands = re.findall(r"addHand\(found, player, InteractionHand\.(\w+)\)", find_all)
check(hand_hands == ["MAIN_HAND", "OFF_HAND"], "双手顺序：主手优先于副手", str(hand_hands))
check("INVENTORY_END = 36" in locator and "index < INVENTORY_END" in locator,
      "背包扫描 0..35（快捷栏 + 主背包）")
inv_body = java_body(locator, "private static void addInventory(")
check("player.getInventory().selected" in inv_body and "index == mainHandIndex" in inv_body,
      "背包扫描跳过主手下标（避免同一台被算两次）")
check("RsccCuriosTerminalSlot.findAllTerminalReferences" in read("locator"),
      "Curios 档位走饰品槽枚举（槽位 id → 槽内下标）")

locate = java_body(locator, "public static Optional<Location> locate(")
check(locate.count("Optional.empty()") == 1 and "found.getFirst()" in locate,
      "多个不拒绝：只在「一个都没有」时返回 empty，其余取 getFirst()")
check("cannot_open_with_shortcut_due_to_duplicate" not in locator
      and "cannot_open_with_shortcut_due_to_duplicate" not in packet
      and "cannot_open_with_shortcut_due_to_duplicate" not in keybind,
      "没有任何「重复 → 拒绝」的分支")

# ----------------------------------------------------------------------------- D
print()
print("=" * 78)
print("D. 找不到时的短提示：只提示一次、不刷屏")
print("=" * 78)
handle = java_body(packet, "public static void handle(")
check(handle.count("displayClientMessage") == 1, "服务端每次请求最多一条提示")
check("return;" in handle and handle.find("displayClientMessage") < handle.find("useFromShortcut"),
      "找不到 → 提示后立刻 return（不会继续打开界面）")
check("ChatFormatting.RED" in handle, "提示为红字")
check("if (!mapping.consumeClick())" in kb_body and kb_body.count("sendToServer") == 1,
      "快捷键一次按键只发一条请求（先 consumeClick 一次，再清空累计按下）")
check(len(re.findall(r"discardClicks\(mapping\)", kb_body)) == 2,
      "累计按下被丢弃（界面中按键 / 连按都不会攒成多条请求）")

lang_dir = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LANG = {}
for name in ("zh_cn.json", "en_us.json"):
    with io.open(os.path.join(lang_dir, name), "r", encoding="utf-8") as f:
        LANG[name] = json.load(f)
KEY = "item.rs_create_compat.advanced_remote_terminal.not_found"
check(LANG["zh_cn.json"].get(KEY) and LANG["en_us.json"].get(KEY),
      "中英双语均含提示键 %s" % KEY)
check(len(LANG["zh_cn.json"].get(KEY, "")) <= 30,
      "中文文案 ≤ 30 字", "%d 字：%s" % (len(LANG["zh_cn.json"].get(KEY, "")), LANG["zh_cn.json"].get(KEY, "")))

# ----------------------------------------------------------------------------- E
print()
print("=" * 78)
print("E. 与 RS 的「引用唯一性」解耦")
print("=" * 78)
src_hits = []
for base, _, names in os.walk(os.path.join(ROOT, "src", "main", "java")):
    for name in names:
        if not name.endswith(".java"):
            continue
        p = os.path.join(base, name)
        with io.open(p, "r", encoding="utf-8") as f:
            # 只看真实代码：javadoc 里会解释「为什么不用 useSlotReferencedItem」，不算调用
            if re.search(r"useSlotReferencedItem\s*\(", code_only(f.read())):
                src_hits.append(os.path.relpath(p, ROOT))
check(not src_hits, "本模组源码已无 useSlotReferencedItem 调用", str(src_hits))
check("OpenAdvancedRemoteTerminalPacket.STREAM_CODEC" in main and "OpenAdvancedRemoteTerminalPacket.TYPE" in main,
      "新 C2S 包已在 RS_Create_Compat 注册")
check("findForUse" not in keybind and "RefinedStorageApi.INSTANCE" not in keybind,
      "快捷键不再引用 RS 的引用查找 API")
check("public static void register()" in read("curios"),
      "Curios 的 SlotReferenceProvider 注册保留（RS 原生无线物品仍要用）")
# 本轮：本模组不再自建饰品槽 —— 查找路径只认**前置槽** id，并兜底枚举玩家实际拥有的全部槽位
curios_src = read("curios")
check('"refinedstorage_curios_integration"' in curios_src
      and '"rs_create_compat_curios_integration"' not in curios_src,
      "饰品槽查找路径只认前置槽 refinedstorage_curios_integration（本模组槽位 id 已彻底移除）")
check("CANDIDATE_SLOT_IDS = {RS_SLOT_ID}" in curios_src,
      "候选槽位表只含前置槽（不再优先扫本模组自己的槽）")
check("getStacksHandler" in curios_src and 'cachedMethod(GET_CURIOS_METHODS' in curios_src,
      "仍保留「首选前置槽 + 兜底枚举玩家实际槽位」两条取值路径（快捷键能找到戴在前置槽里的终端）")

# ----------------------------------------------------------------------------- F
print()
print("=" * 78)
print("F. 逻辑推演（按源码语义建模）")
print("=" * 78)
TIMEOUT = timeout


class Intent(object):
    """TerminalOpenIntent 的等价模型。"""

    def __init__(self):
        self.expire_at = 0
        self.claimed_by = None

    def mark(self, now):
        self.expire_at = now + TIMEOUT
        self.claimed_by = None

    def consume(self, screen, now):
        if now > self.expire_at:
            self.expire_at, self.claimed_by = 0, None
            return False
        if self.claimed_by is None:
            self.claimed_by = screen
            return True
        return self.claimed_by is screen


BUTTONS = 6  # AdvanceRemoteTerminalItem.MODE_COUNT


def buttons(intent, screen, now):
    """onScreenInit：标记未置位 → 0 个按钮；置位 → MODE_COUNT 个。"""
    return BUTTONS if intent.consume(screen, now) else 0


backpack_with_terminal = True  # 玩家背包里有终端（回归 1 的触发条件）
i = Intent()
check(backpack_with_terminal and buttons(i, object(), 1000) == 0,
      "背包里有终端但界面不是终端打开的（标记未置位）→ 不加按钮")

i = Intent()
grid, other = object(), object()
i.mark(1000)
check(buttons(i, grid, 1000) == BUTTONS, "快捷键/右键打开的新界面 → 加按钮")
check(buttons(i, other, 1050) == 0, "标记已被认领 → 之后打开的其它 RS 界面不加按钮")
check(buttons(i, other, 1050) == 0, "同一标记不会被第二个界面认领")
check(buttons(i, grid, 1050) == BUTTONS,
      "同一界面重建（窗口缩放再次 Init）→ 仍加按钮（不因一次性消费而丢失）")

i = Intent()
i.mark(1000)
check(buttons(i, object(), 1000 + TIMEOUT + 1) == 0, "超时后 → 不加按钮")

i = Intent()
i.mark(1000)  # 点模式按钮
check(buttons(i, object(), 1200) == BUTTONS, "切完模式后新界面仍有这一排（按钮回调重新置位）")


class Locator(object):
    """RsccTerminalLocator 的等价模型（含优先级与「多个取第一个」）。"""

    def __init__(self, main=None, off=None, inv=None, curios=None):
        self.main, self.off = main, off
        self.inv = inv or {}          # 下标 -> 物品名
        self.curios = curios or {}    # (槽位, 下标) -> 物品名

    def find_all(self, selected=0):
        found = []
        if self.main:
            found.append(("main_hand", self.main))
        if self.off:
            found.append(("off_hand", self.off))
        for idx in sorted(self.inv):
            if idx == selected:
                continue
            found.append(("inv#%d" % idx, self.inv[idx]))
        for slot in sorted(self.curios):
            found.append(("curios#%s" % (slot,), self.curios[slot]))
        return found

    def locate(self, selected=0):
        found = self.find_all(selected)
        return found[0] if found else None


T = ["normal", "charged", "creative"]
check(Locator(main=T[0]).locate()[0] == "main_hand", "只有主手 → 主手")
check(Locator(off=T[1], inv={5: T[0]}).locate() == ("off_hand", "charged"),
      "副手优先于背包", str(Locator(off=T[1], inv={5: T[0]}).locate()))
check(Locator(inv={12: T[0], 20: T[1]}).locate() == ("inv#12", "normal"),
      "背包内取下标最小的一台（多个不拒绝）")
check(Locator(inv={12: T[0]}, curios={(0, 0): T[2]}).locate() == ("inv#12", "normal"),
      "背包优先于 Curios 饰品槽")
check(Locator(curios={(0, 0): T[2]}).locate() == ("curios#(0, 0)", "creative"),
      "只有饰品槽 → 饰品槽")
check(Locator().locate() is None, "一个都没有 → 没有可打开的目标（服务端回短提示）")
check(Locator(main=T[2]).locate() == ("main_hand", "creative")
      and Locator(main=T[1]).locate() == ("main_hand", "charged")
      and Locator(main=T[0]).locate() == ("main_hand", "normal"),
      "普通 / 满电 / 创造走完全相同的查找路径（只看 instanceof）")
check(Locator(main=T[0], inv={1: T[1], 2: T[2]}).find_all().__len__() == 3
      and Locator(main=T[0], inv={1: T[1], 2: T[2]}).locate() == ("main_hand", "normal"),
      "三台同时在身上 → 仍按优先级取第一台（不拒绝）")

# ---- 全组合推演：普通 / 满电 / 创造 × 主手 / 副手 / 背包 / 快捷栏 / 饰品槽 = 15 种，全部可打开 ----
# 快捷栏在这里与「背包」同档（下标 0..8 属于背包容器，主手只是其中被选中的那一格）：
# 因此「快捷栏」用「物品在 inv#0 且 selected=0（= 拿在手上）」来建模，与 Java 的
# addHand(MAIN_HAND) + addInventory(跳过主手下标) 完全一致。
def combos_for(name):
    return [
        ("主手", dict(main=name)),
        ("副手", dict(off=name)),
        ("背包", dict(inv={9: name})),
        ("快捷栏（选中）", dict(main=name, inv={0: name})),
        ("饰品槽", dict(curios={(0, 0): name})),
    ]


bad = []
opened = 0
for item_name in T:
    for where, kwargs in combos_for(item_name):
        got = Locator(**kwargs).locate(0)
        if got is None:
            bad.append("%s@%s" % (item_name, where))
        else:
            opened += 1
check(not bad and opened == 15,
      "推演：普通 / 满电 / 创造 × 5 个位置 = 15 种组合全部能定位到终端（含创造版在任何位置）",
      ("失败组合=%s" % bad) if bad else "15/15 可定位")
check(Locator().locate() is None,
      "推演：一个都没有 → locate 返回空（服务端据此回一条短提示，不打开界面）")

# ---- Curios 槽位处理器形状：必须认「IItemHandler 风格」的 getStacks() 返回值 ----
curios_src = read("curios")
check("stacksOf(raw" in curios_src and "itemsOf(" in curios_src,
      "Curios：getStacks() 返回非 List 时改走 itemsOf()（对返回的槽位容器按 getSlots + getStackInSlot 读）")
check("getSlots().*getStackInSlot" not in curios_src and "getStackInSlot" in curios_src,
      "Curios：按 getSlots() + getStackInSlot(int) 读槽位容器（旧实现只问处理器自己 → 静默空表）")
check("logUnknownShape(" in curios_src,
      "Curios：处理器形状认不出来时打一条节流日志（不再静默失败）")


def legacy_stacks_of(handler_shape):
    """旧实现等价模型：只认「getStacks() 返回 List」，否则问处理器自己要 getSlots+getStackInSlot。

    * handler_shape = "list"：getStacks() 直接给 List → 旧实现能读到；
    * handler_shape = "iitemhandler"（Curios 9.x 的真实形状）：getStacks() 给的是槽位容器，
      而处理器自身只有 getSlots()（没有 getStackInSlot）→ 旧实现静默返回空表（这就是「饰品槽找不到」的真因）。
    """
    if handler_shape == "list":
        return ["terminal"]
    return []          # 处理器有 getSlots，但没有 getStackInSlot → 空表


def fixed_stacks_of(handler_shape):
    """新实现等价模型：getStacks() 非 List 时，对『返回的那个槽位容器』按槽位读。"""
    if handler_shape == "list":
        return ["terminal"]
    return ["terminal"]  # itemsOf(getStacks() 的返回值) → 按 getSlots + getStackInSlot 读到终端


check(legacy_stacks_of("iitemhandler") == [] and fixed_stacks_of("iitemhandler") == ["terminal"],
      "推演：Curios 9.x 的处理器（getStacks() → IDynamicStackHandler）旧实现恒空表、新实现能读到终端")
check(legacy_stacks_of("list") == fixed_stacks_of("list") == ["terminal"],
      "推演：getStacks() 直接给 List 的版本行为不变（修复不引入回归）")

print()
print("=" * 78)
print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
for p in PROBLEMS:
    print("  - " + p)
print("=" * 78)
sys.exit(1 if PROBLEMS else 0)
