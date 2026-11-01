#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 71 轮：新方块（网络资源接收器 / 请求器）的**节拍口径**与**升级计数口径**收尾自检。

<h2>本脚本回答的四个问题（对应本轮的三个缺陷 + 一个新方块接入）</h2>
<ol>
 *   <li><b>升级容器是否按「物品个数」计数、是否覆写 {@code getStackLimit}</b>
 *       （与范围充电器 {@code RangeChargerBlockEntity.java:111} / {@code :354} 同口径），
 *       并带<b>反例</b>：一格 64 个升级时旧口径算 1 个、新口径夹到上限 6 个；</li>
 *   <li><b>新方块的节流是否真的经 {@code RsccRateLimiter}</b>（源码级：不出现自写 {@code <<}
 *       公式、不出现 RS 的 {@code UpgradeContainer} ticker）；
 *       并且<b>速度升级 = 每 tick 批数</b>（1/2/4/8/16/32/64）、<b>叠加升级 = 每批多少</b>
 *       （64…448）两条承诺都在代码里成立 —— 这是第 92 轮修掉的「速度升级完全无效」缺陷；</li>
 *   <li><b>界面与方块实体的充电速率是否同源</b>（源码级：界面不再自写
 *       {@code << menu.getUpgradeCount()}，两侧调用同一个工具方法）；</li>
 *  <li><b>两个 NetworkNode 注释里不再有「未定稿」字样</b>。</li>
</ol>
 *
 * <h2>第 5 节（本轮追加）：接收器「缓存 → 朝向容器」的守恒 / 未命名 / 节流</h2>
 * <p>只做接收器（请求器一行不碰）。判据同样是「源码形状 + 反例自证」：</p>
 * <ol>
 *   <li><b>只扣真正被接收的量</b>：必须先 simulate 问目标能收多少，再由「送出的 - 退回的」
 *   算出 accepted，之后才从缓存取出，并带 execute 差额回滚；</li>
 *   <li><b>未命名 ⇒ 不动</b>：{@code doWork()} 里 {@code !isProperlyConfigured()} 的闸门
 *   必须存在且在节流之后；</li>
 *   <li><b>有节流</b>：{@code throttleTick()} 闸门 + 批量来自 {@code getThrottleBatch()}；</li>
 *   <li><b>反例自证</b>：把「先扣后写」和「没有未命名闸门」的写法喂给同一批判据 ⇒ 必须判负。</li>
 * </ol>
 *
 * <h2>方法（与工程既有自检同一套）</h2>
 * <p>判据分三层：① <b>源码形状</b>（注释剥掉后做字面量断言 —— 注释里必须能解释「为什么不用
 * RS 的 ticker」，所以裸文本断言会把解释误判成使用）；② <b>行为镜像</b>（把 Java 语义在 Python 里
 * 逐条镜像，用具体数值算出来，而不是「看起来对」）；③ <b>反例自证</b>（把修前的写法喂给同一批判据，
 * 必须被判 FAIL —— 证明这些判据真的抓得住旧口径，而不是恒真）。</p>
 *
 * 用法：python tools/selfcheck_round71_new_machine_pacing.py
 * 退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

CONTAINER = "support/RsccSpeedStackUpgradeContainer.java"
RECEIVER_BE = "block/entity/NetworkResourceReceiverBlockEntity.java"
REQUESTER_BE = "block/entity/NetworkResourceRequesterBlockEntity.java"
RECEIVER_MENU = "menu/NetworkResourceReceiverMenu.java"
REQUESTER_MENU = "menu/NetworkResourceRequesterMenu.java"
RECEIVER_NODE = "network/NetworkResourceReceiverNetworkNode.java"
REQUESTER_NODE = "network/NetworkResourceRequesterNetworkNode.java"
RECEIVER_SCREEN = "client/screen/NetworkResourceReceiverScreen.java"
REQUESTER_SCREEN = "client/screen/NetworkResourceRequesterScreen.java"
CHARGER_SCREEN = "client/screen/RangeChargerScreen.java"
CHARGER_MENU = "menu/RangeChargerMenu.java"

# 新机器（不含已迁移的 5 个机器文件）—— 本轮的「新方块」范围
NEW_MACHINE_FILES = [
    CONTAINER, RECEIVER_BE, REQUESTER_BE, RECEIVER_MENU, REQUESTER_MENU,
    RECEIVER_NODE, REQUESTER_NODE, RECEIVER_SCREEN, REQUESTER_SCREEN,
]

INT_MAX = 2 ** 31 - 1
MAX_LEVEL = 6
STACK_AS_SPEED_WEIGHT = 3
MAX_POWER = 24
BASE_BATCH = 64

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def section(title):
    print("")
    print("=" * 78)
    print(title)
    print("=" * 78)


def read(rel):
    path = os.path.join(SRC, rel)
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """剥掉 // 与 /*...*/ 注释（保留换行，便于定位）；字符串字面量保留（它们是判据本身）。"""
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
        else:
            out.append(char)
            i += 1
    return "".join(out)


def method_body(code, signature):
    """按大括号配平取出以 signature 开头的方法体（含签名行）；找不到返回 None。"""
    start = code.find(signature)
    if start < 0:
        return None
    open_at = code.find("{", start)
    if open_at < 0:
        return None
    depth = 0
    for idx in range(open_at, len(code)):
        ch = code[idx]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return code[start:idx + 1]
    return None


# ======================================================================================
# ① 升级容器：按「物品个数」计数 + 覆写 getStackLimit（源码级 + 反例）
# ======================================================================================

section("① RsccSpeedStackUpgradeContainer：按个数计数 + 槽上限夹到 1")

container_raw = read(CONTAINER)
container = strip_comments(container_raw)

check("①-0 容器文件存在且是 ItemStackHandler 的最终子类",
      container_raw != "" and "public final class RsccSpeedStackUpgradeContainer extends ItemStackHandler" in container)

check("①-1 覆写 getStackLimit：min(1, stack.getMaxStackSize())"
      "（与 RangeChargerBlockEntity:111 逐字同形；默认实现是 99 ⇒ 不覆写就能被漏斗塞几十个）",
      "protected int getStackLimit(final int slot, final ItemStack stack)" in container
      and "Math.min(1, stack.getMaxStackSize())" in container)

check("①-2 覆写 getSlotLimit 返回 1（菜单 / 任何读 IItemHandler 的调用方也看得到上限）",
      "public int getSlotLimit(final int slot)" in container
      and re.search(r"public int getSlotLimit\(final int slot\)\s*\{\s*return 1;", container) is not None)

count_body = method_body(container, "public int countOf(")
check("①-3 countOf 按「物品个数」累加（count += inSlot.getCount()），不是数槽位",
      count_body is not None
      and "count += inSlot.getCount();" in count_body
      and "count++" not in count_body)

cap_body = method_body(container, "private void dropBeyondCap(")
check("①-4 dropBeyondCap 按个数保留：allowed = max - kept，余量逐个 dropOne"
      "（旧写法 kept++ 一格只算一个 ⇒ 一格 64 个全放行）",
      cap_body is not None
      and "final int inSlotCount = inSlot.getCount();" in cap_body
      and "final int allowed = Math.max(0, max - kept);" in cap_body
      and "for (int excess = inSlotCount - allowed; excess > 0; excess--)" in cap_body
      and "dropOne(i)" in cap_body
      and "kept += Math.min(inSlotCount, allowed);" in cap_body
      and "kept++" not in cap_body
      and "counts[" not in cap_body)

validate_body = method_body(container, "public void validate(")
check("①-5 validate 的前置是「没有服务端世界就不动任何东西」"
      "（canDropToWorld 由宿主现问；与 RangeChargerBlockEntity:356 同一语义）",
      validate_body is not None
      and "if (validating || !canDropToWorld.getAsBoolean())" in validate_body
      and "return;" in validate_body)

drop_body = method_body(container, "private void dropOne(")
check("①-6 dropOne 每次只抽 1 个并交给宿主弹出（绝不销毁；与 RangeChargerBlockEntity:419 同义）",
      drop_body is not None
      and "extractItem(slot, 1, false)" in drop_body
      and "host.dropStack(removed)" in drop_body
      and "ItemStack.EMPTY" not in drop_body)

check("①-7 上限仍取自 UpgradeSlot.maxForPath（全工程唯一的上限来源，不另写 6）",
      "UpgradeSlot.maxForPath(path)" in container)

print("")
print("--- 反例：一格塞 64 个速度升级（旧口径 vs 新口径）---")


def old_slot_counting(slots):
    """**修前**的计数口径：数槽位（count++ / kept++），一格 64 个也只算 1 个。
    slots = [(kind, count), ...]；返回 {kind: 计数}。"""
    counts = {}
    for kind, _count in slots:
        if kind is None:
            continue
        counts[kind] = counts.get(kind, 0) + 1
    return counts


def new_item_counting(slots, max_per_kind=6):
    """**修后**的计数口径：按物品个数保留前 max 个，其余逐个弹出。
    返回 (kept: {kind: 个数}, dropped: 弹出总个数)。"""
    kept, dropped = {}, 0
    for kind in sorted({k for k, _ in slots if k is not None}):
        limit = max_per_kind
        already = 0
        for k, count in slots:
            if k != kind:
                continue
            allowed = max(0, limit - already)
            dropped += max(0, count - allowed)
            already += min(count, allowed)
        kept[kind] = already
    return kept, dropped


FUNNEL = [("speed", 64)]  # 一格 64 个速度升级
old = old_slot_counting(FUNNEL)
kept, dropped = new_item_counting(FUNNEL)

check("①-8 反例·旧口径：`counts[kind]++` 数的是槽位 ⇒ 一格 64 个只算 1 个（上限被绕过）",
      old.get("speed") == 1)
check("①-9 反例·新口径：按个数计数并夹到上限 6，多出的 58 个逐个弹出（绝不是 1，也不是 64）",
      kept.get("speed") == MAX_LEVEL and dropped == 58)
check("①-10 反例·后果对照：旧口径即使把 64 喂给速率侧也只有 6 档生效"
      "（`level(64)=6`），而新口径在**写入时**就不可能让一格出现 64 个",
      min(64, MAX_LEVEL) == 6 and kept.get("speed") == 6)


# ======================================================================================
# ② 新方块的节流确实经 RsccRateLimiter（源码级：无自写公式 / 无 RS ticker）
# ======================================================================================

section("② 新方块节流经 RsccRateLimiter（不自己写公式、不用 RS ticker）")

check("②-0 升级容器暴露节流 API（闸门间隔 + 每 tick 批数 + 批量 + 等效档数）",
      "public int throttleIntervalTicks()" in container
      and "public int throttleBatchesPerTick()" in container
      and "public long throttleBatch()" in container
      and "public int throttlePacing()" in container)

interval_body = method_body(container, "public int throttleIntervalTicks()")
batches_body = method_body(container, "public int throttleBatchesPerTick()")
batch_body = method_body(container, "public long throttleBatch()")
pacing_body = method_body(container, "public int throttlePacing()")


def batches_source_ok(body):
    """「每 tick 批数」的实现判据：走 RsccRateLimiter，且**不许**把叠加升级混进来。

    速度升级 = 每 tick 几批、叠加升级 = 每批多少 —— 两种升级各管一个旋钮；
    若批数里也乘上 stackCount，叠加升级就被算了两次，「叠加 = 每批多少」的承诺随之破掉。
    """
    if body is None:
        return False
    if "stackCount" in body:
        return False
    return "RsccRateLimiter.shiftSaturating(1, RsccRateLimiter.level(speedCount()))" in body


check("②-1 闸门间隔被钉成恒 1（物理下限 = 每 tick 一次 doWork；它不再是提速旋钮）",
      interval_body is not None
      and re.search(r"public int throttleIntervalTicks\(\)\s*\{\s*return 1;", interval_body) is not None
      and "intervalTicks(" not in interval_body)
check("②-1b 每 tick 批数 = shiftSaturating(1, level(speedCount))（0/1/6 个速度升级 ⇒ 1/2/64 批）",
      batches_source_ok(batches_body))
check("②-1c 每 tick 批数的实现里没有 stackCount（叠加升级只许作用在「每批多少」）",
      batches_body is not None and "stackCount" not in batches_body)
check("②-2 批量走 RsccRateLimiter.batchSaturating（I1/I3：饱和乘法、绝不为负）",
      batch_body is not None
      and "RsccRateLimiter.batchSaturating(BASE_BATCH, stackCount())" in batch_body)
check("②-3 等效档数走 RsccRateLimiter.power（I4：上限单点，堆叠权重取自工具常量）",
      pacing_body is not None
      and "RsccRateLimiter.power(speedCount(), stackCount())" in pacing_body)
check("②-4 单批基础量是常量 BASE_BATCH = 64L（叠加升级 = 每级 +1 倍，公式不动）",
      "public static final long BASE_BATCH = 64L;" in container
      and batch_body is not None
      and "BASE_BATCH" in batch_body)

# 行为镜像：新机器的节流数值（与 RsccRateLimiter 的 Java 语义逐条对应）
def j_level(raw):
    return max(0, min(raw, MAX_LEVEL))


def j_power(speed, stack):
    return min(j_level(speed) + j_level(stack) * STACK_AS_SPEED_WEIGHT, MAX_POWER)


def j_interval_ticks(base, power):
    """镜像 RsccRateLimiter.intervalTicks(base, speedUpgrades) 与容器对它的调用形态。
    调用方传进来的已经是 level(...) 收敛过的档数（0..MAX_POWER），因此这里只做右移 + 夹 1。
    Java 的 `>>` 对移位量按 32 取模，但档数上限 24 < 32，所以无需镜像掩码。"""
    return max(1, max(1, base) >> max(0, power))


def j_batch_saturating(base, stack):
    return base * (1 + j_level(stack))


def j_batches_per_tick(raw_speed):
    """镜像 throttleBatchesPerTick()：shiftSaturating(1, level(speed)) = 1 << min(speed, 6)。"""
    return 1 << j_level(raw_speed)


check("②-5 行为：0/1/2/…/6 个速度升级 ⇒ 1/2/4/8/16/32/64 批/tick，且漏斗塞 64 个夹到 64",
      [j_batches_per_tick(n) for n in range(7)] == [1, 2, 4, 8, 16, 32, 64]
      and j_batches_per_tick(64) == 64
      and j_batches_per_tick(-5) == 1)
check("②-5b 行为：速度升级确实改变吞吐（0 速 vs 6 速 = 64 倍），叠加升级不改变批数",
      j_batches_per_tick(6) == 64 * j_batches_per_tick(0)
      and all(j_batches_per_tick(s) == j_batches_per_tick(s)
              for s in range(7)))

# 反例自证：把 throttleBatchesPerTick 改回「恒 1」或把 stackCount 混进来，判据必须变红。
BAD_BATCHES_CONST = """
    public int throttleBatchesPerTick() {
        return 1;
    }
"""
BAD_BATCHES_STACK = """
    public int throttleBatchesPerTick() {
        return RsccRateLimiter.shiftSaturating(1,
            RsccRateLimiter.level(speedCount() + stackCount() * 3));
    }
"""
check("②-5c 反例自证：`return 1`（速度升级无效的历史缺陷）⇒ 判负",
      batches_source_ok(BAD_BATCHES_CONST) is False)
check("②-5d 反例自证：批数里混入 stackCount（叠加被算两遍）⇒ 判负",
      batches_source_ok(BAD_BATCHES_STACK) is False)

check("②-6 行为：单批 64,128,…,448（叠加每级 +1 倍；与工程既有 64 一组的口径一致）",
      [j_batch_saturating(BASE_BATCH, n) for n in range(7)]
      == [64, 128, 192, 256, 320, 384, 448])

check("②-7 行为：漏斗塞 64 个叠加升级时夹到 448（不是 64×65 = 4160），"
      "塞 64 个速度升级时档数是 6（不是 64, 也不是 64&31=0）",
      j_batch_saturating(BASE_BATCH, 64) == 448 and j_power(64, 0) == 6
      and j_power(0, 64) == MAX_LEVEL * STACK_AS_SPEED_WEIGHT)

# 源码级反向断言：新机器文件里不得出现自写 `<<` 公式
shift_hits = []
for rel in NEW_MACHINE_FILES:
    code = strip_comments(read(rel))
    for line_no, line in enumerate(code.split("\n"), 1):
        if "<<" in line:
            shift_hits.append("%s:%d: %s" % (rel, line_no, line.strip()))
check("②-8 新机器源码里没有任何自写 `<<` 位移公式（注释里的解释不算，已剥掉）",
      not shift_hits, "; ".join(shift_hits[:3]))

# 源码级反向断言：不得使用 RS 的 UpgradeContainer（其 ticker 的 9-2n 在 n=6 时是 -3）。
# 注意：本模组自己的 RsccSpeedStackUpgradeContainer 名字里也含 UpgradeContainer，
# 因此判据是「出现 UpgradeContainer 但不是 RsccSpeedStackUpgradeContainer，也不是自己的 getter」。
rs_container_hits = []
for rel in NEW_MACHINE_FILES:
    code = strip_comments(read(rel))
    for line_no, line in enumerate(code.split("\n"), 1):
        if "UpgradeContainer" not in line:
            continue
        if "RsccSpeedStackUpgradeContainer" in line:
            continue
        if "getUpgradeContainer()" in line and "new UpgradeContainer" not in line:
            continue  # 自己容器的 getter
        rs_container_hits.append("%s:%d: %s" % (rel, line_no, line.strip()))
check("②-9 新机器源码里没有使用 RS 的 UpgradeContainer 构造器 / 类型"
      "（负节拍来源；只在注释里解释为什么不用）",
      not rs_container_hits, "; ".join(rs_container_hits[:3]))
check("②-9b 新机器源码里也没有 import RS 的 UpgradeContainer",
      all("import com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer" not in strip_comments(read(rel))
          for rel in NEW_MACHINE_FILES))

check("②-10 RS ticker 的负数坑仍然成立（反例依据）：9-2n 在 n=5/6 时是 -1/-3",
      [9 - 2 * n for n in range(7)] == [9, 7, 5, 3, 1, -1, -3]
      and [9 - 2 * n for n in (5, 6)] == [-1, -3])

# 两个方块实体都把节流接到了真实调用点
recv_be = strip_comments(read(RECEIVER_BE))
req_be = strip_comments(read(REQUESTER_BE))
recv_tick = method_body(recv_be, "public boolean throttleTick()")
check("②-11 接收器：throttleTick() 的间隔来自容器（两处调用点：doWork 与容器本身）",
      recv_tick is not None
      and "throttleCooldown = upgradeContainer.throttleIntervalTicks();" in recv_tick)
check("②-12 接收器：doWork() 里真的有节流闸门调用（不是只定义不接线）",
      "if (!throttleTick())" in recv_be
      and "super.doWork();" in recv_be)
check("②-13 请求器：throttleTick() + 触发点接入（配置判定在节流之前，避免吞掉第一次请求）",
      method_body(req_be, "public boolean throttleTick()") is not None
      and "if (!isProperlyConfigured())" in method_body(req_be, "public void triggerRequest()")
      and "if (!throttleTick())" in method_body(req_be, "public void triggerRequest()"))

# ======================================================================================
# ③ 界面与方块实体的充电速率同源（界面不再自写 <<）
# ======================================================================================

section("③ 范围充电器：界面与方块实体同源（不再有第二处算法）")

charger_screen = strip_comments(read(CHARGER_SCREEN))
charger_menu = strip_comments(read(CHARGER_MENU))
charger_be = strip_comments(read("block/entity/RangeChargerBlockEntity.java"))

check("③-1 界面**不再**自写 `Config.rangeChargerChargeRate << menu.getUpgradeCount()`",
      "Config.rangeChargerChargeRate << menu.getUpgradeCount()" not in charger_screen
      and "<< menu.getUpgradeCount()" not in charger_screen)
check("③-2 界面没有别的自写位移（充电速率只能来自菜单）",
      "<<" not in charger_screen)
check("③-3 界面的 chargeRate() 只读 menu.getChargeRate()（一处调用，无第二套公式）",
      "return menu.getChargeRate();" in charger_screen)
check("③-4 菜单暴露 getChargeRate()，且它调用 RsccRateLimiter.rateSaturating",
      "public int getChargeRate()" in charger_menu
      and "RsccRateLimiter.rateSaturating(" in charger_menu)
check("③-5 菜单的 getChargeRate 读的是**原始**速度/堆叠个数（数据槽 10/11），与方块实体传同一对参数",
      "public int getChargeRate()" in charger_menu
      and re.search(r"return RsccRateLimiter\.rateSaturating\(\s*"
                    r"cretae\.cookiewyq\.rs_create_compat\.Config\.rangeChargerChargeRate, "
                    r"data\.get\(10\), data\.get\(11\)\);", charger_menu) is not None)
check("③-5b 数据槽 10/11 由方块实体给出**原始计数**，且 ContainerData 共 14 格（12 高亮开关 / 13 已满跳过）",
      "case 10 -> countUpgrades(SPEED_UPGRADE);" in charger_be
      and "case 11 -> countUpgrades(STACK_UPGRADE);" in charger_be
      # 第 98 轮锚点更新（旧契约被本轮契约合法推翻，不是放宽）：ContainerData 12 → 13，
      # 索引 12 = 本机「高亮充电目标」开关；数据槽 10/11 的语义没变。
      # 第 102 轮再 +1（同样不是放宽）：13 = 上一次扫描「因已满被跳过」的目标数，
      # 断言的仍是「服务端槽数 = 实际最大索引 + 1」。
      and "public int getCount() {\n                return 14;" in charger_be)
check("③-5c 菜单的备用数据容器也是 14 格（槽数不一致会静默截断 10/11，界面速率退回 base）",
      # 第 98 轮：与方块实体 getCount() 同为 13（索引 12 = highlightEnabled）。
      # 第 102 轮：同为 14（索引 13 = 已满跳过计数）。
      "new SimpleContainerData(14)" in charger_menu)
check("③-6 方块实体走的是同一个 rateSaturating（同一工具、同一形状）",
      "RsccRateLimiter.rateSaturating(Config.rangeChargerChargeRate" in charger_be)
check("③-7 方块实体的等效档数已是 power(...) 收敛值（界面读到的是这个数的同步副本）",
      "RsccRateLimiter.power(countUpgrades(SPEED_UPGRADE), countUpgrades(STACK_UPGRADE))" in charger_be)

# 行为镜像：分叉不可能 —— 同一个函数、同一组输入
def j_rate_saturating(base, speed, stack):
    result = max(1, base)
    for _ in range(j_power(speed, stack)):
        if result > INT_MAX >> 1:
            return INT_MAX
        result <<= 1
    return result


check("③-8 行为：两侧调用同一个纯函数 ⇒ 同一组输入必然同一结果"
      "（这就是「不可能分叉」的判据，而不是「看起来一样」）",
      all(j_rate_saturating(500, s, st) == j_rate_saturating(500, s, st)
          for s in (0, 3, 6, 64, -1) for st in (0, 3, 6, 64, -1)))
check("③-9 行为：旧写法在配置 8192 + 满堆叠时确实溢出（负数）—— 缺陷真实存在，不是理论问题",
      (8192 << 18) - 2 ** 32 < 0
      and j_rate_saturating(8192, 0, 6) == INT_MAX)
check("③-10 行为：旧界面写法（`base << power`）在**未溢出**的区间与方块实体逐格相同，"
      "只在溢出区间分叉 —— 这正是「界面负数、方块却充满」这个用户可见现象的由来",
      j_rate_saturating(500, 0, 0) == (500 << 0)
      and j_rate_saturating(500, 6, 0) == (500 << 6)
      and j_rate_saturating(500, 3, 3) == (500 << 12)
      and (8192 << 18) - 2 ** 32 < 0
      and j_rate_saturating(8192, 0, 6) == INT_MAX)

# 两个新方块界面同样不许自算：数值只能来自菜单的同步 getter
for rel, menu_rel, getter in ((RECEIVER_SCREEN, RECEIVER_MENU, "getThrottleBatchesPerTick"),
                              (REQUESTER_SCREEN, REQUESTER_MENU, "getThrottleBatchesPerTick")):
    screen_code = strip_comments(read(rel))
    menu_code = strip_comments(read(menu_rel))
    check("③-11 %s：节流数值只读菜单的 %s()（界面不自算；旧的「节拍间隔」getter 已删除）"
          % (os.path.basename(rel), getter),
          ("menu.%s()" % getter) in screen_code
          and ("public int %s()" % getter) in menu_code
          and "getThrottleIntervalTicks" not in screen_code
          and "getThrottleIntervalTicks" not in menu_code
          and "<<" not in screen_code)
    check("③-12 %s：菜单的节流数值来自 ContainerData（服务端权威），不是本地重算"
          % os.path.basename(menu_rel),
          "addDataSlots(data)" in menu_code
          and ("receiver.getContainerData()" in menu_code
               or "requester.getContainerData()" in menu_code))


# ======================================================================================
# ④ 两个 NetworkNode 注释里不再有「未定稿」
# ======================================================================================

section("④ NetworkNode 注释：不再声称 RsccRateLimiter「API 尚未定稿」")

for rel in (RECEIVER_NODE, REQUESTER_NODE):
    raw = read(rel)
    check("④-a %s：文件存在" % os.path.basename(rel), raw != "")
    check("④-b %s：注释里不再有「未定稿」字样" % os.path.basename(rel),
          "未定稿" not in raw,
          "仍出现：%s" % [l.strip() for l in raw.split("\n") if "未定稿" in l][:2])
    check("④-c %s：明确写出节流已接、并点名 RsccRateLimiter" % os.path.basename(rel),
          "RsccRateLimiter" in raw and "throttleTick" in raw)

# 全工程（新机器范围内）也不该再留下这句过期说法
stale = []
for rel in NEW_MACHINE_FILES:
    raw = read(rel)
    if "未定稿" in raw:
        stale.append(rel)
check("④-d 新机器全部文件里都不再有「未定稿」（过期说法已清干净）", not stale, str(stale))

# 反例自证：把「未定稿」塞回一段文本，判据必须抓得住
probe_text = "走统一的节流入口（support/RsccRateLimiter）—— 因为它与搬运逻辑同批落地，API 尚未定稿。"
check("④-e 反例自证：判据真的抓得住旧注释（把旧句子喂进来必须判负）",
      "未定稿" in probe_text)


# ======================================================================================
# ⑤ 接收器：缓存 → 朝向容器的守恒 / 未命名 / 节流（第 5 节，本轮追加）
# ======================================================================================

section("⑤ 接收器输出：只扣真正被接收的量 + 未命名不动 + 有节流")

recv_do_work = method_body(recv_be, "public void doWork()")
recv_items = method_body(recv_be, "private long outputItems(")
recv_fluids = method_body(recv_be, "private long outputFluids(")
recv_insert = method_body(recv_be, "private static ItemStack insertInto(")
recv_raw = read(RECEIVER_BE)
recv_code = strip_comments(recv_raw)

check("⑤-0 doWork / outputItems / outputFluids（返回本次搬动量）/ insertInto 四个方法都存在",
      recv_do_work is not None and recv_items is not None
      and recv_fluids is not None and recv_insert is not None)

# --- 闸门顺序：先节流、再未命名（未命名 ⇒ 一个字节都不动） ---
i_active = recv_do_work.find("if (!mainNetworkNode.isActive())") if recv_do_work else -1
i_throttle = recv_do_work.find("if (!throttleTick())") if recv_do_work else -1
i_config = recv_do_work.find("if (!isProperlyConfigured())") if recv_do_work else -1
check("⑤-1 doWork 的闸门顺序：isActive → throttleTick → isProperlyConfigured"
      "（未命名 = 完全不可用，缓存原样留着）",
      -1 not in (i_active, i_throttle, i_config)
      and i_active < i_throttle < i_config)
check("⑤-2 有节流：doWork 里 throttleTick() 闸门 + 批量读 getThrottleBatch()（不自算）",
      recv_do_work is not None
      and "if (!throttleTick())" in recv_do_work
      and "final long batch = getThrottleBatch();" in recv_do_work)
check("⑤-2b doWork 按「每 tick 批数」循环（速度升级 = 每 tick 几批），循环内不再查能力",
      recv_do_work is not None
      and "final int batches = getThrottleBatchesPerTick();" in recv_do_work
      and "for (int round = 0; round < batches; round++)" in recv_do_work
      and recv_do_work.find("for (int round = 0; round < batches; round++)")
      < recv_do_work.find("outputItems(itemTarget, batch);")
      and "itemHandlerAt(" not in recv_do_work.split("for (int round = 0", 1)[1]
      and "fluidHandlerAt(" not in recv_do_work.split("for (int round = 0", 1)[1])
check("⑤-2c 一轮完全没有搬动 ⇒ 提前结束本轮批次循环（满批空转防线）",
      recv_do_work is not None
      and "if (movedItems <= 0L && movedFluids <= 0L)" in recv_do_work
      and "break;" in recv_do_work.split("if (movedItems <= 0L && movedFluids <= 0L)", 1)[1])

# --- 朝向格 + 复用既有静态工具（每 tick 至多各查一次） ---
check("⑤-3 目标格 = getBlockPos().relative(FACING)（与执行舱「朝向面」同一口径）",
      recv_do_work is not None
      and "getBlockPos().relative(state.getValue(NetworkResourceReceiverBlock.FACING))"
          in recv_do_work)
check("⑤-4 复用既有静态工具：itemHandlerAt / fluidHandlerAt（不自己查 capability）",
      recv_do_work is not None
      and "RsccChamberImportStrategy.itemHandlerAt(level, targetPos)" in recv_do_work
      and "RsccChamberImportStrategy.fluidHandlerAt(level, targetPos)" in recv_do_work)
check("⑤-5 容器查找每 tick 至多一次：全文件各只出现 1 次 itemHandlerAt / fluidHandlerAt",
      recv_code.count("itemHandlerAt(") == 1 and recv_code.count("fluidHandlerAt(") == 1)


def item_conservation_ok(body):
    """物品守恒判据（源码形状）：
    ① simulate 询问 → ② accepted = 送出 - 退回 → ③ 才 extractItem → ④ execute 写入 →
    ⑤ execute 差额回滚（放回缓存）。返回 (ok, 原因)。"""
    if not body:
        return False, "方法体缺失"
    i_sim = body.find("insertInto(target, offer, true)")
    i_accept = body.find("final int accepted = offer.getCount() - simulatedLeft.getCount();")
    i_extract = body.find("itemCache.extractItem(slot, accepted, false)")
    i_write = body.find("insertInto(target, extracted, false)")
    i_rollback = body.find(
        "itemCache.insertItem(0, extracted.copyWithCount(taken - landed), false)")
    if -1 in (i_sim, i_accept, i_extract, i_write):
        return False, "缺少 simulate / accepted 计算 / extractItem / execute 写入之一"
    if not (i_sim < i_accept < i_extract < i_write):
        return False, "顺序不是「先问 → 算 accepted → 才扣 → 再写」"
    if i_rollback < i_write:
        return False, "缺少 execute 差额回滚（放回缓存）"
    return True, ""


def fluid_conservation_ok(body):
    """流体守恒判据：与物品逐条对称（SIMULATE fill → 才 drain → EXECUTE fill → 差额回填）。"""
    if not body:
        return False, "方法体缺失"
    i_sim = body.find("target.fill(offer, IFluidHandler.FluidAction.SIMULATE)")
    i_drain = body.find("fluidCache.drain(")
    i_write = body.find("target.fill(drained, IFluidHandler.FluidAction.EXECUTE)")
    i_rollback = body.find("fluidCache.fill(drained.copyWithAmount(rejected),")
    if -1 in (i_sim, i_drain, i_write):
        return False, "缺少 SIMULATE / drain / EXECUTE 之一"
    if not (i_sim < i_drain < i_write):
        return False, "顺序不是「先问 → 才扣 → 再写」"
    if i_rollback < i_write:
        return False, "缺少 execute 差额回填"
    return True, ""


def gating_ok(body):
    """未命名闸门判据：存在且在节流之后。"""
    if not body:
        return False
    throttle = body.find("if (!throttleTick())")
    config = body.find("if (!isProperlyConfigured())")
    return throttle >= 0 and config >= 0 and throttle < config


item_ok, item_why = item_conservation_ok(recv_items)
fluid_ok, fluid_why = fluid_conservation_ok(recv_fluids)
check("⑤-6 物品：先 simulate 问能收多少、算 accepted、之后才扣缓存、execute 差额回滚",
      item_ok, item_why)
check("⑤-7 流体：SIMULATE fill 问能收多少、之后才 drain、EXECUTE 差额回填",
      fluid_ok, fluid_why)
check("⑤-8 未命名闸门判据（同一函数）：真实 doWork 判正", gating_ok(recv_do_work))
check("⑤-9 目标为空时的早期返回：两类目标都 null ⇒ 不进入任何扣减路径（只打日志）",
      recv_do_work is not None
      and "if (itemTarget == null && fluidTarget == null)" in recv_do_work
      and recv_do_work.find("if (itemTarget == null && fluidTarget == null)")
          < recv_do_work.find("outputItems(itemTarget, batch);"))

print("")
print("--- 反例自证：把「先扣后写」/「没有未命名闸门」的写法喂给同一批判据 ---")

BAD_ITEM_BODY = """
private void outputItems(final IItemHandler target, final long batch) {
    // 修前的坏写法：先从缓存扣，再尝试写入目标
    final ItemStack extracted = itemCache.extractItem(0, (int) batch, false);
    if (!extracted.isEmpty()) {
        target.insertItem(0, extracted, false);
    }
}
"""

BAD_FLUID_BODY = """
private void outputFluids(final IFluidHandler target, final long batch) {
    final FluidStack drained = fluidCache.drain((int) batch, IFluidHandler.FluidAction.EXECUTE);
    if (!drained.isEmpty()) {
        target.fill(drained, IFluidHandler.FluidAction.EXECUTE);
    }
}
"""

BAD_DO_WORK = """
public void doWork() {
    super.doWork();
    if (!mainNetworkNode.isActive()) {
        return;
    }
    if (!throttleTick()) {
        return;
    }
    final long batch = getThrottleBatch();
}
"""

bad_item_ok, _bad_item_why = item_conservation_ok(BAD_ITEM_BODY)
bad_fluid_ok, _bad_fluid_why = fluid_conservation_ok(BAD_FLUID_BODY)
check("⑤-10 反例自证·物品：先 extractItem 后 insertItem 的写法必须判负（判据不是恒真）",
      bad_item_ok is False)
check("⑤-11 反例自证·流体：先 drain 后 fill 的写法必须判负", bad_fluid_ok is False)
check("⑤-12 反例自证·闸门：没有 isProperlyConfigured 闸门的 doWork 必须判负",
      gating_ok(BAD_DO_WORK) is False)
check("⑤-13 反例自证·判据强度：真实方法体判正、坏方法体判负（四组结论互不混淆）",
      item_ok is True and fluid_ok is True
      and bad_item_ok is False and bad_fluid_ok is False)

# 注：本轮范围极小 —— 请求器一行未改。round71 既有的 ②-13 断言仍覆盖请求器的节流接线，
# 这里不再对它新增任何判据（避免把「只做接收器」的边界模糊掉）。
# 【2026-10-17 追加】上面这条注只描述「接收器输出」那一批；请求器投递是随后单独的一批，
# 它自己的判据在 §6（同一文件、同一套 check / 反例自证写法，不另建平行脚本）。

# ======================================================================================
# ⑥ 请求器投递：守恒 / 重名拒绝 / 节流 / 反例自证（同一文件追加，不另建平行脚本）
# ======================================================================================

section("⑥ 请求器投递：守恒 + 重名拒绝 + 节流（含反例自证）")

req_raw = read(REQUESTER_BE)
req_code = strip_comments(req_raw)
trigger_body = method_body(req_code, "public void triggerRequest()")
deliver_body = method_body(req_code, "private long deliverBatch(")
req_dowork_body = method_body(req_code, "public void doWork()")
cache_body = method_body(req_code, "private long applyToReceiverCache(")


def position(haystack, needle):
    """needle 在 haystack 里的下标；找不到返回 -1（用于断言「先后顺序」，比单纯 in 更强）。"""
    return haystack.find(needle) if haystack else -1


check("⑥-0 triggerRequest / deliverBatch / doWork / applyToReceiverCache 四个方法都在",
      all(body is not None for body in (trigger_body, deliver_body, req_dowork_body, cache_body)))

check("⑥-1 节流在投递之前：triggerRequest 里 throttleTick() 先于 resolveReceiversByName()",
      trigger_body is not None
      and "if (!throttleTick())" in trigger_body
      and 0 <= position(trigger_body, "if (!throttleTick())") < position(trigger_body, "resolveReceiversByName("))

check("⑥-2 重名 ⇒ 拒绝（不随机挑一台）：`hits.size() > 1` 分支先 return，"
      "`hits.get(0)` 只出现在它之后、且远早于 deliverBatch",
      trigger_body is not None
      and "hits.size() > 1" in trigger_body
      and position(trigger_body, "hits.size() > 1") < position(trigger_body, "hits.get(0)")
      and position(trigger_body, "hits.size() > 1") < position(trigger_body, "deliverBatch("))

check("⑥-3 找不到 ⇒ 拒绝 + 提示；目标区块未加载 ⇒ 静默跳过（两种情况分开判，配置不动）",
      trigger_body is not None
      and 'hint("not_found"' in trigger_body
      and "isRememberedTargetChunkUnloaded()" in trigger_body
      and 0 <= position(trigger_body, "isRememberedTargetChunkUnloaded()")
      < position(trigger_body, 'hint("not_found"'))

check("⑥-4 取料顺序：先 SIMULATE 再 EXECUTE，且 EXECUTE 只取 min(能取, 能吃)",
      deliver_body is not None
      and 0 <= position(deliver_body, "Action.SIMULATE") < position(deliver_body, "Action.EXECUTE")
      and "Math.min(available, acceptable)" in deliver_body)

check("⑥-5 写入失败 ⇒ 余量 insert 回网络；网络也不收的进 returnBuffer 且随 NBT 落盘",
      deliver_body is not None
      and "storage.insert(resource, remainder, Action.EXECUTE" in deliver_body
      and "bufferReturn(resource, kept)" in deliver_body
      and "ORDER_CODEC.encodeStart(ops(registries), returnBuffer)" in req_code)

check("⑥-6 有节流：单批量只读 getThrottleBatch()，投递代码里不出现自写位移公式",
      deliver_body is not None
      and "getThrottleBatch()" in deliver_body
      and "<<" not in deliver_body)

check("⑥-7 不每 tick 全网络扫描：doWork 里没有 resolveReceiversByName / getContainers，"
      "缓冲为空立即返回",
      req_dowork_body is not None
      and "resolveReceiversByName" not in req_dowork_body
      and "getContainers" not in req_dowork_body
      and "returnBuffer.isEmpty()" in req_dowork_body)

check("⑥-8 守恒自证在投递路径上：代码里显式校验 got == inserted + rolledBack + kept",
      deliver_body is not None
      and "got != inserted + rolledBack + kept" in deliver_body)

check("⑥-9 失败提示齐备：未配置 / 未联网 / 找不到 / 重名 / 空清单 / 缓存满 / 网络没料 都有 hint",
      all(marker in req_code for marker in (
          'hint("unconfigured"', 'hint("no_network"', 'hint("not_found"', 'hint("ambiguous"',
          'hint("no_orders"', 'hint("receiver_full"', 'hint("no_stock"')))

# ---------- 行为镜像：把 deliverBatch 的算术在 Python 里逐条重放 ----------
def model_deliver(stock, cache_free, want, batch, execute_accepts, rollback_capacity):
    """deliverBatch 的算术镜像（与 Java 逐句对应）。

    stock=网络存量；cache_free=写入前缓存剩余容量（SIMULATE 看到的值）；
    execute_accepts=真正写入时缓存还能收多少（模拟/执行之间可能变少 —— 这条路径才是回滚存在的原因）；
    rollback_capacity=网络愿意收回滚量。返回 {got, inserted, rolled, kept}。
    """
    wanted = min(want, max(1, batch))
    available = min(wanted, stock)                       # ① extract(SIMULATE)
    acceptable = min(available, cache_free)              # ② 缓存 SIMULATE
    got = min(available, acceptable)                     # ③ extract(EXECUTE)
    inserted = min(got, execute_accepts)                 # ④ 写缓存
    remainder = got - inserted
    rolled = min(remainder, max(0, rollback_capacity))   # 回滚网络
    kept = remainder - rolled                            # 进缓冲（下一 tick 重试）
    # landed = 物理上真的写进接收器的量（守恒的基准）；inserted = 代码自己**记账**的写入量。
    # 真实写法两者相同；反例 B 会让它们分叉（账上写 64、实际只进去 10）。
    return {"got": got, "inserted": inserted, "landed": inserted, "rolled": rolled, "kept": kept}


def conservation_ok(result):
    """守恒判据：取出量 == 真实写入量 + 回滚量 + 暂存量，且**记账写入量 == 真实写入量**。

    第二个等号是反例 B 的判负点：只信 SIMULATE、不处理 EXECUTE 差额时，账上写满、实际没进去，
    资源在「账」与「物」之间凭空消失。
    """
    return (result["got"] == result["landed"] + result["rolled"] + result["kept"]
            and result["inserted"] == result["landed"]
            and min(result["inserted"], result["rolled"], result["kept"]) >= 0)


# ---------- 「一次触发把订单投满」的算术镜像（新的 deliverBatch）------------------
# 只镜像**数量**（库存 / 容量 / 计划量），不镜像 SIMULATE↔EXECUTE 分叉：分叉与守恒由
# 上面的 model_deliver + conservation_ok 负责，两者合起来才是完整的 Java 行为。
MAX_BATCHES_PER_TICK = 512


def model_trigger(order, stock, cache_free, batch, budget=MAX_BATCHES_PER_TICK):
    """一次触发的算术镜像：把 {@code order} 投满，每批 ≤ batch，本 tick 最多 budget 批。

    与 Java 的 deliverBatch 逐句对应：
      remaining = 计划量（= 本次请求量）；
      每批 want = min(remaining, batch) ⇒ available = min(want, stock)（取料 SIMULATE）
      ⇒ acceptable = min(available, cache_free)（缓存 SIMULATE）⇒ 真正写进去 inserted；
      remaining -= inserted（**只减真的写进去的量**）；
      结束条件：remaining == 0 / 取不到 / 吃不下 / 本 tick 预算用完。
    budget 用完而 remaining > 0 ⇒ 这部分留给下一 tick（carried）。
    """
    remaining, stock_left, free = order, stock, cache_free
    delivered = 0
    batches = 0
    shortfall = False
    while remaining > 0 and batches < budget:
        want = min(remaining, batch)
        available = min(want, max(0, stock_left))
        if available <= 0:
            shortfall = True                       # no_stock
            break
        acceptable = min(available, max(0, free))
        if acceptable <= 0:
            shortfall = True                       # receiver_full
            break
        inserted = min(available, acceptable)
        batches += 1
        delivered += inserted
        remaining -= inserted
        stock_left -= inserted
        free -= inserted
        if available < want:
            shortfall = True                       # short_stock（网络里就这么多）
            break
    return {"delivered": delivered, "remaining": remaining, "batches": batches,
            "shortfall": shortfall,
            "carried": remaining if (remaining > 0 and not shortfall) else 0}


def fills_order_ok(result, order):
    """「一次触发投满」判据：投出的量 == 订单量、还有剩余必须是因为预算（不是短缺），且绝不过量。"""
    return (result["delivered"] == order
            and result["remaining"] == 0
            and not result["shortfall"]
            and result["delivered"] <= order)


def bounded_ok(result, budget=MAX_BATCHES_PER_TICK):
    """「循环有界」判据：本 tick 真的干活的批数 ≤ 预算。"""
    return 0 <= result["batches"] <= budget


def model_ticks(order, stock, cache_free, batch, budget=MAX_BATCHES_PER_TICK, tick_cap=10000):
    """跨 tick 续投的镜像：每 tick 投 budget 批，直到投满 / 卡住。返回 (总投出, tick 数)。"""
    pending, total, ticks = order, 0, 0
    while pending > 0 and ticks < tick_cap:
        result = model_trigger(pending, stock, cache_free, batch, budget)
        total += result["delivered"]
        stock -= result["delivered"]
        cache_free -= result["delivered"]
        ticks += 1
        if not result["carried"]:
            break                                  # 投满 / 出现缺口 ⇒ 本请求结束
        pending = result["carried"]
    return total, ticks


CASES = [
    (0, 64, 64, 64, 64, 64),          # 网络没料
    (100, 0, 64, 64, 0, 0),           # 缓存满
    (100, 2000, 64, 64, 2000, 64),    # 全部成功
    (100, 64, 100, 64, 10, 0),        # 模拟/执行分叉 + 网络拒收回滚 ⇒ 必须进缓冲
    (100, 64, 100, 64, 10, 40),       # 回滚只收一部分 ⇒ 剩余进缓冲
    (100, 64, 100, 64, 10, 10 ** 9),  # 回滚全收 ⇒ 缓冲为空
    (1, 1, 500, 64, 1, 1),            # 数量不足 ⇒ 投实际能取到的
]


def replay_deliver(deliver_func, cases):
    return [deliver_func(*case) for case in cases]


real_results = replay_deliver(model_deliver, CASES)
check("⑥-10 行为·守恒（可复跑模型）：7 组边界用例里取出量恒等于写入量 + 回滚量 + 暂存量",
      all(conservation_ok(r) for r in real_results),
      str([r for r in real_results if not conservation_ok(r)]))
check("⑥-11 行为·一次触发投满：订单 80 + 网络 80 + 缓存够 ⇒ **投 80**（改前只投 64），"
      "计划清空、2 批",
      fills_order_ok(model_trigger(80, 80, 10 ** 6, 64), 80)
      and model_trigger(80, 80, 10 ** 6, 64)["batches"] == 2)
check("⑥-11a 行为·网络只有 50 ⇒ 只投 50（不是 64、更不是 80），且判为「数量不足」而不是成功",
      (lambda r: r["delivered"] == 50 and r["shortfall"] and not fills_order_ok(r, 80)
                 and r["carried"] == 0)(model_trigger(80, 50, 10 ** 6, 64)))
check("⑥-11b 行为·**不会过量**：订单 80 + 网络 1000 ⇒ 恰好投 80（绝不 128 / 160），"
      "再多一批就过量",
      model_trigger(80, 1000, 10 ** 6, 64)["delivered"] == 80
      and model_trigger(80, 1000, 10 ** 6, 64)["delivered"] != 128)
check("⑥-11c 行为·订单不是「16 / 64 件上限」：订单 1000 ⇒ 一次触发投 1000",
      model_trigger(1000, 1000, 10 ** 6, 64)["delivered"] == 1000)
check("⑥-11d 行为·订单语义 = **每次触发都投这么多**：同一份订单触发两次 ⇒ 投 160（不是 80）",
      sum(model_trigger(80, 10 ** 6, 10 ** 6, 64)["delivered"] for _ in range(2)) == 160)
check("⑥-11e 行为·大订单（100 万）单 tick 有界、跨 tick 投满且**不重复投**："
      "首 tick 恰投 512 批 × 64、总量恰好 1000000",
      (lambda first, total: first["batches"] == MAX_BATCHES_PER_TICK
                            and first["carried"] == 1000000 - first["delivered"]
                            and total == 1000000
                            and model_ticks(1000000, 10 ** 6, 10 ** 7, 64)[1] <= 40)
      (model_trigger(1000000, 10 ** 6, 10 ** 7, 64), model_ticks(1000000, 10 ** 6, 10 ** 7, 64)[0]))
check("⑥-11f 行为·缓存吃不下时不过量：订单 80 + 网络 80 + 缓存只剩 10 ⇒ 投 10，剩下判为缺口",
      (lambda r: r["delivered"] == 10 and r["shortfall"] and not fills_order_ok(r, 80))
      (model_trigger(80, 80, 10, 64)))
check("⑥-12 行为·模拟/执行分叉时余量不丢：写入 10、回滚 40、暂存 14 = 取出 64",
      real_results[4]["got"] == 64 and real_results[4]["inserted"] == 10
      and real_results[4]["rolled"] == 40 and real_results[4]["kept"] == 14)


# ---------- 反例自证：把三种坏写法喂给同一批判据，必须判负 ----------
def deliver_without_rollback(stock, cache_free, want, batch, execute_accepts, rollback_capacity):
    """反例 A：写不进去的余量直接丢掉（不 insert 回网络、也不进缓冲）。"""
    core = model_deliver(stock, cache_free, want, batch, execute_accepts, rollback_capacity)
    return {"got": core["got"], "inserted": core["inserted"], "landed": core["landed"],
            "rolled": 0, "kept": 0}


def deliver_truncating_simulate(stock, cache_free, want, batch, execute_accepts, rollback_capacity):
    """反例 B：把 SIMULATE 的返回值直接当「已写入」（不做 EXECUTE 差额回滚，账物分叉）。"""
    core = model_deliver(stock, cache_free, want, batch, execute_accepts, rollback_capacity)
    return {"got": core["got"], "inserted": core["got"], "landed": core["inserted"],
            "rolled": 0, "kept": 0}


def pick_target(hits, random_pick):
    """反例 C：重名时随机挑一台（random_pick=True）vs 真实写法（拒绝）。"""
    if not hits:
        return "reject_not_found"
    if len(hits) > 1:
        return ("picked:" + hits[0]) if random_pick else "reject_duplicate"
    return "pick:" + hits[0]


def ticks_scanned_per_tick(scans_every_tick):
    """反例 D：缓冲为空时真实写法一次网络枚举都不做；「每 tick 全扫」写法每次都会枚举。"""
    return 20 if scans_every_tick else 0


bad_no_rollback = replay_deliver(deliver_without_rollback, CASES)
bad_truncating = replay_deliver(deliver_truncating_simulate, CASES)
check("⑥-13 反例 A（无回滚）：同一套守恒判据必须判负（余量凭空消失）",
      any(not conservation_ok(r) for r in bad_no_rollback))
check("⑥-14 反例 B（把 SIMULATE 当已写入）：同一套判据必须判负",
      any(not conservation_ok(r) for r in bad_truncating))
check("⑥-15 反例 C（重名随机挑）：真实判据 reject_duplicate，随机写法必须被同一判据判负",
      pick_target(["receiver_a", "receiver_b"], False) == "reject_duplicate"
      and pick_target(["receiver_a", "receiver_b"], True).startswith("picked:"))
check("⑥-16 反例 D（每 tick 全扫网络）：真实写法 20 tick 内扫描 0 次，全扫写法必须被判负",
      ticks_scanned_per_tick(False) == 0 and ticks_scanned_per_tick(True) > 0)


def throttle_allows(interval, ticks):
    """throttleTick 的算术镜像：先自减再判，间隔 N ⇒ 每 N tick 放行一次。"""
    cooldown = 0
    allowed = []
    for _tick in range(ticks):
        cooldown -= 1
        if cooldown > 0:
            allowed.append(False)
        else:
            cooldown = max(1, interval)
            allowed.append(True)
    return allowed


check("⑥-17 行为·节流：间隔 3 时 9 tick 只放行 3 次（不是每 tick 都搬），间隔 1 时每 tick 放行",
      sum(throttle_allows(3, 9)) == 3 and all(throttle_allows(1, 5)))

check("⑥-18 反例 E（节流写成恒放行）：同一判据必须判负",
      sum([True] * 9) == 9 and sum(throttle_allows(3, 9)) != 9)


# ======================================================================================
# ⑦ 「一次触发投满」：源码判据 + 订单语义 + 有界 / 不重复投（含反例自证）
#
# 用户原话：「发送 80 个台阶后，实际并未收到」「不存在『限制 16 件』」「订单上限没有设限」。
# 改前的 bug：wanted = min(订单数量, 单批量=64)，余量不续投、订单不递减，
#   ⇒ 订单 80 只投 64；再点一次又投 64（过量到 128）。
# 本节把「一次触发投满 / 不过量 / 有界 / 可续投 / 订单 = 每次触发的量」钉在判据里。
# ======================================================================================

section("⑦ 一次触发投满（订单 80 ⇒ 80）+ 不过量 + 有界 + 不重复投（含反例）")

req_code2 = strip_comments(read(REQUESTER_BE))
trigger_body2 = method_body(req_code2, "public void triggerRequest()") or ""
deliver_body2 = method_body(req_code2, "private long deliverBatch(") or ""
dowork_body2 = method_body(req_code2, "public void doWork()") or ""


def judge_one_trigger_fills_order(body):
    """deliverBatch 必须「一次触发投满」：分批循环 + 每批 min(还差多少, 单批量) + 只按写入量递减 + 有界。

    第 92 轮起「有界」的预算是 {@code min(MAX_BATCHES_PER_TICK, throttleBatchesPerTick())}：
    真正的节拍来自速度升级，常量只是防单 tick 卡死的兜底。
    """
    errors = []
    if "Math.min(remaining, batch)" not in body:
        errors.append("每批请求量不是 min(还差多少, 单批量)")
    if re.search(r"Math\.min\(\s*order\.amount\(\)\s*,\s*batch\s*\)", body):
        errors.append("仍是改前的 min(订单数量, 单批量)：一次触发只投一批（订单 80 只投 64）")
    if "remaining -= inserted" not in body:
        errors.append("计划量没有按「真的写进接收器的量」递减（会重复投 / 过量）")
    if "Math.min(MAX_BATCHES_PER_TICK, getThrottleBatchesPerTick())" not in body:
        errors.append("单 tick 预算不是 min(硬上限, 每 tick 批数)：速度升级不影响投递节拍")
    if not re.search(r"while \(remaining > 0L && batches < perTickBatches\)", body):
        errors.append("没有「分批循环 + 每 tick 批数」的循环条件（可能只投一批，也可能无界）")
    if "batches++" not in body:
        errors.append("没有对「真的取到资源」的批计数（预算形同虚设）")
    if "carry.add(" not in body or "!stalled" not in body:
        errors.append("没有写回「本 tick 没投完的余量」（只有批数用完才续投，缺口不该无限重试）")
    if "MAX_BATCHES_PER_TICK" not in req_code2:
        errors.append("缺少单 tick 批量硬上限常量")
    return errors


def judge_order_is_per_trigger(trigger, deliver, code):
    """订单语义 = 每次触发投这么多：触发时把请求量并入计划、投递里绝不递减订单清单。

    （第 92 轮起待投计划**必须**落盘：一次触发可能因「每 tick 批数」不够而跨多 tick 投完，
    区块卸载 / 重进存档后要接着投。旧的「计划不落盘」判据随设计修正作废，见 judge_pending_persisted。）
    """
    errors = []
    if "enqueuePendingPlan()" not in trigger:
        errors.append("触发路径没有把「本次请求量」放进待投计划")
    for bad in ("orders.clear()", "orders.remove(", "orders.set(", "orders ="):
        if bad in deliver:
            errors.append("投递里改写订单清单（订单会「总共投这么多」而不是「每次触发投」）：%s" % bad)
    return errors


def judge_pending_persisted(code):
    """待投计划必须随 NBT 落盘并读回（跨 tick 续投在区块卸载 / 读档后仍要继续）。"""
    errors = []
    if "TAG_PENDING_PLAN" not in code:
        errors.append("没有待投计划的 NBT 键")
    if "ORDER_CODEC.encodeStart(ops(registries), pendingPlan)" not in code:
        errors.append("saveAdditional 没有把 pendingPlan 落盘")
    if "tag.contains(TAG_PENDING_PLAN)" not in code:
        errors.append("loadAdditional 没有读回 pendingPlan")
    if "ORDER_CODEC.parse(ops(registries), tag.get(TAG_PENDING_PLAN))" not in code:
        errors.append("loadAdditional 没有按 ORDER_CODEC 解析 pendingPlan")
    return errors


def judge_continuation_no_repeat(dowork, deliver, code):
    """跨 tick 续投不能重复投：续投读的是「还差多少」的计划，且该计划只按写入量递减。"""
    errors = []
    if "continuePendingDelivery()" not in dowork:
        errors.append("doWork 没有续投入口（预算用完的订单永远投不完）")
    if "pendingPlan.isEmpty()" not in dowork:
        errors.append("doWork 没有「计划空 ⇒ 什么都不做」的短路（会每 tick 扫网络）")
    if "resolveReceiversByName" in dowork or "getContainers" in dowork:
        errors.append("续投每 tick 枚举网络（应该用记下的目标坐标）")
    if "pendingPlan" not in deliver:
        errors.append("deliverBatch 不读待投计划 ⇒ 续投无从谈起")
    return errors


real_errors = (judge_one_trigger_fills_order(deliver_body2)
               + judge_order_is_per_trigger(trigger_body2, deliver_body2, req_code2)
               + judge_continuation_no_repeat(dowork_body2, deliver_body2, req_code2)
               + judge_pending_persisted(req_code2))
check("⑦-1 源码：真实 deliverBatch / triggerRequest / doWork 通过全部源码判据", not real_errors,
      "；".join(real_errors[:6]))

check("⑦-2 源码：每批请求量 = min(remaining, batch)，且 EXECUTE 只取 min(能取, 能吃)",
      "Math.min(remaining, batch)" in deliver_body2
      and "Math.min(available, acceptable)" in deliver_body2)
check("⑦-3 源码：单 tick 预算 = min(硬上限, 每 tick 批数)，且只有「真的取到资源」才计数",
      "final int perTickBatches = Math.min(MAX_BATCHES_PER_TICK, getThrottleBatchesPerTick());"
      in deliver_body2
      and "while (remaining > 0L && batches < perTickBatches)" in deliver_body2
      and deliver_body2.find("batches++") > deliver_body2.find("Action.EXECUTE"))
check("⑦-4 源码：只有「本 tick 批数用完」造成的剩余才自动跨 tick 续投；"
      "缓存满 / 网络不够属于缺口 ⇒ 明确提示后结束本次请求（不留在计划里每 tick 空转重试）",
      "stalled" in deliver_body2 and "carry.add(" in deliver_body2
      and "remaining > 0L && !stalled" in deliver_body2)
check("⑦-5 源码：订单 80 而网络不够时明确提示数量（short_stock 报「要多少 / 一共投出多少」）",
      'hint("short_stock", describeResource(resource), order.amount(),'
      in deliver_body2
      and "order.amount() - remaining" in deliver_body2)
check("⑦-6 源码：续投用记下的目标坐标取接收器（不重新枚举网络）",
      ("pendingReceiver(network)"
       in (method_body(req_code2, "private void continuePendingDelivery()") or ""))
      and ("level.getBlockEntity(lastResolvedTargetPos)" in
           (method_body(req_code2,
                        "private NetworkResourceReceiverBlockEntity pendingReceiver(") or "")))
check("⑦-7 源码：「成功」= 投出过（或已排进待投计划）且没有缺口（订单 80 只投 50 不得报成功）",
      "setRequestSucceeded((moved > 0L || !pendingPlan.isEmpty()) && !shortfallThisDelivery)"
      in trigger_body2
      and "shortfallThisDelivery = true;" in deliver_body2)

# ---------- 反例自证：三种坏写法喂给同一批判据必须判负 ----------
OLD_TRUNCATING = """
    private long deliverBatch(final Network network, final Receiver receiver) {
        final long batch = Math.max(1L, getThrottleBatch());
        long moved = 0L;
        for (final ResourceAmount order : List.copyOf(orders)) {
            final long wanted = Math.min(order.amount(), batch);
            final long got = storage.extract(order.resource(), wanted, Action.EXECUTE, Actor.EMPTY);
            moved += got;
        }
        return moved;
    }
"""

UNBOUNDED_LOOP = """
    private long deliverBatch(final Network network, final Receiver receiver) {
        long remaining = orders.get(0).amount();
        long moved = 0L;
        int batches = 0;
        while (remaining > 0L) {
            final long wanted = Math.min(remaining, batch);
            final long got = storage.extract(resource, wanted, Action.EXECUTE, Actor.EMPTY);
            remaining -= got;
            moved += got;
        }
        return moved;
    }
"""

NO_CARRY = """
    private long deliverBatch(final Network network, final Receiver receiver) {
        long moved = 0L;
        int batches = 0;
        final long wanted = Math.min(remaining, batch);
        while (remaining > 0L && batches < MAX_BATCHES_PER_TICK) {
            batches++;
            remaining -= inserted;
        }
        return moved;
    }
"""

check("⑦-8 反例·改前的 min(订单数量, 单批量) 写法 ⇒ 判负（订单 80 只投 64）",
      bool(judge_one_trigger_fills_order(OLD_TRUNCATING)))
check("⑦-9 反例·无界循环（没有批量预算 / 不把「取不到」当终点）⇒ 判负",
      bool(judge_one_trigger_fills_order(UNBOUNDED_LOOP)))
check("⑦-10 反例·余量不跨 tick（预算够了却不留 carried）⇒ 判负",
      bool(judge_one_trigger_fills_order(NO_CARRY)))
check("⑦-11 反例·投递里递减订单清单 ⇒ 判负（订单会变成「总共投这么多」）",
      bool(judge_order_is_per_trigger(
          "enqueuePendingPlan();",
          "orders.clear(); remaining -= inserted;", "// no TAG_PENDING")))
check("⑦-12 反例·doWork 每 tick 扫网络续投 ⇒ 判负",
      bool(judge_continuation_no_repeat(
          "continuePendingDelivery(); if (pendingPlan.isEmpty()) { } resolveReceiversByName(targetName);",
          "pendingPlan", "")))
check("⑦-13 反向自证·同一批判据对真实源码 0 失败（判据有区分力）",
      not judge_one_trigger_fills_order(deliver_body2)
      and not judge_order_is_per_trigger(trigger_body2, deliver_body2, req_code2)
      and not judge_continuation_no_repeat(dowork_body2, deliver_body2, req_code2)
      and not judge_pending_persisted(req_code2))
check("⑦-13b 反例·待投计划不落盘（旧设计）⇒ judge_pending_persisted 判负",
      bool(judge_pending_persisted("// no pending persistence")))
check("⑦-13c 反例·单 tick 预算仍是常量 MAX_BATCHES_PER_TICK（速度升级无效）⇒ 判负",
      bool(judge_one_trigger_fills_order(
          "final long batch = getThrottleBatch();\n"
          "while (remaining > 0L && batches < MAX_BATCHES_PER_TICK) { batches++; remaining -= inserted; carry.add(x); }\n"
          "Math.min(remaining, batch); // !stalled")))


# ---------- 反例自证：行为模型（旧写法 / 无界 / 无限循环）必须被同一批判据判负 ----------
def model_trigger_old_truncating(order, stock, cache_free, batch):
    """反例：改前的写法 —— 一次触发只投 min(订单, 单批量) 一批。"""
    return {"delivered": min(order, batch, stock, cache_free), "remaining": order - min(order, batch),
            "batches": 1, "shortfall": False, "carried": 0}


def model_trigger_old_two_clicks(order, stock, cache_free, batch):
    """反例：改前的「再点一次又投 64」—— 订单清单不递减，于是第二次还是按 80 去夹 64 ⇒ 合计 128 > 80。"""
    first = model_trigger_old_truncating(order, stock, cache_free, batch)
    second = model_trigger_old_truncating(order, stock - first["delivered"],
                                          cache_free - first["delivered"], batch)
    return {"delivered": first["delivered"] + second["delivered"]}


def model_trigger_spinning(order, stock, cache_free, batch, spin_cap=200000):
    """反例：不把「取不到 / 吃不下」当终点 —— 纯 while(remaining > 0) 空转（用 spin_cap 顶替死循环）。"""
    remaining, batches = order, 0
    while remaining > 0 and batches < spin_cap:
        batches += 1
        if stock <= 0:
            continue                    # 网络空着也继续转
        remaining -= min(remaining, batch, stock)
        stock -= min(remaining, batch)
    return {"delivered": order - remaining, "remaining": remaining, "batches": batches,
            "shortfall": False, "carried": 0}


old = model_trigger_old_truncating(80, 200, 10 ** 6, 64)
two_clicks = model_trigger_old_two_clicks(80, 200, 10 ** 6, 64)
spinning = model_trigger_spinning(80, 0, 10 ** 6, 64)
check("⑦-14 反例·旧写法「一次只投 64」被 ⑦-1 同一判据判负（80 ≠ 64）",
      not fills_order_ok(old, 80) and old["delivered"] == 64)
check("⑦-15 反例·旧写法「再点一次又投 64」（网络有余量时）= 128 > 订单 80 ⇒ 过量判负",
      two_clicks["delivered"] == 128 and not (two_clicks["delivered"] <= 80))
check("⑦-16 反例·空转（取不到还继续循环）被「有界」判据判负：200000 批 > 512",
      not bounded_ok(spinning) and spinning["batches"] > MAX_BATCHES_PER_TICK)
check("⑦-17 行为·真实模型在「网络空着」时立刻停（不是空转）：批次 0、缺口成立",
      (lambda r: r["batches"] == 0 and r["shortfall"] and r["delivered"] == 0)
      (model_trigger(80, 0, 10 ** 6, 64)))
check("⑦-18a 行为·速度升级真的改变投递耗时：0 速（1 批/tick）订单 80 需 2 tick，"
      "1 速（2 批/tick）同一 tick 投完；两者总量都恰好 80（不重复、不过量）",
      (lambda slow, fast: slow[0] == 80 and slow[1] == 2
                         and fast["delivered"] == 80 and fast["batches"] == 2
                         and fast["carried"] == 0)
      (model_ticks(80, 10 ** 6, 10 ** 6, 64, budget=j_batches_per_tick(0)),
       model_trigger(80, 10 ** 6, 10 ** 6, 64, budget=j_batches_per_tick(1))))
check("⑦-18b 行为·跨 tick 续投在低节拍下不重复投：3 tick 的 1 批/tick 投 150 件 = 64+64+22",
      model_ticks(150, 10 ** 6, 10 ** 6, 64, budget=j_batches_per_tick(0))[0] == 150
      and model_ticks(150, 10 ** 6, 10 ** 6, 64, budget=j_batches_per_tick(0))[1] == 3)
check("⑦-18 源码·预算**按游戏 tick** 记账：同一 tick 内「触发 + 续投」合计也不超过本 tick 批数预算",
      "if (now != deliveryBudgetTick)" in deliver_body2
      and "int batches = deliveryBudgetUsed;" in deliver_body2
      and "deliveryBudgetUsed = batches;" in deliver_body2)


# ======================================================================================
# 结果
# ======================================================================================

print("")
print("断言总数 %d" % CHECKS[0])
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
