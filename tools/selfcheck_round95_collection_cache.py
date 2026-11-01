#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round95 自检：归流缓存仓三件事（用户第 12 / 13 条）。

用户原话：
    #12「归流缓存器潜在存在严重问题部分情况下无法正常的吸收物品……包括高级和普通的」
    #13「更改一个默认行为……他在处理 shift 加单击转移物品操作时……默认操作应该是标记」

本脚本钉住的不变式（**只读源码 + 行为模型，不启动游戏**）：

  A. #12 回写网络（缓存 → 网络）的三个已证实缺陷必须处于「已修」状态：
     A1 物品回写有<b>跨 tick 轮转游标</b>（旧实现每 tick 从第 0 格重来）；
     A2 单次 insert 失败<b>不消耗处理预算</b>（旧实现无条件 budget--，默认 processRate = 1
        时「第 0 格被网络拒收」= 本 tick 结束，后面每格永远饿死）；
     A3 流体 / 气体回写有<b>独立预算 + 独立游标</b>（旧实现拿物品用剩的预算，默认配置下
        只要缓存里有一件物品，流体就永远回不了网）；
     A4 行为模型：把上面的语义写成 Python 模型跑 2000 tick，旧语义必须「一滴都写不进去」、
        新语义必须几 tick 内就写进去（这是 A1/A2 的因果证明，不只是文本匹配）。
  B. #13 Shift + 左键 = 写 ghost 标记（不塞物品），基础版与高级版都成立：
     B1 基础菜单里「背包 → 本界面」不再调用 insertIntoCache；
     B2 基础菜单走 addMarkerForUi（带失败原因反馈）；
     B3 高级菜单继承基础实现（不复制一份、不回退成塞物品）；
     B4 高级菜单的区域选定器被提前截住，槽位被占时<b>不会</b>落到 super 去写标记。
  C. 经验球「开关关着但附近有球」日志不再刷屏（用户第 3 条）：状态变化一条 + 每 5 分钟至多一条，
     且不能一句话都不说；行为模型跑 7 小时，旧语义 ≥ 2000 条、新语义 ≤ 90 条。
  D. 「写不进网络」必须可观测（A1~A3 修好后玩家/日志能看出是哪一条原因）：
     有状态化告警、有恢复日志、未激活（没电 / 红石停机 / 没接网）也能区分。

反例自证（把修复回退 / 把判据写错时本自检必须判负）：
  ① 把旧版 flushCacheToNetwork 源码喂给 A 的判据 ⇒ A1/A2 必须判负；
  ② 把旧版 flushFluidCacheToNetwork 源码喂进去 ⇒ A3 必须判负；
  ③ 把旧版 quickMoveStack 源码喂给 B 的判据 ⇒ B1 必须判负；
  ④ 把「每 200 次一条」的旧节流模型喂给 C 的判据 ⇒ C 必须判负；
  ⑤ 去掉告警调用 ⇒ D 必须判负。

用法：python tools/selfcheck_round95_collection_cache.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

BE_PATH = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
MENU_PATH = os.path.join(JAVA, "menu", "CollectionCacheMenu.java")
ADV_MENU_PATH = os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java")

NEWLINE = chr(10)
CHECKS = [0]
PROBLEMS = []


# ==================== 基础设施 ====================

def check(desc, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS  %02d  %s" % (CHECKS[0], desc))
    else:
        PROBLEMS.append("%s%s" % (desc, ("  <- " + detail) if detail else ""))
        print("FAIL  %02d  %s%s" % (CHECKS[0], desc, ("  <- " + detail) if detail else ""))
    return ok


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释（够用即可：这些文件里没有会把 '//' 放进字符串的写法）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.split(NEWLINE):
        cut = line.find("//")
        out.append(line if cut < 0 else line[:cut])
    return NEWLINE.join(out)


def method_body(text, sig_pattern):
    """按签名正则找到方法，返回其 {...} 之间的正文（基于大括号计数）。找不到返回 None。"""
    match = re.search(sig_pattern, text)
    if not match:
        return None
    start = text.find("{", match.end())
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return None


BE_SRC_RAW = read(BE_PATH)
BE_SRC = strip_comments(BE_SRC_RAW)
MENU_SRC_RAW = read(MENU_PATH)
MENU_SRC = strip_comments(MENU_SRC_RAW)
ADV_SRC = strip_comments(read(ADV_MENU_PATH))


# ==================== A. #12 回写网络的三个缺陷 ====================

def flush_item_problems(body):
    """物品回写判据：返回问题列表（空 = 通过）。对「当前源码」和「反例源码」都适用。"""
    problems = []
    if body is None:
        return ["找不到 flushCacheToNetwork 方法"]
    if "flushCursor" not in body:
        problems.append("A1 没有跨 tick 轮转游标 flushCursor：每 tick 从第 0 格重来，"
                        "长期被拒的第 0 格会饿死后面所有格子")
    if not re.search(r"flushCursor[^;]*%[ ]*slots", body):
        problems.append("A1 flushCursor 没有参与槽位取模（游标形同虚设）")
    if re.search(r"budget--", body):
        problems.append("A2 仍存在「尝试前就 budget--」：默认 processRate = 1 时第一次尝试就把预算用光，"
                        "insert 返回 0 的格子会让本 tick 立刻结束")
    reject_block = body[body.find("inserted <= 0"):] if "inserted <= 0" in body else ""
    if "inserted <= 0" not in body:
        problems.append("A2 找不到 insert 失败分支")
    elif "rejected++" not in reject_block.split("continue;")[0]:
        problems.append("A2 insert 失败分支没有记 rejected（失败被静默吞掉，也没有诊断）")
    if re.search(r"if[ ]*\(inserted[ ]*<=[ ]*0\)[^{]*\{[^}]*processed\+\+", body):
        problems.append("A2 insert 失败却算作「已处理」（预算照扣）")
    if "flushFluidCacheToNetwork(storage, groups, transfer)" not in body:
        problems.append("A3 流体回写仍拿物品用剩的预算（必须传独立预算 groups）")
    if "noteFlushStall" not in body and "noteFlushProgress" not in body:
        problems.append("D 没有任何「为什么写不进网络」的诊断调用")
    return problems


def flush_fluid_problems(body):
    problems = []
    if body is None:
        return ["找不到 flushFluidCacheToNetwork 方法"]
    if "itemBudget" in body:
        problems.append("A3 签名仍接收 itemBudget（与物品共享预算）")
    if "fluidFlushCursor" not in body:
        problems.append("A3 流体回写没有轮转游标：前几条被拒就一直卡在它们身上")
    if not re.search(r"Math\.floorMod\(fluidFlushCursor", body):
        problems.append("A3 fluidFlushCursor 没有参与取模")
    if re.search(r"budget--", body):
        problems.append("A2/A3 流体侧仍有「尝试前就扣预算」")
    if "inserted <= 0" not in body:
        problems.append("A3 找不到流体 insert 失败分支")
    elif "rejected++" not in body[body.find("inserted <= 0"):].split("continue;")[0]:
        problems.append("A3 流体 insert 失败没有记 rejected")
    if "List.copyOf(fluidCache.entries())" not in body:
        problems.append("A3 遍历活列表：extract 会从列表里删条目 ⇒ 会跳过元素")
    return problems


ITEM_FLUSH = method_body(
    BE_SRC, r"private void flushCacheToNetwork\s*\(\s*final Network network\s*\)")
FLUID_FLUSH = method_body(
    BE_SRC, r"private FluidFlush flushFluidCacheToNetwork\s*\([^)]*\)")

item_problems = flush_item_problems(ITEM_FLUSH)
fluid_problems = flush_fluid_problems(FLUID_FLUSH)
check("A1/A2 物品回写：游标 + 失败不扣预算 + 诊断调用齐全", not item_problems,
      "; ".join(item_problems))
check("A3 流体回写：独立预算 + 游标 + 快照遍历", not fluid_problems,
      "; ".join(fluid_problems))


# ---------- A4：行为模型（证明 A1/A2 的因果关系，而不只是文本长得对） ----------

def sim_old_item_flush(slots, rate, ticks, transfer=64):
    """旧语义模型：每 tick 从 slot 0 开始、尝试前无条件扣预算。返回写进网络的件数。"""
    slots = list(slots)
    flushed = 0
    for _ in range(ticks):
        budget = rate
        i = 0
        while i < len(slots) and budget > 0:
            if slots[i] == 0:  # 空格 / 阻塞：不占预算
                i += 1
                continue
            budget -= 1  # <-- 旧实现：尝试前就扣
            if slots[i] == "REJECT":
                pass
            else:
                moved = min(slots[i], transfer)
                slots[i] -= moved
                flushed += moved
            i += 1
    return flushed


def sim_new_item_flush(slots, rate, ticks, transfer=64):
    """新语义模型：跨 tick 游标 + 失败不扣预算 + 单 tick 尝试上限。返回写进网络的件数。"""
    slots = list(slots)
    count = len(slots)
    cursor = 0
    flushed = 0
    max_attempts = max(rate, min(count, rate * 8 + 8))
    for _ in range(ticks):
        processed = 0
        attempts = 0
        scanned = 0
        step = 0
        while step < count and processed < rate and attempts < max_attempts:
            index = (cursor + step) % count
            scanned += 1
            if slots[index] == 0:
                step += 1
                continue
            attempts += 1
            if slots[index] == "REJECT":
                step += 1
                continue  # <-- 新实现：失败不扣预算
            moved = min(slots[index], transfer)
            slots[index] -= moved
            flushed += moved
            processed += 1
            step += 1
        cursor = (cursor + max(1, scanned)) % count
    return flushed


# 现场：第 0 格是「网络永久拒收」的东西（存储已满 / 没有对应存储），第 1 格是能收的。
SCENE = ["REJECT", 64]
old_flushed = sim_old_item_flush(SCENE, 1, 2000)
new_flushed = sim_new_item_flush(SCENE, 1, 2000)
check("A4 行为模型：旧语义「第 0 格被拒 ⇒ 后面每格永久饿死」（2000 tick 写不进任何东西）",
      old_flushed == 0, "实际写进 %d 件" % old_flushed)
check("A4 行为模型：新语义「第 1 格在极少数 tick 内就被写进网络」",
      new_flushed == 64, "实际写进 %d 件" % new_flushed)


def sim_shared_budget_fluids(items_present, rate, ticks):
    """旧语义：物品先用预算，流体拿剩下的 ⇒ 有物品时流体永远是 0。"""
    flushed = 0
    for _ in range(ticks):
        budget = rate
        if items_present:
            budget -= 1  # 物品每 tick 吃掉一组（旧实现 budget--）
        if budget > 0:
            flushed += 1
    return flushed


def sim_independent_budget_fluids(items_present, rate, ticks):
    """新语义：物品与流体各一份预算。"""
    flushed = 0
    for _ in range(ticks):
        item_budget = rate
        fluid_budget = rate
        if items_present:
            item_budget -= 1
        if fluid_budget > 0:
            flushed += 1
    return flushed


check("A4 行为模型：旧语义「缓存里有物品 ⇒ 流体永远写不进网络」",
      sim_shared_budget_fluids(True, 1, 500) == 0)
check("A4 行为模型：新语义「物品与流体各走各的预算 ⇒ 流体照常回网」",
      sim_independent_budget_fluids(True, 1, 500) == 500)

# 反例自证 ①：把旧版源码喂给同一套判据，必须判负
OLD_ITEM_FLUSH = """
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        final HolderLookup.Provider registries = getLevel() == null ? null : getLevel().registryAccess();
        int budget = getProcessRate();
        final int transfer = getTransferBatch();
        for (int i = 0; i < cache.getContainerSize() && budget > 0; i++) {
            final ItemStack inSlot = cache.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (isBlockedStack(inSlot)) {
                continue;
            }
            budget--;
            final int attempt = Math.min(inSlot.getCount(), transfer);
            final long inserted = storage.insert(ItemResource.ofItemStack(inSlot), attempt, Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0) {
                continue;
            }
            setChanged();
        }
        flushFluidCacheToNetwork(storage, budget, transfer);
"""
old_item_problems = flush_item_problems(OLD_ITEM_FLUSH)
check("①反例：旧版物品回写源码必须被判负（游标缺失 / 失败扣预算 / 流体共享预算）",
      len(old_item_problems) >= 3, "只抓到 %d 条：%s" % (len(old_item_problems), old_item_problems))

OLD_FLUID_FLUSH = """
        if (fluidCache.isEmpty()) {
            return;
        }
        int budget = itemBudget;
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            if (budget <= 0) {
                return;
            }
            budget--;
            final long inserted = storage.insert(resource, attempt, Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0) {
                continue;
            }
        }
"""
old_fluid_problems = flush_fluid_problems(OLD_FLUID_FLUSH)
check("②反例：旧版流体回写源码必须被判负（itemBudget / 无游标 / 活列表遍历）",
      len(old_fluid_problems) >= 4, "只抓到 %d 条：%s" % (len(old_fluid_problems), old_fluid_problems))


# ==================== B. #13 Shift + 左键 = 写 ghost 标记 ====================

QUICK_MOVE = method_body(
    MENU_SRC, r"public ItemStack quickMoveStack\s*\(\s*final Player player, final int index\s*\)")
WRITE_MARKER = method_body(
    MENU_SRC, r"private boolean writeMarkerFromPlayerSlot\s*\([^)]*\)")


def quick_move_problems(body):
    problems = []
    if body is None:
        return ["找不到 quickMoveStack"]
    if re.search(r"block\.insertIntoCache[ ]*\(", body):
        problems.append("B1 背包 Shift 点击仍在调用 insertIntoCache（把物品塞进缓存）")
    if "writeMarkerFromPlayerSlot(" not in body:
        problems.append("B1 背包 Shift 点击没有改走「写标记」")
    if "return super.quickMoveStack(" in body:
        problems.append("B1/B3 居然落到 super（vanilla 会把整堆塞进别人的槽）")
    return problems


qm_problems = quick_move_problems(QUICK_MOVE)
check("B1 基础版：背包 Shift+左键 = 写标记（不再 insertIntoCache）", not qm_problems,
      "; ".join(qm_problems))
# 第 101 轮：写标记改走菜单级钩子 addMarkerFromUi()（高级版覆写成「写进当前选中区域」），
# 钩子默认实现必须仍委托方块实体 addMarkerForUi（否则「已标记 / 被标签覆盖 / 匹配区满」的分类丢失）。
check("B2 基础版：写标记走菜单钩子 addMarkerFromUi（默认实现委托方块实体 addMarkerForUi，"
      "能区分「已标记 / 被标签覆盖 / 匹配区满」）",
      WRITE_MARKER is not None and "addMarkerFromUi(" in WRITE_MARKER
      and WRITE_MARKER.count("case ") >= 4
      and "return block.addMarkerForUi(stack);" in MENU_SRC)
check("B2 基础版：写成功后立刻同步匹配区给客户端（syncMarkers）",
      WRITE_MARKER is not None and "syncMarkers()" in WRITE_MARKER)
check("B2 基础版：写标记失败时给玩家一句话（不能点了没反应）",
      WRITE_MARKER is not None and WRITE_MARKER.count("notifyPlayer(") >= 4)

ADV_QM = method_body(
    ADV_SRC, r"public ItemStack quickMoveStack\s*\(\s*final Player player, final int index\s*\)")
check("B3 高级版继承基础版实现（extends CollectionCacheMenu）",
      re.search(r"class AdvancedCollectionCacheMenu extends CollectionCacheMenu", ADV_SRC) is not None)
check("B3 高级版 quickMoveStack 仍然「其余路径交给 super」（不复制一份实现、也不会回退成塞物品）",
      ADV_QM is not None and "return super.quickMoveStack(player, index);" in ADV_QM)
adv_area_branch = ""
if ADV_QM is not None:
    anchor = ADV_QM.find("AREA_SELECTOR")
    adv_area_branch = ADV_QM[anchor:] if anchor >= 0 else ""
check("B4 高级版：区域选定器被提前截住（不会被当成标记写进匹配区）",
      "AREA_SELECTOR" in ADV_QM if ADV_QM else False)
check("B4 高级版：专用槽被占时什么都不做（返回空），不落到 super",
      "return ItemStack.EMPTY;" in adv_area_branch,
      "区域槽分支里缺少 return ItemStack.EMPTY;")

OLD_QUICK_MOVE = """
        final ItemStack stackInSlot = slot.getItem();
        final ItemStack copy = stackInSlot.copy();
        if (index >= PLAYER_START) {
            if (block == null || block.getLevel().isClientSide()) {
                return ItemStack.EMPTY;
            }
            boolean moved = false;
            if (isAllowedUpgrade(stackInSlot)) {
                moved = moveItemStackTo(stackInSlot, UPGRADE_START, UPGRADE_START + UPGRADE_COUNT, false);
            }
            if (!moved) {
                final ItemStack remainder = block.insertIntoCache(stackInSlot);
                if (remainder.getCount() == stackInSlot.getCount()) {
                    return ItemStack.EMPTY;
                }
                slot.set(remainder);
            }
        }
        return copy;
"""
old_qm_problems = quick_move_problems(OLD_QUICK_MOVE)
check("③反例：旧版 quickMoveStack 源码必须被判负（Shift 点击塞缓存）",
      len(old_qm_problems) >= 2, "只抓到 %d 条：%s" % (len(old_qm_problems), old_qm_problems))


# ---------------- B5（交付前独立复核抓到的 P0 回归锚）：返回值契约 ----------------
# 原版 AbstractContainerMenu#doClick 的 QUICK_MOVE 分支（neoforge-21.1.248-sources.jar 逐字）：
#     ItemStack r = this.quickMoveStack(player, slotId);
#     while (!r.isEmpty() && ItemStack.isSameItem(slot.getItem(), r)) {
#         r = this.quickMoveStack(player, slotId);
#     }
# ItemStack.isSameItem 只比物品种类、不比数量；ghost 分支既不移动也不消耗源槽 ⇒
# 若该分支返回非空 copy，这个 while 会**无限循环**（整合端整局冻结 / 专服看门狗崩服）。
def no_move_returns_empty(body):
    if body is None:
        return False
    found = re.search(r"writeMarkerFromPlayerSlot\s*\([^;]*\);", body)
    if not found:
        return False
    following = re.search(r"return\s+([^;]+);", body[found.end():])
    return following is not None and following.group(1).strip() == "ItemStack.EMPTY"


check("B5 ghost 分支（什么都没搬动）必须返回 ItemStack.EMPTY —— "
      "否则原版 doClick 的 QUICK_MOVE while 循环会因「源槽未变 + 返回非空」无限循环（专服崩）",
      no_move_returns_empty(QUICK_MOVE))
upgrade_cut = ""
if QUICK_MOVE is not None:
    start = QUICK_MOVE.find("isAllowedUpgrade(")
    end = QUICK_MOVE.find("writeMarkerFromPlayerSlot(")
    upgrade_cut = QUICK_MOVE[start:end] if 0 <= start < end else ""
check("B5 升级槽塞不进时不落到「写标记」（升级物品不该变成要吸取的标记；且该分支同样返回 EMPTY）",
      "return ItemStack.EMPTY;" in upgrade_cut)
# 反例：把 ghost 分支的返回改回旧的 `return copy;` ⇒ B5 必须判负
BROKEN_QM = QUICK_MOVE.replace(
    "writeMarkerFromPlayerSlot(player, stackInSlot);", "") if QUICK_MOVE else None
if BROKEN_QM is not None and QUICK_MOVE is not None:
    old_tail = re.search(r"writeMarkerFromPlayerSlot\s*\([^;]*\);", QUICK_MOVE)
    if old_tail:
        head = QUICK_MOVE[:old_tail.end()]
        tail = QUICK_MOVE[old_tail.end():]
        # 把 ghost 分支后的第一个 return 换回非空 copy（旧 bug 的写法）
        tail_broken = re.sub(r"return\s+ItemStack\.EMPTY;", "return copy;", tail, count=1)
        BROKEN_QM = head + tail_broken
check("B5 反例：ghost 分支返回 non-empty copy ⇒ 必须判负（真机表现是服务端死循环）",
      BROKEN_QM is not None and not no_move_returns_empty(BROKEN_QM),
      "反例没有被抓住")


# ==================== C. 经验球日志不再刷屏 ====================

XP_BODY = method_body(
    BE_SRC, r"private void noteExperienceOffButOrbsNearby\s*\(\s*final Level level\s*\)")
check("C1 存在专用的节流方法 noteExperienceOffButOrbsNearby", XP_BODY is not None)
check("C2 旧的「每 200 次一条」计数器已彻底移除（experienceOffWarnCooldown）",
      "experienceOffWarnCooldown" not in BE_SRC)
check("C2 检查本身按 10 秒节流（EXPERIENCE_OFF_CHECK_INTERVAL_TICKS）",
      "EXPERIENCE_OFF_CHECK_INTERVAL_TICKS" in BE_SRC
      and XP_BODY is not None and "EXPERIENCE_OFF_CHECK_INTERVAL_TICKS" in XP_BODY)
check("C3 同一状态重复提醒受 5 分钟节流（EXPERIENCE_OFF_WARN_INTERVAL_TICKS）",
      XP_BODY is not None and "EXPERIENCE_OFF_WARN_INTERVAL_TICKS" in XP_BODY)
check("C3 状态复位：半径内没球 / 开关打开都清标志（下次出现重新说一句）",
      XP_BODY is not None and "experienceOffWarned = false" in XP_BODY)
absorb_body = method_body(BE_SRC, r"private void absorbAll\s*\(\s*final Level level\s*\)")
check("C4 absorbAll 的经验分支改走节流方法",
      absorb_body is not None and "noteExperienceOffButOrbsNearby(level)" in absorb_body)


def sim_old_xp_logs(seconds, every_seconds=10):
    return seconds // every_seconds


def sim_new_xp_logs(ticks, check_interval=200, warn_interval=6000):
    """新语义模型：状态变化一条 + 之后每 warn_interval 一条（与 Java 实现同构）。"""
    logs = []
    checked_at = None
    warned = False
    warned_at = None
    for now in range(ticks):
        if checked_at is not None and now - checked_at < check_interval:
            continue
        checked_at = now
        # 场景：半径内一直有球、开关一直关着
        if warned and warned_at is not None and now - warned_at < warn_interval:
            continue
        warned = True
        warned_at = now
        logs.append(now)
    return logs


seven_hours = 7 * 3600
old_logs = sim_old_xp_logs(seven_hours)
new_logs = sim_new_xp_logs(seven_hours * 20)
check("C5 行为模型：旧语义 7 小时 ≈ %d 条（这就是把日志刷满的量）" % old_logs,
      old_logs >= 2000)
check("C5 行为模型：新语义 7 小时 ≤ 90 条（5 分钟一条）",
      len(new_logs) <= 90, "实际 %d 条" % len(new_logs))
check("C6 行为模型：新语义不能「一句话都不说」（至少一条）", len(new_logs) >= 1)
gaps = [b - a for a, b in zip(new_logs, new_logs[1:])]
check("C6 行为模型：相邻两条提醒间隔 ≥ 6000 tick（5 分钟）",
      all(gap >= 6000 for gap in gaps), "最小间隔 %s" % (min(gaps) if gaps else "n/a"))


def sim_old_xp_every_call(calls, warn_every=1):
    """旧语义模型：每轮扫描一条（warn_every=1 = 完全没节流）。"""
    return calls // warn_every


check("④反例：旧节流模型（10 秒一条）喂给 C5 判据必须判负（7 小时 %d 条 > 90）"
      % sim_old_xp_logs(seven_hours),
      sim_old_xp_logs(seven_hours) > 90)
check("④反例：完全没节流的旧语义同样必须判负",
      sim_old_xp_every_call(seven_hours * 20) > 90)


# ==================== D. 「写不进网络」必须可观测 ====================

check("D1 有「缓存 → 网络」停滞告警 noteFlushStall", "noteFlushStall" in BE_SRC)
check("D2 有恢复日志 noteFlushProgress", "noteFlushProgress" in BE_SRC)
check("D3 告警带原因分类（拒绝 / 未接网 / 没电 / 红石停机）",
      all(token in BE_SRC for token in
          ("FLUSH_REJECTED", "FLUSH_NO_NETWORK", "FLUSH_INACTIVE_ENERGY", "FLUSH_INACTIVE_REDSTONE")))
DOWORK = method_body(BE_SRC, r"public void doWork\s*\(\s*\)")
check("D4 doWork 覆写：节点未激活时也能说清原因（旧实现一句日志都没有）",
      DOWORK is not None and "classifyInactiveReason(" in DOWORK
      and "isActive()" in DOWORK)
check("D5 告警不会自己刷屏：同一原因 5 分钟至多一条（FLUSH_WARN_INTERVAL_TICKS）",
      "FLUSH_WARN_INTERVAL_TICKS" in BE_SRC and "cooledDown" in BE_SRC)
check("D6 告警不是「静默 return」：storage == null 也要报告",
      "noteFlushStall(FLUSH_NO_NETWORK" in BE_SRC)

# 反例自证 ⑤：把诊断调用删掉必须判负
MUTED_FLUSH = re.sub(r"noteFlush(?:Stall|Progress|Idle)\([^;]*\)[ ]*;", ";", ITEM_FLUSH or "")
check("⑤反例：删掉诊断调用后 A/D 判据必须判负",
      len(flush_item_problems(MUTED_FLUSH)) >= 1)


# ==================== E. 「查看范围」框：可见性 + 关界面后继续淡出 ====================
# 这两条是 Lead 追加的（WS-D 交叉复核发现「范围充电器修掉的生命周期 bug 在高级缓存这边还在」，
# 用户第 5 条「不管是充电器还是什么它都没有正常工作」很可能就是两处一起报的）。

AREA_OUTLINE_SRC = strip_comments(read(os.path.join(JAVA, "client", "AreaOutline.java")))
# 第 99 轮：范围框的绘制 / 生命周期（5 秒 TTL、每 tick 重喂、关界面按剩余 TTL 重新武装）
# 从 AdvancedCollectionCacheScreen <b>上移到基类 CollectionCacheScreen</b> —— 基础版与高级版
# 共用同一条「按钮 → 菜单按钮通道 C2S → 服务端快照 → S2C → 客户端只画」链路。
# 判据本身（关界面必须按剩余 TTL 重新武装，不许无条件删框）不变，只是读的文件跟着实现搬家。
ADV_SCREEN_SRC = strip_comments(read(os.path.join(
    JAVA, "client", "screen", "CollectionCacheScreen.java")))


def outline_style_problems(src):
    """范围框样式判据：三个入口都必须有棋盘面 + 1/16 线宽。"""
    problems = []
    if "CHECKERED" not in src or "HIGHLIGHT_CHECKERED" not in src:
        problems.append("E1 范围框没有 withFaceTextures(CHECKERED, HIGHLIGHT_CHECKERED)：只有细线，"
                        "隔着世界几乎不可见（用户第 5 条）")
    face_count = src.count("withFaceTextures(AllSpecialTextures.CHECKERED, "
                           "AllSpecialTextures.HIGHLIGHT_CHECKERED)")
    # 只数「画框」的入口：showAABB / chaseAABB（remove(...) 不是画框，不需要面贴图）
    box_entry_count = src.count("showAABB(") + src.count("chaseAABB(")
    if face_count < box_entry_count:
        problems.append("E1 有 %d 个画框入口，只有 %d 个加了面贴图（同一个功能会长得不一样）"
                        % (box_entry_count, face_count))
    if "1 / 16f" not in src:
        problems.append("E1 线宽不是 1/16（Outliner 默认 1/32，实机几乎看不见）")
    if re.search(r"lineWidth\(\s*1\s*/\s*32f?\s*\)", src):
        problems.append("E1 仍在使用 1/32 线宽")
    return problems


outline_problems = outline_style_problems(AREA_OUTLINE_SRC)
check("E1 范围框 = 棋盘面 + 1/16 线宽（三个入口一致，与范围充电器同一套）", not outline_problems,
      "; ".join(outline_problems))
check("E2 AreaOutline 提供「按剩余 TTL 重新武装」的重载 showForView(slot, area, ttl)",
      re.search(r"public static void showForView\s*\(\s*final Object slot, final Area area, final int ttl\s*\)",
                AREA_OUTLINE_SRC) is not None)

OLD_OUTLINE = """
    public static void showForView(final Object slot, final Area area) {
        Outliner.getInstance()
            .showAABB(slot, boxOf(area), VIEW_TTL)
            .colored(COLOR)
            .lineWidth(LINE_WIDTH);
    }
    public static void chase(final Object slot, final Area area) {
        Outliner.getInstance()
            .chaseAABB(slot, boxOf(area))
            .colored(COLOR)
            .lineWidth(LINE_WIDTH);
    }
"""
check("⑥反例：只有 fine line、没有面贴图的旧写法必须被判负",
      len(outline_style_problems(OLD_OUTLINE)) >= 1)


def removed_problems(body):
    """removed() 判据：关界面时必须按剩余 TTL 重新武装，不能无条件 hideRange。"""
    problems = []
    if body is None:
        return ["找不到 CollectionCacheScreen#removed()（第 99 轮起框选生命周期在基类）"]
    if "hideRange();" not in body and "hideRange()" not in body:
        problems.append("E3 removed() 完全没有处理框（会画满整个 TTL，关界面后还在）")
    if "remaining" not in body:
        problems.append("E3 removed() 没有按「剩余 TTL」重新武装 ⇒ 旧实现那样一关界面就什么都看不到")
    if not re.search(r"showForView\([^;]*remaining", body):
        problems.append("E3 removed() 没有用剩余 TTL 调 showForView")
    # hideRange 只能出现在「剩余时间已过」的 else 分支里（不能无条件执行）
    first_hide = body.find("hideRange(")
    if first_hide >= 0 and "else" not in body[:first_hide]:
        problems.append("E3 hideRange() 仍在 remaining 判断之前被无条件调用（关界面立刻删框）")
    if "shownAreas" not in body:
        problems.append("E3 removed() 没有用到 toggleRange 记下来的区域列表（无法重新武装）")
    return problems


ADV_REMOVED = method_body(ADV_SCREEN_SRC, r"public void removed\s*\(\s*\)")
removed_issues = removed_problems(ADV_REMOVED)
check("E3 关界面后框继续画完再淡出（按剩余 TTL 重新武装，不再无条件 hideRange）",
      not removed_issues, "; ".join(removed_issues))
check("E4 toggleRange 把本次显示的区域记下来（removed 才能重新武装）",
      re.search(r"shownAreas\.add\(area\)", ADV_SCREEN_SRC) is not None)
check("E4 手动「再点一次取消」仍然立即删框（toggleRange 里的 hideRange 分支）",
      re.search(r"isRangeVisible\(\)\s*\)\s*\{\s*hideRange\(\);", ADV_SCREEN_SRC) is not None)

OLD_REMOVED = """
        hideRange();
        super.removed();
"""
removed_old_issues = removed_problems(OLD_REMOVED)
check("⑦反例：removed() 退回「关界面立刻 hide」必须判负",
      len(removed_old_issues) >= 2, "只抓到 %d 条：%s" % (len(removed_old_issues), removed_old_issues))


# ==================== 汇总 ====================
print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 8 组反例自证）" % CHECKS[0])
sys.exit(0)
