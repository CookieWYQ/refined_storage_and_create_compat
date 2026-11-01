#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 96 轮 WS-C 自检：网络资源接收器
  ① 溜槽 / 外部容器能「推入」（可写包装 + 缓存唯一入口 + Create 源码证据）
  ② 界面改成「流体可取 + 无上限可滚动资源列表」（分页快照 + C2S 取出 + 1000 mB/桶）

判定口径来自 `docs/ROUND96_USER_FEEDBACK.md` 第 1 节 #4 / #6：

#4 「网络资源接收器还是不能跟溜槽之类的进行交互」
   旧实现对外暴露的是 ViewOnlyHandlers 的只读视图（insert/fill 一律退回），任何相邻容器都写不进来。
   Create 6.0.10 的 ChuteBlockEntity 用 `side.getOpposite()` 这种**非空**侧别查询
   `Capabilities.ItemHandler.BLOCK`，再 `ItemHandlerHelper.insertItemStacked(cap, item, simulate)`
   先 SIMULATE 后 EXECUTE —— 只要返回余量等于原数量，溜槽就判定「交不出去」，玩家看到的就是「不交互」。
   现在必须：可写包装（读委托、写落到 `applyResource` 唯一入口、外抽仍恒空）；
   注册忽略方向；外部推入的日志状态化（不刷屏）。

#6 「流体该怎么办……你这个格子这么少……资源是没有上限的」
   缓存种类无上限，旧界面只有 16 个固定镜像格 + 4 条流体条目 ⇒ 第 17 种物品 / 第 5 种流体看不见也取不出。
   现在必须：分页快照（P5 复核后每页 12 条 = 2 列 × 6 行，带 page/pageCount/totalTypes）；
   物品与流体混排的列表；
   取出走 C2S 包（资源身份 + 全部标志）；物品左键=一组、Shift=全部；流体左键=1000 mB 换 1 桶
   （背包里必须有空桶，没有就一点不扣并提示）、Shift=尽可能多整桶；客户端不读方块实体。

反例自证见 `MUTATIONS`：把每条修复逐一回退，对应断言必须翻红；任何一条回退抓不到即判负。
"""
import io
import json
import os
import re
import sys

# 本机控制台代码页是 GBK（cp936），断言消息里有 ⇒ / — 这类字符时直接 print 会 UnicodeEncodeError。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "src/main/java/cretae/cookiewyq/rs_create_compat"

FILES = {
    "be_r": PKG + "/block/entity/NetworkResourceReceiverBlockEntity.java",
    "block_r": PKG + "/block/NetworkResourceReceiverBlock.java",
    "menu_r": PKG + "/menu/NetworkResourceReceiverMenu.java",
    "scr_r": PKG + "/client/screen/NetworkResourceReceiverScreen.java",
    "vo": PKG + "/support/ViewOnlyHandlers.java",
    "pkt_sync": PKG + "/network/SyncReceiverConfigPacket.java",
    "pkt_entry": PKG + "/network/ReceiverCacheEntry.java",
    "pkt_action": PKG + "/network/ReceiverCacheActionPacket.java",
    "jade_payload": PKG + "/compat/jade/RsccReceiverJadePayload.java",
    "jade_provider": PKG + "/client/jade/RsccReceiverJadeProvider.java",
}
LANG_FILES = {
    "zh": "src/main/resources/assets/rs_create_compat/lang/zh_cn.json",
    "en": "src/main/resources/assets/rs_create_compat/lang/en_us.json",
}

R = "gui.rs_create_compat.network_resource_receiver."

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


def int_consts(source_text):
    """把 `static final int NAME = <表达式>;` 解析成 dict（支持「数字 / 已定义常量 / + - * /」）。

    为什么不是只匹配数字字面量：真实代码里 `NAME_X = ICON_SIZE + 3`、`SCROLLBAR_H = PAGE_ROWS * ROW_H`、
    `OK_X = BOX_X + BOX_W + 4` 都是**推导式** —— 几何断言必须顺着同一份推导算，
    否则「改了 PAGE_ROWS 却忘记改 SCROLLBAR_H」这类错位就抓不到。
    表达式先过白名单正则（只允许常量名 / 数字 / 四则运算 / 括号），再求值；解析不出来就跳过该常量。
    """
    exprs = {}
    for match in re.finditer(
            r"static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);", source_text):
        exprs[match.group(1)] = match.group(2).strip()
    resolved = {}

    def resolve(name, depth=0):
        if name in resolved:
            return resolved[name]
        if depth > 8 or name not in exprs:
            return None
        expr = exprs[name]
        if not re.fullmatch(r"[A-Za-z_0-9+\-*/(). ]+", expr):
            return None

        def repl(token):
            word = token.group(0)
            value = resolve(word, depth + 1)
            if value is None:
                raise KeyError(word)
            return "(%d)" % value

        try:
            python_expr = re.sub(r"[A-Za-z_][A-Za-z0-9_]*", repl, expr)
            value = eval(python_expr, {"__builtins__": {}}, {})  # noqa: S307（白名单已过滤）
            result = int(value)
        except Exception:
            return None
        resolved[name] = result
        return result

    out = {}
    for name in exprs:
        value = resolve(name)
        if value is not None:
            out[name] = value
    return out


def body_of(text, header_pattern):
    """取某个方法 / 类的 `{...}` 主体（只用于报错定位，不做语法分析）。"""
    match = re.search(header_pattern + r"\s*\{(.*?)\n    \}", text, re.S)
    return None if match is None else match.group(1)


def keys_referenced(source_text):
    prefix_match = re.search(r'String LANG = "([^"]*)";', source_text)
    prefix = prefix_match.group(1) if prefix_match else None
    found = set()
    if prefix:
        for match in re.finditer(r'LANG \+ "([A-Za-z0-9_.]+)"', source_text):
            found.add(prefix + match.group(1))
    for match in re.finditer(r'"((?:gui|config|block|item|advancements)\.[A-Za-z0-9_.]+)"', source_text):
        found.add(match.group(1))
    if prefix:
        found.discard(prefix)
    return found


# ===========================================================================
# ① 溜槽 / 外部容器能推入
# ===========================================================================
@check("A1", "ViewOnlyHandlers 的可写包装（默认只进不出 + 显式外抽落点），且既有只读语义一字未改")
def check_a1(src, zh, en):
    vo = src["vo"]
    # 第 101 轮锚点更新（诚实更新，不削弱）：
    #   ① 两个工厂的返回类型从 IItemHandler / IFluidHandler 收窄成具体类 WritableItem / WritableFluid
    #      —— 调用方才能 .withExtraction / .withDrain 显式接上「外抽落点」；
    #   ② WritableItem#extractItem 不再无条件恒空，而是「没接落点 ⇒ 恒空；接了落点 ⇒ 交给落点」。
    #      原因：Create 溜槽对上方容器**只抽不推**，第 96 轮只放行 insert 等于「溜槽在哪个方向都没反应」
    #      （用户第 101 轮原话「与溜槽交互的时候它是无视方向的」）。
    #   ③ 「写入口必须走 sink」「既有只读包装一字未改」两条保持原样。
    need = {
        "writableItem 工厂": r"public static WritableItem writableItem\(final IItemHandler delegate,\s*final ItemInsertSink sink\)",
        "writableFluid 工厂": r"public static WritableFluid writableFluid\(final IFluidHandler delegate,\s*final FluidInsertSink sink\)",
        "ItemInsertSink": r"public interface ItemInsertSink\s*\{[\s\S]{0,600}?int insert\(ItemStack stack, boolean simulate\);",
        "FluidInsertSink": r"public interface FluidInsertSink\s*\{[\s\S]{0,600}?int fill\(FluidStack stack, boolean simulate\);",
        "ItemExtractSink": r"public interface ItemExtractSink\s*\{[\s\S]{0,900}?ItemStack extract\(int slot, int amount, boolean simulate\);",
        "FluidDrainSink": r"public interface FluidDrainSink\s*\{[\s\S]{0,1200}?FluidStack drain\(FluidStack resource, int maxDrain, boolean simulate\);",
        "withExtraction": r"public WritableItem withExtraction\(final ItemExtractSink extractSink\)",
        "withDrain": r"public WritableFluid withDrain\(final FluidDrainSink drainSink\)",
        "WritableItem 类": r"public static final class WritableItem implements IItemHandler",
        "WritableFluid 类": r"public static final class WritableFluid implements IFluidHandler",
    }
    gone = [name for name, pattern in need.items() if missing(vo, pattern)]
    if gone:
        return False, "ViewOnlyHandlers 缺少可写包装 / 外抽落点：" + "、".join(gone)
    writable = re.search(r"public static final class WritableItem implements IItemHandler\s*\{(.*?)\n    \}",
                         vo, re.S)
    fluid = re.search(r"public static final class WritableFluid implements IFluidHandler\s*\{(.*?)\n    \}",
                      vo, re.S)
    if writable is None or fluid is None:
        return False, "找不到 WritableItem / WritableFluid 类体"
    if not has(writable.group(1), r"final int accepted = Math\.max\(0, Math\.min\(stack\.getCount\(\), sink\.insert\(stack, simulate\)\)\)"):
        return False, "WritableItem#insertItem 没有把写入交给 sink（而是自己写缓存？）"
    # 第 101 轮：默认（未接落点）必须仍然恒空；接了落点才允许真抽。
    if not has(writable.group(1), r"if \(extractSink == null \|\| amount <= 0\)\s*\{\s*return ItemStack\.EMPTY;"):
        return False, "WritableItem#extractItem 丢了「没接外抽落点 ⇒ 恒空」的默认语义"
    if not has(writable.group(1), r"extractSink\.extract\(slot, amount, simulate\)"):
        return False, "WritableItem#extractItem 没有把外抽交给落点（溜槽从上方抽不到东西）"
    if not has(fluid.group(1), r"if \(drainSink == null \|\| maxDrain <= 0\)\s*\{\s*return FluidStack\.EMPTY;"):
        return False, "WritableFluid#drain(int) 丢了「没接外排落点 ⇒ 恒空」的默认语义"
    if not has(fluid.group(1), r"drainSink\.drain\(FluidStack\.EMPTY, maxDrain,"):
        return False, "WritableFluid#drain(int) 没有把外排交给落点"
    # 既有只读语义：Item#insertItem 原样退回、Fluid#fill 恒 0
    ro_item = re.search(r"public static final class Item implements IItemHandler\s*\{(.*?)\n    \}", vo, re.S)
    ro_fluid = re.search(r"public static final class Fluid implements IFluidHandler\s*\{(.*?)\n    \}", vo, re.S)
    if ro_item is None or ro_fluid is None:
        return False, "既有只读包装 Item / Fluid 不见了（别的方块还在用）"
    if not has(ro_item.group(1), r"public ItemStack insertItem\(final int slot, final ItemStack stack, final boolean simulate\)\s*\{\s*return stack;"):
        return False, "只读包装 Item#insertItem 的语义被改了（别的方块会被外部写入）"
    if not has(ro_fluid.group(1), r"public int fill\(final FluidStack resource, final FluidAction action\)\s*\{\s*return 0;"):
        return False, "只读包装 Fluid#fill 的语义被改了"
    return True, "可写包装默认只进不出 + 显式外抽落点；既有只读包装 Item/Fluid 一字未改"


@check("A2", "接收器两个能力都注册成两向包装（忽略方向、显式接上外抽落点），旧的只读注册已全部撤掉")
def check_a2(src, zh, en):
    be = src["be_r"]
    reg = re.search(r"public static void registerCapabilities\(final RegisterCapabilitiesEvent event\)\s*\{(.*?)\n    \}",
                    be, re.S)
    if reg is None:
        return False, "找不到 registerCapabilities"
    text = reg.group(1)
    if not has(text, r"Capabilities\.ItemHandler\.BLOCK[\s\S]{0,400}ViewOnlyHandlers\.writableItem\(blockEntity\.itemCache,\s*blockEntity::insertItemFromExternal\)"):
        return False, "ItemHandler.BLOCK 没有注册成 writableItem(blockEntity::insertItemFromExternal)"
    if not has(text, r"Capabilities\.FluidHandler\.BLOCK[\s\S]{0,400}ViewOnlyHandlers\.writableFluid\(blockEntity\.fluidCache,\s*blockEntity::fillFluidFromExternal\)"):
        return False, "FluidHandler.BLOCK 没有注册成 writableFluid(blockEntity::fillFluidFromExternal)"
    # 第 101 轮：必须再挂上外抽 / 外排落点，否则溜槽（只从上方抽）永远吸不到东西。
    if not has(text, r"\.withExtraction\(blockEntity::extractItemFromExternal\)"):
        return False, "物品能力没有接外抽落点 withExtraction(::extractItemFromExternal)（溜槽抽不出来 = 用户那条 bug）"
    if not has(text, r"\.withDrain\(blockEntity::drainFluidFromExternal\)"):
        return False, "流体能力没有接外排落点 withDrain(::drainFluidFromExternal)"
    if has(text, r"ViewOnlyHandlers\.item\(blockEntity\.itemCache\)"):
        return False, "仍残留只读注册 ViewOnlyHandlers.item(...)（溜槽写不进来）"
    if has(text, r"ViewOnlyHandlers\.fluid\(blockEntity\.fluidCache\)"):
        return False, "仍残留只读注册 ViewOnlyHandlers.fluid(...)"
    # 忽略方向：注册 lambda 的 direction 形参不得参与任何判断
    if has(text, r"direction\s*==\s*null"):
        return False, "注册里开始按方向判断（用户要求「作为 inventory 提供者时不应该有方向限制」）"
    return True, "两个能力都是两向包装、均忽略方向、外抽落点已接上、无只读注册残留"


@check("A3", "外部推入落到缓存唯一入口 applyResource（容量上限与守恒只有一套账）")
def check_a3(src, zh, en):
    be = src["be_r"]
    item = body_of(be, r"public int insertItemFromExternal\(final ItemStack stack, final boolean simulate\)")
    fluid = body_of(be, r"public int fillFluidFromExternal\(final FluidStack stack, final boolean simulate\)")
    if item is None or fluid is None:
        return False, "找不到 insertItemFromExternal / fillFluidFromExternal"
    if not has(item, r"applyResource\(ItemResource\.ofItemStack\(stack\), requested, simulate\)"):
        return False, "insertItemFromExternal 没有走 applyResource（绕开缓存唯一入口）"
    if not has(fluid, r"applyResource\(\s*new FluidResource\(stack\.getFluid\(\), stack\.getComponentsPatch\(\)\), requested, simulate\)"):
        return False, "fillFluidFromExternal 没有走 applyResource"
    for name, text in (("物品", item), ("流体", fluid)):
        if not has(text, r"if \(getLevel\(\) == null \|\| getLevel\(\)\.isClientSide\(\)\)\s*\{\s*return 0;"):
            return False, name + "入口没有「客户端 / 无世界 ⇒ 一律 0」的防线（会本地写）"
        if not has(text, r"if \(!simulate && taken > 0\)\s*\{\s*setChanged\(\);") and \
                not has(text, r"if \(!simulate && taken > 0\)\s*\{\s*\n\s*setChanged\(\);"):
            return False, name + "入口在真正写入后没有 setChanged()（不会落盘）"
        # 负向：不得在入口里直接写缓存（那就绕开了 applyResource 的容量账）
        if has(text, r"itemCache\.insertItem\(") or has(text, r"fluidCache\.fill\("):
            return False, name + "入口绕开 applyResource 直接写缓存（容量账会出现两套）"
    return True, "两条入口都只转交 applyResource；客户端零写入；写后 setChanged"


@check("A4", "外部推入日志状态化：三种状态 + 同状态 30s 至多一条（不刷屏）")
def check_a4(src, zh, en):
    be = src["be_r"]
    need = {
        "三种状态常量": r"INPUT_STATE_FULL_ACCEPT\s*=\s*0;[\s\S]{0,200}INPUT_STATE_PARTIAL\s*=\s*1;[\s\S]{0,200}INPUT_STATE_REJECTED\s*=\s*2;",
        "状态字段": r"private int lastInputState = INPUT_STATE_NONE;",
        "独立前缀": r'private static final String INPUT_LOG_PREFIX = "\[rscc-receiver-input\]";',
        "节流判据": r"if \(state == lastInputState && now - lastInputLogTick < DEBUG_THROTTLE_TICKS\)\s*\{\s*return;",
        "变化立刻打": r"lastInputState = state;",
    }
    gone = [name for name, pattern in need.items() if missing(be, pattern)]
    if gone:
        return False, "外部推入日志缺少：" + "、".join(gone)
    item = body_of(be, r"public int insertItemFromExternal\(final ItemStack stack, final boolean simulate\)")
    fluid = body_of(be, r"public int fillFluidFromExternal\(final FluidStack stack, final boolean simulate\)")
    for name, text in (("物品", item), ("流体", fluid)):
        if text is None or not has(text, r"logExternalPush\("):
            return False, name + "入口没有调用状态化日志（外部推入将不可观测）"
    if has(be, r"private static final long DEBUG_THROTTLE_TICKS = 100L;"):
        return False, "DEBUG_THROTTLE_TICKS 退回 5 秒一条（刷屏）"
    return True, "三态 + 状态翻转立刻打 + 同状态 600 tick 上限"


@check("A5", "Create 溜槽源码证据写进注释（ChuteBlockEntity / insertItemStacked / 非空侧别）")
def check_a5(src, zh, en):
    joined = src["be_r"] + "\n" + src["vo"]
    evidence = {
        "ChuteBlockEntity": r"ChuteBlockEntity",
        "insertItemStacked（先 SIMULATE 后 EXECUTE）": r"insertItemStacked",
        "非空侧别查询": r"side\.getOpposite\(\)",
        "applyResource 是请求器同一入口": r"applyResource",
    }
    gone = [name for name, pattern in evidence.items() if missing(src["vo"], pattern)]
    if missing(joined, r"applyResource"):
        gone.append("applyResource 是请求器同一入口")
    if gone:
        return False, "注释缺少源码证据：" + "、".join(gone)
    return True, "注释含 Create 溜槽的三处源码证据 + 「与请求器同一入口」的说明"


@check("B6", "几何：条目区不与命名框 / 确定 / 「取全部」/ 升级槽 / 滚动条 / 背包标签 / 面板相交，"
             "且行高放得下 12px 图标 + 9px 文字")
def check_b6(src, zh, en):
    menu = src["menu_r"]
    scr = src["scr_r"]
    m = int_consts(menu)
    s = int_consts(scr)
    need_menu = ["LIST_X0", "LIST_Y0", "ROW_H", "PAGE_COLS", "PAGE_ROWS", "COL_W", "COL_GAP",
                 "ICON_SIZE", "NAME_X", "SCROLLBAR_X", "SCROLLBAR_Y", "SCROLLBAR_H",
                 "SCROLLBAR_W", "INV_Y0", "PANEL_W", "PANEL_H", "UPGRADE_X"]
    missing_menu = [k for k in need_menu if k not in m]
    need_screen = ["BOX_X", "BOX_Y", "BOX_W", "BOX_H", "OK_X", "OK_W",
                   "TAKE_ALL_X", "TAKE_ALL_Y", "TAKE_ALL_W", "TAKE_ALL_H"]
    missing_scr = [k for k in need_screen if k not in s]
    if missing_menu or missing_scr:
        return False, "几何常量缺失：menu=%s / screen=%s" % (missing_menu, missing_scr)

    # 条目区（最后一个行 / 列的真实边界，不含 gap）
    rx0 = m["LIST_X0"]
    ry0 = m["LIST_Y0"]
    rx1 = m["LIST_X0"] + m["PAGE_COLS"] * (m["COL_W"] + m["COL_GAP"]) - m["COL_GAP"]
    ry1 = m["LIST_Y0"] + m["PAGE_ROWS"] * m["ROW_H"]
    rows = (rx0, ry0, rx1, ry1)

    def overlaps(a, b):
        return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]

    conflicts = []
    boxes = {
        "命名输入框": (s["BOX_X"], s["BOX_Y"],
                   s["BOX_X"] + s["BOX_W"], s["BOX_Y"] + s["BOX_H"]),
        "确定按钮": (s["OK_X"], s["BOX_Y"], s["OK_X"] + s["OK_W"], s["BOX_Y"] + s["BOX_H"]),
        "「取全部」按钮": (s["TAKE_ALL_X"], s["TAKE_ALL_Y"],
                    s["TAKE_ALL_X"] + s["TAKE_ALL_W"], s["TAKE_ALL_Y"] + s["TAKE_ALL_H"]),
        "玩家背包（含标签行）": (0, m["INV_Y0"] - 10, m["PANEL_W"], m["PANEL_H"]),
    }
    for name, box in boxes.items():
        if overlaps(rows, box):
            conflicts.append("条目区 %s 与「%s」%s 相交" % (rows, name, box))
    # 滚动条：必须在条目区右侧、与条目区同源、不越出面板
    if not rx1 < m["SCROLLBAR_X"]:
        conflicts.append("条目区右边界 %s 未在滚动条（x=%s）左侧" % (rx1, m["SCROLLBAR_X"]))
    if m["SCROLLBAR_X"] + m["SCROLLBAR_W"] > m["PANEL_W"]:
        conflicts.append("滚动条右边界越出面板宽度 %s" % m["PANEL_W"])
    if m["SCROLLBAR_Y"] != m["LIST_Y0"] or m["SCROLLBAR_H"] != m["PAGE_ROWS"] * m["ROW_H"]:
        conflicts.append("滚动条几何（y=%s/h=%s）与条目区（y=%s/h=%s）不同源"
                         % (m["SCROLLBAR_Y"], m["SCROLLBAR_H"], m["LIST_Y0"],
                            m["PAGE_ROWS"] * m["ROW_H"]))
    # 升级槽栏：条目区整体在它左边
    if not rx1 < m["UPGRADE_X"]:
        conflicts.append("条目区右边界 %s 压到升级槽栏（x=%s）" % (rx1, m["UPGRADE_X"]))
    # 面板边界
    if rx1 >= m["PANEL_W"] or ry1 >= m["PANEL_H"]:
        conflicts.append("条目区 %s 越出面板 %s×%s" % (rows, m["PANEL_W"], m["PANEL_H"]))
    # 可读性：行高必须放得下「图标 + 9px 文字」；名称要有可用宽度
    if m["ICON_SIZE"] > m["ROW_H"]:
        conflicts.append("图标 %spx 比行高 %spx 还高（会压到下一行）"
                         % (m["ICON_SIZE"], m["ROW_H"]))
    if m["ROW_H"] < 11:
        conflicts.append("行高 %spx 放不下 9px 字体 + 上下留白" % m["ROW_H"])
    if m["COL_W"] - m["NAME_X"] < 60:
        conflicts.append("名称可用宽度只有 %spx（图标 + 数量之后会挤成 1~2 个字）"
                         % (m["COL_W"] - m["NAME_X"]))
    if conflicts:
        return False, "；".join(conflicts)
    return True, ("条目区 %s = %s 条（%s×%s，行高 %s）与 6 个相邻控件零相交；"
                  "图标 %spx ≤ 行高 %spx；名称可用宽度 %spx"
                  % (rows, m["PAGE_COLS"] * m["PAGE_ROWS"], m["PAGE_COLS"], m["PAGE_ROWS"],
                     m["ROW_H"], m["ICON_SIZE"], m["ROW_H"], m["COL_W"] - m["NAME_X"]))


# ===========================================================================
# ② 界面：流体可取 + 无上限可滚动资源列表
# ===========================================================================
@check("B1", "菜单：每页 2 列 × 6 行 = 12 条（行高 12px / 图标 12px）；固定 16 格实现已彻底移除")
def check_b1(src, zh, en):
    menu = src["menu_r"]
    consts = int_consts(menu)
    need = {
        "PAGE_COLS = 2": r"public static final int PAGE_COLS = 2;",
        "PAGE_ROWS = 6": r"public static final int PAGE_ROWS = 6;",
        "PAGE_SIZE 由行列推导": r"public static final int PAGE_SIZE = PAGE_COLS \* PAGE_ROWS;",
        "ROW_H = 12": r"public static final int ROW_H = 12;",
        "ICON_SIZE = 12": r"public static final int ICON_SIZE = 12;",
        "ICON_SCALE = ICON_SIZE / 16": r"public static final float ICON_SCALE = ICON_SIZE / 16\.0F;",
        "NAME_X = ICON_SIZE + 3": r"public static final int NAME_X = ICON_SIZE \+ 3;",
        "SCROLLBAR_H = 行数 × 行高": r"public static final int SCROLLBAR_H = PAGE_ROWS \* ROW_H;",
        "PLAYER_START 由升级槽推导": r"public static final int PLAYER_START = UPGRADE_START \+ UPGRADE_COUNT;",
    }
    gone = [name for name, pattern in need.items() if not has(menu, pattern)]
    if gone:
        return False, "菜单分页常量缺：" + "、".join(gone)
    size = consts.get("PAGE_COLS", 0) * consts.get("PAGE_ROWS", 0)
    if size < 12:
        return False, ("每页条目数 = %s × %s = %s < 12（第 96 轮复核 P5 的下限："
                       "缓存容量 1728 件、种类无上限，8 条/页要翻 200+ 页）"
                       % (consts.get("PAGE_COLS"), consts.get("PAGE_ROWS"), size))
    if consts.get("PAGE_SIZE") not in (None, size):
        return False, ("PAGE_SIZE 常量（%s）与 PAGE_COLS × PAGE_ROWS（%s）不一致"
                       % (consts.get("PAGE_SIZE"), size))
    for stale in ("CACHE_VIEW_COUNT", "CACHE_COLS", "CacheSlot", "SimpleMirrorContainer"):
        if has(menu, r"\b" + stale + r"\b"):
            return False, "仍残留旧 16 格固定实现的 %s（第 96 轮要求整段替换）" % stale
    return True, "%d 列 × %d 行 = %d 条/页（行高 %s px）；旧固定格实现零残留" % (
        consts["PAGE_COLS"], consts["PAGE_ROWS"], size, consts.get("ROW_H"))

@check("B2", "S2C 分页快照：带 page / pageCount / totalTypes / 每页条目，且服务端夹页 + 切片")
def check_b2(src, zh, en):
    pkt = src["pkt_sync"]
    if not has(pkt, r"int page, int pageCount, int totalTypes,\s*List<ReceiverCacheEntry> entries\)"):
        return False, "SyncReceiverConfigPacket 没有 page/pageCount/totalTypes/entries 组件"
    for pattern, name in (
        (r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.page\(\)\);", "编码 page"),
        (r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.pageCount\(\)\);", "编码 pageCount"),
        (r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.totalTypes\(\)\);", "编码 totalTypes"),
        (r"ReceiverCacheEntry\.STREAM_CODEC\.encode\(buf, entry\);", "编码条目"),
        (r"final int pageCount = Math\.max\(1, ByteBufCodecs\.VAR_INT\.decode\(buf\)\);", "解码时夹 1"),
        (r"entries\.add\(ReceiverCacheEntry\.STREAM_CODEC\.decode\(buf\)\);", "解码条目"),
    ):
        if missing(pkt, pattern):
            return False, "SyncReceiverConfigPacket 缺少：" + name
    menu = src["menu_r"]
    if not has(menu, r"final List<ReceiverCacheEntry> pageEntries = slice\(all, page\);") \
            and not has(menu, r"final List<ReceiverCacheEntry> pageEntries = slice\("):
        return False, "菜单没有把全量快照切片成「当前一页」"
    if not has(menu, r"final int from = Math\.max\(0, page\) \* PAGE_SIZE;"):
        return False, "slice 没有按 PAGE_SIZE 计算起点"
    if not has(menu, r"page = Math\.max\(0, Math\.min\(pages - 1, page\)\);") \
            and not has(menu, r"if \(page >= pageCount\)\s*\{\s*page = pageCount - 1;"):
        return False, "服务端没有把页码夹进 [0, pageCount-1]（翻页可能越界）"
    if not has(menu, r"page, pages, all\.size\(\), pageEntries\)"):
        return False, "服务端工厂调用没带 page/pages/totalTypes/pageEntries"
    return True, "分页快照四处一致 + 服务端夹页 + 按 PAGE_SIZE 切片"


@check("B3", "界面只读 S2C 快照（不读方块实体、不自己算页/不自己算数量）")
def check_b3(src, zh, en):
    scr = src["scr_r"]
    need = {
        "onServerSync": r"public void onServerSync\(final SyncReceiverConfigPacket packet\)",
        "读 entries": r"entries = List\.copyOf\(packet\.entries\(\)\);",
        "读页码": r"page = Math\.max\(0, packet\.page\(\)\);",
        "读总页数": r"pageCount = Math\.max\(1, packet\.pageCount\(\)\);",
        "读种类总数": r"totalTypes = packet\.totalTypes\(\);",
    }
    gone = [name for name, pattern in need.items() if missing(scr, pattern)]
    if gone:
        return False, "界面快照字段缺：" + "、".join(gone)
    if has(scr, r"menu\.getReceiver\(\)"):
        return False, "客户端界面读方块实体（menu.getReceiver(...)），违反「菜单重建不读 BE」"
    for forbidden in ("getItemCache", "getFluidCache", "getFluidSnapshot", "cacheEntries("):
        if has(scr, re.escape(forbidden)):
            return False, "客户端界面直接读缓存（%s）—— 客户端没有权威数据" % forbidden
    return True, "五个权威字段全部来自快照；客户端零方块实体访问"


@check("B4", "列表覆盖全部种类：逐行渲染（缩放图标 + 名称 + 数量）+ 悬停高亮 + 四个滚动入口")
def check_b4(src, zh, en):
    scr = src["scr_r"]
    need = {
        "逐行渲染": r"for \(int i = 0; i < entries\.size\(\); i\+\+\)",
        "图标按 ICON_SCALE 缩放": r"pose\.scale\(NetworkResourceReceiverMenu\.ICON_SCALE,",
        "物品图标": r"guiGraphics\.renderItem\(entry\.item\(\), 0, 0\)",
        "流体图标": r"GhostMarkerRenderer\.renderFluid\(guiGraphics, 0, 0, entry\.fluid\(\)\)",
        "数量文本": r"RsccNumberField\.compact\(entry\.amount\(\)\)",
        "名称按 NAME_X 偏移": r"x \+ NetworkResourceReceiverMenu\.NAME_X",
        # 第 104 轮：名称不再按像素截断（用户口径：界面文本一律完整显示）⇒ 改为
        # RsccTextFit.drawFitLine(..., nameMax, ...)：放不下整体缩字号、下限 0.6 倍、绝不加省略号。
        # 判据不削弱：名称仍必须被 nameMax 这个「数量列之前的可用宽度」约束住（不许压到数量）。
        "名称受 nameMax 约束绘制": (r"RsccTextFit\.drawFitLine\(guiGraphics, font,\s*\n\s*"
                                   r"name == null \? Component\.empty\(\) : name,\s*\n\s*"
                                   r"x \+ NetworkResourceReceiverMenu\.NAME_X,[\s\S]{0,120}?nameMax,"
                                   r" COLOR_TEXT\);"),
        "行悬停高亮": r"if \(i == hovered\)",
        "行 tooltip（完整名称）": r"private void renderEntryTooltip",
        "滚动条": r"new ScrollbarWidget\(",
        "滚轮翻页": r"requestPage\(page - \(int\) Math\.signum\(scrollY\)\)",
        "页码箭头可点": r"final int arrow = pageArrowAt\(mouseX, mouseY\);",
    }
    gone = [name for name, pattern in need.items() if missing(scr, pattern)]
    if gone:
        return False, "接收器界面缺少：" + "、".join(gone)
    labels = re.search(r"protected void renderLabels\(.*?\n    \}", scr, re.S)
    if labels is None or missing(labels.group(0), r"renderEntries\(guiGraphics, mouseX, mouseY\);") \
            or missing(labels.group(0), r"renderPageHeader\(guiGraphics\);"):
        return False, "renderLabels 没有真正调用 renderEntries / renderPageHeader（列表画不出来）"
    return True, "逐行（缩放图标 + 名称 + 数量）+ 悬停高亮 + 滚动条 / 滚轮 / 箭头 / 行 tooltip"

@check("B5", "取出走 C2S 包（资源身份 + 全部标志），服务端三道校验后才扣减")
def check_b5(src, zh, en):
    pkt = src["pkt_action"]
    need = {
        "三个动作": r"ACTION_TAKE_ITEM = 0;[\s\S]{0,400}ACTION_TAKE_FLUID = 1;[\s\S]{0,400}ACTION_PAGE = 2;",
        "资源身份编码": r"ItemStack\.OPTIONAL_STREAM_CODEC\.encode\(buf",
        "全部标志": r"ByteBufCodecs\.BOOL\.encode\(buf, packet\.all\(\)\);",
        "转交菜单": r"NetworkResourceReceiverMenu\.handleCacheAction\(player, packet\);",
    }
    gone = [name for name, pattern in need.items() if missing(pkt, pattern)]
    if gone:
        return False, "C2S 包缺：" + "、".join(gone)
    menu = src["menu_r"]
    handler = body_of(menu, r"public static void handleCacheAction\(final ServerPlayer player,\s*final ReceiverCacheActionPacket packet\)")
    if handler is None:
        return False, "菜单没有 handleCacheAction"
    for pattern, name in (
        (r"if \(open == null \|\| open\.containerId != packet\.containerId\(\)\)\s*\{\s*return;", "containerId 校验"),
        (r"if \(!\(open instanceof NetworkResourceReceiverMenu menu\)\)\s*\{\s*return;", "菜单类型校验"),
        (r"if \(!menu\.stillValid\(player\)\)\s*\{\s*return;", "距离 / 存在性校验"),
    ):
        if missing(handler, pattern):
            return False, "handleCacheAction 缺少：" + name
    dispatch = body_of(menu, r"private void applyCacheAction\(final Player player,\s*final ReceiverCacheActionPacket packet\)")
    if dispatch is None:
        return False, "菜单没有 applyCacheAction 分发"
    for action in ("ACTION_TAKE_ITEM", "ACTION_TAKE_FLUID", "ACTION_PAGE"):
        if missing(dispatch, action):
            return False, "applyCacheAction 没有分发 " + action
    return True, "C2S 三动作 + containerId / 类型 / 距离三道校验 + 分发"


@check("C1", "物品取出：按身份跨格真实扣减，放不下回填缓存（绝不复制、绝不销毁）")
def check_c1(src, zh, en):
    menu = src["menu_r"]
    take = body_of(menu, r"private void takeItem\(final Player player, final ItemStack identity, final boolean all\)")
    if take is None:
        return False, "找不到 takeItem"
    need = {
        "按身份定位": r"ItemStack\.isSameItemSameComponents\(each, wanted\)",
        "逐格真实扣减": r"cache\.extractItem\(slot, Math\.min\(remaining, each\.getCount\(\)\), false\)",
        "塞背包": r"player\.getInventory\(\)\.add\(rest\);",
        "放不下回填": r"final ItemStack refused = cache\.insertItem\(0, rest, false\);",
        "兜底不销毁": r"player\.drop\(refused, false\);",
        "落盘": r"receiver\.setChanged\(\);",
    }
    gone = [name for name, pattern in need.items() if missing(take, pattern)]
    if gone:
        return False, "takeItem 缺少：" + "、".join(gone)
    if has(take, r"getStackInSlot\([^)]*\)\.copy\(\);") and missing(take, r"extractItem"):
        return False, "takeItem 只复制了缓存内容（复制缺陷）"
    if has(take, r"extractItem\([^)]*,\s*true\)"):
        return False, "takeItem 用 SIMULATE 当扣减（玩家会白拿）"
    return True, "身份定位 → 逐格 extractItem(EXECUTE) → 塞背包 → 回填 → 落盘"


@check("C2", "手势：物品左键=一组、Shift=全部（客户端 hasShiftDown，服务端按 all 夹数量）")
def check_c2(src, zh, en):
    menu = src["menu_r"]
    take = body_of(menu, r"private void takeItem\(final Player player, final ItemStack identity, final boolean all\)")
    if take is None:
        return False, "找不到 takeItem"
    if missing(take, r"final int perStack = Math\.max\(1, Math\.min\(64, wanted\.getMaxStackSize\(\)\)\);[\s\S]{0,200}final int want = all \? stored : Math\.min\(perStack, stored\);") \
            and missing(take, r"final int want = all \? stored : Math\.min\(perStack, stored\);") \
            and missing(take, r"final int want = all \? stored : Math\.min\(perStack, stored\);"):
        return False, "takeItem 没有「一组 vs 全部」的数量夹取（perStack 上限失效）"
    scr = src["scr_r"]
    if missing(scr, r"requestTake\(entries\.get\(entryIndex\), hasShiftDown\(\)\);"):
        return False, "界面没有把 Shift 状态传进取出请求"
    return True, "客户端 hasShiftDown → all；服务端一组 = min(最大堆叠, 存量)"


@check("D1", "流体取出：1000 mB/桶；背包里没有空桶 ⇒ 一点不扣 + 明确提示")
def check_d1(src, zh, en):
    menu = src["menu_r"]
    if missing(menu, r"public static final long BUCKET_MB = 1000L;"):
        return False, "BUCKET_MB 不是 1000"
    take = body_of(menu, r"private void takeFluid\(final Player player, final FluidStack identity, final boolean all\)")
    if take is None:
        return False, "找不到 takeFluid"
    gate_ok = (has(take, r"final int emptyBuckets = countEmptyBuckets\(player\);")
               and has(take, r"if \(emptyBuckets <= 0\)\s*\{[\s\S]{0,300}?"
                           r"sendConfigSyncInternal\(MSG_FLUID_NO_CONTAINER\);[\s\S]{0,120}?return;"))
    if not gate_ok:
        return False, "takeFluid 没有「先数空桶，没有就提示并 return」的前置闸门（会先扣后报错）"
    # 关键：空桶闸门必须在真 drain 之前
    gate = take.find("emptyBuckets <= 0")
    drain = take.find("IFluidHandler.FluidAction.EXECUTE")
    if gate < 0 or drain < 0 or gate > drain:
        return False, "空桶闸门在真 drain 之后（会「扣了才发现没桶」）"
    if missing(menu, r"private static boolean isEmptyBucket\(final ItemStack stack\)") \
            or missing(menu, r"stack\.is\(Items\.BUCKET\)"):
        return False, "「空桶」判据不是 Items.BUCKET"
    return True, "1000 mB/桶 + 空桶前置闸门（无空桶零扣减 + 提示）"


@check("D2", "流体守恒：先模拟桶形态 → 真 drain 恰好 1 桶 → 消耗空桶 → 发放满桶；失败互相回滚")
def check_d2(src, zh, en):
    menu = src["menu_r"]
    take = body_of(menu, r"private void takeFluid\(final Player player, final FluidStack identity, final boolean all\)")
    probe = body_of(menu, r"private static ItemStack simulateFilledBucket\(final IFluidHandler source\)")
    if take is None or probe is None:
        return False, "找不到 takeFluid / simulateFilledBucket"
    if missing(probe, r"FluidUtil\.tryFillContainer\(empty, source, \(int\) BUCKET_MB, null, false\)"):
        return False, "桶形态探针不是 SIMULATE（doFill=false），会真的扣流体"
    if missing(take, r"receiver\.getFluidCache\(\)\.drain\(\s*identity\.copyWithAmount\(\(int\) BUCKET_MB\), IFluidHandler\.FluidAction\.EXECUTE\)"):
        return False, "takeFluid 没有按身份 drain 恰好 1 桶（EXECUTE）"
    if missing(take, r"if \(!consumeOneEmptyBucket\(player\)\)\s*\{\s*\n?\s*receiver\.getFluidCache\(\)\.fill\(drained, IFluidHandler\.FluidAction\.EXECUTE\);"):
        return False, "消耗空桶失败时没有把流体灌回缓存（会「扣了没给」）"
    if missing(take, r"giveToPlayer\(player, filledTemplate\.copy\(\)\);") \
            and missing(take, r"giveToPlayer\(player, "):
        return False, "takeFluid 没有发放满桶"
    if not has(take, r"if \(drained\.getAmount\(\) < \(int\) BUCKET_MB\)") \
            and not has(take, r"drained\.getAmount\(\) <"):
        return False, "takeFluid 没有处理「剩余不足一桶」的分支"
    return True, "SIMULATE 探针 + 精确 drain + 双向回滚 + 满桶发放"


@check("D3", "流体 Shift = 尽可能多整桶（受空桶数与存量夹取），剩余仍留在缓存")
def check_d3(src, zh, en):
    menu = src["menu_r"]
    take = body_of(menu, r"private void takeFluid\(final Player player, final FluidStack identity, final boolean all\)")
    if take is None:
        return False, "找不到 takeFluid"
    if missing(take, r"final int maxBuckets = \(int\) Math\.min\(emptyBuckets, current\.getAmount\(\) / BUCKET_MB\);") \
            and missing(take, r"final int maxBuckets = \(int\) Math\.min\("):
        return False, "没有 maxBuckets = min(空桶数, 存量/1000)"
    if missing(take, r"final int buckets = all \? maxBuckets : 1;"):
        return False, "没有「Shift = 尽可能多整桶 / 左键 = 1 桶」的分档"
    return True, "maxBuckets 夹取 + all 分档；剩余留在缓存"


@check("E1", "服务端权威：客户端菜单不写缓存、不自己算；服务端侧判定收口在一处")
def check_e1(src, zh, en):
    menu = src["menu_r"]
    if missing(menu, r"private boolean isServerAuthoritative\(\)\s*\{\s*return receiver != null && receiver\.getLevel\(\) != null && !receiver\.getLevel\(\)\.isClientSide\(\);"):
        return False, "isServerAuthoritative 判据被改（客户端菜单可能写缓存）"
    for pattern, name in (
        (r"private void sendConfigSyncInternal\(final String messageKey\)\s*\{\s*if \(!isServerAuthoritative\(\)\)", "发快照"),
        (r"private void applyCacheAction\(final Player player, final ReceiverCacheActionPacket packet\)\s*\{\s*if \(!isServerAuthoritative\(\)\)", "C2S 动作"),
        (r"public boolean clickMenuButton\(final Player player, final int id\)\s*\{\s*if \(!isServerAuthoritative\(\)\)", "菜单按钮"),
    ):
        if missing(menu, pattern):
            return False, name + "路径没有服务端侧闸门"
    return True, "三条写路径都先判 isServerAuthoritative"


@check("E2", "dist 安全：network/** 不引用 client/**；新包只在服务端处理")
def check_e2(src, zh, en):
    for key in ("pkt_sync", "pkt_entry", "pkt_action"):
        text = src[key]
        if (has(text, r"^import\s+net\.minecraft\.client\.", re.M)
                or has(text, r"^import\s+cretae\.cookiewyq\.rs_create_compat\.client\.", re.M)
                or has(text, r"Minecraft\.getInstance\(\)")):
            return False, "%s 引用了客户端专属类型（专服会 NoClassDefFoundError）" % key
    if missing(src["pkt_sync"], r"ClientPayloadHooks\.get\(\)\.syncNetworkResourceReceiver\(packet\)"):
        return False, "S2C 处理体没有走 ClientPayloadHooks 间接层"
    return True, "network 包零客户端类型；S2C 走 ClientPayloadHooks"


@check("E3", "语言键：界面 / 菜单引用的键全部中英齐全（本轮 0 个新键口径）")
def check_e3(src, zh, en):
    problems = []
    for key in ("scr_r", "menu_r"):
        for ref in sorted(keys_referenced(src[key])):
            if ref not in zh or ref not in en:
                problems.append("%s 引用的键不存在：%s" % (key, ref))
    if problems:
        return False, "；".join(sorted(set(problems))[:8])
    return True, "界面 / 菜单引用键全部中英齐全"


@check("E4", "Jade 未退化：能力仍暴露（内容由 Jade Universal side=null 读取），provider 不自写 Shift")
def check_e4(src, zh, en):
    jade = src["jade_provider"]
    payload = src["jade_payload"]
    if has(jade, r"RsccTooltipLayers\.held\("):
        return False, "Jade provider 又自己判 Shift（用户明确不要）"
    if not has(payload, r"getCapability"):
        return False, "Jade payload 注释丢了能力查询证据"
    if missing(src["be_r"], r"Capabilities\.ItemHandler\.BLOCK") \
            or missing(src["be_r"], r"Capabilities\.FluidHandler\.BLOCK"):
        return False, "接收器不再暴露物品 / 流体能力（Jade 内容行会空）"
    return True, "能力仍在、Provider 只补名字"


# ===========================================================================
# 反例自证：把每条修复回退，对应断言必须翻红
# ===========================================================================
MUTATIONS = [
    ("A1", "vo", [(r"if \(extractSink == null \|\| amount <= 0\)\s*\{\s*return ItemStack\.EMPTY;",
                   "if (amount <= 0) {\n            return ItemStack.EMPTY; // mutated: 默认也放行外抽")],
     "可写包装默认就放行外抽（没接落点的方块会被自动化搬空）"),
    ("A1", "vo", [(r"extractSink\.extract\(slot, amount, simulate\)",
                   "ItemStack.EMPTY // mutated: 接了落点也不真抽")],
     "接了外抽落点却不真抽（溜槽从上方仍然吸不到东西 = 用户那条 bug 的翻版）"),
    ("A1", "vo", [(r"public ItemStack insertItem\(final int slot, final ItemStack stack, final boolean simulate\)\s*\{\s*return stack; // 只读：原样退回（绝不吞物品）",
                   "public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {\n            return ItemStack.EMPTY; // mutated")],
     "把既有只读包装 Item#insertItem 改成吞物品（别的方块会丢东西）"),
    ("A2", "be_r", [(r"\.withExtraction\(blockEntity::extractItemFromExternal\)",
                     "// mutated: 不接外抽落点")],
     "物品能力不接外抽落点（溜槽只抽不推 ⇒ 完全不交互 = 用户第 101 轮那条 bug）"),
    ("A2", "be_r", [(r"ViewOnlyHandlers\.writableItem\(blockEntity\.itemCache,\s*blockEntity::insertItemFromExternal\)",
                     "ViewOnlyHandlers.item(blockEntity.itemCache)")],
     "物品能力退回只读（溜槽写不进来 = 用户第 96 轮原话的那条 bug）"),
    ("A3", "be_r", [(r"final long accepted = applyResource\(ItemResource\.ofItemStack\(stack\), requested, simulate\);",
                     "final long accepted = itemCache.insertItem(0, stack, simulate).isEmpty() ? requested : 0L; // mutated")],
     "外部推入绕开 applyResource 直接写缓存（容量账两套）"),
    ("A4", "be_r", [(r"if \(state == lastInputState && now - lastInputLogTick < DEBUG_THROTTLE_TICKS\)",
                     "if (false) // mutated")],
     "外部推入日志不再按状态节流（连续推送会刷屏）"),
    ("A5", "vo", [(r"insertItemStacked", "insertStack")],
     "注释丢掉 Create 溜槽「先 SIMULATE 后 EXECUTE」的源码证据"),
    ("B1", "menu_r", [(r"public static final int PAGE_ROWS = 6;",
                       "public static final int PAGE_ROWS = 5;")],
     "每页行数退回 5（每页只剩 10 条 < 12，P5 的容量口径不达标）"),
    ("B6", "menu_r", [(r"public static final int LIST_Y0 = 88;",
                       "public static final int LIST_Y0 = 120;")],
     "条目区整体下移 32px（会压到玩家背包标签 / 背包槽）"),
    ("B6", "menu_r", [(r"public static final int ROW_H = 12;",
                       "public static final int ROW_H = 10;")],
     "行高压到 10px（9px 字体 + 留白放不下）"),
    ("B6", "menu_r", [(r"public static final int ICON_SIZE = 12;",
                       "public static final int ICON_SIZE = 16;")],
     "图标保持 16px 却仍用 12px 行高（图标会压到下一行）"),
    ("B1", "menu_r", [(r"public static final int PAGE_COLS = 2;", "public static final int CACHE_COLS = 2;")],
     "分页常量被改名成旧 CACHE_COLS（旧固定格实现残留）"),
    ("B2", "pkt_sync", [(r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.pageCount\(\)\);", "// mutated")],
     "快照不编码总页数（界面页码永远 1/1）"),
    ("B2", "menu_r", [(r"page = Math\.max\(0, Math\.min\(pages - 1, page\)\);", "// mutated")],
     "服务端不再夹页码（翻页可能越界空白）"),
    ("B3", "scr_r", [(r"entries = List\.copyOf\(packet\.entries\(\)\);",
                      "entries = List.copyOf(packet.entries()); // 见 menu.getReceiver() mutated")],
     "界面注释里出现 client 读 BE 的写法（纪律退化）"),
    ("B4", "scr_r", [(r"requestPage\(page - \(int\) Math\.signum\(scrollY\)\);", "// mutated")],
     "滚轮不再翻页（列表滚不动）"),
    ("B4", "scr_r", [(r"guiGraphics\.renderItem\(entry\.item\(\), 0, 0\);", "// mutated")],
     "物品行不再画图标"),
    ("B5", "menu_r", [(r"if \(!menu\.stillValid\(player\)\)\s*\{\s*return;", "// mutated")],
     "C2S 取出不再校验距离 / 存在性"),
    ("C1", "menu_r", [(r"cache\.extractItem\(slot, Math\.min\(remaining, each\.getCount\(\)\), false\)",
                       "cache.extractItem(slot, Math.min(remaining, each.getCount()), true)")],
     "物品取出改用 SIMULATE（玩家白拿）"),
    ("C2", "menu_r", [(r"final int want = all \? stored : Math\.min\(perStack, stored\);",
                       "final int want = all ? stored : stored;")],
     "左键也一次取光（左键 / Shift 分档失效）"),
    ("D1", "menu_r", [(r"if \(emptyBuckets <= 0\)\s*\{[\s\S]{0,300}?sendConfigSyncInternal\(MSG_FLUID_NO_CONTAINER\);[\s\S]{0,120}?return;[\s\S]{0,20}?\n        \}",
                       "// mutated: 没有空桶也照扣")],
     "没有空桶也照扣（用户口径：没有空桶就不扣 + 提示）"),
    ("D2", "menu_r", [(r"if \(!consumeOneEmptyBucket\(player\)\)\s*\{\s*\n\s*receiver\.getFluidCache\(\)\.fill\(drained, IFluidHandler\.FluidAction\.EXECUTE\);\s*\n\s*break;\s*\n\s*\}",
                       "// mutated")],
     "消耗空桶失败时不回滚（扣了没给）"),
    ("D3", "menu_r", [(r"final int buckets = all \? maxBuckets : 1;", "final int buckets = 1;")],
     "Shift 只能换一桶（尽可能多整桶失效）"),
    ("E1", "menu_r", [(r"private boolean isServerAuthoritative\(\)\s*\{\s*return receiver != null && receiver\.getLevel\(\) != null && !receiver\.getLevel\(\)\.isClientSide\(\);",
                       "private boolean isServerAuthoritative() {\n        return receiver != null; // mutated")],
     "服务端侧判据退化成「有方块实体就算」（客户端会写缓存）"),
    ("E2", "pkt_action", [(r"NetworkResourceReceiverMenu\.handleCacheAction\(player, packet\);",
                           "net.minecraft.client.Minecraft.getInstance(); // mutated")],
     "C2S 处理体引用客户端类型（专服崩）"),
    ("E3", "scr_r", [(r'LANG \+ "cache\.take_all\.hint"', 'LANG + "cache.take_all.hint_typo"')],
     "界面引用了语言文件里不存在的键（会显示成原始 key）"),
    ("E4", "jade_provider", [(r"for \(final Component line : RsccReceiverJadePayload\.lines\(accessor\.getServerData\(\)\)\) \{",
                              "final boolean expanded = RsccTooltipLayers.held(RsccTooltipLayers.Layer.SHIFT);\n"
                              "        for (final Component line : RsccReceiverJadePayload.lines(accessor.getServerData(), expanded)) {")],
     "Jade provider 又自己判 Shift"),
]


def apply_mutation(src, rel_key, replacements):
    text = src[rel_key]
    for pattern, replacement in replacements:
        # count=0：同一模式在多个文件位置出现时也要全部回退，否则「只改到一处」会让反例自证假绿
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
        except Exception as exc:  # 断言函数自身出错也算判负（不能静默跳过）
            ok, detail = False, "断言抛异常：%r" % (exc,)
        results.append((cid, desc, ok, detail))
    return results


def main():
    print("=" * 78)
    print("第 96 轮 WS-C 自检：接收器 溜槽可推入 + 流体可取 + 无上限滚动资源列表")
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

    # ---- 反例自证 ----
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
        except Exception as exc:
            ok_after = False  # 回退后断言直接抛异常也算「翻红」
        caught = not ok_after
        mutation_results.append((cid, why, caught,
                                 "回退后仍判 PASS（判据抓不到这条修复）" if not caught else ""))

    if failures:
        print("-" * 78)
        print("[result] 自检失败：%d / %d 项断言未通过" % (len(failures), len(results)))
        return 1

    bad_mutations = [row for row in mutation_results if not row[2]]
    print("-" * 78)
    print("[mutations] 反例自证：%d / %d 条回退被对应断言抓到" %
          (len(mutation_results) - len(bad_mutations), len(mutation_results)))
    for cid, why, ok, why_fail in mutation_results:
        print("%s MUT %s %s" % ("PASS" if ok else "FAIL", cid, why))
        if not ok:
            print("       -> %s" % why_fail)
    if bad_mutations:
        print("[result] 自检失败：%d 条回退抓不到（判据有洞）" % len(bad_mutations))
        return 1
    print("[result] 全部通过：%d 项断言 + %d 条反例自证" % (len(results), len(mutation_results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
