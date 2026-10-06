# -*- coding: utf-8 -*-
# 用 Python 规范化更新 lang JSON（避免手写 JSON 出错）。
# 覆盖：任务2（定量保持器直接标记流体/气体 + 堵塞提示）、任务4（归流缓存仓流体格子）。
import json
import io
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    # 任务3：同类存储「堵塞」状态提示（停止输出但内容仍可取出）
    "gui.rs_create_compat.quantity_keeper.blocked": "堵塞",
    "gui.rs_create_compat.quantity_keeper.blocked.tooltip":
        "内部存在与当前标记不匹配的资源，已停止输出（不再写入网络）。"
        "这些资源不会被销毁，仍可被玩家或管道取出；把标记改回同类资源即可恢复输出。",
    # 任务4：归流缓存仓流体格子
    "gui.rs_create_compat.collection_cache.cache.fluid_amount": "数量：%s",
    "gui.rs_create_compat.collection_cache.cache.fluid_click": "左键点击：取出 1 桶（1000 mB）",
    "gui.rs_create_compat.collection_cache.cache.fluid_insufficient": "不足 1 桶，无法取出",
}

en_updates = {
    "gui.rs_create_compat.quantity_keeper.blocked": "Blocked",
    "gui.rs_create_compat.quantity_keeper.blocked.tooltip":
        "The keeper holds resources that do not match the current marker, so output has stopped "
        "(nothing more is written to the network). Nothing is destroyed: the contents can still be "
        "extracted by players or pipes. Change the marker back to the same resource to resume.",
    "gui.rs_create_compat.collection_cache.cache.fluid_amount": "Amount: %s",
    "gui.rs_create_compat.collection_cache.cache.fluid_click": "Left-click: take out 1 bucket (1000 mB)",
    "gui.rs_create_compat.collection_cache.cache.fluid_insufficient": "Less than 1 bucket - cannot take out",
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
