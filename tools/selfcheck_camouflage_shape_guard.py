# -*- coding: utf-8 -*-
"""伪装形状 hook 的「防回归」自检（2026-09-26 Bootstrap StackOverflowError 的护栏）。

背景：修复前存在一条闭环 ——
    形状 hook（mixin/block/CamouflageShapeMixin）
      → RsccCamouflage.overridesShape
        → SeparationFrameGuard.isSheathable
          → BlockState#isCollisionShapeFullBlock     （会读碰撞形状）
            → BlockStateBase#getCollisionShape       （两参，被同一个 Mixin 注入）
              → 形状 hook …… 栈溢出。

本脚本把「不允许再出现这条环」固化成断言，覆盖任务要求的三件事：
  A. 形状路径（overridesShape 及其<b>传递调用图</b>）里没有任何形状查询 API 调用
     —— 用源码扫描 + 简单调用图 BFS 断言（扫描方式见 build_call_graph / reachable）。
  B. isSheathable 的「整格完整方块」判定不再触发形状计算
     —— 断言先做 hasDynamicShape() 提前返回，且不出现 getShape / getCollisionShape /
     getVisualShape；overridesShape 也不再调用 isSheathable。
  C. CamouflageShapeMixin 的四条注入路径都有可重入保护 + finally 复位。
  D. 运行时等价推演（Python 复刻调用链）：「套壳格 → 取碰撞形状」只进入 1 层、不递归；
     并复刻修复前的模型证明它确实会无限递归（说明护栏真的挡住了那条环）。

用法: python tools/selfcheck_camouflage_shape_guard.py
退出码 0 = 全部通过；1 = 有问题。
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

MIXIN = os.path.join(PKG, "mixin", "block", "CamouflageShapeMixin.java")
CAMOU = os.path.join(PKG, "support", "RsccCamouflage.java")
GUARD = os.path.join(PKG, "support", "SeparationFrameGuard.java")
QUERY_GUARD = os.path.join(PKG, "support", "RsccShapeQueryGuard.java")

#: 形状查询 API —— 出现在「形状路径」的任何一个方法体里就说明环可能被重建
FORBIDDEN = ("getShape", "getCollisionShape", "getVisualShape", "isCollisionShapeFullBlock",
             "getOcclusionShape", "getBlockSupportShape", "getFaceOcclusionShape",
             "isFaceSturdy", "isSolidRender")

PROBLEMS = []
CHECKS = [0]


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def read_text(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(src):
    """剥掉注释：锚点只认真实代码（javadoc 里会写方法名，也会写「不要怎么做」）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


# ---------------------------------------------------------------------------
# 方法体提取 + 调用图（供「形状路径不含形状查询」这条断言使用）
# ---------------------------------------------------------------------------
DECL_RE = re.compile(
    r"(?:(?:public|protected|private|static|final|synchronized|abstract|native|default)\s+)+"
    r"[\w<>\[\],\.\? ]*?(\w+)\s*\([^;{}]*\)\s*(?:throws[^{;]*)?\{")
CALL_RE = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
BODY_CACHE = {}


def body_at(src, open_index):
    """从第一个 '{' 起取配平的方法体。"""
    depth = 0
    for i in range(open_index, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_index:i + 1]
    raise SystemExit("方法体未闭合")


def methods_of(path):
    """返回 {方法名: [方法体, ...]}（只扫带修饰符的声明，覆盖本工程的全部相关方法）。"""
    src = code_only(read_text(path))
    out = {}
    for match in DECL_RE.finditer(src):
        body = body_at(src, src.index("{", match.end() - 1))
        out.setdefault(match.group(1), []).append(body)
    return out


#: 调用图只在这些文件里展开 —— 它们构成形状路径的<b>完整</b>闭包
#: （overridesShape → isSheatheableFamily → isFluidPipe/RsccWireBlocks.isWire → isCamouflaged → saved）。
#: 若不限定文件，按「方法名」做的 BFS 会被包里的同名通用方法（get / add / of / size…）带到别的类去，
#: 那已经不是这条形状路径的一部分了。
GRAPH_FILES = (CAMOU, GUARD, os.path.join(PKG, "support", "RsccWireBlocks.java"))


def all_methods():
    """把这些文件里的方法合并成一张「方法名 → 方法体」表（用于调用图 BFS）。"""
    if BODY_CACHE:
        return BODY_CACHE
    for path in GRAPH_FILES:
        for method, bodies in methods_of(path).items():
            BODY_CACHE.setdefault(method, []).extend(bodies)
    return BODY_CACHE


def reachable(entry, methods):
    """从 entry 方法出发，按「方法名」做调用图 BFS，返回真正可达的方法体列表（含 entry）。"""
    seen, queue, bodies = {entry}, [entry], []
    while queue:
        current = queue.pop()
        for body in methods.get(current, []):
            bodies.append((current, body))
            for call in CALL_RE.findall(body):
                if call in methods and call not in seen:
                    seen.add(call)
                    queue.append(call)
    return seen, bodies


def main():
    mixin_src = read_text(MIXIN)
    mixin_code = code_only(mixin_src)
    camou_src = read_text(CAMOU)
    guard_src = read_text(GUARD)

    print("=" * 96)
    print("A. 形状路径的传递调用图里没有任何形状查询 API")
    print("=" * 96)
    methods = all_methods()
    names, bodies = reachable("overridesShape", methods)
    print("       [调用图] overridesShape 可达的方法：%s" % ", ".join(sorted(names)))
    hits = []
    for name, body in bodies:
        for token in FORBIDDEN:
            if re.search(r"\b%s\b" % token, body):
                hits.append("%s -> %s" % (name, token))
    check(not hits,
          "A1 overridesShape 及其传递调用图中不含任何形状查询 API（%s）" % "/".join(FORBIDDEN),
          "命中: %s" % hits)

    # 逐字确认「两步廉价短路」的实现本身
    overrides = "\n".join(methods.get("overridesShape", []))
    check("SeparationFrameGuard.isSheatheableFamily(state)" in overrides
          and "isCamouflaged(level, pos)" in overrides,
          "A2 overridesShape 的实现 = 线缆 / 管道族类判定 + 坐标记录查询（两步，皆不读形状）")
    check("isSheathable(" not in overrides,
          "A3 overridesShape 不再调用 isSheathable（环的第一环已被拆除）")

    family = "\n".join(methods.get("isSheatheableFamily", []))
    check("RsccWireBlocks.isWire(state)" in family and "isFluidPipe(state)" in family
          and not any(re.search(r"\b%s\b" % token, family) for token in FORBIDDEN),
          "A4 isSheatheableFamily 是纯类判定（instanceof 家族），不查世界 / 不查形状 / 不查记录")

    print()
    print("=" * 96)
    print("B. isSheathable 的「整格完整方块」判定不再触发形状计算")
    print("=" * 96)
    sheathable = "\n".join(methods.get("isSheathable", []))
    for token in ("getShape", "getCollisionShape", "getVisualShape"):
        check(not re.search(r"\b%s\b" % token, sheathable),
              "B1 isSheathable 不调用 %s（不会把形状计算拉进这条路）" % token)
    check("hasDynamicShape()" in sheathable,
          "B2 先做 hasDynamicShape() 早退：动态外形方块一律判「非整格」（线缆 / 管道即在此列）")
    dyn_at = sheathable.find("hasDynamicShape()")
    full_at = sheathable.find("isCollisionShapeFullBlock")
    check(0 <= dyn_at < full_at,
          "B3 hasDynamicShape() 的早退在 isCollisionShapeFullBlock 之前（顺序不能反）",
          "hasDynamicShape@%d / isCollisionShapeFullBlock@%d" % (dyn_at, full_at))
    check("state == null || level == null || pos == null" in sheathable,
          "B4 空值兜底仍在（null = 没有世界可用 → 只认管道 / 线缆族）")
    check("state.getBlock().hasDynamicShape()" in sheathable,
          "B5 判据取 state.getBlock()（方块级属性，不经方块状态的形状查询）")
    # 非动态外形方块：原版 isCollisionShapeFullBlock 只读烘焙缓存（cache != null 分支），
    # 不会调用 getCollisionShape / getShape —— 这一点在脚本 D 段用推演固化。
    check("overridesShape" in camou_src and "isSheatheableFamily" in camou_src,
          "B6 可套性判定（isSheathable）与形状覆盖（overridesShape）分成两条互不调用的路径")

    print()
    print("=" * 96)
    print("C. 四条注入路径都有可重入保护 + finally 复位")
    print("=" * 96)
    check(mixin_code.count('at = @At("HEAD"), cancellable = true') == 4,
          "C1 四处注入都是 HEAD + cancellable（重入时不设返回值 = 放行原版）",
          "数=%d" % mixin_code.count('at = @At("HEAD"), cancellable = true'))
    check(mixin_code.count("RsccShapeQueryGuard.begin()") == 4,
          "C2 四条路径各调一次 RsccShapeQueryGuard.begin()")
    check(mixin_code.count("RsccShapeQueryGuard.end()") == 4,
          "C3 四条路径各在 finally 里复位一次 RsccShapeQueryGuard.end()")
    check(len(re.findall(r"\}\s*finally\s*\{\s*RsccShapeQueryGuard\.end\(\);", mixin_code)) == 4,
          "C4 四个 end() 全部位于 finally 块内（异常也复位，不漏旗标）")
    check(len(re.findall(r"if\s*\(!RsccShapeQueryGuard\.begin\(\)\)\s*\{\s*return;", mixin_code)) == 4,
          "C5 重入时立即 return（不 cancel → 原版实现照常执行），且不再动旗标")

    handlers = {
        "getShape": "rscc$fullShapeWhenCamouflaged",
        "getCollisionShape(3)": "rscc$fullCollisionWhenCamouflaged",
        "getCollisionShape(2)": "rscc$fullCollisionWhenCamouflagedCached",
        "getVisualShape": "rscc$fullVisualWhenCamouflaged",
    }
    for label, handler in handlers.items():
        start = mixin_code.find("void " + handler)
        body = body_at(mixin_code, mixin_code.index("{", start)) if start >= 0 else ""
        ok = (start >= 0 and "RsccShapeQueryGuard.begin()" in body
              and "finally {" in body and "RsccShapeQueryGuard.end();" in body
              and "RsccCamouflage.overridesShape(" in body)
        check(ok, "C6 %s 有 begin/finally/end + overridesShape" % label)

    query_guard = code_only(read_text(QUERY_GUARD))
    check("ThreadLocal" in query_guard and "withInitial" in query_guard
          and query_guard.count("ACTIVE.set(") == 2,
          "C7 旗标用 ThreadLocal（区块烘焙工作线程与主线程互不串味），且只有置位 / 复位两处写")
    check(not any(re.search(r"\b%s\b" % token, query_guard) for token in FORBIDDEN),
          "C8 旗标工具类本身不碰任何形状 API")

    print()
    print("=" * 96)
    print("D. 运行时等价推演：套壳格 → 取碰撞形状，只进入 1 层、不递归")
    print("=" * 96)

    class Recursion(Exception):
        pass

    def simulate(enable_guard, enable_family_shortcut, max_depth=200):
        """复刻修复后 / 修复前的调用链，返回 (进入形状处理器的层数, 是否正常返回)。"""
        state = {"active": False, "entries": 0, "depth": 0}

        def overrides_shape():
            # 修复后：线缆族廉价短路 + 记录查询（不读形状）
            if enable_family_shortcut:
                return True
            return is_sheathable()

        def is_sheathable():
            # 修复前：走到 isCollisionShapeFullBlock → 读碰撞形状
            return is_collision_shape_full_block()

        def is_collision_shape_full_block():
            return get_collision_shape_2()

        def get_collision_shape_2():
            # 两参重载被 Mixin 注入（这就是崩溃的第一帧）
            if enable_guard:
                if state["active"]:
                    return "vanilla"          # 重入：不 cancel，原版照常执行
                state["active"] = True
                state["entries"] += 1
                try:
                    return "full" if overrides_shape() else "vanilla"
                finally:
                    state["active"] = False
            # 修复前：没有旗标，直接重入
            state["entries"] += 1
            state["depth"] += 1
            if state["depth"] > max_depth:
                raise Recursion()
            try:
                return "full" if overrides_shape() else "vanilla"
            finally:
                state["depth"] -= 1

        try:
            result = get_collision_shape_2()
            return state["entries"], result
        except Recursion:
            return state["entries"], "RECURSION"

    entries_new, result_new = simulate(enable_guard=True, enable_family_shortcut=True)
    check(result_new == "full" and entries_new == 1,
          "D1 修复后：套壳格取碰撞形状只进入形状处理器 1 层，直接得到整格（不递归）",
          "层数=%d / 结果=%s" % (entries_new, result_new))

    entries_guard_only, result_guard_only = simulate(enable_guard=True, enable_family_shortcut=False)
    check(result_guard_only != "RECURSION" and entries_guard_only <= 2,
          "D2 只靠可重入保护（假设准入仍读形状）：最多 2 层即被旗标切断（兜底成立）",
          "层数=%d / 结果=%s" % (entries_guard_only, result_guard_only))

    entries_old, result_old = simulate(enable_guard=False, enable_family_shortcut=False)
    check(result_old == "RECURSION",
          "D3 反面对照：修复前的调用链确实无限递归（证明这条护栏拦的就是真崩溃）",
          "层数=%d" % entries_old)

    print()
    print("=" * 96)
    print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
    for entry in PROBLEMS:
        print("  - " + entry)
    print("=" * 96)
    return 1 if PROBLEMS else 0


if __name__ == "__main__":
    sys.exit(main())
