# -*- coding: utf-8 -*-
"""把 tmp_textures 下的整图 GUI 贴图同步进 assets，并用 PIL 校验尺寸。

规则（与 SPT_GUI_TECH_DOC_V7.md §1、COLLECTION_CACHE_GUI_DOC_V4.md §1 一致）：
    tmp_textures/gui_spt_v7/sequence_pattern_terminal.png -> assets/.../textures/gui/sequence_pattern_terminal.png
    tmp_textures/gui_cc_v4/collection_cache.png           -> assets/.../textures/gui/collection_cache.png

注：SPT 出图版本目录随布局版本走（v6 → v7，底部样板区改版）；这里必须指向**当前**版本的
    `gui_spt_v<N>`，否则会把旧布局的整图覆盖回 assets（几何与 verify_gui_layout 立刻不一致）。
"""
import os
import shutil

from PIL import Image
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
GUI = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                   "textures", "gui")

# (源相对路径, 目标相对路径, 期望尺寸)
JOBS = [
    ("tmp_textures/gui_spt_v7/sequence_pattern_terminal.png",
     "sequence_pattern_terminal.png", (256, 324)),
    ("tmp_textures/gui_cc_v4/collection_cache.png",
     "collection_cache.png", (256, 324)),
]


def main():
    ok = True
    print("[copy] 复制贴图：")
    for src_rel, dst_rel, want in JOBS:
        src = os.path.join(ROOT, src_rel.replace("/", os.sep))
        dst = os.path.join(GUI, dst_rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.exists(src):
            print("  MISSING 源文件: %s" % src)
            ok = False
            continue
        shutil.copyfile(src, dst)
        print("  %s -> %s" % (src_rel, os.path.relpath(dst, ROOT)))

    print("\n[verify] PIL 读回目标文件并比对尺寸：")
    for src_rel, dst_rel, want in JOBS:
        dst = os.path.join(GUI, dst_rel.replace("/", os.sep))
        if not os.path.exists(dst):
            print("  MISSING %s" % dst)
            ok = False
            continue
        with Image.open(dst) as im:
            size, mode = im.size, im.mode
        flag = "OK " if size == want else "BAD"
        if size != want:
            ok = False
        print("  %s %-32s size=%s expect=%s mode=%s" % (flag, dst_rel, size, want, mode))

    print("\n[result] %s" % ("ALL TEXTURES OK" if ok else "SIZE MISMATCH"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
