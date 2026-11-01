#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 107 轮自检：充电过滤列表「只显示物品名 · id 只进 tooltip · 搜索仍支持 id」。

用户口径（原话）
----------------
> 你就直接显示它这个物品名字就可以了，它这个 id 的话呢你 tooltip 中加入就可以了，
> 或者说你根本就不需要加入他的物品 id，你显示他的中文名就可以了，但是要支持搜索 id。

落点（一句话）
--------------
物品行 = **只有本地化物品名**（`ItemStack#getHoverName()`），行尾不再画注册 id 的 path；
完整注册 id 仍然逐字出现在**行 tooltip** 里；搜索框照旧同时匹配「物品名子串」与
「注册 id 子串」（不区分大小写）。这三件事互相独立，本脚本逐条钉死，并附反例。

断言清单
--------
① 名字来源是**本地化悬浮名**：`ChargerFilterCandidates` 用 `stack.getHoverName().getString()`
   建 NAMES；`ChargerFilterScreen#displayNameOf` 的物品分支调 `nameOf`（不得调 `idOf().getPath()`
   之类的「拿 id 当名字」写法）。
② 行尾后缀：`suffixOf` 的物品分支**原样返回空串**，方法体内不得出现 `getPath()` / `idOf(`。
③ 列表绘制：仍按「后缀实宽」反推名字可用像素（`suffixW/suffixX/nameMax` 三行在），
   且空后缀走 `if (!suffix.isEmpty())` 不绘制 —— 玩家行的「在线 / 离线」不受影响。
④ tooltip：物品行的 tooltip 仍含 `row.id`（完整 id 有地方可看，只是不占列表宽度）。
⑤ 搜索：`search()` 同时有「名字（第 110 轮起拼音感知，PinyinSearch）」与「id 子串」两个分支；
   另配一份纯 Python 行为模型（中文名命中 / id 命中 / 名字空间前缀命中 / 大小写 / 无命中）。
⑥ 语言键 `row.id` / `search.tip`：zh/en 成对、`row.id` 带 `%s`、zh 提到「ID」、en 无中日韩。
⑦ 反例自证（6 条）：把「旧写法 / 错写法」拼进源码副本，断言判据真的判负。

用法：python tools/selfcheck_round107_charger_filter_names.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老 Python / 重定向
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SCREEN = os.path.join(PKG, "client", "screen", "ChargerFilterScreen.java")
CAND = os.path.join(PKG, "client", "screen", "ChargerFilterCandidates.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

CHECKS = [0]
COUNTER = [0]
FAILS = []

CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释（判据只看真代码；注释里提到旧写法不算）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def body_of(method_text, signature):
    """从 `signature {` 起按花括号配对取出方法体（不含签名）。取不到返回空串。"""
    start = method_text.find(signature)
    if start < 0:
        return ""
    brace = method_text.find("{", start + len(signature) - 1)
    if brace < 0:
        return ""
    depth = 0
    for index in range(brace, len(method_text)):
        char = method_text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return method_text[brace + 1:index]
    return ""


def check(label, ok):
    CHECKS[0] += 1
    if not ok:
        FAILS.append(label)
        print("FAIL %s" % label)
    else:
        print("ok   %s" % label)
    return ok


def counterexample(label, mutated_fails):
    """反例自证：可变体必须让判据判负（mutated_fails 为 True 才算通过）。"""
    COUNTER[0] += 1
    if not mutated_fails:
        FAILS.append("反例未判负: " + label)
        print("FAIL 反例 %s（可变体居然通过了判据）" % label)
    else:
        print("ok   反例 %s 已被判负" % label)


# ==================== 判据（吃源码文本，便于反例复用） ====================

def names_ok(candidates_text, screen_text):
    """① 名字 = 本地化悬浮名，不是注册 id。"""
    cand = strip_comments(candidates_text)
    screen = strip_comments(screen_text)
    body = body_of(screen, "private String displayNameOf(final Row row)")
    return (
        "NAMES.put(item, stack.getHoverName().getString());" in cand
        and 'return row.item() == null ? "" : ChargerFilterCandidates.nameOf(row.item());' in body
        and "getPath()" not in body
        and "idOf(" not in body
    )


def suffix_removed_ok(screen_text):
    """② 物品行后缀为空串，且方法体内不再从 id 取字。"""
    screen = strip_comments(screen_text)
    body = body_of(screen, "private String suffixOf(final Row row)")
    if not body:
        return False
    branch = re.search(r'if\s*\(!row\.player\(\)\)\s*\{(.*?)\}', body, flags=re.S)
    if not branch:
        return False
    item_branch = branch.group(1)
    return (
        'return "";' in item_branch
        and "getPath()" not in body
        and "idOf(" not in body
    )


def list_draw_ok(screen_text):
    """③ 名字宽度仍由后缀实宽反推；空后缀不绘制（在线/离线玩家行照旧）。"""
    screen = strip_comments(screen_text)
    return (
        "final int suffixW = font.width(suffix);" in screen
        and "final int suffixX = left + ROW_SUFFIX_RIGHT - suffixW;" in screen
        and "final int nameMax = Math.max(8, suffixX - (left + ROW_NAME_X) - NAME_SUFFIX_GAP);" in screen
        and "if (!suffix.isEmpty()) {" in screen
        and "COLOR_ONLINE" in screen
    )


def tooltip_ok(screen_text):
    """④ 物品行 tooltip 仍给完整 id。"""
    screen = strip_comments(screen_text)
    body = body_of(screen, "private void renderRowTooltip(final GuiGraphics guiGraphics, final Row row,")
    return 'LANG + "row.id"' in body and "id.toString()" in body


def search_ok(candidates_text):
    """⑤ search 同时匹配名字与 id；名字这一路在第 110 轮改为拼音感知（{@link PinyinSearch}），
    id 这一路仍是纯 ASCII 子串（统一小写）。"""
    cand = strip_comments(candidates_text)
    body = body_of(cand, "public static List<Item> search(final String query)")
    return (
        "PinyinSearch.contains(name, needle)" in body
        and "id.toString().toLowerCase(Locale.ROOT).contains(needleLower)" in body
        and "toLowerCase(Locale.ROOT)" in cand
        and "MAX_SEARCH_RESULTS" in body
    )


def lang_ok(zh_text, en_text):
    """⑥ row.id / search.tip 的 zh/en 成对与内容口径。"""
    try:
        zh = json.loads(zh_text)
        en = json.loads(en_text)
    except Exception:
        return False
    key_id = "gui.rs_create_compat.charger_filter.row.id"
    key_tip = "gui.rs_create_compat.charger_filter.search.tip"
    for key in (key_id, key_tip):
        if key not in zh or key not in en:
            return False
        if not zh[key].strip() or not en[key].strip():
            return False
        if CJK_RE.search(en[key]):
            return False
    return "%s" in zh[key_id] and "ID" in zh[key_id].upper()


# ==================== ⑤ 的纯 Python 行为模型 ====================

def model_search(candidates, query, limit=512):
    """逐字复刻 ChargerFilterCandidates#search 的判定顺序（名字子串 或 id 子串）。"""
    needle = (query or "").strip().lower()
    if not needle:
        return [c for c in candidates]
    out = []
    for name, item_id in candidates:
        if needle in name.lower() or needle in item_id.lower():
            out.append((name, item_id))
            if len(out) >= limit:
                break
    return out


def model_ok():
    cands = [
        ("能量立方", "rs_create_compat:fe_energy_cube"),
        ("热能离心机", "thermalfoundation:machine_frame"),
        ("能量表", "mekanism:energy_tablet"),
        ("红石能量电池", "thermal:redstone_energy_cell"),
    ]
    cases = [
        ("能量", [("能量立方", "rs_create_compat:fe_energy_cube"),
                  ("能量表", "mekanism:energy_tablet"),
                  ("红石能量电池", "thermal:redstone_energy_cell")]),
        ("fe_energy", [("能量立方", "rs_create_compat:fe_energy_cube")]),
        ("MEKANISM:", [("能量表", "mekanism:energy_tablet")]),
        # 命名空间前缀：`thermal:` 是 thermal 的子串（thermalfoundation 不以 `thermal:` 开头）
        ("thermal:", [("红石能量电池", "thermal:redstone_energy_cell")]),
        ("energy", [("能量立方", "rs_create_compat:fe_energy_cube"),
                    ("能量表", "mekanism:energy_tablet"),
                    ("红石能量电池", "thermal:redstone_energy_cell")]),
        ("没有这个东西", []),
    ]
    for query, expected in cases:
        got = model_search(cands, query)
        if got != expected:
            print("    模型不符：query=%r got=%r expected=%r" % (query, got, expected))
            return False
    return True


def main():
    screen_text = read(SCREEN)
    cand_text = read(CAND)
    zh_text = read(os.path.join(LANG_DIR, "zh_cn.json"))
    en_text = read(os.path.join(LANG_DIR, "en_us.json"))

    check("① 物品名来自本地化悬浮名（getHoverName），不是注册 id",
          names_ok(cand_text, screen_text))
    check("② 物品行行尾不再显示注册 id（suffixOf 物品分支返回空串）",
          suffix_removed_ok(screen_text))
    check("③ 名字宽度仍按后缀实宽反推、空后缀不绘制（玩家在线/离线不变）",
          list_draw_ok(screen_text))
    check("④ 物品行 tooltip 仍逐字给出完整注册 id",
          tooltip_ok(screen_text))
    check("⑤ 搜索同时匹配物品名与注册 id（不区分大小写）",
          search_ok(cand_text))
    check("⑤b 搜索行为模型：中文名 / id / 命名空间前缀 / 大小写 / 无命中",
          model_ok())
    check("⑥ 语言键 row.id（带 %s，zh 提到 ID）与 search.tip 的 zh/en 成对",
          lang_ok(zh_text, en_text))

    # ---------------- 反例自证 ----------------
    # ① 把 displayNameOf 的物品分支改成「拿 id 当名字」
    mutated = screen_text.replace(
        'return row.item() == null ? "" : ChargerFilterCandidates.nameOf(row.item());',
        'return row.item() == null ? "" : ChargerFilterCandidates.idOf(row.item()).getPath();')
    counterexample("① displayNameOf 用注册 id 当名字 ⇒ names_ok",
                   not names_ok(cand_text, mutated))

    # ② 把 suffixOf 物品分支改回 id.getPath()
    mutated = screen_text.replace(
        "            // 见方法注释：物品行没有行尾小字（id 只在 tooltip 里）\n            return \"\";",
        "            return ChargerFilterCandidates.idOf(row.item()).getPath();")
    counterexample("② 行尾又显示 id.getPath() ⇒ suffix_removed_ok",
                   mutated != screen_text and not suffix_removed_ok(mutated))

    # ③ 去掉空后缀保护（无条件绘制）
    mutated = screen_text.replace("if (!suffix.isEmpty()) {", "if (true) {")
    counterexample("③ 空后缀也去绘制（丢掉 !suffix.isEmpty() 保护）⇒ list_draw_ok",
                   mutated != screen_text and not list_draw_ok(mutated))

    # ④ tooltip 不再给 id
    mutated = screen_text.replace('lines.add(Component.translatable(LANG + "row.id", id.toString()));',
                                  "lines.add(Component.literal(\"\"));")
    counterexample("④ tooltip 里删掉完整 id ⇒ tooltip_ok",
                   mutated != screen_text and not tooltip_ok(mutated))

    # ⑤ 搜索丢掉 id 分支（只能按名字搜；第 110 轮名字一路已是 PinyinSearch）
    mutated = cand_text.replace(
        "            if (PinyinSearch.contains(name, needle)\n"
        "                || (id != null && id.toString().toLowerCase(Locale.ROOT).contains(needleLower))) {",
        "            if (PinyinSearch.contains(name, needle)) {")
    counterexample("⑤ 搜索只按名字、不认 id ⇒ search_ok",
                   mutated != cand_text and not search_ok(mutated))

    # ⑥ 语言文件 en 塞中日韩
    en_data = json.loads(en_text)
    en_data["gui.rs_create_compat.charger_filter.row.id"] = "物品 ID：%s"
    counterexample("⑥ en_us 里出现中文 ⇒ lang_ok",
                   not lang_ok(zh_text, json.dumps(en_data, ensure_ascii=False)))

    print("")
    print("checks=%d counterexamples=%d" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("FAILED: " + ", ".join(FAILS))
        return 1
    print("SELFCHECK OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
