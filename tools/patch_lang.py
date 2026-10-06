# -*- coding: utf-8 -*-
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
    "gui.rs_create_compat.sequence_execution_chamber.hint": "放入/取出单元样板（右键本仓打开界面）。接入网络后引擎会认领本机步骤的过渡件喂给朝向所指的 Create 机；产物/中间件回流交给回流总线。",
    "gui.rs_create_compat.sequence_assembly_executor.hint1": "已放入总样板",
    "gui.rs_create_compat.sequence_assembly_executor.hint2": "从生成槽拿起总样板，放入上方格内（一格一张，破坏方块会掉落实物）",
    "gui.rs_create_compat.sequence_assembly_executor.hint3": "每一格总样板都会注册到网络，任意终端可直接请求其产物",
    # 自动合成仓内部存储开关（合成仓管理器界面侧边按钮）
    "gui.rs_create_compat.autocrafter_storage.button.on": "开",
    "gui.rs_create_compat.autocrafter_storage.button.off": "关",
    "gui.rs_create_compat.autocrafter_storage.title": "自动合成仓内部存储",
    "gui.rs_create_compat.autocrafter_storage.state.on": "当前：开（产物先存入仓内，可被管道/玩家提取）",
    "gui.rs_create_compat.autocrafter_storage.state.off": "当前：关（产物直接输出到相邻机器/网络）",
    "gui.rs_create_compat.autocrafter_storage.help": "点击切换本管理器网络内全部自动合成仓的内部存储。关闭时会先把仓内物品/流体全部写回网络存储；若网络空间不足则取消关闭，内容不会丢失。",
    "message.rs_create_compat.autocrafter_storage.enabled": "已开启自动合成仓内部存储（共 %s 台）。",
    "message.rs_create_compat.autocrafter_storage.disabled": "已关闭自动合成仓内部存储（共 %s 台），仓内物品与流体已全部写回网络。",
    "message.rs_create_compat.autocrafter_storage.disable_failed": "网络存储空间不足或网络不可用，已取消关闭：物品与流体仍安全保存在自动合成仓内，没有任何丢失。",
    "message.rs_create_compat.autocrafter_storage.no_autocrafter": "该管理器未连接任何自动合成仓。",
    "message.rs_create_compat.autocrafter_storage.disabled_by_config": "服务端配置已禁用自动合成仓内部存储（autocrafterStorageEnabled = false），无法开启。",
    # 配方类型原始 id 的 tooltip 行（暗灰+斜体）
    "gui.rs_create_compat.recipe_type.id_label": "[id] %s",
    "gui.rs_create_compat.recipe_type.id_unset": "未设置",
}

en_updates = {
    "gui.rs_create_compat.sequence_execution_chamber.hint": "Insert/remove unit patterns (right-click this block to open its UI). Once networked it claims transitional items for its step and feeds the Create machine it faces; product/intermediate returns are handled by a Return Bus.",
    "gui.rs_create_compat.sequence_assembly_executor.hint1": "Master patterns stored",
    "gui.rs_create_compat.sequence_assembly_executor.hint2": "Take a generated master pattern and place it in a grid slot above (one per slot; breaking the block drops the real items)",
    "gui.rs_create_compat.sequence_assembly_executor.hint3": "Every stored master pattern is registered to the network and can be requested from any terminal",
    # Autocrafter internal storage toggle (side button of the Autocrafter Manager screen)
    "gui.rs_create_compat.autocrafter_storage.button.on": "ON",
    "gui.rs_create_compat.autocrafter_storage.button.off": "OFF",
    "gui.rs_create_compat.autocrafter_storage.title": "Autocrafter internal storage",
    "gui.rs_create_compat.autocrafter_storage.state.on": "State: ON (outputs are stored inside, extractable by pipes/players)",
    "gui.rs_create_compat.autocrafter_storage.state.off": "State: OFF (outputs go straight to the adjacent machine/network)",
    "gui.rs_create_compat.autocrafter_storage.help": "Click to toggle the internal storage of every autocrafter in this manager's network. When turning OFF, all stored items/fluids are written back to the network storage first; if there is not enough room the toggle is cancelled and nothing is lost.",
    "message.rs_create_compat.autocrafter_storage.enabled": "Autocrafter internal storage enabled (%s autocrafter(s)).",
    "message.rs_create_compat.autocrafter_storage.disabled": "Autocrafter internal storage disabled (%s autocrafter(s)); stored items and fluids were written back to the network.",
    "message.rs_create_compat.autocrafter_storage.disable_failed": "Not enough network storage space (or the network is unavailable); the toggle was cancelled. Items and fluids are still safely kept inside the autocrafters.",
    "message.rs_create_compat.autocrafter_storage.no_autocrafter": "This manager has no autocrafter connected.",
    "message.rs_create_compat.autocrafter_storage.disabled_by_config": "Autocrafter internal storage is disabled by the server config (autocrafterStorageEnabled = false) and cannot be enabled.",
    # Recipe type raw id tooltip line (dark gray + italic)
    "gui.rs_create_compat.recipe_type.id_label": "[id] %s",
    "gui.rs_create_compat.recipe_type.id_unset": "Not set",
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
