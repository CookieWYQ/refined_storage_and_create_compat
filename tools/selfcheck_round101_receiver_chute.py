#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 101 轮自检：网络资源接收器 × Create 溜槽（双向）+ 请求器 / 接收器插件槽 tooltip 统一

判定口径来自用户 1.2.0-beta.6 实机反馈（两条）：

A. 「网络资源接收器还是无法正常跟溜槽交互；与溜槽交互的时候它是无视方向的 —— 你就是写错了。」
   第 96 轮把对外能力改成「可写包装（只进不出）」，但 **Create 6.0.10 的溜槽对上方容器只抽不推**：
     空的普通溜槽每 tick `ChuteBlockEntity#tick` → `handleInputFromAbove()`
       → `grabCapability(Direction.UP)`（查询侧别 = `side.getOpposite()`，**非空**）
       → `ItemHelper.extract(inv, canAccept, ExtractionCountMode.UPTO, 16, false)`
   它唯一「推」进相邻容器的路径是 `handleDownwardOutput`（溜槽自己手里已经有货时往下倒）。
   ⇒ 把溜槽放在接收器**下面**（最自然的「把东西搬走」摆法）只会用到 `extractItem`；
     第 96 轮把 `extractItem` 写死成恒空 ⇒ 溜槽先 SIMULATE 就拿到空、EXECUTE 根本不会发生
     ⇒ 玩家看到「无论溜槽摆在哪个方向都一点反应没有」（用户说的「无视方向」）。
   本轮修正：可写包装默认仍然只进不出（别的方块语义不变），但新增显式「外抽落点」
   `withExtraction` / `withDrain`，接收器把它接到缓存自身的扣减上；
   且注册**不按方向分叉**（`direction == null` 与任意 `Direction` 返回同一份视图）。

B. 「那个插件槽为什么还保留着？而且它的插件槽 tooltip 要保持一致 —— 你去翻一下范围充电器的
   那些插件槽 tooltip 是怎么画的，你这个画的是什么狗屎。」
   产品口径（Lead 拍板）：**槽保留**（速度 / 叠加升级功能不砍），只把 tooltip 统一成
   范围充电器那套 `UpgradeSlotTooltips`（升级物品图标 + 名称 + 单种上限 + `TooltipRenderUtil` 背景）。
   旧实现是各类自己拼的纯文字（`Component.literal("<…>")` + `getHoverName() + " (上限)"`），
   既没有图标也没有背景 ⇒ 同一台机器上「插件槽」长得跟别的机器完全两样。
   「速度 = 每 tick 几批 / 叠加 = 每批多少」这类机制说明搬到**节拍 / 节流行**的悬停提示，
   因此插件槽 tooltip 里只有一份框，且语言键不会变成孤儿。

反例自证见文件末尾 `MUTATIONS`：把每条修复逐一回退，对应断言必须翻红。
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
PKG = "src/main/java/cretae/cookiewyq/rs_create_compat"

FILES = {
    "vo": PKG + "/support/ViewOnlyHandlers.java",
    "be_r": PKG + "/block/entity/NetworkResourceReceiverBlockEntity.java",
    "scr_req": PKG + "/client/screen/NetworkResourceRequesterScreen.java",
    "scr_rcv": PKG + "/client/screen/NetworkResourceReceiverScreen.java",
    "scr_charger": PKG + "/client/screen/RangeChargerScreen.java",
    "menu_req": PKG + "/menu/NetworkResourceRequesterMenu.java",
    "menu_rcv": PKG + "/menu/NetworkResourceReceiverMenu.java",
    "storage": PKG + "/support/RsccUnboundedItemStorage.java",
    "tooltips": PKG + "/client/tooltip/UpgradeSlotTooltips.java",
    # Create 6.0.10 官方源码（构建期解压到 build/createsrc，只读证据）
    "chute": "build/createsrc/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java",
    "itemhelper": "build/createsrc/com/simibubi/create/foundation/item/ItemHelper.java",
    "chute_handler": "build/createsrc/com/simibubi/create/content/logistics/chute/ChuteItemHandler.java",
    "neo_handler": "local_src/neoforge_src/net/neoforged/neoforge/items/ItemHandlerHelper.java",
}
LANG_FILES = {
    "zh": "src/main/resources/assets/rs_create_compat/lang/zh_cn.json",
    "en": "src/main/resources/assets/rs_create_compat/lang/en_us.json",
}

REQ = "gui.rs_create_compat.network_resource_requester."
RCV = "gui.rs_create_compat.network_resource_receiver."

CHECKS = []


def check(cid, desc):
    def decorate(fn):
        CHECKS.append((cid, desc, fn))
        return fn

    return decorate


def read_text(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
        return handle.read()


def has(text, pattern, flags=re.S):
    return re.search(pattern, text, flags) is not None


def missing(text, pattern):
    return re.search(pattern, text, re.S) is None


def body_of(text, anchor_pattern):
    """取出 `anchor_pattern` 命中的方法体（按大括号配对；取不到 ⇒ None）。"""
    match = re.search(anchor_pattern, text)
    if match is None:
        return None
    brace = text.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    index = brace
    while index < len(text):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace:index + 1]
        index += 1
    return None


# ===========================================================================
# A. 接收器 × 溜槽（双向 + simulate 契约 + 抽得出 + 落盘）
# ===========================================================================
@check("A1", "两个能力对 null 与任意方向返回同一份视图（不按方向分叉）")
def check_a1(src, zh, en):
    reg = body_of(src["be_r"],
                  r"public static void registerCapabilities\(final RegisterCapabilitiesEvent event\)")
    if reg is None:
        return False, "找不到 NetworkResourceReceiverBlockEntity#registerCapabilities"
    if has(reg, r"direction\s*==\s*null"):
        return False, "注册里出现 «direction == null» 分叉（用户要求：作为 inventory 提供者不应有方向限制）"
    if has(reg, r"direction\s*!=\s*null"):
        return False, "注册里出现 «direction != null» 分叉（同上）"
    # provider 必须是「忽略 direction 的 lambda」：形参收到但从不参与判断
    providers = re.findall(r"\(blockEntity,\s*direction\)\s*->\s*([^;]{0,400})", reg)
    if len(providers) < 2:
        return False, "registerCapabilities 里不到两个 (blockEntity, direction) -> ... provider"
    for body in providers[:2]:
        if "direction" in body:
            return False, "provider 体内又用到了 direction（等于按面分叉）"
    if not has(reg, r"Capabilities\.ItemHandler\.BLOCK"):
        return False, "ItemHandler.BLOCK 没注册"
    if not has(reg, r"Capabilities\.FluidHandler\.BLOCK"):
        return False, "FluidHandler.BLOCK 没注册"
    return True, "两个能力都由忽略 direction 的 provider 提供（null / 六面同一份视图）"


@check("A2", "两个能力显式接上外抽 / 外排落点（Create 溜槽对上方容器只抽不推）")
def check_a2(src, zh, en):
    reg = body_of(src["be_r"],
                  r"public static void registerCapabilities\(final RegisterCapabilitiesEvent event\)")
    if reg is None:
        return False, "找不到 registerCapabilities"
    if not has(reg, r"\.withExtraction\(blockEntity::extractItemFromExternal\)"):
        return False, "物品能力没有 withExtraction(blockEntity::extractItemFromExternal)"
    if not has(reg, r"\.withDrain\(blockEntity::drainFluidFromExternal\)"):
        return False, "流体能力没有 withDrain(blockEntity::drainFluidFromExternal)"
    vo = src["vo"]
    if missing(vo, r"public static WritableItem writableItem\("):
        return False, "writableItem 的返回类型不是 WritableItem（调用方无法 .withExtraction）"
    if missing(vo, r"public static WritableFluid writableFluid\("):
        return False, "writableFluid 的返回类型不是 WritableFluid（调用方无法 .withDrain）"
    if missing(vo, r"public WritableItem withExtraction\(final ItemExtractSink extractSink\)"):
        return False, "ViewOnlyHandlers 没有 withExtraction"
    if missing(vo, r"public WritableFluid withDrain\(final FluidDrainSink drainSink\)"):
        return False, "ViewOnlyHandlers 没有 withDrain"
    return True, "两向包装：insert/fill 走缓存唯一入口，extract/drain 走缓存自身扣减"


@check("A3", "simulate 零写入：包装只把 simulate 透传，落点只在 execute 分支写")
def check_a3(src, zh, en):
    vo = src["vo"]
    writable = body_of(vo, r"public static final class WritableItem implements IItemHandler")
    fluid = body_of(vo, r"public static final class WritableFluid implements IFluidHandler")
    if writable is None or fluid is None:
        return False, "找不到 WritableItem / WritableFluid"
    insert = body_of(writable, r"public ItemStack insertItem\(final int slot, final ItemStack stack,\s*final boolean simulate\)")
    extract = body_of(writable, r"public ItemStack extractItem\(final int slot, final int amount,\s*final boolean simulate\)")
    if insert is None or extract is None:
        return False, "WritableItem 缺 insertItem / extractItem"
    if missing(insert, r"sink\.insert\(stack, simulate\)"):
        return False, "WritableItem#insertItem 没有把 simulate 透传给落点"
    if has(insert, r"sink\.insert\(stack, false\)") or has(insert, r"sink\.insert\(stack, true\)"):
        return False, "WritableItem#insertItem 把 simulate 写死成常量（真假两边答案必然分叉）"
    if missing(extract, r"extractSink\.extract\(slot, amount, simulate\)"):
        return False, "WritableItem#extractItem 没有把 simulate 透传给外抽落点"
    if has(extract, r"extractSink\.extract\(slot, amount, false\)"):
        return False, "WritableItem#extractItem 把 simulate 写死成 false（看界面就会真抽）"
    fill = body_of(fluid, r"public int fill\(final FluidStack resource, final FluidAction action\)")
    if fill is None or missing(fill, r"sink\.fill\(resource, action == FluidAction\.SIMULATE\)"):
        return False, "WritableFluid#fill 没有把 SIMULATE 透传给落点"
    drain = body_of(fluid, r"public FluidStack drain\(final FluidStack resource, final FluidAction action\)")
    if drain is None or missing(drain, r"action == FluidAction\.SIMULATE"):
        return False, "WritableFluid#drain(FluidStack) 没有透传 SIMULATE"
    return True, "四个写入/抽出方法都按 simulate 透传，没有写死常量"


@check("A4", "接收器入口：simulate 不改缓存，execute 才写 + setChanged（与返回量口径一致）")
def check_a4(src, zh, en):
    be = src["be_r"]
    insert = body_of(be, r"public int insertItemFromExternal\(final ItemStack stack, final boolean simulate\)")
    fill = body_of(be, r"public int fillFluidFromExternal\(final FluidStack stack, final boolean simulate\)")
    extract = body_of(be, r"public ItemStack extractItemFromExternal\(final int slot, final int amount,\s*final boolean simulate\)")
    drain = body_of(be, r"public FluidStack drainFluidFromExternal\(final FluidStack resource, final int maxDrain,\s*final boolean simulate\)")
    for name, body in (("insertItemFromExternal", insert), ("fillFluidFromExternal", fill),
                       ("extractItemFromExternal", extract), ("drainFluidFromExternal", drain)):
        if body is None:
            return False, "找不到 %s" % name
    # ① 推入必须走 applyResource(..., simulate) —— 与请求器投递同一份容量账
    if missing(insert, r"applyResource\(ItemResource\.ofItemStack\(stack\), requested, simulate\)"):
        return False, "insertItemFromExternal 没走 applyResource(..., simulate)"
    if missing(fill, r"requested, simulate\)"):
        return False, "fillFluidFromExternal 没把 simulate 传进 applyResource"
    # ② 抽出必须把 simulate 交给缓存
    if missing(extract, r"itemCache\.extractItem\(slot, amount, simulate\)"):
        return False, "extractItemFromExternal 没把 simulate 交给 itemCache.extractItem"
    if missing(drain, r"simulate\s*\?\s*IFluidHandler\.FluidAction\.SIMULATE\s*:\s*IFluidHandler\.FluidAction\.EXECUTE"):
        return False, "drainFluidFromExternal 没有按 simulate 选 FluidAction"
    # ③ setChanged 只能出现在 !simulate 分支（否则看界面 / 漏斗试插都会把方块标脏）
    for name, body in (("insertItemFromExternal", insert), ("fillFluidFromExternal", fill),
                       ("extractItemFromExternal", extract), ("drainFluidFromExternal", drain)):
        for line in re.findall(r"[^\n]*setChanged\(\)[^\n]*", body):
            context = body[max(0, body.index(line) - 200):body.index(line)]
            if "!simulate" not in context:
                return False, "%s 里的 setChanged() 不在 «!simulate» 分支里" % name
    # ④ 返回量必须是「真正受理/抽出」的量（chute 靠余量判断装不装得下）
    if missing(insert, r"Math\.max\(0L, Math\.min\(requested, accepted\)\)"):
        return False, "insertItemFromExternal 的返回量没有夹到 [0, requested]"
    if missing(extract, r"return taken;"):
        return False, "extractItemFromExternal 没有返回真实抽出的栈"
    return True, "四条入口 simulate 零写入、execute 才 setChanged、返回量如实"


@check("A5", "simulate 的模拟答案能如实回答「还能塞多少」（满仓 ⇒ 原样退回 = 溜槽放弃）")
def check_a5(src, zh, en):
    storage = src["storage"]
    body = body_of(storage, r"public ItemStack insertItem\(final int slot, final ItemStack stack,\s*final boolean simulate\)")
    if body is None:
        return False, "找不到 RsccUnboundedItemStorage#insertItem"
    if missing(body, r"final int insertable = Math\.min\(stack\.getCount\(\), getRemainingCapacity\(\)\);"):
        return False, "insertItem 没有先按剩余容量算 insertable（容量上限失效）"
    if missing(body, r"if \(!simulate\)"):
        return False, "insertItem 的写入没有 «if (!simulate)» 守卫"
    if missing(body, r"final int left = stack\.getCount\(\) - insertable;"):
        return False, "insertItem 的余量不是 «原数量 - insertable»（simulate 与 execute 答案会分叉）"
    if missing(body, r"return left <= 0 \? ItemStack\.EMPTY : stack\.copyWithCount\(left\);"):
        return False, "insertItem 没有把余量原样退回（chute 靠它判断交不交得出去）"
    # 包装层：余量 = 原数量 - 落点受理量
    writable = body_of(src["vo"], r"public static final class WritableItem implements IItemHandler")
    if writable is None or missing(writable, r"final int left = stack\.getCount\(\) - accepted;"):
        return False, "WritableItem#insertItem 的余量不是 «原数量 - 受理量»"
    return True, "容量上限 / simulate 答案 / 余量三者同一套算式"


@check("A6", "getSlots() 恒 > 0：末尾空槽不变式（insertItemStacked 只往空槽里插）")
def check_a6(src, zh, en):
    storage = src["storage"]
    if missing(storage, r"super\(1\);"):
        return False, "存储不是从 1 个槽位起建（空仓时会 getSlots()==0 ⇒ 所有按槽遍历的插入都放弃）"
    if missing(storage, r"private void rscc\$ensureTrailingEmptySlot\(\)"):
        return False, "缺少 rscc$ensureTrailingEmptySlot（末尾必须永远留一个空槽）"
    insert = body_of(storage, r"public ItemStack insertItem\(final int slot, final ItemStack stack,\s*final boolean simulate\)")
    if insert is None or missing(insert, r"rscc\$ensureTrailingEmptySlot\(\);") and \
            not has(storage, r"rscc\$insert\([\s\S]{0,2000}?rscc\$ensureTrailingEmptySlot\(\);"):
        return False, "插入后没有补末尾空槽（空槽用完 ⇒ NeoForge 的「找空槽」循环直接跳过）"
    deserialize = body_of(storage, r"public void deserializeNBT\(final HolderLookup\.Provider provider,\s*final CompoundTag nbt\)")
    if deserialize is None or missing(deserialize, r"rscc\$ensureTrailingEmptySlot\(\);") :
        return False, "读档后没有补末尾空槽（旧存档可能一个空槽都没有）"
    neo = src["neo_handler"]
    if missing(neo, r"if \(inventory\.getStackInSlot\(i\)\.isEmpty\(\)\)\s*\{\s*stack = inventory\.insertItem"):
        return False, "NeoForge ItemHandlerHelper#insertItemStacked 的「找空槽」写法与注释不符（判据过期）"
    return True, "空仓 / 满仓 / 读档后都至少有一个空槽，insertItemStacked 找得到插入点"


@check("A7", "外抽日志状态化节流（溜槽每 tick 来抽，不节流会刷屏）")
def check_a7(src, zh, en):
    be = src["be_r"]
    need = {
        "独立前缀": r'private static final String EXTRACT_LOG_PREFIX = "\[rscc-receiver-extract\]";',
        "状态字段": r"private int lastPullState = PULL_STATE_NONE;",
        "节流判据": r"if \(state == lastPullState && now - lastPullLogTick < DEBUG_THROTTLE_TICKS\)\s*\{\s*return;",
        "状态翻转立刻打": r"lastPullState = state;",
    }
    gone = [name for name, pattern in need.items() if missing(be, pattern)]
    if gone:
        return False, "外抽日志缺少：" + "、".join(gone)
    extract = body_of(be, r"public ItemStack extractItemFromExternal\(final int slot, final int amount,\s*final boolean simulate\)")
    if extract is None or missing(extract, r"logExternalPull\("):
        return False, "extractItemFromExternal 没有调用状态化日志（外抽不可观测）"
    if missing(body_of(be, r"private void logExternalPull\(final int state, final String format, final Object\.\.\. args\)") or "",
               r"LOGGER\.info\(EXTRACT_LOG_PREFIX"):
        return False, "logExternalPull 没有真正打日志"
    return True, "外抽日志三态 + 状态翻转立刻打 + 同状态 600 tick 上限"


@check("A8", "根因证据：Create 溜槽对上方容器只抽不推（源码 file:line 实证）")
def check_a8(src, zh, en):
    chute = src["chute"]
    # tick() → 空溜槽 → handleInputFromAbove()
    if missing(body_of(chute, r"public void tick\(\)") or "",
               r"if \(item\.isEmpty\(\) && !clientSide\)[\s\S]{0,200}?handleInputFromAbove\(\);"):
        return False, "ChuteBlockEntity#tick 里找不到「空溜槽 ⇒ handleInputFromAbove」"
    if missing(chute, r"private void handleInputFromAbove\(\)\s*\{\s*handleInput\(grabCapability\(Direction\.UP\), 1\);") and \
            not has(chute, r"handleInputFromAbove\(\)\s*\{\s*handleInput\(grabCapability\(Direction\.UP\), 1\);"):
        return False, "handleInputFromAbove 不是 «grabCapability(Direction.UP)»（抽路径证据过期）"
    if missing(chute, r"ItemHelper\.extract\(inv, canAccept, mode, count, false\)"):
        return False, "handleInput 没有用 ItemHelper.extract 从上方容器抽（结论 C 的证据过期）"
    # 「只抽不推」：insertItemStacked 只出现在 handleDownwardOutput（溜槽自己手里有货时往下倒）
    if not has(chute, r"handleDownwardOutput\(boolean simulate\)[\s\S]{0,900}?ItemHandlerHelper\.insertItemStacked\("):
        return False, "找不到 handleDownwardOutput 里的 insertItemStacked（推路径证据）"
    if not has(chute, r"ItemHandlerHelper\.insertItemStacked\("):
        return False, "chute 源码里没有 insertItemStacked"
    # 查询侧别是非空值：side.getOpposite()（因此「只注册 null 方向」的写法在溜槽眼里等于不存在）
    if not has(chute, r"BlockCapabilityCache\.create\(\s*Capabilities\.ItemHandler\.BLOCK,\s*serverLevel,\s*pos,\s*side\.getOpposite\(\)"):
        return False, "grabCapability 的查询侧别不是 side.getOpposite()（非空方向）——证据过期"
    if not has(chute, r"level\.getCapability\(Capabilities\.ItemHandler\.BLOCK, pos, side\.getOpposite\(\)\)"):
        return False, "客户端分支的查询侧别证据过期"
    # ItemHelper.extract 的 two-phase（先 SIMULATE 再 EXECUTE）证据
    helper = src["itemhelper"]
    if missing(helper, r"ItemStack stack = inv\.extractItem\(slot, amountToExtractFromThisSlot, true\);[\s\S]{0,600}?inv\.extractItem\(slot, stack\.getCount\(\), false\);") and \
            not has(helper, r"inv\.extractItem\(slot, amountToExtractFromThisSlot, true\)"):
        return False, "ItemHelper#extract 不是先 simulate 再 execute（结论 C 的抽样证据过期）"
    handler = src["chute_handler"]
    if missing(handler, r"if \(!blockEntity\.canAcceptItem\(stack\)\)\s*\{?\s*return stack;"):
        return False, "ChuteItemHandler#insertItem 不再按 canAcceptItem 拒绝（推路径证据过期）"
    return True, "溜槽：空 ⇒ 从上方抽（非空侧别 + SIMULATE/EXECUTE 两段）；手上有货 ⇒ 往下倒"


# ===========================================================================
# B. 请求器 / 接收器插件槽 tooltip 与范围充电器统一（产品口径：槽保留）
# ===========================================================================
@check("B1", "请求器插件槽 tooltip 走 UpgradeSlotTooltips（与范围充电器同一套），不再自拼纯文字")
def check_b1(src, zh, en):
    scr = src["scr_req"]
    if missing(scr, r"UpgradeSlotTooltips\.render\(\s*upgradeSlot, guiGraphics, font, mouseX, mouseY\)"):
        return False, "请求器没有调用 UpgradeSlotTooltips.render(upgradeSlot, ...)"
    if has(scr, r"renderUpgradeTooltipWithPacing\("):
        return False, "请求器还在调用自己那套 renderUpgradeTooltipWithPacing"
    if has(scr, r'Component\.literal\("<"\)'):
        return False, "请求器还在自己拼 <空升级槽位> 的纯文字标题"
    if has(scr, r"maxPerUpgrade\("):
        return False, "请求器还在自己算单种上限（统一实现里已有）"
    if missing(src["scr_charger"], r"UpgradeSlotTooltips\.render\(\s*upgradeSlot, guiGraphics, font, mouseX, mouseY\)"):
        return False, "范围充电器的统一调用不见了（对照基准丢失）"
    return True, "请求器与范围充电器调用同一个实现（图标行 + 原版背景）"


@check("B2", "接收器插件槽 tooltip 同样走统一那套")
def check_b2(src, zh, en):
    scr = src["scr_rcv"]
    if missing(scr, r"UpgradeSlotTooltips\.render\(\s*upgradeSlot, guiGraphics, font, mouseX, mouseY\)"):
        return False, "接收器没有调用 UpgradeSlotTooltips.render(upgradeSlot, ...)"
    if has(scr, r"renderUpgradeTooltipWithPacing\("):
        return False, "接收器还在调用自己那套 renderUpgradeTooltipWithPacing"
    if has(scr, r'Component\.literal\("<"\)'):
        return False, "接收器还在自己拼 <空升级槽位> 的纯文字标题"
    # 统一实现必须仍是「图标行」：UpgradeSlotTooltips 用 UpgradeItemTooltipComponent 画图标
    tooltips = src["tooltips"]
    if missing(tooltips, r"new UpgradeItemTooltipComponent\(stack, max\)"):
        return False, "UpgradeSlotTooltips 不再画升级物品图标（统一口径退化）"
    if missing(tooltips, r"TooltipRenderUtil\.renderTooltipBackground\("):
        return False, "UpgradeSlotTooltips 不再画原版背景"
    return True, "接收器与请求器 / 范围充电器同一套（图标 + 背景 + 单种上限）"


@check("B3", "产品口径 B：请求器 6 个插件槽保留，节流仍读升级容器（功能未砍）")
def check_b3(src, zh, en):
    menu = src["menu_req"]
    be = PKG + "/block/entity/NetworkResourceRequesterBlockEntity.java"
    be_text = read_text(be) if os.path.isfile(os.path.join(ROOT, be)) else ""
    if not be_text:
        return False, "读不到 NetworkResourceRequesterBlockEntity.java"
    if missing(menu, r"public static final int UPGRADE_COUNT = cretae\.cookiewyq\.rs_create_compat\.support\s*\.RsccSpeedStackUpgradeContainer\.SLOTS;"):
        return False, "UPGRADE_COUNT 不再来自 RsccSpeedStackUpgradeContainer.SLOTS（两处各写一个 6 的风险）"
    if missing(menu, r"addSlot\(new UpgradeSlot\(upgrades, i, UPGRADE_X, UPGRADE_Y0 \+ i \* 18, ALLOWED_UPGRADES\)\);"):
        return False, "请求器菜单不再注册 UpgradeSlot（槽被删 = 速度/叠加升级失效）"
    if missing(be_text, r"upgradeContainer\.throttleBatchesPerTick\(\)"):
        return False, "请求器投递节流不再读升级容器（速度升级失效）"
    if missing(be_text, r"upgradeContainer\.throttleBatch\(\)"):
        return False, "请求器投递批量不再读升级容器（叠加升级失效）"
    return True, "6 槽保留、节流仍由升级容器驱动"


@check("B4", "客户端 / 服务端槽位数一致（槽位注册不按端分叉）")
def check_b4(src, zh, en):
    for key, menu_text in (("menu_req", src["menu_req"]), ("menu_rcv", src["menu_rcv"])):
        ctor = body_of(menu_text, r"public NetworkResource(?:Requester|Receiver)Menu\(final int id,\s*final Inventory inventory,\s*@Nullable final NetworkResource\w+BlockEntity \w+\)")
        if ctor is None:
            return False, "%s 找不到三参构造器" % key
        # 升级槽循环必须在「服务端 / 客户端容器选择」的三元表达式之后，且不被 if 包起来
        if not has(ctor, r"\(\)\s*->\s*false\);\s*\n\s*for \(int i = 0; i < UPGRADE_COUNT; i\+\+\)\s*\{\s*\n\s*addSlot\(new UpgradeSlot\("):
            return False, "%s 的升级槽循环可能被 if(...) 包住 ⇒ 两端槽位数会不一致（历史断线根因）" % key
        if len(re.findall(r"addSlot\(", ctor)) != 3:
            return False, "%s 的 addSlot 调用点不是 3 处（升级 / 背包 / 快捷栏）" % key
        if missing(ctor, r"for \(int row = 0; row < 3; row\+\+\)") or \
                missing(ctor, r"for \(int col = 0; col < 9; col\+\+\)"):
            return False, "%s 的背包 / 快捷栏循环尺寸变了（槽位总数与屏幕几何会分叉）" % key
    # 屏幕自己不得再 addSlot（客户端槽位只由菜单决定）
    for key in ("scr_req", "scr_rcv"):
        if has(src[key], r"\baddSlot\("):
            return False, "%s 自己在界面上 addSlot（客户端 / 服务端槽位数会分叉）" % key
    return True, "两台机器的菜单构造器两端同一条路径；屏幕不加槽"


@check("B5", "语言键：引用键中英齐全，机制说明键仍有引用（不产生孤儿键）")
def check_b5(src, zh, en):
    referenced = set()
    for key, prefix in (("scr_req", REQ), ("scr_rcv", RCV)):
        for match in re.finditer(r'LANG \+ "([A-Za-z0-9_.]+)"', src[key]):
            referenced.add(prefix + match.group(1))
    # 机制说明键仍在用（从插件槽 tooltip 搬到节流行后，不能变成孤儿键）
    moved = ["upgrade.speed.effect", "upgrade.stack.effect"]
    for field in moved:
        if (REQ + field) not in referenced and (RCV + field) not in referenced:
            return False, "%s 不再被任何界面引用（会变成孤儿语言键）" % field
    for field in ("upgrade.one_by_one",):
        if (RCV + field) not in referenced:
            return False, "接收器的 %s 不再被引用（孤儿语言键）" % field
    problems = []
    for key in ("scr_req", "scr_rcv"):
        prefix = REQ if key == "scr_req" else RCV
        for match in re.finditer(r'LANG \+ "([A-Za-z0-9_.]+)"', src[key]):
            full = prefix + match.group(1)
            if full not in zh or full not in en:
                problems.append(full)
    if problems:
        return False, "界面引用了语言文件里没有的键：" + "、".join(sorted(set(problems))[:6])
    return True, "本轮 0 个新键；机制说明键仍有引用"


# ===========================================================================
# 反例自证
# ===========================================================================
MUTATIONS = [
    ("A1", "be_r",
     [(r"\(blockEntity, direction\) ->\s*\n\s*ViewOnlyHandlers\.writableItem\(blockEntity\.itemCache,",
       "(blockEntity, direction) -> direction == null\n                ? ViewOnlyHandlers.writableItem(blockEntity.itemCache,"),
      (r"\.withExtraction\(blockEntity::extractItemFromExternal\)",
       ".withExtraction(blockEntity::extractItemFromExternal) : null")],
     "注册改成「只在 direction == null 时暴露」（溜槽按面查询 ⇒ 根本看不见接收器）"),
    ("A2", "be_r",
     [(r"\.withExtraction\(blockEntity::extractItemFromExternal\)",
       "// mutated: 不接外抽落点")],
     "不接外抽落点（溜槽只从上方抽 ⇒ 与接收器完全不交互 = 用户第 101 轮那条 bug）"),
    ("A3", "vo",
     [(r"sink\.insert\(stack, simulate\)", r"sink.insert(stack, false)")],
     "包装把 simulate 写死成 false（漏斗 / 溜槽只在 SIMULATE 阶段问一句就把货写进缓存）"),
    ("A4", "be_r",
     [(r"applyResource\(ItemResource\.ofItemStack\(stack\), requested, simulate\)",
       "applyResource(ItemResource.ofItemStack(stack), requested, false)")],
     "推入入口忽略 simulate 直接真写（模拟会变成真扣容量）"),
    ("A5", "storage",
     [(r"if \(!simulate\) \{\n            rscc\$insert\(stack, insertable\);",
       "rscc$insert(stack, insertable);")],
     "存储层忽略 simulate 直接写（Jade / 漏斗探测会把缓存写脏）"),
    ("A5", "storage",
     [(r"final int insertable = Math\.min\(stack\.getCount\(\), getRemainingCapacity\(\)\);",
       "final int insertable = stack.getCount(); // mutated: 容量上限失效")],
     "余量不再按容量上限算（满仓也报「能收」，溜槽交出去却写不进 ⇒ 卡货）"),
    ("A6", "storage",
     [(r"rscc\$ensureTrailingEmptySlot\(\);", "// mutated: 不再补末尾空槽")],
     "不再保证末尾有空槽（insertItemStacked 只在空槽插入 ⇒ 空仓后第 N 种物品再也推不进来）"),
    ("A7", "be_r",
     [(r"if \(state == lastPullState && now - lastPullLogTick < DEBUG_THROTTLE_TICKS\)",
       "if (false)")],
     "外抽日志不再按状态节流（溜槽每 tick 抽一次 ⇒ 每秒 20 条）"),
    ("A8", "chute",
     [(r"handleInputFromAbove", "handleInputFromAboveGone")],
     "Create 溜槽源码证据被改名（判据其实是硬编码想象，不是读源码）"),
    ("B1", "scr_req",
     [(r"UpgradeSlotTooltips\.render\(\s*upgradeSlot, guiGraphics, font, mouseX, mouseY\)",
       "renderUpgradeTooltipWithPacing(upgradeSlot, guiGraphics, mouseX, mouseY)")],
     "请求器退回自己那套纯文字 tooltip（与范围充电器长得两样）"),
    ("B2", "scr_rcv",
     [(r"UpgradeSlotTooltips\.render\(upgradeSlot, guiGraphics, font, mouseX, mouseY\);",
       "renderUpgradeTooltipWithPacing(upgradeSlot, guiGraphics, mouseX, mouseY);")],
     "接收器退回自己那套纯文字 tooltip"),
    ("B3", "menu_req",
     [(r"for \(int i = 0; i < UPGRADE_COUNT; i\+\+\) \{\n            addSlot\(new UpgradeSlot\(upgrades, i, UPGRADE_X, UPGRADE_Y0 \+ i \* 18, ALLOWED_UPGRADES\)\);\n        \}",
       "// mutated: 请求器的插件槽被删掉")],
     "请求器删掉 6 个插件槽（速度 / 叠加升级彻底失效，产品口径 B 被破坏）"),
    ("B4", "menu_rcv",
     [(r"\(\)\s*->\s*false\);\s*\n\s*for \(int i = 0; i < UPGRADE_COUNT; i\+\+\)",
       "() -> false);\n        if (receiver != null) {\n        for (int i = 0; i < UPGRADE_COUNT; i++")],
     "升级槽只在服务端注册（客户端槽位少 6 格 ⇒ 点一下就断线）"),
    ("B5", "scr_req",
     [(r'LANG \+ "upgrade\.speed\.effect"', 'LANG + "upgrade.speed.effect_typo"')],
     "机制说明键写错（界面显示原始 key，且真键变孤儿）"),
]


def apply_mutation(src, rel_key, replacements):
    text = src[rel_key]
    for pattern, replacement in replacements:
        new_text = re.sub(pattern, replacement, text, count=0, flags=re.S)
        if new_text == text:
            return None
        text = new_text
    return text


def run_all(src, zh, en):
    results = []
    for cid, desc, fn in CHECKS:
        try:
            ok, detail = fn(src, zh, en)
        except Exception as exc:
            ok, detail = False, "断言抛异常：%r" % (exc,)
        results.append((cid, desc, ok, detail))
    return results


def main():
    print("=" * 78)
    print("第 101 轮自检：接收器 × Create 溜槽（双向）+ 插件槽 tooltip 统一")
    print("=" * 78)

    src = {}
    broken = []
    for key, rel in FILES.items():
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            broken.append(rel)
            src[key] = ""
        else:
            src[key] = read_text(rel)
    langs = {}
    for key, rel in LANG_FILES.items():
        try:
            with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
                langs[key] = json.load(handle)
        except Exception as exc:
            broken.append("%s (%r)" % (rel, exc))
            langs[key] = {}
    if broken:
        print("[FAIL] 0 覆盖：以下文件读不到 ⇒ 判据本身失效")
        for rel in broken:
            print("       - %s" % rel)
        return 1

    results = run_all(src, langs["zh"], langs["en"])
    failures = [(cid, desc, detail) for cid, desc, ok, detail in results if not ok]
    for cid, desc, ok, detail in results:
        print("%s %s %s" % ("PASS" if ok else "FAIL", cid, desc))
        if not ok:
            print("       -> %s" % detail)

    mutation_results = []
    for cid, rel_key, replacements, why in MUTATIONS:
        target = None
        for existing_id, existing_desc, fn in CHECKS:
            if existing_id == cid:
                target = fn
                break
        if target is None:
            mutation_results.append((cid, why, False, "MUTATIONS 指向不存在的断言 id"))
            continue
        mutated = apply_mutation(src, rel_key, replacements) if replacements else None
        if replacements and mutated is None:
            mutation_results.append((cid, why, False, "回退模式没匹配上（自检写错了）"))
            continue
        mutated_src = dict(src)
        if mutated is not None:
            mutated_src[rel_key] = mutated
        try:
            ok_after, _ = target(mutated_src, langs["zh"], langs["en"])
        except Exception:
            ok_after = False
        caught = not ok_after
        mutation_results.append((cid, why, caught,
                                 "回退后仍判 PASS（判据抓不到这条修复）" if not caught else ""))

    if failures:
        print("-" * 78)
        print("[result] 自检失败：%d / %d 项断言未通过" % (len(failures), len(results)))
        return 1

    bad = [row for row in mutation_results if not row[2]]
    print("-" * 78)
    print("[mutations] 反例自证：%d / %d 条回退被对应断言抓到" %
          (len(mutation_results) - len(bad), len(mutation_results)))
    for cid, why, ok, why_fail in mutation_results:
        print("%s MUT %s %s" % ("PASS" if ok else "FAIL", cid, why))
        if not ok:
            print("       -> %s" % why_fail)
    if bad:
        print("[result] 自检失败：%d 条回退抓不到（判据有洞）" % len(bad))
        return 1
    print("[result] 全部通过：%d 项断言 + %d 条反例自证" % (len(results), len(mutation_results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
