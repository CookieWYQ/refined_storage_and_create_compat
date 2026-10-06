# -*- coding: utf-8 -*-
"""伪装框架（camouflage_frame）的成品资源生成：方块状态 / 模型 / 占位贴图 / 配方 / 战利品表 / 语言键。

为什么用脚本而不是手写：
  * JSON 有严格格式要求（尾逗号、缩进、编码任意一处错都会让整份资源静默失效）；
  * 语言文件必须保持「按键名排序 + 2 空格缩进 + 无尾逗号」，且要与既有键合并而不是覆盖；
  * 贴图必须是真的 16×16 PNG（字节级），手写不现实。
  脚本是幂等的：重复执行结果一致，可随时重跑。

设计要点（与 Create 伪装板对齐）：
  * 方块状态模型 = **本方块自己的立方体模型**（``rs_create_compat:block/camouflage_frame``）。
    2026-09-25 修正：以前这里写 ``minecraft:block/air``（照抄 Create 伪装板），
    结果用户实测「里面什么都不渲染、能直接看穿到底下的方块，类似透视」——
    因为「未装材质」时 Create 用的是 ``create:copycat_base`` 占位状态，而那张贴图本身是**透明**的。
    改成指向本方块自己的不透明立方体后，``client/model/CamouflageFrameModel`` 就能在「未装材质」
    时退回画这个兜底立方体（见该类注释），四面都有实体外观，任何情况下都不透视。
    装了材质时照样由包装模型按材质实时出画，这个底模只被「兜底分支」与「被邻块遮住的面」用到。
  * 物品模型 = 同一个立方体模型（三张占位贴图），物品栏里能看到一件「空壳」。
  * 外壳未应用时= 兜底立方体（不透明）；已应用时= 外壳材质自己的模型。

用法: python tools/gen_camouflage_frame_resources.py
"""
import io
import json
import os
import zlib
import struct
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NS = "rs_create_compat"
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", NS)
DATA = os.path.join(ROOT, "src", "main", "resources", "data")
JAVA_MAIN = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat",
                         "RS_Create_Compat.java")

BLOCK = "camouflage_frame"
# 三张占位贴图（竖直四面共用 side：项目审计脚本明确允许四个竖直侧面共用一张；
# top / bottom 必须各自独立，否则会被判为「一图多用」）。
TEXTURES = {
    "%s_side" % BLOCK: ((0x8C, 0x8C, 0x8C), (0x5C, 0x5C, 0x5C)),
    "%s_top" % BLOCK: ((0x9C, 0x9C, 0x9C), (0x6C, 0x6C, 0x6C)),
    "%s_bottom" % BLOCK: ((0x7A, 0x7A, 0x7A), (0x50, 0x50, 0x50)),
}

# 配方（理由见交付报告）：4 个 Create 伪装板 + 4 个 Create 锌锭 + 1 个 RS 石英强化铁 → 2 个伪装框架。
# 伪装板本身就是「4 个/锌锭」的廉价装饰件，4 张板拼成一整格的空壳、锌锭做骨架，
# 石英强化铁是 RS 侧的老面孔（本模组其余方块都用它），整体成本远低于一台机器。
RECIPE = {
    "type": "minecraft:crafting_shaped",
    "pattern": [
        "ZPZ",
        "PCP",
        "ZPZ",
    ],
    "key": {
        "Z": {"item": "create:zinc_ingot"},
        "P": {"item": "create:copycat_panel"},
        "C": {"item": "refinedstorage:quartz_enriched_iron"},
    },
    "result": {"id": "%s:%s" % (NS, BLOCK), "count": 2},
}

# 语言键：中文显示名 + 物品帮助文本 + 动作栏提示。
#
# 2026-09-25 改版：伪装框架从「在旁边放一个伪装板方块」改成「把那一格线缆裹起来」
# （见 block/CamouflageFrameBlock 与 support/RsccCamouflage 的注释），
# 因此有两条键的 <b>文本</b> 必然要跟着改（help / occupied），并新增取下相关的那几条。
# 完整清单与取值见 tools/add_camouflage_wrap_lang.py（那个脚本才是本轮的唯一来源），
# 这里保留同名键是为了让「重跑本脚本」不会把新文案覆盖回旧版。
LANG = {
    "zh_cn": {
        "block.rs_create_compat.camouflage_frame": "伪装框架",
        "block.rs_create_compat.camouflage_frame.help":
            "伪装框架：右键线缆/管道把它裹起来（只改外观，不影响连接与网络）；"
            "手持完整方块右键可换外壳；潜行右键取下并归还；拆掉被裹的线缆也会掉回框架与外壳。",
        "block.rs_create_compat.camouflage_frame.hint.pipe_only": "伪装框架只能装在管道或线缆上",
        "block.rs_create_compat.camouflage_frame.hint.full_only":
            "外壳只能用完整方块（台阶、栅栏、火把一类非完整方块不接受）",
        "block.rs_create_compat.camouflage_frame.hint.occupied":
            "已经是这种外壳了（潜行右键先取下再更换）",
        "block.rs_create_compat.camouflage_frame.hint.wrapped":
            "已裹上伪装（手持完整方块右键可选外观）",
        "block.rs_create_compat.camouflage_frame.hint.remove_hint":
            "这一格已经裹着伪装：潜行右键可取下并归还",
        "block.rs_create_compat.camouflage_frame.hint.unwrapped": "已取下伪装，框架与外壳已归还",
        "block.rs_create_compat.camouflage_frame.hint.shelled": "已套上外壳外观",
        "block.rs_create_compat.camouflage_frame.hint.take_off_hint":
            "已裹着伪装：潜行右键（空手/框架/扳手都行）可取下",
    },
    "en_us": {
        "block.rs_create_compat.camouflage_frame": "Camouflage Frame",
        "block.rs_create_compat.camouflage_frame.help":
            "Camouflage Frame: right-click a cable or pipe to wrap it (looks only - connections and "
            "networks are untouched); right-click with a full block to pick the shell; sneak-right-click "
            "to take it off and get it back; breaking the wrapped cable also drops the frame and shell.",
        "block.rs_create_compat.camouflage_frame.hint.pipe_only":
            "(hint) The Camouflage Frame can only go on a pipe or cable",
        "block.rs_create_compat.camouflage_frame.hint.full_only":
            "(hint) The shell must be a full block - slabs, fences and torches are not accepted",
        "block.rs_create_compat.camouflage_frame.hint.occupied":
            "(hint) That shell is already applied - sneak-right-click to take it off first",
        "block.rs_create_compat.camouflage_frame.hint.wrapped":
            "(hint) Wrapped - right-click with a full block to pick a shell",
        "block.rs_create_compat.camouflage_frame.hint.remove_hint":
            "(hint) Already wrapped - sneak-right-click to take it off",
        "block.rs_create_compat.camouflage_frame.hint.unwrapped":
            "(hint) Unwrapped - the frame and shell were returned",
        "block.rs_create_compat.camouflage_frame.hint.shelled": "(hint) Shell applied",
        "block.rs_create_compat.camouflage_frame.hint.take_off_hint":
            "(hint) Wrapped - sneak-right-click to take it off",
    },
}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    # 立刻回读校验：写坏了必须当场发现，而不是等到游戏加载才炸
    with io.open(path, "r", encoding="utf-8") as handle:
        json.load(handle)
    print("[OK  ] %s" % os.path.relpath(path, ROOT).replace("\\", "/"))


def png_bytes(width, height, pixels):
    """把 (r,g,b,a) 二维列表编码成 PNG（只依赖标准库 zlib，不引入 Pillow 依赖）。"""
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("BBBB", *pixels[y][x]) for x in range(width))
        for y in range(height)
    )

    def chunk(tag, payload):
        body = tag + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def frame_pixels(fill, border):
    """一张「空壳」样子的 16×16 占位图：外圈 1px 深色 + 内圈 2px 亮色描边，中间铺底色。"""
    width = height = 16
    pixels = []
    for y in range(height):
        row = []
        for x in range(width):
            on_ring = x in (0, 1, 14, 15) or y in (0, 1, 14, 15)
            color = border if on_ring else fill
            row.append((color[0], color[1], color[2], 255))
        pixels.append(row)
    return width, height, pixels


def gen_textures():
    for name, (fill, border) in TEXTURES.items():
        path = os.path.join(ASSETS, "textures", "block", "%s.png" % name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = png_bytes(*frame_pixels(fill, border))
        with open(path, "wb") as handle:
            handle.write(data)
        print("[OK  ] %s (%d bytes)" % (os.path.relpath(path, ROOT).replace("\\", "/"), len(data)))


def gen_models():
    ns = "%s:" % NS
    # 方块状态：指向本方块自己的立方体模型（「未装材质」时由包装模型退回画它，见文件头说明与
    # client/model/CamouflageFrameModel：兜底用的 originalModel 正是这个模型）
    write_json(os.path.join(ASSETS, "blockstates", "%s.json" % BLOCK), {
        "variants": {
            "": {"model": ns + "block/%s" % BLOCK},
        },
    })
    # 本方块自己的立方体模型：既是「未装材质」的兜底外观，也是物品栏里的「空壳」外观
    write_json(os.path.join(ASSETS, "models", "block", "%s.json" % BLOCK), {
        "parent": "minecraft:block/cube",
        "textures": {
            "particle": ns + "block/%s_side" % BLOCK,
            "north": ns + "block/%s_side" % BLOCK,
            "east": ns + "block/%s_side" % BLOCK,
            "south": ns + "block/%s_side" % BLOCK,
            "west": ns + "block/%s_side" % BLOCK,
            "up": ns + "block/%s_top" % BLOCK,
            "down": ns + "block/%s_bottom" % BLOCK,
        },
    })
    write_json(os.path.join(ASSETS, "models", "item", "%s.json" % BLOCK), {
        "parent": ns + "block/%s" % BLOCK,
    })


def gen_data():
    write_json(os.path.join(DATA, NS, "recipe", "%s.json" % BLOCK), RECIPE)
    write_json(os.path.join(DATA, NS, "loot_table", "blocks", "%s.json" % BLOCK), {
        "type": "minecraft:block",
        "pools": [
            {
                "rolls": 1.0,
                "entries": [
                    {"type": "minecraft:item", "name": "%s:%s" % (NS, BLOCK)},
                ],
                "conditions": [
                    {"condition": "minecraft:survives_explosion"},
                ],
            }
        ],
    })
    # 与项目既有方块一致：进 pickaxe 可挖掘标签（只加行，不动别人的条目）
    tag_path = os.path.join(DATA, "minecraft", "tags", "block", "mineable", "pickaxe.json")
    with io.open(tag_path, "r", encoding="utf-8") as handle:
        tag = json.load(handle)
    entry = "%s:%s" % (NS, BLOCK)
    if entry not in tag["values"]:
        tag["values"] = sorted(tag["values"] + [entry])
        write_json(tag_path, tag)
    else:
        print("[skip] pickaxe 标签已包含 %s" % entry)


def gen_lang():
    for locale, entries in LANG.items():
        path = os.path.join(ASSETS, "lang", "%s.json" % locale)
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        merged = dict(data)
        merged.update(entries)
        write_json(path, dict(sorted(merged.items())))
        print("       %s: 新增/更新 %d 键，合计 %d 键"
              % (locale, len(entries), len(merged)))


def check_registration():
    """源码锚点：确认方块 / 物品 / 方块实体 / 创造栏都真的注册了（资源存在但没注册 = 白干）。"""
    with io.open(JAVA_MAIN, "r", encoding="utf-8") as handle:
        src = handle.read()
    anchors = {
        'BLOCKS.register("%s"' % BLOCK: "方块注册",
        'ITEMS.register("%s"' % BLOCK: "物品注册",
        'BLOCK_ENTITIES.register("%s"' % BLOCK: "方块实体注册",
        "output.accept(CAMOUFLAGE_FRAME_ITEM.get())": "创造栏条目",
    }
    ok = True
    for anchor, what in anchors.items():
        hit = anchor in src
        print("[%s] 源码锚点 %s：%s" % ("OK  " if hit else "FAIL", what, anchor))
        ok = ok and hit
    return ok


def main():
    gen_textures()
    gen_models()
    gen_data()
    gen_lang()
    ok = check_registration()
    print("[result] %s" % ("资源生成完毕，注册锚点齐全" if ok else "注册锚点缺失，请检查 RS_Create_Compat.java"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
