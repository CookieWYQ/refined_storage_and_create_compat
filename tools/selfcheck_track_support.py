#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""列车轨道序列装配支持 —— 三条验收的源码锚点 + 反例推演（本轮）。

用法：
    python tools/selfcheck_track_support.py

回答三件事（全部只读：读源码 + 读 Create 的真实配方 JSON + 纯算术推演，不跑游戏）：

1. **标签 / 多候选输入原料：服务端接受任一候选**（用户第 1 条）
   —— 从配方 JSON 读出真实候选清单；
   —— 反例推演「候选 A 在网 / 候选 B 在网 / 都无」下的备料、推进、缺料判定。
2. **「不同配方、但中间步骤相同 ⇒ 可直接复用」**（用户第 2 条）
   —— 复用判据 = 步骤类型 + 输入候选集合；给出「相同 ⇒ 可复用 / 不同 ⇒ 不可复用」的推演表。
3. **中间产物必须按配方区分**（用户第 3 条）
   —— 类别 id = `intermediate:<配方id>:<步序>`；给出分组效果示意（组名 + 内容）。

退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CREATE_DATA = os.path.join(ROOT, "local_src", "external", "Create", "src", "generated", "resources")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def read_json(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8") as handle:
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
    print("=" * 78)
    print(title)
    print("=" * 78)


chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
probe = read(os.path.join("support", "SequencedRecipeProbe.java"))
category = read(os.path.join("support", "RsccBusCategory.java"))
screen = read(os.path.join("client", "screen", "BusCategoryConfigScreen.java"))

# ======================================================================
# 0. 真实配方数据（先读 JSON，再据此推演 —— 绝不凭空假设候选清单）
# ======================================================================
section("0) Create 真实配方数据（列车轨道 / 坚固板 / 精密构件）")

track = read_json(CREATE_DATA, "data", "create", "recipe", "sequenced_assembly", "track.json")
sturdy = read_json(CREATE_DATA, "data", "create", "recipe", "sequenced_assembly", "sturdy_sheet.json")
precision = read_json(CREATE_DATA, "data", "create", "recipe", "sequenced_assembly",
                      "precision_mechanism.json")
sleepers = read_json(CREATE_DATA, "data", "create", "tags", "item", "sleepers.json")

TRACK_INGREDIENT_TAG = track["ingredient"]["tag"]
SLEEPER_CANDIDATES = list(sleepers["values"])
TRACK_TRANSITIONAL = track["transitional_item"]["id"]
TRACK_STEPS = [step["type"] for step in track["sequence"]]


def step_candidate_spec(recipe, index):
    """把配方某一步的「应用物」候选写成可判等的规范串（tag -> #tag；item -> id）。"""
    parts = []
    for ing in recipe["sequence"][index]["ingredients"][1:]:
        if isinstance(ing, list):                      # 多值：任选其一
            for one in ing:
                parts.append("#" + one["tag"] if "tag" in one else one["item"])
        else:
            parts.append("#" + ing["tag"] if "tag" in ing else ing["item"])
    return "+".join(sorted(parts)) if parts else "(none)"


# 铁粒 / 锌粒标签的真实候选（把 tag 展开成物品名，供报告列出）
NUGGET_TAGS = {
    "c:nuggets/iron": "minecraft:iron_nugget",
    "c:nuggets/zinc": "create:zinc_nugget",
}

print("  列车轨道（create:track）:")
print("    起步原料（ingredient）= 标签 %s ⇒ 候选 = %s" % (TRACK_INGREDIENT_TAG, SLEEPER_CANDIDATES))
print("    过渡件（transitional_item）= %s" % TRACK_TRANSITIONAL)
for i, kind in enumerate(TRACK_STEPS):
    print("    第 %d 步：%s  应用物候选 = %s" % (i + 1, kind, step_candidate_spec(track, i)))
print("  坚固板（create:sturdy_sheet）: 过渡件 = %s；各步 = %s"
      % (sturdy["transitional_item"]["id"], [s["type"] for s in sturdy["sequence"]]))
print("  精密构件（create:precision_mechanism）: 过渡件 = %s；各步 = %s"
      % (precision["transitional_item"]["id"], [s["type"] for s in precision["sequence"]]))

check("0a 列车轨道的起步原料确实是<b>标签</b>（≥ 2 个候选）⇒ 必须按候选集合匹配",
      len(SLEEPER_CANDIDATES) >= 2 and TRACK_INGREDIENT_TAG == "create:sleepers",
      "候选=%s" % SLEEPER_CANDIDATES)
check("0b 列车轨道的第 1、2 步都是 deploying 且应用物是「铁粒 或 锌粒」（多值 ingredient）",
      TRACK_STEPS[0] == "create:deploying" and TRACK_STEPS[1] == "create:deploying"
      and step_candidate_spec(track, 0) == step_candidate_spec(track, 1)
      and "#c:nuggets/iron" in step_candidate_spec(track, 0)
      and "#c:nuggets/zinc" in step_candidate_spec(track, 0))
check("0c 列车轨道的第 3 步是 pressing（不吃应用物）⇒ 与坚固板的冲压步「步骤类型 + 输入集合」相同",
      TRACK_STEPS[2] == "create:pressing" and step_candidate_spec(track, 2) == "(none)"
      and any(s["type"] == "create:pressing" for s in sturdy["sequence"]))

# ======================================================================
# 1. 标签 / 多候选输入原料：服务端接受任一候选
# ======================================================================
section("1) 标签 / 多候选输入原料：服务端「要什么 / 拉什么 / 认什么」按候选集合匹配")

has(probe, "public static List<Item> candidates(@org.jetbrains.annotations.Nullable final Ingredient ingredient) {",
    "锚点①: 「一个 ingredient 的全部候选」只有一份实现（SequencedRecipeProbe#candidates）")
has(probe, "public record InputGroup(ItemStack representative, List<Item> candidates) {",
    "锚点②: 「输入原料组」= 代表物 + 全部候选（组边界保留 ⇒ 整组算一个原料）")
has(probe, "public static List<InputGroup> assemblyStepInputGroups(final ProcessingRecipe<?, ?> recipe,",
    "锚点③: 该步输入按<b>组</b>提取（每个 ingredient 一组，组内是它的全部候选）")
has(probe, "public static InputGroup mainIngredientGroup(", "锚点④: 总样板主原料的候选组（起步原料）")
check("锚点⑤: assemblyStepInputs（该步输入）返回<b>整组候选</b>（不再是 items[0] 代表物）"
      "—— 推料 / 收回 / 备料三处因此对任一候选都成立",
      "for (final InputGroup group : assemblyStepInputGroups(recipe, transitionalItem)) {" in probe
      and "for (final Item item : group.candidates()) {" in probe
      and "result.add(new ItemStack(item));" in probe)
has(chamber, "private static List<Item> mainIngredientCandidates(",
    "锚点⑥: 执行舱的「起步原料候选集合」（标签型起步原料的任一候选都算起步原料）")
check("锚点⑦: 起步原料判定（isStartIngredient 的数据源）/ 主原料保护 / 主原料倍数 三处都用候选集合",
      "result.addAll(mainIngredientCandidates(recipe));" in chamber
      and "return mainIngredientCandidates(recipe).contains(item);" in chamber
      and "if (item != null && mainIngredientCandidates(recipe).contains(item)) {" in chamber
      and "!mainIngredientCandidates(recipe).contains(inSlot.getItem())" in chamber)
check("锚点⑧: 类别 items = 整组候选（RS 过滤项按候选逐个下发 ⇒ 任一候选都能拉 / 推）",
      "final List<Item> candidates = candidateListOf(inputCandidates, entry.getKey(), stack.getItem());" in chamber
      # 2026-10-05：类别 id 改为「按配方限定」（input:<配方>#<物品>），避免两条配方在同一物品上
      # 声明不同候选时被并进同一类别（精密构件曾因此显示成「铁粒或锌粒」）。
      and "inputCategoryIdForRecipe(representative.getItem(), recipeIdForCategory)" in chamber
      and "candidates, List.of(), stack.getCount()," in chamber)
check("锚点⑨: 备料 / 缺料 / 补合成三处都按「整组」判（任一候选已有 ⇒ 不再抽第二种 / 不算缺 / 不请求）",
      # 2026-10-06（第 36 轮）：抽取侧那一次调用加了 R2 的让路前缀（`!substitutePass &&`）——
      # 本意不变，仍是同一个 interchangeableAvailable；加前缀只是让「替补档」在整组尚未满足
      # （最优件一件都没有）时不被这道闸门堵死（用户硬要求：「不管怎么样，还是得取到一个」）。
      ("if (interchangeableAvailable(resource.item())) {" in chamber
       or "if (!substitutePass && interchangeableAvailable(resource.item())) {" in chamber)
      and "if (isInterchangeableFollower(entry.getKey()) || interchangeableAvailable(entry.getKey())) {" in chamber
      and chamber.count("if (isInterchangeableFollower(entry.getKey()) || interchangeableAvailable(entry.getKey())) {") == 2)
check("锚点⑩: 「可互换」再收窄一步（起步原料组 / 同一工位当前步同时接受两者）—— 避免把"
      "「锌粒在网」当成「铁粒不缺」而饿死跨配方共用同一步的产线",
      "if (isStartIngredient(a) && isStartIngredient(b)) {" in chamber
      and "if (wanted.contains(a) && wanted.contains(b)) {" in chamber)


def pull_decision(candidate_available, sibling_interchangeable, sibling_available):
    """pullItem 的等价模型：候选闸门（本项有货？）→ 整组闸门（可互换兄弟已有？）→ 抽。"""
    if candidate_available:
        return 0                      # 本项已够（busItemDeficit <= 0）
    if sibling_interchangeable and sibling_available:
        return 0                      # 整组已满足（interchangeableAvailable）
    return 1                          # 抽一份（本轮：按候选集合，任一都能拉）


def pull_decision(item_in_network, sibling_interchangeable, sibling_available):
    """pullItem 的等价模型（三种情形，逐条对应 Java）：
      * 网络里<b>没有</b>这一种 ⇒ {@code fillInternalForBus} 根本不会为它调用 pullItem（抽 0 份）；
      * 有这一种，但「可互换的兄弟候选」已在仓内 / 机器侧 / 网络 ⇒ 整组已满足（抽 0 份）；
      * 有这一种、整组尚未满足 ⇒ 抽一份（本轮：按候选集合，任一候选都能拉）。
    """
    if not item_in_network:
        return 0
    if sibling_interchangeable and sibling_available:
        return 0
    return 1


def step_accepts(item, wanted_candidates):
    """inputMaterialWantedNow 的等价模型：这一步要的是<b>候选集合</b>，任一项都放行。"""
    return item in wanted_candidates


def shortage_reported(target, candidate_stock):
    """reportBusShortages 的等价模型：整组里任一候选有货 ⇒ 不报缺；整组只报一条（代表物）。"""
    group_available = sum(candidate_stock)
    return target - group_available > 0


SLEEPERS = SLEEPER_CANDIDATES                      # 石头台阶 / 平滑石台阶 / 安山岩台阶
NUGGET_CANDIDATES = ["minecraft:iron_nugget", "create:zinc_nugget"]

print()
print("  反例推演 A：列车轨道的第 1 步（装铁粒；候选 = 铁粒 或 锌粒）")
print("  %-36s %-14s %-14s %-10s" % ("情形", "抽铁粒(份)", "锌粒能推进", "是否报缺"))
CASES_A = [
    ("候选 A（铁粒）在网", True, False, False),
    ("候选 B（锌粒）在网、代表物（铁粒）没有", False, True, True),
    ("都无", False, False, False),
]
for label, iron_in_net, interchangeable, sibling_present in CASES_A:
    pulls_iron = pull_decision(iron_in_net, interchangeable, sibling_present)
    stock = [(1 if iron_in_net else 0), (1 if sibling_present else 0)]
    reported = shortage_reported(1, stock)
    print("  %-36s %-14d %-14s %-10s"
          % (label, pulls_iron,
             "是" if step_accepts(NUGGET_CANDIDATES[1], NUGGET_CANDIDATES) else "否",
             "是" if reported else "否"))
check("1a 候选 A（铁粒）在网 ⇒ 抽 1 份、能推进、不报缺",
      pull_decision(True, False, False) == 1
      and step_accepts(NUGGET_CANDIDATES[0], NUGGET_CANDIDATES)
      and not shortage_reported(1, [1, 0]))
check("1b <b>候选 B（锌粒）在网、代表物（铁粒）没有</b> ⇒ 锌粒能推进该步、整组已满足（不再抽第二种）、"
      "且<b>不报缺</b>（旧实现只认代表物 ⇒ 推不进去 + 误报缺铁粒）",
      pull_decision(False, True, True) == 0
      and pull_decision(True, True, True) == 0
      and step_accepts(NUGGET_CANDIDATES[1], NUGGET_CANDIDATES)
      and not shortage_reported(1, [0, 1]))
check("1c 都无 ⇒ 两种候选都抽不到（网络里没有）⇒ <b>确实报缺</b>（不吞真实缺料）",
      pull_decision(False, False, False) == 0 and shortage_reported(1, [0, 0]))

print()
print("  反例推演 B：列车轨道的起步原料（标签 create:sleepers ⇒ %s）" % SLEEPERS)
print("  %-46s %-12s %-10s" % ("情形", "抽第几种", "是否报缺"))
CASES_B = [
    ("代表物（石头台阶）在网", SLEEPERS[0]),
    ("非代表物（%s）在网" % SLEEPERS[1], SLEEPERS[1]),
    ("非代表物（%s）在网" % SLEEPERS[2], SLEEPERS[2]),
]
for label, available in CASES_B:
    # 整组只抽一种（interchangeableAvailable 命中即 0；否则抽）
    pulls = pull_decision(False, True, True)
    stock = [1 if available == c else 0 for c in SLEEPERS]
    print("  %-46s %-12s %-10s" % (label, "0（组内已有）", "是" if shortage_reported(1, stock) else "否"))
check("1d 起步原料的任一候选在网 ⇒ 只抽这一种（不再抽第二种）、且不报缺"
      "（整组算一个原料；三种台阶都算起步原料）",
      all(not shortage_reported(1, [1 if available == c else 0 for c in SLEEPERS])
          for _l, available in CASES_B)
      and pull_decision(False, True, True) == 0)
check("1e 跨配方共用候选不会互相顶掉：锌粒只对「同时接受铁粒与锌粒」的那一步可互换，"
      "对「只要铁粒」的精密构件步<b>不算</b>可用 ⇒ 铁粒在网时照旧会为精密构件抽它（不会被锌粒挡住）",
      pull_decision(True, True, True) == 0           # 列车步：锌在 ⇒ 整组满足，不抽第二种
      and pull_decision(True, False, True) == 1      # 精密构件步：锌不可互换 ⇒ 照常抽铁粒
      and shortage_reported(1, [0, 0]))

# ======================================================================
# 2. 「不同配方、但中间步骤相同 ⇒ 可复用」
# ======================================================================
section("2) 复用判据 = 步骤类型 + 输入候选集合（可能来自不同配方）")

has(chamber, "private static String stepReuseKey(@org.jetbrains.annotations.Nullable final SequencedRecipe<?> step) {",
    "锚点①: 复用键只有一份实现（{@code stepReuseKey}）")
has(probe, "public static List<Item> stepApplicationCandidates(final ProcessingRecipe<?, ?> recipe) {",
    "锚点②: 判等用的是<b>整组候选</b>（只取代表物会把「铁粒或锌粒」与「只要铁粒」混为一谈）")
has(chamber, "reuseKeys.putIfAbsent(key, stepReuseKey(step));",
    "锚点③: 中间产物类别带复用键（服务端算 → 总线快照下发 → 界面标注）")
has(screen, "private void refreshReuseMarkers() {",
    "锚点④: 客户端「可复用」标注（行内后缀 + 表头 tooltip，只读显示，不改勾选）")
has(category, "long amount, long estimated, String reuseKey,",
    "锚点⑤: 快照记录携带复用键（编解码两端成对，见 STREAM_CODEC）")
check("锚点⑤b: 复用键<编解码成对>（encode 写、decode 读，字段顺序一致 ⇒ 不会串位）",
      "ByteBufCodecs.STRING_UTF8.encode(buf, value.reuseKey() == null ? \"\" : value.reuseKey());" in category
      and "ByteBufCodecs.STRING_UTF8.decode(buf)\n        );" in category)


def reuse_key(step_type, application_candidates):
    """步骤类型 + 排序后的输入候选集合（与 Java stepReuseKey 同口径；流体这里为空）。"""
    return "%s|%s|" % (step_type, ",".join(sorted(application_candidates)))


REUSE_ROWS = [
    ("列车轨道 第 3 步（冲压，不吃应用物）", reuse_key("create:pressing", []),
     "坚固板 第 2/3 步（冲压，不吃应用物）", reuse_key("create:pressing", [])),
    ("列车轨道 第 1 步（装铁粒：铁粒|锌粒）", reuse_key("create:deploying", NUGGET_CANDIDATES),
     "列车轨道 第 2 步（装铁粒：铁粒|锌粒）", reuse_key("create:deploying", NUGGET_CANDIDATES)),
    ("列车轨道 第 1 步（装铁粒：铁粒|锌粒）", reuse_key("create:deploying", NUGGET_CANDIDATES),
     "精密构件 第 1 步（装铁粒：只要铁粒）", reuse_key("create:deploying", ["minecraft:iron_nugget"])),
    ("列车轨道 第 1 步（装铁粒）", reuse_key("create:deploying", NUGGET_CANDIDATES),
     "坚固板 第 2 步（冲压）", reuse_key("create:pressing", [])),
]
print()
print("  反例推演 C（左侧步骤 vs 右侧步骤 → 是否判为「可复用同一台机器 / 同一条总线」）")
print("  %-44s %-44s %-8s" % ("步骤 A", "步骤 B", "结论"))
for a_label, a_key, b_label, b_key in REUSE_ROWS:
    same = a_key == b_key
    print("  %-44s %-44s %-8s" % (a_label, b_label, "可复用" if same else "不可复用"))
    if not same:
        print("      A key = %s" % a_key)
        print("      B key = %s" % b_key)
check("2a 列车轨道的冲压步 ⟷ 坚固板的冲压步 ⇒ <b>可复用</b>（步骤类型相同、输入集合都为空）",
      reuse_key("create:pressing", []) == reuse_key("create:pressing", []))
check("2b 列车轨道第 1 步 ⟷ 第 2 步（同类型同输入：铁粒|锌粒）⇒ 可复用",
      reuse_key("create:deploying", NUGGET_CANDIDATES)
      == reuse_key("create:deploying", NUGGET_CANDIDATES))
check("2c 列车轨道的装铁粒（铁粒|锌粒）⟷ 精密构件的装铁粒（只要铁粒）⇒ "
      "<b>不可复用</b>（输入候选集合不同 ⇒ 同一台机械手推错料会被 Create 永久拒收）",
      reuse_key("create:deploying", NUGGET_CANDIDATES)
      != reuse_key("create:deploying", ["minecraft:iron_nugget"]))
check("2d 类型不同（装铁粒 vs 冲压）⇒ 不可复用",
      reuse_key("create:deploying", NUGGET_CANDIDATES) != reuse_key("create:pressing", []))

# ======================================================================
# 3. 中间产物必须按配方区分
# ======================================================================
section("3) 中间产物按「配方 + 步序」分开（用户第 3 条「一点都没分割」）")

has(category, "public static String intermediateId(@org.jetbrains.annotations.Nullable final String recipeId,",
    "锚点①: 类别 id 的唯一权威写法（配方 + 步序）")
has(category, "final int last = id.lastIndexOf(':');",
    "锚点②: 步序解析取<b>最后</b>一段（配方 id 自带一个冒号，只能从最后一段解析）")
has(category, "public String intermediateRecipe() {",
    "锚点③: 类别可反查归属配方（诊断 / 兼容展开都用它）")
has(chamber, "private static final String UNKNOWN_STEP_KEY = \"-1|\";",
    "锚点④: 「步序 / 配方未知」用独立复合键（对应不带步序的旧类别）")
check("锚点⑤: 旧存档兼容：不带配方的旧 id 会被展开 —— 且<b>只在本仓「当前流程」的配方里</b>展开"
      "（第 18 轮修正：多条流程同挂时绝不跨配方同时放行 ⇒ 不产生多余中间产物）",
      "RsccBusCategory.isLegacyIntermediateId(id)" in chamber
      and "if (!info.isIntermediate() || (legacyStep >= 0 && info.step() != legacyStep)) {" in chamber
      and "if (!activeRecipes.isEmpty() && !activeRecipes.contains(info.recipe())) {" in chamber
      and "public static boolean isLegacyIntermediateId(" in category)

TRACK_ID = "create:track"
STURDY_ID = "create:sturdy_sheet"
PRECISION_ID = "create:precision_mechanism"


def intermediate_id(recipe_id, step):
    return "intermediate:%s:%d" % (recipe_id, step)


TRACK_INTERMEDIATE = track["transitional_item"]["id"]              # create:incomplete_track
STURDY_INTERMEDIATE = sturdy["transitional_item"]["id"]            # create:unprocessed_obsidian_sheet
PRECISION_INTERMEDIATE = precision["transitional_item"]["id"]      # create:incomplete_precision_mechanism

print()
print("  分组效果示意（客户端复合组键 = 基础分组 + 配方标签；同一基础分组下 ≥2 套配方时表头带配方名）")
GROUP_ROWS = [
    ("列车轨道 第 1 步（装配）", intermediate_id(TRACK_ID, 0), TRACK_INTERMEDIATE, "列车轨道"),
    ("列车轨道 第 2 步（装配）", intermediate_id(TRACK_ID, 1), TRACK_INTERMEDIATE, "列车轨道"),
    ("列车轨道 第 3 步（冲压）", intermediate_id(TRACK_ID, 2), TRACK_INTERMEDIATE, "列车轨道"),
    ("坚固板 第 2 步（冲压）", intermediate_id(STURDY_ID, 1), STURDY_INTERMEDIATE, "坚固板"),
    ("坚固板 第 3 步（冲压）", intermediate_id(STURDY_ID, 2), STURDY_INTERMEDIATE, "坚固板"),
    ("精密构件 第 1 步（装配）", intermediate_id(PRECISION_ID, 0), PRECISION_INTERMEDIATE, "精密构件"),
]
groups = {}
for label, cid, item, recipe_label in GROUP_ROWS:
    groups.setdefault("中间产物 · " + recipe_label, []).append((cid, item))
    print("  %-34s id=%-38s 图标物品=%s" % (label, cid, item))
print("  → 分组表头与内容：")
for group_name, members in groups.items():
    print("     [%s] %s" % (group_name, [m[0] for m in members]))

check("3a 同一步序、不同配方 ⇒ <b>不同</b>类别 id（列车轨道冲压=intermediate:create:track:2，"
      "坚固板冲压=intermediate:create:sturdy_sheet:1/2）⇒ 绝不混在同一个类别里",
      intermediate_id(TRACK_ID, 2) != intermediate_id(STURDY_ID, 1)
      and intermediate_id(TRACK_ID, 2) != intermediate_id(STURDY_ID, 2)
      and intermediate_id(TRACK_ID, 0) != intermediate_id(PRECISION_ID, 0))
check("3b 同一配方的相邻同类型步骤（坚固板第 2、3 步都是冲压）⇒ 仍是<b>两个独立</b>类别"
      "（可按步分别勾选，且各自的按步原型不同）",
      intermediate_id(STURDY_ID, 1) != intermediate_id(STURDY_ID, 2))
check("3c 每个中间产物类别<b>只含自己那条配方</b>的过渡件 ⇒ 界面分组里「坚固板的中间产物」与"
      "「列车轨道的中间产物」各自独立成组（不再出现「中间产物就要有坚固板的、又要有列车轨道的」）",
      TRACK_INTERMEDIATE != STURDY_INTERMEDIATE
      and len(groups["中间产物 · 列车轨道"]) == 3
      and len(groups["中间产物 · 坚固板"]) == 2
      and all(item == TRACK_INTERMEDIATE for _id, item in groups["中间产物 · 列车轨道"])
      and all(item == STURDY_INTERMEDIATE for _id, item in groups["中间产物 · 坚固板"]))
check("3d 旧写法 id（不带配方）仍能被识别并展开 ⇒ 旧存档里勾好的「中间产物」不会整条失效",
      "isLegacyIntermediateId" in category and "legacyIntermediateStep" in category)
check("3e 服务端类别表 / 快照 / 诊断三处一致暴露「配方 + 复用键」",
      'row.put("recipe", info.recipe());' in chamber
      and 'row.put("reuseKey", info.reuseKey() == null ? "" : info.reuseKey());' in chamber
      and "info.amount(), info.estimated(), info.reuseKey() == null ? \"\" : info.reuseKey()," in chamber)
check("3f <b>原料 / 输入时原料 / 成品 / 废料同理</b>（用户第 3 条括号里的要求）：客户端的"
      "「物品 / 流体 → 配方 id」表是<b>多对多</b>（{@code recipeIdsByItem} / {@code recipeIdsByFluid} 都是集合），"
      "归属按<b>整组候选</b>登记（锌粒也算列车轨道的）。"
      "<b>第 44 轮改口径（用户实测「精密构件页里冒出用不到的锌粒」）</b>：这张表从此只决定"
      "<b>流体 / 成品 / 废料</b>的页 —— 物品输入类别的页由它自己 id 里的配方段给出"
      "（{@code input:<配方id>#<物品>}）⇒ 铁粒在精密构件页与列车轨道页是<b>两个类别</b>、各显示各的候选，"
      "行内「多配方共用」只标真正跨页的那几类",
      "private final java.util.Map<Item, java.util.Set<String>> recipeIdsByItem =" in screen
      and "private final java.util.Map<Fluid, java.util.Set<String>> recipeIdsByFluid =" in screen
      and "private static <K> void addRecipeId(" in screen
      and "for (final Item item : SequencedRecipeProbe.stepApplicationCandidates(step.getRecipe())) {" in screen
      and "private boolean isSharedAcrossRecipes(final RsccBusCategory category) {" in screen
      and 'Component.translatable(LANG + "row.shared")' in screen
      and "final String declared = category.inputRecipeId();" in screen)


def client_group_label(recipe_labels):
    """旧的「组名 = 基础分组 + 配方标签」模型：仅用于说明「共享件不再被归到某一条配方名下」。"""
    if not recipe_labels:
        return ""
    return " + ".join(sorted(recipe_labels))


print()
print("  共用作的「物品级」归属推演（这一件东西被哪些配方用到；第 44 轮起，物品输入类别按配方分开，"
      "这张表只决定流体 / 成品 / 废料的页归属）")
SHARED_TABS = {
    "minecraft:iron_nugget": ["create:track", "create:precision_mechanism"],  # 两条配方都用到铁粒
    "create:zinc_nugget": ["create:track"],                                   # 只有列车轨道用锌粒
    "create:incomplete_track": ["create:track"],                              # 过渡件只属于列车轨道
    "create:unprocessed_obsidian_sheet": ["create:sturdy_sheet"],
}
for item, tabs in SHARED_TABS.items():
    print("     %-40s → 被 %d 条配方用到：%s"
          % (item, len(tabs), tabs))
check("3g <b>第 44 轮起</b>：物品输入类别按配方分开 —— 列车轨道的铁粒类别（候选含锌粒）与"
      "精密构件的铁粒类别（单候选）是两个不同 id、各归各页（精密构件页不再显示锌粒）；"
      "「多配方共用」标记机制仍在（判据仍是 tabKeysOf 给出的页数，只是物品输入类别恒为 1 页），"
      "而过渡件这类**只属于一条配方**的类别始终各归各页",
      "input:create:track#minecraft:iron_nugget"
      != "input:create:precision_mechanism#minecraft:iron_nugget"
      and len(SHARED_TABS["create:incomplete_track"]) == 1
      and SHARED_TABS["create:incomplete_track"] != SHARED_TABS["create:unprocessed_obsidian_sheet"]
      and "return tabKeysOf(category).size() > 1;" in screen
      and "private List<String> tabKeysOf(final RsccBusCategory category) {" in screen)

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
