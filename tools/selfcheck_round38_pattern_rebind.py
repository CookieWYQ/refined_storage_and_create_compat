# -*- coding: utf-8 -*-
"""自检（第 38 轮 · 总样板「改绑机器」）：已做好的总样板可以就地改机器，且不留悬空引用。

用法：python tools/selfcheck_round38_pattern_rebind.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户现场（需求）
----------------
用户的模型：**单元样板不绑定机器（想放哪就放哪），总样板必须绑定机器**
（同一种配方类型可能有多台机器持有，必须明确是哪一台）。痛点是
「每一次都在做一个样板太麻烦了，我想着能不能在制作好的样板上进行更改」。

决定性的技术事实（本自检的第一条断言）
--------------------------------------
机器指派存在**物品一侧**：`SequencePatternData` 的 `MachinePos` / `MachineName`
（`UnitEntry#machinePos()/machineName()`），而执行舱的 `unitSlots` 里放的是**单元样板**
（配方 id + 步序 + 配方类型），它决定「哪台机器**拥有**这一步」。
两边是两份数据 ⇒ 改绑必须**两侧一起改**，只改一边就是产线静默停摆。

本自检钉住八件事
----------------
K1 触发在物品侧：**右键空气**（非 Shift）打开界面，Shift（还原成空白样板）语义一字未动，
   而**已被用户否定的「右键方块」入口不再存在**（它在 1.21.1 里是死代码：见 round42 的根因说明）；
K2 触发时客户端也返回 SUCCESS（有反馈，且不预测打开别的界面）；
K3 C2S 一定走服务端主线程（`enqueueWork`），客户端不写任何权威状态；
K4 「拒绝」一律发生在「写入」之前：找不到该步的单元样板 ⇒ 不写 machinePos（不留悬空引用），
   槽位不足 ⇒ 在搬移之前就拒绝（绝不先删后放不下）；
K5 一步只有一个属主：同网络里其它持有点上的同一步样板被收走，目标（含同链成员）已持有则不再插第二张；
K6 重写样板时把候选组 / 输入流体 / 主原料候选全部带过去（漏掉会静默把「铁粒/锌粒」退化成「只要铁粒」）；
K7 不新造第二套事实源：网络遍历复用 `UnitManagerSources`、网络解析复用 `UnitPatternDedupe`，
   链身份复用 `chainMembers()`，并且**不**把逻辑塞进执行舱方块实体 / 看门狗（他人并行修改的两处文件）；
K8 界面复用既有的机器选择子界面（`StepMachineSelectScreen` + confirmSink），不写第二套选择器；
   文案键中英成对、中文 ≤40 字、不含禁用措辞。
"""

from __future__ import annotations

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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
PREFIX = "gui.rs_create_compat.assembly_pattern_rebind."

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source):
    """去掉 /* */ 与 // 注释：断言「代码里没有某样东西」时不能被注释里的字面量干扰。"""
    out = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith("/*", index):
            stop = source.find("*/", index + 2)
            index = length if stop < 0 else stop + 2
            continue
        if source.startswith("//", index):
            stop = source.find("\n", index)
            index = length if stop < 0 else stop
            continue
        out.append(source[index])
        index += 1
    return "".join(out)


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def body(source, anchor, end=None):
    """取 anchor 之后的片段（到 end 或文件末尾）：用于「某方法体内」的断言。"""
    index = source.find(anchor)
    if index < 0:
        return ""
    index += len(anchor)
    rest = source[index:]
    if end is not None:
        stop = rest.find(end)
        if stop >= 0:
            return rest[:stop]
    return rest


# ======================================================================
# 与 Java 逐字同构的模型：apply() 里的「迁移决策」（先判后写）
# ======================================================================
def plan(holders, chain, has_empty_slot):
    """AssemblyPatternRebind#apply 的迁移决策复刻。

    holders / chain 都用坐标字符串表示；返回 (verdict, moved_to_clear, insert_copy)。
    verdict ∈ {"refuse:no_unit", "refuse:no_slot", "apply"}。
    """
    if not holders:
        return ("refuse:no_unit", [], False)          # 找不到该步单元样板 ⇒ 绝不写 machinePos
    target_holds = any(h in chain for h in holders)   # 目标自己 / 同链成员都算「这台机器持有」
    if not target_holds and not has_empty_slot:
        return ("refuse:no_slot", [], False)          # 先判位置，再动任何东西
    moved = [h for h in holders if h not in chain]    # 其它机器上的同一步样板一律收走（一步一个属主）
    insert = (not target_holds) and bool(moved)       # 目标已持有 ⇒ 不再插第二张
    return ("apply", moved, insert)


def main():
    item = read(SRC, "item", "SequenceAssemblyPatternItem.java")
    rebind = read(SRC, "item", "AssemblyPatternRebind.java")
    step_packet = read(SRC, "network", "AssemblyPatternStepMachinePacket.java")
    open_packet = read(SRC, "network", "AssemblyPatternRebindOpenPacket.java")
    screen = read(SRC, "client", "screen", "AssemblyPatternRebindScreen.java")
    client = read(SRC, "client", "AssemblyPatternRebindClient.java")
    root = read(SRC, "RS_Create_Compat.java")
    data = read(SRC, "data", "SequencePatternData.java")
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    watchdog = read(SRC, "support", "AssemblyWatchdog.java")

    # ---------------- K1 触发位置与 Shift 语义 ----------------
    section("K1 触发在物品侧（进入 use 的右键空气）；Shift（还原成空白样板）一字未动")
    use_on = body(item, "public net.minecraft.world.InteractionResult useOn(", "// ---------- RS PatternProviderItem")
    use_air = body(item, "public net.minecraft.world.InteractionResultHolder<ItemStack> use(",
                   "public net.minecraft.world.InteractionResult useOn(")
    check("锚点① 绑定事实仍只在物品一侧（MachinePos / MachineName 是唯一写入键）",
          "TAG_MACHINE_POS = \"MachinePos\"" in data and "TAG_MACHINE_NAME = \"MachineName\"" in data
          and "unitTag.putLong(TAG_MACHINE_POS" in data)
    check("锚点② useOn 里 Shift 分支仍原样调用 SampleItemConversions.useOn（还原语义不变）",
          "if (player.isShiftKeyDown()) {" in use_on
          and "return SampleItemConversions.useOn(context.getLevel(), player, context.getHand());" in use_on)
    # 2026-10-06 用户拍板：改绑入口从「右键方块」移到「右键空气」。
    # 旧锚点③（「只有目标方块是执行舱才接管」）固化的正是用户已否定的那条入口 ——
    # 它在 1.21.1 里本来就是死代码（BlockState#useWithoutItem 会先消费掉动作，
    # 见 SequenceExecutionChamberBlock#useWithoutItem），因此改为断言「方块路径不再接管任何东西」。
    check("锚点③ useOn 已不再有「改绑机器」入口：只剩 Shift 还原，其余方块一律 PASS（用户已否定的右键方块入口已移除）",
          "AssemblyPatternRebind" not in use_on
          and "SequenceExecutionChamberBlock" not in use_on
          and "return net.minecraft.world.InteractionResult.PASS;" in use_on)
    check("锚点③·2 触发改为右键空气（非 Shift）⇒ 调 openFor 打开界面，Shift 仍走 SampleItemConversions.use",
          "AssemblyPatternRebind.openFor(serverPlayer, hand);" in use_air
          and "if (player.isShiftKeyDown()) {" in use_air
          and "return SampleItemConversions.use(level, player, hand);" in use_air)
    check("锚点④ 右键只负责「打开」：打开路径不含任何写样板 / 写单元槽的调用",
          "writeAssembly" not in rebind.split("public static void apply(")[0]
          and "unitSlots.setItem" not in rebind.split("public static void apply(")[0])

    # ---------------- K2 客户端也要 SUCCESS ----------------
    section("K2 触发时客户端同样返回 SUCCESS（有反馈且不预测打开别的界面）")
    check("锚点① 返回的是 sidedSuccess（两侧都「消费」这次交互）",
          "net.minecraft.world.InteractionResultHolder.sidedSuccess(" in use_air
          and "player.getItemInHand(hand), level.isClientSide());" in use_air)
    check("锚点② 客户端入口按「已在改绑界面 ⇒ 只刷新」「已在执行舱界面 ⇒ 当父界面」处置",
          "current instanceof AssemblyPatternRebindScreen screen" in client
          and "screen.update(packet);" in client
          and "current instanceof SequenceExecutionChamberScreen" in client
          and "minecraft.setScreen(new AssemblyPatternRebindScreen(packet, current));" in client)

    # ---------------- K3 服务端权威 ----------------
    section("K3 服务端权威：C2S 走主线程，客户端只发请求")
    check("锚点① C2S 处理体只委托给服务端实现（客户端没有第二条写入路径）",
          "cretae.cookiewyq.rs_create_compat.item.AssemblyPatternRebind.apply(player, packet);" in step_packet)
    check("锚点② 注册处用 enqueueWork（写容器 / 写物品栈必须在服务端主线程）",
          "AssemblyPatternStepMachinePacket.STREAM_CODEC," in root
          and "ctx.enqueueWork(() ->" in root
          and "AssemblyPatternStepMachinePacket.handle(" in root)
    check("锚点③ S2C 只发快照（stepEntries / chamberEntries 都是只读构造）",
          "private static List<AssemblyPatternRebindOpenPacket.Step> stepEntries(" in rebind
          and "private static List<SyncChamberListPacket.Entry> chamberEntries(" in rebind)
    check("锚点④ 写入前先校验指纹（界面开着时换掉样板 ⇒ 拒绝，绝不改到别的样板上）",
          "!current.equals(packet.patternId())" in rebind
          and "Component.translatable(LANG + \"stale\")" in rebind
          and "pattern.getId(stack)" in rebind)

    # ---------------- K4 先判后写 ----------------
    section("K4 拒绝一律发生在写入之前（不留悬空引用 / 不做先删后放不下）")
    apply_body = body(rebind, "public static void apply(")
    check("锚点① 找不到该步单元样板 ⇒ 直接拒绝（no_unit），before writeAssembly",
          apply_body.find("LANG + \"no_unit\"") >= 0
          and apply_body.find("LANG + \"no_unit\"") < apply_body.find("SequencePatternData.writeAssembly("))
    check("锚点② 槽位不足 ⇒ 在搬移之前拒绝（no_slot 早于 unitSlots.setItem / writeAssembly）",
          apply_body.find("LANG + \"no_slot\"") >= 0
          and apply_body.find("LANG + \"no_slot\"") < apply_body.find("holder.unitSlots.setItem(")
          and apply_body.find("LANG + \"no_slot\"") < apply_body.find("SequencePatternData.writeAssembly("))
    check("锚点③ 类型不符 / 缺配方类型 / 缺步序 / 未接网络也全部在写入之前拒绝",
          apply_body.find("LANG + \"mismatch\"") < apply_body.find("SequencePatternData.writeAssembly(")
          and apply_body.find("LANG + \"no_recipe_type\"") < apply_body.find("SequencePatternData.writeAssembly(")
          and apply_body.find("LANG + \"no_unit_step\"") < apply_body.find("SequencePatternData.writeAssembly(")
          and apply_body.find("LANG + \"no_network\"") < apply_body.find("SequencePatternData.writeAssembly("))
    check("锚点④ 原地改写同一件物品：copyWithCount(held.getCount()) + setItemInHand（不复制不销毁）",
          "final ItemStack replacement = held.copyWithCount(held.getCount());" in apply_body
          and "player.setItemInHand(hand, replacement);" in apply_body)

    # ---------------- K5 一步只有一个属主 ----------------
    section("K5 一步只有一个属主（迁移 + 去重，与 UnitPatternDedupe 口径一致）")
    check("锚点① 同链成员算「这台机器已经持有」（链 = 一个逻辑执行仓）",
          "final List<SequenceExecutionChamberBlockEntity> chain = target.chainMembers();" in apply_body
          and "holders.stream().anyMatch(chain::contains)" in apply_body)
    check("锚点② 搬移时跳过同链成员，且目标已持有时不再插第二张",
          "if (chain.contains(holder)) {" in apply_body
          and "if (!targetHolds && !relocation.isEmpty()) {" in apply_body)
    check("锚点③ 一台只搬一张（同一步样板不会在别处残留第二份属主）",
          "moved++;" in apply_body and "break; // 一台只搬一张" in apply_body)

    # ---------------- K6 候选组 / 流体 / 主原料候选全带过去 ----------------
    section("K6 重写样板时把候选组 / 输入流体 / 主原料候选全部带过去")
    check("锚点① UnitEntry 用 11 参构造器，inputFluid 与 inputCandidates 都在",
          "unit.inputFluid(), unit.inputCandidates()));" in apply_body)
    check("锚点② AssemblyData 用 6 参构造器（ingredientCandidates 不能被吞掉）",
          "assembly.results(), assembly.scraps()," in apply_body
          and "assembly.ingredientCandidates());" in apply_body)
    check("锚点③ 要搬的那张单元样板在「清空源槽」之前就取到手（否则会删掉旧那张却什么都没搬过去）",
          apply_body.find("firstMatchingHolderStack(holders, registries, recipe, unit.step())") >= 0
          and apply_body.find("firstMatchingHolderStack(holders, registries, recipe, unit.step())")
          < apply_body.find("holder.unitSlots.setItem(slot, ItemStack.EMPTY)"))
    check("锚点④ 机器名不采信客户端：写入的是目标执行舱自己的显示名",
          "packet.name()" not in rebind
          and "target.getChamberDisplayName(), unit.recipeType()," in apply_body
          and "String name" not in step_packet)

    # ---------------- K7 不新造第二套事实源 ----------------
    section("K7 不新造第二套事实源；不把逻辑塞进他人并行改的两个文件")
    code = strip_comments(rebind)
    check("锚点① 网络遍历复用 UnitManagerSources.chambers（不自己走网络图）",
          "UnitManagerSources.chambers(network)" in code
          and "GraphNetworkComponent" not in code
          and "getContainers()" not in code)
    check("锚点② 网络解析复用 UnitPatternDedupe.networkOf（与去重/候选同一口径）：写入侧用目标机，打开侧用玩家身边最近那台",
          "UnitPatternDedupe.networkOf(target)" in code
          and "UnitPatternDedupe.networkOf(chamber)" in code
          and "private static Network nearbyNetwork(final ServerPlayer player) {" in code)
    check("锚点③ 链身份复用 chainMembers()（不新造链推导）",
          "chainMembers()" in code and "chainHead(" not in code)
    check("锚点④ 执行舱方块实体 / 看门狗里没有本功能的写入逻辑（并行修改空间不被侵犯）",
          "AssemblyPatternRebind" not in chamber and "AssemblyPatternRebind" not in watchdog)
    check("锚点⑤ 未新增「单元样板放置限制」：本功能只搬运，不碰 acceptsUnit 判定",
          "acceptsUnit" not in code and "isProperlyConfigured()" in code)

    # ---------------- K8 界面复用 + 文案 ----------------
    section("K8 界面复用既有选择器；文案键中英成对、中文 ≤40 字、无禁用措辞")
    check("锚点① 复用 StepMachineSelectScreen 并传入确认回调（不写第二套选择器）",
          "new StepMachineSelectScreen(this, stepIndex, step.recipeType(), candidates," in screen
          and "selected -> {" in screen
          and "new AssemblyPatternStepMachinePacket(" in screen)
    check("锚点② 界面按钮可用性三条件：选中步骤 + 有配方类型 + 有候选机器",
          "changeButton.active = step != null && !candidatesFor(step.recipeType()).isEmpty();" in screen)
    check("锚点③ 界面文字全部无阴影（drawString 末参 false）",
          "false);" in screen and "true);" not in screen.split("// ==================== 渲染")[1])

    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)
    zh_keys = set(key for key in zh if key.startswith(PREFIX))
    en_keys = set(key for key in en if key.startswith(PREFIX))
    check("锚点④ 中英键集合完全一致（且非空）",
          bool(zh_keys) and zh_keys == en_keys,
          "zh=%d en=%d" % (len(zh_keys), len(en_keys)))
    check("锚点⑤ 中文文案 ≤40 字",
          all(len(zh[key]) <= 40 for key in zh_keys),
          str([key for key in zh_keys if len(zh[key]) > 40]))
    forbidden = ("\u6216", "\u7b49 %s \u79cd", "\u56fe\u6807\u8f6e\u6362")
    check("锚点⑥ 中文文案不含禁用措辞（「或」/「等 N 种」/「图标轮换」）",
          not any(word in zh[key] for key in zh_keys for word in forbidden))
    check("锚点⑦ 代码里引用到的键都在语言文件里（界面 + 服务端反馈）",
          all((PREFIX + suffix) in zh for suffix in (
              "title", "picker", "picker.hint", "step", "steps", "steps.tip", "current", "current.none",
              "current.tip", "hint", "no_recipe_type", "no_candidate", "empty", "change", "change.tip",
              "close", "close.tip", "done", "done.unit", "empty_pattern", "no_pattern", "stale",
              "no_step", "bad_target", "mismatch", "no_unit_step", "no_network", "no_unit", "no_slot",
              "no_chamber", "no_chamber_near")))

    # ---------------- 迁移决策真值表（模型） ----------------
    section("迁移决策真值表（与 Java 同构：不留悬空引用 / 一步一个属主 / 去重）")
    check("① 同网络找不到该步单元样板 ⇒ 拒绝（不写 machinePos）",
          plan([], ["T"], True) == ("refuse:no_unit", [], False))
    check("② 槽位不足 ⇒ 先拒绝，且一个搬移都不做",
          plan(["A"], ["T"], False) == ("refuse:no_slot", [], False))
    check("③ 样板在旧机器上 ⇒ 搬进目标",
          plan(["A"], ["T"], True) == ("apply", ["A"], True))
    check("④ 目标已持有 ⇒ 不搬也不插第二张（去重）",
          plan(["T"], ["T"], True) == ("apply", [], False))
    check("⑤ 旧机器 + 目标都有 ⇒ 只收走旧机器那张，绝不新增重复张",
          plan(["A", "T"], ["T"], True) == ("apply", ["A"], False))
    check("⑥ 多台机器都放着同一步样板 ⇒ 全部收走，只留目标一份（一步一个属主）",
          plan(["C1", "C2"], ["T"], True) == ("apply", ["C1", "C2"], True))
    check("⑦ 目标所在链的成员持有 ⇒ 视为已持有（链 = 一个逻辑执行仓）",
          plan(["T1"], ["T1", "T2"], True) == ("apply", [], False))

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
