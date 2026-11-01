#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFTP 探查 2：只读列出指定远程目录（mods / disabled_mods / client_files 等）。

用法： python tools/sftp_probe.py <alias> <remote_dir> [depth]
"""
import sys
from stat import S_ISDIR, S_ISREG

from sftp_probe_lib import connect  # noqa: E402  (同目录小工具，见 sftp_probe_lib.py)


def walk(sftp, path, depth, out, max_entries=400):
    try:
        entries = sorted(sftp.listdir_attr(path), key=lambda a: a.filename)
    except Exception as e:  # noqa: BLE001
        out.append(f"  !! {path}: {type(e).__name__}: {e}")
        return
    for e in entries:
        full = (path.rstrip("/") + "/" + e.filename) or "/"
        if S_ISDIR(e.st_mode):
            out.append(f"  d {full}/")
            if depth > 1:
                walk(sftp, full, depth - 1, out, max_entries)
        else:
            out.append(f"  f {full}  ({e.st_size} B)")
        if len(out) > max_entries:
            out.append("  ... (truncated)")
            return


def main():
    alias = sys.argv[1]
    remote = sys.argv[2] if len(sys.argv) > 2 else "."
    depth = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    sftp, tr = connect(alias)
    out = []
    walk(sftp, remote, depth, out)
    print("\n".join(out))
    sftp.close()
    tr.close()


if __name__ == "__main__":
    main()
