# -*- coding: utf-8 -*-
"""精简 zh_cn / en_us 语言文件里过长的描述文本。

设计要点
--------
* 只用「键 -> (新中文, 新英文)」映射；逐行定位该键所在行，**只替换值部分**，
  保持文件的行序、缩进、行尾、其余行一字不动。
* 幂等：重复执行结果一致。
* 占位符（%s / %1$s）由人工保证不变，另有 tools/verify_lang_simplify.py 校验。
* 写回后用 json.load 复核语法，并打印每条的新长度 + 是否超出理想目标（zh 45 / en 95）。

用法：python tools/simplify_lang_texts.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

IDEAL_ZH, HARD_ZH = 45, 55
IDEAL_EN, HARD_EN = 95, 110

# ---------------------------------------------------------------------------
# 键 -> (中文新值, 英文新值)
# ---------------------------------------------------------------------------
NEW = {
    # ===== 方块说明（.help）=====
    "block.rs_create_compat.sequence_execution_chamber.help": (
        "紧贴 Create 机放置（朝向机器侧）。右键放入/取出单元样板（6 格）。自动为机器供料，产物回网。",
        "Place touching a Create machine (facing it). Right-click to add/take unit patterns. Feeds it; products return.",
    ),
    "block.rs_create_compat.collection_cache.help": (
        "接入 RS 网络后吸取周围掉落物与流体/气体。匹配区用 ghost 标记指定收集对象，装不下的暂存并持续回流。",
        "Absorbs nearby drops, fluids and gases once networked. Ghost markers set what to collect; surplus is buffered.",
    ),
    "block.rs_create_compat.sequence_assembly_executor.help": (
        "序列装配样板库：放入总样板并接入网络，即注册为自动合成样板。",
        "Pattern Vault: add a master pattern and link a network to register an autocrafting pattern.",
    ),
    "block.rs_create_compat.quantity_keeper.help": (
        "标记对象并维持数量：低于自动合成，高于自动销毁；需接入 RS 网络 + 升级。",
        "Marks an object and holds its amount: autocraft below, destroy above; needs network + upgrade.",
    ),
    "block.rs_create_compat.advanced_quantity_keeper.help": (
        "把 4 个定量保持器合一：4 槽各自设定物品/流体/气体目标数量；其它资源绝不销毁。",
        "Fuses 4 keepers: 4 slots, each with an item/fluid/gas target; other resources are never voided.",
    ),
    "block.rs_create_compat.schematic_loader.help": (
        "紧贴 Create 蓝图加农炮放置，自动补充其所需资源；多个装填器相连协同供物。",
        "Place next to a Create Schematicannon to supply it from the network; loaders share supply.",
    ),
    "block.rs_create_compat.advanced_schematic_loader.help": (
        "容量 108 格，内置蓝图队列，按顺序自动打印。流程：触发加农炮→获取资源→打印→回收空白蓝图→下一张。",
        "108 slots with a blueprint queue; prints blueprints in order: trigger cannon, gather, print, recycle, next.",
    ),
    "block.rs_create_compat.range_charger.help": (
        "接入 RS 网络或 FE 能量，为范围内方块与掉落物充电；右键打开界面调整范围。",
        "Charges blocks and dropped items in range using RS or FE power; right-click to set range.",
    ),
    "block.rs_create_compat.unit_pattern_manager.help": (
        "只管理单元样板：按执行舱分组，另含终端旧库（只出不进）；总样板在自动合成管理舱。",
        "Unit patterns only, grouped per chamber (+ legacy library); master patterns are in the autocrafter manager.",
    ),
    "block.rs_create_compat.sequence_pattern_terminal.help": (
        "把 Create 序列装配接入 RS 自动合成：导入配方；流程/产物/废料只读。",
        "Sequenced assembly for RS autocrafting: import a recipe; flow, results, scraps are read-only.",
    ),
    # ===== 界面提示 =====
    "gui.rs_create_compat.sequence_execution_chamber.hint": (
        "右键本仓打开界面，放入/取出单元样板。需先设定配方类型 + 名字，类型不符会被拒绝。",
        "Right-click to insert/take unit patterns. Set recipe type and name first; other types refused.",
    ),
    "gui.rs_create_compat.quantity_keeper.marker_tooltip": (
        "放入物品或从 JEI 拖入流体作为标记（不消耗），指定要保持的对象。",
        "Place an item or drag a fluid from JEI as the marker (not consumed); it defines what to keep.",
    ),
    "gui.rs_create_compat.quantity_keeper.blocked.tooltip": (
        "内部存在与标记不匹配的资源，已停止输出。资源不会被销毁，仍可被玩家或管道取出。",
        "Keeper holds mismatched resources; output stopped, nothing is destroyed, they stay extractable.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.marker_tooltip": (
        "放入物品或拖入流体/气体作为本行标记（不消耗）；只允许已标记的类型。",
        "Place an item or drag a fluid/gas as this row's marker (not consumed); marked types only.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.blocked.tooltip": (
        "本行存在与标记不匹配的资源，已停止输出。资源不会被销毁，仍可被取出。",
        "This row holds mismatched resources; output stopped. Nothing is destroyed, all extractable.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_no_upgrade.tip": (
        "未安装「自动合成升级」，本机无法自动合成：先放入升级，再用本按钮按槽开关。",
        "No Autocrafting Upgrade installed: insert one, then toggle it per slot with this button.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_header.tip": (
        "自动合成列：点 ✓/✗ 切换该行是否自动合成（需先装自动合成升级）。",
        "Autocraft column: click a row's check/cross to toggle autocrafting (needs the upgrade).",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.overflow_header.tip": (
        "过量销毁列：点某行的 ✓/✗ 切换该行超出目标的数量是否销毁。",
        "Destroy-overflow column: click a row's check/cross to destroy amounts beyond its target.",
    ),
    "gui.rs_create_compat.step_detail.step.tip": (
        "本次编辑作用于第几步（1..%s）。不在窗口内的步只写机器绑定。",
        "Which step this edit applies to (1..%s). Off-screen steps write only the machine binding.",
    ),
    "gui.rs_create_compat.sequence_pattern_terminal.summary.slot.tip": (
        "左键取出 / Shift+左键移动 / 拖拽放置（只收本仓配方类型的单元样板）",
        "Left-click take / Shift+click move / drag to place (unit patterns only; matching recipe type)",
    ),
    "gui.rs_create_compat.sequence_assembly_executor.hint2": (
        "从生成槽拿起总样板，放入上方格内（一格一张，破坏方块会掉落实物）",
        "Take a generated master pattern into a grid slot above (one per slot, dropped when broken).",
    ),
    "gui.rs_create_compat.sequence_execution_chamber.output.bus.tip": (
        "总线输出：忽略逐面配置，由紧贴或线缆相连的「输出总线」代劳。",
        "Bus output: per-face settings are ignored; an exporter bus next to the chamber does the work.",
    ),
    "gui.rs_create_compat.sequence_execution_chamber.chain.tip.entry": (
        "设置链指向：点界面上方「配置」，在子界面底部「链指向」里选方向。",
        "To set the link: press Config above, then pick a direction under Chain link at the bottom.",
    ),
    "gui.rs_create_compat.unit_pattern_config.requires_input.tip": (
        "切换该单元样板是否需要输入原料；选「是」时终端输入槽的物品即为输入物。",
        "Toggle whether this unit pattern needs an input; when ON the terminal input slot item is used.",
    ),
    # ===== 输出总线（exporter executor）=====
    "gui.rs_create_compat.exporter_executor.fluids.tip": (
        "导出该类流体：从执行仓内部存储推给本总线面对的机器；多台总线按轮询均分。",
        "Exports this fluid category from the chamber's storage to its faced machine, round-robin.",
    ),
    "gui.rs_create_compat.exporter_executor.intermediates.tip": (
        "导出过渡件：从执行仓内部存储推给本总线面对的机器，多台总线轮询均分。",
        "Exports transitional items from the chamber's storage to its faced machine, round-robin.",
    ),
    "gui.rs_create_compat.exporter_executor.inputs.tip": (
        "导出该类输入原料：从执行仓内部存储推给本总线面对的机器；多台总线按轮询均分。",
        "Exports this input category from the chamber's storage to its faced machine, round-robin.",
    ),
    "gui.rs_create_compat.exporter_executor.locked.tip": (
        "已连到处于「总线输出」模式的执行仓（≤64 格）。类别由单元样板给出，可多选；过滤器锁定。",
        "Linked to a chamber in Bus Output mode (up to 64 blocks); patterns give categories, filters locked.",
    ),
    "gui.rs_create_compat.exporter_executor.shared.tip": (
        "由 %s 台总线共享：按轮询均分，不丢失也不复制（青色方块标记）。",
        "Shared by %s buses: split round-robin, nothing lost or duplicated (cyan square marks it).",
    ),
    "gui.rs_create_compat.exporter_executor.exclusive.tip": (
        "仅本总线选中该类别（独占）。别的总线也选中它即可改为均分。",
        "Only this bus has this category (exclusive); select it elsewhere to share round-robin.",
    ),
    "gui.rs_create_compat.exporter_executor.estimated.note": (
        "因概率原因，实际消耗可能超出预估（会持续供应直到目标产物达标）。",
        "Due to chance, actual use may exceed this estimate (supply runs until the target is reached).",
    ),
    # ===== 高级远程终端 =====
    "item.rs_create_compat.advanced_remote_terminal.usage": (
        "右键打开；模式由右下角图标切换：合成终端/样板终端/合成仓管理/合成仓监视/序列装配",
        "Right-click to open; bottom-right icons switch modes: Grid/Patterns/Manager/Monitor/Sequence",
    ),
    "item.rs_create_compat.advanced_remote_terminal.bind_hint": (
        "对准无线信号发射器 / RS 网络节点方块右键绑定（支持跨维度增强无线访问点）",
        "Right-click a Wireless Transmitter / RS network node to bind (cross-dimensional access)",
    ),
    # ===== 命令与聊天消息 =====
    "message.rs_create_compat.autocrafter_storage.usage": (
        "用法：/rs_create_compat autocrafter storage <on|off>（32 格）",
        "Usage: /rs_create_compat autocrafter storage <on|off> (within 32 blocks)",
    ),
    "message.rs_create_compat.autocrafter_storage.disabled": (
        "已关闭自动合成仓内部存储（共 %s 台），仓内物品与流体已写回网络。",
        "Autocrafter storage disabled (%s); stored items and fluids were returned to the network.",
    ),
    "message.rs_create_compat.autocrafter_storage.disable_failed": (
        "网络空间不足或网络不可用，已取消关闭：物品与流体仍安全保存在自动合成仓内，没有丢失。",
        "Network storage is full or offline, so the toggle was cancelled; items and fluids stay inside.",
    ),
    "message.rs_create_compat.autocrafter_storage.disabled_by_config": (
        "服务端配置已禁用自动合成仓内部存储（autocrafterStorageEnabled = false）。",
        "Autocrafter storage is disabled by the server config (autocrafterStorageEnabled = false).",
    ),
}


def split_line(line):
    """返回 (key, value_prefix_end, has_comma, ending)；不是键值行返回 (None, None, False, ending)。"""
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    stripped = body.lstrip()
    if not stripped.startswith('"') or '":' not in stripped:
        return None, None, False, ending
    offset = len(body) - len(stripped)
    quote = body.index('":', offset)
    key = body[offset + 1:quote]
    return key, quote + 1, body.endswith(","), ending


def patch(path, index):
    with io.open(path, encoding="utf-8", newline="") as f:
        lines = f.readlines()
    replaced = 0
    for i, line in enumerate(lines):
        key, cut, comma, ending = split_line(line)
        if key is None or key not in NEW:
            continue
        value = NEW[key][index]
        lines[i] = "%s: %s%s%s" % (line[:cut], json.dumps(value, ensure_ascii=False),
                                   "," if comma else "", ending)
        replaced += 1
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.writelines(lines)
    # 复核语法
    with io.open(path, encoding="utf-8") as f:
        loaded = json.load(f)
    assert len(loaded) == len(lines) - 2, "键数量与行数不匹配：%s" % path
    return replaced, loaded


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")

    with io.open(zh_path, encoding="utf-8") as f:
        before_keys = set(json.load(f))
    missing = sorted(set(NEW) - before_keys)
    if missing:
        raise SystemExit("映射里有文件中不存在的键：%s" % missing)

    n_zh, zh = patch(zh_path, 0)
    n_en, en = patch(en_path, 1)
    print("zh_cn.json 替换 %d 行；en_us.json 替换 %d 行" % (n_zh, n_en))

    rows = sorted(((len(zh[k]), len(en[k]), k) for k in NEW), reverse=True)
    print("\n%4s %4s  %s" % ("zh", "en", "key"))
    over, relax = [], []
    for lz, le, key in rows:
        flag = ""
        if lz > HARD_ZH or le > HARD_EN:
            flag = "  <== 超硬上限"
            over.append((key, lz, le))
        elif lz > IDEAL_ZH or le > IDEAL_EN:
            flag = "  (放行)"
            relax.append((key, lz, le))
        print("%4d %4d  %s%s" % (lz, le, key, flag))
    print("\n超出硬上限(zh %d / en %d) %d 条；放行(理想 zh %d / en %d) %d 条"
          % (HARD_ZH, HARD_EN, len(over), IDEAL_ZH, IDEAL_EN, len(relax)))


if __name__ == "__main__":
    main()
