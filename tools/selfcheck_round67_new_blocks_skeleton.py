#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 67 轮：网络资源接收器 / 请求器（第一批：骨架 + 注册 + 语言键）的静态断言。

判据全部来自**工程内的真实来源**（blockstate JSON / Java 源码 / 注册表 / 语言文件），
不依赖任何人工维护的清单，因此「改了代码忘了改资源」一定会被这里抓住。

断言（任一 FAIL 即退出码 1）：
  ① **blockstate 变体 ↔ Java 属性一致**（本题的核心证据）：
     * 接收器：变体集合必须**恰好**是 facing{6} × active{2} = 12 个，且 Java 侧注册的是
       `BlockStateProperties.FACING`（6 向，序列化名 facing）+ `ModBlockStateProperties.ACTIVE`
       （名 active）；**不得**用 `HORIZONTAL_FACING`（只有 4 个水平值 ⇒ up/down 两个变体永远取不到模型）；
     * 请求器：变体集合必须**恰好**是 active{2}，且 Java 侧**只**注册 active
       —— 多注册一个 facing（或 powered）就会让 JSON 里的变体失配 ⇒ 紫黑缺失模型。
  ② **注册齐备**：两个方块 / 两个方块实体 / 两个菜单 / 两个物品 / 创造标签 / 网络节点能力监听器。
  ③ **菜单屏幕已注册且都在 client/ 包**：`client/ClientInit` 里 register 了两个菜单类型，
     两个 Screen 类都位于 `client/screen/` 下；服务端可见的新类里不含任何客户端专属类型。
  ④ **升级槽**：接收器 **6 槽**、白名单**只含两类**（speed_upgrade + stack_upgrade）；
     * 【第 131 轮反转】请求器设置界面删除 + 请求器 6 个插件槽删除 ⇒ ③ 段改成「请求器屏幕必须不存在、
     *   不注册」、④d/④e 反转成「请求器没有升级容器 / 不落盘升级」（判据只收紧与反转，未放宽）。
     * 请求器**也有** 6 槽（2026-10-17 起，见设计文档 §1.4 第 2 条：速度 = 每 tick 几批、
     叠加 = 每批多少，两台机器的判据与容器**同一份实现**）。
  ⑤ **三层准入校验**都在：
     ① 界面 `UpgradeSlot` 白名单 = 那两类；
     ② 方块实体侧 `RsccSpeedStackUpgradeContainer.validate()`（非法 / 超限一律**弹出到世界**）；
     ③ `RegisterUpgradeDestinations` 注册了接收器且只登记那两类。
  ⑥ **R4 已修**：`RegisterUpgradeDestinations` 里**不再**有 `sequence_assembly_executor`。
  ⑦ **语言键中英齐备**：本批新增的 12 个键在 zh_cn / en_us 都在、都非空，且 zh_cn 侧是中文。

用法：python tools/selfcheck_round67_new_blocks_skeleton.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

RECEIVER = "network_resource_receiver"
REQUESTER = "network_resource_requester"
RECEIVER_BE = "NetworkResourceReceiverBlockEntity"
REQUESTER_BE = "NetworkResourceRequesterBlockEntity"

FACINGS = ("north", "east", "south", "west", "up", "down")
EXPECTED_RECEIVER_VARIANTS = {"facing=%s,active=%s" % (f, a) for f in FACINGS for a in ("false", "true")}
EXPECTED_REQUESTER_VARIANTS = {"active=false", "active=true"}

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def read_json(path):
    return json.loads(read(path))


def code_of(rel):
    """读 src/main/java 下的相对路径源码；找不到返回空串（由断言报 FAIL）。"""
    path = os.path.join(JAVA, rel)
    return read(path) if os.path.isfile(path) else ""


def strip_comments_and_strings(text):
    """把注释与字符串字面量换成空格（行号 / 列数不变）。

    为什么需要它：类注释里**必须**能解释「为什么不用 HORIZONTAL_FACING」「为什么不用 POWERED
    方块状态」「R4 删掉了什么」，而那些类名恰好就是要断言的判据字符串。不剥注释，
    这些断言会把「解释为什么不用它」误判成「正在用它」。
    与 `tools/selfcheck_round47_dedicated_server_safety.py` 里的同名函数同一套做法。
    """
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
        elif char == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(char)
            i += 1
    return "".join(out)


def assertions_text(rel):
    """断言用源码：剥掉注释与字符串（字符串里的类名不算「使用」）。"""
    return strip_comments_and_strings(code_of(rel))


def check_variants():
    """① blockstate 变体 ↔ Java 属性一致（本文件最重要的一组断言）。"""
    receiver_keys = set(read_json(os.path.join(ASSETS, "blockstates", RECEIVER + ".json"))
                        .get("variants", {}).keys())
    requester_keys = set(read_json(os.path.join(ASSETS, "blockstates", REQUESTER + ".json"))
                         .get("variants", {}).keys())

    check("①a 接收器 blockstate 恰有 12 个变体（6 向 facing × active）",
          receiver_keys == EXPECTED_RECEIVER_VARIANTS,
          "实际 %d 个；缺失 %s；多余 %s" % (
              len(receiver_keys),
              sorted(EXPECTED_RECEIVER_VARIANTS - receiver_keys)[:4],
              sorted(receiver_keys - EXPECTED_RECEIVER_VARIANTS)[:4]))
    check("①b 请求器 blockstate 恰有 2 个变体（只有 active，无 facing）",
          requester_keys == EXPECTED_REQUESTER_VARIANTS,
          "实际 %s" % sorted(requester_keys))

    # 属性断言必须用「剥掉注释与字符串」后的源码：类注释里**必须**解释「为什么不用
    # HORIZONTAL_FACING / 为什么不用 POWERED」，而那些类名正是判据字符串本身。
    recv_block = assertions_text("block/NetworkResourceReceiverBlock.java")
    req_block = assertions_text("block/NetworkResourceRequesterBlock.java")

    check("①c 接收器方块类注册 FACING + ACTIVE",
          "BlockStateProperties.FACING" in recv_block
          and "builder.add(FACING)" in recv_block
          and "builder.add(ModBlockStateProperties.ACTIVE)" in recv_block)
    check("①d 接收器**不得**用 HORIZONTAL_FACING（只有 4 个水平值 ⇒ up/down 变体取不到模型）",
          "HORIZONTAL_FACING" not in recv_block)
    check("①e 接收器默认状态同时给出 facing 与 active",
          ".setValue(FACING, Direction.NORTH)" in recv_block
          and ".setValue(ModBlockStateProperties.ACTIVE, false)" in recv_block)

    check("①f 请求器方块类**只**注册 ACTIVE（无 facing / powered）",
          "builder.add(ModBlockStateProperties.ACTIVE)" in req_block
          and "builder.add(FACING)" not in req_block
          and "POWERED" not in req_block)
    check("①g 请求器默认状态只有 active",
          ".setValue(ModBlockStateProperties.ACTIVE, false)" in req_block)
    # 两个方块的模型文件必须真的存在（否则变体再对也是紫黑块）
    for ident in (RECEIVER, REQUESTER):
        check("①h %s 的两态方块模型 + 物品模型都存在" % ident,
              all(os.path.isfile(os.path.join(ASSETS, "models", "block", "%s%s.json" % (ident, suffix)))
                  for suffix in ("", "_active"))
              and os.path.isfile(os.path.join(ASSETS, "models", "item", ident + ".json")))


def check_registration():
    """② 两个方块 / 方块实体 / 菜单 / 物品 / 创造标签 / 网络节点能力。"""
    main = code_of("RS_Create_Compat.java")
    for ident in (RECEIVER, REQUESTER):
        upper = ident.upper()
        check("②a %s：方块 + 物品 + 方块实体 + 菜单 四件套齐全" % ident,
              all(marker in main for marker in (
                  'BLOCKS.register("%s"' % ident,
                  'ITEMS.register("%s"' % ident,
                  "%s_BLOCK_ENTITY" % upper,
                  "%s_MENU" % upper)))
        check("②b %s：方块实体类型用新方块构造" % ident,
              "BlockEntityType.Builder.of(" in main and "%s_BLOCK.get()" % upper in main)
        check("②c %s：菜单类型用 FeatureFlags.DEFAULT_FLAGS 构造" % ident,
              re.search(re.escape("%s_MENU" % upper) + r"[\s\S]{0,400}?FeatureFlags\.DEFAULT_FLAGS", main)
              is not None)
    check("②d 两个方块物品都进了创造标签",
          "NETWORK_RESOURCE_RECEIVER_ITEM.get()" in main
          and "NETWORK_RESOURCE_REQUESTER_ITEM.get()" in main)
    check("②e 两个方块实体都注册了网络节点容器能力监听器",
          RECEIVER_BE + "::registerCapabilities" in main
          and REQUESTER_BE + "::registerCapabilities" in main)
    for be in (RECEIVER_BE, REQUESTER_BE):
        text = code_of("block/entity/%s.java" % be)
        check("②f %s：registerCapabilities 用网络节点容器能力 + getContainerProvider" % be,
              "getNetworkNodeContainerProviderCapability()" in text
              and "getContainerProvider()" in text)
    # 两个网络节点类必须在 network/ 包下（本工程一贯的架构位置）
    for node in ("NetworkResourceReceiverNetworkNode", "NetworkResourceRequesterNetworkNode"):
        path = os.path.join(JAVA, "network", node + ".java")
        check("②g %s 位于 network/ 包下" % node, os.path.isfile(path))
        if os.path.isfile(path):
            check("②h %s 声明了 network 包且继承 AbstractNetworkNode" % node,
                  read(path).startswith("package cretae.cookiewyq.rs_create_compat.network;")
                  and "extends AbstractNetworkNode" in read(path))


def check_screens_client_only():
    """③ 屏幕注册 + 屏幕类必须都在 client/ 包（专服安全硬规则）。"""
    client_init = code_of("client/ClientInit.java")
    # 第 131 轮：请求器设置界面整页删除 ⇒ 只注册接收器那一块；
    # 并且请求器菜单**不许**再被注册屏幕（注册了就是一个没有界面的死菜单）。
    check("③a ClientInit 注册了 NETWORK_RESOURCE_RECEIVER_MENU 的屏幕",
          "NETWORK_RESOURCE_RECEIVER_MENU" in client_init)
    check("③a' ClientInit **不再**注册 NETWORK_RESOURCE_REQUESTER_MENU（设置界面已删）",
          "NETWORK_RESOURCE_REQUESTER_MENU" not in client_init)
    receiver_scr = os.path.join(JAVA, "client", "screen", "NetworkResourceReceiverScreen.java")
    check("③b NetworkResourceReceiverScreen 位于 client/screen/ 包下",
          os.path.isfile(receiver_scr))
    if os.path.isfile(receiver_scr):
        text = read(receiver_scr)
        check("③c NetworkResourceReceiverScreen 声明了 client.screen 包且继承 RS GridScreen（第 132 轮）",
              text.startswith("package cretae.cookiewyq.rs_create_compat.client.screen;")
              and "extends GridScreen<NetworkResourceReceiverMenu>" in text)
    # 反向门禁：请求器界面必须真的删了（不是「读不到就跳过」）
    check("③c' 请求器设置界面文件必须不存在（第 131 轮整页删除）",
          not os.path.isfile(os.path.join(JAVA, "client", "screen",
                                          "NetworkResourceRequesterScreen.java")))
    # 反向断言：服务端可见的新类里不得出现客户端专属类型
    for rel in ("block/NetworkResourceReceiverBlock.java", "block/NetworkResourceRequesterBlock.java",
                "block/entity/%s.java" % RECEIVER_BE, "block/entity/%s.java" % REQUESTER_BE,
                "menu/NetworkResourceReceiverMenu.java", "menu/NetworkResourceRequesterMenu.java",
                "network/NetworkResourceReceiverNetworkNode.java",
                "network/NetworkResourceRequesterNetworkNode.java",
                "support/RsccSpeedStackUpgradeContainer.java"):
        text = code_of(rel)
        bad = [line.strip() for line in text.splitlines()
               if "net.minecraft.client." in line or "com.mojang.blaze3d." in line]
        check("③d %s 不含客户端专属类型" % rel, not bad, "; ".join(bad[:2]))


def check_upgrade_slots():
    """④⑤ 6 槽 / 白名单两类 / 三层校验（两台机器都有槽）。"""
    container = code_of("support/RsccSpeedStackUpgradeContainer.java")
    check("④a 6 槽的常量 = 6",
          re.search(r"public static final int SLOTS = 6;", container) is not None)
    check("④b 白名单字符串只含 speed_upgrade + stack_upgrade",
          'SPEED_PATH = "speed_upgrade"' in container and 'STACK_PATH = "stack_upgrade"' in container)
    check("④c 容器按 RS 命名空间 + path 判定（不按 Java 类：RS 里四类升级共用一个类）",
          '"refinedstorage".equals(id.getNamespace())' in container)
    check("⑤a 第 ② 层：非法 / 超限一律弹出到世界（extractItem + addFreshEntity）",
          "extractItem(slot, 1, false)" in container and "addFreshEntity" in container)
    check("⑤a2 第 ② 层上限取自 UpgradeSlot.maxForPath（与界面层同一处口径）",
          "UpgradeSlot.maxForPath(path)" in container)

    menu = code_of("menu/NetworkResourceReceiverMenu.java")
    check("⑤b 第 ① 层：接收器菜单循环建 6 个 UpgradeSlot 且白名单只有两类",
          "for (int i = 0; i < UPGRADE_COUNT; i++)" in menu
          and 'List.of("speed_upgrade", "stack_upgrade")' in menu
          and "new UpgradeSlot(" in menu)
    check("⑤c 接收器菜单槽数常量取自容器（唯一来源，不重复写 6）",
          "RsccSpeedStackUpgradeContainer.SLOTS" in menu)

    be_recv = code_of("block/entity/%s.java" % RECEIVER_BE)
    check("⑤d 接收器方块实体持有容器，且读档后统一校验一次",
          "RsccSpeedStackUpgradeContainer upgradeContainer" in be_recv
          and "upgradeContainer.finishLoading()" in be_recv
          and "upgradeContainer.validate()" in be_recv)

    req_be = code_of("block/entity/%s.java" % REQUESTER_BE)
    req_menu = code_of("menu/NetworkResourceRequesterMenu.java")
    # 【第 131 轮反转】用户拍板「发送器跟插件槽没关系，红石激活的速度决定发送速度」⇒
    # 请求器的 6 个插件槽删除，节拍改成 SenderDelivery 的两个固定常量。
    # 判据从「请求器也有 6 槽」反转成「请求器侧升级通道零残留 + 节拍读固定常量」。
    check("④d 请求器**没有**升级槽（容器 / 菜单 UpgradeSlot / 白名单引用全无）",
          "RsccSpeedStackUpgradeContainer upgradeContainer" not in req_be
          and "RsccSpeedStackUpgradeContainer.Host()" not in req_be
          and "new UpgradeSlot(" not in req_menu
          and "ALLOWED_UPGRADES" not in req_menu
          and "UPGRADE_COUNT" not in req_menu)
    check("④e 请求器也不再落盘升级（TAG_UPGRADES / finishLoading / validate 全无），"
          "节拍改由 SenderDelivery 固定常量给出",
          "TAG_UPGRADES" not in req_be
          and "upgradeContainer.finishLoading()" not in req_be
          and "upgradeContainer.validate()" not in req_be
          and "SenderDelivery.FIXED_BATCHES_PER_TICK" in req_be
          and "SenderDelivery.FIXED_BATCH_SIZE" in req_be)

    dest = code_of("RegisterUpgradeDestinations.java")
    check("⑤e 第 ③ 层：RS 升级注册表登记了接收器 + 速度/叠加各 6",
          'register("network_resource_receiver"' in dest
          and '"speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE' in dest
          and '"stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE' in dest)
    block = re.search(r'register\("network_resource_receiver"[\s\S]*?\);', dest)
    check("⑤f 注册表里接收器那一组**只**有这两类（没有 range / autocrafting / creative）",
          block is not None
          and "range_upgrade" not in block.group(0)
          and "autocrafting_upgrade" not in block.group(0)
          and "creative_range_upgrade" not in block.group(0),
          block.group(0)[:160] if block else "没找到 register(\"network_resource_receiver\") 调用")


def check_r4():
    """⑥ R4：sequence_assembly_executor 不再出现在升级目的地表里。"""
    # 剥注释：R4 的**修正说明**就在类注释里，必须能提到那个方块名；
    # 这里断言的是「**代码/表**里不再有它」，所以只看剥掉注释后的源码。
    dest = assertions_text("RegisterUpgradeDestinations.java")
    check("⑥a RegisterUpgradeDestinations 的**表**里不再有 sequence_assembly_executor",
          "sequence_assembly_executor" not in dest,
          "仍出现在：%s" % [line.strip() for line in dest.splitlines()
                            if "sequence_assembly_executor" in line][:3])
    # 该方块的菜单确实没有升级槽 —— 这是「删掉那 3 行」的依据，别让它被无声改回来
    exec_menu = code_of("menu/SequenceAssemblyExecutorMenu.java")
    check("⑥b 依据仍在：序列装配执行器菜单没有任何 UpgradeSlot",
          "UpgradeSlot" not in exec_menu)


LANG_KEYS = [
    "block.rs_create_compat.network_resource_receiver",
    "block.rs_create_compat.network_resource_receiver.help",
    "block.rs_create_compat.network_resource_requester",
    "block.rs_create_compat.network_resource_requester.help",
    "gui.rs_create_compat.network_resource_receiver.upgrades",
    "gui.rs_create_compat.network_resource_receiver.status.named",
    "gui.rs_create_compat.network_resource_receiver.status.unnamed",
    "gui.rs_create_compat.network_resource_requester.redstone.hint",
    "gui.rs_create_compat.network_resource_requester.status.configured",
    "gui.rs_create_compat.network_resource_requester.status.unconfigured",
    "gui.rs_create_compat.network_resource_requester.trigger.pending",
    "gui.rs_create_compat.network_resource_requester.empty",
]


def check_lang():
    """⑦ 语言键中英齐备 + 非空 + 中文侧确实是中文。"""
    zh = read_json(os.path.join(ASSETS, "lang", "zh_cn.json"))
    en = read_json(os.path.join(ASSETS, "lang", "en_us.json"))
    check("⑦a 12 个新键在 zh_cn 全部存在",
          all(k in zh for k in LANG_KEYS),
          "; ".join(k for k in LANG_KEYS if k not in zh))
    check("⑦b 12 个新键在 en_us 全部存在",
          all(k in en for k in LANG_KEYS),
          "; ".join(k for k in LANG_KEYS if k not in en))
    empty = [k for k in LANG_KEYS
             if not str(zh.get(k, "")).strip() or not str(en.get(k, "")).strip()]
    check("⑦c 新键都不是空值", not empty, "; ".join(empty))
    # 中文侧必须是中文（出现过「zh_cn 里被写成英文」的真实事故，这一条是回归防线）
    ascii_zh = [k for k in LANG_KEYS if k in zh and str(zh[k]).isascii()]
    check("⑦d zh_cn 侧的新键都是中文（不是英文占位）", not ascii_zh, "; ".join(ascii_zh))
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    check("⑦e zh_cn / en_us 键集合完全一致",
          not only_zh and not only_en,
          "仅 zh %s / 仅 en %s" % (only_zh[:3], only_en[:3]))
    for ident in (RECEIVER, REQUESTER):
        key = "block.rs_create_compat.%s" % ident
        check("⑦f %s 有方块名键" % key, key in zh and key in en)
        help_key = key + ".help"
        check("⑦g %s 有帮助键（RsccHelpBlockItem 用）" % help_key, help_key in zh and help_key in en)


def main():
    print("=== 第 67 轮自检：网络资源接收器 / 请求器（第一批 骨架 + 注册 + 语言键） ===")
    check_variants()
    check_registration()
    check_screens_client_only()
    check_upgrade_slots()
    check_r4()
    check_lang()
    print("")
    print("断言总数 %d，失败 %d" % (CHECKS[0], len(FAILURES)))
    for failure in FAILURES:
        print("  FAIL %s" % failure)
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
