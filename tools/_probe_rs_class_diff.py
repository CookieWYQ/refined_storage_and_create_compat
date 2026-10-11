# -*- coding: utf-8 -*-
"""比较 RS 2.0.0 / 2.0.9 中若干类的 javap -c 反汇编（忽略 Classfile/SHA 等元数据头）。

用途：判断「2.0.9 是否改过某个类的**行为**」。只有反汇编本身不同，才说明行为变了；
class 文件 hash 不同可能仅来自编译器重排 + lambda 形参改名（不改变语义）。
"""
import difflib
import io
import os
import sys

WS = r'D:\MODS\refined_storage_and_create_compat'
BUILD = os.path.join(WS, 'build')

PAIRS = [
    ('AutocraftingMonitorScreen', r'rs_geom\screen_2.0.0.txt', r'rs_geom\screen_2.0.9.txt'),
    ('AbstractBaseScreen', 'abs200.txt', 'abs209.txt'),
]

SKIP_PREFIX = ('Classfile', '  Last modified', '  SHA-256', 'Compiled from')


def load(path):
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    return [l for l in lines if not l.startswith(SKIP_PREFIX)]


def main():
    buf = io.StringIO()
    for name, fa, fb in PAIRS:
        a = load(os.path.join(BUILD, fa))
        b = load(os.path.join(BUILD, fb))
        # 忽略 constant-pool 的编号漂移：把 "#123" 归一成 "#?"
        norm = lambda ls: [__import__('re').sub(r'#\d+', '#?', l) for l in ls]
        na, nb = norm(a), norm(b)
        d = [l for l in difflib.unified_diff(na, nb, fa, fb, lineterm='', n=0)
             if l[:1] in '+-' and not l.startswith(('+++', '---'))]
        buf.write('%s: raw-diff-lines=%d  normalized-diff-lines=%d\n'
                  % (name, len(a) - len(b) if False else -1, len(d)))
        for line in d[:120]:
            buf.write('  ' + line + '\n')
        buf.write('\n')
    text = buf.getvalue()
    with open(os.path.join(BUILD, 'absdiff_report.txt'), 'w', encoding='utf-8') as fh:
        fh.write(text)
    sys.stdout.write(text.encode('ascii', 'replace').decode('ascii'))


if __name__ == '__main__':
    main()
