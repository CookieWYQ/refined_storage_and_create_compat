# -*- coding: utf-8 -*-
"""序列装配样板终端 GUI v2（256×324）语言键追加/更新。
按用户规则：语言文件用脚本改，不手写整份 JSON。"""
import io
import json
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    # 顶栏
    "gui.rs_create_compat.sequence_pattern_terminal.tab.unit": "单元样板库",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.assembly": "装配编排",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.unit.tip":
        "单元样板库页：制作/浏览单元样板（三要素：是否需要输入原料、配方类型、名字）",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.assembly.tip":
        "装配编排页：把单元样板拼成步骤序列、为每步指定机器、生成总样板",
    # 右列小标题 / 标签
    "gui.rs_create_compat.sequence_pattern_terminal.results.short": "产物",
    "gui.rs_create_compat.sequence_pattern_terminal.inventory": "物品栏",
    "gui.rs_create_compat.sequence_pattern_terminal.op.assembly": "装配",
    "gui.rs_create_compat.sequence_pattern_terminal.op.input": "输入",
    "gui.rs_create_compat.sequence_pattern_terminal.op.output": "输出",
    "gui.rs_create_compat.sequence_pattern_terminal.op.assembly.tip":
        "装配槽：生成的总样板会写在这里，可直接取出（生成消耗 1 张 refinedstorage:pattern）",
    "gui.rs_create_compat.sequence_pattern_terminal.op.input.tip":
        "同一个真实槽位：单元样板库页 = 输入标记（生成单元样板用），装配编排页 = 原料标记",
    "gui.rs_create_compat.sequence_pattern_terminal.op.output.tip":
        "输出槽：生成的单元样板会写在这里，可直接取出",
    "gui.rs_create_compat.sequence_pattern_terminal.op.input.autocrafter":
        "当前自动合成仓：%s",
    # 流程卡片
    "gui.rs_create_compat.sequence_pattern_terminal.no_machine": "无可用机器",
    "gui.rs_create_compat.sequence_pattern_terminal.card.op": "操作：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.machine": "机器：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.hint":
        "Shift+滚轮：调整该行次数（上 +1 / 下 −1）",
    "gui.rs_create_compat.sequence_pattern_terminal.card.machine.hint":
        "左键点机器名左半边：上一台；右半边：下一台（在该步配方类型对应的机器里循环）",
    "gui.rs_create_compat.sequence_pattern_terminal.card.row_hint":
        "左侧格子：该步的单元样板（需要输入原料时显示输入物预览）",
    # 控制行
    "gui.rs_create_compat.sequence_pattern_terminal.steps.tip":
        "流程共 %s 步（[-]/[+] 增删步骤；滚轮或滚动条浏览更多步骤）",
    "gui.rs_create_compat.sequence_pattern_terminal.import.tip":
        "扫描 Create 序列装配配方，把各操作步骤导入单元样板库（不消耗样板）",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.tip":
        "生成装配样板并写入装配槽（消耗 1 张 refinedstorage:pattern；每步需有可用机器）",
    "gui.rs_create_compat.sequence_pattern_terminal.generate_unit.tip":
        "生成单元样板并写入输出槽（需输入标记 + 已选自动合成仓 + 1 张 refinedstorage:pattern）",
}

en_updates = {
    "gui.rs_create_compat.sequence_pattern_terminal.tab.unit": "Unit Patterns",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.assembly": "Assembly",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.unit.tip":
        "Unit pattern library: create/browse unit patterns (requires-input, recipe type, name)",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.assembly.tip":
        "Assembly page: build the step sequence, assign a machine per step, generate the master pattern",
    "gui.rs_create_compat.sequence_pattern_terminal.results.short": "Results",
    "gui.rs_create_compat.sequence_pattern_terminal.inventory": "Inventory",
    "gui.rs_create_compat.sequence_pattern_terminal.op.assembly": "Assy",
    "gui.rs_create_compat.sequence_pattern_terminal.op.input": "Input",
    "gui.rs_create_compat.sequence_pattern_terminal.op.output": "Output",
    "gui.rs_create_compat.sequence_pattern_terminal.op.assembly.tip":
        "Assembly slot: the generated master pattern is written here and can be taken out (uses 1 refinedstorage:pattern)",
    "gui.rs_create_compat.sequence_pattern_terminal.op.input.tip":
        "One real slot: on the unit page it is the input marker, on the assembly page it is the ingredient marker",
    "gui.rs_create_compat.sequence_pattern_terminal.op.output.tip":
        "Output slot: the generated unit pattern is written here and can be taken out",
    "gui.rs_create_compat.sequence_pattern_terminal.op.input.autocrafter":
        "Selected autocrafter: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.no_machine": "No machine available",
    "gui.rs_create_compat.sequence_pattern_terminal.card.op": "Operation: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.machine": "Machine: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.hint":
        "Shift+scroll: adjust this step's count (+1 up / -1 down)",
    "gui.rs_create_compat.sequence_pattern_terminal.card.machine.hint":
        "Left-click the left half of the machine name for the previous machine, the right half for the next",
    "gui.rs_create_compat.sequence_pattern_terminal.card.row_hint":
        "Left cell: this step's unit pattern (shows the input item preview when input is required)",
    "gui.rs_create_compat.sequence_pattern_terminal.steps.tip":
        "%s step(s) in total ([-]/[+] to add/remove; scroll to browse)",
    "gui.rs_create_compat.sequence_pattern_terminal.import.tip":
        "Scan Create sequenced-assembly recipes and import their steps into the unit library (no pattern cost)",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.tip":
        "Generate the assembly pattern into the assembly slot (uses 1 refinedstorage:pattern; every step needs a machine)",
    "gui.rs_create_compat.sequence_pattern_terminal.generate_unit.tip":
        "Generate the unit pattern into the output slot (needs an input marker, a selected autocrafter and 1 refinedstorage:pattern)",
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
