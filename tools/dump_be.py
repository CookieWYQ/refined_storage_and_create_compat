# -*- coding: utf-8 -*-
"""把蓝图里「本模组方块」的方块实体 NBT 全部倒出来（只读）。"""

from __future__ import annotations

import gzip
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, "tools")
from read_schematic import Reader  # noqa: E402

MINE = "rs_create_compat"


def load(path):
    with gzip.open(path, "rb") as handle:
        raw = handle.read()
    reader = Reader(raw)
    assert reader.u1() == 10
    reader.s()
    return reader.payload(10)


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "run/schematics/test_place.nbt"
    root = load(path)
    palette = root.get("palette") or []
    print("size =", root.get("size"))
    print()
    for entry in root.get("blocks") or []:
        state = entry.get("state")
        if state is None or state >= len(palette):
            continue
        name = palette[state].get("Name")
        nbt = entry.get("nbt")
        if nbt is None:
            continue
        pos = entry.get("pos")
        # 全部有 NBT 的都倒（我方 + RS + Create 的机器都有配置）
        if MINE not in name and "refinedstorage" not in name and "create" not in name:
            continue
        print("### %s @ %s" % (name, pos))
        for key in sorted(nbt.keys()):
            value = nbt[key]
            text = repr(value)
            if len(text) > 700:
                text = text[:700] + " ...(%d chars)" % len(text)
            print("     %-28s %s" % (key, text))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
