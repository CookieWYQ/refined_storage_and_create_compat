#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 99 轮自检：电动飞行背包（`rs_create_compat:electric_jetpack`）的源码级锚点。

契约：任务板 task-24（口径 + 收词表）。本脚本只读源码 / 语言文件，**不改任何 src/main**；
只用 Python 标准库。

断言清单（①..⑨，与 task-24 的验收逐条对应）：
  ① 物品本体：能量存在物品组件里（落盘）、无耐久、**无护甲值**、胸甲槽走 1.21.1 的 `Equipable`、
     物品级 FE 能力注册（`Capabilities.EnergyStorage.ITEM`）且只注册一次。
  ② 生效位置只有三处（胸甲槽 / Curios back / Curios chest），Curios 读取走仓库既有封装
     （不自己反射），其它槽（背包 / 副手 / 其它饰品槽）不生效。
  ③ 服务端权威飞行状态机：只对 `ServerPlayer` 生效；授予三条件（装备 + 够一次 tick 成本 + 非创造/旁观）；
     创造 / 旁观**绝不覆盖**；收回路径覆盖 5 种情形（卸下 / 耗尽 / 换维度 / 下线 / 死亡）+ 登录幂等兜底；
     收回只收「我们发的」那份权限（持久标记）且 flying 一并收回。
  ④ 耗电口径：只在 `abilities.flying` 时扣；先采样后扣电；`charging` 判据 = 能量变大（**不是距离**）。
  ⑤ 低电 / 耗尽提示：跨阈值只发一次 + 最慢 600 tick（30 秒）一条；title 走原版两个包；
     **低电 title 只在玩家真的在飞（`abilities.flying == true`）时才发**（第 105 轮用户口径：
     低电但没在飞就一声不吭）。
  ⑥ HUD：数据只来自服务端快照（不读物品组件 / 不查方块实体 / 不推算）；自绘（fill + drawString，
     不需要贴图）；`charging` 与 `inRange` 两个标记分开显示；快照过期自动隐藏；客户端不写世界。
  ⑦ 范围几何：复刻充电器 `scanPlayers` 的包围盒（`range/2` + `+1` + 碰撞箱相交）、只查已加载区块
     （`getChunkNow`）、无限范围升级、旁观不算。
  ⑧ 网络包：`JetpackStatusPacket` 已注册（playToClient + TYPE + STREAM_CODEC），处理体只写双端安全的
     镜像；`network/**`、`support/**` 不引用客户端类型（复用 round47 判据）。
  ⑨ 不越界：无 `Attributes.ARMOR`、无耐久写读、客户端不写世界、语言键（已存在的必须成对；缺的只告警）。

反例自证：≥8 条「旧写法 / 错误写法」注入虚拟源码或替换行为模型，断言判据判负
（打印 `反例 ... PASS`）。所有「判据函数」都接受文本参数，因此反例是**真的在跑同一套判据**。

用法：python tools/selfcheck_round99_jetpack.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老 Python / 重定向
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
PKG_REL = "cretae/cookiewyq/rs_create_compat"
ITEM_DIR = os.path.join(PKG, "item")
SUPPORT = os.path.join(PKG, "support")
CLIENT = os.path.join(PKG, "client")
NET = os.path.join(PKG, "network")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
TOOLS = os.path.dirname(os.path.abspath(__file__))

ITEM = os.path.join(ITEM_DIR, "ElectricJetpackItem.java")
ENERGY = os.path.join(SUPPORT, "JetpackEnergyStorage.java")
EQUIPPED = os.path.join(SUPPORT, "JetpackEquipped.java")
HUD_STATE = os.path.join(SUPPORT, "JetpackHudState.java")
RANGE = os.path.join(SUPPORT, "JetpackChargerRange.java")
HANDLER = os.path.join(SUPPORT, "JetpackFlightHandler.java")
PACKET = os.path.join(NET, "JetpackStatusPacket.java")
HUD = os.path.join(CLIENT, "JetpackHudOverlay.java")
MAIN = os.path.join(PKG, "RS_Create_Compat.java")
CONFIG = os.path.join(PKG, "Config.java")
CURIOS_WRAPPER = os.path.join(SUPPORT, "RsccCuriosTerminalSlot.java")
CHARGER_BE = os.path.join(PKG, "block", "entity", "RangeChargerBlockEntity.java")
CLIENT_INIT = os.path.join(CLIENT, "ClientInit.java")

#: 本轮允许出现的全部背包语言键（zh/en 必须都有；脚本会与源码里引用到的键对账）。
EXPECTED_LANG_KEYS = [
    "item.rs_create_compat.electric_jetpack",
    "item.rs_create_compat.electric_jetpack.tooltip.energy",
    "item.rs_create_compat.electric_jetpack.tooltip.flight_left",
    "item.rs_create_compat.electric_jetpack.tooltip.slots",
    "item.rs_create_compat.electric_jetpack.tooltip.flight",
    "item.rs_create_compat.electric_jetpack.tooltip.cost",
    "item.rs_create_compat.electric_jetpack.tooltip.low_power",
    "item.rs_create_compat.electric_jetpack.tooltip.charge",
    "item.rs_create_compat.electric_jetpack.hud.charging",
    "item.rs_create_compat.electric_jetpack.hud.in_range",
    "item.rs_create_compat.electric_jetpack.hud.flight_left",
    "item.rs_create_compat.electric_jetpack.low_power.title",
    "item.rs_create_compat.electric_jetpack.low_power.subtitle",
    "item.rs_create_compat.electric_jetpack.depleted.title",
]

FAILS = []
CHECKS = [0]
COUNTER = [0]
WARNS = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok):
    CHECKS[0] += 1
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        FAILS.append(name)
    return ok


def counterexample(name, ok):
    """反例自证：参数是「判据对坏写法的结论」，必须为 True（即真的判负了）。"""
    COUNTER[0] += 1
    check("反例 %s" % name, ok)


def warn(name):
    WARNS.append(name)
    print("WARN " + name)


# ======================================================================================
# 通用工具：剥注释 / 取方法体 / 判据函数（全部接受文本参数，便于反例注入）
# ======================================================================================

def strip_comments(text):
    """把 // 与 /*...*/ 注释替换成空格（保留换行与字符串字面量）。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in ("\"", "'"):
                quote = char
                out.append(char)
                i += 1
                continue
            out.append(char)
            i += 1
            continue
        if char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        if char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def method_body(text, signature_regex):
    """signature_regex 命中后，从其后第一个 `{` 做括号配平，返回方法体（含 `{`...`}`）。"""
    code = strip_comments(text)
    match = re.search(signature_regex, code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for index in range(brace, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():index + 1]
    return None


def between(text, start_token, end_token):
    code = strip_comments(text)
    start = code.find(start_token)
    if start < 0:
        return ""
    end = code.find(end_token, start + len(start_token))
    return code[start:] if end < 0 else code[start:end]


def int_consts(text):
    """收集 `static final int NAME = <表达式>;`，多轮替换后求值（只解析数字表达式）。"""
    code = strip_comments(text)
    raw = []
    for match in re.finditer(r"static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);", code):
        raw.append((match.group(1), match.group(2).strip()))
    values = {}
    for _ in range(10):
        progress = False
        for name, expr in raw:
            if name in values:
                continue
            resolved = expr
            for known, known_value in values.items():
                resolved = re.sub(r"\b%s\b" % re.escape(known), str(known_value), resolved)
            try:
                values[name] = int(eval(resolved, {"__builtins__": {}}, {}))  # noqa: S307 - 只解析数字
                progress = True
            except Exception:
                continue
        if not progress:
            break
    return values


# ======================================================================================
# ① 物品本体：落盘 / 无护甲 / 胸甲槽 / 能力注册
# ======================================================================================

def item_class_ok(text):
    code = strip_comments(text)
    return ("class ElectricJetpackItem extends Item implements Equipable" in code
            and "public EquipmentSlot getEquipmentSlot() {" in code
            and re.search(r"getEquipmentSlot\(\)\s*\{\s*return\s+EquipmentSlot\.CHEST;", code) is not None
            and re.search(r"swapWithEquipmentSlot\(this,\s*level,\s*player,\s*hand\)", code) is not None)


def creative_equip_no_dupe_ok(text):
    """第 105 轮：创造模式右键穿背包必须是「移动」而不是「复制」。

    原版 `Equipable#swapWithEquipmentSlot` 在创造模式用 `held.copy()` 写胸甲槽且**手上保留原件**
    （「创造模式穿甲不消耗」）⇒ 对背包就是真复制：手上 1 + 胸甲 1，而按 Q 只能丢快捷栏那格，
    胸甲那份永远丢不掉 = 幽灵。所以 `use()` 必须为创造模式单独接管，且分支里要真的把
    手上那件写进装备槽、再把旧件写回手槽（= 移动语义），不允许仍然直接调原版 swap。
    """
    code = strip_comments(text)
    body = method_body(code, r"public InteractionResultHolder<ItemStack> use\(")
    if body is None:
        return False
    if "swapWithEquipmentSlot(this, level, player, hand)" not in body:
        return False
    if "player.isCreative()" not in body:
        return False
    # 创造分支从「读取手上那件」开始（生存分支的 swap 调用在它之前，允许存在）
    creative_at = body.find("final ItemStack held = player.getItemInHand(hand);")
    if creative_at < 0:
        return False
    creative = body[creative_at:]
    if "swapWithEquipmentSlot" in creative:
        return False
    return ("player.setItemSlot(slot, held.copy())" in creative
            and "player.setItemInHand(hand, worn)" in creative)


def simulate_creative_equip(move_not_copy=True):
    """行为模型：创造模式右键穿一次后，玩家身上还剩几件背包（必须恒为 1）。"""
    hand = "jetpack"
    worn = None
    if move_not_copy:
        chest, hand = hand, worn
    else:  # 原版创造模式：copy 进槽，手上保留
        chest, hand = hand, hand
    return [item for item in (hand, chest) if item is not None]


def no_armor_ok(sources):
    """无护甲值：本轮所有背包源码里都不许出现 ARMOR 属性 / 属性修饰符相关 token。"""
    banned = ("Attributes.ARMOR", "ARMOR_", "ATTRIBUTE_MODIFIERS", "ItemAttributeModifiers",
              "AttributeModifier(", "ARMOR_TOUGHNESS", "ARMOR_KNOCKBACK_RESISTANCE")
    for text in sources:
        code = strip_comments(text)
        for token in banned:
            if token in code:
                return False
    return True


def no_durability_ok(sources):
    banned = ("DataComponents.DAMAGE", "DataComponents.MAX_DAMAGE", "MAX_DAMAGE",
              "getMaxDamage", "setDamage(", "isDamageableItem")
    for text in sources:
        code = strip_comments(text)
        for token in banned:
            if token in code:
                return False
    return True


def energy_persisted_ok(text):
    """电量必须落在物品自己的组件里：写入 putInt(TAG_ENERGY)、读取 getInt(TAG_ENERGY)，键名唯一。"""
    code = strip_comments(text)
    return (
        'public static final String TAG_ENERGY = "RsccJetpackEnergy";' in code
        and "DataComponents.CUSTOM_DATA" in code
        and re.search(r"tag\.putInt\(TAG_ENERGY,\s*clamped\)", code) is not None
        and re.search(r"\.getInt\(TAG_ENERGY\)", code) is not None
        and "public static int getEnergy(final ItemStack stack)" in code
        and "public static void setEnergy(final ItemStack stack, final int energy)" in code
        and "public static int consume(final ItemStack stack, final int amount)" in code
    )


def config_fallback_ok(text):
    """容量 / 速率 / 耗电 / 低电阈值都要有「配置未加载（读到 0）」的安全回退。"""
    code = strip_comments(text)
    for method in ("capacity", "transfer", "flightCost", "lowPowerMinutes"):
        body = method_body(code, r"public static int %s\(\)" % method)
        if body is None or "DEFAULT_" not in body:
            return False
    return ("DEFAULT_CAPACITY = 50_000_000" in code
            and "DEFAULT_TRANSFER = 100_000" in code
            # 第 108 轮：用户要求「耗电量降到 8 FE/tick，这个值可以配置」⇒ 回退答案也是 8。
            and "DEFAULT_FLIGHT_COST = 8" in code
            # 第 109 轮：低电阈值口径从「剩余百分比」改成「剩余可飞分钟」，回退答案 30 分钟。
            and "DEFAULT_LOW_POWER_MINUTES = 30" in code
            and "return Math.max(1, cost);" in code
            and "Math.clamp(minutes, 1, 100000)" in code)


def jetpack_cost_config_ok(config_text):
    """第 108 轮：耗电默认 **8 FE/tick** 且**可配置**（用户口径：「耗电量降到 8 FE/tick，这个值可以配置」）。

    判据三件：① `Config` 的 `defineInRange("electricJetpackFlightCostPerTick", 8, 1, MAX)` 三元组；
    ② 注释里写的默认值同步成 8（防止「代码改了、文档注释还写 32」这种自相矛盾）；
    ③ `ElectricJetpackItem#flightCost()` 仍是从 `Config.electricJetpackFlightCostPerTick` 读的
    （由 ①-e 的 config_fallback_ok 负责，这里只钉默认值与可配置性）。
    """
    code = strip_comments(config_text)
    return ('defineInRange("electricJetpackFlightCostPerTick", 8, 1, Integer.MAX_VALUE);' in code
            and "每 tick 消耗的 FE（默认 8）" in config_text)


def capability_ok(item_text, main_text):
    code = strip_comments(item_text)
    body = method_body(code, r"public static void registerCapabilities\(")
    if body is None:
        return False
    if "Capabilities.EnergyStorage.ITEM" not in body:
        return False
    if "RS_Create_Compat.ELECTRIC_JETPACK.get()" not in body:
        return False
    if "new cretae.cookiewyq.rs_create_compat.support.JetpackEnergyStorage(stack)" not in body:
        return False
    main_code = strip_comments(main_text)
    # 只注册一次（重复注册在运行期会抛「already registered」）
    return main_code.count("ElectricJetpackItem::registerCapabilities") == 1


def energy_storage_ok(text):
    code = strip_comments(text)
    receive = method_body(code, r"public int receiveEnergy\(final int toReceive, final boolean simulate\)")
    extract = method_body(code, r"public int extractEnergy\(final int toExtract, final boolean simulate\)")
    if receive is None or extract is None:
        return False
    return (
        "implements IEnergyStorage" in code
        # simulate 必须在「真正写值」的那一句上把关（模拟不能写）
        and "if (accepted > 0 && !simulate)" in receive
        and "if (extracted > 0 && !simulate)" in extract
        and "ElectricJetpackItem.setEnergy(stack, stored + accepted)" in receive
        and "ElectricJetpackItem.setEnergy(stack, stored - extracted)" in extract
        and "ElectricJetpackItem.transfer()" in code
        and "ElectricJetpackItem.capacity()" in code
    )


# ======================================================================================
# ② 生效位置：胸甲槽 / Curios back / Curios chest（唯一判定出口）
# ======================================================================================

def equipped_slots_ok(text):
    code = strip_comments(text)
    return (
        'CURIOS_SLOT_BACK = "back"' in code
        and 'CURIOS_SLOT_CHEST = "chest"' in code
        and "player.getItemBySlot(EquipmentSlot.CHEST)" in code
        and "RsccCuriosTerminalSlot.slotStacks(player, CURIOS_SLOT_BACK)" in code
        and "RsccCuriosTerminalSlot.slotStacks(player, CURIOS_SLOT_CHEST)" in code
        and "ElectricJetpackItem.isJetpack(" in code
        and "getInventory()" not in code  # 背包 / 副手 / 主手不算
        and "Offhand" not in code
        and "getMainHandItem" not in code
    )


def curios_via_wrapper_ok(text):
    """Curios 读取必须走仓库既有封装：不自己 forName / 自己找 getStacksHandler。"""
    code = strip_comments(text)
    return ("RsccCuriosTerminalSlot.slotStacks(" in code
            and "Class.forName(" not in code
            and "CuriosApi" not in code
            and "getStacksHandler" not in code
            and "top.theillusivec4" not in code)


def curios_wrapper_has_delegate(wrapper_text):
    code = strip_comments(wrapper_text)
    body = method_body(code, r"public static List<ItemStack> slotStacks\(")
    # 实现里带「空表兜底」也算合格：只要真的把活交给既有的 slots(...)（不是另写一份反射）
    return (body is not None
            and "slots(player, slotId)" in body
            and "Class.forName(" not in body)


# ======================================================================================
# ③ 服务端权威状态机
# ======================================================================================

def tick_body(text):
    return method_body(text, r"private static void tick\(final ServerPlayer player\)")


def grant_ok(text):
    """授予三条件：装备 + 电量 >= 一次成本（+ 非创造/旁观由前面的 privileged 分支保证）。"""
    body = tick_body(text)
    if body is None:
        return False
    grant = re.search(r"else if \(equipped && energyBefore >= cost\)\s*\{", body)
    if grant is None:
        return False
    segment = body[grant.end():]
    return ("abilities.mayfly = true;" in segment
            and "player.onUpdateAbilities();" in segment
            and "persistent.putBoolean(TAG_GRANTED, true);" in segment)


def privileged_ok(text):
    """创造 / 旁观整段跳过：`privileged` 分支里不许写 mayfly / flying，只清自己的标记。"""
    body = tick_body(text)
    if body is None:
        return False
    branch = re.search(r"if \(privileged\)\s*\{", body)
    if branch is None:
        return False
    segment = body[branch.end():]
    stop = segment.find("} else if")
    if stop < 0:
        return False
    segment = segment[:stop]
    if "revoke(" in segment or "mayfly" in segment or "flying" in segment:
        return False
    return "persistent.remove(TAG_GRANTED);" in segment


def privileged_flag_ok(text):
    code = strip_comments(text)
    return ("final boolean privileged = player.isCreative() || player.isSpectator();" in code
            and "if (player.isCreative() || player.isSpectator()) {" in code)


def grant_marker_guard_ok(text):
    """没背 / 没电时的收回必须只收「我们发的」：由 TAG_GRANTED 持久标记把关。"""
    body = tick_body(text)
    if body is None:
        return False
    return re.search(r"if \(persistent\.getBoolean\(TAG_GRANTED\)\)\s*\{\s*revoke\(player\);", body) is not None


def revoke_ok(text):
    """收回三件事：清标记 + mayfly=false + flying=false + 同步（缺 flying 会「耗尽还能飘一瞬」）。"""
    body = method_body(text, r"private static void revoke\(final ServerPlayer player\)")
    if body is None:
        return False
    return ("player.getPersistentData().remove(TAG_GRANTED);" in body
            and "abilities.mayfly = false;" in body
            and "abilities.flying = false;" in body
            and "player.onUpdateAbilities();" in body)


def revoke_paths_ok(text):
    """收回路径 5 种情形 + 登录幂等兜底，每个事件都必须真的调用收回入口。"""
    code = strip_comments(text)
    events = [
        ("onChangedDimension", r"PlayerEvent\.PlayerChangedDimensionEvent"),
        ("onLoggedOut", r"PlayerEvent\.PlayerLoggedOutEvent"),
        ("onDeath", r"LivingDeathEvent"),
        ("onLoggedIn", r"PlayerEvent\.PlayerLoggedInEvent"),
        ("onRespawn", r"PlayerEvent\.PlayerRespawnEvent"),
    ]
    for method, event_type in events:
        body = method_body(code, r"public static void %s\(" % method)
        if body is None:
            return False
        if re.search(event_type, body) is None:
            return False
        if "revokeUnlessPrivileged(player);" not in body:
            return False
    return True


def server_player_only_ok(text):
    body = method_body(text, r"public static void onPlayerTick\(final PlayerTickEvent\.Post event\)")
    if body is None:
        return False
    code = strip_comments(text)
    return ("event.getEntity() instanceof ServerPlayer player" in body
            and "tick(player);" in body
            and "LocalPlayer" not in code)


CONSUME_PATTERN = re.compile(
    r"if \(!privileged && equipped && player\.getAbilities\(\)\.flying\s+"
    r"&& ElectricJetpackItem\.getEnergy\(pack\) >= cost\) \{")


def consume_only_flying_ok(text):
    """耗电只在 flying 时发生（上升/悬停/下降同一口径：只看 flying 标志）。"""
    body = tick_body(text)
    if body is None:
        return False
    if "ElectricJetpackItem.consume(pack, cost)" not in body:
        return False
    return CONSUME_PATTERN.search(body) is not None


def sample_before_consume_ok(text):
    """先采样后扣电：charging 判据用「本 tick 开始时的电量」，不能被自己的耗电抵消。"""
    body = tick_body(text)
    if body is None:
        return False
    sample = body.find("final int energyBefore = equipped ? ElectricJetpackItem.getEnergy(pack) : 0;")
    charging = body.find("final boolean charging = equipped && state.pack == pack")
    consume = body.find("ElectricJetpackItem.consume(pack, cost)")
    return 0 <= sample < consume and 0 <= charging < consume and "energyBefore > state.previousEnergy" in body


def connection_guard_ok(text):
    """假玩家 / 未登录玩家没有 connection：tick 必须整段跳过（否则发包处 NPE）。"""
    body = tick_body(text)
    if body is None:
        return False
    return ("if (player.connection == null) {" in body
            and re.search(r"if \(player\.connection == null\) \{\s*return;", body) is not None)


def state_cleanup_ok(text):
    code = strip_comments(text)
    return (code.count("STATES.remove(player.getUUID());") >= 2
            and "STATES.computeIfAbsent(player.getUUID()" in code
            and "private static final Map<UUID, PlayerState> STATES = new HashMap<>();" in code)


# ======================================================================================
# ④ 充电判据：能量变大（不是距离）
# ======================================================================================

def charging_criterion_ok(text):
    body = tick_body(text)
    if body is None:
        return False
    return ("final boolean charging = equipped && state.pack == pack && energyBefore > state.previousEnergy;"
            in body)


def charging_honest_comment_ok(texts):
    """判据必须诚实写在注释 / 文档里：「不是距离判定 / 真的发生了能量转移」。"""
    for text in texts:
        if "不是距离" in text and "能量" in text:
            return True
    return False


# ======================================================================================
# ⑤ 低电 / 耗尽提示：跨阈值一次 + 600 tick 节流
# ======================================================================================

def notice_throttle_ok(text):
    code = strip_comments(text)
    consts = int_consts(code)
    if consts.get("NOTICE_THROTTLE_TICKS") != 600:
        return False
    body = tick_body(text)
    if body is None:
        return False
    if body.find("state.lowPowerNotified") < 0:
        return False
    throttled = "gameTime - state.lastLowPowerNoticeTick >= NOTICE_THROTTLE_TICKS" in body
    # 第 109 轮起低电 title 带副标题（剩余分钟），因此按前缀匹配而不是匹配 `LANG_LOW_POWER);`
    sent_once = ("state.lowPowerNotified = true;" in body
                 and re.search(r"sendTitle\(player, LANG_LOW_POWER\b", body) is not None)
    reset = re.search(r"\} else \{\s*state\.lowPowerNotified = false;", body)
    return throttled and sent_once and reset is not None


def low_notice_only_flying_ok(text):
    """第 105 轮口径：低电 title 只在 `abilities.flying == true` 时提示（没在飞就不许弹）。

    判据（源码级）：`tick()` 里 `if (energyAfter < threshold)` 分支内，
    `player.getAbilities().flying` 必须出现，且**先于**低电 title 的发送
    —— 即这一条 title 完全被「在飞」条件包住，不存在别的无条件发送点。
    （第 109 轮：阈值改成「剩余可飞分钟 → FE」，比较是**严格小于**；发送改成带副标题的三参形式。）
    """
    body = tick_body(text)
    if body is None:
        return False
    start = body.find("if (energyAfter < threshold) {")
    if start < 0:
        return False
    end = body.find("} else {", start)
    if end < 0:
        return False
    low = body[start:end]
    flying_at = low.find("player.getAbilities().flying")
    send_at = low.find("sendTitle(player, LANG_LOW_POWER")
    return flying_at >= 0 and send_at >= 0 and flying_at < send_at


def simulate_low_notice_flying(ticks, flying_from, flying_to, throttle=600):
    """低电提示行为模型（第 105 轮口径）：只有 flying 的 tick 才可能发送。

    `flying_from`/`flying_to` 是 [from, to) 的飞行窗口（传 from >= to 表示整段都不飞）。
    返回整段窗口里发送的 title 条数。语义与实现一致：
      * 不飞 ⇒ 0 条（连 `lowPowerNotified` 都不置位）；
      * 飞行中跨阈值 1 条，持续低电每 `throttle` tick 至多 1 条；
      * 落地不会清掉「已提示」状态（同一段飞行里不会因为落地再补一条）。
    """
    sent = 0
    last = -10 ** 9
    notified = False
    for tick in range(ticks):
        if not (flying_from <= tick < flying_to):
            continue
        if not notified or tick - last >= throttle:
            notified = True
            last = tick
            sent += 1
    return sent


def simulate_low_notice_ungated(ticks, throttle=600):
    """反例模型：不看是否在飞（旧实现口径）⇒ 2000 tick 发 4 条。"""
    return simulate_low_notice_flying(ticks, -1, ticks, throttle)


def title_packets_ok(text):
    code = strip_comments(text)
    return ("ClientboundSetTitleTextPacket(Component.translatable(langKey))" in code
            and "ClientboundSetTitlesAnimationPacket" in code
            and "LANG_LOW_POWER = \"item.rs_create_compat.electric_jetpack.low_power.title\"" in code
            and "LANG_DEPLETED = \"item.rs_create_compat.electric_jetpack.depleted.title\"" in code)


def simulate_notice(ticks, throttle=600):
    """低电提示的行为模型：跨阈值一次 + 持续低电每 throttle tick 至多一条。"""
    sent = 0
    last = -10 ** 9
    notified = False
    for tick in range(ticks):
        if not notified:
            notified = True
            last = tick
            sent += 1
        elif tick - last >= throttle:
            last = tick
            sent += 1
    return sent


def simulate_notice_without_throttle(ticks):
    """反例模型：没有节流（每 tick 一条）。"""
    return ticks


# ======================================================================================
# ⑥ HUD：只读服务端快照 + 自绘 + 两个标记分开 + 过期隐藏 + 客户端不写世界
# ======================================================================================

def hud_reads_snapshot_only(hud_text):
    code = strip_comments(hud_text)
    if "JetpackHudState.snapshot()" not in code:
        return False
    if not re.search(r"if \(!snapshot\.equipped\(\)\)\s*\{\s*return;", code):
        return False
    banned = ("ElectricJetpackItem.getEnergy", "getCapabilities", "getBlockEntity", "Capabilities.",
              "getEnergyStored", ".getItemHandler", "level.getBlockState", "stack.get(")
    return not any(token in code for token in banned)


def hud_self_drawn_ok(hud_text):
    code = strip_comments(hud_text)
    return ("graphics.fill(" in code
            and "graphics.drawString(font, energyText" in code
            and "blit(" not in code
            and "renderTexture" not in code
            and "TextureManager" not in code)


def hud_two_flags_ok(hud_text):
    code = strip_comments(hud_text)
    return ("snapshot.charging()" in code
            and "snapshot.inRange()" in code
            and 'LANG_HUD_CHARGING = "item.rs_create_compat.electric_jetpack.hud.charging"' in code
            and 'LANG_HUD_IN_RANGE = "item.rs_create_compat.electric_jetpack.hud.in_range"' in code
            and "snapshot.charging() ? LANG_HUD_CHARGING" in code)


def hud_stale_hidden_ok(state_text, hud_text):
    code = strip_comments(state_text)
    hud = strip_comments(hud_text)
    return ("public static final long STALE_MS = 5000L;" in code
            and "System.currentTimeMillis() - snapshot.receivedAtMs() > STALE_MS" in code
            and "return Snapshot.EMPTY;" in code
            and "JetpackHudState.snapshot()" in hud)


def client_writes_nothing_ok(texts):
    """客户端（HUD / 镜像）不写世界、不写物品、不发 C2S、不碰 abilities。"""
    banned = ("setEnergy(", "consume(", "removeItem(", "addFreshEntity(", "setBlock(", "setChanged(",
              "mayfly", "Abilities", "sendToServer", "PacketDistributor.send")
    for text in texts:
        code = strip_comments(text)
        for token in banned:
            if token in code:
                return False
    return True


def hud_auto_registered_ok(hud_text, client_init_text):
    """HUD 自己在 client 包用 @EventBusSubscriber 注册（不依赖 ClientInit，写域不越界）。"""
    code = strip_comments(hud_text)
    return ("@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)" in code
            and "public static void onRenderGui(final RenderGuiEvent.Post event)" in code
            and "Jetpack" not in strip_comments(client_init_text))


# ======================================================================================
# ⑦ 范围几何：复刻充电器的玩家包围盒
# ======================================================================================

def range_geometry_ok(text, charger_text):
    code = strip_comments(text)
    charger = strip_comments(charger_text)
    return (
        "charger.getRangeX() / 2" in code
        and "charger.getRangeY() / 2" in code
        and "charger.getRangeZ() / 2" in code
        and "pos.getX() + halfX + 1" in code
        and "final AABB playerBox = player.getBoundingBox();" in code
        and "playerBox.intersects(rangeBox(charger))" in code
        and "charger.hasInfiniteRange()" in code
        and "player.isSpectator()" in code
        and "getChunkNow(" in code            # 只查已加载区块，绝不主动加载
        and "getChunk(" not in code
        # 与充电器本体的一致性锚点：同一套 half/ +1 包围盒
        and "worldPosition.getX() - halfX" in charger
        and "rangeX / 2" in charger
        and "rangeX = " in charger
    )


def range_server_side_ok(text):
    """服务端算 + 只查已加载区块（getChunkNow），绝不 getChunk( 主动加载。"""
    code = strip_comments(text)
    return ("public static boolean inRange(final ServerPlayer player)" in code
            and "instanceof ServerLevel level" in code
            and "getChunkNow(" in code
            and "getChunk(" not in code)


# ======================================================================================
# ⑧ 网络包：注册 + 客户端安全
# ======================================================================================

RECORD_PATTERN = re.compile(
    r"public record JetpackStatusPacket\(boolean equipped, boolean charging, boolean inRange,\s+"
    r"int energy, int capacity\) implements CustomPacketPayload")


def packet_ok(text):
    code = strip_comments(text)
    return (
        RECORD_PATTERN.search(code) is not None
        and "public static final Type<JetpackStatusPacket> TYPE" in code
        and 'ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "jetpack_status")' in code
        and "public static final StreamCodec<RegistryFriendlyByteBuf, JetpackStatusPacket> STREAM_CODEC"
        in code
        and code.count("ByteBufCodecs.BOOL") >= 3
        and code.count("ByteBufCodecs.VAR_INT") >= 2
        and "public static void handle(final JetpackStatusPacket packet, final IPayloadContext ctx)" in code
        and "JetpackHudState.accept(" in code
        and "net.minecraft.client" not in code
        and "Minecraft" not in code
    )


def registration_ok(main_text):
    code = strip_comments(main_text)
    body = method_body(code, r"private static void registerPayloads\(")
    if body is None:
        return False
    for match in re.finditer(r"registrar\.(playToServer|playToClient)\s*\(", body):
        rest = body[match.end():]
        nxt = re.search(r"registrar\.(?:playToServer|playToClient)\s*\(", rest)
        segment = rest[:nxt.start()] if nxt else rest
        if "JetpackStatusPacket" in segment:
            return (match.group(1) == "playToClient"
                    and "JetpackStatusPacket.TYPE" in segment
                    and "JetpackStatusPacket.STREAM_CODEC" in segment
                    and "JetpackStatusPacket::handle" in segment)
    return False


def load_round47():
    path = os.path.join(TOOLS, "selfcheck_round47_dedicated_server_safety.py")
    spec = importlib.util.spec_from_file_location("rscc_round47", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def network_client_safety_ok(round47, sources, rels):
    """复用 round47 判据：network/** 与 support/** 不引用客户端类型。"""
    bad = []
    for rel in rels:
        src = sources.get(rel)
        if src is None:
            bad.append(rel + " (缺失)")
            continue
        if rel in round47.ALLOWLIST:
            continue
        if src.external_refs or src.client_class_refs:
            bad.append(rel + ": " + src.describe())
    return bad


# ======================================================================================
# ⑨ 语言键
# ======================================================================================

def referenced_lang_keys(texts):
    keys = set()
    for text in texts:
        for match in re.finditer(r'"(item\.rs_create_compat\.electric_jetpack[A-Za-z0-9_.]*)"', text):
            keys.add(match.group(1))
    return keys


def lang_pairs_ok(zh, en, keys):
    """已存在的键必须 zh/en 成对、非空；zh 含中文、en 不含中日韩。"""
    cjk = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")
    for key in keys:
        if key not in zh or key not in en:
            return False
        if not zh[key].strip() or not en[key].strip():
            return False
        if not cjk.search(zh[key]):
            return False
        if cjk.search(en[key]):
            return False
    return True


def lang_keys_in_scope_ok(keys):
    """源码引用的语言键必须都在本轮约定清单内（拼错的键 = 界面上出现裸键名）。"""
    return not [key for key in keys if key not in EXPECTED_LANG_KEYS]


def load_lang(name):
    path = os.path.join(LANG_DIR, name)
    if not os.path.exists(path):
        return {}
    try:
        return json.loads(read(path))
    except Exception:
        return {}


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    texts = {
        "item": read(ITEM),
        "energy": read(ENERGY),
        "equipped": read(EQUIPPED),
        "hud_state": read(HUD_STATE),
        "range": read(RANGE),
        "handler": read(HANDLER),
        "packet": read(PACKET),
        "hud": read(HUD),
        "main": read(MAIN),
        "config": read(CONFIG),
        "curios": read(CURIOS_WRAPPER),
        "charger": read(CHARGER_BE),
        "client_init": read(CLIENT_INIT),
    }
    jetpack_sources = [texts["item"], texts["energy"], texts["equipped"], texts["hud_state"],
                       texts["range"], texts["handler"], texts["packet"], texts["hud"]]

    # ---------------- ① 物品本体 ----------------
    print("=== ① 物品本体：胸甲槽 Equipable / 电量落盘 / 无护甲 / 无耐久 / FE 能力注册 ===")
    check("①-a 1.21.1 口径：extends Item implements Equipable + CHEST + use 调 swapWithEquipmentSlot",
          item_class_ok(texts["item"]))
    counterexample("把 getEquipmentSlot() 改成 HEAD ⇒ 胸甲槽判据判负",
                   item_class_ok(texts["item"].replace("return EquipmentSlot.CHEST;",
                                                       "return EquipmentSlot.HEAD;")) is False)
    check("①-b 电量落在物品组件里（CustomData + TAG_ENERGY，读/写/扣三件套都在）",
          energy_persisted_ok(texts["item"]))
    counterexample("把电量改成 Static 字段（不落盘）⇒ 落盘判据判负",
                   energy_persisted_ok(texts["item"].replace(
                       "tag.putInt(TAG_ENERGY, clamped)", "// tag.putInt(TAG_ENERGY, clamped)")) is False)
    check("①-c 无护甲值（所有背包源码里没有 Attributes.ARMOR / 属性修饰符）",
          no_armor_ok(jetpack_sources))
    counterexample("给物品加 Attributes.ARMOR ⇒ 无护甲判据判负",
                   no_armor_ok(jetpack_sources + [
                       "public static final Holder<Attribute> X = Attributes.ARMOR;"]) is False)
    check("①-d 无耐久（不读 MAX_DAMAGE / 不写 DAMAGE）", no_durability_ok(jetpack_sources))
    counterexample("给物品加 DataComponents.MAX_DAMAGE ⇒ 无耐久判据判负",
                   no_durability_ok(jetpack_sources + ["x.component(DataComponents.MAX_DAMAGE, 1)"]) is False)
    check("①-e 配置回退：容量/速率/耗电/低电四值都有「配置未加载」安全回退，耗电夹到 ≥1",
          config_fallback_ok(texts["item"]))
    counterexample("去掉 flightCost 的 Math.max(1, …)（允许 0 成本 = 免费飞行）⇒ 判据判负",
                   config_fallback_ok(texts["item"].replace("return Math.max(1, cost);",
                                                            "return cost;")) is False)
    check("①-e2 第 108 轮：耗电默认 8 FE/tick 且可配置（Config 默认值与注释同步）",
          jetpack_cost_config_ok(texts["config"]))
    counterexample("把 Config 默认值改回 32 ⇒ 耗电配置判据判负",
                   jetpack_cost_config_ok(texts["config"].replace(
                       'defineInRange("electricJetpackFlightCostPerTick", 8,',
                       'defineInRange("electricJetpackFlightCostPerTick", 32,')) is False)
    check("①-f FE 能力：registerItem(Capabilities.EnergyStorage.ITEM) + 主类只挂一次监听",
          capability_ok(texts["item"], texts["main"]))
    counterexample("把能力注册换成 Capabilities.ItemHandler.ITEM ⇒ 能力判据判负",
                   capability_ok(texts["item"].replace("Capabilities.EnergyStorage.ITEM",
                                                       "Capabilities.ItemHandler.ITEM"),
                                 texts["main"]) is False)
    check("①-g 能量能力语义：receive/extract 都夹速率、simulate 不写、读写物品组件本身",
          energy_storage_ok(texts["energy"]))
    counterexample("receiveEnergy 忽略 simulate（模拟也写）⇒ 判据判负",
                   energy_storage_ok(texts["energy"].replace("if (accepted > 0 && !simulate)",
                                                             "if (accepted > 0)")) is False)
    check("①-h 创造模式右键穿背包是「移动」不是「复制」（原版创造模式会 copy 进胸甲槽 ⇒ 真复制）",
          creative_equip_no_dupe_ok(texts["item"]))
    counterexample("创造分支删掉「旧件写回手槽」（手上那份不清 ⇒ 变成两份）⇒ 判据判负",
                   creative_equip_no_dupe_ok(texts["item"].replace(
                       "player.setItemInHand(hand, worn);", "")) is False)
    counterexample("创造模式仍走原版 swap（把 isCreative 改成 isSpectator ⇒ 创造模式落回复制路径）⇒ 判据判负",
                   creative_equip_no_dupe_ok(texts["item"].replace(
                       "player.isCreative()", "player.isSpectator()")) is False)
    check("①-i 行为模型：创造模式穿一次后身上只剩 1 件（原版复制语义会剩 2 件）",
          len(simulate_creative_equip(True)) == 1 and len(simulate_creative_equip(False)) == 2)
    counterexample("模型退回原版创造模式复制语义（穿一次后剩 2 件）⇒ 模型判据判负",
                   (len(simulate_creative_equip(False)) == 1) is False)

    # ---------------- ② 生效位置 ----------------
    print("=== ② 生效位置只有三处（胸甲槽 / Curios back / Curios chest），Curios 走既有封装 ===")
    check("②-a 三处候选都在、且不含背包/副手/主手扫描", equipped_slots_ok(texts["equipped"]))
    counterexample("把背包 36 格也当生效位置 ⇒ 位置判据判负",
                   equipped_slots_ok(texts["equipped"].replace(
                       "addIfJetpack(found, player.getItemBySlot(EquipmentSlot.CHEST));",
                       "for (int i = 0; i < 36; i++) addIfJetpack(found, player.getInventory().getItem(i));")) is False)
    check("②-b Curios 读取走 RsccCuriosTerminalSlot.slotStacks（不自己 forName / 不自己反射）",
          curios_via_wrapper_ok(texts["equipped"]))
    counterexample("自己反射 CuriosApi ⇒ 封装判据判负",
                   curios_via_wrapper_ok(texts["equipped"] + 'Class.forName("top.theillusivec4.curios.api.CuriosApi");') is False)
    check("②-c 封装里确实有 slotStacks(LivingEntity,String) 公开委托（本轮的跨文件契约）",
          curios_wrapper_has_delegate(texts["curios"]))
    counterexample("把委托改成返回空表 ⇒ 跨文件契约判据判负",
                   curios_wrapper_has_delegate(texts["curios"].replace(
                       "slots(player, slotId)", "java.util.List.of()")) is False)

    # ---------------- ③ 服务端权威状态机 ----------------
    print("=== ③ 服务端权威飞行状态机：授予三条件 / 创造旁观不覆盖 / 收回 5 路径 ===")
    check("③-a PlayerTickEvent.Post 只处理 ServerPlayer（客户端绝不写 abilities）",
          server_player_only_ok(texts["handler"]))
    counterexample("去掉 ServerPlayer 判定 ⇒ 只服务端判据判负",
                   server_player_only_ok(texts["handler"].replace(
                       "if (event.getEntity() instanceof ServerPlayer player) {",
                       "if (event.getEntity() instanceof net.minecraft.world.entity.player.Player player) {")) is False)
    check("③-b 授予三条件：equipped && energyBefore >= cost，且真的写 mayfly + 同步 + 打标记",
          grant_ok(texts["handler"]))
    counterexample("去掉「电量足够」这一条件（谁都能飞）⇒ 授予判据判负",
                   grant_ok(texts["handler"].replace("else if (equipped && energyBefore >= cost)",
                                                     "else if (equipped)")) is False)
    check("③-c 创造 / 旁观整段跳过：privileged 分支里不碰 mayfly/flying，只清自己的标记",
          privileged_ok(texts["handler"]) and privileged_flag_ok(texts["handler"]))
    counterexample("在 privileged 分支里 revoke ⇒ 创造/旁观判据判负",
                   privileged_ok(texts["handler"].replace(
                       "persistent.remove(TAG_GRANTED);\n        } else if",
                       "persistent.remove(TAG_GRANTED);\n            revoke(player);\n        } else if")) is False)
    check("③-d 收回只收「我们发的」：由 TAG_GRANTED 持久标记把关", grant_marker_guard_ok(texts["handler"]))
    counterexample("去掉标记把关（见谁收谁，会误杀别的模组的 mayfly）⇒ 判据判负",
                   grant_marker_guard_ok(texts["handler"].replace(
                       "if (persistent.getBoolean(TAG_GRANTED)) {", "if (true) {")) is False)
    check("③-e 收回动作完整：清标记 + mayfly=false + flying=false + onUpdateAbilities",
          revoke_ok(texts["handler"]))
    counterexample("收回时不撤 flying（耗尽还能飘一瞬）⇒ 判据判负",
                   revoke_ok(texts["handler"].replace("abilities.flying = false;\n            player.onUpdateAbilities();",
                                                      "player.onUpdateAbilities();")) is False)
    check("③-f 收回路径 5 种情形全覆盖（换维度/下线/死亡/登录/复活 都调收回入口）",
          revoke_paths_ok(texts["handler"]))
    counterexample("删掉登录兜底 ⇒ 路径判据判负",
                   revoke_paths_ok(texts["handler"].replace("public static void onLoggedIn(",
                                                            "public static void onLoggedInDisabled(")) is False)
    check("③-g 状态表随下线 / 死亡移除（不会按玩家无限增长）", state_cleanup_ok(texts["handler"]))
    check("③-h 假玩家 / 未登录玩家（connection == null）整段跳过，不在发包处 NPE",
          connection_guard_ok(texts["handler"]))
    counterexample("去掉 connection 空判据（FakePlayer 进 tick 会 NPE）=> 判据判负",
                   connection_guard_ok(re.sub(
                       r"if \(player\.connection == null\) \{\s*return;\s*\}",
                       "if (false) { return; }", texts["handler"])) is False)
    counterexample("去掉下线时的移除 ⇒ 判据判负",
                   state_cleanup_ok(texts["handler"].replace(
                       "revokeUnlessPrivileged(player);\n            STATES.remove(player.getUUID());",
                       "revokeUnlessPrivileged(player);")) is False)

    # ---------------- ④ 耗电口径 + 充电判据 ----------------
    print("=== ④ 耗电只在 flying / 先采样后扣电 / charging 判据 = 能量变大（不是距离） ===")
    check("④-a 只在 abilities.flying 时扣电，且 !privileged && equipped", consume_only_flying_ok(texts["handler"]))
    counterexample("去掉 flying 条件（站着也扣电）⇒ 口径判据判负",
                   consume_only_flying_ok(CONSUME_PATTERN.sub(
                       "if (!privileged && equipped) {", texts["handler"])) is False)
    check("④-b 先采样 energyBefore、再扣电、charging 用 sample 比较（自身耗电不会抵消判据）",
          sample_before_consume_ok(texts["handler"]))
    counterexample("把采样挪到扣电之后 ⇒ 判据判负",
                   sample_before_consume_ok(texts["handler"].replace(
                       "final int energyBefore = equipped ? ElectricJetpackItem.getEnergy(pack) : 0;",
                       "final int energyBefore = 0;")) is False)
    check("④-c charging = 同一件背包 && 本 tick 电量变大（不是距离/不是「在范围」）",
          charging_criterion_ok(texts["handler"]))
    counterexample("把 charging 改成「装备上就算在充」⇒ 判据判负",
                   charging_criterion_ok(texts["handler"].replace(
                       "energyBefore > state.previousEnergy", "equipped")) is False)
    check("④-d 判据的诚实说明写进了文档注释（「不是距离」= 真的能量转移）",
          charging_honest_comment_ok([texts["handler"], texts["packet"], texts["hud_state"]]))

    # ---------------- ⑤ 提示 ----------------
    print("=== ⑤ 低电 / 耗尽提示：跨阈值一次 + 600 tick 节流 + title 包 ===")
    check("⑤-a 跨阈值只发一次 + 持续低电按 NOTICE_THROTTLE_TICKS=600 节流 + 离开低电重置",
          notice_throttle_ok(texts["handler"]))
    counterexample("去掉节流条件（每 tick 一条 title）⇒ 节流判据判负",
                   notice_throttle_ok(texts["handler"].replace(
                       "|| gameTime - state.lastLowPowerNoticeTick >= NOTICE_THROTTLE_TICKS",
                       "|| true")) is False)
    check("⑤-b 两条提示都走原版 title 包 + 语言键常量", title_packets_ok(texts["handler"]))
    model_ok = simulate_notice(2000) == 4
    check("⑤-c 行为模型：连续低电 2000 tick 只发 4 条（1 次跨阈值 + 每 600 tick 至多 1 条）", model_ok)
    counterexample("模型换成无节流（3000 tick 发 3000 条）⇒ 模型判据判负",
                   (simulate_notice_without_throttle(3000) == 4) is False)
    check("⑤-d 低电 title 只在 abilities.flying == true 时发送（没在飞一声不吭，第 105 轮口径）",
          low_notice_only_flying_ok(texts["handler"]))
    counterexample("删掉「在飞」条件（低电站在地上也弹 title）⇒ 判据判负",
                   low_notice_only_flying_ok(texts["handler"].replace(
                       "player.getAbilities().flying", "true")) is False)
    check("⑤-e 行为模型：整段不飞 ⇒ 0 条；连续飞 2000 tick ⇒ 4 条；飞 300 tick ⇒ 1 条",
          simulate_low_notice_flying(2000, 2000, 2000) == 0
          and simulate_low_notice_flying(2000, 0, 2000) == 4
          and simulate_low_notice_flying(2000, 0, 300) == 1)
    counterexample("模型退回「不看是否在飞」（整段不飞也发 4 条）⇒ 模型判据判负",
                   (simulate_low_notice_ungated(2000) == 0) is False)

    # ---------------- ⑥ HUD ----------------
    print("=== ⑥ HUD：只读服务端快照 / 自绘 / 两个标记分开 / 过期隐藏 / 客户端不写世界 ===")
    check("⑥-a HUD 只读 JetpackHudState 快照（不读物品组件、不查方块实体、不推算）",
          hud_reads_snapshot_only(texts["hud"]))
    counterexample("让 HUD 自己读物品电量 ⇒ 权威判据判负",
                   hud_reads_snapshot_only(texts["hud"].replace(
                       "JetpackHudState.snapshot()", "ElectrictJetpackItem.getEnergy(null)")) is False)
    check("⑥-b HUD 纯代码自绘（fill + drawString，不需要任何贴图）", hud_self_drawn_ok(texts["hud"]))
    counterexample("让 HUD 去 blit 贴图 ⇒ 自绘判据判负",
                   hud_self_drawn_ok(texts["hud"].replace("graphics.fill(", "graphics.blit(")) is False)
    check("⑥-c charging 与 inRange 两个标记分开显示（两个语言键 + 三级文案）",
          hud_two_flags_ok(texts["hud"]))
    counterexample("只保留「充电中」丢掉「范围内」⇒ 两标记判据判负",
                   hud_two_flags_ok(texts["hud"].replace(
                       "snapshot.inRange() ? LANG_HUD_IN_RANGE : null", "null")) is False)
    check("⑥-d 快照 5 秒过期即整条隐藏（不留冻结的假面板）",
          hud_stale_hidden_ok(texts["hud_state"], texts["hud"]))
    counterexample("去掉过期判定 ⇒ 判据判负",
                   hud_stale_hidden_ok(texts["hud_state"].replace(
                       "if (System.currentTimeMillis() - snapshot.receivedAtMs() > STALE_MS) {",
                       "if (false) {"), texts["hud"]) is False)
    check("⑥-e 客户端（HUD / 镜像）不写世界、不写物品、不发 C2S、不碰 abilities",
          client_writes_nothing_ok([texts["hud"], texts["hud_state"]]))
    counterexample("客户端里写物品电量 ⇒ 判据判负",
                   client_writes_nothing_ok([texts["hud"] + "ElectricJetpackItem.setEnergy(stack, 1);"]) is False)
    check("⑥-f HUD 自己在 client 包 @EventBusSubscriber 注册（ClientInit 零改动）",
          hud_auto_registered_ok(texts["hud"], texts["client_init"]))
    counterexample("把 HUD 注册塞回 ClientInit => 判据判负",
                   hud_auto_registered_ok(
                       texts["hud"],
                       texts["client_init"] + " private static final Object J = JetpackHudOverlay.class;")
                   is False)

    # ---------------- ⑦ 范围几何 ----------------
    print("=== ⑦ 范围几何：复刻充电器玩家包围盒 / 只查已加载区块 / 服务端算 ===")
    check("⑦-a 包围盒与充电器逐字一致（range/2、+1、碰撞箱相交）+ 无限范围 + 旁观排除",
          range_geometry_ok(texts["range"], texts["charger"]))
    counterexample("把 +1 去掉（口径与充电器不一致）⇒ 几何判据判负",
                   range_geometry_ok(texts["range"].replace("pos.getX() + halfX + 1",
                                                            "pos.getX() + halfX"), texts["charger"]) is False)
    check("⑦-b 只在服务端算（ServerPlayer + ServerLevel），且只查已加载区块不主动加载",
          range_server_side_ok(texts["range"]) and "getChunkNow(" in strip_comments(texts["range"]))
    counterexample("改用 getChunk(（会加载区块）⇒ 判据判负",
                   range_server_side_ok(texts["range"].replace("getChunkNow(", "getChunk(")) is False)

    # ---------------- ⑧ 网络包 ----------------
    print("=== ⑧ 网络包：字段/编解码/注册 + network·support 不引用 client ===")
    check("⑧-a JetpackStatusPacket：5 字段 + TYPE(jetpack_status) + STREAM_CODEC(3 bool + 2 varint)",
          packet_ok(texts["packet"]))
    counterexample("把处理体改成直接碰客户端类型（getEnergy 在包里算）⇒ 载荷判据判负",
                   packet_ok(texts["packet"].replace("JetpackHudState.accept(",
                                                     "net.minecraft.client.Minecraft.getInstance(); JetpackHudState.accept(")) is False)
    check("⑧-b 已在 registerPayloads 里以 playToClient 注册（TYPE + STREAM_CODEC + ::handle）",
          registration_ok(texts["main"]))
    counterexample("把注册方向改成 playToServer ⇒ 注册判据判负",
                   registration_ok(texts["main"].replace(
                       "cretae.cookiewyq.rs_create_compat.network.JetpackStatusPacket.TYPE,",
                       "cretae.cookiewyq.rs_create_compat.network.JetpackStatusPacket.TYPE,")
                       .replace("registrar.playToClient(\n            cretae.cookiewyq.rs_create_compat"
                                ".network.JetpackStatusPacket",
                                "registrar.playToServer(\n            cretae.cookiewyq.rs_create_compat"
                                ".network.JetpackStatusPacket")) is False)
    round47 = load_round47()
    sources = round47.load_sources()
    rels = [PKG_REL + "/network/JetpackStatusPacket.java",
            PKG_REL + "/support/JetpackEnergyStorage.java",
            PKG_REL + "/support/JetpackEquipped.java",
            PKG_REL + "/support/JetpackHudState.java",
            PKG_REL + "/support/JetpackChargerRange.java",
            PKG_REL + "/support/JetpackFlightHandler.java",
            PKG_REL + "/item/ElectricJetpackItem.java"]
    bad = network_client_safety_ok(round47, sources, rels)
    check("⑧-c 新增的 network/support 文件都不引用客户端类型（复用 round47 判据）", not bad)
    if bad:
        for item in bad:
            print("       " + item)
    counterexample("在 support 里内联引用 client 类 ⇒ round47 判据判负", _round47_would_flag(round47) is True)

    # ---------------- ⑨ 不越界 + 语言键 ----------------
    print("=== ⑨ 不越界：无护甲 / 无耐久 / 语言键 ===")
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")
    keys = referenced_lang_keys([texts["item"], texts["hud"], texts["handler"]])
    unknown = sorted(key for key in keys if key not in EXPECTED_LANG_KEYS)
    check("⑨-a 源码引用的语言键都在本轮约定清单内（没有拼错/临时键）", lang_keys_in_scope_ok(keys))
    if unknown:
        for key in unknown:
            print("       未在清单里的键: " + key)
    counterexample("引用一个清单外的键 ⇒ 键清单判据判负",
                   lang_keys_in_scope_ok(keys | {"item.rs_create_compat.electric_jetpack.typo"}) is False)
    present = [key for key in EXPECTED_LANG_KEYS if key in zh or key in en]
    missing = [key for key in EXPECTED_LANG_KEYS if key not in zh and key not in en]
    check("⑨-b 已落地的语言键 zh/en 成对、非空、语种正确", lang_pairs_ok(zh, en, present))
    sample_key = present[0] if present else EXPECTED_LANG_KEYS[0]
    sample_zh = dict(zh) if present else {sample_key: "电量：%s / %s FE"}
    sample_en = dict(en) if present else {sample_key: "Energy: %s / %s FE"}
    sample_keys = [sample_key]
    counterexample("把 en 的一条改成中文 ⇒ 语言成对判据判负",
                   lang_pairs_ok(sample_zh, dict(sample_en, **{sample_key: "电量"}), sample_keys) is False)
    check("⑨-b′ 语言成对判据本身自证（合成一份合法的 zh/en 必须判正）",
          lang_pairs_ok(sample_zh, sample_en, sample_keys))
    if missing:
        warn("语言键尚未落地 %d 条（Lead/资源侧负责，本脚本只告警不判负）：%s"
             % (len(missing), ", ".join(missing)))
    check("⑨-c 背包源码里没有任何 Attributes.ARMOR（用户硬性要求「不提供护甲值」）",
          no_armor_ok(jetpack_sources))

    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条），告警 %d 条" % (CHECKS[0], COUNTER[0], len(WARNS)))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


def _round47_would_flag(round47):
    """用 round47 自己的判据试一段「support 里引用客户端类」的假源码，必须被判负。"""
    try:
        source = round47.Source("cretae/cookiewyq/rs_create_compat/support/Fake.java",
                                "package x;\nimport net.minecraft.client.Minecraft;\n"
                                "class Fake { Object o = Minecraft.getInstance(); }")
    except Exception:
        return False
    return bool(source.external_refs or source.client_class_refs)


if __name__ == "__main__":
    raise SystemExit(main())
