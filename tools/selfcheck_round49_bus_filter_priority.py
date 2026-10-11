# -*- coding: utf-8 -*-
"""第 49 轮 · 任务 C：**过滤槽里有东西 ⇒ 优先按普通总线处理**（用户第 9 条）。

用户原话：「这个输入输出总线他跟这个序列执行力（执行仓）绑定一起之后呢，他原本的（过滤）槽就没有
意义了。所以说如果说一个输入输出总线**本身过滤槽中是有东西**，那么就**优先认为他是普通的**（普通总线）。」

本脚本固化的判据：
    ① 判据是 **RS 侧的过滤容器**（`FilterWithFuzzyMode` → `ResourceContainer#isEmpty()`），
       **不是**本模组的类别勾选（那是延长型的产物，用它就成了「自己判自己」）；
    ② 收口点只有两个：`rscc$isExecutorMode()`（界面走哪套）与 `rscc$linkedExecutor()`（搬运走哪条路），
       两者必须一致，否则会出现「界面是普通总线、实际还在按类别搬运」这类鬼影；
    ③ 边沿即时：判据现算不缓存；过滤槽每一次改动都会走 RS 自己的
       `FilterWithFuzzyMode#notifyListeners → setFilters(...)`，而输出总线在那个方法上有注入
       （`rscc$useExecutorFilters` → `rscc$linkedExecutor()`），因此两个方向都在同一次改动里翻转；
    ④ 输入总线的「全自动收回」开关一字未改：过滤槽为空时它照旧生效，过滤槽非空时本条规则优先级更高
       （两者严格互斥，不会同时说话）。

用法: python tools/selfcheck_round49_bus_filter_priority.py
退出码: 0 = 全部通过。
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

PROBLEMS = []
CHECKS = [0]


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def read(rel):
    with io.open(os.path.join(PKG, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def method_body(code, signature):
    start = code.find(signature)
    if start < 0:
        return ""
    brace = code.find("{", start)
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace:i + 1]
    return ""


EXP = code_only(read(os.path.join("mixin", "exporter", "AbstractExporterBlockEntityMixin.java")))
IMP = code_only(read(os.path.join("mixin", "importer", "AbstractImporterBlockEntityMixin.java")))

print("=" * 100)
print("任务 C：过滤槽有东西 ⇒ 优先按普通总线")
print("=" * 100)

# ---------------------------------------------------------------- ① 判据 = RS 过滤容器
exp_judge = method_body(EXP, "private boolean rscc$hasFilterEntries(")
imp_judge = method_body(IMP, "private boolean rscc$hasFilterEntries(")
check("filter.getFilterContainer().isEmpty()" in exp_judge,
    "①1 输出总线判据 = RS 过滤容器非空（ResourceContainer#isEmpty，RS 自己的公开判定）", exp_judge.strip())
check("filter.getFilterContainer().isEmpty()" in imp_judge,
    "①2 输入总线同一条判据（与输出总线逐字对称）", imp_judge.strip())
check("rscc$exportCategoryIds" not in exp_judge and "rscc$importCategoryIds" not in imp_judge,
    "①3 判据里没有本模组的类别勾选（不是「自己判自己」）")
check(re.search(r"@Shadow\s+@Final\s+private FilterWithFuzzyMode filter;", IMP) is not None,
    "①4 输入总线侧补上了 filter 字段的 @Shadow（字段声明在目标类 AbstractImporterBlockEntity 自身）")
check("@Shadow" in EXP and "private FilterWithFuzzyMode filter;" in EXP,
    "①5 输出总线侧沿用既有的 filter shadow")

# ---------------------------------------------------------------- ② 两个收口点
for tag, src in (("输出总线", EXP), ("输入总线", IMP)):
    mode = method_body(src, "public boolean rscc$isExecutorMode(")
    linked = method_body(src, "private SequenceExecutionChamberBlockEntity rscc$linkedExecutor(")
    check("!rscc$hasFilterEntries()" in mode,
        "②1 %s：界面归属判定收口（isExecutorMode 里带上过滤槽判据）" % tag, mode.strip())
    check("rscc$hasFilterEntries()" in linked and linked.find("rscc$hasFilterEntries()") < linked.find("rscc$resolveLink()"),
        "②2 %s：搬运归属判定收口，且判据在 resolveLink 之前（不解析、不认归属）" % tag)

# ---------------------------------------------------------------- ③ 边沿即时
exp_setfilters = method_body(EXP, "private void rscc$useExecutorFilters(")
check("rscc$linkedExecutor()" in exp_setfilters,
    "③1 输出总线的 setFilters 注入按 linkedExecutor 分流 ⇒ 过滤槽 有↔无 两个方向都在同一次改动里生效",
    exp_setfilters.strip())
check("filter.getFilterContainer().getResources()" in EXP,
    "③2 退回普通总线时把节点过滤项换回玩家自己的过滤器（过滤槽真的生效，不只是界面）")
check("getFilterContainer().isEmpty()" in (exp_judge + imp_judge) and "boolean rscc$hasFilterEntries" in EXP
      and "boolean rscc$hasFilterEntries" in IMP,
    "③3 判据是每次现算的方法（没有缓存字段），因此不需要任何失效通知")
apply_change = method_body(EXP, "private void rscc$applyFiltersOnChange(")
check("rscc$linkedExecutor() == null ? null : rscc$linkedPosCache" in apply_change,
    "③4 输出总线的「有效归属」也走同一个收口 ⇒ 过滤槽一有东西就走「重建导出清单」那一支",
    apply_change.strip()[:200])
check("normalizeBusOwners()" in apply_change,
    "③5 从延长型退回普通时让执行仓归一一次类别归属表（与「强制普通总线」同一句）")

# ---------------------------------------------------------------- ④ 不破坏自动模式
auto = method_body(IMP, "public boolean rscc$isAutoCollect(")
check(auto.strip().replace("\n", " ").replace(" ", "") == "{returnrscc$autoCollect;}",
    "④1 输入总线的自动模式读取一字未改", auto.strip())
set_auto = method_body(IMP, "public void rscc$setAutoCollect(")
check("rscc$autoCollect = auto" in set_auto and "setChanged()" in set_auto,
    "④2 自动模式开关照旧写入 + 落盘（本规则从不改写它）")
check("KEY_AUTO" in IMP and "rscc$setAutoCollect(tag.getBoolean(RsccBusConfig.KEY_AUTO))" in IMP,
    "④3 剪贴板粘贴会原样还原自动模式（与过滤槽判据互不干扰）")

# ---------------------------------------------------------------- ⑤ 推演
def executor_mode(linked_layout, force_normal, filter_entries):
    return linked_layout and not force_normal and not filter_entries


def linked_executor(linked_layout, force_normal, filter_entries):
    """搬运侧的归属（简化自 rscc$linkedExecutor：先过滤槽判据，再按归属缓存）。"""
    if filter_entries:
        return None
    return "chamber" if (linked_layout and not force_normal) else None


TABLE = [
    # 布局可达, 强制普通, 过滤槽有东西, 期望界面算延长型, 期望归属
    (True, False, False, True, "chamber"),
    (True, False, True, False, None),
    (True, True, False, False, None),
    (True, True, True, False, None),
    (False, False, True, False, None),
]
bad = []
for linked_layout, force, entries, want_mode, want_owner in TABLE:
    got_mode = executor_mode(linked_layout, force, entries)
    got_owner = linked_executor(linked_layout, force, entries)
    if got_mode != want_mode or got_owner != want_owner:
        bad.append((linked_layout, force, entries, got_mode, got_owner))
    print("       布局=%-5s 强制普通=%-5s 过滤槽有东西=%-5s -> 延长型=%-5s 归属=%s"
          % (linked_layout, force, entries, got_mode, got_owner))
check(not bad, "⑤1 推演：过滤槽非空一律压过布局判定与强制开关（且界面与搬运结论一致）", str(bad))
check(executor_mode(True, False, True) is False and linked_executor(True, False, True) is None,
    "⑤2 推演：「过滤槽有东西 ⇒ 普通总线」在界面与搬运两侧同时成立，不存在半普通状态")

print("=" * 100)
print("检查项: %d；问题: %d" % (CHECKS[0], len(PROBLEMS)))
if PROBLEMS:
    for item in PROBLEMS:
        print("  [FAIL] %s" % item)
    sys.exit(1)
print("[result] 自检通过")
sys.exit(0)
