# -*- coding: utf-8 -*-
"""写 tools/lang_frag_execchain2.json（本轮：执行舱主界面上把「链指向 / 设置入口」讲清楚）。

新增键：
  * chain.suffix.directed —— 主界面状态行的「链 N 台 · 指向 X」后缀（原来只有链台数）；
  * chain.tip.link        —— tooltip 里的「本台指向：X」；
  * chain.tip.entry       —— tooltip 里的「在哪设置链指向」的最短操作路径。

用法：python tools/gen_lang_frag_execchain2.py && python tools/apply_lang_frag.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_execchain2.json")

LANG = "gui.rs_create_compat.sequence_execution_chamber."

FRAG = {
    LANG + "chain.suffix.directed": {
        "en": "(chain %s, pointing %s)",
        "zh": "（链 %s 台 · 指向%s）",
    },
    LANG + "chain.tip.link": {
        "en": "This chamber points at: %s",
        "zh": "本台指向：%s",
    },
    LANG + "chain.tip.entry": {
        "en": "To set the link: press the Config button above, then pick a direction "
              "in the Chain link picker at the bottom of that screen.",
        "zh": "设置链指向：点本界面上方的「配置」按钮，在子界面底部的「链指向」里选方向。",
    },
    # 旧键（只写「链 N 台」）已被 chain.suffix.directed 取代，顺手删除
    LANG + "chain.suffix": None,
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] tools/lang_frag_execchain2.json（%d 键）" % len(FRAG))


if __name__ == "__main__":
    main()
