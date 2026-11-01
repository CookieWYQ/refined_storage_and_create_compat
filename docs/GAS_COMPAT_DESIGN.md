# 气体（Mekanism 化学系统）兼容方案：调研 + 设计

> **任务口径**：只调研 + 设计，**不改任何 Java 源码**、**不改 `run/`**。本轮**未写一行业务代码**，只新增本文件。
> **硬约束遵守**：读 jar 一律用 Python `zipfile` 直读，未解压到工作区；`javap` 用 `D:\java21\bin\javap.exe`；官方插件 jar 下载到**系统临时目录**（`%TEMP%\rscc_gasprobe\`），未进工作区、未进 `run/`。
> **每条结论都带证据**（`文件:行号` / `javap` 原文 / 字节码原文）。找不到证据的一律写「**未证实**」，不猜。

**一句话结论**：
> **RS 2.0.9 本体完全不支持气体（0 命中）；但 RS 的 `ResourceKey`／`ResourceType` 体系是类型无关的官方扩展点，而且「RS + 气体」已经被 RS 官方自己用一个独立插件做出来了（`refinedstorage_mekanism_integration`）。本工程**不需要**新建「气体存储节点/方块」——我们已有的「通用储存磁盘」（`rs_create_compat:universal`）的 `isAllowed()` 恒为 `true`、编解码走 `ResourceCodecs.CODEC` 的按类型 dispatch，**只要注册一个 `GasResourceType`，它立刻就能存气体，零改动**。真正必须自建的是「资源类型 + 网格映射 + 客户端显示」这三件，其中后两件**不做会 NPE**。**

---

## 0. 目录

| 节 | 内容 |
| -- | ---- |
| ① | 现状证实：本工程气体支持的真实状态（`isMekanismLoaded` 是否死代码、调用点计数） |
| ② | RS 2.0.9 对气体的支持情况（有/没有 + 证据） |
| ③ | RS 的 `ResourceKey`／`PlatformResourceKey`／`ResourceCodecs` 能否装下气体 |
| ④ | 本模组的流体链路（气体要照抄的那条路）+ 必须拆掉的「类型硬门」清单 |
| ⑤ | Mekanism 化学 API 清单（确切类名/包名/方法/方向/单位） |
| ⑥ | 既有「RS + 气体」实现（**官方插件**，含它的完整注册清单字节码原文） |
| ⑦ | 设计：统一资源模型 |
| ⑧ | 设计：气体缓存（取舍与代价） |
| ⑨ | 设计：气体的网络存储（**明确结论**） |
| ⑩ | 设计：界面表示（复用方案） |
| ⑪ | 风险与缓解 |
| ⑫ | 未证实 / 需要用户拍板 |
| ⑬ | 建议实施路线 |
| ⑭ | 取证方法与可复现路径 |

---

## ① 现状证实：本工程气体支持的真实状态

### 1.1 `isMekanismLoaded()` = **死代码**（已证实，不是「预留接口」）

**证据（定义处）**　`src/main/java/cretae/cookiewyq/rs_create_compat/support/OptionalDeps.java:104-107`

```java
104:    /** Mekanism 是否已加载（气体资源的可用性判断）。 */
105:    public static boolean isMekanismLoaded() {
106:        return ModList.get() != null && ModList.get().isLoaded(MOD_MEKANISM);
107:    }
```

**证据（调用点计数 = 0）**　扫描**全仓全部 `.java`**（11,071 个，排除 `build/`、`.gradle/`、`.git/`、`_audit_tmp/`、`tmp_rs_ref*/`、`run/`），逐符号精确匹配：

| 符号 | 命中总数 | **调用点** | 命中位置 |
| ---- | ---- | ---- | ---- |
| `isMekanismLoaded` | **1** | **0** | 仅 `OptionalDeps.java:105`（定义） |
| `MOD_MEKANISM` | 3 | — | `OptionalDeps.java:61`（常量定义）、`:106`（唯一使用） |
| `isEnchantmentIndustryLoaded` | 2 | 1 | `OptionalDeps.java:100`（定义）、`:185`（真调用） |
| `isCuriosLoaded` | 4 | 3 | `OptionalDeps.java:110`、`RsccCuriosTerminalSlot.java:101`、`:236`、`RsccTerminalLocator.java:153` |
| `isFtbUltimineLoaded` | 3 | 2 | `OptionalDeps.java:124`、`RsccUltimineIntegration.java:60`、`:105` |

**⇒ 结论：`isMekanismLoaded()` 是本工程**唯一**一个「定义了但全工程无人调用」的可选依赖探测函数。** 同文件另外三个同型函数都有真实调用点。所以它不是「预留接口」——它连**入口**都还没接上。

**再加一条独立证据（全工程零 Mekanism 类型引用）**：在 `src/` 全部 358 个 `.java` 里搜 `Chemical` → **0 命中**。

**再加一条（构建依赖）**：`build.gradle:137-207` 的 `dependencies` 块依次为 Create / Ponder / Flywheel / Registrate / RS / Quartz Arsenal / JEI / Curios / apiguardian / Jade /（注释掉的 FTB Ultimine）—— **没有任何 `mekanism`**。

### 1.2 「归流缓存仓的气体来源」是**空方法预留**（而且注释自己就承认了原因）

**证据**　`block/entity/CollectionCacheBlockEntity.java:1591-1598`

```java
1591:    /**
1592:     * 气体来源入口（与其它三种共用一个入口与缓存/回流管线）。
1593:     * <p>RS 2.0 的资源体系当前没有气体资源类型（{@code ResourceType}），因此这里暂不产生具体来源；
1594:     * 后续接入气体资源类型后在本方法内实现即可，缓存与回流逻辑无需改动。</p>
1595:     */
1596:    private void collectGas(final Level level) {
1597:        // 预留入口：见方法注释
1598:    }
```

**调用点（唯一一处，且被开关挡住）**　`CollectionCacheBlockEntity.java:1288-1289`

```java
1288:            if (isAbsorbEnabled(AbsorbType.GAS)) {
1289:                collectGas(level);
```

`support/AbsorbType.java:11,16-20`

```java
11: *     <li>{@link #GAS}：气体（入口已预留，RS 资源体系暂无气体类型）；</li>
16: public enum AbsorbType {
17:     ITEM,
18:     FLUID,
19:     GAS,
20:     EXPERIENCE;
```

⇒ 这是一个**真正的空壳**（方法体只有一行注释），`AbsorbType.GAS` 开关目前打开也**什么都不会发生**。

### 1.3 「气体以流体标识承载」只是**注释口径**，真实承载永远是 `FluidResource`

代码里成片出现「（含气体，Mekanism 化学品同样以流体标识承载）」这类注释：

| 位置 | 内容 |
| ---- | ---- |
| `support/KeeperSlotConfig.java:28-29` | `/** 标记形态：气体（Mekanism 化学品同样以流体标识承载）。 */ public static final int FORM_GAS = 2;` |
| `support/KeeperSlotConfig.java:41-43` | `/** 是否为流体 / 气体标记。 */ ... return form == FORM_FLUID \|\| form == FORM_GAS;` |
| `client/widget/GhostMarkerRenderer.java:37-38` | 「Ghost（标记）槽的『物品 / 流体 / 气体』绘制工具……（含气体，Mekanism 化学品同样以流体标识承载）」 |
| `client/QuantityKeeperJeiPlugin.java:34,43` | 「支持物品与流体（**含以流体承载的气体**）」 |

**但实际承载对象是流体**：

- `QuantityKeeperBlockEntity.java:295`：`this.markerForm = form == FORM_GAS ? FORM_GAS : FORM_FLUID;` —— `FORM_GAS` 只影响**一个 int 标记**
- 真正构造资源键时（`QuantityKeeperBlockEntity.java:359`、`:497-498`、`:799`；`AdvancedQuantityKeeperBlockEntity.java:383`、`:550-551`、`:956`；`CollectionCacheBlockEntity.java:2086`）**一律是** `new FluidResource(fluid, ...)`
- `QuantityKeeperBlockEntity.java:265`：「标记是否为流体（含气体）」—— 即 `FORM_GAS` 与 `FORM_FLUID` 走**同一条**流体路径

**⇒ 证实（比「预留」更弱）**：本工程气体支持的现状 = **一个未使用的 `isMekanismLoaded()` + 一个空方法 `collectGas()` + 一堆 `FORM_GAS` 枚举值与注释**。**没有任何一行代码真的处理过气体。**

### 1.4 ⚠️ 一个必须先纠正的事实：**「气体以流体标识承载」这条路在 Mekanism 上不成立**

这条注释口径在 Mekanism 语境下是**错的**，理由要用数据说话：

- Mekanism 的化学品（`Chemical`）与流体（`Fluid`）是**两套独立注册表**：`MekanismAPI.CHEMICAL_REGISTRY`（`MekanismAPI.java:120-121`，`DefaultedRegistry<Chemical>`）vs MC 的 `BuiltInRegistries.FLUID`。
- Mekanism 有 **30+ 个化学品**（`MekanismChemicals.java:24-57`：bio / fungi / tin / gold / refined_obsidian / diamond / redstone / carbon / hydrogen / oxygen / steam / **water_vapor** / chlorine / sulfur_dioxide / sulfur_trioxide / sulfuric_acid / hydrogen_chloride / hydrofluoric_acid / uranium_oxide / uranium_hexafluoride / ethene / sodium / superheated_sodium / brine / lithium / osmium / fissile_fuel / nuclear_waste / spent_nuclear_waste / plutonium / polonium / antimatter，另有每种矿的 slurry）
- 但**只有 14 个化学品有同名流体**（`MekanismFluids.java:23-38`，走 `registerLiquidChemical(ChemicalConstants)`，而 `FluidDeferredRegister.registerLiquidChemical` 用 `constants.getName()` 作注册名 —— `FluidDeferredRegister.java:85-94`、`:121-131`；`ChemicalConstants.java:11-29` 列了这 14 个）：

  `hydrogen, oxygen, chlorine, sulfur_dioxide, sulfur_trioxide, sulfuric_acid, hydrogen_chloride, hydrofluoric_acid, ethene, sodium, superheated_sodium, lithium, uranium_oxide, uranium_hexafluoride`

**⇒ `water_vapor`、`brine`※、`osmium`、`fissile_fuel`、`nuclear_waste`、`spent_nuclear_waste`、`plutonium`、`polonium`、`antimatter`、全部 infuse type（bio/fungi/tin/…）、全部 slurry、`steam`（化学品侧有，但流体注册名相同 —— 这条算同名）都没有可对应的「同名流体」。**（※`brine` 在 `MekanismFluids.java:37` 与 `MekanismChemicals.java:49` 都是 `brine`，算同名；`steam` 在 `:40` 与 `:35` 都是 `steam`，算同名 —— 即便把它们都算上，也**至少有一半以上**的化学品没有同名流体。）

⇒ **本设计的口径必须改**：`FORM_GAS` 不能再当「流体的一种」。正确做法是**注册第三种 RS 资源类型**（见 ③⑦）。但这条改口径会波及 **53 处类型判断**，清单见 ④.5。

---

## ② RS 2.0.9 对气体的支持情况：**完全没有**（0 命中）

### 2.1 证据 1：RS 2.0.9 的 jar 里搜不到任何 chemical / gas / mekanism

Python `zipfile` 直读（未解压）：

```python
p = r'...\refinedstorage-neoforge\2.0.9\<sha1>\refinedstorage-neoforge-2.0.9.jar'
z = zipfile.ZipFile(p); names = z.namelist()
print('total entries', len(names))          # → 4542
for pat in ['chemical','Chemical','Mekanism','mekanism','/gas','Gas']:
    print(pat, len([n for n in names if pat in n]))
```

实际输出：

```
total entries 4542
--- chemical 0
--- Chemical 0
--- Mekanism 0
--- mekanism 0
--- /gas 0
--- Gas 0
--- resource/ResourceType 2
    com/refinedmods/refinedstorage/common/api/support/resource/ResourceType.class
    com/refinedmods/refinedstorage/common/support/resource/ResourceTypes.class
--- ResourceType 5
    com/refinedmods/refinedstorage/common/api/support/resource/ResourceType.class
    com/refinedmods/refinedstorage/common/support/resource/ResourceTypes.class
    com/refinedmods/refinedstorage/common/support/resource/ItemResourceType.class
    com/refinedmods/refinedstorage/common/support/resource/FluidResourceType.class
    com/refinedmods/refinedstorage/common/grid/screen/ResourceTypeSideButtonWidget.class
```

### 2.2 证据 2：内置资源类型只有两个

```java
// local_src/external/RefinedStorage/refinedstorage-common/src/main/java/com/refinedmods/refinedstorage/common/support/resource/ResourceTypes.java:5-10
public final class ResourceTypes {
    public static final ResourceType ITEM = new ItemResourceType();
    public static final ResourceType FLUID = new FluidResourceType();
```

### 2.3 证据 3：`RefinedStorageApi` 的 javap 里没有任何化学入口

`javap` on `refinedstorage-neoforge-2.0.9.jar`：

```
public abstract ...RefinedStorageApi getItemResourceFactory();
public abstract ...RefinedStorageApi getFluidResourceFactory();
public abstract ...RefinedStorageApi getItemStorageType();
public abstract ...RefinedStorageApi getFluidStorageType();
public abstract ...RefinedStorageApi getAlternativeResourceFactories();
```

⇒ 只有「物品 / 流体」两个具名入口 + 一个通用的 `getAlternativeResourceFactories()`（**这就是给第三方挂第三种类型用的口子**）。

### 2.4 证据 4：**官方另有插件**（重要 —— 这是「RS + 气体」的权威答案）

RS 官方（Refined Mods / raoulvdberge）**另发了一个独立插件**：

- Modrinth：project id `QHMDf0Sd`，slug `refined-storage-mekanism-integration`，标题 **Refined Storage - Mekanism Integration**，MIT，`game_versions=["1.21.1"]`，`loaders=["neoforge"]`（证据：`https://api.modrinth.com/v2/project/refined-storage-mekanism-integration`）
- 其 raw body 原文：「This optional integration adds support for Mekanism chemicals in various Refined Storage components, allowing **storage, transfer, viewing, and autocrafting** with chemicals.」
- 其依赖：Modrinth project `KDvYkUg3`（= **Refined Storage**）+ `Ce6I4WUE`（= **Mekanism**），均 `required`
- 官方 wiki 页：`https://refinedmods.com/refined-storage/addons/mekanism.html`（在「Addons → Mekanism integration」下），内容与 Modrinth body 一致，并明确列出：**Chemical storage（64B/256B/1024B/8192B/Creative 的盘与方块）/ Importer / Exporter / External Storage / Storage Monitor / Autocrafting**

**⇒ 结论（本节的答案）**：
**RS 2.0.9 本体不支持气体；RS 官方用「一个独立插件 + 往 `getResourceTypeRegistry()` 注册第三个 `ResourceType` + 往 `getStorageTypeRegistry()` 注册第三个 `StorageType`」的方式实现。** 这同时证明了 ③ 的结论：**这套体系是被设计成可扩展的**。详见 ⑥。

---

## ③ RS 的 `ResourceKey`／`PlatformResourceKey`／`ResourceCodecs` 能否装下气体：**能，这就是官方扩展点**

> 说明：本节的源码引用来自 `local_src/external/RefinedStorage`（分支 **`release/2.0.10`**，git `0f89121b25936f8a65821683ce2d52497df50af0`）。**每一条 API 结论我都用 2.0.9 的 `javap` 逐字复核过**（下方「javap 2.0.9」块），因此结论对 2.0.9 成立。

### 3.1 `ResourceKey` 是**空接口**（纯标记）

```java
// .../refinedstorage-resource-api/.../api/resource/ResourceKey.java:5-7
@API(status = API.Status.STABLE, since = "2.0.0-milestone.3.4")
public interface ResourceKey {
}
```

`javap` 2.0.9：

```
public interface com.refinedmods.refinedstorage.api.resource.ResourceKey {
}
```

### 3.2 `PlatformResourceKey` = 4 个方法

```java
// .../refinedstorage-common-api/.../common/api/support/resource/PlatformResourceKey.java:9-18
@API(status = API.Status.STABLE, since = "2.0.0-milestone.3.4")
public interface PlatformResourceKey extends ResourceKey {
    long getInterfaceExportLimit();
    long getProcessingPatternLimit();
    List<ResourceTag> getTags();
    ResourceType getResourceType();
}
```

`javap` 2.0.9：**逐字一致**（4 个 abstract 方法）。

### 3.3 `ResourceType` = 8 个方法（新资源类型的全部契约）

```java
// .../refinedstorage-common-api/.../common/api/support/resource/ResourceType.java:14-31
@API(status = API.Status.STABLE, since = "2.0.0-milestone.3.4")
public interface ResourceType {
    MapCodec<PlatformResourceKey> getMapCodec();
    StreamCodec<RegistryFriendlyByteBuf, PlatformResourceKey> getStreamCodec();
    MutableComponent getTitle();
    ResourceLocation getSprite();
    long normalizeAmount(double amount);
    double getDisplayAmount(long amount);
    long getInterfaceExportLimit();
    GridOperations createGridOperations(RootStorage rootStorage, Actor actor);
}
```

`javap` 2.0.9：**逐字一致**。

### 3.4 ⭐ `ResourceCodecs.STREAM_CODEC` **是类型无关的**（这是「能不能再挂一种」的核心证据）

```java
// .../refinedstorage-common/.../common/support/resource/ResourceCodecs.java:36-64
36:    public static final Codec<PlatformResourceKey> CODEC = RefinedStorageApi.INSTANCE.getResourceTypeRegistry()
37:        .codec()
38:        .dispatch(PlatformResourceKey::getResourceType, ResourceType::getMapCodec);
...
46:    public static final StreamCodec<RegistryFriendlyByteBuf, PlatformResourceKey> STREAM_CODEC = new StreamCodec<>() {
47:        @Override
48:        public PlatformResourceKey decode(final RegistryFriendlyByteBuf buf) {
49:            final ResourceLocation id = buf.readResourceLocation();
50:            final ResourceType resourceType = RefinedStorageApi.INSTANCE.getResourceTypeRegistry()
51:                .get(id)
52:                .orElseThrow();
53:            return resourceType.getStreamCodec().decode(buf);
54:        }
55:
56:        @Override
57:        public void encode(final RegistryFriendlyByteBuf buf, final PlatformResourceKey resourceKey) {
58:            final ResourceType resourceType = resourceKey.getResourceType();
59:            final ResourceLocation id = RefinedStorageApi.INSTANCE.getResourceTypeRegistry().getId(resourceType)
60:                .orElseThrow();
61:            buf.writeResourceLocation(id);
62:            resourceType.getStreamCodec().encode(buf, resourceKey);
63:        }
64:    };
```

**读法**：`STREAM_CODEC` 本身**不认识任何具体类型** —— 它只写/读一个「资源类型 id」，剩下的**全权委托**给该类型的 `getStreamCodec()`。`CODEC` 同理（`dispatch` 到 `ResourceType::getMapCodec`）。

**⇒ 结论：`ResourceCodecs.STREAM_CODEC` / `CODEC` 完全类型无关，可以再挂任意多种资源类型 —— 前提是该 `ResourceType` 已注册进 `getResourceTypeRegistry()`。这就是「我们之前用过它承载物品+流体」的正确解释：我们用的不是一个「两类型专用」编解码器，而是一个「按注册表 dispatch 的通用编解码器」。**

**本工程已经在用它的证据**：

| 位置 | 用法 |
| ---- | ---- |
| `storage/UniversalStorageType.java:19,33` | `import ...ResourceCodecs;` / `private static final Codec<PlatformResourceKey> RESOURCE_CODEC = ResourceCodecs.CODEC;` |
| `network/SyncAssemblyAlertsPacket.java:5,156,180-181` | `ResourceCodecs.STREAM_CODEC.encode/decode` |

### 3.5 注册入口 = `PlatformRegistry`

`javap` 2.0.9：

```
public interface com.refinedmods.refinedstorage.common.api.support.registry.PlatformRegistry<T> {
  public abstract void register(net.minecraft.resources.ResourceLocation, T);
  public abstract java.util.Optional<net.minecraft.resources.ResourceLocation> getId(T);
  public abstract java.util.Optional<T> get(net.minecraft.resources.ResourceLocation);
  public abstract java.util.List<T> getAll();
  public abstract T nextOrNullIfLast(T);
  public abstract com.mojang.serialization.Codec<T> codec();
  public abstract net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> streamCodec();
}
public abstract ...RefinedStorageApi getResourceTypeRegistry();
public abstract ...RefinedStorageApi getStorageTypeRegistry();
public abstract void addResourceFactory(...ResourceFactory);
public abstract java.util.Set<...ResourceFactory> getAlternativeResourceFactories();
```

### 3.6 ⚠️ 陷阱：`ItemResourceType` / `FluidResourceType` 是 **package-private**，不能继承

`javap` 2.0.9 原文（注意**没有 `public`**）：

```
Compiled from "ItemResourceType.java"
class com.refinedmods.refinedstorage.common.support.resource.ItemResourceType implements ...ResourceType {
  com.refinedmods.refinedstorage.common.support.resource.ItemResourceType();
  ...
Compiled from "FluidResourceType.java"
class com.refinedmods.refinedstorage.common.support.resource.FluidResourceType implements ...ResourceType {
  com.refinedmods.refinedstorage.common.support.resource.FluidResourceType();
```

⇒ **只能自己从头实现 `ResourceType`**（不能像 `AbstractFluidGridResourceRepositoryMapper` 那样「继承现成的流体实现」）。唯一可继承的公开基类是 `AbstractGridResource<T>`（`GridResource` 的抽象基类，见 ⑩）。

### 3.7 ⚠️ 陷阱：`getTypeRegistryName()` 已被标记移除

Mekanism 侧（见 ⑤）的 `ChemicalStack.getTypeRegistryName()` 已经 `@Deprecated(forRemoval = true, since = "10.7.11")` —— 不要用它。

---

## ④ 本模组的流体链路（气体要照抄的那条路）+ 必须拆掉的「类型硬门」

### 4.1 完整链路（逐环节 + 证据）

| # | 环节 | 实现 | 证据 |
| - | ---- | ---- | ---- |
| 1 | **本机缓存** | `MultiFluidCache`：条目键 = (流体注册名 + 数据组件 NBT)，**总容量有上限、种类无上限**，容量守恒 | `support/MultiFluidCache.java:19-30`（类注释与 `Entry` record）、`:25`（`record Entry(ResourceLocation id, CompoundTag nbt, long amount)`）、`:79-125`（`insert`/`extract` 都按 mB 累加/扣减）、`:133-166`（`save`/`load`，读档时按序截断保证 `stored <= capacity`） |
| 2 | **单条目对外能力** | `FluidCacheEntryHandler implements IFluidHandler`：单罐、只读+可抽，`fill` 一律拒绝（返回 0） | `support/FluidCacheEntryHandler.java:13-16`（类注释）、`:18-41` |
| 3 | **写回网络（进）** | `CollectionCacheBlockEntity.flushFluidCacheToNetwork` —— **关键 3 行** | `block/entity/CollectionCacheBlockEntity.java:2052-2097`；其中 `:2086` `final FluidResource resource = new FluidResource(fluid, MarkerEntry.decodeComponents(entry.nbt(), registries));`、`:2087` `final long inserted = storage.insert(resource, attempt, Action.EXECUTE, Actor.EMPTY);`、`:2088-2090` `if (inserted <= 0) { continue; }`；方法 Javadoc `:2051`「网络没有对应资源存储时留在缓存里，不丢资源」 |
| 4 | **从网络取出给机器** | `QuantityKeeperBlockEntity.java:497-498`、`:799`；`AdvancedQuantityKeeperBlockEntity.java:550-551`、`:956`；`SequenceExecutionChamberBlockEntity.java:10095` `pullFluid(...)`、`:10202` `storage.insert(new FluidResource(...), ...)` | 见左 |
| 5 | **自动合成仓内部流体** | `RsccUnboundedFluidStorage extends FluidTank`：每种流体一个罐、同流体同组件自动合并；旧的「单罐字段」被替换为列表 | `support/RsccUnboundedFluidStorage.java:13-24`（类注释）、`:25-31`、`:36-55` |
| 6 | **执行舱物品视图** | `RsccChamberItemStorage implements IItemHandler`：「内部存储 + 网络内共享的中间产物缓存池」统一成一个按槽位遍历的视图 | `support/RsccChamberItemStorage.java:19-38`、`:39-41` |
| 7 | **共享缓存池** | `RsccSharedCache`：把网络里所有「中间产物缓存仓」的磁盘合并成一个池；靠 RS 网络图枚举发现（不另写连接判定） | `support/RsccSharedCache.java:28-56` |
| 8 | **中间产物主动迁移** | `RsccIntermediateFlow`：extract → insert 回**同一个**网络权威存储，靠插入优先级 + 负抽出优先级达成收敛；按**实际抽出量**记账「不丢不复制」 | `support/RsccIntermediateFlow.java:17-47`（类注释）、`:46-47` |
| 9 | **账本** | `RsccFlowLedger`：四类流动（离开网络 / 进入网络 / 推向机器 / 从机器取回）逐资源累加，理想 `未对齐 == 0` | `support/RsccFlowLedger.java:16-44`（类注释）、`:45-56` |

### 4.2 ⭐ 好消息：真正写网络的那一句**完全类型无关**

`javap` 2.0.9：

```
public interface com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent
        extends ...NetworkComponent, ...storage.root.RootStorage {
  public abstract java.util.List<...TrackedResourceAmount> getResources(java.lang.Class<? extends ...Actor>);
  public abstract boolean contains(...Storage);
}
public interface ...storage.root.RootStorage extends ...storage.Storage, ...tracked.TrackedStorage {
  public abstract long get(...ResourceKey);
  public abstract boolean contains(...ResourceKey);
  ...
}
```

而 `Storage`（`insert/extract/getAmount`）的入参类型就是 `ResourceKey`。⇒ **`storage.insert(ResourceKey, long, Action, Actor)` 对气体同样适用**，与物品/流体共用同一条语句（`CollectionCacheBlockEntity.java:2087`）。

### 4.3 ⭐ 好消息：本仓的「通用储存磁盘」**已经为气体准备好了**（这是⑨结论的基础）

`storage/UniversalStorageType.java:80-82`

```java
80:    @Override
81:    public boolean isAllowed(final ResourceKey resource) {
82:        return true; // 接受任意类型资源（物品、流体、气体……）
```

`storage/UniversalLimitedStorage.java:155-160`（容量换算 —— 非物品一律按「1000 单位 = 1 桶 = 1 物品位」）

```java
155:    public static long capacityCost(final ResourceKey resource, final long amount) {
156:        if (resource instanceof ItemResource) {
157:            return amount; // 物品：1 件 = 1 物品位
158:        }
159:        return Math.max(1, (amount + 999) / 1000); // 流体/气体：1 桶（1000 单位）= 1 物品位
160:    }
```

`storage/UniversalLimitedStorage.java:145-150`（「同类」判定用 `getResourceType()`，**类型无关**）

```java
145:    private static boolean isSameCategory(final ResourceKey a, final ResourceKey b) {
146:        if (a instanceof PlatformResourceKey platformA && b instanceof PlatformResourceKey platformB) {
147:            return platformA.getResourceType().equals(platformB.getResourceType());
148:        }
149:        return a instanceof ItemResource == b instanceof ItemResource;
150:    }
```

`storage/UniversalStorageType.java:36-38` 的 `getMapCodec` 里，资源列表用 `RESOURCE_CODEC = ResourceCodecs.CODEC`（`:33`），并包了一层**容错列表编解码**（单条 decode 失败只跳过并 WARN，`ErrorTolerantListCodec`，`:127-164`）。

### 4.4 注册时机（气体注册必须照这个时机来）

`RS_Create_Compat.java:592-597`

```java
592:    private void commonSetup(final FMLCommonSetupEvent event) {
593:        // 注册通用存储类型（必须在任何磁盘数据序列化之前注册，供 RS 存档编解码使用）
594:        RefinedStorageApi.INSTANCE.getStorageTypeRegistry().register(
595:            ResourceLocation.fromNamespaceAndPath(MODID, "universal"),
596:            UniversalStorageType.INSTANCE
597:        );
```

RS 本体自己也是同一时机一次性注册完（`local_src/external/RefinedStorage/.../common/AbstractModInitializer.java:184-198` 的 `initializePlatformApi()` → `registerStorageTypes()` `:200-209` → `registerResourceTypes()` `:211-220` → `registerAdditionalResourceFactories()` `:222-224` → `registerResourceContainerInsertStrategies()` `:226-228`）。

### 4.5 ⚠️ 必须拆掉的「类型硬门」清单（气体要进来，这些地方会静默丢弃或误判）

在 `src/` 全部 `.java` 里精确匹配 `instanceof ItemResource` / `instanceof FluidResource`：

**合计 53 处命中，分布在 12 个文件**（`instanceof ItemResource` 38 处 + `instanceof FluidResource` 15 处）：

| 文件 | 命中数 |
| ---- | ---- |
| `block/entity/SequenceExecutionChamberBlockEntity.java` | 18 |
| `support/AssemblyWatchdog.java` | 10 |
| `support/RsccChamberExportStrategy.java` | 7 |
| `mixin/AutocrafterStorageMixin.java` | 4 |
| `storage/UniversalLimitedStorage.java` | 3 |
| `support/RsccChamberImportStrategy.java` | 2 |
| `mixin/FilterTagMixin.java` | 2 |
| `support/RsccIntermediateFlow.java` | 2 |
| `support/RsccIntermediateOnlyStorage.java` | 1 |
| `support/RsccChamberItemStorage.java` | 1 |
| `client/PreviewIntermediateHint.java` | 1 |
| `block/entity/SequenceAssemblyExecutorBlockEntity.java` | 1 |

**已逐条确认「会看到气体并静默跳过」的典型**（我只逐条读了这一处，其余**需逐处复核** —— 列入 ⑫ 未证实 5）：

```java
// block/entity/SequenceAssemblyExecutorBlockEntity.java:159-162
159:            final ResourceKey resource = amount.resource();
160:            if (!(resource instanceof ItemResource) && !(resource instanceof FluidResource)) {
161:                continue; // 其它资源类型不适用于本机
162:            }
```

同类模式还有 `mixin/AutocrafterStorageMixin.java:170-175`（`rscc$canAcceptAll` 依次判 `ItemResource` / `FluidResource`）、`:195-201`（`rscc$insertAll`，流体侧直接 `rscc$outputTank.fill(new FluidStack(fluidResource.fluid(), (int) amount), ...)`）。

**⇒ 设计含义**：`RsccChamberItemStorage.java:121` 的注释「只暴露物品条目：通用磁盘可能同时存着流体 / 气体，那不是『中间产物缓存』的一部分」说明**至少有一处已经显式考虑了气体**并选择了「不暴露」。这类「显式跳过」是**安全**的（不会崩）；但**「只判 FluidResource 就当作流体处理」的地方必须复核**，否则气体可能被当成流体塞进 `IFluidHandler`。

---

## ⑤ Mekanism 化学 API 清单（确切类名 / 包名 / 方法 / 方向 / 单位）

> **本机源码**：`local_src/external/Mekanism`（git 分支 `1.21.x`，HEAD `11162452affe7b17b25cde251308c9d047c42e87`，2026-06-27；`gradle.properties`：`mod_version=10.7.19`、`minecraft_version=1.21.1`、`neo_version=21.1.200`、`minecraft_version_range=[1.21.1]`）

### 5.1 能力键（capability）—— 确切全限定名

```java
// src/main/java/mekanism/common/capabilities/Capabilities.java:59
public static final MultiTypeCapability<IChemicalHandler> CHEMICAL =
    new MultiTypeCapability<>(Mekanism.rl("chemical_handler"), IChemicalHandler.class);
```

- **能力 id = `mekanism:chemical_handler`**（`Mekanism.rl("chemical_handler")`）
- `MultiTypeCapability` 是 record（`src/main/java/mekanism/common/capabilities/MultiTypeCapability.java:10-20`）：

```java
10: public record MultiTypeCapability<HANDLER>(BlockCapability<HANDLER, @Nullable Direction> block,
11:                                            ItemCapability<HANDLER, Void> item,
12:                                            EntityCapability<HANDLER, ?> entity) implements IMultiTypeCapability<HANDLER, HANDLER> {
14:     public MultiTypeCapability(ResourceLocation name, Class<HANDLER> handlerClass) {
15:         this(
16:               BlockCapability.createSided(name, handlerClass),
17:               ItemCapability.createVoid(name, handlerClass),
18:               EntityCapability.createVoid(name, handlerClass)
19:         );
```

⇒ 三种形态：`CHEMICAL.block()`（`BlockCapability<IChemicalHandler, @Nullable Direction>`）、`CHEMICAL.item()`（`ItemCapability<IChemicalHandler, Void>`）、`CHEMICAL.entity()`。

- **能力注册处**：`Capabilities.registerCapabilities(RegisterCapabilitiesEvent)`（`:89-116`），其中 `:108` 转发物品能力、`:114` `TileEntityBoundingBlock.proxyCapability(event, CHEMICAL.block());`；`:84-87` `registerProxyableCapabilities` 里 `event.setProxyable(CHEMICAL.block());`

- **⚠️ 稳定性警告（这是最重要的一条）**：`mekanism/common/capabilities/Capabilities.java` 位于 **`src/main/java`**（mod 本体），**不是** `src/api/java`。⇒ `Capabilities` 属 Mekanism **内部**类，**不是稳定 API**。（对比：`mekanism/api/chemical/IChemicalHandler.java` 在 `src/api/java`，是 API。）
  **直接后果**：**官方 RS-Mekanism 插件因此根本不引用 `Capabilities`** —— 它自己重建了能力对象，见 ⑥.4。

### 5.2 接口：`mekanism.api.chemical.IChemicalHandler`（**在 API 源集，稳定**）

`src/api/java/mekanism/api/chemical/IChemicalHandler.java:8-166`

| 方法 | 行号 | 签名原文 |
| ---- | ---- | ---- |
| 罐数 | `:16` | `int getChemicalTanks();` |
| 读某罐 | `:34` | `ChemicalStack getChemicalInTank(int tank);`（Javadoc `:22-27` 明确「**MUST NOT** be modified」「DO NOT MODIFY THE RETURNED CHEMICAL STACK」） |
| 写某罐 | `:44` | `void setChemicalInTank(int tank, ChemicalStack stack);` |
| 罐容量 | `:53` | `long getChemicalTankCapacity(int tank);` |
| 可接受性 | `:72` | `boolean isValid(int tank, ChemicalStack stack);` |
| **插入（指定罐）** | `:87` | `ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action);` |
| **抽出（指定罐）** | `:102` | `ChemicalStack extractChemical(int tank, long amount, Action action);` |
| 插入（分配交给实现） | `:121` | `default ChemicalStack insertChemical(ChemicalStack stack, Action action)` |
| 抽出（分配交给实现） | `:142` | `default ChemicalStack extractChemical(long amount, Action action)` |
| 抽出（按类型+上限） | `:162` | `default ChemicalStack extractChemical(ChemicalStack stack, Action action)` |

**返回语义**（Javadoc `:84-86`）：`insertChemical` 返回**未被插入的余量**，全部接收则返回 `ChemicalStack.EMPTY`。

### 5.3 方向参数（**这是用户问题 5 的第 2 小问**）

- **方块／方块实体侧**：`BlockCapability<IChemicalHandler, @Nullable Direction>`（`MultiTypeCapability.java:10`）⇒ 取能力时传 `Direction`，**`null` 表示「内部访问」**。
- 语义在 Mekanism 自己的 API 里有明确文字（`src/api/java/mekanism/api/chemical/IMekanismChemicalHandler.java:35`、`:38`）：

```java
35:     * @implNote When side is null (an internal request), this method <em>MUST</em> return all tanks in the handler.
38:     List<IChemicalTank> getChemicalTanks(@Nullable Direction side);
...
49:     default IChemicalTank getChemicalTank(int tank, @Nullable Direction side) {
```

- **NeoForge 侧取法**（标准 `BlockCapability` 语义）：`level.getCapability(Capabilities.CHEMICAL.block(), pos, state, blockEntity, direction)`；**注意 `IChemicalHandler` 不继承 `IFluidHandler`、与流体能力毫无类型关系**（`:9` `public interface IChemicalHandler {`，`getChemicalInTank` 的 Javadoc `:78` 还专门说「Note: This behaviour is subtly different from `IFluidHandler#fill`」）。
- **遍历**：`for (int i = 0; i < h.getChemicalTanks(); i++) { ChemicalStack s = h.getChemicalInTank(i); ... }`（**这就是官方插件 `ChemicalCapabilityCache` 的做法**，见 ⑥.5 的 javap 对应关系）。

### 5.4 化学资源的标识与数量

| 项 | 结论 | 证据 |
| -- | ---- | ---- |
| 栈类型 | `mekanism.api.chemical.ChemicalStack`（API 源集） | 文件路径 |
| 数量 | `long getAmount()`，**空栈返回 0** | `ChemicalStack.java:443-445`：`return isEmpty() ? 0 : amount;` |
| **单位** | **表观单位 = mB（「1000 = 1 桶」已证实）** | `ChemicalStackRenderer.java:41`：`this(FluidType.BUCKET_VOLUME, TooltipMode.ITEM_LIST, 16, 16);`（`FluidType.BUCKET_VOLUME` = 1000）；`:69`：`return ingredient.copyWithAmount(FluidType.BUCKET_VOLUME);`（「把一份化学品归一化成 1 桶」）；`:102`：tooltip 用 `MekanismLang.GENERIC_MB.translateColored(..., TextUtils.format(stack.getAmount()))` |
| 「与流体 mB 完全同尺度」 | **未证实** | 未找到官方「1 mB 化学 == 1 mB 流体」的定义文档；只证实了展示口径同为「桶 = 1000」 |
| 取化学品本体 | `Chemical getChemical()` | `ChemicalStack.java:271-273`：`return getChemicalHolder().value();`（`:269` 注释：`@since 10.6.0 Previously was getType`） |
| 取 Holder | `Holder<Chemical> getChemicalHolder()` | `ChemicalStack.java:282-285`：`return isEmpty() ? MekanismAPI.EMPTY_CHEMICAL_HOLDER : chemical;` |
| **取注册名** | 推荐 `getChemicalHolder()` → `Holder#getKey().location()` / `Holder#getRegisteredName()` | `ChemicalStack.java:613`（`toString()` 用 `getChemicalHolder().getRegisteredName()`）；官方插件也用 `Holder`（见 ⑥） |
| **取注册名（不推荐）** | `ResourceLocation getTypeRegistryName()` —— **已废弃待移除** | `ChemicalStack.java:399-403`：`@Deprecated(forRemoval = true, since = "10.7.11")` |
| 标签 | `Stream<TagKey<Chemical>> getTags()`；`boolean is(TagKey<Chemical>)` | `:355`、`:294-296` |
| 空判定 | `boolean isEmpty()` | `:433-436`（判 `chemical == null \|\| chemical.is(MekanismAPI.EMPTY_CHEMICAL_KEY) \|\| amount <= 0`） |
| 显示名 | `Component getTextComponent()` | `:617-620`：`return getChemical().getTextComponent();` |
| 翻译键 | `String getTranslationKey()` | `:623-626` |
| tooltip | `void appendHoverText(TooltipContext, List<Component>, TooltipFlag)` | `:567` |
| 等值 | `static boolean isSameChemical(ChemicalStack, ChemicalStack)` | `:635` |

### 5.5 化学品注册表与 `Chemical`

| 项 | 结论 | 证据 |
| -- | ---- | ---- |
| 注册表名 | `MekanismAPI.CHEMICAL_REGISTRY_NAME`（`ResourceKey<Registry<Chemical>>`，路径 `"chemical"`） | `MekanismAPI.java:65` |
| 注册表实例 | `public static final DefaultedRegistry<Chemical> CHEMICAL_REGISTRY` | `MekanismAPI.java:120-121` |
| 空化学品 | `MekanismAPI.EMPTY_CHEMICAL_KEY`（`:72`）、`EMPTY_CHEMICAL_HOLDER`（`:168`）、`EMPTY_CHEMICAL`（`:162`，已 deprecated） | 见左 |
| `Chemical` 取色 | `int getTint()` `:372`、`int getColorRepresentation()` `:381` | `src/api/java/mekanism/api/chemical/Chemical.java` |
| `Chemical` 取名 | `Component getTextComponent()` `:354`、`String getTranslationKey()` `:211` | 同上 |
| `Chemical` 取 Holder | `Holder<Chemical> getAsHolder()` `:418` | 同上 |
| `Chemical` 贴图 | 无直接方法；通过 `MekanismRenderer.getChemicalTexture(...)` → `getSprite(chemical.value().getIcon())` | `MekanismRenderer.java:110-111` |

### 5.6 罐接口 `IChemicalTank`（API 源集）与 `Action`

`IChemicalTank`（`src/api/java/mekanism/api/chemical/IChemicalTank.java`）：`ChemicalStack getStack()` `:34`、`void setStack(ChemicalStack)` `:44`、`default ChemicalStack insert(ChemicalStack, Action, AutomationType)` `:76`、`default ChemicalStack extract(long, Action, AutomationType)` `:124`、`long getCapacity()` `:141`、`boolean isValid(ChemicalStack)` `:159`、`default long getStored()` `:266`、`default long getNeeded()` `:275`、`default Holder<Chemical> getTypeHolder()` `:286`、`default Chemical getType()` `:296`。

`mekanism.api.Action`（`src/api/java/mekanism/api/Action.java:5-8`）：

```java
5: public enum Action {
6:     EXECUTE(FluidAction.EXECUTE),
7:     SIMULATE(FluidAction.SIMULATE);
```

⇒ 与 NeoForge 的 `FluidAction` 同构（`execute()` `:18-19`）。**注意这与 RS 的 `com.refinedmods.refinedstorage.api.core.Action` 是**不同**的枚举 —— 官方插件的 `ChemicalUtil.toMekanismAction(RS Action)` 就是干这个转换的（⑥.3）。**

### 5.7 QQ1：**本机能否实测？——不能（`run/mods` 里没有 Mekanism）**

```
PS> Get-ChildItem run\mods -File | Select Name,Length
architectury-13.0.11-neoforge.jar                    584734
create-aeronautics-bundled-1.21.1-1.3.2.jar        33123131
curios-neoforge-9.5.1+1.21.1.jar                     410690
ftb-library-neoforge-2101.1.36.jar                  1439427
ftb-ultimine-neoforge-2101.1.15.jar                  177109
LetMeSeeSee-NeoForge-Minecraft1.21.1-1.0.8.jar       200567
refinedstorage-curios-integration-1.0.0.jar           11177
sable-neoforge-1.21.1-2.0.5.jar                    12986136
```

**⇒ `run/mods` 共 8 个 jar，既没有 `mekanism*.jar`，也没有 `refinedstorage*.jar` 本体，也没有官方插件。** RS 本体由 moddev 的 runtime 依赖提供（`build.gradle:146` `api("com.refinedmods.refinedstorage:refinedstorage-neoforge:2.0.9")`）。

**⇒ 本机「无法实测」气体链路。** 可实测的只有：
1. **编译期**行为（`build.gradle` 里没有 Mekanism ⇒ 任何 `import mekanism.*` 都会编译失败 —— 这本身就是「必须走反射」的硬约束证据）；
2. **未装 Mekanism 时的降级**行为（等价于当前默认状态）。
要真机验证气体，必须先把 Mekanism（+ 可选官方插件）放进 `run/mods` 或 `libs/` —— **本任务明令禁止改 `run/`，故本轮未做**（见 ⑫ D5）。

### 5.8 QQ2：**RS 之外还有谁做过「RS + 气体」？——有，是 RS 官方**（详见 ⑥）

工作区内的 `local_src/` 里搜 `Chemical` 只有 Create 的零星注释（`create_src/.../AirCurrent.java:178`「transported fluids/gases」）与 Create 的 mod 列表常量（`Mods.java:17` `MEK("mekanism", ...)`）—— **没有** RS 侧气体参考实现。
`local_src/universal-grid` 是另一个 RS 插件（`refinedstorageVersion=3.0.5`），与气体无关。

⇒ 参考实现只能向**官方插件**取。见 ⑥。

---

## ⑥ 既有「RS + 气体」实现：**官方插件**（含完整注册清单字节码原文）

### 6.1 取证方式（可复现、且**未污染工作区**）

```
下载到：%TEMP%\rscc_gasprobe\rsmek.jar
来源：https://cdn.modrinth.com/data/QHMDf0Sd/versions/m5pFB06e/refinedstorage-mekanism-integration-1.1.1.jar
大小：160,633 字节
sha1：7d1e60d2f6301da44043ad3a017bb0563b71755a   （Modrinth API 给出的官方 sha1，已核对一致）
反编译：D:\java21\bin\javap.exe  （classpath 同时挂 2.0.9 的 RS jar 以解析继承）
```

**⚠️ 重要声明**：这个 jar 只用于**取证**，**没有**进 `run/`，**没有**进工作区，**没有**被任何构建脚本引用。是否要真的依赖它，是 ⑫ 的 **D1** 决策。

### 6.2 它的 `META-INF/neoforge.mods.toml`（原文）

```toml
modLoader = "javafml"
loaderVersion = "[2,)"
license = "MIT"
[[mods]]
modId = "refinedstorage_mekanism_integration"
version = "1.1.1"
displayName = "Refined Storage - Mekanism Integration"
authors = "Refined Mods"
[[dependencies.refinedstorage_mekanism_integration]]
modId = "refinedstorage"
type = "required"
versionRange = "2.0.0-beta.17"
side = "BOTH"
[[dependencies.refinedstorage_mekanism_integration]]
modId = "mekanism"
type = "required"
versionRange = "[10.6,11-)"
side = "BOTH"
```

**读法**：
- 依赖 **RS ≥ 2.0.0-beta.17**（Maven 裸版本 = 下限）⇒ **本机的 RS 2.0.9 满足**（`2.0.0-beta.17 < 2.0.9`）。
- 依赖 **Mekanism `[10.6, 11)`** ⇒ **本机源码检出的 10.7.19 在范围内**。
- 它的 modId 与我方（`rs_create_compat`）不同 ⇒ 注册表层面能共存（但语义上会打架，见 ⑪ R10）。

### 6.3 它的 55 个类（**这就是「一个 RS 新资源类型」的完整清单**）

```
com/refinedmods/refinedstorage/mekanism/
  ChemicalResource                                    ← PlatformResourceKey 实现（record）
  ChemicalResourceType                                ← ResourceType 实现（enum INSTANCE）+ 内含 STORAGE_TYPE
  ChemicalResourceFactory                             ← ResourceFactory（enum INSTANCE）
  ChemicalResourceRendering                           ← ResourceRendering（客户端显示）※在根包
  ChemicalResourceContainerInsertStrategy             ← ResourceContainerInsertStrategy
  ChemicalRenderer                                    ← 自己写的图标绘制小工具（客户端）
  ChemicalUtil                                        ← 能力键重建 + getCurrentAmount + Action 转换
  ChemicalCapabilityCache                             ← 方块能力缓存 + 迭代器（遍历罐）
  ChemicalInsertableStorage                           ← InsertableStorage
  ChemicalExtractableStorage                          ← ExtractableStorage
  ResourceContainerChemicalHandlerAdapter             ← 让 RS 的 ResourceContainer 对外表现为 IChemicalHandler
  ModInitializer, ClientModInitializer, Config, MekanismIntegrationIdentifierUtil
  autocrafting/ChemicalPatternProviderExternalPatternSink(+Factory)
  content/BlockEntities, Blocks, Items, Menus
  exporter/ChemicalExporterTransferStrategyFactory
  externalstorage/ChemicalExternalStorageProvider, ChemicalPlatformExternalStorageProviderFactory
  grid/ChemicalGridExtractionStrategy, ChemicalGridInsertionHint, ChemicalGridInsertionStrategy,
       ChemicalGridResource, ChemicalGridResourceRepositoryMapper
  importer/ChemicalImporterSource, ChemicalImporterTransferStrategyFactory
  recipemod/EmiChemicalResourceModIngredientConverter, JeiChemicalRecipeModIngredientConverter
  storage/ChemicalStorageBlockBlockItem, ChemicalStorageBlockProvider, ChemicalStorageDiskItem,
          ChemicalStorageVariant
  storagemonitor/ChemicalStorageMonitorInsertionStrategy
```

### 6.4 ⭐ `ModInitializer.setup` 的**完整注册清单**（字节码原文，按顺序）

```java
// 1) 注册资源类型
RefinedStorageApi.INSTANCE.getResourceTypeRegistry()
    .register(CHEMICAL_ID, ChemicalResourceType.INSTANCE);
// 2) 注册「替代资源工厂」（注意：是直接 add 到 Set，而 不是 addResourceFactory(...)）
RefinedStorageApi.INSTANCE.getAlternativeResourceFactories().add(ChemicalResourceFactory.INSTANCE);
// 3) 注册存储类型
RefinedStorageApi.INSTANCE.getStorageTypeRegistry()
    .register(CHEMICAL_ID, ChemicalResourceType.STORAGE_TYPE);
// 4) 网格资源映射（★必做，否则开网格 NPE，见 ⑨.1）
RefinedStorageApi.INSTANCE.addGridResourceRepositoryMapper(
    ChemicalResource.class, new ChemicalGridResourceRepositoryMapper());
// 5) 网格插入策略工厂（lambda）
RefinedStorageApi.INSTANCE.addGridInsertionStrategyFactory(<lambda>);
// 6) 网格抽取策略工厂（lambda）
RefinedStorageApi.INSTANCE.addGridExtractionStrategyFactory(<lambda>);
// 7) 存储监视器插入策略
RefinedStorageApi.INSTANCE.addStorageMonitorInsertionStrategy(new ChemicalStorageMonitorInsertionStrategy());
// 8) 资源容器插入策略
RefinedStorageApi.INSTANCE.addResourceContainerInsertStrategy(new ChemicalResourceContainerInsertStrategy());
// 9) 输入总线（Importer）
RefinedStorageApi.INSTANCE.getImporterTransferStrategyRegistry()
    .register(CHEMICAL_ID, new ChemicalImporterTransferStrategyFactory());
// 10) 输出总线（Exporter）
RefinedStorageApi.INSTANCE.getExporterTransferStrategyRegistry()
    .register(CHEMICAL_ID, new ChemicalExporterTransferStrategyFactory());
// 11) 外部存储
RefinedStorageApi.INSTANCE.addExternalStorageProviderFactory(new ChemicalPlatformExternalStorageProviderFactory());
// 12) 自动合成：样板机对外部机器的「下沉」
RefinedStorageApi.INSTANCE.addPatternProviderExternalPatternSinkFactory(new ChemicalPatternProviderExternalPatternSinkFactory());
// 13) 配方查看器（emi 优先，否则 jei）
if (ModList.get().isLoaded("emi"))      addIngredientConverter(new EmiChemicalResourceModIngredientConverter());
else if (ModList.get().isLoaded("jei")) addIngredientConverter(new JeiChemicalRecipeModIngredientConverter());
```

（对应字节码偏移 `0..261`；其中 `:204-256` 就是 `ModList.isLoaded("emi")` / `"jei"` 的分支。）

**`ClientModInitializer.onClientSetup` 的注册清单**（字节码原文）：

```java
// 1) ★客户端资源渲染（不做会 NPE，见 ⑨.2）
RefinedStorageClientApi.INSTANCE.registerResourceRendering(ChemicalResource.class, new ChemicalResourceRendering());
// 2) 网格插入提示（拖入时的提示 tooltip）
RefinedStorageClientApi.INSTANCE.addAlternativeGridInsertionHint(new ChemicalGridInsertionHint());
// 3) 每种存储档位注册磁盘模型
ResourceLocation diskModel = MekanismIntegrationIdentifierUtil.createMekanismIntegrationIdentifier("block/disk/chemical_disk");
for (ChemicalStorageVariant v : ChemicalStorageVariant.values())
    RefinedStorageClientApi.INSTANCE.registerDiskModel(Items.getChemicalStorageDisk(v), diskModel);
```

### 6.5 ⭐⭐ `ChemicalUtil` 的**能力取法**（`<clinit>` 字节码原文）—— 这是「版本风险缓解」的答案

```
static {};
  Code:
     0: ldc           #59   // String mekanism
     2: ldc           #61   // String chemical_handler
     4: invokestatic  #63   // ResourceLocation.fromNamespaceAndPath(String, String)ResourceLocation
     7: putstatic     #69   // Field CHEMICAL_HANDLER_ID:ResourceLocation
    10: getstatic     #69
    13: ldc           #8    // class mekanism/api/chemical/IChemicalHandler
    15: invokestatic  #75   // BlockCapability.createSided(ResourceLocation, Class)BlockCapability
    18: putstatic     #81   // Field BLOCK_CAPABILITY
    21: getstatic     #69
    24: ldc           #8    // class mekanism/api/chemical/IChemicalHandler
    26: invokestatic  #85   // ItemCapability.createVoid(ResourceLocation, Class)ItemCapability
    29: putstatic     #91   // Field ITEM_CAPABILITY
    32: return
```

**⇒ 官方插件**不引用 `mekanism.common.capabilities.Capabilities`，而是：
```java
static final ResourceLocation CHEMICAL_HANDLER_ID =
    ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler");
public static final BlockCapability<IChemicalHandler, Direction> BLOCK_CAPABILITY =
    BlockCapability.createSided(CHEMICAL_HANDLER_ID, IChemicalHandler.class);
public static final ItemCapability<IChemicalHandler, Void> ITEM_CAPABILITY =
    ItemCapability.createVoid(CHEMICAL_HANDLER_ID, IChemicalHandler.class);
```
（`javap` 公开签名：`public static final BlockCapability<IChemicalHandler, Direction> BLOCK_CAPABILITY;` / `public static final ItemCapability<IChemicalHandler, Void> ITEM_CAPABILITY;`）

**为什么这很关键**：
1. 它绕开了 Mekanism 的**内部**类 `Capabilities`（在 `src/main/java`，见 ⑤.1），把耦合降到「**一个字符串 + 一个 API 接口**」；
2. 能力 id `mekanism:chemical_handler` 是**注册名**，比 Java 类名稳定得多；
3. **注意**：`IChemicalHandler.class` 仍然是**编译期硬引用**。对官方插件无所谓（它把 mekanism 声明为 required），但**我方是可选依赖**（见 ⑪ R1/R2），所以必须再叠一层反射。

### 6.6 其它关键类的公开签名（`javap` 原文摘要）

| 类 | 公开签名（节选） |
| -- | ---- |
| `ChemicalResource` | `public final class ... extends java.lang.Record implements PlatformResourceKey`；`public ChemicalResource(mekanism.api.chemical.Chemical);`；`public static ChemicalResource ofChemicalStack(ChemicalStack);`；`public Chemical chemical();` + 接口 4 方法 |
| `ChemicalResourceType` | `public final class ... extends java.lang.Enum<ChemicalResourceType> implements ResourceType`；`public static final ChemicalResourceType INSTANCE;`；`public static final MapCodec<ChemicalResource> MAP_CODEC;`；`public static final Codec<ChemicalResource> CODEC;`；`public static final StreamCodec<RegistryFriendlyByteBuf, ChemicalResource> STREAM_CODEC;`；`public static final StorageType STORAGE_TYPE;` + 接口 8 方法 |
| `ChemicalResourceRendering` | `implements ResourceRendering`；`formatAmount(long, boolean)` / `getDisplayName(ResourceKey)` / `getTooltip(ResourceKey)` / `render(ResourceKey, GuiGraphics, int, int)` / `render(ResourceKey, PoseStack, MultiBufferSource, int, Level)`；`public static String format(long)` |
| `ChemicalRenderer` | `public static void render(PoseStack, int, int, Chemical);` / `public static void render(PoseStack, MultiBufferSource, int, Chemical);` |
| `ChemicalCapabilityCache` | `public ChemicalCapabilityCache(ServerLevel, BlockPos, Direction);` / `Optional<IChemicalHandler> getCapability();` / `Iterator<ResourceAmount> createAmountIterator();` / `Iterator<ResourceKey> createIterator();` |
| `ChemicalInsertableStorage` | `implements InsertableStorage`；`insert(ResourceKey, long, Action, Actor)` / `getAmount(ResourceKey)` |
| `ChemicalExtractableStorage` | `implements ExtractableStorage`；`extract(ResourceKey, long, Action, Actor)` / `getAmount(ResourceKey)` |
| `ResourceContainerChemicalHandlerAdapter` | `implements mekanism.api.chemical.IChemicalHandler`；实现全部 7 个方法 |
| `ChemicalResourceContainerInsertStrategy` | `implements ResourceContainerInsertStrategy`（**package-private 类**） |
| `ChemicalGridResourceRepositoryMapper` | `implements ResourceRepositoryMapper<GridResource>`；`apply(ResourceKey)` |
| `ChemicalGridResource` | `extends AbstractGridResource<ChemicalResource>`；实现了 `getRegistryId/render/getTooltip/getTooltipImage/getExtractionHints/canExtract/onExtract/onScroll/getDisplayedAmount/getAmountInTooltip/belongsToResourceType/getAutocraftingRequest` |
| `ChemicalGridExtractionStrategy` / `ChemicalGridInsertionStrategy` / `ChemicalGridInsertionHint` | 分别实现 `GridExtractionStrategy` / `GridInsertionStrategy` / `GridInsertionHint` |
| `ChemicalStorageVariant` | `enum implements StringRepresentable, StorageVariant`；`SIXTY_FOUR_B / TWO_HUNDRED_FIFTY_SIX_B / THOUSAND_TWENTY_FOUR_B / EIGHT_THOUSAND_NINETY_TWO_B / CREATIVE`；`Long getCapacityInBuckets()` / `Long getCapacity()` / `Item getStoragePart()` / `ResourceLocation getStorageDiskId()/getStorageBlockId()/getStoragePartId()` |
| `ChemicalStorageBlockProvider` | `implements StorageBlockProvider`；`SerializableStorage createStorage(Runnable)` / `Component getDisplayName()` / `long getEnergyUsage()` / `ResourceFactory getResourceFactory()` / `BlockEntityType<?> getBlockEntityType()` / `MenuType<?> getMenuType()` |
| `ChemicalImporterSource` | `implements ImporterSource`（package-private） |
| `ChemicalExporterTransferStrategyFactory` | `implements ExporterTransferStrategyFactory`；**含 `Class<? extends ResourceKey> getResourceType()`** |
| `ChemicalPlatformExternalStorageProviderFactory` | `implements ExternalStorageProviderFactory` |
| `ChemicalPatternProviderExternalPatternSink` | `implements PlatformPatternProviderExternalPatternSink`；`accept(Collection<ResourceAmount>, Action)` / `isEmpty()` / `applies(ResourceKey)` |
| `JeiChemicalRecipeModIngredientConverter` | `implements RecipeModIngredientConverter`；`convertToResource(Object)` / `convertToResourceAmount(Object)` / `convertToIngredient(PlatformResourceKey)` |
| `ChemicalUtil` | `public static long getCurrentAmount(IChemicalHandler, ChemicalResource);` / `public static mekanism.api.Action toMekanismAction(com.refinedmods.refinedstorage.api.core.Action);` |

### 6.7 ⭐ 兼容性核对：**它的 RS API 用法与 2.0.9 逐字吻合**

我把官方插件实现/使用的**全部** RS 接口在 2.0.9 上 `javap` 了一遍，全部**逐字存在**：

```
ResourceType / PlatformResourceKey / ResourceKey / StorageType / StorageVariant
PlatformRegistry(register/getId/get/getAll/nextOrNullIfLast/codec/streamCodec)
GridResource / AbstractGridResource / ResourceRepositoryMapper / GridExtractionStrategy /
GridInsertionStrategy / GridInsertionHint / GridResourceAttributeKey
ResourceRendering / RecipeModIngredientConverter / ResourceContainerInsertStrategy / ResourceFactory
ImporterTransferStrategyFactory / ExporterTransferStrategyFactory(含 getResourceType()) /
ExternalStorageProviderFactory / PatternProviderExternalPatternSinkFactory
StorageBlockProvider / StorageMonitorInsertionStrategy / RefinedStorageApiProxy 上的各 add*
```

⇒ **⇒ 该插件在 API 层面与 RS 2.0.9 兼容**（**没有实机运行验证**，列入 ⑫ 未证实 4）。

---

## ⑦ 设计：统一资源模型

### 7.1 三层抽象 + 六张注册表（对齐官方插件）

| 抽象层 | RS 接口 | 物品 | 流体 | **气体（新）** |
| ---- | ---- | ---- | ---- | ---- |
| 资源键 | `ResourceKey` / `PlatformResourceKey` | `ItemResource` | `FluidResource` | **`GasResource`（新）** |
| 资源类型 | `ResourceType` | `ResourceTypes.ITEM` | `ResourceTypes.FLUID` | **`GasResourceType`（新）** |
| 存储类型 | `StorageType` | `StorageTypes.ITEM` | `StorageTypes.FLUID` | **复用 `rs_create_compat:universal`**（已 `isAllowed→true`） |
| 客户端显示 | `ResourceRendering` | `ItemResourceRendering` | `FluidResourceRendering` | **`GasResourceRendering`（新，客户端）** |
| 网格条目 | `GridResource` + mapper | `ForgeItemResourceRepositoryMapper` | `ForgeFluidResourceRepositoryMapper` | **`GasGridResource` + `GasGridResourceRepositoryMapper`（新，★必做）** |
| 世界读写 | NeoForge capability | `IItemHandler` | `IFluidHandler` | **Mekanism `IChemicalHandler`** |

### 7.2 建议的 `GasResource` 具体形状（**照抄官方插件的已验证形状**）

```java
// 形状来自官方插件 javap：
//   public final class ChemicalResource extends java.lang.Record implements PlatformResourceKey {
//       public ChemicalResource(mekanism.api.chemical.Chemical);
//       public static ChemicalResource ofChemicalStack(ChemicalStack);
//       public Chemical chemical();
//   }
public record GasResource(mekanism.api.chemical.Chemical chemical) implements PlatformResourceKey {
    public static GasResource ofChemicalStack(ChemicalStack stack) {
        return new GasResource(stack.getChemical());
    }
    @Override public long getInterfaceExportLimit() { ... }
    @Override public long getProcessingPatternLimit() { ... }
    @Override public List<ResourceTag> getTags() { ... }     // Holder<Chemical>.tags() → ResourceTag
    @Override public ResourceType getResourceType() { return GasResourceType.INSTANCE; }
}
```

**设计取舍**：
- **不带 components**：官方也不带。Mekanism 的 `ChemicalStack` 没有 `DataComponentPatch` 概念（对比 RS 的 `FluidResource` 带 `components`，`FluidResource.java:22-23`）。⇒ 气体的资源键 = **纯 `Chemical`**，比流体更简单。**这是气体反而比流体好做的一点。**
- **推荐 record**：`equals`/`hashCode` 自动由 `Chemical` 决定；官方用 record 也是这个理由。
- **`getTags()`**：`ChemicalResource.getTags()` 是官方实现；来源应为 `Holder<Chemical>.tags()` → 映射为 RS 的 `ResourceTag`（`FluidResource.java:51-59` 是流体的对应写法：`holder.tags().flatMap(tagKey -> registry.getTag(tagKey).stream()).map(tag -> new ResourceTag(tag.key(), tag.stream().map(...).toList()))`）。
- **`getInterfaceExportLimit()` / `getProcessingPatternLimit()` 的具体数值**：官方 `ChemicalResourceType` 有实现（`javap` 可见 8 个方法齐全），但我**没有反编译出常量值** ⇒ **未证实**（列入 ⑫）。这两者与用户要求的「无数量上限」可能冲突（详见 ⑪ R11）。

### 7.3 与既有「气体 = 流体」注释的关系：**必须改口径**（附带波及面）

现状（①）：
```
FORM_GAS = 2，但 KeeperSlotConfig.isFluidForm() 把 1 和 2 一起判为「流体类」
（KeeperSlotConfig.java:41-43），真实承载永远是 FluidResource。
```

**新口径**：
```
FORM_ITEM  = 0  → ItemResource
FORM_FLUID = 1  → FluidResource
FORM_GAS   = 2  → GasResource          ← 语义从「流体的一种」变成「第三种资源」
```

**代价（必须提前说清）**：
1. **UI/文案**：`lang` 里已有大量「流体/气体」并列文案（`zh_cn.json:136`、`:307`、`:913`、`:790`…），大部分**不用改**（它们本来就把两者并列）；但 `GhostMarkerRenderer.java:38`、`QuantityKeeperJeiPlugin.java:34,43`、`KeeperSlotConfig.java:28` 这类「气体以流体承载」的注释**必须改**，否则后人会照着错的注释写。
2. **类型判断要复核 53 处**（清单见 ④.5）。其中「显式跳过未知类型」的是安全的（`SequenceAssemblyExecutorBlockEntity.java:160-161`），「只判 FluidResource 就当流体处理」的**必须**复核。
3. **存档语义**：`FORM_GAS` 的槽位里原本**只能是流体**（等价于 `FORM_FLUID`）；改口径后「标记了 FORM_GAS 但没有气体」的旧档会读到 `GasResource=null`。需要一条**读档兼容**：旧档 `FORM_GAS` + 有流体 id ⇒ 降级成 `FORM_FLUID` 并保留液体标记（否则玩家旧档里的「气体标记」会凭空消失）。
   —— 这一条**尚未设计细节**，列入 ⑫ 未证实/待办。

### 7.4 「统一」到什么程度：**建议只统一到 `PlatformResourceKey` 这一层，不要再往上抽**

理由（有证据）：
- `ResourceCodecs.CODEC` / `STREAM_CODEC` 已经是「统一编解码」（③.4）；
- `storage.insert(ResourceKey, long, Action, Actor)` 已经是「统一网络写入」（④.2）；
- `UniversalStorageType.isAllowed` 已经是「统一接受」（④.3）；
- **再往上抽（例如搞一个 `RsccResource` 联合类型）会与 RS 的 `instanceof` 分发模型冲突**：RS 自己就是靠 `resource.getClass()` / `instanceof` 分发的（`GridResourceRepositoryMapper.java:36`、`ItemGridResource.java:144`、`FluidGridResource.java:122`）。**逆着上游的分发模型抽一层，是最容易被上游版本变更打断的设计。**

---

## ⑧ 设计：气体缓存（照抄 `MultiFluidCache`？还是抽通用？）

### 8.1 三个候选与代价

| 方案 | 做法 | 优点 | 代价 |
| ---- | ---- | ---- | ---- |
| **A. 复制一份 `MultiGasCache`** | 167 行照抄，键简化为「化学品注册名」（去掉 `CompoundTag`） | 零风险；不动任何既有调用点；气体键更简单（无数据组件） | 代码重复一份；将来第四种资源要再复制 |
| **B. 抽 `MultiResourceCache<T>` 泛型** | 把键泛化，加一个 `Codec`/`KeyAdapter` | 一次到位 | **必须动现有 3 处调用点**（`CollectionCacheBlockEntity` 的 `FLUID_CACHE_CAPACITY` 相关、`QuantityKeeperBlockEntity.java:130`、`AdvancedQuantityKeeperBlockEntity.java:118-125`）；泛型 + NBT 键的适配器会引入新抽象；**改造面 vs 收益不对等** |
| **C. 复用 `MultiFluidCache`，键泛化为 `ResourceKey`** | 把 `Entry(ResourceLocation, CompoundTag, long)` 换成 `Entry(ResourceKey, long)` | 真正统一 | 要重写 `MultiFluidCache` 的 `save/load`（`:133-166` 现在按「流体注册名 + NBT」序列化，改 `ResourceKey` 就得改用 `ResourceCodecs.CODEC`）；**这是改动最大的方案，且会把「物品缓存」和「流体缓存」的既有存档格式都牵动** |

### 8.2 **推荐 A（复制）**，理由三条

1. **键的形状天然不同**：`MultiFluidCache.Entry` 的键是 `(ResourceLocation id, CompoundTag nbt)`（`MultiFluidCache.java:25`）。**气体不需要 nbt**（`ChemicalStack` 无数据组件，官方 `ChemicalResource` 也不带）。抽泛型会强迫两边都带 nbt 或都去掉 —— **两边都不对**。
2. **`MultiFluidCache` 已被多处复用**（`CollectionCacheBlockEntity.java:189,333`、`AdvancedQuantityKeeperBlockEntity.java:118`、`QuantityKeeperBlockEntity.java:130`），抽泛型要动这些调用点 ⇒ **风险 >> 收益**。
3. **触发「该抽泛型」的条件是「第三种」而不是「第二种」**。现在加气体是第二种「非物品」资源 —— 恰好是**最不该在这时候做大重构**的时点。等到真有第四种（例如 Mekanism 的 `slurry` / `pigment` / `infuse_type`，或 FE），**那时再抽**，届时泛型的形状会被三种真实用例确定下来，而不是现在猜。

### 8.3 容量单位与换算（**必须显式定义，否则守恒公式会错**）

| 资源 | 单位 | 与「物品位」的换算 | 证据 |
| ---- | ---- | ---- | ---- |
| 物品 | 件 | 1 件 = 1 位 | `UniversalLimitedStorage.java:156-157` |
| 流体 | mB | 1000 mB = 1 位（不足 1 桶按 1 桶） | `UniversalLimitedStorage.java:159` |
| **气体** | mB（同「桶 = 1000」口径） | **1000 mB = 1 位** | `UniversalLimitedStorage.java:159` 的分支本来就对**所有非 `ItemResource`** 生效 ⇒ **零改动**；气体侧「桶 = 1000」的口径证据见 `ChemicalStackRenderer.java:41,69,102` |

⚠️ **单位同尺度警告**：此处把气体按「1000 单位 = 1 桶」处理是**展示口径一致**，不是「1 mB 化学 == 1 mB 流体」的官方定义（⑤.4 已标未证实）。**如果用户在意「气体与流体在同一张盘上的容量可比性」，这一条需要拍板（⑫ D3）。**

### 8.4 混存口径（现成行为，无需改代码，但需拍板）

`Config.universalDiskAllowMixedTypes`（`Config.java:29` 注释：「是否允许通用储存磁盘同时混存不同类型（物品/流体/气体）。关闭后每个磁盘只能存放一种类型。」）

关闭时的判定：`UniversalLimitedStorage.isSameCategory`（`:145-150`）用 `platformA.getResourceType().equals(platformB.getResourceType())` ⇒ **气体与流体是两个不同类别** ⇒ 关掉混存后，「气体盘」与「流体盘」不能同盘。

**这是想要的行为吗？** 需用户确认（⑫ D3）。

---

## ⑨ 设计：气体的网络存储 —— **明确结论**

### 9.1 ⭐ 结论（一句话，这就是用户要的那个「关键结论」）

> **RS 2.0.9 本体不支持气体；但本工程不需要自建「气体存储节点/方块」。**
>
> **理由**：本仓已有的「通用储存磁盘」（`rs_create_compat:universal`）在**三个层面**都已经是类型无关的 —— ①接受层 `isAllowed(ResourceKey)` 恒 `true`（`UniversalStorageType.java:80-82`）②编解码层走 `ResourceCodecs.CODEC` 的按 `ResourceType` dispatch（`:33` + `ResourceCodecs.java:36-38`）③容量/同类判定用 `getResourceType()`（`UniversalLimitedStorage.java:145-150,155-160`）。**⇒ 只要注册一个 `GasResourceType`，通用盘立刻就能存气体，零改动。**
>
> **真正必须自建的是三件**：**(1) `GasResourceType` + `GasResource`；(2) 网格资源映射 `GasGridResourceRepositoryMapper`（★不做会 NPE）；(3) 客户端 `GasResourceRendering`（★不做会 NPE）。** 这三件里，(2)(3) 不是「体验优化」而是**崩溃闸门**（下面 9.3 给证据）。

### 9.2 证据链（六条，逐条给行号）

1. **存储层入口类型无关**：`StorageNetworkComponent extends RootStorage extends Storage`（`javap` 2.0.9），`Storage#insert/extract` 的入参就是 `ResourceKey`。而实际写入语句是 `storage.insert(resource, attempt, Action.EXECUTE, Actor.EMPTY)`（`CollectionCacheBlockEntity.java:2087`）—— **对气体一字不改**。
2. **本仓通用盘恒接受**：`UniversalStorageType.java:80-82` `isAllowed(...) { return true; // 接受任意类型资源（物品、流体、气体……） }`。
3. **编解码自动适配**：`UniversalStorageType.java:33` `private static final Codec<PlatformResourceKey> RESOURCE_CODEC = ResourceCodecs.CODEC;`；`ResourceCodecs.java:36-38` 是 `dispatch(getResourceType, ResourceType::getMapCodec)` ⇒ 注册了 `GasResourceType` 就自动能读写。**并且** `UniversalStorageType.java:131-153` 的 `ErrorTolerantListCodec` 让「单条解码失败只跳过并 WARN，不整盘报废」。
4. **容量与同类判定已类型无关**：`UniversalLimitedStorage.java:155-160`（非物品按 1000 单位/位）、`:145-150`（`getResourceType().equals`）。
5. **存不下时不丢资源**：`CollectionCacheBlockEntity.java:2051` 方法 Javadoc「网络没有对应资源存储时留在缓存里，不丢资源」+ `:2088-2090` `if (inserted <= 0) { continue; }`。⇒ 即使玩家一块通用盘都没插，气体也**不会凭空消失**，只是留在本机缓存里等下一轮。
6. **反证：官方确实做了「化学专用盘」，但那是「商品」不是「能力」**：官方插件的 `ChemicalStorageDiskItem` / `ChemicalStorageBlockProvider` / `ChemicalStorageVariant`（64B/256B/1024B/8192B/Creative，`javap` 在案）确实是把化学品注册进 `getStorageTypeRegistry()` 后才成立的 —— 这说明「新资源类型要能被存储，需要一个 `StorageType`」是**必要条件**，但**不意味着必须新建方块**：我方已有的 `rs_create_compat:universal` 就已经满足这个必要条件。

### 9.3 ⛔ 两个「不做就崩」的闸门（**这是本节最硬的结论**）

#### ⑨.3.1 网格映射 `GasGridResourceRepositoryMapper` 是**硬性要求**

证据（三处拼起来）：

```java
// local_src/external/RefinedStorage/refinedstorage-common/src/main/java/com/refinedmods/refinedstorage/common/grid/view/GridResourceRepositoryMapper.java:34-47
34:    @Override
35:    public GridResource apply(final ResourceKey resource) {
36:        final Class<? extends ResourceKey> resourceClass = resource.getClass();
37:        if (resourceClass == ItemResource.class && itemFactory != null) {
38:            return itemFactory.apply(resource);
39:        } else if (resourceClass == FluidResource.class && fluidFactory != null) {
40:            return fluidFactory.apply(resource);
41:        }
42:        final ResourceRepositoryMapper<GridResource> factory = requireNonNull(
43:            strategies.get(resourceClass),
44:            "No factory for " + resourceClass
45:        );
46:        return factory.apply(resource);
47:    }
```

```java
// .../common/grid/AbstractGridContainerMenu.java:158-166
158:    private static ResourceRepositoryBuilder<GridResource> createRepositoryBuilder(
159:        final GridSortingTypes.TrackedResourceProvider sortingContext
160:    ) {
161:        return new ResourceRepositoryBuilderImpl<>(
162:            RefinedStorageApi.INSTANCE.getGridResourceRepositoryMapper(),   // ← 每次开网格都会拿这个 mapper
...
```

```java
// .../refinedstorage-resource-api/.../repository/ResourceRepositoryImpl.java:142
142:        final T mapped = mapper.apply(resource);   // ← 逐条资源调用；气体没有 mapper ⇒ requireNonNull 抛 NPE
```

**⇒ 只要网络里有气体、而没注册气体 mapper，玩家打开任意网格 → `NullPointerException: No factory for class ...GasResource`。**

⚠️ 附带陷阱：`:36` 用的是 `resource.getClass()`（**精确类相等**，不是 `instanceof`）⇒ 注册时传的 `Class` **必须与具体类完全一致**（`GasResource.class`）。官方插件传的就是 `ChemicalResource.class`（⑥.4 第 4 条字节码）。

#### ⑨.3.2 客户端 `ResourceRendering` 也是**硬性要求**

```java
// .../common/RefinedStorageClientApiImpl.java:99-106
 99:    @Override
100:    public <T extends ResourceKey> ResourceRendering getResourceRendering(final Class<T> resourceClass) {
101:        // fast path for items
102:        if (resourceClass == ItemResource.class) {
103:            return ItemResourceRendering.INSTANCE;
104:        }
105:        return resourceRenderingMap.get(resourceClass);      // ← 未注册 ⇒ 返回 null
106:    }
```

`javap` 2.0.9 的 `ResourceRendering` 是**接口**，RS 各处直接拿去调（例：`FluidGridResource.java:46` `this.rendering = RefinedStorageClientApi.INSTANCE.getResourceRendering(FluidResource.class);` 之后 `render(...)`；`TaskCompletedToast.java:35`；`AutocraftingMonitorScreen.java:262`）⇒ **`null` 会在渲染路径上直接 NPE**。

（`RefinedStorageClientApi.java:36-38` 是公开的注册入口：`<T extends ResourceKey> void registerResourceRendering(Class<T>, ResourceRendering);`）

### 9.4 「直通」vs「存进网络」：可以做，但边界要说清

用户提到「方块到方块直通」。**结论：直通可以做，但它省不掉 9.3 的两个闸门，只省掉 ⑩/⑥ 的周边套件。**

| 做法 | 必须实现的 | 可以不做 | 玩家能做什么 |
| ---- | ---- | ---- | ---- |
| **① 只做直通**（发送端缓存气体 → 输出到朝向的容器） | `GasResource`（只为了能进 `Action` 管线 / 缓存盘点）+ `GasResourceType` + **`GasGridResourceRepositoryMapper`** + **`GasResourceRendering`**（后两者仍然必需，因为气体一旦进了网络存储路径，网格就会看到它） | importer/exporter 策略、外部存储、自动合成 sink、存储监视器策略、资源容器插入策略、recipe viewer 转换器、气体专用盘/方块 | 气体能从 A 搬到 B；但**不能在网格里手动取放**（除非注册 grid insertion/extraction strategy）；不能参与自动合成 |
| **② 官方全套** | ⑥.4 的 13 条 + ⑥.3 的 19 个类 + 气体专用盘/方块 | —— | 完整（存储/查看/取放/自动合成/外部存储/存储监视器） |

**⇒ 建议分阶段（见 ⑬）**：Phase 0-3 做到「网格里能看到 + 能取放」，Phase 4 做「方块↔网络的能力桥（这才是『发送端/接收端支持气体』的核心）」，Phase 5 才是官方全套的周边。

### 9.5 为什么不建议「自建气体存储节点/方块」（除非要做「商品」）

1. **必要条件是 `StorageType` 而不是方块**：官方 `ChemicalResourceType` 把 `StorageType` 作为**静态字段**与 `ResourceType` 绑在一起（`javap`：`public static final StorageType STORAGE_TYPE;`），然后在 `getStorageTypeRegistry().register(CHEMICAL_ID, STORAGE_TYPE)` 注册（⑥.4 第 3 条）。我方 `rs_create_compat:universal` **已经注册过**（`RS_Create_Compat.java:594-597`）且接受一切 ⇒ 必要条件已满足。
2. **自建盘 = 新增方块/物品/BlockEntity/Menu/模型/配方/创造标签页**，并按 `dependency-policy.md` 的要求登记资源 ⇒ **成本高，收益只是「多一件商品」**。
3. **唯一的合理理由**是「玩家希望能有一块**只装气体**的盘」（现在只能靠 `Config.universalDiskAllowMixedTypes=false` 近似）。**这属于产品决策，列入 ⑫ D3。**

---

## ⑩ 设计：界面表示（复用方案 —— **不要自己画**）

### 10.1 好消息：注册 `GasResourceType` 后，网格的「资源类型」侧边按钮**自动出现气体**

```java
// .../common/grid/AbstractGridContainerMenu.java:407-420
407:    public void toggleResourceType() {
408:        final PlatformRegistry<ResourceType> registry = RefinedStorageApi.INSTANCE.getResourceTypeRegistry();
409:        final Config.GridEntry config = Platform.INSTANCE.getConfig().getGrid();
410:        final ResourceType newResourceType = resourceTypeFilter == null
411:            ? ResourceTypes.ITEM                                     // ← 从 ITEM 开始循环
412:            : registry.nextOrNullIfLast(resourceTypeFilter);
413:        if (newResourceType == null) {
414:            config.clearResourceType();
415:        } else {
416:            registry.getId(newResourceType).ifPresent(config::setResourceType);
417:        }
```

```java
// .../common/grid/screen/ResourceTypeSideButtonWidget.java:34-55
35:    protected ResourceLocation getSprite() {
36:        final ResourceType resourceType = menu.getResourceType();
37:        if (resourceType == null) { return ALL; }
40:        return resourceType.getSprite();      // ← 用我们注册的 ResourceType.getSprite()
...
49:    protected List<MutableComponent> getSubText() {
50:        final ResourceType resourceType = menu.getResourceType();
51:        if (resourceType == null) { return SUBTEXT_ALL; }
54:        return List.of(resourceType.getTitle().withStyle(ChatFormatting.GRAY));   // ← 用 getTitle()
```

⇒ **不需要改 RS 的任何 UI 代码**；侧边按钮的图标来自 `ResourceType.getSprite()`，标题来自 `getTitle()`。**这是 RS 为第三方资源类型预留的正门。**

### 10.2 复用清单（三处挂载点）

| 场景 | 现成可复用的东西 | 证据 |
| ---- | ---- | ---- |
| **① 网格/终端里画气体图标** | (a) 官方插件自己写的薄封装 `ChemicalRenderer.render(PoseStack, int, int, Chemical)` / `render(PoseStack, MultiBufferSource, int, Chemical)`（`javap`）<br>(b) 底层原语：`MekanismRenderer.getChemicalTexture(ChemicalStack)` / `getChemicalTexture(Holder<Chemical>)`、`MekanismRenderer.color(GuiGraphics, ChemicalStack)`、`MekanismRenderer.resetColor(GuiGraphics)`<br>(c) 平铺绘制：`GuiUtils.drawTiledSprite(GuiGraphics, x, y, yOffset, desiredWidth, desiredHeight, TextureAtlasSprite, texW, texH, zLevel, TilingDirection)`<br>(d) 现成的 JEI 成分渲染器：`ChemicalStackRenderer implements IIngredientRenderer<ChemicalStack>`，其 `render(GuiGraphics, ChemicalStack)` **只需要 `GuiGraphics` + `ChemicalStack`** | `MekanismRenderer.java:106-111`、`:150`、`:192-195`；`GuiUtils.java:88-94`；`ChemicalStackRenderer.java:30-72`（`:57-72` 是 render 实现，`:33` `MIN_CHEMICAL_HEIGHT = 1`、`:66-70` 用 `color`+`drawTiledSprite`+`resetColor`） |
| **② 名称** | `ChemicalStack.getTextComponent()` 或 `Chemical.getTextComponent()`；官方 `ChemicalResourceRendering.getDisplayName(ResourceKey)` | `ChemicalStack.java:617-620`；`Chemical.java:354`；官方 `javap` |
| **③ tooltip** | `ChemicalStack.appendHoverText(TooltipContext, List<Component>, TooltipFlag)`；官方 `ChemicalResourceRendering.getTooltip(ResourceKey)`；`ChemicalStackRenderer.collectTooltips(...)`（`:95-106`，含 `GENERIC_MB` 单位行） | `ChemicalStack.java:567`；`ChemicalStackRenderer.java:95-106`；官方 `javap` |
| **④ 数量文字** | `ResourceRendering.formatAmount(long, boolean)`；官方 `ChemicalResourceRendering.formatAmount` + `static String format(long)` | `javap` 2.0.9 `ResourceRendering`；官方 `javap` |
| **⑤ 侧边按钮 16×16 图标** | `ResourceType.getSprite()` → `graphics.blitSprite(sprite, x, y, 16, 16)` ⇒ **必须是 GUI sprite 图集里的一张 16×16** | `ResourceType.java:22`；`AbstractSideButtonWidget.java:56-62`（`getSprite()` 交给 `blitSprite`） |
| **⑥ JEI 拖入** | `RefinedStorageApi.addIngredientConverter(RecipeModIngredientConverter)` + 官方 `JeiChemicalRecipeModIngredientConverter`（`javap` 三个方法）；本仓已有 JEI 依赖 | `RefinedStorageApi` `javap`；`build.gradle:152-153`；官方 `javap` |

### 10.3 ⚠️ 一个**不能**复用的地方：侧边按钮图标

- `ResourceType.getSprite()` 走的 `graphics.blitSprite(...)`（`AbstractSideButtonWidget.java:56-62`）⇒ 需要 **GUI sprite 图集**里的一张 16×16。
- 而 Mekanism 的化学品贴图在**方块图集**：`MekanismRenderer.getChemicalTexture(...)` → `getSprite(chemical.value().getIcon())`（`MekanismRenderer.java:110-111`），`Chemical.getIcon()` 指向 `textures/liquid/*`、`textures/slurry/*` 等（本机 `local_src/external/Mekanism/src/main/resources/assets/mekanism/textures/` 下有 `liquid/`、`slurry/`、`infuse_type/`、`pigment/` 四个目录，**没有 `gui/sprites/` 目录**）。
- ⇒ **跨图集复用「未证实」**（列入 ⑫ 未证实 3）。保守做法：**自备一张 16×16 的 GUI sprite**（这是「一张贴图」的资产工作，不是「自己写渲染」）。
- **但网格条目里的图标不需要 sprite**：`AbstractGridResource.render(GuiGraphics, int, int)` 是我们自己实现的，可以直接用 `MekanismRenderer.getChemicalTexture(...)` + `color(...)` + `drawTiledSprite(...)` 画——**零新增贴图**（⑩.2 的 ①(b)(c)）。

### 10.4 网格条目还剩多少活（`AbstractGridResource` 已经做掉一半）

`javap` 2.0.9 的 `AbstractGridResource<T extends PlatformResourceKey>` 是实现类（`GridResource` 的公开抽象基类）；本机源码 `.../common/api/grid/view/AbstractGridResource.java:36-71` 已经实现：
`getTrackedResource` `:38-40`、`getAmount` `:43-45`、`getName` `:48-50`、`getSearchableNames` `:53-55`、`getAttribute` `:58-60`、`isAutocraftable` `:63-65`、`getResourceForRecipeMods` `:69-71`。

⇒ 只需实现（照官方 `ChemicalGridResource` 的形状）：
`getRegistryId`、`render(GuiGraphics,int,int)`、`getTooltip`、`getTooltipImage`、`getExtractionHints`、`canExtract`、`onExtract`、`onScroll`、`getDisplayedAmount`、`getAmountInTooltip`、`belongsToResourceType`、`getAutocraftingRequest`。

**注意**：`getRegistryId` 是「排序/稳定 id」——官方流体用 `BuiltInRegistries.FLUID.getId(...)`（`FluidGridResource.java:45,50-52`）。气体可考虑用 `BuiltInRegistries`? **不行** —— 化学品不在 MC 注册表里，要用 `MekanismAPI.CHEMICAL_REGISTRY.getId(chemical)`（**这个 id 是 int 吗？未证实** —— `Registry#getId` 的返回类型需实测/查源码，列入 ⑫ 未证实 6）。

---

## ⑪ 风险与缓解

| # | 风险 | 证据 | 缓解措施 |
| - | ---- | ---- | ---- |
| **R1** | **Mekanism 的化学 API 会跨版本变化**（不是猜测，是 Mekanism 官方自己承认的） | `local_src/external/Mekanism/src/main/resources/META-INF/neoforge.mods.toml:33-38`：<br>`modId="appmek"` `type="incompatible"` `reason="Requires an update to support changes to Mekanism's Chemical API"` | ①**不要把 Mekanism 版本范围写成「任意」**；照官方插件写 `[10.6,11)`（⑥.2 原文）②能力键用**注册名**而不是 Java 类（⑥.5 的官方做法）③见 R2 的三层闸门 |
| **R2** | **未装 Mekanism 时 `NoClassDefFoundError` 崩游戏**（本工程已有血泪教训） | `OptionalDeps.java:16-19`（类注释：「直接引用它们的类会在未安装时触发 `NoClassDefFoundError` 崩溃」）；对比 `RsccUltimineIntegration` 的**纯反射**先例（`OptionalDeps.java:114-126`） | **三层闸门**：①`ModList.get().isLoaded("mekanism")`（`isMekanismLoaded()` **终于有用了**）②`Class.forName("mekanism.api.chemical.IChemicalHandler")` 成功 ③只有两层都过才 `BlockCapability.createSided(...)` 并注册 `GasResourceType`。**注册时机**：与 `StorageType` 同一处（`RS_Create_Compat.java:592-597` 的 `commonSetup`），因为 RS 要求「在任何磁盘反序列化之前」（`:593` 注释）。</br>⚠️ 注意 `BlockCapability.createSided(name, Class)` 需要 `Class` ⇒ **反射拿到的 `Class<?>` 可以直接传**（`createSided` 的签名就是 `(ResourceLocation, Class)`），所以**整套能力桥都能做到编译期零依赖**。 |
| **R3** | **网络里有气体但没注册网格 mapper ⇒ 打开任意网格 NPE** | `GridResourceRepositoryMapper.java:42-45`（`requireNonNull(..., "No factory for " + resourceClass)`）+ `AbstractGridContainerMenu.java:161-165`（每次开网格构造仓库）+ `ResourceRepositoryImpl.java:142`（逐条 `mapper.apply`） | **把「注册 `GasResourceType`」与「注册 `GasGridResourceRepositoryMapper`」绑成同一个原子动作**（同一个私有方法里，先 mapper 后 type），并在启动日志里打一行自证。⚠️ 注册的 `Class` 必须**精确等于** `resource.getClass()`（`:36` 用 `==`，不是 `instanceof`） |
| **R4** | **客户端没注册 `ResourceRendering` ⇒ NPE** | `RefinedStorageClientApiImpl.java:100-105`（未注册 ⇒ `null`）；消费点如 `FluidGridResource.java:46`、`TaskCompletedToast.java:35`、`AutocraftingMonitorScreen.java:262` | 在客户端 setup 注册（`RefinedStorageClientApi.INSTANCE.registerResourceRendering(GasResource.class, ...)`，入口 `RefinedStorageClientApi.java:36-38`）；把「服务端注册表」与「客户端注册表」写在同一张表里（本文件 ⑦.1 / ⑩.2），防止只做一半 |
| **R5** | **存档兼容**：新 `ResourceType` 进入磁盘编解码路径，写错就是**不可逆**的数据安全问题 | `UniversalStorageType.java:33`（`ResourceCodecs.CODEC`）+ `ResourceCodecs.java:36-38`（dispatch） | ①**注册必须在任何磁盘反序列化之前**（RS 自己也是这么做的：`AbstractModInitializer.java:184-198`）；②`UniversalStorageType.java:131-153` 的 `ErrorTolerantListCodec` 已保证「单条失败只跳过 + WARN，不整盘报废」——**这是本仓已经做对的地方**；③`GasResourceType` 注册 id 一旦发布**不可改**；④上线前用带气体的存档做一次「加/减 GasResourceType」对比测试（**本机做不到，见 ⑫ D5**） |
| **R6** | 化学 API 的**具体方法**随版本漂（例如 `getTypeRegistryName()` 已被标记移除） | `ChemicalStack.java:399-403` `@Deprecated(forRemoval = true, since = "10.7.11")` | ①只用 `getChemicalHolder()`（`:282-285`）；②反射时做**方法签名探测**（`getChemicalTanks` / `getChemicalInTank` / `insertChemical` / `extractChemical` 是否齐全且签名匹配），**不全就整体降级为「气体不可用」**，绝不「部分可用」（部分可用会变成数据安全事件） |
| **R7** | 客户端渲染类（`MekanismRenderer` / `GuiUtils` / `ChemicalStackRenderer` / `Chemical.getTextComponent` 所在类）都在 `src/main/java`（**客户端内部**），不是 API | 文件路径即证据（`local_src/external/Mekanism/src/main/java/mekanism/client/...`） | 客户端渲染也走反射（`Class.forName` + `Method.invoke`）；失败时**退化为「只显示名称文字、不画图标」**（绝不让渲染失败崩界面） |
| **R8** | 「同类」语义：气体与流体是不是一类 | `UniversalLimitedStorage.java:145-150`（按 `getResourceType()` ⇒ 现为**不同类**） | 已经是现存行为；需用户拍板（⑫ D3） |
| **R9** | **本机无法实测** | `run/mods` 只有 8 个 jar，**无 Mekanism、无 RS 本体、无官方插件**（⑤.7 列表） | ①本机可做**编译期**验证 + 「未装 Mekanism 时的降级」验证；②真机验证需先把 Mekanism（+ 可选官方插件）放进 `run/mods`（**本任务禁止改 run/，未做**）；③按 `docs/dependency-policy.md` 的规矩，若需编译期 API 应把 jar 放 `libs/` 并登记 sha1（`dependency-policy.md` §1-§5） |
| **R10** | **与官方插件同时装会「打架」** | 两者都会往同一个 `PlatformRegistry` 注册一个「气体」`ResourceType`（我方 vs `refinedstorage_mekanism_integration:chemical`）。注册表本身能共存（`PlatformRegistry.register(ResourceLocation, T)`，id 不同） | **但两个不同 `ResourceType` ⇒ 两种互不通用的「气体资源」⇒ 同一气体在网格里会显示成两条、互相不能堆叠、自动合成也认不出对方**。⇒ **必须二选一**：⭐**缓解：探测到 `ModList.isLoaded("refinedstorage_mekanism_integration")` 时自动跳过我方的气体注册**（这是我们能完全控制的开关） |
| **R11** | `getInterfaceExportLimit()` / `getProcessingPatternLimit()` 可能与「无数量上限」冲突 | `PlatformResourceKey` 有这两个方法（`javap` 2.0.9）；官方 `ChemicalResource` 也实现了它们 | 官方实现的具体数值**未证实**（⑫ 未证实 1）。缓解：实现时**先给 `Long.MAX_VALUE`**，再按实测回调；并把「这个值到底在哪里被用来截断」列入待验证 |
| **R12** | 43~53 处 `instanceof` 类型门里可能有「气体被误当流体」 | ④.5 清单；已确认 `SequenceAssemblyExecutorBlockEntity.java:160-161` 是**安全跳过** | **逐处复核**（列入 ⑫ 未证实 5）；复核口径：「这句 if 之后，非 ItemResource 且非 FluidResource 的资源会走到哪里去？」——**必须是「像气体一样被显式跳过或不适用」，绝不能是「被当成 FluidResource 强转」** |

---

## ⑫ 未证实 / 需要用户拍板

### 12.1 未证实（我没有证据，不敢当结论）

1. **`getInterfaceExportLimit()` / `getProcessingPatternLimit()` 的实际数值语义**：官方 `ChemicalResource` / `ChemicalResourceType` 有实现（`javap` 可见），但我**没有反编译出常量值**，也**没有证据说明这两个值在 RS 内部哪里被用于截断**。⇒ 「用户要求的『无数量上限』是否会被它截断」**未证实**。
2. **「1 mB 化学 == 1 mB 流体」**：只证实了**展示口径一致**（`ChemicalStackRenderer.java:41,69,102` 用 `FluidType.BUCKET_VOLUME`=1000 与 `GENERIC_MB`），**没有**找到官方的量纲定义。
3. **Mekanism 的 GUI 贴图能否跨图集复用**：`ResourceType.getSprite()` 要求 **GUI sprite 图集**（`AbstractSideButtonWidget.java:56-62` `blitSprite`），而 Mekanism 只有 `textures/liquid|slurry|infuse_type|pigment/` 这些**方块图集**贴图（本机 assets 目录已核）。**未证实**可复用 ⇒ 保守自备一张 16×16。
4. **官方插件 v1.1.1 是否真的能在 RS 2.0.9 上跑起来**：我核对了它**使用/实现的全部** RS 接口在 2.0.9 的 `javap` 里都**逐字存在**（⑥.7 清单），但**没有实机运行**。
5. **本仓 53 处 `instanceof` 类型门里，哪些真的会看到气体**：只逐条确认了 `SequenceAssemblyExecutorBlockEntity.java:159-162`（安全跳过）。其余 **52 处需逐处复核**。
6. **`MekanismAPI.CHEMICAL_REGISTRY.getId(chemical)` 是否返回 `int`**（`GridResource.getRegistryId()` 要 `int`）：未查证，需要 `javap` `Registry#getId` 或实测。
7. **网格里显示气体是否还缺别的注册**：官方注册了 `addGridInsertionStrategyFactory` / `addGridExtractionStrategyFactory`（⑥.4 第 5、6 条），说明**至少这两样是需要的**；但「只读显示」是否也需要，**未证实**。
8. **`ChemicalStack` 的 `amount` 上限**：`ChemicalStack` 的 `amount` 是 `long`（`:443-445`），但 Mekanism 内部罐体容量普遍用 `long` 还是 `int`（`IChemicalTank.getCapacity()` 返回 `long`，`:141`）—— 我方缓存是否会有溢出风险，**未证实**。

### 12.2 需要用户拍板

- **D1（最关键，请优先回答）：气体走哪条路？**
  - **(a) 依赖官方插件**「Refined Storage - Mekanism Integration」：把它写进 `neoforge.mods.toml` 的 `type="optional"` 依赖，我方的「网络资源接收器/请求器」只按 `ResourceKey` **通用处理**（`storage.insert(ResourceKey, ...)` 已经类型无关），**不实现**气体资源类型。
    - 优点：零维护、与官方语义完全一致、已被 24,344 次下载验证（Modrinth API `downloads` 字段）、自动获得「网格显示 + 取放 + 自动合成 + 外部存储」全套。
    - 缺点：多一个前置插件；玩家不装插件时气体仍不可用。
  - **(b) 自己实现一套最小气体资源类型**（本文件 ⑦⑧⑨⑩⑪ 就是路线图）。
    - 优点：本模组自带、不依赖额外插件。
    - 缺点：维护 Mekanism 版本兼容（R1/R6）；要做 ⑨.3 的两个崩溃闸门；要复核 53 处类型门。
  - **(c) 两者都做，运行时二选一**：探测到官方插件就**禁用**我方注册（R10 的缓解）。
  - **我的建议：(a) 优先**。若用户明确要求「本模组自带、不装额外插件」，则走 (b)。
- **D2**：**这一版就做**气体，还是**先做直通**（只支持「发送端缓存 → 输出到朝向容器」，不做网格取放与自动合成）？（直通仍然需要 ⑨.3 的两个闸门，省掉的是 ⑩.2 之外的周边套件 —— 见 9.4 表）
- **D3**：`Config.universalDiskAllowMixedTypes=false` 时，**气体与流体算不算「同一类」**（现在按 `ResourceType` 算**不同类**，即不能同盘）？要不要为气体单独做一块「只装气体」的盘？
- **D4**：`FORM_GAS` 的口径要不要从「流体的一种」改成「第三种资源」？**这会改存档语义 + 需要一条旧档读档兼容（7.3 第 3 点）+ 波及其它 53 处类型门。**
- **D5**：是否允许把 Mekanism 的 jar 放进 `libs/`（按 `docs/dependency-policy.md` 的 5 步规矩登记 sha1）以获得**编译期 API**？还是**坚持纯反射**（编译期零依赖，但代码更绕、更难调试）？
  - 我倾向：**`libs/` 放 jar 只为 `compileOnly`（不 `runtimeOnly`）**，这样能拿到编译期类型检查，同时**运行时仍是可选依赖**（`neoforge.mods.toml` 里 `optional`）。这也正是 Jade 的先例（`build.gradle:195-197`）。
- **D6**：本机要不要**补 Mekanism + 官方插件到 `run/mods`** 以便实测？（**需要用户明确授权改 `run/`** —— 本任务禁了，所以我没做。）

---

## ⑬ 建议实施路线（若选 D1(b) 或 D1(c)）

> 原则：**每一步都保持「未装 Mekanism 时行为与现在完全一致」**，并且**不引入任何编译期 Mekanism 依赖**（除非 D5 批准 `libs/`）。

| Phase | 内容 | 新增类 | 崩溃风险 | 验收方式（本机可做） |
| ---- | ---- | ---- | ---- | ---- |
| **P0 探测层** | `isMekanismLoaded()` 接上真实调用点；`RsccMekanismProbe`：三层闸门（ModList → `Class.forName("mekanism.api.chemical.IChemicalHandler")` → 方法签名探测）+ 一次性日志自证 | `support/RsccMekanismProbe.java` | 无（未装时全程不注册） | 编译通过；启动日志出现一行 `[rscc-gas] mekanism=absent`；**行为零变化** |
| **P1 数据层** | `MultiGasCache`（照抄 `MultiFluidCache`，键简化为化学品注册名）+ `GasCacheEntryHandler`（可选，若要「点一下灌进玩家的化学品罐」） | `support/MultiGasCache.java`、`support/GasCacheEntryHandler.java` | 无（未被调用） | 单元级自测：`insert/extract/save/load` 容量守恒 |
| **P2 RS 资源层（★必须 3 件一起上）** | `GasResource` + `GasResourceType` + `GasGridResourceRepositoryMapper`；注册进 `getResourceTypeRegistry()` 与 `addGridResourceRepositoryMapper()`；**复用 `rs_create_compat:universal`，不新建盘** | `storage/GasResource.java`、`storage/GasResourceType.java`、`support/GasGridResourceRepositoryMapper.java` | **高**（R3：落一个就 NPE） | 必须做「注册原子性」自检：注册失败就整体回滚 + 打 ERROR |
| **P3 客户端** | `GasResourceRendering`（用 ⑩.2 的 ①(b)(c) 反射调用 Mekanism 渲染原语；失败退化为纯文字）+ `GasGridResource`（extends `AbstractGridResource<GasResource>`）+ 侧边按钮 sprite | `client/GasResourceRendering.java`、`client/GasGridResource.java`、`assets/.../gui/sprites/widget/side_button/resource_type/gas.png` | **高**（R4：不注册就 NPE） | 打开网格能看到气体条目（**需要 Mekanism 在场，本机做不到**） |
| **P4 能力桥（「发送端/接收端支持气体」的核心）** | `ChemicalCapabilityCache`（`level.getCapability(BLOCK_CAPABILITY, pos, state, be, dir)`）+ `GasInsertableStorage` / `GasExtractableStorage` + `ResourceContainerChemicalHandlerAdapter`（让我方机器对外表现为 `IChemicalHandler`）+ `ChemicalUtil` 的 `toMekanismAction(RS Action)` | 4 个类 | 中 | 方块↔方块直通（需要 Mekanism 在场实测） |
| **P5 周边（可选，对齐官方 ⑥.4 的 13 条）** | importer/exporter 策略工厂、外部存储、存储监视器策略、资源容器插入策略、自动合成 sink、recipe viewer 转换器、气体专用盘/方块 | ~12 个类 | 中 | 需要 Mekanism 在场实测 |

**建议的最小可用集**（用户说「发送端要支持所有资源包括流体和气体和物品」）：
**P0 + P1 + P2 + P3 + P4**。其中 **P2 + P3 是「气体能不能被这个网络看见」的门槛**，**P4 是「气体能不能被搬」的门槛**。P5 是「玩起来像官方插件那样完整」，可以后置。

---

## ⑭ 取证方法与可复现路径

### 14.1 用到的制品与源码树

| 用途 | 路径 / 来源 | 版本标识 |
| ---- | ---- | ---- |
| RS 2.0.9 二进制 | `C:\Users\70432\.gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar` | `gradle.properties:71` `refinedstorage_version=2.0.9` |
| RS 上游源码 | `local_src/external/RefinedStorage` | 分支 `release/2.0.10`，git `0f89121b25936f8a65821683ce2d52497df50af0`（**比 2.0.9 新一个小版本；所有 API 结论都已用 2.0.9 的 `javap` 复核**） |
| Mekanism 上游源码 | `local_src/external/Mekanism` | 分支 `1.21.x`，git `11162452affe7b17b25cde251308c9d047c42e87`；`gradle.properties`：`mod_version=10.7.19`、`minecraft_version=1.21.1`、`neo_version=21.1.200` |
| 官方插件（RS-Mekanism Integration） | 下载到 `%TEMP%\rscc_gasprobe\rsmek.jar`（**未进工作区、未进 `run/`**）<br>来源 `https://cdn.modrinth.com/data/QHMDf0Sd/versions/m5pFB06e/refinedstorage-mekanism-integration-1.1.1.jar` | v1.1.1，160,633 字节，sha1 `7d1e60d2f6301da44043ad3a017bb0563b71755a`（与 Modrinth API 一致） |
| 本仓源码 | `src/main/java/cretae/cookiewyq/rs_create_compat/**` | `mod_version=1.1.0`，`neo_version=21.1.248`，`create_version=6.0.10-280` |

### 14.2 工具与命令

```powershell
# javap（反编译 API）
& "D:\java21\bin\javap.exe" -cp "<RS 2.0.9 jar>" <全限定类名>
& "D:\java21\bin\javap.exe" -c -p -cp "<插件 jar>;<RS jar>" <类名>     # -c 看字节码（用于取证注册调用链）

# Python 直读 jar（不解压到工作区）
& "C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" -c @"
import zipfile
z = zipfile.ZipFile(r'<jar>')
print([n for n in z.namelist() if 'chemical' in n.lower()])
"@
```

### 14.3 本轮**没有**做的事（遵守任务硬约束）

- ❌ **没有改任何 Java 源码**（`git status` 应该只显示本文件为新增）
- ❌ **没有改 `run/`**
- ❌ **没有解压任何 jar 到工作区**（RS 2.0.9 的 class 列表、插件类列表全部用 `zipfile` 在内存里读）
- ❌ **没有把官方插件 jar 放进 `libs/` 或 `run/mods`**（只在 `%TEMP%` 里）
- ❌ **没有写 `.ps1`**（因此也不涉及 UTF-8 BOM 要求）
- ✅ **只新增了本文件 `docs/GAS_COMPAT_DESIGN.md`**

### 14.4 与本仓既有文档的关系

- `docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md` §3.5/§3.6/§6 已就「气体」给出一版结论，其**事实部分（RS 2.0.9 无气体、`isMekanismLoaded` 无调用点、`PlatformResourceKey` 可扩展）与本文件一致**；其**建议部分（「本版不做气体，只留接口」，§6.4）在本轮已被用户明确推翻**（用户原话：「气体现状是没有气体。但是**我要求可以兼容**」）。
- 本文件相对该文档的**增量**是：
  1. **`isMekanismLoaded` 调用点计数**（1 处定义 / **0 处调用**，与同文件另外三个同型函数对照）；
  2. **RS 官方插件**这一发现（含它 13 条注册清单的字节码原文 + 能力键重建的字节码原文 + 55 个类清单 + 与 2.0.9 的 API 逐字核对）；
  3. **Mekanism 化学 API 的完整清单**（类名/包名/方法/行号/方向/单位/废弃项）；
  4. **⑨ 的明确结论**（不需要新建气体存储节点/盘；通用盘已就绪；两个「不做就 NPE」的闸门）；
  5. **`run/mods` 无 Mekanism ⇒ 本机无法实测** 的确认；
  6. **⑩ 的复用清单**（含「侧边按钮图集不能直接复用 Mekanism 贴图」这条反例）；
  7. **⑧ 气体缓存的取舍**（为什么现在**不该**抽泛型）与 **7.3 改口径的存档兼容代价**。
