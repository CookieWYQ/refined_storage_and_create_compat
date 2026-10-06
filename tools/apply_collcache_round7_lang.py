# -*- coding: utf-8 -*-
"""第 7 轮：归流缓存仓（collection_cache）的语言键增删改（B1 / B2 / B3）。

规范（用户硬规则）：语言键的增删改一律走 python 脚本，保持既有行序（本文件按键名排序）
与 2 空格缩进，并在写回后立刻 json.load 回读校验。

  B3 删「讲解型」说明文本（只解释界面元素是什么）：
       gui.rs_create_compat.collection_cache.region_marker.tip（「匹配区：ghost 标记（…）」）
       gui.rs_create_compat.collection_cache.region_cache.tip（「缓存区：真实存储，…」）
     并把 block.rs_create_compat.collection_cache.help 里教「用 ghost 标记指定收集对象」的半句去掉
     （保留「接入网络后吸取…、装不下的暂存并持续回流」这类基础引导）。
  B1 增 blocked.mark =「被阻塞：目标已满 / 无法接收」（与槽位上那圈红环同一条信息）。
  B2 增两个按钮文案：吸取所有物品 / 反转匹配（含三态与优先级说明）。

用法: python tools/apply_collcache_round7_lang.py
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
CC = "gui.rs_create_compat.collection_cache."

DELETE = [
    CC + "region_marker.tip",
    CC + "region_cache.tip",
]

UPDATE = {
    "block.rs_create_compat.collection_cache.help": {
        "zh_cn": "接入 RS 网络后吸取周围掉落物与流体/气体；装不下的暂存并持续回流。",
        "en_us": "Absorbs nearby drops, fluids and gases once networked; surplus is buffered"
                 " and retried into the network.",
    },
}

ADD = {
    "zh_cn": {
        CC + "blocked.mark": "被阻塞：目标已满 / 无法接收",
        CC + "region_marker.disabled": "%s（全收中已失效）",
        CC + "collect_all.label": "全收",
        CC + "collect_all.tip": "吸取所有物品",
        CC + "collect_all.on": "已开启：匹配区失效，半径内物品全收",
        CC + "collect_all.off": "已关闭：按匹配区过滤",
        CC + "collect_all.priority": "与「反转匹配」同时开启时本开关优先（匹配区整体失效）",
        CC + "invert.label": "反转",
        CC + "invert.tip": "反转匹配：匹配区由白名单变黑名单",
        CC + "invert.on": "已开启：只收没被标记的资源",
        CC + "invert.off": "已关闭：只收已被标记的资源",
        CC + "invert.disabled.tip": "被「吸取所有物品」禁用：匹配区已失效，关闭全收后生效",
        CC + "invert.enabled.tip": "当前生效：匹配区按反转后的规则过滤",
    },
    "en_us": {
        CC + "blocked.mark": "Blocked: target full / cannot accept",
        CC + "region_marker.disabled": "%s (inactive: collect-all on)",
        CC + "collect_all.label": "All",
        CC + "collect_all.tip": "Collect all items",
        CC + "collect_all.on": "ON: markers inactive, every item in range is collected",
        CC + "collect_all.off": "OFF: filtered by the markers",
        CC + "collect_all.priority": "Takes priority over 'Invert match' (markers stop being used)",
        CC + "invert.label": "Invert",
        CC + "invert.tip": "Invert match: markers become a blacklist",
        CC + "invert.on": "ON: only unmarked resources are collected",
        CC + "invert.off": "OFF: only marked resources are collected",
        CC + "invert.disabled.tip": "Disabled by 'Collect all items': markers are inactive",
        CC + "invert.enabled.tip": "Active: markers are filtered with inversion",
    },
}

ZH_MAX = 40


def write_json(path, data):
    with io.open(path, "r", encoding="utf-8") as handle:
        before = json.load(handle)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    with io.open(path, "r", encoding="utf-8") as handle:
        json.load(handle)
    removed = sorted(set(before) - set(data))
    added = sorted(set(data) - set(before))
    print("[OK  ] %s: %d -> %d keys (del %d, add %d)"
          % (os.path.relpath(path, ROOT).replace("\\", "/"), len(before), len(data),
             len(removed), len(added)))
    for one in added:
        print("       + %s" % one)


def main():
    problems = []
    for locale in ("zh_cn", "en_us"):
        path = os.path.join(LANG_DIR, "%s.json" % locale)
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        for key in DELETE:
            if key not in data:
                print("[skip] %s already lacks %s" % (locale, key))
            data.pop(key, None)
        for key, texts in UPDATE.items():
            if key not in data:
                problems.append("%s missing key %s" % (locale, key))
            else:
                data[key] = texts[locale]
        for key, value in ADD[locale].items():
            if locale == "zh_cn" and len(value) > ZH_MAX:
                problems.append("zh key %s len %d > %d" % (key, len(value), ZH_MAX))
            data[key] = value
        write_json(path, dict(sorted(data.items())))

    for locale in ("zh_cn", "en_us"):
        with io.open(os.path.join(LANG_DIR, "%s.json" % locale), "r", encoding="utf-8") as handle:
            data = json.load(handle)
        for key in DELETE:
            if key in data:
                problems.append("%s still has %s" % (locale, key))
        for key, value in ADD[locale].items():
            if data.get(key) != value:
                problems.append("%s / %s written incorrectly" % (locale, key))
        if data.get(CC + "region_marker.disabled", "").count("%s") != 1:
            problems.append("%s region_marker.disabled placeholder != 1" % locale)
    print("=" * 60)
    for problem in problems:
        print("[X] %s" % problem)
    print("[result] collection_cache lang: %s"
          % ("%d problem(s)" % len(problems) if problems else "done"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
