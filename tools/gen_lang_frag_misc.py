# -*- coding: utf-8 -*-
"""生成本轮（Round4 · 高级物品定量保持器 / 面配置 / 输出总线）的语言键片段。

为什么单独用脚本生成 json：
  * 语言文件是有严格形式要求的文件（UTF-8、2 空格缩进、`{"键": {"en": ..., "zh": ...}}`），
    手写容易出编码 / 转义 / 缩进问题；
  * 本轮只往 `tools/lang_frag_misc.json` 写键，随后由 `tools/apply_lang_frag.py` 统一合并进
    中英 lang json —— 这样与并行任务互不冲突（各自一份 frag，合并幂等）。

用法：
    python tools/gen_lang_frag_misc.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_misc.json")

LANG_KEEPER = "gui.rs_create_compat.advanced_quantity_keeper."
LANG_FACE = "gui.rs_create_compat.sequence_execution_chamber."
LANG_EXP = "gui.rs_create_compat.exporter_executor."

# 键 -> {"en": 英文, "zh": 中文}
FRAG = {
    # A. 高级物品定量保持器：两列开关的列标题提示（列标题本身复用既有的 autocraft_label / overflow_label）
    LANG_KEEPER + "autocraft_header.tip": {
        "en": "Autocraft column: click a row's check/cross to toggle autocrafting for that row "
              "(requires an Autocrafting Upgrade; without it these buttons stay disabled).",
        "zh": "自动合成列：点某行的 ✓/✗ 切换该行是否自动合成"
              "（必须先装入自动合成升级，否则这一列按钮保持禁用、点都点不了）。",
    },
    LANG_KEEPER + "overflow_header.tip": {
        "en": "Destroy-overflow column: click a row's check/cross to let that row destroy the amount "
              "beyond its target amount.",
        "zh": "过量销毁列：点某行的 ✓/✗ 切换该行「超出目标数量的部分是否销毁」。",
    },

    # C6. 输出总线：连接判定「相邻或线缆一路直达」；类别由执行仓动态给出、可多选、可被多台共享均分
    LANG_EXP + "locked.tip": {
        "en": "This Exporter is wired to a Sequence Execution Chamber in Bus Output mode: directly adjacent, "
              "or reached through a run of RS cables, with no other machine in between (up to 64 blocks). "
              "The category list comes from the chamber (one entry per input material in its unit patterns, "
              "plus one for the intermediates); tick as many as you like, and if several exporters tick the "
              "same category the chamber splits its output between them in round-robin. The filter slots are "
              "locked; switch the chamber back to Face Output to restore them.",
        "zh": "本输出总线已连到一台处于「总线输出」模式的序列执行仓：紧贴着放，或用 RS 线缆一路接过去都算，"
              "中间不能隔着别的机器（最多 64 格）。类别由执行仓按单元样板动态给出（每一种输入原料一项，"
              "再加一项中间产物）；可以同时勾选多项，若多台总线勾选同一类别，本仓会按轮询把产出均分给它们。"
              "原来的过滤器槽位不可编辑，把执行仓切回「面输出」即可恢复。",
    },

    # B. 面配置界面：格内改画「模式短名」（完整名称留给图例 / tooltip）
    LANG_FACE + "face.mode.short.none": {"en": "None", "zh": "无"},
    LANG_FACE + "face.mode.short.input": {"en": "Input", "zh": "输入"},
    LANG_FACE + "face.mode.short.output": {"en": "Output", "zh": "输出"},
    LANG_FACE + "face.mode.short.intermediate": {"en": "Mid", "zh": "中间"},

    # B/C6. 面配置界面的「总线输出」说明同步为「相邻或线缆直达」（第六轮收紧）
    # 第九轮：tooltip 精简 —— 只保留「忽略逐面配置 + 由相连的输出总线代劳」这一句（用户要求缩短 tooltip）。
    LANG_FACE + "output.bus.tip": {
        "en": "Bus output: per-face settings are ignored; an exporter bus placed next to the chamber "
              "or reached through a run of RS cables does the work.",
        "zh": "总线输出：忽略逐面配置，由紧贴或线缆相连的「输出总线」代劳。",
    },
    LANG_FACE + "output.cell.ignored": {
        "en": "Note: bus output is active, so this face config is ignored (it takes effect again in face output mode).",
        "zh": "注意：当前为「总线输出」模式，该面的配置被整体忽略（切回「面输出」后立即恢复生效）。",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] 写入 %s（%d 键）" % (OUT, len(FRAG)))


if __name__ == "__main__":
    main()
