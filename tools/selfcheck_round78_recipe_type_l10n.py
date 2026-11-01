#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 78 轮：**配方类型显示名本地化**（含「别的模组」的配方类型）。

用户原话（逐字）
---------------
「还有那个**配方类型我让你做汉化你没做汉化呀**，虽然说这个汉化**可能有点艰难**，但是
**你必须得给我做**，**你必须给我想办法做**，因为我感觉**他应该是能做的**，你自己看图片。」

截图证据（改前）
---------------
「配方类型」菜单里中英混杂：`砂轮打磨 / 打印 / 曝光 / 镀膜 / 光束加工 / 正在充电 / 液体燃烧中 / 轧制`
是中文，而 `Freezing / Sanding / Autocannon Ammo Container Filling Deployer /
Big Cartridge Filling / Big Cartridge Filling Deployer / Cartridge Assembly /
Cartridge Assembly Deployer / Fuze Removal` 是英文。

根因（为什么「有的中文、有的英文」）
---------------------------------
`data/RecipeTypeNames#of` 依次探测 `machine.rs_create_compat.<X>`、`recipe.<ns>.<path>`、
`<ns>.recipe.<path>`，都没有就「首字母大写化 id」。于是：
  * `create:pressing` → Create 自己的 `create.recipe.pressing`（冲压）⇒ 中文；
  * `createaddition:charging` → Create Addition 自己的 `createaddition.recipe.charging`（充能）⇒ 中文；
  * `createbigcannons:big_cartridge_filling` → **对方 jar 里没有任何键** ⇒ 只能派生 ⇒ 英文。
所以「做不了」的错觉来自「只查对方语言文件」；本轮加的**我们自己的词典**才是解法的关键。

断言（任一 FAIL 即退出码 1）
    ① 已知配方类型清单（可复跑：内置表 + 语言里的词典键 + 有 jar 时从 jar 的 data/**/recipe* 里抓）
       在 **zh_cn / en_us 两套语言**下都能解析出非空、可读、**不是原始 id**（不含 `:` / `_`）的名字；
       解析失败数必须是 **0**；
    ② 派生算法：样例表逐条比对中英两种形态（幂等：`derive(derive(x)) == derive(x)`）；
    ③ 缓存：源码级 —— `of()` 先查 `ConcurrentHashMap` 再解析、缓存以「语言表实例」为版本标记、
       `RecipeTypeNames.java` 里**没有任何读文件 / 读 jar** 的写法；
    ④ 回退链顺序：词典 → 旧机器键 → 第三方官方键 → 派生 → 原样 id（源码里按这个顺序出现）；
    ⑤ **反例自证**：
       (a) 把解析换成「直接显示 `RecipeType.toString()`（= 注册 id）」后，①「不是原始 id」判据把
           **全部**已知类型判负；
       (b) 把真实源码里的 `return derived.isEmpty() ? recipeType : derived;` 改成 `return recipeType;`，
           ④ 的顺序判据必须判负；
       (c) 删掉第三方键循环 / 让 `of()` 不查缓存，对应判据也必须判负；
    ⑥ 语言文件：词典键与单词键 **中英成对**（zh 含中日韩文字、en 不含），且没有悬空引用；
    ⑦ jar 证据（有 jar 时**硬断言**，没有则打印记录下来的事实）：
       Create 有 `create.recipe.pressing`；Create Addition 有 `createaddition.recipe.{charging,liquid_burning,rolling}`；
       机械动力：火炮有 `recipe.createbigcannons.melting`，但 12 个装填 / 组装 / 引信 / 曳光弹类类型
       **一个键都没有** —— 这正是必须由我们的词典兜底的那批；
    ⑧ GUI 侧没有第二套名字解析：选择控件仍走 `RecipeTypeNames::display`，
       `SyncRecipeTypesPacket` 排序仍用 `RecipeTypeNames.of`。
    ⑨ **剩余英文清单**（第 84 轮）：把「可复跑清单（内置表 + 词典键 + 本机所有 jar 的
       data/**\\*.json 里声明的配方类型）」逐条按 zh_cn 解析，打印**仍然没有中文**的那些；
       断言该清单为空，或只剩白名单里写明原因的那几条。用 `RSCC_MODS_DIR=<整合包 mods 目录>`
       可以把整合包的真实 jar 全部纳入（默认只用本机 run/mods + libs + 已知依赖 jar）。
    ⑩ **搜索三键**（id / 中文名 / 英文名）：用与 Java 同一套算法（搜索串 + 折叠分隔符 + 小写）
       复算「给定查询串 → 命中集合」，逐条断言期望项在里面；同时核对 Java 的
       `EN_SEARCH_ALIASES` 与脚本这张表逐字相同。
    ⑪ **搜索控件是复用的**（源码级）：两个承载配方类型下拉框的界面都必须用
       `RecipeTypeSelect`（`ScrollSelectWidget` + `EditBox` 的组合体），全工程只有它一处
       调用 `RecipeTypeNames.matchesSearch`；界面自己的 `render()` 里不得再出现过滤调用。
    ⑫ **反例自证**：把「只搜中文名」「区分大小写」「每帧全量重算」「界面里手写第二套搜索」
       四种写法分别喂给 ⑨/⑩/⑪ 的判据，判据必须判负。
    ⑬ **机器图标兜底**（第 84 轮）：`RecipeTypeMachines` 的第五条来源「同命名空间方块」必须
       （a）排在前四条之后且只在结果为空时执行、（b）规则是「整词连续 + 同命名空间 + 取最短」。
       用 Python 复算同一套词规则，并喂「纯子串匹配」的反例证明判据能判负。

用法：python tools/selfcheck_round78_recipe_type_l10n.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import glob
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
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA_ROOT, "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

NAMES_JAVA = os.path.join(PKG, "data", "RecipeTypeNames.java")
PACKET_JAVA = os.path.join(PKG, "network", "SyncRecipeTypesPacket.java")
PICKER_SCREENS = (
    os.path.join(PKG, "client", "screen", "UnitPatternConfigScreen.java"),
    os.path.join(PKG, "client", "screen", "ChamberBindingConfigScreen.java"),
)

DICT_PREFIX = "machine.rs_create_compat.recipe_type."
LEGACY_PREFIX = "machine.rs_create_compat."
WORD_PREFIX = "machine.rs_create_compat.word."
# 与 Java `THIRD_PARTY_KEYS` 同一张表、同一顺序（Java 用 `%1$s` 位置参数，这里用 `{0}`）。
THIRD_PARTY_KEYS = (
    "{0}.recipe.{1}",
    "recipe.{0}.{1}",
    "{0}.recipe_type.{1}",
    "emi.category.{0}.{1}",
    "jei.category.{0}.{1}",
)
# 与 Java `derive()` 完全一致的切词表达式。
WORD_SPLIT = re.compile(r"[_\-.]+")
CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]")
# 与 Java `PLACEHOLDER` 一致：`%s` / `%1$s` / `%d` / `%%`（`50%` 这种不算）。
PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[sd%]")

# ---------------------------------------------------------------------------
# ① 已知配方类型清单（内置表；来源写清，便于复核）
# ---------------------------------------------------------------------------
VANILLA_TYPES = (
    "crafting", "smelting", "blasting", "smoking", "campfire_cooking", "stonecutting", "smithing",
)
# Create 6.0.10 的 AllRecipeTypes（17 个真正注册的类型；TOOLBOX_DYEING / ITEM_COPYING 复用 crafting）。
CREATE_TYPES = (
    "conversion", "crushing", "cutting", "milling", "basin", "mixing", "compacting", "pressing",
    "sandpaper_polishing", "splashing", "haunting", "deploying", "filling", "emptying",
    "item_application", "mechanical_crafting", "sequenced_assembly",
)
# Create Addition 1.7.2（1.21.1）的 3 个类型。
CREATE_ADDITION_TYPES = ("charging", "liquid_burning", "rolling")
# 机械动力：火炮 5.11.7（1.21.1）：data/**/recipe*/**.json 里出现过的全部 createbigcannons 类型。
# 其中 melting / built_up_heating / cannon_casting / drill_boring 它自己有官方键；
# 其余 12 条**没有**，必须由我们的词典兜底（见第 ⑦ 段）。
CBC_TYPES = (
    "autocannon_ammo_container_filling",
    "autocannon_ammo_container_filling_deployer",
    "big_cartridge_filling",
    "big_cartridge_filling_deployer",
    "built_up_heating",
    "cannon_casting",
    "cartridge_assembly",
    "cartridge_assembly_deployer",
    "drill_boring",
    "fuze_removal",
    "melting",
    "munition_fuzing",
    "munition_fuzing_deployer",
    "tracer_application",
    "tracer_application_deployer",
    "tracer_removal",
)
# CBC 里「它自己没有翻译」的 12 条（第 ⑦ 段要逐条核实「确实一个候选键都没有」）。
CBC_NEEDS_OUR_DICT = tuple(p for p in CBC_TYPES if p not in
                           ("built_up_heating", "cannon_casting", "drill_boring", "melting"))

KNOWN_TYPES = ([("minecraft:" + p, "原版") for p in VANILLA_TYPES]
               + [("create:" + p, "Create 6.0.10") for p in CREATE_TYPES]
               + [("createaddition:" + p, "Create Addition 1.7.2") for p in CREATE_ADDITION_TYPES]
               + [("createbigcannons:" + p, "机械动力：火炮 5.11.7") for p in CBC_TYPES])

# ② 派生样例表：(path, 中文形态, 英文形态)
DERIVE_SAMPLES = (
    ("liquid_burning", "液体燃烧", "Liquid Burning"),
    ("freezing", "冷冻", "Freezing"),
    ("sanding", "打磨", "Sanding"),
    ("charging", "充能", "Charging"),
    ("rolling", "轧制", "Rolling"),
    ("big_cartridge_filling", "大型弹药装填", "Big Cartridge Filling"),
    ("cartridge_assembly_deployer", "弹药组装机械手", "Cartridge Assembly Deployer"),
    ("fuze_removal", "引信拆除", "Fuze Removal"),
    ("autocannon_ammo_container_filling_deployer", "机炮弹药容器装填机械手",
     "Autocannon Ammo Container Filling Deployer"),
    # 中英混排：未收录的词保留英文，交界处补空格（否则会粘成 BuiltUp加热）
    ("built_up_heating", "Built Up 加热", "Built Up Heating"),
    ("definitely_unknown_word", "Definitely Unknown Word", "Definitely Unknown Word"),
)

# ---------------------------------------------------------------------------
# 第 84 轮常量：搜索别名 / 非配方类型 id / 剩余英文白名单
# ---------------------------------------------------------------------------
# 与 Java `RecipeTypeNames#EN_SEARCH_ALIASES` **同一张表、同一个顺序**（⑩ 段会解析 Java 源码核对）。
# 只收「官方英文名里有、id 词里没有」的少数词，所以表小、可核对。
EN_SEARCH_ALIASES = {
    "create:emptying": "Item Draining",
    "create:item_application": "Manual Item Application",
    "create:sequenced_assembly": "Recipe Sequence",
    "createbigcannons:melting": "Basin Melting",
    "createbigcannons:built_up_heating": "Cannon Building",
}

# `data/**/*.json` 里 `"type"` 不一定是配方类型（战利品表 / 世界生成也会写 type）。
# 这些 id 出现在抓取结果里时直接丢弃，避免把它们算进「剩余英文」。
NON_RECIPE_IDS = frozenset({
    "minecraft:block", "minecraft:item", "minecraft:entity_type", "minecraft:loot_table",
    "minecraft:tag", "minecraft:advancement_reward", "minecraft:command", "minecraft:selector",
    "minecraft:chest", "minecraft:generic", "minecraft:sequence",
})

# 剩余英文白名单：**留空**表示清单必须为空。
# 若将来某条确实无法本地化，必须在这里写清原因（源 id → 原因），不能只把判据放宽。
ENGLISH_ALLOWLIST = {}

# 承载「配方类型下拉框」的两个界面（都必须复用 RecipeTypeSelect）。
MACHINES_JAVA = os.path.join(PKG, "client", "tooltip", "RecipeTypeMachines.java")
SELECT_WIDGET = os.path.join(PKG, "client", "widget", "RecipeTypeSelect.java")
SCROLL_SELECT = os.path.join(PKG, "client", "widget", "ScrollSelectWidget.java")
MODS_DIR_ENV = "RSCC_MODS_DIR"

# ⑦ 记录下来的 jar 事实（没有 jar 时按这些打印；有 jar 时逐条硬核对）。
RECORDED_EVIDENCE = {
    "create": ("create-1.21.1-6.0.10-280-slim.jar",
               "zh_cn 3619 键，其中 create.recipe.* 47 条（含 create.recipe.pressing = 冲压）"),
    "createaddition": ("createaddition-1.7.2.jar",
                       "zh_cn 260 键，含 createaddition.recipe.charging / liquid_burning / rolling"),
    "createbigcannons": ("createbigcannons-5.11.7.jar",
                         "zh_cn 738 键，只有 recipe.createbigcannons.melting 等 5 条配方类键；"
                         "12 个装填 / 组装 / 引信 / 曳光弹类类型完全没有键"),
}

FAILURES = []
CHECKS = [0]


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "INFO"), name,
                       (" | " + detail) if (detail and not ok) else ""))
    return ok


# ---------------------------------------------------------------------------
# 文本工具
# ---------------------------------------------------------------------------
def read_text(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释，保留字符串字面量（键名断言必须看到字面量）。"""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def method_body(source, signature_regex):
    """按大括号配对取出方法体（含花括号）。signature_regex 从方法名开始匹配。"""
    match = re.search(signature_regex, source)
    if not match:
        return None
    brace = source.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[brace:i + 1]
    return None


# ---------------------------------------------------------------------------
# 语言表（复刻 Minecraft 的合并规则：en_us 打底，选中语言覆盖）
# ---------------------------------------------------------------------------
def load_json(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def our_lang(locale):
    return load_json(os.path.join(LANG_DIR, "%s.json" % locale))


def jar_lang(jar, locale):
    """读 jar 里**所有** assets/*/lang/<locale>.json 合并（一个 jar 可能带多个命名空间）。"""
    merged = {}
    try:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if not name.endswith("/lang/%s.json" % locale):
                    continue
                try:
                    merged.update(json.loads(z.read(name).decode("utf-8-sig")))
                except Exception:
                    continue
    except Exception:
        return merged
    return merged


def gradle_caches():
    home = os.environ.get("GRADLE_USER_HOME") or os.path.join(os.path.expanduser("~"), ".gradle")
    return os.path.join(home, "caches")


def is_valid_zip(path):
    """下载到一半 / 损坏的 jar 不该让自检变红（当作「这台上没有这个 jar」处理）。"""
    try:
        with zipfile.ZipFile(path) as z:
            z.namelist()
        return True
    except Exception:
        return False


def find_jars():
    """返回 {命名空间: [jar...]}；任何一步找不到都不算失败（第 ⑦ 段会降级成 INFO）。"""
    found = {"create": [], "createaddition": [], "createbigcannons": []}
    pattern = os.path.join(gradle_caches(), "modules-2", "files-2.1", "com.simibubi.create",
                           "create-1.21.1", "*", "*", "create-*-slim.jar")
    found["create"] = [p for p in sorted(glob.glob(pattern)) if is_valid_zip(p)]
    scan_dirs = [os.path.join(ROOT, "run", "mods"), os.path.join(ROOT, "libs"),
                 os.path.join(ROOT, "build")]
    # %TEMP% 根目录**不递归**（那里文件很多，递归会白等十几秒）；只看根下一层与探针自己的下载目录。
    temp = os.environ.get("TEMP") or os.environ.get("TMP") or ""
    flat_dirs = [temp] if temp else []
    scan_dirs += [os.path.join(temp, "rscc_l10n")] if temp else []
    for ns, prefixes in (("createaddition", ("createaddition-",)),
                         ("createbigcannons", ("createbigcannons-",))):
        hits = []
        for base in scan_dirs:
            if not base or not os.path.isdir(base):
                continue
            for prefix in prefixes:
                hits += glob.glob(os.path.join(base, "**", prefix + "*.jar"), recursive=True)
        for base in flat_dirs:
            if not os.path.isdir(base):
                continue
            for prefix in prefixes:
                hits += glob.glob(os.path.join(base, prefix + "*.jar"))
        found[ns] = [p for p in sorted(set(hits)) if is_valid_zip(p)]
    return found


def build_table(locale, jars):
    table = {}
    for names in jars.values():
        for jar in names:
            table.update(jar_lang(jar, "en_us"))
    table.update(our_lang("en_us"))
    if locale != "en_us":
        for names in jars.values():
            for jar in names:
                table.update(jar_lang(jar, locale))
        table.update(our_lang(locale))
    return table


# ---------------------------------------------------------------------------
# 解析算法（与 RecipeTypeNames.java 一一对应）
# ---------------------------------------------------------------------------
def split_id(recipe_type):
    colon = recipe_type.find(":")
    if colon < 0:
        return "minecraft", recipe_type
    return recipe_type[:colon], recipe_type[colon + 1:]


def capitalize(word):
    return word[:1].upper() + word[1:] if word else word


def candidate_keys(namespace, path):
    yield DICT_PREFIX + namespace + "." + path
    yield LEGACY_PREFIX + capitalize(path)
    for pattern in THIRD_PARTY_KEYS:
        yield pattern.format(namespace, path)


def lookup(table, key):
    value = table.get(key)
    if not isinstance(value, str) or not value.strip():
        return None
    if PLACEHOLDER_RE.search(value):
        return None  # 带占位符的是提示句，不是名字（见 Java 里 PLACEHOLDER 的注释）
    return value.strip()


def derive(table, path):
    out, previous_cjk, added = [], False, 0
    for word in WORD_SPLIT.split(path):
        if not word:
            continue
        translated = lookup(table, WORD_PREFIX + word.lower())
        part = translated if translated is not None else capitalize(word)
        cjk = bool(CJK_RE.search(part))
        if added > 0 and (not cjk or not previous_cjk):
            out.append(" ")
        out.append(part)
        previous_cjk = cjk
        added += 1
    return "".join(out)


def resolve(table, recipe_type):
    namespace, path = split_id(recipe_type)
    for key in candidate_keys(namespace, path):
        hit = lookup(table, key)
        if hit is not None:
            return hit
    derived = derive(table, path)
    return derived if derived else recipe_type


def looks_raw(name):
    """「还是一条原始 id」的判据：带命名空间冒号，或仍是下划线形式。"""
    return name is None or not name.strip() or ":" in name or "_" in name


# ---------------------------------------------------------------------------
# ① / ② 行为断言
# ---------------------------------------------------------------------------
def collect_known(jars, extra_jars=()):
    """可复跑清单：内置表 + 语言里的词典键 + jar 的 data\\/**\\/*.json 里声明的配方类型。

    `jars` 是 {@link find_jars} 的 {命名空间: [jar]} 字典（只抓这三个模组的命名空间）；
    `extra_jars` 是「本机 / 整合包里其它 jar」的列表（命名空间不设限，但会丢掉
    {@link NON_RECIPE_IDS} 与 `minecraft:` 的 id，因为原版由内置表覆盖）。
    """
    known = list(KNOWN_TYPES)
    for locale in ("zh_cn", "en_us"):
        for key in our_lang(locale):
            if key.startswith(DICT_PREFIX):
                rest = key[len(DICT_PREFIX):]
                if "." in rest:
                    ns, path = rest.split(".", 1)
                    known.append(("%s:%s" % (ns, path), "我们自己的词典"))
    harvested = []
    for names in jars.values():
        for jar in names:
            harvested += harvest_recipe_types(jar, namespaces=set(jars))
    for jar in extra_jars:
        harvested += harvest_recipe_types(jar, namespaces=None)
    for recipe_type in harvested:
        if recipe_type.startswith("minecraft:") or recipe_type in NON_RECIPE_IDS:
            continue
        known.append((recipe_type, "jar 抓取"))
    # 去重（保序）
    seen, unique = set(), []
    for recipe_type, source in known:
        if recipe_type not in seen:
            seen.add(recipe_type)
            unique.append((recipe_type, source))
    return unique


def behavioural_checks(jars):
    tables = {"zh_cn": build_table("zh_cn", jars), "en_us": build_table("en_us", jars)}

    known = collect_known(jars)
    print("    可复跑清单：%d 个配方类型（内置表 %d 去重后 + jar 抓取 %d，看护 %d 条火炮类型）"
          % (len(known), len(KNOWN_TYPES),
             sum(1 for _r, s in known if s == "jar 抓取"), len(CBC_TYPES)))

    for locale in ("zh_cn", "en_us"):
        table = tables[locale]
        failures = []
        for recipe_type, _source in known:
            name = resolve(table, recipe_type)
            if looks_raw(name):
                failures.append("%s -> %r" % (recipe_type, name))
        check("①a [%s] %d 个已知配方类型全部解析出非空可读名（解析失败 %d）"
              % (locale, len(known), len(failures)),
              not failures, "; ".join(failures[:6]))
        check("①b [%s] 解析结果里没有原始 id / 下划线泄漏" % locale, not failures)

    # 每个解析名都不得为空串（语言文件缺键时也不许留白）
    empties = [(r, s) for locale in ("zh_cn", "en_us")
               for r, s in known if not resolve(tables[locale], r).strip()]
    check("①c 两套语言下都没有空白名字", not empties, str(empties[:5]))

    # ② 派生样例表
    for locale in ("zh_cn", "en_us"):
        table = tables[locale]
        bad = []
        for path, zh, en in DERIVE_SAMPLES:
            expected = zh if locale == "zh_cn" else en
            actual = derive(table, path)
            if actual != expected:
                bad.append("%s: %r != %r" % (path, actual, expected))
        check("②a [%s] 派生样例 %d 条全部吻合" % (locale, len(DERIVE_SAMPLES)), not bad,
              "; ".join(bad))
        # 幂等：derive(derive(x)) == derive(x)
        non_idempotent = []
        for path, _zh, _en in DERIVE_SAMPLES + (("a__b", "", ""), ("x-y.z", "", ""),
                                                ("__", "", ""), ("one", "", "")):
            once = derive(table, path)
            twice = derive(table, once)
            if twice != once:
                non_idempotent.append("%s: %r -> %r" % (path, once, twice))
        check("②b [%s] 派生幂等（derive(derive(x)) == derive(x)）" % locale, not non_idempotent,
              "; ".join(non_idempotent))
        check("②c [%s] 派生结果可读（无下划线 / 无冒号 / 无首尾空格）" % locale,
              all(not looks_raw(derive(table, p)) and derive(table, p) == derive(table, p).strip()
                  for p, _zh, _en in DERIVE_SAMPLES))

    # 中英确实不同（证明是「两套」而不是同一串英文）
    differing = [r for r, _s in known
                 if resolve(tables["zh_cn"], r) != resolve(tables["en_us"], r)]
    check("①d 中英两套解析确有差异（%d/%d 条）" % (len(differing), len(known)),
          len(differing) > 0)
    cjk_named = [r for r, _s in known if CJK_RE.search(resolve(tables["zh_cn"], r))]
    missing_cjk = [(r, resolve(tables["zh_cn"], r)) for r, _s in known
                   if not CJK_RE.search(resolve(tables["zh_cn"], r))]
    # 用户要的是中文：**已知清单里的每一条**都必须落到中文（词典或单词表）。
    # 将来新加模组导致这里失败 ⇒ 正确做法是补词典 / 补单词，而不是放宽判据。
    check("①e 中文表下 %d/%d 个已知类型解析成中文（未覆盖：%s）"
          % (len(cjk_named), len(known), [r for r, _n in missing_cjk] or "无"),
          not missing_cjk)
    return tables


def harvest_recipe_types(jar, namespaces=None):
    """从 jar 的 data/**/*.json 里抓 "type" 值（可复跑清单的来源之一）。

    只收「像配方」的 JSON：路径里带 `recipe` / `recipes`，或数据目录名就是该 type 的路径
    （机械动力：火炮把 `cannon_casting/` `drill_boring/` 这些按配方类型名建目录）。
    这样能排除 `data/create/worldgen/...` 里 `"type": "create:layered_ore"` 这类**世界生成特征**。
    """
    types = set()
    try:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if not name.startswith("data/") or not name.endswith(".json"):
                    continue
                parts = name.split("/")
                if len(parts) < 4:
                    continue
                try:
                    data = json.loads(z.read(name).decode("utf-8-sig"))
                except Exception:
                    continue
                if not isinstance(data, dict) or not isinstance(data.get("type"), str):
                    continue
                namespace, _, type_path = data["type"].partition(":")
                if namespaces is not None and namespace not in namespaces:
                    continue
                if "recipe" not in parts and "recipes" not in parts and parts[2] != type_path:
                    continue
                types.add(data["type"])
    except Exception:
        return []
    return sorted(types)


# ---------------------------------------------------------------------------
# ③ / ④ 源码级断言
# ---------------------------------------------------------------------------
def source_checks():
    java = read_text(NAMES_JAVA)
    code = strip_comments(java)
    of_body = method_body(code, r"public\s+static\s+String\s+of\s*\(")
    resolve_body = method_body(code, r"private\s+static\s+String\s+resolve\s*\(")
    cache_body = method_body(code, r"private\s+static\s+Cache\s+cache\s*\(")
    derive_body = method_body(code, r"private\s+static\s+String\s+derive\s*\(")

    check("③a 解析结果有缓存字段（ConcurrentHashMap）",
          "ConcurrentHashMap" in code and "record Cache(" in code)
    check("③b of() 先查缓存、未命中才 resolve()",
          of_body is not None and "resolve(" in of_body
          and of_body.find("names().get(recipeType)") >= 0
          and of_body.find("names().get(recipeType)") < of_body.find("resolve("),
          "body=%r" % (of_body or "")[:160])
    check("③c 缓存以「语言表实例」为版本标记（换语言整表作废）",
          cache_body is not None and "Language.getInstance()" in cache_body
          and "owner() == current" in cache_body and "ConcurrentHashMap" in cache_body)
    forbidden = ("java.io", "Files.", "ZipFile", "getResourceAsStream", "InputStream",
                 "OutputStream", "FileInputStream", "new File(", "getLanguageData()")
    hits = [token for token in forbidden if token in code]
    check("③d RecipeTypeNames 里没有任何读文件 / 读 jar / 扫描整张语言表的写法（%s）" % (hits or "无"),
          not hits)
    check("③e 缓存有上限（防御性封顶，避免任意字符串把表撑爆）", "CACHE_LIMIT" in code)

    fallback = (resolve_body.find("derived.isEmpty() ? recipeType : derived")
                if resolve_body else -1)
    check("④a resolve() 里五级回退顺序正确（词典 → 旧键 → 第三方 → 派生 → 原样 id）",
          resolve_body is not None
          and 0 <= resolve_body.find("DICT_PREFIX") < resolve_body.find("LEGACY_PREFIX")
          < resolve_body.find("THIRD_PARTY_KEYS") < resolve_body.find("derive(")
          < fallback,
          "顺序索引=%s" % ([resolve_body.find(t) for t in
                          ("DICT_PREFIX", "LEGACY_PREFIX", "THIRD_PARTY_KEYS", "derive(")]
                          + [fallback] if resolve_body else None))
    check("④b 最终兜底是「原样显示 id」（derived.isEmpty() ? recipeType : derived）",
          resolve_body is not None
          and re.search(r"derived\.isEmpty\(\)\s*\?\s*recipeType\s*:\s*derived", resolve_body) is not None)
    check("④c 派生实现里用了单词表（中英各一份）",
          derive_body is not None and "WORD_PREFIX" in derive_body)
    check("④d 派生按「字形交界」拼空格（连续中文紧贴、中英交界补空格）",
          derive_body is not None and "containsCjk" in derive_body
          and "previousCjk" in derive_body
          and re.search(r"out\.append\('\s'\)", derive_body) is not None)
    check("④e 第三方键格式包含 `recipe.<ns>.<path>` 与 `<ns>.recipe.<path>`（Create 系实测形态）",
          '"recipe.%1$s.%2$s"' in code and '"%1$s.recipe.%2$s"' in code)
    check("④f lookup() 会跳过带格式化占位符的值（避免把「Casting time: %ss」这类提示句当名字）",
          "PLACEHOLDER" in code
          and re.search(r"PLACEHOLDER\.matcher\(value\)\.find\(\)", code) is not None)

    # ⑤ 反例自证（Java 源码注入，只改内存副本，不动工作区文件）
    mutated = code.replace("return derived.isEmpty() ? recipeType : derived;", "return recipeType;")
    mutated_body = method_body(mutated, r"private\s+static\s+String\s+resolve\s*\(")
    check("⑤a 把最终 return 改成 `return recipeType;`（= 直接显示 id）后，④b 判据判负",
          mutated != code and mutated_body is not None
          and re.search(r"derived\.isEmpty\(\)\s*\?\s*recipeType\s*:\s*derived",
                        mutated_body) is None)

    without_third = re.sub(r"for\s*\(int i = 0; found == null && i < THIRD_PARTY_KEYS\.length; i\+\+\)"
                           r"\s*\{[^}]*\}", "", code, flags=re.S)
    third_body = method_body(without_third, r"private\s+static\s+String\s+resolve\s*\(")
    check("⑤b 删掉第三方官方键循环后，④a 顺序判据判负",
          without_third != code and third_body is not None
          and not (third_body.find("LEGACY_PREFIX") < third_body.find("THIRD_PARTY_KEYS")
                   < third_body.find("derive(")))

    uncached = code.replace("final String hit = holder.names().get(recipeType);",
                            "final String hit = null;")
    uncached_body = method_body(uncached, r"public\s+static\s+String\s+of\s*\(")
    check("⑤c 让 of() 不查缓存后，③b 判据判负",
          uncached != code and uncached_body is not None
          and "names().get(recipeType)" not in uncached_body)


# ---------------------------------------------------------------------------
# ⑥ 语言文件
# ---------------------------------------------------------------------------
def lang_checks():
    zh, en = our_lang("zh_cn"), our_lang("en_us")
    dict_keys = [k for k in zh if k.startswith(DICT_PREFIX)]
    word_keys = [k for k in zh if k.startswith(WORD_PREFIX)]
    check("⑥a 词典键存在（%d 条）" % len(dict_keys), len(dict_keys) >= len(CBC_NEEDS_OUR_DICT))
    check("⑥b 单词表键存在（%d 条）" % len(word_keys), len(word_keys) >= 50)
    check("⑥c 词典键中英成对", all(k in en for k in dict_keys))
    check("⑥d 单词表键中英成对", all(k in en for k in word_keys))
    bad_zh = [k for k in dict_keys + word_keys if not CJK_RE.search(str(zh.get(k, "")))]
    bad_en = [k for k in dict_keys + word_keys if CJK_RE.search(str(en.get(k, "")))]
    check("⑥e 词典 / 单词的中文值都含中日韩文字（%d 条不合格）" % len(bad_zh), not bad_zh,
          str(bad_zh[:5]))
    check("⑥f 英文值不含中日韩文字（%d 条不合格）" % len(bad_en), not bad_en, str(bad_en[:5]))
    missing = [p for p in CBC_NEEDS_OUR_DICT
               if DICT_PREFIX + "createbigcannons." + p not in zh]
    check("⑥g 火炮那 12 条（自己没翻译的）都写进了我们的词典", not missing, str(missing))
    check("⑥h 语言里没有悬空的中英单边键（词典键）",
          all((k in zh) == (k in en) for k in set(zh) | set(en)) )


# ---------------------------------------------------------------------------
# ⑦ jar 证据
# ---------------------------------------------------------------------------
def jar_checks(jars):
    def first(names):
        return names[0] if names else None

    create_jar = first(jars["create"])
    if create_jar:
        zh = jar_lang(create_jar, "zh_cn")
        check("⑦a Create jar：存在 create.recipe.pressing（= 冲压），所以 Create 系配方类型是中文",
              zh.get("create.recipe.pressing") is not None, str(zh.get("create.recipe.pressing")))
        recipe_keys = [k for k in zh if k.startswith("create.recipe.")]
        check("⑦b Create jar：create.recipe.* 共 %d 条（本地化来源就是它们）" % len(recipe_keys),
              len(recipe_keys) >= 40)
    else:
        print("INFO ⑦a Create jar 不在本机缓存，记录的事实：%s" % (RECORDED_EVIDENCE["create"][1],))

    ca_jar = first(jars["createaddition"])
    if ca_jar:
        zh = jar_lang(ca_jar, "zh_cn")
        missing = [p for p in CREATE_ADDITION_TYPES
                   if zh.get("createaddition.recipe.%s" % p) is None]
        check("⑦c Create Addition jar：createaddition.recipe.{charging,liquid_burning,rolling} 都在"
              "（缺 %s）" % (missing or "无"), not missing)
        check("⑦d Create Addition jar：charging/liquid_burning/rolling 的中文值非空",
              all(str(zh.get("createaddition.recipe.%s" % p, "")).strip()
                  for p in CREATE_ADDITION_TYPES))
    else:
        print("INFO ⑦c Create Addition jar 不在本机，记录的事实：%s"
              % (RECORDED_EVIDENCE["createaddition"][1],))

    cbc_jar = first(jars["createbigcannons"])
    if cbc_jar:
        zh = jar_lang(cbc_jar, "zh_cn")
        check("⑦e 火炮 jar：有 recipe.createbigcannons.melting（所以 melting 那条是中文）",
              zh.get("recipe.createbigcannons.melting") is not None,
              str(zh.get("recipe.createbigcannons.melting")))
        offenders = []
        for path in CBC_NEEDS_OUR_DICT:
            for key in (("recipe.createbigcannons.%s" % path),
                        ("createbigcannons.recipe.%s" % path),
                        ("createbigcannons.recipe_type.%s" % path),
                        ("emi.category.createbigcannons.%s" % path),
                        ("jei.category.createbigcannons.%s" % path)):
                if zh.get(key) is not None:
                    offenders.append(key)
        check("⑦f 火炮 jar：那 %d 条装填 / 组装 / 引信 / 曳光弹类类型**一个键都没有**"
              "（所以只能由我们的词典兜底；意外命中的键：%s）" % (len(CBC_NEEDS_OUR_DICT), offenders or "无"),
              not offenders)
        check("⑦g 火炮 jar：zh_cn 键数 >= 700（它确实带了一整套中文，只是没覆盖配方类型）",
              len(zh) >= 700, "len=%d" % len(zh))
    else:
        print("INFO ⑦e 火炮 jar 不在本机，记录的事实：%s" % (RECORDED_EVIDENCE["createbigcannons"][1],))


# ---------------------------------------------------------------------------
# ⑧ GUI 侧单一入口
# ---------------------------------------------------------------------------
def gui_checks():
    for screen in PICKER_SCREENS:
        text = read_text(screen)
        # 两个界面各自的取名字写法不同（一个直接 `RecipeTypeNames::display`，一个先算机器图标再回退
        # 到 `RecipeTypeNames.of/display`），判据只要求「最终名字一定来自 RecipeTypeNames」。
        check("⑧a %s 的配方类型名字来自 RecipeTypeNames（没有第二套解析）"
              % os.path.basename(screen),
              any(token in text for token in ("RecipeTypeNames::display", "RecipeTypeNames.display(",
                                              "RecipeTypeNames.of(")))
    packet = read_text(PACKET_JAVA)
    check("⑧b 候选列表排序用 RecipeTypeNames.of（与界面显示同一套名字）",
          "RecipeTypeNames.of(" in packet)
    check("⑧c 候选列表枚举的是 BuiltInRegistries.RECIPE_TYPE（含别的模组）",
          "BuiltInRegistries.RECIPE_TYPE.keySet()" in packet)


# ---------------------------------------------------------------------------
# ⑨ 剩余英文清单（第 84 轮：可复跑、可指向真实整合包）
# ---------------------------------------------------------------------------
def mod_jars():
    """本机可枚举的全部模组 jar。

    默认 = 三个已知依赖 jar + `run/mods/*.jar` + `libs/*.jar`；
    设了环境变量 `RSCC_MODS_DIR=<整合包的 mods 目录>` 时把该目录（含子目录）里所有 jar 一并纳入 ——
    这样「用户实机还有哪些配方类型是英文」可以在**真实整合包**上复跑，不需要启动游戏。
    """
    jars = []
    for names in find_jars().values():
        jars += names
    for base in (os.path.join(ROOT, "run", "mods"), os.path.join(ROOT, "libs")):
        if os.path.isdir(base):
            jars += glob.glob(os.path.join(base, "*.jar"))
    extra = (os.environ.get(MODS_DIR_ENV) or "").strip()
    if extra and os.path.isdir(extra):
        jars += glob.glob(os.path.join(extra, "*.jar"))
        jars += glob.glob(os.path.join(extra, "**", "*.jar"), recursive=True)
    return [path for path in sorted(set(jars)) if is_valid_zip(path)]


def build_table_from(jar_list, locale):
    """与 {@link build_table} 同一条合并规则，但输入是「jar 列表」（可含任意模组）。"""
    table = {}
    for jar in jar_list:
        table.update(jar_lang(jar, "en_us"))
    table.update(our_lang("en_us"))
    if locale != "en_us":
        for jar in jar_list:
            table.update(jar_lang(jar, locale))
        table.update(our_lang(locale))
    return table


def english_residue_checks():
    """⑨ 打印并断言「仍然没有中文」的配方类型清单。"""
    jars = mod_jars()
    zh = build_table_from(jars, "zh_cn")
    known = collect_known(find_jars(), extra_jars=jars)
    residue, mixed = [], []
    for recipe_type, _source in known:
        name = resolve(zh, recipe_type)
        if not CJK_RE.search(name):
            residue.append((recipe_type, name))
        elif re.search(r"[A-Za-z]", name):
            mixed.append((recipe_type, name))
    print("    剩余英文枚举：%d 个配方类型（jar %d 个；RSCC_MODS_DIR=%s）"
          % (len(known), len(jars), os.environ.get(MODS_DIR_ENV) or "未设置"))
    for recipe_type, name in residue:
        print("      [纯英文] %-58s -> %s" % (recipe_type, name))
    for recipe_type, name in mixed:
        print("      [中英混排] %-56s -> %s" % (recipe_type, name))
    unexpected = [(r, n) for r, n in residue if r not in ENGLISH_ALLOWLIST]
    check("⑨a 剩余英文清单为空或只剩白名单（清单 %d 条 / 白名单 %d 条）"
          % (len(residue), len(ENGLISH_ALLOWLIST)), not unexpected, str(unexpected[:8]))
    check("⑨b 白名单里每一条都写明了原因",
          all(str(reason).strip() for reason in ENGLISH_ALLOWLIST.values()),
          str(ENGLISH_ALLOWLIST))
    # 可复跑性：清单里的 id 必须都能被本脚本重新枚举出来（不是手写死的）
    universe = set(r for r, _s in known)
    check("⑨c 清单来源可复跑（%d 个 id 全部来自内置表 / 词典 / jar 抓取）" % len(universe),
          len(universe) > 0 and all(":" in r for r in universe))
    return zh


# ---------------------------------------------------------------------------
# ⑩ 搜索三键（id / 中文名 / 英文名）
# ---------------------------------------------------------------------------
def fold_separators(text):
    """与 Java `RecipeTypeNames#foldSeparators` 逐字一致：`:_-.` 与空白 → 单个空格。"""
    folded = re.sub(r"[:_\-.]+", " ", text)
    return re.sub(r"\s+", " ", folded).strip()


def search_haystack(zh_table, recipe_type, aliases=None):
    """与 Java `RecipeTypeNames#searchText` 一致：id + 折叠 id + 折叠显示名 + 折叠英文别名，整串小写。"""
    aliases = EN_SEARCH_ALIASES if aliases is None else aliases
    parts = [
        recipe_type.lower(),
        fold_separators(recipe_type.lower()),
        fold_separators(resolve(zh_table, recipe_type).lower()),
    ]
    alias = aliases.get(recipe_type)
    if alias:
        parts.append(fold_separators(alias.lower()))
    return " ".join(part for part in parts if part)


def search_match(zh_table, recipe_type, query, aliases=None, casefold=True):
    """与 Java `RecipeTypeNames#matchesSearch` 一致（casefold=False 只用于反例自证）。"""
    needle = query.strip()
    if casefold:
        needle = needle.lower()
    if not needle:
        return True
    haystack = search_haystack(zh_table, recipe_type, aliases)
    if needle in haystack:
        return True
    folded = fold_separators(needle)
    return folded != needle and folded in haystack


# 合成 id：证明「派生出来的名字」（我们没有为它写任何词典 / 官方键）也能被搜到。
SEARCH_SYNTHETIC_IDS = ("unknownmod:freezing", "unknownmod:sanding", "unknownmod:laser_welding")

# (查询串, 必须命中的类型 id, 这条覆盖哪一种键)
SEARCH_CASES = (
    ("create:pressing", "create:pressing", "id 原样（带冒号）"),
    ("createbigcannons:big_cartridge_filling", "createbigcannons:big_cartridge_filling", "id 原样"),
    ("pressing", "create:pressing", "英文名（id 词）"),
    ("PRESSING", "create:pressing", "大小写不敏感"),
    ("冲压", "create:pressing", "中文名"),
    ("大型弹药装填", "createbigcannons:big_cartridge_filling", "中文名（我们词典）"),
    ("big cartridge", "createbigcannons:big_cartridge_filling", "英文名（空格分隔）"),
    ("big_cartridge", "createbigcannons:big_cartridge_filling", "id 片段（下划线）"),
    ("冷冻", "unknownmod:freezing", "派生中文名"),
    ("freezing", "unknownmod:freezing", "派生英文（id 词）"),
    ("draining", "create:emptying", "官方英文别名（id 里没有这个词）"),
    ("sequence", "create:sequenced_assembly", "官方英文别名"),
    ("basin melting", "createbigcannons:melting", "官方英文别名"),
    ("机械手", "createbigcannons:big_cartridge_filling_deployer", "中文（词典括号里的词）"),
    ("充能", "createaddition:charging", "第三方官方中文"),
)


def java_search_aliases():
    """从 Java 源码解析 `EN_SEARCH_ALIASES` 这张表（保证脚本模型与实现同一份数据）。"""
    code = strip_comments(read_text(NAMES_JAVA))
    start = code.find("EN_SEARCH_ALIASES")
    if start < 0:
        return {}
    end = code.find(");", start)
    block = code[start:end if end > 0 else len(code)]
    return dict(re.findall(r'"([^"\s]+:[^"\s]+)"\s*,\s*"([^"]+)"', block))


def search_universe(jars):
    return sorted(set([r for r, _s in collect_known(jars)]) | set(SEARCH_SYNTHETIC_IDS))


def search_checks(tables, jars):
    """⑩ 复算「查询串 → 命中集合」，逐条断言期望项在里面。"""
    zh = tables["zh_cn"]
    universe = search_universe(jars)
    print("    搜索模型：候选 %d 个（含合成 id %d 个）" % (len(universe), len(SEARCH_SYNTHETIC_IDS)))

    failures = []
    for query, expected, label in SEARCH_CASES:
        hits = [recipe_type for recipe_type in universe if search_match(zh, recipe_type, query)]
        if expected not in hits:
            failures.append("%s [%s] 未命中 %s" % (query, label, expected))
    check("⑩a 搜索样例 %d 条全部命中（id / 中文名 / 英文名 / 派生名）" % len(SEARCH_CASES),
          not failures, "; ".join(failures))

    # 同一个类型：三种键都必须命中
    three = [query for query in ("create:pressing", "冲压", "Pressing")
             if "create:pressing" in [r for r in universe if search_match(zh, r, query)]]
    check("⑩b 同一类型三种键都能命中（id / 中文名 / 英文名）：%s" % three, len(three) == 3)

    # 大小写不敏感：同一查询的大小写变体命中集合必须相同
    variants = [set(r for r in universe if search_match(zh, r, q))
                for q in ("pressing", "PRESSING", "Pressing")]
    check("⑩c 大小写不影响命中集合", variants[0] == variants[1] == variants[2])

    # 无匹配时为空（不是「全命中」）
    empty_hits = [r for r in universe if search_match(zh, r, "zzzz_not_a_recipe_type")]
    check("⑩d 无匹配查询返回空集合（%d 条）" % len(empty_hits), not empty_hits)

    # 空查询 = 全部命中（打开界面时与旧行为一致）
    all_hits = [r for r in universe if search_match(zh, r, "   ")]
    check("⑩e 空查询命中全部候选（%d/%d）" % (len(all_hits), len(universe)),
          len(all_hits) == len(universe))

    # Java 与脚本同一张英文别名表
    java_aliases = java_search_aliases()
    check("⑩f Java 的 EN_SEARCH_ALIASES 与脚本表逐条一致（Java %d 条）" % len(java_aliases),
          java_aliases == EN_SEARCH_ALIASES,
          "java=%s script=%s" % (sorted(java_aliases.items()), sorted(EN_SEARCH_ALIASES.items())))

    # Java 侧 API/缓存存在（搜索不是靠界面自己算的）
    code = strip_comments(read_text(NAMES_JAVA))
    check("⑩g RecipeTypeNames 提供 searchText / matchesSearch 且都走缓存",
          "public static String searchText(" in code
          and "public static boolean matchesSearch(" in code
          and "foldSeparators(" in code
          and "searches()" in code)
    check("⑩h 搜索串整串折成小写（否则英文别名 'Item Draining' 永远搜不到）",
          re.search(r"toLowerCase\(Locale\.ROOT\)\.trim\(\)", code) is not None)


# ---------------------------------------------------------------------------
# ⑪ 搜索控件是复用的（源码级）
# ---------------------------------------------------------------------------
def java_files():
    found = []
    for base, _dirs, files in os.walk(JAVA_ROOT):
        for name in files:
            if name.endswith(".java"):
                found.append(os.path.join(base, name))
    return sorted(found)


def select_screens():
    return list(PICKER_SCREENS)


def render_filter_free(sources):
    """判据：每个界面的 `render()` 里都不出现过滤调用（过滤只在 responder / 候选变化时跑）。"""
    for path, text in sources.items():
        body = method_body(strip_comments(text), r"public\s+void\s+render\s*\(")
        if body is None:
            continue
        if any(token in body for token in ("matchesSearch(", "rebuild()", "applyFilter(")):
            return False
    return True


def single_impl(sources):
    """判据：除了共享控件 `RecipeTypeSelect`，没有第二处搜索实现。"""
    for path, text in sources.items():
        if os.path.abspath(path) == os.path.abspath(SELECT_WIDGET):
            continue
        code = strip_comments(text)
        if "RecipeTypeNames.matchesSearch(" in code:
            return False
        # 界面里手写「遍历候选 + 小写 + contains」也算第二套（反例自证会喂这种写法）
        if re.search(r"for\s*\([^)]*\)\s*\{[^}]*toLowerCase\([^)]*\)\.contains\(", code, re.S):
            return False
    return True


def shared_widget_ok(text):
    """判据：共享控件确实是「滚轮选择 + 搜索框 + 唯一过滤实现」。"""
    code = strip_comments(text)
    return ("new ScrollSelectWidget" in code
            and "new EditBox" in code
            and "setResponder(" in code
            and "RecipeTypeNames.matchesSearch(" in code
            and "private void rebuild()" in code)


def reuse_checks():
    widget = read_text(SELECT_WIDGET)
    check("⑪a 共享控件 RecipeTypeSelect = ScrollSelectWidget + EditBox + 唯一过滤实现",
          shared_widget_ok(widget))
    check("⑪b 共享控件把「当前项机器图标」交给 ScrollSelectWidget#withIcon（一处实现）",
          "withIcon(" in widget and "withIcon(" in read_text(SCROLL_SELECT))
    screens = {path: read_text(path) for path in select_screens()}
    missing = [os.path.basename(p) for p, text in screens.items() if "new RecipeTypeSelect(" not in text]
    check("⑪c 两个配方类型下拉框界面都复用 RecipeTypeSelect（缺：%s）" % (missing or "无"), not missing)
    no_icon = [os.path.basename(p) for p, text in screens.items() if "iconFor(" not in text]
    check("⑪d 两个界面都把机器图标接到同一个控件（缺：%s）" % (no_icon or "无"), not no_icon)
    callers = [os.path.relpath(path, ROOT) for path in java_files()
               if "RecipeTypeNames.matchesSearch(" in strip_comments(read_text(path))]
    check("⑪e 全工程只有共享控件调用 matchesSearch（实际：%s）"
          % [os.path.basename(c) for c in callers],
          len(callers) == 1 and os.path.abspath(callers[0]) == os.path.abspath(SELECT_WIDGET))
    check("⑪f 两个界面的 render() 里没有过滤调用（不每帧重算）", render_filter_free(screens))
    machine_selector = os.path.join(PKG, "client", "screen", "StepMachineSelectScreen.java")
    machine_text = read_text(machine_selector)
    check("⑪g 机器选择器（另一个功能的搜索）没有被改成配方搜索",
          "RecipeTypeSelect" not in machine_text and "matchesSearch" not in machine_text)
    # 判据只作用于「承载配方类型下拉框的界面」：工程里另有机器名搜索（StepMachineSelectScreen）
    # 与物品名搜索（UnitPatternManagerScreen），它们是**别的功能**，不属于「第二套配方类型搜索」。
    check("⑪h 两个配方类型界面里没有第二套搜索实现", single_impl(screens))


# ---------------------------------------------------------------------------
# ⑫ 反例自证
# ---------------------------------------------------------------------------
def counterexample_checks(tables, jars):
    zh = tables["zh_cn"]
    universe = search_universe(jars)

    # (a) 只搜中文名：英文查询必须判负
    def zh_only_hits(query):
        needle = query.strip().lower()
        return [r for r in universe
                if needle in fold_separators(resolve(zh, r).lower())]

    check("⑫a 「只搜中文名」的写法判负（'pressing' 不再命中 create:pressing）",
          "create:pressing" not in zh_only_hits("pressing"),
          str(zh_only_hits("pressing")[:5]))

    # (b) 区分大小写：'PRESSING' 必须判负
    sensitive = [r for r in universe if search_match(zh, r, "PRESSING", casefold=False)]
    check("⑫b 「区分大小写」的写法判负（'PRESSING' 不再命中）",
          "create:pressing" not in sensitive, str(sensitive[:5]))

    # (c) 每帧全量重算：把 rebuild() 塞进 render() 后，判据必须判负
    screen_path = select_screens()[0]
    screen = read_text(screen_path)
    marker = "        syncRecipeCandidates();"
    mutated = screen.replace(marker, marker + "\n        recipeSelect.rebuild();", 1)
    check("⑫c 「render 里每帧过滤」的写法判负",
          mutated != screen and not render_filter_free({screen_path: mutated}),
          "注入点缺失" if mutated == screen else "")

    # (d) 界面里手写第二套搜索：判据必须判负
    hand_rolled = screen.replace(
        marker,
        marker + "\n        for (String id : lastCandidates) {\n"
                 "            if (id.toLowerCase(Locale.ROOT).contains(\"x\")) { break; }\n        }",
        1)
    check("⑫d 「界面里手写第二套搜索」的写法判负",
          hand_rolled != screen and not single_impl({screen_path: hand_rolled}),
          "注入点缺失" if hand_rolled == screen else "")

    # (e) 反例自证自身也要有正例：原样源码必须判正
    check("⑫e 原样源码在 ⑪f / ⑪h 两条判据下判正",
          render_filter_free({screen_path: screen}) and single_impl({screen_path: screen}))


# ---------------------------------------------------------------------------
# ⑬ 机器图标兜底（同命名空间方块）
# ---------------------------------------------------------------------------
def path_tokens(path):
    return [word for word in re.split(r"[_\-.]+", path.lower()) if word]


def contiguous_run(words, wanted):
    """`wanted` 是否是 `words` 的一段连续词序列（与 Java `containsContiguous` 一致）。"""
    if not wanted or len(words) < len(wanted):
        return False
    return any(words[i:i + len(wanted)] == wanted
               for i in range(len(words) - len(wanted) + 1))


def sibling_block_pick(namespace, recipe_path, blocks, as_substring=False):
    """复算 Java `siblingBlockMachines` 的选法：同命名空间 + 整词连续 + 取最短。"""
    wanted = path_tokens(recipe_path)
    best, best_words = None, None
    for candidate in blocks:
        words = path_tokens(candidate)
        matched = (recipe_path.lower() in candidate.lower()) if as_substring \
            else contiguous_run(words, wanted)
        if not matched:
            continue
        if best is None or len(words) < len(best_words) \
                or (len(words) == len(best_words) and candidate < best):
            best, best_words = candidate, words
    return best


def icon_fallback_checks():
    code = strip_comments(read_text(MACHINES_JAVA))
    check("⑬a 机器反查有「同命名空间方块」兜底，且用懒构建的命名空间索引",
          "siblingBlockMachines(" in code and "namespaceBlocks()" in code
          and "containsContiguous(" in code and "pathTokens(" in code)
    body = method_body(code, r"public static List<Machine> forRecipeType\(") or ""
    order = [body.find(token) for token in (
        "explicitTable()", "symbolsFromRecipes(", "JeiCatalystMachines.catalystsFor(",
        "DEPLOYER_SUFFIX", "siblingBlockMachines(")]
    check("⑬b 五条来源顺序正确（显式表 → toast → JEI → _deployer → 同命名空间兜底）",
          all(0 <= a < b for a, b in zip(order, order[1:])), str(order))
    empty_guard = body.rfind("if (machines.isEmpty())", 0, body.find("siblingBlockMachines("))
    check("⑬c 兜底只在结果为空时执行（不覆盖已有优先级）", empty_guard > 0)

    # 规则复算：整词连续 vs 纯子串（反例）
    blocks = ["freezing", "freezing_chamber", "freezing_chamber_lid", "freezing_pipe"]
    exact = sibling_block_pick("create_dd", "freezing", blocks)
    check("⑬d 整词连续规则挑出最短的方块（%s）" % exact, exact == "freezing")
    long_path = sibling_block_pick("create_dd", "freezing_chamber", blocks)
    check("⑬e 长路径匹配更具体的方块（%s）" % long_path, long_path == "freezing_chamber")
    # 「纯子串」会把缩写 id（big_cart）抢到 big_cartridge_filling；「整词连续」不会 —— 这就是反例自证。
    substring = sibling_block_pick("createbigcannons", "big_cart", ["big_cartridge_filling"],
                                   as_substring=True)
    word_rule = sibling_block_pick("createbigcannons", "big_cart", ["big_cartridge_filling"])
    check("⑬f 反例：纯子串匹配会把 big_cart 抢到 big_cartridge_filling（%s），"
          "整词连续规则不会（%s）" % (substring, word_rule),
          substring == "big_cartridge_filling" and word_rule is None)


# ---------------------------------------------------------------------------
def main():
    print("=" * 78)
    print("第 78 轮：配方类型显示名本地化（含别的模组）+ 第 84 轮：剩余英文 / 搜索 / 复用")
    print("=" * 78)
    jars = find_jars()
    print("语言来源：本模组 lang + %s"
          % ", ".join("%s=%d" % (ns, len(v)) for ns, v in jars.items()))
    tables = behavioural_checks(jars)
    source_checks()
    lang_checks()
    jar_checks(jars)
    gui_checks()
    english_residue_checks()
    search_checks(tables, jars)
    reuse_checks()
    counterexample_checks(tables, jars)
    icon_fallback_checks()
    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] 第 78/84 轮「配方类型本地化 + 搜索」：%d 项断言全部通过" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
