#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 74 轮：玩家可见文本的本地化（RS 自动合成仓管理器里的机器名显示英文）+ 全工程同类排查。

<h2>本轮实机反馈</h2>
<p>「有一些机器，尤其是那个序列装配样板，接触到网络之后，自动合成仓管理器中显示的是英文名字」。</p>

<h2>根因（判据 D 就是为它写的）</h2>
<p>RS 的自动合成仓管理器在<b>服务端</b>就把机器名压成了 {@code String}：</p>
<pre>
AutocrafterManagerBlockEntity#getGroups()
    .collect(Collectors.groupingBy(a -> a.getAutocrafterName().getString()))
AutocrafterManagerData.Group#name           // ByteBufCodecs.STRING_UTF8
AutocrafterManagerScreen#renderGroups       // graphics.drawString(font, group.getName(), ...)
</pre>
<p>而语言表只存在于客户端：专服上 {@code Language.getInstance()} 只有 {@code en_us}
（客户端选中语言是客户端资源重载时 {@code Language.inject(clientLanguage)} 才注入的）。
于是「可翻译组件」在这条链路上必然被固化成英文名。修法是本工程既有口径：<b>只送语言键，
客户端解析</b>（{@code support/RsccClientText} + client Mixin 解析点）。</p>

<h2>判据与允许清单（都写在代码里，可核对）</h2>
<ul>
 *   <li><b>A 语言键</b>：成对 / 非空 / <b>中文值不得是纯 ASCII 占位</b> / <b>英文值不得含中文</b>。
 *       其中「成对 / 非空」是既有断言，<b>复用</b> {@code tools/audit_lang_keys.py}
 *       （本轮给它加了后两条规则），本脚本以子进程跑它并断言「问题总数: 0」「孤儿键：0 条」；</li>
 *   <li><b>B 名字类方法</b>：{@code Component getName / getDisplayName / getAutocrafterName}、
 *       {@code String getChamberDisplayName} 的 return 不得是裸字符串字面量、也不得是
 *       {@code Component.literal("<含 ASCII 字母的字面量>")}（注释先剥掉，避免把解释误判成实现）；</li>
 *   <li><b>C 全工程 {@code Component.literal} 字面量</b>：去掉 {@code §} 颜色码后仍含 ASCII 字母的，
 *       必须逐条登记进 {@link RENDERED_LITERAL_ALLOWLIST} 并写明「为什么不是玩家可见文本」；
 *       登记项若已不存在也算失败（防止允许清单腐烂）；</li>
 *   <li><b>D RS 集成点</b>：机器名必须走「语言键 + 客户端解析」：
 *       ① {@code getAutocrafterName()} 不得再返回会被 RS 压扁的组件；
 *       ② {@code AUTOCRAFTER_NAME_KEY} 必须中英成对且中文值含中文；
 *       ③ 客户端解析 Mixin 必须存在、只挂在 client 段、且调用 {@code RsccClientText.resolveText}；
 *       ④ {@code RsccClientText} 必须 dist 安全（不得引用 {@code net.minecraft.client}）；
 *       ⑤ {@code getAutocrafterNames()}（把名字当<b>数据</b>存进单元样板的那条路）必须用
 *       {@code keyOf(...)} 收口，不得把某一时刻的本地化快照当绑定键；</li>
 *   <li><b>E 反例自证</b>：把<b>修前</b>的写法喂给同一批判据，必须判 FAIL
 *       （否则说明判据恒真、抓不住旧口径）。</li>
 *   <li><b>F 全工程「String → 玩家可见 Component」扫描</b>（第 2 批：产物名 / 缺料名 / 配方名）：
 *       服务端可达的 {@code .java} 里，凡是把 {@code getString()} 的字符串拼进/传进
 *       「玩家可见文本出口」的写法一律判负（出口 = {@code CompletionBannerPayload.localized /
 *       Row.*}、{@code Component.translatable / literal}、{@code new Shortage / OfflineStep /
 *       Material}、{@code record.productName / materialNames} 赋值、{@code texts/resTexts.add}、
 *       {@code displayClientMessage} 等）；
 *   </li>
 *   <li><b>G 日志与玩家可见的机器区分</b>（本条的关键）：
 *       ① 语句级切分（括号深度 0 且不在字符串里的分号 —— 格式化文案里的分号不算）；
 *       ② 语句里出现「日志出口 token」（{@code RsccAssemblyDebug. / throttledLog( / logState( /
 *       LOGGER. / RsccDiag. / System.out ...}）⇒ 这是日志，{@code getString()} 放行
 *       （服务端日志没有玩家语言可言，保留英文是正常形态）；
 *       ③ 同时出现「玩家出口 token」⇒ 玩家文本优先，判负；
 *       ④ 两者都没有 ⇒ 归「未分类」，必须逐条登记 {@link GETSTRING_ALLOWLIST} 并写明为什么
 *       不是玩家可见文本；登记项必须仍存在（防腐烂），出现新的未登记写法即判负；
 *   </li>
 *   <li><b>H 编码载体必须是语言键</b>：产物名 / 缺料名 / 换料名 / 中间产物名 / 离线配方名
 *       必须经 {@code RsccClientText.itemNameArg / fluidNameArg / argRecipeType} 编码，
 *       解析端（横幅 Toast）必须走 {@code RsccClientText.argument}（协议单一定义、不漂移）。</li>
</ul>

用法：python tools/selfcheck_round74_localization.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # repo root
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
MIXINS_JSON = os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json")

BE = "block/entity/SequenceAssemblyExecutorBlockEntity.java"
TERMINAL = "block/entity/SequencePatternTerminalBlockEntity.java"
CLIENT_TEXT = "support/RsccClientText.java"
RESOLVER_MIXIN = "mixin/client/AutocrafterManagerGroupNameMixin.java"
BANNER = "network/CompletionBannerPayload.java"

FAILURES = []
CHECKS = [0]

CJK_RE = re.compile(r"[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]")
FORMAT_RE = re.compile(r"%(\d+\$)?[sd]")
LITERAL_RE = re.compile(r'Component\s*\.\s*literal\s*\(\s*"((?:[^"\\]|\\.)*)"')
NAME_METHOD_RE = re.compile(
    r'(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?'
    r'(?:net\.minecraft\.network\.chat\.Component|Component|String)\s+'
    r'(getName|getDisplayName|getAutocrafterName|getChamberDisplayName)\s*\(\s*\)\s*\{',
    re.S)
KEY_CONST_RE = re.compile(r'AUTOCRAFTER_NAME_KEY\s*=\s*"([^"]+)"')

# ---------------------------------------------------------------- 允许清单
# 为什么允许：这些字面量都<b>不是绘制出来的玩家文案</b>——
#   * target / range / radius 是 EditBox 的「无障碍名」（AbstractWidget#getMessage → narration），
#     屏幕上看不到；真正的玩家文案在输入框 tooltip 的语言键里；
#   * 含 § 的字符串是颜色码 + 符号（✓ / ▶ / ■），本判据已先剥掉 § 颜色码，正常不会进来。
RENDERED_LITERAL_ALLOWLIST = {
    ("client/screen/QuantityKeeperScreen.java", "target"): "EditBox 无障碍名（不绘制）",
    ("client/screen/AdvancedQuantityKeeperScreen.java", "target"): "EditBox 无障碍名（不绘制）",
    ("client/screen/RangeChargerScreen.java", "range"): "EditBox 无障碍名（不绘制）",
    ("client/screen/CollectionRangeConfigScreen.java", "radius"): "EditBox 无障碍名（不绘制）",
}

# 中文值里允许出现的 ASCII 词（单位 / 修饰键名，本来就不翻译）
ASCII_ALLOWED_TOKENS = {"mB", "Alt", "Ctrl", "Shift", "FE", "RS"}
# 英文值里允许出现中文的键（模组无官方英文名，root 成就标题 = 模组显示名；
# 该断言由 tools/selfcheck_advancements.py 明确检查，这里只做豁免登记，不放宽其它键）
EN_CJK_ALLOWED_KEYS = {"advancements.rs_create_compat.root.title"}


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def section(title):
    print("")
    print("=" * 78)
    print(title)


def read(rel_path):
    with io.open(os.path.join(ROOT, rel_path), encoding="utf-8", errors="ignore") as handle:
        return handle.read()


def read_src(rel_path):
    with io.open(os.path.join(SRC, rel_path.replace("/", os.sep)), encoding="utf-8",
                 errors="ignore") as handle:
        return handle.read()


def strip_comments(text):
    """剥掉 // 与 /* */（字符串字面量里的内容原样保留）。"""
    out = []
    i, n = 0, len(text)
    in_str = None
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == in_str:
                in_str = None
            i += 1
            continue
        if ch in "\"'":
            in_str = ch
            out.append(ch)
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def java_code(rel_path):
    return strip_comments(read_src(rel_path))


def line_of(code, index):
    return code[:index].count("\n") + 1


def method_bodies(code, method_name):
    """返回 [(行号, 方法体)]：按大括号配平切出方法体（含嵌套块）。"""
    pattern = re.compile(
        r'(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?'
        r'[^;{}=()]+?\s+'                      # 返回类型（允许 List<String> 这种泛型）
        + re.escape(method_name) + r'\s*\(\s*\)\s*\{')
    result = []
    for match in pattern.finditer(code):
        start = match.end()
        depth = 1
        i = start
        while i < len(code) and depth:
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
            i += 1
        result.append((line_of(code, match.start()), code[start:i - 1]))
    return result


# ---------------------------------------------------------------- 纯判据（反例复用同一批函数）
def literal_violations(code, label, allowlist):
    """规则 C：含 ASCII 字母的 Component.literal 字面量必须登记。"""
    found = []
    for match in LITERAL_RE.finditer(code):
        literal = match.group(1)
        cleaned = re.sub(r"\u00a7.", "", literal)
        if not re.search(r"[A-Za-z]", cleaned):
            continue
        if (label, literal) in allowlist:
            continue
        found.append("%s:%d 组件字面量 %r（未登记为「非玩家可见」）"
                     % (label, line_of(code, match.start()), literal))
    return found


def name_method_violations(code, label, allowlist):
    """规则 B：名字类方法不得 return 裸字面量 / Component.literal("<字母>")。"""
    found = []
    for line, body in method_bodies(code, "getName"):
        found.extend(_name_body_violations(body, label, line, "getName", allowlist))
    for method in ("getDisplayName", "getAutocrafterName", "getChamberDisplayName"):
        for line, body in method_bodies(code, method):
            found.extend(_name_body_violations(body, label, line, method, allowlist))
    return found


def _name_body_violations(body, label, line, method, allowlist):
    found = []
    for match in re.finditer(r'return\s+"((?:[^"\\]|\\.)*)"\s*;', body):
        found.append("%s:%d %s() 直接返回字符串字面量 %r（必须走语言键）"
                     % (label, line, method, match.group(1)))
    for match in LITERAL_RE.finditer(body):
        literal = match.group(1)
        cleaned = re.sub(r"\u00a7.", "", literal)
        if not re.search(r"[A-Za-z]", cleaned):
            continue
        if (label, literal) in allowlist:
            continue
        found.append("%s:%d %s() 里出现硬编码字面量 %r（必须走 Component.translatable / RsccClientText）"
                     % (label, line, method, literal))
    return found


def rs_autocrafter_name_violations(be_code, zh, en):
    """规则 D①②：自动合成仓机器名必须「语言键 + 客户端解析」。"""
    found = []
    key_match = KEY_CONST_RE.search(be_code)
    if not key_match:
        found.append("缺少 AUTOCRAFTER_NAME_KEY 常量（机器名没有可送的语言键）")
        return found
    key = key_match.group(1)
    if key not in zh or key not in en:
        found.append("AUTOCRAFTER_NAME_KEY=%s 未在中英语言文件里成对存在" % key)
    else:
        if not CJK_RE.search(str(zh[key])):
            found.append("%s 的中文值是纯 ASCII（%r）" % (key, zh[key]))
        if zh[key] == en[key]:
            found.append("%s 的中英值相同（%r）" % (key, zh[key]))
    bodies = dict((line, body) for line, body in method_bodies(be_code, "getAutocrafterName"))
    if not bodies:
        found.append("找不到 getAutocrafterName() 实现")
        return found
    for line, body in bodies.items():
        compact = " ".join(body.split())
        if "autocrafterNameComponent" not in compact:
            found.append("%s:%d getAutocrafterName() 没有交给 autocrafterNameComponent()（"
                         "RS 会在服务端把组件压成 String）" % (BE, line))
        if "blockEntity.getName()" in compact:
            found.append("%s:%d getAutocrafterName() 仍返回 blockEntity.getName()（可翻译组件被固化成英文）"
                         % (BE, line))
    helper = dict((line, body) for line, body in method_bodies(be_code, "autocrafterNameComponent"))
    if not helper:
        found.append("缺少 autocrafterNameComponent() 帮助方法")
    else:
        for line, body in helper.items():
            compact = " ".join(body.split())
            if "RsccClientText" not in compact or "key(" not in compact:
                found.append("%s:%d autocrafterNameComponent() 没有用 RsccClientText.key(...)"
                             % (BE, line))
    return found


def terminal_name_store_violations(terminal_code):
    """规则 D⑤：把名字当数据落盘的那条路必须取语言键，不得存本地化快照。"""
    found = []
    bodies = dict((line, body) for line, body in method_bodies(terminal_code, "getAutocrafterNames"))
    if not bodies:
        return ["找不到 getAutocrafterNames() 实现"]
    for line, body in bodies.items():
        compact = " ".join(body.split())
        if "keyOf(" not in compact:
            found.append("%s:%d getAutocrafterNames() 没有用 RsccClientText.keyOf(...)"
                         "（会把某一时刻的本地化快照当绑定键）" % (TERMINAL, line))
        if "getAutocrafterName().getString()" in compact and "keyOf(" not in compact:
            found.append("%s:%d getAutocrafterNames() 直接 getString()" % (TERMINAL, line))
    return found


def client_resolver_violations(mixin_source, mixins_json_text, mixin_name="client.AutocrafterManagerGroupNameMixin"):
    """规则 D③：客户端解析点必须存在、只挂 client 段、且真的解析。"""
    found = []
    if mixin_source is None:
        return ["客户端解析 Mixin 不存在：%s" % RESOLVER_MIXIN]
    if "@Mixin(AutocrafterManagerData.Group.class)" not in mixin_source:
        found.append("解析 Mixin 没有挂在 AutocrafterManagerData.Group 上")
    if 'method = "name"' not in mixin_source:
        found.append("解析 Mixin 没有注入 AutocrafterManagerData.Group#name")
    if "RsccClientText.resolveText(" not in mixin_source:
        found.append("解析 Mixin 没有调用 RsccClientText.resolveText(...)")
    try:
        mixins = json.loads(mixins_json_text)
    except ValueError as exc:
        return found + ["mixins.json 解析失败：%s" % exc]
    if mixin_name not in mixins.get("client", []):
        found.append("%s 未登记在 mixins.json 的 client 段（客户端不会解析）" % mixin_name)
    if mixin_name in mixins.get("mixins", []):
        found.append("%s 被登记在 common 段（专服会加载客户端解析逻辑）" % mixin_name)
    return found


REGISTRY_RE = re.compile(r'\b(ITEMS|BLOCKS)\.register\(\s*"([^"]+)"')
MAIN_CLASS = "src/main/java/cretae/cookiewyq/rs_create_compat/RS_Create_Compat.java"


def registry_ids():
    """从注册表代码里推出「注册名」：方块/物品显示名就是由它推导出来的语言键。"""
    code = read(MAIN_CLASS)
    result = {"blocks": set(), "items": set()}
    for match in REGISTRY_RE.finditer(code):
        kind, name = match.group(1), match.group(2)
        result["blocks" if kind == "BLOCKS" else "items"].add(name)
    return result


def lang_value_problems(zh, en):
    """规则 A 的后两条（纯函数版：反例直接复用）。"""
    found = []
    for key in sorted(zh):
        value = str(zh[key])
        if CJK_RE.search(value):
            continue
        cleaned = FORMAT_RE.sub(" ", value)
        cleaned = re.sub(r"\u00a7.", " ", cleaned)
        words = [w for w in re.findall(r"[A-Za-z]+", cleaned) if w not in ASCII_ALLOWED_TOKENS]
        if words:
            found.append("zh 值没有中文（疑似英文占位）：%s = %r" % (key, value))
    for key in sorted(en):
        if key in EN_CJK_ALLOWED_KEYS:
            continue
        if CJK_RE.search(str(en[key])):
            found.append("en 值含中文：%s = %r" % (key, en[key]))
    return found


def client_text_dist_safe(client_text_code):
    """规则 D④：RsccClientText 必须 dist 安全。"""
    found = []
    for token in ("net.minecraft.client", "com.mojang.blaze3d", "org.lwjgl"):
        if token in client_text_code:
            found.append("RsccClientText 引用了客户端专属包 %s（专服会崩）" % token)
    if "CompletionBannerPayload.LANG_MARKER" not in client_text_code:
        found.append("RsccClientText 没有复用 CompletionBannerPayload.LANG_MARKER（协议会漂移）")
    return found


# ---------------------------------------------------------------- F/G/H 全工程扫描（纯函数，反例复用）
# 「玩家可见文本出口」：这些调用 / 赋值的产物会走到玩家眼前（聊天、行动栏、横幅 Toast、
# 管理器行、样板 tooltip）。服务端在这些出口里塞 getString() 的结果 ⇒ 专服上必然是英文。
PLAYER_SINK_TOKENS = (
    "CompletionBannerPayload.localized(",
    "CompletionBannerPayload.Row.text(",
    "CompletionBannerPayload.Row.withIcon(",
    "CompletionBannerPayload.Row.segments(",
    "CompletionBannerPayload.Row.colored(",
    "new CompletionBannerPayload.Row(",
    "Component.translatable(",
    "Component.literal(",
    "new SyncAssemblyAlertsPacket.Material(",
    "new SyncAssemblyAlertsPacket.OfflineStep(",
    "new Shortage(",
    "record.productName =",
    "record.materialNames =",
    "texts.add(",
    "resTexts.add(",
    "displayClientMessage(",
    "sendSystemMessage(",
)

# 「日志出口」：服务端日志没有玩家语言可言 ⇒ 这里的 getString() 是正常形态，放行。
# 注意 logThrottled / logState / RsccDiag 也是本工程的日志封装
# （RsccTerminalLocator / SchematicLoaderBlockEntity / RsccDiag），一并算日志。
LOG_SINK_TOKENS = (
    "RsccAssemblyDebug.",
    "throttledLog(",
    "logState(",
    "logThrottled(",
    "LOGGER.",
    "RsccDiag.",
    "System.out",
    "printStackTrace",
)

# 客户端文件整体豁免（附理由）：客户端的 Language 已注入玩家语言，
# getString() 拿到的就是「玩家语言下的最终文本」，只用于排版 / 宽度 / 搜索。
CLIENT_TEXT_EXEMPT_PREFIXES = {
    "client/": "客户端：Language 已注入玩家语言，getString() 得到的就是玩家语言文本（排版/宽度/搜索）",
    "mixin/client/": "客户端 Mixin：同上",
}

# 「未分类」/「已知但本轮不修」的 .getString() 逐条登记：(文件, 语句里必须出现的片段) -> (类别, 理由)。
# 断言会做防腐烂：登记的片段必须仍然存在（该删的条目要删，否则说明清单在腐烂）。
GETSTRING_ALLOWLIST = {
    ("support/AssemblyWatchdog.java", "record.productNamePlain ="):
        ("log-copy", "产物名的服务端日志副本：只进 RsccAssemblyDebug / 诊断快照；"
                     "玩家可见的那一份是 record.productName（语言键编码）"),
    ("support/AssemblyWatchdog.java", "wrapAsHolder(fluid.fluid())"):
        ("log-copy", "resourceNamePlain() 的流体分支：只进日志 / 诊断快照"
                     "（玩家可见走 resourceName 的 fluidNameArg 编码版）"),
    ("support/AssemblyWatchdog.java", "return itemIcon(item).getHoverName().getString();"):
        ("log-copy", "resourceNamePlain() 的物品分支：只进日志 / 诊断快照"
                     "（玩家可见走 resourceName 的 itemNameArg 编码版）"),
    ("support/RsccClientText.java", "DataComponents.CUSTOM_NAME"):
        ("client-gap", "有自定义名时没有可翻译的键 —— 名字本身就是玩家输入的文本，只能送字面量"),
    ("support/RsccClientText.java", "argKey(descriptionId)"):
        ("client-gap", "物品/流体 descriptionId 为空时的兜底（正常注册的物品不会走到）"),
    ("support/RsccClientText.java", "resolve(raw).getString()"):
        ("client", "resolveText 是客户端入口（玩家语言已注入），给只收 String 的第三方绘制/搜索用"),
    ("support/RsccDiag.java", 'row.put("name"'):
        ("diag", "诊断 JSON 快照（/rscc diag 导出），不是玩家可见文本"),
    ("support/UnitPatternDedupe.java", "match.source().getString()"):
        ("data", "去重指纹 / 日志用的描述串，不绘制"),
    ("block/entity/SequencePatternTerminalBlockEntity.java",
     "keyOf(a.getAutocrafterName().getString())"):
        ("data", "名字当绑定数据（keyOf 归一化成语言键），不直接显示"),
    ("block/entity/SequencePatternTerminalBlockEntity.java", "chamber.getName().getString()"):
        ("data", "执行仓名的绑定/轮选数据键（显示走 getChamberDisplayName）"),
}

# 同一类问题、但**不在本轮写入范围**的既存点（蓝图装填器）。它们是真的玩家可见冻结，
# 只是本轮只允许改 4 个文件；已逐条列进交付表 ③，下一轮修掉后请把对应条目删掉。
OUT_OF_SCOPE_PLAYER_TEXT = {
    ("block/entity/SchematicLoaderBlockEntity.java", "firstLine.getString()"):
        "蓝图打印完成横幅：把已经翻好的 Component 再压回 String（专服上是英文）",
    ("block/entity/SchematicLoaderBlockEntity.java", 'getDescription().getString() +'):
        "蓝图完成横幅的资源行：物品名在服务端被固化",
    ("block/entity/SchematicLoaderBlockEntity.java", 'unknown_blueprint").getString()'):
        "打印中止的行动栏提示：未知蓝图名在服务端被固化",
    ("block/entity/SchematicLoaderBlockEntity.java", "blueprint.getHoverName().getString()"):
        "蓝图文件名回退：会显示在完成横幅里（有 SCHEMATIC_FILE 时不触发）",
}


def java_statements(code):
    """把源码切成语句：只在「括号深度 0 且不在字符串里」的 `;` 处切（格式化文案里的分号不算）。

    返回 [(行号, 语句文本)]。行号用于报错定位。
    """
    result = []
    depth = 0
    in_str = None
    start = 0
    i, n = 0, len(code)
    while i < n:
        ch = code[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == in_str:
                in_str = None
            i += 1
            continue
        if ch in "\"'":
            in_str = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif ch == ";" and depth == 0:
            result.append((code[:start].count("\n") + 1, code[start:i + 1]))
            start = i + 1
        i += 1
    return result


def player_text_getstring_hits(code, label, allowlist):
    """规则 F/G：语句里出现 getString() 时，按「玩家出口 / 日志出口 / 未分类」分类判负。

    返回 (violations, used, logs)：
      * violations —— 玩家出口里出现 getString()（且未登记）⇒ 判负；
      * used       —— 命中的登记项集合 {(文件, 片段)}（用于断言清单里没有空登记）；
      * logs       —— 被判定为日志、明确放行的条数（用于证明「日志可以保留」）。
    """
    violations, used, logs = [], set(), 0
    for line, stmt in java_statements(code):
        if ".getString()" not in stmt:
            continue
        player_sink = next((t for t in PLAYER_SINK_TOKENS if t in stmt), None)
        is_log = any(t in stmt for t in LOG_SINK_TOKENS)
        registered = [(lbl, marker) for (lbl, marker) in allowlist
                      if lbl == label and marker in stmt]
        used.update(registered)
        if player_sink is not None:
            # 玩家出口优先于日志出口（同一语句里既拼玩家文本又打日志时，按玩家文本判）
            if registered:
                continue
            violations.append("%s:%d 用 getString() 拼进玩家可见文本（%s）：%s"
                              % (label, line, player_sink, " ".join(stmt.split())[:110]))
            continue
        if is_log:
            logs += 1
            continue
        if registered:
            continue
        violations.append("%s:%d 未分类的 getString()（必须登记它是日志 / 数据 / 客户端，"
                          "否则可能是新的玩家文本冻结）：%s"
                          % (label, line, " ".join(stmt.split())[:110]))
    return violations, used, logs


def allowlist_rot(all_sources, allowlist):
    """允许清单防腐烂：登记的片段必须仍然存在。"""
    stale = []
    for (label, marker) in sorted(allowlist):
        if label not in all_sources:
            stale.append("%s（文件不存在）" % label)
        elif marker not in all_sources[label]:
            stale.append("%s :: %r" % (label, marker))
    return stale


def method_body_named(code, name):
    """按名字取方法体（含参数的方法也支持；跳过同名的调用点）。取不到返回 ""。"""
    pattern = re.compile(r"\b" + re.escape(name) + r"\s*\(")
    for match in pattern.finditer(code):
        brace = code.find("{", match.end())
        if brace < 0:
            continue
        if ";" in code[match.end():brace]:
            continue  # 这是调用点，不是方法声明
        depth = 0
        i = brace
        while i < len(code):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    return code[brace + 1:i]
            i += 1
    return ""


def encoded_carrier_violations(sources):
    """规则 H：玩家可见的名字必须是语言键编码；日志副本必须与玩家文本分开。"""
    found = []
    wd = sources.get("support/AssemblyWatchdog.java", "")
    chamber = sources.get("block/entity/SequenceExecutionChamberBlockEntity.java", "")
    client_text = sources.get("support/RsccClientText.java", "")
    toast = sources.get("client/CompatCompletionToast.java", "")

    if "RsccClientText.itemNameArg(pattern.product())" not in wd:
        found.append("AssemblyWatchdog 产物名没有用 RsccClientText.itemNameArg(...) 编码")
    if "record.productNamePlain" not in wd:
        found.append("AssemblyWatchdog 没有 record.productNamePlain（日志副本与玩家文本没有分开）")
    if ".map(stack -> RsccClientText.itemNameArg(stack))" not in wd:
        found.append("AssemblyWatchdog 缺料名没有用 RsccClientText.itemNameArg(...) 编码")
    if "RsccClientText.itemNameArg(itemIcon(item))" not in wd:
        found.append("AssemblyWatchdog resourceName 的物品分支没有走 itemNameArg")
    if "RsccClientText.fluidNameArg(" not in wd:
        found.append("AssemblyWatchdog resourceName 的流体分支没有走 fluidNameArg")

    offline = method_body_named(wd, "offlineSteps")
    if "RsccClientText.argRecipeType(" not in offline:
        found.append("offlineSteps() 的配方名没有用 RsccClientText.argRecipeType(...) 编码")
    if "RecipeTypeNames.of(" in offline:
        found.append("offlineSteps() 仍在服务端把配方名解析成 String（专服上只有英文）")
    offline_row = method_body_named(wd, "offlineRow")
    if "KEY_OFFLINE_STEP" not in offline_row:
        found.append("offlineRow() 的「配方名（机器 @坐标）」没有走语言键 KEY_OFFLINE_STEP")
    if '+ "（"' in offline_row:
        found.append("offlineRow() 仍用字符串拼横幅段（服务端固化 + 全角括号对英文玩家是错的）")

    if "RsccClientText.itemNameArg(shortageIcon)" not in chamber:
        found.append("缺料横幅的物品名没有用 itemNameArg 编码")
    if "RsccClientText.fluidNameArg(shortageFluid)" not in chamber:
        found.append("缺料横幅的流体名没有用 fluidNameArg 编码")
    if "RsccClientText.itemNameArg(new ItemStack(preferred))" not in chamber:
        found.append("换料横幅的名称没有用 itemNameArg 编码")
    if ('"gui.rs_create_compat.intermediate_visible.entry"' not in chamber
            or "RsccClientText.itemNameArg(entry)" not in chamber):
        found.append("中间产物横幅没有走「语言键 + itemNameArg」")

    for required in ("ARG_KEY_MARKER", "ARG_RECIPE_TYPE_MARKER", "argument(", "resolveSegment(",
                     "itemNameArg(", "fluidNameArg(", "argRecipeType(",
                     "args[i - 1] = argument(parts[i]);"):
        if required not in client_text:
            found.append("RsccClientText 缺少 %s（协议扩展不完整）" % required)

    if "RsccClientText.argument(parts[i])" not in toast:
        found.append("横幅 Toast 没有走 RsccClientText.argument(...)（嵌套语言键不会被翻译）")
    if "RsccClientText.resolveSegment(raw)" not in toast:
        found.append("横幅 Toast 的「单独成段的名字」没有走 RsccClientText.resolveSegment(...)"
                     "（原料名会以 \\u0003 原文显示）")
    if "CompletionBannerPayload.LANG_MARKER" not in toast or "Component.translatable(parts[0]" not in toast:
        found.append("横幅 Toast 不再按 LANG_MARKER + Component.translatable(键, 参数) 解析（既有协议被破坏）")
    return found


# ---------------------------------------------------------------- 主流程
def main():
    print("第 74 轮自检：玩家可见文本的本地化（RS 自动合成仓管理器机器名 + 全工程同类）")

    section("A 语言键：成对 / 非空 / 中文非纯 ASCII / 英文不含中文（复用 audit_lang_keys.py）")
    audit = subprocess.run([sys.executable, os.path.join(ROOT, "tools", "audit_lang_keys.py")],
                           cwd=ROOT, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
    audit_tail = [line for line in audit.stdout.splitlines() if line.strip()][-6:]
    check("audit_lang_keys.py 退出码 0", audit.returncode == 0, "\n".join(audit_tail))
    check("audit_lang_keys.py 问题总数: 0", "问题总数: 0" in audit.stdout, "\n".join(audit_tail))
    check("audit_lang_keys.py 孤儿键：0 条", "孤儿键：0 条" in audit.stdout, "\n".join(audit_tail))

    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
    check("语言文件当前状态满足「中文有中文 / 英文无中文」",
          not lang_value_problems(zh, en), "; ".join(lang_value_problems(zh, en)[:3]))

    # A2：方块/物品的显示名走 Block/Item#getDescriptionId()（＝注册名推导的 block./item. 键），
    #     因此「注册了但语言键缺失」就会直接在物品栏 / 管理器里露出裸键。
    #     注意：BlockItem（含 RS 的 BaseBlockItem）的 descriptionId 用的是**方块**那条键，
    #     所以物品只要求「item. 或 block. 二者之一」成对存在（只有独立 Item 才必须有 item.）。
    registered = registry_ids()
    missing_block_keys = sorted("block.rs_create_compat." + name for name in registered["blocks"]
                                if "block.rs_create_compat." + name not in zh
                                or "block.rs_create_compat." + name not in en)
    missing_item_keys = sorted(
        name for name in registered["items"]
        if not any(("item.rs_create_compat." + name in zh and "item.rs_create_compat." + name in en,
                    "block.rs_create_compat." + name in zh and "block.rs_create_compat." + name in en)))
    check("全部注册方块都有中英显示名", not missing_block_keys, ", ".join(missing_block_keys[:6]))
    check("全部注册物品都有中英显示名（item. 或 block.）", not missing_item_keys,
          ", ".join(missing_item_keys[:6]))

    section("B/C 代码里不出现「给玩家看的硬编码字符串」")
    all_sources = {}
    for base, _dirs, names in os.walk(SRC):
        for file_name in sorted(names):
            if not file_name.endswith(".java"):
                continue
            full = os.path.join(base, file_name)
            label = os.path.relpath(full, SRC).replace("\\", "/")
            all_sources[label] = strip_comments(
                io.open(full, encoding="utf-8", errors="ignore").read())

    literal_hits = []
    for label, code in sorted(all_sources.items()):
        literal_hits.extend(literal_violations(code, label, RENDERED_LITERAL_ALLOWLIST))
    check("全工程 Component.literal 无未登记的字母字面量", not literal_hits, "; ".join(literal_hits[:5]))

    stale = []
    for (label, literal) in sorted(RENDERED_LITERAL_ALLOWLIST):
        code = all_sources.get(label, "")
        if not any(m.group(1) == literal for m in LITERAL_RE.finditer(code)):
            stale.append("%s:%r" % (label, literal))
    check("允许清单无腐烂项（登记的字面量仍存在）", not stale, "; ".join(stale))

    name_hits = []
    for label, code in sorted(all_sources.items()):
        name_hits.extend(name_method_violations(code, label, RENDERED_LITERAL_ALLOWLIST))
    check("名字类方法（getName/getDisplayName/getAutocrafterName/getChamberDisplayName）无硬编码",
          not name_hits, "; ".join(name_hits[:5]))

    section("D RS 集成点：机器名走「语言键 + 客户端解析」")
    be_code = strip_comments(read_src(BE))
    name_problems = rs_autocrafter_name_violations(be_code, zh, en)
    check("序列装配样板库的自动合成仓名已改为语言键", not name_problems, "; ".join(name_problems[:5]))

    key_match = KEY_CONST_RE.search(be_code)
    key_name = key_match.group(1) if key_match else "<未找到>"
    check("机器名语言键中英成对且中文含中文",
          key_name in zh and key_name in en and CJK_RE.search(str(zh.get(key_name, ""))) is not None,
          "%s zh=%r en=%r" % (key_name, zh.get(key_name), en.get(key_name)))

    terminal_problems = terminal_name_store_violations(strip_comments(read_src(TERMINAL)))
    check("getAutocrafterNames() 取语言键（不落本地化快照）", not terminal_problems,
          "; ".join(terminal_problems[:5]))

    mixin_path = os.path.join(SRC, RESOLVER_MIXIN.replace("/", os.sep))
    mixin_source = io.open(mixin_path, encoding="utf-8", errors="ignore").read() \
        if os.path.exists(mixin_path) else None
    resolver_problems = client_resolver_violations(
        mixin_source, io.open(MIXINS_JSON, encoding="utf-8").read())
    check("客户端解析点存在且只挂 client 段", not resolver_problems, "; ".join(resolver_problems[:5]))

    dist_problems = client_text_dist_safe(strip_comments(read_src(CLIENT_TEXT)))
    check("RsccClientText dist 安全（专服可加载）", not dist_problems, "; ".join(dist_problems))

    banner_code = strip_comments(read_src(BANNER))
    check("完成横幅与本轮共用同一套语言键标记",
          "LANG_MARKER = '\\u0001'" in banner_code or "LANG_MARKER = '\u0001'" in banner_code,
          "CompletionBannerPayload.LANG_MARKER 不再是 \\u0001")

    section("F 全工程「String → 玩家可见 Component」扫描（服务端可达代码）")
    all_allow = list(GETSTRING_ALLOWLIST) + list(OUT_OF_SCOPE_PLAYER_TEXT)
    scan_hits, scan_used, scan_logs = [], set(), 0
    for label, code in sorted(all_sources.items()):
        if label.startswith(tuple(CLIENT_TEXT_EXEMPT_PREFIXES)):
            continue
        hits, used, logs = player_text_getstring_hits(code, label, all_allow)
        scan_hits.extend(hits)
        scan_used.update(used)
        scan_logs += logs
    check("F 没有「getString() 拼进玩家可见文本」的新写法（服务端可达代码）",
          not scan_hits, "; ".join(scan_hits[:5]))
    check("F 允许清单无腐烂项（登记的片段仍存在）",
          not allowlist_rot(all_sources, GETSTRING_ALLOWLIST),
          "; ".join(allowlist_rot(all_sources, GETSTRING_ALLOWLIST)[:5]))

    section("G 日志 vs 玩家可见：机器区分（日志允许 getString()，玩家文本不允许）")
    check("G 日志出口的 getString() 未被误判（%d 条放行）" % scan_logs, scan_logs > 0,
          "一条日志都没放行 —— 判据可能把所有 getString() 都判负了")
    unused = sorted(set(all_allow) - scan_used)
    check("G 允许清单每一项都真实命中（没有空登记）", not unused,
          "; ".join("%s :: %r" % item for item in unused[:5]))
    check("G 范围外的同一类问题仍是蓝图装填器的 %d 条（没有增加，已列进交付表）"
          % len(OUT_OF_SCOPE_PLAYER_TEXT),
          not allowlist_rot(all_sources, OUT_OF_SCOPE_PLAYER_TEXT),
          "; ".join(allowlist_rot(all_sources, OUT_OF_SCOPE_PLAYER_TEXT)[:5]))
    check("G 日志行仍然保留 getString()（没有为了判据去改服务端日志）",
          "probe.getHoverName().getString()" in all_sources.get(
              "block/entity/SequenceExecutionChamberBlockEntity.java", "")
          and "record.productNamePlain" in all_sources.get("support/AssemblyWatchdog.java", ""),
          "日志行被改掉了 —— 服务端日志保留原样英文是刻意行为")

    section("H 编码载体：玩家可见的名字必须是语言键（客户端解析）")
    carrier_problems = encoded_carrier_violations(all_sources)
    check("H 产物名 / 缺料名 / 换料名 / 中间产物名 / 离线配方名都走语言键编码",
          not carrier_problems, "; ".join(carrier_problems[:6]))
    for key in ("gui.rs_create_compat.watchdog.offline_step",
                "gui.rs_create_compat.intermediate_visible.entry"):
        check("H 新语言键中英成对：%s" % key, key in zh and key in en)

    section("E 反例自证（修前写法必须被判负）")
    prefix_be = (
        "public class FakeBe {\n"
        "    public static final String AUTOCRAFTER_NAME_KEY = \"block.rs_create_compat.sequence_assembly_executor\";\n"
        "    @Override\n"
        "    public Component getName() {\n"
        "        return getBlockState().getBlock().getName();\n"
        "    }\n"
        "    @Override\n"
        "    public Component getAutocrafterName() {\n"
        "        return blockEntity.getName();\n"
        "    }\n"
        "}\n")
    counter = rs_autocrafter_name_violations(prefix_be, zh, en)
    check("反例① getAutocrafterName() 返回 blockEntity.getName() ⇒ 判负", bool(counter),
          "判据没有抓住修前写法")

    prefix_terminal = (
        "class FakeTerminal {\n"
        "    public List<String> getAutocrafterNames() {\n"
        "        return network.getComponent(GraphNetworkComponent.class).getContainers(Autocrafter.class)\n"
        "            .stream().map(a -> a.getAutocrafterName().getString()).toList();\n"
        "    }\n"
        "}\n")
    counter = terminal_name_store_violations(prefix_terminal)
    check("反例② getAutocrafterNames() 直接 getString() ⇒ 判负", bool(counter),
          "判据没有抓住修前写法")

    hardcoded_name = (
        "class FakeBe {\n"
        "    public Component getAutocrafterName() {\n"
        "        return Component.literal(\"Sequence Assembly Pattern Vault\");\n"
        "    }\n"
        "}\n")
    counter = name_method_violations(hardcoded_name, "FakeBe.java", RENDERED_LITERAL_ALLOWLIST)
    counter += rs_autocrafter_name_violations(hardcoded_name, zh, en)
    check("反例③ 机器名硬编码英文 ⇒ 判负", bool(counter), "判据没有抓住硬编码英文")

    counter = lang_value_problems(
        dict(zh, **{"block.rs_create_compat.fake": "Sequence Assembly Pattern Vault"}),
        dict(en, **{"block.rs_create_compat.fake_cn": "序列装配样板库"}))
    check("反例④ 中文值是英文占位 / 英文值含中文 ⇒ 判负", bool(counter), "判据没有抓住语言文件问题")

    bad_mixins = json.dumps({"mixins": ["client.AutocrafterManagerGroupNameMixin"], "client": []})
    counter = client_resolver_violations(mixin_source, bad_mixins)
    check("反例⑤ 解析 Mixin 挂到 common 段 ⇒ 判负", bool(counter), "判据没有抓住 dist 违规")

    counter = literal_violations(
        'Component.literal("Sequence Assembly Pattern Vault")', "Fake.java", {})
    check("反例⑥ 名字里出现 Component.literal(\"英文\") ⇒ 判负", bool(counter),
          "判据没有抓住硬编码字面量")

    # ---- 第 2 批（本轮）：F/G 的反例（修前的 4+2 处写法 / 日志与玩家文本的区分）----
    prefix_product = (
        "record.productName = pattern != null\n"
        "    ? pattern.product().getHoverName().getString() : resourceName(resource);\n")
    counter, _used, _logs = player_text_getstring_hits(prefix_product, "Fake.java", {})
    check("反例⑦ 产物名取 getHoverName().getString() ⇒ 判负", bool(counter),
          "判据没有抓住「产物名在服务端被固化」")

    prefix_materials = (
        "record.materialNames = record.materialIcons.stream()\n"
        "    .map(stack -> stack.getHoverName().getString()).toList();\n")
    counter, _used, _logs = player_text_getstring_hits(prefix_materials, "Fake.java", {})
    check("反例⑧ 缺料名取 getHoverName().getString() ⇒ 判负", bool(counter),
          "判据没有抓住「缺料名在服务端被固化」")

    prefix_shortage = (
        "shortages.add(new Shortage(shortageIcon, shortageIcon.getHoverName().getString(), shortBy,\n"
        "    KEY_SHORTAGE_ENTRY_ITEM));\n")
    counter, _used, _logs = player_text_getstring_hits(prefix_shortage, "Fake.java", {})
    check("反例⑨ 缺料记录里先固化名字 ⇒ 判负", bool(counter),
          "判据没有抓住「先 getString() 再进横幅记录」")

    prefix_handoff = (
        "texts.add(CompletionBannerPayload.localized(KEY_HANDOFF_ENTRY,\n"
        "    new ItemStack(preferred).getHoverName().getString(),\n"
        "    new ItemStack(pair.actual()).getHoverName().getString()));\n")
    counter, _used, _logs = player_text_getstring_hits(prefix_handoff, "Fake.java", {})
    check("反例⑩ 换料横幅把 getString() 当语言键参数 ⇒ 判负", bool(counter),
          "判据没有抓住「参数位置上的固化字符串」")

    prefix_intermediate = (
        "rows.add(CompletionBannerPayload.Row.withIcon(0xFFE6E6E6, entry,\n"
        "    entry.getHoverName().getString() + \" ×\" + entry.getCount()));\n")
    counter, _used, _logs = player_text_getstring_hits(prefix_intermediate, "Fake.java", {})
    check("反例⑪ 中间产物横幅用 getString() 拼段 ⇒ 判负", bool(counter),
          "判据没有抓住「拼进横幅段的字符串」")

    # 日志 vs 玩家文本：同一表达式，换一个出口，结论必须相反（这是判据能区分两者的自证）
    log_case = (
        "private void f(final ItemStack probe) {\n"
        "    throttledLog(\"x {}\", probe.getHoverName().getString());\n"
        "}\n")
    counter, _used, logs = player_text_getstring_hits(log_case, "Fake.java", {})
    check("反例⑫ 同一写法放进日志出口 ⇒ 不判负（日志允许 getString）",
          not counter and logs == 1,
          "日志被误判成玩家文本：%s" % counter)

    player_case = (
        "private void f(final ItemStack probe) {\n"
        "    Component.literal(probe.getHoverName().getString());\n"
        "}\n")
    counter, _used, _logs = player_text_getstring_hits(player_case, "Fake.java", {})
    check("反例⑬ 同一写法放进玩家出口 ⇒ 判负", bool(counter),
          "玩家出口漏判 —— 说明判据没抓住「用途」")

    # H 的反例：把编码换回旧口径，必须判负
    wd_fixed = all_sources.get("support/AssemblyWatchdog.java", "")
    mutated = dict(all_sources)
    mutated["support/AssemblyWatchdog.java"] = wd_fixed.replace(
        "RsccClientText.argRecipeType(unit.recipeType())", "RecipeTypeNames.of(unit.recipeType())")
    check("反例⑭ 离线配方名改回 RecipeTypeNames.of(...) ⇒ 判负",
          bool(encoded_carrier_violations(mutated)) and mutated["support/AssemblyWatchdog.java"] != wd_fixed,
          "判据没有抓住「配方名在服务端解析」")

    mutated = dict(all_sources)
    mutated["support/AssemblyWatchdog.java"] = wd_fixed.replace(
        "CompletionBannerPayload.localized(KEY_OFFLINE_STEP,",
        "CompletionBannerPayload.localized(KEY_OFFLINE_PREFIX,")
    check("反例⑮ 离线行改回字符串拼接（不走 KEY_OFFLINE_STEP）⇒ 判负",
          bool(encoded_carrier_violations(mutated)),
          "判据没有抓住「掉线行用 + 拼横幅段」")

    section("结论")
    print("检查项：%d，失败：%d" % (CHECKS[0], len(FAILURES)))
    if FAILURES:
        for failure in FAILURES:
            print("  - %s" % failure)
        print("")
        print("失败总数: %d" % len(FAILURES))
        return 1
    print("失败总数: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
