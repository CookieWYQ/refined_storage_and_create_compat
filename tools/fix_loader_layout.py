# -*- coding: utf-8 -*-
import io

def fix(path, rules):
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    for old, new in rules:
        if old not in src:
            print(f"[WARN] {path}: {old[:60]!r}")
            continue
        src = src.replace(old, new)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print(f"[OK] {path}")

base = r"D:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat"

fix(base + r"\menu\SchematicLoaderMenu.java", [
    ("""        // 玩家主物品栏（背景精灵 (8,140) 起 → Menu (9,141) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 141 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,198) → Menu (9,199)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 199));
        }""",
     """        // 玩家主物品栏（背景精灵 (8,188) 起 → Menu (9,189) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 189 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,260) → Menu (9,261)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 261));
        }"""),
])

fix(base + r"\menu\AdvancedSchematicLoaderMenu.java", [
    ("""    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 98;""",
     """    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 71;"""),
    ("""        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (186,5+i*18) → Menu +1）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 187, 6 + i * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列，背景精灵 (7,29) → Menu (8,30)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 8 + col * 18, 30 + row * 18));
            }
        }""",
     """        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (187,6+i*18) → Menu +1）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列，背景精灵 (8,8) → Menu (9,9)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 9 + col * 18, 9 + row * 18));
            }
        }"""),
    ("""                final SlotItemHandler slot = new SlotItemHandler(loaderInv, idx, 8 + col * 18, baseY);""",
     """                final SlotItemHandler slot = new SlotItemHandler(loaderInv, idx, 9 + col * 18, baseY);"""),
    ("""        // 玩家主物品栏（3 行 × 9，背景精灵 (7,216) → Menu (8,217)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 217 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (7,274) → Menu (8,275)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 275));
        }""",
     """        // 玩家主物品栏（3 行 × 9，背景精灵 (8,228) → Menu (9,229)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 229 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,300) → Menu (9,301)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 301));
        }"""),
])
print("done")
