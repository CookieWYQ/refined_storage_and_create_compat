# -*- coding: utf-8 -*-
"""把网络资源接收器 / 请求器的配置界面语言键插入 zh_cn / en_us（保持两文件键集一致）。

为什么用脚本插入而不是整份重写：语言文件是很大的手工维护 JSON（1170+ 键），
整份 load+dump 会重排缩进 / 转义，diff 巨大且容易踩到别的批次正在改的行。
这里只做**定点插入**：锚点是两个已有的「组末行」，插进去后立刻用 json 解析验证。
"""
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

RECEIVER_ANCHOR = '  "gui.rs_create_compat.network_resource_receiver.upgrades": '
REQUESTER_ANCHOR = '  "gui.rs_create_compat.network_resource_requester.upgrades": '

# (key 后缀, zh, en)；前缀在下面按组拼
RECEIVER_KEYS = [
    ("name.label", "接收器名字（同网络内唯一；留空不可用）",
     "Receiver name (unique in this network; empty = unusable)"),
    ("name.button", "确定", "Apply"),
    ("name.hint.empty", "名字不能为空，否则本机不可用",
     "The name cannot be empty; otherwise this machine will not work"),
    ("name.hint.too_long", "名字太长（最多 %s 个字符）", "Name is too long (max %s characters)"),
    ("name.hint.invalid", "名字里不能有控制字符或颜色码",
     "The name cannot contain control characters or colour codes"),
    ("name.hint.taken", "该名字已被同网络的另一台接收器占用",
     "That name is already used by another receiver in this network"),
    ("name.current", "当前名字：%s", "Current name: %s"),
    ("name.occupied", "本网络已占用的名字：%s", "Names already used in this network: %s"),
    ("name.occupied.none", "本网络还没有其它已命名的接收器",
     "No other named receiver in this network yet"),
    ("msg.name_applied", "接收器改名成功", "Receiver renamed"),
    ("msg.name_empty", "改名被拒绝：名字不能为空", "Rename rejected: the name cannot be empty"),
    ("msg.name_too_long", "改名被拒绝：名字太长", "Rename rejected: the name is too long"),
    ("msg.name_invalid", "改名被拒绝：名字含非法字符",
     "Rename rejected: the name contains invalid characters"),
    ("msg.name_taken", "改名被拒绝：本网络内已有同名接收器",
     "Rename rejected: that name is already taken in this network"),
]

REQUESTER_KEYS = [
    ("target.label", "目标接收器名字（须在本网络内存在）",
     "Target receiver name (must exist in this network)"),
    ("target.button", "确定", "Apply"),
    ("target.hint.empty", "目标名字不能为空", "The target name cannot be empty"),
    ("target.hint.too_long", "名字太长（最多 %s 个字符）", "Name is too long (max %s characters)"),
    ("target.hint.invalid", "名字里不能有控制字符或颜色码",
     "The name cannot contain control characters or colour codes"),
    ("target.hint.missing", "找不到该名字的接收器", "No receiver with that name was found"),
    ("target.known", "本网络接收器：%s", "Receivers in this network: %s"),
    ("target.known.none", "本网络里还没有已命名的接收器",
     "No named receiver in this network yet"),
    ("target.current", "目标：%s", "Target: %s"),
    ("trigger.button", "立即发送", "Send now"),
    ("order.add", "添加", "Add"),
    ("order.remove", "删除", "Del"),
    ("order.apply", "应用数量", "Apply amount"),
    ("order.amount", "数量", "Amount"),
    ("order.header", "订单清单 %s / %s", "Order list %s / %s"),
    ("order.empty", "（清单为空，先选资源再点「添加」）",
     "(empty; pick a resource and press Add)"),
    ("order.resource.none", "（本网络无资源，点击刷新）", "(no resources; click to refresh)"),
    ("msg.target_ok", "目标已设定", "Target set"),
    ("msg.target_empty", "设定被拒绝：目标名字不能为空",
     "Rejected: the target name cannot be empty"),
    ("msg.target_too_long", "设定被拒绝：名字太长", "Rejected: the name is too long"),
    ("msg.target_invalid", "设定被拒绝：名字含非法字符",
     "Rejected: the name contains invalid characters"),
    ("msg.target_not_found", "找不到该名字的接收器",
     "Rejected: no receiver with that name was found"),
    ("msg.target_ambiguous", "设定被拒绝：该名字对应多台接收器",
     "Rejected: several receivers share that name"),
    ("msg.order_added", "已添加一条订单", "Order entry added"),
    ("msg.order_removed", "已删除该条订单", "Order entry removed"),
    ("msg.order_updated", "数量已更新", "Amount updated"),
    ("msg.order_too_many", "清单已满，无法再添加", "The order list is full; cannot add more"),
    ("msg.order_invalid_amount", "数量非法：必须大于 0",
     "Invalid amount: it must be greater than 0"),
    ("msg.order_invalid_resource", "资源非法：未知的物品或流体",
     "Invalid resource: unknown item or fluid"),
    ("msg.order_bad_index", "该条目已不存在，请重试", "That entry no longer exists; please retry"),
    ("msg.trigger_ok", "已触发一次发送", "One delivery triggered"),
    ("msg.trigger_failed", "已触发，但本轮没有资源被送出（详见聊天栏提示）",
     "Triggered, but nothing was delivered this round (see chat)"),
    ("msg.trigger_unconfigured", "无法发送：还没有设定目标", "Cannot send: no target set"),
    ("msg.trigger_not_found", "无法发送：找不到该名字的接收器",
     "Cannot send: no receiver with that name"),
    ("msg.trigger_ambiguous", "无法发送：该名字对应多台接收器",
     "Cannot send: several receivers share that name"),
]


def lines(prefix, keys, index):
    out = []
    for suffix, zh, en in keys:
        value = zh if index == 0 else en
        out.append('  "%s%s": %s,' % (prefix, suffix, json.dumps(value, ensure_ascii=False)))
    return out


def insert(text, anchor, new_lines):
    if not new_lines:
        return text
    pos = text.find(anchor)
    if pos < 0:
        raise SystemExit("anchor not found: %s" % anchor)
    end = text.find("\n", pos)
    if end < 0:
        raise SystemExit("anchor line has no newline: %s" % anchor)
    block = "\n".join(new_lines)
    return text[:end + 1] + block + "\n" + text[end + 1:]


def main():
    for index, name in enumerate(("zh_cn.json", "en_us.json")):
        path = os.path.join(LANG, name)
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
        before = json.loads(text)
        text = insert(text, RECEIVER_ANCHOR, lines(
            "gui.rs_create_compat.network_resource_receiver.", RECEIVER_KEYS, index))
        text = insert(text, REQUESTER_ANCHOR, lines(
            "gui.rs_create_compat.network_resource_requester.", REQUESTER_KEYS, index))
        # 旧的「手动触发按钮将在下一批加入」已经过时：值改成新语义（键保留，避免其它自检断键）
        if index == 0:
            text = text.replace(
                '"gui.rs_create_compat.network_resource_requester.trigger.pending":'
                ' "手动触发按钮将在下一批加入"',
                '"gui.rs_create_compat.network_resource_requester.trigger.pending":'
                ' "手动触发：立即按清单发送一次"')
        else:
            text = text.replace(
                '"gui.rs_create_compat.network_resource_requester.trigger.pending":'
                ' "The manual trigger button arrives in the next batch"',
                '"gui.rs_create_compat.network_resource_requester.trigger.pending":'
                ' "Manual trigger: send the order list once now"')
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        with io.open(path, encoding="utf-8") as handle:
            after = json.load(handle)
        print("%s: %d -> %d keys" % (name, len(before), len(after)))
        assert set(after) - set(before) == {
            "gui.rs_create_compat.network_resource_receiver." + k for k, _, _ in RECEIVER_KEYS
        } | {
            "gui.rs_create_compat.network_resource_requester." + k for k, _, _ in REQUESTER_KEYS
        }


if __name__ == "__main__":
    main()
