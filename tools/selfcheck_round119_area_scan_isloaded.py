#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 119 轮自检：区域扫描的流体探测先判「区块已加载」（审计 C-6）。

背景
----
高级归流缓存仓的「区域」来源每 tick 会扫描区域内的坐标：
`AdvancedCollectionCacheBlockEntity.AreaCursor.scanFluidWindow` → `isSource.test(pos)`
→ `CollectionCacheBlockEntity#fluidSourceIdAt(level, pos)` → **第一行就是 `level.getBlockState(pos)`**。

而 `Level#getBlockState` 对**未加载**区块会触发**同步区块加载**（本工程
`support/SeparationFrameGuard` 里早有同样的注释警告）。玩家离开基地、区块被卸载后，
只要机器还在跑（区块加载器 / 玩家偶尔靠近 / 传送门），每 tick 上千次查询就会反复拉区块，
表现为服务器 tick 时间上升、偶发卡顿 —— 没有异常、没有日志，最容易被误判成「模组有性能问题」。

修法：`fluidSourceIdAt` 开头先 `if (!level.isLoaded(pos)) return null;`。
「未加载」⇒ 当作「这里没有流体」是安全的：区块重新加载后下一轮扫描会正常看到它，
不丢任何资源（该函数是**只读探测**，不写世界）。

本脚本三条腿
------------
① 源码锚点：`isLoaded` 判断在 `getBlockState` **之前**（顺序断言，不是「文件里出现过就算」）；
② 范围锚点：区域扫描的调用链（`AreaCursor` → `isSource`）确实走这个函数，
   且该方法本身是只读的（不 setBlock / 不抽取 —— 证明「未加载返回 null」不会丢资源）；
③ 反例自证：把旧的「直接 getBlockState」写法拼回去，断言判据真的判负。

用法：python tools/selfcheck_round119_area_scan_isloaded.py
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
CACHE_FILE = os.path.join(PKG, "block", "entity", "CollectionCacheBlockEntity.java")
ADV_FILE = os.path.join(PKG, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")

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


def group_anchors():
    print("\n=== ① 源码锚点：isLoaded 必须在 getBlockState 之前 ===")
    check("①-1 CollectionCacheBlockEntity.java 存在", os.path.isfile(CACHE_FILE))
    if not os.path.isfile(CACHE_FILE):
        return
    src = read(CACHE_FILE)
    body = method_body(src, "protected static ResourceLocation fluidSourceIdAt(")
    check("①-2 能定位到 fluidSourceIdAt 方法体", bool(body))
    if not body:
        return
    guard_at = body.find("!level.isLoaded(pos)")
    state_at = body.find("level.getBlockState(pos)")
    check("①-3 方法体里有 isLoaded 闸门", guard_at >= 0)
    check("①-4 方法体里有 getBlockState", state_at >= 0)
    check("①-5 闸门在 getBlockState **之前**（顺序断言；只要顺序反了就等于没修）",
          0 <= guard_at < state_at, "guard_at=%d state_at=%d" % (guard_at, state_at))
    check("①-6 未加载时返回 null（当作「没有流体」，不抛异常、不写世界）",
          "return null;" in body[guard_at:state_at] if guard_at >= 0 and state_at >= 0 else False)
    check("①-7 注释写清「getBlockState 会同步加载区块」这个机制（防止后人又删掉闸门）",
          "同步加载" in body or "同步区块加载" in body)

    print("\n=== ② 范围锚点：区域扫描确实走这个函数，且该函数只读 ===")
    adv = read(ADV_FILE) if os.path.isfile(ADV_FILE) else ""
    check("②-1 AreaCursor 的流体扫描存在（isSource.test）", "isSource.test(" in adv)
    check("②-2 区域扫描的谓词由 fluidSourceIdAt 提供（在 CollectionCacheBlockEntity 里接上）",
          "scanFluidWindow(level, MAX_FLUID_BLOCKS_PER_SCAN" in src
          and "pos -> fluidSourceIdAt(level, pos) != null" in src,
          "扫描调用接上了吗：%s" % ("fluidSourceIdAt(level, pos) != null" in src))
    check("②-2b 谓词类型跨类传递（AreaScanHook 用 Predicate<BlockPos>，因此闸门只需在源头加一处）",
          "Predicate<BlockPos> isSource" in adv, "adv 签名=%s" % ("Predicate<BlockPos> isSource" in adv))
    for forbidden in ("setBlock(", "setBlockAndUpdate(", "destroyBlock(", "removeBlock("):
        check("②-3 fluidSourceIdAt 是只读的：不含 %s" % forbidden, forbidden not in body)


def group_counterexample():
    print("\n=== ③ 反例自证：旧写法必须判负 ===")
    src = read(CACHE_FILE)
    body = method_body(src, "protected static ResourceLocation fluidSourceIdAt(")
    if not body:
        check("③ 旧写法反例（无法定位方法体）", False)
        return

    def has_guard(text):
        guard_at = text.find("!level.isLoaded(pos)")
        state_at = text.find("level.getBlockState(pos)")
        return 0 <= guard_at < state_at

    old_body = (
        body.replace("if (!level.isLoaded(pos)) {\n            return null;\n        }\n        ", "", 1)
    )
    check("③-1 把闸门删掉的旧写法会被判负（判据不是摆设）", not has_guard(old_body))
    check("③-2 当前写法被判正", has_guard(body))
    # 顺序反例：把闸门挪到 getBlockState **语句之后**（从「已经删掉闸门」的副本出发，
    # 否则原始闸门还在前面，反例会被原始闸门「救」成正例 —— 这一步自己就踩过一次）。
    statement = "final BlockState state = level.getBlockState(pos);"
    statement_at = old_body.find(statement)
    moved = (
        old_body[:statement_at + len(statement)]
        + "\n        if (!level.isLoaded(pos)) { return null; }"
        + old_body[statement_at + len(statement):]
    )
    check("③-3 闸门放在 getBlockState 语句之后会被判负（顺序判据有效）", not has_guard(moved),
          "moved 的 guard/state 位置：guard=%d state=%d" % (
              moved.find("!level.isLoaded(pos)"), moved.find("level.getBlockState(pos)")))


def main():
    print("第 119 轮自检：区域扫描流体探测的 isLoaded 闸门（审计 C-6）")
    print("仓库：%s" % ROOT)
    group_anchors()
    group_counterexample()
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
