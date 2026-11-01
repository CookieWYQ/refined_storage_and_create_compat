#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 81 轮（第 86 轮修订）：**剪贴板 tooltip 附加显示**的源码级自检 + 反例自证。

用户原话：「他并没有在这个剪贴板的这个 tooltip 上面能够显示出来他复制了什么配置……需要你能够给这个
tooltip 增加一些附加显示，就是可以显示出它复制了什么方块的什么配置」；第 86 轮又要求：
「剪贴板的这个 tooltip 显示的信息太有限了，你可以显示个概括的信息，
但是必须能让玩家看到更为详细的信息，比如说按下 shift 键」。

本脚本不跑游戏，只把「这轮承诺过的硬要求」固化成**可复跑**的源码判据，并用最小反例证明
这些判据不是空转（反例见文件末尾）：

  ⓪ **概括常显 / 详情按 Shift（第 86 轮新增）**：
     · `onItemTooltip` 必须先把 `summaryFor(...)` 的结果无条件 `addAll` 到 tooltip
       （这一步在任何按键门控之前 ⇒ 不按 Shift 也能看到「复制了什么」的概括）；
     · 详情构造入口 `linesFor(...)` **只能**出现在 `showDetails` 里，`onItemTooltip` 直接调用它 ⇒ 判负；
     · `onItemTooltip` 必须在 `RsccTooltipLayers.held(...SHIFT...)` 的门控之后才调 `showDetails(...)`；
     · 详情必须走 `RsccTooltipLayers.append(...)` + `allShift(...)`（工程既有分层唯一实现），
       不许自己 `tooltip.addAll(linesFor(...))`；
     · 概括行有独立的身份缓存（与详情缓存分开），不在每帧重拼；
     · **反例自证**：只按 Shift 才显示（没有常显）/ 详情不按 Shift 就全拼 / 详情不走分层实现
       ⇒ 同一批判据必须全部判负。
  ① 开关：关掉 ⇒ 配置行一行都不出现（只在「确有本模组配置」时留一行「怎么打开」的提示）；
     打开 ⇒ 才走到追加。判据要求「早退」出现在任何 `getToolTip().addAll` **之前**，
     且 `on|off` 两个分支都在指令树里；
  ①b **默认值必须是「开」**（第 82 轮修正）：用户实机反馈「剪贴板 tooltip 没有任何区别」，
     根因就是这个开关默认关。判据同时要求：字段初值 = true、读盘默认值 = "true"、
     且源码里不再出现默认 "false" 的读法；
  ①c **可发现性**：关掉时仍有一行提示（键 `gui.rs_create_compat.clipboard_tooltip.hint.off`），
     且这一行只在「剪贴板里确实有本模组配置」时加（别人的剪贴板不受影响）；
  ② 性能：**不许每帧解析 NBT**。判据要求 tooltip 事件方法里没有任何 NBT 读取，解析只在
     身份缓存未命中时发生，且命中时直接 `return cachedLines`；
  ③ 安全降级：剪贴板为空 / 组件缺失 / 段类型不对 / 别的模组格式 / 解析抛异常 ⇒ 安全地不显示，
     绝不让 tooltip 崩。判据要求 null / 空 / `Tag.TAG_COMPOUND` 类型判断与 try/catch 都在；
  ④ 本地化：新增键中英成对、源码静态引用、占位符数量一致；
  ⑤ 数据源：读的是**物品数据组件** `AllDataComponents.CLIPBOARD_CONTENT`，不是系统剪贴板
     （`keyboardHandler.getClipboard()` 一次都不许出现）；
  ⑥ 专服安全：两个客户端类都声明 `@EventBusSubscriber(value = Dist.CLIENT)`，common 侧的
     `RsccBusClipboard` 不含任何 `net.minecraft.client.*` 引用。

用法：
    python tools/selfcheck_round81_clipboard_tooltip.py
退出码：0 = 全部通过；1 = 有断言失败。
"""

from __future__ import annotations

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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
HANDLER = os.path.join(PKG, "client", "tooltip", "ClipboardTooltipHandler.java")
SETTING = os.path.join(PKG, "client", "tooltip", "ClipboardTooltipSetting.java")
BUS_CLIPBOARD = os.path.join(PKG, "support", "RsccBusClipboard.java")
JADE = os.path.join(PKG, "compat", "jade", "RsccBusJadePayload.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

KEY_PREFIX_GUI = "gui.rs_create_compat.clipboard_tooltip."
KEY_PREFIX_MSG = "message.rs_create_compat.clipboard_tooltip."
KEY_RE = re.compile(r'"((?:gui|message)\.rs_create_compat\.clipboard_tooltip\.[^"\\]+)"')
FORMAT_RE = re.compile(r"%(\d+\$)?[sd]")

# tooltip 事件方法里**不该出现**的 NBT 读取入口（出现即等于「每帧解析 NBT」）。
NBT_TOKENS = ("copiedValues", "getCompound", "getList(", "getString(", "getInt(", "getAllKeys")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """把 // 与 /* */ 注释替换成空格（保留字符串字面量），用于「代码里不许出现 X」这类判据。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _matching_paren(text, open_index):
    """从 `(` 开始找配对的 `)`（跳过字符串），返回 `)` 之后的下标；找不到返回 -1。"""
    depth = 0
    i = open_index
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def method_body(text, name):
    """取出 `name(...) { ... }` 这个方法的花括号体。

    为什么不能简单地 `find(name + "(")` 后找第一个 `{`：`linesFor(...)` 在别处只是**调用**，
    它后面第一个 `{` 是下一句 `if` 的块 —— 早先的写法会取到完全无关的一段，判据因此假失败。
    这里改为逐个候选：跳过括号对，只有紧跟 `{` 的那一个才是方法声明。
    """
    pos = 0
    brace = -1
    n = len(text)
    while True:
        idx = text.find(name + "(", pos)
        if idx < 0:
            return ""
        end = _matching_paren(text, idx + len(name))
        if end < 0:
            return ""
        k = end
        while k < n and text[k].isspace():
            k += 1
        if k < n and text[k] == "{":
            brace = k
            break
        pos = idx + 1
    depth = 0
    i = brace
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
        i += 1
    return ""


# ----------------------------------------------------------------------
# 判据（同一批函数既判真实源码，也判文件末尾的反例）
# ----------------------------------------------------------------------

def has_switch_guard(src):
    """开关：`onItemTooltip` 里必须先问开关、先 return，之后才允许追加行。"""
    body = method_body(src, "onItemTooltip")
    if not body:
        return False
    guard = body.find("isEnabled()")
    add = body.find("getToolTip()")
    if guard < 0 or add < 0 or add < guard:
        return False
    return "return" in body[guard:add]


def default_is_on(src):
    """①b 默认值 = 开：字段初值 true + 读盘默认 "true" + 不再有默认 "false" 的读法。"""
    return (
        "private static volatile boolean enabled = true;" in src
        and 'getProperty(PROPERTY, "true")' in src
        and 'getProperty(PROPERTY, "false")' not in src
    )


def has_disabled_hint(src):
    """①c 关掉时仍有一行「怎么打开」的提示，且只在确有本模组配置时加。"""
    body = method_body(src, "onItemTooltip")
    if not body:
        return False
    guard = body.find("isEnabled()")
    call = body.find("addDisabledHint(event)")
    ret = body.find("return", guard)
    if guard < 0 or call < 0 or ret < 0 or not (guard < call < ret):
        return False
    hint = method_body(src, "addDisabledHint")
    if not hint:
        return False
    return (
        "KEY_HINT_OFF" in hint
        and "RsccBusClipboard.clipboardKey()" in hint
        and "Tag.TAG_COMPOUND" in hint
        and hint.find("return") < hint.find("getToolTip()")
    )


def has_summary_always(src):
    """⓪a 概括常显：`summaryFor(...)` 的结果必须无条件 addAll，且在任何 Shift 门控之前。"""
    body = method_body(src, "onItemTooltip")
    if not body:
        return False
    summary = body.find("summaryFor(")
    add = body.find("addAll(summary)")
    gate = body.find("RsccTooltipLayers.held(")
    if summary < 0 or add < 0 or gate < 0:
        return False
    return summary < add < gate


def has_shift_gate(src):
    """⓪b 详情按 Shift：`showDetails(...)` 必须在 `if (RsccTooltipLayers.held(...SHIFT...))` 之内。"""
    body = method_body(src, "onItemTooltip")
    if not body:
        return False
    gate = body.find("RsccTooltipLayers.held(")
    detail = body.find("showDetails(")
    if gate < 0 or detail < 0 or detail < gate:
        return False
    # 门控条件本身必须点到 SHIFT，且前面紧跟着 if (
    return "SHIFT" in body[gate:detail] and "if (" in body[max(0, gate - 8):gate]


def details_not_in_event(src):
    """⓪b′ 详情构造入口只能在 `showDetails` 里：事件方法直接调 `linesFor(...)` ⇒ 每帧全拼。"""
    return "linesFor(" not in method_body(src, "onItemTooltip")


def details_use_layers(src):
    """⓪c 详情必须走工程既有分层实现：`append` + `allShift` + `linesFor` 三件套。"""
    body = method_body(src, "showDetails")
    if not body:
        return False
    return ("RsccTooltipLayers.append(" in body and "RsccTooltipLayers.allShift(" in body
            and "linesFor(" in body)


def has_summary_cache(src):
    """⓪d 概括行有独立身份缓存（与详情缓存分开；命中即 return，不重拼）。"""
    body = method_body(src, "summaryFor")
    if not body:
        return False
    hit = body.find("== cachedSummaryContent")
    build = body.find("buildSummary(")
    if hit < 0 or build < 0 or build < hit:
        return False
    return "return cachedSummaryLines" in body[hit:build]


def disabled_single_line(src):
    """⓪e 关掉开关时只有一行提示：必须在 `summaryFor(...)` 之前 return。"""
    body = method_body(src, "onItemTooltip")
    if not body:
        return False
    guard = body.find("isEnabled()")
    ret = body.find("return", guard)
    summary = body.find("summaryFor(")
    if guard < 0 or ret < 0 or summary < 0:
        return False
    return ret < summary and "getToolTip()" not in body[guard:ret]


def has_lazy_cache(src):
    """性能：有身份缓存；事件方法里零 NBT 读取；解析只在缓存未命中时发生。"""
    if "ClipboardContent cachedContent" not in src or "cachedLines" not in src:
        return False
    body = method_body(src, "linesFor")
    if not body:
        return False
    hit = body.find("== cachedContent")
    build = body.find("build(content")
    if hit < 0 or build < 0 or build < hit:
        return False
    if "return cachedLines" not in body[hit:build]:
        return False
    event_body = method_body(src, "onItemTooltip")
    if not event_body:
        return False
    for token in NBT_TOKENS:
        if token in event_body:
            return False
    return True


def has_safe_degrade(src):
    """安全降级：null / 空 / 段类型 / 异常四道闸都在。"""
    body = method_body(src, "linesFor")
    build = method_body(src, "build")
    if not body or not build:
        return False
    if "content == null" not in body:
        return False
    if "copied == null" not in build or "isEmpty()" not in build:
        return False
    if "catch (final RuntimeException" not in body and "catch (RuntimeException" not in body:
        return False
    if "List.of()" not in body[body.find("catch"):]:
        return False
    # 只有确实声明为 compound 才 getCompound（类型不对 ⇒ 跳过，不硬解）
    if "contains(RsccBusClipboard.clipboardKey(), Tag.TAG_COMPOUND)" not in src:
        return False
    if "contains(RsccBusConfig.KEY_FILTER, Tag.TAG_COMPOUND)" not in src:
        return False
    return True


def has_command(src):
    """指令：`clipboard-tooltip on|off` 两个分支 + 客户端指令注册 + 持久化文件。"""
    needed = ('"clipboard-tooltip"', '"on"', '"off"', "RegisterClientCommandsEvent",
              "rs_create_compat-client.properties", "Boolean.parseBoolean", "Files.newBufferedWriter")
    return all(token in src for token in needed)


def has_client_only_events(handler, setting):
    for src in (handler, setting):
        if "@EventBusSubscriber" not in src or "value = Dist.CLIENT" not in src:
            return False
    return True


def uses_item_component_not_system_clipboard(src):
    code = strip_comments(src)  # 注释里提到系统剪贴板不算（本类就要解释「它不是系统剪贴板」）
    if "keyboardHandler" in code or ".getClipboard(" in code:
        return False
    return "AllDataComponents.CLIPBOARD_CONTENT" in code


def lang_pairs(zh, en, refs):
    """中英键集合一致 + 源码静态引用齐全 + 占位符数量一致。"""
    problems = []
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    if only_zh:
        problems.append("仅 zh 有：%s" % only_zh[:5])
    if only_en:
        problems.append("仅 en 有：%s" % only_en[:5])
    missing = sorted(refs - set(zh))
    unused = sorted(set(zh) - refs)
    if missing:
        problems.append("源码引用但语言缺：%s" % missing[:5])
    if unused:
        problems.append("语言有但源码未引用：%s" % unused[:5])
    for key in sorted(set(zh) & set(en)):
        if len(FORMAT_RE.findall(str(zh[key]))) != len(FORMAT_RE.findall(str(en[key]))):
            problems.append("占位符数量不一致：%s" % key)
    return problems


# ----------------------------------------------------------------------
# 第 87 轮：Shift 详情「至少对标 Jade」的判据
# ----------------------------------------------------------------------
#
# 用户原话：「你这个说实话就太简陋了，还不如我直接看这个 Jade 的信息更高效。所以说你起码得对标
# Jade 的信息显示吧。」因此这里把 Jade 显示的信息<b>逐类列成清单</b>，要求剪贴板详情覆盖每一类；
# 反例只删掉其中一类 ⇒ 同一批判据必须判负（证明这条清单不是摆设）。

JADE_GROUPS = ("materials", "feedstock", "fluids", "products", "scrap", "intermediates")


def jade_categories(jade_src):
    """Jade 对一条已绑定总线显示的全部信息类别（= 「对标」的基准清单）。

    返回 {类别名: 该类别在 RsccBusJadePayload 里是否仍然完整}。基准自己缺项 ⇒ 也判负：
    否则「剪贴板照抄一份不完整的 Jade」会被当成通过。
    六个分节以 {@code GROUP_ORDER} 的**实际列表**为准（常量名在别处也可能被引用，
    只查「名字出现过」会把「从顺序里删掉一节」误判成仍然完整）。
    """
    order_match = re.search(r"GROUP_ORDER\s*=\s*List\.of\((.*?)\);", jade_src, re.S)
    order = order_match.group(1) if order_match else ""
    sections = {
        "六节·原料": "GROUP_MATERIALS",
        "六节·输入时原料": "GROUP_FEEDSTOCK",
        "六节·流体": "GROUP_FLUIDS",
        "六节·成品": "GROUP_PRODUCTS",
        "六节·废料": "GROUP_SCRAP",
        "六节·中间产物": "GROUP_INTERMEDIATES",
    }
    categories = {
        "归属执行舱": '"linked"' in jade_src,
        "自动模式": '"auto"' in jade_src,
        "方向（输入 / 输出）": '"dir.importer"' in jade_src and '"dir.exporter"' in jade_src,
        "当前没有负责的类别": '"none"' in jade_src,
    }
    for name, constant in sections.items():
        categories[name] = constant in jade_src and constant in order
    return categories


def clipboard_covers_jade(handler, jade_src):
    """剪贴板 Shift 详情必须覆盖 Jade 的全部类别 + 用户要求的补充类别；返回违规清单。"""
    problems = []
    # ① 基准本身必须完整
    for name, ok in jade_categories(jade_src).items():
        if not ok:
            problems.append("Jade 基准缺类别：%s" % name)
    # ② 覆盖方式：复用 Jade 同一份载荷展开（结构上不可能漏类）
    if "RsccBusJadePayload.lines(" not in handler:
        problems.append("详情没有复用 RsccBusJadePayload.lines(...)（只有同一批行才能保证不漏类）")
    if "addJadeLines(" not in handler:
        problems.append("详情没有「来源总线」这一段")
    # ③ 本模组自己的补充类别（Jade 没有的也必须显示；Jade 只显示「开」的自动档要补「关」）
    required = {
        "总线类型": "KEY_BUS_BLOCK",
        "归属执行舱 / 六节类别（由 Jade 那一批覆盖）": "RsccBusJadePayload.lines(",
        "自动模式（含「关」这一档）": "KEY_AUTO_ON",
        "模糊模式": "KEY_FUZZY_ON",
        "强制普通总线": "KEY_FORCE_ON",
        "过滤器条数": "KEY_FILTER_COUNT",
        "过滤器条目": "KEY_FILTER_ITEMS",
        "类别勾选计数": "KEY_CATEGORIES_COUNT",
    }
    for name, token in required.items():
        if token not in handler:
            problems.append("详情缺信息类别：%s（%s）" % (name, token))
    body = method_body(handler, "addBusLines")
    if not body:
        problems.append("找不到 addBusLines（详情构造入口）")
    else:
        # 「有这段实现」不等于「详情里真的调了它」：行构造方法必须真的被调用（反例 ⑦b 就卡在这）。
        if "addJadeLines(" not in body:
            problems.append("详情没有调用 addJadeLines（Jade 那一批行没有进 tooltip）")
        if "addFilterLine(" not in body:
            problems.append("详情没有调用 addFilterLine（过滤条数 / 模糊模式没进 tooltip）")
        if "addFilterItemsLine(" not in body:
            problems.append("详情缺信息类别：过滤器条目（addFilterItemsLine 没被调用）")
        bus = body.find("KEY_BUS_BLOCK")
        jade_call = body.find("addJadeLines(")
        filter_call = body.find("addFilterLine(")
        if not (0 <= bus < jade_call < filter_call):
            problems.append("详情行序不是「方块类型 → 来源总线 → 过滤器」")
    # ⑤ 详情只在 Shift 构造（这条已有独立判据，这里只保证对标没有绕开它）
    if "linesFor(" in method_body(handler, "onItemTooltip"):
        problems.append("事件方法直接调 linesFor（详情不再是 Shift 才构造）")
    return problems


def jade_key_mirror_problems(handler, jade_src):
    """剪贴板里镜像的 Jade 载荷键，必须与 RsccBusJadePayload 的私有常量逐字一致。"""
    problems = []
    pairs = (("JADE_LINKED", "LINKED"), ("JADE_CHAMBER", "CHAMBER"), ("JADE_AUTO", "AUTO"),
             ("JADE_GROUPS", "GROUPS"), ("JADE_IDS", "IDS"))
    for mirror, original in pairs:
        m = re.search(r'\b%s\s*=\s*"([^"]+)"' % mirror, handler)
        o = re.search(r'\b%s\s*=\s*"([^"]+)"' % original, jade_src)
        if not m or not o:
            problems.append("找不到 %s / %s 的定义" % (mirror, original))
        elif m.group(1) != o.group(1):
            problems.append("%s=%r 与 Jade 的 %s=%r 不一致"
                            % (mirror, m.group(1), original, o.group(1)))
    return problems


def keeps_foreign_lines(handler):
    """Create / 别的模组已有的 tooltip 行必须原样保留：只许 add / addAll，绝不清空或覆盖。"""
    code = strip_comments(handler)
    if re.search(r"getToolTip\(\)\s*\.\s*(clear|remove|set)\s*\(", code):
        return False
    if re.search(r"\btooltip\s*\.\s*(clear|remove|set)\s*\(", code):
        return False
    # Create 的段仍然要显示（我们只是追加，不是替代）
    return "addCreateLines(" in code and "KEY_CREATE_HEADER" in code


# ----------------------------------------------------------------------
# 真实源码
# ----------------------------------------------------------------------

def main():
    for path in (HANDLER, SETTING, BUS_CLIPBOARD, JADE):
        check("文件存在：%s" % os.path.relpath(path, ROOT), os.path.isfile(path))
    if FAILURES:
        return finish()

    handler = read(HANDLER)
    setting = read(SETTING)
    bus = read(BUS_CLIPBOARD)
    jade = read(JADE)

    print("\n== ① 开关：关闭时一行配置都不出现 / 打开时才追加 ==")
    check("①a onItemTooltip 先问开关、先 return，之后才追加行", has_switch_guard(handler))
    check("①b 指令树有 on / off 两个分支", '"on"' in setting and '"off"' in setting
          and "clipboard-tooltip" in setting)
    check("①c 持久化文件 + 读取/写入都在", 
          "rs_create_compat-client.properties" in setting
          and "properties.store" in setting)

    print("\n== ①′ 默认值 = 开（第 82 轮修正：用户实机「看不到区别」的根因） ==")
    check("①′a 字段初值 true、读盘默认 \"true\"、且不再有默认 \"false\" 的读法",
          default_is_on(setting))
    check("①′b 关掉时仍有一行「怎么打开」的提示，且只在确有本模组配置时加",
          has_disabled_hint(handler))
    check("①′c 提示键在源码里被静态引用（中英都靠它）",
          KEY_PREFIX_GUI + "hint.off" in handler)

    print("\n== ⓪ 概括常显 + Shift 详情（第 86 轮） ==")
    check("⓪a 概括无条件常显（summaryFor → addAll 在 Shift 门控之前）",
          has_summary_always(handler))
    check("⓪b 详情只在 RsccTooltipLayers.held(SHIFT) 之后构造；事件方法不直接调 linesFor",
          has_shift_gate(handler) and details_not_in_event(handler))
    check("⓪c 详情走 RsccTooltipLayers.append + allShift（工程既有分层唯一实现）",
          details_use_layers(handler))
    check("⓪d 概括行有独立身份缓存（不每帧重拼）", has_summary_cache(handler))
    check("⓪e 开关关闭时只有一行提示（不构造概括 / 详情）", disabled_single_line(handler))

    print("\n== ② 性能：不每帧解析 NBT ==")
    check("②a 身份缓存（== cachedContent 命中即 return cachedLines）", has_lazy_cache(handler))
    check("②b 事件方法里没有任何 NBT 读取入口",
          not any(token in method_body(handler, "onItemTooltip") for token in NBT_TOKENS))

    print("\n== ③ 安全降级：非法 / 空 / 别的格式都不崩 ==")
    check("③a 组件 null / copiedValues 空 / 类型判断 / try-catch 四道闸", has_safe_degrade(handler))
    check("③b 解析异常只降级成空列表（catch 里 List.of()）",
          "catch (final RuntimeException" in handler and "built = List.of();" in handler)

    print("\n== ④ 本地化：新增键中英成对、源码引用、占位符一致 ==")
    zh_all = json.loads(read(os.path.join(LANG_DIR, "zh_cn.json")))
    en_all = json.loads(read(os.path.join(LANG_DIR, "en_us.json")))
    prefixes = (KEY_PREFIX_GUI, KEY_PREFIX_MSG)
    zh = {k: v for k, v in zh_all.items() if k.startswith(prefixes)}
    en = {k: v for k, v in en_all.items() if k.startswith(prefixes)}
    refs = set(KEY_RE.findall(handler)) | set(KEY_RE.findall(setting))
    problems = lang_pairs(zh, en, refs)
    check("④a 中英键集合一致、全部被源码引用、占位符一致（%d 个键）" % len(zh), not problems,
          "; ".join(problems))

    print("\n== ⑤ 数据源：物品组件，不是系统剪贴板 ==")
    check("⑤a 读 AllDataComponents.CLIPBOARD_CONTENT，且无 keyboardHandler/getClipboard()",
          uses_item_component_not_system_clipboard(handler))

    print("\n== ⑥ 专服安全：客户端类声明 Dist.CLIENT，common 侧无客户端引用 ==")
    check("⑥a 两个客户端类都声明 @EventBusSubscriber(value = Dist.CLIENT)",
          has_client_only_events(handler, setting))
    check("⑥b support/RsccBusClipboard.java 没有 net.minecraft.client.* 引用",
          "net.minecraft.client" not in strip_comments(bus))
    check("⑥c 剪贴板键只有一份定义，客户端经 RsccBusClipboard.clipboardKey() 读取",
          "public static String clipboardKey()" in bus and "RsccBusClipboard.clipboardKey()" in handler)

    print("\n== ⑦ 详情「至少对标 Jade」：逐类覆盖 + 键名镜像 + 不挤掉别人的行 ==")
    categories = jade_categories(jade)
    check("⑦a Jade 基准清单完整（%d 类：归属 / 自动 / 方向 / 六节 / 空态）" % len(categories),
          all(categories.values()),
          "缺：%s" % [k for k, ok in categories.items() if not ok])
    cov = clipboard_covers_jade(handler, jade)
    check("⑦b 剪贴板 Shift 详情覆盖 Jade 全部类别 + 用户的补充类别", not cov, "; ".join(cov))
    for name in categories:
        check("⑦b%d 覆盖类别「%s」" % (list(categories).index(name) + 1, name),
              not any(name in item for item in cov))
    mirror = jade_key_mirror_problems(handler, jade)
    check("⑦c 镜像的 Jade 载荷键与 RsccBusJadePayload 逐字一致", not mirror, "; ".join(mirror))
    check("⑦d Create / 别的模组的行原样保留（只 add / addAll，绝不清空 / 覆盖）",
          keeps_foreign_lines(handler))

    # ------------------------------------------------------------------
    # 反例自证：同一批判据必须把三种错写法判负
    # ------------------------------------------------------------------
    print("\n== 反例自证（同一批判据）==")

    bad_no_switch = """
    class BadNoSwitch {
        public static void onItemTooltip(Object event) {
            java.util.List<Object> lines = linesFor(null);
            event.getToolTip().addAll(lines);
        }
        private static java.util.List<Object> linesFor(Object stack) { return java.util.List.of(); }
    }
    """
    bad_default_off = """
    class BadDefaultOff {
        private static volatile boolean enabled;
        private static void ensureLoaded() {
            enabled = Boolean.parseBoolean(properties.getProperty(PROPERTY, "false"));
        }
    }
    """
    bad_silent_off = """
    class BadSilentOff {
        public static void onItemTooltip(Object event) {
            if (!ClipboardTooltipSetting.isEnabled()) {
                return;
            }
            event.getToolTip().addAll(linesFor(event.getItemStack()));
        }
    }
    """
    bad_every_frame = """
    class BadEveryFrame {
        public static void onItemTooltip(Object event) {
            Object content = event.getItemStack().get(AllDataComponents.CLIPBOARD_CONTENT);
            Object copied = content.copiedValues().orElse(null);
            event.getToolTip().add(copied);
        }
    }
    """
    bad_no_degrade = """
    class BadNoDegrade {
        private static java.util.List<Object> linesFor(Object stack) {
            Object content = stack.get(AllDataComponents.CLIPBOARD_CONTENT);
            return build(content);
        }
        private static java.util.List<Object> build(Object content) {
            Object copied = content.copiedValues().orElse(null);
            Object bus = copied.getCompound("rs_create_compat:bus_config");
            return java.util.List.of(bus.getString("kind"));
        }
    }
    """
    # 第 86 轮反例：只有 Shift 详情、没有常显概括
    bad_shift_only = """
    class BadShiftOnly {
        public static void onItemTooltip(Object event) {
            if (RsccTooltipLayers.held(RsccTooltipLayers.Layer.SHIFT)) {
                showDetails(event.getToolTip(), event.getItemStack(), null);
            }
        }
        private static void showDetails(Object tooltip, Object stack, Object regs) {
            RsccTooltipLayers.append(tooltip, RsccTooltipLayers.allShift(linesFor(stack, regs)));
        }
        private static Object linesFor(Object stack, Object regs) { return null; }
    }
    """
    # 第 86 轮反例：详情不按 Shift 就全拼（事件方法里直接 linesFor，没有 held 门控）
    bad_no_gate = """
    class BadNoGate {
        public static void onItemTooltip(Object event) {
            event.getToolTip().addAll(summaryFor(event.getItemStack()));
            event.getToolTip().addAll(linesFor(event.getItemStack(), null));
        }
        private static Object summaryFor(Object stack) { return null; }
        private static Object linesFor(Object stack, Object regs) { return null; }
    }
    """
    # 第 86 轮反例：按了 Shift、但详情不走 RsccTooltipLayers（自己 addAll 拼全量）
    bad_plain_shift = """
    class BadPlainShift {
        public static void onItemTooltip(Object event) {
            event.getToolTip().addAll(summaryFor(event.getItemStack()));
            if (RsccTooltipLayers.held(RsccTooltipLayers.Layer.SHIFT)) {
                showDetails(event.getToolTip(), event.getItemStack(), null);
            }
        }
        private static void showDetails(Object tooltip, Object stack, Object regs) {
            tooltip.addAll(linesFor(stack, regs));
        }
        private static Object summaryFor(Object stack) { return null; }
        private static Object linesFor(Object stack, Object regs) { return null; }
    }
    """
    check("反例 ①「没有开关」⇒ has_switch_guard 判负", not has_switch_guard(bad_no_switch))
    check("反例 ①′「默认关」⇒ default_is_on 判负", not default_is_on(bad_default_off))
    check("反例 ①″「关掉时静默 return、连提示都不加」⇒ has_disabled_hint 判负",
          not has_disabled_hint(bad_silent_off))
    check("反例 ②「每帧直接解析 NBT」⇒ has_lazy_cache 判负", not has_lazy_cache(bad_every_frame))
    check("反例 ③「不做空 / 类型 / 异常处理」⇒ has_safe_degrade 判负",
          not has_safe_degrade(bad_no_degrade))
    check("反例 ⓪a「只有 Shift 详情、没有常显概括」⇒ has_summary_always 判负",
          not has_summary_always(bad_shift_only))
    check("反例 ⓪b「详情不按 Shift 就全拼」⇒ has_shift_gate / details_not_in_event 判负",
          not has_shift_gate(bad_no_gate) and not details_not_in_event(bad_no_gate))
    check("反例 ⓪c「Shift 详情不走 RsccTooltipLayers」⇒ details_use_layers 判负",
          not details_use_layers(bad_plain_shift))
    # 本地化反例：少一个 en 键 ⇒ 同一判据判负
    fake_zh = {KEY_PREFIX_GUI + "x": "中文", KEY_PREFIX_GUI + "y": "%s"}
    fake_en = {KEY_PREFIX_GUI + "x": "English"}
    fake_refs = {KEY_PREFIX_GUI + "x", KEY_PREFIX_GUI + "y"}
    check("反例 ④「中英键不成对」⇒ lang_pairs 判负", bool(lang_pairs(fake_zh, fake_en, fake_refs)))

    # 第 87 轮反例：详情比 Jade 少一类 ⇒ 同一批判据必须判负
    bad_no_jade = handler.replace("addJadeLines(lines, payload);", "")
    check("反例 ⑦a「详情不展开 Jade 那一批行（归属 / 方向 / 六节全没了）」⇒ clipboard_covers_jade 判负",
          bad_no_jade != handler and bool(clipboard_covers_jade(bad_no_jade, jade)))
    bad_no_filter_items = handler.replace("addFilterItemsLine(lines, payload);", "")
    check("反例 ⑦b「详情不列过滤条目」⇒ clipboard_covers_jade 判负",
          bad_no_filter_items != handler and bool(clipboard_covers_jade(bad_no_filter_items, jade)))
    # 从 GROUP_ORDER 的 List.of(...) 里删掉「废料」这一节 = Jade 少一类
    bad_jade_missing = jade.replace(
        "GROUP_PRODUCTS, GROUP_SCRAP,\n        GROUP_INTERMEDIATES);",
        "GROUP_PRODUCTS,\n        GROUP_INTERMEDIATES);")
    check("反例 ⑦c「Jade 基准少一类（废料）」⇒ clipboard_covers_jade 判负（详情不可能比它多）",
          bad_jade_missing != jade and bool(clipboard_covers_jade(handler, bad_jade_missing)),
          "替换是否命中：%s" % (bad_jade_missing != jade))
    bad_mirror = handler.replace('JADE_CHAMBER = "chamber";', 'JADE_CHAMBER = "chamber_x";')
    check("反例 ⑦d「镜像键与 Jade 漂移」⇒ jade_key_mirror_problems 判负",
          bad_mirror != handler and bool(jade_key_mirror_problems(bad_mirror, jade)))
    bad_clears = handler.replace("tooltip.addAll(summary);", "tooltip.clear(); tooltip.addAll(summary);")
    check("反例 ⑦e「清空已有 tooltip 行（挤掉 Create 的描述）」⇒ keeps_foreign_lines 判负",
          bad_clears != handler and not keeps_foreign_lines(bad_clears))

    # 正向对照：真实源码必须全过（避免判据写成「谁都判负」）
    check("正向对照：真实 handler / setting 同时满足全部判据",
          has_switch_guard(handler) and has_lazy_cache(handler) and has_safe_degrade(handler)
          and default_is_on(setting) and has_disabled_hint(handler)
          and has_summary_always(handler) and has_shift_gate(handler)
          and details_not_in_event(handler) and details_use_layers(handler)
          and has_summary_cache(handler) and disabled_single_line(handler)
          and not clipboard_covers_jade(handler, jade) and keeps_foreign_lines(handler))

    return finish()


def finish():
    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] 剪贴板 tooltip 自检通过：%d 项断言全部符合预期"
          "（概括常显 / Shift 分层详情 / 开关两分支 / 默认开 / 关闭时仍有提示 / 身份缓存 / 安全降级 /"
          " 中英成对 / 物品组件 / 专服隔离 / 反例判负）"
          % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
