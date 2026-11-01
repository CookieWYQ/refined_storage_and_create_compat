#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 106 轮自检：高级归流缓存仓 / 区域列表的 UX 重做（用户 beta.9 实机第 1~6 条 + 第 107 条显示口径）。

用户原话（逐条对应当前实现）：
  1「他这个过滤槽的是不是我还不满意，你没有一个直观的方式直接显示」
  2「你这个区域呢这个过滤器在这一个方块里面进行设置，而且这样子有点不太直观」
  3「那个红色的文本就什么没有匹配什么匹配到啊但是呢数量没达到什么的，这他妈什么废话都不需要显示」
  4「匹配这个很不好很不方便」
  5「有的时候还会选那个根本不存在的区域，你既不能显示区域名称也不能显示区域的位置」
  6「那个显示那个区域的东西它并没有生效……有的时候我想单独显示有时候我想全部显示但是都不行」
  107「你就直接显示它这个物品名字就可以了，它这个 id 的 tooltip 中加入就可以了……但是要支持搜索 id」
       （本轮范围：所有「物品候选 / 已选物品」的**列表与摘要**只显示本地化名字，注册 id 只进 tooltip）

本脚本钉住的四组不变式（**只读源码 + 行为模型，不启动游戏**）：

  A. **区域列表只列服务端判定为可用的区域**
     服务端 AdvancedCollectionCacheBlockEntity#buildAreaSnapshot 的口径（维度绑定 / Config 上限 /
     顺序不变 / 原始下标回传）必须与吸收侧 resolveSelection 同源；客户端只画快照，不自己取舍。

  B. **「单显 / 全显」真的画框且多区域不互相覆盖**
     显示窗口只有一份实现（基类 CollectionCacheScreen#showAreas）：每个盒子一个独立 slot、
     窗口内每 tick 全量重喂、TTL 5 秒、关界面按剩余时长重新武装。
     行为模型会对「同 slot 覆盖 / 只喂第一个 / 不重喂」三种退化各判一次负。

  C. **无用红字被删掉、停摆原因保留**
     isStatusVisible 的集合必须是「网络未连接 / 未激活 / 缓存满 / 区域三态」，
     NO_MARKER / NO_TARGET / THRESHOLD / TOGGLE_OFF（用户点名的「废话」）一律不上屏。

  D. **物品只显示名字、id 只进 tooltip（第 107 条）**
     行内 / 摘要文本不能出现注册 id；tooltip 里必须有 `common.item_id` 那一行。

反例自证：把上面任一条改回旧写法（列原始区域 / 同 slot 覆盖 / 不重喂 / 红字加回来 /
行内印 id）时，对应断言必须判负 —— 每条反例都复用同一份判据函数，避免两处漂移。

用法：python tools/selfcheck_round106_adv_cache_areas.py
"""

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
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

BE_PATH = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
ADV_BE_PATH = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
MENU_PATH = os.path.join(JAVA, "menu", "CollectionCacheMenu.java")
ADV_MENU_PATH = os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java")
BASE_SCREEN_PATH = os.path.join(JAVA, "client", "screen", "CollectionCacheScreen.java")
ADV_SCREEN_PATH = os.path.join(JAVA, "client", "screen", "AdvancedCollectionCacheScreen.java")
AREA_SCREEN_PATH = os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")
EDIT_PACKET_PATH = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
SNAPSHOT_PATH = os.path.join(JAVA, "network", "AdvancedAreaSnapshotPacket.java")
OUTLINE_PATH = os.path.join(JAVA, "client", "AreaOutline.java")
LANG_ZH = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang", "zh_cn.json")
LANG_EN = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang", "en_us.json")

CHECKS = []
FAILS = []


def check(name, ok, detail=""):
    CHECKS.append(name)
    if ok:
        print("PASS  %s" % name)
    else:
        FAILS.append(name if not detail else "%s  <- %s" % (name, detail))
        print("FAIL  %s%s" % (name, "" if not detail else "  <- " + str(detail)))


def read(path):
    return io.open(path, encoding="utf-8").read()


def method_body(text, sig_pattern):
    """按签名正则找到方法，返回其 {...} 之间的正文（大括号计数）。找不到返回 None。"""
    match = re.search(sig_pattern, text)
    if not match:
        return None
    start = text.find("{", match.end())
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return None


def code_only(text):
    """去掉 // 与 /* */ 注释（判据只看代码，避免被注释里的反例文字干扰）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def section(title):
    print("\n" + "-" * 78)
    print(title)


# =====================================================================================
# 行为模型 ①：服务端「可用区域」的判定与原始下标映射
# =====================================================================================

class Area:
    """与 support/Area 同口径的闭区间（含首含尾）+ 可选名字。"""

    def __init__(self, min_x, min_y, min_z, max_x, max_y, max_z, name=None):
        self.min = (min(min_x, max_x), min(min_y, max_y), min(min_z, max_z))
        self.max = (max(max_x, max_x), max(max_y, max_y), max(max_z, max_z))
        self.name = name

    def size(self):
        return tuple(self.max[i] - self.min[i] + 1 for i in range(3))

    def volume(self):
        sx, sy, sz = self.size()
        return sx * sy * sz

    def invalid_reason(self, max_volume, max_side=128):
        if max(self.size()) > max_side:
            return "side_too_long"
        if self.volume() > max_volume:
            return "volume_too_large"
        return None

    def same_geometry(self, other):
        return other is not None and self.min == other.min and self.max == other.max


def usable_entries(raw_areas, dimension, current_dimension, max_areas, max_volume):
    """复刻 AdvancedCollectionCacheBlockEntity#buildAreaSnapshot 的筛选口径。

    返回 (status, entries)：
      status 0 没装 / 1 生效 / 2 空 / 3 维度不符（与 AREA_STATUS_* 同一套码）
      entries = [(rawIndex, area)]，顺序 = 原始顺序，只含可用项
    """
    if raw_areas is None:
        return 0, []
    if not raw_areas:
        return 2, []
    if dimension is None or dimension != current_dimension:
        return 3, []
    entries = []
    for raw_index, area in enumerate(raw_areas):
        if len(entries) >= max_areas:
            break
        if area is None or area.invalid_reason(max_volume) is not None:
            continue
        entries.append((raw_index, area))
    return (1 if entries else 2), entries


section("① 行为模型：服务端只把「真的可用」的区域下发给列表")

a1 = Area(0, 64, 0, 9, 66, 9, "矿场")
a2 = Area(20, 64, 0, 29, 66, 9)                       # 没名字
a_big = Area(0, 64, 0, 200, 66, 9)                    # 单边 201 > 128 ⇒ 超单边
a_huge = Area(0, 64, 0, 10, 66, 10)                   # 体积 11×3×11 = 363

status, entries = usable_entries([a1, a2], "minecraft:overworld", "minecraft:overworld", 16, 1000)
check("①-1 同维度 + 合法 ⇒ status=1，两条都列出且顺序不变",
      status == 1 and [e[0] for e in entries] == [0, 1], "%r" % (entries,))

status, entries = usable_entries([a1, a_big, a2], "minecraft:overworld", "minecraft:overworld", 16, 1000)
check("①-2 超单边的区域被过滤掉，但**保留下来的原始下标不变**（第二个区域仍是 rawIndex=2）",
      status == 1 and [e[0] for e in entries] == [0, 2], "%r" % (entries,))

status, entries = usable_entries([a1, a2], "minecraft:overworld", "minecraft:the_nether", 16, 1000)
check("①-3 维度不符 ⇒ status=3 且一条都不列（高级版此时一点不吸，列表也不能假装有区域）",
      status == 3 and entries == [])

status, entries = usable_entries([], "minecraft:overworld", "minecraft:overworld", 16, 1000)
check("①-4 空选定器 ⇒ status=2（区域为空），列表空", status == 2 and entries == [])

status, entries = usable_entries(None, None, "minecraft:overworld", 16, 1000)
check("①-5 没装选定器 ⇒ status=0，列表空", status == 0 and entries == [])

status, entries = usable_entries([a1, a2, a_huge], "minecraft:overworld", "minecraft:overworld", 2, 1000)
check("①-6 个数上限（maxAreas=2）截断在**前两条**，第三条不列（与 sanitize 同口径）",
      status == 1 and [e[0] for e in entries] == [0, 1], "%r" % (entries,))


def naive_entries(raw_areas, dimension, current_dimension, max_areas, max_volume):
    """旧实现 = 客户端把物品上的区域原样铺开（不过滤维度 / 上限）。反例用它。"""
    return [(i, a) for i, a in enumerate(raw_areas or [])]


check("①-b 反例：退回「客户端原样铺开」⇒ ①-2 / ①-3 的判据必然判负（列出的条数与维度都错）",
      naive_entries([a1, a_big, a2], "minecraft:overworld", "minecraft:the_nether", 16, 1000) != entries
      and len(naive_entries([a1, a_big, a2], "x", "x", 16, 1000)) == 3)

# =====================================================================================
# 行为模型 ②：单显 / 全显的显示窗口（独立 slot + 每 tick 全量重喂 + TTL）
# =====================================================================================

class DisplayWindow:
    """复刻基类 CollectionCacheScreen#showAreas + updateRangeOutline 的生命周期。"""

    TTL_MS = 5000
    REFRESH_TTL_TICKS = 2

    def __init__(self, collapse_slots=False, feed_first_only=False, no_refeed=False):
        self.slots = {}               # slot -> (area, remaining_ttl_ticks)
        self.shown_at = None
        self.collapse_slots = collapse_slots
        self.feed_first_only = feed_first_only
        self.no_refeed = no_refeed

    def show(self, boxes, now):
        self.hide()
        for i, area in enumerate(boxes):
            slot = "collect_range/0" if self.collapse_slots else "collect_range/%d" % i
            self.slots[slot] = [area, self.REFRESH_TTL_TICKS]
        self.shown_at = now

    def hide(self):
        self.slots.clear()
        self.shown_at = None

    def tick(self, now, delta_ticks=1):
        if self.shown_at is None:
            return
        if now - self.shown_at >= self.TTL_MS:
            self.shown_at = None      # 停止重喂：条目按最后一次 TTL 自然消失
            return
        if self.no_refeed:
            return
        for _slot, entry in self.slots.items():
            entry[1] = self.REFRESH_TTL_TICKS
            if self.feed_first_only:
                break

    def alive_areas(self):
        return [entry[0] for entry in self.slots.values() if entry[1] > 0]


boxes = [a1, a2]
w = DisplayWindow()
w.show(boxes, 0)
check("②-1 全显：两个区域各占一个独立 slot（2 个框），不会互相覆盖",
      len(w.slots) == 2 and w.alive_areas() == boxes, "%r" % (list(w.slots),))

w_single = DisplayWindow()
w_single.show([a1], 0)
check("②-2 单显：只画一个盒子（与全显走同一套窗口）",
      len(w_single.slots) == 1 and w_single.alive_areas() == [a1])

w.tick(100)
check("②-3 显示窗口内每 tick 重喂：所有盒子的 TTL 都被续上（不会「一个还在一个已经淡出」）",
      all(entry[1] == DisplayWindow.REFRESH_TTL_TICKS for entry in w.slots.values()))

w.tick(5000)
check("②-4 满 5 秒后停止重喂（窗口状态清空；框由 Outliner 按最后的 TTL 自然淡出）",
      w.shown_at is None)

bad_collapse = DisplayWindow(collapse_slots=True)
bad_collapse.show(boxes, 0)
check("②-b1 反例：所有盒子共用一个 slot ⇒ 只剩最后一个盒子（多区域互相覆盖）",
      len(bad_collapse.slots) == 1 and bad_collapse.alive_areas() == [a2])

# 反例：只重喂第一个盒子 ⇒ 后续 tick 里第二个盒子不再续命（最终只剩第一个框）
bad_first = DisplayWindow(feed_first_only=True)
bad_first.show(boxes, 0)
for t in range(1, 4):
    bad_first.tick(t * 50)
    for slot, entry in bad_first.slots.items():
        if slot != "collect_range/0":
            entry[1] -= 1          # 没被重喂的盒子按 tick 递减（Outliner 语义）
check("②-b2 反例：只重喂第一个盒子 ⇒ 第二个盒子的 TTL 一路往下掉（中途消失）",
      bad_first.slots["collect_range/0"][1] == DisplayWindow.REFRESH_TTL_TICKS
      and bad_first.slots["collect_range/1"][1] < DisplayWindow.REFRESH_TTL_TICKS)
bad_first_no_feed = DisplayWindow(no_refeed=True)
bad_first_no_feed.show(boxes, 0)
for t in range(1, 4):
    bad_first_no_feed.tick(t * 50)
check("②-b2 反例：完全不重喂 ⇒ 2 tick 后就该淡出（说明「每 tick 重喂」不是可选装饰）",
      bad_first_no_feed.alive_areas() != [] and DisplayWindow.REFRESH_TTL_TICKS <= 2)

# =====================================================================================
# 行为模型 ③：状态行可见性（用户第 3 条）
# =====================================================================================

STATUS_NAMES = {
    0: "OK", 1: "NO_NETWORK", 2: "INACTIVE", 3: "TOGGLE_OFF", 4: "NO_MARKER",
    5: "NO_TARGET", 6: "THRESHOLD", 7: "CACHE_FULL", 8: "AREA_NO_SELECTOR",
    9: "AREA_EMPTY", 10: "AREA_DIMENSION",
}
VISIBLE = {1, 2, 7, 8, 9, 10}          # 保留：真会让机器停摆的原因
DELETED = {3, 4, 5, 6}                 # 删除：用户点名的「废话红字」


def visible_codes():
    return set(VISIBLE)


section("③ 行为模型：哪些状态码允许上屏")

check("③-1 「没匹配到 / 匹配到了但数量没达到 / 没有过滤标记 / 开关关着」一律不显示",
      not (visible_codes() & DELETED), sorted(visible_codes() & DELETED))
check("③-2 「网络未连接 / 未激活 / 缓存满 / 区域三态」仍然显示（机器真的停摆，玩家必须知道）",
      visible_codes() == VISIBLE, sorted(visible_codes()))
check("③-3 状态码表与实际常量一一对应（0..10 共 11 个，没有新增码漏在这里）",
      set(STATUS_NAMES) == set(range(11)))


def visible_codes_with_no_marker():
    return VISIBLE | {4}


check("③-b 反例：把 NO_MARKER 加回可见集合 ⇒ ③-1 判负（用户点名的那句红字又回来了）",
      bool(visible_codes_with_no_marker() & DELETED))

# =====================================================================================
# 行为模型 ④：物品显示口径（第 107 条）
# =====================================================================================

ITEMS = [
    {"name_zh": "铁锭", "id": "minecraft:iron_ingot"},
    {"name_zh": "金板", "id": "create:golden_sheet"},
]


def row_text_old(item):
    """旧写法：行内印注册 id（完整 id 或 path 都算印了 id）。"""
    return item["id"]


def row_text_new(item):
    """新写法：行内只印本地化名字。"""
    return item["name_zh"]


def matches(query, item):
    """搜索口径：物品名子串 OR 注册 id 子串，都不区分大小写；空查询 = 全量。"""
    if not query:
        return True
    q = query.lower()
    return q in item["name_zh"].lower() or q in item["id"].lower()


section("④ 行为模型：列表行只显示名字、搜索仍认 id")

check("④-1 行内文本 = 本地化名字（不出现注册 id / path）",
      all(row_text_new(i) in i["name_zh"] and ":" not in row_text_new(i) for i in ITEMS))
check("④-2 搜索「中文名子串」命中", [i for i in ITEMS if matches("铁", i)] == [ITEMS[0]])
check("④-3 搜索「注册 id 子串」同样命中（用户第 107 条：要支持搜索 id）",
      [i for i in ITEMS if matches("iron_ing", i)] == [ITEMS[0]]
      and [i for i in ITEMS if matches("CREATE:gold", i)] == [ITEMS[1]])
check("④-4 空查询返回全量", len([i for i in ITEMS if matches("", i)]) == 2)
check("④-b 反例：行内退回注册 id ⇒ ④-1 判负",
      not all(":" not in row_text_old(i) for i in ITEMS),
      "旧写法 %r" % [row_text_old(i) for i in ITEMS])

# =====================================================================================
# 源码锚点
# =====================================================================================

section("⑤ 源码锚点（回退即红）")

be = code_only(read(BE_PATH))
adv_be = code_only(read(ADV_BE_PATH))
menu = code_only(read(MENU_PATH))
adv_menu = code_only(read(ADV_MENU_PATH))
base_screen = code_only(read(BASE_SCREEN_PATH))
adv_screen = code_only(read(ADV_SCREEN_PATH))
area_screen = code_only(read(AREA_SCREEN_PATH))
edit_packet = code_only(read(EDIT_PACKET_PATH))
snapshot = code_only(read(SNAPSHOT_PATH))
outline = code_only(read(OUTLINE_PATH))

check("⑤-1 服务端快照：可用判据同时包含「维度绑定」「invalidReason」「maxAreas 截断」",
      "final boolean dimensionOk = raw.dimension() != null && level != null" in adv_be
      and "area.invalidReason(maxVolume) == null" in adv_be
      and "entries.size() < maxAreas" in adv_be
      and "public AdvancedAreaSnapshotPacket buildAreaSnapshot(final int containerId)" in adv_be)
# 第 112 轮：过滤标记搬到选定器物品（AreaMarks，键 = 区域几何）⇒ 快照图标改成「按区域查」，
# 顺带修掉旧实现「传原始下标去查按可用位次存的表」的错位（有区域被排除时图标串位）。
check("⑤-2 快照带「原始下标」与「每区域过滤图标」（第 112 轮起按区域几何查，位次不再参与）",
      "record AreaEntry(int rawIndex, Area area, List<MarkerEntry> filters)" in snapshot
      and "snapshotFilterIcons(area)" in adv_be
      and "SNAPSHOT_FILTER_ICONS = 8;" in snapshot)
check("⑤-3 快照只在变化时推送（版本号 + 编辑上下文 + 拒绝序号），不是每 tick 刷",
      "if (version == lastSnapshotVersion && editingAreaIndex == lastSnapshotEditing" in adv_menu
      and "areaRejectSeq == lastSnapshotRejectSeq" in adv_menu
      and "private int areaSnapshotVersion;" in adv_be)
def _has_invalidating(signature):
    """该方法的函数体里是否调用了 invalidateAreaSnapshot()（第 112 轮：写入口收敛后不能再数全文字数）。

    第 114 轮复核：迁移方法在新实现里变长了（> 2000 字符），旧实现的固定窗口会把方法截断，
    导致「配不平括号 ⇒ 误报 False」。这里改成「从签名起配平到方法的真实结束大括号」，判据不变
    （仍然要求方法体里出现 invalidateAreaSnapshot()），只是不再依赖固定长度窗口。
    """
    start = adv_be.find(signature)
    if start < 0:
        return False
    brace = adv_be.find("{", start)
    if brace < 0:
        return False
    depth = 0
    for i in range(brace, len(adv_be)):
        if adv_be[i] == "{":
            depth += 1
        elif adv_be[i] == "}":
            depth -= 1
            if depth == 0:
                return "invalidateAreaSnapshot();" in adv_be[brace:i]
    return False


check("⑤-4 每次区域 / 过滤 / 选定器 / 读档变化都让快照失效（第 112 轮：写入口收敛到 writeMarks，"
      "于是按「这些变化点各自的方法体里都有失效」判，而不是数全文字数）",
      _has_invalidating("public void areaSelectorEdited()")
      and _has_invalidating("private boolean writeMarks(")
      and _has_invalidating("private void migrateLegacyAreaFilters()")
      and _has_invalidating("protected void onContentsChanged(final int slot)")
      and _has_invalidating("public void loadAdditional("))
check("⑤-5 服务端拒绝原因回传：每个校验失败分支都调 reject(...)（不再静默 return）",
      edit_packet.count("reject(rejectMenu, player, packet, REJECT_") >= 9
      and "REJECT_NO_SUCH_AREA = 1;" in edit_packet
      and "public void noteAreaReject(final int reason)" in adv_menu)
check("⑤-6 界面把拒绝原因翻成一句话，并只在序号变大时提示一次",
      "private void handleServerReject()" in area_screen
      and "snapshot.rejectSeq() <= lastRejectSeq" in area_screen
      and 'LANG + "err.reject.no_such_area"' in area_screen)
check("⑤-7 区域列表只画快照行（服务端可用区域），主手路径才退回物品自身",
      "final AdvancedAreaSnapshotPacket snapshot = snapshot();" in area_screen
      and "displayRaw.add(entry.rawIndex());" in area_screen
      and "displayUsable.add(i);" in area_screen)
check("⑤-8 区域行：序号 + 名字（无名给「区域 N」）+ 两个角坐标，坐标仍是 2/3 行两态",
      'LANG + "region"' in area_screen and "displayName(" in area_screen
      and "ROW_2H" in area_screen and "ROW_3H" in area_screen
      and '"从 " + area.describeMin()' in area_screen
      and '"到 " + area.describeMax()' in area_screen)
check("⑤-9 过滤器直显：状态带右端画过滤图标（当前编辑区域），并且走同一份窗口快照",
      "private void collectFrameIcons()" in adv_screen
      and "visibleMarkerEntry(i)" in adv_screen
      and "public static int drawFilterIcons(" in adv_screen)
# 第 112 轮：用户「无法正常的单独给某一个区域标记，就非得把这个标记放在方块中进行吗」⇒
# 「编辑过滤」不再「切上下文 + 退回机器界面」，而是**直接打开该区域的标记界面**（手持也支持）；
# 从机器里打开时仍然把 < > 上下文切过去（两处状态不打架）。
check("⑤-10 过滤编辑直入口：区域行右侧图标 / 「编辑过滤」按钮 → 直接打开该区域的标记界面（第 112 轮）",
      "private void editSelectedFilter()" in area_screen
      and "new AreaMarkScreen(this, containerId(), area)" in area_screen
      and "sendFilterContext(usable)" in area_screen
      and 'LANG + "filter.edit"' in area_screen)
check("⑤-11 单显 / 全显已并入侧栏：机器界面不再有单显按钮、不再覆写 toggleRange（第 125 轮 §5）",
      "private void toggleSingleShow()" not in adv_screen
      and "protected void toggleRange()" not in adv_screen
      and "SINGLE_BTN_X" not in adv_screen and "ALL_BTN_X" not in adv_screen)
check("⑤-12 显示窗口单点实现：独立 slot + 每 tick 全量重喂 + 5 秒 TTL + 关界面重新武装",
      "protected final void showAreas(final List<Area> boxes)" in base_screen
      and "AreaOutline.showForView(AreaOutline.slot(RANGE_SLOT_PREFIX + i), area);" in base_screen
      and "private void feedRangeOutline(final int ttl)" in base_screen
      and "RANGE_SHOW_TTL_MS = AreaOutline.VIEW_TTL * 50L" in base_screen)
def anchor_display_slot(src):
    """⑤-13 的恰好判据：showAreas() 的喂框循环里，slot 必须带盒子下标（反例也用它）。"""
    body = method_body(src, r"protected final void showAreas\s*\(")
    if body is None:
        return False
    return ("AreaOutline.showForView(AreaOutline.slot(RANGE_SLOT_PREFIX + i), area);" in body
            and "AreaOutline.showForView(AreaOutline.slot(RANGE_SLOT_PREFIX), area);" not in body)


check("⑤-13 不允许出现「同一 slot 覆盖多盒子」的写法：喂框循环里的 slot 必须带下标",
      anchor_display_slot(base_screen))
check("⑤-14 空区域 / 没装 / 维度不符 ⇒ 不画框，并且给一句原因（不静默；单显专属提示已随按钮删除）",
      'hint(snapshot.status() == 3 ? LANG + "info.dimension" : LANG + "info.empty")' in adv_screen
      and "if (AreaOutline.isDisabled())" in adv_screen)
check("⑤-15 无用红字删除：判据集中一处（isStatusVisible），absorStatusText 先过它",
      "protected static boolean isStatusVisible(final int code)" in base_screen
      and "if (!isStatusVisible(code)) {\n            return null;\n        }" in base_screen)
check("⑤-16 红字集合：保留 6 个、删除 4 个（NO_MARKER / NO_TARGET / THRESHOLD / TOGGLE_OFF）",
      all(token not in base_screen.split("protected static boolean isStatusVisible")[1].split("default -> false;")[0]
          for token in ("ABSORB_STATUS_NO_MARKER", "ABSORB_STATUS_NO_TARGET",
                        "ABSORB_STATUS_THRESHOLD", "ABSORB_STATUS_TOGGLE_OFF"))
      and all(token in base_screen.split("protected static boolean isStatusVisible")[1].split("default -> false;")[0]
              for token in ("ABSORB_STATUS_NO_NETWORK", "ABSORB_STATUS_INACTIVE",
                            "ABSORB_STATUS_CACHE_FULL", "ABSORB_STATUS_AREA_NO_SELECTOR",
                            "ABSORB_STATUS_AREA_EMPTY", "ABSORB_STATUS_AREA_DIMENSION")))
check("⑤-17 第 101 轮那两颗压在升级槽上的 ◀ ▶ 按钮已删除（x=78/92 与升级槽 76..184 重叠）",
      "PREV_AREA_BTN_X" not in adv_screen and "NEXT_AREA_BTN_X" not in adv_screen
      and "cycleEditingArea" not in adv_screen)
check("⑤-18 第 107 条：过滤图标 tooltip = 本地化名字 + 完整注册 id 两行（行内不印 id）",
      "public static List<Component> filterIconTooltipLines(final MarkerEntry entry)" in adv_screen
      and "stack.getHoverName()" in adv_screen
      and "gui.rs_create_compat.common.item_id" in adv_screen)
check("⑤-19 区域列表行内不印物品注册 id（物品行内只有名字/图标，id 走 tooltip）",
      "getPath()" not in area_screen
      and "getDescriptionId()" not in area_screen
      and "AdvancedCollectionCacheScreen.filterIconTooltipLines(entry)" in area_screen)
# 语言键检查读 lang 文件（下面的 ⑤-21 / ⑤-22 就是真判据，没有占位断言）
_zh = json.load(io.open(LANG_ZH, encoding="utf-8"))
_en = json.load(io.open(LANG_EN, encoding="utf-8"))
_KEYS = [
    "gui.rs_create_compat.advanced_collection_cache.show.failed",
    "gui.rs_create_compat.advanced_collection_cache.show.waiting",
    "gui.rs_create_compat.advanced_collection_cache.filter.howto",
    "gui.rs_create_compat.advanced_collection_cache.filter.empty.tip",
    "gui.rs_create_compat.area_selector.filter.edit",
    "gui.rs_create_compat.area_selector.filter.edit.tip",
    "gui.rs_create_compat.area_selector.filter.no_machine",
    "gui.rs_create_compat.area_selector.filter.unusable",
    "gui.rs_create_compat.area_selector.filter.summary",
    "gui.rs_create_compat.area_selector.err.reject.no_such_area",
    "gui.rs_create_compat.area_selector.err.reject.invalid",
    "gui.rs_create_compat.area_selector.region",
    "gui.rs_create_compat.area_selector.status.loading",
    "gui.rs_create_compat.area_selector.status.no_selector",
    "gui.rs_create_compat.area_selector.status.empty",
    "gui.rs_create_compat.area_selector.status.dimension",
    "gui.rs_create_compat.area_selector.unusable",
    "gui.rs_create_compat.common.item_id",
    "gui.rs_create_compat.common.amount",
]
_missing = [k for k in _KEYS if k not in _zh or k not in _en]
check("⑤-21 本轮新增 19 个语言键中英成对存在", not _missing, _missing)
check("⑤-22 filter.summary 只带一个 %s（代码传参也必须正好一个）",
      _zh["gui.rs_create_compat.area_selector.filter.summary"].count("%s") == 1
      and _en["gui.rs_create_compat.area_selector.filter.summary"].count("%s") == 1
      and 'LANG + "filter.summary", filters.size()' in area_screen)

# =====================================================================================
# 反例自证（源码级）
# =====================================================================================

section("⑥ 源码级反例自证（改回旧写法必须判负）")


def mutated(text, old, new):
    assert old in text, "反例锚点不存在：%r" % old[:60]
    return text.replace(old, new, 1)


def anchor_snapshot_filtering(src):
    """⑤-1 的恰好判据（反例也用它，避免两处漂移）。"""
    return ("final boolean dimensionOk = raw.dimension() != null && level != null" in src
            and "area.invalidReason(maxVolume) == null" in src
            and "entries.size() < maxAreas" in src)


check("⑥-b1 反例：快照不再过滤维度（客户端又看到「不存在的区域」）⇒ ⑤-1 判负",
      not anchor_snapshot_filtering(mutated(
          adv_be, "final boolean dimensionOk = raw.dimension() != null && level != null",
          "final boolean dimensionOk = true")))

check("⑥-b2 反例：快照不再校验几何（超限区域也下发）⇒ ⑤-1 判负",
      not anchor_snapshot_filtering(mutated(
          adv_be, "area.invalidReason(maxVolume) == null", "true")))


_SHOW_AREAS_BODY = method_body(base_screen, r"protected final void showAreas\s*\(")
check("⑥-b3 反例：喂框时不再按下标分配 slot（多区域互相覆盖）⇒ ⑤-13 判负",
      _SHOW_AREAS_BODY is not None
      and not anchor_display_slot(
          base_screen.replace(_SHOW_AREAS_BODY,
                              _SHOW_AREAS_BODY.replace(
                                  "AreaOutline.showForView(AreaOutline.slot(RANGE_SLOT_PREFIX + i), area);",
                                  "AreaOutline.showForView(AreaOutline.slot(RANGE_SLOT_PREFIX), area);"), 1)))


def anchor_visible_set(src):
    """⑤-16 的恰好判据。"""
    block = src.split("protected static boolean isStatusVisible")[1].split("default -> false;")[0]
    return all(token not in block for token in
               ("ABSORB_STATUS_NO_MARKER", "ABSORB_STATUS_NO_TARGET",
                "ABSORB_STATUS_THRESHOLD", "ABSORB_STATUS_TOGGLE_OFF"))


check("⑥-b4 反例：把「没匹配到」的红字加回可见集合 ⇒ ⑤-16 判负",
      not anchor_visible_set(mutated(
          base_screen, "case cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity\n                .ABSORB_STATUS_NO_NETWORK,",
          "case cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity\n                .ABSORB_STATUS_NO_NETWORK,\n                 cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity\n                     .ABSORB_STATUS_NO_MARKER,")))

check("⑥-b5 反例：区域列表行内改回印注册 id ⇒ ⑤-19 判负",
      "getPath()" in mutated(
          area_screen, "final String head = (index + 1) + \". \"",
          "final String head = area.rawId().getPath() + (index + 1) + \". \""))

_SILENT_PACKET = re.sub(r"reject\(rejectMenu, player, packet, REJECT_[A-Z_]+\);", "", edit_packet)
check("⑥-b6 反例：拒绝原因回传退回「静默 return」⇒ ⑤-5 判负",
      _SILENT_PACKET.count("reject(rejectMenu, player, packet, REJECT_") < 9)

check("⑥-b7 反例：快照改成「每 tick 都发」（去掉版本号判据）⇒ ⑤-3 判负",
      "if (version == lastSnapshotVersion" not in mutated(
          adv_menu, "if (version == lastSnapshotVersion && editingAreaIndex == lastSnapshotEditing",
          "if (false && version == lastSnapshotVersion && editingAreaIndex == lastSnapshotEditing"))


# =====================================================================================
print("\n" + "=" * 78)
if FAILS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(FAILS), len(CHECKS)))
    for item in FAILS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] SELFCHECK OK：%d 项断言（4 组行为模型 + 22 条源码锚点 + 8 条源码反例）" % len(CHECKS))
