#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 96 轮 WS-D 自检：请求器（发送器）= 「网格 + 订单清单」主界面。

判定口径来自 `docs/ROUND96_USER_FEEDBACK.md` 第 1 节 #5 与第 3 节：

    「发送器他就不要用那种额外的东西，就是网格，然后打开网格之后旁边就有一个清单，
      会显示要发送什么，然后可以方便地搜索已经加了什么，然后可以删除，然后可以更改数量……
      反正一打开就是网格界面」

固化的不变式（只读源码，不启动游戏）：

  ① **右键请求器 = 直接开网格**：方块 `useWithoutItem` 只调菜单的 `openPickerFor`
     （判据仍只有 `ResourcePickerMenu#openFor` 一处）；打不开时**必须**给一句 actionbar 提示
     并退回设置界面 —— 绝不「点了没反应」，也绝不让一台没接网络的机器变成无法配置的死锁。
  ② **一盘棋的判据**：`openResourcePicker`（旧的「网格」按钮路径）也必须走同一个 `openPickerFor`，
     否则两条入口会各有一套连通性判据。
  ③ **网格顶部工具条**：目标接收器循环切换 / 立即发送 / 清空订单 / 速率文字 / 「设置」。
     每个动作都只发 C2S 请求，失败原因由服务端快照回话。
  ④ **右侧订单清单**：可滚动、条目数无上限（不再有 ORDER_COLS/ORDER_ROWS/ORDER_VISIBLE 这类
     人为格子数）、**自带搜索框**（只过滤已加入条目，大小写不敏感，匹配名称 / 资源 id）、
     行尾 × 删除、单行改数量（回车 / −/+，走既有 C2S 包 + 服务端校验）。
  ⑤ **点击语义与索引**：可见行是「权威下标表」的投影；删除 / 改数量发出去的下标必须是
     **权威清单下标**（`rows` 的下标），不是可见行号 —— 过滤状态下用可见行号会删错条目。
  ⑥ **客户端只读**：不写世界、不查能力、不直接调方块实体的写入口；所有变更都是
     `PacketDistributor.sendToServer`。
  ⑦ **设置小面板退位**：4×2 = 8 个订单格（人为上限）删掉，改成一行摘要
     「订单 N 种 / 共 M 件」+ 大按钮「打开网格编辑订单」；未接网络时按钮**不变灰**，
     点它必须给一句原因（灰按钮本身就是「点了没反应」）。
  ⑧ **速率数值来自服务端**：网格菜单与请求器菜单是两张菜单，`GridData` 不带 `ContainerData`，
     因此快照必须把 `pacingBatchesPerTick` / `pacingBatch` 编码 / 解码 / 由服务端 getter 填好，
     界面只读不算。
  ⑨ **语言键**：本轮新增键必须中英齐全，且不得含 round86 已禁的措辞（订单清单 / 节流 / 16）。
  ⑩ **反例自证**：把每条修复逐一回退，对应断言必须翻红；任何一条回退抓不到即判负。

用法：python tools/selfcheck_round96_requester_ui.py
退出码：0 = 全部 PASS。
"""
import io
import json
import os
import re
import sys

# 本机控制台代码页是 GBK：断言消息里有 ⇒ / — 这类字符时直接 print 会 UnicodeEncodeError。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "src/main/java/cretae/cookiewyq/rs_create_compat"

FILES = {
    "block_q": PKG + "/block/NetworkResourceRequesterBlock.java",
    "menu_q": PKG + "/menu/NetworkResourceRequesterMenu.java",
    "pkt_order": PKG + "/network/RequesterOrderPacket.java",
    "pkt_sync": PKG + "/network/SyncRequesterConfigPacket.java",
    # 第 131 轮 C 组：请求器设置界面整页删除 ⇒ 变成**反向门禁**（见 GONE_FILES）
    "scr_grid": PKG + "/client/grid/ResourcePickerScreen.java",
    "menu_grid": PKG + "/client/grid/ResourcePickerMenu.java",
    # 第 131 轮新增：目标选择子界面 / 缺口询问子界面 / 发送源三层抽象
    "scr_target": PKG + "/client/screen/SenderTargetSelectScreen.java",
    "scr_prompt": PKG + "/client/screen/SenderCraftPromptScreen.java",
    "sender_backend": PKG + "/support/SenderBackend.java",
    "req_backend": PKG + "/support/RequesterSenderBackend.java",
}

# 第 131 轮 C 组：必须**不存在**的文件（设置界面整页删除 + 它的唯一发包通道）
GONE_FILES = {
    "请求器设置界面": PKG + "/client/screen/NetworkResourceRequesterScreen.java",
    "「打开网格」死通道包": PKG + "/network/OpenRequesterPickerPacket.java",
}
LANG_FILES = {
    "zh": "src/main/resources/assets/rs_create_compat/lang/zh_cn.json",
    "en": "src/main/resources/assets/rs_create_compat/lang/en_us.json",
}

QP = "gui.rs_create_compat.network_resource_requester."
RP = "gui.rs_create_compat.resource_picker."

# ---------------------------------------------------------------------------
# 本轮新增语言键（由 Lead 统一写入 lang/*.json；中英原文见回报）
# ---------------------------------------------------------------------------
# 第 131 轮 C 组：设置面板的 7 条键（order.summary / open_grid.*）随界面删除；
# 「工具栏设置」两条（toolbar.settings / .hint）也删；新增目标子界面 + 缺口询问两个界面的键。
NEW_LANG_KEYS = [
    # 网格界面：工具条
    RP + "msg.open_failed",
    RP + "toolbar.rate", RP + "toolbar.target.hint", RP + "toolbar.send.hint",
    # 网格界面：订单清单
    RP + "order.search", RP + "order.search.hint", RP + "order.empty",
    RP + "order.no_match", RP + "order.filtered", RP + "order.delete.hint",
    RP + "order.amount.hint", RP + "order.amount.step.item", RP + "order.amount.step.fluid",
    RP + "msg.select_first", RP + "msg.bad_amount",
    # 目标选择子界面（第 131 轮新增）
    "gui.rs_create_compat.sender_target_select.title",
    "gui.rs_create_compat.sender_target_select.current",
    "gui.rs_create_compat.sender_target_select.current.none",
    "gui.rs_create_compat.sender_target_select.empty",
    "gui.rs_create_compat.sender_target_select.hint",
    "gui.rs_create_compat.sender_target_select.search_hint",
    "gui.rs_create_compat.sender_target_select.search.tip",
    "gui.rs_create_compat.sender_target_select.confirm",
    "gui.rs_create_compat.sender_target_select.confirm.tip",
    "gui.rs_create_compat.sender_target_select.cancel",
    "gui.rs_create_compat.sender_target_select.cancel.tip",
    # 缺口询问子界面（第 131 轮 F 组新增）
    "gui.rs_create_compat.sender_craft_prompt.title",
    "gui.rs_create_compat.sender_craft_prompt.line",
    "gui.rs_create_compat.sender_craft_prompt.craft",
    "gui.rs_create_compat.sender_craft_prompt.craft.tip",
    "gui.rs_create_compat.sender_craft_prompt.send_only",
    "gui.rs_create_compat.sender_craft_prompt.send_only.tip",
]

# 第 131 轮 C 组：这些键**必须不存在**（设置界面删了，键也删了；留着就是没人用的死文案）
# 只列**已由 Lead 删除**的键（第 131 轮 lang 收尾时删的就是这两条）。
# 注意：`network_resource_requester.order.summary*` / `order.open_grid*` 这 7 条设置面板键
# **目前仍留在 lang 里**（Lead 的写域，本轮只删了 resource_picker 的两条）⇒ 已作为死文案回报，
# 不在本脚本里判负（判了会变成「脚本要求 Lead 改文件」）。
GONE_LANG_KEYS = [
    RP + "toolbar.settings",
    RP + "toolbar.settings.hint",
]

# round86 已禁的玩家可见措辞（新键的值不得再带回来）
FORBIDDEN_ZH = ("订单清单", "订单上限", "节流", "上限 16", "16 条", "最多 16")
FORBIDDEN_EN = ("order list", "pacing", "max 16", "16 entries", "16 items")

CHECKS = []


def check(cid, desc):
    def decorate(fn):
        CHECKS.append((cid, desc, fn))
        return fn
    return decorate


def read_text(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """把 Java 注释换成等长空白（行号不变）。判「代码里有没有某写法」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:j]))
            i = j
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def has(text, pattern, flags=re.S):
    return re.search(pattern, text, flags) is not None


def body(text, signature):
    """取方法体（从签名后的第一个 { 到配对的 }）。找不到 ⇒ None。"""
    match = re.search(signature, text)
    if match is None:
        return None
    index = text.find("{", match.end() - 1)
    if index < 0:
        return None
    depth = 0
    for j in range(index, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[index + 1:j]
    return None


def line_of(text, pattern):
    match = re.search(pattern, text, re.S)
    return -1 if match is None else text.count("\n", 0, match.start()) + 1


def at(rel, pattern, src):
    return "%s:%s" % (rel, line_of(src[rel], pattern))


# ======================================================================================
# ① 右键 = 网格（判据只有一处，失败必须给提示 + 退回设置界面）
# ======================================================================================
@check("A1", "方块 useWithoutItem：只调 openPickerFor（判据单一来源），成功即开网格")
def check_a1(src, zh, en):
    b = body(src["block_q"], r"protected InteractionResult useWithoutItem\(")
    if b is None:
        return False, "找不到 NetworkResourceRequesterBlock#useWithoutItem"
    if not has(b, r"NetworkResourceRequesterMenu\.openPickerFor\(serverPlayer, requester\)"):
        return False, "useWithoutItem 没有走 openPickerFor（右键没进网格）"
    if has(b, r"isConnectedToNetwork\(") or has(b, r"ResourcePickerMenu"):
        return False, ("方块层自己写了连通性判据 / 直接引用网格菜单（判据会分叉），%s"
                       % at("block_q", r"useWithoutItem", src))
    return True, "只调菜单的 openPickerFor"


@check("A2", "打不开网格时：两种原因各有 actionbar（没接网络 / 这次没开成），不是点了没反应")
def check_a2(src, zh, en):
    b = body(src["block_q"], r"protected InteractionResult useWithoutItem\(")
    if b is None:
        return False, "找不到 useWithoutItem"
    missing = [name for name, pattern in {
        "没接网络的原因": r"NetworkResourceRequesterMenu\.MSG_PICKER_NO_NETWORK",
        "openMenu 被拒的原因": r"NetworkResourceRequesterMenu\.MSG_PICKER_OPEN_FAILED",
        "actionbar": r"displayClientMessage\(",
    }.items() if not has(b, pattern)]
    if missing:
        return False, "useWithoutItem 缺少：" + "、".join(missing)
    if not has(b, r"isPickerConnected\(requester\)"):
        return False, "「为什么没打开」没有走统一的连通性判据（isPickerConnected）"
    # 第 131 轮反转：设置界面已整页删除 ⇒ 不再断言「退回设置界面 + 它的 openMenu 判空」。
    # 现在唯一的配置入口是网格；打不开时只有 actionbar（不能静默）。
    if has(b, r"openSettingsFor\("):
        return False, "打不开时还在往已删除的设置界面跳（openSettingsFor 已不存在）"
    return True, "两种失败原因各有 actionbar；配置入口只剩网格"


@check("A3", "唯一打开入口：菜单 openPickerFor → ResourcePickerMenu#openFor；设置面板那条入口已删")
def check_a3(src, zh, en):
    menu = src["menu_q"]
    wrapper = body(menu, r"public static boolean openPickerFor\(")
    if wrapper is None:
        return False, "找不到 openPickerFor"
    if not has(wrapper, r"ResourcePickerMenu\s*\n?\s*\.openFor\("):
        return False, "openPickerFor 没有委托给 ResourcePickerMenu#openFor（判据会分叉）"
    if not has(menu, r"MSG_PICKER_NO_NETWORK"):
        return False, "打不开时没有回提示"
    # 第 131 轮反转：只有「右键 → 网格」一条入口；旧「设置面板里的打开网格按钮」整条删除
    banned = [name for name, pattern in {
        "旧打开网格入口": r"public void openResourcePicker\(",
        "设置界面入口": r"openSettingsFor\(",
        "旧包通道": r"OpenRequesterPickerPacket",
        "设置动作码": r"ACTION_OPEN_SETTINGS",
    }.items() if has(menu, pattern) or has(src["pkt_order"], pattern)]
    if banned:
        return False, "旧的第二条入口还在：" + "、".join(banned)
    return True, "只有 openPickerFor → openFor 一条路径"


@check("A4", "设置界面的服务端入口已删除（openSettingsFor / 「设置」按钮 / 切界面动作码全无）")
def check_a4(src, zh, en):
    problems = []
    if has(src["menu_q"], r"public static void openSettingsFor\("):
        problems.append("menu_q 还留着 openSettingsFor")
    if has(src["pkt_order"], r"ACTION_OPEN_SETTINGS"):
        problems.append("RequesterOrderPacket 还留着 ACTION_OPEN_SETTINGS")
    if has(src["scr_grid"], r"settingsButton"):
        problems.append("网格工具条还留着「设置」按钮")
    if problems:
        return False, "；".join(problems)
    # 正向项：设置界面删了，但右键必须仍然进网格（否则玩家彻底失去配置入口）
    b = body(src["block_q"], r"protected InteractionResult useWithoutItem\(") or ""
    if "openPickerFor" not in b:
        return False, "设置界面删了，右键也没进网格（玩家没了配置入口）"
    return True, "三条痕迹全无；右键 → 网格唯一入口仍在"


@check("A5", "动作码只剩 4 个（增 / 删 / 改 / 清空）：设置界面删除后不再有「切界面」动作")
def check_a5(src, zh, en):
    pkt = src["pkt_order"]
    for token, value in (("ACTION_ADD", 0), ("ACTION_REMOVE", 1),
                         ("ACTION_SET_AMOUNT", 2), ("ACTION_CLEAR", 3)):
        if not has(pkt, r"%s\s*=\s*%d\s*;" % (token, value)):
            return False, "动作码 %s 不在（协议形状变了）" % token
    if has(pkt, r"ACTION_OPEN_SETTINGS|ACTION_[A-Z_]+\s*=\s*4\s*;"):
        return False, "又出现第 5 个动作码（设置界面已删，切界面通道不该存在）"
    return True, "动作码 0..3，无「切界面」动作"


@check("A6", "三种边界（未连网络 / 目标未设 / 方块被拆）都提示清楚且不会把机器锁死")
def check_a6(src, zh, en):
    problems = []
    # ① 未连网络：网格打不开 **不等于** 机器不可配置 —— 必须退回设置面板 + 一句原因，
    #    否则一台刚放下、还没接网络的请求器永远没法命名目标（死锁）。
    # ① 第 131 轮反转：设置界面已删除 ⇒ 不再要求「退回设置界面」；但「打不开必须有提示」仍在。
    block = body(src["block_q"], r"protected InteractionResult useWithoutItem\(") or ""
    if "displayClientMessage" not in block:
        problems.append("没接网络时界面打不开又没有提示（点了没反应）")
    if "openSettingsFor" in block or "openSettingsFor" in src["menu_q"]:
        problems.append("还在往已删除的设置界面跳（openSettingsFor）")
    # ② 目标未设定：网格仍要能开（不能把 isProperlyConfigured 当打开门槛，否则玩家没法先加订单）；
    #    「立即发送」由服务端回 MSG_TRIGGER_UNCONFIGURED，客户端不许自己静默 return。
    open_for = body(src["menu_grid"], r"public static boolean openFor\(") or ""
    if "isProperlyConfigured" in open_for:
        problems.append("网格打开被「目标未设定」挡掉（玩家无法先加订单）")
    # 第 131 轮：提示常量搬到发送源层（RequesterSenderBackend），不再是菜单静态字段
    if "MSG_TRIGGER_UNCONFIGURED" not in src["req_backend"]:
        problems.append("目标未设定时发送没有明确提示")
    send_now = body(src["scr_grid"], r"private void sendNow\(\)") or ""
    if not send_now or "return" in send_now:
        problems.append("「立即发送」在客户端自己提前 return（服务端提示永远看不到）")
    # ③ 方块被拆 / 被换掉：界面必须自己关掉（按方块实体身份判断），不能对着一台不存在的机器操作。
    # 第 131 轮：有效性判据统一收到「发送源」接口（方块 / 终端两套实现共用一条）：
    # backend.stillValid(player) = 方块还在且够近（终端 = 物品还在），backend.isConnected() = 网络可用。
    still = body(src["menu_grid"], r"public boolean stillValid\(final Player player\)") or ""
    if "backend.stillValid(serverPlayer)" not in still \
            or "backend.isConnected()" not in still:
        problems.append("网格菜单 stillValid 没有复核「发送源还在 + 网络可用」")
    stay = body(src["menu_grid"], r"public boolean canMenuStayOpen\(final Player player\)") or ""
    if "backend.stillValid(serverPlayer)" not in stay:
        problems.append("网格没有按发送源身份判断归属（拆掉 / 换掉后界面还开着）")
    if problems:
        return False, "；".join(problems)
    return True, "未连网络退回设置 / 目标未设由服务端提示 / 拆方块自动关界面"


@check("A7", "本轮没有新增任何刷屏诊断（这几个文件里 0 条日志调用）")
def check_a7(src, zh, en):
    logged = [rel for rel in ("scr_grid", "scr_target", "scr_prompt", "menu_grid",
                              "pkt_order", "pkt_sync", "block_q") if "LOGGER" in src[rel]]
    if logged:
        # 用户第 5 条口径：新诊断必须「状态变化才记」，不允许每帧 / 每 tick 刷；
        # 本轮干脆一条新日志都没有（失败原因全部走 S2C 提示行），因此这里直接禁止。
        return False, "出现日志调用：" + "、".join(logged)
    return True, "六个文件 0 条 LOGGER（无新增诊断 ⇒ 不存在刷屏）"


# ======================================================================================
# ③ 网格顶部工具条（目标 / 发送 / 清空 / 速率 / 设置）
# ======================================================================================
@check("B1", "网格界面真的有顶部工具条（在 GUI 上方，不挤右列），四个控件都在，且没有「设置」按钮")
def check_b1(src, zh, en):
    scr = src["scr_grid"]
    needed = {
        "工具条高": r"TOOLBAR_H\s*=\s*16\s*;",
        "放在 GUI 上方": r"toolbarY = Math\.max\(0, topPos - TOOLBAR_H - TOOLBAR_GAP\);",
        "目标按钮": r"targetButton = Button\.builder\(",
        "发送按钮": r"sendButton = Button\.builder\(",
        "清空按钮": r"clearButton = Button\.builder\(",
        "速率文字": r"renderToolbarText\(",
    }
    missing = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing:
        return False, "工具条缺少：" + "、".join(missing)
    # 第 131 轮反转：设置界面删了 ⇒ 「设置」按钮不许回来（否则又会出现一个点了没作用的按钮）
    if has(scr, r"settingsButton"):
        return False, "工具条又把「设置」按钮加回来了（目标界面已删除）"
    return True, "GUI 上方的工具条 + 4 个控件（无设置按钮）"


@check("B2", "工具条四个动作都接到既有 C2S 包（目标子界面 / 发送 / 清空）；速率读服务端字段")
def check_b2(src, zh, en):
    scr = src["scr_grid"]
    target = src["scr_target"]
    needed = {
        "目标按钮开子界面": r"new SenderTargetSelectScreen\(this, getMenu\(\)\.containerId",
        "立即发送": r"new TriggerRequesterPacket\(getMenu\(\)\.containerId\)",
        "清空订单": r"RequesterOrderPacket\.ACTION_CLEAR",
        "速率文字读服务端字段": r"pacingBatchesPerTick, RsccNumberField\.compact\(pacingBatch\)",
    }
    missing = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing:
        return False, "工具条动作缺少：" + "、".join(missing)
    # 第 131 轮：目标提交挪进子界面（只带名字），旧「左键切下一台」直发 next.name() 必须没了
    if not has(target, r"new SetRequesterTargetPacket\(containerId, selected\.name\(\)\)"):
        return False, "目标子界面没有把选中名字发给服务端"
    if has(scr, r"new SetRequesterTargetPacket\(getMenu\(\)\.containerId, next\.name\(\)\)"):
        return False, "旧的「左键切下一台」直发目标还在（应改走子界面）"
    if has(scr, r"ACTION_OPEN_SETTINGS"):
        return False, "格子里还在发「切设置界面」动作码（设置界面已删）"
    return True, "目标走子界面 + 发送 / 清空 + 速率文字全部就位"


# ======================================================================================
# ④ 右侧订单清单：无上限 / 可滚动 / 自带搜索 / 删除 / 改数量
# ======================================================================================
@check("C1", "订单清单没有人为格子上限（不再有 4×2 / ORDER_VISIBLE / MAX_ORDERS 这类常量）")
def check_c1(src, zh, en):
    forbidden = []
    for rel in ("scr_grid", "menu_q"):
        code = strip_comments(src[rel])
        for token in ("ORDER_COLS", "ORDER_ROWS", "ORDER_VISIBLE", "MAX_ORDER", "MAX_ORDERS"):
            if token in code:
                forbidden.append("%s:%s" % (rel, token))
    if forbidden:
        return False, "仍残留固定格子数常量：" + "、".join(forbidden)
    if "List<Row> rows" not in src["scr_grid"]:
        return False, "清单不是 List<Row>（无上限数据源缺失）"
    return True, "清单是 List，无固定格子数"


@check("C2", "清单可滚动：滚轮翻页 + 滚动上限 + 指示条（条目比可视区多也能全部到达）")
def check_c2(src, zh, en):
    scr = src["scr_grid"]
    needed = {
        "滚轮": r"public boolean mouseScrolled\(",
        "上限": r"private int maxScroll\(\)",
        "夹取": r"panelScroll = Math\.max\(0, Math\.min\(panelScroll, maxScroll\(\)\)\);",
        "指示条": r"private void renderScrollHint\(",
    }
    missing = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing:
        return False, "清单滚动缺少：" + "、".join(missing)
    return True, "滚轮 + 上限 + 指示条"


@check("C3", "清单自带搜索框：只过滤已加入条目，大小写不敏感，匹配名称 / 资源 id")
def check_c3(src, zh, en):
    scr = src["scr_grid"]
    needed = {
        "搜索框": r"filterBox = new EditBox\(",
        "响应即重建": r"filterBox\.setResponder\(",
        "重建可见表": r"private void rebuildVisible\(\)",
        "小写归一": r"filterBox\.getValue\(\)\.trim\(\)\.toLowerCase\(Locale\.ROOT\)",
        "名称+资源id": r"\(name \+ \" \" \+ idOf\(key, fluid\)\)",
        "子串匹配": r"rows\.get\(index\)\.searchText\(\)\.contains\(query\)",
    }
    missing = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing:
        return False, "清单搜索缺少：" + "、".join(missing)
    # 搜索只能过滤「已加入的」条目：数据源必须是 rows（订单清单），不是网格仓储
    rebuild = body(scr, r"private void rebuildVisible\(\)")
    if rebuild is None or not has(rebuild, r"for \(int index = 0; index < rows\.size\(\); index\+\+\)"):
        return False, "rebuildVisible 没有遍历订单清单（不是「只过滤已加入的条目」）"
    if rebuild is not None and has(rebuild, r"getRepository\(\)"):
        return False, "清单搜索在读网格仓储（会变成过滤网络资源，不是过滤订单）"
    return True, "只过滤 rows，名称 + 资源 id，小写子串匹配"


@check("C4", "删除：行尾 × / 右键 → ACTION_REMOVE，且带完整资源键做二次确认")
def check_c4(src, zh, en):
    scr = src["scr_grid"]
    if not has(scr, r"private boolean isOverDelete\(final double mouseX\)"):
        return False, "没有 × 删除热区判定"
    click = body(scr, r"public boolean mouseClicked\(")
    if click is None or not has(click, r"button == 1 \|\| isOverDelete\(mouseX\)"):
        return False, "行上点击没有走删除分支（× / 右键）"
    remove = body(scr, r"private void sendRemove\(final int index\)")
    if remove is None:
        return False, "找不到 sendRemove"
    missing = [name for name, pattern in {
        "动作码": r"RequesterOrderPacket\.ACTION_REMOVE",
        "权威下标": r"index, \"\", rows\.get\(index\)\.fluid\(\), 0, rows\.get\(index\)\.key\(\)\)",
    }.items() if not has(remove, pattern)]
    if missing:
        return False, "sendRemove 缺少：" + "、".join(missing)
    return True, "× / 右键 → ACTION_REMOVE + 完整键"


@check("C5", "改数量：回车应用 + −/+ 步进，全部走 ACTION_SET_AMOUNT 与 RsccNumberField")
def check_c5(src, zh, en):
    scr = src["scr_grid"]
    needed = {
        "回车": r"applyAmount\(\);\s*\n\s*return true;",
        "应用实现": r"private void applyAmount\(\)",
        "步进": r"private void stepAmount\(final int direction\)",
        "动作码": r"RequesterOrderPacket\.ACTION_SET_AMOUNT",
        "整数夹取": r"RsccNumberField\.clampToInt\(",
        "解析口径": r"RsccNumberField\.parse\(",
        "步长按钮": r"button -> stepAmount\(-1\)",
    }
    missing = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing:
        return False, "改数量缺少：" + "、".join(missing)
    if has(scr, r"Long\.parseLong\(|Integer\.parseInt\("):
        return False, "界面自己写了数字解析（解析口径必须只有 RsccNumberField）"
    return True, "回车 + −/+ → ACTION_SET_AMOUNT，解析只走 RsccNumberField"


@check("C6", "可见行是权威下标的投影：删除 / 改数量发的是 rows 下标（过滤态不会删错条目）")
def check_c6(src, zh, en):
    scr = src["scr_grid"]
    hit = body(scr, r"private int orderRowAt\(")
    if hit is None:
        return False, "找不到 orderRowAt"
    if not has(hit, r"visible\.get\(slot\)"):
        return False, "orderRowAt 没有把可见行号换算回权威下标"
    apply_body = body(scr, r"private void applyAmount\(\)")
    if apply_body is None or not has(apply_body, r"index, \"\", row\.fluid\(\),")\
            and not has(apply_body, r"ACTION_SET_AMOUNT, focusIndex"):
        return False, "applyAmount 没有用权威下标（focusIndex）发请求"
    step_body = body(scr, r"private void stepAmount\(final int direction\)")
    if step_body is None or not has(step_body, r"ACTION_SET_AMOUNT, focusIndex"):
        return False, "stepAmount 没有用权威下标（focusIndex）发请求"
    return True, "visible → rows 下标，写请求只带权威下标"


# ======================================================================================
# ⑤ 左侧网格：沿用 RS 网格，点一下 = 按数量加入
# ======================================================================================
@check("D1", "左网格仍是 RS 网格（继承 GridScreen），点资源 = 按数量框加入清单")
def check_d1(src, zh, en):
    scr = src["scr_grid"]
    if not has(scr, r"public class ResourcePickerScreen extends GridScreen<ResourcePickerMenu>"):
        return False, "网格界面不再继承 RS 的 GridScreen"
    add = body(scr, r"private void sendAdd\(")
    if add is None or not has(add, r"ACTION_ADD, -1")\
            or not has(add, r"currentAmount\(\), key\)"):
        return False, "sendAdd 没有带「数量框的值 + 完整资源键」去 ACTION_ADD"
    if not has(scr, r"currentAmount\(\)"):
        return False, "没有数量框取值入口 currentAmount()"
    if not has(scr, r"RsccNumberField\.wire\(amountBox"):
        return False, "数量框没有走 RsccNumberField.wire（解析口径会分叉）"
    return True, "GridScreen + 点击 → ACTION_ADD(数量, key)"


# ======================================================================================
# ⑥ 客户端只读：不写世界 / 不查能力 / 不调方块实体写入口
# ======================================================================================
@check("E1", "两个界面都只有 sendToServer：不写世界、不查能力、不读方块实体、不直接改配置")
def check_e1(src, zh, en):
    problems = []
    for rel in ("scr_grid", "scr_target", "scr_prompt"):
        text = src[rel]
        if "PacketDistributor.sendToServer" not in text:
            problems.append("%s 没有 C2S 发包" % rel)
        for token in ("level.setBlock", "addFreshEntity", "Capabilities.",
                      "getItemCache()", "getFluidCache()",
                      "setOrders(", "clearOrders(", "setTargetName(", "getOrders()"):
            if token in text:
                problems.append("%s 出现客户端写入口/读取口：%s" % (rel, token))
    if problems:
        return False, "；".join(problems)
    return True, "两个界面只发请求"


@check("E2", "每条「点了可能没反应」的路径都给了消息（目标为空 / 未选中 / 数量非法 / 未连网络）")
def check_e2(src, zh, en):
    grid = src["scr_grid"]
    problems = []
    # 第 131 轮：目标入口从「左键切下一台」改成子界面 ⇒ 提示改成两条（网格侧 + 子界面侧）
    if not has(grid, r"Component\.translatable\(QLANG \+ \"target\.known\.none\"\)"):
        problems.append("本网络没有接收器时网格目标按钮没有说明")
    if not has(src["scr_target"], r'Component\.translatable\(LANG \+ "empty"\)'):
        problems.append("目标子界面候选为空时没有说明（一个灰掉的滚轮像坏了）")
    if not has(grid, r"messageKey = LANG \+ \"msg\.select_first\";"):
        problems.append("没选中条目就改数量时没提示")
    if not has(grid, r"messageKey = LANG \+ \"msg\.bad_amount\";"):
        problems.append("数量非法时没提示")
    if not has(grid, r"messageKey = QLANG \+ \"msg\.order_bad_index\";"):
        problems.append("右键移除一个不存在的条目时没提示")
    if not has(src["block_q"], r"MSG_PICKER_NO_NETWORK"):
        problems.append("右键打不开网格时没提示（设置界面已删，这条 actionbar 是唯一说明）")
    if problems:
        return False, "；".join(problems)
    return True, "五条静默路径都有消息"


# ======================================================================================
# ⑦ 设置小面板：4×2 订单格删掉 → 摘要 + 大按钮
# ======================================================================================
@check("F1", "设置面板整页删除：摘要 / 大按钮 / 4×2 订单格 / 「打开网格」包全部零残留（不许改名复活）")
def check_f1(src, zh, en):
    grid = src["scr_grid"]
    menu = src["menu_q"]
    gone = [name for name, pattern in {
        "摘要行": r"private Component summaryLine\(",
        "摘要文案键": r'LANG \+ "order\.summary"',
        "大按钮": r"openGridButton",
        "4×2 订单格": r"renderOrderCells|orderCellAt|ORDER_VISIBLE|ORDER_COLS|ORDER_ROWS",
        "打开网格包": r"OpenRequesterPickerPacket",
    }.items() if has(grid, pattern) or has(menu, pattern)]
    if gone:
        return False, "设置面板已删，却还有它的残留：" + "、".join(gone)
    # 正向项：订单内容没有跟着设置界面一起消失 —— 该在网格里的都还在（C 组 §4.5 第 5 条）
    needed = {
        "订单清单": r"List<Row> rows",
        "增单动作": r"RequesterOrderPacket\.ACTION_ADD",
        "删除动作": r"RequesterOrderPacket\.ACTION_REMOVE",
        "改数量动作": r"RequesterOrderPacket\.ACTION_SET_AMOUNT",
    }
    missing = [name for name, pattern in needed.items() if not has(grid, pattern)]
    if missing:
        return False, "设置面板删掉后网格里缺少：" + "、".join(missing)
    return True, "设置面板五件寿衣全无；订单增删改仍在网格里"


@check("F2", "「变灰了事」不会回来：工具条按钮恒可点，只有 −/+ 在无选中时禁用且必有提示")
def check_f2(src, zh, en):
    grid = src["scr_grid"]
    updater = body(grid, r"private void updateButtons\(\)")
    if updater is None:
        return False, "找不到 updateButtons"
    # 第 131 轮反转：设置面板已删（那段「灰按钮」代码随之消失）。等价纪律搬到网格：
    # 只有 −/+ 步进允许在「没选中条目」时禁用，其余按钮恒可点；且禁用路径必须有文字说明。
    disables = re.findall(r"(\w+)\.active = ", updater)
    unexpected = [name for name in disables
                  if name not in ("minusButton", "plusButton", "sendButton")]
    if unexpected:
        return False, "工具条按钮又按状态变灰了：" + "、".join(unexpected)
    if not has(updater, r"minusButton\.active = hasSelection") \
            or not has(updater, r"plusButton\.active = hasSelection"):
        return False, "−/+ 的禁用条件不是「确实选中了一条」"
    if not has(grid, r"private boolean renderAmountTooltip\("):
        return False, "改数量的禁用原因没有 tooltip（灰按钮 = 点了没反应）"
    return True, "只有 −/+ 按选中态禁用，且原因写进 tooltip"


# ======================================================================================
# ⑧ 速率数值：快照携带（网格菜单没有 ContainerData）
# ======================================================================================
@check("G1", "S2C 快照带 pacing 两个字段：record / encode / decode / of() 四处一致")
def check_g1(src, zh, en):
    pkt = src["pkt_sync"]
    needed = {
        "record 组件": r"int pacingBatchesPerTick, long pacingBatch,",
        "编码批数": r"ByteBufCodecs\.VAR_INT\.encode\(buf, packet\.pacingBatchesPerTick\(\)\);",
        "编码批量": r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.pacingBatch\(\)\);",
        "解码批数": r"final int pacingBatchesPerTick = ByteBufCodecs\.VAR_INT\.decode\(buf\);",
        "解码批量": r"final long pacingBatch = ByteBufCodecs\.VAR_LONG\.decode\(buf\);",
        "构造带上": r"pacingBatchesPerTick, pacingBatch,", 
        # 第 131 轮：节拍不再来自升级槽，改由发送源接口给出固定常量
        "of() 取发送源值": r"backend\.batchesPerTick\(\), backend\.batchSize\(\),",
    }
    missing = [name for name, pattern in needed.items() if not has(pkt, pattern)]
    if missing:
        return False, "快照 pacing 链路缺少：" + "、".join(missing)
    return True, "record/encode/decode/of 四处一致"


@check("G2", "打开网格后立刻补一份权威快照（一进来就是服务端真值，不是「未选目标」）")
def check_g2(src, zh, en):
    menu = src["menu_grid"]
    open_for = body(menu, r"public static boolean openFor\(")
    if open_for is None:
        return False, "找不到 ResourcePickerMenu#openFor"
    if not has(open_for, r"OptionalInt opened = player\.openMenu\("):
        return False, "openFor 没有接住 openMenu 的 containerId（无法给新菜单发快照）"
    # 第 131 轮：补发入口从菜单静态方法改成发送源层唯一通道 RequesterSenderBackend.syncTo
    if not has(open_for, r"opened\.ifPresent\(id -> RequesterSenderBackend\.syncTo\(player, backend"):
        return False, "openFor 没有给新菜单补发权威快照"
    return True, "openMenu → 立刻 sendConfigSyncTo(新 containerId)"


# ======================================================================================
# ⑨ tooltip：新控件都有 hover 说明（MC 不会替我们调）
# ======================================================================================
@check("H1", "网格界面每个新控件都有手动 tooltip（工具条 / 行 / 数量 / 搜索框）")
def check_h1(src, zh, en):
    scr = src["scr_grid"]
    needed = {
        "统一出口": r"private void renderHoverTooltips\(",
        "工具条 tooltip": r"private boolean renderToolbarTooltip\(",
        "行 tooltip": r"private boolean renderRowTooltip\(",
        "数量 tooltip": r"private boolean renderAmountTooltip\(",
        "搜索 tooltip": r"private boolean renderFilterTooltip\(",
        "真的被调用": r"renderHoverTooltips\(guiGraphics, mouseX, mouseY\);",
    }
    missing = [name for name, pattern in needed.items() if not has(scr, pattern)]
    if missing:
        return False, "tooltip 缺少：" + "、".join(missing)
    render = body(scr, r"public void render\(final GuiGraphics guiGraphics, final int mouseX,")
    if render is None or not has(render, r"renderTooltip\(guiGraphics, mouseX, mouseY\);") \
            or not has(render, r"renderHoverTooltips\("):
        return False, "render() 没有真的调用 tooltip（画不出来）"
    if has(render, r"hasShiftDown\(\)"):
        return False, "tooltip 被 Shift 条件包住（工程硬规则：常显）"
    return True, "四类 tooltip + 原版槽位 tooltip 都在 render() 里"


# ======================================================================================
# ⑩ 语言键：新增键中英齐全 + 界面引用的键都存在 + 不含 round86 已禁措辞
# ======================================================================================
def keys_referenced(text, prefix_name):
    """把 `LANG + "..."` / `QLANG + "..."` 展开成完整语言键。

    `<前缀>` 前面必须不是标识符字符：否则 `QLANG + "x"` 会被 `LANG` 这条规则
    以 `..._picker.x`（错误前缀）重复命中 —— 第一版就是这样误报了 2 条假缺失。
    """
    prefix_match = re.search(r'String %s = "([^"]*)";' % prefix_name, text)
    if prefix_match is None:
        return set()
    prefix = prefix_match.group(1)
    pattern = r'(?<![A-Za-z0-9_])%s \+ "([A-Za-z0-9_.]+)"' % prefix_name
    return {prefix + match.group(1) for match in re.finditer(pattern, text)}


@check("I1", "新增 %d 条语言键中英齐全，且不含 round86 已禁的措辞" % len(NEW_LANG_KEYS))
def check_i1(src, zh, en):
    problems = []
    absent = [key for key in NEW_LANG_KEYS if key not in zh or key not in en]
    if absent:
        problems.append("中英缺失 %d 条（Lead 待补）：%s" % (len(absent), ", ".join(absent[:5])))
    # 第 131 轮反转：设置界面的键必须真的删了（否则又会出现「键在、界面没了」的死文案）
    dead = [key for key in GONE_LANG_KEYS if key in zh or key in en]
    if dead:
        problems.append("设置界面已删但键还留着 %d 条：%s" % (len(dead), ", ".join(dead[:5])))
    for key in NEW_LANG_KEYS:
        if key in zh:
            value = str(zh[key])
            for word in FORBIDDEN_ZH:
                if word in value:
                    problems.append("zh %s 含「%s」" % (key, word))
            if "16" in value:
                problems.append("zh %s 含「16」" % key)
        if key in en:
            value = str(en[key]).lower()
            for word in FORBIDDEN_EN:
                if word in value:
                    problems.append("en %s 含「%s」" % (key, word))
            if "16" in value:
                problems.append("en %s 含「16」" % key)
    if problems:
        return False, "；".join(problems[:6])
    return True, "%d 条新键中英齐全且措辞合规" % len(NEW_LANG_KEYS)


@check("I2", "两个界面引用的语言键全部存在（LANG / QLANG 前缀都展开后核对）")
def check_i2(src, zh, en):
    problems = []
    for rel, names in (("scr_grid", ("LANG", "QLANG")),
                       ("scr_target", ("LANG",)), ("scr_prompt", ("LANG",))):
        text = src[rel]
        referenced = set()
        for name in names:
            referenced |= keys_referenced(text, name)
        # 直接写死的完整键（Component.translatable("...")）；以「.」结尾的是前缀常量本身，不算键
        for match in re.finditer(r'"((?:gui|config|block|item|advancements)\.[A-Za-z0-9_.]+)"', text):
            key = match.group(1)
            if not key.endswith("."):
                referenced.add(key)
        for key in sorted(referenced):
            if key not in zh or key not in en:
                problems.append("%s 引用不存在：%s" % (rel, key))
    if problems:
        return False, "；".join(problems[:8])
    return True, "所有引用键都存在"


# ======================================================================================
# 反例自证：把每条修复回退，对应断言必须翻红
# ======================================================================================
MUTATIONS = [
    ("A1", "block_q", [(r"NetworkResourceRequesterMenu\.openPickerFor\(serverPlayer, requester\)",
                        "false /* mutated: 右键不再打开网格 */")],
     "右键退回「只开设置界面」"),
    ("A2", "block_q", [(r"isPickerConnected\(requester\)", "false /* mutated: 不再区分失败原因 */")],
     "打不开网格时不再区分原因（两种失败都变成一个笼统提示）"),
    ("A2", "block_q",
     [(r"NetworkResourceRequesterMenu\.MSG_PICKER_NO_NETWORK",
       "NetworkResourceRequesterMenu.MSG_PICKER_OPEN_FAILED")],
     "「没接网络」和「这次没开成」被合成同一句（原因说不清）"),
    ("A3", "menu_q",
     [(r"ResourcePickerMenu\s*\n\s*\.openFor\(",
       "Object.openFor(")],
     "唯一入口不再调 openFor（判据分叉）"),
    ("A5", "pkt_order", [(r"ACTION_CLEAR\s*=\s*3\s*;", "ACTION_CLEAR = 4;")],
     "动作码多出第 5 个（切界面通道回来了）"),
    ("A6", "menu_grid",
     [(r"return backend\.stillValid\(serverPlayer\) && backend\.isConnected\(\);",
       "return true; /* mutated: 不再复核发送源 / 网络 */")],
     "拆掉方块 / 网络断开后网格界面还开着（对着不存在的机器操作）"),
    ("A7", "scr_grid",
     [(r"    private static final String QLANG =",
       "    private static final Object LOGGER = null; // mutated: 界面里塞一条日志\n"
       "    private static final String QLANG =")],
     "界面里塞一条日志（每帧刷屏）"),
    ("B1", "scr_grid", [(r"targetButton = Button\.builder\(", "targetButton = null; // mutated: (")],
     "工具条少了「目标」按钮"),
    ("B1", "scr_grid", [(r"        renderToolbarText\(guiGraphics\);",
                          "        settingsButton();\n        renderToolbarText(guiGraphics);")],
     "工具条又把「设置」按钮加回来了"),
    ("B2", "scr_grid", [(r"new TriggerRequesterPacket\(getMenu\(\)\.containerId\)",
                         "new TriggerRequesterPacket(-1) /* mutated */")],
     "立即发送没有用本菜单 containerId（服务端会拒收）"),
    ("C1", "scr_grid", [(r"public static final int PANEL_W = 118;",
                         "public static final int MAX_ORDERS = 16;\n"
                         "    /** mutated: 人为上限回来了 */\n"
                         "    public static final int PANEL_W = 118;")],
     "把「订单最多 16 条」的人为上限加回来"),
    ("C3", "scr_grid", [(r"rows\.get\(index\)\.searchText\(\)\.contains\(query\)",
                         "getMenu().getRepository().getViewList().contains(query) /* mutated */")],
     "清单搜索改成过滤网络资源（不是已加入条目）"),
    ("C4", "scr_grid", [(r"button == 1 \|\| isOverDelete\(mouseX\)", "false /* mutated */")],
     "行上点击不再删除"),
    ("C5", "scr_grid", [(r"button -> stepAmount\(-1\)", "button -> { } /* mutated */")],
     "− 按钮不再调 stepAmount（改了数量却没发请求）"),
    ("C6", "scr_grid", [(r"visible\.get\(slot\)", "slot /* mutated: 用可见行号当权威下标 */")],
     "过滤态下用可见行号当权威下标（会删错条目）"),
    ("D1", "scr_grid", [(r"ACTION_ADD, -1", "ACTION_REMOVE, -1 /* mutated */")],
     "点网格资源变成删除"),
    ("E2", "scr_grid", [(r'messageKey = LANG \+ "msg\.select_first";',
                         '/* mutated: 静默 */'),
                        (r'messageKey = LANG \+ "msg\.select_first";',
                         '/* mutated: 静默 */')],
     "没选中就改数量时不再提示（两处入口都静默）"),
    ("F1", "scr_grid", [(r"    private void renderToolbarText\(",
                         "    private Component summaryLine() { return null; }\n\n"
                         "    private void renderToolbarText(")],
     "设置面板的摘要行在网格里复活（寿衣回来了）"),
    ("F2", "scr_grid", [(r"minusButton\.active = hasSelection",
                         "targetButton.active = !rows.isEmpty(); // mutated\n"
                         "        minusButton.active = hasSelection")],
     "工具条按钮又按状态变灰（点了没反应）"),
    ("G1", "pkt_sync", [(r"ByteBufCodecs\.VAR_LONG\.encode\(buf, packet\.pacingBatch\(\)\);", "// mutated")],
     "快照不再编码单批量（工具条显示 0）"),
    ("G2", "menu_grid", [(r"final java\.util\.OptionalInt opened = player\.openMenu[\(]",
                          "final java.util.OptionalInt opened = java.util.OptionalInt.empty(); // mutated 没接住 openMenu 的 containerId (")],
     "打开网格后没接住 containerId（补发快照永远不发生）"),
    ("H1", "scr_grid", [(r"renderHoverTooltips\(guiGraphics, mouseX, mouseY\);", "// mutated: 不画 tooltip")],
     "网格界面不再画任何 tooltip"),
    ("I1", "scr_target", [], "语言键缺失（由运行时删掉一条 zh 新键模拟）"),
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
    print("第 96 轮 WS-D 自检：请求器 = 网格 + 订单清单（搜索 / 删除 / 改数量 / 无上限）")
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

    # 第 131 轮 C 组新增的收紧：已删除的界面 / 死通道包必须真的不在
    revived = [rel for rel in GONE_FILES.values() if os.path.isfile(os.path.join(ROOT, rel))]
    if revived:
        print("[FAIL] 第 131 轮：这些文件已按用户要求整页删除，现在又被加回来了")
        for rel in revived:
            print("       - %s" % rel)
        return 1
    print("[OK] 反向门禁：请求器设置界面 + OpenRequesterPickerPacket 都不存在（第 131 轮 C 组）")

    results = run_all(src, langs["zh"], langs["en"])
    failures = [row for row in results if not row[2]]
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
        if target == "I1":
            # 语言键反例：删掉一条新增 zh 键 ⇒ I1 必须判负
            clone_zh.pop(NEW_LANG_KEYS[0], None)
            mutated = clone
        elif not replacements:
            proof_failures.append("%s：反例变异没生效（%s）" % (target, label))
            continue
        else:
            mutated_text = apply_mutation(src, rel_key, replacements)
            if mutated_text is None:
                proof_failures.append("%s：反例变异没生效（%s）" % (target, label))
                continue
            mutated = dict(src)
            mutated[rel_key] = mutated_text
        mutated_results = run_all(mutated, clone_zh, langs["en"])
        target_ok = [ok for cid, _desc, ok, _detail in mutated_results
                     if cid == target and ok]
        if target_ok:
            proof_failures.append("%s：回退「%s」后断言仍然通过（假绿）" % (target, label))

    caught = {failure.split("：")[0] for failure in proof_failures}
    for target, _rel, _rep, label in MUTATIONS:
        if target not in caught:
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
