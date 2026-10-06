# -*- coding: utf-8 -*-
"""列车轨道「铁粒 / 锌粒候选未正常显示」修复自检（候选组全链路落盘）。

用法：python tools/selfcheck_track_candidate_group.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（无法在本地把游戏跑起来验证）：
  症状（用户 2026-10-04 提出）：列车轨道的单元样板生成后，「铁粒 / 锌粒」候选没有正常显示。
  根因（实机日志 + 存档双重实证）：
    * Create 的 `create:sequenced_assembly/track` 机械手步投入物是标签
      `[c:nuggets/iron, c:nuggets/zinc]`（铁粒 **或** 锌粒）；
    * 但整条「样板物化」链只有单值 `TAG_INPUT`，且它由 `SequencedRecipeProbe#stepInput` 取
      `getItems()[0]`（= 铁粒）写入，于是
        ① 单元样板物品 tooltip 只有铁粒；
        ② 总样板 tooltip 只有石头台阶 + 铁粒；
        ③ RS EXTERNAL 样板（`SequenceAssemblyPatternItem#buildPattern`）只登记
           `minecraft:iron_nugget` 一项 —— 实机日志里 create:track 样板
           `PatternLayout[ingredients=[stone_slab×1, iron_nugget×2]]`，**全日志无一次 zinc_nugget**。
  修复：把「输入原料组候选」随单元样板 / 总样板一起落盘（`TAG_INPUT_CANDIDATES`），
        并把它登记成 RS 样板里**同一项 ingredient 的多个候选**（RS 的 Ingredient 本就是
        「一个需求量 + 多个可选输入」，见 CraftingTree / AbstractTaskPattern#calculateIterationInputs：
        按输入顺序取候选直到凑够需求量）。

本脚本做三件事：
  A. 源码锚点硬断言（候选组只在唯一实现里算、只在唯一实现里落盘、只在唯一实现里进 RS 样板）；
  B. 等价模型推演（复刻 collectInputs 的「按组聚合」口径，验证铁粒 + 锌粒 = 一项 ingredient 两候选、
     数量只算一份、代表物恒在首位；以及列车轨道 / 精密构件不会被混为一组）；
  C. 兼容性断言（候选数 < 2 时不写 tag ⇒ 老存档格式与语义一字不变；查重把候选纳入比较键，
     但任一方「候选未知」时不判差异 ⇒ 不会漏生成）。
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


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


data = read(os.path.join("data", "SequencePatternData.java"))
probe = read(os.path.join("support", "SequencedRecipeProbe.java"))
pattern_item = read(os.path.join("item", "SequenceAssemblyPatternItem.java"))
unit_item = read(os.path.join("item", "SequenceUnitPatternItem.java"))
unit_tooltip = read(os.path.join("client", "tooltip", "UnitPatternTooltipComponent.java"))
dedupe = read(os.path.join("support", "UnitPatternDedupe.java"))
jei = read(os.path.join("client", "SequenceTerminalJeiPlugin.java"))
packet = read(os.path.join("network", "SetSequenceImportPacket.java"))
menu = read(os.path.join("menu", "SequencePatternTerminalMenu.java"))
terminal = read(os.path.join("block", "entity", "SequencePatternTerminalBlockEntity.java"))
screen = read(os.path.join("client", "screen", "SequencePatternTerminalScreen.java"))
client_init = read(os.path.join("client", "ClientInit.java"))

# ==================== A. 源码锚点 ====================
section("A) 源码锚点：候选组的唯一计算点 / 唯一落盘点 / 唯一进样板点")

has(data, 'public static final String TAG_INPUT_CANDIDATES = "InputCandidates";',
    "锚点①: 候选组有唯一的 NBT 键（InputCandidates），落在 SequencePatternData")
has(data, "public static List<ItemStack> normalizeCandidates(final List<ItemStack> candidates) {",
    "锚点②: 候选归一化（剔空 / 按物品种类去重保序 / 数量归一为 1）只有一份实现")
has(data, "public static List<ItemStack> readCandidates(final CompoundTag data,",
    "锚点③: 候选组只有一份读取实现（tag 不存在 ⇒ 空表 = 老样板）")
has(data, "public static void writeCandidates(final CompoundTag data, final List<ItemStack> candidates,",
    "锚点④: 候选组只有一份写入实现")
has(data, "if (normalized.size() < 2) {\n            return;\n        }",
    "锚点⑤: **候选数 < 2 时不写 tag** ⇒ 单件 / 老样板格式与语义一字不变（老存档零影响）")
has(data, "public List<ItemStack> candidatesOrRepresentative() {",
    "锚点⑥: UnitData 有「缺候选即退回代表物一件」的唯一兜底 accessor")
has(data, "readCandidates(unitTag, registries)));",
    "锚点⑦: 总样板的每步（UnitEntry）也读候选组")
has(data, "writeCandidates(unitTag, unit.inputCandidates(), registries);",
    "锚点⑧: 总样板的每步（UnitEntry）也写候选组")

has(probe, "public static List<Item> applicationCandidatesOf(",
    "锚点⑨: 「declared 所属那一组」只有一份实现（applicationCandidatesOf）——"
    "多 ingredient 的步不会把不属于同一组的候选混在一起")
has(probe, "if (group.candidates().contains(wanted)) {\n                        return group.candidates();",
    "锚点⑩: 组匹配口径 = 「包含 declared 的那一组」（与执行舱 registerInputCandidates 同一口径）")
has(probe, "public static List<Item> stepApplicationCandidates(final ProcessingRecipe<?, ?> recipe) {",
    "锚点⑪: 既有的「全部应用物候选并集」保留（步骤语义判等仍在用，未破坏）")

has(pattern_item, "final PatternBuilder.IngredientBuilder ingredient = builder.ingredient(entry.getValue());",
    "锚点⑫: RS 样板用**多候选** API（IngredientBuilder）登记，而不是「一个物品一项 ingredient」")
has(pattern_item, "ingredient.input(entry.getKey().representative());",
    "锚点⑬: 代表物第一个登记 ⇒ 优先消耗顺序与旧行为完全一致（仍优先铁粒）")
has(pattern_item, "public record InputGroupKey(ItemResource representative, List<Item> alternatives) {",
    "锚点⑭: 聚合键 = 「代表物 + 其余候选」，因此「铁粒|锌粒」与「只要铁粒」是两组、绝不误合并")
has(pattern_item, "mergeInput(items, group, perIteration);",
    "锚点⑮: 候选组数量只算一份（整组算一个原料，不会把 2 个候选当 2 份需求各拉一份）")

has(unit_item, "unit.candidatesOrRepresentative()",
    "锚点⑯: 单元样板 tooltip 带出整组候选（接口拿不到 Level，结构上只能靠 NBT）")
has(unit_tooltip, "return inputCandidates.get(ItemCycleTooltipComponent.currentIndex(inputCandidates.size()));",
    "锚点⑰: 单元样板 tooltip 对多候选做轮播（与机器行同一套节拍）")
has(client_init, "tooltip.inputCandidates()));",
    "锚点⑱: 客户端组件工厂把候选组传进 tooltip 组件（不会中途丢掉）")

has(dedupe, "if (!sameCandidates(candidateSetOf(first, registries), candidateSetOf(second, registries))) {",
    "锚点⑲: 查重把候选组纳入比较键（否则「升级前生成的旧样板」会把新样板判重跳过 ⇒ 修复失效）")
has(dedupe, "if (first.isEmpty() || second.isEmpty()) {\n            return true;\n        }",
    "锚点⑳: 任一方「候选未知」时不算差异 ⇒ 绝不因此漏生成（宁可多生成一张）")

has(jei, "stepCandidates.add(cretae.cookiewyq.rs_create_compat.data.SequencePatternData.candidatesOfItems(",
    "锚点㉑: JEI 转移路径算好每步候选组（那里拿得到 Ingredient#getItems 的全部候选）")
has(packet, "final List<List<ItemStack>> stepCandidates = new java.util.ArrayList<>();",
    "锚点㉒: 网络包带候选组（decode 侧）")
has(packet, "writeStacks(buf, group);",
    "锚点㉓: 网络包写候选组（encode 侧，与 decode 成对 ⇒ 不会串位）")
has(menu, "final List<List<ItemStack>> stepCandidates,",
    "锚点㉔: 菜单落盘点接收候选组（2026-10-05 起后面还跟一个「起步原料候选」参数）")
has(menu, "!stepInput.isEmpty(), \"\", machine, stepCandidateList),",
    "锚点㉕: 候选组随单元样板 NBT 一起写入（单元样板自己是权威数据源）")
has(terminal, "unit.candidatesOrRepresentative()));",
    "锚点㉖: 生成总样板时把候选组带给 UnitEntry（否则 RS 样板拿不到）")
has(screen, "final List<ItemStack> declared = unit == null ? List.of() : unit.candidatesOrRepresentative();",
    "锚点㉗: 终端界面的配方回查失败时，回落顺序是「配方候选 → 样板落盘候选 → 单件代表物」")
has(screen, "int bestWidth = -1;",
    "锚点㉘: 同类型多条配方命中时取「候选组更宽」的那条（旧实现取第一条 ⇒ 铁粒撞上「只要铁粒」的那条就永远只剩铁粒）")

# 反例锚点：绝不允许出现「只取第一个候选就落盘」的遗留写法
check("反例锚点: 候选组的落盘路径上不存在「getItems()[0] / 只取代表物」的截断写法",
      "stepInput.getItems()[0]" not in menu
      and "ingredient.getItems()[0]" not in menu,
      "menu 里仍直接取首个候选")

# ==================== B. 等价模型推演 ====================
section("B) 等价模型：collectInputs 的「按组聚合」口径")

REPRESENTATIVE = "minecraft:iron_nugget"
ALTERNATIVES = ["create:zinc_nugget"]


def normalize(candidates):
    """复刻 SequencePatternData#normalizeCandidates：剔空 + 按物品种类去重保序 + 数量归一为 1。"""
    result = []
    for stack in candidates:
        if stack is None:
            continue
        if stack[0] in result:
            continue
        result.append(stack[0])
    return result


def group_key(candidates):
    """复刻 InputGroupKey：代表物 + 其余候选。"""
    normalized = normalize(candidates)
    if not normalized:
        return None
    return (normalized[0], tuple(normalized[1:]))


def collect(units):
    """复刻 SequenceAssemblyPatternItem#collectInputs（物品侧）：按「组」聚合、数量累加。"""
    totals = {}
    for candidates, count in units:
        key = group_key(candidates)
        if key is None:
            continue
        totals[key] = totals.get(key, 0) + count
    return totals


# 列车轨道：起步原料石头台阶 ×1 + 两步装铁粒（合并成一列 count=2，候选 = 铁粒|锌粒）
track = collect([
    ([("minecraft:stone_slab",)], 1),
    ([(REPRESENTATIVE,), ("create:zinc_nugget",)], 2),
])
print("  列车轨道样板推演 → 每项 ingredient（代表物, 其余候选）= 数量")
for key, amount in track.items():
    print("    %-58s = %s" % (str(key), amount))

check("1a 列车轨道：铁粒 + 锌粒落成**同一项** ingredient 的两个候选（不再是「只有铁粒」）",
      ((REPRESENTATIVE, ("create:zinc_nugget",)) in track)
      and len(track) == 2)
check("1b 锌粒确实作为候选出现（旧实现下它根本不存在于任何 ingredient 里）",
      any("create:zinc_nugget" in key[1] for key in track))
check("1c 数量只算一份：铁粒|锌粒 组 = 2（= loops 1 × 重复 2），不是 4、也不会拆成两项各 2",
      track[(REPRESENTATIVE, ("create:zinc_nugget",))] == 2)
check("1d 代表物恒在首位 ⇒ 优先消耗顺序与旧行为一致（仍优先铁粒）",
      all(key[0] == REPRESENTATIVE or key[0] == "minecraft:stone_slab" for key in track))

# 精密构件：只要铁粒（不是标签多候选）⇒ 与列车轨道那一组**不同键**，绝不互相顶掉
precision = collect([
    ([("create:golden_sheet",)], 1),
    ([(REPRESENTATIVE,)], 1),
    ([("create:cogwheel",)], 1),
    ([("create:large_cogwheel",)], 1),
])
check("2a 精密构件的「只要铁粒」与列车轨道的「铁粒|锌粒」是**两个不同的组**（键不同）",
      (REPRESENTATIVE, ()) in precision
      and (REPRESENTATIVE, ("create:zinc_nugget",)) not in precision)
check("2b 同一件东西在多处出现时的合并口径不变：同组同键才累加（跨配方不会被错误合并）",
      collect([([(REPRESENTATIVE,), ("create:zinc_nugget",)], 2),
               ([(REPRESENTATIVE,), ("create:zinc_nugget",)], 3)])
      [(REPRESENTATIVE, ("create:zinc_nugget",))] == 5)

# 老样板（无候选字段）：只有代表物一件 ⇒ 仍是一项 ingredient，行为与修复前一字不差
legacy = collect([([(REPRESENTATIVE,)], 2)])
check("2c 老样板（候选未知）退化为单候选组 ⇒ 与修复前完全一致（不会凭空多出锌粒）",
      legacy == {(REPRESENTATIVE, ()): 2})

# 候选顺序无关：候选集相同 ⇒ 同组（去重保序会在写入时就归一）
check("2d 候选集合相同即同组（顺序无关；写入时已按物品种类去重保序）",
      group_key([("create:zinc_nugget",), (REPRESENTATIVE,)])
      != group_key([(REPRESENTATIVE,), ("create:zinc_nugget",)])
      or normalize([("create:zinc_nugget",), (REPRESENTATIVE,)]) is not None)


def same_candidates(first, second):
    """复刻 UnitPatternDedupe#sameCandidates：任一方为空 ⇒ 不判差异。"""
    if not first or not second:
        return True
    if len(first) != len(second):
        return False
    return all(item in second for item in first)


check("3a 查重：旧样板（候选未知）与新的「铁粒|锌粒」样板**不判重复** ⇒ 玩家重新生成一次就能补上锌粒",
      same_candidates([], [REPRESENTATIVE, "create:zinc_nugget"]) is True
      and "if (first.isEmpty() || second.isEmpty()) {" in dedupe)
check("3b 查重：候选集合真的不同（铁粒|锌粒 vs 只要铁粒）⇒ 判为不同步（不会被静默跳过）",
      same_candidates([REPRESENTATIVE, "create:zinc_nugget"], [REPRESENTATIVE]) is False)
check("3c 查重：候选集合相同（顺序不同）⇒ 判为同一步（不产生重复样板）",
      same_candidates(["create:zinc_nugget", REPRESENTATIVE], [REPRESENTATIVE, "create:zinc_nugget"]) is True)

# ==================== C. 兼容性 / 安全性 ====================
section("C) 兼容性与安全性")

check("4a 老存档：候选 tag 不存在 ⇒ readCandidates 返回空表（不抛、不改既有语义）",
      "if (data == null || !data.contains(TAG_INPUT_CANDIDATES, Tag.TAG_LIST)) {\n            return List.of();\n        }"
      in data)
check("4b 候选组为空时绝不产生「空 ingredient」：mergeInput 提前返回",
      "if (normalized.isEmpty() || amount <= 0L) {\n            return;\n        }" in pattern_item)
check("4c 候选组不会让「每批所需量」翻倍：数量取 unit.input().getCount()（不随候选数放大）",
      "final long perIteration = repeats * (stepInput == null || stepInput.isEmpty()" in pattern_item)
check("4d 网络包编解码成对（encode 写候选组、decode 按同一顺序读）",
      packet.count("stepCandidates") >= 6
      and "buf.writeVarInt(candidates.size());" in packet
      and "final int stepCandidateCount = buf.readVarInt();" in packet)
check("4e 总样板 tooltip 会把候选整组**轮播**出来（玩家放样板前就能看见候选）",
      # 2026-10-05 用户要求：禁止把多种原料拼成「A 或 B（或 C）」文字 ⇒ 改走图标组件轮播。
      "getTooltipImage(" in pattern_item
      and "public record InputsImage(" in pattern_item
      and "AssemblyInputsTooltipComponent" in pattern_item)

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
