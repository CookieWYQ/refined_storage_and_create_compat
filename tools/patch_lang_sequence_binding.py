# -*- coding: utf-8 -*-
# 追加「序列装配新语义（v4）」相关语言键（只追加，不改写已有键）。
# 运行：python tools\patch_lang_sequence_binding.py
import json
import io
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    # 总样板导出：某步无可用机器（后端 getLastExportError() 使用）
    "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine":
        "第 %s 步无可用机器（配方类型：%s），该步已按「无指派」导出",
    "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine.more":
        "；另有 %s 步无可用机器",
    # 序列执行仓绑定（新语义：配方类型 + 名称）
    "gui.rs_create_compat.sequence_execution_chamber.bind.recipe_type": "配方类型",
    "gui.rs_create_compat.sequence_execution_chamber.bind.name": "名称",
    "gui.rs_create_compat.sequence_execution_chamber.unbound": "未绑定",
}

en_updates = {
    "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine":
        "Step %s has no available machine (recipe type: %s); exported as unassigned",
    "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine.more":
        "; plus %s more step(s) without a machine",
    "gui.rs_create_compat.sequence_execution_chamber.bind.recipe_type": "Recipe type",
    "gui.rs_create_compat.sequence_execution_chamber.bind.name": "Name",
    "gui.rs_create_compat.sequence_execution_chamber.unbound": "Unbound",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    added = 0
    for key, value in updates.items():
        if key not in data:
            added += 1
        data[key] = value
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path, "added", added)


patch(ROOT + r"\zh_cn.json", zh_updates)
patch(ROOT + r"\en_us.json", en_updates)
