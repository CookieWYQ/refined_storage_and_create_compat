# -*- coding: utf-8 -*-
"""第 7 轮语言键：样板终端「样板输入槽」+ JEI 导入提示 + 经验形态（删自动/颗粒，加经验球/前置说明）。

按用户规则：语言键的增 / 改 / 删一律用 python 脚本（保持行序与缩进，写回后 json.load 校验），
不手写整份 JSON。脚本幂等：重复执行结果一致。

变更清单
--------
删除（导入按钮整条链已删）：
  * gui.rs_create_compat.sequence_pattern_terminal.import            （按钮文案）
  * gui.rs_create_compat.sequence_pattern_terminal.import.tip        （按钮 tooltip）
  * gui.rs_create_compat.sequence_pattern_terminal.import.failed     （按 id 导入已删除）
  * gui.rs_create_compat.sequence_recipe_select.*                    （按钮打开的配方选择子界面已删除，7 键）
删除（经验「自动」模式与「颗粒」收集对象已删）：
  * gui.rs_create_compat.collection_cache.xp_form.auto
  * gui.rs_create_compat.collection_cache.xp_form.nugget
  * gui.rs_create_compat.collection_cache.xp_form.tip.auto
  * gui.rs_create_compat.collection_cache.xp_form.tip.nugget
新增：
  * 样板输入槽 3 键（说清「样板存在终端里、不用背在身上」）
  * 导入后「无可用机器」步数提示 1 键
  * 经验形态「经验球」2 键 + 缺前置 3 键
改写：
  * generate.tip / generate.missing_patterns / pattern_slots.tip（耗材来源改为终端内）
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"
SPT = "gui.rs_create_compat.sequence_pattern_terminal."
CC = "gui.rs_create_compat.collection_cache."

DELETE_KEYS = [
    SPT + "import",
    SPT + "import.tip",
    SPT + "import.failed",
    "gui.rs_create_compat.sequence_recipe_select.title",
    "gui.rs_create_compat.sequence_recipe_select.count",
    "gui.rs_create_compat.sequence_recipe_select.empty",
    "gui.rs_create_compat.sequence_recipe_select.hint",
    "gui.rs_create_compat.sequence_recipe_select.confirm",
    "gui.rs_create_compat.sequence_recipe_select.confirm.tip",
    "gui.rs_create_compat.sequence_recipe_select.cancel",
    CC + "xp_form.auto",
    CC + "xp_form.nugget",
    CC + "xp_form.tip.auto",
    CC + "xp_form.tip.nugget",
]

ZH_UPDATES = {
    # ---- 样板输入槽（耗材入口；用户问「我原本用来放样板的地方现在放哪？」）----
    SPT + "rs_pattern_slots.tip": "样板输入槽：放入精致存储样板（生成耗材）",
    SPT + "rs_pattern_slots.stored": "终端内现有 %s 张样板",
    SPT + "rs_pattern_slots.where": "样板存在终端里，不用背在身上；破坏方块会掉落",
    # ---- 导入后的可见提示（有步骤匹配不到机器 → 留空）----
    SPT + "import.no_machine": "已导入：%s 步无可用机器（该步留空）",
    # ---- 改写：耗材来源改为终端自己的样板输入槽 ----
    SPT + "generate.tip": "按流程生成总样板与每步单元样板，耗材取自终端内样板输入槽",
    SPT + "generate.missing_patterns": "无法生成：终端内缺少 %s 张样板",
    SPT + "pattern_slots.tip": "总样板槽：存放生成出的总样板（可放入 / 取出）",
    # ---- 经验形态：经验球（收集对象 = 经验球实体）----
    CC + "xp_form.orb": "经验球",
    CC + "xp_form.tip.orb": "经验球：吸经验球实体，把经验点并入网络",
    # ---- 经验形态：液态缺前置时置灰并说明缺哪个前置 ----
    CC + "xp_form.tip.liquid.unavailable": "液态：不可选（缺少前置）",
    CC + "xp_form.missing_dependency": "缺少前置：%s",
    CC + "xp_form.required_mod": "机械动力：覆膜工艺",
    # ---- 经验形态：液态（语义不变，措辞与新收集对象对齐）----
    CC + "xp_form.tip.liquid": "液态：只吸世界中的经验流体源方块",
}

EN_UPDATES = {
    SPT + "rs_pattern_slots.tip": "Pattern input slots: put Refined Storage patterns here",
    SPT + "rs_pattern_slots.stored": "%s pattern(s) stored inside this terminal",
    SPT + "rs_pattern_slots.where": "Patterns live in the terminal, not on you; they drop when broken",
    SPT + "import.no_machine": "Imported: %s step(s) have no machine (left unassigned)",
    SPT + "generate.tip": "Generate the master pattern and one unit pattern per step; consumes the terminal's pattern input slots",
    SPT + "generate.missing_patterns": "Cannot generate: terminal has %s pattern(s) too few",
    SPT + "pattern_slots.tip": "Master pattern slots: hold generated patterns (can be inserted / taken)",
    CC + "xp_form.orb": "Orb",
    CC + "xp_form.tip.orb": "Orb: absorb experience orbs and store the points",
    CC + "xp_form.tip.liquid.unavailable": "Liquid: not selectable (missing prerequisite)",
    CC + "xp_form.missing_dependency": "Missing prerequisite: %s",
    CC + "xp_form.required_mod": "Create: Enchantment Industry",
    CC + "xp_form.tip.liquid": "Liquid: only liquid XP source blocks",
}

MAX_ZH = 40


def patch(path, updates, deleted):
    with io.open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    data = json.loads(raw)
    before = len(data)
    for key in deleted:
        data.pop(key, None)
    data.update(updates)
    # 写回：保持行序（dict 保序 + 追加新键到末尾）、indent=2、无 BOM
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    # 写回后重新 json.load 校验（形式要求：必须可解析）
    with io.open(path, "r", encoding="utf-8") as f:
        again = json.load(f)
    assert again == data, "写回后内容不一致：%s" % path
    print("patched %s: %d -> %d keys (+%d / -%d)"
          % (os.path.basename(path), before, len(data), len(updates), before + len(updates) - len(data)))
    return again


def main():
    zh = patch(os.path.join(ROOT, "zh_cn.json"), ZH_UPDATES, DELETE_KEYS)
    en = patch(os.path.join(ROOT, "en_us.json"), EN_UPDATES, DELETE_KEYS)

    problems = []
    # 中文单条 ≤ 40 字
    for key, value in ZH_UPDATES.items():
        if len(value) > MAX_ZH:
            problems.append("中文超过 %d 字：%s (%d)" % (MAX_ZH, key, len(value)))
    # 删除的键必须真的不在
    for key in DELETE_KEYS:
        for name, data in (("zh_cn", zh), ("en_us", en)):
            if key in data:
                problems.append("%s 仍残留已删键：%s" % (name, key))
    # 新增 / 改写的键两语言都在
    for key in list(ZH_UPDATES) + list(EN_UPDATES):
        if key not in zh or key not in en:
            problems.append("键未同步到两种语言：%s" % key)
    # 必须保留的键（JEI 导入路径仍在用）
    for key in (SPT + "import.done", CC + "xp_form.btn", CC + "xp_form.tip.current"):
        if key not in zh or key not in en:
            problems.append("误删了仍在使用的键：%s" % key)

    print("问题总数: %d" % len(problems))
    for p in problems:
        print("  - " + p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
