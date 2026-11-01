#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 100 轮自检：高级远程多功能终端「绑定网络没电 ⇒ 统一禁用所有模式 Tab」。

<h2>用户实机反馈（逐字）</h2>
<p>「那个远程终端如果说网络中完全没电了——就是绑定的网络它没电了——那么它的样板终端应该也是
不可以使用的，但是目前却是可以使用的状态：它没有变灰、还是可以点击，但是里面的物品不可见。这是
个问题。为了防止出现逻辑上的混乱，所以说它应该也被禁用。然后其他的几个东西就没必要禁用了……
算了你统一禁用吧，你都禁用：如果说网络是没电的，那么终端就算本身是有电的，其他东西也是不可以
使用的。」</p>

<h2>本脚本钉住的契约（源码级锚点 + 纯 Python 行为模型 + 反例自证）</h2>
<ol>
  <li><b>没电全灰</b>：置灰判据只有一条 {@code TerminalPowerState.isLocked()}，<b>不区分模式枚举</b>
      （文件里不存在任何 {@code MODE_*} 分支）⇒ 结构上不可能「只灰样板终端」；</li>
  <li><b>点击提前 return</b>：{@code onTabClicked} 里锁定判断<b>早于</b> {@code setMode / mark / sendSwitch}，
      锁定分支直接 {@code return}（不发 {@code SwitchTerminalModePacket}）；</li>
  <li><b>服务端也拒</b>：{@code SwitchTerminalModePacket#handle} 在写模式 / 重开界面前先判
      {@code TerminalNetworkPower.isPowered}，拒绝时<b>不静默</b>（actionbar 提示 + 补发快照）且不重开界面；</li>
  <li><b>判据只看绑定网络</b>：{@code EnergyNetworkComponent#getStored() > 0}（或服务端关闭耗能配置时
      {@code isEnergyRequired()==false} ⇒ 有电）；<b>不读</b>终端物品自身电量；未绑定 / 未加载 / 未成形
      ⇒ 按没电；</li>
  <li><b>有电时行为完全不变</b>：未知（没收到过快照 / 过期）不锁；有电时只剩 {@code supported} 与
      当前模式高亮两条旧判据；</li>
  <li>信号来源与下发：RS 的网格 / 样板 / 管理器 / 监视器界面<b>没有</b>同步网络能量
      （RS 只给控制器 / 便携网格发 {@code EnergyInfoPacket}）⇒ 走 S2C {@code TerminalPowerPacket}：
      界面打开时立刻一条 + 每 20 tick 一条，关界面 / 下线即停。</li>
</ol>

<h2>反例自证（至少 8 条，必须判负）</h2>
<p>只灰样板终端 / 只做客户端不做服务端 / 用终端自身电量当判据 / 静默拒绝 / 没有提前 return /
无条件锁（有电也灰）/ 把 capacity==0 当没电 / 未绑定算有电 / 未知状态当没电 / 语言键缺一条 /
注册行缺失 —— 这些写法喂给同一批判据必须返回 False。</p>

用法：python tools/selfcheck_round100_terminal_power_gate.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import importlib.util
import io
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
NET = os.path.join(PKG, "network")
SUPPORT = os.path.join(PKG, "support")
CLIENT = os.path.join(PKG, "client")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
TOOLS = os.path.dirname(os.path.abspath(__file__))

OVERLAY = os.path.join(CLIENT, "TerminalModeTabOverlay.java")
STATE = os.path.join(CLIENT, "TerminalPowerState.java")
SINK = os.path.join(CLIENT, "ClientPayloadSink.java")
HOOKS = os.path.join(NET, "ClientPayloadHooks.java")
POWER_PACKET = os.path.join(NET, "TerminalPowerPacket.java")
POWER_REG = os.path.join(NET, "TerminalPowerPayloads.java")
SWITCH_PACKET = os.path.join(NET, "SwitchTerminalModePacket.java")
POWER_SERVER = os.path.join(SUPPORT, "TerminalNetworkPower.java")
TERMINAL_ITEM = os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java")
MAIN = os.path.join(PKG, "RS_Create_Compat.java")

LANG_NO_POWER = "item.rs_create_compat.advanced_remote_terminal.network_no_power"

FAILS = []
CHECKS = [0]
COUNTER = [0]


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
    """反例：ok 必须是 False（判据抓得住错误写法）。"""
    COUNTER[0] += 1
    good = ok is False
    print(("PASS 反例 " if good else "FAIL 反例 ") + name)
    if not good:
        FAILS.append("反例未判负: " + name)
    return good


def section(title):
    print("")
    print("=" * 78)
    print(title)
    print("=" * 78)


def strip_comments(text):
    """剥掉行注释与块注释（字符串字面量保留：判据本身就在代码里）。"""
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


def method_body(code, signature_re):
    """按签名正则找到方法体（花括号配对，跳过字符串/字符字面量里的括号）。"""
    match = re.search(signature_re, code)
    if not match:
        return None
    return brace_block(code, match.start())


def brace_block(code, from_index):
    """从 from_index 之后的第一个 '{' 开始做配对扫描，返回块内文本（失败返回 None）。"""
    start = code.find("{", from_index)
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


# ======================================================================================
# ① 客户端：没电 ⇒ 所有模式 Tab 一律置灰 + 点击提前 return（不区分模式枚举）
# ======================================================================================

def pred_click_early_return(src):
    """点击路径：锁定判断分支必须<b>自己</b>直接 return，且早于 setMode / mark / sendSwitch。"""
    code = strip_comments(src)
    body = method_body(code, r"private\s+static\s+void\s+onTabClicked\s*\(")
    if body is None:
        return False
    lock_at = body.find("if (TerminalPowerState.isLocked())")
    if lock_at < 0:
        return False
    block = brace_block(body, lock_at + len("if (TerminalPowerState.isLocked())") - 1)
    if block is None or "return;" not in block:
        return False
    # 锁定分支里不许有任何写状态 / 发包含义
    if any(word in block for word in
           ("setMode(", "TerminalOpenIntent.mark()", "sendSwitch(")):
        return False
    writes = [body.find(needle) for needle in
              ("AdvancedRemoteTerminalItem.setMode(", "TerminalOpenIntent.mark()", "sendSwitch(")]
    if any(pos < 0 for pos in writes):
        return False
    return all(lock_at < pos for pos in writes)


def pred_lock_not_mode_specific(src):
    """置灰判据只能有一条（isLocked 直接赋值、无 && 追加条件），且文件里不得按模式常量分支。"""
    code = strip_comments(src)
    render = method_body(code, r"protected\s+void\s+renderWidget\s*\(")
    if render is None:
        return False
    mode_const = re.search(
        r"\bMODE_(?:GRID|PATTERNS|MANAGER|MONITOR|SEQUENCE|UNIT_MANAGER)\b", code)
    return ("final boolean locked = TerminalPowerState.isLocked();" in render
            and "locked || !supported" in render
            and mode_const is None
            and "lockedTooltip()" in render)


def pred_lock_semantics(src):
    """locked() 必须是「已知 && 没电」：未知 / 有电都<b>不</b>锁（有电行为不变的前提）。"""
    code = strip_comments(src)
    body = method_body(code, r"public\s+boolean\s+locked\s*\(\s*\)")
    return body is not None and re.search(r"return\s+known\s*&&\s*!powered\s*;", body) is not None



def pred_locked_tooltip(src, power_src):
    """悬停：没电时的 tooltip 必须带「存量 / 容量」并复用服务端提示文本（同一句）。"""
    code = strip_comments(src)
    helper = method_body(code, r"private\s+static\s+Component\s+lockedTooltip\s*\(")
    if helper is None:
        return False
    power_code = strip_comments(power_src)
    return ("TerminalNetworkPower.noPowerText(" in helper
            and "TerminalPowerState.snapshot()" in helper
            and "snapshot.stored()" in helper
            and "snapshot.capacity()" in helper
            and "LANG_NO_POWER" in power_code
            and "Component.translatable(LANG_NO_POWER, stored, capacity)" in power_code)


def pred_reset_only_on_new_screen(src):
    """换界面才清快照（同一界面重复初始化不清）—— 保证缩放不闪、也不拿旧终端的快照锁死新界面。"""
    code = strip_comments(src)
    init = method_body(code, r"public\s+static\s+void\s+onScreenInit\s*\(")
    if init is None:
        return False
    return ("if (!sameScreen)" in init and "TerminalPowerState.reset();" in init)


# ======================================================================================
# ② 客户端状态：未知不锁 / 已过期不锁 / 没电才锁
# ======================================================================================

def pred_state_semantics(src):
    code = strip_comments(src)
    return all(needle in code for needle in (
        "public static final long STALE_MS",
        "public record Snapshot(boolean known, long stored, long capacity, boolean bound,",
        "public boolean locked()",
        "return known && !powered;",
        "public static void reset()",
        "public static boolean isLocked()",
        "return snapshot().locked();",
        "System.currentTimeMillis() - snapshot.receivedAtMs() > STALE_MS",
        "return Snapshot.UNKNOWN;",
    ))


def pred_state_accepts_server_powered(src):
    """客户端只接受服务端的 powered，不自己由 stored/capacity 推算（口径只有服务端知道）。"""
    code = strip_comments(src)
    body = method_body(code, r"public\s+static\s+void\s+accept\s*\(\s*final\s+TerminalPowerPacket")
    if body is None:
        return False
    return "packet.powered()" in body and "capacity > 0" not in body and "stored > 0" not in body


# ======================================================================================
# ③ 服务端：拒绝切换（不改 mode / 不重开界面 / 不静默 / 补发快照）
# ======================================================================================

def pred_server_reject(src):
    code = strip_comments(src)
    body = method_body(code, r"public\s+static\s+void\s+handle\s*\(")
    if body is None:
        return False
    gate_at = body.find("TerminalNetworkPower.isPowered(")
    if gate_at < 0:
        return False
    reject = body[gate_at:body.find("// 已是目标模式则不重开", gate_at)]
    if "return;" not in reject:
        return False
    writes = [body.find(needle) for needle in
              ("AdvancedRemoteTerminalItem.setMode(", "item.openModeScreen(")]
    if any(pos < 0 for pos in writes) or not all(gate_at < pos for pos in writes):
        return False
    # 不静默：可见提示 + 补发快照（客户端立刻置灰）
    return ("player.displayClientMessage(" in reject
            and "TerminalNetworkPower.noPowerText(" in reject
            and "PacketDistributor.sendToPlayer(player, power)" in reject)


# ======================================================================================
# ④ 服务端判据：只看绑定网络（EnergyNetworkComponent），未绑定算没电，不读物品自身电量
# ======================================================================================

def pred_powered_criterion(src):
    code = strip_comments(src)
    body = method_body(code, r"public\s+static\s+TerminalPowerPacket\s+snapshot\s*\(")
    if body is None:
        return False
    # 判据：stored > 0，或服务端不需要能量
    if "energy.getStored()" not in body or "!RefinedStorageApi.INSTANCE.isEnergyRequired()" not in body:
        return False
    if not re.search(r"stored\s*>\s*0L?\s*\|\|", body) and "stored > 0L ||" not in body:
        return False
    # 未绑定 / 网络组件缺失 ⇒ powered=false（早退分支里 bound=false / powered=false）
    return ("Optional.empty" not in body
            and "new TerminalPowerPacket(0L, 0L, false, false)" in body
            and "new TerminalPowerPacket(0L, 0L, true, false)" in body)


def pred_no_item_energy(src):
    """判据类里绝不能出现「终端物品自身电量」的读写（用户：终端本身有电也不行）。"""
    code = strip_comments(src)
    forbidden = ("getEnergyStored", "asItemEnergyStorage", "createEnergyStorage",
                 "EnergyStorage.ITEM", "getBarWidth", "energyStorage")
    return not any(word in code for word in forbidden)


def pred_bound_resolution(terminal_src, power_src):
    """绑定解析只有一套实现（终端物品里的 resolveBoundNetwork），且判据类复用它。"""
    terminal = strip_comments(terminal_src)
    power = strip_comments(power_src)
    body = method_body(terminal, r"public\s+static\s+Optional<[^>]*Network>\s+resolveBoundNetwork\s*\(")
    if body is None:
        return False
    single_impl = terminal.count("getNetworkForItem()") == 1
    return (all(needle in body for needle in (
        "getNetworkLocation()",
        "isLoaded(location.pos())",
        "NetworkItemTargetBlockEntity",
        "Optional.ofNullable(target.getNetworkForItem())",
        "location.dimension()",
    ))
            and single_impl
            and "AdvancedRemoteTerminalItem.resolveBoundNetwork(player, stack)" in power)


# ======================================================================================
# ⑤ 下发：界面打开立刻一条 + 每 20 tick 一条 + 关界面 / 下线停
# ======================================================================================

def pred_heartbeat(src, terminal_src):
    code = strip_comments(src)
    return all(needle in code for needle in (
        "public static final int INTERVAL_TICKS = 20;",
        "WATCHES.put(player.getUUID(), new Watch(slotReference,",
        "send(player, snapshot(player, stack));",
        "gameTime - watch.lastSentTick < INTERVAL_TICKS",
        "player.containerMenu == player.inventoryMenu",
        "public static void onPlayerTick(final PlayerTickEvent.Post event)",
        "event.getEntity() instanceof ServerPlayer player",
        "PlayerLoggedOutEvent",
        "WATCHES.remove(player.getUUID());",
        "instanceof AdvancedRemoteTerminalItem",
        "AdvancedRemoteTerminalItem.getMode(stack) != watch.mode",
    )) and "TerminalNetworkPower.start(player, slotReference);" in strip_comments(terminal_src)


# ======================================================================================
# ⑥ 注册 + 客户端落点 + 专服安全
# ======================================================================================

def pred_packet_shape(packet_src):
    code = strip_comments(packet_src)
    return all(needle in code for needle in (
        "public record TerminalPowerPacket(long stored, long capacity, boolean bound, boolean powered)",
        'ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "terminal_power")',
        "ByteBufCodecs.VAR_LONG, TerminalPowerPacket::stored",
        "ByteBufCodecs.VAR_LONG, TerminalPowerPacket::capacity",
        "ByteBufCodecs.BOOL, TerminalPowerPacket::bound",
        "ByteBufCodecs.BOOL, TerminalPowerPacket::powered",
        "ctx.enqueueWork(() -> ClientPayloadHooks.get().updateTerminalPower(packet));",
    ))


def pred_registered_once(reg_src, packet_src, main_src):
    reg = strip_comments(reg_src)
    packet = strip_comments(packet_src)
    main = strip_comments(main_src)
    registered_reg = ("event.registrar(RS_Create_Compat.MODID).playToClient(" in reg
                      and "TerminalPowerPacket.TYPE" in reg
                      and "TerminalPowerPacket.STREAM_CODEC" in reg
                      and "TerminalPowerPacket::handle" in reg)
    # 主类里不得再注册同一个 type（重复注册同一 payload 会加载期报错）
    registered_main = "TerminalPowerPacket" in main
    return (registered_reg and not registered_main
            and "public static final Type<TerminalPowerPacket> TYPE" in packet)


def pred_sink_wiring(hooks_src, sink_src):
    hooks = strip_comments(hooks_src)
    sink = strip_comments(sink_src)
    return ("default void updateTerminalPower(final TerminalPowerPacket packet)" in hooks
            and "public void updateTerminalPower(final TerminalPowerPacket packet)" in sink
            and "TerminalPowerState.accept(packet);" in sink)


def load_round47():
    path = os.path.join(TOOLS, "selfcheck_round47_dedicated_server_safety.py")
    spec = importlib.util.spec_from_file_location("rscc_round47", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def network_client_safety_ok(round47, sources):
    bad = []
    for rel, src in sources.items():
        if not (rel.startswith(PKG_REL + "/network/") or rel.startswith(PKG_REL + "/support/")):
            continue
        if rel in round47.ALLOWLIST:
            continue
        if src.external_refs or src.client_class_refs:
            bad.append(rel)
    return bad


# ======================================================================================
# ⑦ 语言键（复用 audit_lang_keys 判据）
# ======================================================================================

def load_audit():
    path = os.path.join(TOOLS, "audit_lang_keys.py")
    spec = importlib.util.spec_from_file_location("rscc_audit_lang_keys", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def lang_ok(keys, zh, en, audit):
    for key in keys:
        if key not in zh or key not in en:
            return False
        zh_value, en_value = str(zh[key]), str(en[key])
        if not zh_value.strip() or not en_value.strip():
            return False
        if not audit.CJK_RE.search(zh_value):
            suspicious = [word for word in audit.ascii_tokens_without_placeholder(zh_value)
                          if word not in audit.ASCII_ALLOWED_TOKENS]
            if suspicious:
                return False
        if audit.CJK_RE.search(en_value):
            return False
        if re.findall(r"%(?:\d+\$)?[sd]", zh_value) != re.findall(r"%(?:\d+\$)?[sd]", en_value):
            return False
    return True


# ======================================================================================
# ⑧ 行为模型：置灰 / 点击语义（纯 Python 镜像，供反例自证）
# ======================================================================================

def mirror_click(locked, supported, current_mode, mode, early_return):
    """镜像 onTabClicked 的早退顺序：返回 ('noop'|'unavailable'|'switch'|'same')。"""
    if locked and early_return:
        return "noop"
    if not supported:
        return "unavailable"
    if current_mode == mode:
        return "same"
    return "switch"


def mirror_powered(stored, bound, energy_required, criterion):
    """镜像服务端 powered 判据；criterion='correct'|'capacity'|'item_energy'|'ignore_bound'。"""
    if criterion == "correct":
        return bool(bound and (stored > 0 or not energy_required))
    if criterion == "capacity":
        return bool(bound and stored >= 0)
    if criterion == "item_energy":
        return True  # 终端自身电量满 ⇒ 认为有电（错误写法）
    if criterion == "ignore_bound":
        return stored > 0 or not energy_required
    raise ValueError(criterion)


def mirror_criterion_ok(criterion):
    """判据实现与正确语义在所有边界情形上都一致才算 ok（供反例自证使用）。"""
    cases = [
        (0, True, True),    # 绑定但网络存量为 0 ⇒ 没电
        (5, True, True),    # 绑定且存量 > 0 ⇒ 有电
        (0, True, False),   # 服务端关闭耗能配置 ⇒ 有电
        (0, False, True),   # 未绑定 ⇒ 没电
        (0, False, False),  # 未绑定但关闭耗能配置 ⇒ 仍按没电（没有网络就没有「能不能切」的意义）
    ]
    return all(mirror_powered(stored, bound, required, criterion)
               == mirror_powered(stored, bound, required, "correct")
               for stored, bound, required in cases)


def main():
    overlay = strip_comments(read(OVERLAY))
    state = strip_comments(read(STATE))
    sink = strip_comments(read(SINK))
    hooks = strip_comments(read(HOOKS))
    packet = strip_comments(read(POWER_PACKET))
    reg = strip_comments(read(POWER_REG))
    switch = strip_comments(read(SWITCH_PACKET))
    power = strip_comments(read(POWER_SERVER))
    item = strip_comments(read(TERMINAL_ITEM))
    main_src = strip_comments(read(MAIN))

    # ---------------- ① 客户端：全灰 + 提前 return ----------------
    section("① 客户端：没电时所有模式 Tab 一律置灰、点击提前 return（不区分模式枚举）")
    check("①-a 点击路径的锁定判断早于 setMode / TerminalOpenIntent.mark / sendSwitch，且分支直接 return",
          pred_click_early_return(read(OVERLAY)))
    check("①-b 置灰判据只有 isLocked 一条，且本文件没有 MODE_* 分支（结构上不可能只灰某个模式）",
          pred_lock_not_mode_specific(read(OVERLAY)))
    check("①-c 锁定态背景条件 `locked || !supported`（与「该模式不支持」同一档灰）",
          "locked || !supported" in overlay)
    check("①-d 悬停 tooltip = 服务端同一句提示（存量 / 容量 FE），有电时仍是模式名",
          pred_locked_tooltip(read(OVERLAY), read(POWER_SERVER)))
    check("①-e 只在换界面时 reset 快照（同一界面缩放 / 子界面返回不闪）",
          pred_reset_only_on_new_screen(read(OVERLAY)))

    counterexample("没有提前 return（仍会 sendSwitch）⇒ 判负",
                   pred_click_early_return(read(OVERLAY).replace(
                       "if (TerminalPowerState.isLocked()) {\n            return;\n        }",
                       "if (TerminalPowerState.isLocked()) {\n            // 只是画灰\n        }")))
    counterexample("只灰样板终端（renderWidget 里加 mode == 1 分支）⇒ 「不区分模式」判负",
                   pred_lock_not_mode_specific(read(OVERLAY).replace(
                       "final boolean locked = TerminalPowerState.isLocked();",
                       "final boolean locked = TerminalPowerState.isLocked() && mode == 1;")))
    counterexample("把锁定态画成普通选中态（去掉 locked || 条件）⇒ 判负",
                   pred_lock_not_mode_specific(read(OVERLAY).replace("locked || !supported", "!supported")))

    # ---------------- ② 客户端状态语义 ----------------
    section("② 客户端状态：未知 / 过期不锁（有电行为不变），没电才锁")
    check("②-a 快照有 known/stored/capacity/bound/powered + STALE_MS 过期回落 UNKNOWN",
          pred_state_semantics(read(STATE)))
    check("②-b 锁定 = known && !powered（未知不锁，绝不会「没数据就锁死」）",
          pred_lock_semantics(read(STATE)))
    check("②-c 客户端只信服务端 powered，不自己由 stored/capacity 推算",
          pred_state_accepts_server_powered(read(STATE)))

    counterexample("未知也锁（known 当判据）⇒ 判负",
                   pred_lock_semantics(read(STATE).replace(
                       "return known && !powered;", "return true;")))
    counterexample("无条件锁（有电也灰）⇒ 判负",
                   pred_lock_semantics(read(STATE).replace(
                       "return known && !powered;", "return known;")))
    counterexample("客户端自己算「stored > 0 才有电」（与服务端口径分叉）⇒ 判负",
                   pred_state_accepts_server_powered(
                       read(STATE).replace("packet.powered(),", "packet.stored() > 0,")))

    # ---------------- ③ 服务端拒绝 ----------------
    section("③ 服务端：没电拒绝切换（不改 mode / 不重开界面 / 不静默 / 补发快照）")
    check("③-a handle 里 isPowered 早于 setMode / openModeScreen，拒绝分支 return",
          pred_server_reject(read(SWITCH_PACKET)))
    check("③-b 拒绝时给可见提示（displayClientMessage + 与 tooltip 同一句文本）",
          "player.displayClientMessage(" in switch
          and "TerminalNetworkPower.noPowerText(" in switch)
    check("③-c 拒绝时补发一份快照（客户端立刻置灰，不等下一拍心跳）",
          "PacketDistributor.sendToPlayer(player, power)" in switch)

    counterexample("只做客户端不做服务端（删掉 handle 里的电量门）⇒ 判负",
                   pred_server_reject(re.sub(
                       r"if \(!TerminalNetworkPower\.isPowered\(player, stack\)\) \{.*?\n            \}\n",
                       "", read(SWITCH_PACKET), flags=re.S)))
    counterexample("服务端静默拒绝（只有 return，没有提示）⇒ 判负",
                   pred_server_reject(read(SWITCH_PACKET).replace(
                       "player.displayClientMessage(", "// player.displayClientMessage(")))

    # ---------------- ④ 判据只看绑定网络 ----------------
    section("④ 判据：只读绑定网络 EnergyNetworkComponent；未绑定算没电；不读终端自身电量")
    check("④-a snapshot 用 stored > 0 判有电，或服务端关闭耗能配置（isEnergyRequired=false）",
          pred_powered_criterion(read(POWER_SERVER)))
    check("④-b 未绑定 / 网络组件缺失一律 powered=false（不是 capacity==0 判据）",
          "new TerminalPowerPacket(0L, 0L, false, false)" in power
          and "new TerminalPowerPacket(0L, 0L, true, false)" in power)
    check("④-c 判据类里不出现任何「终端物品自身电量」API（用户：终端本身有电也不行）",
          pred_no_item_energy(read(POWER_SERVER)))
    check("④-d 绑定解析唯一实现 resolveBoundNetwork（含跨维度 dimension + isLoaded），判据复用它",
          pred_bound_resolution(read(TERMINAL_ITEM), read(POWER_SERVER)))
    check("④-e 行为模型：bound 且 stored=0 ⇒ 没电；未绑定 ⇒ 没电；关闭耗能配置 ⇒ 有电",
          mirror_powered(0, True, True, "correct") is False
          and mirror_powered(0, False, True, "correct") is False
          and mirror_powered(0, True, False, "correct") is True)
    check("④-f 行为模型：正确判据在全部边界情形上自洽（mirror_criterion_ok）",
          mirror_criterion_ok("correct"))

    counterexample("没电判据换成「终端自身电量」⇒ 判负",
                   mirror_criterion_ok("item_energy"))
    counterexample("把 capacity==0 当没电（判据落在 capacity 上）⇒ 行为模型判负",
                   mirror_criterion_ok("capacity"))
    counterexample("未绑定算有电（忽略 bound）⇒ 行为模型判负",
                   mirror_criterion_ok("ignore_bound"))
    counterexample("判据类里读物品能量组件 ⇒ ④-c 判负",
                   pred_no_item_energy(read(POWER_SERVER).replace(
                       "final EnergyNetworkComponent energy =",
                       "final long own = stack.getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored();"
                       " final EnergyNetworkComponent energy =")))

    # ---------------- ⑤ 下发 ----------------
    section("⑤ 下发：界面打开立刻一条 + 每 20 tick 一条 + 关界面 / 下线停")
    check("⑤-a 心跳：start 立刻发 + 20 tick 间隔 + 关界面停 + 下线清 + 模式变了停",
          pred_heartbeat(read(POWER_SERVER), read(TERMINAL_ITEM)))
    check("⑤-b 只有界面真的开出来才 start（打开失败不启动观察；能耗页没有 Mode Tab 行、不启动心跳）",
          re.search(r"if \(opened[^)]*\) \{\s*\n\s*[^\n]*TerminalNetworkPower\.start\(player, slotReference\);",
                    read(TERMINAL_ITEM)) is not None
          and "opened = false;" in item)

    counterexample("心跳只发一次（不发周期包）⇒ 判负",
                   pred_heartbeat(read(POWER_SERVER).replace(
                       "gameTime - watch.lastSentTick < INTERVAL_TICKS", "true"), read(TERMINAL_ITEM)))
    counterexample("界面关闭不停（删掉 inventoryMenu 判据）⇒ 判负",
                   pred_heartbeat(read(POWER_SERVER).replace(
                       "if (player.containerMenu == player.inventoryMenu) {", "if (false) {"),
                       read(TERMINAL_ITEM)))

    # ---------------- ⑥ 注册 / 落点 / 专服安全 ----------------
    section("⑥ 注册 / 客户端落点 / 专用服务端安全")
    check("⑥-a 载荷形状：4 字段 codec + type id terminal_power + enqueueWork 走 ClientPayloadHooks",
          pred_packet_shape(read(POWER_PACKET)))
    check("⑥-b 注册且只注册一次（独立 MOD 总线监听器；主类里不得再出现 TerminalPowerPacket）",
          pred_registered_once(read(POWER_REG), read(POWER_PACKET), read(MAIN)))
    check("⑥-c ClientPayloadHooks 有 default 空实现、ClientPayloadSink 写 TerminalPowerState",
          pred_sink_wiring(read(HOOKS), read(SINK)))

    counterexample("把注册挪进主类、同时保留独立监听器（重复注册）⇒ 判负",
                   pred_registered_once(read(MAIN), read(POWER_PACKET),
                                        read(MAIN) + "\n        TerminalPowerPacket.TYPE,"))

    round47 = load_round47()
    sources = round47.load_sources()
    bad = network_client_safety_ok(round47, sources)
    check("⑥-d network/** 与 support/** 不引用客户端类型（复用 round47 判据）", not bad)
    print("       越界文件: %s" % (", ".join(bad[:6]) if bad else "无"))

    # ---------------- ⑦ 语言键 ----------------
    section("⑦ 语言键：本轮新增键 zh/en 成对、非空、zh 有中文、en 无中日韩、占位符一致")
    audit = load_audit()
    zh = audit.load("zh_cn.json")
    en = audit.load("en_us.json")
    keys = {LANG_NO_POWER}
    check("⑦-a 新增键中英成对、占位符一致（复用 audit_lang_keys 判据）",
          lang_ok(keys, zh, en, audit))
    counterexample("en_us 缺这条键 ⇒ 语言判据判负",
                   lang_ok(keys, zh, {k: v for k, v in en.items() if k != LANG_NO_POWER}, audit))

    # ---------------- ⑧ 有电时行为完全不变 ----------------
    section("⑧ 有电时行为完全不变（旧判据与旧链路原样保留）")
    check("⑧-a 有电时仍按 supported 判可用性，并把「模式不支持」提示留在原位",
          "if (!supported) {" in overlay
          and "item.rs_create_compat.advanced_remote_terminal.mode_unavailable" in overlay)
    check("⑧-b 高亮仍是 supported && 当前模式的函数，点击去重 / 写存活栈 / 发包链路未改",
          "!locked && supported && state.mode() == mode" in overlay
          and "if (mode == state.mode()) {" in overlay
          and "AdvancedRemoteTerminalItem.setMode(state.liveStack(), mode);" in overlay
          and "TerminalOpenIntent.mark()" in overlay
          and "sendSwitch(player, state.slotReference(), mode," in overlay)
    check("⑧-c 行为模型：未知 / 有电都不提前 return（点击照旧走原链路）",
          mirror_click(False, True, 0, 1, True) == "switch"
          and mirror_click(False, False, 0, 1, True) == "unavailable"
          and mirror_click(False, True, 1, 1, True) == "same")
    check("⑧-d 行为模型：锁定后点击 = 什么都不发生（不发包），且与模式号无关",
          all(mirror_click(True, True, 0, mode, True) == "noop" for mode in range(6)))
    counterexample("锁定判断放在 click 成功之后（先发再拦）⇒ 模型判负",
                   mirror_click(True, True, 0, 1, False) == "noop")

    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条）" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item_fail in FAILS:
            print(" - " + item_fail)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
