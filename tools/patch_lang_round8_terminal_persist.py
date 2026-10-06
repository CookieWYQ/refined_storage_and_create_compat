# -*- coding: utf-8 -*-
"""语言键补丁（round8）：样板终端的「样板存在哪里」文案补上**手持终端**这一路径。

背景：序列装配样板终端除了「放下方块」打开，还能由高级远程终端**物品**打开。
物品路径的内容现在会写回物品 NBT（见 AdvancedRemoteTerminalItem#TAG_SEQUENCE_TERMINAL），
因此 tooltip 必须同时说清两种落点，否则玩家会以为手持终端里的样板关界面就没了。

做法：按「整行替换」改文本（保持行序 / 缩进 / 其余键一字不动），写完用 json.load 校验。
中文单条 ≤ 40 字。

用法：python tools/patch_lang_round8_terminal_persist.py
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

KEY = "gui.rs_create_compat.sequence_pattern_terminal.rs_pattern_slots.where"
CHANGES = {
    "zh_cn.json": (
        '样板存在终端里，不用背在身上；破坏方块会掉落',
        '样板存在终端里，不用背在身上；方块终端掉落、手持终端随物品保存',
    ),
    "en_us.json": (
        'Patterns live in the terminal, not on you; they drop when broken',
        'Patterns live in the terminal, not on you; the block drops them, the item keeps them',
    ),
}


def patch(name, old, new):
    path = os.path.join(LANG, name)
    with io.open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    hit = 0
    for i, line in enumerate(lines):
        if ('"%s"' % KEY) in line and old in line:
            indent = line[:len(line) - len(line.lstrip())]
            lines[i] = '%s"%s": "%s",\n' % (indent, KEY, new)
            hit += 1
    if hit != 1:
        raise SystemExit("[X] %s：期望命中 1 行，实际 %d 行（未改动）" % (name, hit))
    text = "".join(lines)
    data = json.loads(text)            # 结构校验：必须仍是合法 JSON
    assert data[KEY] == new, "[X] %s：写回值不一致" % name
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("[OK] %s：%s → %s" % (name, old, new))


def main():
    for name, (old, new) in CHANGES.items():
        patch(name, old, new)
    # 中文长度硬约束
    zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
    assert len(zh[KEY]) <= 40, "[X] 中文超长：%d 字" % len(zh[KEY])
    print("[OK] 全部完成，中文单条 %d 字（<= 40）" % len(zh[KEY]))


if __name__ == "__main__":
    main()
