# -*- coding: utf-8 -*-
"""从 RS 2.0.0 / 2.0.9 真实 jar 里取出 AutocraftingMonitorScreen 的几何常量。

为什么要这么取：本工程的 gradle.properties 对着 RS 2.0.0 编译，而用户实际跑 2.0.9。
判断「按钮放不下（primaryFits=false）」是不是显示缺陷的根因，必须用**两边各自真实的字节码**
而非任何一版源码 —— 因为 RS 在 2.0.9 里重排过任务按钮的横向布局。

用 javap 的反汇编（javac 的常量会以 sipush/bipush/iconst 内联出现），
再按方法切块打印关键行，供人工比对。
"""
import os
import re
import shutil
import subprocess
import sys

JAVAP = r'D:\java21\bin\javap.exe'
JAR = r'D:\java21\bin\jar.exe'
WS = r'D:\MODS\refined_storage_and_create_compat'
OUT = os.path.join(WS, 'build', 'rs_geom')
CLS = 'com/refinedmods/refinedstorage/common/autocrafting/monitor/AutocraftingMonitorScreen.class'

SOURCES = {
    '2.0.0': r'C:\Users\70432\.gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage'
             r'\refinedstorage-neoforge\2.0.0\6a7b21cb50df2cc7170accb80f7b7f86e7bf5eff'
             r'\refinedstorage-neoforge-2.0.0.jar',
    '2.0.9': os.path.join(WS, 'build', 'rs209_probe.jar'),
}

WANT = ('init', 'initTaskButtons', 'getSideButtonX', 'getTaskButtonsInnerX',
        'getTaskButtonsInnerY', 'getTaskButtonY', 'renderLabels')

# 结论直接写成 ASCII 报告：Windows 控制台会把中文输出按 GBK 二次编码，
# 混流后文件就不是合法 UTF-8 了（read 工具会判定为二进制）。
REPORT = []


def say(line=''):
    REPORT.append(line)
    print(line)


def dump(version, jar):
    """把某个版本的类单独展开到自己的目录里再反汇编（防止两个版本的 class 互相覆盖）。"""
    d = os.path.join(OUT, version)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    subprocess.run([JAR, 'xf', jar, CLS], cwd=d, check=True)
    out = os.path.join(OUT, 'screen_%s.txt' % version)
    with open(out, 'w', encoding='utf-8') as fh:
        subprocess.run([JAVAP, '-p', '-c', CLS.replace('/', os.sep)],
                       cwd=d, check=True, stdout=fh, stderr=subprocess.STDOUT)
    return out


def methods(text):
    """切成 {方法签名: [字节码行]}；javap 的成员行以两个空格缩进、方法体以 'Code:' 起。"""
    blocks = {}
    cur = None
    for line in text.splitlines():
        m = re.match(r'^  (?:[\w<>,.\[\]$ ]+ )?(\w+)\(.*\);$', line)
        if m and line.startswith('  ') and not line.startswith('   '):
            cur = m.group(1)
            blocks.setdefault(cur, [])
            continue
        if line.strip() == 'Code:' or (cur and re.match(r'^\s+\d+:', line)):
            if cur:
                blocks[cur].append(line)
            continue
        if line.startswith('  ') and not line.startswith('   ') and not line.strip().startswith('Code'):
            cur = None
    return blocks


def main():
    os.makedirs(OUT, exist_ok=True)
    for version, jar in SOURCES.items():
        if not os.path.isfile(jar):
            say('MISSING %s -> %s' % (version, jar))
            return 2
        path = dump(version, jar)
        text = open(path, encoding='utf-8', errors='replace').read()
        blocks = methods(text)
        say('=' * 78)
        say('RS %s   (%s)' % (version, jar))
        say('=' * 78)
        say('methods: %s' % ', '.join(sorted(blocks)))
        for name in WANT:
            body = blocks.get(name)
            if not body:
                say('')
                say('--- %s: ABSENT ---' % name)
                continue
            say('')
            say('--- %s ---' % name)
            for line in body:
                say('   ' + line.strip())
    say('')
    say('disasm dir: %s' % OUT)
    with open(os.path.join(OUT, 'report.txt'), 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(REPORT) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
