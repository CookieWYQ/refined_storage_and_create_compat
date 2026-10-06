"""把需要参考的模组源码拉到项目内的 local_src/external/ 下（不写 C 盘）。

用法:
    python tools/fetch_external_sources.py [仓库名...]     # 不传则拉全部

为什么不用普通 git clone：
    这些仓库包含很深的 src/generated/... 路径，Windows 上会超过 260 字符导致
    「Clone succeeded, but checkout failed」。这里统一用
    `--no-checkout` + 稀疏检出（排除 generated/build/run）来规避，
    只保留我们真正需要阅读的源码。

已存在且已检出的目录会被跳过（可用 `--force` 重新拉取）。
"""

import os
import subprocess
import sys
from pathlib import Path
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


EXTERNAL = Path('local_src/external')

# (显示名, 仓库地址, 候选分支按优先级)
REPOS = [
    # 本模组跑的是 RS 2.0（1.21.1），优先取 2.0 的发布线，develop 为最新开发版（可能已跨 MC 版本）
    ('RefinedStorage', 'https://github.com/refinedmods/refinedstorage2.git',
     ['release/2.0.10', 'support/2.x', 'develop']),
    ('Create', 'https://github.com/Creators-of-Create/Create.git',
     ['mc1.21.1', 'mc1.21.x', 'dev']),
    ('Mekanism', 'https://github.com/mekanism/Mekanism.git',
     ['1.21.x', '1.21.1', 'develop']),
    ('CreateEnchantmentIndustry', 'https://github.com/DragonsPlusMinecraft/CreateEnchantmentIndustry.git',
     ['1.21.1/6.0.0-dev']),
]

# 稀疏检出：要根目录下所有内容，但排除这些（多数是生成长路径/构建产物）
SPARSE_EXCLUDES = ['/src/generated/*', '/src/main/generated/*', '/build/*', '/run/*', '/.gradle/*']


def run(cmd, cwd=None, check=True):
    """执行命令并返回 (returncode, stdout+stderr)。"""
    proc = subprocess.run(cmd, cwd=cwd, shell=False, capture_output=True, text=True)
    out = (proc.stdout or '') + (proc.stderr or '')
    if check and proc.returncode != 0:
        raise RuntimeError('命令失败: %s\n%s' % (' '.join(cmd), out))
    return proc.returncode, out


def pick_branch(url, candidates):
    """从远程分支里挑第一个存在的候选；都不存在则返回 None（用默认分支）。"""
    _, out = run(['git', 'ls-remote', '--heads', url], check=False)
    remote = set()
    for line in out.splitlines():
        if 'refs/heads/' in line:
            remote.add(line.split('refs/heads/', 1)[1].strip())
    for cand in candidates:
        if cand in remote:
            return cand
    return None


def fetch(name, url, candidates, force=False):
    target = EXTERNAL / name
    if target.exists() and (target / '.git').exists() and not force:
        java_count = sum(1 for _ in target.rglob('*.java'))
        if java_count > 0:
            print('[SKIP] %s 已存在（%d 个 java 文件）' % (name, java_count))
            return True

    branch = pick_branch(url, candidates)
    print('[INFO] %s 使用分支: %s' % (name, branch if branch else '<默认>'))
    cmd = ['git', 'clone', '--depth', '1', '--single-branch', '--no-checkout']
    if branch:
        cmd += ['-b', branch]
    cmd += [url, str(target)]
    try:
        run(cmd)
    except RuntimeError as e:
        print('[FAIL] %s 克隆失败\n%s' % (name, e))
        return False

    try:
        run(['git', 'config', 'core.longpaths', 'true'], cwd=target)
        run(['git', 'config', 'core.protectNTFS', 'false'], cwd=target)
        run(['git', 'sparse-checkout', 'init', '--no-cone'], cwd=target)
        args = ['git', 'sparse-checkout', 'set', '--no-cone', '/*'] + ['!' + p for p in SPARSE_EXCLUDES]
        run(args, cwd=target)
        run(['git', 'checkout', 'HEAD', '--', '.'], cwd=target)
    except RuntimeError as e:
        print('[WARN] %s 稀疏检出异常：%s' % (name, e))

    java_count = sum(1 for _ in target.rglob('*.java'))
    ok = java_count > 0
    print('[%s] %s -> %s（%d 个 java 文件）' % ('OK ' if ok else 'BAD', name, target, java_count))
    return ok


def main():
    EXTERNAL.mkdir(parents=True, exist_ok=True)
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    force = '--force' in sys.argv
    wanted = set(args) if args else None

    all_ok = True
    for name, url, candidates in REPOS:
        if wanted and name not in wanted:
            continue
        all_ok &= fetch(name, url, candidates, force)
    print('[result] %s' % ('ALL OK' if all_ok else 'HAS PROBLEM'))


if __name__ == '__main__':
    main()
