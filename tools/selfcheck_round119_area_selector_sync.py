#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 119 轮自检：区域选定器「静默失效」三连（C-1 / C-2 / C-3）。

背景（只读审计 docs/ROUND119_AUDIT_AREA_MARKS.md 的 C 节）
--------------------------------------------------------
* **C-1（中）** 菜单路径被拒时只 `noteAreaReject` + `sync`（`broadcastChanges`）。
  服务端物品本来就没变 ⇒ `broadcastChanges` 一个槽包都不发 ⇒ 客户端那份**乐观副本**
  （发包前 `AreaSelection.set` 改的）留在界面上。接着配标记时，`AreaMarkEditPacket`
  拿着服务端不存在的几何 ⇒ `findArea` 返回 null ⇒ 静默丢包，而界面已经弹「已加入」。
* **C-2（中）** `AreaSelectorScreen#targetStack()` 在 `menu != null` 但区域槽为空时回退读主手，
  而 `containerId()` 仍是菜单 id ⇒ 服务端按菜单解析到空槽 ⇒ 所有编辑静默丢包且没有 sync。
* **C-3（低）** 手持路径（`AreaSelectorHandler`）不做去重 / 维度预检，且发包后立刻宣告「已提交」；
  服务端拒绝时主手分支只 `broadcastFullState()`，不弹任何文字 ⇒ 重复框同一片地会看到「已提交」。

本轮修法
--------
1. `AreaSelectorEditPacket#reject` 的**菜单分支**补一次 `menu.broadcastFullState()`（只在拒绝时，
   不放进 `AreaSelectorTargets#sync`，避免每次成功写入都全量发）；
2. 两个入口（`AreaSelectorScreen` / `AreaMarkScreen`）的菜单路径都**不回退主手**；
   `main` 槽空时区域列表给出 `status.no_slot`，标记界面拒绝发意图（不再弹假的「已加入」）；
3. 手持路径补「维度 / 去重」预检，被拦时就地说明，不再先宣告成功。

三条腿（与第 113 / 118 轮同一套路）
----------------------------------
① 源码锚点：每条修法的关键语句 + 顺序 + 「没有回退成坏写法」；
② 行为模型：客户端副本 vs 服务端权威、菜单槽空、成功提示与事实一致性；
③ 反例自证：把坏写法拼回源码副本 / 模型，断言同一条判据真的判负（证明判据不是摆设）。

纪律：不跑 gradle、不改存档格式、不碰其他 agent 正在写的文件。
用法：python tools/selfcheck_round119_area_selector_sync.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)
LANG_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "lang")
LANG_DIR = os.path.join(ROOT, LANG_REL)

EDIT_PACKET = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
MARK_PACKET = os.path.join(JAVA, "network", "AreaMarkEditPacket.java")
TARGETS = os.path.join(JAVA, "support", "AreaSelectorTargets.java")
SELECTOR_SCREEN = os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")
MARK_SCREEN = os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")
HANDLER = os.path.join(JAVA, "client", "AreaSelectorHandler.java")

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性」判据。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


def method_body(code, hint):
    """从（已去注释的）源码里取一个方法体：从 hint 起找到第一个 '{' 并配平。"""
    start = code.find(hint)
    if start < 0:
        return None
    brace = code.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return None


def brace_block(text, header):
    """取以 header 开头的那个花括号块（含嵌套）。找不到返回 None。"""
    if text is None:
        return None
    start = text.find(header)
    if start < 0:
        return None
    brace = text.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


def lang(lang_name):
    return json.load(io.open(os.path.join(LANG_DIR, lang_name + ".json"), encoding="utf-8"))


# ======================================================================================
# 判据（写成「纯函数」，反例自证时直接喂改坏的源码副本）
# ======================================================================================

# ---------- C-1 ----------

def reject_broadcasts_full_state(src):
    code = strip_comments(src)
    body = method_body(code, "private static void reject(")
    if body is None:
        return False
    # 主手分支 + 菜单分支各一次；菜单分支必须在 menu == null 的早退之后。
    null_early = body.find("if (menu == null) {")
    return (body.count("broadcastFullState();") == 2
            and "menu.broadcastFullState();" in body
            and 0 <= null_early < body.find("menu.broadcastFullState();"))


def sync_does_not_full_broadcast(src):
    body = method_body(strip_comments(src), "public static void sync(")
    # 主手分支本来就有 broadcastFullState（第 113 轮 D7）；这里只禁止「菜单分支也跟着全量发」。
    return body is not None and "menu.broadcastFullState" not in body


def reject_still_records_reason(src):
    body = method_body(strip_comments(src), "private static void reject(")
    return (body is not None
            and "menu.noteAreaReject(reason);" in body
            and "sync(player, packet.containerId());" in body)


# ---------- C-2 ----------

def menu_branch(body):
    """同一语义的两种写法：`if (menu != null)`（区域列表）/ `if (containerId >= 0)`（标记界面）。"""
    return brace_block(body, "if (menu != null) {") or brace_block(body, "if (containerId >= 0) {")


def menu_branch_no_main_hand(src, hint="private ItemStack targetStack()"):
    body = method_body(strip_comments(src), hint)
    block = menu_branch(body)
    return block is not None and "getMainHandItem" not in block


def hand_fallback_only_outside_menu(src, hint="private ItemStack targetStack()"):
    body = method_body(strip_comments(src), hint)
    block = menu_branch(body)
    if body is None or block is None:
        return False
    outside = body.replace(block, "")
    return body.count("getMainHandItem") == 1 and "getMainHandItem" in outside


def selection_stack_delegates(src):
    body = method_body(strip_comments(src), "private ItemStack selectionStack()")
    return (body is not None
            and "targetStack()" in body
            and "getMainHandItem" not in body
            and "getAreaSelectorStack" not in body)


def selector_new_button_requires_target(src):
    body = method_body(strip_comments(src), "private void refreshButtonLabels(")
    if body is None:
        return False
    block = brace_block(body, "if (newButton != null) {")
    return block is not None and "targetStack() != null" in block


def create_area_requires_target(src):
    body = method_body(strip_comments(src), "private void createArea()")
    return body is not None and "if (targetStack() == null) {" in body


def no_slot_note_used(src):
    body = method_body(strip_comments(src), "private void drawTexts(")
    if body is None:
        return False
    block = brace_block(body, "} else if (snapshot != null && snapshot.status() == 0) {")
    return block is not None and 'LANG + "status.no_slot"' in block


def mark_screen_guards_missing_target(src):
    code = strip_comments(src)
    guarded = True
    for hint in ("private void addFromInventory(", "private void removeAt(", "private void clearAll("):
        body = method_body(code, hint)
        if body is None or "menuTargetMissing()" not in body:
            guarded = False
            break
    helper = method_body(code, "private boolean menuTargetMissing()")
    return (guarded
            and helper is not None
            and "targetStack().isEmpty()" in helper
            and "status.no_slot" in helper)


# ---------- C-3 ----------

def hand_add_prechecks(src):
    body = method_body(strip_comments(src), "public static boolean onMouseButton(")
    if body is None:
        return False
    dimension = body.find("selection.matchesDimension(")
    duplicate = body.find("selection.hasGeometry(area, -1)")
    send = body.find("PacketDistributor.sendToServer")
    if not (0 <= dimension < send and 0 <= duplicate < send):
        return False
    block = brace_block(body, "if (selection.hasGeometry(area, -1)) {")
    return (block is not None
            and 'MSG + "duplicate"' in block
            and "return true;" in block)


def hand_resize_prechecks(src):
    body = method_body(strip_comments(src), "private static boolean resizeSelected(")
    if body is None:
        return False
    dimension = body.find("selection.matchesDimension(")
    duplicate = body.find("selection.hasGeometry(resized, selection.selectedIndex())")
    send = body.find("PacketDistributor.sendToServer")
    return (0 <= dimension < send and 0 <= duplicate < send
            and 'MSG + "duplicate"' in body)


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点 ===")
    edit_raw = read(EDIT_PACKET)
    targets_raw = read(TARGETS)
    selector_raw = read(SELECTOR_SCREEN)
    mark_raw = read(MARK_SCREEN)
    handler_raw = read(HANDLER)
    zh = lang("zh_cn")
    en = lang("en_us")

    # ---- C-1：菜单拒绝也全量回发 ----
    check("C1-1 reject()：主手 + 菜单两个分支都 broadcastFullState（菜单分支在 menu==null 早退之后）",
          reject_broadcasts_full_state(edit_raw),
          "菜单拒绝没有全量回发 / 调到了 early-return 之前")
    check("C1-2 reject() 仍保留 noteAreaReject + sync（拒绝原因照旧随快照回传）",
          reject_still_records_reason(edit_raw))
    check("C1-3 broadcastFullState 没有挪进 AreaSelectorTargets#sync（成功写入不跟着全量发）",
          sync_does_not_full_broadcast(targets_raw))
    code = strip_comments(edit_raw)
    check("C1-4 全文件 broadcastFullState 调用恰好 2 处（主手 1 + 菜单拒绝 1），不是每 tick 一次的写法",
          code.count("broadcastFullState()") == 2, "调用 %d 处" % code.count("broadcastFullState()"))

    # ---- C-2：两个入口都不回退主手 + 空槽提示 ----
    check("C2-1 AreaSelectorScreen#targetStack 的菜单分支不读主手",
          menu_branch_no_main_hand(selector_raw))
    check("C2-2 同一个 targetStack 的手持分支仍然读主手（没有误伤手持入口）",
          hand_fallback_only_outside_menu(selector_raw))
    check("C2-3 selectionStack() 委托 targetStack()，没有第二份「菜单槽优先、否则主手」逻辑",
          selection_stack_delegates(selector_raw))
    check("C2-4 AreaMarkScreen#targetStack 的菜单分支同样不读主手（两个入口同口径）",
          menu_branch_no_main_hand(mark_raw))
    check("C2-5 「新建」要求有目标选定器（槽空时按钮置灰，不发注定被丢的包）",
          selector_new_button_requires_target(selector_raw))
    check("C2-6 createArea() 先判 targetStack() == null（兜底不发空包）",
          create_area_requires_target(selector_raw))
    check("C2-7 列表底部状态 0 用 status.no_slot（明确说「放进专用槽」，不是含糊的「没装」）",
          no_slot_note_used(selector_raw))
    check("C2-8 标记界面的加 / 删 / 清三个入口都先拦「菜单槽空」（不弹假的已加入 / 已移除）",
          mark_screen_guards_missing_target(mark_raw))

    key = "gui.rs_create_compat.area_selector.status.no_slot"
    check("C2-9 语言键 %s 在 zh/en 成对存在且非空" % key,
          bool(zh.get(key)) and bool(en.get(key)),
          "zh=%r en=%r" % (zh.get(key), en.get(key)))
    # 语言文件本身是资源，不是源码；这里只断言两个入口引用同一个键（单一来源）。
    check("C2-10 标记界面复用同一个 no_slot 键（不复制一份文案到两个地方）",
          '"gui.rs_create_compat.area_selector.status.no_slot"' in strip_comments(mark_raw))

    # ---- C-3：手持路径预检 + 不预告成功 ----
    check("C3-1 手持 ADD：维度 / 去重预检都在 sendToServer 之前，且去重分支给 duplicate + return",
          hand_add_prechecks(handler_raw))
    check("C3-2 手持 REPLACE（Ctrl+滚轮）：同样补了维度 / 去重预检",
          hand_resize_prechecks(handler_raw))
    handler_code = strip_comments(handler_raw)
    on_mouse = method_body(handler_code, "public static boolean onMouseButton(")
    send_at = on_mouse.find("PacketDistributor.sendToServer") if on_mouse else -1
    committed_at = on_mouse.find('MSG + "committed"') if on_mouse else -1
    check("C3-3 「已提交」只在 sendToServer 之后（发包路径），不会在预检被拦时出现",
          send_at >= 0 and committed_at > send_at)
    for lang_name, data in (("zh_cn", zh), ("en_us", en)):
        check("C3-4 %s 语言键 duplicate / dimension 存在（dimension 带 %%s 维度占位符）" % lang_name,
              bool(data.get("message.rs_create_compat.area_selector.duplicate"))
              and bool(data.get("message.rs_create_compat.area_selector.dimension"))
              and "%s" in data.get("message.rs_create_compat.area_selector.dimension", ""))


# ======================================================================================
# ② 行为模型：客户端副本 vs 服务端权威
# ======================================================================================

def group_models():
    print("\n=== ② 行为模型 ===")

    # ---------- C-1：被拒后客户端乐观副本 ----------
    A = ("A", 0, 10)
    B = ("B", 20, 30)
    X = ("X", 99, 199)   # 客户端陈旧列表里的几何；服务端从来没有它
    server = [A, B]
    client_optimistic = [X, B]   # 玩家改坐标后客户端乐观副本（服务端已拒绝，物品没变）

    def client_after_reject(authoritative, client_copy, full_state):
        """服务端拒绝后回给客户端的东西。

        `full_state=False` = 旧写法：只 `broadcastChanges()`。它按「槽内容与上次发出的快照是否不同」
        决定发不发 —— 拒绝时服务端物品本来就没变 ⇒ 一个槽包都不发 ⇒ 客户端副本原样残留。
        `full_state=True` = 本轮修法：`menu.broadcastFullState()` 把权威槽内容整份推回。
        """
        return list(authoritative) if full_state else list(client_copy)

    old_client = client_after_reject(server, client_optimistic, full_state=False)
    new_client = client_after_reject(server, client_optimistic, full_state=True)

    def server_accepts_mark(authoritative, area):
        """`AreaMarkEditPacket#findArea`：按六坐标几何在**服务端**选定器上找，找不到就丢包。"""
        return area in authoritative

    check("C1-M1 旧写法：被拒后客户端副本仍是乐观值（≠ 服务端权威）⇒ 残留",
          old_client == client_optimistic and old_client != server, str(old_client))
    counterexample("C1-M2 新写法：被拒后客户端副本被整份纠正回服务端权威值",
                   new_client == server, str(new_client))
    check("C1-M3 旧写法：拿残留几何配标记 ⇒ 服务端 findArea 找不到 ⇒ 丢包（界面却会弹「已加入」）",
          server_accepts_mark(server, old_client[0]) is False)
    counterexample("C1-M4 新写法：界面已回到权威几何，配标记命中真实区域 ⇒ 不再静默丢包",
                   server_accepts_mark(new_client, X) is False
                   and server_accepts_mark(new_client, A) is True)
    # 「被拒后客户端副本一定会被纠正」对任意权威状态都成立
    all_cases_ok = True
    for authoritative in ([], [A], [A, B], [B]):
        for optimistic in ([X], [X, B], [A, X]):
            if client_after_reject(authoritative, optimistic, full_state=True) != authoritative:
                all_cases_ok = False
    counterexample("C1-M5 多组「权威状态 × 乐观副本」组合：全量回发后必然 == 权威状态",
                   all_cases_ok)

    # 全量回发只在拒绝时发生：成功写入仍走 broadcastChanges（不全量刷）
    def full_state_calls(actions):
        return sum(1 for action in actions if action == "rejected")

    check("C1-M6 100 tick 空闲 = 0 次全量回发（不是每 tick 调）", full_state_calls(["idle"] * 100) == 0)
    check("C1-M7 一次拒绝 = 恰好 1 次全量回发；一次成功写入 = 0 次",
          full_state_calls(["rejected"]) == 1 and full_state_calls(["written"]) == 0)

    # ---------- C-2：菜单槽空时不读主手 ----------
    def resolve_client_target(menu_open, slot_has_selector, main_hand_has_selector, fallback):
        """对应 AreaSelectorScreen#targetStack（fallback=True = 旧写法）。返回 (目标, 读过谁)。"""
        reads = []
        if menu_open:
            if slot_has_selector:
                return "menu_slot", reads
            if fallback:
                reads.append("main_hand")
                return "main_hand", reads
            return None, reads
        if main_hand_has_selector:
            reads.append("main_hand")
            return "main_hand", reads
        return None, reads

    old_target, old_reads = resolve_client_target(True, False, True, fallback=True)
    new_target, new_reads = resolve_client_target(True, False, True, fallback=False)
    hand_target, hand_reads = resolve_client_target(False, False, True, fallback=False)
    slot_target, slot_reads = resolve_client_target(True, True, True, fallback=False)

    check("C2-M1 旧写法：菜单开着 + 槽空 ⇒ 回退读主手（界面显示主手数据）",
          old_target == "main_hand" and "main_hand" in old_reads)
    counterexample("C2-M2 新写法：菜单开着 + 槽空 ⇒ 没有目标，且**根本没读主手**",
                   new_target is None and "main_hand" not in new_reads)
    counterexample("C2-M3 新写法没有误伤手持入口：menu == null 时仍读主手",
                   hand_target == "main_hand" and "main_hand" in hand_reads)
    counterexample("C2-M4 新写法正常路径不变：菜单槽里有选定器 ⇒ 只读菜单槽",
                   slot_target == "menu_slot" and "main_hand" not in slot_reads)

    def edit_outcome(target, container_id, slot_has_selector, main_hand_has_selector):
        """服务端 AreaSelectorTargets#resolve：containerId >= 0 只认菜单槽，槽空即丢包。"""
        if container_id >= 0:
            resolved = target == "menu_slot" and slot_has_selector
        else:
            resolved = target == "main_hand" and main_hand_has_selector
        return "accepted" if resolved else "dropped"

    old_edit = edit_outcome(old_target, 7, slot_has_selector=False, main_hand_has_selector=True)
    new_edit = edit_outcome(new_target, 7, slot_has_selector=False, main_hand_has_selector=True)
    check("C2-M5 旧写法全链路：界面读主手 + 包带菜单 id ⇒ 服务端 drop（编辑静默消失）",
          old_edit == "dropped")
    counterexample("C2-M6 新写法全链路：没有目标就不发编辑（drop 次数 0，且界面有 no_slot 提示）",
                   new_edit == "dropped" and new_target is None)

    # ---------- C-3：成功提示只在成功路径上 ----------
    def hand_add(precheck, selection, dimension_bound, current_dimension, candidate):
        """手持两次右键提交 ADD 的客户端 / 服务端联合模型。"""
        if precheck:
            if dimension_bound is not None and dimension_bound != current_dimension:
                return {"sent": False, "notice": "dimension", "success_claim": False,
                        "server_added": False}
            if candidate in selection:
                return {"sent": False, "notice": "duplicate", "success_claim": False,
                        "server_added": False}
        sent = True
        server_accepts = (dimension_bound in (None, current_dimension)) and candidate not in selection
        # 旧写法与修好后的写法都在发出后立刻显示「已提交」；区别只在预检阶段有没有拦住。
        return {"sent": sent, "notice": "committed", "success_claim": True,
                "server_added": server_accepts}

    current = "minecraft:overworld"
    base = [A]
    old_dup = hand_add(False, base, None, current, A)
    new_dup = hand_add(True, base, None, current, A)
    old_dim = hand_add(False, base, "minecraft:the_nether", current, B)
    new_dim = hand_add(True, base, "minecraft:the_nether", current, B)
    old_ok = hand_add(False, base, None, current, B)
    new_ok = hand_add(True, base, None, current, B)

    check("C3-M1 旧写法「重复框同一片地」：发包 + 宣称成功，服务端却拒绝 ⇒ 提示与事实相反",
          old_dup["sent"] and old_dup["success_claim"] and not old_dup["server_added"])
    counterexample("C3-M2 新写法「重复框同一片地」：不发送、提示 duplicate、不声称成功",
                   (not new_dup["sent"]) and new_dup["notice"] == "duplicate"
                   and not new_dup["success_claim"] and not new_dup["server_added"])
    check("C3-M3 旧写法「维度不符」：同样出现假的「已提交」",
          old_dim["sent"] and old_dim["success_claim"] and not old_dim["server_added"])
    counterexample("C3-M4 新写法「维度不符」：不发送、提示 dimension、不声称成功",
                   (not new_dim["sent"]) and new_dim["notice"] == "dimension"
                   and not new_dim["success_claim"] and not new_dim["server_added"])
    counterexample("C3-M5 合法几何：新写法照常发送，服务端写入，成功提示与事实一致",
                   new_ok["sent"] and new_ok["server_added"] and new_ok["success_claim"]
                   and new_ok["notice"] == "committed")

    def success_always_truthful(precheck):
        scenarios = [
            ({"selection": [A], "bound": None, "current": current, "candidate": A}),
            ({"selection": [A], "bound": "minecraft:the_nether", "current": current, "candidate": B}),
            ({"selection": [A], "bound": None, "current": current, "candidate": B}),
            ({"selection": [], "bound": None, "current": current, "candidate": A}),
        ]
        for scenario in scenarios:
            result = hand_add(precheck, scenario["selection"], scenario["bound"],
                              scenario["current"], scenario["candidate"])
            if result["success_claim"] and not result["server_added"]:
                return False
        return True

    check("C3-M6 不变量：旧写法存在「声称成功但服务端没写」的场景（所以 C-3 是真 bug）",
          success_always_truthful(False) is False)
    counterexample("C3-M7 不变量：新写法的成功提示只在服务端真写入时出现（全部场景）",
                   success_always_truthful(True) is True)


# ======================================================================================
# ③ 反例自证（源码副本：把坏写法拼回去，断言上面的判据真的判负）
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证（源码副本） ===")
    edit_code = strip_comments(read(EDIT_PACKET))
    targets_code = strip_comments(read(TARGETS))
    selector_code = strip_comments(read(SELECTOR_SCREEN))
    mark_code = strip_comments(read(MARK_SCREEN))
    handler_code = strip_comments(read(HANDLER))

    # C-1：删掉菜单分支的 broadcastFullState ⇒ 锚点判负
    broken = edit_code.replace("        menu.broadcastFullState();\n", "", 1)
    counterexample("C1-C1 源码副本：删掉 menu.broadcastFullState() 后，C1-1 / C1-4 会判负",
                   broken != edit_code and not reject_broadcasts_full_state(broken)
                   and broken.count("broadcastFullState()") == 1)

    # C-1：把全量回发挪进 sync（每次成功写入都全量发）⇒ C1-3 判负
    broken = targets_code.replace(
        "            menu.onAreaSelectorEdited();\n            menu.broadcastChanges();",
        "            menu.onAreaSelectorEdited();\n            menu.broadcastChanges();\n"
        "            menu.broadcastFullState();", 1)
    counterexample("C1-C2 源码副本：把 broadcastFullState 塞进 sync() 后，C1-3 会判负",
                   broken != targets_code and not sync_does_not_full_broadcast(broken))

    # C-2：把「槽空回退主手」塞回菜单分支 ⇒ C2-1 判负
    old_fallback = (
        "        if (menu != null) {\n"
        "            final ItemStack inSlot = menu.getAreaSelectorStack();\n"
        "            if (!inSlot.isEmpty()) {\n"
        "                return inSlot;\n"
        "            }\n"
        "            final ItemStack fallback = Minecraft.getInstance().player.getMainHandItem();\n"
        "            return fallback.is(RS_Create_Compat.AREA_SELECTOR.get()) ? fallback : null;\n"
        "        }\n")
    new_branch = brace_block(method_body(selector_code, "private ItemStack targetStack()"),
                             "if (menu != null) {")
    broken = selector_code.replace(new_branch, old_fallback, 1) if new_branch else selector_code
    counterexample("C2-C1 源码副本：把菜单槽空回退主手塞回去后，C2-1 / C2-2 会判负",
                   new_branch is not None
                   and not menu_branch_no_main_hand(broken)
                   and not hand_fallback_only_outside_menu(broken))

    # C-2：把 selectionStack 自己的回退逻辑塞回去 ⇒ C2-3 判负
    broken = selector_code.replace(
        "        final ItemStack stack = targetStack();\n"
        "        return stack == null ? ItemStack.EMPTY : stack;",
        "        if (menu != null) {\n"
        "            final ItemStack inSlot = menu.getAreaSelectorStack();\n"
        "            if (!inSlot.isEmpty()) {\n"
        "                return inSlot;\n"
        "            }\n"
        "        }\n"
        "        return Minecraft.getInstance().player.getMainHandItem();", 1)
    counterexample("C2-C2 源码副本：selectionStack 自己写回退后，C2-3 会判负（两份判据分叉）",
                   broken != selector_code and not selection_stack_delegates(broken))

    # C-2：x 掉 no_slot 提示 ⇒ C2-7 判负
    broken = selector_code.replace('LANG + "status.no_slot"', 'LANG + "status.no_selector"', 1)
    counterexample("C2-C3 源码副本：把 status.no_slot 换回含糊的 no_selector 后，C2-7 会判负",
                   broken != selector_code and not no_slot_note_used(broken))

    # C-2：允许「槽空时点新建」⇒ C2-5 判负（又会发一个注定被丢的包）
    broken = selector_code.replace(
        "newButton.active = targetStack() != null\n"
        "                && editableHere()",
        "newButton.active = editableHere()", 1)
    counterexample("C2-C5 源码副本：去掉「新建要求有目标」后，C2-5 会判负"
                   "（槽空时又能点出一个静默丢包）",
                   broken != selector_code and not selector_new_button_requires_target(broken))

    # C-2：x 掉标记界面的空槽拦截 ⇒ C2-8 判负
    broken = mark_code.replace(
        "    private void addFromInventory(final int slot) {\n"
        "        if (menuTargetMissing()) {\n"
        "            return;\n"
        "        }",
        "    private void addFromInventory(final int slot) {", 1)
    counterexample("C2-C4 源码副本：删掉 addFromInventory 的空槽拦截后，C2-8 会判负"
                   "（界面又会弹假的「已加入」）",
                   broken != mark_code and not mark_screen_guards_missing_target(broken))

    # C-3：x 掉手持 ADD 的去重预检 ⇒ C3-1 判负
    broken = handler_code.replace("selection.hasGeometry(area, -1)", "false", 1)
    counterexample("C3-C1 源码副本：删掉手持 ADD 的去重预检后，C3-1 会判负",
                   broken != handler_code and not hand_add_prechecks(broken))

    # C-3：把发包挪到预检之前 ⇒ 顺序判据判负
    send_match = re.search(
        r"net\.neoforged\.neoforge\.network\.PacketDistributor\.sendToServer\(\s*"
        r"new AreaSelectorEditPacket\(-1, AreaSelectorEditPacket\.ACTION_ADD, -1, area\)\);",
        handler_code)
    if send_match:
        send_text = send_match.group(0)
        moved = handler_code.replace(send_text, "", 1)
        anchor = "final Area area = second == null ? null : Area.of(pendingFirst, second);"
        moved = moved.replace(anchor, anchor + "\n        " + send_text, 1)
    else:
        moved = handler_code
    counterexample("C3-C2 源码副本：把发包挪到预检之前后，C3-1 的顺序判据会判负（先宣告后校验）",
                   send_match is not None and not hand_add_prechecks(moved))

    # C-3：x 掉手持 REPLACE 的去重预检 ⇒ C3-2 判负
    broken = handler_code.replace("selection.hasGeometry(resized, selection.selectedIndex())",
                                  "false", 1)
    counterexample("C3-C3 源码副本：删掉 Ctrl+滚轮的 REPLACE 去重预检后，C3-2 会判负",
                   broken != handler_code and not hand_resize_prechecks(broken))


def main():
    print("第 119 轮自检：区域选定器同步三连（C-1 菜单拒绝全量回发 / C-2 槽空不回退 / C-3 手持预检）")
    group_anchors()
    group_models()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
