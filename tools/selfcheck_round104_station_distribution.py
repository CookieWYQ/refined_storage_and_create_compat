#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 104 轮自检：序列装配「物品分配架构」换成 B 半自动绑定方案

用户第 99 轮拍板「按你说的做」= 采纳 `docs/ROUND99_DISTRIBUTION_ARCHITECTURE.md` §5.3 的
**B 半自动绑定**（总线 → 工位的绑定今天就由「总线朝向哪一格」物理存在，只需显式化）。

本脚本做四件事：
  ① 源码锚点：B-1 工位推导默认集 / B-2 逐工位推料断言 / B-3 目标量合并键 / B-4 只读工位展示
     各自「唯一实现 + 在正确位置被调用」的 file:line 级证据；
  ② **行为模型**（与 Java 判据同口径的 Python 复刻）：
     * 单链双机器（注液 + 冲压）零配置 ⇒ 两条总线各自推导出的类别集**恰好**等于它那台机器该收的，
       且两个集合不相交（「注液总线不含冲压步的类别」这条硬结论）；
     * 机械手「手位」与「操作台」是两种摆放 ⇒ 默认集不同（手位只拿应用投入物、操作台只拿过渡件）；
     * 多配方同物品 ⇒ 目标量合并是 **sum**（两单备两份，不是一份），再按上限夹紧；
     * `stationAcceptsPush` 三态：接受 / 拒绝 / **判不出必须放行**；
     * 总开关关闭 ⇒ 逐字退回旧默认集 + 推料闸门恒放行；
  ③ 反例自证（源码级 7 条 + 模型级 3 条）：把每条修复逐一回退，对应断言必须翻红；
  ④ 硬底线守卫：推导集**不得**渗进属主判定（`ownedSteps` / `computeOwnedSteps` / `stepVerdictFor`），
     且 `chainOwnedSteps` 的代码调用点仍只有「定义 + stepVerdictFor」两处（第 33 轮守卫不得被破坏）。

运行：python tools/selfcheck_round104_station_distribution.py
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "src/main/java/cretae/cookiewyq/rs_create_compat"

FILES = {
    "chamber": PKG + "/block/entity/SequenceExecutionChamberBlockEntity.java",
    "export": PKG + "/support/RsccChamberExportStrategy.java",
    "probe": PKG + "/support/RsccStationProbe.java",
    "sync": PKG + "/support/RsccBusStationSync.java",
    "badge": PKG + "/client/widget/RsccStationBadge.java",
    "category": PKG + "/support/RsccBusCategory.java",
    "config": PKG + "/Config.java",
    # Create 6.0.10 官方源码（构建期解压到 build/createsrc，只读证据）
    "create_belt": "build/createsrc/com/simibubi/create/content/kinetics/belt/behaviour/BeltProcessingBehaviour.java",
    "create_depot": "build/createsrc/com/simibubi/create/content/logistics/depot/DepotBehaviour.java",
    "create_press": "build/createsrc/com/simibubi/create/content/kinetics/press/PressingRecipe.java",
    "create_deploy": "build/createsrc/com/simibubi/create/content/kinetics/deployer/DeployerApplicationRecipe.java",
}

CHECKS = []


def check(cid, desc):
    def decorate(fn):
        CHECKS.append((cid, desc, fn))
        return fn

    return decorate


def read_text(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
        return handle.read()


def has(text, pattern, flags=re.S):
    return re.search(pattern, text, flags) is not None


def missing(text, pattern):
    return re.search(pattern, text, re.S) is None


def strip_comments(text):
    """去掉 // 与 /* */ 注释并压成一行（结构判据用；注释里的示例不算「代码出现」）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r"\s+", " ", text)


def body_of(text, anchor_pattern):
    """取出 `anchor_pattern` 命中的方法体（按大括号配对；取不到 ⇒ None）。"""
    match = re.search(anchor_pattern, text)
    if match is None:
        return None
    brace = text.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    index = brace
    while index < len(text):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace:index + 1]
        index += 1
    return None


# ===========================================================================
# ① B-1：工位推导默认集（唯一实现 + 在归属表的 !explicit 分支生效 + 判不出逐字回退）
# ===========================================================================
@check("B1-1", "默认集推导有唯一公开实现 stationCategoryDefault(busPos)，且读总开关")
def check_b1_1(src, zh, en):
    chamber = src["chamber"]
    if "public List<String> stationCategoryDefault(final BlockPos busPos) {" not in chamber:
        return False, "找不到 stationCategoryDefault(BlockPos) 的公开入口"
    body = body_of(chamber, r"public List<String> stationCategoryDefault\(final BlockPos busPos\) \{")
    if body is None:
        return False, "取不到 stationCategoryDefault 方法体"
    if "supplyTargetOf(level, busPos)" not in body:
        return False, "公开入口没有解析「总线朝向那一格」（必须用既有 supplyTargetOf，不另立朝向判定）"
    impl = body_of(chamber, r"private List<String> stationCategoryDefault\(final BlockPos busPos,")
    if impl is None:
        return False, "找不到 B-1 的实现本体（私有重载）"
    if "Config.sequenceStationDerivedCategories" not in impl:
        return False, "实现本体没有读总开关 Config.sequenceStationDerivedCategories（一键回退路径失效）"
    if "chainDefaultExportCategoryIds()" not in impl:
        return False, "判不出时没有退回 chainDefaultExportCategoryIds()（旧行为不再是逐字回退）"
    if "RsccStationProbe.detect(" not in impl:
        return False, "没有用 RsccStationProbe.detect 解析「这一格是哪台机器」"
    if "chainBusCategories()" not in impl:
        return False, "推导结果没有与链级可见类别求交（会凭空造出本链不存在的 id）"
    return True, "唯一实现 + 总开关 + 回退 + 与链级类别求交都在同一处"


@check("B1-2", "归属表的 !explicit 分支改用它（链级归属表 = 唯一消费点）")
def check_b1_2(src, zh, en):
    owners = body_of(src["chamber"], r"private Map<String, List<BlockPos>> chainBusOwners\(\) \{")
    if owners is None:
        return False, "找不到 chainBusOwners"
    if "stationCategoryDefault(pos, exporter.rscc$supplyTargetPos())" not in owners:
        return False, "!explicit 分支没有调用 stationCategoryDefault(pos, 供料目标)"
    if "? stationCategoryDefault(pos, exporter.rscc$supplyTargetPos())" not in owners:
        return False, "没有按总开关在「推导集 / 旧默认集」之间二选一"
    if "requested = chainDefaultExportCategoryIds();" in owners:
        return False, "旧默认集的「整体替换」写法还在（B-1 没有真的接管默认集）"
    return True, "显式选过仍走玩家勾选；没选过走工位推导，fallback 逐字保留"


@check("B1-3", "机器类型判据来自 Create 的 addRequiredMachines（不写死方块→类型映射表）")
def check_b1_3(src, zh, en):
    chamber = src["chamber"]
    if "addRequiredMachines(machines)" not in chamber:
        return False, "没有用 IAssemblyRecipe#addRequiredMachines 取「这一步用哪台机器」"
    if "SequencedRecipeProbe.recipeTypeId(step.getRecipe())" not in chamber:
        return False, "StationStep 没有记录该步的 recipeTypeId（用户口径：机器类型 × 本链 ownedSteps）"
    # 几何口径：置物台 / 传送带 / 盆上的加工机在正上方 2 格（Create 自己的源码依据）
    probe = src["probe"]
    if "target.above(2)" not in probe:
        return False, "RsccStationProbe 没有按 Create 口径看正上方 2 格（置物台/传送带/盆的加工机）"
    if "create_belt" in src and "2 spaces above the belt" not in src["create_belt"]:
        return False, "Create 源码证据变了（BeltProcessingBehaviour 的「above(2)」说明不存在）"
    if "create_depot" in src and "pos.above(2)" not in src["create_depot"]:
        return False, "Create 源码证据变了（DepotBehaviour 没有读 above(2)）"
    return True, "机器类型走 addRequiredMachines；几何走 Create 自己的 above(2) 口径"


# ===========================================================================
# ② B-2：逐工位推料断言（唯一实现 + 在抽取计划里被调用 + 判不出放行）
# ===========================================================================
@check("B2-1", "推料闸门有唯一实现 stationAcceptsPush(target, stack)")
def check_b2_1(src, zh, en):
    chamber = src["chamber"]
    if "public boolean stationAcceptsPush(" not in chamber:
        return False, "找不到 stationAcceptsPush"
    body = body_of(chamber, r"public boolean stationAcceptsPush\(")
    if body is None:
        return False, "取不到 stationAcceptsPush 方法体"
    if not has(body, r"if \(machine == null\) \{\s*return true;"):
        return False, "三态口径不完整：判不出机器时必须 return true（不能因为判不出就断供）"
    if "if (matched.isEmpty()) {" not in body or "return true;" not in body:
        return False, "「这台机器与本链无关」也必须放行（宽松）"
    if "machine.hand()" not in body:
        return False, "没有区分「手位」与「操作台」两种摆放（过渡件不能进机械手的手）"
    if "Config.sequenceStationDerivedCategories" not in body:
        return False, "推料闸门没有跟总开关联动（false = 整体退回旧行为）"
    if "Math.floorMod(assembly.step(), total)" not in body:
        return False, "过渡件没有按它自己的进度组件判「下一步归哪一步」"
    return True, "接受 / 拒绝 / 判不出放行 三态都有，且与总开关联动"


@check("B2-2", "闸门在「抽取计划」这一层被调用（判的是即将推上去的那一件）")
def check_b2_2(src, zh, en):
    export = src["export"]
    if "chamber.stationAcceptsPush(targetPos, inSlot)" not in export:
        return False, "RsccChamberExportStrategy 没有调用 stationAcceptsPush（B-2 落空）"
    if "if (!chamber.stationAcceptsPush(targetPos, inSlot)) {" not in export:
        return False, "调用点被短路（必须真的用返回值决定推不推）"
    if "ASSERT_STATION_REJECTS" not in export:
        return False, "拒绝原因没有单独的诊断串（玩家 / 日志无法知道「为什么没推」）"
    if export.count("chamber.stationAcceptsPush(") != 1:
        return False, "调用点应只有一处（唯一闸门，不新增第二套逐工位判定）"
    return True, "唯一调用点 + 断言拒绝原因 + 限频日志通道"


# ===========================================================================
# ③ B-3：目标量合并键 (类别 id, 物品)
# ===========================================================================
@check("B3-1", "累加键 = (类别 id, 物品)；折叠时不同类别相加并按上限夹紧")
def check_b3_1(src, zh, en):
    chamber = src["chamber"]
    if "private record StationTargetKey(" not in chamber:
        return False, "没有 (类别 id, 物品) 的合并键记录"
    if "categoryItemTargets.merge(new StationTargetKey(info.id(), item)," not in chamber:
        return False, "累加没有用 (类别 id, 物品) 作键"
    collapse = body_of(chamber, r"private static Map<Item, Long> collapseCategoryItemTargets\(")
    if collapse is None:
        return False, "找不到 collapseCategoryItemTargets"
    if "left + right" not in collapse:
        return False, "折叠不是相加（B-3 的核心：两倍需求必须备两份）"
    if "BUS_ITEM_TARGET_MAX" not in collapse:
        return False, "折叠后没有按 BUS_ITEM_TARGET_MAX 夹紧"
    return True, "类别内 max + 跨类别 sum + 上限夹紧"


@check("B3-2", "备料 / 推料读折叠表（pullTargets），补合成请求读每物品保守表")
def check_b3_2(src, zh, en):
    chamber = src["chamber"]
    fill = body_of(chamber, r"private void fillInternalForBus\(")
    if fill is None:
        return False, "找不到 fillInternalForBus"
    if "final Map<Item, Long> itemTargets = new LinkedHashMap<>();" not in fill:
        return False, "没有目标量主表 itemTargets（既有名字，全流程共用一张表）"
    if "mergeConservativeItemTarget(itemTargets, item, itemTarget, cap);" not in fill:
        return False, "第一步「按物品取 max」的写入口没有接上"
    if "applyCategorySums(itemTargets, categoryItemTargets);" not in fill:
        return False, "没有把「被多个类别引用的物品」修正成跨类别相加值（B-3 未生效）"
    if "clampStartIngredientTargets(itemTargets);" not in fill:
        return False, "在制名额夹量没有作用在目标量主表上（既有行为不能变）"
    if "new LinkedHashMap<>(itemTargets);" not in fill:
        return False, "补合成请求没有在「名额夹量之前」留快照（既有行为不能变）"
    if fill.index("new LinkedHashMap<>(itemTargets);") > fill.index("clampStartIngredientTargets(itemTargets);"):
        return False, "补合成快照落在名额夹量之后（会把补合成请求一起夹掉）"
    return True, "pull 用 sum 表、autocraft 用 max 表，两张表都有明确用途"


# ===========================================================================
# ④ 硬底线：推导集不得渗进属主判定
# ===========================================================================
@check("GUARD-1", "属主判定（computeOwnedSteps / stepVerdictFor）不读工位推导集")
def check_guard_1(src, zh, en):
    chamber = src["chamber"]
    owned = strip_comments(body_of(
        chamber, r"private Map<String, Set<Integer>> computeOwnedSteps\(final Level level\) \{") or "")
    verdict = strip_comments(body_of(
        chamber, r"private StepVerdict stepVerdictFor\(") or "")
    for token in ("stationCategoryDefault", "stationAcceptsPush", "StationStep", "chainStationProfile"):
        if token in owned or token in verdict:
            return False, "属主判定里出现了 %s（第 20 轮「显示集当属主」的严重回归形态）" % token
    return True, "推导集只是只读层，没有进入属主 / 步序判定"


@check("GUARD-2", "chainOwnedSteps 的代码调用点仍只有 2 处（第 33 轮守卫未被破坏）")
def check_guard_2(src, zh, en):
    chamber = strip_comments(src["chamber"])
    count = chamber.count("chainOwnedSteps")
    if count != 2:
        return False, "chainOwnedSteps 代码出现 %d 次（期望 2：定义 + stepVerdictFor）" % count
    return True, "链级并集仍只有一份实现 + 一个判定调用点"


# ===========================================================================
# ⑤ B-4：只读工位展示（绘制件 + 数据源 + 不引用语言键 / 不改协议字段）
# ===========================================================================
@check("B4-1", "只读展示：服务端快照 + 绘制件都存在，且绘制件不引用语言键")
def check_b4_1(src, zh, en):
    if "public StationView sequenceStationView(" not in src["chamber"]:
        return False, "执行舱没有 B-4 的只读数据源 sequenceStationView(busPos)"
    badge = src["badge"]
    if "public static void render(" not in badge:
        return False, "RsccStationBadge 没有 render（绘制件缺失）"
    if "drawString(font, " not in badge:
        return False, "没有真正绘制文字"
    if 'gui.rs_create_compat.' in badge:
        return False, "绘制件引用了语言键（会把「文案」耦合进排版 / 可能造孤儿键；文案应由界面传 Component）"
    if "0xFFFFF055" not in badge:
        return False, "缺少「不一致」黄字颜色（用户要求：不一致时黄字提示、只展示不拦）"
    if "false);" not in badge:
        return False, "文字没有走 dropShadow=false（与既有界面口径不一致）"
    return True, "数据源 + 纯绘制件 + 无语言键耦合 + 黄字提示"


@check("B4-2", "工位快照「变化才发」且按 containerId 校验归属（不刷屏 / 不串界面）")
def check_b4_2(src, zh, en):
    sync = src["sync"]
    chamber = src["chamber"]
    if "CustomPacketPayload" not in sync or "STREAM_CODEC" not in sync:
        return False, "RsccBusStationSync 不是可下发的 S2C 载荷"
    if "public static RsccBusStationSync forContainer(final int containerId)" not in sync:
        return False, "客户端没有按 containerId 校验归属的读取口"
    push = body_of(chamber, r"private void pushStationViewsToViewers\(\) \{")
    if push is None:
        return False, "找不到 pushStationViewsToViewers"
    if "previous.intValue() == hash" not in push:
        return False, "推送没有「状态变化才发」（会每节拍刷屏）"
    if "bridge.rscc$getExporter() != blockEntity" not in push:
        return False, "推送没有按「打开的正是这条总线」过滤（会把别的总线状态发过来）"
    return True, "变化才发 + 身份过滤 + containerId 校验"


@check("B4-3", "B-4 没有改既有类别快照的编解码字段数（第 39/41 轮硬断言仍成立）")
def check_b4_3(src, zh, en):
    category = src["category"]
    count = category.count("ByteBufCodecs.STRING_UTF8.encode")
    if count != 7:
        return False, "类别快照字符串字段数变成 %d（第 39/41 轮把 7 写成硬断言：界面需求不得拖长协议）" % count
    if "public static boolean isInputId(final String id)" not in category:
        return False, "缺少静态 isInputId（B-4 的「不一致」提示要按 id 判输入类）"
    return True, "协议字段数不变；新增的只是静态判定辅助"


# ===========================================================================
# ⑥ 行为模型（与 Java 同口径的 Python 复刻）
# ===========================================================================
BUS_ITEM_TARGET_MAX = 64

# Create 官方数据里三条序列装配配方（build/createsrc/data/create/recipe/sequenced_assembly/*.json）
RECIPES = {
    "create:sturdy_sheet": {
        "main": ["minecraft:obsidian_dust"],
        "transitional": "create:unprocessed_obsidian_sheet",
        "steps": [
            {"type": "create:filling", "app": [], "fluids": ["minecraft:lava"]},
            {"type": "create:pressing", "app": [], "fluids": []},
            {"type": "create:pressing", "app": [], "fluids": []},
        ],
    },
    "create:track": {
        "main": ["minecraft:stone_slab"],
        "transitional": "create:incomplete_track",
        "steps": [
            {"type": "create:deploying", "app": ["minecraft:iron_nugget"], "fluids": []},
            {"type": "create:deploying", "app": ["minecraft:iron_nugget"], "fluids": []},
            {"type": "create:pressing", "app": [], "fluids": []},
        ],
    },
}
MACHINE_ITEMS = {
    "create:filling": "create:spout",
    "create:pressing": "create:mechanical_press",
    "create:deploying": "create:deployer",
}


def station_derived(chain_owned_steps, machine_type, hand, item_capable, fluid_capable):
    """B-1 的行为模型（与 Java stationCategoryIds 同口径）。

    :returns: (derived_ids, category_table_ids)
    """
    category_table = set()
    for recipe, steps in chain_owned_steps.items():
        spec = RECIPES[recipe]
        for step in steps:
            category_table.add("intermediate:%s:%d" % (recipe, step))
            for item in spec["main"]:
                category_table.add("input:%s#%s" % (recipe, item))
            for app in spec["steps"][step]["app"]:
                category_table.add("input:%s#%s" % (recipe, app))
            for fluid in spec["steps"][step]["fluids"]:
                category_table.add("fluid:%s" % fluid)
    derived = set()
    for recipe, steps in chain_owned_steps.items():
        spec = RECIPES[recipe]
        for step in steps:
            step_spec = spec["steps"][step]
            if step_spec["type"] != machine_type:
                continue
            groups = []
            if step == 0:
                groups.append(("main", spec["main"]))
            if step_spec["app"]:
                groups.append(("app", step_spec["app"]))
            if item_capable:
                if hand:
                    for kind, items in groups:
                        if kind == "main":
                            continue  # 第 0 步的主原料属于操作台，不进手
                        for item in items:
                            derived.add("input:%s#%s" % (recipe, item))
                elif machine_type == "create:deploying":
                    derived.add("intermediate:%s:%d" % (recipe, step))
                    if step == 0:
                        for kind, items in groups:
                            if kind == "main":
                                for item in items:
                                    derived.add("input:%s#%s" % (recipe, item))
                else:
                    derived.add("intermediate:%s:%d" % (recipe, step))
                    for kind, items in groups:
                        for item in items:
                            derived.add("input:%s#%s" % (recipe, item))
            if fluid_capable and not hand:
                for fluid in step_spec["fluids"]:
                    derived.add("fluid:%s" % fluid)
    return derived & category_table, category_table


CHAIN_DEFAULT = {
    "create:sturdy_sheet": {0, 1, 2},
}


def chain_default(chain_owned_steps):
    """旧默认集 = 全部输入类 + 全部中间产物（文档 SEQ:2198 / chainDefaultExportCategoryIds）。"""
    ids = set()
    for recipe, steps in chain_owned_steps.items():
        spec = RECIPES[recipe]
        for item in spec["main"]:
            ids.add("input:%s#%s" % (recipe, item))
        for step in steps:
            ids.add("intermediate:%s:%d" % (recipe, step))
            for app in spec["steps"][step]["app"]:
                ids.add("input:%s#%s" % (recipe, app))
    return ids


def station_accepts(chain_owned_steps, station, pushed, flag=True):
    """B-2 的行为模型（与 Java stationAcceptsPush 同口径）。

    :param station: dict(kind='none'|'machine', machine_type, hand, recipes=set)
    :param pushed: dict(recipe=None, step=None, item, transitional=False, start=False, app_of=None)
    """
    if not flag:
        return True
    if station["kind"] == "none":
        return True  # 判不出 ⇒ 宽松
    matched = [(r, s) for r, steps in chain_owned_steps.items()
               for s in steps if RECIPES[r]["steps"][s]["type"] == station["machine_type"]]
    if not matched:
        return True
    item = pushed["item"]
    if pushed.get("step") is not None:
        if station["hand"]:
            return False  # 过渡件永远不进机械手的手
        for recipe, step in matched:
            if recipe == pushed["recipe"] and step == pushed["step"]:
                return True
        return False
    if station["hand"]:
        for recipe, step in matched:
            if pushed["item"] in RECIPES[recipe]["steps"][step]["app"]:
                return True
        if pushed.get("transitional") or pushed.get("start"):
            return False
        return True
    if pushed.get("transitional"):
        for recipe, step in matched:
            if step >= 1 and RECIPES[recipe]["transitional"] == item:
                return True
        return False
    if pushed.get("start"):
        for recipe, step in matched:
            if step == 0 and item in RECIPES[recipe]["main"]:
                return True
        return False
    return True


def collapse_targets(by_category):
    """B-3 的行为模型：类别内 max（输入已经是每类别一条）+ 跨类别 sum + 上限夹紧。"""
    collapsed = {}
    for (_category, item), value in by_category.items():
        if value <= 0:
            continue
        collapsed[item] = min(BUS_ITEM_TARGET_MAX,
                              collapsed.get(item, 0) + min(value, BUS_ITEM_TARGET_MAX))
    return collapsed


@check("MODEL-1", "【行为模型】单链双机器零配置：两条总线的默认集恰好各收各的、互不相交")
def check_model_1(src, zh, en):
    # 注液（step 0，目标 = 置物台：有物品能力、没有流体能力）
    filling, table = station_derived(CHAIN_DEFAULT, "create:filling", hand=False,
                                     item_capable=True, fluid_capable=False)
    # 冲压（step 1/2，目标 = 置物台）
    pressing, _ = station_derived(CHAIN_DEFAULT, "create:pressing", hand=False,
                                  item_capable=True, fluid_capable=False)
    expected_filling = {"input:create:sturdy_sheet#minecraft:obsidian_dust",
                        "intermediate:create:sturdy_sheet:0"}
    expected_pressing = {"intermediate:create:sturdy_sheet:1",
                         "intermediate:create:sturdy_sheet:2"}
    if filling != expected_filling:
        return False, "注液总线推导集不是「恰好那台机器该收的」：%r" % (filling,)
    if pressing != expected_pressing:
        return False, "冲压总线推导集不是「恰好那台机器该收的」：%r" % (pressing,)
    if filling & pressing:
        return False, "两条总线的默认集相交：%r（会各自抢对方的料）" % (filling & pressing,)
    if "intermediate:create:sturdy_sheet:1" in filling:
        return False, "注液总线包含了冲压步的中间产物类别（零配置误分的原型）"
    if "input:create:sturdy_sheet#minecraft:obsidian_dust" in pressing:
        return False, "冲压总线包含了注液步的主原料类别"
    default = chain_default(CHAIN_DEFAULT)
    if not (filling < default and pressing < default):
        return False, "推导集必须是旧默认集的子集（默认集语义只含输入类 + 中间产物）"
    return True, "注液 {dust, 第0步件} / 冲压 {第1、2步件}，互不相交且都是旧默认集的子集"


@check("MODEL-2", "【行为模型】机械手「手位」与「操作台」两种摆放推导集不同（手位不拿过渡件/主原料）")
def check_model_2(src, zh, en):
    chain = {"create:track": {0, 1, 2}}
    hand, _ = station_derived(chain, "create:deploying", hand=True,
                              item_capable=True, fluid_capable=False)
    operand, _ = station_derived(chain, "create:deploying", hand=False,
                                 item_capable=True, fluid_capable=False)
    if "input:create:track#minecraft:iron_nugget" not in hand:
        return False, "手位默认集没有机械手该拿的应用投入物（铁粒）"
    if "input:create:track#minecraft:stone_slab" in hand:
        return False, "手位默认集含主原料（主原料属于操作台，不该塞进手里）"
    if "intermediate:create:track:0" in hand:
        return False, "手位默认集含过渡件类别（过渡件必须去操作台）"
    if "intermediate:create:track:1" not in operand:
        return False, "操作台默认集没有该步的过渡件"
    if "input:create:track#minecraft:iron_nugget" in operand:
        return False, "操作台默认集含应用投入物（机械手不会从台面取投入物）"
    return True, "手位 {铁粒} / 操作台 {过渡件…}，两种摆放各收各的"


@check("MODEL-3", "【行为模型】多配方同物品 ⇒ 目标量合并是 sum（两单备两份），再按上限夹紧")
def check_model_3(src, zh, en):
    by_category = {
        ("input:create:track#minecraft:iron_nugget", "minecraft:iron_nugget"): 1,
        ("input:create:precision_mechanism#minecraft:iron_nugget", "minecraft:iron_nugget"): 1,
    }
    merged = collapse_targets(by_category)
    if merged.get("minecraft:iron_nugget") != 2:
        return False, "两配方各下 1 单，池里应备 2 份，实际 %r" % (merged.get("minecraft:iron_nugget"),)
    same_category = {
        ("input:create:track#minecraft:iron_nugget", "minecraft:iron_nugget"): 1,
    }
    if collapse_targets(same_category).get("minecraft:iron_nugget") != 1:
        return False, "同一类别内重复引用不应相加"
    big = {}
    for index in range(100):
        big[("input:recipe%d#x" % index, "minecraft:iron_nugget")] = 1
    if collapse_targets(big).get("minecraft:iron_nugget") != BUS_ITEM_TARGET_MAX:
        return False, "折叠结果没有按 BUS_ITEM_TARGET_MAX 夹紧"
    return True, "两单 = 2 份；同类不相加；上限 64 生效"


@check("MODEL-4", "【行为模型】stationAcceptsPush 三态：接受 / 拒绝 / 判不出必须放行")
def check_model_4(src, zh, en):
    chain = {"create:sturdy_sheet": {0, 1, 2}, "create:track": {0, 1, 2}}
    press = {"kind": "machine", "machine_type": "create:pressing", "hand": False}
    deployer = {"kind": "machine", "machine_type": "create:deploying", "hand": True}
    unknown = {"kind": "none", "machine_type": "", "hand": False}
    accept = station_accepts(chain, press, {"recipe": "create:sturdy_sheet", "step": 1,
                                           "item": "create:unprocessed_obsidian_sheet"})
    reject_wrong_recipe = station_accepts(chain, press, {"recipe": "create:track", "step": 1,
                                                         "item": "create:incomplete_track"})
    reject_hand_transition = station_accepts(chain, deployer, {
        "item": "create:unprocessed_obsidian_sheet", "transitional": True})
    reject_hand_start = station_accepts(chain, deployer, {
        "item": "minecraft:obsidian_dust", "start": True})
    accept_hand_app = station_accepts(chain, deployer, {"item": "minecraft:iron_nugget"})
    accept_unknown = station_accepts(chain, unknown, {
        "item": "create:unprocessed_obsidian_sheet", "transitional": True})
    accept_off = station_accepts(chain, deployer, {
        "item": "create:unprocessed_obsidian_sheet", "transitional": True}, flag=False)
    if not accept:
        return False, "第 1 步过渡件推给冲压工位应被接受"
    if reject_wrong_recipe:
        return False, "列车轨道的第 1 步件推给只做坚固板冲压的工位应被拒绝"
    if reject_hand_transition:
        return False, "「冲压步过渡件」推给空的机械手必须被拒（用户第 104 轮的硬反例）"
    if reject_hand_start:
        return False, "主原料推给机械手的手必须被拒（主原料属于操作台）"
    if not accept_hand_app:
        return False, "机械手该拿的应用投入物应被接受"
    if not accept_unknown:
        return False, "判不出（容器 / 未知方块）必须放行 —— 判不出就拒会饿死产线"
    if not accept_off:
        return False, "总开关关闭时闸门必须恒放行（一键回退旧行为）"
    return True, "接受 3 / 拒绝 3 / 判不出放行 / 开关关闭放行"


@check("MODEL-5", "【行为模型】总开关关闭 ⇒ 逐字退回旧默认集（全输入 + 全中间产物）")
def check_model_5(src, zh, en):
    filling_off = chain_default(CHAIN_DEFAULT)
    filling, _ = station_derived(CHAIN_DEFAULT, "create:filling", hand=False,
                                 item_capable=True, fluid_capable=False)
    if filling == filling_off:
        return False, "模型里「关闭开关」与「开启开关」结果相同 ⇒ 断言无法区分（自检失效）"
    if not (len(filling_off) > len(filling)):
        return False, "旧默认集应当比推导集大（全输入 + 全中间产物）"
    return True, "旧默认集 %d 项 vs 推导集 %d 项" % (len(filling_off), len(filling))


# ===========================================================================
# 反例自证
# ===========================================================================
MUTATIONS = [
    ("B1-2", "chamber",
     [(r"requested = Config\.sequenceStationDerivedCategories\n\s*\? stationCategoryDefault\(pos, exporter\.rscc\$supplyTargetPos\(\)\)\n\s*: chainDefaultExportCategoryIds\(\);",
       "requested = chainDefaultExportCategoryIds();")],
     "删掉工位推导、改回「全输入 + 全中间产物」⇒ 零配置回到万能总线（B-1 未生效）"),
    ("B2-2", "export",
     [(r"if \(!chamber\.stationAcceptsPush\(targetPos, inSlot\)\) \{", "if (false) {")],
     "逐工位推料断言被短路 ⇒ 冲压步过渡件又能推进空的机械手"),
    ("B2-1", "chamber",
     [(r"if \(machine == null\) \{\n\s*return true; // 判不出是哪台机器 ⇒ 宽松",
       "if (machine == null) {\n            return false; // mutated: 判不出就拒")],
     "判不出就返回 false（过严）⇒ 陌生布局 / 第三方机器整条线饿死"),
    ("B3-1", "chamber",
     [(r"collapsed\.merge\(item, Math\.min\(target, BUS_ITEM_TARGET_MAX\),\n\s*\(left, right\) -> Math\.min\(BUS_ITEM_TARGET_MAX, left \+ right\)\);",
       "collapsed.merge(item, Math.min(target, BUS_ITEM_TARGET_MAX), Math::max); // mutated: 退回按物品取 max")],
     "目标量仍按物品取 max ⇒ 两配方同物品只备一份（B-3 未生效）"),
    ("GUARD-1", "chamber",
     [(r"(final Map<String, Set<Integer>> owned = new LinkedHashMap<>\(\);)",
       r"\1\n            stationCategoryDefault(worldPosition); // mutated: 推导集塞进属主判定路径")],
     "把推导集塞进属主判定（用 ownedSteps 接收推导结果）⇒ 第 20 轮严重回归复现"),
    ("B1-1", "chamber",
     [(r"if \(!Config\.sequenceStationDerivedCategories\) \{", "if (false) { // mutated: 忽略总开关")],
     "总开关关闭时仍用新默认 ⇒ 用户失去一键回退路"),
    ("GUARD-2", "chamber",
     [(r"private Map<String, Set<Integer>> ownedSteps\(\) \{",
       "private Map<String, Set<Integer>> ownedSteps() {\n        chainOwnedSteps(); // mutated: 多开一个调用点")],
     "链级并集多开一个调用点（渗进别处）⇒ 第 33 轮守卫被破坏"),
]

# 模型级反例：把 B-1/B-2/B-3 的行为模型写成「错误实现」，对应断言必须翻红
MODEL_MUTATIONS = [
    ("MODEL-4", "把推料闸门改成恒 true",
     "station_accepts 改成恒 true ⇒ 用户第 104 轮的硬反例（过度件进空机械手）抓不住",
     lambda f: (lambda *a, **k: True)),
    ("MODEL-3", "把目标量合并改回「按物品取 max」",
     "collapse_targets 改回 max ⇒ 「两单备两份」断言抓不住",
     lambda f: (lambda by_category: _collapse_max(by_category))),
    ("MODEL-1", "让所有总线共用旧默认集（= 回到「万能总线」，B-1 的推导不生效）",
     "station_derived 恒返回旧默认集 ⇒ 「两条总线各收各的 / 不相交」断言抓不住",
     lambda f: (lambda *a, **k: (chain_default(CHAIN_DEFAULT), set()))),
]


def _collapse_max(by_category):
    collapsed = {}
    for (_category, item), value in by_category.items():
        collapsed[item] = max(collapsed.get(item, 0), min(value, BUS_ITEM_TARGET_MAX))
    return collapsed


def apply_mutation(src, rel_key, replacements):
    text = src[rel_key]
    for pattern, replacement in replacements:
        new_text = re.sub(pattern, replacement, text, count=0, flags=re.S)
        if new_text == text:
            return None
        text = new_text
    return text


def run_all(src, zh, en):
    results = []
    for cid, desc, fn in CHECKS:
        try:
            ok, detail = fn(src, zh, en)
        except Exception as exc:
            ok, detail = False, "断言抛异常：%r" % (exc,)
        results.append((cid, desc, ok, detail))
    return results


def main():
    print("=" * 78)
    print("第 104 轮自检：序列装配分配架构 B 半自动绑定方案（B-1 / B-2 / B-3 / B-4）")
    print("=" * 78)

    src = {}
    broken = []
    for key, rel in FILES.items():
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            # Create 源码 / Config 只在部分环境存在：缺失时不 0-覆盖，但其它文件缺了必须失败
            if key.startswith("create_"):
                src[key] = ""
                continue
            broken.append(rel)
            src[key] = ""
            continue
        src[key] = read_text(rel)
    if broken:
        print("[FAIL] 0 覆盖：以下文件读不到 ⇒ 判据本身失效")
        for rel in broken:
            print("       - %s" % rel)
        return 1
    if "sequenceStationDerivedCategories" not in src["config"]:
        print("[FAIL] Config.java 里找不到总开关 sequenceStationDerivedCategories")
        return 1

    results = run_all(src, {}, {})
    failures = [(cid, desc, detail) for cid, desc, ok, detail in results if not ok]
    for cid, desc, ok, detail in results:
        print("%s %s %s" % ("PASS" if ok else "FAIL", cid, desc))
        if not ok:
            print("       -> %s" % detail)

    mutation_results = []
    for cid, rel_key, replacements, why in MUTATIONS:
        target = None
        for existing_id, existing_desc, fn in CHECKS:
            if existing_id == cid:
                target = fn
                break
        if target is None:
            mutation_results.append((cid, why, False, "MUTATIONS 指向不存在的断言 id"))
            continue
        mutated = apply_mutation(src, rel_key, replacements)
        if mutated is None:
            mutation_results.append((cid, why, False, "回退模式没匹配上（自检写错了）"))
            continue
        mutated_src = dict(src)
        mutated_src[rel_key] = mutated
        try:
            ok_after, _ = target(mutated_src, {}, {})
        except Exception:
            ok_after = False
        mutation_results.append((cid, why, not ok_after,
                                 "回退后仍判 PASS（判据抓不到这条修复）" if ok_after else ""))

    # 模型级反例：只改行为模型函数，重跑「引用了该模型」的断言
    model_checks = {cid: fn for cid, desc, fn in CHECKS}
    for cid, why, desc, mutator in MODEL_MUTATIONS:
        target = model_checks.get(cid)
        if target is None:
            mutation_results.append((cid, desc, False, "MODEL_MUTATIONS 指向不存在的断言 id"))
            continue
        original = globals().get(_model_name_of(cid))
        globals()[_model_name_of(cid)] = mutator(original)
        try:
            ok_after, _ = target(src, {}, {})
        except Exception:
            ok_after = False
        finally:
            globals()[_model_name_of(cid)] = original
        mutation_results.append((cid, desc, not ok_after,
                                 "模型改错后仍判 PASS（判据抓不到）" if ok_after else ""))

    if failures:
        print("-" * 78)
        print("[result] 自检失败：%d / %d 项断言未通过" % (len(failures), len(results)))
        return 1

    bad = [row for row in mutation_results if not row[2]]
    print("-" * 78)
    print("[mutations] 反例自证：%d / %d 条回退被对应断言抓到"
          % (len(mutation_results) - len(bad), len(mutation_results)))
    for cid, why, ok, why_fail in mutation_results:
        print("%s MUT %s %s" % ("PASS" if ok else "FAIL", cid, why))
        if not ok:
            print("       -> %s" % why_fail)
    if bad:
        print("[result] 自检失败：%d 条回退抓不到（判据有洞）" % len(bad))
        return 1
    print("[result] 全部通过：%d 项断言 + %d 条反例自证" % (len(results), len(mutation_results)))
    return 0


def _model_name_of(cid):
    """模型级反例要改的函数名（与断言 id 一一对应）。"""
    return {
        "MODEL-4": "station_accepts",
        "MODEL-3": "collapse_targets",
        "MODEL-1": "station_derived",
    }[cid]


if __name__ == "__main__":
    sys.exit(main())
