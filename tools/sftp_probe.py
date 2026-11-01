#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFTP 探查：只读，用来找客户端/服务端的 mods 目录（不打印任何凭据）。

复用 sftp_skill.py 的凭据文件（.sftp_key + sftp_credentials.enc），但独立实现，
因此 **不改动 sftp_skill.py 的任何读取逻辑**，也不依赖 dsh_python_bridge。
"""
import json
import sys
from pathlib import Path
from stat import S_ISDIR, S_ISREG

import paramiko
from cryptography.fernet import Fernet

BASE = Path(__file__).resolve().parent.parent
KEY = BASE / ".sftp_key"
CRED = BASE / "sftp_credentials.enc"


def creds():
    fernet = Fernet(KEY.read_bytes())
    return json.loads(fernet.decrypt(CRED.read_bytes()).decode("utf-8"))


def connect(alias):
    cfg = creds()[alias]
    t = paramiko.Transport((cfg["host"], cfg["port"]))
    t.connect(username=cfg["user"], password=cfg["pass"])
    return paramiko.SFTPClient.from_transport(t), t


def main():
    cfg = creds()
    print("aliases:", list(cfg.keys()))
    for alias in cfg:
        try:
            sftp, tr = connect(alias)
        except Exception as e:  # noqa: BLE001
            print(f"[{alias}] connect failed: {type(e).__name__}: {e}")
            continue
        try:
            print(f"[{alias}] cwd={sftp.normalize('.')}")
            # 列一下家目录，帮助判断 mods 在哪
            for e in sorted(sftp.listdir_attr("."), key=lambda a: a.filename)[:40]:
                kind = "d" if S_ISDIR(e.st_mode) else ("f" if S_ISREG(e.st_mode) else "?")
                print(f"   {kind} {e.filename}")
        except Exception as e:  # noqa: BLE001
            print(f"[{alias}] list failed: {type(e).__name__}: {e}")
        finally:
            sftp.close()
            tr.close()


if __name__ == "__main__":
    main()
