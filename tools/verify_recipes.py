# -*- coding: utf-8 -*-
"""配方校验：① 每个 ingredient / 产物 id 真实存在；② 每个「该有配方」的物品确实有配方。

为什么必须做：写出引用不存在物品的配方 → 客户端 / 服务端**加载报错**（Registry 反序列化失败）。
本脚本把这件事拦在提交前。

id 真值来源（全部是**实际依赖**里的数据，不靠人工维护清单）：
  - 本模组  : `src/main/java/.../RS_Create_Compat.java` 里的 `ITEMS.register("x", ...)` /
              `BLOCKS.register("x", ...)`（DeferredRegister 名 = 注册名）+ 本模组 lang 键交叉核对。
  - RS      : `local_src/rs_assets/assets/refinedstorage/lang/en_us.json`（RS 2.0.0 官方 lang）。
  - Create  : `local_src/external/Create/src/generated/resources/assets/create/lang/en_us.json`。
  - 原版    : 优先读 Gradle 缓存里的 `minecraft_*_client.jar` 内 `assets/minecraft/lang/en_us.json`
              （最权威）；找不到则回退用 `local_src/neoforge_src/data/minecraft/recipe/**` 里出现过的 id。
  - 标签    : 允许集合 = RS 自己配方 + 原版配方 + 本模组既有配方里出现过的 `"tag"` 值
              （即「已被证明能解析」的标签，避免写出不存在的 tag）。

用法：
    python tools/verify_recipes.py
退出码 0 = 全部通过；非 0 = 有问题（逐条打印）。
"""
import json
import os
import re
import sys
import zipfile
import glob as globmod
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 缓存根统一跟随 GRADLE_USER_HOME（本机 = D:\gradle\caches，Gradle 真正在用的那个）。
# 以前这里写死 ~/.gradle/caches：两个根内容不一致时，本脚本会去另一个缓存里取证。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gradle_cache as gc  # noqa: E402  （必须在 sys.path 调整之后再导入）

MODID = "rs_create_compat"
RECIPE_DIR = os.path.join(ROOT, "src", "main", "resources", "data", MODID, "recipe")
MOD_JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat",
                        "RS_Create_Compat.java")
RS_LANG = os.path.join(ROOT, "local_src", "rs_assets", "assets", "refinedstorage", "lang", "en_us.json")
CREATE_LANG = os.path.join(ROOT, "local_src", "external", "Create", "src", "generated", "resources",
                           "assets", "create", "lang", "en_us.json")
RS_RECIPE_DIR = os.path.join(ROOT, "local_src", "external", "RefinedStorage", "refinedstorage-common",
                             "src", "main", "resources", "data", "refinedstorage", "recipe")
VANILLA_RECIPE_DIR = os.path.join(ROOT, "local_src", "neoforge_src", "data", "minecraft", "recipe")

problems = []
notes = []


def problem(msg):
    problems.append(msg)
    print("[FAIL] %s" % msg)


def info(msg):
    print("[info] %s" % msg)


def warn(msg):
    notes.append(msg)
    print("[WARN] %s" % msg)


# ---------------------------------------------------------------- id 真值来源

def ids_from_lang(path, namespace):
    """从 lang 键提取 id：`item.<ns>.a.b.help` -> {a, a.b, a.b.help}（前缀全收，够用于校验）。"""
    found = set()
    if not os.path.exists(path):
        warn("缺少参考文件（跳过）：%s" % os.path.relpath(path, ROOT))
        return found
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    for key in data:
        prefix = None
        for head in ("item.", "block."):
            if key.startswith(head + namespace + "."):
                prefix = head
                break
        if prefix is None:
            continue
        rest = key[len(prefix) + len(namespace) + 1:].split(".")
        for index in range(1, len(rest) + 1):
            found.add("%s:%s" % (namespace, ".".join(rest[:index])))
    return found


def ids_from_recipes(directory):
    """从配方 JSON 里收集出现过的 item / result id。"""
    found = set()
    if not os.path.isdir(directory):
        return found
    for path in globmod.glob(os.path.join(directory, "**", "*.json"), recursive=True):
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
        for match in re.finditer(r'"item"\s*:\s*"([^"]+)"', text):
            found.add(match.group(1))
        for match in re.finditer(r'"id"\s*:\s*"([^"]+)"', text):
            found.add(match.group(1))
    return found


def tags_from_recipes(directory):
    found = set()
    if not os.path.isdir(directory):
        return found
    for path in globmod.glob(os.path.join(directory, "**", "*.json"), recursive=True):
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
        for match in re.finditer(r'"tag"\s*:\s*"([^"]+)"', text):
            found.add(match.group(1))
    return found


def mod_registry_ids():
    """解析本模组注册名（DeferredRegister 名 = 注册名）。"""
    with open(MOD_JAVA, "r", encoding="utf-8") as handle:
        text = handle.read()
    ids = set()
    for match in re.finditer(r'\b(?:ITEMS|BLOCKS)\.register\(\s*"([^"]+)"', text):
        ids.add("%s:%s" % (MODID, match.group(1)))
    return ids


def vanilla_ids_from_client_jar():
    """从 Gradle 缓存的原版 client jar 里读 en_us.json（最权威的原版 id 来源）。

    缓存根由 tools/_gradle_cache.py 统一决定（跟随 GRADLE_USER_HOME），不再写死 ~/.gradle。
    """
    jar, notes = gc.find_vanilla_client_jar()
    for line in notes:
        info(line.strip())
    if jar is None:
        return set(), None
    try:
        with zipfile.ZipFile(jar) as archive:
            with archive.open("assets/minecraft/lang/en_us.json") as handle:
                data = json.loads(handle.read().decode("utf-8"))
    except Exception as exc:  # noqa: BLE001 - 读不到就走回退路径
        warn("读取原版 lang 失败（%s）：%s" % (os.path.basename(jar), exc))
        return set(), None
    ids = set()
    for key in data:
        for head in ("item.minecraft.", "block.minecraft."):
            if key.startswith(head):
                ids.add("minecraft:%s" % key[len(head):])
    return ids, os.path.basename(jar)


# ---------------------------------------------------------------- 主流程

def main():
    mod_ids = mod_registry_ids()
    info("本模组注册名（ITEMS/BLOCKS.register）：%d 个" % len(mod_ids))
    if not mod_ids:
        problem("没能从 RS_Create_Compat.java 解析出任何注册名，校验不可信")

    rs_ids = ids_from_lang(RS_LANG, "refinedstorage") | ids_from_recipes(RS_RECIPE_DIR)
    create_ids = ids_from_lang(CREATE_LANG, "create")
    create_ids |= {"create:%s" % i.split(":", 1)[1] for i in ids_from_recipes(
        os.path.join(ROOT, "local_src", "external", "Create", "src", "generated", "resources",
                     "data", "create", "recipe")) if i.startswith("create:")}
    vanilla_jar_ids, jar_name = vanilla_ids_from_client_jar()
    vanilla_ids = vanilla_jar_ids | {i for i in ids_from_recipes(VANILLA_RECIPE_DIR)
                                     if i.startswith("minecraft:")}
    if jar_name:
        info("原版 id 来源：%s（%d 个）+ 本地原版配方" % (jar_name, len(vanilla_jar_ids)))
    else:
        warn("未找到原版 client jar，原版 id 回退为「本地原版配方里出现过的 id」")

    known = mod_ids | rs_ids | create_ids | vanilla_ids
    info("可用 id 池：RS %d / Create %d / 原版 %d / 本模组 %d"
         % (len(rs_ids), len(create_ids), len(vanilla_ids), len(mod_ids)))

    allowed_tags = (tags_from_recipes(RS_RECIPE_DIR) | tags_from_recipes(VANILLA_RECIPE_DIR)
                    | tags_from_recipes(RECIPE_DIR))
    info("已证明可解析的标签：%s" % ", ".join(sorted(allowed_tags)))

    # ---- ② 覆盖检查：本模组每个 id 要么有配方，要么在 NO_RECIPE 白名单里（含理由）
    no_recipe = {
        "rs_create_compat:universal_storage_disk_creative":
            "创造专用（无限档），无配方——同 RS creative_storage_disk",
        "rs_create_compat:advanced_remote_terminal_charged":
            "与普通版同一物品的「满电副本」（RS createAtEnergyCapacity 仅用于创造模式取用），生存由充电器充能",
        "rs_create_compat:creative_advanced_remote_terminal":
            "创造专用（无视电量），无配方",
        "rs_create_compat:sequence_unit_pattern":
            "由「序列装配样板终端」在游戏内生成（终端产物），刻意不给合成表",
        "rs_create_compat:sequence_assembly_pattern":
            "由「序列装配样板终端」在游戏内生成（终端产物），刻意不给合成表",
    }
    recipe_files = sorted(globmod.glob(os.path.join(RECIPE_DIR, "*.json")))
    info("配方文件：%d 个" % len(recipe_files))

    produced = {}
    for path in recipe_files:
        name = os.path.basename(path)
        with open(path, "r", encoding="utf-8") as handle:
            try:
                data = json.load(handle)
            except json.JSONDecodeError as exc:
                problem("%s 不是合法 JSON：%s" % (name, exc))
                continue

        rtype = data.get("type")
        if rtype not in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
            problem("%s 类型不在校验范围（%s）" % (name, rtype))
            continue

        result = data.get("result", {}).get("id")
        if not result:
            problem("%s 缺少 result.id" % name)
            continue
        produced.setdefault(result, []).append(name)

        # 收集本文件的 ingredient（含标签）
        ingredients = []
        if rtype == "minecraft:crafting_shaped":
            pattern = data.get("pattern", [])
            key = data.get("key", {})
            if len(pattern) != 3 or any(len(row) != 3 for row in pattern):
                problem("%s 有序配方要求 3x3 网格，实际 %s" % (name, pattern))
            used = {ch for row in pattern for ch in row if ch != " "}
            missing = used - set(key)
            unused = set(key) - used
            if missing:
                problem("%s 的 key 缺少图案字符：%s" % (name, sorted(missing)))
            if unused:
                problem("%s 的 key 有多余字符（图案里没用到）：%s" % (name, sorted(unused)))
            for char in sorted(used):
                if char in key:
                    ingredients.append((char, key[char], name))
        else:
            for index, entry in enumerate(data.get("ingredients", [])):
                ingredients.append((str(index), entry, name))

        for slot, entry, owner in ingredients:
            if "item" in entry:
                if entry["item"] not in known:
                    problem("%s 槽 %s 引用了不存在的物品 id：%s" % (owner, slot, entry["item"]))
            elif "tag" in entry:
                if entry["tag"] not in allowed_tags:
                    problem("%s 槽 %s 引用了未验证的标签：%s" % (owner, slot, entry["tag"]))
            else:
                problem("%s 槽 %s 既不是 item 也不是 tag" % (owner, slot))

        if result not in known:
            problem("%s 的产物 id 不存在：%s" % (name, result))

    # ---- 覆盖：本模组注册物必须有配方（除白名单）
    uncovered = sorted(mod_ids - set(produced) - set(no_recipe))
    if uncovered:
        for item in uncovered:
            problem("本模组物品没有配方：%s" % item)
    info("已覆盖 %d 个本模组产物；白名单（判定无配方）%d 个；未覆盖 %d 个"
         % (len(set(produced) & mod_ids), len(no_recipe), len(uncovered)))

    # ---- 白名单反向核对：白名单里除「创造专用」外，不该同时被别人做出配方（避免理由过期）
    for item, reason in sorted(no_recipe.items()):
        if item in produced:
            problem("白名单 %s 已有配方（%s），理由已过期：%s"
                    % (item, produced[item], reason))
        elif item not in mod_ids:
            problem("白名单里的 %s 并不在本模组注册表里（可能已改名）" % item)

    print("=" * 60)
    if problems:
        print("[result] 校验失败：%d 个问题" % len(problems))
        return 1
    print("[result] 校验通过：id 全部存在，本模组注册物 100% 覆盖（未覆盖 0）")
    if notes:
        print("[result] 另有 %d 条警告（不影响结论）" % len(notes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
