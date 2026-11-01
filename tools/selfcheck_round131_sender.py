#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮 · C 组契约自检：请求器去设置页 / 去插件槽 / 目标子界面 / 发送源后端化。

规格来源
--------
`docs/ROUND131_CONTRACT.md` §0（用户原话）、§1（拍板）、§2（写作用域）、§3.C（硬判据）。

本脚本是**规格判定程序**
------------------------
**P0：递归栈溢出，第 131 轮修** —— `RequesterSenderBackend#setTargetName` 曾经调
`applyTarget`，而 `applyTarget` 又调 `setTargetName` 来写入 ⇒ 无基例、一设目标就
`StackOverflowError` 打崩服务端。下面 C10 组断言把「单向调用 + 写/同步顺序」钉死，
反例把旧写法拼回去必须判负。
* 只读 `src/**`，写作用域只有 `tools/`；不 import 游戏代码、不跑 gradlew / javac；
* 纯 stdlib python3；定位一律用正则 + 括号配对，不写死行号；
* **反例有判别力**：把旧写法拼回去（升级槽 / cycleTarget / 空操作被替换 / 重名校验被删）必须判负；
  空树（源码消失）也必须判负。反例只在内存里改字符串，不写任何文件。

PENDING_LEAD（不算失败，但会逐条打印）
------------------------------------
本轮有三类东西**不在 C 组写作用域内**（Lead 独占）：`client/ClientInit.java` 与
`client/ClientPayloadSink.java` 里对已删除界面类的引用、`lang/*.json` 的新键、`RS_Create_Compat`
的 payload 注册。它们不是「实现没做」，而是「另一条线还没合」；脚本把它们单独列成
`PENDING_LEAD`，**不混进失败计数**（否则本组永远交不了），但也绝不静默 ——
每一条都带「改哪里、怎么改」。C 组自己的判据仍然必须全绿。

用法：python tools/selfcheck_round131_sender.py
退出码：0 = C 组判据全过（PENDING_LEAD 允许非空）；1 = 有失败。最后一行：ALL_PASS / FAILED。
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

#: 本轮涉及的文件（键 → 相对 src/main/java 的路径；None = 该文件**必须不存在**）。
PATHS = {
    "screen_old": "client/screen/NetworkResourceRequesterScreen.java",
    "menu": "menu/NetworkResourceRequesterMenu.java",
    "be": "block/entity/NetworkResourceRequesterBlockEntity.java",
    "block": "block/NetworkResourceRequesterBlock.java",
    "picker_menu": "client/grid/ResourcePickerMenu.java",
    "picker_screen": "client/grid/ResourcePickerScreen.java",
    "target_screen": "client/screen/SenderTargetSelectScreen.java",
    "backend": "support/SenderBackend.java",
    "req_backend": "support/RequesterSenderBackend.java",
    "delivery": "support/SenderDelivery.java",
    "order_pkt": "network/RequesterOrderPacket.java",
    "target_pkt": "network/SetRequesterTargetPacket.java",
    "trigger_pkt": "network/TriggerRequesterPacket.java",
    "lead_clientinit": "client/ClientInit.java",
    "lead_sink": "client/ClientPayloadSink.java",
}

#: 已经被本轮删除 / 必须彻底不复存在的 token（在**C 组写作用域内的文件**里统计）。
DEAD_IN_SCOPE = {
    "screen_old": ["NetworkResourceRequesterScreen"],
    "menu": ["openSettingsFor", "openResourcePicker", "ACTION_OPEN_SETTINGS",
             "UPGRADE_COUNT", "RsccSpeedStackUpgradeContainer", "getUpgradeContainer",
             "ContainerData"],
    "be": ["RsccSpeedStackUpgradeContainer", "upgradeContainer", "TAG_UPGRADES",
           "getUpgradeContainer", "getContainerData", "NetworkResourceRequesterScreen"],
    "block": ["getUpgradeContainer"],
    "picker_screen": ["settingsButton", "toolbar.settings", "cycleTarget", "openSettings"],
    "picker_menu": ["NetworkResourceRequesterBlockEntity", "requesterOf"],
    "order_pkt": ["NetworkResourceRequesterMenu", "ACTION_OPEN_SETTINGS"],
    "target_pkt": ["NetworkResourceRequesterMenu"],
    "trigger_pkt": ["NetworkResourceRequesterMenu"],
}

#: 固定节拍（用户拍板「红石激活多快就发多快」，与升级无关）。
FIXED_BATCHES_PER_TICK = 8
FIXED_BATCH_SIZE = 64

#: 目标子界面必须有的语言键前缀（代码里引用，Lead 写进 lang）。
TARGET_LANG_PREFIX = "gui.rs_create_compat.sender_target_select."

FAILS = []
COUNTER_TOTAL = [0]
COUNTER_PASS = [0]
PENDING = []


# ======================================================================================
# 源码装载 / 工具
# ======================================================================================

def load_tree():
    """把源码树读成 {key: content}（缺失 = None）。"""
    tree = {}
    for key, rel in PATHS.items():
        path = os.path.join(ROOT, JAVA_REL, rel)
        tree[key] = read(path) if os.path.isfile(path) else None
    tree["lang_zh"] = read(os.path.join(ROOT, LANG_REL, "zh_cn.json"))
    tree["lang_en"] = read(os.path.join(ROOT, LANG_REL, "en_us.json"))
    tree["all_network"] = read_dir(os.path.join(ROOT, JAVA_REL, "network"))
    tree["all_java"] = read_dir(os.path.join(ROOT, JAVA_REL))
    return tree


def read(path):
    try:
        with io.open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def read_dir(root):
    """{相对路径: 内容}（递归 .java；只用于「全树扫描」类判据）。"""
    out = {}
    for base, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".java"):
                continue
            full = os.path.join(base, name)
            out[os.path.relpath(full, root)] = read(full) or ""
    return out


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


def has(text, token):
    return bool(text) and token in text


def json_keys(text):
    """lang 文件的键集合（解析失败 ⇒ 空集，由调用方判负）。"""
    if not text:
        return set()
    try:
        import json
        return set(json.loads(text).keys())
    except Exception:
        return set()


# ======================================================================================
# 判据（返回 [(名字, 是否通过, 细节)]）
# ======================================================================================

def run_checks(tree):
    out = []

    def check(name, ok, detail=""):
        out.append((name, bool(ok), detail if not ok else ""))

    # ---------- C1：设置界面整页删除 + 入口不存在 ----------
    check("C1-1 请求器设置界面文件已删除", tree["screen_old"] is None,
          "client/screen/NetworkResourceRequesterScreen.java 仍在")
    for key, tokens in DEAD_IN_SCOPE.items():
        text = tree.get(key) or ""
        for token in tokens:
            check("C1/C2 死代码 %s:%s" % (key, token), count(text, token) == 0,
                  "应删除的旧实现 token 仍出现 %d 次" % count(text, token))

    # ---------- C2：6 个升级槽整条删除 ----------
    be = tree["be"] or ""
    check("C2-1 方块实体没有升级槽容器", "RsccSpeedStackUpgradeContainer" not in be, "还有升级容器")
    check("C2-2 方块实体 NBT 不再写 Upgrades", '"Upgrades"' not in be, "还有 Upgrades 键")
    check("C2-3 方块实体不再有 ContainerData", "getContainerData" not in be, "还有 ContainerData")
    block = tree["block"] or ""
    check("C2-4 方块掉落不再结算升级槽", "getUpgradeContainer" not in block,
          "block/NetworkResourceRequesterBlock 还在收升级物品")

    # ---------- C3：节拍固定（常量有两个出处就不算固定） ----------
    delivery = tree["delivery"] or ""
    check("C3-1 FIXED_BATCHES_PER_TICK = %d" % FIXED_BATCHES_PER_TICK,
          re.search(r"FIXED_BATCHES_PER_TICK\s*=\s*%d\s*;" % FIXED_BATCHES_PER_TICK, delivery)
          is not None, "常量不是契约值 / 找不到")
    check("C3-2 FIXED_BATCH_SIZE = %d" % FIXED_BATCH_SIZE,
          re.search(r"FIXED_BATCH_SIZE\s*=\s*%dL?\s*;" % FIXED_BATCH_SIZE, delivery) is not None,
          "常量不是契约值 / 找不到")
    batches_body = body_of(be, r"public\s+int\s+getThrottleBatchesPerTick\s*\(\s*\)")
    check("C3-3 每 tick 批数取自固定常量",
          batches_body is not None and "FIXED_BATCHES_PER_TICK" in batches_body,
          "仍然是升级容器给出的值（用户要求不吃升级）")
    batch_body = body_of(be, r"public\s+long\s+getThrottleBatch\s*\(\s*\)")
    check("C3-4 单批量取自固定常量",
          batch_body is not None and "FIXED_BATCH_SIZE" in batch_body,
          "仍然是升级容器给出的值")
    throttle_body = body_of(be, r"public\s+boolean\s+throttleTick\s*\(\s*\)")
    check("C3-5 闸门恒放行（红石脉冲多快就发多快）",
          throttle_body is not None and "return true" in throttle_body,
          "闸门还在读升级容器")

    # ---------- C4：发送源抽象（菜单只认 SenderBackend） ----------
    picker = tree["picker_menu"] or ""
    check("C4-1 网格菜单实现 SenderBackend.Holder",
          "implements SenderBackend.Holder" in picker, "没有 Holder 接口（包层无法解析发送源）")
    check("C4-2 网格菜单字段是 SenderBackend",
          re.search(r"private\s+final\s+SenderBackend\s+backend\s*;", picker) is not None,
          "字段仍是方块实体")
    check("C4-3 网格菜单不认识请求器方块",
          "NetworkResourceRequesterBlockEntity" not in picker, "还引用方块实体")
    check("C4-4 开放三参 openFor 给终端复用",
          re.search(r"public\s+static\s+boolean\s+openFor\s*\(\s*final\s+ServerPlayer\s+\w+\s*,\s*"
                    r"(?:@\w+\s+)?(?:final\s+)?SenderBackend\s+\w+\s*,\s*"
                    r"(?:@\w+\s+)?(?:final\s+)?Component\s+\w+\s*\)",
                    picker) is not None,
          "缺 openFor(ServerPlayer, SenderBackend, Component) —— 终端复用需要它")
    check("C4-5 订单快照接受 SenderBackend",
          re.search(r"snapshotOrders\s*\(\s*(final\s+)?SenderBackend\s+\w+\s*\)", picker) is not None,
          "snapshotOrders 还只接受方块实体")

    # ---------- C5：RS 网格「只选不搬」三层保证不许破坏 ----------
    create_ops = body_of(picker, r"public\s+GridOperations\s+createOperations\s*\(")
    check("C5-1 createOperations 恒空操作",
          create_ops is not None and "EMPTY_OPERATIONS" in create_ops,
          "服务端变成了真的搬运（用户报告的「物品看似放进去」会复活）")
    filter_update = body_of(picker, r"public\s+void\s+handleResourceFilterSlotUpdate\s*\(")
    filter_lines = []
    for line in (filter_update or "").splitlines():
        code = line.split("//")[0].strip()
        if code:
            filter_lines.append(code)
    check("C5-2 过滤槽回传被吞掉",
          filter_update is not None and not filter_lines,
          "RS 的只带资源键的通道又变成可写通道")
    quick_move = body_of(picker, r"public\s+ItemStack\s+quickMoveStack\s*\(")
    check("C5-3 quickMoveStack 不搬物品",
          quick_move is not None and "ItemStack.EMPTY" in quick_move,
          "玩家背包快速移动会搬物品")
    ops_body = body_of(picker, r"private\s+static\s+final\s+GridOperations\s+EMPTY_OPERATIONS")
    check("C5-4 空操作集 extract/insert 都回 false",
          ops_body is not None and ops_body.count("return false;") >= 2,
          "空操作集被改成真的存取")

    # ---------- C6：目标选择改成可搜索子界面 ----------
    target = tree["target_screen"] or ""
    check("C6-1 子界面继承 ChildConfigScreen", "extends ChildConfigScreen" in target,
          "不是子窗口形态（会盖掉父界面 / 变成整页）")
    check("C6-2 子界面有搜索框", "new EditBox(" in target and "setResponder(" in target,
          "没有搜索（用户明确要求可搜索）")
    check("C6-3 子界面用 ScrollSelectWidget", "ScrollSelectWidget" in target,
          "与机器选择器不同源")
    check("C6-4 确定提交 SetRequesterTargetPacket",
          "SetRequesterTargetPacket" in target and "sendToServer" in target,
          "确定没有提交服务端")
    check("C6-5 取消/确定都返回父界面", "returnToParent()" in target, "关不掉子界面")
    screen = tree["picker_screen"] or ""
    check("C6-6 工具栏「目标」打开子界面",
          "new SenderTargetSelectScreen(" in screen, "目标按钮没有接子界面")
    check("C6-7 旧轮换路径已删除",
          "cycleTarget" not in screen, "「左键切下一台 / Shift 上一台」还在")
    check("C6-8 子界面语言键前缀一致",
          TARGET_LANG_PREFIX in target, "语言键前缀与 lang 不一致")

    # ---------- C7：网络包只认 SenderBackend ----------
    order_pkt = tree["order_pkt"] or ""
    check("C7-1 订单包不再引用旧菜单",
          "NetworkResourceRequesterMenu" not in order_pkt, "还在调旧菜单静态方法")
    check("C7-2 订单包用 Holder 解析发送源",
          "SenderBackend.Holder" in order_pkt, "没有统一解析路径")
    check("C7-3 订单包不再有「打开设置」动作",
          "ACTION_OPEN_SETTINGS" not in order_pkt, "设置入口的动作码还在")
    check("C7-4 每次编辑都回权威快照",
          count(order_pkt, "syncTo(") >= 4,
          "增/删/改/清空没有逐个回快照（界面会停在旧值）")
    check("C7-5 目标包只走 applyTarget（快照由它自己在末尾发）",
          "applyTarget(" in (tree["target_pkt"] or "")
          and "syncTo(" not in (tree["target_pkt"] or ""),
          "包层自己又发一份快照（重复下发）或没走唯一校验链")
    check("C7-6 发送包走 triggerFor",
          "triggerFor(" in (tree["trigger_pkt"] or ""), "发送没有走统一校验链")

    # ---------- C8：服务端校验链（唯一实现） ----------
    req = tree["req_backend"] or ""
    apply_body = body_of(req, r"public\s+static\s+TargetApplyResult\s+applyTarget\s*\(")
    check("C8-1 目标校验有四类拒绝",
          apply_body is not None and all(token in apply_body for token in
                                         ("EMPTY", "TOO_LONG", "INVALID_CHARS", "NOT_FOUND",
                                          "AMBIGUOUS")),
          "目标校验链缺分支（空名/超长/非法/找不到/重名）")
    check("C8-2 重名绝不随机挑一台",
          apply_body is not None and "hits.size() > 1" in apply_body, "重名分支被删")
    check("C8-3 重名分支调用 resolveReceiversByName",
          apply_body is not None and "resolveReceiversByName" in apply_body,
          "没有在本网络内现解析")
    add_body = body_of(req, r"public\s+static\s+OrderResult\s+addEntry\s*\(")
    check("C8-4 追加条目拒绝非正数量/空键",
          add_body is not None and "INVALID_AMOUNT" in add_body and "INVALID_RESOURCE" in add_body,
          "追加校验被删")
    for name, marker in (("C8-5 删除条目做资源键二次确认", "removeEntry"),
                         ("C8-6 改数量做资源键二次确认", "setAmount")):
        body = body_of(req, r"public\s+static\s+OrderResult\s+%s\s*\(" % marker)
        check(name, body is not None and "entryMatches" in body, "只校验下标（旧下标会改到别人头上）")

    # ---------- C9：投递引擎只有一份实现 ----------
    deliver_body = body_of(be, r"private\s+long\s+deliverBatch\s*\(")
    check("C9-1 方块实体投递委托共享引擎",
          deliver_body is not None and "SenderDelivery.deliver" in deliver_body,
          "方块实体里还留着自己那份投递实现（终端无法复用）")
    check("C9-2 方块实体不再自己取料",
          deliver_body is not None and "storage.extract(" not in deliver_body,
          "取料循环又回到方块实体里（会与终端分叉）")
    engine = body_of(delivery, r"public\s+static\s+Result\s+deliver\s*\(")
    check("C9-3 引擎先模拟再执行",
          engine is not None and "Action.SIMULATE" in engine and "Action.EXECUTE" in engine,
          "缺「先夹再取」的两段式")
    check("C9-4 守恒自证（取出 = 写入 + 回滚 + 暂存）",
          engine is not None and "got != inserted + rolledBack + kept" in engine,
          "守恒账被删（资源可能凭空增减）")
    check("C9-5 网络不够 ⇒ 报缺口并收敛（不跨 tick 空转）",
          engine is not None and "if (available < wanted)" in engine
          and "short_stock" in engine and "stalled" in engine,
          "缺口收敛分支被删 ⇒ 投不出的量会被当成「跨 tick 续投」无限重试")

    # ---------- C10（P0）：目标写入不许成环 ----------
    req_backend = tree["req_backend"] or ""
    setter = body_of(req_backend, r"public\s+boolean\s+setTargetName\s*\(")
    check("C10-1 setTargetName 不许回调 applyTarget（P0 递归栈溢出）",
          setter is not None and "applyTarget(" not in setter,
          "实现方又调回 applyTarget ⇒ 无基例、一设目标就 StackOverflowError")
    check("C10-2 setTargetName 是直接写（只碰方块实体 + 标脏）",
          setter is not None and "requester.setTargetName(" in setter
          and "setChanged(" in setter,
          "setTargetName 没有真正写入")
    check("C10-3 区块实体 setter 只有一处调用点",
          count(req_backend, "requester.setTargetName(") == 1,
          "存在第二处直接写（绕过唯一校验链）")
    apply2 = body_of(req_backend, r"public\s+static\s+TargetApplyResult\s+applyTarget\s*\(")
    check("C10-4 applyTarget 通过接口写入（唯一写入点）",
          apply2 is not None and count(apply2, "backend.setTargetName(") == 1,
          "applyTarget 没有唯一的写入点")
    if apply2 is not None:
        validate_pos = apply2.find("hits.size() > 1")
        write_pos = apply2.find("backend.setTargetName(")
        sync_pos = apply2.rfind("syncTo(")
        check("C10-5 顺序：校验通过 → 写 → 回快照",
              validate_pos >= 0 and write_pos > validate_pos and sync_pos > write_pos,
              "写入 / 同步顺序错乱（可能先写后校验）")
        check("C10-6 拒绝分支也要回快照（不静默）",
              apply2.count("syncTo(") >= 3,
              "拒绝路径没有回提示（玩家点了没反应）")

    return out


def run_pending(tree):
    """Lead 独占文件的残留（不算失败，但必须逐条打印出来）。"""
    pending = []
    init = tree["lead_clientinit"] or ""
    sink = tree["lead_sink"] or ""
    if "NetworkResourceRequesterScreen" in init:
        pending.append("client/ClientInit.java：删掉 NETWORK_RESOURCE_REQUESTER_MENU 的界面注册行"
                       "（NetworkResourceRequesterScreen 已删除）")
    if "NetworkResourceRequesterScreen" in sink:
        pending.append("client/ClientPayloadSink.java：import 与 syncNetworkResourceRequester(...) "
                       "函数体（保留方法签名，函数体改空）里的旧界面引用")
    zh = json_keys(tree["lang_zh"])
    en = json_keys(tree["lang_en"])
    needed = [
        "gui.rs_create_compat.sender_target_select.title",
        "gui.rs_create_compat.sender_target_select.search_hint",
        "gui.rs_create_compat.sender_target_select.hint",
        "gui.rs_create_compat.sender_target_select.confirm",
        "gui.rs_create_compat.sender_target_select.cancel",
        "gui.rs_create_compat.sender_target_select.current",
        "gui.rs_create_compat.sender_target_select.current.none",
        "gui.rs_create_compat.sender_target_select.empty",
        "gui.rs_create_compat.sender_target_select.search.tip",
        "gui.rs_create_compat.sender_target_select.confirm.tip",
        "gui.rs_create_compat.sender_target_select.cancel.tip",
        "gui.rs_create_compat.resource_picker.toolbar.rate.hint",
    ]
    missing = [key for key in needed if key not in zh or key not in en]
    if missing:
        pending.append("lang/*.json 缺 %d 个 sender_target_select / toolbar.rate.hint 键（见交付消息清单）"
                       % len(missing))
    if "gui.rs_create_compat.resource_picker.toolbar.settings" in zh:
        pending.append("lang 里 gui.rs_create_compat.resource_picker.toolbar.settings / .hint 已无引用，"
                       "可删")
    return pending


# ======================================================================================
# 反例（改坏内存里的源码，必须新增判负）
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
    cases = []

    # 1) 节拍改回升级容器（用户明确否掉的旧写法）
    cases.append(("节拍改回升级旋钮", lambda t: mutate(
        t, "be",
        "return cretae.cookiewyq.rs_create_compat.support.SenderDelivery.FIXED_BATCHES_PER_TICK;",
        "return upgradeContainer.throttleBatchesPerTick();"),
        ("C3-3", "C2-1", "C2-2")))
    # 2) 关掉「网络里就这么多」的缺口收敛：会一直重试 / 报成功
    cases.append(("关掉缺口收敛分支", lambda t: mutate(
        t, "delivery", "if (available < wanted) {", "if (false) {"),
        ("C9-5",)))
    # 3) 把「只选不搬」换成真的搬运
    cases.append(("createOperations 改成真的搬运", lambda t: mutate(
        t, "picker_menu", "return EMPTY_OPERATIONS;", "return null;"),
        ("C5-1",)))
    # 4) 删掉目标重名校验（会随机挑一台）
    cases.append(("删掉目标重名校验", lambda t: mutate(
        t, "req_backend", "if (hits.size() > 1) {", "if (false) {"),
        ("C8-2", "C10-5")))
    # 5) 把旧轮换路径塞回网格界面
    cases.append(("复活目标轮换", lambda t: mutate(
        t, "picker_screen", "private void openTargetSelect() {",
        "private void cycleTarget(int delta) { openTargetSelect(); }\n\n"
        "    private void openTargetSelect() {"),
        ("C6-7",)))
    # 6) 守恒自证被删
    cases.append(("删掉守恒自证", lambda t: mutate(
        t, "delivery",
        "if (got != inserted + rolledBack + kept) {",
        "if (false) {"),
        ("C9-4",)))
    # 7) P0：把 setTargetName 改回「调 applyTarget」的旧写法（必须判负）
    cases.append(("P0 递归写法拼回", lambda t: mutate(
        t, "req_backend",
        "        requester.setTargetName(name);",
        "        if (applyTarget(player, this, name) == TargetApplyResult.OK) { return true; }"
        "        + requester.setTargetName(name);"),
        ("C10-1",)))
    # 8) 空树：源码消失必须整体判负
    cases.append(("空树", lambda t: {key: None for key in t}, None))

    for label, mutate_fn, expected in cases:
        COUNTER_TOTAL[0] += 1
        broken = mutate_fn(tree)
        if broken is None:
            print("FAIL 反例 %s（源码里找不到锚点，无法构造；实现可能已改写法，请更新自检锚点）"
                  % label)
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
            continue
        print("FAIL 反例 %s ⇒ 改坏了却没有对应判负（新增失败：%s）"
              % (label, sorted(fresh)[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 131 轮 · C 组契约自检：请求器去设置页 / 去插件槽 / 目标子界面 / 发送源后端化")
    print("规格：docs/ROUND131_CONTRACT.md §3（C 组）")
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
    print("\nC 组检查项：%d，失败：%d" % (len(results), len(bad)))
    if bad:
        for name in bad:
            print("  FAIL " + name)
    if pending:
        print("\nPENDING_LEAD（不在 C 组写作用域内，不算失败；交付消息里已列清单）：")
        for item in pending:
            print("  PENDING " + item)

    run_counterexamples(tree)

    print("\nC 组检查项：%d，反例：%d/%d，失败：%d，PENDING_LEAD：%d"
          % (len(results), COUNTER_PASS[0], COUNTER_TOTAL[0], len(bad), len(pending)))
    if bad or COUNTER_PASS[0] != COUNTER_TOTAL[0]:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
