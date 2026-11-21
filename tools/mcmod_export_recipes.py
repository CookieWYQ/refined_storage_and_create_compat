#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出本模组的合成表为 MC百科「批量导入」JSON（RecipeDumper 标准格式）。

流程：
  1. 读 gradle.properties 的 mod_version，定位 build/libs/rs_create_compat-<ver>.jar
  2. 调用 recipedumper-py（RecipeDumper 1.21.1 移植版的离线复刻）导出
  3. 用同一工具的 validate 校验格式（格式不对会非零退出）
  4. 打印可直接贴到 MC百科「批量添加资料JSON收纳帖」的提交信息

用法：
  python tools/mcmod_export_recipes.py             # 用当前 mod_version + 最新构建
  python tools/mcmod_export_recipes.py --jar A.jar # 指定 JAR
  python tools/mcmod_export_recipes.py --out DIR   # 指定输出目录（默认 mcmod/exports）

产物：<out>/dump_recipes_rs_create_compat.json
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RD = ROOT / "recipedumper-py"
OUT_DEFAULT = ROOT / "mcmod" / "exports"
MODID = "rs_create_compat"
MOD_NAME = "机械动力&精致存储：兼容与改善"
MOD_URL = "https://www.mcmod.cn/class/31664.html"
MC_VERSION = "1.21.1"
LOADER = "NeoForge"


def mod_version() -> str:
    text = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    m = re.search(r"^mod_version\s*=\s*(\S+)", text, re.M)
    if not m:
        raise SystemExit("gradle.properties 里找不到 mod_version")
    return m.group(1).strip()


def run(args: list[str]) -> dict:
    proc = subprocess.run(args, cwd=str(RD), capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (proc.stdout or "").strip()
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr or "")
        sys.stderr.write(out[:2000])
        raise SystemExit(f"命令失败（exit={proc.returncode}）: {' '.join(args)}")
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        sys.stderr.write(out[:2000])
        raise SystemExit("工具没有返回可解析的 JSON")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", help="指定模组 JAR（默认 build/libs/rs_create_compat-<mod_version>.jar）")
    ap.add_argument("--out", default=str(OUT_DEFAULT), help="输出目录（默认 mcmod/exports）")
    args = ap.parse_args()

    ver = mod_version()
    jar = Path(args.jar) if args.jar else ROOT / "build" / "libs" / f"rs_create_compat-{ver}.jar"
    if not jar.is_file():
        raise SystemExit(f"找不到 JAR: {jar}（先跑 gradlew build）")
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)

    print(f"[1/2] 导出 {jar.name} → {out}")
    exp = run([sys.executable, "launcher.py", "dump", str(jar), "--out", str(out), "--json"])
    file = next((f for f in exp.get("files", []) if f.get("mod_id") == MODID), None)
    if not file:
        raise SystemExit("导出结果里没有 rs_create_compat：" + json.dumps(exp, ensure_ascii=False)[:800])

    target = Path(file["file"])
    if not target.is_absolute():
        target = (RD / target).resolve()
    print(f"      {file['recipes']} 条配方，{file['bytes']} 字节，0 错误: {file['file']}")

    print("[2/2] 校验格式")
    val = run([sys.executable, "launcher.py", "validate", str(target), "--json"])
    vf = val["files"][0]
    if not val.get("ok") or not vf.get("ok"):
        raise SystemExit("格式校验失败: " + json.dumps(val, ensure_ascii=False)[:1200])
    print(f"      OK：{vf['recipes']} 条，类型 {vf['type_counts']}，errors={vf['error_entries']}")

    print()
    print("=" * 68)
    print("可直接贴到 MC百科「批量添加资料JSON收纳帖」的提交信息：")
    print("  https://bbs.mcmod.cn/thread-1281-1-1.html")
    print("=" * 68)
    print(f"模组名称：{MOD_NAME}")
    print(f"站内链接：{MOD_URL}")
    print(f"模组版本：{ver}")
    print(f"游戏版本：{MC_VERSION} {LOADER}")
    print("导出工具：RecipeDumper（1.21.1 NeoForge 移植版 / 离线复刻，格式逐字段一致）")
    print(f"附件：{target.name}")
    print("=" * 68)
    return 0


if __name__ == "__main__":
    sys.exit(main())
