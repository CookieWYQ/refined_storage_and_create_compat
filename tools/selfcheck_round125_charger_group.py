#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 125 轮自检：充电过滤界面「多颜色线缆合并显示 + 展开」（ROUND122_USER_FEEDBACK §3-4）。

背景（用户原话，2026-10-31 实机反馈）
-----------------------------------------------------------------------------
「有线模组比如说精致存储里面它会有很多颜色嘛……这一些东西你就统一的合并显示。
 什么叫合并显示呢？就是有个展开，你就搞一个总的比如说什么控制器，然后展开之后呢，
 就不同的颜色把它分在一起放在一起，否则的话太难看了。」
—— 充电过滤界面的物品两页候选池是「所有带 FE 容器能力的物品」，RS 2.0.9 的
   **16 色普通控制器**都暴露 `Capabilities.EnergyStorage.ITEM`
   （证据：`neoforge/ModInitializer#registerCapabilities` 里
   `Items.INSTANCE.getControllers().forEach(item -> event.registerItem(EnergyStorage.ITEM, ...))`；
   创造控制器走的是另一张表 `addCreativeController`，没有注册 ITEM 能量能力 ⇒ 不在候选池），
   于是列表被「米色控制器 / 橙色控制器 / …」刷满。

本轮判据（三条腿，与第 120 / 121 / 124 轮同一套路）
-----------------------------------------------------------------------------
① **源码锚点**：分组模型只在 `ChargerFilterCandidates`
   （16 色词表 / 长词优先 / 注册名前缀归一化 / ≥3 才折叠 / 固定颜色序 / 家族展示名），
   界面只在 `ChargerFilterScreen` 里消费 `Family`（组头三态勾选框 / 展开折叠 / 批量全选 /
   搜索自动展开 + 只命中成员时高亮 / 成员缩进 6px），并且**四个页签互不影响**；
② **行为模型**：纯 Python 复刻「颜色词判定 → 家族归一化 → 折叠阈值 → 组内颜色序 →
   组头与普通条目混排 → 搜索过滤 / 高亮 → 批量全选与上限预检」，用 RS 2.0.9 的真实候选清单
   （16 + 16 个控制器 + 3 个无线物件）做端到端断言；
③ **反例自证**：把坏写法拼回源码副本 / 模型，断言上面的判据真的判负
   （`redstone` 不能因含 red 被误判、`light_blue` 不能被 `blue` 先吞、2 成员家族不折叠、
   4 个页签不受影响……）。

纪律：不跑 gradle / javac、不 git、不改存档格式 / 网络契约；只读源码 + 纯 Python 模型。
用法：python tools/selfcheck_round125_charger_group.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

CANDIDATES = os.path.join(JAVA, "client", "screen", "ChargerFilterCandidates.java")
SCREEN = os.path.join(JAVA, "client", "screen", "ChargerFilterScreen.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

CHECKS = [0]
COUNTER = [0]
FAILS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


# ======================================================================================
# 源码解析小工具（与第 120 / 121 / 124 轮同一套）
# ======================================================================================

def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性 / 语句顺序」判据。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


def body_of(code, hint):
    """从（已去注释的）源码里取一个方法 / 构造块：从 hint 起找到第一个 '{' 并配平。"""
    start = code.find(hint)
    if start < 0:
        return None
    brace = code.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return None


def int_const(code, name):
    match = re.search(r"\b%s\s*=\s*(\d+)\s*;" % re.escape(name), code)
    return None if match is None else int(match.group(1))


def flat(text):
    return re.sub(r"\s+", " ", text or "")


def quoted_words(text):
    return re.findall(r'"([^"]*)"', text)


# ======================================================================================
# ① 源码锚点（写成纯函数，反例自证时直接喂改坏的源码副本）
# ======================================================================================

#: 本轮唯一口径的 16 色顺序（原版 DyeColor 顺序，白→黑）。
COLORS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")


def color_order_table(candidates_raw):
    """从 `COLOR_ORDER = List.of(...)` 里取出 16 色，断言顺序 = 白→黑。"""
    code = strip_comments(candidates_raw)
    match = re.search(r"COLOR_ORDER\s*=\s*List\.of\((.*?)\);", code, flags=re.S)
    if match is None:
        return None
    return tuple(quoted_words(match.group(1)))


def color_order_ok(candidates_raw):
    return color_order_table(candidates_raw) == COLORS


def fold_threshold_ok(candidates_raw):
    """折叠阈值常量 == 3，且唯一（源码里只有这一处定义）。"""
    code = strip_comments(candidates_raw)
    return (int_const(code, "MIN_FAMILY_SIZE") == 3
            and code.count("MIN_FAMILY_SIZE = 3;") == 1)


def longest_first_ok(candidates_raw):
    """颜色词表按长度降序构造（长词先试），而不是直接拿 COLOR_ORDER 原序。"""
    code = strip_comments(candidates_raw)
    if "COLOR_PREFIXES_LONGEST_FIRST = longestFirstColorPrefixes();" not in code:
        return False
    body = body_of(code, "private static List<String> longestFirstColorPrefixes()")
    if body is None:
        return False
    packed = flat(body)
    return ("second.length() - first.length()" in packed
            and "first.compareTo(second)" in packed
            and "new ArrayList<>(COLOR_ORDER)" in packed)


def color_prefix_rule_ok(candidates_raw):
    """颜色判据 = 「颜色 + '_' 前缀」，不是 contains / 后缀。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "private static String colorOfId(")
    if body is None:
        return False
    packed = flat(body)
    return ("path.startsWith(color)" in packed
            and "path.charAt(color.length()) == '_'" in packed
            and "path.length() > prefixLength" in packed
            and "contains(color)" not in packed
            and "endsWith(color)" not in packed)


def family_key_rule_ok(candidates_raw):
    """家族键 = 去掉「颜色_」前缀后的基础注册名（ResourceLocation 重建，不拼字符串 id）。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "private static ResourceLocation computeFamilyKey(")
    if body is None:
        return False
    packed = flat(body)
    return ("id.getPath().substring(color.length() + 1)" in packed
            and "ResourceLocation.fromNamespaceAndPath(" in packed
            and "id.getNamespace()" in packed)


def grouping_api_ok(candidates_raw):
    """对外 API 四件套 + Family record 形状（界面只消费 Family）。"""
    code = strip_comments(candidates_raw)
    return (("public static String colorOf(final Item item)" in code or
             "public static String colorOf(final Item item) {" in code)
            and "public static ResourceLocation familyKey(final Item item)" in code
            and "public static Family familyOf(final Item item)" in code
            and "public static Component familyDisplayName(final Item item)" in code
            and "public record Family(ResourceLocation key, List<Item> members, ItemStack icon," in code
            and "Component displayName, String sortName)" in code
            and "members = List.copyOf(members);" in code)


def family_index_built_once_ok(candidates_raw):
    """家族索引在同一次 `all()` 构建里建好（FAMILY_KEYS / FAMILIES），界面重建行不做 O(N²)。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "public static List<Item> all()")
    if body is None:
        return False
    return ("FAMILY_KEYS.put(item, computeFamilyKey(id));" in body
            and "buildFamilies(found);" in body
            and code.count("buildFamilies(found);") == 1)


def fold_rule_ok(candidates_raw):
    """只有成员数 < MIN_FAMILY_SIZE 的家族才被排除在 FAMILIES 之外（2 成员不折叠）。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "private static void buildFamilies(")
    if body is None:
        return False
    packed = flat(body)
    return ("if (entry.getValue().size() < MIN_FAMILY_SIZE) {" in packed
            and "FAMILIES.put(entry.getKey(), createFamily(entry.getKey(), members));" in packed
            and "members.sort(Comparator" in packed)


def family_order_ok(candidates_raw):
    """组内排序 = 固定颜色序（colorOrderIndex），绝不按显示名字母序。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "private static void buildFamilies(")
    if body is None:
        return False
    packed = flat(body)
    return ("comparingInt((Item item) -> colorOrderIndex(colorOfId(IDS.get(item))))" in packed
            and "members.sort(Comparator" in packed
            and "toLowerCase(Locale.ROOT)" not in packed)


def family_display_name_ok(candidates_raw):
    """家族展示名 = 模组名（加载器显示名）+ 基础名 + 「（N 色）」，拿不到模组名就优雅降级。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "private static Family createFamily(")
    mod = body_of(code, "private static String modDisplayName(")
    if body is None or mod is None:
        return False
    return ('Component.translatable(LANG + "group.mod", modName, baseName);' in body
            and 'Component.translatable(LANG + "group.header", fullName,' in body
            and "return new Family(key, members, icon, displayName, sortName);" in body
            and "final String sortName = fullName.getString().toLowerCase(Locale.ROOT);" in body
            and "ModList.get()" in mod
            and "getModContainerById(namespace)" in mod
            and "if (modList == null) { return null; }" in flat(mod)
            and '"minecraft".equals(namespace)' in mod)


def fallback_base_name_ok(candidates_raw):
    """基础条目不在注册表时的兜底：显示名去掉颜色词（颜色词走原版 color.minecraft.*），不硬编码中文。"""
    code = strip_comments(candidates_raw)
    body = body_of(code, "private static Family createFamily(")
    strip_body = body_of(code, "private static String stripColorWord(")
    if body is None or strip_body is None:
        return False
    return ("final ItemStack baseStack = base == null ? ItemStack.EMPTY : new ItemStack(base);" in body
            and "baseName = baseStack.getHoverName();" in body
            and "stripColorWord(NAMES.getOrDefault(first, \"\")" in flat(body)
            and 'Component.translatable("color.minecraft." + color).getString()' in strip_body)


# ---------------- 界面锚点 ----------------

def row_model_ok(screen_raw):
    """Row 多了 group/member 两列；组头数据在独立的 GroupRow record 里（三态 / 高亮 / 可见成员）。"""
    code = strip_comments(screen_raw)
    return ("boolean manual, @Nullable GroupRow group, boolean member) {" in code
            and "private record GroupRow(ChargerFilterCandidates.Family family," in code
            and "int selected, int total, String label, boolean expanded," in code
            and "boolean highlighted) {" in code
            and "int checkState() {" in code
            and "return selected >= total ? CHECK_ON : CHECK_PARTIAL;" in code)


def rebuild_item_rows_ok(screen_raw):
    """物品页重建行：只有折叠家族进组；组头与普通条目同一比较器混排；成员只跟展开的组。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private void rebuildItemRows(")
    if body is None:
        return False
    packed = flat(body)
    return ("ChargerFilterCandidates.familyOf(item)" in packed
            and "top.sort(Comparator.comparing((Row row) -> topSortKey(row))" in packed
            and "if (row.group() != null && row.group().expanded()) {" in packed
            and "for (final Item member : row.group().visibleMembers()) {" in packed
            and "out.add(new Row(false, member, null, null, null, false, false, null, true));" in packed)


def search_expand_ok(screen_raw):
    """搜索非空 ⇒ 命中的组自动展开（默认展开，允许当场手动收起）。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private void rebuildItemRows(")
    if body is None:
        return False
    packed = flat(body)
    return ("final boolean expanded = searching ? !collapsedGroups.contains(family.key())" in packed
            and ": expandedGroups.contains(family.key());" in packed)


def search_highlight_ok(screen_raw):
    """只命中成员（组名本身没命中）⇒ 保留组头并高亮，而不是把组头藏掉。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private void rebuildItemRows(")
    match = body_of(code, "private static boolean familyMatchesQuery(")
    if body is None or match is None:
        return False
    packed = flat(body)
    return ("final boolean highlighted = searching && !familyMatchesQuery(family, query);" in packed
            and "PinyinSearch.contains(family.displayName().getString(), query)" in flat(match)
            and "family.key().toString().toLowerCase(Locale.ROOT)" in flat(match))


def expand_state_ok(screen_raw):
    """两套展开状态（非搜索手动展开 / 搜索手动收起）分别在哪个集合里，点击时按搜索态选择。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private Set<ResourceLocation> groupExpandState(")
    if body is None:
        return False
    packed = flat(body)
    return ("return searchQueryOrEmpty().isEmpty() ? expandedGroups : collapsedGroups;" in packed
            and "private final Set<ResourceLocation> expandedGroups = new LinkedHashSet<>();" in code
            and "private final Set<ResourceLocation> collapsedGroups = new LinkedHashSet<>();" in code
            and "collapsedGroups.clear();" in code)


def indent_ok(screen_raw):
    """成员行缩进常量 = 6，且整行（勾选框 / 图标 / 名字）都右移、名字宽度扣掉缩进。"""
    code = strip_comments(screen_raw)
    return (int_const(code, "GROUP_INDENT") == 6
            and "final int rowIndent = data.member() ? GROUP_INDENT : 0;" in code
            and "left + ROW_CHECK_X + rowIndent" in code
            and "left + ROW_ICON_X + rowIndent" in code
            and "left + ROW_NAME_X + rowIndent" in code
            and "final int rowNameMax = Math.max(8, nameMax - rowIndent);" in code)


def tri_state_ok(screen_raw):
    """三态勾选框：关/部分/开三个状态码，部分态画出绿横杠。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private static void drawTriStateCheckbox(")
    if body is None:
        return False
    packed = flat(body)
    return (int_const(code, "CHECK_OFF") == 0
            and int_const(code, "CHECK_PARTIAL") == 1
            and int_const(code, "CHECK_ON") == 2
            and "state == CHECK_ON ? COLOR_CHECK_ON : COLOR_CHECK_OFF" in packed
            and "if (state == CHECK_PARTIAL) {" in packed)


def group_row_draw_ok(screen_raw):
    """组头行画法：代表图标用缓存好的 Family icon + 名字走 RsccTextFit + 行尾已选/总数。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private void drawGroupRow(")
    if body is None:
        return False
    packed = flat(body)
    return ("guiGraphics.renderItem(group.family().icon(), left + ROW_ICON_X, y + 1);" in packed
            and "RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(group.label())," in packed
            and 'final String suffix = group.selected() + "/" + group.total();' in packed
            and "if (group.highlighted()) {" in packed
            and "COLOR_GROUP_HIT" in packed
            and "ChargerFilterCandidates.icon(" not in packed)


def click_dispatch_ok(screen_raw):
    """点组头：勾选框 = 批量全选/全不选；其它位置 = 展开/折叠（与绘制共用几何常量）。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "public boolean mouseClicked(")
    hit = body_of(code, "private boolean onGroupCheckbox(")
    if body is None or hit is None:
        return False
    packed = flat(body)
    return ("if (onGroupCheckbox(mouseX)) {" in packed
            and "toggleGroupSelection(row.group());" in packed
            and "toggleGroupExpanded(row.group().family());" in packed
            and "rowAt(mouseX, mouseY)" in packed
            and "localX >= ROW_CHECK_X" in flat(hit)
            and "localX < ROW_CHECK_X + ROW_CHECK_SIZE" in flat(hit))


def batch_select_ok(screen_raw):
    """批量全选：先预检上限（超了什么都不改并给提示），部分选中时点一次 = 全选。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private void toggleGroupSelection(")
    if body is None:
        return False
    packed = flat(body)
    precheck = packed.find("if (selection.size() + missing > ChargerFilterLimits.MAX_ITEM_ENTRIES) {")
    adding = packed.find("for (final ResourceLocation id : ids) { selection.add(id); }")
    return ("if (group.selected() >= group.total()) {" in packed
            and "selection.remove(id);" in packed
            and precheck >= 0
            and adding > precheck          # 预检在写入之前
            and "addStatus = fullStatus(ChargerFilterLimits.MAX_ITEM_ENTRIES);" in packed
            and "rebuildRows();" in packed
            and "sendFilter();" in packed
            # 取消勾选不受上限影响（先返回，不经过预检）
            and packed.find("return;") < precheck)


def suffix_group_ok(screen_raw):
    """组头行尾 = 「已选 / 家族总数」（与搜索无关，按整个家族算）。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private String suffixOf(final Row row)")
    if body is None:
        return False
    packed = flat(body)
    return ('return row.group().selected() + "/" + row.group().total();' in packed
            and "if (!row.player()) {" in body
            and 'return "";' in body)


def tooltip_group_ok(screen_raw):
    """组头 tooltip 有家族名 / 数量与已选 / 高亮原因 / 操作说明（本工程 GUI 不会自动渲染 tooltip）。"""
    code = strip_comments(screen_raw)
    body = body_of(code, "private void renderGroupTooltip(")
    row_tip = body_of(code, "private void renderRowTooltip(")
    if body is None or row_tip is None:
        return False
    packed = flat(body)
    return ('Component.translatable(LANG + "group.detail", group.total(), group.selected())' in packed
            and 'Component.translatable(LANG + "group.hit")' in packed
            and 'Component.translatable(LANG + "group.tip")' in packed
            and "if (row.group() != null) {" in row_tip
            and "renderGroupTooltip(guiGraphics, row.group(), mouseX, mouseY);" in row_tip)


def four_tabs_ok(screen_raw, candidates_raw):
    """四个页签互不影响：分组只发生在物品两页；TAB_COUNT 仍 = 4，Tab 仍是那 4 个。"""
    code = strip_comments(screen_raw)
    rebuild = body_of(code, "private void rebuildRows()")
    items = body_of(code, "private void rebuildItemRows(")
    if rebuild is None or items is None:
        return False
    tabs = re.findall(r"^\s*([A-Z]+)\(\"tab\.", code, flags=re.M)
    return (int_const(code, "TAB_COUNT") == 4
            and tabs == ["DROPPED", "CARRIED", "BLOCKS", "PLAYERS"]
            and "rebuildItemRows(next);" in rebuild
            and "Tab.BLOCKS" not in items
            and "Tab.PLAYERS" not in items
            # 方块页 / 玩家页的行模型里没有任何 group 概念
            and "group" not in body_of(code, "private boolean addBlockRow(")
            and "group" not in body_of(code, "private void rebuildBlockRows(")
            # 家族索引只有一个消费点（物品两页重建行）
            and code.count("ChargerFilterCandidates.familyOf(") == 1)


# ======================================================================================
# ② 行为模型（纯 Python 复刻源码判据）
# ======================================================================================

def longest_first(colors):
    return sorted(colors, key=lambda color: (-len(color), color))


LONGEST_FIRST = longest_first(COLORS)


def color_of_path(path, order=None):
    """复刻 `ChargerFilterCandidates#colorOfId`：颜色 + '_' 前缀，长词优先。"""
    order = LONGEST_FIRST if order is None else order
    for color in order:
        prefix = color + "_"
        if path.startswith(prefix) and len(path) > len(prefix):
            return color
    return None


def color_of_loose(path):
    """错误实现（反例用）：只做子串包含，且按字母序试 ⇒ light_blue 会被 blue 先吞。"""
    for color in sorted(COLORS):
        if color in path:
            return color
    return None


def family_key(ident):
    """复刻 `#familyKey` / `#computeFamilyKey`。"""
    namespace, path = ident.split(":", 1)
    color = color_of_path(path)
    if color is None:
        return namespace + ":" + path
    return namespace + ":" + path[len(color) + 1:]


def group_by_family(identifiers, min_size=3):
    """复刻 `#buildFamilies`：只返回成员数 ≥ min_size 的家族（键 → 成员）。"""
    buckets = {}
    order = []
    for ident in identifiers:
        key = family_key(ident)
        if key not in buckets:
            buckets[key] = []
            order.append(key)
        buckets[key].append(ident)
    return {key: buckets[key] for key in order if len(buckets[key]) >= min_size}


def color_index(ident):
    color = color_of_path(ident.split(":", 1)[1])
    return -1 if color is None else COLORS.index(color)


def order_family(members):
    """复刻组内排序：默认变体最前，其余严格白→黑，同色按注册 id。"""
    return sorted(members, key=lambda ident: (color_index(ident), ident))


def rs_candidates():
    """RS 2.0.9 真实候选（只列注册了 `Capabilities.EnergyStorage.ITEM` 的物品）。

    证据（`build/rs_src_full` 反编译源码）：
    * `neoforge/ModInitializer#registerCapabilities` 对 `Items.INSTANCE.getControllers()`
      逐个 `registerItem(EnergyStorage.ITEM, ...)`；
    * `common/AbstractModInitializer` 里 `Blocks.INSTANCE.getController().registerItems(callback,
      Items.INSTANCE::addController)` ⇒ 普通控制器 16 个颜色变体（默认色 LIGHT_BLUE 用基础 id）；
    * 创造控制器走的是**另一个**列表 `addCreativeController`（`getCreativeControllers()`），
      `ModInitializer` 没有给它注册 ITEM 能量能力 ⇒ **不**在候选池里（这一点与「16+16」的猜测不同，
      以源码为准）；
    * 另外三个单件：`wireless_grid` / `portable_grid` / `wireless_autocrafting_monitor`。
    """
    out = ["refinedstorage:controller"]
    out += ["refinedstorage:%s_controller" % color for color in COLORS if color != "light_blue"]
    out += ["refinedstorage:wireless_grid", "refinedstorage:portable_grid",
            "refinedstorage:wireless_autocrafting_monitor"]
    return out


def multi_candidates():
    """RS 真实候选 + 一个虚构模组的多颜色家族（3 成员折 / 2 成员不折），用于测混排与多组。"""
    return rs_candidates() + [
        "acme:red_pipe", "acme:blue_pipe", "acme:white_pipe",   # 3 成员 ⇒ 折叠
        "acme:red_wire", "acme:blue_wire",                       # 2 成员 ⇒ 不折叠
        "acme:flux_cell",                                        # 单件普通条目
    ]


COLOR_ZH = {
    "white": "白色", "orange": "橙色", "magenta": "品红色", "light_blue": "淡蓝色",
    "yellow": "黄色", "lime": "黄绿色", "pink": "粉红色", "gray": "灰色",
    "light_gray": "淡灰色", "cyan": "青色", "purple": "紫色", "blue": "蓝色",
    "brown": "棕色", "green": "绿色", "red": "红色", "black": "黑色",
}

BASE_ZH = {
    "controller": "控制器",
    "creative_controller": "创造控制器",
    "wireless_grid": "无线合成终端",
    "portable_grid": "便携式终端",
    "wireless_autocrafting_monitor": "无线合成监控器",
}


def base_name_of_key(key):
    path = key.split(":", 1)[1]
    return BASE_ZH.get(path, path)


def base_name(ident):
    """家族基础名（默认变体就是基础名；测试清单里没有缺基础条目的家族）。"""
    return base_name_of_key(family_key(ident))


def display_name(ident):
    """测试用显示名（贴近 RS 中文命名：颜色词 + 基础名，例如「红色控制器」）。"""
    color = color_of_path(ident.split(":", 1)[1])
    if color is None:
        return base_name(ident)
    return "%s%s" % (COLOR_ZH[color], base_name(ident))


def matches(ident, query):
    """搜索判据复刻（名字子串 / 注册 id 子串，不区分大小写；拼音这一路不在本模型里）。"""
    needle = query.strip().lower()
    return needle in display_name(ident).lower() or needle in ident.lower()


def family_matches_query(family_ids, query):
    """复刻 `familyMatchesQuery`：家族展示名（基础名 +「（N 色）」）或家族键 id 命中。"""
    shown = "%s（%d 色）" % (base_name(family_ids[0]), len(family_ids))
    needle = query.strip().lower()
    return needle in shown.lower() or needle in family_key(family_ids[0]).lower()


def build_rows(candidates, query="", expanded=frozenset(), collapsed=frozenset()):
    """复刻 `ChargerFilterScreen#rebuildItemRows`（行模型：dict 列表）。"""
    searching = bool(query.strip())
    folded = group_by_family(candidates)
    matched_set = ({ident for ident in candidates if matches(ident, query)}
                   if searching else set(candidates))
    # 只有「有命中成员」的家族才会出现在行模型里（与界面 `search()` 结果驱动 buildFamilies 一致）
    families = {key: members for key, members in folded.items()
                if any(ident in matched_set for ident in members)}
    plain = [ident for ident in candidates
             if family_key(ident) not in folded and ident in matched_set]
    rows = [{"kind": "item", "id": ident, "member": False} for ident in plain]
    for key in sorted(families):
        family = families[key]
        visible = [ident for ident in order_family(family) if ident in matched_set]
        is_expanded = (key not in collapsed) if searching else (key in expanded)
        highlighted = searching and not family_matches_query(family, query)
        rows.append({"kind": "header", "key": key, "expanded": is_expanded,
                     "highlighted": highlighted, "members": visible,
                     "total": len(folded[key]), "order": order_family(folded[key])})
    # 混排：组头排序键 = 家族基础名，普通条目 = 显示名（两者都小写，再按注册 id 兜底）
    def sort_key(row):
        if row["kind"] == "header":
            return (base_name_of_key(row["key"]).lower(), row["key"])
        return (display_name(row["id"]).lower(), row["id"])
    rows.sort(key=sort_key)
    flat_rows = []
    for row in rows:
        if row["kind"] == "item":
            flat_rows.append(row)
        else:
            flat_rows.append(row)
            if row["expanded"]:
                flat_rows.extend({"kind": "member", "id": ident, "member": True}
                                 for ident in row["members"])
    return flat_rows


def batch_toggle(selected, family_members, cap=1024):
    """复刻 `toggleGroupSelection`：全选 ⇒ 全不选；否则预检上限后补齐。"""
    selected = set(selected)
    if all(ident in selected for ident in family_members):
        return selected - set(family_members), None
    missing = [ident for ident in family_members if ident not in selected]
    if len(selected) + len(missing) > cap:
        return selected, "full"
    return selected | set(family_members), None


# ======================================================================================
# ③ 主流程
# ======================================================================================

def load_lang():
    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)
    return zh, en


GROUP_KEYS = (
    "gui.rs_create_compat.charger_filter.group.detail",
    "gui.rs_create_compat.charger_filter.group.header",
    "gui.rs_create_compat.charger_filter.group.hit",
    "gui.rs_create_compat.charger_filter.group.mod",
    "gui.rs_create_compat.charger_filter.group.tip",
)


def lang_ok(zh, en, keys=GROUP_KEYS):
    """中英成对 + 非空 + 参数个数一致（组头两个 %s、详情两个 %s、模组名两个 %s）。"""
    phrases = {
        "group.detail": 2,
        "group.header": 2,
        "group.hit": 0,
        "group.mod": 2,
        "group.tip": 0,
    }
    for key in keys:
        if key not in zh or key not in en:
            return False
        zh_value, en_value = str(zh[key]), str(en[key])
        if not zh_value.strip() or not en_value.strip():
            return False
        suffix = ".".join(key.split(".")[-2:])
        expected = phrases[suffix]
        if zh_value.count("%s") != expected or en_value.count("%s") != expected:
            return False
        # 中文值必须「有中文」或「只有占位符」（group.mod 是纯拼接格式「%s%s」，没有可翻译的字）
        zh_cjk = re.search(r"[\u3400-\u9fff\uff00-\uffef]", zh_value)
        if not zh_cjk and re.search(r"[A-Za-z]", zh_value.replace("%s", "")):
            return False
        if re.search(r"[\u3400-\u9fff\uff00-\uffef]", en_value):
            return False
    return True


def main():
    candidates_raw = read(CANDIDATES)
    screen_raw = read(SCREEN)
    cand = strip_comments(candidates_raw)
    screen = strip_comments(screen_raw)
    zh, en = load_lang()

    # ---------------- ① 分组模型 ----------------
    print("=== ① 分组模型（ChargerFilterCandidates）：16 色词表 / 长词优先 / 归一化 / ≥3 折叠 ===")
    check("①-a 16 色词表 = 原版 DyeColor 顺序（白→黑），且顺序是唯一来源",
          color_order_ok(candidates_raw),
          "got %r" % (color_order_table(candidates_raw),))
    check("①-b 折叠阈值 MIN_FAMILY_SIZE = 3（2 成员不折）", fold_threshold_ok(candidates_raw))
    check("①-c 颜色词表按长度降序构造（light_blue/light_gray 先于 blue/gray 尝试）",
          longest_first_ok(candidates_raw))
    check("①-d 颜色判据 = 「颜色 + '_' 前缀」（不是 contains / 后缀）", color_prefix_rule_ok(candidates_raw))
    check("①-e 家族键 = 去前缀后的基础注册名（ResourceLocation 重建）", family_key_rule_ok(candidates_raw))
    check("①-f 对外 API：colorOf / familyKey / familyOf / familyDisplayName + Family record",
          grouping_api_ok(candidates_raw))
    check("①-g 家族索引在同一次 all() 里建好（界面重建行不做 O(N²)）",
          family_index_built_once_ok(candidates_raw))
    check("①-h 只有成员数 < 3 的家族被排除（2 成员不折叠）", fold_rule_ok(candidates_raw))
    check("①-i 组内排序 = 固定颜色序，绝不按显示名字母序", family_order_ok(candidates_raw))
    check("①-j 家族展示名 = 模组名 + 基础名 +（N 色），模组名取加载器显示名并可降级",
          family_display_name_ok(candidates_raw))
    check("①-k 基础条目不在注册表时：显示名去颜色词（原版 color.minecraft.*），不硬编码",
          fallback_base_name_ok(candidates_raw))

    # ---------------- ② 界面 ----------------
    print("")
    print("=== ② 界面（ChargerFilterScreen）：组头 / 展开 / 三态 / 批量 / 搜索 / 缩进 ===")
    check("②-a Row 多 group/member 两列，组头数据在 GroupRow（三态 / 高亮 / 可见成员）",
          row_model_ok(screen_raw))
    check("②-b 物品页重建：折叠家族进组、组头与普通条目同一比较器混排、成员只在展开时插入",
          rebuild_item_rows_ok(screen_raw))
    check("②-c 搜索非空 ⇒ 命中的组自动展开（允许当场手动收起）", search_expand_ok(screen_raw))
    check("②-d 只命中成员时保留组头并高亮（不隐藏组头）", search_highlight_ok(screen_raw))
    check("②-e 两套展开状态分治（非搜索手动展开 / 搜索手动收起），搜索词变化清空",
          expand_state_ok(screen_raw))
    check("②-f 成员缩进 6px：勾选框 / 图标 / 名字一起右移，名字宽度扣缩进",
          indent_ok(screen_raw))
    check("②-g 三态勾选框：关 / 部分（绿横杠）/ 开", tri_state_ok(screen_raw))
    check("②-h 组头行：代表图标走 Family 缓存 + 名字走 RsccTextFit + 行尾已选/总数 + 高亮底色",
          group_row_draw_ok(screen_raw))
    check("②-i 点击分派：勾选框 = 批量全选/全不选，其它位置 = 展开/折叠",
          click_dispatch_ok(screen_raw))
    check("②-j 批量全选先预检上限（超了什么都不改并提示），取消勾选不受上限影响",
          batch_select_ok(screen_raw))
    check("②-k 组头行尾 = 「已选 / 家族总数」", suffix_group_ok(screen_raw))
    check("②-l 组头 tooltip：家族名 / 数量与已选 / 高亮原因 / 操作说明", tooltip_group_ok(screen_raw))
    check("②-m 四个页签互不影响（分组只在物品两页；TAB_COUNT / Tab 枚举不变）",
          four_tabs_ok(screen_raw, candidates_raw))

    # ---------------- ③ 语言键 ----------------
    print("")
    print("=== ③ 语言键：group.* 中英成对、非空、参数个数一致 ===")
    check("③-a 5 条 group.* 键 zh/en 成对、非空、%s 个数一致、zh 有中文 / en 无中日韩",
          lang_ok(zh, en))
    counterexample("③-b en_us 少一条 group 键 ⇒ 语言判据判负",
                   lang_ok(zh, {k: v for k, v in en.items()
                                if k != GROUP_KEYS[0]}) is False)

    # ---------------- ④ 行为模型 ----------------
    print("")
    print("=== ④ 行为模型：RS 2.0.9 真实候选（16 色控制器 + 3 无线物件） ===")
    candidates = rs_candidates()
    families = group_by_family(candidates)
    check("④-a 恰好折出 1 个家族（普通控制器 16 色）；创造控制器不在候选池（源码证据）",
          len(families) == 1
          and len(families["refinedstorage:controller"]) == 16
          and "refinedstorage:creative_controller" not in candidates,
          "families=%r" % (sorted(families),))
    check("④-b 3 个无线物件不成族（family_key = 自身 id，成员数 1 < 3）",
          family_key("refinedstorage:wireless_grid") == "refinedstorage:wireless_grid"
          and family_key("refinedstorage:portable_grid") == "refinedstorage:portable_grid"
          and family_key("refinedstorage:wireless_autocrafting_monitor")
          == "refinedstorage:wireless_autocrafting_monitor")
    check("④-c 默认变体（无颜色前缀）归一化到基础键，且排在组内最前",
          family_key("refinedstorage:controller") == "refinedstorage:controller"
          and order_family(families["refinedstorage:controller"])[0]
          == "refinedstorage:controller")
    check("④-d 组内严格白→黑：white 在 red 前、red 在 black 前，且 black 不在最前",
          order_family(families["refinedstorage:controller"]).index("refinedstorage:white_controller")
          < order_family(families["refinedstorage:controller"]).index("refinedstorage:red_controller")
          < order_family(families["refinedstorage:controller"]).index("refinedstorage:black_controller")
          and order_family(families["refinedstorage:controller"])[1]
          == "refinedstorage:white_controller")
    check("④-e 组内顺序 ≠ 字母序（字母序会把 black 排到 white 前面）",
          order_family(families["refinedstorage:controller"])
          != sorted(families["refinedstorage:controller"]))
    collapsed = build_rows(candidates)
    check("④-f 默认收起：19 个平铺条目压成 1 个组头 + 3 个普通条目 = 4 行",
          len(collapsed) == 4
          and sum(1 for row in collapsed if row["kind"] == "header") == 1
          and all(not row["expanded"] for row in collapsed if row["kind"] == "header"),
          "rows=%d" % len(collapsed))
    expanded = build_rows(candidates, expanded=frozenset(families))
    check("④-g 展开：1 组头 + 16 成员 + 3 普通条目 = 20 行（平铺 19 + 组头 1）",
          len(expanded) == 20
          and sum(1 for row in expanded if row["kind"] == "member") == 16,
          "rows=%d" % len(expanded))
    multi = multi_candidates()
    multi_families = group_by_family(multi)
    multi_rows = build_rows(multi)
    check("④-h 多家族混排：2 个组头 + 6 个普通条目 = 8 行；2 成员家族（acme:wire）不折叠、成员平铺可见",
          len(multi_families) == 2
          and len(multi_rows) == 8
          and "acme:wire" not in multi_families
          and any(row["kind"] == "item" and row["id"] == "acme:red_wire" for row in multi_rows),
          "rows=%d families=%r" % (len(multi_rows), sorted(multi_families)))

    print("")
    print("=== ⑤ 行为模型：搜索（自动展开 / 只命中成员时高亮 / 颜色序不被搜索打乱） ===")
    search_rows = build_rows(candidates, query="白色控制器")
    hit_groups = [row for row in search_rows if row["kind"] == "header"]
    check("⑤-a 搜索命中的组自动展开，且只显示命中的成员（1 个）",
          len(hit_groups) == 1
          and hit_groups[0]["expanded"]
          and hit_groups[0]["members"] == ["refinedstorage:white_controller"],
          "groups=%r" % [(row["key"], row["members"]) for row in hit_groups])
    check("⑤-b 只命中成员（组名没命中）⇒ 组头保留并标 highlighted",
          all(row["highlighted"] for row in hit_groups))
    base_hit_rows = build_rows(candidates, query="控制器")
    check("⑤-c 组名本身命中（「控制器」）⇒ 组头不算「被成员带出来」",
          len([row for row in base_hit_rows if row["kind"] == "header"]) == 1
          and all(not row["highlighted"] for row in base_hit_rows if row["kind"] == "header"))
    mixed = build_rows(candidates, query="wireless")
    check("⑤-d 搜索时普通条目照旧按显示名出现，不会被组头混排吞掉",
          [row["id"] for row in mixed if row["kind"] == "item"]
          == ["refinedstorage:wireless_autocrafting_monitor", "refinedstorage:wireless_grid"],
          "got %r" % ([row["id"] for row in mixed if row["kind"] == "item"],))
    filtered = build_rows(candidates, query="色控制器")
    members = [row["id"] for row in filtered if row["kind"] == "member"]
    expected_order = ["refinedstorage:%s_controller" % color for color in COLORS
                      if color != "light_blue"]
    check("⑤-e 搜索过滤后的成员仍按家族固定颜色序（不会变成显示名序）",
          members == expected_order and members != sorted(members, key=display_name),
          "got %r" % (members,))

    print("")
    print("=== ⑥ 行为模型：批量全选 / 全不选 / 上限预检 ===")
    controller_family = families["refinedstorage:controller"]
    check("⑥-a 空集合点组头 ⇒ 全选该家族 16 个",
          batch_toggle(set(), controller_family)[0] == set(controller_family))
    partial = set(controller_family[:5])
    check("⑥-b 部分选中点组头 ⇒ 全选（不是翻面成只剩没选的那些）",
          batch_toggle(partial, controller_family)[0] == set(controller_family))
    check("⑥-c 全选中点组头 ⇒ 全不选",
          batch_toggle(set(controller_family), controller_family)[0] == set())
    tiny_cap = batch_toggle(set(controller_family[:10]), controller_family, cap=15)
    check("⑥-d 剩余名额不够装下缺的成员 ⇒ 什么都不改并返回 full（绝不半个状态成功）",
          tiny_cap[0] == set(controller_family[:10]) and tiny_cap[1] == "full")
    check("⑥-e 组头「已选/总数」按整个家族算（搜索过滤不改变这个计数口径）",
          len([m for m in controller_family if m in set(controller_family[:3])]) == 3
          and len([row for row in build_rows(candidates, query="白色")
                   if row["kind"] == "header"][0]["order"]) == 16)

    # ---------------- 反例自证 ----------------
    print("")
    print("=== 反例自证：把坏写法拼回源码副本 / 模型，断言判据真的判负 ===")

    counterexample("① `redstone` 含 red 但不能被当成红色变体（模型）",
                   color_of_path("redstone") is None
                   and color_of_loose("redstone") == "red",
                   "loose=%r" % (color_of_loose("redstone"),))
    counterexample("② `light_blue_controller` 不能被 blue 先吞（模型：长词优先 vs 子串实现）",
                   color_of_path("light_blue_controller") == "light_blue"
                   and color_of_loose("light_blue_controller") == "blue"
                   and color_of_path("light_gray_cable") == "light_gray")
    counterexample("②b 源码把颜色判据改成 contains ⇒ color_prefix_rule 判负",
                   color_prefix_rule_ok(candidates_raw.replace(
                       "path.startsWith(color)", "path.contains(color)")) is False)
    counterexample("②c 源码把颜色词表改成 COLOR_ORDER 原序（不再长词优先）⇒ 判负",
                   longest_first_ok(candidates_raw.replace(
                       "COLOR_PREFIXES_LONGEST_FIRST = longestFirstColorPrefixes();",
                       "COLOR_PREFIXES_LONGEST_FIRST = List.copyOf(COLOR_ORDER);")) is False)
    two_family = ["acme:red_wire", "acme:blue_wire"]
    three_family = ["acme:red_pipe", "acme:blue_pipe", "acme:white_pipe"]
    counterexample("③ 2 成员家族不折叠、3 成员才折叠（模型）",
                   group_by_family(two_family) == {}
                   and len(group_by_family(three_family).get("acme:pipe", [])) == 3)
    counterexample("③b 源码把阈值改成 2 ⇒ fold_threshold 判负",
                   fold_threshold_ok(candidates_raw.replace(
                       "MIN_FAMILY_SIZE = 3;", "MIN_FAMILY_SIZE = 2;")) is False)
    counterexample("④ 组内顺序字母序 ≠ 固定颜色序（模型）",
                   order_family(three_family) == ["acme:white_pipe", "acme:blue_pipe",
                                                  "acme:red_pipe"]
                   and order_family(three_family) != sorted(three_family))
    counterexample("④b 源码把组内排序改成显示名字母序 ⇒ family_order 判负",
                   family_order_ok(candidates_raw.replace(
                       "comparingInt((Item item) -> colorOrderIndex(colorOfId(IDS.get(item))))",
                       "comparingInt((Item item) -> NAMES.getOrDefault(item, \"\").length())")) is False)
    counterexample("⑤ 成员缩进常量改 0 ⇒ indent 判负",
                   indent_ok(screen_raw.replace("GROUP_INDENT = 6;", "GROUP_INDENT = 0;")) is False)
    counterexample("⑤b 名字宽度不扣缩进 ⇒ indent 判负",
                   indent_ok(screen_raw.replace(
                       "final int rowNameMax = Math.max(8, nameMax - rowIndent);",
                       "final int rowNameMax = nameMax;")) is False)
    counterexample("⑤c 搜索不再自动展开 ⇒ search_expand 判负",
                   search_expand_ok(screen_raw.replace(
                       "? !collapsedGroups.contains(family.key())",
                       "? false")) is False)
    counterexample("⑤d 搜索高亮条件改成永远 false ⇒ search_highlight 判负",
                   search_highlight_ok(screen_raw.replace(
                       "final boolean highlighted = searching && !familyMatchesQuery(family, query);",
                       "final boolean highlighted = false;")) is False)
    counterexample("⑥ 批量全选去掉上限预检 ⇒ batch_select 判负",
                   batch_select_ok(screen_raw.replace(
                       "if (selection.size() + missing > ChargerFilterLimits.MAX_ITEM_ENTRIES) {",
                       "if (false) {")) is False)
    counterexample("⑦ 折叠组成员不再只跟展开的组（无条件插入）⇒ rebuild_item_rows 判负",
                   rebuild_item_rows_ok(screen_raw.replace(
                       "if (row.group() != null && row.group().expanded()) {",
                       "if (row.group() != null) {")) is False)
    counterexample("⑧ TAB_COUNT 改成 5 ⇒ 四个页签判据判负",
                   four_tabs_ok(screen_raw.replace("TAB_COUNT = 4;", "TAB_COUNT = 5;"),
                                candidates_raw) is False)
    counterexample("⑨ 界面自己另写一套颜色判据（多出一个 familyOf 调用点）⇒ 四页签/单点判据判负",
                   four_tabs_ok(screen_raw.replace(
                       "toggleRow(row);", "toggleRow(row); ChargerFilterCandidates.familyOf(null);"),
                                candidates_raw) is False)
    counterexample("⑩ 语言键删掉 group.header ⇒ 语言判据判负",
                   lang_ok(zh, {k: v for k, v in en.items()
                                if k != "gui.rs_create_compat.charger_filter.group.header"}) is False)

    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        for name in FAILS:
            print("  FAILED: " + name)
        print("SELFCHECK_FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
