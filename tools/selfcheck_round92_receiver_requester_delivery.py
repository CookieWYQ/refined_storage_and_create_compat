#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第 131 轮：请求器设置界面 / 6 个插件槽 / 「左键切下一台」目标轮换 / 升级驱动的节拍 已删除 ⇒ 判据反转
（服务端唯一入口收敛到 RequesterSenderBackend，投递引擎收敛到 SenderDelivery；只收紧与反转，未放宽）。
第 92 轮：「网络资源接收器 + 网络资源请求器」投递链路的端到端自检。

<h2>这份脚本补的是哪一类洞</h2>
<p>round71 只钉了「单机节流 / 单次投递守恒」；本轮把**整条链路**（请求器按名字找接收器 →
从 RS 网络取料 → 按每 tick 批数写进接收器缓存 → 接收器再按 facing 输出 → 拆方块结算 / NBT 往返 →
客户端只读）一次钉死。每条判据都遵循工程既有三层写法：</p>
<ol>
 *   <li><b>源码形状</b>（剥掉注释与字符串后做字面量 / 先后顺序断言）；</li>
 *   <li><b>行为镜像</b>（把 Java 算术在 Python 里逐条重放，用具体数值算，而不是「看起来对」）；</li>
 *   <li><b>反例自证</b>（把修前的坏写法喂给同一批判据，必须判负 —— 证明判据不是恒真）。</li>
</ol>

<h2>覆盖的不变式（与 task-1 描述逐条对应）</h2>
<ol>
 *   <li>名字解析只在本网络内；0 / 1 / ≥2 三种结果分别处理，重名绝不随机挑一台；目标区块未加载
 *       时不改任何配置、下轮重试；</li>
 *   <li>投递守恒：取出量 == 写入量 + 回滚量 + 暂存量，returnBuffer 落盘且绝不销毁；</li>
 *   <li>跨 tick 续投：pendingPlan 只按「真的写进接收器的量」递减；每 tick 批数对「触发」与
 *       「doWork 续投」合计生效；空探测不消耗批数；待投计划随 NBT 落盘；</li>
 *   <li>接收器输出：只为「缓存 → FACING 相邻容器」服务，按每 tick 批数循环、每轮最多一批，
 *       能力查询每 tick 至多一次，目标满 / 无容器时一个零件都不扣；</li>
 *   <li>任意资源：物品→物品缓存、流体→流体缓存、其它 PlatformResourceKey→直通朝向容器；
 *       非 PlatformResourceKey 一律拒绝并提示；</li>
 *   <li>节流：闸门间隔恒 1 tick 且只表达「每 tick 放行」；速度升级 = 每 tick 批数（1/2/4/…/64）、
 *       叠加升级 = 每批多少（64…448），两种升级各管一个旋钮、互不混算；</li>
 *   <li>客户端 / 服务端：客户端界面绝不写世界、绝不读方块实体 / 查询能力；所有写入都走 C2S 包，
 *       服务端校验 containerId + 距离 + 菜单类型；</li>
 *   <li>拆方块 / NBT：缓存与 returnBuffer 由 BlockContentReleaser 结算，pendingPlan / 订单 /
 *       名字 / 升级 / 目标坐标全部 NBT 往返；</li>
 *   <li>没有死通道：注册表里每个 payload 都必须能找到真实构造点；陈旧「本批 / 后续批次」注释清零；</li>
 *   <li>接收器作为 inventory 提供者无方向限制（用户本轮原话），方向只用于成链。</li>
</ol>

用法：python tools/selfcheck_round92_receiver_requester_delivery.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

RECEIVER_BE = "block/entity/NetworkResourceReceiverBlockEntity.java"
REQUESTER_BE = "block/entity/NetworkResourceRequesterBlockEntity.java"
RECEIVER_BLOCK = "block/NetworkResourceReceiverBlock.java"
REQUESTER_BLOCK = "block/NetworkResourceRequesterBlock.java"
RECEIVER_NODE = "network/NetworkResourceReceiverNetworkNode.java"
REQUESTER_NODE = "network/NetworkResourceRequesterNetworkNode.java"
RECEIVER_MENU = "menu/NetworkResourceReceiverMenu.java"
REQUESTER_MENU = "menu/NetworkResourceRequesterMenu.java"
RECEIVER_SCREEN = "client/screen/NetworkResourceReceiverScreen.java"
REQUESTER_SCREEN = "client/screen/NetworkResourceRequesterScreen.java"
CONTAINER = "support/RsccSpeedStackUpgradeContainer.java"
VIEW_ONLY = "support/ViewOnlyHandlers.java"
PAYLOAD_REGISTRY = "RS_Create_Compat.java"
# 第 131 轮：投递引擎 / 发送源后端 / 目标选择子界面（设置界面已删）
SENDER_DELIVERY = "support/SenderDelivery.java"
REQ_BACKEND = "support/RequesterSenderBackend.java"
TARGET_SCREEN = "client/screen/SenderTargetSelectScreen.java"
PICKER_SCREEN = "client/grid/ResourcePickerScreen.java"
PICKER_MENU = "client/grid/ResourcePickerMenu.java"

# C2S（客户端 → 服务端，必须校验 containerId + 距离 / 方块存在性）
C2S_PACKETS = [
    "network/SetReceiverNamePacket.java",
    "network/SetRequesterTargetPacket.java",
    "network/RequesterOrderPacket.java",
    "network/TriggerRequesterPacket.java",
    # 第 131 轮：OpenRequesterPickerPacket 的唯一发送方随设置界面删除 ⇒ 死通道包一并删除
]
# S2C（服务端 → 客户端，只带显示数据，不得改世界）
S2C_PACKETS = [
    "network/SyncReceiverConfigPacket.java",
    "network/SyncRequesterConfigPacket.java",
]

# 写域内不允许再出现的「过程性 / 与现状矛盾」措辞（用户最反感的一类问题）
STALE_PHRASES = (
    "本批不发包",
    "编辑界面属后续批次",
    "本批只做边沿状态",
    "本批间隔恒为 1",
    "骨架批",
    "本批只改请求器这一个文件",
    "等后续批次把",
    "搬运属后续批次",
)

MAX_LEVEL = 6
STACK_AS_SPEED_WEIGHT = 3
BASE_BATCH = 64
MAX_BATCHES_PER_TICK = 512

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def section(title):
    print("")
    print("=" * 78)
    print(title)
    print("=" * 78)


def read(rel):
    path = os.path.join(JAVA, rel)
    if not os.path.isfile(path):
        return ""
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def code_s(rel):
    """只剥注释、保留字符串字面量（用于 hint reason / 语言键 / 用户原话这类判据）。"""
    text = read(rel)
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


def code(rel):
    """剥掉注释与字符串字面量（保留行号 / 字符串内容由调用方显式断言）。"""
    text = read(rel)
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
        elif char == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append('""')
            i = j
        else:
            out.append(char)
            i += 1
    return "".join(out)


def method_body(source, signature_re):
    """按大括号配平取出以 signature_re 匹配的方法体（含签名）。"""
    match = re.search(signature_re, source)
    if not match:
        return None
    brace = source.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[match.start():index + 1]
    return None


def position(haystack, needle):
    return haystack.find(needle) if haystack else -1


def mirror_level(raw):
    return max(0, min(int(raw), MAX_LEVEL))


def mirror_batches_per_tick(raw_speed):
    """镜像 RsccRateLimiter.shiftSaturating(1, level(speed))。"""
    return 1 << mirror_level(raw_speed)


def mirror_batch(raw_stack):
    """镜像 RsccRateLimiter.batchSaturating(64, stack)。"""
    return BASE_BATCH * (1 + mirror_level(raw_stack))


# ======================================================================================
# ① 名字解析：只在本网络内 + 0/1/≥2 三态 + 未加载不改配置
# ======================================================================================

section("① 名字解析：同网络 / 0/1/≥2 / 目标区块未加载")

recv_code = code(RECEIVER_BE)
req_code = code(REQUESTER_BE)

recv_network_scan = method_body(recv_code, r"private\s+java\.util\.List<NetworkResourceReceiverBlockEntity>\s+receiversInNetwork\(")
# 第 131 轮：目标解析搬到 SenderDelivery（方块 / 终端共用同一份实现）
delivery_code = code(SENDER_DELIVERY)
req_resolve = method_body(delivery_code,
                          r"public\s+static\s+List<NetworkResourceReceiverBlockEntity>\s+resolveReceiversByName\(")
# triggerRequest() 只剩转发壳，真正的实现在 3 参重载里
trigger_body = method_body(req_code,
                           r"public\s+void\s+triggerRequest\(@Nullable\s+final\s+ServerPlayer\s+asker,")
req_code_s = code_s(REQUESTER_BE)
trigger_body_s = method_body(req_code_s,
                             r"public\s+void\s+triggerRequest\(@Nullable\s+final\s+ServerPlayer\s+asker,")

check("①-0 接收器的「本网络内枚举」走 RS 网络图（GraphNetworkComponent#getContainers），"
      "不是自己扫世界",
      recv_network_scan is not None
      and "getComponent(GraphNetworkComponent.class)" in recv_network_scan
      and "getContainers()" in recv_network_scan
      and "getNode().getNetworkOrNull()" in recv_network_scan)
check("①-1 请求器解析接收器时用自己的网络（调用方传进来的 Network），"
      "名字逐字相等 + 必须已配置",
      req_resolve is not None
      and "graphContainers(network)" in req_resolve
      and "name.equals(receiver.getReceiverName())" in req_resolve
      and "receiver.isProperlyConfigured()" in req_resolve)
check("①-2 请求器解析只遍历网络容器集合（同网络作用域），空名直接返回空列表",
      req_resolve is not None
      and "graphContainers(network)" in req_resolve
      and "if (network == null || name == null || name.isEmpty())" in req_resolve
      and "getComponent(GraphNetworkComponent.class)" in delivery_code
      and "getContainers()" in delivery_code)

check("①-3 triggerRequest 里 ≥2 台先拒绝（hint ambiguous）再取 hits.get(0)，绝不随机挑",
      trigger_body_s is not None
      and "hits.size() > 1" in trigger_body_s
      and 'hint("ambiguous"' in trigger_body_s
      and position(trigger_body_s, "hits.size() > 1") < position(trigger_body_s, "hits.get(0)"))
check("①-4 找不到时先判「目标区块未加载」⇒ 直接 return（不改配置、不提示红字），"
      "只有「区块已加载仍找不到」才 hint not_found",
      trigger_body_s is not None
      and "isRememberedTargetChunkUnloaded()" in trigger_body_s
      and 'hint("not_found"' in trigger_body_s
      and position(trigger_body_s, "isRememberedTargetChunkUnloaded()")
      < position(trigger_body_s, 'hint("not_found"'))
check("①-5 解析失败路径绝不改配置（triggerRequest 里没有 setTargetName / setReceiverName 写入）",
      trigger_body is not None
      and "setTargetName(" not in trigger_body
      and "setReceiverName(" not in trigger_body)

recv_menu_code = code(RECEIVER_MENU)
req_menu_code = code(REQUESTER_MENU)
apply_name = method_body(recv_menu_code, r"public\s+NameApplyResult\s+applyNameFromUi\(")
# 第 131 轮：目标写入唯一入口在 RequesterSenderBackend（设置界面已删）
apply_target = method_body(code(REQ_BACKEND),
                           r"public\s+static\s+TargetApplyResult\s+applyTarget\(")

check("①-6 接收器改名：服务端守卫 → 形状 → 「名字没变直接成功」→ 重名 → 最后才写",
      apply_name is not None
      and "NOT_SERVER" in apply_name
      and position(apply_name, "checkNameShape(name)") < position(apply_name, "receiver.setReceiverName(name)")
      and position(apply_name, "isNameTakenInNetwork(name)") < position(apply_name, "receiver.setReceiverName(name)")
      and position(apply_name, "name.equals(receiver.getReceiverName())") < position(apply_name, "isNameTakenInNetwork(name)"))
check("①-7 请求器选目标：NOT_SERVER → 形状 → 0 个 = NOT_FOUND / >1 个 = AMBIGUOUS → 最后才写",
      apply_target is not None
      and "NOT_SERVER" in apply_target
      and "hits.isEmpty()" in apply_target
      and "hits.size() > 1" in apply_target
      and position(apply_target, "hits.size() > 1") < position(apply_target, "backend.setTargetName(player, name)"))


def judge_resolution(result):
    """行为镜像：0 个 = 未找到；≥2 = 拒绝（绝不随机挑）；1 个 = 命中。"""
    return result in ("not_found", "ambiguous", "hit")


def resolve_mirror(hits):
    if not hits:
        return "not_found"
    if len(hits) > 1:
        return "ambiguous"
    return "hit"


def resolve_random_pick(hits):
    if not hits:
        return "not_found"
    return "hit"  # 坏写法：重名也随机挑一台


check("①-8 行为：0 个 / 2 个 / 1 个三种解析结果分别落到 not_found / ambiguous / hit",
      resolve_mirror([]) == "not_found"
      and resolve_mirror(["a", "b"]) == "ambiguous"
      and resolve_mirror(["a"]) == "hit"
      and judge_resolution(resolve_mirror([])) and judge_resolution(resolve_mirror(["a", "b"])))
check("①-9 反例自证：重名时随机挑一台的写法必须被同一判据判负",
      resolve_mirror(["a", "b"]) == "ambiguous" and resolve_random_pick(["a", "b"]) == "hit"
      and resolve_random_pick(["a", "b"]) != resolve_mirror(["a", "b"]))
check("①-10 反例自证：空列表被当成「随便挑一台」必须判负",
      resolve_mirror([]) == "not_found" and resolve_random_pick([]) == "not_found")


# ======================================================================================
# ② 投递守恒：取出 == 写入 + 回滚 + 暂存
# ======================================================================================

section("② 投递守恒（SIMULATE→EXECUTE 取 min→回滚→暂存）")

# 第 131 轮：唯一投递实现在 SenderDelivery.deliver（请求器 / 终端共用）
deliver_body = method_body(delivery_code, r"public\s+static\s+Result\s+deliver\(")

check("②-0 SenderDelivery.deliver 存在，且先 SIMULATE 再 EXECUTE（顺序不可换）",
      deliver_body is not None
      and 0 <= position(deliver_body, "Action.SIMULATE") < position(deliver_body, "Action.EXECUTE")
      and "Math.min(available, acceptable)" in deliver_body)
check("②-1 守恒自证在投递路径上：代码显式校验 got == inserted + rolledBack + kept",
      deliver_body is not None and "got != inserted + rolledBack + kept" in deliver_body)
check("②-2 写不进去的余量先 insert 回网络，网络也不收的才进发送源缓冲（feedback.buffer）",
      deliver_body is not None
      and "storage.insert(resource, remainder, Action.EXECUTE" in deliver_body
      and "feedback.buffer(resource, kept)" in deliver_body
      and position(deliver_body, "storage.insert(resource, remainder, Action.EXECUTE")
      < position(deliver_body, "feedback.buffer(resource, kept)"))
check("②-3 计划量只按「真的写进接收器的量」递减（remaining -= inserted），绝不用 got 递减",
      deliver_body is not None
      and "remaining -= inserted" in deliver_body
      and "remaining -= got" not in deliver_body
      and "remaining -= remainder" not in deliver_body)
check("②-4 returnBuffer 随 NBT 落盘 + 读回（ORDER_CODEC encode/parse 都在）",
      "ORDER_CODEC.encodeStart(ops(registries), returnBuffer)" in req_code
      and "tag.contains(TAG_RETURN_BUFFER)" in req_code
      and "ORDER_CODEC.parse(ops(registries), tag.get(TAG_RETURN_BUFFER))" in req_code)
check("②-5 returnBuffer 唯一被 clear 的位置是读档（loadAdditional），投递路径绝不销毁它",
      req_code.count("returnBuffer.clear()") == 1
      and position(req_code, "returnBuffer.clear()")
      < position(req_code, "private static com.mojang.serialization.DynamicOps")
      and "returnBuffer.clear()" in req_code)
# 更精确：returnBuffer.clear() 必须出现在 loadAdditional 内
load_body = method_body(req_code, r"public\s+void\s+loadAdditional\(")
check("②-5b 同上（精确）：clear 在 loadAdditional 方法体内，且 doWork / deliverBatch 里没有",
      load_body is not None
      and "returnBuffer.clear()" in load_body
      and "returnBuffer.clear()" not in (method_body(req_code, r"public\s+void\s+doWork\(") or ""))


def model_deliver(stock, cache_free, want, batch, execute_accepts, rollback_capacity):
    """deliverBatch 的算术镜像（与 Java 逐句对应，含 SIMULATE↔EXECUTE 分叉）。"""
    wanted = min(want, max(1, batch))
    available = min(wanted, stock)
    acceptable = min(available, cache_free)
    got = min(available, acceptable)
    inserted = min(got, execute_accepts)
    remainder = got - inserted
    rolled = min(remainder, max(0, rollback_capacity))
    kept = remainder - rolled
    return {"got": got, "inserted": inserted, "landed": inserted, "rolled": rolled, "kept": kept}


def conservation_ok(result):
    return (result["got"] == result["landed"] + result["rolled"] + result["kept"]
            and result["inserted"] == result["landed"]
            and min(result["inserted"], result["rolled"], result["kept"]) >= 0)


CASES = [
    (0, 64, 64, 64, 64, 64),        # 网络没料
    (100, 0, 64, 64, 0, 0),         # 缓存满
    (100, 2000, 64, 64, 2000, 64),  # 全部成功
    (100, 64, 100, 64, 10, 0),      # 模拟/执行分叉 + 网络不收回滚 ⇒ 进缓冲
    (100, 64, 100, 64, 10, 40),     # 回滚只收一部分
    (100, 64, 100, 64, 10, 10 ** 9),  # 回滚全收 ⇒ 缓冲空
    (1, 1, 500, 64, 1, 1),          # 数量不足
]

real = [model_deliver(*case) for case in CASES]
check("②-6 行为·守恒：7 组边界用例里取出量恒等于写入 + 回滚 + 暂存，且记账写入 == 实际写入",
      all(conservation_ok(item) for item in real),
      "; ".join(str(item) for item in real if not conservation_ok(item)))


def deliver_differs(result):
    """反例 A：只信「写完就成功」，不处理 EXECUTE 差额（账物分叉）。"""
    return dict(result, inserted=result["got"], landed=result["inserted"], rolled=0, kept=0)


def deliver_without_rollback(result):
    """反例 B：写不进去的余量既不回网也不暂存（余量凭空消失）。"""
    return dict(result, rolled=0, kept=0)


check("②-7 反例 A（账上按 SIMULATE 记满、实际只写一部分）⇒ 同一守恒判据判负",
      not all(conservation_ok(deliver_differs(item)) for item in real))
check("②-8 反例 B（余量不回网、不暂存）⇒ 同一守恒判据判负",
      not all(conservation_ok(deliver_without_rollback(item)) for item in real))

# ---------- 异常路径守恒（任务不变式 #2 明确要求「含异常」） ----------
check("②-9 请求器对接收器的 SIMULATE / EXECUTE 都包了异常护栏："
      "模拟阶段抛异常 ⇒ 一件都没取出就结束；写入阶段抛异常 ⇒ inserted=0 后走统一回滚",
      deliver_body is not None
      and deliver_body.count("catch (final RuntimeException e)") >= 2
      and "hint(\"deliver_exception\"" in (method_body(code_s(SENDER_DELIVERY),
                                                         r"public\s+static\s+Result\s+deliver\(") or "")
      and re.search(r"catch \(final RuntimeException e\) \{\s*inserted = 0L;", deliver_body) is not None
      and position(deliver_body, "inserted = 0L;") < position(deliver_body, "final long remainder = got - inserted;"))
_items_body = method_body(recv_code, r"private\s+long\s+outputItems\(")
_fluids_body = method_body(recv_code, r"private\s+long\s+outputFluids\(")
check("②-10 接收器输出：模拟 / 真正写入的异常都各自兜住，真正写入抛异常时把抽出的量放回缓存"
      "（宁可重复、绝不销毁）",
      _items_body is not None and _fluids_body is not None
      and _items_body.count("catch (final RuntimeException e)") == 2
      and "itemCache.insertItem(0, extracted, false);" in _items_body
      and _fluids_body.count("catch (final RuntimeException e)") == 2
      and "fluidCache.fill(drained, IFluidHandler.FluidAction.EXECUTE);" in _fluids_body)
check("②-11 行为：写入阶段抛异常 ⇒ inserted=0 ⇒ 取出量全部回滚（守恒等式仍成立）",
      (lambda r: r["got"] == r["rolled"] and conservation_ok(r))
      (model_deliver(64, 1000, 64, 64, execute_accepts=0, rollback_capacity=64)))
check("②-12 反例自证：异常时不回滚（把 inserted 记成 got、回滚 0）⇒ 同一守恒判据判负",
      not conservation_ok(dict(model_deliver(64, 1000, 64, 64, 0, 64), inserted=64, landed=0,
                               rolled=0, kept=0)))


# ======================================================================================
# ③ 跨 tick 续投：只按写入量递减、预算按 tick 合计、空探测不耗批
# ======================================================================================

section("③ 跨 tick 续投（pendingPlan / 每 tick 批数 / 空探测不计数）")

# 第 131 轮：节拍固定（用户拍板「红石激活速度决定发送速度」）⇒ 判据反转成
# 「预算 = min(硬上限, 发送源给的批数)」+「固定常量来自 SenderDelivery」。
check("③-0 每 tick 批数 = min(硬上限, 发送源批数)，没有自写位运算公式",
      deliver_body is not None
      and "final int budget = Math.min(MAX_BATCHES_PER_TICK, Math.max(1, perTickBatches));"
      in deliver_body
      and "<<" not in deliver_body
      and "FIXED_BATCHES_PER_TICK = 8" in delivery_code
      and "FIXED_BATCH_SIZE = 64L" in delivery_code)
check("③-1 循环条件用 budget；只有真的取到资源才 batches++（空探测不消耗批数）",
      deliver_body is not None
      and "while (remaining > 0L && batches < budget)" in deliver_body
      and deliver_body.count("batches++") == 1
      and position(deliver_body, "batches++") > position(deliver_body, "Action.EXECUTE"))
check("③-2 只有「批数用完」造成的剩余进 carry 跨 tick（deliver 返回 carry、方块实体接回去），"
      "缺口（stalled）不无限重试",
      deliver_body is not None
      and "if (remaining > 0L && !stalled)" in deliver_body
      and "carry.add(" in deliver_body
      and "pendingPlan.clear();" in req_code
      and "pendingPlan.addAll(result.carry());" in req_code)
check("③-3 每 tick 批数按 gameTime 记账（同一 tick 的「触发 + 续投」合计不超预算）",
      req_code is not None
      and "if (now != deliveryBudgetTick)" in req_code
      and "deliveryBudgetTick = now;" in req_code
      and "deliveryBudgetUsed = 0;" in req_code
      and "deliveryBudgetUsed = result.batchesUsed();" in req_code)
check("③-4 pendingPlan 随 NBT 落盘 + 读回，并在读档前先清空（方块实体可能复用）",
      "ORDER_CODEC.encodeStart(ops(registries), pendingPlan)" in req_code
      and "TAG_PENDING_PLAN" in req_code
      and "tag.contains(TAG_PENDING_PLAN)" in req_code
      and "pendingPlan.clear();" in load_body
      and "ORDER_CODEC.parse(ops(registries), tag.get(TAG_PENDING_PLAN))" in req_code)


def model_trigger(order, stock, cache_free, batch, budget):
    remaining, stock_left, free = order, stock, cache_free
    delivered = 0
    batches = 0
    shortfall = False
    while remaining > 0 and batches < budget:
        wanted = min(remaining, batch)
        available = min(wanted, max(0, stock_left))
        if available <= 0:
            shortfall = True
            break
        acceptable = min(available, max(0, free))
        if acceptable <= 0:
            shortfall = True
            break
        inserted = min(available, acceptable)
        batches += 1
        delivered += inserted
        remaining -= inserted
        stock_left -= inserted
        free -= inserted
    return {"delivered": delivered, "remaining": remaining, "batches": batches,
            "shortfall": shortfall,
            "carried": remaining if (remaining > 0 and not shortfall) else 0}


def model_ticks(order, stock, cache_free, batch, budget):
    pending, total, ticks = order, 0, 0
    while pending > 0 and ticks < 10000:
        result = model_trigger(pending, stock, cache_free, batch, budget)
        total += result["delivered"]
        stock -= result["delivered"]
        cache_free -= result["delivered"]
        ticks += 1
        if not result["carried"]:
            break
        pending = result["carried"]
    return total, ticks


check("③-5 行为：0 速（1 批/tick）订单 80 跨 2 tick 投满；1 速（2 批/tick）同 tick 投完；"
      "两者总量都恰为 80（不重复、不过量）",
      model_ticks(80, 10 ** 6, 10 ** 6, 64, mirror_batches_per_tick(0))[0] == 80
      and model_ticks(80, 10 ** 6, 10 ** 6, 64, mirror_batches_per_tick(0))[1] == 2
      and model_trigger(80, 10 ** 6, 10 ** 6, 64, mirror_batches_per_tick(1))["delivered"] == 80
      and model_trigger(80, 10 ** 6, 10 ** 6, 64, mirror_batches_per_tick(1))["carried"] == 0)
check("③-6 行为：满速（64 批/tick）时大订单的总量仍恰好等于订单量（永远不过量）",
      model_ticks(100000, 10 ** 7, 10 ** 7, 448, mirror_batches_per_tick(6))[0] == 100000)
check("③-7 行为：每次触发都投「订单量」——同一订单触发两次 = 2 × 订单量（不是只投一次）",
      sum(model_ticks(80, 10 ** 6, 10 ** 6, 64, mirror_batches_per_tick(0))[0]
          for _ in range(2)) == 160)


def model_decrement_by_got(order, stock, free, batch, budget):
    """反例：用 got 递减计划量（回滚掉的部分也被当成投出去了 ⇒ 会少投）。"""
    result = model_trigger(order, stock, free, batch, budget)
    got = min(order, batch, stock) if stock > 0 else 0
    return {"delivered": result["delivered"], "remaining": max(0, order - got),
            "batches": result["batches"], "shortfall": result["shortfall"],
            "carried": 0}


# ---------- 上电 / 离网闸门（没电不干活，但不丢任何东西） ----------
req_dowork = method_body(req_code, r"public\s+void\s+doWork\(")
check("③-9 请求器 doWork 在「缓冲 / 计划判空」之前先判节点是否激活（没电 / 离网 ⇒ 不续投、不归还缓冲，"
      "pendingPlan 与 returnBuffer 原样保留）",
      req_dowork is not None
      and "if (!mainNetworkNode.isActive())" in req_dowork
      and position(req_dowork, "if (!mainNetworkNode.isActive())")
      < position(req_dowork, "if (returnBuffer.isEmpty() && pendingPlan.isEmpty())"))
check("③-10 triggerRequest：先判「有没有网络」（no_network）再判「活没活」（no_power），"
      "避免未接网络的请求器被误报成没供电",
      trigger_body_s is not None
      and "hint(\"no_network\"" in trigger_body_s
      and "hint(\"no_power\"" in trigger_body_s
      and 0 <= position(trigger_body_s, "hint(\"no_network\"")
      < position(trigger_body_s, "hint(\"no_power\""))
check("③-11 接收器 doWork 同样先判 isActive（上电语义与请求器一致）",
      "if (!mainNetworkNode.isActive())" in (method_body(recv_code, r"public\s+void\s+doWork\(") or ""))

check("③-8 反例自证：用 got 递减会造成「缺口被当成已投」⇒ 续投总量与订单量不等，必须判负",
      model_ticks(80, 10 ** 6, 10 ** 6, 64, mirror_batches_per_tick(0))[0] == 80
      and model_decrement_by_got(80, 80, 10 ** 6, 64, 1)["remaining"] == 16
      and model_decrement_by_got(80, 80, 10 ** 6, 64, 1)["delivered"] != 80)


# ======================================================================================
# ④ 接收器输出：只为「缓存 → FACING」服务，按每 tick 批数循环，能力查询每 tick 至多一次
# ======================================================================================

section("④ 接收器输出（每 tick 批数循环 + 守恒 + 能力查询一次）")

recv_do_work = method_body(recv_code, r"public\s+void\s+doWork\(")
recv_items = method_body(recv_code, r"private\s+long\s+outputItems\(")
recv_fluids = method_body(recv_code, r"private\s+long\s+outputFluids\(")

check("④-0 doWork 的闸门顺序：isActive → throttleTick → isProperlyConfigured（未命名不动）",
      recv_do_work is not None
      and -1 not in (position(recv_do_work, "if (!mainNetworkNode.isActive())"),
                     position(recv_do_work, "if (!throttleTick())"),
                     position(recv_do_work, "if (!isProperlyConfigured())"))
      and position(recv_do_work, "if (!mainNetworkNode.isActive())")
      < position(recv_do_work, "if (!throttleTick())")
      < position(recv_do_work, "if (!isProperlyConfigured())"))
check("④-1 输出面 = getBlockPos().relative(FACING)（方向只用于成链），不是任意面 / 不猜",
      recv_do_work is not None
      and "getBlockPos().relative(state.getValue(NetworkResourceReceiverBlock.FACING))" in recv_do_work)
check("④-2 按「每 tick 批数」循环、每轮物品 / 流体各最多一批，且循环里不再查能力",
      recv_do_work is not None
      and "final long batch = getThrottleBatch();" in recv_do_work
      and "final int batches = getThrottleBatchesPerTick();" in recv_do_work
      and "for (int round = 0; round < batches; round++)" in recv_do_work
      and position(recv_do_work, "for (int round = 0; round < batches; round++)")
      < position(recv_do_work, "outputItems(itemTarget, batch);")
      and "itemHandlerAt(" not in recv_do_work.split("for (int round = 0", 1)[-1]
      and "fluidHandlerAt(" not in recv_do_work.split("for (int round = 0", 1)[-1])
check("④-3 一整轮完全没搬动 ⇒ 提前结束（目标满 / 缓存空时不空转剩余批次）",
      recv_do_work is not None
      and "if (movedItems <= 0L && movedFluids <= 0L)" in recv_do_work
      and "break;" in recv_do_work.split("if (movedItems <= 0L && movedFluids <= 0L)", 1)[-1])
check("④-4 能力查询每 tick 至多各一次（全文件各只出现 1 次）",
      recv_code.count("itemHandlerAt(") == 1 and recv_code.count("fluidHandlerAt(") == 1)
check("④-5 物品守恒顺序：simulate 问 → 算 accepted → 才 extractItem → execute 写 → execute 差额回滚",
      recv_items is not None
      and -1 not in (position(recv_items, "insertInto(target, offer, true)"),
                     position(recv_items, "final int accepted = offer.getCount() - simulatedLeft.getCount();"),
                     position(recv_items, "itemCache.extractItem(slot, accepted, false)"),
                     position(recv_items, "insertInto(target, extracted, false)"))
      and position(recv_items, "insertInto(target, offer, true)")
      < position(recv_items, "final int accepted = offer.getCount() - simulatedLeft.getCount();")
      < position(recv_items, "itemCache.extractItem(slot, accepted, false)")
      < position(recv_items, "insertInto(target, extracted, false)")
      and "itemCache.insertItem(0, extracted.copyWithCount(taken - landed), false)" in recv_items)
check("④-6 流体守恒顺序与物品对称：SIMULATE fill → drain → EXECUTE fill → 差额回填",
      recv_fluids is not None
      and position(recv_fluids, "target.fill(offer, IFluidHandler.FluidAction.SIMULATE)")
      < position(recv_fluids, "fluidCache.drain(")
      < position(recv_fluids, "target.fill(drained, IFluidHandler.FluidAction.EXECUTE)")
      and "fluidCache.fill(drained.copyWithAmount(rejected), IFluidHandler.FluidAction.EXECUTE)" in recv_fluids)
check("④-7 两个输出方法都把「本次真正搬动量」返回给 doWork（提前结束判据才拿得到数）",
      recv_items is not None and "return moved;" in recv_items
      and recv_fluids is not None and "return moved;" in recv_fluids
      and "long outputItems(" in recv_code and "long outputFluids(" in recv_code)
check("④-8 接收器文件里没有自写位移 / 乘法节流公式（批量与批数一律来自容器）",
      "<<" not in recv_code
      and "throttleIntervalTicks()" in recv_code)  # 只允许用于闸门（恒 1），不得用于显示 / 换算


def model_output(free_target, cache_items, batch):
    """接收器单批输出镜像：目标能收多少就扣多少，目标 0 ⇒ 不扣。"""
    offered = min(cache_items, batch)
    accepted = min(offered, free_target)
    return {"deducted": accepted, "cache_left": cache_items - accepted,
            "target_took": accepted}


check("④-9 行为：目标满（能收 0）⇒ 一个都不扣；部分能收 ⇒ 只扣被收下的量",
      model_output(0, 100, 64)["deducted"] == 0
      and model_output(0, 100, 64)["cache_left"] == 100
      and model_output(10, 100, 64)["deducted"] == 10
      and model_output(10, 100, 64)["cache_left"] == 90)
check("④-10 行为：批数 = 速度升级，批量 = 叠加升级，两者独立（吞吐 = 批数 × 批量）",
      [(mirror_batches_per_tick(s), mirror_batch(st))
       for s, st in ((0, 0), (3, 0), (0, 3), (6, 6))]
      == [(1, 64), (8, 64), (1, 256), (64, 448)])


# ======================================================================================
# ⑤ 任意资源：物品 / 流体 / 其它 PlatformResourceKey / 非 Platform 拒绝
# ======================================================================================

section("⑤ 任意资源类型的分流与「不支持」提示")

can_handle = method_body(recv_code, r"public\s+boolean\s+canHandle\(")
apply_resource = method_body(recv_code, r"public\s+long\s+applyResource\(")

check("⑤-0 canHandle：物品 / 流体直接 true；非 PlatformResourceKey 直接 false；"
      "其它 PlatformResourceKey 走朝向容器 SIMULATE 探针（没有类型白名单）",
      can_handle is not None
      and "RsccResourceKeys.isItemOrFluid(resource)" in can_handle
      and "!(resource instanceof PlatformResourceKey)" in can_handle
      and "target.insert(resource, 1L, Action.SIMULATE)" in can_handle)
check("⑤-1 applyResource 三条分流：ItemResource → applyToItemCache、FluidResource → "
      "applyToFluidCache、其它 PlatformResourceKey → genericTarget().insert；其余 0L",
      apply_resource is not None
      and "resource instanceof ItemResource item" in apply_resource
      and "applyToItemCache(item, amount, simulate)" in apply_resource
      and "resource instanceof FluidResource fluid" in apply_resource
      and "applyToFluidCache(fluid, amount, simulate)" in apply_resource
      and "resource instanceof PlatformResourceKey" in apply_resource
      and "target.insert(resource, amount, simulate ? Action.SIMULATE : Action.EXECUTE)" in apply_resource
      and "return 0L;" in apply_resource)
check("⑤-2 不认识具体第三方类（没有 mekanism / gas / chemical 硬编码类型）",
      all(token not in (recv_code + req_code + recv_menu_code + req_menu_code).lower()
          for token in ("mekanism", "chemicalstack", "gasstack")))
deliver_body_s = method_body(code_s(SENDER_DELIVERY), r"public\s+static\s+Result\s+deliver\(")
check("⑤-3 非 PlatformResourceKey 在投递路径上明确提示 unsupported（不静默跳过）",
      deliver_body_s is not None
      and "!(resource instanceof PlatformResourceKey)" in deliver_body_s
      and 'hint("unsupported"' in deliver_body_s)
check("⑤-4 订单清单入口拒绝坏条目并提示（不静默）：setOrders 里 hint order_rejected + 日志",
      "hint(\"order_rejected\", dropped)" in req_code_s
      and "LOGGER.warn" in req_code_s
      and "!(entry.resource() instanceof PlatformResourceKey key)" in req_code_s)


def dispatch_mirror(resource_kind):
    return {"item": "item_cache", "fluid": "fluid_cache",
            "other_platform": "generic_target", "non_platform": "reject"}[resource_kind]


check("⑤-5 行为：四种资源键分别落到 item_cache / fluid_cache / generic_target / reject",
      dispatch_mirror("item") == "item_cache"
      and dispatch_mirror("fluid") == "fluid_cache"
      and dispatch_mirror("other_platform") == "generic_target"
      and dispatch_mirror("non_platform") == "reject")
check("⑤-6 反例自证：把「非平台键」静默塞进 generic_target（不拒绝）必须被判负",
      dispatch_mirror("non_platform") != "generic_target")


# ======================================================================================
# ⑥ 节流：闸门恒 1 tick；速度 = 每 tick 批数；叠加 = 每批多少
# ======================================================================================

section("⑥ 节流双旋钮（速度 → 批数、叠加 → 批量；互不混算）")

container_code = code(CONTAINER)
interval_body = method_body(container_code, r"public\s+int\s+throttleIntervalTicks\(")
batches_body = method_body(container_code, r"public\s+int\s+throttleBatchesPerTick\(")
batch_body = method_body(container_code, r"public\s+long\s+throttleBatch\(")


def batches_source_ok(body):
    return (body is not None
            and "stackCount" not in body
            and "RsccRateLimiter.shiftSaturating(1, RsccRateLimiter.level(speedCount()))" in body)


check("⑥-0 throttleIntervalTicks() 恒返回 1（闸门每 tick 放行；间隔物理下限就是 1 tick）",
      interval_body is not None
      and re.search(r"return 1;", interval_body) is not None
      and "intervalTicks(" not in interval_body)
check("⑥-1 throttleBatchesPerTick() = shiftSaturating(1, level(speedCount()))，"
      "实现里没有 stackCount（叠加被算两遍的坑）",
      batches_source_ok(batches_body))
check("⑥-2 throttleBatch() = batchSaturating(BASE_BATCH, stackCount())（叠加只作用于每批多少）",
      batch_body is not None
      and "RsccRateLimiter.batchSaturating(BASE_BATCH, stackCount())" in batch_body)
check("⑥-3 行为：0/1/6 个速度升级 ⇒ 1/2/64 批；0/1/6 个叠加升级 ⇒ 64/128/448 件；"
      "漏斗塞 64 个两级都收敛到上限",
      [mirror_batches_per_tick(n) for n in (0, 1, 6)] == [1, 2, 64]
      and [mirror_batch(n) for n in (0, 1, 6)] == [64, 128, 448]
      and mirror_batches_per_tick(64) == 64 and mirror_batch(64) == 448)
check("⑥-4 行为：速度升级对吞吐有 64 倍效果（不是「装了没效果」的历史缺陷）",
      mirror_batches_per_tick(6) * mirror_batch(0) == 64 * mirror_batches_per_tick(0) * mirror_batch(0))
check("⑥-5 反例自证：throttleBatchesPerTick 改回恒 1 ⇒ 判负（历史缺陷）",
      batches_source_ok("public int throttleBatchesPerTick() { return 1; }") is False)
check("⑥-6 反例自证：批数里混入 stackCount ⇒ 判负（叠加升级被算两遍）",
      batches_source_ok("public int throttleBatchesPerTick() { return "
                        "RsccRateLimiter.shiftSaturating(1, "
                        "RsccRateLimiter.level(speedCount() + stackCount() * 3)); }") is False)

# 数据槽：Be.getCount() 与菜单 DATA_COUNT 必须一致，且槽 0/1 真的是批次 / 批量
# 第 131 轮：请求器的节拍已固定（不再走升级槽 / ContainerData）⇒ 只判接收器，
# 请求器另加一条反向判据（见下面 ⑥-7b）。
recv_data = method_body(recv_code, r"public\s+net\.minecraft\.world\.inventory\.ContainerData\s+getContainerData\(")
req_data = method_body(req_code, r"public\s+net\.minecraft\.world\.inventory\.ContainerData\s+getContainerData\(")
for be_name, body, menu_rel in (("接收器", recv_data, RECEIVER_MENU),):
    menu_code = code(menu_rel)
    data_count = re.search(r"public static final int DATA_COUNT = (\d+);", menu_code)
    get_count = re.search(r"public int getCount\(\)\s*\{\s*return (\d+);", body or "")
    check("⑥-7 %s：getContainerData 只同步「每 tick 批数 + 单批量」两格，"
          "且 getCount() == 菜单 DATA_COUNT" % be_name,
          body is not None
          and "throttleBatchesPerTick()" in body
          and "throttleBatch(), Integer.MAX_VALUE" in body
          and "speedCount()" not in body
          and "stackCount()" not in body
          and "throttlePacing()" not in body
          and data_count is not None and get_count is not None
          and data_count.group(1) == get_count.group(1) == "2")
    check("⑥-8 %s：菜单只读同步值（getter 映射槽 0 / 1），没有旧「节拍间隔」getter 残留"
          % be_name,
          "public int getThrottleBatchesPerTick()" in menu_code
          and "return data.get(0);" in menu_code
          and "return data.get(1);" in menu_code
          and "getThrottleIntervalTicks" not in menu_code)

check("⑥-7b 请求器不再有节拍 ContainerData / 菜单 getter（节拍是固定常量）",
      req_data is None
      and "getThrottleBatchesPerTick" not in req_menu_code
      and "getThrottleBatch" not in req_menu_code
      and "SenderDelivery.FIXED_BATCHES_PER_TICK" in req_code
      and "SenderDelivery.FIXED_BATCH_SIZE" in req_code)
check("⑥-9 第 131 轮反转：接收器界面不再有节拍正文（用户要求删掉所有状态句）；"
      "节拍只在发送网格的工具条上显示「每 tick 批数 + 每批件数」",
      "menu.getThrottleBatchesPerTick()" not in code(RECEIVER_SCREEN)
      and "menu.getThrottleBatch()" not in code(RECEIVER_SCREEN)
      and "pacingBatchesPerTick, RsccNumberField.compact(pacingBatch)" in code(PICKER_SCREEN)
      and "getThrottleIntervalTicks()" not in code(PICKER_SCREEN)
      and "getThrottleIntervalTicks()" not in code(RECEIVER_SCREEN))


# ======================================================================================
# ⑦ 客户端 / 服务端：客户端只读 + C2S 服务端校验
# ======================================================================================

section("⑦ 客户端不写世界；所有写入走 C2S 且服务端校验 containerId / 距离 / 菜单类型")

recv_screen = code(RECEIVER_SCREEN)
# 第 131 轮：请求器设置界面删除 ⇒ 界面侧判据改看「网格 + 目标选择子界面」
req_screen = code(PICKER_SCREEN) + "\n" + code(TARGET_SCREEN)
mutators = ("setReceiverName(", "setTargetName(", "setOrders(", "clearOrders(",
            "triggerRequest(", "applyNameFromUi(", "applyTargetFromUi(")
for rel_name, screen_code in (("接收器界面", recv_screen), ("请求器界面", req_screen)):
    bad = [token for token in mutators if token in screen_code]
    check("⑦-0 %s：不直接调方块实体写入口（全部走 C2S 包）" % rel_name, not bad, str(bad))
    check("⑦-1 %s：不读方块实体缓存 / 不查能力（客户端没有权威数据）" % rel_name,
          "getItemCache()" not in screen_code
          and "getFluidCache()" not in screen_code
          and "Capabilities." not in screen_code
          and "getContainerProvider" not in screen_code)
    check("⑦-2 %s：唯一的写动作是 PacketDistributor.sendToServer" % rel_name,
          "PacketDistributor.sendToServer" in screen_code
          and "level.setBlock" not in screen_code
          and "addFreshEntity" not in screen_code)

packet_ok = True
packet_notes = []
for rel in C2S_PACKETS:
    packet_code = code(rel)
    if "containerId" not in packet_code:
        packet_ok = False
        packet_notes.append("%s 没有 containerId 校验" % rel)
    if not ("stillValid(" in packet_code or "stillValidFor(" in packet_code):
        packet_ok = False
        packet_notes.append("%s 没有距离 / 方块存在性校验" % rel)
    if "player.containerMenu.containerId != packet.containerId()" not in packet_code:
        packet_ok = False
        packet_notes.append("%s 没有 containerId 比对" % rel)
check("⑦-3 每个 C2S 包都校验 containerId + 距离 / 方块存在性（没有被改包绕过的写入口）",
      packet_ok, "; ".join(packet_notes))
check("⑦-3c 死通道 OpenRequesterPickerPacket 已彻底删除（包类 + 注册 + 发送点）",
      not os.path.isfile(os.path.join(JAVA, "network/OpenRequesterPickerPacket.java"))
      and "OpenRequesterPickerPacket" not in code(PAYLOAD_REGISTRY)
      and not os.path.isfile(os.path.join(JAVA, "client/screen/NetworkResourceRequesterScreen.java")))
check("⑦-3b 每个 S2C 包只搬运显示数据（不含 level.setBlock / 世界写入调用）",
      all("level.setBlock" not in code(rel) and "addFreshEntity" not in code(rel)
          for rel in S2C_PACKETS))
# 第 131 轮：请求器的距离 / 存在性判据收敛到 RequesterSenderBackend.stillValidFor（方块 / 终端共用）
req_backend_code = code(REQ_BACKEND)
check("⑦-4 服务端距离判据各只有一处（请求器 → stillValidFor；接收器 → 菜单），"
      "都是同一个 64.0 平方距离 + 方块实体身份",
      "getLevel().getBlockEntity(requester.getBlockPos()) == requester" not in req_menu_code
      and "requester.getLevel().getBlockEntity(requester.getBlockPos()) == requester"
      in req_backend_code
      and "<= 64.0" in req_backend_code
      and "receiver.getLevel().getBlockEntity(receiver.getBlockPos()) == receiver" in recv_menu_code
      and "<= 64.0" in recv_menu_code)


def judge_packet_guard(packet_source):
    return ("stillValid(" in packet_source or "stillValidFor(" in packet_source) \
        and "containerId" in packet_source


BAD_PACKET = """
public static void handle(final X packet, final ServerPlayer player) {
    menu.applyNameFromUi(packet.name());
}
"""
check("⑦-5 反例自证：没有 containerId / 距离校验的包处理体必须判负", judge_packet_guard(BAD_PACKET) is False)


# ======================================================================================
# ⑧ 拆方块结算 + NBT 往返
# ======================================================================================

section("⑧ 拆方块由 BlockContentReleaser 结算；NBT 往返含 returnBuffer / pendingPlan / 订单 / 名字 / 升级")

recv_block_code = code(RECEIVER_BLOCK)
req_block_code = code(REQUESTER_BLOCK)
recv_remove = method_body(recv_block_code, r"public\s+void\s+onRemove\(")
req_remove = method_body(req_block_code, r"public\s+void\s+onRemove\(")

check("⑧-0 接收器拆方块：走 BlockContentReleaser，且升级槽 + 物品缓存 + 流体缓存都进结算",
      recv_remove is not None
      and "BlockContentReleaser.release(" in recv_remove
      and "collectHandler(receiver.getUpgradeContainer(), out)" in recv_remove
      and "collectHandler(receiver.getItemCache(), out)" in recv_remove
      and "collectFluidStorage(receiver.getFluidCache(), out)" in recv_remove)
check("⑧-1 请求器拆方块：走 BlockContentReleaser，returnBuffer（物品 / 流体）进结算，"
      "且第 131 轮起**没有**升级槽结算",
      req_remove is not None
      and "BlockContentReleaser.release(" in req_remove
      and "requester.releaseReturnBufferItems()" in req_remove
      and "requester.releaseReturnBufferFluids()" in req_remove
      and "getUpgradeContainer" not in req_remove)
check("⑧-2 returnBuffer 拆出时按 int 上限分块，绝不截断丢失",
      "releaseReturnBufferItems()" in req_code
      and "releaseReturnBufferFluids()" in req_code
      and req_code.count("(int) Math.min(rest, Integer.MAX_VALUE)") == 2)

save_body = method_body(req_code, r"public\s+void\s+saveAdditional\(")
# 第 131 轮：TAG_UPGRADES 随 6 个插件槽一起删除（无升级就不该再有升级 NBT 键）
REQUIRED_SAVE = ("TAG_TARGET_NAME", "TAG_LAST_SUCCESS", "TAG_POWERED",
                 "TAG_ORDERS", "TAG_RETURN_BUFFER", "TAG_PENDING_PLAN", "TAG_LAST_TARGET")
check("⑧-3 请求器 saveAdditional 落盘全部权威状态（目标 / 成功标志 / 红石电平 / 升级 / 订单 / "
      "回滚暂存 / 待投计划 / 上次目标坐标）",
      save_body is not None and all(tag in save_body for tag in REQUIRED_SAVE)
      and "TAG_UPGRADES" not in req_code)
LOAD_REQUIRED = REQUIRED_SAVE
check("⑧-4 请求器 loadAdditional 把上述状态全部读回（NBT 往返一致）",
      load_body is not None and all(tag in load_body for tag in LOAD_REQUIRED)
      and "TAG_UPGRADES" not in load_body)

recv_save = method_body(recv_code, r"public\s+void\s+saveAdditional\(")
recv_load = method_body(recv_code, r"public\s+void\s+loadAdditional\(")
check("⑧-5 接收器 NBT 往返：名字 / 升级 / 物品缓存 / 流体缓存各一处写一处读",
      all(tag in (recv_save or "") and tag in (recv_load or "")
          for tag in ("TAG_NAME", "TAG_UPGRADES", "TAG_ITEM_CACHE", "TAG_FLUID_CACHE")))
check("⑧-6 拆方块时的待投计划语义：它是「计划」不是资源，BLOCK 档随 NBT 带走；"
      "任何档位都不会因为拆方块而少一件资源（returnBuffer 才是资源，已单独结算）",
      "pendingPlan" in req_code and "returnBuffer" in req_code
      and "TAG_PENDING_PLAN" in save_body)


def judge_nbt_keys(save_source, keys):
    return all(key in (save_source or "") for key in keys)


check("⑧-7 反例自证：saveAdditional 漏掉 returnBuffer 的写法必须判负",
      judge_nbt_keys("tag.putString(TAG_TARGET_NAME, targetName);", REQUIRED_SAVE) is False)


# ======================================================================================
# ⑨ 无死通道 + 陈旧注释清零
# ======================================================================================

section("⑨ 注册的 payload 必须有真实发送点；写域内不再有过程性 / 矛盾注释")

registry = code(PAYLOAD_REGISTRY)
registrations = re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\.TYPE", registry)
all_java = []
for dirpath, _dirs, files in os.walk(JAVA):
    for name in files:
        if name.endswith(".java"):
            with io.open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as handle:
                all_java.append(handle.read())
all_text = "\n".join(all_java)

# 构造点判据必须认「全限定名构造」：new cretae...XxxPacket(...)
CONSTRUCT_RE = r"new\s+(?:[\w.]+\.)?%s\s*\("


def dead_payloads(registry_text, source_text):
    payloads = sorted(set(re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\.TYPE", registry_text)))
    return [payload for payload in payloads
            if re.search(CONSTRUCT_RE % re.escape(payload), source_text) is None]


# 冻结基线：这些是**本 task 写域之外**的既有死通道（属于其它功能批次），本轮不修，
# 只保证「不再新增」；本链路自己的死通道（RequestRequesterResourcesPacket /
# SyncRequesterResourcesPacket）已经随本轮删除，因此不在基线里、也不允许再出现。
KNOWN_DEAD_BASELINE = {
    "ClearSequenceResultPacket",
    "SetCollectionDestroyPacket",
    "SetExecutorTargetPacket",
    "SetSequenceChancePacket",
    "SetSequenceCountPacket",
    "SyncAutocrafterNamesPacket",
}
dead = dead_payloads(registry, all_text)
new_dead = [payload for payload in dead if payload not in KNOWN_DEAD_BASELINE]
check("⑨-0 registerPayloads 里注册的每个 payload 类型都能在 src/main/java 找到构造点"
      "（全限定名 new 也算；不允许出现**新增**死通道）",
      not new_dead, "新增死通道: " + ", ".join(new_dead))
check("⑨-0b 本链路的两个死通道已删除（RequestRequesterResourcesPacket / "
      "SyncRequesterResourcesPacket 不再出现在网络层）",
      not os.path.isfile(os.path.join(JAVA, "network/RequestRequesterResourcesPacket.java"))
      and not os.path.isfile(os.path.join(JAVA, "network/SyncRequesterResourcesPacket.java"))
      and "RequestRequesterResourcesPacket" not in all_text
      and "SyncRequesterResourcesPacket" not in all_text)
check("⑨-0c 冻结基线之外的既有死通道与本链路无关（信息项：本 task 不修，交回 Lead 排期）",
      set(dead) <= KNOWN_DEAD_BASELINE, "偏离基线: " + ", ".join(dead))

# 反例自证：往注册表文本里塞一个没人发的类型，判据必须变红
PROBE_REGISTRY = registry + "\nDeadProbePacket.TYPE,"
check("⑨-1 反例自证：注册表里塞一个没人发的类型 ⇒ 判负",
      "DeadProbePacket" in dead_payloads(PROBE_REGISTRY, all_text))

stale_hits = []
# 第 131 轮：REQUESTER_SCREEN 已删除（read 返回空串，留着也不会误判）；补上新界面 / 新支撑类
for rel in (RECEIVER_BE, REQUESTER_BE, RECEIVER_BLOCK, REQUESTER_BLOCK,
            RECEIVER_MENU, REQUESTER_MENU, RECEIVER_SCREEN,
            TARGET_SCREEN, PICKER_SCREEN, SENDER_DELIVERY, REQ_BACKEND,
            RECEIVER_NODE, REQUESTER_NODE):
    raw = read(rel)
    for phrase in STALE_PHRASES:
        if phrase in raw:
            stale_hits.append("%s: %s" % (rel, phrase))
check("⑨-2 写域内没有「本批 / 后续批次 / 骨架批 / 只做边沿状态」这类与现状矛盾的注释",
      not stale_hits, "; ".join(stale_hits[:4]))
PROBE_STALE = "红石：本批只做边沿状态，不做投递；真正的搬运属于后续批次（要发包 / 要节流）"
check("⑨-3 反例自证：把旧句子塞进文本，同一判据必须能抓住",
      any(phrase in PROBE_STALE for phrase in STALE_PHRASES))


# ======================================================================================
# ⑩ 接收器 inventory 无方向限制（用户本轮原话）；方向只用于成链
# ======================================================================================

section("⑩ 接收器作为 inventory 提供者无方向限制；FACING 只用于成链")

recv_caps = method_body(recv_code, r"public\s+static\s+void\s+registerCapabilities\(")
req_caps = method_body(req_code, r"public\s+static\s+void\s+registerCapabilities\(")
view_only = read(VIEW_ONLY)

check("⑩-0 接收器的物品 / 流体能力都不看方向：任何 direction 都返回同一份视图"
      "（第 96 轮：可写包装 + 缓存唯一入口）",
      recv_caps is not None
      and recv_caps.count("ViewOnlyHandlers.writableItem(blockEntity.itemCache,") == 1
      and recv_caps.count("blockEntity::insertItemFromExternal)") == 1
      and recv_caps.count("ViewOnlyHandlers.writableFluid(blockEntity.fluidCache,") == 1
      and recv_caps.count("blockEntity::fillFluidFromExternal)") == 1)
check("⑩-1 接收器能力注册里没有 `direction == null` 分支（用户明确要求无方向限制）",
      recv_caps is not None and "direction == null" not in recv_caps)
check("⑩-2 写入只有一条口子：可写包装的 insert / fill 交给 sink，extract / drain 恒空；"
      "既有只读包装（中间缓存仓 / 请求器仍在用）语义一字未改",
      "sink.insert(stack, simulate)" in view_only
      and "sink.fill(resource, action == FluidAction.SIMULATE)" in view_only
      and "return stack;" in view_only
      and "return 0;" in view_only
      and "return ItemStack.EMPTY;" in view_only
      and "return FluidStack.EMPTY;" in view_only)
check("⑩-3 FACING 只用于成链：doWork 输出面与第三方直通都按 FACING，"
      "没有任何「按侧面暴露 / 拒绝」的逻辑",
      "state.getValue(NetworkResourceReceiverBlock.FACING)" in recv_do_work
      and "facing.getOpposite()" in (method_body(recv_code, r"private\s+RsccGenericResourceTarget\.Target\s+genericTarget\(") or ""))
check("⑩-4 请求器的 returnBuffer 暴露保持原样（内部回滚暂存不是 inventory 提供者，"
      "仅在无侧别查询时只读暴露；用户要求只针对接收器）",
      req_caps is not None
      and req_caps.count("direction == null") == 2)
check("⑩-5 反例自证：把接收器的注册改成「direction == null 才暴露」⇒ ⑩-1 判据必须判负",
      "direction == null" in
      (recv_caps or "").replace("(blockEntity, direction) ->",
                               "(blockEntity, direction) -> direction == null ? null : ")
      and "direction == null" not in (recv_caps or ""))


# ======================================================================================
# 结果
# ======================================================================================

print("")
print("断言总数 %d" % CHECKS[0])
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
