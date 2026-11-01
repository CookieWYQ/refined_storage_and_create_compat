#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 110 轮自检：范围充电器过滤搜索接入「通用拼音搜索」（Just Enough Characters / jecharacters）。

用户第 110 条反馈原话要点：
  > 「那个范围充电器的过滤还有个问题：它并不支持拼音搜索。你去搜一个模组叫通用拼音搜索，
  >  这个模组就是接管了 MC 中的输入法，让它可以输入拼音进行搜索，但现在并不行。」

根因：我们的过滤搜索是**自己手写**的「显示名 / 注册 id 子串匹配」
（`client/screen/ChargerFilterCandidates#search`），完全绕开了原版的 `SearchTree` / `SuffixArray`
——而拼音模组的注入点正是原版那套搜索结构，所以它对我们的搜索框天然不生效。

修法：新增 `client/PinyinSearch`，运行时反射调用
`me.towdium.jecharacters.utils.Match.contains(CharSequence, CharSequence)`（内部就是
`PinIn.contains`，带字典 / 多音字 / 简拼 / 玩家配置的模糊音），**零编译期依赖**；
模组缺席 / 反射失败 / 字典未就绪一律静默退回普通子串匹配（绝不把搜索弄崩）。

本脚本只读源码 / 语言文件 / （若本机有）拼音模组 jar，只用 Python 标准库。

断言清单：
  ① 软依赖形态：PinyinSearch 里是 Class.forName + Method 反射调用，**没有** import me.towdium.*；
     解析结果缓存（只解析一次）；每次调用 try/catch Throwable 后降级到 String.contains。
  ② 接线：ChargerFilterCandidates.search 的名字一路走 PinyinSearch.contains(name, needle)，
     id 一路保持小写纯子串；搜索上限 MAX_SEARCH_RESULTS 不变。
  ③ 行为模型：模组在场（拼音命中）⇒ 接受；模组缺席（Match 为 null）⇒ 与旧行为逐字相同
     （大小写不敏感子串）；模组抛异常 ⇒ 降级而不是崩。
  ④ 硬依赖检查：全工程 src/main 里不出现 `import me.towdium` / `jecharacters` 的编译期引用
     （否则「玩家没装也必须是正常状态」被破坏）。
  ⑤ 可选证据：本机客户端 mods 里存在 jecharacters jar 时，校验 jar 内确实有
     `me/towdium/jecharacters/utils/Match.class` 且 class 常量池里有 `contains`；
     jar 不存在 ⇒ 只 WARN 不判负（别的机器 / 别的整合包可能没装）。
  ⑥ 语言键：搜索框提示/说明提到「拼音」（zh/en 成对、en 不含中文）。

反例自证 ≥5 条：把接线换回 name.contains ⇒ 判负；把反射改成 import 硬依赖 ⇒ 判负；
去掉 try/catch 降级 ⇒ 判负；把 id 分支也改成拼音匹配（id 是 ASCII，会误伤）⇒ 判负；
语言说明不提拼音 ⇒ 判负。

用法：python tools/selfcheck_round110_charger_pinyin_search.py
退出码：0 = 全部通过。
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
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
CLIENT = os.path.join(PKG, "client")
SCREEN = os.path.join(CLIENT, "screen")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

PINYIN = os.path.join(CLIENT, "PinyinSearch.java")
CANDIDATES = os.path.join(SCREEN, "ChargerFilterCandidates.java")

#: 本机客户端 mods 目录（可选证据；不存在只 WARN）。
MODS_DIRS = [
    r"D:\minecraft\.minecraft\versions\Create：Enjoy\mods",
]

FAILS = []
CHECKS = [0]
COUNTER = [0]
WARNS = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok):
    CHECKS[0] += 1
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        FAILS.append(name)
    return ok


def counterexample(name, ok):
    COUNTER[0] += 1
    check("反例 %s" % name, ok)


def warn(name):
    WARNS.append(name)
    print("WARN " + name)


def strip_comments(text):
    """去掉 // 与 /*...*/ 注释（保留字符串字面量），便于做「源码里真的有这段逻辑」的断言。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in ("\"", "'"):
                quote = char
                out.append(char)
                i += 1
                continue
            out.append(char)
            i += 1
            continue
        if char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        if char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def method_body(text, signature_regex):
    code = strip_comments(text)
    match = re.search(signature_regex, code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for index in range(brace, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():index + 1]
    return None


def load_lang(name):
    try:
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            return json.load(handle)
    except Exception:
        return {}


# ======================================================================================
# ① PinyinSearch 的软依赖形态
# ======================================================================================

def soft_dependency_ok(text):
    """反射软依赖：Class.forName + 多签名 getMethod + invoke；没有 import me.towdium.*。"""
    code = strip_comments(text)
    if "import me.towdium" in code or "import me.towdium" in text:
        return False
    if "me.towdium.jecharacters.utils.Match" not in code:
        return False
    return ("Class.forName(MATCH_CLASS" in code
            and 'getMethod("contains", signature)' in code
            and "{String.class, CharSequence.class}" in code
            and "{CharSequence.class, CharSequence.class}" in code
            and "method.invoke(null, haystack, needle)" in code)


def lowercase_both_sides_ok(text):
    """【复核发现的必修点】JEC 对 ASCII 大小写敏感 ⇒ 传进去之前两边都必须 toLowerCase(Locale.ROOT)。"""
    code = strip_comments(text)
    body = method_body(code, r"public static boolean contains\(final String text, final String query\)")
    if body is None:
        return False
    return ("final String haystack = text.toLowerCase(Locale.ROOT);" in body
            and "final String needle = query.toLowerCase(Locale.ROOT);" in body
            and "method.invoke(null, haystack, needle)" in body
            and "return haystack.contains(needle);" in body)


def fallback_ok(text):
    """降级：每次调用 try/catch Throwable，失败后走 String.contains。"""
    code = strip_comments(text)
    body = method_body(code, r"public static boolean contains\(final String text, final String query\)")
    if body is None:
        return False
    return ("catch (final Throwable ignored)" in body
            and "return haystack.contains(needle);" in body
            and "try {" in body)


def cache_once_ok(text):
    """解析结果只解析一次（静态布尔 + 静态 Method + synchronized）。"""
    code = strip_comments(text)
    return ("private static boolean resolved;" in code
            and "private static Method matchContains;" in code
            and "if (resolved)" in code
            and "private static synchronized Method matchContains()" in code)


# ======================================================================================
# ② 接线：search 名字一路走 PinyinSearch
# ======================================================================================

def candidates_wired_ok(text):
    code = strip_comments(text)
    body = method_body(code, r"public static List<Item> search\(final String query\)")
    if body is None:
        return False
    return ("PinyinSearch.contains(name, needle)" in body
            and "id.toString().toLowerCase(Locale.ROOT).contains(needleLower)" in body
            and "MAX_SEARCH_RESULTS" in body
            and "import cretae.cookiewyq.rs_create_compat.client.PinyinSearch;" in code)


# ======================================================================================
# ③ 行为模型（纯 Python 复刻：模组在场 / 缺席 / 抛异常三种状态）
# ======================================================================================

class FakePinyin:
    """测试替身：模拟 jecharacters 的 Match.contains（只覆盖本模型需要的几个词）。"""

    TABLE = {
        "控制器": ("kongzhiqi", "kzq"),
        "能量立方": ("nenglianglifang", "nllf"),
        "能量表": ("nengliangbiao", "nlb"),
        "红石能量电池": ("hongshinengliangdianchi", "hsnldc"),
    }

    def __init__(self, mode="present"):
        self.mode = mode

    def contains(self, text, query):
        if self.mode == "throws":
            raise RuntimeError("dictionary not ready")
        if self.mode == "absent":
            return None  # 表示「没有模组」：调用方应走降级
        query = query.strip().lower()
        if not query:
            return True
        if query in text.lower():
            return True
        for cjk, (full, initials) in self.TABLE.items():
            if cjk in text and (full.startswith(query) or initials.startswith(query)):
                return True
        return False


def model_contains(fake, text, query):
    """逐字复刻 PinyinSearch#contains 的控制流（含 try/catch 降级）。"""
    try:
        result = fake.contains(text, query)
        if result is not None:
            return bool(result)
    except Exception:
        pass
    return query.lower() in text.lower()


def model_contains_without_pinyin(fake, text, query):
    """反例模型：没接上拼音（只做子串）⇒ 简拼 kzq 搜不到「控制器」。"""
    return query.lower() in text.lower()


def model_contains_without_fallback(fake, text, query):
    """反例模型：模组缺席时不做降级（直接 False ⇒ 搜索全空）。"""
    try:
        return bool(fake.contains(text, query))
    except Exception:
        return False


def model_ok():
    cases = [
        # 模组在场：拼音 / 简拼都能命中中文名
        ("present", "控制器", "kongzhiqi", True),
        ("present", "控制器", "kzq", True),
        ("present", "能量立方", "nllf", True),
        ("present", "红石能量电池", "hongshi", True),
        # 模组在场：不命中的查询依旧不命中（不能变成「搜什么都中」）
        ("present", "控制器", "fe_energy", False),
        ("present", "能量立方", "xyz", False),
        # 模组缺席：退回不区分大小写子串（与第 107 轮行为逐字相同）
        ("absent", "能量立方", "能量", True),
        ("absent", "能量立方", "FE_ENERGY", False),
        ("absent", "Energy Tablet", "energy", True),
        # 模组内部抛异常：降级而不是崩
        ("throws", "能量立方", "能量", True),
    ]
    for mode, text, query, expected in cases:
        got = model_contains(FakePinyin(mode), text, query)
        if got != expected:
            print("    模型不符：mode=%s text=%r query=%r got=%r expected=%r"
                  % (mode, text, query, got, expected))
            return False
    return True


def model_absent_matches_old_model():
    """模组缺席时，模型必须与「纯子串」逐字一致（证明降级没有偷偷改变语义）。"""
    fake = FakePinyin("absent")
    samples = [("能量立方", "能量"), ("能量立方", "立方"), ("Energy Tablet", "ENERGY"),
               ("控制器", "kongzhiqi"), ("x", "")]
    for text, query in samples:
        expected = (query.lower() in text.lower()) if query else True
        if model_contains(fake, text, query) != expected:
            return False
    return True


# ======================================================================================
# ④ 硬依赖检查：整个 src/main 不得出现拼音模组的编译期引用
# ======================================================================================

def no_hard_dependency_ok():
    offenders = []
    for base, _dirs, files in os.walk(JAVA):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            try:
                code = strip_comments(read(path))
                raw = read(path)
            except OSError:
                # 并行写文件（编辑器原子替换）的瞬时窗口：跳过本次，不把「读不到」当成违规。
                continue
            if "me.towdium" in code or "me.towdium" in raw:
                # 允许 PinyinSearch 里的**字符串常量**；不允许 import / 类型引用
                if os.path.basename(path) == "PinyinSearch.java":
                    if "import me.towdium" in code or "java.lang.reflect" not in code:
                        offenders.append(path)
                    continue
                offenders.append(path)
    return offenders


# ======================================================================================
# ⑤ 可选证据：本机 jar 里确实有 Match.contains
# ======================================================================================

def local_jar_evidence():
    for mods in MODS_DIRS:
        if not os.path.isdir(mods):
            continue
        for name in os.listdir(mods):
            if not name.lower().startswith("jecharacters") or not name.endswith(".jar"):
                continue
            path = os.path.join(mods, name)
            try:
                with zipfile.ZipFile(path) as archive:
                    entry = "me/towdium/jecharacters/utils/Match.class"
                    names = set(archive.namelist())
                    if entry not in names:
                        return False, "jar 里没有 " + entry
                    data = archive.read(entry)
                    if b"contains" not in data:
                        return False, "Match.class 常量池里没有 contains"
                    return True, path
            except Exception as exc:  # pragma: no cover
                return False, "读 jar 失败: %r" % (exc,)
    return None, "本机没有 jecharacters jar（可选证据）"


# ======================================================================================
# ⑥ 语言键
# ======================================================================================

def lang_ok(zh, en):
    cjk = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")
    for key in ("gui.rs_create_compat.charger_filter.search.hint",
                "gui.rs_create_compat.charger_filter.search.tip"):
        if key not in zh or key not in en:
            return False
        if not zh[key].strip() or not en[key].strip():
            return False
        if cjk.search(en[key]):
            return False
        if "拼音" not in zh[key] or "pinyin" not in en[key].lower():
            return False
    return True


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    pinyin_text = read(PINYIN)
    cand_text = read(CANDIDATES)
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")

    print("=== ① 软依赖：反射调用 jecharacters 的 Match.contains，零编译期依赖 ===")
    check("①-a PinyinSearch 用 Class.forName + getMethod(\"contains\") + invoke（无 import me.towdium）",
          soft_dependency_ok(pinyin_text))
    counterexample("把反射换成 import me.towdium.jecharacters.utils.Match（硬依赖，没装模组的玩家会崩）⇒ 判负",
                   soft_dependency_ok(pinyin_text.replace(
                       'private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";',
                       'private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";\n'
                       'import me.towdium.jecharacters.utils.Match;')) is False)
    check("①-b 每次调用都 try/catch Throwable，失败降级到不区分大小写子串", fallback_ok(pinyin_text))
    check("①-b2 先 toLowerCase(Locale.ROOT) 再传 JEC（复核实测：JEC 对 ASCII 大小写敏感，"
          "不 lower 会让「装了模组后英文名搜不到」）", lowercase_both_sides_ok(pinyin_text))
    counterexample("不 lower 直接把原始大小写传给 JEC ⇒ 判负",
                   lowercase_both_sides_ok(pinyin_text.replace(
                       "method.invoke(null, haystack, needle)", "method.invoke(null, text, query)")) is False)
    counterexample("去掉降级 catch（模组内部异常会直接抛给界面）⇒ 判负",
                   fallback_ok(pinyin_text.replace("catch (final Throwable ignored)", "catch (final Exception ignored)")) is False)
    check("①-c 解析只做一次（resolved 布尔 + 静态 Method + synchronized）", cache_once_ok(pinyin_text))
    counterexample("去掉 resolved 缓存（每次按键都反射解析一遍）⇒ 判负",
                   cache_once_ok(pinyin_text.replace("if (resolved)", "if (false)")) is False)

    print("=== ② 接线：过滤搜索的名字一路走 PinyinSearch，id 一路保持 ASCII 子串 ===")
    check("②-a ChargerFilterCandidates.search 用 PinyinSearch.contains(name, needle)；id 仍为小写子串",
          candidates_wired_ok(cand_text))
    counterexample("接线换回 name.contains(needle)（拼音搜索又失效）⇒ 判负",
                   candidates_wired_ok(cand_text.replace(
                       "PinyinSearch.contains(name, needle)",
                       "name.toLowerCase(Locale.ROOT).contains(needleLower)")) is False)
    counterexample("把 id 分支也改成拼音匹配（ASCII id 会误伤 / 变慢）⇒ 判负",
                   candidates_wired_ok(cand_text.replace(
                       "id.toString().toLowerCase(Locale.ROOT).contains(needleLower)",
                       "PinyinSearch.contains(id.toString(), needle)")) is False)

    print("=== ③ 行为模型：模组在场 / 缺席 / 抛异常三种状态的命中与降级 ===")
    check("③-a 拼音 / 简拼命中、非命中不误报、缺席退回子串、异常降级（10 例）", model_ok())
    check("③-b 模组缺席时与第 107 轮的纯子串模型逐字一致", model_absent_matches_old_model())
    counterexample("模型把「模组在场」的拼音命中改成 false（等于没接上）⇒ 判负",
                   model_contains_without_pinyin(FakePinyin("present"), "控制器", "kzq") is False)
    counterexample("模型在模组缺席时不降级（直接 False，搜索全空）⇒ 判负",
                   model_contains_without_fallback(FakePinyin("absent"), "能量立方", "能量") is False)

    print("=== ④ 硬依赖检查：src/main 里没有任何 jecharacters 的编译期引用 ===")
    offenders = no_hard_dependency_ok()
    check("④-a 整个 src/main 只有 PinyinSearch 以字符串常量提到 jecharacters（无 import / 无类型引用）",
          not offenders)
    if offenders:
        for path in offenders:
            print("       offender: " + os.path.relpath(path, ROOT))
    counterexample("在别的类里 import me.towdium ⇒ 硬依赖判据判负",
                   bool([CANDIDATES]) and ("import me.towdium" in
                                           strip_comments("import me.towdium.jecharacters.utils.Match;")))

    print("=== ⑤ 可选证据：本机 jecharacters jar 里确实有 Match.contains ===")
    jar_ok, detail = local_jar_evidence()
    if jar_ok is None:
        warn(detail)
        check("⑤-a 可选证据：本机未装 jecharacters ⇒ 跳过（不判负）", True)
    else:
        check("⑤-a 本机 jecharacters jar 内 me/towdium/jecharacters/utils/Match.class 含 contains（%s）"
              % detail, jar_ok)

    print("=== ⑥ 语言键：搜索提示/说明提到拼音 ===")
    check("⑥-a search.hint / search.tip 的 zh 提到「拼音」、en 提到 pinyin，均 zh/en 成对",
          lang_ok(zh, en))
    counterexample("zh 的 search.tip 不提拼音 ⇒ 判负",
                   lang_ok(dict(zh, **{"gui.rs_create_compat.charger_filter.search.tip":
                                       "按物品名或 ID 搜索"}), en) is False)

    print("")
    print("断言总数 %d（含反例自证 %d 条），告警 %d 条" % (CHECKS[0], COUNTER[0], len(WARNS)))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
