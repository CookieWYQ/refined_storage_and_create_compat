#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 110 轮自检：FE 能量立方的**物品形态**（`rs_create_compat:fe_energy_cube` 的 ItemStack 侧）。

本脚本只读源码 / 语言文件 / 资源生成脚本，**不改任何 src/main**；只用标准库。

用户第 110 条反馈（实机）原话要点：
> 「这个能量立方应该和控制器一样，它有那个能量条（显示在下面，类似耐久条那样，储存的是电量）；
> 但现在我拿在手上它是没有电量条的，它得是有电量条的，因为他没有电量条拿在手上也充不了电、
> 也看不到他有多少电量。这个东西拿在手上应该是能够显示当前电量的，而且不管有没有按下 shift 键
> 都应该得显示它当前的电量。」

断言清单：
  ① 物品类存在：`item/FEEnergyCubeItem` 存在并继承 `RsccHelpBlockItem`（RS 常显帮助）。
  ② 注册替换：`FE_ENERGY_CUBE_ITEM` 用新类注册，**不再**是裸 `RsccHelpBlockItem`。
  ③ 能力注册**仅一次**：物品类里 `Capabilities.EnergyStorage.ITEM` / `event.registerItem(` 各一处；
     主类 `FEEnergyCubeItem::registerCapabilities` 监听恰好一行；全仓扫描只有物品类同时出现
     「registerItem + FE_ENERGY_CUBE_ITEM」（重复注册 ⇒ 能力表冲突 / 未定义行为）。
  ④ **同一个 NBT 键**（本轮最硬的一条）：物品读写直接引用 `FEEnergyCubeBlockEntity.TAG_ENERGY`，
     容器是 `DataComponents.BLOCK_ENTITY_DATA`（**不是**飞行背包那套 `CUSTOM_DATA`），
     写回走原版 `BlockItem.setBlockEntityData`（成对处理 `id`）；
     物品类里**没有**自己再声明一个 `TAG_ENERGY` 常量、**没有**字面量键 `putInt("...")`；
     方块侧 `saveToItem` → `saveAdditional` 的 `TAG_ENERGY` 读写成对 ⇒ 「手上充的电 = 放下后的方块的电」。
  ④-b **堆叠守卫**：电量组件是**整堆共享一份**的 ⇒ `count > 1` 时能力视图一律拒绝充放
     （否则「拆堆」`split→copyWithCount` 与「放置」`updateCustomBlockEntityTag` 各是一条复制路径，
     64 块一堆带 100,000 FE 能复制出 64 份）；tooltip 在该状态下追加「拆成单块才能充」。
     **不**把堆叠上限改成 1：64 个一堆携带 / 摆放是既有玩法，本轮不动。
  ⑤ 耐久样式电量条三件套：`isBarVisible`（未满才显示）/ `getBarWidth`（13px 线性）/ `getBarColor`；
     条色与 `tools/gen_fe_energy_cube_assets.py` 的 `ACCENT` 青绿**逐字节相同**（解析两侧数值比对，
     不是「看起来差不多」）。
  ⑥ tooltip **常显**：`appendHoverText` 方法体内无 `isAdvanced` / `hasShiftDown` / 任何 `if (` 门控，
     电量行在顶层无条件追加；`super.appendHoverText(...)` 保留（不覆盖父类）；
     **不覆写** `getTooltipImage`（RS 的常显帮助文本走它，覆写 = 丢掉帮助文本）；
     物品类不引用任何 `net.minecraft.client.*`（常显不许靠客户端 Shift 判定）。
  ⑦ 精确千分位：tooltip 复用 `ElectricJetpackItem#formatFeExact`（第 109 轮口径），
     且不出现 `%.1fM` / `%.1fK` / `M FE` 缩写。
  ⑧ 容量 / 速率与方块同源：物品 `Config.feEnergyCubeCapacity` / `Config.feEnergyCubeMaxTransfer`，
     回退常量 = 配置默认值（50,000,000 / 100,000）；方块实体同样用这两个配置项；
     能力视图全部委托物品类的 `capacity()` / `transfer()`（不自己算一套）。
  ⑨ 语言键成对：zh/en 三个 `item.rs_create_compat.fe_energy_cube.tooltip.*` 键齐全、
     占位符个数一致、en 不含 CJK；物品里出现的每个 `translatable(...)` 键在 zh/en 都能查到。
  ⑩ 行为模型（纯 Python，与 Java 同一套语义）：充 / 放 / simulate / 速率夹 / 容量夹 /
     「放下方块后电量不变（同一个键）」；电量条 13px 用 **Java 的 `Math.round`（半数向上）**
     而不是 Python 的银行家舍入。

反例自证（≥6 条，全部喂进**同一批**判据，必须判负）：
  · tooltip 改成按住 Shift 才显示；· 物品电量换成独立 NBT 键（放下丢电）；· 删掉能力注册；
  · 电量条删掉 `getBarWidth`；· 格式改回 `%.1fM` 缩写；· 容量/速率改成硬编码不与配置同源；
  · 重复注册同一件物品；· en_us 里混中文；· 物品类自己另开一个 `TAG_ENERGY` 常量；
  · 用飞行背包的 `CUSTOM_DATA` 存电量（平行存储）；· 覆写 `getTooltipImage`（丢帮助文本）；
  · 模型：用银行家舍入替代 Java `Math.round`（条长差 1px）。

用法：python tools/selfcheck_round110_energy_cube_item.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import json
import math
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")

ITEM = os.path.join(PKG, "item", "FEEnergyCubeItem.java")
SUPPORT = os.path.join(PKG, "support", "EnergyCubeItemEnergy.java")
MAIN = os.path.join(PKG, "RS_Create_Compat.java")
BE = os.path.join(PKG, "block", "entity", "FEEnergyCubeBlockEntity.java")
BLOCK = os.path.join(PKG, "block", "FEEnergyCubeBlock.java")
CONFIG = os.path.join(PKG, "Config.java")
GEN_ASSETS = os.path.join(ROOT, "tools", "gen_fe_energy_cube_assets.py")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
ZH = os.path.join(LANG_DIR, "zh_cn.json")
EN = os.path.join(LANG_DIR, "en_us.json")

FAILS = []
CHECKS = [0]
COUNTER = [0]
SKIPS = []


# ==================== 基础设施 ====================

def read(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释（保留字符串字面量，避免把 "http://" 当注释切掉）。"""
    out = []
    i = 0
    n = len(text)
    state = None  # None / "line" / "block" / "str" / "char"
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state is None:
            if ch == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if ch == '"':
                state = "str"
            elif ch == "'":
                state = "char"
            out.append(ch)
            i += 1
        elif state == "line":
            if ch == "\n":
                state = None
                out.append(ch)
            i += 1
        elif state == "block":
            if ch == "*" and nxt == "/":
                state = None
                i += 2
                continue
            i += 1
        elif state == "str":
            if ch == "\\":
                out.append(ch)
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == '"':
                state = None
            out.append(ch)
            i += 1
        else:  # char
            if ch == "\\":
                out.append(ch)
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == "'":
                state = None
            out.append(ch)
            i += 1
    return "".join(out)


def method_body(text: str, signature: str) -> str:
    """取 `signature` 之后第一个大括号块的完整内容（含大括号）。找不到返回空串。"""
    idx = text.find(signature)
    if idx < 0:
        return ""
    start = text.find("{", idx)
    if start < 0:
        return ""
    depth = 0
    for k in range(start, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[start:k + 1]
    return ""


def check(label: str, ok: bool, detail: str = "") -> bool:
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % label)
    else:
        print("FAIL %s%s" % (label, ("  <- " + detail) if detail else ""))
        FAILS.append(label)
    return ok


def counterexample(label: str, detected: bool) -> bool:
    COUNTER[0] += 1
    if detected:
        print("反例 %s PASS" % label)
    else:
        print("反例 %s FAIL（这个错误写法没有被判据抓到 = 判据无效）" % label)
        FAILS.append("反例未抓住: " + label)
    return detected


def skip(label: str) -> None:
    SKIPS.append(label)
    print("SKIP %s" % label)


# ==================== 判据（源码级；输入是「可能被篡改的」源码文本） ====================

def item_class_ok(item: str) -> bool:
    """① 物品类存在、继承 RsccHelpBlockItem、构造器接 (Block, Component)。"""
    if not item:
        return False
    code = strip_comments(item)
    return ("public class FEEnergyCubeItem extends RsccHelpBlockItem" in code
            and "public FEEnergyCubeItem(final Block block, @Nullable final Component helpText)" in code
            and "super(block, helpText);" in code)


def item_registered_ok(main: str) -> bool:
    """② `FE_ENERGY_CUBE_ITEM` 用新类注册，且该声明段内不再出现裸 RsccHelpBlockItem。"""
    code = strip_comments(main)
    marker = "FE_ENERGY_CUBE_ITEM ="
    start = code.find(marker)
    if start < 0:
        return False
    # 取「字段声明」那一段（到第一个分号）：FE_ENERGY_CUBE_ITEM = ITEMS.register(...);  恰好一个分号
    end = code.find(";", start)
    if end < 0:
        return False
    segment = code[start:end]
    return ("new cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem(" in segment
            and "FE_ENERGY_CUBE_BLOCK.get()" in segment
            and "RsccHelpBlockItem(" not in segment
            and "block.rs_create_compat.fe_energy_cube.help" in segment)


def capability_once_ok(item: str, main: str, support: str) -> bool:
    """③ 物品 FE 能力注册且仅一次（物品类内 + 主类监听行）。"""
    i, m, s = strip_comments(item), strip_comments(main), strip_comments(support)
    body = method_body(i, "public static void registerCapabilities")
    return (i.count("Capabilities.EnergyStorage.ITEM") == 1
            and i.count("event.registerItem(") == 1
            and "RS_Create_Compat.FE_ENERGY_CUBE_ITEM.get()" in body
            and "new EnergyCubeItemEnergy(stack)" in body
            and m.count("item.FEEnergyCubeItem::registerCapabilities") == 1
            and "class EnergyCubeItemEnergy implements IEnergyStorage" in s
            and s.count("Capabilities.EnergyStorage.ITEM") == 0)


def register_item_calls(code: str):
    """把源码里每个 `registerItem(...)` 调用的**实参整段**切出来（括号配平）。

    只按「文件里同时出现 registerItem 和 FE_ENERGY_CUBE_ITEM」判断会误伤：
    `RS_Create_Compat` 里既有终端物品的 `registerItem(...)`（别的物品），
    又必然出现 `FE_ENERGY_CUBE_ITEM`（字段声明 / 创造模式页签）——
    必须看「这两个东西是不是在**同一次注册调用**里」。
    """
    calls = []
    for match in re.finditer(r"registerItem\s*\(", code):
        start = match.end() - 1
        depth = 0
        for k in range(start, len(code)):
            if code[k] == "(":
                depth += 1
            elif code[k] == ")":
                depth -= 1
                if depth == 0:
                    calls.append(code[start:k + 1])
                    break
    return calls


def dup_registration_ok(texts) -> bool:
    """③-b 全仓扫「把立方物品注册进能力表」的调用只允许出现在物品类里，且物品类内只有一处。"""
    hits = []
    for name, text in texts.items():
        code = strip_comments(text)
        if any("FE_ENERGY_CUBE_ITEM" in call for call in register_item_calls(code)):
            hits.append(name)
    if hits != ["FEEnergyCubeItem.java"]:
        return False
    return len(register_item_calls(strip_comments(texts["FEEnergyCubeItem.java"]))) == 1


def shared_tag_ok(item: str, support: str, be: str, block: str) -> bool:
    """④ 物品与方块共用同一个 NBT 键 + 同一条原版还原链路（本轮最硬的一条）。"""
    i, s, b, blk = strip_comments(item), strip_comments(support), strip_comments(be), strip_comments(block)
    # 物品侧：读 / 写都引用方块实体的公开常量
    reads_shared = "tag.getInt(FEEnergyCubeBlockEntity.TAG_ENERGY)" in i
    writes_shared = "existing.putInt(FEEnergyCubeBlockEntity.TAG_ENERGY, clamped)" in i
    # 容器必须是 BLOCK_ENTITY_DATA（原版方块物品 / 放置还原用的那个），不是飞行背包的 CUSTOM_DATA
    right_container = ("DataComponents.BLOCK_ENTITY_DATA" in i
                       and "DataComponents.CUSTOM_DATA" not in i)
    # 写回必须走原版 API（成对处理 id），不许手搓 CustomData.of(...)
    vanilla_write = ("BlockItem.setBlockEntityData(stack, RS_Create_Compat.FE_ENERGY_CUBE_BLOCK_ENTITY.get(),"
                     " existing)" in i)
    no_handmade = "CustomData.of(" not in i
    # 物品类不许自己另开一个键常量（否则就是平行存储）
    no_own_tag = "static final String TAG_ENERGY" not in i
    # 不许出现字面量键（putInt("...") / getInt("...")）
    no_literal_key = (not re.search(r'putInt\s*\(\s*"', i)) and (not re.search(r'getInt\s*\(\s*"', i))
    # 方块侧：存档 / 读档成对用同一个 TAG_ENERGY
    be_pair = ("tag.putInt(TAG_ENERGY, energyStorage.getEnergyStored());" in b
               and "tag.getInt(TAG_ENERGY)" in b)
    be_tag_value = ('public static final String TAG_ENERGY = "Energy";' in b)
    # 掉落链路：原版 saveToItem（写的就是 saveAdditional 的那份 tag）
    drops_shared = "cube.saveToItem(stack, params.getLevel().registryAccess())" in blk
    # 能力视图不自己存电量，全部委托物品类
    view_delegates = ("FEEnergyCubeItem.getEnergy(stack)" in s
                      and "FEEnergyCubeItem.setEnergy(stack" in s
                      and "CustomData" not in s
                      and "DataComponents" not in s)
    return all([reads_shared, writes_shared, right_container, vanilla_write, no_handmade,
                no_own_tag, no_literal_key, be_pair, be_tag_value, drops_shared, view_delegates])


def bar_ok(item: str, gen_assets: str) -> bool:
    """⑤ 耐久样式电量条三件套 + 条色与资源生成脚本的 ACCENT 青绿逐字节一致。"""
    i = strip_comments(item)
    has_three = ("public boolean isBarVisible" in i
                 and "public int getBarWidth" in i
                 and "public int getBarColor" in i)
    visible_when_not_full = "return getEnergy(stack) < capacity();" in i
    width_13px = re.search(r"Math\.round\(13\.0F \* getEnergy\(stack\) / (?:capacity|capacity\(\))\)", i) is not None
    color_used = "return BAR_COLOR;" in i
    java_color = re.search(r"BAR_COLOR\s*=\s*(0x[0-9A-Fa-f]{6})", i)
    gen_accent = re.search(r"^ACCENT\s*=\s*\(0x([0-9A-Fa-f]{2}),\s*0x([0-9A-Fa-f]{2}),\s*0x([0-9A-Fa-f]{2})",
                           gen_assets, re.MULTILINE)
    if not (java_color and gen_accent):
        return False
    accent = (int(gen_accent.group(1), 16) << 16) | (int(gen_accent.group(2), 16) << 8) | int(gen_accent.group(3), 16)
    return (has_three and visible_when_not_full and width_13px and color_used
            and int(java_color.group(1), 16) == accent)


def tooltip_always_ok(item: str) -> bool:
    """⑥ tooltip 常显：无 Shift / isAdvanced 门控、电量行在顶层无条件、保留父类、不覆写 getTooltipImage。"""
    i = strip_comments(item)
    body = method_body(i, "public void appendHoverText")
    if not body:
        return False
    gated = any(token in body for token in
                ("isAdvanced", "hasShiftDown", "hasControlDown", "isShiftKeyDown", "keyDown"))
    # 「常显」= 电量那一行加在**任何条件分支之前**（后面的 if 只允许是「堆叠提示」这类与按键无关的
    # 状态行；方法体内的 if 数量本身不再是判据，但电量行绝不允许被任何 if 包住）。
    energy_at = body.find('tooltip.add(Component.translatable('
                         '"item.rs_create_compat.fe_energy_cube.tooltip.energy"')
    first_if = body.find("if (")
    unconditional = energy_at >= 0 and (first_if < 0 or energy_at < first_if)
    super_kept = "super.appendHoverText(stack, context, tooltip, flag);" in body
    energy_line = energy_at >= 0
    # RS 的常显帮助文本走 getTooltipImage（BaseBlockItem 返回 HelpTooltipComponent）：
    # 覆写它 = 丢掉帮助文本，所以物品类里一个都不能出现。
    no_image_override = "getTooltipImage" not in i
    # 常显不许靠客户端判定（服务端不可用 + 崩专用服）
    no_client = "import net.minecraft.client" not in i
    return (not gated) and unconditional and super_kept and energy_line \
        and no_image_override and no_client


def stacked_guard_ok(item: str, support: str) -> bool:
    """④-b 堆叠守卫：组件整堆共享一份 ⇒ count > 1 时必须拒绝充放，且 tooltip 说明。

    为什么这是**必须**的而不是可选优化：`ItemStack#split` 用 `copyWithCount` 复制组件、
    `BlockItem#updateCustomBlockEntityTag` 读的是整堆那一份组件（`shrink(1)` 不清组件）——
    允许一堆充放电就等于「拆堆 / 放置各复制一份电量」。
    """
    i, s = strip_comments(item), strip_comments(support)
    # 两个出口各自都得挡（只看「文件里出现过 !isStacked()」会被「只挡一个出口」骗过去）
    extract_body = method_body(s, "public boolean canExtract")
    receive_body = method_body(s, "public boolean canReceive")
    view_guard = ("private boolean isStacked()" in s
                  and "return stack.getCount() > 1;" in s
                  and "return !isStacked();" in extract_body
                  and "return !isStacked();" in receive_body)
    both_ports = ("if (toReceive <= 0 || stack.isEmpty() || isStacked())" in s
                  and "if (toExtract <= 0 || stack.isEmpty() || isStacked())" in s)
    tip = ('"item.rs_create_compat.fe_energy_cube.tooltip.stacked"' in i
           and "if (stack.getCount() > 1) {" in i)
    return view_guard and both_ports and tip


def exact_format_ok(item: str) -> bool:
    """⑦ 精确千分位（复用 formatFeExact），且没有任何 M/K 缩写残留。"""
    i = strip_comments(item)
    body = method_body(i, "public void appendHoverText")
    uses_exact = body.count("ElectricJetpackItem.formatFeExact(") >= 2
    no_compact = ("%.1fM" not in i and "%.1fK" not in i
                  and "M FE" not in body and "K FE" not in body)
    return uses_exact and no_compact


def capacity_matches_ok(item: str, support: str, be: str, config: str) -> bool:
    """⑧ 容量 / 速率与方块同源（配置项 + 回退常量 + 能力视图委托）。"""
    i, s, b = strip_comments(item), strip_comments(support), strip_comments(be)
    item_cap = "return Config.feEnergyCubeCapacity > 0 ? Config.feEnergyCubeCapacity : DEFAULT_CAPACITY;" in i
    item_xfer = "return Config.feEnergyCubeMaxTransfer > 0 ? Config.feEnergyCubeMaxTransfer : DEFAULT_TRANSFER;" in i
    fallbacks = "DEFAULT_CAPACITY = 50_000_000" in i and "DEFAULT_TRANSFER = 100_000" in i
    be_same = ("new OwnEnergyStorage(Config.feEnergyCubeCapacity)" in b
               and "Config.feEnergyCubeMaxTransfer" in b)
    view_same = ("FEEnergyCubeItem.capacity()" in s and "FEEnergyCubeItem.transfer()" in s)
    cfg_defaults = (re.search(r'defineInRange\("feEnergyCubeCapacity",\s*50000000', config) is not None
                    and re.search(r'defineInRange\("feEnergyCubeMaxTransfer",\s*100000', config) is not None)
    return item_cap and item_xfer and fallbacks and be_same and view_same and cfg_defaults


def lang_ok(zh_text: str, en_text: str) -> bool:
    """⑨ zh/en 三个 tooltip 键成对、占位符个数一致、en 不含 CJK。"""
    keys = [
        "item.rs_create_compat.fe_energy_cube.tooltip.energy",
        "item.rs_create_compat.fe_energy_cube.tooltip.cluster",
        "item.rs_create_compat.fe_energy_cube.tooltip.charge",
        "item.rs_create_compat.fe_energy_cube.tooltip.stacked",
    ]
    try:
        zh = json.loads(zh_text)
        en = json.loads(en_text)
    except Exception:
        return False
    for key in keys:
        if key not in zh or key not in en:
            return False
        if zh[key].count("%s") != en[key].count("%s"):
            return False
        if re.search(r"[\u4e00-\u9fff]", en[key]):
            return False
    return True


def lang_referenced_ok(item: str, zh_text: str, en_text: str) -> bool:
    """⑨-b 物品类里出现的每个 translatable 键都能在 zh/en 查到（防「代码加了键、语言忘了」）。"""
    try:
        zh = json.loads(zh_text)
        en = json.loads(en_text)
    except Exception:
        return False
    used = re.findall(r'Component\.translatable\(\s*"([^"]+)"', strip_comments(item))
    return bool(used) and all(key in zh and key in en for key in used)


# ==================== 纯 Python 行为模型（与 Java 同一套语义） ====================

ITEM_CAP = 50_000_000     # = Config.feEnergyCubeCapacity 默认值
ITEM_XFER = 100_000       # = Config.feEnergyCubeMaxTransfer 默认值
BAR_PIXELS = 13


def model_receive(stored, to_receive, simulate=False, cap=ITEM_CAP, xfer=ITEM_XFER):
    """与 EnergyCubeItemEnergy#receiveEnergy 同一套夹量顺序：速率 → 容量空间。"""
    space = max(0, cap - stored)
    accepted = min(min(max(0, to_receive), xfer), space)
    return accepted, (stored if simulate else stored + accepted)


def model_extract(stored, to_extract, simulate=False, cap=ITEM_CAP, xfer=ITEM_XFER):
    """与 #extractEnergy 同一套夹量：速率 → 存量（抽空停在 0，绝不为负）。"""
    extracted = min(min(max(0, to_extract), xfer), max(0, stored))
    return extracted, (stored if simulate else stored - extracted)


def model_place(stored, key_used):
    """物品放下 → loadAdditional 读同一个键。键不同 ⇒ 读不到 ⇒ 0（= 放下丢电）。"""
    return stored if key_used == "Energy" else 0


def model_receive_write_in_simulate(stored, to_receive, cap=ITEM_CAP, xfer=ITEM_XFER):
    """反例模型：simulate 阶段也写。NeoForge 的充电器是「先 simulate 问能吃多少、再 execute 真写」，
    两遍都写 ⇒ 同一个 tick 里充进去**两份**（电量凭空多一倍）。"""
    _, stored = model_receive(stored, to_receive, simulate=False, cap=cap, xfer=xfer)  # 错：simulate 也写
    _, stored = model_receive(stored, to_receive, simulate=False, cap=cap, xfer=xfer)
    return stored


def model_receive_no_rate(stored, to_receive, cap=ITEM_CAP):
    """反例模型：不夹速率（一次灌满，与方块 feEnergyCubeMaxTransfer 口径不一致）。"""
    space = max(0, cap - stored)
    accepted = min(max(0, to_receive), space)
    return accepted, stored + accepted


def model_receive_no_cap(stored, to_receive, xfer=ITEM_XFER):
    """反例模型：不夹容量（溢出，getEnergyStored > getMaxEnergyStored）。"""
    accepted = min(max(0, to_receive), xfer)
    return accepted, stored + accepted


def java_round(value: float) -> int:
    """Java `Math.round(float)`：半数向上（4.5 → 5）。Python 的 round() 是银行家舍入（4.5 → 4）。"""
    return int(math.floor(value + 0.5))


def bar_width(stored, cap=ITEM_CAP) -> int:
    """与 Java `Math.round(13.0F * stored / capacity)` 等价。"""
    return java_round(BAR_PIXELS * stored / cap)


def model_place_from_stack(stack_energy, count):
    """反例模型：允许一堆充放电时，逐块放下 ⇒ 每个方块都拿到<b>整堆那一份</b>组件值。"""
    return stack_energy * count


def model_split_total(stack_energy, splits=2):
    """反例模型：拆堆（`split → copyWithCount` 复制组件）⇒ 每堆都带着同一份电量。"""
    return stack_energy * splits


def model_place_guarded(stack_energy, count):
    """正确语义：count > 1 时能力视图拒绝充放 ⇒ 整堆电量只能是 0/未变，放下不会放大。"""
    return 0 if count > 1 else stack_energy


# ==================== 主流程 ====================

def main() -> int:
    item_text = read(ITEM) if os.path.exists(ITEM) else ""
    support_text = read(SUPPORT) if os.path.exists(SUPPORT) else ""
    main_text = read(MAIN)
    be_text = read(BE)
    block_text = read(BLOCK)
    config_text = read(CONFIG)
    gen_text = read(GEN_ASSETS) if os.path.exists(GEN_ASSETS) else ""
    zh_text = read(ZH)
    en_text = read(EN)

    # ---------------- ① 物品类 ----------------
    if not os.path.exists(ITEM):
        check("① 物品类 item/FEEnergyCubeItem.java 存在", False, "文件缺失")
        return 1
    check("①-a 物品类存在且继承 RsccHelpBlockItem（RS 常显帮助机制照旧）",
          item_class_ok(item_text))
    counterexample("删掉 extends RsccHelpBlockItem（改成裸 BaseBlockItem）⇒ ①-a 判负",
                   item_class_ok(item_text.replace(
                       "public class FEEnergyCubeItem extends RsccHelpBlockItem {",
                       "public class FEEnergyCubeItem extends com.refinedmods.refinedstorage."
                       "common.support.BaseBlockItem {")) is False)

    # ---------------- ② 注册替换 ----------------
    check("②-a FE_ENERGY_CUBE_ITEM 用新类注册（不再是裸 RsccHelpBlockItem），help 文本照旧传",
          item_registered_ok(main_text))
    counterexample("注册改回 RsccHelpBlockItem ⇒ ②-a 判负（物品没有 FE 能力 / 没有电量条）",
                   item_registered_ok(main_text.replace(
                       "new cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem(",
                       "new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(")) is False)

    # ---------------- ③ 能力注册仅一次 ----------------
    check("③-a 物品类注册 Capabilities.EnergyStorage.ITEM 一处，绑定 FE_ENERGY_CUBE_ITEM，视图是 EnergyCubeItemEnergy",
          capability_once_ok(item_text, main_text, support_text))
    check("③-b 主类 FEEnergyCubeItem::registerCapabilities 监听恰好一行（不重复 addListener）",
          strip_comments(main_text).count("item.FEEnergyCubeItem::registerCapabilities") == 1)
    tree_texts = {"FEEnergyCubeItem.java": item_text,
                  "RS_Create_Compat.java": main_text,
                  "EnergyCubeItemEnergy.java": support_text,
                  "FEEnergyCubeBlock.java": block_text}
    check("③-c 全仓「registerItem + FE_ENERGY_CUBE_ITEM」只出现在物品类且仅一处（不会重复注册）",
          dup_registration_ok(tree_texts))
    counterexample("删掉 registerCapabilities 里的 event.registerItem(...) ⇒ ③-a 判负"
                   "（拿在手上充不了电 = 用户实机故障原样复现）",
                   capability_once_ok(
                       item_text.replace("event.registerItem(", "noop("), main_text, support_text) is False)
    counterexample("主类再 addListener 一次（重复注册）⇒ ③-b 判负",
                   strip_comments(main_text + "\nmodEventBus.addListener("
                                  "cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem::"
                                  "registerCapabilities);").count(
                       "item.FEEnergyCubeItem::registerCapabilities") != 1)
    counterexample("主类再手写一次 registerItem 注册立方物品 ⇒ ③-c 判负（同一件物品注册两次 = 未定义行为）",
                   dup_registration_ok(dict(tree_texts, **{
                       "RS_Create_Compat.java": main_text
                       + "\nevent.registerItem(Capabilities.EnergyStorage.ITEM, (s, c) -> null,"
                         " FE_ENERGY_CUBE_ITEM.get());\n"})) is False)

    # ---------------- ④ 同一个 NBT 键 ----------------
    check("④-a 物品读写直接引用 FEEnergyCubeBlockEntity.TAG_ENERGY（两侧同一个键，不是另写字符串）",
          shared_tag_ok(item_text, support_text, be_text, block_text))
    counterexample("物品电量换成自有键（putInt(\"RsccCubeEnergy\", ...)）⇒ ④-a 判负（放下丢电）",
                   shared_tag_ok(item_text.replace(
                       "existing.putInt(FEEnergyCubeBlockEntity.TAG_ENERGY, clamped);",
                       'existing.putInt("RsccCubeEnergy", clamped);'), support_text, be_text,
                       block_text) is False)
    counterexample("用飞行背包那套 CUSTOM_DATA 存电量 ⇒ ④-a 判负（平行存储，放下方块读不到）",
                   shared_tag_ok(item_text.replace(
                       "DataComponents.BLOCK_ENTITY_DATA", "DataComponents.CUSTOM_DATA"),
                       support_text, be_text, block_text) is False)
    counterexample("物品类自己再声明一个 TAG_ENERGY 常量 ⇒ ④-a 判负（键名漂移的起点）",
                   shared_tag_ok(item_text.replace(
                       "    public FEEnergyCubeItem(",
                       '    public static final String TAG_ENERGY = "Energy";\n'
                       "    public FEEnergyCubeItem("), support_text, be_text, block_text) is False)
    counterexample("掉落链路改掉 saveToItem（自己拼 tag）⇒ ④-a 判负",
                   shared_tag_ok(item_text, support_text, be_text,
                                 block_text.replace("cube.saveToItem(stack, params.getLevel().registryAccess());",
                                                    "cube.saveWithoutMetadata(params.getLevel().registryAccess());")) is False)
    counterexample("能力视图自己存一份电量（不走物品类的 get/setEnergy）⇒ ④-a 判负",
                   shared_tag_ok(item_text,
                                 support_text.replace("FEEnergyCubeItem.setEnergy(stack, stored + accepted);",
                                                      'stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,'
                                                      ' d -> d.update(t -> t.putInt("E", 1)));'),
                                 be_text, block_text) is False)

    # ---------------- ④-b 堆叠守卫（复制漏洞） ----------------
    check("④-b 堆叠守卫：count > 1 时能力视图 canReceive/canExtract 与两个读写口全部拒绝（防拆堆/放置复制电量）",
          stacked_guard_ok(item_text, support_text))
    check("④-b2 堆叠状态下 tooltip 明说「拆成单块才能充」（玩家不会以为充电器坏了）",
          "item.rs_create_compat.fe_energy_cube.tooltip.stacked" in item_text
          and "if (stack.getCount() > 1) {" in strip_comments(item_text))
    counterexample("删掉堆叠守卫（canExtract 恒 true）⇒ ④-b 判负（一堆 64 块充一次 → 拆堆变两份电量）",
                   stacked_guard_ok(item_text, support_text.replace(
                       "        return !isStacked();\n    }\n\n    /** 允许被注入",
                       "        return true;\n    }\n\n    /** 允许被注入")) is False)
    counterexample("两个读写口不挡堆叠（只挡 canReceive）⇒ ④-b 判负（充电器不看 canReceive 也会直接写）",
                   stacked_guard_ok(item_text, support_text.replace(
                       "|| stack.isEmpty() || isStacked()", "|| stack.isEmpty()")) is False)
    counterexample("堆叠时不提示 ⇒ ④-b2 判负（玩家看到 0/50,000,000 却充不进去，以为坏了）",
                   ("item.rs_create_compat.fe_energy_cube.tooltip.stacked" in item_text
                    and "if (stack.getCount() > 1) {" in strip_comments(item_text.replace(
                        "if (stack.getCount() > 1) {", "if (false) {"))) is False)

    # ---------------- ⑤ 电量条 ----------------
    check("⑤-a isBarVisible / getBarWidth / getBarColor 三件套齐全，未满才显示、13px 线性、色=BAR_COLOR",
          bar_ok(item_text, gen_text))
    check("⑤-b 条色 = tools/gen_fe_energy_cube_assets.py 的 ACCENT 青绿（两侧数值逐字节相同，写注释说明出处）",
          bar_ok(item_text, gen_text) and "gen_fe_energy_cube_assets.py" in item_text)
    counterexample("删掉 getBarWidth ⇒ ⑤-a 判负（物品栏里没有电量条 = 用户实机故障原样复现）",
                   bar_ok(item_text.replace("    public int getBarWidth(final ItemStack stack) {",
                                            "    public int barWidthRemoved(final ItemStack stack) {"),
                          gen_text) is False)
    counterexample("条色改成随便一个蓝色（0x4FC3F7，飞行背包色）⇒ ⑤-a/⑤-b 判负",
                   bar_ok(item_text.replace("BAR_COLOR = 0x18C8AA", "BAR_COLOR = 0x4FC3F7"),
                          gen_text) is False)

    # ---------------- ⑥ tooltip 常显 ----------------
    check("⑥-a appendHoverText 无 Shift / isAdvanced 门控：电量行顶层无条件追加（不按 Shift 也显示）",
          tooltip_always_ok(item_text))
    check("⑥-b 调 super.appendHoverText 保留父类行为，且不覆写 getTooltipImage（RS 常显帮助文本走它）",
          "super.appendHoverText(stack, context, tooltip, flag);" in strip_comments(item_text)
          and "getTooltipImage" not in strip_comments(item_text))
    counterexample("tooltip 改成按住 Shift 才显示（if (flag.isAdvanced()) 包住电量行）⇒ ⑥-a 判负",
                   tooltip_always_ok(item_text.replace(
                       '        tooltip.add(Component.translatable('
                       '"item.rs_create_compat.fe_energy_cube.tooltip.energy"',
                       '        if (flag.isAdvanced()) tooltip.add(Component.translatable('
                       '"item.rs_create_compat.fe_energy_cube.tooltip.energy"')) is False)
    counterexample("删掉 super.appendHoverText（丢掉父类行为）⇒ ⑥-a 判负",
                   tooltip_always_ok(item_text.replace(
                       "        super.appendHoverText(stack, context, tooltip, flag);\n", "")) is False)
    counterexample("覆写 getTooltipImage 丢掉 RS 常显帮助文本 ⇒ ⑥-b 判负",
                   tooltip_always_ok(item_text.replace(
                       "    // ==================== tooltip（常显，不按 Shift） ====================",
                       "    @Override public java.util.Optional<net.minecraft.world.inventory.tooltip."
                       "TooltipComponent> getTooltipImage(final ItemStack s) { return java.util.Optional.empty(); }"
                       "\n    // ==================== tooltip（常显，不按 Shift） ====================")) is False)

    # ---------------- ⑦ 精确格式 ----------------
    check("⑦-a tooltip 电量两处都走 ElectricJetpackItem.formatFeExact（千分位精确值，第 109 轮口径）",
          exact_format_ok(item_text))
    check("⑦-b 无 %.1fM / %.1fK / 「M FE」等缩写残留",
          exact_format_ok(item_text.replace("ElectricJetpackItem.formatFeExact(",
                                            'String.format("%.1fM", 1.0 * ')) is False)
    counterexample("格式改回 %.1fM 缩写 ⇒ ⑦-a 判负（用户明确要 2,000,000 而不是 2.0M）",
                   exact_format_ok(item_text.replace(
                       "formatFeExact(energy), ElectricJetpackItem.formatFeExact(capacity())",
                       'String.format("%.1fM", energy / 1_000_000.0),'
                       ' String.format("%.1fM", capacity() / 1_000_000.0)')) is False)

    # ---------------- ⑧ 容量 / 速率同源 ----------------
    check("⑧-a 物品容量/速率读 Config.feEnergyCubeCapacity / feEnergyCubeMaxTransfer（与方块同源），回退常量 = 配置默认值",
          capacity_matches_ok(item_text, support_text, be_text, config_text))
    counterexample("物品容量硬编码 5000 万、不读配置 ⇒ ⑧-a 判负（改配置后物品与方块容量不一致）",
                   capacity_matches_ok(item_text.replace(
                       "return Config.feEnergyCubeCapacity > 0 ? Config.feEnergyCubeCapacity : DEFAULT_CAPACITY;",
                       "return DEFAULT_CAPACITY;"), support_text, be_text, config_text) is False)

    # ---------------- ⑨ 语言键 ----------------
    check("⑨-a zh/en 三个 tooltip 键成对、占位符个数一致、en 不含中文",
          lang_ok(zh_text, en_text))
    check("⑨-b 物品类里出现的每个 translatable 键在 zh/en 都能查到（代码/语言不脱节）",
          lang_referenced_ok(item_text, zh_text, en_text))
    counterexample("en_us 的 tooltip.cluster 混入中文 ⇒ ⑨-a 判负",
                   lang_ok(zh_text, en_text.replace(
                       '"This item carries only its own cube\'s charge',
                       '"物品只带这一块的电量')) is False)
    counterexample("en_us 少一个 tooltip 键 ⇒ ⑨-a 判负（游戏里看到原始键名）",
                   lang_ok(zh_text, en_text.replace(
                       '  "item.rs_create_compat.fe_energy_cube.tooltip.charge":'
                       ' "Can be charged or drained by chargers; transfer limit %s FE/t.",\n', "")) is False)
    counterexample("占位符个数中英不一致（en 少一个 %s）⇒ ⑨-a 判负（String.format 直接抛异常 / 少数字）",
                   lang_ok(zh_text, en_text.replace(
                       '"Energy: %s / %s FE"', '"Energy: %s FE"')) is False)

    # ---------------- ⑩ 行为模型 ----------------
    # 一次充 2,000,000：速率 100,000/tick ⇒ 需 20 次；充满后放下方块电量不变（同一个键）
    stored = 0
    ticks = 0
    while stored < 2_000_000:
        got, stored = model_receive(stored, 2_000_000 - stored)
        ticks += 1
        if ticks > 100:
            break
    check("⑩-a 模型：速度上限 100,000/tick ⇒ 充到 2,000,000 正好 20 次；放下方块后仍是 2,000,000（同一个键）",
          stored == 2_000_000 and ticks == 20 and model_place(stored, "Energy") == 2_000_000)
    got, after = model_receive(0, 2_000_000, simulate=True)
    check("⑩-b 模型：simulate 只回答「能吃多少」，物品电量一分不动（充电器先模拟再执行）",
          got == ITEM_XFER and after == 0)
    got, after = model_receive(ITEM_CAP - 10, 1_000_000)
    check("⑩-c 模型：只剩 10 空间 ⇒ 只吃 10（「满」是容量夹量，不是门控）",
          got == 10 and after == ITEM_CAP)
    got, after = model_receive(ITEM_CAP, 1_000_000)
    check("⑩-d 模型：已满 ⇒ 吃 0，电量不变（isBarVisible 也因此为 false）",
          got == 0 and after == ITEM_CAP)
    got, after = model_extract(3_000, 1_000_000)
    check("⑩-e 模型：抽 1,000,000 但只有 3,000 ⇒ 全抽 3,000、停在 0（绝不为负）",
          got == 3_000 and after == 0)
    got, after = model_extract(0, 500)
    check("⑩-f 模型：空电池被抽 ⇒ 0，不产生负电量", got == 0 and after == 0)
    check("⑩-g 模型：电量条 13px —— 空=0、半=7（Java Math.round 半数向上）、满=13",
          bar_width(0) == 0 and bar_width(ITEM_CAP // 2) == 7 and bar_width(ITEM_CAP) == 13)
    check("⑩-h 模型：充 20 次得到 2,000,000 后，抽走 1 次（-100,000）⇒ 放下方块 = 1,900,000（守恒，抽走的电不会回来）",
          model_place(model_extract(2_000_000, ITEM_XFER)[1], "Energy") == 1_900_000)
    check("⑩-i 模型：单块正常路径不被堆叠守卫误伤（count=1 充 100,000 ⇒ 放下方块 100,000）",
          model_place_guarded(100_000, 1) == 100_000)
    check("⑩-j 模型：多块一堆 ⇒ 电量恒为 0（能力视图拒绝充放），放下时不会被放大",
          model_place_guarded(model_receive(0, 100_000)[1], 64) == 0)

    counterexample("模型：物品电量存自有键 ⇒ 放下方块读不到（2,000,000 变 0 = 放下丢电）",
                   model_place(2_000_000, "RsccCubeEnergy") == 0)
    counterexample("模型：simulate 阶段就写 ⇒ 充电器「先 simulate 再 execute」两遍都写，"
                   "一个 tick 充进两份（100,000 变 200,000 = 电量凭空多一倍）",
                   model_receive_write_in_simulate(0, ITEM_XFER) == 2 * ITEM_XFER
                   and model_receive(0, ITEM_XFER, simulate=True)[1] == 0)
    counterexample("模型：不夹速率 ⇒ 一次灌满 2,000,000（与方块 feEnergyCubeMaxTransfer 口径不一致）",
                   model_receive_no_rate(0, 2_000_000)[0] == 2_000_000)
    counterexample("模型：不夹容量 ⇒ 存量 60,000,000 > 容量 50,000,000（Tooltip 出现不可能的数）",
                   model_receive_no_cap(ITEM_CAP, 10_000_000)[1] > ITEM_CAP)
    counterexample("模型：抽成负数 ⇒ -100,000 FE（Tooltip / 物品栏出现负电量）",
                   model_extract(0, ITEM_XFER)[1] - 1 < 0 and model_extract(0, ITEM_XFER)[1] == 0)
    counterexample("模型：允许一堆 64 块充到 100,000 FE ⇒ 逐块放下 = 6,400,000 FE（同一份电量被复制 64 份）",
                   model_place_from_stack(100_000, 64) == 6_400_000)
    counterexample("模型：允许一堆充放电 ⇒ 拆成两堆后总电量翻倍（100,000 → 200,000）",
                   model_split_total(100_000) == 200_000)
    counterexample("模型：用 Python 银行家舍入替代 Java Math.round ⇒ 半格电量条比 Java 少 1px（自检模型失真）",
                   round(13 * 0.5) != java_round(13 * 0.5) and java_round(13 * 0.5) == 7)

    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条，SKIP %d 条）" % (CHECKS[0], COUNTER[0], len(SKIPS)))
    for item in SKIPS:
        print("SKIP - " + item)
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
