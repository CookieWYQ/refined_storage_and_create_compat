# -*- coding: utf-8 -*-
"""生成输出总线语言键片段 tools/lang_frag_bus3.json（本轮：类别不限量 + 包含流体输入）。

为什么用脚本生成：语言键属于"有形式要求的 json"，一律由脚本产出（不手写整份 json）。
本脚本只产出<b>片段</b>，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件。

片段语义：
  {"<键>": {"en": "...", "zh": "..."}}  → 新增 / 覆盖

本轮新增：
  * gui.rs_create_compat.exporter_executor.fluids.tip —— 流体输入类别的 tooltip 类型说明；
  * gui.rs_create_compat.exporter_executor.scroll.tip —— 更新文案（滚动条改到锁定条下方，措辞微调）。

用法：python tools/gen_lang_frag_bus3.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_bus3.json")

LANG = "gui.rs_create_compat.exporter_executor."

UPSERT = {
    # ---- 流体输入类别（id = fluid:<流体注册名>）的 tooltip 说明 ----
    LANG + "fluids.tip": {
        "zh": "导出这一类流体输入：把网络上序列执行仓单元样板对应配方所需的那种流体推给本总线面对的机器。"
              "同一种流体若被多台输出总线选中，会按轮询在它们之间均分（水量按导出批次分配，总量守恒）。",
        "en": "Export this fluid input: pushes this fluid (required by the chamber's unit patterns) "
              "into the machine this bus faces. If several exporters select the same fluid, the chamber "
              "splits it between them in round-robin (fluid is split by export batch, so the total is conserved).",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(UPSERT, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("已写入 %s（%d 键）" % (os.path.relpath(OUT, ROOT), len(UPSERT)))


if __name__ == "__main__":
    main()
