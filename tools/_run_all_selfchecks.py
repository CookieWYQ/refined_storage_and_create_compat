# -*- coding: utf-8 -*-
"""round48：批量跑 tools/selfcheck_*.py 并把结果汇总（非 0 即失败）。"""
import sys, os, glob, subprocess, io

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
PY = sys.executable
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "build", "round48_selfchecks.txt")

scripts = sorted(glob.glob(os.path.join(HERE, "selfcheck_*.py")))
fails = []
report = io.open(OUT, "w", encoding="utf-8")
for path in scripts:
    name = os.path.basename(path)
    proc = subprocess.run([PY, path], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    ok = proc.returncode == 0
    tail = [l for l in (proc.stdout or "").splitlines() if l.strip()][-1:] or [""]
    line = "%-6s %-52s %s" % ("OK" if ok else "FAIL", name, tail[0][:110])
    print(line)
    report.write(line + "\n")
    if not ok:
        fails.append(name)
        report.write((proc.stdout or "")[-4000:] + "\n" + (proc.stderr or "")[-2000:] + "\n")
print("")
print("selfcheck 总数 %d，失败 %d" % (len(scripts), len(fails)))
report.write("\nselfcheck 总数 %d，失败 %d %s\n" % (len(scripts), len(fails), fails))
report.close()
sys.exit(1 if fails else 0)
