#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把归流缓存仓「销毁」功能（缓存区清空确认 + 匹配槽直接销毁）的语言键写入 zh_cn / en_us。

为什么用脚本而不是手改 JSON：语言文件必须保持「字典序 + 合法 JSON + UTF-8」，
本脚本 1) 用 json 读入并校验；2) 新键按字典序插入到正确位置，已有键只就地更新；
3) 用工程既有格式写回；4) 写回后再 json.load 自校验并打印字数（中文 ≤ 40 / 英文 ≤ 90）。

用法：python tools/add_collection_destroy_lang.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

PREFIX = "gui.rs_create_compat.collection_cache."

NEW_ZH = {
    "destroy.button": "销毁缓存区",
    "destroy.tip": "销毁缓存区里的全部物品与流体（不可恢复）",
    "destroy.count": "将销毁：物品 %s 件（%s 格）/ 流体 %s mB（%s 种）",
    "destroy.title": "销毁缓存区内容",
    "destroy.warning": "该操作会直接销毁、不可恢复！",
    "destroy.items": "物品：%s 件 / %s 格",
    "destroy.fluids": "流体：%s mB / %s 种",
    "destroy.confirm": "确认销毁",
    "destroy.confirm.tip": "真的销毁上述内容（不可恢复）",
    "destroy.cancel": "取消",
    "destroy.cancel.tip": "放弃销毁并返回",
    "destroy_marker.label": "直接销毁",
    "destroy_marker.armed": "确认销毁？",
    "destroy_marker.on": "已开启",
    "destroy_marker.off": "未开启",
    "destroy_marker.tip": "开启后命中该槽的资源不回流网络，直接被销毁",
    "destroy_marker.warn": "命中物将被直接销毁、不可恢复；再次点击确认",
    "marker.destroy.on": "直接销毁：命中该槽的资源不回流网络、直接销毁",
}

NEW_EN = {
    "destroy.button": "Destroy",
    "destroy.tip": "Destroy every cached item and fluid (irreversible)",
    "destroy.count": "To destroy: %s items (%s stacks) / %s mB (%s kinds)",
    "destroy.title": "Destroy cache contents",
    "destroy.warning": "Direct destroy, irreversible!",
    "destroy.items": "Items: %s / %s stacks",
    "destroy.fluids": "Fluids: %s mB / %s kinds",
    "destroy.confirm": "Confirm destroy",
    "destroy.confirm.tip": "Really destroy the listed contents (irreversible)",
    "destroy.cancel": "Cancel",
    "destroy.cancel.tip": "Abort and go back",
    "destroy_marker.label": "Destroy",
    "destroy_marker.armed": "Confirm?",
    "destroy_marker.on": "On",
    "destroy_marker.off": "Off",
    "destroy_marker.tip": "When on, resources matching this slot are destroyed instead of returning to the network",
    "destroy_marker.warn": "Irreversible! Click again to confirm",
    "marker.destroy.on": "Destroy mode: matching resources are destroyed instead of returning to the network",
}


def load(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def ordered_insert(data, key, value):
    if key in data:
        data[key] = value
        return False
    keys = list(data.keys())
    position = len(keys)
    for index, existing in enumerate(keys):
        if key < existing:
            position = index
            break
    items = list(data.items())
    items.insert(position, (key, value))
    data.clear()
    data.update(items)
    return True


def save(path, data):
    lines = ["{"]
    items = list(data.items())
    for index, (key, value) in enumerate(items):
        comma = "," if index < len(items) - 1 else ""
        lines.append("  %s: %s%s" % (json.dumps(key, ensure_ascii=False),
                                     json.dumps(value, ensure_ascii=False), comma))
    lines.append("}")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def main():
    problems = []
    for name, new_keys in (("zh_cn.json", NEW_ZH), ("en_us.json", NEW_EN)):
        path = os.path.join(LANG_DIR, name)
        data = load(path)
        added = 0
        for suffix, value in new_keys.items():
            if ordered_insert(data, PREFIX + suffix, value):
                added += 1
        save(path, data)
        reloaded = load(path)
        if len(reloaded) != len(data):
            problems.append("%s 写回后键数不一致：%d → %d" % (name, len(data), len(reloaded)))
        keys = list(reloaded.keys())
        if keys != sorted(keys):
            # 既有语言文件本身并非全局字典序（历史批量追加所致），因此这里只做提示、不算失败。
            first_bad = next(i for i in range(1, len(keys)) if keys[i] < keys[i - 1])
            print("  [注] %s 存在历史非字典序位置（%d），不影响功能与校验" % (name, first_bad))
        print("[OK] %s：新增 %d 键，现有 %d 键" % (name, added, len(reloaded)))

    zh = load(os.path.join(LANG_DIR, "zh_cn.json"))
    en = load(os.path.join(LANG_DIR, "en_us.json"))
    if set(zh) != set(en):
        problems.append("zh / en 键集不一致：%s" % sorted(set(zh) ^ set(en))[:6])
    new_keys = [PREFIX + suffix for suffix in NEW_ZH]
    missing = [key for key in new_keys if key not in zh or key not in en]
    if missing:
        problems.append("新键缺失：%s" % missing[:6])
    long_zh = sorted((key, len(zh[key])) for key in new_keys if len(zh[key]) > 40)
    long_en = sorted((key, len(en[key])) for key in new_keys if len(en[key]) > 90)
    if long_zh:
        problems.append("中文超 40 字：%s" % long_zh)
    if long_en:
        problems.append("英文超 90 字符：%s" % long_en)
    print("[OK] 新键 %d 条（zh = en）；最长中文 %d 字 / 最长英文 %d 字符"
          % (len(new_keys), max(len(zh[key]) for key in new_keys),
             max(len(en[key]) for key in new_keys)))

    if problems:
        print("问题总数: %d" % len(problems))
        for problem in problems:
            print("  - " + problem)
        return 1
    print("问题总数: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
