# -*- coding: utf-8 -*-
"""生成本轮（输出总线「类别归属 / 按需备料」修正）的语言键片段。

为什么用脚本生成：语言键属于「有形式要求的 json」，一律由脚本产出（不手写整份 json）。
本脚本只产出 / 修补**片段**，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件。

片段归属（按文件名排序决定合并顺序，后面的覆盖前面的）：
  * bus < bus3 < bus4 < bus5 < bus6 < cc < cc3 < misc，因此 bus6 能覆盖 bus / bus5 里的旧文案。

本轮内容：
  1. `inputs.tip`（覆盖）：把「导出这一类输入性产物」改成「导出这一类输入原料」——
     类别名一律显示真实物品名，类型说明用「输入原料」这一准确说法；
  2. `intermediates.tip`（覆盖）：说明中间产物那一格显示的是**过渡件物品自己**的名字 / 图标
     （不再用「中间产物」这类类别标签当名字）；
  3. `intermediate.label`（删除）：类别名改为真实物品名后，这个语言键不再被任何代码引用。

用法：
    python tools/gen_lang_frag_bus6.py
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
OUT = os.path.join(ROOT, "tools", "lang_frag_bus6.json")

EX = "gui.rs_create_compat.exporter_executor."

BUS6 = {
    # ---- 覆盖：类别类型说明改为「输入原料」（名字那一行显示的是真实物品 / 流体名）----
    EX + "inputs.tip": {
        "zh": "导出这一类输入原料：从执行仓内部存储里，把本步所需的那种原料推给本总线面对的机器。"
              "同一种原料若被多台输出总线选中，会按轮询在它们之间均分。",
        "en": "Exports this input-material category: takes that material from the chamber's own storage and "
              "pushes it to the machine this bus faces. If multiple buses select the same category, they "
              "share it round-robin.",
    },
    # ---- 覆盖：中间产物那一格显示的是「过渡件物品自己」的名字与图标 ----
    EX + "intermediates.tip": {
        "zh": "导出「中间产物」：格子里显示的就是该过渡件物品本身（带进度组件的未完成件），"
              "从执行仓内部存储推给本总线面对的机器。同一种过渡件若被多台输出总线选中，会按轮询均分。",
        "en": "Exports the intermediate category: the cell shows the transitional item itself (an unfinished "
              "item with progress components), pushed from the chamber's own storage to the machine this bus "
              "faces. If multiple buses select it, they share it round-robin.",
    },
    # ---- 删除：类别名已改为真实物品名，该语言键不再被引用 ----
    EX + "intermediate.label": {"delete": True},
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(BUS6, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] %s：写入 %d 键" % (os.path.relpath(OUT, ROOT), len(BUS6)))
    print("下一步：python tools/apply_lang_frag.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
