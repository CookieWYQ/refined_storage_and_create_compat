#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
selfcheck_advancements.py - 成就（advancements）自检。

为什么必须做：成就 JSON 里有三类「写完不报错、进游戏才炸 / 才显示键名」的坑：
  ① display.icon 指向不存在的物品 id → 资源加载期反序列化失败（整个成就页空白）；
  ② root 没有 background / 非 root 带了 background / parent 指向不存在的成就或成环 → 成就树断言失败；
  ③ 中英语言键缺一只 → 界面上直接显示 advancements.rs_create_compat.x.title 这种键名。
另外「依赖是否必要」这件事纯靠肉眼看不出来：必须逐条机检，否则很容易退回成
「同主题就硬挂父子」「一条子线串到底」的过死依赖（第一版就是这么坏的）。

检查项：
  1. 成就清单与验收一致（36 条，不多不少）；每个文件 json.load 通过，且**与生成器渲染结果逐字节一致**
     （生成器 ↔ 产物一致：JSON 不许手改）；
  2. criteria 非空；requirements 指向存在的 criterion；
  3. display.icon.id 真实存在于本模组注册表；root 的 background 贴图真实存在且 16x16；
  4. 树结构：只有 root 没 parent；挂在 root 下的只有「入口清单」（11 个）；无孤儿、无环；
     全部节点从 root 可达；最大深度 ≤4 条边（防「一路串死」）；
  5. 依赖必要性（核心断言，逐条独立验证，不看生成器的说法）：
     - entry   ：必须在本模组的入口清单里；
     - recipe  ：子项 recipe/*.json 的原料里**真的**含父成就物品谓词里的某件物品（父为机器或磁盘集合）；
     - machine ：子项是玩法事件，且事件所属机器 == 父（机器表 + Java 触发点方法名都在下面硬编码）；
     - output  ：子项物品**没有任何配方**（由父机器产出）；
     - compose / prereq / pipeline：白名单逐条列出，每条附理由（这类因果机检不了，只能声明式断言）；
     任何一条边都不在这 7 类里 → 失败（这就是「没必要的前置」的兜底闸门）；
  6. 用户点名的「过死依赖」不得复活（FORBIDDEN_EDGES）；
  7. 成对的基础 / 高级：高级的 parent 必须是基础（对齐原版「钻石在铁锭后面」的进度顺序）；
  8. 磁盘绝不按等级拆分：只有「任意等级」+「最高级」两条，且任意等级那条列全所有等级；
  9. 全部本模组注册物品都被某条成就的物品谓词覆盖（用户要求「基本上每个物品都能触发」）；
 10. 根标题 == 模组显示名（从 gradle.properties 的 mod_name 读，按 Java Properties 规则解码后再比）；
 11. 中英语言键成对存在；中文标题 ≤6 字、中文描述 ≤14 字（≤ 用户给的 40 字硬上限，
     取更严的旧标准；root 标题例外，它必须等于模组名）；文案黑名单（上一轮删掉的废话）不得回流；
 12. JSON 里用到的自定义 criterion 都在 RsccAdvancements.java 注册、每个注册的都被用到、
     每个语义触发点都在源码里有调用（防止「注册了却没人 fire」）。

用法：python tools/selfcheck_advancements.py   退出码 0 = 全部通过。
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


ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "rs_create_compat"
ADV_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "data", MODID, "advancement")
ASSET_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "assets", MODID)
LANG_DIR = os.path.join(ASSET_DIR, "lang")
RECIPE_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "data", MODID, "recipe")
JAVA_DIR = os.path.join(ROOT_DIR, "src", "main", "java")
MAIN_JAVA = os.path.join(JAVA_DIR, "cretae", "cookiewyq", "rs_create_compat", "RS_Create_Compat.java")
TRIGGER_JAVA = os.path.join(JAVA_DIR, "cretae", "cookiewyq", "rs_create_compat", "advancement",
                            "RsccAdvancements.java")
GRADLE_PROPS = os.path.join(ROOT_DIR, "gradle.properties")
# Java .properties 转义：\uXXXX（显示名里的中文就写成这样）+ \n \t \r \f \\ 等
_UNICODE_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
_SIMPLE_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "f": "\f"}

# ---- 验收清单：36 = 1 根 + 35 环 ----
MACHINES = [
    "range_charger", "quantity_keeper", "advanced_quantity_keeper", "schematic_loader",
    "advanced_schematic_loader", "sequence_pattern_terminal", "sequence_assembly_executor",
    "sequence_execution_chamber", "unit_pattern_manager", "collection_cache", "intermediate_cache",
    "separation_frame", "infinite_separation_frame", "camouflage_frame",
]
DISKS = [
    "universal_storage_disk_1k", "universal_storage_disk_4k", "universal_storage_disk_16k",
    "universal_storage_disk_64k", "universal_storage_disk_256k", "universal_storage_disk_1m",
    "universal_storage_disk_4m", "universal_storage_disk_16m", "universal_storage_disk_64m",
    "universal_storage_disk_creative",
]
# 玩法型成就 → 承载它的机器 + 触发它的自定义 criterion + 该事件在源码里的语义触发点方法名
HOOKS = {
    "keeper_autocraft": ("quantity_keeper", [("keeper_autocrafted", "onAutocraftRequested(")]),
    "advanced_keeper_autocraft": ("advanced_quantity_keeper",
                                  [("advanced_keeper_autocrafted", "onAdvancedAutocraftRequested(")]),
    "collection_first_absorb": ("collection_cache", [("collection_absorbed", "onCollectedFromWorld(")]),
    "collection_fluid": ("collection_cache", [("collection_fluid", "onCollectedFluid(")]),
    "collection_xp": ("collection_cache", [("collection_xp", "onCollectedExperience(")]),
    "cache_in_use": ("intermediate_cache", [("cache_in_use", "onSharedCacheDiskChanged(")]),
    "assembly_pattern_loaded": ("sequence_assembly_executor",
                                [("assembly_pattern_loaded", "onAssemblyPatternLoaded(")]),
    "sheath_first": ("separation_frame", [("sheath_applied", "onSheathApplied(")]),
    "sheath_infinite_first": ("infinite_separation_frame", [("sheath_applied_infinite", "onSheathApplied(")]),
    "sheath_chain": ("separation_frame", [("sheath_chain", "onSheathChained(")]),
    "seam_cut": (None, [("seam_cut", "onSeamToggled(")]),
    "seam_restored": (None, [("seam_restored", "onSeamToggled(")]),
    "schematic_auto_print": ("schematic_loader", [("blueprint_printed", "onBlueprintPrinted(")]),
    "advanced_auto_print": ("advanced_schematic_loader", [("blueprint_queue_printed", "onBlueprintPrinted(")]),
    "terminal_hotkey": ("advanced_remote_terminal", [("terminal_hotkey", "onTerminalOpenedByHotkey(")]),
    "terminal_three_modes": ("advanced_remote_terminal", [("terminal_mode_grid", "onTerminalMode("),
                                                          ("terminal_mode_patterns", "onTerminalMode("),
                                                          ("terminal_mode_manager", "onTerminalMode(")]),
}
# 线缆缝（RsccCableCuts）不属于任何一台本模组机器：只需 RS 线缆 + 扳手 → machine 一栏写 None
# 但 sheath_chain（连锁套壳）单格 / 批量是框架的两条入口，用无限框架批量也会 fire → 允许两种框架
MACHINE_OF_CHAIN = {"sheath_chain"}

# 允许挂在 root 下的入口（每条都必须在「本模组里没有前置」）
ENTRY_HEADS = [
    "range_charger", "quantity_keeper", "collection_cache", "intermediate_cache",
    "sequence_pattern_terminal", "separation_frame", "camouflage_frame", "seam_cut",
    "schematic_loader", "universal_storage_disk", "advanced_remote_terminal",
]
# 成对的基础 / 高级（高级 parent 必须是基础）
PAIRS = [
    ("quantity_keeper", "advanced_quantity_keeper"),
    ("schematic_loader", "advanced_schematic_loader"),
    ("separation_frame", "infinite_separation_frame"),
    ("universal_storage_disk", "universal_storage_disk_max"),
]
PLAYSTYLE = sorted(HOOKS)
EXPECTED = set(MACHINES) | set(ENTRY_HEADS) | set(PLAYSTYLE) | {
    "root", "sequence_unit_pattern", "sequence_assembly_pattern", "universal_storage_disk_max",
}

# ---- 依赖必要性：声明的 (子 → 父, 前置类型, 理由)；与生成器必须逐条一致 ----
ENTRY, RECIPE, MACHINE, OUTPUT, COMPOSE, PREREQ, PIPELINE = (
    "entry", "recipe", "machine", "output", "compose", "prereq", "pipeline")
EXPECTED_EDGES = {
    # 入口：本模组里没有前置（机器配方只用 RS / Create / 原版材料；线缆缝只需线缆 + 扳手）
    "range_charger": ("root", ENTRY, "配方只有 RS/Create/原版材料"),
    "quantity_keeper": ("root", ENTRY, "配方只有 RS/Create/原版材料"),
    "collection_cache": ("root", ENTRY, "配方只有 RS/Create/原版材料"),
    "intermediate_cache": ("root", ENTRY, "配方只有 RS/Create/原版材料（与归流缓存仓无因果）"),
    "sequence_pattern_terminal": ("root", ENTRY, "配方只有 RS/Create/原版材料"),
    "separation_frame": ("root", ENTRY, "配方只有原版铁锭"),
    "camouflage_frame": ("root", ENTRY, "配方只有 Create 伪装板与锌锭，与分隔框架无因果"),
    "seam_cut": ("root", ENTRY, "线缆缝只需 RS 线缆 + 扳手，不需要任何本模组机器"),
    "schematic_loader": ("root", ENTRY, "配方只有 RS/Create/原版材料"),
    "universal_storage_disk": ("root", ENTRY, "磁盘是物品，各等级可独立做出"),
    "advanced_remote_terminal": ("root", ENTRY, "配方只有 RS/Create/原版材料"),
    # 配方前置：机检 recipe/*.json
    "advanced_quantity_keeper": ("quantity_keeper", RECIPE, "高级保持器配方里用到定量保持器"),
    "unit_pattern_manager": ("sequence_execution_chamber", RECIPE, "管理舱配方里用到序列执行仓"),
    "infinite_separation_frame": ("separation_frame", RECIPE, "无限框架配方里用到分隔框架"),
    "advanced_schematic_loader": ("schematic_loader", RECIPE, "高级装填器配方里用到装填器"),
    "universal_storage_disk_max": ("universal_storage_disk", RECIPE, "64M 由 4 个 16M 合成，16M 属于「任意等级」"),
    # 机内事件：事件必须发生在父机器上
    "keeper_autocraft": ("quantity_keeper", MACHINE, "补货事件发生在定量保持器上"),
    "advanced_keeper_autocraft": ("advanced_quantity_keeper", MACHINE, "补货事件发生在高级保持器上"),
    "collection_first_absorb": ("collection_cache", MACHINE, "吸取事件发生在归流缓存仓上"),
    "collection_fluid": ("collection_cache", MACHINE, "抽流体事件发生在归流缓存仓上"),
    "collection_xp": ("collection_cache", MACHINE, "收经验事件发生在归流缓存仓上"),
    "cache_in_use": ("intermediate_cache", MACHINE, "换盘事件发生在中间产物缓存仓上"),
    "assembly_pattern_loaded": ("sequence_assembly_executor", MACHINE, "入库事件发生在样板库上"),
    "sheath_first": ("separation_frame", MACHINE, "套壳事件发生在框架上"),
    "sheath_chain": ("separation_frame", MACHINE, "批量套壳是框架的功能（单格与连锁两条独立入口）"),
    "sheath_infinite_first": ("infinite_separation_frame", MACHINE, "无限套壳事件只在无限框架上发"),
    "schematic_auto_print": ("schematic_loader", MACHINE, "自动打印事件发生在装填器上"),
    "advanced_auto_print": ("advanced_schematic_loader", MACHINE, "队列流水线事件只有高级装填器会发"),
    "terminal_hotkey": ("advanced_remote_terminal", MACHINE, "快捷键打开属于终端本体的功能"),
    "terminal_three_modes": ("advanced_remote_terminal", MACHINE, "三种模式都属于终端本体"),
    # 产出 / 编排 / 前提 / 流水线（机检不了，声明式白名单）
    "sequence_unit_pattern": ("sequence_pattern_terminal", OUTPUT, "单元样板无配方，由样板终端生成"),
    "sequence_assembly_pattern": ("sequence_unit_pattern", COMPOSE, "总样板由单元样板编排生成并消耗单元样板"),
    "seam_restored": ("seam_cut", PREREQ, "没断开过的缝无从接回"),
    "sequence_execution_chamber": ("sequence_pattern_terminal", PIPELINE, "装配流水线第二环：先有终端排样板"),
    "sequence_assembly_executor": ("sequence_execution_chamber", PIPELINE, "装配流水线第三环：执行仓跑线，样板库接自动合成"),
}
# 用户点名的「过死依赖」：这些边已拆，禁止复活（子 → 父：旧关系）
FORBIDDEN_EDGES = {
    # 曾经：并列的三种吸取硬串成 掉落物 → 流体 → 经验
    "collection_fluid": "collection_first_absorb",
    "collection_xp": "collection_fluid",
    "intermediate_cache": "collection_cache",        # 曾经：两台无关缓存仓硬挂父子
    "camouflage_frame": "infinite_separation_frame",  # 曾经：伪装框架挂在无限框架后面
    "sheath_chain": "sheath_first",                  # 曾经：单格套壳 → 整段套壳（两条独立入口）
    "unit_pattern_manager": "assembly_pattern_loaded",  # 曾经：管理舱被排到样板入库之后
    "terminal_three_modes": "terminal_hotkey",       # 曾经：三模式挂在快捷键后面
    "quantity_keeper": "range_charger",              # 曾经：两台无关机器串成链
    "sequence_assembly_executor": "sequence_pattern_terminal",
}
# 文案黑名单：上一轮删掉的废话 / 一眼可见的补语，不许回流
FLAVOR_BLACKLIST = ["远水可解渴", "多退少补", "四路精算", "军火库", "整整", "随处可见", "非常"]
ZH_TITLE_MAX, ZH_DESC_MAX, ZH_ABS_MAX = 6, 14, 40

problems = []


def problem(msg):
    problems.append(msg)
    print("[FAIL] %s" % msg)


def info(msg):
    print("[info] %s" % msg)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def unescape_properties(value):
    """按 Java .properties 规则解码：\\uXXXX（含代理对）与 \\n \\t \\r \\f \\\\ 等转义。"""
    text = _UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), value)
    text = re.sub(r"\\(.)", lambda m: _SIMPLE_ESCAPES.get(m.group(1), m.group(1)), text)
    return text.encode("utf-16", "surrogatepass").decode("utf-16")


def read_mod_name():
    """模组显示名：mods.toml 的 displayName = gradle.properties 的 mod_name（唯一真实来源）。"""
    for line in read(GRADLE_PROPS).splitlines():
        if line.startswith("mod_name="):
            return unescape_properties(line.split("=", 1)[1].strip())
    return None


def mod_registry_ids():
    """本模组注册名（ITEMS/BLOCKS.register("x")）→ {rs_create_compat:x}。"""
    return {"%s:%s" % (MODID, m)
            for m in re.findall(r'\b(?:ITEMS|BLOCKS)\.register\(\s*"([^"]+)"', read(MAIN_JAVA))}


def registered_triggers():
    return {"%s:%s" % (MODID, m)
            for m in re.findall(r'TRIGGERS\.register\(\s*"([^"]+)"', read(TRIGGER_JAVA))}


def items_of(payload):
    """一条成就里 item 谓词列出的所有 id（多条件成就可能一条都没有）。"""
    found = []
    for criterion in payload["criteria"].values():
        for predicate in ((criterion.get("conditions") or {}).get("items") or []):
            listed = predicate.get("items")
            found.extend(listed if isinstance(listed, list) else [listed])
    return found


def recipe_ingredients():
    """recipe/*.json → {产物 id: 原料 id 集合}（shaped 的 key / shapeless 的 ingredients 都算）。"""
    table = {}
    if not os.path.isdir(RECIPE_DIR):
        return table
    for name in sorted(os.listdir(RECIPE_DIR)):
        if not name.endswith(".json"):
            continue
        try:
            data = json.loads(read(os.path.join(RECIPE_DIR, name)))
        except ValueError:
            continue
        result = (data.get("result") or {}).get("id")
        if not result:
            continue
        ingredients = set()

        def collect(entry):
            if isinstance(entry, dict):
                if "item" in entry:
                    ingredients.add(entry["item"])
                for value in entry.values():
                    collect(value)
            elif isinstance(entry, list):
                for value in entry:
                    collect(value)

        collect(data.get("key"))
        collect(data.get("ingredients"))
        table.setdefault(result, set()).update(ingredients)
    return table


def adv_file_ids():
    if not os.path.isdir(ADV_DIR):
        return []
    return sorted(f[:-5] for f in os.listdir(ADV_DIR) if f.endswith(".json"))


def check_structure(data):
    """树结构：单一根 / root 子节点 == 入口清单 / 无环无孤儿 / 全部可达 / 深度受控。"""
    parents = {}
    for adv_id, payload in data.items():
        parent = payload.get("parent")
        parents[adv_id] = parent.split(":", 1)[1] if parent else None
    roots = sorted(a for a, p in parents.items() if not p)
    if roots != ["root"]:
        problem("应当只有一个根节点 root，实际：%s" % roots)
        return parents
    root_children = sorted(a for a, p in parents.items() if p == "root")
    if root_children != sorted(ENTRY_HEADS):
        problem("挂在 root 下的应只是入口清单：期望 %s，实际 %s"
                % (sorted(ENTRY_HEADS), root_children))
    # 无孤儿（parent 必须存在）+ 无环
    for adv_id, parent in parents.items():
        if not parent:
            continue
        if parent not in data:
            problem("%s 的 parent 指向不存在的成就：%s" % (adv_id, parent))
            continue
        seen, cursor = {adv_id}, parent
        while cursor:
            if cursor in seen:
                problem("parent 图存在环：%s" % adv_id)
                break
            seen.add(cursor)
            cursor = parents.get(cursor)
    # 全部可达（从 root 出发的广度优先）
    reachable, frontier = {"root"}, ["root"]
    while frontier:
        node = frontier.pop()
        for adv_id, parent in parents.items():
            if parent == node and adv_id not in reachable:
                reachable.add(adv_id)
                frontier.append(adv_id)
    unreachable = sorted(set(parents) - reachable)
    if unreachable:
        problem("以下成就从 root 不可达：%s" % unreachable)
    # 深度：从 root 到最深的节点最多 4 条边（防「一路串死」）
    worst, worst_node = 0, ""
    for adv_id in parents:
        depth, cursor = 0, parents.get(adv_id)
        while cursor:
            depth += 1
            cursor = parents.get(cursor)
        if depth > worst:
            worst, worst_node = depth, adv_id
    if worst > 4:
        problem("成就树太深：%s 距 root %d 条边（上限 4）" % (worst_node, worst))
    else:
        info("树结构：%d 个入口并列挂在 root，最深 %s（%d 条边），全部可达"
             % (len(root_children), worst_node, worst))
    return parents


def check_dependencies(parents, data, recipes):
    """依赖必要性：逐条独立验证（不看生成器的说法），并禁止过死依赖复活。"""
    if sorted(parents) != sorted(EXPECTED):
        problem("成就清单与验收不一致：多出 %s / 缺少 %s"
                % (sorted(set(parents) - EXPECTED), sorted(EXPECTED - set(parents))))
    counts = {kind: 0 for kind in (ENTRY, RECIPE, MACHINE, OUTPUT, COMPOSE, PREREQ, PIPELINE)}
    for adv_id in sorted(a for a in parents if a != "root"):
        actual_parent = parents[adv_id]
        declared = EXPECTED_EDGES.get(adv_id)
        if not declared:
            problem("%s 没有声明前置理由（EXPECTED_EDGES 里没有）" % adv_id)
            continue
        want_parent, kind, why = declared
        if actual_parent != want_parent:
            problem("%s 的 parent 应为 %s（%s：%s），实际 %s"
                    % (adv_id, want_parent, kind, why, actual_parent))
            continue
        counts[kind] += 1
        if kind == ENTRY:
            if want_parent != "root":
                problem("%s 是 entry 却挂在 %s" % (adv_id, want_parent))
            continue
        if kind == RECIPE:
            # 父成就物品谓词（如「任意等级磁盘」是一组物品）里必须有一件真的出现在子项配方里；
            # 子项可能是「成就 id ≠ 物品 id」的那种（如 universal_storage_disk_max → 64m 磁盘），
            # 因此原料取「子项物品谓词（没有谓词就用图标物品）」的配方之和。
            parent_items = set(items_of(data[want_parent]))
            child_items = items_of(data[adv_id]) or ["%s:%s" % (MODID, (data[adv_id].get("display") or {})
                                                               .get("icon", {}).get("id", "").split(":")[-1])]
            ingredients = set()
            for item in child_items:
                ingredients |= recipes.get(item, set())
            if not (parent_items & ingredients):
                problem("%s 声明为配方前置，但它的配方没用 %s 的物品谓词：原料 %s"
                        % (adv_id, want_parent, sorted(ingredients)))
        elif kind == MACHINE:
            machine = HOOKS.get(adv_id, (None, []))[0]
            allowed = {machine} if adv_id not in MACHINE_OF_CHAIN else {machine, "infinite_separation_frame"}
            if actual_parent not in allowed:
                problem("%s 的机内事件应发生在 %s 上，实际挂在 %s" % (adv_id, allowed, actual_parent))
        elif kind == OUTPUT:
            if "%s:%s" % (MODID, adv_id) in recipes:
                problem("%s 声明为由机器产出（无配方），但 recipe 里有它的合成配方" % adv_id)
        # COMPOSE / PREREQ / PIPELINE：声明式白名单（理由已写在 EXPECTED_EDGES 里，人工可核）
    # 过死依赖不得复活
    for adv_id, old_parent in sorted(FORBIDDEN_EDGES.items()):
        if parents.get(adv_id) == old_parent:
            problem("过死依赖复活：%s 又挂回了 %s" % (adv_id, old_parent))
    info("依赖：入口 %d、配方 %d、机内事件 %d、机器产出 %d、编排 %d、前提 %d、流水线 %d（共 %d 条边）"
         % (counts[ENTRY], counts[RECIPE], counts[MACHINE], counts[OUTPUT], counts[COMPOSE],
            counts[PREREQ], counts[PIPELINE], sum(counts.values())))
    # 生成器 ↔ 产物一致（同一渲染器重算一遍，逐字节比对）
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import gen_advancements as gen
    generated = gen.flatten()
    gen_ids = [spec["id"] for spec, _ in generated]
    if sorted(gen_ids) != sorted(parents):
        problem("生成器条目与 JSON 文件不一致：多出 %s / 缺少 %s"
                % (sorted(set(gen_ids) - set(parents)), sorted(set(parents) - set(gen_ids))))
    for spec, parent in generated:
        adv_id = spec["id"]
        path = os.path.join(ADV_DIR, adv_id + ".json")
        if not os.path.isfile(path):
            problem("生成器有 %s 但 JSON 文件不存在" % adv_id)
            continue
        if read(path) != gen.render_json(spec, parent):
            problem("%s.json 与生成器渲染结果不一致（JSON 被手改过？请重跑 tools/gen_advancements.py）"
                    % adv_id)
    for spec, _ in generated[1:]:
        if (spec["parent"], spec["kind"]) != (EXPECTED_EDGES.get(spec["id"], (None, None))[0],
                                              EXPECTED_EDGES.get(spec["id"], (None, None))[1]):
            problem("生成器里 %s 的 (parent,kind) 与自检声明的必要前置清单不一致" % spec["id"])
    info("生成器与产物一致：%d 个 JSON 与渲染结果逐字节相同" % len(generated))


def main():
    if not os.path.isdir(ADV_DIR):
        problem("成就目录不存在：%s" % os.path.relpath(ADV_DIR, ROOT_DIR))
        return 1

    files = sorted(f for f in os.listdir(ADV_DIR) if f.endswith(".json"))
    ids = {f[:-5] for f in files}
    mod_ids = mod_registry_ids()
    info("成就文件 %d 个（验收 %d 个）；本模组注册 id %d 个" % (len(files), len(EXPECTED), len(mod_ids)))
    if len(EXPECTED) < 34:
        problem("验收清单少于 34 条（用户要求 ≥34）：%d" % len(EXPECTED))
    if ids != EXPECTED:
        problem("成就清单与验收不一致：多出 %s / 缺少 %s"
                % (sorted(ids - EXPECTED), sorted(EXPECTED - ids)))

    data = {}
    covered_ids = set()
    disk_advs = {}
    for name in files:
        adv_id = name[:-5]
        try:
            payload = json.loads(read(os.path.join(ADV_DIR, name)))
        except ValueError as exc:
            problem("%s 不是合法 JSON：%s" % (name, exc))
            continue
        data[adv_id] = payload

        criteria = payload.get("criteria") or {}
        if not criteria:
            problem("%s 的 criteria 为空" % name)
        for group in payload.get("requirements") or []:
            for key in group:
                if key not in criteria:
                    problem("%s 的 requirements 引用了不存在的 criterion：%s" % (name, key))

        display = payload.get("display")
        if not isinstance(display, dict):
            problem("%s 缺少 display" % name)
            continue
        if display.get("frame", "task") not in ("task", "goal", "challenge"):
            problem("%s 的 frame 非法：%s" % (name, display.get("frame")))
        icon = (display.get("icon") or {}).get("id")
        if icon not in mod_ids:
            problem("%s 的 display.icon 物品不存在：%s" % (name, icon))
        for part in ("title", "description"):
            if not isinstance(display.get(part), dict) or "translate" not in display[part]:
                problem("%s 的 display.%s 不是语言键" % (name, part))

        for item in items_of(payload):
            if item not in mod_ids:
                problem("%s 的物品谓词引用了不存在的 id：%s" % (name, item))
            else:
                covered_ids.add(item)
        listed = items_of(payload)
        if listed and set(listed) <= {"%s:%s" % (MODID, d) for d in DISKS}:
            disk_advs[adv_id] = listed

    parents = check_structure(data)
    if parents:
        check_dependencies(parents, data, recipe_ingredients())

    # ---- background 贴图真实存在且 16x16 ----
    background = (data.get("root", {}).get("display", {}) or {}).get("background")
    if background:
        path = resolve_asset(background)
        if not os.path.isfile(path):
            problem("root 的 background 贴图不存在：%s" % background)
        else:
            with open(path, "rb") as handle:
                head = handle.read(24)
            size = (int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big"))
            if size != (16, 16):
                problem("background 必须是 16x16 可平铺贴图，实际 %dx%d" % size)
            else:
                info("root background：%s（16x16）" % background)
    else:
        problem("root 缺少 background")

    # ---- 成对基础 / 高级：高级 parent == 基础 ----
    for basic, advanced in PAIRS:
        parent = data.get(advanced, {}).get("parent")
        if parent != "%s:%s" % (MODID, basic):
            problem("%s（高级）的 parent 应为 %s（低级），实际 %s" % (advanced, basic, parent))
    info("成对基础/高级：%d 对，高级均直接挂在低级之后" % len(PAIRS))

    # ---- 覆盖：每个本模组注册物都能触发某条成就 ----
    uncovered = sorted(mod_ids - covered_ids)
    if uncovered:
        problem("以下本模组物品没有被任何成就的物品谓词覆盖：%s" % uncovered)

    # ---- 磁盘：绝不按等级拆分 ----
    if set(disk_advs) != {"universal_storage_disk", "universal_storage_disk_max"}:
        problem("磁盘类成就应恰为「任意等级」+「最高级」两条，实际：%s" % sorted(disk_advs))
    else:
        if len(disk_advs["universal_storage_disk"]) != len(DISKS):
            problem("「任意等级」磁盘成就应列全 %d 个等级，实际 %d"
                    % (len(DISKS), len(disk_advs["universal_storage_disk"])))
        if disk_advs["universal_storage_disk_max"] != ["%s:universal_storage_disk_64m" % MODID]:
            problem("「最高级」磁盘成就应只针对 64m：%s" % disk_advs["universal_storage_disk_max"])
        info("磁盘：任意等级 1 条 + 最高级 1 条（未按等级拆分）")

    # ---- 自定义 criterion：注册点 ↔ JSON 使用 ↔ 源码触发点 ----
    used = {criterion["trigger"] for payload in data.values()
            for criterion in payload["criteria"].values()
            if not criterion["trigger"].startswith("minecraft:")}
    declared = registered_triggers()
    if used != declared:
        problem("自定义 criterion 与注册点不一致：JSON 用了 %s，Java 注册了 %s"
                % (sorted(used), sorted(declared)))
    java_sources = read(TRIGGER_JAVA)  # 触发点只可能写在 RsccAdvancements 的语义方法里
    for adv_id, (machine, hooks) in HOOKS.items():
        for trigger, hook in hooks:
            if "%s:%s" % (MODID, trigger) not in used:
                problem("%s 没有引用自定义触发器 %s" % (adv_id, trigger))
            if "public static void " + hook not in java_sources:
                problem("触发器 %s 的语义触发点 %s 在 RsccAdvancements 里找不到" % (trigger, hook))
    info("自定义 criterion：注册 %d 个，JSON 使用 %d 个，触发点齐备" % (len(declared), len(used)))

    # ---- 语言键：成对 + 长度上限 + 废话黑名单 + 根标题 == 模组显示名 ----
    lang = {}
    for name in ("zh_cn.json", "en_us.json"):
        try:
            lang[name] = json.loads(read(os.path.join(LANG_DIR, name)))
        except ValueError as exc:
            problem("%s 不是合法 JSON：%s" % (name, exc))
            lang[name] = {}
    if set(lang["zh_cn.json"]) != set(lang["en_us.json"]):
        problem("中英语言键不成对：仅中文 %s；仅英文 %s"
                % (sorted(set(lang["zh_cn.json"]) - set(lang["en_us.json"]))[:10],
                   sorted(set(lang["en_us.json"]) - set(lang["zh_cn.json"]))[:10]))

    expected_lang = {"advancements.%s.%s.%s" % (MODID, a, p) for a in EXPECTED for p in ("title", "description")}
    for name, table in lang.items():
        missing = sorted(expected_lang - set(table))
        if missing:
            problem("%s 缺少成就语言键：%s" % (name, missing[:10]))
        extra = sorted(k for k in table if k.startswith("advancements.") and k not in expected_lang)
        if extra:
            problem("%s 有多余的成就语言键（清单外的成就？）：%s" % (name, extra[:10]))

    mod_name = read_mod_name()
    if not mod_name:
        problem("gradle.properties 里读不到 mod_name")
    else:
        for name in ("zh_cn.json", "en_us.json"):
            actual = lang[name].get("advancements.%s.root.title" % MODID)
            if actual != mod_name:
                problem("%s 的根标题应为模组显示名 %s，实际 %s" % (name, mod_name, actual))
        info("根标题 = %s（与 gradle.properties 的 mod_name 一致）" % mod_name)

    for adv_id in sorted(EXPECTED):
        zh_title = lang["zh_cn.json"].get("advancements.%s.%s.title" % (MODID, adv_id), "")
        zh_desc = lang["zh_cn.json"].get("advancements.%s.%s.description" % (MODID, adv_id), "")
        en_title = lang["en_us.json"].get("advancements.%s.%s.title" % (MODID, adv_id), "")
        en_desc = lang["en_us.json"].get("advancements.%s.%s.description" % (MODID, adv_id), "")
        if not (zh_title and zh_desc and en_title and en_desc):
            problem("%s 存在空的标题 / 描述" % adv_id)
            continue
        # root 的标题必须等于模组显示名（用户要求），因此它不受「≤6 字」的写作约束
        if adv_id != "root" and len(zh_title) > ZH_TITLE_MAX:
            problem("%s 的中文标题超过 %d 字：%s" % (adv_id, ZH_TITLE_MAX, zh_title))
        if len(zh_desc) > ZH_DESC_MAX:
            problem("%s 的中文描述超过 %d 字：%s" % (adv_id, ZH_DESC_MAX, zh_desc))
        if len(zh_desc) > ZH_ABS_MAX:
            problem("%s 的中文描述超过 %d 字（硬上限）：%s" % (adv_id, ZH_ABS_MAX, zh_desc))
        if adv_id != "root" and zh_desc == zh_title:
            problem("%s 的描述与标题重复" % adv_id)
        for bad in FLAVOR_BLACKLIST:
            if bad in zh_desc or bad in zh_title:
                problem("%s 的文案里出现废话黑名单词「%s」" % (adv_id, bad))
    info("语言键：%d 条 ×（中英标题 + 中英描述）齐备，中文标题 ≤%d、描述 ≤%d、无废话黑名单词"
         % (len(EXPECTED), ZH_TITLE_MAX, ZH_DESC_MAX))

    # ---- 机器 → 成就 一一对照（防漏） ----
    for machine in MACHINES:
        payload = data.get(machine)
        if not payload:
            problem("机器 %s 没有独立成就" % machine)
            continue
        if items_of(payload) != ["%s:%s" % (MODID, machine)]:
            problem("机器 %s 的成就物品谓词应只含它自己，实际：%s" % (machine, items_of(payload)))

    print("=" * 60)
    if problems:
        print("[result] 自检失败：%d 个问题" % len(problems))
        return 1
    print("[result] 自检通过：%d 条成就 / %d 个入口，parent 成树无环无孤儿且全部可达，"
          "每条前置都有类型与理由（过死依赖未复活），成对高级挂在低级后，磁盘未拆分，"
          "语言键成对且无废话，自定义触发器有注册点与触发点"
          % (len(EXPECTED), len(ENTRY_HEADS)))
    return 0


def resolve_asset(path):
    ns, rel = path.split(":", 1)
    return os.path.join(ROOT_DIR, "src", "main", "resources", "assets", ns, rel)


if __name__ == "__main__":
    sys.exit(main())
