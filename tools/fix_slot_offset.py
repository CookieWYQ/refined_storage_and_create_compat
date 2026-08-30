#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修正 Menu 槽位偏移：背景精灵坐标 +1（用户反馈之前 -1 是反的）。"""
import io, re

def fix(path, rules):
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    for old, new in rules:
        if old not in src:
            print(f"[WARN] 未找到片段 in {path}: {old[:60]!r}")
            continue
        src = src.replace(old, new)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print(f"[OK] {path}")

base = r'd:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat\menu'

# ===== SchematicLoaderMenu：背景精灵 (8,8)/(187,6+i*18)/(8,26)/(8,140)/(8,198) → Menu +1 = (9,9)/(188,7+i*18)/(9,27)/(9,141)/(9,199)
fix(base + r'\SchematicLoaderMenu.java', [
    ("""        // 蓝图槽（Menu 槽位 x/y 各-1 以与背景 slot 精灵对齐）
        addSlot(new SlotItemHandler(blueprint, 0, 7, 7));
        // 插件槽（6 格竖排，界面右侧独立栏，仿 RS 原版 x=186 y=5+i*18）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 186, 5 + i * 18));
        }
        // 主库存 54 格（6 行 × 9 列）
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(loaderInv, col + row * 9, 7 + col * 18, 25 + row * 18));
            }
        }
        // 玩家主物品栏
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 7 + col * 18, 139 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 7 + col * 18, 197));
        }""",
     """        // 蓝图槽（Menu 槽位 x/y = 背景精灵坐标 +1，与背景 slot 精灵重合）
        addSlot(new SlotItemHandler(blueprint, 0, 9, 9));
        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 x=187 y=6+i*18 → Menu +1）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18));
        }
        // 主库存 54 格（6 行 × 9 列，背景精灵 (8,26) 起 → Menu (9,27) 起）
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(loaderInv, col + row * 9, 9 + col * 18, 27 + row * 18));
            }
        }
        // 玩家主物品栏（背景精灵 (8,140) 起 → Menu (9,141) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 141 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,198) → Menu (9,199)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 199));
        }"""),
])

# ===== AdvancedSchematicLoaderMenu：背景精灵插件(186,5+i*18)/队列(7,29)/库存(7,97)/玩家(7,216)/快捷(7,274) → Menu +1
fix(base + r'\AdvancedSchematicLoaderMenu.java', [
    ("""        // 插件槽（6 格竖排，界面右侧独立栏）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 186, 5 + i * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 7 + col * 18, 29 + row * 18));
            }
        }
        // 主库存 108 格：逻辑上 12 行，初始 y 全部放到前 6 行位置，Screen 会按滚动偏移刷新
        for (int row = 0; row < TOTAL_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                final int idx = col + row * COLS;
                final int baseY = STORAGE_BASE_Y + row * ROW_SIZE;
                storageBaseY[idx] = baseY;
                // 初始 y = 基准 y（Screen 会在 init / 滚动时覆盖为基准 y - scrollOffset）
                final SlotItemHandler slot = new SlotItemHandler(loaderInv, idx, 7 + col * 18, baseY);
                storageSlots.add(slot);
                addSlot(slot);
            }
        }
        // 玩家主物品栏（3 行 × 9）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 7 + col * 18, 216 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 7 + col * 18, 274));
        }""",
     """        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (186,5+i*18) → Menu +1）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 187, 6 + i * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列，背景精灵 (7,29) → Menu (8,30)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 8 + col * 18, 30 + row * 18));
            }
        }
        // 主库存 108 格：逻辑上 12 行，初始 y 全部放到前 6 行位置，Screen 会按滚动偏移刷新
        for (int row = 0; row < TOTAL_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                final int idx = col + row * COLS;
                final int baseY = STORAGE_BASE_Y + row * ROW_SIZE;
                storageBaseY[idx] = baseY;
                // 初始 y = 基准 y（Screen 会在 init / 滚动时覆盖为基准 y - scrollOffset）
                final SlotItemHandler slot = new SlotItemHandler(loaderInv, idx, 8 + col * 18, baseY);
                storageSlots.add(slot);
                addSlot(slot);
            }
        }
        // 玩家主物品栏（3 行 × 9，背景精灵 (7,216) → Menu (8,217)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 217 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (7,274) → Menu (8,275)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 275));
        }"""),
    ("""    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 97;""",
     """    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 98;"""),
])

# ===== QuantityKeeperMenu：背景精灵标记槽(8,20)/插件(187,6+i*18)/玩家(8,84)/快捷(8,142) → Menu +1
fix(base + r'\QuantityKeeperMenu.java', [
    ("""        if (keeper != null) {
            addSlot(new Slot(keeper.getInventory(), 0, 7, 19));
            // 插件槽（6 格竖排，界面右侧独立栏，仿 RS 原版 x=186 y=5+i*18）
            for (int i = 0; i < 6; i++) {
                addSlot(UpgradeSlot.forContainer(keeper.getInventory(), 1 + i, 186, 5 + i * 18));
            }
        } else {
            // 客户端重建：槽位数必须与服务端一致（内容由数据包同步）
            final net.minecraft.world.SimpleContainer empty = new net.minecraft.world.SimpleContainer(7);
            addSlot(new Slot(empty, 0, 7, 19));
            for (int i = 0; i < 6; i++) {
                addSlot(UpgradeSlot.forContainer(empty, 1 + i, 186, 5 + i * 18));
            }
        }
        // 玩家主物品栏（3 行 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 7 + col * 18, 83 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 7 + col * 18, 141));
        }""",
     """        if (keeper != null) {
            // 标记槽（背景精灵 (8,20) → Menu (9,21)）
            addSlot(new Slot(keeper.getInventory(), 0, 9, 21));
            // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (187,6+i*18) → Menu +1）
            for (int i = 0; i < 6; i++) {
                addSlot(UpgradeSlot.forContainer(keeper.getInventory(), 1 + i, 188, 7 + i * 18));
            }
        } else {
            // 客户端重建：槽位数必须与服务端一致（内容由数据包同步）
            final net.minecraft.world.SimpleContainer empty = new net.minecraft.world.SimpleContainer(7);
            addSlot(new Slot(empty, 0, 9, 21));
            for (int i = 0; i < 6; i++) {
                addSlot(UpgradeSlot.forContainer(empty, 1 + i, 188, 7 + i * 18));
            }
        }
        // 玩家主物品栏（3 行 9 列，背景精灵 (8,84) → Menu (9,85)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 85 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,142) → Menu (9,143)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 143));
        }"""),
])

print("done")
