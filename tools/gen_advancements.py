#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_advancements.py - 生成本模组的成就（advancements）+ 成就树背景贴图 + 中英语言键。

为什么用脚本而不是手写：
  ① 成就 JSON 有严格的结构要求（display/icon/criteria/requirements、根节点必须有 background、
     非根节点必须有存在的 parent），手写 36 个文件极易漏字段、写错物品 id 或接错 parent；
  ② 成就标题 / 描述 = 语言键，中英两份必须成对补齐，漏一个键游戏里就显示键名；
  ③ 每个文件都要能被 json.load 解析、行序稳定（datagen 风格 = 键名排序 + 2 空格缩进）；
  ④ 依赖关系必须**逐条可解释**：每条成就都显式写出 parent + 前置类型 + 理由（见下面的 ADVANCEMENTS），
     由 tools/selfcheck_advancements.py 对每种前置类型做机检或白名单断言 —— 不允许出现
     「只是同主题就硬挂成父子」的依赖（那是上一版「一条子线串到底」的病根）。

依赖模型（parent 只有下面 7 种来源，selfcheck 逐一独立验证）：
  - ENTRY   root → 入口：该条目在本模组里没有前置（机器配方不含本模组物品 / 事件不需要本模组机器）
            → 各入口并列挂在 root 上（root 只是模组根，不是「前置」）；
  - RECIPE  子项配方里**直接用到**父成就所指物品（父可以是机器，也可以是「任意等级」这种物品集合）；
  - MACHINE 子项是玩法事件，触发点就在父机器上（事件得先有那台机器才能发生）；
  - OUTPUT  子项物品由父机器产出，且它自己没有合成配方（序列单元样板：终端生成）；
  - COMPOSE 子项由父项编排生成并消耗父项（总样板由单元样板编排）；
  - PREREQ  子事件以父事件为前提（接回断缝必须先断开过）；
  - PIPELINE 同一条装配流水线的先后（终端排样板 → 执行仓执行 → 样板库接入自动合成）。

产出：
  - src/main/resources/data/rs_create_compat/advancement/*.json
  - src/main/resources/assets/rs_create_compat/textures/gui/advancements/backgrounds/advancements.png
  - 向 assets/rs_create_compat/lang/{zh_cn,en_us}.json 追加 advancements.* 键（保持既有行序）

用法：python tools/gen_advancements.py     （幂等：重跑只覆盖，不残留孤儿文件）
"""
import io
import json
import os
import re
import sys

from PIL import Image
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "rs_create_compat"
ADV_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "data", MODID, "advancement")
LANG_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "assets", MODID, "lang")
GRADLE_PROPS = os.path.join(ROOT_DIR, "gradle.properties")
BG_REL = "textures/gui/advancements/backgrounds/advancements.png"
BG_PATH = os.path.join(ROOT_DIR, "src", "main", "resources", "assets", MODID, BG_REL)

DISKS = [
    "universal_storage_disk_1k",
    "universal_storage_disk_4k",
    "universal_storage_disk_16k",
    "universal_storage_disk_64k",
    "universal_storage_disk_256k",
    "universal_storage_disk_1m",
    "universal_storage_disk_4m",
    "universal_storage_disk_16m",
    "universal_storage_disk_64m",
    "universal_storage_disk_creative",
]
TERMINALS = [
    "advanced_remote_terminal",
    "advanced_remote_terminal_charged",
    "creative_advanced_remote_terminal",
]
# 全部「可放置机器」（= RS_Create_Compat.java 的 BLOCKS.register 名）：根成就的物品谓词就用它
MACHINES = [
    "range_charger",
    "quantity_keeper",
    "advanced_quantity_keeper",
    "schematic_loader",
    "advanced_schematic_loader",
    "sequence_pattern_terminal",
    "sequence_assembly_executor",
    "sequence_execution_chamber",
    "unit_pattern_manager",
    "collection_cache",
    "intermediate_cache",
    "separation_frame",
    "infinite_separation_frame",
    "camouflage_frame",
]

# ---- 前置类型（与 selfcheck_advancements.py 的断言一一对应） ----
ENTRY = "entry"        # root → 主题入口：配方不含本模组物品
RECIPE = "recipe"      # 子项配方直接用父成就的物品
MACHINE = "machine"    # 玩法事件发生在父机器上
OUTPUT = "output"      # 子项物品由父机器产出（自身无配方）
COMPOSE = "compose"    # 子项由父项编排生成并消耗父项
PREREQ = "prereq"      # 子事件以父事件为前提
PIPELINE = "pipeline"  # 同一条装配流水线的先后

# Java .properties 转义：\uXXXX（显示名里的中文就写成这样）+ \n \t \r \f \\ 等
_UNICODE_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
_SIMPLE_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "f": "\f"}


def unescape_properties(value):
    """按 Java .properties 规则解码：\\uXXXX（含代理对）与 \\n \\t \\r \\f \\\\ 等转义。"""
    text = _UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), value)
    text = re.sub(r"\\(.)", lambda m: _SIMPLE_ESCAPES.get(m.group(1), m.group(1)), text)
    return text.encode("utf-16", "surrogatepass").decode("utf-16")


def read_mod_name():
    """根成就标题 = 模组显示名：从 gradle.properties 的 mod_name 读（mods.toml 的 displayName 就是它）。

    gradle.properties 由 Java Properties 以 ISO-8859-1 读取，中文写成 \\uXXXX 转义，
    因此这里必须按同样的规则解码，否则会把字面量 "\\u673a..." 写进语言文件。
    """
    with io.open(GRADLE_PROPS, "r", encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("mod_name="):
                return unescape_properties(line.split("=", 1)[1].strip())
    raise RuntimeError("gradle.properties 里找不到 mod_name")


def adv(id_, parent, kind, why, icon, frame, zh_t, zh_d, en_t, en_d,
        items=None, trigger=None, criteria=None, theme=""):
    """一条成就：显式声明 parent + 前置类型 + 理由（parent=None 只允许根节点）。"""
    return {
        "id": id_, "parent": parent, "kind": kind, "why": why, "theme": theme,
        "icon": icon, "frame": frame,
        "zh_title": zh_t, "zh_desc": zh_d, "en_title": en_t, "en_desc": en_d,
        "items": items, "trigger": trigger, "criteria": criteria,
    }


# ==================== 成就清单（parent / 前置类型 / 理由 逐条写出） ====================
ADVANCEMENTS = [
    adv("root", None, "", "模组根：拿到任意一台本模组机器",
        "range_charger", "task", "", "获得任意一台本模组机器", "", "Obtain any machine from this mod",
        items=MACHINES, theme="根"),

    # ---------------- 能量与定量 ----------------
    adv("range_charger", "root", ENTRY, "配方只有 RS/Create/原版材料，本模组里没有它的前置",
        "range_charger", "task", "隔空充电", "造出范围充电器",
        "Wireless Charging", "Craft a Range Charger",
        items=["range_charger"], theme="能量与定量"),
    adv("quantity_keeper", "root", ENTRY, "配方只有 RS/Create/原版材料，本模组里没有它的前置",
        "quantity_keeper", "task", "不多不少", "造出定量保持器",
        "Just Enough", "Craft a Quantity Keeper",
        items=["quantity_keeper"], theme="能量与定量"),
    adv("keeper_autocraft", "quantity_keeper", MACHINE, "补货事件发生在定量保持器上，得先有这台机器",
        "quantity_keeper", "goal", "缺啥补啥", "定量保持器自动补货一次",
        "Top It Up", "Let the Quantity Keeper autocraft a refill",
        trigger=MODID + ":keeper_autocrafted", theme="能量与定量"),
    adv("advanced_quantity_keeper", "quantity_keeper", RECIPE, "高级保持器配方里直接用到定量保持器",
        "advanced_quantity_keeper", "goal", "四路不多", "造出高级定量保持器",
        "Four Channels", "Craft an Advanced Quantity Keeper",
        items=["advanced_quantity_keeper"], theme="能量与定量"),
    adv("advanced_keeper_autocraft", "advanced_quantity_keeper", MACHINE, "补货事件发生在高级保持器上",
        "advanced_quantity_keeper", "goal", "四管齐下", "高级保持器自动补货一次",
        "All Four At Once", "Let the Advanced Keeper autocraft a refill",
        trigger=MODID + ":advanced_keeper_autocrafted", theme="能量与定量"),

    # ---------------- 归流与缓存 ----------------
    adv("collection_cache", "root", ENTRY, "配方只有 RS/Create/原版材料，本模组里没有它的前置",
        "collection_cache", "task", "我全都要", "造出归流缓存仓",
        "Give Me Everything", "Craft a Collection Cache",
        items=["collection_cache"], theme="归流与缓存"),
    adv("collection_first_absorb", "collection_cache", MACHINE, "吸取事件发生在归流缓存仓上",
        "collection_cache", "goal", "来者不拒", "让归流缓存仓吸进掉落物",
        "No Refusals", "Let the Collection Cache absorb a drop",
        trigger=MODID + ":collection_absorbed", theme="归流与缓存"),
    adv("collection_fluid", "collection_cache", MACHINE, "抽流体事件发生在归流缓存仓上",
        "collection_cache", "goal", "抽刀断水", "让归流缓存仓抽走源流体",
        "Cut Off The Water", "Let the Collection Cache drain a fluid source",
        trigger=MODID + ":collection_fluid", theme="归流与缓存"),
    adv("collection_xp", "collection_cache", MACHINE, "收经验事件发生在归流缓存仓上",
        "collection_cache", "goal", "吸星大法", "让归流缓存仓吸进经验球",
        "Star Suction", "Let the Collection Cache absorb an experience orb",
        trigger=MODID + ":collection_xp", theme="归流与缓存"),
    adv("intermediate_cache", "root", ENTRY, "配方只有 RS/Create/原版材料，与归流缓存仓无因果关系",
        "intermediate_cache", "task", "中场休息", "造出中间产物缓存仓",
        "Halftime", "Craft an Intermediate Cache",
        items=["intermediate_cache"], theme="归流与缓存"),
    adv("cache_in_use", "intermediate_cache", MACHINE, "换盘事件发生在中间产物缓存仓上",
        "intermediate_cache", "goal", "盘活库存", "给中间产物缓存仓换一次盘",
        "Disk In, Cache On", "Swap a disk in the Intermediate Cache",
        trigger=MODID + ":cache_in_use", theme="归流与缓存"),

    # ---------------- 序列装配 ----------------
    adv("sequence_pattern_terminal", "root", ENTRY, "配方只有 RS/Create/原版材料，本模组里没有它的前置",
        "sequence_pattern_terminal", "task", "序序如生", "造出序列装配样板终端",
        "Order's Up", "Craft a Sequence Pattern Terminal",
        items=["sequence_pattern_terminal"], theme="序列装配"),
    adv("sequence_unit_pattern", "sequence_pattern_terminal", OUTPUT, "单元样板没有合成配方，由样板终端生成",
        "sequence_unit_pattern", "task", "一元复始", "获得一张序列装配单元样板",
        "Square One", "Obtain a Sequence Unit Pattern",
        items=["sequence_unit_pattern"], theme="序列装配"),
    adv("sequence_assembly_pattern", "sequence_unit_pattern", COMPOSE, "总样板由单元样板编排生成并消耗单元样板",
        "sequence_assembly_pattern", "goal", "总揽全局", "获得一张序列装配总样板",
        "The Full Picture", "Obtain a Sequence Assembly Pattern",
        items=["sequence_assembly_pattern"], theme="序列装配"),
    adv("sequence_execution_chamber", "sequence_pattern_terminal", PIPELINE, "装配流水线第二环：终端先排出样板，执行仓才有的可执行",
        "sequence_execution_chamber", "task", "一仓之隔", "造出序列执行仓",
        "One Chamber Away", "Craft a Sequence Execution Chamber",
        items=["sequence_execution_chamber"], theme="序列装配"),
    adv("unit_pattern_manager", "sequence_execution_chamber", RECIPE, "管理舱配方里直接用到序列执行仓",
        "unit_pattern_manager", "goal", "舱舱分明", "造出单元样板管理舱",
        "Every Chamber In Order", "Craft a Unit Pattern Manager",
        items=["unit_pattern_manager"], theme="序列装配"),
    adv("sequence_assembly_executor", "sequence_execution_chamber", PIPELINE, "装配流水线第三环：执行仓跑线，样板库把总样板接进自动合成",
        "sequence_assembly_executor", "task", "样样精通", "造出序列装配样板库",
        "Pattern Perfect", "Craft a Sequence Assembly Executor",
        items=["sequence_assembly_executor"], theme="序列装配"),
    adv("assembly_pattern_loaded", "sequence_assembly_executor", MACHINE, "入库事件发生在样板库上，得先有样板库",
        "sequence_assembly_executor", "goal", "有样学样", "把一张总样板放进样板库",
        "Copy That", "Put an assembly pattern into the library",
        trigger=MODID + ":assembly_pattern_loaded", theme="序列装配"),

    # ---------------- 线缆与框架 ----------------
    adv("separation_frame", "root", ENTRY, "配方只有原版铁锭，本模组里没有它的前置",
        "separation_frame", "task", "分道扬镳", "造出分隔框架",
        "Parting Ways", "Craft a Separation Frame",
        items=["separation_frame"], theme="线缆与框架"),
    adv("sheath_first", "separation_frame", MACHINE, "套壳事件发生在框架上，得先有框架",
        "separation_frame", "task", "穿件外套", "给线缆或管道套上外壳",
        "Coat On", "Sheathe a cable or pipe",
        trigger=MODID + ":sheath_applied", theme="线缆与框架"),
    adv("sheath_chain", "separation_frame", MACHINE, "批量套壳是框架的功能（单格右键与连锁是两条独立入口）",
        "separation_frame", "goal", "一气呵成", "一次给一整段套上外壳",
        "One Run In One Go", "Sheathe a whole run at once",
        trigger=MODID + ":sheath_chain", theme="线缆与框架"),
    adv("seam_cut", "root", ENTRY, "线缆缝是 RS 线缆 + 扳手的功能（RsccCableCuts），不需要任何框架或机器",
        "separation_frame", "task", "一刀两断", "用扳手断开一道线缆缝",
        "Clean Break", "Cut a cable seam with a wrench",
        trigger=MODID + ":seam_cut", theme="线缆与框架"),
    adv("seam_restored", "seam_cut", PREREQ, "没断开过的缝无从「接回」，接回必然是断开之后",
        "separation_frame", "goal", "破镜重圆", "把断开的线缆缝接回去",
        "Mended Seam", "Restore the seam you cut",
        trigger=MODID + ":seam_restored", theme="线缆与框架"),
    adv("infinite_separation_frame", "separation_frame", RECIPE, "无限框架（无序合成）配方里直接用到分隔框架",
        "infinite_separation_frame", "goal", "永不分离", "造出无限分隔框架",
        "Never Parting", "Craft an Infinite Separation Frame",
        items=["infinite_separation_frame"], theme="线缆与框架"),
    adv("sheath_infinite_first", "infinite_separation_frame", MACHINE, "无限套壳事件只有在无限分隔框架上才会发",
        "infinite_separation_frame", "goal", "衣不解带", "用无限分隔框架套一次壳",
        "Never Undressed", "Sheathe with an Infinite Separation Frame",
        trigger=MODID + ":sheath_applied_infinite", theme="线缆与框架"),
    adv("camouflage_frame", "root", ENTRY, "配方只有 Create 伪装板与锌锭，与分隔框架无因果关系",
        "camouflage_frame", "goal", "以假乱真", "造出伪装框架",
        "Perfect Disguise", "Craft a Camouflage Frame",
        items=["camouflage_frame"], theme="线缆与框架"),

    # ---------------- 蓝图与自动打印 ----------------
    adv("schematic_loader", "root", ENTRY, "配方只有 RS/Create/原版材料，本模组里没有它的前置",
        "schematic_loader", "task", "图穷料现", "造出蓝图加农炮装填器",
        "Blueprint Feeder", "Craft a Schematic Cannon Loader",
        items=["schematic_loader"], theme="蓝图与自动打印"),
    adv("schematic_auto_print", "schematic_loader", MACHINE, "自动打印事件发生在装填器上",
        "schematic_loader", "goal", "自动挡", "让装填器自动打印一张蓝图",
        "Cruise Control", "Let the Loader auto-print a blueprint",
        trigger=MODID + ":blueprint_printed", theme="蓝图与自动打印"),
    adv("advanced_schematic_loader", "schematic_loader", RECIPE, "高级装填器配方里直接用到装填器",
        "advanced_schematic_loader", "goal", "图穷料不尽", "造出高级装填器",
        "Blueprint Stockpile", "Craft an Advanced Schematic Loader",
        items=["advanced_schematic_loader"], theme="蓝图与自动打印"),
    adv("advanced_auto_print", "advanced_schematic_loader", MACHINE, "队列流水线事件只有高级装填器会发",
        "advanced_schematic_loader", "goal", "按图索骥", "让高级装填器自动打完一张图",
        "By The Book", "Let the Advanced Loader auto-print a blueprint",
        trigger=MODID + ":blueprint_queue_printed", theme="蓝图与自动打印"),

    # ---------------- 存储磁盘 ----------------
    adv("universal_storage_disk", "root", ENTRY, "磁盘是物品不是机器，各等级可独立做出",
        "universal_storage_disk_1k", "goal", "盘它一盘", "获得任意等级的通用储存磁盘",
        "Any Tier Will Do", "Obtain any tier of Universal Storage Disk",
        items=DISKS, theme="存储磁盘"),
    adv("universal_storage_disk_max", "universal_storage_disk", RECIPE, "64M 由 4 个 16M 合成，而 16M 已在「任意等级」的谓词里",
        "universal_storage_disk_64m", "challenge", "盘满钵满", "做出最高级的通用储存磁盘",
        "Disks To The Brim", "Craft the highest tier Universal Storage Disk",
        items=["universal_storage_disk_64m"], theme="存储磁盘"),

    # ---------------- 终端与工具 ----------------
    adv("advanced_remote_terminal", "root", ENTRY, "配方只有 RS/Create/原版材料，本模组里没有它的前置",
        "advanced_remote_terminal", "goal", "掌上机房", "把机房装进口袋",
        "Datacenter In Hand", "Carry a datacenter in your pocket",
        items=TERMINALS, theme="终端与工具"),
    adv("terminal_hotkey", "advanced_remote_terminal", MACHINE, "快捷键打开事件属于终端本体的功能",
        "advanced_remote_terminal", "task", "键步如飞", "用快捷键打开远程终端",
        "Key To Success", "Open the terminal with its keybind",
        trigger=MODID + ":terminal_hotkey", theme="终端与工具"),
    # 多条件成就：三种模式各一个 criterion，requirements 全都要（原版「集齐」写法）
    adv("terminal_three_modes", "advanced_remote_terminal", MACHINE, "三种模式都属于终端本体（与快捷键是两条独立入口）",
        "advanced_remote_terminal", "goal", "三头六臂", "用终端打开三种模式",
        "Three Heads, Six Arms", "Open three terminal modes",
        criteria=[
            ("grid", MODID + ":terminal_mode_grid"),
            ("patterns", MODID + ":terminal_mode_patterns"),
            ("manager", MODID + ":terminal_mode_manager"),
        ], theme="终端与工具"),
]

BG_BASE = (0x1E, 0x23, 0x2C)     # 深蓝灰底（贴近 RS / Create 的成就页观感）
BG_LINE = (0x2A, 0x31, 0x40)     # 网格线
BG_DOT = (0x33, 0x3C, 0x4E)      # 交叉点亮点


def flatten():
    """校验后的 (spec, parent) 清单：parent 显式写在数据里，这里只做一致性校验。"""
    specs = [dict(spec) for spec in ADVANCEMENTS]
    specs[0]["zh_title"] = read_mod_name()
    specs[0]["en_title"] = read_mod_name()
    ids = [spec["id"] for spec in specs]
    if len(set(ids)) != len(ids):
        raise RuntimeError("成就 id 重复：%s" % sorted(i for i in set(ids) if ids.count(i) > 1))
    if specs[0]["id"] != "root" or specs[0]["parent"] is not None:
        raise RuntimeError("第一条必须是 parent=None 的 root")
    for spec in specs[1:]:
        if not spec["parent"]:
            raise RuntimeError("%s 没有 parent（只允许 root 无 parent）" % spec["id"])
        if spec["parent"] not in ids:
            raise RuntimeError("%s 的 parent 不存在：%s" % (spec["id"], spec["parent"]))
        if spec["kind"] not in (ENTRY, RECIPE, MACHINE, OUTPUT, COMPOSE, PREREQ, PIPELINE):
            raise RuntimeError("%s 的前置类型非法：%s" % (spec["id"], spec["kind"]))
        if not spec["why"]:
            raise RuntimeError("%s 没有写前置理由" % spec["id"])
    return [(spec, spec["parent"]) for spec in specs]


def gen_background():
    """生成 16x16 可无缝平铺的成就树背景（原版成就页就是按 16x16 平铺这张图）。"""
    image = Image.new("RGBA", (16, 16), BG_BASE + (255,))
    pixels = image.load()
    for i in range(16):
        pixels[i, 0] = BG_LINE + (255,)   # 只画 x=0 / y=0 两条线 → 平铺后自动成网格
        pixels[0, i] = BG_LINE + (255,)
    for x, y in ((4, 4), (4, 12), (12, 4), (12, 12)):
        pixels[x, y] = BG_DOT + (255,)
        pixels[x, y + 1] = BG_DOT + (255,)
    os.makedirs(os.path.dirname(BG_PATH), exist_ok=True)
    image.save(BG_PATH)
    return BG_PATH


def build_criteria(spec):
    """criterion 字典 + requirements：三种形态（多条件 / 单自定义触发器 / 原版物品谓词）。"""
    if spec["criteria"]:
        criteria = {key: {"trigger": trigger} for key, trigger in spec["criteria"]}
        requirements = [[key] for key, _ in spec["criteria"]]
        return criteria, requirements
    key = spec["id"]
    if spec["trigger"]:
        return {key: {"trigger": spec["trigger"]}}, [[key]]
    # items 谓词必须写全限定 id（HolderSet codec：单个可用字符串，多个用列表）
    items = ["%s:%s" % (MODID, i) for i in spec["items"]]
    criteria = {key: {"trigger": "minecraft:inventory_changed",
                      "conditions": {"items": [{"items": items}]}}}
    return criteria, [[key]]


def build_json(spec, parent):
    """把一条定义渲染成原版风格的成就 JSON（键名排序 = datagen 风格）。"""
    criteria, requirements = build_criteria(spec)
    display = {
        "icon": {"count": 1, "id": "%s:%s" % (MODID, spec["icon"])},
        "title": {"translate": lang_key(spec["id"], "title")},
        "description": {"translate": lang_key(spec["id"], "description")},
        "frame": spec["frame"],
        "show_toast": True,
        "announce_to_chat": True,
        "hidden": False,
    }
    payload = {"criteria": criteria, "display": display, "requirements": requirements}
    if parent:
        payload["parent"] = "%s:%s" % (MODID, parent)
    else:
        # 根节点：原版要求必须有 background（指向真实存在的贴图，见 selfcheck_advancements.py）
        display["background"] = "%s:%s" % (MODID, BG_REL)
    return payload


def render_json(spec, parent):
    """JSON 文本（与写盘内容完全一致）——selfcheck 用同一渲染器比对「生成器 ↔ 产物」。"""
    return json.dumps(build_json(spec, parent), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def lang_key(id_, part):
    return "advancements.%s.%s.%s" % (MODID, id_, part)


def write_json_files(flat):
    os.makedirs(ADV_DIR, exist_ok=True)
    expected = set()
    for spec, parent in flat:
        name = spec["id"] + ".json"
        expected.add(name)
        text = render_json(spec, parent)
        json.loads(text)  # 自校验：写盘前必须能被解析
        with io.open(os.path.join(ADV_DIR, name), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
    # 幂等：删掉不在清单里的旧成就文件，避免改名后残留孤儿
    for name in sorted(os.listdir(ADV_DIR)):
        if name.endswith(".json") and name not in expected:
            os.remove(os.path.join(ADV_DIR, name))
    return len(flat)


def merge_lang(file_name, index, flat):
    path = os.path.join(LANG_DIR, file_name)
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    added = 0
    for spec, _ in flat:
        for part in ("title", "description"):
            key = lang_key(spec["id"], part)
            value = spec["%s_%s" % (index, "title" if part == "title" else "desc")]
            if data.get(key) != value:
                data[key] = value
                added += 1
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    json.loads(text)  # 自校验：写盘前必须能被解析
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return added


def report_tree(flat):
    """按 parent 打印树（带前置类型），便于人工核对「依赖是否只剩必要的那些」。"""
    parents = {spec["id"]: parent for spec, parent in flat}
    kind = {spec["id"]: spec["kind"] for spec, _ in flat}
    why = {spec["id"]: spec["why"] for spec, _ in flat}
    children = {}
    for spec, parent in flat:
        children.setdefault(parent, []).append(spec["id"])
    order = [spec["id"] for spec, _ in flat]
    lines = []

    def walk(node, depth):
        mark = "[%s] %s" % (kind[node] or "root", node)
        lines.append("%s%s%s" % ("    " * depth, "└─ " if depth else "", mark))
        if depth:
            lines.append("%s    理由：%s" % ("    " * depth, why[node]))
        for child in order:
            if parents.get(child) == node:
                walk(child, depth + 1)

    for theme in dict.fromkeys([spec["theme"] for spec, _ in flat]):
        lines.append("== %s ==" % theme)
    walk("root", 0)
    print("\n".join(lines))
    orphans = [i for i, p in parents.items() if p and p not in parents]
    if orphans:
        raise RuntimeError("parent 指向不存在的成就：%s" % orphans)
    print("[tree] 共 %d 条成就；root 直接子节点 %d 个；最大深度 %d 层"
          % (len(flat), len(children.get("root", [])), max_depth(parents)))


def max_depth(parents):
    best = 0
    for start in parents:
        depth, cursor = 0, parents.get(start)
        while cursor:
            depth += 1
            cursor = parents.get(cursor)
        best = max(best, depth)
    return best


def main():
    flat = flatten()
    report_tree(flat)
    print("[gen] 成就 JSON：%d 个 -> %s" % (write_json_files(flat), os.path.relpath(ADV_DIR, ROOT_DIR)))
    print("[gen] 背景贴图：%s" % os.path.relpath(gen_background(), ROOT_DIR))
    for file_name, index in (("zh_cn.json", "zh"), ("en_us.json", "en")):
        print("[gen] %s 写入/更新 %d 个成就语言键" % (file_name, merge_lang(file_name, index, flat)))
    print("[gen] 根标题 = %s（来源 gradle.properties 的 mod_name）" % read_mod_name())
    return 0


if __name__ == "__main__":
    sys.exit(main())
