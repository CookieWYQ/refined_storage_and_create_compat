# 第 125 轮（beta.26）：区域条（AreaStrip）取代独立区域界面 —— 用户反馈落地记录

> 需求来源：`docs/ROUND122_USER_FEEDBACK.md` 第 1-2 / 1-3 / 1-4 条用户原话（第 124 轮验收后口述），
> 规格来源：`docs/ROUND125_AREA_STRIP_CONTRACT.md`（§0 用户原话逐字 / §1 文件所有权 / §2 几何 /
> §3 API / §4 行为 / §4.5 标记编辑就地做 / §5 退役范围 / §6 验收）。
> 旧断言迁移清单：`docs/ROUND125_LEGACY_ANCHORS.md`。

## 0. 一轮话总结

| # | 用户原话要点 | 结论 | 自检 |
| --- | --- | --- | --- |
| ① | 「不要再用现在这个界面……这个界面首先丑，其次呢没有 JEI，然后不符合我们现在模组的设计风格」 | **已实现**：机器路径 `openAreaScreen()` 不再 `setScreen(new AreaSelectorScreen)`；新 `AreaStripPanel`（1943 行自绘侧栏）贴在 GUI 右侧、默认常驻；旧 `(186,219)/(218,219)` 单显/全显按钮删除 | `selfcheck_round125_area_strip.py` **149 断言 / 38 反例 / 0 跳过 / 0 失败** |
| ② | 「把这一个工具放进这个方块里面之后，可以直接在旁边出现一条……然后直接在里面选择那个，然后直接进行编辑」 | **已实现**：点行 = 选中（`SetAreaFilterContextPacket`）+ 常驻世界框；就地改名（EditBox，打字捕获 rawIndex）、6 个坐标框（`RsccNumberField.parse` 统一口径）、删除二次确认、标记网格 3×4（12/页、`+N`、滚轮翻页）+ JEI 拖入 | 同上（§4 / §4.5）；`selfcheck_round125_charger_group.py` 不相关 |
| ③ | 「不能明显的标注出当前是哪一个在干活」 | **已实现**：左侧 3px 亮橙竖条 `0xFF9F1C` + 行底 `0x30FF9F1C` + 行首 `▶` + 标题 `区域 N/M · 当前：<名字>` + 常驻世界框 slot `strip/focus`（每帧 `AreaOutline.chase` 重喂） | 同上（§4）；离屏 mock `build/mock/area_strip_mock.png` |
| ④ | 「JEI 是占据了屏幕的左右两侧的。要是乱放的话，会跟 JEI 重叠」 | **已实现**：`CollectionCacheJeiPlugin` 注册 `IGuiContainerHandler#getGuiExtraAreas`，每帧把侧栏**屏幕矩形**（`AreaStripBounds`）报给 JEI，由 JEI 自己让位；侧栏不可见 ⇒ 返回 `List.of()` | 同上（§3.3）；JEI 19.27 sources 核实接口存在 |

**本轮验收**：`LEAD_COMPILE_OK sources=508`；全量自检 **187 个脚本 / 0 失败**（`_run_all_selfchecks.py`，用带 Pillow 的 bundled python —— 系统 python 缺 PIL 会让 `selfcheck_advancements` 误报）；
新增 2 个脚本：`selfcheck_round125_area_strip.py` **149 断言 / 38 反例**、`selfcheck_round125_charger_group.py` **61 断言 / 18 反例**；
受影响旧脚本 5 个已按 `ROUND125_LEGACY_ANCHORS.md` 迁移（`round106` ⑤-11/⑤-14/`_KEYS`+⑤-21/⑥-b8、`round101` ④-19、`round90` ⑦、
`round99` ③2、`round69` 登记新 tooltip 站点），**判据原意一处没放宽**（round99 ③2 反而从「必须存在」翻成「必须不存在 + 基类链路不得断」）。
jar `rs_create_compat-1.2.0-beta.26.jar`（**2,888,912 B**，sha256 `5c224516fe00b0dc7bf8c6123a5cccb4a2c2c455f25db4f0c7e66040e69e1f82`），部署四处并回读一致。**未实机验证**（见 §6）。

---

## 1. ① 不再弹独立全屏界面（`AreaSelectorScreen` 从机器路径退役）

### 1.1 改动

- `AdvancedCollectionCacheScreen#openAreaScreen` 不再 `new AreaSelectorScreen(...)`
  （判据：本文件里该构造 **0 次**；§5 保留主手「潜行 + 右键空气」路径的同一个类）。
- 「区域」按钮改为**开合侧栏**（状态 `static`，跨界面记忆）；原「单显 / 全显」两颗按钮
  `(186,219)` / `(218,219)` **删除**（作用并入侧栏：点行 = 单显、底部「显示全部」= 全显）。
- <待填：实现方给出的行号 / 提交说明>

### 1.2 证据

- 自检 §3.2 六处转发（init / render 的 layout→super.render→strip.render / mouseClicked /
  mouseScrolled / keyPressed 的 `isEditing()` 闸门 / removed）+ `new AreaSelectorScreen` 0 次 +
  按钮坐标解析里没有 `(186,219)` / `(218,219)`。
- 反例 <待填：C-14 / C-15 等>：把按钮加回来 / 让 `openAreaScreen` 复活全屏界面 ⇒ 必须判负。

---

## 2. ② 侧栏就地编辑（几何 + API + 行为）

### 2.1 几何（契约 §2，字面量不得改）

| 项 | 值 | 说明 |
| --- | --- | --- |
| 侧栏宽 | `W = 128` / `MIN_W = 96` / `HARD_MIN_W = 72` | `w = clamp(avail, MIN_W, W)`；`avail < HARD_MIN_W` ⇒ 不画（一帧只提示一次）<待填：提示文案键> |
| 间隙 / 边距 | `GAP = 6` / `SCREEN_MARGIN = 4` | `x = leftPos + BG_W + GAP`；`avail = screenWidth - SCREEN_MARGIN - x` |
| 行高 / 内边距 | `ROW_H = 22` / `PAD = 4` | 滚动 = 行数 × `ROW_H` > 可视高 ⇒ 滚轮 + 滚动条 |
| 垂直 | `y = topPos + 4`，`h = BG_H - 8` | 与 GUI 顶/底对齐；GUI 本体不变（`BG_W = 256` / `BG_H = 324`） |
| 矩形 | 每帧写 `AreaStripBounds`（屏幕坐标） | 不可见时写 `null`；关界面 `clear()` + `strip.removed()` |

### 2.2 API（契约 §3）

- `client/AreaStripBounds.java`：`public final class`，`volatile Rect2i`，`set(@Nullable Rect2i)` /
  `get()` / `clear()` 三个 **static**；null = 侧栏不可见。
- `client/screen/AreaStripPanel.java`：不继承 `AbstractWidget`；构造器 + `width/layout/render/
  mouseClicked/mouseScrolled/keyPressed/isEditing/removed` + §4.5 的 `isMarkGridOpen` /
  `closeMarkGrid` / `acceptGhostIngredient(ItemStack)`。

### 2.3 行为（契约 §4）

- 常驻条件：专用槽有 `AreaSelectorItem` ⇒ 可见；槽空 ⇒ 不画（GUI 内保留「区域」入口提示）。
- 行内容：`N. 名字` / 坐标缩略 / 右侧过滤图标摘要（复用 `AreaEntry#filters()`）。
- 点行：`SetAreaFilterContextPacket(containerId, usableIndex)`（与 `AreaSelectorScreen#sendFilterContext`
  同一条链路）+ 常驻世界框（`AreaOutline.chase` 每帧重喂，slot `strip/focus`）。
- 改名：就地 `EditBox`，**打字时捕获 rawIndex**，Enter 提交 `ACTION_RENAME`、Esc 取消，提交后复位
  （第 124 轮 2-2 的三个缺陷不得复现）。<待填：实现方字段名>
- ✕：二次确认 3 秒 → `ACTION_DELETE`；坐标编辑：6 个 `RsccNumberField` → `ACTION_REPLACE`（带名字）。
- 底部：「显示全部」（全部可用区域常亮 5 秒）/「关框」。
- 数据源：只读 `AdvancedAreaSnapshotPacket#lastFor(containerId)`；客户端不判断可用性、不读方块实体。
- 世界框生命周期：`removed()` 清 slot（含常驻框）。

### 2.4 标记编辑就地做（契约 §4.5，本轮新增）

- 侧栏行内「标记」按钮 / 双击行 ⇒ 侧栏下半部切「标记网格」：3 列 × 4 行、格子 18×18、
  上限 12、超出 `+N`；空格子主手添加；右键删（确认）；「清空」`ACTION_CLEAR`（确认）；
  格子渲染与 `AreaSelectorScreen#drawRowFilters` 同源。
- **JEI 拖入**（用户「没有 JEI」的正解）：`CollectionCacheJeiPlugin.MarkerGhostHandler#getTargetsTyped`
  在 `AdvancedCollectionCacheScreen` 且 `strip.isMarkGridOpen()` 时把目标切成侧栏格子，
  `accept()` 只调唯一接缝 `AreaStripPanel#acceptGhostIngredient(ItemStack)`；
  它发**第 125 轮新包** `network/AreaMarkAddItemPacket`（6 分量：
  `int containerId, Area area, ResourceLocation itemId, CompoundTag patch, int count, boolean fluid`）。
- **禁止**扩 `AreaMarkEditPacket` 线格式（仍是 5 参；第 5 参是**背包格下标**，JEI 幽灵栈不在背包
  ⇒ 走旧包永远静默丢包——这同时是「高级仓 48 格窗口 JEI 拖入一直没生效」的根因）。
- 退役范围：机器路径（`AdvancedCollectionCacheScreen` + `AreaStripPanel`）`new AreaMarkScreen`
  **0 次**；`AreaMarkScreen.java` 文件保留（主手世界路径仍可给单区域配标记）。
  `AreaSelectorScreen:821` 那处属于**主手路径**，不在退役范围（不要误报）。
- <待填：服务端 handler 是否顺带修掉 §4.6 的「48 格窗口新 id 被忽略」>

### 2.5 证据

- 自检 §2（7 个常量逐个 + 6 条几何表达式）、§3.1/§3.2（签名 + 六处转发 + 方法体非空）、
  §4（14 条行为）、§4.5（20 条：API / 网格常量 / 主手添加 / 删除/清空通道 / 新包 6 分量 /
  旧包 5 参 / JEI 分支 / 0 次 `new AreaMarkScreen` / 文件保留 / `RS_Create_Compat` 注册行）。
- 反例 <待填：C-1..C-38 里与本条相关的编号>：常量改数字 / 转发少一条 / keyPressed 不判 isEditing /
  removed 空实现 / 侧栏分支发旧包 / 新包少分量 / 旧包扩字段 / 删 `AreaMarkScreen` ⇒ 必须判负。
- 行为模型 / 截图 / mock：<待填>

---

## 3. ③ 一眼看出「当前哪个区域在干活」

- 行标记：左侧 3px 亮橙竖条 `0xFF9F1C` + 行底 `0x30FF9F1C` + 行首 `▶`；标题行 `<待填：当前名键>`。
- 常驻世界框：`AreaOutline.chase(AreaOutline.slot("strip/focus"), area)` 每帧重喂；
  关界面 `removed()` 清 slot（避免「关掉界面框还在」）。
- 证据：自检 §4 三条（颜色/▶、chase + slot 前缀、removed 清框）；反例 C-22 删 `AreaOutline.remove` ⇒ 判负。
- <待填：与旧「单显」的观感对比 / 用户复查结论>

---

## 4. ④ JEI 避让（零重叠，由 JEI 自己挪）

- `CollectionCacheJeiPlugin#registerGuiHandlers` 追加
  `registration.addGuiContainerHandler(AdvancedCollectionCacheScreen.class, ...)`；
  `getGuiExtraAreas` 返回 `AreaStripBounds.get()`（**屏幕坐标**），null ⇒ `List.of()`（绝不返回 null）。
- 语义：`IngredientListOverlay` 把该矩形算进占用 ⇒ 物品列表自动让位，**不是**我们手动挪 GUI。
- 证据：自检 §3.3 六条；反例 C-17（回 null）/ C-18（不注册）⇒ 判负。
- <待填：实机 JEI 左/右两侧布局截图>

---

## 5. 因本轮改动需要更新的旧自检锚点（透明登记）

> 完整清单（含行号 / 原断言 / 新口径 / 是否需改）见 **`docs/ROUND125_LEGACY_ANCHORS.md`**。
> 这里只登记「最终实际改了哪些」，格式照第 124 轮 §5。

| 脚本 | 谁改的 | 改了什么 | 原意是否放宽 |
| --- | --- | --- | --- |
| <待填：`selfcheck_round106_adv_cache_areas.py`> | <待填> | <待填：⑤-11 / ⑤-14 / `_KEYS` / ⑥-b8> | <待填：否> |
| <待填：`selfcheck_round101_collection_absorb.py`> | <待填> | <待填：`anchor_19` 的 `toggleSingleShow` / `toggleRange` 两行> | <待填：否> |
| <待填：`selfcheck_round90_area_selector.py`> | <待填> | <待填：⑦ 的 `range_button` 半句> | <待填：否> |
| <待填：`selfcheck_round99_collection_range.py`> | <待填> | <待填：③2 / ⑨4 的 `usesOwnRangeButton` / `rangeTriggerButton`> | <待填：否> |

全仓 grep 复核：<待填：确认没有其它脚本还锚着 `toggleSingleShow` / `range_button`（高级界面）/
`show.single` / 机器路径 `new AreaMarkScreen` 的具体行号；`AreaSelectorScreen` 保留类的锚点不动>。

---

## 6. 仍未实机验证（诚实清单）

1. **侧栏在真实屏幕分辨率下的自适应**：`avail < MIN_W` 收窄、`avail < HARD_MIN_W` 直接不画的两条
   分支是否在 1280×720 / 1920×1080 / GUI Scale 3-4 下观感正确（只有源码断言，没有真机截图）。
2. **JEI 真机避让**：`getGuiExtraAreas` 的矩形是否真的让 `IngredientListOverlay` 让位（左右两侧
   都要看；JEI 版本差异、侧栏收起/展开切换时的重算时机未测）。
3. **JEI 拖入侧栏格子**：拖拽高亮区是否对准格子、`acceptGhostIngredient` 的物品在服务端是否按
   `AreaMarks` 口径落盘并触发快照刷新（静态断言只能证明「发了新包」，不能证明「服务端写对了」）。
4. **改名/删除/坐标编辑的时序**：侧栏是消息驱动的自绘面板，`EditBox` 焦点与 `mouseClicked` 的
   先后、`keyPressed` 让位、切换行时提交旧行等，仍是源码级推演（第 124 轮 2-2 的同类问题要真机复测）。
5. **世界框 `strip/focus` 的生命周期**：关界面后框是否按 `removed()` 立刻消失（含 Esc / 切维度 /
   方块被破坏三种关闭路径）。
6. **`RS_Create_Compat` 注册行**：新包 payload 注册由 Lead 补，`gradlew compileJava` 前无法验证
   注册顺序 / 方向（C2S）是否正确。
7. **§4.6 既有 bug**：48 格窗口 JEI 拖入「新 id 被忽略」是否顺带修掉 <待填：修了/列进待办>。

---

## 7. 下一轮待办（承接 `ROUND122_USER_FEEDBACK.md` 第 8 节）

1. <待填：1-2 / 1-3 / 1-4 剩余条目>
2. <待填>
