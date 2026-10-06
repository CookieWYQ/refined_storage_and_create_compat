# -*- coding: utf-8 -*-
"""自检（第 37 轮 · 样板「按类型」收口：管理舱不显示总样板 / 总样板库不收单元样板）。

用法：python tools/selfcheck_round37_pattern_kind_gates.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户现场（两条缺陷）
--------------------
A 现场：<i>「那个终端单元样板库（括号 9），我在那个单元样板管理仓中看到了。我之前叫你删了，
  你为什么还没删？就是单元样板管理仓中不要出现这一个总的这一个样板。」</i>
  上一版把「不显示总样板」做在<b>客户端显示层</b>（{@code isDisplayable}），看似对，实则必然失效：
  客户端的镜像容器要等原版槽位同步包才有内容，而槽位是在<b>菜单构造 / 界面 init</b> 那一刻按
  「本组多少格」重建的 —— 那一刻容器全是空的，`instanceof` 判空永远为真 ⇒ 首帧照常把总样板画出来，
  而且此后没有任何一次重建（除非玩家改窗口 / 打字触发 resize）。
B 现场：<i>「为什么单元样板可以放到这一个序列装配样板仓里面？」</i>
  根因是原版 {@code Slot#mayPlace} <b>默认恒 true</b>，它<b>不</b>读容器的 {@code canPlaceItem}
  （后者只被漏斗 / 管道的 InvWrapper 走）。上一版把「只收总样板」只写在容器里 ⇒ 总样板库的 54 格
  界面（点击 / Shift / 拖拽）对任何物品都敞开。

本自检钉住六件事
----------------
A1 隐藏判定的<b>唯一来源</b>是物品类型（`SequenceAssemblyPatternItem#isAssemblyPattern` /
   `SequenceUnitPatternItem#isUnitPattern`），代码里没有任何名字 / 文案 / 语言键启发式；
A2 隐藏下标由<b>服务端算、随开界面下发</b>（`UnitManagerSources#hiddenMasterPatternSlots` →
   `UnitPatternManagerData.Section#hiddenSlots` → 两端 `SectionView`），因此<b>不依赖内容何时到达客户端</b>；
   并断言旧写法 `!clientMirror || isDisplayable(...)` 已消失（防回退）；
A3 隐藏只改<b>位置与可见性</b>：每个下标照旧各建一个 `Slot`（挪到裁剪区外），
   原版槽位同步的两端下标因此一一对应；等价模型验证「下标序列只做压缩、不丢不改序」；
A4 计数自洽：可见格数 / 行数由同一批 `shown` 标记推出（标题带行数 = 可见行数 + 1），
   隐藏格既不计入行数也不占格；
B1 总样板库（54 格）三条放入路径统一收口到 `acceptsPattern`：界面真槽位 `PatternSlot#mayPlace`、
   客户端镜像容器 `canPlaceItem`、容器 `canPlaceItem`（物流）；
B2 <b>别修反</b>：单元样板的「想放哪就放哪」一字未动 —— 执行舱 `acceptsUnit`、汇总菜单
   `WindowContainer#canPlaceItem`、终端单元样板库窗口的判定都只<b>收</b>单元样板，没有任何地方
   因为「它是单元样板」而拒绝它；被拒的只有「放进总样板专用容器」这一条。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(JAVA, *parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def body(source, marker, end_marker):
    """取 marker 起、end_marker 前的片段（找不到返回空串）。"""
    start = source.find(marker)
    if start < 0:
        return ""
    stop = source.find(end_marker, start)
    return source[start:] if stop < 0 else source[start:stop]


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# =====================================================================
# A1 唯一判据 = 物品类型
# =====================================================================
def section_kind_predicates():
    section("A1 唯一判据 = 物品类型（instanceof），不看名字 / 文案 / 语言键")
    unit_item = read("item", "SequenceUnitPatternItem.java")
    asm_item = read("item", "SequenceAssemblyPatternItem.java")
    sources = read("support", "UnitManagerSources.java")
    manager_menu = read("menu", "UnitPatternManagerMenu.java")
    exec_be = read("block", "entity", "SequenceAssemblyExecutorBlockEntity.java")

    check("单元样板类型判定只有一处（SequenceUnitPatternItem#isUnitPattern）",
          "public static boolean isUnitPattern(final ItemStack stack) {" in unit_item
          and "stack.getItem() instanceof SequenceUnitPatternItem" in unit_item)
    check("总样板类型判定只有一处（SequenceAssemblyPatternItem#isAssemblyPattern）",
          "public static boolean isAssemblyPattern(final ItemStack stack) {" in asm_item
          and "stack.getItem() instanceof SequenceAssemblyPatternItem" in asm_item)
    check("管理舱的隐藏下标来自总样板类型判定（不是名字 / 语言键）",
          "SequenceAssemblyPatternItem.isAssemblyPattern(container.getItem(i))" in sources)
    check("管理舱显示层也用同一个类型判定",
          "private static boolean isDisplayable(final ItemStack stack) {" in manager_menu
          and "return !SequenceAssemblyPatternItem.isAssemblyPattern(stack);" in manager_menu)
    check("总样板库的收口判定来自同一个类型判定",
          "public static boolean acceptsPattern(final ItemStack stack) {" in exec_be
          and "return SequenceAssemblyPatternItem.isAssemblyPattern(stack);" in exec_be)
    # 负面断言：判定里不得出现名字 / 语言键比较（去掉注释后检查）
    heuristics = ("getHoverName", "getDescriptionId", "Language.getInstance", "displayName()")
    gate_text = (body(unit_item, "public static boolean isUnitPattern", "}")
                 + body(asm_item, "public static boolean isAssemblyPattern", "}")
                 + body(exec_be, "public static boolean acceptsPattern", "}"))
    check("三条判定里没有任何名字 / 文案启发式",
          all(word not in gate_text for word in heuristics), str(gate_text))


# =====================================================================
# A2 服务端算、随数据下发（不依赖内容到达时序）
# =====================================================================
def section_mask_from_server():
    section("A2 隐藏下标由服务端算并随开界面下发（修复「首帧容器还是空的」这个根因）")
    sources = read("support", "UnitManagerSources.java")
    data = read("network", "UnitPatternManagerData.java")
    manager_be = read("block", "entity", "UnitPatternManagerBlockEntity.java")
    manager_menu = read("menu", "UnitPatternManagerMenu.java")
    summary_menu = read("menu", "ChamberUnitsSummaryMenu.java")
    terminal = read("block", "entity", "SequencePatternTerminalBlockEntity.java")

    check("服务端扫描唯一实现（UnitManagerSources#hiddenMasterPatternSlots）",
          "public static List<Integer> hiddenMasterPatternSlots(final Container container) {" in sources)
    check("方块实体把它放进下发数据（getMenuData）",
          "UnitManagerSources.hiddenMasterPatternSlots(entry.container())" in manager_be)
    check("数据记录带隐藏下标字段（Section#hiddenSlots）",
          "int slotCount, boolean extractOnly, List<Integer> hiddenSlots) {" in data)
    check("写 / 读缓冲都带隐藏下标（两端字段顺序一致）",
          "buf.writeVarInt(section.hiddenSlots().size());" in data
          and "final int hiddenCount = buf.readVarInt();" in data
          and "hidden.add(buf.readVarInt());" in data)
    check("越界 / 重复 / 乱序的隐藏下标在记录构造时归一化（坏数据不得摆出矛盾网格）",
          "public Section {" in data and "sanitized.contains(index)" in data
          and "sanitized.sort(java.util.Comparator.naturalOrder());" in data
          and "hiddenSlots = List.copyOf(sanitized);" in data)
    check("服务端菜单与下发数据共用同一个扫描函数（同一容器同一时刻 ⇒ 结果必然一致）",
          manager_menu.count("UnitManagerSources.hiddenMasterPatternSlots(entry.container())") == 1
          and "UnitManagerSources.hiddenMasterPatternSlots(entry.container())" in manager_be)
    check("客户端菜单直接用服务端下发的下标（不自己看内容）",
          "new SimpleContainer(section.slotCount()), section.extractOnly(), section.hiddenSlots()" in manager_menu)
    check("防回退：旧的「只在客户端镜像按内容判断」已彻底消失",
          "!clientMirror || isDisplayable" not in manager_menu and "clientMirror" not in manager_menu)
    check("显示层仍保留按类型的第二道网（内容后到 / 中途变化也不漏）",
          "!section.isHidden(i) && isDisplayable(stack)" in manager_menu)
    check("同一口径覆盖「执行仓单元样板汇总」窗口（只放单元样板进窗口）",
          "if (SequenceUnitPatternItem.isUnitPattern(unitSlots.getItem(i))) {" in summary_menu)
    check("同一口径覆盖汇总的「行内张数 / 机器图标」元数据（与窗口同一判定，不会两套计数）",
          "if (SequenceUnitPatternItem.isUnitPattern(stack)) {\n                    units.add(stack.copy());"
          in terminal)


# =====================================================================
# A3 隐藏 = 位置与可见性（下标一一对应）
# =====================================================================
def section_index_identity():
    section("A3 隐藏只挪位置：每个下标照旧各建一个槽位（两端下标一一对应）")
    manager_menu = read("menu", "UnitPatternManagerMenu.java")
    check("隐藏格照样 addSlot（下标原位，绝不抽掉）",
          "addSlot(new SectionSlot(section.container, i, slotX, slotY, section.extractOnly," in manager_menu)
    check("隐藏格挪到裁剪区外（HIDDEN_SLOT_Y）且 isActive 一并收紧",
          "HIDDEN_SLOT_Y" in manager_menu and "return shown && y >= frameTopY && y < frameBottomY;" in manager_menu)
    check("SectionView 的隐藏掩码按组内槽位数建（越界下标被丢弃，不会串到别组）",
          "this.hidden = new boolean[Math.max(0, container.getContainerSize())];" in manager_menu
          and "index >= 0 && index < this.hidden.length" in manager_menu)

    # ---- 等价模型：复刻 rebuildSlots 的「压缩可见格」逻辑，断言下标序列不丢不改序 ----
    def rebuild(sections, hidden_sets, columns=9, row_size=18, hidden_y=-1000):
        """sections = [(组名, 槽位数)]；hidden_sets = {组名: {隐藏下标}}。"""
        out = []          # (全局下标, 组内下标, 子标记, y)
        rows = []
        index = 0
        row_y = 20
        for name, size in sections:
            hidden = hidden_sets.get(name, set())
            visible = 0
            for i in range(size):
                shown = i not in hidden
                y = (row_y + row_size + (visible // columns) * row_size) if shown else hidden_y
                out.append((index, i, shown, y))
                if shown:
                    visible += 1
                index += 1
            rows.append((name, visible, 0 if visible <= 0 else (visible - 1) // columns + 1))
            if visible > 0:
                row_y += (0 if visible <= 0 else (visible - 1) // columns + 1) * row_size + row_size
        return out, rows

    layout = [("舱A", 54), ("终端旧库", 9), ("舱B", 54)]
    hidden = {"舱A": {3, 10}, "终端旧库": {8}, "舱B": set()}
    slots, rows = rebuild(layout, hidden)

    check("推演① 全局下标数 == 各组槽位数之和（隐藏不改变槽位总数）",
          len(slots) == sum(size for _, size in layout), str(len(slots)))
    check("推演② 下标顺序 = 「分组顺序 + 组内升序」，一个不丢",
          [s[0] for s in slots] == list(range(sum(size for _, size in layout)))
          and [s[1] for s in slots][:5] == [0, 1, 2, 3, 4])
    check("推演③ 隐藏格在裁剪区外（y == -1000），可见格 y >= 20（不会点到隐藏格）",
          all(s[3] == -1000 for s in slots if not s[2])
          and all(s[3] >= 20 for s in slots if s[2]))
    check("推演④ 可见格紧凑排列：同组可见格 y 只按可见序号推进（不因隐藏留洞）",
          [s[3] for s in slots if s[2]][:3] == [38, 38, 38]
          and [s[3] for s in slots if s[2]][9] == 56)
    check("推演⑤ 计数自洽：可见格数 == 槽位数 - 隐藏数；行数 == ceil(可见/9)",
          rows == [("舱A", 52, 6), ("终端旧库", 8, 1), ("舱B", 54, 6)], str(rows))
    check("推演⑥ 隐藏格不占行：某组全隐藏时该组行数为 0、不占标题带",
          rebuild([("全隐藏", 3)], {"全隐藏": {0, 1, 2}})[1] == [("全隐藏", 0, 0)])


# =====================================================================
# B1 总样板库：三条放入路径统一收口
# =====================================================================
def section_assembly_gates():
    section("B1 总样板库三路收口：界面真槽位 / 客户端镜像 / 物流")
    exec_be = read("block", "entity", "SequenceAssemblyExecutorBlockEntity.java")
    menu = read("menu", "SequenceAssemblyExecutorMenu.java")

    check("容器判定（物流路径）走 acceptsPattern",
          "public boolean canPlaceItem(final int index, final ItemStack stack) {\n"
          "            // 物流路径（InvWrapper / VanillaContainerWrapper）的唯一闸门\n"
          "            return acceptsPattern(stack);" in exec_be)
    check("界面 54 格改用 PatternSlot（原版 Slot#mayPlace 恒 true 的老坑）",
          "addSlot(new PatternSlot(inv, index, 8 + col * 18, 18 + row * 18));" in menu
          and "private static final class PatternSlot extends Slot {" in menu)
    check("PatternSlot#mayPlace 走同一个 acceptsPattern（点击 / Shift / 拖拽同一闸门）",
          "public boolean mayPlace(final ItemStack stack) {\n"
          "            return SequenceAssemblyExecutorBlockEntity.acceptsPattern(stack);" in menu)
    check("客户端镜像容器也收口（不再「假装收下」再被服务端退回）",
          menu.count("return SequenceAssemblyExecutorBlockEntity.acceptsPattern(stack);") == 2
          and "new net.minecraft.world.SimpleContainer(PATTERN_SLOTS) {" in menu
          and menu.index("new net.minecraft.world.SimpleContainer(PATTERN_SLOTS) {")
          < menu.index("return SequenceAssemblyExecutorBlockEntity.acceptsPattern(stack);"))
    check("总样板库一格一张在两端一致（客户端镜像也限 1）",
          "public int getMaxStackSize() {" in menu
          and "public int getMaxStackSize(final ItemStack stack) {\n            return 1;" in menu)
    check("单元样板 / 普通物品 / 空手都进不去总样板库（等价真值表）",
          not accepts_pattern("unit") and not accepts_pattern("dirt")
          and not accepts_pattern("empty") and accepts_pattern("assembly"))


def accepts_pattern(kind):
    """复刻 acceptsPattern 的真值（kind ∈ {assembly, unit, dirt, empty}）。"""
    return kind == "assembly"


# =====================================================================
# B2 别修反：单元样板照旧「想放哪就放哪」
# =====================================================================
def section_unit_not_restricted():
    section("B2 别修反：单元样板的自由放置一字未动")
    chamber = read("block", "entity", "SequenceExecutionChamberBlockEntity.java")
    summary = read("menu", "ChamberUnitsSummaryMenu.java")
    chamber_menu = read("menu", "SequenceExecutionChamberMenu.java")
    manager_menu = read("menu", "UnitPatternManagerMenu.java")
    sources = read("support", "UnitManagerSources.java")

    check("执行舱 acceptsUnit 仍是「单元样板 + 已绑定 + 配方类型相同」（没有新增类型封杀）",
          "if (stack.isEmpty() || !stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get()) || !isBound()) {"
          in chamber)
    check("汇总菜单的窗口判定仍收单元样板（类型判定换成共享口径，语义不变）",
          "if (!SequenceUnitPatternItem.isUnitPattern(stack)) {" in summary
          and "return chamber != null && chamber.acceptsUnit(stack);" in summary)
    check("执行舱界面槽位仍只要求「单元样板」",
          "if (stack.isEmpty() || !stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())) {" in chamber_menu)
    check("管理舱的执行舱槽位判定仍走 acceptsUnit（合并视图同源）",
          "return chamber.acceptsUnit(stack);" in sources
          and "return member != null && member.acceptsUnit(stack);" in sources)
    check("全部「拒收」判定里没有以「是单元样板」为由的封杀",
          "!SequenceUnitPatternItem.isUnitPattern" not in chamber
          and "!SequenceUnitPatternItem.isUnitPattern" not in manager_menu
          and "!SequenceUnitPatternItem.isUnitPattern" not in sources)


def main():
    section_kind_predicates()
    section_mask_from_server()
    section_index_identity()
    section_assembly_gates()
    section_unit_not_restricted()
    print()
    print("=" * 72)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        sys.exit(1)
    print("SELFCHECK OK (%d checks)" % CHECKS[0])


if __name__ == "__main__":
    main()
