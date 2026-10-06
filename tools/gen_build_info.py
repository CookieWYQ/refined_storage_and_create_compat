# -*- coding: utf-8 -*-
"""生成「构建指纹」资源：src/main/resources/build_info.properties。

用法：
    python tools/gen_build_info.py            # 写入资源（Gradle / manual_compile 都会调用）
    python tools/gen_build_info.py --check    # 只校验现有指纹是否与当前 git 状态 / 源码一致

为什么需要它（TECHNICAL_HANDOFF.md §7.3 审计结论 ⑥）：
    上一轮取证时，run/logs/latest.log 里**没有 git hash、也没有编译时间**，只有 0.0.1-SNAPSHOT，
    于是「日志里的行为」与「磁盘上的源码」只能靠文件 mtime 猜 —— 实测最新那份日志其实**晚于**
    修复代码的编译（AssemblyWatchdog.java 16:08 / 最新 class 16:32 vs 日志 15:29-15:43），
    按项目自己的判据（docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md「会话起点必须晚于本次构建」）
    那份日志连基线都算不上，但日志本身看不出这一点。

    本脚本在**每一次编译前**把「这一刻的 git hash / 分支 / 工作区是否脏 / 编译时间 / 源码文件数」
    写进 build_info.properties，随包进 jar；游戏启动时由
    support/RsccBuildInfo.java 打出唯一一行 [rscc-build]，
    于是任何一份日志都能**自证**它是哪一版代码跑出来的。

字段（写入顺序固定，便于 diff）：
    revision            编译那一刻的 git 短 hash
    revisionFull        完整 hash
    branch              分支名
    dirty               工作区是否有未提交改动（true/false）
    builtAt             编译时间（ISO-8601 带时区）
    sourceEpochSeconds  编译时间（Unix 秒；供 RsccBuildInfo#builtAtMillis 做「会话是否晚于构建」判定）
    sources             源码 .java 文件数
    modVersion          gradle.properties 的 mod_version
    minecraftVersion    gradle.properties 的 minecraft_version
    neoForgeVersion     gradle.properties 的 neo_version
    generator           生成者标识（tools/gen_build_info.py）
"""

import argparse
import datetime
import os
import subprocess
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'build_info.properties')
GRADLE_PROPS = os.path.join(ROOT, 'gradle.properties')
# 与构建无关、且数量巨大 / 由外部带进来的目录：不计入 sources，避免数字被噪声带偏
SOURCE_ROOT = os.path.join(ROOT, 'src', 'main', 'java')


def git(*args):
    """执行 git 命令；失败（无 git / 非仓库）返回 None —— 绝不因为指纹而让构建失败。"""
    try:
        result = subprocess.run(
            ['git'] + list(args),
            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=20,
        )
        if result.returncode != 0:
            return None
        return result.stdout.decode('utf-8', 'replace').strip()
    except Exception:
        return None


def gradle_properties():
    """读 gradle.properties 的键值（只取我们需要的几个；解析失败返回空表）。"""
    values = {}
    try:
        with open(GRADLE_PROPS, 'r', encoding='utf-8', errors='replace') as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith('#') or '=' not in line:
                    continue
                key, _, value = line.partition('=')
                values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


def count_sources():
    total = 0
    for _dirpath, _dirnames, filenames in os.walk(SOURCE_ROOT):
        total += sum(1 for name in filenames if name.endswith('.java'))
    return total


def collect():
    now = datetime.datetime.now().astimezone()
    revision = git('rev-parse', '--short', 'HEAD') or '?'
    revision_full = git('rev-parse', 'HEAD') or '?'
    branch = git('rev-parse', '--abbrev-ref', 'HEAD') or '?'
    status = git('status', '--porcelain')
    dirty = 'true' if status else ('false' if status == '' else '?')
    props = gradle_properties()
    return {
        'revision': revision,
        'revisionFull': revision_full,
        'branch': branch,
        'dirty': dirty,
        'builtAt': now.strftime('%Y-%m-%dT%H:%M:%S%z'),
        'sourceEpochSeconds': str(int(now.timestamp())),
        'sources': str(count_sources()),
        'modVersion': props.get('mod_version', '?'),
        'minecraftVersion': props.get('minecraft_version', '?'),
        'neoForgeVersion': props.get('neo_version', '?'),
        'generator': 'tools/gen_build_info.py',
    }


def render(values):
    lines = [
        '# 由 tools/gen_build_info.py 生成 —— 不要手改（每次编译都会覆盖）。',
        '# 用途：游戏启动时打出一行 [rscc-build]，让日志能自证「是哪一版代码跑出来的」。',
        '# 读取方：src/main/java/cretae/cookiewyq/rs_create_compat/support/RsccBuildInfo.java',
    ]
    for key, value in values.items():
        lines.append('%s=%s' % (key, value))
    return '\n'.join(lines) + '\n'


def read_existing():
    values = {}
    try:
        with open(OUT, 'r', encoding='utf-8', errors='replace') as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith('#') or '=' not in line:
                    continue
                key, _, value = line.partition('=')
                values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


def main():
    parser = argparse.ArgumentParser(description='生成 / 校验构建指纹资源')
    parser.add_argument('--check', action='store_true',
                        help='只校验现有指纹是否与当前 git 状态一致（不一致退出码 1）')
    parser.add_argument('--quiet', action='store_true', help='成功时不打印摘要')
    args = parser.parse_args()

    values = collect()
    if args.check:
        existing = read_existing()
        problems = []
        if not existing:
            problems.append('指纹资源不存在：%s' % OUT)
        else:
            for key in ('revision', 'revisionFull', 'branch'):
                if existing.get(key) != values.get(key):
                    problems.append('%s: 资源=%s 当前=%s'
                                    % (key, existing.get(key), values.get(key)))
            if existing.get('dirty') != values.get('dirty'):
                problems.append('dirty: 资源=%s 当前=%s' % (existing.get('dirty'), values.get('dirty')))
        if problems:
            print('BUILD STAMP STALE:')
            for problem in problems:
                print('  - %s' % problem)
            print('修复：python tools/gen_build_info.py')
            return 1
        print('BUILD STAMP OK revision=%s dirty=%s builtAt=%s'
              % (existing.get('revision'), existing.get('dirty'), existing.get('builtAt')))
        return 0

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    text = render(values)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as handle:
        handle.write(text)
    if not args.quiet:
        print('BUILD STAMP WRITTEN %s' % os.path.relpath(OUT, ROOT).replace('\\', '/'))
        for key in ('revision', 'branch', 'dirty', 'builtAt', 'sources', 'modVersion'):
            print('  %-16s %s' % (key, values[key]))
    return 0


if __name__ == '__main__':
    sys.exit(main())
