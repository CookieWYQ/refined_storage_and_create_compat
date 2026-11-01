# 第 130 轮 · 契约书：区域条去掉「标记网格」、改在匹配区编辑 + 搜索 / 删除确认 / 字体与废话收敛

> 本文件是**规格**，不是实现说明。任何实现方要改这里的名字 / 常量，必须先通知 Lead。

## 0. 用户原话（第 129 轮验收后的反馈，逐字）

> 「然后那个高级硅硫（= 归流）缓存仓。现在的那个形式选择的形式我是比较满意的，就是这样子……
> 就是编辑，就是比如说我选择某一个区域，我在匹配器中匹配就应该是可以直接拖进去。
> 而不是说在这一个界面中单独出现一个标记什么的东西，把那一部分删除。
> 而且你现在显示边框就是在这个缓存仓中显示这一个就是还是不行，还是没用。
> 而且你有一些字很小，而且有些没必要的废话。
> 总而言之就是：保留当前这一个选择列表，当然同时增加一个搜索功能，可以搜索坐标，也可以搜索名字之类的。
> 然后这个编辑按钮就这个改名字按钮和这个删除按钮保留，同时确保这个删除按钮有删除前确认。
> 然后让这一个编辑的时候在匹配区中编辑。然后记住这玩意（区域选定器）的数据应该是存在这个物品里面，
> 而不是存在这个缓存仓里面，因为这玩意是跟着物品走的。」

⇒ 六条硬需求：

1. **删掉区域条里的「标记网格」整页**（`AreaStripPanel` 的 3×4 页 + 清空 / 返回按钮 + JEI 拖放目标分支）。
   区域过滤的编辑入口**只剩机器界面的「匹配区」48 格**（它本来就读写当前选中区域，见
   `CollectionCacheMenu#markerWindowEntries` 等 5 个上下文钩子）。
2. **选行 ⇒ 匹配区就是那个区域**：点区域条一行，匹配区标题带直接写明「区域 3 · 铁场」，
   里面就是该区域的过滤标记；从背包 / JEI 拖进去都写进这个区域。
3. **列表加搜索**：可按**名字**也可按**坐标**过滤（同一输入框）。
4. **改名 / 删除按钮保留**；删除**必须二次确认**（确认态要看得见、3 秒自动回退）。
5. **世界边框要真的看得到**：关掉 GUI 后框不能立刻消失（按 5 秒重新武装），并给每行一个「看」按钮。
6. **字别太小、废话别多**：区域条加宽（128 → 176），坐标行改成紧凑写法，删掉 howto 提示族。
7. **数据仍然只存在选定器物品上**（`AreaSelection` 数据组件 + `AreaMarks` 的 `RsccAreaMarks`），
   方块实体里**不得**新增任何区域 / 标记字段。

## 1. 文件所有权（写作用域，冲突即错）

| 文件 | 归属 |
| --- | --- |
| `client/screen/AreaStripPanel.java` | **Lead** |
| `client/screen/AdvancedCollectionCacheScreen.java` | **Lead** |
| `resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json` | **Lead** |
| `client/screen/CollectionCacheScreen.java` | **matchgrid** |
| `client/CollectionCacheJeiPlugin.java` | **matchgrid** |
| `tools/selfcheck_round130_*.py`、`docs/ROUND130_*.md`（草稿） | **verify** |

**禁止**：两个 owner 同时改同一文件；`gradlew` / `javac` 只由 Lead 执行。

## 2. 区域条新几何（Lead 实现，自检可断言）

```java
public static final int W           = 176;  // 理想宽（原 128：128 宽必然把坐标行缩到 0.6 倍 = 玩家说的「字很小」）
public static final int MIN_W       = 140;
public static final int HARD_MIN_W  = 108;
public static final int GAP         = 6;
public static final int SCREEN_MARGIN = 4;
public static final int ROW_H       = 22;
public static final int PAD         = 4;
private static final int TITLE_H    = 12;
private static final int SEARCH_H   = 12;   // 搜索框（EditBox）高
private static final int SEARCH_GAP = 3;    // 标题下沿 → 搜索框
private static final int FOOTER_H   = 16;
private static final int FOOTER_GAP = 3;
```

纵向排布（自上而下）：

```
y + PAD                   标题：区域 3/7（过滤中：区域 3/7 · 命中 2）
y + PAD + TITLE_H + 1     橙色 1px 分隔线（沿用）
y + PAD + TITLE_H + 2     搜索框（整条宽，高 12）
  + SEARCH_H + SEARCH_GAP 行列表起点（rowsAreaTop）
...
y + h - PAD - FOOTER_H    底部：[显示全部] [收起]
```

行内容（两行，行高 22）：

```
▶ 3. 铁场                        [看][✎][✕]     ← 行首 ▶ 仅当前行；右侧 3 个 8px 动作图标
  100,64,100→120,80,120          [icon][icon]   ← 坐标紧凑写法（无空格、无括号）；右侧过滤图标摘要
展开行（+40px）：[名称____]  [X0][Y0][Z0] [X1][Y1][Z1]（6 个 RsccNumberField，继承现状）
```

- 坐标紧凑串 = `minX,minY,minZ→maxX,maxY,maxZ`（`Area#describeMin/describeMax` 的返回值里本身带空格，
  实现时用 `Area.minX()` 等 6 个 getter 自己拼；**不得**再出现 `(...)→(...)` 的括号版）。
- 交付判据（verify 用算术判据断言，不靠肉眼）：`W - 2*PAD - SCROLL_W = 176-8-6 = 162`
  是文字可用总宽；坐标行最坏情况 `-9999999,-9999999,-9999999→9999999,9999999,9999999`（52 字符）
  仍放不下是允许的（那是理论极值，`Area#MAX_SIDE` 约束下坐标最多 7 位，实际常见 ≤ 26 字符），
  但**典型串** `1000,64,1000→1015,80,1015`（26 字符 ≈ 143px @9px 字体）**必须 1.0 倍放得下**
  （可用宽：162 - 动作图标 28 - 过滤图标 20 = 114 … 不够 ⇒ 见下一条）。

**字号纪律（用户第 5 条的直接正解）**：单行文字**允许**缩的最小倍数是 `RsccTextFit.MIN_SCALE = 0.6`，
但区域条里**不许**出现落在 0.6 的文字——
- 名字行：`RsccTextFit.drawFitLine` 可用宽 = `contentW - 动作图标宽(28) - 2`，字号 < 1.0 时改为
  「只画 `N. 名字`，把 ▶ 省掉」（`▶` 是装饰，名字是信息）；
- 坐标行：**两段右对齐折行** —— 左段 `100,64,100→120,80,100`，若放不下则分成
  `100,64,100`（左）与 `→120,80,120`（右对齐到过滤图标左侧）；两段都按 1.0 倍画，**不再缩**。
  两段仍然放不下（极值）时才允许缩，且下限仍是 0.6。
- 搜索框 / 底部按钮 / 提示文字一律 1.0 倍。

## 3. 区域条的交互契约（Lead 实现）

### 3.1 搜索

- 一个 `EditBox`（`client/widget/RsccNumberField` 不适用；用原版 `EditBox`，与改名框同一套 `minecraft.font`），
  常驻（不是点了才出现），hint 文案 = `gui.rs_create_compat.area_strip.search.hint`。
- 过滤规则（**纯客户端**，只作用于快照行）：输入按空格切成若干 token，**全部 token 命中**即保留；
  单个 token 命中 = 名字（`Area#displayName`）不区分大小写的 `contains`，**或**
  坐标串（`minX,minY,minZ,maxX,maxY,maxZ` 六个数拼成的串，逗号与空格都去掉）里 `contains`
  （`"100"` 命中 `100,64,100`、`"64100"` 也命中；这是「搜索坐标」的最省事口径）。
- 过滤中：标题第二段显示 `· 命中 N`；`scrollRows` 归零；当前行仍按 `currentRaw()` 判定（行可能被过滤掉 ⇒
  标题退化成 `区域 -/7`，但**不**清服务端编辑上下文）。
- 键盘：搜索框聚焦时 `charTyped` / `keyPressed` 都先给它（`isEditing()` 已经把「有框聚焦」暴露给 owner，
  见 §3.4）；`Esc` 清空搜索而不是关界面（`keyPressed` 里先处理）。
- 行数不足时搜索框仍画（玩家能删字）。

### 3.2 行动作图标（3 个，从左到右）

| 图标 key | 动作 |
| --- | --- |
| `area_strip.rename.glyph`（`✎`） | 改名（就地 `EditBox`，语义与现状逐字一致） |
| `area_strip.delete.glyph`（`✕`） | 删除（**两次点击确认**，见 §3.3） |

> **落地修订（2026-11-01，Lead）**：原计划的第三个图标「看（`◎`）」**撤掉**，`peekArea` 改绑
> **右键行**（同样的 `showForView` + `strip/peek/N` 5 秒框）。原因：动作区 3 个图标占 29px、
> 2 个只占 19px —— 省下的 10px 正好让**常见三位数坐标串（141px）在 176 宽里 1.0 倍放下**
> （`RsccTextFit` 不再缩到 0.93）。用户第 5 条（「有些字很小」）优先于「多一颗图标」。
> `peer.glyph` 文案键保留（自检 §8 仍要求它在 lang 里），但不强制画在行内。

点行体（不是图标）仍然是「选中 ⇒ 发 `SetAreaFilterContextPacket`（= 匹配区切到这个区域）+ 常驻世界框」，
语义与现状逐字一致；双击名字仍然进改名（现状保留）。

### 3.3 删除确认（用户第 3 条）

- 第一次点 `✕`：该行进入 **armed** 态 3 秒：
  - 第一行文字换成红字 `gui.rs_create_compat.area_strip.delete.confirm.this`（「确认删除 #3？」）；
  - 右侧动作区换成两个按钮：`✓`（`area_strip.confirm.glyph`）与 `✗`（`area_strip.cancel.glyph`）；
  - 点 `✓` = 发 `AreaSelectorEditPacket(ACTION_DELETE)`；点 `✗` / 点别处 / 3 秒到期 = 回退；
  - armed 期间**不画**名字与坐标的第二段（留给确认文案），逻辑同现状的 `armed` 分支。
- `expireArming()` 必须仍然把 armed 归零（现状已有；保持）。
- 判据：**没有任何代码路径能在「未 armed」状态直接发 `ACTION_DELETE`**。

### 3.4 owner 转发（`AdvancedCollectionCacheScreen`，Lead 实现）

现有六处转发全部保留（`layout / render / mouseClicked / mouseScrolled / keyPressed / charTyped / removed`），
本轮**不改签名**，只在 `removed()` 里：
- 删掉 `strip.closeMarkGrid()`（方法已不存在）；
- `strip.removed()` 现在负责「关界面时把常驻框按剩余 5 秒重新武装」（见 §3.5）。

### 3.5 世界边框（用户第 4 条：**「还是没用」的正解**）

诊断：第 125 轮契约要求「关界面清框」（`removed()` 里 `AreaOutline.remove(slot("strip/focus"))`），
于是玩家的实际动作 —— **点行 → 关掉界面 → 转头看那个区域** —— 看到的永远是「框一闪就没有」。
这就是「显示边框还是没用」。

- `render` 里 `feedWorldOutline()` 不变（每帧 `chase`，界面开着时常亮）；
- `removed()` 改成：若当前有区域 ⇒ `AreaOutline.showForView(slot("strip/focus"), area, VIEW_TTL)`
  （5 秒 TTL，与旧 `AreaSelectorScreen#removed()` 同一口径）；「显示全部」喂过的 slot 同样按 5 秒重新武装；
- `slot("strip/focus")` 改成 **static final 常量**（`private static final Object FOCUS_SLOT = AreaOutline.slot("strip/focus");`），
  不再每帧新建 String（Outliner 的 slot 是键，每帧新对象是纯浪费）；
- 每行 `◎` 按钮 = 5 秒独立 slot（`strip/peek/N`，N = 原始下标），与「当前行」的框互不干扰。

## 4. 匹配区 = 唯一的区域过滤编辑入口（matchgrid 实现）

### 4.1 匹配区标题带显示「正在编辑哪个区域」

`CollectionCacheScreen`：

- 把 `renderLabels` 里画 `LANG + "region_marker"` 的那两行抽成：
  ```java
  /** 匹配区标题带的文案（基础版 = 「匹配区」；高级版覆写成「区域 3 · 铁场」）。 */
  protected Component matchRegionLabel() {
      return Component.translatable(LANG + "region_marker");
  }
  ```
  并用 `RsccTextFit.drawFitLine(guiGraphics, font, matchRegionLabel(), MATCH_LABEL_X, MATCH_LABEL_Y,
  MATCH_LABEL_BAND_W, matchDisabled ? COLOR_DISABLED : 0xFF333333)` 画（`MATCH_LABEL_BAND_W = 92` 已存在）。
  逐字等价：基础版看到的仍然是「匹配区」/「Markers」。
- 高级版（Lead 写）覆写成：`已选区域` ⇒ `区域 %s · %s`（序号 + 名字，超宽由 `drawFitLine` 处理）；
  `未选区域` ⇒ `匹配区（未选区域）`。

### 4.2 未选区域时的空态提示（避免「我在编辑什么？」）

高级版、`advancedMenu().getEditingAreaIndex() < 0` 时，匹配区 48 格照旧画（它们此时读的是**全局匹配区**，
那是不该被误当成区域过滤的东西），在匹配区面板正中叠一行灰字：
`gui.rs_create_compat.collection_cache.match_area.pick_hint`（「先在右边点一个区域，再来配它的过滤」）。
实现位置：`CollectionCacheScreen#renderLabels` 之后由子类覆盖一个 `protected void drawMatchAreaHint(...)`
钩子（基础版空实现；`matchgrid` 加钩子，`Lead` 在高级版里覆写并调用 `advancedMenu()`）。**钩子签名**：

```java
/** 匹配区面板内的空态提示（基础版什么都不画）。 */
protected void drawMatchAreaHint(final GuiGraphics guiGraphics) { }
```

### 4.3 JEI 拖放（`CollectionCacheJeiPlugin`）

- 删掉「侧栏标记网格分支」（`strip.isMarkGridOpen()` / `markGridCellAreas()` / `acceptGhostIngredient`
  / `acceptGhostFluid` 在 `AreaStripPanel` 里已被删除，这里是**唯一**必须同步删掉的调用方）。
- 保持「48 格目标」分支不动：高级版下 `menu.globalIndex(i)` + `SetCollectionMarkerConfigPacket`
  已按 `editingAreaIndex` 落到当前区域（服务端 `AdvancedCollectionCacheMenu#applyMarkerConfig` 钩子）。
- 保持 `getGuiExtraAreas` → `AreaStripBounds.get()`（JEI 让位）。
- 判据：`CollectionCacheJeiPlugin` 里 `isMarkGridOpen|markGridCellAreas|acceptGhost|AreaStripPanel`
  出现次数 = 0（`AreaStripPanel` 这个类型名不再 import）。

## 5. 数据归属（用户第 6 条，**已经是事实，本轮只做断言**）

- 区域几何 = `AreaSelection`（注册版数据组件 `rs_create_compat:area_selection`）→ 物品。
- 每区域过滤标记 = `AreaMarks`（`CUSTOM_DATA.RsccAreaMarks`）→ 物品。
- 本轮**禁止**在 `AdvancedCollectionCacheBlockEntity` 新增任何 `Area*` 字段 / NBT；
  既有 `legacyAreaFilters`（旧档迁移源）不许删也不许新增写入。
- verify 判据：`AdvancedCollectionCacheBlockEntity` 里 `AreaMarks.(get|put|remove|migrateByIndex)` 的
  **写**路径只出现在迁移与「服务端权威写入」两处，且 `selectorStack()` 是唯一被写的栈。

## 6. 文案纪律（用户第 5 条「有些没必要的废话」）

- 区域条 **tooltip 从 6 行砍到 3 行**：名字+序号、`从 …`、`尺寸 / 过滤数`。删掉 `tip.howto` 那行。
- 删掉 `empty` / `waiting` 之外的一切常驻说明句（底部不加提示行）。
- 新 lang 键（Lead 落盘，实现方直接用）：

| key | zh_cn | en_us |
| --- | --- | --- |
| `gui.rs_create_compat.area_strip.search.hint` | `搜索名称 / 坐标` | `Search name / coords` |
| `gui.rs_create_compat.area_strip.peer.glyph` | `◎` | `◎` |
| `gui.rs_create_compat.area_strip.confirm.glyph` | `✓` | `✓` |
| `gui.rs_create_compat.area_strip.cancel.glyph` | `✗` | `✗` |
| `gui.rs_create_compat.area_strip.title.filtered` | `区域 %1$s/%2$s · 命中 %3$s` | `Area %1$s/%2$s · %3$s hit` |
| `gui.rs_create_compat.area_strip.delete.confirm.this` | `确认删除 #%1$s？` | `Delete #%1$s?` |
| `gui.rs_create_compat.collection_cache.match_area.pick_hint` | `先在右边点一个区域，再来配它的过滤` | `Pick an area on the right first` |
| `gui.rs_create_compat.advanced_collection_cache.match_label` | `区域 %1$s · %2$s` | `Area %1$s · %2$s` |
| `gui.rs_create_compat.advanced_collection_cache.match_label.none` | `匹配区（未选区域）` | `Markers (no area)` |

- 删除（不再使用）的键：`gui.rs_create_compat.area_strip.mark.*` 全部（`mark.title / mark.hint / mark.full /
  mark.clear / mark.clear.tip / mark.clear.confirm / mark.back / mark.back.tip / mark.cell.tip /
  mark.cell.empty.tip / mark.remove.confirm / mark.fluid_unsupported / mark.no_hand / mark.glyph`）、
  `gui.rs_create_compat.area_strip.tip.howto`、`gui.rs_create_compat.area_strip.tip.marks`（若不再用）。
  **不要删** `gui.rs_create_compat.area_mark.*`（那是独立界面 `AreaMarkScreen` 的键，本轮不动）。

## 7. 验收（verify 写，Lead 跑）

新脚本 `tools/selfcheck_round130_area_strip_rework.py`，至少包含：

1. **几何**：`W=176 / MIN_W=140 / HARD_MIN_W=108` 字面量存在；`rowsAreaTop()` 公式里出现 `SEARCH_H`；
   底部 `FOOTER_H` 与面板高的关系成立（可解析常量算）。
2. **标记网格已删除**：`AreaStripPanel` 的源码里 `markGrid|markCells|openMarkGrid|acceptGhost` 出现次数 = 0；
   `CollectionCacheJeiPlugin` 里 `AreaStripPanel` 出现次数 = 0。
3. **搜索**：`AreaStripPanel` 出现 `searchBox` 字段、`matchesQuery`（或等价方法）、
   `filteredRows`（或等价）三处；`mouseClicked`/`keyPressed`/`charTyped` 里都有搜索框路径。
4. **删除确认**：源码里 `ACTION_DELETE` 的发送点**只有一个**，且被 `armed`（`deleteArmedRaw` /
   `armOrDelete`）分支包裹 —— 按「发送点上溯 N 行内必须出现 armed 判据」实现。
5. **世界框**：`removed()` 里出现 `showForView` 而不是只有 `remove`；`FOCUS_SLOT` 是 `static final`。
6. **字体**：`AreaStripPanel` 里所有 `drawFitLine` 调用的 maxWidth 都是**常量表达式或方法调用**，
   且坐标行不再出现 `"(" + ... + ")→("` 的括号拼接（反例断言）。
7. **数据归属**：`AdvancedCollectionCacheBlockEntity` 里没有新增 `Area*` 字段；
   `AreaMarks.put(` 的调用点全部在 `AdvancedCollectionCacheBlockEntity` / `AreaSelectorEditPacket` /
   `AreaMarks` 自己内部（白名单）。
8. **lang**：zh_cn / en_us 两边的键集合完全相同（既有 `selfcheck_lang_keys` 口径），
   且 §6 的 9 个新键两边都有；§6 删除的键两边都没有。
9. **反例**（每条至少一条）：`W=128` 判负、`isMarkGridOpen` 复活判负、
   `ACTION_DELETE` 裸发送判负、`removed()` 只 remove 不 showForView 判负。
