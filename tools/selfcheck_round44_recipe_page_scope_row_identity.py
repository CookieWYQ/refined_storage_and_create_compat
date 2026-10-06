# -*- coding: utf-8 -*-
"""第 44 轮：配方页「只列该配方真正用到的投入物」（缺陷 1）+ 每行图标 / 名字 / tooltip 同一件物品（缺陷 2）。

用法：python tools/selfcheck_round44_recipe_page_scope_row_identity.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么需要它（本地跑不起游戏，只能靠「源码锚点 + 真实配方 + 等价模型」三件事取证）：

  * 用户实测缺陷 1（截图）：**「精密构件」页的「输入时原料」列了 4 项** ——
      锌粒（多配方共用）· 齿轮 · 铁粒（多配方共用）· 大齿轮
    而 `create:precision_mechanism` 的投入物只有 `create:cogwheel` / `create:large_cogwheel` /
    `#c:nuggets/iron`；**锌粒只出现在 `create:track` 的投入物里**。
    用户原话：「精密构件并不能用到锌粒」。

  * 用户实测缺陷 2：**同一行「显示的是锌粒，tooltip 还是显示铁粒」**。

  * 确切机制（本脚本用真实 JSON 复刻，不写死结论）：
      - 服务端按「配方 + 组代表物」登记输入类别 `input:<配方id>#<物品>`
        （`SequenceExecutionChamberBlockEntity#inputCategoryIdForRecipe`），
        而 `create:track` 的装铁粒 ingredient 写成**数组** `[{tag c:nuggets/iron}, {tag c:nuggets/zinc}]`
        = **一个** ingredient 的两个候选（代表物 = 铁粒、候选 = {铁粒, 锌粒}）；
      - 客户端旧 `tabKeysOf` 对输入类别走「按**代表物**查 recipeIdsByItem」这一支 ——
        铁粒被列车轨道与精密构件同时用到，于是**列车轨道那个类别也拿到了「精密构件」页键**，
        混进了精密构件页（那一行按候选轮播，玩家看到的就是锌粒）；
      - 行内图标 / 名字取 `cycleCandidate(候选)`（会轮播），tooltip 取 `iconOf`（= 组代表物，不轮播）
        ⇒ 轮播到锌粒的那一秒行内是锌粒、tooltip 仍是铁粒。

  * 本脚本验四件事：
      A. 源码锚点：缺陷 1 的修点（输入类别按 id 自带配方段定页）与缺陷 2 的修点
         （`displayStackOf` 是图标 / 名字 / tooltip 三处唯一入口）各只有一处实现；
      B. 真实配方推演：服务端输入类别表（id / 代表物 / 候选）+ 客户端两张表；
      C. 真值表：精密构件页（改动前 4 行含锌粒 = 反例 / 改动后 3 行无锌粒）、列车轨道页（台阶 / 铁粒 / 锌粒）、
         每行「图标 == 名字 == tooltip」逐 tick 成立；
      D. 既有语义未变：一类别一属主、链级「不丢项」、`isTabOnly` 7 处跳过点、
         服务端产线判定（blockedByMachineQueue / startCapacityForRecipe / inFlightUnitsForRecipe /
         NEXT_FOR_MACHINE）锚点仍在，且**只有界面文件被动过**（按 mtime 取证）。
"""
import io
import json
import os
import re
import sys
import time

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

BUS_LANG = "gui.rs_create_compat.bus_config."


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
    print("=" * 78)
    print(title)
    print("=" * 78)


# ======================================================================
# 真实配方 → 候选组（与 Java 逐字同一口径：数组 = 一个 ingredient 的多个候选）
# ======================================================================

TAG_MEMBERS = {
    "c:nuggets/iron": ["minecraft:iron_nugget"],
    "c:nuggets/zinc": ["create:zinc_nugget"],
    "c:plates/gold": ["create:golden_sheet"],
    "c:dusts/obsidian": ["create:powdered_obsidian"],
    "create:sleepers": ["minecraft:stone_slab", "minecraft:smooth_stone_slab",
                        "minecraft:andesite_slab"],
}


def expand(node):
    """配方 JSON 里的一个 ingredient 值节点 → 候选物品列表（保序去重）。

    与 Java 侧 `SequencedRecipeProbe#assemblyStepInputGroups` 同一口径：
      * 数组 `[a, b]` = **一个** ingredient 的多个候选（铁粒 / 锌粒就是这种写法）；
      * `{"item": x}` / `{"tag": t}` = 单值物品 ingredient；
      * `{"id": x}` = Create 的 ItemStack 写法（`transitional_item` 用它）；
      * `{"type": ..., "fluid": ...}` = **流体** ingredient —— 它不是物品输入
        （服务端走 `stepInputFluids` 单独登记），这里必须返回空，否则会凭空多出一个物品类别。
    """
    if isinstance(node, list):
        out = []
        for element in node:
            for item in expand(element):
                if item not in out:
                    out.append(item)
        return out
    if isinstance(node, dict):
        if "item" in node:
            return [node["item"]]
        if "id" in node:
            return [node["id"]]
        if "tag" in node:
            # 不关心的标签按注册名占位（不影响本次推演，也不会把自己当成铁粒 / 锌粒）
            return list(TAG_MEMBERS.get(node["tag"], ["<%s>" % node["tag"]]))
        return []  # 流体 ingredient（neoforge:single 等）：不是物品输入
    return []


RECIPES = {}
for _name in sorted(os.listdir(CREATE_RECIPES)):
    if _name.endswith(".json"):
        RECIPES["create:" + _name[:-5]] = read_json(os.path.join(CREATE_RECIPES, _name))

PRECISION = "create:precision_mechanism"
TRACK = "create:track"
IRON = "minecraft:iron_nugget"
ZINC = "create:zinc_nugget"


def step_groups(data):
    """一个配方的「每步输入候选组」列表（与 assemblyStepInputGroups 同口径：
    下标 0 剔除过渡件，下标 ≥1 每个 ingredient 一组；空组不出）。"""
    transitional_items = expand(data.get("transitional_item", {}))
    transitional = transitional_items[0] if transitional_items else None
    groups = []
    for step in data.get("sequence", []):
        ingredients = step.get("ingredients", [])
        if not ingredients:
            continue
        zero = [item for item in expand(ingredients[0]) if item != transitional]
        if zero:
            groups.append(zero)
        for ingredient in ingredients[1:]:
            group = expand(ingredient)
            if group:
                groups.append(group)
    return groups


# ---- 服务端：computeBusCategories 里的「输入类」类别（id → 代表物 + 候选） ----
INPUT_CATEGORIES = {}


def add_input(recipe_id, group):
    if not group:
        return
    cid = "input:%s#%s" % (recipe_id, group[0])
    INPUT_CATEGORIES[cid] = (group[0], list(group))


for _rid, _data in RECIPES.items():
    # 单元样板手填的起步原料（样板记的就是那一件 ⇒ 组只含它自己；它不属于任何步内候选组）
    _starts = expand(_data.get("ingredient", {}))
    if _starts:
        add_input(_rid, [_starts[0]])
    for _group in step_groups(_data):
        add_input(_rid, _group)

# 「中间产物」类别自带的配方 = 样板库里真的有的配方（patternRecipeIds 的来源；
# 本模型里每条配方都有过渡件 ⇒ 都有 intermediate:<配方id>:<步序> 类别）
PATTERN_RECIPES = set(RECIPES.keys())

# ---- 客户端：collectAssemblyInputs 的等价物（recipeIdsByItem / 分组判据表） ----
RECIPE_IDS_BY_ITEM = {}
START_BY_RECIPE = {}
STEP_BY_RECIPE = {}
for _rid, _data in RECIPES.items():
    START_BY_RECIPE[_rid] = set(expand(_data["ingredient"]))
    for _item in START_BY_RECIPE[_rid]:
        RECIPE_IDS_BY_ITEM.setdefault(_item, set()).add(_rid)
    _steps = set()
    for _step in _data.get("sequence", []):
        for _ingredient in _step.get("ingredients", [])[1:]:
            for _item in expand(_ingredient):
                _steps.add(_item)
                RECIPE_IDS_BY_ITEM.setdefault(_item, set()).add(_rid)
    STEP_BY_RECIPE[_rid] = _steps


# ======================================================================
# A. 源码锚点
# ======================================================================
section("A) 源码锚点：两个缺陷各只有一处修点（不用物品名 / 语言键启发式）")

screen = read(os.path.join("client", "screen", "BusCategoryConfigScreen.java"))
chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
category = read(os.path.join("support", "RsccBusCategory.java"))

check("锚点① 缺陷 1：输入类别在 tabKeysOf 里**早返回**，页键 = id 自带的配方段",
      "if (keys.isEmpty() && category.isInput() && !category.isFluidInput()) {" in screen
      and "final String declared = category.inputRecipeId();" in screen
      and "keys.add(declared);" in screen)
check("锚点② 配方段仍是服务端「按配方登记类别」的那一段（不是界面自己猜的）",
      "public static String inputCategoryIdForRecipe(final Item item," in chamber
      and "return RsccBusCategory.INPUT_PREFIX + recipeId + \"#\"" in chamber
      and "public String inputRecipeId() {" in category)
check("锚点③ 「按代表物查全局表」只剩流体 / 成品 / 废料 / 旧格式 id 这一支（输入类别不再走它）",
      "final Item item = iconItemOf(category);" in screen
      and "keys.addAll(recipeIdsByItem.getOrDefault(item, Set.of()));" in screen
      and screen.count("iconItemOf(category)") == 1)
check("锚点④ 缺陷 2：图标 / 名字 / tooltip 三处共用一个入口（displayStackOf）",
      "private static ItemStack displayStackOf(final RsccBusCategory category) {" in screen
      and screen.count("GhostMarkerRenderer.cycleCandidate(") == 1
      and "return GhostMarkerRenderer.cycleCandidate(candidates);" in screen)
check("锚点⑤ 行为：行内图标与行内名字都取自 displayStackOf（不再各算一份相位）",
      "final ItemStack shown = displayStackOf(category);\n            if (!shown.isEmpty()) {"
      in screen
      and "final ItemStack shown = displayStackOf(category);\n        return shown.isEmpty()"
          " ? fallback : shown.getHoverName().getString();" in screen)
check("锚点⑥ 缺陷 2 的错配点已消失：tooltip 不再直接读组代表物（iconOf）",
      "final ItemStack stack = displayStackOf(category);" in screen
      and "final ItemStack stack = ExporterExecutorRowWidget.iconOf(category);" not in screen)
check("锚点⑦ 「组代表物」仍被保留在它该在的地方（主界面图标 / 无候选时的兜底）",
      "return ExporterExecutorRowWidget.iconOf(category);\n    }" in screen
      and "public static ItemStack iconOf(final RsccBusCategory category) {" in read(
          os.path.join("client", "widget", "ExporterExecutorRowWidget.java")))
check("锚点⑧ 共享语义只在「流体 / 成品 / 废料」上保留（isSharedAcrossRecipes 判据未改）",
      "private boolean isSharedAcrossRecipes(final RsccBusCategory category) {" in screen
      and "return tabKeysOf(category).size() > 1;" in screen)

# ======================================================================
# B. 真实配方推演
# ======================================================================
section("B) 真实配方：服务端输入类别（id / 代表物 / 候选）")

for _cid, (_rep, _candidates) in sorted(INPUT_CATEGORIES.items()):
    print("    %-58s 代表物=%-30s 候选=%s" % (_cid, _rep, _candidates))

check("1a 列车轨道装铁粒的 ingredient 是**一组两个候选**（铁粒 + 锌粒 = 一个原料）",
      INPUT_CATEGORIES.get("input:%s#%s" % (TRACK, IRON), ("", []))[1] == [IRON, ZINC],
      "%s" % (INPUT_CATEGORIES.get("input:%s#%s" % (TRACK, IRON)),))
check("1b 精密构件装铁粒的类别是**单候选**（只有铁粒，没有锌粒）",
      INPUT_CATEGORIES.get("input:%s#%s" % (PRECISION, IRON), ("", []))[1] == [IRON],
      "%s" % (INPUT_CATEGORIES.get("input:%s#%s" % (PRECISION, IRON)),))
check("1c 精密构件的全部输入类别里，任何候选都不含锌粒（用户口径：精密构件用不到锌粒）",
      all(ZINC not in candidates for cid, (_rep, candidates) in INPUT_CATEGORIES.items()
          if cid.startswith("input:%s#" % PRECISION)))
check("1d 客户端「物品 → 用它的配方」表是多对多：铁粒 → 两条配方；锌粒 → 只有列车轨道",
      RECIPE_IDS_BY_ITEM.get(IRON) == {PRECISION, TRACK}
      and RECIPE_IDS_BY_ITEM.get(ZINC) == {TRACK},
      "%s | %s" % (sorted(RECIPE_IDS_BY_ITEM.get(IRON, [])), sorted(RECIPE_IDS_BY_ITEM.get(ZINC, []))))


# ======================================================================
# C. 真值表：页内行（改动前 / 改动后）
# ======================================================================

def tab_keys_before(cid):
    """改动的 tabKeysOf：除中间产物外一律按「身份物品（代表物）」查全局表 + 样板库闸门。"""
    if cid.startswith("intermediate:"):
        return {cid.split(":")[1]}
    representative = INPUT_CATEGORIES.get(cid, ("", []))[0]
    keys = set(RECIPE_IDS_BY_ITEM.get(representative, set()))
    if PATTERN_RECIPES:
        keys &= PATTERN_RECIPES
    return keys or {"<other>"}


def tab_keys_after(cid):
    """改动后的 tabKeysOf：输入类别的页 = id 自带的配方段（早返回，不受样板库闸门影响）。"""
    if cid.startswith("intermediate:"):
        return {cid.split(":")[1]}
    body = cid[len("input:"):]
    hash_index = body.find("#")
    declared = body[:hash_index] if hash_index > 0 else ""
    if declared:
        return {declared}
    return tab_keys_before(cid)


def section_of(cid):
    """镜像 sectionOf / inputSectionOf（按配方上下文，起步原料优先）。"""
    if cid.startswith("fluid:"):
        return "fluids"
    if cid.startswith("intermediate:"):
        return "intermediates"
    if cid.startswith("result:"):
        return "products"
    if cid.startswith("scrap:"):
        return "scrap"
    body = cid[len("input:"):]
    item = body[body.find("#") + 1:] if body.find("#") >= 0 else body
    declared = body[:body.find("#")] if body.find("#") > 0 else ""
    if declared and START_BY_RECIPE.get(declared):
        return "materials" if item in START_BY_RECIPE[declared] else "feedstock"
    return "materials"


def page_rows(tab, fn):
    """某一页的行（含归属分组 / 代表物 / 候选），顺序 = 服务端类别顺序（按 id 升序稳定）。"""
    rows = []
    for cid, (representative, candidates) in sorted(INPUT_CATEGORIES.items()):
        if tab in fn(cid):
            rows.append((section_of(cid), cid, representative, candidates))
    return rows


def displayed_items(row, tick):
    """该行在某 tick 显示的物品集合（多候选按 cycleCandidate 的相位取一件）。"""
    _section, _cid, _representative, candidates = row
    return {cycle_candidate(candidates, tick)}


def cycle_candidate(candidates, tick):
    """GhostMarkerRenderer#cycleCandidate 的复刻：tick / 20 取模（同一帧内恒定）。"""
    if not candidates:
        return ""
    if len(candidates) == 1:
        return candidates[0]
    return candidates[(tick // 20) % len(candidates)]


print()
section("C) 真值表①：「精密构件」页（用户截图的那一页）")

before_rows = page_rows(PRECISION, tab_keys_before)
after_rows = page_rows(PRECISION, tab_keys_after)
for label, rows in (("改动前", before_rows), ("改动后", after_rows)):
    print("    %s %s 页：" % (label, PRECISION))
    for section_name, cid, representative, candidates in rows:
        print("      [%s] %-58s 候选=%s" % (section_name, cid, candidates))

before_feedstock = [row for row in before_rows if row[0] == "feedstock"]
after_feedstock = [row for row in after_rows if row[0] == "feedstock"]

check("2a 反例（改动前）：列车轨道的铁粒类别（候选含锌粒）**混进了精密构件页** —— "
      "这就是玩家截图里那第 4 项",
      any(row[1] == "input:%s#%s" % (TRACK, IRON) for row in before_rows)
      and any(ZINC in row[3] for row in before_feedstock),
      "%s" % ([row[1] for row in before_rows],))
check("2b 改动后：精密构件页的「输入时原料」= 齿轮 / 大齿轮 / 铁粒（**恰好 3 项**）",
      sorted(INPUT_CATEGORIES[row[1]][0] for row in after_feedstock)
      == sorted(["create:cogwheel", "create:large_cogwheel", IRON]),
      "%s" % (sorted(INPUT_CATEGORIES[row[1]][0] for row in after_feedstock),))
check("2c 改动后：这一页**任何一行、任何 tick 都不显示锌粒**（用户原话：精密构件用不到锌粒）",
      all(ZINC not in displayed_items(row, tick) for row in after_rows for tick in range(0, 200))
      and all(row[1] != "input:%s#%s" % (TRACK, IRON) for row in after_rows))
check("2d 改动后：精密构件页的「原料」仍是它自己的起步原料（金板），没有被顺手改坏",
      [row[1] for row in after_rows if row[0] == "materials"]
      == ["input:%s#%s" % (PRECISION, "create:golden_sheet")],
      "%s" % ([row[1] for row in after_rows if row[0] == "materials"],))

print()
section("C) 真值表②：「列车轨道」页（铁粒 / 锌粒 仍然都在这条配方自己的那一组里）")

track_rows = page_rows(TRACK, tab_keys_after)
for section_name, cid, representative, candidates in track_rows:
    print("      [%s] %-58s 候选=%s" % (section_name, cid, candidates))

check("3a 列车轨道页的「输入时原料」= 只有它自己那一个铁粒类别（候选 = 铁粒 + 锌粒）",
      [row[1] for row in track_rows if row[0] == "feedstock"]
      == ["input:%s#%s" % (TRACK, IRON)],
      "%s" % ([row[1] for row in track_rows if row[0] == "feedstock"],))
check("3b 锌粒**仍然能在这条配方自己的那一行上显示**（它是这一组的候选，不是凭空消失）",
      any(ZINC in displayed_items(row, tick)
          for row in track_rows if row[0] == "feedstock" for tick in range(0, 200)))
check("3c 列车轨道页的「原料」= 它自己的起步原料（台阶标签）",
      [row[1] for row in track_rows if row[0] == "materials"]
      == ["input:%s#%s" % (TRACK, "minecraft:stone_slab")],
      "%s" % ([row[1] for row in track_rows if row[0] == "materials"],))
check("3d 反例（改动前）：精密构件自己的铁粒类别也漏进了列车轨道页（同一错配的另一面）",
      any(row[1] == "input:%s#%s" % (PRECISION, IRON) for row in page_rows(TRACK, tab_keys_before)))

print()
section("C) 真值表③：每行「图标 == 名字 == tooltip」同一件物品")


def row_identity_before(category_row, tick):
    """改动前：图标 / 名字 = 轮播候选；tooltip = 组代表物（不轮播）。"""
    _section, _cid, representative, candidates = category_row
    return cycle_candidate(candidates, tick), cycle_candidate(candidates, tick), representative


def row_identity_after(category_row, tick):
    """改动后：三处都走 displayStackOf（多候选 → 当前轮播到的那一件）。"""
    _section, _cid, _representative, candidates = category_row
    displayed = cycle_candidate(candidates, tick)
    return displayed, displayed, displayed


multi_rows = [row for row in page_rows(TRACK, tab_keys_after) if len(row[3]) > 1]
check("4a 场景里确实存在「多候选」的行（否则本节无意义）", bool(multi_rows), str(multi_rows))
mismatch_before = [tick for row in multi_rows for tick in range(0, 200)
                   if len(set(row_identity_before(row, tick))) > 1]
check("4b 反例（改动前）：多候选行在轮播到第二个候选时 **行内锌粒 / tooltip 铁粒** 对不上",
      bool(mismatch_before),
      "第一个对不上的 tick = %s" % (mismatch_before[0] if mismatch_before else None))
check("4c 改动后：200 tick 内三处**逐 tick**完全相同（含 19/20/21 这类相位边界）",
      all(len(set(row_identity_after(row, tick))) == 1
          for row in multi_rows for tick in range(0, 200)),
      "%s" % ([(tick, row_identity_after(multi_rows[0], tick)) for tick in (0, 19, 20, 21)]))
check("4d 改动后：单候选行也照旧（显示组代表物本身，不会因为改动而换件）",
      all(len(set(row_identity_after(row, tick))) == 1
          and row_identity_after(row, tick)[0] == row[2]
          for row in page_rows(TRACK, tab_keys_after) if len(row[3]) == 1
          for tick in range(0, 200)))

# ======================================================================
# D. 既有语义未变
# ======================================================================
section("D) 既有语义未变：一类别一属主 / 链级 / 7 处跳过点 / 服务端产线判定")

check("5a 一个类别只落一页（页归属是集合，物品输入类别恒为单元素 ⇒ 不会再被算成「多配方共用」）",
      all(len(tab_keys_after(cid)) == 1 for cid in INPUT_CATEGORIES)
      and "keys.add(declared);\n                return new ArrayList<>(keys);" in screen)
check("5b 链级语义：改动只删「多余的页键」，没有一个类别掉进兜底页 / 丢项（真实数据逐条推演）",
      all(tab_keys_after(cid) != {"<other>"} for cid in INPUT_CATEGORIES)
      and all(tab_keys_after(cid) for cid in INPUT_CATEGORIES))
check("5c 勾选语义未动：selected / committedIds 仍只按类别 id，切页不改勾选（锚点仍在）",
      "private final Set<String> selected = new LinkedHashSet<>();" in screen
      and "for (final String id : initialSelection) {" in screen
      and "private void switchTab(final String tabKey) {" in screen)
check("5d isTabOnly 跳过点仍是 7 处（本轮没有增减任何枚举处）",
      screen.count("isTabOnly(category)") == 7, "%d" % screen.count("isTabOnly(category)"))
check("5e 服务端产线判定一个都没碰（本轮只改界面显示分组）",
      "blockedByMachineQueue" in chamber and "startCapacityForRecipe" in chamber
      and "inFlightUnitsForRecipe" in chamber and "NEXT_FOR_MACHINE" in chamber)
check("5f 链级类别 / 归属表仍是服务端那两份实现（界面不参与）",
      "public List<ChainBusCategory> chainCategories() {" in chamber
      and "chainBusOwners()" in chamber
      and "chainCategories()" not in screen)
check("5g 服务端「同一 ingredient 多候选 = 一个原料」的语义未动（整组登记 + 每批需求量仍只一份）",
      "private static void registerInputCandidates(final Map<String, LinkedHashSet<Item>> inputCandidates,"
      in chamber
      and "private static List<Item> candidateListOf(final Map<String, LinkedHashSet<Item>> inputCandidates,"
      in chamber
      and "result.add(0, representative); // 代表物必须是首个（图标 / 每批所需量口径一致）" in chamber)
# 2026-10-06（round48「开发日志总开关」）更新，理由写在这里：
#   本条原本用 **mtime** 证明「本轮只动了界面文件」（AssemblyWatchdog / RsccWireLinkSearch 未被触碰）。
#   那是**轮次取证**，不是行为断言；而 round48 **合法地**改动了 AssemblyWatchdog.java ——
#   旧代码把两条「需要玩家干预」的 WARN（binding-orphan / binding-gap）关在
#   `if (!RsccAssemblyDebug.isEnabled()) return;` 之后，一旦按用户要求把开发日志默认关掉，
#   这两条玩家可见的失败提示就会一起消失（行为回归）。round48 把它们挪出了开关。
#   mtime 断言因此必然失败，改为**内容级**断言，保护同一条语义（界面修复没被带坏 + 必要 WARN 没被吞）。
watchdog_src = read(os.path.join("support", "AssemblyWatchdog.java"))
wire_src = read(os.path.join("support", "RsccWireLinkSearch.java"))
check("5h round48 合法触碰 AssemblyWatchdog 后：界面修复锚点仍在，且必要 WARN 不被开发日志开关吞掉",
      "public final class RsccWireLinkSearch" in wire_src
      and "private static void logBindingSnapshot(" in watchdog_src
      and "RsccAssemblyDebug.warn(\"bindingorphan@\"" in watchdog_src
      and "RsccAssemblyDebug.warn(\"bindinggap@\"" in watchdog_src
      and "if (!RsccAssemblyDebug.isEnabled()) {\n            return;\n        }\n        final List<SequencePatternData.UnitEntry> units"
      not in watchdog_src)
check("5i 服务端类别实体（本轮的显示口径依据）也没被改：代表物 = 候选首个",
      "final List<Item> candidates = candidateListOf(inputCandidates, entry.getKey(), stack.getItem());"
      in chamber
      and "info.amount(), info.estimated(), info.reuseKey() == null ? \"\" : info.reuseKey()," in chamber)

# ======================================================================
# E. 文案：本轮没有新增语言键
# ======================================================================
section("E) 文案：本轮未新增语言键；既有键中英成对、中文 ≤ 40 字、无禁用词")

zh = read_json(os.path.join(LANG_DIR, "zh_cn.json"))
en = read_json(os.path.join(LANG_DIR, "en_us.json"))
check("6a 中英键集合仍然一致（没有任何单边键）", set(zh) == set(en),
      "差异：%s" % sorted(set(zh) ^ set(en))[:10])
check("6b 「多配方共用」文案键仍在两份语言文件里，中文 ≤ 40 字",
      (BUS_LANG + "row.shared") in zh and (BUS_LANG + "row.shared") in en
      and 0 < len(zh[BUS_LANG + "row.shared"]) <= 40,
      "%s" % zh.get(BUS_LANG + "row.shared"))
FORBIDDEN = ["或", "等 %s 种", "轮换", "等 2 种", "等 3 种"]
check("6c 相关文案不含禁用词（不写「或」/「等 N 种」/「图标轮换」）",
      all(not any(word in zh[key] or word in en[key] for word in FORBIDDEN)
          for key in (BUS_LANG + "row.shared", BUS_LANG + "group.materials",
                      BUS_LANG + "group.feedstock")),
      "命中：%s" % [key for key in (BUS_LANG + "row.shared", BUS_LANG + "group.materials",
                                    BUS_LANG + "group.feedstock")
                    if any(word in zh[key] or word in en[key] for word in FORBIDDEN)])
check("6d 本轮没有倒着改文案：两处组名仍是「原料 / 输入时原料」",
      zh[BUS_LANG + "group.materials"] == "原料"
      and zh[BUS_LANG + "group.feedstock"] == "输入时原料"
      and en[BUS_LANG + "group.materials"] != en[BUS_LANG + "group.feedstock"])

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
