# -*- coding: utf-8 -*-
"""round83 自检：显式机器表补齐 CBC / Create Addition + 空结果不缓存。

本轮只做两件小事：
    ① 往 RecipeTypeMachines 的「显式机器表」里补上机械动力：火炮（CBC 5.11.7）与
       Create Addition（1.7.2）的机器 —— 这两个模组的配方没有覆写 getToastSymbol()，
       所以显式表是它们唯一的机器来源（不加 JEI、不加反射）。
    ② 终端界面的 machineCache 不再缓存空列表（空结果由 RecipeTypeMachines 的 1 秒负缓存重试）。

本脚本钉死三件事：
    A. 「配方类型 → 机器 id」对照：写进显式表的每个机器 id，都能在对应 jar 的
       assets/*/blockstates/<id>.json（或 data/ 下的同名文件）里用 zipfile 查到；
       缺 jar 只 INFO 跳过并打印，不误判负。
    B. 空结果不缓存：终端 machinesFor 只 put 非空结果（没有 computeIfAbsent）；
       RecipeTypeMachines 的空结果只做 TTL + 代际负缓存、非空时 remove。
    C. 反例自证：把「空结果也缓存」的写法喂给同一批判据 ⇒ 必须判负；
       把不存在的方块 id 喂给存在性判据 ⇒ 必须判负。

可重复执行：python tools/selfcheck_round83_explicit_machine_table.py
     → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
"""
from __future__ import annotations

import glob
import io
import os
import re
import sys
import tempfile
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
MACHINES = os.path.join(PKG, "client", "tooltip", "RecipeTypeMachines.java")
SCREEN = os.path.join(PKG, "client", "screen", "SequencePatternTerminalScreen.java")

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def info(message):
    print("INFO %s" % message)


def section(title):
    print()
    print("=" * 100)
    print(title)
    print("=" * 100)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


# =====================================================================================
# Java 源码小工具
# =====================================================================================

def code_only(src):
    """剥掉注释（保留字符串字面量）：注释里提到 computeIfAbsent 不算代码。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def java_body(src, signature):
    """取以 signature 开头的方法 / 初始化块的花括号体（按括号配平，跳过字符串）。"""
    start = src.find(signature)
    if start < 0:
        return ""
    open_idx = src.find("{", start)
    if open_idx < 0:
        return ""
    depth = 0
    in_str = False
    escape = False
    for i in range(open_idx, len(src)):
        ch = src[i]
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return src[open_idx:i + 1]
    return ""


def call_args(text, open_idx):
    """text[open_idx] == '('，返回 (括号内文本, 右括号之后的下标)；跳过字符串。"""
    assert text[open_idx] == "("
    depth = 0
    in_str = False
    escape = False
    for i in range(open_idx, len(text)):
        ch = text[i]
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i + 1
    raise ValueError("括号不配平：%r" % text[open_idx:open_idx + 60])


def split_top_level(args):
    """按「顶层逗号」切分参数（跳过括号内 / 字符串内的逗号）。"""
    parts = []
    depth = 0
    in_str = False
    escape = False
    current = []
    for ch in args:
        if in_str:
            current.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            current.append(ch)
        elif ch in "([{":
            depth += 1
            current.append(ch)
        elif ch in ")]}":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


# =====================================================================================
# ① 解析显式机器表
# =====================================================================================

ALLBLOCKS = {
    "MILLSTONE": "millstone",
    "CRUSHING_WHEEL": "crushing_wheel",
    "MECHANICAL_PRESS": "mechanical_press",
    "MECHANICAL_SAW": "mechanical_saw",
    "MECHANICAL_MIXER": "mechanical_mixer",
    "BASIN": "basin",
    "ENCASED_FAN": "encased_fan",
    "DEPLOYER": "deployer",
    "DEPOT": "depot",
    "SPOUT": "spout",
    "ITEM_DRAIN": "item_drain",
    "MECHANICAL_CRAFTER": "mechanical_crafter",
    "BLAZE_BURNER": "blaze_burner",
}
BLOCKS = {
    "CRAFTING_TABLE": "crafting_table",
    "FURNACE": "furnace",
    "BLAST_FURNACE": "blast_furnace",
    "SMOKER": "smoker",
    "CAMPFIRE": "campfire",
    "STONECUTTER": "stonecutter",
    "SMITHING_TABLE": "smithing_table",
}
NAMESPACE_CONSTS = {
    "CBC_NAMESPACE": "createbigcannons",
    "CREATE_ADDITION_NAMESPACE": "createaddition",
}
CONST_KEYS = {"VANILLA_CRAFTING": "minecraft:crafting"}


def resolve_machine(arg):
    """把一个 machines(...) 实参解析成 'namespace:path'；解析不了返回 None。"""
    arg = arg.strip()
    match = re.fullmatch(r"AllBlocks\.([A-Z0-9_]+)\.get\(\)", arg)
    if match:
        path = ALLBLOCKS.get(match.group(1))
        return "create:%s" % path if path else None
    match = re.fullmatch(r"Blocks\.([A-Z0-9_]+)", arg)
    if match:
        path = BLOCKS.get(match.group(1))
        return "minecraft:%s" % path if path else None
    match = re.fullmatch(
        r'thirdPartyMachine\(\s*([A-Z0-9_]+)\s*,\s*"([^"]+)"\s*\)', arg)
    if match:
        namespace = NAMESPACE_CONSTS.get(match.group(1))
        return "%s:%s" % (namespace, match.group(2)) if namespace else None
    return None


def parse_explicit_table(src):
    """返回 (ordered list[(recipe_type, [machine_id])], problems)。"""
    body = java_body(src, "private static Map<String, List<ItemLike>> buildExplicitTable(")
    if not body:
        return [], ["找不到 buildExplicitTable 方法体"]
    rows = []
    problems = []
    index = 0
    while True:
        at = body.find("Map.entry(", index)
        if at < 0:
            break
        open_idx = body.find("(", at)
        args, end = call_args(body, open_idx)
        index = end
        parts = split_top_level(args)
        if len(parts) != 2:
            problems.append("Map.entry 参数个数不是 2：%r" % (parts,))
            continue
        key_text = parts[0]
        if key_text.startswith('"') and key_text.endswith('"'):
            recipe_type = key_text[1:-1]
        else:
            recipe_type = CONST_KEYS.get(key_text)
            if recipe_type is None:
                problems.append("不认识的配方类型常量：%s" % key_text)
                continue
        machines_at = parts[1].find("machines(")
        if machines_at < 0:
            problems.append("%s 的机器列表不是 machines(...)：%s" % (recipe_type, parts[1]))
            continue
        machine_args, _ = call_args(parts[1], parts[1].find("(", machines_at))
        machine_ids = []
        for arg in split_top_level(machine_args):
            machine_id = resolve_machine(arg)
            if machine_id is None:
                problems.append("%s 的机器实参无法解析：%r" % (recipe_type, arg))
            else:
                machine_ids.append(machine_id)
        rows.append((recipe_type, machine_ids))
    return rows, problems


# =====================================================================================
# ② jar 内方块存在性（zipfile，不解压）
# =====================================================================================

def find_jar(env_name, patterns, extra_dirs):
    override = os.environ.get(env_name)
    if override and os.path.isfile(override):
        return override
    # 只排除 sources / javadoc 分类器；不能用 "sources" in name —— MC 的
    # client-extra-aka-minecraft-resources.jar 里就含 "resources"（内含 "sources"）。
    classifier = re.compile(r"-(sources|javadoc)\.jar$", re.I)
    for pattern in patterns:
        hits = []
        for directory in extra_dirs:
            hits.extend(glob.glob(os.path.join(directory, pattern)))
        hits = [path for path in hits
                if os.path.isfile(path) and not classifier.search(os.path.basename(path))]
        if hits:
            return sorted(hits, key=os.path.getmtime)[-1]
    return None


def machine_evidence(jar, namespace, path):
    """在 jar 里找 assets/<ns>/blockstates/<path>.json 或 data/ 下的同名文件。"""
    blockstate = "assets/%s/blockstates/%s.json" % (namespace, path)
    loot = "data/%s/loot_table/blocks/%s.json" % (namespace, path)
    with zipfile.ZipFile(jar) as jar_file:
        names = set(jar_file.namelist())
        if blockstate in names:
            return blockstate
        if loot in names:
            return loot
        prefix = "data/%s/" % namespace
        suffix = "/%s.json" % path
        for name in sorted(names):
            if name.startswith(prefix) and name.endswith(suffix):
                return name
    return None


# =====================================================================================
# ③ 空结果不缓存（真实源码判据）
# =====================================================================================

def screen_empty_not_cached_ok(src):
    body = java_body(src, "private java.util.List<RecipeTypeMachines.Machine> machinesFor(")
    if not body:
        return False, "找不到 machinesFor 方法体"
    code = code_only(body)
    if "computeIfAbsent" in code:
        return False, "仍在用 computeIfAbsent（空列表会被永久缓存）"
    if "machineCache.get(recipeType)" not in code:
        return False, "没有先读缓存"
    if "if (!computed.isEmpty())" not in code:
        return False, "put 之前没有判空"
    put_at = code.find("machineCache.put(recipeType, computed);")
    guard_at = code.find("if (!computed.isEmpty())")
    if put_at < 0 or guard_at < 0 or put_at < guard_at:
        return False, "machineCache.put 不在空判之内"
    return True, ""


def negative_cache_ok(src):
    """第 84 轮起：空 / 非空共用一张 CACHE；空结果额外 1 秒 TTL + 两个代际。"""
    if "EMPTY_RETRY_NANOS" not in src:
        return False, "没有空结果 TTL 常量"
    if "JeiCatalystMachines.generation()" not in src:
        return False, "负缓存没有 JEI 代际"
    if "recipeEpoch()" not in src:
        return False, "负缓存没有客户端配方表代际"
    body = java_body(src, "public static List<Machine> forRecipeType(")
    if not body:
        return False, "找不到 forRecipeType 方法体"
    code = code_only(body)
    if "CACHE.put(" not in code or "new Cached(" not in code:
        return False, "结果没有进缓存（缺 CACHE.put / Cached）"
    if "cached.machines().isEmpty()" not in code:
        return False, "读路径没有区分空 / 非空结果"
    if "< EMPTY_RETRY_NANOS" not in code:
        return False, "空结果没有按 TTL 过期"
    if "cached.jeiGeneration() == jeiGeneration" not in code:
        return False, "缓存没有按 JEI 代际失效"
    if "cached.recipeManager() == recipeEpoch" not in code:
        return False, "缓存没有按客户端配方表代际失效"
    return True, ""


# =====================================================================================
# 期望对照：只列本轮确证的机器（id 均来自 jar 反汇编 / 方块注册名）
# =====================================================================================

CRAFTING = "minecraft:crafting_table"
CBC_CRAFTING = [
    "createbigcannons:munition_fuzing",
    "createbigcannons:cartridge_assembly",
    "createbigcannons:big_cartridge_filling",
    "createbigcannons:tracer_application",
    "createbigcannons:fuze_removal",
    "createbigcannons:tracer_removal",
]
CBC_DEPLOYER = [
    "createbigcannons:munition_fuzing_deployer",
    "createbigcannons:cartridge_assembly_deployer",
    "createbigcannons:big_cartridge_filling_deployer",
    "createbigcannons:tracer_application_deployer",
    "createbigcannons:autocannon_ammo_container_filling_deployer",
]
EXPECTED = {
    "createbigcannons:melting": ["createbigcannons:basin_foundry_lid", "create:basin"],
    "createaddition:charging": ["createaddition:tesla_coil"],
    "createaddition:rolling": ["createaddition:rolling_mill"],
    "createaddition:liquid_burning": ["create:blaze_burner"],
}
for recipe_type in CBC_CRAFTING:
    EXPECTED[recipe_type] = [CRAFTING]
for recipe_type in CBC_DEPLOYER:
    EXPECTED[recipe_type] = ["create:deployer", "create:depot"]

# CBC 5.11.7 的 CBCRecipeTypes 只有 12 个常量；不带 _deployer 的
# autocannon_ammo_container_filling 不存在（javap 已确证），所以显式表里也不该有它。
NONEXISTENT_TYPE = "createbigcannons:autocannon_ammo_container_filling"


def main():
    section("① 显式机器表：解析真实源码 + CBC / Create Addition 覆盖")
    machines_src = read(MACHINES)
    screen_src = read(SCREEN)

    rows, problems = parse_explicit_table(machines_src)
    check("① 显式机器表可解析（每个 Map.entry / machines(...) 都能解析成 namespace:path）",
          not problems, "; ".join(problems[:6]))
    table = dict(rows)
    check("① 显式机器表里没有重复的配方类型",
          len(table) == len(rows) and len(rows) > 0, "rows=%d unique=%d" % (len(rows), len(table)))

    missing = [recipe_type for recipe_type in EXPECTED if recipe_type not in table]
    check("① 覆盖要求的 CBC 12 个真实 recipe type + Create Addition 3 个分类",
          not missing, "缺少：%s" % missing)

    wrong = ["%s=%s（期望 %s）" % (recipe_type, table.get(recipe_type), expected)
             for recipe_type, expected in EXPECTED.items()
             if recipe_type in table and table[recipe_type] != expected]
    check("① 每个覆盖项的机器列表与字节码证据一致", not wrong, "; ".join(wrong[:6]))

    check("① 不写不存在的 %s（CBCRecipeTypes 只有 ..._DEPLOYER）" % NONEXISTENT_TYPE,
          NONEXISTENT_TYPE not in table)

    section("② 写入显式表的每个机器 id 必须在对应 jar 内真实存在（zipfile）")

    cbc_jar = find_jar(
        "RSCC_CBC_JAR",
        ["createbigcannons-5.11.7*.jar", "createbigcannons-*.jar"],
        [os.environ.get("TEMP", ""), os.path.join(ROOT, "run", "mods"),
         os.path.join(ROOT, "libs"),
         os.path.join("D:\\minecraft", ".minecraft", "versions", "*", "mods")])
    ca_jar = find_jar(
        "RSCC_CA_JAR",
        ["createaddition-1.7.2*.jar", "createaddition-*.jar"],
        [os.environ.get("TEMP", ""), os.path.join(ROOT, "run", "mods"),
         os.path.join(ROOT, "libs"),
         os.path.join("D:\\minecraft", ".minecraft", "versions", "*", "mods")])
    create_jar = find_jar(
        "RSCC_CREATE_JAR",
        [os.path.join("modules-2", "files-2.1", "com.simibubi.create",
                      "create-1.21.1", "*", "*", "create-*.jar")],
        [os.path.join("D:\\gradle", "caches")])
    mc_jar = find_jar(
        "RSCC_MC_JAR",
        ["*client-extra*.jar"],
        [os.path.join(ROOT, "build", "moddev", "artifacts")])

    jars = {
        "createbigcannons": cbc_jar,
        "createaddition": ca_jar,
        "create": create_jar,
        "minecraft": mc_jar,
    }
    for namespace, jar in jars.items():
        if jar:
            info("jar[%s] = %s" % (namespace, jar))
        else:
            info("jar[%s] 未找到：该命名空间的机器 id 跳过（不判负）" % namespace)

    checked = 0
    skipped = []
    unresolved = []
    for recipe_type, machine_ids in rows:
        for machine_id in machine_ids:
            namespace, _, path = machine_id.partition(":")
            jar = jars.get(namespace)
            if not jar:
                skipped.append(machine_id)
                continue
            evidence = machine_evidence(jar, namespace, path)
            checked += 1
            if evidence is None:
                unresolved.append("%s（%s 的机器）" % (machine_id, recipe_type))
    check("② 显式表里的每个机器 id 都在 jar 内找到 blockstates/data 证据（checked=%d）" % checked,
          not unresolved, "找不到：%s" % unresolved[:8])
    if skipped:
        info("以下 id 因缺 jar 跳过（%d 个）：%s" % (len(skipped), sorted(set(skipped))))

    # 反例自证：不存在的方块 id / 命名空间写错，都必须判负。
    with tempfile.NamedTemporaryFile(suffix=".jar", delete=False) as handle:
        fake_jar = handle.name
    try:
        with zipfile.ZipFile(fake_jar, "w") as jar_file:
            jar_file.writestr("assets/createbigcannons/blockstates/basin_foundry_lid.json", "{}")
        check("②-反例：真实存在的 id（basin_foundry_lid）在样本 jar 里判正",
              machine_evidence(fake_jar, "createbigcannons", "basin_foundry_lid") is not None)
        check("②-反例：不存在的 id 在样本 jar 里判负",
              machine_evidence(fake_jar, "createbigcannons", "not_a_real_block") is None)
        check("②-反例：命名空间写错（create 而不是 createbigcannons）判负",
              machine_evidence(fake_jar, "create", "basin_foundry_lid") is None)
    finally:
        try:
            os.unlink(fake_jar)
        except OSError:
            pass

    section("③ 空结果不缓存（源码级判据 + 反例）")

    ok, why = screen_empty_not_cached_ok(screen_src)
    check("③ 终端 machineCache 只缓存非空结果（没有 computeIfAbsent）", ok, why)
    ok, why = negative_cache_ok(machines_src)
    check("③ RecipeTypeMachines：空结果 = 1 秒 TTL + JEI 代际 + 客户端配方表代际", ok, why)

    bad_compute_if_absent = (
        "private java.util.List<RecipeTypeMachines.Machine> machinesFor(final String recipeType) {\n"
        "    return machineCache.computeIfAbsent(recipeType, RecipeTypeMachines::forRecipeType);\n"
        "}\n")
    bad_unconditional_put = (
        "private java.util.List<RecipeTypeMachines.Machine> machinesFor(final String recipeType) {\n"
        "    final java.util.List<RecipeTypeMachines.Machine> computed = "
        "RecipeTypeMachines.forRecipeType(recipeType);\n"
        "    machineCache.put(recipeType, computed);\n"
        "    return computed;\n"
        "}\n")
    check("③-反例：旧写法 computeIfAbsent（空列表也缓存）⇒ 判负",
          not screen_empty_not_cached_ok(bad_compute_if_absent)[0])
    check("③-反例：无条件 put（空列表也缓存）⇒ 判负",
          not screen_empty_not_cached_ok(bad_unconditional_put)[0])

    bad_negative_cache = (
        "static final long EMPTY_RETRY_NANOS = 1_000_000_000L;\n"
        "public static List<Machine> forRecipeType(final String id) {\n"
        "    JeiCatalystMachines.generation(); recipeEpoch();\n"
        "    CACHE.put(id, new Cached(List.of(), null, null, System.nanoTime()));\n"
        "    return List.of();\n"
        "}\n")
    check("③-反例：结果无条件进缓存、读路径不区分空/非空、没有 TTL ⇒ 判负",
          not negative_cache_ok(bad_negative_cache)[0])

    section("④ 摘要")
    print("显式表条目：%d 条（CBC %d 条 / Create Addition %d 条）" % (
        len(rows),
        sum(1 for recipe_type, _ in rows if recipe_type.startswith("createbigcannons:")),
        sum(1 for recipe_type, _ in rows if recipe_type.startswith("createaddition:"))))
    for recipe_type in EXPECTED:
        print("  %-62s -> %s" % (recipe_type, ", ".join(EXPECTED[recipe_type])))
    print()
    print("没有实机验证：本脚本只做源码 / 字节码 / jar 资源交叉验证；")
    print("tooltip / 终端里肉眼看到的机器列表必须由人在客户端确认。")

    if PROBLEMS:
        print("=" * 100)
        print("SELFCHECK FAILED：%d 项不通过" % len(PROBLEMS))
        for problem in PROBLEMS:
            print("  - %s" % problem)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
