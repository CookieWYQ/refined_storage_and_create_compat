# 生成每个实现方自己的编译校验脚本（输出目录 / 日志 / argfile 全部隔离，互不干扰）。
# 用法：python tools/_gen_compile_scripts.py
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
src = (ROOT / "tools" / "_lead_compile.ps1").read_text(encoding="utf-8-sig")
for name in ["cachegui", "receiver", "sender", "terminal-send"]:
    text = src
    text = text.replace('$outDir = "$root\\build\\lead_compile"',
                        '$outDir = "$root\\build\\compile_' + name + '"')
    text = text.replace('$logFile = "$root\\build\\lead_compile.$PID.log"',
                        '$logFile = "$root\\build\\compile_' + name + '.$PID.log"')
    text = text.replace('$argfile = "$root\\build\\lead_javac_args.$PID.txt"',
                        '$argfile = "$root\\build\\javac_args_' + name + '.$PID.txt"')
    (ROOT / "tools" / ("_compile_" + name + ".ps1")).write_text(text, encoding="utf-8-sig")
    print("wrote tools/_compile_" + name + ".ps1")
