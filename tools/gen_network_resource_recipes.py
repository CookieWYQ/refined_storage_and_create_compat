#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 87 轮：给「网络资源接收器 / 网络资源请求器」补合成配方 + 配方解锁成就。

【为什么必须补】两台机器在第 67 轮就注册了方块 / 方块实体 / 菜单 / 语言键，第 86 轮接了配置 UI，
但**一直没有合成配方**：`tools/verify_recipes.py` 的「本模组注册物必须有配方」会判负，
玩家在生存模式里做不出来（用户实测发现）。

【材料档次怎么定的 —— 逐条读工程里已上线的配方，不凭空想】
  * `collection_cache.json`（网络接口 + 容器 的样板）：
      machine_casing 居中，四角 quartz_enriched_iron，上下是 construction_core / destruction_core，
      一侧 hopper。→ 本轮的「接收器」直接照这个档次。
  * `quantity_keeper.json`：brass_casing 当机身、箱子 / 漏斗当「容器」侧、comparator 当「红石 / 控制」侧。
  * `intermediate_cache.json`：machine_casing + quartz_enriched_iron + 铜制零件（本工程中档机器）。
  * `network_receiver_brass.json`（RS 自家 network_receiver 的改版）：
      「远程投递」用 `c:ender_pearls`。→ 本轮的「请求器」用它。

【两台机器的分工（docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md §1.2）】
  * 接收器 = 网络 → **朝向容器**（导出）：construction_core（RS 的 exporter 用它）+ hopper（朝向出口）；
  * 请求器 = 网络 → **远处命名目标**（抽取并投递，红石脉冲触发）：
      destruction_core（RS 的 importer 用它）+ `c:ender_pearls`（远程投递）+ comparator（红石脉冲控制）。

【配方解锁 —— 工程里此前没有先例，这里按原版 datagen 的位置新开一层】
  已经查过：`src/main/resources/data/rs_create_compat/advancement/` 下 36 条全是**自定义玩法成就**
  （`minecraft:inventory_changed` + 自定义 criterion），`tools/selfcheck_advancements.py` 用
  「36 条不多不少 + 与生成器逐字节一致」把它们钉死；全工程没有任何 `minecraft:recipe_unlocked`。
  因此解锁成就放在 **`advancement/recipes/<配方名>.json`**（原版 `/advancement/recipes/**` 的位置）：
    ① 结构照抄原版（parent `minecraft:recipes/root` + `has_the_recipe` + `has_<材料>` + rewards.recipes）；
    ② `selfcheck_advancements.py` 只扫 `advancement/` **顶层** `*.json`，子目录不会撑破「36 条」清单；
    ③ `gen_advancements.py` 的幂等清理同样只删顶层 `*.json`，不会误删这一层。

产出（幂等：重跑只覆盖，不残留）：
  src/main/resources/data/rs_create_compat/recipe/network_resource_receiver.json
  src/main/resources/data/rs_create_compat/recipe/network_resource_requester.json
  src/main/resources/data/rs_create_compat/advancement/recipes/network_resource_receiver.json
  src/main/resources/data/rs_create_compat/advancement/recipes/network_resource_requester.json

用法：python tools/gen_network_resource_recipes.py
"""

import io
import json
import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gradle_cache as gc  # noqa: E402  （必须在 sys.path 调整之后导入）

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "rs_create_compat"
RECIPE_DIR = os.path.join(ROOT, "src", "main", "resources", "data", MODID, "recipe")
ADV_DIR = os.path.join(ROOT, "src", "main", "resources", "data", MODID, "advancement")
UNLOCK_DIR = os.path.join(ADV_DIR, "recipes")
MOD_JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat",
                        "RS_Create_Compat.java")

# 只写这两个配方；`unlock_on` = 解锁成就里 `inventory_changed` 的触发材料（必须真的能拿到）
RECIPES = [
    {
        "name": "network_resource_receiver",
        "pattern": ["QCQ", "BMB", "QHQ"],
        "key": {
            "Q": {"item": "refinedstorage:quartz_enriched_iron"},
            "C": {"item": "refinedstorage:construction_core"},
            "B": {"item": "create:brass_casing"},
            "M": {"item": "refinedstorage:machine_casing"},
            "H": {"item": "minecraft:hopper"},
        },
        "unlock_on": "refinedstorage:construction_core",
    },
    {
        "name": "network_resource_requester",
        "pattern": ["EDE", "BMB", "QRQ"],
        "key": {
            "E": {"tag": "c:ender_pearls"},
            "D": {"item": "refinedstorage:destruction_core"},
            "B": {"item": "create:brass_casing"},
            "M": {"item": "refinedstorage:machine_casing"},
            "Q": {"item": "refinedstorage:quartz_enriched_iron"},
            "R": {"item": "minecraft:comparator"},
        },
        "unlock_on": "refinedstorage:destruction_core",
    },
]


# --------------------------------------------------------------------------- 判据（写盘前自检）
def ids_from_jar(path, namespace):
    """从 mod jar 里取该命名空间的物品 id：`models/item/<id>.json` ∪ lang 的 item./block. 键。

    为什么两路都取：有的 id 只有方块模型（`block.refinedstorage.machine_casing`），
    有的只有物品模型；单看一路会误判「不存在」。只读 zip 中央目录，不解压。
    """
    found = set()
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        prefix = "assets/%s/models/item/" % namespace
        for name in names:
            if name.startswith(prefix) and name.endswith(".json"):
                found.add("%s:%s" % (namespace, name[len(prefix):-len(".json")]))
        lang_name = "assets/%s/lang/en_us.json" % namespace
        if lang_name in names:
            lang = json.loads(jar.read(lang_name).decode("utf-8"))
            for head in ("item.", "block."):
                tag = head + namespace + "."
                for key in lang:
                    if key.startswith(tag):
                        found.add("%s:%s" % (namespace, key[len(tag):]))
    return found


def mod_registry_ids():
    """本模组注册名（DeferredRegister 名 = 注册名）。"""
    with io.open(MOD_JAVA, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    return {"%s:%s" % (MODID, name)
            for name in re.findall(r'BLOCKS\.register\(\s*"([^"]+)"', text)} | {
            "%s:%s" % (MODID, name)
            for name in re.findall(r'ITEMS\.register\(\s*"([^"]+)"', text)}


def seen_tags():
    """已经被证明能解析的标签：RS 自带配方 + 本工程既有（不含本次要写的两份）配方里出现过的。"""
    tags = set()
    sources = [os.path.join(ROOT, "local_src", "external", "RefinedStorage", "refinedstorage-common",
                            "src", "main", "resources", "data", "refinedstorage", "recipe")]
    for directory in sources:
        if os.path.isdir(directory):
            for name in os.listdir(directory):
                if name.endswith(".json"):
                    with io.open(os.path.join(directory, name), encoding="utf-8") as handle:
                        text = handle.read()
                    tags.update(re.findall(r'"tag"\s*:\s*"([^"]+)"', text))
    writing = {"%s.json" % spec["name"] for spec in RECIPES}
    for name in os.listdir(RECIPE_DIR):
        if name.endswith(".json") and name not in writing:
            with io.open(os.path.join(RECIPE_DIR, name), encoding="utf-8") as handle:
                text = handle.read()
            tags.update(re.findall(r'"tag"\s*:\s*"([^"]+)"', text))
    return tags


def verify_item_ids():
    """写盘前把每个引用到的 id / 标签验一遍；缺 jar 只 INFO 跳过（不阻断生成）。"""
    jars = {}
    for label, namespace, group, artifact in (
            ("RS", "refinedstorage", "com.refinedmods.refinedstorage", "refinedstorage-neoforge"),
            ("Create", "create", "com.simibubi.create", "create-1.21.1")):
        path, notes = gc.find_jar(group, artifact)
        for line in notes:
            print(line)
        if path is None:
            print("[INFO] 找不到 %s 的 jar ⇒ 跳过 %s 命名空间的 id 校验" % (label, namespace))
            jars[namespace] = None
        else:
            print("[INFO] %s jar = %s" % (label, path))
            jars[namespace] = ids_from_jar(path, namespace)
    vanilla, notes = gc.find_vanilla_client_jar()
    for line in notes:
        print(line)
    if vanilla is None:
        print("[INFO] 找不到原版 client jar ⇒ 跳过 minecraft 命名空间的 id 校验")
        jars["minecraft"] = None
    else:
        jars["minecraft"] = ids_from_jar(vanilla, "minecraft")
    known_mod = mod_registry_ids()
    tags = seen_tags()

    problems = []
    for spec in RECIPES:
        entries = list(spec["key"].values())
        entries.append({"item": "%s:%s" % (MODID, spec["name"])})
        for entry in entries:
            if "item" in entry:
                ident = entry["item"]
                if ident.startswith(MODID + ":"):
                    if ident not in known_mod:
                        problems.append("%s：本模组没有注册 %s" % (spec["name"], ident))
                    continue
                namespace = ident.split(":", 1)[0]
                pool = jars.get(namespace)
                if pool is None:
                    print("[INFO] 跳过校验（缺 jar）: %s" % ident)
                elif ident not in pool:
                    problems.append("%s：%s 在 %s 的 jar 里不存在" % (spec["name"], ident, namespace))
                else:
                    print("[CHECK] %-40s 存在" % ident)
            elif "tag" in entry:
                if entry["tag"] not in tags:
                    problems.append("%s：标签 %s 没在已上线的配方里出现过" % (spec["name"], entry["tag"]))
                else:
                    print("[CHECK] %-40s 标签已由既有配方证明" % entry["tag"])
    return problems


# --------------------------------------------------------------------------- 产出
def render_recipe(spec):
    return {
        "type": "minecraft:crafting_shaped",
        "pattern": spec["pattern"],
        "key": spec["key"],
        "result": {"id": "%s:%s" % (MODID, spec["name"])},
    }


def render_unlock(spec):
    """原版 datagen 形状的配方解锁成就（无 display ⇒ 不需要任何语言键）。"""
    recipe_id = "%s:%s" % (MODID, spec["name"])
    trigger = spec["unlock_on"]
    return {
        "parent": "minecraft:recipes/root",
        "criteria": {
            "has_%s" % trigger.split(":", 1)[1]: {
                "conditions": {"items": [{"items": trigger}]},
                "trigger": "minecraft:inventory_changed",
            },
            "has_the_recipe": {
                "conditions": {"recipe": recipe_id},
                "trigger": "minecraft:recipe_unlocked",
            },
        },
        "requirements": [["has_the_recipe", "has_%s" % trigger.split(":", 1)[1]]],
        "rewards": {"recipes": [recipe_id]},
    }


def write_json(path, data):
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    json.loads(text)  # 自校验：写盘前必须能被解析
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def main():
    problems = verify_item_ids()
    if problems:
        for line in problems:
            print("[FAIL] %s" % line)
        print("[result] 有 %d 个 id / 标签问题 ⇒ 拒绝写盘" % len(problems))
        return 1
    for spec in RECIPES:
        recipe_path = os.path.join(RECIPE_DIR, "%s.json" % spec["name"])
        write_json(recipe_path, render_recipe(spec))
        print("[OK ] %s" % os.path.relpath(recipe_path, ROOT))
        unlock_path = os.path.join(UNLOCK_DIR, "%s.json" % spec["name"])
        write_json(unlock_path, render_unlock(spec))
        print("[OK ] %s（解锁材料 %s）" % (os.path.relpath(unlock_path, ROOT), spec["unlock_on"]))
    print("[result] 完成：2 条配方 + 2 条配方解锁（未改动任何既有配方 / 成就）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
