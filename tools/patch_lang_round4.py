# -*- coding: utf-8 -*-
"""Round4 语言键追加（中英同步）。

用法：python tools/patch_lang_round4.py
覆盖三块需求：
  A  归流缓存仓「阻塞」；
  B  序列装配样板终端（卡片顺序 / 总样板生成槽 / 汇总入口）；
  C  序列执行仓（面配置、汇总子界面）。

说明：语言 json 一律通过脚本写入（不手写整份 json），保证 UTF-8 + 无尾逗号 + 键顺序稳定。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

ZH = {
    # ===== A 归流缓存仓：阻塞 =====
    "gui.rs_create_compat.collection_cache.blocked.on": "状态：已阻塞（不再回流进网络）",
    "gui.rs_create_compat.collection_cache.blocked.off": "状态：未阻塞（正常回流进网络）",
    "gui.rs_create_compat.collection_cache.blocked.hint.on": "被阻塞的资源仍可被输入 / 取出，不会被销毁；Ctrl+左键可解除阻塞",
    "gui.rs_create_compat.collection_cache.blocked.hint.off": "Ctrl+左键：切换该资源的阻塞状态（阻止它回流进网络）",
    "gui.rs_create_compat.collection_cache.blocked.hint.band": "Ctrl+左键点击格子：切换该资源的阻塞状态；被阻塞的格子显示红色边框",

    # ===== B 序列装配样板终端 =====
    "gui.rs_create_compat.sequence_pattern_terminal.card.move.hint": "Shift+左键 / Shift+右键：把该步上移 / 下移一格",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot_full": "生成槽里还有一张未取走的样板：请先取走再生成",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot.take": "取走即可放进序列装配样板库（一格一张）",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot.empty": "生成槽：点「生成样板」后总样板会落在这里",
    "gui.rs_create_compat.sequence_pattern_terminal.library.summary.tip":
        "左键标题：打开「执行仓单元样板汇总」（汇总各执行仓的单元样板，可直接取回 / 存入）",

    # ===== C 序列执行仓：面配置 =====
    "gui.rs_create_compat.sequence_execution_chamber.face.button": "面",
    "gui.rs_create_compat.sequence_execution_chamber.face.button.tip":
        "打开面配置：逐面设置「原料输入 / 产物输出 / 中间产物输出」",
    "gui.rs_create_compat.sequence_execution_chamber.face.title": "序列执行仓 · 面配置",
    "gui.rs_create_compat.sequence_execution_chamber.face.close": "关闭",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.up": "上",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.down": "下",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.north": "北",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.south": "南",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.west": "西",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.east": "东",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.none": "无",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.input": "原料输入",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.output": "产物输出",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.intermediate": "中间产物输出",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.none.tip":
        "该面不参与自动交互，也不对外暴露物流能力。",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.input.tip":
        "本仓把认领到的原料 / 过渡件从该面推给相邻机器。",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.output.tip":
        "本仓从该面相邻容器抽出产物存入内部存储；该面同时对外暴露「只出不进」的物流能力。",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.intermediate.tip":
        "本仓从该面相邻容器抽出过渡件并直接回写网络，交给下一个执行仓认领。",
    "gui.rs_create_compat.sequence_execution_chamber.face.current": "当前：%s",
    "gui.rs_create_compat.sequence_execution_chamber.face.click": "左键 = 下一个模式，右键 = 上一个模式",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.1": "原料输入面：把原料喂给该面的相邻机器。",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.2": "产物输出面：从该面收回产物（存入本仓，可被管道抽走）。",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.3": "中间产物输出面：从该面收回过渡件并回写网络。",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.tip":
        "面配置说明：颜色即模式；点击方块面循环切换，保存由服务端权威校验",

    # ===== C 序列执行仓：单元样板汇总子界面 =====
    "gui.rs_create_compat.sequence_pattern_terminal.summary.title": "执行仓单元样板汇总",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.count": "（%s 台执行仓）",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.empty": "当前网络内没有已接入的序列执行仓",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.no_unit": "无单元样板",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.units": "单元样板：%s 张",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.row.tip":
        "超过 4 张时会轮询显示；配方类型不匹配的样板无法存入该仓",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.tip":
        "取回：把该执行仓的单元样板全部搬回本终端的单元样板库",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.push.tip":
        "存入：把库中配方类型匹配的单元样板放进该执行仓",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.none": "该执行仓里没有可取的单元样板",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.push.none":
        "没有与该仓配方类型匹配的单元样板（或该仓已满）",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.close": "关闭",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.page": "第 %s/%s 页",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.poll": "样板较多时按轮询展示",
}

EN = {
    "gui.rs_create_compat.collection_cache.blocked.on": "Status: blocked (no longer returned to the network)",
    "gui.rs_create_compat.collection_cache.blocked.off": "Status: not blocked (returned to the network normally)",
    "gui.rs_create_compat.collection_cache.blocked.hint.on":
        "Blocked resources can still be inserted/extracted and are never destroyed; Ctrl+Left-click to unblock",
    "gui.rs_create_compat.collection_cache.blocked.hint.off":
        "Ctrl+Left-click: toggle blocking for this resource (stop it from returning to the network)",
    "gui.rs_create_compat.collection_cache.blocked.hint.band":
        "Ctrl+Left-click a slot: toggle blocking for that resource; blocked slots get a red frame",

    "gui.rs_create_compat.sequence_pattern_terminal.card.move.hint":
        "Shift+Left-click / Shift+Right-click: move this step up / down by one",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot_full":
        "The output slot still holds a pattern: take it out before generating again",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot.take":
        "Take it and place it into a Sequence Assembly Pattern Library (one per slot)",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot.empty":
        "Output slot: the generated total pattern lands here",
    "gui.rs_create_compat.sequence_pattern_terminal.library.summary.tip":
        "Left-click the title: open the chamber unit-pattern summary (take out / put in directly)",

    "gui.rs_create_compat.sequence_execution_chamber.face.button": "F",
    "gui.rs_create_compat.sequence_execution_chamber.face.button.tip":
        "Open face configuration: set raw-input / product-output / intermediate-output per face",
    "gui.rs_create_compat.sequence_execution_chamber.face.title": "Sequence Execution Chamber · Faces",
    "gui.rs_create_compat.sequence_execution_chamber.face.close": "Close",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.up": "Up",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.down": "Down",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.north": "North",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.south": "South",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.west": "West",
    "gui.rs_create_compat.sequence_execution_chamber.face.dir.east": "East",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.none": "None",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.input": "Raw input",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.output": "Product output",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.intermediate": "Intermediate output",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.none.tip":
        "This face takes no part in automation and exposes no logistics capability.",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.input.tip":
        "Claimed raw materials / intermediates are pushed to the adjacent machine through this face.",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.output.tip":
        "Products are pulled from the adjacent container through this face into internal storage; "
        "this face also exposes extract-only logistics.",
    "gui.rs_create_compat.sequence_execution_chamber.face.mode.intermediate.tip":
        "Intermediates are pulled from the adjacent container through this face and written back to the network.",
    "gui.rs_create_compat.sequence_execution_chamber.face.current": "Current: %s",
    "gui.rs_create_compat.sequence_execution_chamber.face.click":
        "Left-click = next mode, Right-click = previous mode",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.1":
        "Raw input face: feed raw materials to the adjacent machine.",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.2":
        "Product output face: collect products into this chamber (pipes may extract them).",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.3":
        "Intermediate output face: collect intermediates and return them to the network.",
    "gui.rs_create_compat.sequence_execution_chamber.face.hint.tip":
        "Face configuration: colour = mode; click a face to cycle (saved server-side)",

    "gui.rs_create_compat.sequence_pattern_terminal.summary.title": "Chamber unit-pattern summary",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.count": "(%s chambers)",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.empty": "No sequence execution chamber in this network",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.no_unit": "No unit pattern",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.units": "Unit patterns: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.row.tip":
        "More than 4 patterns rotate over time; patterns of another recipe type cannot be stored here",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.tip":
        "Take out: move all unit patterns of that chamber back into this terminal's library",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.push.tip":
        "Put in: move library patterns whose recipe type matches that chamber into it",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.none":
        "That chamber has no unit pattern to take out",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.push.none":
        "No library pattern matches that chamber's recipe type (or the chamber is full)",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.close": "Close",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.page": "Page %s/%s",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.poll": "Patterns rotate when there are more than shown",
}


def patch(filename, table):
    path = os.path.join(LANG_DIR, filename)
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    added = 0
    for key, value in table.items():
        if key not in data:
            added += 1
        data[key] = value
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("%s: 新增 %d 键，覆盖后共 %d 键" % (filename, added, len(data)))


def main():
    patch("zh_cn.json", ZH)
    patch("en_us.json", EN)


if __name__ == "__main__":
    main()
