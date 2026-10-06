# -*- coding: utf-8 -*-
"""追加「按键分层 tooltip」所需语言键（中英成对、中文 ≤ 40 字）。

键位（前缀 `gui.rs_create_compat.tooltip.` 在 RsccTooltipLayers 里是字符串字面量，
因此 audit_lang_keys 的动态前缀规则会把本前缀下的全部键视为「已引用」）：
  * hold       —— 提示行正文（「按住 %s 查看更多」，%s = 彩色按键名）
  * layer.*    —— 三个层面的按键名（Shift / Ctrl / Alt）
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
P = "gui.rs_create_compat.tooltip."

ADD = {
    # 提示行：灰正文 + 彩色按键名（参考机械动力 Hold [Shift] for … 的观感）
    P + "hold": ("按住 %s 查看更多", "Hold %s for more"),
    # 三个层面的按键名（不改语言时也保持英文键位名，方便玩家对应键盘）
    P + "layer.shift": ("Shift", "Shift"),
    P + "layer.ctrl": ("Ctrl", "Ctrl"),
    P + "layer.alt": ("Alt", "Alt"),
}


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def dump(name, data):
    with io.open(os.path.join(LANG_DIR, name), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    zh, en = load("zh_cn.json"), load("en_us.json")
    problems = []
    for key, (zh_value, en_value) in ADD.items():
        zh[key] = zh_value
        en[key] = en_value
        if len(zh_value) > 40:
            problems.append("中文超 40 字：%s" % key)
        if zh_value.count("%s") != en_value.count("%s"):
            problems.append("占位符数量不一致：%s" % key)
    if set(zh) != set(en):
        problems.append("中英键集合不一致")
    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    dump("zh_cn.json", zh)
    dump("en_us.json", en)
    print("已写入 %d 条按键分层语言键：" % len(ADD))
    for key in ADD:
        print("  + %s = %s" % (key, zh[key]))
    print("问题总数: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
