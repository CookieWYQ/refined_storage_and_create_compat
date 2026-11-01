"""从服务器下载 rs_create_compat 的 jar，与本地实例里的同名 jar 比对（大小 + sha256）。"""
import hashlib
import json
import re
import sys
from pathlib import Path

import paramiko
from cryptography.fernet import Fernet

BASE = Path(r"D:\MODS\refined_storage_and_create_compat")
LOCAL_MODS = Path(r"D:\minecraft\.minecraft\versions\Create：Enjoy\mods")
ALIAS = "create_enjoy"


def load_creds():
    key = (BASE / ".sftp_key").read_bytes()
    enc = (BASE / "sftp_credentials.enc").read_bytes()
    return json.loads(Fernet(key).decrypt(enc).decode("utf-8"))


def connect():
    cfg = load_creds()[ALIAS]
    t = paramiko.Transport((cfg["host"], cfg["port"]))
    t.connect(username=cfg["user"], password=cfg["pass"])
    return paramiko.SFTPClient.from_transport(t)


def sha256_file(p: Path, chunk=1 << 20):
    h = hashlib.sha256()
    with p.open("rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sftp = connect()
    try:
        print("== 服务器 /mods 里匹配 rs_create_compat 的文件 ==")
        remote = []
        for e in sftp.listdir_attr("/mods" if sftp.stat("/mods") else "mods"):
            if "rs_create_compat" in e.filename:
                remote.append(e)
                print(f"  {e.st_size:>12}  {e.filename}")
        if not remote:
            # 试着相对根
            for e in sftp.listdir_attr("mods"):
                if "rs_create_compat" in e.filename:
                    remote.append(e)
                    print(f"  {e.st_size:>12}  {e.filename}")
        if not remote:
            print("  （服务器 mods 里没找到 rs_create_compat）")
            return

        out = BASE / "diag" / "remote-rscc.jar"
        out.parent.mkdir(parents=True, exist_ok=True)
        rp = remote[0].filename
        # 找对路径前缀
        prefix = ""
        for cand in ("/mods/", "mods/"):
            try:
                sftp.stat(cand + rp)
                prefix = cand
                break
            except IOError:
                continue
        print(f"\n== 下载 {prefix}{rp} ==")
        sftp.get(prefix + rp, str(out))
        print(f"  已存到 {out}  ({out.stat().st_size} 字节)")

        print("\n== 本地实例 ==")
        local = []
        if LOCAL_MODS.is_dir():
            for p in LOCAL_MODS.glob("*rs_create_compat*"):
                local.append(p)
                print(f"  {p.stat().st_size:>12}  {p.name}")
        else:
            print(f"  （本地目录不存在：{LOCAL_MODS}）")

        print("\n== 比对 ==")
        rh = sha256_file(out)
        print(f"  服务器：size={out.stat().st_size}  sha256={rh}")
        for p in local:
            lh = sha256_file(p)
            same = "★ 完全一致" if lh == rh else "✗ 不一致"
            print(f"  本地  ：size={p.stat().st_size}  sha256={lh}  ⇒ {same}")
    finally:
        sftp.close()


if __name__ == "__main__":
    main()
