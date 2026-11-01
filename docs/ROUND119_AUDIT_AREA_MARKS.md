# 第 119 轮：区域 / 标记家族「只读事实梳理 + 对抗审查」

> 类型：**只读审计**（没有改任何源码、没有跑 gradle、没有 git 操作、没有进游戏）。
> 目的：在动手之前把「区域」这件事的事实钉死，并把用户那句长期未澄清的
> 「**无法单独给某一个区域标记、非得把标记放在方块里**」拆成可判定的诉求。
> 证据口径：所有结论都给 `文件:行`。凡属**读代码推断**、没有实机/字节码佐证的，另标 **〔推断〕**。

---

## 0. 一句话速览

- **「区域」在代码里只是一个物品数据组件里的六个整数坐标**（`Area`），完全不依赖任何方块；
  方块实体里**既没有区域、也没有标记**（只剩一张给旧档用的、按序号的迁移缓冲表）。
- 机器（高级归流缓存仓）**只是区域的一个消费者**：它读专用槽里的选定器物品，不拥有区域数据。
- 所以「非得把标记放在方块里」这个描述在 **1.2.0-beta.14（第 112 轮）之后已经不成立**——
  这条反馈的原文正是第 112 轮的立项依据（`docs/ROUND112_AREA_MARKS_PORTABLE.md:3-7`），
  **第一件要问清的事是：用户跑的是哪个版本**（见 F-1）。
- 但仍有 **6 个真实的残留卡点**（B③/B①/菜单空槽静默失效/只认主手/区块级标记/别的机器用不了区域），
  以及 **9 条对抗审查发现**（其中 2 条「中」是真 bug：菜单路径拒绝后不回全量状态、
  菜单开着但槽为空时区域编辑全部静默丢包）。

---

## A. 能力事实表

### A-1 「区域」到底是什么

| 事实 | 证据 | 强度 |
|---|---|---|
| 区域 = `record Area(int minX,minY,minZ,maxX,maxY,maxZ, @Nullable String name)`，**绝对世界坐标的闭区间盒**（含首含尾） | `support/Area.java:37-38`、`Area.java:12-13` | 代码 |
| 构造时自动归一化 `min<=max`（先点哪个角都合法） | `Area.java:52-67` | 代码 |
| 单边硬上限 128 格（防 1×1×1000000 长条），世界坐标限 ±3e7、Y ∈ [-256,4096] | `Area.java:40`、`Area.java:42`、`Area.java:158-171` | 代码 |
| 区域**不含维度**：维度绑定在「选定器」这一层（`AreaSelection.dimension`） | `Area.java:18-19`、`AreaSelection.java:27-31` | 代码 |
| 区域集合 = `record AreaSelection(ResourceLocation dimension, List<Area> areas, int selectedIndex)` | `AreaSelection.java:48` | 代码 |
| 区域集合存在**注册版数据组件** `AREA_SELECTION` 上（`persistent(Codec)` + `networkSynchronized(StreamCodec)`） | `RS_Create_Compat.java:135-138`、`AreaSelection.java:66-79` | 代码 |

**结论 A-1**：区域 = 一份**纯数据**（物品组件），没有方块依赖。

### A-2 「必须有机器当宿主吗」→ **不需要**

| 事实 | 证据 |
|---|---|
| 世界内框选：手持选定器右键两个点 → 发 `AreaSelectorEditPacket` → 服务端写**主手物品组件** | `client/AreaSelectorHandler.java:220-250`；`network/AreaSelectorEditPacket.java:132-291` |
| 区域列表界面：主手路径直接读写主手物品，**不需要打开任何方块界面** | `client/screen/AreaSelectorScreen.java:240-259`、`:775-807` |
| 区域标记界面：主手路径读主手物品，**不需要机器** | `client/screen/AreaMarkScreen.java:127-153` |
| 机器侧只是「读槽里的那个物品」：`getAreaSelectorStack()` = `areaSlot.getStackInSlot(0)` | `block/entity/AdvancedCollectionCacheBlockEntity.java:119-127` |
| 机器**不缓存**区域副本：每次（每 tick）重新解析槽里的物品 | `AdvancedCollectionCacheBlockEntity.java:81-104`、`:757-791` |

### A-3 标记存在哪：**物品上**（beta.14 起）

| 事实 | 证据 |
|---|---|
| 每区域过滤标记存在选定器物品的 `CUSTOM_DATA` 子树 `RsccAreaMarks`：`Marks:[{Area:[6 个 int], Entries:[ghost 标记…]}]` | `support/AreaMarks.java:45-52`、`:73-76`、`:530-544` |
| 键 = **六坐标几何**（`area.withName(null)`），**名字 / 序号都不参与** ⇒ 改名、重排、复制不会串位 | `AreaMarks.java:85-86`、`:491-494`、`:105-114` |
| 标记编码与机器「匹配区」**完全同一份**（`CollectionCacheBlockEntity#encodeMarkerTag`） | `AreaMarks.java:51-52`、`:540` |
| 单区域条数上限 = `MATCH_CAPACITY` = `MATCH_PAGES(6) × 48` = **288** | `CollectionCacheBlockEntity.java:100-108`、`AreaMarks.java:566` |
| 方块实体里**没有区域、也没有标记**：只有 `areaSlot` 与旧档迁移缓冲 `legacyAreaFilters` | `AdvancedCollectionCacheBlockEntity.java:73-104`、`:209-224`、`:795-819` |

**结论 A-3**：区域与标记都「随物品」，机器只是消费者。**「非得把标记放在方块里」在数据层已经不可能成立。**

### A-4 选择器能选几个区域 / 能否每区域单独命名 / 单独过滤 / 单独开关

| 能力 | 现状 | 证据 |
|---|---|---|
| 一个选定器能存几个区域 | **能存多个**：默认上限 16（Config `1..64`），绝对硬上限 64 | `Config.java:170`、`:483`；`AreaSelection.java:50`、`:100-103` |
| 每区域单独命名 | **能**：名字 ≤24 字符、空白=清除、不参与判等 | `Area.java:37`、`:44`、`:68-77`、`:290-296`；`AreaSelectorEditPacket.java:231-238`；界面名称框 + 「确定」按钮 `AreaSelectorScreen.java:309-323` |
| 每区域单独过滤名单 | **能**（beta.14 起在物品上）：空集 = 该区域什么都不吸 | `AreaMarks.java:84`、`:283-308`；`AreaMarkEditPacket.java:105-155`；`AreaMarkScreen.java:198-286`；机器侧 48 格同源 `AdvancedCollectionCacheBlockEntity.java:538-586` |
| 每区域单独**开关**（临时停用，不删） | **不能**：`Area` 没有 `enabled` 字段；吸收循环无条件遍历全部区域 | `Area.java:37-38`；`AdvancedCollectionCacheBlockEntity.java:654-661`、`:767-791` |
| 每区域单独**颜色 / 描边样式** | **不能**：全局一个橙 `0xFF9F1C` | `client/AreaOutline.java:46`、`:65-120` |
| 一个选定器只能服务**一个维度**；跨维度只能「改绑（清空全部区域）」 | 每条写包都过维度闸门 | `AreaSelection.java:27-31`、`:209-212`；`AreaSelectorEditPacket.java:146-158` |
| 一台机器能装几个选定器 | **1 个**（`AREA_SLOT_COUNT = 1`），槽只收选定器 | `AdvancedCollectionCacheBlockEntity.java:71-72`、`:86-90`；`menu/AdvancedCollectionCacheMenu.java:102-112` |

### A-5 描边 / 边界谁渲染、怎么显示

渲染**全部在客户端**，走 Create / Ponder 的 `Outliner`（本模组只负责「喂 AABB」，不写渲染器）：

| 入口 | 行为 | 证据 |
|---|---|---|
| 统一出口 | `client/AreaOutline`：橙 `0xFF9F1C` + 棋盘面贴图 + `1/16` 线宽；三个入口 `showForView/chase/chaseBox` 参数逐字一致 | `AreaOutline.java:27-32`、`:46-51`、`:65-120` |
| 手持选定器（无 GUI） | **全部已提交区域一起常亮**（每 tick 喂一次 `world/i`） | `AreaSelectorHandler.java:92-103` |
| 未提交的框 | 准星预览框（`pending`），Ctrl 滚轮推拉 | `AreaSelectorHandler.java:104-137`、`:256-274` |
| 区域列表界面 | **选中的那一个**常亮 + 「显示全部」开关（全显） | `AreaSelectorScreen.java:836-858`、按钮 `:331-335` |
| 关界面后 | 不立刻删框，按剩余 5 秒重新武装（防「点完就关看不见」） | `AreaSelectorScreen.java:1338-1361`；`AreaOutline.java:52-53`、`:69-88` |
| 机器「查看范围」 | **单显** = 当前正在编辑过滤的那个区域；**全显** = 快照里全部可用区域 | `client/screen/AdvancedCollectionCacheScreen.java:194-254` |
| 机器范围口径 | 永不返回半径模式：生效→`MODE_AREAS`；区域空/维度不符→`MODE_NO_AREA`；没装→`MODE_NO_SELECTOR`（都不画框，因为真的不吸） | `AdvancedCollectionCacheBlockEntity.java:720-755` |
| 边界去重 | 服务端与客户端都走 `distinctAreas()`（防同一块地叠画两份） | `AreaSelection.java:241-255`；`AdvancedCollectionCacheBlockEntity.java:746-755`；`AreaSelectorHandler.java:93-99` |
| 降级 | Outliner 不可用时 `disabled` 置真、之后不再重试（表现=框不出现，其余功能不受影响） | `AreaOutline.java:40-42`、`:85-87`、`:145-148` |

### A-6 澄清三条容易被误当成事实的说法

1. **「区域存在方块实体 NBT 里」= 错**。BE 里只有 `AreaSelector`（槽里那个物品的序列化，含它的组件）
   与旧档迁移表 `AreaFilters`（`AdvancedCollectionCacheBlockEntity.java:73`、`:209-218`、`:795-819`）。
2. **「标记必须先插进机器才能配」= 错（beta.14 起）**。手持路径完整可用；但**只认主手**
   （`AreaSelectorHandler.java:69-75`、`AreaSelectorTargets.java:35-38`），副手不行。
3. **「区域是区块（chunk）级的」= 错**。全仓**没有任何 chunk 级选择/标记功能**
   （grep `区块标记|ChunkMark|chunk_mark` 命中 0），区域一律是方块坐标盒。

---

## B. 把「无法单独给某一个区域标记」拆成可能诉求

> 前置判断（很重要）：这句话的原文与第 112 轮的立项依据**逐字重合**
> （`AreaMarks.java:19-20`、`AreaMarkScreen.java:26-31`、`AreaSelectorHandler.java:41-45`），
> 而第 112/113/114 轮已经把「每区域标记放到物品上 + 独立界面 + 预算」全部做完
> （`docs/ROUND112_AREA_MARKS_PORTABLE.md`、`ROUND113_*`、`ROUND114_*`）。
> 因此**先确认版本**；下表按「即便在新版本上仍可能成立的诉求」逐条评估。

### B① 「描边只能一堆区域一起亮，没法只高亮我想看的那一个」

- **现在能不能**：**部分能，但体验上不满足**。
  - 列表界面里点一行 → 该区域常亮；但**同时**「显示全部」是开关状态，且**关掉界面立刻回到全部常亮**
    （手持路径 `AreaSelectorHandler.java:92-103` 无条件喂全部区域）。
  - 机器界面有「单显」，但它绑定的不是「玩家想看的行」，而是**当前正在编辑过滤的那一个区域**
    （`AdvancedCollectionCacheScreen.java:189-213`，注释明确说这是刻意的）。
  - 没有「每区域独立显示开关」，也没有「持久选中」——`selectedIndex` 会存，但手持渲染并不读它。
- **卡点**：`AreaSelectorHandler.java:95-99`（全喂）+ `AreaOutline.java:46`（单一颜色）+
  `AreaSelection` 无 per-area `visible` 字段。
- **要支持需要改**：
  - 手持渲染改为「只喂 `selected()`」或「喂 selected + 其余半透明/不喂」，并加一个键位/潜行手势切「全显」；
  - 若要 per-area 开关：`Area` 加 `boolean visible`（**Codec 加 `optionalFieldOf("visible")` 默认 true**，
    旧档零迁移）；`Area` 是 record，新增字段要改 6 参构造 + `CODEC` + `STREAM_CODEC`（7→8 段，
    `Area.java:96-114` 是手写编解码，**改网络契约 ⇒ 客户端/服务端必须同版本**）。
  - 风险：网络契约破坏性变更（旧客户端连新服务端会解错包）⇒ 要么放在大版本，要么用「新包 + 新组件」旁路。
- **工作量**：小（只改客户端渲染 + 一个键位）／中（加持久开关）。

### B② 「每个区域不能有自己的名字 / 自己的过滤名单」

- **现在能不能**：**能**。名字（`AreaSelectorScreen.java:309-323`、`AreaSelectorEditPacket.java:231-238`）、
  过滤名单（`AreaMarkScreen.java` 全套 + `AreaMarks`）都已实现，且机器 48 格窗口与手持界面读写同一份数据
  （`AdvancedCollectionCacheBlockEntity.java:323-336`、`AreaMarkEditPacket.java:47-54`）。
- **唯一残留**：标记界面**只画 36 格**（9×4，`AreaMarkScreen.java:76-85`），第 37 条以后在这个界面里
  **看不到也删不掉**（如实提示「其余在机器界面里看」`AreaMarkScreen.java:402-412`）。
  机器匹配区是 6 页 × 48 格（`CollectionCacheBlockEntity.java:100-108`），能翻页。
- **要支持需要改**：给 `AreaMarkScreen` 加翻页（复用 `SptScrollbarWidget` 或 `< >` 按钮），
  或者把「数量门槛 / NBT / 标签」也搬进来（才可能不再需要机器）。
- **风险**：低（纯客户端）；**存档零影响**。
- **结论**：如果用户跑的是 beta.14+，这条**不应该**再被当作诉求成立；先问版本。

### B③ 「区域选定器必须先插进机器方块槽才能用，拿在手上不能选」

- **现在能不能**：**已经能**（世界手势 + 右键空气开列表），但有 4 个真实残留：
  1. **只认主手**：`AreaSelectorHandler.java:69-75`、`AreaSelectorTargets.java:35-38`（副手一律不认）。
  2. **开列表要「右键空气」**：对着方块右键是「记 A 角 / 提交区域」
     （`AreaSelectorHandler.java:206-219`），基地里到处是方块 ⇒ 仍可能觉得「打不开」。
  3. **要配标记是两步**：右键空气开列表 → 选中行 → 点「编辑过滤」→ 才进标记界面
     （`AreaSelectorScreen.java:681-708`）。没有快捷键、没有「右键某个区域直接配」。
  4. **一处真 bug 会让它看起来「又变回必须插方块」**：从机器界面打开区域列表、但槽里没放选定器时，
     界面会**回退读主手**（`AreaSelectorScreen.java:240-254`），而写包仍带 `containerId`
     ⇒ 服务端 `resolve` 解析到空槽 ⇒ **全部编辑静默丢包**（详见 C-2）。
- **改动面**：主手→双手（1 行判断）；加键位（`KeyMapping` + 客户端注册，中等）；
  加「手持右键某个已提交区域 → 直接开该区域的标记界面」（需要射线拾取 AABB 命中，中等）；
  修 C-2（3~5 行）。
- **存档兼容**：全部零迁移。
- **风险**：低；键位要注意与 Create 的键位冲突检查。

### B④ 「一台机器只能存一个区域」

- **准确说法**：一台机器 = **一个选定器槽（1 个）** = **一份区域集合（默认 16 块、硬上限 64）**。
  所以「一台机器只用一块区域」不是限制，限制是「**不能同时挂两份独立的区域集合**」。
- **证据**：`AdvancedCollectionCacheBlockEntity.java:71-72`、`:86-90`；
  `AdvancedCollectionCacheMenu.java:102-112`；shift 点击时槽被占就什么都不做
  （`AdvancedCollectionCacheMenu.java:461-472`）。
- **如果用户想要「一台机器按区域分组做不同过滤」**：现状可以用「多个区域 + 每区域过滤」表达
  （每个区域本来就是独立过滤单元，`AdvancedCollectionCacheBlockEntity.java:645-663`）。
- **如果用户想要「一台机器装多个选定器」**：改动面=槽位数 1→N + 菜单槽位表 + 每槽独立解析/扫描 +
  快照数据结构；存档兼容=BE 里 `AreaSelector` 从 Compound 变 List（需迁移读旧键）；风险=高（扫描/UI 全动）。
- **建议**：先不做。除非用户明确说「要同时挂多套区域」。

### B⑤ 「别的方块（装填器 / 数量保持器）也想用区域当目标，不想贴方块」

- **现在能不能**：**完全不能**。全仓只有 `AdvancedCollectionCacheBlockEntity` 与其菜单/界面消费 `AreaSelection`
  （grep 全量结果：只有 `AdvancedCollectionCacheBlockEntity`、`AdvancedCollectionCacheMenu`、
  `AreaSelector*Screen`、`AreaSelectorEditPacket`、`ShowCollectionRangePacket`、`CollectionCacheScreen`）。
  数量保持器 / 装填器都没有区域槽、也不读选定器。
- **卡点**：没有任何「区域目标」抽象；`AreaSelection.sanitize` + 维度闸门 + 安全默认（空集=不吸）
  这套语义目前是**内嵌在归流缓存仓里**的（`AdvancedCollectionCacheBlockEntity.java:777-791`）。
- **要支持需要改**：
  1. 抽一个 `support/AreaTarget`（读选定器 → sanitize → 维度校验 → `Optional<List<Area>>` + 状态码），
     把现在 `resolveSelection` / `getAreaStatus` 的逻辑搬进去（**纯重构，行为不变，先把口径统一**）；
  2. 给目标机器加 1 个「区域选定器槽」（照 `AdvancedCollectionCacheMenu.java:102-112` 的写法）
     或「手持选定器」模式（需要服务端在每次动作时读主手，注意 `stillValid` 语义）；
  3. 目标侧把「范围」从 AABB/半径换成「区域命中集合」——这一步每台机器都不一样，风险最高。
- **存档兼容**：新槽位 = 新 NBT 键，旧档读出为空 ⇒ 不需要迁移；但**旧版本回退**会丢新槽内容。
- **风险**：中高（逐机器改）；**建议顺序：先抽抽象 + 先只接一台机器（数量保持器）**。

### B⑥ 「我想按区块（chunk）划范围，而不是一个个点方块」

- **现在能不能**：**不能**（全仓无 chunk 选择）。区域只能：两点框选 / 数字输入 / 1×1×1 种子 + 拉伸。
- **改动面**：`Area` 语义不变（chunk → 16×256×16 的盒，但 `MAX_SIDE=128`、体积上限 32768 会直接拦下
  一个 16×256×16=65536 的整柱 ⇒ 需要放宽 `MAX_SIDE`/体积或做「区块集合」这种新类型）。
- **风险**：中（上限口径与扫描预算都要重算：`AREA_QUERY_BUDGET_PER_TICK = 2048` 是硬约束，
  一个区块柱 65536 格 = 32 tick 才扫完一轮）。
- **建议**：先问用户是不是这个意思（见 F-2）。

### B⑦ 「我想给某块区域打一个世界内的可见标记（名字/图标挂在天上）」

- **现在能不能**：**不能**。名字只出现在 GUI 列表与标记界面标题
  （`AreaSelectorScreen.java:911-1030`、`AreaMarkScreen.java:340-348`），世界内没有文字标签渲染。
- **改动面**：客户端 `RenderLevelStageEvent` 里画 billboard 文字（不新增方块/实体），或给 `Area` 加「标签锚点」。
- **存档兼容**：加可选字段 = 零迁移；或纯客户端（用现有 `name`）。
- **风险**：低-中（渲染距离 / 遮挡 / 性能）。

### B-建议实现顺序

1. **先修 C-1 / C-2**（各 3~10 行，都是「静默失效」类，用户感知强）。
2. **确认版本**（F-1）。若用户仍在 beta.13 或更早 ⇒ 只需让他升级，不写代码。
3. **B③ 的 1/2/3**（双手支持 + 键位 + 右键区域直接开标记界面）——纯体验，零存档风险。
4. **B① 的客户端部分**（只亮选中 / 全显键位），**暂不加持久字段**（避免动网络契约）。
5. **B② 的翻页**（若用户确实 >36 条标记）。
6. **B⑤ 的抽象抽取**（重构先行，接机器后置）。
7. B④ / B⑥ / B⑦ 都要**等用户拍板**，不要先做。

---

## C. 对抗式审查（只读，未实机）

> 严重度：**中** = 玩家可见的错误行为（数据不丢，但会「点了没反应 / 删错一条」）；
> **低** = 边界/仅特殊配置可达；**信息** = 不是 bug，但值得知道。
> 每条都标「可达性」，区分「改包可达」与「正常玩法可达」。

### C-1【中】菜单路径的「拒绝」不回全量状态 ⇒ 客户端乐观副本残留 ⇒ 之后的标记编辑被静默丢弃

- **链路**：
  1. 客户端点「改坐标 / 改名」时**先乐观改本地物品副本**：`AreaSelectorScreen.java:509-518`；
  2. 服务端拒绝（维度 / 重复 / 越界 / 上限）：`AreaSelectorEditPacket.java:311-319`
     → `menu.noteAreaReject(reason)` + `sync(...)`；
  3. `sync` 对菜单路径只做 `onAreaSelectorEdited()` + `broadcastChanges()`
     （`AreaSelectorTargets.java:66-72`）；
  4. `broadcastChanges` 只在**槽内容与服务端上次发出的快照不同**时才发包；服务端物品**本来就没变**，
     所以**什么都不会发** ⇒ 客户端那份被乐观改过的副本留在界面上；
  5. 玩家接着配标记 → `AreaMarkEditPacket` 带着服务端**不存在**的几何 → `findArea` 返回 null →
     `return`（`AreaMarkEditPacket.java:101-104`），而界面已经弹了「已加入」
     （`AreaMarkScreen.java:244-248`）。
- **为什么是「真 bug」**：这正是第 113 轮 D7 修掉的**同一类失效**，但 D7 只修了主手分支
  （`AreaSelectorEditPacket.java:313-315` 的 `menu == null` 分支才 `broadcastFullState()`）。
- **修法**：`reject(...)` 的菜单分支也补一次 `menu.broadcastFullState()`
  （或 `player.connection.send(new ClientboundContainerSetContentPacket(...))`）。
  注意别在每 tick 都调（只在拒绝时调一次）。
- **可达性**：正常玩法可达（例如在 A 维度打开机器、A 维度不符时点「新建」；或点一个几何重复的区域）。
- **置信度**：高（代码路径闭合）；**未实机**。

### C-2【中】菜单开着、但区域槽为空时，区域列表会「读主手、写菜单槽」⇒ 所有编辑静默丢包

- **链路**：
  - `AreaSelectorScreen.targetStack()`：`menu != null` 且槽里有选定器 → 读槽；**否则回退主手**
    （`AreaSelectorScreen.java:240-254`）；
  - 但 `containerId()` 仍返回 `menu.containerId`（`:261-263`），写包带的就是它；
  - 服务端 `AreaSelectorTargets.resolve`：`containerId >= 0` ⇒ 只认菜单槽，槽空 ⇒ 返回 `EMPTY` ⇒ **丢包**
    （`AreaSelectorTargets.java:39-44`）；
  - 更糟：`resolveTarget` 为空时**直接 return，连 sync 都不做**
    （`AreaSelectorEditPacket.java:137-140`）⇒ 客户端那份乐观副本也不会被纠正。
- **对照**：`AreaMarkScreen` 在 D10-f 里已经改成「菜单路径不回退主手」
  （`AreaMarkScreen.java:132-143`），**区域列表界面没跟着改** ⇒ 两处口径不一致。
- **可达性**：正常玩法可达。机器界面「区域」按钮是**无条件**可点的
  （`AdvancedCollectionCacheScreen.java:128-175`），槽里空着也能开。
- **修法**：`targetStack()` 在 `menu != null` 时**不回退**（与 `AreaMarkScreen` 同源），
  并在 UI 上给一句「请把区域选定器放进左下角专用槽」（语言键已有 `area_slot.empty` 可复用
  `AdvancedCollectionCacheScreen.java:515-516`）。
- **置信度**：高。

### C-3【低】手持路径不做「去重 / 维度」预检，且先弹「已提交」再发包；被拒时没有任何可见反馈

- **证据**：`AreaSelectorHandler.java:228-250`：只校验 `invalidReason` 与区域个数，**没有** `hasGeometry`
  预检，发完包立刻 `message(MSG + "committed", ...)`；服务端拒绝时主手路径只 `broadcastFullState()`，
  **不弹任何文字**（`AreaSelectorEditPacket.java:313-315`；`reject` 的菜单分支才有 `noteAreaReject`）。
- **后果**：在同一块地上重复框选两次 → 服务端拒绝 → 玩家看到的是「已提交」。
- **修法**：客户端补 `hasGeometry` 预检（`AreaSelectorScreen.createArea` 已有同款逻辑
  `AreaSelectorScreen.java:604-609`，直接照抄）；或给主手路径加一条 actionbar 拒绝原因
  （需要新 S2C 包，成本更高）。
- **可达性**：正常玩法可达（重复框同一片地是很自然的手滑）。
- **置信度**：高。

### C-4【低】`AREA_LIST_SLOT_MAX = 16` 与 Config 上限 64 不一致 ⇒ 区域 17 以后「只吸 N 种」恒显示 0

- **证据**：`AdvancedCollectionCacheMenu.java:58-60`（16）、`:122-131`（越界直接 `return 0`）、
  `:272-279`（`areaIndex >= AREA_LIST_SLOT_MAX` ⇒ 0，**服务端也返回 0**）。
- **触发条件**：把 `areaSelectorMaxAreas` 配到 >16（Config 允许到 64，`Config.java:170`）。
- **后果**：机器界面里第 17 块及以后区域的「有效过滤条目数」永远显示 0（但**过滤实际生效**——
  48 格窗口走的是 `getAreaFilter(editingAreaIndex)`，不受 16 限制）。
  ⇒ 属于「界面说谎」，不是功能失效。
- **修法**：数据槽改成分页（一次只同步当前页的 16 个计数），或把上限抬到 64（数据槽 +48 个，
  每个 INT，包体每 tick 多 ~2 字节/槽，可接受），或直接标注「超过 16 个区域时该列不显示」。
- **置信度**：高。

### C-5【低】客户端读标记不带注册表 ⇒ 旧格式条目被跳过 ⇒ 列表下标与服务端错位 ⇒ `REMOVE` 可能删错一条

- **证据**：客户端 `AreaMarks.get(stack, area)` 走两参重载（`AreaMarks.java:87-89`），`registries = null`；
  旧格式（`Item` = 整份 ItemStack）条目在 `registries == null` 时**解码返回 null 并被丢弃**
  （`CollectionCacheBlockEntity.java:4132-4139`；即第 113 轮 D9 的早退）；
  服务端读的是 `AreaMarks.get(target, area, player.registryAccess())`
  （`AreaMarkEditPacket.java:142`）⇒ **两边列表长度/顺序不同**；
  `REMOVE` 传的是**下标**（`AreaMarkScreen.java:252-266`）⇒ 删到的是服务端列表里同下标的另一条。
- **可达性**：窄——需要（a）旧档里还留着 beta.13 之前写的 `Item` 格式标记，且（b）该区域在列表前部。
  一旦发生，是**删错条目**（不丢区域、不崩）。
- **修法**：`AreaMarkScreen` 改成「按条目内容（id+fluid+amount）请求删除」而不是按下标；
  或客户端也拿 `registryAccess()`（`Minecraft.getInstance().level.registryAccess()` 可用）。
  后者 1 行、风险最小。
- **置信度**：中-高（逻辑闭合；旧格式条目是否真的还存在取决于用户档龄）。

### C-6【中·〔推断〕】区域扫描对「未加载区块」没有 `isLoaded` 闸门，`getBlockState` 会同步加载区块

- **证据**：`AdvancedCollectionCacheBlockEntity.AreaCursor.scanFluidWindow` 逐位次算坐标后直接
  `isSource.test(pos)`（`AdvancedCollectionCacheBlockEntity.java:1002-1013`）；`isSource` 就是
  `fluidSourceIdAt`，它第一行 `level.getBlockState(pos)`（`CollectionCacheBlockEntity.java:3028-3029`），
  **没有** `level.isLoaded(pos)` 判断。
  对照：本工程其它所有「读世界」的地方都先 `isLoaded`（例：`CollectionCacheBlockEntity.java:2297-2300`、
  `support/SeparationFrameGuard.java:263-264` 甚至写明「`getBlockState` 会对未加载区块触发加载」）。
- **后果**：区域跨越未加载区块时（玩家离开、区块被卸载，而机器还在跑），每 tick 2048 次查询可能
  反复触发同步区块加载 ⇒ 主线程卡顿 / 服务器 tick 时间上升。
- **为什么只标中·推断**：本次**没有 javap 验证** 1.21.1 的 `Level#getBlockState` 对未加载区块的行为
  （工程内的先例注释强烈支持「会加载」，但没有字节码证据），也**没有实机 profile**。
- **修法**：`fluidSourceIdAt` 开头加 `if (!level.isLoaded(pos)) return null;`（1 行），
  或让 `isSource` 谓词先判 `isLoaded`。
- **置信度**：中（机制高置信，触发频率未测）。

### C-7【中·〔推断〕】实体的「并集 AABB」在区域彼此远离时会撑成巨大查询盒

- **证据**：`AreaCursor.update` 对全部区域求 AABB 并集（`AdvancedCollectionCacheBlockEntity.java:936-952`）；
  `AreaScanHook.entityQueryArea()` 再把并集向上扩 1 格（`support/AreaScanHook.java:44-62`）；
  吸收侧用这个盒做 `getEntitiesOfClass`（`CollectionCacheBlockEntity.java:2617-2621`、`:2763-2770`）。
- **后果**：两块区域相隔 1000 格 ⇒ 查询盒跨度 1000+ 格（≈ 60³ 个 16³ 区块段）；
  相隔 10 km ⇒ 天文数字。代价取决于 MC 的「实体段遍历」实现（是否只遍历已存在的段），本次**未验证**。
- **修法**（任选）：
  a) 按「簇」分组：区域按距离聚类，每簇一个并集盒 + 一次查询（推荐，改动只在 `AreaCursor`）；
  b) 逐区域查询（区域数 ≤16 时更简单，但区域多时贵）；
  c) 限制「同一选定器的区域必须相邻」（产品级约束，最省事但限制玩法）。
- **置信度**：中-低（风险方向确定，量级未测）。

### C-8【低】可以把「区域选定器自己」加成某个区域的过滤标记

- **证据**：`AreaMarkScreen.addFromInventory` 只查了「单条是否超预算」与「同 id 是否重复」
  （`AreaMarkScreen.java:205-243`），**没有**排除 `AREA_SELECTOR` 自身；
  服务端同样只查 id 重复（`AreaMarkEditPacket.java:120-124`）。
- **后果**：无害但荒谬——那条标记的 NBT 里嵌着整份 `RsccAreaMarks`（一层嵌套，不会无限递归，
  因为同 id 去重挡住了第二次）。会白占整表预算（`MarkerBudget` 会兜住，不会撑爆）。
- **修法**：`source.is(RS_Create_Compat.AREA_SELECTOR.get())` 时直接拒绝（客户端 + 服务端各 1 行）。
- **可达性**：正常玩法可达（标记界面的右侧背包格里就有选定器）。

### C-9【信息】三个「已知且已披露」的边界，不要重复计为新 bug

| 项 | 事实 | 出处 |
|---|---|---|
| 重叠区域「先命中先算」 | 列表里靠前的**空标记**区域会挡住后面区域的有效标记 | `AdvancedCollectionCacheBlockEntity.java:654-661`；`docs/ROUND113_AREA_MARK_HARDENING.md:107-108` |
| REPLACE 合并 >288 条会被静默截断 | 只有「旧区域本身 >288 条」这种手改 NBT 才可能触发；已加 WARN | `AreaSelectorEditPacket.java:273-284`；`ROUND114_MARKER_BUDGET.md:118`、`:190-191` |
| 机器侧「标签集合」未接体积预算 | `setAreaMarkerConfig` 的 tags 只受 `TAG_CAP` 限制 | `ROUND114_MARKER_BUDGET.md:188-189` |
| 全部区域校验不过时状态显示「区域为空」 | `getAreaStatus` 用 `sanitize(raw).isEmpty()` ⇒ 超限与真空共用 `AREA_STATUS_EMPTY` 文案 | `AdvancedCollectionCacheBlockEntity.java:154-157`（可读性瑕疵，非 bug） |

### C-10 顺带确认「没有」的几类问题（省下一轮排查）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 改包能否把区域写到别人机器 / 别的槽 | 不能：`containerId` 必须匹配当前打开且 `stillValid` 的菜单；否则丢包、**不回退主手** | `AreaSelectorTargets.java:34-55` |
| 改包能否用伪造几何给物品塞标记 | 不能：按六坐标几何在选定器上反查，找不到就丢包 | `AreaMarkEditPacket.java:101-104`、`:159-170` |
| 改包能否塞超大 NBT | 三层拦截（单条 `MAX_ENTRY_BYTES=16384` 堆 ≈2KB 线上、整表 `MAX_TOTAL_BYTES=524288` 堆 ≈64KB 线上、解码端 `TAG_CAP`） | `MarkerBudget.java:64-86`、`:121-140`；`AreaMarkEditPacket.java:127-138` |
| 区域数能否被撑爆 | `ABSOLUTE_MAX_AREAS = 64` 硬夹；快照 / 描边 / `all()` 都按它截断 | `AreaSelection.java:50`；`AdvancedAreaSnapshotPacket.java:162-163`；`AreaSelectorHandler.java:96` |
| 读档会不会丢区域 / 标记 | 区域在物品组件（`persistent`）；标记在 `CUSTOM_DATA`；BE 只序列化槽里的物品 | `RS_Create_Compat.java:135-138`；`AreaMarks.java:513-528`；`AdvancedCollectionCacheBlockEntity.java:795-799` |
| 旧档（按序号）迁移会不会串位 | 有 `isIndexPrefix` 守卫：位次对不上就整体冻结 + WARN，**不猜** | `AreaMarks.java:261-279`、`:425-440`；`AdvancedCollectionCacheBlockEntity.java:354-380` |
| 破坏机器会不会吞选定器 | 走 `BlockContentReleaser` 按真物品爆出；槽内容也在 `saveAdditional` 里 | `AdvancedCollectionCacheBlockEntity.java:857-876` |
| 两个玩家同开一台机器 | 编辑上下文是**菜单级**（每人一份），互不干扰 | `AdvancedCollectionCacheMenu.java:71-76`；`SetAreaFilterContextPacket.java:20-26` |
| 跨维度使用 | 维度不符 ⇒ 机器不吸收、状态码 3、界面显示原因；改绑是唯一逃生口（会清空区域 + 剪标记） | `AdvancedCollectionCacheBlockEntity.java:146-169`、`:777-791`；`AreaSelectorEditPacket.java:146-158` |

---

## D. 已经做过的（不要重复报）

| 轮次 | 做了什么 | 对本轮的意义 |
|---|---|---|
| 第 101 轮 | 每区域过滤（**存在 BE 上、键=序号**） | 用户抱怨的原始形态 |
| 第 106 轮 | 区域列表界面只列服务端可用区域 + 拒绝原因回传 + 单显/全显 + 快照 | B① 的机器侧已有基础 |
| 第 112 轮 | **标记搬到物品**（`AreaMarks`）+ 新界面 `AreaMarkScreen` + 新包 `AreaMarkEditPacket` + 右键空气入口 | **B②③ 的主体已完成** |
| 第 113 轮 | 12 条加固（REPLACE 携带标记、prune 早退、迁移保留未映射、单条上限…） | C-5 的成因（旧格式）在这里被承认 |
| 第 114 轮 | 整表预算 `MarkerBudget` + 迁移「消费/冻结」+ 位次守卫 | 体积类问题已收口 |
| 第 115~118 轮 | 跨机器预算 / 序列写入预算 / 黑名单上限 / 保持器目标同步 | 与本家族无直接冲突 |

---

## E. 未验证清单（诚实标注）

1. **没有实机**：本地形/标记/描边的**任何**行为都没有进游戏点过；本报告全部是源码阅读 + 交叉对照。
2. `Level#getBlockState` 对未加载区块是否触发加载：**未 javap 验证**（C-6 标〔推断〕）。
3. `getEntitiesOfClass` 在大 AABB 上的实际代价：**未 profile**（C-7 标〔推断〕）。
4. 用户当前跑的版本：**未知**（最关键的未知，见 F-1）。本文默认讨论「beta.14 之后」。
5. 旧格式 `Item` 标记在真实存档里是否还存在（决定 C-5 的可达性）：未知。
6. `MAX_ENTRY_BYTES` 的「堆→线上 ≈8 倍」是工程实测经验值，不是精确换算（`MarkerBudget.java:12-63` 已自述）。
7. `AREA_QUERY_BUDGET_PER_TICK = 2048` 在满负载（64 区域 × 128³）下的 tick 影响：未测。

---

## F. 需要用户一句话拍板的问题清单（最多 3 条）

**F-1（最优先）你现在跑的是哪个版本？**
　如果 `1.2.0-beta.13` 或更早 ⇒ 「标记必须先放进方块」**是真的**，升级到 beta.14+ 就解决，
　本轮不需要写代码；如果已经是 beta.14/15/16 或更新 ⇒ 说明剩下的不是这件事，请回答 F-2。

**F-2「标记 / 单独标记」你指的是哪一种？**（选一个就够）
　(a) **在世界里只高亮一个区域**（现在关掉 GUI 会变成「全部一起亮」）；
　(b) 给某区域配**过滤名单**（新版已能，需确认版本）；
　(c) 给区域**起名字 / 做标签**并想在世界上看见；
　(d) 想按**区块**圈范围，而不是点方块；
　(e) 想让**别的机器**（保持器 / 装填器）也能拿区域当目标。

**F-3 一台机器同时挂多套区域（多个选定器槽），是必需还是可以不做？**
　现在是一台机器 = 一个选定器 = 最多 16 块区域（每块可独立命名 / 独立过滤）。
　如果要「多套」，改动面会从「UI 级」升级到「扫描 + 存档 + 界面全动」，需要你确认值不值。
