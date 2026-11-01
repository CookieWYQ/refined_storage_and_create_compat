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

第 131 轮：接收器界面改成终端网格 ⇒ 判据反转
    接收器从「分页资源列表 + 摘要 + 页码箭头 + 取全部按钮」改成「9×7 = 63 格终端网格 + 按行滚动 +
    点格子取出」，用户明确说摘要 / 已命名 / 取全部这些是废话。因此本脚本的接收器判据
    （A1/A2/B1/B3/B4/B5）已按新语义改写为「旧元素不存在 + 新网格几何/语义存在」，
    强度不降：A1 网格常量 + 旧分页零残留；A2 逐格图标 + 数量角标 + 旧状态句零残留；
    B1 线格式不变但按「页首行」切一屏；B3 手势分档 + 「取全部」通道零残留；
    B4 客户端只报身份/全部 + 服务端四步守恒；B5 SIMULATE 探针 + 精确 drain + 双路径回滚。
    第 131 轮 C 组：请求器「设置界面」整页删除 / 去 6 升级槽 / 目标改子界面 ⇒ 判据反转
    用户第 131 轮拍板「发送器跟插件槽没关系，红石激活的速度决定发送速度」「设置界面多余」。
    因此 `client/screen/NetworkResourceRequesterScreen.java` 整个文件删除，本脚本：
      * **文件门禁反向**：那个文件必须**不存在**（以前是「读不到就判据失效」，现在读到了才判负）；
      * C1 摘要+大按钮+「打开网格」包 → 反转成「三件寿衣（summaryLine / OpenRequesterPickerPacket /
        升级容器）在网格界面零残留，而订单内容（图标×数量 / 搜索 / 删除 / 改数量 / 目标 / 发送 /
        清空）全部在网格主界面」；
      * C2 「界面有已投/剩余/缺口进度行」→ 反转成「设置界面删掉后，客户端**不许在别处本地算**
        remaining（服务端账仍在 BE / 协议里，但界面不显示它，`of()` 恒填 0）」；
      * C3 「节拍行读菜单 getter」→ 反转成「节拍文字在网格工具条、只读 S2C 的
        pacingBatchesPerTick/pacingBatch；菜单的节拍 getter 与升级容器通道删除」；
      * C4 「statusLine + targetHint」→ 反转成「目标 = 网格里的目标按钮 + 子界面
        SenderTargetSelectScreen（搜索 / 列表 / 确定），旧「左键切下一台」零残留」；
      * C5 「10 tick 轮询补发进度」→ 反转成「每次动作后服务端单次回发权威快照（syncTo），
        没有 LIVE_SYNC_INTERVAL / broadcastChanges / roundSignature」；
      * D1/D2/D3 把「界面 → 包 → 菜单静态方法」改指新的唯一入口
        （TriggerRequesterPacket → RequesterSenderBackend.triggerFor；ACTION_CLEAR →
        RequesterSenderBackend.clearOrders），校验纪律一条不降；
      * E1-E3（BE 的 round 账 / 落盘）与 F/G 段不受影响；E4 反转成「协议字段还在但 `of()` 恒填 0
        —— 界面不显示进度就不许假装有」。
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
    # 第 96 轮：订单内容（图标×数量 / 搜索 / 删除 / 改数量）搬到网格主界面，本自检也要盯它
    # 第 131 轮：请求器「设置界面」删除 ⇒ 网格主界面成为唯一发送界面，目标/发送/清空也都在这里
    "scr_grid": PKG + "/client/grid/ResourcePickerScreen.java",
    # 第 131 轮 C 组新增：目标选择子界面 / 缺口询问子界面 / 发送源三层抽象
    "scr_target": PKG + "/client/screen/SenderTargetSelectScreen.java",
    "scr_prompt": PKG + "/client/screen/SenderCraftPromptScreen.java",
    "sender_backend": PKG + "/support/SenderBackend.java",
    "sender_delivery": PKG + "/support/SenderDelivery.java",
    "req_backend": PKG + "/support/RequesterSenderBackend.java",
    "pkt_order": PKG + "/network/RequesterOrderPacket.java",
    "pkt_sync": PKG + "/network/SyncRequesterConfigPacket.java",
    # 第 96 轮：接收器自己那份分页快照（与请求器的 pkt_sync 是两份不同的包，不能混用）
    "pkt_rsync": PKG + "/network/SyncReceiverConfigPacket.java",
    "pkt_entry": PKG + "/network/ReceiverCacheEntry.java",
    "jade_payload": PKG + "/compat/jade/RsccReceiverJadePayload.java",
    "jade_provider": PKG + "/client/jade/RsccReceiverJadeProvider.java",
}

# 第 131 轮 C 组：这些文件**必须不存在**（整页删除，不是「先留着下一轮再删」）。
# 以前 `scr_q` 在 FILES 里，读不到就报「0 覆盖 ⇒ 判据失效」；现在改成反向门禁：
# 文件只要还在 ⇒ 判负（防止有人把旧界面捡回来或换个名字复活）。
GONE_FILES = {
    "请求器设置界面": PKG + "/client/screen/NetworkResourceRequesterScreen.java",
    "设置界面的「打开网格」死通道包": PKG + "/network/OpenRequesterPickerPacket.java",
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
@check("A1", "接收器菜单：终端网格 9 列 × 7 行 = 63 格（18px 格子）；旧分页 / 固定格实现整段移除")
def check_a1(src, zh, en):
    """第 131 轮：接收器列表 2×6 分页 ⇒ **终端网格 9×7 = 63 格**（旧分页零残留）。"""
    menu = src["menu_r"]
    need = {
        "GRID_COLS = 9": r"public static final int GRID_COLS = 9;",
        "GRID_ROWS = 7": r"public static final int GRID_ROWS = 7;",
        "CELL = 18（18px 格子 / 16px 图标）": r"public static final int CELL = 18;",
        "GRID_W 由列数推导": r"public static final int GRID_W = GRID_COLS \* CELL;",
        "WINDOW_SIZE 由行列推导": r"public static final int WINDOW_SIZE = GRID_COLS \* GRID_ROWS;",
        "SCROLLBAR_H = 网格高": r"public static final int SCROLLBAR_H = GRID_H;",
        "PLAYER_START 由升级槽推导": r"public static final int PLAYER_START = UPGRADE_START \+ UPGRADE_COUNT;",
    }
    gone = [name for name, pattern in need.items() if not has(menu, pattern)]
    if gone:
        return False, "菜单网格常量缺：" + "、".join(gone)
    for stale in ("PAGE_COLS", "PAGE_ROWS", "PAGE_SIZE", "LIST_X0", "LIST_Y0", "ROW_H",
                  "ICON_SIZE", "ICON_SCALE", "NAME_X", "COL_W", "COL_GAP",
                  "BUTTON_TAKE_ALL_ITEMS", "CACHE_VIEW_COUNT", "CACHE_COLS",
                  "CacheSlot", "SimpleMirrorContainer"):
        if has(menu, r"\b" + stale + r"\b"):
            return False, "仍残留旧列表 / 分页 / 固定格实现的 %s（第 131 轮要求整段替换）" % stale
    return True, "9×7 = 63 格/屏（18px 格子）；旧分页与固定格实现零残留"

@check("A2", "接收器界面：终端网格（图标 + 数量角标）+ 格子 tooltip；旧摘要 / 页码 / 状态句整段删除")
def check_a2(src, zh, en):
    """第 131 轮：资源行 + 摘要 + 页码 ⇒ **终端网格（图标 + 数量角标）+ 格子 tooltip**，
    状态句（摘要 / 节拍 / 已命名 / 页码）全部删除。"""
    scr = src["scr_r"]
    needed = {
        "逐格渲染": r"for \(int i = 0; i < entries\.size\(\)",
        "物品图标": r"guiGraphics\.renderItem\(entry\.item\(\), x \+ 1, y \+ 1\)",
        "流体图标": r"GhostMarkerRenderer\.renderFluid\(guiGraphics, x \+ 1, y \+ 1, entry\.fluid\(\)\)",
        "数量角标": r"amountText\(entry, false\)",
        "格子悬停高亮": r"if \(i == hovered\)",
        "格子 tooltip（完整名称 / 操作说明）": r"private void renderGridTooltip",
        "滚动条": r"new ScrollbarWidget\(",
        "滚轮按行滚动": r"requestScroll\(firstRow",
    }
    missing_items = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing_items:
        return False, "接收器界面缺少：" + "、".join(missing_items)
    labels = re.search(r"protected void renderLabels\(.*?\n    \}", scr, re.S)
    if labels is None or not has(labels.group(0), r"renderGrid\(guiGraphics, mouseX, mouseY\);") \
            or not has(scr, r"renderAmountBadge\("):
        return False, "renderLabels 没有真正调用 renderGrid / 也没有数量角标（网格画不出来）"
    for stale in ("cacheSummary(", "fluidSummary(", "renderPageHeader(", "renderEntries(",
                  "throttleLine(", "statusLine("):
        if has(scr, re.escape(stale)):
            return False, "界面仍残留旧状态句 / 页码机械：%s（第 131 轮已删）" % stale
    if has(scr, r"menu\.getReceiver\("):
        return False, "客户端界面读了方块实体（menu.getReceiver(...)），违反「菜单重建不读 BE」"
    return True, "网格逐格（图标 + 数量角标）+ 格子 tooltip + 滚轮/滚动条；旧状态句与页码机械零残留"

# ---------------------------------------------------------------------------
# #3 缓存真的能取出：左键一组 / Shift 全部 / 流体换桶；真实扣减、不复制
# ---------------------------------------------------------------------------
@check("B1", "S2C 快照：线格式不变（page/pageCount/totalTypes/条目），服务端按「页首行」切一屏")
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
    if missing(menu, r"window\(all, firstRow\)"):
        return False, "菜单没有把全量快照切成「当前一屏」"
    if missing(menu, r"final int from = Math\.max\(0, firstRow\) \* GRID_COLS;"):
        return False, "窗口起点不是「页首行 × 列数」（一屏格数不受控）"
    if missing(menu, r"from \+ WINDOW_SIZE"):
        return False, "窗口长度不是 WINDOW_SIZE"
    return True, "快照线格式不变 + 服务端按 firstRow × GRID_COLS 切一屏（WINDOW_SIZE）"

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

@check("B3", "手势分档：物品左键=一组 / Shift=全部；流体左键=1 桶 / Shift=尽可能多；「取全部」按钮已删")
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
    # 第 131 轮：流体分档改成 min(可用空桶数, 存量/1000)，且容器来源按 RS 顺序。
    # 两条容器路径（背包 / 网络）各要一处 ⇒ 必须出现 2 次（只写一处 = 另一条路径取不干净）。
    split_expr = re.findall(r"all\s*\?\s*\(int\) Math\.min\(availableBuckets,\s*"
                            r"currentFluidAmount\(identity\) / BUCKET_MB\)\s*:\s*1;", menu)
    if len(split_expr) != 2:
        return False, "流体没有「1 桶 vs 尽可能多整桶」的分档（两条容器路径应各 1 处，实际 %d 处）" % len(
            split_expr)
    # 第 131 轮：用户原话「『取全部』这个按钮也是废话」⇒ 入口必须**不存在**（腾空改逐格 Shift 取）。
    if has(menu, r"BUTTON_TAKE_ALL_ITEMS") or has(menu, r"takeAllCacheItems") \
            or has(menu, r"clickMenuButton"):
        return False, "「取全部」按钮通道仍在（第 131 轮已删：用户明确说它是废话）"
    if has(menu, r"takeWholeCacheSlot"):
        return False, "仍残留 takeWholeCacheSlot（旧「只取一组」实现）"
    scr = src["scr_r"]
    if has(scr, r"takeAllButton") or has(scr, r"cache\.take_all"):
        return False, "界面仍残留「取全部」按钮"
    return True, "物品 / 流体都分档；「取全部」按钮与菜单按钮协议零残留"

@check("B4", "取不走不扣（网格版）：点击只报身份 + 要不要全部，数量/能否收下由服务端算；放不下回填")
def check_b4(src, zh, en):
    menu = src["menu_r"]
    scr = src["scr_r"]
    # 客户端只报「取谁 + 要不要全部」——数量与背包空间全在服务端算（第 131 轮点格子取出同一口径）
    request = re.search(r"private void requestTake\(final ReceiverCacheEntry entry,\s*final boolean all\)\s*\{(.*?)\n    \}",
                        scr, re.S)
    if request is None:
        return False, "界面没有 requestTake(...)（点击格子的唯一出口）"
    body = request.group(1)
    if "ReceiverCacheActionPacket.takeItem(" not in body or "takeFluid(" not in body:
        return False, "requestTake 没有发取物包"
    if "amount" in body:
        return False, "客户端把数量也报给服务端（数量必须由服务端算）"
    # 服务端：物品「真扣 → 塞背包 → 放不下回填 → 兜底掉落」四步顺序仍在
    take = re.search(r"private void takeItem\(final Player player, final ItemStack identity, final boolean all\)\s*\{(.*?)\n    \}",
                     menu, re.S)
    if take is None:
        return False, "找不到 takeItem"
    order = [take.group(1).find(token) for token in
             ("cache.extractItem(", "player.getInventory().add(", "cache.insertItem(", "player.drop(")]
    if any(index < 0 for index in order) or order != sorted(order):
        return False, "takeItem 的四步顺序不对：%s" % order
    if "MSG_CACHE_BAG_FULL" not in menu:
        return False, "放不下时没有提示（BAG_FULL）"
    return True, "客户端只报身份/全部；服务端四步守恒 + 放不下提示都在"


@check("B5", "流体取出口：先模拟桶形态 → 真 drain 恰好 1 桶 → 消耗容器 → 发放满桶（失败互相回滚）")
def check_b5(src, zh, en):
    menu = src["menu_r"]
    probe = re.search(r"private static ItemStack simulateFilledBucket\([^)]*\)\s*\{(.*?)\n    \}",
                      menu, re.S)
    drain = re.search(r"private FluidStack drainOneBucket\([^)]*\)\s*\{(.*?)\n    \}", menu, re.S)
    if probe is None or drain is None:
        return False, "找不到 simulateFilledBucket / drainOneBucket"
    # 第 131 轮：探针不再需要外部 source（自己按身份报告「一桶」），但仍必须 doFill=false
    ok_probe = has(probe.group(1),
                   r"FluidUtil\.tryFillContainer\(empty, source, \(int\) BUCKET_MB, null, false\)")
    ok_drain = has(drain.group(1), r"copyWithAmount\(\(int\) BUCKET_MB\)") \
        and has(drain.group(1), r"IFluidHandler\.FluidAction\.EXECUTE")
    # 两条容器路径各自的回滚分支（扣到的不足一桶 / 拿不到容器）
    helper_ok = True
    for header in (r"private void takeFluidWithNetworkBucket\(", r"private void takeFluidWithInventoryBucket\("):
        helper = re.search(header + r"[^)]*\)\s*\{(.*?)\n    \}", menu, re.S)
        if helper is None or helper.group(1).count("refillFluidCache(") < 2:
            helper_ok = False
    ok_identity = has(menu, r"FluidStack\.isSameFluidSameComponents\(stack, identity\)")
    ok_hint = has(menu, r"MSG_FLUID_NO_CONTAINER") and has(menu, r"MSG_FLUID_NO_SPACE")
    if not (ok_identity and ok_probe and ok_drain and helper_ok and ok_hint):
        return False, ("流体取出口不完整：身份核对=%s 模拟探针=%s 真扣减=%s 回滚分支=%s 失败提示=%s"
                       % (ok_identity, ok_probe, ok_drain, helper_ok, ok_hint))
    return True, "身份核对 + SIMULATE 探针 + 精确 drain（EXECUTE）+ 双路径回滚 + 失败提示"

# ---------------------------------------------------------------------------
# #2 请求器界面（第 131 轮反转：设置界面删了，内容全在网格主界面 / 子界面）
# ---------------------------------------------------------------------------
@check("C1", "订单内容看得见 + 设置界面零残留（网格主界面 = 图标×数量 / 搜索 / 删除 / 改数量 / 目标 / 发送 / 清空）")
def check_c1(src, zh, en):
    grid = src["scr_grid"]
    # 第 96 轮：请求器小面板的 4×2 = 8 格是人为上限（第 9 种资源看不见），已删除；
    # 第 131 轮：整个设置界面（摘要 + 大按钮 + 「打开网格」包）也删除。
    # 所以这里不再断言「面板有摘要」，而是断言「三件寿衣一个都不许回来」+「内容确实在网格里」。
    leftover = [name for name, pattern in {
        "摘要行": r"private Component summaryLine\(",
        "设置界面打开网格包": r"OpenRequesterPickerPacket",
        "升级容器入口": r"getUpgradeContainer\(",
        "4×2 订单格": r"renderOrderCells",
        "固定格子常量": r"ORDER_(COLS|ROWS|VISIBLE)",
    }.items() if has(grid, pattern)]
    if leftover:
        return False, "设置界面已删，网格里却还有它的残留：" + "、".join(leftover)
    grid_need = {
        "物品图标": r"guiGraphics\.renderItem\(row\.icon\(\)",
        "数量文本": r"row\.amountText\(\)",
        "搜索框": r"filterBox = new EditBox\(",
        "行尾删除": r"DELETE_GLYPH",
        "删除请求": r"RequesterOrderPacket\.ACTION_REMOVE",
        "改数量请求": r"RequesterOrderPacket\.ACTION_SET_AMOUNT",
        "目标按钮": r"targetButton = Button\.builder\(targetLabel\(\)",
        "发送按钮": r"LANG \+ \"panel\.send\"",
        "清空按钮": r"RequesterOrderPacket\.ACTION_CLEAR",
    }
    grid_gone = [name for name, pattern in grid_need.items() if not has(grid, pattern)]
    if grid_gone:
        return False, "网格主界面缺少订单内容：" + "、".join(grid_gone)
    if has(grid, r"getReceiver\s*\(") or has(grid, r"getOrders\(\)"):
        return False, "客户端网格界面读了方块实体（违反「菜单重建不读 BE」）"
    return True, "设置界面三件寿衣零残留；图标×数量 / 搜索 / 删除 / 改数量 / 目标 / 发送 / 清空都在网格里"


@check("C2", "「已投 / 剩余 / 缺口」界面不本地算：网格客户端零减法，服务端账仍在 BE（协议 of() 恒填 0）")
def check_c2(src, zh, en):
    grid = src["scr_grid"]
    be = src["be_q"]
    # 第 131 轮：用户把设置界面删了 ⇒ 界面不再显示进度行。但「客户端不许自己算 remaining」
    # 这条纪律必须留着（那是「界面撒谎」的根源），只是从「界面里有没有这一行」改成「别处也不许偷偷算」。
    if has(grid, r"roundPlanned") or has(grid, r"roundDelivered") or has(grid, r"roundRemaining"):
        return False, "网格界面出现 round 账字段（第 131 轮起界面不显示进度，不许在网格里复活）"
    if has(grid, r"roundShortfall"):
        return False, "网格界面出现 roundShortfall（同上）"
    # 服务端账仍在：BE 有字段 + remaining 由服务端算并夹 0（触发/续投/作废三条路径记账，见 E1/E2）
    need = {
        "BE 账字段": r"private long roundPlanned;",
        "BE remaining 夹 0": r"Math\.max\(0L, roundPlanned - roundDelivered - roundShortfall\)",
    }
    gone = [name for name, pattern in need.items() if not has(be, pattern)]
    if gone:
        return False, "服务端账目被顺手删了（不该动）：" + "、".join(gone)
    # 协议字段还在，但构造时恒填 0 —— 界面不显示就不许假装有进度
    if not has(src["pkt_sync"], r"0L, 0L, 0L, 0L, false\)"):
        return False, "pkt_sync.of() 没有把 round 账恒填 0（界面不显示却发真值 ⇒ 下一个人会以为有进度行）"
    return True, "客户端零减法；服务端账保留；协议恒填 0"


@check("C3", "节拍：网格工具条只读 S2C 的 pacingBatchesPerTick/pacingBatch；菜单节拍 getter 与升级容器通道已删")
def check_c3(src, zh, en):
    grid = src["scr_grid"]
    be = src["be_q"]
    menu = src["menu_q"]
    need = {
        "工具条速率文字": r'LANG \+ "toolbar\.rate"',
        "读每 tick 批数": r"pacingBatchesPerTick = Math\.max\(1, packet\.pacingBatchesPerTick\(\)\)",
        "读单批量": r"pacingBatch = Math\.max\(0L, packet\.pacingBatch\(\)\)",
    }
    gone = [name for name, pattern in need.items() if not has(grid, pattern)]
    if gone:
        return False, "网格工具条缺少节拍读数：" + "、".join(gone)
    # 第 131 轮反转：界面不再读菜单的节拍 getter（那是设置界面的写法），菜单也不再暴露它
    banned = [name for name, pattern in {
        "界面读菜单 getter": r"menu\.getThrottleBatchesPerTick\(\)|getMenu\(\)\.getThrottleBatch\(\)",
        "菜单节拍 getter 残留": r"public int getThrottleBatchesPerTick\(|public long getThrottleBatch\(",
        "界面按升级容器重算": r"getUpgradeContainer\(",
    }.items()
        if has(grid, pattern) or ((name == "菜单节拍 getter 残留") and has(menu, pattern))]
    if banned:
        return False, "节拍仍走老通道：" + "、".join(banned)
    # 节拍固定：BE 的两个 getter 必须回 SenderDelivery 的常量（红石多快就发多快，不吃升级）
    if not (has(be, r"SenderDelivery\.FIXED_BATCHES_PER_TICK") and has(be, r"SenderDelivery\.FIXED_BATCH_SIZE")):
        return False, "BE 的节拍 getter 没有回 SenderDelivery 固定常量（又变回升级旋钮了）"
    return True, "网格读快照；菜单不再有节拍 getter；BE 回固定常量"


@check("C4", "目标：网格目标按钮 + 子界面 SenderTargetSelectScreen（搜索/列表/确定），旧「左键切下一台」零残留")
def check_c4(src, zh, en):
    grid = src["scr_grid"]
    target = src["scr_target"]
    need_grid = {
        "目标按钮": r"targetButton = Button\.builder\(targetLabel\(\)",
        "打开子界面": r"new SenderTargetSelectScreen\(this, getMenu\(\)\.containerId",
    }
    gone = [name for name, pattern in need_grid.items() if not has(grid, pattern)]
    if gone:
        return False, "网格目标入口缺少：" + "、".join(gone)
    need_target = {
        "搜索框": r"searchBox = new EditBox\(",
        "滚轮列表": r"new ScrollSelectWidget<",
        "提交只带名字": r"new SetRequesterTargetPacket\(containerId, selected\.name\(\)\)",
    }
    gone = [name for name, pattern in need_target.items() if not has(target, pattern)]
    if gone:
        return False, "目标选择子界面缺少：" + "、".join(gone)
    # 反转：旧界面的「左键切下一台 / Shift 上一台 / 序号轮换」整条路径零残留
    rotate = [name for name, pattern in {
        "目标轮换": r"rotateTarget|cycleTarget|nextTarget|prevTarget",
        "候选序号": r"targetIndex\s*=|targetRotation",
    }.items() if has(grid, pattern) or has(target, pattern)]
    if rotate:
        return False, "旧目标轮换路径回来了：" + "、".join(rotate)
    if has(grid, r"targetHint\(\)"):
        return False, "网格里还有旧 targetHint（目标行已换成按钮 + 子界面）"
    return True, "目标 = 网格按钮 → 子界面（搜索 + 列表 + 只提交名字）；轮换零残留"


@check("C5", "进度同步：每次动作后服务端单次回发权威快照（syncTo），没有 10 tick 轮询 / broadcastChanges")
def check_c5(src, zh, en):
    menu = src["menu_q"]
    backend = src["req_backend"]
    if not has(backend, r"public static void syncTo\("):
        return False, "缺少 RequesterSenderBackend.syncTo（唯一的权威快照回发通道）"
    # 反转：设置界面时代的「打开期间每 10 tick 补发进度」整套删除
    banned = [name for name, pattern in {
        "旧补发常量": r"LIVE_SYNC_INTERVAL",
        "旧补发方法": r"public void broadcastChanges\(\)",
        "旧签名方法": r"private long roundSignature\(\)",
    }.items() if has(menu, pattern)]
    if banned:
        return False, "菜单里还留着旧进度补发通道：" + "、".join(banned)
    # 快照是完整覆盖：syncTo 必须真的把 of(...) 发出去
    if not has(backend, r"SyncRequesterConfigPacket\.of\("):
        return False, "syncTo 没有用 SyncRequesterConfigPacket.of(...)（快照来源可疑）"
    return True, "动作 → syncTo 单次回发完整快照；轮询补发已删除"


# ---------------------------------------------------------------------------
# 请求器「立即发送」「清空订单」真的可用：既有 C2S 包 + 服务端校验
# ---------------------------------------------------------------------------
@check("D1", "立即发送：网格发 TriggerRequesterPacket → RequesterSenderBackend.triggerFor 校验目标 → BE.triggerRequest()")
def check_d1(src, zh, en):
    grid = src["scr_grid"]
    pkt = read_text(PKG + "/network/TriggerRequesterPacket.java")
    backend = src["req_backend"]
    be = src["be_q"]
    # 第 131 轮：设置界面删除，发送按钮搬进网格主界面；包本身与「只认发送源」的纪律不变
    if not has(grid, r"new TriggerRequesterPacket\(getMenu\(\)\.containerId\)"):
        return False, "网格界面没有发 TriggerRequesterPacket"
    if not has(pkt, r"instanceof SenderBackend\.Holder"):
        return False, "包处理没有用 SenderBackend.Holder 解析「这张菜单背后的发送源」（两套界面共用一条路径）"
    if not has(backend, r"public static void triggerFor\("):
        return False, "缺少 RequesterSenderBackend.triggerFor（唯一的手动触发入口）"
    # 先校验目标（未配置 / 找不到 / 重名都不触发），再调 backend.trigger
    trigger_for = re.search(r"public static void triggerFor\((.*?)\n    \}", backend, re.S)
    if trigger_for is None:
        return False, "找不到 triggerFor 方法体"
    body = trigger_for.group(1)
    need = {
        "未联网不触发": r"!backend\.isConnected\(\)",
        "未配置不触发": r"targetName\(\)\.isEmpty\(\)",
        "重名不触发": r"hits\.size\(\) > 1",
        "唯一才触发": r"backend\.trigger\(player\)",
    }
    gone = [name for name, pattern in need.items() if not has(body, pattern)]
    if gone:
        return False, "triggerFor 缺少校验：" + "、".join(gone)
    if not has(be, r"public void triggerRequest\(\)"):
        return False, "方块实体没有 triggerRequest()"
    if not has(be, r"shortfallThisDelivery") or not has(be, r"if \(!throttleTick\(\)\)"):
        return False, "triggerRequest 缺前置闸门（节流 / 缺口判定）"
    return True, "网格按钮 → 包 → triggerFor（先校验）→ BE.triggerRequest()"


@check("D2", "清空订单：RequesterOrderPacket#ACTION_CLEAR → RequesterSenderBackend.clearOrders（校验归属后只清订单）")
def check_d2(src, zh, en):
    grid = src["scr_grid"]
    pkt = src["pkt_order"]
    backend = src["req_backend"]
    if not has(grid, r"RequesterOrderPacket\.ACTION_CLEAR"):
        return False, "网格界面没有发 ACTION_CLEAR"
    if not has(pkt, r"ACTION_CLEAR\s*=\s*3\s*;"):
        return False, "RequesterOrderPacket 没有 ACTION_CLEAR = 3"
    if not has(pkt, r"case ACTION_CLEAR\s*->\s*\{[\s\S]{0,200}RequesterSenderBackend\.clearOrders\(backend\)"):
        return False, "包处理没有分发到 RequesterSenderBackend.clearOrders（第 131 轮起菜单静态方法已删）"
    # 包处理的归属 / 存在性校验在 action 分发之前（与三个订单动作同一道闸门）
    switch_at = pkt.find("switch (packet.action())")
    prefix = pkt[:switch_at] if switch_at > 0 else ""
    need_pkt = {
        "containerId 比对": r"containerMenu\.containerId != packet\.containerId\(\)",
        "发送源解析": r"instanceof SenderBackend\.Holder",
        "距离 / 存在性": r"backend\.stillValid\(player\)",
    }
    gone = [name for name, pattern in need_pkt.items() if not has(prefix, pattern)]
    if gone:
        return False, "包处理在分发前缺少校验：" + "、".join(gone)
    match = re.search(r"public static OrderResult clearOrders\(@Nullable final SenderBackend backend\)(.*?)\n    \}",
                      backend, re.S)
    if match is None:
        return False, "找不到 clearOrders"
    body = match.group(1)
    need = {
        "空后端不动作": r"backend == null",
        "只清订单": r"backend\.clearOrders\(\)",
        "写回持久介质": r"backend\.save\(\)",
    }
    gone = [name for name, pattern in need.items() if not has(body, pattern)]
    if gone:
        return False, "clearOrders 缺少：" + "、".join(gone)
    return True, "ACTION_CLEAR → 包内先验归属 → clearOrders（只清清单 + save）"


@check("D3", "清空订单不动目标 / 缓存：clearOrders 内不出现 setTargetName / 缓存写入 / 清缓冲")
def check_d3(src, zh, en):
    backend = src["req_backend"]
    match = re.search(r"public static OrderResult clearOrders\(@Nullable final SenderBackend backend\)(.*?)\n    \}",
                      backend, re.S)
    if match is None:
        return False, "找不到 clearOrders"
    body = match.group(1)
    forbidden = [name for name, pattern in {
        "改目标": r"setTargetName\(",
        "写缓存": r"applyResource\(",
        "清缓冲": r"returnBuffer\.clear\(",
        "改订单清单以外": r"setOrders\(",
    }.items() if has(body, pattern)]
    if forbidden:
        return False, "清空订单越界动了：" + "、".join(forbidden)
    if has(backend, r"clearOrders\(final SenderBackend backend\)[\s\S]{0,300}syncTo\("):
        return False, "clearOrders 自己回快照（回快照只允许在包处理那一处，否则同一动作发两份）"
    return True, "只 backend.clearOrders() + save()"


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
    # 第 131 轮：triggerRequest() 只剩一个转发重载，真正的实现在 3 参重载里（F 组「按计划投」也走它），
    # 所以判据必须钉 3 参重载的方法体 —— 钉无参重载会变成「找到空壳就算过」。
    trigger = re.search(
        r"public void triggerRequest\(@Nullable final ServerPlayer asker,\s*\n\s*"
        r"@Nullable final List<ResourceAmount> plan\) \{(.*?)\n    \}", be, re.S)
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
    # 第 131 轮反转：设置界面删了、网格不显示进度 ⇒ 协议字段保留（不破坏线格式），
    # 但 of() 必须恒填 0 且写明理由 —— 以前是「of() 取 BE 真值」，现在取了反而是在撒谎。
    need = {
        "record 组件": r"long roundPlanned, long roundDelivered",
        "编码 planned": r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.roundPlanned\(\)\)",
        "编码 shortfall": r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.roundShortfall\(\)\)",
        "解码": r"final long roundPlanned = ByteBufCodecs\.VAR_LONG\.decode\(buf\)",
        "构造带账": r"roundPlanned, roundDelivered, roundRemaining, roundShortfall, roundActive\)",
        "of() 恒填 0": r"0L, 0L, 0L, 0L, false\)",
    }
    gone = [name for name, pattern in need.items() if not has(pkt, pattern)]
    if gone:
        return False, "快照账目链路缺少：" + "、".join(gone)
    if has(pkt, r"requester\.getRoundPlanned\(\)|backend\.getRoundPlanned\(\)"):
        return False, "of() 仍把 BE 的 round 账塞进快照（界面不显示它 ⇒ 客户端会收到无人使用的数字）"
    return True, "record/encode/decode 一致；of() 恒填 0（界面不显示进度）"


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
    # 第 131 轮：网格主界面同时用两个前缀（LANG = resource_picker. / QLANG = network_resource_requester.），
    # 只认第一个 `String LANG =` 会把 QLANG 的键算到错误前缀上 ⇒ 按「变量名 → 前缀」逐个映射。
    prefixes = {}
    for match in re.finditer(r'String\s+([A-Za-z_$][\w$]*)\s*=\s*"([^"]*)";', source_text):
        prefixes[match.group(1)] = match.group(2)
    found = set()
    for var, prefix in prefixes.items():
        # (?<![\w$]) 必须有：`LANG` 是 `QLANG` 的后缀，不加边界会把 QLANG 的键算到 LANG 前缀上
        for match in re.finditer(r'(?<![A-Za-z0-9_$])' + re.escape(var)
                                 + r'\s*\+\s*"([A-Za-z0-9_.]*)"', source_text):
            if match.group(1):
                found.add(prefix + match.group(1))
        # 前缀常量本身（末尾带点）不是语言键，不能算「引用的键」
        found.discard(prefix)
    for match in re.finditer(r'"((?:gui|config|block|item|advancements)\.[A-Za-z0-9_.]+)"', source_text):
        found.add(match.group(1))
    # 前缀常量自己的字符串值（`"...resource_picker."`）不是被引用的键
    found -= set(prefixes.values())
    return found


@check("H1", "语言键：新增 34 条中英齐全；界面里 LANG + \"...\" 引用的键也全都存在")
def check_h1(src, zh, en):
    problems = []
    absent = [key for key in NEW_LANG_KEYS if key not in zh or key not in en]
    if absent:
        problems.append("新增键缺 %d 条（Lead 待补）：%s" % (len(absent), ", ".join(absent[:6])))
    for key in ("scr_r", "scr_grid", "scr_target", "scr_prompt",
                "menu_r", "menu_q", "jade_payload"):
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
    ("A1", "menu_r", [(r"public static final int GRID_ROWS = 7;",
                       "public static final int GRID_ROWS = 4;")],
     "网格行数退回 4（一屏只剩 36 格，终端网格容量口径不达标）"),
    ("A1", "menu_r", [(r"public static final int GRID_COLS = 9;",
                       "public static final int PAGE_COLS = 9;\n"
                       "    public static final int CACHE_VIEW_COUNT = 16;\n"
                       "    public static final int GRID_COLS = 9;")],
     "旧分页 / 固定格实现残留（PAGE_COLS / CACHE_VIEW_COUNT 又出现）"),
    ("A2", "scr_r", [(r"        renderGrid\(guiGraphics, mouseX, mouseY\);",
                      "        // mutated: 网格不再绘制")],
     "接收器界面不再画资源网格（缓存不可见）"),
    ("A2", "scr_r", [(r"        // 全部文字不带阴影（工程硬规则：全模组 drawString 一律传 false）",
                       "        menu.getReceiver(); // mutated: 客户端读方块实体")],
     "客户端界面开始读方块实体（客户端没有权威数据）"),
    ("B1", "pkt_rsync", [(r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.pageCount\(\)\);", "// mutated")],
     "快照不编码总行数（界面滚动条永远拖不动）"),
    ("B1", "menu_r", [(r"final int from = Math\.max\(0, firstRow\) \* GRID_COLS;", "final int from = 0; // mutated")],
     "窗口起点不再按页首行计算（一屏格数不受控）"),
    ("B2", "menu_r", [(r"cache\.extractItem\(slot, Math\.min\(remaining, each\.getCount\(\)\), false\)",
                       "cache.extractItem(slot, Math.min(remaining, each.getCount()), true) // mutated")],
     "物品取出改用 SIMULATE（玩家白拿）"),
    ("B3", "menu_r", [(r"all\s*\?\s*\(int\) Math\.min\(availableBuckets,\s*"
                       r"currentFluidAmount\(identity\) / BUCKET_MB\)\s*:\s*1;", "1;")],
     "流体 Shift 只能换一桶（尽可能多整桶失效）"),
    ("B3", "menu_r", [(r"public static final int MAX_NAME_LENGTH = 32;",
                       "public static final int BUTTON_TAKE_ALL_ITEMS = 0;\n"
                       "    public static final int MAX_NAME_LENGTH = 32;")],
     "「取全部」按钮通道复活（用户说它是废话）"),
    ("B4", "scr_r", [(r"        if \(entry == null \|\| entry\.isEmpty\(\)\) \{\s*\n\s*return;",
                       "        if (entry == null || entry.isEmpty()) {\n            return;\n"
                       "        }\n        final long amount = entry.amount(); // mutated")],
     "客户端把数量也报给服务端（数量必须由服务端算）"),
    ("B5", "menu_r", [(r"FluidUtil\.tryFillContainer\(empty, source, \(int\) BUCKET_MB, null, false\)",
                       "FluidUtil.tryFillContainer(empty, source, (int) BUCKET_MB, null, true) // mutated")],
     "桶形态探针改成真执行（会先把缓存里的流体扣掉）"),
    ("B5", "menu_r", [(r"refillFluidCache\(drained\);", ";")],
     "扣到的流体不足一桶 / 拿不到容器时不回滚（扣了没给）"),
    ("C1", "scr_grid",
     [(r"guiGraphics\.renderItem\(row\.icon\(\)", "// mutated: 订单图标不再绘制")],
     "网格界面不再画订单图标（订单内容彻底看不见）"),
    # 第 131 轮：C2/C3/C5/D2/D3 的「修前写法」跟着新实现换锚点（反例必须仍然判负）
    ("C2", "pkt_sync", [(r"0L, 0L, 0L, 0L, false\)",
                         "backend.batchesPerTick(), 0L, 0L, 0L, false)")],
     "of() 又把真账塞进快照（界面不显示却发真值）"),
    ("C3", "scr_grid", [(r"pacingBatchesPerTick = Math\.max\(1, packet\.pacingBatchesPerTick\(\)\)",
                         "pacingBatchesPerTick = 1")],
     "节拍改成本地常量（与 S2C 快照分叉）"),
    ("C4", "scr_target", [(r"new SetRequesterTargetPacket\(containerId, selected\.name\(\)\)",
                           "// mutated: 不再提交目标")],
     "目标子界面不再提交名字（选了等于没选）"),
    ("C5", "menu_q", [(r"public ItemStack quickMoveStack\(",
                       "public static final int LIVE_SYNC_INTERVAL = 10;\n\n    public ItemStack quickMoveStack(")],
     "轮询补发常量又回来了（进度靠轮询而不是单次快照）"),
    ("D2", "pkt_order", [(r"RequesterSenderBackend\.clearOrders\(backend\)",
                          "RequesterSenderBackend.removeEntry(backend, -1, null)")],
     "清空订单包不再分发到 clearOrders（按钮点了没反应）"),
    ("D3", "req_backend", [(r"        backend\.clearOrders\(\);\n        backend\.save\(\);",
                            "        backend.clearOrders();\n        backend.setTargetName(null, \"\");\n"
                            "        backend.save();")],
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

    # 第 131 轮 C 组：反向门禁 —— 已删除的界面/包必须真的不在（这条以前不存在，
    # 是本轮**新增的收紧**：旧脚本只会因为「读不到」失败，永远抓不到「删了又捡回来」）。
    revived = [rel for rel in GONE_FILES.values() if os.path.isfile(os.path.join(ROOT, rel))]
    if revived:
        print("[FAIL] 第 131 轮：这些文件已按用户要求整页删除，现在又被加回来了"
              "（设置界面 / 打开网格包都是死通道）")
        for rel in revived:
            print("       - %s" % rel)
        return 1
    print("[OK] 反向门禁：请求器设置界面 + OpenRequesterPickerPacket 都不存在（第 131 轮 C 组）")

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
