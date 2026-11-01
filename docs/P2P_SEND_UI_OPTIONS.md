# 「发送配置」界面方案对比与 API 调研

> 任务性质：**界面方案设计 + API 调研**。本文档只做调研与设计，**不含业务代码**，**未改动任何 Java 源码**，**未改动 `run/`**。
>
> 版本基线：Minecraft 1.21.1 / NeoForge 21.1.248，模组 `rs_create_compat`，依赖 Refined Storage **2.0.9**、Create **6.0.10**。
>
> 证据口径：所有「类名 + 文件:行号」中的 RS / Create 证据来自工作区内的第三方源码检出 `local_src/rs_src`（RS）与 `local_src/create_src`（Create）；本模组证据来自 `src/main/java`。凡**没有找到证据**的结论，本文一律写「**未证实**」，不做推测性断言。

---

## 0. 用户原话与诉求拆解

> 「还有一点就是说，这个**怎么发送**呢？这个地方**我没想好**。如果说是**新建一个方块**来专门搞这个东西，我**感觉多余了**。因为他的**职责除了加一个这种显示、选择什么、以及选择哪个机器**……**选择机器界面和之前的保持一致，支持搜索**，然后呢支持**功能选择**这样子的。除了多这个东西也没什么。但问题就是说，如果只是复用一下，比如说用什么 **mixin** 那些已经有的界面，我就感觉**不太好**。所以说你得**帮我想个主意**……**你帮我想几个好的方案，并且要有可以执行的那个方案，就是你要有 API 证据之类的。**」

拆成硬性判据（后文每个方案都要逐条回答）：

| 编号 | 判据 | 来源 |
|---|---|---|
| **C1** | **不为这个界面新增方块** | 用户原话「新建一个方块来专门搞这个东西，我感觉多余了」 |
| **C2** | **不用 mixin 硬塞进别人的界面** | 用户原话「用什么 mixin 那些已经有的界面，我就感觉不太好」 |
| **C3** | **选机器界面与既有的一致，且支持搜索** | 用户原话「选择机器界面和之前的保持一致，支持搜索」 |
| **C4** | **支持选资源**（物品 / 流体 / 气体）与**选功能** | 用户原话「显示、选择什么、以及选择哪个机器」「支持功能选择」 |
| **C5** | **要有可执行方案 + API 证据** | 用户原话「要有可以执行的那个方案，就是你要有 API 证据之类的」 |

### 0.1 一个必须先说清的事实：「网络资源接收器」在工程里**不存在**

- `grep` 全 `src/main/java` 搜 `接收器|Receiver|receiver`：**0 命中**（唯一相关命中在 `docs/DESIGN_DECISIONS_ROUND4.md:3228`，指的只是 RS 自己的 `network_*_brass` 方块，且注明「本任务不动」）。
- 因此本文无法假设「接收器」有确定的形态。本文给出两种**都被证据支持**的解读，并在推荐里指明各自适用哪一种：
  - **解读 A①**：接收器 = **RS 自己的 `NetworkReceiverBlock`**（`local_src/rs_src/com/refinedmods/refinedstorage/common/networking/NetworkReceiverBlock.java:31`）。它**没有界面、没有方块实体界面宿主**（`NetworkReceiverBlockEntity` 全文只有 50 行，不含 `createMenu` / `MenuProvider`：`local_src/rs_src/.../NetworkReceiverBlockEntity.java:19-50`）。
  - **解读 A②**：接收器 = **本模组要新建的一条 P2P 发送/接收功能**里的接收端（形态未定）。若接收端本身就要新方块（与「发送端」配对），那么**界面挂在接收器方块上并不违反 C1**——C1 禁的是「**专门为界面**再造一个方块」，不是禁止功能本身需要的方块。

> 这个歧义会直接改变推荐结论，见 §6「必须由用户拍板的点」。

---

## 1. ① 工程既有可复用件清单（类名 + 文件:行号）

### 1.1 既有的「选机器」界面（复用 C3 的核心）

| 类 | 路径:行 | 说明 |
|---|---|---|
| **`StepMachineSelectScreen`** | `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/StepMachineSelectScreen.java:36` | **就是「与之前保持一致 + 支持搜索」的那个界面**。`extends ChildConfigScreen`，面板 200×128。 |
| ├ 构造器（5 参） | 同上 `:78-82` | `(Screen parent, int globalStep, String recipeType, List<SyncChamberListPacket.Entry> candidates, String currentMachineName)` |
| ├ 构造器（6 参，**带确认回调**） | 同上 `:88-99` | 多一个 `@Nullable Consumer<SyncChamberListPacket.Entry> confirmSink`；**非空时点「确定」调它（由调用方自己发服务端包），而不是发终端包**——这是「跨场景复用同一个选择器」的现成扩展点。 |
| ├ **搜索框（自建）** | 同上 `:116-121` | `new EditBox(font, px+PAD, py+SEARCH_Y, WIDGET_W, BOX_H, ...)`，`setMaxLength(64)`、`setTextShadow(false)`、`setResponder(text -> applyFilter())`。 |
| ├ 过滤实现（不区分大小写子串） | 同上 `:138-152` | `applyFilter()`：`entry.name().toLowerCase(Locale.ROOT).contains(query)`，空查询 = 全部；过滤后仍尽量保持当前已指派项选中。 |
| ├ 选项控件 | 同上 `:106-114` | `ScrollSelectWidget<SyncChamberListPacket.Entry>`，`forOptions/titled/displaying/hinting/selectable` 链式配置。 |
| └ 「确定」的服务端出口 | 同上 `:165-180` | 5 参路径发 `SetStepMachinePacket`；6 参路径调 `confirmSink`。 |
| **`ScrollSelectWidget<T>`** | `src/main/java/cretae/cookiewyq/rs_create_compat/client/widget/ScrollSelectWidget.java:28` | **泛型**滚轮选择控件（交互照抄 Create 的 `SelectionScrollInput`，但不引用其任何类）。`setOptions` `:108`、`setState` `:114`、`getSelected` `:128`、`renderTooltip` `:220`、`inBounds` `:278`。**已经是泛型 ⇒ 换一种候选类型即可承载「选资源」**。 |
| **`ChildConfigScreen`** | `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/ChildConfigScreen.java:24` | 所有配置子窗口的基类：持有 `parent`、居中子面板、`returnToParent()` `:104-106`、`VANILLA_PANEL` 九宫格背景 `:41`、`customPanel()` 替换点 `:82`。**硬事实**：`renderBackground` 只做原版暗化、**不渲染父界面**（`:67-74` 与类注释 `:13-15`）。 |

**这个界面被两处复用的现存证据**（证明「同一个选择器跨场景复用」在本工程已是既成事实）：

1. `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/SequencePatternTerminalScreen.java:790-798`（`openMachineSelect`，点击行内机器控件横带 / Ctrl+左键整行）；
2. `src/main/java/cretae/cookiewyq/rs_create_compat/client/AssemblyAlertsClient.java:191-211`（`openMachineSelect`，自动合成监视器「更换机器」，传 `confirmSink`）；
3. `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AssemblyPatternRebindScreen.java:166-185`（「总样板改绑机器」，同样传 `confirmSink`）。

### 1.2 既有的「选资源」界面（C4）

工程里**没有**一个独立的、可被任意宿主复用的「资源选择界面」。现有的资源标记都是**嵌在各自宿主里**的形态：

| 形态 | 证据（类 + 行） | 搜索 | 说明 |
|---|---|---|---|
| **Ghost 标记槽 + JEI 拖拽** | `client/widget/GhostMarkerRenderer.java:46`（`ICON` `:48`、`toFluidStack` `:59`、`fluidName` `:72`、`renderFluid` `:262`、`renderFluidTooltip` `:118`、`displayItem` `:188`、`candidateItems` `:212`、`cycleCandidate` `:246`、`renderLines` `:300`） | 走 JEI 自己的搜索 | 本模组物品 / 流体 / 气体 ghost 槽的**唯一绘制 + 展示数据源**。 |
| JEI ghost 放置注册 | `client/QuantityKeeperJeiPlugin.java:30-32` + `MarkerGhostHandler` `:35-88`（物品 → `SetQuantityMarkerPacket` `:64`；流体 → `SetQuantityFluidMarkerPacket` `:71`） | JEI | 「从 JEI 拖进标记槽」的标准做法。 |
| 同上（归流缓存仓） | `client/CollectionCacheJeiPlugin.java:25`、`:42` | JEI | 支持物品 / 流体 / 气体。 |
| 同上（序列装配终端） | `client/SequenceTerminalJeiPlugin.java:34`、`:227`、`:244-275` | JEI | 物品（按菜单槽位下标）与流体（按**全局步骤下标**）。 |
| **带搜索框的配置子界面**（可作蓝本） | `client/screen/BusCategoryConfigScreen.java`（类注释 `:37`，`EditBox` 搜索） | ✅ 自建 | 2948 行；两级（配方标签页 + 页内分节）+ 搜索 + 多选 + 「确定 / 取消」，服务端权威（出口只有 `commitSink`，注释 `:117-118`）。**这是「带搜索的候选列表」在本工程最完整的既有实现。** |
| 其它带 `EditBox` 的界面 | `AdvancedQuantityKeeperScreen` / `ChamberBindingConfigScreen` / `CollectionMarkerConfigScreen` / `CollectionRangeConfigScreen` / `QuantityKeeperScreen` / `RangeChargerScreen` / `SequencePatternTerminalScreen` / `SequenceResultConfigScreen` / `UnitPatternConfigScreen` | 部分 | 用 `Select-String "EditBox"` 逐个文件确认过（见 §1.4 统计）。 |

**结论**：C4 的「选资源」**没有现成可复用的独立界面**，需要新写一个候选列表（可直接复用 `ScrollSelectWidget<T>` + `EditBox`，与 `StepMachineSelectScreen` 的搜索做法逐字同源），或者**复用 ghost 槽 + JEI 拖拽**（零新增 UI，但依赖 JEI）。

### 1.3 既有的「打开一个自定义界面」的标准做法（三段式）

工程里现在有**三条**并存的、都被证据支持的「开界面」链路：

#### 链路 1：方块右键 → 原生 `MenuProvider` → `RegisterMenuScreensEvent`

- `MenuType` 注册：`RS_Create_Compat.java:85`（`MENUS = DeferredRegister.create(Registries.MENU, MODID)`），随后 13 个 `MENUS.register(...)`（`RS_Create_Compat.java:177,195,214,232,251,270,279,298,317,340,360,384` 附近）。
- 带额外数据的菜单类型（客户端无法自行推导槽位数时）：`RS_Create_Compat.java:337-344`，用 `net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(...)`。
- 客户端界面挂载：`client/ClientInit.java:140-164`（`onRegisterMenuScreens(RegisterMenuScreensEvent)`，13 条 `event.register(...)`）。
- 方块侧入口：`block/QuantityKeeperBlock.java:75`、`:82-89`（`getMenuProvider` + `player.openMenu(state.getMenuProvider(level, pos), pos)`），同族还有 `RangeChargerBlock:75/120`、`SchematicLoaderBlock:76/83`、`AdvancedSchematicLoaderBlock:76/83`、`CollectionCacheBlock:77/84`、`IntermediateCacheBlock:87/94`、`SequencePatternTerminalBlock:77/84`、`SequenceAssemblyExecutorBlock:77/84`、`SequenceExecutionChamberBlock:227/234`、`AdvancedQuantityKeeperBlock:75/82`、`UnitPatternManagerBlock:115`。

#### 链路 2：**非方块**入口（手持物品 / 快捷键 / 指令）→ 服务端 `openMenu`

这一条**工程里已经用过多次**，正是「不想新增方块」时该走的路：

| 非方块入口 | 证据（类 + 行） |
|---|---|
| **手持物品右键 → 打开自己的菜单**（虚拟方块实体） | `item/AdvancedRemoteTerminalItem.java:383-413`（`openSequencePatternTerminal`：`new SequencePatternTerminalBlockEntity(BlockPos.ZERO, ...)` 当虚拟 BE，再 `player.openMenu(new SimpleMenuProvider(...))` `:405`） |
| 同上（单元样板管理舱模式） | `item/AdvancedRemoteTerminalItem.java:446-460` → `menu/UnitPatternManagerMenu.java:175-184`（`open(...)` 内 `player.openMenu(new SimpleMenuProvider(...), buf -> manager.getMenuData(entries).write(buf))`） |
| 同上（合成仓管理器模式） | `item/AdvancedRemoteTerminalItem.java:638-687`（`openReflectedMenu`：**RS 官方 `ExtendedMenuProvider`** 的匿名实现 `:647-685`，最后由 `Platform.INSTANCE.getMenuOpener().openMenu(player, provider)` `:686` 打开） |
| **快捷键**（默认 G）→ C2S 包 → 服务端找终端并打开 | `client/TerminalKeybinds.java:36`、`:42-59`、`:61-91`；服务端包 `network/OpenAdvancedRemoteTerminalPacket.java` |
| **指令打开界面** | `network/OpenChamberUnitsSummaryPacket.java:37`（`player.openMenu(new SimpleMenuProvider(...))`）、`network/CloseChamberUnitsSummaryPacket.java:36` |
| **物品右键空气 → 发 S2C「打开包」→ 客户端 `setScreen`**（**完全不经过 Menu**） | `item/SequenceAssemblyPatternItem.java:246-277`（`AssemblyPatternRebind.openFor(serverPlayer, hand)`）→ `item/AssemblyPatternRebind.java:104-137`（服务端组快照并 `PacketDistributor.sendToPlayer(player, new AssemblyPatternRebindOpenPacket(...))` `:135`）→ `network/AssemblyPatternRebindOpenPacket.java` → `client/ClientPayloadSink.java` → **`client/AssemblyPatternRebindClient.java:28-42`**（`open(packet)`：已开着就 `screen.update(packet)` `:34-37`；已在**别的**界面里就**不强行切屏** `:38-40`；否则 `minecraft.setScreen(new AssemblyPatternRebindScreen(packet, current))` `:41`） |

**关于 `IExtendedMenuProvider` / `MenuProvider` 的既有使用**：
- 原生 `MenuProvider` / `SimpleMenuProvider`：见上表，**大量使用**。
- **RS 的** `ExtendedMenuProvider` **已被使用**：`item/AdvancedRemoteTerminalItem.java:12`（`import com.refinedmods.refinedstorage.common.support.containermenu.ExtendedMenuProvider`）、`:647`。
- NeoForge 的 `IExtendedMenuProvider`：**未证实**——全 `src/main/java` 搜索未命中该类名（只命中了 RS 的 `ExtendedMenuProvider` 与 NeoForge 的 `IMenuTypeExtension`）。

#### 链路 3：服务端权威 + S2C 快照刷新（不改界面、就地 `update`）

- `client/screen/AssemblyPatternRebindScreen.java:116-129`（`update(packet)`：服务端每次写入成功后**重发同一份快照**，界面就地刷新，**不重开界面**——注释明说「避免把玩家正开着的『选择机器』子界面顶掉」）。

### 1.4 设置同步的既有做法（C4 的服务端权威部分）

- **包注册**：`RS_Create_Compat.java:650-1242`（`registerPayloads(RegisterPayloadHandlersEvent)`，`registrar.playToServer(...)` / `registrar.playToClient(...)`）。规模：`src/main/java/.../network/` 下 **98 个** `.java` 文件。
- **C2S 写入包范例**：`network/SetStepMachinePacket.java:19-53`（`record ... implements CustomPacketPayload`、`Type` + `StreamCodec.composite` `:23-30`、`handle(packet, player)` `:32-53`，**服务端复核后才写** `:41-47`，写完立刻回发全量快照 `:52`）。
- **C2S 通用确认包（可复用的回调形态）**：`network/AssemblyPatternStepMachinePacket.java`（`AssemblyPatternRebindScreen.java:181-183` 的出口）。
- **S2C 快照包范例**：`network/SyncChamberListPacket.java:23-68`（`Entry` 记录 `:25-33`、`Type` `:35`、`StreamCodec` `:37-41`、客户端缓存 `lastReceived` `:44-62`）。
- **服务端组快照的范例**：`item/AssemblyPatternRebind.java:362-380`（`chamberEntries(Network)`）与 `:331-360`（`stepEntries`）。
- **客户端侧统一入口**：`network/ClientPayloadHooks.java`（`default void openMachineSelect(...)` `:36`）+ `client/ClientPayloadSink.java:52-53`（**关键约定**：`playToClient` 的载荷类在**专用服务端也会被加载**，因此其静态字段与处理体不得引用 `net.minecraft.client.*`，见 `network/ClientPayloadHooks.java:7`、`network/RefreshCamouflagePacket.java:41,61`、`client/CamouflageShellDisplay.java:42`）。
- **客户端主动拉取（界面一打开就发只读请求）**：`mixin/client/AutocraftingMonitorScreenMixin.java:240`（`PacketDistributor.sendToServer(new RequestAssemblyAlertsPacket())`）、`:243`（`RequestShortageModePacket`）。
- **点击去重闸门（一次点击 = 一个包）**：`client/AssemblyAlertsClient.java:156-177`（`beginAction` / `isPending`）、`:214-216`（`clearPending`）。
- **界面侧的统一附属信息分层（工程硬规则）**：`client/tooltip/RsccTooltipLayers.java`（Shift / Alt 层；**界面不得自行判断修饰键**，见 `AssemblyPatternRebindScreen.java:90-95` 的注释）。
- **指令树范例（路线 4 用）**：`command/CompatCommands.java:97` 起（根 `rs_create_compat` + `autocrafter/blockcontent/supply/shortagemode/refill/reuse/diag/devlogs` 子指令），注册点 `RS_Create_Compat.java:1262-1263`（`RegisterCommandsEvent`）。

### 1.5 既有界面清单（用于「与之前保持一致」的对齐口径）

`client/screen/` 下 23 个界面类（行数为 UTF-8 读出的总行数）：

| 类 | 行数 | 有 `EditBox`（搜索/输入） |
|---|---|---|
| `BusCategoryConfigScreen` | 2948 | ✅ |
| `SequencePatternTerminalScreen` | 2482 | ✅ |
| `CollectionMarkerConfigScreen` | 789 | ✅ |
| `CollectionCacheScreen` | 1466 | — |
| `ChamberBindingConfigScreen` | 544 | ✅ |
| `AdvancedQuantityKeeperScreen` | 529 | ✅ |
| `UnitPatternManagerScreen` | 467 | — |
| `ChamberUnitsSummaryScreen` | 482 | — |
| `AdvancedSchematicLoaderScreen` | 392 | — |
| `QuantityKeeperScreen` | 382 | ✅ |
| `RangeChargerScreen` | 360 | ✅ |
| `UnitPatternConfigScreen` | 355 | ✅ |
| `ChamberFaceConfigScreen` | 347 | — |
| `SequenceExecutionChamberScreen` | 307 | — |
| `SchematicLoaderScreen` | 298 | — |
| `AssemblyPatternRebindScreen` | 274 | — |
| `StepMachineSelectScreen` | 249 | ✅ |
| `CollectionRangeConfigScreen` | 246 | ✅ |
| `SequenceResultConfigScreen` | 205 | ✅ |
| `IntermediateCacheScreen` | 173 | — |
| `SequenceAssemblyExecutorScreen` | 125 | — |
| `CollectionDeleteModeConfirmScreen` | 114 | — |
| `ChildConfigScreen`（基类） | 117 | — |

`Item` / `Block` 注册规模：`RS_Create_Compat.java` 内 `ITEMS.register` **14 处**、`BLOCKS.register` **45 处**（`Select-String` 计数）。

---

## 2. ② RS 2.0.9 的界面可扩展点调查结论

### 2.1 ✅ 有：`AbstractBaseScreen#addSideButton` —— **侧边按钮的唯一官方挂载入口**

```java
// local_src/rs_src/com/refinedmods/refinedstorage/common/support/AbstractBaseScreen.java:147-153
public void addSideButton(final AbstractSideButtonWidget button) {
    button.setX(getSideButtonX());
    button.setY(topPos + sideButtonY);
    exclusionZones.add(new Rect2i(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
    sideButtonY += button.getHeight() + 2;
    addRenderableWidget(button);
}
```

- 方法签名是 **`public`**（不是 `protected`）⇒ **任何**持有屏幕实例的代码都能调用。
- 坐标系统是官方提供的、可覆写的：`getSideButtonY()` `:80-82`、`getSideButtonX()` `:84-86`（`leftPos - AbstractSideButtonWidget.SIZE - 2`）；`init()` 里 `sideButtonY = getSideButtonY()` `:92`；`clearWidgets()` 里清空 `exclusionZones` `:96-99`。RS 自己就在 `AutocraftingMonitorScreen.java:115` 与 `PortableGridScreen.java:35-36` 覆写它们。
- 按钮基类：`AbstractSideButtonWidget extends Button`，`SIZE = 18`（`local_src/rs_src/.../support/widget/AbstractSideButtonWidget.java:27-28`），需实现 `getSprite()` `:43` / `getTitle()` `:110` / `getSubText()` `:112`；`buildTooltip()` `:93-108` 自动带上 `getHelpText()` `:114-117`。
- **本工程已经在用这个点**（以 mixin 做「不挂」）：`mixin/client/AbstractBaseScreenMixin.java:83-90`（`@Inject(method = "addSideButton", at = @At("HEAD"), cancellable = true)`，对 `ImporterScreen` 屏蔽 `FilterModeSideButtonWidget` / `FuzzyModeSideButtonWidget`）。注释 `:74-78` 明确写了 addSideButton 是「挂按钮的**唯一入口**」。

> **但要注意一个硬事实**：`AbstractBaseScreen` 是**别人的界面类**。要让**RS 自己的界面**出现我们的侧边按钮，只有两条路：
> 1. **mixin 进 `AbstractBaseScreen`**（本工程已经这么干过一次，见上）——**违反 C2**；
> 2. 把该 RS 界面**整体替换成我们自己的子类**（需要接管 `MenuType → Screen` 的映射）——**做不到**，见 §2.2。
>
> 换句话说：**`addSideButton` 是「官方扩展点」，但它只对「我们自己写的、继承 `AbstractBaseScreen` 的界面」是干净可用的**；对 RS 既有界面的注入仍然必须 mixin。

### 2.2 ❌ 没有：RS 2.0.9 **不提供**给附属注册界面 / 替换 `MenuType→Screen` 的公开 API

- RS 自己的界面注册是**内部静态方法**，而非 API 包里的注册表：

```java
// local_src/rs_src/com/refinedmods/refinedstorage/common/AbstractClientModInitializer.java:80-138
protected static void registerScreens(final ScreenRegistration registration) { ... }

// 同文件 :210-215
@FunctionalInterface
public interface ScreenRegistration {
    <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void register(
        MenuType<? extends M> type, ScreenConstructor<M, U> factory);
}
// 同文件 :217-221
@FunctionalInterface
public interface ScreenConstructor<T extends AbstractContainerMenu, U extends Screen & MenuAccess<T>> {
    U create(T menu, Inventory inventory, Component title);
}
```

  - `registerScreens` 是 **`protected static`**，只能由 `AbstractClientModInitializer` 的子类调用；`ScreenRegistration` 是它的**嵌套接口**，且 `AbstractClientModInitializer` **不在** `com.refinedmods.refinedstorage.common.api` 包内 ⇒ **未进入 RS 公开 API 面**。
  - NeoForge 侧唯一的调用点：`local_src/rs_src/com/refinedmods/refinedstorage/neoforge/ClientModInitializer.java:111`（`registerScreens(new ScreenRegistration() {...})`）。
- **`RefinedStorageClientApi`（客户端公开 API，`@API(status = STABLE)`）里没有任何界面注册 / 界面注入入口**，全文只有 6 类能力：`createStorageBlockScreen` / `openAutocraftingPreview` / `registerResourceRendering` / `getResourceRendering` / `addAlternativeGridInsertionHint` / `getGridInsertionHints` / `registerDiskModel` / `getDiskModels*`（`local_src/rs_src/com/refinedmods/refinedstorage/common/api/RefinedStorageClientApi.java:24-49`）。
- **`RefinedStorageApi`（服务端公开 API）里也没有**界面 / 屏幕相关的注册入口（`.../api/RefinedStorageApi.java:77-236` 全文核对：只有存储类型、策略工厂、网络节点容器、资源工厂、升级、权限等）。
- **RS 内部也没有「附属可注册自己的界面区块」的机制**：搜索 `register.*Screen` / `ScreenRegistry` / `register.*Section` / `addon|Addon` 在 `local_src/rs_src` 全树只命中上面这些内部方法；`Grid.getResources(...)` / `RefinedStorageApi.INSTANCE.getGridResourceRepositoryMapper()` 是**数据**扩展点（`.../api/grid/Grid.java:20-36`、`RefinedStorageApi.java:122-125`），不是界面扩展点。

> **结论（明确说「没有」）**：RS 2.0.9 **没有**「在 RS 自己的界面里加一个按钮打开附属界面」的**非 mixin** 干净做法。唯一的官方挂载点 `addSideButton` 只对「自己写的 `AbstractBaseScreen` 子类」可用，而**把 RS 既有界面换成一个我们自己的子类**再往里 `addSideButton` —— **未证实可行**（需要替换 `MenuType → Screen` 的映射，而该映射表没有公开注册入口，见上）。

### 2.3 「样板 / 自动合成相关界面里给附属留区块」的机制

- **未证实**存在。`local_src/rs_src` 全树搜索未命中任何 `registerSection` / `addSection` / 附属区块注册表；`AutocrafterManagerScreen` 的分组 / 视图切换（`local_src/rs_src/.../autocraftermanager/AutocrafterManagerScreen.java:95-98` 的 `addSideButton(...)`）都是 RS 内部写死的。
- 本工程对 RS 合成仓管理器的扩展走的是 **mixin + accessor**（`mixin/VirtualAutocrafterManagerMenuMixin.java`、`mixin/accessor/*Accessor.java` 共 7 个 accessor），也印证了「没有干净扩展点」这一结论。

### 2.4 服务端「打开任意 MenuProvider」的公开入口：`Platform.INSTANCE.getMenuOpener()`

```java
// local_src/rs_src/com/refinedmods/refinedstorage/common/Platform.java:53-56
public interface Platform {
    MenuOpener getMenuOpener();

// local_src/rs_src/com/refinedmods/refinedstorage/common/support/containermenu/MenuOpener.java:6-8
public interface MenuOpener {
    void openMenu(ServerPlayer player, MenuProvider menuProvider);
}
```

本工程已使用：`item/AdvancedRemoteTerminalItem.java:686`（`Platform.INSTANCE.getMenuOpener().openMenu(player, provider)`）。
**注意**：`Platform` 不在 `common.api` 包内 ⇒ 它是**内部但公开可达**的类型，属于「能用、但升级时要盯」的一档。

---

## 3. ③ Create 侧可借鉴的做法（类名 + 文件:行号）

### 3.1 `RedstoneRequesterScreen`：**自己的方块 + 自己的菜单 + 自己的界面**，没有复用任何通用选择界面

- `local_src/create_src/com/simibubi/create/content/logistics/redstoneRequester/RedstoneRequesterScreen.java:31`（`extends AbstractSimiContainerScreen<RedstoneRequesterMenu>`）。
- 资源（9 个请求条目）靠 **ghost 槽 + 数量滚轮**，不是选择列表：`:57-59`（读 `menu.ghostInventory`）、`:181-192`（在 9 个 16×16 格上 `mouseScrolled` 改数量，Shift ×10，夹到 1..256）、`:130-141`（`renderItemDecorations` 画数量）。
- 目标（地址）靠 **`AddressEditBox` 文本输入**，不是「选机器」：`:72-77`（`new AddressEditBox(this, new NoShadowFontWrapper(font), x+55, y+68, 110, 10, false)`）、`:143-170`（手动 tooltip）。
- 配置在**关闭界面时**整体回传服务端：`:226-231`（`removed()` → `CatnipServices.NETWORK.sendToServer(new RedstoneRequesterConfigurationPacket(pos, addressBox.getValue(), allowPartial.green, amounts))`）。
- 菜单基类：`.../RedstoneRequesterMenu.java:25`（`extends GhostItemMenu<RedstoneRequesterBlockEntity>`）。

### 3.2 Create **没有**通用的「选择物品 / 流体」通用界面

盘点结果（`Select-String` 全树）：

| 类 | 路径:行 | 性质 |
|---|---|---|
| `AbstractSimiContainerScreen<T>` | `local_src/create_src/com/simibubi/create/foundation/gui/menu/AbstractSimiContainerScreen.java:37` | Create 界面基类（`getExtraAreas()` `:177`、`renderPlayerInventory` `:143`）。 |
| `MenuBase<T>` | `.../foundation/gui/menu/MenuBase.java:15` | 菜单基类（`contentHolder` `:19`、`removed` `:62`、`stillValid` `:68`）。 |
| `GhostItemMenu<T>` | `.../foundation/gui/menu/GhostItemMenu.java:13`（`ghostInventory` `:15`、`clicked` `:53`、`quickMoveStack` `:91`） | 通用 **ghost 槽菜单**基类。 |
| **`HeldItemGhostItemMenu`** | `.../foundation/gui/menu/HeldItemGhostItemMenu.java:17`（`stillValid` `:46-48` 要求主手仍持有该物品；`clicked` `:33` 阻止取走宿主持有物；`isInSlot` `:50-53`） | **「手持物品」形态的 ghost 槽菜单基类** —— 这是「不新增方块就能有容器菜单」的现成基类。 |
| `GhostItemSubmitPacket` | `.../foundation/gui/menu/GhostItemSubmitPacket.java:15-16`（`record (ItemStack item, int slot)`） | Create 的 ghost 提交包。 |
| `SelectionScrollInput` | `.../foundation/gui/widget/SelectionScrollInput.java:12` | 滚轮选择控件（本工程的 `ScrollSelectWidget` 注释里已声明「交互照抄它，但不引用其任何类」，见 `ScrollSelectWidget.java:19`）。 |
| `StockKeeperRequestScreen` | `.../logistics/stockTicker/StockKeeperRequestScreen.java:88` | Create 里**唯一**带「物品搜索 + 候选列表」的请求界面：`searchBox = new EditBox(...)` `:231-235`、`refreshSearchResults(...)` `:269-...`（含 `@mod` / `#tag` 前缀搜索 `:330-361`）。**它也是自己的方块 + 自己的菜单**。 |
| `ValueSettingsScreen` | `.../foundation/blockEntity/behaviour/ValueSettingsScreen.java:31`（`extends AbstractSimiScreen`，`ValueSettingsBoard` `:34`、构造 `:46`） | 数值/选项设置面板，宿主是 `ValueSettingsBehaviour`（`.../ValueSettingsBehaviour.java:20`：**`interface ValueSettingsBehaviour extends ClipboardCloneable`**，`createBoard(...)` `:41`）+ `ValueSettingsClient` 打开（`.../ValueSettingsClient.java:82-100`，`ScreenOpener.open(new ValueSettingsScreen(...))`）。 |
| `PackagePortTargetSelectionHandler` | `.../logistics/packagePort/PackagePortTargetSelectionHandler.java:27`（`onUse()` `:50-72` → 必须 `mc.level.getBlockEntity(pos) instanceof StationBlockEntity`；`flushSettings(pos)` `:33-48` → `CatnipServices.NETWORK.sendToServer(new PackagePortPlacementPacket(...))`） | **Create 的「选目标」是在世界里用扳手/手持物瞄准**，不是 GUI 列表。 |

**结论**：Create 6.0.10 **没有**可直接复用的「通用选物品 / 流体界面」；`RedstoneRequesterScreen` 与 `StockKeeperRequestScreen` 都是**自家方块 + 自家菜单 + 自家界面**。可借鉴的是**基类**（`AbstractSimiContainerScreen` / `GhostItemMenu` / `HeldItemGhostItemMenu`）与**控件**（`SelectionScrollInput`），而不是界面本身。

### 3.3 `ClipboardCloneable` / `ValueSettings` 能否承载「目标 + 资源」

- 接口契约（`local_src/create_src/com/simibubi/create/content/equipment/clipboard/ClipboardCloneable.java:10-16`）：

```java
public interface ClipboardCloneable {
    String getClipboardKey();
    boolean writeToClipboard(@NotNull HolderLookup.Provider registries, CompoundTag tag, Direction side);
    boolean readFromClipboard(@NotNull HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side, boolean simulate);
}
```

- Create 的分发器要求宿主是 `SmartBlockEntity`：`.../clipboard/ClipboardValueSettingsHandler.java:155-168`（`interact(...)` 里 `if (!(world.getBlockEntity(pos) instanceof SmartBlockEntity smartBE)) return;`）。
- 剪贴板载荷的存放位置：`ClipboardContent.copiedValues()`（`.../clipboard/ClipboardContent.java`），Create 自己的读写见 `ClipboardValueSettingsHandler.java:232-296`（`tag.getCompound(cc.getClipboardKey())` / `tag.put(clipboardKey, compoundTag)` `:244-268`）。
- **本工程已经用同一份数据格式 + 同一套手势，但自己写挂点**：`support/RsccBusClipboard.java:70`（`@EventBusSubscriber`）、`:76`（`CLIPBOARD_KEY = MODID + ":bus_config"`）、`:107-145`（右键复制）、`:157-196`（左键粘贴，潜行 = 整簇）、`:204-213`（`payloadOf`：读 `AllDataComponents.CLIPBOARD_CONTENT` 的 `copiedValues`）、`:220-227`（`writeToClipboard`：**只替换本模组那一段**，其余段落原样保留）、`:285-304`（`pasteCluster`：同簇同种、上限 `BULK_MAX_BUSES = 64` `:84`）。类注释 `:36-42` 明确写了「**为什么本类要自己写一份挂点（不能直接实现 Create 的 `ClipboardCloneable`）**：要求宿主是 `SmartBlockEntity`，而 RS 的方块实体继承 RS 自己的体系，结构上不可能」。

**结论**：`ClipboardCloneable` 的**数据格式与手势**可以承载「目标 + 资源」（就是一个 `CompoundTag`，键用 `getClipboardKey()` 命名空间化），但**不能直接实现该接口**（宿主不是 `SmartBlockEntity`）；要照 `RsccBusClipboard` 的做法**自己写事件挂点**。`ValueSettingsBehaviour extends ClipboardCloneable`（`ValueSettingsBehaviour.java:20`）同理：它需要 Create 的 `BlockEntityBehaviour` 体系 + `SmartBlockEntity` 宿主。

---

## 4. ④ 方案对比表

**评分口径**：✅ 满足 / ⚠️ 有条件 / ❌ 不满足。C1 = 不新增方块；C2 = 不用 mixin 塞别人的界面；C3 = 选机器与现有一致且可搜索。

| # | 方案（一句话） | C1 不新增方块 | C2 不 mixin 别人界面 | C3 复用现成可搜索选机器 | 入口（谁打开界面） | 服务端权威 | 实现量级（估） | 主要风险 |
|---|---|---|---|---|---|---|---|---|
| **1** | **手持物品右键空气 → 服务端发「打开包」→ 复用 `StepMachineSelectScreen` + 新增选资源** | ✅ | ✅ | ✅ **直接复用** | 物品（`use()` 右键空气） | 物品 `CustomData` 或服务端；C2S 写入包 + S2C 快照 | **中**：新 1 界面 + 1~2 包；`StepMachineSelectScreen` 泛型化 3~4 文件 | 与 `StepMachineSelectScreen` 的既有 3 个调用点耦合；候选列表需换类型 |
| **2** | **界面挂在「接收器」方块上**（承认需要界面宿主，但宿主是**功能本身要的方块**） | ⚠️（若该方块是功能必需 ⇒ ✅；若仅为界面 ⇒ ❌） | ✅ | ✅ 复用 | 方块右键（标准 `MenuProvider`） | 方块实体 NBT（工程最成熟的一条） | **中**：新 Menu + MenuType + Screen + 注册 5 处 | 与 C1 的字面冲突（需用户确认「功能必需」） |
| **3** | **在 RS 既有界面加官方 side button** | ✅ | ❌ **必须 mixin `AbstractBaseScreen`** | ✅ | RS 界面里的侧边按钮 | 与方案 1 同 | 小（1 个 `AbstractSideButtonWidget` 子类 + 1 个 mixin 注入点） | **违反 C2**；且 RS 升级时 mixin 目标可能漂移 |
| **4** | **指令 + 剪贴板（无 GUI）** | ✅ | ✅ | ⚠️ 无图形搜索（只有指令 Tab 补全 / 剪贴板粘贴） | `/rs_create_compat ...` / 剪贴板 | 指令 = 服务端原生；剪贴板 = 复用 `RsccBusClipboard` 事件挂点 | **小**：1 条子指令树 + 1 个 `RsccXxxClipboard` | 没有「选」的体验；不适合「看清单挑一台」 |
| **5** | **（本调研新增）方案 1 的强化形态：界面挂在**既有**网络发送器 / 既有终端上；界面本体做成「宿主无关的子界面」** | ✅ | ✅ | ✅ 复用 | 发送器方块右键 / 终端模式 Tab / 物品右键 | 与方案 1 同 | **中**：方案 1 + 宿主适配层 | 需要与发送器的既有 `NetworkTransmitterContainerMenu`/`Screen` 共存（不得抢占其原生行为） |

---

## 5. ④ 逐个详述（做法 / 代价 / 风险 / API 证据 / 结论）

### 方案 1：**复用「总样板改机器绑定」那套**（手持物品右键空气 → S2C 打开包 → 复用 `StepMachineSelectScreen`）

#### 做法
1. **入口**：给「发送配置」一个**物品侧**的持有点。两种子形态：
   - **1a**：复用**已有的**总样板 / 单元样板物品（不新增任何物品）；
   - **1b**：新增一个**物品**（注意：新增物品**不是**新增方块，不违反 C1）作为「发送配置卡」。
   `use(...)` 里判 `player instanceof ServerPlayer` 后调用一个服务端类（照 `SequenceAssemblyPatternItem.use` → `AssemblyPatternRebind.openFor` 的形状）。
2. **服务端**：照 `AssemblyPatternRebind.openFor` 组一份**权威快照**（可选目标列表 + 当前配置），`PacketDistributor.sendToPlayer(player, new <我们的>OpenPacket(...))`。
3. **客户端**：照 `AssemblyPatternRebindClient.open(packet)` 的做法（`client/AssemblyPatternRebindClient.java:28-42`）—— 已开着我们的界面就 `screen.update(packet)` **就地刷新**（`:34-37`）、玩家正开着**别的**界面就**不强行切屏**（`:38-40`）、否则 `Minecraft.getInstance().setScreen(new <我们的>SendConfigScreen(packet, parent))`（`:41`）。
4. **界面结构**（建议）：
   - 上半：**选目标** —— 直接 `new StepMachineSelectScreen(this, stepIndex, recipeType, candidates, currentName, confirmSink)`，**搜索框现成**（`StepMachineSelectScreen.java:116-121` + `applyFilter` `:138-152`）；
   - 下半：**选资源** —— `new ScrollSelectWidget<ResourceEntry>(...)`（`ScrollSelectWidget.java:28`，**泛型**）+ 一个 `EditBox` 作搜索（与 `StepMachineSelectScreen` 的搜索做法逐字同源）；
   - 或者「选资源」改成 **ghost 槽 + JEI 拖拽**（复用 `GhostMarkerRenderer` `:46` 与 `QuantityKeeperJeiPlugin` `:30-32` 的注册方式）——**零新增 UI，JEI 自带搜索**，且物品 / 流体 / 气体三种都能拖（`MarkerGhostHandler` `:44-48`）。
   - 「选功能」：`ScrollSelectWidget<Mode>` 或两个 `Button`（模式枚举）。
5. **写入**：确认 → C2S 包（照 `SetStepMachinePacket.java:19-53` 的 record + `StreamCodec.composite` + `handle` 服务端复核）→ 服务端写入 → **回发同一份快照**（照 `AssemblyPatternRebindScreen.update` `:116-129` 的就地刷新）。

#### 要不要新增方块？
**不要。** 入口是物品（`Item#use`）+ S2C 打开包，全链路不经过任何方块。

#### 要不要 mixin 进别人的界面？
**不要。** 全程是我们自己的 `Screen`（`ChildConfigScreen` 子类）+ 我们自己的包。

#### 搜索能力从哪来？
**复用现成的。** 选机器部分 = `StepMachineSelectScreen` 自带的 `EditBox` + `applyFilter`（`:116-152`）；选资源部分 = 照同一套写法（`ScrollSelectWidget` + `EditBox`），或直接交给 JEI 的搜索框。

#### 服务端权威怎么做？
- **配置存哪**：优先**物品侧**（`DataComponents.CUSTOM_DATA`），因为工程已有成熟先例：`item/AssemblyPatternRebind.java:107`（`fingerprint(held)` 用物品 UUID 作权威指纹）、`item/AdvancedRemoteTerminalItem.java:394-404`（读 `CUSTOM_DATA` 的 `TAG_SEQUENCE_TERMINAL`）、`:421-435`（关闭界面时写回物品 NBT）。
- **怎么同步**：S2C 打开包（一次性快照）+ 每次写入成功后**重发同一份快照**（`AssemblyPatternRebindScreen.java:116-129`）；界面一打开可另发一个只读请求包补拉（照 `AutocraftingMonitorScreenMixin.java:240`）。
- **客户端不写任何权威状态**：所有写入都是 C2S 包，服务端复核（照 `SetStepMachinePacket.java:41-47`）。

#### 实现量级（改哪些类、约多少处）
| 项 | 文件数（估） | 说明 |
|---|---|---|
| 新增界面 | 1 | `<我们的>SendConfigScreen extends ChildConfigScreen` |
| 新增网络包 | 2~3 | Open（S2C）+ Set（C2S）+（可选）Request（C2S 只读） |
| `registerPayloads` 注册 | 1 处（文件内 2~3 行） | `RS_Create_Compat.java:650` 起 |
| `StepMachineSelectScreen` 泛型化 | 3~4 | 见下「风险」① |
| 服务端配置写入 / 校验 | 1~2 | 照 `AssemblyPatternRebind` 的形状 |
| 语言键 | 1 | `assets/rs_create_compat/lang/*.json` |
| **合计** | **约 8~11 个文件** | **中等** |

#### 风险
1. **`StepMachineSelectScreen` 与它的候选类型强耦合**：字段类型是 `List<SyncChamberListPacket.Entry>`（`:64`）、`confirmSink` 是 `Consumer<SyncChamberListPacket.Entry>`（`:72`）。要承载「发送目标」需要：
   - 要么**新增一个同形状的 `Entry` 记录 + 新包**（`SyncChamberListPacket.Entry` 只有 `(pos, name, recipeType)`，`:25`，语义上是「执行舱 + 配方类型」，用于发送目标语义不合）；
   - 要么把它**泛型化** → 会牵动 3 个既有调用点：`SequencePatternTerminalScreen.java:797`、`AssemblyAlertsClient.java:202`、`AssemblyPatternRebindScreen.java:176`。**这是本方案最大的回归风险面**（工程有「不要写两套」的既有约定，见 `StepMachineSelectScreen.java:26`）。
2. **`ChildConfigScreen` 不渲染父界面**（`ChildConfigScreen.java:13-15,67-74`）⇒ 我们的界面**不能**「叠在 RS 终端上」；它是独立屏或独立子屏。
3. **1.21.1 的 `useWithoutItem` 消费坑**：以**右键方块**为入口时，`ItemStack#useOn` 可能永远走不到（`SequenceAssemblyPatternItem.java:249-256` 有完整记录）。**必须用「右键空气」**（`AssemblyPatternRebind.java:90-91`）。
4. **物品侧持有点的歧义**：玩家手里可能有多件同类物品 ⇒ 需要指纹（UUID）与「同一只手」的约束（`AssemblyPatternStepMachinePacket` 的 `handOrdinal` + `patternId` 就是为此）。

#### API 证据
- 入口：`item/SequenceAssemblyPatternItem.java:246-277`、`item/AssemblyPatternRebind.java:104-137`
- 打开：`network/AssemblyPatternRebindOpenPacket.java`、`client/AssemblyPatternRebindClient.java`、`client/ClientPayloadSink.java`
- 界面：`client/screen/AssemblyPatternRebindScreen.java:37,70-108,116-129,166-185`
- 选择器：`client/screen/StepMachineSelectScreen.java:36,78-99,116-121,138-152,165-180`
- 控件：`client/widget/ScrollSelectWidget.java:28,63-103,108,128,220,278`
- 包：`network/SetStepMachinePacket.java:19-53`、`network/SyncChamberListPacket.java:23-68`
- 注册：`RS_Create_Compat.java:650-1242`

#### 结论
**能满足 C1 / C2 / C3，是「不用新方块也不用 mixin」的**最直接**路径。** 唯一需要正面处理的是「泛型化 `StepMachineSelectScreen`」这件事；若不愿碰既有的 3 个调用点，**替代做法是在方案 1 里新写一个同源的 `SendTargetSelectScreen`**（把 `StepMachineSelectScreen` 当**蓝本**照抄，而不是改它）——代价是「两套搜索选择器」，与工程既有约定（`StepMachineSelectScreen.java:26`「不要写两套」）相冲突，需要用户拍板。

---

### 方案 2：**给「网络资源接收器」自己配一个界面**（承认需要一个界面宿主，但宿主是功能本身要的方块）

#### 做法
1. 接收器方块实现原生 `MenuProvider`（`getMenuProvider(state, level, pos)` + `player.openMenu(provider, pos)`），照 `block/QuantityKeeperBlock.java:75,82-89` 或 `block/SequencePatternTerminalBlock.java:77,84-91`。
2. `MenuType` 用 `MENUS.register(...)`，若槽位数依赖服务端数据则用 `IMenuTypeExtension.create(...)`（照 `RS_Create_Compat.java:337-344`）。
3. `RegisterMenuScreensEvent` 里挂 Screen（照 `client/ClientInit.java:140-164`）。
4. Screen 里**选目标**部分仍复用 `StepMachineSelectScreen`（作为子界面弹出，`parent = this`），**选资源**部分照方案 1。

#### 要不要新增方块？
**取决于「接收器」是不是功能本身必需的方块。**
- 若接收器**本来就是**这条 P2P 功能要新增的方块 ⇒ **不算「为界面新增方块」**，C1 实际满足；
- 若接收器**只是**为了给界面找个宿主 ⇒ **违反 C1**。
> 这是本文**必须由用户拍板**的第一件事（见 §6）。

#### 要不要 mixin 进别人的界面？
**不要。** 是我们自己的方块 + 我们自己的菜单 + 我们自己的界面。

#### 搜索能力从哪来？
与方案 1 同（复用 `StepMachineSelectScreen` / `ScrollSelectWidget`）。

#### 服务端权威怎么做？
**工程最成熟的一条**：配置存在方块实体的 NBT 里（`saveAdditional` / `loadAdditional`），同步走 `ContainerData`/`Property` 或 S2C 快照包 + C2S 写入包（`SetQuantityMarkerPacket` / `SyncQuantityFluidMarkerPacket` 同族，共 98 个包可参照）。

#### 实现量级
| 项 | 处数（估） |
|---|---|
| `MenuType` 注册 | 1 |
| `Menu` 类 | 1 |
| `Screen` 类 | 1 |
| 方块 `getMenuProvider` + `use` | 2 |
| `ClientInit.onRegisterMenuScreens` | 1 |
| 网络包（如需） | 1~2 |
| **合计** | **约 5~7 个文件** |

#### 风险
1. **与 C1 的字面冲突**（若接收器并非功能必需）。
2. 与 RS 的 `NetworkReceiverBlock` 的关系需要厘清：**RS 自己的接收器没有界面**（`NetworkReceiverBlockEntity.java:19-50` 无 `createMenu`）⇒ 若想「把界面加到 RS 的接收器上」，就必须 mixin（违反 C2）。**因此本方案的「接收器」必须是我们自己的方块**。
3. 方块实体 + 菜单的样板代码多，且本工程已有 45 个方块注册，继续加会增加长期维护面。

#### API 证据
- 方块开界面：`block/QuantityKeeperBlock.java:75,82-89`、`block/SequencePatternTerminalBlock.java:77,84-91`
- 菜单类型：`RS_Create_Compat.java:177-390`（13 个）、`RS_Create_Compat.java:337-344`（`IMenuTypeExtension`）
- 界面挂载：`client/ClientInit.java:140-164`
- RS 接收器无界面：`local_src/rs_src/.../networking/NetworkReceiverBlockEntity.java:19-50`
- 服务端权威样板：`menu/QuantityKeeperMenu.java:122`（JEI/包写入）、`block/entity/QuantityKeeperBlockEntity.java:266,314`（设置标记）

#### 结论
**技术上最稳妥、代码最"正统"，但与 C1 的相容性完全取决于「接收器是不是功能必需的方块」这一个前提。**在用户确认之前**不能**把本方案当成首选。

---

### 方案 3：**RS 界面里的官方扩展点**（side button）

#### 做法
1. 写一个 `AbstractSideButtonWidget` 子类（实现 `getSprite()` / `getTitle()` / `getSubText()`），点击回调里发一个 C2S 包请求服务端打开我们的界面。
2. **在 RS 自己的界面里挂上它** —— 这一步没有非 mixin 的路（见 §2.2），只能 `@Inject(method = "addSideButton"/"init", at = @At("TAIL"))` 到 `AbstractBaseScreen` 或具体 RS 界面。

#### 要不要新增方块？
**不要**（这也是它唯一的优点）。

#### 要不要 mixin 进别人的界面？
**要。这是本方案不可回避的硬事实。** ❌ 直接违反 C2。
- RS **没有**给附属的界面注册 API（§2.2 已用 `RefinedStorageClientApi` 全文 + `registerScreens` 可见性 + `ScreenRegistration` 位置三重证据说明）。
- `addSideButton` 虽然 `public`，但它是**实例方法**：只有先拿到「那个 RS 界面的实例」才谈得上调用它，而「让 RS 用我们的界面子类」需要替换 `MenuType → Screen` 映射 —— **未证实可行**。

#### 搜索能力从哪来？
选机器仍复用 `StepMachineSelectScreen`（与 side button 无关）。

#### 服务端权威怎么做？
与方案 1 相同。

#### 实现量级
小：1 个 Widget 子类 + 1 个 mixin 注入点（约 2~3 个文件）。

#### 风险
1. **违反 C2**（用户明确不喜欢）。
2. **RS 版本漂移**：`AbstractBaseScreen#addSideButton` 的签名 / `sideButtonY` 递增语义若在 2.1 变化，mixin 会崩（本工程已有一次同类教训：`mixin/client/AbstractBaseScreenMixin.java:24` 记录「早期在 `ExporterScreen` 上注入会直接抛 `InvalidInjectionException` 崩客户端」）。
3. 侧边按钮是 **18×18 图标位**，要配合 RS 自己的图标贴图风格；我们新增的按钮会与 RS 原生按钮**混排**（`sideButtonY` 递增），需要处理「放不下」的几何（本工程在 `AutocraftingMonitorScreenMixin.java:314-325` 已经写过一整套落位逻辑，是额外负担）。

#### API 证据
- `local_src/rs_src/.../support/AbstractBaseScreen.java:80-86,147-153`
- `local_src/rs_src/.../support/widget/AbstractSideButtonWidget.java:27-28,43,93-108,110-117`
- 内部注册（证明无公开注册点）：`local_src/rs_src/.../AbstractClientModInitializer.java:80-138,210-221`
- 无界面 API：`local_src/rs_src/.../api/RefinedStorageClientApi.java:24-49`
- 本工程已用 mixin 操作 addSideButton：`mixin/client/AbstractBaseScreenMixin.java:83-90`

#### 结论
**技术上可行、代码量最小，但明确违反 C2（用户不接受的 mixin 硬塞）。不推荐作为主方案；仅在用户改变对 mixin 的态度时才考虑。**

---

### 方案 4：**指令 + 剪贴板（无 GUI）**

#### 做法
1. **指令**：在 `CompatCommands.register(...)` 的根下加一条子树，例如 `/rs_create_compat send <target> <resource> <amount>`（`LiteralArgumentBuilder` + 补全用 `SuggestionProvider` 列出网络内可达目标 / 资源）。
2. **剪贴板**：照 `support/RsccBusClipboard.java` 写一个 `RsccSendConfigClipboard`：右键复制「目标 + 资源」到剪贴板（键用 `MODID + ":send_config"`），左键粘贴到发送器 / 接收器（只替换本模组那一段，其余段落保留，`:220-227` 的做法）。

#### 要不要新增方块？
**不要。**

#### 要不要 mixin 进别人的界面？
**不要。**

#### 搜索能力从哪来？
- 指令：**只有 Brigadier 的 Tab 补全**，不是图形搜索；
- 剪贴板：无搜索。
> ⚠️ 因此 **C3「与既有选择界面保持一致、支持搜索」不满足**。

#### 服务端权威怎么做？
**最干净的一条**：
- 指令 = 服务端原生（执行者就是服务端），没有任何 C2S 伪造面；
- 剪贴板 = 照 `RsccBusClipboard` 的「两端只取消事件，读写全部只在 `ServerLevel` 之后执行」（`:55-58,126-130,176-178`），**连一个自定义网络包都不需要**。

#### 实现量级
小：1 个指令子树（约 1 个文件内 30~80 行）+（可选）1 个剪贴板挂点（约 1 个文件 200~300 行）。

#### 风险
1. **体验**：不符合用户「有一个界面、能看清单挑」的诉求（用户原话里明确提到「显示、选择什么」）。
2. **剪贴板与 Create 的耦合**：依赖 `AllBlocks.CLIPBOARD`、`AllDataComponents.CLIPBOARD_CONTENT`、`ClipboardContent`（`RsccBusClipboard.java:3-6`），Create 换数据组件就会断。
3. 与既有 `RsccBusClipboard` 的手势可能冲突（同一个剪贴板、同一次点击），需要新的键名 + 目标方块种类判定（`:240-249` 的做法）。

#### API 证据
- 指令：`command/CompatCommands.java:97-238`、注册 `RS_Create_Compat.java:1262-1263`
- 剪贴板：`support/RsccBusClipboard.java:69-145,157-196,204-227,285-304`
- Create 侧格式：`local_src/create_src/.../clipboard/ClipboardCloneable.java:10-16`、`ClipboardContent.java`、`ClipboardValueSettingsHandler.java:232-296`
- 不能直接实现 `ClipboardCloneable` 的原因：`RsccBusClipboard.java:36-42` + `ClipboardValueSettingsHandler.java:167`

#### 结论
**作为「批量 / 无 GUI / 服务端权威」的补充通道非常合适**（尤其配合方案 1 的界面做「复制一份发送配置到另一台」），但**不能单独满足 C3/C4**。**建议作为主方案的辅助通道，而不是主方案。**

---

### 方案 5（本调研新增，推荐的主方案）：**「宿主无关的发送配置子界面」+ 挂在既有宿主上（物品 / 既有网络发送器 / 既有终端模式）**

#### 动机（为什么这是比方案 1 更好的形态）
方案 1 的唯一硬伤是「入口必须绑在一个物品上」。但工程里已经有**三种**非方块入口的先例（§1.3 链路 2），而且 `ChildConfigScreen` 本身就是**宿主无关**的（只持有 `parent` 引用，`ChildConfigScreen.java:45,104-106`）。因此可以把「发送配置界面」做成一件事：

> **一个 `ChildConfigScreen` 子类 + 一对（打开 / 写入）包 + 一个「宿主适配接口」。**
> 宿主可以是：① 手持物品（右键空气）；② **RS 既有的 `NetworkTransmitterBlock`（网络发送器）**（右键打开，或加 `addSideButton` 到**我们自己写的** `AbstractBaseScreen` 子类上）；③ 既有终端（作为终端的一个模式页，照 `AdvancedRemoteTerminalItem` 的 `MODE_*` 做法）。

#### 做法
1. **界面本体**：`SendConfigScreen extends ChildConfigScreen`（宿主无关）。内部结构：
   - 「选目标」：`StepMachineSelectScreen`（复用，可搜索）；
   - 「选资源」：`ScrollSelectWidget<ResourceEntry>` + `EditBox` 搜索，或 ghost 槽 + JEI；
   - 「选功能」：`ScrollSelectWidget<Mode>`。
2. **宿主适配**（三个互不冲突的挂点，可分批做）：
   - **5a（最小、零新方块、零 mixin）**：手持物品右键空气 → S2C 打开包。**与方案 1 完全相同**，但是**入口是"某个已有物品"而不是新方块**。
   - **5b（推荐的目标形态）**：挂在 **RS 既有的 `NetworkTransmitterBlock`** 上 —— 但**不是**去改 RS 的界面（那要 mixin），而是：
     - 该方块是 RS 的，我们**不能**改它的 `MenuType → Screen`；
     - 可选做法是**在同一格上叠加我们自己的右键分支**（NeoForge `PlayerInteractEvent.RightClickBlock`，照 `RsccBusClipboard.java:107-145` 的手势挂点做法）——**合法、不用 mixin、不用新方块**，但需要与 RS 自己的右键行为协商优先级（工程已有「一次右键只做一件事」的既有约定，见 `RsccBusClipboard.java:110`）。
   - **5c**：作为**既有终端的一个模式**（`AdvancedRemoteTerminalItem` 的 `MODE_6`）。终端的模式数现在是 `MODE_COUNT = 6`（`AdvancedRemoteTerminalItem.java:68`），扩一个模式 = 1 个常量 + 1 个分支 + 1 个语言键；**零新方块、零 mixin**，且「无线终端已经能远程打开」这件事正好贴合「发送」的远程语义。
3. **服务端权威**：与方案 1 相同；若选 5c，额外可选把配置写进终端物品的 `CUSTOM_DATA`（`AdvancedRemoteTerminalItem.java:394-404,421-435` 的成熟做法）。

#### 要不要新增方块？
**不要。** 5a / 5b / 5c 都不新增方块。

#### 要不要 mixin 进别人的界面？
**不要。** 5a 是我们自己的屏；5b 是 NeoForge 事件挂点（不是 mixin）；5c 是**我们自己的**终端物品 + 我们自己的菜单。

#### 搜索能力从哪来？
**全部复用现成**：`StepMachineSelectScreen`（`:116-152`）/ `ScrollSelectWidget`（`:28`）/ JEI（`QuantityKeeperJeiPlugin.java:30-32`）。

#### 服务端权威怎么做？
- 配置存：物品 `CUSTOM_DATA`（`AdvancedRemoteTerminalItem.java:394-404,421-435`、`AssemblyPatternRebind.java:107`）或方块实体 NBT（方案 2 的做法）；
- C2S 写入包 + 服务端复核 + S2C 快照回发（`SetStepMachinePacket.java:19-53`、`AssemblyPatternRebindScreen.java:116-129`）；
- 客户端只读、不写权威状态。

#### 实现量级
| 项 | 处数（估） |
|---|---|
| `SendConfigScreen` | 1 个文件 |
| 包（Open / Set / 可选 Request） | 2~3 个文件 |
| `registerPayloads` | 2~3 行 |
| 宿主适配（5a 或 5b 或 5c） | 1 个文件 / 1 个分支 |
| `StepMachineSelectScreen` 泛型化（**或**同源新写） | 3~4 个文件（**或** 0） |
| 语言键 | 1 个文件 |
| **合计** | **约 8~12 个文件** |

#### 风险
1. **与既有宿主的行为协商**（5b 的右键优先级 / 5c 的终端模式位）——工程对「一次右键一件事」有明确约定（`RsccBusClipboard.java:110`），需要遵守。
2. **与方案 1 相同的泛型化风险**。
3. 5c 会改变「终端模式数」（`MODE_COUNT`），需检查所有按模式遍历的地方（`AdvancedRemoteTerminalItem.java:61-68` + `isModeSupported` `:251` + Tab 图标/语言键同步登记，见 `docs/DESIGN_DECISIONS_ROUND4.md:2124` 记录的同款改动）。

#### API 证据
- 宿主无关子界面基类：`client/screen/ChildConfigScreen.java:24,41,45,52-58,67-74,82-84,104-106`
- 三种非方块入口先例：`item/AdvancedRemoteTerminalItem.java:405,446-460,638-687`、`client/TerminalKeybinds.java:36-59`、`item/AssemblyPatternRebind.java:93-137`
- 终端模式扩展：`item/AdvancedRemoteTerminalItem.java:61-68,251`、`docs/DESIGN_DECISIONS_ROUND4.md:2124`
- NeoForge 事件挂点（非 mixin）：`support/RsccBusClipboard.java:69,107-145,157-196`
- RS 发送器无我们可用的界面扩展点：`local_src/rs_src/.../networking/NetworkTransmitterScreen.java:34`（只挂了一个 `RedstoneModeSideButtonWidget`）、`NetworkTransmitterBlockEntity.java:43-45,215-227`（`NetworkNodeExtendedMenuProvider` 是**它自己的**菜单，不是给附属的扩展点）

#### 结论
**本方案是方案 1 的超集**：把「界面」与「入口」解耦，从而可以按用户的接受度自由选择入口（物品 / 既有方块 / 既有终端），并**分批交付**（先 5a 验证链路，再考虑 5b/5c）。**同时满足 C1 / C2 / C3**。

---

## 6. ⑤ 明确推荐

### 主方案：**方案 5a + 方案 1 的界面形态**（手持物品右键空气 → 服务端 S2C 打开包 → 复用 `StepMachineSelectScreen` 选目标 + 新写选资源）

**一句话**：**「发送配置」的界面**做成一个宿主无关的 `ChildConfigScreen` 子类；**入口**先走「手持一个**物品**（复用已有样板物品，或新增一个物品）右键空气」；**选目标**直接复用 `StepMachineSelectScreen`（搜索能力现成）；**选资源**复用 `ScrollSelectWidget` + `EditBox`（与选机器同一套写法）或 ghost 槽 + JEI；**服务端权威**照 `AssemblyPatternRebind` + `SetStepMachinePacket` 的成熟形状。

**为什么它能同时满足三条硬判据**：

| 判据 | 为什么满足 | 证据 |
|---|---|---|
| **不新增方块** | 入口是 `Item#use`（右键空气）+ S2C 打开包；界面是 `Screen`；全链路无方块、无方块实体 | `item/SequenceAssemblyPatternItem.java:264-277` → `item/AssemblyPatternRebind.java:104-137`（**完全不经过方块交互**，注释 `:90-91` 明说）；`client/screen/AssemblyPatternRebindScreen.java:37`（`extends ChildConfigScreen`） |
| **不用 mixin 硬塞别人的界面** | 界面是我们自己的 `Screen`；包是我们自己的 `CustomPacketPayload`；打开走 `ClientPayloadSink` | `network/AssemblyPatternRebindOpenPacket.java`、`client/ClientPayloadSink.java`、`network/ClientPayloadHooks.java:36` |
| **选择界面与现有一致、支持搜索** | **同一个类**（`StepMachineSelectScreen`）被第 4 个场景复用；搜索框与过滤逻辑逐字相同 | `client/screen/StepMachineSelectScreen.java:36,116-121,138-152`；既有 3 个调用点：`SequencePatternTerminalScreen.java:797`、`AssemblyAlertsClient.java:202`、`AssemblyPatternRebindScreen.java:176` |

**为什么不是方案 2 / 3 / 4**：
- **方案 2** 与 C1 的相容性取决于一个**尚未确认**的前提（接收器是否功能必需的方块）⇒ 不能作为首选，但**是天然的备选**（若用户本来就要新方块）。
- **方案 3** 必须 mixin（`AbstractBaseScreen.java:147` 是实例方法，而 RS 无界面注册 API，见 §2.2）⇒ 直接违反 C2。
- **方案 4** 没有图形搜索 ⇒ 违反 C3。

### 备选方案：**方案 2 的变体**——若「接收器」本来就是功能必需的方块，则把界面挂在**接收器方块**上

**理由**：工程对「方块 → `MenuProvider` → `MenuType` → `RegisterMenuScreensEvent`」这条链路最熟（13 个菜单、23 个界面），服务端权威（方块实体 NBT + `saveAdditional`）也最成熟；`MenuType` 用 `IMenuTypeExtension`（`RS_Create_Compat.java:337-344`）即可携带服务端推导的数据。**此时 C1 被重新解释为「不为界面专门造方块」，因此不冲突。**

### 入口的降级 / 升级路线（建议分两批）
1. **第 1 批（最小验证）**：5a —— 手持物品右键空气。**不动任何既有类**（界面新写，`StepMachineSelectScreen` 若不愿泛型化就同源新写一个 `SendTargetSelectScreen`）。
2. **第 2 批（可选增强）**：
   - 若想「不用拿物品」⇒ 5c（挂到既有无线终端的 `MODE_6`）；
   - 若想「就贴在发送器那台机器上」⇒ 5b（NeoForge `RightClickBlock` 事件挂点，**不是 mixin**）；
   - 若用户愿意接受新方块 ⇒ 方案 2 / 备选。

### 关于「不要写两套」的取舍（必须让用户知道）
工程里有一条明确约定：`StepMachineSelectScreen` 的类注释写着「**同一个类被两处复用**（用户明确要求『不要写两套』）」（`StepMachineSelectScreen.java:26-32`）。本次要多一个场景，因此只有两种选择：
- **(i) 把它泛型化**（`<T>` + `Function<T, Component>` 显示 + `Function<T, String>` 搜索键 + `Consumer<T>` 确认），**改 1 个类、牵动 3 个既有调用点** —— 符合「不要写两套」，但回归面在既有功能上；
- **(ii) 照抄一份新的** —— 零回归面，但产生第二套搜索选择器，**违背上述约定**。

**这是本文第二个必须由用户拍板的点。**

---

## 7. ⑥ 未证实 / 需要用户拍板的点（诚实清单）

### 7.1 必须由用户拍板

| # | 待定问题 | 为什么它决定结论 |
|---|---|---|
| **Q1** | **「网络资源接收器」到底是什么？**（① RS 自带的 `NetworkReceiverBlock`；② 本模组新建的方块；③ 一个抽象概念、实现形态待定） | 决定方案 2 是否违反 C1，也决定「选目标」的候选是什么。**未证实**：工程里没有任何「接收器」相关代码（§0.1）。 |
| **Q2** | **「发送配置」存在哪里？**（手持物品的数据组件 / 发送方方块的 NBT / 全局配置） | 决定服务端权威的落点与同步方式；也决定「同一份配置能否复制到多台发送器」。 |
| **Q3** | **是否愿意泛型化 `StepMachineSelectScreen`？**（或接受同源新写第二套） | 决定实现量与回归风险（§6）。 |
| **Q4** | **「选资源」的候选来源**：网络里**现有**的资源（`storage.getResources(...)`）还是**全注册表**（所有物品 / 流体）？ | 前者照 `support/RsccIntermediateFlow.java:92`（`storage.getResources(PlayerActor.class)`）；后者需要遍历 `BuiltInRegistries.ITEM` / `FLUID`，与「网络资源」语义不同。 |
| **Q5** | **「选目标」的候选语义**：RS 的接收器端点（按 `GlobalPos`）？还是本模组的机器方块（按 `BlockPos` + 显示名）？ | `SyncChamberListPacket.Entry(pos, name, recipeType)`（`SyncChamberListPacket.java:25`）是「执行舱 + 配方类型」语义，用于「发送目标」时需要新记录类型。 |
| **Q6** | **是否允许新增一个「物品」**（不是方块）作为发送配置的载体？ | 若不允许，则入口只能靠 5b（既有方块事件挂点）或 5c（既有终端模式），实现量会上升。 |

### 7.2 本文中「未证实」的技术点（明确标注，不做推测）

| # | 未证实的点 | 已确认的边界 |
|---|---|---|
| **U1** | **能否把 RS 既有界面的 `MenuType → Screen` 映射替换成我们自己的子类**（从而用「官方 `addSideButton`」而不是 mixin 来注入按钮） | `registerScreens` 是 `protected static`（`AbstractClientModInitializer.java:80`），`RefinedStorageClientApi` 无可注册入口（`:24-49`），RS 内部无界面注册表。**未证实有任何合法替换途径。** |
| **U2** | **「RS 样板 / 自动合成界面里给附属注册自己区块」的机制** | 全树搜索无命中；本工程对 RS 合成仓管理器的扩展走的是 mixin + accessor（`mixin/VirtualAutocrafterManagerMenuMixin.java`、`mixin/accessor/*` 共 7 个）。**未证实存在此机制。** |
| **U3** | **`Platform.INSTANCE.getMenuOpener()` 是否算「稳定 API」** | 类 `com.refinedmods.refinedstorage.common.Platform`（`Platform.java:53-56`）**不在 `common.api` 包**，无 `@API` 注解（同目录 `MenuOpener.java` 也无）。**能用，但升级时需盯。** |
| **U4** | **`com.refinedmods.refinedstorage.api.*` 与 `common.api.*` 的稳定性差异** | 已确认：`common.api.*` 上普遍带 `@API(status = STABLE, since = ...)`（如 `RefinedStorageApi.java:76`、`RefinedStorageClientApi.java:23`、`Grid.java:19`、`ResourceRepository.java:22`）。但**没有**找到 RS 对「附属可以依赖哪些非 api 包」的成文承诺。 |
| **U5** | **在 RS 既有方块（如 `NetworkTransmitterBlock`）上叠加我们自己的右键分支，是否会与 RS 自身行为冲突** | 机制上可行（NeoForge `PlayerInteractEvent.RightClickBlock`，本工程 `RsccBusClipboard.java:107-145` 已在 RS 总线上这么干过），但 **RS 发送器自身的右键行为未逐行核对**。 |
| **U6** | **Create `HeldItemGhostItemMenu`（`HeldItemGhostItemMenu.java:17`）与 `AbstractSimiContainerScreen`（`:37`）能否在本模组的 NeoForge 注册体系下直接用** | 它们是 Create 的 `foundation` 包（非 API 包，无 `@API` 注解）。**未核实** Create 6.0.10 是否对该包做稳定性承诺；且会引入「本模组界面挂到 Create 基类」的耦合。 |
| **U7** | **`ValueSettingsScreen` / `ValueSettingsBehaviour` 能否承载「目标 + 资源」** | 接口契约上可以（`ClipboardCloneable` 的 `CompoundTag`，`ClipboardCloneable.java:10-16`），但宿主必须是 Create 的 `BlockEntityBehaviour` + `SmartBlockEntity`（`ClipboardValueSettingsHandler.java:167`）；本工程的 RS 方块实体**结构上无法**变成 `SmartBlockEntity`（`RsccBusClipboard.java:36-42` 已记录同一结论）。**因此对本工程的方块不可用。** |

### 7.3 本文未做的事（边界声明）
- **未改动任何 Java 源码**（只新建了本文件）。
- **未改动 `run/`**。
- **未运行游戏 / 未编译**：本文所有结论来自源码阅读与 `Select-String` / 文件行号核对，**不含运行时验证**。
- **未做的最小验证（建议下一步做）**：「手持物品右键空气 → `PacketDistributor.sendToPlayer` → 客户端 `setScreen`」这条链路，工程里已有 3 个可用样例（`AssemblyPatternRebind` / `AssemblyPatternRebindClient` / `AssemblyPatternRebindScreen`），建议直接照抄着落第一个可点开的空界面，再往里填「选目标 + 选资源」。

---

## 附：本文引用的关键行号速查

```
# 本模组 —— 选机器（复用核心）
src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/StepMachineSelectScreen.java:36,78-99,106-114,116-121,138-152,165-180
src/main/java/cretae/cookiewyq/rs_create_compat/client/widget/ScrollSelectWidget.java:28,108,114,128,220,278
src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/ChildConfigScreen.java:24,41,45,52-58,67-74,82-84,104-106

# 本模组 —— 复用 StepMachineSelectScreen 的三处既有调用点
client/screen/SequencePatternTerminalScreen.java:790-798
client/AssemblyAlertsClient.java:191-211
client/screen/AssemblyPatternRebindScreen.java:166-185

# 本模组 —— 非方块入口 + S2C 打开包（推荐主方案的蓝本）
item/SequenceAssemblyPatternItem.java:246-277
item/AssemblyPatternRebind.java:90-91,93-137,331-380
item/AdvancedRemoteTerminalItem.java:61-68,251,394-413,421-435,446-460,638-687
client/TerminalKeybinds.java:36-59
network/AssemblyPatternRebindOpenPacket.java
client/ClientPayloadSink.java:52-53
network/ClientPayloadHooks.java:7,36

# 本模组 —— 服务端权威 / 同步
network/SetStepMachinePacket.java:19-53
network/SyncChamberListPacket.java:23-68
RS_Create_Compat.java:85,177-390,337-344,650-1242,1262-1263
command/CompatCommands.java:97-238
client/ClientInit.java:140-164
support/RsccBusClipboard.java:69,76,107-145,157-196,204-227,285-304
support/RsccIntermediateFlow.java:92
client/widget/GhostMarkerRenderer.java:46,59,72,118,188,212,246,262,300
client/QuantityKeeperJeiPlugin.java:30-32,35-88

# RS 2.0.9（local_src/rs_src）
com/refinedmods/refinedstorage/common/support/AbstractBaseScreen.java:41,80-86,89-93,96-99,147-153
com/refinedmods/refinedstorage/common/support/widget/AbstractSideButtonWidget.java:27-28,39-41,43,93-108,110-117
com/refinedmods/refinedstorage/common/AbstractClientModInitializer.java:80-138,210-221
com/refinedmods/refinedstorage/common/api/RefinedStorageClientApi.java:23,24-49
com/refinedmods/refinedstorage/common/api/RefinedStorageApi.java:76,77-236
com/refinedmods/refinedstorage/common/api/grid/Grid.java:19,20-36
com/refinedmods/refinedstorage/api/resource/repository/ResourceRepository.java:22,23-97
com/refinedmods/refinedstorage/common/Platform.java:53-56
com/refinedmods/refinedstorage/common/support/containermenu/MenuOpener.java:6-8
com/refinedmods/refinedstorage/common/support/containermenu/ExtendedMenuProvider.java:7-11
com/refinedmods/refinedstorage/common/networking/NetworkReceiverBlock.java:31
com/refinedmods/refinedstorage/common/networking/NetworkReceiverBlockEntity.java:19-50
com/refinedmods/refinedstorage/common/networking/NetworkTransmitterBlockEntity.java:43-45,61-66,82-95,127-145,215-238
com/refinedmods/refinedstorage/common/networking/NetworkCardItem.java:40-61,90-92
com/refinedmods/refinedstorage/common/networking/NetworkTransmitterScreen.java:34
com/refinedmods/refinedstorage/common/support/AbstractFilterScreen.java:20-79

# Create 6.0.10（local_src/create_src）
com/simibubi/create/content/logistics/redstoneRequester/RedstoneRequesterScreen.java:31,41-50,63-104,130-141,173-195,221-231
com/simibubi/create/content/logistics/redstoneRequester/RedstoneRequesterMenu.java:25
com/simibubi/create/content/logistics/stockTicker/StockKeeperRequestScreen.java:88,228-247,269-361,645-652
com/simibubi/create/content/equipment/clipboard/ClipboardCloneable.java:10-16
com/simibubi/create/content/equipment/clipboard/ClipboardValueSettingsHandler.java:49-90,145-168,232-296
com/simibubi/create/foundation/gui/menu/AbstractSimiContainerScreen.java:37,143,177
com/simibubi/create/foundation/gui/menu/GhostItemMenu.java:13,15,53,91
com/simibubi/create/foundation/gui/menu/HeldItemGhostItemMenu.java:17,33,40,46,50
com/simibubi/create/foundation/gui/menu/GhostItemSubmitPacket.java:15-16
com/simibubi/create/foundation/gui/menu/MenuBase.java:15,19,62,68
com/simibubi/create/foundation/gui/widget/SelectionScrollInput.java:12
com/simibubi/create/foundation/blockEntity/behaviour/ValueSettingsScreen.java:31,34,46
com/simibubi/create/foundation/blockEntity/behaviour/ValueSettingsBehaviour.java:20,41
com/simibubi/create/content/logistics/packagePort/PackagePortTargetSelectionHandler.java:27,33-48,50-72
```
