# -*- coding: utf-8 -*-
"""第 12 轮「机器提示文本再收一遍」——把机器 / 方块 / 物品的 help 与 tooltip 压得更短。

只改**提示字符串的值**（功能逻辑、键名一律不动）：
  * 删除「现象描述 / 一眼可见的常识 / 广告式宣传」；
  * 保留真正影响使用的必要前提与限制（需要联网 / 需要升级 / 类型不符会被拒……）；
  * 能删就删，删不掉的改写得更短（去掉「的、了、会、可以」这类冗余虚词）。

为什么用脚本：中英必须成对、占位符数量必须一致、错一个字整条文案就废；
脚本在写盘前做 json.load 校验、逐键比对，写盘后再复核一遍。

用法：python tools/simplify_lang_round2.py
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

PLACEHOLDER = re.compile(r"%(\d+\$)?s")

# key -> (新中文, 新英文)
CHANGES = {
    # ---------------- 方块 help（tooltip） ----------------
    "block.rs_create_compat.range_charger.help": (
        "接入 RS 网络或 FE，为范围内方块与掉落物充电。",
        "Charges blocks and dropped items in range from RS or FE power."),
    "block.rs_create_compat.quantity_keeper.help": (
        "标记资源并维持目标数量：不足自动合成，超出可销毁。",
        "Marks a resource and holds its target: autocrafts when low, voids the excess."),
    "block.rs_create_compat.advanced_quantity_keeper.help": (
        "4 个定量保持器合一，4 槽各设物品/流体/气体目标数量。",
        "Fuses 4 keepers into one; each slot sets an item/fluid/gas target."),
    "block.rs_create_compat.schematic_loader.help": (
        "紧贴 Create 蓝图加农炮，自动供料；多台相连协同供物。",
        "Place next to a Create Schematicannon to feed it; loaders share supply."),
    "block.rs_create_compat.advanced_schematic_loader.help": (
        "内置蓝图队列：按序自动打印，回收空白蓝图。",
        "Built-in queue: prints in order, recycles empty blueprints."),
    "block.rs_create_compat.sequence_pattern_terminal.help": (
        "把 Create 序列装配接入 RS 自动合成：导入配方，流程只读。",
        "Sequenced assembly for RS autocrafting: import a recipe; the flow is read-only."),
    "block.rs_create_compat.sequence_execution_chamber.help": (
        "紧贴机器放置，存取单元样板（54 格），自动供料并收回产物。",
        "Place against a machine; stores unit patterns (54), feeds it and reclaims products."),
    "block.rs_create_compat.collection_cache.help": (
        "接入 RS 网络后吸取周围掉落物、流体与气体，装不下先暂存并回流。",
        "Absorbs nearby drops, fluids and gases once networked; overflow is buffered and retried."),
    "block.rs_create_compat.intermediate_cache.help": (
        "接入网络后，27 格盘位成为全网执行舱共用的中间产物缓存。",
        "Once networked, its 27 disk slots become a shared intermediate cache for all chambers."),
    # ---------------- 装填器行动栏反馈 ----------------
    "block.rs_create_compat.schematic_loader.all_collected": (
        "资源已集齐。", "All resources collected."),
    "block.rs_create_compat.schematic_loader.missing": (
        "资源数量不足，无法收集。", "Not enough resources to collect."),
    "block.rs_create_compat.schematic_loader.done": (
        "已收集 %s 的资源", "Collected resources for %s"),
    "block.rs_create_compat.schematic_loader.no_materials": (
        "蓝图无需材料（或未能解析出方块）", "No materials needed (or no blocks parsed)"),
    "block.rs_create_compat.schematic_loader.not_printed": (
        "蓝图 %s 未能打印：加农炮未放置方块", "Blueprint %s not printed: cannon placed no blocks"),
    # ---------------- 序列样板终端 ----------------
    "gui.rs_create_compat.sequence_pattern_terminal.summary.slot.tip": (
        "左键取出 / Shift+左键移动 / 拖拽放置（限本仓配方类型）",
        "Left-click take / Shift+click move / drag to place (this recipe type only)"),
    "gui.rs_create_compat.sequence_pattern_terminal.summary.row.tip": (
        "超 4 张时轮询显示；配方类型不符的样板无法存入。",
        "Over 4 patterns rotate over time; other recipe types cannot be stored here"),
    "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine": (
        "第 %s 步无可用机器（%s），已按「无指派」导出",
        "Step %s has no machine (recipe type: %s); exported as unassigned"),
    "gui.rs_create_compat.sequence_pattern_terminal.generate.done": (
        "已生成：总样板 + %s 张单元样板（耗 %s 张）",
        "Generated: master + %s unit pattern(s) (consumed %s)"),
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.step": (
        "Shift + 点击一次 ±5（概率）/ ±10（数量）",
        "Shift + click steps 5 (chance) / 10 (amount)"),
    # ---------------- 单元样板配置 ----------------
    "gui.rs_create_compat.unit_pattern_config.recipe_type.tip": (
        "单元样板的配方类型（滚轮选；已绑定的排最前）",
        "Recipe type of the unit pattern (scroll to choose; bound ones first)"),
    "gui.rs_create_compat.unit_pattern_config.requires_input.tip": (
        "是否给该单元样板输入原料；「是」时用终端输入槽物品。",
        "Whether this unit pattern takes an input; ON uses the terminal input slot."),
    "gui.rs_create_compat.unit_pattern_manager.search.tip": (
        "按配方名 / 样板名 / 输入物名或 id 过滤",
        "Filter by recipe name, pattern name, or input name/id"),
    "gui.rs_create_compat.step_detail.count.tip": (
        "该步重复次数（[-] / [+] 或直接输入，1–1024）",
        "Repeat count of this step ([-] / [+] or type it, 1-1024)"),
    "gui.rs_create_compat.step_detail.step.tip": (
        "本次编辑作用于第几步（1..%s）；窗口外的步只写机器绑定。",
        "Which step this edit applies to (1..%s); off-screen steps bind the machine only."),
    # ---------------- 序列执行仓 ----------------
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.locked": (
        "已锁定：本链还有执行仓放着单元样板，全部取走才能改。",
        "Locked: a chamber in this chain still holds unit patterns; remove them all to change it."),
    "gui.rs_create_compat.sequence_execution_chamber.config.chain.units.tip": (
        "本链还有执行仓放着单元样板：配方类型已锁定。",
        "A chamber in this chain still holds unit patterns: the recipe type stays locked."),
    "gui.rs_create_compat.sequence_execution_chamber.config.machine.tip": (
        "能执行该配方类型的机器；多个时在此轮询切换",
        "Machine that runs this recipe type; cycles here when several share it"),
    "gui.rs_create_compat.sequence_execution_chamber.config.chain.head.tip": (
        "链首 = 沿朝向走到的那台；链上不能只改一台。",
        "Head = chamber along facing; edits apply to the chain."),
    "gui.rs_create_compat.sequence_execution_chamber.config.confirm.tip": (
        "发给服务端保存；服务端校验后回发权威值。",
        "Sent to the server; its reply is authoritative."),
    "gui.rs_create_compat.sequence_execution_chamber.face.button.tip": (
        "逐面设置「原料输入 / 产物输出 / 中间产物输出」",
        "Set per face: raw input / product output / intermediate output"),
    "gui.rs_create_compat.sequence_execution_chamber.output.bus.tip": (
        "总线输出：忽略逐面配置，由相连的输出总线代劳。",
        "Bus output: per-face settings are ignored; a connected exporter bus does the work."),
    "gui.rs_create_compat.sequence_execution_chamber.output.cell.ignored": (
        "「总线输出」下该面配置被忽略（切回面输出恢复）。",
        "In Bus Output mode this face config is ignored (returns in Face Output)."),
    # ---------------- 定量保持器 / 监视器 / 总线配置 ----------------
    "gui.rs_create_compat.advanced_quantity_keeper.blocked.tooltip": (
        "本行资源与标记不符，已停止输出；资源不销毁。",
        "This row holds mismatched resources; output stopped, nothing destroyed."),
    "gui.rs_create_compat.quantity_keeper.blocked.tooltip": (
        "资源与标记不符，已停止输出；资源不销毁。",
        "Mismatched resources present; output stopped, nothing destroyed."),
    "gui.rs_create_compat.assembly.monitor.suspended.tip": (
        "已挂起：不占用执行器与原料；到监视器点「继续」才恢复",
        "Suspended: no executor occupied; click Resume in the monitor to continue"),
    "gui.rs_create_compat.bus_config.mode.auto.tip": (
        "当前自动收回：输入类不收、其余自动收；勾选在切手动后生效",
        "Auto reclaim: inputs are protected, the rest is reclaimed"),
}


def read(name):
    with io.open(os.path.join(LANG_DIR, name), "r", encoding="utf-8") as handle:
        return json.load(handle)


def write(name, data):
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    json.loads(text)  # 写盘前自校验
    with io.open(os.path.join(LANG_DIR, name), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def main():
    zh = read("zh_cn.json")
    en = read("en_us.json")
    problems = []

    # 1) 键集合成对（本脚本只改值，不增删键）
    if set(zh) != set(en):
        problems.append("中英键集合不一致：仅中 %s / 仅英 %s"
                        % (sorted(set(zh) - set(en))[:5], sorted(set(en) - set(zh))[:5]))

    changed = 0
    for key, (new_zh, new_en) in CHANGES.items():
        if key not in zh or key not in en:
            problems.append("键不存在：%s" % key)
            continue
        old_zh, old_en = zh[key], en[key]
        # 2) 占位符数量不得变（改了变量文案就废）
        if len(PLACEHOLDER.findall(old_zh)) != len(PLACEHOLDER.findall(new_zh)) \
                or len(PLACEHOLDER.findall(old_en)) != len(PLACEHOLDER.findall(new_en)):
            problems.append("占位符数量改变：%s" % key)
            continue
        # 3) 基本排版
        for label, value in (("zh", new_zh), ("en", new_en)):
            if value != value.strip():
                problems.append("%s 值首尾有空白：%s" % (label, key))
            if "  " in value:
                problems.append("%s 值含连续空格：%s" % (label, key))
        if len(new_zh) > 40:
            problems.append("中文超 40 字（%d）：%s" % (len(new_zh), key))
        # 4) 只许变短（本轮是「再收一遍」）
        if len(new_zh) > len(old_zh) or len(new_en) > len(old_en):
            problems.append("未变短：%s（zh %d->%d / en %d->%d）"
                            % (key, len(old_zh), len(new_zh), len(old_en), len(new_en)))
            continue
        zh[key], en[key] = new_zh, new_en
        changed += 1
        print("  %-72s zh %2d->%2d  en %3d->%3d" % (key, len(old_zh), len(new_zh),
                                                    len(old_en), len(new_en)))

    if problems:
        print("\n[FAIL] 未写盘，问题：")
        for one in problems:
            print("  - %s" % one)
        return 1

    write("zh_cn.json", zh)
    write("en_us.json", en)

    # 5) 写盘后复核
    zh2, en2 = read("zh_cn.json"), read("en_us.json")
    if set(zh2) != set(en2):
        print("[FAIL] 写盘后中英键集合不一致")
        return 1
    bad = [k for k in CHANGES if zh2[k] != CHANGES[k][0] or en2[k] != CHANGES[k][1]]
    if bad:
        print("[FAIL] 写盘后比对失败：%s" % bad)
        return 1
    print("=" * 60)
    print("[OK] 共收紧 %d 条提示（中英成对、占位符一致、均变短），当前键数 zh %d / en %d"
          % (changed, len(zh2), len(en2)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
