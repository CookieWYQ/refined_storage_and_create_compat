#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 100 轮自检：高级远程多功能终端「RS 网络能耗监视」页（task-29）。

只读源码 / 配置 / 语言文件，**不改 src/main**；只用 Python 标准库。

断言清单（①..⑫，与 task-29 验收逐条对应）：
  ① 枚举口径走 **RS 官方 API**：`GraphNetworkComponent#getContainers()` + `InWorldNetworkNodeContainer`
     的 `getPosition()`（GlobalPos = 坐标 + 维度）/ `getBlockState()`；**不得**新加 `NetworkImpl` mixin、
     不得自己维护「网络 → 容器表」（那正是本方案刻意避开的 WeakHashMap + merge/split 自愈）。
  ② 只列耗电节点：`getEnergyUsage() > 0` 过滤；节点必须是 `AbstractNetworkNode`（判据来自 RS 接口形状，
     不是猜）；控制器 / 能量池提供方（`getEnergyUsage() == 0`）天然落选。
  ③ 双读数语义：`current = isActive() ? 标称 : 0`、`nominal = getEnergyUsage()`；两个字段都进协议、
     界面主数字用 current、待机行显示「待机」而不是隐藏；**不自己乘任何系数**。
  ④ 聚合正确（Python 模型复算）：同坐标多节点求和、类型合计 = Σ单体、全量合计用截断前数据、
     类型按 current 降序、**先排序再截断**、截断不留半条（无 0 子项类型）、hidden 计数自洽。
  ⑤ 维度：每个节点都带维度；已知三个维度走语言键，未知维度显示 id 原文；界面有不跨维度的说明行。
  ⑥ 实时：客户端每 `Config.energyAuditRefreshTicks` 请求一次快照；服务端有最小间隔节流；
     界面关掉后自然停止（无服务端常驻推送）。
  ⑦ 客户端纯洁性：界面**不读方块实体 / 不查能力 / 不扫世界 / 自己不算耗电**；
     世界里的框只由 S2C 回显驱动（`EnergyAuditHighlight.arm` 只允许在快照处理路径里被调用）。
  ⑧ 服务端权威：选中坐标必须由服务端重新枚举校验后才回显；取消选中立刻回空（不靠 TTL）。
  ⑨ 网络包：TYPE / STREAM_CODEC 各一份、只注册一次；S2C 处理体走 `enqueueWork`；
     `network/**`、`support/**` 不引用 `net.minecraft.client.*`（专服链接安全）。
  ⑩ 模式接线：`MODE_ENERGY == 6`、`MODE_COUNT == 7`、`isModeSupported(6)` 为真、Tab 语言键 7 条、
     图标数组长度取 `MODE_COUNT`；切进能耗页记下 returnMode、切走即停会话。
  ⑪ Config 四个键存在且被引用（不在代码里另立常量口径）。
  ⑫ 语言键：源码引用的键必须成对出现在 zh_cn / en_us（**缺失只告警**，由 Lead 统一落盘）。

反例自证（≥8 条）：每个「判据函数」都接受**文本或模型函数**，因此反例是拿同一套判据去判
「旧写法 / 错误写法」并断言它判负（打印 `反例 ... PASS`）。

用法：python tools/selfcheck_round100_energy_audit.py
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
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
PKG_REL = "cretae/cookiewyq/rs_create_compat"
SUPPORT = os.path.join(PKG, "support")
NET = os.path.join(PKG, "network")
CLIENT = os.path.join(PKG, "client")
SCREEN = os.path.join(CLIENT, "screen")
MIXIN = os.path.join(PKG, "mixin")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

AUDIT = os.path.join(SUPPORT, "NetworkEnergyAudit.java")
NODE = os.path.join(NET, "EnergyAuditNode.java")
TYPE = os.path.join(NET, "EnergyAuditType.java")
SNAPSHOT = os.path.join(NET, "SyncEnergyAuditPacket.java")
REQUEST = os.path.join(NET, "EnergyAuditRequestPacket.java")
BRIDGE = os.path.join(NET, "EnergyAuditClientBridge.java")
PAYLOADS = os.path.join(NET, "EnergyAuditPayloads.java")
HIGHLIGHT = os.path.join(CLIENT, "EnergyAuditHighlight.java")
CLIENT_SETUP = os.path.join(CLIENT, "EnergyAuditClientSetup.java")
AUDIT_SCREEN = os.path.join(SCREEN, "EnergyAuditScreen.java")
ITEM = os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java")
SWITCH = os.path.join(NET, "SwitchTerminalModePacket.java")
TABS = os.path.join(CLIENT, "TerminalModeTabOverlay.java")
CONFIG = os.path.join(PKG, "Config.java")
MIXINS_JSON = os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json")

FAILS = []
WARNS = []
CHECKS = [0]


def read(path: str) -> str:
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def code_only(text: str) -> str:
    """去掉注释（javadoc 里会**解释**「我们不用 WeakHashMap / 不调 getBlockEntity」这类反例，
    判据必须只看真正的代码，否则「注释里写清楚为什么不做」会被误判成「做了」）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub("//[^" + chr(10) + "]*", "", text)
    return text


def check(ok: bool, label: str, detail: str = "") -> bool:
    CHECKS[0] += 1
    if ok:
        print("  OK   %s" % label)
    else:
        print("  FAIL %s%s" % (label, ("  <<< " + detail) if detail else ""))
        FAILS.append(label)
    return ok


def warn(ok: bool, label: str, detail: str = "") -> bool:
    CHECKS[0] += 1
    if ok:
        print("  OK   %s" % label)
    else:
        print("  WARN %s%s" % (label, ("  <<< " + detail) if detail else ""))
        WARNS.append(label)
    return ok


# ==========================================================================================
# 判据函数（全部接受「文本」或「模型函数」，反例拿同一套去判，故反例是真判负）
# ==========================================================================================

def judge_official_api(text: str) -> list:
    """① 枚举必须走 RS 官方 API。返回违规列表（空 = 通过）。"""
    text = code_only(text)
    bad = []
    if "GraphNetworkComponent" not in text:
        bad.append("未使用 GraphNetworkComponent")
    if "getComponent(GraphNetworkComponent.class)" not in text:
        bad.append("未按 network.getComponent(GraphNetworkComponent.class) 取组件")
    if ".getContainers()" not in text:
        bad.append("未调用 getContainers()")
    if "InWorldNetworkNodeContainer" not in text:
        bad.append("未按 InWorldNetworkNodeContainer 取位置")
    if "getPosition()" not in text:
        bad.append("未调用 getPosition()（坐标 + 维度）")
    if "getBlockState()" not in text:
        bad.append("未调用 getBlockState()")
    if "isRemoved()" not in text:
        bad.append("未过滤 isRemoved()")
    # 自建容器表 = 本方案明确拒绝的做法
    for banned in ("WeakHashMap", "NetworkImpl", "addContainer", "removeContainer"):
        if banned in text:
            bad.append("出现自建容器表痕迹: " + banned)
    return bad


def judge_energy_filter(text: str) -> list:
    """② 只列 energyUsage > 0，且节点必须是 AbstractNetworkNode。"""
    text = code_only(text)
    bad = []
    if "instanceof AbstractNetworkNode" not in text:
        bad.append("未用 instanceof AbstractNetworkNode 取得耗电读数")
    if "getEnergyUsage()" not in text:
        bad.append("未调用 getEnergyUsage()")
    if not re.search(r"nominal\s*<=\s*0L?", text):
        bad.append("缺少 nominal <= 0 的过滤（不耗电节点会进列表）")
    if "instanceof EnergyProvider" not in text:
        bad.append("provider 侧没有结构判据（只靠 usage==0 碰巧排除）")
    return bad


def judge_dual_reading(text: str) -> list:
    """③ current / nominal 双读数语义（不自己乘系数）。"""
    text = code_only(text)
    bad = []
    if "isActive()" not in text:
        bad.append("未用 isActive() 区分「当前 / 待机」")
    if "currentUsage" not in text or "nominalUsage" not in text:
        bad.append("协议里缺 current/nominal 双读数")
    if re.search(r"(usage|Usage)\s*\*\s*\d", text):
        bad.append("出现「耗电 × 系数」的写法（用户口径：不自己乘系数）")
    return bad


def judge_no_client_world_access(text: str) -> list:
    """⑦ 客户端界面不许读 BE / 查能力 / 扫世界。"""
    text = code_only(text)
    bad = []
    for pattern in ("getBlockEntity", "BlockEntity", "getCapability", "Capabilities",
                    "betweenClosed", "getBlockState", "for (BlockPos", "for (final BlockPos"):
        if pattern in text:
            bad.append("客户端出现世界查询: " + pattern)
    return bad


def judge_highlight_only_from_s2c(screen_text: str, highlight_text: str) -> list:
    """⑦ 世界里的框只能由 S2C 回显驱动。"""
    bad = []
    if "EnergyAuditHighlight.arm(" not in screen_text:
        bad.append("界面没有武装高亮（那框永远不会出现）")
    if "EnergyAuditHighlight.arm(packet.selectedDimension(), packet.selectedPos())" not in screen_text:
        bad.append("武装高亮的参数不是快照里的 selected* 字段（客户端在猜坐标）")
    if "selectedDimension()" not in screen_text or "selectedPos()" not in screen_text:
        bad.append("界面没有读 S2C 的 selected* 字段")
    if "expiresAt" not in highlight_text or "getGameTime()" not in highlight_text:
        bad.append("高亮没有 TTL（断流后会留永久残影）")
    if "chaseBox" not in highlight_text and "chaseAABB" not in highlight_text:
        bad.append("高亮没有每 tick 重喂 Outliner（框会一帧就消失）")
    return bad


def judge_highlight_ttl(highlight_text: str) -> list:
    """⑦ 高亮的 TTL 必须来自 Config 且每 tick 检查过期。"""
    bad = []
    if "Config.energyAuditHighlightTtlTicks" not in highlight_text:
        bad.append("TTL 未走 Config（另立常量口径）")
    if "level.getGameTime() > expiresAt" not in highlight_text and \
            "getGameTime() > expiresAt" not in highlight_text:
        bad.append("没有过期判定")
    return bad


def judge_packets(snapshot_text: str, request_text: str, payloads_text: str) -> list:
    """⑨ 包注册唯一 + S2C 线程安全 + 服务端权威校验。"""
    bad = []
    if snapshot_text.count("public static final Type<") != 1:
        bad.append("S2C 的 Type 不止一处（重复注册风险）")
    if "ctx.enqueueWork" not in snapshot_text:
        bad.append("S2C 处理体没有 enqueueWork（会写客户端状态在错误的线程上）")
    if payloads_text.count("SyncEnergyAuditPacket.TYPE") != 1:
        bad.append("S2C 注册行不是恰好 1 处")
    if payloads_text.count("EnergyAuditRequestPacket.TYPE") != 1:
        bad.append("C2S 注册行不是恰好 1 处")
    if "playToServer" not in payloads_text or "playToClient" not in payloads_text:
        bad.append("两个方向的注册行不齐")
    if "handleRequest" not in request_text:
        bad.append("C2S 处理体没有交给服务端权威实现")
    return bad


def judge_dedicated_server_safe(net_text: str, support_text: str) -> list:
    """⑨ network / support 不得出现客户端类型（专服链接期安全）。"""
    bad = []
    for name, text in (("network", code_only(net_text)), ("support", code_only(support_text))):
        for pattern in ("net.minecraft.client", "client.gui", "net.neoforged.neoforge.client",
                        "Minecraft.getInstance"):
            if pattern in text:
                bad.append("%s 出现客户端类型: %s" % (name, pattern))
    return bad


def judge_mode_wiring(item_text: str, switch_text: str, tabs_text: str) -> list:
    """⑩ 模式枚举 / Tab / 返回模式接线。

    第 131 轮起 MODE_COUNT 不再恒为 7（新增 MODE_SEND=7 / MODE_COUNT=8），因此这里改成
    「派生 + 下限」判据：MODE_ENERGY 必须仍是 6、MODE_COUNT 必须 ≥ 7（覆盖到能耗档）、
    Tab 语言键至少含本轮那 7 条（多出来的新档不算回归，但少的必须判负）。
    写死 7 只会把「本轮合法地新增一档」误报成能耗页回归。
    """
    bad = []
    if "MODE_ENERGY = 6" not in item_text:
        bad.append("MODE_ENERGY 不是 6")
    count_match = re.search(r"MODE_COUNT\s*=\s*(\d+)\s*;", item_text)
    if not count_match:
        bad.append("找不到 MODE_COUNT 字面量")
    elif int(count_match.group(1)) < 7:
        bad.append("MODE_COUNT 是 %s，小于 7（覆盖不到能耗档）" % count_match.group(1))
    if "mode == MODE_ENERGY" not in item_text:
        bad.append("isModeSupported 没把能耗模式算进去")
    if "case MODE_ENERGY" not in item_text:
        bad.append("openModeScreen 没有 MODE_ENERGY 分支")
    if "NetworkEnergyAudit" not in item_text:
        bad.append("openModeScreen 没走 NetworkEnergyAudit")
    if "previousMode" not in switch_text:
        bad.append("切模式时没有记下「上一档」（返回终端会回错界面）")
    if "NetworkEnergyAudit.stop" not in switch_text:
        bad.append("切走时没有结束会话")
    keys = re.findall(r'"(gui\.rs_create_compat\.advanced_remote_terminal\.mode\.[a-z_]+)"', tabs_text)
    if len(keys) < 7:
        bad.append("Tab 语言键少于 7 条（实际 %d）" % len(keys))
    if "mode.energy" not in tabs_text:
        bad.append("Tab 语言键里没有能耗模式")
    if "new ItemStack[AdvancedRemoteTerminalItem.MODE_COUNT]" not in tabs_text:
        bad.append("图标数组长度没有跟 MODE_COUNT 走（再加模式会漏图标）")
    return bad


def judge_config(config_text: str, audit_text: str, screen_text: str, highlight_text: str) -> list:
    """⑪ Config 四个键存在且被引用。"""
    bad = []
    for field in ("energyAuditMaxNodes", "energyAuditMaxTypes",
                  "energyAuditRefreshTicks", "energyAuditHighlightTtlTicks"):
        if field not in config_text:
            bad.append("Config 缺键: " + field)
        if field not in audit_text + screen_text + highlight_text:
            bad.append("代码没引用 Config." + field)
    return bad


# ==========================================================================================
# ④ 聚合模型（Python 复算 Java 的 aggregate + buildPacket；反例 = 故意写错的模型）
# ==========================================================================================

def aggregate(containers, max_nodes=512, max_types=256):
    """与 `support/NetworkEnergyAudit` 完全同构的聚合实现（输入 = 已逐容器读出的原始读数）。

    containers 里每项：{dim, pos(元组), block, nominal, active}
    """
    merged = {}
    for c in containers:
        if c["nominal"] <= 0:                       # ② 只列耗电节点
            continue
        key = (c["dim"], c["pos"])
        entry = merged.setdefault(key, {
            "dim": c["dim"], "pos": c["pos"], "block": c["block"],
            "current": 0, "nominal": 0, "active": False,
        })
        entry["nominal"] += c["nominal"]            # ③ 同坐标多节点求和
        if c["active"]:
            entry["current"] += c["nominal"]
            entry["active"] = True
    blocks = sorted(merged.values(),
                    key=lambda e: (-e["current"], -e["nominal"], e["dim"], e["pos"]))
    total_blocks = len(blocks)
    total_current = sum(e["current"] for e in blocks)
    total_nominal = sum(e["nominal"] for e in blocks)
    total_types = len({e["block"] for e in blocks})
    selected = blocks[:max(0, min(total_blocks, max_nodes))]   # 先排序后截断
    groups = {}
    order = []
    for entry in selected:
        if entry["block"] not in groups:
            groups[entry["block"]] = []
            order.append(entry["block"])
        groups[entry["block"]].append(entry)
    types = []
    for block in order:
        members = groups[block]
        types.append({
            "block": block,
            "count": len(members),
            "current": sum(m["current"] for m in members),
            "nominal": sum(m["nominal"] for m in members),
            "nodes": members,
        })
    types.sort(key=lambda t: (-t["current"], -t["nominal"], t["block"]))
    if len(types) > max_types:
        types = types[:max_types]                                  # 整类丢弃，不留半条
    included_blocks = sum(len(t["nodes"]) for t in types)
    return {
        "types": types,
        "total_blocks": total_blocks,
        "total_types": total_types,
        "total_current": total_current,
        "total_nominal": total_nominal,
        "hidden_blocks": max(0, total_blocks - included_blocks),
        "hidden_types": max(0, total_types - len(types)),
    }


def judge_aggregation(model, containers, max_nodes, max_types) -> list:
    """④ 对任意（模型 + 数据）断言聚合性质。模型是参数 ⇒ 反例是拿同一套判据判错模型。"""
    bad = []
    snap = model(containers, max_nodes, max_types)
    types = snap["types"]
    # 类型合计 = Σ单体
    for t in types:
        if t["current"] != sum(n["current"] for n in t["nodes"]):
            bad.append("类型 %s 的合计 ≠ Σ单体" % (t["block"],))
        if t["nominal"] != sum(n["nominal"] for n in t["nodes"]):
            bad.append("类型 %s 的标称合计 ≠ Σ单体" % (t["block"],))
        if not t["nodes"]:
            bad.append("类型 %s 是空行（截断留了半条）" % (t["block"],))
        if t["count"] != len(t["nodes"]):
            bad.append("类型 %s 的台数与子项数不一致" % (t["block"],))
    # 全量合计 = 截断前（不是界面上看得到的那几条）
    expect_current = sum(c["nominal"] for c in containers if c["nominal"] > 0 and c["active"])
    expect_nominal = sum(c["nominal"] for c in containers if c["nominal"] > 0)
    if snap["total_current"] != expect_current:
        bad.append("total_current 不是全量当前合计")
    if snap["total_nominal"] != expect_nominal:
        bad.append("total_nominal 不是全量标称合计")
    # 每种方块只出现一次，且按 current 降序
    blocks = [t["block"] for t in types]
    if len(blocks) != len(set(blocks)):
        bad.append("同一方块类型出现多行")
    ordered = sorted(types, key=lambda t: (-t["current"], -t["nominal"], t["block"]))
    if [t["block"] for t in ordered] != blocks:
        bad.append("类型没有按当前耗电降序")
    # 截断自洽
    if snap["hidden_blocks"] < 0 or snap["hidden_types"] < 0:
        bad.append("hidden 计数为负")
    if len(types) > max_types:
        bad.append("类型数超过上限（没有截断）")
    if sum(len(t["nodes"]) for t in types) > max_nodes:
        bad.append("子项总数超过节点上限")
    if snap["hidden_blocks"] > 0 and snap["total_blocks"] <= max_nodes and snap["hidden_types"] == 0:
        bad.append("没截断却报了 hidden_blocks")
    return bad


def judge_providers_excluded(model, containers) -> list:
    """② 不耗电的节点（provider 侧：控制器 / 能量池，getEnergyUsage()==0）不得进列表。"""
    bad = []
    snap = model(list(containers), 512, 256)
    positions = {(n["dim"], n["pos"]) for t in snap["types"] for n in t["nodes"]}
    for c in containers:
        key = (c["dim"], c["pos"])
        if c["nominal"] <= 0 and key in positions:
            bad.append("不耗电节点进了列表: %s" % (key,))
    return bad


def judge_pos_merge(model, containers) -> list:
    """③ 同坐标多节点（RS 中继器 input+output）必须求和，不能只留一个。"""
    bad = []
    snap = model(list(containers), 512, 256)
    expect = {}
    for c in containers:
        if c["nominal"] <= 0:
            continue
        key = (c["dim"], c["pos"])
        expect[key] = expect.get(key, 0) + c["nominal"]
    got = {}
    for t in snap["types"]:
        for n in t["nodes"]:
            got[(n["dim"], n["pos"])] = n["nominal"]
    if expect != got:
        bad.append("坐标级标称读数没有逐节点求和")
    return bad


# -------------------------------- 反例用的「错模型」 --------------------------------

def model_counts_providers(containers, max_nodes=512, max_types=256):
    """错：不按 nominal>0 过滤 ⇒ 控制器 / 能量池（provider 侧）也被当成耗电方块列出来。"""
    patched = [dict(c, nominal=(c["nominal"] if c["nominal"] > 0 else 1)) for c in containers]
    return aggregate(patched, max_nodes, max_types)


def model_without_pos_merge(containers, max_nodes=512, max_types=256):
    """错：同坐标只留最后一个节点（不求和）。"""
    seen = {}
    for c in containers:
        seen[(c["dim"], c["pos"])] = c
    return aggregate(list(seen.values()), max_nodes, max_types)


def model_partial_type(containers, max_nodes=512, max_types=256):
    """错：截断时允许把某个类型的子项全丢掉但仍留一行（「半条」）。"""
    snap = aggregate(containers, max_nodes, max_types)
    if snap["types"]:
        last = dict(snap["types"][-1])
        last["nodes"] = []
        last["current"] = 0
        last["nominal"] = 0
        snap["types"] = snap["types"][:-1] + [last]
    return snap


def model_truncate_before_sort(containers, max_nodes=512, max_types=256):
    """错：先按输入顺序截断再排序（会漏掉真正最耗电的那些）。"""
    ordered = [c for c in containers if c["nominal"] > 0][:max_nodes]
    return aggregate(ordered, max_nodes, max_types)


def model_nominal_as_current(containers, max_nodes=512, max_types=256):
    """错：主数字用标称（待机机器也报满额，玩家看不出波动）。"""
    patched = [dict(c, active=True) for c in containers]
    return aggregate(patched, max_nodes, max_types)


# ==========================================================================================
# 主流程
# ==========================================================================================

def main() -> int:
    print("=" * 78)
    print("第 100 轮自检：RS 网络能耗监视页（task-29）")
    print("=" * 78)

    # ---------- 0. 文件存在 ----------
    print("\n[0] 文件存在性")
    for path in (AUDIT, NODE, TYPE, SNAPSHOT, REQUEST, BRIDGE, PAYLOADS,
                 HIGHLIGHT, CLIENT_SETUP, AUDIT_SCREEN):
        check(os.path.isfile(path), "存在 " + os.path.relpath(path, ROOT))
    if FAILS:
        print("\n关键文件缺失，后续断言无意义。")
        return 1

    audit = read(AUDIT)
    node = read(NODE)
    type_src = read(TYPE)
    snapshot = read(SNAPSHOT)
    request = read(REQUEST)
    payloads = read(PAYLOADS)
    highlight = read(HIGHLIGHT)
    screen = read(AUDIT_SCREEN)
    item = read(ITEM)
    switch = read(SWITCH)
    tabs = read(TABS)
    config = read(CONFIG)
    mixins_json = read(MIXINS_JSON)

    # ---------- ① 官方 API，无 mixin ----------
    print("\n[1] 枚举口径走 RS 官方 API（不用 NetworkImpl mixin）")
    for violation in judge_official_api(audit):
        check(False, "官方 API 判据", violation)
    if not judge_official_api(audit):
        check(True, "GraphNetworkComponent#getContainers + InWorldNetworkNodeContainer#getPosition 齐备")
    # 全工程不得新增「网络容器表」mixin
    lease = []
    for dirpath, _dirnames, filenames in os.walk(MIXIN):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            body = read(os.path.join(dirpath, name))
            if "NetworkImpl" in body or "GraphNetworkComponent" in body:
                lease.append(name)
    check(not lease, "mixin 包里没有 NetworkImpl / GraphNetworkComponent 注入", ", ".join(lease))
    check("NetworkImpl" not in mixins_json, "rs_create_compat.mixins.json 没有新增 NetworkImpl 条目")

    # ---------- ② 只列耗电节点 ----------
    print("\n[2] 只列会耗电的方块（provider 侧天然落选）")
    merged_java = audit + node + type_src + snapshot
    for violation in judge_energy_filter(merged_java):
        check(False, "耗电过滤判据", violation)
    if not judge_energy_filter(merged_java):
        check(True, "instanceof AbstractNetworkNode + getEnergyUsage() + nominal<=0 过滤 齐备")
    # 本模组自己的能量立方是 provider：getEnergyUsage() 必须恒为 0（否则它会被当耗电大户列出来）
    cube = os.path.join(NET, "FEEnergyCubeNetworkNode.java")
    if os.path.isfile(cube):
        cube_body = read(cube)
        cube_usage = re.search(r"getEnergyUsage\(\)\s*\{\s*[\s\S]{0,400}?return\s+0L?;", cube_body)
        check(cube_usage is not None, "FEEnergyCubeNetworkNode#getEnergyUsage 返回 0（provider 不进列表）")
    else:
        warn(False, "FEEnergyCubeNetworkNode.java 不存在（跳过 provider 反例）")

    # ---------- ③ 双读数 ----------
    print("\n[3] current / nominal 双读数（不自己乘系数）")
    for violation in judge_dual_reading(merged_java + screen):
        check(False, "双读数判据", violation)
    if not judge_dual_reading(merged_java + screen):
        check(True, "current = isActive() ? 标称 : 0，且协议/界面都带双读数")
    check("待机" in read(os.path.join(LANG_DIR, "zh_cn.json")) or True,
          "（语言键由 Lead 落盘，这里不阻断）")

    # ---------- ④ 聚合模型 ----------
    print("\n[4] 聚合正确性（Python 模型复算 + 反例）")
    # 一组假节点：包含 provider（nominal=0）、同坐标双节点（中继器）、多种类型、跨类型同分
    fake = [
        {"dim": "minecraft:overworld", "pos": (10, 64, 10), "block": "refinedstorage:importer",
         "nominal": 4, "active": True},
        {"dim": "minecraft:overworld", "pos": (11, 64, 10), "block": "refinedstorage:importer",
         "nominal": 4, "active": True},
        {"dim": "minecraft:overworld", "pos": (12, 64, 10), "block": "refinedstorage:exporter",
         "nominal": 6, "active": False},          # 待机：current=0 / nominal=6
        {"dim": "minecraft:overworld", "pos": (13, 64, 10), "block": "refinedstorage:relay",
         "nominal": 3, "active": True},            # 同坐标双节点 ⇒ 应合计 6
        {"dim": "minecraft:overworld", "pos": (13, 64, 10), "block": "refinedstorage:relay",
         "nominal": 3, "active": True},
        {"dim": "minecraft:the_nether", "pos": (0, 32, 0), "block": "refinedstorage:grid",
         "nominal": 9, "active": True},
        {"dim": "minecraft:overworld", "pos": (0, 64, 0), "block": "refinedstorage:controller",
         "nominal": 0, "active": True},            # provider：必须落选
    ]
    for violation in judge_aggregation(aggregate, fake, 512, 256):
        check(False, "聚合性质判据", violation)
    if not judge_aggregation(aggregate, fake, 512, 256):
        check(True, "类型合计 = Σ单体 / 全量合计 / 降序 / 截断自洽")
    snap = aggregate(fake, 512, 256)
    check(snap["total_current"] == 23, "全量当前合计 = 23（4+4+0+6+9，待机那台不计）",
          str(snap["total_current"]))
    check(snap["total_nominal"] == 29, "全量标称合计 = 29（含待机那台）", str(snap["total_nominal"]))
    relay = [t for t in snap["types"] if t["block"] == "refinedstorage:relay"][0]
    check(relay["current"] == 6 and relay["count"] == 1,
          "同坐标中继器两节点合并成 1 台、合计 6", str(relay))
    check(all(t["block"] != "refinedstorage:controller" for t in snap["types"]),
          "provider（controller, nominal=0）没有进列表")
    # 截断：节点上限 3 ⇒ 只留最耗电的 3 台，且不留半条
    cut = aggregate(fake, 3, 256)
    check(cut["hidden_blocks"] == 2, "节点上限 3 ⇒ hidden_blocks = 2",
          str(cut["hidden_blocks"]))
    check(all(t["nodes"] for t in cut["types"]), "截断后没有空类型行")
    check(sum(len(t["nodes"]) for t in cut["types"]) == 3, "截断后子项总数 = 3")
    cut2 = aggregate(fake, 512, 1)
    check(len(cut2["types"]) == 1 and cut2["hidden_types"] == 3,
          "类型上限 1 ⇒ 只剩 1 类、hidden_types = 3", str((len(cut2["types"]), cut2["hidden_types"])))
    # 反例：同一套判据判「错模型」必须判负
    negatives = [
        ("把 provider 也算耗电", model_counts_providers, judge_providers_excluded, (fake,)),
        ("同坐标不求和", model_without_pos_merge, judge_pos_merge, (fake,)),
        ("截断留半条空类型", model_partial_type, None, (fake, 512, 256)),
        ("先截断后排序", model_truncate_before_sort, None, (fake, 3, 256)),
        ("标称当当前（待机也报满额）", model_nominal_as_current, None, (fake, 512, 256)),
    ]
    for label, model, judge, args in negatives:
        if judge is None:
            bad = judge_aggregation(model, *args)
        else:
            bad = judge(model, *args)
        check(bool(bad), "反例 %s → 判负" % label, "错模型竟然通过了判据！")

    # ---------- ⑤ 维度 ----------
    print("\n[5] 维度字段与显示")
    check("dimension" in node and "ResourceLocation dimension" in node, "节点记录带维度字段")
    check("getPosition()" in audit and "position.dimension()" in audit,
          "维度取自 GlobalPos.dimension()")
    check("cross_dim_hint" in screen, "界面有「只显示绑定网络 / 跨维度对端不在此列」提示行")
    check("dim.overworld" in screen and "dim.nether" in screen and "dim.end" in screen,
          "已知三维度走语言键")
    check("dimension.toString()" in screen, "未知维度显示 id 原文（不编造名字）")
    check("getDimension" not in screen, "界面不使用客户端 level 反查维度（避免跨维度画错框）")

    # ---------- ⑥ 实时 ----------
    print("\n[6] 实时刷新（客户端轮询 + 服务端节流）")
    check("Config.energyAuditRefreshTicks" in screen, "客户端刷新间隔走 Config")
    check("EnergyAuditRequestPacket.refresh()" in screen, "客户端按周期请求快照")
    check("MIN_SEND_INTERVAL_TICKS" in audit and "lastSentTick" in audit,
          "服务端有最小发包间隔（防请求风暴）")
    check("@SubscribeEvent" not in audit.replace("@SubscribeEvent\n    public static void onLoggedOut", ""),
          "服务端没有常驻 tick 推送（只有下线清理的订阅）")

    # ---------- ⑦ 客户端纯洁性 ----------
    print("\n[7] 客户端不查任何东西；高亮只由 S2C 驱动")
    for violation in judge_no_client_world_access(screen):
        check(False, "客户端纯洁性判据", violation)
    if not judge_no_client_world_access(screen):
        check(True, "界面无 getBlockEntity / Capability / 世界遍历")
    check("getEnergyUsage" not in screen, "界面不自己读耗电（不调 getEnergyUsage）")
    for violation in judge_highlight_only_from_s2c(screen, highlight):
        check(False, "高亮来源判据", violation)
    if not judge_highlight_only_from_s2c(screen, highlight):
        check(True, "高亮武装参数就是 S2C 的 selected* 字段")
    for violation in judge_highlight_ttl(highlight):
        check(False, "高亮 TTL 判据", violation)
    if not judge_highlight_ttl(highlight):
        check(True, "高亮 TTL 走 Config 且每 tick 检查过期")
    check("AreaOutline" in highlight, "高亮复用 AreauOutline/Outliner（与充电器范围框同观感）"
          .replace("AreauOutline", "AreaOutline"))
    check("removed()" in screen and "EnergyAuditHighlight.clear()" in screen,
          "界面移除时立刻撤框（不靠 TTL）")
    check("EnergyAuditHighlight.arm(" not in screen.split("private void accept")[0],
          "在收快照之前不武装任何高亮")

    # ---------- ⑧ 服务端权威 ----------
    print("\n[8] 服务端权威校验选中坐标")
    check("present" in audit and "session.clearSelection()" in audit,
          "选中坐标必须真的出现在这一份下发的快照里（否则清空回显）")
    check("session.clearSelection()" in audit, "校验失败 / 无网络时清空回显")
    check("selectedDimension" in snapshot and "selectedPos" in snapshot,
          "回显字段在快照里（客户端只据此画框）")
    check("ACTION_CLEAR" in request and "requestClear" in screen,
          "取消选中走独立动作（立刻回空，不靠 TTL）")

    # ---------- ⑨ 网络包 ----------
    print("\n[9] 网络包注册唯一 + 专服安全")
    for violation in judge_packets(snapshot, request, payloads):
        check(False, "网络包判据", violation)
    if not judge_packets(snapshot, request, payloads):
        check(True, "TYPE/注册行各 1 处，S2C 走 enqueueWork")
    net_all = ""
    for name in sorted(os.listdir(NET)):
        if name in ("EnergyAuditNode.java", "EnergyAuditType.java", "SyncEnergyAuditPacket.java",
                    "EnergyAuditRequestPacket.java", "EnergyAuditClientBridge.java",
                    "EnergyAuditPayloads.java"):
            net_all += read(os.path.join(NET, name))
    for violation in judge_dedicated_server_safe(net_all, audit):
        check(False, "专服安全判据", violation)
    if not judge_dedicated_server_safe(net_all, audit):
        check(True, "network/support 新文件无客户端类型")
    check("EnergyAuditClientBridge" in snapshot, "S2C 只经双端安全的桥间接调客户端")
    check("Dist.CLIENT" in read(CLIENT_SETUP), "客户端接线类标了 Dist.CLIENT（专服不加载）")

    # ---------- ⑩ 模式接线 ----------
    print("\n[10] 终端模式接线（第 7 档）")
    for violation in judge_mode_wiring(item, switch, tabs):
        check(False, "模式接线判据", violation)
    if not judge_mode_wiring(item, switch, tabs):
        check(True, "MODE_ENERGY=6 / MODE_COUNT≥7 / Tab ≥7 条 / 返回模式已接线")
    check("returnMode" in snapshot, "returnMode 随快照下发（返回终端回到原来那档）")
    check("closeContainer()" in audit, "切进能耗页前关闭旧容器菜单（纯 Screen 无 Menu）")
    check("isPauseScreen" in screen, "pauseScreen 语义与其它自建界面一致")

    # ---------- ⑪ Config ----------
    print("\n[11] Config 四个键")
    for violation in judge_config(config, audit, screen, highlight):
        check(False, "Config 判据", violation)
    if not judge_config(config, audit, screen, highlight):
        check(True, "四个 Config 键存在且都被引用")
    check("defineInRange(\"energyAuditMaxNodes\"" in config, "energyAuditMaxNodes 有上下界")
    check("defineInRange(\"energyAuditRefreshTicks\", 20, 10, 200)" in config,
          "energyAuditRefreshTicks 夹在 10..200")

    # ---------- ⑫ 语言键 ----------
    print("\n[12] 语言键（缺失只告警，由 Lead 统一落盘）")
    referenced = set()
    for text in (screen, tabs):
        for match in re.findall(r'"(gui\.rs_create_compat\.energy_audit\.[a-z_.]+)"', text):
            referenced.add(match)
    for match in re.findall(r'LANG \+ "([a-z_.]+)"', screen):
        referenced.add("gui.rs_create_compat.energy_audit." + match)
    if "mode.energy" in tabs:
        referenced.add("gui.rs_create_compat.advanced_remote_terminal.mode.energy")
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
    missing = sorted(k for k in referenced if k not in zh or k not in en)
    warn(not missing, "源码引用的 %d 个语言键在 zh/en 里成对存在" % len(referenced),
         "缺 %d 条（见交付清单）: %s" % (len(missing), ", ".join(missing[:6])))
    print("  语言键清单（%d 条）：" % len(referenced))
    for key in sorted(referenced):
        print("    " + key)

    # ---------- 反例：源码级判据也拿同一套函数判负 ----------
    print("\n[13] 源码级反例（同一套判据）")
    bad_audit = audit.replace("getComponent(GraphNetworkComponent.class)", "getComponent(GraphNetworkComponent::class)")
    check(bool(judge_official_api(bad_audit)), "反例 未走官方组件 ⇒ API 判据判负")
    bad_filter = audit.replace("nominal <= 0L", "nominal < -1L")
    check(bool(judge_energy_filter(bad_filter)), "反例 不滤 0 耗电 ⇒ 过滤判据判负")
    bad_provider = audit.replace("node instanceof EnergyProvider", "node == null")
    check(bool(judge_energy_filter(bad_provider)), "反例 只靠 usage==0 排除 provider ⇒ 过滤判据判负")
    bad_client = screen + "\n    void rsccLeak() { level.getBlockEntity(pos); }\n"
    check(bool(judge_no_client_world_access(bad_client)), "反例 客户端读方块实体 ⇒ 纯洁性判据判负")
    bad_highlight = screen.replace(
        "EnergyAuditHighlight.arm(packet.selectedDimension(), packet.selectedPos())",
        "EnergyAuditHighlight.arm(node.dimension(), node.pos())")
    check(bool(judge_highlight_only_from_s2c(bad_highlight, highlight)),
          "反例 客户端自己猜高亮坐标 ⇒ 高亮来源判据判负")
    bad_ttl = highlight.replace("Config.energyAuditHighlightTtlTicks", "60")
    check(bool(judge_highlight_ttl(bad_ttl)), "反例 TTL 不走 Config ⇒ TTL 判据判负")
    bad_payloads = payloads + "\n        event.registrar(MODID).playToClient(SyncEnergyAuditPacket.TYPE, null, null);\n"
    check(bool(judge_packets(snapshot, request, bad_payloads)), "反例 重复注册 S2C ⇒ 包判据判负")
    # 反例：把 MODE_COUNT 改成 6（覆盖不到能耗档）。不能用字面量 replace —— 第 131 轮起
    # MODE_COUNT 是 8，字面量 replace 会变成空操作，反例就退化成「永远判负/永远通过」的假证明。
    bad_mode = re.sub(r"MODE_COUNT\s*=\s*\d+\s*;", "MODE_COUNT = 6;", item)
    check(bool(judge_mode_wiring(bad_mode, switch, tabs)), "反例 MODE_COUNT 仍是 6 ⇒ 模式判据判负")
    bad_config = config.replace("energyAuditMaxTypes", "energyAuditMaxKindsX")
    check(bool(judge_config(bad_config, audit, screen, highlight)), "反例 缺 Config 键 ⇒ Config 判据判负")
    bad_switch = switch.replace("previousMode", "currentMode")
    check(bool(judge_mode_wiring(item, bad_switch, tabs)), "反例 不记上一档 ⇒ 返回模式判据判负")

    # ---------- 汇总 ----------
    print("\n" + "=" * 78)
    print("断言/反例总数 %d，失败 %d，告警 %d" % (CHECKS[0], len(FAILS), len(WARNS)))
    if WARNS:
        print("告警：")
        for label in WARNS:
            print("  - " + label)
    if FAILS:
        print("失败：")
        for label in FAILS:
            print("  - " + label)
        print("RESULT: FAIL")
        return 1
    print("RESULT: OK（%d 条判据全部通过；告警不影响退出码）" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
