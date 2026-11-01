# -*- coding: utf-8 -*-
"""幂等自检（只读，可复跑）：把本轮生成器重跑一遍，前后对**每一个产物**做 SHA-256 比对。

覆盖：
  * ``tools/gen_network_io_textures.py`` -> 18 张 block 贴图 + 3 张预览条 + 同名的留档
  * ``tools/gen_network_io_models.py``   -> 6 个方块模型 + 2 个 blockstate + 2 个物品模型
断言：重跑后每个文件的 SHA-256 完全不变（逐字节相同）。退出码 0 = 全部幂等。

用法：``python tools/verify_network_io_idempotent.py``
"""
import hashlib
import os
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from gen_network_io_textures import expected_textures, OUT, PREVIEW  # noqa: E402

ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
BLOCKS = ("network_resource_receiver", "network_resource_requester")


def sha(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def snapshot():
    """本轮全部产物的 {相对路径: sha256}。"""
    files = []
    files += [os.path.join(OUT, n) for n in expected_textures()]
    for name in os.listdir(PREVIEW):
        if name.endswith(".png"):
            files.append(os.path.join(PREVIEW, name))
    for block in BLOCKS:
        files += [os.path.join(ASSETS, "models", "block", "%s.json" % block),
                  os.path.join(ASSETS, "models", "block", "%s_active.json" % block),
                  os.path.join(ASSETS, "blockstates", "%s.json" % block),
                  os.path.join(ASSETS, "models", "item", "%s.json" % block)]
    return {os.path.relpath(p, ROOT).replace(os.sep, "/"): sha(p)
            for p in files if os.path.exists(p)}


def main():
    print("=" * 88)
    print("网络资源接收器 / 请求器 生成器幂等自检    （tools/verify_network_io_idempotent.py）")
    print("=" * 88)
    before = snapshot()
    print("  产物文件数 = %d（贴图 %d + 预览 + JSON %d）"
          % (len(before), len(expected_textures()), 8))

    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    for script in ("gen_network_io_textures.py", "gen_network_io_models.py"):
        print("\n--- 重跑 tools/%s ---" % script)
        proc = subprocess.run([sys.executable, "-B", os.path.join("tools", script)],
                              cwd=ROOT, env=env, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        tail = [l for l in (proc.stdout or "").splitlines() if l.strip()][-1:]
        print("    exit=%d  输出末行: %s" % (proc.returncode, tail[0] if tail else "(空)"))
        if proc.returncode != 0:
            print("    stderr: %s" % (proc.stderr or "")[-800:])
            return 1

    after = snapshot()
    added = sorted(set(after) - set(before))
    removed = sorted(set(before) - set(after))
    changed = sorted(k for k in before if k in after and before[k] != after[k])
    print("\n--- 比对 ---")
    print("  新增 %d，缺失 %d，内容变化 %d" % (len(added), len(removed), len(changed)))
    for k in added:
        print("    + %s" % k)
    for k in removed:
        print("    - %s" % k)
    for k in changed:
        print("    ~ %s  %s -> %s" % (k, before[k][:12], after[k][:12]))
    if added or removed or changed:
        print("\nFAILED：生成器不幂等")
        return 1
    print("\n  [PASS] 全部 %d 个产物重跑后 SHA-256 完全一致（逐字节相同）" % len(before))
    print("=" * 88)
    print("IDEMPOTENT：重跑生成器产物哈希不变，退出码 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
