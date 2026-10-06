# -*- coding: utf-8 -*-
"""本轮新增语言键：① 挂起标记与原因（监视器）② RS 原版任务的横幅措辞。

背景：挂起 / 恢复从「只支持序列装配任务」扩到「RS 原版自动合成任务也用同一套」，因此需要：
  * 监视器上的挂起标记（三种原因）；
  * 挂起标记的 tooltip（本工程硬规则：GUI 必须给 tooltip，且 tooltip 要手动渲染）；
  * RS 原版任务的横幅第 1/2 行措辞（原措辞写死了「序列装配」）；
  * RS 原版任务的「掉线」「无进展」两句结论行。

同时把既有 banner.hint 里的「自动合成管理器」统一改成「自动合成监视器」（RS 界面实际叫这个名字）。

写法：按 key 的字典序**插入到正确位置**（不动既有键的相对顺序），写回后重新 json.load 校验；
中文单条 ≤ 40 字。
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

NEW_KEYS = {
    "zh_cn": {
        "gui.rs_create_compat.assembly.banner.suspended_auto": "自动合成任务已挂起",
        "gui.rs_create_compat.assembly.banner.product_auto": "自动合成 %s 个 %s",
        "gui.rs_create_compat.assembly.banner.offline_auto": "由于执行器或机器掉线",
        "gui.rs_create_compat.assembly.banner.no_progress": "长时间没有任何进展",
        "gui.rs_create_compat.assembly.monitor.suspended.offline": "已挂起：执行器离线",
        "gui.rs_create_compat.assembly.monitor.suspended.missing": "已挂起：缺少原料",
        "gui.rs_create_compat.assembly.monitor.suspended.noprog": "已挂起：长时间无进展",
    },
    "en_us": {
        "gui.rs_create_compat.assembly.banner.suspended_auto": "Auto-crafting task suspended",
        "gui.rs_create_compat.assembly.banner.product_auto": "Auto-crafting %s x %s",
        "gui.rs_create_compat.assembly.banner.offline_auto": "the executor or machine is offline",
        "gui.rs_create_compat.assembly.banner.no_progress": "no progress for a long time",
        "gui.rs_create_compat.assembly.monitor.suspended.offline": "Suspended: executor offline",
        "gui.rs_create_compat.assembly.monitor.suspended.missing": "Suspended: missing materials",
        "gui.rs_create_compat.assembly.monitor.suspended.noprog": "Suspended: no progress",
    },
}

# 本轮把「挂起标记」的 tooltip 从「会自动恢复」改成「必须点继续才恢复」（自动恢复已撤销）。
REWRITE = {
    "zh_cn": {
        "gui.rs_create_compat.assembly.banner.hint": "在自动合成监视器中决定下一步",
        "gui.rs_create_compat.assembly.monitor.suspended.tip":
            "已挂起：不再占用执行器与原料；需在自动合成监视器点「继续」才会恢复",
    },
    "en_us": {
        "gui.rs_create_compat.assembly.banner.hint": "Decide in the autocrafting monitor",
        "gui.rs_create_compat.assembly.monitor.suspended.tip":
            "Suspended: no executor occupied; click Resume in the autocrafting monitor to continue",
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
        for key, value in NEW_KEYS[lang].items():
            if key in data:
                if data[key] != value:
                    problems.append("%s 已存在键 %s 但值不同" % (name, key))
                continue
            put_sorted(data, key, value)
        for key, value in REWRITE[lang].items():
            if key not in data:
                problems.append("%s 里缺待改键 %s" % (name, key))
                continue
            if data[key] != value:
                print("  [改] %s %s: %r -> %r" % (name, key, data[key], value))
            data[key] = value
        # 中文单条 ≤ 40 字（只对中文文件约束；英文天然更长，不做这个检查）
        if lang == "zh_cn":
            too_long = [k for k, v in data.items() if k.startswith("gui.rs_create_compat.assembly.")
                        and len(v) > 40]
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
    need_zh = sorted(k for k in zh if k.startswith("gui.rs_create_compat.assembly.")
                     or k.startswith("message.rs_create_compat.assembly."))
    need_en = sorted(k for k in en if k.startswith("gui.rs_create_compat.assembly.")
                     or k.startswith("message.rs_create_compat.assembly."))
    if need_zh != need_en:
        problems.append("中英键集合不一致：%s / %s" % (need_zh, need_en))
    else:
        print("  [OK] 装配相关键 %d 条，中英一致" % len(need_zh))

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
