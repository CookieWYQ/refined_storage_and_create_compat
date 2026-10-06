"""扫描 GUI 背景贴图里的槽位框，输出精灵坐标（= 槽位框左上角）。

用法:
    python tools/scan_gui_layout.py <png> [min_size]

原理:
    槽位内部是均匀的浅灰（默认 139,139,139），四周是更深的框线。
    找出所有 N×N 的均匀浅灰块（N 为最大匹配边长），其左上角减 1 即槽位框
    （也就是本工程约定里的“精灵坐标”，Menu 坐标 = 精灵坐标 + 1）。

输出:
    每个槽位的精灵坐标，并按行分组，便于和设计文档对照。
"""

import sys
from collections import defaultdict

import numpy as np
from PIL import Image
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


INTERIOR = (139, 139, 139)  # 槽位内部底色
FRAME_MAX = 138  # 小于该灰度视为框线


def find_interiors(arr: np.ndarray, size: int):
    """找出所有 size×size 的均匀 INTERIOR 块，返回其左上角列表。"""
    h, w, _ = arr.shape
    hits = []
    for y in range(1, h - size):
        for x in range(1, w - size):
            if arr[y, x, 0] != INTERIOR[0]:
                continue
            block = arr[y:y + size, x:x + size, 0]
            if block.min() != INTERIOR[0] or block.max() != INTERIOR[0]:
                continue
            # 上边与左边必须存在更深的框线
            if arr[y - 1, x + size // 2, 0] <= FRAME_MAX and arr[y + size // 2, x - 1, 0] <= FRAME_MAX:
                hits.append((x - 1, y - 1))  # 精灵坐标
    return hits


def dedup(points, tol=4):
    out = []
    for p in sorted(points):
        if not any(abs(p[0] - q[0]) < tol and abs(p[1] - q[1]) < tol for q in out):
            out.append(p)
    return out


def group_rows(points):
    rows = defaultdict(list)
    for x, y in points:
        rows[y].append(x)
    return {y: sorted(xs) for y, xs in sorted(rows.items())}


def find_tracks(arr: np.ndarray):
    """找竖直滚动条暗槽：宽度 5..14、高度 >= 40 的深色竖带。"""
    h, w, _ = arr.shape
    tracks = []
    for x in range(1, w - 1):
        col = arr[:, x, 0]
        if col.min() > 120:
            continue
        best = 0
        run = 0
        for v in col:
            if v <= 120:
                run += 1
                best = max(best, run)
            else:
                run = 0
        if best < 40:
            continue
        # 宽度：向右扩展直到不是深色
        x2 = x
        while x2 < w and arr[30, x2, 0] <= 120:
            x2 += 1
        tracks.append((x, x2 - x, best))
    out = []
    for t in tracks:
        if not out or t[0] - out[-1][0] > 4:
            out.append(t)
    return out


def main():
    path = sys.argv[1]
    img = Image.open(path).convert('RGB')
    arr = np.array(img)
    h, w, _ = arr.shape
    print('=== %s  size=%dx%d ===' % (path, w, h))

    # 槽位内部边长：尝试 17..14，取命中数最多的边长
    best_size, best_hits = 16, []
    for size in (17, 16, 15, 14):
        pts = dedup(find_interiors(arr, size))
        if len(pts) > len(best_hits):
            best_size, best_hits = size, pts
    print('槽位内部边长 = %d，检测到 %d 个槽位' % (best_size, len(best_hits)))

    rows = group_rows(best_hits)
    for y, xs in rows.items():
        gaps = [xs[i + 1] - xs[i] for i in range(len(xs) - 1)]
        gap = max(set(gaps), key=gaps.count) if gaps else 0
        print('  y=%3d  n=%2d  x=%s  gap=%s' % (y, len(xs), xs, gap))

    print('滚动条暗槽:')
    for x, tw, th in find_tracks(arr):
        print('  x=%3d  w=%2d  h>=%d' % (x, tw, th))
    print()


if __name__ == '__main__':
    main()
