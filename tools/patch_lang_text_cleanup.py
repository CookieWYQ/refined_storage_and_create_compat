# -*- coding: utf-8 -*-
"""第 11 轮「全量文本自检」：精简提示 / 描述文案，并清掉孤儿键。

原则（用户原话）：说明「这个机器有什么功能」，不描述「会出现的现象」；参照精致存储（RS）
风格（简短、功能导向）；玩家一眼能看出来 / 常识性 / 不准确的一律删；尽可能精简，保留到能用。

做法
----
* `REWRITE`：键 -> (新中文, 新英文)，只换值；行序 / 缩进 / 行尾 / 其余行一字不动。
* `DELETE`：语言里存在但源码从不引用的孤儿键，中英两处同步删除（删完由 tools/audit_lang_keys.py 复核）。
* 写回后重新 json.load 复核语法，并打印键数变化 + 中文 > 40 字 / 英文 > 110 字符的残留。

用法：python tools/patch_lang_text_cleanup.py
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

# ---------------------------------------------------------------------------
# 改写：键 -> (新中文, 新英文)
# ---------------------------------------------------------------------------
REWRITE = {
    # ===== 方块说明（功能导向，删掉现象 / 常识 / 不准确表述）=====
    "block.rs_create_compat.range_charger.help": (
        "接入 RS 网络或 FE 能量，为范围内方块与掉落物充电。",
        "Charges blocks and dropped items in range from RS or FE power.",
    ),
    # 用户点名②：删「其它资源绝不销毁」；③：删「需接入 RS 网络加升级」。
    "block.rs_create_compat.quantity_keeper.help": (
        "标记资源并维持目标数量：不足自动合成，超出可设销毁。",
        "Marks a resource and holds its target: autocrafts when low, voids the excess when enabled.",
    ),
    "block.rs_create_compat.advanced_quantity_keeper.help": (
        "把 4 个定量保持器合一，4 槽各自设定物品/流体/气体目标数量。",
        "Fuses 4 keepers into one; each slot has its own item/fluid/gas target.",
    ),
    "block.rs_create_compat.schematic_loader.help": (
        "紧贴 Create 蓝图加农炮放置，自动供料；多台相连可协同供物。",
        "Place next to a Create Schematicannon to feed it; loaders can share supply.",
    ),
    "block.rs_create_compat.advanced_schematic_loader.help": (
        "内置蓝图队列，按顺序自动打印，并回收打印后的空白蓝图。",
        "Built-in blueprint queue: prints in order and recycles the empty blueprints.",
    ),
    "block.rs_create_compat.collection_cache.help": (
        "接入 RS 网络后吸取周围的掉落物、流体与气体，装不下的暂存并回流。",
        "Absorbs nearby drops, fluids and gases once networked; overflow is buffered and retried.",
    ),
    # 用户点名①：删「27 格盘位 = 一个小箱子大小」这类比喻，只说功能。
    "block.rs_create_compat.intermediate_cache.help": (
        "接入网络后，27 格盘位里的磁盘成为全网执行舱共用的中间产物缓存。",
        "Once networked, its 27 disk slots become a shared intermediate cache for every chamber.",
    ),
    "block.rs_create_compat.sequence_assembly_executor.help": (
        "放入总样板并接入网络，即注册为自动合成样板。",
        "Add a master pattern and link a network to register it as an autocrafting pattern.",
    ),
    "block.rs_create_compat.sequence_pattern_terminal.help": (
        "把 Create 序列装配接入 RS 自动合成：导入配方；流程/产物/废料只读。",
        "Sequenced assembly for RS autocrafting: import a recipe; the flow stays read-only.",
    ),
    # 用户点名④：不再写「紧贴 Create 机 / 朝向机器侧」（不准确），槽位改成真实的 54 格。
    "block.rs_create_compat.sequence_execution_chamber.help": (
        "紧贴机器放置。右键存取单元样板（54 格）；自动向机器供料并收回产物。",
        "Place against a machine; holds unit patterns (54 slots), feeds it and reclaims products.",
    ),
    "block.rs_create_compat.unit_pattern_manager.help": (
        "管理单元样板：按执行舱分组；总样板在自动合成管理舱。",
        "Manages unit patterns grouped per chamber; master patterns are in the autocrafter manager.",
    ),
    # 用户点名⑤：删「FTB 连锁键 + 右键套壳」。
    "block.rs_create_compat.camouflage_frame.help": (
        "右键裹上外壳；同种方块右键转向；潜行右键取下。",
        "Right-click to apply a shell; right-click the same block to rotate; sneak-right-click "
        "to remove.",
    ),
    # 用户点名⑧：删「按下 FTB 连锁键可成片套壳」。
    "block.rs_create_compat.separation_frame.help": (
        "右键套住线缆或流体管道；潜行右键取下。",
        "Right-click to frame a cable or fluid pipe; sneak-right-click to remove.",
    ),
    # 用户点名⑦：删「外壳带附魔光泽」（一眼可见）。
    "block.rs_create_compat.infinite_separation_frame.help": (
        "同分隔框架，但套上不消耗、取下不回收。",
        "Like the Separation Frame, but never consumed nor refunded.",
    ),
    # ===== 伪装框架提示（去掉举例 / 重复指引）=====
    "block.rs_create_compat.camouflage_frame.hint.full_only": (
        "外壳只能用完整方块",
        "The shell must be a full block",
    ),
    "block.rs_create_compat.camouflage_frame.hint.no_shell": (
        "这一格没有外壳方块",
        "No shell block here",
    ),
    "block.rs_create_compat.camouflage_frame.hint.occupied": (
        "已是这种外壳；换壳请先取下",
        "That shell is already applied; take it off to change it",
    ),
    "block.rs_create_compat.camouflage_frame.hint.pipe_only": (
        "只支持线缆与流体管道；其它方块需在配置开启",
        "Only cables and fluid pipes; other blocks need the config enabled",
    ),
    "block.rs_create_compat.camouflage_frame.hint.remove_hint": (
        "已裹着伪装；潜行右键取下",
        "Wrapped; sneak-right-click to take it off",
    ),
    "block.rs_create_compat.camouflage_frame.hint.rotated": (
        "外壳朝向已转 90°",
        "Shell rotated 90°",
    ),
    "block.rs_create_compat.camouflage_frame.hint.take_off_hint": (
        "已裹着伪装：潜行右键取下，扳手只取外壳。",
        "Wrapped: sneak-right-click to take it off (the wrench takes the shell only).",
    ),
    # ===== 物品说明 =====
    "item.rs_create_compat.universal_storage_disk.help": (
        "可同时存入物品、流体与气体。",
        "Stores items, fluids and gases at once.",
    ),
    "item.rs_create_compat.advanced_remote_terminal.usage": (
        "右键打开；右下角图标切换模式。",
        "Right-click to open; the bottom-right icons switch modes.",
    ),
    "item.rs_create_compat.advanced_remote_terminal.bind_hint": (
        "对准无线信号发射器或网络节点右键绑定。",
        "Right-click a Wireless Transmitter or a network node to bind.",
    ),
    "item.rs_create_compat.advanced_remote_terminal.not_found": (
        "没有找到终端（主手 / 背包 / 饰品槽）",
        "No terminal found (hand / inventory / Curios)",
    ),
    "item.rs_create_compat.advanced_remote_terminal.mode_unavailable": (
        "该模式暂不可用",
        "This mode is unavailable",
    ),
    "item.rs_create_compat.sequence_unit_pattern.edit_hint": (
        "可在样板终端编辑名字 / 配方类型 / 输入物",
        "Editable in the Pattern Terminal (name / recipe type / input)",
    ),
    "item.rs_create_compat.sequence_assembly_pattern.inputs_header": (
        "所需输入：",
        "Inputs:",
    ),
    # ===== 定量保持器 / 高级定量保持器界面 =====
    "gui.rs_create_compat.quantity_keeper.marker_tooltip": (
        "放入物品或拖入流体作为标记（不消耗）。",
        "Place an item or drag in a fluid as the marker (not consumed).",
    ),
    "gui.rs_create_compat.quantity_keeper.blocked.tooltip": (
        "存在与标记不匹配的资源，已停止输出；资源不会被销毁。",
        "Mismatched resources are present, so output stopped; nothing is destroyed.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.marker_tooltip": (
        "放入物品或拖入流体/气体作为本行标记（不消耗）。",
        "Place an item or drag in a fluid/gas as this row's marker (not consumed).",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.blocked.tooltip": (
        "本行存在与标记不匹配的资源，已停止输出；资源不会被销毁。",
        "This row holds mismatched resources; output stopped, nothing is destroyed.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_header.tip": (
        "点 ✓/✗ 切换该行是否自动合成（需装自动合成升级）。",
        "Click ✓/✗ to toggle autocrafting for this row (needs the upgrade).",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_no_upgrade.tip": (
        "需先放入自动合成升级，再按槽开关。",
        "Insert an autocrafting upgrade, then toggle it per slot.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.overflow_header.tip": (
        "点 ✓/✗ 切换该行超出目标的数量是否销毁。",
        "Click ✓/✗ to destroy or keep the amount above this row's target.",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.overflow_off.tip": (
        "已关闭：超出目标的部分保留（每槽独立）",
        "OFF: the surplus above the target is kept (per slot)",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.overflow_on.tip": (
        "已开启：超过目标数量的资源会被销毁（每槽独立）",
        "ON: resources above the target are destroyed (per slot)",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.target_tooltip": (
        "本行保持的目标数量（流体/气体按 mB）。",
        "Target amount for this row (fluids/gases in mB).",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_off.tip": (
        "本槽自动合成：已关闭（点击开启）",
        "Autocraft for this slot: OFF (click to enable)",
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_on.tip": (
        "本槽自动合成：已开启（需「自动合成升级」）",
        "Autocraft for this slot: ON (needs an autocrafting upgrade)",
    ),
    "gui.rs_create_compat.range_charger.rate.tip": (
        "基础 %s FE/t，每装 1 个速度升级翻倍",
        "Base %s FE/t, doubled per speed upgrade",
    ),
    # ===== 中间产物缓存仓 =====
    "gui.rs_create_compat.intermediate_cache.tip.accept": (
        "只接受 RS 存储磁盘；流体磁盘不计入物品空间。",
        "Storage disks only; fluid disks add no item space.",
    ),
    "gui.rs_create_compat.intermediate_cache.tip.capacity": (
        "容量全部来自插入的磁盘。",
        "All capacity comes from the inserted disks.",
    ),
    "gui.rs_create_compat.intermediate_cache.tip.carry": (
        "取盘即带走盘内物品；拆仓按「内容物去向」结算。",
        "Taking a disk takes its contents; breaking follows the block-content setting.",
    ),
    "gui.rs_create_compat.intermediate_cache.tip.share": (
        "本网络所有执行舱共用这些磁盘。",
        "Every chamber in this network shares these disks.",
    ),
    "gui.rs_create_compat.intermediate_cache.tip.slots": (
        "27 格盘位（3 行 × 9 列）；多块盘的容量合并。",
        "27 slots (3 x 9); the disks' capacities merge into one pool.",
    ),
    # ===== 归流缓存仓 =====
    "gui.rs_create_compat.collection_cache.duplicate": (
        "该物品已在匹配区标记过",
        "This item is already marked",
    ),
    "gui.rs_create_compat.collection_cache.match_tags.empty": (
        "该示例物没有标签",
        "This sample has no tags",
    ),
    # ===== 输出总线（执行器）=====
    "gui.rs_create_compat.exporter_executor.inputs.tip": (
        "从执行仓内部存储推给本总线面对的机器；多台总线轮询均分。",
        "Pushed from the chamber to the machine this bus faces.",
    ),
    "gui.rs_create_compat.exporter_executor.fluids.tip": (
        "从执行仓内部存储推给本总线面对的机器；多台总线轮询均分。",
        "Pushed from the chamber to the machine this bus faces.",
    ),
    "gui.rs_create_compat.exporter_executor.intermediates.tip": (
        "从执行仓内部存储推给本总线面对的机器；多台总线轮询均分。",
        "Pushed from the chamber to the machine this bus faces.",
    ),
    "gui.rs_create_compat.exporter_executor.locked.tip": (
        "总线输出模式：类别由样板给出，过滤器锁定。",
        "Linked to a Bus Output chamber; patterns set categories.",
    ),
    "gui.rs_create_compat.exporter_executor.shared.tip": (
        "由 %s 台总线轮询均分，不会丢失或复制。",
        "Shared by %s buses round-robin; nothing lost.",
    ),
    "gui.rs_create_compat.exporter_executor.exclusive.tip": (
        "仅本总线选中该类别；其它总线也选中即改为均分。",
        "Only this bus selected it; select it elsewhere to split.",
    ),
    "gui.rs_create_compat.exporter_executor.estimated.note": (
        "因概率原因，实际消耗可能超出预估。",
        "Chance means actual use may exceed the estimate.",
    ),
    "gui.rs_create_compat.exporter_executor.gate.off.tip": (
        "自动合成未开启时本总线不处理任何类别。",
        "With autocrafting off this bus handles nothing.",
    ),
    "gui.rs_create_compat.exporter_executor.source.tip": (
        "先把原料搬进执行仓，再按勾选类别推出。",
        "Materials move into the chamber first, then out.",
    ),
    "gui.rs_create_compat.exporter_executor.strategy.materials": (
        "只管输出原料（不看概率）",
        "Materials only (ignores chance)",
    ),
    "gui.rs_create_compat.exporter_executor.strategy.target": (
        "直到目标产物达标（缺料自动合成补齐）",
        "Until the target count is reached (auto-crafts missing)",
    ),
    # ===== 输入总线（执行器）=====
    "gui.rs_create_compat.importer_executor.inputs.tip": (
        "从执行仓内部存储收进网络；收不下的留在执行仓。",
        "Reclaimed from the chamber into the network.",
    ),
    "gui.rs_create_compat.importer_executor.fluids.tip": (
        "从执行仓内部存储收进网络；收不下的留在执行仓。",
        "Reclaimed from the chamber into the network.",
    ),
    "gui.rs_create_compat.importer_executor.intermediates.tip": (
        "从执行仓内部存储收进网络，供下一步取用。",
        "Reclaimed into the network for the next step.",
    ),
    "gui.rs_create_compat.importer_executor.auto.skip.tip": (
        "自动：输入类不收回（避免抽回刚喂进去的料）。",
        "Auto: inputs are not reclaimed.",
    ),
    "gui.rs_create_compat.importer_executor.exclusive.tip": (
        "仅本总线勾选该类别；抽取是原子的。",
        "Only this bus reclaims it; extraction is atomic.",
    ),
    "gui.rs_create_compat.importer_executor.shared.tip": (
        "由 %s 台总线同时收回；抽取是原子的。",
        "Reclaimed by %s buses; extraction is atomic.",
    ),
    "gui.rs_create_compat.importer_executor.source.tip": (
        "从执行仓内部存储与它供料的机器收回网络。",
        "From the chamber's storage and the machines it feeds.",
    ),
    "gui.rs_create_compat.importer_executor.strategy.materials": (
        "只把该收回的东西收进网络（不反向供料）。",
        "Reclaims only what it should; never feeds back.",
    ),
    "gui.rs_create_compat.importer_executor.locked.tip": (
        "已连到总线输出执行仓；条上只读，勾选请点「详细配置…」",
        "Linked to a Bus Output chamber; use Details to tick.",
    ),
    # ===== 总线类别配置 =====
    "gui.rs_create_compat.bus_config.mode.manual.tip": (
        "编辑态：点「确定」才提交给服务端",
        "Edit buffer: Confirm submits the whole set",
    ),
    # ===== 序列执行仓 =====
    "gui.rs_create_compat.sequence_execution_chamber.hint": (
        "右键存取单元样板；需先设定配方类型与名字。",
        "Right-click to store unit patterns; set the recipe type and name first.",
    ),
    "gui.rs_create_compat.sequence_execution_chamber.output.cell.ignored": (
        "「总线输出」模式下该面配置被忽略（切回「面输出」即恢复）。",
        "In Bus Output mode this face config is ignored (it returns in Face Output mode).",
    ),
    "gui.rs_create_compat.sequence_execution_chamber.chain.tip.shared": (
        "名字与配方类型整链共享：改任意一台，整条链生效。",
        "Name and recipe type are shared by the whole chain.",
    ),
    # ===== 单元样板配置 =====
    "gui.rs_create_compat.unit_pattern_config.confirm.disabled": (
        "名字与配方类型必填；需要输入原料时输入槽不能为空",
        "Name and recipe type are required; when input is needed the input slot must not be empty",
    ),
    "gui.rs_create_compat.unit_pattern_config.name.tip": (
        "单元样板的名字（必填）",
        "Unit pattern name (required)",
    ),
    # ===== 命令与聊天消息 =====
    "message.rs_create_compat.assemblydebug.usage": (
        "用法：/rs_create_compat debug assembly <on|off>",
        "Usage: /rs_create_compat debug assembly <on|off>",
    ),
    "message.rs_create_compat.autocrafter_storage.disabled_by_config": (
        "服务端配置已禁用自动合成仓内部存储。",
        "Autocrafter internal storage is disabled by the server config.",
    ),
    "message.rs_create_compat.autocrafter_storage.disable_failed": (
        "网络空间不足或不可用，已取消：物品与流体仍留在仓内。",
        "Network storage is full or offline, so the toggle was cancelled; items and fluids stay inside.",
    ),
    "message.rs_create_compat.frame_need_pipe": (
        "只支持线缆与流体管道；其它方块需在配置开启",
        "Only cables and fluid pipes; other blocks need the config enabled",
    ),
    "message.rs_create_compat.frame_remove_hint": (
        "已套住；潜行右键取下",
        "Already framed; sneak-right-click to take it off",
    ),
    "message.rs_create_compat.frame_take_off_hint": (
        "已套住分隔框架；潜行右键取下",
        "Separated frame here; sneak-right-click to take it off",
    ),
}

# ---------------------------------------------------------------------------
# 删除：语言里存在、但源码从未引用的孤儿键（含上一轮改为静默后留下的那条）
# ---------------------------------------------------------------------------
DELETE = (
    # 上一轮把传动杆上的「不支持套伪装」触发点整条移除，语言键成了孤儿（用户要求一并清掉）
    "block.rs_create_compat.camouflage_frame.hint.shaft_unsupported",
    # container.*：界面标题实际取方块 / 物品名（RS 基类 getDisplayName = Block#getName），这 7 条从未被引用
    "container.rs_create_compat.advanced_quantity_keeper",
    "container.rs_create_compat.advanced_remote_terminal",
    "container.rs_create_compat.advanced_schematic_loader",
    "container.rs_create_compat.collection_cache",
    "container.rs_create_compat.quantity_keeper",
    "container.rs_create_compat.range_charger",
    "container.rs_create_compat.schematic_loader",
    # 定量保持器界面改版后留下的旧文案（现用 destroy_label / target_label）
    "gui.rs_create_compat.quantity_keeper.destroy",
    "gui.rs_create_compat.quantity_keeper.target",
    # 序列装配样板库界面的三行旧提示（界面已改版，Java 不再引用）
    "gui.rs_create_compat.sequence_assembly_executor.hint1",
    "gui.rs_create_compat.sequence_assembly_executor.hint2",
    "gui.rs_create_compat.sequence_assembly_executor.hint3",
)


def split_line(line):
    """返回 (key, 值起始偏移, 是否带逗号, 行尾)；非键值行返回 (None, None, False, 行尾)。"""
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    stripped = body.lstrip()
    if not stripped.startswith('"') or '":' not in stripped:
        return None, None, False, ending
    offset = len(body) - len(stripped)
    quote = body.index('":', offset)
    return body[offset + 1:quote], quote + 1, body.endswith(","), ending


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def patch(path, index):
    with io.open(path, encoding="utf-8", newline="") as handle:
        lines = handle.readlines()
    out = []
    rewritten = deleted = 0
    for line in lines:
        key, cut, comma, ending = split_line(line)
        if key is not None and key in DELETE:
            deleted += 1
            continue
        if key is not None and key in REWRITE:
            value = REWRITE[key][index]
            line = "%s: %s%s%s" % (line[:cut], json.dumps(value, ensure_ascii=False),
                                   "," if comma else "", ending)
            rewritten += 1
        out.append(line)
    # 兜底：最后一条数据行不能带逗号（删掉尾键时才会触发）
    for i in range(len(out) - 1, -1, -1):
        stripped = out[i].strip()
        if stripped and stripped not in ("{", "}"):
            if stripped.endswith(","):
                body = out[i].rstrip("\r\n")
                out[i] = body[:-1] + out[i][len(body):]
            break
    with io.open(path, "w", encoding="utf-8", newline="") as handle:
        handle.writelines(out)
    with io.open(path, encoding="utf-8") as handle:
        loaded = json.load(handle)   # 写回后复核语法
    assert len(loaded) == sum(1 for l in out if split_line(l)[0] is not None), "键数与行数不匹配"
    return rewritten, deleted, loaded


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")
    before = load("zh_cn.json")

    missing = sorted(set(REWRITE) - set(before))
    if missing:
        raise SystemExit("REWRITE 里有文件中不存在的键：%s" % missing)
    absent = sorted(set(DELETE) - set(before))
    if absent:
        print("[warn] DELETE 里这些键本就不存在（幂等）：%s" % absent)

    n_zh, d_zh, zh = patch(zh_path, 0)
    n_en, d_en, en = patch(en_path, 1)
    print("zh_cn.json：改写 %d 行 / 删除 %d 行；en_us.json：改写 %d 行 / 删除 %d 行"
          % (n_zh, d_zh, n_en, d_en))
    print("键数：%d -> %d（zh）/ %d（en），中英一致：%s"
          % (len(before), len(zh), len(en), set(zh) == set(en)))

    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    if only_zh or only_en:
        raise SystemExit("中英键集合不一致：仅 zh %s / 仅 en %s" % (only_zh, only_en))
    empty = [k for k, v in list(zh.items()) + list(en.items()) if not str(v).strip()]
    if empty:
        raise SystemExit("存在空值：%s" % empty)

    # 长度：中文 <= 40 字（命令用法里含命令字面量，单独列出）；英文 <= 110 字符
    over_zh = sorted((len(v), k) for k, v in zh.items() if len(v) > 40)
    over_en = sorted((len(v), k) for k, v in en.items() if len(v) > 110)
    print("\n中文 > 40 字：%d 条" % len(over_zh))
    for length, key in over_zh:
        print("  %3d  %s" % (length, key))
    print("英文 > 110 字符：%d 条" % len(over_en))
    for length, key in over_en:
        print("  %3d  %s" % (length, key))


if __name__ == "__main__":
    sys.exit(main())
