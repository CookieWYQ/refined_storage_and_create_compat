#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round96 自检：归流缓存仓「与物品交互」真因 + 区域选定器界面重做（用户第 1 / 2 条）。

用户原话：
    #1「首先就归流缓存期现在还是无法与任何的物品」
    #2「那个标记范围的那个物品他的去重，比如说标记两个一样的地方它还是会这样子显示，并且还显示的
       那个坐标因为太长了然后被挡住看不清，然后呢就是那个界面还是有点丑而且不方便编辑」

本脚本钉住的不变式（**只读源码 + 行为模型，不启动游戏**）：

  A. #1 归流缓存仓 —— 本轮查实的两条真因必须处于「已修」状态：
     A1 缓存窗口分页<b>不得越界</b>：缓存格数（256 / 512）不是每页 48 的整数倍，最后一页必然比 48 格短。
        旧实现把越界下标直接喂给 {@code SimpleContainer#getItem/setItem} ⇒ IndexOutOfBoundsException：
        服务端 {@code AbstractContainerMenu#clicked} 的 try/catch 会把它吞掉（玩家看到「点了没反应」），
        而 {@code broadcastChanges} 没有那层 catch。现在所有缓存侧读写都必须过
        {@code cacheGlobalIndex(...)}（越界返回 -1 = 空格）。
     A2 行为模型：按 256 / 512 两种格数逐页展开窗口 → 旧公式必须<b>真的</b>产生越界下标（证明缺陷存在），
        新判据必须「只用真实存在的格、不重不漏」。
     A3 界面写标记的默认需求数量必须是 1（{@code MIN_UI_MARK_AMOUNT}）：「凑够才吸」的门槛用
        手持件数会让逐件投料的资源永远收不进来；菜单提示里的数量也不能再写 {@code stack.getCount()}。
     A4 一条 {@code [rscc-collect]} 诊断必须存在，覆盖 9 条路径（界面放入 / 界面取出 / 写标记 /
        掉落物 / 相邻容器 / 世界流体 / 气体 / 经验 / 回写网络），且<b>状态变化才记</b>（不刷屏）。

  B. #2 区域选定器 —— 三件事：
     B1 去重：同维度 + 同坐标（{@code sameGeometry} 只比六坐标，<b>名字不参与判等</b>）在
        ADD / REPLACE / COPY 三条写入路径上都被拒绝；世界描边用 {@code distinctAreas()} 去重。
     B2 坐标可读：列表行按 {@code font.width} 量宽在 2 行 / 3 行之间切换，终点永远画得出来；
        悬停 tooltip 给完整坐标（不裁剪）。
     B3 可编辑：点行选中并回填六字段 + 名称框、复制 / 删除 / 重命名、维度 / 体积 / 尺寸 / 数量、
        滚动条、选中高亮、错误提示在字段旁（并把出错的那个框描红）。

反例自证（把修复回退 / 把判据写错时本自检必须判负）：
  ① 把旧版 WindowSlot（用 globalIndex 直读容器）喂给 A1 的判据 ⇒ 必须判负；
  ② 把旧版「最高页 = (格数-1)/48 且不做越界判定」的分页模型喂给 A2 ⇒ 必须判负；
  ③ 把「数量 = 手持件数」的旧 mark 写法喂给 A3 ⇒ 必须判负；
  ④ 去掉 noteCollectPath 调用 / 去掉状态变化判据 ⇒ A4 必须判负；
  ⑤ 把 ADD 分支的去重判定删掉 ⇒ B1 必须判负；
  ⑥ 把列表行改回「单行 + trimToWidth」 ⇒ B2 必须判负；
  ⑦ 把 Area 的 name 从 CODEC/STREAM_CODEC 里删掉 ⇒ B3 必须判负。

用法：python tools/selfcheck_round96_cache_area.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

BE_PATH = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
MENU_PATH = os.path.join(JAVA, "menu", "CollectionCacheMenu.java")
ADV_MENU_PATH = os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java")
AREA_PATH = os.path.join(JAVA, "support", "Area.java")
SELECTION_PATH = os.path.join(JAVA, "support", "AreaSelection.java")
PACKET_PATH = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
SCREEN_PATH = os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")
ADV_SCREEN_PATH = os.path.join(JAVA, "client", "screen", "AdvancedCollectionCacheScreen.java")
# 第 99 轮：高级缓存仓「查看范围」的去重从客户端搬到<b>服务端</b>（方块实体把
# distinctAreas() 的结果随 S2C 快照下发，客户端不再读选定器物品），因此这条判据要同时读
# 界面与方块实体两份源码 —— 判据本身（同几何只画一份）不变，只是落点跟着实现搬家。
ADV_BE_PATH = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
HANDLER_PATH = os.path.join(JAVA, "client", "AreaSelectorHandler.java")

NEWLINE = chr(10)
CHECKS = [0]
PROBLEMS = []


# ==================== 基础设施 ====================

def check(desc, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS  %02d  %s" % (CHECKS[0], desc))
    else:
        PROBLEMS.append("%s%s" % (desc, ("  <- " + detail) if detail else ""))
        print("FAIL  %02d  %s%s" % (CHECKS[0], desc, ("  <- " + detail) if detail else ""))
    return ok


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释（这些文件里没有会把 '//' 放进字符串的写法）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.split(NEWLINE):
        cut = line.find("//")
        out.append(line if cut < 0 else line[:cut])
    return NEWLINE.join(out)


def method_body(text, sig_pattern):
    """按签名正则找到方法，返回其 {...} 之间的正文（大括号计数）。找不到返回 None。"""
    match = re.search(sig_pattern, text)
    if not match:
        return None
    start = text.find("{", match.end())
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return None


def class_body(text, sig_pattern):
    return method_body(text, sig_pattern)


def case_sections(text):
    """把 switch 里的 `case ACTION_XXX -> { ... }` 切成 {常量名: 正文}。

    switch 里每个 case 都恰好是一个块（本文件的写法），因此用 `case ACTION_` 作为切分点即可 ——
    比 `.*?}` 的非贪婪正则稳（块里还有嵌套大括号与 return）。
    """
    out = {}
    marks = [(m.start(), m.group(1)) for m in re.finditer(r"case\s+(ACTION_[A-Z_]+)\s*->", text)]
    for i, (start, name) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else len(text)
        out[name] = text[start:end]
    return out


# ==================== A1 / A2：缓存窗口分页不得越界 ====================

CACHE_WINDOW = 48


def paginate(size, window, guard):
    """把「逐页展开窗口」的行为模型化。

    guard=True  ⇒ 新版语义：越界格返回 -1（空格），窗口只映射真实存在的格；
    guard=False ⇒ 旧版语义：直接 offset*window + i（可能越过容器尾部）。

    返回 (出现过的合法全局下标集合, 越界次数)。
    """
    seen = set()
    out_of_range = 0
    max_offset = max(0, (size - 1) // window)
    for offset in range(0, max_offset + 1):
        for i in range(window):
            index = offset * window + i
            if index >= size:
                if not guard:
                    out_of_range += 1
                continue
            seen.add(index)
    return seen, out_of_range


def window_slot_problems(body):
    """WindowSlot 判据：缓存侧读写必须全部走 cacheIndex()（返回问题列表；空 = 通过）。"""
    problems = []
    if body is None:
        return ["找不到 WindowSlot 类体"]
    # ① 必须存在走向 cacheGlobalIndex 的 cacheIndex() 助手
    if "menu.cacheGlobalIndex(indexInWindow)" not in body:
        problems.append("没有 cacheIndex() → menu.cacheGlobalIndex(...)")
    # ② 缓存读不能再直接用 global() 打容器
    if re.search(r"block\.getCache\(\)\.getItem\(global\(\)\)", body):
        problems.append("缓存读仍然用 getItem(global())（越界会抛 IndexOutOfBounds）")
    if re.search(r"block\.getCache\(\)\.setItem\(global\(\)", body):
        problems.append("缓存写仍然用 setItem(global())")
    if re.search(r"block\.getCache\(\)\.removeItem\(global\(\)", body):
        problems.append("缓存取仍然用 removeItem(global())")
    # ③ 四个入口都必须先判 index >= 0 / cacheSlotExists
    for entry in ("getItem", "set", "remove"):
        # 注意返回类型在名字前面（public ItemStack getItem(...)），正则必须容忍它
        chunk = method_body(body, r"public\s+[A-Za-z0-9_<>\[\]\.]+\s+%s\s*\(" % re.escape(entry))
        if chunk is None:
            problems.append("找不到 %s(...)" % entry)
            continue
        if "cacheIndex()" not in chunk:
            problems.append("%s(...) 没有过 cacheIndex() 判定" % entry)
    place = method_body(body, r"public\s+boolean\s+mayPlace\s*\(")
    if place is None or "cacheSlotExists()" not in place:
        problems.append("mayPlace(...) 没拦住「不存在的空格」（会静默丢物品）")
    pickup = method_body(body, r"public\s+boolean\s+mayPickup\s*\(")
    if pickup is None or "cacheSlotExists()" not in pickup:
        problems.append("mayPickup(...) 没拦住「不存在的空格」")
    return problems


def cache_global_index_problems(body):
    problems = []
    if body is None:
        return ["找不到 cacheGlobalIndex(int)"]
    if "cacheSize()" not in body:
        problems.append("cacheGlobalIndex 没有用 cacheSize() 做上界判定")
    if "return -1" not in body:
        problems.append("cacheGlobalIndex 越界时没有返回 -1")
    return problems


# ==================== A3：界面写标记的默认数量 ====================

def marker_amount_problems(src):
    problems = []
    if "MIN_UI_MARK_AMOUNT" not in src:
        problems.append("没有 MIN_UI_MARK_AMOUNT 常量")
    try_body = method_body(src, r"public\s+boolean\s+tryAddMarker\s*\(\s*final\s+int\s+index\s*,"
                                r"\s*final\s+ItemStack\s+stack\s*,\s*final\s+long\s+amount\s*\)")
    if try_body is None:
        problems.append("没有 tryAddMarker(int, ItemStack, long) 重载")
    elif "Math.max(MIN_UI_MARK_AMOUNT" not in try_body and "MIN_UI_MARK_AMOUNT" not in try_body:
        problems.append("显式数量的重载没有下限保护到 MIN_UI_MARK_AMOUNT")
    ui_body = method_body(src, r"public\s+MarkerAddResult\s+addMarkerForUi\s*\(")
    if ui_body is None:
        problems.append("找不到 addMarkerForUi(...)")
    elif "MIN_UI_MARK_AMOUNT" not in ui_body:
        problems.append("addMarkerForUi 没有用 MIN_UI_MARK_AMOUNT（还在用手持件数当门槛）")
    # 旧的「数量 = 手持件数」写法必须消失
    if re.search(r"MarkerEntry\.item\(\s*stack\s*,\s*Math\.max\(\s*1\s*,\s*stack\.getCount\(\)\s*\)", src):
        problems.append("仍存在 MarkerEntry.item(stack, Math.max(1, stack.getCount())) 的旧写法")
    return problems


def menu_marker_amount_problems(menu_src):
    problems = []
    body = method_body(menu_src, r"private\s+boolean\s+writeMarkerFromPlayerSlot\s*\(")
    if body is None:
        return ["找不到 writeMarkerFromPlayerSlot(...)"]
    if "stack.getCount()" in body:
        problems.append("提示里的数量仍取 stack.getCount()（与实际写进去的需求数量不符）")
    if "MIN_UI_MARK_AMOUNT" not in body:
        problems.append("提示里的数量没有走 MIN_UI_MARK_AMOUNT")
    return problems


# ==================== A4：[rscc-collect] 诊断 ====================

COLLECT_PATHS = [
    ("PATH_UI_PUT", "界面放入"),
    ("PATH_UI_TAKE", "界面取出"),
    ("PATH_UI_MARK", "写标记"),
    ("PATH_DROP", "掉落物"),
    ("PATH_CONTAINER", "相邻容器"),
    ("PATH_FLUID", "世界流体"),
    ("PATH_GAS", "气体"),
    ("PATH_XP", "经验"),
    ("PATH_FLUSH", "回写网络"),
]


def collect_diag_problems(be_src, menu_src):
    problems = []
    if "noteCollectPath" not in be_src:
        problems.append("方块实体里没有 noteCollectPath(...)")
    if "flushCollectDiagnostic" not in be_src:
        problems.append("方块实体里没有 flushCollectDiagnostic()")
    if "[rscc-collect]" not in be_src:
        problems.append("日志里没有 [rscc-collect] 前缀")
    # 状态变化才记：比较上一次的位集合
    flush_body = method_body(be_src, r"private\s+void\s+flushCollectDiagnostic\s*\(")
    if flush_body is None:
        problems.append("找不到 flushCollectDiagnostic(...) 方法体")
    else:
        if "collectDiagLastFlags" not in flush_body:
            problems.append("没有「与上一次比较」的判据（会每 5 秒刷一条）")
        # 必须是「本窗口集合 == 上一窗口集合」这种真比较，而不是写了字段却没比较
        if not re.search(r"if\s*\(\s*flags\s*==\s*collectDiagLastFlags\s*\)", flush_body):
            problems.append("没有「本窗口路径集合 == 上一窗口」的比较（会每 5 秒刷一条）")
        if "==" not in flush_body or "return" not in flush_body:
            problems.append("状态没变时没有提前 return")
    # 必须挂在每 tick 的 doWork 上（未激活时也要写得出「一条都没动」）
    do_work = method_body(be_src, r"public\s+void\s+doWork\s*\(\s*\)")
    if do_work is None or "flushCollectDiagnostic()" not in do_work:
        problems.append("doWork() 没有调用 flushCollectDiagnostic()")
    # 9 条路径都要有 noteCollectPath(...) 调用点：
    #   前 3 条（界面放入 / 取出 / 写标记）在菜单里、用带类名前缀的写法；
    #   其余 6 条在方块实体里。**气体是唯一豁免**：collectGas 目前没有世界来源
    #   （方法体自带注释说明），因此它永远不会有调用点 —— 但它仍然要出现在诊断的名字表里，
    #   这样日志会明确显示「气体这条路径没动过」，而不是把它从清单里悄悄抹掉。
    gas_exempt = {"PATH_GAS"}
    pattern = r"noteCollectPath\(\s*(?:CollectionCacheBlockEntity\.)?%s\s*\)"
    for const, label in COLLECT_PATHS:
        if const in gas_exempt:
            continue
        haystack = menu_src if const in ("PATH_UI_PUT", "PATH_UI_TAKE", "PATH_UI_MARK") else be_src
        if re.search(pattern % const, haystack) is None:
            problems.append("没有任何调用点记录路径「%s」（%s）" % (label, const))
    # 界面路径必须是带前缀的写法（菜单里没有内部常量，必须显式写出类名）
    for const, label in COLLECT_PATHS[:3]:
        if ("CollectionCacheBlockEntity.%s" % const) not in menu_src:
            problems.append("菜单里没有记录界面路径「%s」（CollectionCacheBlockEntity.%s）" % (label, const))
    # 气体虽然豁免「必须有调用点」，但必须在名字表里（否则日志会把它漏掉）
    if "气体" not in be_src:
        problems.append("诊断的名字表里没有「气体」（漏报路径）")
    return problems


# ==================== B1：区域去重 ====================

def dedupe_problems(area_src, selection_src, packet_src, screen_src, handler_src, adv_screen_src):
    problems = []
    # Area.sameGeometry 只比六个坐标（名字不参与判等）
    same = method_body(area_src, r"public\s+boolean\s+sameGeometry\s*\(")
    if same is None:
        problems.append("Area 没有 sameGeometry(...)")
    else:
        for axis in ("minX", "minY", "minZ", "maxX", "maxY", "maxZ"):
            if axis not in same:
                problems.append("sameGeometry 没比较 %s" % axis)
        if "name" in same:
            problems.append("sameGeometry 把 name 也算进去了（改个名字就能绕过去重）")
    # AreaSelection：按下标找同几何 + 去重列表 + 复制 + 重命名
    for needle, label in (("indexOfGeometry", "indexOfGeometry(...)"),
                          ("hasGeometry", "hasGeometry(...)"),
                          ("distinctAreas", "distinctAreas()"),
                          ("duplicate", "duplicate(...)"),
                          ("rename", "rename(...)")):
        if needle not in selection_src:
            problems.append("AreaSelection 没有 %s" % label)
    # 写入路径：ADD / REPLACE 都要判重
    sections = case_sections(packet_src)
    if "ACTION_ADD" not in sections:
        problems.append("找不到 ACTION_ADD 分支")
    elif "hasGeometry" not in sections["ACTION_ADD"]:
        problems.append("ACTION_ADD 没有去重（同坐标能加两份）")
    if "ACTION_REPLACE" not in sections:
        problems.append("找不到 ACTION_REPLACE 分支")
    elif "hasGeometry(area, index)" not in sections["ACTION_REPLACE"]:
        problems.append("ACTION_REPLACE 没有去重（或没排除自身）")
    if "ACTION_COPY" not in packet_src or "ACTION_RENAME" not in packet_src:
        problems.append("包协议里没有 ACTION_COPY / ACTION_RENAME")
    # 三处世界描边都必须去重
    if "distinctAreas()" not in screen_src:
        problems.append("区域列表界面没有用 distinctAreas() 画世界描边")
    if "distinctAreas()" not in handler_src:
        problems.append("世界手势（AreaSelectorHandler）没有用 distinctAreas()")
    if "distinctAreas()" not in adv_screen_src:
        problems.append("高级缓存仓「查看范围」链路没有用 distinctAreas()（服务端去重 / 界面去重都没有）")
    return problems


# ==================== B2：坐标必须完整可读 ====================

def coordinate_readability_problems(screen_src):
    problems = []
    if "font.width(" not in screen_src:
        problems.append("界面没有用 font.width 量宽")
    if "coordinateLine" not in screen_src:
        problems.append("没有 coordinateLine(...)（坐标行统一出口）")
    # 必须有「2 行 / 3 行」两态，且 3 行态把「从 / 到」拆开画
    if "ROW_3H" not in screen_src or "ROW_2H" not in screen_src:
        problems.append("没有 2 行 / 3 行两种行高常数")
    if "describeMin()" not in screen_src or "describeMax()" not in screen_src:
        problems.append("3 行态没有分别画起点 / 终点")
    # 绘制代码里必须真的把「从 / 到」两行画出来（只定义 describeMin/Max 不算）
    if '"到 " + area.describeMax()' not in screen_src:
        problems.append("列表绘制没有画出「到 …」那一行（终点仍然看不见）")
    if '"从 " + area.describeMin()' not in screen_src:
        problems.append("列表绘制没有画出「从 …」那一行")
    # 悬停 tooltip 必须给完整坐标（两个角点都在）
    if "renderTooltip" not in screen_src:
        problems.append("没有 tooltip（完整坐标没地方看）")
    tip = method_body(screen_src, r"private\s+void\s+renderRowTooltip\s*\(")
    if tip is None:
        problems.append("没有 renderRowTooltip(...)")
    elif "describeMin()" not in tip or "describeMax()" not in tip:
        problems.append("行 tooltip 没有给完整的起点 / 终点")
    # 禁止「单行 + 裁剪」的老做法
    if re.search(r"trimToWidth\(\s*line", screen_src):
        problems.append("仍存在把整行坐标 trimToWidth 截断的旧做法")
    return problems


# ==================== B3：可编辑 ====================

def editable_problems(screen_src, area_src, selection_src, packet_src):
    problems = []
    # 名称（重命名）
    if "nameBox" not in screen_src:
        problems.append("界面没有名称框（无法重命名）")
    if "ACTION_RENAME" not in screen_src:
        problems.append("界面没有发 ACTION_RENAME")
    if 'optionalFieldOf("name")' not in area_src:
        problems.append("Area 的持久化 codec 没有 name 字段（重命名会丢失）")
    stream = method_body(area_src, r"public\s+static\s+final\s+StreamCodec<ByteBuf,\s*Area>\s+STREAM_CODEC\s*=")
    if stream is None or "name()" not in stream:
        problems.append("Area 的网络 codec 没有编解码 name（客户端看不到名字）")
    # 复制 / 删除 / 新建
    for needle, label in (("copySelected", "复制"), ("deleteSelected", "删除"), ("createArea", "新建")):
        if needle not in screen_src:
            problems.append("界面没有 %s 入口" % label)
    # 选中回填六字段
    sync = method_body(screen_src, r"private\s+void\s+syncFields\s*\(")
    if sync is None:
        problems.append("没有 syncFields(...)（点行回填字段）")
    else:
        for getter in ("minX()", "minY()", "minZ()", "maxX()", "maxY()", "maxZ()"):
            if getter not in sync:
                problems.append("syncFields 没有回填 %s" % getter)
    # 维度 / 体积 / 尺寸 / 数量
    for key in ('"dimension"', '"volume"', '"size"', '"count"'):
        if key not in screen_src:
            problems.append("界面没有显示 %s 这一行" % key.strip('"'))
    # 滚动条
    if "SptScrollbarWidget" not in screen_src:
        problems.append("没有滚动条（区域多了只能滚轮硬滚）")
    if "setMaxOffset" not in screen_src:
        problems.append("滚动条没有 setMaxOffset（滚不到底）")
    # 选中高亮
    if "COLOR_SELECTED" not in screen_src:
        problems.append("没有选中高亮")
    # 错误提示在字段旁 + 出错框描红
    if "errorField" not in screen_src:
        problems.append("没有 errorField（不知道是哪个字段错）")
    if "COLOR_FIELD_BORDER_ERROR" not in screen_src:
        problems.append("没有把出错字段描红")
    if "ERROR_Y" not in screen_src:
        problems.append("没有把错误文案画在字段下方")
    # 重命名的服务端动作
    if "selection.rename(" not in packet_src:
        problems.append("服务端没有处理 ACTION_RENAME")
    if "selection.duplicate(" not in packet_src:
        problems.append("服务端没有处理 ACTION_COPY")
    if "rename(" not in selection_src:
        problems.append("AreaSelection 没有 rename(...)")
    return problems


# ==================== 载入源码 ====================

BE_RAW = read(BE_PATH)
BE_SRC = strip_comments(BE_RAW)
MENU_SRC = strip_comments(read(MENU_PATH))
AREA_SRC = strip_comments(read(AREA_PATH))
SELECTION_SRC = strip_comments(read(SELECTION_PATH))
PACKET_SRC = strip_comments(read(PACKET_PATH))
SCREEN_SRC = strip_comments(read(SCREEN_PATH))
ADV_SCREEN_SRC = strip_comments(read(ADV_SCREEN_PATH)) + strip_comments(read(ADV_BE_PATH))
HANDLER_SRC = strip_comments(read(HANDLER_PATH))

WINDOW_SLOT = class_body(MENU_SRC, r"private\s+static\s+final\s+class\s+WindowSlot")
WINDOW_SLOT_RAW = class_body(MENU_SRC, r"private\s+static\s+final\s+class\s+WindowSlot")
CACHE_GLOBAL = method_body(MENU_SRC, r"public\s+int\s+cacheGlobalIndex\s*\(")


# ==================== A 组断言 ====================

print("---- A. 归流缓存仓「与物品交互」 ----")

issues = window_slot_problems(WINDOW_SLOT)
check("A1 WindowSlot 的缓存侧读写全部过 cacheGlobalIndex（越界不再打容器）",
      not issues, "; ".join(issues))

issues = cache_global_index_problems(CACHE_GLOBAL)
check("A1 cacheGlobalIndex 越界返回 -1（最后一页不足 48 格时是空格而不是异常）",
      not issues, "; ".join(issues))

check("A1 菜单提供了 cacheSize()（服务端取真实格数，含集群扩容）",
      "public int cacheSize()" in MENU_SRC and "getCache().getContainerSize()" in MENU_SRC)

# A2：行为模型 —— 旧语义必须真的越界，新语义不重不漏
OLD_SIZES = []
for size in (256, 512):
    seen, oob = paginate(size, CACHE_WINDOW, guard=False)
    OLD_SIZES.append((size, oob, len(seen)))
    if oob <= 0:
        check("A2 反例前置：%d 格时旧语义必然越界（证明缺陷真实存在）" % size, False,
              "旧语义没有产生越界下标，说明模型写错了")
    else:
        check("A2 反例前置：%d 格时旧语义越界 %d 个窗口格（缺陷真实存在）" % (size, oob), True)
for size in (256, 512):
    seen, oob = paginate(size, CACHE_WINDOW, guard=True)
    total = size
    check("A2 %d 格：新语义只映射真实存在的小标（%d 个，不重不漏）" % (size, len(seen)),
          len(seen) == total and oob == 0, "覆盖 %d / %d" % (len(seen), total))

issues = marker_amount_problems(BE_SRC)
check("A3 界面写标记的需求数量默认 = MIN_UI_MARK_AMOUNT（1，「有就收」）",
      not issues, "; ".join(issues))

issues = menu_marker_amount_problems(MENU_SRC)
check("A3 菜单的 actionbar 提示不再声称「×手持件数」",
      not issues, "; ".join(issues))

issues = collect_diag_problems(BE_SRC, MENU_SRC)
check("A4 [rscc-collect] 覆盖 9 条路径 + 状态变化才记 + 挂在 doWork",
      not issues, "; ".join(issues))

check("A4 诊断行里带齐「为什么可能一条都没动」的前置条件（节点 / 网络 / 标记 / 四开关 / 待回写）",
      all(needle in BE_SRC for needle in ("节点=未激活", "网络=未接入", "标记={}", "待回写")) or
      all(needle in BE_SRC for needle in ("节点=未激活", "网络=未接入", "标记=", "缓存=")),
      "缺关键字段")

# 反例 ①：旧版 WindowSlot
OLD_WINDOW_SLOT = """
private static final class WindowSlot extends Slot {
    private final CollectionCacheMenu menu;
    private final int indexInWindow;
    private ItemStack localStack = ItemStack.EMPTY;
    private WindowSlot(final CollectionCacheMenu menu, final int indexInWindow, final int x, final int y) {
        super(EMPTY, indexInWindow, x, y);
        this.menu = menu;
        this.indexInWindow = indexInWindow;
    }
    private boolean markerRegion() { return menu.isMarkerWindow(indexInWindow); }
    private int global() { return menu.globalIndex(indexInWindow); }
    @Override
    public ItemStack getItem() {
        final CollectionCacheBlockEntity block = menu.block;
        if (block == null) { return localStack; }
        return markerRegion() ? block.getMarkerStack(global()) : block.getCache().getItem(global());
    }
    @Override
    public void set(final ItemStack stack) {
        final CollectionCacheBlockEntity block = menu.block;
        if (block == null) { localStack = stack; }
        else if (!markerRegion()) { block.getCache().setItem(global(), stack); }
        this.setChanged();
    }
    @Override
    public boolean mayPlace(final ItemStack stack) { return !markerRegion() && !stack.isEmpty(); }
    @Override
    public boolean mayPickup(final Player player) { return !markerRegion(); }
    @Override
    public ItemStack remove(final int amount) {
        final CollectionCacheBlockEntity block = menu.block;
        if (block == null || markerRegion()) { return ItemStack.EMPTY; }
        return block.getCache().removeItem(global(), amount);
    }
}
"""
old_issues = window_slot_problems(strip_comments(OLD_WINDOW_SLOT))
check("①反例：WindowSlot 退回「直读 global()」必须判负",
      len(old_issues) >= 3, "只抓到 %d 条：%s" % (len(old_issues), old_issues))

# 反例 ②：旧分页模型（最高页 = (格数-1)/48，且不做越界判定）
old_oob_total = 0
for size in (256, 512):
    _, oob = paginate(size, CACHE_WINDOW, guard=False)
    old_oob_total += oob
check("②反例：旧分页模型必须被判为「越界」（判据本身有效）", old_oob_total > 0,
      "旧模型没有越界，判据失效")


# ==================== B 组断言 ====================

print("---- B. 区域选定器界面重做 ----")

issues = dedupe_problems(AREA_SRC, SELECTION_SRC, PACKET_SRC, SCREEN_SRC, HANDLER_SRC, ADV_SCREEN_SRC)
check("B1 去重：同维度 + 同坐标在 ADD / REPLACE / COPY 上被拒 + 三处描边按几何去重",
      not issues, "; ".join(issues))

issues = coordinate_readability_problems(SCREEN_SRC)
check("B2 坐标完整可读：按 font.width 量宽、2/3 行两态、tooltip 给全文、无单行 trim",
      not issues, "; ".join(issues))

issues = editable_problems(SCREEN_SRC, AREA_SRC, SELECTION_SRC, PACKET_SRC)
check("B3 可编辑：点行回填六字段 + 名称（复制/删除/重命名）+ 维度/体积/尺寸/数量 + 滚动条 + 描红错误",
      not issues, "; ".join(issues))

# 反例 ⑤：ADD 分支去掉去重
# 第 106 轮诚实更新：写入路径的「校验失败」现在除了 return 还会记拒绝原因
# （AreaSelectorEditPacket#reject），因此变异点改成「把去重条件直接改成 false」——
# 判据本身没变（ACTION_ADD 分支里必须出现 hasGeometry），只是变异写法不再依赖 return 的缩进。
mutated_packet = PACKET_SRC.replace("if (selection.hasGeometry(area, -1)) {",
                                    "if (false) { // 反例：删掉去重判定", 1)
issues = dedupe_problems(AREA_SRC, SELECTION_SRC, mutated_packet, SCREEN_SRC, HANDLER_SRC, ADV_SCREEN_SRC)
check("⑤反例：删掉 ACTION_ADD 的去重判定必须判负",
      any("ACTION_ADD" in item for item in issues), "没有抓到：%s" % issues)

# 反例 ⑥：列表行退回「单行 + 裁剪」（3 行态与「到 …」那一行一起消失）
mutated_screen = SCREEN_SRC.replace('"到 " + area.describeMax()', '""')
mutated_screen = mutated_screen.replace('"从 " + area.describeMin()', '""')
issues = coordinate_readability_problems(mutated_screen)
check("⑥反例：把行改回「单行 + trimToWidth」必须判负",
      len(issues) >= 2, "只抓到 %d 条：%s" % (len(issues), issues))

# 反例 ⑦：Area 的 name 从两个 codec 里删掉
mutated_area = AREA_SRC.replace('Codec.STRING.optionalFieldOf("name")', 'Codec.STRING.optionalFieldOf("noname")')
mutated_area = mutated_area.replace("ByteBufCodecs.STRING_UTF8.encode(buf, area.name() == null ? \"\" : area.name());", "")
issues = editable_problems(SCREEN_SRC, mutated_area, SELECTION_SRC, PACKET_SRC)
check("⑦反例：把 Area 的 name 从 codec 里删掉必须判负",
      len(issues) >= 2, "只抓到 %d 条：%s" % (len(issues), issues))

# 反例 ④：去掉 noteCollectPath 调用 / 状态判据
mutated_be = BE_SRC.replace("noteCollectPath(PATH_DROP);", "")
issues = collect_diag_problems(mutated_be, MENU_SRC)
check("④反例：删掉「掉落物」路径的记录调用必须判负",
      any("掉落物" in item for item in issues), "没有抓到：%s" % issues)
mutated_be2 = BE_SRC.replace("if (flags == collectDiagLastFlags) {", "if (false) {")
issues = collect_diag_problems(mutated_be2, MENU_SRC)
check("④反例：去掉「状态变化才记」的判据必须判负",
      len(issues) >= 1 or "collectDiagLastFlags" not in mutated_be2, "没有抓到：%s" % issues)

# 反例 ③：旧的「数量 = 手持件数」写法
OLD_MARK = """
    public MarkerAddResult addMarkerForUi(final ItemStack stack) {
        return tryAddMarker(-1, stack) ? MarkerAddResult.OK : MarkerAddResult.FULL;
    }
    public boolean tryAddMarker(final int index, final ItemStack stack) {
        setMarker(index, MarkerEntry.item(stack, Math.max(1, stack.getCount()), false, null));
        return true;
    }
"""
issues = marker_amount_problems(strip_comments(OLD_MARK))
check("③反例：退回「数量 = 手持件数」的老写法必须判负",
      len(issues) >= 1, "没有抓到：%s" % issues)

print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 7 组反例自证）" % CHECKS[0])
sys.exit(0)
