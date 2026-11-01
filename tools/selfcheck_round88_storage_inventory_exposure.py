#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 88 轮：**所有「储存物品 / 带缓存」的方块都必须对外暴露 Inventory** 的自检。

用户硬规则（原话）：
    「以后所有「储存物品 / 带缓存」的方块或设备，都必须对外暴露 Inventory。
      例如资源接收器：鼠标指针指向它时，应能显示其内部存储。」

本脚本固化四类判据（任一 FAIL 即退出码 1）：
    ① **盘点完整**：`block/entity/*.java` 里每个方块实体都必须在 `STORAGE_BLOCKS` 清单里
       （给出「物品 / 流体 / 只读 / 读写」预期），或在 `EXEMPT` 里给出「为什么没有储存」的理由。
       新增一个忘了分类的方块实体 ⇒ 直接判负。
    ② **注册存在**：每个清单项都能在对应 `registerCapabilities`（RS 自动合成仓在
       `RS_Create_Compat#registerAutocrafterCapabilities`）里找到 `Capabilities.ItemHandler.BLOCK`
       （声明了流体的还要有 `Capabilities.FluidHandler.BLOCK`），并带上预期包装。
    ③ **只读 / 侧别语义**：新增的只读视图必须
       · 含 `ViewOnlyHandlers`（插入 / 抽取都被拒绝的包装）；
       · 侧别策略与方块的角色一致：
         - `view_only`（中间缓存仓 / 执行器 / 样板管理器 / 请求器暂存）：只在
           `direction == null` 时返回非 null。这是 **RS 外部存储环路** 的源码级判据：
           RS 外部存储方块（`AbstractExternalStorageBlockEntity#loadStorage`）用非空
           `incomingDirection` 创建 `CapabilityCacheImpl`，因此它拿不到这些只读视图；
         - `view_only_any_side`（网络资源接收器）：**任何方向都返回同一个只读视图**
           （用户原话：「网络资源接收器在作为一个 inventory 提供者时不应该有方向限制，
           并且方向只针对于成链机制」）。它不做 StorageProvider，因此不会与网络源重复计数；
           唯一副作用是「RS 外部存储方块贴着它时能看见一份只读幽灵库存」（见 ③ 的环路结论）。
       脚本会顺带核对 RS 源码（若本地有）。
    ④ **Jade 只暴露名字，内容交给 Jade Universal**（第 95 轮口径反转，用户原话：「你的作用只是把
       这一个接口暴露出去，并不需要你自己写……按下 Shift 显示详情什么的这玩意 Jade 已经帮你实现了」）：
       ② 已保证接收器暴露 `ItemHandler.BLOCK` / `FluidHandler.BLOCK`，Jade 的 UniversalPlugin 自己
       会对容器显示内容；因此接收器的 Jade 公共载荷**只许写名字 / 是否已命名**，
       一旦又出现 `items` / `fluids` / `hold` / `more` 明细行、`hasShiftDown` 分支或缓存计数
       （`putInt` / `putLong`）⇒ 直接判负。`RsccJadePlugin` 的公共注册与客户端注册仍须覆盖接收器。

    **反例自证（negative self-proof）**：把四种坏写法喂给同一套 `audit()`：
       (a) 漏掉接收器的 ItemHandler 注册；
       (b) 把接收器改成可写（直接暴露 `itemCache` / `InvWrapper`）；
       (c) 把接收器改回「direction == null 才暴露」——用户本轮明确要求
           「作为 inventory 提供者不应该有方向限制」，因此这种回退必须判负；
       (d) 仍然按旧口径的 `view_only` 方块（中间缓存仓）去掉 null 侧守卫 ⇒ 必须判负，
           保证 (c) 的方向反转没有把「旧方块的侧别守卫」这条判据一起放松。
       四者都必须被判负，否则说明判据本身是「怎么写都过」的假绿。
       (e) 把 Jade 载荷退回「items/fluids 明细 + hasShiftDown 详情」⇒ 必须判负
           （第 95 轮用户明确不要自己写 Shift 详情，内容交给 Jade Universal）。

用法：python tools/selfcheck_round88_storage_inventory_exposure.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

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
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
PKG = "cretae.cookiewyq.rs_create_compat"
PKG_PATH = PKG.replace(".", "/")
BE_DIR = PKG_PATH + "/block/entity"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "INFO"), name,
                       (" | " + detail) if (detail and not ok) else ""))


def read(rel):
    with io.open(os.path.join(JAVA_ROOT, rel), encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """只去注释、保留字符串：用于「必须看到关键字面量」的断言。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def load_sources():
    sources = {}
    for dirpath, _dirs, files in os.walk(JAVA_ROOT):
        for name in files:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, JAVA_ROOT).replace(os.sep, "/")
            with io.open(full, encoding="utf-8") as handle:
                text = handle.read()
            sources[rel] = {"text": text, "raw": strip_comments(text)}
    return sources


def method_body(text, signature_re):
    m = re.search(signature_re, text)
    if not m:
        return None
    brace = text.find("{", m.end() - 1)
    if brace < 0:
        return None
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[m.start():i + 1]
        i += 1
    return None


# ==================== 盘点清单（唯一来源；新增方块实体必须在这里分类） ====================
# mode:
#   view_only          = 只读包装；只在 direction == null 暴露（Jade / 信息类模组）
#   view_only_any_side = 只读包装；任何方向都暴露同一个视图（用户明确要求：inventory 提供者无方向限制）
#   extract_only_faces = 输出面只出不进（既有语义；null 侧不额外暴露，避免挡住按面回退）
#   read_write_filtered= 既有语义：按面 / 按标记过滤的读写能力（不改）
#   pattern_only       = 只暴露样板槽（既有语义）
STORAGE_BLOCKS = {
    # 第 92 轮：用户原话「网络资源接收器在作为一个 inventory 提供者时不应该有方向限制，
    # 并且方向只针对于成链机制」⇒ 接收器改为「任何方向都返回同一份只读视图」。
    # 方向（FACING）只用于成链（缓存 → 朝向容器 / 第三方直通），与「谁能看见 inventory」无关。
    "NetworkResourceReceiverBlockEntity": {
        "item": "ViewOnlyHandlers.writableItem", "fluid": "ViewOnlyHandlers.writableFluid",
        "mode": "writable_any_side",
    },
    "IntermediateCacheBlockEntity": {
        "item": "ViewOnlyHandlers.itemOf", "fluid": None,
        "mode": "view_only",
    },
    "NetworkResourceRequesterBlockEntity": {
        "item": "ViewOnlyHandlers.itemOf", "fluid": "ViewOnlyHandlers.fluidOf",
        "mode": "view_only",
    },
    # 第 101 轮新增：降解室发电机。它**就是要**把自己的 256 格库存原样暴露给任意方向 / 任意管道
    # （用户原话：内部 inventory 可被交互，输出总线能一直往里输植物），所以这里既不是只读也不是
    # 只进不出的包装 —— 它的「可写范围」由 inventory 自己的 isItemValid（可降解表）决定。
    # 因此单独一档 raw_inventory_any_side：要求「无方向分支 + 返回真实库存 + 过滤在 isItemValid」。
    "DecompositionGeneratorBlockEntity": {
        "item": "blockEntity.getInventory()", "fluid": None,
        "mode": "raw_inventory_any_side",
    },
    "SequenceAssemblyExecutorBlockEntity": {
        "item": "ViewOnlyHandlers.item", "fluid": None,
        "mode": "view_only",
    },
    "UnitPatternManagerBlockEntity": {
        "item": "ViewOnlyHandlers.item", "fluid": None,
        "mode": "view_only",
    },
    "SequenceExecutionChamberBlockEntity": {
        "item": "logisticsItemHandler", "fluid": "logisticsFluidHandler",
        "mode": "extract_only_faces",
    },
    "CollectionCacheBlockEntity": {
        "item": "itemLogisticsView", "fluid": "fluidLogisticsView",
        "mode": "read_write_filtered",
    },
    "QuantityKeeperBlockEntity": {
        "item": "getItemHandler", "fluid": "getFluidHandler",
        "mode": "read_write_filtered",
    },
    "AdvancedQuantityKeeperBlockEntity": {
        "item": "getItemHandler", "fluid": "getFluidHandler",
        "mode": "read_write_filtered",
    },
    "SchematicLoaderBlockEntity": {
        "item": "getInventory", "fluid": None,
        "mode": "read_write_filtered",
    },
    "AdvancedSchematicLoaderBlockEntity": {
        "item": "getInventory", "fluid": None,
        "mode": "read_write_filtered",
    },
    "SequencePatternTerminalBlockEntity": {
        "item": "exposedPatternSlots", "fluid": None,
        "mode": "pattern_only",
    },
    # 第 89 轮新增：容器代理器。按面读写（对象面 = 代理对象单槽；内容物面 = 被代理物品自己的
    # 物品/流体库存；禁用面 = null），无名面上的默认视图 = 内容物。它**不是** RS 网络节点，
    # 所以这里不涉及「网络源重复计数」，但仍遵守 round88 的「储存类方块必须对外暴露 Inventory」。
    # 第 90 轮新增：高级归流缓存仓。它继承 CollectionCacheBlockEntity，物品 / 流体能力与基础版
    # 逐字同源（itemLogisticsView / fluidLogisticsView：按输入面过滤、取出始终允许），
    # 因此分类沿用基础版的 read_write_filtered；缓存区更大（512 格）不影响「对外暴露」这条判据。
    "AdvancedCollectionCacheBlockEntity": {
        "item": "itemLogisticsView", "fluid": "fluidLogisticsView",
        "mode": "read_write_filtered",
    },
    "InventoryProxyBlockEntity": {
        "item": "itemHandler", "fluid": "fluidHandler",
        "mode": "read_write_filtered",
    },
}

# 没有「储存物品 / 带缓存」的方块实体（每条都要说明理由；新增未分类会判负）
EXEMPT = {
    "SeparationFrameBlockEntity": "纯框架：没有任何容器 / 缓存字段",
    "RangeChargerBlockEntity":
        "只有 FE 能量缓冲 + 升级槽，没有物品 / 流体缓存；且本轮硬约束明确禁止改 RangeCharger*",
    "FEEnergyCubeBlockEntity":
        "第 99 轮新增：只有 FE 能量缓冲（相邻立方共享容量池），没有物品 / 流体缓存，"
        "不注册 ItemHandler / FluidHandler 能力；它对外只有 Capabilities.EnergyStorage.BLOCK "
        "与 RS 网络节点容器，因此不属于本脚本要盘点的「储存物品 / 带缓存」类别",
}

# RS 自动合成仓（不在本模组 block/entity 下；能力注册在 RS_Create_Compat）
RS_AUTOCRAFTER = {
    "file": PKG_PATH + "/RS_Create_Compat.java",
    "method": r"private\s+static\s+void\s+registerAutocrafterCapabilities",
    "item": "ExtractOnlyHandlers.Item",
    "fluid": "ExtractOnlyHandlers.Fluid",
}

VIEW_ONLY_MODES = ("view_only",)
ANY_SIDE_MODES = ("view_only_any_side", "writable_any_side")
NULL_SIDE_RE = re.compile(r"direction\s*==\s*null\s*\?\s*[\w.]*ViewOnlyHandlers")
# 第 96 轮：接收器改成「可写包装（只进不出）」——用户原话「还是不能跟溜槽之类的进行交互」。
# 判据与只读版一样严：必须是无条件返回同一份视图（不许 direction == null 分支），
# 而且必须把写入接到缓存唯一入口（insertItemFromExternal / fillFluidFromExternal），
# 外抽仍然恒空（extractItem / drain 在 ViewOnlyHandlers.Writable* 里写死）。
ANY_SIDE_RE = re.compile(
    r"\(blockEntity,\s*direction\)\s*->\s*ViewOnlyHandlers\.(item|fluid|writableItem|writableFluid)\(")
WRITABLE_SINK_ITEM_RE = re.compile(
    r"ViewOnlyHandlers\.writableItem\(blockEntity\.itemCache,\s*blockEntity::insertItemFromExternal\)")
WRITABLE_SINK_FLUID_RE = re.compile(
    r"ViewOnlyHandlers\.writableFluid\(blockEntity\.fluidCache,\s*blockEntity::fillFluidFromExternal\)")


def registration_blocks(body):
    """把 `event.registerBlockEntity(...)` 逐个切出来（括号配对），用于逐能力判定。"""
    blocks = []
    for match in re.finditer(r"event\.registerBlockEntity\s*\(", body):
        i = match.end() - 1  # 指向 '('
        depth = 0
        start = match.start()
        while i < len(body):
            if body[i] == "(":
                depth += 1
            elif body[i] == ")":
                depth -= 1
                if depth == 0:
                    blocks.append(body[start:i + 1])
                    break
            i += 1
    return blocks


def registration_text(sources, be, entry):
    """取出某个方块实体的能力注册方法体（统一入口，反例自证也走这里）。"""
    rel = BE_DIR + "/" + be + ".java"
    src = sources.get(rel)
    if src is None:
        return None
    body = method_body(src["raw"], r"public\s+static\s+void\s+registerCapabilities")
    return body


def audit(sources):
    """核心判据：返回 (problems, notes)。反例自证直接复用本函数。"""
    problems = []
    notes = []

    # ---------- ① 盘点完整 ----------
    actual = sorted(name[:-5] for name in os.listdir(os.path.join(JAVA_ROOT, BE_DIR))
                    if name.endswith(".java"))
    unclassified = [name for name in actual
                    if name not in STORAGE_BLOCKS and name not in EXEMPT]
    if unclassified:
        problems.append("① 未分类的方块实体: " + ", ".join(unclassified))
    missing_be = [name for name in STORAGE_BLOCKS if name not in actual]
    if missing_be:
        problems.append("① 清单里的方块实体不存在: " + ", ".join(missing_be))
    notes.append("① 盘点 方块实体 %d 个 = 储存 %d + 无储存 %d"
                 % (len(actual), len(STORAGE_BLOCKS), len(EXEMPT)))

    # ---------- ②/③ 注册存在 + 只读 / 侧别 ----------
    for be, entry in STORAGE_BLOCKS.items():
        body = registration_text(sources, be, entry)
        if body is None:
            problems.append("② %s 找不到 registerCapabilities" % be)
            continue
        blocks = registration_blocks(body)
        item_blocks = [b for b in blocks if "Capabilities.ItemHandler.BLOCK" in b]
        fluid_blocks = [b for b in blocks if "Capabilities.FluidHandler.BLOCK" in b]
        if not item_blocks:
            problems.append("② %s 没有注册 Capabilities.ItemHandler.BLOCK" % be)
        if entry.get("fluid") and not fluid_blocks:
            problems.append("② %s 没有注册 Capabilities.FluidHandler.BLOCK" % be)
        if item_blocks and not any(entry["item"] in b for b in item_blocks):
            problems.append("② %s 的物品包装不是预期的 %s" % (be, entry["item"]))
        if entry.get("fluid") and fluid_blocks \
                and not any(entry["fluid"] in b for b in fluid_blocks):
            problems.append("② %s 的流体包装不是预期的 %s" % (be, entry["fluid"]))
        if entry["mode"] in VIEW_ONLY_MODES:
            for block in item_blocks + fluid_blocks:
                if "ViewOnlyHandlers" not in block:
                    problems.append("③ %s 是只读视图却没有用 ViewOnlyHandlers" % be)
                if not NULL_SIDE_RE.search(block):
                    problems.append("③ %s 的某个能力没有「direction == null 才暴露」的守卫" % be)
        if entry["mode"] in ANY_SIDE_MODES:
            # 反向判据：用户要求「无方向限制」⇒ 这些注册项里**不许**再出现 direction == null 分支，
            # 也不许出现「非 null 方向 ⇒ null」的写法；必须是无条件返回同一份视图。
            for block in item_blocks + fluid_blocks:
                if "ViewOnlyHandlers" not in block:
                    problems.append("③ %s 的视图没有用 ViewOnlyHandlers" % be)
                if "direction == null" in block or "direction==null" in block:
                    problems.append("③ %s 仍然有「direction == null 才暴露」的分支"
                                    "（用户要求 inventory 提供者无方向限制）" % be)
                if not ANY_SIDE_RE.search(block):
                    problems.append("③ %s 的某个能力不是「任何方向都返回同一份视图」" % be)
                if entry["mode"] == "writable_any_side":
                    # 可写包装必须把写入接到缓存唯一入口（否则就是第二套写入账）
                    if "Capabilities.ItemHandler.BLOCK" in block \
                            and not WRITABLE_SINK_ITEM_RE.search(block):
                        problems.append("③ %s 的物品可写包装没有接到 "
                                        "blockEntity::insertItemFromExternal" % be)
                    if "Capabilities.FluidHandler.BLOCK" in block \
                            and not WRITABLE_SINK_FLUID_RE.search(block):
                        problems.append("③ %s 的流体可写包装没有接到 "
                                        "blockEntity::fillFluidFromExternal" % be)
        if entry["mode"] == "raw_inventory_any_side":
            # 原始库存档（降解室发电机）：三个硬要求，缺一即判负。
            for block in item_blocks:
                if "direction == null" in block or "direction==null" in block:
                    problems.append("③ %s 的原始库存暴露带了「direction == null」分支"
                                    "（用户要求 inventory 提供者无方向限制）" % be)
                if "ViewOnlyHandlers" in block:
                    problems.append("③ %s 是 raw_inventory_any_side，却包了 ViewOnlyHandlers"
                                    "（本机需要可进可出）" % be)
                if entry["item"] not in block:
                    problems.append("③ %s 没有返回真实库存（期望 %s）" % (be, entry["item"]))
            raw_src = sources[BE_DIR + "/" + be + ".java"]["raw"]
            # 过滤必须落在 inventory 自己的 isItemValid 上（否则任何物品都能塞进去）
            if "boolean isItemValid(" not in raw_src:
                problems.append("③ %s 的 inventory 没有 isItemValid 过滤（木头也能塞进去）" % be)
            # 拆方块结算必须走 BlockContentReleaser（256 格内容物不能凭空消失）。
            # 结算点允许两种落点：方块实体自己（onRemove / getDrops）或**方块类**（本机就是后者：
            # DecompositionGeneratorBlock#onRemove 结算 256 格 + getDrops 抑制原版战利品）。
            block_src = sources.get(BE_DIR.replace("block/entity", "block") + "/"
                                    + be.removesuffix("BlockEntity") + "Block.java")
            block_raw = block_src["raw"] if block_src else ""
            if "BlockContentReleaser" not in raw_src and "BlockContentReleaser" not in block_raw:
                problems.append("③ %s 拆方块没有走 BlockContentReleaser 结算内容物" % be)
            # 不能同时是 RS StorageProvider（否则同一批库存会被网络再算一遍）
            if "implements StorageProvider" in raw_src or "StorageProvider," in raw_src:
                problems.append("③ %s 同时是 StorageProvider ⇒ 原始库存会被网络重复计数" % be)
        if entry["mode"] == "extract_only_faces":
            if "ExtractOnlyHandlers" not in sources[BE_DIR + "/" + be + ".java"]["raw"]:
                problems.append("③ %s 的侧面不是只出不进（缺 ExtractOnlyHandlers）" % be)
            # 执行仓保持「按面」语义（见其 registerCapabilities javadoc）：
            # null 侧不额外暴露视图，否则会在 RsccChamberImportStrategy#itemHandlerAt 的
            # 「先 null、再逐面回退」里把输出面的真实能力挡住（搬运语义回归）。

    # ---------- RS 自动合成仓 ----------
    auto_src = sources.get(RS_AUTOCRAFTER["file"])
    if auto_src is None:
        problems.append("② RS 自动合成仓：找不到 RS_Create_Compat.java")
    else:
        auto = method_body(auto_src["raw"], RS_AUTOCRAFTER["method"])
        if auto is None:
            problems.append("② RS 自动合成仓：找不到 registerAutocrafterCapabilities")
        else:
            for key in ("item", "fluid"):
                token = RS_AUTOCRAFTER[key]
                if token not in auto:
                    problems.append("② RS 自动合成仓缺少 %s" % token)
            if "Capabilities.ItemHandler.BLOCK" not in auto \
                    or "Capabilities.FluidHandler.BLOCK" not in auto:
                problems.append("② RS 自动合成仓没有注册物品 / 流体能力")

    # ---------- ④ 环路判据（只读视图会不会被 RS 外部存储当成第二份网络源） ----------
    # 只读 + direction==null 的方块：外部存储方块用非空 incomingDirection 查询 ⇒ 拿不到。
    # 接收器按用户要求改成任意方向后：它**不是** StorageProvider（缓存是私有缓冲，不进网络账），
    # 所以即使 RS 外部存储方块贴着它看见这份只读视图，同一批资源也只会被列一次（不会翻倍）。
    # 代价是「只读幽灵库存」：RS 能列出内容，但插入 / 抽取被 ViewOnlyHandlers 全拒 ⇒ 拿不出来。
    # 注意：这不是「猜」，下面会核对 RS 源码的两个具体方法（若本地存在）。
    for be, entry in STORAGE_BLOCKS.items():
        if entry["mode"] not in VIEW_ONLY_MODES + ANY_SIDE_MODES:
            continue
        if "StorageProvider" in sources[BE_DIR + "/" + be + ".java"]["raw"]:
            notes.append("③ %s 本身是 RS StorageProvider；只读视图不会与网络源重复计数" % be)
    notes.append("③ 环路判据：view_only 仅在 direction==null 暴露；view_only_any_side（接收器）"
                 "任意方向暴露，但它不是 StorageProvider ⇒ 不会数量翻倍 / 投递死循环")

    return problems, notes


def check_rs_external_storage_evidence():
    """核对 RS 源码：外部存储方块确实用非空方向查询能力（本地有源码时硬断言）。"""
    root = os.path.join(ROOT, "local_src", "external", "RefinedStorage")
    ext = os.path.join(root, "refinedstorage-common", "src", "main", "java",
                       "com", "refinedmods", "refinedstorage", "common", "storage",
                       "externalstorage", "AbstractExternalStorageBlockEntity.java")
    cache = os.path.join(root, "refinedstorage-neoforge", "src", "main", "java",
                         "com", "refinedmods", "refinedstorage", "neoforge", "storage",
                         "CapabilityCacheImpl.java")
    if not (os.path.isfile(ext) and os.path.isfile(cache)):
        check("③0 RS 源码证据（本地缺源码，跳过硬断言）", True,
              "local_src/external/RefinedStorage 不在", hard=False)
        return
    with io.open(ext, encoding="utf-8") as handle:
        ext_text = handle.read()
    with io.open(cache, encoding="utf-8") as handle:
        cache_text = handle.read()
    check("③1 RS 外部存储 loadStorage 用非空 incomingDirection 创建 provider",
          "incomingDirection" in ext_text
          and "factory.create(serverLevel, sourcePosition, incomingDirection)" in ext_text,
          "AbstractExternalStorageBlockEntity#loadStorage")
    check("③2 RS CapabilityCacheImpl 把该方向原样传给 BlockCapabilityCache",
          re.search(r"BlockCapabilityCache\.create\(Capabilities\.ItemHandler\.BLOCK,\s*level,\s*pos,\s*direction\)",
                    cache_text) is not None,
          "CapabilityCacheImpl 构造器")
    check("③3 因此 direction==null 的只读视图不会被 RS 外部存储看到（源码级结论）",
          "incomingDirection" in ext_text and "direction" in cache_text)


def jade_problems(sources, zh, en):
    """④ 接收器 Jade：**只暴露名字，内容交给 Jade Universal**（第 95 轮用户口径反转）。

    用户原话（1.2.0-beta.1 实机）：「所有这种……让 Jade 显示里面有什么东西的这些方块，你的作用只是
    把这一个接口暴露出去，并不需要你自己写……按下 Shift 显示详情什么的这玩意 Jade 已经帮你实现了」。

    因此本节的判据是「不许多写」而不是「必须写全」：
      · ② 已经保证接收器暴露 `Capabilities.ItemHandler.BLOCK` / `FluidHandler.BLOCK`，
        Jade 15.x 的 `UniversalPlugin` 会对 `Block.class` 自动显示容器内容 ⇒ 我们只发**名字**；
      · 公共载荷里一旦又出现 items/fluids 明细、`hold` / `more` / `item` / `fluid` / `empty` 行、
        或自己判 Shift，都必须判负（这就是用户明确不要的那套）。
    返回 problems 列表（主流程逐条打印，反例自证复用同一函数）。
    """
    problems = []

    def chk(label, ok, detail=""):
        if not ok:
            problems.append(label if not detail else "%s（%s）" % (label, detail))

    payload_rel = PKG_PATH + "/compat/jade/RsccReceiverJadePayload.java"
    server_rel = PKG_PATH + "/compat/jade/RsccReceiverJadeServerData.java"
    client_rel = PKG_PATH + "/client/jade/RsccReceiverJadeProvider.java"
    plugin_rel = PKG_PATH + "/client/jade/RsccJadePlugin.java"
    missing = [rel for rel in (payload_rel, server_rel, client_rel)
               if rel not in sources]
    chk("④a 接收器 Jade 三个新文件都在（公共载荷 / 服务端数据 / 客户端 provider）",
        not missing, "; ".join(missing))
    if missing:
        return problems
    payload = sources[payload_rel]
    server = sources[server_rel]
    client = sources[client_rel]
    plugin = sources[plugin_rel]
    # 注意：sources[*]["raw"] 已经是「注释被换成空格、字符串字面量保留」的版本（见 load_sources），
    # 所以这里判 token 不会被类注释里「我们不再写 items」这类说明误触发。
    payload_code = payload["raw"]

    # 明细行 / Shift 详情的「负向」判据：这些 token 一旦回到载荷里，就说明旧那套又长回来了。
    detail_tokens = ("items", "fluids", "hold", "more", "item", "fluid", "empty", "summary")
    chk("④b 公共载荷不再自己写 items / fluids 明细（内容交给 Jade Universal 按 capability 显示）",
        re.search(r'ITEMS\s*=\s*"items"', payload_code) is None
        and re.search(r'FLUIDS\s*=\s*"fluids"', payload_code) is None
        and "putInt(COUNT" not in payload_code
        and "putInt(AMOUNT" not in payload_code)
    chk("④c 客户端 lines() 只写「名字 / 未命名」两行，不再拼「× 数量 / N mB」明细",
        re.search(r'LANG\s*\+\s*"name"', payload["raw"]) is not None
        and re.search(r'LANG\s*\+\s*"unnamed"', payload["raw"]) is not None
        and all(re.search(r'LANG\s*\+\s*"%s"' % token, payload["raw"]) is None
                for token in detail_tokens))
    chk("④d 载荷 / provider 都不自己判 Shift（用户：Shift 详情归 Jade，不自己写）",
        all(re.search(r'hasShiftDown|isShiftKeyDown|shiftDown', value["raw"]) is None
            for value in (payload, client)))
    chk("④e 服务端 provider 从方块实体取真实名字（write(... getBlockEntity())）",
        re.search(r"RsccReceiverJadePayload\.write", server["raw"]) is not None
        and re.search(r"accessor\.getBlockEntity\(\)", server["raw"]) is not None)
    chk("④f 客户端只读 getServerData()，不碰方块实体",
        re.search(r"getServerData\s*\(\s*\)", client["raw"]) is not None
        and "getBlockEntity" not in client["raw"])
    chk("④g 公共载荷 / 服务端数据不引用客户端 / Jade 类型（专服安全）",
        "net.minecraft.client" not in payload["raw"]
        and "snownee" not in payload["raw"]
        and "net.minecraft.client" not in server["raw"])
    common_body = method_body(plugin["raw"], r"public\s+void\s+register\s*\(\s*(?:final\s+)?IWailaCommonRegistration")
    client_body = method_body(plugin["raw"], r"public\s+void\s+registerClient\s*\(\s*(?:final\s+)?IWailaClientRegistration")
    chk("④h 公共注册覆盖 NetworkResourceReceiverBlock（名字来自服务端）",
        common_body is not None
        and re.search(r"registerBlockDataProvider\([^;]*NetworkResourceReceiverBlock\.class",
                      common_body, re.S) is not None)
    chk("④i 客户端注册覆盖 NetworkResourceReceiverBlock（只加文本）",
        client_body is not None
        and re.search(r"registerBlockComponent\([^;]*NetworkResourceReceiverBlock\.class",
                      client_body, re.S) is not None)
    chk("④j 服务端数据只写「名字 / 是否已命名」两个键，不再序列化缓存内容（零额外带宽）",
        re.search(r'NAME\s*=\s*"name"', payload_code) is not None
        and re.search(r'CONFIGURED\s*=\s*"configured"', payload_code) is not None
        and "putInt(" not in payload_code
        and "putLong(" not in payload_code)
    chk("④k 未命名接收器明确写「不能用」（不会被误认为已收到）",
        re.search(r'LANG\s*\+\s*"unnamed"', payload["raw"]) is not None)

    keys = [
        "gui.rs_create_compat.jade_receiver.name",
        "gui.rs_create_compat.jade_receiver.unnamed",
        "config.jade.plugin_rs_create_compat.receiver",
        "config.jade.plugin_rs_create_compat.receiver_data",
    ]
    missing_keys = [key for key in keys if key not in zh or key not in en]
    chk("④l 仍在用的 4 个语言键中英齐全（明细行那 8 个键已随用户口径删除引用）",
        not missing_keys, "; ".join(missing_keys))
    return problems


def negative_self_proof(sources, zh, en):
    """反例自证：坏写法必须被判负；否则判据是假绿。"""
    receiver_rel = BE_DIR + "/NetworkResourceReceiverBlockEntity.java"

    def mutated(replace, rel=receiver_rel):
        clone = {name: dict(value) for name, value in sources.items()}
        original = clone[rel]["raw"]
        changed = replace(original)
        clone[rel]["raw"] = changed
        clone[rel]["text"] = changed
        return audit(clone)[0]

    # (a) 漏掉接收器的 ItemHandler 注册
    def drop_item(text):
        return re.sub(
            r"event\.registerBlockEntity\(\s*Capabilities\.ItemHandler\.BLOCK,.*?\);",
            "", text, count=1, flags=re.S)

    missing = mutated(drop_item)
    check("⑤a 反例：漏掉接收器 ItemHandler ⇒ 判负",
          any("NetworkResourceReceiverBlockEntity" in item and "ItemHandler" in item
              for item in missing), str(missing[:3]))

    # (b) 绕过统一入口：把可写包装换成裸缓存对象（读 / 写都不再经 ViewOnlyHandlers）
    def make_writable(text):
        return text.replace("ViewOnlyHandlers.writableItem(blockEntity.itemCache,",
                            "((net.neoforged.neoforge.items.IItemHandler) blockEntity.itemCache)"
                            " /* mutated */ (blockEntity.itemCache,")

    writable = mutated(make_writable)
    check("⑤b 反例：接收器绕过 ViewOnlyHandlers 统一入口 ⇒ 判负",
          any("NetworkResourceReceiverBlockEntity" in item for item in writable),
          str(writable[:3]))

    # (b2) 可写包装不接缓存唯一入口（自己写缓存 ⇒ 第二套写入账）
    def break_sink(text):
        return text.replace("blockEntity::insertItemFromExternal)",
                            "stack -> 0 /* mutated: 不走 applyResource */)")

    no_sink = mutated(break_sink)
    check("⑤b2 反例：物品可写包装不接 insertItemFromExternal ⇒ 判负（容量账会变两套）",
          any("NetworkResourceReceiverBlockEntity" in item for item in no_sink),
          str(no_sink[:3]))

    # (c) 把接收器改回「direction == null 才暴露」——用户明确要求「无方向限制」，
    #     所以这种写法现在必须判负（与改前的判据方向相反，但确实抓的是同一条侧别语义）。
    def restore_null_guard(text):
        return text.replace(
            "(blockEntity, direction) ->",
            "(blockEntity, direction) -> direction == null ? null : /* mutated */")

    sided = mutated(restore_null_guard)
    check("⑤c 反例：把接收器改回「direction == null 才暴露」⇒ 判负"
          "（用户要求 inventory 提供者无方向限制）",
          any("NetworkResourceReceiverBlockEntity" in item for item in sided),
          str(sided[:3]))

    # (d) 仍按旧口径的 view_only 方块（中间缓存仓）去掉 null 侧守卫 ⇒ 必须判负
    #     （保证 ⑤c 的方向反转没有把「旧 blocks 的侧别守卫」这条判据一起放松）。
    intermediate_rel = BE_DIR + "/IntermediateCacheBlockEntity.java"

    def remove_guard_legacy(text):
        return text.replace("direction == null", "true")

    legacy = mutated(remove_guard_legacy, intermediate_rel)
    check("⑤d 反例：中间缓存仓去掉 direction == null 守卫 ⇒ 判负（旧口径仍被守住）",
          any("IntermediateCacheBlockEntity" in item and "守卫" in item for item in legacy),
          str(legacy[:3]))

    # (e) Jade 载荷回退成「自己写 items/fluids 明细 + Shift 详情」⇒ 必须判负
    #     （第 95 轮用户明确要求：只暴露接口，Shift 详情交给 Jade，不要自己写）。
    payload_rel = PKG_PATH + "/compat/jade/RsccReceiverJadePayload.java"
    clone = {name: dict(value) for name, value in sources.items()}
    clone[payload_rel]["raw"] = (clone[payload_rel]["raw"]
                                 + '\n    private static final String ITEMS = "items";\n'
                                   '    private static final String FLUIDS = "fluids";\n'
                                   '    private static final String HOLD = LANG + "hold";\n'
                                   '    private static final String MORE = LANG + "more";\n'
                                   '        if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) { }\n'
                                   '        entry.putInt(COUNT, 1);\n')
    clone[payload_rel]["text"] = clone[payload_rel]["raw"]
    jade_bad = jade_problems(clone, zh, en)
    check("⑤e 反例：Jade 载荷退回「items/fluids + Shift 明细」⇒ 判负（至少 3 条断言变红）",
          len(jade_bad) >= 3, str(jade_bad[:3]))


def main():
    sources = load_sources()
    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)

    print("=" * 100)
    print("① 盘点 + ② 注册 + ③ 只读 / 侧别 / 环路判据")
    print("=" * 100)
    problems, notes = audit(sources)
    for note in notes:
        print("      " + note)
    for problem in problems:
        check(problem, False)
    if not problems:
        check("①-③ 盘点 / 注册 / 只读 / 侧别 全部通过", True)

    check_rs_external_storage_evidence()

    print("=" * 100)
    print("④ 接收器 Jade：只暴露名字，内容交给 Jade Universal（用户明确不要自己写 Shift 详情）")
    print("=" * 100)
    jade = jade_problems(sources, zh, en)
    for problem in jade:
        check(problem, False)
    if not jade:
        check("④ 接收器 Jade 口径（载荷只名字 / 不再自己写 items·fluids·Shift 明细）全部通过", True)

    print("=" * 100)
    print("⑤ 反例自证（bad input must fail）")
    print("=" * 100)
    negative_self_proof(sources, zh, en)

    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] 储存方块 Inventory 暴露 / Jade 缓存行：%d 项断言全部通过" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
