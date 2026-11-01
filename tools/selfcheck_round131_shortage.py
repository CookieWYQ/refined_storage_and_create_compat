#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮 · F 组契约自检：发送量超过库存且可自动合成 ⇒ 询问 / 合成 / 补投。

规格来源
--------
`docs/ROUND131_CONTRACT.md` §0（用户原话：优先询问是否自动合成）、§3.F（五条硬判据）。

核心命题（每条都必须有断言钉住，且**反例必须有判别力**）
------------------------------------------------------
1. **先不发**：有缺口且该资源可自动合成 ⇒ 不发，回 S2C 询问；「无缺口 / 不可合成」沿旧路径发（回归）；
2. **最多可合成只来自 RS**：`PreviewProvider#getMaxAmount`（`AutocraftingNetworkComponent` 就是它），
   **不许自己遍历配方瞎猜**；且必须是有界等待 + 异常兜底（不抛到主线程、清中断位）；
3. **按钮语义**：合成 X 并发送 Y / 只发送 Y，两个数都取服务端的同一个口径（界面不算术）；
4. **补投**：合成异步 ⇒ 缺口登记 `PendingSenderSends`，服务端 tick「凑齐即投」，
   **10 分钟超时放弃并提示**（绝不无限挂起）；放弃的是计划，资源一件不丢；
5. 询问/回答都只走「服务端记着当时问的是什么」：客户端只回「按了哪个按钮」。

用法：python tools/selfcheck_round131_shortage.py
退出码：0 = F 组判据全过；1 = 有失败。最后一行：ALL_PASS / FAILED。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "lang")

PATHS = {
    "shortage": "support/SenderShortage.java",
    "pending": "support/PendingSenderSends.java",
    "delivery": "support/SenderDelivery.java",
    "be": "block/entity/NetworkResourceRequesterBlockEntity.java",
    "backend": "support/SenderBackend.java",
    "req_backend": "support/RequesterSenderBackend.java",
    "prompt_pkt": "network/SenderShortagePromptPacket.java",
    "reply_pkt": "network/SenderShortageReplyPacket.java",
    "prompt_screen": "client/screen/SenderCraftPromptScreen.java",
}

#: 补投超时 = 10 分钟（20 tick/s × 60 s × 10 min），用户原话。
EXPECTED_TIMEOUT_TICKS_EXPR = "20L * 60L * 10L"

FAILS = []
COUNTER_TOTAL = [0]
COUNTER_PASS = [0]
PENDING = []


# ======================================================================================
# 源码装载 / 工具
# ======================================================================================

def load_tree():
    tree = {}
    for key, rel in PATHS.items():
        path = os.path.join(ROOT, JAVA_REL, rel)
        tree[key] = read(path) if os.path.isfile(path) else None
    tree["lang_zh"] = read(os.path.join(ROOT, LANG_REL, "zh_cn.json"))
    tree["lang_en"] = read(os.path.join(ROOT, LANG_REL, "en_us.json"))
    return tree


def read(path):
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def body_of(text, signature_regex):
    """按「正则找头 + 括号配对」取方法体（不写死行号）；找不到 ⇒ None。"""
    if not text:
        return None
    match = re.search(signature_regex, text)
    if not match:
        return None
    index = text.find("{", match.end())
    if index < 0:
        return None
    depth = 0
    for pos in range(index, len(text)):
        char = text[pos]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[index + 1:pos]
    return None


def count(text, token):
    return 0 if not text else text.count(token)


def json_keys(text):
    if not text:
        return set()
    try:
        import json
        return set(json.loads(text).keys())
    except Exception:
        return set()


# ======================================================================================
# 判据
# ======================================================================================

def run_checks(tree):
    out = []

    def check(name, ok, detail=""):
        out.append((name, bool(ok), detail if not ok else ""))

    shortage = tree["shortage"] or ""
    pending = tree["pending"] or ""
    be = tree["be"] or ""
    prompt_pkt = tree["prompt_pkt"] or ""
    reply_pkt = tree["reply_pkt"] or ""
    screen = tree["prompt_screen"] or ""

    # ---------- F1：缺口怎么算（每一条都必须有出处） ----------
    scan = body_of(shortage, r"public\s+static\s+List<Gap>\s+scan\s*\(")
    check("F1-1 现有量取自 RS 存储（不自己遍历）",
          scan is not None and "StorageNetworkComponent" in scan and "storage.get(" in scan,
          "「现有」不是 RS 的口径")
    check("F1-2 没缺口就不进缺口表", scan is not None and "have >= order.amount()" in scan,
          "会把没缺口的条目也算成缺口（会乱弹询问）")
    check("F1-3 可合成判据 = 有匹配样板",
          scan is not None and "getPatternsByOutput(" in scan,
          "没有判「本网络是否有以它为产物的样板」")
    check("F1-4 缺口 = 目标 − 现有",
          scan is not None and "order.amount() - have" in scan, "缺口算法不是目标减现有")

    # ---------- F2：「先不发」+ 只在整张订单触发时问（防死循环） ----------
    trigger = body_of(be, r"public\s+void\s+triggerRequest\s*\(\s*@Nullable\s+final\s+ServerPlayer")
    check("F2-1 触发入口里有缺口询问",
          trigger is not None and "SenderShortage.ask(" in trigger, "询问没有被接进发送路径")
    if trigger is not None:
        ask_pos = trigger.find("SenderShortage.ask(")
        enqueue_pos = trigger.find("enqueuePendingPlan(")
        check("F2-2 询问必须在「排计划 / 投递」之前",
              ask_pos >= 0 and enqueue_pos > ask_pos, "询问发生在投递之后（已经发出去了才问）")
        check("F2-3 问出口就 return（一件都不投）",
              ask_pos >= 0 and "return;" in trigger[ask_pos:enqueue_pos if enqueue_pos > 0 else None],
              "询问之后没有 return ⇒ 违反「先不发」")
        check("F2-4 只在 plan == null 时问（问完投现有部分时不再问）",
              "if (plan == null) {" in trigger, "缺少守卫 ⇒ 回答后的投递会再触发询问（死循环）")

    # ---------- F3：「最多可合成」只来自 PreviewProvider + 有界等待 + 异常兜底 ----------
    check("F3-1 用 RS 的 PreviewProvider#getMaxAmount",
          "getMaxAmount(" in shortage, "没有用 RS 的口径算「最多可合成」")
    check("F3-2 自己遍历配方瞎猜 = 0 处",
          "getPatterns()" not in shortage, "SenderShortage 里出现了自己遍历样板集（契约明令禁止）")
    check("F3-3 有界等待（不挂住服务端主线程）",
          re.search(r"\.get\(\s*MAX_AMOUNT_TIMEOUT_MS\s*,\s*TimeUnit\.MILLISECONDS\s*\)", shortage)
          is not None, "getMaxAmount 是无界 get()（合成树复杂时会挂住主线程）")
    check("F3-4 超时 / 中断 / 执行异常都 catch",
          all(token in shortage for token in ("TimeoutException", "InterruptedException",
                                              "ExecutionException")),
          "缺 catch 子句（主线程会抛未捕获异常）")
    check("F3-5 中断后又打断当前线程（清中断位）",
          "Thread.currentThread().interrupt()" in shortage, "吞掉中断却不恢复标记")
    check("F3-6 不向上抛（方法体里没有 throw new）",
          "throw new" not in shortage, "把异常抛给了调用方（发送按钮会崩）")

    # ---------- F4：询问态由服务端持有，客户端只回「按了哪个按钮」 ----------
    check("F4-1 询问上下文按玩家存（take 即清空）",
          "AWAITING.remove(" in shortage and "AWAITING.put(" in shortage,
          "没有「等回答」的记录 ⇒ 无法知道玩家在回答什么")
    check("F4-2 询问 60 秒过期",
          "AWAITING_TTL_MS" in shortage and "60_000L" in shortage, "没有过期判据")
    check("F4-3 回答包只带 containerId + craft",
          re.search(r"record\s+SenderShortageReplyPacket\s*\(\s*int\s+containerId\s*,"
                    r"\s*boolean\s+craft\s*\)", reply_pkt) is not None,
          "回答包里带了数量字段（客户端就能决定资源流向）")
    check("F4-4 回答包只做归属校验 + 委托 SenderShortage.reply",
          "SenderShortage.reply(" in reply_pkt and "SenderBackend.Holder" in reply_pkt,
          "回答没有走服务端统一入口")
    check("F4-5 没待回答时明确回一句（不静默）",
          "MSG_SHORTAGE_EXPIRED" in reply_pkt, "过期 / 重复点会静默（玩家以为按钮坏了）")

    # ---------- F5：询问包只带显示数字，取走即清空 ----------
    check("F5-1 询问包五个数字 + containerId",
          all(token in prompt_pkt for token in ("target", "have", "craftable", "missing",
                                                "sendAfterCraft")),
          "询问包缺字段")
    check("F5-2 询问包一次性消费（takeFor 取走即清空）",
          "lastReceived = null;" in prompt_pkt and "takeFor(" in prompt_pkt,
          "同一份询问会反复弹窗")
    check("F5-3 数字全部由服务端算好（Awaiting 的 *Total）",
          "awaiting.haveTotal()" in prompt_pkt and "awaiting.craftableTotal()" in prompt_pkt,
          "客户端 / 包里出现了自己算的痕迹")

    # ---------- F6：回答后的执行顺序（先投现有 → 再合成 → 最后登记补投） ----------
    reply = body_of(shortage, r"public\s+static\s+boolean\s+reply\s*\(")
    check("F6-1 reply 有「只发送现有」分支",
          reply is not None and "if (!craft)" in reply, "两按钮没有分流")
    if reply is not None:
        existing_pos = reply.find("triggerWithPlan(")
        craft_pos = reply.find("startCraft(")
        register_pos = reply.find("PendingSenderSends.register(")
        check("F6-2 先投现有部分",
              existing_pos >= 0, "回答后没有立刻投出现有部分（按钮说 75 实际到 0）")
        check("F6-3 先投现有 → 再启动合成 → 最后登记补投",
              existing_pos >= 0 and craft_pos > existing_pos and register_pos > craft_pos,
              "顺序错了（先合成会让「现有」也被算进合成量）")
        only_send = reply[reply.find("if (!craft)"):craft_pos if craft_pos > 0 else None]
        only_send_code = chr(10).join(line.split("//")[0] for line in (only_send or "").splitlines())
        check("F6-4 「只发送现有」不发合成任务、不登记补投",
              reply.count("startCraft(") == 1
              and "startCraft(" not in only_send_code
              and "PendingSenderSends.register(" not in only_send_code,
              "点「只发送」也偷偷下了合成单")
    check("F6-5 合成走 ensureTask（幂等：重复点不重复下单）",
          "ensureTask(" in shortage and "startTask(" not in shortage,
          "用 startTask 会在重复点击时下多份任务")
    check("F6-6 ensureTask 带非空取消令牌（否则 RS 内部 NPE）",
          "TimeoutableCancellationToken" in shortage, "缺取消令牌")

    # ---------- F7：补投队列（凑齐即投 / 超时放弃 / 绝不投错） ----------
    check("F7-1 超时 = 10 分钟",
          EXPECTED_TIMEOUT_TICKS_EXPR.replace(" ", "") in pending.replace(" ", ""),
          "超时常量与「10 分钟」不一致")
    tick = body_of(pending, r"public\s+static\s+void\s+tick\s*\(")
    check("F7-2 超时放弃并提示",
          tick is not None and "now >= send.deadlineGameTime()" in tick
          and "shortage_gave_up" in tick,
          "超时后既不放弃也不提示（无限挂起）")
    check("F7-3 只投「现在真的有的量」",
          tick is not None and "Math.min(send.remaining(), have)" in tick,
          "没有按网络存量夹取（会空转 / 过量）")
    check("F7-4 目标找不到 / 重名 ⇒ 作废且提示，绝不投错机器",
          tick is not None and "hits.size() != 1" in tick,
          "目标消失时可能会投给别的接收器")
    check("F7-5 投递复用共享引擎（不写第二份取料循环）",
          tick is not None and "SenderDelivery.deliver(" in tick,
          "补投自己写了一份取料逻辑（守恒语义会分叉）")
    check("F7-6 断网 / 没电只暂停不丢计划",
          tick is not None and "storage == null" in tick and "return false" in tick,
          "断网时直接把计划丢了")
    check("F7-7 补投待办不落盘（避免「永远等不到货」的存档）",
          tree["be"] is not None and "pendingSends" in be,
          "找不到补投字段（BE 没有挂上驱动）")

    # ---------- F8：回归 —— 没缺口 / 不可合成 ⇒ 行为不变 ----------
    ask = body_of(shortage, r"public\s+static\s+boolean\s+ask\s*\(")
    check("F8-1 没缺口 ⇒ 不询问（返回 false）",
          ask is not None and "scanned.isEmpty()" in ask, "无缺口也会弹询问")
    check("F8-2 不可合成 ⇒ 不询问（沿旧路径发）",
          ask is not None and "if (!anyCraftable) {" in ask,
          "不可合成的缺口也弹询问（用户没要求）")
    check("F8-3 有样板但原料不够 ⇒ 不询问（合成不出来）",
          ask is not None and "craftableNow" in ask, "合成不出任何东西还弹询问")
    check("F8-4 询问前不写任何持久状态",
          ask is not None and ".save(" not in ask and "setOrders(" not in ask,
          "询问阶段就改了配置（撤销不回来）")

    # ---------- F9：客户端弹窗只显示服务端的数 ----------
    check("F9-1 弹窗数值全部来自询问包",
          "packet.target()" in screen and "packet.have()" in screen
          and "packet.craftable()" in screen and "packet.missing()" in screen,
          "弹窗自己算数")
    check("F9-2 两个按钮的文案用同一个口径",
          'LANG + "craft"' in screen and 'LANG + "send_only"' in screen,
          "按钮文案不是「合成 X 并发送 Y / 只发送 Y」")
    check("F9-3 弹窗是子界面（Esc = 什么都不做）",
          "extends ChildConfigScreen" in screen, "不是子窗口（Esc 行为会不可控）")

    return out


def run_pending(tree):
    pending = []
    needed = [
        "gui.rs_create_compat.sender_craft_prompt.title",
        "gui.rs_create_compat.sender_craft_prompt.line",
        "gui.rs_create_compat.sender_craft_prompt.craft",
        "gui.rs_create_compat.sender_craft_prompt.send_only",
        "gui.rs_create_compat.sender_craft_prompt.craft.tip",
        "gui.rs_create_compat.sender_craft_prompt.send_only.tip",
        "gui.rs_create_compat.network_resource_requester.msg.shortage_sent_only",
        "gui.rs_create_compat.network_resource_requester.msg.shortage_crafting",
        "gui.rs_create_compat.network_resource_requester.msg.shortage_gave_up",
        "gui.rs_create_compat.network_resource_requester.msg.shortage_expired",
        "gui.rs_create_compat.network_resource_requester.fail.shortage_gave_up",
    ]
    zh = json_keys(tree["lang_zh"])
    en = json_keys(tree["lang_en"])
    missing = [key for key in needed if key not in zh or key not in en]
    if missing:
        pending.append("lang/*.json 缺 %d 个 F 组键（见交付消息清单）" % len(missing))
    return pending


# ======================================================================================
# 反例
# ======================================================================================

def failures_of(tree):
    return set(name for name, ok, _detail in run_checks(tree) if not ok)


def mutate(tree, key, old, new, count_limit=1):
    clone = dict(tree)
    text = clone.get(key) or ""
    if old not in text:
        return None
    clone[key] = text.replace(old, new, count_limit)
    return clone


def run_counterexamples(tree):
    base_bad = failures_of(tree)
    cases = [
        ("删掉「只在整张订单时问」守卫（会死循环）",
         lambda t: mutate(t, "be", "if (plan == null) {", "if (true) {"),
         ("F2-4",)),
        ("删掉中断兜底",
         lambda t: mutate(t, "shortage", "Thread.currentThread().interrupt();", ""),
         ("F3-5",)),
        ("把「最多可合成」改成无界 get()",
         lambda t: mutate(t, "shortage",
                          ".get(MAX_AMOUNT_TIMEOUT_MS, TimeUnit.MILLISECONDS)", ".get()"),
         ("F3-3",)),
        ("超时后不放弃（无限挂起）",
         lambda t: mutate(t, "pending",
                          "if (now >= send.deadlineGameTime() && send.remaining() > 0L) {",
                          "if (false) {"),
         ("F7-2",)),
        ("回答后先合成再投现有（顺序反了）",
         lambda t: mutate(t, "shortage",
                          "if (!awaiting.existingPlan().isEmpty()) {",
                          "startCraft(backend.network(), awaiting.gaps());\n"
                          "        if (!awaiting.existingPlan().isEmpty()) {"),
         ("F6-3",)),
        ("不可合成也弹询问",
         lambda t: mutate(t, "shortage", "if (!anyCraftable) {", "if (false) {"),
         ("F8-2",)),
        ("「只发送现有」也偷偷下合成单",
         lambda t: mutate(t, "shortage", "        if (!craft) {",
                          "        startCraft(backend.network(), awaiting.gaps());\n"
                          "        if (!craft) {"),
         ("F6-4",)),
        ("空树", lambda t: {key: None for key in t}, None),
    ]
    for label, mutate_fn, expected in cases:
        COUNTER_TOTAL[0] += 1
        broken = mutate_fn(tree)
        if broken is None:
            print("FAIL 反例 %s（源码里找不到锚点；实现写法可能变了，请更新自检锚点）" % label)
            continue
        fresh = failures_of(broken) - base_bad
        if expected is None:
            if len(fresh) >= 10:
                COUNTER_PASS[0] += 1
                print("PASS 反例 %s ⇒ 新增判负 %d 项（空树必须判负）" % (label, len(fresh)))
            else:
                print("FAIL 反例 %s ⇒ 新增判负只有 %d 项（空树没有被判负）" % (label, len(fresh)))
            continue
        hit = [name for name in fresh if any(name.startswith(prefix) for prefix in expected)]
        if hit:
            COUNTER_PASS[0] += 1
            print("PASS 反例 %s ⇒ 新增判负 %d 项（例：%s）" % (label, len(fresh), hit[0]))
        else:
            print("FAIL 反例 %s ⇒ 改坏了却没有对应判负（新增失败：%s）"
                  % (label, sorted(fresh)[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 131 轮 · F 组契约自检：缺口自动合成询问 / 合成 / 补投")
    print("规格：docs/ROUND131_CONTRACT.md §3.F")
    print("源码根：%s" % JAVA_REL.replace(os.sep, "/"))

    tree = load_tree()
    results = run_checks(tree)
    for name, ok, detail in results:
        if ok:
            print("PASS " + name)
        else:
            print("FAIL " + name + (("  <- " + detail) if detail else ""))

    bad = [name for name, ok, _detail in results if not ok]
    pending = run_pending(tree)
    print("\nF 组检查项：%d，失败：%d" % (len(results), len(bad)))
    if bad:
        for name in bad:
            print("  FAIL " + name)
    if pending:
        print("\nPENDING_LEAD（lang 归 Lead 写入，不算失败）：")
        for item in pending:
            print("  PENDING " + item)

    run_counterexamples(tree)

    print("\nF 组检查项：%d，反例：%d/%d，失败：%d，PENDING_LEAD：%d"
          % (len(results), COUNTER_PASS[0], COUNTER_TOTAL[0], len(bad), len(pending)))
    if bad or COUNTER_PASS[0] != COUNTER_TOTAL[0]:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
