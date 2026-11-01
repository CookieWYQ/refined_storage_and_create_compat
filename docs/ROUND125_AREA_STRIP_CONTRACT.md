# 第 125 轮 · 契约书：区域条（AreaStrip）取代独立区域界面

> 本文件是**规格**，不是实现说明。实现方（area-strip）与验收方（strip-verify）都以此为准；
> 任何一方要改这里的名字/常量，必须先通知 Lead。

## 0. 用户原话（第 124 轮验收后的反馈，逐字）

> 「那个标记那个就高级的那个规定缓冲器标记的那个部分，不要再用现在这个界面……
> 这个界面首先丑，其次呢没有 JEI，然后不符合我们现在模组的设计风格。
> 就是呢把这一个工具呢放进这个方块里面之后呢，**可以直接在旁边出现一条这个什么选择的，
> 然后直接在里面选择那个，然后直接进行编辑**，这样子都好一点。
> 虽然说这一个已经有类似的人了，就是之前你搞的那种箭头的，但箭头的实在是不直观，
> 也不能明显的标注出当前是哪一个在干活……
> 除此之外，你要考虑这一个显示的区域的问题，应该放在哪里？
> 你要知道 JEI 是占据了屏幕的左右两侧的。要是乱放的话，会跟 JEI 重叠。」

⇒ 四条硬需求：
1. **不再弹独立全屏界面**（`AreaSelectorScreen` 从机器路径退役）；
2. **区域条常驻在方块 GUI 旁边**，点行即选、就地编辑（改名 / 坐标 / 删除 / 过滤入口）；
3. **一眼看出「当前哪个区域在干活」**（左侧亮条 + 行底色 + 常驻世界框 + 标题显示当前名）；
4. **与 JEI 零重叠**：把侧栏矩形注册给 JEI（`IGuiContainerHandler#getGuiExtraAreas`），由 JEI 自己挪开。

## 1. 文件所有权（写作用域，冲突即错）

| 文件 | 归属 |
| --- | --- |
| `client/AreaStripBounds.java`（新） | area-strip |
| `client/screen/AreaStripPanel.java`（新） | area-strip |
| `client/screen/AdvancedCollectionCacheScreen.java` | area-strip |
| `client/screen/AreaSelectorScreen.java`（只做退役/收敛，不删文件） | area-strip |
| `client/CollectionCacheJeiPlugin.java` | area-strip |
| `client/screen/ChargerFilterCandidates.java` | bus-fold |
| `client/screen/ChargerFilterScreen.java` | bus-fold |
| `tools/selfcheck_round125_area_strip.py`（新） | strip-verify |
| `docs/ROUND125_*.md` | strip-verify（草稿）/ Lead（定稿） |

**禁止**：两个 owner 同时改同一文件；`javac` / `gradlew` 只由 Lead 执行（成员机无完整构建环境）。

## 2. 几何契约（自检锚点，字面量不得改）

GUI 本体（继承 `CollectionCacheScreen`）：`BG_W = 256`，`BG_H = 324`，左上角 `leftPos/topPos`。

侧栏 `AreaStripPanel`：

```
public static final int W        = 128;   // 理想宽
public static final int MIN_W    = 96;    // 屏幕挤时的最小宽
public static final int HARD_MIN_W = 72;  // 低于此宽：不画侧栏，chat 提示一次
public static final int GAP      = 6;     // 与 GUI 右边缘的间隙
public static final int SCREEN_MARGIN = 4; // 距屏幕右边缘至少留这么多
public static final int ROW_H    = 22;
public static final int PAD      = 4;     // 侧栏内边距
```

- 水平位置：`x = leftPos + BG_W + GAP`；可用宽 `avail = screenWidth - SCREEN_MARGIN - x`；
  `w = clamp(avail, MIN_W, W)`；若 `avail < HARD_MIN_W` ⇒ `visible = false`（一帧内只提示一次）。
- 垂直：`y = topPos + 4`，`h = BG_H - 8`（与 GUI 顶/底对齐，不超出屏幕）。
- 侧栏矩形（屏幕坐标）**每帧**写入 `AreaStripBounds`；不可见时写 `null`。

## 3. API 契约（签名逐字）

### 3.1 `client/AreaStripBounds.java`

```java
public final class AreaStripBounds {
    public static void set(@Nullable Rect2i area);   // 屏幕坐标；null = 侧栏不可见
    public static @Nullable Rect2i get();
    public static void clear();
}
```

- `set/get/clear` 都是 **static**，线程：客户端主线程 + JEI 回调（同线程），不需要同步但字段 `volatile`。
- `AdvancedCollectionCacheScreen#render` 顶部调用 `set(...)`；`removed()` 调 `clear()`。

### 3.2 `client/screen/AreaStripPanel.java`

自绘组件（**不**继承 `AbstractWidget`，与 `SptScrollbarWidget` 同风格：`render` + `mouseClicked` + `mouseScrolled` 由 owner 转发）。

```java
public final class AreaStripPanel {
    public AreaStripPanel(AdvancedCollectionCacheScreen owner);
    public int width();                       // 本帧自适应后的宽（layout 之后有效）
    public void layout(int leftPos, int topPos, int screenW, int screenH);
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);
    public boolean mouseClicked(double mx, double my, int button);
    public boolean mouseScrolled(double mx, double my, double delta);
    public boolean keyPressed(int keyCode, int scanCode, int modifiers);
    public boolean isEditing();               // 有 EditBox 聚焦 ⇒ owner 必须把 keyPressed 先给它
    public void removed();                    // 关界面：提交改名、清世界框
}
```

`AdvancedCollectionCacheScreen` 的转发义务（缺一条都算没交付）：
- `init()` 里 `strip = new AreaStripPanel(this)`；
- `render(...)` 顶部 `strip.layout(leftPos, topPos, width, height)`，`super.render` 之后 `strip.render(...)`；
- `mouseClicked` 先给 `strip`（命中即 return true），再走基类；
- `mouseScrolled` 同理；
- `keyPressed` 在 `strip.isEditing()` 时先给 `strip`；
- `removed()` 调 `strip.removed()`；
- **`openAreaScreen()` 不得再 `setScreen(new AreaSelectorScreen(...))`**（自检断言：`new AreaSelectorScreen` 在本文件里 0 次出现）。

### 3.3 JEI 避让（已在 JEI 19.27 源码确认）

`client/CollectionCacheJeiPlugin#registerGuiHandlers` 里追加：

```java
registration.addGuiContainerHandler(AdvancedCollectionCacheScreen.class,
    new mezz.jei.api.gui.handlers.IGuiContainerHandler<AdvancedCollectionCacheScreen>() {
        @Override
        public List<Rect2i> getGuiExtraAreas(final AdvancedCollectionCacheScreen screen) {
            final Rect2i strip = AreaStripBounds.get();
            return strip == null ? List.of() : List.of(strip);
        }
    });
```

- `IGuiContainerHandler<T extends AbstractContainerScreen<?>>`，注册泛型 `addGuiContainerHandler(Class<? extends T>, IGuiContainerHandler<T>)`。
- 返回**屏幕坐标**（JEI 原话：*"the space that the gui takes up besides the normal rectangle"*）。
- 目的：JEI 的 `IngredientListOverlay` 会把这个矩形算进占用，物品列表自动让位，**不是**我们手动挪。
- 注意 `AdvancedCollectionCacheScreen` 继承 `CollectionCacheScreen`，`Class` 传子类即可命中（JEI 用 `isAssignableFrom` 查找）。

## 4. 行为契约

| 行为 | 规格 |
| --- | --- |
| 常驻条件 | 专用槽里有 `AreaSelectorItem` ⇒ 侧栏可见；槽空 ⇒ 不画侧栏，仅 GUI 内保留原「区域」入口提示「请把区域选定器放进专用槽」 |
| 行内容 | `N. 名字`（无名 = 「区域 N」）/ 第二行坐标缩略 `(x,y,z)→(x,y,z)` / 右侧过滤图标摘要（复用 `AdvancedAreaSnapshotPacket.AreaEntry#filters()`） |
| 当前行标记 | 左侧 3px 亮橙竖条 `0xFF9F1C` + 行底 `0x30FF9F1C` + 行首 `▶`；标题行显示 `当前：<名字>` |
| 点行 | 发 `SetAreaFilterContextPacket(containerId, usableIndex)`（**与 `AreaSelectorScreen#sendFilterContext` 同一条链路**）+ 常驻世界框（`AreaOutline.chase` + 每帧重喂，slot 前缀 `strip/focus`） |
| 双击 / ✎ | 就地 `EditBox` 改名（Enter 提交 `AreaSelectorEditPacket.ACTION_RENAME(rawIndex, null, name)`、Esc 取消）——**打字时捕获 rawIndex**，提交后立刻复位（第 124 轮 2-2 的三个缺陷不得复现） |
| ✕ | 二次确认（点一次变成「确认？」3 秒）→ `ACTION_DELETE` |
| 坐标编辑 | 行展开后 6 个数字框（复用 `RsccNumberField`），失焦/Enter 提交 `ACTION_REPLACE`（**必须带名字**，服务端已保留旧名） |
| 底部按钮 | 「显示全部」（全部可用区域一次性常亮 5 秒）/「关框」 |
| 滚动 | 行数 × ROW_H > 可视高 ⇒ 滚轮 + 右侧 6px 滚动条（复用 `SptScrollbarWidget` 或自绘） |
| 数据源 | 只读 `AdvancedAreaSnapshotPacket#lastFor(containerId)`；客户端不判断可用性、不读方块实体 |
| 世界框生命周期 | 关界面时 `removed()` 清 slot（含常驻框）；避免「关掉界面框还在」 |

### 4.5 标记编辑就地做（用户「其次呢没有 JEI」的正解）

**现状缺陷**：机器路径的「编辑过滤」= `new AreaMarkScreen(...)`（`AreaSelectorScreen:821`），
而 `CollectionCacheJeiPlugin` 只给 `CollectionCacheScreen` 的匹配区注册了 ghost handler ——
**标记界面没有 JEI 拖入**，玩家只能翻背包。用户说的「没有 JEI」就是这个。

**改法**（机器路径不再打开 `AreaMarkScreen`）：

1. 侧栏行内「标记」按钮 / 双击行 ⇒ 侧栏下半部切「标记网格」模式：
   - 3 列 × 4 行，格子 18×18，最多 12 个标记；超出显示 `+N` 并支持滚轮/翻页
   - 空格子：主手有物品 ⇒ 添加；右键格子 ⇒ 删除（二次确认）；顶部「清空」= `ACTION_CLEAR`（二次确认）
   - 格子渲染与 `AreaSelectorScreen#drawRowFilters` 同源
2. **JEI 拖入**：扩展 `CollectionCacheJeiPlugin.MarkerGhostHandler#getTargetsTyped` ——
   当 screen 是 `AdvancedCollectionCacheScreen` 且 `strip.isMarkGridOpen()` 时，目标是侧栏格子，
   `getArea()` 返回格子屏幕坐标，`accept()` 走**第 125 轮新包** `network/AreaMarkAddItemPacket`。
   **不要用 `AreaMarkEditPacket`**：它真实是 5 参 record
   `(int containerId, int action, Area area, int markerIndex, int inventorySlot)`，
   `ACTION_ADD` 的第 5 参是**背包格下标**（服务端 `inventoryStack(player, slot)` 取物品）——
   JEI 拖出的是配方幽灵栈，不在背包 ⇒ 这条路永远静默丢包（也是「高级仓 48 格窗口 JEI 拖入一直没生效」的根因）。
   新包字段（定稿）：`(int containerId, Area area, ResourceLocation itemId, CompoundTag patch, int count, boolean fluid)`
   —— 服务端 handler 定位已打开的 `AdvancedCollectionCacheBlockEntity`，按 `AreaMarks` 口径写入选定器物品，
   然后走既有的区域快照广播刷新侧栏。**禁止**扩 `AreaMarkEditPacket` 的线格式（作者注释明确反对）。
3. `AreaStripPanel` 追加 API：`boolean isMarkGridOpen()`、`void closeMarkGrid()`（关界面时真）；
   以及唯一接缝 `void acceptGhostIngredient(ItemStack stack)`（JEI 回调只调这一个方法）。
4. `AreaMarkScreen` 只保留主手世界路径（`AreaSelectorHandler`），机器路径 0 次 `new AreaMarkScreen`。
5. `RS_Create_Compat` 里的 payload 注册一行由 **Lead** 补（area-strip 在汇报里给出确切插入位置，不要自己改该文件）。

### 4.6 顺带修掉的既有 bug（写进汇报，别静默）

高级仓原有 48 格窗口的 JEI 拖入只更新**已有**条目、新 id 被忽略（`AdvancedCollectionCacheBlockEntity#setAreaMarkerConfig`
只按下标改、不带 item id）。area-strip 在实现新包时若发现同一路径能一并修正，就修；不能就列进「发现但没动」。

## 5. 退役范围（不许一刀切删能力）

- `AreaSelectorScreen` **文件保留**（主手「潜行+右键空气」路径仍用它，自带完整能力）；
- `AreaMarkScreen` **文件保留**（主手路径仍可给单区域配标记），但机器路径不再打开它（见 §4.5）；
- 机器路径（`AdvancedCollectionCacheScreen#openAreaScreen`）**不再打开它**；
- 「区域」按钮改为：侧栏隐藏时 = 展开侧栏；侧栏可见时 = 收起侧栏（状态存 `static`，跨界面记忆）；
- 原「单显 / 全显」两颗按钮：作用并入侧栏（选中行即单显、底部「显示全部」即全显），**GUI 上这两颗按钮删除**（`(186,219)`/`(218,219)`），理由：用户说「箭头不直观」的同类问题是按钮重复。

## 6. 验收

1. `python tools/selfcheck_round125_area_strip.py` **≥ 40 断言 / ≥ 15 反例 / 0 失败**；
2. 受影响的旧脚本全绿（strip-verify 负责列出并修）：
   `selfcheck_round90/95/96/106/110/112/113/119/120/121/124_area_*`；
3. Lead 侧 `gradlew compileJava` 0 错；
4. 反例必须覆盖：JEI 矩形为空不注册、槽空不画侧栏、改名捕获行号、关界面清框、`new AreaSelectorScreen` 0 次。
