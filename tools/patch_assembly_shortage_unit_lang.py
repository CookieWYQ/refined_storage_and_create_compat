# -*- coding: utf-8 -*-
"""缺料横幅「补单位」：条目键按「物品 / 流体」分成两条（各自把单位写进译文），中英成对。

背景（用户本轮要求）：「资源不足提示缺少单位」——横幅 / 提示要带数量与单位，
例如「缺少材料：黑曜石粉 ×5 个」；流体带单位（mB）。

为什么分成两条键而不是「entry + 单位参数」：横幅由服务端用
{@code CompletionBannerPayload#localized(key, args...)} 构造、客户端把参数原样塞进
{@code Component.translatable}，参数只能是纯字符串（不能嵌套另一条语言键），
而服务端的 Language 在专服上只有 en_us，因此单位只能写在译文里。

本脚本：删掉旧的单条 entry 键，写入 entry.item / entry.fluid（幂等）。
写回后重新 json.load 校验；中文单条 ≤ 40 字；中英键集合完全一致；值不允许首尾空白。
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

PREFIX = "gui.rs_create_compat.assembly.shortage."
STALE_KEYS = ["gui.rs_create_compat.assembly.shortage.entry",
              "gui.rs_create_compat.assembly.shortage.unit.item",
              "gui.rs_create_compat.assembly.shortage.unit.fluid"]

NEW_KEYS = {
    "zh_cn": {
        "gui.rs_create_compat.assembly.shortage.title": "缺少材料",
        "gui.rs_create_compat.assembly.shortage.entry.item": "%1$s ×%2$s 个",
        "gui.rs_create_compat.assembly.shortage.entry.fluid": "%1$s ×%2$s mB",
        "gui.rs_create_compat.assembly.shortage.more": "其他%1$s种",
    },
    "en_us": {
        "gui.rs_create_compat.assembly.shortage.title": "Missing material",
        "gui.rs_create_compat.assembly.shortage.entry.item": "%1$s x%2$s pcs",
        "gui.rs_create_compat.assembly.shortage.entry.fluid": "%1$s x%2$s mB",
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
    problems = []
    for name in ("zh_cn.json", "en_us.json"):
        lang = name[:-5]
        path, data = load(name)
        for stale in STALE_KEYS:
            data.pop(stale, None)
        for key, value in NEW_KEYS[lang].items():
            put_sorted(data, key, value)
        if lang == "zh_cn":
            too_long = [k for k, v in data.items() if k.startswith(PREFIX) and len(v) > 40]
            if too_long:
                problems.append("%s 中文单条超过 40 字：%s" % (name, too_long))
        if problems:
            continue
        dump(path, data)
        _p, again = load(name)
        assert again == data, "%s 写回后重新解析不一致" % name
        print("  [OK] %s 写入 %d 个键（并清掉旧键）后重新 json.load 校验通过" % (name, len(NEW_KEYS[lang])))

    zh = load("zh_cn.json")[1]
    en = load("en_us.json")[1]
    need_zh = sorted(k for k in zh if k.startswith(PREFIX))
    need_en = sorted(k for k in en if k.startswith(PREFIX))
    if need_zh != need_en:
        problems.append("中英键集合不一致：%s / %s" % (need_zh, need_en))
    else:
        print("  [OK] 缺料族键 %d 条，中英一致：%s" % (len(need_zh), need_zh))
    for name, table in (("zh_cn.json", zh), ("en_us.json", en)):
        for key in need_zh:
            if table[key] != table[key].strip():
                problems.append("%s 键 %s 的值首尾有空白" % (name, key))
    # 单位必须写在译文里（用户要求「提示缺少单位」）
    for key, unit in (("entry.item", "个"), ("entry.fluid", "mB")):
        if unit not in zh[PREFIX + key]:
            problems.append("zh %s 译文缺少单位 %s" % (key, unit))
    if "pcs" not in en[PREFIX + "entry.item"] or "mB" not in en[PREFIX + "entry.fluid"]:
        problems.append("en 条目译文缺少单位（pcs / mB）")

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
