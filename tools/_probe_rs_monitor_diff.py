# -*- coding: utf-8 -*-
"""逐行 diff RS 2.0.0 与 2.0.9 的 AutocraftingMonitorScreen verbose 反汇编。

目的：判定两版**字节码本身**是否相同。若只差 SourceFile / MD5 之类的元数据，
说明「2.0.9 改了任务按钮横向布局」的假设不成立（至少对这个类不成立），
必须另找显示缺陷的根因，而不是去改几何公式。
"""
import difflib
import io
import os
import sys

WS = r'D:\MODS\refined_storage_and_create_compat'
A = os.path.join(WS, 'build', 'v200.txt')
B = os.path.join(WS, 'build', 'v209.txt')

a = open(A, encoding='utf-8', errors='replace').read().splitlines()
b = open(B, encoding='utf-8', errors='replace').read().splitlines()

diff = list(difflib.unified_diff(a, b, '2.0.0', '2.0.9', lineterm='', n=0))
# 只看「真的不一样」的行，忽略 unified diff 的头部噪声
interesting = [l for l in diff
               if l[:1] in '+-' and not l.startswith(('+++', '---'))]

buf = io.StringIO()
buf.write('total diff lines: %d\n' % len(diff))
buf.write('changed lines   : %d\n' % len(interesting))
for line in diff[:200]:
    buf.write(line.replace('\ufffd', '?') + '\n')

text = buf.getvalue()
with open(os.path.join(WS, 'build', 'vdiff_report.txt'), 'w', encoding='utf-8') as fh:
    fh.write(text)
sys.stdout.write(text.encode('ascii', 'replace').decode('ascii'))
