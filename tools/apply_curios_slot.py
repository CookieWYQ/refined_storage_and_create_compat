# -*- coding: utf-8 -*-
"""把三件「高级远程多功能终端」登记进**前置模组**的 Curios 饰品槽标签 —— 本模组**不再**注册自己的饰品槽。

背景（用户最终要求）
--------------------
前置模组 **Refined Storage - Curios Integration**（modId `refinedstorage_curios_integration`）本身已经有
一整套饰品槽（slots / entities / 槽位背景精灵 / 语言键），本模组此前**额外**注册了一套自己的槽
（`rs_create_compat_curios_integration`）；用户要求删除：「前置本身已经有了，我们没必要再搞一个」。
它不写一行注册代码，槽位完全由数据包 JSON 声明（本脚本按同一套约定处理）：

  data/<ns>/curios/slots/<ns>.json        {"order": 5, "size": 2, "icon": "<ns>:slot/curios"}
  data/<ns>/curios/entities/<ns>.json     {"entities": ["player"], "slots": ["<ns>"]}
  data/curios/tags/item/<ns>.json         {"replace": false, "values": [ ...可放入该槽的物品... ]}

本脚本做两件事，**幂等**（可反复执行，第二次执行应输出「已不存在 / 已是目标值」）：

  ① 负向清理 —— 删掉本模组自己的那套槽位定义（现已不属于本模组）：
       data/rs_create_compat/curios/slots/rs_create_compat_curios_integration.json
       data/rs_create_compat/curios/entities/rs_create_compat_curios_integration.json
       data/curios/tags/item/rs_create_compat_curios_integration.json
       语言键 curios.identifier.rs_create_compat_curios_integration（en_us / zh_cn）
  ② 正向保留 —— 保证三件终端仍在**前置槽位**的物品标签里（唯一保留的一份）：
       data/curios/tags/item/refinedstorage_curios_integration.json
     `replace: false` 只做追加 / 合并：不动前置自己的 wireless_grid 等条目。

为什么必须保留 ②：物品标签是「终端能不能放进饰品槽」的唯一依据；删掉它终端就放不进任何饰品槽。
为什么槽位本体必须由前置提供：Curios 只把 `assets/<ns>/textures/slot/**` stitch 进方块图集
（见 `assets/minecraft/atlases/blocks.json` 的 `{"type":"directory","source":"slot","prefix":"slot/"}`），
本模组自绘的 `item/` 贴图根本进不了图集，槽位背景只会渲染成缺失精灵。

用法：python tools/apply_curios_slot.py
"""

import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
DATA = os.path.join(RES, "data")
LANG_DIR = os.path.join(RES, "assets", "rs_create_compat", "lang")

MODID = "rs_create_compat"
REMOVED_SLOT_ID = "rs_create_compat_curios_integration"  # 已删除：本模组自己的槽位 id
REFERENCE_SLOT_ID = "refinedstorage_curios_integration"  # 保留：前置模组提供的槽位 id（= 它的 modId）

# 终端物品：必须能放进饰品槽（放进前置槽位标签）
TERMINALS = [
    "rs_create_compat:advanced_remote_terminal",
    "rs_create_compat:advanced_remote_terminal_charged",
    "rs_create_compat:creative_advanced_remote_terminal",
]

# ① 待删除的本模组自有槽位资源（相对 resources 目录）
REMOVED_FILES = [
    os.path.join(DATA, MODID, "curios", "slots", "%s.json" % REMOVED_SLOT_ID),
    os.path.join(DATA, MODID, "curios", "entities", "%s.json" % REMOVED_SLOT_ID),
    os.path.join(DATA, "curios", "tags", "item", "%s.json" % REMOVED_SLOT_ID),
]
# 删空后可一并移除的目录（仅在确为空时移除，避免误删将来新增的资源）
REMOVED_DIRS = [
    os.path.join(DATA, MODID, "curios", "slots"),
    os.path.join(DATA, MODID, "curios", "entities"),
    os.path.join(DATA, MODID, "curios"),
]
REMOVED_LANG_KEY = "curios.identifier.%s" % REMOVED_SLOT_ID
LANG_FILES = ("en_us.json", "zh_cn.json")

# ② 唯一保留的一份标签：把终端登记进前置槽位
REFERENCE_TAG = os.path.join(DATA, "curios", "tags", "item", "%s.json" % REFERENCE_SLOT_ID)


def write_json(path, payload):
    """统一写出：UTF-8 / LF / 2 空格缩进 / 结尾换行（与工程其它 JSON 一致）。"""
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def drop_removed_files():
    removed = 0
    for path in REMOVED_FILES:
        if os.path.exists(path):
            os.remove(path)
            removed += 1
            print("  删除 %s" % os.path.relpath(path, ROOT))
        else:
            print("  已不存在（幂等）：%s" % os.path.relpath(path, ROOT))
    for path in REMOVED_DIRS:
        if os.path.isdir(path) and not os.listdir(path):
            os.rmdir(path)
            print("  移除空目录 %s" % os.path.relpath(path, ROOT))
    return removed


def drop_removed_lang_key():
    """按既有键序删除语言键（不重排），仅在确实变了才写回 → 幂等且 diff 最小。"""
    removed = 0
    for name in LANG_FILES:
        path = os.path.join(LANG_DIR, name)
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle, object_pairs_hook=dict)
        if data.pop(REMOVED_LANG_KEY, None) is not None:
            write_json(path, data)
            removed += 1
            print("  语言键 %s：删除 %s" % (name, REMOVED_LANG_KEY))
        else:
            print("  语言键 %s：已不存在（幂等）" % name)
        with open(path, "r", encoding="utf-8") as handle:
            json.load(handle)  # 写回后必须仍是合法 JSON
    return removed


def ensure_reference_tag():
    """保证前置槽位标签里含三件终端（replace:false，追加不覆盖前置自己的条目）。"""
    if not os.path.exists(REFERENCE_TAG):
        raise SystemExit("缺少前置槽位标签文件：%s" % REFERENCE_TAG)
    with open(REFERENCE_TAG, "r", encoding="utf-8") as handle:
        tag = json.load(handle, object_pairs_hook=dict)
    values = tag.setdefault("values", [])
    added = [entry for entry in TERMINALS if entry not in values]
    if added:
        values.extend(added)
        write_json(REFERENCE_TAG, tag)
    with open(REFERENCE_TAG, "r", encoding="utf-8") as handle:
        json.load(handle)
    print("  前置槽位标签 %s：%s" % (
        os.path.relpath(REFERENCE_TAG, ROOT),
        ("追加 %s" % ", ".join(added)) if added else "三件终端已在其中（幂等）"))
    return added


def main():
    print("① 清理本模组自己的 Curios 槽位（slots / entities / 自有 tag / 语言键）")
    removed = drop_removed_files()
    removed += drop_removed_lang_key()
    print("② 保留：三件终端登记进前置槽位 %s" % REFERENCE_SLOT_ID)
    ensure_reference_tag()
    print("完成（本次实际删除 %d 处；第二次执行应为 0）。" % removed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
