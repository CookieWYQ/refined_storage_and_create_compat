# -*- coding: utf-8 -*-
"""生成 / 改写「高级远程终端」的物品模型 JSON（绑定 / 未绑定两态）。

做法与精致存储原版 wireless_grid 一致：
物品模型本身不含贴图，只用 overrides 按布尔物品属性
`refinedstorage:network_bound_active`(0=未绑定 / 1=已绑定) 指向两个分支模型；
已绑定分支 = *_active（沿用原贴图），未绑定分支 = *_inactive（灰色屏幕贴图）。
创造版（creative_advanced_remote_terminal）永远视为已绑定，不生成 inactive 分支。

用法: python tools/gen_remote_terminal_models.py
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
ITEM_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "models", "item")
MODID = "rs_create_compat"
PROPERTY = "refinedstorage:network_bound_active"


def write_json(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("written", path)


def branch_model(texture):
    """单分支模型：父 item/generated，只有一层贴图。"""
    return {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "%s:item/%s" % (MODID, texture)},
    }


def overridden_model(name):
    """主模型：不含贴图，仅按绑定属性切换到 active / inactive 分支。"""
    return {
        "parent": "minecraft:item/generated",
        "overrides": [
            {
                "predicate": {PROPERTY: 0},
                "model": "%s:item/%s_inactive" % (MODID, name),
            },
            {
                "predicate": {PROPERTY: 1},
                "model": "%s:item/%s_active" % (MODID, name),
            },
        ],
    }


def main():
    for name in ("advanced_remote_terminal", "advanced_remote_terminal_charged"):
        write_json(os.path.join(ITEM_DIR, name + "_active.json"), branch_model(name))
        write_json(os.path.join(ITEM_DIR, name + "_inactive.json"), branch_model(name + "_inactive"))
        write_json(os.path.join(ITEM_DIR, name + ".json"), overridden_model(name))


if __name__ == "__main__":
    main()
