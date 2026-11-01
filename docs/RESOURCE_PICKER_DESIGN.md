# 「与精致存储一样的资源选择界面」——调研 + 设计（含 API 证据）

> 任务范围：**只调研 + 只设计**。本轮**未修改任何 Java 源码**、**未修改 `run/`**，只新建本文件。
>
> 环境：Minecraft 1.21.1 / NeoForge 21.1.248 / RS **2.0.9**（`refinedstorage-neoforge-2.0.9.jar`）。
>
> 证据口径：
> * RS 2.0.9 **只有 jar、没有 sources jar** ⇒ 所有 RS 结论来自
>   `D:\java21\bin\javap.exe -p -c -classpath <RS jar> <类名>` 的**反汇编输出**（本文件引用时给出「类名 + 方法 + 字节码偏移」）。
> * NeoForge / 原版结论来自 `build\moddev\artifacts\neoforge-21.1.248-sources.jar`（NeoForge 打过补丁的原版源码），引用时给 **`文件:行号`**。
>   （同样内容也在 `D:\gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\21.1.248\f0378c9b210dfc258170b6412a85fdffe4aea7fe\neoforge-21.1.248-sources.jar`，本文件用的是工程 `build\moddev` 里那份，两者同源。）
> * 本工程结论来自 `src/main/java/...`，引用时给 **`文件:行号`**。
> * `local_src/external/RefinedStorage/` 是 **post-2.0.9 master**，**只用于「升级风险」对照**，其行号**不能**当 2.0.9 用（本文件已逐处标注 `[master]`）。
> * **找不到证据的一律写「未证实」**，不做推测。

RS jar 路径（下文简称 `<RSJAR>`）：

```
D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar
```

---

## ⓿ 结论摘要（先看这 8 行）

1. **Lead 的假设成立**（已证实，见 §①）：我们**不需要** RS 给附属的界面注册 API。建**自己的 `MenuType`**（继承 RS 的 `AbstractGridContainerMenu`）＋用 **NeoForge `RegisterMenuScreensEvent`** 注册**自己的** Screen 工厂，**零 mixin、零改动 RS 注册表**即可。
2. 关键机理：客户端开哪个界面，**只由服务端发出的 `ClientboundOpenScreenPacket`/`AdvancedOpenScreenPayload` 里携带的 `MenuType` 决定**，而那个 `MenuType` 就是**我们自己菜单构造器传给 super 的那一个**（`abstractcontainermenu.getType()`）。RS 不做任何 `instanceof GridContainerMenu` 的白名单判定。
3. **但绝不能用 RS 现成的 `GridContainerMenu`/`WirelessGridContainerMenu`**：它们的构造器把 `MenuType` **硬编码成 `Menus.INSTANCE.getGrid()`**，走它们 = 客户端去找 RS 的 Screen，我们的注册永远不生效（§1.3）。
4. RS 的网格（grid）**不是 Slot 网格**：客户端从 `menu.getRepository().getViewList()` **自绘**每一格，点击也在客户端自行判定。所以「浏览整个网络 + 搜索 + 排序 + 视图切换」**全部随基类免费得到**，这正是「复用一遍、不自己写」的落点。
5. 「点一下某个资源」**RS 没有选中语义**：基类永远走 `onExtract`（取出物品）。但 `mouseClicked(double,double,int)` 是 `public`、`getCurrentGridResource()` 是 `public` ⇒ 我们**覆写这一个方法**即可把「点击」改成「选中」。
6. 「选中结果回传服务端」**不需要新包、不需要 mixin**：RS 的 `C2SPackets` 是 `public final class`，`sendResourceFilterSlotChange(PlatformResourceKey, int)` 是 `public static`；其落点 `AbstractResourceContainerMenu.handleResourceFilterSlotUpdate(int, PlatformResourceKey)` 我们**直接覆写**接住即可（§3.6）。
7. **推荐做法 = D1（用 RS 真实网格）**，形态是「方块右键 → 自己的方块界面；界面里点『选择』→ 打开一个 `GridScreen` 子类子界面」——即**既有 `ChildConfigScreen` 体系的外观习惯 + RS 真实网格控件**（§④）。D2/D3 不推荐（理由与代价见对比表）。
8. 版本升级风险**可控但真实**：`AbstractGridContainerMenu` / `AbstractGridScreen` / `GridScreen` / `GridData` 四个扩展缝在 2.0.9 与 master 上**签名逐条一致**（§5.1 对照表），但这四条全是 **RS 内部类**（无 `@ApiStatus` 承诺），2.1.x 仍可能变动；缓解手段见 §5.1.4。

---

## ① Lead 假设的证实 / 证伪

**假设原文**：
> 我们**不需要** RS 给附属的界面注册 API —— 我们建**自己的 `MenuType`**（继承 RS 的网格菜单基类），再用 **NeoForge 的 `RegisterMenuScreensEvent`** 注册**我们自己的** Screen 工厂即可。这样零 mixin、零改动 RS 的注册表。

**判定：✅ 证实（全部环节都有证据）。** 下面是完整链路，每一步都给证据。

### 1.1 链路第 1 步：NeoForge 的注册事件存在且就是 RS 自己在用的

`javap -p -c` 于 `neoforge-21.1.248-universal.jar`：

```
public class net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
        extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent {
  private final java.util.Map<MenuType<?>, MenuScreens$ScreenConstructor<?, ?>> registeredScreens;
  public <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>>
         void register(MenuType<? extends M>, MenuScreens$ScreenConstructor<M, U>);
      // 内含 containsKey → 重复注册抛 IllegalStateException，然后 registeredScreens.put(menuType, ctor)
}
```

**RS 自己就是这么注册网格界面的**（同一份 javap 里的 `ClientModInitializer$1`，是 RS 的实现类，不是我们的）：

```
class com.refinedmods.refinedstorage.neoforge.ClientModInitializer$1
        implements com.refinedmods.refinedstorage.common.AbstractClientModInitializer$ScreenRegistration {
  final net.neoforged.neoforge.client.event.RegisterMenuScreensEvent val$e;
  public void register(MenuType<? extends M>, ...ScreenConstructor<M,U>);
      // 6: val$e
      // 16: invokevirtual RegisterMenuScreensEvent.register(MenuType, MenuScreens$ScreenConstructor)
}
```

`ClientModInitializer.onRegisterMenuScreens(RegisterMenuScreensEvent)`（同一 javap）：
偏移 0-8 `new ClientModInitializer$1(event)` → `invokestatic registerScreens(ScreenRegistration)`。

`javap -p -c` 于 `com.refinedmods.refinedstorage.common.AbstractClientModInitializer`（`registerScreens` 方法体内）可见逐条：

```
 21: invokevirtual Menus.getGrid:()Lnet/minecraft/world/inventory/MenuType;
 29: invokeinterface ScreenRegistration.register:(Lnet/minecraft/world/inventory/MenuType;...ScreenConstructor;)V
```

即 **RS 的 `Menus.getGrid()` 配 `GridScreen`**，走的是**同一个** `RegisterMenuScreensEvent`。

### 1.2 链路第 2 步：客户端「用哪个 Screen」只看包里的 `MenuType`

`[sources] net/minecraft/server/level/ServerPlayer.java:1119-1169`（NeoForge 补丁后的 `openMenu(MenuProvider, Consumer<RegistryFriendlyByteBuf>)`）：

```java
1132:  AbstractContainerMenu abstractcontainermenu = p_9033_.createMenu(this.containerCounter, this.getInventory(), this);
1141:  var extraData = net.neoforged.neoforge.common.util.FriendlyByteBufUtil.writeCustomData(
1142:          buffer -> { p_9033_.writeClientSideData(abstractcontainermenu, buffer);
1144:                       if (extraDataWriter != null) extraDataWriter.accept(buffer); }, registryAccess());
1150:  if (extraData.length != 0) {
1151:      this.connection.send(new net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload(
1153:              abstractcontainermenu.containerId,
1154:              abstractcontainermenu.getType(),          // ← 菜单类型来自「菜单对象」
1155:              p_9033_.getDisplayName(), extraData));
1159:  } else {
1160:      this.connection.send(new ClientboundOpenScreenPacket(
1161:              abstractcontainermenu.containerId, abstractcontainermenu.getType(), p_9033_.getDisplayName()));
```

客户端：

* `[sources] net/minecraft/client/multiplayer/ClientPacketListener.java:1244-1248`

```java
public void handleOpenScreen(ClientboundOpenScreenPacket p_105042_) {
    PacketUtils.ensureRunningOnSameThread(...);
    MenuScreens.create(p_105042_.getType(), this.minecraft, p_105042_.getContainerId(), p_105042_.getTitle());
}
```

* `[sources] net/neoforged/neoforge/network/handlers/ClientPayloadHandler.java:115-136`

```java
115: public static void handle(AdvancedOpenScreenPayload msg, IPayloadContext context) { ... createMenuScreen(...); }
129: private static <T extends AbstractContainerMenu> void createMenuScreen(Component name, MenuType<T> menuType, int windowId, RegistryFriendlyByteBuf buf) {
131:     MenuScreens.getScreenFactory(menuType).ifPresent(f -> {
132:         Screen s = f.create(menuType.create(windowId, mc.player.getInventory(), buf), mc.player.getInventory(), name);
133:         mc.player.containerMenu = ((MenuAccess<?>) s).getMenu();
134:         mc.setScreen(s);
```

* `[sources] net/minecraft/client/gui/screens/MenuScreens.java`

```
41: private static final Map<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>> SCREENS = Maps.newHashMap();
43: public static <T extends AbstractContainerMenu> void create(MenuType<T> p_96202_, Minecraft p_96203_, int p_96204_, Component p_96205_) {
44:     getScreenFactory(p_96202_).ifPresent(f -> f.fromPacket(p_96205_, p_96202_, p_96203_, p_96204_));
47: public static <T extends AbstractContainerMenu> java.util.Optional<ScreenConstructor<T, ?>> getScreenFactory(MenuType<T> p_96202_) { ... SCREENS.get(...) ... }
75: @ApiStatus.Internal public static void init() {
77:     var event = new net.neoforged.neoforge.client.event.RegisterMenuScreensEvent(SCREENS);
78:     net.neoforged.fml.ModLoader.postEvent(event);
123: public interface ScreenConstructor<T extends AbstractContainerMenu, U extends Screen & MenuAccess<T>> {
125:     U u = this.create(p_96211_.create(p_96213_, p_96212_.player.getInventory()), ...);
```

**⇒ 结论**：`event.register(X, ScreenCtor)` 把工厂写进 `MenuScreens.SCREENS`；服务端发哪个 `MenuType`，客户端就去这张表里查。**我们的 `MenuType` → 我们的 Screen，完全由 NeoForge 的公开机制支撑，与 RS 的注册表无关。**

### 1.3 ⚠️ 链路第 3 步（**必须知道的前提**）：不能复用 RS 现成的菜单类

`javap -p -c` 于 `<RSJAR>` 的 `com.refinedmods.refinedstorage.common.grid.GridContainerMenu`：

```
public GridContainerMenu(int, Inventory, GridData);
   0: getstatic  Menus.INSTANCE
   4: invokevirtual Menus.getGrid:()Lnet/minecraft/world/inventory/MenuType;
   7: iload_1 ; 8: aload_2 ; 9: aload_3
  10: invokespecial AbstractGridContainerMenu."<init>":(MenuType;ILInventory;LGridData;)V
```

`com.refinedmods.refinedstorage.common.grid.WirelessGridContainerMenu` 同样硬编码 `Menus.INSTANCE.getWirelessGrid()`。

`[master] local_src/external/RefinedStorage/refinedstorage-common/src/main/java/com/refinedmods/refinedstorage/common/grid/GridContainerMenu.java`（同样硬编码）：

```java
public GridContainerMenu(final int syncId, final Inventory playerInventory, final GridData gridData) {
    super(Menus.INSTANCE.getGrid(), syncId, playerInventory, gridData);   // ← 类型被钉死
```

**⇒ 必须自建菜单子类，在自己的构造器里传自己的 `MenuType`。** 这是本方案唯一"必须自己写"的类（约 10 行）。

### 1.4 链路第 4 步：以「菜单类型」为过滤条件的 RS 内部判定，只有 `instanceof` 基类，没有白名单

对整个 RS jar 做了**常量池级扫描**（找出引用了这 5 个名字的所有 class），再对可疑类 `javap -p -c | grep instanceof`：

| 判定点 | 字节码 | 对我们是好是坏 |
|---|---|---|
| `support/packet/s2c/GridUpdatePacket.handle` | `11: instanceof AbstractGridContainerMenu` | ✅ 我们的子类**照样收到网格增量更新** |
| `support/packet/s2c/GridClearPacket.handle` | `11: instanceof AbstractGridContainerMenu` | ✅ 收到清空 |
| `support/packet/s2c/GridActivePacket.handle` | `11: instanceof GridWatcher` | ✅ 菜单基类实现了 `GridWatcher`，`onActiveChanged` 正常 |
| `support/packet/c2s/ResourceFilterSlotChangePacket.handle` | `11: instanceof AbstractResourceContainerMenu` | ✅ 见 §3.6，回传选中资源可用 |
| 全 jar | **没有任何 `instanceof GridContainerMenu` / `instanceof WirelessGridContainerMenu`** | ✅ 无白名单 |
| `AbstractModInitializer` 引用了 `GridContainerMenu`/`WirelessGridContainerMenu` | 只在 `registerMenus(...)` 里 `Menus.setGrid(...)`，无 `instanceof` | 与我们无关 |
| `grid/GridBlockEntity` 引用了 `GridContainerMenu` | 只在 `createMenu(...)` 里 `new GridContainerMenu(...)`，无 `instanceof` | 与我们无关 |

### 1.5 假设证实小结

| 假设分句 | 判定 | 依据 |
|---|---|---|
| 不需要 RS 给附属的界面注册 API | ✅ 证实 | §6.2 `api/` 与 `common/api/` 里没有任何「注册 Screen / 注册 MenuType / 选资源」入口 |
| 建自己的 `MenuType` 继承 RS 网格菜单基类 | ✅ 证实（可行） | `AbstractGridContainerMenu` 是 `public abstract`，两个构造器都是 `protected`（§2.1） |
| 用 NeoForge `RegisterMenuScreensEvent` 注册自己的 Screen | ✅ 证实 | §1.1 + §1.2 全链路 |
| 零 mixin | ✅ 证实（本设计不需要 mixin） | 所有需要的缝都是 `public`/`protected`（§2.4、§3.6） |
| 零改动 RS 注册表 | ✅ 证实 | 只用 RS 的公开类型与公开方法，不往 RS 的 `DeferredRegister` 里塞东西 |

---

## ② RS 网格体系的**可继承性清单**（2.0.9，逐个 `javap -p`）

### 2.1 类清单表（**可见性 / abstract / 构造器签名**）

命令模板：

```powershell
& D:\java21\bin\javap.exe -p -classpath <RSJAR> <类名>
```

| 类（2.0.9 全限定名） | 修饰符 | 构造器（精确签名） | 可继承? |
|---|---|---|---|
| `...common.grid.AbstractGridContainerMenu` | `public abstract class` | `protected AbstractGridContainerMenu(MenuType<? extends AbstractGridContainerMenu>, int, Inventory, GridData)`<br>`protected AbstractGridContainerMenu(MenuType<? extends AbstractGridContainerMenu>, int, Inventory, Grid)` | ✅ **这就是我们的菜单基类** |
| `...common.grid.GridContainerMenu` | `public class`（非 final） | `public GridContainerMenu(int, Inventory, GridData)`<br>包私有 `GridContainerMenu(int, Inventory, GridBlockEntity)` | ⚠️ 可继承但**构造器硬编码 RS 的 MenuType**（§1.3）⇒ 不用 |
| `...common.grid.WirelessGridContainerMenu` | `public class` | `public (int, Inventory, WirelessGridData)`<br>包私有 `(int, Inventory, Grid, SlotReference)` | ⚠️ 同上（硬编码 `getWirelessGrid()`） |
| `...common.grid.screen.AbstractGridScreen<T extends AbstractGridContainerMenu>` | `public abstract class` | `protected AbstractGridScreen(T, Inventory, Component, int bottomHeight)` | ✅ 屏幕基类 |
| `...common.grid.screen.GridScreen<T extends AbstractGridContainerMenu>` | `public class`（非 final） | `public GridScreen(T, Inventory, Component)` | ✅ **这就是我们要继承的屏幕**（现成外观） |
| `...common.grid.GridData` | `public final class ... extends Record` | `public GridData(boolean, List<GridData$GridResource>, Set<PlatformResourceKey>)`；`public static GridData of(Grid)`；`public static final StreamCodec<RegistryFriendlyByteBuf, GridData> STREAM_CODEC` | ✅ 可直接当网络载荷用 |
| `...common.grid.GridData$GridResource` | `public final class ... extends Record` | `public GridResource(ResourceAmount, Optional<TrackedResource>)`；`static GridResource of(TrackedResourceAmount)`（**包私有**） | 读公开 getter 即可 |
| `...common.api.grid.Grid` | `public interface` | —（接口，7 个方法，见 §3.1） | ✅ **我们要自己实现它** |
| `...common.api.grid.view.GridResource` | `public interface` | — | 只读使用 |
| `...common.api.grid.view.AbstractGridResource<T extends PlatformResourceKey>` | `public abstract class` | `protected AbstractGridResource(T, String, Set<String>, Function<...>)`<br>`protected AbstractGridResource(T, String, Function<...>)` | 新增资源类型（气体）时才用 |
| `...common.grid.GridSortingTypes` | `public final class ... extends Enum` | `QUANTITY / NAME / ID / LAST_MODIFIED` | 只读使用 |
| `...common.grid.GridViewType` | `public final class ... extends Enum` | `ALL / AUTOCRAFTABLE / NON_AUTOCRAFTABLE`（**枚举本身 `public`，但 `accepts(boolean)` 是包私有**） | 只读使用 |
| `...common.grid.GridSearchBox` | `public interface` | `setValue/ setValid/ addListener` | 由基类 `setSearchBox` 使用 |
| `...common.grid.screen.GridSearchBoxWidget` | **包私有** `class` | 包私有 | ❌ 我们不能 new（基类自己建） |
| `...common.grid.GridSortingTypes$TrackedResourceProvider` | `public interface` | —（`TrackedResource getTrackedResource(GridResource)`） | 由基类 `AbstractGridContainerMenu` 实现，我们不用管 |
| `...common.api.grid.GridSynchronizer` | `public interface` | `getTitle/getHelpText/synchronizeFromGrid/getTextToSynchronizeToGrid/getSprite` | 需要"与 JEI/REI 搜索框同步"时才实现 |

`GridScreen` 构造器字节码（决定"和 RS 一样"的尺寸与贴图）：

```
  public GridScreen(T, Inventory, Component);
     4: bipush 99          → super(menu, inv, title, bottomHeight=99)
    10: bipush 75   putfield inventoryLabelY
    16: sipush 193  putfield imageWidth
    23: sipush 176  putfield imageHeight
  protected ResourceLocation getTexture();
     0: getstatic TEXTURE
  static {};
     0: ldc "textures/gui/grid.png"
     2: invokestatic IdentifierUtil.createIdentifier → refinedstorage:textures/gui/grid.png
```

该贴图在 jar 里确实存在（`assets/refinedstorage/textures/gui/grid.png`，850 字节），行分隔精灵 `assets/refinedstorage/textures/gui/sprites/grid/row.png` 也在。
⇒ **继承 `GridScreen` 就是"精致存储自己的那张网格界面"，零贴图工作量。**

RS 自己也用不同尺寸做过变体（证明可裁剪）：`CraftingGridScreen` → `imageWidth=193, imageHeight=229`；`PatternGridScreen` → `imageWidth=193, imageHeight=249`；`PortableGridScreen` → `193×176`。

### 2.2 菜单基类**依赖哪些字段 / 构造一个"只用来选资源"的网格要喂什么**

`javap -p -c` 于 `AbstractGridContainerMenu`，两个构造器的实际行为：

**构造器 A：`(MenuType, int, Inventory, GridData)`（客户端 + 无网络的纯数据场景）**

```
  3: invokespecial AbstractResourceContainerMenu."<init>":(MenuType;I)V
 44: aload 4 → invokevirtual GridData.active()          → this.active
 53: invokestatic createRepositoryBuilder(this)          → 空 repositoryBuilder
 59: GridData.resources() .forEach(lambda$new$1)         → 逐条 addResource(resource, amount) + trackedResources.put
 77: GridData.autocraftableResources() .forEach(...)     → addStickyResource
 99: builder.build()                                     → this.repository
110: Config.getGrid().getSortingType().apply(this).apply(repo) + getSortingDirection() → repository.setSort(...)
171: repository.setFilterAndSort(createBaseFilter())
185: this.synchronizer   = loadSynchronizer()            （从 config 读，缺省 NoopGridSynchronizer）
193: this.resourceTypeFilter = loadResourceType()
201: insertionStrategy   = new ClientGridInsertionStrategy()
212: extractionStrategy  = new ClientGridExtractionStrategy()
223: scrollingStrategy   = new ClientGridScrollingStrategy()
234: updateResourceTypeWarning()
```

⇒ **此构造器不建 watcher、`pendingUpdates` 保持 `null`**（`onChanged` 里 `if (pendingUpdates == null) return`，见 §3.3）。这是**客户端**那一次构造。

**构造器 B：`(MenuType, int, Inventory, Grid)`（服务端，唯一会注册 watcher 的）**

```
  3: invokespecial AbstractResourceContainerMenu."<init>":(MenuType;I;Player)V   ← 用 playerInventory.player
 43: playerInventory.player checkcast ServerPlayer         ← ⚠️ 必须真玩家，否则 CCE
 53: createRepositoryBuilder(this).build()                 → 空 repository
 70: this.grid = grid
 76: grid.addWatcher(this, PlayerActor.class)              ← ★ 增量刷新的入口
 88: this.pendingUpdates = new PendingGridUpdates(serverPlayer)
101: this.synchronizer = NoopGridSynchronizer.INSTANCE
108: initStrategies(serverPlayer)
```

`initStrategies`（同 javap）：

```
RefinedStorageApi.INSTANCE.createGridInsertionStrategy(this, serverPlayer, grid)  → insertionStrategy
                                .createGridExtractionStrategy(...)                → extractionStrategy
                                .createGridScrollingStrategy(...)                 → scrollingStrategy
```

**最小"喂料"清单**（做「只读、用于选择」的网格）：

| 需要的东西 | 服务端 | 客户端 | 证据 |
|---|---|---|---|
| 自己的 `MenuType`（`IMenuTypeExtension.create`） | ✅ 构造器参数 | ✅ 由 NeoForge 的 `menuType.create(id, inv, buf)` 调用 | §1.2；`[sources] net/minecraft/world/inventory/MenuType.java:11,53-63` |
| `GridData` | `GridData.of(grid)`（首帧） | 从 buffer 解码 | §3.2 |
| 一个 `Grid` 实现 | ✅ | ❌ 不需要（`grid` 字段留 null） | 构造器 A/B |
| `Inventory` | ✅ 玩家背包 | ✅ | 基类 `resized()` 只加玩家背包槽 |

**不需要**：`StorageNetworkComponent` 字段、`GridWatcher` 手写实现（基类已实现）、`ResourceRepository` 手写（基类建）、`Slot` 手写（网格不是 Slot）。

### 2.3 重大发现：RS 2.0.9 的网格**不是 `Slot` 网格**，是**客户端自绘**

证据（`javap -p -c` 于 `AbstractGridScreen`）：

* 只有 `private void renderRows(GuiGraphics,int,int,int,int,int,int)` / `private void renderRow(...)` / `private void renderCell(...)` /
  `private void renderSlot(GuiGraphics,int,int,int,ResourceRepository,int,int)`（**注意这个重载带 `ResourceRepository`，是网格格的绘制，不是原版 `renderSlot(GuiGraphics, Slot)`**）。
* `updateScrollbar()`：
```
  2: getMenu() → AbstractGridContainerMenu.getRepository()
 11: ResourceRepository.getViewList()  → List.size()
 22: fdiv 9.0f      ← 9 列
 27: Math.ceil(...) → totalRows
```
* `getCurrentGridResource()`：
```
  0: currentGridSlotIndex < 0 → null
  9: getMenu().getRepository().getViewList()
 25: currentGridSlotIndex >= list.size() → null
 40: list.get(currentGridSlotIndex) checkcast GridResource
```
* `AbstractGridContainerMenu.resized(int,int,int)` 只做两件事：
```
  1: resetSlots()
  5: addPlayerInventory(this.playerInventory, 8, p1, listener)   ← 只有玩家背包
```
  整个类里**没有任何 `addSlot` / `ResourceSlot` 引用**（`javap ... | grep ResourceSlot` 为空）。
* 9 列硬编码（`COLUMNS`，`javap -p` 字段列表里可见 `private static final int COLUMNS`），`isOverStorageArea` 里的宽度判定是 `leftPos+7 .. leftPos+168`（字节码 `bipush 7` / `sipush 168`）。

**⇒ 直接后果（对我们的需求全都是好消息）**：
1. 「浏览整个网络的**全部**资源」＝ 基类自己拿 `repository.getViewList()` 逐格绘制，**不存在"服务端槽位数量上限 / 客户端镜像槽位"问题**；
2. 「搜索」＝ `GridSearchBoxWidget`（RS 的查询语法 + 语法高亮），由 `init(int)` 自动建；
3. 「排序」＝ 侧边按钮 `SortingTypeSideButtonWidget` / `SortingDirectionSideButtonWidget`；
4. 「视图过滤」＝ `ViewTypeSideButtonWidget` + `ResourceTypeSideButtonWidget`；
5. **我们一行渲染代码都不用写**。

### 2.4 「点一下某个资源」有没有现成的回调/选中语义？——**没有选中语义，但有一个 protected 缝**

`javap -p` 给出的相关成员：

```
public abstract class AbstractGridScreen<T extends AbstractGridContainerMenu> extends AbstractStretchingScreen<T> {
  protected final int bottomHeight;
  GridSearchBoxWidget searchField;                            ← 包私有字段
  protected AbstractGridScreen(T, Inventory, Component, int);
  protected void init(int);                                   ← 建搜索框 + 4 个侧边按钮
  protected void containerTick();
  public  GridResource getCurrentGridResource();               ← ★ public
  public  boolean mouseClicked(double, double, int);           ← ★ public（可覆写）
  protected void mouseClickedInGrid(int, GridResource);        ← ★ protected（可覆写）
  private   boolean canExtract(GridResource, ItemStack);       ← private（不可覆写）
  private   boolean canInsert(int,int,int,ItemStack);          ← private
  private   void mouseClickedInGrid(int);                      ← private（插入分支）
  ...
}
```

`mouseClicked(double,double,int)` 的完整判定链（字节码，偏移即上文）：

```
1300: public boolean mouseClicked(double, double, int);
   0-45: 若 isOverTenthAnniversaryRibbon(...) → 打开 TenthAnniversaryScreen，return true
  47: mouseClicked(mouseX, mouseY, button, getCurrentGridResource(), getMenu().getCarried())

  private boolean mouseClicked(double,double,int,GridResource,ItemStack);
    0: canExtract(resource, carried)  ? → mouseClickedInGrid(button, resource); return true
   21: canInsert(mx,my,button,carried) ? → mouseClickedInGrid(button);          return true
   44: carried != null && resource.isAutocraftable(repo) && tryStartAutocrafting(resource) → true
   80: super.mouseClicked(...)

  private boolean canExtract(GridResource, ItemStack);
    0: resource != null
    5: resource.canExtract(carried, getMenu().getRepository())
   24: !ClientPlatformUtil.isCommandOrControlDown()
```

`protected void mouseClickedInGrid(int, GridResource)` 的基类实现：

```
   0: aload_2 ; iload_1
   2: invokestatic getExtractMode:(I)GridExtractMode
   5: invokestatic shouldExtractToCursor:()Z
   8: getMenu() checkcast GridExtractionStrategy
  15: GridResource.onExtract(GridExtractMode, boolean, GridExtractionStrategy)
```

**⇒ 结论（回答 A 的最后一问）**：
* 网格**只有"查看 + 取出/放入 + 发起自动合成"三种语义**，**没有"选中"语义**。
* 只覆写 `mouseClickedInGrid(int, GridResource)` **不够**：它只有在 `canExtract(...)` 为真时才会被调用（按住 Ctrl、或该资源不可取出、或光标上有物品时都不走它）。
* **正确做法：覆写 `public boolean mouseClicked(double,double,int)`**，用 `getCurrentGridResource()` 取当前悬停资源；命中存储区时执行"选中"，否则 `return super.mouseClicked(...)` 保留原行为。
  * `getCurrentGridResource()` 只依赖 `currentGridSlotIndex`（由基类的 `mouseMoved`/`renderTooltip` 路径维护）与 `repository.getViewList()`，是 `public`，我们可直接用。
* `[master] local_src/external/RefinedStorage/refinedstorage-common/src/main/java/com/refinedmods/refinedstorage/common/grid/screen/AbstractGridScreen.java:636` 与 `:567`、`:580`、`:584` 与 2.0.9 字节码**逐行对应**（说明这不是我读错字节码）。

---

## ③ 网格数据的**来源**与刷新机制（我们照哪条链路）

### 3.1 服务端"整个网络资源清单"的读取入口

RS 把它抽象成 `Grid` 接口（`com.refinedmods.refinedstorage.common.api.grid.Grid`）：

```java
public interface Grid extends PreviewProvider {
    void addWatcher(GridWatcher, Class<? extends Actor>);
    void removeWatcher(GridWatcher);
    Storage getItemStorage();
    boolean isGridActive();
    List<TrackedResourceAmount> getResources(Class<? extends Actor>);
    Set<PlatformResourceKey> getAutocraftableResources();
    GridOperations createOperations(ResourceType, ServerPlayer);
    boolean canMenuStayOpen(Player);
}
```

RS 的标准实现 `AbstractGridBlockEntity implements Grid` 的**每一个方法都只是转发到网络组件**（`javap -p -c`，逐条）：

| `Grid` 方法 | `AbstractGridBlockEntity` 的实现（字节码） |
|---|---|
| `getResources(actorType)` | `((GridNetworkNode) mainNetworkNode).getNetwork().getComponent(StorageNetworkComponent.class).getResources(actorType)` |
| `getAutocraftableResources()` | `...getComponent(AutocraftingNetworkComponent.class).getOutputs()` → `filter(PlatformResourceKey.class::isInstance)` → `map(PlatformResourceKey.class::cast)` → `Collectors.toSet()` |
| `isGridActive()` | `((GridNetworkNode) mainNetworkNode).isActive()` |
| `getItemStorage()` | `...getComponent(StorageNetworkComponent.class)`（`StorageNetworkComponent` 本身就是 `RootStorage`/`Storage`） |
| `addWatcher(w, t)` | `((GridNetworkNode) mainNetworkNode).addWatcher(w, t)` |
| `removeWatcher(w)` | `((GridNetworkNode) mainNetworkNode).removeWatcher(w)` |
| `createOperations(type, sp)` | `type.createGridOperations(rootStorage, new PlayerActor(sp))` → `new SecuredGridOperations(sp, platformSecurityComponent, ops)` →（rootStorage 是 `FuzzyRootStorage` 时再包 `FuzzyGridOperations`） |
| `canMenuStayOpen(player)` | `Container.stillValidBlockEntity(this, player)` |
| `getPreview / getTreePreview / getMaxAmount / startTask` | `Optional.ofNullable(getNetwork()).map(n -> n.getComponent(AutocraftingNetworkComponent.class)).map(...)`，网络为 null 时各自 `orElseGet` 回退 |

**本工程已有的等价调用点（照抄它们的口径即可）**：

| 位置 | 代码 |
|---|---|
| `src/main/java/.../support/AssemblyWatchdog.java:2398-2402` | `final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class); for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class))` |
| `src/main/java/.../block/entity/SequenceExecutionChamberBlockEntity.java:2897-2908` | 同上（注释明确"与备料侧 `storage.getResources` 同一来源"） |
| `src/main/java/.../support/RsccIntermediateFlow.java:76,92` | 同上 |
| `src/main/java/.../support/RsccDiag.java:1120-1122` | 同上 |
| `src/main/java/.../block/entity/QuantityKeeperBlockEntity.java:452,759,773,794` | `network.getComponent(StorageNetworkComponent.class)` |
| `src/main/java/.../block/entity/AdvancedQuantityKeeperBlockEntity.java:500,911-913` | 同上（并封装成 `storageComponent()`） |

**⇒** 「浏览整个网络的所有东西」这件事，本工程**已经在多处做过**，口径就是
`network.getComponent(StorageNetworkComponent.class).getResources(PlayerActor.class)`；
`GridData.of(grid)` 走的正是这一条（见 §3.2）。

### 3.2 首次下发链路（照这条）

**RS 的原文链路**：

```
[方块右键]
  AbstractBaseBlock.useWithoutItem(...)                       (javap -c)
    → tryOpenScreen(...)  → level.getBlockEntity(pos) instanceof MenuProvider
    → tryOpenScreen(ServerPlayer, MenuProvider)
        1: menuProvider instanceof NetworkNodeMenuProvider ? canOpen(player) : true
       39: Platform.INSTANCE.getMenuOpener()
       46: MenuOpener.openMenu(player, menuProvider)

[服务端打开]
  MenuOpenerImpl.openMenu(player, provider)                    (javap -c)
    1: provider instanceof ExtendedMenuProvider
   14: openExtendedMenu(player, extended)
        2: player.openMenu(provider, buf -> provider.getMenuCodec().encode(buf, provider.getMenuData()))

[RS 的 Grid 方块实体]
  GridBlockEntity implements NetworkNodeExtendedMenuProvider<GridData>   (javap -p)
    getMenuData()  → GridData.of(this)          （javap -c: 0: aload_0 ; 1: invokestatic GridData.of:(LGrid;)LGridData;）
    getMenuCodec() → GridData.STREAM_CODEC
    createMenu(int, Inventory, Player) → new GridContainerMenu(syncId, inv, this)   ← 包私有 ctor

[GridData 构造]
  GridData.of(Grid)                            (javap -c)
    5: grid.isGridActive()
   11: grid.getResources(PlayerActor.class).stream().map(GridData$GridResource::of).toList()
   39: grid.getAutocraftableResources()
```

**`GridData` 是什么**（`javap -p`）：

```
public final class GridData extends java.lang.Record {
  private final boolean active;
  private final java.util.List<GridData$GridResource> resources;
  private final java.util.Set<PlatformResourceKey> autocraftableResources;
  public static final StreamCodec<RegistryFriendlyByteBuf, GridData> STREAM_CODEC;
  public static GridData of(Grid);
}
public final class GridData$GridResource extends Record {   // ← public
  ResourceAmount resourceAmount();
  Optional<TrackedResource> trackedResource();
}
```

`STREAM_CODEC` 的组成（`javap -c` 的 `static {}`）：
`ByteBufCodecs.BOOL` × `collection(GridResource(ResourceCodecs.AMOUNT_STREAM_CODEC + StorageCodecs.TRACKED_RESOURCE_OPTIONAL_STREAM_CODEC))` × `collection(HashSet::new, ResourceCodecs.STREAM_CODEC)`。

`[master] local_src/.../common/grid/GridData.java:22,23,34` 与 2.0.9 完全同形。

**⇒ 我们要照的是**：让**自己的方块实体**实现
`com.refinedmods.refinedstorage.common.support.containermenu.NetworkNodeExtendedMenuProvider<GridData>`（该接口 `javap -p` 显示是
`interface NetworkNodeExtendedMenuProvider<T> extends ExtendedMenuProvider<T>, NetworkNodeMenuProvider`），
`getMenuData() → GridData.of(我们的 Grid 适配器)`、`getMenuCodec() → GridData.STREAM_CODEC`、
`createMenu(id, inv, p) → new RsccResourcePickerMenu(id, inv, ourGrid)`。

`ExtendedMenuProvider` / `NetworkNodeMenuProvider`（`javap -p`）：

```
public interface ExtendedMenuProvider<T> extends net.minecraft.world.MenuProvider {
  T getMenuData();
  StreamEncoder<RegistryFriendlyByteBuf, T> getMenuCodec();
}
public interface NetworkNodeMenuProvider extends net.minecraft.world.MenuProvider {
  NetworkNodeContainerProvider getContainerProvider();
  default boolean canOpen(ServerPlayer);     ← 权限检查；返回 false 时 RS 会发"无权限"提示而不开界面
}
```

> 工程既有同类写法（可作蓝本，但它走的是反射）：`src/main/java/.../item/AdvancedRemoteTerminalItem.java:640-687`
> 手搓 `ExtendedMenuProvider`，`createMenu` 反射构造 RS 原版菜单，最后 `Platform.INSTANCE.getMenuOpener().openMenu(player, provider)`。
> 本设计**不需要反射**——因为我们要的是**自己的**菜单子类，而 `AbstractGridContainerMenu` 的两个构造器都是 `protected`，可正常继承。

### 3.3 增量刷新链路（这是"复用一次"的核心，别自己写轮询）

```
GridNetworkNode.addWatcher(w, actorType)                       (javap -c)
   0: this.watchers
   6: w, actorType
   6: this.network != null ? network.getComponent(StorageNetworkComponent.class) : null
  31: GridWatcherManager.addWatcher(w, actorType, rootStorage)

GridWatcherManagerImpl.addWatcher(w, actorType, rootStorage)   (javap -c)
  13: if watchers.containsKey(w) throw new IllegalArgumentException("Watcher is already registered")   ← ⚠️ 同一菜单不能注册两次
  23: new GridWatcherRegistration(w, actorType)
  34: if rootStorage != null → attach(registration, rootStorage, false)
        → registration.attach(rootStorage, false)
          （内部注册 GridWatcherRootStorageListener(rootStorage, watcher, actorType) 到 rootStorage）

GridWatcherRootStorageListener.changed(MutableResourceList$OperationResult)     (javap -p)
    → watcher.onChanged(key, amount, trackedResource)

AbstractGridContainerMenu.onChanged(key, amount, tracked)       (javap -c)
   0: if (pendingUpdates == null) return;                        ← 客户端实例（构造器 A）在此静默
   7: pendingUpdates.onChanged(key, amount, tracked)

AbstractGridContainerMenu.broadcastChanges()                    (javap -c)
   0: super.broadcastChanges()
   4: if (pendingUpdates != null) pendingUpdates.tryFlush()      ← 每 tick 一次 flush

PendingGridUpdates                                              (javap -p)
  private final Map<Class<? extends ResourceKey>, RateLimiter> otherRateLimiters;
  private final RateLimiter itemRateLimiter, fluidRateLimiter, flushQueueRateLimiter;
  void tryFlush(); void onChanged(...);

GridUpdatePacket.handle(packet, ctx)                            (javap -c)
  10: ctx.getPlayer().containerMenu
  11: instanceof AbstractGridContainerMenu                       ← ★ 我们的子类通过
  42: menu.onResourceUpdate(resource, amount, tracked)

AbstractGridContainerMenu.onResourceUpdate(...)                 (javap -c)
  15: repository.update(resource, amount)
  26: updateOrRemoveTrackedResource(resource, tracked)
  33: updateResourceTypeWarning()

GridClearPacket.handle(ctx)   → containerMenu instanceof AbstractGridContainerMenu → menu.onClear()
GridActivePacket.handle(pkt)  → containerMenu instanceof GridWatcher → onActiveChanged(active)
```

网络切换时的重挂（`GridNetworkNode.setNetwork`，`javap -c`）：

```
  0: if (this.network != null) watchers.detachAll(this.network.getComponent(StorageNetworkComponent.class))
 30: super.setNetwork(newNetwork)      ← AbstractNetworkNode.setNetwork 内部再 attachAll
```

`AbstractGridContainerMenu.removed(Player)`（`javap -c`）：

```
  1: super.removed(player)
  5: if (this.grid != null) this.grid.removeWatcher(this)
```

**⇒ 结论**：**增量刷新完全免费**。我们只要：
1. 服务端菜单走构造器 B（传 `Grid`）；
2. `Grid.addWatcher/removeWatcher` 转发到**一个真实的 `GridNetworkNode` 实例**（`public class`，`public GridNetworkNode(long energyUsage)`，`public addWatcher/removeWatcher/setNetwork`）；
3. 其余不用管（限流、批量、每 tick flush、清空、active 变化、网络重建，全是 RS 的）。

### 3.4 客户端重建链路

```
AdvancedOpenScreenPayload.handle → createMenuScreen(name, menuType, windowId, buf)
  131: MenuScreens.getScreenFactory(menuType)
  132: menuType.create(windowId, mc.player.getInventory(), buf)     ← 我们的 IMenuTypeExtension 工厂
        → new RsccResourcePickerMenu(id, inv, GridData.STREAM_CODEC.decode(buf))    （= 构造器 A）
  133: mc.player.containerMenu = ((MenuAccess<?>) s).getMenu()
  134: mc.setScreen(s)
```

⇒ 客户端的 `repository` 由 `GridData.resources()` 填满；之后的每一次变化走 §3.3 的 `GridUpdatePacket`。

### 3.5 做一个"只读、用于选择"的网格，**最小需要哪些组件**

| 组件 | 服务端 | 客户端 | 备注 / 证据 |
|---|---|---|---|
| `Grid` 适配器（7 个方法） | ✅ 1 个类 | ❌ | `getResources`→`StorageNetworkComponent`；`getAutocraftableResources`→`AutocraftingNetworkComponent#getOutputs`；`isGridActive`→节点 `isActive`；`addWatcher/removeWatcher`→转发 `GridNetworkNode`；`canMenuStayOpen`→`true` 或按方块判定；`createOperations`→照 `AbstractGridBlockEntity` 那三行（`type.createGridOperations(rootStorage, new PlayerActor(sp))` + `SecuredGridOperations`） |
| `GridNetworkNode` 实例 | ✅ 1 个字段 | ❌ | `new GridNetworkNode(0L)` + `setNetwork(network)`；它就是 watcher 管理器 |
| `GridData` | ✅ `GridData.of(grid)` | 解码得到 | `STREAM_CODEC` 公开 |
| 菜单类 | ✅ | ✅（同一个类的两个构造器） | 必须自建（§1.3） |
| `MenuType` | ✅ | ✅ | `IMenuTypeExtension.create`（§①） |
| 屏幕类 | ❌ | ✅ | `extends GridScreen<OurMenu>`；覆写 `mouseClicked` |
| 网络访问 | `network.getComponent(StorageNetworkComponent.class)` | — | 本工程已有大量同类调用（§3.1 表） |
| **不需要** | `ResourceRepository`、`GridWatcher`、`Slot`、任何 mixin、任何 RS 注册表写入 | | §2.2 / §2.3 |

### 3.6 「点选资源」结果回传服务端的三条路（**推荐 b**）

取"被点中的资源键"：

```java
GridResource r = getCurrentGridResource();                 // public
PlatformResourceKey key = r.getResourceForRecipeMods();    // public
```

证据：`GridResource#getResourceForRecipeMods()` 在接口里声明；实现类 `AbstractGridResource<T extends PlatformResourceKey>` 的字节码：

```
  public PlatformResourceKey getResourceForRecipeMods();
     0: aload_0
     1: getfield #7    // Field resource:Lcom/refinedmods/refinedstorage/common/api/support/resource/PlatformResourceKey;
     4: areturn
```

即**直接返回 `protected final T resource`**（`ItemGridResource` / `FluidGridResource` 都没覆写它）。⇒ 物品、流体都能拿到精确的 `PlatformResourceKey`。

| 方案 | 能否零新增包 | 证据 | 代价 |
|---|---|---|---|
| **a) 自建 C2S 包** | ❌ 要注册 | 工程 `tools/check_payload_registration.py`（凡在 `network/` 下声明 `TYPE` 的包类都必须在 `RS_Create_Compat.registerPayloads`（`RS_Create_Compat.java:650`）里注册，否则运行期 NeoForge 直接断连） | 最通用（能带任意 NBT/复杂结构），但多一个包 + 一条自检约束 |
| **b) 复用 `ResourceFilterSlotChangePacket`（推荐）** | ✅ | `public final class ...ResourceFilterSlotChangePacket extends Record implements CustomPacketPayload`，含 `public static final PACKET_TYPE` / `STREAM_CODEC` / `public (int slotIndex, PlatformResourceKey resource)`；`C2SPackets` 是 `public final class`，`public static void sendResourceFilterSlotChange(PlatformResourceKey, int)` 内部就是 `Platform.INSTANCE.sendPacketToServer(new ResourceFilterSlotChangePacket(slotIndex, resource))`；落点：`handle` 里 `containerMenu instanceof AbstractResourceContainerMenu → handleResourceFilterSlotUpdate(int, PlatformResourceKey)` | 只能带「slotIndex:int + 资源键」，够用；语义上把"选择"伪装成"过滤槽更新"，需要在我们的菜单里**覆写 `handleResourceFilterSlotUpdate`** 接住 |
| **c) 复用 `PropertyChangePacket`** | ✅ | `public final class ...PropertyChangePacket(ResourceLocation propertyId, int value)`，public `PACKET_TYPE`/`STREAM_CODEC`；落点 `AbstractBaseContainerMenu.receivePropertyChangeFromClient(ResourceLocation, int)`；`ClientProperty` 的"侧边按钮切换"就是走这条 | **只能传 `int`**，资源没法无损编码（未证实有稳定 int 编码）⇒ 不推荐当主通道 |

**方案 b 的完整闭环**（本设计采用）：

```
客户端 GridScreen 子类：
  @Override public boolean mouseClicked(double mx, double my, int btn) {
      GridResource r = getCurrentGridResource();
      if (r != null && isOverStorageArea) {           // 注：isOverStorageArea 是 private，需自行用
                                                       // leftPos+7..leftPos+168 + 纵向范围 复刻（§2.3 给了字节码常量）
          PlatformResourceKey key = r.getResourceForRecipeMods();
          if (key != null) { C2SPackets.sendResourceFilterSlotChange(key, OUR_SLOT_INDEX); return true; }
      }
      return super.mouseClicked(mx, my, btn);          // 未命中 → 保留 RS 原行为（取出/放入/自动合成）
  }

服务端我们的菜单：
  @Override public void handleResourceFilterSlotUpdate(int index, PlatformResourceKey key) {
      if (index != OUR_SLOT_INDEX) return;
      // 服务端权威：校验类型是否被该方块允许 → 写进方块自己的 NBT → 同步客户端
  }
```

> 注：`AbstractGridScreen.isOverStorageArea(int,int)` 是 `private`，我们**不能覆写/调用**。要么在 `mouseClicked` 里复刻它的判定（常量已从字节码读出：`leftPos+7 ≤ x ≤ leftPos+168`，纵向用 `isInStretchedArea(...)`——后者在 `AbstractStretchingScreen` 里是 `protected final`，`javap -p` 可见）；要么干脆**不做区域判定**（网格界面里"有没有悬停资源"就等价于"鼠标在网格上"）。**推荐后者**（更简单，且界面上本来就没有别的可点区域在网格之外）。

---

## ④ D1 / D2 / D3 三种做法的对比与推荐

### 4.1 三种做法分别是什么

**D1：用 RS 真实网格（继承 RS 网格菜单/屏幕）**
* 菜单：`class RsccResourcePickerMenu extends AbstractGridContainerMenu`
* 屏幕：`class RsccResourcePickerScreen extends GridScreen<RsccResourcePickerMenu>`，只覆写 `mouseClicked`
* 数据：§3 的链路（`Grid` 适配器 + `GridData` + watcher）

**D2：用本工程既有 `ScrollSelectWidget<T>` 做一个"像网格那样"的选择器**
* 控件：`src/main/java/.../client/widget/ScrollSelectWidget.java`（`public class ScrollSelectWidget<T> extends AbstractWidget`，
  滚轮/点半边切换、tooltip 最多 16 行，`forOptions/titled/displaying/hinting/selectable` 链式 API；已在
  `client/screen/StepMachineSelectScreen.java:106-152` 用于"选机器"）
* 外形：`ScrollSelectWidget` 是**单行凹槽 + `◀`/`▶` 文本符**，**不是九宫格图标网格**
* 数据：可以照 §3.3 的 watcher 机制自建，也可以一次性 `getResources(PlayerActor.class)` 灌进去

**D3：混合（列表/交互用我们既有控件，数据与刷新照 RS 网格机制）**
* 控件：`ScrollSelectWidget` 或自绘列表
* 数据：`Grid` 适配器 + `GridData` + `GridUpdatePacket` 增量刷新（可直接把 `GridData`/`GridUpdatePacket` 当自己的载荷用）

### 4.2 对比表

| 维度 | **D1（RS 真实网格）** | **D2（`ScrollSelectWidget`）** | **D3（混合）** |
|---|---|---|---|
| "和精致存储一样"的**观感** | ✅ **一模一样**（同一贴图 `refinedstorage:textures/gui/grid.png`、同一 `GridScreen`、同一侧边按钮） | ❌ 单行文本凹槽，**不是**九宫格图标 | ⚠️ 取决于自绘投入；天然"像我们自己的界面"而不是"像 RS" |
| "和精致存储一样"的**操作** | ✅ 滚轮翻页/滚动条、左/右/中键、Shift 取整组、Ctrl 悬停、搜索语法高亮、侧边排序/视图按钮、tab 焦点——**全部免费** | ⚠️ 只有滚轮 + 点左右半边 + 搜索框（`StepMachineSelectScreen` 自己配的 `EditBox`） | ⚠️ 同上，且要自己实现滚动条与图标网格 |
| **浏览整个网络**（物品 + 流体） | ✅ 免费（`repository.getViewList()` 全量自绘，无槽位上限） | ✅ 能（只要把清单喂进去） | ✅ 能 |
| **搜索** | ✅ RS 查询解析器 + 语法高亮（`GridQueryParser` + `GridSearchBoxWidget`） | ⚠️ 朴素子串匹配（见 `StepMachineSelectScreen.applyFilter()`） | ⚠️ 同 D2 |
| **排序** | ✅ 4 种 × 2 方向（`GridSortingTypes` / `SortingDirection`），走 RS 配置持久化 | ❌ 要自己写 | ⚠️ 要自己写 |
| **视图过滤**（可合成/不可合成、物品/流体） | ✅ 免费 | ❌ 要自己写 | ⚠️ |
| **实时增量刷新** | ✅ 免费（§3.3） | ❌ 要自己实现（或每次打开重拉） | ✅ 可以用 RS 的（但控件不是 RS 的，要自己处理"列表变了"） |
| **耦合 RS 内部类** | ⚠️ 高：`AbstractGridContainerMenu` + `GridScreen` + `GridData`（**全是内部类，无 `@ApiStatus` 承诺**） | ✅ 低（只耦合 `ResourceKey`/`GridResource` 之类公开 API，甚至完全可以用 `TrackedResourceAmount`） | ⚠️ 中：数据侧仍耦合 `GridData`/`GridUpdatePacket` |
| **代码量** | ✅ 最小（菜单 ~10 行 + 屏幕 ~30 行 + `Grid` 适配器 ~60 行） | ❌ 最大（网格布局 + 图标渲染 + 滚动条 + 搜索 + 排序 + 刷新，基本等于重写一遍 RS 网格） | ⚠️ 中（控件要自绘，刷新要接） |
| **"不自己从零写一套"** | ✅ 完全满足 | ❌ **正是用户明确否掉的那种做法** | ⚠️ 部分 |
| **与两个方块右键开界面的衔接** | ✅ 自然（见 §4.4） | ✅ 自然 | ✅ 自然 |
| **与既有 `ChildConfigScreen` 体系的一致性** | ⚠️ 网格子界面**不是** `ChildConfigScreen`（它是 `AbstractContainerScreen`，会自己绘制背景，不是"父界面之上的小弹窗"） | ✅ 完全一致（`StepMachineSelectScreen` 就是 `ChildConfigScreen` 子类） | ✅ 一致 |
| **版本升级风险** | ⚠️ 高（§5.1） | ✅ 低 | ⚠️ 中 |
| **自检冲突** | ✅ 无锚定；但屏幕类**必须放 `client/` 包**（§5.2） | ✅ 无 | ✅ 无 |

### 4.3 推荐

> **推荐 D1，并把"选资源"做成方块界面的一个子界面入口。**
>
> 理由（按用户原话逐条对齐）：
> 1. 用户要求「**能够浏览整个精致存储网络中的所有东西**」→ D1 用 RS 自己那条 `StorageNetworkComponent.getResources(PlayerActor.class)` + `repository.getViewList()`，**且是实时的**（watcher 增量推送）；D2 得自己维护一份快照。
> 2. 用户要求「**外观与操作要和 RS 自己的界面一样**」→ D1 是**同一个类、同一张贴图、同一套控件**，不是"模仿"；D2 是"另画一个"。
> 3. 用户要求「**不要自己从零写一套**，仅仅是从**它自己的界面**复用一遍」→ D1 的代码量是 ~100 行；D2 是重写 RS 网格。
> 4. 用户要求「**不靠 mixin 硬塞**」→ D1 需要 0 个 mixin（`AbstractGridContainerMenu` 的 `public`/`protected` 缝足够）。
>
> **D2 的定位**：如果后续"选资源"要做成**父界面之上的小弹窗**（`ChildConfigScreen` 那种 200×128 的浅灰面板），D2 在**视觉习惯**上更贴本工程；但它在**功能**上（全网络浏览 + 搜索 + 排序 + 实时刷新）要重写一遍，与用户"不要写两套"的要求直接冲突。
> **D3 的定位**：只有在"必须用本工程视觉体系、又不允许重写数据层"时才考虑；它省掉的是数据层（约 1/3 工作量），省不掉控件层（约 2/3）。
>
> ⚠️ **一个必须让用户拍板的取舍**：D1 的子界面**不是 `ChildConfigScreen`** —— RS 的 `GridScreen` 是标准 `AbstractContainerScreen`，会**占满屏幕并绘制自己的背景**，不像 `StepMachineSelectScreen` 那样在父界面之上叠一个小面板。如果用户坚持"子窗口形态"，那就是 D1 的**观感**与**本工程既有子窗口习惯**之间的冲突，只能二选一（见 §⑥ 待拍板 3）。

### 4.4 与「两个方块右键各自打开自己的界面」的衔接

**用户已定**：两个新方块（网络资源接收器 / 网络资源请求器）各自右键打开自己的界面，配置存方块自己的 NBT。
**因此「选资源」有两种子形态：**

| 子形态 | 做法 | 代价 | 风险 |
|---|---|---|---|
| **① 方块界面里"内嵌"一个网格** | 让方块界面**自己就是** `GridScreen` 子类（即"菜单 = 网格菜单"），把该方块自己的配置控件做成**额外侧边按钮**或占位在 `bottomHeight` 区域 | 需要在 `AbstractGridScreen` 的固定布局里挤出空间（`GridScreen` 硬编码 `imageWidth=193/imageHeight=176/inventoryLabelY=75`；想改就得直接继承 `AbstractGridScreen` 并自己传 `bottomHeight`，参 §2.1 的 `PatternGridScreen 193×249` 先例） | 与"方块配置"耦合，配置控件多时布局吃紧 |
| **② 方块界面 → 再打开一个"像 RS 网格一样"的子界面（推荐）** | 方块自己是**普通界面**（沿用工程既有 `AbstractContainerScreen`/`ChildConfigScreen` 习惯）；界面里一个"选择资源"按钮 → 服务端再 `openMenu` 一个**独立的网格菜单**（`RsccResourcePickerMenu` + `RsccResourcePickerScreen`）；选完 → 回传（§3.6 b）→ 服务端写 NBT → **重新打开方块界面**（或直接 `setScreen(parent)` 返回） | 需要一次菜单切换（RS 原生无线网格关掉后不会自动返回父界面；本工程既有做法是 `Minecraft.getInstance().setScreen(parent)`，见 `ChildConfigScreen`） | 菜单切换期间玩家背包同步由原版保证（`ServerPlayer.openMenu` 会先 `closeContainer()`），**不丢物品**；但"返回父界面"需要自己在客户端做 |

**推荐 = ②**，理由：
* 与用户已定的「两个方块各自右键开界面」**完全兼容**：方块界面是"配置界面"，网格是"选择器界面"，职责清晰；
* **不需要改动 RS 网格的布局**（直接用 `GridScreen` 原样，= "和精致存储一样"最大化）；
* 与工程既有"父界面 + 子界面"习惯一致（`ChildConfigScreen` 体系、`StepMachineSelectScreen` 的三处复用）；
* 用户说的「**他要提供一个和精致存储一样的一个界面**」——② 提供的正是**一个独立、完整、和 RS 一样的**网格界面。

**骨架代码（示意，非最终代码；本轮不落地）**：

```java
// ── 菜单（common 侧，无客户端类型）──────────────────────────────
public class RsccResourcePickerMenu extends AbstractGridContainerMenu {
    public static final int SELECT_INDEX = 0;         // 复用过滤槽通道的下标
    private final Grid grid;                          // 仅服务端非 null

    // 服务端
    public RsccResourcePickerMenu(int id, Inventory inv, Grid grid) {
        super(RS_Create_Compat.RSCC_RESOURCE_PICKER_MENU.get(), id, inv, grid);
        this.grid = grid;
        resized(0, 0, 0);                             // 与 GridContainerMenu 同款（javap 证据）
    }
    // 客户端（由 IMenuTypeExtension 工厂调用）
    public RsccResourcePickerMenu(int id, Inventory inv, GridData data) {
        super(RS_Create_Compat.RSCC_RESOURCE_PICKER_MENU.get(), id, inv, data);
        resized(0, 0, 0);
    }

    @Override public void handleResourceFilterSlotUpdate(int idx, PlatformResourceKey key) {
        if (idx != SELECT_INDEX) return;
        // 服务端权威：校验 + 写入方块 NBT + 同步
    }
}

// ── 屏幕（client 包）────────────────────────────────────────────
public class RsccResourcePickerScreen extends GridScreen<RsccResourcePickerMenu> {
    public RsccResourcePickerScreen(RsccResourcePickerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }
    @Override public boolean mouseClicked(double mx, double my, int btn) {
        GridResource r = getCurrentGridResource();               // public
        if (r != null && btn == 0) {
            PlatformResourceKey key = r.getResourceForRecipeMods();   // public
            if (key != null) {
                C2SPackets.sendResourceFilterSlotChange(key, RsccResourcePickerMenu.SELECT_INDEX);
                Minecraft.getInstance().setScreen(parentBlockScreen);  // 返回方块界面
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }
}

// ── 方块实体（服务端）──────────────────────────────────────────
public class RsccRequesterBlockEntity extends ... implements NetworkNodeExtendedMenuProvider<GridData> {
    private final GridNetworkNode gridNode = new GridNetworkNode(0L);   // public ctor
    private final Grid gridAdapter = new RsccNetworkGridAdapter(this::network);  // 见 §3.1 表

    @Override public GridData getMenuData() { return GridData.of(gridAdapter); }
    @Override public StreamEncoder<RegistryFriendlyByteBuf, GridData> getMenuCodec() { return GridData.STREAM_CODEC; }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
        return new RsccResourcePickerMenu(id, inv, gridAdapter);
    }
    // getContainerProvider()/getName() 按 NetworkNodeExtendedMenuProvider 要求补
}

// ── 注册（client/ClientInit.java，已在 client 包内）──────────────
event.register(RS_Create_Compat.RSCC_RESOURCE_PICKER_MENU.get(), RsccResourcePickerScreen::new);
```

> `GridScreen` 的第三个参数是 `Component title`，`ScreenConstructor<M,U>.create(m, inv, title)` 的签名与之一致（`[sources] MenuScreens.java:130`），所以 `RsccResourcePickerScreen::new` 可直接当工厂。

---

## ⑤ 风险

### 5.1 RS 版本升级（2.0.x → 2.1.x）会不会炸？

#### 5.1.1 结论

* **会炸的风险是真实的**：本方案依赖的 4 个类全部在 `com.refinedmods.refinedstorage.**common**.` / `.**common.api**.` 之下，**都是 RS 内部实现类**（没有 `@ApiStatus.Internal` 也没有任何"稳定"承诺；RS 官方文档的 API 面是 `api/` 与 `common/api/` 包）。
* **但短期内风险低于直觉**：把 2.0.9 与 `local_src/external/RefinedStorage`（post-2.0.9 master）**逐条对照**，这 4 个扩展缝**签名完全一致**。

#### 5.1.2 2.0.9 vs master 逐条对照

| 扩展缝 | 2.0.9（javap） | master（源码行号） | 一致? |
|---|---|---|---|
| `AbstractGridContainerMenu` 类头 | `public abstract class ... extends AbstractResourceContainerMenu implements GridWatcher, GridInsertionStrategy, GridExtractionStrategy, GridScrollingStrategy, ScreenSizeListener, CancelablePreviewProvider, GridSortingTypes$TrackedResourceProvider` | `[master] .../common/grid/AbstractGridContainerMenu.java:67-69` 完全相同 | ✅ |
| 构造器 A | `protected AbstractGridContainerMenu(MenuType<? extends AbstractGridContainerMenu>, int, Inventory, GridData)` | `[master] :100-105` 同 | ✅ |
| 构造器 B | `protected AbstractGridContainerMenu(MenuType<? extends AbstractGridContainerMenu>, int, Inventory, Grid)` | `[master] :137-142` 同 | ✅ |
| 构造器 B 的关键副作用 | `grid.addWatcher(this, PlayerActor.class)` + `new PendingGridUpdates(serverPlayer)` | `[master] :151-152` 同 | ✅ |
| `AbstractGridScreen` 类头 | `public abstract class AbstractGridScreen<T extends AbstractGridContainerMenu> extends AbstractStretchingScreen<T>` | `[master] .../screen/AbstractGridScreen.java:62` 同 | ✅ |
| 屏幕构造器 | `protected AbstractGridScreen(T, Inventory, Component, int)` | `[master] :102-105` 同（形参名 `bottomHeight`） | ✅ |
| **点击缝** | `public boolean mouseClicked(double,double,int)`（字节码偏移 1300）；`protected void mouseClickedInGrid(int, GridResource)`（偏移 1493） | `[master] :567` / `:636` 同逻辑（`:580` 走 `mouseClickedInGrid(clickedButton, resource)`，`:584` 走插入分支，`:604-608` `canExtract` 仍为 private） | ✅ |
| `GridScreen` | `public class GridScreen<T extends AbstractGridContainerMenu> extends AbstractGridScreen<T>`；`public GridScreen(T, Inventory, Component)` | `[master] .../screen/GridScreen.java:11` / `:14` 同 | ✅ |
| `GridData` | `public final class ... extends Record`；`public static GridData of(Grid)`；`public static final StreamCodec<...> STREAM_CODEC` | `[master] .../common/grid/GridData.java:22` / `:23` / `:34` 同 | ✅ |
| `GridContainerMenu` 硬编码类型 | `Menus.INSTANCE.getGrid()` | `[master] .../common/grid/GridContainerMenu.java` 仍 `Menus.INSTANCE.getGrid()` | ✅（我们的规避理由不变） |
| 客户端注册入口 | `RegisterMenuScreensEvent` | 未变（NeoForge 侧 API） | ✅ |

#### 5.1.3 与"只用 `Capabilities`/公开 API"做法的对比

| 做法 | 版本升级时的行为 | 功能代价 |
|---|---|---|
| **只用 `Capabilities` / `api/` / `common/api/`**（如只用 `StorageNetworkComponent#getResources`） | 升级几乎不炸；但**只能拿到"数据"**，拿不到"和 RS 一样的界面" | 拿不到：搜索语法、排序、视图切换、实时增量刷新、九宫格渲染 —— 也就是要重写 D2 |
| **本方案（继承内部类）** | 编译期就能发现（`javap` 已经证明签名在 2.0.9→master 未变；一旦变，`javac` 直接报错，不会静默错行为） | ✅ 拿到全部界面能力 |
| **mixin 硬塞**（改 RS 的屏幕/菜单） | 最容易炸（mixin 目标方法改名/改描述符即静默失效或崩服），且需 `verify_mixin_shadows.py` 等自检护送 | 用户已明确排除 |

#### 5.1.4 缓解手段（建议写进实现阶段）

1. **把耦合面收敛到 3 个类**：`RsccResourcePickerMenu` / `RsccResourcePickerScreen` / `RsccNetworkGridAdapter`。别把 `AbstractGridScreen`/`GridData` 泄漏到其它文件。
2. **升级时先跑一次「签名探针」**：写一个 `tools/selfcheck_rs_return_contract.py`（本轮不写）用 `javap` 断言
   `AbstractGridContainerMenu` 的两个构造器描述符、`AbstractGridScreen.mouseClicked` 的修饰符、`GridData.of(Grid)`、`GridScreen` 的构造器仍在。
   这类"锚定外部 jar 签名"的自检在本工程已有先例（`tools/_probe_rs_class_diff.py`、`tools/_probe_rs_monitor_geometry.py`）。
3. **降级路径已就绪**：一旦升级破坏 D1，可以**只换屏幕层**（`RsccResourcePickerScreen` 改成 D2/D3 的 `ScrollSelectWidget` 版），菜单层与数据层（§3）**不用动**——因为数据层只依赖 `GridData` 这个 record 与 `StorageNetworkComponent`。

### 5.2 与本工程既有自检的冲突

**grep 结论**：

```powershell
Select-String -Path "tools\*.py" -Pattern "RegisterMenuScreens|AbstractGridScreen|GridContainerMenu|GridScreen|ResourcePicker" 
# → 0 命中（本文件写作时）
```

⇒ **没有任何自检锚定到我们要新增/继承的这些类**。

但有 3 个自检**会约束我们的实现位置/写法**，必须遵守：

| 自检 | 约束 | 对本设计的含义 |
|---|---|---|
| `tools/selfcheck_round47_dedicated_server_safety.py`（`:62-67` `EXTERNAL_CLIENT_PREFIXES` = `net.minecraft.client.*` / `com.mojang.blaze3d.*` / `org.lwjgl.*` / `net.neoforged.neoforge.client.*`；`:277` `client_files = {rel for rel in sources if "/client/" in "/" + rel} \| mixin_client_files`） | `client/` 包**之外**的文件不得直接引用客户端类型；`@EventBusSubscriber` 必须声明 `value = Dist.CLIENT`；`client/` 之外的高危文件必须在 `ALLOWLIST` 里逐条登记 | **① 屏幕类必须放 `cretae.cookiewyq.rs_create_compat.client.screen`（或 `client/` 下）；② `RegisterMenuScreensEvent` 的注册代码必须留在 `client/ClientInit.java`（它已经在 `:139-164`）；③ 菜单类与 `Grid` 适配器必须**只依赖 common 类型**（`AbstractGridContainerMenu`、`GridData`、`StorageNetworkComponent` 都是 common 侧，不会引入 `net.minecraft.client.*`）** |
| `tools/check_payload_registration.py` | `network/` 下任何声明了 `TYPE` 的包类都必须在 `RS_Create_Compat.registerPayloads`（`RS_Create_Compat.java:650`）注册 | 本设计 §3.6 **走方案 b，不新增包** ⇒ **不触发**该自检。若最终改走方案 a，必须同步注册 |
| `tools/_run_all_selfchecks.py` | 一键跑全部 `selfcheck_*.py`，任一失败即整体失败 | 落地时必须跑一遍 |

### 5.3 其它风险（都有证据）

| 风险 | 证据 | 缓解 |
|---|---|---|
| RS 的排序/搜索记忆是**全局配置**，会跟 RS 网格互相影响 | `AbstractGridContainerMenu` 里 `getSortingDirection/setSortingDirection/getSortingType/setSortingType` 全部读写 `Platform.getConfig().getGrid()`；`setSearchBox` 在 `Config.getGrid().isRememberSearchQuery()` 为真时把 `lastSearchQuery`（`private static String`）灌进搜索框 | 接受（"和 RS 一样"本来就是这个行为）；如需隔离，只能在打开我们的网格前后手动 set/还原 |
| 十字键 `tenth anniversary` 丝带会在特定日期出现 | `AbstractGridScreen` 的 `tenthAnniversaryRibbonVisible` 字段 + `shouldDisplayTenthAnniversaryRibbon()`（`[master] :120-130`：2026-03-20..22）；`mouseClicked` 偏移 18-45 会打开 `TenthAnniversaryScreen` | 这是 RS 原生行为，**保留即可**（要隐藏只能覆写 `mouseClicked` 并绕过；不建议） |
| 资源类型过滤**没有公开 setter** | `resourceTypeFilter` 是 `private` 字段；只有 `getResourceType()` / `toggleResourceType()`；`toggleResourceType()` 走 `Config.getGrid()` + `PlatformRegistry.nextOrNullIfLast`（循环 item → fluid → null）。`createBaseFilter()` / `createResourceTypeFilter()` / `createViewTypeFilter()` 全是 `private` | 想让"接收器只收流体"这类限制生效，**放到服务端**：覆写 `handleResourceFilterSlotUpdate` 时按方块配置校验并拒绝/忽略（服务端才是权威） |
| 想往 `repository` 注入自定义过滤器是**脆弱**的 | `ResourceRepository#setFilterAndSort(ResourceRepositoryFilter<T>)` 是公开的（且 `javap -c` 显示**返回旧 filter**）；但 `AbstractGridContainerMenu.onSearchTextChanged` 每次搜索框内容变化都会 `repository.setFilterAndSort(andFilter(QUERY_PARSER.parse(text), createBaseFilter()))`，**会把我们注入的 filter 冲掉** | 不要走这条路（除非同时接管搜索框，而 `GridSearchBoxWidget` 是包私有，接管不了）。见 §⑥ 未证实 2 |
| 依赖 RS 的贴图/精灵 | `assets/refinedstorage/textures/gui/grid.png`、`.../sprites/grid/row.png`、`.../sprites/widget/side_button/grid/**` 均在 RS jar 里 | RS 是硬依赖（`build.gradle:146` `api("com.refinedmods.refinedstorage:refinedstorage-neoforge:${refinedstorage_version}")`），无需自带 |
| **同一 `GridWatcher` 不能注册两次** | `GridWatcherManagerImpl.addWatcher` 偏移 13-22：`if (watchers.containsKey(w)) throw new IllegalArgumentException("Watcher is already registered")` | 菜单实例每次 `openMenu` 都新建，天然不冲突；**但绝不要**把菜单对象缓存复用 |
| `AbstractGridContainerMenu` 构造器 B 会 `(ServerPlayer) playerInventory.player` | 偏移 43-50（`checkcast ServerPlayer`） | 只在服务端真实玩家打开时用构造器 B；绝不在客户端/假玩家路径调用它 |
| 气（gas）扩展 | 现工程 `docs/GAS_COMPAT_DESIGN.md` 与 `docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md` §⑥ 已确认 RS 2.0.9 **完全没有气体支持** | 本设计**不含气体**；扩展缝是 `RefinedStorageApi#addGridResourceRepositoryMapper(Class, ResourceRepositoryMapper<GridResource>)` + `getResourceTypeRegistry()` 注册一个新的 `ResourceType` + `AbstractGridResource<T>` 子类。**本轮未证实该路径端到端可用** |

---

## ⑥ 未证实 / 需拍板

### 6.1 未证实（我没有证据，不猜）

1. **`AbstractGridScreen.isOverStorageArea(int,int)` 的"复刻"是否在拉伸（`bottomHeight`/`visibleRows` 变化）时仍精确**
   —— 我只从字节码读出横向常量 `leftPos+7 .. leftPos+168`，纵向走 `isInStretchedArea(int)`（`AbstractStretchingScreen` 的 `protected final`）。
   在本设计里**推荐不做区域判定**（网格界面上"悬停到资源"即等价），所以此点不影响结论。
2. **往 `repository` 注入额外过滤器的"可行但脆弱"结论**：`setFilterAndSort` 返回旧 filter 是实证；
   但"搜索框每次按键会不会真的冲掉"我只从 `onSearchTextChanged` 的字节码推断（它确实每次都重新 `andFilter(... createBaseFilter())`），
   **没有实测**。⇒ 标为未证实，且已建议不要走这条路。
3. **气体（Mekanism 化学）作为第三种 `GridResource` 端到端可用**：只知道有
   `RefinedStorageApi#addGridResourceRepositoryMapper` / `getResourceTypeRegistry()` / `AbstractGridResource<T>` 三块料，
   **未证实**能凑成完整的网格条目（渲染、tooltip、`canExtract`、`onExtract` 都要自己写）。
4. **`rs_create_compat` 与 RS 2.0.9 的"两个新方块"当前状态** —— 本文件写作时的实测（`git status --porcelain` + `grep src/main/java`）：
   * **资源已存在**（未跟踪）：`assets/rs_create_compat/blockstates/network_resource_receiver.json`、`.../network_resource_requester.json`；
     `models/block/network_resource_{receiver,requester}{,_active}.json`；`models/item/...`；`textures/block/network_resource_{receiver,requester}_*.png` 共约 20 张。
   * **Java 侧不存在**：`src/main/java/**` 里没有任何 `*Receiver*.java` / `*Requester*.java`，`RS_Create_Compat.java` 的 `BLOCKS`/`ITEMS`/`MENUS` 里也没有对应条目；`lang/*.json` 里也没有对应 key。
   * ⇒ 本设计是**为这套"有图无代码"的待建方块预留**的选择器方案。
5. **RS 2.1.x 的真实破坏性变更**：只能用 master 源码做"当前未变"的对照，**不能**预测 2.1.x。
6. **`Platform.INSTANCE.sendPacketToServer` 从 `Screen#mouseClicked` 里同步调用是否有线程问题**：RS 自己的 `C2SPackets.send*` 也是同一路径（`PlatformImpl.sendPacketToServer` → `PacketDistributor.sendToServer`），
   `javap` 只证明路径相同，**未实测**在点击回调里的时序。

### 6.2 需要用户拍板（这些是**决策**，不是事实）

1. **选完资源后去哪**：
   (a) 关闭网格、**重新打开方块界面**（本文件推荐，符合"父→子→父"习惯）；
   (b) 网格界面里**留一个"确定"按钮**（需要直接继承 `AbstractGridScreen` 并自己传更大的 `bottomHeight`，= 放弃 `GridScreen` 的现成 193×176 布局）；
   (c) 选中即生效、**网格界面不关**（可以连选多个，适合"多标记"型方块）。
2. **点击语义**：是「左键=选中、右键/Shift+左键=仍按 RS 原样取出」，还是「点击=纯选中，**完全禁用**取出/放入」？
   （纯选中 = 覆写 `mouseClicked` 后永不调 `super`；本文件推荐"左键选中，其余按键 fallthrough 给 `super`"）
3. **子界面形态**：接受 D1 的 `GridScreen` **全屏网格**（和 RS 一模一样），还是坚持本工程 `ChildConfigScreen` 的**小弹窗**形态（那就要走 D2/D3）？
   这是本次唯一"鱼与熊掌"的取舍。
4. **是否允许"只显示某类资源"**：如果需要（例如接收器只收流体），是否接受**在服务端校验**（UI 仍显示全部，选了不合法就拒绝/提示）？
5. **是否本轮就动代码**：本文件按任务约束**未写任何业务代码**。

### 6.3 本轮**未做**（边界声明）

* 未修改任何 `.java`；未修改 `run/`；未新增资源/贴图/lang；未跑编译；未启动游戏。
* 未实测「打开网格 → 点击资源 → 服务端收到」的运行时行为（全部结论为静态证据：`javap` / 反汇编 / 补丁源码 / 工程源码）。
* 未做气体扩展的端到端验证。

---

## 附录 A：本文用到的可复现命令

```powershell
# ① RS 2.0.9 类清单（按 grid 过滤）
& "C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" -c "
import zipfile,re
z=zipfile.ZipFile(r'D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar')
for n in z.namelist():
    if n.endswith('.class') and re.search(r'grid', n, re.I): print(n)
"

# ② 关键类的可见性 / 构造器
$RSJAR = "D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar"
& D:\java21\bin\javap.exe -p -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu
& D:\java21\bin\javap.exe -p -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.GridContainerMenu
& D:\java21\bin\javap.exe -p -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen
& D:\java21\bin\javap.exe -p -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.screen.GridScreen
& D:\java21\bin\javap.exe -p -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.GridData
& D:\java21\bin\javap.exe -p -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.GridBlockEntity

# ③ 关键行为（构造器副作用 / 点击链 / 数据链）
& D:\java21\bin\javap.exe -p -c -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu
& D:\java21\bin\javap.exe -p -c -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen
& D:\java21\bin\javap.exe -p -c -classpath $RSJAR com.refinedmods.refinedstorage.common.grid.GridData
& D:\java21\bin\javap.exe -p -c -classpath $RSJAR com.refinedmods.refinedstorage.common.support.packet.c2s.C2SPackets
& D:\java21\bin\javap.exe -p -c -classpath $RSJAR com.refinedmods.refinedstorage.common.support.packet.c2s.ResourceFilterSlotChangePacket
& D:\java21\bin\javap.exe -p -c -classpath $RSJAR com.refinedmods.refinedstorage.api.network.impl.node.grid.GridNetworkNode

# ④ 全 jar：谁引用了网格类 + 有没有 instanceof 白名单
& "C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" -c "
import zipfile
z=zipfile.ZipFile(r'D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar')
pats=[b'common/grid/GridContainerMenu', b'common/grid/WirelessGridContainerMenu', b'common/grid/AbstractGridContainerMenu', b'common/grid/screen/GridScreen', b'common/grid/GridData']
for n in z.namelist():
    if n.endswith('.class') and any(p in z.read(n) for p in pats): print(n)
"

# ⑤ NeoForge 侧（注册事件 + 客户端开屏 + openMenu）
$NF = "D:\gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\21.1.248\9a97bebbd7641685052d59368bb32c56ecb9c913\neoforge-21.1.248-universal.jar"
& D:\java21\bin\javap.exe -p -c -classpath $NF net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
# 补丁源码（行号引用来源）：
#   build\moddev\artifacts\neoforge-21.1.248-sources.jar
#     net/minecraft/server/level/ServerPlayer.java:1114-1169
#     net/minecraft/client/multiplayer/ClientPacketListener.java:1244-1248
#     net/minecraft/client/gui/screens/MenuScreens.java:41,43-55,75-79,123-130
#     net/minecraft/world/inventory/MenuType.java:11,53-63
#     net/neoforged/neoforge/common/extensions/IMenuTypeExtension.java:25-27
#     net/neoforged/neoforge/network/handlers/ClientPayloadHandler.java:115-136
#     net/neoforged/neoforge/network/payload/AdvancedOpenScreenPayload.java:30-42
```

## 附录 B：一句话给实现阶段的交接

> 继承 `GridScreen<OurMenu>`（`javap` 证明它是 `public class`、构造器 `public (T, Inventory, Component)`），
> 菜单继承 `AbstractGridContainerMenu` 并**在自己的 super 里传自己的 `MenuType`**（`GridContainerMenu` 会硬编码 RS 的，不能用），
> `MenuType` 用 `IMenuTypeExtension.create`（照 `RS_Create_Compat.java:340-344`），
> 屏幕注册照 `client/ClientInit.java:139-164`；
> 数据照 `GridData.of(grid)` + watcher（`GridNetworkNode`）+ `GridUpdatePacket`；
> 点击覆写 `public boolean mouseClicked`，命中 `getCurrentGridResource().getResourceForRecipeMods()` 后
> 用 `C2SPackets.sendResourceFilterSlotChange(key, idx)` 回传，服务端覆写 `handleResourceFilterSlotUpdate` 接住并写方块 NBT。
> **0 mixin、0 新包、0 RS 注册表改动。**
