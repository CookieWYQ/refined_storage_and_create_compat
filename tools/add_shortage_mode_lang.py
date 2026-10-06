# -*- coding: utf-8 -*-
"""为「序列装配缺料处置策略」开关（用户第 5 条）追加 5 个语言键。

为什么用脚本而不是手改：语言文件是 JSON，手改容易漏逗号 / 破坏排序；
这里只做「在既有 supply 段之后插入若干行」的最小文本插入，其余一个字节都不动，
因此不会与另一路 agent 正在改的文案冲突（只碰这 5 个新键）。
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

NEW_KEYS = {
    "zh_cn.json": [
        ('  "message.rs_create_compat.shortage.current": "当前缺料处置策略：%s",\n'),
        ('  "message.rs_create_compat.shortage.mode.suspend": "缺料即挂起（默认；不堵塞后面的任务）",\n'),
        ('  "message.rs_create_compat.shortage.mode.wait": "一直等待到有料再继续（缺料不自动挂起）",\n'),
        ('  "message.rs_create_compat.shortage.set": "序列装配缺料处置策略已设为：%s",\n'),
        ('  "message.rs_create_compat.shortage.usage": "用法：/rs_create_compat shortagemode <suspend|wait>"\n'),
    ],
    "en_us.json": [
        ('  "message.rs_create_compat.shortage.current": "Current shortage handling: %s",\n'),
        ('  "message.rs_create_compat.shortage.mode.suspend": "suspend on shortage (default; unblocks later tasks)",\n'),
        ('  "message.rs_create_compat.shortage.mode.wait": "always wait until materials arrive (never auto-suspend)",\n'),
        ('  "message.rs_create_compat.shortage.set": "Sequence assembly shortage handling set to: %s",\n'),
        ('  "message.rs_create_compat.shortage.usage": "Usage: /rs_create_compat shortagemode <suspend|wait>"\n'),
    ],
}

ANCHOR = '"message.rs_create_compat.supply.usage"'


def patch(path, new_lines):
    with io.open(path, "r", encoding="utf-8", newline="") as handle:
        lines = handle.readlines()
    # 幂等：已经加过就跳过
    if any("message.rs_create_compat.shortage.mode.suspend" in line for line in lines):
        print("[skip] %s 已包含新键" % os.path.basename(path))
        return
    for index, line in enumerate(lines):
        if ANCHOR in line:
            # 给锚点行补逗号（它原本是最后一项、没有逗号）
            if not line.rstrip("\r\n").rstrip().endswith(","):
                stripped = line.rstrip("\r\n")
                eol = line[len(stripped):]
                lines[index] = stripped + "," + eol
            lines[index + 1:index + 1] = new_lines
            with io.open(path, "w", encoding="utf-8", newline="") as out:
                out.writelines(lines)
            print("[ok] %s 已插入 %d 个键" % (os.path.basename(path), len(new_lines)))
            return
    raise SystemExit("[fail] 未找到锚点: %s" % ANCHOR)


for name, keys in NEW_KEYS.items():
    patch(os.path.join(LANG, name), keys)
