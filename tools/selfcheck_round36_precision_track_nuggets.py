# -*- coding: utf-8 -*-
"""第 36 轮：铁粒 / 锌粒「混杂」的收口 —— 按配方上下文收敛候选（R1）+ 绝不因为候选不齐断供（R2）
+ 取到的不是最优那件时给一条边沿提示（R3/R4）。

用法：python tools/selfcheck_round36_precision_track_nuggets.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么需要它（本地跑不起游戏，只能靠「源码锚点 + 真实配方 + 等价模型」三件事取证）：

  * 用户原话：「那个铁粒和锌粒就是这个混杂在一起的问题仍然还没有解决。」
    规定：**精密构件只能取铁粒**；**列车轨道优先取有货的那一个**（只有一个可用就取那个）；
    **不管怎么样都得取到一个**，但**要提醒「这并非最优」**。

  * 真实配方（本脚本直接读 Create 的 JSON，不写死结论）：
      create:sequenced_assembly/precision_mechanism  第 2 步 ingredient = {"tag": "c:nuggets/iron"}
      create:sequenced_assembly/track                第 0、1 步 ingredient = [{"tag":"c:nuggets/iron"},
                                                                             {"tag":"c:nuggets/zinc"}]
    **同一个「铁粒 / 锌粒」标签同时出现在两条配方的步里**（两条配方共用同一批机械手）——
    这就是「混杂」的现场。

  * 本脚本做四件事：
      A. 源码锚点：R1 的唯一判据（coversItemNeeds）、R2 的两档目标表与让路、R3 的边沿提示只有一份实现；
      B. 真实配方推演：从两份 JSON 还原 ingredient 候选组，按与 Java 逐字一致的算法算
         「谁覆盖谁」，把 R1 的三条规则与 R2 的「只有一个也取」全部推一遍；
      C. 反例表：精密构件只铁粒 / 轨道优先有的那个 / 只有一个也取 / 判不出来不断供；
      D. R3 边沿语义的等价复刻（进入发一次、恢复清一次、同一状态不再发）+ 文案硬要求检查。
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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
CREATE_RECIPES = os.path.join(ROOT, "local_src", "create_src", "data", "create", "recipe",
                              "sequenced_assembly")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def read_json(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


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


# ==================== 配方 JSON → 候选组（与 Java 逐字同一口径） ====================
#
# Java 侧口径（见 SequencedRecipeProbe#candidates / #assemblyStepInputGroups）：
#   * Minecraft 的 ingredient 若写成**数组** `[a, b]`，读出来是**一个** Ingredient，
#     其 getItems() 会**并集**展开 a 与 b 的全部物品；
#   * 若写成**对象** `{"item": x}` / `{"tag": t}`，就是一个单值 ingredient；
#   * 因此「铁粒 / 锌粒」是**同一个 ingredient 的两个候选**（一个原料），不是「两份需求」。
#
# 本脚本把标签展开成「已知成员」（只看我们关心的这两个标签，其它标签按注册名占位），
# 这样推演完全由真实 JSON 驱动，而不是把结论写死在脚本里。

TAG_MEMBERS = {
    "c:nuggets/iron": ["minecraft:iron_nugget"],
    "c:nuggets/zinc": ["create:zinc_nugget"],
    "c:plates/gold": ["create:golden_sheet"],
    "create:sleepers": ["minecraft:stone_slab"],
}


def expand_value(node):
    """把配方 JSON 里的一个 ingredient 值节点展开成候选物品列表（保序去重）。"""
    if isinstance(node, list):
        # 数组 = 一个 ingredient 的多个候选（铁粒 / 锌粒就是这种写法）
        merged = []
        for element in node:
            for item in expand_value(element):
                if item not in merged:
                    merged.append(item)
        return merged
    if isinstance(node, dict):
        if "item" in node:
            return [node["item"]]
        if "tag" in node:
            tag = node["tag"]
            if tag in TAG_MEMBERS:
                return list(TAG_MEMBERS[tag])
            return ["<%s>" % tag]  # 不关心的标签：按注册名占位（不影响本次推演）
        return ["<unknown>"]
    return ["<unknown>"]


def step_groups(raw_step):
    """一个序列装配步的「应用物候选组」列表（下标 ≥1 的每个 ingredient 各成一组）。"""
    groups = []
    ingredients = raw_step.get("ingredients", [])
    for index, ingredient in enumerate(ingredients):
        if index == 0:
            continue  # 下标 0 被 Create 覆盖成过渡件（本步不是「投入物」）
        groups.append(expand_value(ingredient))
    return groups


def main_groups(recipe):
    """总样板主原料（Create 的 ingredient）的候选组。"""
    return expand_value(recipe.get("ingredient", {}))


def coupling_of(recipe):
    """一个配方的「物品 → 它所属的全部候选组」表（与 Java #inputCoupling 的 byItem 同构）。"""
    by_item = {}

    def register(group):
        normalized = []
        for item in group:
            if item not in normalized:
                normalized.append(item)
        if not normalized:
            return
        for item in normalized:
            by_item.setdefault(item, [])
            if normalized not in by_item[item]:
                by_item[item].append(normalized)

    register(main_groups(recipe))
    for raw_step in recipe.get("sequence", []):
        for group in step_groups(raw_step):
            register(group)
    return by_item


# ---- R1 等价模型（Java #coversItemNeeds 的逐字复刻） ----

def item_needed_recipes(coupling):
    def needed(item):
        return {rid for rid, by_item in coupling.items() if item in by_item}
    return needed


def covers_item_needs(coupling, item, other):
    """「other 能不能把 item 此刻的全部需求顶下来」——Java #coversItemNeeds 的复刻。"""
    if item is None or other is None:
        return False
    if item == other:
        return True
    needed = item_needed_recipes(coupling)(item)
    if not needed:
        return False  # 没有任何有订单的配方要它 ⇒ 不存在「被覆盖」
    for recipe in needed:
        groups = coupling.get(recipe, {}).get(item)
        if not groups:
            return False  # 配方数据缺一块：不表态 ⇒ 覆盖不了（宁可多取一件，绝不断供）
        for group in groups:
            if other not in group:
                return False
    return True


# ==================== A. 源码锚点 ====================
section("A) 源码锚点：R1 覆盖判据 / R2 替补档与让路 / R3 边沿提示，各只有一份实现")

chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
probe = read(os.path.join("support", "SequencedRecipeProbe.java"))

has(chamber, "private boolean coversItemNeeds(final Item item, final Item other) {",
    "锚点①: 「配方上下文包含关系」只有一份实现（coversItemNeeds）")
has(chamber, "for (final List<Item> group : groups) {\n                if (!group.contains(other)) {",
    "锚点②: 判据 = 「item 所属的每个候选组都必须含 other」（单候选组天然覆盖不了别人）")
has(chamber, "private Map<String, Map<Item, List<List<Item>>>> inputCoupling() {",
    "锚点③: 「配方 → 物品 → 候选组」只有一份实现，且只收**有订单**的配方（recipeOrdered 闸门）")
has(chamber, "private Map<Item, Long> substituteTargetsFor(final Map<Item, Long> itemTargets) {",
    "锚点④: R2 的「替补件目标表」只有一份实现（整组仍只备一种，绝不翻倍）")
has(chamber, "final boolean substitutePass = substituteAppliesForPull(resource.item());",
    "锚点⑤: 抽取侧知道「这一趟抽的是替补件」（唯一的让路开关）")
has(chamber, "if (!substitutePass && interchangeableAvailable(resource.item())) {",
    "锚点⑥: 「整组只备一种」在替补档**让路**（最优件一件都没有时不许把兄弟候选当已满足）")
has(chamber, "if (pullItem(storage, itemResource, available, substituteTarget, disallowThresholds)) {",
    "锚点⑦: 替补件的抽取只发生在最优件真的取不到时（并且以 pullItem 的返回值为事实依据）")
has(chamber, "    private boolean pullItem(final StorageNetworkComponent storage, final ItemResource resource,",
    "锚点⑧: pullItem 返回「这一轮真的抽进来了吗」（替代供料的提示以此为准，绝不凭空弹窗）")
has(chamber, "private void reportHandoffUsage() {",
    "锚点⑨: R3 的提示只有一份实现（reportHandoffUsage），走既有横幅出口")
has(chamber, "if (signature.equals(handoffSignature)) {\n            return; // 同一状态原样持续：绝不重复播报",
    "锚点⑩: 边沿语义①——同一状态原样持续 ⇒ 一条都不再发（不是「冷却到了再发」）")
has(chamber, "if (signature.isEmpty()) {\n            // 「恢复」这一条边沿：只重置状态，不播报",
    "锚点⑪: 边沿语义②——恢复（替代消失）只重置状态、不播报")
has(chamber, "reportHandoffUsage();",
    "锚点⑫: 提示在备料末尾发出（读的是「本轮真的抽进了哪些替补件」）")

check("锚点⑬: 既有「同一 ingredient 的多个候选 = 一个原料」的逻辑保留（整组只备一种 / 缺料按整组算）",
      "private boolean interchangeableAvailable(final Item item) {" in chamber
      and "if (isInterchangeableFollower(entry.getKey()) || interchangeableAvailable(entry.getKey())) {" in chamber,
      "interchangeableAvailable 被改写 / 三处共用点被拆散")

check("锚点⑭: 「判不出来不断供」——覆盖判据的每一处失败都落在「覆盖不了」这一侧（绝不因判不出来而少取一件）",
      "return false; // 这一刻没有任何有订单的配方要它 ⇒ 不存在「被覆盖」这回事" in chamber
      and "// （宁可多取一件，绝不断供 —— 用户硬要求 R2）。" in chamber,
      "失败方向反了：判不出来会变成「已满足」")

has(probe, "public static List<InputGroup> assemblyStepInputGroups(final ProcessingRecipe<?, ?> recipe,",
    "锚点⑮: 候选组的数据源仍是既有的 assemblyStepInputGroups（R1 不新建第二套口径）")

# 反例锚点：绝不允许出现「按物品名全局合并候选」的遗留写法
check("反例锚点: 覆盖判据里不出现「只看类别 items() 列表」的写法（那正是旧的两条配方混在一个类别里的口径）",
      "info.items().contains(other)" not in chamber,
      "仍在用类别 items() 直接当覆盖判据")

# ==================== B. 真实配方推演 ====================
section("B) 真实配方：铁粒 / 锌粒在两条配方里各自成组")

precision = read_json(os.path.join(CREATE_RECIPES, "precision_mechanism.json"))
track = read_json(os.path.join(CREATE_RECIPES, "track.json"))

IRON = "minecraft:iron_nugget"
ZINC = "create:zinc_nugget"
PRECISION = "create:sequenced_assembly/precision_mechanism"
TRACK = "create:sequenced_assembly/track"

precision_coupling = coupling_of(precision)
track_coupling = coupling_of(track)
print("  精密构件 物品 → 候选组：")
for item, groups in sorted(precision_coupling.items()):
    print("    %-32s %s" % (item, groups))
print("  列车轨道 物品 → 候选组：")
for item, groups in sorted(track_coupling.items()):
    print("    %-32s %s" % (item, groups))

check("1a 精密构件的装铁粒 ingredient 是**单候选**组（只有铁粒）",
      IRON in precision_coupling
      and any(group == [IRON] for group in precision_coupling[IRON]),
      "精密构件的铁粒候选组不是单候选：%s" % precision_coupling.get(IRON))
check("1b 精密构件里根本没有锌粒（锌粒不是它的候选）",
      ZINC not in precision_coupling,
      "精密构件竟然声明了锌粒：%s" % precision_coupling.get(ZINC))
check("1c 列车轨道的装铁粒 ingredient 是**同一个**组的两个候选（铁粒 + 锌粒，一个原料）",
      IRON in track_coupling and ZINC in track_coupling
      and any(group == [IRON, ZINC] for group in track_coupling[IRON])
      and any(group == [IRON, ZINC] for group in track_coupling[ZINC]),
      "轨道候选组不是 {铁粒, 锌粒}：%s / %s" % (track_coupling.get(IRON), track_coupling.get(ZINC)))

# ==================== C. 反例表：R1 的三条规则 + R2 的「只有一个也取」 ====================
section("C) 反例表（用户给的四条规则逐条验算）")

CASES = [
    # (说明, 有订单的配方集合, item, other, 期望 covers)
    ("R1-1 只有精密构件的订单：锌粒**覆盖不了**铁粒（精密构件只能取铁粒）",
     [PRECISION], IRON, ZINC, False),
    ("R1-2 只有列车轨道的订单：锌粒**能**覆盖铁粒（轨道优先取有货的那一个）",
     [TRACK], IRON, ZINC, True),
    ("R1-3 只有列车轨道的订单：铁粒也能覆盖锌粒（两个方向对称，谁有货取谁）",
     [TRACK], ZINC, IRON, True),
    ("R1-4 **两条配方同时有订单**（混杂现场）：锌粒覆盖不了铁粒（精密构件那条线不能被饿死）",
     [PRECISION, TRACK], IRON, ZINC, False),
    ("R1-5 两条配方同时有订单：铁粒**能**覆盖锌粒（锌粒的全部需求只有轨道，而轨道也收铁粒）",
     [PRECISION, TRACK], ZINC, IRON, True),
    ("R1-6 自己覆盖自己恒真（同一件不算替代）",
     [PRECISION, TRACK], IRON, IRON, True),
    ("R2-1 只有一个可用：轨道订单下「锌粒覆盖铁粒」为真 ⇒ 有锌粒就不必再抽铁粒（不是断供）",
     [TRACK], IRON, ZINC, True),
    ("R2-2 判不出来（一条订单都没有）：一律「覆盖不了」⇒ 退回各自的独立需求，绝不断供",
     [], IRON, ZINC, False),
    ("R2-3 判不出来（配方查不到 ⇒ 该配方无组）：一律「覆盖不了」⇒ 宁可多取一件",
     [PRECISION], IRON, "create:large_cogwheel", False),
    ("R2-4 不相关的物品之间不存在覆盖关系",
     [PRECISION, TRACK], "create:cogwheel", ZINC, False),
]

for index, (title, recipes, item, other, expected) in enumerate(CASES, start=1):
    coupling = {}
    if PRECISION in recipes:
        coupling[PRECISION] = precision_coupling
    if TRACK in recipes:
        coupling[TRACK] = track_coupling
    actual = covers_item_needs(coupling, item, other)
    check("%s" % title, actual is expected, "实际 %s / 期望 %s" % (actual, expected))

# 判不出来（配方数据半缺）时也必须是「覆盖不了」：这里用「配方在订单表里、但耦合表里没有它」模拟
partial = {PRECISION: precision_coupling, TRACK: {}}
check("R2-5 配方数据半缺（订单里有、耦合表里没有该配方）⇒ 「覆盖不了」⇒ 绝不断供",
      covers_item_needs(partial, IRON, ZINC) is False,
      "半缺数据下判成了「已满足」，会饿死这条线")

# ---- R2 的「两档目标表」推演：整组仍只备一种，绝不翻倍 ----
def substitute_targets(item_targets, coupling, is_step_extra=lambda item: True):
    """Java #substituteTargetsFor 的复刻。"""
    result = {}
    for preferred, target in item_targets.items():
        if not is_step_extra(preferred) or target <= 0:
            continue
        for sibling in coupling_siblings(coupling, preferred):
            if not is_step_extra(sibling):
                continue
            if covers_item_needs(coupling, preferred, sibling):
                continue
            result[sibling] = max(result.get(sibling, 0), target)
    return result


def coupling_siblings(coupling, item):
    """Java #interchangeableCandidates 的「候选兄弟」部分（跨配方取并集，去重保序）。"""
    siblings = []
    for by_item in coupling.values():
        for group in by_item.get(item, []):
            for candidate in group:
                if candidate != item and candidate not in siblings:
                    siblings.append(candidate)
    return siblings


both = {PRECISION: precision_coupling, TRACK: track_coupling}
targets = {IRON: 2}
subs = substitute_targets(targets, both)
print("  两条配方同时有订单：最优件目标表 = %s → 替补件目标表 = %s" % (targets, subs))
check("2a 替补件只有锌粒一份，且目标量 ≤ 最优件目标量（整组仍只备一种，绝不翻倍）",
      subs == {ZINC: 2},
      "替补表异常：%s" % subs)

track_only = {TRACK: track_coupling}
check("2b 只有列车轨道时：锌粒是**并列最优件**（互相覆盖）⇒ 不进替补档（它有自己的目标量）",
      substitute_targets({IRON: 1}, track_only) == {},
      "并列最优件被错误地放进了替补档")

precision_only = {PRECISION: precision_coupling}
check("2c 只有精密构件时：锌粒根本不是候选 ⇒ 替补表为空（不可能凭空取锌粒）",
      substitute_targets({IRON: 1}, precision_only) == {},
      "精密构件竟然算出锌粒替补：%s" % substitute_targets({IRON: 1}, precision_only))

# ---- R2「只有一个也取」的抽取判据复刻 ----
def needs_substitute_for(coupling, preferred, substitute, holdings):
    """Java #needsSubstituteFor 的复刻；holdings(item) = 本仓/机器/网络的现有量。"""
    if preferred is None or substitute is None or preferred == substitute:
        return False
    if covers_item_needs(coupling, preferred, substitute):
        return False
    if holdings(preferred) > 0:
        return False
    return True


def effective_siblings(coupling, item, holdings):
    """Java #interchangeableAvailable 收窄后的「可用兄弟」：必须（a）覆盖得了这块料的需求、
    （b）此刻确实有货（本仓 / 机器 / 网络任一处）。"""
    return [sibling for sibling in coupling_siblings(coupling, item)
            if covers_item_needs(coupling, item, sibling) and holdings.get(sibling, 0) > 0]


def target_items(coupling, holdings, start_items=()):
    """Java #fillInternalForBus 目标表的「整组只备一种」收窄（Java 侧完整复刻）。

    口径 = 逐个勾选的输入类别：
      * <b>起步原料</b>（配方主原料）不在「可互换兄弟」讨论范围（它的整组语义是「任一份都能起件」），
        一律进目标表；
      * 其余物品：凡是「有货且顶得下来」的兄弟存在 ⇒ 这一件就不必自己再取一份。
    """
    everything = set()
    for by_item in coupling.values():
        everything.update(by_item)
    result = []
    for item in sorted(everything):
        if item in start_items:
            result.append(item)
            continue
        if not effective_siblings(coupling, item, holdings):
            result.append(item)
    return result


def pull_plan(coupling, holdings, targets, live=None):
    """把「抽取循环」按 Java 的口径复刻一遍（`fillInternalForBus` 的两档）。

    <h2>为什么「替补档」通常会被普通目标档挡住（这不是 bug，要说清楚）</h2>
    <p>Java 侧抽料循环的第一档就是「目标表」：<b>每一个被勾选的输入类别都会把它的全部候选登记进
    目标表</b>（铁粒与锌粒在列车轨道那一组里都是候选 ⇒ 各自都有一个目标量）。因此现实中
    「铁粒用光、只剩锌粒」这一场多半由<b>普通目标档</b>直接抽走锌粒 —— 那也是「绝不断供」，
    只是走的是另一条路。替补档真正独有的价值是<b>普通档够不到的那些候选</b>：
    候选本身没有自己的类别（玩家只手写了一个代表物、或总线过滤把它挡在外面）时，
    只有在「最优件确实取不到」这一刻才该破例去取它。</p>

    :param targets: 目标表（物品 → 该物品此刻的目标量），即 Java 里的 `itemTargets`
    :param live:    可选的「实时存量」回调（默认用一张会递减的副本），
        让「先抽走一份」立刻影响后续的替补判定（Java 侧读的就是实时仓内量）
    :return: (抽到的 (物品, 数量) 列表, 是否发生了替代供料)
    """
    remaining = dict(holdings)
    live_fn = live or (lambda item: remaining.get(item, 0))
    pulled = []
    substituted = False
    # 第一档：普通目标档（每个 (物品 → 目标量) 只处理一次）
    for item, target in targets.items():
        take = min(target, remaining.get(item, 0))
        if take > 0:
            pulled.append((item, take))
            remaining[item] -= take
    # 第二档：替补档 —— 只有「目标表里的某一件此刻本仓与机器侧都没有」时，
    # 那一件的候选兄弟里才有替补档要处理的东西。
    chamber_machine = dict(holdings)  # 本仓 / 机器侧口径（不含网络）
    for preferred, target in targets.items():
        if target <= 0 or chamber_machine.get(preferred, 0) > 0:
            continue  # 首选件已经在本仓 / 机器侧 ⇒ 备料目标已达成，不需要替补
        for substitute in coupling_siblings(coupling, preferred):
            if substitute in targets:
                continue  # 有自己的目标量 ⇒ 由普通档负责（两档不会重复抽同一件）
            if not needs_substitute_for(coupling, preferred, substitute, live_fn):
                continue
            cap = substitute_targets({preferred: target}, coupling).get(substitute, 0)
            take = min(cap, remaining.get(substitute, 0))
            if take > 0:
                pulled.append((substitute, take))
                remaining[substitute] -= take
                substituted = True
    return pulled, substituted


# 现场：两条配方都在跑，铁粒用光、只剩锌粒 2 个。
# 这一场刻意让锌粒**没有自己的目标量** —— 模拟「候选没有自己的类别」（总线过滤 / 手写样板不登记它），
# 替补档此时是唯一的通路（也是它真正独有的价值）。
pulled, substituted = pull_plan(both, {IRON: 0, ZINC: 2}, {IRON: 2})
check("3a **铁粒用光、只剩锌粒** ⇒ 仍然抽到锌粒（绝不因为候选不齐而断供）；"
      "这一场由替补档抽走（并在供完之后给一次换料提示）",
      pulled == [(ZINC, 2)] and substituted is True,
      "抽取计划=%s substituted=%s" % (pulled, substituted))
pulled, substituted = pull_plan(both, {IRON: 2, ZINC: 5}, {IRON: 2})
check("3b 铁粒有货 ⇒ 用铁粒，不算替代供料（优先级优先；锌粒一份都不抽）",
      pulled == [(IRON, 2)] and substituted is False,
      "抽取计划=%s substituted=%s" % (pulled, substituted))
pulled, substituted = pull_plan(track_only, {IRON: 0, ZINC: 3}, {IRON: 2, ZINC: 2})
check("3c 只有列车轨道时：铁粒没有、锌粒有 ⇒ 抽锌粒，且**不算替代**"
      "（它是并列最优件，走的是它自己的目标量 —— 用户要的正是「优先取有的那个」）",
      pulled == [(ZINC, 2)] and substituted is False,
      "抽取计划=%s substituted=%s" % (pulled, substituted))
pulled, substituted = pull_plan(precision_only, {IRON: 0, ZINC: 9}, {IRON: 1})
check("3d 只有精密构件时：铁粒没有、锌粒再多也**不是候选** ⇒ 抽不到（如实报缺），绝不拿锌粒冒充铁粒",
      pulled == [] and substituted is False,
      "抽取计划=%s substituted=%s" % (pulled, substituted))
pulled, substituted = pull_plan(both, {IRON: 1, ZINC: 1}, {IRON: 2, ZINC: 1})
check("3e 铁粒只够一部分 ⇒ 先抽完手上的铁粒，再让锌粒补上剩下的（两种都取到，不断供）",
      sorted(pulled) == sorted([(IRON, 1), (ZINC, 1)]) and substituted is False,
      "抽取计划=%s substituted=%s" % (pulled, substituted))
pulled, substituted = pull_plan(both, {IRON: 0, ZINC: 0}, {IRON: 2, ZINC: 2})
check("3f 两种都没有 ⇒ 一件都抽不到（如实报缺，绝不凭空生成）",
      pulled == [] and substituted is False,
      "抽取计划=%s substituted=%s" % (pulled, substituted))

# ---- 替补档真正独有的价值：候选本身**没有自己的类别**（总线过滤 / 手写样板不登记它） ----
def substitute_targets_for_unregistered(item_targets, coupling, registered):
    """只保留「没有自己类别」的那些替补 —— 有类别的由普通档负责。"""
    return {item: target for item, target in substitute_targets(item_targets, coupling).items()
            if item not in registered}


check("3j 候选没有自己的类别时，替补档**保留**它（因为普通目标档够不到它）",
      ZINC in substitute_targets_for_unregistered({IRON: 1}, both, registered={IRON}),
      "替补表把没有类别的候选也丢了")
check("3k 该候选**有**自己的类别时由普通档负责（替补档把它摘掉 ⇒ 同一条料不会两档都抽、不会多备一份）",
      substitute_targets_for_unregistered({IRON: 1}, both, registered={IRON, ZINC}) == {},
      "两档同时生效会多备一份：%s"
      % substitute_targets_for_unregistered({IRON: 1}, both, registered={IRON, ZINC}))
pulled, substituted = pull_plan(both, {IRON: 0, ZINC: 4}, {IRON: 2})
check("3m 替补件**没有自己的类别**（普通档够不到）而最优件一点都取不到 ⇒ 替补档破例取它（并给提示）",
      pulled == [(ZINC, 2)] and substituted is True,
      "抽取计划=%s substituted=%s" % (pulled, substituted))

# ---- 目标表的「整组只备一种」：铁粒 / 锌粒各自要不要自己取一份 ----
# 起步原料（精密构件的金板 / 轨道的石头台阶）本来就各自成组、且整组语义 = 「任一份都能起件」，
# 不属于本次讨论，因此推演时把它们单独列出来（与 Java 侧 startIngredients 分支一致）。
check("3g **两条配方同时有订单**、网上两种都有 ⇒ 铁粒必须自己取（精密构件非它不可），锌粒被整组覆盖 ⇒ 不重复取",
      IRON in target_items(both, {IRON: 5, ZINC: 5}) and ZINC not in target_items(both, {IRON: 5, ZINC: 5}),
      "目标表=%s" % target_items(both, {IRON: 5, ZINC: 5}))
check("3h **只有列车轨道**、网上两种都没有 ⇒ 两种都是有效目标（谁到货取谁，整组只备一种）",
      IRON in target_items(track_only, {IRON: 0, ZINC: 0})
      and ZINC in target_items(track_only, {IRON: 0, ZINC: 0}),
      "目标表=%s" % target_items(track_only, {IRON: 0, ZINC: 0}))
check("3h2 **只有列车轨道**、网上只有锌粒 ⇒ 铁粒被锌粒覆盖（不再去抽铁粒）；锌粒自己仍要取（它没在仓里）",
      IRON not in target_items(track_only, {IRON: 0, ZINC: 5})
      and ZINC in target_items(track_only, {IRON: 0, ZINC: 5}),
      "目标表=%s" % target_items(track_only, {IRON: 0, ZINC: 5}))
check("3i **只有精密构件**、网上只有锌粒 ⇒ 铁粒**没有**被覆盖（仍然要去取铁粒），绝不把锌粒当已满足",
      IRON in target_items(precision_only, {IRON: 0, ZINC: 9}),
      "目标表=%s" % target_items(precision_only, {IRON: 0, ZINC: 9}))
check("3l **只有精密构件**、网上躺着列车轨道用剩的锌粒 ⇒ 铁粒这一条类别不会被锌粒吞掉（旧实现正是这里饿死）",
      effective_siblings(precision_only, IRON, {ZINC: 64}) == [],
      "锌粒竟然被当成了铁粒的可用兄弟：%s" % effective_siblings(precision_only, IRON, {ZINC: 64}))


# ==================== D. R3 边沿语义的等价复刻 ====================
section("D) R3 边沿语义：进入发一次、恢复清一次、同一状态原样持续不再发")


class HandoffNotifier(object):
    """Java #reportHandoffUsage 的逐字复刻（去掉冷却与播报 I/O，只留状态机）。"""

    def __init__(self):
        self.signature = ""
        self.sent = []

    def tick(self, used):
        """used = 本轮真的抽进来的 {(最优件, 实取件): 件数}（空 = 本轮没有替代供料）。"""
        signature = self.calc(used)
        if signature == self.signature:
            return None  # 同一状态原样持续：绝不重复播报
        if not signature:
            self.signature = ""
            return None  # 「恢复」这一条边沿：只重置，不播报
        self.signature = signature
        self.sent.append(signature)
        return signature

    @staticmethod
    def calc(used):
        # 与 Java #handoffSignature 逐字一致：指纹里只有「哪一对」，**不含件数**
        # （件数每轮都在变，含进去就会把「同一件事还在持续」误判成状态变化而反复弹窗）。
        parts = ["%s->%s" % (preferred, actual) for (preferred, actual) in used]
        parts.sort()
        return ",".join(parts)


iron_zinc = (IRON, ZINC)
zinc_iron = (ZINC, IRON)

n = HandoffNotifier()
state = [
    ("进入替代状态（铁粒取不到，用锌粒顶上）", {iron_zinc: 1}, True),
    ("同一状态持续（第 2 个备料周期）", {iron_zinc: 1}, False),
    ("同一状态持续（第 20 个备料周期）", {iron_zinc: 1}, False),
    ("替代件数量变了但仍是同一对（1 → 2）", {iron_zinc: 2}, False),
    ("恢复（铁粒到货，不再用替代）", {}, False),
    ("再次进入（铁粒又用光了）", {iron_zinc: 1}, True),
    ("持续", {iron_zinc: 1}, False),
    ("换成另一对替代（锌粒顶铁粒 → 铁粒顶锌粒）也算一次状态变化", {zinc_iron: 1}, True),
    ("持续", {zinc_iron: 1}, False),
]
for index, (title, used, expect_send) in enumerate(state, start=1):
    before = len(n.sent)
    n.tick(used)
    sent = len(n.sent) > before
    check("4-%d %s ⇒ %s" % (index, title, "发一次" if expect_send else "不发"),
          sent is expect_send,
          "实际 %s" % ("发了一次" if sent else "没发"))

check("4a 整段序列只发了 3 条横幅（3 次真正的「进入 / 换了一对」，9 个周期里其余 6 个周期一条都不发）",
      len(n.sent) == 3,
      "实际发了 %d 条：%s" % (len(n.sent), n.sent))

# 关键反例：只做「限频」的实现会把 9 个周期全都发出去 —— 本模型必须不是那种
n2 = HandoffNotifier()
for _ in range(50):
    n2.tick({iron_zinc: 1})
check("4b 反例：同一状态连续 50 个周期 ⇒ 仍然只有 1 条（不是「冷却到了再发」）",
      len(n2.sent) == 1,
      "实际 %d 条" % len(n2.sent))

check("4c 反例：状态复原后**不播报**（只有「进入 / 换一对」才是发消息的边沿）",
      n2.tick({}) is None and len(n2.sent) == 1,
      "恢复时也发了消息")


# ==================== E. 文案硬要求（中英成对 / 键集合一致 / 中文长度 / 禁用词） ====================
section("E) 文案：中英两份同时加、键集合一致、中文 ≤ 40 字、不写禁用词")

zh = read_json(os.path.join(LANG_DIR, "zh_cn.json"))
en = read_json(os.path.join(LANG_DIR, "en_us.json"))
HANDOFF_KEYS = [
    "gui.rs_create_compat.assembly.handoff.title",
    "gui.rs_create_compat.assembly.handoff.entry",
    "gui.rs_create_compat.assembly.handoff.more",
]

check("5a 三条换料键在中英两份里都存在",
      all(key in zh and key in en for key in HANDOFF_KEYS),
      "缺键：%s" % [key for key in HANDOFF_KEYS if key not in zh or key not in en])
check("5b 中英键集合完全一致（没有任何单边键）",
      set(zh) == set(en),
      "差异：%s" % sorted(set(zh) ^ set(en))[:10])
check("5c 中文每条 ≤ 40 字",
      all(len(zh[key]) <= 40 for key in HANDOFF_KEYS),
      "超长：%s" % [(key, len(zh[key])) for key in HANDOFF_KEYS if len(zh[key]) > 40])
check("5d 英文非空",
      all(en[key].strip() for key in HANDOFF_KEYS),
      "空译文")
FORBIDDEN = ["或", "等 %s 种", "轮换"]
check("5e 文案不含禁用词（不写「或」/「等 N 种」/「图标轮换」）",
      all(not any(word in zh[key] or word in en[key] for word in FORBIDDEN)
          for key in HANDOFF_KEYS),
      "命中禁用词：%s" % [key for key in HANDOFF_KEYS
                          if any(word in zh[key] or word in en[key] for word in FORBIDDEN)])
check("5f 条目键带两个参数（原本要的那件 / 实取的那件），与 Java 的两参数调用一致",
      zh["gui.rs_create_compat.assembly.handoff.entry"].count("%") >= 2
      and "%1$s" in zh["gui.rs_create_compat.assembly.handoff.entry"]
      and "%2$s" in zh["gui.rs_create_compat.assembly.handoff.entry"]
      and 'CompletionBannerPayload.localized(KEY_HANDOFF_ENTRY,' in chamber,
      "参数与调用不匹配（会出现 %s 字面量）")

# Java 里引用的键必须真的存在（与 verify_lang_refs 同源，这里只查本轮新增的三条）
for key in HANDOFF_KEYS:
    constant = "KEY_HANDOFF_" + key.rsplit(".", 1)[1].upper()
    has(chamber, '"%s";' % key, "锚点: %s 指向 %s" % (constant, key))

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
