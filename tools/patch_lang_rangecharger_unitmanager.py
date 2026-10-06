# -*- coding: utf-8 -*-
"""语言文件补丁（本轮：范围充电器速率 tooltip + 单元样板管理舱不再展示总样板）。

1. 新增 `gui.rs_create_compat.range_charger.rate.tip`：
   充电速率在界面里按固定宽度截断，速率很大时读不出真实值，改由悬停 tooltip 给出完整数值与构成。
2. 删除 `gui.rs_create_compat.unit_pattern_manager.group.merged`：
   该分组展示的是序列装配样板库的<b>总样板</b>，本轮已从单元样板管理舱移除（总样板归 RS 自动合成管理舱管）。
3. 改写 `block.rs_create_compat.unit_pattern_manager.help`：去掉「合并为一个视图」的旧描述。

保留原文件行序与缩进，只做最小增删（写入前用 json 校验）。
用法：python tools/patch_lang_rangecharger_unitmanager.py
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

# 新键：锚点键之后插入
NEW_KEYS = {
    "gui.rs_create_compat.range_charger.rate.tip": {
        "anchor": '"gui.rs_create_compat.range_charger.rate"',
        "zh_cn": "基础 %s FE/t，每装 1 个速度升级翻倍（当前 %s 级）",
        "en_us": "Base %s FE/t, doubled per speed upgrade (%s installed)",
    },
}
# 删除的键
DROP_KEYS = ("gui.rs_create_compat.unit_pattern_manager.group.merged",)
# 改写的键
REWRITE = {
    "block.rs_create_compat.unit_pattern_manager.help": {
        "zh_cn": "管理网络中每台执行舱的单元样板（按执行舱分组），另含序列装配样板终端的旧单元样板库（只出不进）。"
                 "总样板不在这里，由自动合成管理舱管理。",
        "en_us": "Manages unit patterns of every execution chamber in the network, grouped by chamber, plus the "
                 "legacy unit pattern library of sequence pattern terminals (take-only). Assembly patterns are "
                 "not here - use the autocrafter manager for those.",
    },
}


def patch(path, lang):
    with io.open(path, encoding="utf-8") as f:
        lines = f.readlines()

    removed = 0
    out = []
    for line in lines:
        if any('"%s"' % key in line for key in DROP_KEYS):
            removed += 1
            continue
        rewritten = False
        for key, value in REWRITE.items():
            if '"%s"' % key in line:
                out.append('  "%s": %s,\n' % (key, json.dumps(value[lang], ensure_ascii=False)))
                rewritten = True
                break
        if rewritten:
            continue
        out.append(line)
        # 锚点之后插入新键
        for key, spec in NEW_KEYS.items():
            if spec["anchor"] in line:
                out.append('  "%s": %s,\n' % (key, json.dumps(spec[lang], ensure_ascii=False)))

    text = "".join(out)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    with io.open(path, encoding="utf-8") as f:
        json.load(f)  # 合法性校验
    print("[OK] %s：删除 %d 键，新增 %d 键，改写 %d 键"
          % (os.path.basename(path), removed, len(NEW_KEYS), len(REWRITE)))


for name, lang in (("zh_cn.json", "zh_cn"), ("en_us.json", "en_us")):
    patch(os.path.join(LANG_DIR, name), lang)
