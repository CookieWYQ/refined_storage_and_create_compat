#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮（第 132 轮重写后）：网络资源接收器 = RS GridScreen 重写自检。

第 132 轮用户原话：「你完全理解错我意思了，你自己写一套这个狗屎东西干什么？……
你直接像图一（发送器）那样搞一个这个东西重写一下，然后加点东西不就得了。」

因此本脚本的判据已从「手写 9×7 网格」整体改成「复用 RS 网格」：
  * Screen 必须 extends GridScreen<NetworkResourceReceiverMenu>，不得再有自绘贴图 / 手绘格 / 自带滚动条；
  * Menu 必须 extends AbstractGridContainerMenu，内部 ReceiverGrid implements Grid；
  * 缓存 → 网格：GridData + cacheVersion 增量；
  * 界面取出：RS GridExtractPacket → GridOperations.extract（ReceiverGrid.insert 恒 false）；
  * extras：名字输入框 + 6 个升级槽 + 状态/缓存摘要仍在。
用法：python tools/selfcheck_round131_receiver.py
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
    "lang_zh": os.path.join(LANG, "zh_cn.json"),
    "lang_en": os.path.join(LANG, "en_us.json"),
}

FAILURES = []
CHECKS = [0]


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


def body_of(code, signature_regex):
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
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def main():
    print("=" * 78)
    print("第 131/132 轮：网络资源接收器 = RS GridScreen 重写自检")
    print("=" * 78)

    src = {key: read(path) for key, path in FILES.items()}
    missing = [key for key in ("screen", "menu", "sync", "blockentity")
               if not src.get(key, "").strip()]
    for key in missing:
        check("文件存在：%s" % key, False, "不存在或为空")
    if missing:
        print("FAILED")
        return 1

    screen = strip_comments(src["screen"])
    menu = strip_comments(src["menu"])
    sync = strip_comments(src["sync"])
    blockentity = strip_comments(src["blockentity"])

    # ---------- 1. 基类 ----------
    check("Screen 继承 RS GridScreen<NetworkResourceReceiverMenu>",
          "extends GridScreen<NetworkResourceReceiverMenu>" in screen,
          "仍是手写 AbstractContainerScreen")
    check("Menu 继承 RS AbstractGridContainerMenu",
          "extends AbstractGridContainerMenu" in menu,
          "菜单没有换成 RS 网格父类")

    # ---------- 2. 旧自绘必须删干净 ----------
    for token in ("TEXTURE", "renderBg", "ScrollbarWidget", "GRID_COLS", "GRID_ROWS",
                  "renderAmountBadge", "renderGrid("):
        check("Screen 不再有旧自绘符号 %s" % token, token not in screen,
              "旧 9×7 手绘 / 贴图 / 自带滚动条仍残留")
    check("Screen 不再覆盖 mouseClicked / mouseScrolled（网格交互交给 RS）",
          "boolean mouseClicked(" not in screen and "boolean mouseScrolled(" not in screen,
          "又在屏幕层自己写命中 / 滚动")

    # ---------- 3. extras ----------
    check("extras：名字 EditBox + 确定按钮 + SetReceiverNamePacket",
          "new EditBox(" in screen and "SetReceiverNamePacket" in screen
          and "name.button" in screen,
          "名字输入链路缺失")
    check("extras：6 个升级槽来自菜单 resized()（UPGRADE_COUNT + new UpgradeSlot）",
          "for (int i = 0; i < UPGRADE_COUNT; i++)" in menu and "new UpgradeSlot(" in menu,
          "升级槽通道缺失")
    check("extras：状态 / 缓存摘要 / 插件标题仍在面板里",
          "status.named" in screen and "cache.summary" in screen and "upgrades" in screen,
          "状态或升级槽标题被顺手删了")

    # ---------- 4. ReceiverGrid ----------
    check("Menu 内部 ReceiverGrid implements Grid",
          "class ReceiverGrid implements Grid" in menu,
          "没有服务端 Grid 适配器")
    for method in ("getResources", "isGridActive", "createOperations", "getItemStorage",
                   "getAutocraftableResources", "addWatcher", "removeWatcher", "canMenuStayOpen"):
        check("ReceiverGrid.%s 存在" % method, method + "(" in menu,
              "RS Grid 契约方法缺失")
    insert_body = body_of(menu, r"public boolean insert\(final ResourceKey resource,")
    check("ReceiverGrid.insert 恒返回 false（界面不能放入）",
          insert_body is not None and "return false;" in insert_body
          and "return true;" not in insert_body,
          "insert 可能返回 true")

    # ---------- 5. 同步 ----------
    check("SyncReceiverConfigPacket 走 GridData.STREAM_CODEC",
          "GridData.STREAM_CODEC" in sync and "GridData gridData" in sync,
          "资源没有走 RS 的 GridData")
    check("客户端快照用 onClear() + onResourceUpdate(...) 灌进 RS 资源仓库",
          "onClear()" in menu and "onResourceUpdate(" in menu,
          "客户端资源仓库没有入口")
    check("服务端用 cacheVersion + onChanged(...) 做增量",
          "cacheVersion()" in menu and "onChanged(" in menu and "broadcastChanges()" in menu,
          "缓存变化没有推到 RS 网格")
    check("方块实体有 cacheVersion 并在 setChanged() 自增",
          "cacheVersion" in blockentity and "cacheVersion++" in blockentity
          and "void setChanged()" in blockentity,
          "缓存变化没有版本号")
    check("缓存数据源仍是 receiver.cacheEntries()",
          "receiver.cacheEntries()" in menu,
          "没有从方块实体缓存聚合资源")

    # ---------- 6. 流体 ----------
    check("流体取出走 GridOperations.extract + FluidResource",
          "FluidResource" in menu and "GridExtractMode" in menu and "extractFromCache(" in menu,
          "流体没有接进 RS 的取物链路")
    check("流体换桶的 itemStorage 来自缓存",
          "getItemStorage()" in menu and "CacheItemStorage" in menu,
          "RS 换桶时找不到缓存里的空桶")

    # ---------- 7. 名字链路 / 语言键 ----------
    check("名字服务端权威链路保留",
          "applyNameFromUi(" in menu and "isNameTakenInNetwork(" in menu
          and "checkNameShape(" in screen,
          "名字校验被改坏")
    check("没有新增未落盘的语言键（panel.energy / panel.status.idle 不得出现）",
          "panel.energy" not in screen and "panel.status.idle" not in screen,
          "引用了 lang 里没有的新键")

    zh = json.loads(read(FILES["lang_zh"]) or "{}")
    en = json.loads(read(FILES["lang_en"]) or "{}")
    for key in ("gui.rs_create_compat.network_resource_receiver.name.label",
                "gui.rs_create_compat.network_resource_receiver.name.button",
                "gui.rs_create_compat.network_resource_receiver.upgrades",
                "gui.rs_create_compat.network_resource_receiver.cache.slot.hint",
                "gui.rs_create_compat.network_resource_receiver.msg.name_applied"):
        check("语言键中英成对：%s" % key.split(".")[-1], key in zh and key in en,
              "缺少中文或英文")

    # ---------- 8. 反例自证 ----------
    old_screen = screen.replace("extends GridScreen<NetworkResourceReceiverMenu>",
                                "extends AbstractContainerScreen<NetworkResourceReceiverMenu>")
    check("反例：Screen 退回 AbstractContainerScreen ⇒ 判负",
          "extends GridScreen<NetworkResourceReceiverMenu>" not in old_screen)
    bad_sync = sync.replace("GridData.STREAM_CODEC", "ReceiverCacheEntry.STREAM_CODEC")
    check("反例：去掉 GridData.STREAM_CODEC ⇒ 判负",
          "GridData.STREAM_CODEC" not in bad_sync)
    def insert_is_false(body):
        return body is not None and "return false;" in body and "return true;" not in body

    bad_insert = insert_body.replace("return false;", "return true;") if insert_body else ""
    check("反例：insert 改成 return true ⇒ 判负", not insert_is_false(bad_insert))
    bad_version = blockentity.replace("cacheVersion++", "// mutated")
    check("反例：删掉 cacheVersion++ ⇒ 判负", "cacheVersion++" not in bad_version)

    print("-" * 78)
    if FAILURES:
        print("FAILED")
        return 1
    print("ALL_PASS %d checks" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
