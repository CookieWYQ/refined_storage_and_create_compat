#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 79 轮：**输出总线未绑定执行仓时，不显示任何「绑定后才有意义」的信息**。

用户原话（逐字）
----------------
「还有就是那个**输出总线那个地方，如果说他没有绑定序列执行舱，你就不要显示那些（就是）
绑定之后才该显示的东西**，**也不要显示什么「未连接」什么「执行仓」之类的东西**，否则**怪怪的**。」

改前的坏行为（本脚本要防它回来）
--------------------------------
`compat/jade/RsccBusJadePayload` 的两条路都会给**每一台**本模组总线写载荷，于是没接任何执行仓的
输出总线在 Jade 里也会显示两行绑定态文案：「未绑定序列执行仓」+「当前没有负责的类别」；
「强制普通总线」时还会再显示一句「已改为普通总线：不按执行仓工作」。

修复口径（唯一的两个分支）
--------------------------
  * **未绑定**（`rscc$getLinkedExecutor() == null`）⇒ 服务端**一个键都不写**（连根标签都不写），
    客户端 `hasData` 为假 ⇒ 一行都不加；客户端 `lines()` 另有 `if (!linked) return out;` 的防御分支。
    未绑定分支渲染的语言键集合 = **空集**（白名单 `UNBOUND_ALLOWED_KEYS = frozenset()`）。
  * **已绑定** ⇒ 照旧：归属执行舱名 → 自动模式（仅输入总线）→ 方向 → 六节类别
    （没有任何类别时才显示「当前没有负责的类别」）。

断言（任一 FAIL 即退出码 1）
    ① 写侧门控：`write` 的两条分支都在**写第一个键之前**判 `chamber == null` 并 `return;`；
    ② 读侧未绑定分支：`lines` 里 `if (!linked) { ... }` 的分支存在，且**不含**任何
       `Component.translatable` / `LANG + "…"` / `GROUP_LANG` / `DISABLED_LANG` / `out.add`；
    ③ 白名单：未绑定分支实际渲染的键集合 **== 空集白名单**（可复跑）；
    ④ 已绑定分支仍包含 `linked / auto / dir.importer / dir.exporter / none` 与
       `groupLine`（`line` 键在 `groupLine` 里），且这些键中英都在；
    ⑤ **反例自证**：把改前写法（`else` 里追加 `unlinked` / `plain`）（a）直接喂给同一批判据、
       （b）注入到**真实源码**的未绑定分支里 —— 两种都必须判负（键集合非空）；
    ⑥ 占位键清除：`unlinked` / `plain` 不再出现在源码与语言文件里；
    ⑦ 同类设备核查：输出 / 输入总线**界面**的「归属执行舱」行都以 `!linkedChamber().isEmpty()` 为守卫
       （未绑定时一行都不加），两者共用的控件里没有 `unlinked` 一类占位文案；
    ⑧ 客户端 provider 先 `hasData` 再谈「按住 Shift」提示（没有内容可看时连提示行也不加）。

用法：python tools/selfcheck_round79_bus_unbound_ui.py
退出码：0 = 全部通过。
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
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
PKG_PATH = "cretae/cookiewyq/rs_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

PAYLOAD = PKG_PATH + "/compat/jade/RsccBusJadePayload.java"
CLIENT_PROVIDER = PKG_PATH + "/client/jade/RsccBusJadeProvider.java"
BUS_WIDGET = PKG_PATH + "/client/widget/ExporterExecutorRowWidget.java"
IMPORTER_SOURCE = PKG_PATH + "/client/widget/ImporterExecutorBarSource.java"

# 「未绑定时允许显示」的语言键白名单。**必须是空集**：用户要的是「不显示」，不是「换个说法」。
# 将来若真有必须常显的状态行，也只能加在这里，并同步改第 ③ 项断言 —— 不允许悄悄加回代码里。
UNBOUND_ALLOWED_KEYS = frozenset()

# 已绑定分支必须仍然出现的键（语义：绑定之后才该显示的东西，一个都不许丢）。
BOUND_KEYS = ("linked", "auto", "dir.importer", "dir.exporter", "none")

JADE_PREFIX = "gui.rs_create_compat.jade_bus."

FAILURES = []
CHECKS = [0]


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "INFO"), name,
                       (" | " + detail) if (detail and not ok) else ""))
    return ok


def strip_comments(text):
    """去掉注释、保留字符串字面量（键名断言必须看到字面量）。"""
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


def strip_comments_and_strings(text):
    """去掉注释与字符串字面量：用于「代码结构」断言（避免文档里的示例误报）。"""
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
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def read(rel):
    with io.open(os.path.join(JAVA_ROOT, rel), encoding="utf-8") as handle:
        return handle.read()


def method_text(code, signature_re):
    """按大括号配对截出方法体（code 必须是 strip_comments 之后的文本）。"""
    match = re.search(signature_re, code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():i + 1]
    return None


def block_bounds(text, brace):
    """返回 text[brace] == '{' 所对应的块内容区间 (start, end)（左开右开）。"""
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return brace + 1, i
    return None


def unbound_region(code):
    """取出「未绑定分支」的源码文本。

    同时认识两种写法（因此改前代码也能被同一批判据判负）：
      * 修复后：`if (!linked) { ... }`（早返回）；
      * 修复前：`if (linked) { ... } else { ... }`（取 else 块）。
    返回 None = 找不到任何未绑定分支（判负）。
    """
    match = re.search(r"if\s*\(\s*!\s*linked\s*\)\s*\{", code)
    if match:
        brace = code.rfind("{", 0, match.end())
        bounds = block_bounds(code, brace)
        return None if bounds is None else code[bounds[0]:bounds[1]]
    match = re.search(r"if\s*\(\s*linked\s*\)\s*\{", code)
    if not match:
        return None
    brace = code.rfind("{", 0, match.end())
    bounds = block_bounds(code, brace)
    if bounds is None:
        return None
    tail_start = bounds[1] + 1
    tail = code[tail_start:]
    else_match = re.search(r"\belse\s*\{", tail)
    if not else_match:
        return None
    brace2 = tail_start + tail.find("{", else_match.start())
    bounds2 = block_bounds(code, brace2)
    return None if bounds2 is None else code[bounds2[0]:bounds2[1]]


def lang_key_suffixes(fragment):
    """未绑定 / 已绑定分支里用 `LANG + "xxx"` 渲染的键后缀（唯一文案出口）。"""
    return set(re.findall(r'\bLANG\s*\+\s*"([^"]+)"', fragment))


def translatable_calls(fragment):
    """分支里所有「会渲染文字」的调用（translatable / GROUP_LANG / out.add），用于零键断言。"""
    return {
        "Component.translatable": len(re.findall(r"Component\s*\.\s*translatable", fragment)),
        "GROUP_LANG": len(re.findall(r"\bGROUP_LANG\b", fragment)),
        "DISABLED_LANG": len(re.findall(r"\bDISABLED_LANG\b", fragment)),
        "out.add": len(re.findall(r"\bout\s*\.\s*add\s*\(", fragment)),
    }


def load_lang(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


# 改前的写法（逐字摘自修复前的 `lines()` 未绑定分支）：反例自证用。
OLD_SNIPPET = '''
public static List<Component> lines(final CompoundTag serverData) {
    final List<Component> out = new ArrayList<>();
    final CompoundTag root = serverData.getCompound(ROOT);
    final boolean linked = root.getBoolean(LINKED);
    if (linked) {
        out.add(Component.translatable(LANG + "linked", root.getString(CHAMBER)));
    } else {
        out.add(Component.translatable(root.getBoolean(PLAIN) ? LANG + "plain" : LANG + "unlinked"));
    }
    return out;
}
'''


def main():
    sources = {}
    for dirpath, _dirs, files in os.walk(JAVA_ROOT):
        for name in files:
            if name.endswith(".java"):
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, JAVA_ROOT).replace(os.sep, "/")
                with io.open(full, encoding="utf-8") as handle:
                    sources[rel] = handle.read()

    missing = [rel for rel in (PAYLOAD, CLIENT_PROVIDER, BUS_WIDGET, IMPORTER_SOURCE) if rel not in sources]
    check("① 四个被检查的文件都在（载荷 / 客户端 provider / 总线控件 / 输入总线数据源）",
          not missing, "; ".join(missing))
    if missing:
        return report()

    payload_raw = sources[PAYLOAD]
    payload = strip_comments(payload_raw)          # 保留字符串：键名断言用
    payload_code = strip_comments_and_strings(payload_raw)  # 去字符串：结构断言用
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")

    # ---------------- ① 写侧：未绑定 ⇒ 一个键都不写 ----------------
    write_body = method_text(payload, r"public\s+static\s+void\s+write\s*\(")
    check("①a write() 方法体存在", write_body is not None)
    if write_body is not None:
        importer_at = write_body.find("instanceof RsccImporterExecutorMode")
        exporter_at = write_body.find("instanceof RsccExporterExecutorMode")
        check("①b write() 两条分支都在（输入总线 + 输出总线）",
              importer_at >= 0 and exporter_at > importer_at)
        else_at = write_body.find("} else {", exporter_at) if exporter_at >= 0 else -1
        branches = {
            "输入总线": write_body[importer_at:exporter_at] if importer_at >= 0 and exporter_at > importer_at else "",
            "输出总线": write_body[exporter_at:else_at if else_at > exporter_at else len(write_body)]
            if exporter_at >= 0 else "",
        }
        for label, branch in branches.items():
            guard = re.search(r"if\s*\(\s*chamber\s*==\s*null\s*\)\s*\{\s*return\s*;", branch)
            first_put = branch.find("root.put")
            guard_at = branch.find("if (chamber == null)")
            resolve_at = branch.find("rscc$getLinkedExecutor()")
            ok = (guard is not None and first_put > 0 and guard_at > 0
                  and resolve_at >= 0 and resolve_at < guard_at)
            check("①c %s分支：解析归属后、写第一个键之前判 chamber == null 并 return" % label,
                  ok, "guard=%s firstPut=%d resolveAt=%d" % (bool(guard), first_put, resolve_at))
        check("①d write() 里不再出现「未绑定也写载荷」的 LINKED=false / PLAIN / DISABLED 位",
              not re.search(r"putBoolean\s*\(\s*LINKED\s*,\s*false", payload)
              and not re.search(r"\bPLAIN\b|\bDISABLED\b", payload_code))

    # ---------------- ② / ③ 读侧未绑定分支 = 零键 ----------------
    lines_body = method_text(payload, r"public\s+static\s+List\s*<\s*Component\s*>\s+lines\s*\(")
    check("②a lines() 方法体存在", lines_body is not None)
    unbound = unbound_region(lines_body or "")
    check("②b 未绑定分支存在（if (!linked) 早返回，或改前 if (linked)…else…）", unbound is not None)
    if unbound is not None:
        suffixes = lang_key_suffixes(unbound)
        calls = translatable_calls(unbound)
        check("②c 未绑定分支里没有任何 jade_bus 键（%s）" % (sorted(suffixes) or "空"),
              not suffixes, "; ".join(sorted(suffixes)))
        check("②d 未绑定分支里没有任何渲染文字的调用（%s）"
              % ", ".join("%s=%d" % (k, v) for k, v in calls.items()),
              all(v == 0 for v in calls.values()))
        # ③ 白名单（可复跑）：实际渲染的键集合必须恰好等于常量（空集）
        check("③ 未绑定时显示的键集合 == 白名单 %s（防止以后又加回去）"
              % (sorted(UNBOUND_ALLOWED_KEYS) or "空集"),
              suffixes == set(UNBOUND_ALLOWED_KEYS),
              "实际=%s" % sorted(suffixes))

        # ---------------- ④ 已绑定分支：功能不许被改没 ----------------
        bound_keys = lang_key_suffixes(lines_body) - suffixes
        for suffix in BOUND_KEYS:
            check("④a 已绑定分支仍渲染键 %s" % (JADE_PREFIX + suffix),
                  suffix in bound_keys)
        check("④b 已绑定分支仍调用 groupLine()（六节类别）",
              "groupLine(" in (lines_body or ""))
        group_line = method_text(payload, r"private\s+static\s+Component\s+groupLine\s*\(")
        check("④c groupLine() 仍用 LANG + \"line\" 与 GROUP_LANG 拼分节行",
              group_line is not None
              and re.search(r'\bLANG\s*\+\s*"line"', group_line) is not None
              and "GROUP_LANG" in group_line)

    # ---------------- ⑤ 反例自证：改前写法必须判负 ----------------
    old_region = unbound_region(strip_comments(OLD_SNIPPET))
    old_keys = lang_key_suffixes(old_region or "")
    check("⑤a 改前写法（else 里追加）被同一判据判负（%s）" % (sorted(old_keys) or "空"),
          old_region is not None and {"unlinked", "plain"} <= old_keys)
    check("⑤b 改前写法的未绑定分支确实有渲染调用（translatable=%d）"
          % translatable_calls(old_region or "")["Component.translatable"],
          translatable_calls(old_region or "")["Component.translatable"] > 0)

    if lines_body is not None:
        match = re.search(r"if\s*\(\s*!\s*linked\s*\)\s*\{", lines_body)
        if match:
            brace = lines_body.rfind("{", 0, match.end())
            injected = (lines_body[:brace + 1]
                        + ' out.add(Component.translatable(root.getBoolean(PLAIN)'
                          ' ? LANG + "plain" : LANG + "unlinked")); '
                        + lines_body[brace + 1:])
            injected_keys = lang_key_suffixes(unbound_region(injected) or "")
            check("⑤c 把改前那一行注入**真实源码**的未绑定分支后，同一判据也判负（%s）"
                  % (sorted(injected_keys) or "空"),
                  injected_keys == {"unlinked", "plain"},
                  "实际=%s" % sorted(injected_keys))
            # 同一注入必须让第 ③ 项白名单判定失败（证明它不是空跑）
            check("⑤d 该注入会同时打破第 ③ 项白名单（suffixes != 空集）",
                  injected_keys != set(UNBOUND_ALLOWED_KEYS))
        else:
            check("⑤c 未绑定分支是 if (!linked) 早返回形状（便于注入验证）", False)

    # ---------------- ⑥ 占位键清除 ----------------
    offenders = []
    for rel, text in sources.items():
        if "jade_bus.unlinked" in text or "jade_bus.plain" in text:
            offenders.append(rel)
    check("⑥a 源码里不再引用 jade_bus.unlinked / jade_bus.plain", not offenders, "; ".join(offenders))
    for key in ("gui.rs_create_compat.jade_bus.unlinked", "gui.rs_create_compat.jade_bus.plain"):
        check("⑥b 语言文件里已移除占位键 %s" % key, key not in zh and key not in en)
    for suffix in BOUND_KEYS + ("line", "hold"):
        key = JADE_PREFIX + suffix
        check("⑥c 保留键 %s 中英都在" % key, key in zh and key in en)
    for key in ("gui.rs_create_compat.jade_bus.linked", "gui.rs_create_compat.jade_bus.none"):
        if key in zh:
            check("⑥d %s 的文案不含「未连接 / 未绑定」占位说法" % key,
                  "未连接" not in str(zh[key]) and "未绑定" not in str(zh[key]))

    # ---------------- ⑦ 同类设备：两条总线界面都只在绑定时加「归属执行舱」行 ----------------
    widget_raw = sources[BUS_WIDGET]
    widget = strip_comments(widget_raw)
    linked_calls = len(re.findall(r'lang\s*\(\s*\)\s*\+\s*"linked"', widget))
    guarded = len(re.findall(
        r'if\s*\(\s*!\s*linkedChamber\(\)\.isEmpty\(\)\s*\)\s*\{\s*'
        r'lines\.add\(\s*Component\.translatable\(\s*lang\s*\(\s*\)\s*\+\s*"linked"', widget))
    check("⑦a 输出/输入总线界面共用的「归属执行舱」行全部以 !linkedChamber().isEmpty() 为守卫"
          "（%d 处 / 守卫 %d 处）" % (linked_calls, guarded),
          linked_calls == 3 and guarded == linked_calls)
    widget_code = strip_comments_and_strings(widget_raw)
    check("⑦b 总线界面控件里没有 unlinked / 未连接 / 未绑定 一类占位文案",
          re.search(r"unlinked|未连接|未绑定", widget_code) is None)
    importer_source = strip_comments(sources[IMPORTER_SOURCE])
    check("⑦c 输入总线数据源只把 linkedChamber() 透传（没有自己的未绑定文案）",
          "linkedChamber()" in importer_source
          and re.search(r'unlinked|未连接|未绑定', strip_comments_and_strings(sources[IMPORTER_SOURCE])) is None)

    # ---------------- ⑧ 客户端 provider：先 hasData 再谈「按 Shift」 ----------------
    provider = strip_comments(sources[CLIENT_PROVIDER])
    has_data_at = provider.find("hasData(accessor.getServerData())")
    hold_at = provider.find('LANG + "hold"')
    check("⑧a provider 先 hasData 短路、再走「按住 Shift 查看」提示（未绑定 ⇒ 连提示行都不加）",
          has_data_at >= 0 and hold_at > has_data_at)

    return report()


def report():
    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] 第 79 轮「未绑定总线不显示绑定态信息」：%d 项断言全部通过" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
