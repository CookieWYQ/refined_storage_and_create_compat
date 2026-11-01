# 第 126 轮：GUI 槽位 ↔ 背景槽框「对位」自检

> 用户原话（本轮）：**「界面里有一个问题：槽位和背景存在错误的偏差。你可以浏览一下以往的那些 GUI 界面，
> 看看他们的偏差是正一还是负一，我忘了。然后确定好之后把它添加到自检脚本中，以后添加的所有 GUI 界面、
> 带槽位的都必须自检一遍。」**

## 0. 结论（TL;DR）

| 问题 | 结论 |
|---|---|
| 历史界面的偏差是正一还是负一？ | **负一**：PNG 里 17×17 槽框左上角 = **Menu 槽位坐标 − 1**（`OFFSET = -1`）。**不是 +1** |
| 当前仓库有没有偏差？ | **0 处**。11 张「烘焙了槽框」的 PNG、735 个槽框，逐格对位全对（见 §3） |
| 用户反馈的 `decomposition_generator.png` | Lead 已在本轮重生成（mtime `11-01 00:05`），复跑后 **62 格全对**；旧图的表现是「整体偏 (+1,+1)」，本脚本能精确报出来（§8-C1） |
| 自检脚本 | `tools/selfcheck_gui_slot_alignment.py`（**117 条断言** = 110 条正判 + 7 条反例）；登记表 `tools/gui_slot_registry.py`；单跑 **1.1s**、退出码 0/1、`ALL_PASS` 收尾 |
| 会不会「以后新增的 GUI 漏检」 | 会挡住：**任何 PNG 出现槽框但没登记 / 任何 `*Menu.java` 没登记 / 任何新增的代码自绘槽框** 都直接 FAIL，文案就是「新界面必须登记并自检」 |

---

## 1. 约定来源（为什么不可能是 +1）

### 1.1 生成工具（权威约定原文）

`tools/make_gui_gui.py`（图形版编辑器）里四处写着同一件事：

| 行号 | 原文 |
|---|---|
| 21 | `「生成 Java 控件代码」输出槽位/按钮/文本对应的代码（精灵坐标 +1 = Menu 坐标）` |
| 905 | `槽位（精灵坐标）x=%d y=%d，Menu 坐标 x=%d y=%d` |
| 1365 | `精灵坐标 → Menu/控件坐标 = 精灵 + 1` |
| 1495 | `> 坐标说明：精灵坐标 +1 = 游戏中 Menu / 控件坐标` |

⇒ 反过来就是本轮钉死的约定：**贴图精灵坐标 = Menu 坐标 − 1**。

### 1.2 生成工具（第 126 轮新写的 `--slots-menu`，杜绝手算）

`tools/make_gui_bg.py` 第 26–48 行（本轮新增/改写，另见第 61 行 `精灵坐标 = Menu 坐标 - SLOT_INSET`）：

```
坐标语义（**第 126 轮钉死，写错就是「槽位与背景差 1px」**）：
    MC 的 Slot.x / Slot.y 指的是**物品图标**的左上角；18×18 的槽框背景
    左上角在 (Slot.x - 1, Slot.y - 1)，而本工装贴的 17×17 slot.png
    正是那个「槽框」——所以 **PNG 精灵坐标 = Menu 坐标 - 1**。

因此有两种传参方式，**新界面一律用 --slots-menu**：
    --slots      参数是**精灵坐标**（历史用法，贴图原样落位）；
    --slots-menu 参数是**Menu / Spec 坐标**（本工具自动 -1），
                 与 XxxSpec 里给 addSlot 的数字**逐字相同**，杜绝「手算 -1 算反」这类事故。

    # 降解室发电机（Menu 坐标与 DecompositionGeneratorSpec 逐字一致）
    python make_gui_bg.py --out ..\decomposition_generator.png --width 182 --height 212 \
        --slots-menu "8,18,4,4" "118,37,2,2" "118,76,3,2" "8,128,9,3" "8,188,9,1"
```

> 这条命令里的 5 组 Menu 坐标，与本轮登记表 `decomposition_generator.png` 的 5 个分组的
> `x,y,cols,rows` **逐字相同**（8,18,4,4 / 118,37,2,2 / 118,76,3,2 / 8,128,9,3 / 8,188,9,1）——
> 两处独立来源互相印证，说明登记表不是「照着 PNG 反推」出来的。

### 1.3 Runtime 侧同一条约定

* 原版 `AbstractContainerScreen#renderSlot` 画槽位都是用 `leftPos + slot.x - 1`；
* 本仓库代码自绘槽框的地方全部照此写（`tools/selfcheck_gui_slot_alignment.py` 把它们逐处锁死）：

```java
// IntermediateCacheScreen / NetworkResourceReceiverScreen / NetworkResourceRequesterScreen /
// AdvancedQuantityKeeperScreen / ChamberUnitsSummaryScreen —— 五处同款：
for (final Slot slot : menu.slots) {
    McGui.slotFrame(guiGraphics, leftPos + slot.x - 1, topPos + slot.y - 1);
}
```

### 1.4 实证抽样（本轮复算，全部符合 Menu−1）

| 贴图 | 贴图槽框 | Menu | Menu 侧 `addSlot` 坐标 | 结论 |
|---|---|---|---|---|
| `range_charger.png` | (8,84) | `RangeChargerMenu:61` | `9 + col*18, 85 + row*18` | ✔ −1 |
| `quantity_keeper.png` | (8,84) | `QuantityKeeperMenu:59` | `9 + col*18, 85 + row*18` | ✔ −1 |
| `schematic_loader.png` | (8,18) | `SchematicLoaderMenu:87` | `new SlotItemHandler(blueprintHandler, 0, 9, 19)` | ✔ −1 |
| `inventory_proxy.png` | (9,21) | `InventoryProxyMenu:69` | `new ProxySlot(objectView, 0, 10, 22)` | ✔ −1 |
| `collection_cache.png` | (8,32) | `CollectionCacheMenu:74-75` | `MATCH_X0=9 / MATCH_Y0=33` | ✔ −1 |
| `sequence_pattern_terminal.png` | (122,212) | `SequencePatternTerminalMenu:219` | `UNIT_SLOT_X=123, UNIT_SLOT_Y=213` | ✔ −1 |
| `decomposition_generator.png` | (7,17) | `DecompositionGeneratorSpec:109-111` | `INPUT_X=8, INPUT_Y=18` | ✔ −1（本轮修好） |

> 历史坑（别被它带跑）：`tools/fix_slot_offset.py` 当年把一批 Menu 坐标从「精灵坐标」改成
> 「精灵 +1」（脚本注释：「背景精灵坐标 +1（用户反馈之前 -1 是反的）」）。
> 也就是说 **Menu 坐标比精灵坐标大 1**，所以 PNG 永远比 Menu 小 1 —— 与上面的结论一致。

---

## 2. 检测方法（PNG 侧，零手抄）

1. **模板**：`pic/slot.png` 取 `(0,0)` 起 `17×17`。模板只有 2 种颜色、全部不透明：
   上/左各 1px `(55,55,55)` 边框 + 内部 `(139,139,139)` 填充。
   17×17 之外的右/下颜色随界面底色变化（198 / 155 / 120 …），**不作判据**。
2. **匹配**：不用 `array_equal` 暴力扫（26 张图会慢到几秒），改成「三条矩形全命中」等价判据 +
   积分图（`numpy` 累加和），一次算完：
   * 顶行 `1×17` 全 = 边框色；左列 `17×1` 全 = 边框色；内部 `16×16` 全 = 填充色。
   26 张 PNG 全跑 **< 0.2s**（整支脚本 1.1s）。
3. **模板结构自校验**：模板必须真的是「上/左同色 + 内部同色」，否则直接判负
   （`template_signature()`）——这样「模板被改坏 / 取错坐标」不会静默变成「0 个槽框，全过」。
4. **期望值**：由登记表（Menu 坐标）展开成 18px 栅格，再按 `OFFSET=-1` 平移；
   与检出集合**精确相等**（缺一个/多一个/整体偏都判负，并打印「整体偏 (+dx,+dy)」提示）。

---

## 3. 全仓库矩阵（当前状态：**偏差 0 处**）

### 3.1 有烘焙槽框的背景贴图（11 张 / 735 个槽框）

| # | PNG | 对应 Menu | 槽数(Menu) | 槽框数 | 结果 |
|---|---|---|---|---|---|
| 1 | `quantity_keeper.png` | `QuantityKeeperMenu` | 43 | 43 | OK |
| 2 | `range_charger.png` | `RangeChargerMenu` | 42 | 42 | OK |
| 3 | `advanced_quantity_keeper.png` | `AdvancedQuantityKeeperMenu` | 46 | 46 | OK |
| 4 | `schematic_loader.png` | `SchematicLoaderMenu` | 97 | 97 | OK |
| 5 | `advanced_schematic_loader.png` | `AdvancedSchematicLoaderMenu` | 177（可见 123） | 123 | OK |
| 6 | `collection_cache.png` | `CollectionCacheMenu`（+`AdvancedCollectionCacheMenu`） | 138（+1 区域槽自绘） | 138 | OK |
| 7 | `decomposition_generator.png` | `DecompositionGeneratorMenu` | 62 | 62 | OK |
| 8 | `inventory_proxy.png` | `InventoryProxyMenu` | 37 | 37 | OK |
| 9 | `sequence_pattern_terminal.png` | `SequencePatternTerminalMenu` | 79（可见 68） | 68 | OK |
| 10 | `unit_pattern_manager.png` | `UnitPatternManagerMenu` | 动态 | 36（仅玩家背包+快捷栏） | OK |
| 11 | `sequence_assembly_executor.png` | **无（孤儿贴图）** | — | 43（`quantity_keeper` 副本） | OK（零引用如声明） |

「可见」的含义：滚动窗口只烘焙当前行数的槽框（如高级装填器 108 格库存只画 6 行 = 54 格），
其余格子在运行时换位，不参与像素比对；但**槽数账目仍要平**（`sum(分组 slots) == menu_slots`）。

### 3.2 没有槽框的贴图（15 张，逐张登记，不许静默跳过）

| PNG | 为什么没有槽框 |
|---|---|
| `advanced_remote_terminal.png` | 176×120 终端外壳图，零引用（终端走 RS 的 grid 屏） |
| `chamber_binding_config.png` | 绑定配置界面，纯文字/按钮 |
| `chamber_units_summary.png` | **有 Menu 槽位**，但槽框全部由 Screen 运行时自绘（§4 的 code 类） |
| `fe_energy_cube.png` | Menu 故意 0 个 `addSlot`；界面只有 `GuiGraphics#fill` 电量条 |
| `advancements/backgrounds/advancements.png` | 进度界面 16×16 底纹，非容器界面 |
| `collection_cache/marker_config.png` · `range_config.png` | 标记/范围配置子界面，无槽位 |
| `spt/result_config.png` · `step_detail_config.png` | 序列样板终端子界面，无槽位 |
| `spt/spt_btn_{delete,large,small}.png` · `spt_card_bg.png` · `spt_scrollbar_thumb.png` · `spt_tabs.png` | 按钮 / 卡片九段 / 滚动条 / 页签精灵 |

### 3.3 Menu 侧覆盖（19 支 `*Menu.java`，一支不漏）

| 背景类型 | 支数 | 说明 |
|---|---|---|
| `png`（本仓库贴图烘焙） | 11 | 见 §3.1，**有像素级判据** |
| `code`（屏幕逐个 `menu.slots` 自绘） | 4 | `ChamberUnitsSummaryMenu` / `IntermediateCacheMenu` / `NetworkResourceReceiverMenu` / `NetworkResourceRequesterMenu` —— 判据 = 自绘调用必须 `slot.x - 1 / slot.y - 1` |
| `vanilla`（原版 `generic_54`） | 2 | `SequenceAssemblyExecutorMenu` / `SequenceExecutionChamberMenu` —— 贴图在 MC jar 内，**无像素级判据**（§6 限制） |
| `none`（没有槽位） | 1 | `FEEnergyCubeMenu`（源码里就写着「故意不 addSlot」） |
| `rs_framework`（RS 框架画） | 1 | `client/grid/ResourcePickerMenu`（继承 RS 的 `AbstractGridContainerMenu`） |

### 3.4 代码自绘槽框（16 处 `McGui.slotFrame` + 1 处 `blitSprite(Sprites.SLOT)`）

| 类型 | 处数 | 判据 |
|---|---|---|
| **与 Menu 槽位同源**（必须 `- 1`） | 8 | 6 处 `slotFrame(leftPos + slot.x - 1, topPos + slot.y - 1)`；+`AdvancedCollectionCacheScreen` 的区域槽（`AREA_SLOT_X/Y - 1`）；+`SequencePatternTerminalScreen` 的 3 格 RS 样板槽（`RS_PATTERN_SLOT_X + i*18 - 1` / `RS_PATTERN_SLOT_Y - 1`） |
| **不是 Menu 槽位**（只登记调用点） | 9 | `AreaMarkScreen`×2（标记网格）、`AreaStripPanel`×1（区域条小格）、`BusCategoryConfigScreen`×2、`ChargerFilterScreen`×4（列表行图标外框）；`UnitPatternManagerScreen` 的动态分组槽框走 `blitSprite` 单列 |

---

## 4. 登记表 `tools/gui_slot_registry.py` 说明

只有数据，没有判定逻辑（判定全在自检脚本里）。五张表：

| 表 | 作用 | 缺了会怎样 |
|---|---|---|
| `PAINTED` | 每张**烘焙了槽框**的 PNG：`png / kind / screen / menus / menu_slots / groups / anchors` | 该 PNG 一出现槽框就 FAIL（「新界面必须登记并自检」） |
| `MENUS` | `src/main/java/**/*Menu.java` 的**每一支**：`file / screen / bg / png / slots / note` | 新增一支 Menu 就 FAIL |
| `CODE_FRAMES` | 每个代码自绘槽框调用点：`file / api / count / menu_slot / pattern / extra / note` | 调用点总数对不上就 FAIL |
| `DECORATIVE` | `textures/gui/**` 下**没有**槽框的图：`png / frames=0 / why` | 这些图一旦长出槽框就 FAIL（说明该改成正式登记） |
| `ORPHAN` | 必须**零引用**的贴图 | 被源码引用就 FAIL |

关键字段：

* **`groups[].src`**：槽坐标的源码出处（`文件:行号`），例如
  `menu/CollectionCacheMenu.java:74-75 (MATCH_X0/MATCH_Y0) / :313-315`。
  这就是用户要的「槽坐标来源（含源码行号）」。
* **`why` / `note`**：**为什么无法静态解析**。例如
  `UnitPatternManagerMenu` 的分组槽位由 `rebuildSlots()` 按「可见分组 × 行数 × 搜索过滤」动态摆放、
  被隐藏的挪到 `HIDDEN_SLOT_Y = -1000`，没有静态坐标；
  `ChamberUnitsSummary`/`IntermediateCache`/`NetworkResource*` 的槽数随方块实体/滚动变化。
* **`anchors`**：`(源码文件, 正则)`——源码里必须仍然找得到这段（**坐标数字逐字在其中**）。
  这是**防表过期**的锁：Menu 改了坐标而登记表没改 ⇒ 锚点红（不是静静放过）。
  本轮共 **62 条锚点**，覆盖 11 张 PNG 涉及的 6 支 Menu + 1 支 Spec。
* **`kind`**：
  * `plain`：常规界面，`期望 = (x + col*18 − 1, y + row*18 − 1)`；
  * `stretching`：RS 九段式拉伸界面（`AbstractStretchingScreen`），底部整块是从贴图某行裁下来再拼的，
    期望的**贴图坐标**要换算（见 §5.4）；
  * `orphan`：零引用历史副本，判据 = 与 `mirror` 指向的 PNG 逐格相同 + 零引用。
* **`slots` 与 `cols/rows`**：`cols*rows` = **背景上烘焙出来的格数**；`slots` = 该组在 Menu 里的
  **全部**槽位数（滚动窗口里比 `cols*rows` 大）。`sum(slots)` 必须等于 `menu_slots`，这是
  「槽数账目」断言——防止「Menu 加了一格、贴图没加」这种事被漏掉。

---

## 5. 边界情况（本轮逐个查清，都写进登记表）

### 5.1 `collection_cache.png` 被两支 Menu 共用
`AdvancedCollectionCacheMenu extends CollectionCacheMenu`：底图仍是 `collection_cache.png`（138 格烘焙），
额外的**区域选定器槽 1 格**（`AREA_SLOT_X/Y = 10/219`）**不烘焙**，由
`AdvancedCollectionCacheScreen.renderBg` 用 `frameX/frameY = ... - 1` 自绘。
所以这支 Menu 的 139 格 = 138（像素判据）+ 1（代码判据）。

### 5.2 `sequence_pattern_terminal.png` 有 2 组槽位不烘焙
流程编排窗口（8 格，`ARR_SLOT_X/Y = 9/33`）与 RS 样板槽（3 格，`70/191`）**都不在背景里**：
前者「行槽框由界面自绘」（滚动时跟 `setSlotY` 一起动），后者是「删掉导入按钮后腾出的位置」，
由 `renderBg` 里 `McGui.slotFrame(leftPos + RS_PATTERN_SLOT_X + i*18 - 1, topPos + RS_PATTERN_SLOT_Y - 1)` 自绘。
登记表里这两组是 `painted=False`（参与槽数账目、不参与像素比对），自绘部分进 `CODE_FRAMES`。

### 5.3 `sequence_assembly_executor.png` 是孤儿贴图
`SequenceAssemblyExecutorScreen` 用的是**原版** `minecraft:textures/gui/container/generic_54.png`；
`textures/gui/sequence_assembly_executor.png` 全仓库**零引用**，且 43 个槽框与
`quantity_keeper.png` 逐格相同（疑似当年 copy 出来的）。
登记成 `kind="orphan"` 而不是删掉：一旦有人开始引用它 → 断言红 → 必须改成正式登记。

### 5.4 `unit_pattern_manager.png` 是 RS 「拉伸界面」，坐标不在面板顶部
它继承 RS 的 `AbstractStretchingScreen`（`tools/make_gui_bg.py` 之外的另一套机制）：

* `Screen` 构造器写 `this.imageHeight = 176`，只用来反推**可见行数**
  `(176 - 19 - 99) / 18 = 3`；
* RS 的 `init()` 随后把 `imageHeight` 改写成 `TOP_HEIGHT(19) + 18*3 + getBottomHeight(99) = 172`；
* 底图绘制 = 顶部 `19px` + 中间 3×`18px`（从贴图 `src_v=19/37/55` 抽横条）+ **底部整块**
  `172 - 99 = 73` 起、从贴图 `src_v=73` 裁 `99px`；
* 玩家背包 y = `imageHeight - 99 + 17 = 90`，快捷栏 = `90 + 58 = 148`；
* 于是贴图里玩家背包槽框 y = `73 + (90 - 1) - 73 = 89`、快捷栏 = `147` —— 与实测
  (7,89)/(7,107)/(7,125)/(7,147) 完全一致。

因为 `src_v(73) == top_h + 18*visible_rows(19+54=73)`，最终**恰好退化成 `Menu − 1`**；
但脚本仍按完整换算实现（`stretch` 字段）。这样一旦有人改了构造器里的 `176`（可见行数变）、
改了 `getBottomHeight()` / `getBottomV()`，换算结果就会偏 → 判负，逼着重新核对贴图。

另外 `renderSections` 里分组槽框是运行时画的原版 slot 精灵：
`slotX = BAND_X + col*18`，`BAND_X = GROUP_SLOT_X - 1`（x 已 −1）；
`slotY = rowY + ROW_SIZE + row*ROW_SIZE`，`rowY = y + topHeight - offset`，
而 RS 传给 `resized()` 的 `topYStart = TOP_HEIGHT + 1`（Menu 侧起点）⇒ y **净 −1**。

### 5.5 反例：本脚本自己坏了怎么办
脚本内置 **7 条反例自证**（§8）：把 `OFFSET`、登记表、锚点、模板逐个改坏，断言必须判负。
其中 **C-1（`OFFSET` 改成 0）** 就是「约定搞反」这个事故的模拟，正好复现用户看到的现象。

---

## 6. 已知限制（诚实登记，不装作覆盖了）

1. **原版贴图无法像素校验**：`SequenceAssemblyExecutorMenu` / `SequenceExecutionChamberMenu` 用的是
   `minecraft:textures/gui/container/generic_54.png`（在 MC jar 内，仓库里没有）。
   这两支只登记了 Menu 坐标来源与「原版 generic_54 布局」说明，**不做像素比对**。
   若要覆盖：把 vanilla 的 `generic_54.png` 拷进 `pic/`（或 `tools/` 参考目录）再登记即可，
   是 15 分钟的事，本轮没做（不想把原版素材塞进仓库）。
2. **代码自绘只校验公式**，不校验「画出来到底歪不歪」：判据是 `slot.x - 1` 的写法与锚点，
   不是渲染截图。真正的实机确认仍需要人看（或将来接无头渲染）。
3. **`unit_pattern_manager.png` 的 y 依赖 RS 框架常量**（`AbstractStretchingScreen.TOP_HEIGHT=19`、
   `resize()` 里的 `-99+17`）。这些数字在 RS jar 里，本仓库只锁了自己这侧的
   `imageHeight=176` / `getBottomHeight()=99` / `getBottomV()=73` / `PLAYER_INV_X=8` 四个锚点；
   RS 升级若改了框架算法，需要重新核对（脚本会因 expected 与实际不符而红，不会静默）。
4. **2 色模板的理论误检风险**：任何界面只要出现「17px 连续深灰 + 17px 连续深灰 + 16×16 纯 139 灰」
   的 L 形区域就会被认成槽框。本轮 26 张图的实际检出数与「按源码算出的格数」**逐个相等**
   （735 = 735），没有误检；但新增贴图时若出现大面积同色块，需人看一眼失败信息再判断。

---

## 7. 以后新增一个带槽位的 GUI：3 步

### 第 1 步：生成 PNG 时用 `--slots-menu` 传 **Menu / Spec 坐标**（让工具替你 −1）

```bash
# 例：新界面 Menu 里是 addSlot(..., 9 + col*18, 85 + row*18) 与 (188, 7 + i*18)
python tools/make_gui_bg.py --out src/main/resources/assets/rs_create_compat/textures/gui/xxx.png \
    --width 210 --height 166 \
    --slots-menu "9,85,9,3" "9,143,9,1" "188,7,1,6"
```

* **不要**用 `--slots` 手算精灵坐标（历史用法），那是「算反 −1」的事故来源；
* 数字与 `XxxMenu` / `XxxSpec` 里给 `addSlot` 的**逐字相同**，不做任何加减。

### 第 2 步：在 `tools/gui_slot_registry.py` 登记两张表

```python
# ① PAINTED 加一条（png 有烘焙槽框）
dict(
    png="xxx.png", kind="plain",
    screen="client/screen/XxxScreen.java",
    menus=["menu/XxxMenu.java"],
    menu_slots=43,                       # = 该 Menu 的 addSlot 总数（含滚动窗口里没烘焙的行）
    groups=[
        dict(name="玩家背包", x=9, y=85, cols=9, rows=3,
             src="menu/XxxMenu.java:59"),   # ← 源码行号
        dict(name="插件槽", x=188, y=7, cols=1, rows=6,
             src="menu/XxxMenu.java:46"),
    ],
    anchors=[                            # ← 防过期：源码里必须仍能找到这些字面量
        ("menu/XxxMenu.java", r"9 \+ col \* 18, 85 \+ row \* 18"),
    ],
),

# ② MENUS 加一条（每支 Menu 都必须有）
dict(file="menu/XxxMenu.java", screen="client/screen/XxxScreen.java",
     bg="png", png="xxx.png", slots=43),
```

* 如果这个界面**不**在背景里画槽框（代码自绘 / 原版底图 / 没有槽位）：
  `bg="code" | "vanilla" | "none" | "rs_framework"`，`png=None`，
  并在 `note` 里写清「为什么无法静态解析」；
* 如果代码自绘槽框：还要在 `CODE_FRAMES` 加一条（`menu_slot=True` 的**必须带 `- 1`**）。

### 第 3 步：跑自检（全绿才算完）

```bash
python tools/selfcheck_gui_slot_alignment.py     # 期望：ALL_PASS / 退出码 0
python tools/_run_all_selfchecks.py              # 它会自动收集 selfcheck_*.py
```

失败信息会直接告诉你「缺哪些格 / 多哪些格 / 整体偏 (+dx,+dy)」以及是哪个 PNG、哪一组槽位。

---

## 8. 反例自证（7 条，脚本每次运行都会跑）

| # | 把自检改坏的方式 | 期望 |
|---|---|---|
| C-1 | `OFFSET` 改成 `0`（= 把约定改成「槽框 = Menu 坐标」） | 10 张图判负，且提示 **整体偏 (+1,+1)** |
| C-2 | 删掉 `quantity_keeper.png` 的登记 | 报「有 43 个槽框但未登记」 |
| C-3 | 玩家背包 `x` 多算 1px | 报缺 9 格 / 多 9 格 |
| C-4 | `unit_pattern_manager` 的 `stretch.visible_rows` 改错 | 判负（拉伸换算不对） |
| C-5 | 从 `MENUS` 删掉一支 Menu | 报「Menu 未登记」 |
| C-6 | 把 `slotFrame(leftPos + slot.x - 1, ...)` 的 `- 1` 去掉 | 源码锚点不再匹配 ⇒ 判负 |
| C-7 | 模板改成 `pic/slot.png` 的 (1,1) 起 17×17 | 模板结构校验失败 ⇒ 全部判负 |

C-1 的实际输出（用当前仓库数据跑出来的，就是用户看到的现象）：

```
FAIL PNG:decomposition_generator.png  0/62 格对不上；
     缺 (8,18) (8,36) (8,54) (8,72) (8,128) (8,146) …共 62 个；
     多 (7,17) (7,35) (7,53) (7,71) (7,127) (7,145) …共 62 个 → 整体偏 (+1,+1)
```

---

## 9. 本轮改动 / 未做

* **新增** `tools/selfcheck_gui_slot_alignment.py`（117 条断言 = 110 正判 + 7 反例，1.1s）
* **新增** `tools/gui_slot_registry.py`（11 张烘焙槽框 + 15 张无槽框 + 19 支 Menu + 12 个自绘点
  = 16 处 `slotFrame` + 1 处 `blitSprite` + 62 条锚点）
* **新增** 本文件
* **未改** `src/**` 一个字节（只读）；**未改** `tools/make_gui_bg.py` / `make_gui_gui.py`（Lead 在改）
* **未跑** gradlew / javac（Lead 统一编译）、未 git
* **未做** 实机截图确认（本脚本是源码+像素静态判据；真机观感仍建议 Lead 进一次游戏看一眼
  `decomposition_generator` 与 `unit_pattern_manager`）
