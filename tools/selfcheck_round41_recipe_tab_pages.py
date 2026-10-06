# -*- coding: utf-8 -*-
"""自检（第 41 轮 · 没有单元样板时：标签页 = 所有使用该处理器类型的序列装配配方，一配方一页）。

用法：python tools/selfcheck_round41_recipe_tab_pages.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户实测反馈（原话）
-------------------
「不行啊，问题更大了。然后就是输入总线，他那一个如果说没有配置的话呢，你的那个字太长了，超了，
  而且糊在一起了，看不清楚，重新改一下排版。然后就是，就比如说使用 —— 你应该列出来的不是
  「使用」这一栏，而是所有包含「使用」的那些配方，那序列装配的配方。你懂吗？你现在是显示的是错的。」

两件事（本文件各钉一组）
------------------------
A 标签页：整链没有单元样板时，标记类别携带的处理器类型（`create:deploying`）**不能**直接当页名 ——
  它在 Create 自己的语言里是 `create.recipe.deploying` = 「使用」（一个**动词**，页内还零行）。
  现在展开成「所有含该处理器类型步骤的序列装配配方」，**一配方一页**（页名 = 配方结果物名），
  按配方 id 去重，页内仍零行；整链任一成员有样板 ⇒ 一个字都不变（既有行为优先）。
B 底部提示：文案缩短 + **几何兜底**（两段各自按像素截断到半行宽以内、右段右对齐到列表右缘、
  两段之间留 8px 硬间隙）⇒ 不可能重叠、不可能越出面板。

本自检钉住七件事
----------------
K1 「所有使用该类型的配方」是从哪枚举的：就在客户端**既有那一遍**配方扫描
   （`collectAssemblyInputs` 遍历配方管理器里全部 `sequenced_assembly` 配方）里，
   用**与服务端同一个**解析器 `SequencedRecipeProbe#recipeTypeId(step.getRecipe())` 登记
   `recipeIdsByStepType` —— 不新造第二套解析、不用物品名 / 语言键做启发式；
K2 真值表：整链无样板 ⇒ 每个**使用**该处理器类型的配方各占一页（不使用该类型的配方没有页）；
K3 去重：同一配方里的多个同类步骤、同一条标记被多条总线 / 多台机器重复给出 ⇒ 仍只有一页；
K4 兜底：客户端一条配方都查不到（配方管理器未加载完）⇒ 退回处理器类型页（页内零行），
   绝不出现「一个标签页都没有」；
K5 标记类别仍不进任何判定：7 处 `isTabOnly` 跳过点原样、`selected` 恒 false、
   不进 `categoryOrder` / 勾选表 / 分节成员 / 搜索命中（分母 0、提交表为空）；
K6 整链任一成员有样板 ⇒ 服务端不补标记 ⇒ 标签页由真实类别反推（既有行为逐字不变）；
K7 底部提示几何：**任意**文案下两段都不重叠、右段不越出面板；中英四段文案在 236px 行宽里
   都放得下（不出现省略号）。
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

# 面板几何（与 BusCategoryConfigScreen 的常量逐字对应）
CLEAR_X = 8
LIST_X = 8
LIST_W = 236
HINT_GAP = 8
ROW_LEFT = CLEAR_X
ROW_RIGHT = LIST_X + LIST_W          # 244


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
# A. 与 Java 逐字同构的模型：标记类别 → 标签页键（一配方一页）
# ======================================================================

# 配方管理器（客户端与执行舱读的是同一份）：配方 id → 步骤的处理器类型（顺序 = 配方里的顺序）
RECIPES = {
    "create:track": ["create:deploying", "create:pressing", "create:deploying"],
    "create:precision_mechanism": ["create:deploying", "create:deploying", "create:deploying"],
    "create:sturdy_sheet": ["create:pressing"],
    "create:electron_tube": ["create:deploying"],
    "minecraft:stick": ["minecraft:crafting"],          # 不是序列装配配方，不该出现
}
# 配方 id → 页名（结果池首项的悬浮名；与 collectAssemblyInputs 的 recipeDisplayLabel 同源）
RECIPE_LABELS = {
    "create:track": "列车轨道",
    "create:precision_mechanism": "精密构件",
    "create:sturdy_sheet": "坚固板",
    "create:electron_tube": "电子管",
}


def recipe_ids_by_step_type(recipes):
    """collectAssemblyInputs 里那一段登记的复刻（LinkedHashSet ⇒ 有序 + 按配方 id 去重）。"""
    out = {}
    for recipe_id, steps in recipes.items():
        for step_type in steps:
            if not step_type:
                continue
            out.setdefault(step_type, [])
            if recipe_id not in out[step_type]:
                out[step_type].append(recipe_id)
    return out


def is_tab_only(category_id):
    """RsccBusCategory#isRecipeTab 的复刻。"""
    return category_id.startswith(RECIPE_TAB_PREFIX) and len(category_id) > len(RECIPE_TAB_PREFIX)


def recipe_tab_keys_of(segment, by_step_type, labels):
    """BusCategoryConfigScreen#recipeTabKeysOf 的复刻（三种取值）。"""
    if not segment:
        return []
    if segment in labels:
        return [segment]
    ids = by_step_type.get(segment)
    if ids:
        return list(ids)
    return [TAB_RECIPE_TYPE_PREFIX + segment]


def tab_keys_of(category_id, tabs, by_step_type, labels):
    """tabKeysOf 的复刻：标记类别展开成「一配方一页」，其余类别照旧用已有的 tabs。"""
    if is_tab_only(category_id):
        return recipe_tab_keys_of(category_id[len(RECIPE_TAB_PREFIX):], by_step_type, labels)
    return list(tabs or [TAB_OTHER])


def rebuild_tabs(categories, by_step_type, labels):
    """rebuildTabs 的复刻：按首次出现顺序收集，兜底页固定排最后。"""
    order = []
    needs_fallback = False
    for cid, tabs in categories:
        for key in tab_keys_of(cid, tabs, by_step_type, labels):
            if key == TAB_OTHER:
                needs_fallback = True
            elif key not in order:
                order.append(key)
    if needs_fallback:
        order.append(TAB_OTHER)
    return order


def tab_display_name(key, labels, by_step_type, recipe_type_by_tab):
    """tabDisplayName 的复刻：配方页 = 结果物悬浮名（recipeLabelById）。"""
    if key == TAB_OTHER:
        return "通用 / 其他"
    recipe_type = recipe_type_by_tab.get(key)
    if recipe_type is not None:
        return "类型:" + recipe_type
    if key in labels:
        return labels[key]
    return key.split(":", 1)[-1]


def append_recipe_tab_marker(result, chain_has_unit, recipe_type):
    """appendRecipeTabMarker 的复刻（服务端；本文件不改它）。"""
    out = list(result)
    if out or chain_has_unit or not recipe_type:
        return out
    out.append({"id": RECIPE_TAB_PREFIX + recipe_type, "selected": False})
    return out


def tab_members(categories, tab, by_step_type, labels):
    """tabMembers 的复刻：标记类别不算任何一页的内容。"""
    return [cid for cid, tabs in categories
            if not is_tab_only(cid) and tab in tab_keys_of(cid, tabs, by_step_type, labels)]


def category_order(categories):
    """构造器里 categoryOrder 的复刻：标记类别不算「一个类别」。"""
    return [cid for cid, _tabs in categories if not is_tab_only(cid)]


def committed_ids(categories, initial_selection, auto_mode):
    """committedIds 的复刻：标记永远不会进提交表。"""
    selected = set()
    if auto_mode:
        selected |= set(cid for cid, _tabs in categories if not is_tab_only(cid))
    selected |= set(initial_selection)
    order = category_order(categories)
    return [i for i in order if i in selected] + [i for i in selected if i not in order]


# ======================================================================
# B. 底部提示的几何模型（与 drawHint 逐字同构）
# ======================================================================

def char_width(ch):
    """MC 默认字体的量级：空格 4px、拉丁 6px、全角（中日韩）9px。"""
    if ch == " ":
        return 4
    return 9 if ord(ch) > 0x2E7F else 6


def width(text):
    return sum(char_width(ch) for ch in text)


def trim(text, max_width):
    """BusCategoryConfigScreen#trim 的复刻（按像素截断，尾部补省略号）。"""
    if not text or max_width <= 0:
        return ""
    if width(text) <= max_width:
        return text
    s = text
    while len(s) > 1 and width(s + "...") > max_width:
        s = s[:-1]
    return s + "..."


def draw_hint(head, tail):
    """drawHint 的复刻：返回 (左段文字, 左段 x, 右段文字, 右段 x)。"""
    left, right = ROW_LEFT, ROW_RIGHT
    tail_max = max(0, (right - left) // 2 - HINT_GAP)
    tail_text = trim(tail, tail_max)
    tail_x = right - width(tail_text)
    head_text = trim(head, max(0, tail_x - HINT_GAP - left))
    return head_text, left, tail_text, tail_x


def main():
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    screen = read(SRC, "client", "screen", "BusCategoryConfigScreen.java")
    bus_category = read(SRC, "support", "RsccBusCategory.java")
    zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
    L = "gui.rs_create_compat.bus_config."

    # ---------------- K1 「所有使用该类型的配方」从哪枚举 ----------------
    section("K1 枚举来源：客户端既有那一遍配方扫描 + 与服务端同一个步骤类型解析器（不新造第二套）")

    collect = body(screen, "private void collectAssemblyInputs(@Nullable final Level level) {",
                   "private static <K> void addRecipeId(")
    check("锚点① 枚举就在既有的 recipe manager 扫描里（遍历全部 sequenced_assembly 配方的那一个循环）",
          "level.getRecipeManager()" in collect
          and "getAllRecipesFor(com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())" in collect
          and "for (final com.simibubi.create.content.processing.sequenced.SequencedRecipe<?> step"
              in collect)
    check("锚点② 步骤类型用与服务端判定同一个解析器（不是物品名 / 语言键启发式）",
          "final String stepType = SequencedRecipeProbe.recipeTypeId(step.getRecipe());" in screen
          and "recipeType.equals(SequencedRecipeProbe.recipeTypeId(step.getRecipe())))" in chamber)
    check("锚点③ 登记进「处理器类型 → 配方 id」表，值是 LinkedHashSet（有序 + 按配方 id 去重）",
          "private final java.util.Map<String, java.util.LinkedHashSet<String>> recipeIdsByStepType ="
          in screen
          and ".computeIfAbsent(stepType, ignored -> new LinkedHashSet<>())" in screen
          and ".add(recipeId);" in screen)
    check("锚点④ 没有第二套「配方名」解析：页名仍走既有的 recipeLabelById（结果物悬浮名）",
          "private final java.util.Map<String, String> recipeLabelById = new java.util.LinkedHashMap<>();"
          in screen
          and "recipeLabelById.putIfAbsent(recipeId, recipeDisplayLabel(recipe, holder.id()));" in screen
          and "final String label = recipeLabelById.get(tabKey);" in screen
          and screen.count("private static String recipeDisplayLabel(") == 1)
    check("锚点⑤ 展开只有一处判据（recipeTabKeysOf），tabKeysOf 只调它一次",
          "private List<String> recipeTabKeysOf(final String segment) {" in screen
          and "keys.addAll(recipeTabKeysOf(category.recipeTabType()));" in screen
          and screen.count("recipeTabKeysOf(") == 2)   # 定义 1 处 + 调用 1 处

    # ---------------- K2 真值表：整链无样板 ⇒ 一配方一页 ----------------
    section("K2 真值表：整链没有单元样板 ⇒ 每个使用该处理器类型的配方各占一页（不使用它的没有页）")

    by_step_type = recipe_ids_by_step_type(RECIPES)
    marker_type = "create:deploying"
    snapshot = [(marker["id"], None) for marker in
                append_recipe_tab_marker([], False, marker_type)]
    order = rebuild_tabs(snapshot, by_step_type, RECIPE_LABELS)
    check("① 标记 id 仍是服务端那一种写法（recipe:<处理器类型>），本文件不改服务端",
          "final String id = RsccBusCategory.recipeTabId(getRecipeType());" in chamber
          and "private void appendRecipeTabMarker(final List<RsccBusCategory> result) {" in chamber)
    check("② 标签页 = 所有含该类型步骤的配方，一配方一页（顺序 = 配方管理器顺序，去重）",
          order == ["create:track", "create:precision_mechanism", "create:electron_tube"],
          "%s" % (order,))
    check("③ 不使用该处理器类型的配方**没有**页（坚固板只有冲压步 ⇒ 不出现）",
          "create:sturdy_sheet" not in order)
    check("④ 页名 = 配方结果物悬浮名（既有解析），**不再**是 Create 那套「配方类型」动词名",
          [tab_display_name(k, RECIPE_LABELS, by_step_type, {}) for k in order]
          == ["列车轨道", "精密构件", "电子管"])
    check("⑤ 页内仍零行：标记不算任何一页的成员 ⇒ 六个分节全空、分母 0、提交表为空",
          all(tab_members(snapshot, k, by_step_type, RECIPE_LABELS) == [] for k in order)
          and category_order(snapshot) == []
          and committed_ids(snapshot, [], False) == []
          and committed_ids(snapshot, [], True) == [])
    check("⑥ 反例（用户否定的行为）：直接把处理器类型当页名 ⇒ 只会得到那个动词页「使用」",
          tab_display_name(TAB_RECIPE_TYPE_PREFIX + marker_type, RECIPE_LABELS, by_step_type,
                           {TAB_RECIPE_TYPE_PREFIX + marker_type: marker_type})
          == "类型:" + marker_type)

    # ---------------- K3 去重 ----------------
    section("K3 去重：同一配方的多个同类步骤 / 重复的标记类别 ⇒ 仍只有一页")

    check("⑦ 列车轨道有两个 deploying 步 ⇒ 只登记一个配方 id",
          by_step_type["create:deploying"].count("create:track") == 1)
    dup_snapshot = [(RECIPE_TAB_PREFIX + marker_type, None), (RECIPE_TAB_PREFIX + marker_type, None)]
    check("⑧ 同一条标记被重复给出（多台机器 / 多个成员）⇒ tabOrder 去重，页数不变",
          rebuild_tabs(dup_snapshot, by_step_type, RECIPE_LABELS) == order)
    mixed = [(RECIPE_TAB_PREFIX + "create:pressing", None)] + dup_snapshot
    check("⑨ 两条不同类型的标记并存 ⇒ 并集去重（列车轨道不会被两个类型各建一页）",
          rebuild_tabs(mixed, by_step_type, RECIPE_LABELS)
          == ["create:track", "create:sturdy_sheet", "create:precision_mechanism", "create:electron_tube"],
          "%s" % (rebuild_tabs(mixed, by_step_type, RECIPE_LABELS),))

    # ---------------- K4 兜底 ----------------
    section("K4 兜底：客户端一条配方都查不到（配方管理器未加载完）⇒ 退回处理器类型页，绝不零标签页")

    fallback = recipe_tab_keys_of(marker_type, {}, RECIPE_LABELS)
    check("⑩ 查不到配方 ⇒ 键 = 处理器类型页（\\u0000type: 前缀，与真配方 id 结构上不可能撞车）",
          fallback == [TAB_RECIPE_TYPE_PREFIX + marker_type], "%s" % (fallback,))
    check("⑪ 段本身就是配方 id 时直接当那一页（服务端日后换成 recipe:<配方id> 也不用改界面）",
          recipe_tab_keys_of("create:track", by_step_type, RECIPE_LABELS) == ["create:track"])
    check("⑫ Java 侧三种取值都在同一个小方法里（判据只有一处）",
          "if (recipeLabelById.containsKey(segment)) {" in screen
          and "return List.of(TAB_RECIPE_TYPE_PREFIX + segment);" in screen
          and "final java.util.Set<String> recipeIds = recipeIdsByStepType.get(segment);" in screen)

    # ---------------- K5 标记不进任何判定 ----------------
    section("K5 标记类别仍不进任何判定：7 处跳过点、不进勾选表 / 计数 / 分节 / 搜索命中")

    check("⑬ 客户端跳过点仍是 7 处（本轮没有增减：展开只发生在 tabKeysOf 内部）",
          screen.count("isTabOnly(category)") == 7)
    check("⑭ 服务端标记 selected 恒 false、不进类别表（只补在快照返回值里，判据是快照为空 + 链级无样板）",
          "if (!result.isEmpty() || chainHasAnyUnit()) {" in chamber
          and 'result.add(new RsccBusCategory(id, "", "", "", "", false, 1, 0L, 0L, "", ""));' in chamber
          and "recipeTab" not in strip_comments(body(
              chamber, "private List<BusCategoryInfo> computeBusCategories() {",
              "private static void recordStepMachine("))
          and "recipeTab" not in strip_comments(body(
              chamber, "public List<ChainBusCategory> chainCategories() {")))
    check("⑮ 不新增协议字段：类别快照的编解码字段数不变",
          bus_category.count("ByteBufCodecs.STRING_UTF8.encode") == 7
          and "public static final String RECIPE_TAB_PREFIX = \"recipe:\";" in bus_category)

    # ---------------- K6 有样板 ⇒ 既有行为 ----------------
    section("K6 整链任一成员有样板 ⇒ 服务端不补标记 ⇒ 标签页由真实类别反推（既有行为逐字不变）")

    real = [("intermediate:create:track:2", ["create:track"]),
            ("input:create:track#start", ["create:track"]),
            ("result:create:sturdy_sheet", ["create:sturdy_sheet"])]
    check("⑯ 有样板时不补标记（链级判据：本台空但别台有样板也不补）",
          append_recipe_tab_marker([], True, marker_type) == []
          and append_recipe_tab_marker(real, True, marker_type) == real
          and append_recipe_tab_marker(real, False, marker_type) == real)
    check("⑰ 快照非空（有真实类别）⇒ 每个配方页照旧由真实类别反推，与第 41 轮之前逐字相同",
          rebuild_tabs(real, by_step_type, RECIPE_LABELS) == ["create:track", "create:sturdy_sheet"])
    check("⑱ 配方管理器还没扫到（recipeLabelById 为空）也不影响真实类别那条路径",
          rebuild_tabs(real, {}, {}) == ["create:track", "create:sturdy_sheet"])

    # ---------------- K7 底部提示几何 ----------------
    section("K7 底部提示：缩短后的中英文案放得下；任意文案下两段都不重叠、不越出面板")

    check("锚点⑥ drawHint 的排布就是「右段右对齐到列表右缘 + 两段硬间隙 + 各自按像素截断」",
          "final int right = px + LIST_X + LIST_W;" in screen
          and "final int tailMax = Math.max(0, (right - left) / 2 - HINT_GAP);" in screen
          and "final int tailX = right - font.width(tailText);" in screen
          and "final String headText = trim(head.getString(), Math.max(0, tailX - HINT_GAP - left));"
              in screen
          and "private static final int HINT_GAP = 8;" in screen)
    check("锚点⑦ 旧的「右段不截断、只按它的宽度夹左段」那一版已经不在（它才会糊在一起）",
          "tailX - (px + CLEAR_X) - 4" not in screen)

    cases = [
        ("自动模式（zh）", zh[L + "auto.locked"], zh[L + "mode.auto"]),
        ("手动模式（zh）", zh[L + "summary"] % (12, 40), zh[L + "mode.manual"]),
        ("自动模式（en）", en[L + "auto.locked"], en[L + "mode.auto"]),
        ("手动模式（en）", en[L + "summary"] % (12, 40), en[L + "mode.manual"]),
    ]
    fits = True
    detail = []
    for name, head, tail in cases:
        head_text, left, tail_text, tail_x = draw_hint(head, tail)
        ok = ("..." not in head_text and "..." not in tail_text
              and left + width(head_text) + HINT_GAP <= tail_x
              and tail_x + width(tail_text) <= ROW_RIGHT)
        fits = fits and ok
        detail.append("%s=%dpx+%dpx" % (name, width(head_text), width(tail_text)))
    check("⑲ 中英四段文案都**不用截断**就放得下（行宽 236，两段各 ≤ 半行宽 − 8）",
          fits, " ".join(detail))

    overlap_free = True
    for head_len in range(0, 60):
        for tail_len in range(0, 60):
            head = "自" * head_len
            tail = "A" * tail_len
            head_text, left, tail_text, tail_x = draw_hint(head, tail)
            head_right = left + width(head_text)
            if head_right > tail_x - HINT_GAP or tail_x < left or \
                    tail_x + width(tail_text) > ROW_RIGHT:
                overlap_free = False
    check("⑳ 穷举 60×60 组任意长度文案：左段右界 ≤ 右段左界 − 8、右段右界 = 列表右缘"
          "（不重叠 / 不越界由几何保证，不靠「文案够短」这一条假设）",
          overlap_free)

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
