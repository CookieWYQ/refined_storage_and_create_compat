#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 101 轮自检：归流缓存仓「掉落物吸不进去」的三条根因 + 每区域独立过滤器。

用户本轮原话（1.2.0-beta.6 实机）：
  「有时候它并不能正常的吸取……高级的归流缓存器它的范围还是没有任何卵用……
   他根本不能用标定范围内——我扔已经标记的物品根本就是无法吸收的」

本轮修掉的三条机制（逐条有「回退 ⇒ 哪条断言变红」的反例）：
  P0-2 【主因】区域模式的实体包含判定用的是实体整数格坐标，且查询盒与区域盒极限相切：
        掉落在「区域底板」上的实体（y = 底板 + 1）在 getEntitiesOfClass 查询阶段就被整批丢弃
        ⇒ 玩家最常见的「两个角都点在地上」的 1 格高区域一件都吸不到（流体却正常）。
        修法：AreaScanHook#entityQueryAreas()（第 121 轮 C-7 起按簇多盒；每盒向上扩 1 格）+ #containsEntity()
        （占用格 / 所站格 / 被卡的上方格 任一命中）。
  P0-3 旧存档标记的 Amount 是「凑够 N 件才吸」的硬门槛（当年写的是手持堆叠数，常见 64）
        ⇒ 扔 1 件永远不满足门槛。修法：载入时按存档版本（MarkerAmountV2 键）把旧档 >1 归 1，
        新档保留玩家显式设的 N；失败忽略窗口 100 → 20 tick。
  P0-1 高级版「区域取代半径」的硬闸（没装选定器 / 区域为空 / 维度不符 ⇒ 一点不吸）行为不变，
        但把「为什么没吸」做成服务端算的一行状态（状态码 0..10），界面只显示。

P1（用户核心需求）：「每个区域内可以吸收的东西都是不一样的」。数据模型：
  每区域一份 ghost 过滤集合（Map<区域序号, List<MarkerEntry>>，NBT 键 AreaFilters），
  空集 = 该区域不吸任何东西（安全默认，解决「相邻机器重复吸放」）；
  匹配顺序：实体落在哪个区域 ⇒ 用那个区域的过滤器；不在任何区域 ⇒ 不吸。

自检结构：
  ① 行为模型（纯 Python 复刻 Java 的判定规则）：匹配/不匹配 × 内/外 × 物品/经验，
     断言「一轮扫描后缓存里恰好多了该吸的那些」；高级版三态 + 两区域不同过滤器。
  ② 源码锚点：每条修法在 Java 里的关键行必须存在（回退即红）。
  ③ 反例自证：改坏模型 / 判定写反 / 去掉匹配 / 跳过扫描 / 空集当全收 ⇒ 必须判负。

第 131 轮：状态带摘要行（editAreaSummary）/ 状态带右端过滤图标（collectFrameIcons）/
匹配区空态提示（drawMatchAreaHint 覆写）已按用户要求删除 ⇒ ④-19 / ④-20 判据反转成
「这些落点必须不存在」，反例 ⑤-b9 也改成「把它们拼回 ⇒ 判负」（判据只收紧/反转，不放宽）。
"""

import io
import os
import re
import sys

# Windows 控制台默认 GBK：本脚本输出含「⇒ / ①」等字符，统一成 UTF-8 并容错，
# 避免「编码异常」被误认为自检失败。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # 老 Python / 非标准流：退化为容错写
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BE_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/CollectionCacheBlockEntity.java")
ADV_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/AdvancedCollectionCacheBlockEntity.java")
HOOK_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/support/AreaScanHook.java")
MENU_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/menu/CollectionCacheMenu.java")
ADV_MENU_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/menu/AdvancedCollectionCacheMenu.java")
SCREEN_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/CollectionCacheScreen.java")
ADV_SCREEN_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AdvancedCollectionCacheScreen.java")
# 第 131 轮：区域行 / 状态带绘制出口分别落在「侧栏 AreaStripPanel」与「基类 CollectionCacheScreen」，
# 因此把这两份源码也读进来 —— 判据不变（区域行仍要「序号 + 名称 + 坐标」、状态带仍要「只在真问题时出字」），
# 只是落点跟着第 131 轮的实现搬了家（高级界面自己不再画摘要行 / 右端图标 / 状态行）。
PANEL_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AreaStripPanel.java")
BASE_SCREEN_PATH = SCREEN_PATH
PACKET_PATH = os.path.join(ROOT, "src/main/java/cretae/cookiewyq/rs_create_compat/network/SetAreaFilterContextPacket.java")

checks = []
failures = []


def check(name, ok, detail=""):
    checks.append(name)
    if ok:
        print("PASS  %s" % name)
    else:
        failures.append(name if not detail else "%s  <- %s" % (name, detail))
        print("FAIL  %s%s" % (name, "" if not detail else "  <- " + str(detail)))


def read(path):
    return io.open(path, encoding="utf-8").read()


def code_only(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def section(title):
    print("\n" + "-" * 78)
    print(title)


# =====================================================================================
# 行为模型（与 Java 判定同规则，纯函数，不依赖 Minecraft）
# =====================================================================================

class Item:
    def __init__(self, item_id, count=1, tags=()):
        self.id = item_id
        self.count = count
        self.tags = set(tags)

    def __repr__(self):
        return "%s×%d" % (self.id, self.count)


class Marker:
    def __init__(self, item_id=None, amount=1, tags=()):
        self.id = item_id
        self.amount = max(1, amount)
        self.tags = set(tags)


def matches_item(marker, stack, strict_nbt=False):
    """复刻 CollectionCacheBlockEntity#matchesItem（ID / 标签 AND；本模型不模拟数据组件）。"""
    if marker is None or stack is None:
        return False
    if marker.tags:
        return marker.tags.issubset(stack.tags)
    return stack.id == marker.id


class Dropped:
    """一个掉落物实体：物品 + 位置（整数格坐标）。"""

    def __init__(self, stack, x, y, z):
        self.stack = stack
        self.pos = (x, y, z)
        self.absorbed = 0

    def __repr__(self):
        return "%r@%r" % (self.stack, self.pos)


class Box:
    """轴对齐闭区间方块区域（与 Area 同口径：min/max 都是含端点的整数格）。"""

    def __init__(self, min_x, min_y, min_z, max_x, max_y, max_z):
        self.min = (min(min_x, max_x), min(min_y, max_y), min(min_z, max_z))
        self.max = (max(min_x, max_x), max(min_y, max_y), max(min_z, max_z))

    def contains_pos(self, pos):
        return all(self.min[i] <= pos[i] <= self.max[i] for i in range(3))

    def contains_entity_old(self, entity):
        """旧实现（bug 形态）：只看实体整数格坐标。"""
        return self.contains_pos(entity.pos)

    def contains_entity_new(self, entity):
        """修好后的口径：占用格 / 所站格 / 被卡的上方格（对应 AreaScanHook#containsEntity）。"""
        x, y, z = entity.pos
        return (self.contains_pos((x, y, z))
                or self.contains_pos((x, y - 1, z))
                or self.contains_pos((x, y + 1, z)))


class AreaScan:
    """高级版区域扫描：并集查询盒（旧 = 并集原样；新 = 向下扩 1 格）+ 实体包含判定。"""

    def __init__(self, areas, fix_query=True, fix_contains=True, contains_inverted=False):
        self.areas = areas
        self.fix_query = fix_query
        self.fix_contains = fix_contains
        self.contains_inverted = contains_inverted

    def query_box(self):
        min_x = min(a.min[0] for a in self.areas)
        min_y = min(a.min[1] for a in self.areas)
        min_z = min(a.min[2] for a in self.areas)
        max_x = max(a.max[0] for a in self.areas)
        max_y = max(a.max[1] for a in self.areas)
        max_z = max(a.max[2] for a in self.areas)
        if self.fix_query:
            max_y += 1  # 并集盒向上扩 1 格（AreaScanHook#entityQueryArea；方向必须向上）
        return Box(min_x, min_y, min_z, max_x, max_y, max_z)

    def query(self, entities):
        box = self.query_box()
        # getEntitiesOfClass 语义：实体 AABB 与查询盒相交。模型里实体是「柱体」，
        # 下界 = 整数格 y，上界 = y + 1；区域盒是 [min, max + 1) 的连续盒。
        inside = []
        for e in entities:
            ex, ey, ez = e.pos
            e_min_y = ey
            e_max_y = ey + 1.0
            box_min_y = box.min[1]
            box_max_y = box.max[1] + 1.0
            if (box.min[0] <= ex <= box.max[0] and box.min[2] <= ez <= box.max[2]
                    and e_min_y < box_max_y and e_max_y > box_min_y):
                inside.append(e)
        kept = []
        for e in inside:
            contained = (self.areas[0].contains_entity_old(e)
                         if not self.fix_contains else any(a.contains_entity_new(e) for a in self.areas))
            if self.contains_inverted:
                contained = not contained
            if contained:
                kept.append(e)
        return kept


def absorb_scan(base_markers, entities, area_scan=None, filters_for_entity=None,
                capacity=500, cache=None, area_blocked=False):
    """复刻一轮扫描的吸收（返回本轮每个实体吸走的件数合计；cache 为 {item_id: count}）。

    - base_markers / 半径模式：逐条标记统计同一上下文里的命中总数，达到数量门槛才吸；
    - 区域模式：filters_for_entity(entity) 给出该实体所在区域的过滤集合（None/空 = 不吸）；
    - capacity：缓存总容量（件），装满即停（留下次，不丢）。
    """
    if cache is None:
        cache = {}
    if area_blocked:
        return 0
    total = 0
    if area_scan is not None:
        pool = area_scan.query(entities)
        groups = []  # [(filters_list_identity, [entities])]
        for entity in pool:
            filters = filters_for_entity(entity) if filters_for_entity else base_markers
            if not filters:
                continue
            for group in groups:
                if group[0] is filters:
                    group[1].append(entity)
                    break
            else:
                groups.append((filters, [entity]))
    else:
        pool = entities
        groups = [(base_markers, list(entities))]

    for filters, group in groups:
        for marker in filters:
            if marker is None:
                continue
            hit = [e for e in group if matches_item(marker, e.stack)]
            if not hit:
                continue
            if sum(e.stack.count for e in hit) < marker.amount:
                continue  # 数量门槛没凑够：一条都不吸（含旧存档 amount=64 的坑）
            for entity in hit:
                free = capacity - sum(cache.values())
                if free <= 0:
                    break
                moved = min(entity.stack.count, free)
                cache[entity.stack.id] = cache.get(entity.stack.id, 0) + moved
                entity.absorbed += moved
                total += moved
    return total


def marker_window(amounts, cache, entities, area_scan=None, filters=None, capacity=500,
                  blocked=False):
    return absorb_scan([Marker(item_id=k, amount=v) for k, v in amounts.items()],
                       entities, area_scan=area_scan, filters_for_entity=filters,
                       capacity=capacity, cache=cache, area_blocked=blocked)


# =====================================================================================
# ① 行为模型：基础版（半径）——匹配/不匹配 × 内/外 × 数量门槛
# =====================================================================================
section("① 行为模型：基础版半径扫描")

RADIUS = Box(-5, -5, -5, 5, 5, 5)  # 机器在 (0,0,0)，半径 5（相对坐标模型）

def in_radius(e, box=RADIUS):
    x, y, z = e.pos
    return box.contains_pos((x, y, z)) or box.contains_pos((x, y - 1, z))

# 6 个掉落物：2 个匹配在内、1 个匹配在外、1 个不匹配在内、1 个匹配在内但不够门槛
entities = [
    Dropped(Item("minecraft:egg", 3), 1, 1, 1),      # 匹配 + 内
    Dropped(Item("minecraft:egg", 2), -2, 1, 3),     # 匹配 + 内
    Dropped(Item("minecraft:egg", 8), 99, 1, 99),    # 匹配 + 外（半径外）
    Dropped(Item("minecraft:stone", 4), 2, 1, 2),    # 不匹配 + 内
    Dropped(Item("minecraft:wheat", 1), 3, 1, 3),    # 匹配 + 内，但门槛 5 不满足
]
inside = [e for e in entities if in_radius(e)]
cache = {}
absorbed = marker_window({"minecraft:egg": 1, "minecraft:wheat": 5}, cache, inside)
check("①-1 半径内：只吸「匹配 且 数量门槛满足」的掉落物（egg 5 件 / wheat 0 件）",
      absorbed == 5 and cache == {"minecraft:egg": 5},
      "absorbed=%r cache=%r" % (absorbed, cache))
check("①-2 半径外的不吸（egg 8 件留在世界）",
      all(e.absorbed == 0 for e in entities if not in_radius(e)))
check("①-3 不匹配物品完全不吸（stone 4 件留在世界）",
      next(e for e in entities if e.stack.id == "minecraft:stone").absorbed == 0)
check("①-4 数量门槛未满足 ⇒ 一条都不吸（wheat 1 < 5，旧存档 amount=64 的坑）",
      next(e for e in entities if e.stack.id == "minecraft:wheat").absorbed == 0)

# 反例：去掉匹配判据（即「范围内全收」）⇒ ①-1 / ①-3 必须变红
save_matches = matches_item
matches_item = lambda marker, stack, strict_nbt=False: True  # noqa: E731
cache_bad, absorbed_bad = {}, None
absorbed_bad = marker_window({"minecraft:egg": 1, "minecraft:wheat": 5}, cache_bad, inside)
check("①-b 反例：删掉匹配判据 ⇒ 模型断言变红（说明 ① 不是空断言）",
      absorbed_bad != 5 or cache_bad.get("minecraft:stone", 0) > 0,
      "删掉匹配后 cache=%r" % (cache_bad,))
matches_item = save_matches

# =====================================================================================
# ② 场景模型：P0-2 区域底板上的掉落物（旧口径 vs 新口径）
# =====================================================================================
section("② P0-2 行为模型：区域底板上的掉落物")

# 玩家最常见的画法：两个角都点在 y=63 的地面方块上 ⇒ 区域是 1 格高 [63,63]
floor_area = Box(0, 63, 0, 10, 63, 10)
on_floor = [Dropped(Item("minecraft:egg", 2), 5, 64, 5)]          # 落在底板上：实体 y=64
inside_high = [Dropped(Item("minecraft:egg", 3), 5, 63, 5)]       # 站在区域内（实体 y=63）
outside = [Dropped(Item("minecraft:egg", 1), 50, 64, 50)]         # 区域外
markers = [Marker("minecraft:egg", 1)]

old_scan = AreaScan([floor_area], fix_query=False, fix_contains=False)
new_scan = AreaScan([floor_area], fix_query=True, fix_contains=True)

old_cache = {}
absorb_scan(markers, on_floor, area_scan=old_scan, cache=old_cache)
check("②-1 旧口径：区域底板上的掉落物一件都吸不到（复现线上 P0）",
      old_cache == {}, "old_cache=%r" % (old_cache,))

new_cache = {}
absorb_scan(markers, on_floor, area_scan=new_scan, cache=new_cache)
check("②-2 新口径：区域底板上的掉落物被正确吸入（2 件）",
      new_cache == {"minecraft:egg": 2}, "new_cache=%r" % (new_cache,))

mix_cache = {}
absorb_scan(markers, on_floor + inside_high + outside, area_scan=new_scan, cache=mix_cache)
check("②-3 新口径只吸区域内的（底板 2 + 区域内 3 = 5，区域外 1 件不吸）",
      mix_cache == {"minecraft:egg": 5}, "mix_cache=%r" % (mix_cache,))

# 反例：把区域判断写反 ⇒ ②-2 必须变红
inverted = AreaScan([floor_area], fix_query=True, fix_contains=True, contains_inverted=True)
inv_cache = {}
absorb_scan(markers, on_floor, area_scan=inverted, cache=inv_cache)
check("②-b 反例：区域包含判定写反 ⇒ 底板掉落物反而吸不到（②-2 变红）",
      inv_cache == {}, "inverted cache=%r" % (inv_cache,))

# 反例：扫描永远跳过 ⇒ 什么都吸不到
check("②-c 反例：扫描钩子被短路（areaAbsorptionBlocked 恒真）⇒ 一件不吸",
      absorb_scan(markers, on_floor, area_scan=new_scan, cache={}, area_blocked=True) == 0)

# =====================================================================================
# ③ 高级版三态 + 每区域独立过滤器模型
# =====================================================================================
section("③ 每区域独立过滤器（P1）与高级版三态")

area_a = Box(0, 63, 0, 9, 65, 9)
area_b = Box(20, 63, 0, 29, 65, 9)
scan_two = AreaScan([area_a, area_b], fix_query=True, fix_contains=True)

drops = [
    Dropped(Item("minecraft:egg", 2), 3, 64, 3),     # 区域 A
    Dropped(Item("minecraft:stone", 2), 4, 64, 4),   # 区域 A（不该吸）
    Dropped(Item("minecraft:stone", 3), 23, 64, 3),  # 区域 B
    Dropped(Item("minecraft:egg", 5), 24, 64, 4),    # 区域 B（不该吸）
]
filters = {
    0: [Marker("minecraft:egg", 1)],     # 区域 A 只吸 egg
    1: [Marker("minecraft:stone", 1)],   # 区域 B 只吸 stone
}

def filters_for(entity):
    """复刻 AdvancedCollectionCacheBlockEntity#filtersForEntity（区域序号 → 过滤集合）。"""
    for index, area in enumerate(scan_two.areas):
        if area.contains_entity_new(entity):
            return filters.get(index) or []
    return None  # 不在任何区域 ⇒ 不吸

cache = {}
absorb_scan([], drops, area_scan=scan_two, filters_for_entity=filters_for, cache=cache)
check("③-1 区域 A 只吸 egg、区域 B 只吸 stone（互不串味）",
      cache == {"minecraft:egg": 2, "minecraft:stone": 3},
      "cache=%r" % (cache,))

# 空集语义：区域 B 没配过滤 ⇒ 区域 B 不吸任何东西（安全默认，解决重复吸放）
cache = {}
filters_empty_b = {0: [Marker("minecraft:egg", 1)], 1: []}
absorb_scan([], drops, area_scan=scan_two,
            filters_for_entity=lambda e: (next((filters_empty_b[i] for i, a in enumerate(scan_two.areas)
                                                if a.contains_entity_new(e)), None) or []),
            cache=cache)
check("③-2 空集 = 该区域不吸任何东西（区域 B 的 stone 留在世界）",
      cache == {"minecraft:egg": 2}, "cache=%r" % (cache,))

# 不在任何区域 ⇒ 不吸
outside_drop = [Dropped(Item("minecraft:egg", 9), 100, 64, 100)]
cache = {}
absorb_scan([], outside_drop, area_scan=scan_two, filters_for_entity=filters_for, cache=cache)
check("③-3 不在任何区域 ⇒ 不吸（区域取代半径，绝不退回半径）", cache == {})

# 反例：区域过滤器写反（A 用 B 的过滤集合）⇒ ③-1 变红
def filters_swapped(entity):
    for index, area in enumerate(scan_two.areas):
        if area.contains_entity_new(entity):
            return filters.get(1 - index) or []
    return None

cache = {}
absorb_scan([], drops, area_scan=scan_two, filters_for_entity=filters_swapped, cache=cache)
check("③-b 反例：把 A/B 的过滤集合对调 ⇒ ③-1 变红（说明 ③ 不是空断言）",
      cache != {"minecraft:egg": 2, "minecraft:stone": 3}, "swapped cache=%r" % (cache,))

# 反例：空集当成「全收」⇒ ③-2 变红（这正是「重复吸放」的来源之一）
cache = {}
absorb_scan([], drops, area_scan=scan_two,
            filters_for_entity=lambda e: (filters_empty_b[1] or [Marker("minecraft:stone", 1)])
            if any(a.contains_entity_new(e) for a in [scan_two.areas[1]]) else
            (filters_empty_b[0] if any(a.contains_entity_new(e) for a in [scan_two.areas[0]]) else None),
            cache=cache)
check("③-c 反例：把「空集」当成「全收」⇒ 区域 B 的 stone 也被吸（③-2 变红）",
      cache.get("minecraft:stone", 0) > 0, "cache=%r" % (cache,))

# 经验 / 流体：区域模式必须走同一条几何（静态锚点见 ④）
check("③-4 经验球与掉落物共用同一套区域包含口径（模型层：同 contains_entity_new）",
      Box(0, 63, 0, 0, 63, 0).contains_entity_new(Dropped(Item("x"), 0, 64, 0)))

# =====================================================================================
# ④ 源码锚点：三条修法 + P1 数据模型 + 界面状态行
# =====================================================================================
section("④ 源码锚点（回退即红）")

hook = code_only(read(HOOK_PATH))
be = code_only(read(BE_PATH))
adv = code_only(read(ADV_PATH))
menu = code_only(read(MENU_PATH))
adv_menu = code_only(read(ADV_MENU_PATH))
screen = code_only(read(SCREEN_PATH))
adv_screen = code_only(read(ADV_SCREEN_PATH))
base_screen = code_only(read(BASE_SCREEN_PATH))
panel = code_only(read(PANEL_PATH))
packet = code_only(read(PACKET_PATH))

check("④-1 AreaScanHook 提供 entityQueryAreas()：按簇多盒（默认单盒，盒向上扩 1 格）",
      "default List<AABB> entityQueryAreas()" in hook and "expandTowards(0.0D, 1.0D, 0.0D)" in hook)
check("④-2 AreaScanHook 提供 containsEntity()：占用格 / 所站格 / 上方格 任一命中",
      "default boolean containsEntity(final Entity entity)" in hook
      and "contains(pos) || contains(pos.below()) || contains(pos.above())" in hook)
check("④-3 掉落物扫描：逐簇查询盒 entityQueryAreas + containsEntity（旧写法两条都消失）",
      be.count("final List<AABB> boxes = areas.entityQueryAreas();") == 2
      and be.count("found.removeIf(entity -> !areas.containsEntity(entity));") == 2
      and "areas.contains(entity.blockPosition())" not in be)
check("④-4 经验球扫描同样走新口径（不能只修掉落物）",
      be.count("ExperienceOrb.class, box") >= 1
      and be.count("entityQueryAreas()") == 2)
check("④-5 P0-3：旧档标记数量归一（MarkerAmountV2 版本键 + 只在旧档归一 + 写档补键）",
      "TAG_MARKER_AMOUNT_V2" in be
      and "final boolean legacyAmounts = !tag.getBoolean(TAG_MARKER_AMOUNT_V2);" in be
      and "tag.putBoolean(TAG_MARKER_AMOUNT_V2, true);" in be
      and "normalizeLoadedMarkerAmount(" in be)
check("④-6 P0-3：失败忽略窗口 100 → 20 tick",
      "private static final int IGNORE_TICKS = 20;" in be)
check("④-7 状态码：11 个（OK + 10 个「为什么不吸」）且每轮计算",
      all(token in be for token in (
          "ABSORB_STATUS_OK = 0", "ABSORB_STATUS_NO_NETWORK = 1", "ABSORB_STATUS_INACTIVE = 2",
          "ABSORB_STATUS_TOGGLE_OFF = 3", "ABSORB_STATUS_NO_MARKER = 4",
          "ABSORB_STATUS_NO_TARGET = 5", "ABSORB_STATUS_THRESHOLD = 6",
          "ABSORB_STATUS_CACHE_FULL = 7", "ABSORB_STATUS_AREA_NO_SELECTOR = 8",
          "ABSORB_STATUS_AREA_EMPTY = 9", "ABSORB_STATUS_AREA_DIMENSION = 10",
          "absorbStatusReason = computeAbsorbStatus();")))
check("④-8 状态优先级：网络 → 未激活 → 区域 → 开关 → 已吸到 → 门槛 → 无标记 → 缓存满 → 无目标",
      be.find("return ABSORB_STATUS_NO_NETWORK;") < be.find("return ABSORB_STATUS_INACTIVE;")
      < be.find("final int areaReason = absorbStatusOverride();")
      < be.find("return ABSORB_STATUS_TOGGLE_OFF;")
      < be.find("return ABSORB_STATUS_THRESHOLD;")
      < be.find("return ABSORB_STATUS_NO_MARKER;")
      < be.find("return ABSORB_STATUS_CACHE_FULL;")
      < be.find("return ABSORB_STATUS_NO_TARGET;"))
check("④-9 基础版吸收侧按「过滤集合身份」分桶（同一区域的实体必须拿到同一个列表对象）",
      "final List<List<MarkerEntry>> contexts = new ArrayList<>(2);" in be
      and "if (contexts.get(c) == filters) {" in be
      and "protected List<MarkerEntry> filtersForEntity(" in be)
# 第 112 轮：每区域过滤集合从「方块实体上的 Map<Integer,...>（键 = 序号）」搬到
# 「选定器物品上的 AreaMarks（键 = 区域几何）」，于是本组判据改成「同一语义的新落点」：
# 仍然是「每区域一份过滤集合」，仍然有 NO_FILTERS 空集安全默认，仍然按<b>可用位次</b>取。
# 见 docs/ROUND112_AREA_MARKS_PORTABLE.md 与 support/AreaMarks.java。
check("④-10 高级版：每区域过滤集合（第 112 轮起存在选定器物品的 AreaMarks 上）+ 空集安全常量 + 可用位次口径",
      "AreaMarks" in adv
      and "private static final List<MarkerEntry> NO_FILTERS = List.of();" in adv
      and "public List<MarkerEntry> getAreaFilter(final int areaIndex)" in adv
      and "private Area areaAtUsableIndex(final int usableIndex)" in adv)
check("④-11 高级版 filtersForEntity：不在任何区域 ⇒ null（不吸）；在区域 ⇒ 用该区域集合",
      "protected List<MarkerEntry> filtersForEntity(final Entity entity" in adv
      and "return marksFor(area);" in adv
      and adv.count("return null;") >= 1)
check("④-12 高级版 NBT：AreaFilters / Index / Entries（每区域一份）",
      'private static final String TAG_AREA_FILTERS = "AreaFilters";' in adv
      and 'TAG_AREA_FILTER_INDEX = "Index";' in adv
      and 'TAG_AREA_FILTER_ENTRIES = "Entries";' in adv
      and "tag.put(TAG_AREA_FILTERS, filters);" in adv
      and "tag.getList(TAG_AREA_FILTERS, Tag.TAG_COMPOUND)" in adv)
check("④-13 高级版状态码：没装选定器 / 区域为空 / 维度不符 一一对应",
      "ABSORB_STATUS_AREA_NO_SELECTOR" in adv and "ABSORB_STATUS_AREA_EMPTY" in adv
      and "ABSORB_STATUS_AREA_DIMENSION" in adv)
check("④-14 菜单钩子：markerWindowEntries / markerEntryAt / writeMarkerAt / addMarkerFromUi / applyMarkerConfig",
      all(token in menu for token in (
          "protected java.util.List<MarkerEntry> markerWindowEntries(",
          "protected MarkerEntry markerEntryAt(",
          "protected void writeMarkerAt(",
          "protected CollectionCacheBlockEntity.MarkerAddResult addMarkerFromUi(",
          "protected void applyMarkerConfig(",
          "protected boolean applyMarkerTags(")))
check("④-15 高级菜单覆写钩子：编辑区域时 ghost 窗口 / 写入 / 配置全部落到该区域",
      "protected void writeMarkerAt(final int markerIndex" in adv_menu
      and "advanced.addAreaMarker(editingAreaIndex, carried);" in adv_menu
      and "advanced.removeAreaMarker(editingAreaIndex, markerIndex);" in adv_menu
      and "advanced.getAreaMarker(editingAreaIndex, start + i)" in adv_menu
      and "advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt," in adv_menu)
check("④-16 菜单数据槽：编辑上下文 6 + 逐区域计数 7..70（AREA_DATA_COUNT 由源码常量算出）",
      "DATA_AREA_EDITING = 6;" in adv_menu
      and "DATA_AREA_FILTER_BASE = 7;" in adv_menu
      and "AREA_LIST_SLOT_MAX = 64;" in adv_menu
      and "AdvancedCollectionCacheBlockEntity.areaPanelDataCount() + 1 + AREA_LIST_SLOT_MAX" in adv_menu)
check("④-17 C2S 包：SetAreaFilterContextPacket 存在且服务端校验（旁观者 / containerId / stillValid / 越界收敛）",
      "record SetAreaFilterContextPacket(int containerId, int areaIndex)" in packet
      and "player.isSpectator()" in packet
      and "menu.containerId != packet.containerId()" in packet
      and "menu.stillValid(player)" in packet
      and "menu.setEditingArea(packet.areaIndex());" in packet)
def anchor_18(src):
    """④-18 的恰好判据（反例也用它，避免两处漂移）。"""
    return ("protected Component absorbStatusText()" in src
            and "private int absorbStatusColor()" in src
            and "drawStatusLine(guiGraphics);" in src
            and "RsccTextFit.drawFitBlock(" in src
            and "protected static final int STATUS_LINE_W = 240;" in src
            and "trimToWidth" not in src)


def anchor_19(src, base):
    """④-19 的恰好判据（反例也用它，避免两处漂移）。

    第 131 轮**诚实反转**（用户图 1/2/3「框起的部分全部删除」）：
      * 第 106/107 轮钉的是「编辑上下文摘要（editAreaSummary）画在整行状态带 + 右端直接画
        过滤图标（collectFrameIcons → drawFilterIcons）+ 匹配区空态提示（drawMatchAreaHint 覆写）」；
      * 本轮用户逐条点名删掉这三处，因此判据反向：**这些落点必须一个都不存在**
        （留一处就是留一条复活路径）。「正在编辑哪个区域」改由匹配区标题带的
        `match_label` / `match_label.none` 表达 —— 这一条仍然正向钉住，信息没有丢。
      * 「只在真问题时出字」仍由**基类**唯一出口保证：`isStatusVisible(code)` 先判、再画
        （高级界面不再有第二个状态带绘制出口）。
      * 「零省略号」照旧：本文件不许出现 trimToWidth / Required。
    """
    return ("editAreaSummary" not in src
            and 'LANG + "filter.row.empty"' not in src
            and "protected void drawStatusLine(" not in src
            and "drawStatusBand(" not in src
            and "collectFrameIcons" not in src
            # 匹配区空态提示（基类 drawMatchAreaHint 的覆写）同属用户点名的「框起部分」。
            and "drawMatchAreaHint" not in src
            # drawFilterIcons 的**定义**保留给旧「区域列表」界面（AreaSelectorScreen 还在调），
            # 因此这里钉的是「本屏零调用点」：文件里只剩那一处 static 定义。
            and src.count("drawFilterIcons(") == 1
            and 'LANG + "match_label' in src
            and "protected static boolean isStatusVisible(final int code)" in base
            and "if (!isStatusVisible(code)) {" in base
            and "trimToWidth" not in src
            and "Required" not in src)


check("④-18 基础界面：状态行（服务端算、客户端只翻文案）画在整行留白并完整显示 ——"
      "第 104 轮：禁止省略号，放不下只缩字号（RsccTextFit），状态文本仍是服务端状态码的唯一翻译处",
      anchor_18(screen))
check("④-19 高级界面：摘要行 / 状态带右端过滤图标 / 匹配区空态提示都已删除（第 131 轮用户逐条点名）；"
      "「正在编辑哪个区域」只由匹配区标题带的 match_label / match_label.none 表达，"
      "状态带仍由基类 isStatusVisible 保证「只在真问题时出字」（零省略号、只缩字号）",
      anchor_19(adv_screen, base_screen))
check("④-20 「未命名」不再直接展示：区域行（侧栏 AreaStripPanel，第 130 轮起的落点）用"
      "「序号 + 名称（无名退回『区域 N』）+ 紧凑坐标串」，高级界面不再有 filter.* 摘要行",
      '(row.rawIndex() + 1) + ". "' in panel
      and "displayName(" in panel
      and "LANG_REGION" in panel
      and "compactCoords(" in panel
      and "filter.row" not in adv_screen and "filter.none" not in adv_screen)

# =====================================================================================
# ⑤ 反例自证（源码级）：删掉关键行 ⇒ 对应锚点必须变红
# =====================================================================================
section("⑤ 源码级反例自证")

def mutated(text, old, new):
    assert old in text, "反例锚点不存在：%r" % old[:60]
    return text.replace(old, new, 1)

check("⑤-b1 反例：把 containsEntity 退回只看 blockPosition ⇒ ④-2 判负",
      "contains(pos) || contains(pos.below())" not in mutated(
          hook, "contains(pos) || contains(pos.below()) || contains(pos.above())", "contains(pos)"))
check("⑤-b2 反例：把每簇查询盒退回总并集（不向上扩）⇒ ④-1 判负",
      "expandTowards(0.0D, 1.0D, 0.0D)" not in mutated(
          hook, "return union == null ? null : union.expandTowards(0.0D, 1.0D, 0.0D);",
          "return union;"))
check("⑤-b3 反例：删掉「旧档才归一」的版本判断 ⇒ ④-5 判负",
      "!tag.getBoolean(TAG_MARKER_AMOUNT_V2)" not in mutated(
          be, "final boolean legacyAmounts = !tag.getBoolean(TAG_MARKER_AMOUNT_V2);", ""))
check("⑤-b4 反例：把失败忽略窗口改回 100 ⇒ ④-6 判负",
      "IGNORE_TICKS = 20" not in mutated(be, "IGNORE_TICKS = 20", "IGNORE_TICKS = 100"))
check("⑤-b5 反例：高级版 filtersForEntity 改成「不看区域、一律用固定集合」⇒ ④-11 判负",
      "return marksFor(area);" not in mutated(
          adv, "return marksFor(area);", "return marksFor(null);"))
check("⑤-b6 反例：删掉空集安全常量 ⇒ ④-10 判负",
      "NO_FILTERS = List.of()" not in mutated(adv, "NO_FILTERS = List.of()", "NO_FILTERS = null"))
check("⑤-b7 反例：删掉包的服务端校验（containerId / stillValid）⇒ ④-17 判负",
      "menu.stillValid(player)" not in mutated(packet, "|| !menu.stillValid(player)", ""))
check("⑤-b8 反例：基础界面把状态行退回「按像素截断 + 150px」⇒ ④-18 判负（第 104 轮：界面文字禁止省略号）",
      not anchor_18(mutated(
          screen, "drawStatusLine(guiGraphics);",
          "drawStatusLine(guiGraphics); final String legacy = trimToWidth(statusLine.getString(), 150);")))
check("⑤-b9 反例：把状态带右端过滤图标（collectFrameIcons + drawFilterIcons 调用）拼回高级界面 ⇒ ④-19 判负",
      not anchor_19(mutated(
          adv_screen, "public static int drawFilterIcons(",
          "private void collectFrameIcons() { drawFilterIcons(null, 0, 0, null, 0); }\n"
          "    public static int drawFilterIcons("), base_screen))
check("⑤-b10 反例：把匹配区空态提示覆写（drawMatchAreaHint）拼回高级界面 ⇒ ④-19 判负",
      not anchor_19(mutated(
          adv_screen, "protected Component matchRegionLabel()",
          "protected void drawMatchAreaHint(final GuiGraphics guiGraphics) { }\n"
          "    protected Component matchRegionLabel()"), base_screen))
check("⑤-c 源码口径：两个主界面里「按像素截断」的私有实现已删除（第 104 轮口径），"
      "省略号字面量交给 tools/selfcheck_no_ellipsis.py 全仓库扫",
      "trimToWidth" not in screen and "trimToWidth" not in adv_screen
      and "drawEditAreaSummary" not in adv_screen)

print("\n" + "=" * 78)
if failures:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(failures), len(checks)))
    for item in failures:
        print("  - %s" % item)
    sys.exit(1)
print("[result] SELFCHECK OK：%d 项断言（含 3 类行为模型 + 11 条源码反例）；"
      "归流缓存仓 P0（区域底板实体 / 旧档数量门槛 / 失败退避）与 P1（每区域独立过滤器）"
      "的判定规则与实现锚点均成立；第 104 轮：状态行 / 每区域摘要改为整行留白 + 缩字号完整显示；"
      "第 131 轮：摘要行 / 右端过滤图标 / 匹配区空态提示按用户要求删除 ⇒ 判据已反转（必须不存在）"
      % len(checks))
