# -*- coding: utf-8 -*-
"""把 v3 版序列装配样板终端 GUI 贴图从 tmp_textures 复制进 assets，并用 PIL 校验尺寸。

规则（与 SPT_GUI_TECH_DOC_V3.md 第一节一致）：
    tmp_textures/gui_spt_v3/sequence_pattern_terminal.png -> assets/.../textures/gui/sequence_pattern_terminal.png
    tmp_textures/gui_spt_v3/spt_*.png                     -> assets/.../textures/gui/spt/spt_*.png
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
SRC = os.path.join(ROOT, "tmp_textures", "gui_spt_v3")
GUI = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                   "textures", "gui")
SPT = os.path.join(GUI, "spt")

# 目标尺寸表（必须与文档第一节完全一致）
EXPECTED = {
    "sequence_pattern_terminal.png": (256, 320),
    "spt_card_bg.png": (8, 24),
    "spt_tabs.png": (120, 24),
    "spt_btn_small.png": (12, 36),
    "spt_btn_delete.png": (10, 30),
    "spt_btn_large.png": (56, 36),
    "spt_scrollbar_thumb.png": (20, 10),
}

# preview_main.png 只是效果预览，不复制
SKIP = {"preview_main.png", "SPT_GUI_TECH_DOC_V3.md"}


def target_of(name):
    """主背景放 gui 根目录，其余放 gui/spt/。"""
    return os.path.join(SPT, name) if name.startswith("spt_") else os.path.join(GUI, name)


def main():
    os.makedirs(SPT, exist_ok=True)
    copied = []
    for name in sorted(os.listdir(SRC)):
        if name in SKIP or not name.lower().endswith(".png"):
            continue
        src = os.path.join(SRC, name)
        dst = target_of(name)
        shutil.copyfile(src, dst)
        copied.append(name)
        print("[copy] %s -> %s" % (name, os.path.relpath(dst, ROOT)))

    print("\n[verify] 逐张读取目标文件并比对尺寸：")
    ok = True
    for name in sorted(EXPECTED):
        dst = target_of(name)
        if not os.path.exists(dst):
            print("  MISSING %s" % dst)
            ok = False
            continue
        with Image.open(dst) as im:
            size = im.size
            mode = im.mode
        want = EXPECTED[name]
        flag = "OK " if size == want else "BAD"
        if size != want:
            ok = False
        print("  %s %-32s size=%s expect=%s mode=%s" % (flag, name, size, want, mode))
    print("\n[result] %s (copied=%d)" % ("ALL TEXTURES OK" if ok else "SIZE MISMATCH", len(copied)))


if __name__ == "__main__":
    main()
