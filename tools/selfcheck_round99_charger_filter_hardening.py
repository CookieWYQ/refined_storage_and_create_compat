#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 99 轮自检：充电过滤的「硬上限」+「32 位裸十六进制 UUID」（用户反馈「第三个改了他」）。

背景（第 98 轮交付时如实列出的两条已知限制）
--------------------------------------------
① 「过滤名单的 C2S 包没有硬性条数上限」（当时只做到「不按对端给的数量预分配内存」）；
② 「手工输入 32 位无横线 UUID 会被当玩家名（永远不会命中）」。
本脚本就是这两条的验收：既查源码锚点，也把真代码**编译执行**一遍。

断言清单
--------
① ChargerFilterLimits：五个上限常量存在且数量级合理（物品上限 ≥ 界面一次能列的候选数、
   且远小于「能撑爆内存」的量级）；本类<b>不得依赖任何 MC / NeoForge 类型</b>
   （这条同时是「自检能直接编译执行它」的前提）。
② 网络层硬上限（编/解两侧都要）：
   - 编码：四种名单都先 `ChargerFilterLimits.clamp(…, MAX_…)` 再写条数；
   - 解码：**读完全部声明条目**但只保留前 N 条（`if (list.size() < MAX_…)`），
     且绝不 `new ArrayList<>(declared)`（那是第 98 轮修过的 OOM 洞，不能回退）；
   - SetChargerFilterPacket 与 SyncChargerFilterPacket 共用同一份编解码辅助方法
     （不允许两包各写一份几乎一样的循环）。
③ ChargingFilter（最下游的闸）：`of` / `load` 的所有入口都经 addIds / addName / addId，
   超限告警**每个进程只打一次**。
④ 界面：`parseUuid` 委托给 ChargerFilterLimits.parsePlayerId（36 带横线 + 32 裸十六进制）；
   `onAdd` 用同一个 normalizeName；勾选/添加在满员时不改数据、只写提示；
   提示文案走 `list.full` 语言键（带条数参数）。
⑤ 语言键 `list.full`：zh/en 成对、都含 `%s`、zh 非纯 ASCII、en 无中日韩。
⑥ **真代码语义**（JVM 探针，两组）：
   - 纯 JDK 组：ChargerFilterLimits 单独编译执行，覆盖 UUID 解析（36/32/大写/非法/名字）、
     名字归一化（trim/小写/超长/空）、addBounded 与 clamp 的上限行为；
   - 带 MC classpath 组：ChargingFilter.of / load 真实跑一遍（1500 条物品、400 条名字、
     400 条 UUID、2000 条 NBT），断言最终集合恰好被截到上限。

反例自证（≥ 7 条）：把「旧写法 / 错误写法」拼进源码副本，断言判据真的判负。

用法：python tools/selfcheck_round99_charger_filter_hardening.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.dirname(os.path.abspath(__file__))
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import _gradle_cache as gc  # noqa: E402

PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SUPPORT = os.path.join(PKG, "support")
NET = os.path.join(PKG, "network")
CLIENT = os.path.join(PKG, "client")
RES = os.path.join(ROOT, "src", "main", "resources")
LANG_DIR = os.path.join(RES, "assets", "rs_create_compat", "lang")
COMPILE_OUT = os.path.join(ROOT, "build", "lead_compile")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round99")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"
# javac 默认用系统语言的本地化消息（Windows 上是 GBK 中文）；我们按 UTF-8 读它的输出，
# 会把错误信息读成乱码，因此强制它说英文（-J 是传给 javac 所在 JVM 的参数）。
JAVAC_EN = ["-J-Duser.language=en", "-J-Duser.country=US"]

FILES = {
    "limits": os.path.join(SUPPORT, "ChargerFilterLimits.java"),
    "filter": os.path.join(SUPPORT, "ChargingFilter.java"),
    "set_packet": os.path.join(NET, "SetChargerFilterPacket.java"),
    "sync_packet": os.path.join(NET, "SyncChargerFilterPacket.java"),
    "screen": os.path.join(CLIENT, "screen", "ChargerFilterScreen.java"),
    "candidates": os.path.join(CLIENT, "screen", "ChargerFilterCandidates.java"),
}
LANG_KEYS = ["gui.rs_create_compat.charger_filter.list.full"]
CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok):
    """反例自证：参数必须是「判据对坏写法判负了」，否则本脚本自身不可信。"""
    COUNTER[0] += 1
    check("反例 " + name, ok)


# ======================================================================================
# 通用文本工具：去注释 / 取方法体 / 读 int 常量
# ======================================================================================

def strip_comments(text):
    """去掉 // 与 /*...*/ 注释，保留字符串字面量（判「代码里有没有调用 X」时用）。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:end]))
            i = end
            continue
        char = text[i]
        if quote is None and char in "\"'":
            quote = char
        elif quote is not None and char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        elif quote is not None and char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def body_of(text, needle):
    """返回 `needle` 之后第一对花括号里的内容（找不到 ⇒ 空串）。"""
    start = text.find(needle)
    if start < 0:
        return ""
    brace = text.find("{", start + len(needle))
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(text)):
        char = text[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    return ""


def int_const(text, name):
    match = re.search(r"int\s+" + re.escape(name) + r"\s*=\s*([0-9_]+)\s*;", text)
    if not match:
        return None
    return int(match.group(1).replace("_", ""))


# ======================================================================================
# 判据（返回问题码列表；反例自证靠「问题码出现」证明判负）
# ======================================================================================

def audit(src):
    """src: dict[str, str]（键见 FILES），返回问题码列表。"""
    problems = []
    limits = strip_comments(src["limits"])
    sync = strip_comments(src["sync_packet"])
    setp = strip_comments(src["set_packet"])
    filt = strip_comments(src["filter"])
    screen = strip_comments(src["screen"])

    # ---------- ① ChargerFilterLimits：常量存在 + 数量级合理 + 纯 JDK ----------
    consts = {
        "MAX_ITEM_ENTRIES": int_const(limits, "MAX_ITEM_ENTRIES"),
        "MAX_PLAYER_ENTRIES": int_const(limits, "MAX_PLAYER_ENTRIES"),
        "MAX_PLAYER_ID_ENTRIES": int_const(limits, "MAX_PLAYER_ID_ENTRIES"),
        "MAX_NAME_LENGTH": int_const(limits, "MAX_NAME_LENGTH"),
        "MAX_KNOWN_PLAYERS": int_const(limits, "MAX_KNOWN_PLAYERS"),
    }
    if any(value is None for value in consts.values()):
        problems.append("limits-constants-missing")
    else:
        search_cap = int_const(strip_comments(src["candidates"]), "MAX_SEARCH_RESULTS")
        if search_cap is None:
            problems.append("limits-search-cap-unknown")
        elif consts["MAX_ITEM_ENTRIES"] < search_cap:
            # 界面一次最多列 search_cap 个候选；上限比它还小 ⇒ 玩家勾不完一页就被截断
            problems.append("limits-item-cap-too-small")
        if consts["MAX_ITEM_ENTRIES"] > 65536:
            problems.append("limits-item-cap-unbounded")
        if consts["MAX_NAME_LENGTH"] < 16 or consts["MAX_NAME_LENGTH"] > 64:
            # 原版名最长 16：比 16 小会误杀真实玩家名，比 64 大就失去意义
            problems.append("limits-name-length-nonsense")
        if not (0 < consts["MAX_PLAYER_ENTRIES"] <= consts["MAX_ITEM_ENTRIES"]):
            problems.append("limits-player-cap-nonsense")

    # 纯 JDK：不许出现 MC / NeoForge / 本模组其它类（否则「直接编译执行」这条验收就不成立）
    if re.search(r"^\s*import\s+(net\.minecraft|net\.neoforged|com\.mojang|com\.refinedmods)",
                 limits, re.M):
        problems.append("limits-impure-import")
    for word in ("net.minecraft", "net.neoforged", "BuiltInRegistries", "ItemStack", "Level"):
        if word in limits:
            problems.append("limits-impure-ref:" + word)

    # ---------- ② 网络层：编码 clamp、解码封顶、不预分配 ----------
    res_encode = body_of(sync, "static void encode(")
    if "ChargerFilterLimits.clamp(" not in res_encode or "MAX_ITEM_ENTRIES" not in res_encode:
        problems.append("codec-encode-not-clamped:items")
    if "new ArrayList<>(" in res_encode:
        problems.append("codec-encode-allocates-by-input")
    for method, const in (("static void encodeNames(", "MAX_PLAYER_ENTRIES"),
                          ("static void encodeUuids(", "MAX_PLAYER_ID_ENTRIES"),
                          ("static void encodeKnownPlayers(", "MAX_KNOWN_PLAYERS")):
        body = body_of(sync, method)
        if "ChargerFilterLimits.clamp(" not in body or const not in body:
            problems.append("codec-encode-not-clamped:" + method)

    for method, const in (("static List<ResourceLocation> decode(", "MAX_ITEM_ENTRIES"),
                          ("static List<String> decodeNames(", "MAX_PLAYER_ENTRIES"),
                          ("static List<UUID> decodeUuids(", "MAX_PLAYER_ID_ENTRIES"),
                          ("static List<KnownPlayer> decodeKnownPlayers(", "MAX_KNOWN_PLAYERS")):
        body = body_of(sync, method)
        if const not in body:
            problems.append("codec-decode-uncapped:" + method)
        # 解码必须「读完全部声明条目、只留前 N 条」：容量判断在循环内、追加在判断内
        if not re.search(r"for\s*\([^)]*i\s*<\s*declared", body):
            problems.append("codec-decode-count-var:" + method)
        if "new ArrayList<>(declared)" in body or "new ArrayList<>(count)" in body:
            problems.append("codec-decode-prealloc-by-input:" + method)
        if "break;" in body:
            # 读一半就 break 会让后续字段错位（名字被当 UUID 读），整包解析成随机数据
            problems.append("codec-decode-breaks-stream:" + method)

    # Set 包必须复用 Sync 包的编解码辅助（不允许两包各写一份）
    for needle in ("SyncChargerFilterPacket.ResourceCodecList.encode(",
                   "SyncChargerFilterPacket.ResourceCodecList.decode(",
                   "SyncChargerFilterPacket.encodeNames(",
                   "SyncChargerFilterPacket.decodeNames(",
                   "SyncChargerFilterPacket.encodeUuids(",
                   "SyncChargerFilterPacket.decodeUuids("):
        if needle not in setp:
            problems.append("set-packet-not-shared:" + needle)
    if "new ArrayList<>()" in setp:
        problems.append("set-packet-own-allocation")

    # ---------- ③ ChargingFilter：最下游的闸 ----------
    add_ids = body_of(filt, "private static void addIds(")
    if "ChargerFilterLimits.addBounded(" not in add_ids or "MAX_ITEM_ENTRIES" not in add_ids:
        problems.append("filter-addids-uncapped")
    add_name = body_of(filt, "private static void addName(")
    if ("ChargerFilterLimits.normalizeName(" not in add_name
            or "ChargerFilterLimits.addBounded(" not in add_name
            or "MAX_PLAYER_ENTRIES" not in add_name):
        problems.append("filter-addname-uncapped")
    add_id = body_of(filt, "private static void addId(")
    if "ChargerFilterLimits.addBounded(" not in add_id or "MAX_PLAYER_ID_ENTRIES" not in add_id:
        problems.append("filter-addid-uncapped")
    read_ids = body_of(filt, "private static void readIds(")
    if "ChargerFilterLimits.addBounded(" not in read_ids or "MAX_ITEM_ENTRIES" not in read_ids:
        problems.append("filter-nbt-readids-uncapped")
    toggle_semantics = body_of(limits, "public static <T> boolean toggleBounded(")
    if not toggle_semantics:
        problems.append("limits-toggle-missing")
    else:
        remove_at = toggle_semantics.find("target.remove(value)")
        add_at = toggle_semantics.find("target.add(value)")
        if remove_at < 0 or add_at < 0 or remove_at > add_at:
            # remove 必须在 add 之前：否则「取消勾选」会被后面的 add 又加回来
            problems.append("limits-toggle-order")
        if "target.size() >= max" not in toggle_semantics:
            problems.append("limits-toggle-uncapped")

    warn = body_of(filt, "private static void warnOverflow()")
    if "if (overflowWarned)" not in warn or "overflowWarned = true;" not in warn:
        problems.append("filter-overflow-warn-not-throttled")
    for caller in ("private static void addIds(", "private static void addName(",
                   "private static void addId(", "private static void readIds("):
        if "warnOverflow();" not in body_of(filt, caller):
            problems.append("filter-overflow-silent:" + caller)

    # ---------- ④ 界面：UUID 解析 / 归一化 / 满员提示 ----------
    parse_body = body_of(screen, "private static UUID parseUuid(")
    if "ChargerFilterLimits.parsePlayerId(" not in parse_body:
        problems.append("screen-uuid-not-delegated")
    if "UUID.fromString(" in parse_body or "length() != 36" in parse_body:
        problems.append("screen-uuid-own-parser")
    add_body = body_of(screen, "private void onAdd()")
    if "ChargerFilterLimits.normalizeName(" not in add_body:
        problems.append("screen-add-name-not-normalized")
    if "toLowerCase(Locale.ROOT)" in add_body:
        # 小写化只允许在 ChargerFilterLimits.normalizeName 里做一次（两套标准 = 两套 bug）
        problems.append("screen-add-own-lowercasing")
    if "fullStatus(" not in add_body:
        problems.append("screen-add-no-full-hint")
    toggle_body = body_of(screen, "private void toggleRow(")
    if "hasRoomFor(" not in toggle_body:
        problems.append("screen-toggle-no-cap-check")
    if "ChargerFilterLimits.toggleBounded(" not in toggle_body:
        # 「已勾选 ⇒ 取消 / 否则加入」只允许走 ChargerFilterLimits.toggleBounded：
        # 界面自己写 remove+add 很容易写成「无条件 add」⇒ 勾选框永远关不掉
        problems.append("screen-toggle-own-add")
    if "fullStatus(ChargerFilterLimits.MAX_ITEM_ENTRIES)" not in toggle_body:
        # 物品页满了不提示 = 点了没反应（本工程硬规则：必须给提示）
        problems.append("screen-toggle-item-cap-silent")
    if "fullStatus(Math.max(" not in toggle_body:
        # 玩家行是「名字 + UUID」复合操作，两个集合都可能满
        problems.append("screen-toggle-player-cap-silent")
    full_body = body_of(screen, "private static Component fullStatus(")
    if '"list.full"' not in full_body or "Component.translatable(" not in full_body:
        problems.append("screen-full-hint-no-arg")
    if "hintKey(" in screen:
        problems.append("screen-stale-hintkey")
    if "private Component hint()" not in screen:
        problems.append("screen-hint-missing")

    return problems


def lang_problems_with(zh_text, en_text):
    """语言判据（唯一实现；真实检查与反例自证都调它，避免「反例测的是另一套逻辑」）。"""
    problems = []
    try:
        zh = json.loads(zh_text)
    except Exception:  # pragma: no cover
        return ["lang-zh_cn-unreadable"]
    try:
        en = json.loads(en_text)
    except Exception:  # pragma: no cover
        return ["lang-en_us-unreadable"]
    for key in LANG_KEYS:
        for name, data in (("zh_cn", zh), ("en_us", en)):
            if key not in data:
                problems.append("lang-%s-missing:%s" % (name, key))
                continue
            value = data[key]
            if not isinstance(value, str) or "%s" not in value:
                # 提示行要显示「最多 N 条」，缺参数占位就永远显示不出数字
                problems.append("lang-%s-no-placeholder:%s" % (name, key))
        zh_value = zh.get(key)
        en_value = en.get(key)
        if isinstance(zh_value, str) and not CJK_RE.search(zh_value):
            problems.append("lang-zh-ascii:%s" % key)
        if isinstance(en_value, str) and CJK_RE.search(en_value):
            problems.append("lang-en-cjk:%s" % key)
    return problems


def lang_problems():
    return lang_problems_with(read(os.path.join(LANG_DIR, "zh_cn.json")),
                              read(os.path.join(LANG_DIR, "en_us.json")))


# ======================================================================================
# ⑥ JVM 探针：真代码语义
# ======================================================================================

PURE_PROBE = r'''
import cretae.cookiewyq.rs_create_compat.support.ChargerFilterLimits;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 纯 JDK 探针：只依赖 ChargerFilterLimits（因此无需 MC classpath）。 */
public final class ChargerFilterLimitsProbe {
    private static int cases;
    private static int failures;

    public static void main(String[] args) {
        // ---- UUID：36 带横线（小写/大写）都应认 ----
        final UUID dashed = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        expectEq("dashed-lower", dashed, ChargerFilterLimits.parsePlayerId("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        expectEq("dashed-upper", dashed, ChargerFilterLimits.parsePlayerId("069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
        expectEq("dashed-padded", dashed, ChargerFilterLimits.parsePlayerId("  069a79f4-44e9-4726-a5be-fca90e38aaf5  "));
        // ---- UUID：32 裸十六进制（第 98 轮被当成玩家名的那个坑） ----
        expectEq("bare-lower", dashed, ChargerFilterLimits.parsePlayerId("069a79f444e94726a5befca90e38aaf5"));
        expectEq("bare-upper", dashed, ChargerFilterLimits.parsePlayerId("069A79F444E94726A5BEFCA90E38AAF5"));
        expectEq("bare-is-not-name", dashed, ChargerFilterLimits.parsePlayerId("069a79f444e94726a5befca90e38aaf5"));
        // ---- 非法输入不得被当成 UUID ----
        expectNull("short", ChargerFilterLimits.parsePlayerId("069a79f4"));
        expectNull("long", ChargerFilterLimits.parsePlayerId("069a79f444e94726a5befca90e38aaf5ff"));
        expectNull("name-16", ChargerFilterLimits.parsePlayerId("Notch"));
        expectNull("empty", ChargerFilterLimits.parsePlayerId(""));
        expectNull("null", ChargerFilterLimits.parsePlayerId(null));
        expectNull("bare-nonhex", ChargerFilterLimits.parsePlayerId("069a79f444e94726a5befca90e38aafz"));
        expectNull("dashed-bad-dash", ChargerFilterLimits.parsePlayerId("069a79f444e9-4726-a5be-fca90e38aaf5"));
        expectNull("dashed-nonhex", ChargerFilterLimits.parsePlayerId("069a79f4-44e9-4726-a5be-fca90e38aafz"));
        // ---- withDashes / isHex ----
        expectEqStr("with-dashes", "069a79f4-44e9-4726-a5be-fca90e38aaf5",
            ChargerFilterLimits.withDashes("069a79f444e94726a5befca90e38aaf5"));
        expectTrue("ishex", ChargerFilterLimits.isHex("09aF"));
        expectFalse("ishex-z", ChargerFilterLimits.isHex("09aFz"));
        expectFalse("ishex-empty", ChargerFilterLimits.isHex(""));
        // ---- 名字归一化 ----
        expectEqStr("name-trim-lower", "steve", ChargerFilterLimits.normalizeName("  StEvE "));
        expectNull("name-empty", ChargerFilterLimits.normalizeName("   "));
        expectNull("name-null", ChargerFilterLimits.normalizeName(null));
        final String tooLong = "a".repeat(ChargerFilterLimits.MAX_NAME_LENGTH + 1);
        expectNull("name-too-long", ChargerFilterLimits.normalizeName(tooLong));
        expectEqStr("name-max-ok", "a".repeat(ChargerFilterLimits.MAX_NAME_LENGTH),
            ChargerFilterLimits.normalizeName("a".repeat(ChargerFilterLimits.MAX_NAME_LENGTH)));
        // ---- addBounded ----
        final Set<Integer> set = new LinkedHashSet<>();
        expectTrue("add-1", ChargerFilterLimits.addBounded(set, 1, 2));
        expectTrue("add-dup", ChargerFilterLimits.addBounded(set, 1, 2));
        expectTrue("add-null", ChargerFilterLimits.addBounded(set, null, 2));
        expectTrue("add-2", ChargerFilterLimits.addBounded(set, 2, 2));
        expectFalse("add-overflow", ChargerFilterLimits.addBounded(set, 3, 2));
        expectTrue("add-size", set.size() == 2);
        // ---- toggleBounded：勾上 → 再点 → 必须为空（勾选框关不掉的回归点）----
        final Set<Integer> toggle = new LinkedHashSet<>();
        expectTrue("toggle-on", ChargerFilterLimits.toggleBounded(toggle, 7, 2) && toggle.contains(7));
        expectTrue("toggle-off", ChargerFilterLimits.toggleBounded(toggle, 7, 2) && toggle.isEmpty());
        expectTrue("toggle-fill", ChargerFilterLimits.toggleBounded(toggle, 1, 1)
            && ChargerFilterLimits.toggleBounded(toggle, 2, 1) == false && toggle.size() == 1);
        expectTrue("toggle-remove-when-full", ChargerFilterLimits.toggleBounded(toggle, 1, 1)
            && toggle.isEmpty());

        // ---- clamp ----
        final List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            big.add(i);
        }
        expectTrue("clamp-size", ChargerFilterLimits.clamp(big, 10).size() == 10);
        expectTrue("clamp-first", ChargerFilterLimits.clamp(big, 10).get(0) == 0);
        expectTrue("clamp-null", ChargerFilterLimits.clamp(null, 10).isEmpty());
        expectTrue("clamp-under", ChargerFilterLimits.clamp(List.of(1, 2), 10).size() == 2);
        expectTrue("clamp-zero", ChargerFilterLimits.clamp(big, 0).isEmpty());
        // ---- 上限常量本身 ----
        expectTrue("cap-item", ChargerFilterLimits.MAX_ITEM_ENTRIES >= 512
            && ChargerFilterLimits.MAX_ITEM_ENTRIES <= 65536);
        expectTrue("cap-player", ChargerFilterLimits.MAX_PLAYER_ENTRIES > 0
            && ChargerFilterLimits.MAX_PLAYER_ENTRIES <= ChargerFilterLimits.MAX_ITEM_ENTRIES);

        report("PURE");
    }

    private static void expectEq(String name, Object want, Object got) {
        cases++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + name + " got=" + got + " want=" + want);
        }
    }

    private static void expectEqStr(String name, String want, String got) {
        cases++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + name + " got=" + got + " want=" + want);
        }
    }

    private static void expectNull(String name, Object got) {
        cases++;
        if (got != null) {
            failures++;
            System.out.println("FAIL " + name + " got=" + got + " want=null");
        }
    }

    private static void expectTrue(String name, boolean ok) {
        cases++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }

    private static void expectFalse(String name, boolean value) {
        expectTrue(name, !value);
    }

    private static void report(String tag) {
        if (failures > 0) {
            System.out.println(tag + "_FAIL failures=" + failures + " cases=" + cases);
            System.exit(1);
        }
        System.out.println(tag + "_OK cases=" + cases);
    }
}
'''

MC_PROBE = r'''
import cretae.cookiewyq.rs_create_compat.support.ChargingFilter;
import cretae.cookiewyq.rs_create_compat.support.ChargerFilterLimits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 带 MC classpath 的探针：真跑 ChargingFilter.of / load，断言超限一律被截到上限。
 * （这是「上限真的生效」的唯一可信证据：静态 grep 只能证明「代码里有 clamp」，
 *  证明不了「走完 of() 之后集合真的只剩 1024 条」。）
 */
public final class ChargingFilterCapProbe {
    private static int cases;
    private static int failures;

    public static void main(String[] args) {
        final int overItems = ChargerFilterLimits.MAX_ITEM_ENTRIES + 300;
        final int overPlayers = ChargerFilterLimits.MAX_PLAYER_ENTRIES + 40;
        final int overIds = ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES + 40;

        final List<ResourceLocation> dropped = ids("dropped", overItems);
        final List<ResourceLocation> carried = ids("carried", overItems);
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < overPlayers; i++) {
            names.add("Player" + i);
        }
        final List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < overIds; i++) {
            ids.add(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
        }

        final ChargingFilter filter = ChargingFilter.of(false, dropped, carried, names, ids);
        expectTrue("of-dropped", filter.droppedItems().size() == ChargerFilterLimits.MAX_ITEM_ENTRIES);
        expectTrue("of-carried", filter.carriedItems().size() == ChargerFilterLimits.MAX_ITEM_ENTRIES);
        expectTrue("of-names", filter.playerNames().size() == ChargerFilterLimits.MAX_PLAYER_ENTRIES);
        expectTrue("of-ids", filter.playerIds().size() == ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES);

        // 名字归一化（大小写 / 空格）也要在 of() 里生效，且去重后不虚胖
        final ChargingFilter small = ChargingFilter.of(true,
            List.of(), List.of(), List.of("  StEvE  ", "Steve", ""), List.of());
        expectTrue("of-name-normalized", small.playerNames().contains("steve"));
        expectTrue("of-name-dedup", small.playerNames().size() == 1);
        expectTrue("of-whitelist", small.whitelist());

        // 存档回路：超限 NBT 读回来必须被截断
        final CompoundTag huge = new CompoundTag();
        huge.putInt("v", ChargingFilter.NBT_VERSION);
        huge.putBoolean("whitelist", true);
        final ListTag nbtDropped = new ListTag();
        for (int i = 0; i < overItems; i++) {
            nbtDropped.add(StringTag.valueOf("nbt:dropped" + i));
        }
        huge.put("dropped", nbtDropped);
        final ListTag nbtCarried = new ListTag();
        for (int i = 0; i < overItems; i++) {
            nbtCarried.add(StringTag.valueOf("nbt:carried" + i));
        }
        huge.put("carried", nbtCarried);
        final ListTag nbtNames = new ListTag();
        for (int i = 0; i < overPlayers; i++) {
            nbtNames.add(StringTag.valueOf("nbtplayer" + i));
        }
        huge.put("names", nbtNames);
        final ListTag nbtIds = new ListTag();
        for (int i = 0; i < overIds; i++) {
            nbtIds.add(NbtUtils.createUUID(UUID.nameUUIDFromBytes(("n" + i).getBytes())));
        }
        huge.put("ids", nbtIds);

        final ChargingFilter loaded = ChargingFilter.load(huge);
        expectTrue("load-whitelist", loaded.whitelist());
        expectTrue("load-dropped", loaded.droppedItems().size() == ChargerFilterLimits.MAX_ITEM_ENTRIES);
        expectTrue("load-carried", loaded.carriedItems().size() == ChargerFilterLimits.MAX_ITEM_ENTRIES);
        expectTrue("load-names", loaded.playerNames().size() == ChargerFilterLimits.MAX_PLAYER_ENTRIES);
        expectTrue("load-ids", loaded.playerIds().size() == ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES);
        expectTrue("load-first-kept", loaded.droppedItems().contains(id("nbt", "dropped0")));

        // 版本闸没被这次改动弄坏：v=99 一律回落默认
        final CompoundTag future = new CompoundTag();
        future.putInt("v", 99);
        future.put("dropped", nbtDropped);
        final ChargingFilter fallback = ChargingFilter.load(future);
        expectTrue("load-version-gate", fallback.droppedItems().isEmpty() && !fallback.whitelist());

        // save → load 往返（正常规模）语义不变
        final ChargingFilter round = ChargingFilter.of(true, List.of(id("a", "b")), List.of(),
            List.of("Steve"), List.of(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
        final CompoundTag tag = new CompoundTag();
        round.save(tag);
        final ChargingFilter back = ChargingFilter.load(tag);
        expectTrue("roundtrip-whitelist", back.whitelist());
        expectTrue("roundtrip-dropped", back.droppedItems().size() == 1 && back.droppedItems().contains(id("a", "b")));
        expectTrue("roundtrip-names", back.playerNames().size() == 1 && back.playerNames().contains("steve"));
        expectTrue("roundtrip-ids", back.playerIds().size() == 1);

        if (failures > 0) {
            System.out.println("MC_CAP_FAIL failures=" + failures + " cases=" + cases);
            System.exit(1);
        }
        System.out.println("MC_CAP_OK cases=" + cases);
    }

    private static List<ResourceLocation> ids(String kind, int count) {
        final List<ResourceLocation> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(id(kind, kind + i));
        }
        return out;
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void expectTrue(String name, boolean ok) {
        cases++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
'''


def lead_classpath():
    """与 tools/_lead_compile.ps1 同一套判据（跟随 GRADLE_USER_HOME + 同族去重）。"""
    parts = [COMPILE_OUT]
    mc_jar, _ = gc.find_mc_jar()
    if mc_jar:
        parts.append(mc_jar)
    deduped = gc.dedupe(gc.module_jars(), gc.pinned_versions())
    parts.extend(ref.path for ref in deduped.kept)
    libs_dir = os.path.join(ROOT, "libs")
    if os.path.isdir(libs_dir):
        parts.extend(os.path.join(libs_dir, name) for name in sorted(os.listdir(libs_dir))
                     if name.endswith(".jar"))
    seen = set()
    unique = []
    for part in parts:
        if part and part not in seen:
            seen.add(part)
            unique.append(part)
    return ";".join(unique)


def run_jvm_probes():
    for tool in (JAVAC, JAVA):
        if not os.path.isfile(tool):
            return False, "找不到 " + tool
    os.makedirs(PROBE_DIR, exist_ok=True)

    # ---- 探针 A：纯 JDK（只编译 ChargerFilterLimits + 探针，无 classpath）----
    src_dir_a = os.path.join(PROBE_DIR, "pure")
    os.makedirs(src_dir_a, exist_ok=True)
    pure_probe = os.path.join(src_dir_a, "ChargerFilterLimitsProbe.java")
    with io.open(pure_probe, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(PURE_PROBE)
    proc = subprocess.run([JAVAC, *JAVAC_EN, "-proc:none", "-encoding", "UTF-8",
                           "-sourcepath", os.path.join(ROOT, "src", "main", "java"),
                           "-d", src_dir_a, pure_probe],
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=180)
    if proc.returncode != 0:
        return False, "纯 JDK 探针编译失败：" + (proc.stdout + proc.stderr)[-1200:]
    proc = subprocess.run([JAVA, "-cp", src_dir_a, "ChargerFilterLimitsProbe"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=180)
    out_a = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0 or "PURE_OK" not in out_a:
        return False, "纯 JDK 探针失败：" + out_a[-1500:]

    # ---- 探针 B：带 MC classpath（真跑 ChargingFilter.of / load）----
    if not os.path.isdir(COMPILE_OUT):
        return False, "build/lead_compile 不存在（先跑 tools/_lead_compile.ps1）"
    src_dir_b = os.path.join(PROBE_DIR, "mc")
    os.makedirs(src_dir_b, exist_ok=True)
    mc_probe = os.path.join(src_dir_b, "ChargingFilterCapProbe.java")
    with io.open(mc_probe, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(MC_PROBE)
    classpath = lead_classpath()
    proc = subprocess.run([JAVAC, *JAVAC_EN, "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                           "-d", src_dir_b, mc_probe],
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=240)
    if proc.returncode != 0:
        return False, "MC 探针编译失败：" + (proc.stdout + proc.stderr)[-1500:]
    proc = subprocess.run([JAVA, "-cp", src_dir_b + ";" + classpath, "ChargingFilterCapProbe"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=240)
    out_b = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0 or "MC_CAP_OK" not in out_b:
        return False, "MC 探针失败：" + out_b[-1500:]

    tail_a = [line for line in out_a.splitlines() if line.startswith("PURE_OK")]
    tail_b = [line for line in out_b.splitlines() if line.startswith("MC_CAP_OK")]
    return True, "；".join(tail_a + tail_b)


# ======================================================================================
# 主流程
# ======================================================================================

sources = {name: read(path) for name, path in FILES.items()}

print("=" * 78)
print("round99：充电过滤硬上限 + 32 位裸 UUID（第 98 轮遗留的两条已知限制）")
print("=" * 78)

problems = audit(sources)
check("①②③④ 源码锚点：上限常量 / 编解码封顶 / ChargingFilter 下游闸 / 界面提示（%d 项判据）" % 6,
      not problems, ", ".join(problems[:8]))

lang_issues = lang_problems()
check("⑤ 语言键 list.full：zh/en 成对 + 都含 %s + zh 非纯 ASCII + en 无中日韩",
      not lang_issues, ", ".join(lang_issues))

ok, detail = run_jvm_probes()
check("⑥ 真代码语义：ChargerFilterLimits 纯 JDK 探针 + ChargingFilter of/load 超限探针", ok, detail)
if ok:
    print("[probe] " + detail)

# ======================================================================================
# 反例自证：判据必须真的能判负
# ======================================================================================

def mutate(name, old, new, count=1):
    if old not in sources[name]:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (name, old))
    copy = dict(sources)
    copy[name] = sources[name].replace(old, new, count)
    return copy


# 1. 「取消上限」：把物品上限拉到 Integer.MAX_VALUE
case = mutate("limits", "MAX_ITEM_ENTRIES = 1024;", "MAX_ITEM_ENTRIES = 65537;")
counterexample("①a 物品上限抬到 65537（等于取消上限）⇒ limits-item-cap-unbounded",
      "limits-item-cap-unbounded" in audit(case))

# 2. 「上限比界面一页还小」：物品上限压到 128（界面一次最多列 512 个候选）
case = mutate("limits", "MAX_ITEM_ENTRIES = 1024;", "MAX_ITEM_ENTRIES = 128;")
counterexample("①b 物品上限压到 128（小于候选搜索上限 512）⇒ limits-item-cap-too-small",
      "limits-item-cap-too-small" in audit(case))

# 3. 让 ChargerFilterLimits 依赖 MC 类型（探针就无法直接跑了）
case = mutate("limits", "import java.util.UUID;", "import java.util.UUID;\nimport net.minecraft.nbt.CompoundTag;")
counterexample("①c ChargerFilterLimits 引入 MC 依赖 ⇒ limits-impure-import",
      "limits-impure-import" in audit(case))

# 4. 解码回到「按对端条数预分配内存」（第 98 轮修过的 OOM 洞）
case = mutate("sync_packet",
              "final List<ResourceLocation> ids = new ArrayList<>();",
              "final List<ResourceLocation> ids = new ArrayList<>(declared);")
counterexample("②a 解码按声明条数预分配 ⇒ codec-decode-prealloc-by-input",
      any(p.startswith("codec-decode-prealloc-by-input") for p in audit(case))
      or "codec-decode-prealloc-by-input:static List<ResourceLocation> decode(" in audit(case))

# 5. 解码「读够上限就 break」（会让后续字段错位）
case = mutate("sync_packet",
              "if (ids.size() < ChargerFilterLimits.MAX_ITEM_ENTRIES) {\n                    ids.add(id);",
              "if (ids.size() < ChargerFilterLimits.MAX_ITEM_ENTRIES) {\n                    ids.add(id);\n                } else {\n                    break;")
counterexample("②b 解码读够上限就 break（后续字段错位）⇒ codec-decode-breaks-stream",
      any(p.startswith("codec-decode-breaks-stream") for p in audit(case)))

# 6. 编码不截断（直接把 raw 名单写出去）
case = mutate("sync_packet",
              "final List<ResourceLocation> ids = ChargerFilterLimits.clamp(rawIds,\n                ChargerFilterLimits.MAX_ITEM_ENTRIES);",
              "final List<ResourceLocation> ids = rawIds;")
counterexample("②c 编码不截断（写 raw 名单）⇒ codec-encode-not-clamped",
      any(p.startswith("codec-encode-not-clamped") for p in audit(case)))

# 7. ChargingFilter 的闸拆掉（只剩网络层，手改 NBT 就能绕过）
case = mutate("filter",
              "if (!ChargerFilterLimits.addBounded(target, id,\n                ChargerFilterLimits.MAX_ITEM_ENTRIES)) {\n                warnOverflow();\n                return;\n            }",
              "if (id != null) {\n                target.add(id);\n            }")
counterexample("③ 拆掉 addIds 的下游闸 ⇒ filter-addids-uncapped",
      "filter-addids-uncapped" in audit(case))

# 8. 超限告警去掉节流（每个 tick 刷一次日志）
case = mutate("filter", "if (overflowWarned) {\n            return;\n        }\n        overflowWarned = true;",
              "overflowWarned = true;")
counterexample("③b 超限告警不节流 ⇒ filter-overflow-warn-not-throttled",
      "filter-overflow-warn-not-throttled" in audit(case))

# 9. 界面退回「只认 36 字符」（第 98 轮的行为）
case = mutate("screen",
              "return ChargerFilterLimits.parsePlayerId(text);",
              "return text.trim().length() == 36 ? UUID.fromString(text.trim()) : null;")
counterexample("④a 界面退回「只认 36 字符」⇒ screen-uuid-own-parser",
      "screen-uuid-own-parser" in audit(case))

# 10. 界面满员时静默（点了没反应）
case = mutate("screen", "addStatus = fullStatus(ChargerFilterLimits.MAX_ITEM_ENTRIES);",
              "addStatus = null;")
counterexample("④b 界面满员不提示（点了没反应）⇒ screen-toggle-item-cap-silent",
      "screen-toggle-item-cap-silent" in audit(case))

# 10b. 界面自己写「先 remove 再无条件 add」（勾选框永远关不掉）—— 第 99 轮真写错过一次的形态
OLD_TOGGLE = (
    '            if (id != null\n'
    '                && !ChargerFilterLimits.toggleBounded(selectionOfCurrentTab(), id,\n'
    '                    ChargerFilterLimits.MAX_ITEM_ENTRIES)) {\n'
)
NEW_TOGGLE = (
    '            if (id != null) {\n'
    '                final Set<ResourceLocation> selection = selectionOfCurrentTab();\n'
    '                selection.remove(id);\n'
    '                selection.add(id);\n'
    '                if (false) {\n'
)
case = mutate("screen", OLD_TOGGLE, NEW_TOGGLE)
counterexample("④c 界面自己写「先 remove 再无条件 add」（勾选框关不掉）⇒ screen-toggle-own-add",
               "screen-toggle-own-add" in audit(case))

# 10c. toggleBounded 把 remove 挪到 add 之后（取消勾选被加回来）
OLD_REMOVE = (
    '        if (target.remove(value)) {\n'
    '            return true; // 本来就是勾上的 ⇒ 这次是取消\n'
    '        }\n'
)
NEW_REMOVE = (
    '        if (false) {\n'
    '            return true; // 本来就是勾上的 ⇒ 这次是取消\n'
    '        }\n'
)
case = mutate("limits", OLD_REMOVE, NEW_REMOVE)
counterexample("④d toggleBounded 丢了「先 remove」⇒ limits-toggle-order",
               "limits-toggle-order" in audit(case))

# 11. 语言键少一个 / 少了 %s 参数（提示行永远显示不出条数）—— 判据作用在临时改的 JSON 上
original_zh = read(os.path.join(LANG_DIR, "zh_cn.json"))
en_text = read(os.path.join(LANG_DIR, "en_us.json"))

zh_data = json.loads(original_zh)
zh_data.pop("gui.rs_create_compat.charger_filter.list.full")
counterexample("⑤a zh_cn 缺 list.full ⇒ lang-zh_cn-missing",
      any(p.startswith("lang-zh_cn-missing") for p in lang_problems_with(
          json.dumps(zh_data, ensure_ascii=False), en_text)))

zh_data = json.loads(original_zh)
zh_data["gui.rs_create_compat.charger_filter.list.full"] = "名单已满"
counterexample("⑤b 提示文案丢了 %s（永远显示不出条数）⇒ lang-zh_cn-no-placeholder",
      any(p.startswith("lang-zh_cn-no-placeholder") for p in lang_problems_with(
          json.dumps(zh_data, ensure_ascii=False), en_text)))

print("")
print("checks=%d counterexamples=%d" % (CHECKS[0], COUNTER[0]))
if FAILS:
    print("FAILED: " + ", ".join(FAILS))
    sys.exit(1)
print("SELFCHECK OK")
