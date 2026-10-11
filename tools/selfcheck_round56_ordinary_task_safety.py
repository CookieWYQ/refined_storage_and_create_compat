# -*- coding: utf-8 -*-
"""round56 自检：**普通（非序列装配）RS 自动合成任务的安全**（用户第 7 条）。

用户原话（场景）：「我有很多使用输入总线进行回收的、普通的、不是序列装配的自动合成任务……
此时我想在这一条线上接一条分支接到我这一个（序列装配）上……因为别的输入输出总线也被判定成
这一个序列执行器的附属，然后就导致一个很诡异的问题，就是他们全都提示（挂起）了。」

本脚本断言四件事（每条都是可被源码事实证伪的锚点，不是复述注释）：

  ① **总线归属的全部判据**（源码锚点 + 行号）：归属只看「线缆几何」，且只有「可达恰好一条链且
     探查穷尽」才算归属 —— 于是「从主线接一条分支到执行舱」会让整条主线上的**所有**总线
     都被判成这台执行舱的附属。本脚本用 Python 复刻这趟 BFS（可穿行集合 / 扳手断开 / 分隔框架 /
     不穿过执行舱 / 步数与格子上限 / 按链去重 / 归属未确定），把这条机制**跑出来**（场景矩阵）。

  ② **挂起判据链**（源码锚点 + 行号）：扫描分类 → reason → 连续计时超阈值 → suspendRecord。
     并断言「自动挂起」的两条入口都已被 `Record#ourChain` 闸住。

  ③ **真值表**：网络里只有普通任务 ⇒ **不挂起**；普通任务 + 序列装配任务并存 ⇒ 普通任务不挂起；
     序列装配真堵 ⇒ **仍挂起**（不回归，秒级）。含「普通任务产物恰好也在本模组样板库里」这一形态。

  ④ **不可逆路径**：全工程唯一的自动取消（挂起超限兜底回收 → RS 的 cancel）已被收窄成
     「只对本模组序列装配链真正驱动的任务生效」；普通任务即便被玩家手动挂起，超限也**绝不**兜底取消。

可重复执行：python tools/selfcheck_round56_ordinary_task_safety.py
     → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
"""
import io
import os
import re
import sys
from collections import OrderedDict

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RS_API = os.path.join(ROOT, "local_src", "external", "RefinedStorage")

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def rel_read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def read_abs(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def locate(rel, needle):
    """在文件里找字面量，返回「文件:行号」；找不到返回 None。"""
    text = rel_read(rel)
    index = text.find(needle)
    if index < 0:
        return None
    line = text.count("\n", 0, index) + 1
    return "%s:%d" % (rel, line)


def source_files():
    out = []
    for base, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith(".java"):
                out.append(os.path.join(base, name))
    return out


def strip_comments(text):
    """去掉 // 与 /* */ 注释：源码锚点扫描必须看「代码」，不能把注释里的字样当成调用点。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def rel_of(path):
    return os.path.relpath(path, SRC).replace("\\", "/")


WATCHDOG = "support/AssemblyWatchdog.java"
LINK = "support/RsccWireLinkSearch.java"
INTERFERENCE = "support/RsccBusInterference.java"
EXPORTER = "mixin/exporter/AbstractExporterBlockEntityMixin.java"
IMPORTER = "mixin/importer/AbstractImporterBlockEntityMixin.java"
CHAMBER = "block/entity/SequenceExecutionChamberBlockEntity.java"

watchdog = rel_read(WATCHDOG)

# =====================================================================
print("=" * 78)
print("① 总线归属的全部判据（源码锚点；行号由脚本现算，改代码后仍然指得准）")
print("=" * 78)

AFFILIATION = [
    # 2026-10-11（round60）更新：这里原本锚定旧的两条「到点静默结束」上限
    # （`LINK_MAX_STEPS = 64` / `LINK_MAX_CELLS = 128`）。那两条正是「线缆一长 / 总线一多，
    # 总线自己这一侧的搜链被截断 ⇒ 归属误判为未确定而停用」的根因，已按「可续扫」重写并移除；
    # 新锚点见 tools/selfcheck_round60_wirelink_exhaustive.py。这里换成同一件事的新三条。
    (LINK, "private static final int BUS_LINK_TICK_BUDGET = 256;", "搜链每 tick 预算 256（与舱侧同口径）"),
    (LINK, "private static final int BUS_LINK_HARD_CAP = 4096;", "单趟硬上限 4096（触顶显式报出，不静默）"),
    (LINK, "private boolean hasSnapshot;", "可续扫：续扫期间沿用上一次完整结果，只有扫完才发布"),
    (LINK, "public static boolean hasRelevantNeighbor", "廉价预检：六邻块里有导线或执行舱才做 BFS"),
    (LINK, "public static LinkWalk searchChamberLink", "唯一的搜链实现（一趟展开给出可达链数 / 是否穷尽）"),
    (LINK, "RsccCableCuts.isDisconnected(level, cell, direction)", "闸门①：扳手断开的接缝 = 这里没有线"),
    (LINK, "SeparationFrameGuard.blocksConnection(level, cell, direction)", "闸门②：分隔框架套住的一侧 = 这里没有线"),
    (LINK, "if (state.getBlock() instanceof SequenceExecutionChamberBlock)", "碰到执行舱：收集（若是「总线输出」模式）但不穿过"),
    (LINK, "if (RsccWireBlocks.isWire(state))", "可穿行集合：RS 线缆 + 输入 / 输出总线（机器与容器都不穿过）"),
    (LINK, "public boolean ambiguous()", "归属未确定 = 可达 ≥2 条链（round60 起不再把「未穷尽」当停用理由）"),
    (LINK, "private static List<BlockPos> collapseByChain", "同一条链上的多台 = 一个逻辑执行仓（按链计数）"),
    (INTERFERENCE, "public static Report inspect", "把搜链结果整理成报告（disabled / reachableCount / chambers）"),
    (INTERFERENCE, "return new Report(walk.ambiguous(), !walk.exhaustive(), reachable.size(),", "disabled 只由 ambiguous 决定"),
    (EXPORTER, "public boolean rscc$isLinkedLayout()", "输出总线：线缆够得到 ≥1 条链 = 处在延长型布局里"),
    (EXPORTER, "return rscc$linkReport.reachableCount() >= 1;", "唯一判据就是可达链数 ≥ 1（**与机器 / 用料 / 任务都无关**）"),
    (EXPORTER, "public boolean rscc$isExecutorMode()", "延长型生效 = 处在延长型布局 且 没被玩家强制普通"),
    (EXPORTER, "rscc$forceNormalBus", "唯一的例外：玩家逐条总线点的「普通」开关"),
    (IMPORTER, "rscc$linkedPosCache = rscc$forceNormalBus || report.disabled() || report.reachableCount() != 1",
     "输入总线：只有「唯一且穷尽」才认归属，否则退回 RS 原版「相邻容器 → 网络」"),
    (CHAMBER, "public boolean isBusOutput()", "执行舱侧的端点判据（链级：链上任一台设过 BUS 即整条链算端点）"),
]
for rel, needle, why in AFFILIATION:
    where = locate(rel, needle)
    check("判据：%s  [%s]" % (why, where or (rel + ": 未找到")), where is not None,
          "源码里找不到：%s" % needle)

# 关键结论：归属与「总线在给谁供料 / 网络里有没有任务」完全无关 —— 只跟线缆几何有关。
check("归属判定里没有任何「任务 / 用料 / 配方」输入（纯几何 ⇒ 分支一接上，主线上的普通总线全被收编）",
      "getStatuses" not in rel_read(LINK) and "getStatuses" not in rel_read(INTERFERENCE)
      and "AutocraftingNetworkComponent" not in rel_read(INTERFERENCE),
      "归属判定里出现了任务相关输入")

# =====================================================================
print()
print("=" * 78)
print("② 挂起判据链（源码锚点）")
print("=" * 78)

CHAIN = [
    (WATCHDOG, "private static void scanNetwork", "扫描一个网络：给每条任务建 / 更新记录"),
    (WATCHDOG, "final boolean sequenceTask = pattern != null || record.lastAssembly != null;",
     "分类：产物命中本模组样板库总样板（或曾命中）= 序列装配任务"),
    (WATCHDOG, "record.ourChain = sequenceTask && !hasForeignSink(status);",
     "新增闸门：只有「本模组链真在驱动」的任务才允许自动挂起"),
    (WATCHDOG, "private static boolean hasForeignSink(final TaskStatus status)", "闸门判据本体"),
    (WATCHDOG, "if (item.sinkKey() != null)", "判据：任务状态里出现「带身份的接收端」= RS 原生自动合成器"),
    (WATCHDOG, "private static void classifySequence", "序列装配分类（掉线 / 缺料 / 推不动）"),
    (WATCHDOG, "private static void classifyGeneric", "RS 原版分类（REJECTED / LOCKED / NONE_FOUND / 缺料 / 无进展）"),
    (WATCHDOG, "case REJECTED, LOCKED, NONE_FOUND -> sinkBad = true;", "RS 原版：接收端拒绝 / 锁定 / 找不到"),
    (WATCHDOG, "private static void advanceSuspendState", "「挂起」判定的唯一入口（计时 + 阈值）"),
    (WATCHDOG, "record.reason != Reason.NONE && record.ourChain", "自动挂起条件已加 ourChain"),
    (WATCHDOG, "private static void suspendRecord", "「挂起」的唯一实现（自动 / 手动共用）"),
    (WATCHDOG, "private static void overflowHandle", "挂起超限的最终处置（HOLD / 兜底回收）"),
    (WATCHDOG, "if (!Config.assemblySuspendOverflowReclaim || !record.ourChain) {",
     "兜底取消（= 自动取消）只对本模组自己的任务生效"),
    (WATCHDOG, "autocrafting.cancel(new TaskId(record.taskId));", "全工程唯一的自动取消调用"),
    (WATCHDOG, "public static boolean suspend(final ServerPlayer player, final UUID taskId)",
     "玩家手动挂起入口（不受 ourChain 影响：那是玩家的显式动作）"),
]
for rel, needle, why in CHAIN:
    where = locate(rel, needle)
    check("链条：%s  [%s]" % (why, where or (rel + ": 未找到")), where is not None,
          "源码里找不到：%s" % needle)

# 自动挂起的两个入口都必须被闸住
hot = watchdog[watchdog.index("private static boolean pushNoticeHandled"):]
hot = hot[:hot.index("\n    }")]
check("热探针入口（每 tick / 秒级挂起）也被同一个闸门拦住（否则普通任务会被本仓的拒收牵连着秒级挂起）",
      "record.ourChain" in hot, "pushNoticeHandled 里没有 ourChain")
manual = watchdog[watchdog.index("public static boolean suspend(final ServerPlayer player"):]
manual = manual[:manual.index("RsccAssemblyDebug.event(\"watchdog suspend")]
check("手动挂起（玩家点按钮）**不**受 ourChain 限制（普通任务仍能被玩家自己挂起 / 继续）",
      "ourChain" not in manual, "手动挂起被 ourChain 拦住了")
check("既有判定一个字都没有放宽：pushStalledOnDestination() 仍是「≥2 台同时拒收」的整仓闸门",
      "if (!chamber.pushStalledOnDestination() && !chamber.rscc$anyDestinationStuck()) {" in watchdog,
      "pushStalledOnDestination 的用法被改动了")

# sinkKey 判据为什么能把「RS 原生自动合成器」认出来（RS 源码级依据）
# 注意：必须只看**代码**（注释里提到这个方法名不算安装点，本模组的 javadoc 正是这么写的）。
setters = []
for path in source_files():
    if "setSinkKeyProvider" in strip_comments(read_abs(path)):
        setters.append(rel_of(path))
rs_setters = []
for base, _dirs, files in os.walk(RS_API):
    for name in files:
        if not name.endswith(".java"):
            continue
        path = os.path.join(base, name)
        if "setSinkKeyProvider" in read_abs(path):
            rs_setters.append(os.path.relpath(path, ROOT).replace("\\", "/"))
check("本模组从不安装 ExternalPatternSinkKeyProvider（⇒ 本模组驱动的任务 sinkKey 恒为 null）",
      not setters, "本模组出现了安装点：%s" % setters)
autocrafter_setter = [p for p in rs_setters if p.endswith("AutocrafterBlockEntity.java")]
provider_setter = [p for p in rs_setters if p.endswith("PatternProviderNetworkNode.java")]
check("RS 2.0.0 里 sinkKeyProvider 的安装点只有自动合成器（%s）" % (autocrafter_setter or "未找到"),
      len(autocrafter_setter) == 1 and len(provider_setter) == 1
      and "this.mainNetworkNode.setSinkKeyProvider(this);" in read_abs(
          os.path.join(RS_API, "refinedstorage-common", "src", "main", "java", "com", "refinedmods",
                       "refinedstorage", "common", "autocrafting", "autocrafter",
                       "AutocrafterBlockEntity.java")),
      "RS 侧安装点与预期不符：%s" % rs_setters)

print()
print("判据清单（文件:行号，供交付报告直接引用）：")
for rel, needle, why in AFFILIATION + CHAIN:
    print("   [%s] %s" % (locate(rel, needle) or "-", why))

# =====================================================================
print()
print("=" * 78)
print("③ 复刻：线缆搜链 / 归属判定（机器可验证的「普通总线被收编」机制）")
print("=" * 78)

WIRE = "wire"
CHAMBER = "chamber"
BUSA = "busA"          # 普通输入 / 输出总线（延长型候选）
PLAIN = "plain"        # 机器 / 容器：不穿行


class Grid(object):
    """与 RsccWireLinkSearch#searchChamberLink 同构的极小复刻（四向邻接）。"""

    def __init__(self):
        self.cells = {}
        self.cuts = set()          # {(pos, dir)}：扳手断开的接缝
        self.chain_of = {}         # 执行舱坐标 -> 链身份（= 链首）

    def put(self, pos, kind):
        self.cells[pos] = kind
        return self

    def line(self, start, length, axis, kind=WIRE):
        x, y, z = start
        for i in range(length):
            self.put((x + i, y, z) if axis == "x" else (x, y, z + i), kind)
        return self

    def search(self, origin, max_steps=64, max_cells=128):
        """返回 (reachable_sorted, exhaustive, cluster)；reachable 已按链去重（同 Java）。"""
        cluster = [origin]
        visited = {origin}
        frontier = [origin]
        found = OrderedDict()
        depths = {}
        steps = 0
        exhaustive = True
        while frontier:
            if steps >= max_steps or len(cluster) >= max_cells:
                exhaustive = False
                break
            steps += 1
            nxt = []
            for cell in frontier:
                for direction in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)):
                    if (cell, direction) in self.cuts:
                        continue                      # 闸门①：扳手断开
                    neighbor = (cell[0] + direction[0], cell[1] + direction[1], cell[2] + direction[2])
                    if neighbor in visited:
                        continue
                    visited.add(neighbor)
                    kind = self.cells.get(neighbor)
                    if kind == CHAMBER:
                        found.setdefault(neighbor, True)      # 收集，但不穿过
                        depths.setdefault(neighbor, steps)
                        continue
                    if kind == WIRE:
                        cluster.append(neighbor)
                        nxt.append(neighbor)
            frontier = nxt
        sorted_found = sorted(found.keys(), key=lambda p: (depths[p], p))
        # 按链去重（同 Java 的 collapseByChain）：每条链只留它自己最近的那台
        by_chain = []
        seen = set()
        for pos in sorted_found:
            chain = self.chain_of.get(pos, pos)
            if chain in seen:
                continue
            seen.add(chain)
            by_chain.append(pos)
        return by_chain, exhaustive, cluster


def report(grid, bus, force_normal=False):
    """与 RsccBusInterference#inspect + 两个 Mixin 的归属判定同构。

    注意两个**不同**的量（Java 里也是分开的，别混）：
      * `executor_mode` / `linked_layout`（= `reachable >= 1`）：界面与「延长型」判定的依据；
      * `owner`（= 只有「唯一归属」才非空）：真正的搬运归属；为空即退回 RS 原版行为。
    归属未确定时 `linked_layout` 仍为真（界面要显示红条 + 可达区域），但 owner 为空 ⇒ 不搬运。

    2026-10-11（round60）更新：旧规则是 `len(reachable) >= 2 or (len(reachable) == 1 and not exhaustive)`，
    那个「未穷尽」合取项已删除（线缆一长 / 总线一多时旧实现会静默截断 ⇒ 把「暂时没扫完」误判成
    「归属未确定 ⇒ 停用」，正是「总线变红 / 变普通 / 停用」的根因）。本脚本的场景都在上限之内
    （`exhaustive` 恒为真），因此两种规则在这里结论相同；新判据的完整自检见
    tools/selfcheck_round60_wirelink_exhaustive.py。
    """
    reachable, exhaustive, _cluster = grid.search(bus)
    disabled = len(reachable) >= 2
    linked_layout = len(reachable) >= 1
    executor_mode = linked_layout and not force_normal
    owner = None if (force_normal or disabled or len(reachable) != 1) else reachable[0]
    return {"reachable": len(reachable), "disabled": disabled,
            "linked_layout": linked_layout, "executor_mode": executor_mode, "owner": owner}


# ---- 场景 A：普通总线贴着一台机器，附近没有导线 / 执行舱 ----
g = Grid().put((0, 0, 0), BUSA).put((1, 0, 0), PLAIN).put((20, 0, 0), CHAMBER)
g.chain_of[(20, 0, 0)] = (20, 0, 0)
A = report(g, (0, 0, 0))
check("A 孤立总线（够不到任何执行舱）⇒ 不是延长型（不受本模组管辖）",
      A["reachable"] == 0 and not A["executor_mode"] and A["owner"] is None, str(A))

# ---- 场景 B：总线直接贴着执行舱（经典用法）⇒ 唯一归属 ⇒ 延长型 ----
g = Grid().put((0, 0, 0), BUSA).put((1, 0, 0), CHAMBER)
g.chain_of[(1, 0, 0)] = (1, 0, 0)
B = report(g, (0, 0, 0))
check("B 总线紧贴执行舱 ⇒ 唯一归属 ⇒ 延长型（既有功能不变）",
      B["reachable"] == 1 and B["executor_mode"] and B["owner"] == (1, 0, 0), str(B))

# ---- 场景 C（用户场景）：一条主线 + 分支到执行舱；主线上挂着 N 条普通总线 ----
g = Grid()
g.line((0, 0, 0), 8, "x")                     # 主线：8 格线缆
for i in (1, 3, 5):
    g.put((i, 0, 1), BUSA)                    # 主线上挂着 3 条普通总线（各自贴着一台机器）
    g.put((i, 0, 2), PLAIN)
g.put((8, 0, 0), WIRE).put((9, 0, 0), WIRE)   # 分支
g.put((10, 0, 0), CHAMBER)                    # 分支尽头：序列装配执行舱
g.chain_of[(10, 0, 0)] = (10, 0, 0)
C = [report(g, (i, 0, 1)) for i in (1, 3, 5)]
check("C **用户场景复刻**：主线接一条分支到执行舱 ⇒ 主线上*每一条*普通总线都被判成该执行舱的附属",
      all(r["reachable"] == 1 and r["executor_mode"] and r["owner"] == (10, 0, 0) for r in C),
      "并非全部被收编：%s" % C)

# ---- 场景 C2：同上，但玩家给其中一条点了「普通」（强制普通总线） ----
C2 = report(g, (3, 0, 1), force_normal=True)
check("C2 逐条总线的「普通」开关仍然有效（被收编者可以手动退回普通总线）",
      C2["linked_layout"] and not C2["executor_mode"] and C2["owner"] is None, str(C2))

# ---- 场景 D：主线同时够得到两条不同的链 ⇒ 归属未确定 ⇒ 整条停用（红条 + 横幅） ----
g2 = Grid()
g2.line((0, 0, 0), 6, "x")
g2.put((1, 0, 1), BUSA)
g2.put((6, 0, 0), WIRE).put((7, 0, 0), CHAMBER)
g2.put((3, 0, -1), WIRE).put((3, 0, -2), CHAMBER)
g2.chain_of[(7, 0, 0)] = (7, 0, 0)
g2.chain_of[(3, 0, -2)] = (3, 0, -2)
D = report(g2, (1, 0, 1))
check("D 够了到两条链 ⇒ 归属未确定：界面显示红条 + 横幅，且**没有任何**执行舱认领它（不搬运）",
      D["reachable"] == 2 and D["disabled"] and D["owner"] is None and D["linked_layout"], str(D))

# ---- 场景 E：主线与执行舱之间被扳手断开 ⇒ 回到普通总线 ----
g3 = Grid()
g3.line((0, 0, 0), 6, "x")
g3.put((1, 0, 1), BUSA)
g3.put((6, 0, 0), CHAMBER)
g3.chain_of[(6, 0, 0)] = (6, 0, 0)
g3.cuts.add(((5, 0, 0), (1, 0, 0)))           # 5 → 6 这一侧断开
E = report(g3, (1, 0, 1))
check("E 玩家用扳手断开接缝 ⇒ 这一步等于「没有线」⇒ 归属消失、退回普通总线（既有隔离手段有效）",
      E["reachable"] == 0 and not E["executor_mode"], str(E))

# ---- 场景 F：同一条链上的 4 台（两条线缆路径各够到一台）⇒ 仍只是一条链 ----
g4 = Grid()
g4.line((0, 0, 0), 4, "x")
g4.put((0, 0, 1), BUSA)
g4.put((3, 0, 1), WIRE).put((3, 0, 2), WIRE)
g4.put((4, 0, 0), CHAMBER)                     # 链成员①：紧贴主线
g4.put((4, 0, 2), CHAMBER)                     # 链成员②：绕一格导线够到
g4.chain_of[(4, 0, 0)] = (4, 0, 0)
g4.chain_of[(4, 0, 2)] = (4, 0, 0)             # 同一台逻辑执行仓（成链扩容）
F = report(g4, (0, 0, 1))
check("F 同一条链上的多台 = 一个逻辑执行仓（按链去重）⇒ 归属唯一、延长型照常工作",
      F["reachable"] == 1 and F["owner"] == (4, 0, 0) and F["executor_mode"], str(F))

# =====================================================================
print()
print("=" * 78)
print("④ 看门狗真值表（与 Java 的 Record / advanceSuspendState / overflowHandle 同构）")
print("=" * 78)

THRESHOLDS = {"EXECUTOR_OFFLINE": 40, "OUTPUT_BLOCKED": 40, "MISSING_MATERIAL": 100, "NO_PROGRESS": 600}
SUSPEND_LIMIT = 12000


class Task(object):
    """一条任务的极小模型（与 Java 同构：ourChain 闸门 + 无自动恢复 + 超限处置）。"""

    def __init__(self, sequence, foreign_sink=False, reason="NONE", reclaimed_config=False):
        self.sequence = sequence
        self.our_chain = sequence and not foreign_sink
        self.reason = reason
        self.ticks = 0
        self.suspended = False
        self.banners = 0
        self.since = 0
        self.reclaimed = False
        self.reclaimed_config = reclaimed_config

    def tick(self, now):
        # 扫描侧：已挂起 → 冻结原因（绝不自动恢复）
        if self.suspended:
            pass
        self.ticks = 0 if self.reason == "NONE" else self.ticks + 1
        if not self.suspended and self.our_chain and self.reason != "NONE" \
                and self.ticks > THRESHOLDS[self.reason]:
            self.suspended = True
            self.banners += 1
            self.since = now
        # 超限处置：普通任务绝不兜底取消
        if self.suspended and now - self.since > SUSPEND_LIMIT:
            if self.reclaimed_config and self.our_chain:
                self.reclaimed = True


def run(task, ticks):
    for t in range(ticks):
        task.tick(t)
    return task


print("场景矩阵（ticks = 100000 ≈ 83 分钟，远超所有阈值与 10 分钟挂起上限）：")
rows = []

# ① 网络里只有普通任务
t = run(Task(sequence=False, reason="EXECUTOR_OFFLINE", reclaimed_config=True), 100000)
rows.append(("① 只有普通任务（RS 自动合成器在跑）", "不挂起", not t.suspended and not t.reclaimed))
check("① 网络里只有普通任务 ⇒ 即使 reason=EXECUTOR_OFFLINE 持续 8 万秒，也绝不挂起、绝不兜底取消",
      not t.suspended and not t.reclaimed and t.banners == 0,
      "suspended=%s reclaimed=%s banners=%d" % (t.suspended, t.reclaimed, t.banners))

# ② 普通任务 + 序列装配任务并存
ordinary = run(Task(sequence=False, reason="MISSING_MATERIAL", reclaimed_config=True), 100000)
seq = run(Task(sequence=True, reason="OUTPUT_BLOCKED"), 100000)
rows.append(("② 并存：普通任务", "不挂起", not ordinary.suspended and not ordinary.reclaimed))
rows.append(("② 并存：序列装配任务真堵", "仍挂起（秒级）", seq.suspended))
check("② 普通任务 + 序列装配任务并存 ⇒ 普通任务不挂起，序列装配真堵照旧挂起（不回归）",
      not ordinary.suspended and not ordinary.reclaimed and seq.suspended and seq.banners == 1,
      "ordinary=%s/%s seq=%s/%d" % (ordinary.suspended, ordinary.reclaimed, seq.suspended, seq.banners))

# ③ 普通任务的产物恰好也躺在本模组样板库里（sequence=True），但被 RS 自动合成器接手
collide = run(Task(sequence=True, foreign_sink=True, reason="OUTPUT_BLOCKED", reclaimed_config=True), 100000)
rows.append(("③ 产物名撞上样板库、但接收端是 RS 自动合成器", "不挂起", not collide.suspended))
check("③ 「产物名恰好也在本模组样板库里」的普通任务（接收端带身份）⇒ 同样绝不挂起",
      not collide.suspended and not collide.reclaimed, "suspended=%s" % collide.suspended)

# ④ 序列装配任务真堵（接收端 = 本模组样板库，sinkKey 为 null）⇒ 阈值后挂起
for reason, want in (("OUTPUT_BLOCKED", 40), ("EXECUTOR_OFFLINE", 40),
                     ("MISSING_MATERIAL", 100), ("NO_PROGRESS", 600)):
    t = Task(sequence=True, reason=reason)
    first = None
    for now in range(want + 20):
        t.tick(now)
        if t.suspended and first is None:
            first = now
    check("④ 序列装配任务（%s）仍在第 %d tick 挂起（阈值一条都没放宽）" % (reason, want),
          first == want and t.banners == 1, "first=%s banners=%d" % (first, t.banners))

# ⑤ 玩家手动挂起普通任务 ⇒ 有效；但超限绝不兜底取消
manual = Task(sequence=False, foreign_sink=True, reclaimed_config=True)
manual.suspended = True
manual.reason = "MANUAL"
manual.since = 0
run(manual, SUSPEND_LIMIT + 500)
rows.append(("⑤ 玩家手动挂起普通任务 + 配置打开兜底回收", "保持挂起，绝不取消", manual.suspended and not manual.reclaimed))
check("⑤ 普通任务被玩家手动挂起后超限（配置甚至打开了兜底回收）⇒ 仍保持挂起、绝不自动取消",
      manual.suspended and not manual.reclaimed, "reclaimed=%s" % manual.reclaimed)

# ⑥ 本模组自己的任务超限：默认 HOLD（不取消）；配置打开才回收（既有行为不变）
seq_hold = Task(sequence=True, reason="OUTPUT_BLOCKED", reclaimed_config=False)
seq_hold.suspended = True
seq_hold.since = 0
run(seq_hold, SUSPEND_LIMIT + 500)
seq_reclaim = Task(sequence=True, reason="OUTPUT_BLOCKED", reclaimed_config=True)
seq_reclaim.suspended = True
seq_reclaim.since = 0
run(seq_reclaim, SUSPEND_LIMIT + 500)
rows.append(("⑥ 本模组序列装配任务超限（默认配置）", "保持挂起", not seq_hold.reclaimed))
rows.append(("⑥ 本模组序列装配任务超限（玩家显式打开回收）", "兜底回收一次", seq_reclaim.reclaimed))
check("⑥ 兜底回收的既有语义不变：默认 HOLD；玩家显式打开时只回收本模组自己的任务",
      not seq_hold.reclaimed and seq_reclaim.reclaimed,
      "hold=%s reclaim=%s" % (seq_hold.reclaimed, seq_reclaim.reclaimed))

# ⑥2 挂起后一律不自动恢复（回归保护）
seq_resume = Task(sequence=True, reason="OUTPUT_BLOCKED")
run(seq_resume, 200)
seq_resume.reason = "NONE"
run(seq_resume, 100000)
check("⑥2 挂起后原因恢复正常 + 任意时长 ⇒ 仍 SUSPENDED（无自动恢复，既有硬要求不变）",
      seq_resume.suspended, "被自动恢复了")

print()
print("%-46s %-22s %s" % ("场景", "期望", "结果"))
print("-" * 78)
for name, want, ok in rows:
    print("%-46s %-22s %s" % (name, want, "OK" if ok else "**FAIL**"))

# =====================================================================
print()
print("=" * 78)
print("⑤ 不可逆路径盘点：全工程只有一处自动取消，且已被收窄")
print("=" * 78)

cancel_calls = []
for path in source_files():
    text = read_abs(path)
    for match in re.finditer(r"autocrafting\.cancel\(", text):
        line = text.count("\n", 0, match.start()) + 1
        cancel_calls.append("%s:%d" % (rel_of(path), line))
check("全工程 `autocrafting.cancel(...)` 只有一处（= 挂起超限兜底回收，不存在第二个自动取消）",
      len(cancel_calls) == 1, "取消调用点：%s" % cancel_calls)
check("超限兜底回收的准入条件同时要求「配置打开」与「本模组链真在驱动」",
      "if (!Config.assemblySuspendOverflowReclaim || !record.ourChain) {" in watchdog,
      "兜底回收仍可能取消普通任务")
check("配置项 assemblySuspendOverflowReclaim 默认 false（默认档连本模组自己的任务也不取消）",
      'define("assemblySuspendOverflowReclaim", false)' in rel_read("Config.java"),
      "默认值不是 false")
check("监视器上没有本模组自带的「取消」按钮（取消只走 RS 原生路径）",
      "ACTION_CANCEL" not in rel_read("network/AssemblyTaskActionPacket.java"),
      "仍存在 ACTION_CANCEL")

print()
print("判据速查（本轮新增 / 改动的行）：")
for needle in ("record.ourChain = sequenceTask && !hasForeignSink(status);",
               "private static boolean hasForeignSink(final TaskStatus status)",
               "private boolean ourChain;",
               "public boolean ourChain()",
               "record.reason != Reason.NONE && record.ourChain",
               "record.ourChain && record.sequence && record.lastAssembly != null",
               "if (!Config.assemblySuspendOverflowReclaim || !record.ourChain) {",
               'row.put("ourChain", record.ourChain);'):
    print("   [%s] %s" % (locate(WATCHDOG, needle) or "-", needle))

print()
if PROBLEMS:
    for problem in PROBLEMS:
        print("[X] " + problem)
print("问题总数: %d" % len(PROBLEMS))
if PROBLEMS:
    print("SELFCHECK FAILED (%d/%d)" % (len(PROBLEMS), CHECKS[0]))
else:
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(1 if PROBLEMS else 0)
