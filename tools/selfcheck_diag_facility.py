# -*- coding: utf-8 -*-
"""自检：一键诊断设施（指令注册 / 快照八项字段齐全 / 幂等 / 分析脚本鲁棒）。

断言（对应用户验收要求）：
  ① 快照八项字段齐全：用**构造的完整假状态夹具**推演，断言每个必需字段都存在且非默认空；
  ② 幂等：同一输入两次生成报告，除时间戳行外逐字节一致；
  ③ 指令已注册且无权限要求（源码锚点断言）；
  ④ diagnose_all.py 在「没有快照」与「有快照 + 旧日志」两种输入下都能跑完并明确说明证据不足。

用法：python tools/selfcheck_diag_facility.py
末行固定为 `SELFCHECK OK (N checks)` 或 `SELFCHECK FAILED (M/N)`。
"""
from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# 导入 diagnose_all 时不落 __pycache__（保持工作区干净）。
sys.dont_write_bytecode = True

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
FIXTURE = os.path.join(TOOLS, "fixtures", "diag_snapshot_full.json")
SANDBOX = os.path.join(ROOT, "build", "diag_selftest")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def resolve(obj, path):
    """把形如 `chambers[*].materials[*]` 的路径展开成实际节点列表。"""
    nodes = [obj]
    for part in path.split("."):
        star = part.endswith("[*]")
        key = part[:-3] if star else part
        nxt = []
        for node in nodes:
            if not isinstance(node, dict) or key not in node:
                continue
            value = node[key]
            if star:
                if isinstance(value, list):
                    nxt.extend(value)
            else:
                nxt.append(value)
        nodes = nxt
    return nodes


def is_default_empty(value):
    return value is None or value == "" or value == [] or value == {}


# 这些字段「存在但为空」本身就是正确值（例如「没有不平账」= 空表），因此不按「非默认空」判定。
ALLOW_EMPTY = {"unbalanced", "items", "fluids"}


# ---------------------------------------------------------------- ① 字段齐全

def schema_checks(diag):
    print()
    print("== ① 快照八项字段齐全（构造的完整假状态） ==")
    if not os.path.isfile(FIXTURE):
        check("夹具存在", False, FIXTURE)
        return
    data = json.load(io.open(FIXTURE, encoding="utf-8"))
    check("夹具 schema 标识", diag.schema_version_ok(data))
    for path, keys in diag.REQUIRED_SCHEMA.items():
        nodes = resolve(data, path)
        check("路径可达且非空：%s" % path, bool(nodes), "resolve -> 0 节点")
        if not nodes:
            continue
        for key in keys:
            missing = [i for i, node in enumerate(nodes)
                       if not isinstance(node, dict) or key not in node
                       or (key not in ALLOW_EMPTY and is_default_empty(node.get(key)))]
            check("  %s[*].%s 存在且非默认空" % (path, key) if keys else "  %s 非默认空" % key,
                  not missing, "%d 个节点缺该字段/为空" % len(missing) if missing else "")


# ---------------------------------------------------------------- ③ 指令锚点

def command_checks():
    print()
    print("== ③ 指令已注册且无权限要求（源码锚点） ==")
    commands = read(os.path.join(SRC, "command", "CompatCommands.java"))
    main = read(os.path.join(SRC, "RS_Create_Compat.java"))
    diag_java = read(os.path.join(SRC, "support", "RsccDiag.java"))
    check("CompatCommands 注册了 diag 字面量", 'Commands.literal("diag")' in commands)
    check("根指令无权限要求（requires(source -> true)）", ".requires(source -> true)" in commands)
    check("主类挂载 CompatCommands.register", "CompatCommands.register" in main)
    for group in ("chambers", "buses", "counters", "recentEvents", "shortage",
                  "conservation", "keepers", "camouflage", "recipes",
                  # 2026-10-05 新增：用户要求「导出当前网络所有这些机子里面的内部数据，
                  # 比如说某台机子我对它的设置」—— 目的正是让「玩家复述」不再参与定位。
                  "machines", "containers", "universalStorage", "playerInventory"):
        check("快照含分组字段 %s" % group, '"%s"' % group in diag_java)
    check("日志分节前缀 [rscc-diag]", 'PREFIX = "[rscc-diag]"' in diag_java)
    check("默认采集锚点行（diag logging ON (default)）", "diag logging ON (default)" in diag_java)
    check("确定性 JSON 写入器存在", os.path.isfile(os.path.join(SRC, "support", "RsccDiagJson.java")))


# ------------------------------------------------- ③b 全机器导出（2026-10-05 新增）

def export_checks():
    """断言「每台机器都能被导出」这条链路在源码里成立。

    为什么必须有它：用户明确要求导出「所有机子的内部数据 + 我对它的设置」，理由是
    「有时候我记错了，然后我讲错你也就修错」。而本轮实机取证恰好证明这条要求的必要性 ——
    玩家报「机器满了，但机械手和置物台完全是空的」，当时的快照只能给出
    `reason=EXECUTOR_OFFLINE` 一个词，既看不出是「下游拒收」还是「某步没机器认领」，
    也看不出那台机器的总线勾了什么、面是什么模式。因此这里把「导出覆盖面」钉进自检，
    任何一次重构若把某个机器类型漏出导出，自检立刻红。
    """
    print()
    print("== ③b 全机器 / 全容器导出（2026-10-05） ==")
    diag_java = read(os.path.join(SRC, "support", "RsccDiag.java"))
    # ① 通用兜底：任何方块实体都有一条（方块 id + 完整 NBT + 容器槽位）
    check("通用兜底：每个方块实体都进 machines", "machines.add(machineReport(level, be));" in diag_java)
    check("机器报告含完整 NBT（存档里的真实设置）", "saveWithoutMetadata(level.registryAccess())" in diag_java)
    check("NBT 截断保护（避免快照膨胀到不可读）", "4000" in diag_java and "…(截断)" in diag_java)
    # 2026-10-05 实机踩到：只判 `instanceof Container` ⇒ containers=0（本模组机器只是**持有**容器，
    # 自身不是原版 Container：ItemStackHandler / SimpleContainer 都是 getter 返回的对象）。
    # 因此这里必须同时钉住「反射枚举 getter」这条路，否则「导出每台机器的内部数据」会静默落空。
    check("容器槽位逐个列出", "be instanceof Container container)) {" in diag_java
          and 'out.put("nonEmptySlots", slots);' in diag_java
          and 'out.put("size", size);' in diag_java)
    check("容器导出还覆盖「持有容器」的机器（反射枚举 getter，避免 containers=0）",
          "method.getParameterCount() != 0" in diag_java
          and "ItemStackHandler.class\n                .isAssignableFrom(type)" in diag_java
          and 'out.put("slotGroups", groups);' in diag_java)
    check("槽组名取 getter 短名（inventory / queue / upgradeContainer…）",
          "private static String decap(final String getter)" in diag_java)
    check("玩家背包只列本模组相关物品（不做整包噪声）",
          "rs_create_compat:" in diag_java and "refinedstorage:" in diag_java
          and 'out.put("held", itemLabel(player.getMainHandItem()));' in diag_java)
    check("通用储存磁盘内容随快照导出", "universal_storage_disk" in diag_java)
    # ② 机器自述接口（字段比 NBT 更可读的那些机器）
    interface = os.path.join(SRC, "support", "RsccDiagnosable.java")
    check("机器自述接口存在", os.path.isfile(interface))
    check("diag 侧按接口取自述", "be instanceof RsccDiagnosable" in diag_java)
    for name, kind, path in (
        ("SchematicLoaderBlockEntity", "schematic_loader", os.path.join("block", "entity", "SchematicLoaderBlockEntity.java")),
        ("CollectionCacheBlockEntity", "collection_cache", os.path.join("block", "entity", "CollectionCacheBlockEntity.java")),
    ):
        source = read(os.path.join(SRC, path))
        check("%s 实现自述接口（implements RsccDiagnosable）" % name, "RsccDiagnosable" in source)
        check("%s 自述含 kind=%s" % (name, kind), '"kind", "%s"' % kind in source)
    loader = read(os.path.join(SRC, "block", "entity", "SchematicLoaderBlockEntity.java"))
    for setting in ("autoPrint", "autoRecycle", "autoFillGunpowder", "materialPullLimit",
                    "blueprintSlotLocked", "queue", "upgrades", "attachedCannon"):
        check("装填器自述含设置 %s" % setting, '"%s"' % setting in loader)
    cache = read(os.path.join(SRC, "block", "entity", "CollectionCacheBlockEntity.java"))
    for setting in ("radiusX", "radiusY", "radiusZ", "absorb", "collectAll", "invertMatch",
                    "inputFacesMask", "xpForm", "markers", "blockedItems"):
        check("归流缓存仓自述含设置 %s" % setting, '"%s"' % setting in cache)


# ---------------------------------------------------------------- ④ + ② 沙盒

def write_synthetic(root, with_snapshot):
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(os.path.join(root, "run", "logs"), exist_ok=True)
    os.makedirs(os.path.join(root, "run", "rscc_diag", "latest"), exist_ok=True)
    if with_snapshot:
        shutil.copyfile(FIXTURE, os.path.join(root, "run", "rscc_diag", "latest", "snapshot.json"))
    log = [
        "[0110月2026 11:50:00.000] [Server thread/INFO] [rscc] diag logging ON (default) session=2026-10-01T11:50:00",
        "[0110月2026 11:51:00.000] [Server thread/INFO] [rscc-assembly] binding task=aaaa product=create:sturdy_sheet x1 steps=4 loops=1 executor=(-7,-58,5)",
        "[0110月2026 11:51:01.000] [Server thread/INFO] [rscc-trace] item=create:powdered_obsidian x1 | from=network | event=take_to_chamber | to=chamber@(-7,-58,5) | reason=ok | net=32",
        "[0110月2026 11:51:02.000] [Server thread/INFO] [rscc-trace] item=create:sturdy_sheet x1 | from=chamber@(-7,-58,5) | event=insert_network | to=network | reason=ok | net=1",
        "[0110月2026 11:51:10.000] [Server thread/INFO] [rscc-assembly] binding task=bbbb product=create:sturdy_sheet x64 steps=4 loops=1 executor=(-7,-58,5)",
        "[0110月2026 11:51:20.000] [Server thread/INFO] [rscc-trace] item=create:powdered_obsidian x4 | from=network | event=take_to_chamber | to=chamber@(-7,-58,5) | reason=ok | net=64",
        "[0110月2026 11:51:30.000] [Server thread/INFO] [rscc-assembly] binding task=cccc product=create:precision_mechanism x1 steps=5 loops=1 executor=(-7,-58,5)",
        "[0110月2026 11:51:40.000] [Server thread/INFO] [rscc-trace] item=create:andesite_alloy x1 | from=machine@(-7,-58,6) | event=take_from_machine | to=chamber@(-7,-58,5) | reason=ok | net=8",
        "[0110月2026 11:51:50.000] [Server thread/INFO] [rscc-assembly] binding task=dddd product=create:precision_mechanism x64 steps=5 loops=1 executor=(-7,-58,5)",
    ]
    with io.open(os.path.join(root, "run", "logs", "latest.log"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(log) + "\n")


def robustness_checks(diag):
    print()
    print("== ④ + ② 沙盒：无快照 / 有快照+旧日志 / 幂等 ==")
    empty_root = os.path.join(SANDBOX, "no_snapshot")
    write_synthetic(empty_root, with_snapshot=False)
    try:
        diag.main(["--root", empty_root])
        ok_empty = True
        detail_empty = ""
    except Exception as exception:  # noqa: BLE001
        ok_empty = False
        detail_empty = repr(exception)
    check("④a 无快照时不抛异常", ok_empty, detail_empty)
    report_a = os.path.join(empty_root, "run", "rscc_diag", "latest", "report.md")
    text_a = read(report_a) if os.path.isfile(report_a) else ""
    check("④a 无快照时仍产出报告", bool(text_a))
    check("④a 报告明确写出「证据不足」", "证据不足" in text_a)
    check("④a 报告说明缺少快照", "快照缺失" in text_a or "无快照" in text_a)

    full_root = os.path.join(SANDBOX, "with_snapshot")
    write_synthetic(full_root, with_snapshot=True)
    try:
        diag.main(["--root", full_root])
        first = read(os.path.join(full_root, "run", "rscc_diag", "latest", "report.md"))
        diag.main(["--root", full_root])
        second = read(os.path.join(full_root, "run", "rscc_diag", "latest", "report.md"))
        ok_full = True
        detail_full = ""
    except Exception as exception:  # noqa: BLE001
        ok_full = False
        detail_full = repr(exception)
        first = second = ""
    check("④b 有快照+旧日志时不抛异常", ok_full, detail_full)
    check("④b 有快照时切出 4 个订单段", first.count("## 段 ") >= 4,
          "段数=%d" % first.count("## 段 "))
    check("④b 报告含「证据不足」说明（④ 无单窗口天然缺数据）", "证据不足" in first)

    def strip_timestamp(text):
        return "\n".join(line for line in text.splitlines() if not line.startswith("生成时间："))

    check("② 报告幂等（两次生成除时间戳外逐字节一致）",
          bool(first) and strip_timestamp(first) == strip_timestamp(second))


def main():
    diag = load_module("diagnose_all", os.path.join(TOOLS, "diagnose_all.py"))
    schema_checks(diag)
    command_checks()
    export_checks()
    robustness_checks(diag)
    print()
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
