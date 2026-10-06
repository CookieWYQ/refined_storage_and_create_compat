# -*- coding: utf-8 -*-
"""把「总线强制普通」按钮（用户原话：「赶紧做我要的按钮」）的语言键写进 zh_cn / en_us（幂等）。

为什么要脚本而不是手改：
  * 语言文件是「一行一键」的格式，手插一行很容易漏掉/多加逗号（JSON 立刻坏掉）；
  * 中英必须成对（键集合必须完全一致）；
  * 本脚本每次都先用 json.load 复核语法，并打印中文长度（目标 <= 40 字）。

本轮新增（含出处）：
  1) gui.rs_create_compat.bus_force_normal.button        → client/widget/ExporterExecutorRowWidget（条右侧按钮的短标签）
  2) gui.rs_create_compat.bus_force_normal.button.tip    → 同上（点它会做什么）
  3) gui.rs_create_compat.bus_force_normal.button.hint   → 同上（怎么回来）
  4) gui.rs_create_compat.bus_force_normal.restore       → 同上（强制普通后，条下方那条带上的恢复按钮）
  5) gui.rs_create_compat.bus_force_normal.restore.tip   → 同上
  6) gui.rs_create_compat.bus_force_normal.restore.hint  → 同上

用法: python tools/add_bus_force_normal_lang.py
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

# (键, 中文, 英文, 插入锚点：插在同一前缀的最后一条之后；None = 追加到文件末尾)
NEW = [
    ("gui.rs_create_compat.bus_force_normal.button",
     "普通",
     "Plain",
     "gui.rs_create_compat."),
    ("gui.rs_create_compat.bus_force_normal.button.tip",
     "把这台总线设为普通总线",
     "Set this bus to a plain bus",
     "gui.rs_create_compat."),
    ("gui.rs_create_compat.bus_force_normal.button.hint",
     "关闭「自动当作序列装配总线」；之后打开就是普通总线界面",
     "Turns off auto detection as a sequenced assembly bus",
     "gui.rs_create_compat."),
    ("gui.rs_create_compat.bus_force_normal.restore",
     "恢复为总线界面",
     "Restore bus view",
     "gui.rs_create_compat."),
    ("gui.rs_create_compat.bus_force_normal.restore.tip",
     "恢复为序列装配总线界面",
     "Switch back to the sequenced assembly bus view",
     "gui.rs_create_compat."),
    ("gui.rs_create_compat.bus_force_normal.restore.hint",
     "重新按线缆连通自动判定（可再次用条上的「普通」按钮关掉）",
     "Re-enable cable based auto detection",
     "gui.rs_create_compat."),
]

MAX_ZH = 40


def split_key(line):
    """键值行 → (键, 结束符)；不是键值行返回 (None, 结束符)。"""
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    stripped = body.strip()
    if not stripped.startswith('"') or '":' not in stripped:
        return None, ending
    return stripped[1:stripped.index('":')], ending


def normalise_commas(lines):
    """规范化行尾逗号：除最后一个键行外都必须带逗号，最后一个键行必须不带。"""
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
    with io.open(path, encoding="utf-8", newline="") as f:
        lines = f.readlines()

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
            # 幂等：键已存在则只重写值（保持行序），不重复插入
            i = existing[key]
            _, ending = split_key(lines[i])
            lines[i] = '  "%s": %s,%s' % (key, text, ending or os.linesep)
            continue

        if anchor is None:
            insert_at = len(lines) - 1
        else:
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

    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.writelines(normalise_commas(lines))

    with io.open(path, encoding="utf-8") as f:
        loaded = json.load(f)
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
        problems.append("键集合不一致：仅中文 %s / 仅英文 %s"
                        % (sorted(set(zh) - set(en)), sorted(set(en) - set(zh))))
    print("\n%4s %4s  %s" % ("zh", "en", "key"))
    for key, zh_value, en_value, _anchor in NEW:
        lz, le = len(zh.get(key, "")), len(en.get(key, ""))
        flag = ""
        if zh.get(key, "") != zh_value or en.get(key, "") != en_value:
            flag += "  <== 值被改动过（请人工确认）"
        if lz > MAX_ZH:
            flag += "  <== 超出中文目标 %d" % MAX_ZH
            problems.append("中文过长：%s（%d 字）" % (key, lz))
        if len(zh.get(key, "").split("%s")) != len(en.get(key, "").split("%s")):
            problems.append("占位符数量不一致：%s" % key)
        print("%4d %4d  %s%s" % (lz, le, key, flag))

    if problems:
        print("\n问题：")
        for p in problems:
            print("  - %s" % p)
        return 1
    print("\n全部键已写入，中英成对、占位符一致、中文长度合规。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
