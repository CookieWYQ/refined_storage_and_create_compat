# -*- coding: utf-8 -*-
"""生成本轮（归流缓存仓 + 各「面配置」界面）的语言键片段 tools/lang_frag_cc3.json。

为什么用脚本生成：语言键是有形式要求的 json，一律由脚本产出片段，再由
tools/apply_lang_frag.py 幂等合并进中英语言文件（不手写整份 json）。

片段语义：
  {"<键>": {"en": "...", "zh": "..."}}  → 新增 / 覆盖
  {"<键>": {"delete": true}}            → 中英同时删除

本轮两类改动：
  A. 覆盖：把「操作说明型长 tooltip」压到一行（用户要求 tooltip 更短、少遮挡）；
  B. 删除：无信息量的统计 / 旧文案键（「收集 %s / 入网 %s」「速度×%s 堆叠×%s」
     「流体缓存：%s / %s」「滚轮 / 左键怎么操作」等），中英一起删。

用法：python tools/gen_lang_frag_cc3.py  然后  python tools/apply_lang_frag.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_cc3.json")

CC = "gui.rs_create_compat.collection_cache."
CH = "gui.rs_create_compat.sequence_execution_chamber."
RB = "gui.rs_create_compat.sequence_return_bus."

# ==================== 覆盖：tooltip 精简（一行说清，不再堆操作步骤） ====================
UPSERT = {
    # ---- 归流缓存仓主界面 ----
    CC + "region_marker.tip": {
        "zh": "匹配区：ghost 标记（左键标记 / 空手左键配置 / Shift+左键清除）",
        "en": "Markers: ghost markers (left-click to mark / empty-hand left-click to configure / Shift+left-click to clear)",
    },
    CC + "region_cache.tip": {
        "zh": "缓存区：真实存储，装不下的暂存于此并继续尝试入网",
        "en": "Cache: real storage; overflow is held here and retried into the network",
    },
    CC + "blocked.on": {
        "zh": "已阻塞（不回流进网络）",
        "en": "Blocked (not returned to the network)",
    },
    CC + "blocked.off": {
        "zh": "未阻塞（正常回流进网络）",
        "en": "Not blocked (returned to the network normally)",
    },
    CC + "cache.fluid_click": {
        "zh": "左键取出 1 桶",
        "en": "Left-click: take 1 bucket",
    },
    CC + "cache.fluid_insufficient": {
        "zh": "不足 1 桶",
        "en": "Less than 1 bucket",
    },
    CC + "marker.matched": {
        "zh": "可匹配 %s 种物品",
        "en": "Matches %s item type(s)",
    },
    CC + "marker.matched_fluid": {
        "zh": "可匹配 %s 种流体",
        "en": "Matches %s fluid type(s)",
    },
    CC + "marker.tag_filter": {
        "zh": "按标签匹配（一整类资源）",
        "en": "Match by tag (a whole class)",
    },
    CC + "marker.match_nbt.on": {
        "zh": "匹配 NBT：开",
        "en": "Match NBT: ON",
    },
    CC + "marker.match_nbt.off": {
        "zh": "匹配 NBT：关",
        "en": "Match NBT: OFF",
    },
    CC + "match_nbt.tip": {
        "zh": "要求数据组件（NBT）完全一致才命中",
        "en": "Require data components (NBT) to match exactly",
    },
    CC + "blocked.tip": {
        "zh": "阻塞后仍可收集 / 取出，只是不再回流进 RS 网络",
        "en": "Blocked resources can still be collected/extracted, just no longer returned to the RS network",
    },
    CC + "match_tags.tip": {
        "zh": "打开后多选示例物自带的标签；命中物需同时带上全部选中标签",
        "en": "Turn on to multi-select tags the sample carries; a match must carry all of them",
    },
    CC + "input_face.tip": {
        "zh": "逐面开关物流输入（漏斗 / 管道）",
        "en": "Toggle which faces accept logistics input (hoppers/pipes)",
    },
    CC + "input_face.state.on.tip": {
        "zh": "该面允许漏斗 / 管道塞入",
        "en": "This face accepts items/fluids from hoppers/pipes",
    },
    CC + "input_face.state.off.tip": {
        "zh": "该面不接收输入（仍可抽走）",
        "en": "This face refuses input (extraction is still allowed)",
    },
    CC + "range.step": {
        "zh": "Shift+点击 = ±5",
        "en": "Shift+click = ±5",
    },

    # ---- 序列执行仓「面配置」 ----
    CH + "face.mode.none.tip": {
        "zh": "该面不参与自动交互",
        "en": "This face does nothing automatically",
    },
    CH + "face.mode.input.tip": {
        "zh": "把原料推给该面的相邻机器",
        "en": "Push inputs to the machine on this face",
    },
    CH + "face.mode.output.tip": {
        "zh": "从该面收回产物",
        "en": "Collect outputs from this face",
    },
    CH + "face.mode.intermediate.tip": {
        "zh": "从该面收回过渡件并回写网络",
        "en": "Collect transitional items here and return them to the network",
    },
    CH + "output.face.tip": {
        "zh": "面输出：按左侧六个面的配置工作",
        "en": "Face output: works by the six face settings on the left",
    },
    CH + "output.bus.tip": {
        "zh": "总线输出：忽略逐面配置，由相连的输出总线代劳",
        "en": "Bus output: per-face settings are ignored; a connected exporter bus does the work",
    },

    # ---- 序列装配回流总线「面配置」 ----
    RB + "face_config.tip": {
        "zh": "逐面设置是否主动抽取相邻容器",
        "en": "Choose which faces actively pull from neighbours",
    },
    RB + "face_config.state.on.tip": {
        "zh": "该面会主动抽取相邻容器的物品 / 流体",
        "en": "This face actively pulls items/fluids from the neighbour",
    },
    RB + "face_config.state.off.tip": {
        "zh": "该面不主动抽取（外部仍可塞入）",
        "en": "This face does not pull (external insertion is still allowed)",
    },
}

# ==================== 删除：无信息量统计 / 已无渲染点的旧键（中英一起删） ====================
DELETE = [
    # 「收集 0 / 入网 0」这类统计（界面已不显示，数据位保留但不给文案）
    CC + "stats",
    CC + "stats.tip",
    CC + "stats.capacity",
    CC + "stats.fluid",
    CC + "collected",
    CC + "inserted",
    # 「速度×0 堆叠×0 上限 64/格」这类无信息量汇总
    CC + "upgrades",
    CC + "upgrade_speed",
    CC + "upgrade_stack",
    CC + "upgrade_speed.tip",
    CC + "upgrade_stack.tip",
    # 旧的「吸取开关」说明文案（按要求这四个开关不加 tooltip）
    CC + "absorb.item.tip",
    CC + "absorb.fluid.tip",
    CC + "absorb.gas.tip",
    CC + "absorb.experience.tip",
    # 旧分区文案（V4 起流体与物品共用同一套槽位 / 客户端不再渲染）
    CC + "fluid_region",
    CC + "fluid_region.tip",
    CC + "cache.fluid_summary",
    CC + "upgrade",
    CC + "hint",
    # 操作说明型 tooltip（用户要求不要再堆在 tooltip 里）
    CC + "blocked.hint.on",
    CC + "blocked.hint.off",
    CC + "blocked.hint.band",
    CC + "match_tags.selected",
    CC + "match_tags.unselected",
    CC + "match_tags.row_click",
    CC + "input_face.click",
    CH + "face.click",
    CH + "face.hint.1",
    CH + "face.hint.2",
    CH + "face.hint.3",
    CH + "face.hint.tip",
    CH + "output.tip.title",
    CH + "output.tip.switch",
    RB + "face_config.click",
    RB + "face_config.hint.1",
    RB + "face_config.hint.2",
    RB + "face_config.hint.3",
    RB + "face_config.hint.tip",
]


def main():
    fragment = {}
    for key, value in UPSERT.items():
        fragment[key] = value
    for key in DELETE:
        fragment[key] = {"delete": True}
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(fragment, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[写入] %s：覆盖 %d 键 / 删除 %d 键"
          % (os.path.relpath(OUT, ROOT), len(UPSERT), len(DELETE)))


if __name__ == "__main__":
    main()
