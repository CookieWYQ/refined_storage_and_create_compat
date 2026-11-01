#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 113 轮 B 组自检：区域标记「随身带」的**端到端行为模型**（配置 -> 放入机器 -> 真的按它吸收）。

为什么要再写一份（与 `selfcheck_round112_area_marks.py` 的分工）
----------------------------------------------------------------
第 112 轮把「每区域 ghost 过滤标记」从方块实体搬到区域选定器物品的 `CUSTOM_DATA`
（`RsccAreaMarks{ Marks:[{Area:[6 int], Entries:[...]}] }`，键 = 六坐标几何）。
round112 的 96 条断言几乎全是**源码锚点**（键=几何、复用 encode/decode、上限常量、prune、
migrateByIndex、界面 9×4、语言键成对……）。它**没有**回答两个用户真正关心的问题：

1. 「在手上配好标记 -> 塞进机器 -> 机器**真的**只吸我标的那个物品」这条链路的**行为**对不对
   （空标记 = 什么都不吸、清空立刻失效、同 tick 引用稳定否则数量门槛退化、重叠区域先命中谁）；
2. 第 101 轮**旧存档里的真实数据**（键 = 区域序号）迁过来会怎样（越界 / 负序号 / 缺失 /
   幂等 / 逐区域合并 / 物品已有标记时该区域被跳过且不覆盖）。

本脚本用「纯 Python 行为孪生 + 源码锚点 + **反例自证**」两条腿把这些钉住：
每个判据都配一条「把被断言的那一行/那个行为改坏 ⇒ 该判据必须判负」的自证，杜绝恒真断言。

关于旧档迁移（第 113 轮 + 第 113 轮复核后的最终语义）
---------------------------------------------------
任务正文假设旧档能「判断序号 1 对应的几何在区域表里不存在 ⇒ 丢弃」。**旧档里只有序号、没有几何**
（`AdvancedCollectionCacheBlockEntity` 的 `AreaFilters` 是 `{Index, Entries}`），所以
`AreaMarks#migrateByIndex` 只能按**当前（sanitize 后的）区域列表**的下标翻：

* 序号越界（`index < 0 || index >= areas.size()`）⇒ `droppedOutOfRange++`，
  **绝不夹到最后一个区域**（那会把标记套到别的区域上）；
* 该几何上物品**已有**标记 ⇒ `skippedExisting++` 并 **WARN**（物品是权威，**绝不覆盖**）；
* 其余旧条目 ⇒ 并进来，`migrated++` 并 **INFO**。

**「迁不进去」≠「从存档删掉」**（第 113/114 轮复核后的最终语义）：`migrateByIndex` 内部用
`Iterator` **谁消费谁删** —— 只有「真写进物品」/「物品已有标记」/「空条目」三处 `it.remove()`；
**超大条目（`skippedTooLarge`）、会超预算（`skippedOverBudget`）、越界（`droppedOutOfRange`）
一律冻结、不消费**，原样留在旧表里，等区域表恢复或删掉那个过大标记后自动再试。
BE 侧因此**没有** `removeIf(index < areaCount)`、也没有无条件 `clear()`。
位次漂移（sanitize 在中间丢掉区域 ⇒ 序号整体前移）时用 `isIndexPrefix(raw, usable)` 整体冻结，
**绝不按错位序号把标记套到别的区域上**（`legacyMigrationPinnedWarned` 每次读档重置）。
没有真正进展（`consumed || migrated>0` 为假）时**不**每 tick invalidate 快照 / 标脏。

第 112 轮最初那版「物品上只要有**任意**一条新标记 ⇒ 旧表整张丢弃」同样已删除（升级首日会把另一个
区域的旧标记丢光）；两种旧写法都作为反例自证（②-20 / ②-21 / ②-22 / ②-29）。

第 113 轮复核修复摘要（⑦ 组，独立复核报的 3 高危 + 3 中危 + 附带项）
-------------------------------------------------------------------
1. **REPLACE 丢标记**（高风险真回归）：`AreaSelectorEditPacket` 在 `selection.replace(...)` **之前**读旧几何
   的标记（`carriedMarks`），写回后搬到新几何；新几何已有标记时按 `(id, fluid)` **去重合并、绝不覆盖**。
2. **主手路径被拒后客户端乐观副本残留**：`reject(menu == null)` 也 `player.inventoryMenu.broadcastFullState()`。
3. **`prune(null)` 清空陷阱**：`selection == null` 直接早退（不再当「全部都不存在」）；遍历改为**原始
   `Marks` 列表**，超 64 上限的幽灵记录也能剪掉。
4. **单条标记字节上限**：`AreaMarks.MAX_MARKER_BYTES`（第 114 轮起委托 `MarkerBudget.MAX_ENTRY_BYTES`）/ `isOversized`；服务端
   `AreaMarkEditPacket` 兜底 + 客户端 `AreaMarkScreen` 预检提示 `notice.too_complex`。
5. **客户端 `targetStack()` 菜单路径不回退主手**（与服务端 `AreaSelectorTargets.resolve` 对齐）。
6. **`decodeMarkerTag` 旧档 `Item` 分支 `registries == null` 早退**（`ItemStack.parseOptional(null, ...)` 会 NPE）。
7. 附带：`AreaMarks.count(...)` 死代码删除；lang 新增 `area_mark.volume` / `area_mark.notice.too_complex`。

读档健壮性的一处依赖（诚实标注）
--------------------------------
`AreaMarks` 用 `CompoundTag#getList(key, Tag.TAG_COMPOUND)` 读 `Marks` / `Entries`：
原版语义是「类型不符 / 不存在 ⇒ 返回空 ListTag」。本模型按这条语义断言（畸形 NBT ⇒ 读空、
不抛异常），但这一点是**源码级推断**，未实机跑过（不跑专服）。

用法：`python tools/selfcheck_round113_area_mark_e2e.py`
退出码：0 = 全部通过；最后一行：`round113 OK 断言 N / 反例 M`。
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
LANG_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "lang")
LANG_DIR = os.path.join(ROOT, LANG_REL)

AREA_MARKS = os.path.join(JAVA, "support", "AreaMarks.java")
AREA_SELECTION = os.path.join(JAVA, "support", "AreaSelection.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
BASE_BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
MARK_PACKET = os.path.join(JAVA, "network", "AreaMarkEditPacket.java")
EDIT_PACKET = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
MARK_SCREEN = os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")
ZH = os.path.join(LANG_DIR, "zh_cn.json")
EN = os.path.join(LANG_DIR, "en_us.json")

REL = {
    AREA_MARKS: os.path.join(JAVA_REL, "support", "AreaMarks.java"),
    AREA_SELECTION: os.path.join(JAVA_REL, "support", "AreaSelection.java"),
    ADV_BE: os.path.join(JAVA_REL, "block", "entity", "AdvancedCollectionCacheBlockEntity.java"),
    BASE_BE: os.path.join(JAVA_REL, "block", "entity", "CollectionCacheBlockEntity.java"),
    MARK_PACKET: os.path.join(JAVA_REL, "network", "AreaMarkEditPacket.java"),
    EDIT_PACKET: os.path.join(JAVA_REL, "network", "AreaSelectorEditPacket.java"),
    MARK_SCREEN: os.path.join(JAVA_REL, "client", "screen", "AreaMarkScreen.java"),
    ZH: os.path.join(LANG_REL, "zh_cn.json"),
    EN: os.path.join(LANG_REL, "en_us.json"),
}

CHECKS = [0]
COUNTER = [0]
FAILS = []
ANCHORS = []

RL_RE = re.compile(r"^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$")


# ======================================================================
# 基础设施：读 / 去注释 / 取方法体 / 断言 / 反例 / 锚点行号
# ======================================================================

def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性」判据。"""
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
    """从（已去注释的）源码里取一个方法体：从 signature_hint 起找到第一个 '{' 并配平。"""
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
    """反例自证：参数必须是「判据对坏写法判负了」，否则本脚本本身不可信。"""
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


def lineno(text, snippet):
    index = text.find(snippet)
    return 0 if index < 0 else text[:index].count("\n") + 1


def anchor(group, path, text, snippet, note=""):
    """记录一条源码锚点的 file:line（找不到行号 = 0，说明锚点本身失效）。"""
    line = lineno(text, snippet)
    ANCHORS.append((group, REL.get(path, path), line, (note or snippet).replace("\n", " ")[:96]))
    return line


# ======================================================================
# 常量：从源码派生（不写死 288 / 64，任务明确要求先 grep 真实值）
# ======================================================================

def source_constants():
    base = strip_comments(read(BASE_BE))
    match = re.search(r"MATCH_PAGES\s*=\s*(\d+)\s*;", base)
    pages = int(match.group(1)) if match else -1
    match = re.search(r"MATCH_CAPACITY\s*=\s*MATCH_PAGES\s*\*\s*(\d+)\s*;", base)
    per_page = int(match.group(1)) if match else -1
    selection = strip_comments(read(AREA_SELECTION))
    match = re.search(r"ABSOLUTE_MAX_AREAS\s*=\s*(\d+)\s*;", selection)
    max_areas = int(match.group(1)) if match else -1
    capacity = pages * per_page if pages > 0 and per_page > 0 else -1
    return pages, per_page, capacity, max_areas


MATCH_PAGES, MATCH_PER_PAGE, MATCH_CAPACITY, MAX_AREAS = source_constants()


# ======================================================================
# ① 纯 Python 行为孪生：AreaMarks 的 NBT 结构 / 读 / 写 / 剪枝 / 迁移
#    （逐字对应 support/AreaMarks.java；outer = 物品 CUSTOM_DATA 的内层 CompoundTag）
# ======================================================================

TAG_ROOT = "RsccAreaMarks"
TAG_MARKS = "Marks"
TAG_AREA = "Area"
TAG_ENTRIES = "Entries"


def geom(area):
    """对应 Java：`area.withName(null)` —— 键只取六个坐标，名字不参与。"""
    return tuple(int(v) for v in area[:6])


def marker(item_id, amount=1, fluid=False):
    return {"id": item_id, "amount": int(amount), "fluid": bool(fluid)}


def new_outer():
    """一件「从来没配过标记」的选定器：CUSTOM_DATA 里没有 RsccAreaMarks 子树。"""
    return {}


def _is_empty_marker(entry):
    return not entry or not entry.get("id")


def _encode_marker(entry, slot):
    """对应 Java `CollectionCacheBlockEntity#encodeMarkerTag`（区域过滤器只用到这几个字段）。"""
    if _is_empty_marker(entry):
        return None
    return {
        "Slot": int(slot),
        "Fluid": bool(entry.get("fluid")),
        "Id": entry["id"],
        "Nbt": {},
        "Amount": int(entry.get("amount", 1)),
        "MatchNbt": False,
    }


def _decode_marker(tag):
    """对应 Java `decodeMarkerTag`：Id 可解析 -> 条目；否则尝试旧档 Item；都不行 -> None。"""
    if not isinstance(tag, dict):
        return None
    if "Id" in tag:
        raw = tag.get("Id")
        if not isinstance(raw, str) or not RL_RE.match(raw):
            return None
        return {
            "id": raw,
            "amount": max(1, int(tag.get("Amount", 1) or 1)),
            "fluid": bool(tag.get("Fluid", False)),
        }
    if "Item" in tag:
        item = tag.get("Item")
        if isinstance(item, dict) and isinstance(item.get("id"), str) and RL_RE.match(item["id"]):
            return {"id": item["id"],
                    "amount": max(1, int(tag.get("Amount", 1) or 1)),
                    "fluid": False}
        return None
    return None


def _read_area(tag):
    """对应 Java `readArea`：`Area` 必须是长度 6 的 int array，否则 None（跳过，不抛）。"""
    if not isinstance(tag, dict):
        return None
    if TAG_AREA not in tag:
        return None
    values = tag[TAG_AREA]
    if not isinstance(values, (list, tuple)) or len(values) != 6:
        return None
    for value in values:
        if isinstance(value, bool) or not isinstance(value, int):
            return None
    return tuple(values)


def _read_entries(tag, capacity=0):
    """对应 Java `readEntries`：`getList(Entries, TAG_COMPOUND)` 类型过滤 + 逐条解码 + 上限截断。"""
    if not isinstance(tag, dict):
        return []
    if TAG_ENTRIES not in tag:
        return []
    raw = tag[TAG_ENTRIES]
    if not isinstance(raw, list):
        return []                      # 原版 getList：不是 ListTag ⇒ 空
    if raw and not all(isinstance(element, dict) for element in raw):
        return []                      # 元素类型不是 compound ⇒ getList 返回空
    cap = capacity if capacity > 0 else MATCH_CAPACITY
    out = []
    for element in raw:
        if len(out) >= cap:
            break
        entry = _decode_marker(element)
        if entry is not None and not _is_empty_marker(entry):
            out.append(entry)
    return out


def _root(outer):
    """对应 Java `root(stack)`：只读拿内层 tag；不是 CompoundTag ⇒ null。"""
    if not isinstance(outer, dict):
        return None
    tag = outer.get(TAG_ROOT)
    return tag if isinstance(tag, dict) else None


def _marks_list(root):
    """对应 Java `root.getList(TAG_MARKS, Tag.TAG_COMPOUND)` 的类型过滤语义。"""
    if not isinstance(root, dict) or TAG_MARKS not in root:
        return []
    raw = root[TAG_MARKS]
    if not isinstance(raw, list):
        return []
    if raw and not all(isinstance(element, dict) for element in raw):
        return []
    return raw


def get_marks(outer, area, capacity=None):
    """`AreaMarks.get(stack, area)`：命中第一条同几何记录，返回它的 Entries（读侧截断）。"""
    if outer is None or area is None:
        return []
    root = _root(outer)
    if root is None:
        return []
    key = geom(area)
    for entry in _marks_list(root):
        decoded = _read_area(entry)
        if decoded is not None and decoded == key:
            return _read_entries(entry, capacity or MATCH_CAPACITY)
    return []


def all_marks(outer, capacity=None, max_areas=None):
    """`AreaMarks.all(stack)`：LinkedHashMap（插入顺序、同几何只留第一条、空表跳过、上限截断）。"""
    cap = capacity if capacity is not None else MATCH_CAPACITY
    limit = max_areas if max_areas is not None else MAX_AREAS
    out = {}
    if outer is None:
        return out
    root = _root(outer)
    if root is None:
        return out
    for entry in _marks_list(root):
        if len(out) >= limit:
            break
        area = _read_area(entry)
        if area is None or area in out:
            continue
        entries = _read_entries(entry, cap)
        if entries:
            out[area] = entries
    return out


def has_any(outer):
    return bool(all_marks(outer))


def _sanitize_entries(entries, capacity=None):
    """`sanitizeEntries`：去空、截到上限（顺序保持 —— 这一条是 ⑥ 反例的被测点）。"""
    cap = capacity if capacity is not None else MATCH_CAPACITY
    out = []
    for entry in entries or []:
        if len(out) >= cap:
            break
        if not _is_empty_marker(entry):
            out.append(entry)
    return out


def put_marks(outer, area, entries, capacity=None):
    """`AreaMarks.put`：removeIf(同几何/坏记录) + add 到末尾 + 空树删键 + 空组件删组件。"""
    if outer is None or area is None:
        return outer
    key = geom(area)
    clean = _sanitize_entries(entries, capacity)
    root = _root(outer)
    root = dict(root) if root is not None else {}
    kept = []
    for entry in _marks_list(root):
        decoded = _read_area(entry)
        if decoded is None or decoded == key:
            continue                  # 坏记录顺手清掉 / 同几何先删（再 append ⇒ 搬移语义）
        kept.append(entry)
    if clean:
        kept.append({"Area": list(key),
                     "Entries": [_encode_marker(item, index) for index, item in enumerate(clean)]})
    if kept:
        root[TAG_MARKS] = kept
    else:
        root.pop(TAG_MARKS, None)
    if root:
        outer[TAG_ROOT] = root
    else:
        outer.pop(TAG_ROOT, None)
    return outer


def remove_marks(outer, area):
    return put_marks(outer, area, [])


def prune_marks(outer, areas):
    """`AreaMarks.prune`（第 113 轮复核版）。

    * `areas is None`（= Java 的 `selection == null`）⇒ **什么都不剪**（不是「全清」）；
    * 遍历**原始 Marks 列表**（不再走 `all()` 的区域数上限）⇒ 超上限的幽灵记录也能剪掉；
    * 只剪「几何已经不在区域列表里」的记录（名字变了不剪）。
    """
    if outer is None or areas is None:
        return outer
    keep = {geom(area) for area in areas if area is not None}
    root = _root(outer)
    if root is None:
        return outer
    drop = []
    for entry in _marks_list(root):
        existing = _read_area(entry)
        if existing is not None and existing not in keep:
            drop.append(existing)
    for key in drop:
        # 用几何反查一个 Area 实例（prune 里 remove(stack, area) 走的也是几何）
        remove_marks(outer, key)
    return outer


def migrate_by_index(outer, areas, legacy, capacity=None):
    """对应 Java `AreaMarks#migrateByIndex`（第 113 轮「逐区域合并」语义，返回三元组）。

    返回 `(migrated, skippedExisting, droppedOutOfRange)`：
      * 序号 `index < 0 || index >= areas.size()` ⇒ dropped++（丢弃，**绝不夹到最后一个区域**）；
      * 该几何上物品**已有**标记 ⇒ skipped++（物品是权威，**绝不覆盖**）；
      * 否则把旧条目并进来 ⇒ migrated++。
    旧条目本身没有有效标记 ⇒ 三个计数都不动（与 Java 的 `continue` 一致）。
    """
    if outer is None or areas is None or not legacy:
        return (0, 0, 0)
    migrated = skipped = dropped = 0
    for index, entries in legacy.items():
        if index is None or index < 0 or index >= len(areas):
            dropped += 1
            continue
        area = areas[index]
        if get_marks(outer, area, capacity):
            skipped += 1
            continue
        clean = _sanitize_entries(entries, capacity)
        if not clean:
            continue
        put_marks(outer, area, clean, capacity)
        migrated += 1
    return (migrated, skipped, dropped)


# ======================================================================
# ④ 的孪生：机器吸收口径（对应 AdvancedCollectionCacheBlockEntity +
#    CollectionCacheBlockEntity#collectFromDroppedItems 的逐实体分桶）
# ======================================================================

NO_FILTERS = ()


def contains(area, pos):
    x, y, z = pos
    return area[0] <= x <= area[3] and area[1] <= y <= area[4] and area[2] <= z <= area[5]


def first_area_for(areas, pos):
    """`filtersForEntity`：`contains(pos) || contains(pos.below()) || contains(pos.above())`，先命中先算。"""
    x, y, z = pos
    for area in areas:
        if contains(area, pos) or contains(area, (x, y - 1, z)) or contains(area, (x, y + 1, z)):
            return area
    return None


def marks_for(outer, area, cache, capacity=None, stable=True):
    """`marksFor`：同一 tick 内同一区域必须返回**同一个对象**（吸收侧按引用相等分桶）。"""
    if area is None:
        return NO_FILTERS
    if not stable:
        return tuple(get_marks(outer, area, capacity)) or NO_FILTERS
    key = geom(area)
    if cache is not None and key in cache:
        return cache[key]
    filters = tuple(get_marks(outer, area, capacity)) or NO_FILTERS
    if cache is not None:
        cache[key] = filters
    return filters


def absorb(areas, outer, drops, stale=None, stable=True, capacity=None):
    """模拟一轮吸收：返回被吸走的掉落物列表。

    `stale` 给一个「清空前抓下来的 marks 快照」用来演示陈旧缓存；
    `stable=False` 让每次取过滤集合都新建列表（演示引用不稳定导致数量门槛退化）。
    """
    cache = {}
    contexts = []   # [(filters, [drop...])] —— 引用相等分桶
    for drop in drops:
        area = first_area_for(areas, drop["pos"])
        if area is None:
            continue
        if stale is not None:
            filters = stale.get(geom(area), NO_FILTERS)
        else:
            filters = marks_for(outer, area, cache, capacity, stable)
        if not filters:
            continue                    # 空标记 = 该区域什么都不吸（绝不退回「全吸」）
        for context in contexts:
            if context[0] is filters:
                context[1].append(drop)
                break
        else:
            contexts.append([filters, [drop]])
    absorbed = []
    for filters, bucket in contexts:
        for entry in filters:
            if entry.get("fluid"):
                continue
            hit = [drop for drop in bucket if drop["id"] == entry["id"]]
            if sum(int(drop.get("count", 1)) for drop in hit) >= int(entry.get("amount", 1)):
                absorbed.extend(hit)
    return absorbed


# ======================================================================
# 断言组
# ======================================================================

def check_constants():
    check("⓪-1 常量从源码派生：MATCH_PAGES=%d × %d = MATCH_CAPACITY=%d（不写死 288）"
          % (MATCH_PAGES, MATCH_PER_PAGE, MATCH_CAPACITY),
          MATCH_PAGES == 6 and MATCH_PER_PAGE == 48 and MATCH_CAPACITY == 288)
    check("⓪-2 区域数上限取自 AreaSelection.ABSOLUTE_MAX_AREAS = %d" % MAX_AREAS, MAX_AREAS == 64)
    check("⓪-3 两个上限都解析成功（正则失效即判负，防脚本静默用 -1 恒过）",
          MATCH_CAPACITY > 0 and MAX_AREAS > 0)


# ----------------------------------------------------------------------
# ① 编码往返 / 几何键语义（模型 + 源码锚点）
# ----------------------------------------------------------------------

def check_roundtrip():
    a = (0, 64, 0, 10, 70, 10, "刷铁机")
    b = (20, 64, 0, 30, 70, 10, None)
    outer = new_outer()

    put_marks(outer, a, [marker("minecraft:iron_ingot"), marker("minecraft:gold_ingot")])
    check("①-1 put → get 往返（两条标记原样读回）",
          [entry["id"] for entry in get_marks(outer, a)] == ["minecraft:iron_ingot", "minecraft:gold_ingot"])
    check("①-2 未写过的区域 get 返回空表（不是 None、不抛）", get_marks(outer, b) == [])

    renamed = (0, 64, 0, 10, 70, 10, "改了个名字")
    check("①-3 同几何不同名字命中同一条记录（改名不丢标记）",
          get_marks(outer, renamed) == get_marks(outer, a))

    put_marks(outer, b, [marker("minecraft:coal")])
    check("①-4 两个区域互不串（A 仍是 iron+gold、B 只有 coal）",
          [e["id"] for e in get_marks(outer, a)] == ["minecraft:iron_ingot", "minecraft:gold_ingot"]
          and [e["id"] for e in get_marks(outer, b)] == ["minecraft:coal"])

    order = list(all_marks(outer))
    check("①-5 all() 顺序 = 首次插入顺序", order == [geom(a), geom(b)], str(order))

    put_marks(outer, a, [marker("minecraft:diamond")])
    check("①-6 重复 put 已存在区域 ⇒ 记录移到末尾（Java removeIf + add 的真实语义）",
          list(all_marks(outer)) == [geom(b), geom(a)], str(list(all_marks(outer))))

    fresh = new_outer()
    put_marks(fresh, a, [marker("minecraft:iron_ingot")])
    remove_marks(fresh, a)
    check("①-7 删空即删子树（不留 RsccAreaMarks:{Marks:[]} 噪声；组件整体消失）",
          TAG_ROOT not in fresh and fresh == {})

    # 手工构造「同一几何出现两条」的畸形/旧数据
    dup = {TAG_ROOT: {TAG_MARKS: [
        {"Area": list(geom(a)), "Entries": [_encode_marker(marker("minecraft:iron_ingot"), 0)]},
        {"Area": list(geom(a)), "Entries": [_encode_marker(marker("minecraft:diamond"), 0)]},
    ]}}
    check("①-8 重复几何：get 只认第一条（不合并两条的标记）",
          [e["id"] for e in get_marks(dup, a)] == ["minecraft:iron_ingot"])
    check("①-9 重复几何：all() 只保留一条（out.containsKey 跳过）", len(all_marks(dup)) == 1)

    empty_entries = {TAG_ROOT: {TAG_MARKS: [{"Area": list(geom(a)), "Entries": []}]}}
    check("①-10 Entries 为空 ⇒ get 空、all 跳过（空表不算「配了标记」）",
          get_marks(empty_entries, a) == [] and all_marks(empty_entries) == {})

    code = strip_comments(read(AREA_MARKS))
    raw = read(AREA_MARKS)
    anchor("①", AREA_MARKS, raw, 'private static final String TAG_ROOT = "RsccAreaMarks";',
           "根键名（冻结契约）")
    anchor("①", AREA_MARKS, raw, "return area.withName(null);", "几何键 = withName(null)")
    anchor("①", AREA_MARKS, raw, "if (values.length != 6) {", "readArea 要求长度 6")
    anchor("①", AREA_MARKS, raw, "marks.removeIf(tag -> {", "put：先删同几何/坏记录再 append")
    anchor("①", AREA_MARKS, raw, "marks.add(writeArea(key, clean));", "put：追加到末尾")
    check("①-11 源码：根键 / 几何键 / 长度校验 / removeIf+add 四段都在",
          'String TAG_ROOT = "RsccAreaMarks"' in code
          and "return area.withName(null);" in code
          and "values.length != 6" in code
          and "marks.removeIf(tag -> {" in code
          and "marks.add(writeArea(key, clean));" in code)

    # 反例：把键改成「名字参与」⇒ 改名后标记丢失
    def key_with_name(area):
        return tuple(area)

    broken = {}
    put_broken = {TAG_ROOT: {TAG_MARKS: [{"Area": list(geom(a)),
                                          "Entries": [_encode_marker(marker("minecraft:iron_ingot"), 0)]}]}}
    store = {}
    for tag in _marks_list(_root(put_broken)):
        store[key_with_name(a)] = _read_entries(tag)
    counterexample("①-12 键含名字 ⇒ 改名后标记丢失（模型判负）",
                   store.get(key_with_name(renamed), []) == [])

    # 反例：把键改成「区域序号」⇒ 删掉第 0 个区域后，第 1 个区域的序号变 0，标记串区
    index_store = {0: ["minecraft:iron_ingot"], 1: ["minecraft:coal"]}
    reordered = [(20, 64, 0, 30, 70, 10, None)]   # 原第 2 个区域被删，B 变成 index 0
    counterexample("①-13 键 = 序号 ⇒ 删区域重排后标记串到别的区域（模型判负）",
                   index_store.get(0) != get_marks(outer, reordered[0])
                   and index_store.get(0) == ["minecraft:iron_ingot"])

    # 反例：put 写成「只 append、不做 removeIf」⇒ 同一几何出现两条，①-9 的判据会判负
    def put_accumulate(outer_nbt, area, entries, capacity=None):
        root = _root(outer_nbt)
        root = dict(root) if root is not None else {}
        kept = list(_marks_list(root))
        kept.append({"Area": list(geom(area)),
                     "Entries": [_encode_marker(item, i) for i, item in enumerate(entries)]})
        root[TAG_MARKS] = kept
        outer_nbt[TAG_ROOT] = root
        return outer_nbt

    broken_writer = new_outer()
    put_marks(broken_writer, a, [marker("minecraft:iron_ingot")])
    put_accumulate(broken_writer, a, [marker("minecraft:gold_ingot")])
    good_writer = new_outer()
    put_marks(good_writer, a, [marker("minecraft:iron_ingot")])
    put_marks(good_writer, a, [marker("minecraft:gold_ingot")])
    check("①-15 正常 put 同区域两次 ⇒ NBT 里只剩 1 条记录（removeIf 生效，不留重复）",
          len(_marks_list(_root(good_writer))) == 1
          and [e["id"] for e in get_marks(good_writer, a)] == ["minecraft:gold_ingot"])
    counterexample("①-14 put 不做 removeIf（改成只 append）⇒ NBT 里留下同几何 2 条重复记录（判负）",
                   len(_marks_list(_root(broken_writer))) == 2)


# ----------------------------------------------------------------------
# ② 旧档迁移：第 101 轮真实旧数据（{Index, Entries}，键 = 序号）
# ----------------------------------------------------------------------

def check_migration():
    a = (0, 64, 0, 10, 70, 10, "A")
    c = (20, 64, 0, 30, 70, 10, "C")
    raw_adv = read(ADV_BE)
    code = strip_comments(raw_adv)
    raw_marks = read(AREA_MARKS)
    marks_code = strip_comments(raw_marks)
    raw_load = method_body(code, "public void loadAdditional")
    mig = method_body(code, "private void migrateLegacyAreaFilters")

    def migrate_legacy_filters(legacy_map, areas, outer_nbt, capacity=None):
        """BE 层孪生（第 113 轮复核语义）：只删「已映射」的旧条目，未映射的留在旧表里等下次。"""
        result = migrate_by_index(outer_nbt, areas, legacy_map, capacity)
        for index in [key for key in legacy_map if key is not None and 0 <= key < len(areas)]:
            del legacy_map[index]
        return result, (not legacy_map)

    # 真实旧档：0 -> iron（区域 A）、1 -> gold（区域 C）、2 -> coal（区域已被删 ⇒ 越界）
    outer = new_outer()
    result = migrate_by_index(outer, [a, c],
                              {0: [marker("minecraft:iron_ingot")],
                               1: [marker("minecraft:gold_ingot")],
                               2: [marker("minecraft:coal")]})
    check("②-1 旧档 0 → 几何 A、1 → 几何 C（旧档只有序号，按当前列表下标翻）",
          [e["id"] for e in get_marks(outer, a)] == ["minecraft:iron_ingot"]
          and [e["id"] for e in get_marks(outer, c)] == ["minecraft:gold_ingot"])
    check("②-2 越界序号 2 不产生幽灵区域、也不夹到最后一个",
          [e["id"] for e in get_marks(outer, c)] == ["minecraft:gold_ingot"]
          and len(all_marks(outer)) == 2)
    check("②-3 MergeResult 三元组：migrated=2 / skippedExisting=0 / droppedOutOfRange=1",
          result == (2, 0, 1), str(result))

    # 第 113 轮复核核心：区域表暂时为空时**绝不能**清空旧表（旧实现无条件 clear ⇒ 永久丢失）
    empty_legacy = {0: [marker("minecraft:iron_ingot")]}
    empty_outer = new_outer()
    empty_result, empty_all = migrate_legacy_filters(empty_legacy, [], empty_outer)
    check("②-4 区域表暂时为空 ⇒ 旧条目一条不删、只计 dropped（不再无条件清存档）",
          empty_result == (0, 0, 1) and empty_all is False
          and list(empty_legacy) == [0] and get_marks(empty_outer, a) == [])
    restored_result, restored_all = migrate_legacy_filters(empty_legacy, [a], empty_outer)
    check("②-5 区域表恢复后重试 ⇒ 未映射条目被并进来（0→A），旧表随之删空、allMapped=True",
          restored_result == (1, 0, 0) and restored_all is True and empty_legacy == {}
          and [e["id"] for e in get_marks(empty_outer, a)] == ["minecraft:iron_ingot"])

    # 部分映射：只删已映射的序号，未映射（5）留在旧表里
    partial_legacy = {0: [marker("minecraft:iron_ingot")], 5: [marker("minecraft:coal")]}
    partial_outer = new_outer()
    partial_result, partial_all = migrate_legacy_filters(partial_legacy, [a, c], partial_outer)
    check("②-6 部分映射：已映射的 0 从旧表删掉、序号 5 原样保留（等区域表变大/恢复再迁）",
          partial_result == (1, 0, 1) and partial_all is False
          and list(partial_legacy) == [5]
          and [e["id"] for e in get_marks(partial_outer, a)] == ["minecraft:iron_ingot"]
          and get_marks(partial_outer, c) == [])

    # 「物品已有标记被 skipped」也算已映射 ⇒ 旧条目删掉，不会每次加载都重试
    skip_legacy = {1: [marker("minecraft:gold_ingot")]}
    skip_outer = new_outer()
    put_marks(skip_outer, c, [marker("minecraft:coal")])
    skip_result, skip_all = migrate_legacy_filters(skip_legacy, [a, c], skip_outer)
    check("②-7 skippedExisting 的序号也算「已映射」⇒ 旧条目从旧表删除、不会每次加载重试",
          skip_result == (0, 1, 0) and skip_all is True and skip_legacy == {}
          and [e["id"] for e in get_marks(skip_outer, c)] == ["minecraft:coal"])

    # 负序号：loadAdditional 那一层就先丢（`if (areaIndex < 0) continue;`）；模型里只算 dropped
    check("②-8 读档层丢弃负序号；模型里也只计 dropped、不写标记",
          raw_load is not None and "if (areaIndex < 0) {" in raw_load
          and migrate_by_index(new_outer(), [a], {-1: [marker("minecraft:coal")]}) == (0, 0, 1))

    # 逐区域合并：物品上已有某几何的新标记 ⇒ 只跳过那一个，其余照迁，且不覆盖
    merged_outer = new_outer()
    put_marks(merged_outer, c, [marker("minecraft:coal")])      # 玩家先用新界面给 C 配了标记
    merged = migrate_by_index(merged_outer, [a, c],
                              {0: [marker("minecraft:iron_ingot")],
                               1: [marker("minecraft:gold_ingot")]})
    check("②-9 逐区域合并：C 已有新标记 ⇒ 只跳过 C，A 的旧标记仍然迁入（不再是整张丢弃）",
          merged == (1, 1, 0)
          and [e["id"] for e in get_marks(merged_outer, a)] == ["minecraft:iron_ingot"]
          and [e["id"] for e in get_marks(merged_outer, c)] == ["minecraft:coal"],
          str(merged))
    check("②-10 被跳过的几何绝不被旧表覆盖（物品是权威）",
          get_marks(merged_outer, c) == [marker("minecraft:coal")])

    # 幂等：旧表清空后再迁 ⇒ (0, 0, 0)、标记不变
    idem_outer = new_outer()
    first = migrate_legacy_filters({0: [marker("minecraft:iron_ingot")]}, [a], idem_outer)
    second = migrate_legacy_filters({}, [a], idem_outer)
    check("②-11 幂等：旧表清空后再迁 (0,0,0)、标记集合不变",
          first[0] == (1, 0, 0) and first[1] is True
          and second[0] == (0, 0, 0) and second[1] is True
          and [e["id"] for e in get_marks(idem_outer, a)] == ["minecraft:iron_ingot"])

    anchor("②", ADV_BE, raw_adv, "if (!hasSelector()) {", "选定器不在 ⇒ 不迁移、不置位")
    anchor("②", ADV_BE, raw_adv, "if (legacyAreaFilters.isEmpty()) {", "旧表为空 ⇒ 标已迁完")
    anchor("②", ADV_BE, raw_adv, "final AreaSelection raw = AreaSelection.get(stack);",
           "raw / usable 分开（第 114 轮复核 M1）")
    anchor("②", ADV_BE, raw_adv, "if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {",
           "位次对不上 ⇒ 整体冻结（不迁移、不消费）")
    anchor("②", ADV_BE, raw_adv, "AreaMarks.migrateByIndex(stack, usable, legacyAreaFilters,",
           "迁移入口（第 114 轮复核版：谁消费谁删）")
    anchor("②", ADV_BE, raw_adv, "level == null ? null : level.registryAccess()",
           "BE 把注册表上下文传给 migrateByIndex")
    anchor("②", ADV_BE, raw_adv, "final boolean consumed = legacyAreaFilters.size() != before;",
           "consumed = 旧表真的变小（只有消费才会）")
    anchor("②", ADV_BE, raw_adv, "final boolean changed = consumed || result.migrated() > 0;",
           "changed = 有进展才刷快照/标脏")
    anchor("②", ADV_BE, raw_adv, "if (changed) {", "三行 side-effect 必须在 changed 块里")
    anchor("②", ADV_BE, raw_adv, "TAG_AREA_FILTERS = \"AreaFilters\"", "旧键名（可迁移、可回退观察）")
    anchor("②", ADV_BE, raw_adv, "!legacyAreaFilters.isEmpty() && !legacyMigrationWarned",
           "未迁完的 WARN 只打一次（节流）")
    anchor("②", ADV_BE, raw_adv, "legacyAreaFilters.size()", "WARN 用旧表实际剩余条数（数据自洽）")
    anchor("②", AREA_MARKS, raw_marks,
           "public record MergeResult(int migrated, int skippedExisting, int droppedOutOfRange,",
           "MergeResult 六字段（含 skippedTooLarge / skippedOverBudget / pinned）")
    anchor("②", AREA_MARKS, raw_marks, "this(migrated, skippedExisting, droppedOutOfRange, 0, 0, false);",
           "3 参兼容构造器")
    anchor("②", AREA_MARKS, raw_marks, "legacyByIndex.entrySet().iterator()",
           "Iterator 遍历（谁消费谁删）")
    anchor("②", AREA_MARKS, raw_marks, "if (index < 0 || index >= areas.size()) {",
           "越界 ⇒ dropped++（不消费，绝不夹取）")
    anchor("②", AREA_MARKS, raw_marks, "dropped++;", "droppedOutOfRange 计数")
    anchor("②", AREA_MARKS, raw_marks, "if (!get(stack, area, registries).isEmpty()) {",
           "该几何已有标记 ⇒ skipped++ 并消费")
    anchor("②", AREA_MARKS, raw_marks, "skipped++;", "skippedExisting 计数")
    anchor("②", AREA_MARKS, raw_marks, "if (MarkerBudget.isEntryTooBig(candidate)) {",
           "超大 ⇒ skippedTooLarge（冻结、不消费）")
    anchor("②", AREA_MARKS, raw_marks, "if (isOverBudget(stack, area, clean)) {",
           "会超预算 ⇒ skippedOverBudget（冻结、不消费）")
    anchor("②", AREA_MARKS, raw_marks, "put(stack, area, clean);", "其余几何照常并入（并消费）")

    check("②-12 源码：选定器不在不置位（`legacyFiltersMigrated = true` 在 hasSelector 之后）",
          mig is not None
          and mig.index("if (!hasSelector()) {") < mig.index("legacyFiltersMigrated = true;"))
    check("②-13 源码：旧条目的删除只发生在 migrateByIndex 的 it.remove() 三处；"
          "BE 没有无条件 clear / 没有整段 removeIf",
          mig is not None
          and "legacyAreaFilters.clear();" not in mig
          and "removeIf(" not in mig
          and "legacyAreaFilters.keySet().removeIf(" not in mig
          and marks_code.count("it.remove();") == 3
          and "legacyByIndex.entrySet().iterator()" in marks_code)
    check("②-14 源码：load 时先 clear 旧表再按 NBT 重建（不累积）",
          raw_load is not None
          and raw_load.index("legacyAreaFilters.clear();")
          < raw_load.index("tag.getList(TAG_AREA_FILTERS, Tag.TAG_COMPOUND)"))
    check("②-15 源码：invalidate 缓存/快照 + setChanged 三行都在 `if (changed)` 块内（M2 节流）",
          mig is not None
          and "if (changed) {" in mig
          and all(token in mig[mig.index("if (changed) {"):mig.index("if (changed) {") + 400]
                  for token in ("invalidateMarksCache();", "invalidateAreaSnapshot();", "setChanged();"))
          and mig.count("invalidateAreaSnapshot();") == 1)
    check("②-16 源码：六元组 + 3 参兼容 + 越界/已标记/超大/超预算四类计数 + BE 传 registryAccess",
          "public record MergeResult(int migrated, int skippedExisting, int droppedOutOfRange," in marks_code
          and "int skippedTooLarge, int skippedOverBudget, boolean pinned)" in marks_code
          and "this(migrated, skippedExisting, droppedOutOfRange, 0, 0, false);" in marks_code
          and "dropped++;" in marks_code and "skipped++;" in marks_code
          and "tooLarge++;" in marks_code and "overBudget++;" in marks_code
          and "if (!get(stack, area, registries).isEmpty()) {" in marks_code
          and "level == null ? null : level.registryAccess()" in code
          and "legacyMigrationPinnedWarned" in code)

    # 「迁移后旧键不再被读」的结构级判据
    rest = code
    for hint in ("private void migrateLegacyAreaFilters", "public void saveAdditional",
                 "public void loadAdditional"):
        body = method_body(code, hint)
        if body:
            rest = rest.replace(body, "")
    field_decl = "private final Map<Integer, List<MarkerEntry>> legacyAreaFilters"
    anchor("②", ADV_BE, raw_adv, field_decl, "旧表字段只在迁移/存档里出现")
    check("②-17 旧表只在「迁移 / save / load」三处出现，读标记与吸收路径完全不碰它",
          field_decl in rest and rest.replace(field_decl, "").count("legacyAreaFilters") == 0)

    # 三类计数 / 未迁完条目必须各自打日志，且 WARN 不许静默
    def guard_window(body, guard):
        pos = body.find(guard)
        if pos < 0:
            return ""
        following = [body.find(other, pos + 1) for other in
                     ("if (result.migrated() > 0) {", "if (result.skippedExisting() > 0) {",
                      "if (!legacyAreaFilters.isEmpty() && !legacyMigrationWarned) {")]
        following = [value for value in following if value > pos]
        end = min(following) if following else len(body)
        return body[pos:end]

    migrated_window = guard_window(mig or "", "if (result.migrated() > 0) {")
    skipped_window = guard_window(mig or "", "if (result.skippedExisting() > 0) {")
    unmapped_window = guard_window(
        mig or "", "if (!legacyAreaFilters.isEmpty() && !legacyMigrationWarned) {")
    check("②-18 日志：migrated>0 ⇒ info；skippedExisting>0 ⇒ warn；"
          "还有没迁的 ⇒ warn（用 legacyAreaFilters.size() 且 legacyMigrationWarned 节流）",
          "LOGGER.info(" in migrated_window
          and "LOGGER.warn(" in skipped_window
          and "LOGGER.warn(" in unmapped_window
          and "legacyAreaFilters.size()" in unmapped_window
          and "legacyMigrationWarned = true;" in unmapped_window)
    check("②-19 未迁完的 WARN 把 droppedOutOfRange / skippedTooLarge / skippedOverBudget 三类计数"
          "与线上上限都打进日志（不再是只看 droppedOutOfRange）",
          mig is not None
          and "result.droppedOutOfRange()" in mig
          and "result.skippedTooLarge()" in mig
          and "result.skippedOverBudget()" in mig
          and "MAX_ENTRY_WIRE_BYTES" in mig and "MAX_TOTAL_WIRE_BYTES" in mig
          and "droppedOutOfRange" in marks_code)

    # 反例（旧实现）：物品上只要有任意新标记 ⇒ 旧表整张丢弃 ⇒ 另一个区域的旧标记永久丢失
    def migrate_whole_discard(outer_nbt, areas, legacy_map, capacity=None):
        if has_any(outer_nbt):
            return (0, 0, 0)          # 第 112 轮旧写法：整张丢弃
        return migrate_by_index(outer_nbt, areas, legacy_map, capacity)

    old_outer = new_outer()
    put_marks(old_outer, c, [marker("minecraft:coal")])
    old_legacy = {0: [marker("minecraft:iron_ingot")], 1: [marker("minecraft:gold_ingot")]}
    old_result = migrate_whole_discard(old_outer, [a, c], old_legacy)
    new_merge_outer = new_outer()
    put_marks(new_merge_outer, c, [marker("minecraft:coal")])
    new_result = migrate_by_index(new_merge_outer, [a, c], old_legacy)
    counterexample("②-20 旧实现「有任意新标记就整张丢弃」⇒ 区域 A 的旧标记永久丢失（判负）",
                   old_result == (0, 0, 0) and get_marks(old_outer, a) == []
                   and new_result == (1, 1, 0)
                   and [e["id"] for e in get_marks(new_merge_outer, a)]
                   == ["minecraft:iron_ingot"])

    # 反例：迁移后无条件 clear 旧表 ⇒ 区域表暂时为空时旧条目被清空（永久丢失）
    def migrate_legacy_filters_clear_all(legacy_map, areas, outer_nbt, capacity=None):
        result = migrate_by_index(outer_nbt, areas, legacy_map, capacity)
        legacy_map.clear()            # 旧写法：无条件 clear
        return result, (not legacy_map)

    bad_legacy = {0: [marker("minecraft:iron_ingot")]}
    bad_outer = new_outer()
    bad_result, bad_all = migrate_legacy_filters_clear_all(bad_legacy, [], bad_outer)
    counterexample("②-21 「迁移后无条件 clear 旧表」⇒ 区域表暂时为空时旧条目被清空、"
                   "标记永久丢失（②-4 判据判负）",
                   bad_result == (0, 0, 1) and bad_legacy == {} and bad_all is True)

    # 反例：把「映射不上」的条目也一起删掉 ⇒ 序号 5 的标记丢失
    def migrate_legacy_filters_drop_unmapped(legacy_map, areas, outer_nbt, capacity=None):
        result = migrate_by_index(outer_nbt, areas, legacy_map, capacity)
        for index in sorted(legacy_map, key=lambda value: (value is None, value)):
            del legacy_map[index]     # 坏写法：未映射的也删
        return result, (not legacy_map)

    drop_legacy = {0: [marker("minecraft:iron_ingot")], 5: [marker("minecraft:coal")]}
    drop_outer = new_outer()
    migrate_legacy_filters_drop_unmapped(drop_legacy, [a, c], drop_outer)
    counterexample("②-22 「未映射条目直接从存档删掉」⇒ 序号 5 的 coal 丢失（②-6 判据判负）",
                   drop_legacy == {} and 5 not in drop_legacy)

    # 反例：把「越界丢弃」改成「夹到最后一个区域」⇒ 序号 2 的 coal 串到 C 上
    def migrate_clamped(areas, legacy_map):
        result_map = {}
        for index, entries in legacy_map.items():
            if index is None or index < 0:
                continue
            target = areas[min(max(index, 0), len(areas) - 1)]   # 夹取（坏写法）
            result_map[geom(target)] = entries
        return result_map

    clamped = migrate_clamped([a, c], {0: ["iron"], 1: ["gold"], 2: ["coal"]})
    counterexample("②-23 越界改「夹到最后一个区域」⇒ 序号 2 的标记串到 C，而不是被保留/丢弃（判负）",
                   clamped.get(geom(c)) == ["coal"] and clamped.get(geom(c)) != ["gold"])

    # 反例：把 skipped 分支的 LOGGER.warn 降级成 debug ⇒ ②-18 判据判负（静默跳过）
    silent_skipped = (mig or "").replace(
        'LOGGER.warn("[rs_create_compat] 高级归流缓存仓：旧档每区域过滤迁移时',
        'LOGGER.debug("[rs_create_compat] 高级归流缓存仓：旧档每区域过滤迁移时')
    counterexample("②-24 把 skippedExisting 的 warn 降级成 debug（静默跳过）⇒ 判据判负",
                   silent_skipped != mig
                   and "LOGGER.warn(" not in guard_window(
                       silent_skipped, "if (result.skippedExisting() > 0) {"))

    # 反例：把「还有没迁的」的 warn 降级成 debug ⇒ ②-18 判据判负（静默保留/静默丢弃）
    silent_unmapped = (mig or "").replace(
        'LOGGER.warn("[rs_create_compat] 高级归流缓存仓：还有 {} 条旧区域过滤本轮没迁"',
        'LOGGER.debug("[rs_create_compat] 高级归流缓存仓：还有 {} 条旧区域过滤本轮没迁"')
    counterexample("②-25 把「还有没迁的」的 warn 降级成 debug ⇒ 判据判负",
                   silent_unmapped != mig
                   and "LOGGER.warn(" not in guard_window(
                       silent_unmapped,
                       "if (!legacyAreaFilters.isEmpty() && !legacyMigrationWarned) {"))

    # 反例：迁移用未 sanitize 的区域表 ⇒ 配置调小后下标错位，标记套到别的区域
    unsanitized = [a, c]
    sanitized = [a]                                            # c 被配置（体积/个数）排除
    outer4 = new_outer()
    migrate_by_index(outer4, unsanitized, {1: [marker("minecraft:gold_ingot")]})
    outer5 = new_outer()
    migrate_by_index(outer5, sanitized, {1: [marker("minecraft:gold_ingot")]})
    counterexample("②-26 迁移用未 sanitize 的区域表 ⇒ 被配置排除的区域吃掉了标记（模型判负）",
                   get_marks(outer4, c) != [] and get_marks(outer5, c) == [])

    # 源码级回归防线：本轮修掉的那个「整表丢弃」旧分支不许再回来
    old_has_any_branch = "if (AreaMarks.hasAny(stack)) {"
    check("②-27 源码：迁移方法里已无「物品有任意新标记 ⇒ 旧表整表丢弃」的旧分支",
          mig is not None and old_has_any_branch not in mig
          and "legacyAreaFilters.clear();\n            return;" not in mig)
    broken_mig = (mig or "").replace(
        "final AreaMarks.MergeResult result = AreaMarks.migrateByIndex(",
        old_has_any_branch + "\n            legacyAreaFilters.clear();\n            return;\n        }\n"
        "        final AreaMarks.MergeResult result = AreaMarks.migrateByIndex(")
    counterexample("②-28 把「有任意新标记就整表丢弃」塞回迁移方法 ⇒ 判据判负（回归防线有效）",
                   broken_mig != mig and old_has_any_branch in broken_mig)

    # 源码级反例：把无条件 clear 塞回迁移方法 ⇒ ②-13 判据判负
    clear_back = (mig or "").replace(
        "        final ItemStack stack = selectorStack();",
        "        legacyAreaFilters.clear();\n        final ItemStack stack = selectorStack();")
    counterexample("②-29 把无条件 `legacyAreaFilters.clear();` 塞回迁移方法 ⇒ ②-13 判据判负",
                   clear_back != mig and "legacyAreaFilters.clear();" in clear_back)


# ----------------------------------------------------------------------
# ③ AreaMarkEditPacket.handle 的服务端拒绝矩阵（源码锚点 + 逐条反例自证）
# ----------------------------------------------------------------------

GATES = [
    ("旁观者",
     "if (player.isSpectator())",
     lambda body, code: ("if (player.isSpectator())" in body
                         and body.index("player.isSpectator()")
                         < body.index("AreaSelectorTargets.resolve"))),
    ("目标物品解析为空",
     "if (target.isEmpty())",
     lambda body, code: ("if (target.isEmpty())" in body
                         and "AreaSelectorTargets.resolve(packet.containerId(), player)" in body)),
    ("维度不符",
     "!selection.dimension().equals(dimension)",
     lambda body, code: ("selection.dimension() != null" in body
                         and "!selection.dimension().equals(dimension)" in body)),
    ("区域不在选定器上（伪造坐标）",
     "if (area == null)",
     lambda body, code: ("if (area == null)" in body
                         and "findArea(selection, packet.area())" in body
                         # 几何匹配在 findArea 方法体里（handle 之外）⇒ 按整文件判
                         and "indexOfGeometry(candidate, -1)" in code)),
    ("ADD 容量已满",
     "entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY",
     lambda body, code: "entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY" in body),
    ("ADD 背包格为空/越界",
     "if (source.isEmpty())",
     lambda body, code: ("if (source.isEmpty())" in body
                         and "inventoryStack(player, packet.inventorySlot())" in body)),
    ("ADD 物品注册名为 null",
     "if (id == null)",
     lambda body, code: ("if (id == null)" in body
                         and "BuiltInRegistries.ITEM.getKey(source.getItem())" in body)),
    ("ADD 同 id 重复",
     "id.equals(entry.id())",
     lambda body, code: ("id.equals(entry.id())" in body and "!entry.fluid()" in body)),
    ("REMOVE 下标越界",
     "if (index < 0 || index >= entries.size())",
     lambda body, code: "if (index < 0 || index >= entries.size())" in body),
    ("未知 action",
     "default -> {",
     lambda body, code: ("default -> {" in body
                         and body.index("default -> {") > body.index("case ACTION_CLEAR"))),
    ("写后 sync",
     "AreaSelectorTargets.sync(player, packet.containerId())",
     lambda body, code: ("AreaSelectorTargets.sync(player, packet.containerId())" in body
                         and body.rindex("AreaSelectorTargets.sync(player, packet.containerId())")
                         > body.index("switch (packet.action())"))),
]


def check_rejection_matrix():
    raw = read(MARK_PACKET)
    code = strip_comments(raw)
    body = method_body(code, "public static void handle(final AreaMarkEditPacket packet, final ServerPlayer player)")
    check("③-0 handle(packet, ServerPlayer) 方法体取到（否则下面 11 门全部失效）", body is not None)

    for index, (label, required, predicate) in enumerate(GATES, start=1):
        anchor("③", MARK_PACKET, raw, required, label)
        check("③-%d 拒绝矩阵：%s" % (index, label), predicate(body or "", code))
        mutated = (body or "").replace(required, "")
        counterexample("③-%d 删掉「%s」这条闸门 ⇒ 判据必须判负" % (index, label),
                       mutated != body and predicate(mutated, code) is False)

    check("③-12 三个 action 常量 = 0/1/2（与客户端一致）",
          all(token in code for token in ("ACTION_ADD = 0", "ACTION_REMOVE = 1", "ACTION_CLEAR = 2")))
    check("③-13 客户端只发「背包格下标」，服务端自己取物品（不传 ItemStack）",
          "player.getInventory().getItem(slot)" in code and "ItemStack.STREAM_CODEC" not in code)


# ----------------------------------------------------------------------
# ④ 端到端吸收口径（模型）：A 标 iron 只吸 iron / B 空标记什么都不吸 / 清空立刻不吸
# ----------------------------------------------------------------------

AREA_A = (0, 64, 0, 10, 70, 10, "A")
AREA_B = (20, 64, 0, 30, 70, 10, "B")


def check_e2e_absorb():
    outer = new_outer()
    put_marks(outer, AREA_A, [marker("minecraft:iron_ingot")])
    # 区域 B 没有任何标记（物品上根本没有它的记录）
    drops = [
        {"pos": (1, 64, 1), "id": "minecraft:iron_ingot", "count": 1},    # A 内
        {"pos": (2, 64, 1), "id": "minecraft:gold_ingot", "count": 1},    # A 内
        {"pos": (21, 64, 1), "id": "minecraft:iron_ingot", "count": 1},   # B 内（B 没标记）
        {"pos": (100, 64, 100), "id": "minecraft:iron_ingot", "count": 1},  # 区域外
    ]
    got = absorb([AREA_A, AREA_B], outer, drops)
    check("④-1 区域 A 标了 iron ⇒ 只吸 iron，不吸 gold",
          [(drop["pos"], drop["id"]) for drop in got] == [((1, 64, 1), "minecraft:iron_ingot")])
    check("④-2 区域 B 空标记 ⇒ 什么都不吸（安全默认，不退回「全吸」）",
          all(drop["pos"] != (21, 64, 1) for drop in got))
    check("④-3 不在任何区域 ⇒ 不吸", all(drop["pos"] != (100, 64, 100) for drop in got))

    # 清空 A 之后：立刻一件都不吸（不能有陈旧缓存）
    snapshot_before_clear = {geom(AREA_A): tuple(get_marks(outer, AREA_A))}
    remove_marks(outer, AREA_A)
    got_after = absorb([AREA_A, AREA_B], outer, drops)
    check("④-4 清空区域 A 的标记 ⇒ 立刻一件不吸", got_after == [])
    stale = absorb([AREA_A, AREA_B], outer, drops, stale=snapshot_before_clear)
    counterexample("④-5 用清空前的陈旧快照 ⇒ 仍然吸 iron（模型判负，证明 ④-4 有判别力）",
                   len(stale) == 1 and stale[0]["id"] == "minecraft:iron_ingot")

    # 空标记被当成「不限制」的坏口径 ⇒ 会把 gold / 区域外全吸走
    def absorb_unrestricted(areas, drops_list):
        return [drop for drop in drops_list
                if first_area_for(areas, drop["pos"]) is not None]

    counterexample("④-6 空标记当「不限制」⇒ 区域 B 里的 iron 也被吸（模型判负）",
                   any(drop["pos"] == (21, 64, 1) for drop in absorb_unrestricted([AREA_A, AREA_B], drops)))

    # 同 tick 引用稳定：数量门槛必须按「同一区域的整组」累计，而不是单件
    outer2 = new_outer()
    put_marks(outer2, AREA_A, [marker("minecraft:iron_ingot", amount=2)])
    two_iron = [{"pos": (1, 64, 1), "id": "minecraft:iron_ingot", "count": 1},
                {"pos": (2, 64, 1), "id": "minecraft:iron_ingot", "count": 1}]
    stable_got = absorb([AREA_A], outer2, two_iron, stable=True)
    unstable_got = absorb([AREA_A], outer2, two_iron, stable=False)
    check("④-7 同 tick 引用稳定 ⇒ 两条 iron 合计 2 ≥ 门槛 2，整组被吸",
          len(stable_got) == 2)
    counterexample("④-8 每次取过滤集合都新建列表（引用不稳定）⇒ 门槛退化成单件、吸不到（模型判负）",
                   unstable_got == [] and stable_got)

    # 门槛没凑够 ⇒ 不吸（防止 ④-7 是「恒吸」）
    one_iron = [{"pos": (1, 64, 1), "id": "minecraft:iron_ingot", "count": 1}]
    check("④-9 只有 1 件、门槛 2 ⇒ 一件不吸（门槛真的生效）",
          absorb([AREA_A], outer2, one_iron) == [])

    # 重叠区域：先命中列表里第一个区域（Java `filtersForEntity` 逐区域 return）
    overlap_a = (0, 64, 0, 10, 70, 10, "空标记在前")
    overlap_c = (0, 64, 0, 10, 70, 10 + 0, "后")   # 同几何会被去重拦截；这里用相交盒子
    overlap_c = (5, 64, 5, 15, 70, 15, "有 iron 标记")
    outer3 = new_outer()
    put_marks(outer3, overlap_c, [marker("minecraft:iron_ingot")])
    overlap_drops = [{"pos": (6, 64, 6), "id": "minecraft:iron_ingot", "count": 1}]
    check("④-10 两个区域相交时按列表顺序「先命中先算」⇒ 第一个区域空标记 ⇒ 不吸（口径钉住）",
          absorb([overlap_a, overlap_c], outer3, overlap_drops) == [])
    check("④-11 同上但把有标记的区域排在前面 ⇒ 吸（证明 ④-10 不是「永远不吸」）",
          len(absorb([overlap_c, overlap_a], outer3, overlap_drops)) == 1)

    # 源码锚点：吸收侧遇到空集就 continue（不是「空 = 全吸」）
    raw_base = read(BASE_BE)
    base = strip_comments(raw_base)
    collect = method_body(base, "private void collectFromDroppedItems")
    anchor("④", BASE_BE, raw_base, "if (filters == null || filters.isEmpty()) {",
           "空过滤集合 ⇒ continue（安全默认）")
    anchor("④", BASE_BE, raw_base,
           "if (contexts.get(c) == filters) {", "吸收侧按引用相等分桶")
    check("④-12 源码：吸收侧「空集 ⇒ 不吸」与「按引用相等分桶」都在",
          collect is not None
          and "if (filters == null || filters.isEmpty()) {" in collect
          and "continue; // 不在任何生效区域 / 所在区域没配过滤" in raw_base
          and "if (contexts.get(c) == filters) {" in collect)

    raw_adv = read(ADV_BE)
    adv = strip_comments(raw_adv)
    filters_for = method_body(adv, "protected List<MarkerEntry> filtersForEntity")
    anchor("④", ADV_BE, raw_adv, "return marksFor(area);", "按实体所在区域取标记")
    anchor("④", ADV_BE, raw_adv, "return null; // 不在任何区域：不吸", "不在任何区域 ⇒ null")
    check("④-13 源码：filtersForEntity 先命中先 return marksFor(area)，不在任何区域 return null",
          filters_for is not None
          and "contains(pos.below()) || area.contains(pos.above())" in filters_for
          and "return marksFor(area);" in filters_for
          and filters_for.rindex("return null;") > filters_for.index("return marksFor(area);"))
    check("④-14 源码：空集走共享常量 NO_FILTERS（吸收侧分桶要求同一对象）",
          "private static final List<MarkerEntry> NO_FILTERS = List.of();" in adv
          and "return entries.isEmpty() ? NO_FILTERS : entries;" in adv)


# ----------------------------------------------------------------------
# ⑤ 物品携带性：标记只在选定器物品 CUSTOM_DATA，换机器仍可读
# ----------------------------------------------------------------------

def check_portability():
    a = (0, 64, 0, 10, 70, 10, "A")
    outer = new_outer()
    put_marks(outer, a, [marker("minecraft:iron_ingot")])

    # 换机器 = 同一份物品 NBT 交给另一台方块实体的 marksFor（物品是唯一权威）
    def machine_marks(_machine_id, stack_nbt, area):
        return get_marks(stack_nbt, area)

    check("⑤-1 换机器：同一份选定器 NBT 在另一台机器上仍读到 iron",
          [e["id"] for e in machine_marks("machine#2", outer, a)] == ["minecraft:iron_ingot"])
    check("⑤-2 存档/掉落序列化后再读（深拷贝，不依赖对象身份）",
          [e["id"] for e in machine_marks("machine#3", copy_outer(outer), a)] == ["minecraft:iron_ingot"])

    raw_adv = read(ADV_BE)
    adv = strip_comments(raw_adv)
    raw_marks = read(AREA_MARKS)
    marks = strip_comments(raw_marks)
    save = method_body(adv, "public void saveAdditional")
    load = method_body(adv, "public void loadAdditional")

    anchor("⑤", AREA_MARKS, raw_marks, "DataComponents.CUSTOM_DATA", "标记存在物品 CUSTOM_DATA")
    anchor("⑤", ADV_BE, raw_adv, "tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(registries));",
           "标记的唯一间接载体 = 选定器槽里的物品 NBT")
    anchor("⑤", ADV_BE, raw_adv, 'private static final String TAG_AREA_FILTERS = "AreaFilters";',
           "BE 侧只剩旧档 AreaFilters 键")
    check("⑤-3 源码：标记走物品 CUSTOM_DATA（DataComponents.CUSTOM_DATA / CustomData）",
          "DataComponents.CUSTOM_DATA" in marks and "CustomData" in marks)
    check("⑤-4 源码：BE 不写独立的区域标记表（saveAdditional/loadAdditional 无 RsccAreaMarks 字面量）",
          "RsccAreaMarks" not in adv)
    check("⑤-5 源码：BE 存档里唯一的标记载体是选定器槽（TAG_AREA_SLOT）+ 旧档 AreaFilters",
          save is not None and load is not None
          and "tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(registries));" in save
          and "tag.put(TAG_AREA_FILTERS, filters);" in save
          and "TAG_AREA_FILTERS, Tag.TAG_COMPOUND" in load)
    check("⑤-6 源码：区域删除/改绑后修剪标记（AreaSelectorEditPacket 两处 prune）",
          strip_comments(read(EDIT_PACKET)).count("AreaMarks.prune(target,") == 2)

    # 模型：区域删除后修剪 —— 只剪那一个几何，其余不动
    b = (20, 64, 0, 30, 70, 10, "B")
    put_marks(outer, b, [marker("minecraft:coal")])
    prune_marks(outer, [a])
    check("⑤-7 模型：区域 B 被删 ⇒ 只剪 B 的标记，A 仍可读",
          get_marks(outer, b) == []
          and [e["id"] for e in get_marks(outer, a)] == ["minecraft:iron_ingot"])

    # 反例：键里混进「机器 ID / 机器坐标」⇒ 换机器就丢标记
    def machine_scoped_key(machine_id, area):
        return (machine_id,) + geom(area)

    scoped = {machine_scoped_key("machine#1", a): ["minecraft:iron_ingot"]}
    counterexample("⑤-8 键含机器身份 ⇒ 换机器读不到标记（模型判负）",
                   scoped.get(machine_scoped_key("machine#2", a)) is None)

    # 反例：标记存在机器本地（旧实现）⇒ 换机器读不到、且 BE 存档里会出现独立表
    machine_local = {"machine#1": {geom(a): [marker("minecraft:iron_ingot")]}}
    counterexample("⑤-9 标记存机器本地（旧实现）⇒ 换机器即丢（模型判负）",
                   machine_local.get("machine#2") is None)


def copy_outer(outer):
    """模拟存档/掉落的序列化-反序列化（深拷贝，保持 NBT 结构，不保持对象身份）。"""
    import copy
    return copy.deepcopy(outer)


# ----------------------------------------------------------------------
# ⑥ 容量与畸形 NBT 边界
# ----------------------------------------------------------------------

def check_capacity_and_malformed():
    a = (0, 64, 0, 10, 70, 10, "A")

    many = [marker("minecraft:i%d" % index) for index in range(400)]
    outer = new_outer()
    put_marks(outer, a, many)
    kept = [entry["id"] for entry in get_marks(outer, a)]
    check("⑥-1 单区域写 400 条 ⇒ 只保留前 %d 条（顺序保持）" % MATCH_CAPACITY,
          len(kept) == MATCH_CAPACITY and kept == ["minecraft:i%d" % i for i in range(MATCH_CAPACITY)])

    # 第 N+1 条被拒（packet 侧 / 机器侧）⇒ 已有 N 条一条都不动、顺序不变
    def try_add(outer_nbt, area, new_entry):
        """对应 handle 的 ACTION_ADD：容量满 ⇒ return（不写）；否则走 AreaMarks.put。"""
        entries = list(get_marks(outer_nbt, area))
        if len(entries) >= MATCH_CAPACITY:
            return False
        put_marks(outer_nbt, area, entries + [new_entry])
        return True

    before = [entry["id"] for entry in get_marks(outer, a)]
    rejected = not try_add(outer, a, marker("minecraft:overflow"))
    after = [entry["id"] for entry in get_marks(outer, a)]
    check("⑥-2 容量满后再加一条 ⇒ 被拒且已有 %d 条不被破坏、顺序不变" % MATCH_CAPACITY,
          rejected and before == after and len(after) == MATCH_CAPACITY
          and "minecraft:overflow" not in after)

    raw_mark = read(MARK_PACKET)
    raw_adv = read(ADV_BE)
    adv = strip_comments(raw_adv)
    anchor("⑥", MARK_PACKET, raw_mark,
           "if (entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {", "标记包容量闸门")
    anchor("⑥", ADV_BE, raw_adv,
           "if (entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {", "机器侧容量闸门")
    add_marker = method_body(adv, "public MarkerAddResult addAreaMarker")
    anchor("⑥", ADV_BE, raw_adv, "return MarkerAddResult.FULL;", "机器侧容量满的返回码")
    check("⑥-3 源码：机器侧 addAreaMarker 到上限返回 FULL（不是静默丢弃）",
          add_marker is not None and "MATCH_CAPACITY" in add_marker
          and "return MarkerAddResult.FULL;" in add_marker)

    # 手改 NBT 塞 400 条 ⇒ 读侧也截断（防恶意 NBT 撑爆内存 / 界面卡死）
    hand_edited = {TAG_ROOT: {TAG_MARKS: [{
        "Area": list(geom(a)),
        "Entries": [_encode_marker(marker("minecraft:x%d" % i), i) for i in range(400)],
    }]}}
    check("⑥-4 读档遇到 400 条（手改 NBT）⇒ 读侧截到 %d 条，不抛" % MATCH_CAPACITY,
          len(get_marks(hand_edited, a)) == MATCH_CAPACITY)

    # 区域数上限：写 70 个不同区域 ⇒ all() 只返回前 64 个、顺序不变
    wide = new_outer()
    areas = [(index * 100, 64, 0, index * 100 + 5, 70, 5, "r%d" % index) for index in range(70)]
    for area in areas:
        put_marks(wide, area, [marker("minecraft:stone")])
    listed = list(all_marks(wide))
    check("⑥-5 写 %d 个区域 ⇒ all() 只返回前 %d 个（读侧硬上限），顺序保持"
          % (len(areas), MAX_AREAS),
          listed == [geom(area) for area in areas[:MAX_AREAS]])
    check("⑥-6 区域数超限不会打乱已有顺序（列表前 %d 项完全一致）" % MAX_AREAS,
          listed[:10] == [geom(area) for area in areas[:10]])

    selection_code = strip_comments(read(AREA_SELECTION))
    raw_selection = read(AREA_SELECTION)
    anchor("⑥", AREA_SELECTION, raw_selection, "public static final int ABSOLUTE_MAX_AREAS = 64;",
           "区域数绝对上限")
    anchor("⑥", AREA_SELECTION, raw_selection,
           "return Math.max(1, Math.min(ABSOLUTE_MAX_AREAS, Config.areaSelectorMaxAreas));",
           "写侧夹取到绝对上限")
    check("⑥-7 源码：写侧 maxAreas() 夹到 ABSOLUTE_MAX_AREAS（写不进第 65 个区域）",
          "Math.min(ABSOLUTE_MAX_AREAS, Config.areaSelectorMaxAreas)" in selection_code)

    # ---- 畸形 NBT：读返回空、绝不抛异常 ----
    def safe_get(outer_nbt):
        try:
            return get_marks(outer_nbt, a), True
        except Exception:  # pragma: no cover - 真抛了就是缺陷
            return [], False

    cases = [
        ("⑥-8 根不是 compound（字符串）", {TAG_ROOT: "garbage"}),
        ("⑥-9 Marks 不是 list（字符串）", {TAG_ROOT: {TAG_MARKS: "garbage"}}),
        ("⑥-10 Entries 不是 list（字符串）",
         {TAG_ROOT: {TAG_MARKS: [{"Area": list(geom(a)), "Entries": "garbage"}]}}),
        ("⑥-11 Entries 元素不是 compound（int list）",
         {TAG_ROOT: {TAG_MARKS: [{"Area": list(geom(a)), "Entries": [1, 2, 3]}]}}),
        ("⑥-12 Area 长度 ≠ 6",
         {TAG_ROOT: {TAG_MARKS: [{"Area": [0, 64, 0, 10, 70], "Entries": []}]}}),
        ("⑥-13 Area 不是 int array（字符串）",
         {TAG_ROOT: {TAG_MARKS: [{"Area": "0,64,0,10,70,10", "Entries": []}]}}),
        ("⑥-14 Marks 元素不是 compound（int list）", {TAG_ROOT: {TAG_MARKS: [1, 2]}}),
        ("⑥-15 整个 CUSTOM_DATA 为空 dict", {}),
    ]
    for name, malformed in cases:
        got, ok = safe_get(malformed)
        check("%s ⇒ 读空且不抛异常" % name, ok and got == [], "got=%r" % (got,))

    # 单条畸形（Id 非法 / Amount 缺失 / 0 / 负数）不影响同表里的好条目
    mixed = {TAG_ROOT: {TAG_MARKS: [{
        "Area": list(geom(a)),
        "Entries": [
            _encode_marker(marker("minecraft:iron_ingot"), 0),
            {"Id": "not a id!!", "Amount": 1},
            {"Id": "minecraft:coal", "Amount": 0},
            {"Id": "minecraft:diamond", "Amount": -5},
            {},
        ],
    }]}}
    decoded = get_marks(mixed, a)
    check("⑥-16 单条畸形只丢那条：非法 Id / 空条目被跳过，好条目保留",
          [entry["id"] for entry in decoded]
          == ["minecraft:iron_ingot", "minecraft:coal", "minecraft:diamond"])
    check("⑥-17 Amount 缺失 / 0 / 负数一律归一为 1（decode 的 Math.max(1L, ...)）",
          [entry["amount"] for entry in decoded] == [1, 1, 1])

    # 畸形取值矩阵：4 个层级 × 10 种垃圾值，读路径全部不抛、get 返回 list、all 返回 dict
    garbage_values = [None, 0, 1, 2 ** 40, "x", "", [], {}, [1, 2], {"A": 1}, [{}], [[{}]], 1.5, True]
    fuzz_ok = True
    fuzz_detail = ""
    for garbage in garbage_values:
        shapes = [
            {TAG_ROOT: garbage},
            {TAG_ROOT: {TAG_MARKS: garbage}},
            {TAG_ROOT: {TAG_MARKS: [garbage]}},
            {TAG_ROOT: {TAG_MARKS: [{"Area": garbage, "Entries": garbage}]}},
            {TAG_ROOT: {TAG_MARKS: [{"Area": list(geom(a)), "Entries": [garbage]}]}},
        ]
        for shape in shapes:
            try:
                got = get_marks(shape, a)
                listed = all_marks(shape)
                if not isinstance(got, list) or not isinstance(listed, dict):
                    fuzz_ok = False
                    fuzz_detail = repr(shape)[:80]
            except Exception as error:      # pragma: no cover - 真抛了就是缺陷
                fuzz_ok = False
                fuzz_detail = "%r -> %r" % (shape, error)
    check("⑥-18 畸形取值矩阵（%d 种垃圾 × 5 种层级 = %d 组）读路径全部不抛、类型正确"
          % (len(garbage_values), len(garbage_values) * 5),
          fuzz_ok, fuzz_detail)

    # 反例：超限截断用 sorted ⇒ 顺序被打乱（i10 会排到 i2 前面）
    sorted_kept = sorted(["minecraft:i%d" % i for i in range(400)])[:MATCH_CAPACITY]
    counterexample("⑥-19 超限截断改用排序 ⇒ 前 %d 条顺序被打乱（与 ⑥-1 的判据判负）"
                   % MATCH_CAPACITY,
                   sorted_kept != ["minecraft:i%d" % i for i in range(MATCH_CAPACITY)])

    # 反例：区域数上限保留「最后 64 个」⇒ 顺序/包含关系错
    last64 = [geom(area) for area in areas[-MAX_AREAS:]]
    counterexample("⑥-20 区域数上限保留「最后 %d 个」⇒ 与 ⑥-5 判据判负" % MAX_AREAS,
                   last64 != listed)


# ----------------------------------------------------------------------
# ⑦ 第 113 轮复核修复（独立复核报的 3 高危 + 3 中危 + 附带项）
# ----------------------------------------------------------------------

def check_review_fixes():
    raw_edit = read(EDIT_PACKET)
    edit = strip_comments(raw_edit)
    raw_marks = read(AREA_MARKS)
    marks = strip_comments(raw_marks)
    raw_packet = read(MARK_PACKET)
    packet = strip_comments(raw_packet)
    raw_screen = read(MARK_SCREEN)
    screen = strip_comments(raw_screen)
    raw_base = read(BASE_BE)
    base = strip_comments(raw_base)
    raw_be = read(ADV_BE)
    be = strip_comments(raw_be)
    zh = json.loads(read(ZH))
    en = json.loads(read(EN))

    handle = method_body(edit, "public static void handle(final AreaSelectorEditPacket packet, "
                               "final ServerPlayer player)")
    replace_block = method_body(edit, "case ACTION_REPLACE -> {")

    # ================= ⑦-A REPLACE 换几何时把旧几何的标记搬过去 =================
    anchor("⑦", EDIT_PACKET, raw_edit, "List<MarkerEntry> carriedMarks = List.of();",
           "switch 前声明 carriedMarks")
    # 第 124 轮（124-B / 2-1）：REPLACE 改成「先取 previous（旧几何 + 旧名字）再 replace」，
    # 既保证搬运读的是旧几何，也让「只改几何」不再顺手抹掉区域名字。锚点跟着换成新写法。
    anchor("⑦", EDIT_PACKET, raw_edit,
           "carriedMarks = AreaMarks.get(target, previous,",
           "REPLACE：replace 之前读旧几何的标记")
    anchor("⑦", EDIT_PACKET, raw_edit, "AreaMarks.put(target, newArea, carriedMarks);",
           "新几何为空 ⇒ 直接搬过去")
    anchor("⑦", EDIT_PACKET, raw_edit, "kept.id().equals(entry.id())",
           "新几何已有标记 ⇒ 按 (id, fluid) 去重合并")

    def replace_carries_marks_and_name(code):
        """⑦-2 的判据本体（第 124 轮扩展）：REPLACE 必须①在 replace 前读旧几何的标记，
        ②不抹掉区域名字（名字唯一写入口是 ACTION_RENAME）。"""
        block = method_body(code, "case ACTION_REPLACE -> {")
        if block is None:
            return False
        return ("final Area previous = selection.areas().get(index);" in block
                and "carriedMarks = AreaMarks.get(target, previous," in block
                and "area.name() == null ? area.withName(previous.name()) : area" in block
                and "selection = selection.replace(index," in block
                and block.index("carriedMarks = AreaMarks.get(")
                < block.index("selection = selection.replace(index,"))

    check("⑦-1 carriedMarks 在 switch 之前声明（默认空表）",
          handle is not None
          and "List<MarkerEntry> carriedMarks = List.of();" in handle
          and handle.index("List<MarkerEntry> carriedMarks = List.of();")
          < handle.index("switch (packet.action())"))
    check("⑦-2 REPLACE 在 selection.replace( 之前读「旧几何」的标记，且不抹掉区域名字"
          "（几何是键 ⇒ 换框不能清空过滤；名字只能由 RENAME 改）",
          replace_block is not None
          and replace_carries_marks_and_name(edit))
    check("⑦-3 写回物品后把标记搬到新几何（新几何空 ⇒ 直接 put，且在 AreaSelection.set 之后）",
          handle is not None
          and "if (!carriedMarks.isEmpty()) {" in handle
          and "AreaMarks.put(target, newArea, carriedMarks);" in handle
          and handle.index("AreaSelection.set(target, selection);")
          < handle.index("if (!carriedMarks.isEmpty()) {"))
    check("⑦-4 新几何已有标记 ⇒ 按 (id, fluid) 去重合并、绝不覆盖已有条目",
          handle is not None
          and "kept.id().equals(entry.id())" in handle
          and "kept.fluid() == entry.fluid()" in handle
          and "AreaMarks.put(target, newArea, merged);" in handle)
    broken_carry = (edit or "").replace(
        "carriedMarks = AreaMarks.get(target, previous,", "")
    counterexample("⑦-5 删掉 REPLACE 前的搬运 ⇒ 判据判负（改一下框就把这个区域的过滤清空）",
                   broken_carry != edit
                   and not replace_carries_marks_and_name(broken_carry))
    # 第 124 轮新增反例：把「保留旧名字」退回裸 replace(index, area) ⇒ 同一条判据必须判负
    # （原意「REPLACE 不得吞掉另一处数据」里的「名字」那一半，正是本轮 2-1 的根因）。
    broken_name = (edit or "").replace(
        "area.name() == null ? area.withName(previous.name()) : area", "area")
    counterexample("⑦-6 把 REPLACE 退回裸覆盖（丢掉区域名字）⇒ 判据判负"
                   "（「改一下坐标名字就没了」= 用户 2-1 的成因之一）",
                   broken_name != edit
                   and not replace_carries_marks_and_name(broken_name))

    def carry_replace(store, old_area, new_area, capacity=None):
        """REPLACE 搬运孪生：读旧几何 → 读新几何 → 按 (id, fluid) 去重合并 → 旧几何剪掉。"""
        carried = list(get_marks(store, old_area, capacity))
        merged = list(get_marks(store, new_area, capacity))
        for entry in carried:
            if not entry or not entry.get("id"):
                continue
            if not any(kept.get("id") == entry["id"]
                       and bool(kept.get("fluid")) == bool(entry.get("fluid"))
                       for kept in merged if kept):
                merged.append(entry)
        put_marks(store, old_area, [], capacity)      # 旧几何随后被 prune 掉
        if merged:
            put_marks(store, new_area, merged, capacity)
        return merged

    old_area = (0, 64, 0, 5, 70, 5, "旧框")
    new_area = (100, 64, 0, 105, 70, 5, "新框")
    outer7 = new_outer()
    put_marks(outer7, old_area, [marker("minecraft:iron_ingot", amount=3),
                                 marker("minecraft:coal"),
                                 marker("minecraft:water", fluid=True)])
    carried = carry_replace(outer7, old_area, new_area)
    check("⑦-6 模型：旧几何 3 条 → 新几何为空 ⇒ 3 条原样搬过去（amount / fluid 不丢）",
          len(carried) == 3
          and [e["id"] for e in get_marks(outer7, new_area)]
          == ["minecraft:iron_ingot", "minecraft:coal", "minecraft:water"]
          and get_marks(outer7, old_area) == []
          and get_marks(outer7, new_area)[0]["amount"] == 3)

    outer8 = new_outer()
    put_marks(outer8, old_area, [marker("minecraft:iron_ingot", amount=5),
                                 marker("minecraft:coal")])
    put_marks(outer8, new_area, [marker("minecraft:iron_ingot", amount=1)])
    merged8 = carry_replace(outer8, old_area, new_area)
    check("⑦-7 模型：新几何已有 1 条同 id ⇒ 去重合并、保留已有那条（amount 不被覆盖），总数 2",
          len(merged8) == 2 and merged8[0]["amount"] == 1
          and [e["id"] for e in get_marks(outer8, new_area)]
          == ["minecraft:iron_ingot", "minecraft:coal"])

    outer9 = new_outer()
    put_marks(outer9, old_area, [marker("minecraft:iron_ingot"), marker("minecraft:coal")])
    prune_marks(outer9, [new_area])            # 模拟「忘了搬运、直接按新区域表剪」
    counterexample("⑦-8 忘了搬运 ⇒ 改框后新几何一条标记都没有（⑦-6 判据判负）",
                   get_marks(outer9, new_area) == [] and get_marks(outer9, old_area) == [])

    # ================= ⑦-B 主手路径被拒也要广播真状态 =================
    reject_body = method_body(edit, "private static void reject(")
    check("⑦-9 主手路径被拒（menu == null）也 broadcastFullState（客户端乐观副本不残留）",
          reject_body is not None
          and "if (menu == null) {" in reject_body
          and "player.inventoryMenu.broadcastFullState();" in reject_body)
    broken_reject = (reject_body or "").replace(
        "player.inventoryMenu.broadcastFullState();", "")
    counterexample("⑦-10 删掉主手路径的 broadcastFullState ⇒ 判据判负（客户端乐观副本残留）",
                   broken_reject != reject_body
                   and "player.inventoryMenu.broadcastFullState();" not in broken_reject)

    # ================= ⑦-C prune：null 早退 + 遍历原始列表 =================
    prune_body = method_body(marks, "public static void prune(")
    anchor("⑦", AREA_MARKS, raw_marks, "selection == null) {",
           "prune：selection == null 直接早退")
    anchor("⑦", AREA_MARKS, raw_marks,
           "int doomed = 0;",
           "prune：先数要剪的记录（第 114 轮复核 L2：一次 update 批量重建）")
    check("⑦-11 prune：selection == null 直接早退（public API 不再是「清空陷阱」）",
          prune_body is not None and "selection == null) {" in prune_body)
    check("⑦-12 prune：遍历原始 Marks 列表（不再走 all() 的 64 上限，超上限幽灵记录也能剪掉）",
          prune_body is not None
          and "root.getList(TAG_MARKS, Tag.TAG_COMPOUND)" in prune_body
          and "all(stack)" not in prune_body
          and "if (root == null) {" in prune_body)

    model_outer = new_outer()
    put_marks(model_outer, old_area, [marker("minecraft:iron_ingot")])
    prune_marks(model_outer, None)
    broken_outer = new_outer()
    put_marks(broken_outer, old_area, [marker("minecraft:iron_ingot")])
    for key in list(all_marks(broken_outer)):
        remove_marks(broken_outer, key)          # 旧实现：null ⇒ keep 空 ⇒ 全剪
    counterexample("⑦-13 把 prune(null) 当「全部都不存在」⇒ 整件物品的标记被一次清空（判负）",
                   get_marks(model_outer, old_area) != []
                   and get_marks(broken_outer, old_area) == [])

    many_areas = [(index * 100, 64, 0, index * 100 + 5, 70, 5, "r%d" % index)
                  for index in range(MAX_AREAS + 6)]           # 70 个区域 > 64 上限
    wide_outer = new_outer()
    for area in many_areas:
        put_marks(wide_outer, area, [marker("minecraft:stone")])
    prune_marks(wide_outer, many_areas[:MAX_AREAS - 4])        # 只保留前 60 个
    check("⑦-14 模型：prune 用原始列表 ⇒ 第 65–70 条（all() 看不到的幽灵记录）也被剪掉",
          len(all_marks(wide_outer)) == MAX_AREAS - 4
          and all(geom(area) in all_marks(wide_outer)
                  for area in many_areas[:MAX_AREAS - 4]))

    old_prune_outer = new_outer()
    for area in many_areas:
        put_marks(old_prune_outer, area, [marker("minecraft:stone")])
    old_keep = {geom(area) for area in many_areas[:MAX_AREAS - 4]}
    for key in [k for k in all_marks(old_prune_outer) if k not in old_keep]:   # all() 只给 64 条
        remove_marks(old_prune_outer, key)
    counterexample("⑦-15 prune 用 all()（受 64 上限）⇒ 第 65–70 条幽灵记录永远剪不掉（判负）",
                   len(_marks_list(_root(old_prune_outer))) == MAX_AREAS + 2)

    # ================= ⑦-D 单条标记字节上限（两处调用点） =================
    # 第 114 轮：权威常量搬到 MarkerBudget，AreaMarks 改为**委托**（判据同步到新写法，不削弱）
    anchor("⑦", AREA_MARKS, raw_marks, "public static final int MAX_MARKER_BYTES = MarkerBudget.MAX_ENTRY_BYTES;",
           "单条标记字节上限：委托 MarkerBudget（第 114 轮）")
    anchor("⑦", AREA_MARKS, raw_marks, "public static boolean isOversized(",
           "isOversized 判据（委托 MarkerBudget.isEntryTooBig）")
    check("⑦-16 MAX_MARKER_BYTES / isOversized 已改为委托 MarkerBudget（不再有第二份 2048 字面量）",
          "public static final int MAX_MARKER_BYTES = MarkerBudget.MAX_ENTRY_BYTES;" in marks
          and "public static boolean isOversized(" in marks
          and "return MarkerBudget.isEntryTooBig(entry);" in marks
          and marks.count("= 2048;") == 0)
    check("⑦-17 服务端兜底：先构造 candidate 再 isOversized 拒绝（客户端可绕过）",
          "final MarkerEntry candidate = MarkerEntry.item(source," in packet
          and "if (AreaMarks.isOversized(candidate)) {" in packet
          and packet.index("final MarkerEntry candidate")
          < packet.index("if (AreaMarks.isOversized(candidate)) {"))
    check("⑦-18 客户端预检：AreaMarkScreen 先把 candidate 构造一次再 isOversized，并提示 notice.too_complex",
          "final MarkerEntry candidate = MarkerEntry.item(source," in screen
          and "if (AreaMarks.isOversized(candidate)) {" in screen
          and "notice.too_complex" in screen
          and "showNotice(" in screen)
    too_complex_key = "gui.rs_create_compat.area_mark.notice.too_complex"
    volume_key = "gui.rs_create_compat.area_mark.volume"
    check("⑦-19 语言键 notice.too_complex / volume 中英成对、非空、占位符个数一致",
          too_complex_key in zh and too_complex_key in en
          and volume_key in zh and volume_key in en
          and str(zh[too_complex_key]).strip() and str(en[too_complex_key]).strip()
          and str(zh[volume_key]).strip() and str(en[volume_key]).strip()
          and str(zh[volume_key]).count("%s") == str(en[volume_key]).count("%s"))
    broken_packet = packet.replace("if (AreaMarks.isOversized(candidate)) {", "")
    counterexample("⑦-20 删掉服务端 isOversized 兜底 ⇒ 判据判负（大 NBT 标记能写进选定器）",
                   broken_packet != packet
                   and "if (AreaMarks.isOversized(candidate)) {" not in broken_packet)

    # ================= ⑦-E 客户端 targetStack：菜单路径绝不回退主手 =================
    stack_fn = method_body(screen, "private ItemStack targetStack()")
    fallback_split = "final ItemStack mainHand = minecraft.player.getMainHandItem();"
    head = stack_fn[:stack_fn.index(fallback_split)] if (
        stack_fn is not None and fallback_split in stack_fn) else (stack_fn or "")
    anchor("⑦", MARK_SCREEN, raw_screen, "if (containerId >= 0) {",
           "targetStack：菜单路径分支")
    anchor("⑦", MARK_SCREEN, raw_screen,
           "            return ItemStack.EMPTY;\n        }\n        final ItemStack mainHand",
           "targetStack：菜单 id 对不上 ⇒ 返回空（不回退主手）")
    check("⑦-21 targetStack：菜单路径（containerId >= 0）内不再出现 getMainHandItem，对不上就返回空",
          stack_fn is not None
          and "if (containerId >= 0) {" in head
          and "return ItemStack.EMPTY;" in head
          and "getMainHandItem" not in head)
    broken_stack = (stack_fn or "").replace(
        "            return ItemStack.EMPTY;\n        }\n        final ItemStack mainHand",
        "            return minecraft.player.getMainHandItem();\n        }\n"
        "        final ItemStack mainHand")
    broken_head = broken_stack[:broken_stack.index(fallback_split)] if (
        fallback_split in broken_stack) else broken_stack
    counterexample("⑦-22 菜单路径恢复「回退主手」⇒ 判据判负（界面显示主手、服务端改的是菜单槽）",
                   broken_stack != stack_fn and "getMainHandItem" in broken_head)

    # ================= ⑦-F decodeMarkerTag：无注册表上下文不 NPE =================
    decode_body = method_body(base, "public static MarkerEntry decodeMarkerTag")
    anchor("⑦", BASE_BE, raw_base, "if (registries == null) {",
           "decodeMarkerTag 旧档 Item 分支：无 registries 直接返回 null")
    check("⑦-23 decodeMarkerTag 旧档 Item 分支：registries == null 早退（parseOptional(null) 会 NPE）",
          decode_body is not None
          and "if (registries == null) {" in decode_body
          and decode_body.index("TAG_MARKER_ITEM") < decode_body.index("if (registries == null) {")
          < decode_body.index("ItemStack.parseOptional(registries"))
    broken_decode = (decode_body or "").replace("if (registries == null) {", "if (false) {")
    counterexample("⑦-24 删掉 registries == null 早退 ⇒ 判据判负（客户端无注册表上下文时 NPE）",
                   broken_decode != decode_body
                   and "if (registries == null) {" not in broken_decode)

    # ================= ⑦-G 复核附带：迁移 WARN 节流字段存在 =================
    anchor("⑦", ADV_BE, raw_be, "private boolean legacyMigrationWarned;",
           "「未映射条目」WARN 节流字段")
    check("⑦-25 BE 有 legacyMigrationWarned 节流字段（第三次 WARN 不随每次重试刷屏）",
          "private boolean legacyMigrationWarned;" in be
          and "!legacyMigrationWarned" in be)


# ======================================================================
# 主流程
# ======================================================================

def main():
    print("=" * 78)
    print("第 113 轮自检 B 组：区域标记「随身带」端到端行为模型（配置 → 机器真的按它吸收）")
    print("=" * 78)
    check_constants()
    check_roundtrip()
    check_migration()
    check_rejection_matrix()
    check_e2e_absorb()
    check_portability()
    check_capacity_and_malformed()
    check_review_fixes()

    print("-" * 78)
    print("源码锚点清单（file:line）：")
    for group, path, line, note in ANCHORS:
        print("  [%s] %s:%d  %s" % (group, path, line, note))
    print("-" * 78)
    print("检查 %d 项（其中反例自证 %d 项），失败 %d 项" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        for name in FAILS:
            print("  FAILED: " + name)
        return 1
    print("round113 OK 断言 %d / 反例 %d" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
