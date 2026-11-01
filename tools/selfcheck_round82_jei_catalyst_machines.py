# -*- coding: utf-8 -*-
"""round82 自检：**「配方类型的机器列表」读取 JEI 催化剂** + **空结果不永久缓存**。

用户实机反馈（要点）：
    「createbigcannons / createaddition 的配方类型，机器列表一台机器都没有。」

已查明的两条根因（本脚本对每一条都留可复跑判据）：
    A. 机器反查只有两条来源：显式表 + 配方自带的 getToastSymbol()。CBC / Create Addition
       的配方没有覆写 getToastSymbol() ⇒ 返回默认工作台 ⇒ 对非 crafting 类型被当成「无信息」丢掉；
       显式表里又没有它们的 id ⇒ 这些配方类型的机器列表必然为空。
    B. 终端界面的 machineCache 用 computeIfAbsent 把**空列表**也缓存了；这张缓存只随客户端世界
       失效 ⇒ JEI 就绪 / 资源重载后没有任何入口再查一次，用户看到的就是「永远空」。

本脚本钉死：
    ① JEI 类型只出现在「运行时探测 + 反射/隔离类」里：主路径（RecipeTypeMachines、终端界面、
       以及所有调用 RecipeTypeMachines.forRecipeType 的界面）不出现 import mezz.jei / JEI 类型；
       隔离类 JeiCatalystMachines 里 JEI 类名只以字符串出现，且只做只读查询。
    ② 空结果不永久缓存：终端 machineCache 只 put 非空结果；RecipeTypeMachines 对空结果只做
       1 秒 TTL + JEI 代际 + 客户端配方表代际 的负缓存。
    ③ 合并顺序：显式表 → getToastSymbol → JEI 催化剂（→ 命名约定回退），且按 Item 去重。
    ④ 反例自证：把「直接 import JEI」「空结果缓存」「JEI 抢先」的写法喂给同一批判据 ⇒ 必须判负。
    ⑤ CBC / Create Addition 的「配方类型 → 应能查到哪些机器」对照表（覆盖工程里出现的 12 个
       CBC 配方类型 + 3 个 Create Addition 配方类型）；有真实 mod jar 时再用 javap / 字节码核对。

可重复执行：python tools/selfcheck_round82_jei_catalyst_machines.py
     → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
"""
from __future__ import annotations

import glob
import io
import os
import re
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
MACHINES = os.path.join(PKG, "client", "tooltip", "RecipeTypeMachines.java")
JEI_SOURCE = os.path.join(PKG, "client", "tooltip", "JeiCatalystMachines.java")
SCREEN = os.path.join(PKG, "client", "screen", "SequencePatternTerminalScreen.java")
ICONS = os.path.join(PKG, "client", "tooltip", "RecipeTypeIcons.java")
BINDING = os.path.join(PKG, "client", "screen", "ChamberBindingConfigScreen.java")
SUMMARY = os.path.join(PKG, "client", "screen", "ChamberUnitsSummaryScreen.java")
LANG_ZH = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                       "lang", "zh_cn.json")
JAVAP = r"D:\java21\bin\javap.exe"

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def skip(name, detail=""):
    print("SKIP %s%s" % (name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 100)
    print(title)
    print("=" * 100)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(src):
    """剥掉注释（保留字符串字面量）：注释里提到 mezz.jei 不算依赖。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def strip_strings(src):
    """把字符串字面量换成空串：隔离类里 JEI 类名只允许出现在字符串里。"""
    return re.sub(r'"(?:[^"\\]|\\.)*"', '""', src)


def java_body(src, signature):
    """取以 signature 开头的方法的花括号体（含花括号，按括号配平）。"""
    start = src.find(signature)
    if start < 0:
        return ""
    open_idx = src.find("{", start)
    if open_idx < 0:
        return ""
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_idx:i + 1]
    return ""


# =====================================================================================
# ① 依赖安全判据（真实源码 / 反例共用）
# =====================================================================================

JEI_IMPORT_RE = re.compile(r"^\s*import\s+mezz\.jei\b", re.M)
JEI_FQN_RE = re.compile(r"\bmezz\.jei\.[A-Za-z0-9_.$]+")
# 只列 JEI 的**专有**简单名；不要列 RecipeType（原版 net.minecraft...RecipeType 也叫这个）。
JEI_SIMPLE_RE = re.compile(
    r"\b(?:IModPlugin|JeiPlugin|IRecipeCategory|IRecipeManager|IRecipeCatalystLookup|"
    r"IJeiRuntime|IRecipeCatalystRegistration|IRecipeRegistration|ITypedIngredient)\b")


def jei_direct_refs(src):
    """主路径源码里的 JEI 直接引用列表 [(行号, 命中)]；注释已剥、字符串保留。"""
    hits = []
    for idx, line in enumerate(code_only(src).splitlines(), 1):
        if JEI_IMPORT_RE.search(line):
            hits.append((idx, "import mezz.jei"))
        for match in JEI_FQN_RE.finditer(line):
            hits.append((idx, match.group(0)))
        for match in JEI_SIMPLE_RE.finditer(line):
            hits.append((idx, match.group(0)))
    return hits


def isolated_class_ok(src):
    """隔离类判据：没有 import；字符串以外没有 JEI 类型；有探测 + 反射 + LinkageError 兜底。"""
    if JEI_IMPORT_RE.search(code_only(src)):
        return False, "出现 import mezz.jei"
    code = strip_strings(code_only(src))
    if JEI_FQN_RE.search(code):
        return False, "字符串以外出现 mezz.jei.*"
    if JEI_SIMPLE_RE.search(code):
        return False, "字符串以外出现 JEI 专有类型名"
    for token in ("Class.forName", "getMethod", "ModList", "LinkageError",
                  "InvocationTargetException"):
        if token not in code:
            return False, "缺少隔离/只读实现：%s" % token
    # includeHidden / getItemStack 是反射方法名，真实代码里以字符串传给 getMethod，故查原文。
    for token in ("includeHidden", "getItemStack"):
        if token not in src:
            return False, "缺少隔离/只读实现：%s" % token
    # 只读：绝不改 JEI 注册（在剥掉注释/字符串后的真实代码里查）
    for forbidden in ("addRecipeCatalyst", "hideRecipes", "unhideRecipes",
                      "hideRecipeCategory", "unhideRecipeCategory", "addRecipes("):
        if forbidden in code:
            return False, "出现写 JEI 注册的调用：%s" % forbidden
    return True, ""


def negative_cache_ok(src):
    """RecipeTypeMachines：空结果 1 秒 TTL + JEI 代际 + 配方表代际。

    第 84 轮起「只有空结果负缓存」升级为「空 / 非空共用一张 CACHE」：
    非空结果按两个代际长期有效，空结果额外受 1 秒 TTL 约束（选择器每帧查询因此是 O(1)）。
    判据随之改为「结果确实进缓存 + 读路径区分空/非空 + 空结果按 TTL 过期」，语义比原来更强。
    """
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


def screen_empty_not_cached_ok(src):
    """终端 machineCache：只缓存非空结果（没有 computeIfAbsent / 没有无条件 put）。"""
    body = java_body(src, "private java.util.List<RecipeTypeMachines.Machine> machinesFor(")
    if not body:
        return False, "找不到 machinesFor 方法体"
    # 剥注释：本轮修复的注释里会引用旧写法 computeIfAbsent，不能把注释当代码判负。
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


def merge_order_ok(src):
    """合并顺序：显式表 → getToastSymbol → JEI 催化剂。"""
    body = java_body(src, "public static List<Machine> forRecipeType(")
    if not body:
        return False, "找不到 forRecipeType 方法体"
    explicit = body.find("explicitTable().getOrDefault(recipeTypeId")
    toast = body.find("symbolsFromRecipes(recipeTypeId)")
    jei = body.find("JeiCatalystMachines.catalystsFor(recipeTypeId)")
    if min(explicit, toast, jei) < 0:
        return False, "缺少某一条来源：explicit=%d toast=%d jei=%d" % (explicit, toast, jei)
    if not (explicit < toast < jei):
        return False, "顺序不是 显式表 → toast → JEI：explicit=%d toast=%d jei=%d" % (
            explicit, toast, jei)
    return True, ""


def dedupe_ok(src):
    """去重：三个来源都走 addMachine(..., seen, ...)，且 seen.add 真实存在。"""
    body = java_body(src, "public static List<Machine> forRecipeType(")
    if not body:
        return False, "找不到 forRecipeType 方法体"
    if "addMachine(machines, seen, new ItemStack(machine))" not in body:
        return False, "显式表没有走 addMachine(seen)"
    if "addMachine(machines, seen, symbol)" not in body:
        return False, "toast symbol 没有走 addMachine(seen)"
    if "addMachine(machines, seen, catalyst)" not in body:
        return False, "JEI 催化剂没有走 addMachine(seen)"
    add = java_body(src, "private static void addMachine(")
    if "seen.add(stack.getItem())" not in add:
        return False, "addMachine 没有按 Item 去重"
    return True, ""


def symbols_accept_crafting_recipe(src):
    """CBC 的 6 个 CustomRecipe 没有 JEI 分类：必须承认「crafting recipe 的默认工作台」是机器。"""
    body = java_body(src, "private static List<ItemStack> symbolsFromRecipes(")
    if "instanceof CraftingRecipe" not in body:
        return False, "没有识别 CraftingRecipe"
    if "!craftingRecipe && symbol.is(DEFAULT_SYMBOL)" not in body:
        return False, "默认工作台仍被无条件丢弃"
    return True, ""


def jei_catalysts_ok(src):
    """JEI 查询链：getRecipeType → createRecipeCatalystLookup → includeHidden → getItemStack。"""
    tokens = (
        "getRecipeType",
        "createRecipeCatalystLookup",
        "includeHidden",
        "getItemStack",
        "getJeiRuntime",
        "CACHE.put",
        "EMPTY.put",
        "EMPTY_RETRY_NANOS",
    )
    return all(token in src for token in tokens)


# =====================================================================================
# ① 真实源码：主路径不得出现 JEI 直接引用
# =====================================================================================
section("① 依赖安全：JEI 类型只在运行时探测 + 反射/隔离类里出现")

machines_src = read(MACHINES)
jei_src = read(JEI_SOURCE)
screen_src = read(SCREEN)

# 先证明判据本身会判负（反例自证），再对真实源码判正 —— 避免「判据是橡皮图章」。
DIRECT_IMPORT_COUNTEREXAMPLE = (
    "package bad;\n"
    "import mezz.jei.api.recipe.RecipeType;\n"
    "public class Bad { private RecipeType<?> type; }\n")
DIRECT_FQN_COUNTEREXAMPLE = (
    "package bad;\n"
    "public class Bad { Object o = mezz.jei.common.Internal.class; }\n")
check("①-反例：直接 import mezz.jei 的写法会被判负",
      bool(jei_direct_refs(DIRECT_IMPORT_COUNTEREXAMPLE)))
check("①-反例：字符串以外直接写 mezz.jei.* FQN 的写法会被判负",
      bool(jei_direct_refs(DIRECT_FQN_COUNTEREXAMPLE)))
check("①-反例：注释里提到 mezz.jei 不算依赖（不误报）",
      not jei_direct_refs("// 说明：这里以前 import mezz.jei.api.recipe.RecipeType;\nclass Ok {}\n"))

# 主路径 = 机器反查 + 终端 + 所有调用 RecipeTypeMachines 的界面。
# 隔离类 JeiCatalystMachines 单独用 isolated_class_ok 判：它允许把 JEI 类名写成字符串常量，
# 但绝不允许 import / 字符串以外的 JEI 类型引用。
MAIN_PATH = [MACHINES, SCREEN, ICONS, BINDING, SUMMARY]
main_hits = []
for path in MAIN_PATH:
    for line, hit in jei_direct_refs(read(path)):
        main_hits.append("%s:%s %s" % (os.path.basename(path), line, hit))
check("① 主路径（机器反查 + 终端 + 所有调用者）没有 JEI 直接引用/导入", not main_hits,
      "; ".join(main_hits[:8]))

ok, why = isolated_class_ok(jei_src)
check("① 隔离类 JeiCatalystMachines：没有 import、字符串以外没有 JEI 类型", ok, why)
check("① 隔离类里有 ModList 运行时探测（没装 JEI 连 Class.forName 都不执行）",
      "ModList" in jei_src and "ModList.get()" in jei_src and "isLoaded(MOD_ID)" in jei_src)
check("① 隔离类里 JEI 类名只以字符串出现（Class.forName 的四个入口）",
      all(token in jei_src for token in (
          '"mezz.jei.common.Internal"',
          '"mezz.jei.api.recipe.IRecipeManager"',
          '"mezz.jei.api.recipe.IRecipeCatalystLookup"',
          '"mezz.jei.api.recipe.RecipeType"')))
check("① 隔离类把 ReflectiveOperationException | RuntimeException | LinkageError 全部接住（含 NoClassDefFoundError）",
      "LinkageError" in jei_src and "InvocationTargetException" in jei_src)
check("① 隔离类只读：没有 addRecipeCatalyst / hideRecipes 等写 JEI 注册的调用",
      not any(t in strip_strings(code_only(jei_src)) for t in (
          "addRecipeCatalyst", "hideRecipes", "unhideRecipes",
          "hideRecipeCategory", "unhideRecipeCategory")))
check("①-反例：把 JEI 直接 import 的写法喂给隔离类判据 ⇒ 判负",
      not isolated_class_ok(DIRECT_IMPORT_COUNTEREXAMPLE)[0])

# =====================================================================================
# ② 空结果不永久缓存
# =====================================================================================
section("② 空结果不永久缓存：终端只缓存非空；provider 用 1 秒 TTL + 两个代际兜底")

ok, why = screen_empty_not_cached_ok(screen_src)
check("② 终端 machineCache 只缓存非空结果（没有 computeIfAbsent）", ok, why)
ok, why = negative_cache_ok(machines_src)
check("② RecipeTypeMachines：空结果 = 1 秒 TTL + JEI 代际 + 客户端配方表代际", ok, why)

BAD_COMPUTE_IF_ABSENT = (
    "private java.util.List<RecipeTypeMachines.Machine> machinesFor(final String recipeType) {\n"
    "    return machineCache.computeIfAbsent(recipeType, RecipeTypeMachines::forRecipeType);\n"
    "}\n")
BAD_UNCONDITIONAL_PUT = (
    "private java.util.List<RecipeTypeMachines.Machine> machinesFor(final String recipeType) {\n"
    "    final java.util.List<RecipeTypeMachines.Machine> computed = RecipeTypeMachines.forRecipeType(recipeType);\n"
    "    machineCache.put(recipeType, computed);\n"
    "    return computed;\n"
    "}\n")
check("②-反例：旧写法 computeIfAbsent ⇒ 判负",
      not screen_empty_not_cached_ok(BAD_COMPUTE_IF_ABSENT)[0])
check("②-反例：无条件 put（空列表也进缓存）⇒ 判负",
      not screen_empty_not_cached_ok(BAD_UNCONDITIONAL_PUT)[0])

BAD_NEGATIVE_CACHE = (
    "static final long EMPTY_RETRY_NANOS = 1_000_000_000L;\n"
    "public static List<Machine> forRecipeType(final String id) {\n"
    "    JeiCatalystMachines.generation(); recipeEpoch();\n"
    "    CACHE.put(id, new Cached(List.of(), null, null, System.nanoTime()));\n"
    "    return List.of();\n"
    "}\n")
check("②-反例：结果无条件进缓存、读路径不区分空/非空、没有 TTL ⇒ 判负",
      not negative_cache_ok(BAD_NEGATIVE_CACHE)[0])

# =====================================================================================
# ③ 合并顺序 + 去重
# =====================================================================================
section("③ 合并顺序（显式表 → getToastSymbol → JEI 催化剂）与按物品去重")

ok, why = merge_order_ok(machines_src)
check("③ 三条来源的调用顺序正确（JEI 最后）", ok, why)
ok, why = dedupe_ok(machines_src)
check("③ 三个来源都经过 addMachine(seen)，且按 Item 去重", ok, why)
check("③ CBC 的 CustomRecipe 默认工作台被承认（不是「无信息」）",
      symbols_accept_crafting_recipe(machines_src))

REORDERED = machines_src.replace(
    "for (final ItemLike machine : explicitTable().getOrDefault(recipeTypeId, List.of())) {",
    "for (final ItemStack early : JeiCatalystMachines.catalystsFor(recipeTypeId)) {\n"
    "            if (early.isEmpty()) { continue; }\n"
    "        }\n"
    "        for (final ItemLike machine : explicitTable().getOrDefault(recipeTypeId, List.of())) {",
    1)
check("③-反例：把 JEI 催化剂挪到显式表之前 ⇒ 顺序判据判负",
      not merge_order_ok(REORDERED)[0])

NO_DEDUPE = machines_src.replace(" || !seen.add(stack.getItem())", "", 1)
check("③-反例：删掉 seen.add ⇒ 去重判据判负", not dedupe_ok(NO_DEDUPE)[0])

check("③ JEI 查询链完整（getRecipeType / createRecipeCatalystLookup / includeHidden / getItemStack）",
      jei_catalysts_ok(jei_src))
check("③ JEI 缓存：非空结果按代际缓存 + 空结果短 TTL 负缓存",
      "CACHE.put" in jei_src and "EMPTY.put" in jei_src and "EMPTY_RETRY_NANOS" in jei_src)

# =====================================================================================
# ④ JEI API 的 javap 证据（对真实 jar）
# =====================================================================================
section("④ JEI API 的 javap 证据（对真实 jar 核对类名 / 方法签名）")

JEI_API_EXPECT = {
    "mezz.jei.common.Internal": ["getJeiRuntime", "mezz.jei.api.runtime.IJeiRuntime"],
    "mezz.jei.api.recipe.IRecipeManager": [
        "createRecipeCatalystLookup", "mezz.jei.api.recipe.RecipeType",
        "getRecipeType", "net.minecraft.resources.ResourceLocation"],
    "mezz.jei.api.recipe.IRecipeCatalystLookup": [
        "includeHidden", "getItemStack", "java.util.stream.Stream",
        "net.minecraft.world.item.ItemStack"],
}


def natural_key(text):
    return [int(part) if part.isdigit() else part for part in re.split(r"(\d+)", text)]


def find_jei_jars():
    jars = []
    override = os.environ.get("RSCC_JEI_JAR")
    if override and os.path.isfile(override):
        jars.append(override)
    roots = []
    if os.environ.get("GRADLE_USER_HOME"):
        roots.append(os.path.join(os.environ["GRADLE_USER_HOME"], "caches"))
    roots.append(os.path.join(os.path.expanduser("~"), ".gradle", "caches"))
    for cache in roots:
        base = os.path.join(cache, "modules-2", "files-2.1", "mezz.jei",
                            "jei-1.21.1-neoforge")
        for path in glob.glob(os.path.join(base, "*", "*", "*.jar")):
            if "sources" in os.path.basename(path).lower():
                continue
            if path not in jars:
                jars.append(path)
    return sorted(jars, key=natural_key)


jei_jars = find_jei_jars()
if not jei_jars:
    skip("④ javap：缓存里没有 JEI jar（设置 RSCC_JEI_JAR 可指定）")
else:
    for jar in jei_jars:
        print("      核对 jar: %s" % jar)
        for cls, tokens in JEI_API_EXPECT.items():
            try:
                out = subprocess.run([JAVAP, "-classpath", jar, cls],
                                     capture_output=True, text=True, encoding="utf-8",
                                     errors="replace", timeout=120).stdout or ""
            except Exception as exc:  # pragma: no cover
                out = ""
                print("      [警告] javap 失败：%r" % (exc,))
            check("④ %s 的签名含 %s" % (cls.rsplit(".", 1)[-1], ", ".join(tokens)),
                  all(token in out for token in tokens),
                  "javap 输出里缺少 %s" % [t for t in tokens if t not in out])

# =====================================================================================
# ⑤ CBC / Create Addition 对照表
# =====================================================================================
section("⑤ CBC / Create Addition「配方类型 → 应能查到的机器」对照表")

# 来源：
#   · CreateBigCannons 5.11.7 真实 jar：CBCJEI / CBCBlockRecipeCategory 的 addRecipeCatalyst
#   · CreateAddition 1.7.2 真实 jar：CreateAdditionJEI 的 catalyst(...)
#   · CBC 的 6 个 CustomRecipe 与 5 个 Deployer 配方（CBCRecipeTypes / CBCCommonEvents）没有 JEI 分类
CBC_CA_TABLE = [
    # --- JEI 自己建了分类的（key 与 recipe type uid 相同，本来源直接命中）---
    {"recipe_type": "createaddition:charging", "machines": ["createaddition:tesla_coil"],
     "source": "jei-catalyst",
     "evidence": "CreateAdditionJEI 1.7.2: CABlocks.TESLA_COIL -> charging category"},
    {"recipe_type": "createaddition:rolling", "machines": ["createaddition:rolling_mill"],
     "source": "jei-catalyst",
     "evidence": "CreateAdditionJEI 1.7.2: CABlocks.ROLLING_MILL -> rolling category"},
    {"recipe_type": "createaddition:liquid_burning", "machines": ["create:blaze_burner"],
     "source": "jei-catalyst",
     "evidence": "CreateAdditionJEI 1.7.2: AllBlocks.BLAZE_BURNER -> liquid_burning category"},
    {"recipe_type": "createbigcannons:melting", "machines": [
        "createbigcannons:basin_foundry_lid", "create:basin"],
     "source": "jei-catalyst",
     "evidence": "CBCJEI 5.11.7: meltingCatalysts = BASIN_FOUNDRY_LID + AllBlocks.BASIN"},
    # --- CBC 的 JEI 分类存在，但它的 recipe type 不在 BuiltInRegistries.RECIPE_TYPE ---
    {"recipe_type": "createbigcannons:cannon_casting", "machines": ["createbigcannons:casting_sand"],
     "source": "jei-catalyst-not-in-recipe-registry",
     "evidence": "CBCJEI 5.11.7: catalyst(CBCBlocks.CASTING_SAND) on BlockRecipeType.CANNON_CASTING"},
    {"recipe_type": "createbigcannons:built_up_heating", "machines": [
        "createbigcannons:cannon_builder", "create:encased_fan"],
     "source": "jei-catalyst-not-in-recipe-registry",
     "evidence": "CBCJEI 5.11.7: CANNON_BUILDER + ProcessingViaFanCategory.getFan(\"fan_blasting\")"},
    {"recipe_type": "createbigcannons:drill_boring", "machines": [
        "createbigcannons:cannon_drill", "create:mechanical_bearing",
        "create:windmill_bearing"],
     "source": "jei-catalyst-not-in-recipe-registry",
     "evidence": "CBCJEI 5.11.7: CANNON_DRILL + MECHANICAL_BEARING + WINDMILL_BEARING"},
    {"recipe_type": "createbigcannons:incomplete_cannon_blocks", "machines": [],
     "source": "jei-catalyst-not-in-recipe-registry",
     "evidence": "CBCJEI 5.11.7: builder(...).build(\"incomplete_cannon_blocks\", ...) 没有 .catalyst(...)"},
    # --- CBC 12 个已注册 recipe type 中的 6 个 CustomRecipe：工作台 ---
    {"recipe_type": "createbigcannons:munition_fuzing", "machines": ["minecraft:crafting_table"],
     "source": "crafting-recipe",
     "evidence": "CBC MunitionFuzingRecipe extends CustomRecipe（canCraftInDimensions>=2）"},
    {"recipe_type": "createbigcannons:cartridge_assembly", "machines": ["minecraft:crafting_table"],
     "source": "crafting-recipe",
     "evidence": "CBC CartridgeAssemblyRecipe extends CustomRecipe"},
    {"recipe_type": "createbigcannons:big_cartridge_filling", "machines": ["minecraft:crafting_table"],
     "source": "crafting-recipe",
     "evidence": "CBC BigCartridgeFillingRecipe extends CustomRecipe"},
    {"recipe_type": "createbigcannons:tracer_application", "machines": ["minecraft:crafting_table"],
     "source": "crafting-recipe",
     "evidence": "CBC TracerApplicationRecipe extends CustomRecipe"},
    {"recipe_type": "createbigcannons:fuze_removal", "machines": ["minecraft:crafting_table"],
     "source": "crafting-recipe",
     "evidence": "CBC FuzeRemovalRecipe extends CustomRecipe"},
    {"recipe_type": "createbigcannons:tracer_removal", "machines": ["minecraft:crafting_table"],
     "source": "crafting-recipe",
     "evidence": "CBC TracerRemovalRecipe extends CustomRecipe"},
    # --- CBC 5 个 Deployer 配方：没有 JEI 分类，配方由 Deployer 动态构造 ---
    {"recipe_type": "createbigcannons:munition_fuzing_deployer", "machines": ["create:deployer"],
     "source": "deployer-suffix",
     "evidence": "CBCCommonEvents#onAddDeployerRecipes: MunitionFuzingDeployerRecipe"},
    {"recipe_type": "createbigcannons:cartridge_assembly_deployer", "machines": ["create:deployer"],
     "source": "deployer-suffix",
     "evidence": "CBCCommonEvents#onAddDeployerRecipes: CartridgeAssemblyDeployerRecipe"},
    {"recipe_type": "createbigcannons:big_cartridge_filling_deployer", "machines": ["create:deployer"],
     "source": "deployer-suffix",
     "evidence": "CBCCommonEvents#onAddDeployerRecipes: BigCartridgeFillingDeployerRecipe"},
    {"recipe_type": "createbigcannons:tracer_application_deployer", "machines": ["create:deployer"],
     "source": "deployer-suffix",
     "evidence": "CBCCommonEvents#onAddDeployerRecipes: TracerApplicationDeployerRecipe"},
    {"recipe_type": "createbigcannons:autocannon_ammo_container_filling_deployer",
     "machines": ["create:deployer"], "source": "deployer-suffix",
     "evidence": "CBCCommonEvents#onAddDeployerRecipes: AutocannonAmmoContainerFillingDeployerRecipe"},
]


def table_ok(rows):
    """表判据：每行都要有配方类型、机器列表、来源、证据；来源只能是四种之一。"""
    allowed = {"jei-catalyst", "jei-catalyst-not-in-recipe-registry",
               "crafting-recipe", "deployer-suffix"}
    seen = set()
    for row in rows:
        rid = row.get("recipe_type")
        if not rid or rid in seen:
            return False, "配方类型为空或重复：%r" % (rid,)
        seen.add(rid)
        if not isinstance(row.get("machines"), list):
            return False, "%s 的 machines 不是列表" % rid
        if row.get("source") not in allowed:
            return False, "%s 的来源未知：%r" % (rid, row.get("source"))
        if not row.get("evidence"):
            return False, "%s 没有证据" % rid
    return True, ""


ok, why = table_ok(CBC_CA_TABLE)
check("⑤ 对照表结构完整（配方类型唯一 / 机器列表 / 来源 / 证据）", ok, why)

# CBC 5.11.7 真实 jar 里 CBCRecipeTypes 的 12 个常量（javap -p 可见；顺序与源码一致）。
# 注意：语言文件里还有一个 `createbigcannons.autocannon_ammo_container_filling` 键，
# 但 5.11.7 的 enum 里只有 `..._deployer`（jar 里也只有 ...DeployerRecipe.class）——
# 那是历史/额外键，不对应真实存在的配方类型，因此不作为覆盖断言（只在下面打印提示）。
CBC_ACTUAL_TYPES = [
    "createbigcannons:melting",
    "createbigcannons:munition_fuzing",
    "createbigcannons:cartridge_assembly",
    "createbigcannons:big_cartridge_filling",
    "createbigcannons:big_cartridge_filling_deployer",
    "createbigcannons:munition_fuzing_deployer",
    "createbigcannons:cartridge_assembly_deployer",
    "createbigcannons:tracer_application",
    "createbigcannons:tracer_application_deployer",
    "createbigcannons:autocannon_ammo_container_filling_deployer",
    "createbigcannons:fuze_removal",
    "createbigcannons:tracer_removal",
]
table_ids = {row["recipe_type"] for row in CBC_CA_TABLE}
missing_cbc = [rid for rid in CBC_ACTUAL_TYPES if rid not in table_ids]
check("⑤ 覆盖 CBC 5.11.7 的 12 个真实配方类型（CBCRecipeTypes 常量）",
      not missing_cbc, "缺少：%s" % missing_cbc)

# 语言文件里的 CBC 键（用于发现“工程里还写了哪些”），转换点分命名空间为冒号 id。
lang = read(LANG_ZH)
lang_cbc_keys = sorted(set(re.findall(
    r"machine\.rs_create_compat\.recipe_type\.(createbigcannons)\.([a-z0-9_]+)", lang)))
lang_cbc_ids = ["%s:%s" % (namespace, path) for namespace, path in lang_cbc_keys]
extra_lang_ids = [rid for rid in lang_cbc_ids if rid not in table_ids]
check("⑤ 语言文件里没有任何「表里没有的 CBC 真实类型」（额外历史键只提示、不判负）",
      all(rid not in CBC_ACTUAL_TYPES for rid in extra_lang_ids),
      "额外键：%s" % extra_lang_ids)
if extra_lang_ids:
    print("      [提示] zh_cn.json 里的历史/额外 CBC 键（5.11.7 已无对应 recipe type）：%s"
          % extra_lang_ids)

CA_IDS = ["createaddition:charging", "createaddition:rolling", "createaddition:liquid_burning"]
check("⑤ 覆盖 Create Addition 的 3 个 JEI 分类",
      all(rid in table_ids for rid in CA_IDS))

check("⑤ JEI 直接命中的 4 个类型（CA 3 + CBC melting）标记为 jei-catalyst",
      sum(1 for row in CBC_CA_TABLE if row["source"] == "jei-catalyst") == 4)
check("⑤ CBC 的 6 个 CustomRecipe 标记为 crafting-recipe（默认工作台是有效机器）",
      sum(1 for row in CBC_CA_TABLE if row["source"] == "crafting-recipe") == 6)
check("⑤ CBC 的 5 个 *_deployer 标记为 deployer-suffix（机械手）",
      sum(1 for row in CBC_CA_TABLE if row["source"] == "deployer-suffix") == 5)

BAD_TABLE_EMPTY_EVIDENCE = [dict(CBC_CA_TABLE[0], evidence="")]
check("⑤-反例：把某行证据清空 ⇒ 表判据判负",
      not table_ok(BAD_TABLE_EMPTY_EVIDENCE)[0])
BAD_TABLE_DUP = CBC_CA_TABLE + [dict(CBC_CA_TABLE[0])]
check("⑤-反例：重复配方类型 ⇒ 表判据判负", not table_ok(BAD_TABLE_DUP)[0])

# ---- 有真实 jar 时再用字节码核对（没有就 SKIP，不误判负）----
def find_mod_jar(env_name, patterns):
    override = os.environ.get(env_name)
    if override and os.path.isfile(override):
        return override
    candidates = []
    for directory in (os.path.join(ROOT, "run", "mods"), os.path.join(ROOT, "libs"),
                      os.environ.get("TEMP", "")):
        if not directory:
            continue
        for pattern in patterns:
            candidates.extend(glob.glob(os.path.join(directory, pattern)))
    candidates = [path for path in candidates if os.path.isfile(path)]
    return sorted(candidates, key=natural_key)[-1] if candidates else None


def jar_class_names(jar):
    with zipfile.ZipFile(jar) as zf:
        return set(zf.namelist())


def jar_contains_string(jar, needle):
    data = needle.encode("utf-8")
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.endswith(".class") and data in zf.read(name):
                return True
    return False


cbc_jar = find_mod_jar("RSCC_CBC_JAR", ["createbigcannons-*.jar"])
ca_jar = find_mod_jar("RSCC_CA_JAR", ["createaddition-*.jar"])
if not cbc_jar:
    skip("⑤ 字节码核对：找不到 CBC jar（设置 RSCC_CBC_JAR 可指定）")
else:
    print("      核对 jar: %s" % cbc_jar)
    names = jar_class_names(cbc_jar)
    check("⑤ CBC jar 里有 CBCBlockRecipeCategory / CBCJEI",
          "rbasamoyai/createbigcannons/compat/jei/CBCBlockRecipeCategory.class" in names
          and "rbasamoyai/createbigcannons/compat/jei/CBCJEI.class" in names)
    try:
        out = subprocess.run(
            [JAVAP, "-c", "-p", "-classpath", cbc_jar,
             "rbasamoyai.createbigcannons.compat.jei.CBCJEI"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=180).stdout or ""
    except Exception:  # pragma: no cover
        out = ""
    check("⑤ CBC 字节码里确实登记了 CASTING_SAND / CANNON_BUILDER / CANNON_DRILL / 轴承等催化剂",
          all(token in out for token in (
              "CBCBlocks.CASTING_SAND", "CBCBlocks.CANNON_BUILDER",
              "CBCBlocks.CANNON_DRILL", "AllBlocks.MECHANICAL_BEARING",
              "AllBlocks.WINDMILL_BEARING", "BASIN_FOUNDRY_LID")))
    check("⑤ CBC 5.11.7 的类文件里没有 getToastSymbol 字符串（复算根因②）",
          not jar_contains_string(cbc_jar, "getToastSymbol"))

if not ca_jar:
    skip("⑤ 字节码核对：找不到 Create Addition jar（设置 RSCC_CA_JAR 可指定）")
else:
    print("      核对 jar: %s" % ca_jar)
    names = jar_class_names(ca_jar)
    check("⑤ CreateAddition jar 里有 CreateAdditionJEI",
          "com/mrh0/createaddition/compat/jei/CreateAdditionJEI.class" in names)
    try:
        out = subprocess.run(
            [JAVAP, "-c", "-p", "-classpath", ca_jar,
             "com.mrh0.createaddition.compat.jei.CreateAdditionJEI"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=180).stdout or ""
    except Exception:  # pragma: no cover
        out = ""
    check("⑤ CreateAddition 字节码里确实登记了 TESLA_COIL / ROLLING_MILL / BLAZE_BURNER",
          all(token in out for token in (
              "CABlocks.TESLA_COIL", "CABlocks.ROLLING_MILL", "AllBlocks.BLAZE_BURNER")))
    check("⑤ Create Addition 1.7.2 的类文件里没有 getToastSymbol 字符串（复算根因②）",
          not jar_contains_string(ca_jar, "getToastSymbol"))

# =====================================================================================
# ⑥ 打印对照表（可复跑，给人看）
# =====================================================================================
section("⑥ 对照表（CBC / Create Addition）")
for row in CBC_CA_TABLE:
    machines_text = ", ".join(row["machines"]) if row["machines"] else "<无机器>"
    print("  %-62s -> %-70s [%s]" % (row["recipe_type"], machines_text, row["source"]))
print()
print("  说明：createbigcannons:cannon_casting / built_up_heating / drill_boring /")
print("        incomplete_cannon_blocks 是 CBC 自己的 BlockRecipeType，不在")
print("        BuiltInRegistries.RECIPE_TYPE 里（不会出现在配方类型下拉框），")
print("        但 JEI 里有分类，列在这里是为了证据完整。")

# =====================================================================================
# ⑦ 摘要
# =====================================================================================
section("⑦ 摘要")
print("依赖安全：主路径无 JEI import；JeiCatalystMachines 用 ModList 探测 + Class.forName/Method 反射，")
print("          JEI 类名只出现在字符串常量里，ReflectiveOperationException/RuntimeException/LinkageError 全接住。")
print("空结果：终端 machineCache 只缓存非空；RecipeTypeMachines 用 1 秒 TTL + JEI 代际 + 客户端配方表代际。")
print("合并顺序：显式表 → getToastSymbol（含 CraftingRecipe 的默认工作台）→ JEI 催化剂 → *_deployer 回退；按 Item 去重。")
print()
print("没有实机验证：本脚本只做源码 / 字节码交叉验证；tooltip / 终端里肉眼看到的机器列表必须由人在客户端确认。")

if PROBLEMS:
    print("=" * 100)
    print("SELFCHECK FAILED：%d 项不通过" % len(PROBLEMS))
    for problem in PROBLEMS:
        print("  - %s" % problem)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
