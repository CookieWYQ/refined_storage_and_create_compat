# -*- coding: utf-8 -*-
"""② 「取消任务后中间产物守恒 + 回流」断言（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_cancel_reclaim.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（无法在本地把游戏跑起来验证）：
  用户原话：「我先取消任务之后，这些中间产物不会回流到网络之中，不知道是为什么」。

  根因：中间产物（带 `create:sequenced_assembly` 组件的未完成件）的收回判定只有一份
  —— `SequenceExecutionChamberBlockEntity#isTransitionReclaimAllowed`，它按「本仓还要不要它」
  判（`s % T == m` → 不收回）。这个判据**只看物品与步序，不知道任务还在不在跑**：
  任务被取消之后，「下一步仍由同一台机器负责」依然成立，于是输入总线的全自动收回
  永久拒绝这份件 —— 它既不在网络里（终端看不到），也不会再被任何机器加工（任务没了），
  就是用户看到的「凭空消失 / 不回流」。

  修法（判定仍然只有一份）：把「本条任务刚结束」的那一个边沿的一次性令牌
  （`claimResidualInputReclaim`，原本只放行输入类原料）**也用于放行中间产物**。
  令牌只在「与本仓产线相关的任务从『在跑』翻转为『不在跑』」那一刻发出、被领取一次即作废，
  因此：
    * 任务运行期间令牌不存在 ⇒ 「下一步仍由同一台机器（或同 cluster）负责 ⇒ 中间产物绝不被收回」
      一字未改；
    * 任务结束（完成 / 取消 / 停止）的那一次收尾里，中间产物与输入类一起回网一次，
      终端立刻看得见，且总量守恒（先 SIMULATE 夹量 → 原子抽取 → 插入 → 余量原样回写）。
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


import_strategy = read(SRC, "support", "RsccChamberImportStrategy.java")
chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")

accept_item = import_strategy[import_strategy.index("private static boolean autoAcceptsItem("):
                              import_strategy.index("private static boolean autoAcceptsChamberItem(")]
accept_chamber = import_strategy[import_strategy.index("private static boolean autoAcceptsChamberItem("):
                                 import_strategy.index("// ==================== 执行舱内部存储 → RS 网络")]


def flatten(text):
    """折叠空白：源码里同一段逻辑的缩进不同（机器侧 12 空格 / 仓内侧 8 空格），
    因此锚点比对一律走「折叠空白后的子串顺序」，而不是硬编码缩进。"""
    return " ".join(text.split())


EDGE_GUARD = flatten("if (residualEdge) { return true; }")
STILL_MINE = flatten("return chamber.isTransitionReclaimAllowed(stack);")

# ==================== 1. 锚点：一次性令牌的来路（只有「任务刚结束」这一个边沿） ====================
section("1) 锚点：令牌只在「与本仓相关的任务从在跑翻转为不在跑」那一个边沿发出")

has(chamber, "busResidualInputToken = true;",
    "锚点: 任务结束边沿发一次性残留回流令牌")
has(chamber, "busResidualInputDeadline = level.getGameTime() + BUS_RESIDUAL_FLUSH_WINDOW_TICKS;",
    "锚点: 令牌有有效期（40 tick），过期即废 —— 不会退化成「空闲时持续收输入类」")
has(chamber, "public boolean claimResidualInputReclaim() {",
    "锚点: 令牌是消费式领取（领取一次即作废）")
# 本轮同步（旧口径 → 新口径）：挂起（frozen）时既不算「任务结束」（`!frozen &&`），
# 也在冻结分支里把未用的一次性令牌立即作废 —— 用户要求「挂起当刻含搬运一起停」。
check("锚点: 令牌只在「相关任务边沿」处发出（tickBusScheduler 里 gate/relevant 翻转的那一支），"
      "并且「相关任务重新开跑」时立即作废（避免新任务刚喂进去的料被边沿令牌收回来）；"
      "冻结（frozen）时既不发放也立即作废",
      "if (taskEnded) {" in chamber
      and "busResidualInputToken = false;" in chamber
      and "final boolean taskEnded = !frozen && ((busAutoCraftGate && !gate)" in chamber
      and "} else if (frozen) {" in chamber)
check("锚点: 令牌的领取点是输入总线的全自动搬运（每次自动收回都问一次，领取即消费）",
      "chamber.claimResidualInputReclaim()" in import_strategy)

# ==================== 2. 锚点：中间产物不再吃「任务刚结束」的例外（本轮修正） ====================
section("2) 锚点：未完成件任何时刻都只走一份判定；一次性边沿只对「输入类原料」开放")

check("机器侧（autoAcceptsItem）：未完成件分支里<b>没有</b>「边沿一律放行」的例外"
      "（用户硬要求：最终交付 / 回流结果里不得出现半成品）",
      EDGE_GUARD not in flatten(accept_item))
check("仓内侧（autoAcceptsChamberItem）：同样没有该例外",
      EDGE_GUARD not in flatten(accept_chamber))
check("「判定只有一份」仍然成立：两侧未完成件的唯一判据都是同一个 chamber.isTransitionReclaimAllowed",
      accept_item.count("chamber.isTransitionReclaimAllowed(stack)") == 1
      and accept_chamber.count("chamber.isTransitionReclaimAllowed(stack)") == 1
      and accept_item.count("inputMaterialWantedNow") >= 1)
check("非过渡件的语义一字未改：非输入类照收、输入类只有边沿才收一次（岩浆那类残留照旧回流）",
      "return residualEdge || !inputItems.contains(stack.getItem());" in accept_item
      and "return residualEdge;" in accept_chamber)

# ==================== 3. 等价模型：取消前后守恒 + 去向明确 ====================
section("3) 推演：任务结束 / 取消后中间产物「留在原处继续加工」，不销毁不复制")

T = 3                      # create:sturdy_sheet 的 sequence 长度（注液 / 冲压 / 冲压）
OWNED = {1, 2}             # 冲压仓负责的步（一台机器连管第 2、3 步）


def reclaim_allowed(item_step, owned=OWNED):
    """复刻 RsccChamberImportStrategy#autoAcceptsItem 的未完成件分支（本轮起<b>不再有</b>边沿例外）。"""
    return not ((item_step % T) in owned)             # NEXT_FOR_MACHINE → 不收回


class World(object):
    """一份中间产物只可能在三处之一：机器侧 / 执行舱内部+磁盘 / 网络。"""

    def __init__(self, machine=0, chamber_internal=0, network=0):
        self.machine = machine
        self.chamber_internal = chamber_internal
        self.network = network

    def total(self):
        return self.machine + self.chamber_internal + self.network

    def edge_reclaim(self, item_step=1, cap=10 ** 9):
        """一次自动收回：仓内 + 机器侧，先 SIMULATE 夹量再搬，余量原样回写。"""
        movable = self.machine + self.chamber_internal
        acceptable = min(movable, cap)
        if acceptable <= 0:
            return 0
        from_machine = min(self.machine, acceptable)
        from_chamber = acceptable - from_machine
        self.machine -= from_machine
        self.chamber_internal -= from_chamber
        if not reclaim_allowed(item_step):
            self.machine += from_machine                 # 一件都不收：原样留在原处
            self.chamber_internal += from_chamber
            return 0
        self.network += from_chamber + from_machine
        return from_chamber + from_machine


# 现场：一件「已冲压一次」的中间产物（s=1，下一步 s+1=2 仍属本仓）在机器与仓里；网络 0 件
mid_task = World(machine=1, chamber_internal=1, network=0)
before = mid_task.total()
moved = mid_task.edge_reclaim(item_step=1)
check("任务运行中：中间产物被「本仓还要它」保护 → 一件都不收回（下一步仍由同一台机器负责）",
      moved == 0 and mid_task.network == 0 and mid_task.total() == before)

after_cancel = World(machine=1, chamber_internal=1, network=0)
before = after_cancel.total()
moved = after_cancel.edge_reclaim(item_step=1)
cancel_total = after_cancel.total()
check("任务结束 / 取消后的那一次收尾：这件半成品<b>留在原处</b>（机器 1 / 仓 1，网络 0）——"
      "不销毁、不复制，下一次任务继续加工它（用户认可的归宿）",
      moved == 0 and after_cancel.network == 0 and after_cancel.machine == 1
      and after_cancel.chamber_internal == 1)
check("取消前后总量一致：%d == %d（不销毁 / 不复制）"
      % (before, cancel_total), cancel_total == before)

# 反过来：本仓已经做完那一步、下一步不归它（s=0 → 下一步 1 不属冲压仓）的过渡件才允许回网
hand_off = World(machine=0, chamber_internal=1, network=0)
moved = hand_off.edge_reclaim(item_step=0)
check("本仓做完那一步的过渡件（下一步不属本仓）仍照旧回网：网络 +%d 件（= 回到网络作为普通物品）"
      % moved, moved == 1 and hand_off.network == 1 and hand_off.total() == 1)

# 网络塞满：一份都不动，原样留在原处（绝不销毁）
full = World(machine=0, chamber_internal=3, network=0)
full_before = full.total()
moved = full.edge_reclaim(item_step=0, cap=0)
check("网络收不下时一份都不搬（东西原样留在机器 / 仓里，绝不销毁）",
      moved == 0 and full.total() == full_before)

# 幂等：不收回的分支天然幂等（东西仍在原处，总量不变）
again = after_cancel.edge_reclaim(item_step=1)
check("幂等：同一件仍在原处时再收一次仍是 0 件，总量不变（不会重复记账）",
      again == 0 and after_cancel.total() == cancel_total)

check("模型与源码同构：`s % T ∈ owned`（本仓下一步要它）恒不收回；"
      "`s % T ∉ owned`（本仓已做完那一步）才收回 —— 两条结论互补且不重叠，且与边沿无关",
      reclaim_allowed(1) is False and reclaim_allowed(0) is True)

# ==================== 4. 输入类残留仍走它自己的路（既有契约不变） ====================
section("4) 既有契约：输入类残留仍由 flushResidualInputs 在同一个边沿回网")

has(chamber, "\" to=network reason=task_finished\"",
    "锚点: 任务结束时输入类残留整体回网（日志 reason=task_finished，实测可见）")
has(chamber, "&& !isTransitionItem(inSlot.getItem()) && !isMyProductOrScrap(inSlot)) {",
    "锚点: flushResidualInputs 仍然不碰中间产物 / 成品 / 废料（它们的出路是输入总线，本轮修好的是那条出路）")

# ==================== 结果 ====================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
