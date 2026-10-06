# -*- coding: utf-8 -*-
"""把 tmp_textures/gui_child_panels/ 里的 5 张子窗口面板贴图复制进 assets。

复制后用 PIL 读回校验「尺寸 + RGBA」，逐条打印结果；任一不符即报错退出。
用法：python tools/copy_child_panel_textures.py
"""
import os
import shutil
import sys

from PIL import Image
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "tmp_textures", "gui_child_panels")
DST_GUI = os.path.join(
    ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "gui")

# (源文件名, 目标相对 gui/ 的路径, 期望宽, 期望高)
FILES = [
    ("advanced_quantity_keeper.png", "advanced_quantity_keeper.png", 210, 210),
    ("chamber_binding_config.png", "chamber_binding_config.png", 200, 190),
    ("marker_config.png", os.path.join("collection_cache", "marker_config.png"), 256, 220),
    ("result_config.png", os.path.join("spt", "result_config.png"), 190, 132),
    ("range_config.png", os.path.join("collection_cache", "range_config.png"), 200, 126),
]


def main() -> int:
    problems = []
    print("源目录: %s" % SRC_DIR)
    print("目标根: %s" % DST_GUI)
    print("-" * 72)

    for src_name, rel, exp_w, exp_h in FILES:
        src = os.path.join(SRC_DIR, src_name)
        dst = os.path.join(DST_GUI, rel)
        if not os.path.exists(src):
            problems.append("源文件不存在: %s" % src)
            print("[X] %-30s 源文件不存在" % src_name)
            continue

        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)

        # 复制后用 PIL 读回复核
        with Image.open(dst) as img:
            w, h = img.size
            mode = img.mode
            has_alpha = "A" in mode
            # 统计非圆角透明像素数量（面板只允许四角透明）
            alpha_px = 0
            if has_alpha:
                rgba = img.convert("RGBA")
                alpha_px = sum(1 for a in rgba.getdata() if a[3] < 255)

        size_ok = (w, h) == (exp_w, exp_h)
        mode_ok = mode == "RGBA" and has_alpha
        status = "OK " if (size_ok and mode_ok) else "ERR"
        print("[%s] %-30s -> %s" % (status, src_name, os.path.relpath(dst, ROOT)))
        print("      尺寸 %dx%d (期望 %dx%d) / 模式 %s" % (w, h, exp_w, exp_h, mode))
        print("      透明像素 %d 个" % alpha_px)
        if not size_ok:
            problems.append("%s 尺寸 %dx%d != 期望 %dx%d" % (src_name, w, h, exp_w, exp_h))
        if not mode_ok:
            problems.append("%s 模式 %s 不是 RGBA" % (src_name, mode))

    print("-" * 72)
    if problems:
        for p in problems:
            print("[X] %s" % p)
        print("复制结果: %d 个问题" % len(problems))
        return 1
    print("复制结果: 全部 %d 张通过（尺寸 + RGBA 校验）" % len(FILES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
