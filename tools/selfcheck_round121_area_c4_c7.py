#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 121 轮自检：区域标记家族的三条（C-4 数据槽上限 16→64 / C-7 实体查询盒按簇拆分 / C-5 后续 tooltip）。

背景（只读审计 docs/ROUND119_AUDIT_AREA_MARKS.md §C-4 / §C-7 / §C-5 后续）
-----------------------------------------------------------------------
* C-4：`menu/AdvancedCollectionCacheMenu` 的 `AREA_LIST_SLOT_MAX = 16`，而 `Config.areaSelectorMaxAreas`
  允许到 64（`AreaSelection.ABSOLUTE_MAX_AREAS` 也是 64）。管理员把配置调到 17..64 后，
  第 17 块及以后区域的「有效过滤条目数」永远从数据槽读回 0 —— 过滤本身照常生效，界面在说谎。
  本轮拍板：数据槽数量抬到硬上限 64（+48 个 INT），并把「越界 return 0」的闸门留在 64 之外。
* C-5 后续：`item/AreaSelectorItem` 的 tooltip 计数走两参 `AreaMarks.all(stack)`（= registries null），
  而 `decodeMarkerTag` 在 registries 为 null 时会**跳过旧档整份 ItemStack（Item 键）条目**
  ⇒ 计数偏少。改成带注册表的重载；拿不到注册表时保守按 0，**绝不回退两参版**（回退就是 bug 本身）。
  按内容指纹删除（要改 StreamCodec 线格式）本轮不做，只在注释里留待办。
* C-7：`AreaCursor` 对**全部区域**求一个并集 AABB，实体查询（掉落物 / 经验球）各查一次。
  两块区域相隔 1000 格 ⇒ 查询盒跨度 1000+ 格，`getEntitiesOfClass` 白扫中间上千个空区块段。
  本轮做方案 a：按簇贪心聚类（跨度 / 体积阈值），每簇一个 AABB + 一次查询；
  区域全相邻时必须退化成一个盒，行为与改动前逐字一致。

纪律：不跑 gradle、不改存档格式 / 网络线格式、不改语言文件。
用法：python tools/selfcheck_round121_area_c4_c7.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
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

ADV_MENU = os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java")
BASE_MENU = os.path.join(JAVA, "menu", "CollectionCacheMenu.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
BASE_BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
HOOK = os.path.join(JAVA, "support", "AreaScanHook.java")
AREA = os.path.join(JAVA, "support", "Area.java")
AREA_SELECTION = os.path.join(JAVA, "support", "AreaSelection.java")
SELECTOR_ITEM = os.path.join(JAVA, "item", "AreaSelectorItem.java")
CONFIG = os.path.join(JAVA, "Config.java")

#: 原版容器数据同步的保守包体上限（真实协议上限远大于它：即便把 102 个数据槽全塞进一个包，
#: 也只有 102×4 = 408 字节 + 包头，远小于 32767）。这里只用它做「会不会超包」的数量级判断。
SAFE_PACKET_BYTES = 32767

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


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


# ======================================================================================
# 源码解析小工具（与第 120 轮同一套：去注释 / 取方法体；避免再造一份不同口径的判据）
# ======================================================================================

def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性」判据。"""
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


def method_body(code, hint):
    """从（已去注释的）源码里取一个方法体：从 hint 起找到第一个 '{' 并配平。"""
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


def int_constant(code, name):
    """`<name> = 123;` 这种 int 字面量常量（解析不到返回 None）。"""
    match = re.search(r"\b%s\s*=\s*(\d+)\s*;" % re.escape(name), code)
    return int(match.group(1)) if match else None


def eval_java_expr(expr, max_side):
    """在 python 里求 java 常量表达式（只处理本自检需要的 `2.0D * Area.MAX_SIDE` / `(long) ...`）。"""
    text = expr.strip()
    text = text.replace("Area.MAX_SIDE", str(max_side))
    text = re.sub(r"\((?:long|double|int|float)\)", "", text)
    text = re.sub(r"(\d(?:\.\d+)?)[dDfFlL]\b", r"\1", text)
    return eval(text, {"__builtins__": {}}, {})  # noqa: S307 - 只喂源码里解析出的数字表达式


def cluster_thresholds(adv_code, area_code):
    """从源码解析 C-7 的两个簇阈值（跨度 / 体积）与单区域单边上限。"""
    max_side = int_constant(area_code, "MAX_SIDE")
    span_match = re.search(r"AREA_CLUSTER_MAX_SPAN\s*=\s*([^;]+);", adv_code)
    vol_match = re.search(r"AREA_CLUSTER_MAX_VOLUME\s*=\s*([^;]+);", adv_code)
    if max_side is None or not span_match or not vol_match:
        return None
    span = eval_java_expr(span_match.group(1), max_side)
    volume = eval_java_expr(vol_match.group(1), max_side)
    return {"max_side": max_side, "max_span": float(span), "max_volume": float(volume)}


# ======================================================================================
# ① 源码锚点（判据都写成纯函数，反例自证时直接喂「改坏的源码副本」）
# ======================================================================================

def c4_slots_ok(menu_code, base_menu_code, area_selection_code, config_code):
    """C-4：数据槽上限 = 64（= 硬上限），数量 / 布局 / 越界闸门 / 同步包体全部自洽。"""
    list_max = int_constant(menu_code, "AREA_LIST_SLOT_MAX")
    if list_max != 64:
        return False
    # 不许残留写死的 16
    if re.search(r"AREA_LIST_SLOT_MAX\s*=\s*16\s*;", menu_code):
        return False
    # 数据槽总数由源码常量算出，布局 = 6 面板 + 1 编辑上下文 + 64 逐区域计数
    if "AdvancedCollectionCacheBlockEntity.areaPanelDataCount() + 1 + AREA_LIST_SLOT_MAX" not in menu_code:
        return False
    if "DATA_AREA_FILTER_BASE = 7;" not in menu_code or "DATA_AREA_EDITING = 6;" not in menu_code:
        return False
    # 越界 return 0 的闸门仍在、且两处（数据源 get / getAreaFilterCount）都以 64 为界
    if menu_code.count("areaIndex >= AREA_LIST_SLOT_MAX") != 2:
        return False
    # 服务端读真值、客户端读同步值
    if "advanced.getAreaFilterCount(areaIndex)" not in menu_code:
        return False
    if "areaClientData[DATA_AREA_FILTER_BASE + areaIndex]" not in menu_code:
        return False
    # 64 这个数字不是拍脑袋：与硬上限、Config 上限一致
    if "ABSOLUTE_MAX_AREAS = 64;" not in area_selection_code:
        return False
    if 'defineInRange("areaSelectorMaxAreas", 16, 1, 64)' not in config_code:
        return False
    # 数据槽数量 = 基础 DATA_SLOT_COUNT + 区域 AREA_DATA_COUNT
    panel_count = 6  # AdvancedCollectionCacheBlockEntity#areaPanelDataCount()
    data_slot_count = int_constant(base_menu_code, "DATA_SLOT_COUNT")
    if data_slot_count is None:
        return False
    area_data_count = panel_count + 1 + list_max
    if area_data_count != 71 or data_slot_count + area_data_count != 102:
        return False
    # 同步包体：每个数据槽 1 个 INT；即便假设全塞进一个包也只有 102×4 = 408 字节
    return 4 * (data_slot_count + area_data_count) < SAFE_PACKET_BYTES


def c5_tooltip_ok(item_code):
    """C-5 后续：tooltip 计数走「带 registries」的 all 重载；拿不到注册表保守按 0，不回退两参版。"""
    body = method_body(item_code, "public void appendHoverText(")
    if body is None:
        return False
    if "context.level()" not in body:
        return False
    if body.count("AreaMarks.all(") != 1:
        return False
    if "AreaMarks.all(stack, tooltipLevel.registryAccess())" not in body:
        return False
    if "tooltipLevel == null" not in body or "? 0" not in body:
        return False
    # 两参调用（AreaMarks.all(stack) / all(stack, null)）一律不许出现：那就是 bug 本身
    if "AreaMarks.all(stack)" in body or "all(stack, null)" in body:
        return False
    return True


def c7_hook_ok(hook_code):
    """C-7：接口提供「多盒」入口（默认单盒，保持旧实现 / 单区域行为不变）。"""
    return ("default List<AABB> entityQueryAreas()" in hook_code
            and "return single == null ? List.of() : List.of(single);" in hook_code
            and "union.expandTowards(0.0D, 1.0D, 0.0D)" in hook_code)


def c7_base_callers_ok(base_code):
    """C-7：基础版两条实体扫描都逐簇查询 + containsEntity 精确过滤；旧的单并集调用消失。"""
    return (base_code.count("final List<AABB> boxes = areas.entityQueryAreas();") == 2
            and base_code.count("found.removeIf(entity -> !areas.containsEntity(entity));") == 2
            and "getEntitiesOfClass(ItemEntity.class, box," in base_code
            and "ExperienceOrb.class, box" in base_code
            and "areas.entityQueryArea();" not in base_code)


def c7_advanced_ok(adv_code):
    """C-7：AreaCursor 用 clusterAreas 建每簇查询盒（向上扩 1 格），并覆写 entityQueryAreas()。"""
    return ("static List<AABB> clusterAreas(final List<Area> areas)" in adv_code
            and "private static boolean fitsCluster(final AABB box)" in adv_code
            and "if (fitsCluster(merged))" in adv_code
            and "queryBoxes = List.copyOf(expanded);" in adv_code
            and "public List<AABB> entityQueryAreas()" in adv_code
            and "return queryBoxes;" in adv_code
            and "box.expandTowards(0.0D, 1.0D, 0.0D)" in adv_code)


def group_anchors():
    print("\n=== ① 源码锚点 ===")
    menu_raw = read(ADV_MENU)
    base_menu_raw = read(BASE_MENU)
    adv_raw = read(ADV_BE)
    base_raw = read(BASE_BE)
    hook_raw = read(HOOK)
    item_raw = read(SELECTOR_ITEM)

    menu_code = strip_comments(menu_raw)
    adv_code = strip_comments(adv_raw)
    base_code = strip_comments(base_raw)
    hook_code = strip_comments(hook_raw)
    item_code = strip_comments(item_raw)

    check("A-1 C-4：AREA_LIST_SLOT_MAX = 64（= 硬上限 ABSOLUTE_MAX_AREAS，不再是配置默认值 16）",
          int_constant(menu_code, "AREA_LIST_SLOT_MAX") == 64)
    check("A-2 C-4：数据槽布局 = 6 面板 + 1 编辑上下文 + 64 逐区域计数（由源码常量算出，不写死）",
          "AdvancedCollectionCacheBlockEntity.areaPanelDataCount() + 1 + AREA_LIST_SLOT_MAX" in menu_code
          and "DATA_AREA_EDITING = 6;" in menu_code and "DATA_AREA_FILTER_BASE = 7;" in menu_code)
    check("A-3 C-4：越界 return 0 的闸门仍在、两处都以 AREA_LIST_SLOT_MAX 为界（服务端 + 客户端同口径）",
          menu_code.count("areaIndex >= AREA_LIST_SLOT_MAX") == 2)
    check("A-4 C-4：服务端读真值 advanced.getAreaFilterCount / 客户端读同步值 areaClientData",
          "advanced.getAreaFilterCount(areaIndex)" in menu_code
          and "areaClientData[DATA_AREA_FILTER_BASE + areaIndex]" in menu_code)
    check("A-5 C-4：数据槽 = 基础 31 + 区域 71 = 102；即便全塞进一个包也只有 408 字节（远小于上限）",
          c4_slots_ok(menu_code, strip_comments(base_menu_raw),
                      strip_comments(read(AREA_SELECTION)), strip_comments(read(CONFIG))))
    check("A-6 C-5：tooltip 计数走带 registries 的 AreaMarks.all(stack, level.registryAccess())",
          c5_tooltip_ok(item_code))
    check("A-7 C-5：拿不到注册表时保守按 0（tooltipLevel == null ? 0），绝不回退两参版",
          "tooltipLevel == null" in item_code and "? 0 : AreaMarks.all(stack, tooltipLevel.registryAccess())"
          in item_code.replace("\n", " ").replace("            ", " "))
    check("A-8 C-5：注释里保留「按内容指纹删除要改 StreamCodec 线格式、旧客户端整包错位」的待办",
          "StreamCodec" in item_raw and "旧客户端" in item_raw)
    check("A-9 C-7：AreaScanHook 新增 entityQueryAreas() 多盒入口（默认单盒；盒向上扩 1 格）",
          c7_hook_ok(hook_code))
    check("A-10 C-7：基础版掉落物 / 经验球两条扫描都逐簇查询 + containsEntity 过滤（旧单并集调用消失）",
          c7_base_callers_ok(base_code))
    check("A-11 C-7：AreaCursor 用 clusterAreas 贪心聚类，每簇一个盒（已向上扩 1 格）并覆写 entityQueryAreas()",
          c7_advanced_ok(adv_code))
    thresholds = cluster_thresholds(adv_code, strip_comments(read(AREA)))
    check("A-12 C-7：簇阈值可从源码解析：跨度 = 2×Area.MAX_SIDE = 256，体积 = Area.MAX_SIDE³ = 2097152",
          thresholds is not None and thresholds["max_side"] == 128
          and thresholds["max_span"] == 256.0 and thresholds["max_volume"] == 2097152.0,
          str(thresholds))
    check("A-13 C-7：阈值 / 退化性质写在注释里（区域全相邻 ⇒ 1 个盒；相隔 1000 格 / 空心大盒要拆簇）",
          "区域全相邻" in adv_raw and "1000 格" in adv_raw and "空心" in adv_raw)
    return thresholds


# ======================================================================================
# ② 行为模型（python 复刻 Java 判定，纯函数，不依赖 Minecraft）
# ======================================================================================

def to_aabb(area):
    """Area#toAabb：两个角各占一格 ⇒ 上界 +1（与 Java 同口径）。"""
    min_x, min_y, min_z, max_x, max_y, max_z = area
    return (float(min_x), float(min_y), float(min_z),
            float(max_x) + 1.0, float(max_y) + 1.0, float(max_z) + 1.0)


def union_box(first, second):
    return (min(first[0], second[0]), min(first[1], second[1]), min(first[2], second[2]),
            max(first[3], second[3]), max(first[4], second[4]), max(first[5], second[5]))


def box_sizes(box):
    return (box[3] - box[0], box[4] - box[1], box[5] - box[2])


def box_volume(box):
    size_x, size_y, size_z = box_sizes(box)
    return size_x * size_y * size_z


def box_contains(outer, inner):
    return (all(outer[i] <= inner[i] for i in range(3))
            and all(inner[i] <= outer[i] for i in range(3, 6)))


def fits_cluster(box, max_span, max_volume):
    """复刻 AdvancedCollectionCacheBlockEntity#fitsCluster（三轴跨度 + 体积双阈值）。"""
    size_x, size_y, size_z = box_sizes(box)
    if max(size_x, size_y, size_z) > max_span:
        return False
    return box_volume(box) <= max_volume


def cluster_members(areas, max_span, max_volume):
    """复刻 AdvancedCollectionCacheBlockEntity#clusterAreas（按列表顺序贪心，O(n)）。

    返回 `[(簇盒, [区域下标...]), ...]`；空输入 ⇒ 空列表。
    """
    clusters = []
    current_box = None
    current_indices = []
    for index, area in enumerate(areas):
        box = to_aabb(area)
        if current_box is None:
            current_box = box
            current_indices = [index]
            continue
        merged = union_box(current_box, box)
        if fits_cluster(merged, max_span, max_volume):
            current_box = merged
            current_indices.append(index)
        else:
            clusters.append((current_box, current_indices))
            current_box = box
            current_indices = [index]
    if current_box is not None:
        clusters.append((current_box, current_indices))
    return clusters


def cluster_boxes(areas, max_span, max_volume):
    return [box for box, _indices in cluster_members(areas, max_span, max_volume)]


def area_filter_slot(area_index, list_max, server_counts):
    """复刻菜单数据源：index >= DATA_AREA_FILTER_BASE 时按 areaIndex 取，越界 return 0。"""
    if area_index < 0 or area_index >= list_max:
        return 0
    return server_counts.get(area_index, 0)


def data_slot_count(base_data_slots, list_max):
    """菜单 AREA_DATA_COUNT = 面板 6 + 编辑上下文 1 + 逐区域计数 list_max。"""
    return 6 + 1 + list_max


def group_models(thresholds):
    print("\n=== ② 行为模型 ===")
    max_span = thresholds["max_span"]
    max_volume = thresholds["max_volume"]
    base_data_slots = int_constant(strip_comments(read(BASE_MENU)), "DATA_SLOT_COUNT")

    # ---------------- C-4：数据槽模型（16 vs 64 的差异必须能被模型复现） ----------------
    server_counts = {index: index + 1 for index in range(64)}   # 每区域「有效过滤条目数」= 非零
    readable = [area_filter_slot(index, 64, server_counts) for index in range(64)]
    check("M-1 C-4：上限 64 时 0..63 每块区域的计数都读得到（不再从第 17 块起永远 0）",
          readable == [index + 1 for index in range(64)])
    old_readable = [area_filter_slot(index, 16, server_counts) for index in range(64)]
    check("M-2 C-4：上限 16 的旧模型里，第 17 块（index 16）起全部读回 0 —— 复现「界面说谎」",
          old_readable[16:] == [0] * 48 and old_readable[15] == 16)
    check("M-3 C-4：越界闸门仍在 64 之外（index 64 / -1 ⇒ 0；index 63 ⇒ 正常值）",
          area_filter_slot(64, 64, server_counts) == 0
          and area_filter_slot(-1, 64, server_counts) == 0
          and area_filter_slot(63, 64, server_counts) == 64)
    check("M-4 C-4：数据槽布局 7 + 63 = 70 = AREA_DATA_COUNT-1（最后一个逐区域计数不越界）",
          7 + 63 == data_slot_count(base_data_slots, 64) - 1
          and data_slot_count(base_data_slots, 64) == 71
          and base_data_slots + data_slot_count(base_data_slots, 64) == 102)
    check("M-5 C-4：102 个数据槽 × 4 字节 = 408 字节 < 保守包体上限（每槽一个 INT）",
          4 * (base_data_slots + data_slot_count(base_data_slots, 64)) < SAFE_PACKET_BYTES)

    # ---------------- C-7：聚类模型 ----------------
    check("M-6 C-7：空区域 ⇒ 0 个盒（不查询）；非空 ⇒ 至少 1 个盒",
          cluster_boxes([], max_span, max_volume) == []
          and len(cluster_boxes([(0, 64, 0, 0, 64, 0)], max_span, max_volume)) == 1)

    adjacent = [
        (0, 64, 0, 15, 64, 15),
        (16, 64, 0, 31, 64, 15),
        (0, 64, 16, 15, 64, 31),
        (16, 64, 16, 31, 64, 31),
    ]
    adjacent_boxes = cluster_boxes(adjacent, max_span, max_volume)
    total_union = None
    for area in adjacent:
        total_union = to_aabb(area) if total_union is None else union_box(total_union, to_aabb(area))
    check("M-7 C-7：区域全相邻（2×2 挨着画）⇒ 恰好 1 个盒，且 == 旧版总并集（行为逐字一致）",
          len(adjacent_boxes) == 1 and adjacent_boxes[0] == total_union,
          "boxes=%d" % len(adjacent_boxes))

    far_apart = [(0, 64, 0, 15, 64, 15), (1000, 64, 0, 1015, 64, 15)]
    far_boxes = cluster_boxes(far_apart, max_span, max_volume)
    old_far_union = union_box(to_aabb(far_apart[0]), to_aabb(far_apart[1]))
    check("M-8 C-7：相隔 1000 格 ⇒ 至少 2 簇，且每簇跨度都 ≤ 阈值（旧总并集跨度 1016 格是 bug）",
          len(far_boxes) >= 2 and all(max(box_sizes(box)) <= max_span for box in far_boxes)
          and max(box_sizes(old_far_union)) > max_span,
          "boxes=%d old_span=%.0f" % (len(far_boxes), max(box_sizes(old_far_union))))

    # 8 块 32 格宽的区域并排相邻：总跨度正好 256（= 2×MAX_SIDE）⇒ 仍应 1 簇
    chain = [(32 * index, 64, 0, 32 * index + 31, 64, 15) for index in range(8)]
    chain_boxes = cluster_boxes(chain, max_span, max_volume)
    check("M-9 C-7：8 块 32 格宽区域并排（总跨度正好 256 = 2×MAX_SIDE）⇒ 仍然 1 簇（阈值不误伤）",
          len(chain_boxes) == 1 and max(box_sizes(chain_boxes[0])) == 256)

    # 第 9 块让总跨度 288 > 256 ⇒ 必须开新簇
    chain9 = chain + [(256, 64, 0, 287, 64, 15)]
    chain9_boxes = cluster_boxes(chain9, max_span, max_volume)
    check("M-10 C-7：再加一块让跨度 288 > 256 ⇒ 拆成 2 簇（跨度阈值真的生效）",
          len(chain9_boxes) == 2 and max(box_sizes(chain9_boxes[0])) <= max_span)

    # 角对角：跨度 201 ≤ 256，但体积 201³ ≈ 812 万 > 128³ ⇒ 体积阈值必须拆簇（空心大盒）
    diagonal = [(0, 0, 0, 0, 0, 0), (200, 200, 200, 200, 200, 200)]
    diagonal_boxes = cluster_boxes(diagonal, max_span, max_volume)
    check("M-11 C-7：角对角的 1 格区域（跨度 201 ≤ 256，体积 8120601 > 2097152）⇒ 拆成 2 簇",
          len(diagonal_boxes) == 2
          and max(box_sizes(union_box(to_aabb(diagonal[0]), to_aabb(diagonal[1])))) <= max_span
          and box_volume(union_box(to_aabb(diagonal[0]), to_aabb(diagonal[1]))) > max_volume)

    # 通用性质：每个区域恰好落在自己簇的盒里；每簇盒 = 簇内区域的并集；盒都在阈值内
    property_cases = [adjacent, far_apart, chain, chain9, diagonal,
                      [(0, 0, 0, 3, 3, 3), (4, 0, 0, 7, 3, 3), (500, 500, 500, 503, 503, 503)]]
    coverage_ok = True
    bounds_ok = True
    clusters_ok = True
    for areas in property_cases:
        clusters = cluster_members(areas, max_span, max_volume)
        seen = []
        for box, indices in clusters:
            rebuilt = None
            for index in indices:
                rebuilt = to_aabb(areas[index]) if rebuilt is None else union_box(rebuilt, to_aabb(areas[index]))
            if rebuilt != box:
                clusters_ok = False
            for index in indices:
                if not box_contains(box, to_aabb(areas[index])):
                    coverage_ok = False
                seen.append(index)
            if not fits_cluster(box, max_span, max_volume):
                bounds_ok = False
        if sorted(seen) != list(range(len(areas))):
            coverage_ok = False
    check("M-12 C-7：每个区域恰好属于 1 簇且被该簇盒包含（拆簇只改查询范围，不丢区域）", coverage_ok)
    check("M-13 C-7：每簇盒 == 该簇区域的并集（不是随手切一半）", clusters_ok)
    check("M-14 C-7：所有簇盒的三轴跨度 / 体积都在阈值内（单次查询的区块段遍历量有硬上界）", bounds_ok)


# ======================================================================================
# ③ 反例自证（源码副本 / 模型参数：把坏写法拼回去，断言上面的判据真的判负）
# ======================================================================================

def group_counterexamples(thresholds):
    print("\n=== ③ 反例自证 ===")
    max_span = thresholds["max_span"]
    max_volume = thresholds["max_volume"]

    menu_code = strip_comments(read(ADV_MENU))
    base_menu_code = strip_comments(read(BASE_MENU))
    area_selection_code = strip_comments(read(AREA_SELECTION))
    config_code = strip_comments(read(CONFIG))
    item_code = strip_comments(read(SELECTOR_ITEM))
    hook_code = strip_comments(read(HOOK))
    base_code = strip_comments(read(BASE_BE))
    adv_code = strip_comments(read(ADV_BE))

    # C-1：常量退回 16 ⇒ C-4 判负（原始 bug 的源码形态）
    broken = menu_code.replace("AREA_LIST_SLOT_MAX = 64;", "AREA_LIST_SLOT_MAX = 16;", 1)
    counterexample("C-1 源码副本：AREA_LIST_SLOT_MAX 退回 16 ⇒ A-1 / A-5 判负（第 17 块起数据槽又永远是 0）",
                   broken != menu_code
                   and not c4_slots_ok(broken, base_menu_code, area_selection_code, config_code))

    # C-2：删掉一处越界闸门 ⇒ A-3 判负
    broken = menu_code.replace("if (areaIndex < 0 || areaIndex >= AREA_LIST_SLOT_MAX) {", "if (false) {", 1)
    counterexample("C-2 源码副本：删掉一处越界 return 0 闸门 ⇒ A-3 判负（越界数据槽会读到数组外）",
                   broken != menu_code
                   and not c4_slots_ok(broken, base_menu_code, area_selection_code, config_code))

    # C-3：tooltip 退回两参 all(stack) ⇒ C-5 判负（这就是修复前的写法）
    broken = item_code.replace(
        "AreaMarks.all(stack, tooltipLevel.registryAccess())", "AreaMarks.all(stack)", 1)
    counterexample("C-3 源码副本：tooltip 退回两参 AreaMarks.all(stack) ⇒ A-6 / A-7 判负（旧档条目被跳过、计数偏少）",
                   broken != item_code and not c5_tooltip_ok(broken))

    # C-4：hook 的默认多盒入口改名 ⇒ A-9 判负
    broken = hook_code.replace("default List<AABB> entityQueryAreas()",
                               "default List<AABB> entityQueryAreasSingle()", 1)
    counterexample("C-4 源码副本：删掉 entityQueryAreas() 默认实现 ⇒ A-9 判负（调用方只能退回单并集）",
                   broken != hook_code and not c7_hook_ok(broken))

    # C-5：基础版调用退回收总并集 ⇒ A-10 判负
    broken = base_code.replace("areas.entityQueryAreas()", "areas.entityQueryArea();", 1)
    counterexample("C-5 源码副本：一条扫描退回总并集 entityQueryArea() ⇒ A-10 判负（相隔 1000 格的问题原样回来）",
                   broken != base_code and not c7_base_callers_ok(broken))

    # C-6：AreaCursor 不再覆写多盒 ⇒ A-11 判负
    broken = adv_code.replace("public List<AABB> entityQueryAreas()",
                              "public List<AABB> entityQueryAreasLegacy()", 1)
    counterexample("C-6 源码副本：去掉 AreaCursor 的 entityQueryAreas() 覆写 ⇒ A-11 判负",
                   broken != adv_code and not c7_advanced_ok(broken))

    # C-7（模型反例）：把簇阈值放开 = 旧版「总并集一个盒」⇒ 相隔 1000 格的跨度断言判负
    unlimited_boxes = cluster_boxes([(0, 64, 0, 15, 64, 15), (1000, 64, 0, 1015, 64, 15)],
                                    max_span=10 ** 9, max_volume=10 ** 18)
    counterexample("C-7 模型反例：不限簇（= 旧版总并集一个盒）⇒ 跨度 1016 > 256，"
                   "「每簇跨度 ≤ 阈值」判负（证明 C-7 不是理论瑕疵）",
                   len(unlimited_boxes) == 1 and max(box_sizes(unlimited_boxes[0])) > max_span)

    # C-8（模型反例）：只留跨度阈值、去掉体积阈值 ⇒ 角对角空心大盒不拆，M-11 判负
    span_only_boxes = cluster_boxes([(0, 0, 0, 0, 0, 0), (200, 200, 200, 200, 200, 200)],
                                    max_span=max_span, max_volume=10 ** 18)
    counterexample("C-8 模型反例：去掉体积阈值 ⇒ 角对角 812 万格的空心盒不拆簇，M-11 判负",
                   len(span_only_boxes) == 1
                   and box_volume(span_only_boxes[0]) > max_volume)

    # C-9（模型反例）：数据槽上限退回 16 ⇒ 「第 17 块读得到」判负（= 线上界面说谎的机制）
    server_counts = {index: index + 1 for index in range(64)}
    counterexample("C-9 模型反例：数据槽上限退回 16 ⇒ 第 17 块（index 16）读回 0、"
                   "与「每块区域都能读到计数」判负",
                   area_filter_slot(16, 16, server_counts) == 0
                   and area_filter_slot(16, 64, server_counts) != 0)


def main():
    print("第 121 轮自检：C-4 数据槽 16→64 / C-7 实体查询盒按簇拆分 / C-5 后续 tooltip 注册表口径")
    thresholds = group_anchors()
    if thresholds is None:
        print("\n[致命] 解析不到 C-7 簇阈值，后续模型无法建立")
        return 1
    group_models(thresholds)
    group_counterexamples(thresholds)
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
