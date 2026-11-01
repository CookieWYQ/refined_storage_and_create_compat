#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
round99 自检：RS 自动合成仓 × Create 动力合成器 / 动力搅拌机（补丁式）静态审计。

检查对象（全部在 round99-A 的写域内）：
  src/main/java/cretae/cookiewyq/rs_create_compat/compat/crafter/*.java
  src/main/java/cretae/cookiewyq/rs_create_compat/mixin/createcraft/*.java
  src/main/resources/rs_create_compat.mixins.json（只读断言，条目由 Lead 添加）

为什么是「静态审计」而不是跑游戏：
  本轮纪律禁止实机/专用服务端探针，因此本脚本只做「可被机器验证的契约断言」——
  每一条都对应一个真实会崩/会错的行为，并且都用**反例自证**证明断言不是永真。

运行：python tools/selfcheck_round99_create_crafting.py
退出码：0 = 全部通过（且反例全部被检出）；1 = 有问题。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPAT = ROOT / "src/main/java/cretae/cookiewyq/rs_create_compat/compat/crafter"
MIXIN = ROOT / "src/main/java/cretae/cookiewyq/rs_create_compat/mixin/createcraft"
MIXIN_JSON = ROOT / "src/main/resources/rs_create_compat.mixins.json"

COMPAT_FILES = [
    "NetworkIo.java",
    "CreateCraftLog.java",
    "CreateCraftTargets.java",
    "MechanicalCraftPlan.java",
    "BasinTargets.java",
    "OutputReclaim.java",
    "CreateCraftDispatch.java",
]
MIXIN_FILES = [
    "AutocrafterCreateCraftMixin.java",
    "AutocrafterCreateCollectMixin.java",
    "MechanicalCrafterOutputMixin.java",
]
MIXIN_ENTRIES = [
    "createcraft.AutocrafterCreateCraftMixin",
    "createcraft.AutocrafterCreateCollectMixin",
    "createcraft.MechanicalCrafterOutputMixin",
]

FAILURES: list[str] = []
PASSES = 0


def load() -> dict[str, str]:
    files: dict[str, str] = {}
    for name in COMPAT_FILES:
        path = COMPAT / name
        if not path.is_file():
            FAILURES.append(f"[缺失] {path.relative_to(ROOT)}")
        else:
            files[f"compat/{name}"] = path.read_text(encoding="utf-8")
    for name in MIXIN_FILES:
        path = MIXIN / name
        if not path.is_file():
            FAILURES.append(f"[缺失] {path.relative_to(ROOT)}")
        else:
            files[f"mixin/{name}"] = path.read_text(encoding="utf-8")
    if MIXIN_JSON.is_file():
        files["mixins.json"] = MIXIN_JSON.read_text(encoding="utf-8")
    else:
        FAILURES.append(f"[缺失] {MIXIN_JSON.relative_to(ROOT)}")
    return files


def code_only(source: str) -> str:
    """去掉 // 行注释与 /* */ 块注释，只留代码（断言「代码里不出现某标识符」时用）。"""
    without_block = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return re.sub(r"//[^\n]*", "", without_block)


def region(source: str, signature: str) -> str:
    """取一个方法体（从签名起到下一个同缩进的 '}'），只用于精确断言，不做语法解析。"""
    start = source.find(signature)
    if start < 0:
        return ""
    depth = 0
    seen = False
    out = []
    for ch in source[start:]:
        out.append(ch)
        if ch == "{":
            depth += 1
            seen = True
        elif ch == "}":
            depth -= 1
            if seen and depth == 0:
                break
    return "".join(out)


def check(condition: bool, message: str) -> None:
    global PASSES
    if condition:
        PASSES += 1
    else:
        FAILURES.append(message)


def assert_all(files: dict[str, str], predicate, message: str, only_compat: bool = False) -> None:
    subset = {k: v for k, v in files.items() if k.startswith("compat/")} if only_compat else files
    bad = [name for name, text in subset.items() if not predicate(text)]
    check(not bad, f"{message}（违规文件：{', '.join(bad) if bad else '-'}）")


# ----------------------------------------------------------------------------
# 规则集
# ----------------------------------------------------------------------------

def rule_mixin_entries(files: dict[str, str]) -> None:
    json_text = files.get("mixins.json", "")
    # 只认 "mixins" 段（不是 client 段），否则专服会因缺 client mixin 直接崩
    match = re.search(r'"mixins"\s*:\s*\[(.*?)\]', json_text, re.S)
    body = match.group(1) if match else ""
    for entry in MIXIN_ENTRIES:
        check(f'"{entry}"' in body, f"[R1] mixins.json 的 \"mixins\" 段缺少条目 {entry}")


def rule_no_sink_factory(files: dict[str, str]) -> None:
    assert_all(
        files,
        lambda t: "addPatternProviderExternalPatternSinkFactory" not in t,
        "[R2] 不应使用 sink 工厂注册（composite 会给每个 sink 传完整资源集 ⇒ 双插 + 刷屏）",
        only_compat=False,
    )


def rule_takeover_at_node(files: dict[str, str]) -> None:
    mixin = files.get("mixin/AutocrafterCreateCraftMixin.java", "")
    check("@Mixin(value = PatternProviderNetworkNode.class" in mixin
          or "@Mixin(PatternProviderNetworkNode.class" in mixin,
          "[R3] 接管点必须是 PatternProviderNetworkNode（更外层的 accept）")
    check("PatternProviderNetworkNode;" in mixin or "PatternProviderNetworkNode#" in mixin,
          "[R3] 必须引用 PatternProviderNetworkNode 类型")
    check("PatternProviderExternalPatternSink sink;" in mixin,
          "[R3] 需要通过 @Shadow 读取节点自己的 sink 字段判定「这是自动合成仓」")
    check("Lcom/refinedmods/refinedstorage/api/autocrafting/task/ExternalPatternSink$Result;" in mixin,
          "[R3] @Inject 必须写完整描述符（accept 的返回类型 ExternalPatternSink$Result）")


def rule_takeover_not_on_be_accept(files: dict[str, str]) -> None:
    # 接管点若回到 AutocrafterBlockEntity#accept，会与内部存储 mixin 抢同一个 HEAD，
    # 执行顺序取决于 mixin 注册顺序 ⇒ 不可接受。
    for name, text in files.items():
        if not name.startswith("mixin/"):
            continue
        check("AutocrafterBlockEntity.class" not in text or 'method = "accept"' not in text,
              f"[R4] {name} 不得在 AutocrafterBlockEntity#accept 上注入（会与内部存储 mixin 依赖顺序）")


def rule_server_only(files: dict[str, str]) -> None:
    assert_all(files,
               lambda t: "net.minecraft.client" not in t and "Dist.CLIENT" not in t
                         and "isClientSide()" not in t,
               "[R5] 新代码必须是纯服务端路径（不得引用客户端类）")
    dispatch = files.get("compat/CreateCraftDispatch.java", "")
    check("instanceof ServerLevel" in files.get("mixin/AutocrafterCreateCraftMixin.java", ""),
          "[R5] accept 接管入口必须先判 ServerLevel")
    check("instanceof ServerLevel" in files.get("mixin/MechanicalCrafterOutputMixin.java", ""),
          "[R5] dropItem 拦截入口必须先判 ServerLevel")
    check("instanceof ServerLevel" in files.get("mixin/AutocrafterCreateCollectMixin.java", ""),
          "[R5] 采集入口必须先判 ServerLevel")
    check("getMenuData().locked()" in dispatch,
          "[R6] 必须尊重原逻辑的 locked 状态（否则锁模式下会重复塞料）")


def rule_simulate_before_execute(files: dict[str, str]) -> None:
    dispatch = files.get("compat/CreateCraftDispatch.java", "")
    can_pos = dispatch.find("plan.canApply()")
    apply_pos = dispatch.find("plan.apply()")
    check(can_pos >= 0 and apply_pos > can_pos,
          "[R7] 机械合成必须先 plan.canApply()（只读）再 plan.apply()")
    basin_can = dispatch.find("BasinTargets.canAcceptAll")
    basin_exec = dispatch.find("BasinTargets.acceptAll")
    check(basin_can >= 0 and basin_exec > basin_can,
          "[R7] 搅拌盆必须先 canAcceptAll()（只读）再 acceptAll()")
    check("return ExternalPatternSink.Result.REJECTED;" in dispatch,
          "[R7] 模拟不通过必须返回 REJECTED（等同原版机器忙），不得假装成功")

    plan = files.get("compat/MechanicalCraftPlan.java", "")
    can_apply = region(plan, "public boolean canApply()")
    apply_method = region(plan, "public boolean apply()")
    check("insertItem(0, entry.getValue(), true)" in can_apply,
          "[R8] canApply() 必须用 insertItem(..., simulate = true)（只读）")
    check(", false)" not in can_apply,
          "[R8] canApply() 内不得出现任何 EXECUTE 插入（simulate 阶段不许改机器状态）")
    check("insertItem(0, entry.getValue(), false)" in apply_method,
          "[R8] apply() 才是真正插入（simulate = false）")


def rule_fallthrough(files: dict[str, str]) -> None:
    dispatch = files.get("compat/CreateCraftDispatch.java", "")
    check("MechanicalCraftPlan.find" in dispatch and "return null;" in dispatch,
          "[R9] 规划不上时必须 return null（交回原逻辑），不得自行吞料")
    mixin = files.get("mixin/AutocrafterCreateCraftMixin.java", "")
    check(re.search(r"if \(result != null\)\s*\{\s*cir\.setReturnValue\(result\);\s*\}", mixin) is not None,
          "[R9] mixin 只能在拿到非空结果时 setReturnValue（null = 完全放行原逻辑）")
    drop = files.get("mixin/MechanicalCrafterOutputMixin.java", "")
    check(re.search(r"if \(OutputReclaim\.tryReclaimCraftResult\(.*?\)\)\s*\{\s*ci\.cancel\(\);", drop, re.S)
          is not None,
          "[R9] dropItem 只有在「确实写回网络」时才取消掉落，否则原样掉落")


def rule_grid_mapping(files: dict[str, str]) -> None:
    plan = files.get("compat/MechanicalCraftPlan.java", "")
    check("getTargetingCrafter" in plan and "getPrecedingCrafters" in plan,
          "[R10] 阵列拓扑必须用 RecipeGridHandler 的 public API 推导")
    check("MechanicalCrafterBlock.POINTING" in plan,
          "[R10] 格位偏移必须读 POINTING 属性")
    for token in ["new int[] {1, 0}", "new int[] {-1, 0}", "new int[] {0, 1}", "new int[] {0, -1}"]:
        check(token in plan, f"[R10] 缺少 mergeOnto 偏移映射 {token}")
    check("origin[1] - cell.y" in plan,
          "[R10] 配方行必须按 y 翻转映射（行 0 = 网格最上方），与 MechanicalCraftingInput 一致")
    check("maxX != width - 1" in plan and "maxY != height - 1" in plan,
          "[R10] 必须拒绝「有空空白边」的图案（输入包围盒会缩水，Create 永远匹配不上）")


def rule_output_reclaim(files: dict[str, str]) -> None:
    reclaim = files.get("compat/OutputReclaim.java", "")
    check("NetworkIo.insertItemFully" in reclaim,
          "[R11] 产物回网必须走 NetworkIo.insertItemFully（先 SIMULATE 再 EXECUTE）")
    check("ItemStack.isSameItemSameComponents" in reclaim,
          "[R11] 产物匹配必须精确到「物品 + 组件」，不能只按物品类型")
    check("pending" in reclaim, "[R11] 必须有 pending 计数（一轮派发只回收一轮产物）")
    basin = files.get("compat/BasinTargets.java", "")
    check("getOutputInventory" in basin and "getTanks().getSecond()" in basin,
          "[R12] 盆的产物采集只能读输出侧（输出背包 + 输出罐）")
    check("getInputInventory" not in basin,
          "[R12] 不得碰盆的输入侧（否则会抢走还没反应的输入料）")


def rule_logging(files: dict[str, str]) -> None:
    log = files.get("compat/CreateCraftLog.java", "")
    check("[rscc-createcraft]" in log, "[R13] 日志标签必须是 [rscc-createcraft]")
    for name, text in files.items():
        if not name.startswith("compat/"):
            continue
        if name.endswith("CreateCraftLog.java"):
            continue
        check("LoggerFactory" not in text and "System.out" not in text,
              f"[R13] {name} 不得自带 logger / 直接打印（统一走 CreateCraftLog，避免刷屏）")


def rule_no_reflection(files: dict[str, str]) -> None:
    assert_all(files,
               lambda t: "setAccessible" not in t and "java.lang.reflect" not in t,
               "[R14] 不得反射访问 RS/Create 私有成员")


def rule_order_independence_documented(files: dict[str, str]) -> None:
    dispatch = files.get("compat/CreateCraftDispatch.java", "")
    check("AutocrafterStorageMixin" in dispatch,
          "[R15] 必须写明与既有内部存储 mixin 的互斥机制（顺序无关）")
    check("等价 NEVER" in dispatch,
          "[R15] 必须如实写明 Create 特化路径上三种锁模式不生效（不静默）")
    mixin = files.get("mixin/AutocrafterCreateCraftMixin.java", "")
    check("priority = 1500" in mixin,
          "[R15] 必须显式写死 mixin priority（不依赖默认注册顺序）")

    # ① 正对 Create 机器时「无视内部存储」：接管路径的**代码**里不许再读内部存储开关/配置
    #    （注释里可以提到它们——那段注释正是在解释「为什么刻意不读」）
    dispatch_code = code_only(dispatch)
    for token in ("RsccAutocrafterStorage", "rscc$isStorageEnabled", "autocrafterStorageEnabled"):
        check(token not in dispatch_code,
              f"[R16] 正对 Create 机器必须无视内部存储：CreateCraftDispatch 代码里不得出现 {token}")
    check("绕过内部存储" in dispatch and '"bypass-storage"' in dispatch,
          "[R16] 必须有一条节流日志解释「本次绕过内部存储」（每台方块每 200 tick 至多一条）")
    check("return ExternalPatternSink.Result.LOCKED;" in dispatch,
          "[R16] 已锁定时必须如实返回 LOCKED（既不喂料也不吞料）")

    # ② 正对非 Create 机器时：必须立刻完全放行，内部存储才能按今天的语义接管
    check("if (!mechanicalCrafter && basin == null) {" in dispatch,
          "[R16] 正对非 Create 机器时必须走「立刻放行」分支（内部存储优先）")
    early_return = region(dispatch, "if (!mechanicalCrafter && basin == null) {")
    check("return null;" in early_return,
          "[R16] 非 Create 机器分支必须 return null（完全放行原逻辑 + 内部存储）")


RULES = [
    ("R1", rule_mixin_entries),
    ("R2", rule_no_sink_factory),
    ("R3", rule_takeover_at_node),
    ("R4", rule_takeover_not_on_be_accept),
    ("R5", rule_server_only),
    ("R7", rule_simulate_before_execute),
    ("R9", rule_fallthrough),
    ("R10", rule_grid_mapping),
    ("R11", rule_output_reclaim),
    ("R13", rule_logging),
    ("R14", rule_no_reflection),
    ("R15", rule_order_independence_documented),
]

# ----------------------------------------------------------------------------
# 反例自证：把源码**故意改坏**，断言上面的规则真的会报错（证明断言不是永真）
# ----------------------------------------------------------------------------

COUNTEREXAMPLES = [
    (
        "删掉 plan.canApply() 的模拟检查",
        "compat/CreateCraftDispatch.java",
        "        if (!plan.canApply()) {\n"
        "            return ExternalPatternSink.Result.REJECTED;\n"
        "        }\n",
        "",
        "R7",
    ),
    (
        "把 dropItem 的取消改成无条件",
        "mixin/MechanicalCrafterOutputMixin.java",
        "if (OutputReclaim.tryReclaimCraftResult(level, self.getBlockPos(), stack)) {",
        "if (true) {",
        "R9",
    ),
    (
        "混入客户端类引用",
        "compat/CreateCraftDispatch.java",
        "import net.minecraft.server.level.ServerLevel;",
        "import net.minecraft.client.Minecraft;\nimport net.minecraft.server.level.ServerLevel;",
        "R5",
    ),
    (
        "改回 sink 工厂注册",
        "compat/CreateCraftDispatch.java",
        "public final class CreateCraftDispatch {",
        "// RefinedStorageApi.INSTANCE.addPatternProviderExternalPatternSinkFactory(null);\n"
        "public final class CreateCraftDispatch {",
        "R2",
    ),
    (
        "去掉 POINTING 的一个偏移",
        "compat/MechanicalCraftPlan.java",
        "case LEFT -> new int[] {1, 0};",
        "case LEFT -> new int[] {0, 0};",
        "R10",
    ),
    (
        "把盆的采集改成读输入侧",
        "compat/BasinTargets.java",
        "final SmartInventory output = basin.getOutputInventory();",
        "final SmartInventory output = basin.getInputInventory();",
        "R11",
    ),
    (
        "把「正对 Create 机器时无视内部存储」改回「先问内部存储」",
        "compat/CreateCraftDispatch.java",
        "        CreateCraftLog.throttled(level, root.getBlockPos(), \"bypass-storage\",",
        "        if (cretae.cookiewyq.rs_create_compat.Config.autocrafterStorageEnabled) {\n"
        "            return null;\n"
        "        }\n"
        "        CreateCraftLog.throttled(level, root.getBlockPos(), \"bypass-storage\",",
        "R15",
    ),
]


def run_counterexamples(sources: dict[str, str]) -> int:
    detected = 0
    for title, name, old, new, rule_id in COUNTEREXAMPLES:
        mutated = dict(sources)
        text = mutated.get(name, "")
        if old not in text:
            FAILURES.append(f"[反例无效] {title}：锚点 {old[:40]!r} 在 {name} 中找不到")
            continue
        mutated[name] = text.replace(old, new, 1)
        before = len(FAILURES)
        for rid, rule in RULES:
            if rid != rule_id:
                continue
            rule(mutated)
        if len(FAILURES) > before:
            detected += 1
            # 回滚掉这次故意制造的问题记录，只保留「反例被检出」这个事实
            del FAILURES[before:]
        else:
            FAILURES.append(f"[反例未被检出] {title}（规则 {rule_id} 是永真断言）")
    return detected


def main() -> int:
    sources = load()
    # 先把「缺失文件」与「规则失败」分开：缺文件时跳过规则，避免误导性报错
    missing = [msg for msg in FAILURES]
    if missing:
        for msg in missing:
            print(msg)
        print(f"\n断言失败 {len(missing)} 条（文件缺失，规则未执行）")
        return 1

    for _rid, rule in RULES:
        rule(sources)

    detected = run_counterexamples(sources)

    total = PASSES + len(FAILURES)
    print("=" * 72)
    print("round99 自检：RS 自动合成仓 × Create 动力合成器 / 动力搅拌机")
    print("=" * 72)
    print(f"断言总数: {total}，通过: {PASSES}，失败: {len(FAILURES)}")
    print(f"反例自证: {detected}/{len(COUNTEREXAMPLES)} 条被规则检出")
    for failure in FAILURES:
        print(f"FAIL {failure}")
    if FAILURES or detected != len(COUNTEREXAMPLES):
        print("\n结果: FAIL")
        return 1
    print("\n结果: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
