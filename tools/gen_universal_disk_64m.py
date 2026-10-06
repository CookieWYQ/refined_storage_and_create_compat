# -*- coding: utf-8 -*-
"""把通用储存磁盘的「生存可得最大一档」由 32M（33554432）翻倍为 64M（67108864），并同步配方 / 模型 / 贴图。

为什么用脚本：
  * 配方 / 模型 json 是「有形式要求」的文件（UTF-8、2 空格缩进、键顺序固定），手写易出格式问题；
  * 这一档是**改档位**（32M → 64M），物品 id 按本系列惯例内嵌容量数字，
    因此 id / 配方文件名 / 模型文件名 / 贴图名要**同步改名**，多处手改容易漏；
  * 幂等：重复执行结果一致（目标文件按固定内容重写，旧档位文件存在即删除）。

改动内容：
  1. data/rs_create_compat/recipe/universal_storage_disk_64m.json
     —— 4 个 16M 通用盘无序合成 1 个 64M（与 256k/1M/4M/16M 既有的「4 合 1」惯例一致）；
     旧 universal_storage_disk_32m.json 删除（配方 id 即文件名，必须跟着改名）。
  2. assets/rs_create_compat/models/item/universal_storage_disk_64m.json（layer0 指向同名贴图）。
  3. assets/rs_create_compat/textures/item/universal_storage_disk_64m.png
     —— 由 universal_storage_disk_32m.png 改名（同一档位改名，不是新画一档）。

用法：
    python tools/gen_universal_disk_64m.py
"""
import json
import os
import shutil
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECIPE_DIR = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat", "recipe")
MODEL_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "models", "item")
TEX_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "item")

OLD_TIER = "32m"
NEW_TIER = "64m"
PREV_TIER = "16m"
# 4 个低一档（16M）合成 1 个 64M —— 与 256k..16M 各档完全同构
RECIPE = {
    "type": "minecraft:crafting_shapeless",
    "ingredients": [
        {"item": "rs_create_compat:universal_storage_disk_" + PREV_TIER}
        for _ in range(4)
    ],
    "result": {
        "id": "rs_create_compat:universal_storage_disk_" + NEW_TIER
    },
}
MODEL = {
    "parent": "minecraft:item/generated",
    "textures": {
        "layer0": "rs_create_compat:item/universal_storage_disk_" + NEW_TIER
    },
}


def write_json(path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def drop(path, label):
    if os.path.exists(path):
        os.remove(path)
        print("[删除] %s（%s）" % (os.path.relpath(path, ROOT), label))


def main():
    # 1) 配方
    write_json(os.path.join(RECIPE_DIR, "universal_storage_disk_%s.json" % NEW_TIER), RECIPE)
    drop(os.path.join(RECIPE_DIR, "universal_storage_disk_%s.json" % OLD_TIER), "旧档位配方")

    # 2) 模型
    write_json(os.path.join(MODEL_DIR, "universal_storage_disk_%s.json" % NEW_TIER), MODEL)
    drop(os.path.join(MODEL_DIR, "universal_storage_disk_%s.json" % OLD_TIER), "旧档位模型")

    # 3) 贴图（同档改名；目标已存在则不覆盖，保证幂等）
    old_tex = os.path.join(TEX_DIR, "universal_storage_disk_%s.png" % OLD_TIER)
    new_tex = os.path.join(TEX_DIR, "universal_storage_disk_%s.png" % NEW_TIER)
    if os.path.exists(old_tex):
        shutil.copyfile(old_tex, new_tex)
        os.remove(old_tex)
        print("[改名] 贴图 -> %s" % os.path.relpath(new_tex, ROOT))
    elif os.path.exists(new_tex):
        print("[跳过] 贴图已就位: %s" % os.path.relpath(new_tex, ROOT))
    else:
        raise SystemExit("[X] 贴图缺失: %s" % old_tex)

    # 4) 复核：新档三个文件都在，旧档三个文件都没了
    problems = []
    for path in (
        os.path.join(RECIPE_DIR, "universal_storage_disk_%s.json" % NEW_TIER),
        os.path.join(MODEL_DIR, "universal_storage_disk_%s.json" % NEW_TIER),
        new_tex,
    ):
        if not os.path.exists(path):
            problems.append("缺少 " + os.path.relpath(path, ROOT))
    for path in (
        os.path.join(RECIPE_DIR, "universal_storage_disk_%s.json" % OLD_TIER),
        os.path.join(MODEL_DIR, "universal_storage_disk_%s.json" % OLD_TIER),
        old_tex,
    ):
        if os.path.exists(path):
            problems.append("残留 " + os.path.relpath(path, ROOT))
    if problems:
        for problem in problems:
            print("[X] %s" % problem)
        return 1
    print("[OK] 64M 档资源就位（配方 4×%s / 模型 / 贴图），幂等可重复执行" % PREV_TIER)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
