# -*- coding: utf-8 -*-
"""从实际存档的 region 文件里挖出「本模组执行仓的单元样板 + 类别归属」。

为什么需要它：蓝图（test_place.nbt）里的执行仓 UnitSlots 是空的，样板实际存在存档的
方块实体 NBT 里。本工具只读，不改任何文件。

用法：python tools/scan_save_chambers.py run/saves/test/region
"""

from __future__ import annotations

import glob
import io
import os
import struct
import sys
import zlib

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, "tools")
from read_schematic import Reader  # noqa: E402


def iter_chunks(path):
    """产出 (chunkX, chunkZ, 解压后的 NBT bytes)。"""
    with io.open(path, "rb") as handle:
        header = handle.read(8192)
        if len(header) < 8192:
            return
        for i in range(1024):
            offset = struct.unpack_from(">I", header, i * 4)[0]
            if offset == 0:
                continue
            sector = offset >> 8
            handle.seek(sector * 4096)
            length = struct.unpack(">I", handle.read(4))[0]
            comp = handle.read(1)[0]
            if length <= 5 or length > 8 * 1024 * 1024:
                continue
            payload = handle.read(length - 5)
            try:
                if comp == 1:
                    data = zlib.decompress(payload)
                elif comp == 2:
                    data = zlib.decompress(payload, 16 + zlib.MAX_WBITS)
                else:
                    continue
            except Exception:
                continue
            yield i % 32, i // 32, data


def main() -> int:
    region_dir = sys.argv[1] if len(sys.argv) > 1 else "run/saves/test/region"
    found = 0
    for path in sorted(glob.glob(os.path.join(region_dir, "*.mca"))):
        for cx, cz, data in iter_chunks(path):
            if b"sequence_execution_chamber" not in data:
                continue
            try:
                reader = Reader(data)
                if reader.u1() != 10:
                    continue
                reader.s()
                chunk = reader.payload(10)
            except Exception:
                continue
            found += 1
            print("=== %s chunk(%d,%d)" % (os.path.basename(path), cx, cz))
            dump_chunk(chunk)
            print()
    print("命中区块数:", found)
    return 0


def dump_chunk(chunk) -> None:
    """在区块 NBT 里递归找 block_entities。"""
    sections = []
    stack = [("", chunk)]
    while stack:
        name, node = stack.pop()
        if not isinstance(node, dict):
            continue
        for key, value in node.items():
            path = name + "/" + key
            if key in ("block_entities", "blockEntities") and isinstance(value, list):
                sections.extend(value)
            elif isinstance(value, dict):
                stack.append((path, value))
    for be in sections:
        be_id = be.get("id", "")
        if "sequence_execution_chamber" not in str(be_id):
            continue
        print("  %s @ %s" % (be_id, be.get("x"), be.get("y"), be.get("z")))
        for key in ("ChamberName", "ChamberRecipeType", "OutputMode", "FaceModes"):
            if key in be:
                print("     %-22s %r" % (key, be.get(key)))
        units = be.get("UnitSlots")
        if isinstance(units, list):
            print("     UnitSlots: %d 项" % len(units))
            for unit in units:
                if isinstance(unit, dict):
                    brief = {k: unit.get(k) for k in
                             ("recipe", "step", "machinePos", "input", "requiresInput", "displayName")
                             if k in unit}
                    print("        %r" % (brief,))
        owners = be.get("BusCategoryOwners")
        if owners:
            print("     BusCategoryOwners: %r" % (owners,))


if __name__ == "__main__":
    sys.exit(main())
