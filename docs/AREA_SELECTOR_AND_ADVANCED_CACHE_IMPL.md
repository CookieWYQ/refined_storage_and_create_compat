# 区域选定器 + 高级归流缓存仓 —— 实现说明（round 90）

> 配套调研：`docs/AREA_SELECTOR_AND_CHARGE_RANGE.md`（API 证据）。本文只写**已落地的东西**：
> 怎么用、数据长什么样、性能上限在哪、哪些是刻意的取舍。
> 版本：MC 1.21.1 / NeoForge / RS 2.0.9 / Create 6.0.10。

## 0. 用户拍板的 8 条（本文逐条对应）

| # | 结论 | 落地位置 |
|---|---|---|
| 1 | 「硅流缓存器」= 归流缓存仓 ⇒ 高级版是它的高级版 | `AdvancedCollectionCacheBlockEntity extends CollectionCacheBlockEntity` |
| 2 | 缓存容量更大、**吞吐不变** | 512 格 / 1 024 000 mB；`getTransferBatch` / `getProcessRate` 一个字都没改 |
| 3 | 区域与 XYZ = **只用区域（取代）** | `areaAbsorptionBlocked()`：没有有效区域就**整段不吸**，绝不退回半径 |
| 4 | **绝对坐标 + 绑定维度** | `Area(minX..maxZ)` 世界坐标；`AreaSelection.dimension` |
| 5 | 区域上限可配置（两个 int） | `Config.areaSelectorMaxAreas`（16）、`Config.areaSelectorMaxVolume`（32768） |
| 6 | 所有显示范围的界面都要有「查看范围」 | 高级仓界面 `range_button`；充电器（上一轮）同款 |
| 7 | 所有范围显示统一专属色，不与蓝图蓝撞 | `client/AreaOutline.COLOR = 0xFF9F1C`；蓝图为 `0x6886c5` |
| 8 | 显示时长默认 | `AreaOutline.VIEW_TTL = 100` tick = 5 秒 + Outliner 自带 8 tick 淡出 |

## 1. 区域选定器（`rs_create_compat:area_selector`）

### 1.1 数据（注册版数据组件 `area_selection`）

```
AreaSelection {
  dimension: ResourceLocation?      // null = 还没绑定（第一次落笔时由服务端绑定到玩家当前维度）
  areas: List<Area>                 // 0..Config.areaSelectorMaxAreas
  selectedIndex: int                // -1 = 无选中
}
Area { minX,minY,minZ,maxX,maxY,maxZ }   // 闭区间，构造时自动 swap 保证 min<=max
```

- **持久化 + 同步**：`persistent(AreaSelection.CODEC)` + `networkSynchronized(AreaSelection.STREAM_CODEC)`
  （照工程既有 `RAW_MATERIAL` 的 record 双 Codec 先例，不用 `CUSTOM_DATA`）。
- **不可堆叠**：`stacksTo(1)`（区域表不同的两份选定器不是同一个物品；堆叠会让玩家以为只生效一份）。
- **上限不是写死的**：`AreaSelection.sanitize(...)` 在**读档 / 收包 / 扫描**三处都按 Config 截断；
  绝对硬上限（64 个区域、单边 128 格）只用来拦恶意 / 手改数据。

### 1.2 世界内手势（`client/AreaSelectorHandler`）

主手拿着选定器时（界面关闭状态下）：

| 手势 | 行为 |
|---|---|
| **右键方块（第 1 次）** | 记下 A 角，橙色预览框实时跟随准星 |
| **右键方块（第 2 次）** | 以 B 角**提交一个新区域**（追加并自动选中），回到「等 A 角」 |
| **潜行 + 右键方块** | 放弃当前未完成的 A 角（不写任何数据） |
| **潜行 + 右键空气** | 打开「区域列表」界面 |
| **Ctrl + 滚轮（有 A 角）** | 沿**视线主轴**推 / 拉正在框的盒子（Shift ×5） |
| **Ctrl + 滚轮（无 A 角）** | 沿视线主轴调整**当前选中区域**的大小（Shift ×5） |

- 事件走 **`InputEvent.MouseButton.Pre` / `InputEvent.MouseScrollingEvent`**，命中即
  `event.setCanceled(true)`（与 Create 的框选同一条链路；**不是** `UseOnContext`，也**不是**方块交互）。
- **没有复用** Create 的 `SchematicAndQuillHandler`：它 `isActive()` 私有且硬编码主手物品、
  只有一组 `firstPos/secondPos`。这里按同样的套路自写（右键两点 + Ctrl 滚轮 + Outliner + 只存客户端内存）。
- **未提交的 A 角只在客户端内存**：选一半跑掉 / 开界面 / 换物品都不污染物品数据。
- **射线用原版 `Minecraft.hitResult`**，不碰 Create 的 `RaycastHelper`；**不引用 `AllKeys`**。
- **渲染只喂 Create 的 `Outliner`**（Ponder 已在 `AFTER_PARTICLES` 渲染 + 每 tick `tickOutlines`），
  本模组**不挂任何渲染事件**；所有 Outliner 调用包 `try/catch(Throwable)`，失败一次即降级为「不画框」。
- 拿在手上时**已提交的区域常亮**（方便边看边画）。

### 1.3 区域列表界面（`client/screen/AreaSelectorScreen`）

- 左侧：`#序号 + (min)~(max) + 体积`，滚轮翻页（12 行/页），点一行 = 选中（同时也把该区域画在世界上）。
- 右侧：6 个数字框（最小/最大 X/Y/Z），复用 `RsccNumberField.wire` 的唯一解析口径；
  输入后 **250 ms 防抖**再提交；越界 / 超体积 / 超单边 → 红字提示且**不写**（服务端还会再校验一次）。
- 按钮：**新建**（准星方块或玩家脚下 1×1×1）、**删除**（两次点击确认，5 秒失效）、
  **显示全部：开/关**、**关闭**；维度与当前不符时多一个 **改绑维度**（清空区域并重绑当前维度）。
- 打开方式：世界内潜行+右键空气；或高级仓界面里的「区域」按钮（此时改的是机器槽里那一张）。

### 1.4 写入权限（服务端权威）

界面 / 手势只发 `AreaSelectorEditPacket(containerId, action, index, area)`：
`ADD / REPLACE / DELETE / SELECT / REBIND`。服务端：

1. **落点**：`containerId` 命中的高级仓菜单专用槽 → 改槽里的物品；否则主手；都不在 → 丢包。
2. **维度**：已绑定且 ≠ 玩家当前维度 ⇒ 除 `REBIND` 外一律拒绝。
3. **几何**：`Area.invalidReason(maxVolume)`（世界边界 / 单边 ≤128 / 体积 ≤Config）。
4. **数量**：`AreaSelection.maxAreas()`。
5. 写入后回同步：菜单 → `broadcastChanges()`；主手 → `inventoryMenu.broadcastFullState()`。

## 2. 高级归流缓存仓（`rs_create_compat:advanced_collection_cache`）

### 2.1 结构 = 基础版 + 两处差异

- **继承** `CollectionCacheBlockEntity`（三轴半径、扫描节奏、删除模式、升级槽、阻塞名单、
  经验形态、物流能力、耗电公式、诊断导出全部沿用）。基础版只多了**两个 protected 钩子**
  (`areaScanHook` / `areaAbsorptionBlocked`) 与**一个注入容量的 protected 构造器**，基础版行为逐字不变。
- **方块类是独立类**（`extends Block`，照 `AdvancedSchematicLoaderBlock` 先例）：避免
  `RsccMachineCluster` 把 256 格的基础版与 512 格的高级版并成一个集群（容量会按主控规格变，玩家猜不到）。
- **容量**：缓存区 512 格（基础 256），流体/气体 1 024 000 mB（基础 512 000）。
  **吞吐完全不变**：单次 64 × (1 + 堆叠升级数)、每 tick 1 + 速度升级 组。
- **专用槽**：`ItemStackHandler(1)`（只放选定器）+ 菜单里的普通 `SlotItemHandler`
  （**不是** `UpgradeSlot`）；取出即失效（BE 不缓存区域副本，每次扫描都重读槽里的物品）。
- 破坏时选定器按「内容物去向」策略结算（回网 / 爆出 / 存方块），不会凭空消失。

### 2.2 吸收口径（只用区域）

| 来源 | 区域模式 |
|---|---|
| 掉落物 / 经验球 | 所有区域 AABB 的**并集查一次**，再按绝对坐标 `contains` 过滤（不逐区域查） |
| 世界流体源 | **跨 tick 游标**扫区域展平后的一维位次，**每 tick ≤ 2048 次 `getBlockState`** |
| 气体 | 仍是空实现（RS 2.0 无气体资源类型） |
| 相邻容器 | 与区域无关；**没有有效区域时整段不吸**（含相邻容器主动抽取） |
| XYZ 半径 | 仍然存在，但只影响 `getScanInterval()` 与耗电，**不再参与范围过滤** |

**「没有有效区域」= 选定器没装 / 区域为空 / 维度不符** ⇒ `absorbAll` 直接 return。
界面上的状态行会写清是哪一种（`未装选定器 · 不吸收` / `选定器没有区域 · 不吸收` /
`维度不符 · 不吸收` / `区域 3 · 24576 格`），并有「仅区域」橙色标签。
服务端对「维度不符」每 10 秒打一条 INFO（不刷屏）。

### 2.3 性能数值（可复核）

- 单区域上限 32³ = 32 768 格；最多 16 个区域 ⇒ 最坏 524 288 格；
- 每 tick 2 048 次查询 ⇒ 单区域 **16 tick** 扫完一遍（默认扫描间隔 20 tick ⇒ 每秒至少扫一遍）；
- 全部区域最坏 **256 tick（12.8 秒）**扫完一轮（游标只前进，不会永远只扫头部）；
- 区域增删改 ⇒ 游标归零（绝不拿旧区域的位次扫新区域）；同一 tick 的第二次调用复用同一批结果。

### 2.4 界面

- 沿用基础版 256×324 布局（背景 / 匹配区 / 缓存区 / 滚动条 / 所有子界面都在）。
- 升级槽左边新增：**区域选定器槽**（橙色描边）+ **「区域」按钮**（打开列表）+ **「范围」按钮**
  （查看范围：把选定器里每个区域用统一橙色画 5 秒）。
- 缓存区标题带右侧一行状态：`区域 N · 体积` 或三种「不吸收」原因；升级带右侧橙色「仅区域」。
- 空槽 tooltip 明确写「放入区域选定器：只按区域吸收（取代 XYZ）；没装/区域为空 ⇒ 不吸收」。

## 3. 颜色（全局唯一）

| 用途 | 颜色 | 常量 |
|---|---|---|
| 本模组全部范围显示（选定器预览 / 已提交区域 / 查看范围 / 充电范围） | `0xFF9F1C` 橙 | `client/AreaOutline.COLOR`；充电器自身常量同值 |
| Create 蓝图框（**刻意避开**） | `0x6886c5` 蓝紫 | 不引用 |
| 线宽 | `1/16f` | `AreaOutline.LINE_WIDTH` |

> `OutlineParams#colored(int)` 只吃 RGB（不含 alpha），透明度由 Outliner 的淡出与面贴图决定；
> 因此这里传 `0xFF9F1C` 与传 `0x9F1C` 结果相同。

## 4. 贴图（基于基础版派生，不是新画）

`tools/gen_advanced_collection_cache_assets.py`（幂等）：

1. 取基础版 `collection_cache_{top,side,bottom}_{active,inactive}.png`；
2. 每个像素压暗 12% + 往橙铜偏色（R×1.06+10、G×0.78、B×0.52）⇒
   `advanced_collection_cache_*`（与基础版并排一眼是同族、又明显更沉）；
3. 物品图标 `textures/item/area_selector.png` = 基础版侧面贴图压暗后叠一个橙色选框（四边框 + 四角加粗）。

## 5. 刻意不做 / 未验证

- **不做「交集」模式**，也不留 Config 开关：用户明确「只用区域（取代）」。
- **不做「区域内方块实体（容器）」扫描**：与「限位吸掉落物/流体」的需求无关，且是全新成本。
- **不做成就**：成就 JSON 必须由 `tools/gen_advancements.py` 生成，本轮写入范围不含成就；
  两条 id 登记在 `selfcheck_advancements.PENDING_ADVANCEMENT_IDS`（脚本会打印豁免）。
- **未验证**：真实客户端里的手感（框选 / 滚轮步进 / GUI 像素级对齐）需要实机点一遍；
  区域相距极远（如 >256 格）时并集 AABB 会覆盖中间地带，实体查询会多扫一块（成本只与 AABB 相关，
  与方块遍历无关 —— 方块遍历仍只走区域）；光影（Iris/Oculus）下橙色框的可见性未测。
