# -*- coding: utf-8 -*-
"""为「原料 / 输入时原料」两组补一行各自的定义（中英成对），并让英文组名一眼可辨。

为什么需要：
  用户原话「原料和输入时原料你把他们两个混在一起、没有区分」。分组已按配方上下文重做
  （见 BusCategoryConfigScreen#inputSectionOf），但两个组名本身仍然容易被看成同一件事，
  因此：
    * 每个组的表头 tooltip 各补一句定义（zh 组 ≤ 40 字）；
    * 英文组名 group.materials / group.feedstock 改成互不混淆的写法
      （原来 "Materials" 与 "Input-bus materials" 后者会被读成「输入总线」）。

写入方式：按行文本插入（不重新序列化整个 JSON），插入位置 = 各自锚点键的下一行，
  保证 zh_cn 与 en_us 的键集合与键顺序完全一致。

校验：读回 json.load；断言中英键集合 + 键顺序完全一致；断言新增中文 ≤ 40 字。
"""
import io
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
BUS = "gui.rs_create_compat.bus_config."

# (锚点键, 新键, 中文, 英文) —— 新键插在锚点键的下一行（两文件同一相对位置）
INSERTS = [
    (BUS + "group.feedstock", BUS + "group.feedstock.tip",
     "本配方各步加工途中投进去的原料",
     "Extra inputs fed in while the steps run"),
    (BUS + "group.materials", BUS + "group.materials.tip",
     "本配方的起步原料：开一件新的在制件要放的那一份",
     "Starting ingredient that opens a new piece"),
]

# 英文组名：让两组一眼可辨（不改中文 —— 「输入时原料」是用户自己的说法）
RENAMES_EN = {
    BUS + "group.materials": "Starting materials",
    BUS + "group.feedstock": "Step inputs",
}


def patch(path, inserts, renames):
    with io.open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    added = 0
    for anchor, key, text in inserts:
        prefix = '  "%s": ' % key
        line = '  "%s": %s,' % (key, json.dumps(text, ensure_ascii=False))
        existing = next((i for i, item in enumerate(lines) if item.startswith(prefix)), None)
        if existing is not None:
            lines[existing] = line  # 幂等：已存在就只把值改成当前文案
            print("  [更新] %s" % key)
            continue
        anchor_prefix = '  "%s": ' % anchor
        index = next((i for i, item in enumerate(lines) if item.startswith(anchor_prefix)), None)
        if index is None:
            raise SystemExit("[X] 找不到锚点键：%s（%s）" % (anchor, path))
        lines.insert(index + 1, line)
        added += 1
    for key, value in renames.items():
        prefix = '  "%s": ' % key
        index = next((i for i, line in enumerate(lines) if line.startswith(prefix)), None)
        if index is None:
            raise SystemExit("[X] 找不到改名目标：%s（%s）" % (key, path))
        lines[index] = '  "%s": %s,' % (key, json.dumps(value, ensure_ascii=False))
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines))
    return added


def main():
    zh_path = os.path.join(LANG, "zh_cn.json")
    en_path = os.path.join(LANG, "en_us.json")
    zh_added = patch(zh_path, [(a, k, t) for a, k, t, _ in INSERTS], {})
    en_added = patch(en_path, [(a, k, t) for a, k, _, t in INSERTS], RENAMES_EN)
    with io.open(zh_path, encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(en_path, encoding="utf-8") as handle:
        en = json.load(handle)
    assert list(zh.keys()) == list(en.keys()), "中英键顺序不一致"
    assert set(zh) == set(en), "中英键集合不一致"
    for key in [k for _, k, _, _ in INSERTS]:
        assert key in zh and key in en, key
        assert len(zh[key]) <= 40, "[X] 中文超长（%d 字）：%s" % (len(zh[key]), key)
        print("  [OK] %s -> %s | %s（%d 字）" % (key.split(".")[-1], zh[key], en[key], len(zh[key])))
    for key, value in RENAMES_EN.items():
        print("  [改名] %s -> %s" % (key.split(".")[-1], value))
    print("完成：zh +%d / en +%d；键集合一致（各 %d 键）、顺序一致" % (zh_added, en_added, len(zh)))


if __name__ == "__main__":
    main()
