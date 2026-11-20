#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 137 轮自检：能量立方集群 BFS 的「区块安全读」+ 每 tick 重算预算。

背景（beta.37 实机服务端崩溃）
------------------------------
`crash-2026-11-14_22.43.50-server.txt` 的服务器线程栈尖是：

    EnergyCubeCluster.collect(:299)  ← HashSet.add
    EnergyCubeCluster.rebuild(:262)
    EnergyCubeCluster.resolve(:255)
    FEEnergyCubeBlockEntity.snapshot(:346) → getClusterEnergyStored(:326)
    ClusterEnergyCapability.getEnergyStored(:835)
    create_simple_generator: StressGeneratorEntity.extractPowerTo(:109) / tick(:47)

`collect` 的循环本身是**有界**的（`found.size() < MAX_MEMBERS`，最多 64 个成员 ⇒
≤ 64×6+1 次入队），不可能自己转 60 秒。真正的问题在读世界的方式：
1.21.1 的 `Level#isLoaded` 对「已在加载流水线里、但还没 FULL」的区块**同样返回 true**，
而 `Level#getBlockState` 内部走 `getChunk(cx, cz, FULL)`（require = true）——
会在**主线程同步等这个区块生成**。玩家进服 / 传送到基地时，集群 BFS 恰好扫到这些区块，
单 tick 就被拖到几百毫秒；持续下去，`nextTickTimeNanos` 落后 60 秒，看门狗就把服务器判死。

修法
----
① 世界读取一律走 `getChunkSource().getChunkNow(...)`（只返回 FULL 区块：不加载、不加票据、不阻塞）；
   取方块实体用 `LevelChunk#getBlockEntity(pos, CHECK)`（只取已存在的，不创建）；
② 扫到「在流水线里但还没 FULL」的邻块时记 `partial`，该快照永不视为 fresh ⇒ 下次重算；
③ `MAX_REBUILDS_PER_TICK` 硬预算：纯读路径（能力查询 / 界面）每 tick 最多重算 8 次，
   超出后退化为「上次快照 / 只报自己」；
④ 单次重算 ≥ 20 ms 记一条 WARN（带 origin / members），给实机留证据（5 分钟冷却防刷屏）。

本脚本四条腿
------------
① 源码锚点：`getChunkNow` 在、`level.getBlockState(` / `level.getBlockEntity(` / `level.isLoaded(` 都不在；
② 有界性锚点：BFS 的 `found.size() < MAX_MEMBERS` 仍在、重算预算与退化分支仍在；
③ 调用链锚点：崩溃栈里那条链（能力 → getClusterEnergyStored → snapshot → resolve）确实还走本类；
④ 反例自证：把旧写法（isLoaded + getBlockState）、去掉预算的写法拼回去，判据必须判负。

用法：python tools/selfcheck_round137_energy_cluster_safe_read.py
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
CLUSTER_FILE = os.path.join(PKG, "support", "EnergyCubeCluster.java")
CUBE_FILE = os.path.join(PKG, "block", "entity", "FEEnergyCubeBlockEntity.java")

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


# ---------- 判据（供正例与反例共用同一个实现，避免「判据只对正例有效」） ----------

# 危险读世界：1.21.1 上这三条都可能同步加载 / 等待区块生成
UNSAFE_READS = ("level.isLoaded(", "level.getBlockState(", "level.getBlockEntity(")


def unsafe_world_reads(text):
    return [pattern for pattern in UNSAFE_READS if pattern in text]


def has_chunk_safe_read(text):
    body = method_body(text, "private static LevelChunk readyChunkAt(")
    return "getChunkSource().getChunkNow(" in body and "isOutsideBuildHeight" in body


def bfs_is_bounded(text):
    body = method_body(text, "private static ScanResult collect(")
    return "found.size() < MAX_MEMBERS" in body


def has_rebuild_budget(text):
    body = method_body(text, "public static Snapshot resolve(")
    return (
        "MAX_REBUILDS_PER_TICK" in body
        and "cache.budgetUsed >= MAX_REBUILDS_PER_TICK" in body
        and "return cached != null ? cached : new Snapshot(" in body
    )


def has_partial_propagation(text):
    is_fresh = method_body(text, "private boolean isFresh(final ServerLevel server)")
    collect = method_body(text, "private static ScanResult collect(")
    return (
        "private final boolean partial;" in text
        and "if (partial)" in is_fresh
        and "partial = true;" in collect
        and "new ScanResult(found, partial)" in text
    )


def cube_entity_uses_check(text):
    body = method_body(text, "private static FEEnergyCubeBlockEntity cubeAt(")
    return "LevelChunk.EntityCreationType.CHECK" in body and "chunk.getBlockEntity(" in body


def group_source_anchors():
    print("\n=== ① 源码锚点：只读已就绪区块，绝不触发加载 ===")
    check("①-1 EnergyCubeCluster.java 存在", os.path.isfile(CLUSTER_FILE))
    if not os.path.isfile(CLUSTER_FILE):
        return
    src = read(CLUSTER_FILE)

    bad = unsafe_world_reads(src)
    check("①-2 文件里不再出现 level.isLoaded( / level.getBlockState( / level.getBlockEntity(",
          not bad, "仍出现：%s" % bad)
    check("①-3 readyChunkAt 用 getChunkSource().getChunkNow(...) 且先判 isOutsideBuildHeight",
          has_chunk_safe_read(src))
    check("①-4 BFS 里读方块状态走区块对象（chunk.getBlockState），不走 Level 的加载型读",
          "chunk.getBlockState(" in src or "Chunk.getBlockState(" in src)
    check("①-5 取方块实体用 LevelChunk.EntityCreationType.CHECK（不创建、不加载）",
          cube_entity_uses_check(src))
    check("①-6 注释写清「isLoaded 对未 FULL 区块也返回 true、getBlockState 会同步等生成」的机制",
          "isLoaded" in src and "getChunkNow" in src and "同步等" in src)

    print("\n=== ② 有界性锚点：BFS 有界 + 每 tick 重算预算 + partial 传播 ===")
    check("②-1 BFS 仍有 found.size() < MAX_MEMBERS 上限（单次重算不会无限扫）", bfs_is_bounded(src))
    check("②-2 resolve 有 MAX_REBUILDS_PER_TICK 预算，超出后退化为缓存 / 单成员视图",
          has_rebuild_budget(src))
    check("②-3 扫到未就绪区块记 partial，且 partial 快照永不视为 fresh",
          has_partial_propagation(src))
    check("②-4 慢重算 ≥ SLOW_REBUILD_NANOS 记 WARN（5 分钟冷却）",
          "SLOW_REBUILD_NANOS" in src and "warnSlowRebuild(" in src
          and "SLOW_WARN_COOLDOWN_TICKS" in src)
    check("②-5 失效缓存仍按坐标表逐项摘除（invalidateAt 用 cache.byPos）",
          "cache.byPos.get(pos.asLong())" in src and "removeAll(cache, previous)" in src)


def group_call_chain():
    print("\n=== ③ 调用链锚点：崩溃栈那条链仍走本类（修的是真路径） ===")
    if not os.path.isfile(CUBE_FILE):
        check("③-1 FEEnergyCubeBlockEntity.java 存在", False)
        return
    cube = read(CUBE_FILE)
    check("③-1 snapshot() 仍调用 EnergyCubeCluster.resolve(level, worldPosition)",
          "EnergyCubeCluster.resolve(level, worldPosition)" in cube)
    check("③-2 getClusterEnergyStored() 仍走 snapshot()",
          method_body(cube, "public long getClusterEnergyStored(").find("snapshot()") >= 0)
    capability = method_body(cube, "public int getEnergyStored()")
    check("③-3 ClusterEnergyCapability.getEnergyStored 仍走 getClusterEnergyStored()",
          "getClusterEnergyStored()" in capability)


def group_counterexamples():
    print("\n=== ④ 反例自证：旧写法 / 无预算写法必须判负 ===")
    src = read(CLUSTER_FILE)

    old_ready = method_body(src, "private static boolean isCubeBlock(final Level level, final BlockPos pos)")
    old_snippet = (
        "private static boolean isCubeBlock(final Level level, final BlockPos pos) {\n"
        "        return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof FEEnergyCubeBlock;\n"
        "    }"
    )
    check("④-1 旧写法（isLoaded + getBlockState）会被「危险读」判据判负",
          bool(unsafe_world_reads(old_snippet)) and not bool(unsafe_world_reads(src)))
    check("④-2 旧 isCubeBlock 方法体已不含危险读", not unsafe_world_reads(old_ready), old_ready[:120])

    # 把预算分支删掉的 resolve：必须判负
    resolve_body = method_body(src, "public static Snapshot resolve(")
    stripped = resolve_body.replace(
        "if (cache.budgetUsed >= MAX_REBUILDS_PER_TICK) {\n"
        "            // 预算用尽：只读路径退化为「上次的快照」或「只报自己」，绝不再扫世界\n"
        "            return cached != null ? cached : new Snapshot(level, List.of(pos), false);\n"
        "        }\n", "", 1)
    synthetic = src.replace(resolve_body, stripped, 1) if resolve_body else ""
    check("④-3 删掉预算分支后判据判负（预算断言不是摆设）",
          bool(resolve_body) and not has_rebuild_budget(synthetic))

    # 没有 getChunkNow 的 readyChunkAt：必须判负
    ready_body = method_body(src, "private static LevelChunk readyChunkAt(")
    synthetic2 = src.replace(ready_body, ready_body.replace(
        "return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);",
        "return level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);"), 1) if ready_body else ""
    check("④-4 把 getChunkNow 换成会加载的 getChunk 后判据判负",
          bool(ready_body) and not has_chunk_safe_read(synthetic2))


def main():
    print("第 137 轮自检：能量立方集群的区块安全读 + 每 tick 重算预算")
    print("仓库：%s" % ROOT)
    group_source_anchors()
    group_call_chain()
    group_counterexamples()
    print("\n断言 %d 条，失败 %d 条" % (_passed + _failed, _failed))
    if _failed:
        print("\n失败明细：")
        for name, detail in _failures:
            print("  - %s" % name)
            if detail:
                print("      " + str(detail).splitlines()[0])
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
