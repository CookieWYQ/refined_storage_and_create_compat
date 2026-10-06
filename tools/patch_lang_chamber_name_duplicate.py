# -*- coding: utf-8 -*-
"""补齐「执行仓绑定配置：重名时『确定』按钮置灰」的 tooltip 语言键（中英双语）。

背景（本次需求）：
  * `ChamberBindingConfigScreen` 现在会在玩家输入名字时即时判断重名，命中就把「确定」按钮
    置灰；鼠标悬停在按钮上要给出原因。用户原话：「鼠标只能放上去提示『有机器同名了』」。
  * 因此新增 `gui.rs_create_compat.sequence_execution_chamber.config.confirm.duplicate`，
    中文单条 ≤ 40 字。

保持行序与缩进：读 → 追加（新键落在文件末尾，既有键顺序一字不变）→ `json.dump(indent=2,
ensure_ascii=False)` + 结尾换行，与仓库现有 lang 文件格式逐字节一致（已用 round-trip 校验）。

用法:
    python tools/patch_lang_chamber_name_duplicate.py
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

CHAMBER = "gui.rs_create_compat.sequence_execution_chamber."

zh_updates = {
    CHAMBER + "config.confirm.duplicate": "已有机器同名了",
}

en_updates = {
    CHAMBER + "config.confirm.duplicate": "Another machine already uses this name",
}


def patch(name, updates):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)  # 先 json.load 校验，坏文件直接在这里炸掉，不会写出半成品
    added = []
    changed = []
    for key, value in updates.items():
        if len(value) > 40 and name.startswith("zh"):
            raise ValueError("中文语言键过长（>40 字）：%s = %s" % (key, value))
        if key not in data:
            added.append(key)
        elif data[key] != value:
            changed.append(key)
        data[key] = value
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    # 写完再读回来校验一次，确保文件仍是合法 JSON
    with io.open(path, "r", encoding="utf-8") as f:
        json.load(f)
    print("[OK ] %s: 新增 %d 个键，覆盖 %d 个已有键（共 %d 键）"
          % (name, len(added), len(changed), len(data)))
    for key in added:
        print("       + %s" % key)
    for key in changed:
        print("       ~ %s" % key)


def main():
    patch("zh_cn.json", zh_updates)
    patch("en_us.json", en_updates)


if __name__ == "__main__":
    main()
