#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 NeoForge latest.log 中 SchematicLoader 的 [loader ...] 日志。

用法: python tools/analyze_loader_log.py [--out 输出文件]
默认读 run/logs/latest.log，把压缩后的关键时间线写到 out 文件并打印统计。
"""
import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parents[1]
LOG = ROOT / "run" / "logs" / "latest.log"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "run" / "logs" / "loader_summary.txt"))
    ap.add_argument("--log", default=str(LOG))
    args = ap.parse_args()

    log_path = Path(args.log)
    if not log_path.exists():
        print(f"log not found: {log_path}")
        sys.exit(1)

    lines = []
    time_re = re.compile(r"^\[[^\]]+\]")
    msg_re = re.compile(r"\[loader[^\]]*\]\s*(.*)$")
    pos_re = re.compile(r"loader @(-?\d+,-?\d+,-?\d+)\]")  # 旧格式含坐标（当前不含）

    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            if "[loader" not in raw:
                continue
            m_msg = msg_re.search(raw)
            if not m_msg:
                continue
            text = m_msg.group(1).strip()
            pos = ""
            mp = pos_re.search(raw)
            if mp:
                pos = mp.group(1)
            m_time = time_re.search(raw)
            tm = m_time.group(0)[1:] if m_time else ""
            key = text.split()[0] if text else "?"
            # 归一化 tick=NNN，便于折叠
            norm = re.sub(r"tick=\d+", "tick=#", text)
            lines.append((tm, pos, key, norm, text))

    if not lines:
        print("no [loader lines found")
        sys.exit(0)

    key_counter = Counter(k for _, _, k, _, _ in lines)
    print("== [loader] 总行:", len(lines))
    print("== 事件类型统计 ==")
    for k, c in key_counter.most_common():
        print(f"  {k:<12} {c}")

    # 折叠：同(位置,key,归一化文本)连续合并；输出每个位置在 tick 时间线上的转换（首末与关键变化）
    out = []
    cur = None
    cnt = 0
    def emit():
        nonlocal cur, cnt
        if cur:
            tm, pos, key, norm, text = cur
            label = f"@{pos} " if pos else ""
            out.append(f"[{tm}] {label}{key:<11} x{cnt} {text}")
    for item in lines:
        tm, pos, key, norm, text = item
        if cur and cur[1] == pos and cur[3] == norm and cur[2] == key:
            cnt += 1
            continue
        emit()
        cur = item
        cnt = 1
    emit()

    # 只保留：每个位置各自时间线上的最后一个事件段 + 全局末尾 60 段
    per_pos = {}
    for line in out:
        pass
    tail = out[-60:]
    print(f"== 折叠段总数 {len(out)}，末尾 60 段（最后一次操作附近）==")
    for line in tail:
        print("  " + line)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))
    print(f"== 全量折叠写入 {args.out} ==")

if __name__ == "__main__":
    main()
