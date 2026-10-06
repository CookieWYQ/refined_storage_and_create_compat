# -*- coding: utf-8 -*-
"""round48：把「修复前（HEAD 导出）」的自检输出用 UTF-8 落盘（PowerShell 的 > 会写 UTF-16）。"""
import subprocess, sys, os

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
script = os.path.join(ROOT, "build", "_before48", "tools", "selfcheck_round48_devlog_switch.py")
proc = subprocess.run([sys.executable, script], cwd=ROOT, capture_output=True, text=True,
                      encoding="utf-8", errors="replace")
with open(os.path.join(ROOT, "build", "round48_selfcheck_before.txt"), "w", encoding="utf-8") as fh:
    fh.write(proc.stdout or "")
    if proc.stderr:
        fh.write("\n[stderr]\n" + proc.stderr)
print("exit=%d" % proc.returncode)
