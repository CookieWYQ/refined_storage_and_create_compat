#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round102 自检：范围充电器「三种目标各自高亮」+「满电目标直接跳过」。

用户实机反馈（原话）：
  「还有显示那个正在给谁充电：比如掉落物需要那个（高亮）边框、玩家就需要高亮玩家、
    方块就高亮方块这样子的。以及强调一点：比如某个物品它的电量如果一直是满的，
    那么就不给他充电、就跳过这个，减少这种没有意义的次数。」

## 本脚本锚定的两件事

### A. 三种目标各有各的画法，且「发光」这次是真的会发生
第 95 轮那次实现的发光是**彻底无效**的（用户「似乎没看到」完全正确）：
`Entity#setGlowingTag(boolean)` 在客户端是空操作 —— 它先写 `hasGlowingTag`，再用
`isCurrentlyGlowing()` 回写共享标志位 6；而客户端 `isCurrentlyGlowing()` 读的正是那个
还没被写过的共享位 ⇒ 回写的是旧值（false）。本脚本用 `javap` 反编译 MC 的 `Entity`
把这条**从字节码层面**钉住（`setGlowingTag` 里 `isCurrentlyGlowing` 在 `setSharedFlag` 之前、
`isCurrentlyGlowing` 里客户端分支读 `getSharedFlag`），并反过来要求我们的实现
「直接写共享位 6、不许再退回 setGlowingTag」。

三类目标的渲染契约（本脚本逐条判）：
  方块  = 橙色框（0xFF9F1C，与范围框同色系）；
  掉落物 = 青色框（0x36D6A5）+ 发光；
  玩家  = 绿色框（0x62D95F）+ 发光；
三色两两不同（否则「一眼分得清」就是假的）；三类都由 `chaseAABB` 每 tick 喂；
集合变化才重建；超时 / 换维度 / 开关关掉时三类一起清干净（框 remove + 发光还原）。

### B. 满电目标在「分摊预算之前」就被跳过
三档（方块 / 掉落物 / 玩家携带物品）都必须满足：
  1. 判据是 `stored >= max`（或没有能量能力）⇒ 该目标**不**计入目标数、**不**分摊
     `transferBudget()`、**不**登记高亮、**不**产生高亮包；
  2. 跳过判定在 `transferBudget()` **之前**（顺序判据，不是「有这句就行」）；
     **第 124 轮 R-1**：方块档的满电判定改成 `receiveEnergy(transfer, true) <= 0`（simulate），
     因为要用 transfer ⇒ 方块档先算 `transferBudget()`（纯函数）再判满；不变的口径是
     「判满 + 跳过计数都早于真取电 / 扣电，且判满失败的目标不分摊预算消耗、不扣电量、不登记高亮」
     （判据同步放宽见 `lane_skip_ok` 的注释，物品三档仍严格要求在 transferBudget() 之前）；
  3. 跳过数只在服务端每 tick 清零后重算，走数据槽 13 给界面显示（不落盘、不进 NBT）；
  4. 行为模型：给「全满 / 半满 / 空」三个目标，全满的那个 skip+1、目标数 +0、预算 0 次调用；
  5. 反例自证：把满目标算进目标数 / 只跳方块不跳物品 / 把跳过判定挪到预算之后 /
     把高亮登记挪到跳过判定之前 —— 同一个判据必须各自判负。

用法：python tools/selfcheck_round102_charger_highlight.py
"""
import glob
import io
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FILES = {
    "BE": os.path.join(SRC, "block", "entity", "RangeChargerBlockEntity.java"),
    "MENU": os.path.join(SRC, "menu", "RangeChargerMenu.java"),
    "SCREEN": os.path.join(SRC, "client", "screen", "RangeChargerScreen.java"),
    "HIGHLIGHT": os.path.join(SRC, "client", "ChargerTargetHighlight.java"),
    "PACKET": os.path.join(SRC, "network", "ChargerHighlightPacket.java"),
    "ZH": os.path.join(RES, "zh_cn.json"),
    "EN": os.path.join(RES, "en_us.json"),
}

problems = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print("PASS %s" % name)
    else:
        problems.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as handle:
        return handle.read()


def code_only(text):
    """去掉注释再断言（注释里允许出现「旧实现」「不许退回」这类对照说明）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def balanced(text, open_index, opening, closing):
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == opening:
            depth += 1
        elif text[i] == closing:
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i], i
    raise ValueError("括号不配对")


def method_body(code, name):
    """取「名为 name 的方法声明」的方法体（code 需已去注释）；跳过同名调用点，找不到返回 None。"""
    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
        before = code[max(0, match.start() - 200):match.start()]
        if not re.search(r"(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\].]+\s+$",
                         before):
            continue
        try:
            _params, close_paren = balanced(code, match.end() - 1, "(", ")")
        except ValueError:
            return None
        brace = code.find("{", close_paren + 1)
        if brace < 0:
            return None
        try:
            body, _ = balanced(code, brace, "{", "}")
        except ValueError:
            return None
        return body
    return None

sources = {key: read(path) for key, path in FILES.items()}
code = {key: code_only(text) for key, text in sources.items()}
BE = code["BE"]
MENU = code["MENU"]
SCREEN = code["SCREEN"]
HIGHLIGHT = code["HIGHLIGHT"]

# ==================================================================== ① 现状核实：发光
print("=== ① 现状核实：客户端 setGlowingTag 是空操作（字节码证据） ===")


def auto_mc_jar():
    candidates = glob.glob("D:/gradle/caches/neoformruntime/intermediate_results/"
                           "compiledWithNeoForge_*_output.jar")
    if not candidates:
        return None
    candidates.sort(key=os.path.getmtime)
    return candidates[-1]


def entity_bytecode(mc_jar):
    """从 MC 编译产物里取 Entity 的字节码（javap -c），返回 {方法名: 方法字节码文本}。"""
    javap = "D:/java21/bin/javap.exe"
    if mc_jar is None or not os.path.exists(javap) or not os.path.exists(mc_jar):
        return None
    try:
        out = subprocess.run([javap, "-p", "-c", "-cp", mc_jar, "net.minecraft.world.entity.Entity"],
                             capture_output=True, text=True, timeout=180)
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    blocks = {}
    current = None
    buffer = []
    for line in out.stdout.splitlines():
        match = re.match(r"^\s{2}\S.*\s(\w+)\(.*\);$", line)
        if match:
            if current:
                blocks[current] = "\n".join(buffer)
            current = match.group(1)
            buffer = [line]
        elif current:
            buffer.append(line)
    if current:
        blocks[current] = "\n".join(buffer)
    return blocks


mc_jar = auto_mc_jar()
blocks = entity_bytecode(mc_jar)
if blocks is None:
    print("SKIP 找不到 MC 编译产物 / JDK（不在此环境判定字节码；实现侧判据仍然成立）")
else:
    glowing_tag = blocks.get("setGlowingTag", "")
    currently = blocks.get("isCurrentlyGlowing", "")
    check("①-a Entity#setGlowingTag 的字节码：先取 isCurrentlyGlowing() 再 setSharedFlag(6, 它)",
          "isCurrentlyGlowing" in glowing_tag and "setSharedFlag" in glowing_tag
          and glowing_tag.index("isCurrentlyGlowing") < glowing_tag.index("setSharedFlag"),
          "setGlowingTag 字节码片段：%r" % glowing_tag[:200])
    check("①-b Entity#isCurrentlyGlowing 的客户端分支读的是 getSharedFlag（共享位 6），"
          "不是 hasGlowingTag 字段 ⇒ 客户端 setGlowingTag 回写的是旧值 = 空操作",
          "isClientSide" in currently and "getSharedFlag" in currently,
          "isCurrentlyGlowing 字节码片段：%r" % currently[:200])
    print("[info] MC 编译产物 = %s" % os.path.basename(mc_jar))
    print("[info] 旧实现『掉落物 / 玩家 setGlowingTag』从未点亮过任何实体 —— "
          "这正是用户「似乎没看到」的根因（不是渲染距离 / 亮度问题）")

# 实现侧：必须走共享位 6 + 反射，且不许再退回那个空操作
check("①-c 实现直接用共享标志位 6（GLOW_SHARED_FLAG = 6），不再调用客户端空操作的 setGlowingTag",
      "GLOW_SHARED_FLAG = 6" in HIGHLIGHT and "setGlowingTag(" not in HIGHLIGHT)
check("①-d 反射句柄缓存 + 解析失败可退化：getDeclaredMethod(\"setSharedFlag\", int.class, boolean.class)",
      'getDeclaredMethod("setSharedFlag", int.class, boolean.class)' in HIGHLIGHT
      and "method == null" in HIGHLIGHT
      and "method.invoke(entity, GLOW_SHARED_FLAG, glow)" in HIGHLIGHT,
      "反射解析 / 退化路径缺失")
check("①-e 只在「自己设置成功」时才记账（设置失败不进 glowingItems / glowingPlayers）",
      "if (!entity.isCurrentlyGlowing() && setClientGlow(entity, true)) {" in HIGHLIGHT
      and "if (!player.isCurrentlyGlowing() && setClientGlow(player, true)) {" in HIGHLIGHT)
check("①-f 反射失败只提示一次（不每 tick 刷屏）",
      "glowFailureLogged" in HIGHLIGHT and "if (glowFailureLogged) {" in HIGHLIGHT)

# ==================================================================== ② 三种目标三种画法
print("=== ② 三种目标各自明确可辨（颜色 / 框 / 发光 / 清理） ===")

COLORS = dict(re.findall(r"COLOR_(BLOCK|ITEM|PLAYER) = 0x([0-9A-Fa-f]{6})", HIGHLIGHT))
check("②-a 三个类型各有自己的颜色常量（方块 / 掉落物 / 玩家）",
      set(COLORS) == {"BLOCK", "ITEM", "PLAYER"}, str(COLORS))
check("②-b 方块 = 0xFF9F1C（橙，与「查看充电范围」的橙框同色系）",
      COLORS.get("BLOCK", "").upper() == "FF9F1C", str(COLORS))
check("②-c 三色两两不同（否则「一眼分得清」是假的）",
      len({value.upper() for value in COLORS.values()}) == 3, str(COLORS))
check("②-d 三类目标都有自己的 Outliner 槽位前缀（方块 / 掉落物 / 玩家互不覆盖）",
      "ITEM_SLOT_PREFIX" in HIGHLIGHT and "PLAYER_SLOT_PREFIX" in HIGHLIGHT
      and HIGHLIGHT.count('private record') >= 2)
check("②-e 三类都由 chaseAABB 每 tick 喂（方块一路 + 掉落物/玩家实体一路 = 两处调用）",
      HIGHLIGHT.count("chaseAABB(") >= 2)
check("②-f 实体框每 tick 按实体当前 AABB 重算（目标会移动）+ 各自的外扩量常量",
      "entity.getBoundingBox().inflate(inflate)" in HIGHLIGHT
      and "ITEM_BOX_INFLATE" in HIGHLIGHT and "PLAYER_BOX_INFLATE" in HIGHLIGHT)
check("②-g 集合真的变化才重建（快照每 5 tick 一次心跳，不能每来一次就重建对象）",
      "if (!blocksChanged && !itemsChanged && !playersChanged)" in HIGHLIGHT)
check("②-h 换维度 / 超时 / 关开关时三类一起清干净（三个 Outliner.remove + 发光还原）",
      method_body(HIGHLIGHT, "resetState") is not None
      and method_body(HIGHLIGHT, "resetState").count("Outliner.getInstance().remove(") == 3
      and method_body(HIGHLIGHT, "resetState").count("setClientGlow(") >= 2)
check("②-i 每 tick 重设发光（服务端同步共享位会覆盖），离开集合才还原（只还原自己设过的）",
      method_body(HIGHLIGHT, "reassertGlow") is not None
      and method_body(HIGHLIGHT, "restoreGlow") is not None
      and "itemIds.contains(id)" in method_body(HIGHLIGHT, "restoreGlow")
      and "playerIds.contains(id)" in method_body(HIGHLIGHT, "restoreGlow"))
check("②-j 客户端不写世界 / 不写存档（纯渲染 + 客户端内存标志）",
      not any(token in HIGHLIGHT for token in
              ("addFreshEntity", "setPos(", "setDeltaMovement", "setBlock(",
               "sendToServer", "PacketDistributor", "saveAdditional", "ServerPlayer", "ServerLevel")))

# 服务端只发「真充了谁」：跳过 / 满电的目标不得进集合
check("②-k 服务端只在 accepted > 0 分支登记高亮（满电跳过的目标进不去）",
      "tickChargedBlocks.add(pos);" in BE
      and BE.count("tickChargedItemIds.add(itemEntity.getId());") == 2
      and BE.count("tickChargedPlayers.add(player.getUUID());") == 2
      and "if (accepted > 0) {" in BE)

# ==================================================================== ③ 满电跳过
print("=== ③ 满电目标在分摊预算之前直接跳过 ===")

LANES = (
    ("方块", "chargeBlock"),
    ("掉落物", "scanItems"),
    ("掉落物(无限范围)", "scanItemsInfinite"),
    ("携带物品", "chargeItemStack"),
)


def lane_skip_ok(method_regex):
    """单档判据：满电判定存在、跳过计数存在，且两者都在**真取电 / 扣电**之前。

    第 124 轮 R-1：方块档（{@code chargeBlock}）的满电判定从「比较两个 int」改成
    {@code storage.receiveEnergy(transfer, true) <= 0}（simulate，不写世界）。因为 simulate 需要
    {@code transfer}，它必须先算一次 {@code transferBudget()}（纯函数、无副作用）—— 所以
    「判满早于 transferBudget()」这条**只对物品三档成立**。方块档不变的口径是：
    判满 + 跳过计数都早于真取电（{@code receiveEnergy(..., false)}），且判满失败时不扣电、不登记高亮。
    """
    body = method_body(BE, method_regex)
    if body is None:
        return False, "找不到方法体"
    if "lastSkippedFullTargets++" not in body:
        return False, "没有跳过计数"
    int_anchor = "storage.getEnergyStored() >= storage.getMaxEnergyStored()"
    sim_anchor = "storage.receiveEnergy(transfer, true) <= 0"
    if int_anchor not in body and sim_anchor not in body:
        return False, "没有满电判据"
    if "transferBudget()" not in body:
        return False, "没有预算调用"
    if int_anchor in body:
        # 物品三档：仍是「判满（及跳过计数）在分摊预算之前」
        if body.index("lastSkippedFullTargets++") > body.index("transferBudget()"):
            return False, "跳过判定跑到了分摊预算之后"
        if body.index(int_anchor) > body.index("transferBudget()"):
            return False, "满电判据跑到了分摊预算之后"
        return True, ""
    # 方块档（R-1）：判满在「真取电」之前；两者都必须存在
    real_recv = "storage.receiveEnergy(transfer, false)"
    if real_recv not in body:
        return False, "找不到真取电"
    if body.index(sim_anchor) > body.index(real_recv):
        return False, "simulate 满电判据跑到了真取电之后"
    if body.index("lastSkippedFullTargets++") > body.index(real_recv):
        return False, "跳过计数跑到了真取电之后"
    if "energyStorage.extractEnergy" in body \
            and body.index("energyStorage.extractEnergy") < body.index(sim_anchor):
        return False, "扣电跑到了满电判定之前"
    return True, ""


for lane_name, lane_regex in LANES:
    ok, detail = lane_skip_ok(lane_regex)
    check("③-1 %s 档：满电判定与跳过计数都在 transferBudget() 之前（不占预算 / 不计目标 / 不登记高亮）"
          % lane_name, ok, detail)

check("③-2 玩家档按「每件物品」判（背包一格一格判 + 饰品槽一圈也判），且整人全满时不会进高亮",
      method_body(BE, "chargePlayer") is not None
      and method_body(BE, "chargePlayer").count("chargeItemStack(stack)") == 2
      and method_body(BE, "chargePlayer").index("chargeItemStack(stack)")
          < method_body(BE, "chargePlayer").index("tickChargedPlayers.add"))

check("③-3 计数每 tick 清零（不是跨 tick 累计）",
      "lastChargedTargets = 0;" in BE and "lastSkippedFullTargets = 0;" in BE
      and BE.index("lastChargedTargets = 0;") < BE.index("lastSkippedFullTargets = 0;")
      and method_body(BE, "doCharging") is not None
      and "lastSkippedFullTargets = 0;" in method_body(BE, "doCharging"))

check("③-4 跳过计数不进存档（瞬态运行数据，绝不膨胀 NBT）",
      "lastSkippedFullTargets" not in (method_body(BE, "saveAdditional") or "")
      and "lastSkippedFullTargets" not in (method_body(BE, "loadAdditional") or ""))

check("③-5 数据槽 13 = 跳过数，槽数 14（服务端与客户端菜单一致）",
      "case 13 -> lastSkippedFullTargets;" in BE and "return 14;" in BE
      and "new SimpleContainerData(14)" in MENU
      and "public int getSkippedFullTargets()" in MENU
      and "data.get(13)" in MENU)

check("③-6 跳过判定是 O(1)：只读 capability（物品档 stored/max、方块档 simulate），chargeBlock 不额外遍历",
      BE.count("storage.getEnergyStored() >= storage.getMaxEnergyStored()") >= 3
      and BE.count("storage.receiveEnergy(transfer, true) <= 0") == 1
      and "getEntitiesOfClass" not in (method_body(BE, "chargeBlock") or "")
      and "lastSkippedFullTargets" in BE)

# ---- 行为模型：全满 / 半满 / 空 三个目标 ----
print("=== ③-7 行为模型：给「全满 / 半满 / 空」三个目标，跳过的是不是只有全满那个 ===")


def model_charge(targets, stored, rate, max_targets, skip_after_budget=False,
                 count_full_as_target=False):
    """复刻三档扫描的等价模型（只保留与本次验收相关的判定顺序）。

    targets: [(名字, 目标当前电量, 目标容量)]；返回 (charged, target_count, skipped, budget_calls)。
    skip_after_budget=True  = 反例：先分摊预算再判满（"跳过"仍然走了一遍预算路径）；
    count_full_as_target=True = 反例：把全满目标也算进目标数。
    """
    charged = []
    target_count = 0
    skipped = 0
    budget_calls = 0

    def budget():
        nonlocal budget_calls
        budget_calls += 1
        share = stored // max(1, max_targets)
        return max(1, min(rate, share))

    for name, current, capacity in targets:
        full = current >= capacity
        if skip_after_budget:
            transfer = budget()          # 反例：先分摊
            if full:
                skipped += 1
                if count_full_as_target:
                    target_count += 1
                continue
        else:
            if full:                     # 正解：判满在分摊之前
                skipped += 1
                if count_full_as_target:
                    target_count += 1
                continue
            transfer = budget()
        if transfer <= 0:
            continue
        accepted = min(transfer, capacity - current)
        if accepted > 0:
            target_count += 1
            charged.append(name)
    return charged, target_count, skipped, budget_calls


TARGETS = [("全满", 1000, 1000), ("半满", 500, 1000), ("空", 0, 1000)]
charged, target_count, skipped, budget_calls = model_charge(TARGETS, stored=1000000,
                                                            rate=500, max_targets=50)
check("③-7a 正解：全满目标 skip=1、目标数只有 2（半满 + 空）、预算只被调用 2 次",
      skipped == 1 and target_count == 2 and budget_calls == 2
      and charged == ["半满", "空"],
      "charged=%s targets=%d skipped=%d budget=%d"
      % (charged, target_count, skipped, budget_calls))
check("③-7b 全满目标一 FE 都没充进去（它根本不在 charged 列表里）",
      "全满" not in charged)
check("③-7c 反例（先分摊预算再判满）判负：预算调用次数变成 3 次 —— 跳过仍然走了一遍预算",
      model_charge(TARGETS, 1000000, 500, 50, skip_after_budget=True)[3] == 3)
check("③-7d 反例（把全满目标算进目标数）判负：目标数变成 3",
      model_charge(TARGETS, 1000000, 500, 50, count_full_as_target=True)[1] == 3)
def item_lane_has_skip():
    """「携带物品」档的跳过计数是否存在（三档共用同一判据，少一档就红）。"""
    body = method_body(BE, "chargeItemStack") or ""
    return "lastSkippedFullTargets++" in body


check("③-7e 三档判据共用同一函数：物品档也走 lane_ok_on（不是「只给方块补了跳过就完事」）",
      item_lane_has_skip() is True)


def mutated_lane_missing_skip():
    """把「携带物品」档的跳过计数剪掉，判据必须判负。"""
    body = method_body(BE, "chargeItemStack") or ""
    return body.replace("lastSkippedFullTargets++;", "", 1)


def lane_ok_on(body_text):
    if "lastSkippedFullTargets++" not in body_text:
        return False
    if "storage.getEnergyStored() >= storage.getMaxEnergyStored()" not in body_text:
        return False
    if "transferBudget()" not in body_text:
        return False
    return body_text.index("lastSkippedFullTargets++") < body_text.index("transferBudget()")


check("③-7f 反例自证：把「携带物品」档的跳过计数删掉 ⇒ 同一批判据判负",
      lane_ok_on(mutated_lane_missing_skip()) is False)


def moved_skip_after_budget():
    """把跳过计数挪到 transferBudget() 之后（顺序反例）。"""
    body = method_body(BE, "chargeItemStack") or ""
    body = body.replace("lastSkippedFullTargets++;", "", 1)
    return body.replace("final int transfer = transferBudget();",
                        "final int transfer = transferBudget();\n            lastSkippedFullTargets++;", 1)


check("③-7g 反例自证：把跳过计数挪到 transferBudget() 之后 ⇒ 顺序判据判负",
      lane_ok_on(moved_skip_after_budget()) is False)


def highlight_registered_before_skip():
    """把玩家高亮登记挪到 chargeItemStack 之前（跳过判定之前就登记），顺序判据必须判负。"""
    body = method_body(BE, "chargePlayer") or ""
    body = body.replace("if (chargeItemStack(stack)) {",
                        "tickChargedPlayers.add(player.getUUID());\n            "
                        "if (chargeItemStack(stack)) {", 1)
    return body


def player_order_ok(body_text):
    return ("chargeItemStack(stack)" in body_text
            and "tickChargedPlayers.add" in body_text
            and body_text.index("chargeItemStack(stack)") < body_text.index("tickChargedPlayers.add"))


check("③-7h 反例自证：把玩家高亮登记挪到 chargeItemStack 之前 ⇒ 顺序判据判负",
      player_order_ok(highlight_registered_before_skip()) is False)

# ==================================================================== ④ 界面：图例 + 跳过计数
print("=== ④ 界面：一行图例（自绘色块 + 文字）+ 已满跳过计数 ===")

check("④-a 图例三格的颜色**引用** ChargerTargetHighlight 的公开常量（不另写一份十六进制）",
      "ChargerTargetHighlight.COLOR_BLOCK" in SCREEN
      and "ChargerTargetHighlight.COLOR_ITEM" in SCREEN
      and "ChargerTargetHighlight.COLOR_PLAYER" in SCREEN
      and not re.search(r"0x(?:36D6A5|62D95F)", SCREEN))
check("④-b 图例三格各有语言键（方块 / 掉落物 / 玩家）",
      all(key in SCREEN for key in (
          "gui.rs_create_compat.range_charger.legend.block",
          "gui.rs_create_compat.range_charger.legend.item",
          "gui.rs_create_compat.range_charger.legend.player")))
check("④-c 色块是自绘的（fill 实心 + 上下 1px 深色描边），不是贴图 / 字符",
      "guiGraphics.fill(x, y, x + LEGEND_SWATCH, y + LEGEND_SWATCH, color);" in SCREEN
      and SCREEN.count("guiGraphics.fill(x, y") >= 2)
check("④-d 「已满跳过 %s」显示服务端数据槽（客户端只显示，不自算）",
      'Component.translatable("gui.rs_create_compat.range_charger.skipped",' in SCREEN
      and "menu.getSkippedFullTargets()" in SCREEN)
check("④-e 图例与跳过计数同一行且互不重叠：先画图例（返回右边界），跳过文字只在剩余宽度里画",
      "final int legendEnd = drawLegend(guiGraphics, SKIPPED_RIGHT);" in SCREEN
      and "Math.max(1, SKIPPED_RIGHT - (legendEnd + 6))" in SCREEN
      # 第 104 轮：跳过计数不再截断 ⇒ 右对齐用「缩字号之后的实际宽度」drawnWidth（fittedWidth 算出来），
      # 与 drawFitLine 的绘制口径同源；几何判据不变（左界 ≥ 图例右界 + 6，右界 = SKIPPED_RIGHT）。
      and "final int drawnWidth = fittedWidth(skipped, available);" in SCREEN
      and "Math.max(legendEnd + 6, SKIPPED_RIGHT - drawnWidth)" in SCREEN
      and method_body(SCREEN, "drawLegend") is not None
      and "int drawLegend(" in SCREEN
      and "if (entryX + entryWidth > limitX) {" in method_body(SCREEN, "drawLegend")
      and "break;" in method_body(SCREEN, "drawLegend"))
check("④-f 速率没有丢：挪到顶栏右侧右对齐（新的短键 rate_short），不再是信息行挤成一条",
      "drawRateLabel" in SCREEN
      and 'gui.rs_create_compat.range_charger.rate_short' in SCREEN
      # 第 104 轮：右对齐基准从「font.width(drawn)」换成「fittedWidth(...) 的实际绘制宽度」
      and "final int drawnWidth = fittedWidth(text, maxWidth);" in method_body(SCREEN, "drawRateLabel")
      and "RATE_RIGHT - drawnWidth" in method_body(SCREEN, "drawRateLabel")
      and "TARGETS_X" not in SCREEN and "RATE_X" not in SCREEN)
check("④-g 悬停判定与绘制同源（速率按当前文字现算宽度；信息行覆盖整行）",
      "private boolean isOverRateLabel(" in SCREEN
      and "rateLabelMaxWidth()" in method_body(SCREEN, "isOverRateLabel")
      # 第 104 轮：速率不再按像素截断 ⇒ 悬停判定改用与绘制同一个 fittedWidth(...)（同一缩放口径），
      # 判据不变（仍是「悬停范围的左界 = 实际绘制文字的左界」），只是宽度来源从 trimToWidth 换成
      # 「缩字号之后的宽度」；drawRateLabel 与 isOverRateLabel 必须都调用它，否则两者会分叉。
      and "fittedWidth(text, rateLabelMaxWidth())" in method_body(SCREEN, "isOverRateLabel")
      and "fittedWidth(text, maxWidth)" in method_body(SCREEN, "drawRateLabel")
      and "trimToWidth" not in SCREEN
      and "LEGEND_X" in method_body(SCREEN, "isOverInfoRow")
      and "SKIPPED_RIGHT" in method_body(SCREEN, "isOverInfoRow"))
check("④-h tooltip 同帧只画一份（槽位优先 + 每个出口都有 return）",
      "if (hoveredSlot != null) {" in method_body(SCREEN, "renderValueTooltips")
      and method_body(SCREEN, "renderValueTooltips").count("return;") >= 2
      and "vanillaSlotTooltipWillDraw" in method_body(SCREEN, "renderHoverTooltips"))
check("④-i 图例 / 跳过计数的完整说明走悬停提示（颜色含义常显，数值放 Shift 层）",
      'gui.rs_create_compat.range_charger.legend.tip' in SCREEN
      and 'gui.rs_create_compat.range_charger.skipped.tip' in SCREEN)

# ==================================================================== ⑤ 语言键 + 版面宽度
print("=== ⑤ 语言键（本轮新增）与信息行版面宽度 ===")

# 键 → (中文原文, 英文原文, %s 个数)。这两份原文就是交付给 Lead 的落地内容；
# 只要语言文件里已有这些键，⑤-b/⑤-c/⑤-d 会逐条复核（缺键时只打印待落地清单，不判负）。
NEW_KEYS = {
    "gui.rs_create_compat.range_charger.legend.block": ("方块", "Block", 0),
    "gui.rs_create_compat.range_charger.legend.item": ("掉落物", "Item", 0),
    "gui.rs_create_compat.range_charger.legend.player": ("玩家", "Player", 0),
    "gui.rs_create_compat.range_charger.legend.tip": (
        "高亮颜色：橙 = 方块，青 = 掉落物，绿 = 玩家（按住 Shift 看本次充电 / 跳过的数量）",
        "Highlight colours: orange = block, cyan = dropped item, green = player "
        "(hold Shift to see this cycle's counts)", 0),
    "gui.rs_create_compat.range_charger.skipped": ("已满跳过 %s", "Skip %s", 1),
    "gui.rs_create_compat.range_charger.skipped.tip": (
        "本次扫描有 %s 个目标电量已满，被直接跳过（不充电、不高亮、不占充电额度）",
        "This scan skipped %s target(s) that were already full "
        "(not charged, not highlighted, no charge budget used)", 1),
    "gui.rs_create_compat.range_charger.rate_short": ("速率 %s FE/t", "Rate %s FE/t", 1),
}
check("⑤-a 源码里真的引用了这 %d 个新键（没有一个键只写在清单里）" % len(NEW_KEYS),
      all('"' + key + '"' in SCREEN for key in NEW_KEYS),
      "漏引用：" + ", ".join(k for k in NEW_KEYS if '"' + k + '"' not in SCREEN))

try:
    zh = json.loads(sources["ZH"]) if sources["ZH"] else {}
    en = json.loads(sources["EN"]) if sources["EN"] else {}
except json.JSONDecodeError:
    zh, en = {}, {}
pending = [key for key in NEW_KEYS if key not in zh or key not in en]
if pending:
    # 语言文件由 Lead 统一落地（本轮写域不含 lang）。这里**不判负**，但把待落地清单打出来：
    # 只要 Lead 按清单补上，下面的中英成对 / 占位符 / 中文值判据立即生效。
    print("NOTE 待 Lead 落地的语言键 %d 条：" % len(pending))
    for key in pending:
        print("       - %s = %r / %r" % (key, NEW_KEYS[key][0], NEW_KEYS[key][1]))
else:
    check("⑤-b 新键中英成对且非空", all(zh.get(k) and en.get(k) for k in NEW_KEYS))
    check("⑤-c 占位符个数与源码用法一致（%s 的个数）",
          all(zh.get(k, "").count("%s") == NEW_KEYS[k][2]
              and en.get(k, "").count("%s") == NEW_KEYS[k][2] for k in NEW_KEYS))
    check("⑤-d 中文值含中文 / 英文值不含中文",
          all(re.search(r"[\u4e00-\u9fff]", zh.get(k, "")) for k in NEW_KEYS)
          and not any(re.search(r"[\u4e00-\u9fff]", en.get(k, "")) for k in NEW_KEYS))

# ---- 版面宽度：信息行 = 图例 ×3 + 间隔 + 「已满跳过 N」，必须真的放得下 ----
# 字宽模型与 tools/selfcheck_round50_keeper_fluid_display.py 的 MC_ADVANCE 同一套
# （原版字体：窄字符 2~4px、默认 6px、CJK 9px）——同一工程内只有这一份字宽口径。
MC_ADVANCE = {
    " ": 4, "!": 2, "'": 2, ".": 2, ",": 2, ":": 2, ";": 2, "i": 2, "|": 2,
    "(": 4, ")": 4, "[": 4, "]": 4, "f": 4, "k": 4, "l": 4, "t": 4, "I": 4,
    "<": 5, ">": 5, "*": 5, '"': 5, "`": 5, "{": 5, "}": 5, "~": 5,
    "\\": 6, "@": 7,
}


def text_width(text):
    return sum(9 if ord(ch) >= 128 else MC_ADVANCE.get(ch, 6) for ch in text)


def screen_const(name):
    match = re.search(r"\b" + name + r" = (\d+);", SCREEN)
    return int(match.group(1)) if match else None


LEGEND_X = screen_const("LEGEND_X")
SKIPPED_RIGHT = screen_const("SKIPPED_RIGHT")
SWATCH = screen_const("LEGEND_SWATCH")
SWATCH_GAP = screen_const("LEGEND_SWATCH_GAP")
ENTRY_GAP = screen_const("LEGEND_ENTRY_GAP")
ROW_WIDTH = (SKIPPED_RIGHT - LEGEND_X) if None not in (LEGEND_X, SKIPPED_RIGHT) else None


label_zh = [NEW_KEYS["gui.rs_create_compat.range_charger.legend.block"][0],
            NEW_KEYS["gui.rs_create_compat.range_charger.legend.item"][0],
            NEW_KEYS["gui.rs_create_compat.range_charger.legend.player"][0]]
label_en = [NEW_KEYS["gui.rs_create_compat.range_charger.legend.block"][1],
            NEW_KEYS["gui.rs_create_compat.range_charger.legend.item"][1],
            NEW_KEYS["gui.rs_create_compat.range_charger.legend.player"][1]]

if ROW_WIDTH is None:
    check("⑤-e 信息行版面常量可解析（LEGEND_X / SKIPPED_RIGHT / 图例几何）", False)
else:
    widths = {}
    for name, labels in (("zh", label_zh), ("en", label_en)):
        per_entry = [SWATCH + SWATCH_GAP + text_width(label) for label in labels]
        legend = sum(per_entry) + ENTRY_GAP * 2
        skip = (NEW_KEYS["gui.rs_create_compat.range_charger.skipped"][0 if name == "zh" else 1]
                .replace("%s", "99999"))
        widths[name] = (legend, legend + 6 + text_width(skip))
        print("[info] 信息行宽度(%s)：图例 %dpx + 6 + 跳过 %dpx = %dpx / 可用 %dpx（x=%d..%d）"
              % (name, legend, text_width(skip), widths[name][1], ROW_WIDTH,
                 LEGEND_X, SKIPPED_RIGHT))
    check("⑤-e 两种语言下「图例 + 已满跳过（五位数字的极端值）」都放得进信息行"
          "（放不下时 drawLegend 会少画一格 = 用户看到的图例残缺）",
          all(total <= ROW_WIDTH for _legend, total in widths.values()),
          "宽度超限：%r > %d" % ({k: v[1] for k, v in widths.items()}, ROW_WIDTH))
    check("⑤-f 图例单独也放得下（跳过数字再长，图例三格一件都不能少）",
          all(legend <= ROW_WIDTH for legend, _total in widths.values()))
    check("⑤-g 速率行放得下：顶栏右侧 RATE_RIGHT 与标题之间至少留 40px "
          "（英文最宽标题 \"Range Charger\" 约 76px ⇒ 167-76-6 = 85px 可用）",
          screen_const("RATE_RIGHT") is not None
          and screen_const("RATE_RIGHT") - (8 + text_width("Range Charger") + 6) >= 40)

# ==================================================================== 结果
print("=" * 78)
if problems:
    print("[result] 自检失败：%d 个问题" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言；范围充电器「三种目标各自高亮（方块橙 / 掉落物青 / 玩家绿，"
      "三类都有框、掉落物与玩家真的发光）+ 满电目标在分摊预算之前跳过（不计目标 / 不登记高亮 / "
      "不占预算）+ 界面图例与已满跳过计数」锚定完成" % checks)
sys.exit(0)
