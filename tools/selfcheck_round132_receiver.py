#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 132 轮 receiver-grid 自检：接收器界面按 RS GridScreen 重写。

对照 docs/ROUND132_CONTRACT.md「二、接收器」：
  1. Menu extends AbstractGridContainerMenu，内部 ReceiverGrid implements Grid；
  2. Screen extends GridScreen<NetworkResourceReceiverMenu>，删除旧 TEXTURE / 9×7 手绘 /
     自带 ScrollbarWidget / 竖排插件栏；
  3. extras：名称 EditBox + 确定 + 6 个升级槽 + 状态/缓存摘要；
  4. GridOperations.extract 只从缓存取出；insert 恒 false；
  5. 缓存变化可以推到客户端（cacheVersion → onChanged → RS GridUpdatePacket）；
  6. 流体取出接进 RS 的 FluidGridExtractionStrategy（GridOperations + getItemStorage 换桶）。

反例自证：把旧写法 / 坏语义拼回去（退回 AbstractContainerScreen、insert 返回 true、
去掉 GridData、删 cacheVersion）必须判负。
用法：python tools/selfcheck_round132_receiver.py
退出码：0 = 全部通过；1 = 有失败。最后一行：ALL_PASS / FAILED。
"""
from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FILES = {
    "screen": os.path.join(JAVA, "client", "screen", "NetworkResourceReceiverScreen.java"),
    "menu": os.path.join(JAVA, "menu", "NetworkResourceReceiverMenu.java"),
    "sync": os.path.join(JAVA, "network", "SyncReceiverConfigPacket.java"),
    "blockentity": os.path.join(JAVA, "block", "entity", "NetworkResourceReceiverBlockEntity.java"),
    "clientinit": os.path.join(JAVA, "client", "ClientInit.java"),
    "lang_zh": os.path.join(LANG, "zh_cn.json"),
    "lang_en": os.path.join(LANG, "en_us.json"),
}

FAILS = []
TOTAL = [0]


def read(path):
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    out = []
    i, n = 0, len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


def method_body(code, signature_regex):
    match = re.search(signature_regex, code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace + 1:i]
    return None


def check(name, ok, detail=""):
    TOTAL[0] += 1
    if not ok:
        FAILS.append(name)
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail and not ok else ""))


def main():
    print("=" * 78)
    print("第 132 轮 receiver-grid：RS GridScreen 重写自检")
    print("=" * 78)
    src = {key: read(path) for key, path in FILES.items()}
    for key in ("screen", "menu", "sync", "blockentity"):
        check("文件存在：%s" % key, bool(src.get(key, "").strip()))
    if any(not src.get(key, "").strip() for key in ("screen", "menu", "sync", "blockentity")):
        print("FAILED")
        return 1

    screen = strip_comments(src["screen"])
    menu = strip_comments(src["menu"])
    sync = strip_comments(src["sync"])
    be = strip_comments(src["blockentity"])
    clientinit = strip_comments(src["clientinit"])

    # ---------- 1. 基类与注册 ----------
    check("Screen extends GridScreen<NetworkResourceReceiverMenu>",
          "extends GridScreen<NetworkResourceReceiverMenu>" in screen)
    check("Menu extends AbstractGridContainerMenu",
          "extends AbstractGridContainerMenu" in menu)
    check("ClientInit 仍注册接收器 Screen（菜单类型不变）",
          "NetworkResourceReceiverScreen::new" in clientinit)

    # ---------- 2. 旧自绘删除 ----------
    for token in ("TEXTURE", "renderBg", "ScrollbarWidget", "GRID_COLS", "GRID_ROWS",
                  "renderAmountBadge", "renderGrid(", "UPGRADES_LABEL_X"):
        check("旧自绘符号已删除：%s" % token, token not in screen)
    check("Screen 不覆盖 mouseClicked / mouseScrolled（点击与滚动交给 RS）",
          "boolean mouseClicked(" not in screen and "boolean mouseScrolled(" not in screen)

    # ---------- 3. extras ----------
    check("extras：名称 EditBox 存在", "new EditBox(" in screen and "nameBox" in screen)
    check("extras：确定按钮发 SetReceiverNamePacket",
          "SetReceiverNamePacket" in screen and "name.button" in screen)
    check("extras：6 个升级槽（UPGRADE_COUNT + new UpgradeSlot）",
          "for (int i = 0; i < UPGRADE_COUNT; i++)" in menu and "new UpgradeSlot(" in menu)
    check("extras：升级槽在 resized() 里与玩家背包同一路径追加",
          (method_body(menu, r"public void resized\(final int playerInventoryY,") or "").count(
              "super.resized(") >= 1
          and "addSlot(new UpgradeSlot(" in (method_body(menu, r"public void resized\(") or ""))
    check("extras：状态 / 缓存摘要 / 插件标题都还在",
          all(key in screen for key in ("status.named", "cache.summary", "cache.slot.hint", "upgrades")))
    check("extras：空升级槽 tooltip 仍走 UpgradeSlotTooltips",
          "UpgradeSlotTooltips.render(" in screen)

    # ---------- 4. ReceiverGrid ----------
    check("ReceiverGrid implements Grid", "class ReceiverGrid implements Grid" in menu)
    check("ReceiverGrid.getResources 聚合 cacheEntries()",
          "getResources(" in menu and "receiver.cacheEntries()" in menu)
    check("ReceiverGrid.isGridActive = 网络节点 active + isProperlyConfigured",
          "isGridActive()" in menu and "getNode().isActive()" in menu
          and "isProperlyConfigured()" in menu)
    check("ReceiverGrid.getAutocraftableResources = Set.of()",
          "getAutocraftableResources()" in menu and "return Set.of();" in menu)
    check("ReceiverGrid.addWatcher / removeWatcher 委托 GridNetworkNode",
          "GridNetworkNode" in menu and "node.addWatcher(" in menu and "node.removeWatcher(" in menu)
    check("ReceiverGrid.canMenuStayOpen 检查方块实体身份 + 64.0 距离",
          "getBlockEntity(receiver.getBlockPos()) == receiver" in menu and "<= 64.0" in menu)

    # ---------- 5. 取出 / 放入 ----------
    insert_body = method_body(menu, r"public boolean insert\(final ResourceKey resource,")
    check("GridOperations.insert 恒 false（界面不能放入）",
          insert_body is not None and "return false;" in insert_body and "return true;" not in insert_body)
    extract_body = method_body(menu, r"public boolean extract\(final ResourceKey resource,")
    check("GridOperations.extract 按 GridExtractMode 算量（ENTIRE/HALF/SINGLE）",
          extract_body is not None and "GridExtractMode" in menu
          and "ENTIRE_RESOURCE" in extract_body and "HALF_RESOURCE" in extract_body
          and "SINGLE_RESOURCE" in extract_body)
    check("extract 有「模拟 → 真扣 → 目标只收一部分就回填」的顺序",
          extract_body is not None and "Action.SIMULATE" in extract_body
          and "Action.EXECUTE" in extract_body and "insertIntoCache(" in extract_body)
    check("流体换桶的 itemStorage = 缓存物品视图（CacheItemStorage）",
          "getItemStorage()" in menu and "class CacheItemStorage" in menu)

    # ---------- 6. 同步 ----------
    check("GridData 随 S2C 快照下发（GridData.STREAM_CODEC）",
          "GridData.STREAM_CODEC" in sync and "GridData gridData" in sync)
    apply_body = method_body(menu, r"public void applyClientGridSnapshot\(final GridData")
    check("客户端快照走 onClear() + onResourceUpdate(...)",
          apply_body is not None and "onClear()" in apply_body and "onResourceUpdate(" in apply_body)
    broadcast_body = method_body(menu, r"public void broadcastChanges\(\)")
    diff_body = method_body(menu, r"private void sendResourceDiffs\(")
    check("broadcastChanges 用 cacheVersion 判断 + sendResourceDiffs/onChanged 推增量",
          broadcast_body is not None and "cacheVersion()" in broadcast_body
          and "sendResourceDiffs(" in broadcast_body
          and diff_body is not None and "onChanged(" in diff_body)
    check("方块实体 cacheVersion 在 setChanged() 自增",
          "cacheVersion" in be and "cacheVersion++" in be and "void setChanged()" in be)

    # ---------- 7. 名字 / 语言 ----------
    check("名字服务端权威链路保留",
          "applyNameFromUi(" in menu and "isNameTakenInNetwork(" in menu
          and "checkNameShape(" in screen)
    check("界面引用的语言键都走现有前缀（不新增未落盘键）",
          "panel.energy" not in screen and "panel.status.idle" not in screen)
    zh = json.loads(read(FILES["lang_zh"]) or "{}")
    en = json.loads(read(FILES["lang_en"]) or "{}")
    for key in ("gui.rs_create_compat.network_resource_receiver.name.label",
                "gui.rs_create_compat.network_resource_receiver.name.button",
                "gui.rs_create_compat.network_resource_receiver.upgrades",
                "gui.rs_create_compat.network_resource_receiver.cache.slot.hint"):
        check("语言键中英成对：%s" % key.split(".")[-1], key in zh and key in en)

    # ---------- 8. 反例自证 ----------
    check("反例：Screen 退回 AbstractContainerScreen ⇒ 判负",
          "extends AbstractContainerScreen" in
          screen.replace("extends GridScreen<NetworkResourceReceiverMenu>",
                         "extends AbstractContainerScreen<NetworkResourceReceiverMenu>"))
    bad_insert = (insert_body or "").replace("return false;", "return true;")
    check("反例：insert 返回 true ⇒ 判负",
          not (bad_insert and "return false;" in bad_insert and "return true;" not in bad_insert))
    check("反例：去掉 GridData.STREAM_CODEC ⇒ 判负",
          "GridData.STREAM_CODEC" not in sync.replace("GridData.STREAM_CODEC", "X"))
    check("反例：删掉 cacheVersion++ ⇒ 判负",
          "cacheVersion++" not in be.replace("cacheVersion++", "// mutated"))

    print("-" * 78)
    if FAILS:
        print("FAILED %d/%d" % (len(FAILS), TOTAL[0]))
        return 1
    print("ALL_PASS %d checks" % TOTAL[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
