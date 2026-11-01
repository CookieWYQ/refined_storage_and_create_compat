# RS 磁盘存储适配器（专用 Adapter）调研与设计

> 版本基线：MC 1.21.1 / NeoForge 21.1.248 / Refined Storage **2.0.9**（`build/rs209_probe.jar`，4,360,871 字节，`META-INF/neoforge.mods.toml` 中 `version = "2.0.9"`）/ 本模组 `rs_create_compat`。
> 本文**只做调研与设计，不包含任何落地的 Java 代码，也不修改任何 Java 文件**；所有代码块均为设计伪代码。
> 证据等级：**A** = 直接来自 jar 内反编译源码 / NeoForge 源码的原文与行号；**B** = 同项目但版本不完全一致，或由标准原版实现推导；**C** = 由 A/B 代码路径推断、**未做运行期验证**。
> RS 源码来源：`D:\MODS\_rs209_src`（工作区外，避免污染仓库）；NeoForge/MC 源码来源：`build/moddev/artifacts/neoforge-21.1.248-merged.jar` 内的 `.java` 条目；`build/rs209_probe.jar` 仅用 Python `zipfile` 读取，未解压到工作区。

---

## 0. 结论速览（先看这 12 条）

1. **磁盘里的物品既不在 NBT，也不在 DataComponent 里。** 磁盘物品上只有一个 **UUID** 组件 `refinedstorage:storage_reference`；真正的资源在 **world 级 `StorageRepository`（原版 `SavedData`，名字 `refinedstorage_storages`）** 的 `Map<UUID, SerializableStorage>` 里。证据：`StorageContainerItemHelperImpl.java:36-38,162-164`；`StorageRepositoryImpl.java:20-23,44-47`；`RefinedStorageApiImpl.java:186-190`。**A**
2. `StorageContainerItem#resolve(StorageRepository, ItemStack)` 返回 **`Optional<SerializableStorage>`**（不是 `StorageContainer`），实现只是 `getId(stack).flatMap(repository::get)`。证据：`StorageContainerItem.java:13-16`；`AbstractStorageContainerItem.java:33-36`；`StorageContainerItemHelperImpl.java:36-38`。**A**
3. `StorageRepositoryImpl extends AbstractPlatformSavedData`（即 `SavedData`），由 **overworld 的 `ServerLevel#getDataStorage().computeIfAbsent(..., "refinedstorage_storages")`** 挂载；文件名按原版规则是 `<存档>/data/refinedstorage_storages.dat`。证据：`StorageRepositoryImpl.java:20-23`；`RefinedStorageApiImpl.java:186-190`；`DimensionDataStorage.java:39-40`。**A**
4. **一个 UUID 一份存储**。磁盘复制（创造中键、NBT 复制）会复制 UUID ⇒ **两个盘共享同一份存储**（改一个另一个也变、容量共享）。`StorageRepositoryImpl#set` 对已存在的 UUID 直接抛 `IllegalArgumentException`。证据：`StorageRepositoryImpl.java:44-59`。**A**
5. **内容读取**用 RS 的 `Storage` 语义：`getAll()` 返回 `Collection<ResourceAmount>`（资源 + 数量），`getStored()` 返回已用量；物品资源是 `ItemResource`，可用 `ItemResource#toItemStack(long)` 还原成 `ItemStack`（数量超过 `Integer.MAX_VALUE` 会被截断并打日志）。证据：`SerializableStorage.java:11-13`；`StorageView.java:12-15`；`StorageImpl.java:61-68`；`ItemResource.java:42-47`。**A**
6. **写入**用 `Storage#insert(ResourceKey, long, Action, Actor)`（`Action.EXECUTE/SIMULATE`，`Actor` 不能为 null）；容量限制在 `LimitedStorageImpl` / 本模组 `UniversalLimitedStorage` 内完成，成功后由创建时绑定的 `listener`（= `storageRepository::markAsChanged`）把 SavedData 置脏。证据：`InsertableStorage.java:13`；`LimitedStorageImpl.java:30-38`；`PlatformStorage.java:51-66`；`ItemStorageDiskItem.java:50-52`；`TrackedStorageImpl.java:32-58`。**A**
7. **拿 `StorageRepository` 必须有 `Level`（且必须是能取到 server 的 level）**：`getStorageRepository` 会 `level.getServer().getLevel(Level.OVERWORLD)` 再 `computeIfAbsent`。**客户端拿不到内容**：`ClientStorageRepository#get` 直接 `throw new UnsupportedOperationException()`，客户端只有“总量/容量”数值（靠包同步）。证据：`RefinedStorageApiImpl.java:181-190`；`ClientStorageRepository.java:26-28,53-58`。**A**
8. **未格式化的盘**（没有 UUID 组件）：`resolve` 返回 `Optional.empty()`，`getInfo` 也 empty；磁盘只会在**玩家背包 tick** 时被自动格式化（`Inventory.tick()` → `ItemStack.inventoryTick` → `loadStorageIfNecessary`，且要求 `entity instanceof Player` 且服务端）。**放在箱子 / 漏斗 / 代理器里的盘永远不会自动格式化。** 证据：`Inventory.java:216-228`；`StorageContainerItemHelperImpl.java:41-46,73-75`。**A**
9. **本模组磁盘与 RS 原生磁盘是同一套**：`UniversalStorageDiskItem extends AbstractStorageContainerItem`（同一 UUID 组件、同一 `StorageRepository`），只是把 `StorageType` 换成了注册在 RS 注册表里的 `rs_create_compat:universal`。⇒ 一个基于 `StorageContainerItem + storage_reference + StorageRepository` 的适配器**同时覆盖两者**（以及未来的第三方 `AbstractStorageContainerItem` 磁盘）。证据：`UniversalStorageDiskItem.java:30,44-47,64-66`；`UniversalStorageType.java:30-31`；`RS_Create_Compat.java:668-672`。**A**
10. **可以给磁盘 `Item` 注册 `Capabilities.ItemHandler.ITEM`**：`RegisterCapabilitiesEvent#registerItem(ItemCapability<T,C>, ICapabilityProvider<ItemStack,C,T>, ItemLike...)`，provider **拿到的就是真实 `ItemStack`**，因此完全可以依赖 stack 上的 UUID 组件做“每个盘不同的 handler”。这正是让代理器**不需要特例**的首选方案。证据：`RegisterCapabilitiesEvent.java:136-149`；`ItemCapability.java:88-104`。**A**
11. **IItemHandler 形态**：**不要**“单槽聚合”，也**不要**“按种类无限给槽”。推荐**固定 N 个虚拟槽的“资源种类窗口”**（N 可配置）+ 粘性槽位分配；`getStackInSlot` 返回**该种类的真实总量**（NeoForge 明确允许 `getStackInSlot` 的 count **大于**物品最大堆叠，RS 2.0.9 也专门为“槽内数量 > 最大堆叠”写了分支），`extractItem` 按契约每次最多返回 `min(amount, 最大堆叠)`。证据：`IItemHandler.java:23-27,41-70`；`ItemHandlerExtractableStorage.java:31-56`；`ForgeHandlerUtil.java:14-25`；`CapabilityCache.java:47-66`。**A**
12. **首选方案 = 给 `Item` 注册能力**（覆盖所有 `StorageContainerItem`），代理器完全通用；**兜底方案 = 代理器特例**（用自身 `ServerLevel` 构造同一个 handler）。两者共用同一个 handler 类，区别只在“repository 从哪来”。能力注册的主要代价是：provider 无 `Level` 上下文 ⇒ 需要 `ServerLifecycleHooks.getCurrentServer()` + 线程门禁；并且磁盘内容会对**任何**查询该物品能力的模组可见（可用配置开关限制）。**A（签名/时机）+ C（运行期行为未实测）**

---

## 1. 一、磁盘内容的真实存放位置（证据链）

### 1.1 物品上只有 UUID

`DataComponents` 里与磁盘相关的只有两个 `DataComponentType<UUID>`：

```java
// common/content/DataComponents.java:26-28,67-73
@Nullable private Supplier<DataComponentType<UUID>> storageReference;
@Nullable private Supplier<DataComponentType<UUID>> storageReferenceToBeTransferred;

public DataComponentType<UUID> getStorageReference() {
   return Objects.requireNonNull(this.storageReference).get();
}
```

注册处（id 与编解码）：

```java
// common/AbstractModInitializer.java:859-864
DataComponents.INSTANCE.setStorageReference(
   callback.register(
      IdentifierUtil.createIdentifier("storage_reference"),
      () -> DataComponentType.builder()
              .persistent(UUIDUtil.CODEC)
              .networkSynchronized(UUIDUtil.STREAM_CODEC).build()
   )
);
```

结论：

| 项 | 值 | 证据 |
|---|---|---|
| 组件 id | `refinedstorage:storage_reference` | `AbstractModInitializer.java:861` |
| 类型 | `DataComponentType<UUID>` | `DataComponents.java:26` |
| 持久化 | `persistent(UUIDUtil.CODEC)` | `AbstractModInitializer.java:862` |
| 网络同步 | `networkSynchronized(UUIDUtil.STREAM_CODEC)`（**客户端也能看到 UUID，但看不到内容**） | 同上 |
| 迁移用组件 | `refinedstorage:storage_reference_to_be_transferred`（升级/转移盘时用） | `AbstractModInitializer.java:866-870` |

读写该组件的私有方法：

```java
// common/storage/StorageContainerItemHelperImpl.java:162-164,184-186
private Optional<UUID> getId(ItemStack stack) {
   return Optional.ofNullable((UUID) stack.get(DataComponents.INSTANCE.getStorageReference()));
}
private void setId(ItemStack stack, UUID id) {
   stack.set(DataComponents.INSTANCE.getStorageReference(), id);
}
```

**注意**：`common.content.DataComponents` 没有 `@API` 注解（属于内部实现）。本设计**不需要**碰它：读写都走 `StorageContainerItem#resolve` + `SerializableStorage` 的公开 API。

### 1.2 解析链：从 ItemStack 到 SerializableStorage

接口（`common/api`，`@API STABLE`）：

```java
// common/api/storage/StorageContainerItem.java:13-20
public interface StorageContainerItem {
   Optional<SerializableStorage> resolve(StorageRepository storageRepository, ItemStack stack);
   Optional<StorageInfo> getInfo(StorageRepository storageRepository, ItemStack stack);
   static Predicate<ItemStack> stackValidator() {
      return stack -> stack.getItem() instanceof StorageContainerItem;
   }
}
```

实现（**没有** `StorageContainer` 这个返回类型，2.0.9 就是 `SerializableStorage`）：

```java
// common/api/storage/AbstractStorageContainerItem.java:25-36
public abstract class AbstractStorageContainerItem extends Item implements StorageContainerItem {
   protected final StorageContainerItemHelper helper;
   @Override public Optional<SerializableStorage> resolve(StorageRepository repo, ItemStack stack) {
      return this.helper.resolveStorage(repo, stack);
   }
   @Override public Optional<StorageInfo> getInfo(StorageRepository repo, ItemStack stack) {
      return this.helper.getInfo(repo, stack);
   }
}
```

最终只是一次 map 查询：

```java
// common/storage/StorageContainerItemHelperImpl.java:36-38
public Optional<SerializableStorage> resolveStorage(StorageRepository storageRepository, ItemStack stack) {
   return this.getId(stack).flatMap(storageRepository::get);
}
```

链路总览：

```
ItemStack
  └─ DataComponent refinedstorage:storage_reference = UUID
       └─ StorageRepository.get(UUID)                      ← world SavedData（overworld）
            └─ SerializableStorage (extends Storage)
                 ├─ getAll() : Collection<ResourceAmount>   ← 内容（ResourceKey + long）
                 ├─ getStored() : long                      ← 已用量
                 └─ insert/extract(ResourceKey, long, Action, Actor)
```

### 1.3 `StorageRepository` 是什么、存到哪

接口：

```java
// common/api/storage/StorageRepository.java:12-23
public interface StorageRepository {
   Optional<SerializableStorage> get(UUID id);
   void set(UUID id, SerializableStorage storage);
   Optional<SerializableStorage> removeIfEmpty(UUID id);
   void remove(UUID id);
   StorageInfo getInfo(UUID id);
   void markAsChanged();
}
```

实现 = **原版 `SavedData`**：

```java
// common/storage/StorageRepositoryImpl.java:20-23,25-33
public class StorageRepositoryImpl extends AbstractPlatformSavedData implements StorageRepository {
   public static final String NAME = "refinedstorage_storages";
   private final Map<UUID, SerializableStorage> entries;
   public StorageRepositoryImpl(CompoundTag tag, Provider provider) {
      this.entries = new HashMap<>(/* codec.decode(tag) ... */);
   }
}
```

**挂载点（关键）**：

```java
// common/RefinedStorageApiImpl.java:186-190
public StorageRepository getStorageRepository(Level level) {
   ServerLevel serverLevel = Objects.requireNonNull(level.getServer().getLevel(Level.OVERWORLD));
   return (StorageRepository) serverLevel.getDataStorage()
      .computeIfAbsent(new Factory(StorageRepositoryImpl::new, StorageRepositoryImpl::new, null),
                       "refinedstorage_storages");
}
```

- **永远挂在 overworld 的 `DataStorage` 上**，与查询者所在维度无关（在主世界/下界/末地查询拿到的是同一份）。
- `computeIfAbsent` 在第一次访问时把整份 SavedData 读进内存；之后一直是内存里的 `HashMap`，随 autosave 落盘。
- 落盘文件名：原版 `DimensionDataStorage#getDataFile` 为 `new File(dataFolder, name + ".dat")`（`DimensionDataStorage.java:39-40`），即 `<存档>/data/refinedstorage_storages.dat`（`B`：目录来自原版 `ServerLevel` 的 data 目录；文件名为 `A`）。
- `AbstractPlatformSavedData#save(File, Provider)` 只是转调 `Platform#saveSavedData`，NeoForge 实现直接走原版 `SavedData#save`（`AbstractPlatformSavedData.java:9-13`；`neoforge/PlatformImpl.java:240-244`）⇒ 普通压缩 NBT。
- **序列化按 `StorageType` 分发**：`StorageRepositoryImpl.createCodec` 用 `RefinedStorageApi.INSTANCE.getStorageTypeRegistry().codec().dispatch(SerializableStorage::getType, ...)`（`StorageRepositoryImpl.java:36-42`）。所以本模组必须在存档加载前把 `rs_create_compat:universal` 注册进 RS 的 `StorageTypeRegistry`——已在本模组 `commonSetup` 完成（`RS_Create_Compat.java:668-672`）。

### 1.4 “每个磁盘一个存储”还是“按 UUID 共享”？

**答：以 UUID 为键，一份 UUID 一份存储；同一个 UUID 被几个 `ItemStack` 引用，就共享几份视图。**

```java
// common/storage/StorageContainerItemHelperImpl.java:67-71（首次格式化时生成 UUID 并入库）
private void setStorage(StorageRepository storageRepository, ItemStack stack, SerializableStorage storage) {
   UUID id = UUID.randomUUID();
   this.setId(stack, id);
   storageRepository.set(id, storage);
}
```

```java
// common/storage/StorageRepositoryImpl.java:44-59
public Optional<SerializableStorage> get(UUID id) { return Optional.ofNullable(this.entries.get(id)); }

public void set(UUID id, SerializableStorage storage) {
   ...
   if (this.entries.containsKey(id)) {
      throw new IllegalArgumentException(id + " already exists");
   } else {
      this.entries.put(id, storage);
      this.setDirty();
   }
}
```

由此可得：

1. 磁盘 A 首次在玩家背包被 tick → 生成随机 UUID `U`，`repo[U] = storage`，并把 `U` 写进 A 的组件。
2. 复制 A（创造中键复制物品、`/give` 带组件、NBT 复制、其他模组的“复制物品”）⇒ 新 stack 带同一个 `U` ⇒ **两个盘指向同一个 `storage` 对象**：内容、容量、`getStored()` 全部共享。
3. 磁盘的拆解会 `removeIfEmpty(id)`：**只有空盘**才把 `entries` 里的条目删掉（`StorageContainerItemHelperImpl.java:90-96`；`StorageRepositoryImpl.java:62-71`）。若此时另一个同 UUID 的副本还存在，它就会变成“有 UUID 但仓库里没有条目”的悬空盘。
4. 存储方块 ↔ 磁盘的转移也是**搬 UUID**（`transferToBlockEntity` / `transferFromBlockEntity`，`StorageContainerItemHelperImpl.java:139-155`），不是搬内容。

### 1.5 内容与容量怎么读

```java
// api/storage/SerializableStorage.java:11-13
public interface SerializableStorage extends Storage { StorageType getType(); }

// api/storage/Storage.java:10
public interface Storage extends StorageView, InsertableStorage, ExtractableStorage {}

// api/storage/StorageView.java:12-15
Collection<ResourceAmount> getAll();
long getStored();

// api/resource/ResourceAmount.java:10-17
public record ResourceAmount(ResourceKey resource, long amount) { ... }
```

实现细节：

```java
// api/storage/StorageImpl.java:61-68
public Collection<ResourceAmount> getAll() { return this.list.copyState(); }
public long getStored() { return this.stored; }

// api/resource/list/MutableResourceListImpl.java:27,81-83
return new MutableResourceListImpl(new HashMap<>());
...
public Collection<ResourceAmount> copyState() {
   return this.entries.values().stream().map(Entry::toResourceAmount).toList();
}
```

- **`getAll()` 返回的是一份拷贝**（每次调用 O(种类数) 并分配新 list），且底层是 `HashMap` ⇒ **顺序不稳定**。做 UI / 槽位映射时必须自己排序。
- 物品资源类型是 `ItemResource`（`common/support/resource/ItemResource.java:23`），可还原成 `ItemStack`：

```java
// common/support/resource/ItemResource.java:42-47
public ItemStack toItemStack(long amount) {
   if (amount > 2147483647L) {
      LOGGER.warn("Truncating too large amount for {} to fit into ItemStack {}", this, amount);
   }
   return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(this.item), (int) amount, this.components);
}
```

- 容量：`StorageInfo#of` 取 `storage.getStored()` 和（若实现了 `LimitedStorage`）`getCapacity()`；创造盘没有 `LimitedStorage` 装饰，容量报 0（`StorageInfo.java:12-17`；`SameTypeStorageType.java:61-70`）。
- `StorageInfo.UNKNOWN = (0,0)`，`StorageRepositoryImpl#getInfo` 在没条目时返回它（`StorageRepositoryImpl.java:80-82`）。

**数量上限**：`toItemStack` 接收 `long`，但 `ItemStack` 的 count 是 `int` ⇒ 单次还原最多 `Integer.MAX_VALUE` 个；超过会截断并打 warning。设计里遇到创造盘要显式 clamp。

### 1.6 写入需要什么上下文

```java
// api/storage/InsertableStorage.java:13
long insert(ResourceKey var1, long var2, Action var4, Actor var5);

// api/storage/ExtractableStorage.java:13
long extract(ResourceKey var1, long var2, Action var4, Actor var5);
```

- `Action` 只有 `SIMULATE` / `EXECUTE`（`api/core/Action.java:8-11`）。
- `Actor` 是函数式接口，`Actor.EMPTY` 是公开常量；`PlayerActor(String)` 可记录“最后修改者”（`Actor.java:10-12`；`PlayerActor.java:10-16`）。**`TrackedStorageImpl` 会对 null actor 直接抛异常**（`TrackedStorageImpl.java:32-58`），所以适配器里必须传非 null。
- 写入路径：`存储实现.insert(...)` → 容量裁剪 → 真正 list 更新 → `listener.run()`。`listener` 是磁盘创建时绑定的 `storageRepository::markAsChanged`：

```java
// common/storage/storagedisk/ItemStorageDiskItem.java:50-52
protected SerializableStorage createStorage(StorageRepository storageRepository) {
   return StorageTypes.ITEM.create(this.variant.getCapacity(), storageRepository::markAsChanged);
}
```

```java
// common/storage/PlatformStorage.java:51-66（节选）
public long insert(ResourceKey resource, long amount, Action action, Actor actor) {
   if (!this.type.isAllowed(resource)) return 0L;
   long inserted = super.insert(resource, amount, action, actor);
   if (inserted > 0L && action == Action.EXECUTE) {
      this.listener.run();      // → StorageRepositoryImpl#markAsChanged → setDirty()
   }
   return inserted;
}
```

- 容量限制（原生物品盘）：

```java
// api/storage/limited/LimitedStorageImpl.java:30-38
public long insert(ResourceKey resource, long amount, Action action, Actor actor) {
   long spaceRemaining = this.capacity - this.delegate.getStored();
   if (spaceRemaining == 0L) return 0L;
   long maxInsert = Math.min(amount, spaceRemaining);
   return super.insert(resource, maxInsert, action, actor);
}
```

- 本模组通用盘的容量规则不同（1 物品 = 1 物品位；流体/气体 1 桶 = 1 物品位，按“位”计，且可配混装开关）：`UniversalLimitedStorage.java:66-102,133-143`。

**结论**：写入只需要 `SerializableStorage` + `ItemResource` + `Action` + 非空 `Actor`；**不需要** `Level`、`Player`、方块坐标或区块加载。写的是 world 存档里的全局记录。

### 1.7 危险点（逐条）

#### 1.7.1 磁盘放在物品栏里时，存储“是否已加载”？

- **内容本身是加载的**：它属于 overworld 的 `SavedData`，服务器启动后只要访问过一次就在内存里；与磁盘在不在区块、在不在背包无关。
- **但“拿到 repository”需要 `ServerLevel`**：`getStorageRepository(level)` 内部 `level.getServer().getLevel(Level.OVERWORLD)`（`RefinedStorageApiImpl.java:186-190`）。`ItemHandler.ITEM` 的能力 provider **没有 Level 上下文**（`ItemCapability` 的 context 是 `Void`，见 `Capabilities.java:48`），所以必须用 `ServerLifecycleHooks.getCurrentServer()` 之类的静态入口（见 §3.2）。
- **客户端完全拿不到内容**：`ClientStorageRepository#get` 抛 `UnsupportedOperationException`（`ClientStorageRepository.java:26-28`）。客户端只知道“存了多少/容量多少”，由 `StorageInfoRequestPacket` / `StorageInfoResponsePacket` 同步（`ClientStorageRepository.java:53-58`；`StorageInfoRequestPacket.java:24-26`）。
- **磁盘在背包里并不会“解锁”内容**：UUID 已在物品上，内容一直在仓库里；背包 tick 只负责“首次格式化”。

#### 1.7.2 两个相同 UUID 的磁盘（复制盘）会怎样？

- 两个 `ItemStack` 的 `storage_reference` 相同 ⇒ `resolve` 返回**同一个 `SerializableStorage` 实例**（`StorageRepositoryImpl#get` 是 map 取值）。
- 内容、容量、`getStored()` 共享：一个盘插入，另一个盘立刻“看见”；一个盘抽出，另一个也减少。
- 拆解任意一个**空**副本会 `remove(id)`（`StorageContainerItemHelperImpl.java:90-96`），另一个副本立刻失去内容访问（变成“有 UUID、仓库无条目”）。
- `StorageRepositoryImpl#set` 对重复 UUID 抛异常（`StorageRepositoryImpl.java:49-59`），所以“再格式化一次同一个 UUID”是不可能的；只能换新 UUID。
- 注意：**复制盘不是复制内容**。它不会让容量翻倍（`LimitedStorageImpl` 还是同一份），但会让“同一底层库存出现在两个位置”——如果两个位置都被 RS 当作 storage 接入同一网络，网络总量会重复计数（见 §4.2）。

#### 1.7.3 未格式化 / 未插入存储

- `resolve`：UUID 缺失 ⇒ `Optional.empty()`（`StorageContainerItemHelperImpl.java:36-38`）。
- `getInfo`：同样 empty（`StorageContainerItemHelperImpl.java:82-84`）。
- 自动格式化的唯一入口是**玩家背包 tick**：

```java
// common/api/storage/AbstractStorageContainerItem.java:50-54
public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
   super.inventoryTick(stack, level, entity, slot, selected);
   this.helper.transferStorageIfNecessary(stack, level, entity, this::createStorage);
   this.helper.loadStorageIfNecessary(stack, level, entity, this::createStorage);
}
```

```java
// common/storage/StorageContainerItemHelperImpl.java:41-46
public void loadStorageIfNecessary(ItemStack stack, Level level, Entity entity, Function<StorageRepository, SerializableStorage> factory) {
   if (!level.isClientSide() && !this.hasStorage(stack) && entity instanceof Player) {
      StorageRepository storageRepository = RefinedStorageApi.INSTANCE.getStorageRepository(level);
      this.setStorage(storageRepository, stack, factory.apply(storageRepository));
   }
}
```

- 而 `ItemStack#inventoryTick` 的**唯一调用点**是 `Inventory#tick()`：

```java
// net/minecraft/world/entity/player/Inventory.java:216-228（节选）
public void tick() {
   ...
   nonnulllist.get(i).inventoryTick(this.player.level(), this.player, slot, this.selected == slot);
}
```

⇒ **放在箱子、漏斗、代理器、磁盘驱动器里的未格式化盘永远不会有 UUID**（这些容器不会调用 `inventoryTick`）。`/give` 进背包的盘会在下一 tick 自动格式化；用命令直接塞进容器（`/item replace block ...`）的盘不会。
- 另：从别的存档/服务器复制过来的磁盘，UUID 在新世界的 `refinedstorage_storages.dat` 里不存在 ⇒ `resolve` 空、`getInfo` 返回 `UNKNOWN`（`StorageRepositoryImpl.java:80-82`）。

#### 1.7.4 其它

- **磁盘物品被销毁（岩浆/despawn/`/kill`）不会回收存储条目**：`StorageRepositoryImpl` 没有引用计数，`removeIfEmpty` 只在拆解时调用 ⇒ 条目会留在存档里（占空间、可能被 UUID 撞见）。
- **容器类方块不受影响**：存储方块 ↔ 物品的 UUID 转移见 `StorageContainerItemHelperImpl.java:139-155`。
- **`SavedData` 是全局的**：不按维度、不按区块；因此“磁盘在哪个维度”不影响内容，也不需要加载磁盘所在区块。

---

## 2. 二、本模组的通用储存磁盘

### 2.1 代码事实

```java
// src/main/java/cretae/cookiewyq/rs_create_compat/item/UniversalStorageDiskItem.java:30
public class UniversalStorageDiskItem extends AbstractStorageContainerItem {
```

```java
// 同文件 44-47（构造：复用 RS 的 helper）
super(
    new Item.Properties().stacksTo(1).fireResistant(),
    RefinedStorageApi.INSTANCE.getStorageContainerItemHelper()
);
```

```java
// 同文件 64-66（内容存储由本模组的 StorageType 创建）
protected SerializableStorage createStorage(final StorageRepository storageRepository) {
    return UniversalStorageType.INSTANCE.create(getCapacity(), storageRepository::markAsChanged);
}
```

```java
// storage/UniversalStorageType.java:30-31
public final class UniversalStorageType implements StorageType {
    public static final UniversalStorageType INSTANCE = new UniversalStorageType();
```

注册进 RS 的 `StorageTypeRegistry`（必须早于存档里磁盘数据的反序列化）：

```java
// RS_Create_Compat.java:668-672（commonSetup）
RefinedStorageApi.INSTANCE.getStorageTypeRegistry().register(
    ResourceLocation.fromNamespaceAndPath(MODID, "universal"),
    UniversalStorageType.INSTANCE
);
```

物品 id（`RS_Create_Compat.java:118-137`）：`rs_create_compat:universal_storage_disk_1k` / `_4k` / `_16k` / `_64k` / `_256k` / `_1m` / `_4m` / `_16m` / `_64m` / `_creative`（`capacity <= 0` 走“无限”档，`UniversalStorageDiskItem.java:38-40`）。

### 2.2 结论：**与 RS 原生磁盘是同一套**

| 维度 | RS 原生物品盘 | 本模组通用盘 | 证据 |
|---|---|---|---|
| 基类 | `AbstractStorageContainerItem` | `AbstractStorageContainerItem` | `ItemStorageDiskItem.java:21`；`UniversalStorageDiskItem.java:30` |
| UUID 组件 | `storage_reference` | 同一个（helper 是从 RS 拿的） | `UniversalStorageDiskItem.java:44-47`；`StorageContainerItemHelperImpl.java:162-164` |
| 仓库 | `StorageRepository`（overworld SavedData） | 同一个 | `RefinedStorageApiImpl.java:186-190` |
| 存储类型 | `StorageTypes.ITEM`（`SameTypeStorageType<ItemResource>`） | `rs_create_compat:universal`（`UniversalStorageType`） | `ItemStorageDiskItem.java:50-52`；`StorageTypes.java:12-16`；`UniversalStorageType.java:30-31` |
| 内容序列化 | 由 `StorageType` 的 codec 决定 | 由 `UniversalStorageType.getMapCodec` 决定，但都在**同一个** `StorageRepository` 的 map 里 | `StorageRepositoryImpl.java:36-42`；`UniversalStorageType.java:56-77` |
| 容量 | 1024 / 4096 / 16384 / 65536 / null（创造） | 1k…64M / null（创造），按“物品位” | `ItemStorageVariant.java:9-13`；`UniversalStorageDiskItem.java:38-40` |

⇒ **适配器只要按 `StorageContainerItem` 判据写，就同时支持 RS 原生盘、本模组通用盘，以及未来任何继承 `AbstractStorageContainerItem` 的第三方盘。**

### 2.3 差异（会影响适配器）

1. **通用盘允许混装**：`UniversalStorageType#isAllowed` 恒为 `true`（`UniversalStorageType.java:80-82`），并且可能真的存了流体/气体。ItemHandler 只能表达物品 ⇒ 适配器必须**过滤 `ItemResource`**，否则 `toItemStack` 会 `ClassCastException`。
2. **通用盘的 `getStored()` 是实时重算的**（遍历 `getAll()` 按“物品位”换算，`UniversalLimitedStorage.java:66-73`）⇒ 比原生盘（一个 long 字段）更贵，缓存策略要避免高频调用。
3. **混装开关**：`Config.universalDiskAllowMixedTypes`（`Config.java:28-31`）关闭时，`insert` 会拒绝与已有内容不同类别的资源（`UniversalLimitedStorage.java:76-102,133-143`）⇒ 适配器的 `insertItem` 必须把“返回 remainder”当作正常结果。
4. **容量口径**：通用盘 1 桶流体 = 1 物品位，`getStored()` 是“位”数，不一定等于物品件数；UI 显示“已用/容量”时要说明（原生盘 1 件 = 1）。

---

## 3. 三、适配器设计（核心）

### 3.1 首选：给磁盘 `Item` 注册 `Capabilities.ItemHandler.ITEM`

#### 3.1.1 签名与语义（A）

```java
// net/neoforged/neoforge/capabilities/RegisterCapabilitiesEvent.java:136-149
public <T, C extends @Nullable Object> void registerItem(
        ItemCapability<T, C> capability,
        ICapabilityProvider<ItemStack, C, T> provider,
        ItemLike... items) {
    Objects.requireNonNull(provider);
    if (items.length == 0) throw new IllegalArgumentException("Must register at least one item");
    for (ItemLike itemLike : items) {
        Item item = Objects.requireNonNull(itemLike.asItem());
        capability.providers.computeIfAbsent(item, i -> new ArrayList<>()).add(provider);
    }
}
```

```java
// net/neoforged/neoforge/capabilities/ItemCapability.java:88-104
public T getCapability(ItemStack stack, C context) {
    if (stack.isEmpty()) return null;          // 空栈永远没有能力
    for (var provider : providers.getOrDefault(stack.getItem(), List.of())) {
        var ret = provider.getCapability(stack, context);
        if (ret != null) return ret;
    }
    return null;
}
```

```java
// net/neoforged/neoforge/capabilities/Capabilities.java:48
public static final ItemCapability<IItemHandler, @Nullable Void> ITEM =
        ItemCapability.createVoid(create("item_handler"), IItemHandler.class);
```

**关键结论**：

- provider 的入参是 **真实 `ItemStack`**（`ICapabilityProvider<ItemStack, C, T>`，`ICapabilityProvider.java:17-31`），可以读它的 `storage_reference` 组件 ⇒ **天然支持“每个盘一个 handler”**，不需要按 stack 注册。
- context 是 `Void` ⇒ **没有 Level、没有方向、没有玩家**。这是唯一的硬伤，见 §3.2。
- `getCapability` **没有缓存**：每次查询都会调用 provider（可能新建 handler）。设计时必须假设 handler 会被频繁重建。

#### 3.1.2 注册时机允许遍历注册表（A）

```java
// net/neoforged/neoforge/internal/CommonModLoader.java:72-76
ModLoader.dispatchParallelEvent("Common setup", ..., FMLCommonSetupEvent::new);
...
ModLoader.runInitTask("Registration events", ..., RegistrationEvents::init);
```

```java
// net/neoforged/neoforge/internal/RegistrationEvents.java:17-21
static void init() {
    CauldronFluidContent.init();     // must be before capability event
    CapabilityHooks.init();          // must be after cauldron event
    ...
}
```

`CapabilityHooks.init()` 里 `new RegisterCapabilitiesEvent()` 并 `postEventWrapContainerInModOrder`（`CapabilityHooks.java:43-52`）。而 `CommonModLoader.begin` 早已 `GameData.freezeData()`（同文件 46-55 行）⇒ **在 `RegisterCapabilitiesEvent` 里遍历 `BuiltInRegistries.ITEM` 是安全且完整的**（所有模组的物品都已注册并冻结）。**A**
（本模组已有 `event.registerItem(EnergyStorage.ITEM, ..., item)` 的先例：`RS_Create_Compat.java:711-719`。）

#### 3.1.3 注册范围

推荐：

```
在 RegisterCapabilitiesEvent 里遍历 BuiltInRegistries.ITEM，
凡 item instanceof StorageContainerItem 的都 registerItem(Capabilities.ItemHandler.ITEM, provider, item)。
```

优点：
- 覆盖 RS 原生物品盘（1k/4k/16k/64k/creative）+ 本模组 10 档通用盘 + 第三方 addon 盘；
- 不依赖具体物品 id / 类（RS 2.0.9 没有 `refinedstorage:disk`，见 `ContentIds.java:96-102`，写死 id 必错）；
- 不会误伤存储方块物品（`AbstractStorageContainerBlockItem` 并**没有**实现 `StorageContainerItem`，见该类源码 29-33 行）。

**避免遮蔽别的模组**：注册前用 `event.isItemRegistered(Capabilities.ItemHandler.ITEM, item)` 跳过“已经有 provider”的物品（签名见 `RegisterCapabilitiesEvent.java:151-154`）。provider 列表按注册顺序追加、`getCapability` 取第一个非 null（`ItemCapability.java:99-103`），先注册者可能遮蔽后来者，所以能跳过就跳过。

注意：
- `BuiltInRegistries.ITEM` 里可能有 `Items.AIR`（不实现接口，天然排除）。
- 注册前若 RS 未加载，`instanceof StorageContainerItem` 会直接 `NoClassDefFoundError` —— 本模组已把 RS 设为必需依赖（`gradle.properties:71`），但注册代码仍建议放在 `RS_Create_Compat` 里、由 RS 存在性保护（可选）。
- 若担心“外溢”（任何模组都能读到磁盘内容），加配置 `rsDiskItemCapabilityEnabled`（默认 true）。

### 3.2 provider 里怎么拿 `StorageRepository`（没有 Level 上下文）

#### 3.2.1 可行入口

```java
// net/neoforged/neoforge/server/ServerLifecycleHooks.java:138-141
@Nullable
public static MinecraftServer getCurrentServer() { return currentServer; }
```

```java
// net/minecraft/server/MinecraftServer.java:1123
public ServerLevel getLevel(ResourceKey<Level> levelKey);
```

```java
// net/minecraft/world/level/Level.java:81
public static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, ...);
```

#### 3.2.2 推荐写法（含线程/客户端门禁）

【伪代码，不落地】

```java
private static IItemHandler provideDiskHandler(ItemStack stack, Void ctx) {
    if (!(stack.getItem() instanceof StorageContainerItem diskItem)) return null;

    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) return null;                 // 还没启动 / 已停止
    if (!server.isSameThread()) return null;         // 客户端线程 / 网络线程 / 异步线程：一律不提供
    ServerLevel overworld = server.getLevel(Level.OVERWORLD);
    if (overworld == null) return null;              // 极端早期

    StorageRepository repo = RefinedStorageApi.INSTANCE.getStorageRepository(overworld);
    return diskItem.resolve(repo, stack)
        .map(storage -> new RsccDiskItemHandler(stack, storage, repo))
        .orElse(null);                               // 未格式化 / UUID 不在仓库：没有能力
}
```

为什么必须 `isSameThread()`：

- 单人游戏里 `getCurrentServer()` 在**客户端线程**也非 null（集成服务器），如果不判线程，客户端会拿着服务端对象在客户端线程读 `HashMap` ⇒ 数据竞争，甚至触发 `ClientStorageRepository` 之外的路径异常。
- `MinecraftServer extends BlockableEventLoop`，`isSameThread()` 是 public（`BlockableEventLoop.java:35`；`MinecraftServer.java:1520` 也在用）。
- 客户端本来就没内容（`ClientStorageRepository#get` 抛异常），返回 null 是正确语义。

**为什么不在 provider 里调用 `level.getServer()`**：provider 根本没有 `level`。这是 item capability 与 block capability 的本质区别（`BlockCapability` 才有 `Level/BlockPos`）。

#### 3.2.3 代价与替代

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A（推荐）** | provider 用 `ServerLifecycleHooks.getCurrentServer()` + overworld | 代理器不需要任何特例；所有容器扫描器都受益 | 依赖 NeoForge 静态入口；有线程门禁成本；任何模组都能读 |
| B | 代理器特例：BE 用自身 `level` 拿 repo，再构造同一个 handler | 不依赖静态入口 | 只有本模组代理器能用；需要 `instanceof StorageContainerItem` 特例 |
| C | 自定义一个带 `Level` context 的 `ItemCapability` | 语义最干净 | 不是 `Capabilities.ItemHandler.ITEM`，第三方管道不认 ⇒ 失去意义 |

**建议**：A 作为首选；handler 类做成“构造时接收 `StorageRepository`”，于是 B 只是换一个构造入口，**两条路共用同一套代码**。

### 3.3 IItemHandler 形态：三个方案对比

先明确 IItemHandler 的契约要点（A）：

```java
// net/neoforged/neoforge/items/IItemHandler.java:23-27
/**
 * Returns the ItemStack in a given slot.
 * The result's stack size may be greater than the itemstack's max size.
 * ...
 */
ItemStack getStackInSlot(int slot);
```

```java
// 同文件 41-70（节选）
ItemStack insertItem(int slot, ItemStack stack, boolean simulate);
ItemStack extractItem(int slot, int amount, boolean simulate);   // 返回数量必须 <= amount 且 <= getMaxStackSize()
int getSlotLimit(int slot);
boolean isItemValid(int slot, ItemStack stack);
```

**注意**：`getStackInSlot` **允许**返回 count 超过物品最大堆叠；`extractItem` **不允许**。这一条直接决定了本设计能“既真实又兼容”。

#### 方案 1：单虚拟槽（`getSlots() = 1`，`getStackInSlot(0)` = 某种“当前物品”）

- 优点：实现最简单；`insertItem` 天然是“整体插入”。
- **致命缺点（对 RS 外部存储）**：RS 的 `ItemHandlerExtractableStorage#extract` 是**按槽内容匹配资源**的：

```java
// neoforge/storage/ItemHandlerExtractableStorage.java:31-56（节选）
for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
   ItemStack stackInSlot = itemHandler.getStackInSlot(slot);
   boolean relevant = ItemStack.isSameItemSameComponents(stackInSlot, stack);
   if (relevant) { ... extractSlot(...) ... }
}
```

单槽只有“当前显示的那一种”，**要抽其它资源永远匹配不到**；`ForgeHandlerUtil.getCurrentAmount`（`ForgeHandlerUtil.java:14-25`）也只会报一种资源；`CapabilityCache#getItemAmountIterator` 每槽产出一条 `ResourceAmount`（`CapabilityCache.java:47-66`）。
- 对普通管道同样糟：漏斗/溜槽 blind extract 会拿到“随机”种类的物品，过滤器语义崩溃。
- **结论：不可取。**

#### 方案 2：每种资源一个槽，`getSlots()` = 种类数（动态、无上限）

- 优点：语义最忠实；RS 外部存储可以逐种类抽取；`getStackInSlot` 可以返回真实总量。
- 缺点：
  1. `getSlots()` 随内容变化。IItemHandler 没有“槽数变化”通知；`AbstractContainerMenu`、第三方缓存、RS 的迭代器都可能在两次调用之间看到不同槽数。
  2. 数量无上限：1k 盘最多 1024 种，64M 通用盘理论上可到数千万种 ⇒ RS 外部存储每次刷新遍历所有槽，直接卡死。
  3. 空盘 `getSlots()=0`，很多管道会认为“没有库存”而不插入。
- **结论：不适合作为 v1 的默认形态**（可作为“GUI 全量列表”的数据源，见 §3.7）。

#### 方案 3（推荐）：固定 `N` 个虚拟槽的“资源种类窗口”

- `getSlots()` **恒为 N**（配置，默认 64，范围 1–1024）；
- 每个槽通过**粘性分配表**绑定到一种资源（`ResourceKey`）；空槽是合法的；
- `getStackInSlot(i)` 返回该种类的**真实总量**（`ItemResource#toItemStack(amount)`，clamp 到 `Integer.MAX_VALUE`）——NeoForge 明确允许；
- `extractItem(i, n)` 只抽该槽绑定的那一种，返回 `min(n, getMaxStackSize)`；
- `insertItem(任何槽, stack)` 都写到“磁盘整体”，不要求与槽绑定种类一致（磁盘本就没有槽语义）；
- 超过 N 的种类对**管道**不可见，但对 GUI 可见（GUI 走原生门面，不依赖槽）。

**为什么这是最优折中**：

1. 满足 RS 外部存储“按槽匹配资源”的抽取模型（`ItemHandlerExtractableStorage.java:31-56`），每种可见资源都能被精确定位；
2. 槽数固定，第三方缓存/菜单安全；
3. 真实总量可被 RS 正确统计：`ForgeHandlerUtil#getCurrentAmount` 是把同物品同组件的所有槽 count **相加**（`ForgeHandlerUtil.java:14-25`），`getStackInSlot` 给出 10000 就是 10000；
4. RS 2.0.9 **专门写了“槽内数量 > maxStackSize”的模拟分支**，证明“槽内超堆叠”是被预期支持的：

```java
// neoforge/storage/ItemHandlerExtractableStorage.java:51-56
case SIMULATE -> {
   int amountInSlot = stackInSlot.getCount();
   int maxStackSize = stackInSlot.getMaxStackSize();
   int extracted = itemHandler.extractItem(slot, amount, true).getCount();
   yield amount > maxStackSize && extracted == maxStackSize && amountInSlot > maxStackSize
       ? Math.min(amount, amountInSlot)
       : extracted;
}
```

5. `extractItem` 的返回值仍然遵守契约（≤ `amount` 且 ≤ `getMaxStackSize`），不会制造非法 stack。

**代价（必须写进用户文档）**：N 之外的种类对自动化不可见；N 越大，RS 外部存储每次刷新遍历越贵。

### 3.4 槽位分配（粘性）

【设计规则】

1. 每次重建快照时，把当前所有 `ItemResource` 按 `(registryId, components.toString())` 排序，得到稳定顺序。
2. 维护 `ResourceKey[] slotResource`（长度 N）与 `Map<ResourceKey,Integer> resourceToSlot`。
3. **粘性**：仍然存在的资源保留原槽；已消失的资源释放槽；新资源按排序依次填入最小空闲槽。
4. 插入时：若目标槽为空 ⇒ 把插入资源绑到该槽；若目标槽已有别的资源 ⇒ **不改变绑定**（磁盘级插入仍然成功）。
5. 槽位只影响“可见/可抽取”，不影响存储本身。

这样：
- 内容不变时，槽位完全稳定 ⇒ 过滤器/管道行为可预测；
- 内容变化时，最多是“新种类占空槽”，不会把老种类挤走（除非它被抽空）；
- 空盘时所有槽为空，任意插入都能立刻在“被插入的那个槽”看到。

### 3.5 槽语义规则表

| 方法 | 行为 | 证据/理由 |
|---|---|---|
| `getSlots()` | 恒 `N`（默认 64） | 稳定槽数；`IItemHandler` 无槽数变更通知 |
| `getStackInSlot(i)` | 槽绑定的 `ItemResource` → `toItemStack(amount)`；无绑定/数量 0 → `ItemStack.EMPTY` | `ItemResource.java:42-47`；`IItemHandler.java:23-27` 允许超堆叠 |
| `insertItem(i, stack, simulate)` | `ItemResource.ofItemStack(stack)` → `storage.insert(resource, count, SIMULATE/EXECUTE, actor)`；返回 `count - inserted` 的副本 | `ItemResource.java:80-82`；`InsertableStorage.java:13` |
| `extractItem(i, amount, simulate)` | 只抽槽绑定资源；`n = min(amount, min(64, item.getMaxStackSize()))`；`storage.extract(...)`；返回实际抽出量 | `IItemHandler.java:41-70` 的返回值约束 |
| `getSlotLimit(i)` | 槽有绑定：`min(64, 该物品 maxStackSize)`；空槽：64 | 与 `extractItem` 上限一致 |
| `isItemValid(i, stack)` | `storage.getType().isAllowed(ItemResource.ofItemStack(stack))`；流体盘返回 false | `StorageType.java:13-20`；`SameTypeStorageType.java:51-54` |
| 线程 | 只允许服务端主线程；其它线程返回 null（能力层）/抛异常（handler 层防御） | 见 §3.2.2 |

**关于“超堆叠”的兼容性说明（必须向用户写明）**：

- NeoForge 契约允许 `getStackInSlot` 超堆叠（`IItemHandler.java:23-27`）；
- RS 2.0.9 的读取/抽取路径支持（`ForgeHandlerUtil.java:14-25`；`ItemHandlerExtractableStorage.java:31-56`）；
- **Create / 其它物流模组是否对超堆叠做 clamp 未实测**（C，见 §5）。若实测有问题，加配置 `rsDiskHandlerOversizedStacks=false`：此时 `getStackInSlot` 把数量 clamp 到 64，代价是 RS 外部存储看到的总量也变成 64（对“总量准确性”是降级）。

### 3.6 写回：`insertItem` → 直接写磁盘存储

【伪代码，不落地】

```java
public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    if (stack.isEmpty()) return ItemStack.EMPTY;
    ItemResource resource = ItemResource.ofItemStack(stack);
    if (!storage.getType().isAllowed(resource)) return stack;   // 流体盘 / 混装开关拒绝
    long inserted = storage.insert(resource, stack.getCount(),
            simulate ? Action.SIMULATE : Action.EXECUTE, actor);
    if (!simulate && inserted > 0) {
        invalidateSnapshot();          // 自己的写：立即失效
        if (slotResource[slot] == null) bind(slot, resource);
    }
    if (inserted >= stack.getCount()) return ItemStack.EMPTY;
    return stack.copyWithCount(stack.getCount() - (int) inserted);
}
```

要点：

1. **不需要写回物品 NBT/组件**：磁盘物品只有 UUID，内容在 `StorageRepository`；插入后原本就在存档里，`listener.run()` 会 `setDirty()`（`PlatformStorage.java:51-66`；`ItemStorageDiskItem.java:50-52`）。
2. **容量是自动裁剪的**：不要自己算容量；`inserted` 就是被接受的数量（`LimitedStorageImpl.java:30-38`；`UniversalLimitedStorage.java:76-102`）。
3. **`Actor` 的选择**：用 `Actor.EMPTY`（"Empty"）最安全；若代理器能拿到玩家（GUI 插入），用 `new PlayerActor(player)` 可以让 RS 的“最后修改者”显示玩家名（`InMemoryTrackedStorageRepository#update` 用 `actor.getClass()` 分桶，`InMemoryTrackedStorageRepository.java:18-36`）。**不要传 null**（`TrackedStorageImpl.java:32-58`）。
4. **`SIMULATE` 必须用同一资源/数量**，否则会出现“模拟成功、执行失败”（内容被别处改掉）。
5. **无需 Level/坐标/区块加载**；这是与方块库存代理最大的不同。

### 3.7 代理器怎么表现（契约）

#### 3.7.1 首选：完全通用，无特例

- 代理器的“货物槽”接收任意物品；
- 每次需要“内容物面”时：`IItemHandler h = cargoStack.getCapability(Capabilities.ItemHandler.ITEM);`
- `h == null` ⇒ 该面没有库存（不提供能力 / 返回空）；
- `h != null` ⇒ 直接委托：`getSlots/getStackInSlot/insertItem/extractItem/...`。
- **磁盘、潜影盒、背包走同一条路**。

#### 3.7.2 空盘 / 未格式化盘的表现

- 已格式化空盘：`resolve` 成功、`getAll()` 为空 ⇒ handler 存在，`getSlots()=N` 全空；`insertItem` 可写；`extractItem` 恒空。**与“空箱子”表现一致。**
- 未格式化盘：`resolve` 为空 ⇒ **没有能力**。代理器若只按“能力是否存在”判断，会把它当成“不可代理的物品”。建议代理器的**货物槽校验**额外接受 `stack.getItem() instanceof StorageContainerItem`（类级判据，不是按 id），UI 显示“未格式化：请先拿在玩家背包里一 tick”。这不影响内容物面（仍然 null）。
- 也可以做“一次性格式化”：在玩家把盘放进代理器时，对 stack 调一次 `diskStack.getItem().inventoryTick(diskStack, level, player, 0, false)`，借用 RS 自己的 `loadStorageIfNecessary`（`AbstractStorageContainerItem.java:50-54`）生成 UUID。**前提：必须服务端、必须有 `Player`**（helper 要求 `entity instanceof Player`，`StorageContainerItemHelperImpl.java:41-46`）；漏斗/管道插入无玩家 ⇒ 仍无法格式化。见 §3.9。

#### 3.7.3 GUI：可选“富接口”，仍不算特例

给 handler 增加一个可选接口（**不按物品 id 判断**，只按 handler 类型判断）：

```java
interface RsccDiskView {
    long stored();                 // 已用（通用盘是“物品位”）
    long capacity();               // 0 = 未知/无限（创造盘）
    int kindCount();               // 全部种类数（含不可见）
    List<ResourceAmount> snapshot(); // 全部资源（含流体/气体）
    ItemStack extractAny(ItemStack template, int amount, boolean simulate);
    ItemStack insertAny(ItemStack stack, boolean simulate);
}
```

代理器 GUI：

- `handler instanceof RsccDiskView` ⇒ 显示“总量/容量/种类列表 + 全量取出/存入”；
- 否则按普通 N 槽显示。

这样 GUI 能看到全部种类（不受 N 限制），而管道路径保持通用。

#### 3.7.4 客户端

- 代理器的 Screen 不应该查询 `ItemHandler.ITEM`（客户端拿不到，返回 null）；
- 内容列表通过 `AbstractContainerMenu` 的 `DataSlot`/自定义包同步（与 RS 自己的 `StorageInfoRequestPacket` 类似，`StorageInfoRequestPacket.java:24-26`）；
- 客户端即使拿到 handler，也只能看到 0（provider 返回 null）。

### 3.8 缓存与性能

#### 3.8.1 为什么必须缓存

- `getAll()` 每次调用都**新建 list**（`MutableResourceListImpl.java:81-83`），几千种时是几千次对象分配；
- `getStackInSlot` 会被 RS 外部存储按“每个槽、每次刷新”调用（`CapabilityCache.java:47-66`；`ForgeHandlerUtil.java:14-25`），如果每次都 `getAll()` 会直接爆；
- handler 实例可能每次 `getCapability` 都新建（`ItemCapability.java:88-104` 无缓存）⇒ **缓存不能放在 handler 实例里**，必须按 UUID 放到静态缓存。

#### 3.8.2 推荐缓存结构

【伪代码，不落地】

```java
final class DiskViewCache {
    // key: UUID；value: 快照 + 槽位分配 + 时间戳
    // 服务器停止/换世界：清空（ServerStoppedEvent）
    // 上限：LRU 64 个盘，防止长跑整合包无限增长
}
```

快照内容：

```java
record Snapshot(
    List<ResourceAmount> items,        // 只含 ItemResource，按 (registryId, components) 排序
    Map<ResourceKey, Long> amounts,
    ResourceKey[] slotResource,        // 长度 N，粘性
    long stored,                       // storage.getStored()
    long capacity,                     // LimitedStorage ? getCapacity() : 0
    long lastStored,                   // 用于廉价失效探测
    int tickStamp
) {}
```

失效策略：

1. **自己的写**（`insertItem`/`extractItem` 执行成功）⇒ 立即失效；
2. **TTL**（默认 10 tick，可配）⇒ 兜底外部变化（RS 网络、另一个代理器、玩家手动操作）；
3. **廉价探测**：原生盘 `getStored()` 是 O(1) 字段（`StorageImpl.java:66-68`），若 `getStored()` 变了就重建；通用盘的 `getStored()` 是 O(n)（`UniversalLimitedStorage.java:66-73`），**不要**用它做每 tick 探测，只靠 TTL；
4. 任何 `extractItem` 执行前先 `Action.SIMULATE` 校验（防止快照过期导致抽到不存在的东西；`StorageImpl#extract` 不存在时返回 0，`StorageImpl.java:29-38`）。

#### 3.8.3 成本量级

| 操作 | 成本 | 证据 |
|---|---|---|
| 重建快照 | O(种类数) 分配 + 排序 | `MutableResourceListImpl.java:81-83` |
| `getStackInSlot` | O(1)（查快照 + `toItemStack` 新建 stack） | 缓存设计 |
| `extractItem` | 2 次 map 查找 + list 删除（SIMULATE+EXECUTE） | `StorageImpl.java:29-47` |
| `insertItem`（原生盘） | O(1) + `setDirty` | `StorageImpl.java:50-58`；`PlatformStorage.java:51-66` |
| `insertItem`（通用盘） | O(种类数)（`getStored()` 重算） | `UniversalLimitedStorage.java:66-73,89` |
| RS 外部存储每次刷新 | O(N) 次 `getStackInSlot` | `CapabilityCache.java:47-66` |

**建议**：N 默认 64；若用户抱怨“大磁盘种类看不到”，优先在 GUI 里解决（富接口），而不是盲目加大 N（会线性增加 RS 外部存储的每 tick 成本）。

### 3.9 可选：自动格式化未格式化盘

**问题**：管道/漏斗把一张从未进过玩家背包的盘塞进代理器，`resolve` 永远为空。
**通用做法（不复制 RS 逻辑）**：对 `AbstractStorageContainerItem` 直接调一次它的 `inventoryTick`：

```java
// 伪代码；仅在“玩家手动放入代理器”的路径调用
stack.getItem().inventoryTick(stack, level, player, 0, false);
```

- `AbstractStorageContainerItem#inventoryTick`（`AbstractStorageContainerItem.java:50-54`）会调用 `loadStorageIfNecessary`，用物品自己的 `createStorage` 生成正确 `StorageType`/容量；
- 服务端 + `Player` 是硬条件（`StorageContainerItemHelperImpl.java:41-46`）；
- 会顺带执行 `transferStorageIfNecessary`（升级盘的转移逻辑），这正是我们想要的；
- **风险**：绕过正常 tick 语义、可能触发该物品 `inventoryTick` 的其它副作用；只对 `instanceof StorageContainerItem` 调，且只在“玩家放入”这一条路径调，风险可控。**运行期需实测（C）**。

**v1 建议**：不做自动格式化，只在 UI 提示“把盘拿在手里一 tick”；v1.1 再加上述一次性调用。

### 3.10 注册骨架（伪代码）

```java
// 伪代码，不落地
public static void registerDiskItemCapabilities(RegisterCapabilitiesEvent event) {
    if (!Config.rsDiskItemCapabilityEnabled) return;
    for (Item item : BuiltInRegistries.ITEM) {
        if (item instanceof StorageContainerItem
                && !event.isItemRegistered(Capabilities.ItemHandler.ITEM, item)) {
            event.registerItem(Capabilities.ItemHandler.ITEM, RsccDiskHandlers::provide, item);
        }
    }
}
```

```java
// 伪代码：handler 不关心 repository 从哪来
final class RsccDiskItemHandler implements IItemHandler, RsccDiskView {
    private final ItemStack stack;
    private final SerializableStorage storage;
    private final StorageRepository repo;
    private final int slots;              // N
    private final DiskViewCache cache;    // 按 UUID 共享
    ...
}
```

### 3.11 建议新增的配置项

| 配置键 | 默认 | 说明 |
|---|---|---|
| `rsDiskItemCapabilityEnabled` | `true` | 是否给所有 `StorageContainerItem` 注册 `ItemHandler.ITEM`（关闭 = 退回代理器特例/不可用） |
| `rsDiskHandlerSlots` | `64` | 虚拟槽窗口 N（1–1024） |
| `rsDiskHandlerOversizedStacks` | `true` | `getStackInSlot` 是否返回真实总量（>64）；关闭则 clamp 到 64 |
| `rsDiskHandlerSnapshotTtl` | `10` | 快照 TTL（tick） |
| `rsDiskHandlerCacheSize` | `64` | 按 UUID 缓存的盘数量上限（LRU） |
| `rsDiskHandlerAllowInsert` / `AllowExtract` | `true` / `true` | 只读/只写模式（给整合包作者） |

---

## 4. 四、结论、风险与验证

### 4.1 首选方案：给 Item 注册能力 vs 代理器特例

| 维度 | 给 `Item` 注册能力（首选） | 代理器特例 |
|---|---|---|
| 代理器代码 | **零特例**（`stack.getCapability(ITEM)` 一条路） | 需要 `instanceof StorageContainerItem` + 用自身 level 构造 handler |
| 覆盖范围 | 所有 `StorageContainerItem`（RS 原生 + 本模组 + 第三方 addon） | 只有本模组的代理器 |
| Level 来源 | 静态 `ServerLifecycleHooks` + overworld + 线程门禁 | BE 自身 `level`（最干净） |
| 客户端 | 只能返回 null（无内容） | 同左 |
| 外溢风险 | 任何模组都能查询磁盘内容（配置可关） | 无外溢 |
| 与其它模组 provider 冲突 | 先注册者先返回；我们返回非 null 会“遮蔽”后来者 | 无 |
| 实现成本 | 一个事件监听 + 一个 handler 类 | 同左 + 代理器分支 |
| 结论 | **是**（用户要求的“最优解”成立） | 仅作为关闭配置时的兜底 / GUI 全量视图的补充 |

**最终建议**：

1. **首选给 Item 注册 `Capabilities.ItemHandler.ITEM`**；handler 类接受 `StorageRepository` 参数，因此兜底特例可以复用。
2. 形态用 **固定 N 的“资源种类窗口”**（不是单槽、不是无限槽）。
3. GUI 通过可选的 `RsccDiskView` 富接口看全量；管道路径保持 IItemHandler 语义。
4. 默认允许读写；给整合包作者留 `AllowInsert/AllowExtract` 开关。

### 4.2 风险清单

| # | 风险 | 触发条件 | 后果 | 缓解 |
|---|---|---|---|---|
| R1 | **复制盘共享 UUID** | 创造中键复制 / NBT 复制 / 备份还原 | 两个盘内容与容量共享；拆解空副本会让另一副本悬空 | 文档说明“复制盘 = 同一个存储”；不要在有副本时拆解 |
| R2 | **未格式化盘** | 盘从未进过玩家背包（箱子/结构/命令直接塞容器） | `resolve` 空 ⇒ 没有能力；无法写入 | UI 提示“拿在手里一 tick”；可选 §3.9 |
| R3 | **跨世界复制** | 把盘 item 带到另一个存档 | UUID 在新世界不存在 ⇒ 空盘（内容不跟随） | 文档说明；需要“导出/导入”功能则另做 |
| R4 | **客户端无内容** | 在客户端查询能力/直接 `resolve` | provider 返回 null；若绕过则 `ClientStorageRepository#get` 抛 `UnsupportedOperationException` | 客户端只走菜单同步；永不调用 `resolve` |
| R5 | **超堆叠 stack** | `getStackInSlot` 返回 count > 64 | NeoForge 契约允许、RS 支持；**第三方物流（Create 等）行为未实测** | 配置 `OversizedStacks=false` 可退回 clamp 64 |
| R6 | **窗口外种类不可见** | 盘内种类 > N | 管道/RS 外部存储只看到 N 种；GUI 仍可看全部 | 调大 N 或用 GUI；文档说明 |
| R7 | **快照过期** | 别的代理器/RS 网络改动同一 UUID | 最多 TTL 内显示旧数量；抽取前 SIMULATE 校验，不会抽到不存在的东西 | TTL 可调；自己的写立即失效 |
| R8 | **性能** | 几千种 + 高频 `getAll()` / 大 N | 每 tick 分配与遍历 | 快照缓存；N 默认 64；通用盘避免每 tick `getStored()` |
| R9 | **自指 / 重复计数** | 同一 UUID 的盘既在磁盘驱动器里、又在代理器里；代理器面接 RS 外部存储且同网络 | 网络总量可能重复计算；抽取会被真实库存上限约束，但“按 2 倍做合成计划”会中途失败 | 与既有 `CONTAINER_PROXY_DESIGN.md` §0 第 10 条一致：代理器别做网络节点；避免同 UUID 双接入 |
| R10 | **注册外溢** | 任何模组扫描物品能力 | 磁盘内容可被读取/写入（包括放在玩家背包里时） | 配置开关；文档声明这是特性 |
| R11 | **provider 冲突** | 别的模组也给同一物品注册 `ItemHandler.ITEM` | 先注册者先返回；我们的非 null 结果会遮蔽后来者 | 配置开关；只对 `StorageContainerItem` 注册 |
| R12 | **存储条目泄漏** | 磁盘物品被销毁 | 存档里留下无主条目 | 不在本适配器职责内；文档提示 |
| R13 | **创造盘容量 0** | `StorageInfo.of` 对非 `LimitedStorage` 返回 0 | UI 可能显示“0/0” | 把 0 解释为“无限/未知”，并用 `getStored()` 显示已用 |
| R14 | **通用盘混装流体/气体** | 通用盘存了非物品资源 | ItemHandler 只看到物品；`getStored()`（含流体位）与“物品件数”不一致 | GUI 标明“物品位”口径；流体走未来的 `IFluidHandlerItem` 适配器 |
| R15 | **线程** | 能力被异步/客户端线程查询 | 返回 null（能力层）；不会读到半更新状态 | `server.isSameThread()` 门禁 |

### 4.3 用户可执行的验证步骤

> 目标：确认“代理器里塞磁盘 → 能看到磁盘内容 → 能写入”。

**准备**

1. `/give @s refinedstorage:1k_storage_disk`（确认 id：`ContentIds.java:96-98`；RS 2.0.9 没有 `refinedstorage:disk`）。
2. 检查格式化：把盘拿在手里至少 1 tick；按 F3+H 开高级 tooltip，tooltip 会显示 UUID（`StorageContainerItemHelperImpl.java:132-133`）。或 `/data get entity @s Inventory` 看 `refinedstorage:storage_reference`。
3. 用 RS 磁盘驱动器/磁盘接口往盘里放 1000 个圆石。

**A. 读内容**

4. 把盘放进代理器“货物槽”。
5. 打开代理器 GUI：应显示 `stored=1000`、种类=1（或 GUI 的等价字段）。
6. 在代理器“内容物面”贴一个 RS 外部存储（或接 Create 漏斗/机械臂）：应能读到 1000 圆石（若 `OversizedStacks=true`；若为 false 则显示 64，见 R5）。
7. 用管道抽 64 个：盘内应剩 936；再抽一次 64：剩 872。

**B. 写内容**

8. 往代理器内容物面插入 100 个圆石：盘内变 972（容量未满时应全部接受）。
9. 把盘从代理器取出，放进 RS 磁盘驱动器：网络里应能看到 972 圆石（证明写回的是磁盘存储，不是代理器自己的 NBT）。

**C. 复制盘（R1）**

10. 创造模式中键复制该盘（或 `/give` 带同 UUID 组件的盘）：两个盘 tooltip 的 UUID 相同。
11. 从“副本”抽 72 个：原件也应只剩 900。

**D. 未格式化盘（R2）**

12. 用 `/item replace block <坐标> container.0 with refinedstorage:1k_storage_disk` 直接塞进箱子（不经过玩家背包）。
13. 把这张盘放进代理器：应显示“未格式化/无内容”，不能插入物品；把它拿回玩家背包 1 tick 后再放回代理器：应恢复正常。

**E. 客户端**

14. 在多人/单人客户端打开代理器 GUI：不应崩溃；日志不应出现 `UnsupportedOperationException`（来自 `ClientStorageRepository#get`）。
15. 在服务端日志确认没有“off-thread capability query”类异常（若出现，说明 `isSameThread()` 门禁没生效）。

**F. 持久化**

16. 插入物品后退出存档；`<存档>/data/refinedstorage_storages.dat` 的修改时间应更新；重进后数量不变。

**G. 边界**

17. 放入 `refinedstorage:64b_fluid_storage_disk`：ItemHandler 应为空（`isItemValid` 全 false），GUI 应提示“流体盘不适用物品视图”。
18. 放入存了流体的通用盘：ItemHandler 只显示物品；`stored` 含流体位，需与 GUI 的“物品位”说明一致。

---

## 5. 五、未证实项（明确列出）

1. **未做运行期验证**：本文所有行为均来自反编译源码/NeoForge 源码的代码路径，**没有启动游戏实测**（遵循“只调研、不写 Java、不碰 run/”的约束）。
2. **Create / 其它物流模组对“`getStackInSlot` 超堆叠”的兼容性**：NeoForge 契约允许、RS 2.0.9 支持，但 Create 的漏斗/溜槽/机械臂是否会在内部 clamp 到 64（导致只能搬 64/次）**未实测**。
3. **`RegisterCapabilitiesEvent` 里遍历 `BuiltInRegistries.ITEM` 的完整性**：代码顺序（`GameData.freezeData()` 在 `begin`，`RegistrationEvents::init` 在 `load` 之后）支持该结论，但**未在真实整合包中跑过**。
4. **`ServerLifecycleHooks.getCurrentServer()` 在单人集成服务器的客户端线程行为**：`isSameThread()` 是标准 API（`BlockableEventLoop.java:35`），门禁逻辑正确；但**没有实测“客户端线程查询磁盘能力”**。
5. **客户端 `RefinedStorageApi.getStorageRepository(level)` 是否必然 NPE**：`level.getServer()` 在客户端为 null 是常识，但**未在客户端断点验证**。
6. **`/item replace block` 是否真的绕过 `inventoryTick`**：依据是 `Inventory#tick` 是唯一调用点（`Inventory.java:216-228`），但**未逐步验证命令路径**。
7. **`<存档>/data/refinedstorage_storages.dat` 的实际目录**：文件名来自 `DimensionDataStorage.java:39-40`，目录按原版 `ServerLevel` 的 data 目录推导（B），**未在真实存档中打开确认**。
8. **磁盘升级配方（1k → 4k）是否保持同一 UUID**：本文明只说“存储方块 ↔ 物品搬 UUID”（有证据），升级配方路径**未追踪**。
9. **`inventoryTick` 一次性调用格式化的副作用**：`AbstractStorageContainerItem#inventoryTick` 会走 `transferStorageIfNecessary` + `loadStorageIfNecessary`（有证据），但“在代理器插入路径手动调用”**未实测**（C）。
10. **第三方 addon 磁盘**：只要 `instanceof StorageContainerItem` 就会被覆盖，但 addon 是否重写 `resolve`/`createStorage` 语义**未逐个核实**。
11. **性能数字**：没有 benchmark；“N=64、TTL=10 tick”是设计默认值，不是实测最优值。
12. **`StorageRepositoryImpl#set` 在“UUID 已存在”时的异常是否会被自动格式化触发**：设计上不在适配器里自动 `set`，所以不会；但若未来做自动格式化，**必须先用 `repo.get(uuid)` 检查**。

---

## 附录 A：证据索引（类 / 方法 / jar 内路径）

| 结论 | 证据（文件:行） |
|---|---|
| 磁盘只存 UUID 组件 | `com/refinedmods/refinedstorage/common/content/DataComponents.java:26-28,67-73`；`common/AbstractModInitializer.java:859-864` |
| `storage_reference` 是持久化 + 网络同步 UUID | `common/AbstractModInitializer.java:861-862` |
| `resolve` 返回 `Optional<SerializableStorage>` | `common/api/storage/StorageContainerItem.java:13-16`；`common/api/storage/AbstractStorageContainerItem.java:33-36` |
| `resolve` = `getId().flatMap(repo::get)` | `common/storage/StorageContainerItemHelperImpl.java:36-38,162-164` |
| 仓库是 `Map<UUID, SerializableStorage>` | `common/storage/StorageRepositoryImpl.java:20-23,44-47` |
| 仓库挂 overworld `SavedData`，名 `refinedstorage_storages` | `common/RefinedStorageApiImpl.java:186-190`；`StorageRepositoryImpl.java:21` |
| SavedData 文件为 `<name>.dat` | `net/minecraft/world/level/storage/DimensionDataStorage.java:39-40` |
| 按 `StorageType` 分发的 codec | `common/storage/StorageRepositoryImpl.java:36-42` |
| 首次格式化生成 UUID 并 `set` | `common/storage/StorageContainerItemHelperImpl.java:67-71` |
| 重复 UUID 抛异常 | `common/storage/StorageRepositoryImpl.java:49-59` |
| 拆解空盘 `removeIfEmpty` | `common/storage/StorageContainerItemHelperImpl.java:90-96`；`StorageRepositoryImpl.java:62-71` |
| 内容读取 `getAll/getStored` | `api/storage/StorageView.java:12-15`；`api/storage/StorageImpl.java:61-68` |
| `getAll` 拷贝 + HashMap 无序 | `api/resource/list/MutableResourceListImpl.java:27,81-83` |
| 物品资源 → ItemStack | `common/support/resource/ItemResource.java:23,42-47,80-82` |
| 容量信息 | `common/api/storage/StorageInfo.java:12-17` |
| 写入签名 | `api/storage/InsertableStorage.java:13`；`api/storage/ExtractableStorage.java:13` |
| 容量裁剪 | `api/storage/limited/LimitedStorageImpl.java:30-38` |
| 写入触发 `markAsChanged` | `common/storage/PlatformStorage.java:51-66`；`common/storage/storagedisk/ItemStorageDiskItem.java:50-52` |
| `Actor` 不能为 null | `api/storage/tracked/TrackedStorageImpl.java:32-58` |
| 客户端无内容 | `common/storage/ClientStorageRepository.java:26-28,53-58` |
| 只有背包 tick 会格式化 | `net/minecraft/world/entity/player/Inventory.java:216-228`；`common/storage/StorageContainerItemHelperImpl.java:41-46` |
| RS 没有给磁盘注册 ITEM 能力 | `neoforge/ModInitializer.java:426-490`（455-457、463-486 只有 BLOCK / EnergyStorage.ITEM） |
| `registerItem` 签名 | `net/neoforged/neoforge/capabilities/RegisterCapabilitiesEvent.java:136-149` |
| provider 拿到真实 stack | `net/neoforged/neoforge/capabilities/ICapabilityProvider.java:17-31`；`ItemCapability.java:88-104` |
| 能力注册时机在 FMLCommonSetup 之后 | `net/neoforged/neoforge/internal/CommonModLoader.java:72-76`；`RegistrationEvents.java:17-21`；`CapabilityHooks.java:43-52` |
| `ITEM` 能力 context 是 Void | `net/neoforged/neoforge/capabilities/Capabilities.java:48` |
| `getStackInSlot` 允许超堆叠、`extractItem` 不允许 | `net/neoforged/neoforge/items/IItemHandler.java:23-27,41-70` |
| RS 外部存储按槽匹配资源抽取 | `neoforge/storage/ItemHandlerExtractableStorage.java:31-56` |
| RS 统计数量 = 槽 count 求和 | `neoforge/storage/ForgeHandlerUtil.java:14-25` |
| RS 遍历槽产出 ResourceAmount | `neoforge/storage/CapabilityCache.java:47-66` |
| 静态 server 入口 | `net/neoforged/neoforge/server/ServerLifecycleHooks.java:138-141` |
| 线程判断 | `net/minecraft/util/thread/BlockableEventLoop.java:35`；`MinecraftServer.java:1520` |
| 本模组磁盘同构 | `item/UniversalStorageDiskItem.java:30,44-47,64-66`；`storage/UniversalStorageType.java:30-31`；`RS_Create_Compat.java:668-672` |
| 本模组盘 id | `RS_Create_Compat.java:118-137` |
| 本模组容量/混装规则 | `storage/UniversalLimitedStorage.java:66-102,133-143`；`Config.java:28-31` |
| 本模组已有 `registerItem` 先例 | `RS_Create_Compat.java:711-719` |
| 避免遮蔽已有 provider | `net/neoforged/neoforge/capabilities/RegisterCapabilitiesEvent.java:151-154`；`ItemCapability.java:99-103` |

## 附录 B：与 `docs/CONTAINER_PROXY_DESIGN.md` 的关系

- 该文档 §2.4 的结论“RS 磁盘 **没有** `ItemHandler.ITEM`，按 ItemHandler 语义不可代理” **仍然是事实**（RS 2.0.9 确实没注册，见 `ModInitializer.java:426-490`）。
- 本文的增量是：**我们可以在自己的模组里给这些 `Item` 补注册 `ItemHandler.ITEM`**，从而把“不可代理”变成“可代理”。该文档当时担心的“把 `StorageRepository` 整个暴露 ⇒ 自指环”在本文设计里被收窄为“**只暴露某一张盘 UUID 对应的那份存储**”；但“同一 UUID 同时被磁盘驱动器和代理器接入同一网络”仍会重复计数（R9），这与该文档 §0 第 10 条一致，不矛盾。
- 该文档 §2.6.3 说“不应在代理器里做（写 `StorageRepository`）”——本文的设计前提是**用户明确要求做专用适配器**，并且只在“代理器内容物面”暴露**单盘**存储；风险已在 §4.2 列明。建议后续把该文档 §2.4/§2.6.3 的措辞更新为“原生没有能力，但可用本适配器补齐”。
