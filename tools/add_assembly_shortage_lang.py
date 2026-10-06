# -*- coding: utf-8 -*-
"""本轮新增语言键：序列执行仓的「缺料提示」（行动栏）。

背景（用户原话）：「它又有那个什么概率嘛，所以说它偶尔会直接停下来。这种时候我不说了吗：
缺少材料也需要弹弹窗。但是你现在并没有弹弹窗。」
=> 因缺料而停（等待原料 / no_stock / net_short / storage_full）时必须给玩家一条明确提示，
   并说清缺哪种材料、缺多少。

写法：按 key 的字典序**插入到正确位置**（不动既有键的相对顺序），写回后重新 json.load 校验；
中文单条 ≤ 40 字；中英成对（脚本末尾会校验两侧键集合一致）。
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

SHORTAGE_PREFIX = "gui.rs_create_compat.assembly.shortage."

# 【已废弃 / 只读】本脚本是「缺料横幅」最初那一版（单条 entry，无单位）。
# 后继轮次已把它改成「物品 / 流体各一条条目键，单位写在译文里」：
#   tools/patch_assembly_shortage_unit_lang.py（请改跑那一个）。
# 为防止本脚本被误重跑而把旧键写回去（那样会同时存在于语言文件里、并且丢掉单位），
# 这里直接短路退出：本脚本不再修改任何文件。
DEPRECATED = True

NEW_KEYS = {
    "zh_cn": {
        "gui.rs_create_compat.assembly.shortage.title": "缺少材料",
        "gui.rs_create_compat.assembly.shortage.entry": "%1$s×%2$s",
        "gui.rs_create_compat.assembly.shortage.more": "其他%1$s种",
    },
    "en_us": {
        "gui.rs_create_compat.assembly.shortage.title": "Missing material",
        "gui.rs_create_compat.assembly.shortage.entry": "%1$s x%2$s",
        "gui.rs_create_compat.assembly.shortage.more": "%1$s more",
    },
}


def load(name):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, encoding="utf-8") as handle:
        return path, json.load(handle)


def dump(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def put_sorted(data, key, value):
    """按字典序插入（已存在则原地改值），不改动其它键的相对顺序。"""
    if key in data:
        data[key] = value
        return
    items = list(data.items())
    for index, (existing, _value) in enumerate(items):
        if existing > key:
            items.insert(index, (key, value))
            break
    else:
        items.append((key, value))
    data.clear()
    data.update(items)


def main():
    if DEPRECATED:
        print("[SKIP] 本脚本已被 tools/patch_assembly_shortage_unit_lang.py 取代（缺料条目键按物品/流体拆分并带单位），"
              "不改动任何语言文件。")
        return 0
    problems = []
    for name in ("zh_cn.json", "en_us.json"):
        lang = name[:-5]
        path, data = load(name)
        # 先清掉本族的全部旧键（脚本可重复执行；旧版本用过的 header / separator / footer 一并移除）
        for stale in [k for k in data if k.startswith(SHORTAGE_PREFIX)]:
            del data[stale]
        for key, value in NEW_KEYS[lang].items():
            put_sorted(data, key, value)
        if lang == "zh_cn":
            too_long = [k for k, v in data.items()
                        if k.startswith(SHORTAGE_PREFIX) and len(v) > 40]
            if too_long:
                problems.append("%s 中文单条超过 40 字：%s" % (name, too_long))
        if problems:
            continue
        dump(path, data)
        _p, again = load(name)
        assert again == data, "%s 写回后重新解析不一致" % name
        print("  [OK] %s 写入 %d 个新键并重新 json.load 校验通过" % (name, len(NEW_KEYS[lang])))

    zh = load("zh_cn.json")[1]
    en = load("en_us.json")[1]
    need_zh = sorted(k for k in zh if k.startswith(SHORTAGE_PREFIX))
    need_en = sorted(k for k in en if k.startswith(SHORTAGE_PREFIX))
    if need_zh != need_en or len(need_zh) != len(NEW_KEYS["zh_cn"]):
        problems.append("中英键集合不一致 / 数量不符：%s / %s" % (need_zh, need_en))
    else:
        print("  [OK] 缺料提示相关键 %d 条，中英一致" % len(need_zh))
    # 语言键值不允许首尾空白（见 tools/verify_lang_simplify.py 的硬规则）
    for name, table in (("zh_cn.json", zh), ("en_us.json", en)):
        for key in need_zh:
            if table[key] != table[key].strip():
                problems.append("%s 键 %s 的值首尾有空白" % (name, key))

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
