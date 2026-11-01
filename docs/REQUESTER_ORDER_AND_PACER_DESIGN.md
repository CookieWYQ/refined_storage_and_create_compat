# 请求器「订单清单」+ 数量输入 + 网格覆盖面板 + 全模组统一节拍器 —— 调研 + 设计（含 API 证据）

> 环境：Minecraft 1.21.1 / NeoForge 21.1.248，模组 `rs_create_compat`，
> Refined Storage **2.0.9**（`refinedstorage-neoforge-2.0.9.jar`），Create **6.0.10-280**。
> 本轮**只调研 + 只出设计**：未改任何 `.java`，未改 `run/`，新增文件只有本文档与 `_audit_tmp/pacer/` 下的临时取证产物。
> 所有结论都带「类名 + `javap` 原文」或「文件:行号」；查不到的一律写「**未证实**」。

---

## ⓿ 结论摘要（先看这 12 行）

1. **订单清单**：Create 的 `PackageOrder` 本身就是 `List<BigItemStack>`（**无上限的 record**），但红石请求器的**界面/容器把它定死成 9 条**（`RedstoneRequesterScreen.java:44-45`、`RedstoneRequesterMenu.java:41,69-70`）。我们照「record 用 list + UI 定上限」这个形状，但上限**不抄 9**，见 §1.5。
2. **清单序列化有现成先例，不用自己发明**：`ResourceCodecs.CODEC`（`Codec<PlatformResourceKey>`）与 `ResourceCodecs.AMOUNT_CODEC`（`Codec<ResourceAmount>`）都是 **public**，工程已经在 `storage/UniversalStorageType.java:33,62-77` 用 `RecordCodecBuilder` + `{resource, amount}` 列表落盘。**物品与流体走同一个 codec**，零分支。
3. **数量输入**：`client/widget/RsccNumberField.java` **不是控件**，是一个 `public final` + 私有构造的**静态工具类**（`:30,51-52`），提供 `wire(EditBox, LongConsumer)`（`:348`）——设置 `MAX_LENGTH=20`、把文本 → long、支持千分位 / `1e8` / `b`(桶) 后缀，**不支持负数**（`:280-282`）、**没有滚轮**、**上限钳到 `Integer.MAX_VALUE`**（`:283`）。
4. **数量输入推荐**：「每条一个 `RsccNumberField.wire()` 的 EditBox」+「滚轮在条目上 ±1 / Shift ±10」+「Ctrl+V 粘贴 `1000b`」。理由是它同时满足「物品=个数」与「流体=mB」两套口径，且解析口径全工程只有一份。
5. **流体单位口径（有铁证）**：RS 内部流体量就是 **mB**，`PlatformImpl.getBucketAmount()` 字节码返回 `1000L`；`FluidResourceType.normalizeAmount(d) = (long)(d * 1000)`、`getDisplayAmount(mB) = mB / 1000.0` ⇒ **RS 自己的口径 = 存 mB / 显示桶**。工程既有 `RsccNumberField.buckets/bucketHint/bucketTooltip`（`:384,394,412`）与之一致。
6. **网格覆盖面板：可行，而且不会被盖住**。`AbstractBaseScreen.init()` / `AbstractStretchingScreen.init()` / `init(int)` 全部是 **`protected` 非 final**；`addRenderableWidget` RS 自己在 `AbstractGridScreen.init(int)` 里就用了（字节码偏移 320）。渲染顺序已逐层证实：**背景 → 网格行 → 我们 add 的 widget → 槽位 → 标签 → tooltip**（见 §3.3）。
7. **两个"例外"要记住**：`ScrollbarWidget` 与 `GridSearchBoxWidget` 是在 widget 之后**手动 render** 的（`AbstractStretchingScreen.render` 偏移 9-28、`AbstractGridScreen.render` 偏移 9-27）⇒ 面板**别压在这两处**。
8. **只选不取：覆写 2 个方法就够，不是 3 个**。`public mouseClicked(double,double,int)` + `public mouseScrolled(double,double,double,double)`；`mouseReleased` 完全不用管（`AbstractGridScreen` 没覆写它，`AbstractStretchingScreen.mouseReleased` 只碰滚动条）。**证据链已打到包级**：`GridResource.onExtract` → `ClientGridExtractionStrategy.onExtract` → `C2SPackets.sendGridExtract`，而 `mouseClickedInGrid` / `mouseScrolledInGrid` 在 `AbstractGridScreen` 里**各只有一个调用点**（字节码 1344 / 1557）。
9. **流体分页**：RS 网格靠侧边按钮 `ResourceTypeSideButtonWidget` 调 `AbstractGridContainerMenu.toggleResourceType()`（两者都是 **包私有**，我们 new 不了，但**基类 `init(int)` 已经免费帮我们加了**）。当前这一条是物品还是流体，用 `GridResource.getResourceForRecipeMods()` → `PlatformResourceKey.getResourceType()` 与 `ResourceTypes.ITEM` / `ResourceTypes.FLUID` 比对即可（`ResourceTypes` 的两个字段是 **public static final**）。
10. **已选高亮**：RS **没有任何"已选"视觉**，只有悬停高亮（`AbstractGridScreen.renderCell` 里 `renderSlotHighlight(g,x,y,0)`）与可合成/空槽底色。但 `protected void renderRows(GuiGraphics,int,int,int,int,int,int)` **非 final**，我们可以在 `super.renderRows(...)` 之后自绘；且 `public static void renderSlotBackground(GuiGraphics,int,int,boolean,int)` 是公开的现成底色工具（字节码 761）。几何公式见 §3.6。
11. **节拍器的核心事实**：RS 自己的公式是 `workTickRate = defaultWorkTickRate - speedAmount*2`（`UpgradeContainer.notifyListener` 字节码 33-41，`defaultWorkTickRate` 出厂 = `9`），**`n=5` 得 `-1`、`n=6` 得 `-3`** ⇒ 本工程上限 6（`menu/UpgradeSlot.java:29` `MAX_PER_UPGRADE = 6`），**绝不能用 RS 的 ticker**——用户的判断被字节码证实。
12. **清点结果（重要）**：全模组真正吃速度升级的只有 **4 个方块实体、6 处公式**，而且**口径分三套**（倒数 / 线性 / 指数）；另外发现 **3 个既有缺陷**：充电器 `<<` **会溢出成负数**、`quantityKeeperDestroyRatePerSpeed` 是**死配置项**、`sequence_assembly_executor` **注册了速度升级×6 但既没有槽也没有读取代码**。详见 §4。

---

## ① 订单清单（解决「每选一个资源开一次界面」）

### 1.1 用户要什么（原话复位）

> 「我们每选一个资源就必须要打开一次界面吗这不很麻烦吗」
> 「要记住我说的是**所有资源包括流体资源**，气体资源先不管，但是它一定要能用流体资源」

⇒ 需求 = **一次界面会话里能连着选多条**，每条 = 「资源 + 数量」，并且一个方块的**目标名字**（发给谁）也要能设。

### 1.2 Create 的对照（**这是任务要求的第一件事**）

取证来源：`D:\gradle\caches\modules-2\files-2.1\com.simibubi.create\create-1.21.1\6.0.10-280\23e1219501c0debfa0bb56c30ef8e0193341aae5\create-1.21.1-6.0.10-280-sources.jar`（Create 6.0.10 官方 sources，行号即真实行号）。

#### A. 数据模型的「订单」= 一个 list，record 本身**不设上限**

`com/simibubi/create/content/logistics/stockTicker/PackageOrder.java`（全文 29 行）：

```java
14: public record PackageOrder(List<BigItemStack> stacks) {
15: 	public static final Codec<PackageOrder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
16: 		Codec.list(BigItemStack.CODEC).fieldOf("entries").forGetter(PackageOrder::stacks)
17: 	).apply(instance, PackageOrder::new));
19: 	public static final StreamCodec<RegistryFriendlyByteBuf, PackageOrder> STREAM_CODEC =
19:      CatnipStreamCodecBuilders.list(BigItemStack.STREAM_CODEC)
20: 		.map(PackageOrder::new, PackageOrder::stacks);
```

`com/simibubi/create/content/logistics/BigItemStack.java:17-27`：

```java
17: public class BigItemStack {
18: 	public static final Codec<BigItemStack> CODEC = RecordCodecBuilder.create(i -> i.group(
19: 		ItemStack.OPTIONAL_CODEC.fieldOf("item_stack").forGetter(s -> s.stack),
20: 		ExtraCodecs.NON_NEGATIVE_INT.fieldOf("count").forGetter(s -> s.count)
21: 	).apply(i, BigItemStack::new));
23: 	public static final StreamCodec<RegistryFriendlyByteBuf, BigItemStack> STREAM_CODEC = StreamCodec.composite(
24: 		ItemStack.OPTIONAL_STREAM_CODEC, s -> s.stack,
25: 		ByteBufCodecs.VAR_INT, s -> s.count,
26: 		BigItemStack::new);
29: 	public static final int INF = 1_000_000_000;
```

⇒ **一条 = `{item_stack, count}`；一张订单 = `entries: [ ... ]`。数量字段是 `NON_NEGATIVE_INT`（有"非负"约束），`INF = 1e9` 表示"无限"。**

#### B. 「一张订单能放几条」——**record 不限，界面/容器限成 9**

| 位置 | 事实 | 行号 |
|---|---|---|
| 幽灵槽容器 | `new ItemStackHandler(9)` | `RedstoneRequesterMenu.java:41` |
| 界面槽位 | `for (int i = 0; i < 9; i++) addSlot(new SorterProofSlot(ghostInventory, i, slotX + 20 * i, slotY));` | `RedstoneRequesterMenu.java:69-70` |
| 客户端数量数组 | `for (int i = 0; i < 9; i++) amounts.add(1);` | `RedstoneRequesterScreen.java:44-45` |
| 存回方块 | 逐槽扫描 `ghostInventory.getSlots()` 只写非空的 | `RedstoneRequesterMenu.java:74-89` |

⇒ **Create 的"订单条数上限"= 9，而且这个 9 是硬编码在菜单与屏幕里的字面量，不是常量、不是配置项。**（`PackageOrder` 自己可以装更多——库存请求器 StockTicker 那条路径就会用它装更多条。）

#### C. 数量怎么存、怎么改、怎么同步（**对照价值最高的部分**）

| 环节 | Create 的做法 | 行号 |
|---|---|---|
| 数量住在哪 | **只在客户端**：`private List<Integer> amounts`（屏幕字段），方块实体里存的是 `PackageOrder.stacks().get(i).count` | `RedstoneRequesterScreen.java:36,47-49` |
| 改数量 | **滚轮**：`amounts.set(i, Mth.clamp((int)(amounts.get(i) + Math.signum(scrollY) * (hasShiftDown() ? 10 : 1)), 1, 256))` | `RedstoneRequesterScreen.java:181-192` |
| 数量上下限 | **`1 .. 256`（硬编码）**，Shift 一次 ±10 | 同上 `:189` |
| 数量怎么显示 | `graphics.renderItemDecorations(font, itemStack, inputX, inputY, "" + amounts.get(i))`（原版物品堆叠数字） | `RedstoneRequesterScreen.java:139` |
| 数量怎么同步 | `RedstoneRequesterConfigurationPacket(pos, address, allowPartial, List<Integer> amounts)`，网络编码 `CatnipStreamCodecBuilders.list(ByteBufCodecs.INT)` | `RedstoneRequesterConfigurationPacket.java:19-25,29` |
| 服务端怎么落地 | `for (int i = 0; i < stacks.size() && i < amounts.size(); i++) { if (!stack.isEmpty()) stacks.set(i, new BigItemStack(stack, amounts.get(i))); }` | `RedstoneRequesterConfigurationPacket.java:45-56` |
| 目标名字 | `AddressEditBox(this, font, x+55, y+68, 110, 10, false)`，值存方块实体的 `encodedTargetAdress`（String） | `RedstoneRequesterScreen.java:73-77`；`RedstoneRequesterConfigurationPacket.java:21,46` |

#### D. Create 的**局限**（我们要避开的点，都有行号）

1. **数量改不动**：`applySettings` 只对 **`stacks` 里已存在的下标**套用新数量（`:48-51`），而 `stacks` 来自幽灵槽里已放的物品。**只滚数量、不放物品 ⇒ 什么都不会变**。
2. **数量上限 256**（`:189`）：对流体（mB）完全不够用——256 mB = 0.256 桶。
3. **数量口径只有"个数"**：`BigItemStack.count` 是 `VAR_INT` + `NON_NEGATIVE_INT`，**装不了"桶/mB"这种量级**（`BigItemStack.java:20,25`）。
4. **只有物品**：`ItemStack.OPTIONAL_CODEC`（`BigItemStack.java:19`）⇒ **Create 的订单系统结构上不支持流体**。

⇒ **结论：Create 只能当"形状参照"（record + list + 侧名字框 + 滚轮量），"能力参照"必须自己设计——这正是用户说"这个设置物品的量我也没想好该怎么整"的根因。**

### 1.3 我们的数据模型（建议）

```java
// 落点建议：support/RsccOrderList.java（新文件，本轮不写）
public final class RsccOrderList {
    /** 一条 = 资源 + 数量。amount 的口径：物品=个数；流体=mB（与 RS 一致，见 §2.5）。 */
    public record Entry(PlatformResourceKey resource, long amount) {}
    // 外加一个方块级字段：String targetName（目标名字/地址）
}
```

关键设计点与证据：

| 设计点 | 选择 | 证据 |
|---|---|---|
| 资源键类型 | `PlatformResourceKey`（**不是** `ItemResource`） | `ResourceCodecs.CODEC` 的泛型就是它（`javap`: `public static final Codec<PlatformResourceKey> CODEC`）；工程 `storage/UniversalStorageType.java:33` 同样用它；`support/AssemblyWatchdog.java:14,299,1088` 已经把"物品/流体同一条记录路径"跑通过 |
| 数量类型 | `long` | `ResourceAmount` 是 record `(ResourceKey resource, long amount)`（`javap -p` 原文）；`ResourceCodecs.AMOUNT_CODEC` 里 amount 字段是 `Codec.LONG`（字节码 `19: getstatic Codec.LONG; 22: ldc "amount"`） |
| 一条一个资源还是可重复 | **允许重复**（同资源多条目 → 由写入端在 `addEntry` 时**合并**，避免"看起来两条实际是一条"） | Create 的 `RedstoneRequesterMenu.allowRepeats()` 返回 `true`（`:48-51`）——它是**允许**重复的；我们反向收敛更安全，但这属于决策，见 §⑤ |

### 1.4 序列化方案（**可直接落地，有先例**）

#### A. 落盘（NBT）：照抄 `UniversalStorageType` 的形状，连"容错列表"一起抄

先例全文（`src/main/java/cretae/cookiewyq/rs_create_compat/storage/UniversalStorageType.java`）：

```java
33:     private static final Codec<PlatformResourceKey> RESOURCE_CODEC = ResourceCodecs.CODEC;
56:     public MapCodec<SerializableStorage> getMapCodec(final Runnable listener) {
62:         final Codec<UniversalStorageData.UniversalStorageResource> resourceCodec =
63:             RecordCodecBuilder.create(instance -> instance.group(
64:                 RESOURCE_CODEC.fieldOf("resource").forGetter(UniversalStorageData.UniversalStorageResource::resource),
65:                 Codec.LONG.fieldOf("amount").forGetter(UniversalStorageData.UniversalStorageResource::amount),
66:                 ...
68:             ).apply(instance, UniversalStorageData.UniversalStorageResource::new));
70:         return RecordCodecBuilder.<UniversalStorageData>mapCodec(instance -> instance.group(
71:             Codec.optionalField("capacity", Codec.LONG, false).forGetter(UniversalStorageData::capacity),
72:             new ErrorTolerantListCodec<>(resourceCodec).fieldOf("resources").forGetter(UniversalStorageData::resources)
73:         ).apply(instance, UniversalStorageData::new)).xmap(...);
131:     private static final class ErrorTolerantListCodec<T> implements Codec<List<T>> {
    // :128 注释：单条解码失败时跳过并记录日志（对齐 RS 原版 ErrorHandlingListCodec）
```

**更省事的等价写法（我更推荐）**——RS 已经把"资源 + 数量"打包成 `ResourceAmount` 了，直接用它的 codec：

```java
// 建议落点：support/RsccOrderCodecs.java（新文件）
public static final Codec<List<ResourceAmount>> CODEC =
    ResourceCodecs.AMOUNT_CODEC.listOf();          // ← 一份 codec 同时吃物品与流体
```

`ResourceCodecs.AMOUNT_CODEC` 的内部形状（`javap -c` 于 `com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs`，`lambda$static$3`）：

```
  1: getstatic CODEC                      // Codec<PlatformResourceKey>
  4: ldc "resource"  → Codec.fieldOf("resource")
 19: getstatic Codec.LONG
 22: ldc "amount"    → PrimitiveCodec.fieldOf("amount")
 37: RecordCodecBuilder$Instance.group(...)
```

⇒ **NBT 形状 = `{ resource: {...}, amount: <long> }`，由 RS 自己保证 `ItemResource` / `FluidResource` 两种 `MapCodec` 都能解。** `FluidResourceType.getMapCodec()` 字节码返回 `ResourceCodecs.FLUID_MAP_CODEC`、`ItemResourceType.getMapCodec()` 返回 `ITEM_MAP_CODEC`（两者 javap 原文已核）。

落到 `BlockEntity.saveAdditional` / `loadAdditional` 时：
- `saveAdditional`：`CODEC.encodeStart(NbtOps.INSTANCE, entries).result().ifPresent(t -> tag.put("Orders", t));`
- `loadAdditional`：`CODEC.parse(NbtOps.INSTANCE, tag.get("Orders")).result().ifPresent(...)`
- 缺字段 / 解不开 ⇒ **保持空清单**（绝不抛异常、绝不静默改成别的资源）；这一条要写进实现契约，且 `ErrorTolerantListCodec` 那种"单条坏跳过、其余保留"的做法已经在工程里存在，可以直接复用/借鉴（`UniversalStorageType.java:128-165`）。

#### B. 网络同步：`AMOUNT_STREAM_CODEC` + `ByteBufCodecs.list(max)` —— **上限在这里就天然强制**

- `ResourceCodecs.AMOUNT_STREAM_CODEC`：`javap` 原文 `public static final StreamCodec<RegistryFriendlyByteBuf, ResourceAmount> AMOUNT_STREAM_CODEC;`，其编码体（`lambda$static$4` 字节码）= `CODEC.encode(buf, platformKey)` + `buf.writeLong(amount)` ⇒ **一条 = 资源 + 一个 long**。
- 列表：`ByteBufCodecs.list(int maxSize)`（`[sources] net/minecraft/network/codec/ByteBufCodecs.java:384,388`）返回 `StreamCodec.CodecOperation<B,V,List<V>>`，用 `.apply(...)` 收口：

```java
public static final StreamCodec<RegistryFriendlyByteBuf, List<ResourceAmount>> STREAM_CODEC =
    ResourceCodecs.AMOUNT_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES));
```

- 工程既有同类先例（资源进自定义包）：`network/SyncAssemblyAlertsPacket.java:156,180-181` 用 `ResourceCodecs.STREAM_CODEC.encode/decode`；文件 `:85-95` 的注释明确写了"物品 / 流体两端对称"。
- **注意**：新增 `TYPE` 的包类必须在 `RS_Create_Compat.registerPayloads`（`RS_Create_Compat.java:650` 附近）注册，否则被 `tools/check_payload_registration.py` 判红（该自检的存在见 `docs/RESOURCE_PICKER_DESIGN.md` §5.2 第 2 行）。

### 1.5 清单上限几条？——**建议 16，硬上限写成一个常量**

理由（逐条都是可核对的约束，不是口味）：

| 约束 | 数值 | 来源 |
|---|---|---|
| Create 的对照上限 | 9 | `RedstoneRequesterMenu.java:41,69-70` |
| 本工程"单种升级上限"的既有习惯写法 | 6（`UpgradeSlot.MAX_PER_UPGRADE`） | `menu/UpgradeSlot.java:29` |
| RS 网格一屏能显示 | 9 列 × 可见行数；行数由拉伸决定 | `AbstractGridScreen.renderRow` 字节码 `bipush 9`（列），`AbstractStretchingScreen.init` 里 `visibleRows` 动态算（偏移 0-5） |
| 网络包安全上限 | 必须传 `ByteBufCodecs.list(max)`，否则恶意包可撑爆内存 | `ByteBufCodecs.java:388` |
| 一个方块界面的可读性 | 每条至少要 16×16 图标 + 数量文本 + 删除按钮 ≈ 60px 宽；192px 宽的 RS 风格界面放 3 列 × 5 行 = 15 条较舒适 | 工程既有界面宽度（`RsccKeeperGeometry.java:94` 一类几何常量） |

⇒ **建议 `RsccOrderList.MAX_ENTRIES = 16`**：
- 明显多于 Create 的 9（用户抱怨的就是"每次只能一条"，多点余量）；
- 16 条 × 每条 ≥ 24 字节 ≈ 400 字节 NBT/包，完全不是负担；
- **写成一个常量 + 网络端的 `ByteBufCodecs.list(MAX_ENTRIES)` + NBT 端的 `listOf()` 后手动截断**，三处同源；
- 超过上限时**拒绝新增并回一条可见提示**，绝不静默丢弃。

> 需要用户拍板：16 是否够（例如"我要一次请求 30 种材料"）。见 §⑤。

### 1.6 清单怎么增 / 删 / 改（界面操作）

| 操作 | 入口 | 为什么这么设计 |
|---|---|---|
| **增** | 订单界面里「+ 添加」→ 打开 RS 网格子界面（`docs/RESOURCE_PICKER_DESIGN.md` 的 D1）→ 左键点资源 = 追加一条（**界面不关**，可连点连加） | 直接回答用户「每选一个资源就必须要打开一次界面吗这不很麻烦吗」；`AbstractGridScreen.mouseClicked` 覆写点允许"选中即生效、界面不关" |
| **加的时候数量给默认值** | 物品默认 `1`；流体默认 `1000`（= 1 桶） | 理由：`1000` 正好是一桶，玩家最容易接受的第一档；物品 1 个是最小合法值 |
| **改数量** | 条目上的数字框（§2 的主方案），或悬停条目滚轮 ±1 / Shift ±10 | 与 Create 的滚轮一致，但**不抄 256 上限** |
| **删** | 条目右侧一个小 × 按钮；或 `Shift+左键` 在网格里点同一资源 = 取消选择 | 双通道：界面内直接删、网格里反向取消 |
| **改资源** | 先删再加（**不做"替换"**） | 避免"点了替换却把数量也重置了"这类隐蔽副作用 |
| **重排** | **本轮不做** | 清单顺序对请求语义无影响（请求是集合语义），加拖拽排序是纯成本 |

---

## ② 数量的输入（用户没想好，这里给方案）

### 2.1 工程既有的数字输入实现：`client/widget/RsccNumberField.java`

**先纠正一个说法：它不是"控件"，是"静态工具类 + 一个接线函数"。**

`src/main/java/cretae/cookiewyq/rs_create_compat/client/widget/RsccNumberField.java`：

```java
30: public final class RsccNumberField {
32:     public static final int MAX_LENGTH = 20;
34:     public static final char GROUP_SEPARATOR = ',';
37:     private static final long MAX_VALUE = Integer.MAX_VALUE;
51:     private RsccNumberField() {          // ← 私有构造，不能 new
52:     }
```

⇒ **构造参数：没有**（不是控件，无法 `new`）。它只做两件事：`文本 → 数值`、`数值 → 展示文本`，以及把这两件事接进一个外部 `EditBox`。

#### 能力清点（逐条给行号）

| 能力 | 有/无 | 证据 |
|---|---|---|
| 超长保护 | **有**，`setMaxLength(MAX_LENGTH=20)` | `:32,349` |
| 千分位 | **有**，`grouped()` / `ungrouped()`，分隔符 `,`，且**只接受严格千分位**（`\d{1,3}(?:,\d{3})+`） | `:48-49,106-144` |
| 科学计数 | **有**，`1e8` / `1.5e8`，指数上限 18 | `:41,237-249` |
| 单位后缀 | **有**：`b/B`=1000、`m/M`=1、`k/K`=1000、`g/G`=1e9、`t/T`=1e12 | `:360-371` |
| 负数 | **不支持**（解析出 `<0` 直接判非法） | `:280-282` |
| 上限 | 钳到 `Integer.MAX_VALUE`（`Math.min(MAX_VALUE, value)`） | `:37,283` |
| 溢出保护 | **有**，乘之前先判 `value > MAX_FACTORED / factor` ⇒ 判非法而不是回绕 | `:39,268-279` |
| **滚轮** | **没有**（整个类里没有任何 `mouseScrolled`；全文 424 行已通读） | — |
| 显示宽度自适应 | **有**，`display(value,widthPx)`：放得下千分位才用千分位，否则退回纯数字 | `:181-185` |
| 桶换算 | **有**，`buckets(mB) = mB/1000`、`bucketHint()`、`bucketTooltip()` | `:384,394,412` |
| 接线唯一入口 | **有**，`wire(EditBox box, LongConsumer onValue)` | `:348-357` |
| 输入中态容错 | **有**，`parseSanitized`：整串非法但以数字开头 ⇒ 退回数字部分；空串/非数字开头 ⇒ `ok=false`（调用方保持原值、**不发包**） | `:310-319` |
| long→int 安全夹取 | **有**，`clampToInt(long)` | `:420-423` |
| 文本区可用像素宽 | **有**，`textWindow(boxWidth) = max(0, boxWidth-9)` | `:94-96` |

#### 既有调用点（**全部**，`RsccNumberField` 只有 3 个文件提到）

| 文件:行 | 用途 |
|---|---|
| `client/widget/RsccKeeperGeometry.java:29,61,94` | 只作**几何常量引用**：注释里锁死"输入框文本区 = `textWindow(boxWidth)` = 61px"，供自检断言 |
| `client/screen/QuantityKeeperScreen.java:70,84,85,87,145,159,192,258,300` | **基础定量保持器**的目标数量输入框：`textWindow(BASIC_BOX_W)`→`editable()` 回填→`wire()` 接线→解析→`clampToInt`→发 `containerId,row,value`；`:258` 服务端回填；`:300` `bucketTooltip` |
| `client/screen/AdvancedQuantityKeeperScreen.java:144,146,210,219,234,254,402,479` | **高级定量保持器**（4 槽）：同上，每槽一个独立输入框；`:254` 发 `menu.containerId, row, clampToInt(value)`；`:479` `bucketTooltip` |

典型的接线代码（`QuantityKeeperScreen.java:84-87`）：

```java
84:         box.setValue(RsccNumberField.editable(menu.getTargetAmount(), textWindow()));
85:         // 解析口径只有一份实现（RsccNumberField.wire）：千分位 / 后缀 / 科学计数都在里面，
87:         RsccNumberField.wire(box, value -> { ... });
```

⇒ **结论：工程里"一个条目配一个数字框"这件事已经做过两遍（基础 1 个、高级 4 个），且在服务端回填、解析、clamp、tooltip 这四个环节全部收口到同一个类。请求器照抄即可，零新逻辑。**

### 2.2 RS 自己的做法（**注意：RS 走的是"点槽位 → 弹一个小界面"，正是用户嫌麻烦的那条路**）

`javap -p` 于 `com.refinedmods.refinedstorage.common.support.amount.*`：

```
public abstract class AbstractAmountScreen<T extends AbstractContainerMenu, N extends Number>
        extends AbstractBaseScreen<T> {
  protected ActionButton confirmButton;
  protected ActionButton cancelButton;
  protected net.minecraft.client.gui.components.EditBox amountField;   // ← 真·EditBox
  protected abstract boolean confirm(N);
  public boolean mouseScrolled(double, double, double, double);         // ← 有滚轮
  protected final void updateAmount(N);
  protected final Optional<N> getAndValidateAmount();
}

public final class AmountScreenConfiguration<T extends Number> {
  private final T initialAmount;
  private final int[] incrementsTop;          // ← 上排快捷增量按钮（如 1/10/64/...）
  private final int[] incrementsBottom;
  private final int amountFieldWidth;
  private final boolean actionButtonsEnabled;
  private final Supplier<T> minAmount;
  private final T maxAmount;
  private final T resetAmount;
  ...
}

public class ResourceAmountScreen
        extends AbstractAmountScreen<ResourceAmountScreen$SingleResourceContainerMenu, Double> {
  public ResourceAmountScreen(Screen parent, Inventory, ResourceSlot slot);
}
```

打开入口（`javap -c` 于 `AbstractBaseScreen`）：

```
private boolean tryOpenResourceAmountScreen(ResourceSlot slot);   // 字节码 668
   0: slot.getResource() != null  → flag
  17: slot.canModifyAmount()
  30: Screen.hasShiftDown() == false
  43: menu.getCarried().isEmpty()
  → 四条件齐备才 minecraft.setScreen(createResourceAmountScreen(slot))

protected Screen createResourceAmountScreen(ResourceSlot slot);   // 字节码 723
   0: new ResourceAmountScreen(this /*parent*/, playerInventory, slot)
```

⇒ **RS 的既有范式 = 「点击资源槽 → `setScreen` 一个独立的数量小界面 → 确认返回」**。这**恰好是用户抱怨的交互**（"每选一个资源就必须要打开一次界面吗"）。所以：**不要在清单里复用 `ResourceAmountScreen`**（它还会再开一层界面）。但可以复用它的**两个具名常量**：`AmountScreenConfiguration.minAmount/maxAmount/incrementsTop` 这套"上下限 + 快捷增量"的语义值得抄进我们自己的条目控件。

### 2.3 三个方案对比

| | 方案 A：每条一个数字框（推荐） | 方案 B：滚轮 + Shift 加速（Create 式） | 方案 C：剪贴板粘贴 |
|---|---|---|---|
| 形态 | 每条目一个 `EditBox`，用 `RsccNumberField.wire()` 接线 | 悬停条目滚轮 ±1，Shift 滚轮 ±10 | 条目上 `Ctrl+V`，文本走 `RsccNumberField.parseSanitized` |
| 大数（1e8 mB = 100,000 桶） | ✅ 直接输 `100000000` / `1e8` / `100000b` | ❌ 滚到天荒地老（Create 因此把上限钉在 256） | ✅ 一次到位 |
| 小数/精度 | ✅ 精确到 1 mB | ❌ 只能整数步进 | ✅ |
| 流体适用 | ✅ | ❌（256 mB 只有 0.256 桶） | ✅ |
| 实现成本 | 中（但工程已有 4 个实例的成熟写法） | 低 | 低 |
| 误操作风险 | 低（非法输入**不发包**，`parseSanitized` 保证） | 中（滚轮容易滑过头） | 低 |
| 既有证据 | `QuantityKeeperScreen:84-87`、`AdvancedQuantityKeeperScreen:144-146,234` | Create `RedstoneRequesterScreen.java:181-192`（上限 256 的教训） | 无（新做，但解析已有） |
| 批量场景 | 逐条输 | 逐条滚 | ✅ 一次粘一整行 |

#### 推荐：**A 为主，B 为辅，C 作为加速通道**

- **A（必须）**：每条一个 `EditBox` + `RsccNumberField.wire()`。这是唯一能同时表达「物品=个数」与「流体=mB」的输入方式，而且工程已有 5 个可用实例（1 基础 + 4 高级）可照抄。
- **B（可选加分）**：条目悬停时滚轮 ±1 / `Shift` ±10 —— 但**不采纳 Create 的 `clamp(...,1,256)`**，改为 `clamp(...,1,maxForResource)`。
- **C（可选加分）**：`Ctrl+V` 走 `parseSanitized`，支持 `1000b` / `1e8` / `64,000` 三种写法（`RsccNumberField` 已全部支持，见 §2.1 表）。可用于"一次给多条填同一个数"。
- **不做**：每条一个滑块、每条 +/- 按钮阵列（`AmountScreenConfiguration.incrementsTop` 那套）——条数一多就会把界面塞满。

> 备注：如果用户希望"通用性优先"，可以把 A 做成一个可复用小控件 `client/widget/RsccAmountBox.java`（包一层 `EditBox` + `wire()` + `bucketHint` 渲染），清单里每行实例化一个。这属于实现细节，不改变方案选择。

### 2.4 单位口径（**物品 vs 流体，逐条给证据**）

#### A. RS 内部：流体就是 mB；"桶"只是显示

`javap -p -c` 于 `com.refinedmods.refinedstorage.neoforge.PlatformImpl`：

```
public long getBucketAmount();
    Code:
       0: ldc2_w  #46   // long 1000l
       3: lreturn
```

`javap -p -c` 于 `com.refinedmods.refinedstorage.common.support.resource.FluidResourceType`：

```
public long normalizeAmount(double);        // 「显示单位 → 存储单位」
   0: dload_1
   1: getstatic Platform.INSTANCE
   4: invokeinterface Platform.getBucketAmount:()J     // = 1000
   9: l2d ; 10: dmul ; 11: d2l                          // (long)(d * 1000)
public double getDisplayAmount(long);       // 「存储单位 → 显示单位」
   0: lload_1 ; 1: l2d
   2: getstatic Platform.INSTANCE
   5: invokeinterface Platform.getBucketAmount:()J
  10: l2d ; 11: ddiv                                     // amount / 1000.0
public long getInterfaceExportLimit();
   0: ... getBucketAmount() ; 11: lmul(long 16)          // 16 桶
```

`javap -p -c` 于 `ItemResourceType`：

```
public long normalizeAmount(double);   → d2l          （个数原样）
public double getDisplayAmount(long);  → l2d          （个数原样）
public long getInterfaceExportLimit(); → ldc2_w 64l   （64 个）
```

`ResourceAmount`（`javap -p` 原文）：

```
public final class ...ResourceAmount extends Record {
  private final ResourceKey resource;
  private final long amount;
  public ResourceAmount(ResourceKey, long);
  public static void validate(ResourceKey, long);
}
```

⇒ **口径结论（可直接写进实现契约）**：

| | 存（NBT / 网络 / 内部） | 显示 |
|---|---|---|
| 物品 | `long` 个 | 个（可加千分位 / 紧凑写法） |
| 流体 | `long` **mB** | **桶**（`mB/1000`），另附原始 mB |
| 换算 | `1 桶 = 1000 mB`（`PlatformImpl.getBucketAmount()` 常量 1000） | 同 |

#### B. 工程既有口径与 RS **完全一致**

`client/widget/RsccNumberField.java`：

```java
27:  * {@code 1 桶 = 1000 mB}（NeoForge {@code FluidType.BUCKET_VOLUME}）...
102:     public static String raw(final long value) { return Long.toString(Math.max(0L, value)); }
362:             case 'b', 'B' -> 1000L;     // mB 家族：b = 桶 = 1000 mB
365:             case 'm', 'M' -> 1L;
384:     public static long buckets(final long mB) { return Math.max(0L, mB) / 1000L; }
394:     public static String bucketHint(final long mB, final int widthPx) { ... "= " + grouped(buckets(mB)) + " 桶" }
412:     public static String bucketTooltip(final long mB) { ... grouped(mB) + " mB = " + grouped(buckets(mB)) + " 桶" }
```

`tools/selfcheck_round50_keeper_fluid_display.py:13-16` 明确把这条口径当断言的一部分：

```
13: ② 大数值显示不全（1 亿 mB 显示成一串 0）
14:    —— 判据：输入框几何（RsccKeeperGeometry）保证文本区放得下 `100,000,000` 的千分位写法；
15:       显示 / 解析走唯一实现 RsccNumberField；边界用例 0 / 1 / 1000 / 100000000 / Long.MAX_VALUE
```

⇒ **请求器的清单只有一条路：内部一律 `long`（物品个数 / 流体 mB），显示层用 `RsccNumberField.bucketHint` 区分。** 不要引入第二套换算。

### 2.5 界面怎么区分物品与流体

三层都要有，缺一层玩家就会看错：

| 层 | 做法 | 可依据的 API/证据 |
|---|---|---|
| ① 图标 | 直接用 RS 给的渲染：`GridResource.render(GuiGraphics,int,int)`（`GridResource` 接口 public 方法） | `javap -p` 于 `GridResource`：`public abstract void render(GuiGraphics,int,int)`；`FluidGridResource` 与 `ItemGridResource` 各自实现 |
| ② 单位后缀/副文本 | 物品：`<n> 个`；流体：`<mB> mB（= N 桶）` —— 直接调 `RsccNumberField.bucketHint/bucketTooltip` | `RsccNumberField.java:394,412` |
| ③ 类型徽标 | 在条目角落画一个"桶/方块"小图标 | 资源可取自 `ResourceTypes.FLUID.getSprite()` / `ResourceTypes.ITEM.getSprite()`（`ResourceType.getSprite()` 是 public；两个单例是 `public static final`），贴图在 RS jar 里：`assets/refinedstorage/textures/gui/sprites/widget/side_button/resource_type/fluid.png`、`.../item.png`（`jar tf` 已确认存在） |

**判定某一条是物品还是流体（代码级，唯一正确写法）：**

```java
PlatformResourceKey key = gridResource.getResourceForRecipeMods();  // ← GridResource 的公开方法
ResourceType type = key.getResourceType();                          // ← PlatformResourceKey 的公开方法
boolean isFluid = (type == ResourceTypes.FLUID);
```

证据：
- `GridResource` 接口（`javap -p`）`public abstract PlatformResourceKey getResourceForRecipeMods();`
- `AbstractGridResource.getResourceForRecipeMods()` 字节码：`0: aload_0; 1: getfield resource; 4: areturn` ⇒ **原样返回底层资源键**（`AbstractGridResource` 的 `protected final T resource` 我们跨包访问不到，必须走这个方法）
- `PlatformResourceKey`（`javap -p`）`public abstract ResourceType getResourceType();`
- `ResourceTypes`（`javap -p -c`）：`public static final ResourceType ITEM; public static final ResourceType FLUID;`，`static{}` 里分别 `new ItemResourceType()` / `new FluidResourceType()`
- ⚠️ `ItemResourceType` / `FluidResourceType` 两个实现类本身是**包私有**（`javap -p` 类头 `class ... implements ResourceType`，无 `public`）⇒ **只能用 `ResourceTypes.ITEM/FLUID` 这两个公开单例做比较，不能 `import` 实现类**。

---

## ③ 在我们自己的网格子类上「覆盖面板」

### 3.0 先复核 `docs/RESOURCE_PICKER_DESIGN.md` 已确认的事实（本轮用 2.0.9 字节码重新核了一遍）

| 事实 | 复核结果 | 证据 |
|---|---|---|
| `GridScreen<T>` 是 `public class` 非 final，`public GridScreen(T, Inventory, Component)` | ✅ 成立 | `javap -p` 类头 + 构造器原文 |
| 网格不是槽位制，客户端自绘 `repository.getViewList()` | ✅ 成立 | `AbstractGridScreen.renderCell` 字节码里 `ResourceRepository.getViewList()` + `List.get(i)` |
| 回传用 `C2SPackets.sendResourceFilterSlotChange(PlatformResourceKey,int)` + 覆写 `handleResourceFilterSlotUpdate` | ✅ 两者都 public | `javap -p` 于 `C2SPackets`：`public static void sendResourceFilterSlotChange(PlatformResourceKey, int);`；`javap -p` 于 `AbstractResourceContainerMenu`：`public void handleResourceFilterSlotUpdate(int, PlatformResourceKey);` |
| 必须自建菜单子类（RS 的 `GridContainerMenu` 构造器把 MenuType 写死） | ✅ 成立（沿用既有结论） | `docs/RESOURCE_PICKER_DESIGN.md` §1.3 |
| **⚠️ 只覆写 `mouseClickedInGrid` 不够**（`canExtract`/`canInsert` 是 private） | ✅ 成立，且本轮进一步收紧为 §3.4 | `javap -p` 于 `AbstractGridScreen`：`protected void mouseClickedInGrid(int, GridResource);`、`private boolean canExtract(GridResource, ItemStack);`、`private boolean canInsert(int,int,int,ItemStack);` |

### 3.1 在子类里加自己的控件：`init()` 够不够？——**够，而且比想象的更宽松**

#### 证据 1：`init()` 这条链上**没有任何 final**

| 类 | 成员 | `javap -p` 原文 |
|---|---|---|
| `AbstractBaseScreen` | `protected void init();` | **protected，非 final** |
| `AbstractStretchingScreen` | `protected void init();`<br>`protected void init(int);` | 都**非 final** |
| `AbstractGridScreen` | `protected void init(int);` | **非 final** |
| `Screen`（原版） | `protected void init()` | 标准可覆写点 |

`AbstractStretchingScreen.init()` 字节码（`javap -c`，偏移 0-128）清楚地给出调用链：

```
  0:  visibleRows = calculateVisibleRows()
  8:  imageHeight = 19 + 18*visibleRows + getBottomHeight()
 27:  inventoryLabelY = imageHeight - 99 + 4
 40:  resize()                                  ← resize 是 protected FINAL（别覆写）
 44:  invokespecial AbstractBaseScreen.init()   ← ★ 关键：基类 init 在这里被调用
 48:  scrollbar = new ScrollbarWidget(leftPos+174, topPos+20, NORMAL, visibleRows*18-2)
 99:  addWidget(scrollbar)                       ← 注意是 addWidget，不是 addRenderableWidget
108:  init(visibleRows)                          ← ★ 转到 init(int)
116:  addSideButton(new ScreenSizeSideButtonWidget(this))
```

`AbstractGridScreen.init(int)` 字节码（`javap -c`，偏移 0-388）里的控件注册：

```
 17-72:  searchField = new GridSearchBoxWidget(font, leftPos+94+1, topPos+6+1, 67, ...)
107-118: getMenu().setSearchBox(searchField)
142:    updateScrollbar()
146-154: addWidget(searchField)                  ← 搜索框走 addWidget（后面手动 render）
171-192: addSideButton(new RedstoneModeSideButtonWidget(...))   // 仅当菜单有 REDSTONE_MODE 属性
195-210: addSideButton(new ViewTypeSideButtonWidget(getMenu()))
213-236: resourceTypeSideButtonWidget = new ResourceTypeSideButtonWidget(getMenu())
231-236: addSideButton(resourceTypeSideButtonWidget)
239-254: addSideButton(new SortingDirectionSideButtonWidget(getMenu()))
257-272: addSideButton(new SortingTypeSideButtonWidget(getMenu()))
275-287: addSideButton(new AutoSelectedSideButtonWidget(searchField))
290-320: addRenderableWidget(new SearchIconWidget(leftPos+79, topPos+5, ..., searchField))   ← ★ 偏移 320
324-372: 若同步器不全支持 → addSideButton(new SynchronizationModeSideButtonWidget(...))
```

`AbstractBaseScreen.addSideButton` 字节码（偏移 0-75）末尾：

```
69: addRenderableWidget(sideButton)   ← 侧边按钮也是 addRenderableWidget
```

⇒ **RS 自己就在 `init(int)` 里用 `addRenderableWidget`。**

#### 结论：正确写法

```java
@Override
protected void init() {
    super.init();                 // → AbstractStretchingScreen.init() → ... → AbstractGridScreen.init(int)
    buildMyPanel();               // 我们自己的 addRenderableWidget(...)
}

@Override
protected void init(int visibleRows) {   // 可选：想在网格可见行数确定后再摆面板时用
    super.init(int);
    ...
}
```

**依赖顺序（必须遵守）**：`AbstractStretchingScreen.init()` 在偏移 44 才调 `AbstractBaseScreen.init()`（后者会 `clearWidgets()`，见 `AbstractBaseScreen.init` 字节码偏移 0-4），而 `AbstractGridScreen.init(int)` 在偏移 108 才被调用。⇒ **我们的 `addRenderableWidget` 必须在 `super.init()` 之后**，否则会被 `clearWidgets()` 清掉。

**另外两个有用的公开/受保护入口**（用于定位）：
- `AbstractBaseScreen`：`public int getLeftPos();` / `public int getTopPos();`（`javap -p`）⇒ 不用猜 `leftPos`/`topPos` 字段（它们是 `AbstractContainerScreen` 的 `protected int`，我们继承链上本来就能直接读）。
- `AbstractBaseScreen`：`public void addSideButton(AbstractSideButtonWidget);`（`javap -p`）⇒ 想加侧边按钮风格的东西有现成 API；但它会往 `exclusionZones` 里塞 `Rect2i`（字节码 21-53），会影响 tooltip 排除区，**别滥用**。

### 3.2 渲染顺序：**我们加的面板不会被网格盖住**（逐层证据）

#### 完整调用链（从最外层往里）

```
AbstractGridScreen.render(g,mx,my,pt)                 // javap -c 偏移 1280
  └─ invokespecial AbstractStretchingScreen.render(...)          // 偏移 6
       ├─ invokespecial AbstractBaseScreen.render(...)           // 偏移 6
       │    ├─ invokespecial AbstractContainerScreen.render(...) // 偏移 6
       │    │    ├─ this.renderBackground(g,mx,my,pt)            // [sources] AbstractContainerScreen.java:95
       │    │    │    └─ this.renderBg(g,pt,mx,my)               // [sources] :157
       │    │    │         └─ AbstractStretchingScreen.renderBg(...)      // javap -c 偏移 163
       │    │    │              ├─ private renderBackground(g,x,y)        // 偏移 32（贴底图）
       │    │    │              └─ private renderRows(g,mx,my,x,y)        // 偏移 44
       │    │    │                   ├─ GuiGraphics.enableScissor(...)    // 偏移 39
       │    │    │                   ├─ AbstractGridScreen.renderRows(7 参)// 偏移 56  ← ★ 网格格子
       │    │    │                   └─ GuiGraphics.disableScissor()      // 偏移 59
       │    │    ├─ NeoForge ContainerScreenEvent.Render.Background      // [sources] :96
       │    │    ├─ for (Renderable r : this.renderables) r.render(...)  // [sources] :97-99  ← ★★ 我们的 widget
       │    │    ├─ for (Slot) renderSlot(...) / renderSlotHighlight(...) // [sources] :105-115
       │    │    └─ this.renderLabels(...)                               // [sources] :117
       │    │         (+ NeoForge Foreground 事件 :118，+ 拖拽物品渲染 :119+)
       │    └─ renderTooltip(g,mx,my)                                     // AbstractBaseScreen 偏移 13
       └─ scrollbar.render(g,mx,my,pt)       （仅当 scrollbar != null）    // 偏移 16-25  ← ★ 在 widget 之后！
  └─ searchField.render(g,0,0,0)              （仅当 searchField != null） // 偏移 16-24  ← ★★ 最后画
```

#### 三条"谁盖谁"的判定

| 关系 | 结果 | 依据 |
|---|---|---|
| 网格行 vs 我们 `addRenderableWidget` 的控件 | **我们在上面**（网格行在 `renderBg` 里画，我们的 widget 在 `renderables` 循环里画，后者在后） | `[sources] AbstractContainerScreen.java:95` 早于 `:97-99` |
| 我们的控件 vs RS 的侧边按钮 | **看添加顺序**；`addSideButton` 也是 `addRenderableWidget`（`AbstractBaseScreen.addSideButton` 偏移 69），`renderables` 按插入顺序遍历 ⇒ `super.init()` 之后添加的我们**在侧边按钮之上** | `[sources] :97` 顺序遍历 |
| 我们的控件 vs `ScrollbarWidget` / `GridSearchBoxWidget` | **它们在上面**（两者都是 `addWidget`，被手动 `render`，晚于 `renderables` 循环） | `AbstractStretchingScreen.render` 偏移 16-25；`AbstractGridScreen.render` 偏移 16-24 |

**⇒ 设计约束（写进实现契约）：**
1. 面板**不要**盖在 `leftPos+174`（滚动条所在列，`AbstractStretchingScreen.init` 偏移 57 `sipush 174`）上。
2. 面板**不要**盖在搜索框上：搜索框在 `leftPos+95`、`topPos+7`、宽 67（`AbstractGridScreen.init(int)` 偏移 22-46：`leftPos+94+1` / `topPos+6+1` / `67`）。
3. 除此之外，**面板想放哪就放哪，一定画在网格行之上**。
4. **面板绝不能 `final` 化或覆写 `render` 后不调 `super`** —— `renderTooltip` 是在 `AbstractBaseScreen.render` 里 `super.render(...)` **之后**调的（偏移 9-13），漏掉 `super.render` 会让整个网格与 tooltip 全灭。

### 3.3 左键 = 只选不取：**正确覆写点是 2 个，不是 3 个**

#### A. 先把"取出"的完整发包链钉死（**证据打到包级**）

```
AbstractGridScreen.mouseClicked(double,double,int)                     [javap -c 偏移 1300]
  → private mouseClicked(double,double,int,GridResource,ItemStack)
      ├─ if (canExtract(resource, carried))   → protected mouseClickedInGrid(int, GridResource)  [偏移 16]
      ├─ else if (canInsert(mx,my,button,carried)) → private mouseClickedInGrid(int)             [偏移 39]
      ├─ else if (carried != null && resource.isAutocraftable(repo) && tryStartAutocrafting(...))
      └─ else → super.mouseClicked(...)
                ↓
AbstractGridScreen.mouseClickedInGrid(int, GridResource)（基类实现）
  15: GridResource.onExtract(GridExtractMode, boolean, GridExtractionStrategy)
                ↓
GridResource.onExtract 的实现（ItemGridResource / FluidGridResource）
  → C2SPackets.sendGridExtract(...)          【见下方 javap】
```

`javap -p -c` 于 `com.refinedmods.refinedstorage.common.grid.strategy.ClientGridExtractionStrategy`：

```
public boolean onExtract(PlatformResourceKey, GridExtractMode, boolean);
   3: invokestatic C2SPackets.sendGridExtract:(PlatformResourceKey;GridExtractMode;Z)V
```

`javap -p -c` 于 `com.refinedmods.refinedstorage.common.grid.strategy.ClientGridScrollingStrategy`：

```
   3: invokestatic C2SPackets.sendGridScroll:(PlatformResourceKey;GridScrollMode;I)V
```

`C2SPackets`（`javap -p` 原文节选）：

```
public static void sendGridExtract(PlatformResourceKey, GridExtractMode, boolean);
public static void sendGridScroll(PlatformResourceKey, GridScrollMode, int);
public static void sendGridInsert(GridInsertMode, boolean);
```

**调用点唯一性（这是本节的要害）**——`Select-String` 于 `AbstractGridScreen` 全量 `javap -c` 输出：

```
1344: 16: invokevirtual mouseClickedInGrid:(I,GridResource;)V    ← 只在 private mouseClicked 里
1358: 39: invokevirtual mouseClickedInGrid:(I)V                   ← 只在 private mouseClicked 里
1557: 57: invokevirtual mouseScrolledInGrid:(Z,GridResource;)V    ← 只在 public mouseScrolled 里
```

而这两个 private 方法各自**只被一个 public 方法调用**（`mouseClicked` 偏移 56 调 private mouseClicked；`mouseScrolled` 偏移 52 调 `mouseScrolledInGrid`）。

⇒ **只要覆写 `public mouseClicked` 与 `public mouseScrolled` 且在这两处不落到 `super`，就物理上切断了 `sendGridExtract` / `sendGridScroll` 的全部可达路径。**（`onExtract` / `onScroll` 没有别的调用者。）

#### B. `mouseReleased` 要不要覆写？——**不用**

证据：`javap -p` 于 `AbstractGridScreen` 的**完整成员列表**里**没有** `mouseReleased`、也没有 `mouseDragged`、也没有 `mouseMoved`（文件 `_audit_tmp/pacer/AbstractGridScreen.txt` 第 53-73 行）。继承到的是：

`javap -p -c` 于 `AbstractStretchingScreen.mouseReleased`：

```
   0: if (scrollbar != null && scrollbar.mouseReleased(mx,my,button)) return true
  21: else return super.mouseReleased(mx,my,button)     // AbstractBaseScreen → AbstractContainerScreen（快捷合成收尾）
```

⇒ `mouseReleased` 里**没有任何取出/放入行为**。（一个附带效应：因为我们覆写的 `mouseClicked` 在没有命中资源时会走 `super`，而命中资源时返回 `true` 且不调 `super`，所以 vanilla 的 quick-craft 状态不会被我们启动。）

#### C. `keyPressed` / `charTyped` 要不要覆写？——**不用**

`javap -p -c` 于 `AbstractGridScreen.keyPressed`（偏移 0-73）只有三件事：
1. `Screen.hasShiftDown()` 且 `Config.getGrid().isPreventSortingWhileShiftIsDown()` ⇒ `repository.setPreventSorting(true)`；
2. 转给 `searchField.keyPressed(...)`；
3. 转给 `super.keyPressed(...)`。

**没有任何取出/放入。** `charTyped` 同理（只转搜索框 + super）。

#### D. 推荐的覆写实现

```java
@Override
public boolean mouseClicked(final double mx, final double my, final int button) {
    // getCurrentGridResource() 的语义：仅当鼠标悬停在「存储区内的有效格」上才非 null
    // —— 依据：AbstractGridScreen.renderCell 只在 isOverStorageArea(mx,my) 为真时
    //    才写 currentGridSlotIndex（javap -c 偏移 87-118），而 renderRows 每帧先把它置 -1（偏移 2）；
    //    全类里 currentGridSlotIndex 只有这两个写入点（putfield 行 458 / 681）。
    final GridResource hovered = getCurrentGridResource();
    if (hovered != null) {
        if (button == 0) {                       // 左键 = 加入清单
            addEntry(hovered);
            return true;                         // ★ 不回 super ⇒ 不触发 sendGridExtract / sendGridInsert
        }
        if (button == 1) {                       // 右键 = 从清单移除（同一资源的反选）
            removeEntry(hovered);
            return true;
        }
    }
    return super.mouseClicked(mx, my, button);   // 其余（搜索框 / 侧边按钮 / 十周年丝带）保持 RS 原样
}

@Override
public boolean mouseScrolled(final double mx, final double my, final double sx, final double sy) {
    if (getCurrentGridResource() != null) {
        return true;   // ★ 悬停在资源上：吃掉滚轮，绝不落到 super（否则 = sendGridScroll = 取出/放入）
    }
    return super.mouseScrolled(mx, my, sx, sy);  // 滚动条 / 其它区域保持原样
}
```

**为什么 `mouseScrolled` 也要管**：基类 `mouseScrolled` 的偏移 30-57 是 `if (isOverStorageArea(mx,my)) { r = getCurrentGridResource(); if (r != null) mouseScrolledInGrid(down, r); }`，`mouseScrolledInGrid` → `GridResource.onScroll` → `C2SPackets.sendGridScroll`。**滚轮在网格上 = 取出/放入**，对"选择器"来说完全不该有。

**为什么不用重建 `isOverStorageArea`**：它是 `private`（`javap -p` 第 28 行），且我们没有替代需求——`getCurrentGridResource() != null` 与它等价（见上面注释里的字节码依据）。若将来确实要矩形判定，可用 `AbstractStretchingScreen.renderRows` 里的 scissor 算式：`[x+7, x+169) × [y+20, y+20+18*visibleRows-2)`（`javap -c` 偏移 0-39），其中 `x = (width-imageWidth)/2`、`y = (height-imageHeight)/2`（`AbstractStretchingScreen.renderBg` 偏移 0-24），与 `leftPos/topPos` 同一坐标系。

**⚠️ 一个必须知道的副作用**：`AbstractGridScreen.mouseClicked` 的偏移 18-45 处理"十周年丝带"（`isOverTenthAnniversaryRibbon` → 打开 `TenthAnniversaryScreen`，2026-03-20..22 出现，见 `docs/RESOURCE_PICKER_DESIGN.md` §5.3）。丝带画在 `renderStretchingBackground` 偏移 7-22（`x-3, y-15, 82×15`），**与存储区左上角有重叠**。我们的覆写会因为 `getCurrentGridResource() != null` 而**抢掉**丝带的点击。要保留 RS 原生行为就得在覆写里先排除那块矩形——建议**接受抢点击**（三天/年，且用户可以点别处），但要在文档里写明。

### 3.4 流体分页 + "当前这条是什么类型"

#### A. RS 怎么在物品/流体之间切换

`javap -p` 于 `com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu`：

```
15: private com.refinedmods.refinedstorage.common.api.support.resource.ResourceType resourceTypeFilter;
50: private com.refinedmods.refinedstorage.common.api.support.resource.ResourceType loadResourceType();
52: public com.refinedmods.refinedstorage.common.api.support.resource.ResourceType getResourceType();
54: public void toggleResourceType();
```

`javap -p -c` 于 `com.refinedmods.refinedstorage.common.grid.screen.ResourceTypeSideButtonWidget`：

```
class ...ResourceTypeSideButtonWidget extends AbstractSideButtonWidget {        // ← 包私有 class！
  ...ResourceTypeSideButtonWidget(AbstractGridContainerMenu);                   // ← 包私有构造
  protected ResourceLocation getSprite();   → menu.getResourceType() 为 null ? ALL : getResourceType().getSprite()
  private static void lambda$createPressAction$0(AbstractGridContainerMenu m, Button b) {
      m.toggleResourceType();
  }
  static {}: TITLE = "gui.grid.resource_type"
             SUBTEXT_ALL = "gui.grid.resource_type.all"
             ALL = IdentifierUtil.createIdentifier("widget/side_button/resource_type/all")
}
```

⇒ **切换控件是包私有的，我们 `new` 不了；但 `AbstractGridScreen.init(int)` 偏移 213-236 已经免费帮我们注册了它**（把它存进 `resourceTypeSideButtonWidget` 字段并 `addSideButton`）。**我们什么都不用做就能分页。**

⇒ **补充证据**：`ResourceType` 注册表只有两个类型（`javap -p -c` 于 `ResourceTypes`）：`ITEM` = `new ItemResourceType()`、`FLUID` = `new FluidResourceType()`。`toggleResourceType()` 走 `Config.getGrid()` + `PlatformRegistry.nextOrNullIfLast`，循环 **item → fluid → null(ALL) → item → ...**（后者是既有文档 `docs/RESOURCE_PICKER_DESIGN.md` §5.3 第 3 行的结论，本轮未重新反编译 `toggleResourceType` 的方法体 ⇒ 标为「与既有文档一致，本轮未独立复核」）。

#### B. "这一条是物品还是流体"——**唯一正确写法**

```java
final PlatformResourceKey key = hovered.getResourceForRecipeMods();
final ResourceType type = key.getResourceType();
final boolean isFluid = (type == ResourceTypes.FLUID);
final boolean isItem  = (type == ResourceTypes.ITEM);
```

证据链（全部 `javap`）：
1. `GridResource` 接口：`public abstract PlatformResourceKey getResourceForRecipeMods();`
2. `AbstractGridResource.getResourceForRecipeMods()`：字节码 `0: aload_0; 1: getfield resource; 4: areturn` ⇒ 直接就是底层资源键。
   （`AbstractGridResource` 的 `protected final T resource` 是 `protected`，跨包读不到 ⇒ **必须走这个公开方法**，不能直接读字段。）
3. `PlatformResourceKey` 接口：`public abstract ResourceType getResourceType();`
4. `ResourceTypes.ITEM` / `ResourceTypes.FLUID` 是 `public static final` 单例。
5. 备选（等价）：`GridResource.belongsToResourceType(ResourceType)` 也是接口公开方法（`public abstract boolean belongsToResourceType(ResourceType);`），可用于"这个条目属不属于当前分页"。

**⇒ 设计要点：** 清单里**每条都存 `PlatformResourceKey`**（不是 `ItemResource`），所以"同时装物品与流体"是数据模型层面天然成立的；界面**不按类型分区**，而是照 RS 的既有习惯用侧边按钮分页 + 每条自带类型徽标（§2.5）。这样用户"所有资源包括流体"这条硬要求同时被数据层与交互层满足。

### 3.5 "已选"怎么显示（多选累积）

#### A. RS 有没有现成的"已筛选/已选"视觉？——**没有**

`javap -p` 于 `AbstractGridScreen` 的**全部**渲染相关成员（第 29-51 行）：

```
protected void renderStretchingBackground(GuiGraphics,int,int,int);
protected void renderRows(GuiGraphics,int,int,int,int,int,int);
private   void renderRow(GuiGraphics,int,int,int,int,int);
private   void renderCell(GuiGraphics,int,int,int,int,int,int);
protected void renderSlot(GuiGraphics, Slot);
private   void renderSlot(GuiGraphics,int,int,int,ResourceRepository,int,int);
private   void tryRenderAutocraftableResourceHintBackground(GuiGraphics, Slot);
private   void renderResourceWithAmount(GuiGraphics,int,int,GridResource);
public static void renderSlotBackground(GuiGraphics,int,int,boolean,int);
private   void renderAmount(GuiGraphics,int,int,GridResource);
private   int  getAmountColor(GridResource,long);
private   String getAmountText(GridResource,long);
private   void renderDisabledSlot(GuiGraphics,int,int);
```

**唯一的"格子底色"逻辑**（`renderResourceWithAmount` 字节码 0-68）：

```
  2: resource.isAutocraftable(repository) == true
     → renderSlotBackground(g, x, y, false /*large*/, AutocraftableResourceHint.AUTOCRAFTABLE.getColor())
 36: else if (resource.getAmount(repository) == 0)
     → renderSlotBackground(g, x, y, false, 1727987712 /* 空槽底色 */)
 68: resource.render(g, x, y)              ← 资源自身绘制
 78: renderAmount(g, x, y, resource)       ← 数量文本
```

**唯一的"格子高亮"逻辑**（`renderCell` 字节码 87-118）：

```
 87: if (hovered && isOverStorageArea(mx, my)) {
101:     AbstractContainerScreen.renderSlotHighlight(g, cellX, cellY, 0);   ← 悬停高亮（原版静态方法）
110:     if (resource != null) this.currentGridSlotIndex = index;
     }
```

（`renderDisabledSlot` = `RenderType.guiOverlay()` + `fill(..., -10790053)`，用于菜单不 active 时整屏压暗。）

⇒ **RS 只有三种格子视觉：可合成底色 / 空槽底色 / 悬停高亮。"已选"必须我们自己画。**

#### B. 怎么画（推荐做法 + 精确几何）

覆写 `renderRows`（`protected`、非 final，`AbstractGridScreen` 第 32 行）：

```java
@Override
protected void renderRows(final GuiGraphics g, final int x, final int y,
                          final int topOffset, final int visibleRows,
                          final int mouseX, final int mouseY) {
    super.renderRows(g, x, y, topOffset, visibleRows, mouseX, mouseY);   // 先让 RS 画完 9×visibleRows
    // 再画我们的"已选"标记
    final List<GridResource> view = getMenu().getRepository().getViewList();
    final int scroll = getScrollbarOffset();          // protected final，可读
    for (int row = 0; row < Math.max(visibleRows, /*total*/ 0); row++) {
        for (int col = 0; col < 9; col++) {
            final int index = row * 9 + col;
            if (index >= view.size()) break;
            if (!isSelected(view.get(index))) continue;
            final int cellX = x + 7 + 1 + col * 18;                       // ← 见下方推导
            final int cellY = y + topOffset + row * 18 - scroll + 1;
            AbstractGridScreen.renderSlotBackground(g, cellX, cellY, false, SELECTED_COLOR);
        }
    }
}
```

**几何推导（逐条对应字节码，不是估的）：**

| 量 | 公式 | 出处 |
|---|---|---|
| 传进 `renderRows` 的 7 个参数 | `(g, x, y, 19, visibleRows, mouseX, mouseY)` | `AbstractStretchingScreen.renderRows`（private，偏移 42-56）：`renderRows(g, iload_4, iload_5, bipush 19, visibleRows, iload_2, iload_3)` |
| 行底色起点 | `rowX = x + 7`；行高 18，宽 162 | `AbstractGridScreen.renderRows` 偏移 22-26（`iload_2 + bipush 7`）；`renderRow` 偏移 8-13（`blitSprite(ROW_SPRITE, x, y, 162, 18)`） |
| 行底色 Y | `rowY = y + topOffset + row*18 - getScrollbarOffset()` | `AbstractGridScreen.renderRows` 偏移 28-43 |
| 9 列 | `for (col = 0; col < 9; col++)` | `renderRow` 偏移 16-23（`bipush 9`） |
| 每格 X | `cellX = rowX + 1 + col*18` | `renderCell` 偏移 12-22（`iload 4 + iconst_1 + iload 7*18`） |
| 每格 Y | `cellY = rowY + 1` | `renderCell` 偏移 24-28（`iload 5 + iconst_1`） |
| 全局下标 | `index = row*9 + col` | `renderRow` 偏移 34-41（`iload 6 * 9 + iload 7`） |
| 滚动偏移 | `getScrollbarOffset()` —— **`protected final`，可直接调用** | `javap -p` 于 `AbstractStretchingScreen`：`protected final int getScrollbarOffset();` |
| 可见行数 | `visibleRows` 参数直接给我们 | 同上 |

**两个额外好处：**
1. `renderRows` 是在 `AbstractStretchingScreen.renderRows`（private）**开启了 scissor 之后**被调用的（偏移 39 `enableScissor` / 偏移 59 `disableScissor`）⇒ **我们的标记天然被裁剪在网格区内，不会溢出到玩家背包那一块。**
2. 我们画在 `renderBg` 阶段（`renderables` 之前）⇒ **标记在 tooltip 之下、在网格行之上**，视觉正确。

**颜色/样式建议**：
- 已选 = `renderSlotBackground(g, x, y, false, 0x60_00FF00)` 这类半透明填充（`renderSlotBackground` 内部就是 `GuiGraphics.fill(x-?, y-?, x+16+?, y+16+?, color)`，`large=false` 时不外扩 4px，见字节码 0-39）；
- 也可以用工程自己的贴图（更醒目），但**不要**新画一套"选中框"当作唯一线索——配合 §2.5 的类型徽标一起用，玩家才不会看错。

**备选（更简单但更弱）**：覆写 `render(GuiGraphics,int,int,float)`，`super.render(...)` 之后自绘。缺点是**会画在 tooltip 之上**（`renderTooltip` 在 `AbstractBaseScreen.render` 偏移 13、`super.render` 偏移 6 之后），且没有 scissor 裁剪。⇒ **不推荐**，但作为"第一版先跑起来"的降级路径可以接受。

### 3.6 ③ 本节的**未证实**

1. **`getCurrentGridResource()` 与"鼠标实际悬停格"在"点击那一帧"是否严格同步** —— 依据是 `currentGridSlotIndex` 由**渲染**维护（`renderCell` 偏移 115-118），而渲染发生在输入之前（同帧顺序），**逻辑上成立但没有实机验证**。风险低（基类自己的 `mouseClicked` 也读同一个方法，偏移 13-16）。
2. **`leftPos/topPos` 与 `AbstractStretchingScreen.renderBg` 里局部算出的 `(width-imageWidth)/2` 是否恒等** —— 两者算式相同、且在同一次 `renderBg` 调用内，但**我没找到"赋值"的字节码把它们钉在一起**。影响面：只有"自己重建存储区矩形"这条（我们已选择不重建）。
3. **十周年丝带与存储区重叠的精确矩形** —— 只读到 `renderStretchingBackground` 偏移 7-22 的 `blitSprite(RIBBON, x-3, y-15, 82, 15)`，**没验证** `isOverTenthAnniversaryRibbon` 的判定矩形是否与之同源。
4. **`toggleResourceType()` 的循环顺序（item → fluid → null）** —— 沿用 `docs/RESOURCE_PICKER_DESIGN.md` §5.3 的结论，**本轮没有独立反编译该方法体**。
5. **在 `mouseClicked` 里同步调 `C2SPackets` / 自定义包发送是否有时序问题** —— 与既有文档 §6.1 第 6 条相同的未证实项（路径已证明，实机未测）。

---

## ④ 全模组统一节流 / 节拍工具

### 4.1 清点：全模组所有吃"速度升级"的地方

**方法**：`grep` 全工程 `countUpgrades|SPEED_UPGRADE|STACK_UPGRADE`（52 命中）+ `Interval|scanCooldown|workCooldown|chargeCooldown|Rate|perSecond`（78 命中）+ 逐文件读公式；再反向核对 `RegisterUpgradeDestinations` 里**注册了**升级的机器清单。

#### 主表（**每一行都给了公式原文与行号**）

| # | 机器 / 方块 | 类名 | 速度升级读取点（文件:行） | 当前公式（原文） | 升级上限 | 每级实际效果（数量化） |
|---|---|---|---|---|---|---|
| 1 | **归流缓存仓**·扫描间隔 | `CollectionCacheBlockEntity` | `:447` `countUpgrades(SPEED_UPGRADE)` | `:445-450`<br>`final int base = Math.max(1, Config.collectionCacheScanInterval);`<br>`final int speedFactor = 1 + countUpgrades(SPEED_UPGRADE);`<br>`final int radiusFactor = 1 + (getCollectRadius()-1)/2;`<br>`return Math.max(1, base / (speedFactor * radiusFactor));` | 6（`UpgradeSlot.MAX_PER_UPGRADE`，`menu/UpgradeSlot.java:29`） | **倒数曲线**。半径 1：`20,10,6,5,4,3,2` tick；半径 5（factor 3）：`6,3,2,1,1,1,1` |
| 2 | **归流缓存仓**·每 tick 处理组数 | 同上 | `:2573` | `:2572-2574` `return 1 + countUpgrades(SPEED_UPGRADE);` | 6 | **线性**：`1,2,3,4,5,6,7` 组/tick |
| 3 | **归流缓存仓**·单次吞吐 | 同上 | `:2578`（**读的是 STACK**） | `:2577-2579` `return Math.max(1, 64 * (1 + countUpgrades(STACK_UPGRADE)));` | 6 | **线性**：`64,128,...,448` 个/组 |
| 4 | **归流缓存仓**·删除速率（导出量） | 同上 | `:2406` | `:2405-2407` `return (long) Math.max(1, getProcessRate()) * Math.max(1, getTransferBatch());` | — | 派生量：`getProcessRate × getTransferBatch` |
| 5 | **定量保持器**（基础）·销毁速率 | `QuantityKeeperBlockEntity` | `:575-576` | `:574-580`<br>`final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);`<br>`final int stack = Math.min(countUpgrades(STACK_UPGRADE), 6);`<br>`final int power = Math.min(speed + stack * 3, 24);`<br>`return Config.quantityKeeperDestroyRate << power;` | 速度 6 / 堆叠 6 | **指数（每级 ×2）**，堆叠按 **1 个 = 3 级**。base=1 时：`1,2,4,8,16,32,64`（纯速度）；纯堆叠满级 6 → power 18 → **262,144**/次 |
| 6 | **高级定量保持器**·销毁速率 | `AdvancedQuantityKeeperBlockEntity` | `:673-674` | `:672-677` **与 #5 逐字符相同** | 同上 | 同 #5 |
| 7 | **范围充电器**·单目标充电速率 | `RangeChargerBlockEntity` | `:155` `getUpgradeCount()` | `:154-156` `return Config.rangeChargerChargeRate << getUpgradeCount();`<br>`:159-161` `return countUpgrades(SPEED_UPGRADE) + countUpgrades(STACK_UPGRADE) * 3;` | 速度 6 / 堆叠 6 / 范围 6 / 创造范围 1（`RangeChargerMenu.java:41-46`、`RegisterUpgradeDestinations.java:41-45`） | **指数（每级 ×2）**，堆叠 1 个 = 3 级。base=500：纯速度 `500,1000,2000,4000,8000,16000,32000` FE/t；纯堆叠满级（power 18）→ **131,072,000** FE/t |
| 8 | **装填器**·单次拉取上限 | `SchematicLoaderBlockEntity` | `:437` | `:436-439` `final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);`<br>`return speed >= 6 ? Integer.MAX_VALUE : (8 << speed);` | 速度 6 / 堆叠 6 / 自动合成 1 | **指数 + 满级特判**：`8,16,32,64,128,256,∞` |
| 9 | **装填器**·每 tick 拉取速率 | 同上 | `:1225` | `:1225-1226` `final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);`<br>`final double rate = speed >= 6 ? Double.MAX_VALUE : (0.5 * (1 << speed));` | 同上 | **指数 + 满级特判**：`0.5,1,2,4,8,16,∞` 个/tick |
| 10 | **范围充电器**·饰品槽扫描间隔 | 同上 | **不吃升级** | `Config.rangeChargerCuriosScanInterval`（默认 5，`Config.java:65-70`），用法 `RangeChargerBlockEntity.java:421` `curiosScanCooldown = Math.max(0, Config.rangeChargerCuriosScanInterval - 1);` | — | 固定节拍，不随升级变化 |

#### 反向核对：**注册了升级、但代码里不生效** 的地方（**新发现**）

| 机器 | 注册情况 | 代码事实 | 结论 |
|---|---|---|---|
| `sequence_assembly_executor`（序列装配执行器） | `RegisterUpgradeDestinations.java:51-53` 注册 `speed_upgrade`（上限 `MAX_PER_UPGRADE`=6）+ `autocrafting_upgrade`（1） | `Select-String -Path SequenceAssemblyExecutorBlockEntity.java -Pattern "Upgrade|speed" -CaseSensitive:$false` ⇒ **0 命中**；`SequenceAssemblyExecutorMenu.java` 里 `grep UpgradeSlot|Upgrade|addSlot` ⇒ 只有 `PatternSlot` 与玩家槽，**没有任何 `UpgradeSlot`** | **死注册**：RS 的升级物品 tooltip 会声称"序列装配执行器 ×6"，但玩家**无处可插**，插了也不生效 |

#### 已定义但**从未被读取**的配置项（**新发现**）

| 配置项 | 定义/赋值 | 被逻辑读取？ |
|---|---|---|
| `quantityKeeperDestroyRatePerSpeed`（默认 16） | `Config.java:85-87`（`defineInRange`）、`:276`（字段）、`:346`（赋值） | **没有**——全工程 grep 只有这 3 处，没有任何 `Config.quantityKeeperDestroyRatePerSpeed` 的读取点，而 `QuantityKeeperBlockEntity.getDestroyRate()`（`:574-580`）用的是 `<< power`。⇒ 玩家改了它**没有任何效果**（注释还写着"每个速度升级额外增加的每 tick 销毁数量"，与实现不符） |

### 4.2 RS 侧的公式与"负数坑"（**字节码原文**）

`javap -p -c` 于 `com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer$ThrottledNetworkNodeTicker` 相关路径 —— 公式写在 `UpgradeContainer.notifyListener()` 里：

```
private void notifyListener();
   0: if (listener == null) return
   8: LOGGER.debug("Reconfiguring for upgrades")
  18: Items.INSTANCE.getSpeedUpgrade()
  25: invokevirtual getAmount:(UpgradeItem)I        → istore_1   // n = 速度升级个数
  29: getfield ticker
  33: getfield defaultWorkTickRate
  37: iload_1                                        // n
  38: iconst_2
  39: imul                                           // n * 2
  40: isub                                           // defaultWorkTickRate - n*2
  41: putfield ThrottledNetworkNodeTicker.workTickRate
  44: listener.updateState(this, getEnergyUsage())
```

`defaultWorkTickRate` 的出厂值（构造器字节码）：

```
public UpgradeContainer(UpgradeDestination);
   3: invokespecial <init>:(UpgradeDestination;UpgradeContainerListener;)V
public UpgradeContainer(UpgradeDestination, UpgradeContainerListener);
   3: bipush 9      // ★ 9
   5: invokespecial <init>:(UpgradeDestination;UpgradeContainerListener;I)V
public UpgradeContainer(UpgradeDestination, UpgradeContainerListener, int defaultWorkTickRate);
   0: iconst_4      // 默认 4 个槽
```

⇒ **`workTickRate = 9 - 2n`**：

| n | 0 | 1 | 2 | 3 | 4 | **5** | **6** |
|---|---|---|---|---|---|---|---|
| workTickRate | 9 | 7 | 5 | 3 | 1 | **-1** | **-3** |

**结合本工程事实**：
- 本工程 `UpgradeSlot.MAX_PER_UPGRADE = 6`（`menu/UpgradeSlot.java:29`），而 `UpgradeSlot.mayPlace` 是**逐槽 1 个、单种上限 6**（`:104-125`）；
- ⇒ **`n=5`/`n=6` 是合法配置**，RS 的 `workTickRate` 分别是 `-1` / `-3`。

⇒ **用户的判断完全正确：RS 的 `UpgradeContainer` ticker 在本工程的口径下会算出负节拍，绝不能用。**（工程既有自检 `tools/selfcheck_assembly_cancel_immediate.py:11-13` 也把 `UpgradeContainer.DEFAULT_WORK_TICK_RATE = 9` 写进了注释，与本轮 javap 一致。）

**另外注意**：`UpgradeContainer.canPlaceItem` 用的是 RS 自己注册的 `UpgradeMapping.maxAmount()`（字节码 49-59），**不是**本工程的 6 —— 本工程的 6 是自己 `UpgradeSlot` 里再挡一层。两套上限并存，所以**我们绝不能依赖 RS 的容器去做"最多 6"**。

### 4.3 统一工具设计：`support/RsccRateLimiter.java`（建议）

#### A. 设计目标（对应用户原话）

> 「这一个严格的要求针对所有我们这个模组中提到这种有速度限制的物品，充电器也包含在内，都要有这种……反正就是保证他能用而且这种逻辑统一」

拆成 4 条可验证的不变量（**这就是"统一"的落点**）：

| 不变量 | 说明 |
|---|---|
| **I1 · 永不非正** | 任何输入下，间隔 ≥ 1 tick、批量 ≥ 1、速率 ≥ 1（RS 的 `9-2n` 在 n=5/6 时违反此条） |
| **I2 · 永不除零** | 公式里不出现 `base / f(n)` 而 `f(n)` 可能为 0 的形态；改用移位/乘法 |
| **I3 · 永不溢出/回绕** | `<<` 与 `*` 一律走饱和版本（到 `Integer.MAX_VALUE` / `Long.MAX_VALUE` 就停，绝不变成负数） |
| **I4 · 上限单点** | "最多几级"只有一处（`MAX_LEVEL = UpgradeSlot.MAX_PER_UPGRADE`）；任何上游（漏斗、NBT、创造模式）塞进来多少都会被夹到 `[0, MAX_LEVEL]` |

#### B. API 建议（三个输出形状 + 一组不变量）

```java
package cretae.cookiewyq.rs_create_compat.support;

/** 全模组唯一的速度/堆叠升级 → 节拍/批量换算。所有机器都必须走这里。 */
public final class RsccRateLimiter {
    /** 每级"堆叠升级折几个速度升级"——保留工程既有口径（充电器/保持器都是 ×3）。 */
    public static final int STACK_AS_SPEED_WEIGHT = 3;
    /** 级数上限 = 插件槽数（与 UpgradeSlot.MAX_PER_UPGRADE 同源）。 */
    public static final int MAX_LEVEL = cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE; // 6
    /** power 上限（= 6 级速度 + 6 级堆叠×3），仅作溢出防线；正常玩法到不了。 */
    public static final int MAX_POWER = MAX_LEVEL + MAX_LEVEL * STACK_AS_SPEED_WEIGHT; // 24

    private RsccRateLimiter() {}

    /** I4：任何来源的升级个数 → [0,6]。 */
    public static int level(final int rawUpgrades) {
        return Math.max(0, Math.min(rawUpgrades, MAX_LEVEL));
    }

    /** power = level(speed) + level(stack)*3，再夹到 [0,24]。 */
    public static int power(final int speedUpgrades, final int stackUpgrades) {
        return Math.min(level(speedUpgrades) + level(stackUpgrades) * STACK_AS_SPEED_WEIGHT, MAX_POWER);
    }

    /** 形状①「指数速率」：base << power，饱和到 Integer.MAX_VALUE（I1/I3）。 */
    public static int rateSaturating(final int basePerTick, final int speedUpgrades, final int stackUpgrades) {
        return shiftSaturating(Math.max(1, basePerTick), power(speedUpgrades, stackUpgrades));
    }

    /** 形状②「线性批量」：base * (1 + level)（I1/I3）。 */
    public static long batchSaturating(final long baseBatch, final int stackUpgrades) {
        return multiplySaturating(Math.max(1L, baseBatch), 1L + level(stackUpgrades));
    }

    /** 形状③「指数间隔」：max(1, base >> level)（I1/I2）。 */
    public static int intervalTicks(final int baseInterval, final int speedUpgrades) {
        return Math.max(1, Math.max(1, baseInterval) >> level(speedUpgrades));
    }

    /** 饱和左移：base << power，越界即封顶，绝不为负。 */
    public static int shiftSaturating(final int base, final int power) {
        int result = base;
        for (int i = 0; i < power; i++) {
            if (result > Integer.MAX_VALUE >> 1) return Integer.MAX_VALUE;
            result <<= 1;
        }
        return result;
    }

    /** 饱和乘法（long）。 */
    public static long multiplySaturating(final long a, final long b) {
        try { return Math.multiplyExact(a, b); }
        catch (final ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
```

**"满级 = 不限"这种语义也收进同一个工具**（装填器 #8/#9 需要）：

```java
    /** 满级哨兵：与工程既有「speed >= 6 ⇒ 不限」语义一致，统一成一个具名常量。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static boolean isMaxed(final int rawSpeedUpgrades) { return level(rawSpeedUpgrades) >= MAX_LEVEL; }
```

#### C. 公式选择与理由（**这是"我的选择"，不是事实**）

| 形状 | 采用哪条曲线 | 理由（每条都可核对） |
|---|---|---|
| 形状① 速率型（充电器、保持器销毁、装填器拉取） | **保留 `base << power`（指数）** | ① 工程已有 **2/4** 台机器（#5/#6/#7）在用这个口径，是当前的"主流"；② 用户明确要求"充电器也包含在内"，而充电器就是 `<<`，**改动量最小、手感回归风险最低**；③ 指数 + 饱和钳位天然满足 I1/I3；④ **不建议改成线性**：那会把充电器的 `131,072,000 FE/t`（满堆叠）砍到 `500*19=9,500`，是**一万三千倍**的削弱（见 §4.4 的"备选方案"列） |
| 形状② 批量型（缓存吞吐、每格容量） | **保留 `base * (1 + n)`（线性）** | 与 RS 原版 `stack_upgrade → ×64` 的语义一致（`docs/REUSE_SELECTION_UI_AND_UPGRADES.md` §4.3 已确认）；且#3 与 `SchematicLoaderBlockEntity.storageSlotCapacity`（`:447-450`）已经是这个口径 |
| 形状③ 间隔型（归流缓存仓扫描） | **改为 `max(1, base >> level)`（指数）** | 现有 `base/((1+n)*radiusFactor)` 虽然已经用 `Math.max(1,·)` 兜住了"非正"（不违反 I1），但它是**全工程唯一一条倒数曲线**，且**永远到不了 1 tick**（`20/7=2`）——也就是"满级了还是每 2 tick 才扫一次"。改成移位后：无除法（I2）、满级 = 1 tick、与形状①同一套 `>>` 直觉 |
| 固定节拍（饰品槽扫描） | 用 `intervalTicks(base, 0)` 表达 | 只是为了"所有间隔都从同一个函数出来"；数值不变（`>>0` 恒等） |
| "满级 = 不限" 特判（#8/#9） | 保留，但收敛为 `isMaxed()` + `UNLIMITED` | 若删掉特判改成 `8<<6 = 512`，装填器满级会从"瞬间"变成"每 tick 512 个"，是**语义级削弱**，不能顺手改 |

### 4.4 ★ 逐台「改前 / 改后」数值对照（**本次最重要的交付**）

> 口径：**"统一工具落地版"= 只做形状③的曲线替换 + 三处缺陷修复，其余机器的数值一字不动**（因为形状①②的曲线本来就保留）。
> 另附**"备选·全指数版"**列，供用户判断"是否要顺手把线性机器也提速"。

#### 表 1：范围充电器 `getChargeRate()`（FE/tick，`Config.rangeChargerChargeRate` 默认 500）

| 配置 | 改前 | 落地版 | 备选·全指数版 |
|---|---|---|---|
| 无升级 | 500 | **500（不变）** | 500 |
| 1 速度 | 1,000 | **1,000（不变）** | 1,000 |
| 2 速度 | 2,000 | **2,000（不变）** | 2,000 |
| 3 速度 | 4,000 | **4,000（不变）** | 4,000 |
| 6 速度 | 32,000 | **32,000（不变）** | 32,000 |
| 1 堆叠（power 3） | 4,000 | **4,000（不变）** | 4,000 |
| 3 速度 + 3 堆叠（power 12） | 2,048,000 | **2,048,000（不变）** | 2,048,000 |
| 6 堆叠（power 18） | 131,072,000 | **131,072,000（不变）** | 131,072,000 |
| **`rangeChargerChargeRate = 8192` + 6 堆叠** | **负数（`8192<<18` 溢出 int）** | **`Integer.MAX_VALUE`（2147483647）** | 同落地版 |
| **`rangeChargerChargeRate = 1000000`（配置上限）+ 3 速度 3 堆叠** | **负数（`1e6<<12` 溢出）** | **`Integer.MAX_VALUE`** | 同落地版 |
| **漏斗往一格塞 64 个速度升级** | **`500 << 64` → Java 掩码 `64&31=0` → 500（反而变慢！）** | **`level(64)=6` → 32,000（符合直觉）** | 同落地版 |

**注**：`getChargeRate()` 的返回值在上游一律被 `Math.min(getChargeRate(), energyStorage.getEnergyStored())` 夹住（`RangeChargerBlockEntity.java:453,606,679,710`），而默认能量容量是 10,000,000 FE（`Config.java:37-39`）⇒ **131,072,000 这个数在实际供电上是"永远被缓存夹住"**，改前改后都如此。溢出成负数才是真问题（会让 `Math.min(负数, stored)` = 负数 ⇒ **完全不充电**）。

#### 表 2：定量保持器 / 高级定量保持器 `getDestroyRate()`（`Config.quantityKeeperDestroyRate` 默认 1）

| 配置 | 改前 | 落地版 | 备选·全指数版 |
|---|---|---|---|
| 无升级 | 1 | **1（不变）** | 1 |
| 1 速度 | 2 | **2（不变）** | 2 |
| 3 速度 | 8 | **8（不变）** | 8 |
| 6 速度 | 64 | **64（不变）** | 64 |
| 1 堆叠（power 3） | 8 | **8（不变）** | 8 |
| 3 堆叠（power 9） | 512 | **512（不变）** | 512 |
| 6 堆叠（power 18） | 262,144 | **262,144（不变）** | 262,144 |
| **`quantityKeeperDestroyRate = 1000000`（配置上限）+ 3 速度 3 堆叠** | **负数（`1e6<<12` 溢出）** | **`Integer.MAX_VALUE`** | 同落地版 |
| `Config.quantityKeeperDestroyRatePerSpeed`（=16） | **无效**（死配置） | **要么实现、要么删除**（见 §4.7） | 同落地版 |

> **另外一条语义分叉必须一起修**：`getDestroyRate()` 的注释与配置注释都写"每 **tick**"（`QuantityKeeperBlockEntity.java:570`、`Config.java:82`），但消费端把它当"每 **秒**"用——`QuantityKeeperNetworkNode.java:140` 变量名就叫 `ratePerSecond`，并交给 `KeeperOverflow.cooldownTicks(ratePerSecond)` / `batchSize(ratePerSecond)`，而 `KeeperOverflow.java:30` 是 `TICKS_PER_SECOND = 20` 且 `:42` 做 `20 / ratePerSecond`。**数值行为是"每秒"**，注释是"每 tick"。统一工具落地时**只改注释与命名，不改数值**（否则手感会变 20 倍）。

#### 表 3：归流缓存仓 `getScanInterval()`（tick，越小越快；`Config.collectionCacheScanInterval` 默认 20）

半径 1（`radiusFactor = 1`）：

| 速度升级数 | 改前（`max(1, 20/(1+n))`） | 落地版（`max(1, 20 >> n)`） | 变化 |
|---|---|---|---|
| 0 | 20 | 20 | 相同 |
| 1 | 10 | 10 | 相同 |
| 2 | 6 | 5 | **快 1.2×** |
| 3 | 5 | 2 | **快 2.5×** |
| 4 | 4 | 1 | **快 4×** |
| 5 | 3 | 1 | **快 3×** |
| 6 | 2 | 1 | **快 2×** |

半径 5（`radiusFactor = 1+(5-1)/2 = 3`）：

| 速度升级数 | 改前（`max(1, 20/((1+n)*3))`） | 落地版（`max(1, (20/3) >> n)` = `max(1, 6>>n)`） | 变化 |
|---|---|---|---|
| 0 | 6 | 6 | 相同 |
| 1 | 3 | 3 | 相同 |
| 2 | 2 | 1 | **快 2×** |
| 3 | 1 | 1 | 相同 |
| 4~6 | 1 | 1 | 相同（已到下限） |

⚠️ **这是本次唯一会改手感的既有机器**：中高速度段扫描更快（最多 4×）。要"零手感变化"就必须放弃形状③的统一，保留 `base/((1+n)*radiusFactor)`（它本身没有数值缺陷，只有曲线不统一）。**这一条需要用户拍板**（见 §⑤ 第 3 条）。

#### 表 4：归流缓存仓 `getProcessRate()` / `getTransferBatch()`（个/tick）

| 配置 | 改前 | 落地版 | 备选·全指数版 |
|---|---|---|---|
| 速度 0..6 → `getProcessRate` | 1,2,3,4,5,6,7 | **不变** | `1,2,4,8,16,32,64`（满级 **9.1×**） |
| 堆叠 0..6 → `getTransferBatch` | 64,128,...,448 | **不变** | 不变（本来就是线性） |
| 满级删除速率 `getDeleteRatePerTick()` | `7 × 448 = 3,136` | **3,136（不变）** | `64 × 448 = 28,672`（**9.1×**） |

#### 表 5：装填器（`SchematicLoaderBlockEntity`）

| 项 | 速度 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| `getMaterialPullLimit()` 改前 | 8 | 16 | 32 | 64 | 128 | 256 | `Integer.MAX_VALUE` |
| 落地版 | 8 | 16 | 32 | 64 | 128 | 256 | `UNLIMITED`（不变） |
| 每 tick 拉取速率 改前 | 0.5 | 1 | 2 | 4 | 8 | 16 | `Double.MAX_VALUE` |
| 落地版 | 0.5 | 1 | 2 | 4 | 8 | 16 | `UNLIMITED`（不变） |

⇒ **装填器数值零变化**（只是把 `speed >= 6` 的字面量换成 `isMaxed()`）。

#### 表 6：固定节拍（不吃升级）

| 项 | 改前 | 落地版 |
|---|---|---|
| 范围充电器饰品槽扫描间隔 | `Config.rangeChargerCuriosScanInterval`（默认 5） | `intervalTicks(5, 0)` = 5（**数值不变**） |

#### 表 7：**没有实现的**注册（改前改后都是"不生效"）

| 项 | 改前 | 落地版（若采纳 §4.5 的迁移策略） |
|---|---|---|
| `sequence_assembly_executor` 的速度升级 ×6 | **注册了但无槽、无代码 = 不生效** | 二选一：**要么给它加槽 + 接 `RsccRateLimiter`**，**要么从 `RegisterUpgradeDestinations` 摘掉**（推荐后者，除非用户要给它加速） |

### 4.5 迁移策略：**建议「一次性全改 + 分层落地」**

| 方案 | 优点 | 缺点 | 判断 |
|---|---|---|---|
| **一次性全改**（4 个 BE、6 处公式、同一次提交） | ①"逻辑统一"这个诉求只有一次性能满足；②自检影响面**极小**（见 §4.6，只有 1 个自检锚定了调用点，且不锚定数值）；③不存在"新老两套口径并存"的中间态 | 一次提交的回归面较大 | ✅ **推荐** |
| 新增机器先用 / 旧机器逐步迁移 | 回归面小 | 会长期存在两套口径，而用户要的正是"统一"；且新机器（请求器/接收器）会先按新口径上线，旧机器后改 ⇒ **同一功能的两台机器速度不一样**，这正是用户抱怨的 | ❌ 不推荐 |
| 只改缺陷、完全不碰曲线 | 零手感风险 | 形状③仍是倒数曲线 ⇒ "统一"不成立 | ⚠️ 折中方案，作为**保底选项**保留（见 §5 第 3 条） |

**推荐的分层执行顺序（一次性提交内的 3 步）：**

1. **先落工具 + 缺陷修复（零手感变化）**：新增 `RsccRateLimiter`；把 #5/#6/#7 的 `<< ` 换成 `rateSaturating(...)`；把 #8/#9 的 `speed >= 6` 换成 `isMaxed()`；`getProcessRate`/`getTransferBatch` 换成 `batchSaturating` / 显式 `level()`。**此时全模组数值与改前逐格相同**（表 1/2/4/5 的"落地版"列）。
2. **再改曲线（唯一有手感变化的一步）**：把 #1 的 `getScanInterval()` 换成 `intervalTicks(...)`。**这是唯一需要用户点头的一步**（表 3）。
3. **最后收口**：修 §4.7 的三处（死配置、死注册、命名分叉）+ 让新机器（网络资源请求器/接收器）**从第一天起就走 `RsccRateLimiter`**，并把"每台机器必须走 `RsccRateLimiter`"写进 `docs/` 的实现契约。

### 4.6 风险：既有自检有没有锚定这些公式？——**有 1 个，且只锚定"调用点"不锚定"数值"**

**grep 方法与结论**（对 `tools/*.py` + `tools/*.ps1` 全量扫描，关键词 `getScanInterval|getProcessRate|getChargeRate|ChargeRate|ScanInterval|workTickRate|DestroyRate|destroyRate|DeleteRate|getUpgradeCount|getSpeedUpgradeCount|speed_upgrade|stack_upgrade|RateLimiter|rangeChargerChargeRate|quantityKeeperDestroyRate|collectionCacheScanInterval|getTransferBatch|getMaterialPullLimit|storageSlotCapacity`）：

| 文件 | 行 | 内容（原文） | 我们改了公式会红吗？ |
|---|---|---|---|
| `tools/selfcheck_collection_delete_mode.py` | 105-106 | `check("删除使用 getProcessRate() 作为每 tick 处理组数", "final int itemBudget = Math.max(1, getProcessRate());" in block)` | **不会红**（只要求**调用** `getProcessRate()`；我们保留这个方法名与调用点） |
| 同上 | 107-108 | `check("删除使用 getTransferBatch() 作为单次吞吐", "final int transfer = Math.max(1, getTransferBatch());" in block)` | **不会红**（同上） |
| 同上 | 111-113 | `check("暴露 getDeleteRatePerTick = 组数 × 吞吐（界面与快照同源）", "public long getDeleteRatePerTick()" in block and "Math.max(1, getProcessRate()) * Math.max(1, getTransferBatch())" in block)` | **⚠️ 会红**——这条锚定了 `getDeleteRatePerTick()` 的**方法体字面量**。若我们把它改写成 `RsccRateLimiter.multiplySaturating(...)`，必须同步改这个自检（这是**唯一**需要动的自检）。**建议**：`getDeleteRatePerTick()` 这次**保持字面量不动**（它本来就是派生量、不涉及 `<<`/除法，无缺陷），就可以做到**零自检改动**。 |
| 同上 | 114-116 | `check("回流与删除读的是同一对升级量（口径不会分叉）", "int budget = getProcessRate();" in block and "final int transfer = getTransferBatch();" in block)` | **不会红**（同 105-108） |
| `tools/selfcheck_round52_charger_curios.py` | 184 | `... and "curiosScanCooldown = Math.max(0, Config.rangeChargerCuriosScanInterval - 1);" in next_pass` | **不会红**（饰品槽节拍不改数值；若换成 `intervalTicks(Config.rangeChargerCuriosScanInterval, 0) - 1`，**会红** ⇒ 这行建议也不动） |
| 同上 | 194-200 | 断言 `defineInRange("rangeChargerCuriosScanInterval", 5, 1, 1200)` / 静态字段 / 赋值 / 使用点 | **不会红**（配置项不动） |
| `tools/selfcheck_assembly_cancel_immediate.py` | 11-13 | docstring 里的说明：`work tick 速率见 UpgradeContainer.DEFAULT_WORK_TICK_RATE = 9` | **不会红**（只是注释文本，不是断言） |
| `tools/selfcheck_round9_feedback.py` | 178 | `report("锚点: 「未阻塞的其它资源逐步回网、阻塞的留在缓存」—— 每 tick 受处理速率 / 吞吐升级约束，...")` | **需人工核对**（`report` 是文本上报，不是 `check` 断言；本轮**未读全文**，标为待核） |
| `tools/selfcheck_adv_keeper_slots.py` | 158 | `"单种升级上限仍为 6（与 6 格插件槽一致）——只是「判定」，不负责搬走超额部分"` | **不会红**（锚定的是"上限 6"，我们正是把 6 变成 `RsccRateLimiter.MAX_LEVEL` 的来源；**前提是 `UpgradeSlot.MAX_PER_UPGRADE` 仍是 6**） |

**⇒ 结论**：只要**不碰 `getDeleteRatePerTick()` 的字面量与 `curiosScanCooldown` 的赋值写法**，可以做到**零自检改动**落地统一工具。落地时必须跑 `tools/_run_all_selfchecks.py`（既有惯例，见 `docs/RESOURCE_PICKER_DESIGN.md` §5.2 第 3 行）。

**另外**：`docs/RESOURCE_PICKER_DESIGN.md` §5.2 的 grep 结论（"没有任何自检锚定 `AbstractGridScreen` / `GridContainerMenu` / `GridScreen` / `ResourcePicker`"）本轮**未重新执行**——若 §③ 落地，建议重跑一次该 grep。

### 4.7 风险清单（**全部带证据**）

| # | 风险 | 证据 | 影响 | 建议 |
|---|---|---|---|---|
| R1 | **范围充电器 `<<` 溢出成负数** | `RangeChargerBlockEntity.java:155` `Config.rangeChargerChargeRate << getUpgradeCount()`；`Config.java:35` 允许到 `1000000`；合法配置（6 堆叠 → power 18）下 base ≥ 8192 即溢出 | `getChargeRate()` 返负数 → `Math.min(负数, stored)` = 负数 → **完全不充电**；且 `RangeChargerScreen.java:220` 也照抄同一公式，界面会显示负数 | 用 `rateSaturating(...)` |
| R2 | **充电器的 BlockEntity 级计数可以被"一格 64 个"骗过，并导致移位掩码** | `RangeChargerBlockEntity.java:93` `new ItemStackHandler(6) { ... }`（**未覆写 `getStackLimit` ⇒ 默认 64**）；`:310-322` `validateUpgradeSlots` 是 `counts[kind]++`（**数槽，不数个数**）；`:140-151` `countUpgrades` 却是 `count += stack.getCount()` | 漏斗/管道往一格塞 64 个速度升级 ⇒ `getUpgradeCount() = 64` ⇒ `500 << 64` ⇒ Java 掩码 `64 & 31 = 0` ⇒ **500**（比 6 个还慢）。纯玩家操作走菜单（`UpgradeSlot.getMaxStackSize = 1`）到不了，但自动化到得了 | `RsccRateLimiter.level()` 一律夹到 6（I4 就是为它设计的）；另建议 `ItemStackHandler` 覆写 `getStackLimit` 返回 1 |
| R3 | **`quantityKeeperDestroyRatePerSpeed` 是死配置项** | 全工程只有 3 处（`Config.java:87` 定义、`:276` 字段、`:346` 赋值），**无读取点**；而 `QuantityKeeperBlockEntity.java:574-580` 用 `<< power`。注释写"每个速度升级额外增加的每 tick 销毁数量"，与实现不符 | 玩家改了没效果（静默无效） | 要么实现、要么删掉并在 CHANGELOG 说明（**建议删**：它表达的是"线性加成"，与统一后的"每级 ×2"冲突） |
| R4 | **`sequence_assembly_executor` 注册了速度升级但无处可插、也不生效** | `RegisterUpgradeDestinations.java:51-53`；`SequenceAssemblyExecutorBlockEntity.java` 全文 `grep -i "upgrade\|speed"` = 0 命中；`SequenceAssemblyExecutorMenu.java` 无 `UpgradeSlot` | RS 升级物品的 tooltip 会误导玩家（"可用于序列装配执行器 ×6"） | 从注册表摘掉，或补槽 + 接工具（需用户拍板） |
| R5 | **`getDestroyRate()` 的"每 tick / 每秒"命名分叉** | 注释：`QuantityKeeperBlockEntity.java:570`「每秒销毁的过量数量」 vs `Config.java:82`「每 tick 基础销毁的过量数量」；消费者：`QuantityKeeperNetworkNode.java:140` 变量名 `ratePerSecond` + `KeeperOverflow.java:30,42`（`TICKS_PER_SECOND = 20`） | 阅读代码的人会算错 20 倍（**行为**是每秒，**注释**一半写每 tick） | 只改命名与注释，**不改数值** |
| R6 | **`Config.quantityKeeperDestroyRate` 也能溢出** | `Config.java:83` 允许到 `1000000`；power 合法最大 18 ⇒ `1e6 << 18` 溢出 | 销毁速率变负数 ⇒ `KeeperOverflow.cooldownTicks(负数)` 会被 `Math.max(1, ...)` 兜住，但 `batchSize` 走 `Math.max(1L, rate/20)` ⇒ 表现为"只销毁 1 个" | `rateSaturating(...)` |
| R7 | **统一工具会改变归流缓存仓的扫描频率（最多 4×）** | §4.4 表 3 | 手感变化（正面：满级不再卡在 2 tick） | 需用户拍板（§5 第 3 条） |
| R8 | **新机器（请求器/接收器）还没有 Java 代码，规格可能先于统一工具落地** | `git status --short` 只显示 `assets/.../network_resource_{receiver,requester}.json`、`models/...`、`textures/...` 存在，`src/main/java/**` 里没有任何 `*Receiver*.java` / `*Requester*.java`（与 `docs/RESOURCE_PICKER_DESIGN.md` §6.1 第 4 条的实测一致） | 如果先写机器后写工具，两套口径会分叉 | **先把 `RsccRateLimiter` 写出来再写机器** |

---

## ⑤ 未证实 / 需拍板（诚实清单）

### 5.1 未证实（**本轮没有证据，不猜**）

1. **`toggleResourceType()` 的循环顺序（item → fluid → null）** —— 沿用 `docs/RESOURCE_PICKER_DESIGN.md` §5.3 的结论；本轮只证明了 `ResourceTypeSideButtonWidget` 调它、`ResourceTypes` 只有 ITEM/FLUID 两个单例，**没有独立反编译 `toggleResourceType` 的方法体**。
2. **`getCurrentGridResource()` 在"点击那一帧"与鼠标位置严格同步** —— 逻辑链完整（`currentGridSlotIndex` 只有渲染路径写、全类仅 2 个写入点），但**没有实机验证**。
3. **`leftPos/topPos` 与 `AbstractStretchingScreen.renderBg` 里局部算出的 `(width-imageWidth)/2` 恒等** —— 算式相同，但**没有找到把它们钉在一起的字节码**。
4. **十周年丝带与存储区重叠的精确矩形** —— 只读到绘制矩形（`x-3, y-15, 82×15`），**没读 `isOverTenthAnniversaryRibbon` 的判定**。
5. **`mouseClicked` / `mouseScrolled` 覆写后，"选中即发包"的时序** —— 与既有文档 §6.1 第 6 条同源，路径已证明，**实机未测**。
6. **Create 的"数量改不动"是否在 6.0.10 真的表现为玩家可见的 bug** —— 我是从 `RedstoneRequesterConfigurationPacket.applySettings` 的循环边界（`:48-51` 只遍历 `stacks.size()`）**读出来的**，**没有实机复现**。
7. **`ResourceCodecs.AMOUNT_CODEC` 的完整 `MapCodec` 外形**（是否有 `Commented`/`ErrorHandling` 包装层） —— 只读到 `RecordCodecBuilder{resource: CODEC, amount: Codec.LONG}` 这一段字节码，**外层包装未逐条追**。对落盘影响不大（DFU 会给出 `DataResult`），但**建议首次实现时用一条真实存档手测往返**。
8. **`tools/selfcheck_round9_feedback.py:178` 的 `report(...)` 到底断言了什么** —— 本轮未读全文。若它内部有 `check`，需重跑确认。
9. **新增 `RsccRateLimiter` 后 `tools/_run_all_selfchecks.py` 的完整影响** —— 我只对"公式改动"做了定向 grep（§4.6），**没有全量跑一遍自检**（本轮不改代码，跑了也没意义）。
10. **RS 的 `UpgradeContainer` 是否被本工程任何机器使用** —— 本轮确认了本工程所有机器都用自己的 `countUpgrades`（4 个 BE 各自实现），**但没有 grep `UpgradeContainer` 的使用点**来"证明一个都没用"。从 §4.1 的清点看是没有，标为未证实。

### 5.2 需要用户拍板（**这些是决策，不是事实**）

| # | 决策点 | 选项 | 我的建议 |
|---|---|---|---|
| 1 | **清单上限** | 9（照抄 Create）/ **16** / 32 / 不限 | **16**（理由见 §1.5）；若用户有"一次请求 30+ 种材料"的实际场景就上 32 |
| 2 | **流体数量的默认值**（新增条目时） | 1 桶（1000 mB）/ 1 mB / 1000 mB 但显示 1 桶 | **1000 mB**（= 1 桶），显示走 `bucketHint` |
| 3 | **归流缓存仓的扫描曲线** | (a) 保留倒数（**零手感变化**，"统一"降级为"只统一 API 与不变量"）/ (b) 改成 `base>>n`（**中高速度段快 1.2×~4×**） | 若用户在意"手感别变" ⇒ (a)；若在意"满级就该满速" ⇒ (b)。**这是本次唯一的手感取舍**（表 3） |
| 4 | **堆叠升级的语义** | (a) 保留"1 堆叠 = 3 速度"（充电器/保持器数值不变）/ (b) 改为"只影响批量"（充电器满级速率从 131,072,000 掉到 32,000，**削弱 ~4000×**） | **(a)**。(b) 更"理论统一"，但对既有机器是灾难性削弱 |
| 5 | **`sequence_assembly_executor` 的速度升级** | (a) 从 `RegisterUpgradeDestinations` 摘掉 / (b) 补一个升级槽 + 接 `RsccRateLimiter` | **(a)**，除非用户明确要给执行器加速 |
| 6 | **`quantityKeeperDestroyRatePerSpeed` 死配置** | (a) 删掉 / (b) 实现成"每级线性 +N"（会与 ×2 曲线冲突） | **(a) 删**，CHANGELOG 注明 |
| 7 | **网格里右键 / Shift+左键的语义** | (a) 右键 = 反选（从清单移除）/ (b) 完全禁用右键 / (c) 右键 = 仍按 RS 原样取出 | **(a)**；若要保留 RS 的"取出当前格"能力，可挂在 `Ctrl+左键` 上（但会重新打开发包路径，需谨慎） |
| 8 | **十周年丝带的点击被我们抢掉** | (a) 接受（3 天/年）/ (b) 在覆写里先排除丝带矩形 | **(a)**，并在实现里留 TODO |
| 9 | **清单条目是否允许重复资源** | 允许（Create 式）/ 自动合并 | **自动合并**（避免"两条看起来一样、实际是两笔"的误解） |
| 10 | **统一工具落地节奏** | 一次性全改（推荐）/ 只改缺陷不碰曲线 / 逐步迁移 | **一次性全改**，分 3 步（§4.5） |

### 5.3 本轮**未做**（边界声明）

- 未修改任何 `.java`；未修改 `run/`；未新增资源/贴图/lang；未跑编译；未启动游戏。
- 未实测「打开网格 → 左键选中 → 清单新增一条 → 数量输入 → 落盘 → 重进存档」的端到端行为（全部为静态证据）。
- 未实测统一工具落地后的自检全绿（**本轮不改代码 ⇒ 没有必要跑**）。
- 取证产物落在 `_audit_tmp/pacer/`（`javap` 全量输出、`extract.py` 辅助脚本、`create_src_path.txt`），未提交。

---

## 附录 A：本轮用到的可复现命令

```powershell
# RS 2.0.9 jar（唯一制品）
$jar = "D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar"

# ① 网格屏幕的完整成员（证明 protected/private/final）
& D:\java21\bin\javap.exe -p -classpath $jar com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen
& D:\java21\bin\javap.exe -p -classpath $jar com.refinedmods.refinedstorage.common.grid.screen.GridScreen
& D:\java21\bin\javap.exe -p -classpath $jar com.refinedmods.refinedstorage.common.support.AbstractBaseScreen
& D:\java21\bin\javap.exe -p -classpath $jar com.refinedmods.refinedstorage.common.support.stretching.AbstractStretchingScreen

# ② 渲染顺序 / init 链 / 点击链（字节码偏移就是本文档里的偏移）
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.support.stretching.AbstractStretchingScreen
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.support.AbstractBaseScreen

# ③ 原版渲染顺序（AbstractContainerScreen.render 的 :95/:97-99/:105-115/:117）
#    sources jar：build\moddev\artifacts\neoforge-21.1.248-sources.jar
#    net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java:91-118, 155-158

# ④ 流体单位口径
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.neoforge.PlatformImpl                       # getBucketAmount() = 1000L
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.support.resource.FluidResourceType      # normalizeAmount / getDisplayAmount
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.support.resource.ItemResourceType
& D:\java21\bin\javap.exe -p    -classpath $jar com.refinedmods.refinedstorage.common.support.resource.ResourceTypes          # ITEM / FLUID 公开单例
& D:\java21\bin\javap.exe -p    -classpath $jar com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs         # CODEC / AMOUNT_CODEC / STREAM_CODEC

# ⑤ 升级公式 + 负数坑
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer
#   → notifyListener(): defaultWorkTickRate - speedAmount*2；构造器 bipush 9

# ⑥ 取出链的包级证据
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.grid.strategy.ClientGridExtractionStrategy   # → sendGridExtract
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.grid.strategy.ClientGridScrollingStrategy    # → sendGridScroll
& D:\java21\bin\javap.exe -p    -classpath $jar com.refinedmods.refinedstorage.common.support.packet.c2s.C2SPackets

# ⑦ Create 对照（sources jar 行号即真实行号）
#   create-1.21.1-6.0.10-280\23e1219501c0debfa0bb56c30ef8e0193341aae5\create-1.21.1-6.0.10-280-sources.jar
#   PackageOrder.java、BigItemStack.java、RedstoneRequesterScreen.java、RedstoneRequesterMenu.java、
#   RedstoneRequesterConfigurationPacket.java

# ⑧ 网格分页切换控件
& D:\java21\bin\javap.exe -p -c -classpath $jar com.refinedmods.refinedstorage.common.grid.screen.ResourceTypeSideButtonWidget
& D:\java21\bin\javap.exe -p    -classpath $jar com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu    # getResourceType / toggleResourceType
& D:\java21\bin\javap.exe -p    -classpath $jar com.refinedmods.refinedstorage.common.api.grid.view.GridResource          # getResourceForRecipeMods / belongsToResourceType

# ⑨ 数量输入（原版 API）
#   net/minecraft/network/codec/ByteBufCodecs.java:384,388   list() / list(int maxSize)
```

## 附录 B：一句话给实现阶段的交接

> **清单**用 `List<ResourceAmount>` + `ResourceCodecs.AMOUNT_CODEC`（落盘）/ `AMOUNT_STREAM_CODEC.apply(ByteBufCodecs.list(16))`（网络），上限 16 写成一个常量；
> **数量**每条一个 `EditBox` 走 `RsccNumberField.wire()`（内部一律 `long`：物品=个数、流体=mB；显示走 `bucketHint`）；
> **网格**继承 `GridScreen` + 自己的 `AbstractGridContainerMenu` 子类，覆写 `init()` 加面板（`super.init()` 之后！）、覆写 `mouseClicked`/`mouseScrolled` 两处切断 `sendGridExtract`/`sendGridScroll`、覆写 `renderRows` 自绘"已选"底色；
> **节拍**一切走 `RsccRateLimiter`（`level()` 夹到 6、`shiftSaturating` 饱和、`intervalTicks` 无除法），逐台对照表见 §4.4。
