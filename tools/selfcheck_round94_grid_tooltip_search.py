#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round94 自检：网格搜索框的**裸文本**也要能搜 tooltip（用户需求：搜「水下呼吸」要搜到附魔书）。

用户原话（这一轮的原始诉求）：
    「我想搜索水下呼吸这个附魔术，我直接搜索水下呼吸，JEI 是给我搜得到的。但精致存储里不具备
      这个功能。我希望你把这个功能移植过来……这个就不需要配置了……或者你可以加上一些字符，
      比如说井号后面加上 tag 搜索 tag 之类的。但是这个附魔书的这个功能我是非常急需的。」

本脚本要钉住的**事实与不变式**（只读源码 / 配置 / 编译产物 / 真实 RS 字节码，不启动游戏）：

  ⓪ **RS 2.0.9 的真实搜索结构**（字节码取证，不是读文档）：
     a `GridQueryParser#parseLiteral(LiteralNode)` 是「裸文本叶子」的**唯一**生成点，
       且**只被 `parseNode` 调用** —— 因此在这里做逻辑或，就等价于「裸文本 = 名字或 tooltip」；
     b `attributeMatch` **只被 `parseUnaryOp` 调用** —— 因此 `$`(tooltip) / `#`(tag) / `@`(mod)
       三个前缀**根本不经过** parseLiteral，本功能在结构上不可能改动它们的语义；
     c `GridResourceAttributeKeys` 里 `TOOLTIP` 这个属性键真实存在（RS 自己采过 tooltip 全文）；
     d `GridResource` 接口真实提供 `getAttribute(key)` 与 `getSearchableNames()`。
     这四条任何一条不成立（RS 升级换了结构），本功能就不是「注入点漂移」而是「假设失效」，
     脚本必须判负 —— 否则改天 RS 升级，功能会静默消失而自检全绿。

  ① **注入体必须做「逻辑或」且名字优先**：
     a 目标方法必须是 `parseLiteral`（不是 `parse` / `parseUnaryOp` / `attributeMatch`）；
     b `@At("RETURN")` + `cancellable = true` + `setReturnValue(...)`（包裹而不是替换 RS 的判定）；
     c 包装体里 `nameOnly.test(...)` 在 `RsccGridTooltipSearch.matches(...)` **之前**，两者用 `||`
        连接 —— 顺序反了或写成 `&&` 都会让「名字搜得到的东西突然搜不到」；
     d 空字面量有 `isBlank()` 守卫（空查询的语义归 RS 的 `parse()`，不在叶子上乱判）。

  ② **必须登记在 mixins.json 的 `client` 数组**（注入体引用了本模组的客户端类；专用服务端不加载），
     且**不得**同时出现在公共 `mixins` 数组里。

  ③ **匹配口径与缓存边界**（helper 类）：
     a 只消费 RS 的 `GridResourceAttributeKeys.TOOLTIP` 属性，不自己再写一份 tooltip 生成；
     b 规范化与 RS 的 `attributeMatch` 逐字一致：`trim()` + `toLowerCase(Locale.ROOT)` + `contains`；
     c **恰好一个配置开关读点**（第 95 轮用户改口：「既然默认存在，就做成可配置的吧」）：
       开关名 `gridSearchMatchesTooltip`、默认 `true`、读点只能在 `matches()` 入口；
       `textMatches()` 必须保持纯函数（不许读配置）、`Config.java` 里默认值必须是 true；
     d 缓存必须有上限：条数 LRU + **字符总量预算**、跳过超长文本、语言变化与「换世界对象」整表作废；
     e 读 tooltip 抛异常按「不匹配」处理，且**失败不缓存**（一次瞬态异常不能把物品永久藏起来）；
     f 缓存键带**资源类型**（RS 允许附属模组替换 mapper，只按资源键缓存会串味）；
     g `textMatches` 是**纯函数**（不碰 Minecraft）—— 这样自检能把它真跑起来验证语义。

  ④ **真代码语义跑通**：把 `RsccGridTooltipSearch.textMatches` 编译并执行 12 组断言
     （中文子串 / 大小写 / 首尾空白 / 空关键词 / 空 tooltip / 不相关词），要求
     `PURE_SEMANTICS_OK`。这不是「读源码相信」，是**跑真代码**。

  ⑤ **反例自证 ×17**：把真实源码改回「功能退化 / 注入点漂移 / 语义反转 / 关掉缓存边界 /
     把失败缓存成空串 / 去掉字符总量预算 / 键不带资源类型 / 换世界不清表」的形态，
     本脚本必须判负。任何一条反例不再被抓住 ⇒ 对应的断言是空转的。

用法：python tools/selfcheck_round94_grid_tooltip_search.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import json
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
TOOLS = os.path.dirname(os.path.abspath(__file__))
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import _gradle_cache as gc  # noqa: E402

SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")
MIXIN_JSON = os.path.join(RES, "rs_create_compat.mixins.json")
MIXIN_SRC = os.path.join(SRC, "mixin", "client", "GridQueryParserTooltipSearchMixin.java")
SEARCH_SRC = os.path.join(SRC, "client", "grid", "RsccGridTooltipSearch.java")
CONFIG_SRC = os.path.join(SRC, "Config.java")
CLS = os.path.join(ROOT, "build", "lead_compile", "cretae", "cookiewyq", "rs_create_compat")
COMPILE_OUT = os.path.join(ROOT, "build", "lead_compile")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round94")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"

MIXIN_CLASS = "client.GridQueryParserTooltipSearchMixin"

PROBLEMS = []
CHECKS = [0]
INFO = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % name)
    else:
        PROBLEMS.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def info(text):
    INFO.append(text)
    print("[info] " + text)


def read(path):
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """只去注释、保留字符串字面量（判 `method = "parseLiteral"` 这类注解参数时用）。"""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def strip_comments_and_strings(text):
    """注释 + 字符串字面量都换成空格（行号不变）。判「代码里有没有用某样东西」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if char == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif char == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif char == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(char)
            i += 1
    return "".join(out)


def block_at(text, open_index):
    depth = 0
    for j in range(open_index, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:j]
    return ""


def method_body(code, needle):
    """取包含 `needle` 的那段方法体（从 needle 之后第一个 `{` 起配对）。"""
    start = code.find(needle)
    if start < 0:
        return ""
    opening = code.find("{", start)
    return "" if opening < 0 else block_at(code, opening)


def load_sources():
    return {
        "mixin": read(MIXIN_SRC),
        "mixin_nc": strip_comments(read(MIXIN_SRC)),
        "mixin_code": strip_comments_and_strings(read(MIXIN_SRC)),
        "search": read(SEARCH_SRC),
        "search_code": strip_comments_and_strings(read(SEARCH_SRC)),
        "json": read(MIXIN_JSON),
        "config": read(CONFIG_SRC),
    }


# ==================================================================== 审计核心
# 返回「问题码」列表。真实源码必须为空；反例自证要求对应问题码出现。
def audit(sources):
    problems = []
    mixin_nc = sources["mixin_nc"]
    mixin = sources["mixin_code"]
    search = sources["search_code"]

    # ---------- ① 注入体 ----------
    if not sources["mixin"].strip():
        return ["mixin-file-missing"]

    if "@Mixin(GridQueryParser.class)" not in mixin_nc:
        problems.append("mixin-target")
    if ('method = "parseLiteral"' not in mixin_nc
            or '@At("RETURN")' not in mixin_nc
            or "cancellable = true" not in mixin_nc):
        problems.append("mixin-injection")
    if ("private static void rscc$matchTooltipForPlainLiteral" not in mixin
            or "LiteralNode node" not in mixin
            or "CallbackInfoReturnable<ResourceRepositoryFilter<GridResource>> cir" not in mixin):
        problems.append("mixin-injection")

    body = method_body(mixin, "rscc$matchTooltipForPlainLiteral")
    if "cir.getReturnValue()" not in body or "cir.setReturnValue(" not in body:
        problems.append("mixin-injection")
    name_at = body.find("nameOnly.test(")
    tip_at = body.find("RsccGridTooltipSearch.matches(")
    if name_at < 0 or tip_at < 0 or name_at > tip_at:
        problems.append("mixin-not-wrapping")          # 缺一个 / 顺序反了
    else:
        between = body[name_at:tip_at]
        if "||" not in between or "&&" in between:
            problems.append("mixin-not-wrapping")      # 写成 && 等于「名字与 tooltip 同时命中」
    if "literal.isBlank()" not in body:
        problems.append("mixin-empty-guard")

    # 注入面必须只有这一处，且绝不碰属性前缀那条路（$ / # / @）
    if mixin.count("@Inject(") != 1:
        problems.append("mixin-touches-attribute-path")
    for forbidden in ("parseUnaryOp", "attributeMatch", "ATTRIBUTE_MAPPING"):
        if forbidden in mixin:
            problems.append("mixin-touches-attribute-path")

    # ---------- ② 注册 ----------
    try:
        data = json.loads(sources["json"])
    except Exception:
        problems.append("registration-not-client")
        data = {}
    client = data.get("client") or []
    common = data.get("mixins") or []
    if MIXIN_CLASS not in client:
        problems.append("registration-not-client")
    if MIXIN_CLASS in common:
        problems.append("registration-not-client")

    # ---------- ③ helper ----------
    # 配置开关（第 95 轮）：有且只有一个读点，且必须在 matches() 入口。
    matches_body = method_body(search, "public static boolean matches(")
    if matches_body.count("Config.gridSearchMatchesTooltip") != 1:
        problems.append("search-config-gate")
    if "Config." in search.replace("Config.gridSearchMatchesTooltip", ""):
        problems.append("search-config-gate")          # 多读一处 = 判定口径分叉
    if "Config." in method_body(search, "public static boolean textMatches("):
        problems.append("search-config-pure")          # 纯函数不许读配置
    config = sources.get("config", "")
    if 'define("gridSearchMatchesTooltip", true)' not in config:
        problems.append("search-config-default")       # 默认关闭 = 功能不存在
    if "gridSearchMatchesTooltip = GRID_SEARCH_MATCHES_TOOLTIP.get();" not in config:
        problems.append("search-config-default")       # 配置改了却不同步到静态字段
    if "GridResourceAttributeKeys.TOOLTIP" not in search or "getAttribute(" not in search:
        problems.append("search-attribute")

    normalize = method_body(search, "private static String normalize(")
    if "trim()" not in normalize or "toLowerCase(Locale.ROOT)" not in normalize:
        problems.append("search-normalize")

    tooltip_text = method_body(search, "private static String tooltipText(")
    remember = method_body(search, "private static void remember(")
    if "removeEldestEntry" not in search or "size() > MAX_CACHED_RESOURCES" not in search:
        problems.append("search-cache-bound")            # 没有条数上限 = 内存泄漏
    if "new LinkedHashMap<>(" not in search or ", true)" not in search:
        problems.append("search-cache-bound")            # 必须访问序 LRU
    if "MAX_CACHED_TEXT_LENGTH" not in remember:
        problems.append("search-cache-bound")            # 超长文本必须跳过缓存
    if "MAX_CACHED_CHARS" not in remember or "cachedChars" not in remember:
        problems.append("search-cache-budget")           # 必须有「字符总量预算」

    context = method_body(search, "private static void refreshContext(")
    if ("getLanguageManager().getSelected()" not in context
            or "TOOLTIP_TEXT.clear()" not in context
            or "cachedLanguage" not in context):
        problems.append("search-language")               # 切语言必须清表
    if "cachedLevel" not in context or "level != cachedLevel" not in context:
        problems.append("search-context")                # 换世界 / 换维度必须清表

    read_tooltip = method_body(sources["search"], "private static String readTooltip(")
    if "catch (final Throwable" not in read_tooltip:
        problems.append("search-error-tolerance")
    failure_branch = read_tooltip[read_tooltip.find("catch (final Throwable"):]
    if "return null;" not in failure_branch:
        problems.append("search-failure-not-cached")     # 失败必须返回 null（不能被 remember 缓存成 ""）
    if "text != null" not in tooltip_text or "remember(" not in tooltip_text:
        problems.append("search-failure-not-cached")     # 只有非 null 才进缓存

    cache_key = method_body(search, "private static CacheKey cacheKey(")
    if "resource.getClass()" not in cache_key or "Class<? extends GridResource> type" not in search:
        problems.append("search-cache-key")              # 键必须带资源类型

    pure = method_body(search, "public static boolean textMatches(")
    if ("needle.isEmpty()" not in pure
            or "Minecraft" in pure
            or "getAttribute(" in pure
            or "contains(needle)" not in pure):
        problems.append("search-pure")

    matches = method_body(search, "public static boolean matches(")
    if "textMatches(" not in matches:
        problems.append("search-pure")

    return problems


# ==================================================================== ⓪ RS 字节码取证
# 自检**不调用 javap、也不依赖 java 在 PATH 里**：本机 javap 子进程会偶发卡死（无输出、CPU 0，
# 能挂 20 分钟以上；`subprocess.run(timeout=)` 在超时收尾时还有一次无超时的 communicate，
# 于是连「超时就杀」都收不了尾 —— 详见 tools/verify_mixin_shadows.py 里 run_javap 的注释）。
# 全量自检是 119 个脚本串行跑，任何一处卡住都会拖死整轮，所以这里用标准库
# zipfile + struct 直接读 class 文件（常量池 / 字段表 / 方法表）：结论完全确定、可复算。
class JavaClass:
    """极简 class 文件解析器：只读常量池、字段表、方法表（不解析字节码指令）。"""

    def __init__(self, raw):
        self.raw = raw
        self.cp = [None]
        self.fields = []
        self.methods = []
        self._parse()

    # ---- 常量池访问 ----
    def utf8(self, index):
        entry = self.cp[index] if 0 < index < len(self.cp) else None
        return entry[1] if entry and entry[0] == 1 else ""

    def class_name(self, index):
        entry = self.cp[index] if 0 < index < len(self.cp) else None
        return self.utf8(entry[1]) if entry and entry[0] == 7 else ""

    def name_and_type(self, index):
        entry = self.cp[index] if 0 < index < len(self.cp) else None
        if entry and entry[0] == 12:
            return self.utf8(entry[1][0]), self.utf8(entry[1][1])
        return "", ""

    def method_refs(self):
        """常量池里所有方法 / 接口方法引用：{(所属类, 方法名, 描述符)}。"""
        refs = set()
        for entry in self.cp[1:]:
            if entry and entry[0] in (10, 11):
                refs.add((self.class_name(entry[1][0]),) + self.name_and_type(entry[1][1]))
        return refs

    def utf8_values(self):
        return {entry[1] for entry in self.cp[1:] if entry and entry[0] == 1}

    # ---- 解析 ----
    def _parse(self):
        raw = self.raw
        if len(raw) < 10 or raw[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("不是 class 文件")
        count = int.from_bytes(raw[8:10], "big")
        pos = 10
        for _ in range(1, count):
            tag = raw[pos]
            pos += 1
            if tag == 1:
                length = int.from_bytes(raw[pos:pos + 2], "big")
                self.cp.append((1, raw[pos + 2:pos + 2 + length].decode("utf-8", "replace")))
                pos += 2 + length
            elif tag in (3, 4):
                self.cp.append((tag, raw[pos:pos + 4]))
                pos += 4
            elif tag in (5, 6):
                self.cp.append((tag, raw[pos:pos + 8]))
                self.cp.append(None)          # long / double 各占两个常量池槽位
                pos += 8
            elif tag in (7, 8, 16, 19, 20):
                self.cp.append((tag, int.from_bytes(raw[pos:pos + 2], "big")))
                pos += 2
            elif tag in (9, 10, 11, 12, 17, 18):
                self.cp.append((tag, (int.from_bytes(raw[pos:pos + 2], "big"),
                                      int.from_bytes(raw[pos + 2:pos + 4], "big"))))
                pos += 4
            elif tag == 15:
                self.cp.append((tag, (raw[pos], int.from_bytes(raw[pos + 1:pos + 3], "big"))))
                pos += 3
            else:
                raise ValueError("未知常量池 tag %d" % tag)
        pos += 2                                   # access_flags
        pos += 4                                   # this_class + super_class
        interfaces = int.from_bytes(raw[pos:pos + 2], "big")
        pos += 2 + 2 * interfaces
        for target in (self.fields, self.methods):
            total = int.from_bytes(raw[pos:pos + 2], "big")
            pos += 2
            for _ in range(total):
                access = int.from_bytes(raw[pos:pos + 2], "big")
                name = self.utf8(int.from_bytes(raw[pos + 2:pos + 4], "big"))
                desc = self.utf8(int.from_bytes(raw[pos + 4:pos + 6], "big"))
                attributes = int.from_bytes(raw[pos + 6:pos + 8], "big")
                pos += 8
                target.append((access, name, desc))
                for _ in range(attributes):
                    pos += 6 + int.from_bytes(raw[pos + 2:pos + 6], "big")


def read_class_from_jar(jar_path, internal_name):
    with zipfile.ZipFile(jar_path) as archive:
        return JavaClass(archive.read(internal_name + ".class"))


def read_text_from_jar(jar_path, internal_name):
    with zipfile.ZipFile(jar_path) as archive:
        return archive.read(internal_name).decode("utf-8", "replace")


def java_method_body(text, signature):
    start = text.find(signature)
    if start < 0:
        return ""
    opening = text.find("{", start)
    if opening < 0:
        return ""
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[opening + 1:index]
    return ""


LITERAL_DESC = ("(Lcom/refinedmods/refinedstorage/query/parser/node/LiteralNode;)"
                "Lcom/refinedmods/refinedstorage/api/resource/repository/ResourceRepositoryFilter;")

MIXIN_HANDLER_DESC = ("(Lcom/refinedmods/refinedstorage/query/parser/node/LiteralNode;"
                      "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V")


def audit_rs_bytecode():
    """⓪ 在**真实运行时 jar**（pin 的 2.0.9）上核对「注入点假设」是否仍然成立。"""
    problems = []
    rs_jar, notes = gc.find_jar("com.refinedmods.refinedstorage", "refinedstorage-neoforge")
    if not rs_jar:
        info("找不到 RS jar：%s" % "; ".join(notes))
        return ["rs-jar-missing"]
    info("RS jar = %s" % rs_jar)
    if "2.0.9" not in os.path.basename(rs_jar):
        info("注意：本次取到的 RS 版本不是 2.0.9：%s" % os.path.basename(rs_jar))

    try:
        parser = read_class_from_jar(
            rs_jar, "com/refinedmods/refinedstorage/common/grid/query/GridQueryParser")
    except Exception as exc:
        info("读 GridQueryParser.class 失败：%s" % exc)
        return ["rs-parseLiteral"]

    literal = [m for m in parser.methods if m[1] == "parseLiteral" and m[2] == LITERAL_DESC]
    if len(literal) != 1:
        problems.append("rs-parseLiteral")            # 目标方法不存在 / 描述符变了 ⇒ 注入点漂移
    elif not (literal[0][0] & 0x0002 and literal[0][0] & 0x0008):
        problems.append("rs-parseLiteral")            # 必须是 private static（handler 也必须是 static）

    keys = read_class_from_jar(
        rs_jar, "com/refinedmods/refinedstorage/common/api/grid/GridResourceAttributeKeys")
    if not any(field[1] == "TOOLTIP" for field in keys.fields):
        problems.append("rs-attribute-key")           # 枚举常量 TOOLTIP 不在 ⇒ helper 读不到 tooltip

    resource = read_class_from_jar(
        rs_jar, "com/refinedmods/refinedstorage/common/api/grid/view/GridResource")
    resource_methods = {method[1] for method in resource.methods}
    if "getAttribute" not in resource_methods or "getSearchableNames" not in resource_methods:
        problems.append("rs-attribute-key")

    # 调用关系：`parseLiteral` 只由 `parseNode` 调用、`attributeMatch` 只由 `parseUnaryOp` 调用。
    # 用**同一 pin 的 sources 附件**（2.0.9-sources.jar）做文本核对，不读任何反编译产物。
    source_jar, source_notes = gc.find_jar(
        "com.refinedmods.refinedstorage", "refinedstorage-neoforge", kind="sources")
    if not source_jar:
        info("找不到 RS sources 附件：%s" % "; ".join(source_notes))
        return problems + ["rs-call-sites"]
    text = read_text_from_jar(
        source_jar, "com/refinedmods/refinedstorage/common/grid/query/GridQueryParser.java")
    parse_node = java_method_body(text, " parseNode(")
    parse_unary = java_method_body(text, " parseUnaryOp(final UnaryOpNode node)")
    if "parseLiteral(" not in parse_node or "parseLiteral(" in parse_unary:
        problems.append("rs-call-sites")              # 注入点不再只服务「裸文本叶子」
    if "attributeMatch(" not in parse_unary or "parseLiteral(" in parse_unary:
        problems.append("rs-attribute-path")          # $ / # / @ 与裸文本混到一条路上
    if not any('"$"' in line and "TOOLTIP" in line for line in text.splitlines()):
        problems.append("rs-attribute-path")          # RS 的 $tooltip 语义必须还在（我们不改它）
    return problems


# ==================================================================== ④ 编译产物取证（我们自己的 mixin）
def audit_compiled_mixin():
    """④ 我们编译出来的 mixin 类：真的带着 @Inject(method=parseLiteral, at=RETURN, cancellable)。"""
    path = os.path.join(CLS, "mixin", "client", "GridQueryParserTooltipSearchMixin.class")
    if not os.path.isfile(path):
        return ["class-not-compiled"]
    try:
        with open(path, "rb") as handle:
            compiled = JavaClass(handle.read())
    except Exception:
        return ["class-not-compiled"]

    problems = []
    handler = [m for m in compiled.methods if m[1] == "rscc$matchTooltipForPlainLiteral"]
    if len(handler) != 1 or handler[0][2] != MIXIN_HANDLER_DESC:
        problems.append("class-annotation")           # 形参 / 返回类型不对 ⇒ 注入体签名错误
    elif not (handler[0][0] & 0x0002 and handler[0][0] & 0x0008):
        problems.append("class-annotation")           # 目标是 static，handler 必须 private static
    strings = compiled.utf8_values()
    for needed in ("parseLiteral", "RETURN", "cancellable",
                   "Lorg/spongepowered/asm/mixin/injection/Inject;",
                   "Lorg/spongepowered/asm/mixin/injection/At;"):
        if needed not in strings:
            problems.append("class-annotation")
    refs = compiled.method_refs()
    if not any(name == "matches" and "RsccGridTooltipSearch" in owner for owner, name, _ in refs):
        problems.append("class-annotation")           # 必须真的调用 helper（不能只剩空壳）
    if not any(name in ("getReturnValue", "setReturnValue") for _, name, _ in refs):
        problems.append("class-annotation")           # 必须真的是「包裹」而不是「替换」
    return problems


# ==================================================================== ④b 真代码语义
PROBE_SOURCE = '''\
public final class GridTooltipSearchProbe {
    private static int cases;
    private static int failures;

    private static void expect(final String tooltip, final String query, final boolean want) {
        cases++;
        final boolean got =
            cretae.cookiewyq.rs_create_compat.client.grid.RsccGridTooltipSearch.textMatches(tooltip, query);
        if (got != want) {
            failures++;
            System.out.println("FAIL tooltip=" + tooltip + " query=" + query + " want=" + want + " got=" + got);
        }
    }

    public static void main(final String[] args) {
        // 用户的原始诉求：搜「水下呼吸」必须命中附魔书 tooltip 里的「水下呼吸 III」
        expect("\\u9644\\u9b54\\u4e66\\n\\u6c34\\u4e0b\\u547c\\u5438 III", "\\u6c34\\u4e0b\\u547c\\u5438", true);
        expect("\\u6c34\\u4e0b\\u547c\\u5438 III", "\\u6c34\\u4e0b\\u547c\\u5438", true);
        expect("\\u6c34\\u4e0b\\u547c\\u5438 III", "\\u6c34\\u4e0b", true);
        // 英文大小写不敏感（Locale.ROOT）
        expect("Respiration III", "respiration", true);
        expect("Respiration III", "RESPIRATION", true);
        // 首尾空白由 trim 吃掉
        expect("Respiration III", "  Respiration  ", true);
        // 与 RS 的 $ 同一套「原始子串」口径：中间多一个空格就不命中
        expect("\\u6c34\\u4e0b\\u547c\\u5438 III", "\\u6c34\\u4e0b\\u547c\\u5438  III", false);
        // 不相关词不能命中（否则「搜什么都出」= 功能反而更糟）
        expect("\\u6c34\\u4e0b\\u547c\\u5438 III", "\\u950b\\u5229", false);
        // 空关键词不算命中
        expect("\\u6c34\\u4e0b\\u547c\\u5438 III", "", false);
        expect("\\u6c34\\u4e0b\\u547c\\u5438 III", "   ", false);
        // 空 tooltip / null 不炸
        expect("", "\\u6c34\\u4e0b", false);
        expect(null, "\\u6c34\\u4e0b", false);
        if (failures > 0) {
            System.out.println("PURE_SEMANTICS_FAIL failures=" + failures + " cases=" + cases);
            System.exit(1);
        }
        System.out.println("PURE_SEMANTICS_OK cases=" + cases);
    }
}
'''


def lead_classpath():
    """与 tools/_lead_compile.ps1 同一套判据（跟随 GRADLE_USER_HOME + 同族去重）。"""
    parts = [COMPILE_OUT]
    mc_jar, _ = gc.find_mc_jar()
    if mc_jar:
        parts.append(mc_jar)
    deduped = gc.dedupe(gc.module_jars(), gc.pinned_versions())
    parts.extend(ref.path for ref in deduped.kept)
    libs_dir = os.path.join(ROOT, "libs")
    if os.path.isdir(libs_dir):
        parts.extend(os.path.join(libs_dir, n) for n in sorted(os.listdir(libs_dir)) if n.endswith(".jar"))
    seen = set()
    unique = []
    for part in parts:
        if part and part not in seen:
            seen.add(part)
            unique.append(part)
    return ";".join(unique)


def run_pure_semantics():
    for tool in (JAVAC, JAVA):
        if not os.path.isfile(tool):
            return False, "找不到 %s" % tool
    if not os.path.isdir(COMPILE_OUT):
        return False, "build/lead_compile 不存在（先跑 tools/_lead_compile.ps1）"
    os.makedirs(PROBE_DIR, exist_ok=True)
    source = os.path.join(PROBE_DIR, "GridTooltipSearchProbe.java")
    with io.open(source, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(PROBE_SOURCE)
    classpath = lead_classpath()
    compile_proc = subprocess.run(
        [JAVAC, "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", PROBE_DIR, source],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    if compile_proc.returncode != 0:
        return False, (compile_proc.stdout + compile_proc.stderr)[-1200:]
    run_proc = subprocess.run(
        [JAVA, "-cp", PROBE_DIR + ";" + classpath, "GridTooltipSearchProbe"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    out = (run_proc.stdout or "") + (run_proc.stderr or "")
    if run_proc.returncode != 0 or "PURE_SEMANTICS_OK" not in out:
        return False, out[-1200:]
    return True, out.strip().splitlines()[-1]


# ==================================================================== 主流程
sources = load_sources()

print("=" * 78)
print("round94：网格裸文本搜索 tooltip（用户诉求：搜「水下呼吸」→ 附魔书）")
print("=" * 78)

rs_problems = audit_rs_bytecode()
check("⓪ RS 2.0.9 字节码：parseLiteral 只由 parseNode 调用 / attributeMatch 只由 parseUnaryOp 调用 / "
      "TOOLTIP 键与 getAttribute 都存在（注入点假设未失效）",
      not rs_problems, ", ".join(rs_problems))

real_problems = audit(sources)
check("① 注入体 / ② 注册 / ③ 缓存与口径：裸文本 = 名字 || tooltip，且名字优先、唯一配置读点（默认开）、缓存有边界",
      not real_problems, ", ".join(real_problems))

compiled_problems = audit_compiled_mixin()
check("④ 编译产物：mixin 类里有 @Inject(method=parseLiteral, at=RETURN) 与对 helper 的调用",
      not compiled_problems, ", ".join(compiled_problems))

ok, detail = run_pure_semantics()
check("⑤ 真代码语义：textMatches 编译并执行 12 组断言（中文子串/大小写/空白/空值/不相关词）",
      ok, detail)

# ==================================================================== 反例自证
def mutate(name, old, new):
    if old not in sources[name]:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (name, old))
    copy = dict(sources)
    copy[name] = sources[name].replace(old, new, 1)
    return copy


def mutate_mixin_nc_pair(old, new):
    """mixin 的「去注释版」与「去注释去字符串版」要同步改（两版都参与审计）。"""
    copy = dict(sources)
    copy["mixin"] = sources["mixin"].replace(old, new, 1)
    copy["mixin_nc"] = sources["mixin_nc"].replace(old, new, 1)
    copy["mixin_code"] = sources["mixin_code"].replace(old, new, 1)
    return copy


# 1. 语义反转：名字与 tooltip 用 && 连接（改名后反而搜不到）
case = mutate("mixin_code", "nameOnly.test(repository, resource) || RsccGridTooltipSearch.matches",
              "nameOnly.test(repository, resource) && RsccGridTooltipSearch.matches")
check("⑥a 反例（裸文本与 tooltip 用 && ⇒ 名字命中但 tooltip 不含关键词的物品反而消失）判负",
      "mixin-not-wrapping" in audit(case))

# 2. 顺序反转：先算 tooltip（丢掉短路，名字命中的也要生成 tooltip）
case = mutate("mixin_code",
              "nameOnly.test(repository, resource) || RsccGridTooltipSearch.matches(resource, literal)",
              "RsccGridTooltipSearch.matches(resource, literal) || nameOnly.test(repository, resource)")
check("⑥b 反例（先算 tooltip ⇒ 每次搜索都给全部物品生成 tooltip，短路优化丢失）判负",
      "mixin-not-wrapping" in audit(case))

# 3. 去掉 cancellable（@Inject 改了返回值却不允许，实机 InvalidInjectionException）
case = mutate_mixin_nc_pair("at = @At(\"RETURN\"), cancellable = true", "at = @At(\"RETURN\")")
check("⑥c 反例（@Inject 少写 cancellable = true ⇒ 运行期注入失败）判负",
      "mixin-injection" in audit(case))

# 4. 注入点漂移：改成挂 parse（整串与 tooltip 比，&& / # 会被当成搜索词）
case = mutate_mixin_nc_pair('method = "parseLiteral"', 'method = "parse"')
check("⑥d 反例（注入点漂移到 parse ⇒ 复合查询里的运算符被当成搜索词）判负",
      "mixin-injection" in audit(case))

# 5. 顺手也去碰属性前缀（会把 $ / # / @ 的语义搅进来）
case = mutate("mixin_code", "private static void rscc$matchTooltipForPlainLiteral",
              "private static void rscc$attributeMatchHook() {\n    }\n\n"
              "    private static void rscc$matchTooltipForPlainLiteral")
check("⑥e 反例（多挂一处 parseUnaryOp / attributeMatch ⇒ 属性前缀搜索被污染）判负",
      "mixin-touches-attribute-path" in audit(case))

# 6. 注册错区：登记进公共 mixins 数组（专服会加载客户端类）
case = dict(sources)
case["json"] = sources["json"].replace(
    '    "client.GridQueryParserTooltipSearchMixin",\n', '', 1).replace(
    '    "FilterTagMixin",', '    "FilterTagMixin",\n    "client.GridQueryParserTooltipSearchMixin",', 1)
check("⑥f 反例（登记进公共 mixins 数组 ⇒ 专服加载客户端类）判负",
      "registration-not-client" in audit(case))

# 7. 配置开关读点跑进纯函数里（判定口径分叉，且纯函数不再纯）
case = mutate("search_code",
              "        final String needle = normalize(query);",
              "        if (!cretae.cookiewyq.rs_create_compat.Config.gridSearchMatchesTooltip) { return false; }\n        final String needle = normalize(query);")
check("⑥g 反例（开关读点挪进 textMatches ⇒ 纯函数被污染）判负",
      "search-config-pure" in audit(case))

# 7b. 默认值写成 false（默认关闭 = 功能不存在）
case = dict(sources)
case["config"] = sources["config"].replace('define("gridSearchMatchesTooltip", true)',
                                           'define("gridSearchMatchesTooltip", false)', 1)
check("⑥g2 反例（开关默认值写成 false ⇒ 功能默认不存在）判负",
      "search-config-default" in audit(case))

# 7c. 多读一处别的配置（判定口径分叉）
case = mutate("search_code", "        if (!cretae.cookiewyq.rs_create_compat.Config.gridSearchMatchesTooltip) {",
              "        if (!cretae.cookiewyq.rs_create_compat.Config.devLogs) { return false; }\n        if (!cretae.cookiewyq.rs_create_compat.Config.gridSearchMatchesTooltip) {")
check("⑥g3 反例（多读一处别的配置 ⇒ 判定口径分叉）判负",
      "search-config-gate" in audit(case))

# 8. 换成别的属性键（TAGS）：搜「水下呼吸」变回搜不到
case = mutate("search_code", "GridResourceAttributeKeys.TOOLTIP", "GridResourceAttributeKeys.TAGS")
check("⑥h 反例（属性键换成 TAGS ⇒ 附魔书又搜不到）判负",
      "search-attribute" in audit(case))

# 9. 缓存上界退化成无界
case = mutate("search_code", "if (size() > MAX_CACHED_RESOURCES) {", "if (false) {")
check("⑥i 反例（LRU 上界失效 ⇒ 长时间游玩后缓存无限增长）判负",
      "search-cache-bound" in audit(case))

# 10. 语言变化时不清表（切语言后拿旧语言文本比关键词）
case = mutate("search_code", "|| level != cachedLevel) {\n            TOOLTIP_TEXT.clear();\n",
              "|| level != cachedLevel) {\n")
check("⑥j 反例（语言变化不清表 ⇒ 切语言后搜不到）判负",
      "search-language" in audit(case))

# 11. 让纯函数沾上 Minecraft（自检就没法在无游戏环境跑真代码了）
case = mutate("search_code", "        return normalize(tooltipText).contains(needle);",
              "        Minecraft.getInstance();\n        return normalize(tooltipText).contains(needle);")
check("⑥k 反例（textMatches 沾上 Minecraft ⇒ 语义无法脱离游戏验证）判负",
      "search-pure" in audit(case))

# 12. 去掉空查询守卫（空字面量变成「匹配一切」，与 RS 的空查询语义冲突）
case = mutate("mixin_code", "if (literal == null || literal.isBlank()) {", "if (literal == null) {")
check("⑥l 反例（去掉 isBlank 守卫 ⇒ 空字面量匹配一切）判负",
      "mixin-empty-guard" in audit(case))

# 12b. 失败结果被缓存（一次瞬态异常把物品永久藏起来）
case = dict(sources)
case["search"] = sources["search"].replace(
    "            warnOnce(resource.getClass(), t);\n            return null;",
    "            warnOnce(resource.getClass(), t);\n            return \"\";", 1)
case["search_code"] = sources["search_code"].replace(
    "            warnOnce(resource.getClass(), t);\n            return null;",
    "            warnOnce(resource.getClass(), t);\n            return \"\";", 1)
assert case["search"] != sources["search"]
check("⑥m 反例（读取失败返回空串 ⇒ 被 remember 缓存成「永远不命中」）判负",
      "search-failure-not-cached" in audit(case))

# 12c. 去掉字符总量预算（只靠条数 × 单条上限，极端情况内存上界过大）
case = mutate("search_code", "if (cachedChars + text.length() > MAX_CACHED_CHARS) {", "if (false) {")
check("⑥n 反例（去掉字符总量预算 ⇒ 内存上界只剩「4096×4096 字符」）判负",
      "search-cache-budget" in audit(case))

# 12d. 缓存键退回「只用资源键」（RS 允许附属模组替换 mapper）
case = mutate("search_code", "new CacheKey(resource.getClass(), platform)",
              "new CacheKey(null, platform)")
check("⑥o 反例（键不带资源类型 ⇒ 附属模组替换 mapper 后串味）判负",
      "search-cache-key" in audit(case))

# 12e. 换世界 / 换维度不清表（翻译没变但 level 上下文变了）
case = mutate("search_code", " || level != cachedLevel", "")
check("⑥p 反例（换世界不清表 ⇒ 依赖 level 的 tooltip 文本过期）判负",
      "search-context" in audit(case))

print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 17 个反例自证 + 1 次真代码语义执行）；"
      "网格裸文本搜索 tooltip 满足本轮的不变式" % CHECKS[0])
sys.exit(0)
