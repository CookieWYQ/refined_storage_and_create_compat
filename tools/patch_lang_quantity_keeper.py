# -*- coding: utf-8 -*-
# 定量保持器：容器物品标记语义变更（按物品处理，不再反推流体）后的 tooltip 文案更新。
# 用 Python 规范化更新 lang JSON（避免手写 JSON 出错）。
import json
import io
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    "gui.rs_create_compat.quantity_keeper.marker_tooltip":
        "放入物品，或从 JEI 拖入流体作为标记（样板）：指定要保持数量的对象类型，仅标记不消耗该物品。"
        "容器物品（如水桶）按物品计数——存水桶、取水桶；要保持流体请从 JEI 拖入流体。",
}

en_updates = {
    "gui.rs_create_compat.quantity_keeper.marker_tooltip":
        "Place an item, or drag a fluid in from JEI, as the marker (pattern): defines which object to keep a "
        "target amount of; the marker is not consumed. Container items (e.g. a water bucket) count as items - "
        "buckets go in and buckets come out; to keep a fluid, drag the fluid in from JEI.",
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
