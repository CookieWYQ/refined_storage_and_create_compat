#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 128 轮：把「还没工具化」的 6 个界面背景用 make_gui_bg.py 的同一套素材重新生成。

工具 = `tools/make_gui_bg.py`（`pic/bg.png` 九宫格 4px 边框 + `pic/slot.png` 17×17 槽框烘焙）。
本脚本**只做拼装**，不发明任何新画风：直接调用 `make_gui_bg.draw_container`，
再按 **Menu 坐标 − 1** 贴槽框（与 `--slots-menu` 同一条约定，见 `tools/gui_slot_registry.py` 顶部）。

生成后逐张回读：尺寸 / 槽框中心色（17×17 模板内部 = (139,139,139)）/ 四角透明像素数。

用法：
    python tools/gen_round128_gui_bg.py            # 生成 6 张
    python tools/gen_round128_gui_bg.py --check    # 只校验现有 PNG 是否与规格一致
"""
import argparse
import os
import sys

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import make_gui_bg as MGB  # noqa: E402

GUI = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "gui")
BG = os.path.join(ROOT, "pic", "bg.png")
SLOT = os.path.join(ROOT, "pic", "slot.png")

# (PNG 名, 宽, 高, Menu 文件, [(x, y, cols, rows), ...] —— 全部是 Menu 坐标)
SPECS = [
    dict(
        png="intermediate_cache.png", width=176, height=198,
        menu="IntermediateCacheMenu.java",
        # 盘位 3×9（Menu 8,36） / 玩家背包（8,116） / 快捷栏（8,174）
        groups=[(8, 36, 9, 3), (8, 116, 9, 3), (8, 174, 9, 1)],
        why="原先 renderBg 用 McGui.panel + 逐槽 slotFrame 现画，没有背景贴图",
    ),
    dict(
        png="network_resource_receiver.png", width=236, height=252,
        menu="NetworkResourceReceiverMenu.java",
        # 插件 6 格（214,7 + i*18） / 玩家背包（9,176） / 快捷栏（9,230）
        # 第 131 轮：界面从「12 行文字列表」改成「终端样式的 9×7 = 63 格网格」（Menu 9,38，18px 步进），
        # 槽框同样烘进 PNG（不是代码现画），因此这里补进 groups —— 否则 --check 会把新图判成
        # 「与工具重建不一致」。
        groups=[(214, 7, 1, 6), (9, 176, 9, 3), (9, 230, 9, 1), (9, 38, 9, 7)],
        why="原先代码自绘面板",
    ),
    dict(
        png="network_resource_requester.png", width=210, height=238,
        menu="NetworkResourceRequesterMenu.java",
        # 插件 6 格（188,16 + i*18） / 玩家背包（9,164） / 快捷栏（9,218）
        groups=[(188, 16, 1, 6), (9, 164, 9, 3), (9, 218, 9, 1)],
        why="原先代码自绘面板",
    ),
    dict(
        png="sequence_assembly_executor.png", width=176, height=222,
        menu="SequenceAssemblyExecutorMenu.java",
        # 总样板 6×9（8,18） / 玩家背包（8,140） / 快捷栏（8,198）
        groups=[(8, 18, 9, 6), (8, 140, 9, 3), (8, 198, 9, 1)],
        why="原先直接用原版 generic_54.png",
    ),
    dict(
        png="sequence_execution_chamber.png", width=176, height=222,
        menu="SequenceExecutionChamberMenu.java",
        # 单元样板 6×9（8,18） / 玩家背包（8,140） / 快捷栏（8,198）
        groups=[(8, 18, 9, 6), (8, 140, 9, 3), (8, 198, 9, 1)],
        why="原先直接用原版 generic_54.png",
    ),
    dict(
        png="fe_energy_cube.png", width=176, height=166,
        menu="FEEnergyCubeMenu.java",
        # 立方没有槽位（Menu 故意 0 个 addSlot），只要 MC 容器底
        groups=[],
        why="原先是手绘浅灰面板（无 4px 容器边框），与其它机器不统一",
    ),
]


def build(spec):
    bg = Image.open(BG).convert("RGBA")
    slot = Image.open(SLOT).convert("RGBA").crop((0, 0, MGB.SLOT_PX, MGB.SLOT_PX))
    dst = Image.new("RGBA", (spec["width"], spec["height"]), (0, 0, 0, 0))
    MGB.draw_container(dst, bg, spec["width"], spec["height"])
    for x, y, cols, rows in spec["groups"]:
        for row in range(rows):
            for col in range(cols):
                px = x + col * MGB.SLOT_GAP - MGB.SLOT_INSET
                py = y + row * MGB.SLOT_GAP - MGB.SLOT_INSET
                assert 0 <= px and 0 <= py, (spec["png"], x, y)
                assert px + MGB.SLOT_PX <= spec["width"], (spec["png"], px, spec["width"])
                assert py + MGB.SLOT_PX <= spec["height"], (spec["png"], py, spec["height"])
                dst.paste(slot, (px, py))
    return dst


def verify(spec, path):
    problems = []
    if not os.path.exists(path):
        return ["文件不存在: %s" % path]
    with Image.open(path) as raw:
        im = raw.convert("RGBA")
    if im.size != (spec["width"], spec["height"]):
        problems.append("尺寸 %s != %s" % (im.size, (spec["width"], spec["height"])))
        return problems
    px = im.load()
    # ① 每格槽框内部中心必须是槽位底色 (139,139,139)
    for x, y, cols, rows in spec["groups"]:
        for row in range(rows):
            for col in range(cols):
                cx = x + col * MGB.SLOT_GAP - MGB.SLOT_INSET + 8
                cy = y + row * MGB.SLOT_GAP - MGB.SLOT_INSET + 8
                if px[cx, cy][:3] != (139, 139, 139):
                    problems.append("槽心色不符 @(%d,%d)=%s" % (cx, cy, px[cx, cy]))
    # ② 与「工具本体重建」逐像素一致（工具输出长什么样，这里就该长什么样；
    #    bg.png 只有左上/右上/左下三处圆角透明，右下角取的是 (198,198,198)，与既有
    #    quantity_keeper.png 等工具产物完全一致 —— 不以「四角全透明」为判据）
    ref = build(spec).load()
    diff = sum(1 for yy in range(spec["height"]) for xx in range(spec["width"])
               if px[xx, yy] != ref[xx, yy])
    if diff:
        problems.append("与工具重建不一致 %d px" % diff)
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只校验，不写文件")
    args = ap.parse_args()
    if not args.check:
        os.makedirs(GUI, exist_ok=True)
    bad = 0
    for spec in SPECS:
        path = os.path.join(GUI, spec["png"])
        if not args.check:
            build(spec).save(path)
            print("[GEN] %-34s %dx%d  %s" % (spec["png"], spec["width"], spec["height"],
                                             spec["menu"]))
        problems = verify(spec, path)
        if problems:
            bad += len(problems)
            for msg in problems:
                print("   [X] %-30s %s" % (spec["png"], msg))
        else:
            print("   [OK] %-30s %d 组槽位 / %d 格" % (
                spec["png"], len(spec["groups"]), sum(c * r for _x, _y, c, r in spec["groups"])))
    print("-" * 78)
    print("结果：%d 张，%d 个问题" % (len(SPECS), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
