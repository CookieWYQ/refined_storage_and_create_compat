#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Screen 布局重排：基础装填器界面高度 268、按钮在库存(27..132)下方(140..188)；高级 320、按钮在库存下方。"""
import io

def fix(path, rules):
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    for old, new in rules:
        if old not in src:
            print(f"[WARN] {path}: {old[:70]!r}")
            continue
        src = src.replace(old, new)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print(f"[OK] {path}")

base = r'D:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat\client\screen'

# ===== 基础装填器 Screen：高度 222→268，按钮 ROW0_Y 120→140（库存下方），按钮区域 4 行 = 140..188 =====
fix(base + r'\SchematicLoaderScreen.java', [
    ("""    private static final int LABEL_X = 10;
    private static final int BTN_X = 108;
    private static final int ROW0_Y = 120;
    private static final int ROW_H = 16;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;""",
     """    private static final int LABEL_X = 10;
    private static final int BTN_X = 150;
    private static final int ROW0_Y = 140;
    private static final int ROW_H = 14;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;"""),
    ("""        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 222;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中""",
     """        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 268;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中"""),
    ("""        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 222);""",
     """        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 268);"""),
])

# ===== 高级装填器 Screen：高度 364→320、库存区 STORAGE_TOP 98→71、按钮 ROW0_Y 296→188、
#     队列标签 y 调整、玩家背包文字 y 调整、队列按钮 y 342→314、滚动条位置 =====
fix(base + r'\AdvancedSchematicLoaderScreen.java', [
    ("""    private static final int BG_W = 210;
    private static final int BG_H = 364;""",
     """    private static final int BG_W = 210;
    private static final int BG_H = 320;"""),
    ("""    // 开关：y=296..338 (4 行 × 14)，按钮 x=112, 标签 x=10
    private static final int LABEL_X = 10;
    private static final int BTN_X = 112;
    private static final int ROW0_Y = 296;
    private static final int ROW_H = 14;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;
    // 队列运行按钮：底部
    private static final int QUEUE_BTN_Y = 342;
    private static final int QUEUE_BTN_W = 158;
    private static final int QUEUE_BTN_H = 16;""",
     """    // 开关：y=188..230 (4 行 × 14)，按钮 x=150, 标签 x=10（库存下方、玩家背包上方）
    private static final int LABEL_X = 10;
    private static final int BTN_X = 150;
    private static final int ROW0_Y = 188;
    private static final int ROW_H = 14;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;
    // 队列运行按钮：库存区域右下（滚动条下方）
    private static final int QUEUE_BTN_Y = 306;
    private static final int QUEUE_BTN_W = 158;
    private static final int QUEUE_BTN_H = 16;"""),
    ("""        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.queue"),
            8, 18, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.storage"),
            8, 86, 0xA0A0A0, false);""",
     """        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.queue"),
            8, 0, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.storage"),
            8, 62, 0xA0A0A0, false);"""),
    ("""        // 玩家背包文字
        guiGraphics.drawString(font, playerInventoryTitle, 8, 204, 0xA0A0A0, false);""",
     """        // 玩家背包文字
        guiGraphics.drawString(font, playerInventoryTitle, 8, 220, 0xA0A0A0, false);"""),
])

print("done")
