# -*- coding: utf-8 -*-
"""自检（第 31 轮 · 缺口 2）：reachable=0 的「延长型已断开」提示与它的边沿语义。

用法：python tools/selfcheck_round31_bus_link_lost.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户现场（缺陷）
----------------
玩家用「分隔框架」+「扳手断开」把线缆隔开，本来是让总线归属唯一的手段；一旦断错地方
（断在总线与执行舱之间），线缆就<b>一台执行舱都够不到</b>。此时判定按既有语义只能返回 CLEAR ——
这恰好也是「这台总线本来就不是给执行仓用的」的同一个返回值，于是玩家<b>什么提示都收不到</b>，
只看到总线莫名其妙退回普通界面（实证日志：`reason=no_chamber reachable=0`）。

本自检钉住四件事
----------------
K1 判定语义不变：Report.CLEAR / inspect 的返回条件与「≥2 台才停用」原样；
K2 边沿判据 = 「本实例亲眼见过可达 ≥ 1」这条历史事实（没连过的总线永不提示）；
K3 边沿去重：0 持续期间只提示一次；恢复可达后复位，再次掉到 0 可以再提示一次；
   首个样本不算翻转（载入 / 放置本身不弹）；
K4 两侧（输出 / 输入总线）共用同一份实现，且横幅走既有完成横幅系统 + 语言键成对。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ======================================================================
# 与 Java 逐字同构的边沿状态机（LinkLostLatch#onResolve 的复刻）
# ======================================================================
class Latch(object):
    def __init__(self):
        self.baseline = False
        self.ever_linked = False
        self.announced = False

    def on_resolve(self, reachable_count):
        linked = reachable_count >= 1
        if not self.baseline:
            self.baseline = True
            self.ever_linked = linked
            self.announced = not linked
            return False
        if linked:
            self.ever_linked = True
            self.announced = False
            return False
        if not self.ever_linked or self.announced:
            return False
        self.announced = True
        return True


def fires(seq):
    """把一串可达台数喂进去，返回每一次是否提示。"""
    latch = Latch()
    return [latch.on_resolve(n) for n in seq]


def main():
    interference = read(SRC, "support", "RsccBusInterference.java")
    exporter = read(SRC, "mixin", "exporter", "AbstractExporterBlockEntityMixin.java")
    importer = read(SRC, "mixin", "importer", "AbstractImporterBlockEntityMixin.java")
    banner = read(SRC, "report", "RsccBusDisabledBanner.java")
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))

    # ---------------- K1 判定语义不变 ----------------
    section("K1 既有判定语义一字未动（不得为了提示而改判定）")
    check("inspect 仍然：一台都够不到 → Report.CLEAR（不新增判定分支）",
          "if (!walk.linked()) {" in interference
          and "return Report.CLEAR;" in interference
          and "walk.ambiguous()" in interference)
    check("Report.CLEAR 仍是同一条「可达 0 台、不 disabled」的实例",
          "public static final Report CLEAR = new Report(false, false, 0, List.of(), List.of());"
          in interference.replace("        ", " ").replace("    ", " ")
          or "Report CLEAR = new Report(false, false, 0, List.of(), List.of())" in interference)
    check("「≥2 台 / 探查不穷尽才停用」的判据仍在唯一实现里",
          "public boolean ambiguous() {" in read(SRC, "support", "RsccWireLinkSearch.java"))

    # ---------------- K2 边沿判据 ----------------
    section("K2 判据 = 「本实例亲眼见过可达 ≥ 1」（历史事实，不误报）")
    check("LinkLostLatch 存在，且注释写明判据 / 不误报 / 不重复弹",
          "public static final class LinkLostLatch" in interference
          and "不误报" in interference
          and "不重复弹" in interference)
    check("『见过 ≥ 1 台』这一历史事实由 everLinked 承载，且恢复时置真",
          "everLinked = linked;" in interference and "everLinked = true;" in interference)
    check("『从没连过』直接不提示（!everLinked 收口）",
          "if (!everLinked || announced) {" in interference
          and "return false; // 从没连过（不误报）/ 这一轮已经提示过（不重复弹）" in interference)

    # ---------------- K3 边沿语义（真值表） ----------------
    section("K3 边沿语义真值表（与 Java 同构复刻）")
    check("首个样本就是 0：不提示（载入 / 放置本身不算翻转）",
          fires([0, 0, 0]) == [False, False, False], str(fires([0, 0, 0])))
    check("首个样本就是 1：也不提示（基线只是记录）",
          fires([1]) == [False], str(fires([1])))
    check("1 → 0：提示一次；0 持续：不再提示",
          fires([1, 0, 0, 0]) == [False, True, False, False], str(fires([1, 0, 0, 0])))
    check("恢复为可达后复位：再次 1 → 0 可以再提示一次",
          fires([1, 0, 1, 0]) == [False, True, False, True], str(fires([1, 0, 1, 0])))
    check("从未连过（一直 0）：永远不提示",
          fires([0, 0, 0, 0]) == [False, False, False, False])
    check("≥2 台也算「连过」：2 → 0 同样提示（停用与断开都能被看到）",
          fires([2, 0]) == [False, True], str(fires([2, 0])))
    check("Java 源码含同一条翻转判据（三条分支齐全）",
          "if (!baseline) {" in interference
          and "if (linked) {" in interference
          and "if (!everLinked || announced) {" in interference)

    # ---------------- K4 两侧接入 + 横幅出口 ----------------
    section("K4 输出 / 输入总线共用同一实现，提示走既有横幅系统")
    check("输出总线持有 LinkLostLatch",
          "private final RsccBusInterference.LinkLostLatch rscc$linkLostLatch" in exporter)
    check("输入总线持有 LinkLostLatch",
          "private final RsccBusInterference.LinkLostLatch rscc$linkLostLatch" in importer)
    check("两侧都在归属状态机里调用 rscc$announceLinkLost()",
          "rscc$announceLinkLost();" in exporter and "rscc$announceLinkLost();" in importer)
    check("两侧只在 latch 返回 true 时才发横幅",
          "if (!rscc$linkLostLatch.onResolve(rscc$linkReport.reachableCount())) {" in exporter
          and "if (!rscc$linkLostLatch.onResolve(rscc$linkReport.reachableCount())) {" in importer)
    check("两侧都发自己那一侧的说法（exporter=true / importer=false）",
          "RsccBusInterference.notifyLinkLost(serverLevel, self.getBlockPos(), true);" in exporter
          and "RsccBusInterference.notifyLinkLost(serverLevel, self.getBlockPos(), false);" in importer)
    check("既有「归属未确定」横幅的边沿语义逐字保留（首个解析只记基线 / 只在翻转时弹 / 恢复不弹）",
          "if (!rscc$linkBaselineRecorded) {" in exporter
          and "if (ambiguous != rscc$announcedAmbiguous) {" in exporter
          and "if (ambiguous != rscc$announcedAmbiguous) {" in importer
          and "RsccBusDisabledBanner.send(serverLevel, self.getBlockPos(), true," in exporter
          and "RsccBusDisabledBanner.send(serverLevel, self.getBlockPos(), false," in importer)
    check("「搜索重入」不再污染归属报告（防止凭空点亮两条边沿提示）",
          "if (RsccSearchGuard.isSearching()) {" in exporter
          and "if (RsccSearchGuard.isSearching()) {" in importer)
    check("notifyLinkLost 走既有完成横幅出口 + 同前缀语言键 + 诊断留痕",
          "CompatCompletionSender.sendToNearby(level, busPos, LOST_RADIUS_SQ, rows);" in interference
          and "gui.rs_create_compat.bus_interference." in interference
          and 'RsccDiag.recordBanner(exporter ? "bus_link_lost_exporter" : "bus_link_lost_importer"' in interference)
    check("与既有横幅共用同一段键前缀（键集合不分裂）",
          'private static final String LANG = "gui.rs_create_compat.bus_interference.";' in banner
          and 'private static final String LANG = "gui.rs_create_compat.bus_interference.";' in interference)

    # ---------------- 语言键 ----------------
    section("语言键：成对 + 中文长度 + 硬要求文案")
    keys = ["gui.rs_create_compat.bus_interference.lost.title",
            "gui.rs_create_compat.bus_interference.lost.reason",
            "gui.rs_create_compat.bus_interference.lost.hint"]
    for key in keys:
        check("zh/en 都有 %s" % key, key in zh and key in en)
    check("中文都不超过 40 字符（最长 %d）"
          % max(len(zh[k]) for k in keys),
          all(len(zh[k]) <= 40 for k in keys))
    check("文案不写「或」、不写「等 N 种」、不写「图标轮换」",
          all(("或" not in zh[k]) and ("等" not in zh[k]) and ("图标轮换" not in zh[k]) for k in keys))
    check("「哪条总线」复用既有键（不新增重复键）",
          "banner.toast.exporter" in interference and "banner.toast.importer" in interference)
    check("提示里说清了事实与下一步（够不到执行舱 / 检查接缝）",
          "够不到执行舱" in zh[keys[1]] and "接缝" in zh[keys[2]])

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
