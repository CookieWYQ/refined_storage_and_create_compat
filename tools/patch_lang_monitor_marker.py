# -*- coding: utf-8 -*-
"""监视器「挂起原因挪到标题行 + 点击在途反馈」的语言键增删（用脚本改，不手改 JSON）。

本轮只新增一个键：
  `gui.rs_create_compat.assembly.monitor.pending` —— 点下按钮后到服务端答复之间，
  按钮 tooltip 追加的一行「正在处理…」（按钮同时会变灰，这是即时反馈的一半）。

既有键一律不动（挂起原因那三句 `monitor.suspended.*` 与 tooltip 复用原键 —— 文案没变，
只是换了绘制位置）。写入按字典序插入、写回后重新 json.load 校验；中文单条 ≤ 40 字。
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
        "gui.rs_create_compat.assembly.monitor.pending": "正在处理…",
    },
    "en_us": {
        "gui.rs_create_compat.assembly.monitor.pending": "Working…",
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
            if key in data and data[key] != value:
                problems.append("%s 已存在键 %s 但值不同" % (name, key))
                continue
            put_sorted(data, key, value)
        if lang == "zh_cn":
            too_long = [k for k, v in data.items()
                        if k.startswith("gui.rs_create_compat.assembly.") and len(v) > 40]
            if too_long:
                problems.append("%s 中文单条超过 40 字：%s" % (name, too_long))
        if problems:
            continue
        dump(path, data)
        _p, again = load(name)
        assert again == data, "%s 写回后重新解析不一致" % name
        print("  [OK] %s 写入 %d 个键并重新 json.load 校验通过" % (name, len(NEW_KEYS[lang])))

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

    for key in NEW_KEYS["zh_cn"]:
        if zh.get(key) != NEW_KEYS["zh_cn"][key] or en.get(key) != NEW_KEYS["en_us"][key]:
            problems.append("键 %s 的落盘值与预期不符" % key)

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
