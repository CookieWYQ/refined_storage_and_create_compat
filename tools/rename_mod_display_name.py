#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rename_mod_display_name.py - 把模组的人类可读显示名改成中文，并同步它的每一个落点。

为什么用脚本：
  ① gradle.properties 由 Java Properties 以 ISO-8859-1 读取，中文必须写成 \\uXXXX 转义，
     手写极易漏字符 / 写错码点；脚本从真实码点自动生成转义序列，杜绝手误；
  ② 语言文件（json）必须成对且写盘前 json.load 校验（用户规则：json 一律用脚本写）；
  ③ 显示名的真实来源只有一个（gradle.properties 的 mod_name），mods.toml 的 displayName
     与成就根标题都由它派生，脚本保证这几处永远是同一条字符串。

改哪里（幂等，可重复运行）：
  - gradle.properties：mod_name = <名字的 \\uXXXX 转义形式>（并保留一行中文注释解释为什么转义）
  - assets/<ns>/lang/{zh_cn,en_us}.json：advancements.<ns>.root.title = 同一个名字

不碰：mod_id / 包名 / 资源命名空间 / 注册 id / mod_description / 依赖版本等一切其它内容。

用法：python tools/rename_mod_display_name.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "rs_create_compat"
GRADLE_PROPS = os.path.join(ROOT_DIR, "gradle.properties")
LANG_DIR = os.path.join(ROOT_DIR, "src", "main", "resources", "assets", MODID, "lang")
ROOT_TITLE_KEY = "advancements.%s.root.title" % MODID

# 唯一的显示名来源（改名字只改这一行）
DISPLAY_NAME = "机械动力&精致存储：兼容与改善"
# mod_name 行上方的中文注释（逐字符精确匹配 → 重跑时先删旧注释再插，保证幂等）
NOTE = u"# 显示名含中文，Java Properties 按 ISO-8859-1 读取，故此处用 \\uXXXX 转义，避免读取乱码。"

problems = []


def problem(msg):
    problems.append(msg)
    print("[FAIL] %s" % msg)


def info(msg):
    print("[info] %s" % msg)


def read_text(path, encoding="utf-8"):
    with io.open(path, "r", encoding=encoding, newline="") as handle:
        return handle.read()


def write_text(path, text, encoding="utf-8"):
    with io.open(path, "w", encoding=encoding, newline="\n") as handle:
        handle.write(text)


def java_escape(text):
    """按 Java .properties 规则转义：所有非 ASCII 与 \\u 敏感字符一律写成 \\uXXXX。"""
    return u"".join(u"\\u%04x" % ord(ch) for ch in text)


def java_unescape(value):
    """Java .properties 解码（\\uXXXX + 常见转义），用于「读回来比对」，杜绝自欺。"""
    simple = {u"n": u"\n", u"t": u"\t", u"r": u"\r", u"f": u"\f"}
    out, i = [], 0
    while i < len(value):
        ch = value[i]
        if ch == u"\\" and i + 1 < len(value):
            nxt = value[i + 1]
            if nxt == u"u" and len(value) >= i + 6:
                out.append(unichr_ok(value[i + 2:i + 6]))
                i += 6
                continue
            out.append(simple.get(nxt, nxt))
            i += 2
            continue
        out.append(ch)
        i += 1
    text = u"".join(out)
    # \u 也可以拼出 UTF-16 代理对，用 utf-16 往返把代理对合并成真正的字符
    return text.encode("utf-16", "surrogatepass").decode("utf-16")


def unichr_ok(hex4):
    try:
        return chr(int(hex4, 16))
    except ValueError:
        raise ValueError("非法的 \\uXXXX 转义：%s" % hex4)


def update_gradle_properties():
    """把 mod_name 改成转义形式，并在其上方保留一行中文注释（幂等）。"""
    lines = read_text(GRADLE_PROPS).splitlines()
    target = java_escape(DISPLAY_NAME)
    index = next((i for i, line in enumerate(lines) if line.startswith("mod_name=")), None)
    if index is None:
        problem("gradle.properties 里找不到 mod_name")
        return None
    # 先回删上一次插入的注释（只认紧邻 mod_name 的那一行，避免误删 mod_description 上方的同类注释）
    while index > 0 and lines[index - 1] == NOTE:
        del lines[index - 1]
        index -= 1
    # mod_name 行连同它上方的中文注释一起替换（上面的回删已把旧注释清干净）
    lines[index:index + 1] = [NOTE, "mod_name=" + target]
    write_text(GRADLE_PROPS, u"\n".join(lines) + u"\n")
    return target


def update_lang():
    """中英语言文件的成就根标题 = 同一个显示名（json 写盘前自校验）。"""
    changed = []
    for name in ("zh_cn.json", "en_us.json"):
        path = os.path.join(LANG_DIR, name)
        data = json.loads(read_text(path))
        if data.get(ROOT_TITLE_KEY) != DISPLAY_NAME:
            data[ROOT_TITLE_KEY] = DISPLAY_NAME
            text = json.dumps(data, ensure_ascii=False, indent=2) + u"\n"
            json.loads(text)  # 自校验：写盘前必须能被解析
            write_text(path, text)
            changed.append(name)
        json.loads(read_text(path))  # 复核
        info("%s：%s = %s" % (name, ROOT_TITLE_KEY, json.loads(read_text(path))[ROOT_TITLE_KEY]))
    return changed


def main():
    escaped = update_gradle_properties()
    if escaped is None:
        return 1
    # 读回来按 Java Properties 规则解码 → 必须与 DISPLAY_NAME 完全一致
    raw = None
    for line in read_text(GRADLE_PROPS).splitlines():
        if line.startswith("mod_name="):
            raw = line.split("=", 1)[1]
    decoded = java_unescape(raw)
    if decoded != DISPLAY_NAME:
        problem("解码后与目标显示名不一致：%r != %r" % (decoded, DISPLAY_NAME))
    if raw == DISPLAY_NAME:
        problem("mod_name 仍是字面中文（应为 \\uXXXX 转义形式，否则 ISO-8859-1 读取会乱码）")
    info("gradle.properties: mod_name=%s" % raw)
    info("解码后 = %s" % decoded)
    info("改写语言文件：%s" % (", ".join(update_lang()) or "（已是目标值，无需改动）"))

    print("-" * 60)
    print("[evidence] 目标显示名      : %s" % DISPLAY_NAME)
    print("[evidence] 码点数          : %d（每字符一个 \\uXXXX 转义）" % len(DISPLAY_NAME))
    print("[evidence] UTF-8 字节(hex) : %s" % DISPLAY_NAME.encode("utf-8").hex(" "))
    print("[evidence] gradle 转义原文 : %s" % raw)
    if problems:
        print("[result] 失败：%d 个问题" % len(problems))
        return 1
    print("[result] 通过：mod_name 与成就根标题已统一为同一个名字（mod_id/包名/命名空间未动）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
