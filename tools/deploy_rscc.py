#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把构建好的 jar 部署到 SFTP 上的「服务端」与「客户端」mods 目录。

规则（用户 2026-10-25 定案）：
  1. 先禁用旧版本：同目录下所有 `rs_create_compat-*.jar`（不含 `.disabled`）改名为
     `<原名>.disabled` —— 用户明确要求「禁用 = 改名」，不是删除。
  2. 再上传新 jar：先传到 `<final>.uploading`，校验通过后再 rename 成正式名，
     避免网络中断留下半个 jar 被游戏加载。
  3. 完整性校验：上传后把远端文件**读回来**算 sha256，与本地一致才算成功；
     同时比对 size。
  4. 只读凭据：复用 .sftp_key + sftp_credentials.enc（与 sftp_skill.py 同一份），
     本脚本不修改 sftp_skill.py / sftp_manager.py。

用法：
  python tools/deploy_rscc.py            # 干跑：只打印将要做什么
  python tools/deploy_rscc.py --apply    # 真执行
  python tools/deploy_rscc.py --apply --only server|client
"""
import argparse
import hashlib
import os
import posixpath
import sys
from pathlib import Path

from sftp_probe_lib import connect

BASE = Path(__file__).resolve().parent.parent
def newest_jar():
    """默认取 build/libs 里最新的 rs_create_compat-*.jar（避免每轮手改文件名）。"""
    candidates = [p for p in (BASE / "build" / "libs").glob("rs_create_compat-*.jar")]
    if not candidates:
        raise SystemExit("build/libs 里没有 rs_create_compat-*.jar（先跑 gradlew build）")
    return max(candidates, key=lambda p: p.stat().st_mtime)



# 远端目录（已用 tools/sftp_find_rscc.py 确认）：
#   /mods              = 服务端 mods（有 server.properties / world / eula.txt）
#   /client_files/mods = 客户端 mods
TARGETS = {
    "server": "/mods",
    "client": "/client_files/mods",
}
PREFIX = "rs_create_compat-"
SUFFIX = ".jar"


def sha256_local(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_remote(sftp, remote_path):
    h = hashlib.sha256()
    with sftp.open(remote_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def remote_rscc_jars(sftp, directory):
    out = []
    for e in sftp.listdir_attr(directory):
        name = e.filename
        if not name.startswith(PREFIX):
            continue
        if not name.endswith(SUFFIX):  # 已经禁用（*.jar.disabled）的不动
            continue
        out.append((posixpath.join(directory, name), e.st_size))
    return sorted(out)


def deploy(alias, directory, local_jar, apply):
    sftp, transport = connect(alias)
    try:
        local_size = local_jar.stat().st_size
        local_hash = sha256_local(local_jar)
        final = posixpath.join(directory, local_jar.name)
        temp = final + ".uploading"
        print(f"\n== {directory}")
        print(f"   本地 {local_jar.name}: {local_size} B sha256={local_hash}")

        old = remote_rscc_jars(sftp, directory)
        if not old:
            print("   [warn] 远端没有发现启用中的 rs_create_compat jar")
        for path, size in old:
            print(f"   禁用旧版: {posixpath.basename(path)} ({size} B) -> .disabled")
            if apply:
                try:
                    sftp.rename(path, path + ".disabled")
                except OSError as e:  # 已存在同名 .disabled 时加时间戳
                    stamp = int(__import__("time").time())
                    sftp.rename(path, f"{path}.disabled.{stamp}")
                    print(f"     (目标已存在，改名为 .disabled.{stamp}: {e})")

        # 若远端已有同名正式 jar（重复部署），先禁用掉
        try:
            sftp.stat(final)
            print(f"   [info] 远端已存在 {local_jar.name}，先禁用为 .disabled.pre")
            if apply:
                try:
                    sftp.remove(final + ".disabled.pre")
                except OSError:
                    pass
                sftp.rename(final, final + ".disabled.pre")
        except OSError:
            pass

        print(f"   上传 -> {temp}")
        if apply:
            sftp.put(str(local_jar), temp)

            remote_size = sftp.stat(temp).st_size
            remote_hash = sha256_remote(sftp, temp)
            print(f"   远端: {remote_size} B sha256={remote_hash}")
            if remote_size != local_size or remote_hash != local_hash:
                print("   [FAIL] 校验不一致，保留 .uploading 供排查，未启用新 jar")
                return False

            sftp.rename(temp, final)
            print(f"   已启用: {posixpath.basename(final)}")

            # 收尾再验一遍（rename 之后）
            remote_hash2 = sha256_remote(sftp, final)
            if remote_hash2 != local_hash:
                print("   [FAIL] rename 后哈希变化")
                return False
            print("   [OK] sha256 一致（上传 + rename 后各校验一次）")
        else:
            print("   (干跑，未执行)")
        return True
    finally:
        sftp.close()
        transport.close()


def main():
    global LOCAL_JAR
    LOCAL_JAR = newest_jar()
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--only", choices=["server", "client"], default=None)
    args = ap.parse_args()

    if not LOCAL_JAR.exists():
        print(f"[FAIL] 找不到 {LOCAL_JAR}")
        return 1
    print(f"部署目标: {LOCAL_JAR.name}")
    ok = True
    for name, directory in TARGETS.items():
        if args.only and name != args.only:
            continue
        try:
            ok &= deploy("create_enjoy", directory, LOCAL_JAR, args.apply)
        except Exception as e:  # noqa: BLE001
            print(f"[FAIL] {name} ({directory}): {type(e).__name__}: {e}")
            ok = False
    print("\n[结果]", "OK" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
