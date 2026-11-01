# RS 2.0.9 外部存储“同一底层库存算两遍”核实报告

> 核实对象：`refinedstorage-neoforge-2.0.9.jar`（实际构建使用的版本，见 `gradle.properties`）
> 核实方式：jar 内字节码 + Vineflower 反编译源码 + 同版本依赖源码（NeoForge 21.1.248 / Create 6.0.10）
> 约束遵守：**未改任何 Java**；**未碰 `run/`**；jar 只在工作区外临时目录读取，未解压进工作区。
> 结论性质：**A = 代码路径/字节码已核实；C = 游戏内运行后果未实测**（本环境无法启动客户端）。

---

## 0. 先给结论

### 0.1 ② 会不会算两遍？——**会**

在同时满足下面 4 个条件时，RS 2.0.9 会把同一批物品计 **2 次**（不是“可能”，是存储聚合的必然结果）：

1. 两个 RS **外部存储**方块接在**同一个网络**上（都连到同一个 Controller/同一张网络）；
2. 两个外部存储的 `sourcePosition` 是**同一个方块**（同一底层库存），或者虽然是不同方块、但对外暴露的是**同一批物品**；
3. 两个面暴露的资源**有重叠**（同一批物品在两个面上都可见）；
4. 两个外部存储都处于 active（有能量、红石模式允许）。

稳态表现（A + C）：

- 终端/网格里同一物品的数量 = **2 ×** 实际数量；
- 网络根存储的 `getStored()` = **2 ×** 实际总量；
- 从网络取走 1 个实际物品，终端数量会**掉 2**（缓存追平后）；
- **不会凭空复制物品**：`extract/insert` 每次都走 live handler，底层只有一份；但数量语义被破坏，自动合成的 simulate 可能按翻倍数量通过，执行时底层不够 ⇒ 合成失败/超量请求。

不翻倍的情况（同样是代码路径结论）：

- 两个外部存储接在**不同网络** ⇒ 各自 1×；
- 两个面暴露的是**不相交**资源（真正的 sided inventory）⇒ 只有重叠部分被计两次，不相交部分正常；
- 只贴**一个**外部存储 ⇒ 1×（这是正常的用法）。

### 0.2 用户的反例「物品保险库」——概念要先拆开

**RS 2.0.9 里没有任何叫“保险库/Vault”的方块。**（`grep -i vault` 整个 jar 的条目：0 条；RS 的 `zh_cn.json` 里也没有“保险库”这个词。）

- 用户说的「物品保险库」是 **Create 的 Item Vault**（Create `zh_cn.json`：`block.create.item_vault = 物品保险库`）。
- RS 自己的对应物叫 **Storage Block（存储方块）**（`block.refinedstorage.1k_storage_block = 1k存储方块`）。

两者和外部存储的关系完全不同：

| 方块 | 它是什么 | 两个这样的方块 / 两个外部存储贴它，会不会翻倍 |
|---|---|---|
| RS **Storage Block**（1k/4k/16k/64k） | **自己就是 StorageProvider**，存储内容在自己的 `SerializableStorage` 里，按 UUID 存在 `StorageRepository`；**从不扫描邻居容器** | 两个 Storage Block 是**两个独立 UUID、两份独立库存** ⇒ 不会把同一份库存算两次（两份各 64 时合计 128 是合法的）。但这不是“外部存储去重”，是**根本没用外部存储机制** |
| Create **Item Vault**（物品保险库） | 普通容器，**六个面返回同一个组合库存**（`IItemHandler` 忽略 side） | 两个 RS 外部存储贴它**照样翻倍**；Create 自己“不翻倍”靠的是 Create 的 `InventoryIdentifier`，**RS 完全不读这个标识** |

所以：

- 如果用户指的是 **RS Storage Block**：它“不会算两遍”是对的，但它的机制和外部存储**不可类比**——它没有“读取邻居库存”这一步。
- 如果用户指的是 **Create Item Vault**：在 Create 自己的打包机/物流系统里确实有去重（`InventoryIdentifier`），但那是 **Create 的去重，不是 RS 的**；一旦用 RS 外部存储去读 Vault，RS 这边没有去重，**会翻倍**。

### 0.3 断言逐句核对

| 原断言 | 判定 | 说明 / 证据 |
|---|---|---|
| “单个外部存储只查它朝向的那一面” | ✅ 对 | `AbstractExternalStorageBlockEntity#loadStorage`：`direction = tryExtractDirection(state)`、`incomingDirection = direction.getOpposite()`、`sourcePosition = worldPosition.relative(direction)`（E1）；`CapabilityCacheImpl` 用这个 side 建 `BlockCapabilityCache`（E3） |
| “它不按源方块去重” | ✅ 对 | `loadStorage` 只特判 `InterfaceBlock`，没有任何“这个 pos 已被占用”的检查（E1/E2）；`StorageNetworkComponentImpl#onContainerAdded` 无条件 `addSource`（E7）；`CompositeStorageImpl.addSource` 是 `List.add`，连 provider 实例都不去重（E8） |
| “`CompositeStorageImpl.getStored()` 是按 source 逐个求和的” | ✅ 对 | `insertSources.stream().mapToLong(StorageView::getStored).sum()`（E8/E9，含字节码） |
| “`CompositeStorageImpl.getCapacity()`” | ❌ **2.0.9 里没有这个方法** | `javap -p CompositeStorageImpl` 的方法列表里没有 `getCapacity`；`StorageView` 只有 `getAll/getStored`；`getCapacity` 在 `LimitedStorage` 上（E18） |
| “两个外部存储贴同一底层库存 ⇒ 算两遍（数量翻倍）” | ✅ 对（A；C 未实测） | 每个外部存储各建一个 `ExposedExternalStorage` + `ExternalStorage`，各自 `buildCache()` 都从同一底层库存迭代；根 `CompositeStorageImpl` 是 `List` 求和 + 列表累加（E4–E10） |
| 用户：“物品保险库不会算两遍” | ⚠️ 取决于指哪个 | RS Storage Block：对，但不是同一机制；Create Item Vault：**错**，RS 外部存储会翻倍（E15） |

---

## 1. 证据方法（可复现）

### 1.1 用到的文件

| 文件 | 路径 | SHA-256 |
|---|---|---|
| RS 2.0.9 | `D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar` | `36f2fd60dd7b6077307ecdd5091033946c07caf061478ca4cc7740120c708ed9` |
| NeoForge 21.1.248 sources | `D:\gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\21.1.248\f0378c9b210dfc258170b6412a85fdffe4aea7fe\neoforge-21.1.248-sources.jar` | `b4b912457549ff586873ce78b39b08af96f11a0e3a54c6b77576c6a25907083f` |
| Create 6.0.10 sources | `D:\gradle\caches\modules-2\files-2.1\com.simibubi.create\create-1.21.1\6.0.10-280\23e1219501c0debfa0bb56c30ef8e0193341aae5\create-1.21.1-6.0.10-280-sources.jar` | `376de15ca5acf720106a075ca4eb2ef53e63e0e5d9ec93523a1abbdd0f9f0cb4` |

### 1.2 读取方式

```bash
# jar 内容用 Python zipfile 读，不解压到工作区
python - <<'PY'
import zipfile
z = zipfile.ZipFile(r"...\refinedstorage-neoforge-2.0.9.jar")
print([n for n in z.namelist() if 'externalstorage' in n.lower()])
PY

# 只把需要的 class 解到工作区外（D:\_rs209_tmp），再用 Vineflower 反编译
java -jar vineflower-1.10.1.jar -dgs=1 classes src

# 关键方法直接看字节码（JDK 21）
javap -p -c -cp refinedstorage-neoforge-2.0.9.jar \
  com.refinedmods.refinedstorage.api.storage.composite.CompositeStorageImpl
```

> 说明：本文所有 `*.java` 片段都是 **Vineflower 对 2.0.9 jar 的反编译结果**；行号是反编译文件的行号，不是上游源码行号。字节码片段是 `javap -p -c` 的原样输出。反编译只用于可读性，结论以字节码为准。

---

## 2. 一、RS 外部存储的注册与去重

### 2.1 `AbstractExternalStorageBlockEntity#loadStorage` 做了什么

**E1：反编译源码（2.0.9）**

```java
// AbstractExternalStorageBlockEntity#loadStorage(ServerLevel)
Direction direction = AbstractDirectionalBlock.tryExtractDirection(this.getBlockState());
LOGGER.debug("Loading storage for external storage with direction {} @ {}", direction, this.worldPosition);
if (direction != null) {
    Direction incomingDirection = direction.getOpposite();
    BlockPos sourcePosition = this.worldPosition.relative(direction);
    if (serverLevel.getBlockState(sourcePosition).getBlock() instanceof InterfaceBlock) {
        this.mainNetworkNode.initialize(new InterfaceProxyExternalStorageProvider(serverLevel, sourcePosition));
    } else {
        this.mainNetworkNode
           .initialize(
              new CompositeExternalStorageProvider(
                 RefinedStorageApi.INSTANCE
                    .getExternalStorageProviderFactories()
                    .stream()
                    .map(factory -> factory.create(serverLevel, sourcePosition, incomingDirection))
                    .toList()
              )
           );
    }
}
```

**E1b：字节码（关键偏移）**

```text
 28: aload_2
 29: invokevirtual #189  // Direction.getOpposite:()Lnet/minecraft/core/Direction;
 32: astore_3
 33: aload_0
 34: getfield      #150  // worldPosition:Lnet/minecraft/core/BlockPos;
 37: aload_2
 38: invokevirtual #195  // BlockPos.relative:(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;
 41: astore        4
 ...
 81: ... new CompositeExternalStorageProvider
 95: invokeinterface #228 // RefinedStorageApi.getExternalStorageProviderFactories:()Ljava/util/Collection;
105: aload_1
106: aload         4        // sourcePosition
108: aload_3               // incomingDirection
109: invokedynamic #238  // factory.create(level, sourcePosition, incomingDirection)
124: invokespecial #252 // CompositeExternalStorageProvider.<init>(List)
127: invokevirtual #217 // ExternalStorageNetworkNode.initialize(ExternalStorageProvider)
```

**E3：这个 side 最终落实到 capability 查询（`CapabilityCacheImpl`）**

```java
public CapabilityCacheImpl(ServerLevel level, BlockPos pos, Direction direction) {
   this.itemCapabilityCache = BlockCapabilityCache.create(ItemHandler.BLOCK, level, pos, direction);
   this.fluidCapabilityCache = BlockCapabilityCache.create(FluidHandler.BLOCK, level, pos, direction);
}

@Override
public Optional<IItemHandler> getItemHandler() {
   return Optional.ofNullable(this.itemCapabilityCache.getCapability());
}
```

⇒ 一个外部存储只拿到**一个面**的 `IItemHandler`；`pos` 是目标方块，`direction` 是目标方块朝向外部存储的那一面。

要点：

- 一个外部存储**只算一个目标方块**：`worldPosition.relative(direction)`，距离固定 1；
- 传给 capability 的 side 是 **`direction.getOpposite()`**，即“目标方块朝向外部存储的那一面”；
- **唯一的特判是 `InterfaceBlock`**（Interface 作为外部存储源）；
- 其余情况把 **所有** `ExternalStorageProviderFactory` 都调一遍（物品工厂、流体工厂、其他模组工厂），每个工厂拿到的上下文只有 `(level, pos, side)`，**没有“其他外部存储”的信息**。

**E2：外部存储 BE 的字段里没有“已占用 pos 注册表”**

反编译的 `AbstractExternalStorageBlockEntity` 字段只有：

```java
private final FilterWithFuzzyMode filter;
private final StorageConfigurationContainerImpl configContainer;
private final ExternalStorageTrackedStorageRepository trackedStorageRepository = ...;
private final ExternalStorageWorkRate workRate = ...;
private boolean initialized;
```

没有 static `Set<BlockPos>`、没有 `Map<BlockPos, ...>`、没有任何“这个库存已经被别的外部存储用了”的记录。

### 2.2 从 provider 到网络：会不会产生两个 `StorageProvider`？

**会——而且是必然。**

**(a) 每个外部存储 BE 各建一个 `ExternalStorageNetworkNode`**

**E5：反编译构造器**

```java
protected AbstractExternalStorageBlockEntity(BlockPos pos, BlockState state) {
   super(
      BlockEntities.INSTANCE.getExternalStorage(),
      pos,
      state,
      new ExternalStorageNetworkNode(
         Platform.INSTANCE.getConfig().getExternalStorage().getEnergyUsage(),
         System::currentTimeMillis)
   );
   ...
}
```

**(b) 每个 `ExternalStorageNetworkNode` 各建一个 `ExposedExternalStorage`**

**E4：反编译 + 字节码**

```java
public class ExternalStorageNetworkNode extends AbstractNetworkNode implements StorageProvider {
   private final ExposedExternalStorage storage;
   @Nullable
   private ExternalStorage externalStorage;

   public ExternalStorageNetworkNode(long energyUsage, LongSupplier clock) {
      ...
      this.storage = new ExposedExternalStorage(this.storageConfiguration, clock);
   }

   public void initialize(ExternalStorageProvider provider) {
      this.storage.tryClearDelegate();
      this.externalStorage = new ExternalStorage(provider, this.storage);
      ...
   }

   public Storage getStorage() {
      return this.storage;
   }
}
```

```text
// 构造器字节码
21: new           #22  // class .../ExposedExternalStorage
24: dup
...
34: putfield      #27  // Field storage:L.../ExposedExternalStorage;

// initialize 字节码
 8: new           #38  // class .../ExternalStorage
...
20: putfield      #43  // Field externalStorage:L.../ExternalStorage;
```

**(c) 每个 BE 只注册一个 network container，但不同 BE 的 container 互不相干**

**E6：`AbstractNetworkNodeContainerBlockEntity` 构造器**

```java
protected AbstractNetworkNodeContainerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, T mainNetworkNode) {
   super(type, pos, state);
   this.containers.addContainer(this.createMainContainer(mainNetworkNode));
   this.mainNetworkNode = mainNetworkNode;
}
```

`NetworkNodeContainerProviderImpl` 的 `containers` 是**每个 BE 自己的** `HashSet<InWorldNetworkNodeContainer>`；`InWorldNetworkNodeContainerImpl` 里存的是 `blockEntity` + `node`，没有跨 BE 的“同 pos 只留一个”逻辑。

**(d) 网络添加 container 时无条件把 storage 加进根存储**

**E7：`StorageNetworkComponentImpl#onContainerAdded` 反编译 + 字节码**

```java
public void onContainerAdded(NetworkNodeContainer container) {
   if (container.getNode() instanceof StorageProvider provider) {
      Storage storage = provider.getStorage();
      LOGGER.debug("Adding source {} from provider {}", storage, provider);
      this.addSource(storage);
   }
}
```

```text
12:  aload_1
13:  invokeinterface #7  // NetworkNodeContainer.getNode()
...
18:  instanceof    #13 // StorageProvider
...
24:  invokeinterface #15 // StorageProvider.getStorage()
...
40:  invokevirtual #33 // StorageNetworkComponentImpl.addSource(Storage)
```

没有任何 `if (alreadyContains(...)) return;`、没有按 `GlobalPos`/`BlockPos` 的集合、没有按 provider 的映射。

**结论：两个外部存储方块（两个不同 pos，贴着同一个库存的两个面）⇒ 两个 `ExternalStorageNetworkNode` ⇒ 两个 `ExposedExternalStorage` ⇒ 两个 source 被 `addSource` 进同一个 `CompositeStorageImpl`。**

> 补充：**同一个**外部存储方块重复 `loadStorage`（旋转、邻居更新、方块状态变化）**不会**变成两个 source：`ExternalStorageNetworkNode.storage` 是 `final` 的同一个 `ExposedExternalStorage`；`initialize` 先 `tryClearDelegate()`（`ExposedExternalStorage#clearDelegate` 会 `parent.onSourceRemovedFromChild(delegate)` 把旧缓存从根列表移除），再 `new ExternalStorage(...)`。翻倍需要**两个外部存储方块**（两个节点、两个 source 对象）。

### 2.3 `getStored()` 是不是逐个 source 相加？

**是。而且连“同一个 source 被加两次”都不会被拦。**

**E8：`CompositeStorageImpl` 字段与 `addSource`**

```java
private final List<Storage> insertSources = new ArrayList<>();
private final List<Storage> extractSources = new ArrayList<>();
private final MutableResourceList list;

public void addSource(Storage source) {
   this.insertSources.add(source);
   this.extractSources.add(source);
   this.sortSources();
   this.addContentOfSourceToList(source);
   this.parentComposites.forEach(parentComposite -> parentComposite.onSourceAddedToChild(source));
   if (source instanceof CompositeAwareChild compositeAwareChild) {
      compositeAwareChild.onAddedIntoComposite(this);
   }
}

private void addContentOfSourceToList(Storage source) {
   source.getAll().forEach(this.list::add);
}

public long getStored() {
   return this.insertSources.stream().mapToLong(StorageView::getStored).sum();
}
```

**E8b：`addSource` 字节码——`List.add`，没有 `contains`**

```text
47:  public void addSource(Storage);
49:   0: aload_0
50:   1: getfield      #10  // Field insertSources:Ljava/util/List;
51:   4: aload_1
52:   5: invokeinterface #45,  2 // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
...
57:  16: invokeinterface #45,  2 // List.add  (extractSources)
...
63:  28: invokevirtual #52  // addContentOfSourceToList(Storage)
```

**E8c：`getStored` 字节码——对 `insertSources` 逐个 `StorageView.getStored` 求和**

```text
338: public long getStored();
340:   0: aload_0
341:   1: getfield      #10  // Field insertSources:Ljava/util/List;
342:   4: invokeinterface #160 // List.stream()
343:   9: invokedynamic #164 // ToLongFunction (StorageView::getStored)
344:  14: invokeinterface #168 // Stream.mapToLong
345:  19: invokeinterface #174 // LongStream.sum
346:  24: lreturn
```

**E9：`RootStorageImpl#getStored` 只是转发**

```java
public long getStored() {
   return this.storage.getStored();
}
```

`StorageNetworkComponentImpl extends RootStorageImpl`，所以网络根存储的 `getStored()` 就是 `CompositeStorageImpl.getStored()`。

**E10：每个 source 自己的 `getStored` 也是“自己 cache 求和”**

```java
// ExternalStorage
public long getStored() {
   return this.getAll().stream().mapToLong(ResourceAmount::amount).sum();
}

private ResourceList buildCache() {
   MutableResourceList list = MutableResourceListImpl.create();
   this.provider.iterator().forEachRemaining(list::add);
   return list;
}

private void addToCache(ResourceKey resource, long amount) {
   this.cache.add(resource, amount);
   this.parents.forEach(parent -> parent.addToCache(resource, amount));
}
```

```text
299: public long getStored();
302:   1: invokevirtual #164 // getAll()
303:   4: invokeinterface #166 // Collection.stream()
305:  14: invokeinterface #176 // Stream.mapToLong
306:  19: invokeinterface #182 // LongStream.sum
```

**列表本身也会被累加两次**：`addSource` → `addContentOfSourceToList(source)` → `source.getAll().forEach(this.list::add)`；`MutableResourceListImpl.add` 在已有 key 上走 `Entry.increment(amount)`（`amount += amount`，字节码 `ladd`）。所以终端读到的 `getAll()` 也是 2×。

**E11：终端/网格确实读这个根列表**

```java
// GridWatcherRegistration#attach
rootStorage.getAll().forEach(resourceAmount ->
   this.watcher.onChanged(resourceAmount.resource(), resourceAmount.amount(), ...));

// StorageNetworkComponentImpl#getResources
return this.getAll().stream().map(...).toList();
```

⇒ **终端里同一物品的计数 = 根列表里的值 = 2×。**

### 2.4 小结：去重发生在哪一层？

**没有发生。** 具体说：

- 不是按 `BlockPos`（没有 source pos 注册表）；
- 不是按 `StorageProvider` 实例（`addSource` 是 `List`，重复加同一实例也会再加一次）；
- 不是按 `IItemHandler` 实例/哈希（`CapabilityCacheImpl` 只查 capability，不记录身份）；
- 不是按 `ResourceKey`（列表本来就是按 key 累加的）。

唯一类似“去重”的东西是 Interface 的自指保护（见 3.3），它**只认 Interface**，不适用于箱子/保险库/第三方容器。

---

## 3. 二、RS 自己有没有防重复的机制

### 3.1 全局搜索的结果

- 对 2.0.9 jar 的所有 `.class` 常量池做字符串扫描：`already` / `duplicate` / `dedup` / `in use` / `occupied` 等，**没有**任何与“外部存储/同一库存”相关的提示或异常；命中的都是 `TASK_ALREADY_RUNNING`、`Watcher is already registered`、`Storage ... already exists` 等无关逻辑。
- RS 的 mixin 配置只有 `AbstractContainerMenuMixin`、`AbstractGuiGraphicsMixin`、`AbstractCapeLayerMixin`，**没有**改存储聚合/外部存储的 mixin。
- `ExternalStorageProviderFactory#create(ServerLevel, BlockPos, Direction)` 的签名里没有“网络/其他外部存储”的上下文；即使某个工厂想全局去重，也拿不到 RS 的内部注册表（RS 也没有提供）。

### 3.2 `ExternalStorage` / `ItemHandlerExternalStorageProvider` 有没有身份/哈希去重？

**没有。**

- `ItemHandlerExternalStorageProvider` 的字段只有 `CapabilityCache`、`InsertableStorage`、`ExtractableStorage`；`iterator()` 直接 `capabilityCache.getItemAmountIterator()`。
- `ExternalStorage` 的字段只有 `provider`、`parents`（`Set<ParentComposite>`）、`cache`、`listener`；`cache` 是内容缓存，不是“库存身份表”。
- 两个 `ExternalStorage` 即使读到**同一个 `IItemHandler` 实例**，也各自维护自己的 cache，互不知道对方存在。

**E12：`ItemHandlerExternalStorageProvider` 反编译（2.0.9）**

```java
class ItemHandlerExternalStorageProvider implements ExternalStorageProvider {
   private final CapabilityCache capabilityCache;
   private final InsertableStorage insertTarget;
   private final ExtractableStorage extractTarget;

   public long extract(ResourceKey resource, long amount, Action action, Actor actor) {
      return this.extractTarget.extract(resource, amount, action, actor);
   }

   public long insert(ResourceKey resource, long amount, Action action, Actor actor) {
      return this.insertTarget.insert(resource, amount, action, actor);
   }

   @Override
   public Iterator<ResourceAmount> iterator() {
      return this.capabilityCache.getItemAmountIterator();
   }
}
```

### 3.3 唯一的“防自指”特判：Interface

RS 2.0.9 **确实**有一处 `hasSource(...)` 去重，但它只服务于 Interface 作为外部存储源的自指场景：

**E13：`InterfaceNetworkNode#isActingAsExternalStorage`**

```java
public boolean isActingAsExternalStorage() {
   return this.network == null
      ? false
      : ((StorageNetworkComponent)this.network.getComponent(StorageNetworkComponent.class))
         .hasSource(this::isStorageAnExternalStorageProviderThatReferencesMe);
}

private boolean isStorageAnExternalStorageProviderThatReferencesMe(Storage storage) {
   if (storage instanceof ExposedExternalStorage proxy
      && proxy.getExternalStorageProvider() instanceof InterfaceExternalStorageProvider interfaceProvider
      && interfaceProvider.getInterface() == this) {
      return true;
   }
   return false;
}
```

**E13b：`InterfaceExternalStorageProviderImpl` 的 guard**

```java
public long extract(ResourceKey resource, long amount, Action action, Actor actor) {
   if (this.isAnotherInterfaceActingAsExternalStorage(actor)) {
      return 0L;
   }
   ...
}
```

要点：

- 它检查的是“**这个 Interface 的 provider 是否已经被网络当成 source**”，**不是**“两个外部存储是否对准同一个库存”；
- `hasSource` 的谓词只匹配 `InterfaceExternalStorageProvider` + `interfaceProvider.getInterface() == this`；
- 对箱子、Create Vault、我们自己的容器代理器**完全不生效**。

`hasSource` 在整个 jar 里只被 `InterfaceNetworkNode` 使用（`RootStorageImpl` 提供实现，`InterfaceNetworkNode` 是唯一调用方）——这也反证了“没有通用的按源去重”。

### 3.4 RS 的 Storage Block（1k/4k/16k/64k）：根本不用外部存储机制

**E14：`StorageBlockBlockEntity` 是自给自足的 `StorageNetworkNode`**

```java
public class StorageBlockBlockEntity
   extends AbstractBaseNetworkNodeContainerBlockEntity<StorageNetworkNode>
   implements NetworkNodeExtendedMenuProvider<StorageBlockData>, StorageBlockEntity, Provider {
   ...
   public void setLevel(Level level) {
      super.setLevel(level);
      if (!level.isClientSide()) {
         if (this.storageId == null) {
            this.storageId = UUID.randomUUID();
            StorageRepository storageRepository = RefinedStorageApi.INSTANCE.getStorageRepository(level);
            SerializableStorage storage = this.storageBlockProvider.createStorage(storageRepository::markAsChanged);
            storageRepository.set(this.storageId, storage);
         }
         this.mainNetworkNode.setProvider(this);
      }
   }
}
```

```java
// ItemStorageBlockProvider
public SerializableStorage createStorage(Runnable listener) {
   return StorageTypes.ITEM.create(this.variant.getCapacity(), listener);
}
```

- 每个 Storage Block 有自己的 `UUID storageId`（NBT `"sid"`），`StorageRepository` 以这个 UUID 存它的 `SerializableStorage`；
- `StorageNetworkNode implements StorageProvider`，它的 `getStorage()` 返回 `ExposedStorage`，内部 source 是 `StateTrackedStorage`（包着那份 `SerializableStorage`）；
- **`StorageBlockBlockEntity` 从不调用 `loadStorage`/`ExternalStorageProviderFactory`/`BlockCapabilityCache`，也不读邻居方块。**（对 `StorageBlockBlockEntity.class` 与 `StorageNetworkNode.class` 的常量池直接搜索 `CapabilityCache` / `ExternalStorageProvider` / `BlockCapabilityCache` / `loadStorage`，命中数均为 0。）

所以两个 Storage Block 并排：两个不同 UUID、两份不同库存，合计是“1+1”，不是“同一份算两次”。**这解释了它为什么不会翻倍，但不能拿来类比外部存储。**

### 3.5 Create 的 Item Vault（物品保险库）：会翻倍；Create 自己的去重在别处

**E15a：Create 的 capability 注册忽略 side，所有面返回同一份组合库存**

```java
// ItemVaultBlockEntity#registerCapabilities (Create 6.0.10)
public static void registerCapabilities(RegisterCapabilitiesEvent event) {
   event.registerBlockEntity(
      Capabilities.ItemHandler.BLOCK,
      AllBlockEntityTypes.ITEM_VAULT.get(),
      (be, context) -> {                 // context（side）完全没用到
         be.initCapability();
         if (be.itemCapability == null)
            return null;
         return be.itemCapability.getCapability();
      }
   );
}
```

```java
// ItemVaultBlockEntity#initCapability
private void initCapability() {
   if (itemCapability != null && itemCapability.getCapability() != null)
      return;
   if (!isController()) {
      ItemVaultBlockEntity controllerBE = getControllerBE();
      if (controllerBE == null)
         return;
      controllerBE.initCapability();
      itemCapability = ICapabilityProvider.of(() -> {
         if (controllerBE.isRemoved()) return null;
         if (controllerBE.itemCapability == null) return null;
         return controllerBE.itemCapability.getCapability();
      });
      invId = controllerBE.invId;
      return;
   }

   // 控制器：把所有 vault 部件的 ItemStackHandler 合成一个 wrapper
   ...
   itemCapability = ICapabilityProvider.of(
      new VersionedInventoryWrapper(SameSizeCombinedInvWrapper.create(invs)));

   // 同时构造整个多方块的 InventoryIdentifier
   BoundingBox bounds = BoundingBox.fromCorners(this.worldPosition, farCorner);
   this.invId = new InventoryIdentifier.Bounds(bounds);
}
```

⇒ 无论外部存储贴在 Vault 的哪一面、甚至贴在不同部件方块上，拿到的都是**同一份组合库存**；两个 RS 外部存储 ⇒ 两个 provider 各迭代一遍 ⇒ 2×。

**E15b：Create 自己的“不翻倍”来自 `InventoryIdentifier`，与 RS 无关**

```java
// InventoryIdentifier（Create API）类注释
/**
 * Interface identifying an inventory spanning multiple block faces.
 * This is used to avoid multiple packagers on the same inventory requesting items from each other.
 */
```

```java
// AllInventoryIdentifiers#registerDefaults
InventoryIdentifier.REGISTRY.register(AllBlocks.ITEM_VAULT.get(), (level, state, face) -> {
   BlockEntity be = level.getBlockEntity(face.getPos());
   return be instanceof ItemVaultBlockEntity vault ? vault.getInvId() : null;
});
```

⇒ Create 的打包机/物流系统会用 `InventoryIdentifier` 判断“这两个面是不是同一个库存”，从而避免多个 packager 互相请求。**RS 的 `ExternalStorage` 路径完全不查询 `InventoryIdentifier`**（RS 是独立模组，连 Create 的类都不引用；`ExternalStorageProviderFactory` 的上下文只有 level/pos/side）。所以用户看到的“保险库不翻倍”只可能来自：

1. Create 自己的物流系统（`InventoryIdentifier`）；或
2. 只贴了**一个** RS 外部存储；或
3. 两个外部存储接在**不同网络**上（各自 1×）。

在“同一网络 + 两个 RS 外部存储”的条件下，Item Vault 和箱子一样会翻倍。

### 3.6 对照表

| 机制 | 有没有按“同一底层库存”去重？ | 证据 |
|---|---|---|
| RS 外部存储 → 网络根存储 | ❌ 没有 | E7/E8：`onContainerAdded` 无条件 `addSource`；`insertSources` 是 `List` |
| RS 根存储求和 | ❌ 没有 | E8c：`mapToLong(StorageView::getStored).sum()` |
| RS 外部存储 provider 包装 | ❌ 没有 | E12：只有 `CapabilityCache`，无身份表 |
| RS Interface 自指 | ✅ 有，但只认 `InterfaceExternalStorageProvider` + 同一个 Interface | E13 |
| RS Storage Block | 不适用（自己就是 storage，不读邻居） | E14 |
| Create Item Vault + Create 打包机 | ✅ 有，`InventoryIdentifier` | E15b |
| Create Item Vault + RS 外部存储 | ❌ 没有（RS 不读 `InventoryIdentifier`） | E15a/E15b + E7/E8 |

---

## 4. 三、用户可执行的验证方案（游戏内）

> 我这边跑不了客户端，下面是**用户可执行**的步骤。核心判据不是“看到 128”，而是“**取走 1 个，终端掉 2**”——这能排除“两个独立箱子各 64”的干扰。

### 4.1 准备（创造模式）

- RS 2.0.9；
- 一个 **Creative Controller**（`block.refinedstorage.creative_controller`，无限能量，避免能量干扰）；
- 一个 **Grid**（`block.refinedstorage.grid`）；
- 若干 **Cable**；
- 一个**普通箱子**（或木桶）；
- 2 个 **External Storage**（`block.refinedstorage.external_storage`）；
- 64 个同一种物品（例如石头），**只占 1 格**（不要分成两格，便于读数）。

### 4.2 步骤

1. 放 Controller，放 Grid，用 Cable 把 Grid 接到 Controller，确认 Grid 能打开、网络正常。
2. 放箱子，把 **64 个石头**放进箱子第 1 格（其余格空）。
3. 在箱子的**北面**放 External Storage #1：**直接右键箱子的北面**放置（若右键打开了箱子界面，潜行再右键）。RS 的 `DefaultDirectionType.FACE_CLICKED` 会把方块朝向设为 `clickedFace.getOpposite()`，即**自动朝向箱子**（`direction = SOUTH`，`worldPosition.relative(SOUTH)` 正好是箱子）。**不要贴在别的方块上再指望它转过来**——它只认自己朝向的那一格。
4. 在箱子的**东面**放 External Storage #2：同样直接右键箱子的东面。
5. 两个外部存储的 **filter 留空、access mode 保持默认（可读可写）、priority 保持 0**，避免过滤/优先级干扰读数。
6. 用 Cable 把 **#1 和 #2 都接到同一个 Controller/同一张网络**（注意：两个外部存储都要和网络连通；如果它们各自孤立，就不构成“同一网络”）。
7. 确认两个 External Storage 都处于 active（有能量/红石允许；创造控制器下应为 active）。

### 4.3 读数与判读

打开 Grid，搜索“石头”（或直接看物品列表）：

| 观察 | 含义 |
|---|---|
| 显示 **64** | 没有重复计数（或只有一个外部存储生效） |
| 显示 **128** | 两个外部存储都把同一批 64 个各算了一遍 ⇒ 翻倍 |

> 读数以**每个物品的数量**为准；RS 2.0.9 的根存储不聚合 capacity（见 E18），不要依赖“总存储量/容量条”来判断。

排错：如果只有 64，先确认 #1/#2 都连在同一网络、都 active、都朝向箱子（而不是朝向别的方块）；如果取 1 只掉 1，说明另一个外部存储看的不是同一批物品（例如你实际放了两个不同箱子，或它朝向错了）。

**决定性验证（排除“两个箱子各 64”的误判）：**

8. 从 Grid 里**取出 1 个石头**到玩家背包（不是从箱子里拿）。
9. 等 1–2 秒（让 `ExternalStorageWorkRate`/`detectChanges` 追平）。
10. 检查：
   - 箱子里实际剩 **63**；
   - Grid 里显示 **126**（不是 127，也不是 64）。

> 如果显示 126：证明网络把**同一份库存**算了两遍（取出 1 个，两个 source 的 cache 各减 1，列表减 2）。
> 如果显示 127：说明另一个 source 还没追平（瞬时），再等几秒；稳态应回到 126。

> **为什么是“取 1 掉 2”而不是掉 1？** 外部存储 source 走的是 `compositeExtract`：`ExposedExternalStorage#compositeExtract` 调用 `super.compositeExtract`（真正从箱子取 1 个，并触发 `ExternalStorage#detectChanges` 从根列表减 1），然后 `withoutNotifyingList()` 返回 `Amount(extracted, 0)`（`CompositeAwareChild$Amount`，`amountForList = 0`）。所以 `CompositeStorageImpl.extract` 不会在列表里再减一次；根列表的两次减少来自**两个 `ExternalStorage` 各自的 `detectChanges` → `removeFromCache` 通知**（各减 1）。

**反向验证（插入）：**

11. 往网络里放入 1 个石头（从玩家背包 shift-click 进 Grid），等 1–2 秒：
    - 箱子实际 **64**；
    - Grid 显示 **130**（128 + 2）。

### 4.4 对照实验（强烈建议做，防止误判）

| 实验 | 布置 | 预期 |
|---|---|---|
| 基线 | 只贴 **1 个** External Storage 在箱子北面 | 64；取 1 → 63（掉 1） |
| 双网络 | #1 接网络 A、#2 接网络 B（两张独立网络，各有一个 Grid） | 每张网络各显示 64；各自取 1 → 各掉 1 |
| 两个箱子 | #1 贴箱子 A（64）、#2 贴箱子 B（64），同一网络 | 显示 128；从 A 取 1 → 127（掉 1）——这是**合法**的 128，不是翻倍 |
| 拆一个 | 在“同一箱子两面”的布置下拆掉 #2 | 显示回到 64 |
| 双箱（double chest） | 大箱子两面各贴一个 | 同样翻倍（NeoForge 的 chest capability 不看 side，见 E16） |

### 4.5 Create Item Vault 变体

1. 放一个 Create **Item Vault**（物品保险库），放入 64 个石头（Vault 不能手动开 GUI，用漏斗/机械手/或先放进去再拼？最简单：用 Create 的漏斗/溜槽输入，或直接用 `/give` + 管道；也可以先用普通箱子验证，再换 Vault）。
2. 在 Vault 的**两个不同面**各贴一个 RS External Storage（或贴在**两个不同的 Vault 部件方块**上——由于 `initCapability` 会委托到 controller，仍然是同一份组合库存）。
3. 两个外部存储接同一网络。
4. 预期：显示 **128**；取 1 → **126**。

> 如果用户在 Create 的打包机/物流界面看到“不翻倍”，那是 Create 的 `InventoryIdentifier` 在起作用；换到 RS 的 Grid 读数就会看到翻倍。

**E16：NeoForge 对箱子的 capability 注册（side 被忽略）**

```java
// CapabilityHooks#registerVanillaProviders (NeoForge 21.1.248)
event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, blockEntity, side) -> {
    return new InvWrapper(ChestBlock.getContainer((ChestBlock) state.getBlock(), state, level, pos, true));
}, Blocks.CHEST, Blocks.TRAPPED_CHEST);
```

⇒ 同一个箱子的任何一面都包同一个 `Container`；两个外部存储读到的是同一批 slot。

---

## 5. 四、结论与建议

### 5.1 明确回答

**会算两遍。** 条件是：

- 两个 RS **外部存储**（不是 Storage Block）在同一网络；
- 它们对准**同一底层库存**（同一方块的多个面，或同一多方块库存的不同部件）；
- 两个面暴露的物品**有重叠**；
- 两个外部存储都 active。

**不是“所有多面接入都翻倍”**：如果两个面返回的是**不相交**的资源（真正按面分槽的机器），不相交部分只算一次，重叠部分才算两次。对“同一个 handler 在多个面返回同一批物品”的容器代理器/保险库/箱子，重叠 = 全部 ⇒ 2×。

**不会凭空复制物品**：取/放都走 live handler；两个 cache 最终会追平。坏的是**数量语义**（终端、自动合成 simulate、监控器、探测器等）。

### 5.2 我们的“容器代理器”界面要不要警告？

**要，但分情况：**

- **v1 如果只在一个面暴露内容物（方案 A：FRONT=内容物，其余面不暴露同一库存）**：从根上避免了“两个外部存储贴同一库存”，可以**不加强制警告**，但建议在 GUI/Jade 放一行说明（因为玩家仍可能用其他模组的多面包装/管道绕过）。
- **如果任何配置允许“同一底层库存出现在 ≥2 个面”**（包括 4 个内容物面、2 个对象面、可配置面）：**必须在 UI 明确警告**，并建议默认关闭多面同库存。
- **如果代理器同时是 RS 网络节点**（方案 B/C）：那是更严重的自指环，已有设计文档禁止；本报告的结论只是“外部存储侧也会翻倍”，不改变“不要同时做节点 + 暴露同一库存”的结论。

**建议的警告文案（中文，可直接用）：**

> ⚠ 同一库存被多个面暴露：RS 2.0.9 的外部存储**不按源方块去重**。两个 RS 外部存储贴在同一库存的不同面（即使返回的是同一个 handler）会把终端里的数量显示为**两倍**，且取走 1 个会掉 2 个。**每个底层库存只应接 1 个 RS 外部存储。**

Jade/短提示：

> 同一库存只接 1 个 RS 外部存储；多面接入会重复计数（RS 2.0.9 不去重）

**放在哪：**

1. 方块 GUI 的信息区（多面模式开启时常驻）；
2. Jade/TOP 的 tooltip（紧挨“内容物/对象”说明）；
3. 模组文档的“已知限制”；
4. 可选：`onNeighborChange`/放置时扫描 6 个邻居的 `ExternalStorageBlock`，若 ≥2 个朝向本方块，给放置者发一次聊天/动作栏警告。注意：这只是启发式（两个外部存储可能接在不同网络，实际不翻倍），所以文案用“可能/会”而不是“必然”，或者只在两者网络相同的时候警告（需要读 `ExternalStorageNetworkNode#getNetwork()`，服务端可做）。

### 5.3 “去重是按 pos 还是按 provider 实例？”——都不是

- 不是按 pos：没有 `(level, BlockPos)` 的占用表；
- 不是按 provider 实例：`CompositeStorageImpl.insertSources` 是 `List`，重复 `addSource` 同一个对象也会再加一次；
- 不是按 handler 实例/哈希：`ExternalStorage` 不记录 `IItemHandler` 身份；
- 唯一的“去重”是 Interface 自指（按 `InterfaceExternalStorageProvider#getInterface() == this`），以及 Create 自己的 `InventoryIdentifier`（RS 不读）。

所以我们的警告/文档**不能**写成“只要返回不同的 handler 实例就安全”——RS 不看 handler 身份；**也不能**写成“同一个 BlockPos 只会算一次”——RS 不看 pos。

### 5.4 对现有设计文档的修正

`docs/CONTAINER_PROXY_DESIGN.md` §4.4.3 有一句：

> “`addSource` 只是把 source 放进 `Set`（`CompositeStorageImpl.java:44-52`，按对象身份去重，不按源坐标）”

**这句需要修正**：2.0.9 的 `CompositeStorageImpl.insertSources` 是 **`List`**（`new ArrayList<>()`），`addSource` 直接 `List.add`，**连对象身份都不去重**。这不改变该文档的结论（仍然会翻倍），但会让“为什么翻倍”的解释更准确：不是“Set 按身份去重、只是不按 pos”，而是“**根本没有任何去重**”。

### 5.5 给实现/测试的提醒

- 测试时务必区分“同一网络”与“两张网络”；两张网络各自 1× 是正常的。
- 测试“取 1 掉 2”，不要只看初始 128（两个独立箱子也能得到 128）。
- 如果未来 RS 升级，先复查 `CompositeStorageImpl.addSource` 是否变成 `Set`/加了 pos 去重，以及 `ExternalStorageProviderFactory` 的签名是否变化；本报告只对 2.0.9 负责。

---

## 6. 五、未证实项（明确列出）

1. **游戏内运行未实测**：本环境无法启动客户端；2× 是由代码路径 + 字节码推出的（A），运行期表现（C）需要用户按 §4 验证。
2. **客户端 Grid 渲染未实测**：服务端数据路径已核实（`getAll` → `GridWatcherRegistration` → `onChanged`），但客户端实际显示的像素/数字未跑。
3. **网络 merge/split 时 `addSource` 是否会对同一 source 重复调用**：未逐路径追踪；正常单网络搭建不受影响。若存在重复调用，会进一步放大计数（因为 `List` 不去重）。
4. **`getCapacity` 路径未分析**：2.0.9 的 `CompositeStorageImpl` 没有 `getCapacity`；容量是 `LimitedStorage` 的概念（`StateTrackedStorage`/Storage Block），网络根存储不聚合容量。断言中的 `getCapacity()` 属于版本错位。
5. **外部存储方块能否放在全部 6 个面**：`DirectionalCableBlockShapes` 有 UP/DOWN 变体，理论上可以；未进游戏逐一验证。
6. **Create Item Vault 在 RS + Create 同时加载时的实际表现**：代码路径上 RS 不读 `InventoryIdentifier`，结论应为 2×；未进游戏实测。
7. **其他模组容器的分面语义**：如果某个机器每个面返回**不同**的 handler/槽位，重叠部分才 2×；未逐一验证。对“同一 handler 多面返回”的容器（箱子、Vault、我们的代理器）结论确定。
8. **双箱（double chest）的 wrapper 实例身份**：NeoForge 每次查询 `new InvWrapper(...)`，实例可能不同；但包的是同一个 `Container`，内容相同，不影响 2× 结论；未实测。
9. **用户实际看到“保险库不翻倍”的场景**：未复现；我们给出三种可能（Create `InventoryIdentifier` / 只接一个外部存储 / 两张网络），但不知道用户具体是哪种。

---

## 7. 附录：证据索引

| 编号 | 结论 | 证据位置 |
|---|---|---|
| E1 | 外部存储只查朝向面；`sourcePosition = relative(direction)`；只特判 Interface | `AbstractExternalStorageBlockEntity#loadStorage`（反编译 + `javap -c` 偏移 28–127） |
| E2 | 没有“已占用 pos”注册表 | `AbstractExternalStorageBlockEntity` 字段表（反编译） |
| E3 | capability 按 side 建缓存 | `CapabilityCacheImpl` 构造器：`BlockCapabilityCache.create(ItemHandler.BLOCK, level, pos, direction)` |
| E4 | 每个节点一个 `ExposedExternalStorage`、`initialize` 新建 `ExternalStorage` | `ExternalStorageNetworkNode` 构造器/`initialize`（反编译 + 字节码 `new #22` / `new #38`） |
| E5 | 每个外部存储 BE 新建一个节点 | `AbstractExternalStorageBlockEntity` 构造器 `new ExternalStorageNetworkNode(...)` |
| E6 | 每个 BE 一个 container；container 内是 BE+node | `AbstractNetworkNodeContainerBlockEntity` 构造器；`InWorldNetworkNodeContainerImpl`；`NetworkNodeContainerProviderImpl` |
| E7 | 网络添加 container 时无条件 `addSource` | `StorageNetworkComponentImpl#onContainerAdded`（反编译 + 字节码） |
| E8 | `insertSources` 是 `List`；`addSource` 是 `List.add`；`getStored` 是逐个求和 | `CompositeStorageImpl` 字段/`addSource`/`addContentOfSourceToList`/`getStored`（反编译 + `javap -c`） |
| E9 | 根存储转发 `getStored` | `RootStorageImpl#getStored` |
| E10 | 每个 source 的 `getStored` 是自己 cache 求和；`buildCache` 从 provider 迭代 | `ExternalStorage#getStored/buildCache/addToCache` |
| E11 | 终端/网格读根列表 | `GridWatcherRegistration#attach`、`StorageNetworkComponentImpl#getResources` |
| E12 | provider 只有 capability cache，无身份表 | `ItemHandlerExternalStorageProvider`（反编译） |
| E13 | 唯一去重是 Interface 自指 | `InterfaceNetworkNode#isActingAsExternalStorage`、`InterfaceExternalStorageProviderImpl#isAnotherInterfaceActingAsExternalStorage` |
| E14 | Storage Block 自己就是 StorageProvider，按 UUID 存 | `StorageBlockBlockEntity#setLevel`、`ItemStorageBlockProvider#createStorage`、`StorageNetworkNode#getStored` |
| E15 | Create Vault 六面同一库存；Create 用 `InventoryIdentifier` 去重；RS 不读 | `ItemVaultBlockEntity#registerCapabilities/initCapability/getInvId`、`InventoryIdentifier`、`AllInventoryIdentifiers` |
| E16 | NeoForge 箱子 capability 忽略 side | `CapabilityHooks#registerVanillaProviders`（NeoForge 21.1.248 sources） |
| E17 | “物品保险库”是 Create 的，不是 RS 的 | RS `zh_cn.json`（无“保险库”）；Create `zh_cn.json`（`block.create.item_vault` = 物品保险库） |
| E18 | 2.0.9 的根存储/Composite 没有 `getCapacity` | `javap -p CompositeStorageImpl`、`StorageView`、`RootStorage`、`StorageNetworkComponent`；`getCapacity` 仅在 `LimitedStorage` |

### 复现命令（摘要）

```bash
JAR="D:/gradle/caches/modules-2/files-2.1/com.refinedmods.refinedstorage/refinedstorage-neoforge/2.0.9/b7beb5d418dd7f850c767e7716f6bae8bd63da2f/refinedstorage-neoforge-2.0.9.jar"

# 方法列表（确认没有 getCapacity）
javap -p -cp "$JAR" com.refinedmods.refinedstorage.api.storage.composite.CompositeStorageImpl

# addSource / getStored 字节码
javap -p -c -cp "$JAR" com.refinedmods.refinedstorage.api.storage.composite.CompositeStorageImpl

# 外部存储注册路径
javap -p -c -cp "$JAR" com.refinedmods.refinedstorage.common.storage.externalstorage.AbstractExternalStorageBlockEntity
javap -p -c -cp "$JAR" com.refinedmods.refinedstorage.api.network.impl.node.externalstorage.ExternalStorageNetworkNode

# 网络存储组件
javap -p -c -cp "$JAR" com.refinedmods.refinedstorage.api.network.impl.storage.StorageNetworkComponentImpl

# 反编译（需要 Vineflower 1.10.1）
java -jar vineflower-1.10.1.jar -dgs=1 classes src
```

---

**一句话总结：** 断言基本成立——RS 2.0.9 的外部存储不按 pos、不按 provider、不按 handler 去重，根存储是 `List` 逐个求和，所以同一底层库存被两个外部存储读取时会被计两次；“物品保险库不翻倍”是因为用户说的是 Create 的 Vault（去重在 Create 的 `InventoryIdentifier`）或 RS 的 Storage Block（根本不走外部存储），两者都不能用来否定 RS 外部存储的翻倍行为。
