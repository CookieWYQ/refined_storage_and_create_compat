import sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
# 只读静态审计：把「按总线 / 按类别 / 按链成员」的循环嵌套数出来，
# 用来估算 busExportFilters 一次调用内部会真算几次结构性查找（chainMembers / chamberAt）。
# 这不是性能测量，是调用点普查：结论必须与源码行号对得上。
import re, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/cretae/cookiewyq/rs_create_compat"

def read(rel):
    return (SRC / rel).read_text(encoding="utf-8", errors="replace").splitlines()

def count(lines, pattern):
    rx = re.compile(pattern)
    hits = []
    for i, line in enumerate(lines, 1):
        if rx.search(line):
            hits.append((i, line.strip()))
    return hits

CH = "block/entity/SequenceExecutionChamberBlockEntity.java"
lines = read(CH)

print("== chainMembers() 调用点（含 javadoc 引用） ==")
call_rx = re.compile(r"(?<![\w.])chainMembers\(\)")
plain = []
for i, (ln, txt) in enumerate(zip(range(1, len(lines) + 1), lines)):
    if call_rx.search(txt):
        # 区分「真调用」与「javadoc / 注释里的引用」
        stripped = txt.strip()
        is_doc = stripped.startswith("*") or stripped.startswith("//") or stripped.startswith("/*")
        plain.append((ln, is_doc, stripped))
real = [p for p in plain if not p[1]]
doc = [p for p in plain if p[1]]
print("  真调用 %d 处 / 文档引用 %d 处" % (len(real), len(doc)))
for ln, _, txt in real:
    print("   L%-6d %s" % (ln, txt))

# 关键放大点：busExportFilters 里的 for member : chainMembers()
print()
print("== 外层循环（放大因子） ==")
for ln, _, txt in real:
    pass
amp = {
    "pushBusTurnToExporters 每 tick 上限": ("BUS_TURN_BUDGET_PER_TICK", 8),
    "链成员上限": ("MAX_CHAIN_LENGTH", 8),
}
for name, (const, val) in amp.items():
    hits = count(lines, re.escape(const) + r"\s*=")
    print("  %s = %d  @ %s" % (name, val, hits))

print()
print("== busExportFilters 的成员循环（每个总线每 tick 的真实成本乘子） ==")
for ln in (5680, 5686):
    print("   L%-6d %s" % (ln, lines[ln - 1].strip()))

print()
print("== rscc$applyExportFilters 的调用点（每 tick 最多推进 8 台总线） ==")
mx = read("mixin/exporter/AbstractExporterBlockEntityMixin.java")
for ln, txt in count(mx, r"rscc\$applyExportFilters\(\)"):
    print("   L%-6d %s" % (ln, txt))
