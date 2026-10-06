# -*- coding: utf-8 -*-
"""只读检查：存档里到底有没有写下「候选组」（InputCandidates）。

为什么要看这个：用户反馈「铁粒还是只显示个铁粒，没有任何轮换」——
候选组要能显示，必须**先真的写进单元样板物品的 NBT**。这一步骗不了人：
存档是游戏自己写的，源码里写没写、写对没写对，在这里一查就知道。

另外顺带把「单元样板 NBT 里出现过的键名」列出来，用来核对写入端 / 读取端是不是同一个键。
"""

import glob
import io
import os
import re
import sys
import zlib

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAVES = os.path.join(ROOT, "run", "saves")

KEYS = [b"InputCandidates", b"Input", b"RecipeType", b"Recipe", b"Step", b"Machine",
        b"RequiresInput", b"DisplayName", b"InputFluid", b"sequence_unit_pattern",
        b"sequence_assembly_pattern"]


def region_bytes(path):
    """把 region 文件里每个 chunk 解压后拼起来（只读；失败就跳过该 chunk）。"""
    out = bytearray()
    with io.open(path, "rb") as handle:
        data = handle.read()
    if len(data) < 8192:
        return bytes(out)
    for i in range(1024):
        offset = int.from_bytes(data[i * 4:(i * 4) + 3], "big")
        count = data[(i * 4) + 3]
        if offset == 0 or count == 0:
            continue
        start = offset * 4096
        length = int.from_bytes(data[start:start + 4], "big")
        if length <= 0 or start + 4 + length > len(data):
            continue
        chunk = data[start + 5:start + 4 + length]
        try:
            out += zlib.decompress(chunk)
        except Exception:
            continue
    return bytes(out)


def main():
    if not os.path.isdir(SAVES):
        print("no saves dir: %s" % SAVES)
        return 1
    total = 0
    counts = {k: 0 for k in KEYS}
    for path in sorted(glob.glob(os.path.join(SAVES, "*", "region", "*.mca"))):
        blob = region_bytes(path)
        total += len(blob)
        for key in KEYS:
            n = blob.count(key)
            if n:
                counts[key] += n
        print("%-52s %9d bytes" % (os.path.relpath(path, SAVES), len(blob)))
    print()
    print("解压后总计 %d 字节" % total)
    print("--- 键名出现次数（整个存档） ---")
    for key in KEYS:
        print("  %-28s %d" % (key.decode(), counts[key]))
    print()
    if counts[b"InputCandidates"] == 0 and counts[b"sequence_unit_pattern"] > 0:
        print("结论：存档里有单元样板，但**没有任何 InputCandidates**")
        print("      ⇒ 候选组从未落盘 ⇒ 界面无论怎么读都只能显示单件代表物。")
        print("      ⇒ 断点在「写入端」（JEI 导入 / 生成总样板 / 菜单写单元样板 NBT），不在显示端。")
    elif counts[b"InputCandidates"] > 0:
        print("结论：候选组**确实写进了存档** ⇒ 断点在「读取/显示端」或「轮播驱动」。")
    else:
        print("结论：这个存档里没有单元样板（本次没生成过），需要先跑一次才能判定。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
