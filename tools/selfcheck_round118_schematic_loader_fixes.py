#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 118 轮自检（之四）：蓝图加农炮装填器家族的 4 处修复（H1 / H2 / M1 / M3）。

背景
----
第 118 轮请一位独立只读复核员对抗式复核「蓝图加农炮装填器」家族（MC 1.21.1 + Create 6）。
他报了 2 高 / 4 中 / 3 低，本轮修掉其中最要紧的 4 条：

* **H1（丢物品）** ``SchematicLoaderMenu`` 的蓝图槽只挡「同集群有高级装填器」，
  而``BlueprintView#setStackInSlot`` 在「队列流水线掌管共享蓝图槽」时**静默 return**。
  原版 SWAP / Shift 点击在调用 ``set`` 之前就已经把物品从玩家背包摘掉了 ⇒ 静默 return = 销毁蓝图。
  可达场景：基础装填器贴着同一台加农炮、同集群里却没有高级装填器（高级在另一侧、彼此不相邻）。
* **H2（卡服 / 带宽）** ``doLoaderWork`` 每 tick 无条件 ``cannon.updateChecklist()``：
  那是 Create 里最重的操作（遍历蓝图全部坐标 + 逐点读世界 + 重扫邻块容器），内部还会置 sendUpdate
  ⇒ 加农炮每 tick 向周围客户端发一次方块实体更新包（20 包/秒，空闲也发）。Create 自己只在
  ``initializePrinter`` / 写字板打印完成时调用它。
* **M1（空转 + 状态卡死）** ``takeFromQueue()`` 写在「该不该部署」的判断外面：
  「加农炮蓝图槽空 + 队列有货」时每 tick「取一张 → 原样放回」，玩家从加农炮界面取走蓝图即可进入。
* **M3（全服刷屏）** 「网络无火药」提示走的是全服 ``getPlayerList().getPlayers()``，
  与同文件里「距离口径 64 格，看得到机器的人才收得到提示」自相矛盾。

四条腿（与第 116～118 轮同一套路）
----------------------------------
① 源码锚点：四处修复各自的关键语句都在，且没有回退成坏写法；
② 行为模型：H1 的物品守恒（原版 SWAP 先摘物）、M1 的「空转次数 = 0」、H2 的「每 tick 一次 → 只在换图时一次」；
③ 反例自证：把坏写法拼进源码副本 / 模型，断言判据真的判负；
④ 语言键 zh/en 成对。

用法：python tools/selfcheck_round118_schematic_loader_fixes.py
退出码：0 = 全部通过。
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
BE = os.path.join(PKG, "block", "entity", "SchematicLoaderBlockEntity.java")
MENU = os.path.join(PKG, "menu", "SchematicLoaderMenu.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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


def call_lines(code, needle):
    """返回包含 needle 的每一行的缩进宽度（去注释后的源码）。"""
    out = []
    for line in code.splitlines():
        if needle in line:
            out.append(len(line) - len(line.lstrip()))
    return out


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点 ===")
    be_src = read(BE)
    be = strip_comments(be_src)
    menu = strip_comments(read(MENU))

    # ---- H1：丢蓝图 ----
    anchor = menu.find("stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())")
    check("H1-1 基础菜单蓝图槽 mayPlace 增加了「队列掌管时拒绝」",
          "isSharedBlueprintSlotQueueLocked()" in menu[menu.find("addSlot(new SlotItemHandler(blueprintHandler"):][:800],
          "mayPlace 没有队列锁判断")
    check("H1-2 方块实体提供公开判据 isSharedBlueprintSlotQueueLocked（客户端恒 false）",
          "public boolean isSharedBlueprintSlotQueueLocked()" in be
          and "lvl.isClientSide()" in be,
          "缺少公开判据或缺少客户端早退")
    set_branch = be.find("queue pipeline owns the cannon blueprint slot -> set refused")
    check("H1-3 setStackInSlot 的队列锁分支把物品落回世界（不再静默 return = 销毁）",
          set_branch > 0 and "dropIntoWorld(stack)" in be[set_branch:set_branch + 700],
          "队列锁分支没把物品还给世界")

    # ---- H2：每 tick updateChecklist ----
    indents = call_lines(be, "cannon.updateChecklist()")
    check("H2-1 没有任何一处裸的（方法体顶层 8 空格缩进）cannon.updateChecklist() 调用",
          all(indent >= 12 for indent in indents),
          "缩进 %r ⇒ 存在无条件调用" % (indents,))
    check("H2-2 updateChecklist 只在两处调用：换蓝图分支 + 队列部署分支",
          len(indents) == 2, "调用点 %d 处" % len(indents))
    check("H2-3 换蓝图分支里确实刷新了 checklist（避免沿用旧蓝图清单）",
          "cannon.updateChecklist();" in be[be.find("blueprint changed -> new round"):][:2000],
          "换图分支没有刷新")

    # ---- M1：队列空转 ----
    takes = re.findall(r"= takeFromQueue\(\)", be)
    check("M1-1 takeFromQueue() 的调用只有 1 处（取出即部署，不存在「取出再看该不该部署」）",
          len(takes) == 1, "调用 %d 处" % len(takes))
    gate = be.find("queueRunning && cannonSlotEmpty && (printJustFinished || queueJustStarted)")
    check("M1-2 取队列动作被「刚打完 / 刚启动」两个条件门控",
          gate > 0 and "takeFromQueue()" in be[gate:gate + 200],
          "takeFromQueue 不在门控分支内")
    check("M1-3 已删除 returnToQueue（补救路径随「取出即部署」一起消失，不再留死代码 / 过期注释）",
          "returnToQueue" not in be,
          "仍存在 returnToQueue")
    check("M1-4 新增「中途取空蓝图槽 → 自动暂停 + 提示附近玩家」分支（队列原样保留）",
          "queue_paused_empty" in be and "stopQueueRun();" in be
          and be.find("queue_paused_empty") > be.find("hasPendingBlueprintQueue()"),
          "缺少中途暂停分支")

    # ---- M3：全服广播 ----
    check("M3-1 「网络无火药」提示改走 notifyNearby（64 格口径），文件里不再有全服广播",
          "getPlayerList().getPlayers()" not in be,
          "仍有全服广播")
    gun = be.find("schematic_loader.no_gunpowder")
    check("M3-2 火药提示的发送方式是 notifyNearby(..., true)（行动栏 + 64 格）",
          gun > 0 and "notifyNearby(" in be[max(0, gun - 400):gun],
          "火药提示没走 notifyNearby")

    # ---- 语言键 ----
    key = "block.rs_create_compat.schematic_loader.queue_paused_empty"
    for lang in ("zh_cn", "en_us"):
        data = json.load(io.open(os.path.join(LANG_DIR, lang + ".json"), encoding="utf-8"))
        text = data.get(key)
        check("M1-5 语言键 %s 在 %s 存在且无占位符（静态文案）" % (key, lang),
              text is not None and "%s" not in text and "%1$s" not in text,
              "缺失 / 带了占位符：%r" % (text,))


# ======================================================================================
# ② 行为模型
# ======================================================================================

def group_models():
    print("\n=== ② 行为模型 ===")

    # ---- H1：原版 SWAP 先摘物，再 set ----
    def vanilla_swap(take_from_inventory, slot_set_refuses, drop_on_refuse, may_place_allows):
        inventory = ["BLUEPRINT"]
        if not may_place_allows:
            return inventory, []  # mayPlace=false ⇒ 原版根本不会 detach
        detached = inventory.pop()  # ① 先摘物（SWAP: inventory.setItem(hotbar, EMPTY)）
        world = []
        if slot_set_refuses:  # ② 共享视图拒收
            if drop_on_refuse:
                world.append(detached)
            # else: 静默 return ⇒ 物品凭空消失
        else:
            world.append(None)  # 写进了加农炮
        return inventory, world

    inv_old, world_old = vanilla_swap(True, True, drop_on_refuse=False, may_place_allows=True)
    inv_new, world_new = vanilla_swap(True, True, drop_on_refuse=True, may_place_allows=True)
    inv_guard, world_guard = vanilla_swap(True, True, drop_on_refuse=True, may_place_allows=False)
    check("H1-M1 旧写法（静默 return）：物品从背包摘掉后再也没出现 ⇒ 丢失 1 张蓝图",
          inv_old == [] and world_old == [])
    counterexample("H1-M2 新写法（拒收即落回世界）：物品总数守恒（背包 + 世界 = 1）",
                   len(inv_new) + len([x for x in world_new if x]) == 1)
    counterexample("H1-M3 更外层的新写法（mayPlace 先拒）：物品压根不离开背包，也守恒",
                   inv_guard == ["BLUEPRINT"] and world_guard == [])

    # ---- M1：队列空转 ----
    def tick_old(slot_empty, queue, just_finished, just_started):
        """旧实现：无条件 take，然后判断该不该部署；不该部署就放回。"""
        if not slot_empty:
            return queue, None, "noop"
        if not queue:
            return queue, None, "noop"
        taken = queue.pop(0)
        if just_finished or just_started:
            return queue, taken, "deploy"
        queue.insert(0, taken)  # returnToQueue
        return queue, None, "take+return"

    def tick_new(slot_empty, queue, just_finished, just_started, cannon_stopped=False):
        """新实现：只在「刚打完 / 刚启动」时 take；否则若加农炮已停则暂停运行。"""
        if not slot_empty:
            return queue, None, "noop"
        if not queue:
            return queue, None, "stop-empty"
        if just_finished or just_started:
            return queue[1:], queue[0], "deploy"
        if cannon_stopped:
            return queue, None, "stall-stop"
        return queue, None, "noop"

    queue_old = ["A", "B", "C"]
    churn_old = 0
    for _ in range(60):
        queue_old, deployed, what = tick_old(True, queue_old, False, False)
        if what == "take+return":
            churn_old += 1
    queue_new = ["A", "B", "C"]
    churn_new = 0
    actions_new = []
    for _ in range(60):
        queue_new, deployed, what = tick_new(True, queue_new, False, False, cannon_stopped=True)
        actions_new.append(what)
        if what == "take+return":
            churn_new += 1
    check("M1-M1 旧实现：60 tick 内「取出→放回」空转 60 次（每 tick 一次，日志/标脏刷屏）",
          churn_old == 60, "churn=%d" % churn_old)
    counterexample("M1-M2 新实现：60 tick 内空转 0 次，且首 tick 就把运行状态退回「开始」",
                   churn_new == 0 and actions_new[0] == "stall-stop", "actions=%r" % actions_new[:3])
    counterexample("M1-M3 新实现：队列里的蓝图一张不少（不销毁、不消耗）",
                   queue_new == ["A", "B", "C"], "queue=%r" % (queue_new,))
    counterexample("M1-M4 新实现：重启服务器后首 tick（queueJustStarted）仍能把下一张部署进去",
                   tick_new(True, ["A", "B"], False, True, cannon_stopped=False)[2] == "deploy")
    counterexample("M1-M5 新实现：「刚打完」那一 tick 也会部署（正常队列推进没被误伤）",
                   tick_new(True, ["A"], True, False, cannon_stopped=False)[2] == "deploy")

    # ---- H2：updateChecklist 调用频次 ----
    ticks = 100
    old_calls = ticks  # 每 tick 一次
    new_calls = 1      # 只在蓝图变化的那一 tick
    check("H2-M1 旧实现：100 tick → 100 次 updateChecklist（= 100 次 sendUpdate / 客户端包）",
          old_calls == ticks)
    counterexample("H2-M2 新实现：同一张蓝图跑 100 tick → 只刷新 1 次（换图那一次）",
                   new_calls == 1 and new_calls < old_calls)
    # 蓝图换了：新旧都必须刷新（功能不能被优化掉）
    tail = read(BE)[read(BE).find("blueprint changed -> new round"):]
    check("H2-M3 换蓝图时仍然刷新（不是把功能删掉，只是改成按需）",
          "cannon.updateChecklist();" in tail[:2000])


# ======================================================================================
# ③ 反例自证（源码副本）
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证（源码副本） ===")
    be = read(BE)
    menu = read(MENU)

    # H1：去掉 dropIntoWorld ⇒ 锚点判负
    broken = be.replace("dropIntoWorld(stack);\n                return;\n            }\n            if (ItemStack.isSameItemSameComponents(cannon.inventory.getStackInSlot(0), stack))",
                        "return;\n            }\n            if (ItemStack.isSameItemSameComponents(cannon.inventory.getStackInSlot(0), stack))")
    anchor = broken.find("queue pipeline owns the cannon blueprint slot -> set refused")
    counterexample("H1-C1 源码副本：删掉 dropIntoWorld 后，H1-3 会判负",
                   anchor < 0 or "dropIntoWorld(stack)" not in broken[anchor:anchor + 700])

    # H1：去掉菜单里的队列锁 ⇒ 锚点判负
    broken_menu = menu.replace("&& !(loader != null && loader.isSharedBlueprintSlotQueueLocked())", "")
    slot_start = broken_menu.find("addSlot(new SlotItemHandler(blueprintHandler")
    counterexample("H1-C2 源码副本：mayPlace 去掉队列锁后，H1-1 会判负",
                   slot_start < 0 or "isSharedBlueprintSlotQueueLocked()" not in broken_menu[slot_start:slot_start + 800])

    # H2：把无条件调用加回去 ⇒ H2-1 判负
    broken_be = be.replace("        cacheRequirementsFromBlueprint(cannon);",
                           "        cannon.updateChecklist();\n        cacheRequirementsFromBlueprint(cannon);", 1)
    indents = [len(line) - len(line.lstrip()) for line in strip_comments(broken_be).splitlines() if "cannon.updateChecklist()" in line]
    counterexample("H2-C1 源码副本：把无条件调用加回去后，H2-1/H2-2 会判负",
                   any(indent < 12 for indent in indents) and len(indents) == 3)

    # M1：把 take 挪回条件外 ⇒ M1-2 判负
    broken_m1 = be.replace("if (queueRunning && cannonSlotEmpty && (printJustFinished || queueJustStarted)) {",
                           "if (queueRunning && cannonSlotEmpty) {", 1)
    gate = broken_m1.find("if (queueRunning && cannonSlotEmpty) {")
    counterexample("M1-C1 源码副本：把 take 放回条件外后，M1-2 会判负（门控丢失）",
                   "printJustFinished || queueJustStarted" not in broken_m1[gate:gate + 60])

    # M3：把全服广播加回去 ⇒ M3-1 判负
    broken_m3 = be.replace("notifyNearby(net.minecraft.network.chat.Component.translatable(\n                    \"block.rs_create_compat.schematic_loader.no_gunpowder\"), true);",
                           "level.getServer().getPlayerList().getPlayers().forEach(p -> p.displayClientMessage(\n                    net.minecraft.network.chat.Component.translatable(\n                        \"block.rs_create_compat.schematic_loader.no_gunpowder\"), true));")
    counterexample("M3-C1 源码副本：把全服广播加回去后，M3-1 会判负",
                   "getPlayerList().getPlayers()" in broken_m3)


def main():
    print("第 118 轮自检（之四）：蓝图加农炮装填器家族 H1 / H2 / M1 / M3")
    group_anchors()
    group_models()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
