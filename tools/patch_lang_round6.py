# -*- coding: utf-8 -*-
"""Round4 追加（第三轮）语言键：中英同步 + 删除已废弃的自动合成仓界面按钮键。

用法：python tools/patch_lang_round6.py
覆盖三块改动：
  1. 自动合成仓「内部存储」由界面按钮改为无权限指令（删除 gui.*.autocrafter_storage.*，新增用法提示键）；
  2. 序列执行仓新增「输出模式」：面输出 / 总线输出（延长型输出）——面配置子界面新增按钮与提示；
  3. 输出总线「延长型输出」界面：两个类别开关（输出原料 / 输出中间产物）+ 锁定说明；
  4. 归流缓存仓新增「输入面配置」子界面（多个输入面）。

说明：语言 json 一律通过脚本写入（不手写整份 json），保证 UTF-8 + 无尾逗号 + 既有键顺序不变。
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

REMOVED = [
    "gui.rs_create_compat.autocrafter_storage.button.on",
    "gui.rs_create_compat.autocrafter_storage.button.off",
    "gui.rs_create_compat.autocrafter_storage.title",
    "gui.rs_create_compat.autocrafter_storage.state.on",
    "gui.rs_create_compat.autocrafter_storage.state.off",
    "gui.rs_create_compat.autocrafter_storage.help",
]

ZH = {
    # ===== 1. 自动合成仓：指令用法提示（开关按钮已删除） =====
    "message.rs_create_compat.autocrafter_storage.usage":
        "用法：/rs_create_compat autocrafter storage <on|off>（作用范围为执行者周围 32 格内的自动合成仓）",

    # ===== 2. 序列执行仓：输出模式（面输出 / 总线输出） =====
    "gui.rs_create_compat.sequence_execution_chamber.output.button": "输出：%s",
    "gui.rs_create_compat.sequence_execution_chamber.output.face": "面输出",
    "gui.rs_create_compat.sequence_execution_chamber.output.bus": "总线输出",
    "gui.rs_create_compat.sequence_execution_chamber.output.ignored": "面配置已忽略（总线输出）",
    "gui.rs_create_compat.sequence_execution_chamber.output.tip.title": "输出模式",
    "gui.rs_create_compat.sequence_execution_chamber.output.face.tip":
        "面输出（默认）：按左侧六个面的配置工作——原料从「原料输入面」推给相邻机器，"
        "产物 / 中间产物从对应面收回来。",
    "gui.rs_create_compat.sequence_execution_chamber.output.bus.tip":
        "总线输出（延长型输出）：忽略逐面配置，本机不做面 I/O；"
        "相邻的「输出总线」会把网络里的「输入原料 / 中间产物」推给它面对的机器。",
    "gui.rs_create_compat.sequence_execution_chamber.output.tip.switch": "点击按钮切换（左键循环）",
    "gui.rs_create_compat.sequence_execution_chamber.output.cell.ignored":
        "注意：当前为「总线输出」模式，该面的配置被整体忽略（切回「面输出」后立即恢复生效）。",

    # ===== 3. 输出总线「延长型输出」界面 =====
    "gui.rs_create_compat.exporter_executor.inputs.name": "输出原料",
    "gui.rs_create_compat.exporter_executor.inputs.tip":
        "导出「输入原料」：把网络上序列执行仓各单元样板的加工输入物推给本总线面对的机器。",
    "gui.rs_create_compat.exporter_executor.intermediates.name": "输出中间产物",
    "gui.rs_create_compat.exporter_executor.intermediates.tip":
        "导出「中间产物」：把网络上序列装配的过渡件（带进度组件的未完成件）推给本总线面对的机器。",
    "gui.rs_create_compat.exporter_executor.state.on": "状态：开",
    "gui.rs_create_compat.exporter_executor.state.off": "状态：关",
    "gui.rs_create_compat.exporter_executor.click": "左键：切换该类别是否导出",
    "gui.rs_create_compat.exporter_executor.locked": "过滤器槽位已锁定",
    "gui.rs_create_compat.exporter_executor.locked.tip":
        "本输出总线相邻着处于「总线输出」模式的序列执行仓：导出清单由执行仓决定，"
        "原来的过滤器槽位不可编辑（把执行仓切回「面输出」即可恢复）。",

    # ===== 4. 归流缓存仓：输入面配置（多个输入） =====
    "gui.rs_create_compat.collection_cache.input_face": "输入面",
    "gui.rs_create_compat.collection_cache.input_face.tip":
        "配置哪些面可以放进物品 / 流体（漏斗、管道等物流方块）；已进入仓内的资源不受影响。",
    "gui.rs_create_compat.collection_cache.input_face.state": "当前允许输入的面：%s / 6",
    "gui.rs_create_compat.collection_cache.input_face.title": "归流缓存仓 · 输入面配置",
    "gui.rs_create_compat.collection_cache.input_face.close": "关闭",
    "gui.rs_create_compat.collection_cache.input_face.dir.up": "上",
    "gui.rs_create_compat.collection_cache.input_face.dir.down": "下",
    "gui.rs_create_compat.collection_cache.input_face.dir.north": "北",
    "gui.rs_create_compat.collection_cache.input_face.dir.south": "南",
    "gui.rs_create_compat.collection_cache.input_face.dir.west": "西",
    "gui.rs_create_compat.collection_cache.input_face.dir.east": "东",
    "gui.rs_create_compat.collection_cache.input_face.on": "可输入",
    "gui.rs_create_compat.collection_cache.input_face.off": "已关闭",
    "gui.rs_create_compat.collection_cache.input_face.current": "当前：%s",
    "gui.rs_create_compat.collection_cache.input_face.state.on.tip":
        "该面允许漏斗 / 管道把物品与流体塞进本仓。",
    "gui.rs_create_compat.collection_cache.input_face.state.off.tip":
        "该面不接收输入；但仍可从该面抽走仓内资源（不会困住任何东西）。",
    "gui.rs_create_compat.collection_cache.input_face.click": "左键 / 右键：切换该面",
    "gui.rs_create_compat.collection_cache.input_face.hint.1":
        "绿色面：允许物品 / 流体从该面进入本仓。",
    "gui.rs_create_compat.collection_cache.input_face.hint.2":
        "灰色面：不接收输入，已进仓的资源仍可从任意面抽走。",
    "gui.rs_create_compat.collection_cache.input_face.hint.3":
        "本仓始终照常收集世界掉落物并回流 RS 网络。",
    "gui.rs_create_compat.collection_cache.input_face.hint.tip":
        "输入面说明：颜色即状态；点击方块面切换，保存由服务端权威生效",
}

EN = {
    "message.rs_create_compat.autocrafter_storage.usage":
        "Usage: /rs_create_compat autocrafter storage <on|off> "
        "(applies to autocrafters within 32 blocks of the executor)",

    "gui.rs_create_compat.sequence_execution_chamber.output.button": "Output: %s",
    "gui.rs_create_compat.sequence_execution_chamber.output.face": "Face output",
    "gui.rs_create_compat.sequence_execution_chamber.output.bus": "Bus output",
    "gui.rs_create_compat.sequence_execution_chamber.output.ignored": "Face config ignored (bus output)",
    "gui.rs_create_compat.sequence_execution_chamber.output.tip.title": "Output mode",
    "gui.rs_create_compat.sequence_execution_chamber.output.face.tip":
        "Face output (default): works per the six faces on the left - inputs are pushed to the adjacent "
        "machine from the input face, products/intermediates are collected from their faces.",
    "gui.rs_create_compat.sequence_execution_chamber.output.bus.tip":
        "Bus output (extended output): all per-face config is ignored and this block does no face I/O; "
        "an adjacent Exporter pushes the network's input materials / intermediates into the machine it faces.",
    "gui.rs_create_compat.sequence_execution_chamber.output.tip.switch": "Click to switch (left-click cycles)",
    "gui.rs_create_compat.sequence_execution_chamber.output.cell.ignored":
        "Note: bus output is active, so this face config is ignored (it takes effect again in face output mode).",

    "gui.rs_create_compat.exporter_executor.inputs.name": "Export inputs",
    "gui.rs_create_compat.exporter_executor.inputs.tip":
        "Export input materials: pushes the crafting inputs of the sequence chamber's unit patterns "
        "into the machine this bus faces.",
    "gui.rs_create_compat.exporter_executor.intermediates.name": "Export intermediates",
    "gui.rs_create_compat.exporter_executor.intermediates.tip":
        "Export intermediates: pushes the sequenced-assembly transitional items (with progress components) "
        "into the machine this bus faces.",
    "gui.rs_create_compat.exporter_executor.state.on": "State: ON",
    "gui.rs_create_compat.exporter_executor.state.off": "State: OFF",
    "gui.rs_create_compat.exporter_executor.click": "Left-click: toggle this category",
    "gui.rs_create_compat.exporter_executor.locked": "Filter slots are locked",
    "gui.rs_create_compat.exporter_executor.locked.tip":
        "This Exporter is adjacent to a sequence chamber in bus output mode: the export list is decided by "
        "that chamber and the filter slots cannot be edited (switch the chamber back to face output to restore).",

    "gui.rs_create_compat.collection_cache.input_face": "Input faces",
    "gui.rs_create_compat.collection_cache.input_face.tip":
        "Choose which faces accept items/fluids from logistics (hoppers, pipes); "
        "resources already inside are unaffected.",
    "gui.rs_create_compat.collection_cache.input_face.state": "Faces accepting input: %s / 6",
    "gui.rs_create_compat.collection_cache.input_face.title": "Collection Cache - Input Faces",
    "gui.rs_create_compat.collection_cache.input_face.close": "Close",
    "gui.rs_create_compat.collection_cache.input_face.dir.up": "Up",
    "gui.rs_create_compat.collection_cache.input_face.dir.down": "Down",
    "gui.rs_create_compat.collection_cache.input_face.dir.north": "North",
    "gui.rs_create_compat.collection_cache.input_face.dir.south": "South",
    "gui.rs_create_compat.collection_cache.input_face.dir.west": "West",
    "gui.rs_create_compat.collection_cache.input_face.dir.east": "East",
    "gui.rs_create_compat.collection_cache.input_face.on": "Accepts input",
    "gui.rs_create_compat.collection_cache.input_face.off": "Closed",
    "gui.rs_create_compat.collection_cache.input_face.current": "Current: %s",
    "gui.rs_create_compat.collection_cache.input_face.state.on.tip":
        "This face accepts items and fluids from hoppers/pipes.",
    "gui.rs_create_compat.collection_cache.input_face.state.off.tip":
        "This face refuses input; stored resources can still be extracted from it (nothing gets trapped).",
    "gui.rs_create_compat.collection_cache.input_face.click": "Left/right-click: toggle this face",
    "gui.rs_create_compat.collection_cache.input_face.hint.1":
        "Green face: items/fluids may enter the block from this side.",
    "gui.rs_create_compat.collection_cache.input_face.hint.2":
        "Grey face: refuses input; stored resources can still be extracted from any side.",
    "gui.rs_create_compat.collection_cache.input_face.hint.3":
        "The block still collects world drops and returns them to the RS network as usual.",
    "gui.rs_create_compat.collection_cache.input_face.hint.tip":
        "Input faces: colour equals state; click a face to toggle, saved server-side",
}


def patch(filename, table, removed):
    path = os.path.join(LANG_DIR, filename)
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    dropped = 0
    for key in removed:
        if key in data:
            del data[key]
            dropped += 1
    added = 0
    for key, value in table.items():
        if key not in data:
            added += 1
        data[key] = value
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("%s: 新增 %d 键 / 删除 %d 键，共 %d 键" % (filename, added, dropped, len(data)))


def main():
    patch("zh_cn.json", ZH, REMOVED)
    patch("en_us.json", EN, REMOVED)


if __name__ == "__main__":
    main()
