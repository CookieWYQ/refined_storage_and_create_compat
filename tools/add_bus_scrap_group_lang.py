# -*- coding: utf-8 -*-
"""语言键补丁：总线「详细配置」子界面**新增的「废料」分组标题**（用户第 3 条）。

为什么要有这个脚本（而不是手改 lang）：
  * 语言文件是「一行一键」的格式，手插一行极易漏逗号 / 多逗号（JSON 立刻坏掉）；
  * 中英必须成对（键集合必须完全一致）；
  * 批量写入前先用 json.load 复核语法，写完再断言「中文单条 <= 40 字」。

本轮新增（出处）：
  gui.rs_create_compat.bus_config.group.scrap → client/screen/BusCategoryConfigScreen.java
  （GROUP_SCRAP；废料 = create 序列装配 results 池里除主产物以外的概率产出，例如安山合金）

用法：python tools/add_bus_scrap_group_lang.py
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
KEY = "gui.rs_create_compat.bus_config.group.scrap"
ANCHOR = '"gui.rs_create_compat.bus_config.group.products"'
VALUES = {"zh_cn.json": "废料", "en_us.json": "Scrap"}
MAX_ZH = 40


def apply(name, value):
    path = os.path.join(LANG, name)
    with io.open(path, encoding="utf-8") as handle:
        lines = handle.readlines()
    if any(('"%s"' % KEY) in line for line in lines):
        print("[SKIP] %s：%s 已存在" % (name, KEY))
        return 0, json.load(io.open(path, encoding="utf-8"))
    index = next((i for i, line in enumerate(lines) if ANCHOR in line), -1)
    if index < 0:
        raise SystemExit("[X] %s：找不到锚点 %s（未改动）" % (name, ANCHOR))
    indent = lines[index][:len(lines[index]) - len(lines[index].lstrip())]
    lines.insert(index + 1, '%s"%s": %s,\n' % (indent, KEY, json.dumps(value, ensure_ascii=False)))
    text = "".join(lines)
    data = json.loads(text)          # 结构校验：必须仍是合法 JSON
    assert data[KEY] == value, "[X] %s：写回值不一致" % name
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("[OK] %s：+ %s = %s" % (name, KEY, value))
    return 1, data


def main():
    added = 0
    for name, value in VALUES.items():
        n, _ = apply(name, value)
        added += n
    zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
    problems = []
    if set(zh) != set(en):
        problems.append("中英键集不一致")
    if KEY not in zh or KEY not in en:
        problems.append("缺键：%s" % KEY)
    elif len(zh[KEY]) > MAX_ZH:
        problems.append("中文超长（%d 字）：%s" % (len(zh[KEY]), KEY))
    print("[OK] 新增 %d 行；%s = zh「%s」/ en「%s」" % (added, KEY, zh.get(KEY), en.get(KEY)))
    if problems:
        for item in problems:
            print("  - %s" % item)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
