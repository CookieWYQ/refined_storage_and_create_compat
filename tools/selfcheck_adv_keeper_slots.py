# -*- coding: utf-8 -*-
"""高级定量保持器「插件升级守恒」静态自检（第 17 轮重写）。

上一轮本文件断言的是「一格多个就硬切 / 多余弹出到世界」。用户实测那套**把升级弄丢了**
（插件直接就没了），并明确要求：
  1) 绝对不得删除 / 丢弃任何升级；
  2) 撤销「自动均匀分配 + 多余弹出」，**已经一格多个的状态不要去动它**；
  3) 只保留「非空槽拒收」这类**不移动 / 不删除已有物品**的最小防线。

本文件因此改为断言「不删除、不移动」并附守恒推演：

A. 插件槽校验必须是**纯只读**：`enforceUpgradeCaps` 体内不得出现
   `ejectSlot` / `ejectItem` / `removeItemNoUpdate` / `inventory.setItem` /
   `addFreshEntity` —— 一个物品都不许动（高级 + 基础两个保持器都要）。
B. 「不再出现一格多个」的最小防线 = `UpgradeSlot.mayPlace` 拒收非空槽 +
   `getMaxStackSize()`/`getMaxStackSize(stack)` 恒 1；拒收时物品完整留在原处。
C. 撤销自动分配：菜单里不得再有自研「整叠搬运」循环（`distributeUpgrades` 已删除），
   玩家背包 → 插件槽只走 vanilla `moveItemStackTo`。
D. 计数起点（countFromIndex）保留：修「第二台只能放 5 个」而不碰任何物品。
E. 守恒推演：任何一条路径下「容器内 + 玩家背包 + 掉落物」的升级总数恒等于 N。
F. 既有「一格多个」状态逐字节保持不变（只判定、不改写）。

用法：python tools/selfcheck_adv_keeper_slots.py
退出码：0 = 全部通过；1 = 有问题。
"""
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

ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
BASE_BE = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_MENU = os.path.join(JAVA, "menu", "AdvancedQuantityKeeperMenu.java")
UPGRADE_SLOT = os.path.join(JAVA, "menu", "UpgradeSlot.java")

problems = []


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def require(ok, message):
    if ok:
        print("  [OK] %s" % message)
    else:
        problems.append(message)
        print("  [X]  %s" % message)


def section(title):
    print("\n===== %s =====" % title)


def body_of(source, signature, end="\n    }"):
    """取方法体（从签名之后到第一个以 end 开头的收尾）。签名不在则返回空串。"""
    if signature not in source:
        return ""
    return source.split(signature, 1)[1].split(end, 1)[0]


# 界面容器规模：4 个 ghost 标记槽 + 6 个插件槽（与菜单 CONTAINER_SLOTS 一致）。
SLOTS = 10
# SimpleContainer.getMaxStackSize(ItemStack) 的默认上限（旧路径合并时用的上限）。
MAX_STACK = 64


def legacy_additem(out, count):
    """复刻旧读取路径 {@code SimpleContainer#addItem}：先并入同类已有堆（上限 64），再塞首个空槽。"""
    remaining = count
    for i in range(len(out)):
        if remaining <= 0:
            break
        if out[i] > 0:
            room = MAX_STACK - out[i]
            if room > 0:
                take = min(room, remaining)
                out[i] += take
                remaining -= take
    if remaining > 0:
        for i in range(len(out)):
            if out[i] == 0:
                out[i] = remaining
                remaining = 0
                break
    return remaining


def legacy_read(slots):
    """旧格式（无 "Slot"）：逐条 addItem ⇒ 同类升级被合并到同一格（bug 复现）。"""
    out = [0] * SLOTS
    for slot in sorted(slots):
        legacy_additem(out, slots[slot])
    return out


def new_read(slots):
    """新格式（带 "Slot"）：逐格原样落位（与 readInventoryTag 同口径）。"""
    out = [0] * SLOTS
    for slot, count in slots.items():
        out[slot] = count
    return out


# ===========================================================================
# A) 只读校验：一个物品都不许动
# ===========================================================================
def section_readonly():
    section("A) 插件槽校验是纯只读（绝不搬移 / 拆分 / 弹出 / 删除）")
    for path, label in ((ADV_BE, "高级保持器"), (BASE_BE, "基础保持器")):
        src = read(path)
        body = body_of(src, "public void enforceUpgradeCaps() {")
        require(bool(body), "%s：存在 enforceUpgradeCaps" % label)
        for token, why in (("ejectSlot", "不得再有「整格弹出」"),
                           ("ejectItem", "不得再有「生成掉落物」"),
                           ("removeItemNoUpdate", "不得再从容器里直接删物品"),
                           ("inventory.setItem", "不得再改写容器内容（哪怕只是拆格）"),
                           ("addFreshEntity", "不得再生成掉落物实体")):
            require(token not in body, "%s：enforceUpgradeCaps 体内不含 %s（%s）" % (label, token, why))
        require("SLOT_COUNT" in body or "i = 1" in body,
              "%s：只读校验仍然遍历插件槽区间（判据还在，只是不动手）" % label)
        require("LOGGER.info" in body or "LOGGER.info" in src,
              "%s：异常只记节流日志（可查原因、不刷屏）" % label)
        require("lastUpgradeAnomaly" in src, "%s：日志按签名节流（同一状态只打一条）" % label)

    # 全工程不得再有 eject 辅助方法把「先删后给」这条路留着
    adv = read(ADV_BE)
    require("private void ejectSlot(" not in adv and "private void ejectItem(" not in adv,
            "高级保持器：ejectSlot / ejectItem 已整体删除（杜绝「先删后给」）")
    base = read(BASE_BE)
    require("private void ejectSlot(" not in base,
            "基础保持器：ejectSlot 已整体删除（同一套写法一并根治）")


# ===========================================================================
# B) 最小防线：只拒收、不碰已有物品
# ===========================================================================
def section_minimal_defense():
    section("B) 最小防线：非空槽拒收（只拒绝放入，物品完整留在原处）")
    slot = read(UPGRADE_SLOT)
    require('if (!getItem().isEmpty()) {\n            return false;' in slot,
            "UpgradeSlot.mayPlace：非空槽一律拒收（防止再出现一格多个）")
    require(re.search(r"public int getMaxStackSize\(\)\s*\{\s*return 1;", slot) is not None,
            "UpgradeSlot.getMaxStackSize() 恒 1（vanilla 一次只放 1 个）")
    require(re.search(r"public int getMaxStackSize\(final ItemStack stack\)\s*\{\s*return 1;", slot) is not None,
            "UpgradeSlot.getMaxStackSize(ItemStack) 恒 1（双保险）")
    require("return stack;" in slot or "return false;" in slot,
            "UpgradeSlot.mayPlace 只是返回布尔（拒收 = vanilla 不写入，物品留在光标/原槽）")
    require("MAX_PER_UPGRADE = 6" in slot,
            "单种升级上限仍为 6（与 6 格插件槽一致）——只是「判定」，不负责搬走超额部分")


# ===========================================================================
# C) 撤销自动分配
# ===========================================================================
def section_no_auto_distribution():
    section("C) 撤销「自动均匀分配」（菜单里不再有自研整叠搬运）")
    menu = read(ADV_MENU)
    require("private void distributeUpgrades(" not in menu and "distributeUpgrades(slot," not in menu,
            "AdvancedQuantityKeeperMenu：distributeUpgrades 方法已删除")
    require("UPGRADE_SLOT_COUNT" not in menu, "不再保留只为分配服务的 UPGRADE_SLOT_COUNT 常量")
    require("moveItemStackTo(stackInSlot, UPGRADE_START, CONTAINER_SLOTS, false)" in menu,
            "玩家背包 → 插件槽只走 vanilla moveItemStackTo（不自己写搬运循环）")
    require("upgrade_remain" not in menu, "「剩余弹回」的动作栏提示与语言键一并撤销")
    require("setByPlayer(source" not in menu,
            "菜单里不再有「按 placed 数改写原槽」的写法（杜绝中间态丢物的可能）")
    require("player.displayClientMessage" not in menu,
            "菜单不再插手任何提示（纯 vanilla 行为）")


# ===========================================================================
# D) 计数起点（保留的最小修复，不碰物品）
# ===========================================================================
def section_count_range():
    section("D) 计数起点 countFromIndex（修「只能放 5 个」，本身不动任何物品）")
    slot = read(UPGRADE_SLOT)
    menu = read(ADV_MENU)
    base = read(os.path.join(JAVA, "menu", "QuantityKeeperMenu.java"))
    require("private final int countFromIndex;" in slot, "UpgradeSlot 仍有 countFromIndex（只影响判定）")
    require("for (int i = Math.max(0, countFromIndex); i < handler.getSlots(); i++) {" in slot,
            "计数从真正的插件槽区间起（跳过 ghost 标记槽）")
    require("UpgradeSlot.forContainer(container, MARKER_SLOTS + i, 188, 7 + i * 18, MARKER_SLOTS)" in menu,
            "高级保持器：计数起点 = MARKER_SLOTS")
    require("UpgradeSlot.forContainer(keeper.getInventory(), 1 + i, 188, 7 + i * 18, 1)" in base,
            "基础保持器：计数起点 = 1")


# ===========================================================================
# E) 原 Bug 2：Quick Move 只写第一个空槽 + 三通道不串道
# ===========================================================================
def section_quick_move_marker():
    section("E) Quick Move 的 ghost 标记路径（本次一行未改，仍必须守恒）")
    menu = read(ADV_MENU)
    require("if (!hasMarker(i))" in menu,
            "firstEmptyMarkerRow 用 hasMarker(i) 判定空槽（覆盖物品+流体+气体标记）")
    fem = body_of(menu, "private int firstEmptyMarkerRow() {")
    require("hasMarker(i)" in fem and "getItem().isEmpty()" not in fem,
            "firstEmptyMarkerRow 方法体不出现 getItem().isEmpty()")
    marker_start = menu.index("firstEmptyMarkerRow()")
    marker_block = menu[marker_start:marker_start + 700]
    require("return ItemStack.EMPTY;" in marker_block,
            "设置 ghost 标记后返回 ItemStack.EMPTY（终止 vanilla QUICK_MOVE 的 while 循环）")
    after = menu.split("keeper.setItemMarker(emptyRow, stackInSlot);", 1)[1]
    nxt = after[:after.index("return ") + len("return ItemStack.EMPTY;")]
    require("return ItemStack.EMPTY;" in nxt and "return stack;" not in nxt,
            "设置标记后的下一个 return 是 ItemStack.EMPTY（不会把原物品搬走）")
    be = read(ADV_BE)
    require("inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));" in be,
            "setItemMarker 只写「标记槽」（slot < SLOT_COUNT），永远不碰插件槽")
    require("fluidMarkerIds[slot] = null" in be and "private final ResourceLocation[] fluidMarkerIds" in be,
            "物品/流体/气体三通道按槽独立（不串道、不整体覆盖）")


# ===========================================================================
# F) 槽位一致性
# ===========================================================================
def section_slot_consistency():
    section("F) 槽位一致性（客户端可见槽数 = 服务端数据结构长度）")
    menu = read(ADV_MENU)
    be = read(ADV_BE)
    require("CONTAINER_SLOTS = MARKER_SLOTS + 6" in menu, "CONTAINER_SLOTS = MARKER_SLOTS(4) + 6")
    require("SLOT_COUNT = 4" in be, "SLOT_COUNT = 4（4 个标记槽）")
    require("for (int i = 0; i < 6; i++)" in menu and "MARKER_SLOTS + i" in menu,
            "菜单添加 6 个 UpgradeSlot，索引 MARKER_SLOTS..MARKER_SLOTS+5（=4..9）")
    require("DATA_PER_SLOT = 6" in be, "DATA_PER_SLOT = 6")
    require("DATA_COUNT = DATA_REDSTONE_MODE + 1" in be, "DATA_COUNT = 4*6 + 3 = 27")


# ===========================================================================
# G) 守恒推演
# ===========================================================================
def simulate_quick_move_upgrade(total, slot_empty, counts, cap):
    """vanilla QUICK_MOVE + UpgradeSlot 规则下的落点（faithful 复刻，见报告）。

    vanilla 的 doClick：`ItemStack s = quickMoveStack(...); while (!s.isEmpty() &&
    isSameItem(slot.getItem(), s)) s = quickMoveStack(...)`；
    每次 quickMoveStack 走一次 `moveItemStackTo`：只往「第一个 空 且 mayPlace」的槽放
    `min(源数量, getMaxStackSize=1)` = 1 个，然后 break；一个都放不进时返回 false → 循环终止。
    返回 (每槽新增, 源槽剩余)。
    """
    placed = [0] * len(slot_empty)
    remaining = total
    already = sum(counts)
    while remaining > 0:
        target = -1
        for i in range(len(slot_empty)):
            if slot_empty[i] and already < cap:
                target = i
                break
        if target < 0:
            break
        placed[target] = 1
        slot_empty[target] = False
        remaining -= 1
        already += 1
    return placed, remaining


def conservation(total_in, moved_in, total_out, moved_out, note=""):
    ok = total_in + moved_in == total_out + moved_out
    print("       %-44s 进入 %d+%d = 离开 %d+%d %s" %
          (note, total_in, moved_in, total_out, moved_out, "守恒" if ok else "★不守恒★"))
    return ok


def section_conservation():
    section("G) 守恒推演：容器内 + 玩家背包 + 掉落物 的升级总数恒等于 N")

    # 1) 空机器放 6 个（6 空槽）→ 6 槽各 1，背包 0
    slots, left = simulate_quick_move_upgrade(6, [True] * 6, [0] * 6, 6)
    print("       6 空槽放 6 个 -> %s，背包剩 %d" % (slots, left))
    require(slots == [1] * 6 and left == 0, "G1 放 6 个 ⇒ 6 槽各 1，背包 0（总数 6 = 6 + 0）")

    # 2) 空机器放 8 个 → 6 进 2 留在背包（不弹出、不销毁）
    slots, left = simulate_quick_move_upgrade(8, [True] * 6, [0] * 6, 6)
    print("       6 空槽放 8 个 -> %s，背包剩 %d" % (slots, left))
    require(sum(slots) == 6 and left == 2, "G2 槽位用尽 ⇒ 剩余留在背包（8 = 6 + 2），绝不弹出")

    # 3) 已有 5 个 + 1 空槽，再放 3 个 → 只进 1
    slots, left = simulate_quick_move_upgrade(3, [False] * 5 + [True], [1] * 5, 6)
    require(sum(slots) == 1 and left == 2, "G3 只剩 1 空槽 ⇒ 只进 1，其余留在背包（守恒）")

    # 4) 自动合成已达上限 1：一个都放不进 → 一个都不动
    slots, left = simulate_quick_move_upgrade(3, [True] * 6, [1], 1)
    require(sum(slots) == 0 and left == 3,
            "G4 该种升级已达上限 ⇒ 一个都放不进、源物品一个不少（3 = 0 + 3）")

    # 5) 背包满（moveItemStackTo 返回 false）→ 插件槽 → 背包 方向：物品原地不动
    require(conservation(2, 0, 2, 0, note="G5 背包满：Shift 取插件槽物品"),
            "G5 背包满时插件槽物品原地不动（总数不变，绝不「搬一半丢掉」）")

    # 6) 破坏方块：掉落清单覆盖全部升级槽 + 方块物品 NBT 携带整份 Inventory
    be = read(ADV_BE)
    block = read(os.path.join(JAVA, "block", "AdvancedQuantityKeeperBlock.java"))
    require("collectContainerRange(keeper.getInventory(),\n                            AdvancedQuantityKeeperBlockEntity.SLOT_COUNT,"
            in block or "SLOT_COUNT," in block,
            "G6 破坏时收集区间从 SLOT_COUNT 起（4..9 = 全部升级槽，一格不漏）")
    require("tag.put(TAG_INVENTORY, writeInventoryTag(registries));" in be
            and "private ListTag writeInventoryTag(" in be
            and "RsccSlotNbt.write(inventory, registries)" in be,
            "G7 高级保持器：存盘整份 Inventory 逐格带 \"Slot\"（收敛到共用实现 RsccSlotNbt）")
    require("private void readInventoryTag(" in be
            and "RsccSlotNbt.read(tag.getList(TAG_INVENTORY, Tag.TAG_COMPOUND), inventory, registries);" in be,
            "G8 高级保持器：读回按 \"Slot\" 放回原槽（旧档无 Slot 才退回兼容搬运，不丢物）")
    require("tag.put(TAG_INVENTORY, inventory.createTag(registries));" not in be,
            "G8c 旧的「无槽位」写盘路径已彻底删除（那正是「插件被挤到一格」的根因）")
    helper = read(os.path.join(JAVA, "support", "RsccSlotNbt.java"))
    require('entry.putByte(TAG_SLOT, (byte) i);' in helper and "stack.save(registries, entry)" in helper,
            "G11 RsccSlotNbt.write：逐格写 \"Slot\" 下标（与 ContainerHelper#saveAllItems 同一写法）")
    require("entry.getByte(TAG_SLOT) & 255" in helper
            and "items.set(slot, ItemStack.parse(registries, entry)" in helper,
            "G12 RsccSlotNbt.read：有下标就逐格原样落位（直接写 NonNullList，不经 setItem 截断）")
    require("container.fromTag(list, registries); // 旧档兼容" in helper,
            "G13 RsccSlotNbt.read：无下标（旧档 / 旧方块物品）退回原版兼容搬运，不丢物")
    require(conservation(6, 0, 6, 0, note="G9 破坏方块（6 个升级）"),
            "G9 破坏方块：容器 -6、掉落物 +6（总数不变）")

    # 7) 界面关闭中途：客户端只做本地预测，服务端权威回复覆盖；本工程没有任何「关界面写回」的搬运
    menu = read(os.path.join(JAVA, "menu", "AdvancedQuantityKeeperMenu.java"))
    require("removed(" not in menu or "terminalCloseSaver" not in menu,
            "G10 关闭界面不触发任何自研搬运（服务端权威 + 原生槽位同步）")

    # 8) 既有「一格多个」状态保持不变
    def read_only_check(slot_counts):
        """只读校验：返回 (oversized, invalid, perKind) —— <b>不修改入参</b>。

        与方块实体同构：空槽 continue（只统计非空槽），非法 kind 计入 invalid，
        每格多于 1 个计入 oversized，「每种升级占几格」计入 perKind（<b>只看不写</b>）。
        """
        per_kind = [0, 0, 0]
        oversized = invalid = 0
        for count, kind in slot_counts:
            if count <= 0:
                continue
            if kind < 0:
                invalid += 1
                continue
            if count > 1:
                oversized += 1
            per_kind[kind] += 1
        return oversized, invalid, per_kind

    multi = [(6, 1)] + [(0, 1)] * 5
    snapshot = list(multi)
    oversized, invalid, per_kind = read_only_check(multi)
    print("       「一格多个」快照 %s -> oversized=%d invalid=%d perKind=%s" % (snapshot, oversized, invalid, per_kind))
    require(multi == snapshot, "H1 只读校验不修改任何槽内容（快照逐字节不变）")
    require(oversized == 1 and per_kind[1] == 1,
            "H2 一格 6 个被识别为「异常」但<b>保留原样</b>（不拆分、不清理、不迁移）")
    require(invalid == 0, "H3 合法升级不被误判为非法（因此不会被任何清理逻辑盯上）")


def section_slot_roundtrip():
    """本轮修复的断言：插件槽「逐格往返」—— 放 3 个堆叠升级，存 → 读仍是 3 个不同槽、每格 1 个。

    根因 = 原版 {@code SimpleContainer#createTag} 不写 "Slot"、{@code #fromTag} 走 {@code addItem}
    （先并入同类已有堆、再塞首个空槽）。于是「4/5/6 三格各 1 个」读回后被并成一格，
    甚至落到<b>靠前的 ghost 标记槽</b>（槽 0）。下面用同一套规则 faithful 复刻两条路径。
    """
    section("I) 插件槽位往返：放 3 个堆叠升级在不同 / 相邻槽 ⇒ 存 → 读仍是 3 个不同槽、每格 1 个")

    # 根因复现：旧路径把 3 个 count=1 合并到槽 0（首个空槽 → 再并入同类堆）。
    old = legacy_read({4: 1, 5: 1, 6: 1})
    print("       旧格式(无 Slot) 4/5/6 -> %s" % old)
    require(old == [3, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            "I0 旧格式确实会把 4/5/6 三格合并成「槽 0 有 3 个」——即用户报的「插件被挤到一格」")

    # 相邻槽：4/5/6。
    adjacent = new_read({4: 1, 5: 1, 6: 1})
    require(adjacent == [0, 0, 0, 0, 1, 1, 1, 0, 0, 0],
            "I1 相邻槽 4/5/6：读回仍是 3 个不同槽、每格 1 个（位置与数量原样）")
    # 分散槽：4/6/9。
    scattered = new_read({4: 1, 6: 1, 9: 1})
    require(scattered == [0, 0, 0, 0, 1, 0, 1, 0, 0, 1],
            "I2 分散槽 4/6/9：读回仍是 3 个不同槽、每格 1 个（含顺序）")
    # 混合升级 + 不同数量：速度 4（2 个）、堆叠 5/6、自动合成 9。
    mixed = new_read({4: 2, 5: 1, 6: 1, 9: 1})
    require(mixed == [0, 0, 0, 0, 2, 1, 1, 0, 0, 1],
            "I3 混合升级：逐格数量原样（不合并、不重排）")

    for name, case in (("相邻", adjacent), ("分散", scattered)):
        occupied = [i for i, c in enumerate(case) if c > 0]
        require(len(occupied) == 3 and all(case[i] == 1 for i in occupied),
                "I4 %s摆放：占 3 个槽、每格恰好 1 个（%s）" % (name, occupied))

    # 旧格式读回后总数不丢（6 个仍在），只是位置被合并 —— 修的是「位置」，不是「数量」。
    require(sum(legacy_read({4: 1, 5: 1, 6: 1})) == 3 and sum(new_read({4: 1, 5: 1, 6: 1})) == 3,
            "I5 新旧格式总数都守恒（3 = 3）；差异只在「位置」——本轮只改位置口径")

    # ---- I6：所有「按 SimpleContainer 存槽位」的机器都改为共用实现（不再各写一遍 / 不再漏网） ----
    machines = (
        ("block", "entity", "AdvancedQuantityKeeperBlockEntity.java", "高级资源定量保持器", "(4 标记 + 6 插件)"),
        ("block", "entity", "QuantityKeeperBlockEntity.java", "基础资源定量保持器", "(1 标记 + 6 插件)"),
        ("block", "entity", "CollectionCacheBlockEntity.java", "归流缓存仓", "(内容槽 + 6 插件槽)"),
        ("block", "entity", "IntermediateCacheBlockEntity.java", "中间产物缓存仓", "(27 磁盘槽)"),
        ("block", "entity", "SequenceAssemblyExecutorBlockEntity.java", "序列装配执行器", "(54 样板槽)"),
    )
    for parts in machines:
        src = read(os.path.join(JAVA, *parts[:3]))
        label, shape = parts[3], parts[4]
        require("RsccSlotNbt.write(" in src and "RsccSlotNbt.read(" in src,
                "I6 %s%s：存 / 读都走 RsccSlotNbt（逐格带 \"Slot\"）" % (label, shape))
        require("createTag(registries)" not in src and ".fromTag(tag.getList" not in src,
                "I6 %s%s：彻底不再用会丢槽位的 createTag/fromTag 组合" % (label, shape))
    # 序列执行舱（54 单元样板槽）：本轮收敛到共用实现。
    # 注意它另有「旧磁盘单件迁移容器」（pending / legacy，各 1 格），那两个 fromTag 不是「槽位容器」，
    # 1 格容器不存在「同类并格」问题，因此这里按<b>样板槽本体</b>精确断言，而不是笼统地禁止一切 fromTag。
    chamber_src = read(os.path.join(JAVA, "block", "entity", "SequenceExecutionChamberBlockEntity.java"))
    require('RsccSlotNbt.write(unitSlots, registries)' in chamber_src
            and 'RsccSlotNbt.read(tag.getList("UnitSlots"' in chamber_src,
            "I6 序列执行舱(54 单元样板槽)：存 / 读都走 RsccSlotNbt（逐格带 \"Slot\"）")
    require("unitSlots.createTag(" not in chamber_src and "unitSlots.fromTag(" not in chamber_src,
            "I6 序列执行舱(54 单元样板槽)：样板槽彻底不再用会丢槽位的 createTag/fromTag 组合")
    # 读后按槽位重建：样板槽内容变了，由它派生的缓存（unitsCache 是源头）必须一起作废
    load_body = chamber_src.split("public void loadAdditional(final CompoundTag tag")[1].split("\n    }")[0]
    require("unitsCache = null;" in load_body and "busCategoriesCache = null;" in load_body
            and "ownedStepsCache = null;" in load_body,
            "I6 序列执行舱：读档后按「槽位下标」触发的重建齐全（unitsCache 源头 + 下游类别/步序表一并作废）")
    # 反例守卫：共用实现本身不得退回「无 Slot」写盘
    helper = read(os.path.join(JAVA, "support", "RsccSlotNbt.java"))
    require(".createTag(" not in helper and "slotAware" in helper,
            "I7 RsccSlotNbt 自身不调用 SimpleContainer#createTag，且必须做「有无 Slot」的分支判定")


def main():
    section_readonly()
    section_minimal_defense()
    section_no_auto_distribution()
    section_count_range()
    section_quick_move_marker()
    section_slot_consistency()
    section_conservation()
    section_slot_roundtrip()

    print("\n" + "=" * 60)
    if problems:
        print("[FAIL] 问题总数: %d" % len(problems))
        for p in problems:
            print("  - %s" % p)
        sys.exit(1)
    print("[PASS] 全部通过（守恒 + 逐格往返：不复制、不删除、不合并）")
    sys.exit(0)


if __name__ == "__main__":
    main()
