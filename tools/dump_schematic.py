# -*- coding: utf-8 -*-
"""把一个 Create 蓝图倒成可读清单：调色板、尺寸、每个方块的坐标 + 全部 NBT 数据。"""

from __future__ import annotations

import gzip
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, "tools")
from read_schematic import Reader  # noqa: E402


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
    print("size =", root.get("size"))
    print()
    print("=== palette（%d 项）===" % len(root.get("palette") or []))
    for i, entry in enumerate(root.get("palette") or []):
        name = entry.get("Name") if isinstance(entry, dict) else entry
        props = entry.get("Properties") if isinstance(entry, dict) else None
        print("  [%2d] %-58s %s" % (i, name, props or ""))
    print()
    print("=== blocks（%d 项）===" % len(root.get("blocks") or []))
    for entry in root.get("blocks") or []:
        pos = entry.get("pos")
        state = entry.get("state")
        nbt = entry.get("nbt")
        print("  pos=%-16s state=%-3s%s" % (pos, state, "" if not nbt else ""))
        if nbt:
            for key in sorted(nbt.keys()):
                value = nbt[key]
                text = repr(value)
                if len(text) > 300:
                    text = text[:300] + "...(%d)" % len(text)
                print("        %-24s %s" % (key, text))
    return 0


if __name__ == "__main__":
    sys.exit(main())
