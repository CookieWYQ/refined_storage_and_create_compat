#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 140 轮自检：48 格窗口 JEI 拖入「新 id 被忽略」——拖到已有条目的格子必须整条替换。

第 125 轮复核 §4.6 遗留（本轮之前一直未修）
------------------------------------------
基础版 `CollectionCacheBlockEntity#setMarkerConfig` 的语义是：
**同 id 才 update（数量 / 规则），否则替换该下标**。
高级版 `AdvancedCollectionCacheMenu#applyMarkerConfig`（7 参覆写）只调
`setAreaMarkerConfig`（只改数量 / 规则、保留旧 id）⇒ JEI 把**另一个**物品拖到 48 格里
**已经有条目**的格子上时，拖进来的 id 与数据组件被静默丢掉（格子看起来没变）。
第 130 轮只修了「空格子静默丢包」，本条是它的另一半。

本轮改法
--------
1. `AdvancedCollectionCacheBlockEntity#replaceAreaMarkerEntry`：整条替换某下标，
   去重时**排除本下标**（替换本来就要覆盖旧条目），单条体积 / 整表预算与新增同口径；
2. 菜单 7 参覆写：`id 或 fluid 与旧条目不同` ⇒ 走替换（失败有 actionbar 文案），
   `id == null` ⇒ 与基类同义清空；
3. 同 id 仍走原来的 `setAreaMarkerConfig`（只改规则，不复活旧行为）。

用法：python tools/selfcheck_round140_advanced_jei_replace.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

ADV_BLOCK = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
BASE_BLOCK = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
ADV_MENU = os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java")
JEI_PLUGIN = os.path.join(JAVA, "client", "CollectionCacheJeiPlugin.java")

REPLACE_SIG = "public MarkerAddResult replaceAreaMarkerEntry("
MENU_SIG = "protected void applyMarkerConfig(final int markerIndex, final boolean fluid,"
MENU_NEXT_SIG = "protected void applyMarkerConfig(final int markerIndex, final long amount,"

_passed = 0
_failed = 0
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  PASS  %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL  %s%s" % (name, ("  [" + detail + "]") if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 行注释与 /* */ 块注释（判据只看真代码，避免注释里出现同名符号就假绿）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def slice_between(text, start_marker, end_marker):
    start = text.find(start_marker)
    if start < 0:
        return ""
    end = text.find(end_marker, start + len(start_marker))
    return text[start:end if end >= 0 else len(text)]


def menu_replaces_new_id(body):
    return (
        "existing.fluid() != fluid || !id.equals(existing.id())" in body
        and "advanced.replaceAreaMarkerEntry(editingAreaIndex, markerIndex, marker)" in body
        and "notifyMarkerResult(advanced.replaceAreaMarkerEntry(" in body
        and "new cretae.cookiewyq.rs_create_compat.support.MarkerEntry(false, id," in body
        and "existing == null || existing.isEmpty()" in body
        and body.index("existing == null || existing.isEmpty()")
        < body.index("advanced.replaceAreaMarkerEntry(")
        and body.index("advanced.replaceAreaMarkerEntry(")
        < body.index("advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,")
        and "if (id == null) {" in body
        and "advanced.removeAreaMarker(editingAreaIndex, markerIndex);" in body
    )


def block_replaces_safely(body):
    return (
        REPLACE_SIG in body
        and "marker == null || marker.isEmpty()" in body
        and "markerIndex < 0 || markerIndex >= entries.size()" in body
        and "if (i == markerIndex) {" in body
        and "continue;" in body
        and "MarkerBudget.isEntryTooBig(marker)" in body
        and "AreaMarks.isOverBudget(selectorStack(), area, next)" in body
        and "next.set(markerIndex, marker)" in body
        and "list.set(markerIndex, marker)" in body
        and body.index("if (i == markerIndex) {") < body.index("MarkerAddResult.DUPLICATE")
        and body.index("AreaMarks.isOverBudget(") < body.index("withAreaFilter(areaIndex, list -> {")
    )


def main():
    print("第 140 轮自检：48 格窗口 JEI 拖入「新 id 被忽略」→ 已有条目整条替换")

    raw_adv = read(ADV_BLOCK)
    raw_menu = read(ADV_MENU)
    raw_base = read(BASE_BLOCK)
    raw_jei = read(JEI_PLUGIN)

    adv = strip_comments(raw_adv)
    menu = strip_comments(raw_menu)
    base = strip_comments(raw_base)
    jei = strip_comments(raw_jei)

    block_body = slice_between(adv, REPLACE_SIG, "\n    /** 清除某区域的第")
    menu_body = slice_between(menu, MENU_SIG, MENU_NEXT_SIG)

    # ---- ① 客户端：JEI 拖入确实把 id 发给了服务端（否则服务端无从替换） ----
    check("①-1 JEI 拖入 48 格时把物品 id 放进 SetCollectionMarkerConfigPacket",
          "BuiltInRegistries.ITEM.getKey(stack.getItem())" in jei
          and "new SetCollectionMarkerConfigPacket(" in jei
          and "menu.containerId, globalIndex, false," in jei)
    check("①-2 拖放目标仍是匹配区 48 格（MATCH_WINDOW），没有第二套分支",
          "CollectionCacheMenu.MATCH_WINDOW" in jei
          and jei.count("menu.getSlot(i)") == 1)

    # ---- ② 基础版口径：同 id update、异 id 替换（高级版要对齐它） ----
    check("②-1 基础版 setMarkerConfig 的「同 id 才保留旧 nbt、异 id 替换该下标」仍在",
          "if (!existing.isEmpty() && existing.fluid() == fluid && id.equals(existing.id())) {" in base
          and "setMarker(index, candidate);" in base)

    # ---- ③ 高级版数据层：替换原语存在，且四道闸门都在 ----
    check("③-1 新增 replaceAreaMarkerEntry（整条替换，不是只 update）",
          block_body != "" and block_replaces_safely(block_body))
    check("③-2 替换只允许落在「已有条目」的格子上（空格子仍归 addAreaMarkerEntry，不替换空气）",
          "|| entries.get(markerIndex) == null || entries.get(markerIndex).isEmpty()" in block_body)
    check("③-3 去重时跳过本下标自身（否则替换自己会被误判 DUPLICATE）",
          "if (i == markerIndex) {" in block_body
          and block_body.index("if (i == markerIndex) {") < block_body.index("MarkerAddResult.DUPLICATE"))
    check("③-4 预算闸门与新增同口径（单条 MarkerBudget + 整表 AreaMarks.isOverBudget）",
          "MarkerBudget.isEntryTooBig(marker)" in block_body
          and "AreaMarks.isOverBudget(selectorStack(), area, next)" in block_body)

    # ---- ④ 高级版菜单：异 id 走替换、同 id 走原 update、id==null 清空 ----
    check("④-1 7 参覆写里「id 或 fluid 与旧条目不同」分支存在，且在 setAreaMarkerConfig 之前",
          menu_replaces_new_id(menu_body))
    check("④-2 替换失败会给玩家提示（DUPLICATE / FULL / TOO_COMPLEX 不再静默）",
          "notifyMarkerResult(advanced.replaceAreaMarkerEntry(" in menu_body
          and "case DUPLICATE -> player.displayClientMessage(" in strip_comments(raw_menu))
    check("④-3 同 id 仍只 update（不把数量 / 规则写入顺手换成替换）",
          menu_body.count("advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,") == 1)

    # ---- ⑤ 反例自证：把旧写法拼回去，判据必须判负 ----
    broken_menu = menu_body.replace(
        "notifyMarkerResult(advanced.replaceAreaMarkerEntry(editingAreaIndex, markerIndex, marker));",
        "advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt, existing.tags());")
    check("⑤-1 旧菜单（异 id 也只 update ⇒ 新 id 被忽略）⇒ ④-1 判据判负",
          broken_menu != menu_body and not menu_replaces_new_id(broken_menu))

    broken_self = block_body.replace(
        "            if (i == markerIndex) {\n                continue;\n            }\n", "")
    check("⑤-2 去掉「跳过本下标」⇒ ③-3 判据判负（替换自己会被当重复拒绝）",
          broken_self != block_body
          and "if (i == markerIndex) {\n                continue;\n            }" not in broken_self
          and "if (i == markerIndex) {" not in broken_self)

    broken_budget = block_body.replace(
        "if (AreaMarks.isOverBudget(selectorStack(), area, next)) {", "if (false) {")
    check("⑤-3 去掉整表预算闸门 ⇒ ③-4 判据判负（改包 / 大组件可撑爆标记表）",
          broken_budget != block_body
          and "AreaMarks.isOverBudget(selectorStack(), area, next)" not in broken_budget)

    broken_set = block_body.replace("next.set(markerIndex, marker)", "next.add(marker)")
    check("⑤-4 把「替换」写成「追加」⇒ ③-1 判据判负（条数会偷偷变多）",
          broken_set != block_body and not block_replaces_safely(broken_set))

    print("\n结果：%d 通过 / %d 失败" % (_passed, _failed))
    if _failed:
        print("失败项：")
        for name, detail in _failures:
            print("  - %s" % name)
        return 1
    print("全部通过（JEI 带 id / 基础版口径 / 替换原语四道闸门 / 菜单分支顺序 / 反例自证）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
