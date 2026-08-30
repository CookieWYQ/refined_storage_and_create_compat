# -*- coding: utf-8 -*-
"""像素级扫描 5 张 GUI 成品图 + 对照 Menu/Screen 代码槽位坐标，
精确定位每张图存在的全部问题（槽位遗漏、多余 slot、位置错位、背景未延伸、按钮区被 slot 覆盖等）。
输出为结构化中文报告，供后续模型修改使用。
"""
import os
from PIL import Image

ROOT = r"D:\MODS\refined_storage_and_create_compat"
GUI_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "gui")
PIC_DIR = os.path.join(ROOT, "pic")

BG_PATH = os.path.join(PIC_DIR, "bg.png")
SLOT_PATH = os.path.join(PIC_DIR, "slot.png")

# 单槽位尺寸（SlotItemHandler 的槽位中心间距 = 18px）
SLOT = 18

# 各 Screen / Menu 的槽位+按钮布局定义（与 Java 代码严格一致，按 GUI 内部坐标，不含 leftPos/topPos 偏移）
# 每张图 = (width, height, slot_regions[(name, x, y, cols, rows)], button_regions[(name, x, y, w, h)], label_regions[(name, x, y, w, h)])
#   x,y = 该区域在 GUI 内部的左上角（相对于背景图左上角）
LAYOUTS = {
    "schematic_loader.png": {
        "size": (208, 222),
        "slot_regions": [
            # 与 SchematicLoaderMenu.java 一致（基础版）
            ("blueprint_slot",    8,   8,  1,  1),
            ("upgrade_slots",    35,   8,  6,  1),
            ("storage_slots",     8,  26,  9,  6),
            ("player_inventory",  8, 140,  9,  3),
            ("hotbar",            8, 198,  9,  1),
        ],
        "button_regions": [],  # 本 GUI 无 GUI 内按钮（按钮在 Screen 标题栏外渲染）
        "blank_regions": [
            # 顶部标签/标题留白（不应画 slot）：y = 0..25，x 全宽 除 slot 占用外
            # SchematicLoaderScreen.renderLabels：titleLabelY=6，标题行 y=0..14 不应有 slot
            ("title_row", 0, 0, 208, 26, "标题/标签区域（背景上不画 slot，仅 bg.png 边框底纹）"),
            # 玩家物品栏与快捷栏之间的间距行 y=194..197（玩家背包最后一行 y=140+54-18=176, 3*18=54, 末行 y=176+18-1=193; hotbar 从 198 起，中间 194-197 无 slot）
            ("inv_hotbar_gap", 8, 194, 9*18, 4, "物品栏与快捷栏间隔"),
        ],
    },
    "advanced_schematic_loader.png": {
        "size": (176, 428),
        "slot_regions": [
            # 与 AdvancedSchematicLoaderMenu.java 一致（修复版）
            ("upgrade_slots",   124,   8,  2,  3),  # 右上角 2×3
            ("queue_slots",       8,  30,  9,  3),  # 蓝图队列 3 行
            ("storage_slots",     8,  98,  9, 12),  # 主库存 12 行
            ("player_inventory",  8, 332,  9,  3),  # 玩家背包
            ("hotbar",            8, 390,  9,  1),  # 快捷栏
        ],
        "button_regions": [
            # 5 个按钮（AdvancedSchematicLoaderScreen.init）x,y,w,h 在 GUI 内部
            ("btn_A",   0, 392, 18, 14),
            ("btn_D",  20, 392, 18, 14),
            ("btn_R",  40, 392, 18, 14),
            ("btn_G",  60, 392, 18, 14),
            ("btn_Start", 92, 392, 68, 14),
        ],
        "blank_regions": [
            ("title_row",     0,   0, 176,  30, "标题 + 标签（含 queue 标签 y=20，storage 标签 y=88 也需要空位）"),
            ("queue_storage_gap", 0,  84, 176, 14, "queue 区(30+54=84) 与 storage 区(98) 之间的标签间隔"),
            ("storage_inv_gap",   0, 314, 176, 18, "storage 末行(98+12*18=314) 与 背包(332) 之间的间隔"),
            ("hotbar_button_gap", 0, 406, 176, 22, "快捷栏底部 y=390+18=408，到背景底部 428 之间 + 按钮行 y=392+14=406 之后区域"),
            ("upgrade_left_area", 0, 8, 116, 22, "插件槽 124+ 左侧 x=0..123、y=8..29 区域（不应画 slot，要留给标题/可能的状态显示）"),
        ],
    },
    "quantity_keeper.png": {
        "size": None,  # 扫描后填充
        "slot_regions": [],  # 由代码读取后填入
        "button_regions": [],
        "blank_regions": [],
    },
    "range_charger.png": {
        "size": None,
        "slot_regions": [],
        "button_regions": [],
        "blank_regions": [],
    },
    "advanced_remote_terminal.png": {
        "size": None,
        "slot_regions": [],
        "button_regions": [],
        "blank_regions": [],
    },
}


def load(path):
    return Image.open(path).convert("RGBA")


def detect_slot_cells(img):
    """在整张图中检测所有被 slot 边框覆盖的 18×18 单元。
    判断标准：slot.png 左上角 4×4 是深灰边（color ~55），单元 (x,y) 指其左上角坐标，
    若该位置 4 条边 (top=y, bottom=y+17, left=x, right=x+17) 的像素有 ≥ 70% 与 slot.png 的 18×18 原型匹配，则认为是 slot 格。
    """
    slot_pat = load(SLOT_PATH).crop((0, 0, SLOT, SLOT))
    w, h = img.size
    cells = []
    # 预先拿 slot 原型的边像素
    edge_pts = []
    for i in range(SLOT):
        edge_pts.append((i, 0, slot_pat.getpixel((i, 0))))
        edge_pts.append((i, SLOT-1, slot_pat.getpixel((i, SLOT-1))))
        edge_pts.append((0, i, slot_pat.getpixel((0, i))))
        edge_pts.append((SLOT-1, i, slot_pat.getpixel((SLOT-1, i))))
    for y in range(0, h - SLOT + 1, 2):  # 步长 2 扫描以加速
        for x in range(0, w - SLOT + 1, 2):
            match = 0
            for dx, dy, expected in edge_pts:
                px = img.getpixel((x + dx, y + dy))
                # 颜色差（忽略透明差异）
                dist = sum(abs(a - b) for a, b in zip(px[:3], expected[:3]))
                if dist < 30:
                    match += 1
            if match / len(edge_pts) > 0.70:
                cells.append((x, y))
    # 去重（2px 步长会产生相邻命中）
    seen = set()
    deduped = []
    for x, y in cells:
        key = (x // SLOT, y // SLOT)
        if key in seen:
            continue
        seen.add(key)
        # 向 18 栅格对齐
        gx, gy = (x // SLOT) * SLOT, (y // SLOT) * SLOT
        # 若附近 0-8 偏移内有更精确对齐的格，修正
        if (gx + SLOT) <= w and (gy + SLOT) <= h:
            deduped.append((gx, gy))
    return sorted(set(deduped))


def region_cells(slot_regions):
    """由布局定义展开所有应有的 slot 单元列表。"""
    expected = set()
    for (name, x, y, cols, rows) in slot_regions:
        for r in range(rows):
            for c in range(cols):
                expected.add((x + c * SLOT, y + r * SLOT))
    return expected


def cells_overlap(cells_set, rect_xywh):
    """检查 cells_set 中哪些落在给定矩形区域内。"""
    x, y, w, h = rect_xywh
    hits = set()
    for (cx, cy) in cells_set:
        if cx + SLOT > x and cx < x + w and cy + SLOT > y and cy < y + h:
            hits.add((cx, cy))
    return hits


def main():
    print("=" * 80)
    print(f"素材分析：bg.png 尺寸 {load(BG_PATH).size}，slot.png 尺寸 {load(SLOT_PATH).size}")
    slot_top = load(SLOT_PATH).crop((0, 0, SLOT, SLOT))
    print(f"  slot 原型左上 18×18 四角色：TL={slot_top.getpixel((0,0))} TR={slot_top.getpixel((17,0))} BL={slot_top.getpixel((0,17))} BR={slot_top.getpixel((17,17))}")
    print("=" * 80)

    # 从 Java 代码补 quantity_keeper / range_charger / advanced_remote_terminal 的布局
    import re, glob

    def read_menu_slots(menu_path):
        """简单解析 Menu.java 中的 addSlot(... 8 + col * 18, Y + row * 18 ...) 模式，
        提取 (x_base, y_start, cols, rows)。返回 [(name, x, y, cols, rows)]。"""
        with open(menu_path, "r", encoding="utf-8") as f:
            src = f.read()
        # 提取所有 for (row|col) 嵌套块中的 X/Y
        regions = []
        # 玩家背包/快捷栏/升级/库存：匹配注释
        comments = {}
        for m in re.finditer(r"//\s*([^\n]+)\n\s*for\s*\(int\s+row\s*=\s*0;\s*row\s*<\s*(\d+);\s*row\+\+\)\s*\{\s*for\s*\(int\s+col\s*=\s*0;\s*col\s*<\s*(\d+);\s*col\+\+\)", src):
            name = m.group(1).strip().replace(" ", "_")
            rows = int(m.group(2)); cols = int(m.group(3))
            rem = src[m.end():]
            mx = re.search(r"(\d+)\s*\+\s*col\s*\*\s*18,\s*(\d+)\s*\+\s*row\s*\*\s*18", rem)
            if mx:
                x0 = int(mx.group(1)); y0 = int(mx.group(2))
                regions.append((name, x0, y0, cols, rows))
        # 单行横排槽（升级槽 6 格横排等 for (int i = 0; i < N; i++) ... col+i*18, Y）
        for m in re.finditer(r"//\s*([^\n]+)\n\s*for\s*\(int\s+i\s*=\s*0;\s*i\s*<\s*(\d+);\s*i\+\+\)", src):
            name = m.group(1).strip().replace(" ", "_")
            n = int(m.group(2))
            rem = src[m.end():]
            # 匹配 (N + i * 18, Y) 或 (N + (i%2)*18, Y + (i/2)*18)
            m1 = re.search(r"\((\d+)\s*\+\s*(?:\(i\s*%\s*(\d+)\)\s*\*\s*18|i\s*\*\s*18),\s*(\d+)\s*(?:\+\s*(?:\(i\s*/\s*(\d+)\)\s*\*\s*18))?\)", rem)
            if m1:
                x0 = int(m1.group(1)); cols_per_row = int(m1.group(2) or n)
                y0 = int(m1.group(3)); rows_per = int(m1.group(4) or 1)
                cols = cols_per_row
                rows = max(1, (n + cols_per_row - 1) // cols_per_row)
                # 若使用 i%2 和 i/rows_per 说明是 2×3 竖排，行列互换处理
                if m1.group(2) and m1.group(4):
                    cols = int(m1.group(2))
                    rows = int(m1.group(4))
                regions.append((name, x0, y0, cols, rows))
        # 单槽（蓝图槽等）addSlot(new SlotItemHandler(X, 0, 8, 8)) 形式
        for m in re.finditer(r"//\s*([^\n]+)\n\s*addSlot\(new\s+Slot(?:ItemHandler)?\([^,]+,\s*0,\s*(\d+),\s*(\d+)\)\)", src):
            name = m.group(1).strip().replace(" ", "_")
            x = int(m.group(2)); y = int(m.group(3))
            regions.append((name, x, y, 1, 1))
        return regions

    menu_files = {
        "quantity_keeper.png": "QuantityKeeperMenu.java",
        "range_charger.png": "RangeChargerMenu.java",
        "advanced_remote_terminal.png": "AdvancedRemoteTerminalMenu.java",
    }
    for fname, mf in menu_files.items():
        candidates = glob.glob(os.path.join(ROOT, "src", "main", "java", "**", mf), recursive=True)
        if candidates:
            try:
                slots = read_menu_slots(candidates[0])
            except Exception as e:
                slots = []
                print(f"  [WARN] 解析 {mf} 失败：{e}")
            LAYOUTS[fname]["slot_regions"] = slots

    # 再从 Screen 代码读按钮/内部坐标信息
    def read_screen_size(screen_path):
        with open(screen_path, "r", encoding="utf-8") as f:
            src = f.read()
        ws = re.findall(r"imageWidth\s*=\s*(\d+)", src)
        hs = re.findall(r"imageHeight\s*=\s*(\d+)", src)
        return (int(ws[0]), int(hs[0])) if (ws and hs) else None

    screen_files = {
        "quantity_keeper.png": "QuantityKeeperScreen.java",
        "range_charger.png": "RangeChargerScreen.java",
        "advanced_remote_terminal.png": "AdvancedRemoteTerminalScreen.java",
        "schematic_loader.png": "SchematicLoaderScreen.java",
        "advanced_schematic_loader.png": "AdvancedSchematicLoaderScreen.java",
    }
    for fname, sf in screen_files.items():
        candidates = glob.glob(os.path.join(ROOT, "src", "main", "java", "**", sf), recursive=True)
        if candidates:
            sz = read_screen_size(candidates[0])
            if sz and (LAYOUTS[fname]["size"] is None or LAYOUTS[fname]["size"] == sz):
                LAYOUTS[fname]["size"] = sz
            elif sz and LAYOUTS[fname]["size"] != sz:
                print(f"  [WARN] {fname}: Screen imageWidth/Height={sz} vs 预设尺寸={LAYOUTS[fname]['size']} 不一致")

    def read_screen_buttons(screen_path):
        with open(screen_path, "r", encoding="utf-8") as f:
            src = f.read()
        # .bounds(leftPos + X, topPos + Y, W, H)
        return [(int(x), int(y), int(w), int(h)) for x, y, w, h in
                re.findall(r"\.bounds\(\s*leftPos\s*\+\s*(\d+)\s*,\s*topPos\s*\+\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)", src)]

    for fname, sf in screen_files.items():
        candidates = glob.glob(os.path.join(ROOT, "src", "main", "java", "**", sf), recursive=True)
        if candidates:
            btns = read_screen_buttons(candidates[0])
            for i, (x, y, w, h) in enumerate(btns, 1):
                LAYOUTS[fname]["button_regions"].append((f"button_{i}", x, y, w, h))

    # ===================== 逐图扫描 =====================
    for fname in sorted(LAYOUTS.keys()):
        fpath = os.path.join(GUI_DIR, fname)
        if not os.path.exists(fpath):
            print(f"\n=== {fname} === 【文件不存在，跳过】")
            continue
        img = load(fpath)
        real_w, real_h = img.size
        cfg = LAYOUTS[fname]
        expected_w, expected_h = cfg.get("size") or (None, None)

        print(f"\n{'='*80}")
        print(f"=== {fname} ===")
        print(f"  实际尺寸: {real_w} x {real_h}  代码声明(Screen imageWidth/Height): {expected_w} x {expected_h}")

        issues = []
        if expected_w and (real_w != expected_w or real_h != expected_h):
            issues.append(f"【尺寸错误】图像 {real_w}x{real_h}，但 Screen imageWidth/Height={expected_w}x{expected_h}，必须一致否则 blit 出现拉伸/裁切")

        # 背景边界检测（bg.png 是灰底边框：左右边缘是否有 bg.png 的边）
        # 扫描左右 4 像素 + 上下 4 像素是否有深灰边框色 ~55（bg.png 的边框色）
        border_pix = 0
        for i in range(real_h):
            if img.getpixel((0, i))[0] < 100: border_pix += 1
            if img.getpixel((real_w-1, i))[0] < 100: border_pix += 1
        for i in range(real_w):
            if img.getpixel((i, 0))[0] < 100: border_pix += 1
            if img.getpixel((i, real_h-1))[0] < 100: border_pix += 1
        total = 2 * real_h + 2 * real_w
        border_ratio = border_pix / total
        if border_ratio < 0.40:
            issues.append(f"【背景边框缺失】边缘像素深灰覆盖率仅 {border_ratio:.1%}，bg.png 边框未正确作为整体轮廓渲染")
        else:
            print(f"  背景边框检测: 深灰覆盖率 {border_ratio:.1%} (≥40% OK)")

        # 背景内部是否有"纯白洞"——即 bg.png 未完全铺满整图（bg 内部是 ~200 浅灰，slot 是 139）
        # 统计非边缘像素中纯白(>250)像素比例
        inner_white = 0; inner_total = 0
        for y in range(4, real_h - 4, 3):
            for x in range(4, real_w - 4, 3):
                inner_total += 1
                r, g, b, a = img.getpixel((x, y))
                if a < 200:
                    continue
                if r > 250 and g > 250 and b > 250:
                    inner_white += 1
        if inner_total and inner_white / inner_total > 0.05:
            issues.append(f"【背景未铺满】内部 {inner_white/inner_total:.1%} 像素是纯白，bg.png 底纹没有完全覆盖整图作为背景（用户明确要求 bg.png 拉伸覆盖全部背景）")

        # 扫描实际所有 slot 单元
        actual_cells = set(detect_slot_cells(img))
        expected_cells = region_cells(cfg["slot_regions"])

        missing = expected_cells - actual_cells
        extra = actual_cells - expected_cells
        print(f"  应存在槽位: {len(expected_cells)}  实际检测到槽位: {len(actual_cells)}")
        print(f"  槽位名称定义:")
        for (name, x, y, cols, rows) in cfg["slot_regions"]:
            print(f"    - {name} @ ({x},{y}) cols={cols} rows={rows} ({cols*rows} 格)")

        if missing:
            # 按所在分组聚合
            groups = {}
            for (cx, cy) in missing:
                matched = None
                for (name, x, y, cols, rows) in cfg["slot_regions"]:
                    if x <= cx < x + cols*SLOT and y <= cy < y + rows*SLOT:
                        matched = name; break
                groups.setdefault(matched or "unknown_region", []).append((cx, cy))
            for region_name, cells in groups.items():
                cells_sorted = sorted(cells)
                issues.append(f"【槽位缺失】{region_name} 区域缺少 {len(cells)} 个 slot 背景单元，示例: {cells_sorted[:5]}（总 {len(cells)}）")

        if extra:
            # 按所在空白/按钮区域聚合
            grouped_extra = {"未分配区域": []}
            for (cx, cy) in extra:
                placed = False
                for (name, bx, by, bw, bh, *_desc) in cfg.get("blank_regions", []) + [
                    (bn, bx, by, bw, bh, f"按钮区 {bn}") for (bn, bx, by, bw, bh) in cfg.get("button_regions", [])
                ]:
                    if bx <= cx < bx + bw and by <= cy < by + bh:
                        grouped_extra.setdefault(f"空白/按钮区【{name}】", []).append((cx, cy))
                        placed = True
                        break
                if not placed:
                    # 也可能是 label_regions 顶部标题区
                    grouped_extra["未分配区域"].append((cx, cy))
            for where, cells in grouped_extra.items():
                cells_sorted = sorted(cells)
                if not cells_sorted:
                    continue
                issues.append(f"【多余 slot】{where} 不应有槽位，但存在 {len(cells)} 个 slot 背景单元，示例: {cells_sorted[:10]}")

        # 检查按钮区域被 slot 遮挡
        for (bname, bx, by, bw, bh) in cfg.get("button_regions", []):
            hits = cells_overlap(actual_cells, (bx, by, bw, bh))
            if hits:
                issues.append(f"【按钮被遮挡】按钮 {bname} ({bx},{by}) {bw}x{bh} 与 {len(hits)} 个 slot 背景重叠，重叠单元: {sorted(hits)[:5]}")

        # 检查"整幅背景只画 slot 没画底纹"——像素灰度只有 slot 内部(139)和边框(55)，缺少 bg 背景(约 200)
        # 抽样非 slot 区域的像素灰度分布
        slot_occupied = set()
        for (cx, cy) in actual_cells:
            for dy in range(SLOT):
                for dx in range(SLOT):
                    slot_occupied.add((cx + dx, cy + dy))
        bg_samples = []
        for y in range(2, real_h - 2, 3):
            for x in range(2, real_w - 2, 3):
                if (x, y) not in slot_occupied and img.getpixel((x, y))[3] >= 128:
                    bg_samples.append(img.getpixel((x, y))[0])
        if bg_samples:
            avg = sum(bg_samples) / len(bg_samples)
            if avg < 155:
                issues.append(f"【背景灰度异常】非 slot 区域平均亮度 R={avg:.0f}，正常值约 200（bg.png 浅灰底）。说明背景没有用 bg.png 铺满，整图视觉上几乎全是 slot 深色")
            else:
                print(f"  背景灰度检测: 非slot区域平均R={avg:.0f} (正常≈200)")

        print(f"  问题总计: {len(issues)}")
        for idx, issue in enumerate(issues, 1):
            print(f"    [{idx}] {issue}")

    print("\n" + "=" * 80)
    print("报告完毕。以上问题供后续模型修复参考。")


if __name__ == "__main__":
    main()
