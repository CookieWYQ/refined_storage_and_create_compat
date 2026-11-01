# 网络资源接收器 + 请求器：需求复核 / API 调研 / 设计

> **文档状态**：需求分析 + API 调研 + 设计，**不含实现**。本轮**未修改任何 Java 源码**，未改 `run/`。
>
> **证据口径**：每条结论都带「类名 + 文件:行号」或 `javap` 输出。**找不到证据的一律写「未证实」**。
>
> **依赖版本**：Minecraft 1.21.1 / NeoForge 21.1.248 / Refined Storage **2.0.9** / Create **6.0.10**。
>
> **源码来源**：
> - Create：`local_src/external/Create/src/main/java/...`（仓库内源码检出）
> - RS：**只有 jar，没有 2.0.9 的 sources jar**。证据来自
>   `D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar`
>   的 `javap -p -c` 输出。
>   （注：RS 的 **2.0.0** *sources* jar 在本机确实存在
>   `...\refinedstorage-neoforge\2.0.0\b00ad24375231bee05075dd03d34609390597e38\refinedstorage-neoforge-2.0.0-sources.jar`，
>   但它不是我们要对齐的 2.0.9，本文件**不把它当作 2.0.9 的证据**。）
> - Mekanism：`local_src/external/Mekanism/src/api/java/mekanism/api/chemical/...`
> - 本工程：`src/main/java/cretae/cookiewyq/rs_create_compat/...`

---

## ① 需求复核（对用户原话的复核与我可能的误读纠正）

### 1.1 用户原话拆解与我读到的语义

| # | 用户原话要点 | 我读到的语义 | 判定 |
| - | ------------ | ------------ | ---- |
| 1 | 「首先要有一个网络资源接收器」 | 新方块 A | ✅ 成立 |
| 2 | 「他的行为类似于自动合成仓」 | A 的**架构**参照 RS 自动合成仓：RS 网络节点方块 + 内部缓存 + 面朝向 + 能力暴露 | ✅ 成立（但见 1.2 的边界说明） |
| 3 | 「有朝向，朝向指向的容器会被输出」 | A 有 `FACING`，把缓存内容输出到该面相邻的容器 | ✅ 成立 |
| 4 | 「也可以有缓存」 | A 自带内部缓存（物品 / 流体），缓存的作用见 §⑦ | ✅ 成立（「可以有」= 设计自由度，我按「必须有」设计，理由见 §⑦.2） |
| 5 | 「这个和那个自动合成仓保持一致的逻辑，就是使用同一个命令进行开关」 | A 复用**同一条指令**：`/rs_create_compat autocrafter storage on\|off` | ✅ 成立，但**这是一条最需要用户确认的要求**，见 1.3 |
| 6 | 「必须设定一个名字，并且这名字不能重复」 | A 必须命名；名字是**功能前提**，不是装饰 | ✅ 成立 |
| 7 | 「所有的名字重不重复针对的是同一网络下」 | 唯一性作用域 = **同一个 RS 网络** | ✅ 成立 |
| 8 | 「如果说有什么子网络或说别的网络中……这个是没有关系的」 | 跨网络 / 跨维度**不**冲突 | ✅ 成立 |
| 9 | 「这个名字必须要设定才可以用，否则他就无法使用」 | 空名 ⇒ A **完全不工作**（不输出、不作为投递目标） | ✅ 成立 |
| 10 | 「需要通过一个红石脉冲来定向发送这一个物品的包裹」 | 新方块 B = 请求器，脉冲触发 | ✅ 成立 |
| 11 | 「可以设定目标，就是发送到哪里，然后呢再设定一下什么资源」 | B 的配置 = (目标名, 资源, 数量) | ✅ 成立 |
| 12 | 「发送的所有资源包括流体和气体和这个物品都要有」 | B 支持 物品 / 流体 / 气体 三类 | ⚠️ 见 §⑥（气体现状未实现） |
| 13 | 「没有什么数量上限」 | 单次发送量不受 RS 原生 quota（物品 1×64^n）限制 | ✅ 成立，处理方式见 §⑦.4 |
| 14 | 「速度升级是装在这个网络资源接收器这里」 | 升级槽在 **A** | ✅ 按原话执行；但见 1.4 的补充判断 |
| 15 | 「速度升级对叠升级这样子」 | 只收 速度升级 + 叠加升级，二者叠加生效 | ✅ 成立 |
| 16 | 「其他升级不可以安装」 | 白名单**只有**这两类，其余一律拒收 | ✅ 成立 |
| 17 | 「6 个升级槽」 | `UpgradeContainer(size = 6)` | ✅ 成立 |

### 1.2 需要澄清的边界：「行为类似于自动合成仓」到底像哪一部分

「自动合成仓」在本工程里有**两层**含义，容易混淆，必须分开：

- **(a) RS 自己的方块 `AutocrafterBlockEntity`**（`refinedstorage:autocrafter`）——
  它是 **RS 网络节点方块**，抽象基类
  `AbstractBaseNetworkNodeContainerBlockEntity<PatternProviderNetworkNode>`
  （证据：`javap` 输出
  `public class com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity extends com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity<com.refinedmods.refinedstorage.api.network.impl.node.patternprovider.PatternProviderNetworkNode>`）。
  它**只有 4 个升级槽**（证据：`UpgradeContainer` 的一个构造器把默认 size 写死为 `4`——

  ```
  public UpgradeContainer(UpgradeDestination, UpgradeContainerListener, int);
    Code:
       0: iconst_4          // ← 默认槽数 = 4
       2: aload_1
       ...
       5: invokespecial #7  // <init>:(IL...UpgradeDestination;L...UpgradeContainerListener;I)V
  ```
  另有 `UpgradeContainer(int, UpgradeDestination, UpgradeContainerListener, int)` 允许**显式指定槽数**）。
  ⇒ **A 要 6 槽，必须自己 new 一个 6 槽的 `UpgradeContainer`，不能照抄自动合成仓的 4 槽。**

- **(b) 本工程给自动合成仓加的「内部存储」改造**（`mixin/AutocrafterStorageMixin.java`）——
  这才是用户真正在说「行为类似」的那一层：钩住 RS 的
  `AutocrafterBlockEntity#accept`，让产物**先落进方块自己的内部缓存**，
  再由玩家/管道用 capability 取走（`mixin/AutocrafterStorageMixin.java:149-205`，内部存储字段在
  `:57-63`，能力暴露与开关在 `:95-107`）。

**结论**：A 应当**同时**模仿 (a) 的「RS 网络节点方块 + 面朝向 + 升级容器」骨架，与 (b) 的
「先落内部缓存、再从缓存往外走」的数据流。**A 不是自动合成仓的子类，也不应去 Mixin 自动合成仓**。

### 1.3 关于「使用同一个命令进行开关」——这条我建议**改口径**，请你确认

工程里现在**唯一**的「自动合成仓开关」指令是：

```
/rs_create_compat autocrafter storage on     开启（执行者周围 32 格内全部自动合成仓）
/rs_create_compat autocrafter storage off    关闭（先把仓内物品/流体写回网络，装不下则取消）
```

证据：`command/CompatCommands.java:101-111`（指令树）、`:420-449`（`setStorage` 处理器）、
`:41`（`RADIUS = 32.0`）、`:43-49`（语言键）；语言键实现在
`assets/rs_create_compat/lang/zh_cn.json:833-838`。

它的语义**逐台**、**按网络分组整体生效**、**作用范围是执行者周围 32 格**，并且「关闭」时
是要**真的把内容写回网络**的（`support/AutocrafterStorageController.java:104-149`，
先 `SIMULATE` 再 `EXECUTE`，装不下就整组不关）。

**这里有三个真实冲突，必须由你选：**

| 方案 | 含义 | 优点 | 代价 |
| ---- | ---- | ---- | ---- |
| **S1（严格照原话）** | A 直接复用 `/rs_create_compat autocrafter storage on\|off`，同一条指令同时开关两种方块 | 用户原话逐字满足；玩家只需记一条 | ① 指令名里写着 `autocrafter`，却去开关一个不叫自动合成仓的方块，**语义名不副实**；② 「关闭」时 A 的缓存也要回写网络，需要给 A 实现与 `AutocrafterStorageController#disableGroup` 等价的守恒路径（**可以做**，见 §⑦.5）；③ 半径 32 混合影响两类方块，玩家无法只关其中一类 |
| **S2（推荐）** | **同一条指令**但**扩一档**：`/rs_create_compat autocrafter storage on\|off` 保持原义，新增 `/rs_create_compat receiver storage on\|off`（**同一个处理器、同一套守恒语义、只是一次作用一类方块**） | 语义诚实；两类方块可以分别开关；实现上仍是「一条指令一套逻辑」 | 与原话「使用同一个命令」有出入 |
| **S3** | 完全另起 `/rs_create_compat receiver on\|off`，与自动合成仓那条**互相独立**，只是代码复用 | 最清楚 | 与原话出入最大 |

**我的判断**：**S2**。理由是原话的**意图**是「不要为同一个概念发明第二套开关语义 / 第二套守恒规则」，
而不是「指令字面必须一模一样」。S2 用**同一个处理器 + 同一套守恒判定**满足意图，
同时避免 S1 的语义错位。**这条请你拍板**（列入 §⑧）。

### 1.4 关于「升级槽到底在 A 还是 B，还是两边都要」

用户原话只说「速度升级是装在这个网络资源接收器这里」（= **A**）。
但用起来需要**两组不同的速率**，它们物理上作用在不同环节：

| 环节 | 想要的效果 | 该由谁承担 |
| ---- | ---------- | ---------- |
| **脉冲 → 取料 → 投递到 A**（B 侧） | 一个脉冲后多快把东西送到 A | 逻辑上属于 B |
| **A 缓存 → 朝向容器**（A 侧） | A 每秒往外吐多少 | 逻辑上属于 A |
| **单次投递量** | 一个脉冲一次发多少 / A 一次推多少 | 两边都说得通 |

**我的建议（推荐方案）**：

1. **A 一定装 6 槽升级**（用户明确要求，且 A 是「有缓存、持续往外输出」的那台机器，
   速度升级直接改变它的输出节拍）——**这条无争议，照做**。
2. **B 也建议给 6 槽**（同样只收 速度 + 叠加）。理由：B 的「一次脉冲要搬完配置里的量」这件事
   在**无数量上限**的前提下（原话第 13 条）必然要分批；
   **速度升级 = 每 tick 搬几批，叠加升级 = 每批搬多少**，这两个旋钮正好缺一不可，
   否则「无数量上限」实际会退化成「一个脉冲搬不完，剩下的不知道什么时候搬」。
3. **两边的语义必须明确分开，且用 tooltip 写清楚**，否则玩家会以为「我在 A 装的速度升级也该加速 B」。

**如果只能选一个**（比如实现成本受限）：**把 6 槽给 A，B 不给槽**，此时
B 的「无数量上限」用一个**固定内部节拍**（例如每 tick 一批、每批 `Long` 量级）兜底，
并在 B 的界面明说「批量节拍不可升级」。**这条请你确认**（列入 §⑧）。

### 1.5 我的复述里**最可能误读**的两处（先自我纠正）

1. **「他的行为类似于自动合成仓」不等于「A 要参与自动合成」**。
   读原话上下文（"有朝向，朝向指向的容器会被输出"）可知，用户说的是**形态与内部缓存的组织方式**像，
   **不是**说 A 要变成合成仓的替代品。我按「形态像、角色不同」设计。
2. **「使用同一个命令进行开关」**——见 1.3，我不认为字面必须一致，但**必须由你确认**。

---

## ② Create 侧机制（类名 + 文件:行号）+「局限」的准确含义

### 2.1 Create 红石请求器（Redstone Requester）怎么工作

| 关注点 | 证据 |
| ------ | ---- |
| 方块类 | `com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlock`（`local_src/external/Create/src/main/java/com/simibubi/create/content/logistics/redstoneRequester/RedstoneRequesterBlock.java:45`） |
| 方块实体类 | `RedstoneRequesterBlockEntity extends StockCheckingBlockEntity implements MenuProvider`（同目录 `RedstoneRequesterBlockEntity.java:39`） |
| 注册 | 方块 `AllBlocks.java:1950-1957`；方块实体 `AllBlockEntityTypes.java:547-548`；菜单 `AllMenuTypes.java:78-79` |

**「设定目标」存哪**：一个**纯字符串**

```java
// RedstoneRequesterBlockEntity.java:44
public String encodedTargetAdress = "";
```

**「设定资源」存哪**：一个 `PackageOrderWithCrafts`（内含 `PackageOrder`，即 `List<BigItemStack>`）

```java
// RedstoneRequesterBlockEntity.java:43
public PackageOrderWithCrafts encodedRequest = PackageOrderWithCrafts.empty();
```

**持久化位置 = 方块实体 NBT**（不是数据组件）：

```java
// RedstoneRequesterBlockEntity.java:129-135（writeSafe）
tag.putBoolean("AllowPartial", allowPartialRequests);
tag.putString("EncodedAddress", encodedTargetAdress);
tag.put("EncodedRequest", CatnipCodecUtils.encode(PackageOrderWithCrafts.CODEC, registries, encodedRequest).orElseThrow());
// :119-127（read）逐字对应：Powered / Success / AllowPartial / EncodedRequest / EncodedAddress
```

**资源条目的表示**：`BigItemStack`（`content/logistics/BigItemStack.java:17-22`）——
**`ItemStack` + `int count`**，count 上限常量 `INF = 1_000_000_000`（`:31`）。
⇒ **Create 的「资源 + 数量」在类型层面就是「物品 + 32 位整数」，天然装不下流体 / 气体。**

**数据组件（只在物品形态上，用于「请求器被扳手打包带走」）**：
`AllDataComponents.AUTO_REQUEST_DATA`（`AllDataComponents.java:186` 附近）+ `AutoRequestData`
（`content/logistics/redstoneRequester/AutoRequestData.java`），写入/读出在
`RedstoneRequesterBlock.java:93-116`（`programRequester`）与 `:138-148`（`setPlacedBy`）。

**红石脉冲 → 发送**：

```java
// RedstoneRequesterBlock.java:150-157
public void neighborChanged(...) {
    if (pLevel.isClientSide()) return;
    pLevel.setBlockAndUpdate(pPos, pState.setValue(POWERED, pLevel.hasNeighborSignal(pPos)));
    withBlockEntityDo(pLevel, pPos, RedstoneRequesterBlockEntity::onRedstonePowerChanged);
}

// RedstoneRequesterBlockEntity.java:77-88
protected void onRedstonePowerChanged() {
    boolean hasNeighborSignal = level.hasNeighborSignal(worldPosition);
    if (redstonePowered == hasNeighborSignal) return;   // ← 边沿触发
    lastRequestSucceeded = false;
    if (hasNeighborSignal) triggerRequest();
    redstonePowered = hasNeighborSignal;
    notifyUpdate();
}

// :90-117 triggerRequest()
//  ① 请求为空 → 直接返回
//  ② getAccurateSummary() 看网络够不够
//  ③ !allowPartialRequests 且有任何一项不够 → 发 RedstoneRequesterEffectPacket(success=false) 并 return
//  ④ broadcastPackageRequest(RequestType.REDSTONE, encodedRequest, null, encodedTargetAdress)
```

⇒ 请求器**本身没有库存**：它只是「看目标网络的库存 + 向该网络广播一个打包请求」。

**「设定资源」在界面层的硬上限 = 9 种**

```java
// RedstoneRequesterMenu.java:70-82（saveData）
int slotX = 27; int slotY = 28;                       // :64-65
for (int i = 0; i < 9; i++) addSlot(new SorterProofSlot(ghostInventory, i, slotX + 20 * i, slotY));  // :83-84
// saveData：每格取 1 个 + 沿用旧 count
list.add(new BigItemStack(stackInSlot.copyWithCount(1), i < stacks.size() ? stacks.get(i).count : 1));
```

⇒ 请求器**最多 9 种资源**，每种一个 `int` 数量。

### 2.2 Create 包裹（Package）系统

| 概念 | 证据 |
| ---- | ---- |
| 包裹实体 | `PackageEntity extends LivingEntity implements IEntityWithComplexSpawn`（`content/logistics/box/PackageEntity.java:65`），字段 `public ItemStack box`（`:67`） |
| 包裹物品 | `PackageItem`，`public static final int SLOTS = 9`（`content/logistics/box/PackageItem.java:50`） |
| 包裹内容物 | 数据组件 `PACKAGE_CONTENTS`（`AllDataComponents.java`，`DataComponentType<ItemContainerContents>`），写入 `PackageItem.containing(ItemStackHandler)`（`PackageItem.java:97-101`），读出 `PackageItem.getContents(ItemStack)` → `new ItemStackHandler(9)`（`PackageItem.java:213-218`） |
| **目的地地址** | 数据组件 `PACKAGE_ADDRESS`（`AllDataComponents.java` 中 `register("package_address", ... Codec.STRING ...)`）；访问器 `PackageItem.getAddress/addAddress/clearAddress`（`PackageItem.java:104-110`、`:195-197`）；实体侧 `PackageEntity.getAddress()`（`PackageEntity.java:160-162`） |
| 地址如何被写入包裹 | 打包机 `PackagerBlockEntity#submitNewPackage`：`PackageItem.clearAddress(createdBox)` → 若请求带地址则 `PackageItem.addAddress(createdBox, fixedAddress)`（`PackagerBlockEntity.java:509-517`） |
| **收件人（地址）如何被匹配** | `PackageItem.matchAddress`（`PackageItem.java:180-193`）：空串配空串；`*` 通配；相等；**否则按 glob 双向匹配**（`net.createmod.catnip.data.Glob`） |
| 收件方 | `PackagePortBlockEntity`：`public String addressFilter`（`packagePort/PackagePortBlockEntity.java:41`）、`getFilterString()`（`:79-81`，`acceptsPackages==false` 时返回 null）、持久化键 `"AddressFilter"`（`:87`、`:98`） |
| 分拣 / 分发 | `LogisticsManager.broadcastPackageRequest`（`packagerLink/LogisticsManager.java:91-105`）→ `findPackagersForRequest`（`:107-...`）逐个打包机 `link.processRequest(...)`；**包裹是被物理投送到地址匹配的端口**的 |

**关键**：Create 的「地址」**只是一个字符串**，它**没有注册表**——
没有任何地方记录「现在世界上存在哪些地址」。地址的**权威定义**在
`PackagerBlockEntity.signBasedAddress`（`PackagerBlockEntity.java:76`，由告示牌读出，`:538-549`），
而**消费方**是包裹端口自己的 `addressFilter` 字符串。两端**各自手填**。

### 2.3 「点对点」的局限到底局限在哪（这是用户说的「局限性很大」）

逐条给证据，且明确区分「Create 的局限」与「我们照搬才会有的局限」：

| # | 局限 | 证据 / 机制 | 我们照搬吗 |
| - | ---- | ----------- | ---------- |
| L1 | **只能搬物品**，装不下流体 / 气体 | 包裹内容 = `ItemContainerContents`（`PackageItem.java:213-218`）；请求条目 = `BigItemStack(ItemStack, int)`（`BigItemStack.java:17-22`） | ❌ 不照搬：用 RS `PlatformResourceKey` 统一三类（§⑥） |
| L2 | **单包裹容量小**：9 格 × 每格 ≤ 64 | `PackageItem.SLOTS = 9`；打包循环每格 `int initialCount = Math.min(64, nextRequest.getCount())`（`PackagerBlockEntity.java:444`） | ❌ 不照搬：RS 量级 + 无上限（§⑦.4） |
| L3 | **请求器只有 9 种资源** | `RedstoneRequesterMenu.java:83-84` 固定 9 槽 | ❌ 不照搬：界面槽位另定 |
| L4 | **单次数量受限** | `BigItemStack.count` 是 `int`（`BigItemStack.java:31` 的 `INF`），且包裹实物容量才是真瓶颈 | ❌ 不照搬 |
| L5 | **地址是自由文本、可通配、无唯一性** | `matchAddress` 用 glob 双向匹配（`PackageItem.java:184-193`）；`*` 匹配一切 | ❌ 不照搬：**这正是用户要的「名字 + 同网络唯一」**（§④） |
| L6 | **地址无法枚举 / 无法查重** | Create 里没有任何「地址注册表」类 | ❌ 不照搬：我们做网络内的名字集合（§④） |
| L7 | **投递是物理过程**：包裹要真的飞过去 | `PackageEntity` 是实体（`PackageEntity.java:65`），走漏斗 / 蛙港 / 邮筒 / 列车 | ⚠️ **这是我们要去掉的核心局限**：改成 RS 网络内瞬时路由 |
| L8 | **发件方依赖收件方的库存网络** | 请求器 `getAccurateSummary()` 读的是**自己所在网络**的库存（`RedstoneRequesterBlockEntity.java:100` + `StockCheckingBlockEntity.java:29-31`），而包裹由该网络的打包机造出来 | ✅ 语义上可保留：请求器读**自己所在网络**的库存（这正是 RS 的做法） |

**结论**：
- 用户说的「局限性很大」，**核心就是 L1 + L5 + L6 + L7**：
  只能搬物品、地址靠手填自由文本且无法查重、投递要靠实体飞。
- **用 RS 网络替代可以绕开 L1、L2、L3、L4、L5、L6、L7**，因为：
  - L1/L2/L3/L4 靠 RS 的 `ResourceKey` + `long` 量级解决（§⑥、§⑦.4）；
  - L5/L6 靠「同网络名字注册表」解决（§④）；
  - L7 靠「不再产生包裹实体，请求器直接把资源写进目标方块」解决（§⑦）。
- **L8 不应绕开**：请求器读自己所在网络的库存，与 Create 语义一致，也与 RS 的
  `StorageNetworkComponent` 用法一致。

### 2.4 一个必须说清楚的替代关系

**我们不是在「给 Create 的包裹系统换后端」，而是在「另起一套不经过包裹的定向投递」。**
理由：Create 的打包 / 投递链路（`PackagerBlockEntity` → `PackageEntity` → `PackagePortBlockEntity`）
**全部以 `ItemStack` 为最小单位**（L1 的证据链），要让它承载流体/气体必须**改 Create 源码或大量 Mixin**，
不可接受。

⇒ **本设计里 B 与 Create 的包裹系统没有任何代码耦合**，只是**交互范式**（脉冲 + 目标 + 资源）向它致敬。

---

## ③ RS 侧可用能力清单（含我们既有可复用的实现）

### 3.1 我们的方块怎么拿到 RS 网络（**优先复用**）

标准骨架 = 继承 RS 的 `AbstractBaseNetworkNodeContainerBlockEntity<我们的节点>`：

| 我们的实现 | 证据 |
| ---------- | ---- |
| `SequenceExecutionChamberBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<SequenceExecutionChamberNetworkNode>` | `block/entity/SequenceExecutionChamberBlockEntity.java:103` |
| `IntermediateCacheBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<IntermediateCacheNetworkNode>` | `block/entity/IntermediateCacheBlockEntity.java:56-57` |
| 构造时 `new ...NetworkNode()` 并 `setBlockEntity(this)` | `IntermediateCacheBlockEntity.java:76-78`；`SequenceExecutionChamberBlockEntity.java:1407-1408` |
| **取网络**：`getNode().getNetworkOrNull()` | `IntermediateCacheBlockEntity.java:128`；`SequenceExecutionChamberBlockEntity.java:1754`（`getNode().getNetworkOrNull()`） |
| 网络节点容器能力注册 | `IntermediateCacheBlockEntity.java:236-242`；`SequenceExecutionChamberBlockEntity.java:15957-15962`；统一入口 `RS_Create_Compat.java:533`、`:535` |

```java
// 注册方式（逐字，来自 IntermediateCacheBlockEntity.java:236-242）
public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
        RS_Create_Compat.INTERMEDIATE_CACHE_BLOCK_ENTITY.get(),
        (blockEntity, direction) -> blockEntity.getContainerProvider()
    );
}
```

**网络读写**：拿到 `Network` 后取 `StorageNetworkComponent`（它继承 `RootStorage`）：

```java
// 取存储
final Network network = getNode().getNetworkOrNull();
final StorageNetworkComponent storage =
    network == null ? null : network.getComponent(StorageNetworkComponent.class);
// 证据：SequenceExecutionChamberBlockEntity.java:10327-10333（currentStorage()）
//       AutocrafterStorageController.java:126-129
```

`javap` 证明它同时是「组件」和「根存储」：

```
public interface com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent
    extends ...NetworkComponent, ...storage.root.RootStorage

public interface ...storage.root.RootStorage extends ...storage.Storage, ...storage.tracked.TrackedStorage {
  public abstract long get(ResourceKey);
  public abstract boolean contains(ResourceKey);
  public abstract void sortSources();
  public abstract void addSource(Storage);
  public abstract void removeSource(Storage);
  ...
}
public interface ...storage.Storage {
  long insert(ResourceKey, long, Action, Actor);
  long extract(ResourceKey, long, Action, Actor);
  Collection<ResourceAmount> getAll();
  long getStored();
}
```

**已有用法（逐字可复用）**：

```java
// 写回网络（AutocrafterStorageController.java:160-161 / :187-188 / :198-199）
final long accepted = storage.insert(ItemResource.ofItemStack(stack), stack.getCount(), Action.SIMULATE, Actor.EMPTY);
final long accepted = storage.insert(ItemResource.ofItemStack(stack), stack.getCount(), Action.EXECUTE,  Actor.EMPTY);
final long accepted = storage.insert(new FluidResource(fluid.getFluid(), fluid.getComponentsPatch()),
                                     fluid.getAmount(), Action.EXECUTE, Actor.EMPTY);
```

**枚举同网络内的自家方块**（§④ 名字注册表的实现基础）：

```java
// RsccSharedCache.resolve（support/RsccSharedCache.java:139-149）
for (final NetworkNodeContainer container
    : network.getComponent(GraphNetworkComponent.class).getContainers()) {
    if (container.getNode() instanceof IntermediateCacheNetworkNode node) {
        final IntermediateCacheBlockEntity warehouse = node.getBlockEntity();
        ...
    }
}
```

`javap` 证实 `GraphNetworkComponent` 提供了两把刷子：

```
public interface ...GraphNetworkComponent extends ...NetworkComponent {
  public abstract Set<NetworkNodeContainer> getContainers();
  public abstract <T> Set<T> getContainers(Class<T>);          // ← 可直接按类型筛
  public abstract NetworkNodeContainer getContainer(Object);
}
```

⇒ **§④ 的「同网络唯一」我强烈建议就走这条**（与 `RsccSharedCache` 同一套枚举），
不引入新的 `SavedData` 账本。

### 3.2 「输出到朝向的容器」——我们的既有做法

现成范式有两处，**都直接可用**：

**(a) 面配置驱动（执行仓）**——`itemHandlersFor(FaceMode)` / `fluidHandlersFor(FaceMode)`：

```java
// SequenceExecutionChamberBlockEntity.java:15010-15032
private List<IItemHandler> itemHandlersFor(final FaceMode mode) {
    ...
    for (final Direction direction : Direction.values()) {
        if (getFaceMode(direction) != mode) continue;
        final BlockPos pos = worldPosition.relative(direction);
        final BlockEntity neighbor = level.getBlockEntity(pos);
        if (neighbor == null) continue;
        final IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
            pos, neighbor.getBlockState(), neighbor, direction.getOpposite());
        if (handler != null) handlers.add(handler);
    }
    ...
}
// 流体对称：:15054-15076，Capabilities.FluidHandler.BLOCK
```

**(b) 单点相邻（导入策略）**——`RsccChamberImportStrategy` 里的静态工具：

```java
public static IItemHandler itemHandlerAt(final Level level, final BlockPos pos)   // :1577
public static IFluidHandler fluidHandlerAt(final Level level, final BlockPos pos) // :1608
```

⇒ **A 的「朝向输出」直接调用 (b) 的两个静态方法即可**，不需要新写一套 capability 查询。
`getBlockEntity(pos)` 而非 `level.getBlockState(pos)` 也保证了「未加载区块不误判」。

**对比：RS 输出总线（Exporter）怎么做**

RS 的 Exporter 走的是**网络节点 + 传输策略注册表**：

- `ExporterNetworkNode`（`api/network/impl/node/exporter/ExporterNetworkNode.class`）
- `ExporterTransferStrategyFactory` 注册表：`RefinedStorageApi.getExporterTransferStrategyRegistry()`
  （`javap` 输出可见 `getExporterTransferStrategyRegistry()`）
- NeoForge 侧的具体策略：`neoforge/exporter/ItemHandlerExporterTransferStrategyFactory.class`、
  `neoforge/exporter/FluidHandlerExporterTransferStrategyFactory.class`
- 传输量上限由 `ExporterTransferQuotaProvider` 决定（见 §⑤.2，**物品原版会 ×64**）

**我们的结论**：**不学 Exporter 的那套注册表 / 策略链**（对我们过重，且把
资源类型绑死在注册表上），**改用 A 自己的 tick 循环 + `Capabilities.*` 直接查** ——
这正是本工程既有方块（执行仓 / 缓存仓 / 收集缓存）一贯的做法。

### 3.3 缓存：既有实现能直接复用哪些

| 既有类 | 用途 | 能否复用 | 证据 |
| ------ | ---- | -------- | ---- |
| `RsccUnboundedItemStorage` | **种类无上限**的物品存储（`ItemStackHandler` 子类，按需开槽） | ✅ **推荐**。A 的物品缓存直接用它 | `support/RsccUnboundedItemStorage.java`；使用处 `AutocrafterStorageMixin.java:57-59`、`:100-102`；`STACK_LIMIT` 见 `RsccChamberItemStorage.java:59` 引用 |
| `RsccUnboundedFluidStorage` | **多流体合并成一罐、种类无上限**（`FluidTank` 子类） | ✅ **推荐**。A 的流体缓存直接用它 | `support/RsccUnboundedFluidStorage.java:41`（`extends FluidTank`）、`:42`（`List<FluidStack> tanks`）、`:55-66`（`getTotalAmount/getRemainingCapacity/getTanksSnapshot`） |
| `MultiFluidCache` | 多条「流体/气体」缓存，**总量有上限、种类无上限**；键 = 流体注册名 + NBT | ⚠️ **可用但不推荐作为主力**：键是 `(ResourceLocation, CompoundTag)` 而非 RS 的 `ResourceKey`，与网络侧的类型不一致，转换处容易出 bug | `support/MultiFluidCache.java` 顶部注释与 `record Entry(ResourceLocation id, CompoundTag nbt, long amount)` |
| `RsccIntermediateOnlyStorage` | **只收中间产物**的存储装饰器（按 `ResourceId` 白名单，返回 0 = 换下一个源） | ❌ 复用**思路**：它的「返回 0 即拒收」是 RS 官方约定，见 `support/RsccIntermediateOnlyStorage.java:22-25`、`:52-63` |
| `RsccCacheExposedStorage` | 把一块复合存储**注册成网络源**，并实现 `CompositeAwareChild` + `PriorityProvider` | ✅ **强烈建议复用同构模式**（A 若要做「网络往里写」的网络源，这就是模板） | `support/RsccCacheExposedStorage.java:69`（`implements CompositeAwareChild, PriorityProvider`）、`:74`/`:80`（插入 +1000 / 抽出 −1000）、`:126-176`（四个 `CompositeAwareChild` 方法）、`:188-196`（优先级） |
| `RsccChamberItemStorage` | 「内部存储 + 网络共享缓存池」的统一 `IItemHandler` 视图 | ❌ **不要复用**：它绑在执行仓的语义上（`internal` + `PoolSource`） | `support/RsccChamberItemStorage.java:82-86`；其 `getSlots()` 的 2026-10-05 重大修正说明见 `:144-173` |
| `RsccSharedCache` | 把网络内**所有**缓存仓的磁盘合并成一个池子 | ❌ 不复用（A 的缓存是**方块自带**，不是磁盘池） | `support/RsccSharedCache.java:74`、`:123-136` |

**⚠️ 一个必须提前避开的坑（`RsccCacheExposedStorage` 的教训）**：
存储节点**必须实现 `CompositeAwareChild`**，否则 RS 的根复合把它的存储当**叶子**，
递归不进去 ⇒ 「源注册了却不生效」。原文证据：

> RS 的根存储（`RootStorageImpl` → `CompositeStorageImpl`）在 `addSource`/`insert` 时
> **只递归进实现了 `CompositeAwareChild` 的子节点**
> —— `support/RsccCacheExposedStorage.java:24-26`

并且 `compositeInsert/compositeExtract` **不能**转发给 `CompositeStorageImpl` 的占位实现
（那里**故意抛 `UnsupportedOperationException`**，实测崩过：

```
RsccCacheExposedStorage.compositeExtract(:108)
  → CompositeStorageImpl.compositeExtract(CompositeStorageImpl.java:191)
  → java.lang.UnsupportedOperationException
```
—— 同文件 `:140-150`）。**自己算 `Amount(实际量, 实际量)` 返回**，见 `:161-176`。

### 3.4 「与自动合成仓同一套逻辑与同一指令开关」

**指令全清单**（逐条列出，来源 `command/CompatCommands.java`）：

| 指令 | 作用 | 代码位置 | 权限 | 作用域 |
| ---- | ---- | -------- | ---- | ------ |
| `/rs_create_compat autocrafter storage on` | 开启执行者周围 32 格内全部自动合成仓的**内部存储** | `:101-104` → `setStorage(ctx, true)` `:420-438` | `requires(source -> true)`（`:100`） | 世界内、**半径 32**（`:41`） |
| `/rs_create_compat autocrafter storage off` | 关闭：**先把仓内物品/流体写回网络**，装不下则整组取消 | `:105-106` → `:439-448` | 同上 | 同上 |
| `/rs_create_compat autocrafter storage` | 只打用法提示 | `:108-111` | 同上 | — |
| `/rs_create_compat blockcontent network\|drop\|block` | 拆方块时内容物去向（三档） | `:113-127` → `setBlockContent` `:407-417` | 无权限 | **全局存档级**（`SavedData`，`support/BlockContentPolicy.java:19-35`） |
| `/rs_create_compat supply materials\|target` | 序列执行仓原料供应策略 | `:131-143` → `:293-303` | 无权限 | 全局存档级（`RsccSupplyPolicy`） |
| `/rs_create_compat shortagemode suspend\|wait` | 缺料处置策略 | `:147-159` → `:313-334` | 无权限 | 全局存档级（`RsccShortagePolicy`） |
| `/rs_create_compat refill on\|off\|machines` | 补合成请求量档位 | `:164-181` → `:355-379` | 无权限 | 全局存档级（`RsccRefillPolicy`） |
| `/rs_create_compat reuse on\|off` | 优先复用网络中的中间产物 | `:186-198` → `:388-398` | 无权限 | 全局存档级（`RsccIntermediateReusePolicy`） |
| `/rs_create_compat devlogs on\|off` | 开发日志总开关（推荐写法） | `:242-247` → `:277-283` | 无权限 | 运行时（`RsccAssemblyDebug`） |
| `/rs_create_compat assemblydebug on\|off` | 同上（兼容别名） | `:249-255` | 无权限 | 同上 |
| `/rs_create_compat debug assembly on\|off` | 同上（兼容别名） | `:257-263` | 无权限 | 同上 |
| `/rs_create_compat diag` | 一键诊断快照 → `run/rscc_diag/<时间戳>/snapshot.json` | `:210-213` → `:222-237` | 无权限 | 只读 |

**⇒ 「自动合成仓的开关」= 上表第 1、2 条**（`/rs_create_compat autocrafter storage on|off`）。

**A 要复用哪一个**：见 §1.3 的 S1/S2/S3。**推荐 S2**：新增
`/rs_create_compat receiver storage on|off`，**共用 `AutocrafterStorageController` 同构的守恒判定**
（`Simulate → Execute → 复查为空 → 才置关`，`support/AutocrafterStorageController.java:120-149`）。

### 3.5 气体：现状 = **完全没有实现**（详见 §⑥）

### 3.6 物品 / 流体 / 气体的统一建模：**RS 2.0.9 的 `PlatformResourceKey` 可以统一承载前两者，第三者需要自己注册**

| 事实 | 证据 |
| ---- | ---- |
| `ResourceKey` 是**空接口**（纯标记） | `javap`：`public interface ...api.resource.ResourceKey { }` |
| `ItemResource` / `FluidResource` 都实现 `PlatformResourceKey` | `javap`：`ItemResource implements PlatformResourceKey, FuzzyModeNormalizer`；`FluidResource implements PlatformResourceKey, FuzzyModeNormalizer` |
| `PlatformResourceKey extends ResourceKey` | `javap`：`public interface ...PlatformResourceKey extends ...ResourceKey { long getInterfaceExportLimit(); long getProcessingPatternLimit(); List<ResourceTag> getTags(); ResourceType getResourceType(); }` |
| **统一序列化编解码器存在** | `ResourceCodecs.STREAM_CODEC : StreamCodec<RegistryFriendlyByteBuf, PlatformResourceKey>`；`ResourceCodecs.CODEC : Codec<PlatformResourceKey>`（`javap`） |
| **本工程已经在用它**（这是「我们之前已经用过」的证据） | `network/SyncAssemblyAlertsPacket.java:5`（import）、`:156`（`ResourceCodecs.STREAM_CODEC.encode(buf, alert.product())`）、`:180-181`（decode）；`storage/UniversalStorageType.java:19`（import）、`:33`（`private static final Codec<PlatformResourceKey> RESOURCE_CODEC = ResourceCodecs.CODEC;`）、`:104`（转型使用） |
| **新资源类型如何接入** | `RefinedStorageApi.getResourceTypeRegistry()` 返回 `PlatformRegistry<ResourceType>`（`javap`）；`ResourceType` 要求 `getMapCodec()` / `getStreamCodec()` / `getTitle()` / `getSprite()` / `normalizeAmount(double)` / `getDisplayAmount(long)` / `getInterfaceExportLimit()` / `createGridOperations(...)` |
| **`ResourceAmount` 的量是 `long`** | `javap`：`public record ResourceAmount(ResourceKey resource, long amount)`，`public static void validate(ResourceKey, long)` |

**结论**：

1. **物品 + 流体**：`ItemResource` / `FluidResource` 都是 `PlatformResourceKey`，
   用 `ResourceCodecs.STREAM_CODEC` 一把梭，**这正是需求里的「统一建模」**，
   已在本工程的生产代码里跑通。
2. **气体**：RS **2.0.9 里没有任何 chemical / gas / mekanism 相关类**
   （在 jar 里按 `chemical` / `mekanism` / `gas` 名称子串搜索 **零命中**，见 §⑥.1）。
   ⇒ 若要做气体，**必须自己实现一个 `PlatformResourceKey` + `ResourceType` 并在
   `getResourceTypeRegistry()` 里注册**；代价见 §⑥.3。
3. **注意 `getInterfaceExportLimit()` / `getProcessingPatternLimit()` 这两个名字**：
   它们暗示 RS 对**不同资源类型**有各自的「接口导出上限」，与需求第 13 条「无数量上限」**可能冲突**。
   **这两个方法的实际数值语义未证实**（列入 §⑧）。

---

## ④ 命名与唯一性设计

### 4.1 网络的稳定标识是什么（拿什么当 key）

**RS 的 `Network` 对象本身就是最合适的 key**——它是**网络拓扑的权威实例**，
且本工程已经有「按 `Network` 做缓存 key」的成熟用法：

```java
// RsccSharedCache.java:87-88
/** 世界 → （网络 → 解析结果）。两级都用弱键，避免世界 / 网络卸载后泄漏。 */
private static final Map<Level, Map<Network, Entry>> CACHE = new WeakHashMap<>();
```

**跨维度 / 子网络如何隔离**：由 RS 的网络图本身保证，不需要我们额外写判定。原文证据
（`support/RsccSharedCache.java:36-49`）：

> 走 RS 自己的网络图：`network.getComponent(GraphNetworkComponent.class).getContainers()`
> 枚举该网络当前**全部**网络节点容器……
> 这份枚举天然满足全部既有连接语义，且**绝不重复实现连接判定**：
> - RS 的网络图连边本身就经过「机械动力扳手断开线缆」与「分隔框架阻断」这两道闸门
>   （`mixin/network/InWorldNetworkNodeContainerImplMixin` 接管 `canAcceptIncomingConnection`）；
> - 缓存仓要真的「插进线缆」才会出现在网络图里。

⇒ **同一个 RS 网络 = 同一个 `Network` 对象 = 同一个名字作用域**；
**不同维度 / 不同子网络 = 不同的 `Network` 对象**（取不到对方的容器）⇒ 天然不冲突，
**逐字满足用户「跨网络不冲突」的要求**。

### 4.2 三个候选做法与代价

| 方案 | 做法 | 代价 | 我的评价 |
| ---- | ---- | ---- | -------- |
| **N1 枚举网络图比对**（推荐） | 每次需要判重时，用 `GraphNetworkComponent#getContainers()` 枚举同网络内所有 A 节点，收集非空名字成集合，做集合成员判断 | 每次判重 O(网络节点数)。但**判重只发生在「界面提交改名」这一个时刻**（不是每 tick），且可加一个 20 tick 的短路缓存 | ✅ **推荐**。与 `RsccSharedCache` 同源，**不新增持久化结构**，不会出现「账本与实际不一致」 |
| **N2 `SavedData` 按网络记账** | 主世界 `DimensionDataStorage` 里存一张 `Map<网络标识, Set<String>>` | 需要一个**稳定的网络标识**——`Network` 是运行时对象，重启/合并/分裂后不保证同一性。用「网络内所有方块坐标的哈希」当标识则**拓扑一变就失效**；用维度+中心坐标则**网络移动就失效** | ❌ **不推荐**。引入了一个**可能说谎的账本**，而它撒谎的后果是「玩家明明没重名却被拒绝」 |
| **N3 只在客户端界面判重** | 界面维护一份名字列表 | **必须**有服务端最终判定，否则改存档 / 用指令 / 别的方式就能绕过 | ❌ 单用不行，但**应作为 N1 的补充**（见 4.4） |

### 4.3 本工程已有的同型实现（**直接照搬语义**）

执行仓已经做过**完全一样**的事，**这套语义已经过用户验收**：

```java
// SequenceExecutionChamberBlockEntity.java:13695-13700
public boolean isNameTakenInNetwork(final String name) {
    if (name == null || name.isEmpty()) {
        return false;                       // ← 空名不冲突
    }
    return occupiedChamberNamesInNetwork().contains(name);
}

// :13711-13724
public Set<String> occupiedChamberNamesInNetwork() {
    final SequenceExecutionChamberBlockEntity myHead = chainHead();
    final Set<String> names = new LinkedHashSet<>();
    for (final SequenceExecutionChamberBlockEntity chamber : chambersInNetwork()) {
        if (chamber.chainHead() == myHead) continue;   // 自己不算冲突
        final String name = chamber.getChamberName();
        if (name != null && !name.isEmpty()) names.add(name);
    }
    return names;
}

// :13739-13741
private List<SequenceExecutionChamberBlockEntity> chambersInNetwork() {
    return chambersOf(getNode().getNetworkOrNull());
}
```

并且**「未命名 = 不可用」这条语义也已经实现过**：

```java
// SequenceExecutionChamberBlockEntity.java:13682-13686
public boolean isProperlyConfigured() {
    final String name = getChamberName();
    final String type = getRecipeType();
    return name != null && !name.isEmpty() && type != null && !type.isEmpty();
}
```

**⇒ A 的设计：把上面这套「`isNameTakenInNetwork` + `occupiedNamesInNetwork` + `isProperlyConfigured`」
按同一口径复制到 A 上**（A 没有「链」的概念，所以去掉 `chainHead()` 那一层）。
**判据必须与服务端最终拒绝同源**——即 `isNameTakenInNetwork` 的集合成员判断**就是**服务端拒绝的依据，
不在两处写两套规则。

### 4.4 名字 ↔ 目标地址 的解析路径（含冲突 / 拆除 / 改名 / 未命名）

**核心决议：请求器里填的「目标」就是对方的名字，但运行时不做字符串广播，而是每 tick（或每脉冲）现解析。**

```
B 的配置：targetName (String) + resource (PlatformResourceKey) + amount (long)
        ↓ 红石上升沿（模仿 Create：RedstoneRequesterBlock.java:150-157）
B 触发一轮投递
        ↓
① 解析：在 B 所在网络内查找「名字 == targetName」的 A
        ├─ 0 个        → 失败。日志/提示「目标未找到」。B 的缓冲不清空
        ├─ 1 个        → 命中
        └─ ≥2 个       → 失败并**明确报警**（正常情况下不可能，见下）
        ↓
② 从 B 所在网络的 StorageNetworkComponent 提取指定资源（SIMULATE → EXECUTE）
        ↓
③ 写入 A 的缓存（SIMULATE 校验能否全收；不能全收则只搬能收下的部分，其余留在网络）
        ↓
④ A 每 tick（受速度升级节流）把自己的缓存 drain 到「朝向面」的容器
```

**逐种异常的处理（与用户原话一一对应）**：

| 情形 | 处理 | 理由 |
| ---- | ---- | ---- |
| **名字未设置**（空名） | A **不输出**、**不可被解析为投递目标**。判定沿用 `isProperlyConfigured` 的口径：`name == null \|\| name.isEmpty()` ⇒ 不工作 | 用户原话第 9 条「必须要设定才可以用，否则就无法使用」。**注意语义**：A 不工作 = 缓存里的东西**留在缓存里不丢**（绝不因为没名字就把东西吐出来 / 销毁） |
| **重名冲突（提交改名时）** | **服务端拒绝**：`isNameTakenInNetwork(newName) == true` ⇒ 不改，回失败提示 | 用户原话第 6/7 条 |
| **重名冲突（已经发生了，例如拆存档 / 覆写 / 两网络合并）** | **双方都继续工作，但解析到目标时报「重名」并拒绝投递**（不随机挑一个） | 「随机挑一个」是**静默的错误投递**，比不投递坏得多；报错让玩家能修。网络合并是 RS 的常规操作（`Network.merge`，见 `javap`），**必须考虑** |
| **目标方块被拆** | 下一轮解析立刻找不到 ⇒ 投递失败，**B 的缓冲保留、不丢**。A 拆掉时其缓存按全局「内容物去向」策略结算（复用 `BlockContentPolicy`，`support/BlockContentPolicy.java:50-58`；执行仓/Autocrafter 的既有做法见 `mixin/AutocrafterStorageMixin.java:118-147`），**绝不销毁** | 守恒 |
| **目标改名** | 下一轮解析用的就是新名字 ⇒ **自动跟随**，无需任何通知机制 | 这正是「现解析」相对「注册表快照」的最大好处：**没有需要同步的缓存副本** |
| **目标所在区块未加载** | 枚举不到（`GraphNetworkComponent` 只含已加载的节点）⇒ 视为「未找到」，失败并保留缓冲 | 与 `RsccSharedCache` 的「晚一点看见」同一哲学（`support/RsccSharedCache.java:65-67`） |
| **目标没接入网络** | 同上：不在网络图里 ⇒ 找不到 | 用户原话「子网络 / 别的网络」不干扰，反之亦然 |

### 4.5 界面判重（N1 的补充，沿用既有做法）

执行仓的做法是把占用集合**随包下发**给客户端，客户端做**同一份判断**：

> 客户端没有网络对象、也读不到别人的方块实体。因此把这份集合随
> `SyncChamberBindingPacket`（打开界面时下发一次）同步给客户端，界面直接做集合成员判断
> —— 与这里的服务端判据**逐字同源**
> （`support/...SequenceExecutionChamberBlockEntity.java:13702-13710`）

⇒ **A 照做**：打开界面时下发一次「本网络已占用名字集合」，客户端实时禁用「确定」按钮，
服务端仍然**最终再判一次**。

---

## ⑤ 升级槽设计

### 5.1 工程既有方块怎么做升级槽（**优先复用**）

**标准做法 = `ItemStackHandler(6)` + `UpgradeSlot` 白名单 + 方块实体侧二次校验**：

| 关键点 | 证据 |
| ------ | ---- |
| 6 槽容器（三种既有写法都在用） | **(a) `ItemStackHandler(6)`**：`RangeChargerBlockEntity.java:93`（`new ItemStackHandler(6) { ... }`）、`:136-138`（`getUpgradeContainer()`）；`SchematicLoaderBlockEntity.java:84`、`:212`、`:364-366`<br>**(b) `SimpleContainer(6)`**：`CollectionCacheBlockEntity.java:103`（`UPGRADE_SLOTS = 6`）、`:188`（`new SimpleContainer(UPGRADE_SLOTS)`）、`:329-331`（`getUpgradeContainer()`）<br>**(c) RS 官方 `UpgradeContainer`**：见 §5.2（本工程尚未用它做过机器，但它自带 ticker） |
| 白名单判定（按 RS 的 `ResourceLocation` path） | `menu/UpgradeSlot.java:186-197`（`pathOf`：非 `refinedstorage` 命名空间 ⇒ null ⇒ 拒收）、`:114-125`（`mayPlace`）、`:104-112`（`getMaxStackSize() == 1`） |
| 单种上限 | `menu/UpgradeSlot.java:29`（`MAX_PER_UPGRADE = 6`）、`:90-92`（`maxForPath`：自动合成 / 创造范围各 1，其余 6） |
| 允许清单按机器定制 | `UpgradeSlot` 构造器接受 `List<String> allowedUpgrades`（`:58-64`、`:66-77`） |
| 方块实体侧清退非法升级（**必须做**，界面拦不住存档直改 / 版本变更） | `SchematicLoaderBlockEntity.java:273-291`（`validateUpgradeSlots`：非法即 `dropUpgrade(i)`）、`:288-289`（超上限弹出）；`RangeChargerBlockEntity.java:304-347`；`CollectionCacheBlockEntity.java:2508-2556` |
| 升级物品 id 常量 | `SchematicLoaderBlockEntity.java:54-59`（`speed_upgrade` / `stack_upgrade` / `autocrafting_upgrade`）、`RangeChargerBlockEntity.java:39-46` |
| 注册成 RS 升级「目的地」（决定 tooltip 里显示哪些机器） | `RegisterUpgradeDestinations.java:28-54`（机器 → 允许升级表）、`:72-88`（`UpgradeRegistry.forDestination(匿名 UpgradeDestination)`） |

**关键发现（决定了 A 的实现路径）**：本工程**已经在用匿名 `UpgradeDestination`**
注册自定义目的地（`RegisterUpgradeDestinations.java:80-88`），
且 `javap` 证明注册表就是一张普通 `Map`：

```
public class ...upgrade.UpgradeRegistryImpl implements ...UpgradeRegistry {
  private final Map<UpgradeDestination, Set<UpgradeMapping>> byDestination;   // ConcurrentHashMap
  ...
  public Set<UpgradeMapping> getByDestination(UpgradeDestination) { ... }
}
public interface ...UpgradeRegistry$DestinationBuilder {
  DestinationBuilder add(Item, int maxAmount);
  default DestinationBuilder add(Item) { ... }
}
```

⇒ **A 可以直接 new 一个 6 槽 `UpgradeContainer` 并传自定义 `UpgradeDestination`**（§⑤.3），
RS 的 `canPlaceItem` 会自动只放行注册表里允许的升级：

```
// javap UpgradeContainer.canPlaceItem(int, ItemStack)
0: aload_0
1: invokevirtual getAllowedUpgrades:()Ljava/util/Set;      // ← 来自 getByDestination(destination)
...
33: aload_3  (matched UpgradeMapping)
34: ifnonnull 39
37: iconst_0  → 没匹配 → **拒收**
39: ... countItem(item) >= mapping.maxAmount() → 拒收
```

**注意**：`UpgradeContainer` 是**方块实体级的容器**，
而本工程**多数机器用的是自己 new 的 `ItemStackHandler(6)` + `UpgradeSlot`**（见上表）。
A **建议两种都上**：
- 用 `UpgradeContainer(6, destination, listener, defaultWorkTickRate)` 得到**官方的 tick 节流器**（§⑤.2），
- 同时用 `UpgradeSlot(handler, i, x, y, List.of("speed_upgrade","stack_upgrade"))` 保证**界面层拒收白名单外的一切**，
- 方块实体层再加一次 `validateUpgradeSlots` 兜底（照抄 `SchematicLoaderBlockEntity.java:274-291`）。

### 5.2 `速度升级` / `叠加升级` 在 RS 2.0.9 里**到底做什么**（javap 实证）

**(1) 两个物品从哪来**（我们代码里用的是「按 id 取物品」的写法，
`Menu/UpgradeSlot.java:204-213`；RS 内部的工厂方法入口）：

```
// javap com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem
public static com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem speedUpgrade();
public static com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem stackUpgrade();
public static com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem silkTouchUpgrade();
public static com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem autocraftingUpgrade();
```

**(2) 速度升级的作用 = 缩短 `doWork()` 的调用周期**

```java
// javap -p UpgradeContainer（节选，含 updateIndex 里的关键三行）
22: invokevirtual ...content.Items.getSpeedUpgrade:()...AbstractUpgradeItem;
25: invokevirtual ...UpgradeContainer.getAmount:(...UpgradeItem;)I;   // ← speed 的数量
28: istore_1
29: aload_0
30: getfield  ticker:...UpgradeContainer$ThrottledNetworkNodeTicker;
33: aload_0
34: getfield  defaultWorkTickRate:I
37: iload_1
38: iconst_2
39: imul
40: isub
41: putfield  ...ThrottledNetworkNodeTicker.workTickRate:I
```

⇒ **`workTickRate = defaultWorkTickRate − 2 × speedCount`**。

ticker 的语义在内部类里一目了然：

```java
// javap -p -c 'UpgradeContainer$ThrottledNetworkNodeTicker'
public void tick(AbstractNetworkNode);
  Code:
     0: ... workTicks++;                 // 每被调用一次 +1
    11: getfield workTickRate:I
    15: irem                            // workTicks % workTickRate
    16: ifne 23
    19: aload_1
    20: invokevirtual AbstractNetworkNode.doWork:()V
    23: return
```

而默认周期常量与各构造器的默认值（`javap UpgradeContainer` 各构造器的 `bipush 9`）：

```
public UpgradeContainer(UpgradeDestination, int);              → (arg1, dest, null, 9)
public UpgradeContainer(UpgradeDestination, Listener);         → (arg1, arg2, 9)
public UpgradeContainer(int, UpgradeDestination, Listener);    → (arg1, arg2, arg3, 9)
```

⇒ **`DEFAULT_WORK_TICK_RATE = 9`**（本工程注释里也写到了这一点：
`SequenceExecutionChamberBlockEntity.java:1065` ——
「必须 ≥ 输入总线默认搬运节拍（RS `UpgradeContainer.DEFAULT_WORK_TICK_RATE = 9`）」）。

**算例**（`defaultWorkTickRate = 9`）：

| 速度升级数 | `workTickRate` | 效果 |
| ---------- | -------------- | ---- |
| 0 | 9 | 每 9 tick 工作一次（基线） |
| 1 | 7 | 快 9/7 ≈ 1.29× |
| 2 | 5 | 1.8× |
| 3 | 3 | 3× |
| 4 | 1 | 每 tick（9×） |
| 5 | −1 | ⚠️ 见下 |
| 6 | −3 | ⚠️ 见下 |

**⚠️ 未证实 / 风险**：`workTickRate` 变成 `≤ 0` 时 `workTicks % workTickRate` 会
`ArithmeticException: / by zero`（Java 的 `%` 对 0 抛异常）。**RS 有没有在别处夹住这个值，我没有证据**。
因此 **A 自己必须按本工程惯例夹住**：现有机器一律
`Math.min(countUpgrades(SPEED_UPGRADE), 6)`（`QuantityKeeperBlockEntity.java:575`、
`SchematicLoaderBlockEntity.java:437`、
`RangeChargerBlockEntity.java:325` 的 `kind == 3 ? 1 : UpgradeSlot.MAX_PER_UPGRADE`）后再算节拍，
**A 应当自己算节拍而不是完全依赖 RS 的 ticker**，
或至少把传入的 `defaultWorkTickRate` 限制在能保证 `workTickRate ≥ 1` 的范围。
**这条列入 §⑧ 需要实测确认。**

**(3) 叠加升级的作用 = 把单次转移量 ×64**（**是「有没有」的布尔量，不是「有几个」**）

```java
// javap -p -c com.refinedmods.refinedstorage.common.exporter.ExporterTransferQuotaProvider
public ExporterTransferQuotaProvider(long baseQuota, UpgradeState upgrades, ToLongFunction<ResourceKey>, boolean);
  Code:
     4: aload_0
     5: aload_3
     6: getstatic  ...content.Items.INSTANCE:...Items;
     9: invokevirtual ...Items.getStackUpgrade:()...AbstractUpgradeItem;
    12: invokeinterface UpgradeState.has:(UpgradeItem)Z     // ← 只看「有没有」
    17: ifeq 28
    20: lload_1
    21: ldc2_w #23  // long 64l
    24: lmul                                               // ← ×64
    25: goto 29
    28: lload_1
    29: putfield baseTransferQuota:J
```

**本工程已有的同义实现**（**口径已对齐，直接照抄**）：

```java
// (1) 叠加升级 = 每级 ×64（基础 64 × (1 + 叠加数)）
// block/entity/SchematicLoaderBlockEntity.java:60-61, 447-449
/** 无堆叠升级时每格上限（= RS 常规堆叠），是 {@link #storageSlotCapacity(int)} 的基础值。 */
public static final int BASE_SLOT_CAPACITY = 64;
public static int storageSlotCapacity(final int stackUpgradeCount) {
    return BASE_SLOT_CAPACITY * (1 + Math.min(Math.max(0, stackUpgradeCount),
        cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE));   // MAX_PER_UPGRADE = 6
}
// :442-449 的注释明确写了「唯一实现」与与 QuantityKeeper 同口径

// (2) 速度升级 = 线性提频（本工程不直接算「+1 每次」，而是「1 + 速度数」的倍率）
// block/entity/CollectionCacheBlockEntity.java:447
final int speedFactor = 1 + countUpgrades(SPEED_UPGRADE);
// block/entity/CollectionCacheBlockEntity.java:2479-2482（每格容量随叠加升级增长）
public int getCacheSlotCapacity() {
    return Math.max(1, Config.collectionCacheSlotCapacity
        + countUpgrades(STACK_UPGRADE) * Config.collectionCacheSlotCapacityPerStackUpgrade);
}
```

⇒ **本工程对叠加升级的既有解释是「每级 ×64」，而 RS 原版是「有就 ×64」。两者不一致。**
**A 必须挑一个并写进 tooltip**。**我的建议：跟本工程既有口径（每级 ×64）走**，
因为 A 的「无数量上限」需要更强的放大能力，且与本工程其它机器一致（玩家不用学两套）。
**这条列入 §⑧ 需要你确认。**

### 5.3 「6 槽且只收这两类」的实现要点

```java
// 伪代码（不是要写的代码，是设计）
// 1) 6 槽容器：官方 UpgradeContainer，自带 ticker 与 energyUsage 汇总
private final UpgradeContainer upgrades = new UpgradeContainer(
        6,                              // 槽数 = 6（用户要求）
        RECEIVER_DESTINATION,           // 自定义 UpgradeDestination
        this::onUpgradesChanged,        // listener：变化时 setChanged() + 通知
        9);                             // defaultWorkTickRate = 9（RS 默认）

// 2) 注册目的地：只登记两类 ⇒ RS 的 canPlaceItem 自动拒收其余一切
RefinedStorageApi.INSTANCE.getUpgradeRegistry()
    .forDestination(RECEIVER_DESTINATION)
        .add(speedUpgradeItem, 6)       // 上限 6（= 槽数）
        .add(stackUpgradeItem, 6);

// 3) 界面层：白名单只有这两个 path（复用 menu/UpgradeSlot.java 的既有构造器）
new UpgradeSlot(handler, i, x, y, List.of("speed_upgrade", "stack_upgrade"))

// 4) 方块实体层兜底（界面拦不住存档直改）：
//    照抄 SchematicLoaderBlockEntity.java:274-291 的 validateUpgradeSlots
```

**白名单判据的确切写法**（复用既有 `pathOf` 的口径，`menu/UpgradeSlot.java:186-197`）：

```java
final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
if (id == null || !"refinedstorage".equals(id.getNamespace())) return null;   // 非 RS 物品 ⇒ 拒收
return id.getPath();                                                          // 必须 ∈ {"speed_upgrade","stack_upgrade"}
```

**A 与 B 的升级配置建议**（与 §1.4 一致）：

| 方块 | 槽数 | 白名单 | 速度升级作用 | 叠加升级作用 |
| ---- | ---- | ------ | ------------ | ------------ |
| **A 网络资源接收器** | **6**（用户明确） | 只 `speed_upgrade` + `stack_upgrade` | 缩短 A 的**输出节拍**（drain 到朝向容器的周期） | 放大 A **单次 drain 的量** |
| **B 请求器**（建议，待确认） | 6 | 同上 | 缩短 B 的**投递节拍**（一批 → 网络 → A 缓存的周期） | 放大 B **单批投递量** |

---

## ⑥ 气体现状（证实 / 接入路径 / 风险 / 是否本版就做）

### 6.1 现状：**完全没有气体支持**（证实的四条独立证据）

**证据 1：RS 2.0.9 里不存在任何 chemical / gas / mekanism 类。**
在 jar 内按名称子串搜索（Python `zipfile` 直读，未解压到工作区）：

```python
hits = [n for n in z.namelist() if any(k in n.lower() for k in ('chemical','mekanism','gas'))]
print('HITS:', hits)     # → HITS: []
```

**证据 2：RS 2.0.9 的资源类型注册表里只有物品与流体两个内置实现。**
`javap` 可见 `RefinedStorageApi` 暴露的是
`getItemResourceFactory()` / `getFluidResourceFactory()` / `getItemStorageType()` / `getFluidStorageType()` /
`getAlternativeResourceFactories()` / `addResourceFactory(ResourceFactory)`，
**没有任何 chemical / gas 专用入口**。

**证据 3：本工程从来没有引用过 Mekanism 的任何类型。**
全仓搜索 `Mekanism|ChemicalStack|chemical|isMekanismLoaded|gas` 只有 **12 处命中**，
其中**唯一**与 Mekanism 有关的是：

```java
// support/OptionalDeps.java:60-61
public static final String MOD_MEKANISM = "mekanism";
// :104-107
/** Mekanism 是否已加载（气体资源的可用性判断）。 */
public static boolean isMekanismLoaded() {
    return ModList.get() != null && ModList.get().isLoaded(MOD_MEKANISM);
}
```

—— 而 **`isMekanismLoaded()` 在全工程没有任何调用点**（grep 命中只在定义处）。
其余命中全部是**注释里提到 Mekanism 的交互思路**（如
`client/screen/ChamberFaceConfigScreen.java:22-23`「参考通用机械 Mekanism 的机器面配置交互思路」）
或**把气体当作流体处理的注释**（`client/widget/GhostMarkerRenderer.java:38`
「含气体，Mekanism 化学品同样以流体标识承载」；`support/KeeperSlotConfig.java:28-29` 的 `FORM_GAS = 2`）。

**证据 4：`build.gradle` 里没有 Mekanism 依赖。** 依赖段
（`build.gradle` dependencies 块）依次为 Create / Ponder / Flywheel / Registrate / RS /
Quartz Arsenal / JEI / Curios / apiguardian / Jade / (注释掉的 FTB Ultimine)，
**没有任何 `mekanism`**。

**⇒ 结论（我印象里是「预留未实现」——证实了，而且比「预留」更弱）：**
**不是「预留了接口没实现」，而是「只有一个未使用的 `isMekanismLoaded()` 和几条注释」。**
`FORM_GAS` 只是一条**标记形态枚举值**，实际承载仍然是流体
（`KeeperSlotConfig.java:43` 把 `FORM_FLUID` 与 `FORM_GAS` 一起判为「流体类」）。

### 6.2 接入 Mekanism 化学系统的 API 路径（类名 + 能力）

若要做，需要**同时**改两侧：

**(a) 往目标容器写入气体 —— Mekanism 的能力（外送侧）**

```java
// 证据：local_src/external/Mekanism/src/main/java/mekanism/common/capabilities/Capabilities.java:59
public static final MultiTypeCapability<IChemicalHandler> CHEMICAL =
    new MultiTypeCapability<>(Mekanism.rl("chemical_handler"), IChemicalHandler.class);
```

⇒ 取邻居的能力：`level.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.block(), pos, state, be, dir)`。
（`IChemicalHandler` 的路径：`local_src/external/Mekanism/src/api/java/mekanism/api/chemical/IChemicalHandler.java`；
它声明了 `int getChemicalTanks()`、`ChemicalStack getChemicalInTank(int)`、
`long getChemicalTankCapacity(int)`、`ChemicalStack insertChemical(ChemicalStack, Action)`、
`ChemicalStack extractChemical(long, Action)` 等，**全部带 `Action` 的模拟/执行语义**，
与 NeoForge 的 `IFluidHandler` 思路一致。）

**(b) 让 RS 网络认识「气体」这种资源 —— 必须自己实现并注册**

```java
// 需要实现的接口（javap RS 2.0.9）
public interface ...common.api.support.resource.PlatformResourceKey extends ...api.resource.ResourceKey {
  long getInterfaceExportLimit();
  long getProcessingPatternLimit();
  List<ResourceTag> getTags();
  ResourceType getResourceType();
}
public interface ...common.api.support.resource.ResourceType {
  MapCodec<PlatformResourceKey> getMapCodec();
  StreamCodec<RegistryFriendlyByteBuf, PlatformResourceKey> getStreamCodec();
  MutableComponent getTitle();
  ResourceLocation getSprite();
  long normalizeAmount(double);
  double getDisplayAmount(long);
  long getInterfaceExportLimit();
  GridOperations createGridOperations(RootStorage, Actor);
}
// 注册入口（javap）
PlatformRegistry<ResourceType> getResourceTypeRegistry();
void addResourceFactory(ResourceFactory);
```

### 6.3 风险

| # | 风险 | 说明 |
| - | ---- | ---- |
| R1 | **编译期硬依赖** | Mekanism 是**可选**依赖。直接 `import mekanism.api.chemical.IChemicalHandler` 会在未安装时 `NoClassDefFoundError`。本工程对 FTB Ultimine 已有「**纯反射**」的成熟先例（`support/OptionalDeps.java:114-126` 的注释），气体侧必须照做 |
| R2 | **RS 侧资源类型要自己写全套** | `ResourceType` 有 8 个方法，其中 `createGridOperations(...)` 会**影响终端界面**（能不能在 RS 终端里看到 / 取出气体）。这是**远大于「A 和 B 支持气体」的工作量** |
| R3 | **`ResourceTag` / 配方系统 / JEI 联动** | `PlatformResourceKey.getTags()` 与 `RecipeModIngredientConverter` 都可能需要气体侧的对应实现，否则「气体能不能被自动合成 / 被样板识别」是未知数（**未证实**） |
| R4 | **`getInterfaceExportLimit()` 与「无数量上限」冲突** | 见 §3.6 第 3 点，**未证实**该值是否会被用于截断 |
| R5 | **存档兼容** | 新增 `ResourceType` 会进入磁盘存储的序列化路径。若气体键的 codec 写错，**旧存档读不出来是数据安全问题** |

### 6.4 我的建议：**本版不做气体，但把接口留出来**

理由：
1. **工作量不对称**：A/B 支持气体需要「RS 资源类型全套餐 + Mekanism 反射桥 + 存档兼容验证」，
   而 A/B 的核心价值（**替换 Create 包裹的点对点投递**）**物品 + 流体就能完整体现**。
2. **风险不对称**：R5（存档兼容）是**不可逆**的风险，R1（NoClassDefFoundError）是**会崩游戏**的风险。
3. **可增量**：把 A/B 的资源抽象**从一开始就写成 `PlatformResourceKey`**
   （已完成，见 §3.6），则后续加气体**只是「多一个实现 + 多一个注册」**，不需要重构 A/B。
   —— 这也是本工程在「统一建模」上已经做对的地方（`storage/UniversalStorageType.java:33`、
   `network/SyncAssemblyAlertsPacket.java:156`）。

**⇒ 建议：本版只做 物品 + 流体；在界面与文档里明确写「气体（Mekanism 化学品）暂不支持」，
并把「资源键类型」抽象保持在 `PlatformResourceKey` 这一层，为后续留门。**

---

## ⑦ A 与 B 的交互（数据流与失败处理）

### 7.1 一句话模型

> **A 是「带名字的落地点 + 缓冲」；B 是「脉冲触发的搬运工」。**
> **B 从自己所在网络取料，写进「名字解析到的那个 A」的缓存；A 再按自己的节拍把缓存推到朝向的容器。**
> **全程不产生任何实体，不经过 Create 的包裹系统。**

### 7.2 为什么 A **必须有**缓存（而不是「可以直接透传到朝向容器」）

1. **用户原话就有**（「也可以有缓存」）。
2. **没有缓存就没有「名字」的意义**：若 B 直接写朝向容器，那么「按名字找方块」就退化成
   「按名字找一个**代理**」，A 变成一个纯粹的转发器 —— 那不如让 B 自己有朝向，
   用户就不必多放一个方块。
3. **没有缓存就没有升级槽的落点**：用户要求 A 装 6 槽速度 + 叠加升级。
   **速度升级只能作用在「A 有自己节拍」的地方**；没有缓存，A 就没有自己的节拍，
   速度升级**无处生效**（§5.2 已证明速度升级 = 改 `doWork` 周期）。
   ⇒ **缓存是「A 装速度升级」这个需求的逻辑前提。**
4. **缓存是失败时「绝不丢物」的保险箱**（§7.5）。

### 7.3 完整数据流（含每一跳的失败处理）

```
[红石上升沿]
   RedstoneRequesterBlock#neighborChanged → B.onRedstonePowerChanged()
   边沿判定：powered 由 false→true 才触发一次（与 Create 逐字同构：
   Create RedstoneRequesterBlock.java:150-157 / RedstoneRequesterBlockEntity.java:77-88）
        │
        ├─ 失败点 F0：B 未配置（targetName 为空 / resource 为空 / amount <= 0）
        │   → 不动作，界面/提示说明「未配置」，lastRequestSucceeded = false
        │
        ▼
[① 解析目标]
   在 B 所在网络的 GraphNetworkComponent#getContainers() 里找 A（同 §4.4）
        │
        ├─ 失败点 F1：找到 0 个 ⇒ 「目标未找到」（可能：名字打错 / 对方被拆 / 对方未接入网络 / 区块未加载）
        ├─ 失败点 F2：找到 ≥2 个 ⇒ 「目标重名，拒绝投递」（不随机挑）
        └─ 失败点 F3：找到的 A 名字为空 ⇒ 不可能是解析结果（空名不入集合，§4.3）
        │
        ▼
[② 从网络取料]
   StorageNetworkComponent storage = B.getNode().getNetworkOrNull().getComponent(StorageNetworkComponent.class)
   long available = storage.get(resource);                 // RootStorage#get(ResourceKey) → long
   long want = min(amount, available, 剩余待投递量)
   long got = storage.extract(resource, want, Action.EXECUTE, ACTOR);   // Actor 用 B 自己的名字，便于审计
        │
        ├─ 失败点 F4：网络不可用（storage == null）⇒ 不动作
        ├─ 失败点 F5：网络里没有这种资源 ⇒ got == 0 ⇒ 本轮结束（不是错误，是「缺料」）
        └─ 失败点 F6：**先 SIMULATE 再 EXECUTE**（本工程铁律，见 AutocrafterStorageController.java:32-33 的
                      「先写网络、再按写入量扣内部存储，绝不先扣后写」的同一哲学）
        │
        ▼
[③ 写入 A 的缓存]（**先模拟再执行，绝不出现「从网络扣了却放不进 A」**）
   if got > 0:
       long accepted = A.cache.insert(resource, got, Action.SIMULATE, ACTOR);
       if accepted == 0        → 把刚取出的 got 原样 insert 回网络；本轮结束（A 缓存满）
       if accepted < got       → 只 EXECUTE accepted 的量，余量原样写回网络
       A.cache.insert(resource, accepted, Action.EXECUTE, ACTOR)
       B.pending -= accepted
        │
        ├─ 失败点 F7：A 缓存放不下 ⇒ **回写网络**（不回写就等于销毁玩家资源）
        └─ 失败点 F8：A 在写入的一瞬间被拆 ⇒ 回写网络
        │
        ▼
[④ B 的「无数量上限」如何收敛]
   若 amount > 单批能力 ⇒ B 记 pending，按自己的节拍（速度升级）继续搬，直到 pending == 0。
   **pending 必须持久化在 B 的 NBT 里**（否则存档/重载后剩下的量会凭空消失）。
        │
        ▼
[⑤ A 每 tick（受速度升级节流）往外推]
   A.doWork()（由 RS 的 NetworkNodeTicker 驱动；周期 = 9 - 2×speed，见 §5.2）
     for each 缓存条目（按稳定顺序）:
         目标容器 = 朝向面（A 的 FACING）的相邻方块
         IItemHandler  h = RsccChamberImportStrategy.itemHandlerAt(level, facingPos)      // :1577
         IFluidHandler f = RsccChamberImportStrategy.fluidHandlerAt(level, facingPos)     // :1608
         单次量 = base × (叠加升级 ×64)（§5.2）
         **先 SIMULATE 求得能吃下多少，再 EXECUTE 抽出同样多**
        │
        ├─ 失败点 F9：朝向面没有容器 / 没有对应能力 ⇒ **什么都不做，缓存保留**
        └─ 失败点 F10：容器满了 ⇒ 只搬吃得下的部分，余量留缓存（下一 tick 再来）
```

### 7.4 「无数量上限」怎么处理

| 层面 | 上限在哪 | 处理 |
| ---- | -------- | ---- |
| **数据类型** | RS 的 `ResourceAmount.amount` 与 `Storage.insert/extract` 都是 **`long`**（`javap` 实证） | ✅ 天然支持远大于 `int` 的量 |
| **界面输入** | 界面输入框需要一个数值类型 | 用 **`long`**；不要用 `int`（`BigItemStack.count` 的 `int` 正是 Create 的局限之一，见 L4） |
| **单批搬运** | 一次 `insert/extract` 可以传 `long`；但**目标容器**（`IItemHandler` / `IFluidHandler`）本质按「每格 ≤ 64 / 每罐 ≤ 容量」工作 | **分批**：一批 = min(剩余, 单批上限)，单批上限由**叠加升级**放大（§5.2）。**这正是叠加升级在 A/B 上的语义落点** |
| **跨 tick 收敛** | 一个脉冲搬不完 | **B 的 pending 持久化**（§7.3 ④） |
| **脉冲语义** | 玩家可能连发脉冲 | pending 累加（不是覆盖），并给一个合理上限或提示（**这一条的设计细节待你确认**，列入 §⑧） |
| **网络侧的原生 cap** | RS 的 Exporter 默认会把物品量 **×64**（`ExporterTransferQuotaProvider`，§5.2）；`PlatformResourceKey.getInterfaceExportLimit()` 也可能是个截断点 | ⚠️ **A/B 不走 Exporter 路径**，所以不受 `ExporterTransferQuotaProvider` 影响。但 `getInterfaceExportLimit()` 的**实际用途未证实**（§⑧） |

### 7.5 失败处理的总原则（继承本工程铁律）

1. **绝不销毁、绝不复制（守恒）**。三条硬规则：
   - 「从网络扣了却放不进 A」 ⇒ **回写网络**；
   - 「从 A 缓存抽了却放不进朝向容器」 ⇒ **只抽放得下的量**（先 SIMULATE）；
   - 「A 被拆」 ⇒ 缓存按全局 `BlockContentPolicy` 结算（`support/BlockContentPolicy.java:50-58`），
     复用自动合成仓的既有做法（`mixin/AutocrafterStorageMixin.java:118-147`）。
2. **先模拟、后执行**。这是本工程所有搬运路径的统一写法
   （`AutocrafterStorageController.java:30-34` 的三条安全保证、`:152-177` 的 `canFlushAll`）。
3. **失败要可诊断，不要静默**。命名解析失败 / 重名 / 缓存满 都要有明确信号；
   复用既有的提示通道（`CompatCommands` 的 `sendFailure`，`command/CompatCommands.java:426`；
   Create 请求器的「成功/失败」反馈是 `lastRequestSucceeded` + `getAnalogOutputSignal`
   `<RedstoneRequesterBlock.java:82-86>`，**建议 B 也提供比较器输出**）。
4. **节流要自愈**。不要引入「需要持久化并且可能说谎的计数器」——
   本工程在这件事上有明确的既有立场
   （`SequenceExecutionChamberBlockEntity.java:15034-15041`：
   「机器把料吃掉后容器里就查不到它了 —— 此刻立刻放行下一份。因此节流是**自愈**的」）。
5. **开关关闭 = 先回写、再关闭、装不下就取消**。直接复用
   `AutocrafterStorageController.disableGroup` 的三步（`:120-149`）。

### 7.6 为什么不让 A 直接当「网络存储源」（`StorageProvider`）

**这是一个真实的设计岔路，必须明确表态：**

- 若 A 实现 `StorageProvider`（像 `IntermediateCacheNetworkNode` 那样，
  `network/IntermediateCacheNetworkNode.java:52`、`:79-82`），则 A 的缓存会成为**网络的存储源**，
  自动合成 / 其他机器可以**直接往里写**——这会**绕过 B**，让 B 的存在失去意义。
- 若 A **不**实现 `StorageProvider`（**推荐**），则 A 的缓存是一个**私有缓冲**，
  只能由 B 写入、由 A 自己输出到朝向容器。
  **投递的唯一入口就是「按名字找 A」这条路径**，语义清晰、可审计。

**⇒ 推荐：A 不实现 `StorageProvider`。**
（但 A 仍然必须实现 `NetworkNodeContainerProvider`
——那是「接进网络」的必要条件，`IntermediateCacheBlockEntity.java:236-242` 同款。）

---

## ⑧ 明确列出「未证实 / 需要你确认」的点

### 8.1 未证实（我没有证据，不敢猜）

| # | 未证实的点 | 我做了什么 / 还需要什么 |
| - | ---------- | ---------------------- |
| U1 | **`UpgradeContainer` 在 `workTickRate ≤ 0` 时是否有保护**。`javap` 显示 `workTicks % workTickRate`，按 Java 语义**会抛 `ArithmeticException`**；但 RS 可能在别处夹住，我**没有找到那段代码** | 需要的验证：反编译完整 `UpgradeContainer`（jar 里只有 `.class`，本机**没有 2.0.9 sources jar**），或在开发环境里塞 5~6 个速度升级实测 |
| U2 | **`PlatformResourceKey.getInterfaceExportLimit()` / `getProcessingPatternLimit()` 的实际用途与数值语义** | 只有接口签名（`javap`）。需要看调用点才能判断它会不会截断「无数量上限」 |
| U3 | **`AutocrafterBlockEntity#accept` 的**真实**签名**。`javap` 显示公开方法是 `accept(Pattern, Collection<ResourceAmount>, Action)`（3 参），但 `mixin/AutocrafterStorageMixin.java:149-152` 的注入签名是 `(Collection<ResourceAmount>, Action)`（2 参）——**两者不一致** | 要么 Mixin 目标签名会随版本变化（值得警惕），要么 `javap -p` 的输出被 bridge/synthetic 方法干扰。**我没有进一步验证**（Mixin 在运行时才匹配） |
| U4 | **RS 是否提供「注册自定义 `ExternalPatternSink`」的公开扩展点** | `javap` 可见 `RefinedStorageApi.addPatternProviderExternalPatternSinkFactory(...)` / `getPatternProviderExternalPatternSinkFactory()`，但**它是不是给「任意方块」用的、还是只给 pattern provider 用的，我没有证据**。**本设计因此刻意不依赖它**（A 不实现 `StorageProvider`，见 §7.6） |
| U5 | **Create 的 `AddressEditBox` / `programRequester` 之外，是否还有其它地方维护「地址清单」** | 我只查了 `content/logistics/` 下的地址相关类。**结论「Create 没有地址注册表」是基于这些证据的推断**，不是全仓证明 |
| U6 | **`Network.merge` 之后重名 A 的具体行为** | `javap` 证明 `Network` 有 `merge(Network)` / `split(Set<Network>)`（网络会合并/分裂），但**合并后我设计的「现解析 + 重名即拒绝」的实际体验如何，没有实测** |
| U7 | **Mekanism 的 `Capabilities.CHEMICAL.block()` 在 NeoForge 的 `RegisterCapabilitiesEvent` 之外能否用 `level.getCapability(...)` 直接取** | `javap`/源码显示它是 `MultiTypeCapability<IChemicalHandler>`（`Mekanism Capabilities.java:59`），`.block()` 返回 `BlockCapability`；**但本工程从未实际调用过 Mekanism 的任何 API**，未经验证 |
| U8 | **`ResourceTag` / JEI 联动 / 自动合成对「自定义资源类型」的要求** | 见 R3，未证实 |

### 8.2 需要你确认（这些是**决策**，不是事实）

| # | 问题 | 我的建议 | 影响面 |
| - | ---- | -------- | ------ |
| **Q1** | 「使用同一个命令进行开关」到底要多严格？ | **S2**：新增 `/rs_create_compat receiver storage on\|off`，与自动合成仓那条**共用同一套守恒语义与代码**，但**不共用字面量**（理由见 §1.3） | 指令树、语言文件、玩家习惯；**改动最小且语义最诚实** |
| **Q2** | 升级槽只放 A，还是 A + B 都放？ | **都放**（A 必放，用户明确；B 建议也放 6 槽，理由见 §1.4）。**若只能一个 ⇒ 给 A** | 界面、tooltip、B 的批量投递能否被加速 |
| **Q3** | **叠加升级的口径**：跟 RS 原版（有就 ×64）还是跟本工程既有（每级 ×64）？ | **跟本工程既有（每级 ×64）**（`SchematicLoaderBlockEntity.java:447-449` 已是这个口径，且注释写明是「唯一实现」） | 数值平衡、tooltip 文案 |
| **Q4** | **连发脉冲**怎么算？累加 pending 还是忽略？ | **累加**，并给一个可配上限（防止无限 pending 撑爆 NBT） | B 的 NBT 结构、失败提示 |
| **Q5** | **重名冲突在「已经发生」时**（网络合并/存档直改）：是拒绝投递还是随机挑一个？ | **拒绝投递 + 明确报警**（静默错误投递比不投递更坏，见 §4.4） | 玩家体验、日志 |
| **Q6** | **本版是否做气体**？ | **不做**，但把资源抽象保持在 `PlatformResourceKey` 这一层（理由见 §6.4） | 工作量、存档兼容风险 |
| **Q7** | A 的缓存**要不要给玩家一个可见/可取的界面**？（既然用户说「也可以有缓存」） | **要给一个只读展示 + 可取出的界面**（否则缓存满了玩家看不见原因，只会觉得「东西卡住了」） | 界面工作量 |

### 8.3 本轮的**未做**（遵守任务硬约束）

- ❌ **未修改任何 Java 源码**（本任务只做调研与设计）。
- ❌ **未改动 `run/`**。
- ❌ **未解压任何 jar 到工作区**（RS / Create 的 jar 全部用 `javap` 与 Python `zipfile` 直读）。
- ❌ **未新增任何依赖**。
- ✅ 只新增本文件：`docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md`。
