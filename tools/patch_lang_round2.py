# -*- coding: utf-8 -*-
"""GUI 反馈第二轮（GUI_FEEDBACK_ROUND2.md）语言键追加/更新。
按用户规则：语言文件用脚本改，不手写整份 JSON。"""
import io
import json
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

SPT = "gui.rs_create_compat.sequence_pattern_terminal."
SD = "gui.rs_create_compat.step_detail."
RS = "gui.rs_create_compat.sequence_recipe_select."
AK = "gui.rs_create_compat.advanced_quantity_keeper."

zh_updates = {
    # ===== 序列装配样板终端（单页 v6） =====
    SPT + "search_hint": "搜索流程编排",
    SPT + "op.input": "输入原料",
    SPT + "op.input.tip":
        "输入原料标记：导入配方时自动填入；也可手持物品点击设置（复制标记，不消耗手持物品）",
    SPT + "generate.tip":
        "生成装配样板：消耗 1 张 refinedstorage:pattern，成品直接进入你的背包（背包满则掉在脚下）",
    SPT + "generate.done": "已生成装配样板",
    SPT + "import.tip":
        "打开配方选择界面：把一张 Create 序列装配配方一键转移进流程（消耗 1 张 refinedstorage:pattern）",
    SPT + "import.done": "已导入配方：流程 / 原料 / 产物（含概率）已写入",
    SPT + "import.failed": "导入失败：该配方无法读取（样板已返还）",
    SPT + "card.hint": "Shift+滚轮：调整该行次数（上 +1 / 下 −1）",
    SPT + "card.detail_hint": "Ctrl+左键：打开详细配置（循环次数 / 绑定机器）",
    SPT + "card.machine.hint": "机器区：滚轮切换该步配方类型的机器；左键打开机器选择弹窗",
    SPT + "arrangement.tip":
        "流程编排：每行一步；Shift+滚轮 调整该步次数，Ctrl+左键 打开详细配置，机器区滚轮/左键切换机器，右键删除该步",
    SPT + "loops.label": "循环",
    SPT + "loops.tip": "整体循环次数（1–64）",
    SPT + "section_readonly.tip": "只读标记槽：不可取出/放入（由导入配方或 JEI 标记写入）",
    # ===== 步骤详细配置子界面 =====
    SD + "title": "步骤详细配置",
    SD + "step": "第 %s 步",
    SD + "recipe_type": "配方类型：%s",
    SD + "count": "循环次数",
    SD + "count.tip": "该步的重复次数（配合 [-] / [+] 或直接输入，1–1024）",
    SD + "machine": "绑定机器",
    SD + "machine.hint": "滚轮 / 左右点击切换绑定机器",
    SD + "machine.tip": "把该步指派到某台序列执行仓（第一项 = 未绑定）",
    SD + "unbound": "未绑定",
    SD + "confirm": "确定",
    SD + "confirm.tip": "写入循环次数与机器绑定（服务端权威校验）",
    SD + "cancel": "取消",
    # ===== 导入配方选择子界面 =====
    RS + "title": "导入序列装配配方",
    RS + "count": "共 %s 条 Create 序列装配配方",
    RS + "hint": "滚轮 / 左右点击选择配方",
    RS + "empty": "当前整合包里没有 Create 序列装配配方",
    RS + "confirm": "导入",
    RS + "confirm.tip": "把选中配方一键转移进流程（消耗 1 张 refinedstorage:pattern）",
    RS + "cancel": "取消",
    # ===== 高级物品定量保持器 =====
    AK + "stored": "已存 %s",
    AK + "on": "开",
    AK + "off": "关",
    AK + "state.autocraft": "合成 %s",
    AK + "state.destroy": "销毁 %s",
    AK + "overflow_label": "过量销毁",
    AK + "overflow_on.tip": "已开启：超过目标数量的同种资源会被销毁（每槽独立）",
    AK + "overflow_off.tip": "已关闭：绝不销毁任何资源，超过目标的部分会保留（每槽独立）",
}

en_updates = {
    SPT + "search_hint": "Search flow",
    SPT + "op.input": "Ingredient",
    SPT + "op.input.tip":
        "Ingredient marker: filled automatically by recipe import; or click with an item in hand to copy a marker (free)",
    SPT + "generate.tip":
        "Generate the assembly pattern: uses 1 refinedstorage:pattern, the result goes straight to your inventory (drops if full)",
    SPT + "generate.done": "Assembly pattern generated",
    SPT + "import.tip":
        "Open the recipe picker: one-click import a Create sequenced-assembly recipe (uses 1 refinedstorage:pattern)",
    SPT + "import.done": "Recipe imported: flow / ingredient / results (with chances) written",
    SPT + "import.failed": "Import failed: recipe unreadable (the pattern was refunded)",
    SPT + "card.hint": "Shift+scroll: adjust this step's count (+1 up / -1 down)",
    SPT + "card.detail_hint": "Ctrl+left-click: open the detail config (loops / bound machine)",
    SPT + "card.machine.hint":
        "Machine area: scroll to cycle machines of this recipe type; left-click to open the machine picker",
    SPT + "arrangement.tip":
        "Flow: one step per row; Shift+scroll changes count, Ctrl+left-click opens detail config, machine area scrolls/left-clicks to pick, right-click deletes",
    SPT + "loops.label": "Loops",
    SPT + "loops.tip": "Overall loop count (1-64)",
    SPT + "section_readonly.tip": "Read-only marker slot: cannot be taken or inserted (written by import or JEI)",
    SD + "title": "Step detail config",
    SD + "step": "Step %s",
    SD + "recipe_type": "Recipe type: %s",
    SD + "count": "Loops",
    SD + "count.tip": "Repeat count of this step (use [-] / [+] or type it, 1-1024)",
    SD + "machine": "Bound machine",
    SD + "machine.hint": "Scroll / click sides to change the bound machine",
    SD + "machine.tip": "Assign this step to a sequence execution chamber (first entry = unbound)",
    SD + "unbound": "Unbound",
    SD + "confirm": "Confirm",
    SD + "confirm.tip": "Write loops and machine binding (server-authoritative)",
    SD + "cancel": "Cancel",
    RS + "title": "Import sequenced-assembly recipe",
    RS + "count": "%s Create sequenced-assembly recipe(s)",
    RS + "hint": "Scroll / click sides to pick a recipe",
    RS + "empty": "No Create sequenced-assembly recipe in this pack",
    RS + "confirm": "Import",
    RS + "confirm.tip": "Import the selected recipe (uses 1 refinedstorage:pattern)",
    RS + "cancel": "Cancel",
    AK + "stored": "Stored %s",
    AK + "on": "ON",
    AK + "off": "OFF",
    AK + "state.autocraft": "Craft %s",
    AK + "state.destroy": "Destroy %s",
    AK + "overflow_label": "Destroy overflow",
    AK + "overflow_on.tip": "Enabled: resource beyond the target is destroyed (per slot)",
    AK + "overflow_off.tip": "Disabled: nothing is ever destroyed; surplus is kept (per slot)",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path, "+%d keys" % len(updates))


patch(ROOT + r"\zh_cn.json", zh_updates)
patch(ROOT + r"\en_us.json", en_updates)
