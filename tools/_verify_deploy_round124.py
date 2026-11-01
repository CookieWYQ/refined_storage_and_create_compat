# 第 124 轮部署回读校验：build/libs / 本地客户端实例 / SFTP 服务端 mods / SFTP 客户端 mods 四处 sha256 必须一致。
# 只读（不写远端），失败即非 0。
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sftp_probe_lib import connect  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = "rs_create_compat-1.2.0-beta.25.jar"
IMAGE = "rs_create_compat-1.2.0-beta.25.jar"
LOCAL_INSTANCE = r"D:\minecraft\.minecraft\versions\Create：Enjoy\mods"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def local_sha(path):
    with open(path, "rb") as handle:
        return sha(handle.read())


local_build = os.path.join(BASE, "build", "libs", JAR)
want = local_sha(local_build)
print("build/libs  %s  %s" % (want, JAR))

ok = True
instance_path = os.path.join(LOCAL_INSTANCE, IMAGE)
if os.path.isfile(instance_path):
    got = local_sha(instance_path)
    print("local inst  %s  %s" % (got, instance_path))
    ok = ok and got == want
else:
    print("local inst  MISSING  %s" % instance_path)
    ok = False

for alias, directory in (("create_enjoy", "/mods"), ("create_enjoy", "/client_files/mods")):
    sftp, transport = connect(alias)
    try:
        remote = directory + "/" + IMAGE
        with sftp.open(remote, "rb") as handle:
            got = sha(handle.read())
        print("sftp %-7s%s  %s" % (alias, got, remote))
        ok = ok and got == want
    finally:
        sftp.close()
        transport.close()

# 旧版必须已就地禁用（改名 .disabled），避免两个版本同时被加载 —— 远端由 deploy_rscc.py 打印，
# 这里对本地实例做一次硬校验（缺旧版 = 没问题；旧版仍是 .jar = 两份同时启用，明确报错）。
def check_old_disabled(directory, old_jar):
    enabled = os.path.join(directory, old_jar)
    disabled = enabled + ".disabled"
    if os.path.isfile(enabled):
        print("old jar     STILL ENABLED  %s" % enabled)
        return False
    print("old jar     disabled=%s" % os.path.isfile(disabled))
    return True


ok = check_old_disabled(LOCAL_INSTANCE, "rs_create_compat-1.2.0-beta.23.jar") and ok

print("")
print("DEPLOY_VERIFY_%s" % ("OK" if ok else "FAILED"))
sys.exit(0 if ok else 1)
