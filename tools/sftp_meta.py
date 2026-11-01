#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFTP 探查 4：列目录 + mtime（判断哪个是「上一版本」/客户端目录结构）。只读。"""
import sys
from stat import S_ISDIR

from sftp_probe_lib import connect


def show(sftp, path, name_filter=None):
    try:
        entries = sorted(sftp.listdir_attr(path), key=lambda a: a.filename)
    except Exception as e:  # noqa: BLE001
        print(f"[{path}] <err {type(e).__name__}: {e}>")
        return
    print(f"[{path}] {len(entries)} entries")
    for e in entries:
        if name_filter and name_filter.lower() not in e.filename.lower():
            continue
        kind = "d" if S_ISDIR(e.st_mode) else "f"
        print(f"   {kind} {e.filename}  size={e.st_size}  mtime={e.st_mtime}")


def main():
    sftp, tr = connect("create_enjoy")
    try:
        show(sftp, "/")
        show(sftp, "/client_files")
        show(sftp, "/mods", "rs_create")
        show(sftp, "/client_files/mods", "rs_create")
        for probe in ("/server.properties", "/world", "/client_files/mods/../config"):
            try:
                st = sftp.stat(probe)
                print(f"exists {probe}: size={st.st_size}")
            except Exception:  # noqa: BLE001
                print(f"missing {probe}")
    finally:
        sftp.close()
        tr.close()


if __name__ == "__main__":
    main()
