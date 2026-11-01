# -*- coding: utf-8 -*-
"""第 131 轮：把 sender / terminal-send 报上来的语言键一次性写进 zh_cn / en_us。

原则（本轮纪律）：
- **只加键 + 改已有键的文案**，不删键（删键会让历史自检 round95/96 等无谓变红；死键对玩家不可见，
  本轮只保证「没有在用的撒谎文案」被改掉）。
- 键的插入位置：文件末尾 `}` 之前（保持两空格缩进与逗号格式）；已有键原地改值。
- 幂等：同一份表重复跑只会覆盖成同一份文案，不会重复插键。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(r"D:\MODS\refined_storage_and_create_compat")
ZH = ROOT / "src/main/resources/assets/rs_create_compat/lang/zh_cn.json"
EN = ROOT / "src/main/resources/assets/rs_create_compat/lang/en_us.json"

P = "gui.rs_create_compat."

# ---- 新增键（zh, en） ----
NEW = {
    # 接收器选择子界面（sender_target_select.*）
    P + "sender_target_select.title": ("选择接收器", "Select Receiver"),
    P + "sender_target_select.current": ("目标：%s", "Target: %s"),
    P + "sender_target_select.current.none": ("目标：未选择", "Target: not set"),
    P + "sender_target_select.empty": (
        "本网络还没有已命名的接收器：先在接收器上给它命名",
        "No named receivers on this network yet — name one on the receiver first"),
    P + "sender_target_select.search_hint": ("搜索接收器名字", "Search receiver name"),
    P + "sender_target_select.hint": (
        "只列出本网络里已命名的接收器", "Only named receivers on this network are listed"),
    P + "sender_target_select.confirm": ("确定", "Confirm"),
    P + "sender_target_select.cancel": ("取消", "Cancel"),
    P + "sender_target_select.search.tip": ("按名字搜索：%s / %s 台", "Search by name: %s of %s"),
    P + "sender_target_select.confirm.tip": (
        "把目标设为选中的接收器并返回", "Set the target to the selected receiver and return"),
    P + "sender_target_select.cancel.tip": (
        "放弃并返回发送界面", "Discard and return to the send screen"),

    # 自动合成询问（sender_craft_prompt.*）
    P + "sender_craft_prompt.title": (
        "库存不足，可以自动合成", "Not enough in storage — automatic crafting available"),
    P + "sender_craft_prompt.line": (
        "目标 %s · 现有 %s · 最多可合成 %s · 还缺 %s",
        "Target %s · Have %s · Craftable %s · Missing %s"),
    P + "sender_craft_prompt.craft": ("合成 %s 并发送 %s", "Craft %s and send %s"),
    P + "sender_craft_prompt.send_only": ("只发送 %s", "Send %s only"),
    P + "sender_craft_prompt.craft.tip": (
        "立刻发送现有的部分，同时合成 %s 个；剩余缺口在凑齐后自动补投（最多等 10 分钟）",
        "Send what's available now and craft %s; the gap is re-delivered automatically once "
        "available (waits up to 10 minutes)"),
    P + "sender_craft_prompt.send_only.tip": (
        "只发送现在能发的 %s 个；还缺 %s 个不会补投，也不会自动合成",
        "Send only the %s available now; the missing %s are neither crafted nor re-delivered"),

    # 网格：固定节拍提示（不再吃升级）
    P + "resource_picker.toolbar.rate.hint": (
        "节拍固定：每 tick %s 批、每批 %s 件（红石脉冲多快就发多快，不吃升级）",
        "Fixed pace: %s batches/tick, %s each (upgrades can't change it; redstone pulses drive "
        "the speed)"),

    # 缺口补投的反馈（无占位符）
    P + "network_resource_requester.msg.shortage_sent_only": (
        "只发送了现有的部分；缺口不会自动补投（要合成请重新点「发送」并在询问里选「合成」）",
        "Sent only what was available; the gap won't be delivered later (click Send again and "
        "choose Craft if you want it)"),
    P + "network_resource_requester.msg.shortage_crafting": (
        "已发送现有部分，并对缺口启动了自动合成；凑齐后会自动补投（最多等 10 分钟）",
        "Sent the available part and started crafting the gap; it will be re-delivered "
        "automatically once ready (up to 10 minutes)"),
    P + "network_resource_requester.msg.shortage_gave_up": (
        "补投超时（10 分钟）：自动合成没有凑齐，本次补投已放弃（资源仍在网络里）",
        "Re-delivery timed out (10 minutes): crafting did not finish, so this re-delivery was "
        "abandoned (the resources stay in the network)"),
    P + "network_resource_requester.msg.shortage_expired": (
        "这个询问已经过期或已经回答过，请重新点「发送」",
        "This prompt expired or was already answered — click Send again"),

    # 投递层兜底（带占位符，代码里 fallback 是中文）
    P + "network_resource_requester.fail.shortage_gave_up": (
        "补投超时（10 分钟）：接收器「%s」还差 %s，自动合成没有凑齐，已放弃本次补投",
        "Re-delivery timed out (10 minutes): receiver \"%s\" still needs %s; crafting did not "
        "finish, so this re-delivery was abandoned"),
    P + "network_resource_requester.fail.no_storage": (
        "RS 网络里没有可用的存储，无法取料",
        "The RS network has no usable storage; nothing can be extracted"),
    P + "network_resource_requester.fail.conservation_broken": (
        "投递守恒校验失败（取出 %s / 写入 %s / 回滚 %s / 暂存 %s），请把日志发给作者",
        "Delivery conservation check failed (extracted %s / inserted %s / rolled back %s / "
        "buffered %s); please send the log to the author"),
}

# ---- 改文案（老键，不新增） ----
UPDATE = {
    P + "resource_picker.toolbar.target.hint": (
        "点开接收器列表，搜索并选用一台",
        "Open the receiver list, search and pick one"),
}


def line_for(key: str, value: str) -> str:
    return '  ' + json.dumps(key, ensure_ascii=False) + ': ' + json.dumps(value, ensure_ascii=False) + ',\n'


def apply(path: Path, col: int) -> None:
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")
    # 1) 改已有键（整行替换，保留行尾逗号）
    for key, pair in UPDATE.items():
        pat = re.compile(r'^\s*' + re.escape(json.dumps(key, ensure_ascii=False)) + r'\s*:\s*.*?,\s*$')
        hit = 0
        for i, line in enumerate(lines):
            if pat.match(line):
                lines[i] = line_for(key, pair[col]).rstrip("\n")
                hit += 1
        if hit != 1:
            print("WARN update 命中数=%d %s" % (hit, key))
    text = "\n".join(lines)
    # 2) 新增键（已存在就跳过）
    existing = set(re.findall(r'"((?:[^"\\]|\\.)*)"\s*:', text))
    add = [line_for(k, v[col]) for k, v in NEW.items() if k not in existing]
    # 插在最后一个 "}" 行之前
    m = re.search(r'\n\}\s*$', text)
    if not m:
        raise SystemExit("没有找到结尾的 }")
    body = text[:m.start()]
    if not body.rstrip().endswith(","):
        body = body.rstrip() + ","
    tail = text[m.start():]
    merged = body + "\n" + "".join(add) + tail.lstrip("\n")
    path.write_text(merged, encoding="utf-8")
    print("wrote", path.name, "added", len(add), "updated", len(UPDATE))


apply(ZH, 0)
apply(EN, 1)

# 3) 校验两份 JSON 都能解析、键集合与新增表一致
for p, col in ((ZH, 0), (EN, 1)):
    data = json.loads(p.read_text(encoding="utf-8"))
    missing = [k for k in NEW if k not in data]
    assert not missing, (p.name, missing)
    for k, pair in UPDATE.items():
        assert data.get(k) == pair[col], (p.name, k, data.get(k))
print("JSON_OK")
