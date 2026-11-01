"""用项目自带的 sftp_credentials（Fernet 加密）直连服务器，列目录 / 读日志。
用法：
  python tools/_sftp_probe.py ls [远程目录]
  python tools/_sftp_probe.py find <起始目录> <文件名通配> [最大结果]
  python tools/_sftp_probe.py read <远程文件> [最多字节] [从头/尾 tail]
"""
import json
import sys
from pathlib import Path

import paramiko
from cryptography.fernet import Fernet

BASE = Path(r"D:\MODS\refined_storage_and_create_compat")
ALIAS = "create_enjoy"


def load_creds():
    key = (BASE / ".sftp_key").read_bytes()
    enc = (BASE / "sftp_credentials.enc").read_bytes()
    return json.loads(Fernet(key).decrypt(enc).decode("utf-8"))


def connect(alias=ALIAS):
    creds = load_creds()
    if alias not in creds:
        raise SystemExit(f"未知别名 {alias}，可用: {list(creds.keys())}")
    cfg = creds[alias]
    t = paramiko.Transport((cfg["host"], cfg["port"]))
    t.connect(username=cfg["user"], password=cfg["pass"])
    return paramiko.SFTPClient.from_transport(t)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    sftp = connect()
    try:
        if cmd == "ls":
            remote = sys.argv[2] if len(sys.argv) > 2 else "."
            print(f"== ls {remote} ==")
            for e in sftp.listdir_attr(remote):
                kind = "DIR " if e.st_mode & 0o040000 else "FILE"
                print(f"  {kind} {e.st_size:>12}  {e.filename}")
        elif cmd == "find":
            start = sys.argv[2]
            pattern = sys.argv[3] if len(sys.argv) > 3 else "*.log"
            limit = int(sys.argv[4]) if len(sys.argv) > 4 else 20
            import fnmatch
            hits = []

            def walk(p, depth=0):
                if len(hits) >= limit or depth > 6:
                    return
                try:
                    entries = sftp.listdir_attr(p)
                except IOError:
                    return
                for e in entries:
                    full = (p.rstrip("/") + "/" + e.filename)
                    if e.st_mode & 0o040000:
                        walk(full, depth + 1)
                    elif fnmatch.fnmatch(e.filename, pattern):
                        hits.append((full, e.st_size, e.st_mtime))

            walk(start)
            print(f"== find {start} / {pattern} ==")
            for f, sz, mt in hits:
                print(f"  {sz:>12}  {f}")
            if not hits:
                print("  （没找到）")
        elif cmd == "read":
            remote = sys.argv[2]
            limit = int(sys.argv[3]) if len(sys.argv) > 3 else 65536
            mode = sys.argv[4] if len(sys.argv) > 4 else "tail"
            with sftp.open(remote, "rb") as f:
                if mode == "tail":
                    f.seek(0, 2)
                    size = f.tell()
                    start = max(0, size - limit)
                    f.seek(start)
                raw = f.read(limit)
            text = raw.decode("utf-8", errors="replace")
            print(f"== read {remote} ({len(raw)} 字节, {mode}) ==")
            print(text)
        else:
            print(__doc__)
    finally:
        sftp.close()


if __name__ == "__main__":
    main()
