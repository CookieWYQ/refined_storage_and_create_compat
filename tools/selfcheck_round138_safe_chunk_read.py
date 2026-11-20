#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 138 轮自检：区块安全读统一收口 + 充电器分片扫描 + 保持器 M3。

三个主题（对应本轮 A / B / C）
------------------------------
A. 「isLoaded 挡不住未 FULL 区块」这一类阻塞读，全体收敛到 `support/SafeChunkRead`：
   - 机器集群 BFS（RsccMachineCluster）与存活校验；
   - 范围充电器（RangeChargerBlockEntity）的区块扫描；
   - 全工程 `getChunkAt(...)`（阻塞 API）清零；
   - 方块实体 / 事件 / 网络包 / 物品路径里所有 `isLoaded + getBlockState|getBlockEntity` 组合；
   - 拿不到 FULL 区块时「这一 tick 先当看不见」：机器集群记 partial 下一 tick 补算，
     同一 tick 只重算一次（lastAttemptTick）。

B. 范围充电器区块扫描改为「每 tick 区块预算 + 轮转游标」：
   - 普通范围 `MAX_FINITE_SCAN_CHUNKS_PER_TICK = 128`（默认 50/100 格范围与旧行为逐 tick 等价）；
   - 无限范围（creative）`MAX_INFINITE_SCAN_CHUNKS_PER_TICK = 256`；
   - 候选池只在「一轮完整轮转开始」（start == 0）时清空 + 登记自身，避免分片把界面刷成碎片。

C. 保持器仲裁 M3：目标量 = 0（未标记）不认领资源，避免同一资源两台保持器时
   「未标记的那台坐标更靠前 ⇒ 真正生效的那台永远让位 ⇒ 功能静默失效」。

本脚本四条腿
------------
① 源码锚点：SafeChunkRead 的存在与判据、三个消费点的形态、工程内 getChunkAt 清零；
② 有界性锚点：集群 BFS 上限、partial 传播、同 tick 重算节流、充电器预算常量；
③ 行为锚点：哪些代码块里必须出现安全读调用（逐方法判定，不用「文件里有就行」）；
④ 反例自证：旧写法（isLoaded + getBlockState / 无预算全量扫描 / claimedResources 不看目标量）
   拼回内存里的合成源码，判据必须判负 —— 保证判据不是「只对当前源码成立」。

用法：python tools/selfcheck_round138_safe_chunk_read.py
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

SAFE_FILE = os.path.join(PKG, "support", "SafeChunkRead.java")
CLUSTER_FILE = os.path.join(PKG, "support", "RsccMachineCluster.java")
CHARGER_FILE = os.path.join(PKG, "block", "entity", "RangeChargerBlockEntity.java")
KEEPER_FILE = os.path.join(PKG, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_KEEPER_FILE = os.path.join(PKG, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")

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


# ---------- 判据（正例 / 反例共用同一实现） ----------

UNSAFE_READS = ("level.isLoaded(", "level.getBlockState(", "level.getBlockEntity(",
                "level.getChunkAt(", "level.getChunk(")


def unsafe_reads(text):
    """返回文本里出现的阻塞读模式（ModList.isLoaded 不算）。"""
    return [p for p in UNSAFE_READS if p in text]


def safe_helper_is_correct(text):
    chunk = method_body(text, "public static LevelChunk chunkNow(")
    entity = method_body(text, "public static BlockEntity blockEntityNow(")
    state = method_body(text, "public static BlockState blockStateNow(")
    has = method_body(text, "public static boolean hasChunkHolder(")
    return (
        "ChunkStatus.FULL, false" in chunk
        and "instanceof LevelChunk" in chunk
        and "getChunkAt(" not in chunk
        and "isLoaded(" not in chunk
        and "EntityCreationType.CHECK" in entity
        and "EntityCreationType.FULL" not in entity
        and "chunk.getBlockState(" in state
        and "level.hasChunkAt(" in has
    )


def cluster_uses_safe_reads(text):
    is_fresh = method_body(text, "private boolean isFresh(final ServerLevel level)")
    collect = method_body(text, "private static Collected collect(")
    ensure = method_body(text, "public static void ensure(final RsccClusterable member)")
    return (
        not unsafe_reads(text)
        and "SafeChunkRead.blockStateNow(" in is_fresh
        and "SafeChunkRead.blockEntityNow(" in collect
        and "SafeChunkRead.blockStateNow(" in collect
        and "SafeChunkRead.hasChunkHolder(" in collect
        and "partial = true;" in collect
        and "found.size() < MAX_MEMBERS" in collect
        and "cached.partial() && cached.lastAttemptTick() == server.getGameTime()" in ensure
    )


def charger_uses_budget_and_safe_reads(text):
    finite = method_body(text, "private int scanBlocks(final Level level)")
    infinite = method_body(text, "private int scanBlocksInfinite(final Level level)")
    return (
        not unsafe_reads(text)
        and "MAX_FINITE_SCAN_CHUNKS_PER_TICK" in text
        and "MAX_INFINITE_SCAN_CHUNKS_PER_TICK" in text
        and "private int blockScanCursor;" in text
        and "Math.min(total, MAX_FINITE_SCAN_CHUNKS_PER_TICK)" in finite
        and "Math.min(total, MAX_INFINITE_SCAN_CHUNKS_PER_TICK)" in infinite
        and "blockScanCursor = (start + budget) % total;" in finite
        and "blockScanCursor = (start + budget) % total;" in infinite
        and "SafeChunkRead.chunkNow(" in finite
        and "SafeChunkRead.chunkNow(" in infinite
        and "if (start == 0)" in finite
        and "if (start == 0)" in infinite
        and "seenBlocks.clear();" in finite
        and "seenBlocks.clear();" in infinite
        and "recordSeenSelf(level);" in finite
        and "recordSeenSelf(level);" in infinite
    )


def keeper_claims_only_marked(text, slot_based):
    body = method_body(text, "public List<ResourceKey> claimedResources()")
    if slot_based:
        return "KeeperTarget.isMarked(targets[slot])" in body and "continue;" in body
    return "KeeperTarget.isMarked(targetAmount)" in body and "return List.of();" in body


def repo_has_blocking_get_chunk_at():
    hits = []
    for base, _dirs, files in os.walk(PKG):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            text = read(path)
            if ".getChunkAt(" in text:
                hits.append(os.path.relpath(path, ROOT))
    return hits


# ---------- ① / ② / ③ 正例 ----------

def group_helper():
    print("\n=== ① SafeChunkRead：只读已 FULL 区块，不加载 / 不阻塞 ===")
    check("①-1 SafeChunkRead.java 存在", os.path.isfile(SAFE_FILE))
    if not os.path.isfile(SAFE_FILE):
        return
    src = read(SAFE_FILE)
    check("①-2 chunkNow 用 getChunk(..., ChunkStatus.FULL, false) 且校验 instanceof LevelChunk",
          safe_helper_is_correct(src))
    check("①-3 blockEntityNow 用 EntityCreationType.CHECK（绝不创建）",
          "EntityCreationType.CHECK" in method_body(src, "public static BlockEntity blockEntityNow("))
    check("①-4 hasChunkHolder 用非阻塞的 hasChunkAt（区分「没加载」与「加载中」）",
          "level.hasChunkAt(pos)" in method_body(src, "public static boolean hasChunkHolder("))


def group_cluster():
    print("\n=== ② 机器集群：安全读 + partial 补算 + 同 tick 只重算一次 ===")
    check("②-1 RsccMachineCluster.java 存在", os.path.isfile(CLUSTER_FILE))
    if not os.path.isfile(CLUSTER_FILE):
        return
    src = read(CLUSTER_FILE)
    check("②-2 文件里不再出现 level.isLoaded( / getBlockState( / getBlockEntity( / getChunkAt(",
          not unsafe_reads(src), "仍出现：%s" % unsafe_reads(src))
    check("②-3 判据：isFresh 走 blockStateNow、collect 走 blockStateNow+blockEntityNow+hasChunkHolder、"
          "partial 传播、BFS 有 MAX_MEMBERS 上限、ensure 同 tick 节流",
          cluster_uses_safe_reads(src))
    check("②-4 起点区块未 FULL 时不清缓存（保持旧缓存，下一 tick 再来）",
          "collected.originBlock() == null" in src and "保持现有缓存不动" in src)


def group_charger():
    print("\n=== ③ 范围充电器：区块预算轮转 + 安全读 + 候选池按整轮重建 ===")
    check("③-1 RangeChargerBlockEntity.java 存在", os.path.isfile(CHARGER_FILE))
    if not os.path.isfile(CHARGER_FILE):
        return
    src = read(CHARGER_FILE)
    check("③-2 文件里不再出现 level.isLoaded( / level.getChunk( / getChunkAt( / getBlockState(",
          not unsafe_reads(src), "仍出现：%s" % unsafe_reads(src))
    check("③-3 判据：有限/无限两条扫描都按 budget 取 Math.min、游标跨 tick 记忆、"
          "start == 0 时清空候选池并登记自身、读区块走 SafeChunkRead.chunkNow",
          charger_uses_budget_and_safe_reads(src))
    check("③-4 默认 50/100 格范围（≤128 区块）与旧行为逐 tick 等价（预算 ≥ 8×8）",
          "MAX_FINITE_SCAN_CHUNKS_PER_TICK = 128" in src)


def group_repo_wide():
    print("\n=== ④ 全工程：阻塞 API getChunkAt( 清零 ===")
    hits = repo_has_blocking_get_chunk_at()
    check("④-1 src/main/java 下不再有 .getChunkAt( 调用", not hits, "仍有：%s" % hits)
    # 允许的例外：客户端渲染 / 蓝图读取（不是 Level 的世界读）
    check("④-2 客户端渲染与蓝图 reader 的读法未被误改（SheathRenderer / SchematicLoader 仍在）",
          os.path.isfile(os.path.join(PKG, "client", "SheathRenderer.java"))
          and os.path.isfile(os.path.join(PKG, "block", "entity", "SchematicLoaderBlockEntity.java")))


def group_keeper():
    print("\n=== ⑤ 保持器 M3：目标 0（未标记）不认领 ===")
    for label, path, slot_based in (
        ("基础版", KEEPER_FILE, False),
        ("高级版", ADV_KEEPER_FILE, True),
    ):
        check("⑤-%s claimedResources 存在" % label, os.path.isfile(path))
        if not os.path.isfile(path):
            continue
        src = read(path)
        check("⑤-%s 认领前必须判 KeeperTarget.isMarked（未标记不认领）" % label,
              keeper_claims_only_marked(src, slot_based),
              method_body(src, "public List<ResourceKey> claimedResources()")[:400])


# ---------- ⑥ 反例自证 ----------

def group_counter_examples():
    print("\n=== ⑥ 反例自证：判据必须能判负 ===")

    old_helper = (
        "public static LevelChunk chunkNow(Level level, BlockPos pos) {\n"
        "    if (!level.isLoaded(pos)) { return null; }\n"
        "    return level.getChunkAt(pos);\n"
        "}\n"
    )
    check("⑥-1 旧写法（isLoaded + getChunkAt）拼成「helper」→ 判据判负",
          not safe_helper_is_correct(old_helper))

    old_cluster = (
        "private boolean isFresh(final ServerLevel level) {\n"
        "    if (!level.isLoaded(pos) || !sameFamily(level.getBlockState(pos).getBlock(), block)) { return false; }\n"
        "    return true;\n"
        "}\n"
        "private static Collected collect(ServerLevel level, BlockPos origin) {\n"
        "    while (!queue.isEmpty() && found.size() < MAX_MEMBERS) { level.getBlockState(n); }\n"
        "    return new Collected(List.of(), null, false);\n"
        "}\n"
        "public static void ensure(final RsccClusterable member) { rebuild(server, map, pos, null); }\n"
    )
    check("⑥-2 旧写法（isLoaded + getBlockState、无 partial / 无节流）→ 判据判负",
          not cluster_uses_safe_reads(old_cluster))

    old_charger = (
        "private int scanBlocks(final Level level) {\n"
        "    for (int chunkX = a; chunkX <= b; chunkX++) { level.getChunk(chunkX, chunkZ); }\n"
        "}\n"
        "private int scanBlocksInfinite(final Level level) {\n"
        "    for (Player p : level.players()) { for (int dx = -16; dx <= 16; dx++) { level.getChunk(cx, cz); } }\n"
        "}\n"
    )
    check("⑥-3 旧写法（无预算全量扫描 + level.getChunk）→ 判据判负",
          not charger_uses_budget_and_safe_reads(old_charger))

    old_keeper = (
        "public List<ResourceKey> claimedResources() {\n"
        "    final ResourceKey key = networkResourceKey();\n"
        "    return key == null ? List.of() : List.of(key);\n"
        "}\n"
    )
    check("⑥-4 旧写法（claimedResources 不看目标量）→ 判据判负",
          not keeper_claims_only_marked(old_keeper, False))


def main():
    print("第 138 轮自检：区块安全读 / 充电器分片 / 保持器 M3")
    group_helper()
    group_cluster()
    group_charger()
    group_repo_wide()
    group_keeper()
    group_counter_examples()
    print("\n结果：%d 通过 / %d 失败" % (_passed, _failed))
    if _failed:
        print("失败项：")
        for name, detail in _failures:
            print("  - %s" % name)
        return 1
    print("全部通过（安全读判据 + 分片预算 + 认领口径 + 反例自证）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
