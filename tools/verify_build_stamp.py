# -*- coding: utf-8 -*-
"""校验「这份日志能不能拿来证明当前源码的行为」——把日志自证变成一条命令。

用法：
    python tools/verify_build_stamp.py                    # 校验 run/logs/latest.log
    python tools/verify_build_stamp.py --log <路径>        # 校验指定日志
    python tools/verify_build_stamp.py --allow-empty       # 没跑过游戏时不算失败（CI / 纯编码场景）

它回答的问题（TECHNICAL_HANDOFF.md §7.3 结论 ⑥ 的直接产物）：
    「日志里的行为」与「磁盘上的源码」是不是同一版？
    上一轮取证就死在这里：日志里有 rev A 的行为，磁盘上已经是 rev B 的修复，而日志本身
    **看不出这一点**，于是被误当成了验证证据。现在游戏启动时由 support/RsccBuildInfo.java
    打出唯一一行 [rscc-build]，本脚本据此做四条硬断言。

四条断言（全部来自日志与工作区的事实，不做任何推测）：
    B1 日志存在且非空                      —— 否则「无日志却报 OK」的假绿（见下）
    B2 日志里存在 [rscc-build] 行           —— 缺失 = 跑的是加指纹之前的旧构建，日志无法自证
    B3 日志里的 revision == 当前 git HEAD   —— 不等 = 日志来自另一版代码，只能当基线
    B4 日志会话起点晚于本次构建             —— log mtime 早于 build_info.properties 的编译时间
                                             = 构建发生在会话之后，日志同样是基线

为什么不能「没日志就跳过」：
    项目里原本的自检（verify_single_unit_supply.py）在**没有日志**或**日志里没有 rscc 行**时
    会提前 return 并照样打印 SELFCHECK OK —— 也就是「什么都没查也报绿」。
    本脚本把「拿不到证据」一律判为 FAIL（除非显式传 --allow-empty），
    因为「没有证据」与「证据显示没问题」在取证上是两件事。
"""

import argparse
import datetime
import os
import re
import subprocess
import sys
from pathlib import Path
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# Windows 控制台默认 GBK：中文 / 箭头字符会直接抛 UnicodeEncodeError 把校验打断。
# 统一把 stdout 切到 UTF-8（失败就退回「不可编码字符替换」），保证本脚本永远能跑完并给出结论。
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOG = ROOT / 'run' / 'logs' / 'latest.log'
BUILD_INFO = ROOT / 'src' / 'main' / 'resources' / 'build_info.properties'

# [rscc-build] mod=rs_create_compat version=0.0.1-SNAPSHOT revision=e347372 branch=main built=... sources=324 ...
RE_BUILD = re.compile(r'\[rscc-build\]\s+(?P<body>.*)$')
RE_FIELD = re.compile(r'(\w+)=(\S+)')
# 会话起点（RsccDiag 的锚点行）：[rscc] diag logging ON (default) session=2026-10-04T15:32:00 ...
RE_SESSION = re.compile(r'\[rscc\]\s+diag logging ON .*?session=(\S+)')
RE_STAMP_MISSING = re.compile(r'\[rscc-build\]\s+build stamp missing')


class Result:
    def __init__(self):
        self.checks = []
        self.notes = []

    def add(self, ok, name, detail):
        self.checks.append((bool(ok), name, detail))

    def note(self, text):
        self.notes.append(text)

    @property
    def failed(self):
        return [c for c in self.checks if not c[0]]


def git_revision():
    try:
        out = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
        if out.returncode == 0:
            return out.stdout.decode('utf-8', 'replace').strip()
    except Exception:
        pass
    return None


def read_build_info():
    values = {}
    try:
        with open(BUILD_INFO, 'r', encoding='utf-8', errors='replace') as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith('#') or '=' not in line:
                    continue
                key, _, value = line.partition('=')
                values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


def parse_build_lines(text):
    """日志里所有 [rscc-build] 行 → 字段字典列表（按出现顺序）。"""
    found = []
    for line in text.splitlines():
        match = RE_BUILD.search(line)
        if not match:
            continue
        fields = dict(RE_FIELD.findall(match.group('body')))
        fields['_missing_stamp_warning'] = bool(RE_STAMP_MISSING.search(line))
        found.append(fields)
    return found


def parse_session_starts(text):
    return RE_SESSION.findall(text)


def main():
    parser = argparse.ArgumentParser(description='校验日志的构建指纹（日志能否自证版本）')
    parser.add_argument('--log', default=str(DEFAULT_LOG), help='要校验的日志（默认 run/logs/latest.log）')
    parser.add_argument('--allow-empty', action='store_true',
                        help='日志不存在 / 没跑过游戏时不算失败（默认判 FAIL，避免假绿）')
    args = parser.parse_args()

    log_path = Path(args.log)
    result = Result()

    # ---- B1：日志存在且非空 ----
    if not log_path.exists():
        if args.allow_empty:
            print('BUILD STAMP SKIPPED (no log at %s, --allow-empty)' % log_path)
            return 0
        result.add(False, 'B1 log exists',
                   '找不到日志：%s（没跑过游戏 ⇒ 没有任何运行期证据，不能报 OK）' % log_path)
        return report(result)
    size = log_path.stat().st_size
    result.add(size > 0, 'B1 log non-empty', '%s（%d 字节）' % (log_path, size))
    if size == 0:
        return report(result)

    text = log_path.read_text(encoding='utf-8', errors='replace')
    builds = parse_build_lines(text)
    sessions = parse_session_starts(text)

    # ---- B2：存在 [rscc-build] 行 ----
    if not builds:
        result.add(False, 'B2 build stamp present',
                   '日志里没有 [rscc-build] 行 ⇒ 这一步跑的是「加指纹之前」的旧构建，'
                   '日志与源码的对应关系不可证明（只能当基线）')
        return report(result)
    stamp = builds[-1]  # 取最后一次启动的那一条
    result.add(True, 'B2 build stamp present',
               'revision=%s built=%s branch=%s sources=%s'
               % (stamp.get('revision'), stamp.get('built'), stamp.get('branch'), stamp.get('sources')))
    if stamp.get('_missing_stamp_warning'):
        result.add(False, 'B2b build stamp resource loaded',
                   '游戏报了 "build stamp missing" ⇒ 这个 jar 里没有 build_info.properties'
                   '（编译时没跑 tools/gen_build_info.py）')

    # ---- B3：日志 revision == 当前 git HEAD ----
    # 注意 `+dirty` 后缀：指纹里的 revision 形如 `e347372+dirty`（编译那一刻工作区有未提交改动）。
    # 它表示「同一个 HEAD，但源码可能又改过」，**不是**另一个 revision —— 早期实现直接拿整串与
    # `git rev-parse --short HEAD` 比较，于是每一次「边改边测」都会被误报成「日志来自另一版代码」
    # （实测：同一份日志，B2 已经明确写着 revision=e347372+dirty，B3 却判为不一致）。
    # 正确口径：比较<b>去掉 dirty 后缀</b>的 hash；dirty 单独作为一条 note 提示「需重新编译」。
    current = git_revision()
    logged_raw = stamp.get('revision') or ''
    logged_hash = logged_raw.split('+')[0]
    logged_dirty = logged_raw.endswith('+dirty')
    if current is None:
        result.note('无法读取 git revision（无 git / 非仓库），跳过 B3')
    elif not logged_hash or logged_hash == '?':
        result.add(False, 'B3 revision matches HEAD',
                   '日志里的 revision 是 "?"（资源缺失），无法与当前 HEAD(%s) 比对' % current)
    else:
        result.add(logged_hash == current, 'B3 revision matches HEAD',
                   '日志=%s 当前=%s' % (logged_raw, current)
                   + ('' if logged_hash == current
                      else ' ⇒ 日志来自另一版代码，只能当基线'))
        if logged_hash == current and logged_dirty:
            result.note('日志指纹带 +dirty（编译时工作区有未提交改动）：同一 HEAD 下源码仍可能变过；'
                        '若未在改完代码后重新编译，这份日志不能代表当前源码')

    # ---- B4：会话起点晚于本次构建 ----
    info = read_build_info()
    built_at = info.get('builtAt')
    epoch = info.get('sourceEpochSeconds')
    try:
        built_epoch = int(epoch)
    except (TypeError, ValueError):
        built_epoch = None
    if built_epoch is None:
        result.note('build_info.properties 缺 sourceEpochSeconds，跳过 B4')
    else:
        log_epoch = int(log_path.stat().st_mtime)
        ok = log_epoch >= built_epoch
        result.add(ok, 'B4 session after build',
                   '日志最后写入=%s 本次构建=%s'
                   % (fmt(log_epoch), built_at or fmt(built_epoch))
                   + ('' if ok else ' ⇒ 构建晚于会话，日志是基线（项目判据：docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md）'))
    if sessions:
        result.note('日志内会话起点（RsccDiag 锚点，共 %d 次）：%s'
                    % (len(sessions), ', '.join(sessions[-3:])))
    else:
        result.note('日志内没有 [rscc] diag logging ON 锚点行 —— 该日志可能不是本模组跑出来的会话')

    return report(result)


def fmt(epoch):
    return datetime.datetime.fromtimestamp(epoch).strftime('%Y-%m-%dT%H:%M:%S')


def report(result):
    print('== 构建指纹校验（日志能否自证版本）==')
    for ok, name, detail in result.checks:
        print('  [%s] %-32s %s' % ('OK  ' if ok else 'FAIL', name, detail))
    for note in result.notes:
        print('  [note] %s' % note)
    failed = result.failed
    if failed:
        print('VERIFY BUILD STAMP FAILED (%d/%d)' % (len(failed), len(result.checks)))
        print('  处理：先 python tools/manual_compile.ps1（刷新指纹）→ 再启动游戏 → 再跑本脚本。')
        return 1
    print('VERIFY BUILD STAMP OK (%d checks) —— 这份日志可以用来证明当前源码的行为'
          % len(result.checks))
    return 0


if __name__ == '__main__':
    sys.exit(main())
