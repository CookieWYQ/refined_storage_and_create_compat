# -*- coding: utf-8 -*-
"""round48 源码侧日志审计：
1) 统计 RsccAssemblyDebug / LoggerFactory / LOGGER. / LOG.info 的出现次数；
2) 列出全部「直接调用 slf4j 输出」的点（这些才是开关关掉后仍会打的行），并标注它是否已被
   RsccAssemblyDebug.isEnabled() / config 守卫（同因合并 / 状态翻转 / 一次性）。
"""
import sys, os, re, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.join("src", "main", "java")
OUT = os.path.join("build", "round48_src_log_audit.txt")
_report = open(OUT, "w", encoding="utf-8")
_real_print = print


def print(*a, **kw):  # noqa: A001 - 双写：屏幕 + 报告
    _real_print(*a, **kw)
    kw.pop("flush", None)
    _real_print(*a, file=_report, **kw)

# 直接输出调用（receiver 是 logger 变量）
CALL = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\.(info|warn|error|debug|trace)\s*\(")
# 认为是 logger 的变量名
LOGGER_NAMES = {"LOGGER", "LOG", "ORG_SLF4J", "LOGGER_", "LOG_"}
DEBUG_API = re.compile(r"RsccAssemblyDebug\.(event|trace|transition|reject|repeat|reason|"
                       r"warn|dedupe|countPull|countPullFluid|countFeed|countCollect|countReturn|"
                       r"countReject|changed|isEnabled)")

files = []
for base, _dirs, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".java"):
            files.append(os.path.join(base, n))
files.sort()

counts = collections.Counter()
direct = []          # (file, line, level, text, guarded)
debugapi = []
for path in files:
    text = open(path, "r", encoding="utf-8", errors="replace").read()
    counts["files"] += 1
    counts["RsccAssemblyDebug 引用次数"] += len(re.findall(r"RsccAssemblyDebug", text))
    counts["LoggerFactory.getLogger 次数"] += len(re.findall(r"LoggerFactory\.getLogger", text))
    counts["LOGGER. 出现次数"] += len(re.findall(r"\bLOGGER\.", text))
    counts["直接 .info( 次数"] += len(re.findall(r"\.info\s*\(", text))
    counts["直接 .warn( 次数"] += len(re.findall(r"\.warn\s*\(", text))
    counts["直接 .error( 次数"] += len(re.findall(r"\.error\s*\(", text))
    lines = text.splitlines()
    for i, line in enumerate(lines, 1):
        for m in CALL.finditer(line):
            if m.group(1) not in LOGGER_NAMES:
                continue
            # 往前找 12 行，看有没有 RsccAssemblyDebug 守卫 / config 守卫
            ctx = "\n".join(lines[max(0, i - 14):i])
            guarded = bool(re.search(r"RsccAssemblyDebug\.isEnabled|Config\.rsccAssemblyDebug|"
                                     r"RsccAssemblyDebug\.changed", ctx))
            direct.append((path, i, m.group(2), line.strip(), guarded))
        for m in DEBUG_API.finditer(line):
            debugapi.append((path, i, m.group(1)))

print("=== 出现次数统计（src/main/java 全量）===")
for k in ["files", "RsccAssemblyDebug 引用次数", "LoggerFactory.getLogger 次数", "LOGGER. 出现次数",
          "直接 .info( 次数", "直接 .warn( 次数", "直接 .error( 次数"]:
    print("%-28s %6d" % (k, counts[k]))
print()
print("=== RsccAssemblyDebug 各 API 调用点次数 ===")
for k, n in collections.Counter(a for _, _, a in debugapi).most_common():
    print("%-20s %6d" % (k, n))
print()
print("=== 直接 slf4j 输出点（%d 处）===" % len(direct))
byfile = collections.Counter(os.path.relpath(p, ROOT) for p, _, _, _, _ in direct)
for f, n in byfile.most_common():
    print("%-70s %4d" % (f, n))
print()
print("=== 未被 RsccAssemblyDebug/config 守卫的直接输出点 ===")
unguarded = [d for d in direct if not d[4]]
for p, i, lvl, txt, _ in unguarded:
    print("%s:%d [%s] %s" % (os.path.relpath(p, ROOT).replace("\\", "/"), i, lvl, txt[:150]))
print()
print("总计：直接输出 %d 处，其中未被守卫 %d 处" % (len(direct), len(unguarded)))
_report.close()
