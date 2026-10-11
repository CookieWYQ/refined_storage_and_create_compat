# -*- coding: utf-8 -*-
"""round61 自检：自动合成监视器里「任务操作按钮」（挂起 / 继续 / 更换机器）的**渲染可达性**。

用户原话（本轮实测现象）：
    「那个任务，不管什么任务不都是可以挂起和继续吗之类的，但是我好像没有正常的看到那几个按钮渲染。
      你再检查一下是不是我们的问题。」
第 63 轮用户把要求收紧成硬要求：
    「**不管什么任务都要有这个按钮啊**」—— 物品的、流体的、别的，都没有例外。

本脚本把这条链路逐段钉死（每条断言都是可被源码事实证伪的锚点，不是复述注释）：

  ① **绘制入口存在且被注册**：AutocraftingMonitorScreenMixin 里三个按钮的创建 /
     addRenderableWidget / 每帧刷新 / 手动 tooltip 锚点，且 mixin 已在
     rs_create_compat.mixins.json 的 client 列表里。
  ② **可见性 = 动作位**：`offered = alert != null && alert.offers(actionBit)`（单一状态源、零推断），
     而「服务端还没有这条任务的记录」被 `alertOrDefault` 折成一份**只带挂起位的默认告警**
     （唯一一处兜底，位置明确、只有一位）；`alertOf` 仍然诚实返回 null（界面据此补拉快照）。
     `Record#ourChain`（第 56 轮的保护位）只出现在自动挂起 / 兜底取消闸门上，
     按钮渲染路径（客户端）里一个字都没有。
  ③ **真值表（第 63 轮重写）**：物品任务（有记录 / 未挂起 / 已挂起）、**流体任务（无记录）**、
     任意无记录任务、非物品非流体任务 ⇒ **每一种都必须至少画出一个按钮**；
     并把「无记录 ⇒ 不画」的**旧写法喂给同一组断言 ⇒ 必须判负**（反例自证）。
  ④ **手动挂起 / 继续仍然有效、且服务端一定处理得了**：C2S 包 → 服务端 suspend/resume 两条动作路径；
     `suspend()` 在**查不到记录时就地补一拍全维度扫描**（rescanNow），并跨维度找记录。
  ⑤ **几何**：按钮行的四个坐标（含放不下时收起的按钮）全部落在面板 / 屏幕内。
  ⑥ **协议**：SyncAssemblyAlertsPacket 的产物资源键物品 / 流体**两端对称**编解码
     （同一个 ResourceCodecs 编码器、无按类型分支），字段顺序 = 编码顺序 = 解码顺序。
  ⑦ **不变性**：第 56 轮三闸门、第 58 轮「scheduled / processing 不算预留」、第 61 轮补拉逻辑都还在。

运行：python tools/selfcheck_round61_task_action_buttons.py
      → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
"""
import io
import json
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """去掉块注释与行注释：只看真代码，避免文档里提到的词把断言带偏。"""
    out = []
    depth = 0
    i = 0
    in_line = False
    in_str = False
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_line:
            if ch == "\n":
                in_line = False
                out.append(ch)
            i += 1
            continue
        if depth > 0:
            if ch == "*" and nxt == "/":
                depth -= 1
                i += 2
                continue
            if ch == "\n":
                out.append(ch)
            i += 1
            continue
        if in_str:
            out.append(ch)
            if ch == "\\":
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == "/" and nxt == "*":
            depth += 1
            i += 2
            continue
        if ch == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if ch == '"':
            in_str = True
        out.append(ch)
        i += 1
    return "".join(out)


def line_of(text, needle):
    index = text.find(needle)
    return None if index < 0 else text.count("\n", 0, index) + 1


def body_of(text, signature):
    """取某个方法的实现体（到下一个顶层 `private/public static` 或类尾为止）。"""
    start = text.index(signature)
    tail = text[start:]
    ends = [tail.find(marker, 1) for marker in ("\n    private ", "\n    public ", "\n    static ")]
    ends = [e for e in ends if e > 0]
    return tail[:min(ends)] if ends else tail


MIXIN = read(os.path.join("mixin", "client", "AutocraftingMonitorScreenMixin.java"))
MIXIN_CODE = strip_comments(MIXIN)
WATCHDOG = read(os.path.join("support", "AssemblyWatchdog.java"))
WATCHDOG_CODE = strip_comments(WATCHDOG)
ACTION = read(os.path.join("network", "AssemblyTaskActionPacket.java"))
CLIENT = read(os.path.join("client", "AssemblyAlertsClient.java"))
CLIENT_CODE = strip_comments(CLIENT)

print("=" * 78)
print("① 绘制入口：按钮在哪、怎么画（文件:行号）")
print("=" * 78)

for needle, label in (
    ("@Mixin(AutocraftingMonitorScreen.class)", "@Mixin 目标是 RS 的 AutocraftingMonitorScreen"),
    ('@Inject(method = "init", at = @At("TAIL"))', "注入 init() 末尾（只在 init 里建控件）"),
    ("rscc$resumeButton = Button.builder(resume, button -> rscc$resume())", "创建「继续」按钮"),
    ("rscc$suspendButton = Button.builder(suspend, button -> rscc$suspend())", "创建「挂起」按钮"),
    ("rscc$machineButton = Button.builder(machine, button -> rscc$changeMachine())", "创建「更换机器」按钮"),
    ("rscc$addRenderableWidget(rscc$resumeButton);", "把「继续」挂进 Screen 的 renderables"),
    ("rscc$addRenderableWidget(rscc$suspendButton);", "把「挂起」挂进 Screen 的 renderables"),
    ("rscc$addRenderableWidget(rscc$machineButton);", "把「更换机器」挂进 Screen 的 renderables"),
    ('@Inject(method = "render", at = @At("TAIL"))', "注入 render() 末尾（每帧刷新 + 手动 tooltip）"),
    ('@Inject(method = "currentTaskChanged", at = @At("TAIL"))', "注入 currentTaskChanged() 末尾"),
    ("rscc$refreshButtons();", "每帧刷新按钮状态"),
    ("rscc$tooltip(graphics, font, rscc$resumeButton,", "「继续」tooltip（GUI 不会自动渲染 tooltip）"),
    ("rscc$tooltip(graphics, font, rscc$suspendButton,", "「挂起」tooltip"),
):
    line = line_of(MIXIN, needle)
    check("① %s  [mixin/client/AutocraftingMonitorScreenMixin.java:%s]" % (label, line),
          line is not None, "锚点缺失：%r" % needle)

mixin_json = json.loads(io.open(os.path.join(RES, "rs_create_compat.mixins.json"),
                                "r", encoding="utf-8").read())
check("① mixin 已在 rs_create_compat.mixins.json 的 client 列表注册"
      "（未注册 = 客户端根本不加载它，按钮一个都不会画）",
      "client.AutocraftingMonitorScreenMixin" in mixin_json.get("client", []),
      "client 列表 = %r" % (mixin_json.get("client")))

check("① 本模组**没有**第二条取消按钮（ACTION_CANCEL 不存在）—— 那排按钮只有挂起 / 继续 / 更换机器",
      "ACTION_CANCEL" not in MIXIN and "ACTION_CANCEL" not in ACTION,
      "又出现了 ACTION_CANCEL")

print()
print("=" * 78)
print("② 显隐条件：可见性 = 服务端动作位（没有记录 ⇒ 由 alertOrDefault 折成默认「挂起」位）")
print("=" * 78)

check("② visible 只由两个因子决定：view.visible()（= 动作位） && fits（init 一次性算出的静态几何）",
      "button.visible = view.visible() && fits;" in MIXIN_CODE,
      "rscc$apply 的可见性因子变了")
check("② active = visible && view.enabled()（在途只影响可用性，绝不影响可见性 —— 点击不会让按钮消失）",
      "button.active = button.visible && view.enabled();" in MIXIN_CODE,
      "在途混进了可见性")
check("② actionView 的 visible 仍然**只是** alert.offers(actionBit)（零推断：兜底不在这里，"
      "而在 alertOrDefault —— 见下一条）",
      "final boolean offered = alert != null && alert.offers(actionBit);" in CLIENT_CODE
      and "return new ActionView(offered, offered && !isPending(taskId, actionBit));" in CLIENT_CODE,
      "actionView 的 visible 不是纯 offered")

# ---- 第 63 轮：客户端兜底的唯一位置与唯一一位 ----
alert_of_body = body_of(CLIENT_CODE, "public static SyncAssemblyAlertsPacket.Alert alertOf(")
defaulted_body = body_of(CLIENT_CODE, "public static SyncAssemblyAlertsPacket.Alert alertOrDefault(")
check("② `alertOf` 仍然诚实（快照里没有这条任务 ⇒ 返回 null）：补拉快照的判据因此没有被兜底骗过去",
      "return null;" in alert_of_body and "ACTION_BIT_SUSPEND" not in alert_of_body,
      "alertOf 不再诚实（补拉逻辑会失效：真快照永远拉不回来）")
check("② **兜底只有一处**：`alertOrDefault` 在没有快照时造一份只带 ACTION_BIT_SUSPEND 的默认告警"
      "（挂起位是唯一的默认位；RESUME / CHANGE_MACHINE 不出现在这里）",
      "SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND" in defaulted_body
      and "ACTION_BIT_RESUME" not in defaulted_body
      and "ACTION_BIT_CHANGE_MACHINE" not in defaulted_body
      and "SyncAssemblyAlertsPacket.REASON_NONE" in defaulted_body,
      "默认位不是「只有挂起」或位置不对：%r" % defaulted_body[:400])
check("② 界面用**两份**视图：按钮用 alertOrDefault（带默认位）、补拉用 alertOf（诚实）",
      "AssemblyAlertsClient.alertOrDefault(taskId);" in MIXIN_CODE
      and "AssemblyAlertsClient.alertOf(taskId);" in MIXIN_CODE
      and "rscc$requestAlertIfMissing(taskId, received);" in MIXIN_CODE,
      "按钮视图与补拉视图没有分开（兜底会把补拉一起骗掉）")

refresh_body = body_of(MIXIN_CODE, "private void rscc$refreshButtons() {")
check("② 按钮刷新路径**不读** task 的业务状态（reason / offlineSteps / suspendState / stallTicks / ourChain）"
      "—— 旧实现按 reason + offlineSteps 自己推断，服务端一改分类按钮就消失",
      all(word not in refresh_body for word in
          ("reason", "offlineSteps", "suspendState", "stallTicks", "ourChain")),
      "刷新路径里又出现了状态推断：%r" % refresh_body)

actions_body = body_of(WATCHDOG_CODE, "private int actions() {")
check("② 服务端 actions() 只读 returning / suspended / reason / offlineSteps（不看 ourChain）",
      "returning" in actions_body and "suspended" in actions_body and "ourChain" not in actions_body,
      "actions() 里出现了 ourChain 或结构变了")
check("② 未挂起且 RS 没在回收内部暂存 ⇒ 一定给「挂起」位（`return ... ACTION_BIT_SUSPEND;`）"
      "—— 这一句对**每一条**任务成立，与它是普通任务还是序列装配任务无关",
      "return SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND;" in actions_body,
      "未挂起任务不再给「挂起」位")

# ourChain 的全部真代码使用点：必须一分不漏地落在「记录字段 / 读取器 / 自动挂起三闸门 / 诊断导出」里，
# 一个字节都不许落在「按钮可见性」那两处（actions() 与客户端渲染路径）。
code_lines = WATCHDOG_CODE.splitlines()
raw_lines = WATCHDOG.splitlines()
our_chain_lines = [n for n, line in enumerate(code_lines, 1) if "ourChain" in line]
ALLOWED = (
    "private boolean ourChain;",            # 字段声明
    "public boolean ourChain() {",          # 只读读取器
    "return ourChain;",                     # 只读读取器主体
    "record.ourChain && record.sequence",   # 热探针闸门（自动挂起）
    "record.ourChain && !noticeOwned",      # 扫描闸门（自动挂起）
    "!record.ourChain",                     # 兜底取消闸门
    'row.put("ourChain"',                   # 诊断导出
    'ourChain=" + record.ourChain',         # 日志
    "+ (record.ourChain",                   # 日志续行
    "record.ourChain = sequenceTask",       # 唯一写入处（每次扫描刷新）
)
unexpected = [n for n in our_chain_lines
              if not any(needle in code_lines[n - 1] for needle in ALLOWED)]
check("② ourChain 的真代码使用点**全部**落在「字段 / 只读读取器 / 自动挂起三闸门 / 诊断日志」里"
      "—— 没有一处落在按钮可见性路径（actions() 与客户端渲染路径）上",
      not unexpected, "越界使用点 = %r" % [(n, raw_lines[n - 1].strip()) for n in unexpected])
check("② ourChain 在**按钮可见性**的两处真代码里都不存在："
      "① 服务端 actions()（唯一动作位判据）② 客户端监视器 Mixin + AssemblyAlertsClient",
      "ourChain" not in actions_body and "ourChain" not in MIXIN_CODE
      and "ourChain" not in CLIENT_CODE,
      "按钮可见性路径里出现了 ourChain")
for lineno in our_chain_lines:
    print("      ourChain 使用点  AssemblyWatchdog.java:%d  %s"
          % (lineno, raw_lines[lineno - 1].strip()))

check("② 按钮可见性也不依赖「是否被挂起」：挂起只决定服务端给 RESUME 还是 SUSPEND 位"
      "（互斥共槽），渲染那一步对两位一视同仁",
      "rscc$apply(rscc$resumeButton, rscc$primaryFits," in MIXIN_CODE
      and "rscc$apply(rscc$suspendButton, rscc$primaryFits," in MIXIN_CODE,
      "两个主按钮不再共槽 / 判定不对称")

check("② 结构性缺口已补：选中任务但本地无快照时补发一次只读 RequestAssemblyAlertsPacket"
      "（去重字段 rscc$alertsRequested；每个任务每次会话最多一次）"
      "—— 界面里恰好两条发包路径：① init 拉一次 ② 选中任务无快照时按任务各补拉一次；"
      "注意它用的是**诚实**的那一份（received = alertOf），不是带默认位的按钮视图",
      "rscc$requestAlertIfMissing(taskId, received);" in MIXIN_CODE
      and "private UUID rscc$alertsRequested;" in MIXIN_CODE
      and MIXIN_CODE.count("new RequestAssemblyAlertsPacket()") == 2,
      "补拉逻辑缺失或发包次数异常（count=%d）"
      % MIXIN_CODE.count("new RequestAssemblyAlertsPacket()"))
check("② 补拉只在**没有快照**时发（alert != null 直接返回），已有快照的任务一个包都不多发",
      "if (taskId == null || alert != null || taskId.equals(rscc$alertsRequested)) {" in MIXIN_CODE,
      "补拉条件不是「无快照才发」")

print()
print("=" * 78)
print("③ 真值表：**任何**任务都至少画出一颗按钮（含反例自证）")
print("=" * 78)

SUSPEND_BIT, RESUME_BIT, MACHINE_BIT = 4, 1, 2


def server_actions(suspended, returning, offline_step):
    """与 AssemblyWatchdog.Record#actions() 同构（唯一的按钮可见性判据；不看 ourChain）。"""
    if returning:
        return 0
    if not suspended:
        return SUSPEND_BIT
    bits = RESUME_BIT
    if offline_step:
        bits |= MACHINE_BIT
    return bits


def in_snapshot(has_record, suspended, returning, offline_step):
    """服务端 AssemblyWatchdog.allAlerts() 只收 actions() != 0 的记录 ⇒ 收尾态（returning）不在快照里。

    这一条必须显式建模：客户端能看到的**只有快照**，因此「服务端有记录但一个动作位都不给」
    与「服务端根本没有这条记录」在客户端是**同一件事**（都是 alertOf == null）。
    """
    return has_record and server_actions(suspended, returning, offline_step) != 0


def client_bits(has_record, suspended=False, returning=False, offline_step=False):
    """与 AssemblyAlertsClient.alertOf + alertOrDefault + actionView 同构（客户端唯一判据）。"""
    if in_snapshot(has_record, suspended, returning, offline_step):
        return server_actions(suspended, returning, offline_step)
    # 没有快照（没有记录 / 记录不在快照里）⇒ alertOrDefault 给「只带挂起位」的默认告警。
    return SUSPEND_BIT


def old_client_bits(has_record, suspended=False, returning=False, offline_step=False):
    """**旧写法（第 63 轮之前）**：没有记录 ⇒ 一个动作位都不给 ⇒ 整排按钮一个都不画。

    它被留在脚本里当**反例**：同一组断言必须把它判负（见本节末尾的 ③ 反例）。
    """
    return server_actions(suspended, returning, offline_step) if in_snapshot(
        has_record, suspended, returning, offline_step) else 0


def rendered(bits, primary_fits=True, machine_fits=True):
    """与 actionView + rscc$apply + rscc$layoutButtons 同构。"""
    out = set()
    if bits & RESUME_BIT and primary_fits:
        out.add("resume")
    if bits & SUSPEND_BIT and primary_fits:
        out.add("suspend")
    if bits & MACHINE_BIT and machine_fits:
        out.add("machine")
    return frozenset(out)


# 每一行 = (标签, 有记录?, 已挂起?, RS 正在回收?, 掉线步骤已知?, 期望渲染)
CASES = (
    ("物品任务 · 有记录 · 未挂起", True, False, False, False, frozenset({"suspend"})),
    ("物品任务 · 有记录 · 已挂起", True, True, False, False, frozenset({"resume"})),
    ("物品任务 · 有记录 · 已挂起 + 掉线步骤已知", True, True, False, True, frozenset({"resume", "machine"})),
    ("**流体任务 · 无记录**（第 63 轮双资源前：永远没有记录）", False, False, False, False,
     frozenset({"suspend"})),
    ("**任意无记录任务**（刚下单不到一秒：记录还没被扫描建出来）", False, False, False, False,
     frozenset({"suspend"})),
    ("**非物品非流体任务 · 无记录**（其它平台资源，若存在）", False, False, False, False,
     frozenset({"suspend"})),
    ("物品任务 · 有记录 · RS 正在回收内部暂存（收尾态）", True, True, True, False,
     frozenset({"suspend"})),
    ("物品任务 · 有记录 · 未挂起 · RS 正在回收内部暂存", True, False, True, False,
     frozenset({"suspend"})),
)
for label, has_record, suspended, returning, offline_step, expected in CASES:
    bits = client_bits(has_record, suspended, returning, offline_step)
    got = rendered(bits)
    check("③ [%s] 渲染 %s" % (label, sorted(expected)), got == expected,
          "got=%r bits=%d" % (sorted(got), bits))

check("③ **关键：任何一条被界面显示出来的任务都至少画出一颗按钮**（没有任何例外）",
      all(rendered(client_bits(h, s, r, o)) for _l, h, s, r, o, _e in CASES),
      "存在「一个按钮都不画」的任务状态")

check("③ 「挂起 / 继续」这一对主槽位对**每一条**任务都恰好给出其中一颗（互斥且穷举）",
      all(len(rendered(client_bits(h, s, r, o)) & {"suspend", "resume"}) == 1
          for _l, h, s, r, o, _e in CASES),
      "存在「主槽位一颗都没有」或「两颗同时画」的状态")

# --- ③ 反例：把「无记录 ⇒ 不画」的旧写法喂给同一组断言，必须判负 ---
counterexamples = [(_l, h, s, r, o) for _l, h, s, r, o, _e in CASES
                   if not rendered(old_client_bits(h, s, r, o))]
check("③ **反例（必须判负）**：旧写法「没有记录 ⇒ 不画」在 [%s] 这些用例上渲染不出任何按钮"
      "—— 同一组断言的否定式因此成立（新写法不是空断言）"
      % "；".join(_l for _l, _h, _s, _r, _o in counterexamples),
      bool(counterexamples)
      and all(rendered(client_bits(h, s, r, o)) for _l, h, s, r, o in counterexamples),
      "旧写法竟然也画得出按钮 ⇒ 本节的断言没有鉴别力")
check("③ 反例至少覆盖「流体任务（无记录）」与「任意无记录任务」这两类用户点名的情形",
      len(counterexamples) >= 4,
      "反例太少（%d 条），不足以证明「整排消失」这个缺口被堵住" % len(counterexamples))

same = ("ourChain" not in CLIENT_CODE and "ourChain" not in MIXIN_CODE
        and "sequence" not in refresh_body and "ourChain" not in refresh_body)
check("③ 同一状态下，普通任务与序列装配任务渲染的按钮集合完全相同"
      "（按钮显隐与「归不归本模组管」无关 ⇒ 第 56 轮的 ourChain 收窄不是按钮消失的原因）",
      same, "渲染路径里出现了 ourChain / sequence 这类「任务种类」判据")

check("③ 「放不下就整排收起」这一条与任务无关（静态几何），且主槽位收起时**两个主按钮一起收起**"
      "（不会出现「点不了却画着」的半截状态）",
      "rscc$primaryFits = x + slotWidth <= maxRight;" in MIXIN_CODE
      and rendered(SUSPEND_BIT, primary_fits=False) == frozenset()
      and rendered(RESUME_BIT, primary_fits=False) == frozenset(),
      "主槽位收起分支不完整")

print()
print("=" * 78)
print("④ 手动挂起 / 继续的动作路径（仍然有效、且不受 ourChain 限制）")
print("=" * 78)

check("④ 界面「挂起」→ C2S AssemblyTaskActionPacket(ACTION_SUSPEND)",
      "new AssemblyTaskActionPacket(taskId, AssemblyTaskActionPacket.ACTION_SUSPEND)" in MIXIN_CODE,
      "挂起按钮不再发 C2S 包")
check("④ 界面「继续」→ C2S AssemblyTaskActionPacket(ACTION_RESUME)",
      "new AssemblyTaskActionPacket(taskId, AssemblyTaskActionPacket.ACTION_RESUME)" in MIXIN_CODE,
      "继续按钮不再发 C2S 包")
check("④ 服务端分派：继续 → AssemblyWatchdog.resume；挂起 → AssemblyWatchdog.suspend",
      "AssemblyWatchdog.resume(player, packet.taskId())" in ACTION
      and "AssemblyWatchdog.suspend(player, packet.taskId())" in ACTION,
      "服务端动作分派缺失")

suspend_body = body_of(WATCHDOG_CODE, "public static boolean suspend(final ServerPlayer player")
check("④ 手动 suspend 的准入条件**不含** ourChain（普通任务也允许玩家手动挂起）",
      "record.suspendState != SuspendState.RUNNING || record.returning" in suspend_body
      and "ourChain" not in suspend_body,
      "手动 suspend 被 ourChain 拦住了")
check("④ 手动挂起与自动挂起共用唯一实现 suspendRecord（语义一致）",
      "suspendRecord(record, Reason.MANUAL, now);" in WATCHDOG_CODE
      and "suspendRecord(record, record.reason, now);" in WATCHDOG_CODE,
      "手动 / 自动挂起不再共用实现")
check("④ 恢复入口唯一（resumeRecord 只有 2 处引用：定义 + resume 调用）",
      WATCHDOG_CODE.count("resumeRecord") == 2,
      "resumeRecord 引用数 = %d" % WATCHDOG_CODE.count("resumeRecord"))

# ---- 第 63 轮：**服务端一致性**（界面画了按钮 ⇒ 服务端必须处理得了）----
check("④ **无记录时就地补一拍扫描**：suspend 先 findRecord，查不到就 rescanNow 再查一次"
      "（旧实现直接安全失败 ⇒ 玩家观感「按了没反应」）",
      "Record record = findRecord(player, taskId);" in suspend_body
      and "rescanNow(player);" in suspend_body
      and "record = findRecord(player, taskId);" in suspend_body,
      "无记录时服务端不会补建记录")
rescan_body = body_of(WATCHDOG_CODE, "private static void rescanNow(final ServerPlayer player) {")
check("④ 补扫描用的是**同一条只读扫描路径**（scanLevel，全维度）—— 判据一字未改，"
      "补出来的记录与下一拍本来就会建出来的记录完全一致",
      "server.getAllLevels()" in rescan_body and "scanLevel(level, now);" in rescan_body,
      "补扫描走了第二条判定路径")
check("④ **跨维度找记录**：findRecord 先查玩家维度，再遍历全部维度（无线监视器可以跨维度）",
      "for (final Map<UUID, Record> records : RECORDS.values())" in WATCHDOG_CODE
      and "private static ServerLevel levelOf(final ServerPlayer player, final UUID taskId)" in WATCHDOG_CODE,
      "跨维度查找缺失（跨维度点挂起会安全失败）")
resume_body = body_of(WATCHDOG_CODE, "public static boolean resume(final ServerPlayer player")
check("④ 挂起 / 继续成功后**当刻重播快照**（按钮当刻翻转，不必等下一次 1 秒扫描）",
      "broadcast(levelOf(player, taskId));" in suspend_body and "broadcast(level);" in resume_body,
      "挂起 / 继续后没有立刻重播快照")
check("④ 补扫描**没有**放宽任何判据：补建之后照旧要过「RUNNING 且未在回收」这道闸门",
      "record.suspendState != SuspendState.RUNNING || record.returning" in suspend_body,
      "补建的记录绕过了既有闸门")

print()
print("=" * 78)
print("⑤ 几何：按钮行落在面板 / 屏幕内（RS 2.0.0 布局依据）")
print("=" * 78)

# RS 2.0.0 AutocraftingMonitorScreen 自身声明的布局常量（源码事实）
RS_IMAGE_WIDTH = 254      # this.imageWidth = 254
RS_IMAGE_HEIGHT = 231     # this.imageHeight = 231
RS_CANCEL_X_INSET = 7     # cancelButton = pos(leftPos + 7, topPos + 204)
RS_CANCEL_W = 50          # font.width("Cancel") + 14（英文）
RS_CANCEL_ALL_GAP = 4     # cancelAllButton = pos(cancel.getX() + cancel.getWidth() + 4, ...)
RS_CANCEL_ALL_W = 74      # font.width("Cancel All") + 14（英文）
RS_BUTTON_Y_INSET = 204   # topPos + 204
RS_BUTTON_H = 20

RSCC_GAP = 4              # RSCC_BUTTON_GAP
RSCC_PAD = 14             # RSCC_BUTTON_PAD
RSCC_RIGHT_MARGIN = 7     # RSCC_RIGHT_MARGIN

native_row_right = RS_CANCEL_X_INSET + RS_CANCEL_W + RS_CANCEL_ALL_GAP + RS_CANCEL_ALL_W


def layout(label_width, left=0, top=0):
    """与 rscc$layoutButtons 同构；返回可见按钮的 (x, y, w, h) 与两个 fits 标志。"""
    x = left + native_row_right + RSCC_GAP
    slot_width = label_width + RSCC_PAD
    max_right = left + RS_IMAGE_WIDTH - RSCC_RIGHT_MARGIN
    out = {}
    primary_fits = x + slot_width <= max_right
    if primary_fits:
        out["primary"] = (x, top + RS_BUTTON_Y_INSET, slot_width, RS_BUTTON_H)
        machine_x = x + slot_width + RSCC_GAP
    else:
        machine_x = x
    machine_fits = machine_x + label_width + RSCC_PAD <= max_right
    if machine_fits:
        out["machine"] = (machine_x, top + RS_BUTTON_Y_INSET, label_width + RSCC_PAD, RS_BUTTON_H)
    return out, primary_fits, machine_fits


for label, width in (("中文（挂起 / 继续：12px 文本）", 26), ("英文（Suspend：40px 文本）", 54),
                     ("超长本地化（100px 文本）", 114), ("病态超长（174px 文本）", 174)):
    boxes, primary_fits, machine_fits = layout(width)
    inside = all(0 <= bx and bx + bw <= RS_IMAGE_WIDTH - RSCC_RIGHT_MARGIN
                 for (bx, _by, bw, _bh) in boxes.values())
    no_overlap = True
    if "primary" in boxes and "machine" in boxes:
        px, _py, pw, _ph = boxes["primary"]
        mx, _my, _mw, _mh = boxes["machine"]
        no_overlap = px + pw + RSCC_GAP <= mx
    check("⑤ [%s] 画出来的按钮全部落在面板内（右界 ≤ leftPos+%d）且互不重叠"
          % (label, RS_IMAGE_WIDTH - RSCC_RIGHT_MARGIN),
          inside and no_overlap,
          "boxes=%r primaryFits=%s machineFits=%s" % (boxes, primary_fits, machine_fits))

for scaled_height in (240, 300, 480, 1080):
    top = (scaled_height - RS_IMAGE_HEIGHT) // 2
    y = top + RS_BUTTON_Y_INSET
    check("⑤ [缩放后高 %d] 按钮行 y=%d..%d 整行在屏幕内（0 ≤ y 且 y+%d ≤ %d）"
          % (scaled_height, y, y + RS_BUTTON_H, RS_BUTTON_H, scaled_height),
          y >= 0 and y + RS_BUTTON_H <= scaled_height,
          "y=%d 越界（RS 原生取消按钮同一行，若它可见则本行也可见）" % y)

check("⑤ 我们的按钮与 RS 原生取消按钮**同一行同高**（行 y / 行高直接取 cancelButton，"
      "不存在「画到面板外」的独立纵坐标）",
      "final int rowY = cancelButton != null ? cancelButton.getY() : screen.getGuiTop() + 204;"
      in MIXIN_CODE
      and "final int rowH = cancelButton != null ? cancelButton.getHeight() : 20;" in MIXIN_CODE,
      "按钮行不再与原生按钮对齐")
check("⑤ 横向起点 = 原生按钮行右缘 + 4（绝不与原生取消 / 全部取消重叠）",
      "int x = rscc$nativeRowRight() + RSCC_BUTTON_GAP;" in MIXIN_CODE
      and "return cancelAllButton.getX() + cancelAllButton.getWidth();" in MIXIN_CODE,
      "横向起点不再取原生按钮行右缘")

print()
print("=" * 78)
print("⑥ 协议：SyncAssemblyAlertsPacket 的产物资源键「物品 / 流体」两端对称编解码")
print("=" * 78)

PACKET = read(os.path.join("network", "SyncAssemblyAlertsPacket.java"))
PACKET_CODE = strip_comments(PACKET)


def alert_components():
    """从 Alert 的 record 声明里取出分量名（顺序即协议字段顺序）。"""
    start = PACKET_CODE.index("public record Alert(")
    decl = PACKET_CODE[start:PACKET_CODE.index(")", PACKET_CODE.index("@Nullable PlatformResourceKey"))]
    body = decl[decl.index("(") + 1:]
    return [piece.split()[-1] for piece in body.replace("\n", " ").split(",") if piece.strip()]


components = alert_components()
check("⑥ Alert 的分量顺序 = 协议字段顺序，产物资源键 `product` **追加在末尾**"
      "（只增不改 ⇒ 物品 / 流体两端看到的是同一份字段表）",
      components == ["taskId", "reason", "actions", "productName", "productIcon", "amount",
                     "materials", "offlineSteps", "product"],
      "Alert 分量顺序变了：%r" % components)

codec_body = PACKET_CODE[PACKET_CODE.index("public void encode("):PACKET_CODE.index("public SyncAssemblyAlertsPacket decode(")]
decoder_body = PACKET_CODE[PACKET_CODE.index("public SyncAssemblyAlertsPacket decode("):]


def in_order(text, markers):
    """顺序扫描：每个标记都必须出现在上一个标记之后（证明字段顺序）。"""
    pos = -1
    for marker in markers:
        found = text.find(marker, pos + 1)
        if found < 0:
            return False
        pos = found
    return True


ORDER = ["taskId", "reason", "actions", "productName", "productIcon", "product", "amount",
         "material", "step"]
check("⑥ 编码端按 Alert 的字段顺序写出（含末位产物资源键）", in_order(codec_body, ORDER),
      "编码端字段顺序与 Alert 声明不一致")
check("⑥ 解码端按同一顺序读回（两端对称 ⇒ 不会出现「写三读二」这类错位）",
      in_order(decoder_body, ORDER), "解码端字段顺序与 Alert 声明不一致")

check("⑥ **物品 / 流体共用一个编码器、没有按类型分支**："
      "产物资源键走 RS 自己的 ResourceCodecs.STREAM_CODEC（它按 ResourceType 分派到各自子编码器，"
      "因此物品与流体在两端是同一套规则）",
      "ResourceCodecs.STREAM_CODEC.encode(buf, alert.product());" in codec_body
      and "ResourceCodecs.STREAM_CODEC.decode(buf)" in decoder_body
      and "instanceof ItemResource" not in PACKET_CODE
      and "instanceof FluidResource" not in PACKET_CODE,
      "产物资源键出现了按类型分支的第二套编解码")
check("⑥ 资源键可缺省（记录刚建 / 键不可用时为 null）：用布尔前缀表达，两端对称",
      "if (alert.product() == null) {" in codec_body and "buf.writeBoolean(true);" in codec_body
      and "buf.readBoolean()" in decoder_body,
      "资源键的缺省表达两端不对称")
check("⑥ 变化只发生在 Alert 自己的编解码里：包的 TYPE / 注册常量没有被牵动"
      "（注册结果由 tools/check_payload_registration.py 独立把关）",
      "sync_assembly_alerts" in PACKET and "public static final Type<SyncAssemblyAlertsPacket> TYPE" in PACKET_CODE,
      "包的 TYPE / 注册常量被改动")

print()
print("=" * 78)
print("⑦ 不变性：第 56 / 58 / 61 轮的硬结论都还在")
print("=" * 78)

check("⑦ 第 56 轮三闸门原样：自动挂起两道 + 兜底回收一道（普通任务永不被自动挂起 / 自动取消）",
      "record.ourChain && record.sequence" in WATCHDOG_CODE
      and "record.ourChain && !noticeOwned" in WATCHDOG_CODE
      and "!record.ourChain" in WATCHDOG_CODE
      and "record.ourChain = sequenceTask && !hasForeignSink(status);" in WATCHDOG_CODE,
      "第 56 轮的三道闸门被改动")
check("⑦ 第 56 轮的收尾态语义原样：returning ⇒ 服务端不给任何动作位（且「取消必须立刻返回」不受影响）",
      "return SyncAssemblyAlertsPacket.ACTION_BIT_NONE;" in WATCHDOG_CODE
      and "returning" in body_of(WATCHDOG_CODE, "private int actions() {"),
      "returning 的动作位判定被改动")
CHAMBER = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
check("⑦ 第 58 轮硬结论原样：取用侧**一次都没读** scheduled / processing（它们不算别人的预留）",
      ".scheduled()" not in CHAMBER and ".processing()" not in CHAMBER,
      "执行舱里又出现了 scheduled / processing 的读取")
check("⑦ 第 58 轮的另一半：本模组执行器 accept() 仍把每轮投入物原样插回网络",
      "insert(" in CHAMBER, "执行舱的 accept() 回插路径消失")
check("⑦ 第 61 轮的补拉逻辑原样（界面里恰好两条 RequestAssemblyAlertsPacket 发包路径 + 按任务去重）",
      MIXIN_CODE.count("new RequestAssemblyAlertsPacket()") == 2
      and "private UUID rscc$alertsRequested;" in MIXIN_CODE
      and "if (taskId == null || alert != null || taskId.equals(rscc$alertsRequested)) {" in MIXIN_CODE,
      "补拉逻辑被改动（count=%d）" % MIXIN_CODE.count("new RequestAssemblyAlertsPacket()"))
check("⑦ 第 63 轮的双资源扫描判据还在（记录的产生与「资源是不是物品」解耦）",
      "instanceof final PlatformResourceKey resource" in WATCHDOG_CODE
      and "private static Set<PlatformResourceKey> presentResources(final Network network)" in WATCHDOG_CODE
      and "instanceof final FluidResource fluid" in WATCHDOG_CODE,
      "双资源判据缺失或退回「只认 ItemResource」")
check("⑦ 第 63 轮的跨维度快照还在（无线监视器跨维度也能拿到权威动作位）",
      "private static List<SyncAssemblyAlertsPacket.Alert> allAlerts()" in WATCHDOG_CODE
      and "server.getPlayerList().getPlayers()" in WATCHDOG_CODE,
      "跨维度快照缺失")

OK = not PROBLEMS
print()
print("=" * 78)
if OK:
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
else:
    print("SELFCHECK FAILED (%d/%d)" % (len(PROBLEMS), CHECKS[0]))
    for problem in PROBLEMS:
        print("  - " + problem)
sys.exit(0 if OK else 1)
