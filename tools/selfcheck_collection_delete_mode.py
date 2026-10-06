# -*- coding: utf-8 -*-
"""自检：归流缓存仓「删除模式」全链路（2026-10-05 用户要求的复选框式清资源功能）。

用法：python tools/selfcheck_collection_delete_mode.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户原话（本自检逐条对应）
--------------------------
「它现在的逻辑是点击按钮然后清空缓存区，但我想要的是有一个状态——就是一个复选框那样的选择，
选择之后可以开启清除资源功能，然后清除的资源是指到了缓存仓里面到了缓存区或者说被收进来的资源。
但是具体来说这又有再加一层过滤，就是匹配区那里还可以使用某种方式在给它编辑一下，使它能够再进行
编辑一下就是最终要被删除的资源。首先先要开启那个删除模式，然后这个匹配的资源，然后这个要删除的
资源首先要在匹配资源里面找，找到的都是……要对匹配区的那些标记的物品再次进行这种标记是否要表达
他们删除，然后标记要删除的物品在开启了删除模式下才会被删除。然后这一个速度和删除的速度和流经
网络的速度保持一致，所以说这一个删除的速度也会受到速度升级和堆叠升级的影响。」

拆成可断言的规则
----------------
D1 总闸存在且**默认关闭**（老存档读出来也是关闭）——读档绝不能自己开始销毁资源；
D2 销毁路径**必须**同时满足「总闸开启」+「命中标记为删除的匹配槽」；
D3 速率与回流同源：每 tick 处理组数 = getProcessRate()、单次吞吐 = getTransferBatch()
   （即速度升级 / 堆叠升级同时影响删除速度）；
D4 开启必须带确认位（C2S 包 + 服务端硬校验），关闭无需确认；
D5 界面有复选框（复用 McGui.toggle 三态控件），且「开启」只打开确认子窗口、不发包；
D6 状态经 ContainerData 同步（菜单槽号 → 方块实体槽号显式映射，两个方向都写到）；
D7 诊断导出包含 deleteMode / deleteRatePerTick / destroyMarkers（「我以为我开了」不再靠猜）；
D8 中英语言键成对存在（含确认子窗口文案）。
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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def main():
    block = read(SRC, "block", "entity", "CollectionCacheBlockEntity.java")
    menu = read(SRC, "menu", "CollectionCacheMenu.java")
    screen = read(SRC, "client", "screen", "CollectionCacheScreen.java")
    confirm = read(SRC, "client", "screen", "CollectionDeleteModeConfirmScreen.java")
    packet = read(SRC, "network", "SetCollectionDeleteModePacket.java")
    main_cls = read(SRC, "RS_Create_Compat.java")

    section("D1 总闸存在、默认关闭、读档不自行开启")
    check("方块实体有 deleteMode 字段（默认 false）",
          re.search(r"private boolean deleteMode;\s*$", block, re.M) is not None
          or "private boolean deleteMode;" in block)
    check("NBT 键存在且注释写明「读档不得自行开启」",
          'TAG_DELETE_MODE = "DeleteMode"' in block
          and "老存档没有这个键" in block)
    check("saveAdditional 落盘 deleteMode",
          "tag.putBoolean(TAG_DELETE_MODE, deleteMode);" in block)
    check("loadAdditional 读回 deleteMode（缺键 ⇒ 关闭）",
          "deleteMode = tag.getBoolean(TAG_DELETE_MODE);" in block)
    check("有服务端权威 setter（落 NBT + 记一条 ON/OFF 日志）",
          "public void setDeleteMode(final boolean enabled)" in block
          and "删除模式 = {}" in block)

    section("D2 销毁必须同时满足「总闸开」+「命中标记为删除的槽」")
    check("destroyMarkedContent 的第一道闸门是 deleteMode",
          "if (!deleteMode || destroyMarkers.isEmpty()) {" in block)
    check("命中判据只看「标记为删除」的槽（destroyMarkers）",
          "for (final int index : destroyMarkers) {" in block
          and "matchesAnyDestroyItemMarker(stack, registries)" in block)
    check("销毁仍走守恒账本 destroyed（与既有审计口径一致）",
          "flowLedger.destroyed(RsccFlowLedger.itemKey(stack.getItem()), amount);" in block)
    check("一键「销毁缓存区内容」保持独立（玩家显式确认的一次性全清）",
          "public long destroyAllCacheContent()" in block)

    section("D3 删除速率与回流同源（受速度 / 堆叠升级影响）")
    check("删除使用 getProcessRate() 作为每 tick 处理组数",
          "final int itemBudget = Math.max(1, getProcessRate());" in block)
    check("删除使用 getTransferBatch() 作为单次吞吐",
          "final int transfer = Math.max(1, getTransferBatch());" in block)
    check("单次销毁量被吞吐上限夹住（不是整格一次删光）",
          "final int amount = Math.min(stack.getCount(), transfer);" in block)
    check("暴露 getDeleteRatePerTick = 组数 × 吞吐（界面与快照同源）",
          "public long getDeleteRatePerTick()" in block
          and "Math.max(1, getProcessRate()) * Math.max(1, getTransferBatch())" in block)
    check("回流与删除读的是同一对升级量（口径不会分叉）",
          "int budget = getProcessRate();" in block
          and "final int transfer = getTransferBatch();" in block)

    section("D4 开启必须带确认位（服务端硬校验）")
    check("C2S 包具备 containerId / enabled / confirmed 三字段",
          "int containerId, boolean enabled," in packet
          and "boolean confirmed" in packet)
    check("未确认的「开启」被服务端直接忽略",
          "if (packet.enabled() && !packet.confirmed()) {" in packet)
    check("只接受「当前打开的菜单就是该容器」",
          "menu.containerId != packet.containerId()" in packet
          and "menu instanceof CollectionCacheMenu" in packet)
    check("包已在主类注册（漏注册会直接断开连接）",
          "SetCollectionDeleteModePacket.TYPE" in main_cls
          and "SetCollectionDeleteModePacket.STREAM_CODEC" in main_cls)

    section("D5 界面：复选框 + 开启走确认子窗口")
    check("屏幕有 deleteModeButton（复用 McGui.toggle 三态控件）",
          "private Button deleteModeButton;" in screen
          and "deleteModeButton = addRenderableWidget(McGui.toggle(" in screen)
    check("复选框状态来自服务端（menu.isDeleteMode()）",
          "() -> menu.isDeleteMode()" in screen)
    check("「开启」只打开确认窗口、不发包",
          "openDeleteModeConfirm();" in screen
          and "if (minecraft != null) {\n            minecraft.setScreen(new CollectionDeleteModeConfirmScreen(this, menu));" in screen)
    check("「关闭」立即发包（恢复安全状态，无需确认）",
          "new cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket(\n                            menu.containerId, false, true)" in screen)
    check("每帧回写服务端权威状态",
          "McGui.refreshToggle(deleteModeButton, menu.isDeleteMode());" in screen)
    check("确认窗口发送的是带确认位的开启包",
          "new SetCollectionDeleteModePacket(menu.containerId, true, true)" in confirm)
    check("确认窗口写清「会发生什么」（规则 + 不可恢复警告）",
          "confirm.rule" in confirm and "confirm.warning" in confirm)
    # 2026-10-05 用户要求去掉速率行（原话：「不要告诉也不需要说明删除的速率」）：
    # 这里反过来钉住「确认窗不再画速率」，防止有人又加回来。
    check("确认窗口不再画速率行（用户要求）",
          'confirm.rate"' not in confirm)

    section("D6 ContainerData 同步（菜单槽号 ↔ 方块实体槽号双向都写到）")
    check("菜单侧新增两个数据槽常量",
          "DATA_DELETE_MODE = 27" in menu and "DATA_DELETE_RATE = 28" in menu)
    check("菜单侧映射到方块实体槽 22 / 23",
          "BLOCK_DATA_DELETE_MODE = 22" in menu and "BLOCK_DATA_DELETE_RATE = 23" in menu)
    check("读取分支显式映射（否则恒 0 —— 红石模式曾经踩过这个坑）",
          "case DATA_DELETE_MODE -> base.get(BLOCK_DATA_DELETE_MODE);" in menu
          and "case DATA_DELETE_RATE -> base.get(BLOCK_DATA_DELETE_RATE);" in menu)
    check("数据槽总数扩到 29（够放两个新槽）",
          "DATA_SLOT_COUNT = 29;" in menu)
    check("方块实体侧提供 case 22 / 23",
          "case 22 -> deleteMode ? 1 : 0;" in block
          and "case 23 -> (int) Math.min(Integer.MAX_VALUE, getDeleteRatePerTick());" in block
          and "return 24;" in block)

    section("D7 诊断导出（「我以为我开了」不再靠猜）")
    check("快照含 deleteMode / deleteRatePerTick / destroyMarkers",
          'out.put("deleteMode", isDeleteMode());' in block
          and 'out.put("deleteRatePerTick", getDeleteRatePerTick());' in block
          and 'out.put("destroyMarkers", new java.util.ArrayList<Object>(destroyMarkers));' in block)

    section("D8 中英语言键成对")
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
    need = [key for key in zh if "collection_cache.delete_mode." in key]
    check("中文有删除模式相关键（≥ 12 条）", len(need) >= 12, "实际 %d" % len(need))
    missing_en = [key for key in need if key not in en]
    check("英文键集合一致", not missing_en, "英文缺键：%s" % missing_en)
    # 2026-10-05：`confirm.rate` 已按用户要求删除，因此不再要求它存在。
    for key in ("label", "tip", "rate", "confirm.title", "confirm.warning", "confirm.rule",
                "confirm.cache", "confirm.yes", "confirm.no"):
        full = "gui.rs_create_compat.collection_cache.delete_mode." + key
        check("键存在：%s" % key, full in zh and full in en)

    section("D9 文案宽度（用户明确反馈过「文字超出背景」）")
    # 两个确认窗口的面板宽度与「最宽文案」的估算像素宽（CJK 9px / ASCII 6px / 空格 4px）。
    # 这是静态估算：宁可留余量，绝不让文字压出背景 —— 且绘制端另有按像素硬截断兜底。
    def pixel_width(text):
        total = 0
        for ch in text:
            total += 9 if ord(ch) > 0x2E80 else (4 if ch == " " else 6)
        return total

    # 只量「画在面板里」的那几行；`*.tip` 是 tooltip（自带一个会换行的框），不受面板宽度约束。
    for panel, available, keys, label in (
        (208, 192, [k for k in zh if "collection_cache.delete_mode.confirm" in k
                    and not k.endswith(".tip")], "删除模式确认窗"),
        (232, 216, [k for k in zh
                    if "collection_cache.destroy." in k and "state." not in k
                    and not k.endswith(".tip")], "销毁确认窗（面板内文字）"),
    ):
        widest = max(pixel_width(zh[k].replace("%s", "00")) for k in keys)
        check("%s：最宽文案 %dpx ≤ 可用 %dpx（面板 %d）" % (label, widest, available, panel),
              widest <= available, "最宽 %dpx" % widest)
    # 两行「销毁」按钮：按钮 44px、可用约 40px
    btn_prefix = "gui.rs_create_compat.collection_cache.destroy."
    for key in ("button", "state.on", "state.off"):
        for lang, table in (("zh", zh), ("en", en)):
            text = table[btn_prefix + key]
            check("销毁按钮 %s 的 %s 放得下（%dpx ≤ 40）" % (lang, key, pixel_width(text)),
                  pixel_width(text) <= 40)

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
