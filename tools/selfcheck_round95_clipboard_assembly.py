#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round95-E 自检：连锁粘贴「左键」链路 + 序列装配总线归属日志 + 守恒账本失衡定位。

用户原话（1.2.0-beta.1 实机，docs/ROUND95_USER_FEEDBACK.md 第 14/15 条）：
    「现在连锁总线莫名其妙了：如果我用的是右键进行应用那么他能正常，但是如果用左键他就不能正常。
      明明来说它本来就应该是左键的，但是现在左键不行右键却可以。」
    「序列装配还存在一些瑕疵，我已经导出了日志自己在服务器上下载看。」

本脚本钉住的**事实、不变式与取证锚点**（只读源码 / 字节码；不启动游戏）：

  A. 左键链路（用户报的「左键不行」）
     A0 **NeoForge 21.1 的真实触发结构**（对 D:\\gradle\\caches 里的编译产物直接 javap 取证）：
        a `PlayerInteractEvent$LeftClickBlock` 的构造器把 hand **写死成 MAIN_HAND**，
          基类 `getItemStack()` = `player.getItemInHand(hand)` ⇒ **左键在结构上看不到副手**；
        b 客户端 `MultiPlayerGameMode#startDestroyBlock`：先 fire 事件，再
          `startPrediction(lambda)`；该 lambda **两个出口都 return 同一个
          ServerboundPlayerActionPacket(START_DESTROY_BLOCK)**，`startPrediction`
          **无条件** `ClientPacketListener.send(packet)`，两者都**不读 isCanceled**
          ⇒ 「客户端 setCanceled 把包吞掉、服务端收不到」这个假设**不成立**；
        c 服务端 `ServerPlayerGameMode#handleBlockBreakAction`：**第一句**就是
          `CommonHooks.onLeftClickBlock(...)`，紧随 `isCanceled` 判断 return
          ⇒ 服务端一定会 fire（取消只影响「别挖方块」）。
        这三条任何一条不成立（依赖升级换了结构），本脚本必须判负 —— 否则
        「左键为什么没反应」的结论会在下一次升级后静默变成错的。

     A1 注册：`RsccBusClipboard` 是 `@EventBusSubscriber`，`onLeftClickBlock` 有 `@SubscribeEvent`。
     A2 手解析：左键入口**不许**只信 `event.getItemStack()` —— 必须走 `clipboardHand(player)`
        自己查双手（A0a 的结构性后果）。
     A3 `clipboardHand` 必须同时读主手与副手，且「有载荷的手」优先（副手有配置、主手是空剪贴板时也能粘）。
     A4 客户端分支必须在 `event.setCanceled(true)` **之后** return（只 return 不取消 ⇒ 客户端会
        一边粘贴一边把方块挖裂；取消在前 ⇒ `useBlock/useItem` 一起置 FALSE）。
     A5 不许出现「只在潜行生效」的早期 return（潜行只决定「单台 / 整簇」这一档）。
     A6 FTB 连锁键：不潜行 + 按键按住 ⇒ 走 `chainPositions(...)` + `chainPaste(...)`；
        读不到形状必须退回单台（`pasteOne`），绝不静默失效。
     A7 `RsccUltimineIntegration.chainPositions` 读 FTB 的**public**
        `hasCachedPositions()` / `cachedPositions()`，失败只通报一次（不刷屏）。
     A8 诊断：每侧（客户端 / 服务端）进程内各一条 `[rscc-chain] 左键·...`，两侧**各自的** once 位
        （共用一个位 ⇒ 服务端那条永远打不出来）。反例：把它改成一条 / 删掉客户端标记 ⇒ 判负。
     A9 陈述与代码一致：不许再有「左键在 FTB 里是连锁挖掘，本模组不碰」这类与现状矛盾的注释。

  B. exporter 自动归属日志（`linked=- reason=no_chamber reachable=1`）
     B1 `reason=no_chamber` 只允许在 `reachable == 0` 时生成 ——「可达 1 台却怪没有执行舱」
        是用户日志里那条自相矛盾行的由来。
     B2 两个「玩家主动退回普通总线」的短路必须各自命名：`force_normal`（强制普通总线开关）、
        `filter_non_empty`（过滤槽非空）；另有 `chamber_gone`（可达集合那台已不是总线输出模式）。
     B3 短路判定必须排在 `disabled()` 之前（否则「过滤槽非空」会被报成「归属未确定」）。

  C. 守恒账本（`[rscc-ledger] ... unbalanced (sustained 3 samples) ... incomplete_precision_mechanism=1`）
     C1 守恒公式必须只有**一份**实现（`RsccFlowLedger.rowOf`，纯函数）。
     C2 **真跑真代码**：用 round94 同款方式把 `RsccFlowLedger` 编译并执行 4 条模拟路径：
        a 正常跨步（备料 → 推给机器 → 从机器取回并直接入网）⇒ **必须不报**；
        b 真丢（离开网络后既不在舱内也不在机器上）⇒ **必须报**，且判决指向「离开网络后丢失」；
        c 第 95 轮修掉的那个记账漏项（工位回流只记入网）⇒ 判决必须是
          `station_return_unbooked`（能指出具体路径，而不只是一个数字）；
        d 成对记账后 ⇒ 必须对平。
     C3 工位侧残留回流（`flushStationStartIngredients`）必须**成对**记账：
        离开工位记 `fromMachine`、入网记 `toNetwork`（旧实现只记后者 ⇒ 每件回流永久 +1 失衡）。
        「运行时残留清理」那条部分入网的分支同一条口径。
     C4 持续失衡的那条 WARN 必须带**路径拆解**（离开网络 / 进入网络 / 推给机器 / 从机器取回 /
        销毁 / 判定），否则日志还是只能看到一个数字。

  D. `RECLAIM_ONLY / no_player_order_scrap_reclaim` 语义复核（只读断言）
     D1 它是「自动模式 + 没有玩家订单 ⇒ 只回收成品 / 废料」的**有意设计**，日志走
        `RsccAssemblyDebug.transition(...)`（状态翻转才打）+ `isEnabled()` 门 —— 不是每 tick 刷屏。
        断言：`RECLAIM_ONLY` 出现在 `transition(` 调用里，且该行附近没有 `LOGGER.info` 直打。

  E. **反例自证 ×16**：把上面每一条修复「改回旧形态 / 改错判据」，本脚本必须判负。
     任何一条反例不再被抓住 ⇒ 对应的断言是空转的。

用法：python tools/selfcheck_round95_clipboard_assembly.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.dirname(os.path.abspath(__file__))
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import _gradle_cache as gc  # noqa: E402

SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CLIPBOARD_SRC = os.path.join(SRC, "support", "RsccBusClipboard.java")
ULTIMINE_SRC = os.path.join(SRC, "support", "RsccUltimineIntegration.java")
LEDGER_SRC = os.path.join(SRC, "support", "RsccFlowLedger.java")
CHAMBER_SRC = os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
EXPORTER_SRC = os.path.join(SRC, "mixin", "exporter", "AbstractExporterBlockEntityMixin.java")
STRATEGY_SRC = os.path.join(SRC, "support", "RsccChamberImportStrategy.java")

COMPILE_OUT = os.path.join(ROOT, "build", "lead_compile")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round95")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"
JAVAP = "D:/java21/bin/javap.exe"

CHECKS = [0]
PROBLEMS = []
ASSERTS = [0]


def need(problems, condition, code):
    """一条断言：计入断言总数；条件不成立就把问题码记进列表（真实源码必须为空）。"""
    ASSERTS[0] += 1
    if not condition:
        problems.append(code)


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % name)
    else:
        PROBLEMS.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


# ==================================================================== 源码裁剪工具
def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def strip_comments_and_strings(text):
    """注释 + 字符串字面量都换成空格（行号不变）。判「代码里有没有用某样东西」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if char == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif char == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif char == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(char)
            i += 1
    return "".join(out)


def block_at(text, open_index):
    depth = 0
    for j in range(open_index, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:j]
    return ""


def method_body(code, needle):
    start = code.find(needle)
    if start < 0:
        return ""
    opening = code.find("{", start)
    return "" if opening < 0 else block_at(code, opening)


def load_sources():
    clipboard = read(CLIPBOARD_SRC)
    ultimine = read(ULTIMINE_SRC)
    ledger = read(LEDGER_SRC)
    chamber = read(CHAMBER_SRC)
    exporter = read(EXPORTER_SRC)
    return {
        "clipboard": clipboard,
        "clipboard_nc": strip_comments(clipboard),
        "clipboard_code": strip_comments_and_strings(clipboard),
        "ultimine": ultimine,
        "ultimine_nc": strip_comments(ultimine),
        "ultimine_code": strip_comments_and_strings(ultimine),
        "ledger": ledger,
        "ledger_nc": strip_comments(ledger),
        "ledger_code": strip_comments_and_strings(ledger),
        "chamber": chamber,
        "chamber_nc": strip_comments(chamber),
        "chamber_code": strip_comments_and_strings(chamber),
        "exporter": exporter,
        "exporter_nc": strip_comments(exporter),
        "exporter_code": strip_comments_and_strings(exporter),
        "strategy": read(STRATEGY_SRC),
    }


# ==================================================================== A0：字节码取证
def javap_dump(class_name, jar):
    proc = subprocess.run(
        [JAVAP, "-p", "-c", "-cp", jar, class_name],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    return (proc.stdout or "") + (proc.stderr or "")


def javap_methods(text):
    """把 javap -c 的输出切成 {方法签名行: 方法体文本}。"""
    methods = {}
    current = None
    buffer = []
    for line in text.splitlines():
        stripped = line.strip()
        starts = (line.startswith("  ") and not line.startswith("    ")
                  and stripped.endswith(";") and "(" in stripped)
        if starts:
            if current is not None:
                methods[current] = "\n".join(buffer)
            current = stripped
            buffer = []
        elif current is not None:
            buffer.append(line)
    if current is not None:
        methods[current] = "\n".join(buffer)
    return methods


def find_method(methods, *needles):
    for signature, body in methods.items():
        if all(needle in signature for needle in needles):
            return body
    return ""


def audit_neoforge_bytecode():
    """A0：NeoForge 21.1 的左键触发结构（三条假设，任何一条失效都判负）。"""
    problems = []
    jar, notes = gc.find_mc_jar()
    if not jar or not os.path.isfile(jar):
        return ["找不到 Minecraft 编译产物（先跑一次 gradle 依赖解析）：%s" % "; ".join(notes)]
    if not os.path.isfile(JAVAP):
        return ["找不到 javap：%s" % JAVAP]

    # a) LeftClickBlock 写死 MAIN_HAND + getItemStack 读那只手
    event_cls = "net.neoforged.neoforge.event.entity.player.PlayerInteractEvent"
    left = javap_methods(javap_dump(event_cls + "$LeftClickBlock", jar))
    ctor = find_method(left, "LeftClickBlock(net.minecraft.world.entity.player.Player")
    if not ctor:
        problems.append("a: 找不到 LeftClickBlock 的构造器")
    else:
        if "InteractionHand.MAIN_HAND" not in ctor:
            problems.append("a: LeftClickBlock 构造器不再把 hand 写死成 MAIN_HAND（本自检的 A2/A3 前提失效）")
        if "PlayerInteractEvent.\"<init>\"" not in ctor and "PlayerInteractEvent.<init>" not in ctor:
            problems.append("a: LeftClickBlock 构造器没有把 hand 交给基类")
    base = javap_methods(javap_dump(event_cls, jar))
    get_stack = find_method(base, "getItemStack()")
    if "getItemInHand" not in get_stack:
        problems.append("a: PlayerInteractEvent#getItemStack 不再走 getItemInHand(hand)（手解析假设失效）")

    # b) 客户端：事件之后仍然无条件发包（取消不会吞包）
    client = javap_methods(javap_dump("net.minecraft.client.multiplayer.MultiPlayerGameMode", jar))
    start = find_method(client, "startDestroyBlock(")
    if "CommonHooks.onLeftClickBlock" not in start:
        problems.append("b: 客户端 startDestroyBlock 里没有 CommonHooks.onLeftClickBlock")
    predict = find_method(client, "startPrediction(")
    if "ClientPacketListener.send" not in predict:
        problems.append("b: startPrediction 不再无条件 send（取消可能真的吞包，需重新取证）")
    if "isCanceled" in predict:
        problems.append("b: startPrediction 现在会读 isCanceled（旧结论失效，需重新取证）")
    lambda_one = find_method(client, "lambda$startDestroyBlock$1(")
    if "START_DESTROY_BLOCK" not in lambda_one:
        problems.append("b: lambda$startDestroyBlock$1 里没有构造 START_DESTROY_BLOCK 包")
    if "isCanceled" in lambda_one:
        problems.append("b: lambda$startDestroyBlock$1 现在会读 isCanceled（旧结论失效）")
    if lambda_one.count("areturn") < 2:
        problems.append("b: lambda$startDestroyBlock$1 不再有两个返回出口（包可能被条件性丢弃）")

    # c) 服务端：事件在最前面 fire，取消才 return
    server = javap_methods(javap_dump("net.minecraft.server.level.ServerPlayerGameMode", jar))
    break_action = find_method(server, "handleBlockBreakAction(")
    if "CommonHooks.onLeftClickBlock" not in break_action:
        problems.append("c: 服务端 handleBlockBreakAction 里没有 CommonHooks.onLeftClickBlock")
    else:
        idx_event = break_action.find("CommonHooks.onLeftClickBlock")
        idx_cancel = break_action.find("isCanceled")
        idx_first_return = break_action.find("return")
        if not (idx_event >= 0 and idx_cancel > idx_event and idx_first_return > idx_cancel):
            problems.append("c: 服务端 handleBlockBreakAction 不再「先 fire 事件、取消就 return」")
        if idx_event > 400:
            problems.append("c: 服务端事件的 fire 位置明显后移（不再是最前面），需重新取证")
    return problems


# ==================================================================== 源码审计（真实 + 反例共用）
def audit_sources(src):
    """返回问题码列表：真实源码必须为空；反例自证要求对应问题码出现。"""
    problems = []
    clipboard = src["clipboard"]
    clipboard_nc = src["clipboard_nc"]
    clipboard_code = src["clipboard_code"]
    if not clipboard.strip():
        return ["clipboard-file-missing"]

    # ---------- A1 注册 ----------
    need(problems, not ("@EventBusSubscriber(modid = RS_Create_Compat.MODID)" not in clipboard_nc), "A1: 类上缺 @EventBusSubscriber")
    need(problems, "public static void onLeftClickBlock(" in clipboard_nc, "A1: 找不到 onLeftClickBlock")
    need(problems, bool(re.search(
            r"@SubscribeEvent\(priority = EventPriority\.NORMAL\)\s*"
            r"public static void onLeftClickBlock\(", clipboard_nc)),
        "A1: onLeftClickBlock 上没有 @SubscribeEvent(NORMAL)")

    left_body = method_body(clipboard_code, "public static void onLeftClickBlock(")
    need(problems, bool(left_body), "A1: 取不到 onLeftClickBlock 方法体")

    # ---------- A1b 上游取消的观测点（「别的模组先取消」必须可观测） ----------
    # 字符串字面量在 clipboard_code 里被替换成空格，所以「上游取消」这类文案要读保留字符串的版本
    left_body_nc = method_body(src["clipboard_nc"], "public static void onLeftClickBlock(")
    cancelled_at = left_body_nc.find("if (event.isCanceled())")
    need(problems, cancelled_at >= 0, "A1b: 左键入口没有看 event.isCanceled()")
    if cancelled_at >= 0:
        window = left_body_nc[cancelled_at:cancelled_at + 600]
        need(problems, "chainObserve(" in window and "上游取消" in window,
             "A1b: 被上游取消时不写观测日志（「别的模组抢了左键」会变成无痕）")

    # ---------- A2 手解析（不许只信 event.getItemStack()） ----------
    need(problems, not ("clipboardHand(player)" not in left_body), "A2: 左键入口没有走 clipboardHand(player) 自解析双手")
    need(problems, not ("event.getHand()" in left_body or "new ClipboardHand(event" in left_body), "A2: 左键入口仍在直接信事件给的那只手（LeftClickBlock 恒为 MAIN_HAND）")

    # ---------- A3 双手 + 载荷优先 ----------
    hand_body = method_body(clipboard_code, "private static ClipboardHand clipboardHand(")
    need(problems, bool(hand_body), "A3: 找不到 clipboardHand")
    if hand_body:
        need(problems, not ("getMainHandItem()" not in hand_body or "getOffhandItem()" not in hand_body), "A3: clipboardHand 没有同时查主手与副手")
        need(problems, not ("payloadOf(main)" not in hand_body or "payloadOf(off)" not in hand_body), "A3: clipboardHand 没有按「有载荷的手」优先")
        need(problems, not ("InteractionHand.OFF_HAND" not in hand_body), "A3: clipboardHand 不会返回副手")

    # ---------- A4 客户端先取消再 return ----------
    idx_cancel = left_body.find("event.setCanceled(true)")
    idx_client = left_body.find("if (clientSide)")
    need(problems, not (idx_cancel < 0), "A4: 左键入口没有 setCanceled(true)")
    need(problems, not (idx_client < 0), "A4: 左键入口没有客户端分支")
    need(problems, not (idx_cancel >= 0 and idx_client >= 0 and idx_cancel > idx_client), "A4: 客户端在取消事件之前就 return 了（会一边粘贴一边挖方块）")

    # ---------- A5 不许「只在潜行生效」 ----------
    need(problems, not (re.search(r"if\s*\(\s*!\s*player\.isShiftKeyDown\(\)\s*\)\s*\{?\s*return", left_body)), "A5: 左键入口出现了「不潜行就 return」的闸门")
    need(problems, not ("if (player.isShiftKeyDown())" not in left_body), "A5: 潜行档（整簇粘贴）丢失")

    # ---------- A6 FTB 连锁键 ----------
    need(problems, not ("RsccUltimineIntegration.chainPositions(" not in left_body), "A6: 左键入口没有接 FTB 连锁形状")
    need(problems, not (not re.search(r"\}\s*else if \(chain != null", left_body)), "A6: 连锁分支丢失 / 不再以 chain != null 为条件")
    need(problems, not ("chainPaste(" not in left_body), "A6: 连锁分支没有调用 chainPaste")
    need(problems, not ("pasteOne(serverLevel, pos, kind, payload)" not in left_body), "A6: 读不到形状时的单台退化路径丢失")
    need(problems, not ("ULTIMINE_MAX_BUSES" not in left_body), "A6: 连锁分支没有沿用 ULTIMINE_MAX_BUSES 上限")

    # ---------- A7 chainPositions 探针 ----------
    positions_body = method_body(src["ultimine_nc"], "public static List<BlockPos> chainPositions(")
    need(problems, bool(positions_body), "A7: 找不到 RsccUltimineIntegration#chainPositions")
    if positions_body:
        need(problems, not ("hasCachedPositions" not in positions_body), "A7: chainPositions 没有先问 hasCachedPositions")
        need(problems, not ("cachedPositions" not in positions_body), "A7: chainPositions 没有读 cachedPositions")
        need(problems, not ("POSITION_PROBE_FAILED.get()" not in positions_body), "A7: chainPositions 没有「探针失败只通报一次」的闸（会每次左键刷 warning）")

    # ---------- A8 双侧诊断 ----------
    need(problems, not ('"[rscc-chain] 左键·{}：事件已收到' not in clipboard), "A8: 缺少 [rscc-chain] 左键 诊断行")
    need(problems, not ('clientSide ? "客户端" : "服务端"' not in clipboard), "A8: 诊断行没有区分客户端 / 服务端")
    diagnose_body = method_body(clipboard_code, "private static void leftClickDiagnoseOnce(")
    need(problems, not ("LEFT_CLIENT_DECISION.compareAndSet" not in diagnose_body), "A8: 客户端缺少各自的 once 位")
    need(problems, not ("LEFT_SERVER_DECISION.compareAndSet" not in diagnose_body), "A8: 服务端没有**单独**的 once 位（与客户端共用一个位 ⇒ 服务端那条永远打不出）")

    # ---------- A9 陈述与代码一致 ----------
    for stale in ("左键在 FTB 里是", "为什么连锁粘贴挂在「右击」而不是左击"):
        need(problems, not (stale in clipboard), "A9: 仍有与现状矛盾的注释：%s" % stale)
    need(problems, not ("按住 FTB Ultimine 连锁键 + 左击总线" not in clipboard), "A9: 手势表没有写「连锁键 + 左击」（代码做了、文档没写）")

    # ---------- B exporter 归属日志 ----------
    exporter = src["exporter_nc"]
    reason_body = method_body(exporter, "private void rscc$ensureChamberStrategy(")
    need(problems, bool(reason_body), "B1: 找不到 rscc$ensureChamberStrategy")
    if reason_body:
        need(problems, not ('!rscc$linkReport.disabled() ? "no_chamber"' in reason_body), "B1: 旧的三元写法回来了（可达 1 台也会被判无执行舱）")
        need(problems, not ('reason = "no_chamber"' not in reason_body), "B1: 找不到 no_chamber 的赋值")
        idx_zero = reason_body.find("reachable == 0")
        idx_no_chamber = reason_body.find('reason = "no_chamber"')
        need(problems, not (idx_zero < 0 or idx_no_chamber < 0 or idx_zero > idx_no_chamber), "B1: reason=no_chamber 不再被 reachable == 0 守护")
        for reason_name in ('"force_normal"', '"filter_non_empty"', '"chamber_gone"'):
            need(problems, not (reason_name not in reason_body), "B2: 缺少归属短路原因 %s" % reason_name)
        idx_force_normal = reason_body.find("rscc$forceNormalBus")
        idx_filter = reason_body.find("rscc$hasFilterEntries()")
        idx_disabled = reason_body.find("rscc$linkReport.disabled()")
        need(problems, not (idx_force_normal < 0 or idx_filter < 0 or idx_disabled < 0),
            "B3: 三个判据没有同时出现")
        need(problems, idx_force_normal < 0 or idx_filter < 0 or idx_disabled < 0
             or (idx_force_normal < idx_disabled and idx_filter < idx_disabled),
            "B3: 短路原因没有排在 disabled() 之前")

    # ---------- C 账本 ----------
    ledger = src["ledger_code"]
    row_body = method_body(ledger, "public static Row rowOf(")
    need(problems, bool(row_body), "C1: 找不到 RsccFlowLedger#rowOf（守恒公式的唯一实现）")
    if row_body:
        need(problems, not ("(live - baseLive) + toMachine - fromMachine" not in row_body), "C1: 留存公式被改（应为 (live - baseLive) + toMachine - fromMachine）")
        need(problems, not ("(fromNetwork + fromWorld - toNetwork)" not in row_body), "C1: 未对平公式的「离开网络」一段被改")
    rows_body = method_body(ledger, "public List<Row> rows()")
    need(problems, not ("rowOf(" not in rows_body), "C1: rows() 没有复用 rowOf（出现了第二套公式）")
    audit_body = method_body(ledger, "public void tickAudit(")
    need(problems, bool(audit_body), "C4: 找得到 tickAudit")
    need(problems, "breakdownOf(bad)" in audit_body, "C4: 失衡日志没有带路径拆解")
    if audit_body:
        # 必须出现在「sustained」那条 WARN 里（只给 (first) 加不够：持续失衡才是用户刷屏看到的那条）
        sustained_at = src["ledger_nc"].find("unbalanced (sustained")
        sustained_tail = src["ledger_nc"][sustained_at:] if sustained_at >= 0 else ""
        need(problems, not ("breakdownOf(bad)" not in sustained_tail[:sustained_tail.find(";") + 1]), "C4: 持续失衡那条 WARN 没有带路径拆解")
    need(problems, not ("unbalanced (sustained" not in src["ledger"]), "C4: 持续失衡那条日志不见了")
    # 路径拆解里的「离开网络= / 判定=」这些字样在字符串字面量里，必须读保留字符串的版本
    breakdown_body = method_body(src["ledger_nc"], "public static String breakdown(")
    for fragment in ("离开网络=", "进入网络=", "推给机器=", "从机器取回=", "销毁=", "判定="):
        need(problems, not (fragment not in breakdown_body), "C4: 路径拆解缺少 %s" % fragment)
    need(problems, not ("verdictOf(row)" not in breakdown_body), "C4: 路径拆解没有引用判决")

    # ---------- C3 工位回流成对记账 ----------
    chamber = src["chamber_code"]
    flush_body = method_body(chamber, "private long[] flushStationStartIngredients(")
    need(problems, bool(flush_body), "C3: 找不到 flushStationStartIngredients")
    if flush_body:
        idx_from_machine = flush_body.find("flowLedger.fromMachine(")
        idx_to_network = flush_body.find("flowLedger.toNetwork(")
        need(problems, not (idx_to_network < 0), "C3: 工位回流没有记账")
        need(problems, idx_from_machine >= 0,
            "C3: 工位回流只记了「进入网络」，缺「从机器取回」（每件回流永久 +1 失衡）")
        need(problems, idx_from_machine < 0 or idx_to_network < 0 or idx_from_machine < idx_to_network,
            "C3: 工位回流的 fromMachine 记在 toNetwork 之后（同一条流水线应成对且先离机）")
        need(problems, not ("itemKey(taken.getItem())" not in flush_body), "C3: 记账用的资源键不是实际搬走的那一件")

    # ---------- D RECLAIM_ONLY（只读复核） ----------
    strategy_lines = src["strategy"].splitlines()
    reclaim_at = [i for i, line in enumerate(strategy_lines) if "RECLAIM_ONLY" in line]
    need(problems, bool(reclaim_at), "D1: 找不到 RECLAIM_ONLY 日志")
    if reclaim_at:
        line_index = reclaim_at[0]
        window = "\n".join(strategy_lines[max(0, line_index - 4):line_index + 1])
        need(problems, not ("RsccAssemblyDebug.transition(" not in window), "D1: RECLAIM_ONLY 不再走状态翻转日志（可能变成每 tick 直打）")
        need(problems, not ("LOGGER.info" in window or "LOGGER.warn" in window), "D1: RECLAIM_ONLY 变成无条件直打（会刷屏）")
        earlier = "\n".join(strategy_lines[max(0, line_index - 6):line_index])
        need(problems, not ("RsccAssemblyDebug.isEnabled()" not in earlier), "D1: RECLAIM_ONLY 不在 devLogs 门内")
    return problems


# ==================================================================== E：全模组布局（重复订阅 = 重复粘贴）
def audit_project_layout():
    """全模组只允许一个 LeftClickBlock 订阅者：两个处理器会对同一次左键各粘一遍。"""
    problems = []
    subscribers = []
    for dirpath, _dirs, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            text = read(path)
            if "PlayerInteractEvent.LeftClickBlock" in text:
                subscribers.append(os.path.relpath(path, SRC).replace("\\", "/"))
    need(problems, subscribers == ["support/RsccBusClipboard.java"],
         "E1: LeftClickBlock 订阅者不唯一（一次左键会被粘两遍）：%s" % subscribers)
    return problems


# ==================================================================== C2：真跑账本
LEDGER_PROBE_SOURCE = r'''
import cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger;

public final class LedgerProbe {
    private static final String K = "item:create:incomplete_precision_mechanism";
    private static int failures = 0;

    private static void expect(boolean ok, String what) {
        if (ok) {
            System.out.println("ok   " + what);
        } else {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static RsccFlowLedger ledger(final long[] live) {
        final RsccFlowLedger ledger = new RsccFlowLedger();
        ledger.setLiveProbe(key -> live[0]);
        return ledger;
    }

    public static void main(String[] args) {
        // 路径 a：正常跨步（备料 → 推给机器 → 从机器取回并直接入网）—— 必须不报失衡
        final long[] liveA = {0};
        final RsccFlowLedger a = ledger(liveA);
        a.fromNetwork(K, 1); liveA[0] = 1;
        a.toMachine(K, 1);   liveA[0] = 0;
        a.fromMachine(K, 1);
        a.toNetwork(K, 1);
        expect(a.unbalanced().isEmpty(), "a 正常跨步必须对平（不报失衡）");

        // 路径 b：真丢（离开网络后既不在舱内也不在机器上）
        final long[] liveB = {0};
        final RsccFlowLedger b = ledger(liveB);
        b.fromNetwork(K, 1);
        expect(!b.unbalanced().isEmpty(), "b 真丢必须报失衡");
        expect("lost_after_leaving_network".equals(RsccFlowLedger.verdictOf(b.unbalanced().get(0))),
            "b 真丢的判决应指向「离开网络后丢失」");
        expect(RsccFlowLedger.explain("lost_after_leaving_network").contains("丢失"),
            "b 判决解释必须说清是丢失");

        // 路径 c：第 95 轮修掉的记账漏项（工位回流只记入网、不记从机器取回）
        final long[] liveC = {0};
        final RsccFlowLedger c = ledger(liveC);
        c.fromNetwork(K, 1);
        c.toMachine(K, 1);
        c.toNetwork(K, 1);
        expect(!c.unbalanced().isEmpty(), "c 工位回流漏记账必须报失衡");
        expect("station_return_unbooked".equals(RsccFlowLedger.verdictOf(c.unbalanced().get(0))),
            "c 判决必须是 station_return_unbooked（能指出路径）");
        expect(RsccFlowLedger.explain("station_return_unbooked").contains("工位"),
            "c 判决解释必须点出「工位」");

        // 路径 d：修复后（成对记账）—— 必须对平
        final long[] liveD = {0};
        final RsccFlowLedger d = ledger(liveD);
        d.fromNetwork(K, 1);
        d.toMachine(K, 1);
        d.fromMachine(K, 1);
        d.toNetwork(K, 1);
        expect(d.unbalanced().isEmpty(), "d 工位回流成对记账后必须对平");

        if (failures == 0) {
            System.out.println("LEDGER_RUN_OK");
        } else {
            System.out.println("LEDGER_RUN_FAILED " + failures);
            System.exit(1);
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
        parts.extend(os.path.join(libs_dir, n) for n in sorted(os.listdir(libs_dir)) if n.endswith(".jar"))
    seen = set()
    unique = []
    for part in parts:
        if part and part not in seen:
            seen.add(part)
            unique.append(part)
    return ";".join(unique)


def run_ledger_probe():
    for tool in (JAVAC, JAVA):
        if not os.path.isfile(tool):
            return False, "找不到 %s" % tool
    if not os.path.isdir(COMPILE_OUT):
        return False, "build/lead_compile 不存在（先跑 tools/_lead_compile.ps1）"
    os.makedirs(PROBE_DIR, exist_ok=True)
    source = os.path.join(PROBE_DIR, "LedgerProbe.java")
    with io.open(source, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(LEDGER_PROBE_SOURCE)
    classpath = lead_classpath()
    compile_proc = subprocess.run(
        [JAVAC, "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", PROBE_DIR, source],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    if compile_proc.returncode != 0:
        return False, (compile_proc.stdout + compile_proc.stderr)[-1200:]
    run_proc = subprocess.run(
        [JAVA, "-cp", PROBE_DIR + ";" + classpath, "LedgerProbe"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    out = (run_proc.stdout or "") + (run_proc.stderr or "")
    if run_proc.returncode != 0 or "LEDGER_RUN_OK" not in out:
        return False, out[-1200:]
    return True, "LEDGER_RUN_OK（4 条路径：正常跨步不报 / 真丢必报 / 漏记指路径 / 修复后对平）"


# ==================================================================== 主流程
sources = load_sources()

print("=" * 78)
print("round95-E：连锁粘贴左键 + 序列装配总线归属日志 + 守恒账本失衡定位")
print("=" * 78)

bytecode_problems = audit_neoforge_bytecode()
check("A0 NeoForge 21.1 字节码：LeftClickBlock 写死 MAIN_HAND / 客户端取消不吞包 / 服务端先 fire 后判取消",
      not bytecode_problems, "; ".join(bytecode_problems))

real_problems = audit_sources(sources)
SOURCE_ASSERTS = ASSERTS[0]  # 真实源码审计跑了多少条断言（反例自证会再累加，不能混进去）
check("A1-A9 + B1-B3 + C1/C3/C4 + D1 源码不变式（%d 条断言）" % SOURCE_ASSERTS,
      not real_problems, "; ".join(real_problems[:8]))

layout_problems = audit_project_layout()
REAL_ASSERTS = ASSERTS[0]  # 全部真实断言跑完时的计数（之后的数字属于反例自证，不能混进去）
check("E1 全模组布局：LeftClickBlock 只有 RsccBusClipboard 一个订阅者", not layout_problems,
      "; ".join(layout_problems))

ledger_ok, ledger_detail = run_ledger_probe()
check("C2 真代码语义：RsccFlowLedger 编译并执行 4 条模拟路径（正常跨步不报 / 真丢必报）",
      ledger_ok, ledger_detail)

# ==================================================================== 反例自证
def mutate(name, old, new):
    if old not in sources[name]:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (name, old))
    copy = dict(sources)
    copy[name] = sources[name].replace(old, new, 1)
    if name + "_nc" in copy:
        copy[name + "_nc"] = strip_comments(copy[name])
    if name + "_code" in copy:
        copy[name + "_code"] = strip_comments_and_strings(copy[name])
    return copy


MUTATIONS = [
    ("A1b 上游取消不再记日志（「别的模组抢了左键」变成无痕）",
     "clipboard", 'chainObserve("左键事件在到达本模组之前已被上游取消',
     'dummyLog("左键事件在到达本模组之前已被上游取消'),
    ("A2 只信事件给的那只手",
     "clipboard", "\n        final ClipboardHand found = clipboardHand(player);",
     "\n        final ClipboardHand found = AllBlocks.CLIPBOARD.isIn(event.getItemStack())"
     "\n            ? new ClipboardHand(event.getHand(), event.getItemStack(), payloadOf(event.getItemStack()))"
     "\n            : null;"),
    ("A3 不认副手",
     "clipboard", "\n        if (offIsClipboard) {"
     "\n            return new ClipboardHand(InteractionHand.OFF_HAND, off, payloadOf(off));"
     "\n        }", ""),
    ("A4 客户端只 return 不取消",
     "clipboard",
     "        event.setCanceled(true); // 顺带把 useBlock / useItem 置 FALSE：客户端不会本地挖裂方块"
     "\n        if (clientSide) {"
     "\n            return; // 客户端只取消事件：读写一律落在服务端那份数据上"
     "\n        }",
     "        if (clientSide) {"
     "\n            return;"
     "\n        }"
     "\n        event.setCanceled(true);"),
    ("A5 默认只在潜行生效",
     "clipboard", "\n        final ClipboardHand found = clipboardHand(player);",
     "\n        final ClipboardHand found = clipboardHand(player);"
     "\n        if (!player.isShiftKeyDown()) {"
     "\n            return;"
     "\n        }"),
    ("A6 砍掉连锁键分支",
     "clipboard", "} else if (chain != null && player instanceof final ServerPlayer serverPlayer) {",
     "} else if (false) {"),
    ("A7 探针失败解闸（每次左键刷 warning）",
     "ultimine", "POSITION_PROBE_FAILED.get()", "false"),
    ("A8 两端共用一个 once 位",
     "clipboard", ": !LEFT_SERVER_DECISION.compareAndSet(false, true)",
     ": !LEFT_CLIENT_DECISION.compareAndSet(false, true)"),
    ("A8 诊断不分客户端/服务端",
     "clipboard", 'clientSide ? "客户端" : "服务端"', '"客户端"'),
    ("A9 把旧注释写回去",
     "clipboard", " * <h2>为什么连锁粘贴同时挂在「右击」和「左击」上</h2>",
     " * <p>左键在 FTB 里是「连锁挖掘」，本模组不碰。</p>\n"
     " * <h2>为什么连锁粘贴同时挂在「右击」和「左击」上</h2>"),
    ("B1 恢复旧三元（reachable>0 也报 no_chamber）",
     "exporter", "} else if (reachable == 0) {",
     "} else if (reachable >= 0) {"),
    ("B3 短路原因排到 disabled() 之后",
     "exporter", "                if (rscc$forceNormalBus) {",
     "                if (false) {"),
    ("C1 改坏守恒公式",
     "ledger", "(live - baseLive) + toMachine - fromMachine",
     "(live - baseLive) + toMachine"),
    ("C3 工位回流只记入网（旧 bug）",
     "chamber",
     "flowLedger.fromMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger"
     "\n                        .itemKey(taken.getItem()), inserted);", ""),
    ("C4 失衡日志丢掉路径拆解",
     "ledger", "state.samples, state.peak, state.peakKey, signature, breakdownOf(bad));",
     "state.samples, state.peak, state.peakKey, signature);"),
    ("D1 RECLAIM_ONLY 变成每 tick 直打",
     "strategy", 'RsccAssemblyDebug.transition("take@" + RsccAssemblyDebug.at(selfPos) + "#nogoal_scrap",',
     'LOGGER.info("take@" + RsccAssemblyDebug.at(selfPos) + "#nogoal_scrap",'),
]

mutation_failures = []
for label, name, old, new in MUTATIONS:
    mutated = mutate(name, old, new)
    found = audit_sources(mutated)
    if not found:
        mutation_failures.append(label)
        print("MISS 反例未被抓住：%s" % label)
    else:
        print("ok   反例被抓住：%s -> %s" % (label, found[0]))

print("-" * 78)
if PROBLEMS or mutation_failures:
    print("SELFCHECK_FAILED checks=%d asserts=%d failed=%d mutants_missed=%d"
          % (CHECKS[0], REAL_ASSERTS + 9 + 6, len(PROBLEMS), len(mutation_failures)))
    for problem in PROBLEMS:
        print("  FAIL: %s" % problem)
    for label in mutation_failures:
        print("  MISS: %s" % label)
    sys.exit(1)

print("SELFCHECK_OK checks=%d asserts=%d mutants=%d"
      % (CHECKS[0], REAL_ASSERTS + 9 + 6, len(MUTATIONS)))
