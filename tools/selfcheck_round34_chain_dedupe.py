# -*- coding: utf-8 -*-
"""自检（第 34 轮 · 线缆搜链「按链去重」）：同链多台 = 一个逻辑执行仓。

用法：python tools/selfcheck_round34_chain_dedupe.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户现场（缺陷，由上一轮「isBusOutput 改成链级」引入的副作用）
----------------------------------------------------------------
4 台同配方执行仓沿箭头排成一条链（当作<b>一个</b>逻辑执行仓扩容）。为了让「总线接在链上任意
一台旁都能连上」，`isBusOutput()` 改成了链级（本台设过 <b>或</b> 链上任一台设过）。副作用：
若一条总线的线缆<b>同时</b>够到同一条链的两台成员，搜链就按「台」数出可达 = 2 ⇒ 归属判定判
「归属未确定 ⇒ 停用 + 红条」。而这两台其实是<b>同一个逻辑执行仓</b>，属误判。

本自检钉住五件事
----------------
K1 去重发生在「产出 reachable」的那一步（唯一实现 `RsccWireLinkSearch#collapseByChain`），
   而不是判定侧 —— 否则 `reachableCount() == 1` 闸门照样把总线停用；
K2 链身份<b>复用</b>唯一事实源（`SequenceExecutionChamberBlockEntity#chainIdentity()` = 链首坐标），
   搜链里<b>没有</b>第二套链推导（不出现 chainMembers/chainHead）；
K3 判定真值表：同链 2 台 ⇒ 唯一归属（不停用）；跨链 2 台 ⇒ 停用；接在<b>非链首</b>的那台上 ⇒
   仍然连得上（代表台只从「已经够到的那几台」里挑，绝不按代表台裁剪候选）；
K4 `reachable=0`（`Report.CLEAR`）语义与「≥2 才停用」的跨链语义一字未动；
K5 既有的「曾连到执行舱 → 现在 0 台」边沿提示只看「0 还是 ≥1」，去重不会让它误报 / 重复弹。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source):
    """去掉 /* */ 与 // 注释：断言「代码里没有某样东西」时不能被注释里的字面量干扰。"""
    out = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith("/*", index):
            stop = source.find("*/", index + 2)
            index = length if stop < 0 else stop + 2
            continue
        if source.startswith("//", index):
            stop = source.find("\n", index)
            index = length if stop < 0 else stop
            continue
        out.append(source[index])
        index += 1
    return "".join(out)


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ======================================================================
# 与 Java 逐字同构的模型：collapseByChain（按链去重）+ ambiguous（判定）
# ======================================================================
class Chamber(object):
    """一台执行仓：坐标 + 它所属的链身份（= 链首坐标，同一条链上各台相同）。"""

    def __init__(self, pos, identity):
        self.pos = pos
        self.identity = identity


def collapse_by_chain(sorted_positions, chambers):
    """RsccWireLinkSearch#collapseByChain 的复刻（保留首次出现 = 该链最近的那台）。"""
    if len(sorted_positions) <= 1:
        return list(sorted_positions)  # 常见情形：一次链推导都不做
    heads = []
    distinct = []
    for pos in sorted_positions:
        chamber = chambers.get(pos)
        if chamber is not None and chamber.identity in heads:
            continue  # 这条链在前面已经有更近的一台被记下了
        if chamber is not None:
            heads.append(chamber.identity)
        distinct.append(pos)
    return distinct


def ambiguous(reachable, exhaustive=True):
    """LinkWalk#ambiguous 的复刻（reachable 已是按链去重的表）。"""
    return len(reachable) >= 2 or (len(reachable) == 1 and not exhaustive)


def report(reachable, exhaustive=True):
    """RsccBusInterference#inspect 的复刻：(disabled, reachableCount)。"""
    if not reachable:
        return False, 0  # Report.CLEAR：一台执行舱都够不到，总线只是一条普通总线
    return ambiguous(reachable, exhaustive), len(reachable)


def on_resolve(seq):
    """LinkLostLatch#onResolve 的复刻（只关心 0 / ≥1 这条边沿）。"""
    baseline = False
    ever_linked = False
    announced = False
    out = []
    for count in seq:
        linked = count >= 1
        if not baseline:
            baseline = True
            ever_linked = linked
            announced = not linked
            out.append(False)
            continue
        if linked:
            ever_linked = True
            announced = False
            out.append(False)
            continue
        if not ever_linked or announced:
            out.append(False)
            continue
        announced = True
        out.append(True)
    return out


# 链 A = 4 台同配方仓（链首 A1，坐标升序 A1<A2<A3<A4）—— 线缆可能够到其中任意若干台。
CHAIN_A = {"A1": "A1", "A2": "A1", "A3": "A1", "A4": "A1"}
# 链 B = 另一条同配方链（链首 B1），与链 A 不是同一个逻辑执行仓。
CHAIN_B = {"B1": "B1", "B2": "B1"}
CHAMBERS = {name: Chamber(name, identity) for name, identity in list(CHAIN_A.items()) + list(CHAIN_B.items())}


def main():
    linksearch = read(SRC, "support", "RsccWireLinkSearch.java")
    interference = read(SRC, "support", "RsccBusInterference.java")
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    exporter = read(SRC, "mixin", "exporter", "AbstractExporterBlockEntityMixin.java")
    importer = read(SRC, "mixin", "importer", "AbstractImporterBlockEntityMixin.java")

    # ---------------- K1 去重的位置 ----------------
    section("K1 去重发生在「产出 reachable」的那一步（唯一实现里），不是判定侧")
    check("锚点① 唯一实现里新增 private static collapseByChain",
          "private static List<BlockPos> collapseByChain(" in linksearch)
    check("锚点② searchChamberLink 在排序后立刻按链去重，LinkWalk 收的是去重表（不是原表）",
          "final List<BlockPos> distinct = collapseByChain(reachable, chambers);" in linksearch
          and "return new LinkWalk(chambers.get(distinct.get(0)), List.copyOf(distinct)," in linksearch
          and "List.copyOf(reachable)," not in linksearch)
    check("锚点③ 去重表仍是「先距离近、再坐标字典序」⇒ 第 0 个仍是旧语义的归属（owner 不变义）",
          "reachable.sort((a, b) -> {" in linksearch
          and "final int byDepth = Integer.compare(depths.getOrDefault(a, 0), depths.getOrDefault(b, 0));"
          in linksearch)
    check("锚点④ reachableCount / 注释已明确为「链（逻辑执行仓）数」",
          "public int reachableCount() {" in linksearch
          and "线缆可达的<b>链（= 逻辑执行仓）数</b>" in linksearch)
    check("锚点⑤ 判定侧未新增「按链裁剪候选」的闸门：inspect 仍只看 walk.linked / walk.ambiguous",
          "final RsccWireLinkSearch.LinkWalk walk = RsccWireLinkSearch.searchChamberLink(level, busPos);"
          in interference
          and "if (!walk.linked()) {" in interference
          and "return new Report(walk.ambiguous(), !walk.exhaustive(), reachable.size()," in interference)

    # ---------------- K2 链身份复用唯一事实源 ----------------
    section("K2 链身份复用唯一事实源（链首坐标），搜链里没有第二套链推导")
    check("锚点① SequenceExecutionChamberBlockEntity 新增 public 只读 chainIdentity()（= 链首坐标）",
          "public BlockPos chainIdentity() {" in chamber
          and "return chainHead().worldPosition;" in body(chamber, "public BlockPos chainIdentity() {"))
    check("锚点② 它是既有 chainHead() 的纯投影（不新造链推导）",
          "public SequenceExecutionChamberBlockEntity chainHead() {" in chamber)
    check("锚点③ 搜链里没有第二套链推导（代码里不出现 chainMembers / chainHead / chainSize；只问 chainIdentity）",
          "chamber.chainIdentity().asLong()" in strip_comments(linksearch)
          and strip_comments(linksearch).count("chainIdentity(") == 1
          and "chainMembers(" not in strip_comments(linksearch)
          and "chainHead(" not in strip_comments(linksearch)
          and "chainSize(" not in strip_comments(linksearch))
    check("锚点④ 去重的键是「同链 ⇔ 同链首坐标」，且缺失方块实体时按「自成一条链」保守处理（宁可多算一条链）",
          "if (chamber != null && !chainHeads.add(chamber.chainIdentity().asLong())) {" in linksearch
          and "continue; // 这条链在前面已经有更近的一台被记下了：同链合并，不重复计" in linksearch)
    check("锚点⑤ 唯一归属（可达 ≤1）时原样返回：一次链推导都不做（单台仓 / 普通总线行为逐字不变）",
          "if (sorted.size() <= 1) {" in linksearch
          and "return sorted; // 常见情形（唯一归属 / 一台都够不到）：一次链推导都不做，开销为零" in linksearch)
    check("锚点⑥ 链身份没有渗进任何排队 / 名额 / 在制 / 备料判据（全工程只有搜链那一处消费）",
          chamber.count("chainIdentity()") >= 1
          and strip_comments(linksearch).count("chainIdentity(") == 1
          and chamber.count("outputMode != OutputMode.BUS") == 2)

    # ---------------- K3 判定真值表 ----------------
    section("K3 反例表：同链 2 台 ⇒ 唯一归属；跨链 2 台 ⇒ 停用；接在非链首旁 ⇒ 仍连得上")

    # ① 只够到链 A 的一台，而那台<b>不是</b>链首（代表台）—— 用户明确要求的形态
    r = collapse_by_chain(["A3"], CHAMBERS)
    check("① 线缆只够到 A3（链首是 A1，A3 不是代表台）⇒ 去重后仍是 [A3]，归属 = A3 ⇒ 连得上、不停用",
          r == ["A3"] and report(r) == (False, 1), "%s %s" % (r, report(r)))

    # ② 同一条链够到两台（本轮的缺陷形态）
    r = collapse_by_chain(["A2", "A4"], CHAMBERS)
    check("② 线缆同时够到同链 A2 / A4 ⇒ 去重后只剩最近那台 [A2]（同一个逻辑执行仓）⇒ 不停用",
          r == ["A2"] and report(r) == (False, 1), "%s %s" % (r, report(r)))

    # ③ 同链三台（链首 + 两台成员）
    r = collapse_by_chain(["A1", "A2", "A3"], CHAMBERS)
    check("③ 同链三台 ⇒ 仍只算一条链（代表台 = 最近的那台）⇒ 不停用",
          r == ["A1"] and report(r) == (False, 1), "%s %s" % (r, report(r)))

    # ④ 反过来的顺序：最近的是非链首成员
    r = collapse_by_chain(["A4", "A1"], CHAMBERS)
    check("④ 同链两台且最近的是 A4 ⇒ 代表台 = A4（谁近留谁，与链首无关）⇒ 不停用",
          r == ["A4"] and report(r) == (False, 1), "%s %s" % (r, report(r)))

    # ⑤ 跨链两台：既有语义必须保住
    r = collapse_by_chain(["A2", "B1"], CHAMBERS)
    check("⑤ 够到链 A 的 A2 与链 B 的 B1（两条不同的链）⇒ 仍判「归属未确定 ⇒ 停用」",
          r == ["A2", "B1"] and report(r) == (True, 2), "%s %s" % (r, report(r)))

    # ⑥ 同链多台 + 另一条链一台：去重后仍是两条不同的链
    r = collapse_by_chain(["A2", "A3", "B1"], CHAMBERS)
    check("⑥ 同链 A2/A3 合并后与 B1 并存 ⇒ 仍是 2 条不同的链 ⇒ 停用",
          r == ["A2", "B1"] and report(r) == (True, 2), "%s %s" % (r, report(r)))

    # ⑦ 一台都够不到：Report.CLEAR 语义不变
    check("⑦ 够不到任何执行舱 ⇒ Report.CLEAR（disabled=false, count=0），去重不参与",
          report([]) == (False, 0) and collapse_by_chain([], CHAMBERS) == [])

    # ⑧ 恰好一条链但探查不穷尽：保守分支不能被去重削弱
    r = collapse_by_chain(["A2", "A4"], CHAMBERS)
    check("⑧ 同链多台但探查未能穷尽 ⇒ 仍是「归属未确定」（宁可保护：别处可能还有一条链）",
          report(r, exhaustive=False) == (True, 1), "%s" % (report(r, exhaustive=False),))

    # ⑨ 唯一归属时去重是恒等（不含任何未定义行为）
    check("⑨ 单元素表的去重恒等（唯一归属 / 单台仓行为逐字不变）",
          all(collapse_by_chain([p], CHAMBERS) == [p] for p in CHAMBERS))

    # ⑩ 「接在任意一台旁都连得上」的通例：链 A 任取一台 ⇒ 结论恒为「连得上」
    ok_all = True
    detail = []
    for member in ("A1", "A2", "A3", "A4"):
        outcome = report(collapse_by_chain([member], CHAMBERS))
        detail.append("%s=%s" % (member, outcome))
        ok_all = ok_all and outcome == (False, 1)
    check("⑩ 链 A 的每一台单独被够到时都判「连得上」（不存在只有代表台才行）", ok_all, " ".join(detail))

    # ⑪ 归入结论：Java 里同一套判据仍在（不是只在 Python 模型里成立）
    check("⑪ Java 判据与模型同源：ambiguous() = 「≥2 条链」或「1 条链且不穷尽」",
          "return reachable.size() >= 2 || (reachable.size() == 1 && !exhaustive);" in linksearch)
    check("⑫ 两个 Mixin 仍以 reachableCount() == 1 作为「唯一归属」闸门（去重后 = 恰好一条链）",
          "rscc$linkedPosCache = rscc$forceNormalBus || report.disabled() || report.reachableCount() != 1"
          in exporter
          and "rscc$linkedPosCache = rscc$forceNormalBus || report.disabled() || report.reachableCount() != 1"
          in importer)

    # ---------------- K4 既有语义未动 ----------------
    section("K4 既有语义一字未动：reachable=0 / 跨链 ≥2 / 不穷尽")
    check("① Report.CLEAR 仍是同一条「可达 0、不 disabled」的实例",
          "public static final Report CLEAR = new Report(false, false, 0, List.of(), List.of());"
          in interference)
    check("② inspect 仍是「一台都够不到 → CLEAR」（不新增判定分支）",
          "if (!walk.linked()) {" in interference and "return Report.CLEAR;" in interference
          and "walk.ambiguous()" in interference)
    check("③ 搜链里仍在「重入守卫」下运行（去重不会在搜索途中二次进入搜索）",
          "if (RsccSearchGuard.isSearching()) {" in linksearch
          and "RsccSearchGuard.enter();" in linksearch
          and "RsccSearchGuard.exit();" in linksearch)
    collapse_code = strip_comments(body(linksearch, "private static List<BlockPos> collapseByChain("))
    check("④ 去重是只读的：只维护本地表并对 chambers 取值，不写方块 / 不写任何字段",
          "chambers.get(pos)" in collapse_code
          and ".put(" not in collapse_code
          and "setBlock" not in collapse_code)
    check("⑤ 分类判定闸门（输出模式原始字段）仍是逐字比较：链级端点判定没有渗进排队 / 名额判据",
          chamber.count("outputMode != OutputMode.BUS") == 2
          and "return outputMode == OutputMode.BUS || chainHasBusOutputMember();" in chamber)

    # ---------------- K5 边沿提示不受影响 ----------------
    section("K5 「曾经连到执行舱 → 现在 0 台」的边沿提示：只关心 0 / ≥1，去重不改结论")
    check("① 首个样本就是 0 不弹；0 持续不重复弹",
          on_resolve([0, 0, 0]) == [False, False, False])
    check("② 1 → 0 弹一次（去重后 count 仍是 1：同链多台也不影响）",
          on_resolve([1, 0, 0]) == [False, True, False])
    check("③ 「够到两条链」（去重后 count=2）→ 0 同样算「曾经连过」并弹一次",
          on_resolve([2, 0]) == [False, True])
    check("④ Java 的判据仍是 reachableCount >= 1（与台 / 链口径无关）",
          "final boolean linked = reachableCount >= 1;" in interference)
    check("⑤ 两侧仍在同一状态机里调用同一份 latch（不新增第二条提示路径）",
          "rscc$announceLinkLost();" in exporter and "rscc$announceLinkLost();" in importer
          and "if (!rscc$linkLostLatch.onResolve(rscc$linkReport.reachableCount())) {" in exporter
          and "if (!rscc$linkLostLatch.onResolve(rscc$linkReport.reachableCount())) {" in importer)

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


def body(source, anchor, end=None):
    """取 anchor 之后的片段（到 end 或文件末尾）：用于「某方法体内」的断言。"""
    index = source.find(anchor)
    if index < 0:
        return ""
    index += len(anchor)
    rest = source[index:]
    if end is not None:
        stop = rest.find(end)
        if stop >= 0:
            return rest[:stop]
    return rest


if __name__ == "__main__":
    sys.exit(main())
