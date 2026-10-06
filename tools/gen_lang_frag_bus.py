# -*- coding: utf-8 -*-
"""生成输出总线语言键片段 tools/lang_frag_bus.json（随每轮改动更新）。

为什么用脚本生成：语言键属于"有形式要求的 json"，一律由脚本产出（不手写整份 json），
本脚本只产出<b>片段</b>，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件。

片段语义：
  {"<键>": {"en": "...", "zh": "..."}}  → 新增 / 覆盖
  {"<键>": {"delete": true}}            → 中英同时删除

本轮（类别动态化 + 多选 + 共享均分）改动：
  * 删除两个「禁用态」键（独占语义已取消，共享是正常状态）；
  * 新增「类别显示名 / 共享提示 / 独占提示 / 无类别提示」；
  * 改写 inputs.tip / intermediates.tip / click / state.*（不再提「哪两类」「按钮」）。

用法：python tools/gen_lang_frag_bus.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_bus.json")

LANG = "gui.rs_create_compat.exporter_executor."

UPSERT = {
    # ---- 类别本身 ----
    LANG + "intermediate.label": {
        "zh": "中间产物（过渡件）",
        "en": "Intermediates (transitional items)",
    },
    LANG + "inputs.tip": {
        "zh": "导出这一类输入性产物：把网络上序列执行仓单元样板对应的这种原料推给本总线面对的机器。"
              "同一种原料若被多台输出总线选中，会按轮询在它们之间均分。",
        "en": "Export this input material: pushes this specific ingredient (from the chamber's unit patterns) "
              "into the machine this bus faces. If several exporters select the same material, the chamber "
              "splits it between them in round-robin.",
    },
    LANG + "intermediates.tip": {
        "zh": "导出「中间产物」：把网络上序列装配的过渡件（带进度组件的未完成件）推给本总线面对的机器。"
              "同一种过渡件若被多台输出总线选中，会按轮询在它们之间均分。",
        "en": "Export intermediates: pushes the sequenced-assembly transitional items (with progress "
              "components) into the machine this bus faces. If several exporters select it, the chamber "
              "splits it between them in round-robin.",
    },

    # ---- 开关状态与共享语义 ----
    LANG + "state.on": {
        "zh": "状态：本总线已选",
        "en": "State: selected on this bus",
    },
    LANG + "state.off": {
        "zh": "状态：本总线未选",
        "en": "State: not selected on this bus",
    },
    LANG + "shared.tip": {
        "zh": "当前 %s 台输出总线共享该类别：本仓按轮询把产出均分给它们（除不尽时余数按轮询顺序分配，"
              "不会丢失或复制）。右下角的青色小方块就是这个标记。",
        "en": "This category is shared by %s exporters: the chamber hands its output to them in round-robin "
              "(a remainder goes to the earlier exporter in the rotation, never lost or duplicated). "
              "The cyan corner square marks a shared category.",
    },
    LANG + "exclusive.tip": {
        "zh": "当前仅本总线导出该类别（独占）。再让别的输出总线也选中它即可改为均分。",
        "en": "Only this bus exports this category right now (exclusive). Let another exporter select it "
              "too to switch to even splitting.",
    },
    LANG + "click": {
        "zh": "左键：切换本总线是否导出该类别（可以多选）",
        "en": "Left-click: toggle whether this bus exports this category (multi-select allowed)",
    },
    # ---- 本轮（类别条可横向滚动）新增：滚轮翻看更多类别 ----
    LANG + "scroll.tip": {
        "zh": "滚轮：左右翻看更多类别（当前显示第 %s-%s 项，共 %s 项）。点下方滚动条可直接跳转。",
        "en": "Mouse wheel: scroll to browse more categories (showing %s-%s of %s). Click the bar below to jump.",
    },
    LANG + "no_categories": {
        "zh": "执行仓暂无可用类别",
        "en": "The chamber has no exportable category yet",
    },

    # ---- 独占语义作废：删除两个「禁用态」键 ----
    LANG + "disabled.state": {"delete": True},
    LANG + "disabled.tip": {"delete": True},
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(UPSERT, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("已写入 %s（%d 键）" % (os.path.relpath(OUT, ROOT), len(UPSERT)))


if __name__ == "__main__":
    main()
