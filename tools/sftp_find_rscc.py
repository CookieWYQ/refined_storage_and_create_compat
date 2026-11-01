#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFTP 探查 3：找 rs_create_compat 的 jar（客户端 / 服务端 各自的 mods 目录）。只读。"""
import sys
from stat import S_ISDIR, S_ISREG

from sftp_probe_lib import connect

DIRS = ["/mods", "/client_files", "/client_files/mods", "/disabled_mods",
        "/disabled_mods/mods", "/client_files/disabled_mods", "."]


def main():
    alias = sys.argv[1] if len(sys.argv) > 1 else "create_enjoy"
    sftp, tr = connect(alias)
    try:
        for d in DIRS:
            try:
                entries = sftp.listdir_attr(d)
            except Exception as e:  # noqa: BLE001
                print(f"[{d}] <err {type(e).__name__}>")
                continue
            hits = [e for e in entries if "rs_create_compat" in e.filename.lower()]
            jars = [e for e in entries if e.filename.lower().endswith((".jar", ".zip"))]
            print(f"[{d}] entries={len(entries)} jars={len(jars)} rscc_hits={len(hits)}")
            for e in hits:
                print(f"    {'d' if S_ISDIR(e.st_mode) else 'f'} {e.filename}  {e.st_size} B")
    finally:
        sftp.close()
        tr.close()


if __name__ == "__main__":
    main()
