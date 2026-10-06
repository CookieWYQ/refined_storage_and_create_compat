# -*- coding: utf-8 -*-
# 序列装配样板终端 v3 GUI 新增语言键（用 Python 规范化更新 lang JSON，避免手写整份 JSON）。
import io
import json
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    # 顶部样板计数（v3 位置很窄，改用短文本 + tooltip）
    "gui.rs_create_compat.sequence_pattern_terminal.pattern_count.short": "×%s",
    "gui.rs_create_compat.sequence_pattern_terminal.pattern_count.tip": "剩余 RS 原版样板 ×%s（生成样板 / 导入配方各消耗 1 张）",
    # 单元样板库
    "gui.rs_create_compat.sequence_pattern_terminal.library.tip": "单元样板库：存放单元样板，可用上方搜索框过滤；滚轮或右侧滚动条翻页",
    "gui.rs_create_compat.sequence_pattern_terminal.library.page.tip": "第 %s/%s 页（共 %s 格）",
    # 流程编排
    "gui.rs_create_compat.sequence_pattern_terminal.arrangement.tip": "流程编排：每行一步；[-]/[+] 调整该步次数，Shift+滚轮 轮换绑定执行仓",
    "gui.rs_create_compat.sequence_pattern_terminal.steps.tip": "流程共 %s 步；当前窗口显示第 %s–%s 步",
    "gui.rs_create_compat.sequence_pattern_terminal.card.step": "第 %s 步",
    "gui.rs_create_compat.sequence_pattern_terminal.card.count": "次数：×%s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.hint": "Shift+滚轮：轮换该步绑定的序列执行仓",
    # 产物 / 废料
    "gui.rs_create_compat.sequence_pattern_terminal.results.tip": "预期产物（第 %s 格）",
    "gui.rs_create_compat.sequence_pattern_terminal.scraps.tip": "废料（第 %s 格）",
    "gui.rs_create_compat.sequence_pattern_terminal.chance": "概率：%s%%",
    "gui.rs_create_compat.sequence_pattern_terminal.chance.hint": "Shift+滚轮：调整概率（±10%）",
    "gui.rs_create_compat.sequence_pattern_terminal.slot.tip": "手持物品点击放入（配置项不可取出）",
    # 单元样板页的执行仓选择
    "gui.rs_create_compat.sequence_pattern_terminal.autocrafter.selected": "执行仓：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.autocrafter.tip": "当前执行仓：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.autocrafter.hint": "滚轮：切换执行仓",
    # 循环次数
    "gui.rs_create_compat.sequence_pattern_terminal.loops.tip": "整体循环次数（1–64）",
    # 顶部页签
    "gui.rs_create_compat.sequence_pattern_terminal.tab.unit.tip": "单元样板制作页：机器 + 输入 → 生成单元样板",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.assembly.tip": "装配样板制作页：装配槽 / 原料 / 循环 + 产物 / 废料",
}

en_updates = {
    "gui.rs_create_compat.sequence_pattern_terminal.pattern_count.short": "×%s",
    "gui.rs_create_compat.sequence_pattern_terminal.pattern_count.tip": "Blank RS patterns left: ×%s (each generate/import consumes 1)",
    "gui.rs_create_compat.sequence_pattern_terminal.library.tip": "Unit pattern library: unit patterns, filter with the search box; scroll wheel or the scrollbar to page",
    "gui.rs_create_compat.sequence_pattern_terminal.library.page.tip": "Page %s/%s (%s slots)",
    "gui.rs_create_compat.sequence_pattern_terminal.arrangement.tip": "Sequence: one step per row; [-]/[+] adjusts repeats, Shift+scroll cycles the bound chamber",
    "gui.rs_create_compat.sequence_pattern_terminal.steps.tip": "%s steps in total; the window shows steps %s-%s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.step": "Step %s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.count": "Repeats: ×%s",
    "gui.rs_create_compat.sequence_pattern_terminal.card.hint": "Shift+scroll: cycle the sequence chamber bound to this step",
    "gui.rs_create_compat.sequence_pattern_terminal.results.tip": "Expected result (slot %s)",
    "gui.rs_create_compat.sequence_pattern_terminal.scraps.tip": "Scrap (slot %s)",
    "gui.rs_create_compat.sequence_pattern_terminal.chance": "Chance: %s%%",
    "gui.rs_create_compat.sequence_pattern_terminal.chance.hint": "Shift+scroll: adjust chance (±10%)",
    "gui.rs_create_compat.sequence_pattern_terminal.slot.tip": "Click with a held item to set it (config slots cannot be taken out)",
    "gui.rs_create_compat.sequence_pattern_terminal.autocrafter.selected": "Chamber: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.autocrafter.tip": "Current chamber: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.autocrafter.hint": "Scroll: switch chamber",
    "gui.rs_create_compat.sequence_pattern_terminal.loops.tip": "Total loops (1-64)",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.unit.tip": "Unit pattern page: machine + input → unit pattern",
    "gui.rs_create_compat.sequence_pattern_terminal.tab.assembly.tip": "Assembly pattern page: assembly slot / ingredient / loops + results / scrap",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    before = len(data)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched %s (%d -> %d keys, +%d)" % (path, before, len(data), len(data) - before))


patch(ROOT + r"\zh_cn.json", zh_updates)
patch(ROOT + r"\en_us.json", en_updates)
