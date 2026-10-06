# -*- coding: utf-8 -*-
"""生成 tools/lang_frag_chamber.json（本轮「执行舱界面排版/标题 + 自动合成缺料提示」改动的语言键）。

本轮只涉及：
  * 序列装配总样板 tooltip 的「所需输入」新增一行：输入流体（按 mB 显示所需量）。
    自动合成预览的缺料口径由 RS 样板 ingredient 决定，物品项已走既有的 input_entry，
    流体项需要这一条新键（格式 "%s: %s mB"）。

按用户规则用 Python 脚本产出 json（不手写），再交给 tools/apply_lang_frag.py 幂等合并。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_chamber.json")

FRAG = {
    # ===== 新增：总样板 tooltip 的流体输入行（流体按 mB） =====
    "item.rs_create_compat.sequence_assembly_pattern.input_fluid_entry": {
        "en": "%s: %s mB",
        "zh": "%s：%s mB",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] 已写出 %s（%d 键）" % (os.path.relpath(OUT, ROOT), len(FRAG)))


if __name__ == "__main__":
    main()
