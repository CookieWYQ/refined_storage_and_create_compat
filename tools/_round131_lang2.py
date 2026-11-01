# -*- coding: utf-8 -*-
"""第 131 轮 D 组 lang 修复 + 落盘（幂等）：先删掉 D 组相关旧行，再按正确逗号重新追加。"""
import json
import re
from pathlib import Path

ROOT = Path(r"D:\MODS\refined_storage_and_create_compat")
P = "gui.rs_create_compat."

NEW = {
    P + "advanced_remote_terminal.mode.send": ("发送", "Send"),
    P + "terminal_send.msg.no_terminal": (
        "这台高级远程终端已经不在你身上，无法发送",
        "This advanced remote terminal is no longer with you; cannot send"),
    P + "terminal_send.msg.no_network": (
        "这台终端还没有绑定可用的 RS 网络，无法发送（先对着网络节点潜行右键绑定）",
        "This terminal is not bound to a usable RS network; cannot send "
        "(sneak-right-click a network block to bind)"),
    P + "terminal_send.msg.no_power": (
        "绑定的网络没有电（或未激活），本轮不发送；订单与目标原样保留",
        "The bound network has no power (or is inactive); nothing was sent; orders and target "
        "are kept"),
    P + "terminal_send.msg.unconfigured": (
        "这台终端还没有选择目标接收器，无法发送",
        "No target receiver selected on this terminal; cannot send"),
    P + "terminal_send.msg.no_orders": (
        "订单是空的：先在网格里点选要发送的资源",
        "The order list is empty: click resources in the grid to add them"),
    P + "terminal_send.msg.pending_carry": (
        "单次发送上限已到，还有 %s 件没投出去；再点一次发送接着投",
        "Single-send limit reached; %s item(s) not delivered yet — press Send again to continue"),
    P + "terminal_send.msg.order_not_saved": (
        "订单没能写进终端物品（编码失败），当前会话仍生效；请把日志发给作者",
        "The order list could not be written into the terminal item (encoding failed); it still "
        "works this session — please send the log to the author"),
    P + "terminal_send.msg.buffered_to_player": (
        "网络拒收回滚的 %s ×%s，已放进你的背包 / 掉在脚边（不会销毁）",
        "The network refused to take back %s ×%s, so it was put into your inventory / dropped at "
        "your feet (never destroyed)"),
    P + "terminal_send.msg.buffered_retry_later": (
        "网络拒收回滚的 %s ×%s，已暂存在终端里，下次发送时重试归还网络",
        "The network refused to take back %s ×%s; it is held temporarily and will be returned to "
        "the network on your next send"),
    P + "network_resource_requester.fail.deliver_exception": (
        "投递过程中出现异常：%s", "Exception while delivering: %s"),
}


def key_of(line: str):
    m = re.match(r'\s*"((?:[^"\\]|\\.)*)"\s*:', line)
    return m.group(1) if m else None


for name, col in (("zh_cn.json", 0), ("en_us.json", 1)):
    path = ROOT / "src/main/resources/assets/rs_create_compat/lang" / name
    lines = path.read_text(encoding="utf-8").split("\n")
    kept = [ln for ln in lines if key_of(ln) not in NEW]
    # 找结尾 } 行
    end = len(kept) - 1
    while end >= 0 and kept[end].strip() == "":
        end -= 1
    assert kept[end].strip() == "}", (name, kept[end])
    body = kept[:end]
    while body and body[-1].strip() == "":
        body.pop()
    if not body[-1].rstrip().endswith(","):
        body[-1] = body[-1].rstrip() + ","
    add = ["  " + json.dumps(k, ensure_ascii=False) + ": " + json.dumps(v[col], ensure_ascii=False)
           + ("," if i < len(NEW) - 1 else "")
           for i, (k, v) in enumerate(NEW.items())]
    text = "\n".join(body + add + ["}"]) + "\n"
    path.write_text(text, encoding="utf-8")
    data = json.loads(path.read_text(encoding="utf-8"))
    print(name, "keys", len(data), "missing", [k for k in NEW if k not in data])
