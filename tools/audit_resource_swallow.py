#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""静态审计：找出「吞资源」反模式（先删后插 / 不检查插入结果 / 无容量判断就动手）。

针对的硬规则（用户原文）：
    「任何收集物品/流体的机器，缓存满时不能吞资源。非常重要。」

本脚本是一个**可重复运行的回归工具**：它只做静态模式匹配，不依赖编译，
用于在后续改动中快速定位「可能把玩家资源弄丢」的代码位置。判据如下。

规则
----
R1 EXTRACT_THEN_UNCHECKED_INSERT
    「消耗型语句」（真正的抽取 / 清空：`extractItem(..., false)`、`extract(..., EXECUTE, ...)`、
    `drain(..., EXECUTE)`、`discard()`、`setStackInSlot(i, EMPTY)`、`setBlock(... AIR)` …）
    之后 8 条语句内出现**未检查返回值**的插入（`xxx.insert(...)` / `xxx.insertItem(...)` /
    `xxx.fill(...)` 这类裸调用），且两者之间**没有任何容量判断**
    （`SIMULATE` / `getFreeSpace` / `getRemainingCapacity` / `simulateCacheInsert` …）。
    → 抽取的量可能直接消失（目标已满时被吞）。

R2 UNCHECKED_INSERT_THEN_DESTROY
    未检查返回值的插入之后 2 条语句内紧跟「销毁型语句」（清槽 / 丢弃 / 移除方块）。
    → 插入失败也照样把源删了（先插后删但没看插入结果）。

R3 DESTROY_WITHOUT_CAPACITY_CHECK
    方法体内出现销毁型语句，但此前既没有容量判断、也没有「结果被判定的插入」
    （`X = ....insert(...)`）。
    → 缺少容量核对的删除动作。

豁免
----
在**方法体内任意位置**写一行注释标记即可豁免该方法的所有命中：

    // rscc-audit-ok: <理由>

脚本会把豁免项与其理由逐条列出，便于人工复核（豁免必须写明为什么安全）。

用法
----
    python tools/audit_resource_swallow.py            # 审计并打印报告
    python tools/audit_resource_swallow.py -v         # 额外打印源码片段
    python tools/audit_resource_swallow.py --files a.java b.java   # 只审计指定文件

灵敏度自检（证明脚本对真实缺陷有效，而不是「什么都没查出来」）：

    python tools/audit_resource_swallow.py --files tools/_selftest_swallow_sample.java.txt
    # 应报出 R1 + R2 两条命中（该文件故意复刻了修复前的「先删后插」写法，不参与编译）

退出码：0 = 没有未处理的可疑点；1 = 存在未处理的可疑点。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOT = os.path.join(REPO_ROOT, "src", "main", "java")
MARKER = "rscc-audit-ok"

# --------------------------------------------------------------------------- #
# 语句分类
# --------------------------------------------------------------------------- #

# 消耗型语句：真的把资源从某个地方拿走了
CONSUME_PATTERNS = [
    re.compile(r"\.extractItem\([^;]*,\s*false\s*\)"),          # IItemHandler 真抽取
    re.compile(r"\.extract\([^;]*Action\.EXECUTE[^;]*\)"),       # RS 存储 / 内部缓存真抽取
    re.compile(r"\.drain\([^;]*EXECUTE[^;]*\)"),                 # 流体真抽取
    re.compile(r"\.removeItem\([^)]*\)"),                        # Container 取走
    re.compile(r"\.discard\(\)"),                                # 实体移除
    re.compile(r"\.kill\(\)"),
    re.compile(r"\.setStackInSlot\([^;]*ItemStack\.EMPTY[^;]*\)"),
    re.compile(r"\.setItem\([^;]*ItemStack\.EMPTY[^;]*\)"),
    re.compile(r"\.setFluid\([^;]*FluidStack\.EMPTY[^;]*\)"),
    re.compile(r"\.setBlock\([^;]*Blocks\.AIR[^;]*\)"),
]

# 销毁型语句：只保留真正「丢弃 / 清空资源」的那几条（用于 R2 / R3）
DESTROY_PATTERNS = [
    re.compile(r"\.discard\(\)"),
    re.compile(r"\.kill\(\)"),
    re.compile(r"\.removeItem\([^)]*\)"),
    re.compile(r"\.setStackInSlot\([^;]*ItemStack\.EMPTY[^;]*\)"),
    re.compile(r"\.setItem\([^;]*ItemStack\.EMPTY[^;]*\)"),
    re.compile(r"\.setFluid\([^;]*FluidStack\.EMPTY[^;]*\)"),
    re.compile(r"\.setBlock\([^;]*Blocks\.AIR[^;]*\)"),
]

# 「外部来源」标志：只有和外界打交道的方法才可能是「收集机」，用于给 R3 收窄范围
# （避免把 NBT 读档 / GUI 列表 / 标记槽操作这类与容量无关的清空误报成吞资源）。
EXTERNAL_SOURCE_TOKENS = [
    "getCapability",
    "Capabilities.",
    "getEntitiesOfClass",
    "ItemEntity",
    "IItemHandler",
    "IFluidHandler",
    "extractItem(",
    "getBlockEntity(",
    "ItemHandlerHelper",
    "storage.extract(",
    "net.insert(",
    "pushItemsToNetwork",
    "pullItems(",
    "pullFluid(",
]

# 未检查返回值的插入 / 交付：整条语句就是一个裸方法调用，且方法名带「插入 / 交付」语义
BARE_CALL = re.compile(r"^[A-Za-z_$][\w.$]*(\.[A-Za-z_$][\w$]*)?\s*\(")
TRANSFER_NAME = re.compile(
    r"(?:^|\.)(?:insert|insertItem|addItem|offerItem|fill|push\w*|store\w*|returnTo\w*|give\w*)\s*\(")

# 容量判断：出现这些就认为「代码真的核对过目标容量」
# 注意：故意不把 isEmpty() / getCount() 算进来 —— 它们只是「非空 / 计数」检查，
# 不能证明目标装得下，否则会漏掉「抽完发现装不下就把东西丢了」这类真实缺陷。
CAPACITY_TOKENS = [
    "SIMULATE",
    "simulate",
    "getFreeSpace",
    "getRemainingCapacity",
    "simulateCacheInsert",
    "canAccept",
    "acceptable",
    "notStorable",
    "simulateInsertIntoCluster",
]

# 「结果被判定」的插入：赋值给变量 / return（含 insertIntoCache、pushItemsToNetwork 这类自研方法）
ASSIGNED_INSERT = re.compile(
    r"^\s*(?:final\s+)?[\w<>\[\],.\s]+\s+\w+\s*=\s*[^;]*\b\w*(?:[Ii]nsert|fill|Fill|push|Push)\w*\s*\(")
RETURN_INSERT = re.compile(r"^\s*return\s+[^;]*\b\w*(?:[Ii]nsert|fill|Fill|push|Push)\w*\s*\(")

MEMBER_START = re.compile(
    r"^    (?:@|public\b|private\b|protected\b|static\b|final\b|synchronized\b|abstract\b|"
    r"default\b|void\b|boolean\b|int\b|long\b|double\b|float\b|String\b|ItemStack\b|FluidStack\b|"
    r"List\b|Map\b|Set\b|Component\b|ResourceLocation\b|CompoundTag\b)")


def is_consume(stmt: str) -> bool:
    return any(p.search(stmt) for p in CONSUME_PATTERNS)


def is_destroy(stmt: str) -> bool:
    return any(p.search(stmt) for p in DESTROY_PATTERNS)


def is_unchecked_insert(stmt: str) -> bool:
    if not BARE_CALL.match(stmt):
        return False
    if ASSIGNED_INSERT.match(stmt) or RETURN_INSERT.match(stmt):
        return False
    return bool(TRANSFER_NAME.search(stmt))


def has_capacity_token(stmts: list[str]) -> bool:
    joined = "\n".join(stmts)
    return any(tok in joined for tok in CAPACITY_TOKENS)


def is_assigned_insert(stmt: str) -> bool:
    return bool(ASSIGNED_INSERT.match(stmt) or RETURN_INSERT.match(stmt))


# --------------------------------------------------------------------------- #
# 源码解析：按「成员（方法）」切块，方法内按「语句」切块
# --------------------------------------------------------------------------- #


@dataclass
class Statement:
    line: int
    text: str


@dataclass
class Method:
    name: str
    start_line: int
    stmts: list[Statement] = field(default_factory=list)
    raw_lines: list[str] = field(default_factory=list)


@dataclass
class Finding:
    path: str
    line: int
    rule: str
    message: str
    method: str
    snippet: str
    suppressed: bool = False
    reason: str = ""


def strip_comments(lines: list[str]) -> list[str]:
    """去掉行注释与块注释（保留行号一一对应），避免注释里的示例代码被误判。"""
    out: list[str] = []
    in_block = False
    for line in lines:
        stripped = line
        if in_block:
            end = stripped.find("*/")
            if end < 0:
                out.append("")
                continue
            stripped = " " * (end + 2) + stripped[end + 2:]
            in_block = False
        start = stripped.find("/*")
        while start >= 0:
            end = stripped.find("*/", start + 2)
            if end < 0:
                stripped = stripped[:start]
                in_block = True
                break
            stripped = stripped[:start] + " " * (end + 2 - start) + stripped[end + 2:]
            start = stripped.find("/*", start)
        # 行注释
        hash_idx = stripped.find("//")
        if hash_idx >= 0:
            stripped = stripped[:hash_idx]
        out.append(stripped.rstrip())
    return out


def split_methods(lines: list[str]) -> list[Method]:
    """按缩进 4 的成员声明行切块（本仓库统一 4 空格缩进，够用且不引入真正的 Java 解析器）。"""
    methods: list[Method] = []
    current: Method | None = None
    for idx, line in enumerate(lines):
        if MEMBER_START.match(line):
            current = Method(name=line.strip()[:90], start_line=idx + 1)
            methods.append(current)
        elif current is not None:
            current.raw_lines.append(line)
    for m in methods:
        m.stmts = split_statements(m.raw_lines, m.start_line)
    return methods


def split_statements(raw_lines: list[str], start_line: int) -> list[Statement]:
    """把方法体按 `;` 聚合为语句（多行调用合成一条），便于跨行匹配。

    关键点：括号深度要**跨行累计**，否则 `foo.insert(a,\n b);` 这类多行调用会被拆成两条，
    导致「赋值过的插入」被误判成「未检查返回值的插入」。
    """
    stmts: list[Statement] = []
    buffer: list[str] = []
    buffer_start = start_line
    depth = 0
    for offset, line in enumerate(raw_lines):
        text = line.strip()
        if not text:
            continue
        if not buffer:
            buffer_start = start_line + offset + 1
        buffer.append(text)
        depth = max(0, depth + text.count("(") - text.count(")"))
        ends_block = text.endswith("{") or text.endswith("}") or text.startswith("}")
        if depth <= 0 and (text.endswith(";") or ends_block):
            stmts.append(Statement(buffer_start, " ".join(buffer)))
            buffer = []
            depth = 0
    if buffer:
        stmts.append(Statement(buffer_start, " ".join(buffer)))
    return stmts


def scan_file(path: str, verbose: bool) -> list[Finding]:
    with open(path, "r", encoding="utf-8") as fh:
        raw = fh.read().splitlines()
    lines = strip_comments(raw)

    findings: list[Finding] = []
    for method in split_methods(lines):
        # 方法体里出现 rscc-audit-ok 标记 → 整个方法的命中都算「已说明理由的豁免」
        body_marker = None
        method_raw = raw[method.start_line - 1:method.start_line - 1 + len(method.raw_lines)]
        for line in method_raw:
            if MARKER in line:
                body_marker = line.split(MARKER, 1)[1].lstrip(":： ").strip()
                break
        found: list[Finding] = []
        stmts = method.stmts
        joined_all = "\n".join(s.text for s in stmts)
        touches_external = any(tok in joined_all for tok in EXTERNAL_SOURCE_TOKENS)
        for i, stmt in enumerate(stmts):
            if is_consume(stmt.text):
                for j in range(i + 1, min(i + 9, len(stmts))):
                    later = stmts[j]
                    interim = [s.text for s in stmts[i + 1:j]]
                    # 容量核对可以出现在抽取之前（先算容量再抽 = 正确顺序），
                    # 也可以出现在抽取与插入之间；此外「结果被判定的插入」说明这是回滚残料 → 都不算可疑
                    guarded = (has_capacity_token(interim)
                               or any(map(is_assigned_insert, interim))
                               or has_capacity_token([s.text for s in stmts[:i]]))
                    if is_unchecked_insert(later.text) and not guarded:
                        found.append(Finding(
                            path=path, line=stmt.line, rule="R1",
                            message="先抽取/清空，之后出现未检查返回值的插入（其间无容量判断）",
                            method=method.name, snippet=later.text[:160]))
                        break
                    if is_destroy(later.text):
                        break
            if is_unchecked_insert(stmt.text):
                for j in range(i + 1, min(i + 3, len(stmts))):
                    later = stmts[j]
                    if is_destroy(later.text):
                        found.append(Finding(
                            path=path, line=stmt.line, rule="R2",
                            message="未检查返回值的插入之后紧跟清空/销毁动作（插入失败也会把源删掉）",
                            method=method.name, snippet=stmt.text[:160] + "  ->  " + later.text[:120]))
                        break
            if is_destroy(stmt.text) and touches_external:
                earlier = [s.text for s in stmts[:i]]
                if not has_capacity_token(earlier) and not any(map(is_assigned_insert, earlier)):
                    found.append(Finding(
                        path=path, line=stmt.line, rule="R3",
                        message="收集方法里的销毁/清空动作之前没有任何容量核对"
                                "（既无 SIMULATE 也无结果被判定的插入）",
                        method=method.name, snippet=stmt.text[:160]))
        for finding in found:
            if body_marker is not None:
                finding.suppressed = True
                finding.reason = body_marker
        findings.extend(found)
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="吞资源反模式静态审计")
    parser.add_argument("--files", nargs="*", default=None, help="只审计指定文件（默认整个 src/main/java）")
    parser.add_argument("-v", "--verbose", action="store_true", help="打印源码片段")
    args = parser.parse_args()

    if args.files:
        targets = [os.path.abspath(p) for p in args.files]
    else:
        targets = []
        for root, _dirs, files in os.walk(SRC_ROOT):
            for name in sorted(files):
                if name.endswith(".java"):
                    targets.append(os.path.join(root, name))

    findings: list[Finding] = []
    for path in targets:
        findings.extend(scan_file(path, args.verbose))

    pending = [f for f in findings if not f.suppressed]
    suppressed = [f for f in findings if f.suppressed]

    def rel(p: str) -> str:
        return os.path.relpath(p, REPO_ROOT).replace("\\", "/")

    if pending:
        print("未处理的可疑点：")
        for f in sorted(pending, key=lambda x: (x.path, x.line)):
            print(f"  [{f.rule}] {rel(f.path)}:{f.line}  {f.method}")
            print(f"         {f.message}")
            if args.verbose:
                print(f"         片段: {f.snippet}")
        print()

    if suppressed:
        print("已豁免（方法体内写了 '// rscc-audit-ok: <理由>'）：")
        for f in sorted(suppressed, key=lambda x: (x.path, x.line)):
            print(f"  [{f.rule}] {rel(f.path)}:{f.line}  理由: {f.reason or '（未写理由！）'}")
        print()

    print(f"扫描文件: {len(targets)}")
    print(f"未处理可疑点: {len(pending)}")
    print(f"已豁免: {len(suppressed)}")
    return 1 if pending else 0


if __name__ == "__main__":
    sys.exit(main())
