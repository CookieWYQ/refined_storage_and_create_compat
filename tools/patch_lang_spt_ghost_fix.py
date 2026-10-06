# -*- coding: utf-8 -*-
"""序列装配样板终端 / 单元样板 语言键增删（行级编辑，保持行序与缩进）。

按用户规则：语言文件必须用脚本改，不手写整份 JSON。
这里刻意<b>不用</b> json.dump 重写整份文件（那会把新增键排到末尾、并可能改动缩进），
而是按行做「删掉指定键所在行 / 在锚点键后插入一行」，写回后再用 json.load 校验。

本次改动（SPT 幽灵容器 & 只读输入槽 & 单元样板流体）：
  1) 删 `gui.rs_create_compat.sequence_pattern_terminal.op.input.tip`
     —— 输入原料槽改为只读展示槽，这条「点击可设置」的提示不再成立（输入槽不再有 tooltip）。
  2) 删 `gui.rs_create_compat.sequence_pattern_terminal.pattern_slots.hint`
     —— 误导性文案「只放本模组的序列装配总样板，一格一张」。
  3) 增 `item.rs_create_compat.sequence_unit_pattern.input_fluid`
     —— 单元样板 tooltip 显示流体输入（流体名 + 数量 mB）。
"""
import io
import json
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

KEY_RE = re.compile(r'^\s*"([^"]+)"\s*:')

REMOVE_KEYS = [
    "gui.rs_create_compat.sequence_pattern_terminal.op.input.tip",
    "gui.rs_create_compat.sequence_pattern_terminal.pattern_slots.hint",
]

# (锚点键, 新键, 中文值, 英文值)：新行插在锚点键那一行的<b>下一行</b>，保持同一组键聚集在一起。
INSERT_AFTER = [
    (
        "item.rs_create_compat.sequence_unit_pattern.input",
        "item.rs_create_compat.sequence_unit_pattern.input_fluid",
        "输入流体：%s %s mB",
        "Input fluid: %s %s mB",
    ),
]


def key_of(line):
    m = KEY_RE.match(line)
    return m.group(1) if m else None


def patch(path, remove_keys, insert_after):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    newline = "\r\n" if "\r\n" in raw else "\n"
    lines = raw.splitlines(keepends=True)

    removed = []
    kept = []
    for line in lines:
        k = key_of(line)
        if k is not None and k in remove_keys:
            removed.append(k)
            continue
        kept.append(line)

    inserted = []
    for anchor, new_key, zh_value, en_value in insert_after:
        value = zh_value if path.endswith("zh_cn.json") else en_value
        out = []
        done = False
        for line in kept:
            out.append(line)
            if not done and key_of(line) == anchor:
                indent = re.match(r'^(\s*)', line).group(1)
                out.append('%s"%s": "%s",%s' % (indent, new_key, value, newline))
                inserted.append(new_key)
                done = True
        if not done:
            raise SystemExit("anchor key not found in %s: %s" % (path, anchor))
        kept = out

    text = "".join(kept)
    json.loads(text)  # 写回前先校验（语法 / 尾逗号问题立刻暴露）
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    json.loads(io.open(path, "r", encoding="utf-8").read())  # 写回后再次校验
    print("patched %s: -%d keys %s, +%d keys %s"
          % (path, len(removed), removed, len(inserted), inserted))


for name in ("zh_cn.json", "en_us.json"):
    patch(ROOT + "\\" + name, set(REMOVE_KEYS), INSERT_AFTER)
