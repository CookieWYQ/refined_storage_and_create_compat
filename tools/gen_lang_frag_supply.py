# -*- coding: utf-8 -*-
"""生成本轮（连线识别 + 起始原料输出限制 + 两种供应策略）的语言键片段。

为什么用脚本生成：语言键属于「有形式要求的 json」，一律由脚本产出（不手写 json）。
本脚本只产出**片段** tools/lang_frag_supply.json，再由 tools/apply_lang_frag.py
幂等合并进中英语言文件。

片段归属（按文件名排序决定合并顺序）：… bus6 < cc < … < supply < spt…，
因此 supply 里的覆盖式键会覆盖更早片段中的同名键。

本轮新增键：
  1. 输出总线锁定条 tooltip：归属执行舱名 / 预估需求 / 概率提示 / 当前供应策略；
  2. `/rs_create_compat supply <materials|target>` 指令的用法 / 设置 / 当前值反馈；
  3. 两种策略档位的可读名（message.rs_create_compat.supply.mode.*）。

用法：
    python tools/gen_lang_frag_supply.py
    python tools/apply_lang_frag.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_supply.json")

EX = "gui.rs_create_compat.exporter_executor."
CMD = "message.rs_create_compat.supply."

SUPPLY = {
    # ---- 输出总线锁定条：归属执行舱（连线确定性裁决后可一眼确认） ----
    EX + "linked": {
        "zh": "归属执行舱：%s",
        "en": "Linked chamber: %s",
    },
    # ---- 预估需求（正常情况所需量）+ 概率提示 ----
    EX + "estimated": {
        "zh": "预估需求（每 1 个最终产物）：%s",
        "en": "Estimated need (per final product): %s",
    },
    EX + "estimated.note": {
        "zh": "因概率原因，实际消耗可能会超出这个预估（系统会持续供应直到目标产物达标）。",
        "en": "Due to chance, actual consumption may exceed this estimate (supply keeps going until the target "
              "product count is reached).",
    },
    # ---- 当前供应策略（悬停锁定条时显示） ----
    EX + "strategy.materials": {
        "zh": "供应策略：只管输出原料（不看概率）",
        "en": "Supply mode: materials only (ignores chance)",
    },
    EX + "strategy.target": {
        "zh": "供应策略：直到目标产物达标（缺料自动合成补齐）",
        "en": "Supply mode: until target product count is reached (auto-crafts missing inputs)",
    },
    # ---- 指令 /rs_create_compat supply <materials|target> ----
    CMD + "usage": {
        "zh": "用法：/rs_create_compat supply <materials|target>",
        "en": "Usage: /rs_create_compat supply <materials|target>",
    },
    CMD + "set": {
        "zh": "序列执行仓原料供应策略已设为：%s",
        "en": "Sequence chamber supply strategy set to: %s",
    },
    CMD + "current": {
        "zh": "当前原料供应策略：%s",
        "en": "Current supply strategy: %s",
    },
    CMD + "mode.materials": {
        "zh": "只管输出原料（不看概率）",
        "en": "materials only (ignores chance)",
    },
    CMD + "mode.target": {
        "zh": "直到目标产物达标（默认；缺料自动合成补齐）",
        "en": "until target product count is reached (default; auto-crafts missing inputs)",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(SUPPLY, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] %s：写入 %d 键" % (os.path.relpath(OUT, ROOT), len(SUPPLY)))
    print("下一步：python tools/apply_lang_frag.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
