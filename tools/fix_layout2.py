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

fix(base + r"\menu\SchematicLoaderMenu.java", [
    ("""        // 玩家主物品栏（背景精灵 (8,188) 起 → Menu (9,189) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 189 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,260) → Menu (9,261)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 261));
        }""",
     """        // 玩家主物品栏（背景精灵 (8,196) 起 → Menu (9,197) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 197 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,268) → Menu (9,269)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 269));
        }"""),
])

fix(base + r"\menu\AdvancedSchematicLoaderMenu.java", [
    ("""        // 玩家主物品栏（3 行 × 9，背景精灵 (8,228) → Menu (9,229)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 229 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,300) → Menu (9,301)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 301));
        }""",
     """        // 玩家主物品栏（3 行 × 9，背景精灵 (8,240) → Menu (9,241)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 241 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,312) → Menu (9,313)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 313));
        }"""),
])

fix(base + r"\client\screen\SchematicLoaderScreen.java", [
    ("""        this.imageHeight = 268;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中""",
     """        this.imageHeight = 285;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中"""),
    ("""        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 268);""",
     """        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 285);"""),
])

fix(base + r"\client\screen\AdvancedSchematicLoaderScreen.java", [
    ("""    private static final int BG_W = 210;
    private static final int BG_H = 320;""",
     """    private static final int BG_W = 210;
    private static final int BG_H = 329;"""),
    ("""    // 开关：y=188..230 (4 行 × 14)，按钮 x=150, 标签 x=10（库存下方、玩家背包上方）
    private static final int LABEL_X = 10;
    private static final int BTN_X = 150;
    private static final int ROW0_Y = 188;""",
     """    // 开关：y=186..232 (4 行 × 14)，按钮 x=150, 标签 x=10（库存下方、玩家背包上方）
    private static final int LABEL_X = 10;
    private static final int BTN_X = 150;
    private static final int ROW0_Y = 186;"""),
    ("""    // 队列运行按钮：底部（玩家背包下方）
    private static final int QUEUE_BTN_Y = 300;""",
     """    // 队列运行按钮：底部（玩家背包下方）
    private static final int QUEUE_BTN_Y = 316;"""),
    ("""        // 玩家背包文字（库存下方按钮区之后）
        guiGraphics.drawString(font, playerInventoryTitle, 8, 222, 0xA0A0A0, false);""",
     """        // 玩家背包文字（库存下方按钮区之后）
        guiGraphics.drawString(font, playerInventoryTitle, 8, 234, 0xA0A0A0, false);"""),
])
print("done")
