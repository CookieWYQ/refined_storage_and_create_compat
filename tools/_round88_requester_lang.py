# -*- coding: utf-8 -*-
"""按 2026-10-23 的界面重构调整请求器 / 网格选资源的语言键（两边成对）。"""
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

REQ = "gui.rs_create_compat.network_resource_requester."
PICK = "gui.rs_create_compat.resource_picker."

ZH = {
    "block.rs_create_compat.network_resource_requester.help":
        "红石脉冲或界面按钮触发；把已选资源投递给你选定的那台接收器。",
    REQ + "status.unconfigured": "尚未设定目标",
    REQ + "upgrade.speed.effect": "速度升级：同一段时间里多送几次",
    REQ + "upgrade.stack.effect": "叠加升级：每次多送一些",
    REQ + "order.resource.grid": "网格（已选 %s）",
    REQ + "msg.order_added": "已加入",
    REQ + "msg.order_removed": "已移除",
    PICK + "selected.header": "已选 %s 种",
    PICK + "selected.empty": "（还没选中资源：点网格里的资源加入）",
    PICK + "selected.more": "…还有 %s 种未显示",
    PICK + "msg.no_network": "请求器未接入可用的 RS 网络，无法打开网格",
}

EN = {
    "block.rs_create_compat.network_resource_requester.help":
        "Redstone pulse or GUI button; delivers the selected resources to the receiver you picked.",
    REQ + "status.unconfigured": "No target set yet",
    REQ + "upgrade.speed.effect": "Speed upgrades: more deliveries in the same time",
    REQ + "upgrade.stack.effect": "Stack upgrades: more per delivery",
    REQ + "order.resource.grid": "Grid (%s selected)",
    REQ + "msg.order_added": "Added",
    REQ + "msg.order_removed": "Removed",
    PICK + "selected.header": "Selected: %s",
    PICK + "selected.empty": "(nothing selected yet: click a resource in the grid)",
    PICK + "selected.more": "...%s more not shown",
    PICK + "msg.no_network": "The requester is not connected to a usable network; the grid cannot be opened",
}

# 玩家可见文本里不再出现的概念 / 技术词（整键删除）
DELETE = [
    REQ + "throttle",
    REQ + "upgrade.one_by_one",
    REQ + "order.add",
    REQ + "order.remove",
    REQ + "order.apply",
    REQ + "order.amount",
    REQ + "order.header",
    REQ + "order.empty",
    REQ + "order.resource.none",
    REQ + "order.resource.button",
    REQ + "msg.order_too_many",
]

# 新增键：插在锚点键之后（保持分组可读）
ZH_INSERT = {
    REQ + "status.unconfigured": [(REQ + "status.no_network",
                                  "未接入可用的 RS 网络：请把请求器接上网络并供电")],
    REQ + "upgrade.stack.effect": [(REQ + "upgrade.pacing",
                                    "节奏：每 %s tick 一批，每批 %s 件")],
    REQ + "order.resource.grid": [
        (REQ + "grid.hint", "打开网格：选资源、改数量、直接发送"),
        (REQ + "grid.unavailable", "未接入可用的 RS 网络，无法打开网格"),
    ],
    PICK + "msg.no_network": [
        (PICK + "panel.amount", "数量"),
        (PICK + "panel.apply", "应用"),
        (PICK + "panel.send", "发送"),
        (PICK + "panel.hint", "左键点资源加入，右键点已选移除"),
    ],
}

EN_INSERT = {
    REQ + "status.unconfigured": [(REQ + "status.no_network",
                                  "Not connected to a usable network: wire it up and power it")],
    REQ + "upgrade.stack.effect": [(REQ + "upgrade.pacing",
                                    "Pacing: one batch every %s ticks, %s items per batch")],
    REQ + "order.resource.grid": [
        (REQ + "grid.hint", "Open the grid: pick resources, set amounts, send"),
        (REQ + "grid.unavailable", "Not connected to a usable network; the grid cannot be opened"),
    ],
    PICK + "msg.no_network": [
        (PICK + "panel.amount", "Amount"),
        (PICK + "panel.apply", "Apply"),
        (PICK + "panel.send", "Send"),
        (PICK + "panel.hint", "Left click adds, right click removes"),
    ],
}


def apply(path, updates, inserts):
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    out = {}
    for key, value in data.items():
        if key in DELETE:
            pass
        else:
            out[key] = updates.get(key, value)
        for new_key, new_value in inserts.get(key, []):
            out[new_key] = new_value
    for key, value in updates.items():
        if key not in out:
            raise SystemExit("missing update anchor: %s" % key)
    for anchor, items in inserts.items():
        for new_key, _value in items:
            if new_key not in out:
                raise SystemExit("missing insert anchor: %s (new %s)" % (anchor, new_key))
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(out, ensure_ascii=False, indent=2))
        handle.write("\n")
    print("%s: %d keys" % (os.path.basename(path), len(out)))


apply(os.path.join(LANG, "zh_cn.json"), ZH, ZH_INSERT)
apply(os.path.join(LANG, "en_us.json"), EN, EN_INSERT)
