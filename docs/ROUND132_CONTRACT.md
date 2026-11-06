# 第 132 轮契约（发送器小修 + 接收器改 RS 网格）

来源：用户 2026-11-06 语音反馈（m00008）+ 两张截图（图一 = 发送模式界面，图二 = 现接收器界面）。
用户原话要点：页签重叠、± 不改输入框、要 RS 原生数量子界面、面板字色怪、界面太长撞 JEI、订单界面不能放物品却还给物品区、
**接收器不要自己手写一套，按图一（发送器）那样用 RS 的网格界面重写再补功能**。

本文件是实施依据。实施者不得自行改方向；确实做不到的地方先报 Lead 并写进交付说明。

> **状态（2026-11-06 22:4x，实现完成）**：A–D 与「接收器改 RS 网格」已实现；E（去掉物品栏）按契约保留不做。
> 事实记录见 `docs/ROUND132_LANDING.md`；验证：自检 200/0、`LEAD_COMPILE_OK sources=526`、`gradlew build` 成功、
> jar `1.2.0-beta.33` 三处部署 `DEPLOY_VERIFY_OK`、专服启动 `Done (1.580s)` PASS。

## 一、发送器 ResourcePickerScreen（sender-polish 负责）

### A. 左侧 8 个模式页签与 RS 侧键重叠（反馈 1）
事实：RS 侧键由 `AbstractBaseScreen` 绘制：
- `AbstractBaseScreen.java:85-86` `getSideButtonX() { return leftPos - AbstractSideButtonWidget.SIZE - 2; }`
- `:81-83` `getSideButtonY() { return 6; }`，`:149-153` 每个侧键 `setX(getSideButtonX()); setY(topPos + sideButtonY); sideButtonY += height + 2;`
- `AbstractSideButtonWidget.SIZE = 18`
⇒ 电网格界面的侧键占 x ∈ [leftPos-20, leftPos-2]，从 topPos+6 起每 20px 一个（网格屏最多 7 个）。
现发送模式把 8 个模式页签也放在左侧 `x = guiLeft - TAB_W - 2`（TAB_W=24），正好压在侧键上。
要求：发送模式页签列整体再左移，公式 `x = leftPos - TAB_W - AbstractSideButtonWidget.SIZE - 4`（= leftPos-46；页签右缘 leftPos-22，和侧键左缘 leftPos-20 留 2px 空），`x < 2` 时退回 `x = 2`；纵向仍贴 GUI 底部向上排列。`TerminalModeTabOverlay` 里其它模式分支不要动。

### B. 订单数量 ± 不刷新输入框 + 换 RS 原生数量子界面（反馈 2、3）
- 删除 `ResourcePickerScreen` 里的 `amountBox` / `minusButton` / `plusButton`、`buildAmountControls()`、`applyAmount()`、`stepAmount()`、`parseAmount()`、`currentAmount()`、`amountLoadedKey`、`parsedAmount`、`AMOUNT_ROW_H` 相关布局与 tooltip（`renderAmountTooltip`）以及 `keyPressed` 里的 Enter→applyAmount 分支；面板行高相应回收（`amountY()`/`messageY()`/`panelHeight()` 跟着改，保证不越界）。
- 新增 `src/main/java/cretae/cookiewyq/rs_create_compat/client/grid/OrderAmountScreen.java`：
  `extends com.refinedmods.refinedstorage.common.support.amount.AbstractAmountScreen<AbstractAmountScreen.DefaultDummyContainerMenu, Long>`，
  `LongAmountOperations.INSTANCE`；
  构造 `(ResourcePickerScreen parent, long initial, long maxAmount, LongConsumer onConfirm)`：
  `super(new DefaultDummyContainerMenu(), parent, Minecraft.getInstance().player.getInventory(), Component.translatable("gui.rs_create_compat.resource_picker.order.amount"), configuration, LongAmountOperations.INSTANCE)`；
  `AmountScreenConfiguration<Long> configuration` 照抄 `ResourceAmountScreen`（`build\rssrc2\...\common\support\amount\ResourceAmountScreen.java`）：
  initialAmount、incrementsTop(1,10,64)、incrementsBottom(-1,-10,-64)、incrementsBottomStartPosition(7,72)、amountFieldPosition(9,51)、actionButtonsStartPosition(114,22)、minAmount 1、maxAmount 传进来的上限、reset 1；
  贴图 `refinedstorage:textures/gui/resource_amount.png`，`imageWidth=180`，`imageHeight=99`；`confirm(Long amount)` → `onConfirm.accept(amount)` 并返回 true（子界面自动关回 parent）。
- 打开入口：左键点击订单行（行区域）时仍执行选中；**双击或点击行内数量文字**打开数量子界面（推荐：行内 `amountX` 区域左键打开，非数量区左键只选中，避免误触）。打开时 `setScreen(new OrderAmountScreen(this, row.amount, availableMax(key), v -> sendSetAmount(index, v)))`。
- `availableMax`：拿不到就 `Integer.MAX_VALUE`；有网络可用量（`rows[i].amount` 或菜单资源）就用它，至少 1。
- 发回仍用 `RequesterOrderPacket` 的 `ACTION_SET_AMOUNT`（int 截断，>Integer.MAX_VALUE 用 Integer.MAX_VALUE），保留原行为。
- 结果：± 由 RS 子界面负责，输入框消失 ⇒ 反馈 2 自然消失。

### C. 该界面不显示 JEI（反馈 5）
新增 `src/main/java/cretae/cookiewyq/rs_create_compat/client/ResourcePickerJeiPlugin.java`：
`@JeiPlugin`，`IModPlugin.registerGuiHandlers` 里 `registration.addGuiContainerHandler(ResourcePickerScreen.class, handler)`，
handler 的 `getGuiExtraAreas(screen)` 返回 `List.of(new Rect2i(0, 0, screen.width, screen.height))`
⇒ JEI 的 `contents.hasRoom()` 变 false，列表整体收起。参考同工程 `client/CollectionCacheJeiPlugin.java:52-67` 的写法（那是给缓存界面留条）。
不要动 `CollectionCacheJeiPlugin`（避免写冲突）。

### D. 面板文字颜色（反馈 4）
事实：`McGui.panel` 用的是原版 sprites `minecraft:recipe_book/overlay_recipe`（`client/widget/McGui.java:20,50`），是深蓝半透明底；
现在 `COLOR_TEXT=0xFF404040`、`COLOR_TITLE=0xFF333333` 这种深灰字在深底上几乎看不见。
要求：面板/工具条上的文字换浅色（保持语义分区）：
- `COLOR_TITLE` → `0xFFFFE08A`（浅金，表头/总份数）
- `COLOR_TEXT` → `0xFFE8E8E8`（近白，行名/数量/工具条速率）
- `COLOR_HINT` → `0xFFAFC8E8`（浅蓝提示）
- `COLOR_WARN` → `0xFFFF8A8A`
- `COLOR_OK` → `0xFF8FE08F`
- `COLOR_FLUID` → `0xFF7FC8FF`
- `COLOR_DELETE` → `0xFFFF9A9A`
行选中底色 `COLOR_ROW_SELECTED=0x3300A0FF` 与 `COLOR_SELECTED_BORDER` 不变；网格里的键高亮（`COLOR_SELECTED_FILL/BORDER`）不变。

### E. 「订单界面不能放物品就不该有物品栏」（反馈 6，可选）
发送器菜单继承 RS 的 `AbstractGridContainerMenu`，玩家背包槽由 RS 添加；**本轮不允许替换菜单父类**（风险太大）。
如果实现成本低且不影响同步，可以把背包区视觉上收紧/去掉标题；否则在交付说明里明确写「未做，原因」。不要为此改菜单类型。

## 二、接收器改 RS 网格界面（receiver-grid 负责，反馈 7）

目标：图二界面（自己画的 9×7 格子 + 自绘贴图 + 自带滚动条 + 插件竖条）作废，改成和图一发送器同一套 RS `GridScreen` 基底：
搜索框、排序/视图侧键、滚动条、数量角标、槽位 tooltip 全由 RS 提供；**再补 extras**：接收器名称输入框 + 确认、6 个升级槽、状态/能量提示。

### 必做结构
1. `NetworkResourceReceiverMenu` 改为 `extends com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu`
   - 服务端构造 `(MenuType, int, Inventory, Grid)`、客户端构造 `(MenuType, int, Inventory, GridData)`；客户端 `read(int, Inventory, RegistryFriendlyByteBuf)` 用 `GridData.STREAM_CODEC` 解码。
   - 服务端 Grid 实现（建议内部类 `ReceiverGrid`，可参考 `client/grid/ResourcePickerMenu.java` 的 `BackendGrid`）：
     - `getResources(actorType)` = 缓存内容汇总：`itemCache` 按 `ItemResource` 聚合、`fluidCache` 按 `FluidResource` 聚合，返回 `List<TrackedResourceAmount>`；
     - `isGridActive()` = 主网络节点激活 && 方块实体 `isProperlyConfigured()`；
     - `getItemStorage()` 可返回缓存 storage（只读用途）；
     - `getAutocraftableResources()` = `Set.of()`；
     - `createOperations(ResourceType, ServerPlayer)` 返回「从缓存取出」的 `GridOperations`：`extract(resource, mode, destination)` 按 `GridExtractMode` 算量（ENTIRE=全部，HALF=一半，SINGLE=1）从缓存搬到 `destination`；`insert(...)` 一律 false（界面不能放入，用户原 tooltip 也这么写）；
     - `addWatcher/removeWatcher` 交给 `GridNetworkNode`；`canMenuStayOpen` 看方块实体还在且已加载。
   - **缓存变化必须能推到客户端**：`AbstractGridContainerMenu.onResourceUpdate(ResourceKey, long change, TrackedResource)` 是同步入口（`AbstractGridContainerMenu.java:168`）。方块实体在 doWork 输出/外部插入/界面取出后要通知打开中的菜单（例如网格对象自己维护 watcher 集合，方块实体持有当前打开的网格列表；或菜单 `broadcastChanges()` 里对账）。哪种方式都行，但必须实测「界面开着时缓存变化，格子数量会动」。
   - `quickMoveStack`、`stillValid`（`grid.canMenuStayOpen`）、`removed(Player)` 等按 RS 惯例实现；名称/升级/能量等 extras 同步继续走 `SyncReceiverConfigPacket`（可删掉其中的条目列表/流体快照字段，改由 GridData + onResourceUpdate 负责）。
2. `NetworkResourceReceiverScreen` 改为 `extends GridScreen<NetworkResourceReceiverMenu>`：
   - 删除旧自绘：`renderBg` 贴图、9×7 手绘格子、`renderAmountBadge`、自带 `ScrollbarWidget` 字段、`renderLabels` 里的网格部分、`UPGRADES_LABEL_X/Y` 竖排插件条；
   - extras 面板（名称输入框 + 确定按钮 + 6 个升级槽 + 状态文字/能量）画在 GUI 右侧或下方**屏幕内**，用 `McGui.panel` 或 RS 面板；不要超出屏幕，不要压在 GUI 外。
   - 网格点击交给 RS：左键 = 取一组/全部（按 RS 语义），右键 = 取一半，Shift = 全部/移到背包（`GridScreen`/`AbstractGridScreen` 已实现，取到光标或背包由 RS 的 `menu.onExtract` 走）。
   - 打开入口仍为方块 `useWithoutItem → player.openMenu`（`NetworkResourceReceiverBlock.java:145-164`），菜单类型注册沿用现有 `RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_MENU`；屏幕注册在 `client/ClientInit.java`。
   - 不在本轮处理 JEI（RS 会按网格界面规则显示，允许）。

### 明确不做 / 不做也要写清
- 不允许在界面放入物品（insert 全 false）。
- 旧 `ReceiverCacheActionPacket` 的 take/page 交互可保留、可删；如果删，服务端取物逻辑必须完整迁到 `GridOperations.extract`（含流体→光标/背包的处理，RS 有现成路径就复用）。
- 服务器行为（doWork 推送到相邻方块、外部机器插入、漏斗/溜槽推入）不得回退。

## 三、写作范围（谁改哪些文件，避免互相踩）

sender-polish 只改：
- `src/main/java/cretae/cookiewyq/rs_create_compat/client/grid/ResourcePickerScreen.java`
- `src/main/java/cretae/cookiewyq/rs_create_compat/client/grid/OrderAmountScreen.java`（新建）
- `src/main/java/cretae/cookiewyq/rs_create_compat/client/TerminalModeTabOverlay.java`
- `src/main/java/cretae/cookiewyq/rs_create_compat/client/ResourcePickerJeiPlugin.java`（新建）
- `tools/selfcheck_round132_sender.py`（新建）

receiver-grid 只改：
- `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/NetworkResourceReceiverScreen.java`
- `src/main/java/cretae/cookiewyq/rs_create_compat/menu/NetworkResourceReceiverMenu.java`
- `src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/NetworkResourceReceiverBlockEntity.java`（仅在需要通知网格时）
- `src/main/java/cretae/cookiewyq/rs_create_compat/network/SyncReceiverConfigPacket.java`、`ReceiverCacheActionPacket.java`（如需）
- `src/main/java/cretae/cookiewyq/rs_create_compat/client/ClientInit.java`（仅在注册变化时）
- `tools/selfcheck_round131_receiver.py`（改成新断言）、`tools/selfcheck_round132_receiver.py`（新建）

**语言文件（`src/main/resources/assets/rs_create_compat/lang/en_us.json` / `zh_cn.json`）由 Lead 统一合并。**
两位实施者不要直接编辑 lang；把需要的新 key 以清单形式写进交付说明（key = 英文文案 / 中文文案），Lead 负责加进两个 json 并编译。

## 四、验证与交付
- 编译自证：`pwsh -File tools\_lead_compile.ps1`，末行必须是 `LEAD_COMPILE_OK`（javac 真跑过才算）。并发跑没问题（argfile 按 PID 隔离）。
- 自检：`python tools/_run_all_selfchecks.py`，只看自己范围内；旧的 round131 断言若因本轮重构失效，改成新断言而不是删掉。
- 交付说明必须包含：改动文件清单、关键行为、语言 key 清单（中英）、编译末行、自检结论、没做到的点。
- 不要执行 git commit/push/tag，不要动 `build/` 之外的产物，不要改别人的文件。

## 五、参考坐标（发送器，已实测）
- 面板：`panelX = min(leftPos+197, ...)`，`PANEL_W=118`，`panelY=topPos+19`；行高 `ROW_H=11`；`rowsTop = filterY + FILTER_H + GAP`。
- 订单行含：焦点底色、8×8 半比例图标、名称、数量、右侧删除「×」；行内数量右对齐到 `deleteX()-2`。
- 界面高于屏幕时 `layoutToolbar` 置顶、面板只到屏幕底部（`layoutPanel` 已按 `height` 截断）。
