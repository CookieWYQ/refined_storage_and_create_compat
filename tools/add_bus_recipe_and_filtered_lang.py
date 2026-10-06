# -*- coding: utf-8 -*-
"""为本轮 F（详细配置按配方完整分组）与 H（总线界面只显示已勾选）补语言键。

做法：json.load → 校验键集 / 值 → 插入 → 重新 dump（sort_keys，与既有文件格式一致）。
前后都做校验，任何一处不符就 SystemExit，绝不写坏语言文件。

用法：python tools/add_bus_recipe_and_filtered_lang.py
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

BUS = "gui.rs_create_compat.bus_config."
EXP = "gui.rs_create_compat.exporter_executor."
IMP = "gui.rs_create_compat.importer_executor."

# key -> (zh_cn, en_us)
NEW = {
    # ---- F：详细配置里「按配方完整分组」的两句 ----
    BUS + "group.unclassified": ("未归类", "Unclassified"),
    BUS + "group.recipe.tip": ("这条总线上有多套样板，每套配方的分类各自成组",
                               "Each pattern's groups are separate here"),
    # ---- H：总线界面「默认只显示已勾选」的两句（两条总线各一份，语义相反） ----
    EXP + "no_selected_categories": ("还没勾选任何类别（点「详细配置…」开始勾选）",
                                     "No categories selected yet (use Details)"),
    EXP + "filtered.tip": ("条上只显示已勾选的类别；未勾选的请到「详细配置…」里看",
                           "Only selected ones are listed; open Details for all"),
    IMP + "no_selected_categories": ("还没勾选任何要收回的类别（点「详细配置…」开始勾选）",
                                     "Nothing to collect is selected yet (use Details)"),
    IMP + "filtered.tip": ("条上只显示已勾选的类别；未勾选的请到「详细配置…」里看",
                           "Only selected ones are listed; open Details for all"),
}

LIMIT_ZH = 40  # 用户硬要求：中文 ≤ 40 字
LIMIT_EN = 60  # 与既有自检的英文长度口径一致（≤ 60 字符）


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def check_values(mapping, limit, label):
    problems = []
    for key, value in mapping.items():
        if not key or not str(value).strip():
            problems.append("空键或空值：%r" % (key,))
        if len(value) > limit:
            problems.append("%s超过 %d 字（%d）：%s" % (label, limit, len(value), key))
    return problems


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")
    zh, en = load("zh_cn.json"), load("en_us.json")

    before = (len(zh), len(en), set(zh) == set(en))
    print("写入前：zh=%d en=%d 键集一致=%s" % before)
    if not before[2]:
        print("写入前 zh/en 键集已不一致，拒绝写入")
        return 1

    problems = check_values({k: v[0] for k, v in NEW.items()}, LIMIT_ZH, "中文")
    problems += check_values({k: v[1] for k, v in NEW.items()}, LIMIT_EN, "英文")
    if problems:
        print("语言值校验失败：")
        for problem in problems:
            print("  - " + problem)
        return 1

    for key, (zh_value, en_value) in NEW.items():
        zh[key] = zh_value
        en[key] = en_value

    after = (len(zh), len(en), set(zh) == set(en))
    print("写入后：zh=%d en=%d 键集一致=%s（新增 %d 条）" % (after[0], after[1], after[2], len(NEW)))
    if not after[2]:
        print("写入后键集不一致，拒绝落盘")
        return 1
    if after[0] != before[0] + len(NEW):
        print("键数增量不符，拒绝落盘")
        return 1

    for path, data in ((zh_path, zh), (en_path, en)):
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True))
            handle.write("\n")

    # 落盘后重新加载复核（JSON 合法 + 内容在位）
    zh2, en2 = load("zh_cn.json"), load("en_us.json")
    missing = [key for key in NEW if key not in zh2 or key not in en2]
    if missing or set(zh2) != set(en2):
        print("落盘复核失败：%s" % missing)
        return 1
    for key, (zh_value, en_value) in sorted(NEW.items()):
        if zh2[key] != zh_value or en2[key] != en_value:
            print("落盘内容不符：%s" % key)
            return 1
    print("落盘复核通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
