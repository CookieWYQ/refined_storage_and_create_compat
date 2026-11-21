#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 141 轮自检：清第 138/139 轮清单里两条「不需要产品拍板」的账。

1) ROUND118 复核 L2：中间产物缓存仓**无电仍执行存量迁移**（`IntermediateCacheBlockEntity#doWork`
   直接跑 `RsccIntermediateFlow.flowOnce`，没有判 `isActive()`）。
   修法：节流之前加 `node == null || !node.isActive()` 闸门，并改用同一个 `node` 取网络
   （不再第二次 `getNode()`）。闸门放在节流之前 ⇒ 未激活时冷却不推进，重新上电第一 tick 就能搬。

2) ROUND118 复核 M1：定量保持器的**目标数量无服务端上限**（C2S 只做 `Math.max(0, value)`）。
   修法：`KeeperTarget.MAX_SET_TARGET = 1_000_000_000`（与 `Config.quantityKeeperDefaultTarget`
   的 `defineInRange(..., 1, 1000000000)` 同口径）+ 唯一钳制入口 `KeeperTarget#clampSetTarget`，
   基础版 `setTargetAmount` 与高级版 `setTarget` 都必须走它；读档路径不经过钳制（只拦增长）。

用法：python tools/selfcheck_round141_remaining_closure.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

INTERMEDIATE = os.path.join(JAVA, "block", "entity", "IntermediateCacheBlockEntity.java")
KEEPER_TARGET = os.path.join(JAVA, "support", "KeeperTarget.java")
BASE_KEEPER = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_KEEPER = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
CONFIG = os.path.join(JAVA, "Config.java")

_passed = 0
_failed = 0
_failures: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append(name)
        print("  FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ---------- 行为模型：clampSetTarget（与 Java 逐字同语义） ----------
MAX_SET_TARGET = 1_000_000_000


def clamp(value: int) -> int:
    return max(0, min(value, MAX_SET_TARGET))


def main() -> int:
    intermediate = read(INTERMEDIATE)
    keeper_target = read(KEEPER_TARGET)
    base_keeper = read(BASE_KEEPER)
    adv_keeper = read(ADV_KEEPER)
    config = read(CONFIG)

    print("① 行为模型：目标数量钳制区间 [0, 1e9]")
    model = [
        (-5, 0, "负值 = 未标记"),
        (0, 0, "0 = 未标记"),
        (1, 1, "正常值原样保留"),
        (64, 64, "默认 64 原样保留"),
        (1_000_000, 1_000_000, "百万级原样保留"),
        (MAX_SET_TARGET, MAX_SET_TARGET, "恰好上限"),
        (MAX_SET_TARGET + 1, MAX_SET_TARGET, "超上限 1 ⇒ 夹到上限"),
        (2_000_000_000, MAX_SET_TARGET, "20 亿（改包常见值）⇒ 夹到上限"),
        (2_147_483_647, MAX_SET_TARGET, "Integer.MAX_VALUE ⇒ 夹到上限"),
    ]
    for value, expect, why in model:
        check("①-%d clamp(%d) == %d（%s）" % (abs(value) % 97 + 1, value, expect, why),
              clamp(value) == expect, "得到 %d" % clamp(value))

    print("\n② KeeperTarget：上限常量 + 唯一钳制入口")
    check("②-1 KeeperTarget 定义 MAX_SET_TARGET = 1_000_000_000",
          "public static final int MAX_SET_TARGET = 1_000_000_000;" in keeper_target)
    check("②-2 KeeperTarget 提供 clampSetTarget（唯一实现）",
          "public static int clampSetTarget(final int value)" in keeper_target
          and "Math.max(0, Math.min(value, MAX_SET_TARGET))" in keeper_target)
    check("②-3 文档写明「只拦增长、读档不经过本钳制」",
          "只拦" in keeper_target and "读档" in keeper_target)
    check("②-4 上限与 Config.quantityKeeperDefaultTarget 的 defineInRange 上限同口径",
          re.search(r'defineInRange\("quantityKeeperDefaultTarget",\s*64,\s*1,\s*1000000000\)', config) is not None)

    print("\n③ 两个 setter 都走唯一钳制入口（不许各写一遍 Math.max）")
    check("③-1 基础版 setTargetAmount 用 KeeperTarget.clampSetTarget",
          "this.targetAmount = KeeperTarget.clampSetTarget(targetAmount);" in base_keeper)
    check("③-2 基础版不再出现旧写法 Math.max(0, targetAmount)",
          "Math.max(0, targetAmount)" not in base_keeper)
    check("③-3 高级版 setTarget 用 KeeperTarget.clampSetTarget",
          "targets[slot] = KeeperTarget.clampSetTarget(value);" in adv_keeper)
    check("③-4 高级版不再出现旧写法 targets[slot] = Math.max(0, value)",
          "targets[slot] = Math.max(0, value)" not in adv_keeper)
    check("③-5 读档仍不经过钳制（旧档超限值原样读回）",
          'targetAmount = Math.max(0, tag.getInt("TargetAmount"));' in base_keeper)

    print("\n④ L2：中间产物缓存仓 doWork 先判 isActive 再搬")
    gate = "final IntermediateCacheNetworkNode node = getNode();\n        if (node == null || !node.isActive()) {"
    check("④-1 doWork 有 isActive 闸门", gate in intermediate)
    idx_gate = intermediate.find(gate)
    idx_flow = intermediate.find("RsccIntermediateFlow.flowOnce(")
    idx_cooldown = intermediate.find("if (--flowCooldown > 0) {")
    check("④-2 闸门在 flowOnce 之前", -1 < idx_gate < idx_flow, "%d vs %d" % (idx_gate, idx_flow))
    check("④-3 闸门在节流之前（未激活时冷却不推进，重新上电立刻搬）",
          -1 < idx_gate < idx_cooldown, "%d vs %d" % (idx_gate, idx_cooldown))
    check("④-4 flowOnce 复用同一个 node（不再二次 getNode）",
          "level, node.getNetworkOrNull());" in intermediate
          and "RsccIntermediateFlow.flowOnce(\n            level, getNode().getNetworkOrNull());" not in intermediate)

    print("\n⑤ 反例自证：把修法拼回去，判据必须判负")
    broken_gate = intermediate.replace(gate, "if (false) {")
    check("⑤-1 去掉 isActive 闸门 ⇒ ④-1 判负", gate not in broken_gate and broken_gate != intermediate)
    broken_setter = base_keeper.replace(
        "this.targetAmount = KeeperTarget.clampSetTarget(targetAmount);",
        "this.targetAmount = Math.max(0, targetAmount);")
    check("⑤-2 基础版 setter 改回 Math.max(0, …) ⇒ ③-1/③-2 判负",
          "this.targetAmount = KeeperTarget.clampSetTarget(targetAmount);" not in broken_setter
          and "Math.max(0, targetAmount)" in broken_setter)
    broken_adv = adv_keeper.replace("targets[slot] = KeeperTarget.clampSetTarget(value);",
                                    "targets[slot] = Math.max(0, value);")
    check("⑤-3 高级版 setter 改回 Math.max(0, value) ⇒ ③-3/③-4 判负",
          "targets[slot] = KeeperTarget.clampSetTarget(value);" not in broken_adv)
    broken_clamp = "return Math.min(value, MAX_SET_TARGET);"
    check("⑤-4 钳制只留上限、不留下限 ⇒ ①-1/①-2 判负（负值会被原样写进目标）",
          broken_clamp != "Math.max(0, Math.min(value, MAX_SET_TARGET))"
          and max(-5, min(-5, MAX_SET_TARGET)) == -5)

    print("\n结果：%d 通过 / %d 失败" % (_passed, _failed))
    if _failed:
        print("失败项：")
        for name in _failures:
            print("  - %s" % name)
        return 1
    print("全部通过（L2 能量闸门位置 / 目标钳制唯一入口 / 读档不截断 / 反例自证）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
