# -*- coding: utf-8 -*-
"""语言键审计（只读）：① zh/en 成对与空值；② 源码静态引用的键是否都存在；③ 孤儿键。

引用来源（全部是工程内的真实来源，不靠人工维护清单）：
  * `src/main/java/**/*.java` 与 `src/main/resources/**` 里的字符串字面量；
  * 以 "." 结尾的字面量按**动态前缀**处理（如 `"machine.rs_create_compat." + name`），
    匹配该前缀的全部语言键视为被引用；
  * 本模组注册名（`ITEMS/BLOCKS.register("x")`）推导 `block.` / `item.` 键。

用法：python tools/audit_lang_keys.py
末行固定为 `问题总数: N`。
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


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "rs_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", MODID, "lang")
MOD_JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat",
                        "RS_Create_Compat.java")
SCAN_DIRS = (os.path.join(ROOT, "src", "main", "java"),
             os.path.join(ROOT, "src", "main", "resources"),
             os.path.join(ROOT, "src", "main", "templates"))

# 语言键的「头段」白名单：只有这些头段才可能是本模组的语言键。
# `jei` 段（第 129 轮加）：JEI 配方分类的标题 / 文案键写成
# `jei.rs_create_compat.<分类>.<名>`（JEI 生态的通行写法），它们同样是本模组语言文件里的键，
# 不登记就会被建成「孤儿键」。见 client/DecompositionGeneratorJeiCategory 与 JeiPlugin。
HEADS = ("block", "item", "gui", "message", "container", "jade", "advancements", "machine", "key",
         "jei")
# 框架规定的固定形状键（不是 <头段>.<modid>.<名> 三段的写法）。
FIXED_KEYS = ("itemGroup." + MODID, "key.categories." + MODID, "config.jade.plugin_" + MODID + ".")
LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# 中日韩字符（含全角标点）：用来判断「中文值到底有没有中文」。
CJK_RE = re.compile(r"[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]")
# 格式化占位符（%s / %1$s / %d）。
FORMAT_RE = re.compile(r"%(\d+\$)?[sd]")
# 纯 ASCII 的中文值里<b>允许</b>出现的词：单位 / 修饰键名（这些词本来就不该翻译）。
ASCII_ALLOWED_TOKENS = {
    "mB": "毫桶（流体单位），业界通用写法",
    "Alt": "修饰键名（与游戏内键位同名）",
    "Ctrl": "修饰键名",
    "Shift": "修饰键名",
    "FE": "Forge Energy 单位",
    "RS": "模组缩写（Refined Storage）",
}

# 框架自己按约定拼出来、工程里没有字面量的键：白名单（附来源说明）。
WHITELIST = {
    "config.jade.plugin_" + MODID + ".camouflage":
        "Jade 依插件 UID（rs_create_compat:camouflage）自动拼出的配置键，见 client/jade/RsccJadePlugin",
    "config.jade.plugin_" + MODID + ".bus":
        "Jade 依插件 UID（rs_create_compat:bus）自动拼出的配置键，见 client/jade/RsccBusJadeProvider",
    "config.jade.plugin_" + MODID + ".bus_data":
        "Jade 依服务端数据 UID（rs_create_compat:bus_data）自动拼出的配置键，"
        "见 compat/jade/RsccBusJadeServerData",
    "config.jade.plugin_" + MODID + ".receiver":
        "Jade 依插件 UID（rs_create_compat:receiver）自动拼出的配置键，"
        "见 client/jade/RsccReceiverJadeProvider",
    "config.jade.plugin_" + MODID + ".receiver_data":
        "Jade 依服务端数据 UID（rs_create_compat:receiver_data）自动拼出的配置键，"
        "见 compat/jade/RsccReceiverJadeServerData",
    # 定量保持器的「单位」键：基础界面已不再单独绘制单位文字（QuantityKeeperScreen#renderLabels
    # 有注释说明），但 tools/selfcheck_round50_keeper_fluid_display.py 直接读它们做输入框几何宽度
    # 基准（unit_w = max(width(unit_item), width(unit_mb))），属语言契约的一部分，不删。
    "gui.rs_create_compat.quantity_keeper.unit_item":
        "定量保持器单位键（selfcheck_round50 的几何宽度基准读取，不绘制）",
    "gui.rs_create_compat.quantity_keeper.unit_mb":
        "定量保持器单位键（selfcheck_round50 的几何宽度基准读取，不绘制）",
}

# 英文值里**允许**出现中文的键（附来源说明）。目前只有一条：
# 本模组没有官方英文名（neoforge.mods.toml 的 displayName 就是中文名），
# 根成就标题按工程既有断言必须等于模组显示名（tools/selfcheck_advancements.py 明确检查）。
EN_CJK_ALLOWED_KEYS = {
    "advancements.rs_create_compat.root.title":
        "根成就标题 = 模组显示名（模组无官方英文名；selfcheck_advancements 断言中英一致）",
}


def is_lang_key(text):
    if text in FIXED_KEYS:
        return True
    if any(text.startswith(fixed) for fixed in FIXED_KEYS if fixed.endswith(".")):
        return True
    return any(text.startswith(head + "." + MODID + ".") for head in HEADS)


def ascii_tokens_without_placeholder(value):
    """把格式化占位符与颜色码去掉后，剩余的「ASCII 单词」（用于判纯 ASCII 占位）。"""
    cleaned = FORMAT_RE.sub(" ", str(value))
    cleaned = re.sub(r"\u00a7.", " ", cleaned)  # §a 这类颜色/格式码
    return [word for word in re.findall(r"[A-Za-z]+", cleaned)]


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def iter_files():
    for root_dir in SCAN_DIRS:
        for base, _dirs, files in os.walk(root_dir):
            if os.path.abspath(base) == os.path.abspath(LANG_DIR):
                continue  # 语言文件自身不算「引用」
            for name in files:
                if name.endswith((".java", ".json", ".toml", ".mcmeta")):
                    yield os.path.join(base, name)


def scan_literals():
    """返回 (完整键集合, 前缀集合)。"""
    full, prefixes = set(), set()
    for path in iter_files():
        with io.open(path, encoding="utf-8", errors="ignore") as handle:
            text = handle.read()
        for match in LITERAL_RE.finditer(text):
            raw = match.group(1)
            if MODID not in raw:
                continue
            if raw.endswith(".") and is_lang_key(raw):
                prefixes.add(raw)
            elif is_lang_key(raw):
                full.add(raw)
    return full, prefixes


def registry_ids():
    with io.open(MOD_JAVA, encoding="utf-8") as handle:
        text = handle.read()
    ids = set()
    for match in re.finditer(r'\b(?:ITEMS|BLOCKS)\.register\(\s*"([^"]+)"', text):
        ids.add(match.group(1))
    return ids


def main():
    zh, en = load("zh_cn.json"), load("en_us.json")
    problems, notes = [], []

    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    for key in only_zh:
        problems.append("仅 zh_cn 有该键：%s" % key)
    for key in only_en:
        problems.append("仅 en_us 有该键：%s" % key)

    for key in sorted(set(zh) & set(en)):
        for field, value in (("zh_cn", zh[key]), ("en_us", en[key])):
            if not str(value).strip():
                problems.append("%s 值为空：%s" % (field, key))

    # 2026-10-17（第 74 轮）：中文值不能是「纯 ASCII 占位」——本轮用户实机反馈的同类坑。
    # 判据：去掉 %s/%1$s 与 § 颜色码后，若仍出现 ASCII 单词，则每个单词都必须是
    # 单位/键名这类「本来就不翻译」的白名单词（mB / Alt / Ctrl / Shift / FE / RS）。
    for key in sorted(zh):
        value = str(zh[key])
        if CJK_RE.search(value):
            continue
        bad = [word for word in ascii_tokens_without_placeholder(value)
               if word not in ASCII_ALLOWED_TOKENS]
        if bad:
            problems.append("zh_cn 值没有中文（疑似英文占位）：%s = %r（可疑词：%s）"
                            % (key, value, ", ".join(sorted(set(bad)))))

    # 反向同理：英文值里出现中日韩字符 ⇒ 英文玩家会看到中文（本轮实测 root 成就标题就是这样）。
    # 唯一豁免：模组显示名（无官方英文名，见 EN_CJK_ALLOWED_KEYS 的说明）。
    for key in sorted(en):
        if key in EN_CJK_ALLOWED_KEYS:
            continue
        value = str(en[key])
        if CJK_RE.search(value):
            problems.append("en_us 值含中日韩字符（英文玩家会看到中文）：%s = %r" % (key, value))

    full, prefixes = scan_literals()
    referenced = set(k for k in (full & set(zh)) if k)
    for prefix in prefixes:
        for key in zh:
            if key.startswith(prefix):
                referenced.add(key)
    for ident in registry_ids():
        for head in ("block", "item"):
            candidate = "%s.%s.%s" % (head, MODID, ident)
            if candidate in zh:
                referenced.add(candidate)

    # 静态引用（完整键）必须存在
    for key in sorted(full):
        if key not in zh:
            problems.append("源码引用了语言里不存在的键：%s" % key)

    orphans = sorted(set(zh) - referenced - set(WHITELIST))
    if orphans:
        notes.append("孤儿键（语言里存在但源码未引用）%d 条" % len(orphans))
        for key in orphans:
            notes.append("  - %s" % key)
    else:
        notes.append("孤儿键：0 条")

    print("键数：zh_cn %d / en_us %d（一致：%s）" % (len(zh), len(en), not only_zh and not only_en))
    print("静态引用：完整键 %d 个 / 动态前缀 %d 个；推导引用键 %d 个"
          % (len(full), len(prefixes), len(referenced)))
    print("动态前缀：%s" % sorted(prefixes))
    print("白名单（框架拼出的键）%d 条：" % len(WHITELIST))
    for key, why in sorted(WHITELIST.items()):
        print("  - %s（%s）" % (key, why))
    print("\n提示：")
    for note in notes:
        print(note)
    if problems:
        print("\n问题清单：")
        for problem in problems:
            print("  - %s" % problem)
    else:
        print("\n问题清单：无")
    print("\n问题总数: %d" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
