#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把新 jar 部署到**本地客户端实例**的 mods 目录（禁用旧版 = 改名，不删除）。

用户 2026-10-25 定案：客户端也是「把上一版本禁用（改名），再把新的拷进去」。

默认目标：D:\\minecraft\\.minecraft\\versions\\Create：Enjoy\\mods
（实例目录名含全角冒号，所以必须用 Python 而不是 shell 拼路径。）

用法：
  python tools/deploy_rscc_local.py            # 干跑
  python tools/deploy_rscc_local.py --apply    # 真执行
"""
import argparse
import hashlib
import shutil
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
def newest_jar():
    """默认取 build/libs 里最新的 rs_create_compat-*.jar（避免每轮手改文件名）。"""
    candidates = [p for p in (BASE / "build" / "libs").glob("rs_create_compat-*.jar")]
    if not candidates:
        raise SystemExit("build/libs 里没有 rs_create_compat-*.jar（先跑 gradlew build）")
    return max(candidates, key=lambda p: p.stat().st_mtime)


CLIENT_MODS = Path(r"D:\minecraft\.minecraft\versions\Create：Enjoy\mods")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    global LOCAL_JAR
    LOCAL_JAR = newest_jar()
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--mods", default=str(CLIENT_MODS))
    args = ap.parse_args()

    mods = Path(args.mods)
    if not mods.is_dir():
        print(f"[FAIL] 客户端 mods 目录不存在：{mods}")
        return 1
    if not LOCAL_JAR.exists():
        print(f"[FAIL] 找不到新 jar：{LOCAL_JAR}")
        return 1

    local_hash = sha256(LOCAL_JAR)
    local_size = LOCAL_JAR.stat().st_size
    print(f"新 jar : {LOCAL_JAR.name}  {local_size} B  sha256={local_hash}")
    print(f"目标   : {mods}")

    olds = sorted(p for p in mods.glob("rs_create_compat-*.jar") if p.is_file())
    if not olds:
        print("[warn] 客户端 mods 里没有启用中的 rs_create_compat jar")
    for p in olds:
        print(f"禁用旧版: {p.name} -> {p.name}.disabled")
        if args.apply:
            target = p.with_name(p.name + ".disabled")
            if target.exists():
                target.unlink()
            p.rename(target)

    final = mods / LOCAL_JAR.name
    if final.exists():
        print(f"[info] 目标已存在同名 jar，先禁用为 .disabled.pre")
        if args.apply:
            pre = final.with_name(final.name + ".disabled.pre")
            if pre.exists():
                pre.unlink()
            final.rename(pre)

    print(f"复制新 jar -> {final.name}")
    if args.apply:
        shutil.copy2(LOCAL_JAR, final)
        got = sha256(final)
        print(f"复制后 sha256={got}")
        if got != local_hash or final.stat().st_size != local_size:
            print("[FAIL] 哈希/大小不一致！")
            return 1
        print("[OK] sha256 与大小一致")

    # 收尾：列出所有 rs_create_compat*，确认只剩一个新 jar 启用
    print("\n最终状态：")
    for p in sorted(mods.glob("rs_create_compat-*")):
        print(f"  {'ENABLED ' if p.suffix == '.jar' else 'disabled'} {p.name}  {p.stat().st_size} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
