# -*- coding: utf-8 -*-
"""
第 131 轮：请求器设置界面 / 6 个插件槽 / 「左键切下一台」目标轮换 / 升级驱动的节拍 已删除 ⇒ 判据反转
（服务端唯一入口收敛到 RequesterSenderBackend，投递引擎收敛到 SenderDelivery；只收紧与反转，未放宽）。
第 86 轮：网络资源接收器 / 请求器的**配置 UI + 数据包**防回归自检。

本轮新增的东西（全部是「服务端权威」而非「客户端自己改」）：
  * 接收器：改名输入框 + 确定（C2S `SetReceiverNamePacket` / S2C `SyncReceiverConfigPacket`）；
  * 请求器：目标名 + 订单清单（≤16 条，资源 + 数量）+ 立即发送
    （C2S `SetRequesterTargetPacket` / `RequesterOrderPacket` / `TriggerRequesterPacket`；
    S2C `SyncRequesterConfigPacket`）。
    第 92 轮：`RequestRequesterResourcesPacket` / `SyncRequesterResourcesPacket` 这条
    「打开界面 / 刷新才要一份资源列表」的通道**从未存在任何发送点**（`new ...Packet(` = 0 处），
    已随本次收尾整体删除；玩家选资源改为在 RS 网格界面里直接看网络（更权威、也没有死通道）。

断言（任一 FAIL 即退出码 1）：
  ① **C2S 每个字段都在服务端被校验**（源码级）：
     * 每个 C2S 包的 `handle` 都有 ① 菜单 id 校验 ② `stillValid(player)` ③ 调进菜单的校验入口；
     * 接收器改名：空名 / 超长 / 非法字符 / 重名 四条守卫**全部在 `setReceiverName` 之前**，
       且「改成本名」在判重之前（否则保持原名会误判冲突）；
     * 请求器目标：形状 → `resolveReceiversByName` → 0 个 = NOT_FOUND、>1 个 = AMBIGUOUS，
       全部在 `setTargetName` 之前；
     * 订单：数量 > 0、资源注册名存在、条目上限 16、下标范围，全部在 `setOrders` 之前。
  ② **行为模型可复跑**：把 Java 的判据在 Python 里逐条镜像，用具体输入算出「接受 / 拒绝」，
     并断言「被拒绝 ⇒ 配置一个字都没改」（不是只看字符串）。
  ③ **payload 注册不引用客户端类**（源码级）：8 个新包文件里没有任何
     `net.minecraft.client.*` / `com.mojang.blaze3d.*`；S2C 的处理体只走 `ClientPayloadHooks`；
     `RS_Create_Compat.registerPayloads` 里 8 个包全部注册（C2S 一律 `ctx.enqueueWork`）。
  ④ **条目上限 16**、**数量用 `RsccNumberField.wire`**、**白名单仍是两大类**、
     **同步只在变更时发**（界面的 `renderLabels` 里没有 `sendToServer` / 网络存储扫描）。
  ⑤ **反例自证**：把「客户端直接改 NBT」「无服务端校验」「S2C 里直接 new 客户端 Screen」
     三种修前写法喂给**同一批判据**，必须判负；再证明真实源码通过（判据不是恒真）。
  ⑧ **目标接收器：从列表里挑（本批新增，替换「手打名字」）**：
     * 候选列表**复用既有网络枚举**（菜单里只有一处 `GraphNetworkComponent#getContainers()`
       遍历，筛的是接收器节点 + `isProperlyConfigured()`，与接收器方块实体既有的
       `receiversInNetwork()` 同一判据 —— 那里是 private，所以只能复用同一段遍历，不能另写一套）；
     * **提交的仍然只有名字**（`SetRequesterTargetPacket` + `.name()`），存储层不变，
       服务端 `applyTargetFromUi` 仍走 `resolveReceiversByName`（存在性 + 重名拒绝）；
     * 客户端**不再有任何目标名输入框**（EditBox 只剩数量框）；
     * **空列表有提示**（`target.known.none` + `target.entry.none` + 代码里的 `receivers.isEmpty()` 分支）；
     * **语言键里没有「同名」**（可复跑扫描 zh_cn；en_us 扫描 same-name）；
     * 渲染循环（`renderLabels`）不扫网络、不发包；
     * **反例自证**：把「仍然手打名字」「语言里出现同名字样」「每帧扫网络」喂给同一批判据 ⇒ 必须判负。
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
NET = os.path.join(PKG, "network")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

RECEIVER_MENU = "menu/NetworkResourceReceiverMenu.java"
REQUESTER_MENU = "menu/NetworkResourceRequesterMenu.java"
RECEIVER_SCREEN = "client/screen/NetworkResourceReceiverScreen.java"
REQUESTER_SCREEN = "client/screen/NetworkResourceRequesterScreen.java"
RECEIVER_BE = "block/entity/NetworkResourceReceiverBlockEntity.java"
REQUESTER_BE = "block/entity/NetworkResourceRequesterBlockEntity.java"
MAIN = "RS_Create_Compat.java"
HOOKS = "network/ClientPayloadHooks.java"
SINK = "client/ClientPayloadSink.java"

# 第 131 轮 C 组：请求器设置界面整页删除 ⇒ 它的「打开网格」包（唯一发送方随界面消失）
# 是死通道，已连同类一起删除。C2S 因此是 4 个。
C2S_PACKETS = [
    "SetReceiverNamePacket.java",
    "SetRequesterTargetPacket.java",
    "RequesterOrderPacket.java",
    "TriggerRequesterPacket.java",
]
S2C_PACKETS = [
    "SyncReceiverConfigPacket.java",
    "SyncRequesterConfigPacket.java",
]
ALL_PACKETS = C2S_PACKETS + S2C_PACKETS

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       (" | " + detail) if (detail and not ok) else ""))


def section(title):
    print("")
    print("=" * 78)
    print(title)
    print("=" * 78)


def read(rel):
    path = os.path.join(PKG, rel)
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """剥掉 `//` 与 `/*...*/`（保留换行）；字符串字面量保留（它们就是判据）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if char == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif char == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        else:
            out.append(char)
            i += 1
    return "".join(out)


def method_body(code, signature):
    """按大括号配平取方法体（含签名行）；找不到返回 None。"""
    start = code.find(signature)
    if start < 0:
        return None
    open_at = code.find("{", start)
    if open_at < 0:
        return None
    depth = 0
    for index in range(open_at, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[start:index + 1]
    return None


def order_ok(body, tokens):
    """tokens 必须全部出现，且按给定顺序出现（「先校验、后写入」的源码级判据）。"""
    positions = [body.find(token) for token in tokens]
    if any(pos < 0 for pos in positions):
        return False
    return positions == sorted(positions)


# ======================================================================================
# ① 源码级：C2S 字段 → 服务端校验（真实源码 + 反例共用同一批判据）
# ======================================================================================

def judge_c2s_packet(text):
    """一个 C2S 包是否满足「handle → 菜单校验入口」的三道归属校验。"""
    errors = []
    if "implements CustomPacketPayload" not in text:
        errors.append("不是 CustomPacketPayload")
    body = method_body(text, "public static void handle(")
    if body is None:
        return errors + ["缺少 handle(...)"]
    if "containerId != packet.containerId()" not in body:
        errors.append("没有校验 containerId 归属")
    if "stillValidFor(player" not in body and "stillValid(player)" not in body:
        errors.append("没有校验玩家 / 方块仍然有效")
    # 第 131 轮：服务端唯一校验入口从「菜单静态方法」收敛到 RequesterSenderBackend
    if "NetworkResourceRequesterMenu." not in body and "menu." not in body \
            and "RequesterSenderBackend." not in body:
        errors.append("没有调用服务端校验入口（菜单 / RequesterSenderBackend 都没有）")
    return errors


def judge_name_flow(menu_text):
    """接收器改名：四条守卫都在 setReceiverName 之前，且拒绝分支真的拒绝。"""
    body = method_body(menu_text, "public NameApplyResult applyNameFromUi")
    if body is None:
        return ["缺少 applyNameFromUi"]
    errors = []
    if "checkNameShape(name)" not in body:
        errors.append("没有做形状校验（空名 / 长度 / 非法字符）")
    if not re.search(r"if \(shape != NameApplyResult\.OK\)\s*\{", body):
        errors.append("形状校验没有形成拒绝分支")
    if not order_ok(body, ["checkNameShape(name)", "isNameTakenInNetwork(name)",
                           "setReceiverName("]):
        errors.append("校验顺序不对：重名判定必须在写入之前")
    if "name.equals(receiver.getReceiverName())" not in body:
        errors.append("没有「改成本名不算冲突」的短路")
    if body.find("name.equals(receiver.getReceiverName())") < 0 \
        or body.find("isNameTakenInNetwork(name)") < body.find(
            "name.equals(receiver.getReceiverName())"):
        errors.append("「本名短路」必须在重名判定之前")
    if not re.search(r"isNameTakenInNetwork\(name\)\)\s*\{\s*sendConfigSync\(MSG_NAME_TAKEN\);\s*"
                     r"return NameApplyResult\.TAKEN;", body):
        errors.append("重名分支没有拒绝 + 提示")
    if "receiver.setReceiverName(name);" not in body:
        errors.append("没有真正的写入点")
    if "sendConfigSync(" not in body:
        errors.append("失败路径没有回发提示")
    return errors


def judge_target_flow(menu_text):
    """请求器目标：形状 → 存在性（0 / >1）→ setTargetName，且目标不存在必须拒绝。

    第 131 轮：这段逻辑从「请求器菜单静态方法 applyTargetFromUi」搬到
    `RequesterSenderBackend.applyTarget`（方块与终端共用同一条），判据原样重锚。
    """
    body = method_body(menu_text, "public static TargetApplyResult applyTarget(")
    if body is None:
        return ["缺少 applyTarget（唯一的目标写入入口）"]
    errors = []
    if "checkNameShape(name)" not in body:
        errors.append("没有形状校验")
    if "resolveReceiversByName(" not in body:
        errors.append("没有做目标存在性校验")
    if not re.search(r"hits\.isEmpty\(\)\)\s*\{[\s\S]{0,240}?"
                     r"messageKeyFor\(TargetApplyResult\.NOT_FOUND\)[\s\S]{0,80}?"
                     r"return TargetApplyResult\.NOT_FOUND;", body):
        errors.append("找不到目标时没有拒绝 + 提示")
    if not re.search(r"hits\.size\(\) > 1\)\s*\{[\s\S]{0,240}?"
                     r"messageKeyFor\(TargetApplyResult\.AMBIGUOUS\)[\s\S]{0,80}?"
                     r"return TargetApplyResult\.AMBIGUOUS;", body):
        errors.append("同名多台时没有拒绝 + 提示")
    if not order_ok(body, ["checkNameShape(name)", "resolveReceiversByName(",
                           "setTargetName("]):
        errors.append("校验顺序不对：存在性判定必须在写入之前")
    return errors


def judge_order_flow(menu_text):
    """已选资源的增 / 删 / 改：数量 > 0、资源键非空、下标范围、资源键二次确认，全部在 setOrders 之前。

    **没有条数上限**（用户原话：「不存在『限制 16 件』的设定」「订单上限没有设限」）：
    判据里一旦出现 MAX_ORDERS / MAX_ORDER_ENTRIES / TOO_MANY，本节直接判负。

    第 131 轮：实现从「请求器菜单的 appendOrderEntryChecked / *For 包装」收敛成
    `RequesterSenderBackend` 的三个唯一入口 ``addEntry`` / ``removeEntry`` / ``setAmount``
    （网格界面与终端发送界面共用）。“唯一实现”这条判据因此变成“只有一个类里只有一套”。
    """
    errors = []
    if "MAX_ORDERS" in menu_text or "MAX_ORDER_ENTRIES" in menu_text:
        errors.append("菜单里仍存在条数上限常量（用户明确要求不要上限）")
    if re.search(r"(?:current|next|orders)(?:\.size\(\))?\s*>=?\s*(?:16|MAX_[A-Z_]+)",
                 menu_text):
        errors.append("存在按条数封顶的判断（用户明确要求「订单上限没有设限」）")
    add = method_body(menu_text, "public static OrderResult addEntry(")
    if add is None:
        errors.append("缺少唯一实现 addEntry（追加订单的判据必须只有一处）")
    else:
        if not re.search(r"if \(amount <= 0\)\s*\{\s*return OrderResult\.INVALID_AMOUNT;", add):
            errors.append("ADD 没有拒绝非法数量")
        if not re.search(r"if \(key == null\)\s*\{\s*return OrderResult\.INVALID_RESOURCE;", add):
            errors.append("ADD 没有拒绝空资源键（解析失败必须拒绝，绝不猜一个资源）")
        if "MAX_ORDER_ENTRIES" in add or "MAX_ORDERS" in add or "TOO_MANY" in add:
            errors.append("ADD 里仍有条数上限（用户明确要求「订单上限没有设限」）")
        if not order_ok(add, ["amount <= 0", "key == null", "backend.setOrders("]):
            errors.append("ADD 的校验顺序不对（必须校验全部字段后才写）")
    # 第 131 轮：不再有 *For 包装层（网格 / 终端两套界面都直接调这三个唯一入口）
    for stale in ("appendOrderEntryChecked(", "removeEntryChecked(", "setAmountChecked(",
                  "addEntryFor(", "removeEntryFor(", "setAmountFor("):
        if stale in menu_text:
            errors.append("旧的包装层还在：%s（第 131 轮已收敛成唯一入口）" % stale)
    remove = method_body(menu_text, "public static OrderResult removeEntry(")
    if remove is None:
        errors.append("缺少 removeEntry")
    else:
        if not re.search(r"index < 0 \|\| index >= current\.size\(\)", remove):
            errors.append("REMOVE 没有校验下标")
        if "entryMatches(current.get(index), key)" not in remove:
            errors.append("REMOVE 没有二次确认「这一条还是客户端看到的那一条」（完整资源键）")
        if not order_ok(remove, ["index < 0 || index >= current.size()",
                                 "entryMatches(current.get(index), key)",
                                 "backend.setOrders("]):
            errors.append("REMOVE 先写后校验")
    update = method_body(menu_text, "public static OrderResult setAmount(")
    if update is None:
        errors.append("缺少 setAmount")
    else:
        if "amount <= 0" not in update:
            errors.append("SET_AMOUNT 没有校验数量")
        if not re.search(r"index < 0 \|\| index >= current\.size\(\)", update):
            errors.append("SET_AMOUNT 没有校验下标")
        if "entryMatches(current.get(index), key)" not in update:
            errors.append("SET_AMOUNT 没有二次确认「这一条还是客户端看到的那一条」（完整资源键）")
        if not order_ok(update, ["amount <= 0", "index < 0 || index >= current.size()",
                                 "entryMatches(current.get(index), key)",
                                 "backend.setOrders("]):
            errors.append("SET_AMOUNT 先写后校验")
    return errors


def judge_dist_safe(text):
    """一个包类不得引用任何客户端专属类型；S2C 必须走 ClientPayloadHooks。"""
    errors = []
    for bad in ("net.minecraft.client.", "com.mojang.blaze3d.", "Minecraft.getInstance()"):
        if bad in text:
            errors.append("引用了客户端类型：%s" % bad)
    return errors


def judge_s2c_hook(text):
    body = method_body(text, "public static void handle(")
    if body is None:
        return ["缺少 handle(...)"]
    if "ClientPayloadHooks.get()" not in body:
        return ["S2C 处理体没有走 ClientPayloadHooks"]
    return []


def audit_real_sources(packets, receiver_menu, requester_menu):
    """把同一批判据跑在真实源码上，返回失败清单（空 = 全部通过）。"""
    errors = []
    for name in C2S_PACKETS:
        text = packets.get(name, "")
        if not text:
            errors.append("%s 缺失" % name)
            continue
        errors.extend("%s: %s" % (name, item) for item in judge_c2s_packet(text))
    for name in S2C_PACKETS:
        text = packets.get(name, "")
        if not text:
            errors.append("%s 缺失" % name)
            continue
        errors.extend("%s: %s" % (name, item) for item in judge_dist_safe(text))
        errors.extend("%s: %s" % (name, item) for item in judge_s2c_hook(text))
    errors.extend("接收器菜单: %s" % item for item in judge_name_flow(receiver_menu))
    errors.extend("请求器菜单: %s" % item for item in judge_target_flow(requester_menu))
    errors.extend("请求器菜单: %s" % item for item in judge_order_flow(requester_menu))
    return errors


def section_source_validation():
    section("① C2S 字段 → 服务端校验（源码级：每个 setter 前有校验）")
    packets = {}
    for name in ALL_PACKETS:
        packets[name] = strip_comments(read("network/" + name))
    receiver_menu = strip_comments(read(RECEIVER_MENU))
    # 第 131 轮：请求器的校验 / 写入唯一实现在 RequesterSenderBackend（菜单只剩容器壳）。
    # 判据文本一字不改，只是喂进来的源码 = 后端 + 菜单（后者仍要扫 providerFor 标题）。
    requester_menu = strip_comments(read(REQ_BACKEND)) + "\n" + strip_comments(read(REQUESTER_MENU))

    check("①-0 4 个 C2S + 2 个 S2C 包文件都存在（第 92 / 131 轮删掉三条死通道后）",
          all(packets[name] for name in ALL_PACKETS),
          str([name for name in ALL_PACKETS if not packets[name]]))

    errors = audit_real_sources(packets, receiver_menu, requester_menu)
    check("①-1 真实源码通过全部源码级判据（handle / 顺序 / 拒绝分支 / dist）",
          not errors, "；".join(errors[:6]))

    # 逐条小断言（失败时定位更准）
    for name in C2S_PACKETS:
        check("①-2 %s：handle 校验 containerId + stillValid + 调菜单" % name,
              not judge_c2s_packet(packets[name]), "；".join(judge_c2s_packet(packets[name])))
    check("①-3 接收器改名：空名 / 长度 / 字符 / 重名都在 setReceiverName 之前",
          not judge_name_flow(receiver_menu), "；".join(judge_name_flow(receiver_menu)))
    check("①-4 请求器目标：resolveReceiversByName 的 0 / >1 都拒绝且提示",
          not judge_target_flow(requester_menu), "；".join(judge_target_flow(requester_menu)))
    check("①-5 订单：数量 / 资源 / 上限 / 下标全部在 setOrders 之前",
          not judge_order_flow(requester_menu), "；".join(judge_order_flow(requester_menu)))
    # 第 131 轮：形状判据仍只有一处（接收器菜单），请求器侧经 RequesterSenderBackend 调用它
    check("①-6 接收器目标名长度常量 = 32，形状判据被接收器与请求器（后端）共用",
          "public static final int MAX_NAME_LENGTH = 32;" in receiver_menu
          and "NetworkResourceReceiverMenu.checkNameShape(name)" in requester_menu)
    # 菜单标题是语言键（客户端翻译，服务端从不拼自然语言）：这是菜单里**唯一**允许出现
    # Component.translatable 的地方。其余一切提示都必须走 MSG_* 语言键 / S2C key，
    # 绝不能由服务端拼好一句话再发。
    # 第 96 轮：请求器多了一条「从网格切回设置界面」的打开路径，两条路径共用同一个 provider 工厂
    # （providerFor），标题因此仍然只有**一处** —— 断言从「open() 内 ≤1 处」收紧成
    # 「providerFor() 内恰好 1 处 + 其余 0 处 + 菜单里不得出现 Component.literal」。
    title_body = method_body(
        requester_menu, "public static net.minecraft.world.MenuProvider providerFor(") or ""
    # 第 131 轮：右键直接开网格 ⇒ openPickerFor 也要给新菜单一个标题（同样是语言键，
    # 不是服务端拼的自然语言）。允许的 translatable 因此恰好两处：providerFor + openPickerFor。
    open_body = method_body(requester_menu, "public static boolean openPickerFor(") or ""
    menu_without_title = requester_menu.replace(title_body, "").replace(open_body, "")
    check("①-7 菜单只发 key、不拼自然语言（提示可翻译；仅两处菜单标题）",
          "Component.translatable" not in receiver_menu
          and "Component.translatable" not in menu_without_title
          and title_body.count("Component.translatable") == 1
          and open_body.count("Component.translatable") == 1
          and "Component.literal(" not in requester_menu,
          "除 providerFor() / openPickerFor() 的菜单标题外仍出现 Component.translatable")


# ======================================================================================
# ② 行为模型：Java 判据的逐条镜像（可复跑）
# ======================================================================================

NAME_MAX = 32


def model_check_shape(raw):
    name = (raw or "").strip()
    if not name:
        return "EMPTY"
    if len(name) > NAME_MAX:
        return "TOO_LONG"
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F or ch == "\u00a7" for ch in name):
        return "INVALID_CHARS"
    return "OK"


def model_apply_name(current, occupied, raw):
    """返回 (result, new_current)；result != OK 时 new_current 必须等于 current。"""
    name = (raw or "").strip()
    shape = model_check_shape(name)
    if shape != "OK":
        return shape, current
    if name == current:
        return "OK", current
    if name in occupied:
        return "TAKEN", current
    return "OK", name


def model_names_in_network(receivers):
    return [name for name in receivers if name]


def model_apply_target(receivers, raw):
    """返回 (result, new_target)。receivers = 网络里所有接收器的名字（可能重名）。"""
    name = (raw or "").strip()
    shape = model_check_shape(name)
    if shape != "OK":
        return shape, None
    hits = [item for item in model_names_in_network(receivers) if item == name]
    if not hits:
        return "NOT_FOUND", None
    if len(hits) > 1:
        return "AMBIGUOUS", None
    return "OK", name


def model_add_order(orders, resource_id, amount, known_resources):
    """orders = [(id, amount), ...]；返回 (result, new_orders)。"""
    if amount <= 0:
        return "INVALID_AMOUNT", orders
    if resource_id not in known_resources:
        return "INVALID_RESOURCE", orders
    # 没有条数上限：无论已有多少种资源，新增一种都直接接受
    merged = []
    found = False
    for entry in orders:
        if entry[0] == resource_id:
            merged.append((resource_id, entry[1] + amount))
            found = True
        else:
            merged.append(entry)
    if not found:
        merged.append((resource_id, amount))
    return "OK", merged


def model_set_amount(orders, index, amount, resource_id):
    if amount <= 0:
        return "INVALID_AMOUNT", orders
    if index < 0 or index >= len(orders):
        return "BAD_INDEX", orders
    if orders[index][0] != resource_id:
        return "BAD_INDEX", orders  # 旧下标：这一条已经不是客户端看到的那一条
    updated = list(orders)
    updated[index] = (updated[index][0], amount)
    return "OK", updated


def model_remove_order(orders, index, resource_id):
    if index < 0 or index >= len(orders):
        return "BAD_INDEX", orders
    if orders[index][0] != resource_id:
        return "BAD_INDEX", orders
    updated = list(orders)
    del updated[index]
    return "OK", updated


def model_open_picker(has_network, has_other_node, has_energy):
    """网格入口模型（= ResourcePickerMenu#isConnectedToNetwork 的逐条镜像）。

    RS 2.x 给每个网络节点（哪怕孤零零一个方块）都建了一个 Network，
    所以 'has_network' 单独为 True 绝不能放行 —— 必须还有别的节点 + 有电。
    返回 'OPENED' / 'REFUSED'。
    """
    if not has_network or not has_other_node or not has_energy:
        return "REFUSED"
    return "OPENED"


def section_behaviour_model():
    section("② 重名 / 空名 / 找不到目标 / 非法数量 ⇒ 拒绝（行为模型，可复跑）")

    empty_cases = ["", "   ", None]
    for raw in empty_cases:
        result, current = model_apply_name("主基地", {"备用"}, raw)
        check("②-1 空名/空白 %r ⇒ EMPTY 且不改名" % (raw,),
              result == "EMPTY" and current == "主基地")
    result, current = model_apply_name("主基地", {"备用"}, "备用")
    check("②-2 同网络重名 ⇒ TAKEN 且不改名", result == "TAKEN" and current == "主基地")
    result, current = model_apply_name("主基地", {"备用"}, "主基地")
    check("②-3 改成本名 ⇒ OK（不与自己冲突）", result == "OK" and current == "主基地")
    result, current = model_apply_name("主基地", set(), "x" * (NAME_MAX + 1))
    check("②-4 超过 32 字符 ⇒ TOO_LONG 且不改名",
          result == "TOO_LONG" and current == "主基地")
    result, current = model_apply_name("主基地", set(), "坏\u00a7名")
    check("②-5 含 § / 控制字符 ⇒ INVALID_CHARS 且不改名",
          result == "INVALID_CHARS" and current == "主基地")
    result, current = model_apply_name("主基地", {"备用"}, "新名字")
    check("②-6 合法且未占用 ⇒ OK 并写入", result == "OK" and current == "新名字")

    result, target = model_apply_target(["甲", "乙"], "丙")
    check("②-7 目标不存在 ⇒ NOT_FOUND 且不写目标", result == "NOT_FOUND" and target is None)
    result, target = model_apply_target(["甲", "甲"], "甲")
    check("②-8 同名多台 ⇒ AMBIGUOUS 且不写目标", result == "AMBIGUOUS" and target is None)
    result, target = model_apply_target(["甲", "乙"], " 甲 ")
    check("②-9 目标唯一存在（首尾空白被 trim）⇒ OK", result == "OK" and target == "甲")

    orders = [("minecraft:iron_ingot", 64)]
    result, after = model_add_order(orders, "minecraft:iron_ingot", 0, {"minecraft:iron_ingot"})
    check("②-10 数量 0 ⇒ INVALID_AMOUNT 且清单不变", result == "INVALID_AMOUNT" and after == orders)
    result, after = model_add_order(orders, "minecraft:gold_ingot", -5, {"minecraft:gold_ingot"})
    check("②-11 数量负数 ⇒ INVALID_AMOUNT 且清单不变", result == "INVALID_AMOUNT" and after == orders)
    result, after = model_add_order(orders, "minecraft:not_registered", 1, {"minecraft:iron_ingot"})
    check("②-12 资源未注册 ⇒ INVALID_RESOURCE 且清单不变",
          result == "INVALID_RESOURCE" and after == orders)

    many = [("r%d" % index, 1) for index in range(16)]
    result, after = model_add_order(many, "r16", 1, {entry[0] for entry in many} | {"r16"})
    check("②-13 已有 16 种资源仍能加第 17 种（没有条数上限，绝不拒绝）",
          result == "OK" and len(after) == 17 and after[-1] == ("r16", 1))
    result, after = model_add_order(many, "r0", 5, {entry[0] for entry in many})
    check("②-14 同资源合并 ⇒ OK 且不新增条目（合并是唯一的「已存在」处理）",
          result == "OK" and len(after) == 16 and after[0] == ("r0", 6))

    result, after = model_add_order([], "minecraft:water", 1000, {"minecraft:water"})
    result, after = model_set_amount(after, 0, 5000, "minecraft:water")
    check("②-15 合法改数量 ⇒ OK 并写入", result == "OK" and after == [("minecraft:water", 5000)])
    result, after = model_set_amount(after, 5, 10, "minecraft:water")
    check("②-16 下标越界 ⇒ BAD_INDEX 且清单不变", result == "BAD_INDEX" and after == [("minecraft:water", 5000)])
    stale, after = model_set_amount(after, 0, 10, "minecraft:lava")
    check("②-16b 旧下标（资源对不上）⇒ BAD_INDEX 且绝不改到别的条目",
          stale == "BAD_INDEX" and after == [("minecraft:water", 5000)])
    stale, after = model_remove_order(after, 0, "minecraft:lava")
    check("②-16c 旧下标删除 ⇒ BAD_INDEX 且清单不变",
          stale == "BAD_INDEX" and after == [("minecraft:water", 5000)])
    result, after = model_remove_order(after, 0, "minecraft:water")
    check("②-17 删除唯一一条 ⇒ OK 且清单为空", result == "OK" and after == [])
    result, after = model_remove_order(after, 0, "minecraft:water")
    check("②-18 删除已不存在的条目 ⇒ BAD_INDEX", result == "BAD_INDEX")

    # ⑥ 用户报告的严重问题（未连网络仍能开网格）：入口判据的行为模型
    check("②-19 孤立方块（RS 仍给它一个只有自己的 Network）⇒ 网格入口被拒",
          model_open_picker(True, False, True) == "REFUSED")
    check("②-20 接了线但网络没电 ⇒ 网格入口被拒",
          model_open_picker(True, True, False) == "REFUSED")
    check("②-21 接在网络里且有电 ⇒ 网格可开",
          model_open_picker(True, True, True) == "OPENED")
    check("②-22 完全没有 Network ⇒ 网格入口被拒",
          model_open_picker(False, False, False) == "REFUSED")


# ======================================================================================
# ③ payload 注册 / dist 安全
# ======================================================================================

def section_registration_and_dist():
    section("③ payload 注册 + dist 安全（专服不得加载客户端类）")
    main = read(MAIN)
    for name in ALL_PACKETS:
        stem = name[:-5]
        check("③-1 %s 已在 RS_Create_Compat.registerPayloads 注册" % stem,
              ("network.%s.TYPE" % stem) in main and ("network.%s.STREAM_CODEC" % stem) in main)
    # 第 131 轮反转：设置界面删除后 OpenRequesterPickerPacket 是死通道，注册与包类都必须没了
    check("③-1b 死通道 OpenRequesterPickerPacket 已彻底删除（包类 + 注册 + 发送点）",
          "OpenRequesterPickerPacket" not in strip_comments(main)
          and not os.path.isfile(os.path.join(PKG, "network/OpenRequesterPickerPacket.java"))
          and not os.path.isfile(os.path.join(PKG, "client/screen/NetworkResourceRequesterScreen.java")))
    check("③-2 5 个 C2S 全部用 ctx.enqueueWork（服务端主线程写方块实体）",
          main.count("ctx.enqueueWork(() ->") >= 5)
    for name in ALL_PACKETS:
        text = strip_comments(read("network/" + name))
        check("③-3 %s 不含客户端专属类型" % name, not judge_dist_safe(text),
              "；".join(judge_dist_safe(text)))
    hooks = read(HOOKS)
    for method in ("syncNetworkResourceReceiver", "syncNetworkResourceRequester"):
        check("③-4 ClientPayloadHooks.Sink 声明 %s（default 空实现）" % method,
              ("default void %s(" % method) in hooks)
    sink = read(SINK)
    for method in ("syncNetworkResourceReceiver", "syncNetworkResourceRequester"):
        check("③-5 ClientPayloadSink 覆盖 %s（客户端实现）" % method,
              ("public void %s(" % method) in sink)
    # 第 92 轮：资源列表死通道整体删除后，hook / sink 里也不得再有对应方法
    check("③-4b 已删除的死通道不再有 hook / sink 方法（syncNetworkResourceOptions 清零）",
          "syncNetworkResourceOptions" not in hooks
          and "syncNetworkResourceOptions" not in sink)
    check("③-6 新包名都在 network 包下（服务端可见）",
          all(read("network/" + name).startswith(
              "package cretae.cookiewyq.rs_create_compat.network;") for name in ALL_PACKETS))


# ======================================================================================
# ④ 上限 / 输入实现 / 白名单 / 同步时机
# ======================================================================================

def section_limits_and_inputs():
    section("④ 条目上限 16 / RsccNumberField.wire / 两类白名单 / 只在变更时同步")
    requester_be = strip_comments(read(REQUESTER_BE))
    requester_menu = strip_comments(read(REQUESTER_MENU))
    receiver_menu = strip_comments(read(RECEIVER_MENU))
    requester_screen = strip_comments(read(REQUESTER_SCREEN))
    receiver_screen = strip_comments(read(RECEIVER_SCREEN))
    picker_screen = strip_comments(read(PICKER_SCREEN))
    target_screen = strip_comments(read(TARGET_SCREEN))

    check("④-1 方块实体**没有**条数上限常量（MAX_ORDERS 已删除）",
          "MAX_ORDERS" not in requester_be)
    check("④-2 菜单**没有**条数上限常量 / 封顶判断（不再有「满了就拒绝」）",
          "MAX_ORDER_ENTRIES" not in requester_menu and "TOO_MANY" not in requester_menu)
    check("④-3 方块实体的 setOrders 不再按条数丢弃条目",
          "merged.size() >=" not in requester_be and "MAX_ORDERS" not in requester_be)
    check("④-4 数量输入复用 RsccNumberField.wire（全工程唯一解析口径）",
          "RsccNumberField.wire(" in picker_screen)
    check("④-5 流体数量显示按桶换算（bucketHint），物品按个数（grouped）",
          "RsccNumberField.bucketHint(" in (picker_screen + requester_screen)
          and "RsccNumberField.grouped(" in (picker_screen + requester_screen))
    check("④-6 接收器改名用 EditBox + 服务端权威包（不是本地写 NBT）",
          "new EditBox(" in receiver_screen and "SetReceiverNamePacket" in receiver_screen
          and "setReceiverName(" not in receiver_screen)
    # 第 131 轮：目标提交挪进子界面 SenderTargetSelectScreen（设置界面已删）
    check("④-7 请求器目标同理（子界面只发 SetRequesterTargetPacket，客户端不写 setTargetName）",
          "SetRequesterTargetPacket" in target_screen
          and "setTargetName(" not in target_screen)

    check("④-8 接收器白名单仍是两类（speed_upgrade + stack_upgrade）",
          'List.of("speed_upgrade", "stack_upgrade")' in receiver_menu)
    # 第 131 轮反转：请求器 6 个插件槽删除 ⇒ 不再有「对称」这回事，白名单只剩接收器一份
    check("④-9 第 131 轮反转：请求器没有插件白名单（不再复用接收器那份 ALLOWED_UPGRADES）",
          "ALLOWED_UPGRADES" not in requester_menu
          and "ALLOWED_UPGRADES" not in requester_be)
    check("④-10 接收器仍是 6 个升级槽",
          "for (int i = 0; i < UPGRADE_COUNT; i++)" in receiver_menu
          and "RsccSpeedStackUpgradeContainer.SLOTS" in receiver_menu
          and "new UpgradeSlot(" in receiver_menu)
    check("④-10b 请求器**不再有**升级槽（容器 / UPGRADE_COUNT / UpgradeSlot 全无）",
          "UPGRADE_COUNT" not in requester_menu
          and "new UpgradeSlot(" not in requester_menu
          and "RsccSpeedStackUpgradeContainer" not in requester_menu
          and "RsccSpeedStackUpgradeContainer" not in requester_be)

    # 同步时机：渲染循环里不得发包 / 扫网络
    render_labels = method_body(requester_screen, "protected void renderLabels(") or ""
    check("④-11 请求器 renderLabels 里没有发包（同步只在变更时发）",
          "sendToServer" not in render_labels and "PacketDistributor" not in render_labels)
    check("④-12 请求器 renderLabels 里没有扫网络存储（不每帧拼全量数据）",
          "getResources(" not in render_labels and "getNetworkOrNull(" not in render_labels)
    render_labels_receiver = method_body(receiver_screen, "protected void renderLabels(") or ""
    check("④-13 接收器 renderLabels 里没有发包 / 没有占用集合重算",
          "sendToServer" not in render_labels_receiver
          and "occupiedReceiverNamesInNetwork(" not in render_labels_receiver)
    def judge_resource_list_channel(menu_text, resources_packet_exists):
        """资源列表死通道判据：整条通道（常量 / 枚举 / 发送方法 / 包类）必须都不存在。"""
        errors = []
        if "MAX_RESOURCE_OPTIONS" in menu_text:
            errors.append("还残留 MAX_RESOURCE_OPTIONS（只服务已删除的资源列表通道）")
        if "resourceViews(" in menu_text:
            errors.append("还残留 resourceViews()（只服务已删除的资源列表通道）")
        if "sendResourcesSync(" in menu_text:
            errors.append("还残留 sendResourcesSync()（只服务已删除的资源列表通道）")
        if resources_packet_exists:
            errors.append("SyncRequesterResourcesPacket 仍然存在（死通道包类未删）")
        return errors

    resources_packet_path = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat", "network", "SyncRequesterResourcesPacket.java")
    real_channel = judge_resource_list_channel(requester_menu,
                                               os.path.isfile(resources_packet_path))
    check("④-14 资源列表死通道已整体删除（不再有「打开界面 / 刷新才下发」的同步列表；"
          "玩家在网格里直接看网络）", not real_channel, "；".join(real_channel))
    # 反例自证：把旧通道的痕迹塞回文本，判据必须变红
    check("④-14b 反例自证：菜单里写回 MAX_RESOURCE_OPTIONS / resourceViews() ⇒ 判负",
          bool(judge_resource_list_channel(
              requester_menu + "\nMAX_RESOURCE_OPTIONS = 512; resourceViews();", False)))

    # 每一条失败都有提示：消息常量都在语言文件里
    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)
    # 第 131 轮：请求器的提示常量搬到 RequesterSenderBackend（菜单只剩转发两个别名）
    requester_backend = strip_comments(read(REQ_BACKEND))
    message_keys = sorted(set(re.findall(r'MSG_[A-Z_]+\s*=\s*"([^"]+)"', requester_backend))
                          | set(re.findall(r'MSG_[A-Z_]+\s*=\s*"([^"]+)"', requester_menu))
                          | set(re.findall(r'MSG_[A-Z_]+\s*=\s*"([^"]+)"', receiver_menu)))
    check("④-15 菜单里至少有 16 条提示语言键（每条失败都有话可说）",
          len(message_keys) >= 16, "实际 %d 条" % len(message_keys))
    missing = [key for key in message_keys if key not in zh or key not in en]
    check("④-16 每条服务端提示在 zh_cn / en_us 都存在", not missing, str(missing[:4]))
    # 拒绝类结果必须有对应提示（源码级：每个失败分支都 sendConfigSync）
    # 接收器把「空名 / 超长 / 非法字符」统一走 messageKeyFor(shape)，因此分开判：
    #   ① 形状分支必须 sendConfigSync(messageKeyFor(shape))；
    #   ② messageKeyFor 里三种失败都映射到各自的语言键。
    receiver_body = method_body(strip_comments(read(RECEIVER_MENU)),
                                "public NameApplyResult applyNameFromUi") or ""
    mapper_body = method_body(strip_comments(read(RECEIVER_MENU)),
                              "public static String messageKeyFor(final NameApplyResult result)") or ""
    check("④-17a 接收器改名的形状失败分支回发提示",
          "sendConfigSync(messageKeyFor(shape))" in receiver_body
          and all(("case " + case) in mapper_body for case in
                  ("EMPTY", "TOO_LONG", "INVALID_CHARS", "TAKEN"))
          and all(("MSG_NAME_" + key) in mapper_body for key in
                  ("EMPTY", "TOO_LONG", "INVALID", "TAKEN")))
    # 第 131 轮：目标 / 订单的唯一实现搬到 RequesterSenderBackend；提示映射仍是 messageKeyFor + syncTo
    backend = strip_comments(read(REQ_BACKEND))
    order_mapper = method_body(backend,
                               "public static String messageKeyFor(final OrderResult result)") or ""
    target_mapper = method_body(
        backend, "public static String messageKeyFor(final TargetApplyResult result)") or ""
    order_packet_text = strip_comments(read(ORDER_PACKET))
    for label, body_signature, failures, mapper in (
            ("请求器目标", "public static TargetApplyResult applyTarget(",
             ["MSG_TARGET_NOT_FOUND", "MSG_TARGET_AMBIGUOUS"], target_mapper),
            ("已选资源", "public static OrderResult addEntry(",
             ["MSG_ORDER_INVALID_AMOUNT", "MSG_ORDER_INVALID_RESOURCE"], order_mapper)):
        body = method_body(backend, body_signature) or ""
        if label == "请求器目标":
            # 拒绝分支 = syncTo(..., messageKeyFor(TargetApplyResult.X), -1)：绝不静默
            ok = ("syncTo(" in body and "messageKeyFor(" in body
                  and all(key in mapper for key in failures))
        else:
            # 订单失败由包处理回发：messageKeyFor(result) 必须被调用，且结果映射到具体语言键
            ok = ("RequesterSenderBackend.messageKeyFor(result)" in order_packet_text
                  and all(key in mapper for key in failures))
        check("④-17b %s 的每个失败分支都回发提示" % label, ok,
              "失败分支缺提示：%s" % failures)


# ======================================================================================
# ⑤ 反例自证：修前写法必须被判负
# ======================================================================================

def section_counterexamples():
    section("⑤ 反例自证（同一批判据必须判负）")
    real_receiver_menu = strip_comments(read(RECEIVER_MENU))
    # 第 131 轮：请求器判据的宿主是 RequesterSenderBackend（菜单只剩容器壳）
    real_requester_menu = strip_comments(read(REQ_BACKEND)) + "\n" + strip_comments(
        read(REQUESTER_MENU))

    # 反例 A：客户端直接改 NBT（没有 C2S 校验链）
    client_nbt = """
    public static void handle(final SomePacket packet, final Player player) {
        final MyBlockEntity be = (MyBlockEntity) player.level().getBlockEntity(packet.pos());
        be.setReceiverName(packet.name());   // 客户端说啥就写啥
    }
    """
    errors = judge_c2s_packet(strip_comments(client_nbt))
    check("⑤-1 反例·客户端直接改 NBT ⇒ 判负（缺 containerId / stillValid / 菜单校验）",
          bool(errors), "；".join(errors))

    # 反例 B：无服务端校验（只有写入）
    no_validation = """
    public NameApplyResult applyNameFromUi(final String rawName) {
        if (receiver == null) {
            return NameApplyResult.NOT_SERVER;
        }
        receiver.setReceiverName(rawName);
        return NameApplyResult.OK;
    }
    """
    errors = judge_name_flow(no_validation)
    check("⑤-2 反例·无服务端校验 ⇒ 判负（空名 / 重名 / 顺序全缺）", bool(errors),
          "；".join(errors))

    # 反例 C：重名分支不再拒绝（只提示、照样写）
    broken = real_receiver_menu.replace("return NameApplyResult.TAKEN;",
                                        "return NameApplyResult.OK;")
    errors = judge_name_flow(broken)
    check("⑤-3 反例·重名仍写入 ⇒ 判负（拒绝分支被改坏）", bool(errors), "；".join(errors))

    # 反例 D：目标存在性校验被删掉
    # 第 131 轮：目标判据实现在 RequesterSenderBackend；反例直接打穿它的「找不到就拒绝」分支
    broken_target = re.sub(
        r"if \(hits\.isEmpty\(\)\) \{[\s\S]*?return TargetApplyResult\.NOT_FOUND;\s*\}",
        "", real_requester_menu, count=1)
    errors = judge_target_flow(broken_target)
    check("⑤-4 反例·删掉「找不到目标」的拒绝 ⇒ 判负", bool(errors), "；".join(errors))

    # 反例 E：把「最多 16 条」的封顶加回去（用户明确说「订单上限没有设限」）
    broken_cap = real_requester_menu.replace(
        "next.add(new ResourceAmount(key, amount));",
        "if (next.size() >= 16) {\n            return OrderResult.INVALID_RESOURCE;\n        }\n"
        "        next.add(new ResourceAmount(key, amount));",
        1)
    errors = judge_order_flow(broken_cap)
    check("⑤-5 反例·把「最多 16 条」的封顶加回去 ⇒ 判负", bool(errors), "；".join(errors))

    # 反例 F：S2C 处理体里直接 new 客户端 Screen
    client_screen = """
    public static void handle(final SomeSyncPacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> Minecraft.getInstance().setScreen(new MyClientScreen(packet)));
    }
    """
    errors = judge_dist_safe(client_screen)
    check("⑤-6 反例·S2C 里直接 new 客户端 Screen ⇒ 判负（专服会崩）", bool(errors),
          "；".join(errors))

    # 反例 G：「判据不是恒真」——同一批判据对真实源码必须是 0 失败
    errors = audit_real_sources(
        {name: strip_comments(read("network/" + name)) for name in ALL_PACKETS},
        real_receiver_menu, real_requester_menu)
    check("⑤-7 反向自证·同一批判据对真实源码 0 失败（判据有区分力）",
          not errors, "；".join(errors[:4]))


# ======================================================================================
# ⑥ 任意资源类型（任意 PlatformResourceKey；无 Mekanism 编译期依赖）
#
# 本节的判据全部是「形状 + 行为模型」，且与真实源码 / 反例共用同一批函数：
#   ① 资源身份唯一标识 = ResourceKey / PlatformResourceKey（主路径不按注册名映射两态）；
#   ② 不存在「只支持物品 / 流体」的硬编码拒绝（isCacheSupported 已删除）；
#   ③ 传输层走 RS 的运行时扩展点（ExternalStorageProviderFactory），不 import 任何联动类型；
#   ④ 未识别类型仍有明确提示；
#   ⑤ 物品 / 流体路径逐字不变（零回归）；
#   ⑥ 反例自证：把「硬编码两类 + 直接 import Mekanism」喂给同一批判据必须判负。
# ======================================================================================

KEY_HELPER = "support/RsccResourceKeys.java"
TARGET_HELPER = "support/RsccGenericResourceTarget.java"
ORDER_PACKET = "network/RequesterOrderPacket.java"
CONFIG_PACKET = "network/SyncRequesterConfigPacket.java"


def judge_identity_by_resource_key(requester_be):
    """请求器投递主路径必须以 ResourceKey 为唯一标识（不能按注册名映射两态）。

    第 131 轮：投递主路径从 BE 的 ``deliverBatch`` 搬到 ``SenderDelivery.deliver``
    （方块 / 终端共用同一份实现），判据原样重锚。
    """
    errors = []
    body = method_body(requester_be, "public static Result deliver(")
    if body is None:
        return ["缺少 SenderDelivery.deliver（唯一投递实现）"]
    if "final ResourceKey resource = order.resource();" not in body:
        errors.append("没有从 ResourceAmount 取 ResourceKey")
    if "sink.apply(resource" not in body:
        errors.append("写入没有走统一的资源键入口（ReceiverSink#apply）")
    for bad in ("BuiltInRegistries", "getKey(", "ItemStack", "FluidStack"):
        if bad in body:
            errors.append("主路径仍出现按注册名 / 物品栈映射的痕迹：%s" % bad)
    return errors


def judge_no_hardcoded_two_state(requester_be):
    """不能存在「只支持物品 / 流体」的硬编码拒绝；未识别类型必须有提示。"""
    errors = []
    code = strip_comments(requester_be)
    if "isCacheSupported" in code:
        errors.append("仍然存在 isCacheSupported 硬编码白名单")
    body = method_body(code, "public static Result deliver(") or ""
    if re.search(r"if \(!\(resource instanceof ItemResource\s*\|\|\s*resource instanceof FluidResource\)\)",
                 body):
        errors.append("投递路径直接硬编码「只支持物品 / 流体」")
    if 'feedback.hint("unsupported"' not in body:
        errors.append("未识别类型没有提示（不允许静默跳过）")
    if "sink.canHandle(resource)" not in body:
        errors.append("没有把「能不能处理这种资源」交给接收器的通用判据")
    return errors


def judge_generic_transport(receiver_be, target_helper):
    """传输层必须是「平台能力 / 运行时扩展点」，不能是类型白名单，也不能 import 联动类。"""
    errors = []
    body = method_body(receiver_be, "public long applyResource(")
    if body is None:
        errors.append("缺少 applyResource（请求器投递的唯一入口）")
    else:
        for token in ("applyToItemCache(", "applyToFluidCache(", "genericTarget()"):
            if token not in body:
                errors.append("applyResource 缺少分支：%s" % token)
    handle = method_body(receiver_be, "public boolean canHandle(")
    if handle is None:
        errors.append("缺少 canHandle")
    else:
        if "instanceof PlatformResourceKey" not in handle:
            errors.append("canHandle 没有把 PlatformResourceKey 当通用判据")
        if "genericTarget()" not in handle:
            errors.append("canHandle 没有探测通用写入通道")
    if target_helper == "":
        errors.append("缺少通用目标解析类 %s" % TARGET_HELPER)
    else:
        if "getExternalStorageProviderFactories()" not in target_helper:
            errors.append("通用目标没有走 RS 的外部存储提供器扩展点")
        if "ExternalStorageProviderFactory" not in target_helper:
            errors.append("通用目标没有使用 ExternalStorageProviderFactory")
        if "instanceof ItemResource" in target_helper or "instanceof FluidResource" in target_helper:
            errors.append("通用目标里又出现了物品 / 流体两态判据")
    return errors


def judge_no_mekanism_compile_dep(files):
    """全仓源码级：不得出现 Mekanism 编译期引用（import / 类型限定名）。"""
    errors = []
    for name, text in files:
        code = strip_comments(text)
        if re.search(r"\bimport\s+mekanism\b", code):
            errors.append("%s 出现 import mekanism" % name)
        if re.search(r"\bmekanism\.[a-zA-Z]", code):
            errors.append("%s 出现 mekanism.* 类型引用" % name)
    return errors


def judge_key_carrying_packets(order_packet, config_packet, requester_menu):
    """资源键必须随包下发：订单增 / 删 / 改与配置快照都要带完整 PlatformResourceKey。"""
    errors = []
    for name, text in (("RequesterOrderPacket", order_packet),
                       ("SyncRequesterConfigPacket", config_packet)):
        if "PlatformResourceKey key" not in strip_comments(text):
            errors.append("%s 没有携带 PlatformResourceKey" % name)
        if "ResourceCodecs.STREAM_CODEC" not in strip_comments(text):
            errors.append("%s 没有用 RS 自己的资源键 codec（不能自造格式）" % name)
    # 配置快照里的订单视图也必须把完整键放进下发条目（客户端只读镜像的来源）
    if "new OrderView(id, fluid, entry.amount(), key)" not in strip_comments(config_packet):
        errors.append("配置快照的 OrderView 没有带上完整资源键")
    menu = strip_comments(requester_menu)
    if "OrderView" not in config_packet:
        errors.append("配置快照没有订单视图")
    return errors


def judge_zero_regression(receiver_be):
    """物品 / 流体路径必须逐字不变（缓存调用 / NBT 键 / 输出路径）。"""
    errors = []
    code = strip_comments(receiver_be)
    checks = (
        ("itemCache.insertItem(0, probe, simulate)", "物品写入缓存"),
        ("fluidCache.fill(probe,", "流体写入缓存"),
        ("itemCache.extractItem(slot, accepted, false)", "物品输出扣减"),
        ("fluidCache.drain(offer.copyWithAmount(accepted)", "流体输出扣减"),
        ("RsccChamberImportStrategy.itemHandlerAt(level, targetPos)", "物品目标查找"),
        ("RsccChamberImportStrategy.fluidHandlerAt(level, targetPos)", "流体目标查找"),
        ('TAG_ITEM_CACHE = "ItemCache"', "物品缓存 NBT 键"),
        ('TAG_FLUID_CACHE = "FluidCache"', "流体缓存 NBT 键"),
    )
    for token, what in checks:
        if token not in code:
            errors.append("零回归判据缺失：%s（%s）" % (token, what))
    if "if (itemTarget == null && fluidTarget == null" not in code:
        errors.append("原有「两类目标都为空 ⇒ 直接返回」判据被改掉")
    return errors


def section_generic_resource_types():
    section("⑥ 任意 PlatformResourceKey（气体等）/ 无 Mekanism 编译期依赖 / 零回归")
    requester_be = read(REQUESTER_BE)
    receiver_be = read(RECEIVER_BE)
    requester_menu = read(REQUESTER_MENU)
    target_helper = read(TARGET_HELPER)
    key_helper = read(KEY_HELPER)

    # 第 131 轮：投递主路径在 SenderDelivery（请求器 / 终端共用），判据重锚到这里
    delivery_text = strip_comments(read(SENDER_DELIVERY))
    identity = judge_identity_by_resource_key(delivery_text)
    check("⑥-1 请求器主路径以 ResourceKey 为唯一标识（不按注册名映射两态）",
          not identity, "；".join(identity))
    two_state = judge_no_hardcoded_two_state(delivery_text)
    check("⑥-2 没有「只支持物品 / 流体」的硬编码拒绝 + 未识别类型有提示",
          not two_state, "；".join(two_state))
    transport = judge_generic_transport(strip_comments(receiver_be),
                                        strip_comments(target_helper))
    check("⑥-3 传输层走 RS 的 ExternalStorageProviderFactory 运行时扩展点"
          "（装联动即自动支持气体，不写死任何类型）",
          not transport, "；".join(transport))

    all_sources = []
    for base, _dirs, names in os.walk(os.path.join(ROOT, "src", "main", "java")):
        for name in names:
            if name.endswith(".java"):
                path = os.path.join(base, name)
                with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
                    all_sources.append((os.path.relpath(path, ROOT), handle.read()))
    mek = judge_no_mekanism_compile_dep(all_sources)
    check("⑥-4 全仓源码没有 Mekanism 编译期引用（未装联动也能启动专服）", not mek,
          "；".join(mek[:4]))

    packets = judge_key_carrying_packets(
        strip_comments(read(ORDER_PACKET)), strip_comments(read(CONFIG_PACKET)),
        requester_menu)
    check("⑥-5 资源键随包下发（RequesterOrder / 配置快照）且用 RS 自己的 codec",
          not packets, "；".join(packets))

    regression = judge_zero_regression(receiver_be)
    check("⑥-6 零回归：物品 / 流体缓存调用、输出路径、NBT 键与改前逐字一致",
          not regression, "；".join(regression))

    check("⑥-7 第三方类型的稳定令牌工具存在且只服务非物品 / 流体",
          "public static String token(" in key_helper
          and "TOKEN_PREFIX" in key_helper
          and "isItemOrFluid" in key_helper)
    check("⑥-8 通用目标解析带缓存失效判据（provider 内含能力缓存，不能每 tick 重建）",
          "private void invalidateGenericTarget()" in strip_comments(receiver_be)
          and "targetState == genericTargetState" in strip_comments(receiver_be))

    # ---------- 行为模型：applyResource / canHandle / 提示分流的算术镜像 ----------
    def model_apply_resource(kind, amount):
        """kind ∈ item / fluid / third / foreign；返回 (写入量, 走了哪条通道)。"""
        if amount <= 0:
            return 0, "none"
        if kind == "item":
            return amount, "item_cache"
        if kind == "fluid":
            return amount, "fluid_cache"
        if kind == "third":
            return amount, "generic_target"
        return 0, "none"

    def model_can_handle(kind, target_accepts):
        if kind in ("item", "fluid"):
            return True
        if kind == "foreign":
            return False
        return target_accepts

    def model_hint(kind, acceptable, target_accepts):
        if acceptable > 0:
            return ""
        return "receiver_full" if model_can_handle(kind, target_accepts) else "unsupported"

    check("⑥-9 行为·分派：物品→缓存、流体→缓存、第三方→直通、不可持久化键→0",
          model_apply_resource("item", 64) == (64, "item_cache")
          and model_apply_resource("fluid", 1000) == (1000, "fluid_cache")
          and model_apply_resource("third", 500) == (500, "generic_target")
          and model_apply_resource("foreign", 500) == (0, "none")
          and model_apply_resource("third", 0) == (0, "none"))
    check("⑥-10 行为·提示分流：通道不存在 ⇒ unsupported；通道在但满 ⇒ receiver_full",
          model_hint("third", 0, False) == "unsupported"
          and model_hint("third", 0, True) == "receiver_full"
          and model_hint("item", 0, False) == "receiver_full"
          and model_hint("item", 64, False) == "")
    check("⑥-11 行为·可扩展：只要目标愿意收 1 单位，第三方类型就自动变成「支持」",
          model_can_handle("third", True) is True and model_can_handle("third", False) is False
          and model_can_handle("foreign", True) is False)

    # ---------- 反例自证：同一批判据必须判负 ----------
    hardcoded_two_state = """
    private long deliverBatch(final Network network, final NetworkResourceReceiverBlockEntity receiver) {
        for (final ResourceAmount order : List.copyOf(orders)) {
            final ResourceKey resource = order.resource();
            // 修前写法：只认物品 / 流体，其它类型一律当「不支持」
            if (!(resource instanceof ItemResource || resource instanceof FluidResource)) {
                hint("unsupported", describeResource(resource));
                continue;
            }
            final long available = storage.extract(resource, wanted, Action.SIMULATE, Actor.EMPTY);
        }
        return 0L;
    }
    """
    check("⑥-12 反例·硬编码「只支持物品 / 流体」⇒ 判负",
          bool(judge_no_hardcoded_two_state(hardcoded_two_state)),
          "；".join(judge_no_hardcoded_two_state(hardcoded_two_state)))

    named_mapping = """
    private long deliverBatch(final Network network, final NetworkResourceReceiverBlockEntity receiver) {
        for (final ResourceAmount order : List.copyOf(orders)) {
            final ResourceKey resource = order.resource();
            final String id = BuiltInRegistries.ITEM.getKey(item.item()).toString();
            applyToReceiverCache(receiver, id, 64, true);
        }
        return 0L;
    }
    """
    check("⑥-13 反例·主路径按注册名映射两态 ⇒ 判负",
          bool(judge_identity_by_resource_key(named_mapping)),
          "；".join(judge_identity_by_resource_key(named_mapping)))

    mekanism_import = """
    import mekanism.api.chemical.Chemical;
    public class Bad {
        private Chemical chemical;
        public long insert(mekanism.api.chemical.ChemicalStack stack) { return 0L; }
    }
    """
    check("⑥-14 反例·直接 import Mekanism ⇒ 判负（未装联动会 NoClassDefFoundError）",
          bool(judge_no_mekanism_compile_dep([("Bad.java", mekanism_import)])),
          "；".join(judge_no_mekanism_compile_dep([("Bad.java", mekanism_import)])))

    no_key_packets = """
    public record RequesterOrderPacket(int containerId, String resourceId, boolean fluid, int amount) {
        public static void handle() { }
    }
    """
    check("⑥-15 反例·包里只带「注册名 + 是否流体」⇒ 判负（气体无法表达）",
          bool(judge_key_carrying_packets(no_key_packets, no_key_packets, requester_menu)),
          "；".join(judge_key_carrying_packets(no_key_packets, no_key_packets,
                                              requester_menu)[:4]))

    # 反向自证：同一批判据对真实源码 0 失败（判据不是恒真）
    real_ok = (not judge_identity_by_resource_key(strip_comments(read(SENDER_DELIVERY)))
               and not judge_no_hardcoded_two_state(strip_comments(read(SENDER_DELIVERY)))
               and not judge_generic_transport(strip_comments(receiver_be),
                                               strip_comments(target_helper))
               and not judge_key_carrying_packets(
                   strip_comments(read(ORDER_PACKET)), strip_comments(read(CONFIG_PACKET)),
                   requester_menu)
               and not judge_zero_regression(receiver_be))
    check("⑥-16 反向自证·同一批判据对真实源码 0 失败（判据有区分力）", real_ok)


# ======================================================================================
# ⑦ 网格选资源（RS GridScreen 复用）：自建菜单 / 只选不取 / 已选面板几何
#
# 本轮把请求器的「◀ 资源 ▶ 逐条切换」**增补**为真正的 RS 网格界面（◀ ▶ 保留作兜底）：
#   ① 菜单 / 屏幕都是我们自己的类，MenuType **不是** RS 的（RS 的 GridContainerMenu 把类型写死成
#      Menus.getGrid()，走它 = 客户端去查 RS 的 Screen 表，我们的注册永远不生效）；
#   ② 只选不取：mouseClicked 命中资源时 return true，**绝不落 super**（super 会取出 / 放入 / 自动合成）；
#   ③ 资源键必须取自 GridResource#getResourceForRecipeMods()（物品 / 流体都有精确键）；
#   ④ 已选清单面板的几何：不得压滚动条列（leftPos+174）与搜索框（leftPos+95..162 / topPos+7..23）；
#   ⑤ 反例自证：复用 RS 的 GridContainerMenu / 不覆写 mouseClicked / 把面板压到滚动条上 ⇒ 判负。
# ======================================================================================

PICKER_MENU = "client/grid/ResourcePickerMenu.java"
PICKER_SCREEN = "client/grid/ResourcePickerScreen.java"
# 第 131 轮 C 组：OpenRequesterPickerPacket 删除；目标选择改由子界面承担
TARGET_SCREEN = "client/screen/SenderTargetSelectScreen.java"
REQ_BACKEND = "support/RequesterSenderBackend.java"
SENDER_DELIVERY = "support/SenderDelivery.java"
CLIENT_INIT = "client/ClientInit.java"

# 「自有 MenuType」的判据：必须是我们注册的类型，且不得复用 RS 的菜单类 / 类型
RS_GRID_WIRING_BAD = (
    "Menus.INSTANCE.getGrid()",
    "Menus.INSTANCE.getWirelessGrid()",
    "new GridContainerMenu",
    "new WirelessGridContainerMenu",
    "extends GridContainerMenu",
    "extends WirelessGridContainerMenu",
)


def judge_picker_menu(text):
    """我们的网格菜单：自建类 + 自建 MenuType + 专服安全 + **只选不搬** + 未连网络打不开。"""
    errors = []
    if "extends AbstractGridContainerMenu" not in text:
        errors.append("没有继承 RS 的 AbstractGridContainerMenu（无法免费拿到网格数据链路）")
    if "RS_Create_Compat.RESOURCE_PICKER_MENU.get()" not in text:
        errors.append("super(...) 没有传我们自己的 MenuType（客户端会去查 RS 的 Screen 表）")
    for bad in RS_GRID_WIRING_BAD:
        if bad in text:
            errors.append("复用了 RS 的网格菜单 / 类型：%s" % bad)
    for bad in ("net.minecraft.client.", "com.mojang.blaze3d.", "Minecraft.getInstance()"):
        if bad in text:
            errors.append("菜单类引用了客户端专属类型（专服会崩）：%s" % bad)

    # ① 只选不搬：服务端操作层恒为空操作（客户端伪造取出 / 放入包也搬不走任何物品）
    ops = method_body(text, "public GridOperations createOperations(")
    if ops is None:
        errors.append("没有覆写 createOperations（RS 的取出 / 放入会落到真实网络存储）")
    elif "return EMPTY_OPERATIONS;" not in ops:
        errors.append("createOperations 没有恒回 EMPTY_OPERATIONS（仍可能真的搬物品）")

    # ② 未接入可用网络 ⇒ 入口直接拒绝。第 131 轮：判据统一问「发送源」接口
    #   （方块 / 终端两套实现共用一条），不再是 RequestPickerMenu 自己扫网络图。
    open_body = method_body(text, "public static boolean openFor(")
    if open_body is None:
        errors.append("缺少 openFor（网格唯一的服务端入口）")
    elif "backend.isConnected()" not in open_body:
        errors.append("openFor 没有校验「发送源真的接在可用网络里」")
    check_text = strip_comments(read(REQ_BACKEND))
    check_body = method_body(check_text, "public static boolean isConnectedToNetwork(")
    if check_body is None:
        errors.append("缺少 isConnectedToNetwork（连通性判据必须只有一处）")
    else:
        for token in ("GraphNetworkComponent", "container.getNode() != requester.getNode()",
                      "EnergyNetworkComponent", "getStored()"):
            if token not in check_body:
                errors.append("isConnectedToNetwork 缺少判据：%s" % token)

    # ③ 打开后网络断开 ⇒ 服务端每 tick 的 stillValid 直接关掉它
    valid = method_body(text, "public boolean stillValid(") or ""
    if "backend.stillValid(serverPlayer)" not in valid or "backend.isConnected()" not in valid:
        errors.append("stillValid 没有跟随「发送源还在 + 网络可用」（网络断开后界面还留着）")
    return errors


def judge_picker_screen(text):
    """屏幕：继承 GridScreen + 网格只选不搬 + 面板内可设数量 / 发送 + 高亮几何 + 渲染顺序。"""
    errors = []
    if "extends GridScreen<ResourcePickerMenu>" not in text:
        errors.append("没有继承 RS 的 GridScreen（外观 / 搜索 / 排序就都不是 RS 的了）")

    body = method_body(text, "public boolean mouseClicked(")
    if body is None:
        errors.append("没有覆写 mouseClicked（点击仍会走 RS 的取出 / 放入）")
    else:
        if "getCurrentGridResource()" not in body:
            errors.append("mouseClicked 没有用 getCurrentGridResource() 判断「悬停在哪个资源上」")
        if "getResourceForRecipeMods()" not in body:
            errors.append("选中资源没有从 getResourceForRecipeMods() 取键")
        if "RequesterOrderPacket" not in text and "sendAdd(" not in body:
            errors.append("加入 / 移除没有走请求器自己的 C2S 包（无法同时带数量与动作）")
        if "isOverStorageArea(" not in body:
            errors.append("网格区域没有用 isOverStorageArea 判定（空格子会落到 RS 的放入分支）")
        if "if (isOverStorageArea(mouseX, mouseY))" not in body:
            errors.append("网格区域的点击没有无条件拦截（空格子上的点击会放入物品）")
        if "return true;" not in body:
            errors.append("网格区域没有 return true（会落 super ⇒ 真的放入 / 取出）")
        if "super.mouseClicked(" not in body:
            errors.append("非网格区域（背包 / 侧边按钮）没有落 super（原生交互会失灵）")
        elif body.find("super.mouseClicked(") < body.find("if (isOverStorageArea(mouseX, mouseY))"):
            errors.append("super.mouseClicked 在网格区域判断之前 ⇒ 仍会搬物品")
        if "hovered != null" not in body and "resource != null" not in body:
            errors.append("没有「悬停到资源」的分支")

    # 面板里必须能：累积多选、设数量、直接发送（用户要求全部在网格里完成）
    if "TriggerRequesterPacket" not in text:
        errors.append("网格里没有发送入口（用户要求「发送也在网格里完成」）")
    if "RsccNumberField.wire(" not in text:
        errors.append("数量输入没有复用 RsccNumberField（全工程唯一解析口径）")
    if "ACTION_SET_AMOUNT" not in text or "ACTION_REMOVE" not in text:
        errors.append("不能改数量 / 移除已选项（只能加不能编辑）")

    # 高亮几何：必须按 RS 的字节码常量重算格位（x+7+1+col*18 / y+topOffset+row*18-offset+1 / 9 列）
    rows_body = method_body(text, "protected void renderRows(")
    if rows_body is None:
        errors.append("没有覆写 renderRows（已选条目无法在网格里高亮）")
    else:
        if "x + 7 + 1 + col * 18" not in rows_body:
            errors.append("高亮 x 几何与 RS 不一致（应为 x + 7 + 1 + col * 18）")
        if "y + topOffset + row * 18 - scrollbarOffset + 1" not in rows_body:
            errors.append("高亮 y 几何与 RS 不一致（应为 y + topOffset + row * 18 - offset + 1）")
        if "row * COLUMNS + col" not in rows_body:
            errors.append("高亮下标与 RS 不一致（应为 row * COLUMNS + col）")
        if "getScrollbarOffset()" not in rows_body:
            errors.append("高亮没有跟随滚动偏移（滚动后会错位）")
        if "super.renderRows(" not in rows_body:
            errors.append("没有先让基类画完行（高亮会盖掉图标 / 顺序不对）")
        if "getResourceForRecipeMods()" not in rows_body:
            errors.append("高亮没有按资源键比对（会按显示名误判）")

    # 渲染顺序：面板背景在 super 之前（否则盖住控件）、面板文字在 super 之后（否则被网格盖住）
    render_body = method_body(text, "public void render(")
    if render_body is None:
        errors.append("没有覆写 render（已选面板没地方画）")
    elif ("renderSelectedPanelBackground(" not in render_body
          or "renderSelectedPanel(" not in render_body):
        errors.append("render 里没有画已选面板（背景 + 内容）")
    else:
        if render_body.find("renderSelectedPanelBackground(") > render_body.find("super.render("):
            errors.append("面板背景画在 super.render 之后 ⇒ 会盖住面板里的数量框 / 按钮")
        if render_body.find("renderSelectedPanel(") < render_body.find("super.render("):
            errors.append("面板文字画在 super.render 之前 ⇒ 会被网格行覆盖")

    if "COLUMNS = 9" not in text:
        errors.append("列数不是 9（与 RS 的 COLUMNS 不一致）")
    return errors


def const_table(text):
    """把 `public static final int X = 数字 / A + B` 解析成 {名字: 整数}（解析失败的名字不出现）。"""
    raw = dict(re.findall(r"public static final int ([A-Z_]+) = ([^;]+);", text))
    values = {}

    def resolve(name, depth=0):
        if name in values:
            return values[name]
        expr = raw.get(name)
        if expr is None or depth > 8:
            return None
        total = 0
        for part in expr.split("+"):
            part = part.strip()
            if re.fullmatch(r"-?\d+", part):
                total += int(part)
            elif part in raw:
                value = resolve(part, depth + 1)
                if value is None:
                    return None
                total += value
            else:
                return None
        values[name] = total
        return total

    for name in raw:
        resolve(name)
    return values


def judge_panel_geometry(screen_text):
    """已选清单面板的几何：不得压滚动条列，也不得压搜索框（矩形不相交）。"""
    table = const_table(strip_comments(screen_text))
    need = ("GRID_IMAGE_W", "SCROLLBAR_X", "SEARCH_X", "SEARCH_W", "SEARCH_Y", "SEARCH_H",
            "PANEL_X_OFFSET", "PANEL_W", "PANEL_TOP", "ROW_H")
    missing = [name for name in need if name not in table]
    if missing:
        return ["几何常量不全：%s" % ", ".join(missing)]
    grid_w = table["GRID_IMAGE_W"]
    scroll_x = table["SCROLLBAR_X"]
    search = (table["SEARCH_X"], table["SEARCH_Y"],
              table["SEARCH_X"] + table["SEARCH_W"], table["SEARCH_Y"] + table["SEARCH_H"])
    panel = (table["PANEL_X_OFFSET"], table["PANEL_TOP"],
             table["PANEL_X_OFFSET"] + table["PANEL_W"], table["PANEL_TOP"] + table["ROW_H"])
    errors = []
    # ① 面板整块在 GUI 主体右侧（GUI 自身宽 193，滚动条列在 174）
    if panel[0] < grid_w:
        errors.append("面板左沿 +%d 落在 GUI 主体（0..%d）里" % (panel[0], grid_w))
    # ② 不压滚动条列：滚动条从 +174 起，按 16px 安全带宽算到 +190
    if panel[0] < scroll_x + 16:
        errors.append("面板左沿 +%d 压在滚动条列（+%d..+%d）上" % (panel[0], scroll_x, scroll_x + 16))
    # ③ 不与搜索框矩形相交（x / y 同时相交才算压住）
    overlap_x = panel[0] < search[2] and search[0] < panel[2]
    overlap_y = panel[1] < search[3] and search[1] < panel[3]
    if overlap_x and overlap_y:
        errors.append("面板矩形 (%d,%d)-(%d,%d) 与搜索框 (%d,%d)-(%d,%d) 相交"
                      % (panel + search))
    if panel[2] - panel[0] != table["PANEL_W"] or table["PANEL_W"] < 60:
        errors.append("面板宽不合理：%d" % table["PANEL_W"])
    return errors


def judge_picker_wiring(main_text, client_init, requester_screen, requester_menu, packet_text):
    """注册 / 入口 / 回传链路的源码级判据。"""
    errors = []
    if 'MENUS.register("resource_picker"' not in main_text:
        errors.append("RS_Create_Compat 没有注册 resource_picker 菜单类型")
    if "IMenuTypeExtension.create(" not in main_text:
        errors.append("网格菜单类型不是带额外数据的 IMenuTypeExtension（GridData 传不过去）")
    if "ResourcePickerMenu" not in main_text:
        errors.append("注册里没有点名 ResourcePickerMenu")
    if "RESOURCE_PICKER_MENU.get()" not in client_init:
        errors.append("ClientInit 没有注册网格屏幕（客户端找不到 Screen 工厂）")
    if "ResourcePickerScreen::new" not in client_init:
        errors.append("ClientInit 没有把我们的 Screen 工厂挂上去")
    # 第 131 轮反转：设置界面（连同「打开网格」包 / 大按钮 / openResourcePicker）整页删除，
    # 右键方块 = 直接开网格 ⇒ 这里断言「旧通道必须没了 + 唯一入口必须是 openPickerFor」。
    if "OpenRequesterPickerPacket" in strip_comments(main_text):
        errors.append("死通道 OpenRequesterPickerPacket 仍在 registerPayloads 里")
    if os.path.isfile(os.path.join(PKG, "client/screen/NetworkResourceRequesterScreen.java")):
        errors.append("请求器设置界面又回来了")
    if "public static boolean openPickerFor(" not in requester_menu:
        errors.append("菜单缺少 openPickerFor（右键 = 唯一入口）")
    else:
        body = method_body(requester_menu, "public static boolean openPickerFor(")
        if body is None or ".openFor(player" not in body:
            errors.append("openPickerFor 没有调用网格菜单的 openFor(player, backend, title)")
        if body is not None and "MSG_PICKER_NO_NETWORK" not in (
                strip_comments(read("block/NetworkResourceRequesterBlock.java"))):
            errors.append("打不开时没有回提示（玩家会面对一个点了没反应的方块）")
    return errors


def judge_picker_selection_path(screen_text, menu_text, packet_text):
    """选完**不再跳回请求器界面**：增 / 删 / 改 / 发送都在网格内闭环（服务端权威）。"""
    errors = []
    for action in ("ACTION_ADD", "ACTION_REMOVE", "ACTION_SET_AMOUNT"):
        if action not in screen_text:
            errors.append("网格屏幕没有发 %s（无法在网格里编辑已选）" % action)
    if "RequesterOrderPacket" not in screen_text or "getMenu().containerId" not in screen_text:
        errors.append("请求包没有用网格菜单自己的 containerId（服务端会拒收）")
    if "NetworkResourceRequesterMenu.open(" in screen_text or \
            "NetworkResourceRequesterMenu.open(" in menu_text:
        errors.append("仍然「选完重新打开请求器界面」（用户要求全部在网格里完成）")
    if "SyncRequesterConfigPacket.takeFor(" not in screen_text:
        errors.append("网格没有接收服务端权威快照（列表只能是本地假数据）")

    # RS 的「过滤槽选中」通道在本界面一律不写
    filter_update = method_body(menu_text, "public void handleResourceFilterSlotUpdate(")
    if filter_update is None:
        errors.append("没有覆写 handleResourceFilterSlotUpdate（RS 的选中通道会落到基类）")
    elif "appendOrderEntry" in filter_update or "setOrders(" in filter_update:
        errors.append("过滤槽通道仍在写条目（写入必须带动作 + 数量）")

    # 服务端 C2S：三个动作都路由到同一段判据（第 131 轮：唯一入口 = RequesterSenderBackend）
    for token in ("RequesterSenderBackend.addEntry(",
                  "RequesterSenderBackend.removeEntry(",
                  "RequesterSenderBackend.setAmount("):
        if token not in packet_text:
            errors.append("C2S 处理体没有路由到 %s" % token)
    return errors


def section_resource_picker():
    section("⑦ 网格**主界面**：只选不搬 / 未连网络打不开 / 面板内可设数量与发送（含反例）")
    menu_text = strip_comments(read(PICKER_MENU))
    screen_text = strip_comments(read(PICKER_SCREEN))
    packet_text = strip_comments(read(ORDER_PACKET))
    main_text = strip_comments(read(MAIN))
    client_init = strip_comments(read(CLIENT_INIT))
    requester_screen = strip_comments(read(REQUESTER_MENU))
    requester_menu = strip_comments(read(REQUESTER_MENU))
    target_screen = strip_comments(read(TARGET_SCREEN))

    check("⑦-0 网格三件套存在（菜单 / 屏幕 / 目标选择子界面），旧「打开网格」包必须不存在",
          bool(menu_text) and bool(screen_text) and bool(target_screen)
          and not os.path.isfile(os.path.join(PKG, "network/OpenRequesterPickerPacket.java")),
          str([name for name, text in ((PICKER_MENU, menu_text), (PICKER_SCREEN, screen_text),
                                       (TARGET_SCREEN, target_screen))
               if not text]))

    errors = judge_picker_menu(menu_text)
    check("⑦-1 菜单：自建 MenuType + createOperations 恒空 + 未连网络打不开 + stillValid 跟随",
          not errors, "；".join(errors))

    errors = judge_picker_screen(screen_text)
    check("⑦-2 屏幕：网格区域无条件吞掉 + 面板内可设数量 / 发送 + 高亮几何 + 渲染顺序",
          not errors, "；".join(errors))

    errors = judge_panel_geometry(screen_text)
    check("⑦-3 已选面板不压滚动条列 / 不压搜索框（几何）", not errors, "；".join(errors))

    errors = judge_picker_selection_path(screen_text, menu_text, packet_text)
    check("⑦-4 选完不再跳回请求器界面（增 / 删 / 改 / 发送都在网格内闭环）",
          not errors, "；".join(errors))

    errors = judge_picker_wiring(main_text, client_init, requester_screen, requester_menu,
                                 strip_comments(read(TARGET_SCREEN)))
    check("⑦-5 注册 / 入口 / payload 全部接上", not errors, "；".join(errors))

    check("⑦-6 资源键取自 GridResource#getResourceForRecipeMods()（物品 + 流体同一入口）",
          "getResourceForRecipeMods()" in screen_text,
          "屏幕里没有出现 getResourceForRecipeMods()")

    check("⑦-6b 已选清单按加入顺序显示（逐条 append packet.orders()，不排序、不分组）",
          "for (final SyncRequesterConfigPacket.OrderView view : packet.orders())" in screen_text
          and "rows.add(" in screen_text
          and "sort(" not in screen_text)

    # ---- 反例自证：同一批判据必须判负 ----
    reuse_rs_menu = """
    public class ResourcePickerMenu extends GridContainerMenu {
        public ResourcePickerMenu(int id, Inventory inv, GridData data) {
            super(Menus.INSTANCE.getGrid(), id, inv, data);
        }
    }
    """
    errors = judge_picker_menu(strip_comments(reuse_rs_menu))
    check("⑦-7 反例·复用 RS 的 GridContainerMenu / Menus.getGrid() ⇒ 判负", bool(errors),
          "；".join(errors))

    no_override = """
    public class ResourcePickerScreen extends GridScreen<ResourcePickerMenu> {
        public ResourcePickerScreen(ResourcePickerMenu menu, Inventory inv, Component title) {
            super(menu, inv, title);
        }
    }
    """
    errors = judge_picker_screen(strip_comments(no_override))
    check("⑦-8 反例·不覆写 mouseClicked ⇒ 判负（会照 RS 原样取出 / 放入）", bool(errors),
          "；".join(errors))

    # 反例：网格区域仍然落 super（= 用户报告的「物品看似放进去」的写法）
    falls_through = """
    public class ResourcePickerScreen extends GridScreen<ResourcePickerMenu> {
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            GridResource hovered = getCurrentGridResource();
            if (hovered != null) {
                PlatformResourceKey key = hovered.getResourceForRecipeMods();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
    """
    errors = judge_picker_screen(strip_comments(falls_through))
    check("⑦-9 反例·网格区域仍落 super ⇒ 判负（放入路径被打开）", bool(errors),
          "；".join(errors[:3]))

    # 反例：服务端操作层交回真实存储（客户端伪造包就能搬物品）
    real_ops = menu_text.replace(
        "return EMPTY_OPERATIONS;",
        "return resourceType.createGridOperations(storage(), new PlayerActor(player));")
    errors = judge_picker_menu(real_ops)
    check("⑦-9b 反例·createOperations 交回真实存储 ⇒ 判负", bool(errors), "；".join(errors[:3]))

    # 反例：未连网络也允许打开（用户报告的严重问题）
    opens_anyway = menu_text.replace(
        "if (player == null || backend == null || !backend.isConnected()) {",
        "if (player == null) {", 1)
    errors = judge_picker_menu(opens_anyway)
    check("⑦-9c 反例·去掉「未连网络不许打开」⇒ 判负", bool(errors), "；".join(errors[:3]))

    panel_over_scrollbar = screen_text.replace("PANEL_GAP = 4;", "PANEL_GAP = -30;")
    errors = judge_panel_geometry(panel_over_scrollbar)
    check("⑦-10 反例·面板左移压到滚动条列 ⇒ 判负", bool(errors), "；".join(errors))

    panel_over_search = screen_text.replace("PANEL_X_OFFSET = GRID_IMAGE_W + PANEL_GAP;",
                                            "PANEL_X_OFFSET = SEARCH_X + 10;")
    errors = judge_panel_geometry(panel_over_search)
    check("⑦-11 反例·面板左沿挪进搜索框 ⇒ 判负", bool(errors), "；".join(errors))

    bad_geometry = screen_text.replace("x + 7 + 1 + col * 18", "x + col * 18")
    errors = judge_picker_screen(bad_geometry)
    check("⑦-12 反例·高亮几何不用 RS 的常量 ⇒ 判负", bool(errors), "；".join(errors[:3]))

    # ---- 反向自证：同一批判据对真实源码 0 失败 ----
    real_ok = (not judge_picker_menu(menu_text)
               and not judge_picker_screen(screen_text)
               and not judge_panel_geometry(screen_text)
               and not judge_picker_selection_path(screen_text, menu_text, packet_text)
               and not judge_picker_wiring(main_text, client_init, requester_screen,
                                           requester_menu, strip_comments(read(TARGET_SCREEN))))
    check("⑦-13 反向自证·同一批判据对真实源码 0 失败（判据有区分力）", real_ok)


# ======================================================================================
# ⑨ 用户 6 条反馈的端到端判据（可复跑 + 反例）
#
#   ② 没有「订单清单」概念、没有 16 条上限（源码 + 语言文件都扫）
#   ③ 网格里能选多个 / 设数量 / 直接发送（发送入口在网格屏幕内）
#   ⑤ 请法器也有 6 个插件槽（与接收器对称）；没有「添加 / 删除」按钮；没有「节流」技术文案
#   ⑥ 未连网络 ⇒ 网格入口被拒（源码级 + 行为模型）
#   任何「放入了但实际没有」的错觉都必须消灭（createOperations 恒空 + 网格点击全吞）
# ======================================================================================

FORBIDDEN_ZH = ("订单清单", "订单上限", "节流", "上限 16", "16 条", "最多 16")
FORBIDDEN_EN = ("order list", "pacing", "max 16", "16 entries", "16 items")


def judge_no_forbidden_wording(zh, en):
    """语言文件里玩家可见的请求器 / 网格文案不得出现技术词 / 已否定的概念。"""
    errors = []
    prefixes = ("gui.rs_create_compat.network_resource_requester.",
                "gui.rs_create_compat.resource_picker.",
                "block.rs_create_compat.network_resource_requester")
    for key, value in zh.items():
        if not key.startswith(prefixes):
            continue
        text = str(value)
        for word in FORBIDDEN_ZH:
            if word in text:
                errors.append("zh_cn %s 含「%s」" % (key, word))
        if "16" in text:
            errors.append("zh_cn %s 含「16」" % key)
    for key, value in en.items():
        if not key.startswith(prefixes):
            continue
        # 唯一例外：upgrade.pacing 是「升级槽 tooltip 的 Shift 最深层」——
        # 用户明确允许技术数值留在这里（正文里必须看不到）
        if key.endswith(".upgrade.pacing"):
            continue
        text = str(value).lower()
        for word in FORBIDDEN_EN:
            if word in text:
                errors.append("en_us %s 含「%s」" % (key, word))
        if "16" in text:
            errors.append("en_us %s 含「16」" % key)
    return errors


def judge_requester_screen(screen_text, menu_text, picker_screen, picker_menu):
    """请求器界面（第 131 轮反转）：设置界面删了 ⇒ 只剩网格；插件槽删了 ⇒ 不再对称。

    判据强度不降：
      * 旧设置界面的「清单栏目 / 增删按钮 / 节流正文 / 灰按钮」必须全无（文件本身也不存在）；
      * 网格里该有的（发送按钮 / 目标按钮 / 清空 / 订单编辑）必须都在；
      * 请求器侧插件槽与「对称」整条反转成零残留；
      * 未连网络必须有 actionbar 说明（不是变灰、也不是静默）。
    """
    errors = []
    if os.path.isfile(os.path.join(PKG, "client/screen/NetworkResourceRequesterScreen.java")):
        errors.append("请求器设置界面又回来了（第 131 轮已整页删除）")
    # ② 订单清单只能在网格里；旧设置界面的控件痕迹不许在网格里复活
    for bad in ("summaryLine(", "openGridButton", "OpenRequesterPickerPacket"):
        if bad in picker_screen:
            errors.append("设置界面残留物又出现在网格里：%s" % bad)
    # ③ 网格内的发送 / 目标 / 清空 / 订单编辑必须都在（用户要求「发送也在网格里完成」）
    for need, name in (("new TriggerRequesterPacket(getMenu().containerId)", "发送按钮"),
                       ("new SenderTargetSelectScreen(", "目标选择入口"),
                       ("RequesterOrderPacket.ACTION_CLEAR", "清空按钮"),
                       ("RequesterOrderPacket.ACTION_SET_AMOUNT", "改数量"),
                       ("RequesterOrderPacket.ACTION_REMOVE", "删除")):
        if need not in picker_screen:
            errors.append("网格里缺少 %s" % name)
    # ④ 未连网络：方块层必须给 actionbar 原因（不是变灰、也不是静默）
    block = strip_comments(read("block/NetworkResourceRequesterBlock.java"))
    if "MSG_PICKER_NO_NETWORK" not in block and "MSG_PICKER_NO_NETWORK" not in (
            strip_comments(read(REQUESTER_MENU))):
        errors.append("未连网络时没有明确原因")
    if "displayClientMessage" not in block:
        errors.append("未连网络时没有任何 actionbar 提示（点了没反应）")
    if "active = networkConnected" in picker_screen:
        errors.append("又把入口按钮变灰（要求给提示，不是变灰）")
    # ⑤ 反转：请求器侧插件槽 / 白名单 / 升级容器全无；接收器仍 6 槽
    if "UPGRADE_COUNT" in menu_text or "new UpgradeSlot(" in menu_text:
        errors.append("请求器菜单又出现插件槽（第 131 轮已删）")
    if "ALLOWED_UPGRADES" in menu_text:
        errors.append("请求器又复用接收器的插件白名单（槽已删，不该再有白名单）")
    receiver_menu = strip_comments(read(RECEIVER_MENU))
    if "for (int i = 0; i < UPGRADE_COUNT; i++)" not in receiver_menu:
        errors.append("接收器菜单的 6 个插件槽不见了")
    return errors


def section_user_requirements():
    section("⑨ 用户 6 条反馈：无上限 / 无「订单清单」/ 网格内发送 / 插件对称 / 未连网络拒开")
    zh, en = load_lang()
    # 第 131 轮：设置界面已删（read 返回空串），判据改看「网格 + 后端 + 方块层」
    requester_screen = strip_comments(read(REQUESTER_SCREEN))
    requester_menu = strip_comments(read(REQ_BACKEND)) + "\n" + strip_comments(
        read(REQUESTER_MENU))
    picker_screen = strip_comments(read(PICKER_SCREEN))
    picker_menu = strip_comments(read(PICKER_MENU))

    errors = judge_no_forbidden_wording(zh, en)
    check("⑨-1 语言文件里没有「订单清单 / 上限 16 / 节流」这类玩家可见文案",
          not errors, "；".join(errors[:6]))
    check("⑨-2 zh_cn 全表扫描：请求器 / 网格前缀下 0 条含「16」",
          not [key for key, value in zh.items()
               if key.startswith("gui.rs_create_compat.network_resource_requester.")
               and "16" in str(value)])

    errors = judge_requester_screen(requester_screen, requester_menu, picker_screen, picker_menu)
    check("⑨-3 第 131 轮：设置界面零残留 / 网格内该有的都在 / 请求器插件槽与对称关系全无",
          not errors, "；".join(errors[:6]))

    check("⑨-4 无条数上限（方块实体 + 菜单两处都没有封顶常量 / 封顶判断）",
          "MAX_ORDERS" not in strip_comments(read(REQUESTER_BE))
          and "MAX_ORDER_ENTRIES" not in requester_menu
          and "MAX_ORDERS" not in requester_menu)

    # 第 131 轮：服务端入口 triggerFor 在 RequesterSenderBackend，最终调 backend.trigger(...)
    check("⑨-5 网格内可发送：屏幕发 TriggerRequesterPacket，服务端 triggerFor 走同一段判据",
          "TriggerRequesterPacket" in picker_screen
          and "public static void triggerFor(" in requester_menu
          and "backend.trigger(player);" in requester_menu)

    check("⑨-6 未连网络 ⇒ 入口被拒（openFor 的连通性判据 + 方块层 actionbar 说明）",
          "backend.isConnected()" in picker_menu
          and "public static boolean openPickerFor(" in requester_menu
          and "MSG_PICKER_NO_NETWORK" in strip_comments(
              read("block/NetworkResourceRequesterBlock.java"))
          and "active = networkConnected" not in picker_screen)

    # ---- 反例自证 ----
    bad_zh = dict(zh)
    bad_zh["gui.rs_create_compat.resource_picker.selected.header"] = "订单清单 16 / 16"
    check("⑨-7 反例·语言里写回「订单清单 16 / 16」⇒ 判负",
          bool(judge_no_forbidden_wording(bad_zh, en)))

    bad_zh2 = dict(zh)
    bad_zh2["gui.rs_create_compat.network_resource_requester.order.resource.grid"] = "节流：每 1 tick 一批"
    check("⑨-7b 反例·语言里写回「节流」⇒ 判负",
          bool(judge_no_forbidden_wording(bad_zh2, en)))

    # 第 131 轮：设置界面删了 ⇒ 反例改成「在网格里把设置界面的按钮/摘要加回来」
    with_buttons = picker_screen.replace(
        "sendButton = Button.builder(",
        'addRenderableWidget(Button.builder(Component.translatable(LANG + "order.add"),'
        ' b -> { }).bounds(0, 0, 24, 16).build());\n        '
        "openGridButton = Button.builder(Component.literal(\"x\"), b -> { }).bounds(0, 0, 1, 1).build();\n        "
        "sendButton = Button.builder(")
    errors = judge_requester_screen(requester_screen, requester_menu, with_buttons, picker_menu)
    check("⑨-8 反例·把「设置界面 / 增删按钮」加回网格 ⇒ 判负", bool(errors), "；".join(errors[:3]))

    # 第 131 轮：设置界面删了 ⇒ 反例改成「网格入口按连通性变灰」（静默失败的一种）
    no_hint = picker_screen.replace(
        "sendButton = Button.builder(",
        "if (false) { targetButton.active = networkConnected; } sendButton = Button.builder(", 1)
    errors = judge_requester_screen(requester_screen, requester_menu, no_hint, picker_menu)
    check("⑨-8b 反例·未连网络时不提示（静默失败）⇒ 判负", bool(errors), "；".join(errors[:3]))

    # 行为模型：未连网络 / 有上限 / 有按钮 三种修前写法喂给同一批判据
    check("⑨-9 反例·模型侧：未接网络仍放行 ⇒ 判负（与 ②-19..22 同一判据）",
          model_open_picker(True, False, True) == "REFUSED"
          and model_open_picker(True, True, False) == "REFUSED")
    capped = [("r%d" % index, 1) for index in range(16)]
    result, after = model_add_order(capped, "r16", 1,
                                    {entry[0] for entry in capped} | {"r16"})
    check("⑨-10 反例·若保留 16 条上限，第 17 种就会被拒 —— 真实模型必须接受",
          result == "OK" and len(after) == 17)



# ======================================================================================
# ⑧ 目标接收器：从「本网络接收器列表」里挑（替换「手打名字」）
#
# 用户原话：「为什么说是同名的这一个机器？……是要在这个发送器中设定一个机器，然后呢再进行发送，
# 什么玩意叫同名？」⇒ ① 交互改成「从列表里挑一台」；② 全部文案删掉「同名」。
#
# 本节的判据（可跑在真实源码上，也可跑在被改坏的反例上）：
#   ① 候选数据来自**既有**那一处网络枚举（不是新写一套）：全菜单只有一次
#      `GraphNetworkComponent#getContainers()` 遍历、一次 `NetworkNodeContainer` 循环，
#      筛的是接收器节点 + `isProperlyConfigured()`；
#   ② 提交的仍然只有**名字**（`SetRequesterTargetPacket` + `.name()`）：存储层不变，
#      服务端 `applyTargetFromUi` 仍用 `resolveReceiversByName` 做存在性 / 重名判定；
#   ③ 客户端没有目标名输入框（`targetBox` 消失、EditBox 只剩数量框）；
#   ④ 空列表有提示（面板位 + 按钮位 + 代码分支）；
#   ⑤ 渲染循环不扫网络 / 不发包；
#   ⑥ 语言里没有「同名」（zh）/「same-name」（en）。
# ======================================================================================

REQ_PREFIX = "gui.rs_create_compat.network_resource_requester."
RECV_PREFIX = "gui.rs_create_compat.network_resource_receiver."
REQUESTER_HELP_KEY = "block.rs_create_compat.network_resource_requester.help"
NETWORK_LOOP = re.compile(r"for \(final [\w.]*NetworkNodeContainer")


def load_lang():
    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)
    return zh, en


def judge_receiver_picker(menu_text, screen_text, packet_text, receiver_be):
    """候选列表来源 / 提交语义 / 无手打输入 / 空列表提示 / 渲染循环开销。

    第 131 轮：目标选择从「请求器设置面板」搬到子界面 `SenderTargetSelectScreen`，
    候选枚举从「请求器菜单自己遍历」收敛到 `SenderDelivery.receiverViews`（方块 / 终端共用）。
    判据原样重锚：唯一枚举、只提交名字、服务端现解析、空列表有话说、渲染不扫网络。
    """
    errors = []
    # 第 131 轮：候选枚举的唯一实现在 SenderDelivery ⇒ 判据只看传进来的这段文本（反例才可见）
    delivery = menu_text
    # ① 唯一一处网络枚举：只允许一个 graphContainers() 助手，两个消费者都走它
    if delivery.count("private static List<NetworkNodeContainer> graphContainers(") != 1:
        errors.append("网络枚举助手不是唯一一处（graphContainers 必须只有一个）")
    if delivery.count("GraphNetworkComponent.class") != 1:
        errors.append("出现了第二处 GraphNetworkComponent 枚举")
    if delivery.count("getContainers()") != 1:
        errors.append("出现了第二处 getContainers() 枚举（等于新写了一套）")
    if delivery.count("graphContainers(network)") != 2:
        errors.append("候选 / 解析没有共用同一个 graphContainers()（各自遍历 = 两套口径）")
    if "NetworkResourceReceiverNetworkNode" not in delivery:
        errors.append("候选不是按接收器节点筛的（与 receiversInNetwork() 不同源）")
    if "isProperlyConfigured()" not in delivery:
        errors.append("候选没有按 isProperlyConfigured() 过滤（未命名接收器会进列表）")
    if "receiverViews(" not in delivery:
        errors.append("缺少只读视图方法 receiverViews()")
    sync_to = method_body(strip_comments(read(REQ_BACKEND)), "public static void syncTo(") or ""
    if "receiverViews(" not in sync_to:
        errors.append("配置快照没有携带 receiverViews(...)（界面拿不到候选）")
    # 既有枚举仍然在（本轮不许为了「新视图」把它删掉 / 改名）
    if "receiversInNetwork()" not in receiver_be:
        errors.append("接收器方块实体既有的 receiversInNetwork() 不见了")
    if "occupiedReceiverNamesInNetwork()" not in receiver_be:
        errors.append("接收器方块实体既有的 occupiedReceiverNamesInNetwork() 不见了")

    # ② 提交仍然只有名字 + 服务端仍然校验存在性
    submit = method_body(screen_text, "private void confirmAndReturn(") or ""
    if "SetRequesterTargetPacket(" not in submit:
        errors.append("选定没有走既有 C2S SetRequesterTargetPacket")
    if ".name()" not in submit:
        errors.append("提交的不是名字（存储层只认名字）")
    if "setTargetName(" in screen_text:
        errors.append("客户端在写 setTargetName（必须由服务端写）")
    apply_target = method_body(strip_comments(read(REQ_BACKEND)),
                               "public static TargetApplyResult applyTarget(") or ""
    if "resolveReceiversByName(" not in apply_target:
        errors.append("服务端不再校验目标存在性")
    if "setTargetName(" not in apply_target:
        errors.append("服务端唯一写入点 setTargetName 不见了")
    # 视图只带名字 + 坐标
    if "public record ReceiverView(String name, int x, int y, int z)" not in packet_text:
        errors.append("ReceiverView 不是「名字 + 坐标」的纯视图")
    if "BlockEntity" in (method_body(packet_text, "public record ReceiverView") or ""):
        errors.append("ReceiverView 里夹带了方块实体引用（越权）")
    if "ByteBufCodecs.VAR_INT.encode(buf, view.x())" not in packet_text:
        errors.append("坐标没有随包下发（界面无法显示「哪一台」）")

    # ③ 客户端不再手打名字（子界面只有搜索框，没有名字输入框；数量框在网格里）
    if "targetBox" in screen_text:
        errors.append("界面里还有目标名输入框（玩家仍被要求手打名字）")
    if screen_text.count("new EditBox(") != 1:
        errors.append("目标子界面应当只有 1 个输入框（搜索框）；实际 %d 个"
                      % screen_text.count("new EditBox("))

    # ④ 空列表有提示（网格目标按钮 + 子界面空态）
    if "target.known.none" not in strip_comments(read(PICKER_SCREEN)):
        errors.append("候选为空时网格目标按钮没有提示")
    if 'LANG + "empty"' not in screen_text:
        errors.append("候选为空时子界面没有说明（玩家会以为界面坏了）")
    if "candidates.isEmpty()" not in screen_text:
        errors.append("界面没有处理空列表")

    # ⑤ 渲染循环不扫网络 / 不发包（只判传进来的这段屏幕文本：反例变异才可见）
    render_body = method_body(screen_text, "public void render(") or ""
    for bad in ("receiverViews(", "GraphNetworkComponent", "getNetworkOrNull(",
                "sendToServer"):
        if bad in render_body:
            errors.append("目标子界面 render 里出现 %s（每帧扫网络 / 发包）" % bad)
    return errors


def judge_lang_wording(zh, en):
    """语言键：没有「同名」；关键文案指向「选定的那台接收器」；空列表提示存在。"""
    errors = []
    for key, value in zh.items():
        if "同名" in str(value):
            errors.append("zh_cn 里仍有「同名」：%s" % key)
    for key, value in en.items():
        text = str(value).lower()
        if "same-named" in text or "same name" in text:
            errors.append("en_us 里仍有 same-name：%s" % key)
    if zh.get(REQ_PREFIX + "target.label") != "目标接收器":
        errors.append("target.label 不是「目标接收器」：%r" % zh.get(REQ_PREFIX + "target.label"))
    if en.get(REQ_PREFIX + "target.label") != "Target receiver":
        errors.append("target.label(en) 不是 Target receiver：%r"
                      % en.get(REQ_PREFIX + "target.label"))
    if "选定" not in str(zh.get(REQUESTER_HELP_KEY, "")):
        errors.append("方块帮助文本没有说明「投递给选定的那台接收器」")
    if "命名" not in str(zh.get(REQ_PREFIX + "target.known.none", "")):
        errors.append("空列表提示没有告诉玩家「先去命名」")
    # 重名拒绝的提示必须仍然存在（只是不再说「同名」）
    if "占用" not in str(zh.get(REQ_PREFIX + "msg.target_ambiguous", "")):
        errors.append("重名拒绝的提示被删掉了（错误的拒绝必须仍然有话可说）")
    if "占用" not in str(zh.get(RECV_PREFIX + "msg.name_taken", "")):
        errors.append("接收器改名冲突的提示被删掉了")
    return errors


def section_receiver_picker():
    section("⑧ 目标接收器：从列表里挑（复用既有枚举 / 仍提交名字 / 无「同名」）")
    # 第 131 轮：候选枚举的唯一实现在 SenderDelivery（target 写入在 RequesterSenderBackend）
    menu_text = strip_comments(read(SENDER_DELIVERY))
    screen_text = strip_comments(read(TARGET_SCREEN))
    packet_text = strip_comments(read(CONFIG_PACKET))
    receiver_be = strip_comments(read(RECEIVER_BE))
    zh, en = load_lang()

    errors = judge_receiver_picker(menu_text, screen_text, packet_text, receiver_be)
    check("⑧-1 候选来自既有枚举 / 仍提交名字 / 无手打输入 / 空列表有提示 / 渲染不扫网络",
          not errors, "；".join(errors[:6]))

    # 既有枚举的形态：private（所以菜单不可能直接调，只能复用同一段遍历 —— 这条解释了「复用」）
    check("⑧-2 接收器方块实体的 receiversInNetwork() 仍在且仍是 private（唯一枚举口径）",
          "private java.util.List<NetworkResourceReceiverBlockEntity> receiversInNetwork()"
          in receiver_be
          and "public Set<String> occupiedReceiverNamesInNetwork()" in receiver_be)

    # 只有一处遍历、一次 getContainers()：把「新写一套」挡在门外
    # 第 131 轮：枚举收在 SenderDelivery.graphContainers() 一个助手，两个消费者共用它
    check("⑧-3 只有一处网络图遍历（graphContainers() 助手 + getContainers() == 1）",
          menu_text.count("getContainers()") == 1
          and menu_text.count("GraphNetworkComponent.class") == 1
          and menu_text.count("private static List<NetworkNodeContainer> graphContainers(") == 1
          and menu_text.count("graphContainers(network)") == 2)

    # 视图字段：名字 + 坐标（不含方块实体 / 网络对象）
    check("⑧-4 S2C 视图 = 名字 + 三个坐标（无方块实体引用）",
          "public record ReceiverView(String name, int x, int y, int z)" in packet_text
          and "BlockEntity" not in (method_body(packet_text, "public record ReceiverView") or ""))

    # 提交语义：只发名字；服务端仍现解析
    submit = method_body(screen_text, "private void confirmAndReturn(") or ""
    check("⑧-5 「选定」只把名字交给既有 C2S 包（存储层不变）",
          ".name()" in submit and "SetRequesterTargetPacket(" in submit
          and "setTargetName(" not in screen_text)
    check("⑧-6 服务端仍校验「目标必须存在」（resolveReceiversByName 在写入之前）",
          order_ok(method_body(strip_comments(read(REQ_BACKEND)),
                               "public static TargetApplyResult applyTarget(") or "",
                   ["resolveReceiversByName(", "setTargetName("]))

    # 客户端不再手打名字（搜索框不是名字输入框）
    check("⑧-7 目标子界面没有名字输入框（只有 1 个搜索框）；数量框在网格里",
          "targetBox" not in screen_text and screen_text.count("new EditBox(") == 1)

    # 空列表提示（网格目标按钮 + 子界面空态 + isEmpty 分支）
    check("⑧-8 空列表有提示（网格目标按钮文案 + 子界面空态 + isEmpty 分支）",
          "target.known.none" in strip_comments(read(PICKER_SCREEN))
          and 'LANG + "empty"' in screen_text
          and "candidates.isEmpty()" in screen_text)

    # 语言键：没有「同名」
    errors = judge_lang_wording(zh, en)
    check("⑧-9 语言键里不再有「同名」/ same-name，且关键文案指向「选定的那台」",
          not errors, "；".join(errors[:6]))
    check("⑧-10 可复跑扫描：zh_cn 全表 0 条含「同名」",
          not [key for key, value in zh.items() if "同名" in str(value)],
          str([key for key, value in zh.items() if "同名" in str(value)][:3]))

    # ---- 反例自证：同一批判据必须判负 ----
    manual_typing = screen_text.replace(
        "new SetRequesterTargetPacket(containerId, selected.name())",
        "new SetRequesterTargetPacket(containerId, targetBox.getValue())").replace(
        "private EditBox searchBox;", "private EditBox searchBox;\n    private EditBox targetBox;")
    errors = judge_receiver_picker(menu_text, manual_typing, packet_text, receiver_be)
    check("⑧-11 反例·仍然手打名字（targetBox + getValue()）⇒ 判负", bool(errors),
          "；".join(errors[:3]))

    bad_lang_zh = dict(zh)
    bad_lang_zh[REQUESTER_HELP_KEY] = "红石脉冲触发；投递给同网络中同名的接收器。"
    errors = judge_lang_wording(bad_lang_zh, en)
    check("⑧-12 反例·语言里出现「同名」⇒ 判负", bool(errors), "；".join(errors[:3]))

    labels = method_body(screen_text, "public void render(") or ""
    per_frame = screen_text.replace(
        labels, labels.replace("{", "{\n        SenderDelivery.receiverViews(null);", 1), 1)
    errors = judge_receiver_picker(menu_text, per_frame, packet_text, receiver_be)
    check("⑧-13 反例·每帧扫网络（renderLabels 里调 receiverViews()）⇒ 判负", bool(errors),
          "；".join(errors[:3]))

    second_enum = menu_text.replace(
        "return graph == null ? List.of() : List.copyOf(graph.getContainers());",
        "return graph == null ? List.of() : List.copyOf(graph.getContainers())"
        "; network.getComponent(GraphNetworkComponent.class).getContainers(); // 第二套枚举", 1)
    errors = judge_receiver_picker(second_enum, screen_text, packet_text, receiver_be)
    check("⑧-14 反例·新写第二套网络枚举 ⇒ 判负", bool(errors), "；".join(errors[:3]))

    # ---- 反向自证：同一批判据对真实源码 0 失败 ----
    real_ok = (not judge_receiver_picker(menu_text, screen_text, packet_text, receiver_be)
               and not judge_lang_wording(zh, en))
    check("⑧-15 反向自证·同一批判据对真实源码 0 失败（判据有区分力）", real_ok)


# ======================================================================================
def main():
    print("=== 第 86 轮自检：网络资源接收器 / 请求器（配置 UI + 数据包） ===")
    section_source_validation()
    section_behaviour_model()
    section_registration_and_dist()
    section_limits_and_inputs()
    section_counterexamples()
    section_generic_resource_types()
    section_resource_picker()
    section_user_requirements()
    section_receiver_picker()
    print("")
    print("断言总数 %d，失败 %d" % (CHECKS[0], len(FAILURES)))
    for failure in FAILURES:
        print("  FAIL %s" % failure)
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
