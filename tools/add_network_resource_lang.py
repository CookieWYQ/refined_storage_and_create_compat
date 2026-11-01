# -*- coding: utf-8 -*-
"""给 zh_cn / en_us 追加「网络资源接收器 / 请求器」的语言键（纯文本行插入，保留原格式）。

为什么按「行插入」而不是 json.dump 重写整份文件：
  语言文件是**按功能分组、人工维护**的（并非全量字典序），json.dump 会把它整体重排，
  产生一份巨大的、无法审阅的 diff。这里只在最后一个 `}` 之前插入一组新键，
  其余字节一个都不动（校验：插入后的键数必须 = 原键数 + 新增数，且既有键值逐字不变）。
"""
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

KEYS = [
    # ---- 方块名 + 常显帮助（RsccHelpBlockItem 用 block.<id>.help） ----
    ("block.rs_create_compat.network_resource_receiver",
     "网络资源接收器",
     "Network Resource Receiver"),
    ("block.rs_create_compat.network_resource_receiver.help",
     "接入网络并命名后可用；把缓存输出到朝向的容器。",
     "Name it once networked; pushes its cache into the container it faces."),
    ("block.rs_create_compat.network_resource_requester",
     "网络资源请求器",
     "Network Resource Requester"),
    ("block.rs_create_compat.network_resource_requester.help",
     "红石脉冲或界面按钮触发；把设定的资源投递给同网络中同名的接收器。",
     "Redstone pulse or GUI button; delivers the configured resource to the same-named receiver in this network."),
    # ---- 接收器界面 ----
    ("gui.rs_create_compat.network_resource_receiver.upgrades",
     "插件",
     "Upgrades"),
    ("gui.rs_create_compat.network_resource_receiver.status.named",
     "已命名；缓存会输出到朝向的容器",
     "Named; the cache is pushed into the container it faces"),
    ("gui.rs_create_compat.network_resource_receiver.status.unnamed",
     "未命名：本机不可用，请先命名",
     "Unnamed: this machine will not work until it is named"),
    # ---- 请求器界面 ----
    ("gui.rs_create_compat.network_resource_requester.redstone.hint",
     "红石上升沿触发一次投递",
     "A redstone rising edge triggers one delivery"),
    ("gui.rs_create_compat.network_resource_requester.status.configured",
     "已设定目标",
     "Target set"),
    ("gui.rs_create_compat.network_resource_requester.status.unconfigured",
     "尚未设定目标",
     "No target set yet"),
    ("gui.rs_create_compat.network_resource_requester.trigger.pending",
     "手动触发按钮将在下一批加入",
     "The manual trigger button arrives in the next batch"),
    ("gui.rs_create_compat.network_resource_requester.empty",
     "（未配置）",
     "(not configured)"),
]


def patch(name, index, force=False):
    path = os.path.join(LANG, name)
    text = io.open(path, encoding="utf-8").read()
    before = json.loads(text)
    todo = [(entry[0], entry[index]) for entry in KEYS
            if force or entry[0] not in before]
    if not force:
        for entry in KEYS:
            if entry[0] in before:
                print("  skip(已存在) %s" % entry[0])
    if not todo:
        print("  %s：无需改动" % name)
        return 0
    # force 模式：先把已存在的同名键从文本里摘掉，再统一插入（幂等重建）
    if force:
        kept = []
        drop = set(k for k, _ in todo)
        for line in text.split("\n"):
            stripped = line.strip()
            hit = None
            for key in drop:
                if stripped.startswith('"%s"' % key):
                    hit = key
                    break
            if hit is None:
                kept.append(line)
        text = "\n".join(kept)
    lines = text.split("\n")
    closing = None
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].strip() == "}":
            closing = i
            break
    if closing is None:
        raise SystemExit("找不到收尾大括号：%s" % path)
    last = closing - 1
    while last >= 0 and not lines[last].strip():
        last -= 1
    if last >= 0 and not lines[last].rstrip().endswith(","):
        lines[last] = lines[last].rstrip() + ","
    added = []
    for key, value in todo:
        added.append('  "%s": "%s",' % (key, value))
    if added:
        added[-1] = added[-1][:-1]
    lines[closing:closing] = added
    out = "\n".join(lines)
    after = json.loads(out)  # 语法自证
    if force:
        assert len(after) == len(before), (len(after), len(before))
    else:
        assert len(after) == len(before) + len(todo), (len(after), len(before), len(todo))
    for key in before:
        if force and key in dict(todo):
            continue
        assert after[key] == before[key], "既有键被改动：%s" % key
    with io.open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(out)
    # 写完立刻回读：证明确实落盘的是中文 / 英文，而不是「看起来对」
    reread = json.loads(io.open(path, encoding="utf-8").read())
    sample = todo[0]
    print("  回读 %s = %r（ascii=%s）" % (sample[0], reread[sample[0]],
                                        str(reread[sample[0]]).isascii()))
    print("  %s：%d 个既有键全部保持不变，新增 %d 个（总计 %d）"
          % (name, len(before), len(todo), len(after)))
    return len(todo)


def main():
    # --force-zh：把 zh_cn 的新键整组重建（用于修正「值被写成了英文」的单侧错误）
    force_zh = "--force-zh" in sys.argv
    n_zh = patch("zh_cn.json", 1, force=force_zh)
    n_en = patch("en_us.json", 2)
    print("新增：zh_cn %d / en_us %d" % (n_zh, n_en))
    return 0


if __name__ == "__main__":
    sys.exit(main())
