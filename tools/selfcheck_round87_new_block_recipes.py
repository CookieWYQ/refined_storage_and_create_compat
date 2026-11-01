# -*- coding: utf-8 -*-
"""第 87 轮：网络资源接收器 / 请求器的**合成配方 + 配方解锁**防回归自检。

为什么必须做：两台机器在第 67 轮就注册完成（方块 / 方块实体 / 菜单 / 语言键），第 86 轮接了配置 UI，
但**没有任何合成配方** —— 编译、mixin、语言键全部 PASS，玩家却在生存模式里做不出来。
这类「注册了但做不出来」的漏项只有把「注册表 ↔ 配方」摆在一起比才看得见，所以本轮把它机检。

判据（任一 FAIL 即退出码 1）：
  ① **配方存在且格式合法**：两份 `recipe/*.json` 都是 `minecraft:crafting_shaped`、3x3、
     pattern 用到的字符与 key 一一对应（缺字符 / 多字符都判负）、`result.id` 就是那台机器、
     `count` 为 1；
  ② **配方里每一个物品 id 在对应模组的 jar 里真实存在**（`zipfile` 只读中央目录，不解压）：
     * `refinedstorage:*` → Gradle 缓存里 **RS 2.0.9** 的 jar（`tools/_gradle_cache.py` 按
       `gradle.properties` 的 pin 选版本，本机 = `D:\\gradle\\caches`）；
     * `create:*` → **Create 6.0.10** 的 jar；
     * `minecraft:*` → 原版 client jar；
     * `rs_create_compat:*` → 从 `RS_Create_Compat.java` 的 `BLOCKS/ITEMS.register` 解析；
     * `tag` → 必须已在**既有**配方（RS 自带 / 本工程本轮之前）里出现过（= 已被证明能解析）。
     **缺 jar 时只 INFO 跳过并打印**（不阻断，也不假装验过）；
  ③ **配方有解锁路径**：`advancement/recipes/<配方>.json` 存在且是原版形状
     （parent `minecraft:recipes/root` + `minecraft:recipe_unlocked` 指向本配方 +
     一条 `minecraft:inventory_changed` 触发项 + `rewards.recipes` 含本配方 + requirements 自洽）；
     **只靠 `has_the_recipe` 自触发的写法判负**（那是死锁：拿不到配方就永远解锁不了）；
  ④ **「注册方块 ↔ 有配方」差集**必须为空：差集逐条打印；刻意不给配方的方块只能进
     `BLOCK_NO_RECIPE`（附理由），否则判负。这一条就是防「以后又加方块忘了配方」的闸门；
  ⑤ **反例自证**：把「引用不存在的 id」「无解锁文件」「解锁指向别的配方 / 没有 rewards」
     「pattern 与 key 不一致」四种写法喂给**同一批判据**，必须判负；再对真实文件跑一遍必须 0 失败
     （证明判据有区分力，不是恒真 / 恒假）。

用法：python tools/selfcheck_round87_new_block_recipes.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

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

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gradle_cache as gc  # noqa: E402

MODID = "rs_create_compat"
RECIPE_DIR = os.path.join(ROOT, "src", "main", "resources", "data", MODID, "recipe")
ADV_DIR = os.path.join(ROOT, "src", "main", "resources", "data", MODID, "advancement")
UNLOCK_DIR = os.path.join(ADV_DIR, "recipes")
MOD_JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat",
                        "RS_Create_Compat.java")
RS_RECIPE_DIR = os.path.join(ROOT, "local_src", "external", "RefinedStorage", "refinedstorage-common",
                             "src", "main", "resources", "data", "refinedstorage", "recipe")
VANILLA_RECIPE_DIR = os.path.join(ROOT, "local_src", "neoforge_src", "data", "minecraft", "recipe")

# 本轮要保证「做得出 + 有解锁」的两台机器：{方块名: 解锁成就的 inventory_changed 触发材料}
NEW_RECIPES = {
    "network_resource_receiver": "refinedstorage:construction_core",
    "network_resource_requester": "refinedstorage:destruction_core",
}
# 刻意不给合成配方的方块（附理由）。新增条目必须先想清楚「为什么不做得出」。
BLOCK_NO_RECIPE = {}

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def info(message):
    print("[info] %s" % message)


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def load_json(path):
    try:
        return json.loads(read(path))
    except (IOError, OSError, ValueError):
        return None


# ======================================================================================
# id 真值（缺 jar ⇒ None ⇒ 该命名空间只 INFO 跳过，不假装验过）
# ======================================================================================
def ids_from_jar(path, namespace):
    """jar 里的物品 id：`assets/<ns>/models/item/<id>.json` ∪ lang 的 item./block. 键。"""
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
                found.update("%s:%s" % (namespace, key[len(tag):])
                             for key in lang if key.startswith(tag))
    return found


def mod_registry_ids():
    text = read(MOD_JAVA)
    names = (re.findall(r'BLOCKS\.register\(\s*"([^"]+)"', text)
             + re.findall(r'ITEMS\.register\(\s*"([^"]+)"', text))
    return {"%s:%s" % (MODID, name) for name in names}


def registered_blocks():
    """所有注册方块名（= BLOCKS.register 的字面量；`BLOCKS.register(modEventBus)` 不会被正则抓到）。"""
    return sorted(set(re.findall(r'BLOCKS\.register\(\s*"([^"]+)"', read(MOD_JAVA))))


def proven_tags(exclude):
    """已被证明能解析的标签：RS 自带配方 + 本工程既有配方（排除本轮新增的两份）。"""
    tags = set()
    for directory in (RS_RECIPE_DIR, VANILLA_RECIPE_DIR):
        if not os.path.isdir(directory):
            continue
        for name in os.listdir(directory):
            if name.endswith(".json"):
                tags.update(re.findall(r'"tag"\s*:\s*"([^"]+)"',
                                       read(os.path.join(directory, name))))
    if os.path.isdir(RECIPE_DIR):
        for name in os.listdir(RECIPE_DIR):
            if name.endswith(".json") and name[:-5] not in exclude:
                tags.update(re.findall(r'"tag"\s*:\s*"([^"]+)"',
                                       read(os.path.join(RECIPE_DIR, name))))
    return tags


def jar_pools():
    """{命名空间: id 集合 或 None}；同时打印「到底看了哪个 jar / 什么版本」。"""
    pools = {}
    for label, namespace, group, artifact in (
            ("RS", "refinedstorage", "com.refinedmods.refinedstorage", "refinedstorage-neoforge"),
            ("Create", "create", "com.simibubi.create", "create-1.21.1")):
        path, notes = gc.find_jar(group, artifact)
        for line in notes:
            if line.strip().startswith("com.") or "缓存" in line:
                info(line.strip())
        if path is None:
            info("[INFO] 缺 %s jar ⇒ 跳过 %s 的 id 校验" % (label, namespace))
            pools[namespace] = None
        else:
            info("%s jar = %s" % (label, path))
            pools[namespace] = ids_from_jar(path, namespace)
    vanilla, _notes = gc.find_vanilla_client_jar()
    if vanilla is None:
        info("[INFO] 缺原版 client jar ⇒ 跳过 minecraft 的 id 校验")
        pools["minecraft"] = None
    else:
        info("原版 jar = %s" % vanilla)
        pools["minecraft"] = ids_from_jar(vanilla, "minecraft")
    return pools


# ======================================================================================
# 判据（纯函数：真实文件与反例走同一份代码）
# ======================================================================================
def judge_recipe_structure(name, data):
    """有序配方结构判据：3x3、字符 ↔ key 自洽、产物 = 那台机器。"""
    problems = []
    if not isinstance(data, dict):
        return ["不是 JSON 对象"]
    if data.get("type") != "minecraft:crafting_shaped":
        problems.append("type 不是 minecraft:crafting_shaped：%s" % data.get("type"))
    pattern = data.get("pattern") or []
    if len(pattern) != 3 or any(len(row) != 3 for row in pattern):
        problems.append("pattern 不是 3x3：%s" % (pattern,))
    key = data.get("key") or {}
    used = {char for row in pattern for char in row if char != " "}
    missing = used - set(key)
    unused = set(key) - used
    if missing:
        problems.append("key 缺少图案字符：%s" % sorted(missing))
    if unused:
        problems.append("key 有多余字符：%s" % sorted(unused))
    result = data.get("result") or {}
    if result.get("id") != "%s:%s" % (MODID, name):
        problems.append("result.id 不是 %s:%s（实际 %s）" % (MODID, name, result.get("id")))
    if result.get("count", 1) != 1:
        problems.append("result.count 应省略或为 1（实际 %s）" % result.get("count"))
    return problems


def judge_recipe_ids(data, pools, tags, mod_ids):
    """物品 id / 标签存在性判据；某个命名空间缺 jar 时该命名空间只 INFO 跳过。"""
    problems = []
    checked = [0]
    skipped = [0]

    def judge_item(ident):
        if ident.startswith(MODID + ":"):
            if ident not in mod_ids:
                problems.append("本模组没有注册 %s" % ident)
            return
        namespace = ident.split(":", 1)[0]
        pool = pools.get(namespace)
        if pool is None:
            skipped[0] += 1
            return
        checked[0] += 1
        if ident not in pool:
            problems.append("%s 在 %s 的 jar 里不存在" % (ident, namespace))

    key = data.get("key") or {}
    for char in sorted(key):
        entry = key[char] or {}
        if "item" in entry:
            judge_item(entry["item"])
        elif "tag" in entry:
            if entry["tag"] not in tags:
                problems.append("标签 %s 没在任何既有配方里出现过（未被证明存在）" % entry["tag"])
        else:
            problems.append("槽 %s 既不是 item 也不是 tag" % char)
    result = (data.get("result") or {}).get("id")
    if result:
        judge_item(result)
    return problems, checked[0], skipped[0]


def judge_unlock_data(data, recipe_id):
    """配方解锁判据（data=None = 解锁文件不存在）。"""
    problems = []
    if data is None:
        return ["没有配方解锁成就：advancement/recipes/%s.json 不存在" % recipe_id.split(":", 1)[1]]
    if not isinstance(data, dict):
        return ["不是 JSON 对象"]
    if data.get("parent") != "minecraft:recipes/root":
        problems.append("parent 应为 minecraft:recipes/root（实际 %s）" % data.get("parent"))
    criteria = data.get("criteria") or {}
    if not criteria:
        problems.append("criteria 为空")
    unlocked = [name for name, spec in criteria.items()
                if isinstance(spec, dict) and spec.get("trigger") == "minecraft:recipe_unlocked"]
    if not unlocked:
        problems.append("没有 minecraft:recipe_unlocked 触发项")
    elif not any(((criteria[name].get("conditions") or {}).get("recipe") == recipe_id)
                 for name in unlocked):
        problems.append("recipe_unlocked 的 conditions.recipe 不是 %s" % recipe_id)
    if not any(isinstance(spec, dict) and spec.get("trigger") == "minecraft:inventory_changed"
               for spec in criteria.values()):
        problems.append("没有 minecraft:inventory_changed 触发项 ⇒ 只能靠「已有该配方」自触发 = 死锁")
    rewards = (data.get("rewards") or {}).get("recipes") or []
    if recipe_id not in rewards:
        problems.append("rewards.recipes 不含 %s（实际 %s）" % (recipe_id, rewards))
    requirements = data.get("requirements") or []
    if not requirements:
        problems.append("缺少 requirements")
    for group in requirements:
        for name in group:
            if name not in criteria:
                problems.append("requirements 引用了不存在的 criterion：%s" % name)
    if "display" in data:
        problems.append("解锁成就不该带 display（会进成就树 / 要补语言键）")
    return problems


def load_unlock(recipe_id):
    path = os.path.join(UNLOCK_DIR, "%s.json" % recipe_id.split(":", 1)[1])
    return load_json(path) if os.path.isfile(path) else None


# ======================================================================================
# 正式断言
# ======================================================================================
def section_recipes(pools, tags, mod_ids):
    print("\n--- ① 两份配方：存在 / 结构 / id 真实存在 ---")
    total_checked = 0
    total_skipped = 0
    for name in sorted(NEW_RECIPES):
        path = os.path.join(RECIPE_DIR, "%s.json" % name)
        exists = os.path.isfile(path)
        check("①-1 %s 的配方 JSON 存在" % name, exists, path)
        data = load_json(path) if exists else None
        check("①-2 %s 的配方能被 json.load 解析" % name, data is not None)
        if data is None:
            continue
        problems = judge_recipe_structure(name, data)
        check("①-3 %s 的配方格式合法（3x3 / 字符↔key / 产物）" % name, not problems,
              "；".join(problems))
        problems, checked, skipped = judge_recipe_ids(data, pools, tags, mod_ids)
        total_checked += checked
        total_skipped += skipped
        check("①-4 %s 的每个物品 id 在对应模组 jar 里真实存在" % name, not problems,
              "；".join(problems))
        info("%s：核对了 %d 个物品 id，跳过 %d 个（缺 jar）" % (name, checked, skipped))
    info("本轮共核对 %d 个物品 id / 跳过 %d 个" % (total_checked, total_skipped))


def section_unlock(pools, tags, mod_ids):
    print("\n--- ② 配方解锁路径 ---")
    for name in sorted(NEW_RECIPES):
        recipe_id = "%s:%s" % (MODID, name)
        path = os.path.join(UNLOCK_DIR, "%s.json" % name)
        check("②-1 %s 的解锁成就存在（%s）" % (name, os.path.relpath(path, ROOT).replace("\\", "/")),
              os.path.isfile(path), "缺文件 ⇒ 合成表里可能不显示")
        data = load_unlock(recipe_id)
        problems = judge_unlock_data(data, recipe_id)
        check("②-2 %s 的解锁成就形状合法且指向本配方" % name, not problems, "；".join(problems))
        if data is not None:
            trigger = NEW_RECIPES[name]
            got = [items
                   for spec in (data.get("criteria") or {}).values()
                   if isinstance(spec, dict) and spec.get("trigger") == "minecraft:inventory_changed"
                   for entry in ((spec.get("conditions") or {}).get("items") or [])
                   for items in [entry.get("items")]]
            check("②-3 %s 的解锁触发材料是 %s" % (name, trigger), trigger in got,
                  "实际 %s" % (got,))
            problems, _c, _s = judge_recipe_ids(
                {"key": {"X": {"item": trigger}},
                 "result": {"id": recipe_id}}, pools, tags, mod_ids)
            check("②-4 %s 的解锁触发材料 id 真实存在" % name, not problems, "；".join(problems))
        # 解锁不能落在顶层：selfcheck_advancements 对顶层 *.json 是「一条不多一条不少」的硬钉口径。
        # 注意（2026-10-24 更新）：这 5 台/件新东西**后来补了「造出来」的顶层成就**，
        # 所以顶层出现同名文件是正常的 —— 这里要守的不再是「顶层不能有文件」，
        # 而是「顶层的那个文件必须是成就，不能是配方解锁」（把 recipe_unlocked / rewards.recipes
        # 塞进顶层会同时撑大成就清单、又让配方解锁走错机制）。
        top = os.path.join(ADV_DIR, "%s.json" % name)
        top_is_unlock = False
        if os.path.isfile(top):
            top_data = load_json(top) or {}
            top_is_unlock = any(
                isinstance(spec, dict) and spec.get("trigger") == "minecraft:recipe_unlocked"
                for spec in (top_data.get("criteria") or {}).values()
            ) or bool((top_data.get("rewards") or {}).get("recipes"))
        check("②-5 %s 的**配方解锁**不在 advancement 顶层（顶层若有同名文件，必须是「造出它」的成就）" % name,
              not top_is_unlock, top)


def section_diff():
    print("\n--- ③ 「注册方块 ↔ 有配方」差集 ---")
    blocks = registered_blocks()
    produced = {}
    for file_name in sorted(os.listdir(RECIPE_DIR)):
        if not file_name.endswith(".json"):
            continue
        data = load_json(os.path.join(RECIPE_DIR, file_name)) or {}
        result = (data.get("result") or {}).get("id") or ""
        if result.startswith(MODID + ":"):
            produced.setdefault(result.split(":", 1)[1], []).append(file_name)
    missing = []
    for block in blocks:
        recipes = produced.get(block)
        if recipes:
            print("  [ok  ] %-32s ← %s" % (block, ", ".join(recipes)))
        elif block in BLOCK_NO_RECIPE:
            print("  [白名单] %-32s 刻意无配方：%s" % (block, BLOCK_NO_RECIPE[block]))
        else:
            missing.append(block)
            print("  [MISS] %-32s 注册了方块但没有任何配方" % block)
    for block in BLOCK_NO_RECIPE:
        if block not in blocks:
            missing.append("%s（白名单里的方块已不在注册表）" % block)
    info("注册方块 %d 个；其中有配方 %d 个；差集 %d 个"
         % (len(blocks), len([b for b in blocks if produced.get(b)]), len(missing)))
    check("③-1 每个注册方块都有配方（差集为空）", not missing, "缺配方：%s" % missing)
    for name in NEW_RECIPES:
        check("③-2 %s 出现在「注册方块」清单里" % name, name in blocks, "不在 %s" % (blocks,))
        check("③-3 %s 出现在「有配方」清单里" % name, bool(produced.get(name)))


def section_counterexamples(pools, tags, mod_ids):
    print("\n--- ④ 反例自证（同一批判据必须判负） ---")
    good = load_json(os.path.join(RECIPE_DIR, "network_resource_receiver.json"))

    # 反例 1：引用不存在的 id（拼错一个字母 / 多打后缀）
    bad = json.loads(json.dumps(good))
    bad["key"]["Q"] = {"item": "refinedstorage:quartz_enriched_iron_" + "typo"}
    problems, _c, _s = judge_recipe_ids(bad, pools, tags, mod_ids)
    check("④-1 反例·引用不存在的 id ⇒ 判负", bool(problems), "；".join(problems))

    # 反例 2：无解锁文件
    problems = judge_unlock_data(None, "%s:network_resource_receiver" % MODID)
    check("④-2 反例·没有解锁成就 ⇒ 判负", bool(problems), "；".join(problems))

    # 反例 3：解锁指向别的配方 / 没有 rewards / 只有自触发（死锁）
    unlock = load_unlock("%s:network_resource_receiver" % MODID)
    if isinstance(unlock, dict):
        wrong_target = json.loads(json.dumps(unlock))
        for spec in wrong_target.get("criteria", {}).values():
            if spec.get("trigger") == "minecraft:recipe_unlocked":
                spec["conditions"]["recipe"] = "%s:network_resource_requester" % MODID
        check("④-3 反例·解锁指向别的配方 ⇒ 判负",
              bool(judge_unlock_data(wrong_target, "%s:network_resource_receiver" % MODID)))
        no_rewards = json.loads(json.dumps(unlock))
        no_rewards["rewards"] = {"recipes": []}
        check("④-4 反例·rewards.recipes 缺失 ⇒ 判负",
              bool(judge_unlock_data(no_rewards, "%s:network_resource_receiver" % MODID)))
        deadlock = json.loads(json.dumps(unlock))
        deadlock["criteria"].pop("has_construction_core", None)
        deadlock["requirements"] = [["has_the_recipe"]]
        check("④-5 反例·只靠 has_the_recipe 自触发（死锁）⇒ 判负",
              bool(judge_unlock_data(deadlock, "%s:network_resource_receiver" % MODID)))
    else:
        check("④-3/4/5 反例前置（真实解锁文件可读）", False, "解锁文件读不到，反例无法构造")

    # 反例 4：pattern 与 key 不一致
    broken = json.loads(json.dumps(good))
    broken["pattern"] = ["QZQ", "BMB", "QHQ"]  # Z 不在 key 里
    probe = judge_recipe_structure("network_resource_receiver", broken)
    check("④-6 反例·pattern 有 key 里没有的字符 ⇒ 判负", bool(probe), "；".join(probe))

    # 反向自证：同一批判据对真实文件 0 失败
    real_problems = judge_recipe_structure("network_resource_receiver", good)
    real_problems += judge_recipe_ids(good, pools, tags, mod_ids)[0]
    real_unlock = load_unlock("%s:network_resource_receiver" % MODID)
    real_problems += judge_unlock_data(real_unlock, "%s:network_resource_receiver" % MODID)
    check("④-7 反向自证·同一批判据对真实文件 0 失败（判据有区分力）",
          not real_problems, "；".join(real_problems))


def main():
    print("=== 第 87 轮自检：新方块（网络资源接收器 / 请求器）的配方 + 解锁 ===")
    mod_ids = mod_registry_ids()
    pools = jar_pools()
    tags = proven_tags(exclude=set(NEW_RECIPES))
    info("本模组注册 id：%d 个；已证明的标签：%s" % (len(mod_ids), ", ".join(sorted(tags))))
    section_recipes(pools, tags, mod_ids)
    section_unlock(pools, tags, mod_ids)
    section_diff()
    section_counterexamples(pools, tags, mod_ids)
    print("")
    print("断言总数 %d，失败 %d" % (CHECKS[0], len(FAILURES)))
    for failure in FAILURES:
        print("  FAIL %s" % failure)
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
