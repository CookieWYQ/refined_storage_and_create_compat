#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 101 轮自检：新方块「降解室发电机」（task-33）。

**第 123 轮已把本方块整体重做**（20 格库存 + 6 升级槽 + 恒定 62 槽 + 熔炉箭头 + 一条 RsccEnergyBar），
本脚本随之迁移：旧设计口径的断言（256 格 / 5 页分页 / 展示槽 / 竖进度条 / 红字提示 / 无效槽计数）
已改写为新设计的断言；**第 123 轮新引入的不变量**（槽数对账 / 数据包无限电 / chance 裁剪 /
存档迁移守恒 / 贴图几何 / 新 lang 键）在 `selfcheck_round123_decomp_redesign.py` 里，
两个脚本分工见那边的文件头。

本脚本<b>不启动游戏</b>，用三层手段交叉验证交付物：

1. **数据层**：直接读 `data/rs_create_compat/decomposition/*.json` 与 `assets/`，
   校验数值范围、必填字段、禁止出现的木头类**输入**（木棍允许作为树叶的产物）、
   禁止枯萎类、禁止「压缩块 → 原料」无限电条目、农夫乐事的 `mod_loaded` 门；
   若本机有 MC 编译产物 + JDK（`D:/java21/bin/javap.exe`），还会把每一条 `item` id
   与 `net.minecraft.world.item.Items` 的字段表逐字对照（**这是「id 真的存在」的唯一硬证据**）。
2. **语义层**：用 Python 复刻核心算法并做单元测试 ——
   （a）数据包合并（替换/追加/后写覆盖/remove/mod_loaded 门/未知 id 跳过）；
   （b）机器 tick 状态机（进度推进、到点**恰好发一次电**、槽 -1、进度清零、缓存满暂停不消耗、
       环形游标覆盖全部 16 个输入格不饿死、重载后中止/重计时都不丢物品）；
   （c）库存布局（16 输入 + 4 副产物 = 20 格、两段互斥完备）与副产物投放
       （4 格 × 64、同类堆叠、不同类不混装、「判得下 ⇔ 放得下」必须同一算法）；
   （d）升级换算（速度只缩短耗时、堆叠只抬每格上限）。
3. **源码层**：对交付的 Java 文件做文本断言（只出不可进、进度落盘、62 槽一端一口径、
   结算三判与顺序、六面推送限速、provider 读数、拆方块结算库存 + 升级容器 …），
   并对每条断言配一个**反例**：把源码就地改成错误写法，断言必须变成「失败」
   ——证明这些断言不是永远为真的摆设。

用法：
    python tools/selfcheck_round101_generator.py            # 全量（找不到 MC jar 时数据校验降级并打印 SKIP）
    python tools/selfcheck_round101_generator.py --mc-jar <path>
    python tools/selfcheck_round101_generator.py --no-vanilla-check
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = "src/main/java/cretae/cookiewyq/rs_create_compat"
RES = "src/main/resources"

FILES = {
    "be": f"{JAVA}/block/entity/DecompositionGeneratorBlockEntity.java",
    "block": f"{JAVA}/block/DecompositionGeneratorBlock.java",
    "node": f"{JAVA}/network/DecompositionGeneratorNetworkNode.java",
    "menu": f"{JAVA}/menu/DecompositionGeneratorMenu.java",
    "screen": f"{JAVA}/client/screen/DecompositionGeneratorScreen.java",
    "spec": f"{JAVA}/support/DecompositionGeneratorSpec.java",
    "table": f"{JAVA}/support/DecompositionTable.java",
    "profile": f"{JAVA}/support/DecompositionProfile.java",
    "loader": f"{JAVA}/support/DecompositionDataLoader.java",
    "builtin": f"{RES}/data/rs_create_compat/decomposition/builtin_plants.json",
    "fd": f"{RES}/data/rs_create_compat/decomposition/farmers_delight.json",
    "loot": f"{RES}/data/rs_create_compat/loot_table/blocks/decomposition_generator.json",
    "blockstate": f"{RES}/assets/rs_create_compat/blockstates/decomposition_generator.json",
    "model": f"{RES}/assets/rs_create_compat/models/block/decomposition_generator.json",
    "model_active": f"{RES}/assets/rs_create_compat/models/block/decomposition_generator_active.json",
    "item_model": f"{RES}/assets/rs_create_compat/models/item/decomposition_generator.json",
    "lang_zh": f"{RES}/assets/rs_create_compat/lang/zh_cn.json",
    "lang_en": f"{RES}/assets/rs_create_compat/lang/en_us.json",
    "docs": "docs/DECOMPOSITION_DATAPACK.md",
    "doc_example": "docs/decomposition_example.json",
}

# 必须**不存在**于默认表里的木头类 id（用户硬要求：不包括原木之类的木头）
# 只针对**输入**（`entries[].item` / `entries[].tag`）：木棍可以作为「树叶的产物」出现
# （第 123 轮平衡表：树叶 → 木棍 ×1），但绝不能作为可降解输入。
FORBIDDEN_WOOD_IDS = [
    "minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log",
    "minecraft:oak_planks", "minecraft:oak_slab", "minecraft:oak_stairs",
    "minecraft:oak_fence", "minecraft:oak_door", "minecraft:stick",
    "minecraft:crafting_table", "minecraft:chest", "minecraft:barrel",
    "minecraft:bamboo_block", "minecraft:bamboo_planks",
]
# 用户硬约束 1：枯萎类一律不收（「只做植物、且不是枯萎的」）
FORBIDDEN_DECAY_IDS = ["minecraft:dead_bush", "minecraft:dead_brain_coral",
                       "minecraft:dead_bubble_coral", "minecraft:dead_fire_coral",
                       "minecraft:dead_horn_coral", "minecraft:dead_tube_coral"]
# 用户硬约束 2：压缩块不许把**原料**按 1:1 / ×9 退回来（否则「合成 → 降解 → 再合成」= 无限电）
NO_INFINITE_ENERGY = {
    "minecraft:hay_block": {"minecraft:wheat"},
    "minecraft:dried_kelp_block": {"minecraft:dried_kelp"},
}
# 内置表里必须出现的 tag（覆盖「原版所有植物」的口径）
REQUIRED_TAGS = [
    "minecraft:leaves", "minecraft:flowers", "minecraft:saplings",
    "minecraft:villager_plantable_seeds",
]
# 内置表里必须显式出现的关键植物（类别代表；少了就说明覆盖面退化了）
REQUIRED_ITEMS = [
    "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
    "minecraft:sugar_cane", "minecraft:cactus", "minecraft:bamboo", "minecraft:kelp",
    "minecraft:dried_kelp_block", "minecraft:brown_mushroom", "minecraft:red_mushroom",
    "minecraft:vine", "minecraft:lily_pad", "minecraft:short_grass", "minecraft:fern",
    "minecraft:pumpkin", "minecraft:melon", "minecraft:hay_block",
    "minecraft:nether_wart", "minecraft:cocoa_beans", "minecraft:chorus_flower",
    "minecraft:big_dripleaf", "minecraft:moss_block",
]

CHECKS: list[tuple[str, bool, str]] = []
# 反例模式：不记录 CHECKS（避免污染正式断言计数），只把失败项攒到 PENDING_FAILURES
COLLECT_ONLY = False
PENDING_FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if COLLECT_ONLY:
        if not ok:
            PENDING_FAILURES.append(name + (f"（{detail}）" if detail else ""))
        return
    CHECKS.append((name, bool(ok), detail))


def read(path: str) -> str:
    full = os.path.join(ROOT, path)
    if not os.path.exists(full):
        return ""
    with open(full, encoding="utf-8") as handle:
        return handle.read()


def load_sources() -> dict[str, str]:
    return {key: read(path) for key, path in FILES.items()}


def count(pattern: str, text: str) -> int:
    return len(re.findall(pattern, text, re.S | re.M))


# =====================================================================================
# 1. 数据层校验
# =====================================================================================

def _parse_table_file(path: str, text: str | None = None) -> dict:
    """解析降解表 JSON。`text` 给定时用它（反例模式要注入「被改坏的源码」），否则读磁盘。"""
    if text is not None:
        return json.loads(text)
    return json.loads(read(path))


def _validate_entries(obj: dict) -> list[str]:
    """返回条目问题列表（空 = 全部合法）。"""
    problems = []
    if not isinstance(obj, dict):
        return ["root not object"]
    entries = obj.get("entries")
    if entries is None:
        entries = []
    if not isinstance(entries, list):
        return ["entries not a list"]
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            problems.append(f"entries[{index}] not object")
            continue
        has_item = isinstance(entry.get("item"), str) and entry["item"].strip()
        has_tag = isinstance(entry.get("tag"), str) and entry["tag"].strip()
        if has_item == has_tag:  # 两者都缺 或 两个都有
            problems.append(f"entries[{index}] needs exactly one of item/tag")
        time = entry.get("time")
        energy = entry.get("energy")
        if not isinstance(time, int) or not (1 <= time <= 20 * 60 * 10):
            problems.append(f"entries[{index}] time out of range: {time!r}")
        if not isinstance(energy, int) or not (1 <= energy <= 100_000_000):
            problems.append(f"entries[{index}] energy out of range: {energy!r}")
        problems.extend(_validate_results(index, entry))
    return problems


def _validate_results(index: int, entry: dict) -> list[str]:
    """校验 `results` / `result`（第 123 轮新增的副产物字段）。

    `result` 只是「只有一个产物」时的语法糖，两者**等价**；同时写按非法处理
    （与 `DecompositionTable` 的解析侧口径一致：那一条整条跳过）。
    """
    problems: list[str] = []
    has_results = entry.get("results") is not None
    has_result = entry.get("result") is not None
    if has_results and has_result:
        problems.append(f"entries[{index}] 同时写了 results 与 result")
        return problems
    if not has_results and not has_result:
        return problems  # 没有副产物 = 只有电，合法
    raw = entry["results"] if has_results else entry["result"]
    products = raw if isinstance(raw, list) else [raw]
    for position, product in enumerate(products):
        where = (f"entries[{index}].results[{position}]" if has_results
                 else f"entries[{index}].result")
        if not isinstance(product, dict):
            problems.append(f"{where} not object")
            continue
        item = product.get("item")
        if not isinstance(item, str) or ":" not in item or not item.strip():
            problems.append(f"{where}.item 不是 ns:path: {item!r}")
            continue
        count = product.get("count", 1)
        if isinstance(count, bool) or not isinstance(count, int) or not (1 <= count <= 64):
            problems.append(f"{where}.count out of range: {count!r}")
        chance = product.get("chance", 1.0)
        if isinstance(chance, bool) or not isinstance(chance, (int, float)) \
                or not (0.0 <= float(chance) <= 1.0):
            problems.append(f"{where}.chance out of range: {chance!r}")
    return problems


def _collect_product_ids(obj: dict) -> list[str]:
    """所有 `results` / `result` 里的产物 id（第 123 轮新增）。"""
    products: list[str] = []
    for entry in obj.get("entries") or []:
        if not isinstance(entry, dict):
            continue
        has_results = entry.get("results") is not None
        raw = entry.get("results") if has_results else entry.get("result")
        if raw is None:
            continue
        for product in (raw if isinstance(raw, list) else [raw]):
            if isinstance(product, dict) and isinstance(product.get("item"), str):
                products.append(product["item"].strip())
    return products


def _collect_ids(obj: dict) -> tuple[list[str], list[str]]:
    items, tags = [], []
    for entry in obj.get("entries") or []:
        if not isinstance(entry, dict):
            continue
        if isinstance(entry.get("item"), str):
            items.append(entry["item"].strip())
        if isinstance(entry.get("tag"), str):
            tags.append(entry["tag"].strip())
    return items, tags


def vanilla_item_ids(mc_jar: str | None) -> set[str] | None:
    """用 javap 读 `net.minecraft.world.item.Items` 的字段名，转成注册名集合。

    javap 输出里的字段名就是注册名的大写形式（`SHORT_GRASS` → `short_grass`），
    这是当前环境里「不启动游戏也能拿到完整物品 id 列表」的最可靠办法。
    """
    javap = "D:/java21/bin/javap.exe"
    if mc_jar is None or not os.path.exists(javap) or not os.path.exists(mc_jar):
        return None
    try:
        out = subprocess.run([javap, "-p", "-cp", mc_jar, "net.minecraft.world.item.Items"],
                             capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    ids = set()
    for line in out.stdout.splitlines():
        match = re.search(r"public static final .* ([A-Z0-9_]+);$", line)
        if match:
            ids.add(match.group(1).lower())
    return ids or None


def auto_mc_jar() -> str | None:
    candidates = glob.glob("D:/gradle/caches/neoformruntime/intermediate_results/"
                           "compiledWithNeoForge_*_output.jar")
    if not candidates:
        return None
    candidates.sort(key=os.path.getmtime)
    return candidates[-1]


def audit_data(sources: dict[str, str], vanilla: set[str] | None) -> None:
    check("A01 builtin_plants.json 存在且非空", len(sources["builtin"]) > 100)
    check("A02 farmers_delight.json 存在且非空", len(sources["fd"]) > 100)

    builtin = fd = None
    try:
        builtin = _parse_table_file(FILES["builtin"], sources["builtin"])
    except json.JSONDecodeError as error:
        check("A03 builtin_plants.json 是合法 JSON", False, str(error))
    try:
        fd = _parse_table_file(FILES["fd"], sources["fd"])
    except json.JSONDecodeError as error:
        check("A04 farmers_delight.json 是合法 JSON", False, str(error))
    if builtin is None or fd is None:
        return
    check("A03 builtin_plants.json 是合法 JSON", True)
    check("A04 farmers_delight.json 是合法 JSON", True)

    problems = _validate_entries(builtin)
    check("A05 内置表所有条目字段/数值合法", not problems, "；".join(problems[:4]))
    problems_fd = _validate_entries(fd)
    check("A06 农夫乐事表所有条目字段/数值合法", not problems_fd, "；".join(problems_fd[:4]))

    items, tags = _collect_ids(builtin)
    products = _collect_product_ids(builtin)
    check("A07 内置表 tag 条目覆盖 4 类植物分区",
          all(tag in tags for tag in REQUIRED_TAGS),
          f"tags={tags}")
    missing_items = [item for item in REQUIRED_ITEMS if item not in items]
    check("A08 内置表显式覆盖各类植物", not missing_items, f"缺失={missing_items}")
    # 用户硬约束 1：枯萎类一律不收（「只做植物、且不是枯萎的」）—— 输入与产物都不许出现
    decay_hits = [item for item in items + products if "dead_" in item]
    check("A08b 内置表不含任何枯萎类 id（用户硬约束 1：只做植物、且不是枯萎的）",
          not decay_hits, f"命中={decay_hits}")
    check("A09 内置表显式条目数量足够（≥ 40）", len(items) >= 40, f"items={len(items)}")

    builtin_text = sources["builtin"]
    # 木头类只针对**输入**：木棍是「树叶 → 木棍 ×1」的合法产物（第 123 轮平衡表），
    # 但它绝不能作为可降解输入（否则「木棍 → 电」等于允许木头发电）。
    forbidden_hits = [wood for wood in FORBIDDEN_WOOD_IDS if wood in items]
    check("A10 内置表不含任何木头类**输入**（用户硬要求）", not forbidden_hits, f"命中={forbidden_hits}")
    check("A10b 木棍只作为产物出现、不作为输入（树叶 → 木棍）",
          "minecraft:stick" in products and "minecraft:stick" not in items,
          f"products={products.count('minecraft:stick')} inputs={items.count('minecraft:stick')}")
    # 标签层面的兜底：不许出现 logs / planks 标签
    log_tags = ["minecraft:logs", "minecraft:planks", "minecraft:wooden_"]
    check("A11 内置表不引用 logs/planks 标签",
          not any(tag in tags for tag in log_tags)
          and not any(tag in builtin_text for tag in ("minecraft:logs", "minecraft:planks")))

    # 用户硬约束 2：压缩块不许把原料按 1:1 / ×9 退回来（否则「合成 → 降解 → 再合成」= 无限电）
    infinite_offenders = []
    for entry in builtin.get("entries") or []:
        if not isinstance(entry, dict):
            continue
        source_id = entry.get("item")
        raw_materials = NO_INFINITE_ENERGY.get(source_id) if isinstance(source_id, str) else None
        if not raw_materials:
            continue
        has_results = entry.get("results") is not None
        raw = entry.get("results") if has_results else entry.get("result")
        for product in (raw if isinstance(raw, list) else [raw] if raw else []):
            if not isinstance(product, dict):
                continue
            if product.get("item") in raw_materials:
                infinite_offenders.append((source_id, product.get("item"), product.get("count")))
    check("A11b 压缩块不退回原料（禁止「合成 → 降解 → 再合成」无限电；干草块/海带块必须给骨粉）",
          not infinite_offenders, f"违规={infinite_offenders}")
    check("A11c 所有产物 id 形如 ns:path",
          all(":" in product_id and product_id.split(":", 1)[0] and product_id.split(":", 1)[1]
              for product_id in products),
          f"异常={[p for p in products if ':' not in p][:4]}")

    check("A12 农夫乐事表用 mod_loaded 门控", fd.get("mod_loaded") == "farmersdelight",
          f"mod_loaded={fd.get('mod_loaded')!r}")
    fd_items, fd_tags = _collect_ids(fd)
    verified_fd_ids = {
        "farmersdelight:cabbage", "farmersdelight:tomato", "farmersdelight:onion",
        "farmersdelight:cabbage_leaf", "farmersdelight:cabbage_seeds",
        "farmersdelight:tomato_seeds", "farmersdelight:rice", "farmersdelight:rice_panicle",
    }
    unknown_fd = [item for item in fd_items if item not in verified_fd_ids]
    check("A13 农夫乐事表只用已核实的 id", not unknown_fd, f"未核实={unknown_fd}")
    verified_fd_tags = {"farmersdelight:wild_crops", "farmersdelight:mushroom_colonies"}
    check("A14 农夫乐事表引用的是 1.21 分支真实存在的 tag",
          set(fd_tags) <= verified_fd_tags, f"tags={fd_tags}")

    if vanilla is None:
        print("SKIP 数据校验（原版 id 存在性）：找不到 MC 编译产物或 javap，"
              "本次只做「自有清单」层面的断言")
    else:
        unknown = [item for item in items
                   if item.startswith("minecraft:")
                   and item.split(":", 1)[1] not in vanilla]
        check("A15 内置表所有 minecraft: id 在 Items 里真实存在", not unknown,
              f"不存在={unknown[:6]}")

    # 资产 / 战利品表 / 文档
    check("A16 自掉战利品表存在且指向自己",
          "rs_create_compat:decomposition_generator" in sources["loot"])
    blockstate = sources["blockstate"]
    check("A17 blockstate 有 active=true / active=false 两套变体（否则工作态紫黑）",
          "active=true" in blockstate and "active=false" in blockstate)
    check("A18 inactive / active 模型都指向自己的贴图",
          "decomposition_generator_top_inactive" in sources["model"]
          and "decomposition_generator_top_active" in sources["model_active"])
    textures = glob.glob(os.path.join(ROOT, RES,
                                      "assets/rs_create_compat/textures/block/"
                                      "decomposition_generator_*.png"))
    check("A19 六张面贴图齐全（side/top/bottom × active/inactive）", len(textures) == 6,
          f"found={len(textures)}")
    check("A20 物品模型指向方块模型", "block/decomposition_generator_active" in sources["item_model"])
    check("A21 数据包说明文档 + 示例 JSON 存在",
          "replace" in sources["docs"] and '"item"' in sources["doc_example"])
    try:
        example = json.loads(sources["doc_example"])
        check("A22 示例 JSON 合法且含 entries", isinstance(example.get("entries"), list))
    except json.JSONDecodeError as error:
        check("A22 示例 JSON 合法且含 entries", False, str(error))


# =====================================================================================
# 2. 语义层：数据包合并模型
# =====================================================================================

BUILTIN_NS = "rs_create_compat"


def merge_order(file_ids: list[str]) -> list[str]:
    """复刻 DecompositionTable.apply 的排序：内置命名空间最先，其余按 ns/path 字母序。"""
    def key(file_id: str):
        namespace, _, path = file_id.partition(":")
        return (0 if namespace == BUILTIN_NS else 1, namespace, path)
    return sorted(file_ids, key=key)


def merge_tables(files: dict[str, dict], known_items: set[str] | None = None,
                 loaded_mods: set[str] | None = None):
    """复刻数据包合并语义，返回 (by_item, by_tag, stats)。"""
    loaded_mods = loaded_mods or set()
    by_item: dict[str, tuple[int, int]] = {}
    by_tag: list[tuple[str, tuple[int, int]]] = []
    stats = {"files": 0, "applied": 0, "skipped": 0, "item_errors": 0, "entry_errors": 0}
    for file_id in merge_order(list(files)):
        stats["files"] += 1
        obj = files[file_id]
        if not isinstance(obj, dict):
            stats["entry_errors"] += 1
            continue
        mod_loaded = obj.get("mod_loaded")
        if isinstance(mod_loaded, str) and mod_loaded and mod_loaded not in loaded_mods:
            stats["skipped"] += 1
            continue
        if obj.get("replace") is True:
            by_item.clear()
            by_tag.clear()
        for removal in obj.get("remove") or []:
            if not isinstance(removal, str):
                stats["entry_errors"] += 1
                continue
            if removal.startswith("#"):
                tag = removal[1:].strip()
                by_tag = [pair for pair in by_tag if pair[0] != tag]
            else:
                by_item.pop(removal.strip(), None)
        entries = obj.get("entries")
        if entries is not None and not isinstance(entries, list):
            stats["entry_errors"] += 1
            continue
        for entry in entries or []:
            if not isinstance(entry, dict):
                stats["entry_errors"] += 1
                continue
            item = entry.get("item")
            tag = entry.get("tag")
            time = entry.get("time")
            energy = entry.get("energy")
            if not isinstance(time, int) or not isinstance(energy, int) or time < 1 or energy < 1:
                stats["entry_errors"] += 1
                continue
            if isinstance(item, str) and item:
                if known_items is not None and item not in known_items:
                    stats["item_errors"] += 1
                    continue
                by_item[item] = (time, energy)
            elif isinstance(tag, str) and tag:
                by_tag.append((tag, (time, energy)))
            else:
                stats["entry_errors"] += 1
        stats["applied"] += 1
    return by_item, by_tag, stats


def lookup(by_item: dict, by_tag: list, item_id: str, item_tags: set[str] | None = None):
    """复刻 DecompositionTable.profile：精确 item 优先，再按 tag 顺序懒匹配。"""
    if item_id in by_item:
        return by_item[item_id]
    for tag, profile in by_tag:
        if item_tags and tag in item_tags:
            return profile
    return None


def audit_merge_model() -> None:
    known = {"minecraft:oak_sapling", "minecraft:oak_log", "mymod:plant"}
    # B1 内置命名空间最先应用：其它命名空间的同名条目覆盖它
    files = {
        "mypack:a": {"entries": [{"item": "minecraft:oak_sapling", "time": 10, "energy": 100}]},
        "rs_create_compat:a": {"entries": [{"item": "minecraft:oak_sapling", "time": 99, "energy": 999}]},
    }
    by_item, _, _ = merge_tables(files, known)
    check("B01 后写覆盖先写（其它命名空间覆盖内置表）", by_item["minecraft:oak_sapling"] == (10, 100),
          str(by_item))
    # B2 replace 清空此前累积
    files = {
        "rs_create_compat:a": {"entries": [{"item": "minecraft:oak_sapling", "time": 99, "energy": 999}]},
        "mypack:a": {"replace": True,
                     "entries": [{"item": "mymod:plant", "time": 5, "energy": 50}]},
    }
    by_item, by_tag, _ = merge_tables(files, known)
    check("B02 replace=true 清空此前全部条目（含内置表）",
          "minecraft:oak_sapling" not in by_item and by_item.get("mymod:plant") == (5, 50),
          str(by_item))
    # B3 remove：item / tag 两种写法
    files = {
        "rs_create_compat:a": {"entries": [
            {"item": "minecraft:oak_sapling", "time": 10, "energy": 100},
            {"tag": "minecraft:leaves", "time": 10, "energy": 100},
        ]},
        "mypack:a": {"remove": ["minecraft:oak_sapling", "#minecraft:leaves"]},
    }
    by_item, by_tag, _ = merge_tables(files, known)
    check("B03 remove 支持 item 与 #tag 两种写法", not by_item and not by_tag,
          f"{by_item} {by_tag}")
    # B4 mod_loaded 门：没装 mod 的文件整份跳过
    files = {"mypack:a": {"mod_loaded": "not_installed",
                          "entries": [{"item": "mymod:plant", "time": 5, "energy": 50}]}}
    by_item, _, stats = merge_tables(files, known, loaded_mods=set())
    check("B04 mod_loaded 未安装 ⇒ 整份跳过（不产生无效条目）",
          not by_item and stats["skipped"] == 1)
    # B5 未知 id 只跳过自己
    files = {"mypack:a": {"entries": [
        {"item": "nonexistent:thing", "time": 5, "energy": 50},
        {"item": "minecraft:oak_sapling", "time": 10, "energy": 100},
    ]}}
    by_item, _, stats = merge_tables(files, known)
    check("B05 不存在的 item id 只跳过该条、计数 +1",
          "minecraft:oak_sapling" in by_item and stats["item_errors"] == 1)
    # B6 精确 item 优先于 tag
    files = {"rs_create_compat:a": {"entries": [
        {"tag": "minecraft:leaves", "time": 1, "energy": 1},
        {"item": "minecraft:oak_leaves", "time": 2, "energy": 2},
    ]}}
    by_item, by_tag, _ = merge_tables(files, {"minecraft:oak_leaves"})
    profile = lookup(by_item, by_tag, "minecraft:oak_leaves", {"minecraft:leaves"})
    check("B06 精确 item 优先于 tag 匹配", profile == (2, 2), str(profile))
    # B7 非法数值条目被拒
    files = {"mypack:a": {"entries": [
        {"item": "minecraft:oak_sapling", "time": 0, "energy": 100},
        {"item": "minecraft:oak_sapling", "time": 10, "energy": -5},
    ]}}
    by_item, _, stats = merge_tables(files, known)
    check("B07 time/energy 非法 ⇒ 条目被拒且计数", not by_item and stats["entry_errors"] == 2,
          f"{by_item} {stats}")
    # B8 内置默认表自身能被模型吃下（顺序 / 覆盖无异常）
    builtin = json.loads(read(FILES["builtin"]))
    fd = json.loads(read(FILES["fd"]))
    by_item, by_tag, stats = merge_tables({"rs_create_compat:builtin": builtin,
                                           "rs_create_compat:fd": fd}, loaded_mods={"farmersdelight"})
    check("B08 内置两表可被合并模型吃下（条目数 > 40、tag 数 = 6）",
          len(by_item) >= 40 and len(by_tag) == 6, f"items={len(by_item)} tags={len(by_tag)}")


# =====================================================================================
# 3. 语义层：机器 tick 状态机
# =====================================================================================

class Machine:
    """复刻 DecompositionGeneratorBlockEntity#tickDecomposition 的状态机。"""

    SLOT_COUNT = 16  # 第 123 轮：物品库存 20 格 = 16 输入 + 4 副产物；状态机只处理输入区

    def __init__(self, table: dict, capacity: int = 1_000_000):
        self.inventory = [0] * self.SLOT_COUNT
        self.items = [None] * self.SLOT_COUNT
        self.table = table
        self.capacity = capacity
        self.energy = 0
        self.current = -1
        self.progress = 0
        self.progress_total = 0
        self.progress_energy = 0
        self.scan_cursor = 0
        self.buffer_full = False
        self.generated_events = 0
        self.last_generated = 0

    def profile(self, slot: int):
        item = self.items[slot]
        if item is None or self.inventory[slot] <= 0:
            return None
        return self.table.get(item)

    def tick(self) -> None:
        self.buffer_full = False
        # ① 当前格校验
        if self.current >= 0:
            profile = self.profile(self.current)
            if profile is None:
                self.abort()
            elif profile[0] != self.progress_total or min(profile[1], self.capacity) != self.progress_energy:
                self.progress = 0
                self.progress_total = profile[0]
                self.progress_energy = min(profile[1], self.capacity)
        # ② 空闲 ⇒ 环形找下一格
        if self.current < 0:
            found = -1
            for offset in range(self.SLOT_COUNT):
                index = (self.scan_cursor + offset) % self.SLOT_COUNT
                if self.profile(index) is not None:
                    found = index
                    break
            if found < 0:
                self.progress = self.progress_total = self.progress_energy = 0
                return
            self.current = found
            self.progress = 0
            profile = self.table[self.items[found]]
            self.progress_total = profile[0]
            # 与 Java 的 rewardFor 同口径：单次发电量夹到缓存容量
            self.progress_energy = min(profile[1], self.capacity)
        # ③ 缓存满 ⇒ 暂停（不推进、不消耗）
        if self.capacity - self.energy < self.progress_energy:
            self.buffer_full = True
            return
        # ④ 推进
        self.progress += 1
        if self.progress < self.progress_total:
            return
        # ⑤ 到点：恰好发一次电 + 槽 -1
        self.energy += self.progress_energy
        self.generated_events += 1
        self.last_generated = self.progress_energy
        self.inventory[self.current] -= 1
        finished = self.current
        self.progress = self.progress_total = self.progress_energy = 0
        self.current = -1
        self.scan_cursor = (finished + 1) % self.SLOT_COUNT

    def abort(self) -> None:
        self.current = -1
        self.progress = self.progress_total = self.progress_energy = 0


def audit_machine_model() -> None:
    table = {"minecraft:oak_sapling": (10, 500)}
    machine = Machine(table)
    machine.items[0] = "minecraft:oak_sapling"
    machine.inventory[0] = 3
    for _ in range(9):
        machine.tick()
    check("C01 进度未满时发电量为 0", machine.energy == 0 and machine.generated_events == 0,
          f"energy={machine.energy}")
    check("C02 第 10 tick 恰好完成", machine.progress == 9)
    machine.tick()
    check("C03 到点恰好发一次电（energy == 单次发电量）",
          machine.energy == 500 and machine.generated_events == 1, f"{machine.energy}")
    check("C04 到点该槽 -1 且进度清零",
          machine.inventory[0] == 2 and machine.progress == 0 and machine.current == -1,
          f"count={machine.inventory[0]} progress={machine.progress}")
    for _ in range(10):
        machine.tick()
    check("C05 连续处理：第二次完成后总发电 == 2 倍、槽 == 1",
          machine.inventory[0] == 1 and machine.energy == 1000 and machine.generated_events == 2,
          f"count={machine.inventory[0]} energy={machine.energy}")
    for _ in range(40):
        machine.tick()
    check("C06 槽空了以后不再发电、不再消耗",
          machine.inventory[0] == 0 and machine.energy == 1500 and machine.generated_events == 3,
          f"energy={machine.energy}")

    # C07 缓存满 ⇒ 暂停且不消耗物品
    machine = Machine({"mymod:plant": (5, 800_000)}, capacity=1_000_000)
    machine.items[0] = "mymod:plant"
    machine.inventory[0] = 5
    machine.energy = 500_000  # 剩余空间 500k < 800k ⇒ 永远无法完成
    for _ in range(200):
        machine.tick()
    check("C07 缓存装不下单次发电量时暂停：进度不推进、物品不被吃",
          machine.inventory[0] == 5 and machine.energy == 500_000 and machine.buffer_full,
          f"count={machine.inventory[0]} energy={machine.energy} full={machine.buffer_full}")
    machine.energy = 100_000  # 腾出空间
    for _ in range(5):
        machine.tick()
    check("C08 空间够了立即完成一次（且只补发一次）",
          machine.inventory[0] == 4 and machine.energy == 900_000 and machine.generated_events == 1,
          f"count={machine.inventory[0]} energy={machine.energy}")

    # C09 环形游标覆盖全部输入格（不饿死后面的槽）
    table_many = {"mymod:plant": (1, 10)}
    machine = Machine(table_many, capacity=10_000_000)
    for slot in range(Machine.SLOT_COUNT):
        machine.items[slot] = "mymod:plant"
        machine.inventory[slot] = 1
    for _ in range(Machine.SLOT_COUNT):
        machine.tick()
    check("C09 环形游标让 16 格输入各处理一次（无饿死）",
          all(count == 0 for count in machine.inventory)
          and machine.generated_events == Machine.SLOT_COUNT,
          f"events={machine.generated_events}")

    # C10 数据包中途改数值 ⇒ 从 0 重新计时、物品不丢
    table_a = {"mymod:plant": (10, 100)}
    machine = Machine(table_a)
    machine.items[0] = "mymod:plant"
    machine.inventory[0] = 1
    for _ in range(4):
        machine.tick()
    machine.table = {"mymod:plant": (3, 300)}  # 重载后变了
    machine.tick()
    check("C10 重载改了数值 ⇒ 进度从 0 重计（物品仍在）",
          machine.progress == 1 and machine.inventory[0] == 1 and machine.energy == 0,
          f"progress={machine.progress} count={machine.inventory[0]}")
    for _ in range(2):
        machine.tick()
    check("C11 新数值生效后按新发电量结算",
          machine.energy == 300 and machine.inventory[0] == 0, f"energy={machine.energy}")

    # C12 重载后当前物品不可降解 ⇒ 中止但物品保留
    machine = Machine({"mymod:plant": (10, 100)})
    machine.items[0] = "mymod:plant"
    machine.inventory[0] = 3
    for _ in range(3):
        machine.tick()
    machine.table = {}  # 重载后这一条没了
    machine.tick()
    check("C12 重载后当前物品不可降解 ⇒ 中止进度、物品原地保留",
          machine.current == -1 and machine.progress == 0 and machine.inventory[0] == 3,
          f"current={machine.current} count={machine.inventory[0]}")

    # C15 单次发电量 > 缓存容量 ⇒ 按容量结算（不会永远转不完）
    machine = Machine({"mymod:big": (5, 2_000_000)}, capacity=1_000_000)
    machine.items[0] = "mymod:big"
    machine.inventory[0] = 1
    for _ in range(5):
        machine.tick()
    check("C15 单次发电量超缓存容量时按容量结算（不会永远卡住）",
          machine.energy == 1_000_000 and machine.inventory[0] == 0 and machine.generated_events == 1,
          f"energy={machine.energy} count={machine.inventory[0]}")

    # C13 / C14 库存布局（第 123 轮：20 格 = 16 输入 + 4 副产物，不再有 256 格分页）
    INPUT_FIRST, INPUT_COUNT = 0, 16
    OUTPUT_FIRST, OUTPUT_COUNT = INPUT_FIRST + INPUT_COUNT, 4
    inventory_size = INPUT_COUNT + OUTPUT_COUNT

    def input_slot(index):
        return INPUT_FIRST + index

    def output_slot(index):
        return OUTPUT_FIRST + index

    def is_input_slot(slot):
        return INPUT_FIRST <= slot < INPUT_FIRST + INPUT_COUNT

    def is_output_slot(slot):
        return OUTPUT_FIRST <= slot < OUTPUT_FIRST + OUTPUT_COUNT

    layout = ([input_slot(index) for index in range(INPUT_COUNT)]
              + [output_slot(index) for index in range(OUTPUT_COUNT)])
    check("C13 库存布局 16 输入 + 4 副产物 = 20 格，恰好铺满 0..19 且无重叠（不再有 256 格）",
          sorted(layout) == list(range(inventory_size)) and inventory_size == 20,
          f"size={inventory_size} first={layout[:3]} last={layout[-3:]}")
    check("C14 每格恰好属于「输入」或「副产物」之一（isInputSlot / isOutputSlot 互斥且完备）",
          all(is_input_slot(slot) != is_output_slot(slot) for slot in range(inventory_size))
          and all(is_input_slot(slot) or is_output_slot(slot) for slot in range(inventory_size)))

    # C16 / C17 副产物投放：4 格 × 64、同类堆叠、不同类不混装；「判」与「放」必须同一算法
    OUTPUT_LIMIT = 64

    def deposit_into_four(slots, product, count):
        """复刻 depositIntoFour：返回放不下的余量（None = 全部放下）。"""
        for index in range(len(slots)):
            existing = slots[index]
            if existing is not None and existing[0] != product:
                continue
            used = 0 if existing is None else existing[1]
            room = OUTPUT_LIMIT - used
            if room <= 0:
                continue
            moved = min(room, count)
            slots[index] = (product, used + moved)
            count -= moved
            if count <= 0:
                return None
        return (product, count) if count > 0 else None

    slots = [None] * OUTPUT_COUNT
    leftovers = [deposit_into_four(slots, product, count)
                 for product, count in (("a", 64), ("a", 64), ("b", 1))]
    check("C16 副产物 4 格 × 64：同类堆叠、不同类不混装，放得下时余量为空",
          slots[0] == ("a", 64) and slots[1] == ("a", 64) and slots[2] == ("b", 1)
          and all(leftover is None for leftover in leftovers),
          f"slots={slots}")

    def naive_fits(batch):
        """错判法：每一项都在「同一份空快照的副本」上试放 —— 不把前一项的占用记账。"""
        return all(deposit_into_four([None] * OUTPUT_COUNT, product, count) is None
                   for product, count in batch)

    def sequential_fits(batch):
        """正确判法：在**同一份快照**上按顺序试放（判据与投放逐字相同）。"""
        snapshot = [None] * OUTPUT_COUNT
        return all(deposit_into_four(snapshot, product, count) is None
                   for product, count in batch)

    batch = [("a", 64)] * 5  # 5 × 64 = 320 件 > 4 格 × 64 = 256
    check("C17 顺序投放才是对的：5 × 64 同类产物放不进 4 格（256 容量），"
          "「每项独立判空位」的错判法会误判成放得下",
          naive_fits(batch) and not sequential_fits(batch))

    # C18 升级换算：速度升级只缩短耗时、不改单件发电量；堆叠升级只抬每格上限
    def level(raw_upgrades):
        return max(0, min(6, raw_upgrades))

    def interval_ticks(base_interval, speed_upgrades):
        return max(1, max(1, base_interval) >> level(speed_upgrades))

    def batch_saturating(base_batch, stack_upgrades):
        return max(1, base_batch) * (1 + level(stack_upgrades))

    check("C18 速度升级只缩短耗时（max(1, base >> level)，level 夹 0..6、不改单件发电量）；"
          "堆叠升级只抬每格上限（64 × (1 + level)，满级 448）",
          interval_ticks(20, 0) == 20 and interval_ticks(20, 6) == 1
          and interval_ticks(20, 64) == 1 and interval_ticks(20, -3) == 20
          and batch_saturating(64, 0) == 64 and batch_saturating(64, 6) == 448
          and batch_saturating(64, -1) == 64)


# =====================================================================================
# 4. 源码层断言（每条都配反例）
# =====================================================================================


def lang_key_arg_counts(text: str) -> dict[str, set[int]]:
    """扫描 `Component.translatable("key", ...)`：统计每个语言键出现过的「参数个数」集合。

    这是专门用来抓一类**静态审查很难发现、运行期才炸**的 bug：同一个 key 在两处分别用 2 个 / 3 个
    参数调用，MC 的 TranslatableContents 会走 `String.format`，参数不够直接抛
    `MissingFormatArgumentException`（界面打开即崩）。
    """
    result: dict[str, set[int]] = {}
    for match in re.finditer(r'Component\.translatable\(\s*"([^"]+)"', text):
        key = match.group(1)
        index = match.end()
        depth = 0
        commas = 0
        in_string = False
        previous = ""
        while index < len(text):
            char = text[index]
            if in_string:
                if char == '"' and previous != "\\":
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                if depth == 0:
                    break
                depth -= 1
            elif char == "," and depth == 0:
                commas += 1
            previous = char
            index += 1
        result.setdefault(key, set()).add(commas)
    return result


def lang_placeholder_counts(text: str) -> dict[str, int]:
    """语言文件里每个键的 `%s` 个数（用来核对调用处实参个数）。

    这是「界面打开即崩」那类 bug 的静态判据：MC 的 `TranslatableContents` 走
    `String.format`，实参个数 != 占位符个数就抛 `MissingFormatArgumentException`。
    """
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {}
    if not isinstance(data, dict):
        return {}
    return {key: value.count("%s") for key, value in data.items() if isinstance(value, str)}


def body_after(text: str, signature_pattern: str, length: int = 400) -> str:
    match = re.search(signature_pattern, text, re.S)
    if not match:
        return ""
    return text[match.end():match.end() + length]


def strip_java_comments(text: str) -> str:
    """去掉 `//` 与 `/* */` 注释（判据只看真代码；javadoc 里提旧写法不算）。

    第 123 轮的 `DecompositionGeneratorMenu` javadoc 里专门写了「这里曾经写成 `36 + 9`」
    作为崩溃复盘 —— 那是文档，不该被「源码里不许出现 36 + 9」这类判据误伤。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def status_line_removed(screen: str) -> bool:
    """第 126 轮：用户点名删掉「状态 / 缓存 / 升级」三行文字，状态行四态随之整段删除。

    原先这里校验的是 `drawStatusTexts` 里 if/else-if 阶梯的四态优先级
    （output_full > buffer_full > idle > working）；现在方法体、四个语言键、以及
    `DecompositionGeneratorSpec.TEXT_*` 常量都已删除，判据反过来 —— 任何人把它们加回来
    都算退化（`selfcheck_round123_decomp_redesign.py` 里有对应的反例自证）。
    """
    keys = ("decomposition_generator.output_full", "decomposition_generator.buffer_full",
            "decomposition_generator.idle", "decomposition_generator.working")
    return "drawStatusTexts" not in screen and not any(key in screen for key in keys)


SOURCE_ASSERTIONS = 0


def upgrade_container_accessor(be: str, menu: str):
    """读「服务端升级容器访问器」的**实际方法名**，并确认方块实体里有同名方法返回容器。

    契约文档写的是 `getUpgrades()`，但实际源码里那个名字被基类接口占成
    `List<ItemStack> getUpgrades()`（返回升级内容列表），容器访问器因此叫 `getUpgradeContainer()`。
    自检不能僵在文档上：这里从 Menu 的 `upgradeHandler = generator.XXX();` 反查真实名字，
    再去方块实体里核对签名，两边对不上就判负（改名不会被漏检，写错也不会被放过）。
    """
    match = re.search(r"upgradeHandler\s*=\s*generator\.(get[A-Za-z0-9_]+)\(\)\s*;", menu)
    if not match:
        return None
    accessor = match.group(1)
    if not re.search(r"public\s+RsccSpeedStackUpgradeContainer\s+" + re.escape(accessor), be):
        return None
    return accessor


def audit_sources(sources: dict[str, str]) -> list[str]:
    """返回失败项名称列表（空 = 全绿）。反例测试靠这里逐个判定。"""
    global SOURCE_ASSERTIONS
    failures: list[str] = []

    def expect(name: str, ok: bool, detail: str = "") -> None:
        global SOURCE_ASSERTIONS
        if not COLLECT_ONLY:
            SOURCE_ASSERTIONS += 1
        if not ok:
            failures.append(name + (f"（{detail}）" if detail else ""))

    be = sources["be"]
    node = sources["node"]
    menu = sources["menu"]
    screen = sources["screen"]
    table = sources["table"]
    loader = sources["loader"]
    block = sources["block"]
    profile = sources["profile"]
    # 判据只看真代码：注释里提到旧写法（「曾经写成 36 + 9」「旧版是 256 格」）不算违规
    be_code = strip_java_comments(be)
    menu_code = strip_java_comments(menu)
    screen_code = strip_java_comments(screen)

    # ---- D1 只出不可进 ----
    receive_body = body_after(be, r"public int receiveEnergy\(final int toReceive, final boolean simulate\)\s*\{")
    expect("D01 receiveEnergy 恒 0（只出不可进）",
           receive_body.strip().startswith("return 0;"), receive_body[:60])
    expect("D02 canReceive 恒 false",
           bool(re.search(r"public boolean canReceive\(\)\s*\{\s*return false;", be, re.S)))
    expect("D03 外部能量视图只暴露这一个只出实例",
           "return energyStorage;" in be)

    # ---- D2 进度 / 当前槽落盘 ----
    save_body = body_after(be, r"public void saveAdditional\(", 1200)
    load_body = body_after(be, r"public void loadAdditional\(", 1800)
    expect("D04 进度落盘", "tag.putInt(TAG_PROGRESS," in save_body)
    expect("D05 当前槽落盘", "tag.putInt(TAG_CURRENT_SLOT," in save_body)
    expect("D06 库存落盘", "inventory.serializeNBT(registries)" in save_body)
    expect("D07 电量落盘", "tag.putInt(TAG_ENERGY," in save_body)
    expect("D08 读档恢复进度 / 当前槽",
           "TAG_PROGRESS" in load_body and "TAG_CURRENT_SLOT" in load_body)
    expect("D09 读档走「设值」语义（不经过 receiveEnergy）",
           "energyStorage.setInternal(" in load_body)

    # ---- D3 到点恰好发一次电 + 消耗一次（第 123 轮：结算搬进 settle()） ----
    settle_body = body_after(be, r"private void settle\(", 2600)
    expect("D10 结算时恰好写一次能量", count(r"energyStorage\.addInternal\(reward\);", be) == 1,
           f"count={count(r'energyStorage.addInternal(reward);', be)}")
    expect("D11 结算时恰好扣一次物品",
           count(r"inventory\.extractItem\(DecompositionGeneratorSpec\.inputSlot\(inputIndex\), 1, false\);",
                 be) == 1)
    expect("D12 结算后进度清零、当前格释放、游标按输入格数前移",
           "progress = 0;" in settle_body and "currentSlot = -1;" in settle_body
           and "scanCursor = (finished + 1) % DecompositionGeneratorSpec.INPUT_COUNT;" in settle_body,
           settle_body[:80])
    # 结算顺序硬约束：写缓存 → 写产物 → 扣 1 件（否则会出现「吃了植物没产出 / 电丢了」）
    energy_at = settle_body.find("energyStorage.addInternal(reward);")
    outputs_at = settle_body.find("writeOutputs(settledOutputs);")
    consume_at = settle_body.find("inventory.extractItem(")
    expect("D58 结算顺序固定：写缓存 → 写产物 → 扣 1 件（三判都过才结算）",
           0 <= energy_at < outputs_at < consume_at, f"{energy_at}/{outputs_at}/{consume_at}")
    tick_body = body_after(be, r"private void tickDecomposition\(\)", 4000)
    # 缓存满判定必须出现在 progress++ 之前
    buffer_index = tick_body.find("getEnergyCapacity() - getEnergyStored() < progressEnergy")
    progress_index = tick_body.find("progress++;")
    expect("D13 缓存满判定在进度推进之前（暂停时不吃物品）",
           0 <= buffer_index < progress_index, f"{buffer_index} vs {progress_index}")
    # 副产物满判定必须在结算调用之前（放不下就不消耗物品，玩家清格子后自动继续）
    fit_at = tick_body.find("productsFit(products)")
    settle_at = tick_body.find("settle(currentSlot, progressEnergy, products);")
    expect("D59 副产物放不下就停在当前进度（productsFit 判定在 settle 之前，且置 outputFull）",
           0 <= fit_at < settle_at and "outputFull = true;" in tick_body,
           f"{fit_at} vs {settle_at}")

    # ---- D4 库存布局 / 槽位数（第 123 轮：20 格库存 + 6 升级 + 36 玩家 = 62 槽，无分页） ----
    spec = sources["spec"]
    expect("D14 库存 20 格 = 16 输入（4×4）+ 4 副产物（2×2），Spec 里由行列推导",
           "public static final int INVENTORY_SIZE = INPUT_COUNT + OUTPUT_COUNT;" in spec
           and "public static final int INPUT_COUNT = INPUT_COLS * INPUT_ROWS;" in spec
           and "public static final int OUTPUT_COUNT = OUTPUT_COLS * OUTPUT_ROWS;" in spec
           and "public static final int INPUT_COLS = 4;" in spec
           and "public static final int INPUT_ROWS = 4;" in spec
           and "public static final int OUTPUT_COLS = 2;" in spec
           and "public static final int OUTPUT_ROWS = 2;" in spec)
    expect("D15 升级槽 6 格 = 3 列 × 2 行（独立容器，不算在物品库存里）",
           "public static final int UPGRADE_SLOTS = UPGRADE_COLS * UPGRADE_ROWS;" in spec
           and "public static final int UPGRADE_COLS = 3;" in spec
           and "public static final int UPGRADE_ROWS = 2;" in spec)
    expect("D16 方块实体按 Spec.INVENTORY_SIZE 建库存，菜单/实体里再无 256 格 SLOT_COUNT 常量",
           "new ItemStackHandler(DecompositionGeneratorSpec.INVENTORY_SIZE)" in be
           and not re.search(r"\bSLOT_COUNT\b", be)
           and not re.search(r"\bSLOT_COUNT\b", menu),
           f"be={bool(re.search(r'\\bSLOT_COUNT\\b', be))} menu={bool(re.search(r'\\bSLOT_COUNT\\b', menu))}")
    # 2026-10-31 第122轮：原判据把「36+9」当成正确值（双重计算快捷栏），实为 36；崩溃修复后判据随之纠正，原意「槽数声明必须等于实际」未放宽。
    expect("D17 菜单恒定 62 槽（20 机器 + 6 升级 + 36 玩家），声明 = 实际",
           "public static final int TOTAL_SLOT_COUNT = PLAYER_SLOT_END;" in menu
           and "PLAYER_SLOT_COUNT = PLAYER_MAIN_SLOT_COUNT + PLAYER_HOTBAR_SLOT_COUNT;" in menu)
    expect("D18 菜单只有 5 处 addSlot（输入 / 副产物 / 升级 / 主背包 / 快捷栏），没有按 256 循环",
           menu_code.count("addSlot(") == 5, f"addSlot={menu_code.count('addSlot(')}")
    # 升级容器的访问器名字以实际源码为准（契约文档写的 getUpgrades() 被接口占成了 List 版本）
    upgrade_accessor = upgrade_container_accessor(be, menu)
    expect("D19 服务端槽位包的是真库存 / 真升级容器（不是副本；访问器名字与方块实体的签名必须对得上）",
           "machineHandler = generator.getInventory();" in menu
           and upgrade_accessor is not None,
           f"accessor={upgrade_accessor}")
    expect("D20 客户端槽位用的就是 20 格镜像、且与服务端同判据（isItemValid / getSlotLimit 读数据槽）",
           "this.clientInventoryMirror = new ClientInventoryMirror(this.data);" in menu
           and "machineHandler = this.clientInventoryMirror;" in menu
           and "DecompositionGeneratorSpec.isInputSlot(slot)" in menu
           and "DecompositionGeneratorSpec.inputSlotLimit(data.get(DATA_STACK_UPGRADES))" in menu)
    expect("D21 输入槽只收可降解物（槽位是玩家能接触到的第一道门）",
           "return DecompositionTable.isDecomposable(stack) && super.mayPlace(stack);" in menu)
    expect("D22 数据槽 14 个且两端同源（Menu 直接取方块实体的常量）",
           "public static final int DATA_SLOT_COUNT = DecompositionGeneratorBlockEntity.DATA_SLOT_COUNT;" in menu
           and "public static final int DATA_SLOT_COUNT = 14;" in be)
    expect("D23 分页 / 展示槽 / 翻页按钮整套已删除（界面不再有页码与按钮通道）",
           not any(token in menu_code for token in ("PagedContainer", "PagedSlot", "DisplaySlot",
                                                    "isLocalIndexUsable", "clickMenuButton"))
           and "handleInventoryButtonClick" not in screen
           and not re.search(r"\b(?:PAGE_SIZE|PAGE_COUNT|PAGE_ROWS)\b", menu_code))

    # ---- D24 只收可降解物 ----
    expect("D24 库存 isItemValid 走「输入格 + 降解表」双判",
           "DecompositionGeneratorSpec.isInputSlot(slot) && DecompositionTable.isDecomposable(stack)" in be)
    expect("D25 quickMoveStack 对不可降解物给提示且不移动",
           "DecompositionTable.isDecomposable(slotStack)" in menu
           and "decomposition_generator.reject" in menu)
    expect("D26 状态行四态已随三行废文本整段删除（drawStatusTexts 与四个状态键都不许回来）",
           status_line_removed(screen))

    # ---- D27 六面推送 ----
    push_body = body_after(be, r"private int pushToNeighbours\(\)", 2400)
    expect("D27 六面都推送（Direction.values()）", "Direction.values()" in push_body)
    expect("D28 推送受配置限速", "Config.decompositionGeneratorPushPerTick" in push_body)
    expect("D29 推送前不加载区块（isLoaded 守卫）", "level.isLoaded(target)" in push_body)
    expect("D30 推送先 simulate 再真收（不丢电）",
           "receiveEnergy(offer, true)" in push_body and "receiveEnergy(taken, false)" in push_body)
    expect("D56 每 tick 只推一轮（pushToNeighbours 只被调用一次）",
           count(r"pushToNeighbours\(\)", be) == 2, f"count={count(r'pushToNeighbours()', be)}")
    expect("D57 六面分摊同一预算（budget 递减 ⇒ 总量不超上限）", "budget -= moved;" in push_body)
    expect("D31 推送与网络抽取共用同一份内部扣电",
           "energyStorage.extractInternal(" in push_body
           and "energyStorage.extractInternal(" in body_after(be, r"public long extractForNetwork\(", 800))

    # ---- D32 网络 provider 读数 ----
    expect("D32 节点实现 EnergyProvider", "implements EnergyProvider" in node)
    expect("D33 provider 只报本块存量 / 容量（不得自报集群 / ×N）",
           "return blockEntity == null ? 0L : blockEntity.getEnergyStored();" in node
           and "return blockEntity == null ? 0L : blockEntity.getEnergyCapacity();" in node)
    expect("D34 网络抽取走本机缓存扣电", "blockEntity.extractForNetwork(amount)" in node)
    expect("D35 节点从不反向从网络抽电（不得出现 network...extract）",
           not re.search(r"network\s*\.getComponent\([^)]*\)\s*\.extract", node))
    expect("D36 发电机节点能耗恒 0",
           "return 0L;" in body_after(node, r"public long getEnergyUsage\(\)", 400))

    # ---- D37 数据包加载 ----
    expect("D37 加载器继承 SimpleJsonResourceReloadListener", "extends SimpleJsonResourceReloadListener" in loader)
    expect("D38 目录名固定 decomposition", 'DIRECTORY = "decomposition"' in loader)
    expect("D39 挂到 AddReloadListenerEvent（game bus）且真的 addListener",
           "AddReloadListenerEvent" in loader and "event.addListener(INSTANCE);" in loader)
    expect("D40 逐条容错：坏 id / 坏数值只跳过或裁剪自己，绝不抛异常",
           "ResourceLocation.tryParse" in table
           and table.count("getOptional(id).orElse(null)") >= 2
           and "LOGGER.warn" in table
           and not re.search(r"throw new", table))
    expect("D41 支持 replace / remove / mod_loaded",
           '"replace"' in table and '"remove"' in table and '"mod_loaded"' in table)
    expect("D42 内置命名空间最先应用（排序键）",
           "BUILTIN_NAMESPACE.equals(rl.getNamespace()) ? 0 : 1" in table)
    expect("D43 tag 懒匹配（不在重载时展开）", "stack.is(binding.tag())" in table)
    expect("D44 重载版本号用于重算无效槽位", "revision++" in table and "DecompositionTable.revision()" in be)
    expect("D45 非空输入格计数进数据槽 11（只数 0..15 输入区）",
           "case 11 -> occupied;" in be
           and "for (int i = 0; i < DecompositionGeneratorSpec.INPUT_COUNT; i++)" in be
           and "invalid_slots" not in be)

    # ---- D46 拆方块结算 ----
    expect("D46 拆方块走 BlockContentReleaser", "BlockContentReleaser.release(" in block)
    expect("D47 结算 20 格库存 + 6 格升级容器（不结算就吞掉玩家的升级）",
           "collectHandler(generator.getInventory(), out)" in block
           and upgrade_accessor is not None
           and ("collectHandler(generator.%s(), out)" % upgrade_accessor) in block
           and "getDisplayHandler" not in block)

    # ---- D48 界面不读本地 Config ----
    expect("D48 界面不读 Config（速率 / 电量全部由服务端数据槽下发）",
           "Config." not in screen_code and "Config.decompositionGeneratorPushPerTick" in be)
    expect("D49 界面背景走单张贴图 blit（不再用 fill 拼面板 / 槽框）",
           "textures/gui/decomposition_generator.png" in screen
           and screen_code.count("guiGraphics.blit(TEXTURE,") == 1
           and ".fill(" not in screen_code)
    expect("D50 方块 active 状态由「正在降解」驱动", "currentSlot >= 0" in body_after(be, r"calculateActive\(\)", 200))
    expect("D51 副产物槽只出不进（mayPlace 恒 false）",
           "public boolean mayPlace(final ItemStack stack) {\n            return false;" in menu)

    # ---- D52 表 / 数值层 ----
    expect("D52 单次发电上限与耗时上限有硬夹量",
           "MAX_TIME" in profile and "MAX_ENERGY" in profile)
    expect("D53 电容量来自 Config 键", "Config.decompositionGeneratorCapacity" in be)
    lang_usage: dict[str, set[int]] = {}
    for source_key in ("be", "block", "menu", "screen", "node"):
        for lang_key, counts in lang_key_arg_counts(sources[source_key]).items():
            lang_usage.setdefault(lang_key, set()).update(counts)
    inconsistent = {key: sorted(value) for key, value in lang_usage.items() if len(value) > 1}
    expect("D55 同一语言键不会以不同参数个数使用（否则 String.format 抛异常）",
           not inconsistent, str(inconsistent))
    zh_args = lang_placeholder_counts(sources["lang_zh"])
    en_args = lang_placeholder_counts(sources["lang_en"])
    missing_lang = sorted(key for key in lang_usage
                          if key not in zh_args or key not in en_args)
    expect("D55b 界面用到的每个语言键在 zh_cn / en_us 都存在（缺失 = 界面只显示原始 key）",
           bool(zh_args) and bool(en_args) and not missing_lang, str(missing_lang[:6]))
    arity_mismatch = {
        key: (sorted(lang_usage[key]), zh_args.get(key), en_args.get(key))
        for key in lang_usage
        if key in zh_args and key in en_args
        and (sorted(lang_usage[key]) != [zh_args[key]] or zh_args[key] != en_args[key])
    }
    expect("D55c 每个语言键的 %s 个数 == 调用处实参个数 == zh/en 两份一致"
           "（不一致 ⇒ String.format 抛 MissingFormatArgumentException，界面打开即崩）",
           not arity_mismatch, str(arity_mismatch))
    if not COLLECT_ONLY:
        for lang_key in sorted(lang_usage):
            print(f"LANG_KEY {lang_key} args={sorted(lang_usage[lang_key])}")
    expect("D54 单次发电量夹到缓存容量（防「永远转不完」）",
           count(r"rewardFor\(profile\)", be) >= 2
           and "Math.min(profile.energy(), capacity)" in be)
    if not COLLECT_ONLY:
        print(f"SOURCE_ASSERTIONS {SOURCE_ASSERTIONS} failures={len(failures)}")
    return failures


# =====================================================================================
# 5. 反例（把源码改成错误写法，断言必须失败）
# =====================================================================================

COUNTEREXAMPLES: list[tuple[str, str, dict[str, str]]] = []


def counterexample(name: str, filename: str, old: str, new: str) -> None:
    COUNTEREXAMPLES.append((name, filename, {"old": old, "new": new}))


counterexample("CE01 允许从外部灌电（receiveEnergy 不再恒 0）", "be",
               "public int receiveEnergy(final int toReceive, final boolean simulate) {\n            return 0;",
               "public int receiveEnergy(final int toReceive, final boolean simulate) {\n            return toReceive;")
counterexample("CE02 canReceive 改成 true", "be",
               "public boolean canReceive() {\n            return false;",
               "public boolean canReceive() {\n            return true;")
counterexample("CE03 进度不落盘", "be",
               "tag.putInt(TAG_PROGRESS, progress);", "")
counterexample("CE04 当前槽不落盘", "be",
               "tag.putInt(TAG_CURRENT_SLOT, currentSlot);", "")
counterexample("CE05 缓存满时仍然推进（会白吃物品）", "be",
               "        // ③ 缓存装不下这一次的发电量 ⇒ 暂停（不推进、不消耗物品、不丢电）\n"
               "        if ((long) getEnergyCapacity() - getEnergyStored() < progressEnergy) {",
               "        // ③ 缓存装不下这一次的发电量 ⇒ 暂停（不推进、不消耗物品、不丢电）\n"
               "        if (false) {")
counterexample("CE06 一次完成发两次电", "be",
               "        energyStorage.addInternal(reward);",
               "        energyStorage.addInternal(reward);\n        energyStorage.addInternal(reward);")
counterexample("CE07 允许木头进槽（isItemValid 恒 true）", "be",
               "return DecompositionGeneratorSpec.isInputSlot(slot) && DecompositionTable.isDecomposable(stack);",
               "return true;")
counterexample("CE08 菜单槽数少一格（客户端/服务端槽位对不上会断线）", "menu",
               "public static final int PLAYER_SLOT_COUNT = PLAYER_MAIN_SLOT_COUNT + PLAYER_HOTBAR_SLOT_COUNT;",
               "public static final int PLAYER_SLOT_COUNT = PLAYER_MAIN_SLOT_COUNT + PLAYER_HOTBAR_SLOT_COUNT - 1;")
counterexample("CE09 菜单里按 256 循环 addSlot（历史断线根因）", "menu",
               "        // ---- ③ 玩家背包 + 快捷栏",
               "        for (int dump = 0; dump < 256; dump++) {\n"
               "            addSlot(new Slot(new net.minecraft.world.SimpleContainer(1), 0, 0, 0));\n        }\n"
               "        // ---- ③ 玩家背包 + 快捷栏")
counterexample("CE10 客户端镜像不再与服务端同判据（shift 点击预测分叉）", "menu",
               "machineHandler = this.clientInventoryMirror;",
               "machineHandler = new ItemStackHandler(DecompositionGeneratorSpec.INVENTORY_SIZE);")
counterexample("CE11 推送不加载区块守卫（会触发区块加载）", "be",
               "if (!level.isLoaded(target)) {\n                continue;\n            }", "")
counterexample("CE12 推送不按配置限速", "be",
               "final int limit = Math.max(1, Config.decompositionGeneratorPushPerTick);",
               "final int limit = Integer.MAX_VALUE;")
counterexample("CE13 provider 报集群/全局（重复计数）", "node",
               "return blockEntity == null ? 0L : blockEntity.getEnergyStored();",
               "return blockEntity == null ? 0L : blockEntity.getEnergyStored() * 2L;")
counterexample("CE14 节点反向从网络抽电", "node",
               "        if (blockEntity == null) {\n            return;\n        }\n"
               "        blockEntity.serverTick();",
               "        if (blockEntity == null) {\n            return;\n        }\n"
               "        if (network != null) {\n"
               "            network.getComponent(com.refinedmods.refinedstorage.api.network.energy."
               "EnergyNetworkComponent.class).extract(10L);\n        }\n"
               "        blockEntity.serverTick();")
counterexample("CE15 界面读本地 Config（专服会显示错速率）", "screen",
               "    protected void init() {", "    protected void init() {\n        final int leak = Config.foo;")
counterexample("CE16 界面又用 fill 拼面板（丑面板回潮）", "screen",
               "        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0,",
               "        guiGraphics.fill(leftPos, topPos, leftPos + 1, topPos + 1, 0);\n"
               "        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0,")
counterexample("CE17 拆方块不结算 6 格升级容器（吞掉玩家的速度/堆叠升级）", "block",
               "                        BlockContentReleaser.collectHandler(generator.getUpgradeContainer(), out);\n", "")
counterexample("CE18 加载器不挂事件（数据包永远不加载）", "loader",
               "    @SubscribeEvent\n    public static void onAddReloadListeners(final AddReloadListenerEvent event) {\n"
               "        event.addListener(INSTANCE);\n    }",
               "    public static void onAddReloadListeners(final AddReloadListenerEvent event) {\n"
               "        // CE: 不注册\n    }")
counterexample("CE22 六面推送不扣预算（单 tick 超上限）", "be",
               "            budget -= moved;", "            // CE22：不扣预算")
counterexample("CE21 同一语言键用错参数个数（界面打开即崩）", "screen",
               '"gui.rs_create_compat.decomposition_generator.progress_short",',
               '"gui.rs_create_compat.decomposition_generator.progress",')
counterexample("CE20 单次发电量不夹容量（超大条目永远转不完）", "be",
               "final int reward = Math.min(profile.energy(), capacity);",
               "final int reward = profile.energy();")
counterexample("CE19 压缩块退回原料（干草块 → 9 个小麦 = 无限电）", "builtin",
               '{ "item": "minecraft:hay_block", "time": 180, "energy": 450, '
               '"result": { "item": "minecraft:bone_meal", "count": 9 } }',
               '{ "item": "minecraft:hay_block", "time": 180, "energy": 450, '
               '"result": { "item": "minecraft:wheat", "count": 9 } }')
counterexample("CE23 内置表混进枯萎类（死灌木）", "builtin",
               '"item": "minecraft:kelp",', '"item": "minecraft:dead_bush",')
counterexample("CE24 木棍当成可降解输入（木头发电）", "builtin",
               '"item": "minecraft:kelp",', '"item": "minecraft:stick",')
counterexample("CE25 产物 chance 写超 1.0（概率越界）", "builtin",
               '"chance": 0.25 }', '"chance": 1.25 }')
counterexample("CE26 结算不写缓存（吃了植物却不发电）", "be",
               "        energyStorage.addInternal(reward);\n", "")


def run_counterexamples(sources: dict[str, str], vanilla: set[str] | None) -> int:
    """每个反例：把指定文件替换成错误版本，审计必须报出失败。返回被正确识别的个数。"""
    global COLLECT_ONLY, PENDING_FAILURES
    passed = 0
    for name, filename, patch in COUNTEREXAMPLES:
        mutated = dict(sources)
        original = mutated[filename]
        if patch["old"] not in original:
            print(f"COUNTEREXAMPLE_BROKEN {name}：源码里找不到待替换片段（断言锚点过期）")
            continue
        mutated[filename] = original.replace(patch["old"], patch["new"], 1)
        COLLECT_ONLY = True
        PENDING_FAILURES = []
        try:
            audit_data(mutated, vanilla)
            # audit_sources 自己维护 failures 列表并 return（不走 check()），必须显式合并
            failures = list(PENDING_FAILURES) + audit_sources(mutated)
        finally:
            COLLECT_ONLY = False
        if failures:
            passed += 1
        else:
            print(f"COUNTEREXAMPLE_MISSED {name}：把源码改错了但审计仍然全绿（断言无效）")
    return passed


# =====================================================================================
# main
# =====================================================================================

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mc-jar", default=None)
    parser.add_argument("--no-vanilla-check", action="store_true")
    args = parser.parse_args()

    sources = load_sources()
    for key, path in FILES.items():
        check(f"F-{key} 文件存在", os.path.exists(os.path.join(ROOT, path)) and len(sources[key]) > 0,
              path)

    vanilla = None
    if not args.no_vanilla_check:
        jar = args.mc_jar or auto_mc_jar()
        vanilla = vanilla_item_ids(jar)
        if vanilla:
            print(f"VANILLA_IDS_OK source={jar} count={len(vanilla)}")

    audit_data(sources, vanilla)
    audit_merge_model()
    audit_machine_model()
    failures = audit_sources(sources)
    check("D00 源码层断言（62 槽/只出/落盘/结算三判/推送/provider/数据包/贴图界面）", not failures,
          "；".join(failures[:8]))

    counterexamples_passed = run_counterexamples(sources, vanilla)

    failed = [item for item in CHECKS if not item[1]]
    for name, ok, detail in CHECKS:
        if not ok:
            print(f"FAIL {name} {detail}")
    print(f"COUNTEREXAMPLES {counterexamples_passed}/{len(COUNTEREXAMPLES)}")
    if failed:
        print(f"SELFCHECK FAILED ({len(CHECKS)} checks, {len(failed)} failed)")
        return 1
    print(f"SELFCHECK OK ({len(CHECKS)} checks, {counterexamples_passed} counterexamples)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
