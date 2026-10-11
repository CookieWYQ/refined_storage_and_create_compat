# -*- coding: utf-8 -*-
"""可见性链路：RS 2.0.0（我们编译目标）与 RS 2.0.9（用户实跑）逐个符号比对。

为什么要这一步：用户报「挂起 / 继续按钮一直没有」。按钮可见性 = alert.offers(bit)，
alert 来自服务端 AssemblyWatchdog.Record，Record 又来自扫描 RS 的网络状态 API
（AutocraftingNetworkComponent#getStatuses / TaskStatus#info#resource）与菜单的
currentTaskId 字段。屏幕类字节码已证同构，因此剩下的「2.0.9 特有」缺口只可能在
这几处 API 上。本脚本只做只读反汇编比对，不写任何工程文件。
"""
import os
import re
import shutil
import subprocess
import sys

JAVAP = r'D:\java21\bin\javap.exe'
JAR = r'D:\java21\bin\jar.exe'
WS = r'D:\MODS\refined_storage_and_create_compat'
OUT = os.path.join(WS, 'build', 'rs209_chain')

RS_200 = (r'C:\Users\70432\.gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage'
          r'\refinedstorage-neoforge\2.0.0\6a7b21cb50df2cc7170accb80f7b7f86e7bf5eff'
          r'\refinedstorage-neoforge-2.0.0.jar')
RS_209 = os.path.join(WS, 'build', 'rs209_probe.jar')

# 可见性链路上我们自己引用到的每一个 RS 类型。
WANT = (
    'com/refinedmods/refinedstorage/api/network/autocrafting/AutocraftingNetworkComponent.class',
    'com/refinedmods/refinedstorage/api/autocrafting/status/TaskStatus.class',
    'com/refinedmods/refinedstorage/common/autocrafting/monitor/AbstractAutocraftingMonitorContainerMenu.class',
    'com/refinedmods/refinedstorage/api/autocrafting/task/TaskId.class',
)

REPORT = []


def say(line=''):
    REPORT.append(line)
    print(line)


def disasm(tag, jar, cls):
    d = os.path.join(OUT, tag)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    r = subprocess.run([JAR, 'xf', jar, cls], cwd=d, capture_output=True)
    if r.returncode != 0:
        return None, r.stderr.decode('utf-8', 'replace')
    path = os.path.join(d, cls.replace('/', os.sep))
    if not os.path.isfile(path):
        return None, 'not in jar'
    r = subprocess.run([JAVAP, '-p', '-c', cls.replace('/', os.sep)],
                       cwd=d, capture_output=True)
    return r.stdout.decode('utf-8', 'replace'), None


def main():
    os.makedirs(OUT, exist_ok=True)
    bad = 0
    for cls in WANT:
        a, ea = disasm('2.0.0', RS_200, cls)
        b, eb = disasm('2.0.9', RS_209, cls)
        short = cls.rsplit('/', 1)[-1]
        say('=' * 78)
        say('%s' % short)
        say('=' * 78)
        if a is None or b is None:
            say('  2.0.0: %s   2.0.9: %s' % (ea, eb))
            bad += 1
            continue
        if a == b:
            say('  IDENTICAL  (%d bytes of javap -p -c)' % len(a))
            continue
        say('  *** DIFFERENT ***')
        bad += 1
        al, bl = a.splitlines(), b.splitlines()
        import difflib
        for line in difflib.unified_diff(al, bl, '2.0.0', '2.0.9', lineterm='', n=2):
            say('  ' + line)
    say('')
    say('DIFFERING CLASSES: %d / %d' % (bad, len(WANT)))
    with open(os.path.join(OUT, 'report.txt'), 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(REPORT) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
