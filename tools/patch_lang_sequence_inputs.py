# -*- coding: utf-8 -*-
# 追加「装配样板所需输入（含中间原料）」相关语言键（避免手写整份 JSON）。
import io
import json
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    # 装配样板 tooltip：一次循环所需的全部输入（主原料 + 各步中间输入原料，同物品累加）
    "item.rs_create_compat.sequence_assembly_pattern.inputs_header": "所需输入（每次循环）：",
    "item.rs_create_compat.sequence_assembly_pattern.input_entry": "%s ×%s",
}

en_updates = {
    "item.rs_create_compat.sequence_assembly_pattern.inputs_header": "Inputs (per loop):",
    "item.rs_create_compat.sequence_assembly_pattern.input_entry": "%s x%s",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path)


patch(ROOT + r"\zh_cn.json", zh_updates)
patch(ROOT + r"\en_us.json", en_updates)
