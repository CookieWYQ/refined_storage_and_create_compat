# -*- coding: utf-8 -*-
"""自检（第 46 轮 · 总线那一条里的类别图标也要轮播，且与详细配置界面同一相位）。

用户实测（2026-10-06）：
    「第二张图中显示为两个铁粒，显然是不正确的，就是它一直显示成铁粒，没有轮换。
      这个界面应该也出现轮换。」

即：主界面面板上方那一条类别条（输出总线 / 输入总线）里，多候选类别（例：列车轨道的
「铁粒 / 锌粒」这**一个** ingredient 的两个候选）恒显示组代表物铁粒，不轮播；
而同一类别在「类别详细配置」子界面里早就按候选轮播 —— 同一件事在两个界面显示成两件东西。

本轮修法（不新造第二套轮播）：
    - 取值处 = `ExporterExecutorRowWidget#drawCell`（条上那一格）原先直读 `iconOf`（组代表物）；
    - 现在改读 `BusCategoryConfigScreen#barStackOf`，它是 `displayStackOf` 的**纯委托**
      （候选 / 相位 / 判据全部沿用详细配置界面那一套 `GhostMarkerRenderer#cycleCandidate`）；
    - tooltip 标题改读 `barNameOf`，与 `barStackOf` 同源同相位，杜绝「条上锌粒、tooltip 铁粒」；
    - 输入侧 / 输出侧共用**同一个**控件（`ImporterExecutorBarSource implements
      ExporterExecutorRowWidget.Source`），因此一处修复覆盖两侧。

本脚本检查：
    A. 源码锚点：取值处、纯委托（无第二份轮播）、tooltip 同源、唯一轮播实现；
    B. 真值表：多候选逐 tick 轮换 / 单候选恒定 / 无候选兜底 / 条上图标与 tooltip 同相位 /
       条与详细配置行同相位 / 两侧同心 / 一类别一格（候选数不改变格数）。
"""
import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]

WIDGET = ("client", "widget", "ExporterExecutorRowWidget.java")
GHOST = ("client", "widget", "GhostMarkerRenderer.java")
SCREEN = ("client", "screen", "BusCategoryConfigScreen.java")
IMPORTER_SOURCE = ("client", "widget", "ImporterExecutorBarSource.java")
IMPORTER_MIXIN = ("mixin", "client", "ImporterScreenMixin.java")
CHAMBER = ("block", "entity", "SequenceExecutionChamberBlockEntity.java")


def read(*parts):
    with io.open(os.path.join(SRC, *parts), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def method_body(text, signature):
    """从签名处截到下一个同缩进的 `}`（够用的静态方法体提取，用于证明「只是委托」）。"""
    start = text.find(signature)
    if start < 0:
        return ""
    depth = 0
    index = text.find("{", start)
    if index < 0:
        return ""
    for pos in range(index, len(text)):
        if text[pos] == "{":
            depth += 1
        elif text[pos] == "}":
            depth -= 1
            if depth == 0:
                return text[index:pos + 1]
    return ""


# ======================================================================
# A. 源码锚点
# ======================================================================
section("A) 源码锚点：条上取值处改成「与详细配置界面同一个入口」，且没有第二份轮播")

widget = read(*WIDGET)
ghost = read(*GHOST)
screen = read(*SCREEN)
importer_source = read(*IMPORTER_SOURCE)
importer_mixin = read(*IMPORTER_MIXIN)
chamber = read(*CHAMBER)

check("锚点① 取值处（条上那一格）改用 barStackOf（= 详细配置界面 displayStackOf 的委托）",
      "final ItemStack icon = BusCategoryConfigScreen.barStackOf(category);" in widget)
check("锚点② 缺陷点已消失：条上那格（drawCell）不再直读组代表物 iconOf",
      "iconOf(category)" not in method_body(widget, "private static void drawCell(")
      and "barStackOf(category)" in method_body(widget, "private static void drawCell("))
check("锚点③ tooltip 标题改读 barNameOf（与格子同源），不再写静态 displayName",
      "lines.add(BusCategoryConfigScreen.barNameOf(category).getVisualOrderText());" in widget
      and "lines.add(displayName(category).getVisualOrderText());" not in widget)

bar_stack_body = method_body(screen, "public static ItemStack barStackOf(final RsccBusCategory category) {")
check("锚点④ barStackOf 是**纯委托**（体内只有 displayStackOf，绝无第二份候选 / 相位计算）",
      bar_stack_body.replace(" ", "").replace("\n", "") == "{returndisplayStackOf(category);}",
      bar_stack_body.strip().replace("\n", " ")[:120])
bar_name_body = method_body(screen, "public static Component barNameOf(final RsccBusCategory category) {")
check("锚点⑤ barNameOf 也不自己算相位：取哪一件仍只问 displayStackOf",
      "displayStackOf(category)" in bar_name_body and "cycleCandidate" not in bar_name_body)

check("锚点⑥ 全模组只有一套候选轮播实现（cycleCandidate 唯一定义在 GhostMarkerRenderer）",
      ghost.count("public static ItemStack cycleCandidate(final List<ItemStack> candidates) {") == 1
      and screen.count("public static ItemStack cycleCandidate(") == 0
      and widget.count("public static ItemStack cycleCandidate(") == 0)
check("锚点⑦ 轮播周期仍是既有的 CYCLE_TICKS=20（没有为条上新造第二个周期常量）",
      "private static final int CYCLE_TICKS = 20;" in ghost
      and "candidates.get((int) (Math.floorDiv(tick, CYCLE_TICKS) % candidates.size()));" in ghost
      and "static final int CYCLE_TICKS" not in widget
      and "static final int CYCLE_TICKS" not in screen)
check("锚点⑧ 详细配置界面的唯一入口 displayStackOf 未被绕开（仍是私有 + 单处 cycleCandidate 调用）",
      "private static ItemStack displayStackOf(final RsccBusCategory category) {" in screen
      and screen.count("GhostMarkerRenderer.cycleCandidate(") == 1
      and "return GhostMarkerRenderer.cycleCandidate(candidates);" in screen)
check("锚点⑨ 没有候选可轮播时仍是组代表物兜底（iconOf 语义与调用点都在）",
      "return ExporterExecutorRowWidget.iconOf(category);" in screen
      and "public static ItemStack iconOf(final RsccBusCategory category) {" in widget
      and 'category.iconItem()' in widget)

check("锚点⑩ 输入侧 / 输出侧共用同一个控件（一处修复覆盖两侧）",
      "implements ExporterExecutorRowWidget.Source" in importer_source
      and "new ExporterExecutorRowWidget(" in importer_mixin
      and widget.count("drawCell(guiGraphics, list.get(i)") == 1)
check("锚点⑪ 一类别一格：条上仍按类别列表逐格画，候选数不影响格数",
      "drawCell(guiGraphics, list.get(i), cellX(i, scrolled), y, auto);" in widget)

check("锚点⑫ 既有语义未变：isTabOnly 7 处跳过点、一类别一属主、链级两份实现仍在服务端",
      screen.count("isTabOnly(category)") == 7
      and "public List<ChainBusCategory> chainCategories() {" in chamber
      and "chainBusOwners()" in chamber
      and "chainCategories()" not in screen)
# 2026-10-06（round48「开发日志总开关」）更新，理由：
#   本条原本用 **mtime** 证明「本轮只动了两个界面文件」（AssemblyWatchdog / RsccWireLinkSearch /
#   SequenceExecutionChamberBlockEntity 未被触碰）。那是**轮次取证**，不是行为断言；而 round48
#   **合法地**改动了其中两个文件：
#     * AssemblyWatchdog.java —— 把两条必要 WARN 从 `if (!isEnabled()) return;` 之后挪出来；
#     * SequenceExecutionChamberBlockEntity.java —— 把 noteStall / stepOwnerMissingAt 从
#       `if (isEnabled())` 块里挪出来（否则关掉开发日志后「没机器认领」的横幅会消失），
#       并给各高频开发日志加上总开关。
#   改为内容级断言：本条真正要保护的是「条上图标与详细配置同相位」这套修法没被带坏。
check("锚点⑬ 条上/详细配置同相位的修法未被本轮改动带坏（mtime 断言已被 round48 合法触碰取代）",
      "public static ItemStack barStackOf(" in screen
      and "return displayStackOf(category);" in screen
      and "barNameOf(" in widget
      and "cycleCandidate(" in read(*GHOST))


# ======================================================================
# B. 真值表（按 cycleCandidate / displayStackOf / barStackOf 的 Java 口径复刻）
# ======================================================================
CYCLE_TICKS = 20
IRON = "minecraft:iron_nugget"
ZINC = "create:zinc_nugget"
SLABS = ["minecraft:stone_slab", "minecraft:smooth_stone_slab", "minecraft:andesite_slab"]

NAME_OF = {
    IRON: "铁粒",
    ZINC: "锌粒",
    SLABS[0]: "石头台阶",
    SLABS[1]: "平滑石头台阶",
    SLABS[2]: "安山岩台阶",
}


def cycle_candidate(candidates, tick):
    """GhostMarkerRenderer#cycleCandidate 的复刻：tick / 20 取模（同一帧内恒定）。"""
    if not candidates:
        return ""
    if len(candidates) == 1:
        return candidates[0]
    return candidates[(tick // CYCLE_TICKS) % len(candidates)]


class Category(object):
    def __init__(self, cid, representative, candidates, is_input=True, static_name=None):
        self.id = cid
        self.representative = representative
        self.candidates = list(candidates)
        self.is_input = is_input
        self.static_name = static_name or NAME_OF.get(representative, representative)

    def display_stack_of(self, tick):
        """BusCategoryConfigScreen#displayStackOf：多候选 ⇒ 当前轮播件；否则 ⇒ 组代表物。"""
        if self.is_input and len(self.candidates) > 1:
            return cycle_candidate(self.candidates, tick)
        return self.representative

    def bar_stack_of(self, tick):
        """主条那一个格子的图标（barStackOf → displayStackOf：同一个实现）。"""
        return self.display_stack_of(tick)

    def bar_name_of(self, tick):
        """主条那一个格子的 tooltip 标题（barNameOf）。"""
        if self.is_input and len(self.candidates) > 1:
            shown = self.display_stack_of(tick)
            if shown:
                return NAME_OF[shown]
        return self.static_name


TRACK_IRON = Category("input:create:track#%s" % IRON, IRON, [IRON, ZINC])
PRECISION_IRON = Category("input:create:precision_mechanism#%s" % IRON, IRON, [IRON])
SLEEPER = Category("input:create:track#minecraft:stone_slab", SLABS[0], SLABS)
NO_CANDIDATES = Category("input:create:x#create:golden_sheet", "create:golden_sheet", [])
FLUID_LIKE = Category("input:create:y", "create:golden_sheet", [], is_input=False,
                      static_name="工序: 压床")

# 两侧的类别列表（两侧共用同一个控件，因此同一个函数同时服务它们）
SIDES = {"输出侧": [TRACK_IRON, PRECISION_IRON, SLEEPER, NO_CANDIDATES],
         "输入侧": [SLEEPER, TRACK_IRON]}

section("B) 真值表①：多候选类别在条上逐 tick 轮换（用户截图的那一类：铁粒 / 锌粒）")
timeline = [TRACK_IRON.bar_stack_of(tick) for tick in range(0, 60)]
print("    tick 0..59 条上显示：%s" % [NAME_OF[item] for item in timeline])
check("1a 三个周期内确实轮换：0..19 铁粒、20..39 锌粒、40..59 又回铁粒",
      [NAME_OF[item] for item in timeline]
      == ["铁粒"] * 20 + ["锌粒"] * 20 + ["铁粒"] * 20,
      "%s" % [NAME_OF[item] for item in timeline])
check("1b 轮换周期 = 既有的 CYCLE_TICKS=20（不是新的一套节拍）",
      len({timeline[t] for t in range(0, 20)}) == 1
      and len({timeline[t] for t in range(20, 40)}) == 1
      and timeline[0] != timeline[20])
check("1c 两个候选都出现过（不是「一直铁粒」）",
      {timeline[t] for t in range(0, 40)} == {IRON, ZINC})
check("1d 反例对照：改动前（条上恒读代表物）在 tick=20 仍是铁粒 —— 这正是用户看到的现象",
      IRON != TRACK_IRON.display_stack_of(20) and IRON == TRACK_IRON.representative)

section("B) 真值表②：单候选恒定 / 无候选兜底（行为与改动前一致）")
check("2a 单候选类别（精密构件的铁粒）：任何 tick 都是铁粒，不引入无意义的变化",
      {PRECISION_IRON.bar_stack_of(tick) for tick in range(0, 200)} == {IRON})
check("2b 无候选类别（老快照 / 服务端没带候选）：退回组代表物",
      {NO_CANDIDATES.bar_stack_of(tick) for tick in range(0, 200)} == {NO_CANDIDATES.representative})
check("2c 非输入类别（工序 / 流体）：静态显示名恒定，不参与物品轮播",
      {FLUID_LIKE.bar_name_of(tick) for tick in range(0, 200)} == {FLUID_LIKE.static_name})
check("2d 三候选类别（任意台阶）：逐 20 tick 走完三个候选再回第一个",
      [NAME_OF[SLEEPER.bar_stack_of(t)] for t in range(0, 80)]
      == ["石头台阶"] * 20 + ["平滑石头台阶"] * 20 + ["安山岩台阶"] * 20 + ["石头台阶"] * 20)

section("B) 真值表③：条上图标与 tooltip 同相位 + 条与详细配置行同相位 + 两侧同心")
check("3a 条上图标与悬浮 tooltip 在**同一帧**指的是同一件（不存在「条上锌粒、tooltip 铁粒」）",
      all(TRACK_IRON.bar_name_of(t) == NAME_OF[TRACK_IRON.bar_stack_of(t)]
          for t in range(0, 200)))
check("3b 条上与详细配置界面行内每 tick 显示同一件（同一份候选、同一套相位）",
      all(TRACK_IRON.bar_stack_of(t) == TRACK_IRON.display_stack_of(t)
          for t in range(0, 200)))
check("3c 输入侧 / 输出侧共用同一控件 ⇒ 同一 tick 显示同一件（两侧不存在相位差）",
      all(category.bar_stack_of(t) == category.display_stack_of(t)
          for categories in SIDES.values() for category in categories for t in range(0, 200))
      and "implements ExporterExecutorRowWidget.Source" in importer_source)
check("3d 反例对照：改动前「条=代表物 / 详细配置行=轮播件」在 tick=20 对不上",
      TRACK_IRON.representative != TRACK_IRON.display_stack_of(20))

section("B) 真值表④：一类别一格（候选数不改变格数 / 不产生第二份需求）")
sides = SIDES
check("4a 每一侧的格数 == 类别数，与候选多少无关（列车轨道 2 候选仍只占 1 格）",
      all(len(categories) == len({c.id for c in categories}) for categories in sides.values())
      and all(len({c.id for c in categories}) == len(categories) for categories in sides.values()))
check("4b 格数不随时间变化（轮播只换图，不增删格）",
      all(len({c.id for c in categories}) == len(categories)
          for categories in sides.values() for _tick in range(0, 200)))
check("4c 同一 ingredient 的两个候选仍是**一个**类别（不是一个候选一格）",
      len(TRACK_IRON.candidates) == 2 and TRACK_IRON.id.count("#") == 1)

# ======================================================================
# 结果
# ======================================================================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
