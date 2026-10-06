"""
像素扫描 GUI 背景图，反推槽位/滚动条/面板坐标。
用法:  python tools/scan_gui_sprites.py <背景PNG>
输出: 控制台打印检测结果 + tmp_textures/gui_brief/_detected.png 辅助图。
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# -------- 常量 --------
SLOT = 18               # 槽位格子边长 (宽 = 高 = 18)
SLOT_DELTA = 30         # 槽位四角 vs 中心的最小颜色差 (欧氏距离)
SCROLL_MIN_W = 7        # 滚动条暗槽最小宽度
SCROLL_MAX_W = 12       # 滚动条暗槽最大宽度
SCROLL_MIN_H = 30       # 滚动条暗槽最小高度
SCROLL_DARK_THRESHOLD = 85  # 暗槽 RGB 分量都 <= 此值

OUT_LABEL = {
    "slot":     (255,   0,   0, 255),   # 红
    "scroll":   (  0, 255,   0, 255),   # 绿
    "panel":    (  0,   0, 255, 255),   # 蓝
}


def load_rgba(path: Path):
    im = Image.open(path).convert("RGBA")
    arr = np.array(im)
    return im, arr  # shape (H, W, 4)


def color_delta(a: np.ndarray, b: np.ndarray) -> float:
    """每个像素都是 (R,G,B) 三元组，返回欧氏距离。"""
    d = a.astype(int) - b.astype(int)
    return float(np.sqrt(np.sum(d * d)))


def detect_slots(arr: np.ndarray) -> list[tuple[int, int]]:
    """扫描所有 18x18 可能的槽位左上角 (sx,sy)。
    判定：四角颜色与中心颜色差 > SLOT_DELTA。
    返回去重后按 x,y 排序的坐标列表。"""
    H, W, _ = arr.shape
    found: set[tuple[int, int]] = set()
    cx0, cy0 = SLOT // 2, SLOT // 2       # 中心相对偏移 (9, 9)
    for sy in range(0, H - SLOT + 1):
        for sx in range(0, W - SLOT + 1):
            tl = arr[sy,     sx,     :3].astype(int)
            tr = arr[sy,     sx + SLOT - 1, :3].astype(int)
            bl = arr[sy + SLOT - 1, sx,     :3].astype(int)
            br = arr[sy + SLOT - 1, sx + SLOT - 1, :3].astype(int)
            center = arr[sy + cy0, sx + cx0, :3].astype(int)
            corners_mean = (tl + tr + bl + br) / 4
            d = np.linalg.norm(corners_mean - center)
            if d >= SLOT_DELTA:
                found.add((sx, sy))
    # 去重 + 排序：同一组槽位经常因为相邻像素相似产生多次相邻命中，
    # 取 x 间距 >= 9 的 (允许半格重叠)，但槽位应该严格按 18px 网格对齐，
    # 所以最后再按 18px 网格过滤一次。
    if not found:
        return []
    coords = sorted(found)
    # 按 x 对齐
    x_candidates = sorted({c[0] for c in coords})
    x_fixed = []
    for x in x_candidates:
        # 多数命中都在这个 x 附近 (±3)，取最常见
        near = [cx for cx in x_candidates if abs(cx - x) <= 3]
        x_fixed.append(int(np.median(near)))
    x_fixed = sorted(set(x_fixed))

    y_candidates = sorted({c[1] for c in coords})
    y_fixed = []
    for y in y_candidates:
        near = [cy for cy in y_candidates if abs(cy - y) <= 3]
        y_fixed.append(int(np.median(near)))
    y_fixed = sorted(set(y_fixed))

    # 用 median 过的 x/y 重新笛卡尔组合，看哪些组合的 delta 仍达标
    result = []
    for x in x_fixed:
        for y in y_fixed:
            if 0 <= y < H - SLOT + 1 and 0 <= x < W - SLOT + 1:
                tl = arr[y,     x,     :3].astype(int)
                tr = arr[y,     x + SLOT - 1, :3].astype(int)
                bl = arr[y + SLOT - 1, x,     :3].astype(int)
                br = arr[y + SLOT - 1, x + SLOT - 1, :3].astype(int)
                center = arr[y + cy0, x + cx0, :3].astype(int)
                corners_mean = (tl + tr + bl + br) / 4
                d = float(np.linalg.norm(corners_mean - center))
                if d >= SLOT_DELTA:
                    result.append((x, y))
    return sorted(result)


def detect_scroll_tracks(arr: np.ndarray) -> list[tuple[int, int, int, int]]:
    """检测竖向连续深灰条。返回 (x, y, w, h)。
    判定: 宽度 7..12，高度 > 30，内部所有像素 RGB 分量均 <= SCROLL_DARK_THRESHOLD。"""
    H, W, _ = arr.shape
    # 构造深度 mask：每个像素是否属于暗槽 (r,g,b 都 <= 85)
    dark = np.all(arr[:, :, :3] <= SCROLL_DARK_THRESHOLD, axis=2)

    tracks = []
    visited = np.zeros((H, W), dtype=bool)

    for sy in range(H):
        for sx in range(W):
            if not dark[sy, sx] or visited[sy, sx]:
                continue
            # BFS 找同一竖条 (尽量只找竖向连续)
            # 简化：从 (sx, sy) 向下延伸直到不是暗色
            ey = sy
            while ey + 1 < H and dark[ey + 1, sx]:
                ey += 1
            h = ey - sy + 1
            # 再向左/向右扩展确定宽度
            ex = sx
            while ex + 1 < W and np.all(dark[sy:ey + 1, ex + 1]):
                ex += 1
            x2 = sx
            while x2 - 1 >= 0 and np.all(dark[sy:ey + 1, x2 - 1]):
                x2 -= 1
            w = ex - x2 + 1
            # 标记访问
            visited[sy:ey + 1, x2:ex + 1] = True
            if SCROLL_MIN_W <= w <= SCROLL_MAX_W and h >= SCROLL_MIN_H:
                tracks.append((x2, sy, w, h))

    # 合并: 如果两个 track 几乎相同 (x 差 <= 1) 合并
    merged = []
    for t in sorted(tracks):
        if merged and abs(t[0] - merged[-1][0]) <= 1 and abs(t[1] - merged[-1][1]) <= 2:
            prev = merged[-1]
            mx = min(prev[0], t[0])
            my = min(prev[1], t[1])
            mx2 = max(prev[0] + prev[2], t[0] + t[2])
            my2 = max(prev[1] + prev[3], t[1] + t[3])
            merged[-1] = (mx, my, mx2 - mx, my2 - my)
        else:
            merged.append(t)
    return sorted(merged)


def detect_panels(arr: np.ndarray) -> list[tuple[int, int, int, int]]:
    """检测面板凹陷区 (内部颜色集中在 ~139/175 附近)。
    用连通域找出内部色值为面板基底色的矩形区域。"""
    H, W, _ = arr.shape

    # 面板候选色: 扫描背景中高频出现的中灰值
    pixels = arr[:, :, :3].reshape(-1, 3)
    # 常见面板底色: 175/175/175, 边框 139 或 120 等暗色
    gray = pixels.mean(axis=1).astype(int)
    # 看一下 130..180 区间的高频
    counts = {}
    for g in gray:
        if 130 <= g <= 180:
            counts[g] = counts.get(g, 0) + 1
    if not counts:
        return []
    base_gray = max(counts, key=counts.get)

    # mask: 灰度接近 base_gray (±10) 的像素
    mask = np.abs(gray.astype(int) - base_gray) <= 10
    mask = mask.reshape(H, W)

    # 找连通域
    visited = np.zeros((H, W), dtype=bool)
    panels = []
    for y in range(H):
        for x in range(W):
            if not mask[y, x] or visited[y, x]:
                continue
            # BFS
            stack = [(x, y)]
            pixels = []
            visited[y, x] = True
            while stack:
                cx, cy = stack.pop()
                pixels.append((cx, cy))
                for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < W and 0 <= ny < H and mask[ny, nx] and not visited[ny, nx]:
                        visited[ny, nx] = True
                        stack.append((nx, ny))
            if len(pixels) < 200:
                continue
            xs = [p[0] for p in pixels]
            ys = [p[1] for p in pixels]
            panels.append((min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1))
    # 过滤太小的 (宽/高都要 > 20)
    panels = [p for p in panels if p[2] >= 20 and p[3] >= 20]
    # 合并重叠
    merged = []
    for p in sorted(panels):
        px, py, pw, ph = p
        while merged:
            qx, qy, qw, qh = merged[-1]
            if not (px + pw < qx or qx + qw < px or py + ph < qy or qy + qh < py):
                mx = min(px, qx); my = min(py, qy)
                mx2 = max(px + pw, qx + qw); my2 = max(py + ph, qy + qh)
                merged[-1] = (mx, my, mx2 - mx, my2 - my)
                px, py, pw, ph = merged[-1]
            else:
                break
        if not merged or merged[-1] != p:
            merged.append(p)
    return sorted(merged)


def draw_overlays(im: Image.Image,
                  slots: list[tuple[int, int]],
                  scrolls: list[tuple[int, int, int, int]],
                  panels: list[tuple[int, int, int, int]],
                  out_path: Path):
    out = im.copy()
    d = ImageDraw.Draw(out)
    for x, y in slots:
        d.rectangle([x, y, x + SLOT - 1, y + SLOT - 1],
                    outline=OUT_LABEL["slot"], width=1)
    for x, y, w, h in scrolls:
        d.rectangle([x, y, x + w - 1, y + h - 1],
                    outline=OUT_LABEL["scroll"], width=1)
    for x, y, w, h in panels:
        d.rectangle([x, y, x + w - 1, y + h - 1],
                    outline=OUT_LABEL["panel"], width=1)
    out.save(out_path)


def run_scan(png_path: Path, out_path: Path) -> dict:
    print(f"\n==== 扫描: {png_path.name} ({png_path.stat().st_size} bytes) ====")
    im, arr = load_rgba(png_path)
    H, W, _ = arr.shape
    print(f"图像尺寸: {W}x{H}")

    slots = detect_slots(arr)
    scrolls = detect_scroll_tracks(arr)
    panels = detect_panels(arr)

    print(f"\n--- 槽位格子 (18x18, 共 {len(slots)} 个) ---")
    for sx, sy in slots:
        print(f"  sprite ({sx:3d}, {sy:3d})  Menu ({sx + 1:3d}, {sy + 1:3d})")

    print(f"\n--- 滚动条暗槽 (共 {len(scrolls)} 条) ---")
    for sx, sy, w, h in scrolls:
        print(f"  sprite ({sx:3d}, {sy:3d})  {w}x{h}")

    print(f"\n--- 面板凹陷 (共 {len(panels)} 块) ---")
    for sx, sy, w, h in panels:
        print(f"  sprite ({sx:3d}, {sy:3d})  {w}x{h}")

    draw_overlays(im, slots, scrolls, panels, out_path)
    print(f"\n辅助图已保存: {out_path}")

    return {"slots": slots, "scrolls": scrolls, "panels": panels}


def main() -> int:
    if len(sys.argv) < 2:
        print("用法:  python scan_gui_sprites.py <背景PNG>")
        print("      (默认扫描 gui_brief 下两张图)")
        root = Path(__file__).resolve().parent.parent
        inputs = [
            root / "tmp_textures" / "gui_brief" / "sequence_pattern_terminal.png",
            root / "tmp_textures" / "gui_brief" / "collection_cache.png",
        ]
        out_dir = root / "tmp_textures" / "gui_brief"
    else:
        inputs = [Path(sys.argv[1])]
        out_dir = inputs[0].parent

    out_dir.mkdir(parents=True, exist_ok=True)

    for p in inputs:
        if not p.exists():
            print(f"跳过不存在的文件: {p}")
            continue
        out_path = out_dir / f"_detected_{p.stem}.png"
        run_scan(p, out_path)

    return 0


if __name__ == "__main__":
    sys.exit(main())
