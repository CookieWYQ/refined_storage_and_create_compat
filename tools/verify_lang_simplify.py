# -*- coding: utf-8 -*-
"""校验「精简语言文本」结果（只读）。

检查项
------
1. zh_cn.json 与 en_us.json 的键集合完全一致（不增、不删、改名即报错）。
2. 每个键的中英占位符数量一致（%s / %1$s 这类，按出现次数比较）。
3. 长度：本次精简范围（SCOPE）内，zh_cn > 55 字 或 en_us > 110 字符 = **问题**（硬上限）；
   45 < zh <= 55 或 95 < en <= 110 = 「放行」（允许但需在报告中说明原因）。
   范围外（后续轮次新增、未纳入本次精简）的文案即使超限也只**提示**、不计为问题
   —— 本脚本校验的是「本次精简」的结果，不对未参与的键作强制判定。
4. 值首尾空白、连续重复空格等明显笔误（拼接片段的前缀 / 后缀 / 分隔符例外，见 EDGE_SPACE_BY_DESIGN）。

输出末行固定为 `问题总数: N`。

用法：python tools/verify_lang_simplify.py
"""
import io
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

IDEAL_ZH, HARD_ZH = 45, 55
IDEAL_EN, HARD_EN = 95, 110

PLACEHOLDER = re.compile(r"%(\d+\$)?s")

# 本次精简的原始范围：开工时 zh_cn > 55 字 或 en_us > 110 字符的键。
# 只有这些键需要为「放行」逐条说明原因；范围外的历史文本不作为问题。
SCOPE = {
    "block.rs_create_compat.advanced_schematic_loader.help",
    "block.rs_create_compat.quantity_keeper.help",
    "block.rs_create_compat.range_charger.help",
    "block.rs_create_compat.schematic_loader.help",
    "block.rs_create_compat.collection_cache.help",
    "block.rs_create_compat.sequence_assembly_executor.help",
    "block.rs_create_compat.sequence_execution_chamber.help",
    "block.rs_create_compat.sequence_pattern_terminal.help",
    "block.rs_create_compat.advanced_quantity_keeper.help",
    "block.rs_create_compat.unit_pattern_manager.help",
    "item.rs_create_compat.advanced_remote_terminal.usage",
    "item.rs_create_compat.advanced_remote_terminal.bind_hint",
    "gui.rs_create_compat.quantity_keeper.marker_tooltip",
    "gui.rs_create_compat.quantity_keeper.blocked.tooltip",
    "gui.rs_create_compat.advanced_quantity_keeper.marker_tooltip",
    "gui.rs_create_compat.advanced_quantity_keeper.blocked.tooltip",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_header.tip",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_no_upgrade.tip",
    "gui.rs_create_compat.advanced_quantity_keeper.overflow_header.tip",
    "gui.rs_create_compat.sequence_execution_chamber.hint",
    "gui.rs_create_compat.sequence_execution_chamber.output.bus.tip",
    "gui.rs_create_compat.sequence_execution_chamber.chain.tip.entry",
    "gui.rs_create_compat.sequence_assembly_executor.hint2",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.slot.tip",
    "gui.rs_create_compat.step_detail.step.tip",
    "gui.rs_create_compat.unit_pattern_config.requires_input.tip",
    "gui.rs_create_compat.exporter_executor.fluids.tip",
    "gui.rs_create_compat.exporter_executor.inputs.tip",
    "gui.rs_create_compat.exporter_executor.intermediates.tip",
    "gui.rs_create_compat.exporter_executor.locked.tip",
    "gui.rs_create_compat.exporter_executor.shared.tip",
    "gui.rs_create_compat.exporter_executor.exclusive.tip",
    "gui.rs_create_compat.exporter_executor.estimated.note",
    "message.rs_create_compat.autocrafter_storage.usage",
    "message.rs_create_compat.autocrafter_storage.disabled",
    "message.rs_create_compat.autocrafter_storage.disable_failed",
    "message.rs_create_compat.autocrafter_storage.disabled_by_config",
}

# 「拼接片段」：这些键的值会被**首尾直接相接**地拼成一行（前缀 / 后缀 / 列表分隔符），
# 因此其首尾空格是**语义的一部分**（去掉就会出现「由于铁锭」「Becauseiron」这类黏连），
# 属于刻意写法而不是笔误。逐键登记（附拼接位置），只对这几个键豁免首尾空白判定。
EDGE_SPACE_BY_DESIGN = {
    "gui.rs_create_compat.assembly.banner.material_prefix": "前缀：「由于 」/「Because 」+ 原料名",
    "gui.rs_create_compat.assembly.banner.material_suffix": "后缀：原料名 +「 缺少或自动合成失败」",
    "gui.rs_create_compat.assembly.banner.material_more": "缀尾：「 and %s more」（拼在原料列表之后）",
    "gui.rs_create_compat.assembly.banner.offline_prefix": "前缀：「由于 」/「Because the executor for 」+ 步骤名",
    "gui.rs_create_compat.assembly.banner.offline_suffix": "后缀：步骤名 +「 的执行器掉线」",
    "gui.rs_create_compat.assembly.banner.offline_more": "缀尾：「 等 %s 个步骤」/「 and %s more steps」",
    "gui.rs_create_compat.assembly.banner.output_blocked_prefix": "前缀：「输出阻塞：第 」/「Output blocked: step 」+ 步序",
    "gui.rs_create_compat.push_stall.cause.refused": "堵塞原因段：「输出阻塞：第 N」+「 步的下游工位收不下这一件」+ 后缀（拼接片段，首空格是语义的一部分）",
    "gui.rs_create_compat.push_stall.cause.no_owner": "堵塞原因段：「输出阻塞：第 N」+「 步没有任何在线执行仓认领」+ 后缀（同上）",
    "gui.rs_create_compat.assembly.banner.separator": "列表分隔符：「、」/「, 」",
    "gui.rs_create_compat.bus_bar.sep": "列表分隔符：「、」/「, 」",
}


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as f:
        return json.load(f)


def main():
    zh = load("zh_cn.json")
    en = load("en_us.json")
    problems = []

    # 1) 键集合
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    for k in only_zh:
        problems.append("仅 zh_cn 有该键：%s" % k)
    for k in only_en:
        problems.append("仅 en_us 有该键：%s" % k)

    relaxed = []
    out_of_scope_long = []
    for key in sorted(set(zh) & set(en)):
        zh_v, en_v = str(zh[key]), str(en[key])

        # 2) 占位符
        pz, pe = PLACEHOLDER.findall(zh_v), PLACEHOLDER.findall(en_v)
        if len(pz) != len(pe):
            problems.append("占位符数量不一致 %s：zh %d 个 %s / en %d 个 %s"
                            % (key, len(pz), pz, len(pe), pe))

        # 3) 长度：硬上限只判定本次精简范围（SCOPE）内的键；范围外的超限文案只提示
        for field, value, hard in (("zh_cn", zh_v, HARD_ZH), ("en_us", en_v, HARD_EN)):
            if len(value) > hard:
                message = "%s 超硬上限(%d) 实为 %d：%s" % (field, hard, len(value), key)
                if key in SCOPE:
                    problems.append(message)
                else:
                    out_of_scope_long.append(message)
        if IDEAL_ZH < len(zh_v) <= HARD_ZH or IDEAL_EN < len(en_v) <= HARD_EN:
            if key in SCOPE:
                relaxed.append((key, len(zh_v), len(en_v)))

        # 4) 明显笔误（拼接片段的前缀 / 后缀 / 分隔符允许首尾空格，见 EDGE_SPACE_BY_DESIGN）
        for field, value in (("zh_cn", zh_v), ("en_us", en_v)):
            if value != value.strip() and key not in EDGE_SPACE_BY_DESIGN:
                problems.append("%s 值首尾有空白：%s" % (field, key))
            if "  " in value:
                problems.append("%s 值含连续空格：%s" % (field, key))

    print("键集合：zh_cn %d 键 / en_us %d 键，一致：%s"
          % (len(zh), len(en), not only_zh and not only_en))
    print("占位符：逐键比对中英 %s / %%N$s 数量")
    print("长度：理想 zh<=%d / en<=%d，硬上限 zh<=%d / en<=%d"
          % (IDEAL_ZH, IDEAL_EN, HARD_ZH, HARD_EN))

    if relaxed:
        print("\n放行（未达理想目标但仍在硬上限内）%d 条：" % len(relaxed))
        for key, lz, le in relaxed:
            print("  zh %2d / en %3d  %s" % (lz, le, key))
    else:
        print("\n放行：0 条")

    if out_of_scope_long:
        print("\n提示（范围外文案超硬上限，不计为问题）%d 条：" % len(out_of_scope_long))
        for msg in out_of_scope_long:
            print("  - %s" % msg)

    if problems:
        print("\n问题清单：")
        for p in problems:
            print("  - %s" % p)
    else:
        print("\n问题清单：无")

    print("\n问题总数: %d" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
