#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「批 2：序列装配停滞/掉线检测 + 横幅 + 管理器处置」自检。

两类检查：
 1. 源码锚点：把关键规则锚定到具体实现行（防止后续重构把规则悄悄改掉）；
 2. 逻辑推演：用与 Java 同构的小模型跑一遍计时器 / 清理 / uuid / 掉线，断言行为符合验收标准。

可重复执行：python tools/selfcheck_assembly_watchdog.py → 末行「问题总数: 0」。
"""
import json
import re
import sys
from pathlib import Path
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/java/cretae/cookiewyq/rs_create_compat"
WATCHDOG = SRC / "support/AssemblyWatchdog.java"
CONFIG = SRC / "Config.java"
BANNER = SRC / "network/CompletionBannerPayload.java"
TOAST = SRC / "client/CompatCompletionToast.java"
ALERTS = SRC / "network/SyncAssemblyAlertsPacket.java"
ACTION = SRC / "network/AssemblyTaskActionPacket.java"
REQUEST_ALERTS = SRC / "network/RequestAssemblyAlertsPacket.java"
MAIN_SRC = SRC / "RS_Create_Compat.java"
STEP_MACHINE = SRC / "network/AssemblyStepMachinePacket.java"
CANDIDATES = SRC / "network/AssemblyMachineCandidatesPacket.java"
CLIENT = SRC / "client/AssemblyAlertsClient.java"
MONITOR_MIXIN = SRC / "mixin/client/AutocraftingMonitorScreenMixin.java"
TASK_CONTAINER_MIXIN = SRC / "mixin/TaskContainerMixin.java"
NODE_ACCESSOR = SRC / "mixin/accessor/AbstractNetworkNodeContainerBlockEntityAccessor.java"
NODE_ACCESS = SRC / "support/RsccNodeContainerAccess.java"
MENU_ACCESSOR = SRC / "mixin/accessor/AbstractAutocraftingMonitorMenuAccessor.java"
STEP_SELECT = SRC / "client/screen/StepMachineSelectScreen.java"
MIXINS_JSON = ROOT / "src/main/resources/rs_create_compat.mixins.json"
LANG_ZH = ROOT / "src/main/resources/assets/rs_create_compat/lang/zh_cn.json"
LANG_EN = ROOT / "src/main/resources/assets/rs_create_compat/lang/en_us.json"

problems = []


def check(condition, ok_msg, bad_msg):
    if condition:
        print("  [OK] " + ok_msg)
    else:
        print("  [X] " + bad_msg)
        problems.append(bad_msg)


def text(path):
    return path.read_text(encoding="utf-8")


# =====================================================================
print("===== 1. 阈值与配置（可配置 + 默认 100 tick = 5 秒）=====")
cfg = text(CONFIG)
m_stall = re.search(r'defineInRange\("assemblyStallTimeoutTicks",\s*(\d+),\s*(\d+),\s*(\d+)\)', cfg)
m_expiry = re.search(r'defineInRange\("assemblyRecordExpiryTicks",\s*(\d+),\s*(\d+),\s*(\d+)\)', cfg)
check(m_stall is not None and int(m_stall.group(1)) == 100,
      "配置键 assemblyStallTimeoutTicks 默认 %s（=5 秒），范围 %s..%s"
      % (m_stall.group(1), m_stall.group(2), m_stall.group(3)) if m_stall else "",
      "缺少配置键 assemblyStallTimeoutTicks 或默认值不是 100")
check(m_expiry is not None and int(m_expiry.group(1)) == 12000,
      "配置键 assemblyRecordExpiryTicks 默认 %s（兜底过期上限）" % (m_expiry.group(1) if m_expiry else "?"),
      "缺少配置键 assemblyRecordExpiryTicks 或默认值不是 12000")
check("assemblyStallTimeoutTicks = ASSEMBLY_STALL_TIMEOUT_TICKS.get();" in cfg
      and "assemblyRecordExpiryTicks = ASSEMBLY_RECORD_EXPIRY_TICKS.get();" in cfg,
      "两个配置项都在 onLoad 里读入静态字段（运行时生效）",
      "配置项未在 onLoad 里读入静态字段")

# =====================================================================
print("===== 2. 记录结构（uuid / 记录时 tick / 当前 tick / 停滞原因）=====")
wd = text(WATCHDOG)
for anchor, label in (("private final UUID taskId;", "任务 uuid 字段"),
                      ("private final long recordedTick;", "记录时的世界 tick 字段"),
                      ("private long currentTick;", "当前世界 tick 字段"),
                      ("private Reason reason = Reason.NONE;", "停滞原因字段"),
                      ("private long lastSeenTick;", "最近一次被扫描到的 tick（过期判定用）")):
    check(anchor in wd, label, "缺少 " + label)
check("status.info().id().id()" in wd and "records.computeIfAbsent(status.info().id().id()" in wd,
      "记录键 = RS 自动合成任务自带的 TaskId 的 UUID（两条内容相同的任务天然区分）",
      "记录键没有使用 RS TaskId 的 UUID")
check("TaskId.create() 是任务创建时的随机 UUID" in wd or "随机 UUID" in wd,
      "javadoc 说明了 uuid 来源与「重启视为新任务」的选择",
      "缺少 uuid 来源 / 持久化选择的说明")

# =====================================================================
print("===== 3. 计时器（每 tick +1 / 可推进归零 / > 阈值只触发一次）=====")
check("record.stallTicks++;" in wd, "每 tick +1（record.stallTicks++）", "缺少每 tick +1")
check("record.stallTicks = 0;" in wd, "原因消失 / 恢复时归零（record.stallTicks = 0）", "缺少归零逻辑")
check("record.stallTicks > thresholdFor(record.reason)" in wd
      and "private static int thresholdFor(final Reason reason)" in wd,
      "触发条件：原因不是 NONE 且连续计数 > 该原因对应的阈值（阈值判定只有 thresholdFor 一处）",
      "触发条件不是「按原因取阈值」")
check("positive(Config.assemblyStallTimeoutTicks, 100)" in wd
      and "positive(Config.assemblyOfflinePersistTicks, 40)" in wd
      and "positive(Config.assemblyNoProgressTimeoutTicks, 600)" in wd,
      "三种原因各自的阈值都取自配置并带默认值（缺料 100 / 掉线 40 / 无进展 600）",
      "阈值没有全部走配置 + 默认值")
check("record.notified = true;" in wd, "触发时置位 notified（不会每 tick 重复弹）", "触发时没有置位 notified")
# 重置条件：进度变化 / 相关子自动合成在跑 / 执行仓在手料 / 中间产物回流（+ 「一项原料都不缺」）
check("progress || subTaskRunning || chamberInFlight" in wd and "intermediateBack" in wd,
      "可推进判定 = 进度变化 / 子自动合成在跑 / 执行仓在手料 / 中间产物回流 / 一项原料都不缺",
      "可推进判定缺少「子自动合成 / 中间产物回流」条件")
check("relatedSubTaskRunning(" in wd and "hasIntermediate(" in wd,
      "重置条件两条（相关子自动合成 + 中间产物回流）都有独立函数",
      "缺少相关子自动合成或中间产物回流判定")
# 回归：曾经的 `missing.size() < needed.size()`（「还有部分原料就算能推进」）会让缺料横幅在
# 「多种原料里只缺一部分」这种典型场景下永不弹出 —— 现在只认「一项都不缺」。
check("missing.size() < needed.size()" not in wd and "intermediateBack || missing.isEmpty()" in wd,
      "可推进判定只认「一项原料都不缺」（missing.isEmpty），不会把「缺一部分」当成能推进",
      "可推进判定仍把「已有部分原料」当成能推进（缺料横幅会漏报）")
# 横幅第 3 行的「等 N 种」要能走到：materialTotal 必须被真正赋值
check("record.materialTotal = missing.size();" in wd
      and "record.materialTotal > record.materialIcons.size()" in wd,
      "materialTotal 被赋值（缺料多于 3 种时第 3 行会补「等 N 种」）",
      "materialTotal 从未赋值 → 「等 N 种」分支是死代码")

# =====================================================================
print("===== 4. 清理（任务消失即移除 / 过期回收 / 硬上限 / 不漏删活跃）=====")
check("MAX_RECORDS = 256" in wd, "记录总数硬上限 MAX_RECORDS = 256", "缺少记录总数硬上限")
check("records.values().removeIf(record -> !live.contains(record.taskId())" in wd,
      "任务不在本轮 live 集合里 → 立即移除记录", "缺少「任务消失立即移除」")
check("isExpired(nowTick, record.lastSeenTick)" in wd and "currentTick - lastSeenTick > recordExpiryTicks()" in wd,
      "过期回收：currentTick − lastSeenTick > 过期上限（默认 12000 tick）",
      "缺少过期回收判定")
check("ordered.sort((a, b) -> Long.compare(a.recordedTick, b.recordedTick));" in wd
      and "records.remove(ordered.get(i).taskId());" in wd,
      "超上限时丢最旧的记录（确定性、幂等）", "缺少超上限处理")
check("RECORDS.remove(level.dimension())" in wd and "LOADED_CHUNKS.remove(level.dimension())" in wd,
      "维度卸载时清空记录 / 区块表（无泄漏）", "维度卸载没有清理")
check("record.executorPos = executorPos.immutable();" in wd.replace("this.executorPos", "record.executorPos")
      or "this.executorPos = executorPos.immutable();" in wd,
      "样板库坐标取不可变副本（避开可变 BlockPos 引用）",
      "样板库坐标没有取不可变副本")
record_body = wd[wd.index("public static final class Record"):wd.index("public SyncAssemblyAlertsPacket.Alert snapshot()")]
held = [name for name in ("BlockEntity", "Network ", "Level ", "Container ", "MinecraftServer")
        if name in record_body]
check(not held, "记录里只存不可变数据（uuid / 坐标 / 物品栈快照 / 资源键），不钉住 BlockEntity / Network",
      "记录可能持有 BlockEntity / Network 引用：%s" % held)

# =====================================================================
print("===== 5. 卡住检测：掉线（序列装配 + RS 原版两条判定都只保留一份）=====")
check("if (!offline.isEmpty())" in wd and "record.reason = Reason.EXECUTOR_OFFLINE;" in wd,
      "序列装配：样板里指派的执行仓坐标不在同网络里 → 写原因 EXECUTOR_OFFLINE",
      "缺少序列装配掉线判定分支")
offline_block = wd[wd.index("if (!offline.isEmpty())"):wd.index("final List<ItemStack> needed")]
check("record.reason = Reason.EXECUTOR_OFFLINE;" in offline_block
      and "record.materialTotal = 0;" in offline_block
      and "record.sequence" not in offline_block,
      "序列装配掉线分支：写原因 + 清掉缺料快照（原因优先级最高，且只写 reason，不在这里挂起）",
      "序列装配掉线分支不完整")
check("case REJECTED, LOCKED, NONE_FOUND -> sinkBad = true;" in wd
      and "private static void classifyGeneric" in wd,
      "RS 原版任务：直接读 RS 自己给出的卡住信号（REJECTED / LOCKED / NONE_FOUND）→ 掉线",
      "RS 原版任务没有用 RS 自己的 ItemType 信号")
check("item.extracting() > 0 && item.resource() instanceof final ItemResource resource" in wd
      and "!present.contains(resource) && !craftedByOthers(statuses, status, resource)" in wd,
      "RS 原版任务缺料判定 = 想抽 + 网络里没有 + 没有别的任务正在产出它",
      "RS 原版任务缺少缺料判定")
check("record.reason = Reason.NO_PROGRESS;" in wd
      and "record.reason = Reason.EXECUTOR_OFFLINE;" in wd
      and "record.reason = Reason.OUTPUT_BLOCKED;" in wd
      and "record.reason = canAdvance ? Reason.NONE : Reason.MISSING_MATERIAL;" in wd,
      "无进展是兜底判据（前两项不成立时才用）；可推进时 reason 恒为 NONE（正常运行时绝不挂起）；"
      "第 21 轮同步 + 2026-10-05 修正：「推不动」按原因分成两种 reason —— "
      "某步无机器认领 ⇒ EXECUTOR_OFFLINE；下游机器满 / 不接受 ⇒ OUTPUT_BLOCKED（机器在线）",
      "无进展 / 可推进判定不对")
check("status.state() == TaskState.RETURNING_INTERNAL_STORAGE" in wd
      and "record.reason = Reason.NONE;" in wd,
      "不干预 RS 自己的回收（RETURNING_INTERNAL_STORAGE 一律不挂起）",
      "会干预 RS 自己的内部暂存回收")
check("machinePos()" in wd and "chambers.containsKey(pos)" in wd,
      "掉线判定 = 该步指派的执行仓（machinePos）不在同网络的执行仓集合里",
      "掉线判定没有查指派坐标")

# =====================================================================
print("===== 6. 横幅（复用既有实现 / 恒定 5 行 / 不可点击）=====")
banner_body = wd[wd.index("private static void sendBanner"):wd.index("第 3 行（缺料）")]
check(banner_body.count("rows.add(") == 5, "sendBanner 恒定向列表里加 5 行（rows.add 出现 5 次）",
      "sendBanner 行数不是恒定 5：%d" % banner_body.count("rows.add("))
check("List.of(ItemStack.EMPTY, record.productIcon)" in banner_body
      and "record.amount, record.productName), \"\"));" in banner_body
      and "record.sequence ? KEY_PRODUCT : KEY_PRODUCT_AUTO" in banner_body,
      "第 2 行：产物名在前、物品图标紧跟名字之后（序列装配 / RS 原版任务用两套措辞）",
      "第 2 行没有做到「名字后跟图标」或没有区分两种任务")
check("CompatCompletionSender.sendToNearby(" in banner_body
      and "CompletionBannerPayload.Row" in banner_body,
      "复用既有横幅实现（CompatCompletionSender + CompletionBannerPayload，即蓝图装填器那套 Toast）",
      "没有复用既有横幅实现")
for forbidden in ("Button", "onPress", "setOnClick", "ClickEvent", "runCommand", "executeCommand"):
    check(forbidden not in banner_body, "横幅内不含点击相关代码：%s" % forbidden,
          "横幅里出现了点击相关代码：%s" % forbidden)
check("rows.add(reasonRow(record));" in banner_body
      and "private static CompletionBannerPayload.Row reasonRow" in wd
      and "case EXECUTOR_OFFLINE ->" in wd and "case NO_PROGRESS ->" in wd,
      "第 3 行按原因三选一（掉线 / 无进展 / 缺料），行数仍恒为 5",
      "第 3 行不是按原因三选一")
# 既有 Toast 支持逐段颜色 + 语言键（本批新增的两点，需有实现）
toast = text(TOAST)
check("segment.color()" in toast and "record Segment(ItemStack icon, Component text, int color)" in toast,
      "既有 Toast 支持「逐段颜色」（一行内用颜色区分不同原料）", "Toast 不支持逐段颜色")
check("CompletionBannerPayload.LANG_MARKER" in toast and "Component.translatable(parts[0]" in toast,
      "既有 Toast 支持「语言键段」在客户端解析（文案走语言键）", "Toast 不支持语言键段")
check("LANG_MARKER = '\\u0001'" in text(BANNER) and "public static String localized(" in text(BANNER),
      "CompletionBannerPayload 提供 localized(key, args) 供服务端构造语言键段",
      "CompletionBannerPayload 缺少 localized 辅助")

print("===== 7. 语言键（两文件同键、中文 ≤ 40 字）=====")
zh = json.loads(text(LANG_ZH))
en = json.loads(text(LANG_EN))
need = [key for key in zh if key.startswith("gui.rs_create_compat.assembly.")
        or key.startswith("message.rs_create_compat.assembly.")]
missing_en = [key for key in need if key not in en]
# 本轮同步（旧口径 → 新口径）：+1 条 = 缺料横幅的条目键按「物品 / 流体」拆成两条
# （entry.item = 「%1$s ×%2$s 个」/ entry.fluid = 「%1$s ×%2$s mB」，用户要求「资源不足提示缺少单位」），
# 原来的单条 shortage.entry 已删除 ⇒ 缺料横幅由 3 条变 4 条。因此 34 → 35。
# <b>2026-10-06（第 36 轮）43 → 46</b>：用户要求「取到的候选不是最优时给一条提醒」，
# 提示走既有横幅系统，因此新增 assembly.handoff.title / .entry / .more 共 3 条
# （中英两份同时加、键集合一致；每条中文 ≤ 40 字；文案不含「或」/「等 N 种」/「图标轮换」）。
# 该断言是「有没有人偷偷加减这组键」的哨兵，这里同步到新基线。
check(len(need) == 46,
      "语言键 46 条（横幅 18 + 监视器 18 + 缺料横幅 4 + 动作反馈 3 + 换料提示 3 = 46；"
      "横幅本轮新增 output_blocked_prefix / output_blocked_suffix / output_blocked_auto 共 3 条，"
      "用于区分「输出阻塞（机器在但拒收）」与「设备掉线（机器不在）」；"
      "缺料横幅本轮改成「物品 / 流体各一条条目键，单位写在译文里」；"
      "上一版的 action/join、「取消」相关键已删除，"
      "手动挂起新增 monitor.suspend / suspend.tip / suspended.manual / suspended.inflight_tip 与 "
      "message.suspend_failed 共 5 条；界面上的「缺料处置」开关 4 条 monitor.shortage.suspend / .wait / .title / .tip；"
      "2026-10-05 新增 monitor.suspended.output_blocked 1 条 —— 「下游机器满 / 不接受」与"
      "「执行器离线」在监视器上必须显示成两句不同的话，否则玩家会被指去拆一台好机器；"
      "2026-10-06 新增 handoff.title / .entry / .more 共 3 条 —— R1/R2 让「替补件顶上」时"
      "必须告诉玩家「这不是最优那件」，否则玩家会以为取错了料）",
      "语言键数量不是 46：%d" % len(need))
check(not missing_en, "中英文语言文件键集合一致", "英文缺键：%s" % missing_en)
too_long = [key for key in need if len(zh[key]) > 40]
check(not too_long, "中文单条 ≤ 40 字", "中文超长：%s" % too_long)
for key in need:
    if key.endswith(".banner.suspended") or key.endswith(".banner.paused") or key.endswith(".banner.hint"):
        check(bool(zh[key]), "横幅固定行文案存在：%s = %s" % (key, zh[key]), "缺少 " + key)

print("===== 8. 监视器处置（复用既有路径 / 服务端权威 / 安全失败）=====")
action = text(ACTION)
alerts = text(ALERTS)
step_machine = text(STEP_MACHINE)
candidates = text(CANDIDATES)
client = text(CLIENT)
mixin = text(MONITOR_MIXIN)
accessor = text(MENU_ACCESSOR)
select = text(STEP_SELECT)
mixins_json = json.loads(text(MIXINS_JSON))
main_src = text(MAIN_SRC)
request = text(REQUEST_ALERTS)
check("AssemblyWatchdog.resume(player, packet.taskId())" in action,
      "「继续」走 AssemblyWatchdog 的服务端方法", "继续动作没有走服务端方法")
check("if (record == null) {" in wd and "return false; // 任务已消失 → 安全失败" in wd,
      "任务不存在 → 安全失败（返回 false，不误伤别的任务）",
      "缺少「任务不存在安全失败」")
check("if (!known) {" in step_machine and "anyMatch(entry -> entry.pos().equals(packet.pos()))" in step_machine,
      "更换机器写入前校验「该步配方类型下真实存在的执行仓」", "更换机器没有校验候选坐标")
check("inventory.setItem(slot, replacement);" in wd and "stack.copyWithCount(stack.getCount())" in wd
      and "inventory.setChanged();" in wd,
      "更换机器原地改写同一张样板（数量/槽位不变，不复制不销毁）",
      "更换机器的写入方式不安全")
check("StepMachineSelectScreen" in client
      and "confirmSink" in select and "confirmSink.accept(selected);" in select,
      "更换机器复用既有机器选择子界面（StepMachineSelectScreen + 可注入的确认回调）",
      "没有复用既有选择器")

# ---- ③ 删除断言：全模组不存在第二个「取消」入口 ----
check("AssemblyTaskActionPacket.ACTION_RESUME" in mixin
      and "AssemblyTaskActionPacket.ACTION_SUSPEND" in mixin and "requestMachines(" in mixin,
      "监视器界面里三个按钮分别发继续 / 挂起 / 请求候选机器", "监视器按钮动作不全")
check("ACTION_CANCEL" not in mixin and "ACTION_CANCEL" not in action,
      "③ 全模组已无 ACTION_CANCEL：本模组不再提供第二条取消入口", "仍存在 ACTION_CANCEL")
check("AssemblyWatchdog.cancel" not in action and "public static boolean cancel(" not in wd,
      "③ AssemblyWatchdog 不再实现 cancel（取消只走 RS 原生的 AutocraftingNetworkComponent#cancel）",
      "AssemblyWatchdog 仍在重复实现取消")
check("autocrafting.cancel(new TaskId(record.taskId));" in wd
      and "private static boolean reclaim(final ServerLevel level" in wd
      and "assemblySuspendOverflowReclaim" in wd
      and "ACTION_CANCEL" not in action and "ACTION_CANCEL" not in mixin,
      "③ 服务端唯一的取消调用只出现在「挂起超限兜底回收」reclaim() 里（走 RS 自己的 cancel 路径），"
      "且动作包 / 界面都没有第二个取消入口",
      "服务端存在面向玩家的第二条取消路径")
check(mixin.count("Button.builder") == 4,
      "③ 监视器界面只创建 4 个本模组按钮（挂起 / 继续 / 更换机器 / 缺料处置开关；前两个互斥共槽）：实际 %d 个"
      % mixin.count("Button.builder"),
      "监视器界面创建的按钮数不是 4：%d" % mixin.count("Button.builder"))
check("rscc$cancelButton" not in mixin and 'monitor.cancel' not in mixin,
      "③ 本模组自带的「取消」按钮（字段 / 语言键）已彻底删除",
      "仍能查到本模组自带的取消按钮残留")
check("gui.rs_create_compat.assembly.monitor.cancel" not in zh
      and "message.rs_create_compat.assembly.cancelled" not in zh,
      "③ 取消相关的语言键（monitor.cancel / monitor.cancel.tip / assembly.cancelled）已从语言文件删除",
      "语言文件里仍有取消相关键")

# ---- ① 中断态下按钮确实被创建且 visible（创建条件 + 调用链）----
check('@Inject(method = "init", at = @At("TAIL"))' in mixin
      and "rscc$resumeButton = Button.builder" in mixin
      and "rscc$suspendButton = Button.builder" in mixin
      and "rscc$machineButton = Button.builder" in mixin
      and "rscc$addRenderableWidget(rscc$resumeButton);" in mixin
      and "rscc$addRenderableWidget(rscc$suspendButton);" in mixin
      and "rscc$addRenderableWidget(rscc$machineButton);" in mixin,
      "① init 末尾：三个按钮（挂起 / 继续 / 更换机器）都被创建并挂进 Screen 的 renderables（未挂 = 不渲染）",
      "① 按钮没有在 init 末尾被创建 / 挂载")
check("rscc$layoutButtons(screen, rowY);" in mixin and "rscc$refreshButtons();" in mixin
      and '@Inject(method = "render", at = @At("TAIL"))' in mixin
      and '@Inject(method = "currentTaskChanged", at = @At("TAIL"))' in mixin,
      "① 调用链：init(建) → layout(rowY) → refresh；render 每帧 refresh；currentTaskChanged refresh",
      "① 按钮可见性刷新调用链不完整")
check("rscc$apply(rscc$resumeButton, rscc$primaryFits," in mixin
      and "rscc$apply(rscc$suspendButton, rscc$primaryFits," in mixin
      and "rscc$apply(rscc$machineButton, rscc$machineFits," in mixin
      and "SyncAssemblyAlertsPacket.ACTION_BIT_RESUME))" in mixin
      and "SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND))" in mixin
      and "SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE))" in mixin,
      "① 三个按钮的可见性都只来自服务端动作位"
      "（ACTION_BIT_RESUME / ACTION_BIT_SUSPEND / ACTION_BIT_CHANGE_MACHINE）",
      "① 按钮状态没有走服务端动作位")
check(mixin.count("rscc$refreshButtons();") == 6,
      "① 可见性刷新点 6 处：init 建完 / 切换任务 / 每帧 render / 三个按钮点击后各一次"
      "（实为 %d 处）：告警异步到达也能立刻显示与消失，点击也能立刻变灰"
      % mixin.count("rscc$refreshButtons();"),
      "① 刷新点不是 6 处（实际 %d）：界面开着时异步到达的告警可能不显示 / 点击没有即时反馈"
      % mixin.count("rscc$refreshButtons();"))
check("button.visible = view.visible() && fits;" in mixin
      and "button.active = button.visible && view.enabled();" in mixin,
      "① 可见性 = 服务端动作位 && 横向放得下；可用性 = 可见 && 不在途（在途绝不影响可见性）"
      "（visible=false → AbstractWidget 既不绘制也不接点击）",
      "① 按钮只写 active 没写 visible，或可见性里混进了在途状态")
check("graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);" in mixin
      and "rscc$renderAssemblyTooltips" in mixin
      and 'RSCC_LANG + "resume.tip", SyncAssemblyAlertsPacket.ACTION_BIT_RESUME' in mixin
      and 'RSCC_LANG + "suspend.tip", SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND' in mixin
      and 'RSCC_LANG + "change_machine.tip", SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE' in mixin,
      "① 三个按钮都有手动渲染的 tooltip（GUI 不会自动渲染 tooltip）",
      "① 监视器按钮缺少手动 tooltip")

# ---- ④ 点击动作走包 + 服务端校验 ----
check("PacketDistributor.sendToServer(new AssemblyTaskActionPacket(taskId, "
      "AssemblyTaskActionPacket.ACTION_RESUME));" in mixin,
      "④ 继续按钮的点击只发 C2S 包（客户端不直接改服务端状态）",
      "④ 继续按钮没有走包")
check("AssemblyAlertsClient.requestMachines(" in mixin and "PacketDistributor.sendToServer(new AssemblyStepMachinePacket(" in client,
      "④ 更换机器按钮只发 C2S 包（请求候选 / 后续写入）", "④ 更换机器没有走包")
check("AssemblyWatchdog.sendAlerts(player)" in request
      and "RequestAssemblyAlertsPacket.TYPE" in main_src
      and "RequestAssemblyAlertsPacket.STREAM_CODEC" in main_src,
      "④ 新包 RequestAssemblyAlertsPacket 已在 RS_Create_Compat.registerPayloads 注册，服务端只回只读快照",
      "④ 新包未注册 / 服务端处理不对")
check("rscc$currentTaskId()" in mixin and "@Accessor(\"currentTaskId\")" in accessor,
      "当前选中任务 id 经 @Accessor 桥接读取（不改 RS 的包级私有方法可见性）",
      "没有桥接读取当前任务 id")
check("accessor.AbstractAutocraftingMonitorMenuAccessor" in mixins_json["mixins"]
      and "client.AutocraftingMonitorScreenMixin" in mixins_json["client"],
      "两个新 Mixin 都已在 mixins.json 注册", "Mixin 未注册")
check("REASON_EXECUTOR_OFFLINE = 2" in alerts and "REASON_MISSING_MATERIAL = 1" in alerts,
      "告警快照带原因序号常量（客户端据此显示挂起原因文案）",
      "告警快照缺少原因常量")
check("ACTION_BIT_RESUME = 1" in alerts and "ACTION_BIT_CHANGE_MACHINE = 2" in alerts
      and "ACTION_BIT_SUSPEND = 4" in alerts
      and "public boolean offers(final int actionBit)" in alerts,
      "告警快照带「服务端允许的动作位」（唯一按钮可见性判据 + offers() 读取器；"
      "本轮新增 ACTION_BIT_SUSPEND = 4，追加在末尾不动既有位）",
      "告警快照缺少动作位 / offers()")
check("if (player.containerMenu instanceof AbstractAutocraftingMonitorContainerMenu)" in wd,
      "告警只发给「正开着自动合成监视器」的玩家（不刷包）",
      "告警广播目标不是监视器玩家")

# ---- ① / 根因修复：界面打开即拉一次快照（否则告警产生时不在场的玩家永远看不到按钮）----
check("PacketDistributor.sendToServer(new RequestAssemblyAlertsPacket());" in mixin,
      "① init 末尾主动请求补发告警快照（修复「告警产生时没开着监视器 → 本地无快照 → 按钮永不渲染」）",
      "① init 没有请求补发快照：告警产生时不在场的玩家永远看不到按钮")

# ---- ② 布局：与原生「取消 / 取消全部」同一行、右侧并排；放不下才收起 ----
check("private Button cancelButton;" in mixin and "private Button cancelAllButton;" in mixin
      and "@Shadow" in mixin,
      "② 通过 @Shadow 读取 RS 原生「取消 / 取消全部」按钮（行 y / 行高 / 右缘都取自它们，"
      "不复制 RS 的布局常量）",
      "② 没有 shadow 原生按钮：行位置可能与 RS 版本漂移")
check("final int rowY = cancelButton != null ? cancelButton.getY() : screen.getGuiTop() + 204;" in mixin
      and "final int rowH = cancelButton != null ? cancelButton.getHeight() : 20;" in mixin,
      "② 行 y / 行高 = 原生取消按钮（同一行 = 原生那排可见时我们必然可见）",
      "② 行位置没有与原生按钮对齐")
check("int x = rscc$nativeRowRight() + RSCC_BUTTON_GAP;" in mixin,
      "② 起点 = 原生按钮那一排的右缘 + 4（绝不与原生按钮重叠）",
      "② 起点没有紧贴原生按钮右缘")
check("final int maxRight = screen.getGuiLeft() + screen.getXSize() - RSCC_RIGHT_MARGIN;" in mixin
      and "if (button == null || x + button.getWidth() > maxRight) {" in mixin
      and "return false;" in mixin,
      "② 右界 = 面板内侧右留白；越界的按钮一律收起（visible 恒 false），绝不越出面板 / 屏幕",
      "② 缺少右界裁剪 / 收起逻辑")
check("rscc$primaryFits" in mixin and "rscc$machineFits" in mixin
      and "rscc$apply(rscc$resumeButton, rscc$primaryFits," in mixin
      and "rscc$apply(rscc$suspendButton, rscc$primaryFits," in mixin
      and "rscc$apply(rscc$machineButton, rscc$machineFits," in mixin
      and "button.visible = view.visible() && fits;" in mixin,
      "② 「放得下」（init 里算一次的静态几何：挂起 / 继续共槽取较宽）与「服务端动作位」取与"
      "（收起不会与按需显示互相打架）",
      "② 收起标志没有参与可见性判定")


# =====================================================================
print("===== 9. 推演：计时器 / 清理 / uuid / 掉线 =====")
THRESHOLD = 100
EXPIRY = 12000


class Rec(object):
    """与 Java 同构的极小模型。"""

    def __init__(self, task_id, tick, product):
        self.id = task_id
        self.recorded = tick
        self.last_seen = tick
        self.current = tick
        self.stall = 0
        self.stalled = False
        self.notified = False
        self.suspended = False
        self.reason = "NONE"
        self.product = product
        self.banners = 0


def tick(records, level_tick):
    for record in records.values():
        record.current = level_tick
        if record.stalled:
            record.stall += 1
        else:
            record.stall = 0
        if record.stalled and not record.notified and record.stall > THRESHOLD:
            record.notified = True
            record.suspended = True
            record.reason = "MISSING_MATERIAL"
            record.banners += 1


def scan(records, live, can_advance, level_tick):
    """live：任务仍存在的 uuid 集合；can_advance：uuid → 可否推进。

    <p>注意：本模型只覆盖「计时器 / 清理 / uuid」；<b>不含自动恢复</b>——
    可推进只把计时器归零（未挂起时），<b>绝不</b>把已挂起的任务解挂（挂起只由「继续」解除，
    见 10.5 的 SuspendSim）。</p>
    """
    for task_id in live:
        if task_id not in records:
            records[task_id] = Rec(task_id, level_tick, "精密构件")
        record = records[task_id]
        record.last_seen = level_tick
        if can_advance.get(task_id, False):
            record.stalled = False
            record.stall = 0
            record.reason = "NONE"
        else:
            record.stalled = True
            record.reason = "MISSING_MATERIAL"
    records_keys = list(records.keys())
    for task_id in records_keys:
        record = records[task_id]
        if task_id not in live or (level_tick - record.last_seen) > EXPIRY:
            del records[task_id]
    if len(records) > 256:
        ordered = sorted(records.values(), key=lambda r: r.recorded)
        for record in ordered[:len(records) - 256]:
            del records[record.id]


# 9.1 每 tick +1，> 阈值恰好触发一次
records = {}
scan(records, {"t1"}, {"t1": False}, 0)
for t in range(1, 400):
    tick(records, t)
    if t % 20 == 0:
        scan(records, {"t1"}, {"t1": False}, t)
rec = records["t1"]
check(rec.banners == 1, "停滞任务在 400 tick 内恰好触发 1 次处置（实际 %d 次）" % rec.banners,
      "触发次数不是 1：%d" % rec.banners)
# 找到第一次触发的 tick：阈值 100 ⇒ 第 101 个停滞 tick
records2 = {}
scan(records2, {"t2"}, {"t2": False}, 0)
first = None
for t in range(1, 200):
    tick(records2, t)
    if records2["t2"].banners == 1 and first is None:
        first = t
check(first == THRESHOLD + 1, "首次触发发生在第 %d 个停滞 tick（阈值 100 ⇒ 第 101 tick）" % first,
      "首次触发 tick 不是阈值 +1：%s" % first)

# 9.2 可推进即归零：停滞 90 tick 后到达料 → 重新数，不会误触发
records3 = {}
scan(records3, {"t3"}, {"t3": False}, 0)
for t in range(1, 91):
    tick(records3, t)
scan(records3, {"t3"}, {"t3": True}, 100)      # 第 100 tick 有料可推进
check(records3["t3"].stall == 0 and not records3["t3"].suspended
      and records3["t3"].banners == 0,
      "料到了 → 计时器归零且未触发（banners=%d）" % records3["t3"].banners,
      "可推进时没有归零")
for t in range(101, 300):
    tick(records3, t)
    if t % 20 == 0:
        scan(records3, {"t3"}, {"t3": False}, t)
check(records3["t3"].banners == 1, "再次停滞 100 tick 后重新触发一次（重置是「重新计时」而非不再触发）",
      "重置后不再触发，不符合「重新开始计时」")

# 9.3 清理：任务消失即移除 / 过期回收 / 活跃任务不被误删
records4 = {}
scan(records4, {"a", "b"}, {"a": False, "b": False}, 0)
scan(records4, {"a"}, {"a": False}, 20)                      # b 任务消失
check("b" not in records4 and "a" in records4, "任务消失的记录被立即移除，活跃记录保留",
      "清理规则误删了活跃记录或漏删消失记录")
stale = Rec("zombie", 0, "x")
stale.last_seen = 0
records4["zombie"] = stale
scan(records4, {"a"}, {"a": True}, EXPIRY + 1)               # 兜底：超过过期上限的记录被回收
check("zombie" not in records4, "超过过期上限（%d tick）的记录被兜底回收" % EXPIRY,
      "过期记录没有被回收")
check("a" in records4, "过期回收没有漏删活跃任务（a 仍在）", "过期回收误删了活跃任务")

# 9.4 uuid：两批「同样下 10 个精密构件」的任务互不覆盖
records5 = {}
scan(records5, {"uuid-A", "uuid-B"}, {"uuid-A": False, "uuid-B": False}, 0)
for t in range(1, 60):
    tick(records5, t)
for t in range(60, 105):
    tick(records5, t)
check(len(records5) == 2, "两条内容完全相同的任务各占一条记录（按 uuid 区分，互不覆盖）",
      "两条同内容任务被合并成一条")
check(records5["uuid-A"].stall == records5["uuid-B"].stall, "两条记录各自独立计时（tick 数一致）",
      "两条记录计时不一致")

# 9.5 掉线：即时触发（不等 5 秒 / 不看 stallTicks）
records6 = {}
scan(records6, {"d1"}, {"d1": True}, 0)
record = records6["d1"]
# 掉线分支：直接 suspended + 立即弹一次
record.stalled = False
record.stall = 0
record.suspended = True
record.banners += 1
record.reason = "EXECUTOR_OFFLINE"
check(record.suspended and record.banners == 1 and record.stall == 0 and record.reason == "EXECUTOR_OFFLINE",
      "掉线在扫描当刻即挂起 + 弹横幅（stallTicks 仍为 0，不受 100 tick 约束）",
      "掉线没有即时触发")

# 9.6 可推进判定的语义（与 Java 同构）：只有「一项原料都不缺」才算能推进。
#     关键回归：「多种原料里只缺一部分」= 缺料卡住的典型形态，必须<b>不</b>算可推进。
def can_advance(progress, sub_task, chamber_in_flight, intermediate_back, missing_count):
    return progress or sub_task or chamber_in_flight or intermediate_back or missing_count == 0


check(not can_advance(False, False, False, False, 1) and not can_advance(False, False, False, False, 2),
      "需求 2 种、缺 1~2 种（其余还在）→ 不算可推进 ⇒ 5 秒后照常弹缺料横幅",
      "「只缺一部分原料」被当成能推进 ⇒ 缺料横幅会漏报")
check(can_advance(False, False, False, False, 0),
      "一项原料都不缺 → 视为可推进（卡住的原因不是缺料，不走缺料文案）",
      "「一项都不缺」被当成停滞 ⇒ 会误报缺料横幅")
check(can_advance(True, False, False, False, 2), "进度在动 → 可推进（归零重新计时）", "进度判定失效")
check(can_advance(False, True, False, False, 2), "相关子自动合成在跑 → 可推进", "子自动合成判定失效")
check(can_advance(False, False, False, True, 2), "中间产物回流 → 可推进", "中间产物回流判定失效")
check(can_advance(False, False, True, False, 2), "执行仓里还压着料 → 可推进", "执行仓在手料判定失效")

# =====================================================================
print("===== 10. 挂起 / 恢复（序列装配任务 + RS 原版任务共用同一套）=====")
print("--- 10.1 配置与状态机 ---")
for key, default in (("assemblyOfflinePersistTicks", 40),
                     ("assemblyNoProgressTimeoutTicks", 600),
                     ("assemblySuspendLimitTicks", 12000)):
    m = re.search(r'defineInRange\("%s",\s*(-?\d+),' % key, cfg)
    check(m is not None and int(m.group(1)) == default,
          "配置 %s 默认 %s" % (key, default),
          "缺少配置 %s 或默认值不是 %s" % (key, default))
check('define("assemblySuspendOverflowReclaim", false)' in cfg,
      "配置 assemblySuspendOverflowReclaim 默认 false = 继续挂起（永不自动取消）",
      "缺少「挂起超限」处置配置或默认值不是 false")
check("assemblyOfflinePersistTicks = ASSEMBLY_OFFLINE_PERSIST_TICKS.get();" in cfg
      and "assemblyNoProgressTimeoutTicks = ASSEMBLY_NO_PROGRESS_TIMEOUT_TICKS.get();" in cfg
      and "assemblySuspendLimitTicks = ASSEMBLY_SUSPEND_LIMIT_TICKS.get();" in cfg
      and "assemblySuspendOverflowReclaim = ASSEMBLY_SUSPEND_OVERFLOW_RECLAIM.get();" in cfg,
      "四个新配置项都在 onLoad 里读入静态字段（运行时生效）",
      "新配置项未在 onLoad 里读入")
check("public enum SuspendState" in wd and "RUNNING," in wd and "SUSPENDED" in wd
      and "PROBING," not in wd and "SuspendState.PROBING" not in wd and "case PROBING" not in wd,
      "状态机两态：RUNNING / SUSPENDED（PROBING 探测窗口已随「自动恢复」一起移除）",
      "SuspendState 不是两态 / 仍残留 PROBING")
check("private static void advanceSuspendState" in wd
      and "advanceSuspendState(level, record, now, reclaimed);" in wd
      and "for (final UUID taskId : reclaimed)" in wd,
      "「挂起 / 恢复」判定只有一处（advanceSuspendState）；超限回收的记录在遍历结束后才删（不改表时遍历）",
      "挂起判定散落多处，或边遍历边删记录")
check("record.suspended = state != SuspendState.RUNNING;" in wd,
      "挂起标记与状态机一起写回（永不失去同步）", "挂起标记与状态机可能失配")
check("REASON_NO_PROGRESS = 3" in alerts and "NO_PROGRESS" in wd,
      "网络包新增 REASON_NO_PROGRESS = 3（追加在末尾，不改动既有序号）",
      "网络包缺少无进展原因常量")

print("--- 10.2 ② 释放占用：挂起的唯一实现点（只跳过「合成途中」的 step） ---")
mix = text(TASK_CONTAINER_MIXIN)
check('at = @At("HEAD")' in mix and "cancellable = true" in mix and "cir.setReturnValue(false);" in mix,
      "② TaskContainer 单任务 step 起始处取消并返回 false（任务保留在列表里，不被移除）",
      "② 挂起注入不完整")
check('method = "step(Lcom/refinedmods/refinedstorage/api/autocrafting/task/Task;' in mix,
      "② 只暂停指定的那一条任务（注入私有单任务重载；公开重载会跳过整批任务）",
      "② 注入目标不对（可能一次跳过整批任务）")
# 本轮修正（用户原话「取消时，不管是怎么样的，你要立刻返回」）：跳过不再「跳过一切」——
# 只对 RS 自己的「合成途中」状态生效；一旦进入 RETURNING_INTERNAL_STORAGE / COMPLETED 必须放行，
# 否则玩家按原生「取消」后 RS 自己的内部暂存回收会被永久冻住（只能等记录过期，约 10 分钟）。
check("case READY, EXTRACTING_INITIAL_RESOURCES, RUNNING -> true;" in mix
      and "case RETURNING_INTERNAL_STORAGE, COMPLETED -> false;" in mix
      and "if (rscc$isCrafting(task.getState())) {" in mix
      and "AssemblyWatchdog.onTaskTerminated(task.getId().id());" in mix,
      "② 跳过只作用于「进行中的 crafting 状态」（READY / EXTRACTING_INITIAL_RESOURCES / RUNNING）；"
      "收尾 / 结束态（RETURNING_INTERNAL_STORAGE / COMPLETED）放行 RS 自己的回收并通知 watchdog 同步清理"
      "（旧口径「挂起就一律跳过」会把取消后的回收也冻住 → 已修正）",
      "② 跳过仍是「跳过一切」/ 没有按 RS 状态放行")
check("AssemblyWatchdog.isSuspended(task.getId().id())" in mix
      and "public static boolean isSuspended(final UUID taskId)" in wd
      and "if (taskId == null || RECORDS.isEmpty())" in wd,
      "② 挂起判定只读、客户端安全（挂起表只由服务端扫描填充）",
      "② 挂起判定不是只读 / 非客户端安全")
check("PatternProviderNetworkNode" in text(ROOT / "local_src/rs_src/com/refinedmods/refinedstorage/api/network/impl/node/patternprovider/PatternProviderNetworkNode.java")
      and "tasks.step(network, stepBehavior, this);" in text(
          ROOT / "local_src/rs_src/com/refinedmods/refinedstorage/api/network/impl/node/patternprovider/PatternProviderNetworkNode.java")
      and "tasks.step(outputNode.getNetwork(), stepBehavior, this);" in text(
          ROOT / "local_src/rs_src/com/refinedmods/refinedstorage/api/network/impl/node/relay/RelayOutputPatternProvider.java"),
      "② 自动合成器与中继都经由 TaskContainer.step 推进 → 一处注入覆盖全部提供者",
      "② 仍有别的推进路径没被覆盖")
# ③ 守恒：挂起注入的**代码**里不搬运任何物品 / 流体（javadoc 里提到 API 名不算）
mix_code = re.sub(r"/\*.*?\*/", "", mix, flags=re.S)
mix_code = re.sub(r"//[^\n]*", "", mix_code)
for forbidden in ("extract(", "insert(", "setItem(", "internalStorage", "rootStorage", "sink.accept"):
    check(forbidden not in mix_code,
          "③ 挂起注入的代码不含任何物品 / 流体搬运：%s" % forbidden,
          "③ 挂起注入的代码里出现了搬运：%s（可能破坏守恒）" % forbidden)
check("TaskContainerMixin" in mixins_json["mixins"]
      and "accessor.AbstractNetworkNodeContainerBlockEntityAccessor" in mixins_json["mixins"],
      "两个新 Mixin 都已在 mixins.json 注册", "新 Mixin 未注册")
check("RsccNodeContainerAccess" in text(NODE_ACCESS) and "@Accessor(\"mainNetworkNode\")" in text(NODE_ACCESSOR)
      and "@Mixin(AbstractNetworkNodeContainerBlockEntity.class)" in text(NODE_ACCESSOR)
      and "@Shadow" not in wd and "rscc$mainNetworkNode()" in wd,
      "扫描入口用 @Accessor 读基类 mainNetworkNode（不 shadow 继承字段），普通代码只引用 support 接口",
      "扫描入口的 accessor 写法违规（继承 shadow / 直接引用 mixin 类）")

print("--- 10.3 ④ 一律手动恢复：挂起后不自动恢复（本轮撤销自动恢复） ---")
check("PROBING," not in wd and "SuspendState.PROBING" not in wd and "case PROBING" not in wd
      and "markObservedProgress" not in wd
      and "MIN_BACKOFF_TICKS" not in wd and "MAX_BACKOFF_TICKS" not in wd and "PROBE_TICKS" not in wd,
      "④ 自动恢复相关实现（PROBING 态 / 退避 / 探测窗口 / markObservedProgress）已全部删除",
      "④ 仍残留自动恢复相关代码")
check("卡住原因消失" not in wd and "立刻彻底恢复" not in wd
      and "record.reason == Reason.NONE && record.suspendState != SuspendState.RUNNING" not in wd,
      "④ 「原因变回 NONE 就当场恢复」的旧捷径已删除（扫描把原因判回正常也不解挂）",
      "④ 仍存在「原因正常即自动恢复」的实现")
check("if (record.suspendState == SuspendState.RUNNING) {" in wd
      and "record.suspendState = state;" in wd
      and wd.count("resumeRecord") == 2,
      "④ 只有 RUNNING 态才做「挂起」判定；resumeRecord 只有一个调用点（AssemblyWatchdog.resume，"
      "即玩家点「继续」）—— 恢复入口唯一",
      "④ 挂起状态机结构不对 / 恢复入口不唯一")
check("AssemblyTaskActionPacket.ACTION_RESUME" in mixin
      and "PacketDistributor.sendToServer(new AssemblyTaskActionPacket(taskId, "
          "AssemblyTaskActionPacket.ACTION_RESUME));" in mixin
      and "AssemblyWatchdog.resume(player, packet.taskId())" in action,
      "④ 手动恢复走 C2S 包 + 服务端校验（AssemblyTaskActionPacket → AssemblyWatchdog.resume）",
      "④ 手动恢复没有走包 / 没有服务端校验")
check("if (record == null) {" in wd and "return false; // 任务已消失 → 安全失败" in wd,
      "④ 任务不存在 → 安全失败（不误伤别的任务）", "④ 缺少「任务不存在安全失败」")

print("--- 10.4 ⑤ 上限与兜底 / 挂起标记 ---")
check("record.overflowNotified" in wd and "overflowHandle(level, record, reclaimed);" in wd
      and "suspendLimitTicks()" in wd and "Config.assemblySuspendOverflowReclaim" in wd,
      "⑤ 连续挂起超过上限 → 提示一次并按配置决定最终处置（HOLD / RECLAIM）",
      "⑤ 缺少挂起上限与兜底")
check("autocrafting.cancel(new TaskId(record.taskId));" in wd and "records.remove(taskId);" in wd
      and "private static boolean reclaim" in wd,
      "⑤ 兜底「安全回收」= 调 RS 自己的取消路径（TaskImpl 会把内部暂存原样还回网络，不销毁）",
      "⑤ 兜底回收不是走 RS 自己的取消路径")
check("rscc$renderMarker" in mixin and "rscc$suspendedKey" in mixin
      and "suspended.offline" in mixin and "suspended.missing" in mixin and "suspended.noprog" in mixin
      and "REASON_MANUAL -> RSCC_LANG + \"suspended.manual\"" in mixin,
      "⑤ 监视器上有明确的挂起标记（四种原因各有文案：掉线 / 缺料 / 无进展 / 玩家手动挂起）",
      "⑤ 监视器缺少挂起标记或手动挂起标记")
# 本轮同步（旧口径 → 新口径）：tooltip 从「单行」改为「两行」——第一行仍是 suspended.tip，
# 第二行 suspended.inflight_tip 解释 RS 画的「处理中：N」是在途件数（挂起期间冻结）。
check('lines.add(Component.translatable(RSCC_LANG + "suspended.tip").getVisualOrderText());' in mixin
      and 'lines.add(Component.translatable(RSCC_LANG + "suspended.inflight_tip").getVisualOrderText());'
          in mixin
      and "graphics.renderTooltip(font, lines, mouseX, mouseY);" in mixin,
      "⑤ 挂起标记也有手动渲染的 tooltip（GUI 不会自动渲染 tooltip；本轮起为两行）",
      "⑤ 挂起标记缺少手动 tooltip")
check("RSCC_MARKER_Y = 226" not in mixin and "RSCC_MARKER_Y = 7" in mixin
      and "RSCC_TITLE_X = 7" in mixin,
      "⑤ 挂起原因已从「按钮行下方（y=226，行底 235 越出面板）」挪到「原生标题行（y=7）」",
      "⑤ 挂起原因仍画在按钮行下方 / 没有对齐原生标题行")
check("graphics.drawString(font, shown, right - textWidth, y, rscc$markerColor(alert.reason()), false);" in mixin
      and "plainSubstrByWidth" in mixin and "getGuiScaledHeight() - lineHeight" in mixin,
      "⑤ 挂起原因右对齐到面板右内缘、按可用宽度截断、并夹进屏幕矩形（任何缩放都不越界）",
      "⑤ 挂起原因没有右对齐 / 没有截断 / 没有兜底夹取")

print("--- 10.5 推演：状态机 / 释放占用 / 一律手动恢复 ---")
THRESHOLDS = {"EXECUTOR_OFFLINE": 40, "MISSING_MATERIAL": 100, "NO_PROGRESS": 600}
SUSPEND_LIMIT = 12000


class SuspendSim(object):
    """与 Java 的 advanceSuspendState 同构的极小模型：只有 RUNNING / SUSPENDED，绝无自动恢复。"""

    def __init__(self):
        self.state = "RUNNING"
        self.reason = "NONE"
        self.ticks = 0
        self.since = 0
        self.banners = 0
        self.suspended_ticks = 0
        self.overflow = 0

    def tick(self, now, reason, progressed):
        # 扫描侧（与 Java 的 scanNetwork 同构）：已挂起 → 冻结原因、不再重新分类；
        # 未挂起 → 按本次扫描结果写原因（progressed = 观察到进展，序列装配走 canAdvance）。
        if self.state == "RUNNING":
            self.reason = "NONE" if progressed else reason
        # 计时 + 挂起判定（与 Java 的 advanceSuspendState 同构）：<b>不含任何自动恢复</b>。
        self.ticks = 0 if self.reason == "NONE" else self.ticks + 1
        if self.state == "RUNNING" and self.reason != "NONE" and self.ticks > THRESHOLDS[self.reason]:
            self.state = "SUSPENDED"
            self.banners += 1
            self.since = now
        if self.state != "RUNNING":
            self.suspended_ticks += 1
            if self.overflow == 0 and now - self.since > SUSPEND_LIMIT:
                self.overflow = 1

    def resume(self):
        """= AssemblyTaskActionPacket.ACTION_RESUME → AssemblyWatchdog.resume（唯一恢复入口）。"""
        self.state = "RUNNING"
        self.reason = "NONE"
        self.ticks = 0


# ① 正常运行时绝不挂起：进度每 10 tick 变一次，1000 tick 内一次都不挂
sim = SuspendSim()
for t in range(1000):
    sim.tick(t, "NONE", t % 10 == 0)
check(sim.state == "RUNNING" and sim.banners == 0,
      "① 正常推进的任务（reason 恒 NONE）1000 tick 内一次都不挂起",
      "① 正常任务被误挂起")

# ① 三种原因各自在阈值之后才挂起（不早不晚）：连续卡住计数 > 阈值 → 恰好在第 threshold tick
for reason, threshold in THRESHOLDS.items():
    sim = SuspendSim()
    first = None
    for t in range(threshold + 20):
        sim.tick(t, reason, False)
        if sim.state != "RUNNING" and first is None:
            first = t
    check(first == threshold,
          "① %s：第 %d tick 挂起（连续卡住计数超过阈值 %d 才动手，不会提前）"
          % (reason, first, threshold),
          "① %s 的挂起时机不对（应在第 %d tick，实际 %s）" % (reason, threshold, first))

# ① 间歇性卡住不挂起（瞬时 REJECTED 场景）：每隔 5 tick 有一次进展就绝不挂
sim = SuspendSim()
for t in range(1000):
    sim.tick(t, "EXECUTOR_OFFLINE", t % 5 == 0)
check(sim.state == "RUNNING" and sim.banners == 0,
      "① 间歇性卡住（每 5 tick 有进展）不会被挂起 ⇒ 机器输入口短暂满掉不会误挂",
      "① 间歇性卡住被误挂起")

# ② 释放占用：A 挂起后，同一个仓的 step 循环里只有 B 被推进
class ContainerSim(object):
    def __init__(self, suspended):
        self.tasks = {"A": 0, "B": 0}
        self.suspended = suspended

    def step(self):
        stepped = []
        for task in self.tasks:
            if task in self.suspended:
                continue  # = TaskContainerMixin 的 cir.setReturnValue(false)
            self.tasks[task] += 1
            stepped.append(task)
        return stepped


container = ContainerSim(set())
for _ in range(100):
    container.step()
before = dict(container.tasks)
container.suspended.add("A")
stepped = container.step()
check(stepped == ["B"] and container.tasks["A"] == before["A"] and container.tasks["B"] == before["B"] + 1,
      "② A 挂起后该仓的 step 只推进 B（A 冻结不再抽取网络原料 / 不再向机器投料）",
      "② 挂起后 A 仍在被推进")

# ③ 守恒：挂起前后「任务内部暂存 + 网络库存」总量不变（挂起只跳过 step，不搬运）
internal, network = 12, 340
total_before = internal + network
# 挂起期间：不抽料（-0）、不投料（-0）、不回流（-0）
container.suspended.add("A")
for _ in range(600):
    container.step()
check(internal + network == total_before,
      "③ 挂起 600 tick 后「任务内部暂存 %d + 网络库存 %d」与挂起前完全一致（不复制 / 不销毁）"
      % (internal, network),
      "③ 挂起期间物品 / 流体总量发生变化")

# ② 挂起后不自动恢复：原因恢复正常 + 持续进展，经过任意时长也仍保持 SUSPENDED，直到「继续」
sim = SuspendSim()
for t in range(200):
    sim.tick(t, "EXECUTOR_OFFLINE", False)          # 掉线连续 > 40 tick → 挂起
check(sim.state == "SUSPENDED" and sim.banners == 1,
      "② 掉线超过阈值 → 挂起一次（只弹一次横幅）", "② 掉线没有在阈值后挂起")
resumed_at = None
for t in range(200, 100000):                        # 机器回到网络 + 任意长时间 + 持续有进展
    sim.tick(t, "NONE", True)
    if sim.state == "RUNNING":
        resumed_at = t
        break
check(resumed_at is None and sim.state == "SUSPENDED",
      "② 原因恢复正常后经过任意时长，状态仍为 SUSPENDED（一律不自动恢复）",
      "② 出现了自动恢复（原因正常 / 有进展即解挂）")
check(sim.banners == 1, "② 挂起期间不重复弹横幅（notified 只触发一次）",
      "② 挂起状态被反复触发横幅")
sim.resume()
check(sim.state == "RUNNING" and sim.reason == "NONE",
      "② 收到 ACTION_RESUME（AssemblyWatchdog.resume）后才变 RUNNING",
      "② 手动「继续」没有解除挂起")

# ③ 不误挂起：正常推进（原因恒 NONE）任何时长都不挂起
sim = SuspendSim()
for t in range(200000):
    sim.tick(t, "NONE", True)
check(sim.state == "RUNNING" and sim.banners == 0,
      "③ 正常推进的任务（reason 恒 NONE）20 万 tick 内一次都不挂起",
      "③ 正常任务被误挂起")

# ⑤ 上限与兜底
sim = SuspendSim()
for t in range(SUSPEND_LIMIT + 400):
    sim.tick(t, "MISSING_MATERIAL", False)
check(sim.overflow == 1 and sim.state != "RUNNING",
      "⑤ 连续挂起超过上限（%d tick）触发一次处置提示（默认 HOLD：仍保持挂起，不静默丢失）"
      % SUSPEND_LIMIT,
      "⑤ 挂起超限没有任何提示")

# =====================================================================
print("===== 11. 本轮：挂起原因挪位 / 单击一次即响应 / 按钮集合只随服务端状态 / 零副作用 =====")

print("--- 11.1 ① 挂起原因的新落点（标题行右侧；几何推演在 tmp_textures/verify_gui_layout.py）---")
base_screen = text(ROOT / "local_src/rs_src/com/refinedmods/refinedstorage/common/support/AbstractBaseScreen.java")
check("this.titleLabelX = 7;" in base_screen and "this.titleLabelY = 7;" in base_screen,
      "① 落点依据 RS 自己的标题行（titleLabelX/titleLabelY = 7）：标题行右侧整段空闲，"
      "在那儿画一行状态文字既不用自造横条、也永远在面板内",
      "① RS 标题行锚点缺失")
check("RSCC_MARKER_Y = 226" not in mixin and "RSCC_MARKER_Y = 7" in mixin
      and "RSCC_MARKER_RIGHT = 7" in mixin and "RSCC_MARKER_MIN_WIDTH = 16" in mixin,
      "① 落点常量：行 y=7（与原生标题同基线）、右内缘留白 7（与 RS 左边距对称）、"
      "可用宽度 <16 就整行不画；旧的 y=226（行底 235 > 面板高 231，且高缩放时被屏幕下沿裁掉）已消失",
      "① 落点常量不完整或旧坐标仍在")
check("int y = rscc$clamp(screen.getGuiTop() + RSCC_MARKER_Y, 0," in mixin
      and "window.getGuiScaledHeight() - lineHeight);" in mixin
      and "0, window.getGuiScaledWidth());" in mixin,
      "① 兜底夹取：行 y ∈ [0, 屏高 − 行高]、右界 ∈ [0, 屏宽] ⇒ 面板比屏幕还高时也不越界",
      "① 缺少兜底夹取")
check("plainSubstrByWidth(text, budget) + RSCC_ELLIPSIS" in mixin,
      "① 文案按可用宽度截断并补「…」（绘制宽度 ≤ 可用宽度，构造上不可能越出面板右缘）",
      "① 缺少按可用宽度截断")

print("--- 11.2 ② 单击一次即响应（服务端主线程 + 一次点击一个包）---")
for packet, label in (("AssemblyTaskActionPacket.TYPE", "继续"),
                      ("RequestAssemblyAlertsPacket.TYPE", "请求快照"),
                      ("AssemblyStepMachinePacket.TYPE", "更换机器")):
    at = main_src.index(packet)
    check("ctx.enqueueWork" in main_src[at:at + 400],
          "② %s 的 C2S 处理器走 ctx.enqueueWork（在网络线程上读改记录表 / 写样板会偶发失效 ⇒ "
          "「点了没反应、点几下才反应」）" % label,
          "② %s 的 C2S 处理器没有下发到服务端主线程" % label)
check("if (!AssemblyAlertsClient.beginAction(taskId, SyncAssemblyAlertsPacket.ACTION_BIT_RESUME)"
      in mixin,
      "② 继续：在途闸门返回 true 才发包（一次点击 = 一次包）",
      "② 继续按钮没有在途闸门")
check("if (!AssemblyAlertsClient.beginAction(taskId, SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND)"
      in mixin,
      "② 挂起：同样走在途闸门（一次点击 = 一次包）", "② 挂起按钮没有在途闸门")
check("if (!AssemblyAlertsClient.beginAction(taskId, SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE))"
      in mixin,
      "② 更换机器：同样走在途闸门", "② 更换机器按钮没有在途闸门")
check("alerts = received == null ? List.of() : List.copyOf(received);" in client
      and "PENDING.clear();" in client,
      "② 新快照到达 = 新的服务端权威状态 → 清掉在途标记；另有 2 秒超时兜底（不会永久变灰）",
      "② 在途标记没有解锁路径")
check("private static final long PENDING_TIMEOUT_NANOS = 2_000_000_000L;" in client
      and "PENDING_TIMEOUT_NANOS" in client,
      "② 在途超时 2 秒（服务端只在内容变化时广播，这次点击若恰好不改变内容就必须靠超时解锁）",
      "② 缺少在途超时")


class ClickGate(object):
    """与 AssemblyAlertsClient.beginAction / setAlerts 同构的极小模型。"""

    TIMEOUT = 2.0   # 秒（= 2_000_000_000 ns）

    def __init__(self):
        self.pending = {}
        self.sent = []

    def click(self, key, now):
        at = self.pending.get(key)
        if at is not None and now - at < self.TIMEOUT:
            return False
        self.pending[key] = now
        self.sent.append(now)
        return True

    def snapshot(self):
        self.pending.clear()


gate = ClickGate()
for i in range(5):                        # 手速连点 5 次（1 秒内）
    gate.click("task#resume", i * 0.15)
check(len(gate.sent) == 1 and gate.sent[0] == 0.0,
      "② 连点 5 次只发 1 个包（多次点击不变成一串重复包）", "② 连点产生了多个包")
check(gate.click("task#resume", 2.5) is True,
      "② 2 秒超时后自动解锁（可以再点一次）", "② 超时后仍然锁死")
gate2 = ClickGate()
gate2.click("task#resume", 0.0)
gate2.snapshot()
check(gate2.click("task#resume", 0.1) is True,
      "② 服务端快照到达即解锁（不必等超时）", "② 快照到达没有解锁")

print("--- 11.3 ③ 点击后按钮集合只随服务端状态变化 ---")
check("AssemblyAlertsClient.actionView(alert, taskId, SyncAssemblyAlertsPacket.ACTION_BIT_RESUME))" in mixin
      and "AssemblyAlertsClient.actionView(alert, taskId, SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND))" in mixin
      and "AssemblyAlertsClient.actionView(alert, taskId, "
          "SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE))" in mixin,
      "③ 三个按钮的 visible/enabled 都由 AssemblyAlertsClient.actionView 从「这一份快照」折出",
      "③ 按钮状态没有走单一状态源")
check("final boolean offered = alert != null && alert.offers(actionBit);" in client
      and "return new ActionView(offered, offered && !isPending(taskId, actionBit));" in client,
      "③ actionView：visible = 服务端动作位；enabled = visible && 不在途（在途与可见性正交）",
      "③ actionView 把在途混进了可见性")
check("button.visible = view.visible() && fits;" in mixin,
      "③ 可见性只有两个因子：服务端动作位（动态） + 横向放得下（init 里算一次的静态几何）",
      "③ 可见性还有别的因子")

RESUME_BIT, MACHINE_BIT, SUSPEND_BIT = 1, 2, 4
BOTH = RESUME_BIT | MACHINE_BIT


def server_actions(suspended, returning=False, offline=False):
    """与 AssemblyWatchdog.Record#actions() 同构（唯一的按钮可见性判据）。

    本轮新增「挂起」位：未挂起且未被 RS 回收内部暂存 ⇒ 只给 SUSPEND_BIT；
    已挂起 ⇒ 只给 RESUME_BIT（+ 掉线时才给 MACHINE_BIT）。因此**服务端从不把「挂起」与「继续」同时给**。
    """
    if suspended:
        bits = RESUME_BIT
        if offline:
            bits |= MACHINE_BIT
        return bits
    return 0 if returning else SUSPEND_BIT


def visible_set(actions, pending=0, primary_fits=True, machine_fits=True):
    """与 actionView + rscc$apply 同构：可见性只看动作位与「放得下」（挂起 / 继续共槽 → 同一个 fits）。"""
    bits = ((RESUME_BIT, "resume", primary_fits),
            (SUSPEND_BIT, "suspend", primary_fits),
            (MACHINE_BIT, "machine", machine_fits))
    result = set()
    for bit, name, fits in bits:
        offered = actions is not None and (actions & bit) != 0
        if offered and fits:
            result.add(name)
    return frozenset(result)


# ---- ① 互斥：服务端从不把「挂起」与「继续」同时给（两者共槽的前提） ----
check(server_actions(True) & SUSPEND_BIT == 0 and server_actions(False) & RESUME_BIT == 0,
      "④ 「挂起」与「继续」位互斥：挂起后只有「继续」、未挂起只有「挂起」（共槽成立）",
      "④ 服务端可能同时给出「挂起」与「继续」两位（共槽会重叠）")
check(server_actions(True, offline=True) == BOTH,
      "④ 已挂起 + 执行器掉线 ⇒ 继续 + 更换机器（与既有行为一致）",
      "④ 已挂起 + 掉线的动作位不对")
check(server_actions(False, returning=True) == 0,
      "④ RS 正在回收内部暂存（RETURNING_INTERNAL_STORAGE）⇒ 不给「挂起」位"
      "（跳过它会把 RS 自己的回收也冻住）",
      "④ 回收内部暂存时仍提供「挂起」")
# ---- ② RUNNING 任务的按钮：只有「挂起」，SUSPENDED 任务只有「继续」 ----
check(visible_set(server_actions(False)) == {"suspend"},
      "④ RUNNING 任务 ⇒ 只渲染「挂起」（不渲染「继续」）", "④ RUNNING 任务渲染的按钮不对")
check(visible_set(server_actions(True)) == {"resume"},
      "④ SUSPENDED 任务 ⇒ 只渲染「继续」（不渲染「挂起」）", "④ SUSPENDED 任务渲染的按钮不对")
check(visible_set(BOTH) == {"resume", "machine"},
      "③ 服务端给出「继续 + 更换机器」两位 ⇒ 两个按钮都在", "③ 两位没有渲染两个按钮")
check(visible_set(BOTH, pending=RESUME_BIT) == {"resume", "machine"}
      and visible_set(BOTH, pending=MACHINE_BIT) == {"resume", "machine"},
      "③ 点「继续」/「更换机器」之后可见集合不变（点击不可能让任何按钮消失，只让它变灰）",
      "③ 点击改变了可见集合")
check(visible_set(BOTH, pending=SUSPEND_BIT) == visible_set(BOTH),
      "③ 点「挂起」之后可见集合不变（只变灰，不消失）", "③ 点「挂起」让按钮消失了")
check(visible_set(RESUME_BIT) == {"resume"},
      "③ 服务端只留下一半（执行仓回到网络里 ⇒ 收回「更换机器」位）时「继续」仍在、不受影响",
      "③ 「更换机器」消失带走了「继续」")
check(visible_set(0) == frozenset() and visible_set(None) == frozenset(),
      "③ 无动作（服务端报 0 位 / 没有告警）⇒ 整排不渲染（既由服务端决定，也不是客户端一厢情愿）",
      "③ 没有告警时仍会渲染按钮")
check(visible_set(BOTH, pending=BOTH) == visible_set(BOTH)
      and visible_set(RESUME_BIT, pending=BOTH) == visible_set(RESUME_BIT),
      "③ 在途状态与可见性正交（任意在途组合都不改变可见集合）",
      "③ 在途状态影响了可见集合")
check(visible_set(BOTH, machine_fits=False) == {"resume"},
      "③ 「更换机器」放不下时只收起它自己（收起的按钮进入 visible=false，不绘制也不接点击）",
      "③ 收起一个按钮会带走另一个")
check(visible_set(server_actions(False), primary_fits=False) == frozenset(),
      "④ 主槽位（挂起 / 继续）放不下时整排收起（与既有「放不下就收起」一致）",
      "④ 主槽位放不下时仍渲染按钮")

print("--- 11.4 ④ 打开 / 关闭 / 点击界面不改变任务推进（零副作用）---")
render_body = mixin[mixin.index("private void rscc$renderAssemblyTooltips"):
                    mixin.index("private void rscc$renderMarker")]
check("sendToServer" not in render_body and "setPosition" not in render_body
      and "Button.builder" not in render_body,
      "④ 每帧 render 只刷新状态 + 画文字 / tooltip：不发包、不移动控件、不重建控件",
      "④ 每帧 render 里有发包 / 位移 / 重建控件")
check(mixin.count("PacketDistributor.sendToServer(") == 5,
      "④ 界面里只有五条发包路径：打开时各拉一次只读快照（告警 / 缺料策略）+ 「挂起」/「继续」/「缺料处置」点击各一条（实为 %d 条）"
      % mixin.count("PacketDistributor.sendToServer("),
      "④ 界面里的发包路径不是 5 条")
check("AssemblyWatchdog.sendAlerts(player);" in request and "sendToServer" not in request
      and "public static void sendAlerts(final ServerPlayer player)" in wd
      and "PacketDistributor.sendToPlayer(player," in wd,
      "④ 打开界面发的请求包只触发「回一份只读快照」（服务端侧不写任何状态）",
      "④ 打开界面会改动服务端状态")
check("rscc$toggle" not in mixin and "suspendState" not in mixin and "stallTicks" not in mixin,
      "④ 界面代码里没有任何写挂起状态机 / 计时器的入口（任务推进只由服务端扫描 + 显式点击决定）",
      "④ 界面代码碰到了挂起状态机 / 计时器")

# =====================================================================
print("===== 12. 手动挂起（本轮新增：玩家主动让出正在跑的任务，让别人先做）=====")

print("--- 12.1 ① 包 / 动作号 / 服务端校验 / 挂起入口复用 / 原因标记 ---")
check("ACTION_RESUME = 0" in action and "ACTION_SUSPEND = 1" in action,
      "① 动作包新增 ACTION_SUSPEND = 1（追加，不动既有 ACTION_RESUME = 0）",
      "① 动作包没有 ACTION_SUSPEND 或改动了既有动作号")
check("if (packet.action() == ACTION_RESUME) {" in action
      and "if (packet.action() == ACTION_SUSPEND" in action
      and "AssemblyWatchdog.suspend(player, packet.taskId())" in action,
      "① 服务端按动作分派：继续 → AssemblyWatchdog.resume；挂起 → AssemblyWatchdog.suspend",
      "① 服务端没有按动作分派「挂起」")
check(action.count("displayClientMessage") == 1
      and "message.rs_create_compat.assembly.suspend_failed" in action
      and "message.rs_create_compat.assembly.action_failed" in action,
      "① 失败提示只有一处实现（helper）：继续 / 挂起 各自最多提示一次，成功一个字都不播",
      "① 失败提示数量不对 / 缺少挂起失败文案")
check("private static void suspendRecord(final Record record, final Reason reason, final long now) {" in wd
      and "suspendRecord(record, record.reason, now);" in wd
      and "suspendRecord(record, Reason.MANUAL, now);" in wd,
      "① 「挂起」只有一处实现（suspendRecord），自动挂起与手动挂起共用它 —— 判定不重复",
      "① 挂起入口没有共用（可能存在两份判定）")
check("record.reason = reason;" in wd and "setSuspendState(record, SuspendState.SUSPENDED, now);" in wd,
      "① suspendRecord：写原因 + 状态机转 SUSPENDED（语义与自动挂起完全一致）",
      "① suspendRecord 实现不完整")
check("REASON_MANUAL = 4" in alerts and "MANUAL" in wd,
      "① 手动挂起的原因标记 = MANUAL / REASON_MANUAL = 4（追加在末尾，不动既有序号）",
      "① 缺少手动挂起的原因标记")
check("if (record.reason == Reason.MANUAL) {" in wd,
      "① 手动挂起不弹横幅（sendBanner 提前返回：玩家自己做的动作不播报，与「继续」成功不播报同一口径）",
      "① 手动挂起会走横幅（会说成「缺料」）")
check("record.suspendState != SuspendState.RUNNING || record.returning" in wd,
      "① 服务端校验：任务须存在且处于 RUNNING、且 RS 未在回收内部暂存（否则安全失败，绝不误挂）",
      "① 服务端缺少「必须 RUNNING」校验")
check("public static boolean suspend(final ServerPlayer player, final UUID taskId)" in wd,
      "① suspend 是服务端权威入口（AssemblyTaskActionPacket → AssemblyWatchdog.suspend）",
      "① 缺少服务端 suspend 入口")

print("--- 12.2 ② 对原版 RS 普通自动合成任务同样生效（非序列装配的推演）---")
actions_body = wd[wd.index("private int actions() {"):wd.index("/** 维度 →")]
check("sequence" not in actions_body and "SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND;" in actions_body,
      "② actions() 完全不看 sequence（是否序列装配不影响「可挂起」）⇒ 原版 RS 任务同样能手动挂起",
      "② actions() 里按 sequence 区分：原版任务可能拿不到「挂起」位")
check("record.sequence = sequenceTask;" in wd and "record.returning = status.state()" in wd,
      "② 记录里 sequence 与 returning 各自独立：序列装配只影响原因判定与文案，不影响挂起语义",
      "② 记录结构与预期不符")
# 用户第 ⑥ 条（本轮新增的第三条路径）：样板库自身掉线 ⇒ patterns 里没有条目 ⇒ pattern==null。
# 若不缓存「上一次解析到的样板」，这条任务会被降级成 RS 原版任务、失去离线判定 ⇒ 断缝后不挂起、不弹横幅。
check("final boolean sequenceTask = pattern != null || record.lastAssembly != null;" in wd
      and "record.lastAssembly = pattern.assembly();" in wd
      and "record.lastExecutorPos = pattern.executorPos();" in wd,
      "⑥ 样板库自身掉线时用「上一次解析到的样板数据」继续按序列装配分类 ⇒ 断链必挂起、必弹横幅",
      "⑥ 缺少 lastAssembly 缓存：样板库掉线会被降级成原版任务、不判离线")
check("} else if (sequenceTask) {" in wd
      and "new PatternRef(record.lastExecutorPos, record.productIcon, record.lastAssembly)" in wd,
      "⑥ 分类分派按 sequenceTask（而非 pattern != null）⇒ 缓存样板同样走 classifySequence",
      "⑥ 分类分派仍只看 pattern != null")

print("--- 12.3 ③④ 推演：挂起释放占用 / 中间件守恒 / 一律不自动恢复 / 拒绝只提示一次 ---")


class TaskSim(object):
    """与 Java 的 Record + TaskContainerMixin + AssemblyWatchdog.suspend/resume 同构的极小模型。"""

    def __init__(self):
        self.suspended = False
        self.reason = "NONE"
        self.stepped = 0          # 被 RS 推进的次数
        self.internal = 12        # 任务内部暂存（已抽出的中间件）
        self.network = 340        # 网络库存

    def step(self):
        if self.suspended:
            return                # = TaskContainerMixin 的 cir.setReturnValue(false)
        self.stepped += 1

    def suspend(self, exists=True, returning=False):
        """= AssemblyWatchdog.suspend 的判定（存在性 / RUNNING / 未在回收）。"""
        if not exists or self.suspended or returning:
            return False
        self.suspended = True
        self.reason = "MANUAL"
        return True

    def resume(self):
        """= AssemblyWatchdog.resume（唯一恢复入口）。"""
        self.suspended = False
        self.reason = "NONE"


# ① RUNNING → 收到 ACTION_SUSPEND → SUSPENDED、释放占用、中间件守恒
sim = TaskSim()
for _ in range(50):
    sim.step()
before = sim.stepped
check(sim.suspend() is True and sim.suspended and sim.reason == "MANUAL",
      "① RUNNING 任务收到 ACTION_SUSPEND ⇒ 变 SUSPENDED 且原因标记 = MANUAL", "① 手动挂起没有生效")
for _ in range(600):
    sim.step()
check(sim.stepped == before,
      "① 挂起后 600 tick 内不再被 step（不再占用执行器 / 不再抽料投料）", "① 挂起后仍在被推进")
check(sim.internal + sim.network == 352,
      "① 挂起 600 tick 后「任务内部暂存 %d + 网络库存 %d」与挂起前一致（不复制 / 不销毁）"
      % (sim.internal, sim.network),
      "① 挂起期间物品 / 流体总量发生变化")

# 拒绝路径：不存在 / 已挂起 / RS 正在回收 —— 都返回 false，且只提示一次
msgs = []


def click_suspend(model, **kwargs):
    if model.suspend(**kwargs):
        return True               # 成功不播报
    msgs.append("suspend_failed")  # = AssemblyTaskActionPacket.fail(...) 每次动作最多一次
    return False


check(click_suspend(TaskSim(), exists=False) is False and len(msgs) == 1,
      "① 任务不存在 ⇒ 拒绝并只提示一次（安全失败，不误伤别的任务）", "① 不存在的任务没有被拒绝")
check(click_suspend(TaskSim(), returning=True) is False and len(msgs) == 2,
      "① RS 正在回收内部暂存 ⇒ 拒绝并只提示一次", "① 回收内部暂存时仍被挂起")
already = TaskSim()
already.suspend()
check(click_suspend(already) is False and len(msgs) == 3,
      "① 任务已挂起（非 RUNNING）⇒ 拒绝并只提示一次", "① 已挂起的任务被重复挂起")

# ② 原版 RS 普通自动合成任务（非序列装配）走同一条路：未挂起 ⇒ 可挂起；挂起 ⇒ 释放占用、只等「继续」
generic = TaskSim()
check(visible_set(server_actions(generic.suspended)) == {"suspend"},
      "② 原版 RS 任务 RUNNING ⇒ 监视器渲染「挂起」（不区分是否序列装配）",
      "② 原版 RS 任务拿不到「挂起」按钮")
check(generic.suspend() is True and visible_set(server_actions(generic.suspended)) == {"resume"},
      "② 原版 RS 任务手动挂起成功 ⇒ 改渲染「继续」（互斥）", "② 原版 RS 任务的挂起 / 继续切换不对")
generic_before = generic.stepped
for _ in range(400):
    generic.step()
check(generic.stepped == generic_before,
      "② 原版 RS 任务挂起后同样不再被 step（释放占用、不阻塞同网络的后续任务）",
      "② 原版 RS 任务挂起后仍在被推进")

# ③ 挂起后一律不自动恢复：原因恢复正常 + 任意时长，仍 SUSPENDED；只有「继续」才回 RUNNING
long_sim = TaskSim()
long_sim.suspend()
for _ in range(200000):
    long_sim.step()
check(long_sim.suspended and long_sim.reason == "MANUAL",
      "③ 手动挂起后经过 20 万 tick（原因早已恢复正常）仍为 SUSPENDED —— 一律不自动恢复",
      "③ 手动挂起被自动恢复了")
long_sim.resume()
check(not long_sim.suspended and long_sim.reason == "NONE",
      "③ 只有收到 ACTION_RESUME（AssemblyWatchdog.resume）才回到 RUNNING", "③ 手动「继续」没有解除挂起")

# =====================================================================
print()
if problems:
    for problem in problems:
        print("[X] " + problem)
print("问题总数:", len(problems))
sys.exit(1 if problems else 0)
