# 区域选定器（多区域 CRUD）+ 按区域吸收 + 充电范围显示 —— API 调研与设计

> 状态：**只调研 + 设计，未改任何 Java，未碰 `run/`。**
> 适用版本：MC 1.21.1 / NeoForge 21.1.x / **Refined Storage 2.0.9** / **Create 6.0.10-280**。
> 证据来源两类：
> 1. 本工程源码（相对路径 `src/main/java/...`，行号以当前工作树为准）；
> 2. Create 本地缓存中的**源码 jar**（**未解压**，用 Python `zipfile` 直读）：
>    `D:\gradle\caches\modules-2\files-2.1\com.simibubi.create\create-1.21.1\6.0.10-280\...\create-1.21.1-6.0.10-280-sources.jar`
>    Catnip（`net.createmod.catnip`）实际打包在 **ponder jar** 内：
>    `...\net.createmod.ponder\ponder-neoforge\1.0.82+mc1.21.1\...\ponder-neoforge-1.0.82+mc1.21.1-sources.jar`
>    下文凡写 `create/...` 或 `catnip/...` 均指这两个 jar 内的路径。
> 凡没有找到证据的，一律标 **「未证实」**，不猜。
>
> **证据抓取时间**：2026-10-23 22:33–23:24（工作树快照）。本工程 `src/` 下有多处**非本次调研产生**的未提交改动（`git status` 可见），若之后这些文件被继续修改，行号可能整体偏移 —— 复核时请以**类名 + 方法名**为准，行号仅作定位辅助。

---

## 0. 结论速览

| # | 问题 | 结论 | 关键证据 |
|---|---|---|---|
| ① | 「硅流缓存器」是什么 | **工程里没有这个方块**。它 100% 是 **`归流缓存仓` (`collection_cache`)** 的同音误写/口误。用户自己在 `docs/GUI_FEEDBACK_ROUND3.md:181-182` 就把「归流缓存仓」写作「硅流缓存仓」，且上下文谈的正是该仓的范围升级 | `lang/zh_cn.json` → `block.rs_create_compat.collection_cache = 归流缓存仓`；`GUI_FEEDBACK_ROUND3.md:181-182` |
| ② | 蓝图框选能不能直接复用 | **不能复用代码，只能复用「套路」**。Create 的框选是 **纯客户端私有状态**（`SchematicAndQuillHandler.firstPos/secondPos` 两个 `BlockPos` 字段，不进 NBT），且 `isActive()` 里**硬编码**只认主手 `AllItems.SCHEMATIC_AND_QUILL`，**没有**任何「外部注入区域」的 API。要做多区域必须自己写一份同款 handler | `create/.../SchematicAndQuillHandler.java:44-45, 93-128, 198-200` |
| ② | 交互走哪个事件 | **不是** `UseOnContext`，**也不是** `PlayerInteractEvent`。走 `InputEvent.MouseButton.Pre`（右键）+ `InputEvent.MouseScrollingEvent`（Ctrl+滚轮改大小），并在事件里 `setCanceled(true)` | `create/.../InputEvents.java:46-72` |
| ② | 框选怎么画、颜色在哪 | `Outliner.getInstance().chaseAABB(...).colored(0x6886c5).withFaceTextures(CHECKERED, HIGHLIGHT_CHECKERED).lineWidth(1/16f)`。**颜色 = `0x6886c5`（蓝）**，硬编码在 3 处 | `SchematicAndQuillHandler.java:180-186`、`SchematicHandler.java:369-371`、`SchematicToolBase.java:139-142` |
| ② | 渲染要不要自己挂渲染事件 | **不用**。Ponder 已经在 `RenderLevelStageEvent.Stage.AFTER_PARTICLES` 渲染全局 `Outliner`，并在 `ClientTickEvent.Pre` 里 `tickOutlines()`；我们只负责每 tick「喂」一个 AABB | `ponder/NeoForgePonderClient.java:60-66`、`ponder/PonderClient.java:50-59` |
| ③ | 数据放哪 | **数据组件（`DataComponentType`）**，本工程已有**带自定义 record + Codec + StreamCodec** 的完整先例（`RAW_MATERIAL`），照抄即可 | `RS_Create_Compat.java:96-110`、`support/SequenceMaterialGuard.java:61-71` |
| ④ | 按区域吸收与既有 XYZ 的关系 | **交集（AND）** —— 用户原话是「限位」，即「在 XYZ 之内 **再** 被区域筛一遍」；同时给一个 config 开关可切成「仅区域」 | 需求原文 + `CollectionCacheBlockEntity.java:1850-1858, 1955-1971` |
| ④ | 安全上限 | 建议：**每区域 ≤ 32768 格（32³）**、**≤ 16 个区域**、**每 tick 全局方块查询预算 2048 次（跨 tick 游标续扫）**。理由见 §4.3，其中包含一个**既有实现的已证实缺陷**：流体扫描在半径 > 11 时永远只扫立方体一角的 4096 格 | `MAX_FLUID_BLOCKS_PER_SCAN = 4096`（`:118`）、`BlockPos.betweenClosed` 每次从头开始（`:1960-1965`） |
| ⑤ | 充电范围怎么显示 | 复用 **`Outliner` + `showAABB(slot, box, ttl)`**（与蓝图同一套），**换色 `0xFF9F1C`（橙）**；范围语义是 **`rangeX/Y/Z` = 直径不是半径**（`halfX = rangeX/2`），画框必须用同一口径 | `RangeChargerBlockEntity.java:63-65, 695-704, 876-885`；`Outliner.java:68-80` |
| ⚠ | C 盘 | 全程只在 D 盘写 1 个文档（~64 KB），无大文件；读取 jar 用 `zipfile`，未解压 | —— |

---

## 1. 「硅流缓存器」到底是什么（核实结论）

### 1.1 先给结论

**本工程没有「硅流缓存器」这个方块，也没有任何叫 `Silicon*` / `硅流*` 的类。**

- 对 `src/`（本工程自有代码）grep `硅流|Silicon|silicon|缓存器`：**0 命中**（实测 `grep -rniE '硅流|silicon|缓存器' src/` → 0 行）。132 个命中全部在 `_audit_tmp/`、`build/`、`local_src/external/` 里，即 RS / Mekanism / Create 的上游源码与解包产物，**不是我们的代码**。
- 我们的方块名单里只有两个「缓存」类机器：
  - `collection_cache` → **归流缓存仓**（`block.rs_create_compat.collection_cache`）
  - `intermediate_cache` → **中间产物缓存仓**（`block.rs_create_compat.intermediate_cache`）
  证据：`src/main/resources/assets/rs_create_compat/lang/zh_cn.json`。

```
block.rs_create_compat.collection_cache        = 归流缓存仓
block.rs_create_compat.collection_cache.help   = 接入 RS 网络后吸取周围掉落物、流体与气体，装不下先暂存并回流。
block.rs_create_compat.intermediate_cache      = 中间产物缓存仓
block.rs_create_compat.intermediate_cache.help = 接入网络后，27 格盘位成为全网执行舱共用的中间产物缓存。
```

### 1.2 为什么可以判定是「归流缓存仓」而不是别的

**决定性证据是本仓库自己的文档**：`docs/GUI_FEEDBACK_ROUND3.md:181`（用户原话转写）

> 「……然后就是那个**范围**我要的是三轴都可以调控你现在只能调控一个直接的范围不是我想要的，而且为**归流缓存仓**它匹配许可缓存区的滚动条都滚动不了，然后给这个**硅流缓存仓**的物品升级允许它加入范围升级和无限范围升级具体它们俩的实现参考我之前跟你说的那个**范围充电器**的一样的……」

同一个句子里先写「归流缓存仓」、紧接着写「硅流缓存仓」，指的都是**同一台机器**（上下文：三轴范围、范围升级 +25、参考范围充电器 —— 这**逐字对应** `CollectionCacheBlockEntity` 的现有实现）。第 182 行继续写「你现在这个**硅流缓存仓**里面没有检测一下」。

**结论**：
- 「硅流缓存器」= **归流缓存仓** `rs_create_compat:collection_cache`，同音（guī liú huǎn cún …）字不同。
- **不是** `CollectionCacheBlockEntity` 之外的东西；**不是** RS 的「磁盘仓」；**不是**「中间产物缓存仓」。
- 所以「高级硅流缓存器」= 「**归流缓存仓的高级版**」，基础版存在（`CollectionCacheBlock` / `CollectionCacheBlockEntity`），**不需要**问「基础版在哪」。

### 1.3 基础版现有什么能力（决定「高级版」加什么）

类：`src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/CollectionCacheBlockEntity.java`（3521 行）。

**吸收范围 = 以自身方块为中心、三轴各自独立的三轴半径**（注意：这里与范围充电器不同，**是半径**）：

```java
// :218-220
private int radiusX = Config.collectionCacheScanRadius;
private int radiusY = Config.collectionCacheScanRadius;
private int radiusZ = Config.collectionCacheScanRadius;
```

| 来源 | 扫描方式 | 证据 |
|---|---|---|
| 掉落物 | `AABB(worldPosition).inflate(rX, rY, rZ)` → `level.getEntitiesOfClass(ItemEntity.class, ...)` | `:1850-1858` |
| 经验球 | 同上（同一个 AABB 口径） | `:1712-1717` |
| 世界流体源 | **逐格遍历** `BlockPos.betweenClosed(pos-(rX,rY,rZ), pos+(rX,rY,rZ))`，单次最多 4096 格 | `:1955-1971` |
| 气体 | **空实现**（RS 2.0 无气体资源类型，预留入口） | `:1607-1611` |
| 相邻容器 | 只按**选中面**抽（6 个面），不按区域 | `absorbAll` → `collectFromAdjacentContainers`（`:1296-1298`）|

**范围上限**：基础 25 格，每个 `range_upgrade` +`Config.rangeChargerRangePerUpgrade`（默认 25），放 `creative_range_upgrade` 则无限：

```java
// :110-112
public static final int MIN_COLLECT_RADIUS = 1;
public static final int MAX_COLLECT_RADIUS = 25;
// :380-388
public int getMaxCollectRadius() {
    if (hasInfiniteRange()) {
        return Integer.MAX_VALUE - 1;
    }
    final long bonus = (long) countUpgrades(RANGE_UPGRADE) * Config.rangeChargerRangePerUpgrade;
    return (int) Math.min(Integer.MAX_VALUE - 1, MAX_COLLECT_RADIUS + bonus);
}
```

配置默认值：`Config.java:98-103` → 扫描间隔 20 tick、扫描半径 5 格（范围 1..64）。

**扫描节流**：`getScanInterval()`（`:440-461`）——范围越大扫得越勤（半径档 `1 + (r-1)/2`），再按速度升级做移位，满级 1 tick。

**结论**：基础版已经有「XYZ 三轴范围 + 范围升级 + 无限范围」，**高级版唯一缺的就是「区域限位」**（用户原话：「增加『限位』功能，避免普通 XYZ 范围吸收到不想收的物品」）。这也说明高级版不是「数值更大」，而是「**范围可以被形状约束**」。

---

## 2. 蓝图的框选：交互 / 渲染 / 颜色（两处需求都可能复用）

> 先纠正一个常见误解：**框选逻辑不在物品类里**。
> `create/.../SchematicAndQuillItem.java:23-27` 全文只有构造器 + 3 个静态辅助方法（`replaceStructureVoidWithAir` / `clampGlueBoxes` / `newDoubleList`），**没有** `use`、**没有** `useOn`、**没有** `UseOnContext`。
> 真正的框选在客户端的 `SchematicAndQuillHandler`。

### 2.1 交互：两个 NeoForge 客户端输入事件，不是方块交互

**`create/.../foundation/events/InputEvents.java:46-72`**（原文）

```java
@SubscribeEvent
public static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
    if (Minecraft.getInstance().screen != null) return;
    double delta = event.getScrollDeltaY();
    boolean cancelled = CreateClient.SCHEMATIC_HANDLER.mouseScrolled(delta)
        || CreateClient.SCHEMATIC_AND_QUILL_HANDLER.mouseScrolled(delta) || TrainHUD.onScroll(delta)
        || ElevatorControlsHandler.onScroll(delta);
    event.setCanceled(cancelled);
}

@SubscribeEvent
public static void onMouseInput(InputEvent.MouseButton.Pre event) {
    if (Minecraft.getInstance().screen != null) return;
    int button = event.getButton();
    boolean pressed = !(event.getAction() == 0);
    RadialWrenchHandler.onKeyInput(button, pressed);
    if (CreateClient.SCHEMATIC_HANDLER.onMouseInput(button, pressed))
        event.setCanceled(true);
    else if (CreateClient.SCHEMATIC_AND_QUILL_HANDLER.onMouseInput(button, pressed))
        event.setCanceled(true);
}
```

**`SchematicAndQuillHandler.java:93-128`**（原文，右键 = button 1）

```java
public boolean onMouseInput(int button, boolean pressed) {
    if (!pressed || button != 1) return false;
    if (!isActive()) return false;
    LocalPlayer player = Minecraft.getInstance().player;
    if (player.isShiftKeyDown()) { discard(); return true; }        // 潜行右键 = 取消
    if (secondPos != null) { ScreenOpener.open(new SchematicPromptScreen()); return true; } // 已有两点 = 开命名屏
    if (selectedPos == null) { /* 提示 noTarget */ return true; }
    if (firstPos != null) { secondPos = selectedPos; /* 提示 secondPos */ return true; }
    firstPos = selectedPos;                                        // 第一次 = 第一点
    return true;
}
```

**选中点的来源**：`tick()` 里每帧射线（`SchematicAndQuillHandler.java:138-164`）：

```java
if (AllKeys.ACTIVATE_TOOL.isPressed()) {           // 按住某键 = 按视线距离取远处点
    Vec3 targetVec = player.getEyePosition(pt).add(player.getLookAngle().scale(range));
    selectedPos = BlockPos.containing(targetVec);
} else {
    BlockHitResult trace = RaycastHelper.rayTraceRange(player.level(), player, 75);
    if (trace != null && trace.getType() == Type.BLOCK) {
        BlockPos hit = trace.getBlockPos();
        boolean replaceable = player.level().getBlockState(hit)
            .canBeReplaced(new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, trace)));
        if (trace.getDirection().getAxis().isVertical() && !replaceable)
            hit = hit.relative(trace.getDirection());   // 点顶面/底面时放到相邻格
        selectedPos = hit;
    } else selectedPos = null;
}
```

**Ctrl + 滚轮 = 改框的大小**（`SchematicAndQuillHandler.java:50-91`）：`range` 可 1..100；已有两个点时，沿**选中面法线**推/拉 AABB，再把 `firstPos/secondPos` 重新由 AABB 的 min/max 反算（`:83-84`），并 `CreateLang.translate("schematicAndQuill.dimensions", ...)` 报尺寸。这个「用滚轮改大小」正是用户需求里「可编辑区域大小」的现成交互范式。

**状态存在哪：只在内存，不进 NBT**（`SchematicAndQuillHandler.java:42-48`）：

```java
private Object outlineSlot = new Object();
public BlockPos firstPos;
public BlockPos secondPos;
private BlockPos selectedPos;
private Direction selectedFace;
private int range = 10;
```

=> 退出游戏 / 交给别人 / 换物品，框选状态**全部丢失**；真正落盘的是「保存成蓝图」之后的 `.nbt` 文件（`SchematicExport.saveSchematic(...)`，`:207-233`）以及物品上的数据组件（见 §2.4）。

### 2.2 渲染：Create 的框 = catnip `Outliner` 的一个 AABB

**`SchematicAndQuillHandler.java:180-186`**（原文）

```java
AABB currentSelectionBox = getCurrentSelectionBox();
if (currentSelectionBox != null)
    outliner().chaseAABB(outlineSlot, currentSelectionBox)
        .colored(0x6886c5)
        .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
        .lineWidth(1 / 16f)
        .highlightFace(selectedFace);
```

`getCurrentSelectionBox()`（`:189-196`）说明框的构造方式：两点之间取 AABB 再 **`expandTowards(1,1,1)`**（因为两点是「方块坐标」，要变成「包围盒」），第三点未定时用 `selectedPos` 做预览：

```java
if (secondPos == null) {
    if (firstPos == null) return selectedPos == null ? null : new AABB(selectedPos);
    return selectedPos == null ? new AABB(firstPos)
        : new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(selectedPos)).expandTowards(1, 1, 1);
}
return new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(secondPos)).expandTowards(1, 1, 1);
```

**谁负责调用渲染**（这是「我们能不能白嫖渲染」的关键）：

- `catnip/outliner/Outliner.java:82-87`

```java
public OutlineParams chaseAABB(Object slot, AABB bb) {
    createAABBOutlineIfMissing(slot, bb);
    ChasingAABBOutline outline = getAndRefreshAABB(slot);
    outline.targetBB = bb;
    return outline.getParams();
}
```

- 每 tick 维护 & 每帧渲染，**已经由 Ponder 做了**：`ponder/NeoForgePonderClient.java:52-66`

```java
@EventBusSubscriber(Dist.CLIENT)
public static class ClientEvents {
    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre event) { PonderClient.onTick(); ... }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        PonderClient.onRenderWorld(event.getPoseStack());
    }
}
```

- `ponder/PonderClient.java:50-59`（`tickOutlines`）与 `:63-75`（`renderOutlines`）。

而 **Ponder 是 Create 的硬依赖**，随 Create 必装（`create-1.21.1-6.0.10-280-slim.jar!/META-INF/neoforge.mods.toml`）：

```toml
[[dependencies."create"]]
modId = "ponder"
type = "required"
versionRange = "[1.0.82,)"
ordering = "AFTER"
side = "BOTH"
```

=> **只要装了 Create，`Outliner` 就有人 tick、有人 render。我们不需要自己挂 `RenderLevelStageEvent`。**

### 2.3 颜色与贴图在哪定义

| 位置 | 颜色 | 用途 |
|---|---|---|
| `SchematicAndQuillHandler.java:183` | `0x6886c5` | 蓝图与笔的**框选**（需求十五要求「换色」就是相对这个） |
| `SchematicHandler.java:369-371` | `0x6886c5` | 已放置蓝图的**外框** |
| `SchematicToolBase.java:139-142` | `0x6886c5` | 蓝图工具（移动/旋转/放置）的框 |
| `FlipTool.java:76`、`RotateTool.java:36` | `0xdddddd` | 镜像/旋转手柄 |

面贴图枚举（`create/.../AllSpecialTextures.java:6-22`）：`CHECKERED("checkerboard.png")`、`HIGHLIGHT_CHECKERED("highlighted_checkerboard.png")`，资源目录 `textures/special/`。

**颜色怎么进渲染管线**（决定「能不能设 alpha」）：

```java
// catnip/outliner/Outline.java:538-541
public OutlineParams colored(int color) {
    rgb = new Color(color, false);      // false = 不含 alpha，纯 0xRRGGBB
    return this;
}
// :599-601
public void loadColor(Vector4f vec) {
    vec.set(rgb.getRedAsFloat(), rgb.getGreenAsFloat(), rgb.getBlueAsFloat(), rgb.getAlphaAsFloat() * alpha);
}
```

```java
// catnip/outliner/AABBOutline.java:97-99
float alphaMult = highlighted ? 1 : 0.5f;          // 非高亮面 = 半透明的一半
colorTemp1.set(color.x(), color.y(), color.z(), color.w() * alphaMult);
```

=> **`colored(int)` 不能带 alpha**；透明度由 `params.alpha`（渲染时被 `renderOutlines` 置 1，消散时按 `FADE_TICKS` 淡出）+ 面贴图的 0.5 倍共同决定。

### 2.4 蓝图「数据化」的现成先例：Create 自己就用数据组件

`SchematicHandler.java:357-372`（原文）

```java
public void loadSettings(ItemStack blueprint) {
    StructurePlaceSettings settings = SchematicItem.getSettings(blueprint);
    transformation = new SchematicTransformation();
    deployed = blueprint.getOrDefault(AllDataComponents.SCHEMATIC_DEPLOYED, false);
    BlockPos anchor = blueprint.getOrDefault(AllDataComponents.SCHEMATIC_ANCHOR, BlockPos.ZERO);
    Vec3i size = blueprint.get(AllDataComponents.SCHEMATIC_BOUNDS);
    if (size == null) return;
    bounds = new AABB(0, 0, 0, size.getX(), size.getY(), size.getZ());
    outline = new AABBOutline(bounds);
    outline.getParams().colored(0x6886c5).lineWidth(1 / 16f);
    transformation.init(anchor, settings, bounds);
}
```

注册处（`create/.../AllDataComponents.java:170-205`）：`SCHEMATIC_DEPLOYED(Boolean)` / `SCHEMATIC_OWNER(String)` / `SCHEMATIC_FILE(String)` / `SCHEMATIC_ANCHOR(BlockPos)` / `SCHEMATIC_ROTATION(Rotation)` / `SCHEMATIC_MIRROR(Mirror)` / `SCHEMATIC_BOUNDS(Vec3i)` / `SCHEMATIC_HASH(Integer)`。

**要点**：蓝图把「**尺寸 `Vec3i` + 锚点 `BlockPos`**」存成**数据组件**，而不是存 min/max 两个角 —— 因为它要「随机变换：旋转/镜像/移动」，锚点 + 尺寸更稳。我们的「区域」是**世界坐标的固定盒子**，用 min/max 更直接（见 §3.2）。

### 2.5 「复用蓝图的框选功能」：能复用/不能复用，逐条摊开

| 项 | 能复用吗 | 证据 / 理由 |
|---|---|---|
| 两点选取的**交互范式** | ✅（照抄，非调用） | 交互只是「主手拿着某物品 + `InputEvent.MouseButton.Pre` + 自己射线」，没有任何 Create 专有状态 |
| Ctrl+滚轮改大小的**算法** | ✅（照抄 ~40 行） | `SchematicAndQuillHandler.java:50-91`，纯几何 |
| **代码层面**调用它的框选 | ❌ | `isActive()` 硬编码主手物品：`return isPresent() && AllItems.SCHEMATIC_AND_QUILL.isIn(Minecraft.getInstance().player.getMainHandItem());`（`:198-200`）；`isActive` 是 **private**，外部/子类都改不了；`firstPos/secondPos` 只有**一组**，没有 `List<Area>` 概念 |
| **渲染**（画出那个框） | ✅ **完全复用** | `Outliner.getInstance()` 是全局单例（`Outliner.java:40-42` 起全 public），Ponder 已负责 tick + render |
| **颜色** | ✅ 任意 | `.colored(0x______)` |
| **面贴图** | ⚠️ 需引用 Create 的枚举 | `AllSpecialTextures` 是 `com.simibubi.create` 下的 public enum；也可以传 `null`（`withFaceTexture(null)` → 只画线不画面，见 `AABBOutline.java:90-92`：`if (faceTexture == null) return;`） |

> **一句话**：用户说「直接复用蓝图的框选功能」——**在体验上可以做到 1:1（同样的两点 + Ctrl 滚轮 + 同样的框）**，但**在代码上必须重写一份 handler**（约 150 行），因为 Create 没有留出「再加一组框」的接口。

### 2.6 若依赖 Create 内部类：版本升级风险与缓解（照本工程既往做法）

**风险清单**

| 依赖点 | 稳定性 | 说明 |
|---|---|---|
| `InputEvent.MouseButton.Pre` / `MouseScrollingEvent` | 🟢 稳 | NeoForge 公共事件 |
| `Outliner` / `OutlineParams`（catnip，在 ponder jar 里） | 🟡 中 | 第三方库，但被 Create 硬依赖；`chaseAABB/showAABB/colored/lineWidth` 是 Create 生态的常用 API（实测 Create 自己在 `SchematicAndQuillHandler`、`SchematicHandler:370`、`SchematicToolBase:140`、`FlipTool:76`、`RotateTool:36` 等处用它） |
| `AllSpecialTextures.CHECKERED` | 🟡 中 | Create 内部枚举；可改用 `null` 面贴图规避 |
| `RaycastHelper.rayTraceRange(level, player, 75)` | 🔴 偏险 | Create 内部工具类；**建议换原版**：`player.pick(75, pt, false)` 或 `level.clip(...)`（原版 API，零风险） |
| `AllKeys.ACTIVATE_TOOL` | 🔴 险 | Create 键位；**建议换原版 `Minecraft.getInstance().options.keyUse.isDown()`** 或自己的 `KeyMapping` |
| `SchematicAndQuillHandler` 本身 | 🔴 险 | 明确**不引用**（`isActive` 私有，引用也没用） |

**本工程既往的缓解手法（有先例，不是我发明的）**

1. **反射 + 失败即降级**：`SchematicLoaderBlockEntity.java:1439-1462` 用 `getDeclaredField("blockReader")` 读 Create 私有字段，整个 `try/catch(Exception)` 包住，失败就 `return null`（功能消失，不崩）。
2. **`@Nullable` + 可选能力**：同文件 `:1429-1435`，`printer.isLoaded()/isErrored()` 双重判空。
3. **依赖集中在少数「接入点」类**：

```
src/main/java/.../client/CableCutPreviewOverlay.java   ← 世界叠加层的全部 Create/NeoForge 依赖都在这一个类里
src/main/java/.../block/entity/SchematicLoaderBlockEntity.java ← Create 蓝图相关
```

4. **`dependency-policy.md`（仓库规矩）** §4 要求：接入类**不得有静态副作用**、缺失表现 = 「功能不出现」而不是「启动失败」。

**建议的落地纪律**（写进代码注释即可）：
- 新建的 `AreaSelectorHandler` / `AreaOutlineRenderer` 各只有**一个**类接触 `Outliner`；换 Create 大版本时**只改这两个类**。
- 所有 `Outliner` 调用外包一层 `try { ... } catch (Throwable t) { disabled = true; }`，并在 `disabled` 后不再重试（避免每帧刷异常日志）。
- 射线、键位一律用**原版 API**，不碰 `RaycastHelper` / `AllKeys`。
- 面贴图先用 `null`（只有线框），等确认贴图枚举稳定再考虑 `CHECKERED`。

---

## 3. 区域选定器（Area Selector）：数据设计 + CRUD 交互

### 3.1 数据放哪：数据组件（1.21.1 推荐），本工程已有强先例

**先例一（本工程，且是自定义 record）** —— `RS_Create_Compat.java:96-110`（原文）

```java
public static final DeferredRegister.DataComponents DATA_COMPONENTS =
    DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> TAG_FILTER =
    DATA_COMPONENTS.registerComponentType("tag_filter",
        builder -> builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

public static final DeferredHolder<DataComponentType<?>, DataComponentType<RawMaterialMark>>
    RAW_MATERIAL = DATA_COMPONENTS.registerComponentType("raw_material",
        builder -> builder.persistent(RawMaterialMark.CODEC)
            .networkSynchronized(RawMaterialMark.STREAM_CODEC));
```

**record + 双 Codec 的写法** —— `support/SequenceMaterialGuard.java:61-71`（原文）

```java
public record RawMaterialMark(ResourceLocation recipe, int step) {
    public static final Codec<RawMaterialMark> CODEC = RecordCodecBuilder.create(builder -> builder.group(
        ResourceLocation.CODEC.fieldOf("recipe").forGetter(RawMaterialMark::recipe),
        Codec.INT.fieldOf("step").forGetter(RawMaterialMark::step)
    ).apply(builder, RawMaterialMark::new));

    public static final StreamCodec<ByteBuf, RawMaterialMark> STREAM_CODEC = StreamCodec.composite(
        ResourceLocation.STREAM_CODEC, RawMaterialMark::recipe,
        ByteBufCodecs.INT, RawMaterialMark::step,
        RawMaterialMark::new);
}
```

**先例二（Create 侧，同样用数据组件存几何）**：`SCHEMATIC_BOUNDS(Vec3i)`（§2.4）。

**结论**：
- **用 `DataComponentType`，不要用 `DataComponents.CUSTOM_DATA` 塞 NBT。**
- 必须同时提供 `persistent(Codec)`（存档/掉落物保留）与 `networkSynchronized(StreamCodec)`（客户端要画框、GUI 要显示列表）。
- 同样的理由见工程里另一个走 `CUSTOM_DATA` 的类（`item/SequenceUnitPatternItem.java` 等）—— 那是历史遗留；**新东西不要再加**。

**物品 → 缓存的传递**：选定器就放在缓存器的专用槽里，**缓存器每次扫描时直接读 `ItemStack.get(COMPONENT)`**（服务端），**不需要额外同步**。这比「把区域拷进 BE 的 NBT」更稳（区域永远跟着物品走，取出即失效）。

### 3.2 数据模型

```java
/** 一个闭区间方块区域（含首含尾，世界坐标）。 */
public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    // 规范化：构造时 min<=max（swap）
    // 体积 = (dx)*(dy)*(dz)
    // contains(int x,int y,int z)
    // AABB toAabb()  → new AABB(minX, minY, minZ, maxX+1, maxY+1, maxZ+1)
}

/** 选定器的全部数据（一个物品一份）。 */
public record SelectorData(
    ResourceLocation dimension,   // 该选定器绑定的维度；跨维度不生效
    List<Area> areas,             // 0..16
    int selectedIndex             // 当前「选中」的区域下标（-1 = 无）
) {}
```

**为什么带 `dimension`**：区域是世界坐标，主世界的 (100,64,100) 与下界的同名坐标是两回事。绑定维度可以避免「拿着主世界的选定器插进下界的缓存器，吸错地方」。跨维度时的表现：提示「此选定器属于 X 维度」并**忽略全部区域**（等价于不装）。

**Codec 形状**（建议）：

```java
// Area
RecordCodecBuilder.group(Codec.INT.fieldOf("x1")..., "y1", "z1", "x2", "y2", "z2")
// SelectorData
RecordCodecBuilder.group(
    ResourceLocation.CODEC.fieldOf("dim").forGetter(SelectorData::dimension),
    Area.CODEC.listOf().fieldOf("areas").forGetter(SelectorData::areas),
    Codec.INT.optionalFieldOf("sel", -1).forGetter(SelectorData::selectedIndex)
)
```

> 网络侧 `StreamCodec` 的「List 编解码器」具体重载（`ByteBufCodecs.collection(...)` / `list(...)`）以实现时能编译的那个为准（**未证实**：我只确认了 `StreamCodec.composite` + `ByteBufCodecs.VAR_INT/INT/STRING_UTF8` 在本工程可用，见 `SetCollectionRadiusPacket.java:19-26`）。

### 3.3 数量与体积上限（建议值）

| 项 | 建议 | 理由 |
|---|---|---|
| 最多区域数 | **16**（硬上限，Codec 层就拒收更多） | 数据量极小（16 × 24 B = 384 B），真正的限制在扫描预算（§4.3）；16 也够「一个车间 4 个角 + 若干机器群」 |
| 单区域最大体积 | **32 768 格（32×32×32）** | 见 §4.3：这是「1 秒内扫完一遍」与「单区域不失控」的交点 |
| 单区域最小体积 | 1 格 | 允许「只吸一格水」这种极端用法 |
| 单区域最大单边 | 128 格（防止 1×1×100000 这种长条） | 体积上限已经隐含限制，单边长限制是为了 GUI 数字框不溢出、AABB 不至于超出渲染距离 |
| 总区域体积 | 16 × 32768 = 524 288 格 | 由「每 tick 预算 + 游标」摊销，不要求一次扫完 |

> 若用户觉得 16 太多/太少，改一个常量即可（`MAX_AREAS`）。

### 3.4 CRUD 交互（最小可行方案）

**设计原则**：世界里的鼠标手势只管「**点两个角**」，所有列表管理都在**一个 Screen** 里做。这样既能复刻蓝图的肌肉记忆，又不会把「删除第 3 个区域」硬塞进鼠标手势。

#### A. 世界内手势（主手拿区域选定器时）

| 手势 | 行为 | 复刻自 |
|---|---|---|
| **右键方块**（第 1 次） | 记下 A 角（`pendingA`），框实时预览 | 蓝图 `:117-127` |
| **右键方块**（第 2 次） | 记下 B 角 → **新增一个区域**（若已达 16 个则替换当前选中区域）→ `pendingA=null`，自动选中新区域 | 蓝图 `:106-109`（它第 2 次是开命名屏，我们改成提交） |
| **潜行 + 右键方块** | 取消当前未完成的 A 角（`pendingA=null`） | 蓝图 `:101-104`（它潜行右键 = 放弃整个框） |
| **Ctrl + 滚轮** | 沿「视线与框的相交面」推/拉**当前选中区域**（无 A 角时）或**正在框的盒子**（有 A 角时），步长 1，Shift ×5 | 蓝图 `:50-91`；步长叠加照 `RangeChargerScreen.java:99-115`（`hasShiftDown()?5:1`） |
| **潜行 + 右键空气** | 打开「区域列表」界面 | 本工程键位先例：`client/CamouflageKeybinds.java`、`client/TerminalKeybinds.java`（如需改成键位） |

> **注意**：`pendingA`（未完成的第一点）**只在客户端**存在（照蓝图的 `firstPos`），**不落盘**；一旦提交成 `Area` 才写数据组件。这样「选一半跑掉」不会污染数据。

#### B. 「区域列表」界面（`AreaSelectorScreen`，客户端 Screen）

布局建议（照本工程 `CollectionRangeConfigScreen` / `NetworkResourceRequesterScreen` 的既有风格：左侧列表 + 右侧详情 + 底部按钮）：

```
┌─────────────────────────────┬──────────────────────────┐
│ #1  (12,64,-30)~(40,80,5)   │  区域 #2                  │
│ #2  (0,60,0)~(15,75,15)  ◀  │  X1 [   0]  X2 [  15]    │
│ #3  (-8,62,3)~(-1,70,9)     │  Y1 [  60]  Y2 [  75]    │
│ ...（滚动条，16 行）         │  Z1 [   0]  Z2 [  15]    │
│                             │  体积 4096 格             │
│ [新建] [复制] [删除]         │  [定位到此处] [选中在世界上显示] │
└─────────────────────────────┴──────────────────────────┘
```

- **列表行**：`#序号 + min~max + 体积`；点一行 = `selectedIndex` 改为它，并**在世界里用高亮色画它**（「选择区域进行操作」）。
- **新建**：以「玩家当前所在的 1 格」或「准星指的方块」为种子，建一个 1×1×1 区域（这样不依赖世界里的两点手势也能纯 GUI 操作）。
- **复制**：复制选中区域，偏移 +1 格（做「阵列」时省事）。
- **删除**：按钮 + **二次确认**（照 `CollectionDeleteModeConfirmScreen` 的既有确认模式，别做「点了就删」）。
- **6 个数字框**：直接改 min/max（这是「可编辑区域大小、位置」的最精确入口）；越界/超体积时数字变红 + 不提交（照 `CollectionRangeConfigScreen.java:160-180` 对超限的处理）。
- **定位**：把客户端相机的朝向/位置提示出来（或直接显示文字坐标），避免「改了半天不知道在哪」。
- **「选中在世界上显示」**：勾上后**所有**区域常亮显示（不同颜色区分选中/未选中），关掉只显示选中那一个。

#### C. 写入权限（服务端权威）

**客户端绝不直接改 ItemStack 的组件**（会与服务器不同步、且可被作弊）。走本工程既有的 C2S 包模式 —— 照 `network/SetCollectionRadiusPacket.java`（原文节选）：

```java
public record SetCollectionRadiusPacket(int containerId, int axis, int radius) implements CustomPacketPayload {
    public static final Type<SetCollectionRadiusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_radius"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionRadiusPacket> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.VAR_INT, SetCollectionRadiusPacket::containerId, ...);

    public static void handle(final SetCollectionRadiusPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) return;
        cacheMenu.setCollectRadius(packet.axis(), packet.radius());
    }
}
```

**需要的包（建议 1 个即可，别开 6 个）**：

```
AreaSelectorEditPacket(int containerId, int action, int index, Area area)
   action: ADD=0 / REPLACE=1 / DELETE=2 / SELECT=3 / MOVE=4
```

服务端 `handle` 里：校验 `containerId` → 校验玩家仍是该菜单持有者 → 从菜单拿到**主手/槽位里的那个 `ItemStack`** → 改组件 → `menu.broadcastChanges()`（或 `player.getInventory().setChanged()`）让客户端立刻看到。

> **落盘位置优先级**：(a) 若选定器在缓存器的专用槽里 → 改槽里那一个；(b) 若在主手 → 改主手那一个。两个位置都不在 → 丢弃包（防作弊）。

### 3.5 新物品，还是给蓝图加功能？

**建议：新物品**（用户原话「同步添加一个物品」）。理由：

1. 蓝图的 `SchematicAndQuillHandler` 不支持第二个框（§2.5），硬塞要么 fork 它的行为，要么写第二套 handler —— 后者等于新物品。
2. 蓝图物品有自己的用途链（保存/打印/上传服务器，`SchematicExport` / `InstantSchematicPacket`），混入「区域选择」会让右键语义冲突（蓝图右键 = 选点，谁也抢不到）。
3. 新物品可以有自己的耐久/权限/创造版，边界清晰。

命名建议（与既有命名风格一致，全小写 + 下划线）：
- 物品：`rs_create_compat:area_selector`，中文「区域选定器」
- 方块（高级版）：`rs_create_compat:advanced_collection_cache`，中文「高级归流缓存仓」

> 注：用户口述是「高级硅流缓存器」，**中文名建议仍用「高级归流缓存仓」**，与既有 `归流缓存仓` 保持同一词根；若用户坚持「硅流」二字，改 lang 即可（一行）。

---

## 4. 放进缓存器 + 按区域吸收

### 4.1 「把选定器放进缓存器」：槽位怎么做（有先例）

**先例 A（最贴近）：蓝图槽** —— `SchematicLoaderBlockEntity.java:78-83`（原文）

```java
protected final ItemStackHandler blueprintSlot = new ItemStackHandler(1) {
    @Override
    public boolean isItemValid(final int slot, final ItemStack stack) {
        return stack.isEmpty() || isValidBlueprintStack(stack);
    }
};
```

对应菜单槽（`menu/SchematicLoaderMenu.java:87`）：`addSlot(new SlotItemHandler(blueprintHandler, 0, 9, 19) { ... })` —— **普通 `SlotItemHandler` + 覆写 `mayPlace`**，不是升级槽。

**先例 B（不要用）**：`UpgradeSlot`（`menu/UpgradeSlot.java:1-40`）**只接受 RS 原版升级物品名**（`speed_upgrade` / `stack_upgrade` / `range_upgrade` / `autocrafting_upgrade`，见 `:36-37` 的 `DEFAULT_ALLOWED`），还带「单种最多 6 个」的计数逻辑。区域选定器不是升级，塞进去会被 `mayPlace` 挡。

**结论**：
- 高级版新增 **1 个专用槽**：`ItemStackHandler(1)` + `isItemValid` 只放行 `AREA_SELECTOR`；菜单里用**普通 `SlotItemHandler`**（照蓝图槽），空槽绘制提示「放入区域选定器以启用区域限位」（本工程对空槽 tooltip 有明文要求，见 `GUI_FEEDBACK_ROUND3.md:182`）。
- 槽位数量**不要**改成 2 个以上（一个选定器 = 一份区域集合，多放无意义）。
- 取出即失效：BE **不缓存**区域副本，每次扫描都读槽里的 `ItemStack`（避免「取出后还在吸」的幽灵状态）。

### 4.2 按区域吸收的算法（与既有实现逐条对照）

基础版的三种扫描（§1.3）在「区域模式」下改成：

| 来源 | 现在（基础版） | 区域模式（高级版） |
|---|---|---|
| 掉落物 | `AABB.inflate(rX,rY,rZ)` + `getEntitiesOfClass` | **先取所有区域 AABB 的并集** 做一次 `getEntitiesOfClass`，再用 `areas.stream().anyMatch(a -> a.contains(pos))` **过滤**；再与 XYZ 盒子取交集 |
| 经验球 | 同上 | 同上 |
| 流体源 | `BlockPos.betweenClosed(立方体)` 4096 上限 | **只遍历区域内的坐标**；用**跨 tick 游标**续扫（见 §4.3） |
| 气体 | 空实现 | 保持空实现 |
| 相邻容器 | 按选中面抽 | **不变**（区域不参与：相邻容器是「贴着机器」的，不是「范围内的」） |

**为什么并集查询 + 过滤，而不是「每个区域查一次」**：`getEntitiesOfClass` 的代价主要在遍历区块内的实体表；16 次查询 = 16 倍。并集 1 次 + 纯算术过滤，几乎免费。

**「交集」的具体写法**（推荐）：

```
boolean accept(BlockPos p) {
    return inXyzBox(p)                       // 既有 XYZ 范围（基础版语义，不动）
        && (areas.isEmpty() || areas.stream().anyMatch(a -> a.contains(p)));   // 区域限位
}
```

`areas.isEmpty()` 时**退化为基础版行为** —— 这保证「不放选定器 = 和现在一模一样」，不破坏既有存档与手感。

### 4.3 上限与性能（含一个既有实现缺陷的发现）

#### 先算清楚现有代价

| 量 | 数值 | 出处 |
|---|---|---|
| 默认扫描半径 | 5 格（三轴） | `Config.java:101-103` |
| 默认半径下的立方体 | 11³ = **1 331** 格 | 2r+1 |
| 半径上限（无升级） | 25 格 | `CollectionCacheBlockEntity.java:112` |
| 半径 25 的立方体 | 51³ = **132 651** 格 | —— |
| 一个范围升级后（50 格） | 101³ = **1 030 301** 格 | `:385` |
| 流体单次扫描上限 | **4 096** 格 | `:118` `MAX_FLUID_BLOCKS_PER_SCAN` |
| 默认扫描间隔 | 20 tick（1 秒） | `Config.java:98-99` |

**⚠️ 已证实的既有缺陷（建议新版顺手修掉）**：流体扫描是

```java
// :1959-1969
final Iterable<BlockPos> positions = BlockPos.betweenClosed(
    worldPosition.offset(-rx, -ry, -rz),
    worldPosition.offset(rx, ry, rz));
for (final BlockPos pos : positions) {
    if (sources.size() >= MAX_FLUID_BLOCKS_PER_SCAN) break;
    ...
}
```

`betweenClosed` **每次迭代都从最小角开始**（同样的顺序），而 `break` 在 4096 就停 —— 于是**体积 > 4096 时，超出部分永远扫不到**：半径 25（132 651 格）只覆盖前 **3.1%**；一个范围升级后（1 030 301 格）只覆盖 **0.4%**。「范围升级到 50」实际上**没有让流体收得更远，只是让同一小块被更频繁地扫**。这是既有行为，**不是本轮引入**；区域模式正好借机换成游标。

#### 建议的预算模型（给数值 + 理由）

| 参数 | 建议值 | 理由（可复核） |
|---|---|---|
| 每 tick 方块 `getBlockState` 总预算 | **2 048 次 / 每台缓存器** | 既有实现单次就允许 4 096 次（`:118`），且是 **20 tick 一次**；摊到每 tick 只用了 205 次。取 2 048 = **10 倍**既有平均负载，仍然是「每 tick 一个区块段量级」的廉价操作 |
| 游标 | **持久化到 NBT**（区域下标 + 区内线性下标） | 不持久化 = 每次重登都从头发扫，大区域永远只扫前面 |
| 单区域体积上限 | **32 768 格（32³）** | 32³ 在 2 048/tick 下 **16 tick** 扫完一遍，正好落在默认 20 tick 的扫描节奏内 ⇒ 「每秒至少完整扫一遍」 |
| 区域数上限 | **16** | 16 × 32 768 = 524 288 格；最坏 **256 tick（12.8 秒）** 全扫一遍。这是「多个区域」的合理成本上限；再多建议玩家合并区域 |
| 每 tick 处理的区域数 | **1 个（轮转）** | 「每 tick 只处理 1 个区域」比「每 tick 每个区域各扫一点」实现简单、单帧抖动小；轮转让每个区域的延迟接近平均 |
| 实体查询频率 | 跟随 `getScanInterval()`（不变） | 实体查询走 AABB + 区块实体表，代价远小于方块遍历；不需要额外限流 |
| 每区域「方块实体」扫描 | **v1 不做** | 现在基础版根本**不按范围扫方块实体**（只扫 6 个相邻面，`:1296-1298`）。新增「区域内所有容器」是**全新成本**（每格一次 `level.getBlockEntity`），且与「限位吸掉落物/流体」的需求无关。若以后要做，也在 2 048 预算内顺带做（只对**已经是流体源/已被遍历到**的坐标查 BE），不额外开一条遍历 |

> 数值都是**建议**，不是实测；实现后用 `RsccDiag`（工程既有诊断通道，`support/RsccDiag.java`）打点验证。

#### 与「每 tick 只处理 1 个区域」配套的伪代码

```
void absorbByAreas(Level level) {
    List<Area> areas = readAreasFromSlot();          // 空 ⇒ 走老路径
    int budget = 2048;
    for (int n = 0; n < areas.size() && budget > 0; n++) {
        Area a = areas.get(cursorArea);
        budget -= scanAreaChunk(level, a, budget);   // 从 cursorPos 续扫，返回实际消耗
        advanceCursor(a);                            // 扫完该区域 ⇒ cursorArea++，cursorPos 归零
    }
}
```

### 4.4 与既有 XYZ 范围吸收的关系：**交集**（建议）

用户原话：「作用是增加『**限位**』功能，避免普通 XYZ 范围吸收到不想收的物品。」

- 「限位」= **限制在已有范围之内** ⇒ **交集（AND）**，而不是替代。
- 交集的好处：
  1. 基础版语义、范围升级、耗电公式（`Config.collectionCacheEnergyPerRadius`）**全部不变**；
  2. 「不放选定器 = 完全没变化」，零回归风险；
  3. 玩家心智简单：「XYZ 是**最大**范围，区域是**允许**的形状」。
- 交集的坑（必须提示）：**区域画在 XYZ 之外 = 一点都吸不到**。所以：
  - GUI 里显示「区域体积 / 与 XYZ 相交体积」两个数（相交为 0 时红字警告）；
  - 世界里画区域框时，**同时画 XYZ 框**（不同颜色），一眼看出「谁在谁外面」。
- 给一个 config 逃生口：`collectionCacheAreaMode = INTERSECT | AREA_ONLY`（默认 `INTERSECT`）。`AREA_ONLY` 给「我就想要一个 1 格水坑，别管 XYZ」的玩家。

### 4.5 坐标系：绝对坐标（建议），并说明代价

| 方案 | 优点 | 缺点 |
|---|---|---|
| **绝对坐标（推荐）** | 玩家心里想的是「那台机器那片地」；GUI 里直接显示世界坐标，可与 F3、Jade、`/tp` 对照；物品拿走插到另一台缓存器上仍然指向「同一片地」 | 缓存器被活塞/结构方块搬走 ⇒ 区域不跟随（**语义正确**：区域是「地点」，不是「机器的属性」） |
| 相对缓存器 | 搬机器时区域跟着走 | 玩家看到的数字是「+3 格」而不是世界坐标，极难核对；同一个选定器插到另一台缓存器上会指向**完全不同的地方**（反直觉） |

**结论**：**绝对坐标 + 绑定维度**（§3.2）。补充规则：

- 区域与缓存器**不在同一维度** ⇒ 忽略（并在 GUI 提示）。
- 区域**距离缓存器极远**（如 > 256 格）⇒ 允许，但**标黄提示**（性能：距离本身不影响成本，成本只与体积有关；但跨区块加载会失败，实际吸不到）。
- 缓存器被搬走 ⇒ 区域不变（玩家要么重新插选定器重选，要么把机器搬回去）。

---

## 5. 充电范围显示（需求十五，独立且小）

### 5.1 现状：范围存在哪、怎么同步（先纠正一个坑）

**存在方块实体字段里，不是 Config**：

```java
// RangeChargerBlockEntity.java:63-65
private int rangeX = 50;
private int rangeY = 50;
private int rangeZ = 50;
```

**上限**（`:292-297`）：

```java
// :292-297
public int getMaxRange() {
    return hasInfiniteRange() ? Integer.MAX_VALUE - 1
        : BASE_MAX_RANGE + getRangeUpgradeCount() * Config.rangeChargerRangePerUpgrade;
}
```
（`BASE_MAX_RANGE = 100`，`:49`；`Config.rangeChargerRangePerUpgrade` 默认 25，`Config.java:54-55`）

**怎么到客户端**：`ContainerData`（`:305-345`），槽位 **0/1/2 = X/Y/Z**，`getCount() = 12`。客户端读 `menu.getRangeX()`（`RangeChargerMenu.java:89-99`）。

**⚠️ 关键语义：`rangeX` 是「直径」，不是「半径」**：

```java
// RangeChargerBlockEntity.java:695-704（scanPlayers）
final int halfX = rangeX / 2;
final int halfY = rangeY / 2;
final int halfZ = rangeZ / 2;
final AABB box = new AABB(
    worldPosition.getX() - halfX, worldPosition.getY() - halfY, worldPosition.getZ() - halfZ,
    worldPosition.getX() + halfX + 1, worldPosition.getY() + halfY + 1, worldPosition.getZ() + halfZ + 1
);
```

`scanItems`（`:876-885`）与 `scanBlocks`（`:808` 起）用的是**同一套 `half` + `+1`**。

**⇒ 画出来的框必须用同一个公式**，否则「看到的框」与「真正充电的范围」差一倍，这个按钮就白做了。

### 5.2 按钮放哪（有现成布局依据）

`client/screen/RangeChargerScreen.java` 的已知占用（原文常量）：

| 区域 | 坐标 | 出处 |
|---|---|---|
| 面板尺寸 | `210 × 166` | `:50-53`（`imageWidth=210` 含右侧 34px 插件栏）|
| 能量条 | `(26,20)` `16×51` | `:57-61` |
| X/Y/Z 行 | y = 20 / 37 / 54；`[-]`@x=64、输入框@x=86..126、`[+]`@x=128 | `:64-67, 82, 99, 106` |
| 升级栏标签 | x=168, y=4 | `:32-35` |
| 升级槽 | x ≥ 187 | `:35`（`UPGRADES_LABEL_MAX_W = 187 - 168`）|
| 底部文字行 | y=72，目标数@x=8、速率@x=96 | `:41-45` |

**空位（按上表常量推算，未做像素级核对）**：x≈150..186、y≈20..70（三行 `[+]` 右侧、升级槽左侧、底部文字行之上）。建议放一个 **`20×14` 的小按钮**在 `(leftPos+152, topPos+20)`（与 XYZ 三行同高，视觉上属于「范围」这一组），文案「查看」。

### 5.3 怎么画：复用 Create 的框（推荐）还是自己画

| 方案 | 代码量 | 观感 | 风险 | 结论 |
|---|---|---|---|---|
| **A. `Outliner.showAABB(slot, box, ttl)`** | ~5 行调用 | 与蓝图**完全同款**（同样的线宽、同样的棋盘面、同样的消散动画） | 依赖 catnip（Create 硬依赖，§2.2）| ✅ **推荐**（用户要的就是「蓝图那种框，换个色」） |
| B. 本工程自绘 `CableCutPreviewOverlay.drawBoxes(...)` | 1 行调用，**零新依赖** | 半透明色块 + `LevelRenderer.renderLineBox` 描边（更「实」） | 无 | ✅ 备选（若不想碰 catnip） |
| C. 纯原版 `LevelRenderer.renderLineBox` | ~30 行 | 只有线，没有面 | 无 | 最保守，但观感与蓝图不一致 |

**A 的原文（`catnip/outliner/Outliner.java:68-80`）**

```java
public OutlineParams showAABB(Object slot, AABB bb, int ttl) {
    createAABBOutlineIfMissing(slot, bb);
    ChasingAABBOutline outline = getAndRefreshAABB(slot, ttl);
    outline.prevBB = outline.targetBB = outline.bb = bb;
    return outline.getParams();
}
```

**TTL 语义（决定「显示多久」）**（`Outliner.java:182-211`）

```java
public static class OutlineEntry {
    public static final int FADE_TICKS = 8;
    private int ticksTillRemoval = 1;
    public boolean isAlive() { return ticksTillRemoval >= -FADE_TICKS; }
    public boolean isFading() { return ticksTillRemoval < 0; }
    public void tick() { ticksTillRemoval--; outline.tick(); }
}
```

=> `showAABB(slot, box, 100)` 调用**一次**：可见 100 tick（5 秒）+ 8 tick 淡出 ≈ **5.4 秒**后自动消失。**不需要**每 tick 重新调用（每 tick 调用反而会卡住不消失）。

**B 的原文（本工程，已被抽成公共 API）** —— `client/CableCutPreviewOverlay.java:253, 292`：

```java
public static void drawBoxes(final PoseStack poseStack, final MultiBufferSource.BufferSource buffers,
                            final Vec3 camera, final java.util.Collection<BlockPos> cells,
                            final VoxelShape shape, final float[] color, final float alpha,
                            final boolean outlineWholeCell) {
    ...
    LevelRenderer.renderLineBox(poseStack, lines, box, color[0], color[1], color[2], Math.min(1.0F, alpha + 0.5F));
}
```

它的类注释写明了「本类与批 3 的『可达区域』叠加层共用这唯一一份实现」（`:227-233`），也就是说**本工程已经有「复用同一份世界叠加层绘制」的先例**。

> 注意：B 方案是 **逐格**填色（`cells` 是 `Collection<BlockPos>`），画 51³ = 132 651 格会**炸**。B 只适合「少量格子」。**画一个巨大的范围框必须用 A（一个 AABB）**。这是选 A 的硬理由。
> 若要用 B 画范围，只能改成「画 12 条边」的变体（不在现有 `drawBoxes` 能力内）⇒ 又回到自己写。

### 5.4 颜色

| 用途 | 建议颜色 | 说明 |
|---|---|---|
| 蓝图框（要避开） | `0x6886c5`（蓝紫） | §2.3 |
| **充电范围** | **`0xFF9F1C`（橙）** | 与蓝紫在色相上几乎互补，最容易一眼区分；且橙色不常用于「危险/失败」提示（红留给「删除/禁止」） |
| 区域选定器 · 已选中区域 | `0x35E06A`（绿） | 「这个在生效」 |
| 区域选定器 · 未选中区域 | `0x9AA0A6`（灰） | 弱化 |
| 区域选定器 · 正在框（未提交） | `0xFFD60A`（黄） | 「还没定」 |

**alpha 说明（重要）**：`colored(int)` **不含 alpha**（`Outline.java:538-541`），实际透明度由 `params.alpha`（渲染时恒 1，消散时淡出）× 面贴图 0.5 决定（`AABBOutline.java:97-99`）。所以「alpha 0.4 的橙色」在 A 方案里**做不到**；只有 B 方案（自绘）能精确控 alpha（`ALPHA = 0.35F`，`CableCutPreviewOverlay.java:57`）。

> 结论：**要「蓝图同款观感 + 明显不同的颜色」⇒ A 方案 + `0xFF9F1C`**（不纠结 alpha，靠色相区分）。
> **要「精确 alpha」⇒ B 方案**，但只能小范围逐格画。

### 5.5 显示多久 / 怎么关

| 方案 | 实现 | 评价 |
|---|---|---|
| **点击 → 5 秒自动消失**（推荐） | `showAABB(slot, box, 100)`，不重复调用 | 最省事；`FADE_TICKS=8` 自带淡出，观感好 |
| 再点一次关 | 客户端 boolean，点了 `Outliner.remove(slot)` | 与「5 秒」可共存：点开 = 重设 ttl，再点 = 立即 remove |
| 离开界面自动关 | `Screen.onClose()` 里 `Outliner.remove(slot)` | ~~建议**保留**~~ **已按用户实机反馈推翻**（见下方修正） |

> **2026-10-25 修正（第 95 轮，用户实机反馈）**：这一行原来的「建议保留」是**错的**。实机后果是
> 「点一下『显示范围』再把界面关掉，世界里的框一定看不见」—— 而玩家的心智模型正是「点一下 → 关 GUI → 去世界里看」。
> 充电器、高级归流缓存仓、区域选定器三个界面因此统一改为：**关界面不立刻 remove，而是按剩余 TTL
> 重新武装一次**，让框自己画完 5 秒再淡出；「显示中再点一次」仍然是立即 `remove`。
> 另：只有 1/32 细线、没有面贴图时，GUI 开着（世界被菜单背景压暗）几乎看不见，所以统一补
> `withFaceTextures(CHECKERED, HIGHLIGHT_CHECKERED)` + 1/16 线宽（与 Create 蓝图框同款）。
> 证据与反例见 `tools/selfcheck_round95_range_charger.py`（⑦d 就是「removed() 里立刻清框」这个旧行为判负）。

**建议组合（已按上面的修正更新）**：点一下 = 显示 5 秒（`ttl=100`，窗口内每 tick 重喂）+ 再点一下 = 立即 `remove` + 关界面 = **按剩余 TTL 重新武装，不立刻 `remove`**。

**服务端要不要参与？** **不要**。这是纯客户端视觉，客户端从 `menu.getRangeX/Y/Z()` + `menu.getChargerPos()` 就够（`RangeChargerMenu.java:185-187` 已暴露方块坐标）。零发包、零服务端改动、零存档改动 —— 这是本需求「最小方案」的核心。

---

## 6. 未证实 / 需用户拍板

### 6.1 未证实（我拿不到证据，或证据不足）

1. **「硅流缓存器」的用户本意**：我按**同音 + 本仓库自己的文档**判定为「归流缓存仓」（§1.2，证据很强），但**用户没有直接确认过**。若用户其实指的是别的机器（例如 RS 的「磁盘仓」或「中间产物缓存仓」），整份 §4 的目标方块要换。
2. **`StreamCodec` 的 List 编解码器具体重载**：本工程只用过 `ByteBufCodecs.VAR_INT/INT/STRING_UTF8`（`SetCollectionRadiusPacket.java:19-26`），**List 版没在本工程出现过**，我没查 MC/NeoForge 源码里的确切方法名 ⇒ 实现时以能编译的重载为准。
3. **`Outliner` 在「Ponder 被禁用/被替换」时的行为**：我只确认了「Create 6.0.10 硬依赖 ponder」（`neoforge.mods.toml`），没验证「玩家能否单独禁用 ponder」。若可以，A 方案会静默不显示（不崩，因为是别人 tick 的）⇒ 需要 §2.6 的 try/catch + 降级。
4. **32³ / 2048 / 16 这三个建议值没有实测**：是从既有 `MAX_FLUID_BLOCKS_PER_SCAN = 4096` 与默认 20 tick 间隔推出来的量级，**不是 benchmark 结果**。
5. **`Outliner` 的渲染色在光影（Iris/Oculus）下是否仍然可见**：未验证。工程里 `CableCutPreviewOverlay`（`RenderType.debugFilledBox` + `lines`）是已知可用的，但那是 B 方案。
6. **数据组件里存 `List<Area>` 时的「物品堆叠」影响**：`RAW_MATERIAL` 的先例说明「同标记可堆叠」（`RS_Create_Compat.java:106-110` 注释）；但区域列表**几乎必然每个都不同** ⇒ 选定器会变成**不可堆叠**物品（每份数据不同）。这不是 bug，但要在 tooltip 里说清楚（避免玩家以为坏了）。
7. **`RangeChargerMenu.getChargerPos()` 在客户端是否恒非空**：源码是 `charger != null ? charger.getBlockPos() : null`（`RangeChargerMenu.java:185-187`），**客户端 `charger` 是否为空我没查**。若不空，按钮逻辑极简；若可能为空，需要把坐标塞进 `ContainerData`（但那只有 12 槽，得加槽）⇒ **实现前必须确认这一条**（这是 §5 唯一的硬阻塞点）。

### 6.2 需用户拍板（按影响排序）

1. **「硅流缓存器」= 归流缓存仓？**（§1.2）—— 若不是，请指出是哪个方块。
2. **高级版 = 「归流缓存仓 + 区域槽」还是「数值更强的归流缓存仓 + 区域槽」？** 用户只说「更高级版本」，没说要更大缓存/更快回流。建议：**先只加区域能力**（缓存格数、吞吐、耗电全沿用基础版），避免一次改太多。
3. **区域上限**：16 个够吗？单区域 32³（32 768 格）够吗？（§3.3）
4. **与 XYZ 的关系**：确认「交集」（区域画在 XYZ 外 = 吸不到）是用户想要的「限位」，还是想要「区域一旦存在就完全取代 XYZ」？（§4.4，建议交集 + config 开关）
5. **坐标系**：绝对坐标（建议）还是相对缓存器？（§4.5）
6. **`AREA_ONLY` 模式要不要做**（config 开关）？做 = 多一条分支 + 多一组测试。
7. **「查看充电范围」按钮的目标界面**：我判定是 **`RangeChargerScreen`**（它的 X/Y/Z 就是充电范围）。但 `CollectionRangeConfigScreen`（归流缓存仓的范围子界面）也有 X/Y/Z —— 要不要**两个界面都加**这个按钮？（§5.1；用户原话「当前 XYZ 范围包括充电范围」更像指充电器）
8. **显示时长**：5 秒 / 再点关 / 离开界面关（三项都做？）（§5.5）
9. **颜色**：橙 `0xFF9F1C` 可以吗？（§5.4）
10. **中文名**：方块叫「高级归流缓存仓」还是照用户口述叫「高级硅流缓存器」？（§3.5 注）

---

## 附录 A：本工程证据索引（可直接点开核对）

| 主题 | 文件:行 | 内容 |
|---|---|---|
| 归流缓存仓名字 | `src/main/resources/assets/rs_create_compat/lang/zh_cn.json` | `block.rs_create_compat.collection_cache = 归流缓存仓` |
| 用户同音先例 | `docs/GUI_FEEDBACK_ROUND3.md:181-182` | 「归流缓存仓」与「硅流缓存仓」同句混用 |
| 归流缓存仓半径字段 | `block/entity/CollectionCacheBlockEntity.java:218-220` | `radiusX/Y/Z = Config.collectionCacheScanRadius` |
| 半径上下限 | 同上 `:110-112` | `MIN=1`、`MAX=25` |
| 范围升级加成 | 同上 `:380-388` | `MAX + n × Config.rangeChargerRangePerUpgrade` |
| 单次流体扫描上限 | 同上 `:118` | `MAX_FLUID_BLOCKS_PER_SCAN = 4096` |
| 掉落物扫描 | 同上 `:1850-1858` | `new AABB(worldPosition).inflate(...)` |
| 经验球扫描 | 同上 `:1712-1717` | 同上口径 |
| 流体源扫描（含缺陷） | 同上 `:1955-1971` | `BlockPos.betweenClosed` + `break` |
| 气体空实现 | 同上 `:1607-1611` | 注释「预留入口」 |
| 扫描节流 | 同上 `:440-461` | `getScanInterval()` |
| 配置默认 | `Config.java:98-103` | 间隔 20 tick、半径 5（1..64）|
| 充电器范围字段 | `block/entity/RangeChargerBlockEntity.java:63-65` | `rangeX/Y/Z = 50` |
| 充电器范围上限 | 同上 `:49, 292-296` | `BASE_MAX_RANGE=100` + 25×n |
| 充电器数据同步 | 同上 `:305-345` | `ContainerData` 0/1/2 = X/Y/Z |
| **范围=直径** | 同上 `:695-704, 876-885` | `halfX = rangeX/2` |
| 充电器方块坐标 | `menu/RangeChargerMenu.java:185-187` | `getChargerPos()` |
| 充电器界面布局 | `client/screen/RangeChargerScreen.java:20-21, 50-67` | 210×166、能量条、XYZ 行 |
| 世界叠加层公共 API | `client/CableCutPreviewOverlay.java:253, 292` | `drawBoxes(...)` + `renderLineBox` |
| 世界叠加层注册方式 | 同上 `:67-69` | `@EventBusSubscriber(Dist.CLIENT)` |
| 数据组件注册 | `RS_Create_Compat.java:96-110` | `registerComponentType` ×2 |
| record + 双 Codec | `support/SequenceMaterialGuard.java:61-71` | `RawMaterialMark` |
| 蓝图槽先例 | `block/entity/SchematicLoaderBlockEntity.java:78-83` | `ItemStackHandler(1) + isItemValid` |
| Create 内部反射先例 | 同上 `:1439-1462` | `getDeclaredField("blockReader")` + 失败降级 |
| 高级版继承先例 | `block/entity/AdvancedSchematicLoaderBlockEntity.java:18` | `extends SchematicLoaderBlockEntity` |
| 升级槽（不适用） | `menu/UpgradeSlot.java:36-37` | 仅 RS 原版升级名 |
| C2S 包先例 | `network/SetCollectionRadiusPacket.java:19-45` | `Type` + `StreamCodec` + `handle` |
| 依赖规矩 | `docs/dependency-policy.md:59-70` | 接入类无静态副作用、缺失=功能不出现 |

## 附录 B：Create / Catnip 证据索引

> `create/...` = `create-1.21.1-6.0.10-280-sources.jar` 内路径；`catnip/...` = `ponder-neoforge-1.0.82+mc1.21.1-sources.jar` 内路径。

| 主题 | 路径:行 | 内容 |
|---|---|---|
| 笔物品**没有**框选逻辑 | `create/com/simibubi/create/content/schematics/SchematicAndQuillItem.java:23-27` | 只有构造器 + 静态工具 |
| 框选状态（内存） | `create/.../client/SchematicAndQuillHandler.java:42-48` | `firstPos/secondPos/selectedPos/range` |
| 滚轮改大小 | 同上 `:50-91` | Ctrl+滚轮、`expandTowards` |
| 右键两点 | 同上 `:93-128` | `button != 1` 返回 false |
| 射线取点 | 同上 `:138-164` | `RaycastHelper.rayTraceRange` |
| **框的渲染与颜色** | 同上 `:180-186` | `.colored(0x6886c5)` + CHECKERED + `1/16f` |
| 框的构造 | 同上 `:189-196` | `new AABB(...).expandTowards(1,1,1)` |
| **硬编码主手物品** | 同上 `:198-200` | `AllItems.SCHEMATIC_AND_QUILL.isIn(...)` |
| 事件接线 | `create/.../foundation/events/InputEvents.java:46-72` | `MouseScrollingEvent` / `MouseButton.Pre` |
| 每 tick 驱动 | `create/.../foundation/events/ClientEvents.java:148` | `SCHEMATIC_AND_QUILL_HANDLER.tick()` |
| 已放置蓝图外框 | `create/.../client/SchematicHandler.java:357-372` | `0x6886c5` + `SCHEMATIC_BOUNDS` |
| 蓝图工具框 | `create/.../client/tools/SchematicToolBase.java:139-142` | `0x6886c5` |
| 镜像/旋转手柄色 | `create/.../client/tools/FlipTool.java:76`、`RotateTool.java:36` | `0xdddddd` |
| 面贴图枚举 | `create/com/simibubi/create/AllSpecialTextures.java:6-22` | `CHECKERED` / `HIGHLIGHT_CHECKERED` |
| 数据组件定义 | `create/com/simibubi/create/AllDataComponents.java:170-205` | `SCHEMATIC_BOUNDS(Vec3i)` 等 |
| Outliner `chaseAABB` | `catnip/net/createmod/catnip/outliner/Outliner.java:82-87` | 见 §2.2 |
| Outliner `showAABB(ttl)` | 同上 `:68-80` | 见 §5.3 |
| Outliner tick/render | 同上 `:151-180` | `tickOutlines` / `renderOutlines` |
| TTL / 淡出 | 同上 `:182-211` | `FADE_TICKS = 8` |
| `colored(int)` 无 alpha | `catnip/net/createmod/catnip/outliner/Outline.java:538-541, 599-601` | `new Color(color, false)` |
| 面 alpha × 0.5 | `catnip/net/createmod/catnip/outliner/AABBOutline.java:97-99` | 见 §2.3 |
| 无贴图 ⇒ 不画面 | 同上 `:90-92` | `if (faceTexture == null) return;` |
| Ponder 渲染世界 | `catnip/../ponder/net/createmod/ponder/NeoForgePonderClient.java:52-66` | `AFTER_PARTICLES` |
| Ponder tick | `ponder/net/createmod/ponder/PonderClient.java:50-59` | `tickOutlines()` |
| Create 依赖 ponder | `create-1.21.1-6.0.10-280-slim.jar!/META-INF/neoforge.mods.toml` | `modId="ponder" type="required"` |

### 附录 B.1 `javap` 原文（`Outliner` / `OutlineParams` / `AABBOutline`）

```
$ javap -classpath ponder-neoforge-1.0.82+mc1.21.1.jar net.createmod.catnip.outliner.Outliner
public class net.createmod.catnip.outliner.Outliner {
  public static net.createmod.catnip.outliner.Outliner getInstance();
  public net.createmod.catnip.outliner.Outline$OutlineParams showOutline(java.lang.Object, net.createmod.catnip.outliner.Outline);
  public net.createmod.catnip.outliner.Outline$OutlineParams showLine(java.lang.Object, net.minecraft.world.phys.Vec3, net.minecraft.world.phys.Vec3);
  public net.createmod.catnip.outliner.Outline$OutlineParams endChasingLine(java.lang.Object, net.minecraft.world.phys.Vec3, net.minecraft.world.phys.Vec3, float, boolean);
  public net.createmod.catnip.outliner.Outline$OutlineParams showAABB(java.lang.Object, net.minecraft.world.phys.AABB, int);
  public net.createmod.catnip.outliner.Outline$OutlineParams showAABB(java.lang.Object, net.minecraft.world.phys.AABB);
  public net.createmod.catnip.outliner.Outline$OutlineParams chaseAABB(java.lang.Object, net.minecraft.world.phys.AABB);
  public net.createmod.catnip.outliner.Outline$OutlineParams showCluster(java.lang.Object, java.lang.Iterable<net.minecraft.core.BlockPos>);
  public net.createmod.catnip.outliner.Outline$OutlineParams showItem(java.lang.Object, net.minecraft.world.phys.Vec3, net.minecraft.world.item.ItemStack);
  public void keep(java.lang.Object);
  public void remove(java.lang.Object);
  public java.util.Optional<net.createmod.catnip.outliner.Outline$OutlineParams> edit(java.lang.Object);
  public void tickOutlines();
  public void renderOutlines(com.mojang.blaze3d.vertex.PoseStack, net.createmod.catnip.render.SuperRenderTypeBuffer, net.minecraft.world.phys.Vec3, float);
}

$ javap -classpath ... net.createmod.catnip.outliner.Outline$OutlineParams
public class net.createmod.catnip.outliner.Outline$OutlineParams {
  public net.createmod.catnip.outliner.Outline$OutlineParams colored(int);
  public net.createmod.catnip.outliner.Outline$OutlineParams colored(net.createmod.catnip.theme.Color);
  public net.createmod.catnip.outliner.Outline$OutlineParams lightmap(int);
  public net.createmod.catnip.outliner.Outline$OutlineParams lineWidth(float);
  public net.createmod.catnip.outliner.Outline$OutlineParams withFaceTexture(net.createmod.catnip.render.BindableTexture);
  public net.createmod.catnip.outliner.Outline$OutlineParams clearTextures();
  public net.createmod.catnip.outliner.Outline$OutlineParams withFaceTextures(net.createmod.catnip.render.BindableTexture, net.createmod.catnip.render.BindableTexture);
  public net.createmod.catnip.outliner.Outline$OutlineParams highlightFace(net.minecraft.core.Direction);
  public net.createmod.catnip.outliner.Outline$OutlineParams disableLineNormals();
  public net.createmod.catnip.outliner.Outline$OutlineParams disableCull();
  public float getLineWidth();
  public net.minecraft.core.Direction getHighlightedFace();
  public void loadColor(org.joml.Vector4f);
}
```

---

## 附录 C：本次调研的边界（没有做什么）

- **没有改任何 `.java`**；**没有碰 `run/`**；**没有解压任何 jar**（全部 `zipfile` 直读）。
- 新增文件只有两个：
  - 本设计文档 `docs/AREA_SELECTOR_AND_CHARGE_RANGE.md`
  - 临时读取脚本 `tmp_dump_java.py`（**可删**，仅用于从 jar 里按行号打印源码）
- 未新增任何二进制/大文件；所有写操作都在 `D:` 盘。
