# -*- coding: utf-8 -*-
"""生成本轮（回流总线：删方位 + 线缆连接 + 类别条复用输出总线控件 + 详情区排版修复）的语言键片段。

为什么用脚本生成：语言键属于「有形式要求的 json」，一律由脚本产出（不手写整份 json）。
本脚本只产出 / 修补**片段**，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件；
片段文件按文件名排序合并，`lang_frag_returnbus2.json` 排在 `lang_frag_misc.json` 之后、
`lang_frag_spt*.json` 之前 —— 本片段要覆盖的键（exporter_executor 的 shared/exclusive/
gate/scroll/state/no_categories）在更靠后的片段里没有被再次定义，覆盖关系稳定。

本轮内容：
  1. 删除：方位（主动吸取面）相关全部语言键 + 被「段标题 + 正文」取代的旧 detail.* 行键 + 未再使用的 auto_note；
  2. 新增：回流总线「可回收类别」条（标题 / 空态 / 三类提示）+ 详情区四段标题；
  3. 覆盖：把类别条里少数「只提输出总线」的措辞改成中性（输出总线与回流总线共用同一控件）。

用法：
    python tools/gen_lang_frag_returnbus2.py
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
OUT = os.path.join(ROOT, "tools", "lang_frag_returnbus2.json")

RB = "gui.rs_create_compat.sequence_return_bus."
EX = "gui.rs_create_compat.exporter_executor."

# 方位（主动吸取面）配置整体删除 → 相关语言键一并清除
DELETED = [
    RB + "face_config.button",
    RB + "face_config.tip",
    RB + "face_config.state",
    RB + "face_config.title",
    RB + "face_config.close",
    RB + "face_config.dir.up",
    RB + "face_config.dir.down",
    RB + "face_config.dir.north",
    RB + "face_config.dir.south",
    RB + "face_config.dir.west",
    RB + "face_config.dir.east",
    RB + "face_config.on",
    RB + "face_config.off",
    RB + "face_config.current",
    RB + "face_config.state.on.tip",
    RB + "face_config.state.off.tip",
    # 详情区改为「段标题行 + 正文行」：旧的「xx：%s」行键不再使用
    RB + "detail.inputs",
    RB + "detail.intermediate",
    RB + "detail.results",
    RB + "detail.scraps",
    # 旧布局遗留（本轮起界面不再显示该文案）
    RB + "auto_note",
]

FRAG = {
    # ---- 1. 删除 ----
    **{key: {"delete": True} for key in DELETED},

    # ---- 2. 回流总线「可回收类别」条（复用输出总线控件，只有文案是本机的）----
    RB + "categories.title": {
        "zh": "可回收类别（点格子勾选）",
        "en": "Collectable categories (click to toggle)",
    },
    RB + "categories.title.tip": {
        "zh": "本机已通过 RS 线缆 / 输入总线连到「总线输出」模式的序列执行仓：类别由本机绑定的总样板给出，"
              "勾选后才会被本机抽回网络。",
        "en": "This bus is wired (RS cables / importer) to a sequence chamber in bus-output mode: the categories "
              "come from the bound assembly pattern, and only checked ones are pulled back.",
    },
    RB + "categories.empty": {
        "zh": "未连接执行舱或未绑定总样板",
        "en": "No chamber linked, or no assembly pattern bound",
    },
    RB + "categories.product.tip": {
        "zh": "回收成品：这一步的成品产出会被本机抽回网络存储（产出多少收多少）。",
        "en": "Collect products: this step's outputs are pulled back into network storage.",
    },
    RB + "categories.intermediate.tip": {
        "zh": "回收中间产物（该步的 Create 过渡件）：一律带回网络，绝不当废料销毁。",
        "en": "Collect intermediates (this step's Create transitional item): always returned to the network, "
              "never destroyed as waste.",
    },
    RB + "categories.waste.tip": {
        "zh": "回收废料：勾选后本机才会抽走它；是否销毁由「销毁废料」开关决定。",
        "en": "Collect waste: only checked waste is pulled away; destruction is controlled by the "
              "\"destroy waste\" switch.",
    },

    # ---- 3. 详情区四段标题（正文 = 纯清单文本，段与段不再连成一片）----
    RB + "detail.head.inputs": {"zh": "输入原料", "en": "Inputs"},
    RB + "detail.head.intermediate": {"zh": "中间产物", "en": "Intermediate"},
    RB + "detail.head.results": {"zh": "成品", "en": "Products"},
    RB + "detail.head.scraps": {"zh": "废料", "en": "Waste"},

    # ---- 4. 共用控件的措辞中性化（输出总线 / 回流总线都用同一个类别条）----
    EX + "shared.tip": {
        "zh": "当前 %s 台总线共享该类别：按轮询均分（除不尽时余数按轮询顺序分配，不会丢失或复制）。"
              "右下角的青色小方块就是这个标记。",
        "en": "%s buses share this category: output is split by round-robin (the remainder follows the same "
              "order; nothing is lost or duplicated). The cyan square marks it.",
    },
    EX + "exclusive.tip": {
        "zh": "当前仅本总线选中该类别（独占）。再让别的总线也选中它即可改为均分。",
        "en": "Only this bus has this category selected (exclusive). Select it on another bus to switch to "
              "round-robin sharing.",
    },
    EX + "gate.off.tip": {
        "zh": "自动合成未开启：本总线当前不处理任何类别（先发起自动合成）",
        "en": "Autocrafting is off: this bus currently handles no categories (start an autocrafting task first)",
    },
    EX + "scroll.tip": {
        "zh": "滚轮：左右翻看更多类别（当前显示第 %s-%s 项，共 %s 项）。点下方滚动条可直接跳转。",
        "en": "Wheel: browse more categories (showing %s-%s of %s). Click the bar below to jump.",
    },
    EX + "no_categories": {
        "zh": "暂无可用类别",
        "en": "No categories available",
    },
    EX + "state.on": {"zh": "状态：本总线已选", "en": "State: selected on this bus"},
    EX + "state.off": {"zh": "状态：本总线未选", "en": "State: not selected on this bus"},
}


def main():
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    deletes = sum(1 for value in FRAG.values() if isinstance(value, dict) and value.get("delete"))
    print("[写入] %s：%d 键（其中删除 %d 键）" % (os.path.relpath(OUT, ROOT), len(FRAG), deletes))


if __name__ == "__main__":
    main()
