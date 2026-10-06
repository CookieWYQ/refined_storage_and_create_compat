# -*- coding: utf-8 -*-
"""为 v4 GUI 布局补充语言键（避免手写 JSON）。

- 序列装配样板终端：步数文字、卡片右键删除提示、生成/导入按钮提示。
- 归流缓存仓：分区 Tab 提示、顶部统计（收集/入网）、升级槽标签与提示。

用法: python tools/patch_lang_gui_v4.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

SPT = "gui.rs_create_compat.sequence_pattern_terminal."
CC = "gui.rs_create_compat.collection_cache."

zh_updates = {
    # ===== 序列装配样板终端 =====
    SPT + "steps": "步数 %s",
    SPT + "card.slot_hint": "左侧格子：本步的单元样板（可用 JEI 拖入 / 从单元库放入）",
    SPT + "card.delete_hint": "右键该行：删除该步",
    SPT + "generate.tip": "生成装配样板并写入装配样板槽（消耗 1 张 refinedstorage:pattern，需先在样板槽放入）",
    SPT + "generate_unit.tip": "生成单元样板并写入输出槽（需输入标记 + 已选执行仓 + 1 张 refinedstorage:pattern）",
    SPT + "import.tip": "扫描 Create 序列装配配方，把各操作步骤导入单元样板库（不消耗样板）",
    # ===== 归流缓存仓 =====
    CC + "collected": "收集 %s",
    CC + "inserted": "入网 %s",
    CC + "region_marker.tip": "匹配区：ghost 标记可收集物。左键手持物品标记，空手左键点击已标记槽打开配置，Shift+左键清除；同一物品只能标记一次。",
    CC + "region_cache.tip": "缓存区：真实存储。收集到的物品优先写入 RS 网络，装不下的暂存于此并逐 tick 继续尝试入网。",
    CC + "stats.tip": "已收集 %s / 已入网 %s",
    CC + "stats.capacity": "缓存每格上限：%s",
    CC + "upgrade_speed": "速度",
    CC + "upgrade_stack": "堆叠",
    CC + "upgrade_speed.tip": "速度升级 ×%s：提高每 tick 写入网络的组数",
    CC + "upgrade_stack.tip": "堆叠升级 ×%s：提高缓存区每格上限",
}

en_updates = {
    # ===== Sequence Pattern Terminal =====
    SPT + "steps": "Steps %s",
    SPT + "card.slot_hint": "Left slot: the unit pattern for this step (drag in from JEI or the unit library)",
    SPT + "card.delete_hint": "Right-click this row: delete the step",
    SPT + "generate.tip": "Generate the assembly pattern into the pattern slot (consumes 1 refinedstorage:pattern)",
    SPT + "generate_unit.tip": "Generate a unit pattern into the output slot (needs an input marker, a selected crafter and 1 refinedstorage:pattern)",
    SPT + "import.tip": "Scan Create sequenced assembly recipes and import their steps into the unit pattern library (consumes nothing)",
    # ===== Collection Cache =====
    CC + "collected": "Collected %s",
    CC + "inserted": "Inserted %s",
    CC + "region_marker.tip": "Match area: ghost markers. Left-click with an item to mark, empty-hand left-click a marked slot to configure it, Shift+left-click to clear; each item can only be marked once.",
    CC + "region_cache.tip": "Cache area: real storage. Collected items are inserted into the RS network first; what does not fit stays here and is retried every tick.",
    CC + "stats.tip": "Collected %s / Inserted %s",
    CC + "stats.capacity": "Cache capacity per slot: %s",
    CC + "upgrade_speed": "Speed",
    CC + "upgrade_stack": "Stack",
    CC + "upgrade_speed.tip": "Speed upgrades x%s: raises how many stacks are inserted into the network per tick",
    CC + "upgrade_stack.tip": "Stack upgrades x%s: raises the cache capacity per slot",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path, "->", len(updates), "keys")


if __name__ == "__main__":
    patch(os.path.join(LANG_DIR, "zh_cn.json"), zh_updates)
    patch(os.path.join(LANG_DIR, "en_us.json"), en_updates)
