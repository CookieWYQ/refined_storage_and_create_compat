# -*- coding: utf-8 -*-
"""把「传动杆不支持套伪装」的提示语言键写进 zh_cn / en_us（幂等）。

为什么是脚本：语言文件一行一键，手插容易漏逗号把 JSON 弄坏；中英必须成对；
本脚本每次都先用 json.load 复核语法，并打印中文长度（目标 <= 40 字）。

出处：item/CamouflageFrameItem（用户第 ⑧ 条「传动杆套了伪装却不显示」——
取证结论：Create 的传动杆由自带的 Flywheel 视觉系统渲染，本模组「替换烘焙模型」的
外壳链路接不到它，因此在操作入口显式拒绝并说明，且不消耗物品）。

用法: python tools/add_camouflage_shaft_lang.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

NEW = [
    ("block.rs_create_compat.camouflage_frame.hint.shaft_unsupported",
     "传动杆不支持套伪装（由 Flywheel 渲染，外壳不显示）",
     "Shafts cannot be camouflaged (rendered by Flywheel)",
     "block.rs_create_compat.camouflage_frame.hint."),
]

MAX_ZH = 40


def split_key(line):
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    stripped = body.strip()
    if not stripped.startswith('"') or '":' not in stripped:
        return None, ending
    return stripped[1:stripped.index('":')], ending


def normalise_commas(lines):
    key_pos = [i for i, line in enumerate(lines) if split_key(line)[0] is not None]
    if not key_pos:
        return lines
    last = key_pos[-1]
    out = []
    for i, line in enumerate(lines):
        if i not in key_pos:
            out.append(line)
            continue
        body = line.rstrip("\r\n")
        ending = line[len(body):]
        body = body.rstrip().rstrip(",").rstrip()
        if i != last:
            body += ","
        out.append(body + (ending or os.linesep))
    return out


def apply(path, column):
    with io.open(path, encoding="utf-8", newline="") as handle:
        lines = handle.readlines()
    existing = {}
    for i, line in enumerate(lines):
        key, _ = split_key(line)
        if key is not None:
            existing[key] = i
    written = 0
    for key, zh, en, anchor in NEW:
        value = zh if column == 0 else en
        text = json.dumps(value, ensure_ascii=False)
        if key in existing:
            i = existing[key]
            _, ending = split_key(lines[i])
            lines[i] = '  "%s": %s,%s' % (key, text, ending or os.linesep)
            continue
        hits = [pos for k, pos in existing.items() if k.startswith(anchor)]
        if not hits:
            raise SystemExit("找不到插入锚点：%s" % anchor)
        insert_at = max(hits) + 1
        prev = lines[insert_at - 1].rstrip("\r\n")
        if not prev.rstrip().endswith(","):
            lines[insert_at - 1] = prev + ",\n"
        ending = lines[insert_at - 1][len(lines[insert_at - 1].rstrip("\r\n")):]
        lines.insert(insert_at, '  "%s": %s,%s' % (key, text, ending or os.linesep))
        existing = {}
        for i, line in enumerate(lines):
            k, _ = split_key(line)
            if k is not None:
                existing[k] = i
        written += 1
    with io.open(path, "w", encoding="utf-8", newline="") as handle:
        handle.writelines(normalise_commas(lines))
    with io.open(path, encoding="utf-8") as handle:
        loaded = json.load(handle)
    assert len(loaded) == len(lines) - 2, "键数量与行数不匹配：%s" % path
    return written, loaded


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")
    n_zh, zh = apply(zh_path, 0)
    n_en, en = apply(en_path, 1)
    print("zh_cn.json 新增 %d 键（共 %d）；en_us.json 新增 %d 键（共 %d）"
          % (n_zh, len(zh), n_en, len(en)))
    problems = []
    if sorted(zh) != sorted(en):
        problems.append("键集合不一致")
    for key, zh_value, en_value, _anchor in NEW:
        print("  zh=%d 字  %s" % (len(zh.get(key, "")), key))
        if len(zh.get(key, "")) > MAX_ZH:
            problems.append("中文过长：%s" % key)
        if zh.get(key, "") != zh_value or en.get(key, "") != en_value:
            problems.append("值被改动过，请人工确认：%s" % key)
    if problems:
        for p in problems:
            print("  - %s" % p)
        return 1
    print("中英成对、中文 <= %d 字。" % MAX_ZH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
