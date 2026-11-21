# -*- coding: utf-8 -*-
"""第 142 轮部署：build/libs → 本地实例 + SFTP /mods + SFTP /client_files/mods，并停用旧 beta。

只做「复制 / 上传 / 改名 / 回读校验」四件事；回读 sha256 四处必须全等。

沿用第 139 轮验证过的策略（第 139 轮修掉了 `/client_files/mods` 里 beta.37 仍启用的旧问题）：
1. 两个远端目录都「停用所有不是新 jar 的 beta jar」，不按固定 OLD 文件名停；
2. 停用前先删同名 `.disabled`（避免 rename 因目标已存在而失败被静默吞掉）；
3. 结束后断言每个远端目录「恰好只有一个启用 jar」。
"""
import hashlib
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sftp_probe_lib import connect  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW = "rs_create_compat-1.2.0-beta.43.jar"
OLDER = re.compile(r"^rs_create_compat-1\.2\.0-beta\.\d+\.jar$")
LOCAL_INSTANCE = r"D:\minecraft\.minecraft\versions\Create：Enjoy\mods"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def disable_path(full_path):
    """把启用状态的旧 jar 就地改名 .disabled；目标已存在先删。返回是否改名。"""
    disabled = full_path + ".disabled"
    if os.path.isfile(disabled):
        os.remove(disabled)
    os.rename(full_path, disabled)
    return True


build = os.path.join(BASE, "build", "libs", NEW)
data = open(build, "rb").read()
want = sha(data)
print("build/libs  %s  %s  %d B" % (want, NEW, len(data)))

# 1) 本地实例：先停用旧的 beta jar，再放新的
os.makedirs(LOCAL_INSTANCE, exist_ok=True)
for name in sorted(os.listdir(LOCAL_INSTANCE)):
    if name != NEW and OLDER.match(name):
        disable_path(os.path.join(LOCAL_INSTANCE, name))
        print("local disabled ->", name + ".disabled")
shutil.copy2(build, os.path.join(LOCAL_INSTANCE, NEW))

# 2) SFTP 两处
for directory in ("/mods", "/client_files/mods"):
    sftp, transport = connect("create_enjoy")
    try:
        for name in sorted(sftp.listdir(directory)):
            if name == NEW or not OLDER.match(name):
                continue
            old_remote = directory + "/" + name
            try:
                sftp.remove(old_remote + ".disabled")
            except IOError:
                pass
            sftp.rename(old_remote, old_remote + ".disabled")
            print("sftp disabled ->", old_remote + ".disabled")
        remote = directory + "/" + NEW
        with sftp.open(remote, "wb") as handle:
            handle.write(data)
        with sftp.open(remote, "rb") as handle:
            got = sha(handle.read())
        print("sftp %-28s %s" % (remote, got))
        assert got == want, "远端回读不一致: " + remote
        active = [n for n in sftp.listdir(directory) if n.startswith("rs_create_compat-") and n.endswith(".jar")]
        print("sftp active jars ->", active)
        assert active == [NEW], "该目录仍有多个启用 jar: %r" % (active,)
    finally:
        sftp.close()
        transport.close()

# 3) 本地实例回读
got = sha(open(os.path.join(LOCAL_INSTANCE, NEW), "rb").read())
print("local inst  %s  %s" % (got, os.path.join(LOCAL_INSTANCE, NEW)))
assert got == want

print("DEPLOY_OK", want)
