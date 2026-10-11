#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""专用服务端（DEDICATED_SERVER）日志判定器 —— 给 tools/verify_dedicated_server.ps1 用。

为什么要有「归属判定」这一层
--------------------------
专用服务端崩了不等于**我们**崩了。本工程历史上出现过两类完全不同的崩溃：
  * 我们自己的错（本次 1.1.0 的根因）：
        Mixin apply for mod rs_create_compat failed
        rs_create_compat.mixins.json:AutocrafterManagerSlotMixin -> ...AutocrafterManagerContainerMenu
        InvalidInjectionException: @Redirect factory method ... has an invalid signature.
        Found unexpected return type .../Slot, expected .../PatternSlot
  * 别人的错（例：某个联动模组缺前置，如 extra_gauges 要 create:factory_panel）：
        Missing or unsupported mandatory dependencies / Mixin apply for mod <别的 modid> failed
两者对「本模组是否可以在专服上跑」含义相反，所以必须分开报，而不是笼统地「失败」。

判定顺序（先看自己，再看别人；绝不用退出码 —— gradlew 在服务端崩溃时仍然报 BUILD SUCCESSFUL）
    1) 命中任何「专服上不存在客户端类」的证据且归属本模组 ⇒ OUR_FAILURE
    2) 命中 Mixin 应用失败且归属本模组（含 mixins.json 名 / 我们自己的类名前缀）⇒ OUR_FAILURE
    3) 命中 "Done (" 且没有致命标记 ⇒ PASS
    4) 有致命标记但归属别的 mod ⇒ OTHER_MOD
    5) 什么都没有 ⇒ NO_TERMINAL（超时 / 日志被截断）

退出码
    0 = 判定与 --expect 一致（校验通过）
    1 = 判定为「我们自己的错」
    2 = 「别人的错」/ 环境不可用（INCONCLUSIVE，无法拿它证明我们没问题）
    3 = 没抓到终态（超时 / 日志不完整）

用法
    python tools/verify_dedicated_server.py <日志> [--expect=pass|our-failure|any]
    python tools/verify_dedicated_server.py --selftest      # 用内置样例自证判定逻辑
"""

from __future__ import annotations

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


OUR_MODID = "rs_create_compat"
# 本模组自己的类名前缀（用于把「专服上加载客户端类」这类崩溃归属到自己头上）
OUR_PACKAGE_MARKERS = ("cretae/cookiewyq/rs_create_compat", "cretae.cookiewyq.rs_create_compat")

# 终态 / 致命标记（与 PS 侧的轮询判据保持一致）
FATAL_PATTERNS = (
    r"Failed to start the minecraft server",
    r"Mod loading has failed",
    r"Mixin apply for mod .* failed",
    r"has an invalid signature",
    r"MixinTransformerError",
    r"InvalidInjectionException",
    r"InvalidMixinException",
    r"for invalid dist DEDICATED_SERVER",
    r"Missing or unsupported mandatory dependencies",
    r"Incompatible mod set",
    r"java\.lang\.NoClassDefFoundError",
)
DONE_PATTERN = r"Done \("

# 「我们的错」的直接证据
OUR_MARKERS = (
    r"Mixin apply for mod %s failed" % OUR_MODID,
    r"%s\.mixins\.json:" % OUR_MODID,
    r"from mod %s" % OUR_MODID,
    r"mod %s\b" % OUR_MODID,
)
# 「必需前置缺失」的归属：FML 的 ModSorter 会打印
#     Mod ID: '<被依赖的 modid>', Requested by: '<提出依赖的 modid>', ...
# ⇒ 谁提出依赖就归谁，绝不笼统算「别人的错」。
# （实证：把本模组装进一个空 mods/ 目录时会打印
#   Mod ID: 'refinedstorage_curios_integration', Requested by: 'rs_create_compat'
#   —— 这是**我们**的必需前置缺失，不是别人的错。）
MISSING_DEP_RE = re.compile(
    r"Mod ID:\s*'([^']+)'\s*,\s*Requested by:\s*'([^']+)'")

# 别人的错：从这些句子里抠出 modid
OTHER_MODID_PATTERNS = (
    r"Mixin apply for mod ([a-z0-9_]+) failed",
    r"Mod file ([A-Za-z0-9_.\-]+) (?:has|is|requires)",
    r"mod ([a-z0-9_]+) requires",
)


def read_log(path):
    """读出日志文本，**按 BOM / 真实编码**解码。

    为什么不能直接 `decode("utf-8", errors="replace")`（踩过的坑）：
      PowerShell 的 `Tee-Object -FilePath` 默认写 **UTF-16LE**（带 FF FE BOM），
      用 UTF-8 解出来每个字符之间夹一个 NUL ⇒ "Mixin apply" 变成 "M\\0i\\0x\\0e\\0n..."，
      所有正则全部匹配不到 ⇒ 判定器会把一份**明明崩了的日志**报成 NO_TERMINAL（假阴性）。
      （本工程历史上已经因为「编码假设」误判过一次，所以这里显式判 BOM。）
    判定顺序：UTF-16LE/BE BOM → UTF-8 BOM → 严格 UTF-8 → GBK → latin-1 兜底。
    """
    with open(path, "rb") as handle:
        raw = handle.read()
    if raw[:2] == b"\xff\xfe":
        return raw[2:].decode("utf-16-le", errors="replace")
    if raw[:2] == b"\xfe\xff":
        return raw[2:].decode("utf-16-be", errors="replace")
    if raw[:3] == b"\xef\xbb\xbf":
        return raw[3:].decode("utf-8", errors="replace")
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1", errors="replace")


def interesting_lines(text, limit_per_pattern=4):
    """抓出所有致命/成功关键行（去重、保序），用于展示证据。"""
    hits = []
    seen = set()
    for line in text.splitlines():
        if len(line) > 600:
            line = line[:600] + " …"
        if re.search(DONE_PATTERN, line) or any(re.search(p, line) for p in FATAL_PATTERNS):
            key = line.strip()
            if key and key not in seen:
                seen.add(key)
                hits.append(key)
    return hits[:limit_per_pattern * len(FATAL_PATTERNS) + 10]


def attribute_others(text):
    """从崩溃日志里抠出「别人的错」涉及到的 modid（排除我们自己的）。"""
    found = []
    for pat in OTHER_MODID_PATTERNS:
        for m in re.finditer(pat, text):
            modid = m.group(1)
            if modid == OUR_MODID or modid in found:
                continue
            found.append(modid)
    return found


def judge(text):
    """返回 (verdict, 说明, 证据行列表)。

    verdict ∈ {PASS, OUR_MIXIN_FAILURE, OUR_DEPENDENCY, OUR_FAILURE, OTHER_MOD, NO_TERMINAL}
    """
    evidence = interesting_lines(text)
    has_done = re.search(DONE_PATTERN, text) is not None
    fatal = [p for p in FATAL_PATTERNS if re.search(p, text)]

    our_lines = []
    for line in text.splitlines():
        if any(re.search(p, line) for p in OUR_MARKERS):
            our_lines.append(line.strip())

    # ---- ① 我们的错：Mixin 应用失败 / 注入签名不符 / 专服加载客户端类，且归属自己 ----
    our_fatal_hits = []
    for line in text.splitlines():
        if not any(re.search(p, line) for p in FATAL_PATTERNS):
            # 崩溃的 "Caused by" 细节行本身不带 FATAL 关键字，但它会带我们的类名 + 失败原因
            if not re.search(r"has an invalid signature|for invalid dist DEDICATED_SERVER", line):
                continue
        if any(marker in line for marker in OUR_PACKAGE_MARKERS) or OUR_MODID in line:
            our_fatal_hits.append(line.strip())
    # @Mixin 目标类在我们自己的 mixin 里时，失败行通常写作 "rs_create_compat.mixins.json:..."
    if not our_fatal_hits:
        for line in our_lines:
            if any(re.search(p, line) for p in FATAL_PATTERNS):
                our_fatal_hits.append(line)

    if our_fatal_hits:
        # 再细分：究竟是「@Redirect 工厂 handler 签名不符」还是别的 Mixin 失败
        signature = re.search(
            r"has an invalid signature|InvalidInjectionException|InvalidMixinException",
            text) is not None
        kind = "OUR_MIXIN_FAILURE" if signature else "OUR_FAILURE"
        reason = ("崩溃归属 rs_create_compat（我们自己的错）"
                  + ("，且是 Mixin 注入签名 / 目标不符（InvalidInjectionException）" if signature else ""))
        return kind, reason, evidence

    # ---- ② 必需前置缺失：按 "Requested by" 归属（谁提出依赖就算谁的问题）----
    missing = MISSING_DEP_RE.findall(text)
    if missing:
        ours = [(dep, by) for dep, by in missing if by == OUR_MODID]
        if ours:
            return ("OUR_DEPENDENCY",
                    "本模组（%s）声明的必需前置在本次环境里缺失：%s"
                    % (OUR_MODID, ", ".join(dep for dep, _by in ours)),
                    evidence)
        return ("OTHER_MOD",
                "必需前置缺失，但提出依赖的不是本模组：%s"
                % ", ".join("%s(要 %s)" % (by, dep) for dep, by in missing[:5]),
                evidence)

    # ---- ③ 我们自己把只存在于客户端的类带到了专服上（dist 违规）----
    for m in re.finditer(r"Attempted to load class (\S+) for invalid dist DEDICATED_SERVER", text):
        cls = m.group(1)
        if cls in text:
            return ("OUR_FAILURE",
                    "专用服务端尝试加载客户端专属类 %s（dist 违规）" % cls, evidence)

    # ---- ④ 成功 ----
    if has_done and not fatal:
        return "PASS", "服务端完成启动（日志出现 Done(...)）且无任何致命标记", evidence

    # ---- ⑤ 别人的错 ----
    if fatal:
        others = attribute_others(text)
        if others:
            return ("OTHER_MOD",
                    "致命标记的归属不是本模组，而是：%s" % ", ".join(others[:5]), evidence)
        return ("OTHER_MOD",
                "有致命标记，但日志里没有任何 rs_create_compat 的归属线索（无法归到我们头上）",
                evidence)

    # ---- ⑥ 没终态 ----
    return "NO_TERMINAL", "日志里既没有 Done(...) 也没有任何已知致命标记（超时 / 被截断？）", evidence


EXPECTED_EXIT = {"PASS": 0, "OUR_MIXIN_FAILURE": 1, "OUR_DEPENDENCY": 1,
                 "OUR_FAILURE": 1, "OTHER_MOD": 2, "NO_TERMINAL": 3}
OUR_VERDICTS = ("OUR_MIXIN_FAILURE", "OUR_DEPENDENCY", "OUR_FAILURE")


def expect_matches(expect, verdict):
    """--expect 与判定是否一致。

    our-failure          = 任意「我们自己的错」（含签名不符 / 必需前置缺失）
    our-mixin-failure    = 必须**确实是** Mixin 注入签名那一类（本次 1.1.0 的根因）
    """
    if expect == "any":
        return True
    if expect == "pass":
        return verdict == "PASS"
    if expect == "our-failure":
        return verdict in OUR_VERDICTS
    if expect == "our-mixin-failure":
        return verdict == "OUR_MIXIN_FAILURE"
    return False


def selftest():
    """内置样例自证：证明判定器能把「我们的错 / 我们的前置缺失 / 别人的错 / 成功」分开。"""
    cases = [
        ("our-mixin-failure", """
[09:00:01] [modloading-sync-worker/FATAL] [mixin/]: Mixin apply for mod rs_create_compat failed rs_create_compat.mixins.json:AutocrafterManagerSlotMixin from mod rs_create_compat -> com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu
Caused by: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: @Redirect factory method ... has an invalid signature. Found unexpected return type net.minecraft.world.inventory.Slot, expected com.refinedmods.refinedstorage.common.autocrafting.PatternSlot
net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed
[09:00:04] [main/ERROR] [minecraft/Main]: Failed to start the minecraft server
""", "OUR_MIXIN_FAILURE"),
        ("our-dependency-missing", """
[09:10:41] [main/ERROR] [ne.ne.fm.lo.ModSorter/LOADING]: Missing or unsupported mandatory dependencies:
\tMod ID: 'refinedstorage_curios_integration', Requested by: 'rs_create_compat', Expected range: '[1.0.0,)', Actual version: '[MISSING]'
""", "OUR_DEPENDENCY"),
        ("other-mod-dependency", """
[09:10:41] [main/ERROR] [ne.ne.fm.lo.ModSorter/LOADING]: Missing or unsupported mandatory dependencies:
\tMod ID: 'create', Requested by: 'extra_gauges', Expected range: '[6.0.0,)', Actual version: '[MISSING]'
""", "OTHER_MOD"),
        ("other-mod-mixin", """
[10:00:00] [modloading-worker-0/FATAL] [mixin/]: Mixin apply for mod extra_gauges failed extra_gauges.mixins.json:FooMixin from mod extra_gauges
net.neoforged.neoforge.logging.CrashReportExtender$ModLoadingCrashException: Mod loading has failed
""", "OTHER_MOD"),
        ("pass", """
[09:00:30] [Server thread/INFO] [minecraft/DedicatedServer]: Done (12.345s)! For help, type "help"
""", "PASS"),
    ]
    bad = 0
    for name, text, want in cases:
        got, why, _ev = judge(text)
        ok = (got == want)
        print("%s %-24s -> %-18s %s" % ("PASS" if ok else "FAIL", name, got, why))
        if not ok:
            bad += 1
    # --expect 语义自证：our-failure 应接受「签名不符」与「前置缺失」两种，our-mixin-failure 只接受前者
    sem = [("our-failure", "OUR_MIXIN_FAILURE", True), ("our-failure", "OUR_DEPENDENCY", True),
           ("our-failure", "OTHER_MOD", False), ("our-mixin-failure", "OUR_DEPENDENCY", False),
           ("our-mixin-failure", "OUR_MIXIN_FAILURE", True), ("pass", "PASS", True),
           ("pass", "OUR_MIXIN_FAILURE", False)]
    for expect, verdict, want in sem:
        got = expect_matches(expect, verdict)
        ok = (got == want)
        print("%s %-24s -> %-18s expect=%s ⇒ %s" % ("PASS" if ok else "FAIL",
                                                    "--expect 语义", verdict, expect, got))
        if not ok:
            bad += 1
    print("")
    print("[%s] 判定器自证：%d/%d" % ("OK" if bad == 0 else "FAIL",
                                     len(cases) + len(sem) - bad, len(cases) + len(sem)))
    return 1 if bad else 0


def main(argv):
    if "--selftest" in argv:
        return selftest()

    args = [a for a in argv[1:] if not a.startswith("--")]
    if not args:
        print("用法: python tools/verify_dedicated_server.py <日志> "
              "[--expect=pass|our-failure|our-mixin-failure|any]")
        return 2
    log_path = args[0]
    expect = "pass"
    for a in argv[1:]:
        if a.startswith("--expect="):
            expect = a.split("=", 1)[1]
    if expect not in ("pass", "our-failure", "our-mixin-failure", "any"):
        print("[FATAL] --expect 只接受 pass / our-failure / our-mixin-failure / any")
        return 2
    if not os.path.isfile(log_path):
        print("[FATAL] 日志不存在: %s" % log_path)
        return 3

    text = read_log(log_path)
    verdict, why, evidence = judge(text)

    print("=" * 100)
    print("专用服务端日志判定：%s" % log_path)
    print("  日志大小 : %d 字节" % len(text.encode("utf-8", errors="replace")))
    print("  结论     : %s" % verdict)
    print("  理由     : %s" % why)
    print("  证据行（关键行）:")
    for line in evidence[:12]:
        print("      %s" % line)
    if not evidence:
        print("      <无>")

    matched = expect_matches(expect, verdict)
    print("  期望比对 : --expect=%s ⇒ %s" % (expect, "一致" if matched else "不一致"))
    print("=" * 100)

    if matched:
        if verdict == "OUR_MIXIN_FAILURE":
            print("[OK] 反例复现成功：校验器抓到了 rs_create_compat 自己的 Mixin 崩溃"
                  "（这正是 1.1.0 的根因）")
        elif verdict in OUR_VERDICTS:
            print("[OK] 判定与期望一致：这是我们自己的错（%s）" % verdict)
        elif verdict == "PASS":
            print("[OK] 专用服务端真的起来了，且日志里没有任何 Mixin / dist 致命标记")
        else:
            print("[OK] 结论与期望一致（%s）" % verdict)
        return 0
    return EXPECTED_EXIT.get(verdict, 3)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
