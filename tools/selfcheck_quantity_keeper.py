# -*- coding: utf-8 -*-
"""定量保持器「过量销毁」+「同网络多台共存」静态自检（只读源码 / 语言文件，不跑游戏）。

覆盖用户本轮反馈的四条里的三条（界面清理那条在 tmp_textures/verify_gui_layout.py）：

① 高级版：开关开 ⇒ 超量销毁；开关关 ⇒ 一个都不销毁
   —— 网络节点的销毁分支必须被 `isDestroyOverflow(slot)` 显式闸住；
      且销毁量由 `KeeperOverflow.excess(stored, target)` 决定（超出目标的那部分）。
② 基础版：同款语义
   —— 节点侧同样 `isDestroyOverflow()` 闸住；本机缓冲侧（tickStorage）也按同一语义
      销毁「目标 − 网络已有」之外的余量（否则满仓 / 堵塞时缓冲永远清不掉）。
④ 同网络多台共存（同资源确定仲裁、物品与流体分维度、不互相抢占）
   —— 两台都实现 KeeperCluster.Node，每 tick 先算「让位集合」，让位者不合成也不销毁；
      权威判据是全序（坐标 x→y→z → 机器类型 → 类名），与放置顺序 / tick 顺序无关。
⑤ 销毁精确性 + 可诊断日志
   —— excess 只在 stored > target 时为正；单次销毁 `min(excess, batch)`，绝不销毁到低于目标；
      只对「被标记的那一个资源键」操作；每次过量事件有 INFO 起止日志（`[rscc-keeper]`）。

用法：python tools/selfcheck_quantity_keeper.py
退出码：0 = 全部通过；1 = 有问题（逐条打印）。
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
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

BASIC_NODE = os.path.join(JAVA, "network", "QuantityKeeperNetworkNode.java")
ADV_NODE = os.path.join(JAVA, "network", "AdvancedQuantityKeeperNetworkNode.java")
BASIC_BE = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
CLUSTER = os.path.join(JAVA, "support", "KeeperCluster.java")
OVERFLOW = os.path.join(JAVA, "support", "KeeperOverflow.java")
TARGET = os.path.join(JAVA, "support", "KeeperTarget.java")
ADV_SCREEN = os.path.join(JAVA, "client", "screen", "AdvancedQuantityKeeperScreen.java")

problems = []


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def require(ok, message):
    if ok:
        print("  [OK] %s" % message)
    else:
        problems.append(message)
        print("  [X]  %s" % message)


def main():
    basic_node = read(BASIC_NODE)
    adv_node = read(ADV_NODE)
    basic_be = read(BASIC_BE)
    adv_be = read(ADV_BE)
    cluster = read(CLUSTER)
    overflow = read(OVERFLOW)
    target = read(TARGET)
    adv_screen = read(ADV_SCREEN)

    print("===== ⑤ 过量销毁语义（超出目标才销毁、绝不低于目标）=====")
    require("return Math.max(0L, stored - target);" in overflow,
            "KeeperOverflow.excess = max(0, stored - target)（只有超量才 > 0）")
    require("final long want = Math.min(excess, Math.max(1L, batch));" in overflow
            and "storage.extract(resource, want, Action.EXECUTE, Actor.EMPTY)" in overflow,
            "单次销毁量 = min(excess, batch)，且只对传入的那一个资源键 extract")
    require("Math.max(1, TICKS_PER_SECOND / Math.max(1, ratePerSecond))" in overflow,
            "销毁间隔 >= 1 tick（ratePerSecond 越大越快，绝不为 0）")
    require("Math.max(1L, Math.max(1, ratePerSecond) / (long) TICKS_PER_SECOND)" in overflow,
            "单批销毁 >= 1（低速率也会逐个清完，不会卡死）")
    require("storage == null || resource == null || excess <= 0L" in overflow,
            "excess <= 0 时直接返回 0（不销毁到低于目标）")
    require("final long pool = networkAfter + localLeft;" in overflow
            and "return Math.min(localLeft, Math.max(0L, pool - target));" in overflow,
            "本机侧销毁量被 min(本机余量, max(0, 池 − 目标)) 夹住 ⇒ 构造上绝不低于目标")

    print("===== ⑤ 销毁日志（可诊断、不刷屏）=====")
    require('public static final String LOG_PREFIX = "[rscc-keeper]";' in overflow,
            "销毁日志统一前缀 [rscc-keeper]（便于检索）")
    require("LOGGER.info" in overflow and "LOGGER.debug" in overflow,
            "过量事件「开始 / 清完」各一条 INFO，逐步明细走 DEBUG")
    require("public void record(" in overflow and "public void idle()" in overflow,
            "记录器有 record（记账 + 日志）与 idle（无销毁时收尾，不留半截日志）")
    require("overflowLog.record(" in basic_be and "overflowLogs[slot].record(" in adv_be,
            "基础版 / 高级版的本机侧销毁都记账（有日志）")
    require("log.record(blockEntity.diagnosticLabel(" in basic_node
            and "log.record(blockEntity.diagnosticLabel(slot)" in adv_node,
            "基础版 / 高级版的网络侧销毁都记账（有日志）")

    print("===== ① 高级版：开关开 ⇒ 销毁，开关关 ⇒ 一个都不销毁 =====")
    require("excess <= 0L || !blockEntity.isDestroyOverflow(slot)" in adv_node,
            "网络侧销毁分支被本槽 isDestroyOverflow(slot) 显式闸住")
    require("KeeperOverflow.destroyFromNetwork(storage, resource, excess," in adv_node,
            "闸住之后才调用 KeeperOverflow.destroyFromNetwork（excess 精确传入）")
    require("log.idle();" in adv_node,
            "未开开关 / 已达标 / 让位时走 idle（不销毁、事件收尾）")
    require("KeeperOverflow.localDestroyAmount(net.get(resource), left, targets[slot])" in adv_be
            and "destroyOverflow[slot] && slotKey != null && !yielded.contains(slotKey)" in adv_be,
            "本机侧：只有开关打开且仲裁获胜才销毁，销毁量走 KeeperOverflow.localDestroyAmount")

    print("===== ② 基础版：同款语义（含本机缓冲侧销毁）=====")
    require("!blockEntity.isDestroyOverflow()" in basic_node,
            "网络侧销毁分支被 isDestroyOverflow() 显式闸住")
    require("KeeperOverflow.destroyFromNetwork(storage, resource, excess," in basic_node,
            "闸住之后才调用 KeeperOverflow.destroyFromNetwork（与高级版同一实现）")
    require("KeeperTarget.isMarked(targetAmount) && destroyOverflow && key != null && !yielded.contains(key);"
            in basic_be,
            "本机侧：未标记 / 开关关闭（或让位）时一个都不销毁，只做原样回流")
    require("KeeperOverflow.localDestroyAmount(net.get(resource), left, targetAmount)" in basic_be
            and "KeeperOverflow.excess(net.get(resource) + (left - destroy), targetAmount)" in basic_be,
            "本机侧销毁量走 KeeperOverflow.localDestroyAmount，且日志按「网络 + 本机余量」结算剩余超量")
    require("KeeperOverflow.localDestroyAmount(net.get(resource), left, targets[slot])" in adv_be
            and "KeeperOverflow.excess(net.get(resource) + (left - destroy), targets[slot])" in adv_be,
            "高级版本机侧同样按池结算（同段日志不会每 tick 重复触发）")
    require("KeeperOverflow.excess(storage.get(resource), target)" in basic_node,
            "基础版网络侧销毁后按 max(0, 网络存量 − 目标) 结算剩余超量（日志收尾判据）")

    print("===== ④ 同网络多台共存：确定仲裁 + 物品 / 流体分维度 =====")
    require("implements KeeperCluster.Node" in basic_node and "implements KeeperCluster.Node" in adv_node,
            "基础版与高级版节点都参与仲裁（都实现 KeeperCluster.Node）")
    require("KeeperCluster.yieldedResources(network, this)" in basic_node
            and "KeeperCluster.yieldedResources(network, this)" in adv_node,
            "两台都每 tick 先算「让位集合」")
    require("yielded.contains(resource)" in basic_node and "yielded.contains(resource)" in adv_node,
            "让位资源不合成也不销毁（只搬运，避免互相抢占）")
    require("graph.getContainers()" in cluster,
            "仲裁走 RS 网络图（GraphNetworkComponent.getContainers）枚举同网络节点")
    require("Integer.compare(a.getX(), b.getX())" in cluster
            and "Integer.compare(a.getY(), b.getY())" in cluster
            and "Integer.compare(a.getZ(), b.getZ())" in cluster,
            "权威判据第一步：方块坐标 x → y → z 字典序（与放置顺序无关）")
    require("a.arbitrationRank() != b.arbitrationRank()" in cluster
            and "a.getClass().getName().compareTo(b.getClass().getName()) < 0" in cluster,
            "权威判据二 / 三：机器类型 rank → 节点类名（全序，无「最后装的赢」）")
    require("if (mine.isEmpty() || network == null) {" in cluster,
            "未标记 / 无网络时不做仲裁（零开销、不会误伤）")
    require("List<ResourceKey> claimedResources();" in cluster
            and "ItemResource.ofItemStack(marker)" in basic_be
            and "new FluidResource(fluid, MarkerEntry.decodeComponents(fluidMarkerNbts[slot], registries))" in adv_be,
            "资源键维度：物品走 ItemResource、流体 / 气体走 FluidResource（两者永不相等 ⇒ 可同时控制）")
    require("public ResourceKey networkResourceKey()" in basic_be
            and "public ResourceKey networkResourceKey(final int slot)" in adv_be,
            "「入网用键」与「销毁 / 仲裁用键」同源（networkResourceKey 唯一入口）")

    print("===== ③ 高级版界面：已无「已存 / 目标」区域与文案 =====")
    for token in ("STORED_SPRITE_X", "STORED_MAX_W", "storedText", "isOverStored", "statusColor",
                  'LANG + "stored"', 'LANG + "target"'):
        require(token not in adv_screen, "屏幕源码不再出现 %s" % token)
    langs = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            langs[name] = json.load(handle)
    require("gui.rs_create_compat.advanced_quantity_keeper.stored" not in langs["zh_cn.json"]
            and "gui.rs_create_compat.advanced_quantity_keeper.stored" not in langs["en_us.json"],
            "语言键 advanced_quantity_keeper.stored 已删除（中英）")
    require("gui.rs_create_compat.advanced_quantity_keeper.target" not in langs["zh_cn.json"]
            and "gui.rs_create_compat.advanced_quantity_keeper.target" not in langs["en_us.json"],
            "语言键 advanced_quantity_keeper.target 已删除（中英）")

    print("===== ⑥ 生效目标钳制（下限 0 + 上限 min(设定, 可达上限)）—— 唯一实现 =====")
    require("public static long effectiveTarget(final long setTarget, final long stored, final boolean craftable)"
            in target,
            "生效目标由 KeeperTarget.effectiveTarget 唯一给出（基础版与高级版共用一处口径）")
    require("if (setTarget <= 0L) {" in target
            and "return 0L; // 下限 0：≤ 0 视为未标记" in target,
            "③ 下限 0：设定值 ≤ 0 视为「未标记」（零维持 / 零合成 / 零销毁 / 不计入缺料）")
    require("final long reachableCap = craftable ? Long.MAX_VALUE : Math.max(0L, stored);" in target,
            "①④ 不可自动合成 ⇒ 可达上限 = 网络当前持有量（最多就是现在这么多）")
    require("return Math.min(setTarget, reachableCap);" in target,
            "①② 上限 = min(设定值, 可达上限)")
    require("public static boolean isCraftable(" in target
            and "getPatternsByOutput(resource).isEmpty()" in target,
            "② 可自动合成 = 终端里有输出样板（getPatternsByOutput 非空）⇒ 不设上限，可补到设定值")
    require("if (!KeeperTarget.isMarked(setTarget)) {" in basic_node
            and "if (!KeeperTarget.isMarked(setTarget)) {" in adv_node,
            "③ 基础版与高级版都先用 isMarked 闸住未标记（0 / 负）⇒ 零维持 / 零合成 / 零销毁")

    print("===== ⑥ 基础版与高级版共用同一口径（同一实现被两处调用）=====")
    require("KeeperTarget.effectiveTarget(setTarget, stored," in basic_node
            and "KeeperTarget.isCraftable(autocrafting, resource));" in basic_node,
            "⑤ 基础版调用 KeeperTarget.effectiveTarget（唯一实现，非自有第二套）")
    require("KeeperTarget.effectiveTarget(setTarget, stored," in adv_node
            and "KeeperTarget.isCraftable(autocrafting, resource));" in adv_node,
            "⑤ 高级版调用同一个 KeeperTarget.effectiveTarget（同一处口径）")
    require("KeeperOverflow.excess(stored, target);" in basic_node
            and "excess <= 0L || !blockEntity.isDestroyOverflow(slot)" in adv_node,
            "④ 过量销毁仍只看 excess（= 存量 − 生效目标）+ 开关，既有语义不变")
    require("KeeperTarget.ClampObservation" in basic_node and "KeeperTarget.ClampObservation" in adv_node,
            "生效目标被钳制时留可诊断 INFO 日志（与「设定值」区分；观测器两版共用）")
    require("KeeperTarget.isMarked(targetAmount) && destroyOverflow" in basic_be
            and "KeeperTarget.isMarked(targets[slot])" in adv_be,
            "③④ 本机缓冲侧：未标记（0 / 负）时零销毁（只把缓冲原样回流进网络）")
    require("this.targetAmount = Math.max(0, targetAmount);" in basic_be
            and "targets[slot] = Math.max(0, value);" in adv_be,
            "③ 设定值下限放宽到 0（0 = 未标记），服务端权威保存且与设定值区分")

    print("=" * 60)
    if problems:
        for problem in problems:
            print("[X] %s" % problem)
    print("问题总数: %d" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
