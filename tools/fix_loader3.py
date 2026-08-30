# -*- coding: utf-8 -*-
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

base = r"D:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat"

# 基础 Menu：玩家(8,196)→(8,172) +1=(9,173)，快捷(8,268)→(8,244)+1=(9,245)
fix(base + r"\menu\SchematicLoaderMenu.java", [
    ("""        // 玩家主物品栏（背景精灵 (8,196) 起 → Menu (9,197) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 197 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,268) → Menu (9,269)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 269));
        }""",
     """        // 玩家主物品栏（背景精灵 (8,172) 起 → Menu (9,173) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 173 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,244) → Menu (9,245)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 245));
        }"""),
])

# 基础 Screen：高度 285→261、按钮 2x2（y=140/156，标签 x=10/100 按钮 x=68/158）、blit
fix(base + r"\client\screen\SchematicLoaderScreen.java", [
    ("""    /** 每个 toggle 的位置：(labelX, labelY, btnX, btnY) —— 库存下方（y=140..188），玩家背包上方。 */
    private static final int LABEL_X = 10;
    private static final int BTN_X = 150;
    private static final int ROW0_Y = 140;
    private static final int ROW_H = 14;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;""",
     """    // 开关：2 列 × 2 行（标签 x=10/100，按钮 x=68/158，行 y=140/156），库存下方、玩家背包上方
    private static final int[] TOGGLE_LABEL_X = {10, 100};
    private static final int[] TOGGLE_BTN_X = {68, 158};
    private static final int ROW0_Y = 140;
    private static final int ROW_H = 16;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;"""),
    ("""        this.imageHeight = 285;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中""",
     """        this.imageHeight = 261;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中"""),
    ("""        for (int i = 0; i < BTN_IDS.length; i++) {
            final int id = BTN_IDS[i];
            final int y = ROW0_Y + i * ROW_H;
            final Button button = new Button.Builder(getStateFor(id), btn -> sendButton(id))
                .bounds(leftPos + BTN_X, topPos + y, BTN_W, BTN_H)
                .build();
            toggleButtons[i] = button;
            addRenderableWidget(button);
        }""",
     """        for (int i = 0; i < BTN_IDS.length; i++) {
            final int id = BTN_IDS[i];
            final int row = i / 2;
            final int col = i % 2;
            final int y = ROW0_Y + row * ROW_H;
            final Button button = new Button.Builder(getStateFor(id), btn -> sendButton(id))
                .bounds(leftPos + TOGGLE_BTN_X[col], topPos + y, BTN_W, BTN_H)
                .build();
            toggleButtons[i] = button;
            addRenderableWidget(button);
        }"""),
    ("""        // 开关标签（左侧文字，亮白提升可读性）
        for (int i = 0; i < BTN_LABEL_KEYS.length; i++) {
            guiGraphics.drawString(font, Component.translatable(BTN_LABEL_KEYS[i]),
                LABEL_X, ROW0_Y + i * ROW_H + 2, 0xFFFFFF, false);
        }""",
     """        // 开关标签（2 列布局，亮白提升可读性）
        for (int i = 0; i < BTN_LABEL_KEYS.length; i++) {
            final int row = i / 2;
            final int col = i % 2;
            guiGraphics.drawString(font, Component.translatable(BTN_LABEL_KEYS[i]),
                TOGGLE_LABEL_X[col], ROW0_Y + row * ROW_H + 2, 0xFFFFFF, false);
        }"""),
    ("""        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 285);""",
     """        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 261);"""),
])
print("done")
