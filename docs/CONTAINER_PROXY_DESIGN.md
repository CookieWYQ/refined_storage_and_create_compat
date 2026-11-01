# 容器代理器（Container Proxy）API 调研与设计

> 版本基线：MC 1.21.1 / NeoForge 21.1.248 / Refined Storage **2.0.9** / Create **6.0.10-280** / Sophisticated Backpacks **3.26.3.2158**。
> 本文只做调研与设计，**不包含任何要落地的 Java 代码**；所有代码片段均为设计伪代码。
> 证据等级：**A** = 直接来自 jar / javap / 反编译源码的原文与行号；**B** = 同项目源码但版本不完全一致（已注明）；**C** = 由 A/B 代码路径推断、未做运行时实测。

---

## 0. 结论速览（先看这 12 条）

1. **物品级 Inventory 的唯一通用入口**是 `ItemStack#getCapability(Capabilities.ItemHandler.ITEM)`（NeoForge 1.21.1 的 `ItemCapability`，**不是**旧版 `Capability` / `ICapabilityProvider`）。空栈恒返回 `null`。证据：`ItemCapability.java:20-31,90-105`；javap `IItemStackExtension`。**A**
2. **原版潜影盒有 ItemHandler**，但是 **NeoForge 补的桥**：只对 17 个原版潜影盒物品注册 `ComponentItemHandler(stack, DataComponents.CONTAINER, 27)`，不是“任何带 `CONTAINER` 组件的物品都自动有”。证据：`CapabilityHooks.java:114-131`。**A**
3. **Sophisticated Backpacks 3.26.3.2158 注册了 `Capabilities.ItemHandler.ITEM`**（6 种背包）。空 `contentsUuid` 时返回 `EmptyItemHandler`，即**拒绝外部写入**。证据：jar 内 javap `ModItems` 字节码 + 源码 `ModItems.java:474-477`（3.25.69，B）。**A/B**
4. **RS 2.0.9 的存储磁盘没有 `ItemHandler.ITEM`**，也不是物品栏：它只有 `StorageContainerItem#resolve(StorageRepository, ItemStack)`，磁盘里只存一个 **UUID 组件**，真正的资源在 world `StorageRepository`（SavedData）。⇒ **磁盘不能按 ItemHandler 语义代理**。证据：`StorageContainerItem.java:13-18`、`StorageContainerItemHelperImpl.java:36-39,162-166`、`StorageRepositoryImpl.java:20`、`ModInitializer.java:455-490`。**A**
   - 另：RS 2.0.9 里**没有** `refinedstorage:disk` 这个 id；实际 id 是 `1k_storage_disk` / `4k_storage_disk` / … / `*_fluid_storage_disk`（`ContentIds.java:97,101`，lang 键 `item.refinedstorage.1k_storage_disk`）。**A**
5. **本模组自己的通用储存磁盘同样不可代理**：`UniversalStorageDiskItem extends AbstractStorageContainerItem`（`item/UniversalStorageDiskItem.java:30`），与 RS 磁盘同构。**A**
6. **方块级**：`Capabilities.ItemHandler.BLOCK` 只能按“世界坐标 + 方块状态 + BE”查询；**一个 `BlockItem` 在物品栏里时没有“未来的 BE”可查**，通用 API 做不到“代理它放下后的 BE”。**A/C**
7. 确实存在“物品（含方块物品）自带容器数据”的例子：**方块物品**里的 Create 工具箱 `TOOLBOX_INVENTORY`，以及普通物品的包裹 `PACKAGE_CONTENTS`、过滤器 `FILTER_ITEMS`、链接控制器 `LINKED_CONTROLLER_ITEMS`。但 **Create 6.0.10 没有为这些物品注册 `ItemHandler.ITEM`**；工具箱只在**放置后**注册 `ItemHandler.BLOCK`。⇒ “带组件数据” ≠ “有 Inventory 能力”。证据：`ToolboxBlock.java:79,117`、`ToolboxBlockEntity.java:78-84`、`AllDataComponents.java:236-238`。**A**
8. **面分离可以做到**：`registerBlockEntity(Capabilities.ItemHandler.BLOCK, TYPE, (be, side) -> be.handlerFor(side))` 的 `side` 就是查询面（可空）。NeoForge 不会为 `null` 合成“六面合并”，原样把 `null` 传给 provider。证据：`BlockCapability.java:198-211`；`IBlockCapabilityProvider` javap；现有 `RsccChamberImportStrategy.java:1577-1600` 的“先 unsided、再逐面”写法。**A**
9. **RS 2.0.9 的单个外部存储方块只查询它朝向的那一面**：`direction = 方块朝向`，`incomingDirection = direction.getOpposite()`，`BlockCapabilityCache.create(ItemHandler.BLOCK, level, pos, incomingDirection)`。所以**一个**外部存储不会同时看到 A/B 两面。证据：`AbstractExternalStorageBlockEntity.java:87-102`、`CapabilityCacheImpl.java:18-24`。**A**
10. **但 RS 不会按“源方块”去重**：每个外部存储方块各建一个 `ExternalStorageNetworkNode` / `ExternalStorage`，网络存储的总量是逐 source 求和（`CompositeStorageImpl.getStored()`，`CompositeStorageImpl.java:153-155`）；2.0.9 里唯一的“自指/接口”特判是 `InterfaceBlock`。⇒ **同一底层库存（无论 handler 实例是否相同）若被两个面各接一个外部存储，同一批物品会被计两次**；**若本方块同时是 RS 网络节点、又把同一内容物通过能力暴露出去，则构成真正的自指环**。证据：`ExternalStorageNetworkNode.java:35-37`、`ExternalStorage.java:59-75,135-137`、`CompositeStorageImpl.java:153-155`、`AbstractExternalStorageBlockEntity.java:92-102`、`InterfaceExternalStorageProviderImpl.java:24,34,42`。**A（代码路径）+ C（运行期后果未实测）**
11. **推荐关系**：容器代理器**不要做 RS 网络节点**，做成“能力提供者（外部存储目标）”。RS 玩家在需要的面上贴 RS 外部存储总线即可。这样既不重复计数，也不用碰 RS 的网络节点/能量/图更新内部 API。
12. **推荐面分配（v1）**：`FRONT（朝向）= 内容物`、`BACK（背面）= 代理对象`、其余 4 面 `null`；`side == null` 返回**内容物**。**理由：每个底层库存只在一个面暴露**（关键不是 handler 实例，而是底层库存；即使每次返回新实例，只要底层是同一批物品，RS 仍会重复计数），从根上避免“两个外部存储贴两个面 ⇒ 同一库存计两次”；同时 unsided 查询有确定语义。**（需用户拍板，见 §6）**

---

## 1. 调研方法与可复现证据

### 1.1 版本核验

| 组件 | 证据 |
|---|---|
| NeoForge 21.1.248 | `build/moddev/artifacts/neoforge-21.1.248-merged.jar`；反编译补丁源码在 `local_src/neoforge_src/` |
| Create 6.0.10-280 | `local_src/create_src/META-INF/neoforge.mods.toml`：`version = "6.0.10"` |
| RS 2.0.9 | `build/rs209_probe.jar` 与 Gradle 缓存 `refinedstorage-neoforge-2.0.9.jar` 均为 **4,360,871** 字节；`META-INF/neoforge.mods.toml` 中 `version = "2.0.9"` |
| RS 反编译 | 用 Vineflower 1.10.1 反编译 RS 2.0.9 到 **`D:\MODS\_rs209_src`**（工作区外，避免污染仓库） |
| Sophisticated Backpacks 3.26.3.2158 | 本机可搜索的整合包目录（`run/mods`、`D:\MODS` 下各实例）中未定位到该 jar；为避免结论依赖版本，直接从 Modrinth 官方 CDN 下载**同版本** `sophisticatedbackpacks-1.21.1-3.26.3.2158.jar`（1,251,706 字节）到 `D:\MODS\_sb_3263.jar`，用 javap 直接读它。URL：`https://cdn.modrinth.com/data/TyCTlI4b/versions/AhPnzNtJ/sophisticatedbackpacks-1.21.1-3.26.3.2158.jar` |
| Sophisticated Core | 为看 `InventoryIOHandler`，另下载 `sophisticatedcore-1.21.1-1.5.17.2426.jar`（`https://cdn.modrinth.com/data/nmoqTijg/versions/gtPF4VTh/...`）；SB 3.26.3.2158 的依赖声明是 `[1.5.1,)`（jar 内 `neoforge.mods.toml`），版本兼容 |

> 注意：工作区 `local_src/rs_src` 是 **RS 2.0.0** 的反编译源码（`neoforge.mods.toml` 写的是 2.0.0）。本文所有 RS 行号来自 **2.0.9 的重新反编译**，不是那份旧源码。

### 1.2 关键命令

```text
javap -p -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar \
      net.neoforged.neoforge.common.extensions.IItemStackExtension
javap -p -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar \
      'net.neoforged.neoforge.capabilities.Capabilities$ItemHandler'
javap -p -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar \
      net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
javap -c -p -classpath D:\MODS\_sb_3263.jar \
      net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems
```

Python `zipfile` 只用于**读 jar 条目/文本**，未解压到工作区。

---

## 2. 一、物品级 Inventory 能力

### 2.1 `Capabilities.ItemHandler.ITEM` 的确切用法（1.21.1）

javap 原文（A）：

```text
public final class net.neoforged.neoforge.capabilities.Capabilities$ItemHandler {
  public static final BlockCapability<IItemHandler, Direction> BLOCK;
  public static final ItemCapability<IItemHandler, java.lang.Void> ITEM;
}
```

`ItemCapability` 的查询契约（A）：

```text
$ javap -p ... net.neoforged.neoforge.common.extensions.IItemStackExtension
public default <T, C> T getCapability(ItemCapability<T, C>, C);
public default <T> T getCapability(ItemCapability<T, java.lang.Void>);
```

```java
// ItemCapability.java:20-31（NeoForge 21.1.248 反编译源码）
// IItemHandler maybeHandler = stack.getCapability(Capabilities.ItemHandler.ITEM);
// if (maybeHandler != null) { ... }
```

空栈保护（A）：

```java
// ItemCapability.java:90-105
public T getCapability(ItemStack stack, C context) {
    if (stack.isEmpty()) {
        // ... disallow empty stacks to provide capabilities ...
        return null;
    }
    for (var provider : providers.getOrDefault(stack.getItem(), List.of())) {
        var ret = provider.getCapability(stack, context);
        if (ret != null) return ret;
    }
    return null;
}
```

要点：

- 物品能力**没有 side 上下文**（`Void`），只有 `stack`；所以“面向/面”在物品级不存在，只存在于**方块**级。
- 每次 `getCapability` 都可能**新建**一个 handler 实例（例如潜影盒的 lambda 每次都 `new ComponentItemHandler`），所以**不要按 handler 身份做缓存/比较**；按 `ItemStack` 身份缓存。
- 1.21.1 里没有 `ContainerItem` 这个类（javap 报 `找不到符号: net.minecraft.world.item.ContainerItem`），所以不要用 `instanceof ContainerItem` 做探测（见 §2.5）。

### 2.2 潜影盒：原版有没有暴露 `ItemHandler`？

**结论：原版本身没有能力系统；在 NeoForge 21.1.248 下，17 个原版潜影盒物品有 `ItemHandler.ITEM`，由 NeoForge 补丁注册。**

证据（A）：

```java
// CapabilityHooks.java:80-88：放置后的潜影盒方块 —— BLOCK 能力
var sidedVanillaContainers = List.of(..., BlockEntityType.SHULKER_BOX);
for (var type : sidedVanillaContainers) {
    event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type, SidedInvWrapper::new);
}

// CapabilityHooks.java:114-131：潜影盒物品 —— ITEM 能力
event.registerItem(Capabilities.ItemHandler.ITEM,
    (stack, ctx) -> new ComponentItemHandler(stack, DataComponents.CONTAINER, 27),
    Items.SHULKER_BOX, Items.BLACK_SHULKER_BOX, ..., Items.YELLOW_SHULKER_BOX);
```

- 槽位数：**27**。
- 组件：**`DataComponents.CONTAINER`**（vanilla `ItemContainerContents`）。
- 原版侧证据：潜影盒物品本身就是 `BlockItem`，且默认组件就是 `CONTAINER`（`Items.java:570-572`，来自 `neoforge-21.1.248-sources.jar`）：
  ```java
  public static final Item SHULKER_BOX = registerBlock(
      new BlockItem(Blocks.SHULKER_BOX,
          new Item.Properties().stacksTo(1).component(DataComponents.CONTAINER, ItemContainerContents.EMPTY))
  );
  ```
  原版没有 `IItemHandler` 概念；`Container` 是方块实体接口，不是物品栏接口；物品级只有组件数据。**NeoForge 的 `CapabilityHooks` 才是把“组件”桥接成 `IItemHandler` 的那层。**
- 关键限制：这是**逐物品显式注册**的 17 个原版潜影盒；**不是**“任何带 `CONTAINER` 组件的物品自动获得能力”。
- `ComponentItemHandler.isItemValid` 用 `stack.canFitInsideContainerItems()`（`ComponentItemHandler.java:143`），所以**潜影盒不能塞潜影盒**（与 vanilla 一致）。

### 2.3 Sophisticated Backpacks 3.26.3.2158：jar 实测

**结论：注册了 `Capabilities.ItemHandler.ITEM`；但“空 contentsUuid”的背包返回 `EmptyItemHandler`，外部写入会失败。**

javap 字节码原文（A，直接读 3.26.3.2158 jar）：

```text
private static void registerCapabilities(RegisterCapabilitiesEvent);
   0: aload_0
   1: getstatic  #395  // Field Capabilities$ItemHandler.ITEM:Lnet/neoforged/neoforge/capabilities/ItemCapability;
   4: invokedynamic #401 // ICapabilityProvider
   9: bipush 6
  11: anewarray  #346  // class net/minecraft/world/level/ItemLike
  16: getstatic  #339  // BACKPACK
  28: getstatic  #357  // COPPER_BACKPACK
  40: getstatic  #360  // IRON_BACKPACK
  52: getstatic  #363  // GOLD_BACKPACK
  64: getstatic  #366  // DIAMOND_BACKPACK
  76: getstatic  #369  // NETHERITE_BACKPACK
  86: invokevirtual #405 // RegisterCapabilitiesEvent.registerItem(ItemCapability, ICapabilityProvider, ItemLike[])
```

对应的 provider lambda 原文（A）：

```text
private static IItemHandler lambda$registerCapabilities$81(ItemStack, Void);
   0: aload_0
   1: invokestatic  #442  // getCapabilityWrapper(ItemStack)
   5: aload_2
   6: invokeinterface #480 // IBackpackWrapper.getContentsUuid():Optional
  11: invokevirtual #483  // Optional.isEmpty():Z
  14: ifeq 23
  17: getstatic  #486     // EmptyItemHandler.INSTANCE
  20: goto 29
  23: aload_2
  24: invokeinterface #492 // IBackpackWrapper.getInventoryForInputOutput():ITrackedContentsItemHandler
  29: areturn
```

源码对照（**B**：本机 `D:\MODS\SophisticatedBackpacks-1.21.x` 是 **3.25.69** 源码，不是 3.26.3.2158；但注册代码一致）：

```java
// ModItems.java:473-477
private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerItem(Capabilities.ItemHandler.ITEM, (stack, v) -> {
        IBackpackWrapper backpackWrapper = BackpackWrapper.fromStack(stack);
        return backpackWrapper.getContentsUuid().isEmpty()
            ? EmptyItemHandler.INSTANCE
            : backpackWrapper.getInventoryForInputOutput();
    }, BACKPACK.get(), COPPER_BACKPACK.get(), ..., NETHERITE_BACKPACK.get());
```

补充（B）：

- `BackpackBlockEntity#getExternalItemHandler` 也已经把同一套 handler 暴露为 **`ItemHandler.BLOCK`**（`BackpackBlockEntity.java:186-208`），放置后的背包同样可被自动化访问。
- `getInventoryForInputOutput()` 返回的是 `InventoryIOHandler#getFilteredItemHandler()`（`BackpackWrapper.java:191-197`），会叠加升级的**输入/输出过滤器**（SophisticatedCore `InventoryIOHandler` 有 `addFilters(...)`，javap 可见），所以“有 handler”不等于“什么都收”。
- 类型上它是 `ITrackedContentsItemHandler`，而 `ITrackedContentsItemHandler extends IItemHandlerSimpleInserter, IItemHandlerSimpleExtractor, IInsertBlockOverride`；其中 `IItemHandlerSimpleInserter extends IItemHandlerModifiable`（javap 原文），所以它确实是一个完整的 `IItemHandler`，代理器的内容物视图可以安全地按 3 参 `insertItem/extractItem` 委托。
- 背包内容不在物品 NBT：`getBackpackContentsNbt()` 从 **`BackpackStorage`**（`SavedData`，`BackpackStorage.java:25`）按 UUID 取；`markBackpackContentsDirty()` 调 `setDirty()`（`BackpackWrapper.java:182-188`）。物品上只有 `contentsUuid` 组件（`BackpackWrapper.java:344`）。
- **空 UUID 的后果**：新背包/未初始化背包的 `getContentsUuid()` 为空 ⇒ provider 直接返回 `EmptyItemHandler.INSTANCE` ⇒ 代理器的“内容物面”会看到 0 格、`insertItem` 全拒。**这是一个必须在 UI/文档里提示的行为**（用户需要先打开一次背包/让 UUID 生成）。

### 2.4 RS 2.0.9 磁盘：不是容器，也没有 ItemHandler

**结论：RS 磁盘没有 `ItemHandler.ITEM`，且其“内容”不是物品栏，而是 world `StorageRepository`（SavedData）里的 `SerializableStorage`；按 ItemHandler 语义不可代理。**

证据（A）：

```java
// StorageContainerItem.java:13-18
public interface StorageContainerItem {
   Optional<SerializableStorage> resolve(StorageRepository var1, ItemStack var2);
   Optional<StorageInfo> getInfo(StorageRepository var1, ItemStack var2);
   static Predicate<ItemStack> stackValidator() { ... }
}
```

```java
// AbstractStorageContainerItem.java:29-38
public abstract class AbstractStorageContainerItem extends Item implements StorageContainerItem {
   public Optional<SerializableStorage> resolve(StorageRepository repo, ItemStack stack) {
      return this.helper.resolveStorage(repo, stack);
   }
   ...
}
```

```java
// StorageContainerItemHelperImpl.java:36-39
public Optional<SerializableStorage> resolveStorage(StorageRepository storageRepository, ItemStack stack) {
   return this.getId(stack).flatMap(storageRepository::get);
}

// StorageContainerItemHelperImpl.java:162-166
private Optional<UUID> getId(ItemStack stack) {
   return Optional.ofNullable((UUID) stack.get(DataComponents.INSTANCE.getStorageReference()));
}
```

```java
// StorageRepositoryImpl.java:20
public class StorageRepositoryImpl extends AbstractPlatformSavedData implements StorageRepository {
   public static final String NAME = "refinedstorage_storages";
   ...
}
```

```java
// ModInitializer.java:455-490（RS 2.0.9 的 NeoForge 能力注册）
event.registerBlockEntity(ItemHandler.BLOCK, ...getDiskDrive(), (be, side) -> new InvWrapper(be.getDiskInventory()));
event.registerBlockEntity(ItemHandler.BLOCK, ...getInterface(), ...);
event.registerBlockEntity(ItemHandler.BLOCK, ...getPatternGrid(), ...);
event.registerBlockEntity(ItemHandler.BLOCK, ...getDiskInterface(), ...);
event.registerItem(EnergyStorage.ITEM, ...);   // 463/471/477/482 全是 EnergyStorage.ITEM
// 没有任何 registerItem(ItemHandler.ITEM, ...)
```

要点：

- 磁盘物品只带 **`DataComponents.INSTANCE.getStorageReference()`（UUID）**；数据在 `StorageRepository`（`refinedstorage_storages` SavedData），不在 stack 的组件里。
- 即使你用 `StorageRepository` 强行把 `SerializableStorage` 包装成 `IItemHandler`，那也等于把**整个 RS 存储网络**暴露给外部存储 ⇒ 自指环，禁止。
- **RS 2.0.9 没有 `refinedstorage:disk` 这个 id**：实际是 `1k_storage_disk`、`4k_storage_disk`、`16k_storage_disk`、`64k_storage_disk`、`creative_storage_disk`、`64b_fluid_storage_disk`、…（`ContentIds.java:97,101`；lang 键 `item.refinedstorage.1k_storage_disk` 等）。
- 本模组 `UniversalStorageDiskItem extends AbstractStorageContainerItem`（`item/UniversalStorageDiskItem.java:30`），同构，同样**不可代理**。

### 2.5 通用探测：怎么判断“这个 ItemStack 带 Inventory”？

**推荐判据（唯一通用）：**

```java
IItemHandler h = stack.getCapability(Capabilities.ItemHandler.ITEM);
boolean proxyable = h != null;
```

理由：

- `ItemCapability#getCapability` 已处理空栈（`ItemCapability.java:91-97`）。
- 能力是**逐物品注册**的；`getCapability` 非空 ⇔ 该物品的 mod 明确提供了 ItemHandler（`CapabilityHooks.java:114` 的潜影盒、`ModItems.java:474` 的背包）。
- **不要**用 `instanceof ContainerItem`：1.21.1 里没有这个类（javap 无法解析，jar 内只有 `DispensibleContainerItem`）；潜影盒物品是 `BlockItem`（`Items.java:570` 用 `registerBlock(...)` 注册），背包是 `BackpackItem extends ItemBase`，都不靠这个接口。
- **不要**用“有 `DataComponents.CONTAINER`”当判据：NeoForge 只给 17 个原版潜影盒注册能力；Create 工具箱用的是自定义组件 `TOOLBOX_INVENTORY`，没有能力。
- 可以额外用 `stack.getComponentsPatch()` / `stack.has(DataComponents.CONTAINER)` 做**展示/提示**，但不能作为“可写代理”的判据。

### 2.6 写回：通过能力写进去后，会不会自动保存？

#### 2.6.1 潜影盒：会（写回 `DataComponents.CONTAINER`，随 ItemStack 保存）

证据（A）：

```java
// ComponentItemHandler.java:31 类声明：public class ComponentItemHandler implements IItemHandlerModifiable
// ：32-34 字段
protected final MutableDataComponentHolder parent;      // 就是那个 ItemStack
protected final DataComponentType<ItemContainerContents> component;

// ：187-196
protected void updateContents(ItemContainerContents contents, ItemStack stack, int slot) {
    ...
    this.parent.set(this.component, ItemContainerContents.fromItems(list));  // 写回组件
    this.onContentsChanged(slot, oldStack, stack);
}
```

- `insertItem` / `extractItem` / `setStackInSlot` 最终都走 `updateContents`（`ComponentItemHandler.java:70,106,130`）。
- 因此**写的是 `ItemStack` 自己的组件**；只要这个 stack 被 BE 的 NBT 正常保存，内容就保存了。
- `ItemStack.CODEC` 明确包含 components：

```java
// ItemStack.java:103-110（build/moddev/artifacts/neoforge-21.1.248-sources.jar）
public static final Codec<ItemStack> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.create(
    p -> p.group(
        ITEM_NON_AIR_CODEC.fieldOf("id")...,
        ExtraCodecs.intRange(1, 99).fieldOf("count").orElse(1)...,
        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
            .forGetter(s -> s.components.asPatch())
    )...));
```

- 结论：**潜影盒内容 = stack 的组件；BE 保存 stack 即保存内容**。不需要额外 `setChanged` 之外的“写盘”动作，但**必须把同一个 stack 实例交回/保存在 BE 里**（不要写到一个副本上）。

#### 2.6.2 背包：会（写到 world `BackpackStorage`，物品只存 UUID）

证据（A/B）：

```java
// BackpackWrapper.java:182-188（B：3.25.69 源码）
private CompoundTag getBackpackContentsNbt() {
    return BackpackStorage.get().getOrCreateBackpackContents(getOrCreateContentsUuid());
}
private void markBackpackContentsDirty() {
    BackpackStorage.get().setDirty();
}
```

```java
// BackpackStorage.java:25
public class BackpackStorage extends SavedData { ... }
```

- 背包 handler 的写入最终改的是 `BackpackStorage` 里那个 `CompoundTag`；变更回调 `markBackpackContentsDirty()` → `SavedData#setDirty()`。
- 物品 stack 上只有 `contentsUuid`（`BackpackWrapper.java:344`），所以**保存物品 ≠ 保存内容**；内容由世界存档的 SavedData 保存。
- 对代理器的影响：**“写回自动保存”成立**，但保存发生在 `BackpackStorage`，不是你的 BE NBT；你的 BE 只需要保存代理对象 stack（UUID 在里面）。

#### 2.6.3 RS 磁盘：没有“写回物品栏”这回事

- 没有 ItemHandler；`resolve()` 返回的是 `SerializableStorage`，修改它等于修改 RS 的全局存储记录（`StorageRepository`）。**不应**在代理器里做。

### 2.7 物品级实测结论表

| 对象 | `ItemHandler.ITEM` | 内容存放 | 写回 | 能否代理 | 证据 |
|---|---|---|---|---|---|
| 原版潜影盒（17 色） | **有**（27 格） | `DataComponents.CONTAINER`（`ItemContainerContents`） | 组件写回 stack，随 BE NBT 保存 | **能** | `CapabilityHooks.java:114-131`；`ComponentItemHandler.java:187-196`；`ItemStack.java:103-110` |
| Sophisticated Backpack（6 种） | **有** | `BackpackStorage`（SavedData，按 UUID）；物品只有 UUID | handler 写 NBT，`setDirty()` | **能**，但空 UUID ⇒ `EmptyItemHandler`；受升级过滤器影响 | jar javap（3.26.3.2158）；`ModItems.java:474-477`；`BackpackWrapper.java:182-188,191-197` |
| RS 存储磁盘 / 本模组通用磁盘 | **无** | `StorageRepository`（SavedData）里的 `SerializableStorage` | 不是物品栏，无写回概念 | **不能**（会自指） | `StorageContainerItem.java:13-18`；`StorageContainerItemHelperImpl.java:36-39,162-166`；`ModInitializer.java:455-490` |
| Create 工具箱（BlockItem） | **无** | 自定义组件 `TOOLBOX_INVENTORY`（`ToolboxInventory extends ItemStackHandler`） | 放置时读组件、破坏时写回组件 | **通用不能**；要写 per-mod adapter | `ToolboxBlock.java:79,117`；`ToolboxBlockEntity.java:78-84`；`ToolboxInventory.java:30` |

### 2.8 “别的模组允许外部写入吗？”——逐条说明

| 对象 | 是否有 owner/权限校验 | 写入限制 | 证据 |
|---|---|---|---|
| 原版潜影盒 | **没有** | 只受 `canFitInsideContainerItems()` 限制（不能塞潜影盒）；`insertItem` 不区分玩家/机器 | `ComponentItemHandler.java:62-72,143`（A） |
| Sophisticated Backpack | **未发现 owner/权限校验**：item capability lambda 只检查 `contentsUuid`，`getInventoryForInputOutput()` 不接收玩家；但会叠加升级的输入/输出过滤 | 空 UUID ⇒ `EmptyItemHandler`；升级过滤可拒收；SB 的“locked/设置”是否影响自动化**未证实**（源码有 `BackpackSettingsHandler`，但未逐路径核实） | jar javap `ModItems`；`BackpackWrapper.java:191-197`；`InventoryIOHandler.addFilters`（javap） |
| RS 存储磁盘 | **不适用**（没有 ItemHandler） | 无物品栏可写；`StorageRepository` 是全局存储，不能当物品栏 | `StorageContainerItem.java:13-18`；`StorageContainerItemHelperImpl.java:36-39` |
| Create 工具箱 | **不适用**（没有 `ItemHandler.ITEM`） | 只有放置后的 BE 有 `ItemHandler.BLOCK`；物品组件需 per-mod adapter | `ToolboxBlockEntity.java:78-84`；`ToolboxBlock.java:79,117` |
| 其他模组容器 | **未证实** | 可能有 owner/security（如 AE2 安全终端类）、只读 handler、`FakePlayer` 检查；本机未逐一测试 | — |

**设计含义**：对象面只能验证“这个 stack 有 ItemHandler”，**不能**验证“这个 handler 允许外部写入”。真正的写入许可只能通过 `insertItem` 的返回值判断；内容物面也必须把 `insertItem` 的 remainder 当作正常结果（R5/R15）。

---

## 3. 二、方块级 Inventory 能力

### 3.1 `Capabilities.ItemHandler.BLOCK` 的用法（现有代码已用过）

javap 原文（A）：

```text
public final class Capabilities$ItemHandler {
  public static final BlockCapability<IItemHandler, Direction> BLOCK;
}
public interface IBlockCapabilityProvider<T, C> {
  T getCapability(Level, BlockPos, BlockState, BlockEntity, C);
}
RegisterCapabilitiesEvent:
  void registerBlock(BlockCapability<T,C>, IBlockCapabilityProvider<T,C>, Block...);
  <T,C,BE extends BlockEntity> void registerBlockEntity(BlockCapability<T,C>, BlockEntityType<BE>,
                                                        ICapabilityProvider<? super BE,C,T>);
```

- 有 BE 的方块**优先用 `registerBlockEntity`**（只在该 BE 类型上查，少一次 `getBlockEntity`）；`registerBlock` 适合无 BE / 纯状态方块，它的 provider 也拿得到 `side` 和 `be`。
- 本模组已有写法：`RsccChamberImportStrategy.itemHandlerAt` 先查 `null` 面、再逐 `Direction.values()` 查（`support/RsccChamberImportStrategy.java:1577-1600`）。这正是“无面 / 有面”两种查询并存的实际证据。
- 另一个现成模式：`NetworkResourceReceiverBlockEntity.registerCapabilities` 用 `direction == null ? ... : null`（`block/entity/NetworkResourceReceiverBlockEntity.java:808-815`），即**只允许 unsided 查询**。

### 3.2 “代理一个方块物品”是什么意思？能不能代理它放下后的 BE？

**结论：通用 API 做不到。**

- `Capabilities.ItemHandler.BLOCK` 的查询签名需要 `Level + BlockPos (+ BlockState + BlockEntity)`（`BlockCapability.java:198-211`）；一个躺在物品栏里的 `BlockItem` 没有 pos，也没有 BE。
- 你无法从 `BlockItem` 拿到“它将来放置后 BE 的能力”：BE 的能力由 `registerBlockEntity` 的 lambda 在**世界中的 BE 实例**上返回；BE 的字段（库存）可能在放置时由组件/掉落物/`setPlacedBy` 初始化（例如 Create 工具箱的 `ToolboxBlock.java:79`），**没有统一入口**。
- 模拟放置（临时 level / 假 pos）来查询 BE 能力：需要完整的世界/邻接/调度上下文，且第三方 BE 可能假设 `level != null`、`getBlockPos()` 合法；**不推荐，也不属于稳定 API**。⇒ **明确说“做不到（通用）”**。

### 3.3 确实存在“方块物品自带容器数据”的情况，但它不是 ItemHandler

Create 6.0.10 的反编译源码（A）。**注意：下表中只有“工具箱”是方块物品；包裹/过滤器/链接控制器是普通物品**——列出来是为了说明“Create 的组件式容器数据”并不自动等于 `ItemHandler`：

| 组件 | 用途 | 证据 |
|---|---|---|
| `AllDataComponents.TOOLBOX_INVENTORY`（`DataComponentType<ToolboxInventory>`） | 工具箱方块物品携带 8×4 格库存 | `AllDataComponents.java:236-238`；`ToolboxInventory.java:30`（`extends ItemStackHandler`） |
| `AllDataComponents.PACKAGE_CONTENTS` | 包裹物品携带内容 | `AllDataComponents.java:301-303` |
| `AllDataComponents.FILTER_ITEMS` | 列表过滤器 | `AllDataComponents.java:114-116` |
| `AllDataComponents.LINKED_CONTROLLER_ITEMS` | 链接控制器 | `AllDataComponents.java:231-233` |

工具箱的读写时机（A）：

```java
// ToolboxBlock.java:79（放置时从物品组件读入 BE）
be.readInventory(stack.get(AllDataComponents.TOOLBOX_INVENTORY));

// ToolboxBlock.java:117（getCloneItemStack / 破坏时把 BE 写回物品组件）
item.set(AllDataComponents.TOOLBOX_INVENTORY, tb.inventory);
```

但 **Create 6.0.10 没有为工具箱物品注册 `ItemHandler.ITEM`**：

```java
// ToolboxBlockEntity.java:78-84
public static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK,
        AllBlockEntityTypes.TOOLBOX.get(),
        (be, context) -> be.inventory
    );
}
// 全仓 create_src 中 grep "ItemHandler.ITEM" ⇒ 0 命中（没有任何 registerItem(Capabilities.ItemHandler.ITEM, ...)）
```

⇒ 结论：

- “方块物品自带容器数据”**存在**（工具箱、包裹、过滤器等），但它们是**自定义组件**，不是 `ItemHandler`；Create 也没有把工具箱物品注册成 `ItemHandler.ITEM`。
- 因此通用代理器**只能支持“物品级 ItemHandler”**；对 Create 工具箱这类，只能写 **per-mod adapter**（读 `TOOLBOX_INVENTORY`，包一个 `IItemHandler`，并在写回时 `stack.set(...)`），不属于通用能力。
- 对**原版潜影盒**这种“方块物品 + 物品能力”的特例，物品级已经覆盖，不需要“代理它放下后的 BE”。

### 3.4 方块级结论与替代

| 情况 | 结论 |
|---|---|
| `BlockItem` 且 mod 注册了 `ItemHandler.ITEM`（潜影盒、背包物品） | **按物品级代理**；不要试图代理 BE |
| `BlockItem` 只带自定义容器组件（Create 工具箱；普通物品的包裹/过滤器见 §3.3） | 通用**做不到**；需 per-mod adapter；v1 不支持 |
| `BlockItem` 放下后 BE 有 `ItemHandler.BLOCK`（Create 工具箱 BE、背包 BE） | 只有“已放置的方块”能查；物品栏中的物品**查不到** |
| 其他任意方块 | 同理：必须在世界中按 pos 查询 |

**替代方案（推荐）**：v1 只承诺“物品级 ItemHandler 代理”。对 Create 工具箱这类，先列入“未来 per-mod adapter”，并在 tooltip/文档明确“不是所有带 NBT 的方块物品都能代理”。

---

## 4. 三、面分离

### 4.1 一个方块如何对不同面暴露不同能力

```java
// 设计伪代码
event.registerBlockEntity(
    Capabilities.ItemHandler.BLOCK,
    RS_Create_Compat.CONTAINER_PROXY_BLOCK_ENTITY.get(),
    (be, side) -> be.handlerFor(side)   // side 可为 null
);
```

- `side` 来自查询方：`level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, be, side)`；NeoForge 把 `context`（这里是 `Direction`）**原样**传给 provider（`BlockCapability.java:198-211`）。
- 不要在 provider 里做“缓存成单个 handler 字段”的优化，除非你同时处理 `null` 和六个面；**按面返回不同对象**是合法的。
- 注意：`Capabilities.ItemHandler.BLOCK` 被 NeoForge 标记为 **proxyable**（`CapabilityHooks.java:55-57`）。这意味着第三方“能力代理方块/管道”可能把你的能力**转发**到别处；因此每个面的返回值必须是确定、稳定、可重复查询的。

### 4.2 `side == null`（无面/内部）怎么处理？

**建议：`null` 返回“内容物”handler（主库存）；绝不要返回“对象 + 内容物”的合并 handler。**

理由：

1. NeoForge **不会**把 `null` 解释成“六个面合并”：`BlockCapability#getCapability` 直接把 `context` 传给 provider（`BlockCapability.java:198-211`）；`Level#getCapability(..., side)` 只是查询。**返回什么完全由你决定**。
2. 大量调用方（包括本模组自己的 `RsccChamberImportStrategy.itemHandlerAt`）会**先查 `null` 面**，拿不到再逐面扫（`RsccChamberImportStrategy.java:1586-1600`）。如果 `null` 返回 `null`，这些调用方会按 `Direction.values()` 的固定顺序（DOWN, UP, NORTH, SOUTH, WEST, EAST）挑到**第一个非空面**；这个面可能是“对象面”，取决于方块朝向 ⇒ **行为随朝向漂移**。
3. 返回内容物，等于声明“这个方块的默认库存 = 被代理对象的内容物”，与方块用途一致；对象面成为**显式**接口，只能按面访问。
4. 合并 handler（对象槽 + 内容槽）会让 RS 同时看到“容器物品”和“内容物”，并在同一 handler 内产生**双重语义**；虽然资源 key 不同，但会破坏“一个 handler = 一类接口”的约定，也会让 §4.4 的重复计数更难排查。**禁止**。

**与本模组现有代码的衔接**：`RsccChamberImportStrategy.itemHandlerAt` 先查 `null` 面（`:1586-1587`），因此它会把容器代理器当成“内容物容器”来插入/抽取；如果某个机器/步骤想放的是**容器物品本身**，必须改为显式按 `BACK` 面查询，不能走 `itemHandlerAt`。这一点要在接入时写清楚，否则会出现“机器把潜影盒塞进内容物”的误用。

补充：如果代理槽为空，内容物 handler 返回 0 格（或空实现），而对象面仍返回 1 格空槽；`null` 面建议仍返回内容物 handler（空），保持“默认库存”语义稳定。

### 4.3 面分配建议（推荐方案 + 备选）

#### 方案 A（推荐，v1）：`FRONT = 内容物`，`BACK = 代理对象`，其余 4 面 = `null`

```text
        [UP]   null
[WEST] null  FRONT(内容物)  null [EAST]
        [DOWN] null
        [BACK] 代理对象（容器物品）
```

- `side == null` → 内容物。
- `side == FACING`（正面）→ 内容物。
- `side == FACING.getOpposite()`（背面）→ 代理对象。
- 其余 → `null`。

> 约定：`FACING` 是**方块正面**（放置时朝玩家，`getHorizontalDirection().getOpposite()` 语义），与本模组现有水平朝向机器保持一致；不要把它和“玩家看向的方向”混用。

**理由（按重要性排序）：**

1. **避免 RS 重复计数**：每个底层库存只在一个面暴露。即使玩家在正面和背面各贴一个 RS 外部存储，它们看到的是**不相交**的两套资源（容器物品 vs 内容物），不会把同一批物品计两次；如果把“内容物库存”暴露在 4 个面，玩家在任意两个面各贴一个外部存储，**同一批内容物就会被两个 storage 各计一次**（RS 不去重，见 §4.4）。
2. **语义直观**：正面是“内容物端口”（接管道/外部存储，像普通容器），背面是“装/卸容器”的槽口（像磁盘驱动器/背包架）。贴图/模型只需在正面和背面做区分。
3. **`null` 有确定语义**：unsided 调用方拿到内容物，不会随朝向漂移。
4. **实现最简**：`FACING` 一个水平朝向即可；不需要 6 个独立 handler。

#### 方案 B（备选，用户原提案）：`LEFT/RIGHT = 对象`，`UP/DOWN/FRONT/BACK = 内容物`

可实现，但**必须接受/规避以下问题**：

- 4 个内容物面返回**同一个**内容物 handler ⇒ 玩家在任意两个内容物面各贴一个 RS 外部存储，RS 会把同一批内容物计两次（§4.4 结论 2）。**除非**你在文档/UI 明确“每个 handler 只允许接一个外部存储”，或者加配置只启用一个内容物面。
- 2 个对象面返回同一个对象 handler ⇒ 同理，两个外部存储会把同一个代理对象计两次。
- 需要按 `FACING` 计算 6 个面的角色，旋转/扳手改变朝向后必须 `invalidateCapabilities`（见 §5.3）。

**建议**：默认方案 A；把方案 B 做成 `Config` 枚举（`FRONT_BACK` / `SIDES` / `SINGLE_CONTENT`），默认 `FRONT_BACK`，并在 Jade/工具提示里对“多面同 handler”给出警告。

> **如果你的“输入面/输出面”是指方向性（只进/只出），而不是“对象/内容物”**：可以在 handler 层让 `insertItem` 或 `extractItem` 返回拒绝（`ItemStack.EMPTY` / 原栈），面仍然共用同一底层库存。但 RS 外部存储会**同时**做 insert/extract，单向面会让 RS 认为该存储只能进/只能出（并可能影响自动合成），所以 **RS 只应接双向的内容物面**。建议 v1 不做方向性限制；若要做，作为每个面的独立配置，并在 Jade 标注。

#### 方案 C（不推荐）：6 面都返回“合并 handler”

- 直接违反“两种接口”的需求；RS 会把容器物品和内容物混在一个 storage 里；不采用。

### 4.4 ⚠️ 关键风险：RS 外部存储会不会把 A/B 都当存储、造成重复计数/环路？

#### 4.4.1 RS 2.0.9 的实际查询路径（A）

```java
// AbstractExternalStorageBlockEntity.java:87-102
Direction direction = AbstractDirectionalBlock.tryExtractDirection(this.getBlockState());
if (direction != null) {
    Direction incomingDirection = direction.getOpposite();
    BlockPos sourcePosition = this.worldPosition.relative(direction);
    if (serverLevel.getBlockState(sourcePosition).getBlock() instanceof InterfaceBlock) {
        this.mainNetworkNode.initialize(new InterfaceProxyExternalStorageProvider(serverLevel, sourcePosition));
    } else {
        this.mainNetworkNode.initialize(new CompositeExternalStorageProvider(
            RefinedStorageApi.INSTANCE.getExternalStorageProviderFactories().stream()
                .map(factory -> factory.create(serverLevel, sourcePosition, incomingDirection))
                .toList()));
    }
}
```

```java
// CapabilityCacheImpl.java:18-24
this.itemCapabilityCache = BlockCapabilityCache.create(ItemHandler.BLOCK, level, pos, direction);
...
return Optional.ofNullable((IItemHandler) this.itemCapabilityCache.getCapability());
```

```java
// ExternalStorageNetworkNode.java:35-37
public void initialize(ExternalStorageProvider provider) {
    this.storage.tryClearDelegate();
    this.externalStorage = new ExternalStorage(provider, this.storage);
    ...
}
```

```java
// ExternalStorage.java:59-75,123-126
public boolean detectChanges() {
    ResourceList updatedCache = this.buildCache();
    boolean hasChanges = this.detectCompleteRemovals(updatedCache);
    return hasChanges | this.detectAdditionsAndPartialRemovals(updatedCache);
}
private ResourceList buildCache() {
    MutableResourceList list = MutableResourceListImpl.create();
    this.provider.iterator().forEachRemaining(list::add);
    return list;
}
```

要点：

- **一个 RS 外部存储方块**只查询它**朝向的那一面**（`incomingDirection` = 目标方块朝向外部存储的那一面），不会同时查询 A/B。
- 每个外部存储方块各自创建一个 `ExternalStorageNetworkNode` 和一个 `ExternalStorage`；网络的存储聚合是**逐 storage** 的，没有“按源坐标去重”的逻辑（在 2.0.9 的 ExternalStorage 路径上未发现任何按 pos/Block 身份去重的代码）。
- 2.0.9 里唯一的“自指/接口”特殊处理是 `InterfaceBlock` 分支（`AbstractExternalStorageBlockEntity.java:92`）。RS 的 Interface 自己还有一层保护：

```java
// InterfaceExternalStorageProviderImpl.java:24,34,42
if (this.isAnotherInterfaceActingAsExternalStorage(actor)) return 0L;
...
private boolean isAnotherInterfaceActingAsExternalStorage(Actor actor) { ... }
```

**这个保护只针对 Interface，不覆盖任意第三方方块。**

#### 4.4.2 结论 1：单个外部存储 + A/B 不相交 ⇒ **不会**重复计数

- 外部存储只看到它贴的那一面；A 面（代理对象）返回的是容器物品（如 1 个潜影盒），B 面（内容物）返回的是 27 格内容物。
- 这两套 `ResourceKey` 不同（一个是 `ItemResource(shulker_box)`，一个是 `ItemResource(diamond)`），所以**不是“同一资源被计两次”**。
- 单个外部存储 + 只贴一面 ⇒ **安全**。

#### 4.4.3 结论 2：两个外部存储贴两个“同一底层库存”的面 ⇒ **会**重复计数

- 例：方案 B 的 4 个内容物面返回同一个内容物 handler；玩家在 UP 和 DOWN 各贴一个外部存储。
- 网络里就会有两个 `ExposedExternalStorage`（它们是两个不同的 `ExternalStorage` 对象），各自的 `ExternalStorage#buildCache()` 都从同一个底层内容物 handler 迭代出同一批物品；RS 的网络根存储 `RootStorageImpl` 用 `CompositeStorageImpl`（`RootStorageImpl.java:38-51`），`addSource` 只是把 source 放进 `Set`（`CompositeStorageImpl.java:44-52`，按对象身份去重，不按源坐标），而 `getStored()` 是 `insertSources.stream().mapToLong(StorageView::getStored).sum()`（`CompositeStorageImpl.java:153-155`），`ExternalStorage#getStored()` 又是自己 cache 的求和（`ExternalStorage.java:135-137`）⇒ **显示数量翻倍**。
- 后果（C，代码路径推断，未运行时实测）：
  - RS 的“已存储数量”偏大，自动合成/请求可能按翻倍数量做 simulate，执行时底层只有一份 ⇒ 合成失败/超量请求；
  - `ExternalStorage.extract/insert` 每次执行后会 `detectChanges()`（`ExternalStorage.java:37-55`），两个 storage 的缓存会逐步追平，所以**不会凭空复制物品**，但会出现“瞬间虚高/幽灵数量”。
  - **这不是凭空 dupe，但会破坏 RS 的数量语义**，必须避免。
- 同理：两个对象面返回同一个对象 handler，也会把同一个容器物品计两次。

**设计约束（重要）**：**每个底层库存只在一个面暴露**（不是“每个 handler 实例”——同一底层库存即使用不同 handler 实例包装，RS 也会重复计数）；如果产品上需要多面，必须保证“多面 = 不同底层库存”不成立（同一底层库存仍是同一资源），所以**多面同底层只能在“玩家只接一个外部存储”的前提下使用**，或者干脆只保留一个面（方案 A）。

#### 4.4.4 结论 3：本方块同时是 RS 网络节点 + 内容物能力 ⇒ **真正的自指环**

- 如果代理器自己实现成 RS 网络节点（自身存储 = 内容物），同时又在内容物面返回同一个内容物的 `IItemHandler`：
  - RS 网络里：节点自己的 storage（内容物） + 外部存储贴面加进来的 storage（同一内容物） ⇒ 同一批物品计两次；
  - 网络可以“从外部存储抽取 → 存进节点存储”，而两者是同一底层 ⇒ 形成自指/环；
  - RS 2.0.9 的 `InterfaceBlock` 特判**不会**保护你（`AbstractExternalStorageBlockEntity.java:92` 只认 `InterfaceBlock`；`InterfaceExternalStorageProviderImpl` 的 guard 只认 `InterfaceNetworkNode`）。
- **⇒ 结论：容器代理器不要做 RS 网络节点；只做能力提供者。**（见 §5.5、§6 R7）

#### 4.4.5 结论 4：A/B 同接一个网络 ⇒ 不是“重复计数”，但有“容器-内容物联动”问题

即使 A/B handler 不相交，二者接进同一个 RS 网络后仍有语义耦合（C，代码路径推断）：

1. **对象面的资源 key 会随内容变化而变**：`ItemResource.ofItemStack` 把 `stack.getComponentsPatch()` 纳入 key（`ItemResource.java:80-83,97-104`）。玩家/网络通过 B 面往潜影盒里塞 27 个钻石后，A 面的 `ItemResource(shulker_box)` 会变成 `ItemResource(shulker_box + container 组件)` ⇒ RS 的外部存储缓存会看到“旧 key 消失 + 新 key 出现”（`ExternalStorage.detectChanges` 的 `detectCompleteRemovals` / `detectAdditionsAndPartialRemovals`，`ExternalStorage.java:59-105`）。
2. **抽走容器 = 抽走内容物的底层**：从 A 面抽走潜影盒时，B 面的内容物 handler 立刻失去 backing（如果包装器是 live 的），RS 会看到内容物“消失”。反过来，B 面把内容物抽空，A 面的容器物品 key 又变回空盒。
3. **非原子**：A 面抽容器、B 面同时写内容物，可能产生“容器被抽走时内容物还在写入”的竞态（同一 tick 内由不同网络节点/管道触发）。后果是物品随容器被带走/写入丢失，而不是复制。
4. **自动合成/请求**可能因为上述 key 抖动而失败或重复请求；**不会凭空生成物品**（因为 `ExternalStorage.extract` 每次都走 live handler，`ItemHandlerExtractableStorage` 用 `isSameItemSameComponents` 逐槽匹配，`ItemHandlerExtractableStorage.java:36`）。

**建议**：默认只把**内容物面**接进 RS 网络（这是“代理器”的主用途）；对象面用于玩家/管道装容器物品，或在一个**独立**网络/不接外部存储的情况下使用。若用户坚持两面都接，必须在文档中声明上述联动风险。

---

## 5. 四、实现骨架（设计，不写业务代码）

### 5.1 方块实体字段

```java
// 设计伪代码
public final class ContainerProxyBlockEntity extends BlockEntity {
    private static final String TAG_PROXY = "Proxy";

    /** v1：1 个代理槽；未来可扩到 N（见 §6 待拍板）。 */
    private final ProxySlot slot = new ProxySlot();

    /** 两种视图，按面返回；不要合并。 */
    private final IItemHandler objectView   = new ObjectFaceHandler(slot);
    private final IItemHandler contentsView = new ContentsFaceHandler(slot);

    @Nullable
    IItemHandler handlerFor(@Nullable Direction side) {
        return switch (faceRole(side)) {
            case OBJECT   -> objectView;    // 稳定实例：不要按内容新建
            case CONTENTS -> contentsView;  // 稳定实例：内部动态解析
            case NONE     -> null;
        };
    }
}
```

> **稳定实例原则**：`objectView` / `contentsView` 在 BE 生命周期内保持不变，只有“面角色变化 / 方块放置或破坏”才需要 `invalidateCapabilities`。代理槽换物品时只清 `ProxySlot.inner`，外层 handler 实例不变 ⇒ RS/管道缓存的 `IItemHandler` 仍然有效，读到的就是新内层。若实现成“每次查询新建 handler”，则每次换物品都必须 `invalidateCapabilities`，否则外部缓存永远拿旧实例（§5.3、R1）。

```java
// 代理槽 + 内层 handler 缓存（按 ItemStack 身份，不按 handler 身份）
final class ProxySlot {
    ItemStack stack = ItemStack.EMPTY;
    @Nullable IItemHandler inner;
    @Nullable ItemStack innerFor;     // 身份比较：innerFor != stack 时重解析

    @Nullable IItemHandler inner() {
        if (stack.isEmpty()) return null;
        if (innerFor != stack) {       // 不要用 equals：同内容不同实例也会悬空
            inner = stack.getCapability(Capabilities.ItemHandler.ITEM);
            innerFor = stack;
        }
        return inner;
    }

    void set(ItemStack newStack) {    // 对象面唯一写入口
        stack = newStack.isEmpty() ? ItemStack.EMPTY : newStack.copy();
        inner = null;
        innerFor = null;
    }
}
```

> 说明：`ItemStackHandler#getStackInSlot` 返回的是**实际实例**（`ItemStackHandler.java:49-51`），所以身份比较可行；`ComponentItemHandler` 绑定的是构造时传入的 `parent` stack（`ComponentItemHandler.java:32,160-162,194`），一旦 `setStackInSlot` 换了实例，旧 handler 写入会落到被丢弃的 stack 上。
>
> 对背包还有第二层“内层刷新”风险：`BackpackWrapper` 在升级变更/槽数变化时会刷新 `getInventoryForInputOutput()`（javap 可见 `refreshInventoryForInputOutput` / `onInventoryForInputOutputHandlerRefresh`），缓存的 `ITrackedContentsItemHandler` 可能指向旧的 `InventoryHandler`。**建议**：内层缓存不要无限期持有；至少每 N tick 或每次 `setChanged()` 后重解析一次（v1 可以先“每次访问重解析”，用 profiler 决定是否加缓存）。

### 5.2 两种 `IItemHandler` 实现

#### 5.2.1 对象面 `ObjectFaceHandler`（包装“代理对象本身”）

```java
// 设计伪代码
final class ObjectFaceHandler implements IItemHandler {
    int getSlots() { return 1; }                       // v1：单槽
    ItemStack getStackInSlot(int s) { return slot.stack; }
    int getSlotLimit(int s) { return 1; }              // 容器物品通常 stacksTo(1)；见风险 §6 R13
    boolean isItemValid(int s, ItemStack st) {
        return !st.isEmpty() && st.getCapability(Capabilities.ItemHandler.ITEM) != null;
    }
    ItemStack insertItem(int s, ItemStack st, boolean sim) {
        if (!slot.stack.isEmpty() || !isItemValid(s, st)) return st;
        if (!sim) { slot.set(st.copyWithCount(1)); }
        return st.copyWithCount(st.getCount() - 1);    // 只收 1 个
    }
    ItemStack extractItem(int s, int amount, boolean sim) {
        if (slot.stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack out = slot.stack.copyWithCount(1);
        if (!sim) slot.set(ItemStack.EMPTY);
        return out;
    }
}
```

- `insertItem` 必须**先做能力探测**：没有 `ItemHandler.ITEM` 的物品（RS 磁盘、Create 工具箱）直接拒收。
- **必须拷贝入参再存**：`ItemStackHandler#insertItem` 在目标槽为空时会直接把**调用方传入的 stack 引用**放进槽（`ItemStackHandler.java:55-88`，关键在 `:82` 的 `this.stacks.set(slot, ... : stack)`），调用方随后复用/修改该 stack 会污染代理槽；上面的 `st.copyWithCount(1)` 就是为此。
- 不要在 `isItemValid` 里调用 `insertItem`（会递归）；能力探测只查 `getCapability != null`。
- 如果未来做多槽：每个槽独立 `ProxySlot`，对象面 `getSlots()=N`，内容物面 = 各 `ProxySlot.inner()` 的串联（见 §6 待拍板）。

**“面向玩家”的入口**：GUI 用 `SlotItemHandler(objectView, 0, ...)`（`mayPlace` 调 `objectView.isItemValid`），与背面 `ObjectFaceHandler` 共享**同一个** `ProxySlot`。玩家放入的容器物品 = 自动化在对象面看到的同一个 stack；不要在 GUI 里再保存一份 `ItemStack`（否则背包 UUID 会出现两份引用）。

#### 5.2.2 内容物面 `ContentsFaceHandler`（包装“内部 Inventory”）

```java
// 设计伪代码
final class ContentsFaceHandler implements IItemHandler {
    int getSlots() { var h = slot.inner(); return h == null ? 0 : h.getSlots(); }
    ItemStack getStackInSlot(int s) { var h = slot.inner(); return h == null ? ItemStack.EMPTY : h.getStackInSlot(s); }
    int getSlotLimit(int s) { var h = slot.inner(); return h == null ? 0 : h.getSlotLimit(s); }
    boolean isItemValid(int s, ItemStack st) { var h = slot.inner(); return h != null && h.isItemValid(s, st); }
    ItemStack insertItem(int s, ItemStack st, boolean sim) { var h = slot.inner(); return h == null ? st : h.insertItem(s, st, sim); }
    ItemStack extractItem(int s, int a, boolean sim) { var h = slot.inner(); return h == null ? ItemStack.EMPTY : h.extractItem(s, a, sim); }
}
```

- **不要**把代理槽本身混进内容物视图（否则同一容器物品既在对象面又在内容物面，RS 会把它计两次/语义混乱）。
- **不要**实现 `IItemHandlerModifiable` 除非内层确实是；`ComponentItemHandler.setStackInSlot` 对非法栈会抛 `RuntimeException`（`ComponentItemHandler.java:62-65`），管道普遍用 `insertItem`，不会踩；但 GUI 若用 `setStackInSlot` 要额外校验。
- `getSlots()` 可能随内层变化（背包升级改变槽数、背包 UUID 从无到有）；`IItemHandler` 的调用方通常每次重新读 `getSlots()`，但**缓存槽数的第三方管道**可能出错（风险 §6 R6）。

### 5.3 失效 / 更新

| 触发 | 动作 | 原因/证据 |
|---|---|---|
| 代理槽被 `insertItem` / `extractItem` / `setStackInSlot` 改变 | 清 `inner`/`innerFor`；`setChanged()`；**通常不需要** `invalidateCapabilities`（外层 handler 是稳定实例） | 旧 `ComponentItemHandler` 绑定旧 stack；旧背包 handler 绑定旧 UUID。外层 `contentsView` 仍会被外部缓存持有，但它在每次调用时重新解析 `inner`，所以实例仍然有效。**只有**你选择“每次查询新建 handler”时，才必须每次 `invalidateCapabilities`（`BlockCapability.java:55-57`） |
| 方块被放置/破坏 | `level.invalidateCapabilities(pos)` | 同上；`BlockCapability.java:55-84` 的示例 |
| 方块状态变化（`FACING` 旋转/镜像） | 清缓存 + `invalidateCapabilities` | 面角色映射变了；若 `Block#rotate` 拿不到 level，用 `BlockEntity#setBlockState` 覆写或 tick 检测前后 state（`BlockEntity#setBlockState` 存在，javap 可见） |
| 内层内容变化（潜影盒组件被写、背包 NBT 被写） | **不需要** invalidate：内容物视图是 live 包装，读时直接问内层 | 外部缓存（RS 的 `BlockCapabilityCache`）缓存的是 **handler 实例**，不是内容；内容变化由调用方每次读 `getStackInSlot` 看到 |
| 内层 handler 从 `null` 变成非空（背包 UUID 刚生成） | **不需要** invalidate：`contentsView` 是稳定实例，`getSlots()` 会从 0 变 N | 因为外层实例不变，RS 的 `BlockCapabilityCache` 拿到的仍是同一个 `contentsView`；它下次 `getSlots()` 就能看到新内层。只有“外部调用方自己缓存了槽数=0”才会看不到，这不是 `invalidateCapabilities` 能解决的 |
| 区块卸载/重载 | `BlockCapabilityCache` 会在 chunk load 时被 NeoForge 失效 | `CapabilityHooks.java:160-170`（`invalidateCapsOnChunkLoad/Unload`）；但第三方自建缓存不保证，故本 BE 的 `onLoad`/`setRemoved` 也要清自己的缓存 |

关于 **`BlockCapabilityCache` 的缓存语义**（A）：

```java
// BlockCapabilityCache.java:17-19
// The cache is invalidated when the level is notified of a change via Level#invalidateCapabilities(BlockPos).

// BlockCapabilityCache.java:137-160
public T getCapability() {
    if (!cacheValid) {
        cachedCap = level.isLoaded(pos) ? level.getCapability(capability, pos, context) : null;
        cacheValid = true;
    }
    return cachedCap;
}
```

⇒ 如果你在 BE 内部**换了 handler 实例**却不 `invalidateCapabilities`，RS/管道会继续用旧实例（旧 stack / 旧 UUID）——这是最危险的“悬空”来源。

### 5.4 持久化

```java
// 设计伪代码
@Override
protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (!slot.stack.isEmpty()) tag.put(TAG_PROXY, slot.stack.save(registries));
}

@Override
protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    slot.stack = tag.contains(TAG_PROXY)
        ? ItemStack.parseOptional(registries, tag.getCompound(TAG_PROXY))
        : ItemStack.EMPTY;
    slot.inner = null; slot.innerFor = null;   // 读档后一律重解析
}
```

- `ItemStack#save(HolderLookup.Provider)` / `ItemStack.parseOptional(HolderLookup.Provider, CompoundTag)` 签名（javap，A）：
  ```text
  public net.minecraft.nbt.Tag save(net.minecraft.core.HolderLookup$Provider);
  public static net.minecraft.world.item.ItemStack parseOptional(net.minecraft.core.HolderLookup$Provider, net.minecraft.nbt.CompoundTag);
  ```
- 只保存**代理对象 stack**；内层内容：
  - 潜影盒：在 stack 的 `container` 组件里，随 stack 保存（`ItemStack.CODEC` 的 `components` 字段，`ItemStack.java:103-110`）；
  - 背包：在世界 `BackpackStorage` SavedData，物品只有 UUID；BE 不需要也不应该复制内容；
  - 其他：由物品自己的能力/数据负责。
- 客户端同步（渲染/提示用）：`getUpdateTag()` 里放同一份 stack（或只放展示用的 `ItemStack`），**不要**在客户端查询能力做逻辑。`getUpdatePacket()` 用 `ClientboundBlockEntityDataPacket.create(this)`。
- `setChanged()` 在每次对象槽变化后调用；背包内容变化由背包自己的 `setDirty` 负责，不需要你的 BE `setChanged`（但你若缓存了“内容摘要”用于 Jade，则要刷新）。

### 5.5 与 RS 网络的关系：网络节点还是外部存储？

**建议：能力提供者（外部存储目标），不要做 RS 网络节点。**

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 能力-only（推荐）** | 你的方块只注册 `ItemHandler.BLOCK`（按面）；玩家在内容物面贴 **RS 外部存储总线** | 不重复计数；不碰 RS 内部网络节点/能量/图 API；与“外部存储”的既有语义一致（`ItemHandlerPlatformExternalStorageProviderFactory`） | 玩家必须多放一个外部存储方块；内容物面只被 RS 看见时才有网络存储 |
| B. RS 网络节点（存储=内容物） | 实现 `AbstractNetworkNodeContainerBlockEntity` + `NetworkNodeContainerProvider` 能力，节点 storage 直接是内容物 | 不需要外部存储方块，方块本身就是网络存储 | **必须禁止内容物面再暴露能力**，否则 §4.4.4 双计/自指；还要处理能量、节点生命周期、RS 版本耦合；内容物面就不能给普通管道用了 |
| C. 两者都做 | 节点 + 内容物能力 | 看似“全能” | **明确不安全**：RS 只特判 Interface，不特判你；同一内容物会被节点和外部存储各计一次 |

**推荐 A 的具体接线**：

```text
[RS 外部存储总线] —贴—> [容器代理器 正面/内容物面]
[管道/漏斗/玩家] —> [容器代理器 背面/对象面]（装/卸潜影盒、背包）
```

- 内容物面接 RS：RS 把被代理对象的内部库存当成“外部存储”，可存取、可合成、可过滤（RS 外部存储自己的过滤/优先级）。
- 对象面接管道：只搬运“容器物品本身”，不碰内容物。
- 如果用户想让“容器物品本身”也进 RS 网络：在对象面再贴一个外部存储总线即可；但请阅读 §4.4.5 的联动风险，建议单独网络或只在一个方向使用。

**补充：RS 还有一个 `ExternalStorageProviderFactory` 扩展点**（`common/api/storage/externalstorage/ExternalStorageProviderFactory.java:16-20`，`@FunctionalInterface`），通过 `RefinedStorageApi#addExternalStorageProviderFactory` 注册（`RefinedStorageApi.java:96`）。它适合“资源不是 `IItemHandler`”的场景（例如磁盘/气体），但**对所有外部存储全局生效**，每次外部存储查询都会调用；本方块用标准 `Capabilities.ItemHandler.BLOCK` 就够，不需要注册全局工厂。

**为什么“做网络节点”不是顺手的事**：网络节点走的是另一套 API（`RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability()`），本模组已有先例（`NetworkResourceReceiverBlockEntity.java:800-807`）；它要求 BE 继承 `AbstractNetworkNodeContainerBlockEntity`、处理能量/节点图/可见性，且**不会**解决“内容物被外部存储重复计数”的问题（反而制造它）。

### 5.6 GUI 版面与「面说明」的承载方式（第 97 轮口径）

几何（三处必须逐字一致：`InventoryProxyScreen` / `InventoryProxyMenu` / `inventory_proxy.png`，
由 `tools/selfcheck_round97_proxy_layout.py` 硬判）：

| 元素 | 坐标 |
|---|---|
| 面板 | 224 × 222（第 97 轮原 224×248 收矮） |
| 代理对象槽 | 菜单 (10, 22)；标签文本 (32, 20) |
| 状态文字 | (10, 46)，`font.split(..., 98)` 折行、最多 4 行、行高 9；不得进入 x ≥ 108 的按钮列 |
| 六面按钮 | (108, 20) 起、宽 110 高 16、步长 18（最后一颗底边 126） |
| 玩家背包 3 行 | 行首 y = 136 / 154 / 172（`26 + 18c`） |
| 快捷栏 | y = 196（格子底 214 ⇒ 面板底 222，底部留白 8px） |

**为什么把常驻说明删掉、改成悬停按钮的 tooltip**（用户第 97 轮第 3 条）：
第 96 轮把「主内容物面被 RS 读 / 备面仅进 / 对象面唯一」写成界面上的一行常驻红字，
为塞进 224px 面板按宽度整行等比缩小（约 0.4 倍），2/3 倍 GUI 缩放下就是「一撮蚂蚁字」；
用户明确说「字太小了，而且（就算）修复的话，也不喜欢那行字了」——要的是**不要这行字**。
现在同批信息挂在 4 条 `gui.rs_create_compat.inventory_proxy.face_tip.*`（键名 = `ProxyFaceRole#id()`），
只在悬停对应面按钮时以 tooltip 出现；旧键 `inventory_proxy.warning` 已从中英两边删除（不留孤儿键）。
常驻界面只剩标题 / 槽位标签 / 状态行。

---

## 6. 五、风险清单

> 级别：🔴 高（会丢物/复制/崩） / 🟠 中（语义错误/失败） / 🟡 低（体验/兼容）。

| # | 风险 | 级别 | 证据/原因 | 规避 |
|---|---|---|---|---|
| R1 | **缓存的 `IItemHandler` 悬空**：代理槽换 stack 后仍用旧 `ComponentItemHandler`（写回旧 stack，静默丢失） | 🔴 | `ComponentItemHandler` 持有 `parent`（`ComponentItemHandler.java:32,160-162,194`）；`BlockCapabilityCache` 缓存 handler 实例（`BlockCapabilityCache.java:137-160`） | **外层 handler 用稳定实例**（每次调用重新解析内层）；内层用身份缓存 `innerFor != stack`；读档/卸载清内层缓存；面角色变化才 `invalidateCapabilities` |
| R2 | **背包 UUID 悬空/错位**：换成另一个背包后，旧 handler 仍写旧 UUID 的 `BackpackStorage` | 🔴 | `getInventoryForInputOutput` 的 handler 由 wrapper/UUID 决定（`BackpackWrapper.java:191-197,344`） | 同 R1；必要时额外比较 `contentsUuid` |
| R3 | **背包被复制 ⇒ 两个 stack 同 UUID，共享同一份内容** | 🔴（复制类） | `BackpackStorage` 按 UUID 存（`BackpackStorage.java:25`；`getContentsUuid` `:344`） | 背包物品被复制（创造 middle-click、`copy()`、掉落物复制等）会产生两个同 UUID 的 stack；这不是代理器特有，但代理器不要制造第二份（`ProxySlot.set` 的 `copy()` 只是防别名，原入参不保留）；`extract` 后清空 |
| R4 | **空 UUID 背包**：`getContentsUuid().isEmpty()` ⇒ `EmptyItemHandler`，内容物面 0 格、插入全拒 | 🟠 | jar javap（3.26.3.2158）；`ModItems.java:474-477` | UI/Jade 提示“先让 UUID 生成（通常是打开一次背包）”；内容物面显示“未初始化”；不要把它当故障 |
| R5 | **背包过滤器**：升级的输入/输出过滤会拒收/限制 | 🟠 | `InventoryIOHandler#getFilteredItemHandler`（`BackpackWrapper.java:191-197`）；`InventoryIOHandler.addFilters`（javap） | 把 `insertItem` 的 remainder 当正常结果；不要假设“有 handler 就能全收” |
| R6 | **RS 重复计数**：同一底层库存暴露在多个面，多个外部存储各计一次 | 🟠（数量语义） | §4.4.3；RS 无按源去重；`ExternalStorageNetworkNode.java:35-37`、`ExternalStorage.java:59-75,135-137`、`CompositeStorageImpl.java:153-155` | **每个底层库存只暴露一个面**（方案 A）；多面模式必须在文档/UI 禁止“同一底层库存接多个外部存储” |
| R7 | **RS 自指环**：同时做网络节点 + 内容物能力 | 🔴 | §4.4.4；唯一保护是 Interface（`InterfaceExternalStorageProviderImpl.java:24,34,42`） | 不做网络节点（推荐）；或做节点时**禁用内容物面能力** |
| R8 | **容器物品 key 抖动**：内容变化 ⇒ `ItemResource` 组件变 ⇒ RS 看到 remove+add | 🟠 | `ItemResource.java:80-83,97-104`；`ExternalStorage.java:59-105` | 默认只把内容物面接 RS；对象面接管道；文档说明 |
| R9 | **容器被抽走 ⇒ 内容物面瞬间清空**：非原子 | 🟠 | 内容物视图是 live 包装；A 面抽取会移除 backing | 单槽 v1；对象面与内容物面不要同时接同一个 RS 网络的自动合成；可加“锁定”红石/配置 |
| R10 | **`getCapability` 每次新建 handler**：按 handler 身份缓存会失效 | 🟡 | `ItemCapability.java:90-105`；潜影盒 lambda `CapabilityHooks.java:114` | 按 `ItemStack` 身份缓存；不比较 handler 引用 |
| R11 | **`setStackInSlot` 抛异常**：`ComponentItemHandler` 对非法栈抛 `RuntimeException` | 🟡 | `ComponentItemHandler.java:62-65` | 内容物面只实现 `IItemHandler`（`insert/extract`）；GUI 用 `insertItem` 或先 `isItemValid` |
| R12 | **嵌套潜影盒**：`canFitInsideContainerItems()` 拒绝 | 🟡 | `ComponentItemHandler.java:143` | 不用特殊处理；把 remainder 返回即可 |
| R13 | **多槽/数量 >1**：一个槽里 2 个同组件容器，内容物归属歧义 | 🟠 | 设计问题；`IItemHandler.getSlotLimit`/count 语义 | v1 对象槽 `getSlotLimit=1`，拒收 `count>1`（或拆成 N 个代理槽） |
| R14 | **旋转/镜像后面角色错乱**：外部缓存仍指向旧面的 handler | 🟠 | `BlockCapability.java:55-57`；面映射随 `FACING` 变 | `FACING` 变化时清缓存 + `invalidateCapabilities`；或 v1 用绝对面不旋转 |
| R15 | **第三方容器只读/有 owner 权限**：`insertItem` 返回原栈 | 🟡 | 未逐一实测（§7.1） | 永远检查 `insertItem` 返回值；不要假设写入成功；对象面只验证“能力存在”，不验证“可写” |
| R16 | **方块被破坏时容器掉落**：代理对象必须掉出来，否则内容物丢失 | 🟠 | 设计问题 | `getDrops`/`playerWillDestroy` 里把代理 stack 掉出（含组件/背包 UUID）；不要只清空 |
| R17 | **客户端查询能力做逻辑**：客户端 BE 内容可能不同步 | 🟡 | NeoForge 能力是双端的，但 BE 数据同步不保证 | 逻辑只在服务端；客户端用 `getUpdateTag` 的展示 stack |
| R18 | **RS 磁盘误插**：对象面若不做能力探测，会收下磁盘但内容物面永远 0 格 | 🟠 | 磁盘无 ItemHandler（§2.4） | `isItemValid` 里 `getCapability(ITEM) != null`；UI 提示“不是容器” |
| R19 | **入参 stack 别名**：把调用方传入的 `ItemStack` 直接存进代理槽，调用方随后改它 | 🟠 | `ItemStackHandler.java:55-88`（`:82` 空槽时直接放引用） | 存入前 `copy()`/`copyWithCount(1)`；`getStackInSlot` 只读 |
| R20 | **多槽未来扩展的内容物映射**：全局 slot 索引 → (proxy, inner slot) 在容器增减时会错位 | 🟠 | 设计问题；`getSlots()` 随内层变化 | v1 单槽；多槽需“槽位映射表 + 变更时重建 + 通知失效” |
| R21 | **背包升级后内层 handler 刷新**：缓存的 `ITrackedContentsItemHandler` 可能指向旧的 `InventoryHandler`/过滤器 | 🟠（未实测） | `BackpackWrapper` 有 `refreshInventoryForInputOutput()` / `onInventoryForInputOutputHandlerRefresh`（javap 可见）；升级会改变槽数/过滤 | 内层缓存加“每 N tick 重解析”或仅在单次调用内缓存；升级/`setChanged` 后主动清 |

---

## 7. 六、未证实 / 需用户拍板

### 7.1 未证实（不要当成结论用）

1. **SophisticatedCore 过滤器细节**：`InventoryIOHandler#addFilters` 的具体过滤规则（哪些升级、是否可完全拒收）只在 javap 里看到方法存在；`sophisticatedcore` 反编译源码未逐行核对。**需要运行时测试**（装背包 + 升级，往内容物面 `insertItem`）。
2. **新背包 UUID 何时生成**：`getContentsUuid().isEmpty()` ⇒ `EmptyItemHandler` 是确定的；但“合成/首次打开/首次访问”哪个时机会生成 UUID，未逐路径核实。**需要实测**。
3. **RS 重复计数的运行期表现**：§4.4.3 的“数量翻倍/幽灵数量/合成失败”由代码路径推断（C），**没有在游戏内做双外部存储实测**；建议上线前用创造模式做一次对照实验。
4. **其他容器模组**：本机整合包中只核验了“潜影盒 / Sophisticated Backpacks / RS 磁盘 / Create 工具箱”。其他模组（例如 Sable、Create Aeronautics 等）是否注册 `ItemHandler.ITEM`、是否可写、是否有 owner，**未逐一核验**。
5. **模组化潜影盒**：NeoForge 只给 17 个原版潜影盒注册能力；第三方“潜影盒变种”是否自己注册 `ItemHandler.ITEM`，取决于该模组，**不能假定有**。
6. **`BlockEntity#setBlockState` 的调用时机**：`setBlockState` 方法存在（javap），但是否在所有 `FACING` 变化路径（扳手/活塞/结构方块）都被调用，未逐一验证；保守做法是 tick 检测 state 变化。
7. **RS 外部存储方块的可放置面**：`AbstractDirectionalBlock.tryExtractDirection` 的朝向集合（是否只允许水平）未逐行确认；但这不影响“它只查朝向面”的结论。

### 7.2 需用户拍板

| # | 决策 | 选项 | 建议 |
|---|---|---|---|
| D1 | **面分配** | A：`FRONT=内容物 / BACK=对象 / 其余 null`；B：`LEFT/RIGHT=对象 / 其余=内容物`；C：可配置 | **A**（默认）；B 作为配置项但默认关闭，并在文档警告“同一底层库存只能接一个外部存储” |
| D2 | **代理槽数量** | 1 个 / N 个（最多 9） | **v1 用 1 个**；N 个的“内容物合并视图”在容器被抽走时会产生大面积 key 抖动，复杂度高 |
| D3 | **是否做 RS 网络节点** | 不做（能力-only）/ 做节点（存储=内容物，禁内容物面能力） | **不做**；如果未来要做，必须把“内容物面能力”关掉，或让节点存储与能力库存是不同底层 |
| D4 | **是否支持 Create 工具箱等自定义组件** | 不支持 / per-mod adapter | **v1 不支持**；在 tooltip 明确“只支持 ItemHandler.ITEM”；后续按需加 `ToolboxInventory` adapter |
| D5 | **对象面是否允许 `count>1`** | 只允许 1 / 允许堆叠 | **只允许 1**（容器物品通常 stacksTo(1)；避免“一槽两容器、内容物归属歧义”） |
| D6 | **方块能否旋转（`FACING`）** | 可旋转 / 固定绝对面 | 可旋转，但必须处理 R14（旋转后 invalidate） |
| D7 | **对象面/内容物面是否都接 RS** | 只内容物面 / 两面都接 | 默认**只内容物面**接 RS；对象面留给管道/玩家；两面都接时在 Jade 显示警告 |

---

## 8. 附录：证据索引（按文件）

### 8.1 NeoForge 21.1.248（`local_src/neoforge_src/`，A）

| 结论 | 文件:行 |
|---|---|
| `ItemHandler.ITEM` 类型、`BLOCK` 类型 | `capabilities/Capabilities.java:36-48` |
| 物品能力查询签名/空栈 | `capabilities/ItemCapability.java:20-31,90-105` |
| 物品能力实现（`IItemStackExtension`） | `common/extensions/IItemStackExtension.java:489-495`（javap 同） |
| 潜影盒物品能力（17 色，27 格，`DataComponents.CONTAINER`） | `capabilities/CapabilityHooks.java:114-131` |
| 放置后的潜影盒/熔炉等 BLOCK 能力 | `capabilities/CapabilityHooks.java:80-88` |
| `ItemHandler.BLOCK` / `FluidHandler.BLOCK` 被标记 proxyable | `capabilities/CapabilityHooks.java:55-57` |
| chunk load/unload 失效能力缓存 | `capabilities/CapabilityHooks.java:160-170` |
| `ComponentItemHandler` 写回组件 | `items/ComponentItemHandler.java:31-34,187-196` |
| `ComponentItemHandler` 非法栈抛异常 / 嵌套限制 | `items/ComponentItemHandler.java:62-65,143` |
| `IItemHandler` 契约（不可修改返回值） | `items/IItemHandler.java:13-60` |
| `ItemStackHandler#getStackInSlot` 返回实际实例 | `items/ItemStackHandler.java:49-51` |
| `BlockCapability` 失效要求 / `registerBlock` 示例 / `createSided` | `capabilities/BlockCapability.java:55-84,117-121,198-211` |
| `BlockCapabilityCache` 失效语义 / 缓存实现 | `capabilities/BlockCapabilityCache.java:17-19,137-160` |
| `RegisterCapabilitiesEvent` 的 `registerBlock`/`registerBlockEntity` | `capabilities/RegisterCapabilitiesEvent.java:40,59`（javap 同） |
| `IBlockCapabilityProvider` 五参签名 | javap：`(Level, BlockPos, BlockState, BlockEntity, C)` |
| `ItemStack` 的 components 保存 | `ItemStack.java:103-110`（来自 `build/moddev/artifacts/neoforge-21.1.248-sources.jar`） |
| 原版潜影盒 = `BlockItem` + 默认 `CONTAINER` 组件 | `Items.java:570-572`（同上 sources jar） |
| `ItemStack#save/parseOptional` 签名 | javap `net.minecraft.world.item.ItemStack` |

### 8.2 Refined Storage 2.0.9（`D:\MODS\_rs209_src`，A）

| 结论 | 文件:行 |
|---|---|
| 单个外部存储只查朝向面 | `common/storage/externalstorage/AbstractExternalStorageBlockEntity.java:87-102` |
| 外部存储的能力缓存按 direction 建 | `neoforge/storage/CapabilityCacheImpl.java:18-24` |
| 每个外部存储一个网络节点 storage | `api/network/impl/node/externalstorage/ExternalStorageNetworkNode.java:35-37` |
| 外部存储的缓存/变更检测 | `api/storage/external/ExternalStorage.java:37-55,59-105,123-126` |
| 网络存储总量按 storage 求和（`getStored`） | `api/storage/external/ExternalStorage.java:135-137`；`api/storage/composite/CompositeStorageImpl.java:153-155` |
| 网络根存储 = 多 source 复合；`addSource` 按对象身份去重（不按源坐标） | `api/storage/root/RootStorageImpl.java:38-51`；`api/storage/composite/CompositeStorageImpl.java:44-52` |
| 外部存储工厂：按 (pos, direction) 建 `CapabilityCacheImpl` | `neoforge/storage/externalstorage/ItemHandlerPlatformExternalStorageProviderFactory.java:13-19` |
| 外部存储工厂扩展点（全局） | `common/api/storage/externalstorage/ExternalStorageProviderFactory.java:16-20`；`common/api/RefinedStorageApi.java:96,98` |
| 唯一 Interface 特判 | `common/storage/externalstorage/AbstractExternalStorageBlockEntity.java:92`；`common/iface/InterfaceProxyExternalStorageProvider.java:26-31`；`api/network/impl/node/iface/externalstorage/InterfaceExternalStorageProviderImpl.java:24,34,42` |
| 资源 key 含 components | `common/support/resource/ItemResource.java:80-83,97-104` |
| 内容物迭代按槽读 `getStackInSlot` | `neoforge/storage/CapabilityCache.java:47-70` |
| 插入/抽取交给 `IItemHandler` | `neoforge/storage/ItemHandlerInsertableStorage.java:34`；`ItemHandlerExtractableStorage.java:36` |
| RS 物品能力注册（无 `ItemHandler.ITEM`） | `neoforge/ModInitializer.java:455-490` |
| 磁盘接口只有 `resolve/getInfo` | `common/api/storage/StorageContainerItem.java:13-18` |
| 磁盘 = UUID 组件 + `StorageRepository` | `common/storage/StorageContainerItemHelperImpl.java:36-39,162-166`；`common/storage/StorageRepositoryImpl.java:20` |
| 磁盘 id 命名 | `common/content/ContentIds.java:97,101`；lang `item.refinedstorage.1k_storage_disk` |

### 8.3 Sophisticated Backpacks 3.26.3.2158（jar，A）与 3.25.69 源码（B）

| 结论 | 证据 |
|---|---|
| 3.26.3.2158 注册 `ItemHandler.ITEM` | javap `ModItems`：offset 1 `Capabilities$ItemHandler.ITEM`，offset 86 `registerItem` |
| 空 UUID ⇒ `EmptyItemHandler` | javap lambda `lambda$registerCapabilities$81`：offset 11 `isEmpty`，17 `EmptyItemHandler.INSTANCE`，24 `getInventoryForInputOutput` |
| 源码注册位置 | `ModItems.java:473-477`（3.25.69） |
| 背包内容在 SavedData | `BackpackStorage.java:25`；`BackpackWrapper.java:182-188` |
| 背包 handler 与过滤 | `BackpackWrapper.java:191-197`；javap `InventoryIOHandler` |
| 放置后的背包也有 BLOCK 能力 | `BackpackBlockEntity.java:186-208`；`ModBlocks.java:46` |

### 8.4 Create 6.0.10-280（`local_src/create_src/`，A）

| 结论 | 文件:行 |
|---|---|
| 工具箱物品带 `TOOLBOX_INVENTORY` 组件 | `AllDataComponents.java:236-238`；`ToolboxInventory.java:30` |
| 放置读组件、破坏写回 | `ToolboxBlock.java:79,117` |
| 只注册 BLOCK 能力，无 `ItemHandler.ITEM` | `ToolboxBlockEntity.java:78-84`；全仓 `grep ItemHandler.ITEM` 0 命中 |
| 其他容器型组件（包裹/过滤器/链接控制器） | `AllDataComponents.java:301-303,114-116,231-233` |

### 8.5 本仓库现有模式（A）

| 结论 | 文件:行 |
|---|---|
| “先 unsided、再逐面”取 ItemHandler | `support/RsccChamberImportStrategy.java:1577-1600` |
| “仅 unsided 暴露、侧面不暴露”的现成写法 | `block/entity/NetworkResourceReceiverBlockEntity.java:808-815` |
| 本模组通用磁盘继承 RS `AbstractStorageContainerItem` | `item/UniversalStorageDiskItem.java:30` |

---

## 9. 一句话回答用户最关心的两个问题

1. **“带 Inventory 的物品/方块”能不能代理？**
   - 物品级：**能**，只看 `stack.getCapability(Capabilities.ItemHandler.ITEM)`；潜影盒（27 格）和 Sophisticated Backpacks（受 UUID/过滤器限制）可以，RS 磁盘和 Create 工具箱**不行**（没有 ItemHandler）。
   - 方块级：**不能通用地“代理它放下后的 BE”**；只有已经放在世界里的方块才有 `ItemHandler.BLOCK`。带容器数据的方块物品（Create 工具箱）需要 per-mod adapter，v1 不做。

2. **A 类面（对象）和 B 类面（内容物）会不会让 RS 重复计数/成环？**
   - **一个** RS 外部存储只查朝向面，A/B 不相交时不会把同一资源计两次；
   - **两个**外部存储贴两个面（或任意两个面返回同一底层库存）⇒ **会重复计数**（RS 不按源去重）；
   - 本方块**同时做 RS 网络节点 + 内容物能力** ⇒ **会形成真正的自指环**（RS 只特判 Interface）；
   - 推荐：**不做网络节点；每个 handler 只暴露一个面（默认 FRONT=内容物、BACK=对象、其余 null、null=内容物）**。
