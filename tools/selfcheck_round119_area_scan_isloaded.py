#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 119 轮自检（第 138 轮升级）：区域扫描的流体探测「绝不加载区块」。

背景
----
高级归流缓存仓的「区域」来源每 tick 会扫描区域内的坐标：
`AdvancedCollectionCacheBlockEntity.AreaCursor.scanFluidWindow` → `isSource.test(pos)`
→ `CollectionCacheBlockEntity#fluidSourceIdAt(level, pos)`。

- **第 119 轮**：第一行是 `level.getBlockState(pos)` ⇒ 对未加载区块会**同步加载**区块。
  当时的修法是 `if (!level.isLoaded(pos)) return null;` 先挡一道。
- **第 138 轮升级**：`isLoaded` 判断的是「chunk holder 存在」，对**正在加载流水线里、但还没到 FULL**
  的区块同样返回 `true`，紧跟其后的 `getBlockState` 仍会在主线程同步等区块生成。
  因此改成 `SafeChunkRead.blockStateNow(level, pos)`：**只读已到 FULL 的区块**，
  不加载、不加票据、不阻塞；未就绪返回 `null`（当作「这里没有流体」，只读探测，不丢资源）。

本脚本三条腿
------------
① 源码锚点：`fluidSourceIdAt` 用 `SafeChunkRead.blockStateNow`，且**不再**出现
   `level.isLoaded` / `level.getBlockState` / `getChunkAt`；`null` 分支在安全读之后（顺序断言）；
② 范围锚点：区域扫描的调用链（`AreaCursor` → `isSource`）确实走这个函数，
   且该方法本身是只读的（不 setBlock / 不抽取 —— 证明「未就绪返回 null」不会丢资源）；
③ 反例自证：①旧的「直接 getBlockState」写法、②**第 119 轮的 isLoaded 闸门写法**（本轮起也不合格）、
   ③安全读但没判 null —— 三种都必须判负（第②条正是本轮升级的理由）。

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

UNSAFE = ("level.isLoaded(pos)", "level.getBlockState(pos)", "level.getChunkAt(")


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


def safe_guard(body):
    """判据（正例 / 反例共用）：安全读 → null 判定 → return null；且不含任何阻塞读。

    这是第 138 轮升级后的判据；第 119 轮那种 `isLoaded + getBlockState` 组合**不再算合格**。
    """
    safe_at = body.find("SafeChunkRead.blockStateNow(level, pos)")
    if safe_at < 0:
        return False
    null_at = body.find("state == null", safe_at)
    if null_at < 0:
        return False
    return_at = body.find("return null;", null_at)
    if return_at < 0:
        return False
    return not any(unsafe in body for unsafe in UNSAFE)


def group_anchors():
    print("\n=== ① 源码锚点：只读已 FULL 区块（不加载 / 不阻塞） ===")
    check("①-1 CollectionCacheBlockEntity.java 存在", os.path.isfile(CACHE_FILE))
    if not os.path.isfile(CACHE_FILE):
        return
    src = read(CACHE_FILE)
    body = method_body(src, "protected static ResourceLocation fluidSourceIdAt(")
    check("①-2 能定位到 fluidSourceIdAt 方法体", bool(body))
    if not body:
        return
    check("①-3 方法体里有 SafeChunkRead.blockStateNow（安全读）",
          "SafeChunkRead.blockStateNow(level, pos)" in body)
    check("①-4 方法体里没有 level.isLoaded / level.getBlockState / getChunkAt（阻塞读）",
          not any(unsafe in body for unsafe in UNSAFE),
          "仍出现：%s" % [u for u in UNSAFE if u in body])
    check("①-5 判据成立：安全读 → state == null → return null（顺序断言）", safe_guard(body))
    check("①-6 注释写清「isLoaded 挡不住未 FULL 区块 / getBlockState 会同步等生成」的机制"
          "（防止后人又换回旧写法）",
          ("未 FULL" in body or "同步" in body or "不加载" in body))

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
    print("\n=== ③ 反例自证：三种旧写法都必须判负 ===")
    src = read(CACHE_FILE)
    body = method_body(src, "protected static ResourceLocation fluidSourceIdAt(")
    if not body:
        check("③ 无法定位方法体", False)
        return

    # ① 第 118 轮之前：直接 getBlockState
    direct = (
        "final BlockState state = level.getBlockState(pos);\n"
        "        if (state.getProperties().size() != 1) {\n            return null;\n        }\n"
    )
    check("③-1 直接 getBlockState（旧写法）⇒ 判负", not safe_guard(direct))

    # ② 第 119 轮的 isLoaded 闸门写法：对「加载中但未 FULL」区块仍然会阻塞 ⇒ 本轮起判负
    old_guard = (
        "if (!level.isLoaded(pos)) {\n            return null;\n        }\n"
        "        final BlockState state = level.getBlockState(pos);\n"
    )
    check("③-2 第 119 轮的 isLoaded + getBlockState 写法 ⇒ 判负（这正是第 138 轮升级的理由）",
          not safe_guard(old_guard))

    # ③ 安全读但没判 null（未就绪时 NPE / 空指针风险）
    no_null_check = (
        "final BlockState state = SafeChunkRead.blockStateNow(level, pos);\n"
        "        if (state.getProperties().size() != 1) {\n            return null;\n        }\n"
    )
    check("③-3 安全读但不判 null ⇒ 判负", not safe_guard(no_null_check))

    check("③-4 当前写法被判正", safe_guard(body))


def main():
    print("第 119 轮自检（第 138 轮升级）：区域扫描流体探测「绝不加载区块」")
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
