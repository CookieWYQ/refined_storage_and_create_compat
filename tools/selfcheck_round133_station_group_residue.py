#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 133 轮自检：收尾残留按「整工位」判定 / 回收 + diag 能拍到现场。

背景（用户 2026-11-07 实测）
--------------------------
「下单 20 个，做完之后多发了一个金板；这个金板完成了一次装配，然后就没有回去。」

代码层面的根因（本轮结论）
- 在制登记表 `inFlightUnits` 的键是**推料成功的那一格**（`rscc$noteUnitPushed` 在
  `RsccChamberExportStrategy` 里记的就是 `target`）。而「机械手 + 它朝向 2 格外的置物台」
  是**同一个物理工位**：本仓把金板推给机械手，机械手把金板压到置物台上的在制件上，
  装配完的过渡件留在**置物台**（`stationKey`）那一格 —— 登记坐标（机械手）此刻已经空了。
- 旧实现的 `stationHoldsPipelineResidue` 与 `clearDeadStationPieceAt` 都只扫
  **登记坐标那一个 handler**，于是：①任务结束边沿判「工位已空」⇒ 删掉凭据；
  ②有界 sweep 只遍历登记表、且只扫登记坐标 ⇒ 置物台上那件过渡件**此后没有任何路径收得回来**。
  这条缝隙与用户看到的症状逐字吻合。

本脚本四条腿
------------
① 源码锚点：`stationGroupPositions` 存在，且 `stationHoldsPipelineResidue` / `clearDeadStationPieces`
   都改成了「整工位」口径（旧写法 `itemHandlerAt(level, station)` 只允许出现在逐格实现里）；
② 安全锚点：扫同工位其它容器时**只收过渡件**（`allowStartIngredient == false`），
   非过渡件的放行分支必须被 `if (!allowStartIngredient) continue;` 挡在前面；
   搬运仍是 SIMULATE → extract → insert，装不下原样还回、绝不销毁（不丢不复制）；
③ diag 锚点：`rscc$diagReport()` 补出 `inFlightUnits` 明细（登记坐标 / 整工位坐标组 / 物品 /
   配方 / 步序 / 停留 tick），`RsccDiag.blockEntities()` 把「玩家周边 view-distance 个已加载区块」
   并入枚举（两次快照都只拍到出生点试炼密室 ⇒ 玩家保存的 diag 对基地零信息）；
④ 反例自证：把旧的「只扫登记坐标」写法拼回去，断言判据真的判负。

用法：python tools/selfcheck_round133_station_group_residue.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CHAMBER = os.path.join(PKG, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
DIAG = os.path.join(PKG, "support", "RsccDiag.java")

_passed = 0
_failed = 0
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL %s" % name)
        if detail:
            for line in str(detail).strip().splitlines()[:10]:
                print("       " + line)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def method_body(text, signature):
    index = text.find(signature)
    if index < 0:
        return ""
    start = text.find("{", index)
    if start < 0:
        return ""
    depth = 0
    for position in range(start, len(text)):
        char = text[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    return ""


def legacy_scan_only_registered(body):
    """反例判据：方法体里「只扫登记坐标那一个 handler」= 旧写法（应当被本轮改掉）。"""
    return ("itemHandlerAt(level, station)" in body
            and "stationGroupPositions(" not in body)


def group_source_anchors():
    print("\n=== ① 源码锚点：收尾判定 / 回收改成「整工位」口径 ===")
    check("①-1 SequenceExecutionChamberBlockEntity.java 存在", os.path.isfile(CHAMBER))
    if not os.path.isfile(CHAMBER):
        return
    src = read(CHAMBER)

    helper = method_body(src, "private List<BlockPos> stationGroupPositions(")
    check("①-2 stationGroupPositions 存在", bool(helper))
    if helper:
        check("①-3 登记坐标排第一位（绝大多数情况件就在这一格，先扫它零成本）",
              helper.find("positions.add(registered)") >= 0)
        check("①-4 并上工位本体（置物台 = 装配产出的落点）",
              "stationKeyCached(level, registered)" in helper, helper[:400])
        check("①-5 并上同工位其它供料目标（机械手等）",
              "stationSiblings(level, station)" in helper, helper[:400])

    residue = method_body(src, "private boolean stationHoldsPipelineResidue(")
    check("①-6 stationHoldsPipelineResidue 方法体可定位", bool(residue))
    if residue:
        check("①-7 【核心】收尾保留判定改为遍历整工位坐标组",
              "stationGroupPositions(level, station)" in residue,
              "旧写法（只扫登记坐标）是否还在：%s" % legacy_scan_only_registered(residue))
        check("①-8 未加载的任一格 ⇒ 保留凭据（看不见 ≠ 没有）",
              "!level.isLoaded(pos)" in residue and "return true;" in residue)

    clear_all = method_body(src, "private int clearDeadStationPieces()")
    check("①-9 clearDeadStationPieces 方法体可定位", bool(clear_all))
    if clear_all:
        check("①-10 先扫登记坐标（三种残留都放行）",
              "clearDeadStationPieceAt(level, station, station, true)" in clear_all)
        check("①-11 再去整工位其它容器（只收过渡件）",
              "stationGroupPositions(level, station)" in clear_all
              and "clearDeadStationPieceAt(level, other, station, false)" in clear_all)
        check("①-12 仍然每轮至多搬一件（有界，不会一次扫爆服务器）",
              "deadStationClearBurst > 0" in clear_all)

    piece = method_body(src, "private int clearDeadStationPieceAt(final Level level, final BlockPos pos,")
    check("①-13 clearDeadStationPieceAt 已改成 (level, pos, registered, allowStartIngredient)",
          bool(piece))
    if piece:
        check("①-14 handler 取的是本次要扫的 pos（而不是登记坐标）",
              "itemHandlerAt(level, pos)" in piece)
        check("①-15 登记表查询 / 撤销用的是 registered（不是 pos）",
              "inFlightUnits.get(registered)" in piece and "inFlightUnits.remove(registered)" in piece)
        check("①-16 输出总线闸门对 pos 与 registered 都判（两格任一仍会推它 ⇒ 不碰）",
              "busTargetAccepts(pos, item)" in piece and "busTargetAccepts(registered, item)" in piece)


def safety_anchors():
    print("\n=== ② 安全锚点：同工位其它容器只收过渡件，搬运不丢不复制 ===")
    src = read(CHAMBER)
    piece = method_body(src, "private int clearDeadStationPieceAt(final Level level, final BlockPos pos,")
    if not piece:
        check("②-0 能定位逐格实现", False)
        return
    guard_at = piece.find("if (!allowStartIngredient)")
    non_transition_at = piece.find("if (!isTransitionOfMyRecipes(item))")
    registered_at = piece.find("registeredUnit == null")
    check("②-1 非过渡件分支存在", non_transition_at >= 0)
    check("②-2 非过渡件分支里有 allowStartIngredient 闸门", guard_at >= 0)
    check("②-3 【安全】闸门在「同物品命中登记表」判定之前（否则会误收玩家手放的金板 / 齿轮）",
          0 <= guard_at and 0 <= non_transition_at and guard_at < registered_at,
          "guard=%d non_transition=%d registered=%d"
          % (guard_at, non_transition_at, registered_at))
    check("②-4 过渡件分支仍要求「该配方没有活跃订单」才收",
          "recipeOrdered(assembly.id().toString())" in piece)
    check("②-5 搬运前先 SIMULATE 夹量（本仓装不下 ⇒ 一个字节都不动）",
          "canAcceptTransitionLocally(inSlot)" in piece and "if (accepted <= 0)" in piece)
    check("②-6 抽取后收不进去 ⇒ 原样还回（绝不吞掉玩家资源）",
          "handler.insertItem(slot, taken, false)" in piece)
    check("②-7 收回本仓后立即走任务结束回流通道（flushResidualInputs）",
          "flushResidualInputs();" in piece)
    check("②-8 仍是逐槽 try/catch 隔离（一格抛了不影响其余格）",
          "catch (final RuntimeException failure)" in piece)


def diag_anchors():
    print("\n=== ③ diag 锚点：能拍到基地的仓 + 在制登记明细 ===")
    src = read(CHAMBER)
    report = method_body(src, "public Map<String, Object> rscc$diagReport()")
    check("③-1 rscc$diagReport 可定位", bool(report))
    if report:
        check("③-2 补出 inFlightUnits 明细字段", 'out.put("inFlightUnits", inFlightDetail)' in report)
        for key in ('"station"', '"item"', '"recipe"', '"step"', '"age"', '"group"'):
            check("③-3 inFlightUnits 行含 %s" % key, ("row.put(%s" % key) in report)
        check("③-4 明细来自在制登记表快照（先拷贝再遍历，避免并发修改）",
              "new ArrayList<>(inFlightUnits.entrySet())" in report)

    check("③-5 RsccDiag.java 存在", os.path.isfile(DIAG))
    if not os.path.isfile(DIAG):
        return
    diag = read(DIAG)
    body = method_body(diag, "private static List<BlockEntity> blockEntities(final ServerLevel level)")
    check("③-6 blockEntities 可定位", bool(body))
    if body:
        check("③-7 仍保留看门狗区块表这一来源",
              "AssemblyWatchdog.rscc$loadedChunks(level.dimension())" in body)
        check("③-8 【核心】并入「玩家周边已加载区块」（否则玩家保存的 diag 看不到基地的仓）",
              "player.chunkPosition()" in body and "getChunkNow" in body)
        check("③-9 半径受 view-distance 约束且夹紧（不会遍历上千区块把导出卡死）",
              "getViewDistance()" in body and "Math.min(16" in body)
        check("③-10 只用 getChunkNow 读已加载区块，绝不主动加载",
              "getChunkNow" in body and "getChunk(" not in body and "getChunkAt(" not in body)
        check("③-11 区块键打包格式与看门狗一致（高 32 位 x / 低 32 位 z）",
              "packChunk(center.x + dx, center.z + dz)" in body
              and "private static long packChunk(final int x, final int z)" in diag)


def counter_example():
    print("\n=== ④ 反例自证：把旧的「只扫登记坐标」写法拼回去，判据必须判负 ===")
    old_residue = """
        final IItemHandler handler = RsccChamberImportStrategy.itemHandlerAt(level, station);
        if (handler == null) { return false; }
        for (int slot = 0; slot < handler.getSlots(); slot++) { }
        return false;
    """
    check("④-1 旧写法被判负（legacy_scan_only_registered == True）",
          legacy_scan_only_registered(old_residue) is True)
    src = read(CHAMBER) if os.path.isfile(CHAMBER) else ""
    residue = method_body(src, "private boolean stationHoldsPipelineResidue(")
    check("④-2 当前源码不被判负（已改成整工位口径）",
          legacy_scan_only_registered(residue) is False,
          "当前方法体前 300 字：%s" % residue[:300])

    old_clear = """
        deadStationClearBurst = 0;
        for (final Map.Entry<BlockPos, InFlightUnit> entry : new ArrayList<>(inFlightUnits.entrySet())) {
            final int moved = clearDeadStationPieceAt(level, station);
        }
    """
    check("④-3 旧的两参调用写法在本轮签名下已不存在（否则会编译失败 / 漏扫整工位）",
          "clearDeadStationPieceAt(level, station)" not in src)
    check("④-4 旧写法本身不含整工位扫描（自证判据有意义）",
          "stationGroupPositions(" not in old_clear)


def main():
    group_source_anchors()
    safety_anchors()
    diag_anchors()
    counter_example()
    print("\n=== 汇总 ===")
    print("通过 %d 项，失败 %d 项" % (_passed, _failed))
    if _failed:
        print("失败项：")
        for name, detail in _failures:
            print("  - %s" % name)
            if detail:
                print("      %s" % str(detail).strip().splitlines()[0])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
