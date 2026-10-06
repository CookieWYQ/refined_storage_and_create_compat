# -*- coding: utf-8 -*-
"""补齐「序列执行仓 → 绑定配置」界面相关语言键（中英双语）。

背景（本次修复的根因）：
  * `ChamberBindingConfigScreen` 给滚轮控件挂的 `hinting(...)` 用的是
    `gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.hint`，
    该键在 zh_cn / en_us 里都<b>不存在</b> → tooltip 直接渲染出这串 70+ 字符的原始 key，
    于是「配方类型的提示框」被撑得异常大。本脚本把它补齐。
  * 顺带把全工程扫描出来的其它缺失键一并补齐（只有这 9 个，见 `_audit_lang_keys.py`）。
  * 另外补上「机器方块」tooltip 用的新键，以及会回退成英文 id 的配方类型中文名
    （`machine.rs_create_compat.<Path 首字母大写>`，与 `RecipeTypeNames` 的键格式一致）。

用法:
    python tools/patch_lang_chamber_binding_config.py
"""

import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

CHAMBER = "gui.rs_create_compat.sequence_execution_chamber."
UNIT = "gui.rs_create_compat.unit_pattern_config."
CACHE = "gui.rs_create_compat.collection_cache."

zh_updates = {
    # ---------- 1) 本次界面缺失的键（tooltip 过大的根因） ----------
    CHAMBER + "config.recipe_type.hint": "滚轮切换配方类型；点机器图标可切换该配方类型下的机器",
    # ---------- 2) 机器方块展示 / 轮询（本次新增界面文案） ----------
    CHAMBER + "config.machine.count": "可选机器 %s/%s（点击图标切换）",
    CHAMBER + "config.machine.tip": "能执行该配方类型的机器方块；同一配方类型有多个机器时在此轮询切换",
    # ---------- 3) 全工程扫描出的其它缺失键（与本次界面同批补齐） ----------
    UNIT + "title.edit": "编辑单元样板",
    UNIT + "missing": "还缺少：%s",
    UNIT + "missing.name": "名字",
    UNIT + "missing.recipe_type": "配方类型",
    UNIT + "missing.input": "输入原料",
    "item.rs_create_compat.sequence_unit_pattern.edit_hint": "在样板终端界面可编辑其名字 / 配方类型 / 输入物",
    CACHE + "upgrade.speed.effect": "本机速度升级 ×%s：提升吸取扫描与缓存回流速度",
    CACHE + "upgrade.stack.effect": "本机堆叠升级 ×%s：缓存区每格上限提升至 %s",
    # ---------- 4) 会回退成英文 id 的配方类型中文名 ----------
    "machine.rs_create_compat.Conversion": "转化",
    "machine.rs_create_compat.Basin": "工作盆加工",
    "machine.rs_create_compat.Compacting": "压缩",
    "machine.rs_create_compat.Splashing": "洗涤",
    "machine.rs_create_compat.Haunting": "缠魂",
    "machine.rs_create_compat.Emptying": "排液",
    "machine.rs_create_compat.Crafting": "合成",
    "machine.rs_create_compat.Smelting": "熔炼",
    "machine.rs_create_compat.Blasting": "高炉冶炼",
    "machine.rs_create_compat.Smoking": "烟熏",
    "machine.rs_create_compat.Campfire_cooking": "营火烹饪",
    "machine.rs_create_compat.Stonecutting": "切石",
    "machine.rs_create_compat.Smithing": "锻造",
}

en_updates = {
    # ---------- 1) key missing so far: it was rendered raw and blew up the tooltip ----------
    CHAMBER + "config.recipe_type.hint": "Scroll to switch recipe type; click the machine icon to cycle machines",
    # ---------- 2) machine block display / cycling ----------
    CHAMBER + "config.machine.count": "Machine %s/%s (click the icon to switch)",
    CHAMBER + "config.machine.tip": "Machine block that runs this recipe type; cycles here when several machines share it",
    # ---------- 3) other keys missing across the project ----------
    UNIT + "title.edit": "Edit Unit Pattern",
    UNIT + "missing": "Missing: %s",
    UNIT + "missing.name": "name",
    UNIT + "missing.recipe_type": "recipe type",
    UNIT + "missing.input": "input ingredient",
    "item.rs_create_compat.sequence_unit_pattern.edit_hint": "Its name / recipe type / input can be edited in the Pattern Terminal UI",
    CACHE + "upgrade.speed.effect": "Speed upgrades x%s: boosts pickup scanning and cache flush speed",
    CACHE + "upgrade.stack.effect": "Stack upgrades x%s: cache slot capacity is now %s",
    # ---------- 4) recipe type names that would otherwise fall back to raw ids ----------
    "machine.rs_create_compat.Conversion": "Conversion",
    "machine.rs_create_compat.Basin": "Basin",
    "machine.rs_create_compat.Compacting": "Compacting",
    "machine.rs_create_compat.Splashing": "Splashing",
    "machine.rs_create_compat.Haunting": "Haunting",
    "machine.rs_create_compat.Emptying": "Item Draining",
    "machine.rs_create_compat.Crafting": "Crafting",
    "machine.rs_create_compat.Smelting": "Smelting",
    "machine.rs_create_compat.Blasting": "Blasting",
    "machine.rs_create_compat.Smoking": "Smoking",
    "machine.rs_create_compat.Campfire_cooking": "Campfire Cooking",
    "machine.rs_create_compat.Stonecutting": "Stonecutting",
    "machine.rs_create_compat.Smithing": "Smithing",
}


def patch(name, updates):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    added = []
    changed = []
    for key, value in updates.items():
        if key not in data:
            added.append(key)
        elif data[key] != value:
            changed.append(key)
        data[key] = value
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("[OK ] %s: 新增 %d 个键，覆盖 %d 个已有键（共 %d 键）"
          % (name, len(added), len(changed), len(data)))
    for key in added:
        print("       + %s" % key)
    for key in changed:
        print("       ~ %s" % key)


def main():
    patch("zh_cn.json", zh_updates)
    patch("en_us.json", en_updates)


if __name__ == "__main__":
    main()
