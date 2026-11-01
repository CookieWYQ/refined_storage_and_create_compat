#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮契约自检（E 组「区域选定器 NBT 复制配方」）。

规格来源
--------
`docs/ROUND131_CONTRACT.md` §0 用户原话最后一段 + §3.E 的 4 条硬判据 + §4 协作纪律。
名字统一以 `§E<N>` 开头，失败信息里写「契约第几节 / 期望 / 实际」。

本脚本是**规格判定程序**，不是实现
---------------------------------
* 只读 `src/**`（一个字节都不写），写作用域只有 `tools/`；
* 纯 stdlib python3，从**真源码**解析，不 import 游戏代码、不跑 gradlew / javac；
* 定位方法一律用「正则找定义 + 括号配对」，**不写死行号**；
* 断言语义（守卫的**顺序**、判据的**合取/析取**、产物的**复制方式**），不是「文件里有这个字符串」；
* 每条断言都带**反例**：把旧写法 / 漏守卫的写法拼回去、把源码换成空树后必须判负。

覆盖（对应 §3.E 四条）
----------------------
§E1 产物与形状：本类 `extends CustomRecipe`（= 无形状、由代码决定产物的原版机制家族），
    `SELECTOR_COUNT = 2`，JSON 落在 `data/rs_create_compat/recipe/area_selector_copy.json`
    且 `type` 指向本配方的序列化器（不是 `crafting_shapeless`，否则产物写死、复制不了 NBT）。
§E2 输入判据（四条守卫，顺序也钉住）：非空 = 恰好 2 个选定器；出现别的物品 ⇒ 不匹配；
    第 3 个选定器 ⇒ 不匹配；**恰好一个**带区域数据（两个都带 ⇒ 无产物）。
    「带区域数据」= `AreaSelection` 非空 **或** `AreaMarks` 非空（两个来源缺一都会丢配置）。
§E3 产物：「带数据的那一份」整份复制成 2 个（`copyWithCount(2)`），**不是**新建一个空选定器；
    判据不成立时 `assemble` 返回 `ItemStack.EMPTY`（不是抛异常、更不是「合出两个空的」）。
§E4 两个入口都生效：`canCraftInDimensions` 允许 2×2（背包）与 3×3（工作台）；
    并且本类**不覆写 `getType()`** —— `RecipeManager` 按 `recipe.getType()` 分桶，工作台与玩家 2×2 都只查
    `RecipeType.CRAFTING`；返回自定义类型会让配方落到别的桶里（玩家看到「放了两个选定器什么也不出」）。
    若 `build/probe_jars/mcneo` 的反编译源码在场，还会**直接核对**这两条入口的取配方类型。
§E5 数据安全：本类只读物品数据（不许 `AreaSelection.set` / `AreaMarks.put` / `set(` 写输入），
    也不许把产物写成「同 id 但无组件」的新物品。

纪律：不跑 gradlew / javac（Lead 统一编译）、不 git、不改 `src/**`、不改 docs / lang。
用法：python tools/selfcheck_round131_selector_copy.py
退出码：0 = 全部通过；1 = 有失败。最后一行：ALL_PASS / FAILED。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)
RES_REL = os.path.join("src", "main", "resources", "data", "rs_create_compat", "recipe")
RES = os.path.join(ROOT, RES_REL)

CONTRACT = "docs/ROUND131_CONTRACT.md"

RECIPE_REL = "src/main/java/cretae/cookiewyq/rs_create_compat/item/AreaSelectorCopyRecipe.java"
JSON_REL = "src/main/resources/data/rs_create_compat/recipe/area_selector_copy.json"

RECIPE = os.path.join(ROOT, RECIPE_REL)
RECIPE_JSON = os.path.join(ROOT, JSON_REL)

#: 反编译的 MC 源码（build 产物；不在场时跳过「分桶证据」那两条，不算失败）。
PROBE_DIR = os.path.join(ROOT, "build", "probe_jars", "mcneo", "net", "minecraft", "world",
                         "inventory")
PROBE_CRAFTING_MENU = os.path.join(PROBE_DIR, "CraftingMenu.java")
PROBE_INVENTORY_MENU = os.path.join(PROBE_DIR, "InventoryMenu.java")

COUNTER_TOTAL = [0]
COUNTER_PASS = [0]


# ======================================================================================
# 基础工具
# ======================================================================================

def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def read(path):
    try:
        with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def find_method_body(code, name):
    """返回 `name(` 那个**方法定义**的方法体（含花括号）；找不到返回 None。"""
    pattern = re.compile(
        r"(?m)^[ \t]*(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?[ \t]*\n[ \t]*)*"
        r"(?:public|protected|private)[^;{}\n]*\b" + re.escape(name) + r"\s*\(")
    for match in pattern.finditer(code):
        brace = code.find("{", match.end())
        if brace < 0:
            continue
        depth = 0
        for index in range(brace, len(code)):
            char = code[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return code[brace:index + 1]
    return None


def load_json(path):
    text = read(path)
    if not text.strip():
        return None
    try:
        return json.loads(text)
    except ValueError:
        return None


def load_snapshot():
    return {
        "recipe": read(RECIPE),
        "json": load_json(RECIPE_JSON),
        "json_raw": read(RECIPE_JSON),
        "crafting_menu": read(PROBE_CRAFTING_MENU),
        "inventory_menu": read(PROBE_INVENTORY_MENU),
    }


# ======================================================================================
# §E1 产物与形状
# ======================================================================================

def section_e1(s):
    results = []

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    recipe = s["recipe"]
    check("§E1.1 配方类文件在 item 包下（契约 §2 E 组指定路径）",
          os.path.isfile(RECIPE),
          "缺文件：%s" % RECIPE_REL)
    check("§E1.2 继承 CustomRecipe（无形状 + 产物由代码决定，同原版地图复制家族）",
          re.search(r"class\s+AreaSelectorCopyRecipe\s+extends\s+CustomRecipe", recipe) is not None,
          "类声明：%r" % (re.search(r"class\s+\w+[^\{]*", recipe).group(0) if recipe else ""))
    count = re.search(r"public\s+static\s+final\s+int\s+SELECTOR_COUNT\s*=\s*(\d+)\s*;", recipe)
    check("§E1.3 一次合成恰好 1 进 2 出：SELECTOR_COUNT = 2",
          count is not None and count.group(1) == "2",
          "实际：%r" % (count.group(1) if count else None))
    payload = s["json"]
    check("§E1.4 配方 JSON 在 data/rs_create_compat/recipe/ 下且是合法 JSON",
          payload is not None,
          "缺文件或解析失败：%s" % JSON_REL)
    check("§E1.5 JSON 的 type 指向本配方自己的序列化器（不是 crafting_shapeless）",
          payload is not None and payload.get("type") == "rs_create_compat:area_selector_copy",
          "实际 type：%r" % (payload.get("type") if payload else None))
    bad_keys = [key for key in ("ingredients", "result", "pattern", "key")
                if payload is not None and key in payload]
    check("§E1.6 JSON 不许写死 ingredients/result（那会把产物钉成「无 NBT 的新物品」）",
          not bad_keys,
          "出现了这些键：%r" % (bad_keys,))
    return results


# ======================================================================================
# §E2 输入判据（守卫 + 顺序）
# ======================================================================================

def section_e2(s):
    results = []
    recipe = s["recipe"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    matches = find_method_body(recipe, "matches")
    check("§E2.1 matches 走「带注册表的判据」（level.registryAccess() 传下去）",
          matches is not None and "markedSelector(input, level.registryAccess())" in matches,
          "matches 方法体：%r" % (matches,))
    core = find_method_body(recipe, "markedSelector")
    check("§E2.2 判据方法 markedSelector 存在且遍历整个合成格",
          core is not None and "input.size()" in core and "input.getItem(i)" in core,
          "方法体：%r" % (core,))
    check("§E2.3 守卫①：非空格子必须是区域选定器，否则不匹配",
          core is not None and "stack.is(RS_Create_Compat.AREA_SELECTOR.get())" in core
          and "return null" in core,
          "找不到「不是选定器就 return null」")
    check("§E2.4 守卫②：出现第 3 个以上选定器 ⇒ 不匹配（不悄悄吞物品）",
          core is not None and "selectors > SELECTOR_COUNT" in core,
          "找不到 `selectors > SELECTOR_COUNT`")
    check("§E2.5 守卫③：两个都带区域数据 ⇒ 无产物（契约 §3.E 用户拍板）",
          core is not None and "if (marked != null)" in core
          and core.find("if (marked != null)") > core.find("hasAreaData("),
          "`if (marked != null) return null;` 缺失或不在 hasAreaData 之后")
    check("§E2.6 守卫④：最后必须「恰好」2 个选定器（不是 >= 2）",
          core is not None
          and "selectors == SELECTOR_COUNT ? marked : null" in core,
          "找不到 `selectors == SELECTOR_COUNT ? marked : null`")
    order = None
    if core is not None:
        order = (core.find("selectors++"),
                 core.find("selectors > SELECTOR_COUNT"),
                 core.find("hasAreaData(stack, registries)"),
                 core.find("if (marked != null)"))
    check("§E2.7 守卫顺序：数个数 → 挡第 3 个 → 判是否带数据 → 挡第二个带数据的",
          order is not None and all(index >= 0 for index in order)
          and order[0] < order[1] < order[2] < order[3],
          "实际顺序下标：%r" % (order,))

    data = find_method_body(recipe, "hasAreaData")
    check("§E2.8 「带区域数据」= AreaSelection 非空 或 AreaMarks 非空（两个来源缺一都丢配置）",
          data is not None and "!AreaSelection.get(stack).isEmpty()" in data
          and "!AreaMarks.all(stack, registries).isEmpty()" in data and "||" in data,
          "方法体：%r" % (data,))
    check("§E2.9 AreaMarks 读的是带注册表的重载（旧档 Item 编码的标记不会漏判成空白）",
          data is not None and "AreaMarks.all(stack, registries)" in data
          and "AreaMarks.all(stack)" not in data,
          "方法体：%r" % (data,))
    return results


# ======================================================================================
# §E3 产物
# ======================================================================================

def section_e3(s):
    results = []
    recipe = s["recipe"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    assemble = find_method_body(recipe, "assemble")
    check("§E3.1 assemble 判据不成立时返回 ItemStack.EMPTY（不抛异常、不合出空物品）",
          assemble is not None and "return ItemStack.EMPTY;" in assemble,
          "方法体：%r" % (assemble,))
    check("§E3.2 产物 = 「带数据那份」整份复制（copyWithCount），不是新建同 id 物品",
          assemble is not None and "marked.copyWithCount(SELECTOR_COUNT)" in assemble
          and "new ItemStack(" not in assemble,
          "方法体：%r" % (assemble,))
    check("§E3.3 copyWithCount 的参数就是 SELECTOR_COUNT（1 进 2 出）",
          assemble is not None and re.search(r"copyWithCount\(\s*SELECTOR_COUNT\s*\)", assemble)
          is not None,
          "找不到 copyWithCount(SELECTOR_COUNT)")
    check("§E3.4 本类只读输入数据：不许 AreaSelection.set / AreaMarks.put / AreaMarks.remove",
          not any(token in recipe for token in ("AreaSelection.set(", "AreaMarks.put(",
                                                "AreaMarks.remove(")),
          "配方类里出现了写物品数据的调用（合成会改玩家手里的东西）")
    return results


# ======================================================================================
# §E4 两个入口都生效
# ======================================================================================

def section_e4(s):
    results = []
    recipe = s["recipe"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    dims = find_method_body(recipe, "canCraftInDimensions")
    check("§E4.1 canCraftInDimensions 允许 2×2 背包合成（width*height >= 2）",
          dims is not None and "width * height >= SELECTOR_COUNT" in dims,
          "方法体：%r" % (dims,))
    check("§E4.2 不覆写 getType()：配方必须留在 RecipeType.CRAFTING 桶里（两个入口都只查这个桶）",
          occurrences_get_type(recipe) == 0,
          "本类覆写了 getType() —— 自定义 RecipeType 会让配方掉出 CRAFTING 桶")
    check("§E4.3 getSerializer() 返回本类静态单例（与注册处同一个实例）",
          find_method_body(recipe, "getSerializer") is not None
          and "return SERIALIZER;" in find_method_body(recipe, "getSerializer"),
          "getSerializer 方法体：%r" % (find_method_body(recipe, "getSerializer"),))
    check("§E4.4 SERIALIZER 用原版 SimpleCraftingRecipeSerializer（无参配方序列化器）",
          "new SimpleCraftingRecipeSerializer<>(AreaSelectorCopyRecipe::new)" in recipe,
          "找不到 `new SimpleCraftingRecipeSerializer<>(AreaSelectorCopyRecipe::new)`")

    if s["crafting_menu"] and s["inventory_menu"]:
        working = "RecipeType.CRAFTING" in s["crafting_menu"] \
            and ".assemble(craftinginput" in s["crafting_menu"]
        player = "CraftingMenu.slotChangedCraftingGrid" in s["inventory_menu"]
        check("§E4.5 反编译证据：工作台按 RecipeType.CRAFTING + assemble 取配方，玩家 2×2 复用同一路径",
              working and player,
              "crafting=%s inventory=%s" % (working, player))
    else:
        print("SKIP §E4.5 反编译源码不在场（build/probe_jars 被清过），跳过「两个入口取 CRAFTING 桶」证据")
    return results


def occurrences_get_type(recipe):
    """统计 `getType(` 的**方法定义**次数（`Recipe::getType` 之类的方法引用、调用点都不算）。"""
    return len(re.findall(
        r"(?m)^[ \t]*(?:@[A-Za-z0-9_.]+[ \t]*\n[ \t]*)*(?:public|protected|private)[^;{}\n]*\b"
        r"getType\s*\(", recipe))


# ======================================================================================
# §E5 汇总 / 反例
# ======================================================================================

SECTIONS = (section_e1, section_e2, section_e3, section_e4)


def run_all(snapshot):
    results = []
    for section in SECTIONS:
        results.extend(section(snapshot))
    return results


def failures_of(snapshot):
    return set(name for name, ok, _detail in run_all(snapshot) if not ok)


def copy_with(snapshot, **overrides):
    out = dict(snapshot)
    out.update(overrides)
    return out


def repl(snapshot, key, old, new):
    text = snapshot.get(key, "")
    if old not in text:
        return None
    return copy_with(snapshot, **{key: text.replace(old, new, 1)})


def run_counterexamples(base_snapshot, base_bad):
    cases = []

    # ① 去掉「两个都带 ⇒ 无产物」守卫（用户拍板的核心规则）
    cases.append(("删掉「两个都带区域数据 ⇒ 无产物」守卫",
                  ["§E2.5"],
                  lambda snap: repl(snap, "recipe",
                                    "                if (marked != null) {\n"
                                    "                    return null; // ③ 两个都带区域数据 ⇒ 按用户拍板「合成不出东西」\n"
                                    "                }\n",
                                    "")))

    # ② 第 3 个选定器不再挡
    cases.append(("不挡第 3 个选定器（selectors > SELECTOR_COUNT 被放宽）",
                  ["§E2.4"],
                  lambda snap: repl(snap, "recipe", "if (selectors > SELECTOR_COUNT) {",
                                    "if (selectors > 64) {")))

    # ③ 只认 AreaSelection，不认 AreaMarks（只配过过滤的复制会丢配置）
    cases.append(("「带数据」只查 AreaSelection，漏掉 AreaMarks",
                  ["§E2.8"],
                  lambda snap: repl(snap, "recipe",
                                    "        return !AreaSelection.get(stack).isEmpty()\n"
                                    "            || !AreaMarks.all(stack, registries).isEmpty();",
                                    "        return !AreaSelection.get(stack).isEmpty();")))

    # ④ 用两参 AreaMarks.all（旧档 Item 编码的标记读不出 ⇒ 误判空白）
    cases.append(("AreaMarks 改用两参重载（旧档标记读不出）",
                  ["§E2.9"],
                  lambda snap: repl(snap, "recipe", "AreaMarks.all(stack, registries)",
                                    "AreaMarks.all(stack)")))

    # ⑤ 产物改成新建同 id 物品（NBT 丢失）
    cases.append(("产物改成 new ItemStack(...)（不复制 NBT）",
                  ["§E3.2", "§E3.3"],
                  lambda snap: repl(snap, "recipe", "return marked.copyWithCount(SELECTOR_COUNT);",
                                    "return new ItemStack(RS_Create_Compat.AREA_SELECTOR.get(), "
                                    "SELECTOR_COUNT);")))

    # ⑥ 覆写 getType() 返回自定义类型（掉出 CRAFTING 桶 ⇒ 两个入口都找不到）
    cases.append(("覆写 getType() 返回自定义 RecipeType",
                  ["§E4.2"],
                  lambda snap: copy_with(snap, recipe=snap["recipe"] + (
                      "\n    @Override\n"
                      "    public RecipeType<?> getType() {\n"
                      "        return RecipeType.CRAFTING;\n"
                      "    }\n"))))

    # ⑦ 一进一出（数量写错）
    cases.append(("SELECTOR_COUNT 改成 1（一进一出）",
                  ["§E1.3"],
                  lambda snap: repl(snap, "recipe",
                                    "SELECTOR_COUNT = 2;", "SELECTOR_COUNT = 1;")))

    # ⑧ 2×2 放不下（canCraftInDimensions 写死 3×3）
    cases.append(("canCraftInDimensions 写死 3×3（背包合成失效）",
                  ["§E4.1"],
                  lambda snap: repl(snap, "recipe", "return width * height >= SELECTOR_COUNT;",
                                    "return width >= 3 && height >= 3;")))

    # ⑨ JSON type 写错（序列化器找不到）
    cases.append(("JSON 的 type 写错",
                  ["§E1.5"],
                  lambda snap: copy_with(snap, json=dict(snap["json"] or {},
                                                         type="rs_create_compat:area_selector"),
                                         json_raw=snap["json_raw"])))

    # ⑩ 空树
    cases.append(("空树（配方源码与 JSON 都为空）",
                  ["§E"],
                  lambda snap: copy_with(snap, recipe="", json=None, json_raw="")))

    for label, targets, builder in cases:
        COUNTER_TOTAL[0] += 1
        broken = builder(base_snapshot)
        if broken is None:
            print("FAIL 反例 %s（锚点缺失：实现文本变了却没同步本脚本）" % label)
            continue
        fresh = [name for name in failures_of(broken) if name not in base_bad]
        hit = [name for name in fresh if any(name.startswith(prefix) for prefix in targets)]
        if hit:
            COUNTER_PASS[0] += 1
            print("PASS 反例 %s ⇒ 新增判负 %d 项（例：%s）" % (label, len(fresh), hit[0]))
            continue
        print("FAIL 反例 %s（改了却没多出目标判负；新增失败：%s）" % (label, fresh[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 131 轮契约自检（E 组）：区域选定器 NBT 复制配方")
    print("规格：%s §3.E" % CONTRACT)
    print("配方：%s" % RECIPE_REL)
    print("数据：%s" % JSON_REL)

    missing = [rel(path) for path in (RECIPE, RECIPE_JSON) if not os.path.isfile(path)]
    if missing:
        print("\n[注意] 以下文件还不存在（实现未就绪时正常）：")
        for item in missing:
            print("  - %s" % item)

    snapshot = load_snapshot()
    results = run_all(snapshot)
    for name, ok, detail in results:
        if ok:
            print("PASS " + name)
        else:
            print("FAIL " + name + (("  <- " + detail) if detail else ""))

    base_bad = set(name for name, ok, _detail in results if not ok)
    print("\n检查项：%d，失败：%d" % (len(results), len(base_bad)))
    if base_bad:
        for name in sorted(base_bad):
            print("  FAIL " + name)

    run_counterexamples(snapshot, base_bad)

    print("\n检查项：%d，反例：%d/%d，失败：%d"
          % (len(results), COUNTER_PASS[0], COUNTER_TOTAL[0], len(base_bad)))
    if base_bad or COUNTER_PASS[0] != COUNTER_TOTAL[0]:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
