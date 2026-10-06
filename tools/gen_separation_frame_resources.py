# -*- coding: utf-8 -*-
"""生成「分隔框架 / 无限分隔框架」的数据与资源文件（批 4a）。

为什么用脚本生成而不是手写：这些文件都有严格的格式与命名要求（blockstate / 配方 /
掉落物表），手写极易出现尾逗号、命名漂移等问题。脚本可重复执行（幂等），写完用 json.load 回读校验。

生成清单
--------
assets/rs_create_compat/blockstates/{separation_frame,infinite_separation_frame}.json
assets/rs_create_compat/models/item/{...}.json            （父级指向对应方块模型）
data/rs_create_compat/recipe/separation_frame.json                    （铁锭 X 形 5 格 → 64 个）
data/rs_create_compat/recipe/infinite_separation_frame.json           （ABA/BAB/ABA → 1 个）
data/rs_create_compat/recipe/infinite_separation_frame_from_frame.json（1 框架 + 4 钻石，无序 → 1 个）
data/rs_create_compat/loot_table/blocks/separation_frame.json         （可回收：拆下掉落自身）

**不生成**（用户自有的美术资产，脚本一旦覆盖就等于推翻用户成果）：
  assets/rs_create_compat/models/block/separation_frame.json   —— 用户用 Blockbench 自制的 12 元素模型
                                                                  （无限版是 `{"parent": ...}` 的薄壳，两个框架共用）
  assets/rs_create_compat/textures/block/separation_frame.png  —— 用户自绘的 16×16 半透明贴图（两个框架共用一张）
所以脚本只维护「代码侧必须正确的路径与数据」，改模型 / 贴图请直接改那两个文件。
脚本对用户模型只做<b>只读体检</b>（check_user_model）：两个「不能丢」的键必须有 ——
  * `parent = block/block`：方块标准父模型，提供 gui / thirdperson / firstperson / ground / fixed 的 display 变换
    （没有它就会出现用户报的「手持过大、物品栏显示成一个平面」）；
  * `render_type = translucent`：用户贴图是 ARGB 半透明，cutout 会把 alpha 裁掉。
补这两个键用专门的小脚本 tools/add_separation_frame_model_parent.py（同样不覆盖用户的 elements / textures）。
**本脚本永远不会写这个模型文件，重跑也不会把上面两个键改回去。**

注意：**无限分隔框架刻意不生成掉落物表** —— 用户要求「不能回收」，没有掉落物表即拆下不掉东西。

用法：python tools/gen_separation_frame_resources.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat")
MODID = "rs_create_compat"

FRAMES = ("separation_frame", "infinite_separation_frame")

# 方块模型 / 贴图由用户自绘（见文件头说明），脚本只做存在性 + 契约检查，绝不覆盖。
USER_OWNED = (
    os.path.join(ASSETS, "models", "block", "separation_frame.json"),
    os.path.join(ASSETS, "textures", "block", "separation_frame.png"),
)

written = []


def write_json(path, data):
    """写 JSON（2 空格缩进 + 末尾换行，与工程既有资源文件一致）并回读校验。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    with open(path, "r", encoding="utf-8") as handle:
        json.load(handle)  # 校验：读不回来就直接抛，不留半成品
    written.append(os.path.relpath(path, ROOT))


def check_user_model():
    """只读体检：用户模型必须带「父模型 + 半透明」这两个契约键（本脚本永远不写它）。

    为什么要在生成脚本里查：这两个键一旦被谁（比如某次手工整份重写）删掉，游戏里就会退化成
    「手持过大 / 物品栏显示成平面 / 半透明质感丢失」，而且不会报任何错 —— 属于最难发现的回归。
    """
    path = USER_OWNED[0]
    if not os.path.exists(path):
        print("[提醒] 用户模型不存在（请自行提供，脚本不会生成）：%s"
              % os.path.relpath(path, ROOT).replace("\\", "/"))
        return False
    with open(path, "r", encoding="utf-8") as handle:
        model = json.load(handle)
    ok = True
    for key, expect, why in (
        ("parent", "block/block", "display 变换来源（否则手持过大 / 物品栏是平面）"),
        ("render_type", "translucent", "贴图是 ARGB 半透明，cutout 会把 alpha 裁掉"),
    ):
        actual = model.get(key)
        hit = actual == expect
        ok = ok and hit
        print("[%s] 用户模型 %s = %r（%s）" % ("OK  " if hit else "FAIL", key, actual, why))
    print("[skip] 未写入用户模型：%s" % os.path.relpath(path, ROOT).replace("\\", "/"))
    return ok


def main():
    for path in USER_OWNED:
        if not os.path.exists(path):
            print("[提醒] 用户资产缺失（请自行提供，脚本不会生成）：%s"
                  % os.path.relpath(path, ROOT))

    model_ok = check_user_model()

    for name in FRAMES:
        # 方块状态：单变体（无属性）；指向各自的方块模型（无限版模型再继承普通版）
        write_json(os.path.join(ASSETS, "blockstates", name + ".json"), {
            "variants": {
                "": {"model": "%s:block/%s" % (MODID, name)},
            },
        })
        # 物品模型：直接复用方块模型（物品是 3D 立体）
        write_json(os.path.join(ASSETS, "models", "item", name + ".json"), {
            "parent": "%s:block/%s" % (MODID, name),
        })

    # ---- 配方 ----
    # 1) 分隔框架：铁锭 X 形（四角 + 中间行正中），产出 64 个
    write_json(os.path.join(DATA, "recipe", "separation_frame.json"), {
        "type": "minecraft:crafting_shaped",
        "pattern": ["A A", " A ", "A A"],
        "key": {"A": {"item": "minecraft:iron_ingot"}},
        "result": {"id": "%s:separation_frame" % MODID, "count": 64},
    })
    # 2) 无限分隔框架：ABA/BAB/ABA（A=铁锭，B=钻石），产出 1 个
    write_json(os.path.join(DATA, "recipe", "infinite_separation_frame.json"), {
        "type": "minecraft:crafting_shaped",
        "pattern": ["ABA", "BAB", "ABA"],
        "key": {
            "A": {"item": "minecraft:iron_ingot"},
            "B": {"item": "minecraft:diamond"},
        },
        "result": {"id": "%s:infinite_separation_frame" % MODID},
    })
    # 3) 无限分隔框架（无序）：1 个分隔框架 + 4 个钻石 → 1 个
    write_json(os.path.join(DATA, "recipe", "infinite_separation_frame_from_frame.json"), {
        "type": "minecraft:crafting_shapeless",
        "ingredients": [
            {"item": "%s:separation_frame" % MODID},
            {"item": "minecraft:diamond"},
            {"item": "minecraft:diamond"},
            {"item": "minecraft:diamond"},
            {"item": "minecraft:diamond"},
        ],
        "result": {"id": "%s:infinite_separation_frame" % MODID},
    })

    # ---- 掉落物表：只有普通版（可回收）；无限版刻意没有（不能回收） ----
    write_json(os.path.join(DATA, "loot_table", "blocks", "separation_frame.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1.0,
            "entries": [{"type": "minecraft:item", "name": "%s:separation_frame" % MODID}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })

    print("已生成 %d 个文件：" % len(written))
    for item in written:
        print("  - %s" % item)
    if not model_ok:
        print("[result] 生成完毕，但用户模型缺少契约键（见上面的 FAIL）—— 请跑 "
              "python tools/add_separation_frame_model_parent.py")
        return 1
    print("[result] 生成完毕；用户模型契约键齐全（脚本未改动用户的模型 / 贴图）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
