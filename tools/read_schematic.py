# -*- coding: utf-8 -*-
"""读取 Create 蓝图 (.nbt, gzip 压缩的 NBT) 并摘要输出方块与方块实体。

为什么自己写：Create 蓝图是标准 NBT（gzip），没有现成工具能把「方块实体里的全部数据」
按可读形式倒出来；本工具只读、不改任何文件。
"""

from __future__ import annotations

import gzip
import io
import struct
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


class Reader:
    def __init__(self, data: bytes):
        self.d = data
        self.i = 0

    def u1(self) -> int:
        v = self.d[self.i]
        self.i += 1
        return v

    def i1(self) -> int:
        v = struct.unpack_from(">b", self.d, self.i)[0]
        self.i += 1
        return v

    def i2(self) -> int:
        v = struct.unpack_from(">h", self.d, self.i)[0]
        self.i += 2
        return v

    def i4(self) -> int:
        v = struct.unpack_from(">i", self.d, self.i)[0]
        self.i += 4
        return v

    def i8(self) -> int:
        v = struct.unpack_from(">q", self.d, self.i)[0]
        self.i += 8
        return v

    def f4(self) -> float:
        v = struct.unpack_from(">f", self.d, self.i)[0]
        self.i += 4
        return v

    def f8(self) -> float:
        v = struct.unpack_from(">d", self.d, self.i)[0]
        self.i += 8
        return v

    def s(self) -> str:
        n = struct.unpack_from(">H", self.d, self.i)[0]
        self.i += 2
        v = self.d[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return v

    def payload(self, t: int):
        if t == 0:
            return None
        if t == 1:
            return self.i1()
        if t == 2:
            return self.i2()
        if t == 3:
            return self.i4()
        if t == 4:
            return self.i8()
        if t == 5:
            return self.f4()
        if t == 6:
            return self.f8()
        if t == 7:
            n = self.i4()
            v = list(self.d[self.i:self.i + n])
            self.i += n
            return v
        if t == 8:
            return self.s()
        if t == 9:
            et = self.u1()
            n = self.i4()
            return [self.payload(et) for _ in range(max(0, n))]
        if t == 10:
            out = {}
            while True:
                tt = self.u1()
                if tt == 0:
                    return out
                name = self.s()
                out[name] = self.payload(tt)
        if t == 11:
            n = self.i4()
            return [self.i4() for _ in range(max(0, n))]
        if t == 12:
            n = self.i4()
            return [self.i8() for _ in range(max(0, n))]
        raise ValueError("unknown tag %d" % t)


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "run/schematics/test_place.nbt"
    with gzip.open(path, "rb") as handle:
        raw = handle.read()
    reader = Reader(raw)
    t = reader.u1()
    assert t == 10, "root must be compound, got %d" % t
    root_name = reader.s()
    root = reader.payload(10)
    print("root=%r 总键=%d" % (root_name, len(root)))
    print("keys:", sorted(root.keys()))
    print()
    for key in sorted(root.keys()):
        value = root[key]
        if isinstance(value, dict):
            print("== %s (compound, %d keys): %s" % (key, len(value), sorted(value.keys())[:20]))
        elif isinstance(value, list):
            print("== %s (list, %d 项)" % (key, len(value)))
        else:
            print("== %s = %r" % (key, value))
    return 0


if __name__ == "__main__":
    sys.exit(main())
