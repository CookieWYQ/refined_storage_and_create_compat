# -*- coding: utf-8 -*-
# 第三轮 GUI 反馈（补充）：步骤详细配置子窗口的「步骤序号」相关语言键。
# 按用户规则用 Python 规范化更新 lang JSON，避免手写整份 json。
import io
import json
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    "gui.rs_create_compat.step_detail.step_label": "步骤",
    "gui.rs_create_compat.step_detail.step_total": "共 %s 步",
    "gui.rs_create_compat.step_detail.step.tip": (
        "本次编辑作用于第几步（1..%s）。改到当前可见窗口之外的步时，只写入机器绑定，循环次数需先滚动到那一步再改。"
    ),
}

en_updates = {
    "gui.rs_create_compat.step_detail.step_label": "Step",
    "gui.rs_create_compat.step_detail.step_total": "of %s",
    "gui.rs_create_compat.step_detail.step.tip": (
        "Which step this edit applies to (1..%s). When targeting a step outside the visible window only the machine "
        "binding is written; scroll to that step first to change its loop count."
    ),
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path, "keys:", len(updates))


patch(ROOT + r"\zh_cn.json", zh_updates)
patch(ROOT + r"\en_us.json", en_updates)
