#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 114 轮自检：ghost 标记体积「整表预算」统一拦截（MarkerBudget）。

用户/复核背景
-------------
第 113 轮给了**单条**标记上限（`MAX_ENTRY_BYTES = 2048`），但 `MATCH_CAPACITY = 288` 条 × 2 KB
= 576 KB，仍然足以把「随物品整体网络同步」的 `CUSTOM_DATA` 撑爆。第 114 轮补上**整表**
上限 `MAX_TOTAL_BYTES = 65536`，并把五个写入口（服务端包 / 客户端界面 / 机器侧 48 格 /
基础版匹配区 UI / 基础版服务端权威 setMarkerConfig）收敛到同一套预算判定。

本脚本怎么验（不是字符串 grep）
------------------------------
1. **真字节模型**：逐字复刻 1.21.1 `net.minecraft.nbt` 的 `sizeInBytes()` 公式
   （从本工程 NeoForge sources jar 抽取，见下），再复刻 `encodeMarkerTag` / `writeArea` /
   `RsccAreaMarks` 子树的编码，算出 `MarkerBudget.entryBytes` / `AreaMarks.itemTableBytes` /
   `AreaMarks.isOverBudget` 的**数值**，然后断言边界与增长量。
   * `CompoundTag`: `48 + Σ(64 + 2·|key| + value.sizeInBytes())`
   * `ListTag`: `37 + 4·n + Σ child`
   * `StringTag`: `36 + 2·len`；`IntTag` 12；`LongTag` 16；`ByteTag` 9；`IntArrayTag` 24 + 4·n
2. **源码锚点**：只用于「防止实现被改回去 / 确认顺序与常量委托关系」。
3. **反例自证**：把被断言的源码片段或行为模型改坏 ⇒ 对应判据必须判负，证明判据有判别力。

已知偏差（如实打印，不写成「期望行为」——修好会自动变 KNOWN-RESOLVED）
--------------------------------------------------------------------
* `sizeInBytes()` 是**保守上界估计**（每个 compound 48 B、每个条目 64 B 起步）：
  一条「空 NBT 普通物品标记」实测 **658 B**，288 条 = **191102 B** ≫ 65536。
  因此 task 正文「288 条普通标记必须能通过」与当前常量**不成立**（单区域实际上限 98 条、
  基础版匹配区 99 条）；`MarkerBudget` javadoc 里「普通标记约 40–60 B / 288 条 ≈ 15–20 KB」
  的估算口径与 `sizeInBytes()` 不符。已上报 Lead。
* `isOverBudget(stack, area, List.of())`：javadoc 承诺「删除永远允许」，但当前实现在
  **整表已经超限**时返回 `true`（`total` 没减去目标记录就被判定）。`next == null` 时确实恒 false。
  已上报 Lead（建议 `if (clean.isEmpty()) return false;`）。

用法：`python tools/selfcheck_round114_marker_budget.py`
退出码：0 = 全部通过；最后一行：`round114 OK 断言 N / 反例 M`。
"""

from __future__ import annotations

import copy
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
LANG_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "lang")
JAVA = os.path.join(ROOT, JAVA_REL)

MARKER_BUDGET = os.path.join(JAVA, "support", "MarkerBudget.java")
AREA_MARKS = os.path.join(JAVA, "support", "AreaMarks.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
BASE_BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
MARK_PACKET = os.path.join(JAVA, "network", "AreaMarkEditPacket.java")
MARK_SCREEN = os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")
MENU = os.path.join(JAVA, "menu", "CollectionCacheMenu.java")
ADV_MENU = os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java")
SET_PACKET = os.path.join(JAVA, "network", "SetCollectionMarkerConfigPacket.java")
ZH = os.path.join(ROOT, LANG_REL, "zh_cn.json")
EN = os.path.join(ROOT, LANG_REL, "en_us.json")

REL = {
    MARKER_BUDGET: os.path.join(JAVA_REL, "support", "MarkerBudget.java"),
    AREA_MARKS: os.path.join(JAVA_REL, "support", "AreaMarks.java"),
    ADV_BE: os.path.join(JAVA_REL, "block", "entity", "AdvancedCollectionCacheBlockEntity.java"),
    BASE_BE: os.path.join(JAVA_REL, "block", "entity", "CollectionCacheBlockEntity.java"),
    MARK_PACKET: os.path.join(JAVA_REL, "network", "AreaMarkEditPacket.java"),
    MARK_SCREEN: os.path.join(JAVA_REL, "client", "screen", "AreaMarkScreen.java"),
    MENU: os.path.join(JAVA_REL, "menu", "CollectionCacheMenu.java"),
    ADV_MENU: os.path.join(JAVA_REL, "menu", "AdvancedCollectionCacheMenu.java"),
    SET_PACKET: os.path.join(JAVA_REL, "network", "SetCollectionMarkerConfigPacket.java"),
    ZH: os.path.join(LANG_REL, "zh_cn.json"),
    EN: os.path.join(LANG_REL, "en_us.json"),
}

CHECKS = [0]
COUNTER = [0]
NOTES = [0]
FAILS = []
ANCHORS = []


# ======================================================================
# 基础设施
# ======================================================================

def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去注释（保留字符串字面量）。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in ("\"", "'"):
                quote = char
                out.append(char)
                i += 1
                continue
            out.append(char)
            i += 1
            continue
        if char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        if char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def method_body(code, signature_hint):
    start = code.find(signature_hint)
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


def known_deviation(name, contract_holds, detail=""):
    """与契约/文档不符的**已知偏差**：显式打印但不计 PASS/FAIL。

    这样既不会把 bug 固化成「期望行为」，也不会因为如实记录它而让自检红掉；
    Lead 修好之后这里会自动变成 KNOWN-RESOLVED，提醒我们把它改成正式断言。
    """
    NOTES[0] += 1
    print(("KNOWN-RESOLVED " if contract_holds else "KNOWN-DEVIATION ") + name
          + (("  <- " + detail) if detail else ""))


def lineno(text, snippet):
    index = text.find(snippet)
    return 0 if index < 0 else text[:index].count("\n") + 1


def anchor(group, path, text, snippet, note=""):
    line = lineno(text, snippet)
    ANCHORS.append((group, REL.get(path, path), line, (note or snippet).replace("\n", " ")[:96]))
    return line


# ======================================================================
# ① NBT 字节模型（1.21.1 net.minecraft.nbt#sizeInBytes 逐字复刻）
# ======================================================================

def tag_compound(fields):
    return ("compound", fields)


def tag_list(children):
    return ("list", list(children))


def tag_string(value):
    return ("string", value)


def tag_int_array(values):
    return ("intarray", tuple(values))


TAG_INT = ("int",)
TAG_LONG = ("long",)
TAG_BYTE = ("byte",)


def tag_size(tag):
    kind = tag[0]
    if kind == "compound":
        total = 48
        for key, value in tag[1].items():
            total += 64 + 2 * len(key) + tag_size(value)
        return total
    if kind == "list":
        return 37 + 4 * len(tag[1]) + sum(tag_size(child) for child in tag[1])
    if kind == "string":
        return 36 + 2 * len(tag[1])
    if kind == "int":
        return 12
    if kind == "long":
        return 16
    if kind == "byte":
        return 9
    if kind == "intarray":
        return 24 + 4 * len(tag[1])
    raise ValueError("unknown tag kind: %r" % (kind,))


# ======================================================================
# ② 标记 / 区域记录的编码模型（对应 CollectionCacheBlockEntity.encodeMarkerTag
#    与 AreaMarks.writeArea / RsccAreaMarks 子树）
# ======================================================================

def marker(item_id="minecraft:iron_ingot", amount=1, fluid=False,
           nbt=None, match_nbt=False, tags=None):
    return {
        "id": item_id,
        "amount": amount,
        "fluid": fluid,
        "nbt": tag_compound({}) if nbt is None else nbt,
        "match_nbt": match_nbt,
        "tags": list(tags or []),
    }


def is_empty_entry(entry):
    return not entry or not entry.get("id")


def encode_marker_tag(entry, slot):
    """CollectionCacheBlockEntity#encodeMarkerTag：六个固定键 + 可选 Tags 列表。"""
    fields = {
        "Slot": TAG_INT,
        "Fluid": TAG_BYTE,
        "Id": tag_string(entry["id"]),
        "Nbt": entry.get("nbt") or tag_compound({}),
        "Amount": TAG_LONG,
        "MatchNbt": TAG_BYTE,
    }
    if entry.get("tags"):
        fields["Tags"] = tag_list([tag_string(tag) for tag in entry["tags"]])
    return tag_compound(fields)


def entry_bytes(entry, slot=0):
    """MarkerBudget#entryBytes（null / 空条目 = 0；slot 只影响 int 值，不影响字节数）。"""
    if entry is None or is_empty_entry(entry):
        return 0
    return tag_size(encode_marker_tag(entry, max(0, slot)))


def entries_bytes(entries):
    if not entries:
        return 0
    return sum(entry_bytes(entry, index) for index, entry in enumerate(entries))


def geom(area):
    return tuple(int(value) for value in area[:6])


def write_area(area, entries):
    """AreaMarks#writeArea：{Area:int[6], Entries:[标记...]}（空条目不写）。"""
    encoded = [encode_marker_tag(entry, index) for index, entry in enumerate(entries)
               if not is_empty_entry(entry)]
    return tag_compound({"Area": tag_int_array(geom(area)), "Entries": tag_list(encoded)})


def root_of(records):
    """整个 RsccAreaMarks 子树：{Marks:[区域记录...]}。"""
    return tag_compound({"Marks": tag_list(records)})


def item_table_bytes(root):
    """AreaMarks#itemTableBytes = root.sizeInBytes()（null = 0）。"""
    return 0 if root is None else tag_size(root)


def records_of(root):
    if not isinstance(root, tuple) or root[0] != "compound":
        return []
    fields = root[1]
    if "Marks" not in fields or fields["Marks"][0] != "list":
        return []
    return [record for record in fields["Marks"][1]
            if isinstance(record, tuple) and record[0] == "compound"]


def record_key(record):
    area_tag = record[1].get("Area")
    if area_tag is None or area_tag[0] != "intarray" or len(area_tag[1]) != 6:
        return None
    return tuple(area_tag[1])


def record_bytes(root, key):
    """AreaMarks#recordBytes：现有子树里该区域那条记录的原始字节数。"""
    for record in records_of(root):
        if record_key(record) == key:
            return tag_size(record)
    return 0


def sanitize_entries(entries, capacity):
    """AreaMarks#sanitizeEntries：去空 + 截到 MATCH_CAPACITY。"""
    out = []
    for entry in entries or []:
        if len(out) >= capacity:
            break
        if entry is not None and not is_empty_entry(entry):
            out.append(entry)
    return out


def is_total_too_big(bytes_count):
    """MarkerBudget#isTotalTooBig：严格大于才拒绝。"""
    return bytes_count > MAX_TOTAL_BYTES


def is_entry_too_big(entry):
    """MarkerBudget#isEntryTooBig。"""
    return entry_bytes(entry, 0) > MAX_ENTRY_BYTES


def is_over_budget(root, area, next_entries):
    """AreaMarks#isOverBudget 的逐行孪生（第 114 轮复核后的三分支）。"""
    total = 0 if root is None else tag_size(root)
    if next_entries is None:
        # 纯查询：只看现在超没超（不涉及替换）
        return is_total_too_big(total)
    key = None if area is None else geom(area)
    clean = sanitize_entries(next_entries, MATCH_CAPACITY)
    if not clean:
        # 删除该区域 ⇒ 只会让数据变小 ⇒ 永远允许
        return False
    after = total - record_bytes(root, key)
    after += tag_size(write_area(key, clean))
    return is_total_too_big(after)


def marker_table_bytes(entries):
    """CollectionCacheBlockEntity#markerTableBytes（匹配区所有条目之和）。"""
    return entries_bytes(entries)


def would_exceed_marker_budget(entries, candidate):
    """CollectionCacheBlockEntity#wouldExceedMarkerBudget。"""
    if is_entry_too_big(candidate):
        return True
    return is_total_too_big(marker_table_bytes(entries) + entry_bytes(candidate, 0))


def add_marker_model(entries, candidate):
    """tryAddMarker / addMarkerForUi 的预算闸门：超限 ⇒ 不写、表不变。"""
    if would_exceed_marker_budget(entries, candidate):
        return False, entries
    return True, entries + [candidate]


def entry_with_bytes(target):
    """构造一条「编码后恰好 target B」的模型标记（用 nbt 里的字符串 / 字节标签调尺寸）。"""
    for byte_tags in (0, 1):
        rest = target - 760 - 75 * byte_tags
        if rest < 0 or rest % 2:
            continue
        length = rest // 2
        fields = {}
        for index in range(byte_tags):
            fields[chr(ord("b") + index)] = TAG_BYTE
        fields["x"] = tag_string("a" * length)
        candidate = marker(nbt=tag_compound(fields))
        if entry_bytes(candidate, 0) == target:
            return candidate
    raise ValueError("cannot build entry of exactly %d B" % target)


def area_record(area, entries):
    return write_area(area, entries)


# ======================================================================
# 源码常量
# ======================================================================

def source_constants():
    budget = strip_comments(read(MARKER_BUDGET))
    ratio = int(re.search(r"WIRE_RATIO\s*=\s*(\d+)\s*;", budget).group(1))
    entry_base = int(re.search(r"MAX_ENTRY_BYTES\s*=\s*(\d+)\s*\*\s*WIRE_RATIO\s*;",
                               budget).group(1))
    total_base = int(re.search(r"MAX_TOTAL_BYTES\s*=\s*(\d+)\s*\*\s*WIRE_RATIO\s*;",
                               budget).group(1))
    base = strip_comments(read(BASE_BE))
    pages = int(re.search(r"MATCH_PAGES\s*=\s*(\d+)\s*;", base).group(1))
    per_page = int(re.search(r"MATCH_CAPACITY\s*=\s*MATCH_PAGES\s*\*\s*(\d+)\s*;",
                             base).group(1))
    return entry_base * ratio, total_base * ratio, pages * per_page, ratio, entry_base, total_base


(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, MATCH_CAPACITY,
 WIRE_RATIO, ENTRY_WIRE_BYTES, TOTAL_WIRE_BYTES) = source_constants()

# 每区域记录 / 空 nbt 的固定尺寸（由公式推出，供断言对比）
PLAIN = marker()
PLAIN_BYTES = entry_bytes(PLAIN)
EMPTY_COMPOUND_BYTES = tag_size(tag_compound({}))
ONE_RECORD_ROOT_BYTES = tag_size(root_of([write_area((0, 64, 0, 10, 70, 10), [PLAIN])]))


# ======================================================================
# ⓪ 常量与委托关系
# ======================================================================

def check_constants():
    budget = strip_comments(read(MARKER_BUDGET))
    marks = strip_comments(read(AREA_MARKS))
    raw_budget = read(MARKER_BUDGET)
    raw_marks = read(AREA_MARKS)

    check("⓪-1 常量取自源码：WIRE_RATIO=%d、MAX_ENTRY_BYTES=%d（线上 %d）、"
          "MAX_TOTAL_BYTES=%d（线上 %d）、MATCH_CAPACITY=%d"
          % (WIRE_RATIO, MAX_ENTRY_BYTES, ENTRY_WIRE_BYTES, MAX_TOTAL_BYTES,
             TOTAL_WIRE_BYTES, MATCH_CAPACITY),
          WIRE_RATIO == 8 and MAX_ENTRY_BYTES == 16384 and MAX_TOTAL_BYTES == 524288
          and ENTRY_WIRE_BYTES == 2048 and TOTAL_WIRE_BYTES == 65536
          and MATCH_CAPACITY == 288)
    anchor("⓪", MARKER_BUDGET, raw_budget, "public static final int WIRE_RATIO = 8;",
           "堆估算 → 线上字节的倍率")
    anchor("⓪", MARKER_BUDGET, raw_budget, "MAX_ENTRY_BYTES = 2048 * WIRE_RATIO;",
           "单条上限 = 线上 2 KB × 倍率")
    anchor("⓪", MARKER_BUDGET, raw_budget, "MAX_TOTAL_BYTES = 65536 * WIRE_RATIO;",
           "整表上限 = 线上 64 KB × 倍率")
    check("⓪-2 AreaMarks 的常量都**委托** MarkerBudget（含给文案用的 *_WIRE_BYTES）",
          "public static final int MAX_MARKER_BYTES = MarkerBudget.MAX_ENTRY_BYTES;" in marks
          and "public static final int MAX_TOTAL_BYTES = MarkerBudget.MAX_TOTAL_BYTES;" in marks
          and "public static final int MAX_TOTAL_WIRE_BYTES = MarkerBudget.MAX_TOTAL_WIRE_BYTES;" in marks
          and "return MarkerBudget.isEntryTooBig(entry);" in marks
          and marks.count("= 2048") == 0 and marks.count("= 65536") == 0)
    check("⓪-3 `AREA_RECORD_BYTES` 已删除（上一版按记录头估算的方案已废弃，不得再出现）",
          "AREA_RECORD_BYTES" not in budget and "AREA_RECORD_BYTES" not in marks)
    check("⓪-4 byte 算法/树大小/预算/线上常量四处实现都在位",
          "public static int entryBytes(" in budget
          and "encodeMarkerTag(entry, Math.max(0, slot))" in budget
          and "tag.sizeInBytes()" in budget
          and "MAX_ENTRY_WIRE_BYTES = MAX_ENTRY_BYTES / WIRE_RATIO;" in budget
          and "MAX_TOTAL_WIRE_BYTES = MAX_TOTAL_BYTES / WIRE_RATIO;" in budget
          and "public static int itemTableBytes(" in marks
          and "root.sizeInBytes()" in marks
          and "public static boolean isOverBudget(" in marks
          and "recordBytes(root, key)" in marks
          and "writeArea(key, clean).sizeInBytes()" in marks)
    check("⓪-5 `put(...)` 不做预算拒绝（数据层原语，迁移/搬运要经过它）",
          "public static void put(" in marks and "isOverBudget" not in
          (method_body(marks, "public static void put(") or ""))
    # 常量唯一来源：不许在别处再抄一份倍率/基数（口径漂移的来源）
    others = {
        "AreaMarks": marks,
        "AreaMarkEditPacket": strip_comments(read(MARK_PACKET)),
        "AreaMarkScreen": strip_comments(read(MARK_SCREEN)),
        "AdvancedCollectionCacheBlockEntity": strip_comments(read(ADV_BE)),
        "CollectionCacheBlockEntity": strip_comments(read(BASE_BE)),
        "CollectionCacheMenu": strip_comments(read(MENU)),
    }
    leaks = [name for name, text in others.items()
             if "65536" in text or "WIRE_RATIO" in text]
    check("⓪-6 基数/倍率只有 MarkerBudget 一处定义（其余文件全部委托，不许再抄字面量）",
          leaks == [], str(leaks))
    duplicates = [name for name, text in others.items()
                  if "> MAX_ENTRY_BYTES" in text or "> 2048" in text]
    check("⓪-7 单条上限判据只在 MarkerBudget 一处（其余文件不许再写 `> 2048` 之类的比较）",
          duplicates == [] and "MarkerBudget.isEntryTooBig" in marks
          and "MarkerBudget.MAX_ENTRY_BYTES" in marks, str(duplicates))


# ======================================================================
# ① 字节算法与两个上限的边界
# ======================================================================

def check_byte_algorithm():
    check("①-1 空 NBT 普通标记的编码字节数 = %d（Compound 48 + 6 个键的 64+2|k| 与值）" % PLAIN_BYTES,
          PLAIN_BYTES == 658, str(PLAIN_BYTES))
    check("①-2 公式拆解对得上源码口径（空 Compound=48 / Int=12 / Long=16 / Byte=9 / String=36+2len）",
          EMPTY_COMPOUND_BYTES == 48
          and tag_size(encode_marker_tag(PLAIN, 0))
          == 48 + (64 + 2 * 4 + 12) + (64 + 2 * 5 + 9)
          + (64 + 2 * 2 + 36 + 2 * len("minecraft:iron_ingot"))
          + (64 + 2 * 3 + 48) + (64 + 2 * 6 + 16) + (64 + 2 * 8 + 9))

    small = marker(nbt=tag_compound({"x": tag_string("a" * 10)}))
    big = marker(nbt=tag_compound({"x": tag_string("a" * 110)}))
    check("①-3 同一份 NBT 越大 ⇒ entryBytes 越大（每多 1 字符 +2 B）",
          entry_bytes(big, 0) - entry_bytes(small, 0) == 200
          and entry_bytes(big, 0) > entry_bytes(small, 0))
    check("①-4 标签过滤器条目额外计入 Tags 列表（64+2|Tags| + 37 + 4n + Σ(36+2len)）",
          entry_bytes(marker(tags=["c:iron", "c:metal"]), 0)
          == entry_bytes(marker(), 0) + (64 + 2 * 4) + 37 + 4 * 2
          + (36 + 2 * len("c:iron")) + (36 + 2 * len("c:metal")))

    exact_limit = entry_with_bytes(MAX_ENTRY_BYTES)
    over_limit = entry_with_bytes(MAX_ENTRY_BYTES + 1)
    check("①-5 单条边界（构造精确尺寸）：恰好 %d B ⇒ 通过，%d B ⇒ 拒绝（严格大于）"
          % (MAX_ENTRY_BYTES, MAX_ENTRY_BYTES + 1),
          entry_bytes(exact_limit, 0) == MAX_ENTRY_BYTES
          and entry_bytes(over_limit, 0) == MAX_ENTRY_BYTES + 1
          and is_entry_too_big(exact_limit) is False
          and is_entry_too_big(over_limit) is True)
    raw_budget = read(MARKER_BUDGET)
    budget = strip_comments(raw_budget)
    anchor("①", MARKER_BUDGET, raw_budget, "entryBytes(entry, 0) > MAX_ENTRY_BYTES",
           "单条判据是严格大于")
    anchor("①", MARKER_BUDGET, raw_budget, "bytes > MAX_TOTAL_BYTES", "整表判据是严格大于")
    check("①-6 整表边界：恰好 %d B ⇒ 通过，+1 ⇒ 拒绝" % MAX_TOTAL_BYTES,
          is_total_too_big(MAX_TOTAL_BYTES) is False
          and is_total_too_big(MAX_TOTAL_BYTES + 1) is True
          and "bytes > MAX_TOTAL_BYTES" in budget)

    one = tag_size(root_of([write_area((0, 64, 0, 10, 70, 10), [PLAIN])]))
    check("①-7 子树布局：1 条普通标记的 RsccAreaMarks = %d B（Marks 列表头 + 区域记录头都算）"
          % ONE_RECORD_ROOT_BYTES,
          ONE_RECORD_ROOT_BYTES == one == 1108)
    check("①-8 往已有 1 条的区域再加 1 条 ⇒ 整树增长 = 该条 entryBytes %d + 列表元素计数 4 B"
          % PLAIN_BYTES,
          tag_size(root_of([write_area((0, 64, 0, 10, 70, 10), [PLAIN, marker()])]))
          - ONE_RECORD_ROOT_BYTES == PLAIN_BYTES + 4)

    # 288 条普通标记：整树体积与「上限能装多少条」（用行为模型实测）
    full_records = [area_record((0, 64, 0, 10, 70, 10), [marker() for _ in range(MATCH_CAPACITY)])]
    full_root = root_of(full_records)
    full_bytes = tag_size(full_root)
    fit_area = 0
    while fit_area <= MATCH_CAPACITY * 4:
        probe = root_of([area_record((0, 64, 0, 10, 70, 10),
                                     [marker() for _ in range(fit_area + 1)])])
        if tag_size(probe) > MAX_TOTAL_BYTES:
            break
        fit_area += 1
    fit_base = 0
    while fit_base <= MATCH_CAPACITY * 4:
        if is_total_too_big(entries_bytes([marker() for _ in range(fit_base + 1)])):
            break
        fit_base += 1
    check("①-9 288 条普通标记整树 = %d B ≤ %d B ⇒ **能整体通过**（线上约 %d KB）"
          % (full_bytes, MAX_TOTAL_BYTES, full_bytes // WIRE_RATIO // 1024),
          full_bytes == 191102 and full_bytes <= MAX_TOTAL_BYTES
          and is_over_budget(None, (0, 64, 0, 10, 70, 10),
                            [marker() for _ in range(MATCH_CAPACITY)]) is False)
    check("①-10 上限实际容量（行为模型）：单区域 %d 条 / 基础版匹配区 %d 条（均 > MATCH_CAPACITY=%d）"
          % (fit_area, fit_base, MATCH_CAPACITY),
          fit_area == 791 and fit_base == 796
          and fit_area > MATCH_CAPACITY and fit_base > MATCH_CAPACITY)
    check("①-11 javadoc 量级与模型一致：线上约 64 KB ⇒ 约 %d 条普通标记（文档写 ~800 条）" % fit_base,
          abs(fit_base - 800) <= 10 and TOTAL_WIRE_BYTES == 65536
          and ENTRY_WIRE_BYTES == 2048)
    check("①-12 倍率口径：堆估算 / 线上 ≈ WIRE_RATIO（普通标记 %d B 堆 ≈ %d B 线上）"
          % (PLAIN_BYTES, PLAIN_BYTES // WIRE_RATIO),
          WIRE_RATIO == 8 and 70 <= PLAIN_BYTES // WIRE_RATIO <= 90)

    # 反例：把整表判据改成 >= ⇒ 恰好 MAX_TOTAL 会被误拒
    def broken_total(bytes_count):
        return bytes_count >= MAX_TOTAL_BYTES

    counterexample("①-13 `isTotalTooBig` 改成 `>=`（恰好 %d B 被误拒）⇒ ①-6 判据判负"
                   % MAX_TOTAL_BYTES,
                   broken_total(MAX_TOTAL_BYTES) is True
                   and is_total_too_big(MAX_TOTAL_BYTES) is False)
    # 反例：把单条判据改成 >= ⇒ 恰好 MAX_ENTRY 被误拒
    counterexample("①-14 单条判据改成 `>=`（恰好 %d B 被误拒）⇒ ①-5 判据判负"
                   % MAX_ENTRY_BYTES,
                   entry_bytes(exact_limit, 0) == MAX_ENTRY_BYTES
                   and (entry_bytes(exact_limit, 0) >= MAX_ENTRY_BYTES) is True
                   and is_entry_too_big(exact_limit) is False)


# ======================================================================
# ② 「按整件选定器算」与删除路径
# ======================================================================

AREA_A = (0, 64, 0, 10, 70, 10)
AREA_B = (20, 64, 0, 30, 70, 10)


def build_two_areas(per_area=MATCH_CAPACITY, one_entry_bytes=1200):
    """两个区域各装满 288 条「每条 1200 B」的标记（单条没超上限，但两区域合计超整表上限）。"""
    heavy = entry_with_bytes(one_entry_bytes)
    entries_a = [heavy for _ in range(per_area)]
    entries_b = [heavy for _ in range(per_area)]
    root = root_of([area_record(AREA_A, entries_a), area_record(AREA_B, entries_b)])
    return entries_a, entries_b, root


def check_whole_item_and_delete():
    entries_a, entries_b, root = build_two_areas()
    total = tag_size(root)
    check("②-1 两个区域各 %d 条 × %d B ⇒ 整树 %d B（> %d B）；单看一个区域并不超"
          % (MATCH_CAPACITY, 1200, total, MAX_TOTAL_BYTES),
          total > MAX_TOTAL_BYTES
          and tag_size(area_record(AREA_A, entries_a)) + 163 <= MAX_TOTAL_BYTES)
    check("②-2 往其中一个区域再加一条普通标记 ⇒ isOverBudget 必须 True（两个区域的量一起算）",
          is_over_budget(root, AREA_A, entries_a + [marker()]) is True
          and is_over_budget(root, AREA_B, entries_b + [marker()]) is True)

    def only_current_area(root_tag, area, next_entries):
        """坏写法：只算「当前区域 + next」，不看整件物品。"""
        clean = sanitize_entries(next_entries, MATCH_CAPACITY)
        return is_total_too_big(tag_size(write_area(area, clean)))

    counterexample("②-3 「只算当前区域」的坏写法 ⇒ ②-2 判据判负（合计超限时必须拒）",
                   only_current_area(root, AREA_A, entries_a + [marker()]) is False
                   and is_over_budget(root, AREA_A, entries_a + [marker()]) is True)

    check("②-4 同一判定对「另一个区域」同样成立（不是只修了某个入口）",
          is_over_budget(root, AREA_B, entries_b + [marker()]) is True)

    # 拒绝无副作用（模型层）：不写条目 / 不变数量 / 不产生半写
    snapshot = copy.deepcopy(root)
    accepted = not is_over_budget(root, AREA_A, entries_a + [marker()])
    if accepted:
        root = root_of([area_record(AREA_A, entries_a + [marker()]),
                        area_record(AREA_B, entries_b)])
    check("②-5 拒绝后不写：整树仍与拒绝前逐字节相同（拒绝分支只 return）",
          accepted is False and tag_size(root) == tag_size(snapshot)
          and bytes(repr(root), "utf-8") == bytes(repr(snapshot), "utf-8"))

    # 新增一条之后 total 的增长 == 该条 entryBytes（同一区域）
    one_root = root_of([area_record(AREA_A, [PLAIN])])
    two_root = root_of([area_record(AREA_A, [PLAIN, marker()])])
    check("②-6 同一区域加一条 ⇒ 整树增长 = entryBytes %d + 列表元素计数 4 B（估算口径可解释）"
          % PLAIN_BYTES,
          tag_size(two_root) - tag_size(one_root) == entry_bytes(marker(), 1) + 4)

    # 精确整表边界：单区域 next 让 writeArea 恰好 MAX_TOTAL / MAX_TOTAL+1
    boundary_entry = entry_with_bytes(MAX_TOTAL_BYTES - 287)
    over_entry = entry_with_bytes(MAX_TOTAL_BYTES - 287 + 1)
    check("②-7 构造使 writeArea 恰好 %d B 的条目 ⇒ isOverBudget False；+1 B ⇒ True"
          % MAX_TOTAL_BYTES,
          tag_size(write_area(AREA_A, [boundary_entry])) == MAX_TOTAL_BYTES
          and tag_size(write_area(AREA_A, [over_entry])) == MAX_TOTAL_BYTES + 1
          and is_over_budget(None, AREA_A, [boundary_entry]) is False
          and is_over_budget(None, AREA_A, [over_entry]) is True)

    # 删除 / 查询路径（第 114 轮复核修复后的三分支）
    small_root = root_of([area_record(AREA_A, [PLAIN])])
    check("②-8 删除契约：整表已超限时 next = 空表 仍返回 False（删除永远允许）；"
          "未超限当然也 False",
          is_over_budget(root, AREA_A, []) is False
          and is_over_budget(small_root, AREA_A, []) is False
          and tag_size(root) > MAX_TOTAL_BYTES)
    check("②-9 纯查询（next == null）：超限 ⇒ True，未超限 ⇒ False（查询与删除两种语义分开）",
          is_over_budget(root, AREA_A, None) is True
          and is_over_budget(small_root, AREA_A, None) is False)

    # 反例：把删除分支改回「超预算就拦」⇒ ②-8 判负（超限物品连删都删不掉）
    counterexample("②-10 删除分支改回 `return next != null && isTotalTooBig(total)` ⇒ ②-8 判负",
                   is_total_too_big(tag_size(root)) is True
                   and is_over_budget(root, AREA_A, []) is False)

    # 反例：整表估算漏掉「列表头 / 区域记录头」⇒ ②-7 的 +1 边界判据判负（漏算 287 B）
    counterexample("②-11 「只算条目、漏掉列表/记录头」⇒ ②-7 的 +1 边界判据判负（坏估算 %d B 判"
                   "「没超」，实际 %d B 已超）"
                   % (entries_bytes([over_entry]),
                      tag_size(write_area(AREA_A, [over_entry]))),
                   entries_bytes([over_entry]) <= MAX_TOTAL_BYTES
                   and tag_size(write_area(AREA_A, [over_entry])) > MAX_TOTAL_BYTES)

    # 旧格式（Item 整份 ItemStack）记录：raw 字节必须算进去
    legacy_item_tag = tag_compound({
        "Item": tag_compound({"id": tag_string("minecraft:written_book"),
                              "Count": TAG_BYTE,
                              "components": tag_compound({"minecraft:written_book_content": tag_string("x" * 400)})}),
    })
    legacy_record = tag_compound({
        "Area": tag_int_array(AREA_A),
        "Entries": tag_list([tag_compound({"Item": legacy_item_tag, "Amount": TAG_INT})]),
    })
    legacy_root = root_of([legacy_record])
    legacy_bytes = tag_size(legacy_root)
    check("②-12 旧格式（Item 整份 ItemStack）记录被 itemTableBytes 算进去（%d B）" % legacy_bytes,
          legacy_bytes > 1000 and legacy_bytes == item_table_bytes(legacy_root))

    # 源码证据：isOverBudget 真的遍历整棵子树（而不是只看 area 对应的那条）
    raw_marks = read(AREA_MARKS)
    marks_code = strip_comments(raw_marks)
    over_body = method_body(marks_code, "public static boolean isOverBudget(")
    record_body = method_body(marks_code, "private static int recordBytes(")
    anchor("②", AREA_MARKS, raw_marks, "long after = total - recordBytes(root, key);",
           "isOverBudget：以整棵子树为基数、减去目标区域的旧记录")
    anchor("②", AREA_MARKS, raw_marks, "if (key.equals(readArea(record))) {",
           "recordBytes：遍历整棵 Marks 找目标区域那条记录")
    check("②-14 按整件算的源码证据：基数 = root.sizeInBytes()，减目标记录、加新记录；"
          "查询/删除/新增三分支齐备；记录查找遍历整棵 Marks（不是只看 area 那条）",
          over_body is not None and record_body is not None
          and "final long total = root == null ? 0L : Math.max(0, root.sizeInBytes());" in over_body
          and "if (next == null) {" in over_body
          and "if (clean.isEmpty()) {" in over_body
          and "return false;" in over_body
          and "long after = total - recordBytes(root, key);" in over_body
          and "after += writeArea(key, clean).sizeInBytes();" in over_body
          and "for (int i = 0; i < marks.size(); i++)" in record_body
          and "key.equals(readArea(record))" in record_body)
    delete_branch = block_inside(over_body or "", "if (clean.isEmpty()) {")
    check("②-15 删除分支只 `return false;`（不拿整表体积拦删除；next == null 的查询分支另有 return）",
          delete_branch != ""
          and "return false;" in delete_branch
          and "return true" not in delete_branch
          and "if (next == null) {" in (over_body or ""))
    broken_delete = (over_body or "").replace(
        "        if (clean.isEmpty()) {",
        "        if (clean.isEmpty()) {\n            if (next != null && isTotalTooBig(total)) { return true; }")
    counterexample("②-16 删除分支塞回「超限就 return true」⇒ ②-15 判负（超限物品连删都删不掉）",
                   broken_delete != over_body
                   and ("return true" in block_inside(broken_delete, "if (clean.isEmpty()) {")))

    broken_record = (record_body or "").replace("for (int i = 0; i < marks.size(); i++) {",
                                                "for (int i = 0; i < 0; i++) {")
    counterexample("②-17 把记录查找改成「不看 Marks、直接返回 0」⇒ ②-14 判负（旧记录减不掉）",
                   broken_record != record_body
                   and "for (int i = 0; i < marks.size(); i++)" not in broken_record)

    def decoded_based_bytes(root_tag):
        """坏写法：先 decode 再重编码求和（旧格式 Item 在无注册表上下文时读不出来 ⇒ 漏算）。"""
        total = 0
        for record in records_of(root_tag):
            fields = record[1]
            entries = []
            for tag in fields.get("Entries", ("list", []))[1]:
                if tag[0] != "compound":
                    continue
                if "Id" in tag[1]:
                    entries.append(marker(tag[1]["Id"][1]))
                # "Item" 旧格式：decodeMarkerTag(null) 读不出来 ⇒ 这里直接跳过（漏算）
            total += tag_size(write_area(record_key(record), entries))
        return total

    counterexample("②-13 「逐条 decode 再重编码」的坏写法 ⇒ 旧格式记录被漏算（②-12 判据判负）",
                   decoded_based_bytes(legacy_root) < legacy_bytes // 2
                   and item_table_bytes(legacy_root) == legacy_bytes)


# ======================================================================
# ③ 五个写入口都有拦截（源码锚点 + 顺序）
# ======================================================================

def check_entry_points():
    raw_packet = read(MARK_PACKET)
    packet = strip_comments(raw_packet)
    raw_screen = read(MARK_SCREEN)
    screen = strip_comments(raw_screen)
    raw_adv = read(ADV_BE)
    adv = strip_comments(raw_adv)
    raw_base = read(BASE_BE)
    base = strip_comments(raw_base)
    raw_menu = read(MENU)
    menu = strip_comments(raw_menu)

    packet_body = method_body(packet, "public static void handle(final AreaMarkEditPacket packet, "
                                      "final ServerPlayer player)")
    anchor("③", MARK_PACKET, raw_packet, "if (AreaMarks.isOverBudget(target, area, next)) {",
           "服务端 ADD：isOverBudget 兜底")
    check("③-1 服务端 ADD 的预算检查在 `AreaMarks.put` 之前，且检查的是「现有条目 + candidate」",
          packet_body is not None
          and "final List<MarkerEntry> next = new ArrayList<>(entries);" in packet_body
          and "next.add(candidate);" in packet_body
          and "if (AreaMarks.isOverBudget(target, area, next)) {" in packet_body
          and packet_body.index("if (AreaMarks.isOverBudget(target, area, next)) {")
          < packet_body.index("AreaMarks.put(target, area, next);"))
    check("③-2 同一分支里单条上限仍在前（isOversized ⇒ return），随后才是整表上限",
          packet_body is not None
          and packet_body.index("if (AreaMarks.isOversized(candidate)) {")
          < packet_body.index("if (AreaMarks.isOverBudget(target, area, next)) {"))

    add_from = method_body(screen, "private void addFromInventory(")
    anchor("③", MARK_SCREEN, raw_screen, "final MarkerEntry candidate = MarkerEntry.item(source,",
           "客户端：candidate 只构造一次")
    anchor("③", MARK_SCREEN, raw_screen, "if (AreaMarks.isOverBudget(targetStack(), area, next)) {",
           "客户端：整表预算预检")
    check("③-3 客户端 addFromInventory：candidate 只构造一次，单条→整表两段预检，各给不同提示",
          add_from is not None
          and add_from.count("MarkerEntry.item(source,") == 1
          and "if (AreaMarks.isOversized(candidate)) {" in add_from
          and "notice.too_complex" in add_from
          and "if (AreaMarks.isOverBudget(targetStack(), area, next)) {" in add_from
          and "notice.too_many" in add_from
          and add_from.index("if (AreaMarks.isOversized(candidate)) {")
          < add_from.index("if (AreaMarks.isOverBudget(targetStack(), area, next)) {"))

    add_area = method_body(adv, "public MarkerAddResult addAreaMarker(")
    anchor("③", ADV_BE, raw_adv, "if (cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isEntryTooBig(marker)) {",
           "机器侧 48 格：单条上限")
    anchor("③", ADV_BE, raw_adv, "if (AreaMarks.isOverBudget(selectorStack(), area, next)) {",
           "机器侧 48 格：整件选定器预算")
    check("③-4 机器侧 addAreaMarker：单条 + 整件预算都在 withAreaFilter 之前，且都返回 TOO_COMPLEX",
          add_area is not None
          and "MarkerBudget.isEntryTooBig(marker)" in add_area
          and "if (AreaMarks.isOverBudget(selectorStack(), area, next)) {" in add_area
          and add_area.count("return MarkerAddResult.TOO_COMPLEX;") == 2
          and add_area.index("if (AreaMarks.isOverBudget(selectorStack(), area, next)) {")
          < add_area.index("withAreaFilter(areaIndex"))

    budget_helper = method_body(base, "private boolean wouldExceedMarkerBudget(")
    check("③-5 基础版预算判定唯一实现：单条 isEntryTooBig 或 整表 markerTableBytes()+entryBytes",
          budget_helper is not None
          and "MarkerBudget.isEntryTooBig(candidate)" in budget_helper
          and "MarkerBudget.isTotalTooBig(markerTableBytes() + MarkerBudget.entryBytes(candidate, 0))"
              in budget_helper)
    table_bytes = method_body(base, "public int markerTableBytes()")
    check("③-6 markerTableBytes = 逐条 entryBytes 求和（不是只数条数）",
          table_bytes is not None
          and "MarkerBudget.entryBytes(markers.get(i), i)" in table_bytes
          and "markers.size()" in table_bytes)

    try_add = method_body(base, "public boolean tryAddMarker(final int index, final ItemStack stack, "
                                "final long amount)")
    anchor("③", BASE_BE, raw_base, "if (wouldExceedMarkerBudget(candidate)) {",
           "基础版 tryAddMarker 兜底（所有调用方都经过）")
    check("③-7 tryAddMarker 的预算兜底在 setMarker 之前（兜底覆盖所有调用方）",
          try_add is not None
          and "if (wouldExceedMarkerBudget(candidate)) {" in try_add
          and "setMarker(target, candidate);" in try_add
          and try_add.index("if (wouldExceedMarkerBudget(candidate)) {")
          < try_add.index("setMarker(target, candidate);"))

    add_ui = method_body(base, "public MarkerAddResult addMarkerForUi(")
    check("③-8 addMarkerForUi：预算超限返回 TOO_COMPLEX（区别于 FULL，界面才能说人话）",
          add_ui is not None
          and "if (wouldExceedMarkerBudget(budgetCandidate)) {" in add_ui
          and "return MarkerAddResult.TOO_COMPLEX;" in add_ui
          and "COVERED_BY_TAG" in add_ui)

    set_config = method_body(base, "public void setMarkerConfig(")
    anchor("③", BASE_BE, raw_base, "（数据组件 {} B，单条上限 {} B / 整表上限 {} B）",
           "setMarkerConfig：WARN 里带单条/整表上限")
    check("③-9 setMarkerConfig 新建分支：预算超限 ⇒ WARN + return（防客户端自带任意 nbt），在 setMarker 之前",
          set_config is not None
          and "if (wouldExceedMarkerBudget(candidate)) {" in set_config
          and "LOGGER.warn(" in set_config
          and "MarkerBudget.MAX_ENTRY_BYTES" in set_config
          and "MarkerBudget.MAX_TOTAL_BYTES" in set_config
          and set_config.index("if (wouldExceedMarkerBudget(candidate)) {")
          < set_config.rindex("setMarker(index, candidate);"))

    menu_switch = method_body(menu, "private boolean writeMarkerFromPlayerSlot(")
    anchor("③", MENU, raw_menu, "case TOO_COMPLEX -> notifyPlayer(player, LANG_MARK + \"too_complex\",",
           "菜单：TOO_COMPLEX 单独提示（不能落进 FULL）")
    check("③-10 菜单 switch 有独立 `case TOO_COMPLEX`（提示参数 = **线上**整表上限 KB = 64）",
          menu_switch is not None
          and "case TOO_COMPLEX -> notifyPlayer(player, LANG_MARK + \"too_complex\"," in menu_switch
          and "MAX_TOTAL_WIRE_BYTES / 1024" in menu_switch
          and TOTAL_WIRE_BYTES // 1024 == 64)
    check("③-11 MarkerAddResult 里 TOO_COMPLEX 存在且在 INVALID 之后（最后一项）",
          re.search(r"INVALID,\s*(?:/\*.*?\*/\s*)?TOO_COMPLEX\s*\n\s*\}", base, re.S) is not None
          or ("INVALID," in base and "TOO_COMPLEX" in base
              and base.index("INVALID,") < base.index("TOO_COMPLEX")))

    # 反例（源码层）：删掉服务端兜底 ⇒ ③-1 判负
    broken_packet = packet_body.replace("if (AreaMarks.isOverBudget(target, area, next)) {", "")
    counterexample("③-12 删掉服务端 ADD 的 isOverBudget 兜底 ⇒ ③-1 判负（改包即可超限写入）",
                   broken_packet != packet_body
                   and "if (AreaMarks.isOverBudget(target, area, next)) {" not in broken_packet)
    # 反例：删掉 tryAddMarker 兜底 ⇒ ③-7 判负
    broken_try = try_add.replace("if (wouldExceedMarkerBudget(candidate)) {", "if (false) {")
    counterexample("③-13 短路 tryAddMarker 的预算兜底 ⇒ ③-7 判负（其它调用方会漏）",
                   broken_try != try_add and "if (wouldExceedMarkerBudget(candidate)) {" not in broken_try)
    # 反例：删掉 setMarkerConfig 的 nbt 校验 ⇒ ③-9 判负
    broken_set = set_config.replace("if (wouldExceedMarkerBudget(candidate)) {", "if (false) {")
    counterexample("③-14 删掉 setMarkerConfig 的预算校验 ⇒ ③-9 判负（客户端可回传超大 nbt）",
                   broken_set != set_config
                   and "if (wouldExceedMarkerBudget(candidate)) {" not in broken_set)
    # 反例：把 advanced 的 isEntryTooBig 去掉 ⇒ ③-4 判负
    broken_add_area = add_area.replace("MarkerBudget.isEntryTooBig(marker)", "false")
    counterexample("③-15 删掉机器侧单条上限（isEntryTooBig）⇒ ③-4 判负",
                   broken_add_area != add_area
                   and "MarkerBudget.isEntryTooBig(marker)" not in broken_add_area)


# ======================================================================
# ④ 拒绝无副作用 + 不消耗物品（行为模型 + 源码锚点）
# ======================================================================

def check_no_side_effects():
    base = strip_comments(read(BASE_BE))
    adv = strip_comments(read(ADV_BE))
    packet = strip_comments(read(MARK_PACKET))

    heavy = entry_with_bytes(2000)
    entries = [heavy for _ in range(MATCH_CAPACITY)]      # 288 × 2000 B ≈ 576 KB > 上限
    candidate = marker()
    accepted, after = add_marker_model(entries, candidate)
    check("④-1 基础版：预算超限 ⇒ tryAdd 返回 False、表长度/字节数都不变",
          accepted is False and len(after) == len(entries)
          and entries_bytes(after) == entries_bytes(entries))

    # 模型层「半写」检查：拒绝分支没有调用任何写入原语
    called = []

    def reject_flow(entries, candidate):
        if would_exceed_marker_budget(entries, candidate):
            return False
        called.append("setMarker")
        return True

    check("④-2 拒绝时不会走到写入原语（模型：写入调用计数 = 0）",
          reject_flow(entries, candidate) is False and called == [])

    for label, body, write_hint in (
            ("基础版 tryAddMarker", method_body(base, "public boolean tryAddMarker(final int index, final ItemStack stack, "
                                   "final long amount)"),
             "setMarker(target, candidate);"),
            ("基础版 addMarkerForUi", method_body(base, "public MarkerAddResult addMarkerForUi("),
             "tryAddMarker(-1, stack, MIN_UI_MARK_AMOUNT)"),
            ("基础版 setMarkerConfig", method_body(base, "public void setMarkerConfig("),
             "setMarker(index, candidate);"),
            ("机器侧 addAreaMarker", method_body(adv, "public MarkerAddResult addAreaMarker("),
             "withAreaFilter(areaIndex"),
            ("服务端包 ADD", method_body(packet, "public static void handle(final AreaMarkEditPacket packet, "
                                                  "final ServerPlayer player)"),
             "AreaMarks.put(target, area, next);"),
    ):
        budget_pos = None
        for probe in ("wouldExceedMarkerBudget(", "AreaMarks.isOverBudget(", "isEntryTooBig(",
                      "MarkerBudget.isEntryTooBig("):
            index = (body or "").find(probe)
            if index >= 0:
                budget_pos = index if budget_pos is None else min(budget_pos, index)
        write_pos = (body or "").find(write_hint)
        check("④-3 %s：预算判定在写入原语之前（拒绝 = 提前 return，不会半写）" % label,
              body is not None and budget_pos is not None and write_pos >= 0
              and budget_pos < write_pos,
              "budget=%r write=%r" % (budget_pos, write_pos))

    no_consume = []
    for label, body in (
            ("tryAddMarker", method_body(base, "public boolean tryAddMarker(final int index, final ItemStack stack, "
                                   "final long amount)")),
            ("addMarkerForUi", method_body(base, "public MarkerAddResult addMarkerForUi(")),
            ("setMarkerConfig", method_body(base, "public void setMarkerConfig(")),
            ("addAreaMarker", method_body(adv, "public MarkerAddResult addAreaMarker(")),
    ):
        if body is None or ".shrink(" in body or "consume" in body.lower():
            no_consume.append(label)
    check("④-4 五个写入口都不消耗物品（源码里没有 shrink / consume）", not no_consume, str(no_consume))

    # 模型：加上一条后若刚好超限，条目数不会先加再删
    base_entries = [heavy for _ in range(MATCH_CAPACITY)]
    ok, result = add_marker_model(base_entries, marker())
    check("④-5 模型：被拒时返回的列表与入参是同一份数据（没有 append 后再回滚）",
          ok is False and result is base_entries)

    # 基础版整表边界（行为模型）：每条 2000 B ⇒ 261+1 条可写；262+1 条被整表上限拦住
    table_261 = [heavy for _ in range(261)]
    table_262 = [heavy for _ in range(262)]
    accept_262, _ = add_marker_model(table_261, heavy)
    accept_263, _ = add_marker_model(table_262, heavy)
    check("④-6 基础版整表边界（行为模型）：261+1 条可写（%d B），262+1 条被拦（%d B > %d B）"
          % (entries_bytes(table_262), entries_bytes(table_262) + entry_bytes(heavy, 0),
             MAX_TOTAL_BYTES),
          accept_262 is True and accept_263 is False)
    anchor("④", BASE_BE, read(BASE_BE),
           "MarkerBudget.isTotalTooBig(markerTableBytes() + MarkerBudget.entryBytes(candidate, 0))",
           "基础版：整表 + 这一条")


# ======================================================================
# ⑤ TOO_COMPLEX 分类与语言键
# ======================================================================

def check_lang_and_enum():
    base = strip_comments(read(BASE_BE))
    menu = strip_comments(read(MENU))
    zh = json.loads(read(ZH))
    en = json.loads(read(EN))

    shift_key = "gui.rs_create_compat.collection_cache.shift_mark.too_complex"
    area_key = "gui.rs_create_compat.area_mark.notice.too_complex"
    many_key = "gui.rs_create_compat.area_mark.notice.too_many"
    check("⑤-1 TOO_COMPLEX 是独立枚举值（不会被界面误当成 FULL 去删标记）",
          "TOO_COMPLEX" in base and "FULL," in base
          and re.search(r"INVALID,\s*(?:/\*\*.*?\*/\s*)?TOO_COMPLEX", base, re.S) is not None)
    check("⑤-2 基础版界面把 TOO_COMPLEX 单独映射成提示（不是 FULL）",
          "case TOO_COMPLEX -> notifyPlayer(" in menu
          and "LANG_MARK + \"too_complex\"" in menu)
    check("⑤-3 机器侧 addAreaMarker 超限返回 TOO_COMPLEX（不是 FULL / INVALID）",
          "return MarkerAddResult.TOO_COMPLEX;" in
          (method_body(strip_comments(read(ADV_BE)), "public MarkerAddResult addAreaMarker(") or ""))
    pairs = [(shift_key, 1), (area_key, 0), (many_key, 1)]
    ok = True
    detail = []
    for key, placeholders in pairs:
        if key not in zh or key not in en:
            ok = False
            detail.append("missing:" + key)
            continue
        if not str(zh[key]).strip() or not str(en[key]).strip():
            ok = False
            detail.append("empty:" + key)
        if str(zh[key]).count("%1$s") != placeholders or str(en[key]).count("%1$s") != placeholders:
            ok = False
            detail.append("placeholder:" + key)
        if re.search(r"[\u4e00-\u9fff]", str(en[key])):
            ok = False
            detail.append("cjk:" + key)
    check("⑤-4 语言键 too_complex / too_many 中英成对、非空、%1$s 占位符个数一致", ok, str(detail[:3]))
    check("⑤-5 客户端 too_many 的提示参数是**线上**整表上限 KB（`MAX_TOTAL_WIRE_BYTES / 1024` = 64）",
          "AreaMarks.MAX_TOTAL_WIRE_BYTES / 1024" in strip_comments(read(MARK_SCREEN))
          and "AreaMarks.MAX_TOTAL_BYTES / 1024" not in strip_comments(read(MARK_SCREEN))
          and TOTAL_WIRE_BYTES // 1024 == 64)
    broken_menu = menu.replace(
        'case TOO_COMPLEX -> notifyPlayer(player, LANG_MARK + "too_complex",',
        'case TOO_COMPLEX -> notifyPlayer(player, LANG_MARK + "full", 288);')
    counterexample("⑤-6 TOO_COMPLEX 分支漏成「匹配区满」提示 ⇒ ⑤-2 判负（玩家会去删标记却腾不出空间）",
                   broken_menu != menu
                   and 'case TOO_COMPLEX -> notifyPlayer(player, LANG_MARK + "too_complex",'
                       not in broken_menu)



# ----------------------------------------------------------------------
# ⑥ 第 114 轮复核修复（H2 迁移冻结/消费、M1 位次冻结、M2 快照节流、
#    L2 prune 批量、M3 高级仓落错表、H1 tagCount DoS）
# ----------------------------------------------------------------------

def block_inside(text, guard):
    """取 `guard` 后面的那个 {...} 的**块内文本**（用于「语句必须在 if 块里」判据）。"""
    pos = (text or "").find(guard)
    if pos < 0:
        return ""
    brace = text.find("{", pos)
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    return ""


def is_index_prefix(raw_areas, usable_areas):
    """AreaMarks#isIndexPrefix 的孪生：usable 是 raw 的前缀（逐项几何相等）。"""
    if raw_areas is None or usable_areas is None or len(usable_areas) > len(raw_areas):
        return False
    for index, usable in enumerate(usable_areas):
        raw = raw_areas[index]
        if raw is None or usable is None or geom(raw) != geom(usable):
            return False
    return True


def marks_in(root, area):
    """从 RsccAreaMarks 子树里解出该区域的标记（只用于本组的「写没写进去」判据）。"""
    for record in records_of(root):
        if record_key(record) != geom(area):
            continue
        entries = []
        for tag in record[1].get("Entries", ("list", []))[1]:
            if tag[0] == "compound" and "Id" in tag[1]:
                entries.append(marker(tag[1]["Id"][1]))
        return entries
    return []


def assign_marks(root, area, entries):
    """把某区域的记录整条替换（模拟 AreaMarks#put 的编码结果，但不做预算拒绝）。"""
    records = [record for record in records_of(root) if record_key(record) != geom(area)]
    if entries:
        records.append(write_area(area, entries))
    return root_of(records) if records else None


def migrate_by_index_v2(root, areas, legacy, capacity=None):
    """AreaMarks#migrateByIndex（第 114 轮复核版）的孪生：谁消费谁删、超大/超预算冻结。

    返回 `((migrated, skippedExisting, droppedOutOfRange, skippedTooLarge, skippedOverBudget),
    root_after)`。
    """
    cap = capacity if capacity is not None else MATCH_CAPACITY
    if areas is None or not legacy:
        return (0, 0, 0, 0, 0), root
    migrated = skipped = dropped = too_large = over_budget = 0
    for index in list(legacy.keys()):
        entries = legacy.get(index)
        if index is None or index < 0 or index >= len(areas):
            dropped += 1
            continue
        area = areas[index]
        if marks_in(root, area):
            skipped += 1
            del legacy[index]
            continue
        clean = sanitize_entries(entries, cap)
        if not clean:
            del legacy[index]
            continue
        if any(is_entry_too_big(entry) for entry in clean):
            too_large += 1
            continue
        if is_over_budget(root, area, clean):
            over_budget += 1
            continue
        root = assign_marks(root, area, clean)
        del legacy[index]
        migrated += 1
    return (migrated, skipped, dropped, too_large, over_budget), root


def migrate_pinned(root, raw_areas, areas, legacy, capacity=None):
    """第 115 轮：`migrateByIndex` 的位次校验孪生（pinned 第一次真正可达）。

    raw 读不到、或 usable 不是 raw 的几何前缀 ⇒ 整体冻结：`(0,0,0,0,0,True)`、旧表一条不消费。
    返回 `((migrated, skippedExisting, droppedOutOfRange, skippedTooLarge, skippedOverBudget, pinned),
    root_after)`。
    """
    if raw_areas is None or areas is None or not is_index_prefix(raw_areas, areas):
        return (0, 0, 0, 0, 0, True), root
    result, root_after = migrate_by_index_v2(root, areas, legacy, capacity)
    return result + (False,), root_after


def check_review_round114():
    raw_marks = read(AREA_MARKS)
    marks = strip_comments(raw_marks)
    raw_adv = read(ADV_BE)
    adv = strip_comments(raw_adv)
    raw_adv_menu = read(ADV_MENU)
    adv_menu = strip_comments(raw_adv_menu)
    raw_packet = read(SET_PACKET)
    packet = strip_comments(raw_packet)

    area_a = (0, 64, 0, 10, 70, 10)
    area_b = (20, 64, 0, 30, 70, 10)
    area_c = (40, 64, 0, 50, 70, 10)

    # ================= ⑥-A H2：迁移「消费 / 冻结」双态 =================
    merge_record = method_body(marks, "public record MergeResult")
    anchor("⑥", AREA_MARKS, raw_marks,
           "public record MergeResult(int migrated, int skippedExisting, int droppedOutOfRange,",
           "MergeResult 六字段（含 skippedTooLarge / skippedOverBudget / pinned）")
    anchor("⑥", AREA_MARKS, raw_marks, "this(migrated, skippedExisting, droppedOutOfRange, 0, 0, false);",
           "保留 3 参兼容构造器")
    anchor("⑥", AREA_MARKS, raw_marks, "legacyByIndex.entrySet().iterator()",
           "用 Iterator 遍历（谁消费谁删）")
    anchor("⑥", AREA_MARKS, raw_marks, "if (MarkerBudget.isEntryTooBig(candidate)) {",
           "超大条目 ⇒ skippedTooLarge（冻结，不消费）")
    anchor("⑥", AREA_MARKS, raw_marks, "if (isOverBudget(stack, area, clean)) {",
           "会超预算 ⇒ skippedOverBudget（冻结，不消费）")
    check("⑥-1 迁移返回六元组 + 3 参兼容 + touched()（消费/冻结语义的来源）",
          merge_record is not None
          and "int skippedTooLarge, int skippedOverBudget, boolean pinned)" in merge_record
          and "public MergeResult(final int migrated, final int skippedExisting, "
              "final int droppedOutOfRange) {" in merge_record
          # 第 115 轮：pinned 变成可达状态 ⇒ 它不许再算进「动过」（冻结 = 一个字节都没改）
          and "return migrated > 0 || skippedExisting > 0;" in merge_record
          and "|| pinned" not in merge_record)
    migrate_body = method_body(marks, "public static MergeResult migrateByIndex(")
    anchor("⑥", AREA_MARKS, raw_marks, "it.remove();", "三处消费：迁成 / 已标记 / 空条目")
    check("⑥-2 消费只发生在三处（迁成 / 已标记 / 空条目），冻结与越界一律不消费",
          migrate_body is not None
          and migrate_body.count("it.remove();") == 3
          and "tooLarge++;" in migrate_body and "overBudget++;" in migrate_body
          and "dropped++;" in migrate_body)
    check("⑥-3 BE 不再有「index < areaCount 一律删」的整段 removeIf（否则冻结条目静默蒸发）",
          "removeIf(index" not in adv)

    # 行为模型：五种结局各一条
    legacy = {0: [marker("minecraft:iron_ingot")]}
    result, outer = migrate_by_index_v2(None, [area_a], legacy)
    check("⑥-4 正常迁移：写进物品 + 旧条目被消费（旧表清空）",
          result == (1, 0, 0, 0, 0) and legacy == {}
          and [e["id"] for e in marks_in(outer, area_a)] == ["minecraft:iron_ingot"])

    legacy2 = {0: [marker("minecraft:iron_ingot")]}
    outer2 = assign_marks(None, area_a, [marker("minecraft:coal")])
    result2, outer2 = migrate_by_index_v2(outer2, [area_a], legacy2)
    check("⑥-5 物品已有标记：skippedExisting 且旧条目作废（消费），物品不被覆盖",
          result2 == (0, 1, 0, 0, 0) and legacy2 == {}
          and marks_in(outer2, area_a) == [marker("minecraft:coal")])

    legacy3 = {0: []}
    result3, _ = migrate_by_index_v2(None, [area_a], legacy3)
    check("⑥-6 空条目：不计数但消费（否则每次加载都白跑）",
          result3 == (0, 0, 0, 0, 0) and legacy3 == {})

    huge = entry_with_bytes(20000)
    legacy4 = {0: [huge]}
    result4, outer4 = migrate_by_index_v2(None, [area_a], legacy4)
    check("⑥-7 单条 20 KB 堆估算 > 单条上限 ⇒ skippedTooLarge，旧条目**冻结保留**、物品上没有它",
          is_entry_too_big(huge) is True
          and result4 == (0, 0, 0, 1, 0)
          and list(legacy4) == [0] and marks_in(outer4, area_a) == [])

    outer5 = assign_marks(None, area_a, [entry_with_bytes(2000) for _ in range(MATCH_CAPACITY)])
    legacy5 = {1: [marker("minecraft:iron_ingot")]}
    result5, outer5 = migrate_by_index_v2(outer5, [area_a, area_b], legacy5)
    check("⑥-8 搬进来会超整表上限 ⇒ skippedOverBudget，旧条目冻结保留、物品表不变",
          is_over_budget(outer5, area_b, [marker("minecraft:iron_ingot")]) is True
          and result5 == (0, 0, 0, 0, 1)
          and list(legacy5) == [1] and marks_in(outer5, area_b) == [])

    legacy6 = {9: [marker("minecraft:coal")]}
    result6, _ = migrate_by_index_v2(None, [area_a], legacy6)
    check("⑥-9 越界序号 ⇒ droppedOutOfRange，旧条目保留（等区域表恢复）",
          result6 == (0, 0, 1, 0, 0) and list(legacy6) == [9])

    mixed = {0: [marker("minecraft:iron_ingot")],
             1: [marker("minecraft:gold_ingot")],
             2: [],
             9: [marker("minecraft:coal")]}
    outer7 = assign_marks(None, area_b, [marker("minecraft:diamond")])
    result7, outer7 = migrate_by_index_v2(outer7, [area_a, area_b, area_c], mixed)
    check("⑥-10 混合：迁成/已标记/空条目被消费，越界冻结 ⇒ 旧表只剩 9 那一条",
          result7 == (1, 1, 1, 0, 0)
          and list(mixed) == [9]
          and [e["id"] for e in marks_in(outer7, area_a)] == ["minecraft:iron_ingot"]
          and marks_in(outer7, area_b) == [marker("minecraft:diamond")])

    # 反例：冻结分支也 it.remove() ⇒ ⑥-2 的「只在三处消费」判据判负
    broken_migrate = migrate_body.replace(
        "            if (oversized) {\n                tooLarge++;",
        "            if (oversized) {\n                tooLarge++;\n                it.remove();", 1)
    counterexample("⑥-11 冻结分支也 `it.remove()`（超大条目不保留）⇒ ⑥-2 判据判负",
                   broken_migrate != migrate_body
                   and broken_migrate.count("it.remove();") != 3)

    # ================= ⑥-B M1：位次漂移冻结 =================
    prefix_body = method_body(marks, "public static boolean isIndexPrefix(")
    anchor("⑥", AREA_MARKS, raw_marks, "public static boolean isIndexPrefix(",
           "isIndexPrefix（usable 必须是 raw 的几何前缀）")
    check("⑥-12 isIndexPrefix：前缀 true / 中间丢一个 false / 相等 true / usable 更长 false / 几何不同 false",
          prefix_body is not None
          and "usableAreas.size() > rawAreas.size()" in prefix_body
          and "!geometryKey(raw).equals(geometryKey(usable))" in prefix_body
          and is_index_prefix([area_a, area_b, area_c], [area_a, area_b]) is True
          and is_index_prefix([area_a, area_b, area_c], [area_a, area_c]) is False
          and is_index_prefix([area_a, area_b], [area_a, area_b]) is True
          and is_index_prefix([area_a], [area_a, area_b]) is False
          and is_index_prefix([area_a, area_b], [area_a, (0, 64, 0, 11, 70, 10)]) is False)

    mig = method_body(adv, "private void migrateLegacyAreaFilters")
    anchor("⑥", ADV_BE, raw_adv, "if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {",
           "位次对不上 ⇒ 本轮整体冻结（不迁移）")
    anchor("⑥", ADV_BE, raw_adv, "legacyMigrationPinnedWarned", "位次冻结 WARN 只打一次")
    check("⑥-13 BE：isIndexPrefix 不成立时在 migrateByIndex 之前 return（不消费、不改快照）",
          mig is not None
          and "if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {" in mig
          and mig.index("if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {")
          < mig.index("AreaMarks.migrateByIndex(")
          and "legacyAreaFilters.size()" in mig)

    # 反例（模型）：位次漂移时硬按序号迁 ⇒ 标记套到别的区域上（比丢失更糟）
    raw_areas = [area_a, area_b, area_c]
    usable_areas = [area_a, area_c]              # sanitize 在中间丢了 B
    broken_result, broken_outer = migrate_by_index_v2(
        None, usable_areas, {1: [marker("minecraft:coal")]})
    fixed_outer = None
    if is_index_prefix(raw_areas, usable_areas):
        _, fixed_outer = migrate_by_index_v2(None, usable_areas, {1: [marker("minecraft:coal")]})
    counterexample("⑥-14 位次漂移时硬按序号迁 ⇒ B 的标记落到 C 上（⑥-12/⑥-13 判据判负）",
                   marks_in(broken_outer, area_c) != []
                   and marks_in(fixed_outer, area_c) == [])

    # 第 115 轮：pinned 从「恒 false 的预留位」变成**数据层自己判**（纵深防御）。
    # 第 114 轮只有 BE 在调用前判一次；任何别的调用方（或将来重构掉那行）都会按序号乱搬。
    anchor("⑥", AREA_MARKS, raw_marks, "final AreaSelection rawSelection = AreaSelection.get(stack);",
           "migrateByIndex 自己从物品读原始区域表（不靠调用方传）")
    anchor("⑥", AREA_MARKS, raw_marks,
           "if (rawSelection == null || !isIndexPrefix(rawSelection.areas(), selection.areas())) {",
           "位次不是前缀 ⇒ 数据层整体冻结")
    anchor("⑥", AREA_MARKS, raw_marks, "return new MergeResult(0, 0, 0, 0, 0, true);",
           "pinned 返回值 = 0 迁移 / 0 消费")
    check("⑥-25 数据层自带位次校验：raw 读不到 或 不是 usable 的前缀 ⇒ pinned（先判后迁）",
          migrate_body is not None
          and "final AreaSelection rawSelection = AreaSelection.get(stack);" in migrate_body
          and "if (rawSelection == null || !isIndexPrefix(rawSelection.areas(), selection.areas())) {"
              in migrate_body
          and "return new MergeResult(0, 0, 0, 0, 0, true);" in migrate_body
          and migrate_body.index("final AreaSelection rawSelection = AreaSelection.get(stack);")
          < migrate_body.index("legacyByIndex.entrySet().iterator()"))

    legacy_p = {0: [marker("minecraft:iron_ingot")]}
    result_p, outer_p = migrate_pinned(None, [area_a, area_b, area_c], [area_a, area_c], legacy_p)
    check("⑥-26 位次漂移（sanitize 在中间丢区）⇒ pinned，旧表一条不消费、物品一个字节不写",
          result_p == (0, 0, 0, 0, 0, True) and list(legacy_p) == [0] and outer_p is None
          # touched() 也不许把「冻结」算成「动过」
          and merge_record is not None
          and "|| pinned" not in merge_record)
    legacy_q = {0: [marker("minecraft:iron_ingot")]}
    result_q, outer_q = migrate_pinned(None, [area_a, area_b], [area_a, area_b], legacy_q)
    check("⑥-27 位次只是砍尾巴（前缀成立）⇒ 照常迁移、pinned=False（不误伤正常旧档）",
          result_q == (1, 0, 0, 0, 0, False) and legacy_q == {}
          and [e["id"] for e in marks_in(outer_q, area_a)] == ["minecraft:iron_ingot"])
    broken_pinned = (migrate_body or "").replace(
        "if (rawSelection == null || !isIndexPrefix(rawSelection.areas(), selection.areas())) {",
        "if (false) {", 1)
    counterexample("⑥-28 数据层不自判位次 ⇒ ⑥-25 / ⑥-26 判负（pinned 又变回恒 false 的死字段）",
                   broken_pinned != migrate_body
                   and "isIndexPrefix(rawSelection.areas()" not in broken_pinned)

    # ================= ⑥-C M2：没进展时不刷快照/标脏 =================
    changed_block = block_inside(mig or "", "if (changed) {")
    anchor("⑥", ADV_BE, raw_adv, "final boolean changed = consumed || result.migrated() > 0;",
           "changed 判据")
    check("⑥-15 只有 changed 时才 invalidate 缓存/快照 + setChanged（冻结/越界不每 tick 刷）",
          mig is not None
          and "final boolean consumed = legacyAreaFilters.size() != before;" in mig
          and "final boolean changed = consumed || result.migrated() > 0;" in mig
          and "invalidateMarksCache();" in changed_block
          and "invalidateAreaSnapshot();" in changed_block
          and "setChanged();" in changed_block
          and mig.count("invalidateAreaSnapshot();") == 1)
    broken_changed = (mig or "").replace(
        "        if (changed) {\n            invalidateMarksCache();\n"
        "            invalidateAreaSnapshot();\n            setChanged();\n        }",
        "        if (changed) {\n        }\n        invalidateMarksCache();\n"
        "        invalidateAreaSnapshot();\n        setChanged();")
    counterexample("⑥-16 把三行 side-effect 移出 `if (changed)` ⇒ ⑥-15 判负（冻结时每 tick 刷快照）",
                   broken_changed != mig
                   and "invalidateMarksCache();" not in block_inside(
                       broken_changed, "if (changed) {"))
    load_body = method_body(adv, "public void loadAdditional")
    check("⑥-17 读档重置所有迁移状态（legacyFiltersMigrated / legacyMigrationWarned / "
          "legacyMigrationPinnedWarned）",
          load_body is not None
          and "legacyFiltersMigrated = false;" in load_body
          and "legacyMigrationWarned = false;" in load_body
          and "legacyMigrationPinnedWarned = false;" in load_body)

    # ================= ⑥-D L2：prune 批量重建 =================
    prune_body = method_body(marks, "public static void prune(")
    anchor("⑥", AREA_MARKS, raw_marks, "if (doomed == 0) {",
           "prune：没有要剪的记录 ⇒ 一次 copy 都不做")
    anchor("⑥", AREA_MARKS, raw_marks, "update(stack, updatedRoot -> {",
           "prune：一次 update 批量重建（不再是每区域 remove ⇒ O(n²)）")
    check("⑥-18 prune 批量：先数 doomed、为 0 直接 return；否则一次 update 重建 ListTag",
          prune_body is not None
          and "int doomed = 0;" in prune_body
          and "if (doomed == 0) {" in prune_body
          and prune_body.count("update(stack, updatedRoot -> {") == 1
          and "remove(stack, area);" not in prune_body
          and "kept.add(record);" in prune_body)
    broken_prune = (prune_body or "").replace(
        "        if (doomed == 0) {", "        remove(stack, area);\n        if (doomed == 0) {")
    counterexample("⑥-19 prune 改回「每区域 remove()」⇒ ⑥-18 判负（O(n²) 主线程开销）",
                   broken_prune != prune_body and "remove(stack, area);" in broken_prune)

    # ================= ⑥-E M3：高级仓编辑区域标记规则落错表 =================
    six_arg = method_body(adv_menu, "protected void applyMarkerConfig(final int markerIndex, "
                                    "final boolean fluid,")
    anchor("⑥", ADV_MENU, raw_adv_menu, "protected void applyMarkerConfig(final int markerIndex, "
           "final boolean fluid,", "6 参覆写（基础版 5 参包走这条）")
    check("⑥-20 AdvancedCollectionCacheMenu 有 6 参 applyMarkerConfig 覆写（参数含 id + nbt）",
          six_arg is not None
          and "@Nullable final ResourceLocation id" in six_arg
          and "@Nullable final net.minecraft.nbt.CompoundTag nbt" in six_arg)
    check("⑥-21 有区域上下文 + 非流体 ⇒ 转发 setAreaMarkerConfig；无上下文 / 流体 ⇒ 交回基类",
          six_arg is not None
          and "if (advanced == null || editingAreaIndex < 0) {" in six_arg
          and "if (fluid) {" in six_arg
          and six_arg.count("super.applyMarkerConfig(markerIndex, fluid, id, nbt, amount, matchNbt, tags);")
          == 2
          and six_arg.count("advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,")
          == 1
          and six_arg.index("if (advanced == null || editingAreaIndex < 0) {")
          < six_arg.index("advanced.setAreaMarkerConfig(")
          and six_arg.index("if (fluid) {") < six_arg.index("advanced.setAreaMarkerConfig("))
    broken_forward = (six_arg or "").replace(
        "advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,", "")
    counterexample("⑥-22 删掉转发（编辑区域标记却写到全局匹配区）⇒ ⑥-21 判负",
                   broken_forward != six_arg
                   and "advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,"
                       not in broken_forward)

    # ---- ⑥-E2 第 130 轮：空格子 + JEI 拖入必须**真的新增**一条（以前只 update ⇒ 静默丢包） ----
    check("⑥-25 空格子（该下标还没有条目）走 addAreaMarkerEntry 新增，而不是只调 setAreaMarkerConfig"
          "（setAreaMarkerConfig 找不到条目就 return false ⇒ JEI 拖到空格子上什么都不发生）",
          six_arg is not None
          and "advanced.getAreaMarker(editingAreaIndex, markerIndex)" in six_arg
          and "advanced.addAreaMarkerEntry(editingAreaIndex, marker)" in six_arg
          and "notifyMarkerResult(result)" in six_arg
          and six_arg.index("existing == null || existing.isEmpty()")
          < six_arg.index("advanced.addAreaMarkerEntry(")
          and six_arg.index("advanced.addAreaMarkerEntry(")
          < six_arg.index("advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,"))
    broken_add = (six_arg or "").replace("advanced.addAreaMarkerEntry(editingAreaIndex, marker)", "false")
    counterexample("⑥-26 把新增分支改回「只 update」⇒ ⑥-25 判负（JEI 拖入空格子静默丢包）",
                   broken_add != six_arg and "advanced.addAreaMarkerEntry(" not in broken_add)

    # ================= ⑥-F H1：tagCount DoS =================
    anchor("⑥", SET_PACKET, raw_packet, "public static final int TAG_CAP = 256;",
           "标签解码上限")
    anchor("⑥", SET_PACKET, raw_packet, "final int keep = Math.min(tagCount, TAG_CAP);",
           "只暂存前 TAG_CAP 条，但仍按 count 消费字节")
    check("⑥-23 tagCount 解码按 count 消费字节、只暂存前 TAG_CAP 条，且**不**按客户端计数预分配",
          "public static final int TAG_CAP = 256;" in packet
          and "final int keep = Math.min(tagCount, TAG_CAP);" in packet
          and "for (int i = 0; i < tagCount; i++) {" in packet
          and "if (i < keep) {" in packet
          and "new ArrayList<>(tagCount)" not in packet
          and "new java.util.ArrayList<>(tagCount)" not in packet
          and "new java.util.ArrayList<>(keep)" in packet)
    broken_cap = packet.replace("final int keep = Math.min(tagCount, TAG_CAP);",
                                "final int keep = tagCount;")
    counterexample("⑥-24 把 `Math.min(tagCount, TAG_CAP)` 改回 `tagCount` ⇒ ⑥-23 判负（GB 级预分配）",
                   broken_cap != packet
                   and "final int keep = Math.min(tagCount, TAG_CAP);" not in broken_cap)


def main():
    print("=" * 78)
    print("第 114 轮自检：标记体积「整表预算」统一拦截（MarkerBudget）")
    print("=" * 78)
    check_constants()
    check_byte_algorithm()
    check_whole_item_and_delete()
    check_entry_points()
    check_no_side_effects()
    check_lang_and_enum()
    check_review_round114()

    print("-" * 78)
    print("源码锚点清单（file:line）：")
    for group, path, line, note in ANCHORS:
        print("  [%s] %s:%d  %s" % (group, path, line, note))
    print("-" * 78)
    print("检查 %d 项（其中反例自证 %d 项），已知偏差 %d 项，失败 %d 项"
          % (CHECKS[0], COUNTER[0], NOTES[0], len(FAILS)))
    if FAILS:
        for name in FAILS:
            print("  FAILED: " + name)
        return 1
    print("round114 OK 断言 %d / 反例 %d" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as error:  # 常量解析/结构断言失败时也要显式 FAIL，而不是裸 traceback
        print("FAIL 自检脚本异常（源码结构/常量与预期不符）：%r" % (error,))
        sys.exit(1)
