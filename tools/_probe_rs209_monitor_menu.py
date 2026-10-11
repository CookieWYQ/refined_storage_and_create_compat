# -*- coding: utf-8 -*-
"""可见性链路（续）：菜单继承链是否在 2.0.9 里变了。

为什么单独查这一条：客户端按钮可见性的**唯一**入口是
`getMenu() instanceof RsccAssemblyMonitorBridge`。桥接接口是注入到
`AbstractAutocraftingMonitorContainerMenu` 上的 —— 一旦用户实跑的 RS 换了具体菜单类、
或让它不再继承那个抽象基类，`instanceof` 只会**静默**返回 false（不抛异常、不崩游戏），
`rscc$currentTaskId()` 从此恒为 null，`alertOf(null)` 恒为 null，
于是三个按钮一个都不画。这与「不是时有时无、而是一直没有」的现象完全吻合，
所以必须用两边的真实字节码把继承链钉死。
"""
import os
import re
import shutil
import subprocess
import sys

JAVAP = r'D:\java21\bin\javap.exe'
JAR = r'D:\java21\bin\jar.exe'
WS = r'D:\MODS\refined_storage_and_create_compat'
OUT = os.path.join(WS, 'build', 'rs209_menu')

RS_200 = (r'C:\Users\70432\.gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage'
          r'\refinedstorage-neoforge\2.0.0\6a7b21cb50df2cc7170accb80f7b7f86e7bf5eff'
          r'\refinedstorage-neoforge-2.0.0.jar')
RS_209 = os.path.join(WS, 'build', 'rs209_probe.jar')

# 两个包里所有和「监视器 / 菜单」有关的类型
PREFIXES = (
    'com/refinedmods/refinedstorage/common/autocrafting/monitor/',
    'com/refinedmods/refinedstorage/common/autocrafting/',
)


def listing(jar):
    r = subprocess.run([JAR, 'tf', jar], capture_output=True)
    names = r.stdout.decode('utf-8', 'replace').splitlines()
    return set(n for n in names if n.endswith('.class'))


def disasm(tag, jar, cls, listing_set):
    if cls not in listing_set:
        return None
    d = os.path.join(OUT, tag)
    os.makedirs(d, exist_ok=True)
    subprocess.run([JAR, 'xf', jar, cls], cwd=d, capture_output=True)
    path = os.path.join(d, cls.replace('/', os.sep))
    if not os.path.isfile(path):
        return None
    r = subprocess.run([JAVAP, '-p', cls.replace('/', os.sep)], cwd=d, capture_output=True)
    return r.stdout.decode('utf-8', 'replace')


def header(text):
    """只取类声明那一行（extends / implements），后面的成员签名版本间本来就会漂。"""
    for line in (text or '').splitlines():
        if line.startswith(('public ', 'final ', 'abstract ', 'class ', 'interface ')):
            return line.strip()
    return text or ''


def main():
    a_all, b_all = listing(RS_200), listing(RS_209)
    names = sorted(n for n in a_all | b_all
                   if n.startswith('com/refinedmods/refinedstorage/common/autocrafting/')
                   and 'monitor' in n.lower())
    bad = 0
    for cls in names:
        a = disasm('2.0.0', RS_200, cls, a_all)
        b = disasm('2.0.9', RS_209, cls, b_all)
        ha, hb = header(a), header(b)
        same = (ha == hb)
        print('%-4s %s' % ('OK' if same else 'DIFF', cls.rsplit('/', 1)[-1]))
        if not same:
            bad += 1
            print('      2.0.0: %s' % ha)
            print('      2.0.9: %s' % hb)
    print()
    print('监视器相关类型 %d 个，声明行不同 %d 个' % (len(names), bad))

    # 把「谁继承了 AbstractAutocraftingMonitorContainerMenu」单独列出来（两边对照）。
    # 只在上面的监视器相关类型里找，避免对整包做几千次 javap。
    base_simple = 'AbstractAutocraftingMonitorContainerMenu'
    print()
    for tag, jar, allset in (('2.0.0', RS_200, a_all), ('2.0.9', RS_209, b_all)):
        kids = []
        for cls in names:
            a = disasm(tag + '_kids', jar, cls, allset)
            if a and ('extends ' + base_simple) in header(a):
                kids.append(cls.rsplit('/', 1)[-1])
        print('RS %s：继承 %s 的监视器类型 = %s' % (tag, base_simple, kids))
    return 0


if __name__ == '__main__':
    sys.exit(main())
