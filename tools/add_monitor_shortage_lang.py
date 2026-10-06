# -*- coding: utf-8 -*-
"""语言键补丁：自动合成监视器界面上的「缺料处置」开关（用户第 4 条）。

为什么要有这个脚本（而不是手改 lang）：
  * 语言文件是「一行一键」的格式，手插一行极易漏逗号 / 多逗号（JSON 立刻坏掉）；
  * 中英必须成对（键集合必须完全一致）；
  * 写完用 json.load 复核语法，并断言「中文单条 <= 40 字、中英占位符个数一致」。

本轮新增（出处：mixin/client/AutocraftingMonitorScreenMixin.java）：
  gui.rs_create_compat.assembly.monitor.shortage.suspend → 按钮标签（档位 = 挂起；后缀取自 RsccShortagePolicy.Mode#id）
  gui.rs_create_compat.assembly.monitor.shortage.wait    → 按钮标签（档位 = 等待；同上）
  gui.rs_create_compat.assembly.monitor.shortage.title   → tooltip 首行
  gui.rs_create_compat.assembly.monitor.shortage.tip     → tooltip 末行（%s = 点一下会切到哪一档）

用法：python tools/add_monitor_shortage_lang.py
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
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
PREFIX = "gui.rs_create_compat.assembly.monitor."
ANCHOR = '"gui.rs_create_compat.assembly.monitor.suspend.tip"'
NEW = [
    ("shortage.suspend", "缺料·挂起", "Suspend"),
    ("shortage.wait", "缺料·等待", "Wait"),
    ("shortage.title", "缺料处置", "Shortage policy"),
    ("shortage.tip", "点一下切换为：%s", "Click to switch to: %s"),
]
MAX_ZH = 40


def apply(name, column):
    path = os.path.join(LANG, name)
    with io.open(path, encoding="utf-8") as handle:
        lines = handle.readlines()
    index = next((i for i, line in enumerate(lines) if ANCHOR in line), -1)
    if index < 0:
        raise SystemExit("[X] %s：找不到锚点 %s" % (name, ANCHOR))
    indent = lines[index][:len(lines[index]) - len(lines[index].lstrip())]
    added = 0
    # 逆序插入到锚点之后，保证最终顺序与 NEW 一致
    for offset, (suffix, zh, en) in enumerate(NEW):
        key = PREFIX + suffix
        if any(('"%s"' % key) in line for line in lines):
            continue
        lines.insert(index + 1 + offset, '%s"%s": %s,\n'
                     % (indent, key, json.dumps(zh if column == 0 else en, ensure_ascii=False)))
        added += 1
    text = "".join(lines)
    data = json.loads(text)          # 结构校验：必须仍是合法 JSON（且无重复键覆盖）
    for suffix, zh, en in NEW:
        assert data[PREFIX + suffix] == (zh if column == 0 else en), "[X] %s：%s" % (name, suffix)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("[OK] %s：新增 %d 行" % (name, added))
    return data


def main():
    zh = apply("zh_cn.json", 0)
    en = apply("en_us.json", 1)
    problems = []
    if set(zh) != set(en):
        problems.append("中英键集不一致")
    for suffix, zh_value, en_value in NEW:
        key = PREFIX + suffix
        if key not in zh or key not in en:
            problems.append("缺键：%s" % key)
            continue
        if len(zh[key]) > MAX_ZH:
            problems.append("中文超长（%d 字）：%s" % (len(zh[key]), key))
        if len(zh[key].split("%s")) != len(en[key].split("%s")):
            problems.append("占位符数量不一致：%s" % key)
        print("  %-16s zh「%s」/ en「%s」" % (suffix, zh[key], en[key]))
    if problems:
        for item in problems:
            print("  - %s" % item)
        return 1
    print("[OK] 中英成对、中文 <= %d 字、占位符一致" % MAX_ZH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
