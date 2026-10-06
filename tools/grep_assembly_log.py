# -*- coding: utf-8 -*-
"""把 latest.log 里本模组序列装配诊断行（[rscc-assembly]）筛出来，便于快速定位断点。

日志可能是非 UTF-8（混合编码），这里用 errors="replace" 兜底。
用法：
    python tools/grep_assembly_log.py                # 默认取最后 200 行匹配
    python tools/grep_assembly_log.py 500            # 取最后 500 行
    python tools/grep_assembly_log.py 200 claim      # 只保留含 "claim" 的匹配行
输出：控制台 + tmp_textures/assembly_log_extract.txt
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(ROOT, "run", "logs", "latest.log")
OUT = os.path.join(ROOT, "tmp_textures", "assembly_log_extract.txt")

LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 200
NEEDLE = sys.argv[2] if len(sys.argv) > 2 else None


def main():
    if not os.path.exists(LOG):
        print("[MISS] 找不到日志：%s" % LOG)
        return 1
    with io.open(LOG, "r", encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()

    hits = [ln.rstrip("\n") for ln in lines if "rscc-assembly" in ln]
    if NEEDLE:
        hits = [ln for ln in hits if NEEDLE in ln]
    tail = hits[-LIMIT:]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(tail) + "\n")

    print("[info] 日志总行数 %d，匹配 [rscc-assembly] %d 行，输出最后 %d 行到 %s"
          % (len(lines), len(hits), len(tail), os.path.relpath(OUT, ROOT)))
    for ln in tail:
        print(ln)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
