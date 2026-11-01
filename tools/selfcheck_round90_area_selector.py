#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round90 自检：区域选定器（新物品）+ 高级归流缓存仓（新方块）。

只做**静态可证伪**的检查（读源码 / 资源 / 语言文件，不启动游戏），每条断言都对应需求里的
一句硬话；末尾带 4 个**反例自证**：把真实源码改成「交集语义 / 没有上限 / 每 tick 全扫 /
不校验维度」，本脚本必须判负 —— 否则说明断言是空转的。

检查分组
  ① 区域数据组件往返（record + CODEC + STREAM_CODEC + 注册版组件）
  ② 上限走 Config（两个 int；不写死 16 / 32768；硬上限只拦恶意数据）
  ③ **只用区域**（高级版不读 XYZ 半径、无交集语义、无区域就不吸）
  ④ 绝对坐标 + 绑定维度（跨维度不生效、写入前服务端校验）
  ⑤ 跨 tick 游标 + 每 tick ≤2048 次方块查询
  ⑥ 区域选定器槽（ItemStackHandler(1) + SlotItemHandler，**不是** UpgradeSlot）
  ⑦ 范围按钮 + 统一橙色（0xFF9F1C，避开蓝图蓝 0x6886c5）
  ⑧ 配方 / 资源 / 语言齐全（贴图基于基础版派生）
  ⑨ 反例自证 ×4

用法：python tools/selfcheck_round90_area_selector.py
"""
import io
import json
import os
import re
import sys
import zlib
import struct

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "rs_create_compat")
DATA = os.path.join(RES, "data", "rs_create_compat")
LANG = os.path.join(ASSETS, "lang")

problems = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print("PASS %s" % name)
    else:
        problems.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def rel(*parts):
    return os.path.join(SRC, *parts)


def load_sources():
    """把所有相关源码读进来（反例自证直接在这份 dict 上改）。"""
    files = {
        "RS_Create_Compat.java": rel("RS_Create_Compat.java"),
        "Config.java": rel("Config.java"),
        "Area.java": rel("support", "Area.java"),
        "AreaSelection.java": rel("support", "AreaSelection.java"),
        "AreaScanHook.java": rel("support", "AreaScanHook.java"),
        "CollectionCacheBlockEntity.java": rel("block", "entity", "CollectionCacheBlockEntity.java"),
        "AdvancedCollectionCacheBlockEntity.java":
            rel("block", "entity", "AdvancedCollectionCacheBlockEntity.java"),
        "AdvancedCollectionCacheBlock.java": rel("block", "AdvancedCollectionCacheBlock.java"),
        "AdvancedCollectionCacheMenu.java": rel("menu", "AdvancedCollectionCacheMenu.java"),
        "AdvancedCollectionCacheScreen.java":
            rel("client", "screen", "AdvancedCollectionCacheScreen.java"),
        # 第 99 轮：范围框的绘制与生命周期从高级界面<b>上移到基类</b>（两台机器共用同一条
        # S2C 权威链路），因此把基类也读进 sources —— ⑦ 的「用统一橙色出口画框」判据在两处
        # 任一命中即可（不许因为搬家就把这条判据删掉）。
        "CollectionCacheScreen.java":
            rel("client", "screen", "CollectionCacheScreen.java"),
        "AreaSelectorScreen.java": rel("client", "screen", "AreaSelectorScreen.java"),
        "AreaSelectorHandler.java": rel("client", "AreaSelectorHandler.java"),
        "AreaOutline.java": rel("client", "AreaOutline.java"),
        "AreaSelectorItem.java": rel("item", "AreaSelectorItem.java"),
        "AreaSelectorEditPacket.java": rel("network", "AreaSelectorEditPacket.java"),
        "ClientInit.java": rel("client", "ClientInit.java"),
        "RangeChargerScreen.java": rel("client", "screen", "RangeChargerScreen.java"),
    }
    sources = {}
    for name, path in files.items():
        if not os.path.isfile(path):
            sources[name] = ""
        else:
            sources[name] = read(path)
    return sources


# ==================================================================== 审计核心
# 反例自证复用同一个函数：真实源码必须 0 问题，5 个变异必须各自命中对应问题码。
def code_only(text):
    """去掉注释再断言：注释里允许出现「蓝图蓝 0x6886c5」「betweenClosed」这类对照说明。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def audit(raw_sources):
    result = []
    sources = {name: code_only(text) for name, text in raw_sources.items()}

    def has(name, *needles):
        text = sources.get(name, "")
        missing = [needle for needle in needles if needle not in text]
        return missing

    # ---------------- ① 数据组件往返 ----------------
    for needle in ('AREA_SELECTION =', '.persistent(AreaSelection.CODEC)',
                   '.networkSynchronized(AreaSelection.STREAM_CODEC)'):
        if needle not in sources.get("RS_Create_Compat.java", ""):
            result.append("component-registration")
            break
    area = sources.get("Area.java", "")
    for needle in ("RecordCodecBuilder.create", 'Codec.INT.fieldOf("min_x")',
                   'Codec.INT.fieldOf("max_z")', "public static final StreamCodec<ByteBuf, Area> STREAM_CODEC",
                   "minX > maxX", "contains(final int x"):
        if needle not in area:
            result.append("area-codec")
            break
    selection = sources.get("AreaSelection.java", "")
    for needle in ('optionalFieldOf("dimension")', "Area.CODEC.listOf()",
                   "ByteBufCodecs.list()", "public static AreaSelection get(final ItemStack stack)",
                   "public static void set(final ItemStack stack"):
        if needle not in selection:
            result.append("selection-codec")
            break

    # ---------------- ② 上限走 Config ----------------
    config = sources.get("Config.java", "")
    if not ('defineInRange("areaSelectorMaxAreas", 16, 1, 64)' in config
            and 'defineInRange("areaSelectorMaxVolume", 32768, 1, 1048576)' in config
            and "public static int areaSelectorMaxAreas;" in config
            and "public static int areaSelectorMaxVolume;" in config
            and "areaSelectorMaxAreas = AREA_SELECTOR_MAX_AREAS.get();" in config):
        result.append("config-limit")
    elif not ("Config.areaSelectorMaxAreas" in selection and "Config.areaSelectorMaxVolume" in selection):
        result.append("config-limit")
    # 校验侧必须走同一个入口（不能另写一个 16）
    if "AreaSelection.maxAreas()" not in sources.get("AreaSelectorEditPacket.java", ""):
        result.append("config-limit")
    if "AreaSelection.maxVolume()" not in sources.get("AreaSelectorEditPacket.java", ""):
        result.append("config-limit")

    # ---------------- ③ 只用区域（不读 XYZ、无交集、无区域不吸） ----------------
    base = sources.get("CollectionCacheBlockEntity.java", "")
    if "protected AreaScanHook areaScanHook()" not in base:
        result.append("area-hook")
    if "if (areaAbsorptionBlocked())" not in base:
        result.append("area-only-fallback")
    advanced = sources.get("AdvancedCollectionCacheBlockEntity.java", "")
    # 高级版不得调用半径 / 交集：一旦调用就说明「区域只是限位（交集）」而不是取代
    for forbidden in ("getScanRadiusX()", "getScanRadiusY()", "getScanRadiusZ()",
                      "getCollectRadius(", ".intersects("):
        if forbidden in advanced:
            result.append("intersection")
            break
    if "return selectionForTick() == null;" not in advanced:
        result.append("area-only-fallback")
    if "protected AreaScanHook areaScanHook()" not in advanced:
        result.append("area-hook")
    # 三条扫描路径都要真的走区域几何
    # 第 101 轮：实体查询改用 areas.entityQueryAreas()（第 121 轮 C-7 起按簇多盒，每盒向上扩 1 格）
    # + areas.containsEntity()。旧写法只比 blockPosition()，会把「落在区域底板上」的掉落物整批丢掉（P0 根因）。
    for needle in ("areas.entityQueryAreas()", "areas.scanFluidWindow(level, MAX_FLUID_BLOCKS_PER_SCAN",
                   "areas.containsEntity(entity)"):
        if needle not in base:
            result.append("area-hook")
            break

    # ---------------- ④ 绝对坐标 + 绑定维度 ----------------
    if not ("level.dimension().location()" in advanced
            and "return dimension == null || dimension.equals(current);" in selection):
        result.append("dimension")
    if "!selection.dimension().equals(dimension)" not in sources.get("AreaSelectorEditPacket.java", ""):
        result.append("dimension")
    if "worldPosition" in area or "worldPosition" in selection:
        result.append("absolute-coords")

    # ---------------- ⑤ 跨 tick 游标 + 预算 ----------------
    if "AREA_QUERY_BUDGET_PER_TICK = 2048" not in advanced:
        result.append("budget")
    if "Math.min(remaining, Math.max(0, budget))" not in advanced:
        result.append("budget")
    for needle in ("cursorIndex", "windowTick", "budgetTick", "queriesThisTick",
                   "if (windowTick == tick)", "if (next.equals(areas))"):
        if needle not in advanced:
            result.append("cursor")
            break
    if "betweenClosed" in advanced:
        result.append("full-scan")

    # ---------------- ⑥ 区域选定器槽 ----------------
    if not ("new ItemStackHandler(AREA_SLOT_COUNT)" in advanced
            and "stack.getItem() instanceof AreaSelectorItem" in advanced):
        result.append("area-slot")
    menu = sources.get("AdvancedCollectionCacheMenu.java", "")
    if not ("SlotItemHandler" in menu and "AREA_SLOT_INDEX = BASE_SLOT_COUNT" in menu
            and "UpgradeSlot.forContainer" not in menu):
        result.append("area-slot")
    if "collectHandler(cache.getAreaSlot()" not in sources.get("AdvancedCollectionCacheBlock.java", ""):
        result.append("area-slot")
    # 槽位下标必须排在基础版 138 个槽位之后（不与既有槽位重叠）
    if "BASE_SLOT_COUNT = MATCH_WINDOW + CACHE_WINDOW" not in menu:
        result.append("area-slot")

    # ---------------- ⑦ 范围按钮 + 统一橙色 ----------------
    outline = sources.get("AreaOutline.java", "")
    if not ("COLOR = 0xFF9F1C" in outline and "LINE_WIDTH" in outline
            and "showAABB" in outline and "chaseAABB" in outline):
        result.append("range-color")
    base_screen = sources.get("CollectionCacheScreen.java", "")
    # 第 125 轮 §5：高级版的两颗「单显 / 全显」按钮并入侧栏，范围按钮改回**基础版**（LANG + "show_range"）。
    # 高级版仍必须覆写 usesOwnRangeButton()=true 阻止基类再补一颗（否则会压住区域选定器槽），
    # 这条「不许补回」由 round99 ③2 与 round125 §5 钉住；本处只钉「基础版按钮 + 喂 Outliner 仍在」。
    adv_screen = sources.get("AdvancedCollectionCacheScreen.java", "")
    if not ('LANG + "show_range"' in base_screen and "AreaOutline.showForView" in base_screen):
        result.append("range-button")
    if 'protected boolean usesOwnRangeButton()' in adv_screen and 'return true;' not in adv_screen.split('protected boolean usesOwnRangeButton()')[1].split('}')[0]:
        result.append("range-button")
    charger = sources.get("RangeChargerScreen.java", "")
    if "RANGE_OUTLINE_COLOR = 0xFF9F1C" not in charger:
        result.append("range-color")
    # 新文件里不许再出现蓝图蓝
    for name in ("AreaOutline.java", "AreaSelectorHandler.java", "AreaSelectorScreen.java",
                 "AdvancedCollectionCacheScreen.java"):
        if "0x6886c5" in sources.get(name, ""):
            result.append("range-color")
            break

    return result


def audit_resources():
    result = []
    wanted = [
        "assets/rs_create_compat/blockstates/advanced_collection_cache.json",
        "assets/rs_create_compat/models/block/advanced_collection_cache.json",
        "assets/rs_create_compat/models/block/advanced_collection_cache_active.json",
        "assets/rs_create_compat/models/item/advanced_collection_cache.json",
        "assets/rs_create_compat/models/item/area_selector.json",
        "data/rs_create_compat/loot_table/blocks/advanced_collection_cache.json",
        "data/rs_create_compat/recipe/advanced_collection_cache.json",
        "data/rs_create_compat/recipe/area_selector.json",
        "assets/rs_create_compat/textures/item/area_selector.png",
    ]
    for rel_path in wanted:
        if not os.path.isfile(os.path.join(RES, *rel_path.split("/"))):
            result.append("missing:" + rel_path)
    for face in ("top", "side", "bottom"):
        for state in ("active", "inactive"):
            path = os.path.join(ASSETS, "textures", "block",
                                "advanced_collection_cache_%s_%s.png" % (face, state))
            if not os.path.isfile(path):
                result.append("missing:texture/%s_%s" % (face, state))
            else:
                with open(path, "rb") as handle:
                    head = handle.read(24)
                if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n":
                    result.append("png:" + face + "_" + state)
                else:
                    width, height = struct.unpack(">II", head[16:24])
                    if (width, height) != (16, 16):
                        result.append("png-size:%s_%s=%dx%d" % (face, state, width, height))
    # 配方：产物 id 正确、原料只用真实 id
    for name, expect in (("advanced_collection_cache", "rs_create_compat:advanced_collection_cache"),
                         ("area_selector", "rs_create_compat:area_selector")):
        path = os.path.join(DATA, "recipe", name + ".json")
        if os.path.isfile(path):
            payload = json.loads(read(path))
            if payload.get("result", {}).get("id") != expect:
                result.append("recipe:" + name)
            if payload.get("type") != "minecraft:crafting_shaped":
                result.append("recipe:" + name)
    return result


def audit_lang():
    result = []
    zh = json.loads(read(os.path.join(LANG, "zh_cn.json")))
    en = json.loads(read(os.path.join(LANG, "en_us.json")))
    if set(zh) != set(en):
        result.append("lang-pair")
    prefixes = (
        "block.rs_create_compat.advanced_collection_cache",
        "item.rs_create_compat.area_selector.",
        "message.rs_create_compat.area_selector.",
        "gui.rs_create_compat.area_selector.",
        "gui.rs_create_compat.advanced_collection_cache.",
    )
    for prefix in prefixes:
        if prefix.endswith("."):
            hits = [key for key in zh if key.startswith(prefix)]
        else:
            hits = [key for key in zh if key == prefix or key.startswith(prefix + ".")]
        if not hits:
            result.append("lang:" + prefix)
    # 空心值 / 中英同形（只针对新增键）
    for key in zh:
        if key.startswith(prefixes[1]) or key.startswith(prefixes[2]) or key.startswith(prefixes[3]) \
            or key.startswith(prefixes[4]):
            if not str(zh[key]).strip() or not str(en[key]).strip():
                result.append("lang-empty:" + key)
    return result


# ==================================================================== 主流程
sources = load_sources()

# ① 文件全都存在
for name in ("Area.java", "AreaSelection.java", "AreaScanHook.java", "AreaSelectorItem.java",
             "AdvancedCollectionCacheBlockEntity.java", "AdvancedCollectionCacheBlock.java",
             "AdvancedCollectionCacheMenu.java", "AdvancedCollectionCacheScreen.java",
             "AreaSelectorScreen.java", "AreaSelectorHandler.java", "AreaOutline.java",
             "AreaSelectorEditPacket.java"):
    check("① 新文件存在：%s" % name, sources[name] != "")

real_problems = audit(sources)
check("①②③④⑤⑥⑦ 源码审计 0 问题（%d 项断言合一）" % 1, not real_problems,
      ", ".join(sorted(set(real_problems))))

# 单独展开关键几条（便于失败时定位，而不是只有一个大断言）
base = sources["CollectionCacheBlockEntity.java"]
check("③ 基础版只在三个扫描路径上加区域分支、不引入交集判定",
      "areas.entityQueryAreas()" in base and "areas.containsEntity(entity)" in base
      and "if (areaAbsorptionBlocked())" in base)
advanced = sources["AdvancedCollectionCacheBlockEntity.java"]
check("③ 高级版 0 处半径 / 交集调用（只用区域）",
      not any(token in advanced for token in ("getScanRadiusX()", "getCollectRadius(", ".intersects(")))
check("③ 没有选定器 / 区域为空 / 维度不符 ⇒ 整段不吸收（不是退回半径）",
      "return selectionForTick() == null;" in advanced)
check("② 上限来自 Config（两个 int），校验侧走同一入口",
      "Config.areaSelectorMaxAreas" in sources["AreaSelection.java"]
      and "Config.areaSelectorMaxVolume" in sources["AreaSelection.java"]
      and "AreaSelection.maxAreas()" in sources["AreaSelectorEditPacket.java"])
check("④ 绝对坐标（Area/AreaSelection 里没有相对机器的偏移字段）",
      "worldPosition" not in sources["Area.java"] and "worldPosition" not in sources["AreaSelection.java"])
check("④ 跨维度：服务端拒写 + 机器忽略（两处都有）",
      "matchesDimension" in sources["AreaSelection.java"]
      and "!selection.dimension().equals(dimension)" in sources["AreaSelectorEditPacket.java"]
      and "level.dimension().location()" in advanced)
check("⑤ 每 tick 方块查询 ≤2048 且跨 tick 游标可复位",
      "AREA_QUERY_BUDGET_PER_TICK = 2048" in advanced
      and "if (next.equals(areas))" in advanced and "cursorIndex" in advanced)
check("⑤ 实体查询按簇多盒各查一次 + containsEntity 过滤（不逐区域查）",
      "areas.entityQueryAreas()" in base
      and "found.removeIf(entity -> !areas.containsEntity(entity))" in base)
check("⑥ 专用槽是普通 SlotItemHandler(1)，不是升级槽",
      "SlotItemHandler" in sources["AdvancedCollectionCacheMenu.java"]
      and "UpgradeSlot.forContainer" not in sources["AdvancedCollectionCacheMenu.java"]
      and "new ItemStackHandler(AREA_SLOT_COUNT)" in advanced)
check("⑥ 破坏时选定器按内容物策略结算（不会凭空消失）",
      "collectHandler(cache.getAreaSlot()" in sources["AdvancedCollectionCacheBlock.java"])
check("⑦ 查看范围按钮仍在**基础版**（`show_range`）且用统一橙色；高级版改由侧栏「显示全部」承担",
      'LANG + "show_range"' in sources["CollectionCacheScreen.java"]
      and "COLOR = 0xFF9F1C" in sources["AreaOutline.java"]
      and "RANGE_OUTLINE_COLOR = 0xFF9F1C" in sources["RangeChargerScreen.java"]
      and "AreaOutline.showForView" in sources["CollectionCacheScreen.java"])
check("⑦ 新代码没有用蓝图蓝 0x6886c5",
      all("0x6886c5" not in code_only(sources[name]) for name in
          ("AreaOutline.java", "AreaSelectorHandler.java", "AreaSelectorScreen.java",
           "AdvancedCollectionCacheScreen.java")))
check("⑧ 世界手势走 InputEvent（MouseButton.Pre / MouseScrolling），不是方块交互",
      "onMouseButton" in sources["AreaSelectorHandler.java"]
      and "Screen.hasControlDown()" in sources["AreaSelectorHandler.java"]
      and "InputEvent.MouseButton.Pre" in sources["ClientInit.java"]
      and "InputEvent.MouseScrollingEvent" in sources["ClientInit.java"])
check("⑧ 屏幕 / 处理器都已在 ClientInit 接线",
      "AdvancedCollectionCacheScreen::new" in sources["ClientInit.java"]
      and "AreaSelectorHandler::onClientTick" in sources["ClientInit.java"])

resource_problems = audit_resources()
check("⑧ 方块态 / 模型 / 掉落表 / 配方 / 贴图（6 面 + 图标）齐全且尺寸正确",
      not resource_problems, ", ".join(resource_problems))
lang_problems = audit_lang()
check("⑧ 中英语言键成对且新增命名空间齐全", not lang_problems, ", ".join(lang_problems))

# 容量 / 吞吐：数值写死在这里，改了就必须同步改这两条断言
check("② 高级版容量翻倍（512 格 / 1024000 mB），吞吐不覆盖",
      "CACHE_SLOTS_ADVANCED = 512" in advanced
      and "FLUID_CACHE_CAPACITY_ADVANCED = 1_024_000L" in advanced
      and "getTransferBatch" not in advanced)
check("② 基础版容量与集群扩容改成实例字段（高级版不再回到 256 格）",
      "protected final int cacheSlots;" in base and "new SimpleContainer(cacheSlots * count)" in base)


# ==================================================================== ⑨ 反例自证
def mutate(name, old, new):
    copy = dict(sources)
    text = copy[name]
    if old not in text:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (name, old))
    copy[name] = text.replace(old, new, 1)
    return copy


# 反例 1：把「取代」写成「交集」——高级版也读半径
case = mutate("AdvancedCollectionCacheBlockEntity.java", "return selectionForTick() == null;",
              "return selectionForTick() == null && getScanRadiusX() > 0;")
check("⑨a 反例（写成交集：区域 ∩ XYZ）判负", "intersection" in audit(case))

# 反例 2：没有可配置上限（校验侧改回写死 16）
# ⚠ 第 96 轮：`AreaSelectorEditPacket` 现在有**两处**上限入口（ADD 新增 / COPY 复制），
# 因此反例必须把「全部」调用点都换成写死的 16 才算真的「校验侧写死」；只替换第一处会留下另一处，
# 反例就抓不到了（这会让本组自证变成空转）。这里用 replace 全量替换，判据一个字都没放宽。
case = dict(sources)
case["AreaSelectorEditPacket.java"] = case["AreaSelectorEditPacket.java"].replace(
    "AreaSelection.maxAreas()", "16")
check("⑨b 反例（上限不读 Config / 写死）判负", "config-limit" in audit(case))

# 反例 3：每 tick 全扫（去掉预算夹取）
case = mutate("AdvancedCollectionCacheBlockEntity.java",
              "Math.min(remaining, Math.max(0, budget))", "Math.max(0, budget)")
check("⑨c 反例（每 tick 全扫、无 2048 预算）判负", "budget" in audit(case))

# 反例 4：不校验维度（记录里也不存维度匹配）
case = mutate("AreaSelection.java",
              "public boolean matchesDimension(@Nullable final ResourceLocation current) {\n"
              "        return dimension == null || dimension.equals(current);\n    }",
              "public boolean matchesDimension(@Nullable final ResourceLocation current) {\n"
              "        return true;\n    }")
check("⑨d 反例（不校验维度）判负", "dimension" in audit(case))

# 反例 5（额外）：把槽位改成升级槽
case = mutate("AdvancedCollectionCacheMenu.java", "addSlot(new SlotItemHandler(areaSlot, 0",
              "addSlot(UpgradeSlot.forContainer(areaSlot, 0")
check("⑨e 反例（区域槽改成升级槽）判负", "area-slot" in audit(case))

print("=" * 78)
if problems:
    print("[result] 自检失败：%d 个问题" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 5 个反例自证）；区域选定器 / 高级归流缓存仓符合用户拍板的"
      "「只用区域（取代 XYZ）+ 绝对坐标 + 绑定维度 + Config 可配上限 + 每 tick 2048 预算 + "
      "统一橙色范围框」" % checks)
sys.exit(0)
