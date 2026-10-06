# -*- coding: utf-8 -*-
"""为装填器家族的「堆叠升级」效果行写入中英文案。

为什么要用脚本：语言文件是有格式要求的 JSON（2 空格缩进、键按既有顺序排列），手改容易漏一处或破坏缩进；
本脚本只「插入 / 覆盖对应那一行」，改完用 json.load 自校验（解析失败直接报错，不写坏文件）。

文案口径：显示的是<b>本机</b>实际生效的数值 —— 前一个 %s 是本机插件槽里的堆叠升级个数，
后一个 %s 是本机每格容量上限（见 SchematicLoaderBlockEntity#storageSlotCapacity）。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

KEY = "gui.rs_create_compat.schematic_loader.upgrade.stack.effect"
# 插在装填器家族既有 gui.* 键的后面，保持文件里键的分组顺序
ANCHOR = '"gui.rs_create_compat.schematic_loader.recycle"'

TEXTS = {
    "zh_cn.json": "本机堆叠升级 ×%s：资源存储每格上限提升至 %s",
    "en_us.json": "Stack upgrades x%s: storage slot capacity is now %s",
}

LIMITS = {"zh_cn.json": 30, "en_us.json": 60}


def patch(fname, text):
    path = os.path.join(LANG_DIR, fname)
    with open(path, "rb") as f:
        raw = f.read().decode("utf-8")
    assert raw.count("\r\n") == 0, "%s 不是 LF 换行，脚本按 LF 处理" % fname
    lines = raw.split("\n")
    entry = '  %s: %s' % (json.dumps(KEY, ensure_ascii=False), json.dumps(text, ensure_ascii=False))
    # 幂等：已存在同名键则覆盖，否则插到锚点行之后（保持行序与缩进）
    hit = [i for i, line in enumerate(lines) if line.lstrip().startswith('"%s"' % KEY)]
    if hit:
        lines[hit[0]] = entry + ("," if lines[hit[0]].rstrip().endswith(",") else "")
    else:
        anchor = [i for i, line in enumerate(lines) if ANCHOR in line]
        assert len(anchor) == 1, "%s 找不到唯一锚点 %s（找到 %d 处）" % (fname, ANCHOR, len(anchor))
        assert lines[anchor[0]].rstrip().endswith(","), "%s 锚点行不是键值对结尾" % fname
        lines.insert(anchor[0] + 1, entry + ",")
    with open(path, "wb") as f:
        f.write("\n".join(lines).encode("utf-8"))
    # 自校验：必须仍能解析，且键值 = 期望值、格式占位符个数不变
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    assert data[KEY] == text, "%s 写入后读回不一致" % fname
    assert data[KEY].count("%s") == 2, "%s 占位符个数应为 2" % fname
    assert len(text) <= LIMITS[fname], "%s 文案过长（%d > %d）" % (fname, len(text), LIMITS[fname])
    print("[OK] %s  %s = %r（%d 字，JSON 校验通过）" % (fname, KEY, text, len(text)))


def main():
    for fname, text in TEXTS.items():
        patch(fname, text)


if __name__ == "__main__":
    main()
