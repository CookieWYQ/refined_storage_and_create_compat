# -*- coding: utf-8 -*-
"""第 28 轮：总线「类别详细配置」把「原料」与「输入时原料」按<b>配方上下文</b>分开。

用户原话（反复提过多次）：
  「输入输出总线那个详细配置界面，原料和输入时原料你把他们两个混在一起、没有区分的问题你还没有解决。」

本自检回答四件事（全部只读：源码锚点 + Create 真实配方 JSON + 纯推演，不跑游戏）：

  ① **源码锚点**：分组判据是否已经「按配方」（`startingItemsByRecipe` / `stepInputItemsByRecipe`
     + `inputSectionOf` / `recipeContextOf`），而不是只用全局物品集合；
  ② **类别 id 解析**：`input:<配方id>#<物品>` 的物品段 / 配方段是否正确拆开 ——
     改动前把整段当物品名（`create:track#minecraft:iron_nugget`），它不是合法 ResourceLocation，
     界面拿到 `null` ⇒ <b>每个物品输入都退化成「原料」，「输入时原料」永远为空</b>；
  ③ **真实数据推演**：从 `local_src` 的 Create 配方 JSON + 标签文件算出每条配方的
     起步原料 / 各步投入物，逐条推演它落在哪一组（列车轨道 / 精密构件 / 坚固板）；
  ④ **边界口径**：「同一物品同属两类」怎么办（跨配方 = 各归各的页；同配方 = 起步原料优先）。

用法：python tools/selfcheck_round28_bus_group_split.py
退出码：0 = 没有任何 FAIL。
"""
from __future__ import annotations

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
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LOCAL = os.path.join(ROOT, "local_src")
CREATE_DATA = os.path.join(LOCAL, "create_src", "data")
BUS = "gui.rs_create_compat.bus_config."
OTHER_TAB = "\u0000other"  # 与 Java 的 TAB_OTHER 同值（兜底页哨兵）

FAILURES = []
CHECKS = [0]


def read(*parts):
    with io.open(os.path.join(*parts), encoding="utf-8") as handle:
        return handle.read()


def read_json(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 92)
    print(title)
    print("=" * 92)


# ======================================================================
# 0. 真实数据：local_src 里的 create:sequenced_assembly 配方 + 物品标签
# ======================================================================
TAG_FILES = {}
_MARKER = os.sep + "tags" + os.sep + "item" + os.sep
for _base, _dirs, _files in os.walk(LOCAL):
    for _name in _files:
        if not _name.endswith(".json"):
            continue
        _full = os.path.join(_base, _name)
        if _MARKER not in _full or (os.sep + "data" + os.sep) not in _full:
            continue
        _ns = _full.split(os.sep + "data" + os.sep, 1)[1].split(os.sep)[0]
        _rel = _full.split(_MARKER, 1)[1].replace("\\", "/")[:-5]
        TAG_FILES.setdefault("%s:%s" % (_ns, _rel), []).append(_full)

_TAG_CACHE = {}


def resolve_tag(tag, depth=0):
    tag = tag[1:] if tag.startswith("#") else tag
    if tag in _TAG_CACHE:
        return _TAG_CACHE[tag]
    if depth > 6:
        return set()
    out = set()
    for path in TAG_FILES.get(tag, []):
        try:
            data = read_json(path)
        except Exception:
            continue
        for value in data.get("values", []):
            if isinstance(value, str) and value.startswith("#"):
                out |= resolve_tag(value, depth + 1)
            elif isinstance(value, str):
                out.add(value)
            elif isinstance(value, dict) and value.get("id"):
                ident = value["id"]
                out |= resolve_tag(ident, depth + 1) if ident.startswith("#") else {ident}
    _TAG_CACHE[tag] = out
    return out


def resolve_ingredient(node):
    """ingredient 节点（item / tag / 多值列表）→ 物品 id 集合（标签展开成全部候选）。"""
    out = set()
    if isinstance(node, list):
        for one in node:
            out |= resolve_ingredient(one)
        return out
    if not isinstance(node, dict):
        return out
    if "item" in node:
        out.add(node["item"])
    elif "tag" in node:
        out |= resolve_tag(node["tag"])
    return out


RECIPES = {}
_recipe_dir = os.path.join(CREATE_DATA, "create", "recipe", "sequenced_assembly")
if os.path.isdir(_recipe_dir):
    for _name in sorted(os.listdir(_recipe_dir)):
        if _name.endswith(".json"):
            RECIPES["create:" + _name[:-5]] = read_json(os.path.join(_recipe_dir, _name))

START_BY = {}
STEP_BY = {}
for _rid, _data in sorted(RECIPES.items()):
    START_BY[_rid] = resolve_ingredient(_data["ingredient"])
    _steps = set()
    for _step in _data.get("sequence", []):
        for _ing in _step.get("ingredients", [])[1:]:
            _steps |= resolve_ingredient(_ing)
    STEP_BY[_rid] = _steps
ALL_START = set().union(*START_BY.values()) if START_BY else set()
ALL_STEP = set().union(*STEP_BY.values()) if STEP_BY else set()

# ======================================================================
# ① 源码锚点：分组按配方上下文
# ======================================================================
section("① 源码锚点：分组判据按配方（BusCategoryConfigScreen / RsccBusCategory）")

screen = read(SRC, "client", "screen", "BusCategoryConfigScreen.java")
category = read(SRC, "support", "RsccBusCategory.java")

check("①分组入口 sectionOf 走 inputSectionOf（不再在 sectionOf 里直接查全局集合）",
      "return inputSectionOf(category, inputItemOf(category));" in screen)
check("①两张按配方的判据表存在，且在 collectAssemblyInputs 里被填充",
      "private final java.util.Map<String, Set<Item>> startingItemsByRecipe" in screen
      and "private final java.util.Map<String, Set<Item>> stepInputItemsByRecipe" in screen
      and re.search(r"startingItemsByRecipe\s*\.computeIfAbsent", screen) is not None
      and re.search(r"stepInputItemsByRecipe\s*\.computeIfAbsent", screen) is not None)
check("①「输入时原料」按配方收「下标 ≥1 的全部候选」（整组，避免锌粒这类候选掉回原料）",
      "for (final Item item : SequencedRecipeProbe.stepApplicationCandidates(step.getRecipe())) {" in screen
      and re.search(r"stepInputItemsByRecipe\s*\.computeIfAbsent", screen) is not None)

_recipe_branch = screen.find("final Set<Item> starts = startingItemsByRecipe.get(recipe);")
_legacy_branch = screen.find("return !startingItems.contains(item) && stepInputItems.contains(item)")
check("①按配方判据在前、全局集合只作「判不出配方」的兜底（顺序不能反）",
      0 <= _recipe_branch < _legacy_branch, "%d < %d" % (_recipe_branch, _legacy_branch))
check("①配方上下文来源：类别 id 自带配方段优先，旧格式退回当前标签页",
      "private String recipeContextOf(final RsccBusCategory category) {" in screen
      and "category.inputRecipeId()" in screen
      and 'return TAB_OTHER.equals(activeTab) ? "" : activeTab;' in screen)

check("①RsccBusCategory.inputItemId() 剥掉配方段；inputRecipeId() 单独给出配方段",
      "public String inputItemId() {" in category and "public String inputRecipeId() {" in category
      and "return hash < 0 ? body : body.substring(hash + 1);" in category
      and 'return hash <= 0 ? "" : body.substring(0, hash);' in category)

check("①分类计数 / 命中不变：一个类别恒定落一节（sectionMembers 仍是单值过滤）",
      "private List<String> sectionMembers(final String section) {" in screen
      and "if (inActiveTab(category) && section.equals(sectionOf(category))) {" in screen)
check("①两个组名各有 tooltip 定义行（表头 hover 时给出「谁是谁」）",
      'LANG + "group.materials.tip"' in screen and 'LANG + "group.feedstock.tip"' in screen)

_zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
_en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
check("①语言键中英成对、中文 ≤ 40 字，且两个组名在英文里也分得开",
      all(k in _zh and k in _en for k in (BUS + "group.materials.tip", BUS + "group.feedstock.tip"))
      and len(_zh[BUS + "group.materials.tip"]) <= 40 and len(_zh[BUS + "group.feedstock.tip"]) <= 40
      and _zh[BUS + "group.materials"] == "原料" and _zh[BUS + "group.feedstock"] == "输入时原料"
      and _en[BUS + "group.materials"] != _en[BUS + "group.feedstock"],
      "%s | %s" % (_en[BUS + "group.materials"], _en[BUS + "group.feedstock"]))

# ======================================================================
# ② 类别 id 解析：改动前恒解析失败 ⇒ 「输入时原料」永远为空
# ======================================================================
section("② 类别 id input:<配方id>#<物品>：物品段 / 配方段解析")

RL = re.compile(r"^[a-z0-9_.-]+:[a-z0-9/._-]+$")  # 与 ResourceLocation 的字符规则同口径


def input_body(cid):
    return cid[len("input:"):] if cid.startswith("input:") else ""


def item_id_of(cid):
    """镜像 RsccBusCategory#inputItemId（已修：剥掉配方段）。"""
    body = input_body(cid)
    return body if body.find("#") < 0 else body[body.find("#") + 1:]


def recipe_id_of(cid):
    """镜像 RsccBusCategory#inputRecipeId（旧格式 id 返回空串）。"""
    body = input_body(cid)
    hash_index = body.find("#")
    return "" if hash_index <= 0 else body[:hash_index]


def legacy_item_id_of(cid):
    """改动前的实现：前缀之后整段当物品名。"""
    return input_body(cid)


REAL_IDS = [
    "input:create:track#minecraft:stone_slab",
    "input:create:track#minecraft:iron_nugget",
    "input:create:track#create:zinc_nugget",
    "input:create:precision_mechanism#create:golden_sheet",
    "input:create:precision_mechanism#create:cogwheel",
    "input:create:sturdy_sheet#create:powdered_obsidian",
]
check("②真实类别 id 的物品段 = 合法注册名（改动前整段带回配方，`#` 不是合法字符 ⇒ 解析必然失败）",
      all(RL.match(item_id_of(cid)) for cid in REAL_IDS)
      and all(not RL.match(legacy_item_id_of(cid)) for cid in REAL_IDS),
      "旧解析示例：%s" % legacy_item_id_of(REAL_IDS[1]))
check("②配方段正确还原（= 服务端登记该类别时用的配方）",
      recipe_id_of("input:create:track#minecraft:iron_nugget") == "create:track"
      and recipe_id_of("input:create:precision_mechanism#create:golden_sheet")
      == "create:precision_mechanism"
      and recipe_id_of("input:minecraft:iron_nugget") == "",
      "旧格式 id 无配方段 ⇒ 空串（退回当前标签页）")

# ======================================================================
# ③ 真实数据推演：每组各是谁
# ======================================================================
section("③ 真实配方推演：原料 = 起步原料；输入时原料 = 各步投入物（下标 ≥1）")

check("③三条 Create 配方都读到了（local_src 真实数据）",
      {"create:track", "create:precision_mechanism", "create:sturdy_sheet"} <= set(RECIPES),
      str(sorted(RECIPES)))
check("③列车轨道：起步原料 = 台阶标签的三个候选；投入物 = 铁粒 / 锌粒",
      START_BY.get("create:track") == {"minecraft:stone_slab", "minecraft:smooth_stone_slab",
                                       "minecraft:andesite_slab"}
      and STEP_BY.get("create:track") == {"minecraft:iron_nugget", "create:zinc_nugget"},
      "%s | %s" % (sorted(START_BY.get("create:track", [])), sorted(STEP_BY.get("create:track", []))))
check("③精密构件：起步原料 = 金板；投入物 = 齿轮 / 大齿轮 / 铁粒",
      START_BY.get("create:precision_mechanism") == {"create:golden_sheet"}
      and STEP_BY.get("create:precision_mechanism")
      == {"create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"},
      "%s | %s" % (sorted(START_BY.get("create:precision_mechanism", [])),
                   sorted(STEP_BY.get("create:precision_mechanism", []))))


def section_of(cid, active_tab=OTHER_TAB):
    """镜像 BusCategoryConfigScreen#sectionOf + inputSectionOf（含兜底分支）。"""
    if cid.startswith("fluid:"):
        return "fluids"
    if cid == "intermediate" or cid.startswith("intermediate:"):
        return "intermediates"
    if cid.startswith("result:"):
        return "products"
    if cid.startswith("scrap:"):
        return "scrap"
    item = item_id_of(cid)
    if not item:
        return "materials"
    recipe = recipe_id_of(cid) or ("" if active_tab == OTHER_TAB else active_tab)
    if recipe:
        starts = START_BY.get(recipe)
        if starts:
            return "materials" if item in starts else "feedstock"
    return "feedstock" if (item not in ALL_START and item in ALL_STEP) else "materials"


def section_of_before_id_change(cid, active_tab=OTHER_TAB):
    """改动前的实现（整段当物品名 + 全局集合）：物品恒解析不出 ⇒ 一律「原料」。"""
    item = legacy_item_id_of(cid)
    known = item if RL.match(item) else None
    if known is None:
        return "materials"
    recipe = recipe_id_of(cid) or ("" if active_tab == OTHER_TAB else active_tab)
    if recipe:
        starts = START_BY.get(recipe)
        if starts:
            return "materials" if known in starts else "feedstock"
    return "feedstock" if (known not in ALL_START and known in ALL_STEP) else "materials"


def section_of_global_sets(cid):
    """只把「全局集合」这一条旧口径单独拿出来（物品段已正确解析），看它会怎么判。"""
    item = item_id_of(cid)
    return "feedstock" if (item not in ALL_START and item in ALL_STEP) else "materials"


TABLE = [
    ("input:create:track#minecraft:stone_slab", "materials", "列车轨道的起步原料（台阶）"),
    ("input:create:track#minecraft:iron_nugget", "feedstock", "列车轨道装铁粒步的投入物"),
    ("input:create:track#create:zinc_nugget", "feedstock", "同一步的另一个候选（锌粒）"),
    ("input:create:precision_mechanism#create:golden_sheet", "materials", "精密构件的起步原料（金板）"),
    ("input:create:precision_mechanism#create:cogwheel", "feedstock", "精密构件第 1 步的投入物"),
    ("input:create:precision_mechanism#minecraft:iron_nugget", "feedstock",
     "精密构件第 3 步的投入物（与列车轨道同名、不同类别）"),
]
wrong = [(cid, section_of(cid), want) for cid, want, _why in TABLE if section_of(cid) != want]
check("③两条配方的输入各归各的组（不再一栏全塞「原料」）", not wrong, str(wrong))
print("      分组推演：")
for cid, want, why in TABLE:
    print("        %-56s -> %-10s %s" % (cid, section_of(cid), why))

track_materials = [cid for cid in REAL_IDS
                   if recipe_id_of(cid) == "create:track" and section_of(cid) == "materials"]
track_feedstock = [cid for cid in REAL_IDS
                   if recipe_id_of(cid) == "create:track" and section_of(cid) == "feedstock"]
check("③列车轨道页两组都非空（「输入时原料」不再永远为空 —— 用户看到的就是「混在一起」）",
      bool(track_materials) and bool(track_feedstock),
      "原料 %d 项 / 输入时原料 %d 项" % (len(track_materials), len(track_feedstock)))
check("③反例（改动前）：整段当物品名 ⇒ 物品解析不出 ⇒ 全部落「原料」，两组必然混在一起",
      all(section_of_before_id_change(cid) == "materials" for cid in (
          "input:create:track#minecraft:stone_slab",
          "input:create:track#minecraft:iron_nugget",
          "input:create:track#create:zinc_nugget")),
      str([section_of_before_id_change(cid) for cid in REAL_IDS]))
check("③坚固板页：起步原料 = 黑曜石粉（标签候选），物品投入物为空（只有岩浆流体 ⇒ 落流体组）",
      START_BY.get("create:sturdy_sheet") >= {"create:powdered_obsidian"}
      and STEP_BY.get("create:sturdy_sheet") == set()
      and section_of("input:create:sturdy_sheet#create:powdered_obsidian") == "materials",
      "%s | %s" % (sorted(START_BY.get("create:sturdy_sheet", [])),
                   sorted(STEP_BY.get("create:sturdy_sheet", []))))

# ======================================================================
# ④ 边界口径：同一物品同属两类
# ======================================================================
section("④ 同一物品同属两类：跨配方各归各页；同配方「起步原料优先」")

# 跨配方：金板是精密构件的起步原料；假设另一条配方把它当步内投入物（设计文档预判的情形，
# 见 docs/SEQUENCE_CHAIN_REDESIGN.md 的「一件物品可能同时是 A 配方的起步原料、B 配方的投入物」）
FAKE = "create:demo_casing"
START_BY[FAKE] = {"minecraft:copper_ingot"}
STEP_BY[FAKE] = {"create:golden_sheet"}
cross_materials = section_of("input:create:precision_mechanism#create:golden_sheet")
cross_feedstock = section_of("input:%s#create:golden_sheet" % FAKE)
check("④跨配方：同一件金板在两个类别里各按自己的配方归组（起步原料 / 投入物）",
      cross_materials == "materials" and cross_feedstock == "feedstock",
      "%s | %s" % (cross_materials, cross_feedstock))
check("④反例（全局集合旧口径）：金板是「任意配方的起步原料」⇒ 两个类别都判成「原料」，差异可见",
      section_of_global_sets("input:create:precision_mechanism#create:golden_sheet") == "materials"
      and section_of_global_sets("input:%s#create:golden_sheet" % FAKE) == "materials",
      "旧口径：%s | %s" % (section_of_global_sets("input:create:precision_mechanism#create:golden_sheet"),
                          section_of_global_sets("input:%s#create:golden_sheet" % FAKE)))
del START_BY[FAKE]
del STEP_BY[FAKE]

# 同配方：起步原料又被本配方某一步投入 ⇒ 起步原料优先
START_BY["create:track"] = START_BY["create:track"] | {"minecraft:iron_nugget"}
STEP_BY["create:track"] = STEP_BY["create:track"] | {"minecraft:iron_nugget"}
dual = section_of("input:create:track#minecraft:iron_nugget")
check("④同配方双角色 ⇒ 落「原料」（起步原料优先；id 里没有角色段，服务端本就是一个勾选框）",
      dual == "materials", dual)
check("④该口径在源码注释里写明（不是隐式行为）",
      "起步原料优先" in screen and "服务端把「配方 + 物品」合成" in screen)
START_BY["create:track"] = START_BY["create:track"] - {"minecraft:iron_nugget"}
STEP_BY["create:track"] = STEP_BY["create:track"] - {"minecraft:iron_nugget"}
check("④口径说明引用真实数据：本包三条 Create 配方都没有同配方双角色（优先级不会隐藏任何条目）",
      not any(START_BY[rid] & STEP_BY[rid] for rid in RECIPES),
      str([rid for rid in RECIPES if START_BY[rid] & STEP_BY[rid]]))

# ======================================================================
# 结果
# ======================================================================
print()
print("=" * 92)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
