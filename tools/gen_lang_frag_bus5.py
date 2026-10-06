# -*- coding: utf-8 -*-
"""生成本轮（输出总线「取货源 = 执行舱内部存储」流向语义修正）的语言键片段。

为什么用脚本生成：语言键属于「有形式要求的 json」，一律由脚本产出（不手写整份 json）。
本脚本只产出 / 修补**片段**，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件。

片段归属（按文件名排序决定合并顺序，后面的覆盖前面的）：
  * 新增键 + 覆盖键 一律 → tools/lang_frag_bus5.json（本轮新片段；
    排序为 bus < bus3 < bus4 < bus5 < cc < cc3 < misc，因此它能覆盖 bus / bus3 里的旧文案）。

本轮内容：
  1. `source.tip`（新增）：锁定条 tooltip 多一行「取料从哪来」；
  2. `inputs.tip` / `intermediates.tip`（覆盖，原属 bus / bus3）：把「从网络上取」改成「从执行舱内部存储取」。

注意：`locked.tip` 归属 tools/lang_frag_misc.json，而 misc 排在 bus5 **之后**（会覆盖本片段），
若要改它必须改 tools/gen_lang_frag_misc.py，本脚本不碰。

用法：
    python tools/gen_lang_frag_bus5.py
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
OUT = os.path.join(ROOT, "tools", "lang_frag_bus5.json")

EX = "gui.rs_create_compat.exporter_executor."

BUS5 = {
    # ---- 新增：锁定条的「取料从哪来」一行（文案要短）----
    EX + "source.tip": {
        "zh": "取料：先把原料搬进执行仓内部存储，再从执行舱按勾选类别推出。",
        "en": "Source: the chamber's own storage (materials are moved in first, then pushed out).",
    },
    # ---- 覆盖：类别类型说明（原来说的是「把网络上的…推给机器」）----
    EX + "inputs.tip": {
        "zh": "导出这一类输入性产物：从执行仓内部存储里，把单元样板对应的这种原料推给本总线面对的机器。"
              "同一种原料若被多台输出总线选中，会按轮询在它们之间均分。",
        "en": "Exports this input category: takes that material from the chamber's own storage and pushes it "
              "to the machine this bus faces. If multiple buses select the same category, they share it "
              "round-robin.",
    },
    EX + "intermediates.tip": {
        "zh": "导出「中间产物」：从执行仓内部存储里，把序列装配的过渡件（带进度组件的未完成件）"
              "推给本总线面对的机器。同一种过渡件若被多台输出总线选中，会按轮询在它们之间均分。",
        "en": "Exports the intermediate category: takes sequenced-assembly intermediates (unfinished items "
              "with progress components) from the chamber's own storage and pushes them to the machine this "
              "bus faces. If multiple buses select the same category, they share it round-robin.",
    },
    EX + "fluids.tip": {
        "zh": "导出这一类流体输入：从执行仓内部存储里，把单元样板对应配方所需的那种流体推给本总线面对的机器。"
              "同一种流体若被多台输出总线选中，会按轮询在它们之间均分（水量按导出批次分配，总量守恒）。",
        "en": "Exports this fluid input category: takes that fluid from the chamber's own storage and pushes it "
              "to the machine this bus faces. If multiple buses select the same category, they share it "
              "round-robin (batch-based, amount is conserved).",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(BUS5, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] %s：写入 %d 键" % (os.path.relpath(OUT, ROOT), len(BUS5)))
    print("下一步：python tools/apply_lang_frag.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
