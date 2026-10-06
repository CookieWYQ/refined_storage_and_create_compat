# -*- coding: utf-8 -*-
"""自检（第 39 轮 · 执行舱整条链没有单元样板 ⇒ 仍建配方标签页，但页内零行）。

用法：python tools/selfcheck_round39_recipe_tab_without_units.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户拍板（原话）
----------------
「**总线配置界面仍建配方标签页，但页内一条都不显示。**」

语义（本轮实现的口径）
----------------------
① 执行舱（链）**没有任何单元样板**时：类别表仍然为空（第 35 轮的结论不变，本文件不重做那份真值表），
   但界面**仍要显示配方标签页** —— 因为「这台机器能做这类加工」是**已知事实**
   （= 本仓绑定的 `recipeType`，如 create:pressing / create:deploying）；
② **页内一行都不显示** —— 没有单元样板就没有「具体配方 + 步序」，也就没有任何可交接的原料 /
   中间产物 / 成品；
③ 有单元样板时行为**逐字不变**（标签页照旧由真实类别反推）。

第 41 轮改口径（**标签页 = 一配方一页**，本文件 K2 的复刻已同步）
-----------------------------------------------------------------
用户实测反馈：「你应该列出来的不是「使用」这一栏，而是所有包含「使用」的那些配方，那序列装配的配方。」
即：标记类别携带的处理器类型（create:deploying）**不能**直接当页名 —— 它在 Create 自己的语言里
叫「使用」（`create.recipe.deploying`），是一个动词页，看不出有哪些配方。
现在的口径：标记类别由客户端展开成「所有含该处理器类型步骤的序列装配配方」，
**一个配方一个标签页**（页名 = 配方结果物名），去重、页内仍零行。
因此本文件的 K2 ② 由「键 = 处理器类型页」改成「键 = 每配方一页」；
`TAB_RECIPE_TYPE_PREFIX` 只在「客户端一条配方都查不到」时作为兜底页保留。
完整真值表（含去重、兜底、有样板时逐字不变）见 `tools/selfcheck_round41_recipe_tab_pages.py`。

本轮的落地方式（为什么这样最不容易出错）
----------------------------------------
类别表（`busCategories()` / `chainCategories()`）**一个字都没动** —— 它是归属 / 备料 / 排队 / 名额 /
在制计数的唯一事实源。改为在**两个界面快照的返回值**里补一条**只喂标签页的标记类别**
（`RsccBusCategory#RECIPE_TAB_PREFIX` + 处理器类型 id），客户端把它从所有「行 / 计数 / 命中」里排除，
只用它建页与起页名。

本自检钉住五件事
----------------
K1 标记**只**加在界面快照的返回值里（每个快照各一次），类别表 / 链级类别表 / 归属表 / 属主判定 /
   备料入口的代码里**一个字都没出现**它 ⇒ 不污染任何判定（结构证明）；
K2 真值表：整链无样板 ⇒ 快照 = 只有一条标记类别；页在（第 41 轮起展开成「一配方一页」）、
   页内零行、分节成员全空、底部「已选 N / M 项」分母 0、提交表为空；
K3 有样板 / 快照非空 / 处理器类型为空 三种情形**都不补** ⇒ 既有行为逐字不变；
K4 标记 id 的唯一来源仍是**本仓的处理器类型**（`getRecipeType()`，服务端**不猜具体配方**）；
    「哪些配方用到它」由客户端读自己那份配方管理器展开（第 41 轮，见 K2 ② 与本文件末的说明）；
K5 零行时的排版 / 滚动 / 命中 / 折叠自洽：没有空行占位、滚动上限 0、命中恒 -1、空页文案单独一句。
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
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILURES = []
CHECKS = [0]

RECIPE_TAB_PREFIX = "recipe:"
TAB_RECIPE_TYPE_PREFIX = "\u0000type:"
TAB_OTHER = "\u0000other"
GROUPS = ["materials", "feedstock", "fluids", "products", "scrap", "intermediates"]
VISIBLE_ROWS = 9


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source):
    """去掉 /* */ 与 // 注释：断言「代码里没有某样东西」时不能被注释里的字面量干扰。"""
    out = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith("/*", index):
            stop = source.find("*/", index + 2)
            index = length if stop < 0 else stop + 2
            continue
        if source.startswith("//", index):
            stop = source.find("\n", index)
            index = length if stop < 0 else stop
            continue
        out.append(source[index])
        index += 1
    return "".join(out)


def body(source, anchor, end=None):
    """取 anchor 之后的片段（到 end 或文件末尾）：用于「某方法体内」的断言。"""
    index = source.find(anchor)
    if index < 0:
        return ""
    index += len(anchor)
    rest = source[index:]
    if end is not None:
        stop = rest.find(end)
        if stop >= 0:
            return rest[:stop]
    return rest


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ======================================================================
# 与 Java 逐字同构的模型
# ======================================================================

def recipe_tab_id(recipe_type):
    """RsccBusCategory#recipeTabId 的复刻（类型为空 ⇒ 空串 = 不建页）。"""
    return (RECIPE_TAB_PREFIX + recipe_type) if recipe_type else ""


def append_recipe_tab_marker(result, chain_has_unit, recipe_type):
    """appendRecipeTabMarker 的复刻：只补在快照返回值里（就地追加语义）。"""
    out = list(result)
    if out or chain_has_unit:
        return out
    marker_id = recipe_tab_id(recipe_type)
    if not marker_id:
        return out
    # selected 恒 False / sharedCount 1 / 其余字段空：它不画任何行，字段都不会被读到
    out.append({"id": marker_id, "selected": False, "icon": "", "items": "", "shared": 1})
    return out


def is_tab_only(marker):
    """BusCategoryConfigScreen#isTabOnly 的复刻（= RsccBusCategory#isRecipeTab）。"""
    return marker["id"].startswith(RECIPE_TAB_PREFIX) and len(marker["id"]) > len(RECIPE_TAB_PREFIX)


# 配方管理器里「含该处理器类型步骤」的序列装配配方（第 41 轮起标记类别据此展开成配方页）
RECIPE_LABELS = {"create:track": "列车轨道", "create:sturdy_sheet": "坚固板",
                 "create:precision_mechanism": "精密构件"}
RECIPES_BY_STEP_TYPE = {"create:pressing": ["create:track", "create:sturdy_sheet",
                                            "create:precision_mechanism"]}


def recipe_tab_keys_of(segment):
    """recipeTabKeysOf 的复刻（第 41 轮口径）：段是配方 id ⇒ 就是那一页；
    段是处理器类型 ⇒ 所有含该类型步骤的配方（一配方一页、按配方 id 去重）；查不到 ⇒ 类型页兜底。"""
    if not segment:
        return []
    if segment in RECIPE_LABELS:
        return [segment]
    ids = RECIPES_BY_STEP_TYPE.get(segment)
    if ids:
        return list(ids)
    return [TAB_RECIPE_TYPE_PREFIX + segment]


def tab_keys_of(cat):
    """tabKeysOf 的复刻：标记类别展开成「所有使用该处理器类型的配方，一配方一页」。"""
    if is_tab_only(cat):
        return recipe_tab_keys_of(cat["id"][len(RECIPE_TAB_PREFIX):])
    return list(cat.get("tabs") or [TAB_OTHER])


def tab_members(categories, tab):
    """tabMembers 的复刻（标记类别不算这一页的内容）。"""
    return [c["id"] for c in categories if not is_tab_only(c) and tab in tab_keys_of(c)]


def section_members(categories, active_tab, group, section_of):
    """sectionMembers 的复刻（标记类别不进任何一节）。"""
    return [c["id"] for c in categories
            if not is_tab_only(c) and active_tab in tab_keys_of(c) and section_of(c) == group]


def category_order(categories):
    """构造器里 categoryOrder 的复刻（标记类别不算「一个类别」）。"""
    return [c["id"] for c in categories if not is_tab_only(c)]


def committed_ids(categories, initial_selection, auto_mode):
    """勾选集合 + committedIds() 的复刻（自动模式的接管集合同样跳过标记）。"""
    selected = set()
    if auto_mode:
        selected |= set(c["id"] for c in categories if c["selected"] and not is_tab_only(c))
    selected |= set(initial_selection)
    order = category_order(categories)
    return [i for i in order if i in selected] + [i for i in selected if i not in order]


def row_at(lines, first_visible_row, index):
    """rowAt 的复刻：index < lines.size() 才算命中（零行 ⇒ 永远 -1）。"""
    return index if 0 <= index < len(lines) else -1


def max_scroll(lines):
    """clampScroll / refreshScrollbar 的复刻：零行 ⇒ 上限 0（滚动条不可用）。"""
    return max(0, len(lines) - VISIBLE_ROWS)


def main():
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    screen = read(SRC, "client", "screen", "BusCategoryConfigScreen.java")
    bus_category = read(SRC, "support", "RsccBusCategory.java")

    # ---------------- K1 标记只加在界面快照的返回值里 ----------------
    section("K1 标记只加在界面快照的返回值里：类别表 / 归属表 / 属主判定 / 备料入口一个字都没出现它")

    check("锚点① 「补标记」在两个界面快照里各调用一次（输出快照 + 输入快照），别无第三处",
          chamber.count("appendRecipeTabMarker(result);") == 2)
    check("锚点② 补标记是一个独立的私有方法，判据只有一个（链级 chainHasAnyUnit + 快照为空）",
          "private void appendRecipeTabMarker(final List<RsccBusCategory> result) {" in chamber
          and "if (!result.isEmpty() || chainHasAnyUnit()) {" in chamber)
    check("锚点③ 归属表 / 链级类别表的枚举仍是 3 处（没有为标记多开一套链级枚举）",
          chamber.count("for (final ChainBusCategory entry : chainCategories()) {") == 3)

    compute_code = strip_comments(body(chamber, "private List<BusCategoryInfo> computeBusCategories() {",
                                      "private static void recordStepMachine("))
    chain_cats_code = strip_comments(body(chamber, "public List<ChainBusCategory> chainCategories() {",
                                          "public List<BusCategoryInfo> chainBusCategories() {"))
    owners_code = strip_comments(body(chamber, "private Map<String, List<BlockPos>> chainBusOwners() {",
                                      "private static void addBusOwner("))
    owned_code = strip_comments(body(chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {",
                                     "private Map<String, Set<Integer>> patternAssignedSteps(final Level level) {"))
    fill_code = strip_comments(body(chamber, "private void fillInternalForBus(",
                                    "private Set<Item> intermediateOnly("))
    check("锚点④s 四个「不许出现标记」的片段都取到了实代码（切片锚点没漂：切片为空会让下面的断言变成假通过）",
          len(compute_code) > 200 and len(chain_cats_code) > 100 and len(owners_code) > 100
          and len(owned_code) > 100 and len(fill_code) > 200,
          "%d/%d/%d/%d/%d" % (len(compute_code), len(chain_cats_code), len(owners_code),
                              len(owned_code), len(fill_code)))
    check("锚点④ **类别表**（computeBusCategories）里没有标记：它不是类别，不进缓存",
          "recipeTab" not in compute_code and "RECIPE_TAB" not in compute_code)
    check("锚点⑤ **链级类别表**（chainCategories）里没有标记 ⇒ 归属表 / 备料 / 排队 / 名额 / 在制计数"
          "的口径逐字不变；链级归属表（chainBusOwners）与属主判定（computeOwnedSteps）同样一个字没动",
          "recipeTab" not in chain_cats_code and "RECIPE_TAB" not in chain_cats_code
          and "recipeTab" not in owners_code and "RECIPE_TAB" not in owners_code
          and "recipeTab" not in owned_code and "RECIPE_TAB" not in owned_code)
    check("锚点⑥ **备料入口**（fillInternalForBus，按 busCategories() ∩ busCategoryOwners 生成目标量）"
          "里没有标记 ⇒ 页内零行 = 备料零目标，勾不到也就不可能备料",
          "recipeTab" not in fill_code and "RECIPE_TAB" not in fill_code)
    check("锚点⑦ selected 恒 false ⇒ 界面永远不会把它放进勾选集合（提交表里不可能出现它）",
          'result.add(new RsccBusCategory(id, "", "", "", "", false, 1, 0L, 0L, "", ""));' in chamber)
    check("锚点⑧ 附带的「不是它」也一样被钉住：属主仍按样板判定（链级闸门不参与属主）",
          "chainHasAnyUnit" not in strip_comments(body(
              chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {")))

    # ---------------- K2 真值表：整链无样板 ⇒ 页在、零行 ----------------
    section("K2 真值表：整链无样板 ⇒ 快照只有一条标记类别；页在、页内零行、计数为 0")

    RECIPE_TYPE = "create:pressing"
    empty_chain = {"A": False, "B": False, "C": False, "D": False}
    chain_has_unit = any(empty_chain.values())

    own_categories = []          # 类别表为空（第 35 轮的结论）
    snapshot = append_recipe_tab_marker(own_categories, chain_has_unit, RECIPE_TYPE)
    check("① 4 台成链、整条链一台样板都没有 ⇒ 快照里恰好一条**只喂标签页**的标记类别",
          len(snapshot) == 1 and snapshot[0]["id"] == "recipe:create:pressing"
          and is_tab_only(snapshot[0]), "%s" % (snapshot,))

    marker_tab = tab_keys_of(snapshot[0])
    check("② 标签页**在**，且第 41 轮起是「一配方一页」：键 = 所有含该处理器类型步骤的配方 id "
          "（不再是一个「处理器类型页」—— 那个页名在 Create 语言里是动词「使用」，用户已否定）",
          marker_tab == ["create:track", "create:sturdy_sheet", "create:precision_mechanism"],
          "%s" % (marker_tab,))
    check("③ 页内**零行**：页成员为空、六个分节的成员全空（不画表头、不留空行占位）",
          tab_members(snapshot, marker_tab[0]) == []
          and all(section_members(snapshot, marker_tab[0], g, lambda c: "materials") == []
                  for g in GROUPS))
    check("④ 计数自洽：标记不进 categoryOrder ⇒ 底部「已选 N / M 项」的分母是 0（与眼前零行一致）",
          category_order(snapshot) == [])
    check("⑤ 提交表为空：手动（无初选）/ 自动（标记 selected=false 不被接管）两种模式的提交结果都为空",
          committed_ids(snapshot, [], False) == []
          and committed_ids(snapshot, [], True) == [])
    check("⑥ 搜索也不会因为它给出假结果：跨页命中（tabHasItemMatch）跳过标记 ⇒ 没有物品可命中",
          [c["id"] for c in snapshot if not is_tab_only(c)] == [])

    # ---------------- K3 有样板 / 快照非空 / 类型为空 ⇒ 不补 ----------------
    section("K3 有样板 / 快照非空 / 处理器类型为空：三种情形都不补 ⇒ 既有行为逐字不变")

    real = [{"id": "intermediate:create:track:2", "tabs": ["create:track"], "selected": True}]
    check("⑦ 链上任意一台有样板（chainHasAnyUnit = true）⇒ 一个字都不补（既有行为逐字不变）",
          append_recipe_tab_marker(real, True, RECIPE_TYPE) == real)
    check("⑧ 判据是**链级**：本台没有样板、但链上别台有 ⇒ 同样不补"
          "（绝不按「当前这台空不空」给整条链凭空加页）",
          append_recipe_tab_marker([], True, RECIPE_TYPE) == [])
    check("⑨ 快照非空（有真实类别）⇒ 不补：标签页照旧由真实类别反推，不会多出一个点不动的空页",
          append_recipe_tab_marker(real, False, RECIPE_TYPE) == real)
    check("⑩ 连处理器类型都没绑定 ⇒ 不补：没有可读的页名，界面照旧「没有可选类别」",
          append_recipe_tab_marker([], False, "") == []
          and recipe_tab_id("") == "" and recipe_tab_id(None) == "")

    # ---------------- K4 显示的是「处理器类型」那一层 ----------------
    section("K4 标签页要显示的是**处理器类型**（recipeType）这一层；具体配方 id 拿不到就不猜")
    check("⑪ 标记 id 的唯一来源就是本仓的处理器类型（链委托 getRecipeType()），"
          "没有任何「按配方表反查 / 硬推某条配方」的第二来源",
          "final String id = RsccBusCategory.recipeTabId(getRecipeType());" in chamber
          and "public static String recipeTabId(" in bus_category
          and "public String getRecipeType() {" in chamber
          and "return chainHead().ownRecipeType();" in chamber)
    check("⑫ 页名走执行舱那套「配方类型」解析（同一台机器在两个界面上叫同一个名字；"
          "查不到译名时该解析自己可读化兜底，不会回退成 create:xxx 这种技术串）",
          "import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;" in screen
          and "return RecipeTypeNames.of(recipeType);" in screen
          and "RecipeTypeNames" in read(SRC, "client", "screen", "SequenceExecutionChamberScreen.java"))
    check("⑬ 不新增协议字段：类别快照的编解码字段数不变（标记复用既有记录，不改 STREAM_CODEC）",
          bus_category.count("ByteBufCodecs.STRING_UTF8.encode") == 7)

    # ---------------- K5 零行时的排版 / 滚动 / 命中 / 折叠自洽 ----------------
    section("K5 零行时：没有空行占位、滚动上限 0、命中恒 -1、空页文案单独一句、切页与折叠不炸")

    check("⑭ 命中判定按 lines.size() 夹住（零行 ⇒ 恒 -1，点击不可能落到「别的行」）",
          "return row >= 0 && row < VISIBLE_ROWS && index < lines.size() ? index : -1;" in screen
          and row_at([], 0, 0) == -1)
    check("⑮ 滚动以 lines.size() 为唯一上限（零行 ⇒ 上限 0、滚动条不可用），切页会把列表复位到第 0 行",
          "final int max = Math.max(0, lines.size() - VISIBLE_ROWS);" in screen
          and max_scroll([]) == 0
          and "firstVisibleRow = 0; // 新的一页：列表从头看" in screen)
    check("⑯ 空页文案与「搜索没命中」分开说：处理器类型页 + 搜索框为空 ⇒ 单独一句"
          "（交互文案只加一处，两个语言文件成对）",
          'private Component emptyListText() {' in screen
          and '? "empty_recipe_tab" : "empty"' in screen)
    zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
    key = "gui.rs_create_compat.bus_config.empty_recipe_tab"
    check("⑰ 新语言键中英成对、中文 ≤ 40 字，且不写「或」/ 不写「等 N 种」/ 不枚举种类数",
          key in zh and key in en and 0 < len(zh[key]) <= 40
          and "或" not in zh[key] and "等" not in zh[key]
          and "icon" not in en[key].lower(),
          "%s | %s" % (zh.get(key), en.get(key)))

    # 客户端排除点的清单（少一处就会多一行 / 多一个计数，因此逐处点名）
    check("⑱ 客户端的排除点**逐处**都在：构造器（自动接管集合 + categoryOrder）、tabKeysOf、"
          "tabMembers、sectionMembers、tabHasItemMatch 共 7 处（新增枚举处必须同样跳过）",
          screen.count("isTabOnly(category)") == 7
          and "public boolean isRecipeTab() {" in bus_category
          and "private static boolean isTabOnly(final RsccBusCategory category) {" in screen)
    check("⑲ 既有的「一个类别恒定落一节」锚点没被绕开：分节仍是单值过滤（标记在其前被跳过，"
          "sectionOf 的返回值仍必是六节之一）",
          "if (inActiveTab(category) && section.equals(sectionOf(category))) {" in screen
          and "private String sectionOf(final RsccBusCategory category) {" in screen)
    check("⑳ 切页 / 折叠 / 折叠按钮的状态判断全部以「本页各节成员」为准 ⇒ 零行页上它们都是空转、"
          "不会去动 selected（勾选态的唯一真相）",
          "private List<String> sectionMembers(final String section) {" in screen
          and "if (!sectionMembers(section).isEmpty() && !collapsed.contains(section)) {" in screen
          and "private void toggleCollapsed(final String section) {" in screen)

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
