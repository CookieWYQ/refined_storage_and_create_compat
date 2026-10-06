"""复制 GUI brief 新贴图到资源目录，并用 PIL 校验尺寸。"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

from PIL import Image
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "tmp_textures" / "gui_brief"
DST_ROOT = ROOT / "src" / "main" / "resources" / "assets" / "rs_create_compat" / "textures"

# 复制表: (源文件名, 目标相对路径, 期望尺寸 (宽, 高))
COPY_TABLE: list[tuple[str, str, tuple[int, int]]] = [
    ("sequence_pattern_terminal.png", "gui/sequence_pattern_terminal.png", (256, 320)),
    ("collection_cache.png", "gui/collection_cache.png", (256, 246)),
]


def main() -> int:
    ok_all = True
    for src_name, dst_rel, expected in COPY_TABLE:
        src = SRC_DIR / src_name
        dst = DST_ROOT / dst_rel
        if not src.exists():
            print(f"FAIL  缺失源文件: {src}")
            ok_all = False
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print(f"COPY  {src_name} -> {dst_rel}")

        # 用 PIL 打开校验
        try:
            with Image.open(dst) as im:
                im.load()
                actual = im.size
            if actual == expected:
                print(f"  OK  {src_name}: {actual[0]}x{actual[1]}  (期望 {expected[0]}x{expected[1]})")
            else:
                print(f"  FAIL  {src_name}: 实际 {actual[0]}x{actual[1]}  != 期望 {expected[0]}x{expected[1]}")
                ok_all = False
        except Exception as e:
            print(f"  FAIL  PIL 打开 {dst} 出错: {e}")
            ok_all = False

    print()
    if not ok_all:
        print("** 存在校验失败，已停止，请检查上方 FAIL 项。**")
        return 1
    print("全部复制 & 尺寸校验通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
