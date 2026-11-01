# -*- coding: utf-8 -*-
"""round72：终端快捷键的「按键 → 发包」链路上不允许存在任何「位置 / 移动 / 输入向量」前提，
也不允许存在**静默失败**（不写日志的 return）。

用户实机反馈（逐字）：
    「那个终端按下去就是完全没反应，直到我移动一下，这个是确定的，然后没有红字。」
上一轮把「客户端没发包」当成结论去查服务端，本自检把这一层彻底钉死：

  A. 链路接线：KeyMapping 在 MOD 总线注册、按键事件在**游戏总线**注册（少一处就整键失效）
  B. 处理器体内**没有**任何依赖玩家位置 / 移动 / 输入向量的条件（源码级判据 + 列出检查的量）
  C. 发包语句不在任何 if/else 里（通过守卫后无条件发包），且顺序是 consumeClick → mark → send
  D. 每一条早退分支要么写日志，要么用 `silent-ok` 显式标注（全方法最多一处，且必须是「非本键」那条）
  E. 反例自证：把**修前的写法**（静默 return、无诊断行）和三种植入式「移动门禁」喂给同一批判据
     ⇒ 必须全部判负（判据不是恒真的橡皮图章）
  G. round82（借鉴 Universal Grid 0.3.2 反汇编）：按住重复事件（GLFW_REPEAT）不得再发一条打开请求
     —— 原版 KeyboardHandler 对 action != 0（按下 **和重复**）都会 KeyMapping.click()，
     而 ServerPlayer#openMenu 每次都会先 closeContainer() ⇒ 按住 G = 界面被反复拆掉重建。
     判据：重复判定必须存在、必须在唯一那条发包之前、必须清计数且留日志、全方法只允许一处发包。
  H. round82：服务端打开链路不允许存在「没界面也没红字」的静默出口
     （旧 useFromShortcut 的 `if (stackOpt.isEmpty()) return;` 正是这种出口）；
     并要求打开之后做一次**事后校验**（containerMenu 仍是玩家背包 ⇒ 打节流 ERROR + 界面提示），
     且该校验不补发第二个请求、不引入任何定时器（不是「定时刷新」式绕过）。

用法: python tools/selfcheck_round72_terminal_key_send.py
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

KEYBIND = os.path.join(PKG, "client", "TerminalKeybinds.java")
INTENT = os.path.join(PKG, "client", "TerminalOpenIntent.java")
CLIENT_INIT = os.path.join(PKG, "client", "ClientInit.java")
PACKET = os.path.join(PKG, "network", "OpenAdvancedRemoteTerminalPacket.java")

PROBLEMS = []
CHECKS = [0]


def read(path):
    with io.open(path, "r", encoding="utf-8") as f:
        return f.read()


def code_only(src):
    """剥掉注释：只对真实代码做判定（注释里会引用旧写法、也会出现被禁的词汇）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def code_padded(src):
    """把注释替换成**等长空白**（保留换行与全部偏移）：这样「代码」判定看不到注释，
    但基于偏移的窗口仍能在同一位置读到原始文本里的 `silent-ok` 标注。"""
    def blank(match):
        return re.sub(r"[^\n]", " ", match.group(0))

    src = re.sub(r"/\*.*?\*/", blank, src, flags=re.S)
    return re.sub(r"//[^\n]*", blank, src)


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def java_body(src, signature):
    """取以 signature 开头的方法的花括号体（含花括号，按括号配平）。"""
    start = src.find(signature)
    if start < 0:
        return ""
    open_idx = src.find("{", start)
    if open_idx < 0:
        return ""
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_idx:i + 1]
    return ""


# ---------------------------------------------------------------------------
# 判据主体（可复用于真实源码 / 修前写法 / 植入式反例）：传入 onKeyInput 的方法体。
# ---------------------------------------------------------------------------

# 关注的量：这些一旦出现在「按键 → 发包」的判定里，就等于把「能不能发包」挂在会随走动变化的东西上。
#   位置            position() / blockPosition() / xo yo zo（上一 tick 坐标） / xLast yLast1 zLast
#   移动            walkDist / walkDistO / hasMoved / deltaMovement / getDeltaMovement
#   输入向量        zza / xxa / jumping / shiftKeyDown / isSprinting
#   脚下方块        onGround / isOnGround
#   射程 / 射线     distanceToSqr / distanceTo / clip / pick / hasLineOfSight
#   世界状态        isLoaded / getBlockEntity / getChunk / hasChunkAt / level()
#   操作时间戳      getLastActionTime / resetLastActionTime
MOVE_POSITION_TOKENS = (
    "position()", "blockPosition(", "blockPosition()", ".xo", ".yo", ".zo",
    "xLast", "yLast1", "zLast",
    "walkDist", "walkDistO", "hasMoved", "deltaMovement", "getDeltaMovement",
    "zza", "xxa", "isSprinting", "onGround()", "isOnGround",
    "distanceToSqr", "distanceTo(", "hasLineOfSight", "clip(", "pick(",
    "isLoaded(", "getBlockEntity(", "getChunk(", "hasChunkAt", "level()",
    "getLastActionTime", "resetLastActionTime",
)

LOG_TOKENS = ("debug(", "LOGGER.", "NOISE.log(", "openLog(", "chainLog(", "chainObserve(")


def enclosing_block_flags(body, index):
    """返回 index 处被哪些**块**包着：[(块头文本, 'if' 或 'try' 或 'method')]。"""
    stack = []
    for i in range(0, min(index, len(body))):
        ch = body[i]
        if ch == "{":
            head = body[max(0, i - 240):i]
            head = head[head.rfind("}") + 1:] if "}" in head else head
            kind = "other"
            if re.search(r"(\bif\s*\(|\belse\b|\bfor\s*\(|\bwhile\s*\()", head):
                kind = "if"
            elif re.search(r"\btry\b", head):
                kind = "try"
            elif re.search(r"\bcatch\s*\(", head):
                kind = "catch"
            stack.append((i, kind, head.strip()[-60:]))
        elif ch == "}":
            if stack:
                stack.pop()
    return stack


def find_returns(body):
    return [m.start() for m in re.finditer(r"\breturn\s*;", body)]


def inspect_key_handler(body):
    """把「按键 → 发包」这一层要死守的不变量算成违规清单（空表示通过）。

    `body` 必须是**原始**方法体（含注释）：判定用等长去注释副本 `body_code`，
    而 `silent-ok` 标注要到原始文本里读（它是注释，判定副本里已被抹成空白）。
    """
    body_code = code_padded(body)
    violations = []

    # B：位置 / 移动 / 输入向量
    hits = [t for t in MOVE_POSITION_TOKENS if t in body_code]
    if hits:
        violations.append("B 判定里出现位置/移动/输入向量量: %s" % ",".join(sorted(set(hits))))

    # C：发包必须在顶层（不被 if/else 包住），且 try 里有 catch+日志
    send_at = body_code.find("sendToServer")
    if send_at < 0:
        violations.append("C 方法体里找不到 sendToServer")
    else:
        kinds = [k for (_i, k, _h) in enclosing_block_flags(body_code, send_at)]
        if "if" in kinds:
            violations.append("C 发包被 if/else 包住（= 存在「不满足就走不到发包」的条件）")
    if "sendToServer" in body_code and "catch (final RuntimeException" not in body_code:
        violations.append("C 发包没有 try/catch：失败会静默消失（或直接崩）")
    if "sendToServer" in body_code and "LOGGER.warn(" not in body_code:
        violations.append("C 发包失败分支没有日志")

    # C/顺序：consumeClick → mark → sendToServer
    i_consume = body_code.find("consumeClick")
    if i_consume < 0:
        # round83：tick 轮询把 consumeClick 收进了 drainClicks 里（唯一调用点），判定跟着换名
        i_consume = body_code.find("drainClicks(")
    i_mark = body_code.find("TerminalOpenIntent.mark()")
    i_send = body_code.find("sendToServer")
    if not (0 <= i_consume < i_mark < i_send):
        violations.append("C 顺序不是 consumeClick → mark → sendToServer (consume=%d mark=%d send=%d)"
                          % (i_consume, i_mark, i_send))

    # D：早退分支必须留日志，或被显式标注 silent-ok
    silent = []
    for ret in find_returns(body_code):
        blocks = enclosing_block_flags(body_code, ret)
        if not blocks:
            silent.append(ret)
            continue
        block_start = blocks[-1][0]
        has_log = any(tok in body_code[block_start:ret] for tok in LOG_TOKENS)
        marked_ok = "silent-ok" in body[block_start:ret]
        if not (has_log or marked_ok):
            silent.append(ret)
    if silent:
        violations.append("D 存在 %d 处静默 return（无日志、无 silent-ok 标注）" % len(silent))
    marked = body.count("silent-ok")
    if marked > 1:
        violations.append("D silent-ok 标注出现 %d 次（只允许 1 处：『本次按键不是本模组的键』）" % marked)

    # A（处理器侧）：守卫只能是「有没有界面 / 是否旁观」，不许出现别的语义
    for tok in ("isPassenger", "containerMenu", "isUsingItem", "hasContainerOpen"):
        if tok in body_code:
            violations.append("A 守卫里出现与「界面」无关的玩家状态: %s" % tok)

    return violations, hits, len(silent)


# ---------------------------------------------------------------------------
# A. 接线（少一处整键就失效；这里钉死「注册在哪条总线」）
# ---------------------------------------------------------------------------
section("A. 接线：KeyMapping 在 MOD 总线、按键事件在游戏总线（两处缺一就整键失效）")
keybinds_src = read(KEYBIND)
client_init_src = read(CLIENT_INIT)
packet_src = read(PACKET)

check("bus = EventBusSubscriber.Bus.MOD" in keybinds_src,
      "A1 TerminalKeybinds 仍在 MOD 总线（RegisterKeyMappingsEvent 只能在那里收到）")
check("NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onKeyInput)" in client_init_src,
      "A2 ClientInit 把 onKeyInput 挂到**游戏总线**（InputEvent.Key 只在游戏总线派发；"
      "只靠类上的 MOD 总线订阅会整键失效）")
check("KeyConflictContext.IN_GAME" in keybinds_src,
      "A3 按键仍在 IN_GAME 冲突上下文（有界面时不激活，含聊天栏 / 文本框）")
check("OpenAdvancedRemoteTerminalPacket.INSTANCE" in keybinds_src
      and "sendToServer" in keybinds_src,
      "A4 快捷键发的仍是本模组的空负载 C2S 包")
handle = java_body(code_only(packet_src), "public static void handle(")
check("RsccTerminalLocator.locate(player)" in handle and "item.useFromShortcut(player," in handle,
      "A5 服务端处理体不变：定位终端 → useFromShortcut（客户端这一段只负责「喊一声」）")

# ---------------------------------------------------------------------------
# B/C/D. 真实源码过判据
# ---------------------------------------------------------------------------
section("B/C/D. 真实源码：无位置前提、发包无条件、无静默失败")
handler_body = java_body(keybinds_src, "public static void onClientTick")
check(bool(handler_body), "B0 取到 onClientTick 方法体（round83：热键驱动点已换成 tick 轮询）",
      "%d 字符" % len(handler_body))
violations, hits, silent_n = inspect_key_handler(handler_body)
check(not violations, "B1 判据全部通过（真实源码）", "违规=%s" % (violations or "无"))
print("      检查的量（共 %d 个 token）：%s" % (len(MOVE_POSITION_TOKENS), ", ".join(MOVE_POSITION_TOKENS)))
print("      命中：%s；静默出口：%d 处（应为 0，另加 1 处 silent-ok 标注）"
      % (hits or "无", silent_n))

# consumeClick 本身：原版语义是「计数>0 就自减并返回 true」，与玩家状态无关
check("consumeClick" in keybinds_src and "drainClicks(" in handler_body,
      "B2 仍用 consumeClick 取按下的那一次（原版 clickCount 由 keyPress 无条件自增，"
      "第一次按下即 true —— 不存在「第一次按不生效」）")
check("mapping.isDown()" in handler_body,
      "B3 同时读物理按住状态 isDown()（round83：用来区分「真按下」与「按住重复」，二者缺一不可）")

# 打开意图：时间制，与位置无关
intent_src = code_only(read(INTENT))
check("System.currentTimeMillis()" in intent_src
      and not any(t in intent_src for t in ("position()", "blockPosition(", "distanceTo", "walkDist")),
      "D1 打开意图只按时间失效（3 秒），体内无任何位置判定")

# ---------------------------------------------------------------------------
# E. 反例自证：修前写法 + 三种植入式「移动门禁」必须被判负
# ---------------------------------------------------------------------------
section("E. 反例自证：把修前写法 / 植入式「移动门禁」喂给同一批判据 ⇒ 必须判负")

PRE_FIX_BODY = """
    {
        final KeyMapping mapping = openTerminal;
        final Minecraft minecraft = Minecraft.getInstance();
        if (mapping == null) {
            return;
        }
        if (minecraft.player == null || minecraft.screen != null || minecraft.player.isSpectator()) {
            discardClicks(mapping);
            return;
        }
        if (!mapping.consumeClick()) {
            return;
        }
        discardClicks(mapping);
        TerminalOpenIntent.mark();
        PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE);
    }
"""

CASES = [
    ("修前写法（静默 return、发包无 try/catch、无诊断行）", PRE_FIX_BODY),
    ("植入：静止门禁（walkDist 没变就不发包）", PRE_FIX_BODY.replace(
        "discardClicks(mapping);\n        TerminalOpenIntent.mark();",
        "if (minecraft.player.walkDist == minecraft.player.walkDistO) { return; }\n"
        "        discardClicks(mapping);\n        TerminalOpenIntent.mark();")),
    ("植入：本地预判（区块没加载就静默 return）", PRE_FIX_BODY.replace(
        "discardClicks(mapping);\n        TerminalOpenIntent.mark();",
        "if (!minecraft.level.hasChunkAt(minecraft.player.blockPosition())) { return; }\n"
        "        discardClicks(mapping);\n        TerminalOpenIntent.mark();")),
    ("植入：射程预判（distanceToSqr 太远就不发包）", PRE_FIX_BODY.replace(
        "discardClicks(mapping);\n        TerminalOpenIntent.mark();",
        "if (minecraft.player.distanceToSqr(0.5, 0.5, 0.5) > 64.0) { return; }\n"
        "        discardClicks(mapping);\n        TerminalOpenIntent.mark();")),
    ("植入：条件内发包（if (player.isSprinting()) 才发）", PRE_FIX_BODY.replace(
        "PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE);",
        "if (minecraft.player.isSprinting()) {\n"
        "            PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE);\n"
        "        }")),
]

for label, body in CASES:
    v, h, s = inspect_key_handler(body)
    check(bool(v), "E 反例必须判负：%s" % label, "违规=%s" % (v or "居然通过了！"))

check(len(CASES) == 5, "E1 反例共 5 个（修前写法 + 4 种植入式门禁）")
neg_ok = all(inspect_key_handler(b)[0] for _l, b in CASES)
check(neg_ok, "E2 5/5 反例全部被判负（判据不是橡皮图章）")
pos_ok = not inspect_key_handler(handler_body)[0]
check(pos_ok, "E3 同一批判据对真实源码判正（避免「只要判负」的假绿）")

# 修前 vs 修后：唯一允许的差别是「可观测性」，成功路径的语义必须一字不动
prefix_pairs = [
    ("if (mapping == null)", "mapping == null 分支仍在"),
    ("minecraft.player == null || minecraft.screen != null || minecraft.player.isSpectator()",
     "界面 / 旁观守卫原样保留（不是「把守卫全删掉」式修复）"),
    ("drainClicks(mapping)", "点击计数的排空入口仍在（consumeClick 的唯一调用点）"),
    ("TerminalOpenIntent.mark();", "打开意图仍置位"),
    ("PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE);",
     "发包调用一字未改"),
]
for token, label in prefix_pairs:
    check(token in handler_body, "F %s" % label)

# ---------------------------------------------------------------------------
# G. round83：<b>换掉整个驱动方式</b> —— tick 轮询状态机（一次物理按下 = 一条打开请求）
#
# 为什么第 82 轮的「忽略 GLFW_REPEAT」不够：用户实机仍然打不开。第 83 轮照抄同一环境里
# 被证明可用的写法（Universal-Grid 0.3.2 的 AbstractClientModInitializer#tickInputEvents）：
#   ClientTickEvent.Post 轮询 + activePress 状态机 + consumeClick 排空点击计数，
#   **完全不用 InputEvent.Key 驱动**。
# 判据（全部是源码级结构判据，不依赖注释）：
#   S1 activePress 状态位存在；
#   S2 「已经处理过这次按下」的早退分支必须在唯一那条发包**之前**；
#   S3 发包之前必须把 activePress 置位；
#   S4 抬手（!isDown()）必须复位，否则第二次按下永远打不开；
#   S5 必须用 consumeClick 排空点击计数（原版只对它有可靠语义）；
#   S6 必须读 isDown()（区分「真按下」与「按住重复」的唯一可靠信号）；
#   S7 tick 监听器必须挂到游戏总线；
#   S8 不允许再出现 GLFW_REPEAT 事件语义（两套去重语义并存 = 迟早互相打架）；
#   S9 旧入口 InputEvent.Key 不得再消费点击 / 发包（否则会与 tick 状态机抢同一次按下）。
# ---------------------------------------------------------------------------
section("G. round83：热键驱动点 = ClientTickEvent.Post 轮询 + activePress 状态机")


def tick_state_machine_violations(src):
    """把「tick 轮询 + 一次按下一次打开」算成违规清单（空表示通过）。"""
    body = java_body(src, "public static void onClientTick")
    code = code_padded(body)
    v = []
    if not body:
        return ["S0 取不到 onClientTick 方法体（tick 轮询驱动点丢了）"]
    if "activePress" not in code:
        v.append("S1 没有 activePress 状态位")
    i_repeat = code.find("if (activePress)")
    i_send = code.find("sendToServer")
    if i_repeat < 0:
        v.append("S2 没有「这次按下已经处理过」的早退分支（按住 G 仍会按重复速率发包）")
    elif i_send >= 0 and i_repeat > i_send:
        v.append("S2 activePress 判定在发包之后（等于没挡）")
    i_set = code.find("activePress = true")
    if i_set < 0:
        v.append("S3 没有把 activePress 置位（每次 tick 都会被当成新按下）")
    elif i_send >= 0 and i_set > i_send:
        v.append("S3 activePress 置位在发包之后（先发后挡）")
    if "activePress = false" not in code:
        v.append("S4 抬手后不复位（第二次按下永远打不开）")
    if "drainClicks(" not in body or "consumeClick" not in src:
        v.append("S5 没有用 consumeClick 排空点击计数")
    if "isDown()" not in code:
        v.append("S6 没有读物理按住状态 isDown()（无法区分按住重复）")
    if "ClientTickEvent.Post" not in src or "addListener(TerminalKeybinds::onClientTick)" not in src:
        v.append("S7 tick 轮询监听器没有挂到游戏总线（热键根本不会跑）")
    if "GLFW.GLFW_REPEAT" in src:
        v.append("S8 仍在用 GLFW_REPEAT 事件语义（与 tick 状态机是两套去重语义）")
    legacy = java_body(src, "public static void onKeyInput")
    if ("consumeClick" in legacy or "sendToServer" in legacy
            or "TerminalOpenIntent.mark" in legacy):
        v.append("S9 旧入口 InputEvent.Key 仍在驱动（会和 tick 状态机抢同一次按下）")
    return v


v_s = tick_state_machine_violations(keybinds_src)
check(not v_s, "G1 真实源码：tick 轮询 + activePress + isDown + 无 GLFW_REPEAT + 旧入口不驱动",
      "违规=%s" % (v_s or "无"))
check("ClientTickEvent.Post" in keybinds_src
      and "public static void onClientTick(final ClientTickEvent.Post event)" in keybinds_src,
      "G2 订阅的确实是 ClientTickEvent.Post（与通用终端同构，不是自建计时器）")
check("NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onClientTick)" in keybinds_src,
      "G3 tick 监听器通过游戏总线挂载（ClientInit 未改动，避免超出本轮写入范围）")
check("private static void attachTickListener()" in keybinds_src and "tickListenerAttached" in keybinds_src,
      "G4 挂载是幂等的（重复挂会变成一 tick 两跳 ⇒ 两次打开请求）")
i_consume = handler_body.find("drainClicks(mapping)")
i_mark = handler_body.find("TerminalOpenIntent.mark()")
i_send = handler_body.find("sendToServer")
check(0 <= i_consume < i_mark < i_send,
      "G5 顺序仍是 排空点击 → mark → sendToServer (consume=%d mark=%d send=%d)"
      % (i_consume, i_mark, i_send))
check(keybinds_src.count("PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE)") == 1,
      "G6 整个 TerminalKeybinds 里只有一处「打开终端」发包（重复分支没有自己的发包）")
check("MIN_PRESS_INTERVAL_MS" in handler_body,
      "G7 连点兜底存在（相邻两次请求 <100ms 时只保留一条，避免刚开的界面又被 closeContainer）")
check("System.nanoTime" not in handler_body,
      "G8 不是靠定时器轮询触发的（tick 是原版的，逻辑里没有自建时钟循环）")

# 反例：去掉状态机任意一环 ⇒ 必须判负
NEG_TICK = [
    ("去掉 activePress 早退分支（回到每 tick 一发包）",
     keybinds_src.replace("if (activePress) {", "if (false) {")),
    ("activePress 从不复位（第二次按下永久失效）",
     keybinds_src.replace("activePress = false;", "activePress = activePress;")),
    ("tick 监听器没挂上（热键彻底不跑）",
     keybinds_src.replace(
         "NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onClientTick);", "")),
    ("旧入口又去消费点击（和 tick 状态机抢同一次按下）",
     keybinds_src.replace("if (legacyEntryNoted) {",
                          "openTerminal.consumeClick();\n        if (legacyEntryNoted) {")),
    ("回到 GLFW_REPEAT 事件语义（第 82 轮方案）",
     keybinds_src.replace('LOGGER.info("[rscc-terminal-key] ②发包前',
                          'if (event.getAction() == GLFW.GLFW_REPEAT) { return; }\n'
                          '        LOGGER.info("[rscc-terminal-key] ②发包前')),
]
for label, mutated in NEG_TICK:
    check(mutated != keybinds_src and bool(tick_state_machine_violations(mutated)),
          "G9 反例必须判负：%s" % label,
          "违规=%s" % (tick_state_machine_violations(mutated) or "居然通过了！"))
check(not tick_state_machine_violations(keybinds_src),
      "G10 同一批判据对真实源码判正（避免「只要判负」的假绿）")

# ---------------------------------------------------------------------------
# H. round82：服务端打开链路的静默出口封死（Universal-Grid 的链路里根本没有「找不到」这一步）
#
# 反汇编证据：Universal Grid 0.3.2 的 useGridCorrectly / openGridType 只做
#   SlotReference.resolve(player).ifPresent(stack -> 调原版 RS 物品的 use(...))
# 然后交给 RS 原版打开界面；它没有 locate、没有唯一性判定、没有「未找到」分支。
# 我们的链路曾经有两条静默出口：locate 为空（有红字，可观测）与 useFromShortcut 的
# `if (stackOpt.isEmpty()) return;`（无界面、无红字 —— 正是用户报告的症状）。
# ---------------------------------------------------------------------------
section("H. round82：服务端打开链路不再有「没界面也没红字」的静默出口")

ITEM = os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java")
item_src = read(ITEM)
shortcut_body = java_body(code_only(item_src), "public void useFromShortcut(")
check(bool(shortcut_body), "H0 取到 useFromShortcut 方法体", "%d 字符" % len(shortcut_body))


def open_chain_violations(body):
    """打开入口必须：解引用 → 失败则按固定优先级兜底重新定位 → openModeScreen；且不碰任何位置/世界状态。"""
    code = code_padded(body)
    v = []
    if "resolve(player)" not in code:
        v.append("H1 没有解引用存活栈")
    if "openModeScreen(" not in code:
        v.append("H2 没有调用 openModeScreen（打开界面的唯一实现）")
    if "RsccTerminalLocator.locate(player)" not in code:
        v.append("H3 解引用失败时没有兜底重新定位（= 静默无操作）")
    hits = [t for t in MOVE_POSITION_TOKENS if t in code]
    if hits:
        v.append("H4 打开入口出现位置/移动/世界状态量: %s" % ",".join(sorted(set(hits))))
    return v


v_h = open_chain_violations(shortcut_body)
check(not v_h, "H1 真实源码：解引用失败 ⇒ 兜底重新定位；且不含任何位置/移动/世界状态前提",
      "违规=%s" % (v_h or "无"))
check("openModeScreen(" in shortcut_body and "type" not in shortcut_body,
      "H2 打开入口仍不按型号 / 不按玩家状态分支（三版终端同一条路径）")

# useFromShortcut 里不允许「解引用失败就静默放弃」：放弃点必须在**兜底重新定位之后**，
# 且该情形由调用方（C2S 处理体）负责给出提示 ⇒ 整条链路不存在「既没界面也没提示」的结局。
OLD_SHORTCUT_BODY = """
    {
        final Optional<ItemStack> stackOpt = slotReference.resolve(player);
        if (stackOpt.isEmpty()) {
            return;
        }
        rescueLegacyExtraSlot(player, stackOpt.get());
        openModeScreen(player, stackOpt.get(), slotReference);
    }
"""


def abandon_violations(body):
    code = code_padded(body)
    i_locate = code.find("RsccTerminalLocator.locate(player)")
    i_open = code.find("openModeScreen(")
    if i_locate < 0:
        return ["H3 没有兜底定位（= 解引用失败即静默放弃）"]
    if i_open < i_locate:
        return ["H3 兜底定位在 openModeScreen 之后（等于没用）"]
    returns = [m.start() for m in re.finditer(r"\breturn\s*;", code)]
    after = [r for r in returns if i_locate < r < i_open]
    if not after:
        return ["H3 兜底定位之后没有明确的放弃点（语义不清晰）"]
    if len(after) != 1:
        return ["H3 兜底定位之后有 %d 个放弃点（只允许 1 个）" % len(after)]
    return []


v_a = abandon_violations(shortcut_body)
check(not v_a, "H3 useFromShortcut 只在「兜底也定位不到」之后才允许放弃（解引用失败不再静默 return）",
      "违规=%s" % (v_a or "无"))
check(bool(abandon_violations(OLD_SHORTCUT_BODY)),
      "H3b 反例必须判负：修前写法解引用失败即静默 return（无兜底）",
      "违规=%s" % (abandon_violations(OLD_SHORTCUT_BODY) or "居然通过了！"))

handle_body = java_body(code_only(packet_src), "public static void handle(")
check("player.containerMenu != player.inventoryMenu" in handle_body
      or "player.containerMenu == player.inventoryMenu" in handle_body,
      "H4 服务端在打开之后做一次事后校验（containerMenu 仍是玩家背包 ⇒ 这一次没打开）")
check("logOpenFailure(" in handle_body and "displayClientMessage(" in handle_body,
      "H5 没打开时打节流 ERROR + 给玩家提示（封死「没界面也没红字」）")
check(handle_body.count("useFromShortcut(") == 1,
      "H6 事后校验只判定、不补发第二次打开请求")
check(not any(t in handle_body for t in ("schedule(", "Timer", "Thread.sleep", "currentTimeMillis")),
      "H7 没有任何定时器 / 延时重试（不是「客户端强制定时刷新」式绕过）")

# 反例：旧写法（静默 return）与「只判定不提示」的写法必须判负
check(bool(open_chain_violations(OLD_SHORTCUT_BODY)),
      "H8 反例必须判负：修前写法（解引用失败即静默 return，无兜底）",
      "违规=%s" % (open_chain_violations(OLD_SHORTCUT_BODY) or "居然通过了！"))
check(not open_chain_violations(shortcut_body),
      "H9 同一批判据对真实源码判正（避免「只要判负」的假绿）")

# 新路径不依赖「服务端先回数据」：整条链路只有一条 C2S、且没有任何「等 S2C 回来再建界面」的状态
check("S2C" not in code_only(packet_src) and "playToClient" not in packet_src,
      "H10 打开请求是单向 C2S：客户端不等服务端数据就先喊一声（不存在「必须先有服务端数据才能打开」）")

# ---------------------------------------------------------------------------
# I. round83：六点日志 —— 一份日志就能定位「断在哪一环」
#
# 前两轮最大的成本不是改错，而是「用户不开 devLogs ⇒ 回来一条日志都没有」。
# 因此本轮把打开链路切成六点：①②③ 客户端、④⑤⑥ 服务端，**每点都不受 devLogs 控制**，
# 但结构上不刷屏（一次物理按下只有一轮；服务端再加 200ms 窗口 + 被压条数汇总）。
# 判据：
#   I1 六个标记各自出现在一条**直接**的 LOGGER.info / openLog(...) 调用里（不是 debug(...)）；
#   I2 三点客户端日志所在的 onClientTick 里不出现 RsccAssemblyDebug（= 不会被 devLogs 关掉）；
#     ④⑥ 所在的网络包类、⑤ 所在的 openModeScreen 同理；
#   I3 顺序：① → ② → sendToServer → ③；④ → useFromShortcut → ⑥；
#   I4 节流：客户端噪音有 Throttle + 1 秒窗口，服务端有 LOG_WINDOW_MS + 被压条数汇总；
#   I5 反例：删掉任一点 / 给它套上 devLogs 开关 / 去掉节流 ⇒ 必须判负。
# ---------------------------------------------------------------------------
section("I. round83：六点日志（①②③客户端 + ④⑤⑥服务端）齐全、不受 devLogs 控制、且节流")

SIX_POINTS = [
    ("①按键", keybinds_src),
    ("②发包前", keybinds_src),
    ("③发包后", keybinds_src),
    ("④服务端收到", packet_src),
    ("⑤开菜单", item_src),
    ("⑥打开后校验", packet_src),
]


def point_violations(points):
    """每个观测点必须是一条**直接的**日志调用（LOGGER.info / openLog），不能是受开关控制的 debug(...)。"""
    v = []
    for tag, src in points:
        hit = any(tag in line and ("LOGGER.info(" in line or "openLog(" in line)
                  for line in src.splitlines())
        if not hit:
            v.append("%s 没有一条直接的 LOGGER.info / openLog(...) 带这个标记" % tag)
    return v


def gating_violations(keybind_src, packet_src_, item_src_):
    """六点日志所在的方法体里不允许出现 devLogs 总开关（否则用户没开就又是一片空白）。"""
    v = []
    tick_body = java_body(keybind_src, "public static void onClientTick")
    if any(tag in tick_body for tag in ("①按键", "②发包前", "③发包后")) and "RsccAssemblyDebug" in tick_body:
        v.append("①②③ 所在的 onClientTick 里出现 RsccAssemblyDebug（会被 devLogs 关掉）")
    if "RsccAssemblyDebug" in packet_src_:
        v.append("网络包处理体里出现 RsccAssemblyDebug（④⑥ 会被 devLogs 关掉）")
    open_body = java_body(code_only(item_src_), "public void openModeScreen(")
    if "RsccAssemblyDebug" in open_body:
        v.append("openModeScreen 里出现 RsccAssemblyDebug（⑤ 会被 devLogs 关掉）")
    return v


def throttle_violations(keybind_src, packet_src_):
    v = []
    if "NOISE_THROTTLE_MS" not in keybind_src or "class Throttle" not in keybind_src:
        v.append("客户端噪音日志没有节流器（守卫 / 重复分支会 20 条/秒刷屏）")
    if "MIN_PRESS_INTERVAL_MS" not in keybind_src:
        v.append("客户端没有「两次打开请求最小间隔」兜底")
    if "LOG_WINDOW_MS" not in packet_src_ or "logSuppressed" not in packet_src_:
        v.append("服务端三点日志没有窗口节流 / 没统计被压条数")
    for src, name in ((keybind_src, "客户端"), (packet_src_, "服务端")):
        if "上一窗口压掉" not in src:
            v.append("%s节流没有把「被压掉几条」汇总（等于静默丢日志）" % name)
    return v


v_i1 = point_violations(SIX_POINTS)
check(not v_i1, "I1 六点日志齐全（①②③④⑤⑥各一条直接日志）", "违规=%s" % (v_i1 or "无"))
v_i2 = gating_violations(keybinds_src, packet_src, item_src)
check(not v_i2, "I2 六点都不受 devLogs 控制（方法体内没有 RsccAssemblyDebug 开关）",
      "违规=%s" % (v_i2 or "无"))
i1 = code_only(keybinds_src).find("①按键")
i2 = code_only(keybinds_src).find("②发包前")
i3 = code_only(keybinds_src).find("③发包后")
i_send_src = code_only(keybinds_src).find("PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE)")
check(0 <= i1 < i2 < i_send_src < i3, "I3a 客户端顺序正确：①按键 → ②发包前 → sendToServer → ③发包后"
      " (%d/%d/%d/%d)" % (i1, i2, i_send_src, i3))
i4 = code_only(packet_src).find("④服务端收到")
i5 = code_only(packet_src).find("useFromShortcut(")
i6 = code_only(packet_src).find("⑥打开后校验")
check(0 <= i4 < i5 < i6, "I3b 服务端顺序正确：④服务端收到 → useFromShortcut → ⑥打开后校验"
      " (%d/%d/%d)" % (i4, i5, i6))
check("⑤开菜单" in item_src, "I3c ⑤开菜单 落在 openModeScreen（真正开界面的唯一实现）里")
v_i4 = throttle_violations(keybinds_src, packet_src)
check(not v_i4, "I4 节流 + 被压条数汇总都在（不刷屏也不静默丢日志）", "违规=%s" % (v_i4 or "无"))

# I5 反例自证
_NO_POINT2 = [(tag, src.replace('LOGGER.info("[rscc-terminal-key] ②发包前', '/* removed */ LOGGER.debug("②发包前')
               if tag == "②发包前" else src) for tag, src in SIX_POINTS]
check(bool(point_violations(_NO_POINT2)), "I5a 反例必须判负：删掉 ②发包前 这一点",
      "违规=%s" % (point_violations(_NO_POINT2) or "居然通过了！"))
_GATED = keybinds_src.replace(
    'LOGGER.info("[rscc-terminal-key] ②发包前',
    'if (RsccAssemblyDebug.isEnabled()) LOGGER.info("[rscc-terminal-key] ②发包前')
check(_GATED != keybinds_src and bool(gating_violations(_GATED, packet_src, item_src)),
      "I5b 反例必须判负：把 ②发包前 套进 devLogs 开关（用户没开就等于没有）",
      "违规=%s" % (gating_violations(_GATED, packet_src, item_src) or "居然通过了！"))
check(bool(throttle_violations(keybinds_src.replace("NOISE_THROTTLE_MS", "X_MS"), packet_src)),
      "I5c 反例必须判负：去掉客户端噪音节流")
check(bool(throttle_violations(keybinds_src, packet_src.replace("LOG_WINDOW_MS", "X_MS"))),
      "I5d 反例必须判负：去掉服务端窗口节流")
check(not point_violations(SIX_POINTS) and not throttle_violations(keybinds_src, packet_src),
      "I6 同一批判据对真实源码判正（避免「只要判负」的假绿）")

print()
print("=" * 78)
print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
for p in PROBLEMS:
    print("  - " + p)
print("=" * 78)
sys.exit(1 if PROBLEMS else 0)
