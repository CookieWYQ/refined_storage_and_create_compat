# -*- coding: utf-8 -*-
"""自检（第 31 轮 · 缺口 1）：蓝图导出时的「本区有一部分不会跟着蓝图走」提示。

用法：python tools/selfcheck_round31_schematic_notice.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户现场（缺陷）
----------------
「分隔框架」与「扳手断开」都只存在本模组的 SaveData 里（不占方块），而 Create 的蓝图只写
StructureTemplate（方块 + 方块实体）—— 导出 → 粘贴之后两者全丢：两条线缆重新连通，总线归属
变成「≥2 台可达」而被停用。玩家原话：「我明明放了框架，它却说不行」。

本轮选择方案 b（方案 a 的否决理由写在 mixin 的类注释里），因此本自检钉住：
L1 挂点是 Create 导出蓝图的唯一出口 SchematicExport#saveSchematic，且只在客户端那次调用提示；
L2 三条收口：导出失败不提示 / 非客户端不提示 / 两种记录都为 0 一个字都不弹；
L3 数量取自权威真值（RsccSheaths / RsccCableCuts 的包围盒计数），静止的口径写清（接缝算任意一端）；
L4 文案走语言键、zh/en 成对、中文 ≤ 40 字符、不写「或」、不写「等 N 种」、不写「图标轮换」。
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
RES = os.path.join(ROOT, "src", "main", "resources")
LANG_DIR = os.path.join(RES, "assets", "rs_create_compat", "lang")

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


def rows_for(sheaths, cuts):
    """复刻 mixin 里「该画哪几行」的判据（返回行标识列表）。"""
    if sheaths <= 0 and cuts <= 0:
        return []
    rows = ["title"]
    if sheaths > 0:
        rows.append("sheaths")
    if cuts > 0:
        rows.append("cuts")
    rows.append("hint")
    return rows


def main():
    mixin = read(SRC, "mixin", "client", "SchematicExportNoticeMixin.java")
    mixins_json = read(RES, "rs_create_compat.mixins.json")
    sheaths = read(SRC, "support", "RsccSheaths.java")
    cuts = read(SRC, "support", "RsccCableCuts.java")
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))

    # ---------------- L1 挂点 ----------------
    section("L1 挂点 = 蓝图导出的唯一出口，且只在客户端那次提示")
    check("mixin 目标就是 Create 的导出实现 SchematicExport",
          "@Mixin(SchematicExport.class)" in mixin)
    check("注入点是 saveSchematic 的 RETURN（拿到返回值 = 知道导出成没成）",
          '@Inject(method = "saveSchematic", at = @At("RETURN"))' in mixin)
    check("处理静态目标的注入方法也是 static（否则 Mixin 直接加载失败）",
          "private static void rscc$notifyCutsAndSheathsNotCopied(" in mixin)
    check("已登记进 mixins.json 的 client 列表（专服不会加载客户端类）",
          '"client.SchematicExportNoticeMixin"' in mixins_json
          and mixins_json.index('"client"') < mixins_json.index('"client.SchematicExportNoticeMixin"'))
    check("单人存档里服务端那次「立即转换」导出不再重复提示",
          "if (!level.isClientSide()) {" in mixin and "只提示一次" in mixin)
    check("借既有完成横幅 Toast 呈现（不新造一套提示）",
          "new CompatCompletionToast(rows)" in mixin
          and "CompletionBannerPayload.Row.text(" in mixin)

    # ---------------- L2 三条收口 ----------------
    section("L2 三条收口：失败不弹 / 非客户端不弹 / 都没东西不弹")
    check("导出失败（返回 null）不提示",
          "if (cir.getReturnValue() == null || level == null || first == null || second == null) {" in mixin)
    check("两种记录都为 0 时一个字都不弹（普通蓝图零打扰）",
          "if (sheaths <= 0 && cuts <= 0) {" in mixin)
    check("行级也各自收口（只有套壳就只写套壳那一行）",
          "if (sheaths > 0) {" in mixin and "if (cuts > 0) {" in mixin)
    check("选择区换算成包围盒（与导出的那两个角同源）",
          "BoundingBox.fromCorners(first, second)" in mixin)
    check("行数真值表：()=%s，套壳=title/sheaths/hint，断开=title/cuts/hint，两者=四行"
          % rows_for(0, 0),
          rows_for(0, 0) == []
          and rows_for(3, 0) == ["title", "sheaths", "hint"]
          and rows_for(0, 5) == ["title", "cuts", "hint"]
          and rows_for(3, 5) == ["title", "sheaths", "cuts", "hint"])

    # ---------------- L3 数量真值 ----------------
    section("L3 数量取自权威真值（同一份镜像 / 存档数据，不是估算）")
    check("RsccSheaths.countIn 读服务端存档数据与客户端整份镜像两条来源",
          "public static int countIn(final Level level, @Nullable final BoundingBox box)" in sheaths
          and "map = saved(serverLevel).sheaths;" in sheaths
          and "map = clientMirror;" in sheaths)
    check("套壳按「坐标落在选择区内」逐格计数",
          "if (box.isInside(pos)) {" in sheaths)
    check("RsccCableCuts.countCutsIn 读服务端 seam/face 与客户端镜像",
          "public static int countCutsIn(final Level level, @Nullable final BoundingBox box)" in cuts
          and "final Saved saved = saved(serverLevel);" in cuts
          and "seam = saved.seam;" in cuts
          and "seam = mirror.seam();" in cuts)
    check("档位为 OFF 时计 0（没生效的设置不算「会丢的东西」）",
          "modeFor(level) == Mode.OFF" in cuts)
    check("接缝按「任意一端落在选择区内」计一处（口径写进注释）",
          "if (box.isInside(owner) || box.isInside(owner.relative(direction))) {" in cuts
          and "任意一端" in cuts)
    check("面记录按所在方块落在选择区内计一处",
          "for (final BlockPos pos : face.keySet()) {" in cuts)
    check("两端都为 0 时连包围盒都不需要（早退，零开销）",
          "if (map == null || map.isEmpty()) {" in sheaths)

    # ---------------- L4 文案 ----------------
    section("L4 语言键与文案硬要求")
    keys = ["gui.rs_create_compat.schematic_notice.title",
            "gui.rs_create_compat.schematic_notice.sheaths",
            "gui.rs_create_compat.schematic_notice.cuts",
            "gui.rs_create_compat.schematic_notice.hint"]
    for key in keys:
        check("zh/en 都有 %s" % key, key in zh and key in en)
    check("中文都不超过 40 字符（最长 %d）" % max(len(zh[k]) for k in keys),
          all(len(zh[k]) <= 40 for k in keys))
    check("不写「或」、不写「等 N 种」、不写「图标轮换」",
          all(("或" not in zh[k]) and ("等" not in zh[k]) and ("图标轮换" not in zh[k]) for k in keys))
    check("带 %s 的两行各自只有一个占位符（不会把数量画成键名）",
          zh[keys[1]].count("%s") == 1 and zh[keys[2]].count("%s") == 1)
    check("标题与建议不带占位符（不可传参）",
          "%s" not in zh[keys[0]] and "%s" not in zh[keys[3]]
          and "%s" not in en[keys[0]] and "%s" not in en[keys[3]])
    check("说清了「不会随蓝图复制」与「粘贴后手动补回」",
          "不会随蓝图复制" in zh[keys[1]] and "不会随蓝图复制" in zh[keys[2]]
          and "手动补回" in zh[keys[3]])
    check("代码里按同一前缀常量拼接（verify_lang_refs 能展开成完整键）",
          'private static final String RSCC_NOTICE_LANG = "gui.rs_create_compat.schematic_notice.";' in mixin)
    check("两个数量都真的是变量（不是写死的 1）",
          "CompletionBannerPayload.localized(RSCC_NOTICE_LANG + \"sheaths\", sheaths)" in mixin
          and "CompletionBannerPayload.localized(RSCC_NOTICE_LANG + \"cuts\", cuts)" in mixin)

    # ---------------- 说明方案 a 的否决理由 ----------------
    section("附：为什么不做方案 a（把记录写进蓝图）——理由必须留在源码里")
    check("类注释写明「Create 没有扩展点」与「没有安全落点」两条否决理由",
          "没有给模组任何扩展点" in mixin and "也没有安全的落点" in mixin)

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
