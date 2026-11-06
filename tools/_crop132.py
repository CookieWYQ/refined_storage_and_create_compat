"""Round-132 截图裁切助手：把用户截图的局部放大，便于核对几何（只读附件，不改原图）。

用法： python tools/_crop132.py <src.png> <x0> <y0> <x1> <y1> <scale> <out.png>
"""
import sys

from PIL import Image


def main() -> int:
    if len(sys.argv) != 8:
        print(__doc__)
        return 2
    src, x0, y0, x1, y1, scale, out = sys.argv[1:]
    img = Image.open(src).convert("RGBA")
    box = (int(x0), int(y0), int(x1), int(y1))
    crop = img.crop(box)
    factor = float(scale)
    if factor != 1.0:
        crop = crop.resize(
            (max(1, int(crop.width * factor)), max(1, int(crop.height * factor))),
            Image.LANCZOS,
        )
    crop.save(out)
    print(f"OK {box} -> {out} ({crop.width}x{crop.height})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
