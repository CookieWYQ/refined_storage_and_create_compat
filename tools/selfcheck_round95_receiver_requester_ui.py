#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 95 轮 WS-A 自检：网络资源接收器 / 请求器（发送方）界面重做 + 缓存可取 + Jade 只暴露接口。

判定口径来自 `docs/ROUND95_USER_FEEDBACK.md` 第 1 节 #2/#3/#4：

#2 「资源接收和资源发送他们的界面并没有发生多大的改变」⇒ 两个界面都必须真的显示内容：
   接收器显示缓存（物品格 + 流体条目 + 摘要），请求器显示目标 / 订单图标×数量 / 已投·剩余·缺口 / 节拍 / 状态行。
#3 「还是不能正常提取里面的物品和资源」⇒ 接收器缓存必须能从界面取出：左键取一组 / Shift 取全部 /
   流体条目点击换 1 桶；取出走真实扣减（`extractItem` / `drain`），不许复制；取不走不扣并提示。
#4 「Jade 已经帮你实现了……不需要自己写按住 Shift 看明细」⇒ 两台 BE 暴露 ItemHandler/FluidHandler 能力，
   自定义 provider 只加「Jade 结构上拿不到的那一行文本」，不得自己判按键、不得自己列容器内容。

外加：`[rscc-receiver-output]` 刷屏改成「状态变化才记 / 30 秒至多一条」；
      请求器「清空订单」走既有 C2S 包 + 服务端校验；新增语言键必须中英齐全。

反例自证：下面 `MUTATIONS` 把每一条修复逐一回退，期望对应断言翻红；任何一条回退抓不到即判负。
"""
import io
import json
import os
import re
import sys

# 本机控制台代码页是 GBK（cp936），断言消息里有 ⇒ / — 这类字符时直接 print 会
# UnicodeEncodeError 崩掉（2026-10-25 全量自检里真崩过一次，被误当成「自检失败」）。
# 统一改成 UTF-8 + errors=replace：结论行永远能打出来。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "src/main/java/cretae/cookiewyq/rs_create_compat"

FILES = {
    "be_r": PKG + "/block/entity/NetworkResourceReceiverBlockEntity.java",
    "be_q": PKG + "/block/entity/NetworkResourceRequesterBlockEntity.java",
    "menu_r": PKG + "/menu/NetworkResourceReceiverMenu.java",
    "menu_q": PKG + "/menu/NetworkResourceRequesterMenu.java",
    "scr_r": PKG + "/client/screen/NetworkResourceReceiverScreen.java",
    "scr_q": PKG + "/client/screen/NetworkResourceRequesterScreen.java",
    # 第 96 轮：订单内容（图标×数量 / 搜索 / 删除 / 改数量）搬到网格主界面，本自检也要盯它
    "scr_grid": PKG + "/client/grid/ResourcePickerScreen.java",
    "pkt_order": PKG + "/network/RequesterOrderPacket.java",
    "pkt_sync": PKG + "/network/SyncRequesterConfigPacket.java",
    # 第 96 轮：接收器自己那份分页快照（与请求器的 pkt_sync 是两份不同的包，不能混用）
    "pkt_rsync": PKG + "/network/SyncReceiverConfigPacket.java",
    "pkt_entry": PKG + "/network/ReceiverCacheEntry.java",
    "jade_payload": PKG + "/compat/jade/RsccReceiverJadePayload.java",
    "jade_provider": PKG + "/client/jade/RsccReceiverJadeProvider.java",
}
LANG_FILES = {
    "zh": "src/main/resources/assets/rs_create_compat/lang/zh_cn.json",
    "en": "src/main/resources/assets/rs_create_compat/lang/en_us.json",
}

# ---------------------------------------------------------------------------
# 本轮新增语言键（由 Lead 统一写入 lang/*.json；值见回报清单）
# ---------------------------------------------------------------------------
R = "gui.rs_create_compat.network_resource_receiver."
Q = "gui.rs_create_compat.network_resource_requester."
NEW_LANG_KEYS = [
    R + "cache.take_all", R + "cache.take_all.hint", R + "cache.slot.hint",
    R + "cache.summary", R + "cache.empty", R + "cache.more",
    R + "cache.fluid", R + "cache.fluid.none", R + "cache.fluid.hint",
    R + "cache.fluid.page",
    R + "msg.cache_taken_all", R + "msg.cache_bag_full", R + "msg.cache_empty",
    R + "msg.fluid_taken", R + "msg.fluid_empty", R + "msg.fluid_insufficient",
    R + "msg.fluid_no_container", R + "msg.fluid_no_space", R + "msg.fluid_gone",
    R + "status.named.facing",
    Q + "order.clear", Q + "order.clear.hint", Q + "order.none",
    Q + "order.entry.item", Q + "order.entry.fluid",
    Q + "order.label", Q + "order.label.more", Q + "order.more",
    Q + "order.stats", Q + "order.stats.active", Q + "order.stats.hint",
    Q + "order.stats.units",
    Q + "msg.order_cleared", Q + "msg.order_already_empty",
]

CHECKS = []


def check(cid, desc):
    def decorate(fn):
        CHECKS.append((cid, desc, fn))
        return fn

    return decorate


def read_text(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
        return handle.read()


def line_of(text, pattern):
    """pattern 首次出现在第几行（只用于报错定位）。"""
    match = re.search(pattern, text, re.S)
    if match is None:
        return -1
    return text.count("\n", 0, match.start()) + 1


def has(text, pattern, flags=re.S):
    return re.search(pattern, text, flags) is not None


def missing(text, pattern):
    return re.search(pattern, text, re.S) is None


def at(rel, pattern, text=None):
    return "%s:%s" % (rel, line_of(text if text is not None else read_text(rel), pattern))


# ---------------------------------------------------------------------------
# #2 接收器界面：缓存真的可见（物品格 + 流体条目 + 摘要，且全部来自 S2C 快照）
# ---------------------------------------------------------------------------
@check("A1", "接收器菜单：资源列表 2 列 × 6 行 = 12 条/页（P5 复核；旧 16 格固定镜像槽整段移除）")
def check_a1(src, zh, en):
    menu = src["menu_r"]
    need = {
        "PAGE_COLS = 2": r"public static final int PAGE_COLS = 2;",
        "PAGE_ROWS = 6": r"public static final int PAGE_ROWS = 6;",
        "ROW_H = 12（12px 行高 / 图标）": r"public static final int ROW_H = 12;",
        "PAGE_SIZE 由行列推导": r"public static final int PAGE_SIZE = PAGE_COLS \* PAGE_ROWS;",
        "PLAYER_START 由升级槽推导": r"public static final int PLAYER_START = UPGRADE_START \+ UPGRADE_COUNT;",
    }
    gone = [name for name, pattern in need.items() if not has(menu, pattern)]
    if gone:
        return False, "菜单分页常量缺：" + "、".join(gone)
    for stale in ("CACHE_VIEW_COUNT", "CACHE_COLS", "CacheSlot", "SimpleMirrorContainer"):
        if has(menu, r"\b" + stale + r"\b"):
            return False, "仍残留旧 16 格固定实现的 %s（第 96 轮要求整段替换）" % stale
    return True, "2×6 = 12 条/页；旧固定格实现零残留"

@check("A2", "接收器界面：资源行（图标 + 名称 + 数量）+ 摘要 + 行 tooltip 真的画出来")
def check_a2(src, zh, en):
    scr = src["scr_r"]
    needed = {
        "逐行渲染": r"for \(int i = 0; i < entries\.size\(\); i\+\+\)",
        "图标按 ICON_SCALE 缩放": r"pose\.scale\(NetworkResourceReceiverMenu\.ICON_SCALE,",
        "物品图标": r"guiGraphics\.renderItem\(entry\.item\(\), 0, 0\)",
        "流体图标": r"GhostMarkerRenderer\.renderFluid\(guiGraphics, 0, 0, entry\.fluid\(\)\)",
        "名称按 NAME_X 偏移": r"x \+ NetworkResourceReceiverMenu\.NAME_X",
        "行悬停高亮": r"if \(i == hovered\)",
        "数量文本": r"RsccNumberField\.compact\(entry\.amount\(\)\)",
        "物品摘要行": r"private Component cacheSummary\(\)",
        "流体摘要行": r"private Component fluidSummary\(\)",
        "行 tooltip（完整名称 / 操作说明）": r"private void renderEntryTooltip",
        "页眉（页码箭头）": r"private void renderPageHeader",
    }
    missing_items = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing_items:
        return False, "接收器界面缺少：" + "、".join(missing_items)
    labels = re.search(r"protected void renderLabels\(.*?\n    \}", scr, re.S)
    if labels is None or not has(labels.group(0), r"renderEntries\(guiGraphics, mouseX, mouseY\);") \
            or not has(labels.group(0), r"renderPageHeader\(guiGraphics\);") \
            or not has(labels.group(0), r"cacheSummary\(\)"):
        return False, "renderLabels 没有真正调用 renderEntries / renderPageHeader / cacheSummary（画不出来）"
    if has(scr, r"menu\.getReceiver\("):
        return False, "客户端界面读了方块实体（menu.getReceiver(...)），违反「菜单重建不读 BE」"
    return True, "列表逐行（图标/名称/数量）+ 摘要 + 行 tooltip 都在；客户端不读方块实体"

# ---------------------------------------------------------------------------
# #3 缓存真的能取出：左键一组 / Shift 全部 / 流体换桶；真实扣减、不复制
# ---------------------------------------------------------------------------
@check("B1", "S2C 分页快照：带 page / pageCount / totalTypes / 当前页条目（不一次塞上千条）")
def check_b1(src, zh, en):
    pkt = src["pkt_rsync"]
    if not has(pkt, r"int page, int pageCount, int totalTypes,\s*List<ReceiverCacheEntry> entries\)"):
        return False, "SyncReceiverConfigPacket 没有 page/pageCount/totalTypes/entries 组件"
    for pattern, name in (
        (r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.page\(\)\);", "编码 page"),
        (r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.pageCount\(\)\);", "编码 pageCount"),
        (r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.totalTypes\(\)\);", "编码 totalTypes"),
        (r"ReceiverCacheEntry\.STREAM_CODEC\.encode\(buf, entry\);", "编码条目"),
        (r"entries\.add\(ReceiverCacheEntry\.STREAM_CODEC\.decode\(buf\)\);", "解码条目"),
    ):
        if missing(pkt, pattern):
            return False, "SyncReceiverConfigPacket 缺少：" + name
    menu = src["menu_r"]
    if missing(menu, r"final List<ReceiverCacheEntry> pageEntries = slice\(all, page\);") \
            and missing(menu, r"final List<ReceiverCacheEntry> pageEntries = slice\("):
        return False, "菜单没有把全量快照切片成「当前一页」"
    if missing(menu, r"final int from = Math\.max\(0, page\) \* PAGE_SIZE;"):
        return False, "slice 没有按 PAGE_SIZE 计算起点（每页行数不受控）"
    return True, "分页快照四处一致 + 服务端按 PAGE_SIZE 切片"

@check("B2", "物品取出：按身份跨格真实扣减（不是复制），放不下的回填缓存")
def check_b2(src, zh, en):
    menu = src["menu_r"]
    match = re.search(r"private void takeItem\(final Player player, final ItemStack identity, final boolean all\)\s*\{(.*?)\n    \}",
                      menu, re.S)
    if match is None:
        return False, "找不到 takeItem"
    body = match.group(1)
    need = {
        "按身份定位": r"ItemStack\.isSameItemSameComponents\(each, wanted\)",
        "真实扣减": r"cache\.extractItem\(slot, Math\.min\(remaining, each\.getCount\(\)\), false\)",
        "回填缓存": r"cache\.insertItem\(0, rest, false\)",
        "绝不销毁": r"player\.drop\(refused, false\)",
    }
    gone = [name for name, pattern in need.items() if not has(body, pattern)]
    if gone:
        return False, "takeItem 缺少：" + "、".join(gone)
    if has(body, r"extractItem\([^)]*, true\)"):
        return False, "takeItem 用 SIMULATE 当扣减（玩家会白拿）"
    if has(body, r"getStackInSlot\([^)]*\)\.copy\(\)") and missing(body, r"extractItem"):
        return False, "takeItem 只复制了缓存内容（复制缺陷）"
    return True, "身份定位 → extractItem(EXECUTE) → 回填；无复制、无 SIMULATE 扣减"

@check("B3", "手势分档：物品左键=一组 / Shift=全部；流体左键=1 桶 / Shift=尽可能多；「取全部」仍在")
def check_b3(src, zh, en):
    menu = src["menu_r"]
    item = re.search(r"private void takeItem\(final Player player, final ItemStack identity, final boolean all\)\s*\{(.*?)\n    \}",
                     menu, re.S)
    fluid = re.search(r"private void takeFluid\(final Player player, final FluidStack identity, final boolean all\)\s*\{(.*?)\n    \}",
                      menu, re.S)
    if item is None or fluid is None:
        return False, "找不到 takeItem / takeFluid"
    if not has(item.group(1), r"final int want = all \? stored : Math\.min\(perStack, stored\);"):
        return False, "物品没有「一组 vs 全部」的数量夹取"
    if not has(fluid.group(1), r"final int buckets = all \? maxBuckets : 1;"):
        return False, "流体没有「1 桶 vs 尽可能多整桶」的分档"
    if not has(menu, r"public static final int BUTTON_TAKE_ALL_ITEMS = 0;") \
            or not has(menu, r"private void takeAllCacheItems\(final Player player\)"):
        return False, "「取全部」入口不见了（用户仍需要一键腾空接收器）"
    if has(menu, r"takeWholeCacheSlot"):
        return False, "仍残留 takeWholeCacheSlot（旧「只取一组」实现）"
    return True, "物品 / 流体都分档；「取全部」入口仍在；无残留旧方法"

@check("B4", "取不走不扣：全部取出先塞背包、放不下的 insertItem 回缓存 + BAG_FULL 提示")
def check_b4(src, zh, en):
    menu = src["menu_r"]
    match = re.search(r"private void takeAllCacheItems\(final Player player\)\s*\{(.*?)\n    \}", menu, re.S)
    if match is None:
        return False, "找不到 takeAllCacheItems"
    body = match.group(1)
    need = {
        "真实扣减": r"cache\.extractItem\(slot, stored\.getCount\(\), false\)",
        "背包": r"player\.getInventory\(\)\.add\(rest\)",
        "回填缓存": r"cache\.insertItem\(0, rest, false\)",
        "放不下提示": r"MSG_CACHE_BAG_FULL",
        "空缓存提示": r"MSG_CACHE_EMPTY",
    }
    gone = [name for name, pattern in need.items() if not has(body, pattern)]
    if gone:
        return False, "takeAllCacheItems 缺少：" + "、".join(gone)
    return True, "先取后放、放不下回填、两条提示都在"


@check("B5", "流体取出口：先模拟桶形态 → 真 drain 恰好 1 桶 → 消耗空桶 → 发放满桶（失败互相回滚）")
def check_b5(src, zh, en):
    menu = src["menu_r"]
    take = re.search(r"private void takeFluid\(final Player player, final FluidStack identity, final boolean all\)\s*\{(.*?)\n    \}",
                     menu, re.S)
    probe = re.search(r"private static ItemStack simulateFilledBucket\(final IFluidHandler source\)\s*\{(.*?)\n    \}",
                      menu, re.S)
    if take is None or probe is None:
        return False, "找不到 takeFluid / simulateFilledBucket"
    ok_identity = has(menu, r"FluidStack\.isSameFluidSameComponents\(stack, identity\)")
    ok_probe = has(probe.group(1),
                   r"FluidUtil\.tryFillContainer\(empty, source, \(int\) BUCKET_MB, null, false\)")
    ok_drain = has(take.group(1),
                   r"copyWithAmount\(\(int\) BUCKET_MB\), IFluidHandler\.FluidAction\.EXECUTE\)")
    ok_rollback = has(take.group(1), r"if \(!consumeOneEmptyBucket\(player\)\)")
    ok_source = has(menu, r"implements IFluidHandler") and has(menu, r"cache\.drain\(resource")
    ok_hint = has(menu, r"MSG_FLUID_NO_CONTAINER") and has(menu, r"MSG_FLUID_NO_SPACE")
    if not (ok_identity and ok_probe and ok_drain and ok_rollback and ok_source and ok_hint):
        return False, ("流体取出口不完整：身份核对=%s 模拟探针=%s 真扣减=%s 空桶回滚=%s 单罐源=%s 失败提示=%s"
                       % (ok_identity, ok_probe, ok_drain, ok_rollback, ok_source, ok_hint))
    return True, "身份核对 + SIMULATE 探针 + 精确 drain + 空桶回滚 + 失败提示"

# ---------------------------------------------------------------------------
# #2 请求器界面：目标 / 订单图标×数量 / 已投·剩余·缺口 / 节拍 / 状态行
# ---------------------------------------------------------------------------
@check("C1", "订单内容真的看得见：设置面板=摘要+大按钮，网格主界面=图标×数量+搜索+删除+改数量")
def check_c1(src, zh, en):
    scr = src["scr_q"]
    grid = src["scr_grid"]
    # 第 96 轮：请求器小面板的 4×2 = 8 格是人为上限（第 9 种资源看不见），已删除；
    # 订单内容整体搬到网格主界面。因此这里断言「内容在哪、是否齐全」，不再钉格子数。
    leftover = [name for name, pattern in {
        "4×2 订单格": r"renderOrderCells",
        "固定格子常量": r"ORDER_(COLS|ROWS|VISIBLE)",
    }.items() if has(scr, pattern)]
    if leftover:
        return False, "设置面板仍残留：" + "、".join(leftover)
    panel_need = {
        "摘要行": r"private Component summaryLine\(",
        "摘要文案": r"LANG \+ \"order\.summary\"",
        "大按钮": r"openGridButton = Button\.builder\(openGridLabel\(\)",
        "打开网格入口": r"new OpenRequesterPickerPacket\(menu\.containerId, 1\)",
    }
    panel_gone = [name for name, pattern in panel_need.items() if not has(scr, pattern)]
    if panel_gone:
        return False, "设置面板缺少：" + "、".join(panel_gone)
    grid_need = {
        "物品图标": r"guiGraphics\.renderItem\(row\.icon\(\)",
        "数量文本": r"row\.amountText\(\)",
        "搜索框": r"filterBox = new EditBox\(",
        "行尾删除": r"DELETE_GLYPH",
        "删除请求": r"RequesterOrderPacket\.ACTION_REMOVE",
        "改数量请求": r"RequesterOrderPacket\.ACTION_SET_AMOUNT",
    }
    grid_gone = [name for name, pattern in grid_need.items() if not has(grid, pattern)]
    if grid_gone:
        return False, "网格主界面缺少订单内容：" + "、".join(grid_gone)
    if has(grid, r"getReceiver\s*\(") or has(grid, r"getOrders\(\)"):
        return False, "客户端网格界面读了方块实体（违反「菜单重建不读 BE」）"
    return True, "摘要+大按钮在设置面板；图标×数量 / 搜索 / 删除 / 改数量在网格界面"


@check("C2", "请求器界面有「已投 / 剩余 / 缺口」进度行，数值全部来自 S2C 快照（不本地算）")
def check_c2(src, zh, en):
    scr = src["scr_q"]
    if not has(scr, r"private Component progressLine\(\)"):
        return False, "没有 progressLine"
    progress = re.search(r"private Component progressLine\(\)\s*\{(.*?)\n    \}", scr, re.S)
    if progress is None:
        return False, "找不到 progressLine 方法体"
    body = progress.group(1)
    # 三个数必须全部来自服务端字段，且确实被画进这一行（不能有占位常量）
    for field in ("roundDelivered", "roundRemaining", "roundShortfall"):
        if not has(body, r"compact\(" + field + r"\)"):
            return False, "progressLine 没有用服务端字段 %s" % field
    if not has(scr, r"roundPlanned = packet\.roundPlanned\(\);"):
        return False, "onServerSync 没有接收 roundPlanned（%s）" % at("scr_q", r"public void onServerSync")
    if has(scr, r"roundPlanned\s*-\s*roundDelivered"):
        return False, "客户端自己算了 remaining（应读服务端 roundRemaining）"
    return True, "三个数都来自 packet；客户端不做减法"


@check("C3", "请求器界面有节拍行（每 tick 批数 / 单批量），数值只读菜单同步值")
def check_c3(src, zh, en):
    scr = src["scr_q"]
    if not has(scr, r"private Component pacingLine\(\)"):
        return False, "没有 pacingLine"
    if not (has(scr, r"menu\.getThrottleBatchesPerTick\(\)") and has(scr, r"menu\.getThrottleBatch\(\)")):
        return False, "pacingLine 没有读菜单同步值"
    if has(scr, r"throttleBatchesPerTick\(\)\s*\*") or has(scr, r"getUpgradeContainer\(\)"):
        return False, "界面按升级容器自己重算节拍（必须读 ContainerData）"
    return True, "节拍行读 menu.getThrottle*"


@check("C4", "请求器界面有状态行 + 目标行（目标名字/坐标或明确原因）")
def check_c4(src, zh, en):
    scr = src["scr_q"]
    if not (has(scr, r"private Component statusLine\(\)") and has(scr, r"private Component targetHint\(\)")):
        return False, "缺少 statusLine / targetHint"
    if not has(scr, r"targetText\(\)"):
        return False, "目标行没有把名字 + 坐标拼出来"
    return True, "statusLine + targetHint + targetText"


@check("C5", "请求器界面打开期间进度会补发（跨 tick 投递时「已投」不会停在第一批）")
def check_c5(src, zh, en):
    menu = src["menu_q"]
    if not has(menu, r"LIVE_SYNC_INTERVAL\s*=\s*10\s*;"):
        return False, "menu_q 没有 LIVE_SYNC_INTERVAL（进度补发间隔）"
    body = re.search(r"public void broadcastChanges\(\)\s*\{(.*?)\n    \}", menu, re.S)
    if body is None:
        return False, "menu_q 没有 broadcastChanges"
    text = body.group(1)
    if not (has(text, r"roundSignature\(\)") and has(text, r"requester\.isRoundActive\(\)")):
        return False, "broadcastChanges 没有按「续投中 / 数字变化」补发进度（%s）" % at("menu_q", r"public void broadcastChanges")
    if not has(menu, r"private long roundSignature\(\)"):
        return False, "缺少 roundSignature()"
    return True, "10 tick 补发 + 只在续投 / 数字变化时发"


# ---------------------------------------------------------------------------
# 请求器「立即发送」「清空订单」真的可用：既有 C2S 包 + 服务端校验
# ---------------------------------------------------------------------------
@check("D1", "立即发送：界面发 TriggerRequesterPacket；服务端走 triggerRequest()（红石同一入口）")
def check_d1(src, zh, en):
    scr = src["scr_q"]
    if not has(scr, r"new TriggerRequesterPacket\(menu\.containerId\)"):
        return False, "请求器界面没有发 TriggerRequesterPacket"
    be = src["be_q"]
    if not has(be, r"public void triggerRequest\(\)"):
        return False, "方块实体没有 triggerRequest()"
    if not has(be, r"shortfallThisDelivery") or not has(be, r"if \(!throttleTick\(\)\)"):
        return False, "triggerRequest 缺前置闸门（节流 / 缺口判定）"
    return True, "按钮 → TriggerRequesterPacket → triggerRequest()"


@check("D2", "清空订单：复用 RequesterOrderPacket#ACTION_CLEAR，服务端 clearOrdersFor 校验归属后只清订单")
def check_d2(src, zh, en):
    scr = src["scr_q"]
    pkt = src["pkt_order"]
    menu = src["menu_q"]
    if not has(scr, r"RequesterOrderPacket\.ACTION_CLEAR"):
        return False, "界面没有发 ACTION_CLEAR"
    if not has(pkt, r"ACTION_CLEAR\s*=\s*3\s*;"):
        return False, "RequesterOrderPacket 没有 ACTION_CLEAR = 3"
    if not has(pkt, r"case ACTION_CLEAR\s*->\s*NetworkResourceRequesterMenu\.clearOrdersFor\(player, containerId\)"):
        return False, "包处理没有分发到 clearOrdersFor"
    match = re.search(r"public static OrderResult clearOrdersFor\((.*?)\n    \}", menu, re.S)
    if match is None:
        return False, "找不到 clearOrdersFor"
    body = match.group(1)
    need = {
        "解析玩家当前菜单": r"requesterOf\(player\.containerMenu\)",
        "服务端权威": r"isServerAuthoritative\(requester\)",
        "距离/存在性": r"stillValidFor\(player, requester\)",
        "只清订单": r"requester\.clearOrders\(\)",
        "回提示": r"MSG_ORDER_CLEARED",
    }
    gone = [name for name, pattern in need.items() if not has(body, pattern)]
    if gone:
        return False, "clearOrdersFor 缺少：" + "、".join(gone)
    if has(menu, r"clearOrdersFor[\s\S]{0,400}setOrders\("):
        return False, "清空订单顺手改了订单清单以外的状态（越权）"
    return True, "ACTION_CLEAR → clearOrdersFor（两道校验 + 只清清单）"


@check("D3", "清空订单不动目标 / 缓存：clearOrdersFor 内不出现 setTargetName / 缓存写入")
def check_d3(src, zh, en):
    menu = src["menu_q"]
    match = re.search(r"public static OrderResult clearOrdersFor\((.*?)\n    \}", menu, re.S)
    if match is None:
        return False, "找不到 clearOrdersFor"
    body = match.group(1)
    forbidden = [name for name, pattern in {
        "改目标": r"setTargetName\(",
        "写缓存": r"applyResource\(",
        "清缓冲": r"returnBuffer\.clear\(",
    }.items() if has(body, pattern)]
    if forbidden:
        return False, "清空订单越界动了：" + "、".join(forbidden)
    return True, "只 requester.clearOrders() + 一条提示"


# ---------------------------------------------------------------------------
# 服务端「最近一轮」账（已投 / 剩余 / 缺口）—— 有触发点就有记账点
# ---------------------------------------------------------------------------
@check("E1", "请求器 BE 有 round 账字段与 getter（planned/delivered/shortfall/remaining 夹 ≥0）")
def check_e1(src, zh, en):
    be = src["be_q"]
    need = {
        "字段": r"private long roundPlanned;",
        "已投字段": r"private long roundDelivered;",
        "缺口字段": r"private long roundShortfall;",
        "进行中": r"private boolean roundActive;",
        "getter remaining": r"public long getRoundRemaining\(\)",
        "remaining 夹 0": r"Math\.max\(0L, roundPlanned - roundDelivered - roundShortfall\)",
    }
    gone = [name for name, pattern in need.items() if not has(be, pattern)]
    if gone:
        return False, "请求器 BE 账目缺少：" + "、".join(gone)
    return True, "四个字段 + 四个 getter（remaining 由服务端算并夹 0）"


@check("E2", "触发 / 续投 / 作废三条路径都记账：beginRound→recordRoundDelivery→finishRound")
def check_e2(src, zh, en):
    be = src["be_q"]
    trigger = re.search(r"public void triggerRequest\(\)\s*\{(.*?)\n    \}", be, re.S)
    cont = re.search(r"private void continuePendingDelivery\(\)\s*\{(.*?)\n    \}", be, re.S)
    drop = re.search(r"private void dropPendingPlan\(final String reason\)\s*\{(.*?)\n    \}", be, re.S)
    if trigger is None or cont is None or drop is None:
        return False, "找不到 triggerRequest / continuePendingDelivery / dropPendingPlan"
    problems = []
    if not (has(trigger.group(1), r"beginRound\(\);") and has(trigger.group(1), r"recordRoundDelivery\(moved\);")
            and has(trigger.group(1), r"finishRound\(shortfallThisDelivery\);")):
        problems.append("triggerRequest 未完整记账")
    if not (has(cont.group(1), r"recordRoundDelivery\(moved\);")
            and has(cont.group(1), r"finishRound\(shortfallThisDelivery\);")):
        problems.append("continuePendingDelivery 未完整记账")
    if not has(drop.group(1), r"finishRound\(true\);"):
        problems.append("dropPendingPlan 未把剩余记成缺口")
    if problems:
        return False, "；".join(problems)
    return True, "触发 / 续投 / 作废三处都记账"


@check("E3", "round 账落盘并读回（重进存档界面不撒谎归零）")
def check_e3(src, zh, en):
    be = src["be_q"]
    need = {
        "落盘 planned": r"tag\.putLong\(TAG_ROUND_PLANNED, roundPlanned\)",
        "落盘 delivered": r"tag\.putLong\(TAG_ROUND_DELIVERED, roundDelivered\)",
        "读回 planned": r"roundPlanned = tag\.getLong\(TAG_ROUND_PLANNED\)",
        "读回 delivered": r"roundDelivered = tag\.getLong\(TAG_ROUND_DELIVERED\)",
        "读回 active": r"roundActive = tag\.getBoolean\(TAG_ROUND_ACTIVE\)",
    }
    gone = [name for name, pattern in need.items() if not has(be, pattern)]
    if gone:
        return False, "round 账持久化缺少：" + "、".join(gone)
    return True, "四个 tag 写入 + 读回"


@check("E4", "S2C 快照真的带账：record 组件 / encode / decode / of() 四处一致")
def check_e4(src, zh, en):
    pkt = src["pkt_sync"]
    need = {
        "record 组件": r"long roundPlanned, long roundDelivered",
        "编码 planned": r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.roundPlanned\(\)\)",
        "编码 shortfall": r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.roundShortfall\(\)\)",
        "解码": r"final long roundPlanned = ByteBufCodecs\.VAR_LONG\.decode\(buf\)",
        "构造带账": r"roundPlanned, roundDelivered, roundRemaining, roundShortfall, roundActive\)",
        "of() 取 BE 值": r"requester\.getRoundPlanned\(\), requester\.getRoundDelivered\(\)",
        "of() 取 remaining": r"requester\.getRoundRemaining\(\), requester\.getRoundShortfall\(\)",
    }
    gone = [name for name, pattern in need.items() if not has(pkt, pattern)]
    if gone:
        return False, "快照账目链路缺少：" + "、".join(gone)
    return True, "record/encode/decode/of 四处一致"


# ---------------------------------------------------------------------------
# #4 Jade：能力暴露 + 自定义 provider 只加文本、不判按键、不列容器内容
# ---------------------------------------------------------------------------
@check("F1", "两台 BE 都暴露 ItemHandler.BLOCK / FluidHandler.BLOCK（Jade Universal side=null 就能读到）")
def check_f1(src, zh, en):
    problems = []
    for key, cap in (("be_r", "接收器"), ("be_q", "请求器")):
        text = src[key]
        if not has(text, r"Capabilities\.ItemHandler\.BLOCK"):
            problems.append(cap + " 未暴露 ItemHandler.BLOCK")
        if not has(text, r"Capabilities\.FluidHandler\.BLOCK"):
            problems.append(cap + " 未暴露 FluidHandler.BLOCK")
    if problems:
        return False, "；".join(problems)
    return True, "两个 BE 的两类能力都注册了"


@check("F2", "Jade provider 不再自己判 Shift、不再自己列 items/fluids（内容交给 Jade Universal）")
def check_f2(src, zh, en):
    provider = src["jade_provider"]
    payload = src["jade_payload"]
    banned = []
    if has(provider, r"hasShiftDown"):
        banned.append("provider 里出现 hasShiftDown")
    if has(provider, r"RsccTooltipLayers"):
        banned.append("provider 里出现 RsccTooltipLayers（自己判按键）")
    if has(payload, r'ITEMS\s*=\s*"items"'):
        banned.append("payload 仍写 items 明细")
    if has(payload, r'FLUIDS\s*=\s*"fluids"'):
        banned.append("payload 仍写 fluids 明细")
    if has(payload, r"boolean expanded"):
        banned.append("payload lines() 还有 expanded 参数")
    if has(payload, r'LANG \+ "hold"'):
        banned.append("payload 仍拼「按住 Shift」提示行")
    if banned:
        return False, "；".join(banned)
    if not has(payload, r"lines\(@Nullable final CompoundTag serverData\)"):
        return False, "payload lines() 不是单参数（仍在按按键分叉）"
    return True, "provider / payload 都没有按键判定与容器明细"


@check("F3", "Jade 精简的理由写进注释且是字节码证据（Side=null 的 Level#getCapability）")
def check_f3(src, zh, en):
    payload = src["jade_payload"]
    evidence = {
        "能力查询": r"getCapability",
        "side=null": r"side\s*=\s*null|aconst_null",
        "Jade 类名": r"Jade",
        "「不再自己写按住 Shift」": r"按住 Shift",
    }
    gone = [name for name, pattern in evidence.items() if not has(payload, pattern)]
    if gone:
        return False, "Jade payload 注释缺少证据：" + "、".join(gone)
    if not has(payload, r"registerCapabilities"):
        return False, "注释没有指出能力注册点（registerCapabilities）"
    return True, "注释含 getCapability / side=null / 用户原话口径"


# ---------------------------------------------------------------------------
# #4b 接收器日志不再刷屏：状态变化才记 / 30 秒至多一条
# ---------------------------------------------------------------------------
@check("G1", "[rscc-receiver-output] 改成「指纹变化才打 + 状态不变最多 30s 一条」")
def check_g1(src, zh, en):
    be = src["be_r"]
    if not has(be, r"DEBUG_THROTTLE_TICKS\s*=\s*600L\s*;"):
        return False, "DEBUG_THROTTLE_TICKS 不是 600（30 秒）（%s）" % at("be_r", r"DEBUG_THROTTLE_TICKS")
    if not has(be, r"lastDebugHash"):
        return False, "没有内容指纹 lastDebugHash（无法做到「状态变化才记」）"
    match = re.search(r"private void debug\(final String format, final Object\.\.\. args\)\s*\{(.*?)\n    \}",
                      be, re.S)
    if match is None:
        return False, "找不到 debug()"
    body = match.group(1)
    if not (has(body, r"hash == lastDebugHash && now - lastDebugTick < DEBUG_THROTTLE_TICKS")
            and has(body, r"LOGGER\.info\(LOG_PREFIX")):
        return False, "debug() 没有同时做「指纹相同才节流」与「变化立刻打」"
    return True, "600 tick 上限 + 指纹变化立刻打"


# ---------------------------------------------------------------------------
# 语言键：新增 34 条中英齐全；界面引用的 LANG 键也都必须存在
# ---------------------------------------------------------------------------
def keys_referenced(source_text):
    prefix_match = re.search(r'String LANG = "([^"]*)";', source_text)
    prefix = prefix_match.group(1) if prefix_match else None
    found = set()
    if prefix:
        for match in re.finditer(r'LANG \+ "([A-Za-z0-9_.]+)"', source_text):
            found.add(prefix + match.group(1))
    for match in re.finditer(r'"((?:gui|config|block|item|advancements)\.[A-Za-z0-9_.]+)"', source_text):
        found.add(match.group(1))
    # 前缀常量本身（末尾带点）不是语言键，不能算「引用的键」
    if prefix:
        found.discard(prefix)
    return found


@check("H1", "语言键：新增 34 条中英齐全；界面里 LANG + \"...\" 引用的键也全都存在")
def check_h1(src, zh, en):
    problems = []
    absent = [key for key in NEW_LANG_KEYS if key not in zh or key not in en]
    if absent:
        problems.append("新增键缺 %d 条（Lead 待补）：%s" % (len(absent), ", ".join(absent[:6])))
    for key in ("scr_r", "scr_q", "menu_r", "menu_q", "jade_payload"):
        for ref in sorted(keys_referenced(src[key])):
            if ref not in zh or ref not in en:
                problems.append("%s 引用的键不存在：%s" % (key, ref))
    if problems:
        return False, "；".join(sorted(set(problems))[:8])
    return True, "34 条新键 + 界面引用键全部中英齐全"


# ---------------------------------------------------------------------------
# 反例自证：把每条修复回退，对应断言必须翻红
# ---------------------------------------------------------------------------
MUTATIONS = [
    ("A1", "menu_r", [(r"public static final int PAGE_ROWS = 6;",
                       "public static final int PAGE_ROWS = 5;")],
     "每页行数退回 5（每页只剩 10 条 < 12，P5 复核的容量口径不达标）"),
    ("A1", "menu_r", [(r"public static final int PAGE_COLS = 2;",
                       "public static final int CACHE_COLS = 2;\n    public static final int CACHE_VIEW_COUNT = 16;\n    public static final int PAGE_COLS = 2;")],
     "旧 16 格固定实现残留（CACHE_VIEW_COUNT / CACHE_COLS 又出现）"),
    ("A2", "scr_r", [(r"        renderEntries\(guiGraphics, mouseX, mouseY\);",
                      "        // mutated: 列表不再绘制")],
     "接收器界面不再画资源列表（缓存不可见）"),
    ("A2", "scr_r", [(r"        // 全部文字不带阴影（工程硬规则：全模组 drawString 一律传 false）",
                       "        menu.getReceiver(); // mutated: 客户端读方块实体")],
     "客户端界面开始读方块实体（客户端没有权威数据）"),
    ("B1", "pkt_rsync", [(r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.pageCount\(\)\);", "// mutated")],
     "快照不编码总页数（界面页码永远 1/1）"),
    ("B1", "menu_r", [(r"final int from = Math\.max\(0, page\) \* PAGE_SIZE;", "final int from = 0; // mutated")],
     "切片不再按 PAGE_SIZE 计算（每页行数不受控）"),
    ("B2", "menu_r", [(r"cache\.extractItem\(slot, Math\.min\(remaining, each\.getCount\(\)\), false\)",
                       "cache.extractItem(slot, Math.min(remaining, each.getCount()), true) // mutated")],
     "物品取出改用 SIMULATE（玩家白拿）"),
    ("B3", "menu_r", [(r"final int buckets = all \? maxBuckets : 1;",
                       "final int buckets = 1; // mutated")],
     "流体 Shift 只能换一桶（尽可能多整桶失效）"),
    ("B3", "menu_r", [(r"private void takeAllCacheItems\(final Player player\) \{",
                       "private void takeAllCacheItemsGone(final Player player) { // mutated")],
     "「取全部」入口消失（用户无法一键腾空接收器）"),
    ("B5", "menu_r", [(r"FluidUtil\.tryFillContainer\(empty, source, \(int\) BUCKET_MB, null, false\)",
                       "FluidUtil.tryFillContainer(empty, source, (int) BUCKET_MB, null, true) // mutated")],
     "桶形态探针改成真执行（会先把缓存里的流体扣掉）"),
    ("B5", "menu_r", [(r"if \(!consumeOneEmptyBucket\(player\)\) \{",
                       "if (false) { // mutated: 不等空桶消耗成功")],
     "消耗空桶失败时不回滚（扣了没给）"),
    ("C1", "scr_grid",
     [(r"guiGraphics\.renderItem\(row\.icon\(\)", "// mutated: 订单图标不再绘制")],
     "网格界面不再画订单图标（订单内容彻底看不见）"),
    ("C2", "scr_q", [(r"RsccNumberField\.compact\(roundDelivered\)", "RsccNumberField.compact(0L)")],
     "请求器进度不再用服务端已投数"),
    ("C3", "scr_q", [(r"menu\.getThrottleBatchesPerTick\(\)", "1")],
     "节拍行改成本地常量（与升级槽分叉）"),
    ("C5", "menu_q", [(r"            if \(requester\.isRoundActive\(\) \|\| signature != lastRoundSignature\) \{",
                       "            if (false) { // mutated: 不再补发进度")],
     "打开界面期间不再补发投递进度（数字停在第一批）"),
    ("D2", "pkt_order", [(r"case ACTION_CLEAR -> NetworkResourceRequesterMenu\.clearOrdersFor\(player, containerId\);",
                          "// mutated: 清空订单不处理")],
     "清空订单包不再分发（按钮点了没反应）"),
    ("D3", "menu_q", [(r"requester\.clearOrders\(\);",
                       "requester.clearOrders(); requester.setTargetName(\"\");")],
     "清空订单顺手清掉目标（越界）"),
    ("E1", "be_q", [(r"public long getRoundRemaining\(\)\s*\{\s*return Math\.max\(0L, roundPlanned - roundDelivered - roundShortfall\);",
                     "public long getRoundRemaining() { return roundPlanned;")],
     "remaining 不再夹 0（可能显示负数）"),
    ("E2", "be_q", [(r"        final long moved = deliverBatch\(network, receiver\);\s*\n        recordRoundDelivery\(moved\);",
                     "        final long moved = deliverBatch(network, receiver);")],
     "触发路径不再记「已投」"),
    ("E3", "be_q", [(r"tag\.putLong\(TAG_ROUND_PLANNED, roundPlanned\);", "// mutated")],
     "round 账不再落盘"),
    ("E4", "pkt_sync", [(r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.roundPlanned\(\)\);", "// mutated")],
     "快照不编码计划量（客户端显示 0）"),
    ("F2", "jade_provider", [(r"for \(final Component line : RsccReceiverJadePayload\.lines\(accessor\.getServerData\(\)\)\) \{",
                              "final boolean expanded = RsccTooltipLayers.held(RsccTooltipLayers.Layer.SHIFT);\n"
                              "        for (final Component line : RsccReceiverJadePayload.lines(accessor.getServerData(), expanded)) {")],
     "provider 又自己判 Shift（用户明确不要）"),
    ("F3", "jade_payload", [(r"getCapability", "getThing")],
     "Jade 注释丢掉能力查询证据"),
    ("G1", "be_r", [(r"DEBUG_THROTTLE_TICKS\s*=\s*600L", "DEBUG_THROTTLE_TICKS = 100L")],
     "日志节流退回 5 秒一条（刷屏）"),
    ("H1", "be_q", [], "新增语言键缺失（由运行时把 zh 里的键删掉模拟）"),
]


def apply_mutation(src, rel_key, replacements):
    text = src[rel_key]
    for pattern, replacement in replacements:
        new_text = re.sub(pattern, replacement, text, count=1, flags=re.S)
        if new_text == text:
            return None
        text = new_text
    return text


def run_all(src, zh, en):
    results = []
    for cid, desc, fn in CHECKS:
        try:
            ok, detail = fn(src, zh, en)
        except Exception as exc:  # 断言函数自身出错也算判负（不能静默跳过）
            ok, detail = False, "断言抛异常：%r" % (exc,)
        results.append((cid, desc, ok, detail))
    return results


def main():
    print("=" * 78)
    print("第 95 轮 WS-A 自检：接收器 / 请求器界面重做 + 缓存可取 + Jade 只暴露接口")
    print("=" * 78)

    src = {}
    broken_files = []
    for key, rel in FILES.items():
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            broken_files.append(rel)
            src[key] = ""
        else:
            src[key] = read_text(rel)
    langs = {}
    for key, rel in LANG_FILES.items():
        try:
            with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
                langs[key] = json.load(handle)
        except Exception as exc:
            broken_files.append("%s (%r)" % (rel, exc))
            langs[key] = {}

    if broken_files:
        print("[FAIL] 0 覆盖：以下文件读不到 ⇒ 判据本身失效")
        for rel in broken_files:
            print("       - %s" % rel)
        return 1

    results = run_all(src, langs["zh"], langs["en"])
    failures = [(cid, desc, detail) for cid, desc, ok, detail in results if not ok]
    for cid, desc, ok, detail in results:
        print("%s %s %s" % ("PASS" if ok else "FAIL", cid, desc))
        if not ok:
            print("       -> %s" % detail)
    if failures:
        print("-" * 78)
        print("[result] 自检失败：%d / %d 项断言未通过" % (len(failures), len(results)))
        return 1

    # ==================== 反例自证 ====================
    proof_failures = []
    checks_by_id = {cid for cid, _desc, _fn in CHECKS}
    for target, rel_key, replacements, label in MUTATIONS:
        if target not in checks_by_id:
            proof_failures.append("%s：断言 id 不存在" % target)
            continue
        clone = dict(src)
        clone_zh = dict(langs["zh"])
        if target == "H1":
            # 语言键反例：删掉第一条新增键 ⇒ H1 必须判负
            clone_zh.pop(NEW_LANG_KEYS[0], None)
            mutated = clone
        else:
            mutated_text = apply_mutation(src, rel_key, replacements)
            if mutated_text is None:
                proof_failures.append("%s：反例变异没生效（%s）" % (target, label))
                continue
            mutated = dict(src)
            mutated[rel_key] = mutated_text
        mutated_results = run_all(mutated, clone_zh, langs["en"])
        target_result = [ok for cid, _desc, ok, _detail in mutated_results if cid == target]
        if not target_result or target_result[0]:
            proof_failures.append("%s：回退「%s」后断言仍然通过（假绿）" % (target, label))

    for target, _rel_key, _replacements, label in MUTATIONS:
        if target not in [failure.split("：")[0] for failure in proof_failures]:
            print("PASS 反例 %s：回退「%s」⇒ 判负" % (target, label))
    if proof_failures:
        print("-" * 78)
        for failure in proof_failures:
            print("FAIL 反例 %s" % failure)
        print("[result] 自检失败：反例自证 %d 项未抓住" % len(proof_failures))
        return 1

    total = len(results) + len(MUTATIONS)
    print("=" * 78)
    print("[result] 自检通过：%d 项断言（%d 条正向 + %d 条反例自证）"
          % (total, len(results), len(MUTATIONS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
