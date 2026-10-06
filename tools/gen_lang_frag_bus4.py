# -*- coding: utf-8 -*-
"""生成 / 更新本轮（输出总线滚轮交互 + 自动合成门控 + 回流总线步骤详情与应用预览）的语言键片段。

为什么用脚本生成：语言键属于「有形式要求的 json」，一律由脚本产出（不手写整份 json）。
本脚本只产出 / 修补**片段**，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件。

片段归属（按文件名排序决定合并顺序，后面的覆盖前面的）：
  * 新增键          → tools/lang_frag_bus4.json（本轮新片段）
  * 覆盖 locked.tip → tools/lang_frag_misc.json（该键原本就归这里，且 misc 排在 bus4 之后）
  * 覆盖 step.hint  → tools/lang_frag_cc.json  （该键原本就归这里，且 cc   排在 bus4 之后）

本轮内容：
  1. 输出总线锁定条：滚轮 = 翻看 / 左键 = 勾选 / Shift+滚轮 = 切换鼠标下那一格（操作说明写短）；
  2. 「自动合成未开启」提示（服务端门控）；
  3. 回流总线：「应用」按钮 + 步骤详情 + 会收集 / 不会收集预览。

用法：
    python tools/gen_lang_frag_bus4.py
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
OUT = os.path.join(ROOT, "tools", "lang_frag_bus4.json")
MISC = os.path.join(ROOT, "tools", "lang_frag_misc.json")
CC = os.path.join(ROOT, "tools", "lang_frag_cc.json")

EX = "gui.rs_create_compat.exporter_executor."
RB = "gui.rs_create_compat.sequence_return_bus."

# ===== 本轮新片段：tools/lang_frag_bus4.json =====
BUS4 = {
    # ---- 输出总线锁定条：交互说明（用户嫌长 tooltip 挡视线 → 一律短句、可分行）----
    EX + "click": {
        "zh": "左键：勾选 / 取消（可多选）",
        "en": "Left click: select/deselect (multi-select)",
    },
    EX + "wheel": {
        "zh": "滚轮：翻看 · Shift+滚轮：切换鼠标下那格",
        "en": "Wheel: browse · Shift+Wheel: toggle the cell under the cursor",
    },
    # ---- 自动合成门控 ----
    EX + "gate.off.tip": {
        "zh": "自动合成未开启：本总线当前不导出任何东西（先发起自动合成）",
        "en": "Autocrafting is off: this bus exports nothing right now (start an autocrafting task first)",
    },
    # ---- 回流总线：应用按钮 ----
    RB + "apply.button": {
        "zh": "应用",
        "en": "Apply",
    },
    RB + "apply.button.pending": {
        "zh": "应用 *",
        "en": "Apply *",
    },
    RB + "apply.tip": {
        "zh": "把当前选中的回流步骤应用到本机（服务端权威）。应用后下方列出这一步「会收集 / 不会收集」的内容。",
        "en": "Apply the selected return step to this machine (server-authoritative). "
              "Afterwards the panel below lists what this step will and will not collect.",
    },
    RB + "apply.state.applied": {
        "zh": "当前：已应用",
        "en": "State: applied",
    },
    RB + "apply.state.pending": {
        "zh": "当前：有未应用的步骤修改（点「应用」生效）",
        "en": "State: unapplied step change (click Apply)",
    },
    # ---- 回流总线：步骤详情 ----
    RB + "detail.title": {
        "zh": "第%1$s步 · %2$s（%3$s）",
        "en": "Step %1$s · %2$s (%3$s)",
    },
    RB + "detail.inputs": {
        "zh": "输入原料：%s",
        "en": "Inputs: %s",
    },
    RB + "detail.intermediate": {
        "zh": "中间产物：%s",
        "en": "Intermediate: %s",
    },
    RB + "detail.results": {
        "zh": "成品：%s",
        "en": "Products: %s",
    },
    RB + "detail.scraps": {
        "zh": "废料：%s",
        "en": "Scraps: %s",
    },
    RB + "detail.none": {
        "zh": "无",
        "en": "none",
    },
    RB + "detail.apply_hint": {
        "zh": "点「应用」后显示会收集 / 不会收集",
        "en": "Click Apply to see what is collected / not collected",
    },
    # ---- 回流总线：收集预览 ----
    RB + "preview.section": {
        "zh": "— 应用后 —",
        "en": "- after applying -",
    },
    RB + "preview.collect": {
        "zh": "会收集：%s",
        "en": "Collects: %s",
    },
    RB + "preview.keep": {
        "zh": "不会收集：%s",
        "en": "Does not collect: %s",
    },
    # ---- 回流总线：自动合成门控 ----
    RB + "gate.autocraft_off": {
        "zh": "自动合成未开启（已暂停吸取）",
        "en": "Autocrafting off (pulling paused)",
    },
    RB + "gate.autocraft_off.tip": {
        "zh": "所在网络没有进行中的自动合成任务：本机暂停主动吸取；缓冲里已有的内容仍会照常回网，绝不销毁。",
        "en": "No autocrafting task is running on the network: active pulling is paused. "
              "Anything already buffered is still returned to the network and is never destroyed.",
    },
}

# ===== 覆盖（归属别的片段，且那些片段排在 bus4 之后）=====
OVERRIDES = {
    MISC: {
        # 锁定条空白处 tooltip：原来一大段，用户嫌挡视线 → 压短
        EX + "locked.tip": {
            "zh": "本总线已连到一台处于「总线输出」模式的序列执行仓（贴着放或用 RS 线缆直达，最多 64 格）。"
                  "类别由执行仓按单元样板动态给出，可多选；原来的过滤器槽位不可编辑。",
            "en": "This bus is linked to a sequence execution chamber in Bus Output mode (adjacent, or reached "
                  "through RS cables up to 64 blocks). Categories come from the chamber's unit patterns and "
                  "can be multi-selected; the original filter slots are locked.",
        },
    },
    CC: {
        # 步骤选择器提示：补上「要先应用」这条本轮新增的交互
        RB + "step.hint": {
            "zh": "滚轮切换；左键点半区 = 上一步 / 下一步。改完要点「应用」才生效。",
            "en": "Wheel to switch; left-click a half to go prev/next. Click Apply to make it take effect.",
        },
    },
}


def write_bus4():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(BUS4, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] %s：写入 %d 键" % (os.path.relpath(OUT, ROOT), len(BUS4)))


def patch_overrides():
    for path, upserts in OVERRIDES.items():
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        for key, value in upserts.items():
            data[key] = value
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print("[OK] %s：覆盖 %d 键（幂等）" % (os.path.relpath(path, ROOT), len(upserts)))


def main():
    write_bus4()
    patch_overrides()
    print("下一步：python tools/apply_lang_frag.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
