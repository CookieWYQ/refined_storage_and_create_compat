# -*- coding: utf-8 -*-
"""第 136 轮部署：build/libs → 本地实例 + SFTP /mods + SFTP /client_files/mods，并停用 beta.36。

只做「复制 / 上传 / 改名 / 回读校验」四件事；回读 sha256 四处必须全等。
"""
import hashlib
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sftp_probe_lib import connect  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW = "rs_create_compat-1.2.0-beta.37.jar"
OLD = "rs_create_compat-1.2.0-beta.36.jar"
LOCAL_INSTANCE = r"D:\minecraft\.minecraft\versions\Create：Enjoy\mods"


def sha(data):
    return hashlib.sha256(data).hexdigest()


build = os.path.join(BASE, "build", "libs", NEW)
data = open(build, "rb").read()
want = sha(data)
print("build/libs  %s  %s  %d B" % (want, NEW, len(data)))

# 1) 本地实例
os.makedirs(LOCAL_INSTANCE, exist_ok=True)
shutil.copy2(build, os.path.join(LOCAL_INSTANCE, NEW))
old_local = os.path.join(LOCAL_INSTANCE, OLD)
if os.path.isfile(old_local):
    disabled = old_local + ".disabled"
    if os.path.isfile(disabled):
        os.remove(disabled)
    os.rename(old_local, disabled)
    print("local disabled ->", disabled)

# 2) SFTP 两处
for directory in ("/mods", "/client_files/mods"):
    sftp, transport = connect("create_enjoy")
    try:
        remote = directory + "/" + NEW
        with sftp.open(remote, "wb") as handle:
            handle.write(data)
        old_remote = directory + "/" + OLD
        try:
            sftp.stat(old_remote)
            try:
                sftp.remove(old_remote + ".disabled")
            except IOError:
                pass
            sftp.rename(old_remote, old_remote + ".disabled")
            print("sftp disabled ->", old_remote + ".disabled")
        except IOError:
            pass
        tmp = directory + "/" + NEW
        with sftp.open(tmp, "rb") as handle:
            got = sha(handle.read())
        print("sftp %-28s %s" % (tmp, got))
        assert got == want, "远端回读不一致: " + tmp
    finally:
        sftp.close()
        transport.close()

# 3) 本地实例回读
got = sha(open(os.path.join(LOCAL_INSTANCE, NEW), "rb").read())
print("local inst  %s  %s" % (got, os.path.join(LOCAL_INSTANCE, NEW)))
assert got == want

print("DEPLOY_OK", want)
