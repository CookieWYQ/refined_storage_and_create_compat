# -*- coding: utf-8 -*-
"""「中间产物缓存仓」（intermediate_cache）的成品资源生成：方块状态 / 模型 / 贴图 / 配方 / 战利品表 / 语言键。

为什么用脚本而不是手写：
  * JSON 有严格格式要求（尾逗号、缩进、编码任意一处错都会让整份资源静默失效）；
  * 语言文件必须保持「按键名排序 + 2 空格缩进 + 无尾逗号 + UTF-8」，且要与既有键合并而不是覆盖；
  * 贴图必须是真的 16×16 PNG（字节级），手写不现实。
脚本是幂等的：重复执行结果一致（固定 zlib 压缩级别 → 字节级一致），可随时重跑。

本轮（第 7 轮）三处修正：
  1. **容量语义改回「磁盘存放空间」**：方块**不再自带 1728 件物品容量**，改为
     「一个小箱子大小的磁盘存放空间」= 27 格盘位（见 IntermediateCacheBlockEntity#DISK_SLOTS）。
     因此帮助行 / 界面两行文字 / tooltip 全部改写，不再出现 1728。
  2. **紫黑粒子（missing texture）根因修复**：inactive 模型的 ``particle`` 之前指向
     ``block/intermediate_cache_side``（**这个文件从来不存在**，只有 ``_side_inactive`` /
     ``_side_active``），于是破坏方块时喷出紫黑方块粒子。现在 particle 指向**确实存在的**面贴图
     （inactive 模型 → ``_side_inactive``，active 模型 → ``_side_active``）。
  3. **贴图第 3 次重画**：竖直四面 = 可辨识的「3 槽磁盘仓（盘位 + 每槽一颗指示灯）」，
     顶面 = 通风顶盖，底面 = 脚垫 + 铭牌；active / inactive 只差指示灯颜色。

用法: python tools/gen_intermediate_cache_resources.py
"""
import io
import json
import os
import struct
import zlib
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

BLOCK = "intermediate_cache"

# 六张贴图（active / inactive × side / top / bottom），全部由 frame_pixels 逐像素画出。
#   * 竖直四面共用 side（用户硬规则允许「同一台机器的四个竖直侧面共用一张」）：本仓方块没有朝向属性，
#     因此把「盘位正面」直接做进 side —— 四个竖直面任意一面都是可辨识的存储 / 盘位外观；
#   * top / bottom 各自独立图案（通风顶盖 / 脚垫铭牌），既不被 audit_shared_textures.py 判为「一图多用」，
#     也不会出现两张贴图字节完全相同；
#   * active / inactive 只差指示灯（接入网络后点亮），与其它机器同一观感语言。
# 每项 = (贴图名, 面类型, 底色, 1px 描边色, 指示灯色)；指示灯为 None = inactive（灯灭）。
TEXTURES = [
    ("%s_side_inactive" % BLOCK, "side", (0x9A, 0x9A, 0x9A), (0x3A, 0x3A, 0x3A), None),
    ("%s_side_active" % BLOCK, "side", (0x9A, 0x9A, 0x9A), (0x3A, 0x3A, 0x3A), (0x2E, 0x6F, 0xD8)),
    ("%s_top_inactive" % BLOCK, "top", (0xAC, 0xAC, 0xAC), (0x44, 0x44, 0x44), None),
    ("%s_top_active" % BLOCK, "top", (0xAC, 0xAC, 0xAC), (0x44, 0x44, 0x44), (0x2E, 0x6F, 0xD8)),
    ("%s_bottom_inactive" % BLOCK, "bottom", (0x7E, 0x7E, 0x7E), (0x2E, 0x2E, 0x2E), None),
    ("%s_bottom_active" % BLOCK, "bottom", (0x7E, 0x7E, 0x7E), (0x2E, 0x2E, 0x2E), (0x2E, 0x6F, 0xD8)),
]

# 配方（材料理由见交付报告）：RS 机器外壳 + 构造核心 + 石英强化铁骨架 + Create 精密构件。
# 它就是「一个放磁盘的托盘」，成本明显低于一台机器：3 石英强化铁 + 1 机器外壳 + 1 构造核心
# + 2 精密构件 + 2 铁板。精密构件（create:precision_mechanism）代表「这是插盘用的精密仓」。
RECIPE = {
    "type": "minecraft:crafting_shaped",
    "pattern": [
        "QPQ",
        "CMC",
        "IQI",
    ],
    "key": {
        "Q": {"item": "refinedstorage:quartz_enriched_iron"},
        "P": {"item": "create:precision_mechanism"},
        "C": {"item": "create:iron_sheet"},
        "M": {"item": "refinedstorage:machine_casing"},
        "I": {"item": "refinedstorage:construction_core"},
    },
    "result": {"id": "%s:%s" % (NS, BLOCK)},
}

# 语言键：本仓方块 + 界面。
# 2026-09-25 第 7 轮：容量语义改为「一个小箱子大小的磁盘存放空间（27 格盘位）」，
# 不再声称方块自带 1728 件物品容量；占位符个数必须与 Java 传参一致
# （disk.none 0 个、disk.usage 2 个、disk.usage.unlimited 1 个）。
LANG_ADD = {
    "zh_cn": {
        "block.rs_create_compat.intermediate_cache": "中间产物缓存仓",
        "block.rs_create_compat.intermediate_cache.help":
            "接入网络后，27 格盘位（= 一个小箱子大小）里的磁盘共享给所有执行舱。",
        "gui.rs_create_compat.intermediate_cache.disk.label": "共享缓存磁盘",
        "gui.rs_create_compat.intermediate_cache.disk.none": "未插盘：共享池为空，请放入存储磁盘",
        "gui.rs_create_compat.intermediate_cache.disk.pending": "磁盘存储同步中…",
        "gui.rs_create_compat.intermediate_cache.disk.usage": "盘：已用 %s / %s",
        "gui.rs_create_compat.intermediate_cache.disk.usage.unlimited": "盘：已用 %s / 无限",
        "gui.rs_create_compat.intermediate_cache.tip.capacity":
            "27 格盘位 = 一个小箱子大小：放这里的磁盘拼成同一个共享池子。",
        "gui.rs_create_compat.intermediate_cache.tip.share":
            "插在这里的磁盘＝本网络所有序列装配执行舱共用的中间产物临时储存点。",
        "gui.rs_create_compat.intermediate_cache.tip.slots":
            "27 格盘位（3 行 × 9 列）：多块盘容量合并成一个池子（多台仓也合并）。",
        "gui.rs_create_compat.intermediate_cache.tip.carry":
            "取盘即带走盘内物品；拆仓时磁盘（含内容）按「内容物去向」策略结算。",
        "gui.rs_create_compat.intermediate_cache.tip.accept":
            "只接受 RS 存储磁盘；流体磁盘不计入物品缓存空间。",
    },
    "en_us": {
        "block.rs_create_compat.intermediate_cache": "Intermediate Cache Warehouse",
        "block.rs_create_compat.intermediate_cache.help":
            "Once networked, disks in its 27 slots (= one small chest) are shared by every"
            " sequence chamber in that network.",
        "gui.rs_create_compat.intermediate_cache.disk.label": "Shared cache disks",
        "gui.rs_create_compat.intermediate_cache.disk.none": "No disk: the shared pool is empty",
        "gui.rs_create_compat.intermediate_cache.disk.pending": "Disk storage syncing...",
        "gui.rs_create_compat.intermediate_cache.disk.usage": "Disks: used %s / %s",
        "gui.rs_create_compat.intermediate_cache.disk.usage.unlimited":
            "Disks: used %s / unlimited",
        "gui.rs_create_compat.intermediate_cache.tip.capacity":
            "27 disk slots = one small chest; the disks placed here form a single shared pool.",
        "gui.rs_create_compat.intermediate_cache.tip.share":
            "Disks placed here are one shared intermediate cache for every sequence assembly chamber"
            " in this network.",
        "gui.rs_create_compat.intermediate_cache.tip.slots":
            "27 slots (3 rows x 9): several disks (and several warehouses) merge into one pool.",
        "gui.rs_create_compat.intermediate_cache.tip.carry":
            "Taking a disk out carries its contents with it; breaking the warehouse drops the disks.",
        "gui.rs_create_compat.intermediate_cache.tip.accept":
            "Storage disks only; fluid disks do not add item cache space.",
    },
}

# 已移除的执行舱磁盘槽语言键（磁盘槽不存在了，留着就是死键）
LANG_DELETE = [
    "gui.rs_create_compat.sequence_execution_chamber.disk.label",
    "gui.rs_create_compat.sequence_execution_chamber.disk.none",
    "gui.rs_create_compat.sequence_execution_chamber.disk.pending",
    "gui.rs_create_compat.sequence_execution_chamber.disk.usage",
    "gui.rs_create_compat.sequence_execution_chamber.disk.usage.unlimited",
    "gui.rs_create_compat.sequence_execution_chamber.disk.tip.capacity",
    "gui.rs_create_compat.sequence_execution_chamber.disk.tip.accept",
    "gui.rs_create_compat.sequence_execution_chamber.disk.tip.carry",
]

# 中文单条长度上限（用户硬规则）
ZH_MAX = 40

# 盘位布局（与 IntermediateCacheBlockEntity#DISK_SLOTS / Menu 的 3 行 × 9 列一致）：
# 竖直面画的就是这 3 行盘位 + 每行一颗指示灯。
BAY_ROWS = (4, 7, 10)
BAY_X0, BAY_X1 = 3, 11
LED_X = 12


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    with io.open(path, "r", encoding="utf-8") as handle:
        json.load(handle)  # 立刻回读校验
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


def _blank(fill):
    """一张 16×16 的纯底色画布（后续用画点 / 画线的方式叠加图案）。"""
    return [[(fill[0], fill[1], fill[2], 255) for _x in range(16)] for _y in range(16)]


def _fill_rect(pixels, x0, y0, x1, y1, color):
    """填充 [x0,x1) × [y0,y1) 的矩形（越界自动裁剪）。"""
    for y in range(max(0, y0), min(16, y1)):
        for x in range(max(0, x0), min(16, x1)):
            pixels[y][x] = (color[0], color[1], color[2], 255)


def _frame(pixels, border, edge_light, edge_dark):
    """标准「面板」外框：1px 深色描边 + 上/左亮边 + 下/右暗边（与同族方块同一套观感语言）。"""
    _fill_rect(pixels, 0, 0, 16, 1, border)
    _fill_rect(pixels, 0, 15, 16, 16, border)
    _fill_rect(pixels, 0, 1, 1, 15, border)
    _fill_rect(pixels, 15, 1, 16, 15, border)
    _fill_rect(pixels, 1, 1, 15, 2, edge_light)
    _fill_rect(pixels, 1, 1, 2, 15, edge_light)
    _fill_rect(pixels, 1, 14, 15, 15, edge_dark)
    _fill_rect(pixels, 14, 1, 15, 15, edge_dark)


def _on(led):
    """指示灯颜色：接入网络 = 蓝灯；未接入 = 略暗的冷灰（与同族机器同款）。"""
    return led if led is not None else (0x4E, 0x54, 0x5E)


def frame_pixels(fill, border, led, face):
    """一张 16×16 的「磁盘仓」贴图（面与面之间图案不同，字节级必然不同）。

    * ``side``（四个竖直面共用，用户允许）：**3 槽磁盘仓正面** —— 一块凹进去的盘仓背板，
      上面三行盘位（每行 = 深色槽口 + 浅色盘沿），每行右侧一颗 2×2 指示灯；
      「一个小箱子大小 = 3 行 × 9 列盘位」→ 画 3 行盘位，与配置界面的 3 行盘位同构；
    * ``top``：机柜顶盖 —— 2×3 通风孔阵列 + 四角螺钉 + 顶部中央指示灯；
    * ``bottom``：机柜底 —— 四个脚垫 + 中央铭牌（铭牌里有两道「文字」线）+ 底部中央指示灯。

    所有面都不透明、不使用 alpha（与同族机器一致：默认 solid 渲染即可）。
    """
    edge_light = (min(0xFF, fill[0] + 0x1E), min(0xFF, fill[1] + 0x1E), min(0xFF, fill[2] + 0x1E))
    edge_dark = (max(0x00, fill[0] - 0x22), max(0x00, fill[1] - 0x22), max(0x00, fill[2] - 0x22))
    plate = (max(0x00, fill[0] - 0x24), max(0x00, fill[1] - 0x24), max(0x00, fill[2] - 0x24))
    plate_dark = (max(0x00, fill[0] - 0x3C), max(0x00, fill[1] - 0x3C), max(0x00, fill[2] - 0x3C))
    bay_dark = (max(0x00, fill[0] - 0x4C), max(0x00, fill[1] - 0x4C), max(0x00, fill[2] - 0x4C))
    bay_edge = (min(0xFF, fill[0] + 0x24), min(0xFF, fill[1] + 0x24), min(0xFF, fill[2] + 0x24))
    pixels = _blank(fill)
    _frame(pixels, border, edge_light, edge_dark)

    if face == "side":
        # 盘仓背板（凹槽）：上沿暗、下沿亮，一眼是「凹进去的一块板」
        _fill_rect(pixels, 2, 3, 14, 13, plate)
        _fill_rect(pixels, 2, 3, 14, 4, plate_dark)
        _fill_rect(pixels, 2, 12, 14, 13, bay_edge)
        # 三行盘位：每行 = 1px 深色槽口 + 1px 浅色盘沿（插入的盘前沿反光）+ 行末 2×2 指示灯
        for row in BAY_ROWS:
            _fill_rect(pixels, BAY_X0, row, BAY_X1, row + 1, bay_dark)
            _fill_rect(pixels, BAY_X0, row + 1, BAY_X1, row + 2, bay_edge)
            _fill_rect(pixels, LED_X, row, LED_X + 2, row + 2, _on(led))
    elif face == "top":
        # 顶盖：中央通风阵列（2 列 × 3 行 2×2 孔）+ 四角螺钉
        _fill_rect(pixels, 2, 2, 14, 14, plate_dark)
        for hx in (5, 9):
            for hy in (4, 7, 10):
                _fill_rect(pixels, hx, hy, hx + 2, hy + 2, bay_dark)
        for cx, cy in ((1, 1), (14, 1), (1, 14), (14, 14)):
            pixels[cy][cx] = (edge_dark[0], edge_dark[1], edge_dark[2], 255)
        # 顶部中央指示灯（与四角螺钉同一行，不压通风孔）
        _fill_rect(pixels, 7, 1, 9, 3, _on(led))
    else:  # bottom
        # 底：一整块内凹底座（上暗沿 + 下亮沿）+ 四角脚垫螺钉 + 中央铭牌（浅色板 + 一道「文字」线）
        #      + 底部中央指示灯。与顶面（通风孔阵列）图案完全不同，也与 side（3 行盘位）不同。
        _fill_rect(pixels, 2, 2, 14, 14, plate)
        _fill_rect(pixels, 2, 2, 14, 3, plate_dark)
        _fill_rect(pixels, 2, 13, 14, 14, bay_edge)
        for cx, cy in ((3, 3), (12, 3), (3, 12), (12, 12)):
            pixels[cy][cx] = (bay_dark[0], bay_dark[1], bay_dark[2], 255)
        _fill_rect(pixels, 4, 6, 12, 10, bay_edge)
        _fill_rect(pixels, 5, 7, 11, 9, fill)
        _fill_rect(pixels, 6, 8, 10, 9, plate_dark)
        _fill_rect(pixels, 7, 11, 9, 13, _on(led))
    return 16, 16, pixels


def gen_textures():
    seen = {}
    for name, face, fill, border, led in TEXTURES:
        path = os.path.join(ASSETS, "textures", "block", "%s.png" % name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = png_bytes(*frame_pixels(fill, border, led, face))
        if data in seen:
            raise SystemExit("[X] %s 与 %s 字节完全相同（贴图不许一图多用）" % (name, seen[data]))
        seen[data] = name
        with open(path, "wb") as handle:
            handle.write(data)
        print("[OK  ] %s (%d bytes, 面=%s)" % (os.path.relpath(path, ROOT).replace("\\", "/"),
                                              len(data), face))


def gen_models():
    ns = "%s:" % NS
    write_json(os.path.join(ASSETS, "blockstates", "%s.json" % BLOCK), {
        "variants": {
            "active=false": {"model": ns + "block/%s" % BLOCK},
            "active=true": {"model": ns + "block/%s_active" % BLOCK},
        },
    })
    # 模型后缀 与 贴图后缀 不是同一个东西：inactive 模型的贴图是 *_inactive（活跃模型才是 *_active）。
    # particle 必须指向**真实存在**的贴图，否则破坏方块时喷紫黑（missing texture）粒子 ——
    # 之前这里写的是 `_side` + model_suffix（""）= `intermediate_cache_side`，那个文件从来不存在。
    for model_suffix, tex_suffix in (("", "_inactive"), ("_active", "_active")):
        write_json(os.path.join(ASSETS, "models", "block", "%s%s.json" % (BLOCK, model_suffix)), {
            "parent": "minecraft:block/cube",
            "textures": {
                "particle": ns + "block/%s_side%s" % (BLOCK, tex_suffix),
                "north": ns + "block/%s_side%s" % (BLOCK, tex_suffix),
                "east": ns + "block/%s_side%s" % (BLOCK, tex_suffix),
                "south": ns + "block/%s_side%s" % (BLOCK, tex_suffix),
                "west": ns + "block/%s_side%s" % (BLOCK, tex_suffix),
                "up": ns + "block/%s_top%s" % (BLOCK, tex_suffix),
                "down": ns + "block/%s_bottom%s" % (BLOCK, tex_suffix),
            },
        })
    # 物品模型：指向 active 版（与 collection_cache / sequence_execution_chamber 同一写法）
    write_json(os.path.join(ASSETS, "models", "item", "%s.json" % BLOCK), {
        "parent": ns + "block/%s_active" % BLOCK,
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
    # 与项目既有方块一致：进 pickaxe 可挖掘标签（只加行 + 排序，不动别人的条目）
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
    problems = []
    for locale, entries in LANG_ADD.items():
        for key, value in entries.items():
            if locale == "zh_cn" and len(value) > ZH_MAX:
                problems.append("中文键 %s 长度 %d > %d" % (key, len(value), ZH_MAX))
        path = os.path.join(ASSETS, "lang", "%s.json" % locale)
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        for key in LANG_DELETE:
            data.pop(key, None)
        merged = dict(data)
        merged.update(entries)
        # 保持既有文件的行序口径：整份按键名排序（与当前两份 lang 的既有顺序一致）
        write_json(path, dict(sorted(merged.items())))
        print("       %s: 新增/更新 %d 键、删除 %d 键，合计 %d 键"
              % (locale, len(entries), len(LANG_DELETE), len(merged)))
    return problems


def check_lang():
    """复核：新增键都在、待删键都不在、占位符个数与 Java 传参一致、容量语义与 Java 常量一致。"""
    problems = []
    for locale, entries in LANG_ADD.items():
        path = os.path.join(ASSETS, "lang", "%s.json" % locale)
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        for key in entries:
            if key not in data:
                problems.append("%s 缺少键 %s" % (locale, key))
        for key in LANG_DELETE:
            if key in data:
                problems.append("%s 未删除键 %s" % (locale, key))
    # 占位符个数 = Java 侧传参个数（少一个就抛格式化异常，多一个会显示成字面量）
    expect_args = {
        "disk.none": 0,
        "disk.usage": 2,
        "disk.usage.unlimited": 1,
    }
    for key, count in expect_args.items():
        full = "gui.rs_create_compat.intermediate_cache." + key
        for locale in ("zh_cn", "en_us"):
            with io.open(os.path.join(ASSETS, "lang", "%s.json" % locale), "r", encoding="utf-8") as handle:
                text = json.load(handle)[full]
            if text.count("%s") != count:
                problems.append("%s 的 %s 占位符 %d 个，应为 %d 个：%s"
                                % (locale, full, text.count("%s"), count, text))
    return problems


def check_capacity_text():
    """容量语义自检：文案里**不得**再出现 1728（方块不再自带容量），
    且「一个小箱子大小」必须与 Java 的 27 格盘位数同源。"""
    problems = []
    with io.open(os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                              "rs_create_compat", "block", "entity",
                              "IntermediateCacheBlockEntity.java"), "r", encoding="utf-8") as handle:
        src = handle.read()
    if "DISK_SLOTS = 27" not in src:
        problems.append("IntermediateCacheBlockEntity 未声明 DISK_SLOTS = 27（一个小箱子的槽数）")
    if "BASELINE_CAPACITY" in src:
        problems.append("IntermediateCacheBlockEntity 仍带基线下限容量（BASELINE_CAPACITY）")
    for locale in ("zh_cn", "en_us"):
        with io.open(os.path.join(ASSETS, "lang", "%s.json" % locale), "r", encoding="utf-8") as handle:
            data = json.load(handle)
        blob = " ".join(v for k, v in data.items()
                        if k.startswith("block.rs_create_compat.intermediate_cache")
                        or k.startswith("gui.rs_create_compat.intermediate_cache"))
        if "1728" in blob:
            problems.append("%s 的缓存仓文案仍写着 1728 件自带容量" % locale)
        if "27" not in data.get("gui.rs_create_compat.intermediate_cache.tip.capacity", ""):
            problems.append("%s 的 tip.capacity 未写明 27 格盘位：%s"
                            % (locale, data.get("gui.rs_create_compat.intermediate_cache.tip.capacity")))
    if not problems:
        print("[OK  ] 容量语义 = 一个小箱子大小的磁盘存放空间（27 格盘位），文案已不再声称自带 1728 件")
    return problems


def check_registration():
    """源码锚点：资源存在但没注册 = 白干。"""
    with io.open(JAVA_MAIN, "r", encoding="utf-8") as handle:
        src = handle.read()
    anchors = {
        'BLOCKS.register("%s"' % BLOCK: "方块注册",
        'ITEMS.register("%s"' % BLOCK: "物品注册",
        'BLOCK_ENTITIES.register("%s"' % BLOCK: "方块实体注册",
        'MENUS.register("%s"' % BLOCK: "菜单注册",
        "output.accept(INTERMEDIATE_CACHE_ITEM.get())": "创造栏条目",
        "IntermediateCacheBlockEntity::registerCapabilities": "能力注册（网络节点容器）",
        "RsccCacheInvalidation.register()": "池缓存的事件驱动失效注册",
    }
    ok = True
    for anchor, what in anchors.items():
        hit = anchor in src
        print("[%s] 源码锚点 %s：%s" % ("OK  " if hit else "FAIL", what, anchor))
        ok = ok and hit
    return ok


def check_particles():
    """A2 自检：所有模型引用的贴图 id 都必须**真实存在**（否则破坏方块会喷紫黑 missing 粒子）。"""
    problems = []
    model_dir = os.path.join(ASSETS, "models", "block")
    tex_dir = os.path.join(ASSETS, "textures")
    for name in sorted(os.listdir(model_dir)):
        if not name.startswith(BLOCK):
            continue
        with io.open(os.path.join(model_dir, name), "r", encoding="utf-8") as handle:
            model = json.load(handle)
        for key, value in model.get("textures", {}).items():
            if not str(value).startswith(NS + ":"):
                continue
            rel = str(value).split(":", 1)[1] + ".png"
            path = os.path.join(tex_dir, rel)
            if not os.path.exists(path):
                problems.append("%s 的 %s 引用了不存在的贴图 %s" % (name, key, value))
            else:
                print("[OK  ] %s.%s -> %s 存在" % (name, key, value))
    return problems


def main():
    gen_textures()
    gen_models()
    gen_data()
    problems = gen_lang()
    problems += check_lang()
    problems += check_capacity_text()
    problems += check_particles()
    ok = check_registration()
    print("=" * 60)
    for problem in problems:
        print("[X] %s" % problem)
    print("[result] %s%s"
          % ("资源有问题：%d 个；" % len(problems) if problems else "",
             "资源生成完毕，注册锚点齐全" if ok else "注册锚点缺失，请检查 RS_Create_Compat.java"))
    return 1 if (problems or not ok) else 0


if __name__ == "__main__":
    raise SystemExit(main())
