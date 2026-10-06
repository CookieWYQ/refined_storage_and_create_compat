#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「单元样板管理舱」（unit_pattern_manager）的全部资源文件。

产物（全部按 UTF-8 + 2 空格缩进 + 结尾换行写出，可重复执行且结果幂等）：
  assets/rs_create_compat/blockstates/unit_pattern_manager.json
  assets/rs_create_compat/models/block/unit_pattern_manager.json          （未接入：灰）
  assets/rs_create_compat/models/block/unit_pattern_manager_active.json   （已接入：亮）
  assets/rs_create_compat/models/item/unit_pattern_manager.json
  assets/rs_create_compat/textures/block/unit_pattern_manager_{front,back,left,right,top,bottom}[_active].png
  data/rs_create_compat/loot_table/blocks/unit_pattern_manager.json
  data/minecraft/tags/block/mineable/pickaxe.json                        （追加本方块）

贴图来源：RS 的自动合成管理舱（`refinedstorage:block/autocrafter_manager/*` + `refinedstorage:block/bottom`）——
「界面/外观照抄自动合成舱」的落地方式之一。未接入态把该贴图整体去饱和压暗，得到本工程统一的
「未接入 = 灰 / 已接入 = 亮」两态观感（与其它机器一致）。

**六个面各自一张贴图**（用户硬规则：不许一图多用）：RS 的自动合成管理舱本来就提供
front / back / left / right / top 五张独立贴图，底面用 RS 通用的 bottom —— 本生成器照抄这套对应关系，
不再让「背面复用侧面贴图」「底面复用顶面贴图」。

**正面必须自己补一块不透明面板**（本轮修复「正面贴图是透明的 / 像缺图」）：
RS 的 `autocrafter_manager/front.png` 中间本来就挖了 10×10 的**全透明窗**（16×16 里有 90 个 alpha=0
像素），RS 自己的模型用 `"render_type": "cutout"` 让这个窗**透出方块内部**；照抄到本方块后
（本方块模型是 `minecraft:block/cube`、不声明 render_type），透明像素被剔除 / 或按不透明绘制，
正面看起来就是「透空 / 缺图」。本工程不做透空设计，因此生成时把那个窗补成一块**不透明面板**
（内凹暗边 + 屏幕 + 样板槽 + 指示灯），六面观感一致、无任何透明像素。

用法：python tools/gen_unit_manager_resources.py
"""
from __future__ import annotations

import json
import os
from pathlib import Path

from PIL import Image
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "rs_create_compat"
MODELS_BLOCK = ASSETS / "models" / "block"
MODELS_ITEM = ASSETS / "models" / "item"
BLOCKSTATES = ASSETS / "blockstates"
TEXTURES = ASSETS / "textures" / "block"
DATA = ROOT / "src" / "main" / "resources" / "data"
LOOT = DATA / "rs_create_compat" / "loot_table" / "blocks"
MINEABLE = DATA / "minecraft" / "tags" / "block" / "mineable" / "pickaxe.json"

RS_TEXTURES = (ROOT / "local_src" / "external" / "RefinedStorage" / "refinedstorage-common"
               / "src" / "main" / "resources" / "assets" / "refinedstorage" / "textures" / "block")

BLOCK_ID = "unit_pattern_manager"
# 我方贴图名 -> RS 源贴图路径（相对 refinedstorage/textures/block）
FACE_SOURCES = {
    "front": "autocrafter_manager/front.png",
    "back": "autocrafter_manager/back.png",
    "left": "autocrafter_manager/left.png",
    "right": "autocrafter_manager/right.png",
    "top": "autocrafter_manager/top.png",
    "bottom": "bottom.png",
}
# 旧的「一图多用」贴图（已不再被任何模型引用，生成时顺手删除）
STALE_TEXTURES = ["unit_pattern_manager_side.png", "unit_pattern_manager_side_active.png"]

# RS 正面贴图里被挖空的那块窗（左闭右开，PNG 像素坐标）：10×10
FRONT_WINDOW = (3, 3, 13, 13)
# 指示灯颜色（仅“已接入”态；未接入态经 grey_out 去饱和后变暗灰 = 熄灭）
FRONT_LED = (0x2F, 0x5C, 0x2F)


def dump_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def draw_front_panel(image: Image.Image) -> Image.Image:
    """把正面贴图中间那块透明窗补成一块**不透明面板**，其余像素原样保留。

    设计（10×10 局部坐标，与同族五面同一套“深色机壳 + 内凹”观感）：
      边框 1px   —— 上 / 左 0x2B（内凹暗边）、下 / 右 0x6E（内凹亮边）
      面板底     —— 0x58
      屏幕       —— 上 4 行深色玻璃（0x24 / 0x30 / 0x30 / 0x3C），中间两块 2×2 高亮 = “读到样板”
      分隔亮线   —— 第 5 行 0x6E（把屏幕区与样板槽区分开）
      样板槽     —— 左上 6×3 内凹槽（上暗下亮）
      指示灯     —— 右侧 2×3 竖条（见 FRONT_LED）
    所有像素 alpha 一律 255：本方块不做透空设计。
    """
    out = image.copy().convert("RGBA")
    pixels = out.load()
    x0, y0, _x1, _y1 = FRONT_WINDOW

    def put(lx: int, ly: int, color) -> None:
        if isinstance(color, int):
            color = (color, color, color)
        pixels[x0 + lx, y0 + ly] = (color[0], color[1], color[2], 255)

    for ly in range(10):
        for lx in range(10):
            if lx == 0 or ly == 0:
                value = 0x2B
            elif lx == 9 or ly == 9:
                value = 0x6E
            else:
                value = 0x58
            put(lx, ly, value)

    # ① 屏幕（4 行深色玻璃 + 两行“内容”高亮线，像屏上的文字行）
    for lx in range(1, 9):
        put(lx, 1, 0x24)
        put(lx, 4, 0x3C)
    for ly in (2, 3):
        for lx in range(1, 9):
            put(lx, ly, 0x30)
    for lx in (2, 3, 4, 5):
        put(lx, 2, 0x9E)
    for lx in (2, 3, 4):
        put(lx, 3, 0x7A)

    # ② 屏幕与样板槽之间的分隔亮线
    for lx in range(1, 9):
        put(lx, 5, 0x6E)

    # ③ 样板槽（6×3 内凹）
    for lx in range(1, 7):
        put(lx, 6, 0x3A)
        put(lx, 7, 0x2C)
        put(lx, 8, 0x46)

    # ④ 右侧指示灯竖条（2×3）
    for ly in (6, 7, 8):
        for lx in (7, 8):
            put(lx, ly, FRONT_LED)
    return out


def alpha_zero_count(image: Image.Image) -> int:
    """透明（alpha == 0）像素个数：用于断言贴图不是「透空」的。"""
    return sum(1 for pixel in image.getdata() if pixel[3] == 0)


def grey_out(source: Image.Image) -> Image.Image:
    """去饱和 + 压暗（未接入态）。保留 alpha，透明像素不受影响。"""
    out = source.convert("RGBA")
    pixels = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            lum = (r * 299 + g * 587 + b * 114) // 1000
            value = (lum * 3) // 5
            pixels[x, y] = (value, value, value, a)
    return out


def write_textures() -> None:
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for face, source_name in FACE_SOURCES.items():
        source_path = RS_TEXTURES / source_name
        if not source_path.is_file():
            raise SystemExit("缺少源贴图：%s" % source_path)
        image = Image.open(source_path).convert("RGBA")
        if face == "front":
            # RS 的正面中间是挖空的透明窗（RS 用 cutout 透出方块内部）；本方块不做透空设计，
            # 必须补成不透明面板，否则正面看起来是「透空 / 缺图」（用户实测问题）。
            image = draw_front_panel(image)
            holes = alpha_zero_count(image)
            if holes:
                raise SystemExit("正面贴图仍有 %d 个透明像素（应全部补齐）" % holes)
            print("[ok] 正面透明窗已补成不透明面板（0 个透明像素）")
        # 命名约定：未接入 = unit_pattern_manager_<face>；已接入 = unit_pattern_manager_<face>_active
        grey_out(image).save(TEXTURES / f"{BLOCK_ID}_{face}.png")
        image.save(TEXTURES / f"{BLOCK_ID}_{face}_active.png")
        print(f"[ok] 贴图 {face}: 未接入(灰) / 已接入(亮)")
    for stale in STALE_TEXTURES:
        path = TEXTURES / stale
        if path.exists():
            path.unlink()
            print(f"[ok] 清理旧贴图 {stale}")


def write_models() -> None:
    for suffix, active in (("", False), ("_active", True)):
        state = "已接入(亮)" if active else "未接入(灰)"
        tail = "_active" if active else ""
        # 六个面各自一张独立贴图（front/back/left/right/top/bottom），与 RS 自动合成管理舱一一对应
        model = {
            "parent": "minecraft:block/cube",
            "textures": {
                "particle": f"rs_create_compat:block/{BLOCK_ID}_front{tail}",
                "north": f"rs_create_compat:block/{BLOCK_ID}_front{tail}",
                "south": f"rs_create_compat:block/{BLOCK_ID}_back{tail}",
                "east": f"rs_create_compat:block/{BLOCK_ID}_right{tail}",
                "west": f"rs_create_compat:block/{BLOCK_ID}_left{tail}",
                "up": f"rs_create_compat:block/{BLOCK_ID}_top{tail}",
                "down": f"rs_create_compat:block/{BLOCK_ID}_bottom{tail}",
            },
        }
        dump_json(MODELS_BLOCK / f"{BLOCK_ID}{suffix}.json", model)
        print(f"[ok] 模型 {BLOCK_ID}{suffix}.json（{state}）")

    dump_json(MODELS_ITEM / f"{BLOCK_ID}.json", {"parent": f"rs_create_compat:block/{BLOCK_ID}"})


def write_blockstate() -> None:
    variants = {}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for active in (False, True):
            entry: dict = {"model": f"rs_create_compat:block/{BLOCK_ID}{'_active' if active else ''}"}
            if y:
                entry["y"] = y
            variants[f"facing={facing},active={str(active).lower()}"] = entry
    dump_json(BLOCKSTATES / f"{BLOCK_ID}.json", {"variants": variants})
    print(f"[ok] blockstate {BLOCK_ID}.json（4 朝向 × 2 接入态）")


def write_loot() -> None:
    dump_json(LOOT / f"{BLOCK_ID}.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1.0,
            "entries": [{"type": "minecraft:item", "name": f"rs_create_compat:{BLOCK_ID}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })
    print(f"[ok] 掉落表 {BLOCK_ID}.json")


def append_mineable() -> None:
    with MINEABLE.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    entry = f"rs_create_compat:{BLOCK_ID}"
    if entry not in data["values"]:
        data["values"].append(entry)
        data["values"].sort()
        dump_json(MINEABLE, data)
    print(f"[ok] 挖掘标签 pickaxe 已包含 {entry}")


def main() -> None:
    if not RS_TEXTURES.is_dir():
        raise SystemExit("找不到 RS 贴图目录：%s" % RS_TEXTURES)
    write_textures()
    write_models()
    write_blockstate()
    write_loot()
    append_mineable()
    print("done: unit_pattern_manager resources")


if __name__ == "__main__":
    main()
