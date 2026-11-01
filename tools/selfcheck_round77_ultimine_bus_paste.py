# -*- coding: utf-8 -*-
"""第 77 轮：**FTB Ultimine 连锁 + Create 剪贴板批量粘贴总线配置**的防回归自检。

用户原话：「我突然想到一个很爽的东西，就是**连锁功能支持这个剪贴板的批量配置**。……
直接**使用连锁，然后框中的这所有的输入输出总线，然后直接一键配置**。哦对了，这个配置功能
**也应该包含关于是否开启自动模式的配置**，我指的是输入总线。」

本脚本固化的判据（全部是「不能悄悄退化」的硬点）：
    ① **服务端权威**：连锁粘贴只接受 `ServerPlayer`、只在 `player.serverLevel()` 里读写；
       FTB 的右键处理器先做 `instanceof ServerPlayer player` 再分派到剪贴板；不新增任何网络包，
       客户端没有直接改方块实体数据的路径；
    ② **上限只有一个来源**：`ULTIMINE_MAX_BUSES = BULK_MAX_BUSES`，写入方法引用它并与 FTB 的
       `maxBlocks` 取较小者；整份源码里 `64` 只出现一次（不写死两处），且写入循环里没有裸数字上限；
    ③ **去重**：`LinkedHashSet` + `targets.contains(pos)` —— 连锁候选里的重复坐标只处理一次；
    ④ **类型不符只跳过不写**：`!anchorKind.equals(kind)` 分支只 `mismatched++` 并 `continue`，
       位置必须在 `pasteOne(...)` 之前；
    ⑤ **自动模式在剪贴板读写两端都在**：输入总线 `writeBusConfig` 写 `KEY_AUTO`、
       `readBusConfig` 读回 `KEY_AUTO`；而连锁粘贴走的是同一个 `rscc$readBusConfig`，因此一并覆盖；
       输出总线没有「自动模式」开关，它独有的 `KEY_EXPLICIT` / 两侧共有的 `KEY_FORCE_NORMAL` 也逐项核对；
    ⑥ **逐块异常不中断整批**：`try { pasteOne(...) } catch (RuntimeException) { failed++; }`，
       catch 里没有 return / throw；
    ⑦ **反馈文案中英成对**：`pasted_ultimine` 三个占位符（成功 / 跳过 / 类型不符），源码里带三个实参；
    ⑦b **每一条「静默失败」都必须有话说**（第 82 轮补，用户实机「连锁也不行」的直接教训）：
       剪贴板里没配置 / 没权限 / 范围内没有同类总线 / 一条都没写进去 / 类型不符 —— 每条失败分支
       都必须在 `return` 之前 `displayClientMessage` 对应的键。判据解析真实分支结构，
       并把「静默 return 0」的反例喂给同一函数判负；
    ⑦c **反射注册失败有明确日志与降级**：状态机（NOT_INSTALLED / HOOKED / HANDLER_REGISTERED / FAILED）、
       register 与 registerHandler 两处失败都置 FAILED + 只记一次 warning、成功登记时记一条 INFO
       （这是「反射注册真的走通了」的运行期证据），并且玩家侧有一次「连锁不可用」的说明；
    ⑧ **反例自证**：把「无上限 / 无去重 / 客户端改数据 / 静默 return / 静默降级」的写法喂给**同一批**判据
       ⇒ 必须判负，证明这些判据不是「怎么写都通过」。

用法: python tools/selfcheck_round77_ultimine_bus_paste.py
退出码: 0 = 全部通过。
"""

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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

CLIP_REL = os.path.join("support", "RsccBusClipboard.java")
ULT_REL = os.path.join("support", "RsccUltimineIntegration.java")
CMD_REL = os.path.join("command", "CompatCommands.java")
IMP_REL = os.path.join("mixin", "importer", "AbstractImporterBlockEntityMixin.java")
EXP_REL = os.path.join("mixin", "exporter", "AbstractExporterBlockEntityMixin.java")

CHAIN_KEY = "message.rs_create_compat.bus_clipboard.pasted_ultimine"
CHAIN_SIG = "public static int chainPaste("
PASTE_ONE_CALL = "pasteOne(level, pos, kind, payload)"

# 第 82 轮：每一条「静默失败」路径都必须有一个玩家看得见的文案（键 → 源码里的常量名）。
LOUD_PATHS = (
    ("payload == null", "MSG_NO_PAYLOAD_COPIED",
     "message.rs_create_compat.bus_clipboard.no_payload_copied"),
    ("!player.mayBuild()", "MSG_CANNOT_BUILD",
     "message.rs_create_compat.bus_clipboard.cannot_build"),
    ("acceptsKind(payload, anchorKind)", "MSG_KIND_MISMATCH",
     "message.rs_create_compat.bus_clipboard.kind_mismatch"),
    ("applied == 0", "MSG_CHAIN_NONE",
     "message.rs_create_compat.bus_clipboard.pasted_ultimine_none"),
    ("targets.size() == 1", "MSG_CHAIN_NO_SIBLINGS",
     "message.rs_create_compat.bus_clipboard.pasted_ultimine_no_siblings"),
)
FTB_MISSING_KEY = "message.rs_create_compat.ultimine.not_installed"
FTB_FAILED_KEY = "message.rs_create_compat.ultimine.integration_failed"


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
    """去掉注释再断言：注释里写到这些名字不算「实现了」。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def body(code, signature):
    """从 `signature` 起截出配对的 `{...}`（用于只看某一个方法的实现）。"""
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


# ---------------------------------------------------------------------------
# 判据（纯函数：真实源码与反例改写都喂给这批函数）
# ---------------------------------------------------------------------------

def server_authoritative(integration, clipboard):
    """① 服务端权威：ServerPlayer / ServerLevel / 无客户端类型 / 无发包。"""
    chain = body(clipboard, CHAIN_SIG)
    dispatch = body(integration, "private static Object chain(")
    player_check = dispatch.find("instanceof ServerPlayer player")
    clipboard_call = dispatch.find("RsccBusClipboard.chainPaste(")
    return (
        "public static int chainPaste(final ServerPlayer player" in clipboard
        and "final ServerLevel level = player.serverLevel();" in chain
        and "player.mayBuild()" in chain
        and "net.minecraft.client" not in clipboard
        and "net.minecraft.client" not in integration
        and "PacketDistributor" not in clipboard
        and "sendToServer" not in clipboard
        and 0 <= player_check < clipboard_call
    )


def cap_single_source(clipboard):
    """②a 上限只有一个来源：整份源码里 `64` 只出现一次（BULK_MAX_BUSES 的定义）。"""
    return len(re.findall(r"\b64\b", clipboard)) == 1


def cap_referenced(clipboard):
    """②b 写入路径引用具名常量，且循环里没有裸数字上限。"""
    chain = body(clipboard, CHAIN_SIG)
    return (
        re.search(r"ULTIMINE_MAX_BUSES\s*=\s*BULK_MAX_BUSES\s*;", clipboard) is not None
        and "Math.min(limit, ULTIMINE_MAX_BUSES)" in chain
        and "targets.size() >= cap" in chain
        and re.search(r"(?:>=|>)\s*\d+\b", chain) is None
    )


def dedupe(clipboard):
    """③ 去重：保序集合 + 显式 contains 检查。"""
    chain = body(clipboard, CHAIN_SIG)
    return "new LinkedHashSet<>()" in chain and "targets.contains(pos)" in chain


def mismatch_only_skips(clipboard):
    """④ 类型不符：只 mismatched++ / continue，且在 pasteOne 之前。"""
    chain = body(clipboard, CHAIN_SIG)
    branch = re.search(
        r"if \(!anchorKind\.equals\(kind\)\) \{\s*mismatched\+\+;\s*continue;\s*\}", chain)
    paste_at = chain.find(PASTE_ONE_CALL)
    branch_at = chain.find("if (!anchorKind.equals(kind))")
    return (branch is not None and "acceptsKind(payload, anchorKind)" in chain
            and 0 <= branch_at < paste_at)


def per_block_isolation(clipboard):
    """⑤ 逐块 try/catch：catch 里只 failed++ 并留一次带堆栈的 WARN，整批继续、不 return / 不 throw。

    第 88 轮加严：以前 catch 里只有 `failed++`（异常彻底被吞，玩家只看到「少写了一台」）；
    现在必须把异常交给 `pasteWriteFailure(pos, kind, e)`，且那个函数第一次带堆栈、不受 devLogs 控制
    （Gated 反例见 ⑪）。
    """
    chain = body(clipboard, CHAIN_SIG)
    match = re.search(
        r"try \{\s*if \(pasteOne\(level, pos, kind, payload\)\) \{\s*applied\+\+;\s*\}\s*"
        r"else \{\s*failed\+\+;\s*\}\s*\}\s*"
        r"catch \(final RuntimeException e\) \{(?P<catch>.*?)\}",
        chain, flags=re.S)
    if match is None:
        return False
    catch = match.group("catch")
    return ("failed++;" in catch
            and "pasteWriteFailure(pos, kind, e)" in catch
            and "return" not in catch and "throw" not in catch)


def auto_mode_two_way(importer):
    """⑥ 自动模式在剪贴板写入 / 读取两端都在（输入总线）。"""
    write = body(importer, "public void rscc$writeBusConfig(")
    back = body(importer, "public void rscc$readBusConfig(")
    return ("tag.putBoolean(RsccBusConfig.KEY_AUTO, rscc$autoCollect)" in write
            and "rscc$setAutoCollect(tag.getBoolean(RsccBusConfig.KEY_AUTO))" in back)


def output_side_switches(exporter):
    """⑥b 输出总线没有自动模式；它独有的/共有的开关必须双向齐全。"""
    write = body(exporter, "public void rscc$writeBusConfig(")
    back = body(exporter, "public void rscc$readBusConfig(")
    return ("KEY_EXPLICIT" in write and "KEY_EXPLICIT" in back
            and "KEY_FORCE_NORMAL" in write and "KEY_FORCE_NORMAL" in back
            and "KEY_AUTO" not in exporter)


def guarded_message(src, guard, key):
    """⑦b 某个失败分支里：在下一个 `return` 之前必须 displayClientMessage 对应的键。

    做法：从 guard 的出现位置切到它后面的第一个 `return`（失败分支一定以 return 结束），
    要求这一段里既有 `displayClientMessage`、又引用了那个常量名。
    这样「写了一条死代码消息但分支里还是静默 return」也会被判负。
    """
    idx = src.find(guard)
    if idx < 0:
        return False
    end = src.find("return", idx)
    segment = src[idx:] if end < 0 else src[idx:end]
    return "displayClientMessage" in segment and key in segment


def failure_paths_loud(clipboard):
    """⑦b 五条失败分支全部有话说（缺一条就判负）。"""
    chain = body(clipboard, CHAIN_SIG)
    if not chain:
        return False
    return all(guarded_message(chain, guard, key) for guard, key, _lang in LOUD_PATHS)


def integration_diagnostics(integration):
    """⑦c 反射注册的状态机 + 失败日志/降级 + 玩家侧说明。"""
    return (
        "public enum State" in integration
        and "state = State.NOT_INSTALLED" in integration
        and "state = State.HOOKED" in integration
        and "state = State.HANDLER_REGISTERED" in integration
        # 两处失败点（挂监听器 / 登记处理器）都必须置 FAILED
        and integration.count("state = State.FAILED") >= 2
        and "public static String unavailableMessageKey()" in integration
        and "MSG_NOT_INSTALLED" in integration
        and "MSG_INTEGRATION_FAILED" in integration
        # 成功登记时要有一条 INFO：这是「反射注册真的走通」的运行期证据
        and "LOGGER.info" in integration
        and "register event observed" in integration
        # 失败只记一次，不刷屏
        and "warnOnce(" in integration
    )


def feedback_key_ok(clipboard, zh, en):
    """⑦ 一句结果型反馈：中英各三个占位符，源码带三个实参。"""
    return (CHAIN_KEY in zh and CHAIN_KEY in en
            and zh[CHAIN_KEY].count("%s") == 3
            and en[CHAIN_KEY].count("%s") == 3
            and "MSG_CHAIN_PASTED" in clipboard
            and re.search(r"Component\.translatable\(MSG_CHAIN_PASTED,\s*"
                          r"applied, skipped \+ failed, mismatched\)", clipboard) is not None)


# ---------------------------------------------------------------------------
# 真实源码
# ---------------------------------------------------------------------------
CLIP_RAW = read(CLIP_REL)
ULT_RAW = read(ULT_REL)
CMD_RAW = read(CMD_REL)
IMP_RAW = read(IMP_REL)
EXP_RAW = read(EXP_REL)

CLIP = code_only(CLIP_RAW)
ULT = code_only(ULT_RAW)
CMD = code_only(CMD_RAW)
IMP = code_only(IMP_RAW)
EXP = code_only(EXP_RAW)

ZH = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
EN = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))

print("=" * 100)
print("第 77 轮：FTB Ultimine 连锁 + 剪贴板批量粘贴总线配置")
print("=" * 100)

# ---------------------------------------------------------------- 入口 / 手势
check("RsccBusClipboard.chainPaste(player, hand, anchor, candidates, maxBlocks.intValue())" in ULT,
      "入口 1 FTB 右键连锁处理器按「手里的物品」分派：剪贴板 → RsccBusClipboard.chainPaste")
check("AllBlocks.CLIPBOARD.isIn(held)" in body(ULT, "private static Object chain("),
      "入口 2 只有拿着 Create 剪贴板才走连锁粘贴（框架链路不受影响）")
check("RsccSheathChain.chainSheath(player, hand, anchor, candidates, maxBlocks.intValue())" in ULT,
      "入口 3 非剪贴板仍走既有框架连锁（一次右键只做一件事）")
check(re.search(r"@SubscribeEvent\(priority = EventPriority\.LOW\)\s*public static void onRightClickBlock",
                CLIP_RAW) is not None,
      "入口 4 复制挂点排在 Architectury(HIGH) 的连锁分发之后（FTB 取消后不再覆盖剪贴板）")

# ---------------------------------------------------------------- ① 服务端权威
check(server_authoritative(ULT, CLIP),
      "①1 服务端权威：ServerPlayer + player.serverLevel() + 分派在 ServerPlayer 判定之后 + 无客户端类型 / 无发包")
check("private static boolean pasteOne(final ServerLevel level" in CLIP,
      "①2 真正写方块实体的 pasteOne 只接受 ServerLevel（客户端拿不到写入入口）")
check("instanceof final ServerLevel serverLevel" in body(CLIP, "public static void onLeftClickBlock("),
      "①3 既有的单台 / 整簇粘贴仍然只在 ServerLevel 分支内写入（未被本次改动破坏）")

# ---------------------------------------------------------------- ② 上限
check(cap_single_source(CLIP), "②1 上限只有一个来源：整份源码里 `64` 只出现一次",
      "出现次数=%d" % len(re.findall(r"\b64\b", CLIP)))
check(cap_referenced(CLIP),
      "②2 写入路径引用 ULTIMINE_MAX_BUSES（= BULK_MAX_BUSES），并与 FTB 的 maxBlocks 取较小者；循环内无裸数字上限")

# ---------------------------------------------------------------- ③ 去重
check(dedupe(CLIP), "③ 连锁候选去重：LinkedHashSet + targets.contains(pos)")

# ---------------------------------------------------------------- ④ 类型不符
check(mismatch_only_skips(CLIP),
      "④ 类型不符只 mismatched++ / continue，且分支位于 pasteOne(...) 之前（绝不乱写）")
check("RsccBusConfig.acceptsKind(payload, anchorKind)" in body(CLIP, CHAIN_SIG),
      "④b 闸门在写入前：载荷的版本 / 种类必须先通过 acceptsKind")

# ---------------------------------------------------------------- ⑤ 逐块容错
check(per_block_isolation(CLIP),
      "⑤ 逐块 try/catch：单块异常只 failed++，整批继续、异常不冒回 FTB")

# ---------------------------------------------------------------- ⑥ 自动模式双向
check(auto_mode_two_way(IMP),
      "⑥1 输入总线自动模式在剪贴板写入 / 读取两端都在（KEY_AUTO）")
check("rscc$readBusConfig(payload, level.registryAccess())" in body(CLIP, "private static boolean pasteOne("),
      "⑥2 连锁粘贴复用 rscc$readBusConfig ⇒ 自动模式自动随载荷写入，无需第二条写路径")
check(output_side_switches(EXP),
      "⑥3 输出总线没有自动模式（KEY_AUTO 不存在）；它的 KEY_EXPLICIT 与两侧的 KEY_FORCE_NORMAL 双向齐全")
check("tag.putBoolean(RsccBusConfig.KEY_FORCE_NORMAL, rscc$forceNormalBus)" in body(IMP, "public void rscc$writeBusConfig(")
      and "rscc$setForceNormalBus(tag.getBoolean(RsccBusConfig.KEY_FORCE_NORMAL))" in body(IMP, "public void rscc$readBusConfig("),
      "⑥4 输入总线的「强制普通总线」也双向齐全（与输出总线共用同一个 KEY_FORCE_NORMAL）")

# ---------------------------------------------------------------- ⑦ 反馈
check(feedback_key_ok(CLIP, ZH, EN), "⑦ 反馈键中英各 3 个占位符，源码带 3 个实参",
      repr(ZH.get(CHAIN_KEY, "<缺失>")))
check(len(ZH.get(CHAIN_KEY, "")) <= 40, "⑦2 中文 ≤40 字", "长度=%d" % len(ZH.get(CHAIN_KEY, "")))

# ---------------------------------------------------------------- ⑦b 静默失败 = 0 条
print("-" * 100)
print("⑦b 每一条失败路径都必须告诉玩家原因（用户反馈「按了没反应」的直接教训）")
check(failure_paths_loud(CLIP),
      "⑦b1 五条失败分支（没配置 / 没权限 / 类型不符 / 一条没写 / 范围内无同类）都在 return 前 displayClientMessage")
for guard, const, lang_key in LOUD_PATHS:
    check(guarded_message(body(CLIP, CHAIN_SIG), guard, const) and lang_key in ZH and lang_key in EN,
          "⑦b2 分支「%s」→ %s 中英都在（%s）" % (guard, const, lang_key))
check("MSG_NO_PAYLOAD_COPIED" in CLIP and "copyInto(level, anchor, anchorKind, clipboard)" in body(CLIP, CHAIN_SIG),
      "⑦b3 剪贴板里没配置时不是静默返回：先 copyInto 把命中这条复制进去，再提示下一步")
check(ZH.get("message.rs_create_compat.bus_clipboard.no_payload_copied", "").count("%s") == 1
      and EN.get("message.rs_create_compat.bus_clipboard.no_payload_copied", "").count("%s") == 1,
      "⑦b4 「没配置」文案中英各恰好 1 个占位符（方块名）")
_LOUD_KEYS = [lang_key for _g, _c, lang_key in LOUD_PATHS] + [FTB_MISSING_KEY, FTB_FAILED_KEY]
check(all(ZH.get(k, "").count("%s") == EN.get(k, "").count("%s") for k in _LOUD_KEYS),
      "⑦b5 新增的 %d 条玩家文案：中英占位符数量一一相等" % len(_LOUD_KEYS))

# ---------------------------------------------------------------- ⑦c 反射注册的可观测性
print("-" * 100)
print("⑦c 反射注册：状态机 + 失败日志/降级 + 玩家侧说明")
check(integration_diagnostics(ULT),
      "⑦c1 状态机四态 + 两处失败都置 FAILED + 成功登记记 INFO + 失败 warnOnce 只记一次")
check(FTB_MISSING_KEY in ZH and FTB_MISSING_KEY in EN
      and FTB_FAILED_KEY in ZH and FTB_FAILED_KEY in EN,
      "⑦c2 「未安装 / 联动失败」两条玩家文案中英都在")
check("unavailableMessageKey()" in CLIP and "notifyUltimineAvailabilityOnce" in CLIP,
      "⑦c3 玩家侧确实会用这两个键（复制完配置后每人每局提示一次）")
check(body(CLIP, "private static void notifyUltimineAvailabilityOnce(").count("ULTIMINE_NOTICE_SENT.add") == 1,
      "⑦c4 提示只发一次（不会每次复制都刷）")
check("state = State.UNKNOWN" in ULT and "case \"toString\"" in ULT,
      "⑦c5 未跑 register() 时状态是 UNKNOWN（不误报），且代理的 Object 方法不再返回 null")

# ---------------------------------------------------------------- ⑧ 反例自证
print("-" * 100)
print("⑧ 反例自证：把「无上限 / 无去重 / 客户端改数据 / 静默 return / 静默降级」喂给同一批判据")

NO_CAP = CLIP.replace("final int cap = Math.min(limit, ULTIMINE_MAX_BUSES);", "final int cap = limit;")
NO_DEDUPE = CLIP.replace("targets.contains(pos)", "false")
CLIENT_WRITE = CLIP.replace(
    "public static int chainPaste(final ServerPlayer player,", 
    "public static int chainPaste(final net.minecraft.world.entity.player.Player player,").replace(
    "final ServerLevel level = player.serverLevel();", "final Level level = player.level();")
# 反例：把「剪贴板里没配置」改回上一版的静默 return 0（用户实机遇到的正是这个）。
# 只在 chainPaste 的方法体里替换 —— 否则会先命中 onLeftClickBlock 里同名的那句（判据会假失败）。
_CHAIN_BODY = body(CLIP, CHAIN_SIG)
_SILENT_BODY = re.sub(
    r"if \(payload == null\) \{.*?\n        \}",
    "if (payload == null) {\n            return 0;\n        }",
    _CHAIN_BODY, count=1, flags=re.S)
SILENT_PAYLOAD = CLIP.replace(_CHAIN_BODY, _SILENT_BODY)
# 反例：注册失败时静默 return，不置状态、不记日志
BAD_INTEGRATION = re.sub(r"catch \(final ReflectiveOperationException \| RuntimeException \| LinkageError e\) \{.*?\n        \}",
                         "catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {\n"
                         "            return;\n        }",
                         ULT, count=1, flags=re.S)

check(NO_CAP != CLIP and not cap_referenced(NO_CAP),
      "⑧1 反例「无上限」：cap_referenced 判负（裸 limit 当上限）")
check(NO_DEDUPE != CLIP and not dedupe(NO_DEDUPE),
      "⑧2 反例「无去重」：dedupe 判负（既不保序也不查重）")
check(CLIENT_WRITE != CLIP and not server_authoritative(ULT, CLIENT_WRITE),
      "⑧3 反例「客户端改数据」：server_authoritative 判负（Player / player.level()）")
check(SILENT_PAYLOAD != CLIP and not failure_paths_loud(SILENT_PAYLOAD),
      "⑧4 反例「没配置就静默 return 0」：failure_paths_loud 判负（这正是用户遇到的「按了没反应」）")
check(BAD_INTEGRATION != ULT and not integration_diagnostics(BAD_INTEGRATION),
      "⑧5 反例「注册失败静默 return」：integration_diagnostics 判负（没有 FAILED / 没有日志）")

# 对照组：反例只应打掉对应判据，不能把别的判据也带崩（否则说明判据互相污染）
check(dedupe(CLIP) and cap_referenced(CLIP) and server_authoritative(ULT, CLIP)
      and failure_paths_loud(CLIP) and integration_diagnostics(ULT),
      "⑧6 对照组：五批判据在真实源码上全部通过（反例失败不是因为判据本身写死）")

# ---------------------------------------------------------------- ⑨ round83 运行时可观测性
print("-" * 100)
print("⑨ round83：连锁「断在哪一环」的观测点（handler 被调用 / 参数 / 各跳过原因 / 最终写入 / 异常堆栈）")
#
# 用户实机反馈「连锁还是没生效」，而上一轮的运行期证据只到「处理器注册成功」。从那一步到
# 「方块真的被改了」之间还有 6 道闸（按 FTB 2101.1.15 字节码逐条核对）：
#   ① 我们的代理被 RightClickDispatcher#dispatchRightClick 真的调到了吗
#   ② ShapeContext 的字段 + args[2] 候选集合真的被填了吗
#   ③ 手里是剪贴板 / 框架吗（不是就交回 FTB）
#   ④ 命中格是本模组总线吗（不是就静默交回 —— 正常路径，不能误判成故障）
#   ⑤ 各种跳过原因（权限 / 种类 / 距离 / 未加载 / 上限）各几次
#   ⑥ 最终写进去几条
# 判据要求：每一环都有日志；首次被调用那条**不受 devLogs 控制**（否则用户不开开关就又是一片空白）；
# 其余细节受 devLogs + 1 秒窗口节流（不刷屏）；异常必须至少一次带堆栈的 WARN。

CHAIN_TAGS = (
    "①handler 被调用",
    "②handler 参数类型不符",
    "②chainPaste 进入",
    "③剪贴板里没有本模组配置",
    "④连锁粘贴完成",
    "⑥剪贴板连锁结束",
)


def chain_log_violations(ult_raw, clip_raw):
    """连锁每个环节都必须有日志；且「①handler 被调用」首次不受 devLogs 控制。"""
    v = []
    joined = ult_raw + "\n" + clip_raw
    for tag in CHAIN_TAGS:
        if tag not in joined:
            v.append("缺观测点：%s" % tag)
    # ① 必须走 chainLog(firstAlways=True)
    if 'chainLog(true, "①handler 被调用' not in ult_raw:
        v.append("①handler 被调用 没有走 firstAlways=true（首次不会被无条件打出来）")
    log_body = body(code_only(ult_raw), "private static void chainLog(")
    if "HANDLER_CALL_SEEN.compareAndSet(false, true)" not in log_body:
        v.append("chainLog 没有「首次」判定（every-call 都打 = 刷屏，或全被开关挡住 = 失明）")
    if "RsccAssemblyDebug.isEnabled()" not in log_body:
        v.append("chainLog 没有接 devLogs 总开关（细节日志会一直刷）")
    if "CHAIN_LOG_WINDOW_MS" not in log_body or "CHAIN_LOG_SUPPRESSED" not in log_body:
        v.append("chainLog 没有时间窗口节流 / 没汇总被压条数")
    if "上一窗口压掉" not in log_body:
        v.append("chainLog 没把被压掉的条数汇总出来（等于静默丢日志）")
    return v


def chain_silence_violations(ult_raw, clip_raw):
    """chain() 里的每一条 return 0 都必须先留下「为什么」——0 本身合法，静默的 0 才是 bug。"""
    v = []
    chain_body = body(code_only(ult_raw), "private static Object chain(")
    for m in re.finditer(r"\breturn\s+0\s*;", chain_body):
        head = chain_body[max(0, m.start() - 600):m.start()]
        if "chainLog(" not in head:
            v.append("chain() 里有一处 return 0 之前 600 字符内没有 chainLog（静默返回 0）")
    chain_paste = body(code_only(clip_raw), "public static int chainPaste(")
    for m in re.finditer(r"\breturn\s+0\s*;", chain_paste):
        head = chain_paste[max(0, m.start() - 600):m.start()]
        if "chainObserve(" not in head:
            v.append("chainPaste() 里有一处 return 0 之前 600 字符内没有 chainObserve（静默返回 0）")
    return v


def chain_exception_violations(ult_raw):
    """处理器抛异常必须至少有一次带堆栈的 WARN（否则就是「异常被吞 ⇒ 永远返回 0 ⇒ 没反应」）。"""
    v = []
    if "handlerFailure(" not in ult_raw:
        v.append("没有 handlerFailure 汇总（异常会被 warnOnce 吞掉，第 2 次起完全静默）")
    fail_body = body(code_only(ult_raw), "private static void handlerFailure(")
    if "LOGGER.warn(" not in fail_body or ", cause)" not in fail_body:
        v.append("handlerFailure 第一次没有把 Throwable 一起交给 LOGGER.warn（= 没有堆栈）")
    if "count == 1" not in fail_body:
        v.append("handlerFailure 没有「第一次带堆栈」的分支")
    return v


def chain_probe_violations(ult_raw, clip_raw):
    """FTB 的「连锁键是否按住」必须在我们的右键入口被读出来（这是区分两侧闸门的唯一手段）。"""
    v = []
    if "public static Boolean ultimineKeyPressed(" not in ult_raw:
        v.append("没有 ultimineKeyPressed 探针（无法区分「FTB 认为键没按住」与「我们没写」）")
    probe = body(code_only(ult_raw), "public static Boolean ultimineKeyPressed(")
    if "isPressed" not in probe or "getOrCreatePlayerData" not in probe:
        v.append("探针没有读 FTBUltiminePlayerData#isPressed（读的不是同一个闸）")
    if "PRESS_PROBE_FAILED" not in probe:
        v.append("探针失败没有「只通报一次」的闸（会在每次右键刷屏）")
    on_click = body(code_only(clip_raw), "public static void onRightClickBlock(")
    if "ftbPressed(" not in on_click:
        v.append("复制挂点没有把 FTB 键状态写进日志")
    if "FTB 没有派发连锁" not in clip_raw:
        v.append("缺少「右键未被取消 ⇒ FTB 没派发连锁」这条决定性日志")
    return v


v9a = chain_log_violations(ULT_RAW, CLIP_RAW)
check(not v9a, "⑨1 六个环节的观测点齐全，且「①handler 被调用」首次不受 devLogs 控制",
      "违规=%s" % (v9a or "无"))
v9b = chain_silence_violations(ULT_RAW, CLIP_RAW)
check(not v9b, "⑨2 两处入口的每一句 return 0 之前都有「为什么」日志（0 合法，静默的 0 才是 bug）",
      "违规=%s" % (v9b or "无"))
v9c = chain_exception_violations(ULT_RAW)
check(not v9c, "⑨3 处理器异常：第一次带堆栈的 WARN，之后按次数汇总（不再第 2 次起静默）",
      "违规=%s" % (v9c or "无"))
v9d = chain_probe_violations(ULT_RAW, CLIP_RAW)
check(not v9d, "⑨4 FTB 键状态探针存在，并写进「未被取消 ⇒ 没派发」那条决定性日志",
      "违规=%s" % (v9d or "无"))
check(ULT_RAW.count("CHAIN_LOG_WINDOW_MS") >= 2 and CLIP_RAW.count("CHAIN_LOG_WINDOW_MS") >= 2,
      "⑨5 两个文件都有 1 秒窗口节流常量（不是每按一次打一条）")

# 反例自证：把观测点删掉 / 让首次也受开关控制 / 去掉探针 / 静默 return ⇒ 必须判负
_NO_TAG = ULT_RAW.replace(
    '"①handler 被调用但参数不足（args=%d）⇒ 返回 0（FTB 交回下一个处理器）"', '"bad args"').replace(
    '"①handler 被调用：hand=%s anchor=%s 候选=%d 上限=%d 手持=%s"', '"dispatched"')
check(_NO_TAG != ULT_RAW and bool(chain_log_violations(_NO_TAG, CLIP_RAW)),
      "⑨6 反例必须判负：删掉「①handler 被调用」这一条",
      "违规=%s" % (chain_log_violations(_NO_TAG, CLIP_RAW) or "居然通过了！"))
_ALWAYS_GATED = ULT_RAW.replace('chainLog(true, "①handler 被调用', 'chainLog(false, "①handler 被调用')
check(_ALWAYS_GATED != ULT_RAW and bool(chain_log_violations(_ALWAYS_GATED, CLIP_RAW)),
      "⑨7 反例必须判负：让「①handler 被调用」也受 devLogs 控制（首次就不再无条件）",
      "违规=%s" % (chain_log_violations(_ALWAYS_GATED, CLIP_RAW) or "居然通过了！"))
_SILENT_RET = CLIP_RAW.replace(
    '            chainObserve("②chainPaste 提前返回：FTB 上限 limit=%d（<=0）⇒ 这次不连锁任何方块", limit);\n'
    '            return 0;', "            return 0;")
check(_SILENT_RET != CLIP_RAW and bool(chain_silence_violations(ULT_RAW, _SILENT_RET)),
      "⑨8 反例必须判负：把某一处 return 0 改回静默（上一轮「按了没反应」的元凶）",
      "违规=%s" % (chain_silence_violations(ULT_RAW, _SILENT_RET) or "居然通过了！"))
_NO_PROBE = CLIP_RAW.replace("⇒ FTB 没有派发连锁；", "⇒ 上游未接手；")
check(_NO_PROBE != CLIP_RAW and bool(chain_probe_violations(ULT_RAW, _NO_PROBE)),
      "⑨9 反例必须判负：去掉「未被取消 ⇒ FTB 没派发」的决定性日志",
      "违规=%s" % (chain_probe_violations(ULT_RAW, _NO_PROBE) or "居然通过了！"))
check(not chain_log_violations(ULT_RAW, CLIP_RAW) and not chain_silence_violations(ULT_RAW, CLIP_RAW)
      and not chain_exception_violations(ULT_RAW) and not chain_probe_violations(ULT_RAW, CLIP_RAW),
      "⑨10 同一批判据对真实源码判正（避免「只要判负」的假绿）")

# ---------------------------------------------------------------- ⑩ round87「总线分支不被抢」+ 线缆簇兜底
#
# 用户实机：「方块这个填充的连锁是正常的……整个配置信息的连锁还是有问题，还是不可以连锁。」
# 材质能连、配置不能 ⇒ 分派 / 权限 / 距离都是通的；差异只在**目标方块能不能被 FTB 的同方块洪泛
# 走到**。这一节把两件事固化成可复跑判据：
#   ① 总线分支必须排第一，并且无论返回值是多少都在框架 / 材质之前 return（结构上不可能被抢）；
#   ② FTB 形状一个同类都给不出来时，沿同一条线缆簇补齐同种总线（种类 / 距离 / 上限逐条复检）。

def dispatch_order_violations(ult_raw):
    """剪贴板分支必须是第一支，并在框架 / 材质之前直接 return applied。"""
    v = []
    chain = body(code_only(ult_raw), "private static Object chain(")
    if not chain:
        return ["找不到 chain() 分派入口"]
    clip = chain.find("RsccBusClipboard.chainPaste(")
    sheath = chain.find("RsccSheathChain.chainSheath(")
    material = chain.find("RsccCamouflageChain.chainApplyMaterial(")
    guard = chain.find("AllBlocks.CLIPBOARD.isIn(held)")
    ret = chain.find("return applied;", clip) if clip >= 0 else -1
    if clip < 0:
        v.append("chain() 里没有剪贴板分支")
    if not (0 <= guard < clip):
        v.append("剪贴板分支前没有 AllBlocks.CLIPBOARD.isIn(held) 守卫（分派依据不是手里的物品）")
    if not (0 <= clip < sheath < material):
        v.append("分派顺序不是「剪贴板 → 框架 → 材质」（总线分支被前面的分支抢掉了）")
    if ret < 0 or ret > material:
        v.append("剪贴板分支没有在材质分支之前直接 return（0 会被后两支再猜一次）")
    return v


def cluster_fallback_violations(clip_raw):
    """总线之间隔着线缆 ⇒ FTB 候选只剩命中格；必须沿线缆簇补齐，且边界一条不少。"""
    v = []
    cp = body(code_only(clip_raw), "public static int chainPaste(")
    if not cp:
        return ["找不到 chainPaste()"]
    call = cp.find("RsccWireLinkSearch.searchChamberLink(level, anchor).cluster()")
    if call < 0:
        v.append("形状里没有同类总线时没有沿线缆簇补齐（总线之间隔着线缆 ⇒ 永远只写命中一格）")
    gate = cp.find("if (sameKindFromShape == 0)")
    if gate < 0 or call < gate:
        v.append("线缆簇补齐没有「仅当形状里一个同类都没有」的门控（会无端扩大写入范围）")
    if call >= 0:
        window = cp[call:call + 1400]
        if "anchorKind.equals(kindAt(level, pos))" not in window:
            v.append("线缆簇补齐没有按种类过滤（线缆 / 另一种总线会被当成目标）")
        if "withinReach(player, pos)" not in window or "level.isLoaded(pos)" not in window:
            v.append("线缆簇补齐没有逐块距离 / 加载校验")
        if "targets.size() >= cap" not in window:
            v.append("线缆簇补齐没有台数上限")
    return v


def first_decision_violations(clip_raw):
    """「总线分支走到哪一步」必须留一条**不受 devLogs 控制**的日志（每进程一次）。"""
    v = []
    if "CHAIN_FIRST_DECISION" not in clip_raw:
        v.append("没有「剪贴板连锁首次判定」的无条件日志（用户不开 devLogs 时日志仍然一片空白）")
    fn = body(code_only(clip_raw), "private static void logFirstDecision(")
    if not fn:
        v.append("找不到 logFirstDecision 实现")
        return v
    if "compareAndSet(false, true)" not in fn:
        v.append("首次判定日志没有「每进程一次」的闸（会每次右键刷一条）")
    if "LOGGER.info(" not in fn:
        v.append("首次判定日志不是 INFO（默认级别看不到）")
    if "RsccAssemblyDebug.isEnabled()" in fn or "chainObserve(" in fn:
        v.append("首次判定日志仍然受 devLogs 控制（等于没有观测点）")
    if "payload.getString(RsccBusConfig.KEY_KIND)" not in fn:
        v.append("首次判定日志没写「剪贴板载荷自称是哪种总线」（分不清种类不符）")
    return v


BAD_DISPATCH_ORDER = """
class BadDispatch {
    private static Object chain(Object[] args) {
        final ItemStack held = player.getItemInHand(hand);
        final int framed = RsccSheathChain.chainSheath(player, hand, anchor, candidates, maxBlocks.intValue());
        if (framed > 0) {
            return framed;
        }
        if (AllBlocks.CLIPBOARD.isIn(held)) {
            final int applied = RsccBusClipboard.chainPaste(player, hand, anchor, candidates, maxBlocks.intValue());
            return applied;
        }
        final int material = RsccCamouflageChain.chainApplyMaterial(player, hand, anchor, candidates, maxBlocks.intValue());
        return material;
    }
}
"""
# 反例：删掉线缆簇兜底 / 去掉「仅当形状里无同类」的门控 / 让首次判定日志也受开关控制
NO_CLUSTER = CLIP_RAW.replace(
    "RsccWireLinkSearch.searchChamberLink(level, anchor).cluster()",
    "java.util.List.<BlockPos>of()")
NO_CLUSTER_GATE = CLIP_RAW.replace("if (sameKindFromShape == 0) {", "if (true) {")
GATED_FIRST_DECISION = CLIP_RAW.replace(
    'LOGGER.info("[rscc-chain] 剪贴板连锁首次判定',
    'chainObserve("剪贴板连锁首次判定')


v10a = dispatch_order_violations(ULT_RAW)
check(not v10a,
      "⑩1 总线分支排第一，并在框架 / 材质之前直接 return（结构上不可能被抢）",
      "违规=%s" % (v10a or "无"))
v10b = cluster_fallback_violations(CLIP_RAW)
check(not v10b,
      "⑩2 FTB 形状给不出同类时沿线缆簇补齐；种类 / 距离 / 上限逐条复检",
      "违规=%s" % (v10b or "无"))
v10c = first_decision_violations(CLIP_RAW)
check(not v10c,
      "⑩3 「剪贴板连锁首次判定」无条件日志（每进程一次，不受 devLogs 控制）",
      "违规=%s" % (v10c or "无"))
check(bool(dispatch_order_violations(BAD_DISPATCH_ORDER)),
      "⑩4 反例必须判负：把剪贴板分支挪到框架之后（材质能连、配置被抢）",
      "违规=%s" % (dispatch_order_violations(BAD_DISPATCH_ORDER) or "居然通过了！"))
check(NO_CLUSTER != CLIP_RAW and bool(cluster_fallback_violations(NO_CLUSTER)),
      "⑩5 反例必须判负：删掉线缆簇兜底（= 用户遇到的「只改命中一格」）",
      "违规=%s" % (cluster_fallback_violations(NO_CLUSTER) or "居然通过了！"))
check(NO_CLUSTER_GATE != CLIP_RAW and bool(cluster_fallback_violations(NO_CLUSTER_GATE)),
      "⑩6 反例必须判负：去掉「仅当形状里无同类」的门控（无端扩大写入范围）",
      "违规=%s" % (cluster_fallback_violations(NO_CLUSTER_GATE) or "居然通过了！"))
check(GATED_FIRST_DECISION != CLIP_RAW and bool(first_decision_violations(GATED_FIRST_DECISION)),
      "⑩7 反例必须判负：让首次判定日志也受 devLogs 控制",
      "违规=%s" % (first_decision_violations(GATED_FIRST_DECISION) or "居然通过了！"))
check(not v10a and not v10b and not v10c,
      "⑩8 同一批判据对真实源码判正（避免「只要判负」的假绿）")

# ---------------------------------------------------------------- ⑪ round88：「不看日志也能判断」
#
# 用户实机：开了 devLogs、按了一次连锁键，latest.log 里**连一行 [rscc-chain-bus] 都没有**
# ⇒ 最可能是 FTB 压根没把右键派发到我们。因此这一轮要证明的不是「日志更多」，而是：
#   ① 一条指令回报四类事实（FTB 装没装 / 注册状态 / 它认为键按没按 / 手持与潜行）；
#   ② 异常第一次带完整堆栈 WARN，且**不受 devLogs 控制**（处理器 + 单块写入两处）；
#   ③ 初始化有一条无条件 INFO（state=...）；
#   ④ 有一条完全不经过 FTB 的保底手势（潜行 + 右键整簇粘贴），且不与既有手势冲突。
# 反例：把「异常全吞」「只有 devLogs 才能看出问题」喂给同一批判据 ⇒ 必须判负。

FOUR_FACTS = (
    "联动注册状态: ",
    "处理器是否被 FTB 调用过",
    "FTB 认为连锁键按住",
    "手持(主手/副手)",
)


def command_debug_violations(cmds_raw, ult_raw, clip_raw):
    """⑪1 /rs_create_compat chain-debug 存在，且回报四类信息（源码级）。"""
    v = []
    cmds = code_only(cmds_raw)
    ult = code_only(ult_raw)
    clip = code_only(clip_raw)
    if 'Commands.literal("chain-debug")' not in cmds:
        v.append("没有 /rs_create_compat chain-debug 指令")
    if "reportChainDebug" not in cmds:
        v.append("chain-debug 没有处理器")
    if "ModList.get()" not in cmds or "isLoaded(OptionalDeps.MOD_FTB_ULTIMINE)" not in cmds:
        v.append("指令没有直接问 ModList「FTB Ultimine 装没装」")
    if "RsccUltimineIntegration.debugReport(player)" not in cmds:
        v.append("指令没有回报 RsccUltimineIntegration 的注册状态 / 键状态 / 手持")
    if "RsccBusClipboard.debugReport(player)" not in cmds:
        v.append("指令没有回报剪贴板 / 保底这一侧")
    report = body(cmds, "private static int reportChainDebug(")
    if "source.sendSuccess(" not in report:
        v.append("reportChainDebug 没有把结果发给玩家")
    for label in FOUR_FACTS:
        if label not in ult:
            v.append("debugReport 缺一类回报：%s" % label)
    dbg = body(ult, "public static List<String> debugReport(")
    if not dbg:
        v.append("RsccUltimineIntegration 没有 debugReport")
    else:
        if "stateMeaning(current)" not in dbg:
            v.append("debugReport 没有把 State 翻译成人话")
        if "ultimineKeyPressed(player)" not in dbg:
            v.append("debugReport 没读 FTBUltiminePlayerData#isPressed（第三类）")
        if "getMainHandItem" not in dbg or "isShiftKeyDown()" not in dbg:
            v.append("debugReport 没报手持 / 潜行（第四类）")
        if "RsccAssemblyDebug.isEnabled()" in dbg:
            v.append("debugReport 受 devLogs 控制（关掉开关就看不到结论）")
    cdbg = body(clip, "public static List<String> debugReport(")
    if not cdbg:
        v.append("RsccBusClipboard 没有 debugReport")
    elif "sneakFallbackEnabled()" not in cdbg or "payloadOf(" not in cdbg:
        v.append("剪贴板诊断没报保底开关 / 剪贴板里有没有配置")
    return v


def exception_visible_violations(ult_raw, clip_raw):
    """⑪2 异常不再全吞：第一次带完整堆栈 WARN、按次数汇总、且不受 devLogs 控制。"""
    v = []
    ult = code_only(ult_raw)
    clip = code_only(clip_raw)
    handler = body(ult, "private static void handlerFailure(")
    if not handler:
        v.append("没有 handlerFailure（处理器异常会被吞掉）")
    else:
        if "LOGGER.warn(" not in handler or ", cause)" not in handler:
            v.append("handlerFailure 第一次没把 Throwable 交给 LOGGER.warn（= 没有堆栈）")
        if "count == 1" not in handler:
            v.append("handlerFailure 没有「第一次带堆栈」的分支")
        if "RsccAssemblyDebug" in handler or "chainLog(" in handler:
            v.append("处理器异常日志受 devLogs 控制（关掉开关就看不见）")
    call = body(ult, "private static Object onHandlerCall(")
    if "handlerFailure(method.getName(), e)" not in call:
        v.append("处理器 catch 没有把异常交给 handlerFailure（异常仍被吞）")
    block = body(clip, "private static void pasteWriteFailure(")
    if not block:
        v.append("单块写入异常仍被全吞（没有 pasteWriteFailure）")
    else:
        if "LOGGER.warn(" not in block or ", cause)" not in block:
            v.append("pasteWriteFailure 第一次没有堆栈")
        if "count == 1" not in block:
            v.append("pasteWriteFailure 没有「第一次带堆栈」的分支")
        if "RsccAssemblyDebug" in block or "chainObserve(" in block:
            v.append("单块写入异常受 devLogs 控制")
    chain = body(clip, CHAIN_SIG)
    if "pasteWriteFailure(pos, kind, e)" not in chain:
        v.append("chainPaste 的 catch 仍只 failed++（异常被吞）")
    return v


def init_info_violations(ult_raw):
    """⑪3 初始化有无条件 INFO（固定前缀 [rscc-ultimine] + state=...）。"""
    v = []
    ult = code_only(ult_raw)
    fn = body(ult, "private static void logInitState(")
    if not fn:
        v.append("没有初始化 INFO（logInitState）")
        return v
    if "LOGGER.info(" not in fn:
        v.append("初始化状态不是 INFO（默认级别看不到）")
    if "stateMeaning(state)" not in fn:
        v.append("初始化 INFO 没把状态翻译成人话")
    if "RsccAssemblyDebug" in fn or "chainLog(" in fn:
        v.append("初始化 INFO 受 devLogs 控制（关掉开关就看不见）")
    if ult.count("logInitState(") < 3:
        v.append("初始化 INFO 没有覆盖全部结局（未安装 / 已挂 / 失败）")
    return v


def fallback_violations(cmds_raw, clip_raw):
    """⑪4 保底：潜行 + 右键整簇粘贴存在、默认开、可关，且四道前置不抢既有右键。"""
    v = []
    cmd = code_only(cmds_raw)
    clip = code_only(clip_raw)
    if re.search(r"sneakFallbackEnabled\s*=\s*true\s*;", clip) is None:
        v.append("保底没有「默认开启」的字段")
    if "public static boolean sneakFallbackEnabled()" not in clip:
        v.append("没有保底开关的读取器")
    if "public static void setSneakFallbackEnabled(final boolean enable)" not in clip:
        v.append("没有保底开关的设置器")
    right = body(clip, "public static void onRightClickBlock(")
    if "if (player.isShiftKeyDown())" not in right or "sneakClusterPaste(event, player);" not in right:
        v.append("右击入口没有把「潜行 + 右键」交给保底（等于保底不存在）")
    fb = body(clip, "private static void sneakClusterPaste(")
    if not fb:
        v.append("没有 sneakClusterPaste 实现")
    else:
        for needle, why in (
            ("AllBlocks.CLIPBOARD.isIn(clipboard)", "没检查手里是不是剪贴板"),
            ("payloadOf(clipboard)", "没检查剪贴板里有没有本模组配置"),
            ("kindAt(level, pos)", "没检查命中格是不是本模组总线"),
            ("player.mayBuild()", "没有权限闸"),
            ("pasteCluster(serverLevel, pos, kind, payload)", "没有复用既有 pasteCluster"),
            ("event.setCanceled(true)", "没有取消事件（总线界面会照常弹出）"),
        ):
            if needle not in fb:
                v.append("保底缺少：%s（%s）" % (why, needle))
        payload_at = fb.find("if (payload == null)")
        cancel_at = fb.find("event.setCanceled(true)")
        if payload_at < 0 or cancel_at < 0 or payload_at > cancel_at:
            v.append("空剪贴板也会被接管（payload 判空必须在取消事件之前）")
        if "copyInto(" in fb:
            v.append("保底里混进了复制路径（会覆盖玩家剪贴板）")
    left = body(clip, "public static void onLeftClickBlock(")
    if "player.isShiftKeyDown()" not in left or "pasteCluster(serverLevel, pos, kind, payload)" not in left:
        v.append("既有的「潜行 + 左键整簇」被破坏")
    if "SNEAK_FALLBACK_USED" not in clip or "logSneakFallbackUsedOnce" not in clip:
        v.append("保底生效时没有一次 INFO 证据")
    if 'Commands.literal("fallback")' not in cmd or "setSneakFallbackEnabled(enable)" not in cmd:
        v.append("指令没有提供 fallback on|off")
    return v


print("-" * 100)
print("⑪ round88：不看日志也能判断（chain-debug / 异常带堆栈 / 初始化 INFO / 不依赖 FTB 的保底）")
v11a = command_debug_violations(CMD_RAW, ULT_RAW, CLIP_RAW)
check(not v11a,
      "⑪1 /rs_create_compat chain-debug 存在，回报四类：ModList / State / isPressed / 手持与潜行",
      "违规=%s" % (v11a or "无"))
v11b = exception_visible_violations(ULT_RAW, CLIP_RAW)
check(not v11b,
      "⑪2 异常不再全吞：处理器 + 单块写入第一次都带完整堆栈 WARN，且不受 devLogs 控制",
      "违规=%s" % (v11b or "无"))
v11c = init_info_violations(ULT_RAW)
check(not v11c,
      "⑪3 初始化有无条件 INFO（[rscc-ultimine] state=...；未安装 / 已挂 / 失败三种结局都覆盖）",
      "违规=%s" % (v11c or "无"))
v11d = fallback_violations(CMD_RAW, CLIP_RAW)
check(not v11d,
      "⑪4 保底：潜行 + 右键整簇粘贴存在、默认开、可关，四道前置不抢既有右键，左键语义未动",
      "违规=%s" % (v11d or "无"))

# 反例自证：删指令 / 异常全吞 / 日志被 devLogs 挡住 / 删保底 ⇒ 必须判负
NO_CMD = CMD_RAW.replace('Commands.literal("chain-debug")', 'Commands.literal("chain-trace")')
check(NO_CMD != CMD_RAW and bool(command_debug_violations(NO_CMD, ULT_RAW, CLIP_RAW)),
      "⑪5 反例必须判负：指令不存在（用户又只能去翻日志）",
      "违规=%s" % (command_debug_violations(NO_CMD, ULT_RAW, CLIP_RAW) or "居然通过了！"))
SWALLOW_HANDLER = ULT_RAW.replace("handlerFailure(method.getName(), e);", "")
check(SWALLOW_HANDLER != ULT_RAW and bool(exception_visible_violations(SWALLOW_HANDLER, CLIP_RAW)),
      "⑪6 反例必须判负：处理器异常全吞（catch 里什么都不留）",
      "违规=%s" % (exception_visible_violations(SWALLOW_HANDLER, CLIP_RAW) or "居然通过了！"))
DEVLOG_GATED = ULT_RAW.replace(
    "final int count = HANDLER_FAILURES.incrementAndGet();",
    "if (!RsccAssemblyDebug.isEnabled()) {\n            return;\n        }\n"
    "        final int count = HANDLER_FAILURES.incrementAndGet();")
check(DEVLOG_GATED != ULT_RAW and bool(exception_visible_violations(DEVLOG_GATED, CLIP_RAW)),
      "⑪7 反例必须判负：异常日志只有 devLogs 开着才看得见",
      "违规=%s" % (exception_visible_violations(DEVLOG_GATED, CLIP_RAW) or "居然通过了！"))
INIT_GATED = ULT_RAW.replace(
    'LOGGER.info("[rscc-ultimine] {}：state={}（{}），FTB Ultimine 已加载={}；"',
    'if (RsccAssemblyDebug.isEnabled()) LOGGER.info('
    '"[rscc-ultimine] {}：state={}（{}），FTB Ultimine 已加载={}；"')
check(INIT_GATED != ULT_RAW and bool(init_info_violations(INIT_GATED)),
      "⑪8 反例必须判负：初始化 INFO 也被 devLogs 挡住（用户不开日志就看不到联动在不在）",
      "违规=%s" % (init_info_violations(INIT_GATED) or "居然通过了！"))
NO_FALLBACK = CLIP_RAW.replace("sneakClusterPaste(event, player);", "return;")
check(NO_FALLBACK != CLIP_RAW and bool(fallback_violations(CMD_RAW, NO_FALLBACK)),
      "⑪9 反例必须判负：删掉保底（FTB 不派发时又回到「按了没反应」）",
      "违规=%s" % (fallback_violations(CMD_RAW, NO_FALLBACK) or "居然通过了！"))
EMPTY_TAKES_OVER = CLIP_RAW.replace(
    "final CompoundTag payload = payloadOf(clipboard);\n"
    "        if (payload == null) {\n"
    "            return; // 前置 2",
    "final CompoundTag payload = new CompoundTag();\n"
    "        if (false) {\n"
    "            return; // 前置 2")
check(EMPTY_TAKES_OVER != CLIP_RAW and bool(fallback_violations(CMD_RAW, EMPTY_TAKES_OVER)),
      "⑪10 反例必须判负：空剪贴板也抢潜行右键（会挡住总线界面，与既有语义冲突）",
      "违规=%s" % (fallback_violations(CMD_RAW, EMPTY_TAKES_OVER) or "居然通过了！"))
check(not v11a and not v11b and not v11c and not v11d,
      "⑪11 同一批判据对真实源码判正（避免「只要判负」的假绿）")

print("=" * 100)
print("检查项: %d；问题: %d" % (CHECKS[0], len(PROBLEMS)))
if PROBLEMS:
    for item in PROBLEMS:
        print("  [FAIL] %s" % item)
    sys.exit(1)
print("[result] 自检通过")
sys.exit(0)
