# -*- coding: utf-8 -*-
"""第 126 轮：GUI 槽位 ↔ 背景槽框「对位登记表」——唯一权威数据源。

本文件**只放数据**，判定逻辑全在 `tools/selfcheck_gui_slot_alignment.py`（读本表 + 读 PNG + 读源码）。

## 约定（唯一权威，不要改这里，改约定要改整条链路）

**背景贴图里 17×17 槽框的左上角 = Menu 槽位坐标 − 1**（即 `OFFSET = -1`，**不是 +1**）。

来源（`tools/make_gui_gui.py`，权威约定原文）：
* 第 21 行「生成 Java 控件代码……（精灵坐标 +1 = Menu 坐标）」
* 第 905 行「槽位（精灵坐标）x=%d y=%d，Menu 坐标 x=%d y=%d」
* 第 1365 行「精灵坐标 → Menu/控件坐标 = 精灵 + 1」
* 第 1495 行「> 坐标说明：精灵坐标 +1 = 游戏中 Menu / 控件坐标」

实证抽样（贴图槽框 vs 该 Menu 的 addSlot，全部符合 Menu−1）：
`range_charger.png (8,84)` ↔ `RangeChargerMenu` 背包 `9+col*18, 85+row*18`；
`quantity_keeper.png (8,84)` ↔ `QuantityKeeperMenu` 背包 `9+col*18, 85+row*18`；
`schematic_loader.png (8,18)` ↔ `SchematicLoaderMenu` 蓝图槽 `9,19`；
`inventory_proxy.png (9,21)` ↔ `InventoryProxyMenu` 代理槽 `10,22`。

**Runtime 侧同一条约定**：`AbstractContainerScreen` 画槽位高亮/槽框都用 `leftPos + slot.x - 1`；
本仓库代码自绘槽框（`McGui.slotFrame` / `blitSprite(Sprites.SLOT, ...)`）也必须 `- 1`
（见 `IntermediateCacheScreen.renderBg` 等 6 处 `slotFrame(leftPos + slot.x - 1, topPos + slot.y - 1)`）。

> 历史坑：`tools/fix_slot_offset.py` 当年把「sprite 坐标 → Menu」统一改成了 +1
> （脚本注释「背景精灵坐标 +1（用户反馈之前 -1 是反的）」），所以**历史界面全部是 −1**；
> 第 126 轮用户反馈的 `decomposition_generator.png` 是**唯一**一个整体偏 +1 的
> （贴图槽框直接画在 Menu 坐标上），已由 Lead 重生成修复。

## 数据格式

`PAINTED`：每一张「背景里烘焙了槽框」的 PNG 一条。
  * `kind="plain"`：期望槽框 = `(x + col*px + OFFSET, y + row*py + OFFSET)`
  * `kind="stretching"`：RS 拉伸界面（9 段式），底部整块是从贴图 `src_v` 处裁下来的，
    `期望槽框 y(贴图坐标) = src_v + (menu_y + OFFSET) - (top_h + PITCH*visible_rows)`；
    `visible_rows` 由 Screen 构造器里的 `imageHeight` 反推（见 `stretch` 字段与 `why`）。
  * `kind="orphan"`：**没有任何 Screen 引用**的贴图（历史遗留副本）；判据 = 与 `mirror` 指向的
    PNG 槽框逐格相同 + 全 `src/main/java` 里零引用。一旦有人开始用它，本断言会红 → 必须改成正式登记。
  * `groups`：槽位分组。`cols/rows` = **背景上烘焙出来的**格数（滚动窗口只烘焙可见行），
    `slots` = 该组在 Menu 里的**全部**槽位数（默认 `cols*rows`，滚动窗口里比 `cols*rows` 大）。
    `painted=False` 的组 = 该组槽位不在本 PNG 里画（代码自绘 / 滚动窗口外的行）。
  * `menus`：这张 PNG 会被哪些 Menu 使用（`AdvancedCollectionCacheMenu extends CollectionCacheMenu`
    共用同一张底图，但它的区域槽是代码自绘的，所以 `painted=False`）。
  * `anchors`：`(menu 文件, 正则)` —— 源码里必须仍然能找到这段（坐标数字逐字在其中）。
    这是**防表过期**的锁：Menu 改了坐标而登记表没改 → 锚点红（而不是静静放过）。

`MENUS`：`src/main/java/**/*Menu.java` 的每一支都必须在这里出现（`bg` 决定它的槽框从哪来）。

`CODE_FRAMES`：代码自绘槽框的调用点。`menu_slot=True` 的条目**必须**带 `- 1`（x/y 各一处），
否则判负；`menu_slot=False` 的是「行内图标框 / 标记网格」等非 Menu 槽位，只做「调用点存在 + 计数」
（新出现的自绘点没登记 → 判负，提示补登记）。
"""

#: 槽框左上角相对 Menu 槽位坐标的偏移（唯一约定：−1）。
OFFSET = -1

#: 槽位间距（原版 18×18 格）。
PITCH = 18

#: 槽框模板：`pic/slot.png` 左上角 17×17（上/左各 1px 深灰 55,55,55 + 内部 139,139,139，
#: 全部不透明；17×17 之外（右/下）的颜色随界面底色变，不作判据）。
TEMPLATE = dict(png="pic/slot.png", x=0, y=0, w=17, h=17)

GUI_ROOT = "src/main/resources/assets/rs_create_compat/textures/gui"


# ======================================================================================
# 一、烘焙了槽框的背景贴图
# ======================================================================================

PAINTED = [
    dict(
        png="quantity_keeper.png",
        kind="plain",
        screen="client/screen/QuantityKeeperScreen.java",
        menus=["menu/QuantityKeeperMenu.java"],
        menu_slots=43,
        groups=[
            dict(name="标记槽", x=9, y=30, cols=1, rows=1,
                 src="menu/QuantityKeeperMenu.java:42"),
            dict(name="插件槽", x=188, y=7, cols=1, rows=6,
                 src="menu/QuantityKeeperMenu.java:46"),
            dict(name="玩家背包", x=9, y=85, cols=9, rows=3,
                 src="menu/QuantityKeeperMenu.java:59"),
            dict(name="快捷栏", x=9, y=143, cols=9, rows=1,
                 src="menu/QuantityKeeperMenu.java:63"),
        ],
        anchors=[
            ("menu/QuantityKeeperMenu.java", r"new MarkerSlot\(keeper\.getInventory\(\), 0, 9, 30\)"),
            ("menu/QuantityKeeperMenu.java", r"UpgradeSlot\.forContainer\(keeper\.getInventory\(\), 1 \+ i, 188, 7 \+ i \* 18, 1\)"),
            ("menu/QuantityKeeperMenu.java", r"9 \+ col \* 18, 85 \+ row \* 18"),
            ("menu/QuantityKeeperMenu.java", r"9 \+ col \* 18, 143\)"),
        ],
    ),
    dict(
        png="range_charger.png",
        kind="plain",
        screen="client/screen/RangeChargerScreen.java",
        menus=["menu/RangeChargerMenu.java"],
        menu_slots=42,
        groups=[
            dict(name="插件槽", x=188, y=7, cols=1, rows=6,
                 src="menu/RangeChargerMenu.java:55"),
            dict(name="玩家背包", x=9, y=85, cols=9, rows=3,
                 src="menu/RangeChargerMenu.java:61"),
            dict(name="快捷栏", x=9, y=143, cols=9, rows=1,
                 src="menu/RangeChargerMenu.java:66"),
        ],
        anchors=[
            ("menu/RangeChargerMenu.java", r"UpgradeSlot\(upgrades, i, 188, 7 \+ i \* 18,"),
            ("menu/RangeChargerMenu.java", r"9 \+ col \* 18, 85 \+ row \* 18"),
            ("menu/RangeChargerMenu.java", r"9 \+ col \* 18, 143\)"),
        ],
    ),
    dict(
        png="advanced_quantity_keeper.png",
        kind="plain",
        screen="client/screen/AdvancedQuantityKeeperScreen.java",
        menus=["menu/AdvancedQuantityKeeperMenu.java"],
        menu_slots=46,
        groups=[
            # ROW_Y = {22,46,70,94}，Menu y = ROW_Y[i]+3 ⇒ 行距 24（不是 18）
            dict(name="标记槽", x=9, y=25, cols=1, rows=4, pitch_y=24,
                 src="menu/AdvancedQuantityKeeperMenu.java:33 (ROW_Y) / :60"),
            dict(name="插件槽", x=188, y=7, cols=1, rows=6,
                 src="menu/AdvancedQuantityKeeperMenu.java:66"),
            dict(name="玩家背包", x=9, y=133, cols=9, rows=3,
                 src="menu/AdvancedQuantityKeeperMenu.java:71"),
            dict(name="快捷栏", x=9, y=190, cols=9, rows=1,
                 src="menu/AdvancedQuantityKeeperMenu.java:76"),
        ],
        anchors=[
            ("menu/AdvancedQuantityKeeperMenu.java", r"ROW_Y = \{22, 46, 70, 94\}"),
            ("menu/AdvancedQuantityKeeperMenu.java", r"new MarkerSlot\(container, i, 9, ROW_Y\[i\] \+ 3\)"),
            ("menu/AdvancedQuantityKeeperMenu.java", r"188, 7 \+ i \* 18, MARKER_SLOTS"),
            ("menu/AdvancedQuantityKeeperMenu.java", r"9 \+ col \* 18, 133 \+ row \* 18"),
            ("menu/AdvancedQuantityKeeperMenu.java", r"9 \+ col \* 18, 190\)"),
        ],
    ),
    dict(
        png="schematic_loader.png",
        kind="plain",
        screen="client/screen/SchematicLoaderScreen.java",
        menus=["menu/SchematicLoaderMenu.java"],
        menu_slots=97,
        groups=[
            dict(name="蓝图槽", x=9, y=19, cols=1, rows=1,
                 src="menu/SchematicLoaderMenu.java:87"),
            dict(name="插件槽", x=188, y=7, cols=1, rows=6,
                 src="menu/SchematicLoaderMenu.java:104"),
            dict(name="主库存", x=9, y=37, cols=9, rows=6,
                 src="menu/SchematicLoaderMenu.java:110-111"),
            dict(name="玩家背包", x=9, y=183, cols=9, rows=3,
                 src="menu/SchematicLoaderMenu.java:117"),
            dict(name="快捷栏", x=9, y=241, cols=9, rows=1,
                 src="menu/SchematicLoaderMenu.java:122"),
        ],
        anchors=[
            ("menu/SchematicLoaderMenu.java", r"new SlotItemHandler\(blueprintHandler, 0, 9, 19\)"),
            ("menu/SchematicLoaderMenu.java", r"new UpgradeSlot\(upgrades, i, 188, 7 \+ i \* 18,"),
            ("menu/SchematicLoaderMenu.java", r"9 \+ col \* 18, 37 \+ row \* 18"),
            ("menu/SchematicLoaderMenu.java", r"9 \+ col \* 18, 183 \+ row \* 18"),
            ("menu/SchematicLoaderMenu.java", r"9 \+ col \* 18, 241\)"),
        ],
    ),
    dict(
        png="advanced_schematic_loader.png",
        kind="plain",
        screen="client/screen/AdvancedSchematicLoaderScreen.java",
        menus=["menu/AdvancedSchematicLoaderMenu.java"],
        menu_slots=177,  # 6 + 27 + 108 + 27 + 9
        groups=[
            dict(name="插件槽", x=188, y=7, cols=1, rows=6,
                 src="menu/AdvancedSchematicLoaderMenu.java:113"),
            dict(name="蓝图队列", x=9, y=19, cols=9, rows=3,
                 src="menu/AdvancedSchematicLoaderMenu.java:121-123"),
            # 12 行逻辑库存，背景只烘焙可见 6 行（其余行由滚动窗口换位）
            dict(name="主库存(可见 6 行)", x=9, y=83, cols=9, rows=6, slots=108,
                 src="menu/AdvancedSchematicLoaderMenu.java:30 (STORAGE_BASE_Y=83) / :129-130"),
            dict(name="玩家背包", x=9, y=230, cols=9, rows=3,
                 src="menu/AdvancedSchematicLoaderMenu.java:136"),
            dict(name="快捷栏", x=9, y=288, cols=9, rows=1,
                 src="menu/AdvancedSchematicLoaderMenu.java:141"),
        ],
        anchors=[
            ("menu/AdvancedSchematicLoaderMenu.java", r"STORAGE_BASE_Y = 83"),
            ("menu/AdvancedSchematicLoaderMenu.java", r"new UpgradeSlot\(upgrades, i, 188, 7 \+ i \* 18,"),
            ("menu/AdvancedSchematicLoaderMenu.java", r"9 \+ col \* 18, 19 \+ row \* 18"),
            ("menu/AdvancedSchematicLoaderMenu.java", r"STORAGE_BASE_Y \+ row \* ROW_SIZE"),
            ("menu/AdvancedSchematicLoaderMenu.java", r"9 \+ col \* 18, 230 \+ row \* 18"),
            ("menu/AdvancedSchematicLoaderMenu.java", r"9 \+ col \* 18, 288\)"),
        ],
    ),
    dict(
        png="collection_cache.png",
        kind="plain",
        screen="client/screen/CollectionCacheScreen.java",
        menus=["menu/CollectionCacheMenu.java", "menu/AdvancedCollectionCacheMenu.java"],
        menu_slots=138,
        groups=[
            dict(name="匹配区窗口", x=9, y=33, cols=12, rows=4,
                 src="menu/CollectionCacheMenu.java:74-75 (MATCH_X0/MATCH_Y0) / :313-315"),
            dict(name="缓存区窗口", x=9, y=119, cols=12, rows=4,
                 src="menu/CollectionCacheMenu.java:77-78 (CACHE_X0/CACHE_Y0) / :320-322"),
            dict(name="升级槽", x=76, y=219, cols=6, rows=1,
                 src="menu/CollectionCacheMenu.java:80-81 (UPGRADE_X0/Y0) / :327-329"),
            dict(name="玩家背包", x=43, y=242, cols=9, rows=3,
                 src="menu/CollectionCacheMenu.java:83-84 (INV_X0/INV_Y0)"),
            dict(name="快捷栏", x=43, y=302, cols=9, rows=1,
                 src="menu/CollectionCacheMenu.java:86 (HOTBAR_Y=302)"),
        ],
        anchors=[
            ("menu/CollectionCacheMenu.java", r"MATCH_X0 = 9"),
            ("menu/CollectionCacheMenu.java", r"MATCH_Y0 = 33"),
            ("menu/CollectionCacheMenu.java", r"CACHE_Y0 = 119"),
            ("menu/CollectionCacheMenu.java", r"UPGRADE_X0 = 76"),
            ("menu/CollectionCacheMenu.java", r"UPGRADE_Y0 = 219"),
            ("menu/CollectionCacheMenu.java", r"INV_X0 = 43"),
            ("menu/CollectionCacheMenu.java", r"INV_Y0 = 242"),
            ("menu/CollectionCacheMenu.java", r"HOTBAR_Y = 302"),
        ],
    ),
    dict(
        png="decomposition_generator.png",
        kind="plain",
        screen="client/screen/DecompositionGeneratorScreen.java",
        menus=["menu/DecompositionGeneratorMenu.java"],
        menu_slots=62,  # = DecompositionGeneratorSpec.TOTAL_SLOT_COUNT
        groups=[
            dict(name="输入槽 4×4", x=8, y=18, cols=4, rows=4,
                 src="support/DecompositionGeneratorSpec.java:109-111 (INPUT_X/Y)"),
            dict(name="输出槽 2×2", x=118, y=37, cols=2, rows=2,
                 src="support/DecompositionGeneratorSpec.java:123-125 (OUTPUT_X/Y)"),
            dict(name="升级槽 3×2", x=118, y=76, cols=3, rows=2,
                 src="support/DecompositionGeneratorSpec.java:135-137 (UPGRADE_X/Y)"),
            dict(name="玩家背包", x=8, y=128, cols=9, rows=3,
                 src="support/DecompositionGeneratorSpec.java:164-166 (PLAYER_INV_X/Y)"),
            dict(name="快捷栏", x=8, y=188, cols=9, rows=1,
                 src="support/DecompositionGeneratorSpec.java:168 (HOTBAR_Y)"),
        ],
        anchors=[
            ("support/DecompositionGeneratorSpec.java", r"INPUT_X = 8"),
            ("support/DecompositionGeneratorSpec.java", r"INPUT_Y = 18"),
            ("support/DecompositionGeneratorSpec.java", r"OUTPUT_X = 118"),
            ("support/DecompositionGeneratorSpec.java", r"OUTPUT_Y = 37"),
            ("support/DecompositionGeneratorSpec.java", r"UPGRADE_X = 118"),
            ("support/DecompositionGeneratorSpec.java", r"UPGRADE_Y = 76"),
            ("support/DecompositionGeneratorSpec.java", r"PLAYER_INV_X = 8"),
            ("support/DecompositionGeneratorSpec.java", r"PLAYER_INV_Y = 128"),
            ("support/DecompositionGeneratorSpec.java", r"HOTBAR_Y = 188"),
            ("menu/DecompositionGeneratorMenu.java", r"slotX\(DecompositionGeneratorSpec\.INPUT_X, index, DecompositionGeneratorSpec\.INPUT_COLS\)"),
            ("menu/DecompositionGeneratorMenu.java", r"DecompositionGeneratorSpec\.PLAYER_INV_Y \+ row \* DecompositionGeneratorSpec\.SLOT_PITCH"),
        ],
    ),
    dict(
        # 第 128 轮：原先 renderBg 用 McGui.panel + 逐槽 slotFrame 现画（没有背景贴图），
        # 现在由 tools/gen_round128_gui_bg.py（内部调 make_gui_bg.draw_container）生成。
        png="intermediate_cache.png",
        kind="plain",
        screen="client/screen/IntermediateCacheScreen.java",
        menus=["menu/IntermediateCacheMenu.java"],
        menu_slots=63,  # 27 盘位 + 36 玩家背包/快捷栏
        groups=[
            dict(name="盘位 3×9", x=8, y=36, cols=9, rows=3,
                 src="menu/IntermediateCacheMenu.java:76-77 (DISK_X0/DISK_Y0)"),
            dict(name="玩家背包", x=8, y=116, cols=9, rows=3,
                 src="menu/IntermediateCacheMenu.java:81-82 (INV_X0/INV_Y0)"),
            dict(name="快捷栏", x=8, y=174, cols=9, rows=1,
                 src="menu/IntermediateCacheMenu.java:86 (HOTBAR_Y)"),
        ],
        anchors=[
            ("menu/IntermediateCacheMenu.java", r"DISK_X0 = 8"),
            ("menu/IntermediateCacheMenu.java", r"DISK_Y0 = 36"),
            ("menu/IntermediateCacheMenu.java", r"DISK_X0 \+ \(i % DISK_COLS\) \* 18, DISK_Y0 \+ \(i / DISK_COLS\) \* 18"),
            ("menu/IntermediateCacheMenu.java", r"INV_X0 \+ col \* 18, INV_Y0 \+ row \* 18"),
            ("menu/IntermediateCacheMenu.java", r"INV_X0 \+ col \* 18, HOTBAR_Y"),
        ],
    ),
    # 第 132 轮：接收器界面改成 RS 的 GridScreen（资源网格 / 滚动条 / 数量角标全由 RS 画），
    # 不再有自绘 PNG 与手写槽位账；network_resource_receiver.png 已无界面引用，故从登记表移除。
    # 第 131 轮：请求器「设置界面」（NetworkResourceRequesterScreen）按用户要求整页删除，
    # 6 个升级槽也一并删除（用户：红石激活速度决定发送速度，跟插件槽没关系）。
    # 因此这里不再登记 network_resource_requester.png 的槽位账（那张底图已无界面引用）。
    dict(
        # 第 128 轮：原来直接用原版 generic_54.png（176×222），现在工具生成同尺寸底图。
        png="sequence_assembly_executor.png",
        kind="plain",
        screen="client/screen/SequenceAssemblyExecutorScreen.java",
        menus=["menu/SequenceAssemblyExecutorMenu.java"],
        menu_slots=90,  # 54 总样板 + 36 玩家背包/快捷栏
        groups=[
            dict(name="总样板 9×6", x=8, y=18, cols=9, rows=6,
                 src="menu/SequenceAssemblyExecutorMenu.java:65"),
            dict(name="玩家背包", x=8, y=140, cols=9, rows=3,
                 src="menu/SequenceAssemblyExecutorMenu.java:76-77 (104 + (rows-4)*18)"),
            dict(name="快捷栏", x=8, y=198, cols=9, rows=1,
                 src="menu/SequenceAssemblyExecutorMenu.java:81 (162 + (rows-4)*18)"),
        ],
        anchors=[
            ("menu/SequenceAssemblyExecutorMenu.java", r"PATTERN_ROWS"),
            ("menu/SequenceAssemblyExecutorMenu.java", r"8 \+ col \* 18, 18 \+ row \* 18"),
            ("menu/SequenceAssemblyExecutorMenu.java", r"8 \+ col \* 18, 104 \+ row \* 18 \+ i"),
            ("menu/SequenceAssemblyExecutorMenu.java", r"8 \+ col \* 18, 162 \+ i"),
        ],
    ),
    dict(
        # 第 128 轮：原来直接用原版 generic_54.png（176×222），现在工具生成同尺寸底图。
        png="sequence_execution_chamber.png",
        kind="plain",
        screen="client/screen/SequenceExecutionChamberScreen.java",
        menus=["menu/SequenceExecutionChamberMenu.java"],
        menu_slots=90,  # 54 单元样板 + 36 玩家背包/快捷栏
        groups=[
            dict(name="单元样板 9×6", x=8, y=18, cols=9, rows=6,
                 src="menu/SequenceExecutionChamberMenu.java:45"),
            dict(name="玩家背包", x=8, y=140, cols=9, rows=3,
                 src="menu/SequenceExecutionChamberMenu.java:55 (104 + (rows-4)*18)"),
            dict(name="快捷栏", x=8, y=198, cols=9, rows=1,
                 src="menu/SequenceExecutionChamberMenu.java:60 (162 + (rows-4)*18)"),
        ],
        anchors=[
            ("menu/SequenceExecutionChamberMenu.java", r"UNIT_ROWS"),
            ("menu/SequenceExecutionChamberMenu.java", r"8 \+ col \* 18, 18 \+ row \* 18"),
            ("menu/SequenceExecutionChamberMenu.java", r"8 \+ col \* 18, 104 \+ row \* 18 \+ i"),
            ("menu/SequenceExecutionChamberMenu.java", r"8 \+ col \* 18, 162 \+ i"),
        ],
    ),
    dict(
        png="inventory_proxy.png",
        kind="plain",
        screen="client/screen/InventoryProxyScreen.java",
        menus=["menu/InventoryProxyMenu.java"],
        menu_slots=37,
        groups=[
            dict(name="代理对象槽", x=10, y=22, cols=1, rows=1,
                 src="menu/InventoryProxyMenu.java:69"),
            dict(name="玩家背包", x=26, y=136, cols=9, rows=3,
                 src="menu/InventoryProxyMenu.java:75"),
            dict(name="快捷栏", x=26, y=196, cols=9, rows=1,
                 src="menu/InventoryProxyMenu.java:81"),
        ],
        anchors=[
            ("menu/InventoryProxyMenu.java", r"new ProxySlot\(objectView, 0, 10, 22\)"),
            ("menu/InventoryProxyMenu.java", r"26 \+ col \* 18, 136 \+ row \* 18"),
            ("menu/InventoryProxyMenu.java", r"26 \+ col \* 18, 196\)"),
        ],
    ),
    dict(
        png="sequence_pattern_terminal.png",
        kind="plain",
        screen="client/screen/SequencePatternTerminalScreen.java",
        menus=["menu/SequencePatternTerminalMenu.java"],
        menu_slots=79,  # = SLOT_COUNT（1+8+12+12+6+27+9+1+3）
        groups=[
            dict(name="输入原料标记槽", x=184, y=33, cols=1, rows=1,
                 src="menu/SequencePatternTerminalMenu.java:191-192 / :281"),
            dict(name="产物区(可见 4×3)", x=166, y=66, cols=4, rows=3,
                 src="menu/SequencePatternTerminalMenu.java:193-194 / :288-290"),
            dict(name="废料区(可见 4×3)", x=166, y=131, cols=4, rows=3,
                 src="menu/SequencePatternTerminalMenu.java:195-196 / :292-294"),
            dict(name="单元样板槽 6 格", x=123, y=213, cols=6, rows=1,
                 src="menu/SequencePatternTerminalMenu.java:219-220 / :316"),
            dict(name="玩家背包", x=49, y=247, cols=9, rows=3,
                 src="menu/SequencePatternTerminalMenu.java:221-222 / :321-322"),
            dict(name="快捷栏", x=49, y=303, cols=9, rows=1,
                 src="menu/SequencePatternTerminalMenu.java:223-224 / :326"),
            dict(name="总样板槽", x=37, y=213, cols=1, rows=1,
                 src="menu/SequencePatternTerminalMenu.java:209-210 / :331"),
            # 这两组**不烘焙在背景里**（行槽框/3 格样板槽框由 Screen 运行时自绘）
            dict(name="流程编排窗口(自绘)", x=9, y=33, cols=1, rows=8, slots=8, painted=False,
                 src="menu/SequencePatternTerminalMenu.java:188-189 / :282-286"),
            dict(name="RS 样板槽(自绘)", x=70, y=191, cols=3, rows=1, painted=False,
                 src="menu/SequencePatternTerminalMenu.java:217-218 / :343-345"),
        ],
        anchors=[
            ("menu/SequencePatternTerminalMenu.java", r"SLOT_INPUT_X = 184"),
            ("menu/SequencePatternTerminalMenu.java", r"OP_SLOT_Y = 33"),
            ("menu/SequencePatternTerminalMenu.java", r"RESULT_SLOT_X = 166"),
            ("menu/SequencePatternTerminalMenu.java", r"RESULT_SLOT_Y = 66"),
            ("menu/SequencePatternTerminalMenu.java", r"SCRAP_SLOT_Y = 131"),
            ("menu/SequencePatternTerminalMenu.java", r"UNIT_SLOT_X = 123"),
            ("menu/SequencePatternTerminalMenu.java", r"UNIT_SLOT_Y = 213"),
            ("menu/SequencePatternTerminalMenu.java", r"PLAYER_SLOT_X = 49"),
            ("menu/SequencePatternTerminalMenu.java", r"PLAYER_SLOT_Y = 247"),
            ("menu/SequencePatternTerminalMenu.java", r"HOTBAR_SLOT_Y = 303"),
            ("menu/SequencePatternTerminalMenu.java", r"PATTERN_SLOT_X = 37"),
            ("menu/SequencePatternTerminalMenu.java", r"PATTERN_SLOT_Y = 213"),
            ("menu/SequencePatternTerminalMenu.java", r"\(i % 4\) \* 18"),
        ],
    ),
    dict(
        png="unit_pattern_manager.png",
        kind="stretching",
        screen="client/screen/UnitPatternManagerScreen.java",
        menus=["menu/UnitPatternManagerMenu.java"],
        # 分组槽位是动态的（按搜索/可见行重排，被隐藏的挪到 HIDDEN_SLOT_Y=-1000），无法静态枚举
        menu_slots=None,
        why=("分组槽位由 `rebuildSlots()` 按「可见分组 × 行数 × 搜索过滤」动态摆放"
             "（隐藏槽挪到 HIDDEN_SLOT_Y=-1000），没有静态坐标；"
             "本 PNG 只烘焙了**玩家背包 + 快捷栏** 36 格，分组槽框由 Screen 用 "
             "`blitSprite(Sprites.SLOT, ...)` 运行时自绘（见 CODE_FRAMES）。"),
        stretch=dict(
            # Screen 构造器：this.imageHeight = 176（只用于反推 visibleRows）
            paint_image_h=176,
            top_h=19,          # RS AbstractStretchingScreen.TOP_HEIGHT
            bottom_h=99,       # Screen#getBottomHeight() = 99
            src_v=73,          # Screen#getBottomV() = 73
            visible_rows=3,    # (176 - 19 - 99) / 18
        ),
        groups=[
            dict(name="玩家背包", x=8, y=90, cols=9, rows=3,
                 src="menu/UnitPatternManagerMenu.java:73 (PLAYER_INV_X=8)；"
                     "y = 实际面板高(19+18*3+99=172) - 99 + 17 = 90（RS AbstractStretchingScreen#resize）"),
            dict(name="快捷栏", x=8, y=148, cols=9, rows=1,
                 src="同上：hotbar = playerInventoryY + 58 = 148"),
        ],
        anchors=[
            ("client/screen/UnitPatternManagerScreen.java", r"this\.imageHeight = 176"),
            ("client/screen/UnitPatternManagerScreen.java", r"return 99;"),
            ("client/screen/UnitPatternManagerScreen.java", r"return 73;"),
            ("menu/UnitPatternManagerMenu.java", r"PLAYER_INV_X = 8"),
        ],
    ),
    # 第 128 轮：原历史副本 sequence_assembly_executor.png 已转正为 SequenceAssemblyExecutorScreen
    # 的背景（见上方 plain 条目），本仓库当前没有任何孤儿贴图。
]


# ======================================================================================
# 二、Menu 覆盖表：`src/main/java/**/*Menu.java` 必须**逐支**出现
# ======================================================================================

MENUS = [
    dict(file="menu/AdvancedCollectionCacheMenu.java",
         screen="client/screen/AdvancedCollectionCacheScreen.java",
         bg="png", png="collection_cache.png", slots=139,
         note="继承 CollectionCacheMenu 的 138 格（底图烘焙）+ 1 个区域槽（代码自绘，AREA_SLOT_X/Y=10/219）"),
    dict(file="menu/AdvancedQuantityKeeperMenu.java",
         screen="client/screen/AdvancedQuantityKeeperScreen.java",
         bg="png", png="advanced_quantity_keeper.png", slots=46),
    dict(file="menu/AdvancedSchematicLoaderMenu.java",
         screen="client/screen/AdvancedSchematicLoaderScreen.java",
         bg="png", png="advanced_schematic_loader.png", slots=177),
    dict(file="menu/ChamberUnitsSummaryMenu.java",
         screen="client/screen/ChamberUnitsSummaryScreen.java",
         bg="code", png=None, slots=None,
         note="执行舱窗口槽数随执行舱数量/滚动动态变化；槽框由 Screen 逐个 menu.slots 自绘（−1）"),
    dict(file="menu/CollectionCacheMenu.java",
         screen="client/screen/CollectionCacheScreen.java",
         bg="png", png="collection_cache.png", slots=138),
    dict(file="menu/DecompositionGeneratorMenu.java",
         screen="client/screen/DecompositionGeneratorScreen.java",
         bg="png", png="decomposition_generator.png", slots=62),
    dict(file="menu/FEEnergyCubeMenu.java",
         screen="client/screen/FEEnergyCubeScreen.java",
         bg="none", png=None, slots=0,
         note="故意不 addSlot：立方没有库存/升级槽、也不显示玩家背包（Menu 源码里有这行注释）"),
    dict(file="menu/NetworkResourceRequesterMenu.java",
         screen=None,
         bg="none", png=None, slots=None,
         note="第 131 轮：设置界面整页删除、6 个升级槽删除后，这个类只剩「菜单壳」（玩家背包槽 + stillValid），没有任何界面会打开它；登记 none 只为覆盖 Menu 清单"),
    dict(file="menu/IntermediateCacheMenu.java",
         screen="client/screen/IntermediateCacheScreen.java",
         bg="png", png="intermediate_cache.png", slots=63,
         note="第 128 轮工具化：27 盘位 + 36 玩家背包/快捷栏全部烘焙进底图"),
    dict(file="menu/InventoryProxyMenu.java",
         screen="client/screen/InventoryProxyScreen.java",
         bg="png", png="inventory_proxy.png", slots=37),
    dict(file="menu/NetworkResourceReceiverMenu.java",
         screen="client/screen/NetworkResourceReceiverScreen.java",
         bg="rs_framework", slots=42,
         note="第 132 轮：界面继承 RS 的 GridScreen（背景 = refinedstorage:grid.png，"
              "资源格 / 滚动条 / 搜索框全由 RS 画）。extras 面板的 6 个升级槽 + 玩家 36 槽"
              "由玩家背包槽位组成，坐标是客户端运行时按面板位置生成的，不再有自绘 PNG。"),
    # 第 131 轮：请求器设置界面已删除，瘦菜单没有任何界面，因此没有槽位账。
    dict(file="menu/QuantityKeeperMenu.java",
         screen="client/screen/QuantityKeeperScreen.java",
         bg="png", png="quantity_keeper.png", slots=43),
    dict(file="menu/RangeChargerMenu.java",
         screen="client/screen/RangeChargerScreen.java",
         bg="png", png="range_charger.png", slots=42),
    dict(file="menu/SchematicLoaderMenu.java",
         screen="client/screen/SchematicLoaderScreen.java",
         bg="png", png="schematic_loader.png", slots=97),
    dict(file="menu/SequenceAssemblyExecutorMenu.java",
         screen="client/screen/SequenceAssemblyExecutorScreen.java",
         bg="png", png="sequence_assembly_executor.png", slots=90,
         note="第 128 轮工具化：176×222 底图（54 总样板 + 36 玩家背包/快捷栏烘焙），\n"
              "不再用原版 generic_54.png"),
    dict(file="menu/SequenceExecutionChamberMenu.java",
         screen="client/screen/SequenceExecutionChamberScreen.java",
         bg="png", png="sequence_execution_chamber.png", slots=90,
         note="第 128 轮工具化：176×222 底图（54 单元样板 + 36 玩家背包/快捷栏烘焙），\n"
              "不再用原版 generic_54.png"),
    dict(file="menu/SequencePatternTerminalMenu.java",
         screen="client/screen/SequencePatternTerminalScreen.java",
         bg="png", png="sequence_pattern_terminal.png", slots=79),
    dict(file="menu/UnitPatternManagerMenu.java",
         screen="client/screen/UnitPatternManagerScreen.java",
         bg="png", png="unit_pattern_manager.png", slots=None,
         note="分组槽位动态重排（见 PAINTED 里该条目的 why）；玩家背包/快捷栏烘焙在底图"),
    dict(file="client/grid/ResourcePickerMenu.java",
         screen="client/grid/ResourcePickerScreen.java",
         bg="rs_framework", png=None, slots=None,
         note="继承 RS 的 AbstractGridContainerMenu，槽位框与网格由 RS 框架画（本仓库不画），\n"
              "因此不纳入本自检的像素判据"),
]


# ======================================================================================
# 三、代码自绘槽框（McGui.slotFrame / blitSprite(Sprites.SLOT, ...)）
#     menu_slot=True ⇒ 必须 x/y 各带 `- 1`（与 Menu 槽位同源的 −1 约定）
# ======================================================================================

CODE_FRAMES = [
    # ---- 与 Menu 槽位同源：必须 −1 ----
    dict(file="client/screen/AdvancedQuantityKeeperScreen.java", api="slotFrame", count=1, menu_slot=True,
         pattern=r"McGui\.slotFrame\(guiGraphics, leftPos \+ slot\.x - 1, topPos \+ slot\.y - 1\)",
         note="逐 menu.slots 画"),
    dict(file="client/screen/ChamberUnitsSummaryScreen.java", api="slotFrame", count=1, menu_slot=True,
         pattern=r"McGui\.slotFrame\(guiGraphics, leftPos \+ slot\.x - 1, topPos \+ slot\.y - 1\)",
         note="逐 menu.slots 画"),
    dict(file="client/screen/AdvancedCollectionCacheScreen.java", api="slotFrame", count=1, menu_slot=True,
         pattern=r"frameX = leftPos \+ AdvancedCollectionCacheMenu\.AREA_SLOT_X - 1",
         extra=[r"frameY = topPos \+ AdvancedCollectionCacheMenu\.AREA_SLOT_Y - 1",
                r"McGui\.slotFrame\(guiGraphics, frameX, frameY\)"],
         note="区域槽（AREA_SLOT_X/Y=10/219）单格：x/y 都先 −1 再画"),
    # SequencePatternTerminalScreen：3 格 RS 样板槽框（背景没烘焙这块，删「导入」按钮后腾出的位置）
    dict(file="client/screen/SequencePatternTerminalScreen.java", api="slotFrame", count=1, menu_slot=True,
         pattern=r"leftPos \+ SequencePatternTerminalMenu\.RS_PATTERN_SLOT_X \+ i \* 18 - 1,",
         extra=[r"topPos \+ SequencePatternTerminalMenu\.RS_PATTERN_SLOT_Y - 1\)",
                r"McGui\.slotFrame\(guiGraphics,"],
         note="3 格 → RS_PATTERN_SLOT_X/Y(70/191)"),
    # UnitPatternManagerScreen：分组槽框（动态）用原版 slot 精灵自绘
    dict(file="client/screen/UnitPatternManagerScreen.java", api="slotSprite", count=1, menu_slot=True,
         pattern=r"blitSprite\(Sprites\.SLOT, slotX, slotY, 18, 18\)",
         extra=[r"BAND_X = UnitPatternManagerMenu\.GROUP_SLOT_X - 1",
                r"final int slotY = rowY \+ ROW_SIZE \+ \(\(i / UnitPatternManagerMenu\.COLUMNS\) \* ROW_SIZE\)"],
         note="x 已 −1（BAND_X）；y 的 −1 由 RS 框架的 topYStart=TOP_HEIGHT+1 给出（见 docs §5.4）"),
    # ---- 非 Menu 槽位（行内图标框 / 标记网格 / 幽灵物品格）：只登记调用点，不要求 −1 ----
    dict(file="client/screen/AreaMarkScreen.java", api="slotFrame", count=2, menu_slot=False,
         pattern=r"McGui\.slotFrame\(guiGraphics, x - 1, y - 1\)",
         note="标记网格 / 幽灵格（不是 Menu 槽位，槽位在自定义界面里手排）"),
    dict(file="client/screen/BusCategoryConfigScreen.java", api="slotFrame", count=2, menu_slot=False,
         pattern=r"McGui\.slotFrame\(guiGraphics, left \+ ROW_ICON_X - 1, y\)",
         note="列表行图标外框（17×17 素材观感，不是 Menu 槽位）"),
    dict(file="client/screen/ChargerFilterScreen.java", api="slotFrame", count=4, menu_slot=False,
         pattern=r"McGui\.slotFrame\(",
         note="列表行图标外框 4 处（不是 Menu 槽位）"),
    # 第 132 轮：接收器 extras 面板里的 6 个升级槽（2×3）是真实 Menu 槽位；
    # 面板位置在客户端运行时计算，因此槽框也必须运行时画（x/y 都先 −1 再交给 slotFrame）。
    dict(file="client/screen/NetworkResourceReceiverScreen.java", api="slotFrame", count=1, menu_slot=True,
         pattern=r"McGui\.slotFrame\(guiGraphics, slotX - 1, slotY - 1\)",
         extra=[r"final int slotX = panelX \+ PANEL_PAD",
                r"final int slotY = panelY \+ NetworkResourceReceiverMenu\.UPGRADE_Y0"],
         note="6 个升级槽（i % 2 / i / 2 网格）由 panelX/panelY 运行时定位；槽框 = 内容坐标 − 1"),
]


# ======================================================================================
# 四、必须**零引用**的贴图（orphan）
#     （第 128 轮起 sequence_assembly_executor.png 已被 Screen 正式引用 → 移出本表）
# ======================================================================================

ORPHAN = [
    # 第 131 轮：请求器「设置界面」按用户要求整页删除（NetworkResourceRequesterScreen 已删），
    # 这张 210×238 的底图因此零引用；图本身保留（历史美术资产），但登记成 ORPHAN =
    # 「源码里不许再引用它」—— 将来谁把它接回新界面，这条就会判负，逼他改成正式登记。
    dict(png="network_resource_requester.png",
         why="第 131 轮：请求器设置界面整页删除（用户：这界面没有任何作用）⇒ 零引用"),
    # 第 132 轮：接收器界面改成 RS 的 GridScreen（背景 = RS 的 grid.png），
    # 第 128 轮生成的 236×252 自绘底图因此零引用；保留为历史美术资产，但禁止再被源码引用。
    dict(png="network_resource_receiver.png",
         why="第 132 轮：接收器界面改成 RS GridScreen ⇒ 自绘底图零引用"),
]


# ======================================================================================
# 五、`textures/gui/**` 下**没有**槽框的贴图（必须逐张登记，不许静默跳过）
#     `frames` = 期望检测到的 17×17 槽框个数（0 = 这张图不该有槽框）
# ======================================================================================

DECORATIVE = [
    dict(png="advanced_remote_terminal.png", frames=0,
         why="176×120 的终端外壳图，零引用（终端界面走 RS 的 grid 屏，不用本图）"),
    dict(png="chamber_binding_config.png", frames=0,
         why="绑定配置界面：无槽位，纯文字/按钮"),
    dict(png="chamber_units_summary.png", frames=0,
         why="执行舱汇总：有 Menu 槽位（窗口槽），但槽框全部由 Screen 运行时自绘（CODE_FRAMES），底图不烘焙"),
    dict(png="fe_energy_cube.png", frames=0,
         why="能量立方：Menu 故意 0 个 addSlot；第 128 轮起底图由 tools/make_gui_bg.py 生成"
             "（176×166 纯容器底，无槽框），电量条仍由 GuiGraphics 画"),
    dict(png="advancements/backgrounds/advancements.png", frames=0,
         why="进度界面底纹 16×16，非容器界面"),
    dict(png="collection_cache/marker_config.png", frames=0,
         why="标记配置子界面：无槽位（区域标记在主界面/区域条上编辑）"),
    dict(png="collection_cache/range_config.png", frames=0,
         why="范围配置子界面：无槽位"),
    dict(png="spt/result_config.png", frames=0,
         why="序列样板终端·产物配置子界面：无槽位（只读列表）"),
    dict(png="spt/step_detail_config.png", frames=0,
         why="序列样板终端·步骤详情子界面：无槽位"),
    dict(png="spt/spt_btn_delete.png", frames=0, why="按钮精灵"),
    dict(png="spt/spt_btn_large.png", frames=0, why="按钮精灵"),
    dict(png="spt/spt_btn_small.png", frames=0, why="按钮精灵"),
    dict(png="spt/spt_card_bg.png", frames=0, why="卡片九段底线"),
    dict(png="spt/spt_scrollbar_thumb.png", frames=0, why="滚动条滑块精灵"),
    dict(png="spt/spt_tabs.png", frames=0, why="页签精灵"),
]
