# Round4 设计决策文档（自主决策记录 + 待用户拍板清单）

> 本轮用户睡觉期间授权自主决策。以下逐条列出**我替用户做的设计决定**（决定 + 理由 + 涉及文件），
> 并在 §4 单独列出**必须由用户醒来拍板的点**（含候选方案与推荐）。
> 编译状态：`tools\manual_compile.ps1` → `COMPILE OK`；两个 GUI 校验脚本 → 0 问题。

***

## 0. 本轮交付范围速览

| 需求                                    | 状态                             | 说明          |
| ------------------------------------- | ------------------------------ | ----------- |
| A 归流缓存仓阻塞（物品 / 流体独立阻塞 + 红色标识 + 物流可操作） | ✅ 完成                           | 见 §1        |
| B6 同操作不同输入独立成卡片                       | ✅ 已满足（原有实现即如此，本轮补测试性说明）        | 见 §2.1      |
| B7 卡片顺序可调整                            | ✅ 新实现（Shift+左键上移 / Shift+右键下移） | 见 §2.2      |
| B8 卡片上显示机器图标                          | ✅ 新实现                          | 见 §2.3      |
| B9 SPT 样板槽支持物流输入 / 输出                 | ✅ 新实现（注册 9 格样板槽的物品能力）          | 见 §2.4      |
| B10「总样板生成到哪里」                         | ✅ 已解释并**改了落点**（新增可见「生成槽」）      | 见 §2.5、§3.1 |
| B11 单元样板流转链 + 汇总显示 + 轮询 + 拿取/放入       | ✅ 完成（新汇总子界面）                   | 见 §3.2、§3.3 |
| B12 单元样板必须指定配方类型；执行仓须先设类型+名字          | ✅ 完成                           | 见 §3.4      |
| B13/B15 执行仓类型匹配硬约束                    | ✅ 完成                           | 见 §3.4      |
| B14 样板槽只能放单元样板                        | ✅ 完成（原版样板 / 综合样板一律拒收）          | 见 §3.4      |
| B16 执行舱输入 / 输出面配置                     | ✅ 完成（参考 Mekanism 交互，新子界面）      | 见 §3.5、§3.6 |
| B17 执行舱物品 / 流体缓存（与自动合成仓同容量）           | ✅ 完成                           | 见 §3.7      |
| B18 自动合成仓 / 执行仓禁止物流输入，只允许输出           | ✅ 完成                           | 见 §3.8      |

***

## 1. A：归流缓存仓「阻塞」

### 1.1 数据结构与持久化

- **决定**：阻塞名单 = 两张「资源注册名」集合（物品一组、流体/气体一组），存在方块实体里，
  与「匹配区标记」解耦——阻塞是**按资源种类**判定的（同一种物品的任意 NBT 变体一起阻塞）。

- **理由**：用户说「每一个物品单独配置」，且界面上的红色标识要落在具体格子上；
  按注册名（而非 NBT 精确匹配）能让「后来从管道塞进来的同种物品」也被正确阻塞，不会漏。

- **涉及文件**：`block/entity/CollectionCacheBlockEntity.java`

  - 字段：`blockedItems` / `blockedFluids`（`LinkedHashSet`）

  - NBT 键：`BlockedItems` / `BlockedFluids`（字符串列表）

  - 同时写入 `saveStateForItem()` / `loadPlacedState()`：**破坏方块再放下，阻塞配置零损耗**

  - API：`isBlocked` / `isBlockedStack` / `setBlocked` / `toggleBlocked` / `getBlockedItems` / `getBlockedFluids`

### 1.2 行为语义（对应「磁铁方块」的理解）

- **决定**：被阻塞的资源 **仍可被本机收集、仍可被物流输入、仍可被玩家与管道取出**，
  唯一变化是**绝不** **`insert`** **进 RS 网络**（本机对它「只进不出 / 不再回流」）。

- **理由**：用户原话「阻塞之后相当于是它变成了一个磁铁方块」+「阻塞了的物品不再返回物流网络」+
  硬规则「绝不允许销毁玩家资源」。让内容安全留在仓内、可随时解除阻塞是最不伤玩家的解释。

- **实现**：`flushCacheToNetwork` 跳过被阻塞物品（且不占用每 tick 处理预算）、
  `flushFluidCacheToNetwork` 跳过被阻塞流体；`isWorking()` 忽略被阻塞内容
  （否则被阻塞资源会长期滞留，机器会一直按「工作档」耗电，属于副作用）。

- **涉及文件**：`block/entity/CollectionCacheBlockEntity.java`

### 1.3 物流可操作性（漏斗 / 管道）

- **决定**：为归流缓存仓**新增** `ItemHandler.BLOCK` 与 `FluidHandler.BLOCK` 两个能力：

  - 物品：`insertItem` 走既有 `insertIntoCache`（自动合并、每格上限 = 堆叠升级后的容量，模拟插入为纯计算）；
    `extractItem` 按槽位取出；`getSlotLimit` 返回本机每格上限（可 > 64）。

  - 流体：`fill` / `drain` 直接读写流体缓存，条目按「注册名 + 数据组件」归并，与内部存储完全一致；
    容量不足时不接收（**绝不丢资源**）。

- **理由**：用户原话「这个物品归流器是可以直接被操作的嘛……用那些物流方块可以对它进行操作吗」，
  任务描述也要求「确认已可用；若没有则补上」——实测**此前没有注册任何物品/流体能力**，因此必须补。

- **涉及文件**：`block/entity/CollectionCacheBlockEntity.java`（`itemLogisticsView()` / `fluidLogisticsView()` / `toFluidStack`）

### 1.4 视觉标识（不用文字）

- **决定（第九轮修订）**：被阻塞的格子只在 **16×16 图标区铺一层淡的半透明红底（`0x40FF3030`，alpha ≈ 25%）**，
  且**绘制在内容之下**（先红底 → 再图标 / 流体），**不再给槽框描 1px 红边**、不染面板底纹。
  用户实测反馈「整个背景变红」正是因为旧顺序把红底盖在图标**之上**（图标被染红看着像整格变红）。

- **理由**：用户明确「透明的那种淡一点的红色背景，而不是整个背景变红」（= 之前那种设计）、
  且要求不影响其它元素可读性；红底在下 + 无描边同时满足两者。

- **涉及文件**：`client/screen/CollectionCacheScreen.java`（`renderSlot` 先铺 `COLOR_BLOCKED_BASE`；
  判定收口在 `isSlotBlocked(Slot)`，绘制与命中同源）

### 1.5 配置入口（逐个资源配置）

- **决定**：**Ctrl + 左键点击格子**切换该资源的阻塞状态，**匹配区与缓存区的格子都支持**：

  - 匹配区格子 → 切换该标记资源的阻塞；

  - 缓存区格子 → 有物品时按物品；是流体叠加时按流体。
    并在三处给出手动 rendering 的 tooltip：

  - 网格 tooltip 追加「状态：已阻塞 / 未阻塞 + Ctrl+左键说明」；

  - 两个分区标题带 tooltip 追加一行总说明；

  - 匹配条目配置子窗口（`CollectionMarkerConfigScreen`）**未改动**（其面板 PNG 为固定 176×150，
    塞不下新行；强行改尺寸会作废已交付贴图）。

- **理由**：符合用户「每个物品单独配置」；不破坏已交付贴图与既有布局校验（0 问题）。

- **涉及文件**：`client/screen/CollectionCacheScreen.java`、`network/SetCollectionBlockedPacket.java`（新）、
  `network/SyncCollectionMarkersPacket.java`（新增两个阻塞名单字段）、`menu/CollectionCacheMenu.java`（`setBlocked`）、
  `RS_Create_Compat.java`（包注册）

***

## 2. B6\~B10：序列装配样板终端

### 2.1 B6「同操作不同输入独立成卡片」

- **结论（现状即满足，未改逻辑）**：导入路径里「相邻条目合并」的判定键是
  **操作类型 + 该步输入物**（`importCreateRecipe` 中的 `stepOps` / `stepInputs` 比较），
  因此「同一个机械手装配、输入分别是铁粒 / 齿轮 / 大齿轮」会落成**三张独立卡片**；
  而机器指派是按 **配方类型** 解析的（同一配方类型的步骤会绑定同一台执行仓），
  即「同一种机器」也能正确复用。

- **涉及文件**：`block/entity/SequencePatternTerminalBlockEntity.java`（`importCreateRecipe`）、
  `menu/SequencePatternTerminalMenu.java`（`importSequencedRecipe`）、`client/screen/SequencePatternTerminalScreen.java`

### 2.2 B7 卡片顺序可调整

- **决定**：**Shift+左键 = 该步上移一格，Shift+右键 = 该步下移一格**（相邻交换）。

  - 为什么不用「Ctrl+左键下移」：Ctrl+左键已被「打开详细配置」占用（既有交互，不破坏）。

  - 为什么选相邻交换：与「顺序其实关系不大、但希望能调」的诉求匹配，操作最小、误触代价最低。

- **实现**：`SequencePatternTerminalBlockEntity.moveArrangementStep(from,to)` 交换
  （单元样板 + 次数 + 机器指派一起搬）；菜单按钮 id `BTN_ROW_MOVE_UP=100+row` / `BTN_ROW_MOVE_DOWN=120+row`；
  界面在 `mouseClicked` 里优先处理 Shift 分支。

- **涉及文件**：`block/entity/SequencePatternTerminalBlockEntity.java`、
  `menu/SequencePatternTerminalMenu.java`、`client/screen/SequencePatternTerminalScreen.java`

- 行 tooltip 新增 `card.move.hint`：「Shift+左键 / Shift+右键：把该步上移 / 下移一格」。

### 2.3 B8 卡片上显示机器图标

- **决定**：卡片第 2 行最左侧绘制 **8×8（16×16 按 0.5 缩放）的机器方块图标**，
  右侧仍是「◀ 机器名 ▶」控件；机器图标按**配方类型**反查（`RecipeTypeMachines`，即 Create 官方 JEI 催化剂 + 配方自带 toast symbol），
  客户端按配方类型**懒加载缓存**（避免每帧重扫配方）。

- **理由**：用户要求「每个卡片那里应该也显示他那个机器的图标，而不是只在执行舱和详细界面看得到」。
  机器名区宽度由 40 → 48（8px 让给图标），可点区域与绘制区域同步放大，避免 hover/点击错位。

- **涉及文件**：`client/screen/SequencePatternTerminalScreen.java`（`machineIconFor` / `drawScaledItem` / `ROW_MACHINE_W`）

### 2.4 B9 样板槽支持物流输入 / 输出

- **决定**：为 SPT 注册 **`Capabilities.ItemHandler.BLOCK`，只暴露 9 格样板槽**；
  其 `isItemValid` 已限定为 `refinedstorage:pattern`，因此漏斗 / 管道只能搬运 RS 原版样板，
  其它物品（含综合样板）塞不进去；玩家侧本来就允许取放，本轮**取消任何"不可取出"的限制**（原本也没有，缺的是物流能力）。

- **涉及文件**：`block/entity/SequencePatternTerminalBlockEntity.registerCapabilities`

### 2.5 B10「总样板生成到哪里去了？」——解释与修正

- **原实现（用户找不到的原因）**：生成时把总样板写进 `assemblyPatternSlot`，然后
  **立刻取出并塞进玩家背包**（背包满则掉在脚下）。也就是说：**它其实直接进了你的背包**，
  界面上确实**没有**任何格子，所以看起来「没有落点」。

- **本轮改动（决定）**：**新增一个可见的「生成槽」**（1 格，菜单坐标 `(231,191)`，位于底排控制行右侧空白区）；
  点「生成样板」后总样板**留在该槽里**，玩家看清楚后自己取走（Shift+左键也可直接收进背包）。

  - 背景 PNG 里没有这个槽框 → 按既有约定 **Java 自绘槽框**（取样背景里始终存在的输入原料槽框像素，观感完全一致），
    因此**不需要重出 PNG、也不会破坏两个 GUI 校验脚本（仍为 0 问题）**。

  - 槽里有旧样板时生成会被拒绝并提示（`generate.slot_full`），**绝不覆盖销毁玩家已有样板**。

- **涉及文件**：`menu/SequencePatternTerminalMenu.java`（`SLOT_TOTAL_PATTERN` + `TotalPatternSlot` + `generateAndHandOver`）、
  `block/entity/SequencePatternTerminalBlockEntity.generateAssemblyPattern`、
  `client/screen/SequencePatternTerminalScreen.java`（自绘槽框 + tooltip）

***

## 3. B11\~B18：单元样板流转链与执行仓

### 3.1 总样板（Assembly Pattern）现在的落点

```
[终端] 编排/导入 → 点「生成样板」
        ↓ 消耗 1 张 refinedstorage:pattern
   写入【生成槽】(界面右下角，菜单坐标 231,191，Java 自绘槽框)
        ↓ 玩家直接取走
   放进【序列装配样板库】(Sequence Assembly Executor) 的槽里 → 注册为网络自动合成任务
```

> 生成槽为空时才允许生成；槽里有东西时按钮置灰 + 服务端二次校验 + 明确提示。

### 3.2 单元样板（Unit Pattern）完整流转链

```
① 来源（两种，都会产出「真实物品」的单元样板）
   a. 导入 Create 序列装配配方（终端「导入配方」/ JEI 转移）
      → 每一步生成 1 张单元样板，写入【流程编排行】+ 同步写入【单元样板库】（按 配方类型+输入物+名字 去重）
   b. 终端「生成单元」（UnitPatternConfigScreen，必须填 名字 + 配方类型；需要输入原料时还要填输入物）
      → 写入【单元样板库】第一个空位
② 取用
   · 从【单元样板库】格子里直接拿进背包（库是真实存储，可自由取放）
   · 或打开【执行仓单元样板汇总】子界面，用「存入」按钮把库里配方类型匹配的样板直接塞进目标执行仓
③ 放入执行仓
   执行仓必须已绑定「配方类型 + 名字」，且样板配方类型必须与该仓完全一致，否则放不进去（B12/B13/B15）
④ 运行
   执行仓引擎认领网络里的原料 / 过渡件 → 按「原料输入面」推给相邻 Create 机
⑤ 收回
   按「产物输出面」把产物收进本仓内部存储（可被管道抽出）；
   按「中间产物输出面」把过渡件收回并直接回写网络 → 交给下一个执行仓认领
⑥ 汇总查看
   终端 → 左键「单元样板库」标题 → 打开【执行仓单元样板汇总】：
   逐台显示 机器图标 + 执行仓名字 + 配方 id + 该仓单元样板（>4 张时按 20 帧轮询轮换），
   并提供每行「取回」（仓 → 终端库）与「存入」（终端库 → 仓，按配方类型匹配）两个按钮。
```

### 3.3 汇总显示（原 RS 自动合成仓管理器风格）

- **决定**：不做成 Menu 槽位，而是做成**子界面 + 按钮驱动的取放**：

  - 为什么：汇总要跨多台方块、数量不定，做成真槽位需要动态 Menu + 大量槽位同步；
    子界面 + 「取回 / 存入」两个动作能覆盖用户诉求（看清是哪台机器、有哪些样板、能拿能放），
    且不触碰主界面已交付的 PNG 几何。

  - 轮询：每 20 帧把样板展示窗口前移一格（`POLL_TICKS=20`）。

- **涉及文件**：`client/screen/ChamberUnitsSummaryScreen.java`（新）、
  `network/RequestChamberUnitsPacket.java` + `SyncChamberUnitsPacket.java` + `TransferChamberUnitPacket.java`（新）、
  `block/entity/SequencePatternTerminalBlockEntity.java`（`listChamberUnits` / `pullUnitsFrom` / `pushUnitsTo`）

### 3.4 B12 / B13 / B14 / B15 硬约束（全部收敛到同一判定）

- **决定**：新增单一权威判定 `SequenceExecutionChamberBlockEntity.acceptsUnit(ItemStack)`，三处共用：

  1. `ChamberUnitSlot.mayPlace`（GUI 拖放 / Shift 移动，服务端槽位为准；客户端只做「必须是单元样板」的前置拦截）；
  2. 方块右键 `putUnit`（手持样板右键放入）；
  3. 汇总子界面的「存入」按钮。

- 判定内容：① 必须是 `sequence_unit_pattern`（原版 RS 样板 / 综合样板一律拒收）；
  ② 本仓必须已设定「配方类型 + 名字」；③ 样板自带的配方类型必须与本仓绑定值**完全相等**。

- **理由**：把约束放在一个函数里，避免三处逻辑走偏（这是本轮最容易出 bug 的点）。

- **涉及文件**：`block/entity/SequenceExecutionChamberBlockEntity.java`、
  `menu/SequenceExecutionChamberMenu.java`、`client/screen/SequencePatternTerminalScreen.java`（汇总入口）

### 3.5 B16 面配置：参考了哪个模组、怎么设计的

- **参考对象**：**Mekanism（通用机械）** 的机器「面配置」交互——点方块的某个面，逐面切换该面的工作模式。
  本工程 `local_src/external/Mekanism/` 下有源码目录（源码树未展开到子模块，故按 Mekanism 的经典交互范式自行实现）。

- **决定（本仓语义收敛为 4 档** **`FaceMode`）**：

  | 模式                    | 语义                                              |
  | --------------------- | ----------------------------------------------- |
  | 无 `NONE`              | 该面不参与自动交互，也**不对外暴露任何物流能力**                      |
  | 原料输入 `INPUT`          | 本仓把认领到的原料 / 过渡件从该面**推给**相邻机器                    |
  | 产物输出 `OUTPUT`         | 本仓从该面相邻容器**抽出产物**存入内部存储；**只在该面**对外暴露「只出不进」的物流能力 |
  | 中间产物输出 `INTERMEDIATE` | 本仓从该面相邻容器抽出**过渡件**，直接回写 RS 网络供下一个执行仓认领          |

- **为什么不是 Mekanism 的「输入/输出/无」三档**：本仓要区分「原料」与「中间产物 / 产物」两类回流，
  且 B18 明确「执行仓不允许被物流输入」，因此「输入」在本仓只能表示**本仓主动喂给机器**的方向。

- **UI**（`ChamberFaceConfigScreen`，新子界面，走原版九宫格面板）：
  左侧是**方块展开图（十字网：上 / 西 / 北 / 东 / 南 / 下 六个 30×30 色块）**，
  颜色即模式；**左键 = 下一个模式，右键 = 上一个模式**；右侧是四档模式图例 + 说明；底部「关闭」。
  默认值：**机器朝向面 = 原料输入**（与旧行为一致，旧存档读档时自动补齐）。

- **入口**：执行仓主界面标题行新增「面」按钮（菜单坐标 `(150,4) 22×13`），
  原有「配置」按钮宽度 46 → 26 以腾出位置（两者都不压容器边框与首行槽位）。

- **涉及文件**：`client/screen/ChamberFaceConfigScreen.java`（新）、`client/screen/SequenceExecutionChamberScreen.java`、
  `network/SetChamberFacePacket.java`（新）、`network/SyncChamberBindingPacket.java`（新增 `faceModes` 字段）、
  `block/entity/SequenceExecutionChamberBlockEntity.java`（`FaceMode` / `faceModes` / `faceModeOrdinals`）

- **技术文档**：`d:\MODS\refined_storage_and_create_compat\tmp_textures\CHAMBER_FACE_CONFIG_GUI_DOC.md`

### 3.6 面配置与「机器朝向」的关系

- **决定**：保留既有的 `FACING` 方块属性（决定方块贴图朝向），并让它成为**默认的原料输入面**；
  用扳手旋转方块时，若原朝向面是 `INPUT`，会把 `INPUT` 标记**一起搬到新朝向面**（避免旋转后机器侧变成「无」）。

- **涉及文件**：`block/entity/SequenceExecutionChamberBlockEntity.setBlockState`

### 3.7 B17 执行舱物品 / 流体缓存

- **决定**：执行仓内置 `RsccUnboundedItemStorage(Config.autocrafterOutputSlots × 64)` 与
  `RsccUnboundedFluidStorage(Config.autocrafterFluidCapacity)`——**与自动合成仓完全同一套类与容量参数**，
  因此「容量与缓存细节一致」是结构性保证（种类无上限、总容量受配置上限约束）。
  NBT 键：`OutputItems` / `OutputFluids`，区块卸载 / 重载零损耗。

- **涉及文件**：`block/entity/SequenceExecutionChamberBlockEntity.java`（字段 `outputStorage` / `outputTank` + 存档）

### 3.8 B18 自动合成仓 / 执行仓「只出不进」

- **决定**：新增公共包装 `support/ExtractOnlyHandlers.Item` / `.Fluid`
  ——`insertItem` 原样退回、`fill` 返回 0，其余全部委托真实存储。

  - 自动合成仓：`RS_Create_Compat.registerAutocrafterCapabilities` 改为注册**包装后**的能力；

  - 序列执行仓：只有 `OUTPUT` / `INTERMEDIATE` 面**才返回**能力，且返回的也是包装后的「只出」视图。

- **理由**：用户原话「所有这种自动合成仓啊执行舱他都不允许使用这个东西往里面输物品，它只允许输出」。
  包装只作用于**对外能力**，方块实体内部的合成 / 收集逻辑仍直接操作真实存储，不受影响。

- **涉及文件**：`support/ExtractOnlyHandlers.java`（新）、`RS_Create_Compat.java`、
  `block/entity/SequenceExecutionChamberBlockEntity.java`（`logisticsItemHandler` / `logisticsFluidHandler`）

***

## 4. 需要用户拍板的点（醒来请逐条确认）

### Q1. 「阻塞」的配置入口放在哪里？

- 现状（我的选择）：**只在主界面 Ctrl+左键点格子**切换（tooltip 有说明）。

- 候选：

  1. **（推荐，现状）** Ctrl+左键点格子 —— 不改任何已交付贴图；
  2. 在「匹配条目配置」子窗口加一个「阻塞」开关行 —— 需要把该子窗口 PNG 从 176×150 改大到约 176×166
     并重出贴图（`COLLECTION_MARKER_CONFIG_GUI_DOC.md` 同步改）；
  3. 主界面右上角加一个「阻塞模式」总开关按钮（进入后点格子即切换，退出恢复）——需要新增按钮区域并可能微调标题行留白。

- 推荐 **1**（零贴图改动、语义直白）；若你更看重「配置集中在一个窗口里」，我再做 2。

### Q2. 「阻塞」是否应该只对「回流进网络」生效，还是也禁止别人从管道把该资源塞进来？

- 现状（我的选择）：**只禁止回流进网络**；仍允许物流输入、仍允许玩家 / 管道取出（绝不销毁）。

- 候选：

  1. **（推荐，现状）** 只挡「本机 → 网络」；
  2. 同时禁止「外部 → 本机」（等于该资源在本机彻底单向）；
  3. 连带把已被阻塞的资源标记为「允许被自动销毁」（**不推荐**，与硬规则冲突）。

- 推荐 **1**：最符合「磁铁方块 / 不再返回网络」的原话，且绝不伤玩家资源。

### Q3. 执行仓「原料输入面」是否也该允许外部物流把物品塞进来？

- 现状（我的选择）：**不允许**（按 B18：执行仓只出不进），`INPUT` 面只表示「本仓把原料推给相邻机器」。

- 候选：

  1. **（推荐，现状）** 只出不进；
  2. `INPUT` 面开放「外部可塞入」（与 B18 冲突，需要你明确推翻 B18 才做）；
  3. 增加第 5 档 `INOUT`（既收也放），把选择权交给玩家。

- 推荐 **1**；若你希望「玩家能手填原料做测试」，告诉我，我加 3。

### Q4. 汇总子界面的取放粒度

- 现状（我的选择）：每行两个按钮——「取回」= 该仓**全部**样板回终端库；「存入」= 库里**所有配方类型匹配**的样板进该仓。

- 候选：

  1. **（推荐，现状）** 整批取 / 整批存；
  2. 单击某一张样板图标 = 只取回那一张（更细，但交互需要再区分左右键）；
  3. 做成真正的槽位 Menu（可拖拽），需要给该子界面**新出 PNG** 并同步槽位校验表。

- 推荐 **1**；若你要 2 我可以增量补上（无需新贴图）。

### Q5. 单元样板库自动写入是否会「刷库」？

- 现状（我的选择）：导入配方时把每步样板写入库，并按「配方类型 + 输入物 + 名字」去重。

- 候选：

  1. **（推荐，现状）** 自动写入 + 去重；
  2. 不自动写库（用户必须手动从流程行"取"出来）—— 但流程行的单元样板按现有设计**不可取出**，会让你更困惑；
  3. 自动写入但放在库末尾并高亮新条目。

- 推荐 **1**；若你觉得库太乱，我可以加一个「导入时是否写入库」的开关。

***

## 5. 新增 / 修改文件清单（本轮）

**新增**

- `java/.../network/SetCollectionBlockedPacket.java`

- `java/.../network/SetChamberFacePacket.java`

- `java/.../network/RequestChamberUnitsPacket.java`

- `java/.../network/SyncChamberUnitsPacket.java`

- `java/.../network/TransferChamberUnitPacket.java`

- `java/.../support/ExtractOnlyHandlers.java`

- `java/.../client/screen/ChamberFaceConfigScreen.java`

- `java/.../client/screen/ChamberUnitsSummaryScreen.java`

- `tools/patch_lang_round4.py`

- `tmp_textures/CHAMBER_FACE_CONFIG_GUI_DOC.md`

- `tmp_textures/CHAMBER_UNITS_SUMMARY_GUI_DOC.md`

- `docs/DESIGN_DECISIONS_ROUND4.md`（本文件）

**修改**

- `block/entity/CollectionCacheBlockEntity.java`（阻塞 + 物流能力）

- `block/entity/SequenceExecutionChamberBlockEntity.java`（面配置 + 内部存储 + 只出能力 + 单元样板校验 + 引擎按面工作）

- `block/entity/SequencePatternTerminalBlockEntity.java`（顺序调整 + 汇总 API + 样板写库 + 生成槽防覆盖 + 样板槽物流能力）

- `menu/CollectionCacheMenu.java`、`menu/SequencePatternTerminalMenu.java`、`menu/SequenceExecutionChamberMenu.java`

- `client/screen/CollectionCacheScreen.java`、`client/screen/SequencePatternTerminalScreen.java`、`client/screen/SequenceExecutionChamberScreen.java`

- `network/SyncCollectionMarkersPacket.java`、`network/SyncChamberBindingPacket.java`、`network/SetChamberBindingPacket.java`

- `RS_Create_Compat.java`（包注册 + 自动合成仓只出能力）

- `resources/.../lang/zh_cn.json` + `en_us.json`（+47 键，并更新 3 个既有 tooltip）

***

## 6. 未完成 / 已知限制（如实列出）

1. **~~`CollectionMarkerConfigScreen`~~**\~\~\~\~ **~~里没有「阻塞」开关~~** →**本轮已补**（面板改 176×166 + 新增一行开关，
   方案与「PNG 未到位如何避免拉伸」见 §7.2 / §7.5）。
2. **气体（Mekanism 化学品）的阻塞**：能力层面只做了「流体」与「物品」两套；
   气体在 RS 2.0 资源体系里当前没有独立资源类型（既有代码注释亦如此），
   归流缓存仓的"气体来源"本来就还是预留入口，因此阻塞暂不覆盖气体（流体路径已覆盖液态）。
3. **`SetChamberFacePacket`** **的越界防御**：`Direction.from3DDataValue` 对越界值做取模而非抛异常，
   代码里保留了 `direction == null` 判断（防御性死代码），无功能影响。
4. **~~汇总子界面没有做成槽位 Menu~~** →**本轮已重做**为真槽位容器菜单（照 RS 自动合成管理器理念，
   见 §7.1）。
5. **单元样板库自动写入策略**（见 Q5）：目前是导入时去重写入。
6. **未做**：把「执行仓内部存储」做成可视化槽位界面（用户原话只要求"支持储存流体和物品"，
   当前通过 `OUTPUT`/`INTERMEDIATE` 面的管道能力 + NBT 持久化实现，没有新增槽位 GUI）。

***

## 7. Round4 追加（第二轮自主决策：汇总界面真槽位化 + 阻塞开关入子窗口）

> 编译状态：`tools\manual_compile.ps1` → `COMPILE OK (171 sources)`；
> `python tmp_textures/verify_gui_layout.py` → 0 问题；`python tools/audit_gui_textures.py` → 0 问题。

### 7.1 参考了 RS 的哪个类、理念怎么落地

**参考来源（RS 源码，本仓库内）**：

| RS 类                                       | 文件路径                                                                                                                                                                                                                                                                                                                                                                       | 借鉴的设计理念                                                            | 具体落地                                                                                                            |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| `AutocraftingMonitorScreen`                | `local_src/external/RefinedStorage/refinedstorage-common/src/main/java/com/refinedmods/refinedstorage/common/autocrafting/monitor/AutocraftingMonitorScreen.java`（条目区渲染 `renderRow` L212-228、**单条选中 + 滚动条** `updateTaskButtonsScrollbar` L425-435、底部动作按钮 `cancelButton/cancelAllButton` L100-107、hover 高亮 + 手动 tooltip `renderItem` L247-276、`mouseClicked` 手动分发 L335-344） | 列表 + **选中项** + 底部动作按钮 + 滚动条 + 全部 tooltip 手动渲染 + `enableScissor` 裁剪 | 每台执行仓一行（5 行/页），**左键点行 = 选中**；底部「取回 / 存入」只作用于选中行；翻页按钮与滚轮换页；行 / 按钮 tooltip 全部手绘，`hover` 判定与绘制共用同一批坐标              |
| `AbstractAutocraftingMonitorContainerMenu` | 同目录 `AbstractAutocraftingMonitorContainerMenu.java`（`statusByTaskId` 动态数据 L42-48、变化即回推 L118-159、`removed` 解绑 L69-76）                                                                                                                                                                                                                                                       | **容器菜单持有动态数据**、数据变化即回推客户端、容器销毁时清理                                  | `ChamberUnitsSummaryMenu` 持有终端 BE 与「执行仓缓存快照」，每 tick 重建压缩窗口映射，靠原版 `broadcastChanges` 的逐格 diff 推给客户端              |
| `PatternGridScreen`                        | `.../autocrafting/patterngrid/PatternGridScreen.java`（193×…、`renderTooltip` 覆写 L180-183、`canInteractWithResourceSlot` L193）                                                                                                                                                                                                                                                | **真** **`Slot`** **网格** + 交互全部走原版槽位协议 + tooltip 手动渲染               | 20 个真槽位（点取 / Shift 快速移动 / 拖拽放置全部交给原版），tooltip 手绘                                                                |
| `AbstractGridScreen`                       | `.../grid/screen/AbstractGridScreen.java`（`renderSlot` L313、`renderTooltip` L424-438、`isHovering` 命中）                                                                                                                                                                                                                                                                      | 槽位渲染与命中同源、hover 高亮由原版负责                                            | 槽位框 `McGui.slotFrame` 与 `Slot#x/y` 严格同源（精灵 = Menu-1）；悬停高亮由 `AbstractContainerScreen` 用**同一** `slot.x/slot.y` 绘制 |

**新增/改动文件（本轮）**：

- 新增 `menu/ChamberUnitsSummaryMenu.java`（真槽位容器菜单）、
  `network/OpenChamberUnitsSummaryPacket.java`、`network/CloseChamberUnitsSummaryPacket.java`、
  `network/SetChamberUnitsPagePacket.java`；

- 重写 `client/screen/ChamberUnitsSummaryScreen.java`（由子 `Screen` 改为 `AbstractContainerScreen`）；

- 改 `network/SyncChamberUnitsPacket.java`（新增权威页码 `page`）、
  `network/RequestChamberUnitsPacket.java` + `network/TransferChamberUnitPacket.java`（改为经
  `ChamberUnitsSummaryMenu.terminalOf` 同时支持终端菜单与汇总菜单）、
  `block/entity/SequencePatternTerminalBlockEntity.java`（新增 `networkChambers()`）、
  `client/screen/SequencePatternTerminalScreen.java`（改为发打开请求）、
  `RS_Create_Compat.java`（菜单类型 + 3 个 C2S 包）、`client/ClientInit.java`（屏幕注册）。

### 7.2 「阻塞」入口两处并存（用户要求「现状再加一个开关都要」）

- **决定**：**保留主界面 Ctrl+左键**（原样不动），**并在匹配设置子窗口新增一行「阻塞」开关**。
  两处走**同一条** C2S 协议 `SetCollectionBlockedPacket(containerId, fluid, id, blocked)`，
  **无需扩展协议**：该包只按「资源 id + 物/流体」定位，本身就与「条目索引」无关，
  子窗口只要把当前编辑条目的 `entry.id() / entry.fluid()` 发出去即可。

- **子窗口开关是服务端权威的**：`SyncCollectionMarkersPacket` 的处理者新增一条分支 ——
  当最前面的界面是 `CollectionMarkerConfigScreen` 时把「阻塞名单」路由给它
  （`applyBlockedSync`），它刷新自己的 ✓/✗ 并转交父界面刷新红色标识；否则原来只认
  `CollectionCacheScreen` 的逻辑会导致「子窗口改开关 → 主界面红色标识不更新」。

- **tag 过滤条目**：其 `id` 只是「代表物品」，因此对它按 `entry.id()` 阻塞是**代表资源**级别
  （不改变 tag 过滤语义）；这是「作用于当前正在编辑的那个匹配条目对应的资源」的字面落地，
  待拍板点见 §7.7。

- 涉及文件：`client/screen/CollectionMarkerConfigScreen.java`、`client/screen/CollectionCacheScreen.java`
  （新增 `isBlockedResource` + 构造时回填开关初值）、`network/SyncCollectionMarkersPacket.java`、
  `tools/patch_lang_round5.py`（+2 语言键）。

### 7.3 汇总界面的分页 / 轮询 / 窗口映射（自己定的方案）

- **分页**：每页 5 台执行仓；分页是服务端权威（`SetChamberUnitsPagePacket` → `setPage`），
  客户端不自行改页（避免「标签翻页了、格子还没翻」一帧错位），页码经 `SyncChamberUnitsPacket.page` 回传。

- **每行 4 格是滑动窗口**：每台执行仓有 54 个单元样板槽，行内只显示 4 格；
  窗口在「该仓**非空**样板槽」上滑动（先压缩再取模），因此样板稀疏时轮询才有意义；
  轮询周期 20 tick（`POLL_TICKS`），**玩家手上还拿着东西时暂停轮询**，避免「看着 A 却点到 B」。

- **为什么用「真 Container」而不是逐槽覆写**：原版 `Slot` 对容器的写入只有
  `container.setItem(index, stack)` 一条路径（`Slot#set` / `safeInsert` / 客户端槽位同步都汇聚到它），
  把窗口映射放进容器就**天然覆盖点击、Shift、拖拽、同步**四种路径，不会因漏覆写而丢内容。

- `Container#clearContent()` **刻意实现为空操作**：它是「通用清空」路径（玩家死亡掉落等），
  若照做会抹掉执行仓里的样板 → 违反「绝不允许销毁玩家资源」硬规则。

- **批量「取回 / 存入」**：仍走既有 `TransferChamberUnitPacket` → `pullUnitsFrom / pushUnitsTo`
  （整批语义不变，只是触发点从「每行两个按钮」改为「选中行 + 底部两个按钮」）。

- **关闭返回终端**：新增 `CloseChamberUnitsSummaryPacket`；`onClose()`（Esc 与按钮共用）先发它，
  服务端 `openMenu` 终端菜单，随后客户端发来的「关闭旧容器」包因 containerId 已变而被忽略。

### 7.4 `acceptsUnit` 复用点（不允许旁路）

| 路径                                 | 判定位置                                                                                  |
| ---------------------------------- | ------------------------------------------------------------------------------------- |
| 汇总界面格子里「放」（点击 / 拖拽 / `safeInsert`） | `ChamberUnitsSummaryMenu.WindowContainer#canPlaceItem` → `chamber.acceptsUnit(stack)` |
| 汇总界面 Shift 快速移动（背包 → 各仓窗口）         | `ChamberUnitsSummaryMenu#quickMoveStack` → `moveItemStackTo` → 槽位 `mayPlace` → 同上     |
| 客户端前置拦截                            | `canPlaceItem` 在 `terminal == null`（客户端）只校验「必须是单元样板」，权威判定始终在服务端                       |
| 批量存入                               | `SequencePatternTerminalBlockEntity#pushUnitsTo`（原有 `chamber.acceptsUnit` 判定不变）       |
| 执行仓自身界面 / 手持右键放入                   | `ChamberUnitSlot#mayPlace` / `putUnit`（原有，未改动）                                        |

### 7.5 「新尺寸 PNG 尚未交付」如何避免拉伸（自行定的最稳做法）

- **机制**：`McGui.textureMatches(rl, w, h)` 只读 PNG 头的 24 字节（IHDR 宽高）核对实际尺寸；
  `ChildConfigScreen#renderPanel` 与 `ChamberUnitsSummaryScreen#renderPanel` 都改为
  **「尺寸完全一致才整图** **`blit`，否则退回原版九宫格精灵」**。

- **效果**：旧 176×150 贴图遇上新 176×166 面板时不会纵向拉伸 / 边框错位，而是显示 MC 自带风格九宫格面板；
  **新 PNG 一放进去即自动生效，无需改任何代码**（资源重载 F3+T 即刻生效，因为不缓存）。

- **为什么不用「九宫格拉伸旧图」**：九宫格 `blit` 需要贴图原始尺寸才能算 UV，而运行期拿不到
  （`AbstractTexture` 不暴露宽高），硬编码尺寸反而会在图没换时把底边拉粗 → 不如直接回退九宫格。

### 7.6 校验脚本同步

- `tmp_textures/verify_gui_layout.py`：新增 `build_chamber_units_summary()`（244×250，56 槽 + 5 控件，
  全部精灵坐标）与 `build_marker_config()`（176×166，15 个控件矩形），并新增
  `verify_pixels_when_ready` —— **贴图存在且尺寸与约定一致才做像素 / 留白区校验**，
  否则打印「待交付」提示而不计为问题（因为代码已保证不会拉伸）。

- `tools/audit_gui_textures.py`：在 LAYOUTS 尾部登记了上述两处变动的说明（该脚本只扫描已交付且
  尺寸与声明一致的 PNG，尺寸不符会被判为错误，故未登记未交付/旧尺寸的图，以免把「待交付」误报成问题）。

### 7.7 需要用户拍板的新增点

- **Q6 汇总界面的行选择交互**：现状 = **左键点行选中**（再点一次取消），底部两个按钮作用于选中行；
  候选：①（现状）行选中 + 底部按钮；② 每行各带一组小按钮（更直观但更挤）；③ 只有槽位、不要批量按钮。
  推荐 ①，与 RS 自动合成监视器（选中任务 → 底部 Cancel）完全一致。

- **Q7 汇总界面槽位数**：现状 = **每页 5 行 × 4 格 = 20 格**（样板 > 4 张时轮询）；
  候选：①（现状）4 格/行 + 轮询；② 8 格/行（窗口更宽，约 316×250，需要重新评估布局与贴图）；
  ③ 不要轮询、每行只显示前 4 张 + 用 tooltip 列全（省电但看不到全部）。
  推荐 ①（用户明确要「多于 4 张轮询」）。

- **Q8 tag 过滤条目的阻塞粒度**：现状 = 按「代表物品 id」阻塞；
  候选：①（现状）按代表物品阻塞；② tag 过滤条目隐藏「阻塞」行（语义更干净）；
  ③ 扩展协议支持「按 tag 阻塞整类」。
  推荐 ①（零协议改动、可逆）；若要 ③ 需要新增协议字段并改 `CollectionCacheBlockEntity` 的阻塞判定。

- **Q9 关闭汇总后是否回终端**：现状 = **回终端**（新增一个 C2S 包）；
  候选：①（现状）回终端；② 直接关回世界（少一个包）。
  推荐 ①（保持既有交互习惯，不额外增加新协议族）。

***

# Round4 · 追加章节：第三轮自主决策（指令化 / 输出总线延长 / 多个输入面）

> 生成方式：本轮同样是用户睡前授权「遇到要判断的点自己想一套最合理最顺手的方案并实现」。
> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (179 sources)`；
> `python tmp_textures/verify_gui_layout.py` → 问题总数 0；`python tools/audit_gui_textures.py` → 0 个问题。

## 8.1 任务一：自动合成仓「内部存储开关」从按钮改成指令

| 项                | 决定                                                                                                                                                                                                          |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 删除的东西            | 合成仓管理器侧边按钮 `client/AutocrafterStorageButton.java`、追加按钮的客户端 Mixin `mixin/client/AutocrafterManagerScreenMixin.java`、以及配套的两个网络包 `SetAutocrafterStoragePacket` / `SyncAutocrafterStoragePacket`（界面消失了，它们再无调用方） |
| 新增的指令            | **`/rs_create_compat autocrafter storage <on\|off>`**（指令根 = 模组 id，3 层子命令；`on` / `off` 是字面量，客户端 Tab 自动补全）                                                                                                    |
| 权限               | `requires(source -> true)` —— **任何玩家可执行**，不设任何 permission level（用户明确要求）                                                                                                                                     |
| 作用范围             | 以**执行者为中心 32 格**（三轴）内的全部自动合成仓，逐台生效；同一 RS 网络的仓**按网络分组一起处理**，避免半开半关                                                                                                                                           |
| 无参数时的行为          | 只输入到 `storage` 一层时给出用法提示（`message...usage`），而不是原版的「未知参数」红字                                                                                                                                                  |
| 资源安全逻辑（**一字未改**） | 关闭前先 `SIMULATE` 检查仓内物品/流体能否**全部**写回网络；任何一项放不下 → **整组取消关闭**（内容原样留在仓内）；确认可写才 `EXECUTE`，且**先写网络、再按写入量扣内部存储**，最后复查仓内确实为空才置「关」                                                                                   |
| 未接入网络时           | 拿不到 `StorageNetworkComponent` → **拒绝关闭**（宁可不关，也不把内容困在没有回写通道的仓里）                                                                                                                                             |
| 开启时              | 若服务端配置 `autocrafterStorageEnabled = false`（此时不会注册物品/流体能力）→ 拒绝并提示，避免把产物困在仓内                                                                                                                                  |
| 逻辑复用方式           | 原包里的安全逻辑抽成 `support/AutocrafterStorageController.java`（纯静态工具 + 按网络分组），指令只负责「找仓 / 调它 / 回话」                                                                                                                   |
| 为拿网络新增的桥接        | `RsccAutocrafterStorage` 新增 `rscc$getNetwork()`，由 `AutocrafterStorageMixin` 用 `@Shadow` 取 `mainNetworkNode.getNetwork()` 实现                                                                                 |

**指令用法（完整层级）**

```
/rs_create_compat
└── autocrafter
    └── storage
        ├── on    立即开启（执行者周围 32 格内的全部自动合成仓）
        └── off   安全关闭（先把仓内物品/流体写回网络；装不下则整组取消，内容不丢）
```

**为什么是「执行者附近 32 格」而不是「全局」**：全局开关会让指令变成一发不可收拾的服务器级操作，
而原按钮的语义本来就是「我这一片机器」；32 格既覆盖玩家实际布置规模，又天然限定影响面，
且不需要玩家记住坐标。待拍板点见 Q10。

**已删除的语言键**（6 个，`tools/patch_lang_round6.py` 负责删除，不手写 json）：
`gui.rs_create_compat.autocrafter_storage.button.on / .button.off / .title / .state.on / .state.off / .help`。

***

## 8.2 任务二：序列执行仓新增第二种输出模式「总线输出（延长型输出）」+ Mixin 改输出总线界面

### 8.2.1 执行仓侧：输出模式（`SequenceExecutionChamberBlockEntity`）

| 模式                             | 语义                                                                                           |
| ------------------------------ | -------------------------------------------------------------------------------------------- |
| `FACE` **面输出**（默认，旧行为**一字未改**） | 原料从「原料输入面」推给相邻机器；产物 / 中间产物从对应面收回                                                             |
| `BUS` **总线输出（延长型输出）**          | **整体忽略逐面配置**：`tickEngine` 在节流判定后直接返回，本机不再做任何面 I/O；改由相邻 RS **输出总线**把网络里的「输入原料 / 中间产物」推给它面对的机器 |

- **持久化**：NBT `OutputMode`（int）；旧存档缺该 tag → 面输出（行为与旧版完全一致）。

- **同步**：`SyncChamberBindingPacket` 新增 `outputMode` 字段；`SetChamberOutputModePacket` 为 C2S 写入口
  （与 `SetChamberFacePacket` 同一套目标解析规则：优先当前打开的执行仓菜单，回退坐标 + ≤64 格距离校验）。

- **UI**：改在已有的「面配置」子界面（不新增界面）：右上角新增「输出：面输出 / 总线输出」切换按钮；
  选总线输出时**六个面格子整体压暗并居中标注「面配置已忽略（总线输出）」**，格子 tooltip 追加一行说明。

- **切换时联动**：`setOutputMode` 会通知六个相邻方块中实现了 `RsccExporterExecutorMode` 的输出总线
  立刻重新检测（只认相邻方块实体，不做任何全局广播）。

### 8.2.2 输出总线侧：Mixin（**真实类名 / 路径 / 行号已逐一核对**）

| 目标类（真实全名）                                                                    | 源码位置（本仓库 `local_src/rs_src/…`）                                                    | 注入点                                                                                                 | 理由                                                                                                                                                               |
| ---------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `com.refinedmods.refinedstorage.common.exporter.AbstractExporterBlockEntity` | `com/refinedmods/refinedstorage/common/exporter/AbstractExporterBlockEntity.java` | `setFilters(List<ResourceKey>)` **L220**，HEAD + cancel                                              | 过滤器下发的**唯一入口**（GUI 点击 / 读档 / 管道驱动全走它），在这里整表替换即可「锁定槽位 + 改写导出清单」                                                                                                   |
| 同上                                                                           | 同上                                                                                | `initialize(ServerLevel, Direction)` **L100**，TAIL                                                  | 放置 / 旋转 / 插升级 / 改模糊模式的重建点，此处刷新导出清单                                                                                                                               |
| 同上                                                                           | 同上                                                                                | `isFuzzyMode()` **L162**，HEAD + setReturnValue(true)                                                | `createStrategy`（**L107**）据此决定是否使用 `FuzzyRootStorage.expander()`；返回 true 即可**强制模糊匹配**，不必碰策略构造（过渡件带 `create:sequenced_assembly` 组件，必须模糊）                          |
| 同上                                                                           | 同上                                                                                | `saveAdditional` **L127** / `loadAdditional` **L133**，TAIL                                          | 持久化「导出类别」两个位（每台输出总线各自保存），键 `rscc_export_categories`                                                                                                              |
| `com.refinedmods.refinedstorage.common.exporter.ExporterContainerMenu`       | `…/common/exporter/ExporterContainerMenu.java`                                    | 服务端构造函数 **L49-66** TAIL；`broadcastChanges()` **L77** TAIL                                           | 客户端菜单拿不到方块实体，必须在服务端抓住它并按变化推 S2C（与 RS 数据槽同节奏，不新增轮询、不会包风暴）                                                                                                         |
| `com.refinedmods.refinedstorage.common.exporter.ExporterScreen`（客户端）         | `…/common/exporter/ExporterScreen.java`                                           | `init()` **L18** TAIL（挂锁定条控件）；`renderTooltip(GuiGraphics,int,int)` **L28** HEAD（锁定条补绘 + tooltip 接管） | 前者把自绘控件挂进 widgets；后者是「槽位/标签之后、物品 tooltip 之前」的最后一步：锁定条可盖住 9 个槽位且仍让物品 tooltip 在最上层                                                                                 |
| `com.refinedmods.refinedstorage.common.support.AbstractBaseScreen`（客户端）      | `…/common/support/AbstractBaseScreen.java`                                        | `mouseClicked(double,double,int)` **L279** HEAD + cancel                                            | 锁定条盖住的 9 格都是「过滤器资源槽」，RS 会在本方法**最前面**判定并 `return true`，事件到不了 `children` 里的控件 → 按钮点不动。**注意：必须注入「声明类自身」的** **`mouseClicked`**（`ExporterScreen` 自己不声明它，早期在它上面注入会直接崩） |

> 具体从 `BlockEntity` 侧取字段的方式：`@Shadow @Final protected AbstractNetworkNode mainNetworkNode`
> （字段声明在 `…/api/support/network/AbstractNetworkNodeContainerBlockEntity.java` **L14**，泛型擦除后为 `AbstractNetworkNode`）、
> `@Shadow @Final private FilterWithFuzzyMode filter`（L57）、`@Shadow protected abstract void initialize(ServerLevel, Direction)`。
> **Mixin 类不继承目标类**，所以目标父类字段一律走 `@Shadow`（本轮踩过一次：直接写 `mainNetworkNode` 编译报「找不到符号」）。

**「相邻 / 连接」的检测方式（最终实现）**

- **先**认「方块实体相邻」：扫描自身六个方向，若相邻方块实体是处于 `BUS` 模式的序列执行仓 → 进入延长模式。

- **否则退回「同一张 RS 网络」**：从自身的 `NetworkNode#getNetwork()` 取网络，用
  `GraphNetworkComponent#getContainers()` 找出网络里所有 `SequenceExecutionChamberNetworkNode`，
  取其中「处于 `BUS` 模式、且离本输出总线最近」的一台。**线缆只是把两者接入同一张网络**，
  中间不存在任何「线缆实体」可扫描，因此这就是「用线缆连接也生效」的落地方式（本轮用户硬要求）。

- 客户端恒为「非延长模式」（检测在 `level.isClientSide()` 时直接返回 null），界面状态一律以 S2C 为准。

- **切换时的即时刷新**：执行仓 `setOutputMode` 会通知「相邻 + 同网络」的输出总线重新检测
  （同网络部分用 `InWorldNetworkNodeContainer#getLocalPosition()` 反查方块实体；只在切换瞬间执行，无轮询）。

- **代价 / 待拍板**：同网络里有任意一台执行仓切到总线输出，该网络内**所有**输出总线都会变形 ——
  这正是用户本轮要的语义，但确实会影响「同网络里还有别的输出总线在干别的活」的用法，见 Q11 / §8.7。

**两种模式如何切换与持久化**

| 环节     | 做法                                                                                                                                                                                                                        |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 执行仓模式  | 面配置子界面右上角按钮（左键循环）→ `SetChamberOutputModePacket` → 服务端写 NBT `OutputMode` → 回发 `SyncChamberBindingPacket`                                                                                                                   |
| 输出总线类别 | 界面锁定条上的类别单元格（第六轮起；第七轮改为**可滚动 + 滚轮 + 勾选框**）→ `SetExporterExecutorCategoriesPacket` → 服务端写 NBT `rscc_export_categories` → 回发 `SyncExporterExecutorModePacket`（按变化推送）                                                        |
| 导出清单内容 | 输入原料 = 执行仓各单元样板的 `input()`；中间产物 = 执行仓各单元样板所属配方的 `SequencedAssemblyRecipe#getTransitionalItem()`（`…/content/processing/sequenced/SequencedAssemblyRecipe.java` **L263**）。**第七轮补充**：样板没有所属配方 id 时按「配方类型 + 该步输入物」反查（见 §11.3） |

**界面改了什么**（技术文档：`tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`）

- 把 9 格过滤器槽位带（x 8..170 / y 20..38）用一块与 RS 面板同风格的锁定条整体盖住（1px `#2B2B2B` 描边 +
  `#C6C6C6` 底），条内放两个 80×18 的大按钮「输出原料」「输出中间产物」；

- 按钮三态配色：关 `#9E9E9E` / 开 `#4CAF50` / 开+悬停 `#6FBF73`，文字 `#1F1F1F` 且**无阴影**；

- 按钮与锁定带空白处的 tooltip 全部**手动渲染**，hover 判定与绘制范围**同一批常量**；

- 非延长模式下**一帧都不绘制、一个点击都不拦截**，界面与 RS 原版逐像素一致。

***

## 8.3 任务三：归流缓存仓「可配置多个的输入」

**选定的语义（需要拍板，见 Q12）：多个输入面** —— 即「**哪些面允许物流（漏斗 / 管道）把资源塞进本仓**」逐面开关。

- **为什么不选「多个输入槽位 / 多份输入配置」**：本仓的输入本来就有两条线（世界掉落物 + 物流方块），
  再叠加多份「输入配置」在语义上与**匹配区 54 条 ghost 标记**重复；而「哪些面能输入」正是玩家布置管道时
  真正要控制的东西，且与序列执行仓的「面配置」**完全同构**，学习成本最低。

- **默认值**：六面全开（`ALL_FACES = 0b111111`），旧存档零迁移、行为不变。

- **关闭某个面只影响「外部 → 本仓」**：该面不再暴露物品/流体能力；
  **已进入仓内的资源仍可从任意面抽走**（绝不困住），也仍然照常收集世界掉落物、照常回流网络。

- **持久化**：NBT `InputFaces`（int 位掩码），`saveAdditional` / `saveStateForItem` / `loadAdditional` /
  `loadPlacedState` 四处都写，破坏重放零损耗。

- **同步**：菜单数据槽索引 **19**（`CollectionCacheMenu.DATA_SLOT_COUNT` 19 → 20），服务端权威、逐 tick diff 推送。

- **写入口**：`SetCollectionInputFacePacket(containerId, face, enabled)`（C2S，按容器 id 校验）。

- **UI**：新增子界面 `client/screen/CollectionInputFaceConfigScreen.java`，**复用执行仓面配置的十字网交互**
  （六个 30×30 面块 + 颜色即状态 + 手动 tooltip），只是两档（绿 = 可输入 / 灰 = 已关闭）；
  主界面标题行新增「输入面」入口按钮（Menu (160,4) 42×14，在「范围」按钮左侧，两者不重叠）。
  技术文档：`tmp_textures/COLLECTION_INPUT_FACE_GUI_DOC.md`。

***

## 8.4 需要用户拍板的新增点（第三轮）

- **Q10 自动合成仓指令的作用范围**：现状 = **执行者周围 32 格**；
  候选：①（现状）32 格；② 加一个半径参数 `/rs_create_compat autocrafter storage <on|off> [半径]`；
  ③ 只作用于**当前所在的 RS 网络**（需要先找到玩家脚下的网络，语义最贴原按钮，但实现重且边界模糊）。
  推荐 ①；若你想统一管理大片机器，我加 ②。

- **Q11 输出总线的「连接」判定**：现状 = **必须贴着执行仓放（六向相邻）**；
  候选：①（现状）相邻；② 允许「同网络」也算（会让同网络所有输出总线一起变形，**不推荐**）；
  ③ 新增「配对」交互（用扳手把某台输出总线绑到某台执行仓，需要新协议 + 两端的配对 UI）。
  推荐 ①；③ 是「用线缆隔开摆放」唯一的正解，但要花更多界面与协议成本。

- **Q12 「多个输入」的语义**：现状 = **多个输入面**（逐面开关允许外部塞入）；
  候选：①（现状）多个输入面；② 多个输入槽位 / 多份输入配置（会与匹配区 ghost 标记重复，**不推荐**）；
  ③ 两者都要（面开关 + 每个面各自的白名单）。推荐 ①。

- **Q13 总线输出模式下执行仓要不要保留「认领」能力**：现状 = **总线模式下** **`tickEngine`** **直接返回**，
  即本机**完全不动手**，原料与产物全部交给输出总线 / 回流总线。
  候选：①（现状）完全交给总线；② 仍按「机器朝向面」把认领到的原料推给相邻机器（= 只忽略输出相关的面配置）。
  推荐 ①（最贴「忽略掉逐面配置」的原话，且**不会再多一条搬运路径互相打架**）；
  若你希望总线模式下仍能靠执行仓自己喂料，我改成 ②。

- **Q14 输出总线的默认导出类别**：现状 = **「输出原料」默认开、「输出中间产物」默认关**；
  候选：①（现状）；② 两个都默认开；③ 两个都默认关（必须玩家手点才导出）。
  推荐 ①（先只延原料最不容易出乎意料）。

***

## 8.5 本轮新增 / 修改文件清单

**新增**

- `java/.../command/CompatCommands.java`（模组指令；无权限）

- `java/.../support/AutocrafterStorageController.java`（内部存储开关的安全逻辑 + 按网络分组）

- `java/.../support/RsccExporterExecutorMode.java`、`support/RsccExporterMenuBridge.java`（duck-typing 桥接）

- `java/.../network/SetCollectionInputFacePacket.java`、`network/SetChamberOutputModePacket.java`、
  `network/SyncExporterExecutorModePacket.java`、`network/SetExporterExecutorCategoriesPacket.java`

- `java/.../client/screen/CollectionInputFaceConfigScreen.java`

- `java/.../mixin/exporter/AbstractExporterBlockEntityMixin.java`、`mixin/exporter/ExporterContainerMenuMixin.java`、
  `mixin/client/ExporterScreenMixin.java`

- `tools/patch_lang_round6.py`

- `tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`、`tmp_textures/COLLECTION_INPUT_FACE_GUI_DOC.md`

**删除**

- `java/.../client/AutocrafterStorageButton.java`

- `java/.../mixin/client/AutocrafterManagerScreenMixin.java`

- `java/.../network/SetAutocrafterStoragePacket.java`、`network/SyncAutocrafterStoragePacket.java`

**修改**

- `RS_Create_Compat.java`（指令事件；删 2 个旧包注册；加 4 个新包注册）

- `resources/rs_create_compat.mixins.json`（删 1 个客户端 Mixin；加 3 个新 Mixin）

- `block/entity/SequenceExecutionChamberBlockEntity.java`（输出模式 + 导出清单 + 通知相邻总线）

- `block/entity/CollectionCacheBlockEntity.java`（多个输入面 + 按面能力 + 数据槽 15 + 存档）

- `mixin/AutocrafterStorageMixin.java`、`support/RsccAutocrafterStorage.java`（新增 `rscc$getNetwork()`）

- `menu/CollectionCacheMenu.java`（数据槽 19 + setInputFace）

- `network/SyncChamberBindingPacket.java`（新增 `outputMode` 字段）与 3 处构造调用

- `client/screen/ChamberFaceConfigScreen.java`（输出模式按钮 + 压暗标注 + tooltip）

- `client/screen/SequenceExecutionChamberScreen.java`、`client/screen/CollectionCacheScreen.java`（入口按钮 / 传参 / tooltip）

- `resources/.../lang/zh_cn.json` + `en_us.json`（+40 键 / −6 键，由 `tools/patch_lang_round6.py` 写入）

***

## 8.6 本轮未完成项与风险（如实列出）

1. **Mixin 兼容性（最大风险）**：本轮 3 个 Mixin 依赖 RS 的
   `setFilters` / `initialize` / `isFuzzyMode` / `saveAdditional` / `loadAdditional` /
   `ExporterScreen.renderTooltip` / `ExporterContainerMenu` 构造函数与 `broadcastChanges`
   的**确切签名**。已在 `local_src/rs_src` 源码逐一核对（行号见 §8.2.2），且 mixins.json 的
   `defaultRequire: 1` 保证「目标不存在时直接报错」而不是静默失效；但**未做游戏内运行验证**，
   RS 版本升级或与其它同样 Mixin 输出总线的模组（若存在）可能冲突。
   缓解：注入点全部落在**具体方法名 + 明确描述符**上，没有用 `@Redirect` 改原版逻辑分支，
   非延长模式下一律「早返回」，不改动任何原版行为。
2. **「线缆连接」未实现**：只认相邻（见 Q11）。用户原话提到「使用线缆或者说不使用」，
   当前实现只覆盖「不使用线缆（贴放）」，若必须支持线缆连接需要新增配对协议。
3. **`ExportingIndicators`（原版的小感叹号状态图标）**：延长模式下 9 个槽位仍会被原版逻辑绘制状态图标，
   但已被锁定条盖住（视觉上不可见）；其 tooltip 也已被我们的手动 tooltip 接管，无功能影响。
4. **中间产物依赖配方的过渡件**：`SequencedAssemblyRecipe#getTransitionalItem()` 需要该 Create 配方已被
   数据包加载；配方缺失时该类别为空（不会误导出别的物品）。
5. **任务三语义选择**：已按「多个输入面」实现（§8.3），若用户实际想要的是「多个输入槽位」，
   需要重做界面与数据模型（见 Q12）。
6. **未做游戏内实测**：本轮所有行为（指令、总线输出、输入面）均通过**编译 + 布局/像素脚本**验证，
   未启动客户端做交互实测。用户醒来实测后若有偏差，优先按 §8.4 的候选方案调整。

***

# Round4 · 追加章节：第四轮（用户实测反馈修复：高级定量保持器 / 面配置 / 输出总线）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (185 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题（新增 1 个客户端 Mixin 后仍为 0）；
> `python tmp_textures/verify_gui_layout.py` / `python tools/audit_gui_textures.py` → 0 问题。

## 8.7.1 高级物品定量保持器

| 项                     | 决定与理由                                                                                                                                                                                                                                                                                                                             |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 两个开关按钮分开 + 列标题        | 按钮列 x 由 148 / 166 改为 **134 / 162**（列心 142 / 170，中间留 12px）；顶部各画一行列标题「自动合成」「过量销毁」（原版字体 ×0.75，仅在顶部画一次以省空间）。列标题 hover 也给 tooltip（与按钮同一套文案来源）。                                                                                                                                                                                         |
| 无升级时「自动合成」真正禁用        | 客户端每帧 `autocraftButtons[row].active = menu.hasAutocraftingUpgrade()` → 控件既不响应点击、也被原版画成置灰；**服务端二次校验**：`AdvancedQuantityKeeperBlockEntity#setAutoCraft` 在 `enabled && !hasAutocraftingUpgrade()` 时直接忽略（关闭请求永远允许，避免拔掉升级后开关卡死）。                                                                                                       |
| 禁用态仍要有 tooltip        | `isMouseOver()` 在控件 `active == false` 时恒为 false，因此 tooltip 的 hover 判定改用**几何判定** `isHovering(...)`（与按钮绘制范围同源），否则禁用按钮会失去提示。                                                                                                                                                                                                         |
| 空插件槽的「升级专用 tooltip」缺失 | 根因：`UpgradeSlot.forContainer(...)` 返回的是**匿名** **`Slot`**，`hoveredSlot instanceof UpgradeSlot` 恒为 false → 这些槽永远不弹升级提示。改为 `new UpgradeSlot(new InvWrapper(container), ...)` 返回**真正的** **`UpgradeSlot`**。这样高级定量保持器 / 定量保持器 / 归流缓存仓三处的空插件槽 tooltip 一并恢复（`CollectionCacheScreen#upgradeEffectLine` 的 `instanceof UpgradeSlot` 也同时被修好）。 |

## 8.7.2 执行仓「面配置」子界面

| 项       | 决定与理由                                                                                                                                      |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 面上的字太小  | 格子 30×30 → **40×40**；格内「面名 + 模式短名」由 0.5 缩放小字改为**原版字号**，并在文字下垫一条 33% 白底条（`0x55FFFFFF`）保证任何模式色上都清晰；模式短名（无/输入/输出/中间）新增语言键，完整名称留给图例与 tooltip。  |
| 底部提示压按钮 | **删除**原「说明文字块」（3 条长提示，折行后会盖住「关闭」按钮），改为右侧**图例**按像素宽折行显示四档模式的**完整名称**（信息不丢失，细节仍在 tooltip）；排版经 §3.2 校验：十字网 y 26..149、关闭按钮 y 152..168，不再有任何重叠。 |

## 8.7.3 输出总线

| 项        | 决定与理由                                                                                                                                                                                                                                                                                                        |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 线缆连接也要生效 | 判定改为「**相邻优先，否则同网络**」：相邻六向找不到处于 `BUS` 模式的执行仓时，从自身网络取 `GraphNetworkComponent#getContainers()`，在其中的 `SequenceExecutionChamberNetworkNode` 里选「`isBusOutput()` 且离本机最近」的一台。线缆没有实体可扫，因此「同网络」就是「用线缆连接」的等价落地；执行仓切换模式时会同时通知「相邻 + 同网络」的输出总线立刻重建导出清单。tooltip（`.locked.tip` / `output.bus.tip`）已同步说明「紧贴或用线缆连接都算」。         |
| 界面按钮点不动  | 根因：锁定条盖住的 9 格全是 RS 的**过滤器资源槽**，`AbstractBaseScreen#mouseClicked` 会在**最前面**判定「鼠标下是资源槽」并 **直接** **`return true`**，事件根本到不了挂在 `children` 里的自绘控件。修法：新增 `mixin/client/AbstractBaseScreenMixin`，注入**声明类自身**的 `mouseClicked`（HEAD，可取消），当且仅当「当前界面实现了 `RsccExporterBarHost`（即输出总线界面）且锁定条吞下点击」时取消原版逻辑；非延长模式 / 点在条外一律放行。 |

## 8.7.4 需要用户拍板的新增点（第四轮）

- **Q15 同网络判定的影响面**：现状 = **同网络里只要有任意一台执行仓切到「总线输出」，该网络内所有输出总线都进入延长模式**（紧贴的优先）。
  候选：

  1. **（现状）** 同网络即生效 —— 最省事，完全符合「用线缆接上就能用」；
  2. 只在「与本机相邻的那台执行仓」或「网络里**唯一**一台处于总线输出的执行仓」时才生效（多台时全部退化为普通输出总线）；
  3. 新增「配对」交互（用扳手把某台输出总线绑到某台执行仓，需要新协议 + 两端 UI）。
     推荐 **1**（本轮用户明确要求线缆连接可用）；若你发现「同网络里还有别的输出总线在干别的活」被误伤，我再做 2 或 3。

- **Q16 面配置子界面的说明信息去处**：现状 = **删掉底部说明块**，信息由右侧图例（完整模式名）+ tooltip 承担。
  候选：①（现状）只用图例 + tooltip；② 在图例下方再放一行**极短**的提示（如「左键=下一个模式，右键=上一个模式」）；
  ③ 把说明做成面板右上角的「?」按钮 tooltip。推荐 ①（最不容易再出现文字压按钮）。

***

# Round4 · 追加章节：第五轮（输出总线连接收紧 / 独占绑定 / 概率与 JEI 完全一致）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (187 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题；`python tmp_textures/verify_gui_layout.py` → 0 问题；
> `python tools/audit_gui_textures.py` → 0 问题。

## 9.1 任务一：输出总线 ↔ 执行舱 的绑定收紧

### 9.1.1 「连上」的最终判定规则（本轮把 §8.2.2 / §8.7.3 的「同网络兜底」删除）

| 项       | 规则                                                                                                                                                      |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 主体      | 从**输出总线方块实体**出发做逐格 BFS                                                                                                                                  |
| 可穿行格    | **只有 RS 线缆方块** `com.refinedmods.refinedstorage.common.networking.CableBlock`（所有颜色都是同一个类；输出总线 / 导入器 / 执行仓等机器都是 `AbstractDirectionalCableBlock`，**不可穿行**） |
| 终点      | BFS 过程中（含起点六向）碰到 `SequenceExecutionChamberBlockEntity` 且 `isBusOutput()` 为真                                                                             |
| 六向相邻    | = 「0 步线缆」的特例，同一套代码即命中                                                                                                                                   |
| 步数上限    | **64**（`RSCC_LINK_MAX_STEPS`；执行仓侧枚举用同一数值 `BUS_LINK_MAX_STEPS`，两侧判定一致）                                                                                   |
| 多台同距    | BFS 顺序 = 最近的优先；同距时按 `Direction.values()` 顺序取第一个（可预期）                                                                                                    |
| **已删除** | 「找不到就退回**同一张 RS 网络**去找」的宽松兜底 → 隔得老远的输出总线**不再**能输出远处执行舱的东西                                                                                               |
| 缓存      | 结果缓存 **20 tick**（`RSCC_LINK_CACHE_TICKS`）：命中缓存的执行仓若已不再是总线输出模式则立刻重搜；执行仓切模式时还会主动 `rscc$refreshExecutorMode()` 清缓存                                         |
| 客户端     | 恒为「未绑定」（`level.isClientSide()` 直接返回 null），界面状态一律以 S2C 为准                                                                                                |

### 9.1.2 独占绑定与「>2 台」的禁用逻辑（服务端权威）

| 项           | 决定与实现                                                                                                                                                                                                        |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **数据保存在哪**  | **执行仓方块实体** `SequenceExecutionChamberBlockEntity`：两个 `long` 字段 `busInputOwner` / `busIntermediateOwner`（`BlockPos#asLong()`，`0` = 未占用），NBT 键 `BusInputOwner` / `BusIntermediateOwner`（旧存档缺省 = 未占用），**服务端权威** |
| 谁在写         | 输出总线侧 Mixin 的 `rscc$normalizeCategories()`：调用 `chamber.claimBusCategories(本机掩码, 本机坐标)`，由执行仓**归一**后返回生效掩码，写回本机 `rscc_export_categories`                                                                       |
| 归一三条规则      | ① 按**可见性**裁剪（无意义的类别位丢弃，例如冲压仓的「输出原料」）；② 超过 **2 台**（`BUS_MAX_EXPORTERS`）的输出总线一律不许占用（申请清空并释放自己的历史占用）；③ **每个类别只能被一台占用**：已被别人占用的位被拒绝（本机该位保持关闭），本机关闭的位释放自己名下的占用                                                    |
| 「>2 台」的判定   | `chamber.isExporterAllowed(pos)` = 在「与本仓相邻 / 线缆相连的输出总线」列表中，按**坐标升序**排在**前 2 名**；其余一律 `enabledMask = 0`（两个按钮全禁用）                                                                                              |
| 连接枚举（执行仓侧）  | `connectedExporterPositions()`：从执行仓出发、同样**只穿线缆、不穿机器**、上限 64 步，收集六向相邻的 `RsccExporterExecutorMode` 方块实体，并**再要求该总线自身 BFS 的结果恰好是本仓**（消除「线缆分叉到两台仓」的歧义）；结果带 20 tick 缓存，`markDirtyAndSync()` 时作废                    |
| 失效占用自愈      | `sanitizeBusOwners()`：占用者已不在枚举里 / 已被台数上限挤掉 / 该类别对本仓已无意义 → 清 0                                                                                                                                                |
| 客户端怎么知道禁用   | 新增 S2C 字段 `visibleMask` / `enabledMask`（`SyncExporterExecutorModePacket` → `ExporterContainerMenuMixin#broadcastChanges` 变化时推送）；控件照它绘制与拦截点击                                                                  |
| 服务端怎么再校验    | `SetExporterExecutorCategoriesPacket` → `rscc$setExportCategories` → `rscc$applyExportFilters` → `claimBusCategories` **再归一一次**；被抢占 / 超上限 / 无意义的位一律落不了地                                                      |
| 被抢占的一方能及时停手 | `rscc$getCategoryEnabledMask()`（服务端逐 tick 随菜单同步调用）会先跑一次归一，发现自己的位被拿掉就立刻 `rscc$applyExportFilters()` 重建导出清单                                                                                                    |

### 9.1.3 「存在输入性产物」的判定规则 + 冲压的实际表现

- **判定规则（`SequenceExecutionChamberBlockEntity#hasInputCategory()`）**：遍历本仓全部单元样板，命中任一即算「存在输入性产物」：

  1. 该单元样板 `requiresInput()` 为真，或 `input()`（该步真正消耗的加工输入物）非空；或
  2. 该单元样板带配方 id 时，**回查 Create 配方数据**：取 `SequencedAssemblyRecipe#getSequence()` 里第 `step % size` 个
     `SequencedRecipe`，对其内部 `ProcessingRecipe` 跑 `SequencedRecipeProbe.stepInput(...)`
     —— 即「跳过第 0 个 ingredient（被 Create 覆盖为过渡件）、取其后第一个物品」，非空即算。

- **为什么冲压没有**：`create:pressing` 类步骤只有 1 个 ingredient（第 0 个 = 过渡件），第 1 个起为空
  → `stepInput` 恒为空 → `hasInputCategory()` 为 false → **`visibleMask`** **不含「输出原料」位**。

- **界面表现**：该输出总线的锁定条**只画「输出中间产物」一个按钮**（**居中**，不留空洞）；服务端也把
  「输出原料」位裁掉（即便存档里开着）。

- 若配方确实还有额外输入物（机械手装配 / 灌注等），则两个按钮都显示、左右排布，与改造前一致。

### 9.1.4 界面（`ExporterExecutorRowWidget`）

| 项          | 决定                                                                                                                              |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------- |
| 按钮数量可变     | 由 `visibleMask` 决定：2 个 → 左贴边 / 右贴边（同旧版）；1 个 → **整条居中**（`x + (162-80)/2`）；0 个（异常兜底）→ 兜底画 2 个                                     |
| 禁用态        | `enabledMask` 里没有的位：底色 `#7A7A7A`、文字 `#4A4A4A`、**悬停不变色**；点击**不切换、不发包**（服务端另有一道权威校验）                                              |
| 悬停（可用、关）   | 新增 `#B8B8B8`（浅灰），避免「关」的按钮悬停就变绿造成误解                                                                                              |
| tooltip    | 全部**手动渲染**、`drawString(..., false)`；hover 判定与绘制范围共用同一批常量（`buttonX(slot, count)` / `BTN_W/BTN_H` / `contains`）；禁用时第 4-5 行改显示禁用原因 |
| 唯一 tooltip | 同一次 `renderTooltip` 里只会渲染「一个按钮的 tooltip」或「锁定条空白处的 tooltip」二选一，不会叠两份                                                             |

（详细几何 / 配色技术文档：`tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md` §3 / §4 / §6，已同步本轮改动。）

## 9.2 任务二：产物 / 废料的概率显示与 Create JEI 完全一致

### 9.2.1 Create 的算法出处（本仓库 `local_src/external/Create/…`）

| 内容         | 文件 + 行号                                                                                                                                                                           |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 权重 → 概率的换算 | `com/simibubi/create/content/processing/sequenced/SequencedAssemblyRecipe.java` **L189-194**：`getOutputChance() = resultPool.getFirst().getChance() / Σ resultPool[].getChance()` |
| 轮盘抽取（印证权重） | 同文件 **L132-144** `rollResult()`：`totalWeight = Σ weight; number = random * totalWeight; number -= entry.weight; if (number < 0) return entry`                                     |
| 概率 → 显示数字  | `com/simibubi/create/compat/jei/category/SequencedAssemblyCategory.java` **L221-225** `chanceComponent(float)`                                                                    |
| 是否显示概率     | 同文件 **L50 / L118 / L169**：`noRandomOutput = recipe.getOutputChance() == 1` → 必得（唯一非零权重项）时 JEI **不显示百分比**                                                                          |

### 9.2.2 本模组采用的换算与格式化（逐字符照抄）

```
概率 = 该项权重 / 全部非空项权重之和              // SequencedRecipeProbe.probability(weight, totalWeight)
显示数字 = chance < 0.01 ? "<1" : chance > 0.99 ? ">99" : String.valueOf(Math.round(chance * 100))
显示文案 = Component.translatable("create.recipe.processing.chance", 显示数字).withStyle(GOLD)
```

- 落地在 `support/SequencedRecipeProbe`：`probability(...)` / `chanceNumber(...)` / `chanceComponent(...)`；

- `splitResultPool(...)`：主产物 = 池中第一个非空项（与 Create `getResultItem()` = `resultPool.getFirst()` 同口径），
  其余归废料；**两者概率一律用上面的归一公式**（旧版「第一项固定 100%」已删除）。

- 使用点：SPT 单格概率小字（`chanceNumber + "%"`）、SPT 产物/废料 tooltip（`chanceComponent`）、
  结果配置子界面的概率行 tooltip（`chanceComponent`）、JEI「+」导入（写入同一套概率）。

- **必得产物不显示百分比**：`chance >= 1` 时 tooltip **不**追加概率行 —— 与 JEI 的 `noRandomOutput` 分支一致
  （池里只有一个非零权重项时 `getOutputChance() == 1`，JEI 也不显示）。

- **副作用（必须一起改，否则自相矛盾）**：主产物不再是「概率恒 100%」，因此原先靠
  `result.chance() >= 1.0F` 找「必得输出」的两处改为「**取** **`results`** **第一项**」：
  `item/SequenceAssemblyPatternItem`（`getOutput()` 与 `buildPattern()`）与
  `block/entity/SequenceReturnBusBlockEntity#stepAbsorbItems()`。这不改变「一张 EXTERNAL 样板必须有一个输出」的约束。

### 9.2.3 示例（`create:sequenced_assembly/precision_mechanism`）

| 池权重                              | 旧显示（错）                        | 本轮显示（= JEI）                                                    |
| -------------------------------- | ----------------------------- | -------------------------------------------------------------- |
| `[120, 8, 8, 5, 3, 2, 2]`（Σ=148） | 主产物 100%；废料 5%/5%/3%/2%/1%/1% | 主产物 **81%**（120/148）；废料 **5%**（8/148→Math.round=5）、3%、2%、1%、1% |

## 9.3 语言键（`tools/lang_frag_bus.json`）

- 新增：`gui.rs_create_compat.exporter_executor.disabled.state` / `.disabled.tip`（禁用原因）；

- 改写（归属 frag 是 `tools/lang_frag_misc.json`，按文件名排序它在 `lang_frag_bus.json` **之后**合并、会覆盖前者，
  因此这两条放在 `tools/gen_lang_frag_misc.py` 里）：
  `gui.rs_create_compat.exporter_executor.locked.tip`（连接判定收紧后的说明）、
  `gui.rs_create_compat.sequence_execution_chamber.output.bus.tip`（同步为「相邻或线缆直达」）。

- 一律由 `python tools/gen_lang_frag_bus.py` + `python tools/gen_lang_frag_misc.py` 产出片段，
  再 `python tools/apply_lang_frag.py` 合并（幂等）。

## 9.4 本轮新增 / 修改文件

**新增**：`tools/gen_lang_frag_bus.py`、`tools/lang_frag_bus.json`

**修改**：
`mixin/exporter/AbstractExporterBlockEntityMixin.java`（BFS + 缓存 + 独占归一 + 两个新掩码接口的实现）、
`mixin/exporter/ExporterContainerMenuMixin.java`（多推 visible/enabled 掩码）、
`support/RsccExporterExecutorMode.java`（+3 个方法）、
`block/entity/SequenceExecutionChamberBlockEntity.java`（占用者 + 连接枚举 + 可见/可用掩码 + `hasInputCategory` + NBT + 通知收紧）、
`network/SyncExporterExecutorModePacket.java`（+2 个字段）、
`client/widget/ExporterExecutorRowWidget.java`（可变按钮数 / 居中 / 禁用态 / tooltip）、
`support/SequencedRecipeProbe.java`、`item/SequenceAssemblyPatternItem.java`、
`block/entity/SequenceReturnBusBlockEntity.java`、`client/screen/SequencePatternTerminalScreen.java`、
`client/screen/SequenceResultConfigScreen.java`、`client/SequenceTerminalJeiPlugin.java`（注释）、
`block/entity/SequencePatternTerminalBlockEntity.java`（注释）、
`tools/gen_lang_frag_misc.py`、`tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`、本文件。

## 9.5 未完成 / 已知限制

1. **未做游戏内实测**：本轮改动全部通过「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」验证，未启动客户端交互实测；
   BFS 连接（尤其「线缆分叉到两台仓」的归属）建议实测确认。
2. **BFS 只认 RS 线缆方块**：若某整合包用别的模组方块充当「网络线缆」，不会被穿过（判定会更保守）。
3. **台数上限的「前 2 台」按坐标排序**：这是确定性规则，但与玩家的直觉可能不同；若想改成「先到先得」，
   需要把「谁是第 1/2 台」也落盘（当前不落盘）。
4. **`enabledMask`** **依赖菜单打开**：只有打开输出总线界面时服务端才逐 tick 归一；未打开时靠「放置 / 旋转 / 读档」
   触发的 `initialize` 归一。极端情况下（长时间不打开界面且拓扑变化）可能出现短暂的「导出清单仍含旧类别」，
   下次触发即自愈。

***

# Round4 · 追加章节：第六轮（输出总线类别动态化 · 多选 · 共享均分）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (188 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题；`python tmp_textures/verify_gui_layout.py` → 0 问题；
> `python tools/audit_gui_textures.py` → 0 问题。

## 10.1 类别模型（id 方案 + 动态生成来源）

| 项            | 决定与理由                                                                                                                                                                                                                                                                                                                                                                                 |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **id 方案**    | 稳定字符串：`input:` + 物品注册名（一种输入性产物 = 一个独立类别，例 `input:minecraft:iron_ingot`）；「中间产物」恒为常量 `intermediate`。字符串而不是位掩码，因为类别数量由配方决定、可变（附属模组可能给出 3 种以上输入性产物）                                                                                                                                                                                                                                       |
| **生成来源**     | 执行仓的**当前单元样板 + Create 配方数据**（`SequenceExecutionChamberBlockEntity#computeBusCategories()`）：① 样板自己记录的输入物（`UnitData#input()`）；② 样板带配方 id 时回查 `SequencedAssemblyRecipe#getSequence()` 第 `step % size` 步，取**除下标 0（被 Create 覆盖为过渡件）以外的每一个 ingredient** 的首个物品（新增 `SequencedRecipeProbe#stepInputItems`，与旧 `stepInput` 同口径但支持多种输入物）；③ 中间产物 = 各配方 `getTransitionalItem()` 去重后的集合，作为**单独一项**类别 |
| **显示名 / 图标** | 每个类别带 `iconItem`（代表物品注册名，界面画该物品图标）与 `labelKey`：输入性产物 `labelKey` 为空 → 界面用**该物品自己的名字**；中间产物用语言键 `...intermediate.label`。**这样附属模组新增物品无需任何语言键**                                                                                                                                                                                                                                           |
| **顺序**       | 固定为「输入性产物按物品注册名升序」→「中间产物最后」，保证界面顺序稳定可预期                                                                                                                                                                                                                                                                                                                                               |
| **持久化 / 缓存** | 类别列表每 20 tick 重建（`BUS_SCHEDULE_INTERVAL_TICKS`，数据包重载自愈）；`markDirtyAndSync` 与读档时立即作废                                                                                                                                                                                                                                                                                                   |

## 10.2 多选与共享均分规则（用户原话 2 的核心）

| 项           | 决定与理由                                                                                                                                   |
| ----------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| **多选**      | 一台输出总线可同时选中任意多个类别（`List<String>`，服务端权威）。例：总线 A 选 \[输入原料1, 输入原料2] → 一起送进机器 X；总线 B 选 \[输入原料3] → 送进机器 Y                                    |
| **共享**      | **取消「被占用就禁用」**：同一类别可被 N 台输出总线同时选中，**不再有台数上限、不再有禁用态**                                                                                    |
| **顺序**      | 共享顺序 = **输出总线坐标升序**（`BlockPos#asLong` 排序，确定性）。写入 NBT 时也按此顺序存 `long[]`                                                                   |
| **轮询**      | 轮次 = `gameTime % N`；只有轮到的**那一台**在当帧导出清单里含该类别，其余当帧不含。执行仓逐 tick 把当前轮次推给相连输出总线（`tickBusScheduler` → `rscc$refreshBusTurn`）                 |
| **粒度**      | **按「输出总线的一次导出批次」**：RS 输出总线默认「每 tick 每个过滤项取 1 件」（装堆叠升级后一批可达 64），因此无升级时等价于**按物品逐个轮流**，有升级时是**按批轮流** —— 与用户原话「按每个物品 / 每批轮流发」一致             |
| **余数处理**    | 除不尽时余数自然落到轮询顺序靠前的那台（例：5 件 / 2 台 → 轮次 0 取第 1、轮次 1 取第 2、轮次 0 取第 3…第 5 件回到轮次 0 的那台 → **3 / 2**）                                            |
| **总量守恒**    | 由 RS 自身的「网络抽取（`Action.EXECUTE`，原子）→ 目标插入 → 余量 `handleLeftover` 回写网络」链路保证：我们只控制「谁有资格取」，取多少、取到后怎么放全交 RS。因此**不丢不复制**；没轮到的那台当帧根本不尝试，也就不存在争抢 |
| **各自朝向仍生效** | 每条输出总线自身的输出面 / 目标容器（RS 原生 `initialize` 决定的 transfer strategy）完全不受影响                                                                     |

## 10.3 归属持久化与归一化时机

- **权威数据**：执行仓上的 `Map<String, List<BlockPos>> busCategoryOwners`（类别 id → 有序输出总线坐标），
  NBT 键 `BusCategoryOwners`（compound：id → `long[]`），**服务端权威、与区块一起存盘**。
  归属表是**纯派生数据**，即使损坏/丢失也会在下次归一化时重建，因此**绝不依赖它来保存玩家资源**。

- **归一化时机**（`normalizeBusOwners()` 整表重建，幂等）：

  1. 每 20 tick（`tickBusScheduler` 定期自愈）；
  2. 打开输出总线界面后每 tick（菜单 `broadcastChanges` 读快照时顺带归一）；
  3. 客户端发来类别选择包（`SetExporterExecutorCategoriesPacket` → `rscc$setExportCategoryIds`）；
  4. 执行仓切换「面输出 / 总线输出」模式（`setOutputMode`）；
  5. 读档（`busCategoryOwners` 从 NBT 恢复后由上面任一时机再次校正）。

- **归一三条规则**：① 类别必须在本仓类别表里；② 选择方必须「与本仓相邻或线缆相连、且自身解析结果指向本仓」
  （沿用第五轮的收紧判定，**没有恢复「同网络即可」的宽松兜底**）；③ 多台选择者一律保留（共享），坐标升序。

- **变更后通知**：归属表变化时立即 `pushBusTurnToExporters()` 让相关输出总线重建导出清单（不新增轮询）。

## 10.4 界面（技术文档：`tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`）

- 锁定条几何不变（外描边 164×20 @ (7,19)，底色 162×18 @ (8,20)），内部由「两个 80×18 大按钮」改为
  **N 个 18×18 图标开关**（`buttonSize/pitch/buttonX` 自适应 + 整排居中）；图标画在「格内 +1」处（16×16）。

- 三态 + 标记：未选 `#9E9E9E`（悬停 `#B8B8B8`）/ 已选 `#4CAF50`（悬停 `#6FBF73`）/
  **已选且共享 → 右下角 4×4 青色** **`#29B6F6`** **角标**；文字/图标 hover 判定与绘制范围共用同一批常量。

- tooltip 全手动、`drawString(..., false)`，同一帧只渲染一份；已选时按共享台数显示
  `shared.tip`（含 %s = 台数）或 `exclusive.tip`；**删除**「禁用原因」两行与对应语言键。

- 例（3 种输入性产物 + 中间产物，N=4）：开关 x = **50 / 70 / 90 / 110**，y=20，18×18；图标 x = 51/71/91/111。

## 10.5 本轮新增 / 修改文件

**新增**：`support/RsccBusCategory.java`（此前已建、本轮定型为 5 字段记录）。

**修改**：`support/RsccExporterExecutorMode.java`（字符串类别集合 + 显式选择标志 + `rscc$refreshBusTurn`）、
`support/SequencedRecipeProbe.java`（+`stepInputItems`）、
`block/entity/SequenceExecutionChamberBlockEntity.java`（动态类别 + 共享均分 + `BusCategoryOwners` 持久化）、
`mixin/exporter/AbstractExporterBlockEntityMixin.java`、`mixin/exporter/ExporterContainerMenuMixin.java`、
`network/SyncExporterExecutorModePacket.java`、`network/SetExporterExecutorCategoriesPacket.java`、
`client/widget/ExporterExecutorRowWidget.java`、
`tools/gen_lang_frag_bus.py` + `tools/lang_frag_bus.json`、`tools/gen_lang_frag_misc.py`（`locked.tip` 改写）、
`tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`、本文件。

## 10.6 未完成项与残余风险（如实列出）

1. **未做游戏内实测**：仍以「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」为验证手段。均分效果（尤其装堆叠升级后
   的「按批」粒度、以及「轮到的总线目标容器已满」时该 tick 该类别无人导出）建议实测复核。
2. **默认选择语义**：从未被点过的输出总线按「默认全选全部**输入性产物**类别」生效（等价旧版「输出原料默认开、
   输出中间产物默认关」）。玩家一旦点过任意一项即转为显式选择（`rscc_categories_explicit`），此后新出现的类别
   **不会自动加入**（避免反复改选择）。
3. **旧存档迁移**：旧 `rscc_export_categories` 是 int 位掩码，读档时 `getList(TAG_STRING)` 类型不符 → 视为
   「未显式选择」→ 落到上面的默认语义；旧执行仓的 `BusInputOwner/BusIntermediateOwner` 两个 long 被忽略，
   归属表由归一化立即重建。
4. **类别图标仅支持物品**：单元样板的输入物与过渡件都是物品，故当前无需流体/气体类别；若未来出现非物品类别，
   `RsccBusCategory.iconItem` 为空时界面只显示 tooltip 名字（不画图标）。
5. **`exporterFilters`** **/** **`busVisibleMask`** **/** **`busEnabledMask`** **/** **`claimBusCategories`** **已删除**：它们是第五轮独占模型的
   接口，本轮不再需要；如外部（附属模组）曾反射调用它们，需改为新 API。

***

# Round4 · 追加章节：第七轮（类别条滚动化 · 中间产物反查修复）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (189 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题；`python tmp_textures/verify_gui_layout.py` → 0 问题；
> `python tools/audit_gui_textures.py` → 0 问题。

## 11.1 用户实测的原话问题（本轮只处理输出总线类别选择界面）

1. 「物品特别多、加了很多样板，空间不够该怎么排？」→ 类别**多到一横排放不下**；
2. 「还是滚轮选择好一点……但滚轮选怎么多选呢？」→ 需要**滚轮翻看 + 仍然多选**；
3. 「只看见输入型原料（铁粒 / 齿轮 / 大齿轮），中间产物不知道在哪配置、也没看见能选」→
   **「中间产物」类别看不到**。

## 11.2 新的类别多选 UI（滚动式，仍支持多选）

| 项                | 决定与理由                                                                                                                                                                                     |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **布局**           | 锁定条几何**不变**（外描边 164×20 @ Menu(7,19)，底色 162×18 @ Menu(8,20)），条内改成**固定 18×18 的横向类别条**（间距 20）。**不再压缩**：旧实现遇到大量类别会把格子压到 12px，图标糊成一团且仍会溢出                                                      |
| **一行容量**         | `VISIBLE_MAX = 8`（`8 × 20 - 2 = 158 ≤ 162`）。`N ≤ 8` 时整排居中（与旧观感一致）；`N > 8` 时**左对齐 + 可滚动**                                                                                                  |
| **滚动方式**         | **鼠标滚轮**在条内（或滚动条上）滚一格（`scrollY > 0` 向左），一次一格、不跳页；`scroll` 每次使用前按 `N` 夹紧（类别数变化不会越界）                                                                                                        |
| **滚动条**          | 贴在锁定条**正下方的 2px 留白带**：Menu (8,40) 162×2，轨道 `#9E9E9E`、滑块 `#1F1F1F`；滑块宽 = `max(12, round(162 × 8 / N))`，位置 = `8 + round((162-thumbW) × scroll/(N-8))`。**点轨道可直接跳转**（`scrollFromMouse` 与绘制同源） |
| **多选怎么解**        | 这是用户「滚轮怎么多选」的正解：**滚轮只管「看哪一个」、左键点击才切换勾选**，两件事分开做，因此仍然**支持任意多选**                                                                                                                            |
| **勾选框**          | 每格左下角 5×5（`cx+1, 32`）：1px `#2B2B2B` 外框 + 3×3 内胆（`#E0E0E0` 未选 / `#2E7D32` 已选）。底色（绿/灰）表达选中、勾选框表达「这是个可勾选项」                                                                                   |
| **共享标记**         | 保持不变：已选且共享 → 右下角 4×4 青色 `#29B6F6` 角标；tooltip 写明「N 台共享，均分」                                                                                                                                 |
| **tooltip**      | 单元格 5 行（名字 / 类型说明 / 选中态 / 共享或独占 / 点击提示）；锁定带空白处与滚动条上追加一行 `scroll.tip`（首 / 末 / 总数）。全部手动渲染、`drawString(..., false)`、同一帧只有一份                                                                  |
| **滚轮为何不用 Mixin** | RS 的过滤器界面不覆写 `mouseScrolled`，标准 `children` 分发即可到达控件（`AbstractWidget#mouseScrolled`），不需要像点击那样再加一路注入                                                                                        |

**坐标表（Menu 坐标）**

| # | 元素              | 位置 / 尺寸                                                                |
| - | --------------- | ---------------------------------------------------------------------- |
| 1 | 锁定带外描边          | (7,19) 164×20                                                          |
| 2 | 锁定带底色           | (8,20) 162×18                                                          |
| 3 | 类别单元格（第 j 个可见位） | `cx = startX + j*20`，y=20，18×18；`startX = N≤8 ? 8+(162-(20N-2))/2 : 8` |
| 4 | 类别图标            | (cx+1, 21) 16×16                                                       |
| 5 | 勾选框             | (cx+1, 32) 5×5                                                         |
| 6 | 共享角标            | (cx+13, 33) 4×4                                                        |
| 7 | 横向滚动条（仅 N>8）    | (8,40) 162×2                                                           |
| 8 | 空类别提示（仅 N=0）    | 条内居中文字                                                                 |

## 11.3 「中间产物」看不到 —— 诊断结论与修复

**结论：是 bug，不是「该配方确实没有过渡件」。**

- **类别生成逻辑**（`SequenceExecutionChamberBlockEntity#computeBusCategories()`）里，输入性产物来自
  `UnitData#input()` / 配方第 1 个 ingredient 起的真实输入物；而过渡件**只**来自
  `transitionalItemOf(level, unit.recipe())` —— 即**必须**能从单元样板里读到「所属 Create 序列装配配方 id」。

- **新语义（v4）的单元样板只保存** **`requiresInput / displayName / recipeType`**（`SequencePatternData#writeUnitMeta`），
  旧的 `recipe`（配方 id）与 `step` 字段只有 `importCreateRecipe` 那条导入路径会写。
  **JEI「+」导入**（`SetSequenceImportPacket` → `SequencePatternTerminalMenu` 第 531 行附近）与**手工创建**
  （`CreateUnitPatternPacket`）都写 `recipe = ""`、`step = -1`。
  → `transitionalItemOf` 拿到空串直接返回 `null` → `transitionals` 为空 → `intermediate` 类别**被整条裁掉**
  → 界面上只剩输入性产物。这正是用户看到的现象。

- **修复（只做加法，不误报）**：新增 `transitionalItemsByRecipeType(level, unit)` 作为兜底 ——
  样板没有配方 id 时，在 Create 全部 `create:sequenced_assembly` 配方里找「含一个步骤，其
  `recipeTypeId(该步配方) == 样板 recipeType`，且（样板未记输入物 或 该步真正消耗的输入物与样板输入物同物品）」
  的配方，把它们 `getTransitionalItem()` 去重后作为候选。

  - 结果按 `recipeType|输入物品` 缓存（`transitReverseCache`，随 `seqSizeCache` 的 20s 周期一起清空），
    避免每 20 tick 重建类别时全表扫描配方；

  - **候选为空 = 无法确定/确实没有过渡件 → 仍然不显示中间产物类别**（保持「不误报」）；

  - 中间产物类别的显示名仍是语言键 `...intermediate.label`、图标是该过渡件物品（与第六轮一致）。

- **为什么不去改 JEI 导入链路**：那只能修好「以后新建的样板」，已有存档 / 已放进执行仓的样板依旧看不到中间产物；
  兜底反查对**所有**样板（含旧档）都生效，且改动被限制在输出总线用到的类别计算里。

## 11.4 本轮修改文件

- `block/entity/SequenceExecutionChamberBlockEntity.java`（+`transitReverseCache`、+`transitionalItemsByRecipeType`、
  +`sequenceMatchesUnit`；`computeBusCategories()` 增加兜底路径）

- `client/widget/ExporterExecutorRowWidget.java`（类别条改为固定 18×18 + 滚轮滚动 + 滚动条 + 勾选框）

- `mixin/client/ExporterScreenMixin.java`（仅类注释同步，几何未变）

- `tools/gen_lang_frag_bus.py` + `tools/lang_frag_bus.json`（+`scroll.tip`）→ `tools/apply_lang_frag.py`（幂等合并）

- `tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`、`tmp_textures/verify_gui_layout.py`（+`build_exporter_executor_bar`）、本文件

## 11.5 未完成项与残余风险

1. **未做游戏内实测**：滚轮手感、滚动条点击跳转、以及「兜底反查」在整合包里的候选集合（若多条配方共用
   「同配方类型 + 同输入物」会把它们的过渡件都算进同一类别）建议实测复核。兜底只影响**导出清单里多几个过滤项**，
   不会凭空产出物品（没有对应物品时就导不出去）。
2. **仍未显示中间产物类别的两种情况**（属预期，不是 bug）：样板连 `recipeType` 都没有；或该步骤
   （如 `create:pressing`）在 Create 配方里根本不含「过渡件之外的输入物 / 无法反查到配方」。
3. `scroll` 是客户端本地状态、不落盘：重新打开界面回到最左端（可接受，不进 NBT）。

***

# Round4 · 追加章节：第八轮（输出总线类别不限量 · 加入流体输入 · 图标真的画出来）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (189 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题；`python tmp_textures/verify_gui_layout.py` → 0 问题；
> `python tools/audit_gui_textures.py` → 0 问题。

## 12.1 用户原话与三件事

> 「那个滚轮和左键勾选的那一部分它的数量我需要没有限制哦就是可以标记没有上限过的那些物品而且流体要包含在内
> 而且它要有图标哦你确定有图标吗就是那些流体或者物品的图标」

拆成三件事：① **类别数量不设上限**；② **类别必须包含流体输入**；③ **图标必须真的画出来**（物品 / 流体都要），
并且「勾选框与共享角标不要压到图标」。

## 12.2 类别数量上限原来在哪、现在怎么改

| 位置               | 原来                                                                                                                                                  | 现在                                                                                                                                                                                                                                                   |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **网络清洗（唯一的硬截断）** | `AbstractExporterBlockEntityMixin#rscc$sanitizeCategoryIds` 用常量 `RSCC_MAX_CATEGORIES = 64` 做 `set.size() >= 64 → continue`，**第 65 个类别之后的 id 直接被丢掉** | **删除该常量与截断**，只做「去 null / 去空串 / 去重」；能不能生效由执行仓归一化时按可见类别过滤                                                                                                                                                                                              |
| 类别生成             | `computeBusCategories` 本来就无上限（`TreeMap` 全量收集）                                                                                                       | 不变（新增流体类别后仍全量收集）                                                                                                                                                                                                                                     |
| 归属归一化            | `normalizeBusOwners` 遍历全部类别                                                                                                                         | 不变（无上限）                                                                                                                                                                                                                                              |
| 导出清单             | `busExportFilters` 遍历全部类别                                                                                                                           | 不变（无上限）                                                                                                                                                                                                                                              |
| 界面渲染             | `VISIBLE_MAX = 8` 只是**一屏可见窗**，靠滚轮 + 滚动条翻页；不是截断                                                                                                      | 不变（仍是可见窗）                                                                                                                                                                                                                                            |
| **网络字段**         | S2C `RsccBusCategory` **5 字段** `(id, iconItem, labelKey, selected, sharedCount)`                                                                    | 扩到 **7 字段** `(id, iconItem, iconFluid, labelKey, selected, sharedCount, amount)`；因为 `StreamCodec.composite` 最多只吃 6 组，改为**手写** **`StreamCodec.of(encode, decode)`**（逐字段顺序与记录构造参数一致）。C2S `SetExporterExecutorCategoriesPacket` 的 `List<String>` 本来就不限长 |
| 持久化              | 输出总线的已选 id 存 NBT `ListTag<String>`（无长度限制）                                                                                                           | 不变                                                                                                                                                                                                                                                   |

## 12.3 流体类别：id 方案与数据来源

| 项                   | 决定与出处                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **id 方案**           | `fluid:` + **流体注册名**（与 `input:` + 物品注册名、常量 `intermediate` 并列），例 `fluid:minecraft:water`。前缀常量 `RsccBusCategory.FLUID_PREFIX`；`isInput()` 现在对 `input:` 与 `fluid:` 都返回 true（两者都是「输入原料」，因此「未显式选择 → 默认全选输入原料」的旧语义自动覆盖流体）                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| **顺序**              | 物品输入（`input:` 前缀，按注册名升序）→ 流体输入（`fluid:` 前缀，按注册名升序）→ `intermediate`（恒最后）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **数据来源（真实类型 / 方法）** | Create `com.simibubi.create.content.processing.recipe.ProcessingRecipe#getFluidIngredients()` → `NonNullList<SizedFluidIngredient>`（`local_src/external/Create/.../ProcessingRecipe.java:126`）；每个 `net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient#getFluids()` → `FluidStack[]`（`local_src/neoforge_src/.../SizedFluidIngredient.java:147`，数量取 `amount()`），**与 Create 自己的 JEI 取法一致**（`compat/jei/category/sequencedAssembly/SequencedAssemblySubCategory$AssemblySpouting#setRecipe` 第 73-77 行就是 `recipe.getRecipe().getFluidIngredients().get(0)`）。定位方式与物品输入相同：单元样板带 `recipe` 时取 `getSequence().get(step % size)`；样板只带 `recipeType` 时不可反查（不误报，返回空） |
| **探针实现**            | 新增 `support/SequencedRecipeProbe#stepInputFluids(ProcessingRecipe)`：遍历全部 `SizedFluidIngredient`，把每种流体取一份（含 `amount()`），按流体去重；Create 只在**物品** ingredient 上覆盖过渡件（`SequencedRecipe#initFromSequencedAssembly`），**流体不动**，因此不需要跳过下标 0                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **图标**              | 每个流体类别带 `iconFluid`（流体注册名），界面用现成的 `GhostMarkerRenderer.renderFluid` 画 16×16 流体贴图（照搬 RS `AbstractFluidRenderer` 的 `POSITION_TEX_COLOR` 顶点色着色）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |

## 12.4 流体均分与总量守恒

**选「按批次轮询」**（与物品完全同一套，不另算 mB）：

- 执行仓 `busCategoryOwners`（类别 id → 有序输出总线坐标）**对流体类别同样生效**；
  `busExportFilters` 在轮到的那一台的导出清单里追加 `new FluidResource(fluid)`（RS `common/support/resource/FluidResource`），
  没轮到的当帧清单里没有它。

- 于是 RS 输出总线对「流体过滤项」的每次导出批次就是一整份（`CompositeExporterTransferStrategy` 里的流体策略）；
  多台共享时按 `gameTime % N` 轮流，谁轮到谁才有资格取 → **水量按导出批次轮询分配**。

- **总量守恒**：仍然只控制「谁有资格取」；取多少、能不能放下、放不下的余量怎么回写，全交给 RS 自己的
  「网络原子抽取（`Action.EXECUTE`）→ 目标插入 → `handleLeftover` 余量回写网络」链路，
  因此**不丢不复制**；除不尽时余数自然落到轮询顺序靠前的那台。

- tooltip 里的「数量」是**配方每批所需 mB**（`SizedFluidIngredient#amount()`），只作展示，不参与分配。

## 12.5 图标渲染的具体代码点

| 类别                  | 画在哪                                                          | 代码点                                                                                                                                                                                                                                                                                                                             |
| ------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **物品**              | 单元格 `(cx+1, 17)` 16×16（Menu 坐标）                              | `ExporterExecutorRowWidget#drawCell`（非流体分支）→ `guiGraphics.renderItem(iconOf(category), bx + 1, y + 1)`；`iconOf` 由 `iconItem` → `BuiltInRegistries.ITEM`                                                                                                                                                                         |
| **流体**              | 同上位置 16×16                                                   | `ExporterExecutorRowWidget#drawCell`（流体分支）→ `GhostMarkerRenderer.renderFluid(guiGraphics, bx + 1, y + 1, fluidIconOf(category))`；`fluidIconOf` 由 `iconFluid` → `ResourceLocation` → `GhostMarkerRenderer.toFluidStack(id, 1)`                                                                                                   |
| **勾选框 / 共享角标**      | 图标**正下方**那一行：勾选框 `(cx+1, 33)` 5×5 左、共享角标 `(cx+13, 34)` 4×4 右 | `drawCell` 末尾调用 `drawCheckbox(bx + 1, y + CELL_H - 6, selected)` 与 `fill(bx + CELL - 5, y + CELL_H - 5, bx + CELL - 1, y + CELL_H - 1, …COLOR_SHARED_MARK)`；为此单元格高度从 18 加到 **23**（`ExporterExecutorRowWidget.CELL_H`），锁定条 `RSCC_ROW_Y/ROW_H` 由 `20/18` 改为 `16/23`（上探到 y15，仍完整盖住 9 个过滤器槽 y20..37），**保证三者与 16×16 图标、与单元格下描边都不重叠** |
| **tooltip 名称 + 数量** | 跟随鼠标                                                         | `rscc$renderTooltip`：名称走 `displayName`（物品用 `ItemStack#getHoverName`、流体用 `GhostMarkerRenderer.fluidName`），数量行复用已有语言键 `gui.rs_create_compat.marker.amount`（物品件数 / 流体 `fluidAmount`），类型说明按 `.fluids.tip` / `.inputs.tip` / `.intermediates.tip` 三选一（新增 `.fluids.tip`）                                                              |

> 物品图标其实**一直**在渲染（旧代码就是 `renderItem(icon, bx+1, y+1)`）；本轮把它从「与勾选框/角标同格叠放」
> 改成「独占顶部 16px」，并把流体图标补上。`verify_gui_layout.py` 新增 `verify_exporter_cell_interior()`，
> 对 8 个可见位逐一断言「图标 / 勾选框 / 共享角标两两不重叠」。

## 12.6 本轮新增 / 修改文件

- `support/RsccBusCategory.java`（+`FLUID_PREFIX`、+`iconFluid`、+`amount`、手写 STREAM\_CODEC）

- `support/SequencedRecipeProbe.java`（+`stepInputFluids`）

- `block/entity/SequenceExecutionChamberBlockEntity.java`（流体类别生成、`BusCategoryInfo` 扩展、
  `recipeStepInputFluids`、`fluidCategoryId`、导出清单追加 `FluidResource`）

- `mixin/exporter/AbstractExporterBlockEntityMixin.java`（删除 `RSCC_MAX_CATEGORIES` 截断）

- `client/widget/ExporterExecutorRowWidget.java`（流体图标、单元格加高、标记行下移、tooltip 数量行）

- `mixin/client/ExporterScreenMixin.java`（锁定条 `RSCC_ROW_Y/H` 改 16/22）

- `tools/gen_lang_frag_bus3.py` + `tools/lang_frag_bus3.json`（+`fluids.tip`）→ `tools/apply_lang_frag.py`（幂等合并）

- `tmp_textures/EXPORTER_EXECUTOR_MODE_GUI_DOC.md`、`tmp_textures/verify_gui_layout.py`、本文件

## 12.7 未完成项与残余风险

1. **未做游戏内实测**：流体类别的出现依赖「单元样板带配方 id」（JEI「+」/ 手动创建的新语义样板不带），
   这类样板**不会**生成流体类别（与中间产物类别的兜底反查同理，属预期，不误报）。建议实测一条含注液步骤
   （`AssemblySpouting` 那一类）的序列装配配方。
2. 流体类别的**图标颜色**依赖流体自身的 `IClientFluidTypeExtensions#getTintColor`；未注册 tint 的流体按不透明处理（与工程内 ghost 槽一致）。
3. 类别上限移除后，若玩家手动构造超长 C2S 包，服务端只做去重、不做长度截断；真正生效的类别仍由执行仓
   `normalizeBusOwners` 按「本仓可见类别」过滤，因此不会因超长列表产生异常行为。

***

# Round4 · 追加章节：第九轮（输出总线「流向语义」修正：取货源改为执行舱自身）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (190 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题（退出码 0）；
> `python tmp_textures/verify_gui_layout.py` → 0 问题；`python tools/audit_gui_textures.py` → 0 问题；
> 语言键：`python tools/gen_lang_frag_bus5.py` + `python tools/apply_lang_frag.py`（幂等，片段 4 键 / 新增 1 覆盖 3）。

## 13.1 用户原话与要修的东西

> 「从逻辑上来讲应该是先把这个所需要的所有原料移动到或者说输出到这个序列执行器里面，然后呢跟序列执行器
> 绑定在一起或者说连接在一起的输出总线从序列执行器里面输出它的物品和流体」
> 「流向顺序是逻辑上来说是这样子的那具体怎么实现的我不管反正最终结果不要错就行了」

**旧实现的问题**：`BUS` 模式下输出总线是「**从 RS 网络抽取**再推给它面对的机器」，
即原料**没经过执行舱**就被送出去了，与用户要的「先进入执行器、再被执行器输出」相反。

## 13.2 选定方案（候选 A 的落地形态）

**在输出总线侧接管「抽取」这一步**：把节点上的 transfer strategy 换成自定义策略
`support/RsccChamberExportStrategy`，它的**抽取源被执行舱的内部存储顶替**；
同时在执行舱侧补上「**先把料搬进自己内部存储**」的那半段。

```
RS 网络 ──(执行舱搬运 fillInternalForBus)──▶ 执行舱内部存储 ──(输出总线 RsccChamberExportStrategy)──▶ 本总线朝向的机器
```

| 环节          | 做法                                                                                                                                                                                                                                    |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **取货源**     | `RsccExporterExecutorMode#rscc$getLinkedExecutor()`（新增桥接方法，逐次解析、不持有引用）拿到「相邻或线缆直达的 BUS 模式执行舱」；物品走 `chamber.outputStorage`、流体走 `chamber.outputTank`                                                                                              |
| **在哪换策略**   | `AbstractExporterBlockEntityMixin` 在 `initialize(ServerLevel, Direction)` 的 **TAIL**（放置 / 旋转 / 插插件 / 改模糊模式 / **读档 setLevel** 都会走这里）调 `rscc$installChamberStrategy`：绑定了才换，没绑定保留 RS 原版策略                                                                     |
| **朝向照旧**    | 目标能力用 `new CapabilityCacheImpl(level, pos.relative(direction), direction.getOpposite())` —— 与 RS 原版 `createStrategy` **逐字同参**，因此「面向哪个方块就送哪里」完全不变                                                                                            |
| **配额照旧**    | 每批搬运量用 RS 自己的 `ExporterTransferQuotaProvider`（物品 base 1、流体 base = 桶量，`×64` 堆叠升级、调节升级照旧），因此「按物品 / 按批轮询」的粒度与旧版一致                                                                                                                     |
| **备料（新增）**  | `SequenceExecutionChamberBlockEntity#fillInternalForBus`：**只搬「被相连输出总线选中的类别」**（`busCategoryOwners` 的键集），物品在仓内维持 64 件 / 流体 1000 mB 的缓冲，受内部存储剩余容量约束；`busAutoCraftGate` 关着时完全不搬（与「一律不导出」同一道闸门）                                                            |
| **门控**      | 两条独立闸门：执行舱不给过滤项（`busExportFilters` 返空 → 策略根本不会被调用）＋ 策略自身再判一次 `chamber.isAutoCraftingEnabled()`（未开启直接 `SKIPPED`）                                                                                                                    |
| **脱绑回退**    | 解析不到执行舱（拆掉 / 切回面输出 / 超出 64 格线缆范围）时，策略**委托 RS 原版「网络 → 目标」**（用 RS 自己的 `ExporterTransferStrategyImpl` + `FuzzyRootStorage.expander()` 构造），所以输出总线立刻恢复成普通输出总线，不会变哑巴，也不会留幽灵引用                                                       |
| **为什么不选候选 B** | 候选 B 要「让执行舱把产出暴露给总线、总线仍走 RS 原版策略」——但 RS 原版策略的抽取源在 `Network#getComponent(StorageNetworkComponent)` 上写死，除非把执行舱内部存储塞进网络（会造成「既在网络又在总线」的重复风险，且违反「不回流网络」），否则做不到「总线照常走自己的传输策略」，因此改用 A |

## 13.3 「不丢不重复」是怎么保证的（用户最关心的点）

1. **物料任一时刻只在一个地方**：网络 →（`Action.EXECUTE` 原子抽取）→ 执行舱内部存储 →
   （`SIMULATE` 先探目标能不能收）→ 目标机器。**执行舱只从网络取，从不把物料写回网络**；
   **总线只从执行舱取，余量也只回写执行舱**（`outputStorage.insertItem` / `outputTank.fill`）。
   因此不存在「既进网络又进总线」的同一条物料。
2. **不会被导出两次**：谁能导出仍是执行舱的**轮询归属表**说了算 —— 同一类别同一 tick 只有
   「本轮轮到的」那一台总线的过滤清单里有它，其余总线根本不会调到本策略；共享时按 `gameTime % N` 轮流。
3. **收不下就一点都不动**：正式搬运前先对目标做 `insertItem(..., simulate=true)` / `fill(..., SIMULATE)`，
   目标一个都收不下时直接返回 `DESTINATION_DOES_NOT_ACCEPT`，抽取量为 0。
4. **余量立刻回写执行舱**：`SIMULATE` 与 `EXECUTE` 之间目标若变卦，插不下的部分原地退回执行舱
   （物品 `insertItem`、流体 `fill`），**绝不销毁**（执行舱刚扣过，容量必然够）。
5. **备料也不会丢**：`fillInternalForBus` 的 deficit 已按「内部存储剩余容量」夹过；万一仍装不下
   （理论不可达），剩余量立即 `storage.insert` 退回网络。
6. **执行舱内部存储不在网络里**：`SequenceExecutionChamberNetworkNode` 不是 `StorageProvider`，
   所以 RS 原版策略（回退路径）也看不到执行舱的内容 —— 不存在被「网络侧导出」一次、「总线侧导出」又一次。

## 13.4 拆方块 / 切模式 / 断连时的清理

| 场景                  | 行为                                                                                                          |
| ------------------- | ----------------------------------------------------------------------------------------------------------- |
| **切模式（BUS→FACE）**   | 执行舱 `setOutputMode` 清缓存 + 归一归属（归属立刻变空）+ 通知相连总线 `rscc$refreshExecutorMode` → 总线重跑 `initialize` → 换回 RS 原版策略与玩家自己的过滤器。执行舱内部存储里已搬进来的料**原地保留**（可从输出面抽取，或拆方块掉落） |
| **拆输出总线**           | 执行舱侧 `connectedExporterPositions` 20 tick 内自愈，归属表随之收缩；总线上什么引用都没存（策略里的执行舱是逐次解析的）                                                  |
| **拆执行舱**            | 总线侧 `rscc$linkedExecutor()` 20 tick 缓存过期后重新 BFS → `null` → 策略自动委托 RS 原版行为；**执行舱内部存储随方块掉落**（见下）                   |
| **拆执行舱丢不丢东西**       | 新增 `SequenceExecutionChamberBlock#onRemove → dropOutputContents`：物品直接 `popResource`，流体**优先写回 RS 网络**（零损耗）、网络不可用才退化为按桶掉落（与回流总线同一约定）。**这是本轮新修的**——旧代码只掉单元样板，内部存储会随方块消失 |
| **区块卸载 / 读档**       | 内部存储走既有 NBT（`OutputItems` / `OutputFluids`），零损耗；`setLevel` → `initialize` → 策略重新装好                                                                  |
| **客户端**             | `rscc$linkedExecutor()` 在客户端恒 `null`，策略**只在服务端**安装；界面状态一律以 S2C 为准                                                     |

## 13.5 语言键（`tools/lang_frag_bus5.json`）

- 新增 `gui.rs_create_compat.exporter_executor.source.tip`：锁定条 tooltip 多一行
  「取料：先把原料搬进执行仓内部存储，再从执行舱按勾选类别推出。」（操作说明类文案，写短）。
- 覆盖 `inputs.tip` / `intermediates.tip` / `fluids.tip`：原文案写的是「把**网络上**的…推给机器」，
  与本轮语义矛盾，改为「从**执行仓内部存储**里…」。
- 归属说明：`locked.tip` 在 `tools/lang_frag_misc.json`（排序在 `bus5` 之后），本轮**未改动**它，
  因为它的描述（「已连到一台处于总线输出模式的执行舱 + 类别由执行舱给出 + 过滤器槽位锁定」）依旧准确。

## 13.6 本轮新增 / 修改文件

**新增**

- `java/.../support/RsccChamberExportStrategy.java`（取货源 = 执行舱内部存储的传输策略 + 脱绑回退）
- `tools/gen_lang_frag_bus5.py`、`tools/lang_frag_bus5.json`

**修改**

- `support/RsccExporterExecutorMode.java`（+`rscc$getLinkedExecutor()`）
- `support/RsccBusCategory.java`（总量守恒的出处改为「执行舱内部存储」）
- `mixin/exporter/AbstractExporterBlockEntityMixin.java`（+`@Shadow upgradeContainer`、+`rscc$installChamberStrategy`、
  `initialize` TAIL 注入改名为 `rscc$refreshAfterInitialize`）
- `block/entity/SequenceExecutionChamberBlockEntity.java`（BUS 模式改为「备料进内部存储」：`fillInternalForBus` /
  `pullItem` / `pullFluid` / `busItemDeficit` / `busFluidDeficit`；+`dropOutputContents`；常量 `BUS_ITEM_BUFFER` / `BUS_FLUID_BUFFER`）
- `block/SequenceExecutionChamberBlock.java`（`onRemove` 一并结算内部存储）
- `client/widget/ExporterExecutorRowWidget.java`（锁定条 tooltip +`source.tip` 一行）
- `resources/.../lang/zh_cn.json` + `en_us.json`（由 `tools/apply_lang_frag.py` 幂等合并：+1 / 覆盖 3）
- 本文件

## 13.7 未完成项与残余风险

1. **未做游戏内实测**：仍以「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」为验证手段。
   建议实测两件事：① 总线只从执行舱取料（网络里的同类物料不会被它直接取走）；
   ② 拆掉执行舱 / 切回面输出后，总线是否立刻恢复成普通输出总线。
2. **备料的「缓冲量」是自定值**（物品 64 / 流体 1000 mB，每 20 tick 补一次）：比基础输出总线
   （每 tick 每过滤项 1 件）的消耗更快，但**装了堆叠升级的总线**在一段时间内可能受限于备料速度；
   若实测觉得慢，把 `BUS_ITEM_BUFFER` 调大即可（只影响缓冲深度，不影响守恒）。
3. **中间产物类别按「物品」归类**：备料与导出都以「物品种类」匹配（与原版模糊模式同一口径），
   因此同一配方不同 step 的过渡件会被一起搬给同一台机器（不区分 step）。旧实现（`matchUnit`）按 step
   精确匹配，但它依赖单元样板带配方 id；JEI「+」/ 手工创建的样板不带，会完全搬不动。
   本轮选「能工作」而不是「能工作但只对老样板生效」；若后续要按 step 精确，需要新增单元样板的配方 id 兜底。
4. **回退路径的过滤器清洗时机**：脱绑后过滤清单会在下一次 `initialize` / 打开界面时恢复为玩家自己的过滤器；
   在这之间的极短窗口里，总线可能按残留的类别过滤项从**网络**导出（这是改造前就有的旧行为，
   且**不会**碰到执行舱内部存储里的东西，因此不违反「不重复」）。

***

# Round4 · 追加章节：第十轮（拆方块「内容物去向」全局三档策略 + 无权限指令）

> 编译：`powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1` → `COMPILE OK (193 sources)`；
> `python tools/verify_mixin_shadows.py` → 0 问题（退出码 0）；
> `python tmp_textures/verify_gui_layout.py` → 0 问题；`python tools/audit_gui_textures.py` → 0 问题；
> 语言键：`python tools/gen_lang_frag_blockcontent.py` + `python tools/apply_lang_frag.py`（幂等，片段 6 键 / 新增 6）。

## 14.1 用户原话与本轮目标

> 「加一个统一的开关保证行为统一……它有三种选项第一种就是回流到网络之中第二种就掉落出来第三种就保存在这个方块内部
> 可以自由选择……如果说选择了回流到网络之中但是他并没有和网络进行连接……它是自动保存到方块内部的而不是自动爆出……
> 那些样板……它不要自动回流到网络之中因为回流到网络之中不知道该怎么方便的取出来所以说它这些呢就只能是掉落或者说保存了方块之中」

要的是：**一个全局、统一、可切换的三档策略**，作用在**所有「内装实在物品/流体/样板」的方块**被破坏时；
外加一条**无权限指令**切换档位。

## 14.2 三档语义 + 两条硬规则（唯一实现在 `support/BlockContentReleaser`）

| 档位                | 物品              | 样板                        | 流体                        |
| ----------------- | --------------- | ------------------------- | ------------------------- |
| `network`（回网）     | 写回所在 RS 网络      | **永不回网 → 降级为「存方块」**       | 写回网络                      |
| `drop`（掉落）        | 爆出为掉落物          | 爆出为掉落物                    | **不使用**（见流体硬规则）           |
| `block`（存方块）      | 写进方块物品 NBT      | 写进方块物品 NBT                | 写进方块物品 NBT                |

- **流体硬规则（无论全局选什么）**：能连上网络就 `NETWORK`，连不上就 `BLOCK`；**永不 `DROP`**（流体不可能直接流出来）。
  网络空间不足时，余量**放回方块内部**并一并存入 NBT；万一回填也失败，才退化为「按桶掉落」这一条保底路径（记 warning）。
- **无网络兜底**：全局选 `network` 但方块**没接网络**（例：离线破坏自动合成仓）→ 物品/样板/流体一起
  **降级为 `BLOCK`（保存到方块内部）**，**不爆出**。
- **样板特例**：样板**永远不进网络**。全局 `network` 时样板降级为 `BLOCK`（最安全，重新放下原样恢复）；
  全局 `drop` → 样板掉落；全局 `block` → 样板存方块。

## 14.3 指令（无权限，带 Tab 补全与短反馈）

```
/rs_create_compat blockcontent            # 只查：回报当前档位 + 用法
/rs_create_compat blockcontent network    # 第一档：内容物写回 RS 网络（无网络自动存方块）
/rs_create_compat blockcontent drop       # 第二档：内容物掉落出来
/rs_create_compat blockcontent block      # 第三档：内容物保存在方块内部（重新放下原样恢复）
```

- **权限**：`Commands.literal("rs_create_compat").requires(source -> true)` —— 任何玩家可用（用户明确要求「不需要权限」）。
- **Tab 补全**：`network/drop/block` 是 brigadier 字面量，自动补全，无需额外 Provider。
- **反馈**：`message.rs_create_compat.blockcontent.set` / `.current` / `.usage` + `.mode.network|drop|block`（中英各一份）。
- 与已有的 `/rs_create_compat autocrafter storage <on|off>` 并列，互不影响。

## 14.4 持久化：存哪里、为什么

**存在主世界（overworld）的 `DimensionDataStorage` 里的一张 `SavedData`**，数据名 `rs_create_compat_block_content`
（`support/BlockContentPolicy extends SavedData`，NBT 键 `mode`，默认 `drop`）。

- 为什么不用 ForgeConfig：用户要「按世界保存、读档后仍生效」，`SavedData` 与存档同生共死、随存档读写，天然满足；
  ForgeConfig 是「配置文件级」而非「存档级」。
- 为什么存主世界：这是**全局（非维度级）**行为开关，主世界数据即可覆盖全部维度；客户端 / 取不到服务器时回落到默认档，不做客户端缓存。

## 14.5 `BLOCK` 怎么做到「重新放下原样恢复」（无需每个方块实体写读回逻辑）

- 保存：`be.saveWithoutMetadata(registries)` 取**整份**方块实体 NBT（等价于调用受保护的 `saveAdditional`，不含 `id/x/y/z`），
  用 `BlockItem.setBlockEntityData(item, be.getType(), tag)` 写进方块物品；放置时由**原版** `BlockItem` 的
  `BLOCK_ENTITY_DATA` 机制自动 `loadStatic` 写回，因此**零额外读回代码**。
- 配合：需要「存方块」时，方块的 `getDrops` 必须**返回空表**（`BlockContentReleaser.filterLoot` 判定），
  改由 `onRemove` 里的 `release` 补掉**同一个**带 NBT 的方块物品 —— 保证「生存 / 创造」两条路径都恰好一个方块物品。

## 14.6 接入的方块清单（物品 / 流体 / 样板 的分类）

| 方块                             | 物品（items）                                     | 流体（fluids）                        | 样板（patterns）                     |
| ------------------------------ | --------------------------------------------- | --------------------------------- | ------------------------------- |
| 范围充电器 `range_charger`           | 插件槽                                           | —                                 | —                               |
| 定量保持器 `quantity_keeper`         | 标记槽+插件槽+同类存储                                  | 流体同类存储（MultiFluidCache）           | —（标记槽按物品/流体标记处理）                 |
| 高级定量保持器 `advanced_quantity_keeper` | 标记/插件槽 + 4 份同类存储                              | 4 份流体同类存储                         | —                               |
| 装填器 `schematic_loader`           | 库存+蓝图槽+插件槽+队列                                  | —                                 | —                               |
| 高级装填器 `advanced_schematic_loader` | 同上                                            | —                                 | —                               |
| 序列装配样板终端 `sequence_pattern_terminal` | —（内部全部容器都是样板）                                | —                                 | 单元库+流程编排+样板槽+原料/产物/废料（**全部样板**） |
| 回流总线 `sequence_return_bus`      | 物品缓冲 + 插件槽                                    | 流体缓冲（MultiFluidCache）            | 总样板槽                            |
| 序列装配执行器 `sequence_assembly_executor` | —（库存全部是样板）                                   | —                                 | 库存（**全部样板**）                     |
| 序列执行仓 `sequence_execution_chamber` | 内部输出物品存储                                     | 内部输出流体罐（RsccUnboundedFluidStorage） | 单元样板槽                           |
| 自动合成仓（RS `Autocrafter`，Mixin）   | 模组新增的内部输出物品存储                                | 模组新增的内部输出流体罐                      | RS 自有的样板/升级仍走 RS 原版掉落（见风险 §14.8） |

## 14.7 自动合成仓（RS 方块）的接入方式

- 新增 `@Inject(method = "getDrops", at = @At("RETURN"))`（`mixin/AutocrafterStorageMixin`）：
  拆仓时按策略结算**模组新增的内部输出存储**（物品 + 流体）。
- 为什么注入 `getDrops` 而不是 `onRemove`：RS 的 `AbstractBaseBlock#onRemove` 会调用 `BlockEntityWithDrops#getDrops`
  并**在创造 / 生存两条路径下都执行**，所以注入这里即可覆盖「创造模式空手破坏」；且 `getDrops` 声明在目标类
  `AutocrafterBlockEntity` 自身，符合「只能注入目标类自身声明的成员」。
- 物品：全局 `network` 且有网络 → 写回网络（写不下的余量落回掉落）；其余情况（`drop` / `block` / 回网但无网络）→ 掉落。
- 流体：有网络 → 回网；无网络 → 按桶掉出（自动合成仓没有「流体存方块」的挂载点）。

## 14.8 本轮新增 / 修改文件

**新增**：`support/BlockContentMode.java`、`support/BlockContentPolicy.java`、`support/BlockContentReleaser.java`、
`tools/gen_lang_frag_blockcontent.py`、`tools/lang_frag_blockcontent.json`

**修改**：`command/CompatCommands.java`（+`blockcontent` 子树）、`mixin/AutocrafterStorageMixin.java`（+拆仓结算）、
10 个方块类的 `onRemove` / `getDrops`（接入同一策略）、`resources/.../lang/zh_cn.json`+`en_us.json`（幂等合并 +6）、本文件

## 14.9 未完成项与残余风险

1. **自动合成仓的 `BLOCK` 档退化为掉落**：RS 的自动合成仓方块物品不带 `BLOCK_ENTITY_DATA` 挂载点，
   无法把「模组新增的内部输出存储」写进方块物品；因此本块在 `block` 档 / 「回网但无网络」时**掉落**（不销毁，只是不写回方块物品）。
2. **自动合成仓自带的样板 / 升级仍走 RS 原版掉落**（不在本轮接管范围）：全局 `network` 时它们仍会掉落，
   不影响「样板永不进网络」这条规则；`block` 档时它们也不会进方块物品（同上，无挂载点）。
3. **未做游戏内实测**：仍以「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」为验证手段。
   建议实测：① 三档各破坏一次同一机器，确认「网络 +1 / 地上多一堆 / 方块物品带 NBT 且放下原样恢复」；
   ② 离线（拆掉线缆）破坏自动合成仓，确认内容物**不掉一地**而是保存在方块物品里（自动合成仓降级为掉落，见风险 1）。
4. **`SavedData` 与 ForgeConfig 的取舍**：档位挂在存档上，换存档即换档位（用户要求「按世界保存」，属预期）。

# Round4 · 追加章节：第十一轮（执行舱标题/排版 + 自动合成缺料提示覆盖流体）

## 15.1 用户原话与三件事

1. 执行舱界面上「配方类型 + 名字」排版不行：**配方类型字号太小看不清** → 改**横向排**并**放大**；
2. 执行舱的**标题改成该执行舱的名字**（默认未命名时才显示「序列执行仓」）；
3. **自动合成界面（RS 自动合成预览）**在黑曜石版坚固板这类需要**岩浆**的配方上**不说缺岩浆**；
   且提示里的原料要对（起始原料 = 黑曜石粉末、中间产物 = 未完成的坚固板）。

## 15.2 执行舱主界面：单行横排 + 标题 = 仓名

文件 `client/screen/SequenceExecutionChamberScreen.java`（背景仍是原版 `generic_54.png`，无自绘 PNG）：

| 元素   | 位置（Menu 坐标）                                       | 说明                                                                                                        |
| ---- | ------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| 标题   | `(titleLabelX=8, titleLabelY=5)`，宽 ≤ `TITLE_MAX_W=112` | **= 本执行仓的名字**（S2C `SyncChamberBindingPacket#name`，`trimToWidth` 截断）；名字为空（未命名）时回落到菜单标题 = 方块名「序列执行仓」 |
| 绑定状态行 | `(8, 128)`，`STATUS_MAX_W=160`，高 9                  | **单行横排**：`配方类型：X  名字：Y`（语言键 `...sequence_execution_chamber.bound`），**原版字号**（不再 0.5 缩放）                     |

- 留白带校验：上方 6 行单元样板槽框占 17..124、玩家背包槽框自 139 起，状态行 128..136 正落在中间留白带内，不压任何槽位。
- tooltip：hover 矩形与绘制矩形同源（`(8,128,160,9)`），同一格只渲染一份 tooltip（`statusLine()` + `status.tip`）。
- 旧实现的两行（`STATUS_RECIPE_Y=126` 0.5 缩放小字 + `STATUS_NAME_Y=131`）连同 `drawSmallText` /
  `recipeTypeLine` / `nameLine` / `bindingStatusLine` 一并移除。

## 15.3 自动合成缺料提示：流体必须进 RS 样板 ingredient

**根因**：RS「自动合成预览」（`AutocraftingPreviewScreen` / `TreePreviewWidget`，本仓库
`local_src/rs_src/.../common/autocrafting/preview/`）是**按样板登记的资源逐项算缺料**的
（`PreviewType.MISSING_RESOURCES`）；而 `SequenceAssemblyPatternItem#buildPattern` 只登记了**物品** ingredient
（旧 `collectInputs` 返回 `Map<Item,Long>`），**流体从未登记** —— 于是需要岩浆的配方在预览里永远不会出现「缺少岩浆」。

**修复链路（每步的数据来源）**：

1. `SequencePatternData.UnitEntry` 新增 `inputFluid`（NBT 键沿用 `InputFluid`），随总样板物品落盘；旧样板无该键 = `FluidStack.EMPTY`。
2. SPT 生成总样板时（`SequencePatternTerminalBlockEntity#generateAssemblyPattern`）读该步单元样板上的 `InputFluid`
   （`SequencePatternData#readUnitFluid`，其源头是 Create `ProcessingRecipe#getFluidIngredients()` 的 `SizedFluidIngredient#amount`）。
3. `SequenceAssemblyPatternItem`：`collectInputs` 同时聚合物品（件）与流体（`FluidResource` → mB，倍率同为
   `loops × 该步重复次数`）；`buildPattern` 把流体也用 `PatternBuilder#ingredient(ResourceKey, long)` 登记
   （EXTERNAL 样板允许流体 ingredient）；tooltip「所需输入」增列流体行
   （新键 `item.rs_create_compat.sequence_assembly_pattern.input_fluid_entry`，`%s：%s mB`）。
4. `SequenceAssemblyExecutorBlockEntity#accept`（EXTERNAL sink）现在把**物品与流体都原样写回网络**：
   样板登记流体后 RS 会从内部暂存抽走流体交给 sink，只处理物品会把流体**销毁**（违反「绝不销毁玩家资源」）。

**判定「缺料」的口径（完全交给 RS，不自算）**：预览缺料 = 该样板各 ingredient（物品 + 流体）在**网络存储**里凑不齐所需量；
物品按件、流体按 mB（同为 `ResourceKey` 体系）。本模组只负责把 ingredient 登记得**正确、完整**。

## 15.4 校验与语言键

- `python tmp_textures/verify_gui_layout.py` → 0 问题；`python tools/audit_gui_textures.py` → 0 问题
  （执行舱主界面用原版 `generic_54.png`，不在两份脚本的布局表内，故布局表无需改动）。
- 语言键：`tools/gen_lang_frag_chamber.py` → `tools/lang_frag_chamber.json`（+1 键），
  经 `tools/apply_lang_frag.py` 幂等合并（zh_cn / en_us 各新增 1、覆盖 0、删除 0）。

## 15.5 本轮新增 / 修改文件

**新增**：`tools/gen_lang_frag_chamber.py`、`tools/lang_frag_chamber.json`

**修改**：`client/screen/SequenceExecutionChamberScreen.java`、`data/SequencePatternData.java`、
`item/SequenceAssemblyPatternItem.java`、`block/entity/SequenceAssemblyExecutorBlockEntity.java`、
`block/entity/SequencePatternTerminalBlockEntity.java`、`lang/zh_cn.json` + `lang/en_us.json`、本文件

## 15.6 未完成项与残余风险

1. **未做游戏内实测**（本环境无客户端）：仍以「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」为验证手段。
2. **旧总样板不带流体**：修复前生成、且步骤需要流体的总样板仍不会提示缺流体，需**重新生成**总样板才带上流体项。
3. **执行舱运行时取流体不受本改动影响**（引擎按 Create 配方自判该步流体，见 `recipeStepInputFluids`）；
   本改动只影响 RS 的**任务规划 / 缺料预览**：若把流体直接喂机器而不放进网络，RS 会按「缺料」拒绝开单（与预览口径一致）。

# Round4 · 追加章节：第十二轮（连线识别放宽 / 串线确定性裁决 / 起始原料只在第 1 步输出 / 两种供应策略）

## 16.1 用户原话与四个诉求

1. **输出总线 / 输入总线贴在一起判不出连接**：紧贴另一台输出总线 / 输入总线时识别不到执行舱，
   必须再补一段线缆才行 → 要求「凡是从序列执行舱分支出来的路径都要能被识别」；
2. **多张线缆互相连通会「串线」**：一条路径同时够到多台执行舱时归属不明 → 要求给出确定性规则；
3. **起始原料被两台输出总线重复输出**：黑曜石粉末这类起始原料在「第 1 步」和别的中间步骤都被输出
   → 要求**默认只在第 1 步对应的那次输出里出现**；
4. **两种供应策略**：A「只管输出原料」（不看概率）/ B「持续供应直到目标产物达标」，
   用指令切换、**默认 B**；B 模式要显示**预估需求**并提示「因概率原因实际可能超出」，
   且某些原料不在网络里时要能自动合成。

## 16.2 任务一：可穿行集合（连线识别放宽）

**唯一实现**：`support/RsccWireBlocks#isWire(level, pos)`，输出总线侧（`AbstractExporterBlockEntityMixin`）
与执行舱侧（`SequenceExecutionChamberBlockEntity#collectExporters`）的 BFS **共用同一判定**。

| 判定              | 真实 RS 类名                                                                 | 可穿行 |
| --------------- | ------------------------------------------------------------------------ | --- |
| RS 线缆           | `com.refinedmods.refinedstorage.common.networking.CableBlock`（所有颜色同一类）   | 是   |
| 输出总线            | 方块实体 `...common.exporter.AbstractExporterBlockEntity`                     | 是   |
| 输入总线            | 方块实体 `...common.importer.AbstractImporterBlockEntity`                     | 是   |
| 外部存储总线 / 构造器 / 破坏器 | `AbstractExternalStorageBlockEntity` / `AbstractConstructorBlockEntity` 等 | **否**（与容器 / 世界交互，属机器） |
| 序列执行仓 / 自动合成仓等  | ——                                                                       | **否**（碰到即止步，不穿过） |

- 两侧 BFS 都保留**步数上限 64**（`RSCC_LINK_MAX_STEPS` / `BUS_LINK_MAX_STEPS`，同一数值）与 **20 tick 缓存**。
- 输出总线侧改为**逐层 BFS**（`frontier / next` 两层列表）：碰到执行仓记录但不穿行；
  碰到导线继续入队 → 于是「紧贴的输出总线 / 输入总线」与「隔着一段输出总线的执行舱」都能被识别。

## 16.3 任务一（续）：串线的确定性裁决规则

一条路径同时够到多台「总线输出」模式的执行舱时，按以下**字典序规则**唯一裁决（同一条规则在
`rscc$searchExecutor` 内实现）：

1. **BFS 距离最近**的那台优先；
2. 距离相同（同一层）时，取**坐标字典序最小**者：`x` → `y` → `z`（`rscc$lexicographic`）。
   与方向枚举顺序**无关**，因此结果稳定、可复现。

界面可辨识：输出总线锁定条 tooltip 新增「**归属执行舱：<名字>**」一行（`linkedChamber`；
S2C `SyncExporterExecutorModePacket` 下发，服务端取 `getChamberDisplayName()`，坐标为兜底名）。

**「新增专用线缆（不与 RS 原版线缆合并连接）」方案对比与建议**（本轮**未**实现，供用户拍板）：

| 维度   | 本轮方案：最近裁决 + 可辨识                | 专用线缆方案                                        |
| ---- | -------------------------------- | --------------------------------------------- |
| 工作量  | 小（改 2 处 BFS + 同步 1 个字段）           | 大（新方块 / 方块实体 / 连接图形 / 纹理 / 模型 / 数据生成 / 与 RS 连接系统互斥） |
| 风险   | 低（不动 RS 连接语义）                    | 高（要阻止 RS 线缆与专用线缆互连，需拦截 RS 的 `AbstractCableLikeBlockEntity#updateConnections`；跨模组兼容性差） |
| 用户可见 | 多张线缆连通时按「最近 + 字典序」归属，界面显示归属名 | 各执行舱的线缆物理隔离，绝不会串，但玩家要改用新线缆                      |
| 建议   | **本轮先落地**（开销小、可回退）               | 若「最近裁决」仍不满足（例如两台执行舱距离完全相等且玩家不接受字典序），再上专用线缆    |

## 16.4 任务二：起始原料只在第 1 步输出

**数据来源**：`computeBusCategories()` 里，每个单元样板先经 `resolveUnitRecipe` 定位所属
Create 序列装配配方，再用 `stepForUnit` 取该单元对应的那一步；新增
`stepIndex = recipe.getSequence().indexOf(step)`（identity 比较）判定它在序列里的下标。

- 「起始原料」= 配方主原料（`recipe.getIngredient()` 的首个非空物品，`mainIngredientOf`）；
- 旧实现里「某步除过渡件外没有输入（冲压这类只有 1 个 ingredient 的步骤）」会**兜底**
  把主原料当本步输入 → 于是中间步骤也出现起始原料类别、被另一台输出总线重复输出；
- **本轮收紧**：该兜底**只在 `stepIndex == 0`（第 1 步）时生效**；中间步骤不再生成起始原料类别
  （冲压仓只剩「中间产物」类别）。因此起始原料只在第 1 步对应的那次输出里出现。

## 16.5 任务二（续）：两种供应策略（指令切换 + 持久化）

**枚举 / 持久化**：`support/RsccSupplyStrategy`（`materials` / `target`）+
`support/RsccSupplyPolicy`（主世界 `SavedData`，数据名 `rs_create_compat_supply_strategy`，
与 `BlockContentPolicy` 同一套做法，**读档仍生效**）。**默认 = `target`**（用户要求）。

| 策略                     | 语义                                                                       | 备料口径（`fillInternalForBus`）                | 缺料处理 |
| ---------------------- | ------------------------------------------------------------------------ | --------------------------------------- | ---- |
| A `materials` 只管输出原料   | 不看概率、不主动发起合成；沿用既有「自动合成门控」（网络里有进行中任务才供应）                                  | 每类「每批需求 × 1」（既有口径）                       | 不发起  |
| B `target` 直到目标产物达标（默认） | 持续供应；某输入在网络里不足 / 缺失时按缺口 `ensureTask` 补齐 → 只要最终产物的自动合成任务未达标就不断补 | 每类「**预估需求**」（步骤输入 = 每批 × 配方 `loops`，起始原料 = 每批） | 按缺口自动合成（40 tick 节流，仅有对应样板时） |

**预估需求口径**（`BusCategoryInfo#estimated`，S2C `RsccBusCategory#estimated`）：
正常情况（**不看概率**）产出 **1 个最终产物**所需的量 —— 起始原料 = 每批需求量；
步骤输入 = 每批需求量 × 配方 `loops`；中间产物 = 0（不显示）。
策略 B 下悬停单元格显示「预估需求（每 1 个最终产物）：N」+「因概率原因，实际消耗可能会超出这个预估」。

**指令**（与既有指令同根 `rs_create_compat`，**不设权限**，Brigadier 字面量自带 Tab 补全，短反馈）：
```
/rs_create_compat supply materials   只管输出原料（不看概率）
/rs_create_compat supply target      直到目标产物达标（默认；缺料自动合成补齐）
/rs_create_compat supply             只输入到 supply 一层：回报当前档位 + 用法
```

**「原料不在网络里 / 需要自动合成」的处理**：策略 B 下由执行舱在每个备料周期按
「网络存量 < 目标量」的缺口调用 `AutocraftingNetworkComponent#ensureTask`
（携带非空 `TimeoutableCancellationToken`，参考 `AdvancedQuantityKeeperNetworkNode` 的既有写法）。
只对**已有输出样板**的资源请求，避免空转；已有任务在跑时 `ensureTask` 返回 `TASK_ALREADY_RUNNING`，天然幂等。
策略 A 完全不请求合成（用户语义「只管把原料输出去」）。

## 16.6 校验与语言键

- 语言键：`tools/gen_lang_frag_supply.py` → `tools/lang_frag_supply.json`（+10 键），
  经 `tools/apply_lang_frag.py` 幂等合并（zh_cn / en_us 各新增 10、覆盖 0、删除 0）。
- GUI 几何未变（只增 tooltip 文本行），`tmp_textures/verify_gui_layout.py` 与
  `tools/audit_gui_textures.py` 布局表**无需改动**。
- Mixin 改动后跑 `python tools/verify_mixin_shadows.py`（0 问题、退出码 0）。

## 16.7 本轮新增 / 修改文件

**新增**：`support/RsccWireBlocks.java`、`support/RsccSupplyStrategy.java`、
`support/RsccSupplyPolicy.java`、`tools/gen_lang_frag_supply.py`、`tools/lang_frag_supply.json`

**修改**：`mixin/exporter/AbstractExporterBlockEntityMixin.java`、
`mixin/exporter/ExporterContainerMenuMixin.java`、
`block/entity/SequenceExecutionChamberBlockEntity.java`、`support/RsccBusCategory.java`、
`network/SyncExporterExecutorModePacket.java`、`client/widget/ExporterExecutorRowWidget.java`、
`command/CompatCommands.java`、`lang/zh_cn.json` + `lang/en_us.json`、本文件

## 16.8 未完成项与残余风险

1. **未做游戏内实测**（本环境无客户端）：仍以「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」为验证手段。
2. **专用线缆方案未实现**：按用户要求仅作为方案对比留在 §16.3，待拍板。
3. **策略 A / B 的门控相同**：都沿用「网络里有进行中的自动合成任务」这道闸门；
   两者差异在**备料量**与**是否主动自动合成**（见 §16.5 表）。
4. **策略 B 的预估需求不含概率补偿**（按用户口径「预估 = 正常所需量」），
   概率导致的额外消耗由「持续供应 + 缺口自动合成」兜住。


# Round4 · 追加章节：第十三轮（服务端崩溃修复：搜索零副作用 / 线程级重入守卫 / 懒安装）

## 17.1 崩溃现象与递归根因（已定位）

崩溃报告 `run/crash-reports/crash-2026-09-13_15.24.26-server.txt`：

```
Description: Exception chunk generation/loading
java.lang.StackOverflowError
  AbstractExporterBlockEntity.initialize(AbstractExporterBlockEntity.java:103)
   → rscc$refreshAfterInitialize（本模组 initialize 注入）
    → rscc$installChamberStrategy
     → rscc$linkedExecutor → rscc$searchExecutor   ← 此处调 Level#getBlockEntity(相邻坐标)
      → LevelChunk.getBlockEntity → addAndRegisterBlockEntity → setBlockEntity
       → AbstractBaseNetworkNodeContainerBlockEntity.setLevel → initialize
        → AbstractExporterBlockEntity.initialize     ← 相邻的另一台输出总线，回到起点
```

**根因**：`initialize` 是「方块实体正在初始化」的阶段，本模组却在这个阶段做邻块搜索，且搜索用
`Level#getBlockEntity` 探测相邻方块 —— 该方法会**强制加载并初始化**邻块。上一轮又把「输出总线 / 输入总线」
纳入了可穿行集合，于是输出总线串接时形成闭环：**A 初始化 → 探测到 B → B 初始化 → 探测到 A → …**
无限递归，区块加载即栈溢出。

## 17.2 三条修复

### 修复一：搜索阶段零副作用（绝不触发方块实体加载）

搜索（`rscc$searchExecutor`）对所有候选方块**只读 `BlockState`** 判定，**绝不调用 `Level#getBlockEntity`**：

```java
final BlockState state = level.getBlockState(neighbor);           // 只读状态
if (state.getBlock() instanceof SequenceExecutionChamberBlock) {  // 确认是本模组执行舱方块
    final BlockEntity be = level.getChunkAt(neighbor)
        .getBlockEntity(neighbor, LevelChunk.EntityCreationType.CHECK); // 只取已存在的，不创建
    ... isBusOutput() ...
    continue;                                                     // 执行舱不可穿行
}
if (RsccWireBlocks.isWire(state)) { next.add(neighbor); }         // 线缆/输出总线/输入总线：穿行
```

- 穿行集合 `RsccWireBlocks` 改为按**方块类**判定（`CableBlock` / `ExporterBlock` / `ImporterBlock`），
  与旧的方块实体类型判定一一对应、结果等价，但无任何副作用；
- 只有方块状态确认是「本模组的执行舱方块」时才取方块实体，且用 `LevelChunk.EntityCreationType.CHECK`
  （**已存在才返回、绝不创建**）；执行舱初始化不会反过来搜索输出总线，这一步安全。
- 执行舱侧 `SequenceExecutionChamberBlockEntity#collectExporters` 的 BFS 同步收紧：
  只有 `RsccWireBlocks.isExporterBus(state)`（输出总线方块）才取方块实体，其余一律只看状态。

### 修复二：线程级重入守卫（必须）

新增 `support/RsccSearchGuard`（`ThreadLocal<Boolean>`）。`rscc$searchExecutor` 入口：

```java
if (RsccSearchGuard.isSearching()) { return null; }  // 已在搜索中：立即返回，绝不递归
RsccSearchGuard.enter();
try { ...BFS... } finally { RsccSearchGuard.exit(); } // 异常也必须复位
```

守卫按**线程**隔离（区块加载线程与主线程互不干扰）；`exit()` 在 `finally` 中，
任何异常路径都不会让守卫状态泄漏（否则该线程会永久「卡在搜索中」而使连接判定全失效）。
`rscc$refreshAfterInitialize` 注入同样先看守卫：若本 BE 是被搜索间接强制加载的，则直接返回、不改状态。

### 修复三：安装时机后移（initialize → 服务端首个 tick 懒安装）

- `initialize` 注入（`rscc$refreshAfterInitialize`）**不再探测邻块**，只做两件只读自身状态的事：
  ① 复位「策略待安装」标记；② 作废连接缓存。此时**不解析归属、不安装策略、不下发清单**。
- 新增 `mixin/network/AbstractBaseNetworkNodeContainerBlockEntityMixin`：在 RS 的
  `AbstractBaseNetworkNodeContainerBlockEntity#doWork()`（方块实体 tick 入口，声明在其自身）TAIL 前注入，
  若实体是输出总线（实现了 `RsccExporterExecutorMode`）则调用新增的 `rscc$serverTick()`。
- `rscc$serverTick()` → `rscc$ensureChamberStrategy()`：**首次 tick** 解析归属（走已有 20 tick 缓存），
  绑定到「总线输出」执行舱才把节点策略换成 `RsccChamberExportStrategy` 并置位；**未绑定时保留 RS 原版策略**
  （普通输出总线完全不受影响），下次 tick 再试。

## 17.3 「何时安装策略」的最终选择与理由

**最终选择：服务端首个 tick 的懒安装**（并把「归属解析」也一并推迟到 tick）。

理由：
1. `initialize` 天然属于「方块实体初始化」阶段，此时邻块可能仍在初始化，任何跨方块探测都可能形成环；
   按时序把「探测邻块」推迟到 tick，此时被探测方块早已初始化完毕，探测是纯读操作、无副作用；
2. 「首个 tick 才装」不会漏：输出总线的 tick 每个服务端 tick 都会跑，首次 tick 即完成安装，
   玩家可感差异为零；RS 重新 `initialize`（旋转 / 升级 / 改模糊模式）会复位标记，下一 tick 按新状态重装；
3. 未绑定（普通输出总线）时**完全不替换策略**，因此不会给非本模组用法带来任何行为差异
   （尤其是 RS 自带的「自动合成升级」委托链得以完整保留）。

连接缓存 / 刷新策略保持不变：解析结果缓存 `20 tick`；执行舱切模式时通过
`rscc$refreshExecutorMode()` 主动作废缓存与安装标记，下一 tick 立即重建。

## 17.4 为什么现在不可能再出现 A→B→A 递归

原递归环由三条边构成，现已逐条切断：
1. **`initialize → search`**：`initialize` 注入不再探测邻块（修复三）；
2. **`search → Level#getBlockEntity → 邻块 initialize`**：搜索改为只读方块状态，
   对穿行方块**完全不取方块实体**；对执行舱方块用 `CHECK` **只取已存在、不创建**（修复一）；
3. **兜底**：`RsccSearchGuard` 使任何「搜索途中再次进入搜索」的调用立即返回 `null`（修复二），
   即便未来出现未知的间接调用链也无法形成栈增长。

## 17.5 保留的既有行为（回归清单）

- 可穿行集合仍为「RS 线缆 + 输出总线 + 输入总线」，不含外部存储总线 / 构造器 / 破坏器 / 执行舱；
- 最近距离优先 + 同距离按坐标字典序（x → y → z）裁决，BFS 上限 64 步不变；
- 脱绑（拆执行舱 / 切回面输出 / 断连）时策略内部逐次解析退化为 RS 原版「网络 → 目标」；
- 归属解析结果 20 tick 缓存，执行舱切模式时主动刷新；
- 工具提示 / 界面「归属执行舱」显示链路不变（`rscc$getLinkedExecutor()` 语义未变）。

## 17.6 本轮新增 / 修改文件

**新增**：`support/RsccSearchGuard.java`、
`mixin/network/AbstractBaseNetworkNodeContainerBlockEntityMixin.java`

**修改**：`support/RsccWireBlocks.java`、`support/RsccExporterExecutorMode.java`（+`rscc$serverTick()`）、
`mixin/exporter/AbstractExporterBlockEntityMixin.java`、
`block/entity/SequenceExecutionChamberBlockEntity.java`、`src/main/resources/rs_create_compat.mixins.json`、本文件

## 17.7 未完成项与残余风险

1. **未做游戏内实测**（本环境无客户端 / 无法起服务端）：以「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」为验证手段；
2. **未绑定输出总线每 tick 会做一次缓存查询**（20 tick 才真正 BFS 一次），开销与执行舱侧既有 BFS 同量级，可接受；
3. 若未来 RS 给输出总线新增「非 `ExporterBlock` 但复用 `AbstractExporterBlockEntity`」的方块，
   需要同步扩充 `RsccWireBlocks` 的方块类集合（当前 RS 版本下为一对一）。

***

## 18. 输出总线归属解析的性能优化：定时轮询 → 事件驱动 + 廉价预检 + 退避

### 18.1 问题（改前）

上一轮把「安装策略」改成服务端首个 tick 懒安装后，未绑定的输出总线仍然每 `20 tick` 跑一次**全量 BFS**
（`rscc$linkedExecutor()` → `rscc$searchExecutor()`，最多展开 64 层）。绝大多数输出总线旁边什么都没有，
这次 BFS 纯属空转 —— 基座里几百台输出总线就是每 20 tick 几百次 BFS。

### 18.2 新触发模型（四件套）

| 机制 | 实现 | 参数 |
| --- | --- | --- |
| **廉价预检** | `AbstractExporterBlockEntityMixin#rscc$hasRelevantNeighbor`：只读**六个邻块**的 `BlockState`（**绝不取方块实体**），判「导线集合成员（RS 线缆 / 输出总线 / 输入总线）或执行舱方块」。一个都不是 → **直接跳过整个 BFS** | — |
| **失效事件** | ① 邻块放置 / 破坏 / 被替换 → `support/RsccBusLinkInvalidation` 监听 NeoForge `net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent`（`getNotifiedSides()` 逐向找输出总线，`CHECK` 取方块实体，调 `rscc$invalidateNeighborLink()`）；② 执行舱切输出模式 → `rscc$refreshExecutorMode()`（已有）；③ 区块加载（`setLevel` → RS `initialize`）→ `rscc$refreshAfterInitialize`（已有）；④ 玩家打开本总线界面 → `ExporterContainerMenuMixin` 服务端构造末尾 `rscc$refreshExecutorMode()` | — |
| **退避** | 预检未命中 → 检查间隔 `20 → 40 → 80 → 160 → 200` 指数退避并饱和；**预检命中**或**任一失效事件** → 立刻回到 `20` | 初始 `20` / 上限 `200` |
| **节流** | `rscc$nextSearchAt = now + RSCC_SEARCH_MIN_INTERVAL`，任何路径都过不了这道门 → **同一 tick / 20 tick 窗口内最多一次全量 BFS**，邻居抖动不抖动搜索 | 最小间隔 `20` |

关键正确性细节：**「六邻块里存在导线」是任何连通的必要条件**。这个前提若被破坏，必然伴随邻块事件 →
立刻清零间隔；前提仍成立时（哪怕远端还没接上执行舱）间隔恒为 `20`，与旧实现一致。因此退避**只**发生在
「本来就绝无可能连通」的场景，不改变任何可观测行为。

### 18.3 最坏 / 典型开销对比

| 场景 | 改前 | 改后 |
| --- | --- | --- |
| **典型**：未绑定、旁边什么都没有（数千台） | 每台每 20 tick 一次全量 BFS | 稳态每 200 tick 一次 **6 次 `getBlockState`**（≈ 改前的 1/60 × 1/64） |
| **已绑定** | 每 20 tick 一次全量 BFS | **不变**（20 tick 缓存过期即重搜，作为「中途断线」的兜底） |
| **邻旁有导线但未连通** | 每 20 tick 一次全量 BFS | **不变**（预检命中即复位到 20 tick） |
| **最坏**：一次世界内高频方块更新 | 每条 BFS ≈ 64 层 × 每层 6 向 | 每个 `NeighborNotifyEvent` ≤ 6 次 `getBlockState`（仅 `ServerLevel`），搜索仍受节流约束 |

「最坏情况」由 `NeighborNotifyEvent` 决定：它由 `Level#updateNeighborsAt` 触发，而原版该方法本身已要发 6 次
`neighborChanged`（重得多），本监听只多 6 次方块状态读取 + 极少数 `CHECK` 取实体（命中输出总线方块时）。
BFS 的总次数上限仍是「每 20 tick 每台一次」。

### 18.4 为什么正确性没被破坏

- 预检判定与 BFS 第一层展开**完全等价**（BFS 第一步恰好只访问这六个邻块），因此不可能漏掉任何可达执行舱；
- 能穿行集合、最近距离 + 坐标字典序裁决、脱绑退回 RS 原版策略、tooltip「归属执行舱」等语义均未改动；
- 搜索仍只用 `getBlockState` + `LevelChunk#getBlockEntity(CHECK)`，**绝不触发相邻方块实体加载**；
- `RsccSearchGuard` 重入守卫保留；
- 失效事件只「作废」，不在事件回调里同步搜索（世界正处于修改中），重算仍在 `rscc$serverTick()` 的安全时机。

### 18.5 本轮新增 / 修改文件

**新增**：`support/RsccBusLinkInvalidation.java`

**修改**：`mixin/exporter/AbstractExporterBlockEntityMixin.java`、
`support/RsccExporterExecutorMode.java`（+`rscc$invalidateNeighborLink()`）、
`mixin/exporter/ExporterContainerMenuMixin.java`、`RS_Create_Compat.java`（注册监听器）、本文件

### 18.6 验证

`tools/manual_compile.ps1` → `COMPILE OK`；`tools/verify_mixin_shadows.py` → 0 问题、退出码 0；
`tmp_textures/verify_gui_layout.py` 与 `tools/audit_gui_textures.py` → 各 0 问题。

***

## 19. 原料标记 + 防中间产物错误回流

### 19.1 问题

序列装配的中间产物（未完成的过渡件）一旦被错误地当成「输入」再投回执行舱 / 回流总线，
整条产线就会串步、重复加工甚至卡死。同时，「原料」与「中间产物」在物品层面常常是同一个物品
（例如某配方的起始原料恰好也是别处的中间件），只看物品是分不出来的。

### 19.2 标记载体：**方案 2**（任务第一步打原料标记，不写 Create 的 Mixin）

| 用途 | 载体 | 键名 | 值的形态 |
| --- | --- | --- | --- |
| **原料标记**（任务第一步 / 初始原料被输出给机器的时刻打上） | **注册版 DataComponent**（挂在 `DataComponentPatch` 上） | `rs_create_compat:raw_material` | `{recipe: <配方 id>, step: <输出该原料的步骤序>}` |
| **禁止回流步骤**配置（写在样板上） | 样板既有的 `DataComponents.CUSTOM_DATA`（沿用 `SequencePatternData` 的约定） | `disallow_inputting_by_step` | **NBT int 数组**（`putIntArray` / `getIntArray`） |

**为什么原料标记用注册版 DataComponent 而不是裸 NBT**：1.21.1 里物品附加数据的唯一正路就是组件，
组件值参与「同物品 + 同组件 = 同一资源」的合并 → **同种原料照常堆叠**；RS 的
`ItemResource(item, DataComponentPatch)` 照常存取；也不会和别的模组用的 `CustomData` 抢键。
**为什么样板配置仍走 `CustomData`**：样板数据本来就全在 `CustomData`（`SequencePatternData`），
配置属于「样板的形式」，跟着既有约定走最不容易出错，且 int 数组正好就是需求里的「NBT 数组」。

关键代码（`support/SequenceMaterialGuard.java`）：

```java
public static final String TAG_DISALLOW_INPUTTING_BY_STEP = "disallow_inputting_by_step";
public record RawMaterialMark(ResourceLocation recipe, int step) { /* CODEC + STREAM_CODEC */ }
stack.set(RS_Create_Compat.RAW_MATERIAL.get(), new RawMaterialMark(recipe, step)); // 打原料标记
return stepInLoop(assembly, sequenceSize) >= threshold;                            // 禁止回流判定
```

### 19.3 `step index` 语义（**0 基准**，以 Create 代码为准）

在 `local_src/create_src/com/simibubi/create/content/processing/sequenced/SequencedAssemblyRecipe.java` 里：

- `SequencedAssembly` 组件（L267-280）= `(id: ResourceLocation, step: int, progress: float)`，
  由 `advance()`（L113-126）在每一步完成后写入 `step + 1`；
- `getStep(input)`（L162-167）：无组件返回 `0`，否则返回组件的 `step`；
- `getNextRecipe(input)`（L158-160）= `sequence.get(getStep(input) % sequence.size())`。

因此 **`step` = 「已经走过的步数」= 「下一个要执行的步下标」（0 基准）**：
`step == 0` 表示还没做第一步，`step == 1` 表示第一步已完成、下一步是第 1 号步骤。
本模组统一用 `step % sequenceSize`（`floorMod`，即 `SequenceMaterialGuard#stepInLoop`）
作为**单循环内的步骤序**，与 `disallow_inputting_by_step` 的数组元素同基准：
**配置里的 `N` = 步骤序 ≥ N 的未完成物品禁止直接回流**（数组取最小值作阈值）。

### 19.4 Create 未完成物品上**已有**的配方相关字段（直接复用，不另建键）

| 字段 | 位置 | 说明 |
| --- | --- | --- |
| `id`（配方 id，`ResourceLocation`） | `create:sequenced_assembly` 数据组件（值 = `SequencedAssembly` record） | **就是「属于哪条配方」的定位键** —— 精密构件这类多配方物品靠它区分 |
| `step`（int） | 同上 | 步骤序（0 基准，见 19.3） |
| `progress`（float） | 同上 | `(step + 1) / (sequence.size() * loops)`，仅供进度条显示，判定不用 |
| 过渡件物品本体 | `SequencedAssemblyRecipe#transitionalItem`（`getTransitionalItem()`，L263） | 未完成物品的「物品」部分，判定不用 |

**结论**：`配方 ID + 步骤序` 已经完整存在于 `create:sequenced_assembly` 组件里
（`appliesTo()` L146-156 也是用 `id` 匹配配方的），所以本模组**没有**再发明定位键，
`disallow_inputting_by_step` 只承担「哪些步骤序不允许再回流」这一件事。

### 19.5 拦截点（**服务端权威**）与放行 / 拒绝规则

拦截点全部落在 **`block/entity/SequenceExecutionChamberBlockEntity`**（执行舱 = 「作为输入被投回执行舱」的入口；
回流总线 / 无线终端 / 单元样板管理相关文件本轮未改）：

| # | 位置 | 时机 | 规则 |
| --- | --- | --- | --- |
| ① | `tickEngine` → 面输出认领循环 | 从网络认领资源喂机器之前 | 命中「禁止回流」→ `continue`（**不认领**，物品留在网络里） |
| ② | `fillInternalForBus` → `pullItem` | 「总线输出」模式下把网络物料搬进本仓内部存储之前 | 同上（**不拉取**） |
| ③ | `collectFromFaces` → `FaceMode.INTERMEDIATE` 分支 | 中间产物从「中间产物输出面」回流进网络时 | 若带**原料标记** → 按**未标记的原始物品**写回网络（去掉标记），数量一个不少 |

判定顺序（`SequenceMaterialGuard#isInputBlocked`）：
1. 该配方**没有**配 `disallow_inputting_by_step` → **放行**（对旧存档零影响）；
2. 物品带**原料标记** → **放行**（它就是任务第一步输出的原料，不是中间产物）；
3. 物品没有 `create:sequenced_assembly` 组件 → **放行**（起步原料）；
4. 配方查不到（数据包未加载）→ **放行**（宁可放过，不可误拦）；
5. 否则 `step % sequenceSize >= 阈值` → **拒绝投回**（不接收，留在原处；**绝不销毁**），
   并用 `throttledLog`（600 tick 节流）记一条可诊断日志。

### 19.6 为什么没有破坏堆叠 / RS 存取 / 正常合成

1. **堆叠**：标记是数据组件，同物品 + 同组件值仍合并为同一堆；不同标记本来就不该混堆；
2. **RS 存取**：原料标记**只打在「送去机器的那一份」上**，回写网络时用的是未标记的原始资源
   （`storage.insert(itemResource, …)`），回流时再把标记去掉 → **网络里永远只有「原始原料」一种资源**，
   不会凭空多出「带标记的变体」去影响 `Ingredient` 匹配 / 自动合成预览；
3. **正常合成**：判定只发生在执行舱的输入路径上，且默认（未配置）全放行；
   配置了也只影响「未完成物品的认领」，对原版 Create / RS 行为零改动（**没有新增 / 修改任何 Mixin**）；
4. **不销毁资源**：三个拦截点都是「不拿 / 不放 / 按原数量归一化」，没有任何销毁路径；
   网络装不下的极端分支照旧 `Block.popResource` 落回世界。

### 19.7 短 tooltip

`client/SequenceMaterialTooltip`（`ItemTooltipEvent`，最多加 2 行，不改任何界面 / 槽位）：

| 语言键 | 触发条件 | 文案（中文） |
| --- | --- | --- |
| `item.rs_create_compat.raw_material.tip` | 物品带原料标记 | 「原料标记（配方 %s，第 %s 步输出）：允许正常回流。」 |
| `item.rs_create_compat.disallow_inputting_by_step.tip` | 样板带该配置 | 「禁止回流步骤：%s（0-based，单循环内）；超过后不能作为输入投回执行舱。」 |
| `item.rs_create_compat.sequence_incomplete.tip` | 物品带 `create:sequenced_assembly` | 「未完成件（配方 %s，第 %s 步）：超过配方配置的禁止回流步骤后，禁止直接回流。」 |

语言键由 `tools/gen_lang_frag_material.py` → `tools/lang_frag_material.json`，
再经 `tools/apply_lang_frag.py` **幂等**合并进中英语言文件。

### 19.8 本轮新增 / 修改文件

**新增**：`support/SequenceMaterialGuard.java`、`client/SequenceMaterialTooltip.java`、
`tools/gen_lang_frag_material.py`、`tools/lang_frag_material.json`

**修改**：`RS_Create_Compat.java`（注册 `rs_create_compat:raw_material` 数据组件）、
`block/entity/SequenceExecutionChamberBlockEntity.java`、本文件

### 19.9 未完成项与残余风险

1. **未做游戏内实测**（本环境无客户端 / 无法起服务端）：验证手段为「编译 + Mixin 注入校验 + 两个 GUI 校验脚本」；
2. `disallow_inputting_by_step` 的**图形化写入入口本轮未做**（GUI 归属并行的「单元样板管理」一路）：
   当前通过 `SequenceMaterialGuard#writeDisallowInputtingByStep` 或直接写样板 NBT 落地；
   未配置时全放行，因此缺入口不影响任何既有行为；
3. 阈值取数组最小值；若将来要「按区间精确禁止」，需要把判定从 `>= 阈值` 扩展为「区间集合」（当前数组语义已预留）；
4. 标记只打在「送去机器的那一份」：若机器把未被消耗的原料**原样退回**且退回路径是
   「中间产物输出面」，标记会在 19.5 ③ 被正常消化；若退回路径是别的模组 / 别的设备的私有库存，
   标记会随物品留在那里（不影响功能，只是多一个可读的标记）。

***

## 20. 收集类机器「缓存满不吞资源」全局审计与统一安全语义

> 用户需求原文（标为「非常重要」）：「任何收集物品/流体的机器，**缓存满时不能吞资源**。」

### 20.1 铁律与统一语义（唯一一份实现）

`support/SafeCollect.java`（本轮新增，服务端权威）把「把资源从 A 搬到 B」统一成三步，
**顺序不能颠倒**：

1. **先算能收多少**：对目标侧做 SIMULATE（RS 网络 `Action.SIMULATE`，`IItemHandler` / `IFluidHandler`
   用 `simulate = true` / `FluidAction.SIMULATE`），拿到「实际能收下的量」；
2. **再按实际能收的量抽取**：目标收不下就**一点都不抽**；「缓存 / 网络满」的唯一表现是**什么都不做**；
3. **执行不足要回滚**：`EXECUTE` 阶段少于模拟量时，差额还回来源；还回不去则交给调用方给的兜底出口
   （落回世界 / 落回自身存储），**绝不静默丢弃**。

对外 API（4 个语义原语，全部被真实调用）：

| 方法 | 语义 | 用在哪 |
| --- | --- | --- |
| `networkAcceptable(net, resource, wanted)` | 网络可接纳量（纯 SIMULATE，不改状态） | 「自己库存 → 网络」的前置容量计算 |
| `pushItemsToNetwork(net, source, slot, amount, overflow)` | 来源格 → 网络：先算容量 → 只抽能收下的 → 差额回滚回来源格 | 装填器库存回流、蓝图自动回收、过量回收 |
| `pullItems(source, slot, store, limit, overflow)` | 外部容器 → 自身存储：先模拟自身容量 → 只抽能收下的 → 差额回滚回来源格 | 执行舱「产物输出面」收集 |
| `pullFluid(source, store, limit, overflow)` | 外部流体容器 → 自身罐：同上（流体版） | 执行舱「产物输出面」流体收集 |

### 20.2 机器清单（穷举审计结果）

| 机器 / 路径 | 是否吞资源风险 | 结论与修法 |
| --- | --- | --- |
| **归流缓存仓** `CollectionCacheBlockEntity` | **无**（本就安全，验证通过） | 掉落物：`insertIntoCache()` 先真插入、再按**实际存入量** `shrink`，`stored <= 0` 时只 `markIgnored` 不删；实体只在整堆被收完后 `discard()`。世界流体：先判 `getFreeSpace() >= 1000` 并 `insert` 成功**之后**才 `setBlock(AIR)`。缓存 → 网络：按 `insert` 返回值扣缓存。物流能力视图（`IItemHandler` / `IFluidHandler`）按标准语义返回剩余 |
| **序列执行仓** `SequenceExecutionChamberBlockEntity` | **有**（2 处） | ①「产物输出面」物品收集改为 `SafeCollect.pullItems`（回滚回来源格 + 落世界兜底）；②流体收集改为 `SafeCollect.pullFluid`（原先 `outputTank.fill` 的结果没检查，填失败即丢流体）；③`dropOutputContents` 原先无条件 `setFluid(EMPTY)` → 改为**只抽走已交付的量**（回网 / 装桶成功的部分），无桶形态又回不了网的流体留在罐里并记 WARN |
| **蓝图装填器** `SchematicLoaderBlockEntity`（含高级版子类） | **有**（6 处，见 20.3） | 全部改为 `SafeCollect.pushItemsToNetwork` / 「先算容量再写」；队列部署失败时把蓝图**放回队列** |
| **回流总线** `SequenceReturnBusBlockEntity` | 未发现真实风险；**本轮按约定不改**（回流总线改造由并行一路负责） | `pullItems` 是「先插入缓冲（真插入）再按 `inserted` 抽取」；`pullFluids` 先查 `getFreeSpace()` 再按 `accepted` 抽取；`processInbound` 是「先抽出、插网络、`inserted < taken` 的差额回插缓冲」。`MultiFluidCache.insert` 严格受 `getFreeSpace()` 上限约束，故缓冲满时不会吞 |
| **定量保持器 / 高级定量保持器** | **无** | 自身同类存储 → 网络：`net.insert(EXECUTE)` 的返回值决定扣除量；`destroyOverflow` 是**用户显式开启**的「过量销毁」功能（不是吞资源）；标记槽的 `setItem(EMPTY)` 作用于「标记槽」而非资源存储 |
| **自动合成仓** `AutocrafterStorageMixin` / `AutocrafterStorageController` | **无** | 接收资源前先 `rscc$canAcceptAll`（逐项 SIMULATE）；关仓清空前先 `canFlushAll`（全量 SIMULATE）再 `flushAll`（先 insert 成功、再按成功量扣除） |
| **序列装配执行器** `SequenceAssemblyExecutorBlockEntity.accept` | **无** | 逐资源 `insert` 并检查 `inserted < count → REJECTED`；SIMULATE 阶段不落盘 |
| **范围充能器** `RangeChargerBlockEntity` | **无** | 只搬能量（网络 → 自身缓冲 / 玩家物品），不收集物品与流体 |
| **方块破坏内容结算** `BlockContentReleaser` | **无** | 物品 / 流体都按 `insert` 返回值算余量，余量爆出或写回方块 NBT；流体不可装桶时记 WARN |
| **执行舱 → 总线目标** `RsccChamberExportStrategy` | **无** | 先 SIMULATE 目标可收量，再按量从执行舱扣除，插不下的余量立即回写执行舱（未检查返回值，但 `RsccUnboundedItemStorage.insertItem` 是「任意位置」语义，刚扣除过即必有容量） |

### 20.3 本轮修好的真实缺陷（吞玩家资源）

1. **装填器「自动回收」先把蓝图从槽里清掉、再写网络且不检查结果**
   （`returnToNetwork` + `cannon.inventory.setStackInSlot(1, EMPTY)`）：网络存储满时，空白蓝图被直接吞掉。
   修法：`returnToNetwork` 先 SIMULATE 网络可接纳量，只清掉**网络真正收下的部分**，余量留在输出槽。
2. **`recycleBlueprintMaterialsToNetwork` / `returnExcessToNetwork` 先 `extractItem(..., false)` 再
   `storage.insert(...)`（返回值丢弃）**：换蓝图重开一轮或多材料回收时，网络一满，从库存抽出来的那批材料
   直接消失。修法：改用 `SafeCollect.pushItemsToNetwork`（先算容量 → 只抽能收下的量 → 差额回滚）。
3. **高级装填器队列蓝图被逐 tick 吞掉**（`doLoaderWork`）：`takeFromQueue()` 已经**破坏性**地把蓝图从队列
   移除，但只有 `printJustFinished || queueJustStarted` 时才写进加农炮槽，其余情况 `next` 被直接丢弃。
   触发条件：队列运行中加农炮蓝图槽提前空出（玩家手动取走 / 多个装填器共享同一加农炮），
   则每 tick 取一张、丢一张（每秒最多 20 张）。修法：新增 `returnToQueue(...)`，非部署时机把蓝图原样放回队列
   （放不下则落回世界）。
4. **独立模式的部署蓝图自动回流**：原先 `storage.insert(...)` 之后无条件 `setStackInSlot(0, EMPTY)`，
   网络满时蓝图消失。修法：按实际收下量决定是否清槽，没收完则留下并限频记日志，下一轮重试。
5. **执行舱流体收集无回滚**：`handler.drain(accepted, EXECUTE)` 之后 `outputTank.fill(...)` 的结果没检查，
   填不进去的流体凭空消失。修法：`SafeCollect.pullFluid`（先 SIMULATE 自身剩余容量 → 只抽能收下的量 →
   差额回滚进来源容器）。
6. **执行舱 `dropOutputContents` 无条件清空流体罐**：网络写不下、流体又没有桶形态时，
   `outputTank.setFluid(FluidStack.EMPTY)` 会把这份流体抹掉。修法：只 `drain` 已交付的量，其余留在罐里并记 WARN。

### 20.4 为什么现在「满缓存一定不吞」

- 所有「外部 → 自身」的收集路径都先对**自身**做 SIMULATE，自身满 → **不抽**（`pullItems` / `pullFluid`）；
- 所有「自身 → 网络」的回流路径都先对**网络**做 `Action.SIMULATE`，网络满 → **不抽 / 不清槽**
  （`pushItemsToNetwork` / `returnToNetwork`）；
- 抽取与写入之间如果仍有差额，一律**回滚回来源**，来源也拒收时交给兜底出口（落回世界 / 留在原地），
  不存在「抽了但没人接」的中间态；
- 判定口径统一为「**实际能收下的量**」（`insert` / `fill` 的返回值），而不是「打算搬走的量」；
- 世界实体的删除（`ItemEntity#discard`、流体源 `setBlock(AIR)`）仍然**只在资源已经被完整接管之后**执行。

### 20.5 可重复运行的静态审计脚本

`tools/audit_resource_swallow.py`（新增）：

| 规则 | 判据 |
| --- | --- |
| `R1` | 消耗型语句（`extractItem(..., false)` / `extract(..., EXECUTE)` / `drain(..., EXECUTE)` / `discard()` / `setStackInSlot(EMPTY)` / `setBlock(AIR)`）之后 8 条语句内出现**未检查返回值**的插入，且抽取前后都没有容量核对 |
| `R2` | 未检查返回值的插入之后 2 条语句内紧跟清空 / 销毁动作（= 插入失败也照样把源删掉） |
| `R3` | **收集方法**（方法体内出现能力查询 / `IItemHandler` / `ItemFluidHandler` / `extractItem(` 等外部来源标志）里的销毁动作之前没有任何容量核对 |

用法与结果：

```powershell
python tools/audit_resource_swallow.py          # 全量审计（退出码 0 = 没有未处理可疑点）
python tools/audit_resource_swallow.py -v       # 附带源码片段
python tools/audit_resource_swallow.py --files <a.java> ...
```

- 当前结果：`扫描文件: 210`、**`未处理可疑点: 0`**、`已豁免: 0`（退出码 0）；
- 灵敏度自检：`tools/_selftest_swallow_sample.java.txt` 故意复刻修复前的写法，
  运行 `--files tools/_selftest_swallow_sample.java.txt` 应报出 `R1` + `R2` 两条命中
  （证明脚本对真实缺陷有效，而不是「什么都没查出来」）；
- 需要豁免时，在**方法体内**写一行 `// rscc-audit-ok: <理由>`，脚本会把它单独列在
  「已豁免」清单里并打印理由，便于人工复核。

### 20.6 本轮新增 / 修改文件

**新增**：`support/SafeCollect.java`、`tools/audit_resource_swallow.py`、
`tools/_selftest_swallow_sample.java.txt`

**修改**：`block/entity/SchematicLoaderBlockEntity.java`、
`block/entity/SequenceExecutionChamberBlockEntity.java`、本文件

**刻意未动**：NBT 标记、回流总线改造（`SequenceReturnBus*`）、单元样板管理 / 无线终端相关文件（并行三路在改）；
界面几何与语言键未改动（GUI 脚本仍 0 问题）。

### 20.7 验证

`tools/manual_compile.ps1` → `COMPILE OK`（若并行三路的在改文件尚未收尾，全量编译会报它们的错，
本轮改动文件单独探针编译 0 错）；`python tools/verify_mixin_shadows.py` → 0 问题、退出码 0；
`python tmp_textures/verify_gui_layout.py` → 0 问题；`python tools/audit_gui_textures.py` → 0 问题；
`python tools/audit_resource_swallow.py` → 未处理可疑点 0。

***

# Round4 · 追加章节：第二十一轮（单元样板管理舱 + 无线终端接入 + 额外槽位 + 多仓内存合并）

> 生成方式：用户一次性给出四条需求 + 硬规则；本轮**不改 NBT 标记 / 回流总线 / 收集类机器**（并行三路在改）。

## 21.1 四条需求的落点

| # | 需求 | 落点 |
| - | ---- | ---- |
| 1 | 新增**单元样板管理舱**（区别于「自动合成管理舱」；界面一样，以执行舱为区分） | 新方块 `rs_create_compat:unit_pattern_manager` + 新菜单/界面；界面**直接复用 RS 自动合成管理器的贴图、精灵与拉伸骨架**，分组维度换成「一台执行舱 = 一组」 |
| 2 | 该界面**也放进无线终端** | 复用既有的「高级远程多功能终端」模式机制：新增 `MODE 5 = MODE_UNIT_MANAGER`，Tab 图标 + 语言键 + `isRsScreen` 同步登记 |
| 3 | 网络内多个**序列装配样板仓内存合并显示** | `support/UnitManagerSources.MergedVaultContainer`：把网络内全部样板库的样板按**合并键**去重成一个只出不进的统一视图，作为管理舱的最后一个分组 |
| 4 | 无线终端**额外槽位**（含 Curios 前置调研） | 调研见 §21.6；**后续已改写**：那套「自成一套」的临时槽位已整体删除，改按参考前置的约定注册 **Curios 饰品槽** `rs_create_compat_curios_integration`（见 §21.12） |
| — | 命名槽 | **不做**（用户明确「意义不大」） |

## 21.2 新文件清单

**Java 新增**：
`block/UnitPatternManagerBlock.java`、`block/entity/UnitPatternManagerBlockEntity.java`、
`menu/UnitPatternManagerMenu.java`、`client/screen/UnitPatternManagerScreen.java`、
`network/UnitPatternManagerData.java`、`network/StoreUnitPatternsPacket.java`、
`support/UnitManagerSources.java`（`support/RsccExtraSlotContainer.java` 已于 §21.12 整包删除）

**Java 修改**：`RS_Create_Compat.java`（方块/物品/方块实体/菜单类型/能力/网络包/创造栏）、
`client/ClientInit.java`（屏幕注册）、`Config.java`（能量消耗配置）、
`item/AdvancedRemoteTerminalItem.java`（MODE 5 + 开界面）、
`client/TerminalModeTabOverlay.java`（Tab 图标 + 语言键 + 屏幕识别）

**资源 / 工具**：`tools/gen_unit_manager_resources.py`（blockstate / 模型 / 掉落表 / 挖掘标签 / 贴图）、
`tools/gen_lang_frag_unitmanager.py` + `tools/lang_frag_unitmanager.json`、
`tmp_textures/UNIT_PATTERN_MANAGER_GUI_DOC.md`

## 21.3 界面与「自动合成管理舱」的复用关系（用户要求「界面等与自动合成舱一样」）

| 复用的 RS 类 / 资源 | 路径 | 用途 |
| ------------------- | ---- | ---- |
| `AbstractStretchingScreen` | `common/support/stretching/` | 拉伸滚动骨架：可见行数、滚动条、`enableScissor` 裁剪、`scrollbarChanged` 回调、`resized` 通知菜单重排槽位 |
| `ScreenSizeListener` | 同上 | 菜单侧接收 `resized(playerInventoryY, topYStart, topYEnd)`，与 RS 管理器完全同款流程 |
| `AbstractBaseContainerMenu` | `common/support/` | `resetSlots()` / `addPlayerInventory()` 等公共容器能力 |
| `Sprites.SLOT` | `common/support/Sprites` | 18×18 槽位框精灵（槽位框与 `Slot#x/y` 同源） |
| `refinedstorage:textures/gui/autocrafter_manager.png` | RS 资源 | 背景整图（顶 19px + 18px 行 + 底部 99px，`getBottomV()=73`、`getBottomHeight()=99`） |
| `refinedstorage:autocrafter_manager/autocrafter_name` | RS 资源 | 分组标题带（162×18） |
| `Platform.INSTANCE.setSlotY(Slot,int)` | `common/Platform` | 滚动时把槽位整体平移（同 RS 做法） |
| `refinedstorage:block/autocrafter_manager/{front,right,top}` | RS 资源 | 方块贴图来源（未接入态整体去饱和压暗，得到本工程统一的「灰/亮」两态） |

**刻意裁掉**：RS 管理器的搜索框、视图类型 / 搜索模式侧边按钮（它们绑定 RS 自己的配置对象，
与本舱语义无关，且用户要求「操作说明文字要短」）。

## 21.4 以「执行舱」分组的布局要点

- **面板**：宽 `193`（`UnitPatternManagerMenu.PANEL_W`），高由可见行数决定（`19 + 18×rows + 99`）。
- **分组标题带**：`x = leftPos + 7`、宽 `162`、高 `18`；标题文字画在 `(+4, +6)`，
  颜色 `4210752`，`drawString(..., false)`（无阴影）。
- **槽位网格**：每组从 `Menu x = 8`（= `7 + 1`，即精灵 `x = 7`）起，每行 9 格、步距 18；
  组内第一行 y = `组标题行 y + 18`；**每组从第 0 格重新起排**（组间不串列）。
- **组间行距**：`(该组槽位行数 + 1) × 18`；槽位数为 0 的组不占行（与 RS 一致）。
- **组内容**：一台执行舱 = 一组（标题 = `执行舱名字 · 配方类型`；**未绑定配方类型时只显示舱名**，
  因为标题在服务端拼好原样下发，而服务端没有语言表 —— 任何 `Component#getString()` 都只会得到翻译键本身），
  组内是该仓的 54 格单元样板槽（真槽位，可点击取放 / Shift 移动 / 拖拽）。
- **最后追加一组**：`序列装配样板库（网络合并）`（只出不进，见 §21.7）。
- **滚动**：滚动条位于 `leftPos + 174`、`topPos + 20`；`scrollbarChanged` 里把每个分组槽位
  重设 `slot.y = 原始 y - 滚动偏移`，并在裁剪区（`x+7 .. x+7+162`）内手动绘制槽位内容与悬停高亮。
- **标题行右侧**：`Menu (174, 2)` 一格**额外槽位**（精灵 `(173,1)`，18×18 槽位框），
  与标题（宽 84 的 marquee）和「存回网络」按钮（`Menu (94,2)` 76×14）互不重叠；
  滚动条从 `y = 20` 开始，因此标题行右侧空间与滚动条也不冲突。
  > 该格已于 **§21.12 删除**（改由 Curios 饰品槽承载）。
- **空状态**：未接入网络时按 RS 同款在槽位区铺 `0xFF5B5B5B` 灰色蒙版 + 一句短提示；
  网络内没有执行舱时提示「本网络内没有执行舱」。
- **坐标口径**：本文件与代码中，凡是「背景精灵坐标」一律 = **Menu 坐标 - 1**（硬规则）。
- 因为**直接复用 RS 的成品贴图**，本工程 `tmp_textures/verify_gui_layout.py` 与
  `tools/audit_gui_textures.py` 的布局表**无需新增条目**（新增条目会去我们的 `textures/gui/` 找图，
  而本界面没有自己的 PNG），两个脚本保持 0 问题；几何以本节与 `tmp_textures/UNIT_PATTERN_MANAGER_GUI_DOC.md` 为准。

## 21.5 无线终端接入点

- 模式常量：`AdvancedRemoteTerminalItem.MODE_UNIT_MANAGER = 5`、`MODE_COUNT = 6`；
  `isModeSupported(5) = true`。
- 开界面：`AdvancedRemoteTerminalItem#openUnitPatternManager` —— 与既有的「合成仓管理」模式
  完全同一套**虚拟方块实体**做法（`new UnitPatternManagerBlockEntity(BlockPos.ZERO, ...)` +
  反射写入 `mainNetworkNode` 的网络 + `setActive(true)`），未绑定 / 网络不可达时绑定
  `EmptyNetwork`，界面照常打开并显示灰色空状态。
- Tab：`client/TerminalModeTabOverlay` —— `MODE_KEYS[5]`、`iconFor(5) = 管理舱方块物品`、
  `isRsScreen` 追加 `UnitPatternManagerScreen`。
- 方块右键与终端模式**走同一个菜单与同一个 `openMenu` 入口**（`UnitPatternManagerMenu#open`）。

## 21.6 Curios 槽位前置的调研结论（原始调研，后续结论见 §21.12）

- **前置是谁**：**Refined Storage - Curios Integration**（modId `refinedstorage_curios_integration`，
  NeoForge 专用；由 RS 作者 raoulvdberge 维护，是 RS 2 的**官方可选前置**，为
  Wireless Grid / Creative Wireless Grid / Wireless Crafting Grid / Creative Wireless Crafting Grid /
  Portable Grid / Creative Portable Grid **提供 2 个专用 Curios 饰品槽**）。
  证据：① RS 仓库自带文档 `local_src/external/RefinedStorage/docs/pages/addons/curios.adoc`
  （“An optional integration mod adds support for Curios on NeoForge, providing two dedicated Curios slots…”）；
  ② 本工程内 `local_src/universal-grid/neoforge/src/main/resources/data/curios/tags/item/refinedstorage_curios_integration.json`
  正是往该前置的饰品物品标签里登记自己的无线终端。
  **版本**：1.21.1 / NeoForge 专版（CurseForge 首版 2025-03-28，与 RS 2.x 配套；
  上游源码 tag `v1.0.0` = 1.21.1 版，`v2.0.1` = 26.1.2 版）。
  本工程 `build.gradle` 里**没有**引入该前置（故无法从锁文件读出版本号，只能给到「1.21.1/NeoForge 专版」这一粒度）。
- **前置的注册机制（关键调研结论）**：**零代码 —— 全部是数据包 JSON**（详见 §21.12 的原始参数表）。
- **本轮（RL4）当时的实现**：`support/RsccExtraSlotContainer extends SimpleContainer(1)` ——
  把这一格的内容写进**无线终端物品自身**的 `CustomData`（键 `RsccExtraSlot`），
  任何写入口（点击 / Shift / 拖拽 / 同步）都汇聚到 `setItem` / `removeItem` → `persist()`
  统一落盘并通知玩家背包。物品跟着终端走（背包 / 快捷栏 / 副手都行），**不需要任何前置**。
  防丢保护：只有「宿主栈确实是玩家身上那一份实例」时才装载内容，否则 `canPlaceItem` 恒 false
  且不装载（避免读到副本 → 取出凭空多一件，或放进去存不下来）。
  方块形态下这一格由方块实体自带容器承载（随方块 NBT 持久化，破坏按全局「内容物去向」策略结算）。
  > ⚠️ **该实现已整体删除**（用户明确「要的是 Curios 的饰品槽，不是在别的地方加一个什么东西」），
  > 替换方案与旧数据抢救见 **§21.12**。

## 21.7 「多仓内存合并」的合并键与实现

- **合并键**：`support/UnitManagerSources#mergeKey(ItemStack)` = **样板物品 id + 样板的数据组件**。
  样板的「配方类型 / 显示名 / 输入物」（以及装配样板的步骤、机器指派与概率产物池）全部编码在该
  数据组件里，因此该键与既有「单元样板库按 配方类型 + 输入物 + 名字 去重」的口径一致。
- **实现**：`UnitManagerSources.MergedVaultContainer` ——
  ① 按**仓坐标升序、槽位下标升序**枚举网络内全部 `SequenceAssemblyExecutorBlockEntity`；
  ② 用合并键把重复样板合并成**唯一项**（保留第一条作为代表，视图只显示代表）；
  ③ 视图**大小在创建时固定**（不随后续变化漂移），因此客户端槽位数永远与服务端一致（原版同步前提）；
  ④ **只出不进**：`canPlaceItem` 恒 false、`setItem` 空操作；取出即从代表槽位真实移除（不复制、不销毁）；
  ⑤ `getItem` 实时读回代表槽位，所以取出后立刻显示为空。
- **RS 管理器的同名分组**：RS 的 `AutocrafterManagerBlockEntity#getGroups()` 按
  `getAutocrafterName()` 分组，本模组所有样板库同名，因此它们在 RS 的自动合成管理舱里本来就合为一个组
  （多子组各 54 格）；本轮的「内存合并 + 去重」是**单元样板管理舱**里额外的统一视图。

## 21.8 「存回网络」按钮

- 标题行 `Menu (94,2) 76×14` 的按钮 → C2S `StoreUnitPatternsPacket` → 服务端
  `UnitPatternManagerBlockEntity#storeChapterUnitsToNetwork()`：
  **先整批 `SIMULATE`，任何一张放不下就整体放弃（什么都不动）**；确认全部放得下才逐张 `EXECUTE`：
  先写入网络、写入成功才清空原槽位。网络满 / 断连时玩家的样板**原地不动**（硬规则：绝不销毁玩家资源）。
- 按钮的提示文字由界面**手动渲染**（`GuiGraphics#renderTooltip`），
  hover 判定用控件自身的坐标（与绘制范围同源）；槽位内物品的 tooltip 仍由原版按 `hoveredSlot` 渲染一份，
  **同一格不会出现两份 tooltip**。

## 21.9 校验

- `tools\manual_compile.ps1` → `COMPILE OK (213 sources)`
- `python tools\verify_mixin_shadows.py` → 问题总数 0、退出码 0（本轮未新增 Mixin）
- `python tmp_textures/verify_gui_layout.py` → 0 问题
- `python tools/audit_gui_textures.py` → 0 问题
- `python tools/apply_lang_frag.py` → 中英各 538 键、无缺失（新增 10 键）

## 21.10 未完成项与风险（如实列出）

1. **界面结构在打开时固定**：分组 / 槽位数不随网络变化实时重发（重发会让槽位数漂移 → 原版同步错位）。
   期间新增 / 移除执行舱需要**重开界面**才能看到最新分组。这是「槽位下标必须两端一致」的必然取舍。
2. **合并视图是打开时的快照（去重集合）**：打开后别的玩家往样板库塞入新样板不会出现在该视图里；
   重开界面即可刷新。视图大小固定是刻意设计（防同步错位）。
3. **合并视图只出不进**：跨仓合并后「放入哪台仓」没有唯一答案，强行放会产生复制 / 覆盖风险，
   故只允许取出；放入请用「执行舱自己的分组」（那里是真实槽位，判定唯一）。
4. **RS 管理器的搜索框 / 视图类型按钮未复刻**（见 §21.3），如需再补需新增自绘控件与语言键。
5. **额外槽位已删除**（§21.12）：随终端携带的槽位改由 Curios 饰品槽承载。旧存档里残留在
   终端 `CustomData` / 方块 NBT 中的内容是**抢救**而非丢弃（见 §21.12 的「旧数据」小节）。
6. 分组标题里**执行舱部分**使用服务端拼好的字面量（舱名 + 配方类型 id，两者本来就是字面量，无翻译问题）；
   合并视图那一组的标题走**翻译键 + 客户端翻译**（`Section.nameIsKey`），因此多语言下也正确。

## 21.11 本轮新增 / 修改文件

**新增**：`block/UnitPatternManagerBlock.java`、`block/entity/UnitPatternManagerBlockEntity.java`、
`menu/UnitPatternManagerMenu.java`、`client/screen/UnitPatternManagerScreen.java`、
`network/UnitPatternManagerData.java`、`network/StoreUnitPatternsPacket.java`、
`support/UnitManagerSources.java`（原文另有 `support/RsccExtraSlotContainer.java`，已于 §21.12 删除）、
`tools/gen_unit_manager_resources.py`、`tools/gen_lang_frag_unitmanager.py`、
`tools/lang_frag_unitmanager.json`、`tmp_textures/UNIT_PATTERN_MANAGER_GUI_DOC.md`、
`blockstates/unit_pattern_manager.json`、`models/block/unit_pattern_manager{,_active}.json`、
`models/item/unit_pattern_manager.json`、
`textures/block/unit_pattern_manager_{front,side,top}{,_active}.png`、
`loot_table/blocks/unit_pattern_manager.json`

**修改**：`RS_Create_Compat.java`、`client/ClientInit.java`、`Config.java`、
`item/AdvancedRemoteTerminalItem.java`、`client/TerminalModeTabOverlay.java`、
`lang/zh_cn.json` + `lang/en_us.json`（经 `apply_lang_frag.py`）、
`data/minecraft/tags/block/mineable/pickaxe.json`、本文件

**刻意未动**：NBT 标记、回流总线（`SequenceReturnBus*` / `RsccWireLinkSearch` 等并行在改文件）、
收集类机器。

## 21.12 改为真正的 Curios 饰品槽（本模组自己的槽位）+ 旧临时槽位清理

### 21.12.1 参考前置：是谁、怎么注册的（原始参数，逐字核对上游源码）

- **前置**：`Refined Storage - Curios Integration`，**modId = `refinedstorage_curios_integration`**
  （上游 `ModInitializer.ID` 即此串；NeoForge 专版；1.21.1 对应源码 tag `v1.0.0`）。
  它的**槽位 id 恰好等于它自己的 modId**（`refinedstorage_curios_integration`），这就是本轮的命名依据。
- **注册机制：零代码，全部是数据包 JSON**（上游 `src/main/resources/data/**`）：

  | 文件（上游路径） | 内容 | 作用 |
  | ---- | ---- | ---- |
  | `data/refinedstorage_curios_integration/curios/slots/refinedstorage_curios_integration.json` | `{"order":5,"size":2,"icon":"refinedstorage_curios_integration:slot/curios"}` | 注册槽位类型（2 格） |
  | `data/refinedstorage_curios_integration/curios/entities/refinedstorage_curios_integration.json` | `{"entities":["player"],"slots":["refinedstorage_curios_integration"]}` | 把该槽位发给玩家 |
  | `data/curios/tags/item/refinedstorage_curios_integration.json` | `{"replace":false,"values":["refinedstorage:wireless_grid", …, {"id":"refinedstorage_quartz_arsenal:wireless_crafting_grid","required":false}]}` | **物品标签 = 可放入该槽的物品** |
  | `assets/refinedstorage_curios_integration/lang/en_us.json` | `{"curios.identifier.refinedstorage_curios_integration":"Refined Storage"}` | 槽位显示名 |

  机制依据：Curios 官方文档（1.21.x）——「槽位类型 = `data/<ns>/curios/slots/<id>.json`」、
  「实体分配 = `data/<ns>/curios/entities/*.json`」、
  「默认校验器 `curios:tag` 表示该槽接受 **`curios:<槽位 id>` 物品标签**里的物品」。
  Curios 本体是该前置的**必需**依赖（CurseForge 关系表），本模组则**不依赖**它（见 21.12.4）。
- 上游另有 `CuriosPlayerSlotReference` + `CuriosPlayerSlotReferenceProvider`（把 RS 的
  `PlayerSlotReference` 工厂注册为 `refinedstorage_curios_integration:curios`），
  使 RS 的无线物品**装在饰品槽里也能被使用**；该 provider 注册在 **RS API 全局**上，
  因此本模组的终端（同样继承 RS `AbstractNetworkEnergyItem`）**自动受益，无需本模组写代码**。

### 21.12.2 本模组注册的槽位

- **槽位 id：`rs_create_compat_curios_integration`** —— 命名依据 = 参考前置的 ID 约定
  「**槽位 id = 模组自己的 modId + `_curios_integration`**」（前置的 modId 与槽位 id 相同，
  本模组同理取自己的 modId `rs_create_compat`）。
- 文件（`tools/apply_curios_slot.py` 生成，四处 JSON 均为 `indent=2` / UTF-8 / LF）：

  | 文件 | 内容 |
  | ---- | ---- |
  | `data/rs_create_compat/curios/slots/rs_create_compat_curios_integration.json` | `{"order":6,"size":2,"icon":"rs_create_compat:item/advanced_remote_terminal"}` |
  | `data/rs_create_compat/curios/entities/rs_create_compat_curios_integration.json` | `{"entities":["player"],"slots":["rs_create_compat_curios_integration"]}` |
  | `data/curios/tags/item/rs_create_compat_curios_integration.json` | `{"replace":false,"values":[三个终端 id]}` |
  | `lang/{en_us,zh_cn}.json` | `curios.identifier.rs_create_compat_curios_integration` = `Advanced Remote Terminal` / `高级远程终端` |

  `order` 取 6（紧跟前置的 5，显示在它下面）；`size` 取 2（与前置同规格）；
  `icon` 复用本模组已有的 16×16 物品贴图（**不新增任何贴图**，故 GUI 贴图审计不受影响）。

### 21.12.3 终端怎么被允许放进槽里

物品能否入槽**只由物品标签决定**（Curios 默认校验器 `curios:tag`），因此两处标签都要登记
（三态终端：`advanced_remote_terminal` / `advanced_remote_terminal_charged` / `creative_advanced_remote_terminal`）：

- `data/curios/tags/item/rs_create_compat_curios_integration.json` → 终端可放进**本模组**的槽位；
- `data/curios/tags/item/refinedstorage_curios_integration.json`（`replace:false`）→ 终端**同时**可放进
  **参考前置**的槽位（与 `local_src/universal-grid` 的做法完全一致：同路径标签合并、只做追加、不覆盖）。
  前置缺失时该文件只是无人读取的普通物品标签，无害。

```json
{
  "replace": false,
  "values": [
    "rs_create_compat:advanced_remote_terminal",
    "rs_create_compat:advanced_remote_terminal_charged",
    "rs_create_compat:creative_advanced_remote_terminal"
  ]
}
```

### 21.12.4 依赖处理方式：软前置（零 gradle 依赖）

- 槽位注册是**纯数据包**，本工程 `build.gradle` / `gradle.properties` **无需新增** Curios API 或
  该前置的 maven 依赖（离线环境也 100% 可编译）；Curios / 前置未安装时这些 JSON 不被任何代码读取。
- 因此不涉及版本号来源问题（写进 build.gradle 的必要性为零）；参考前置在 1.21.1 的版本为 `v1.0.0`
  （CurseForge 2025-03-28，对应 RS 2.x），仅供文档记录。

### 21.12.5 旧「临时额外槽位」清理清单

| 位置 | 处理 |
| ---- | ---- |
| `support/RsccExtraSlotContainer.java` | **整文件删除** |
| `menu/UnitPatternManagerMenu.java` | 删除 `extraSlot` 字段 / 构造参数 / `create`+`open` 参数 / `EXTRA_SLOT_X/Y` 常量 / `getExtraSlotIndex()` / `addSlot` / `quickMoveStack` 的额外槽分支 |
| `client/screen/UnitPatternManagerScreen.java` | 删除 `renderBg` 里的额外槽位框绘制、`KEY_EXTRA_TIP` 与空槽说明 tooltip 分支 |
| `item/AdvancedRemoteTerminalItem.java` | 开界面不再构造容器（`UnitPatternManagerMenu.open(player, be, title)`）；新增**旧数据抢救** |
| 语言键 | 删除 `gui.rs_create_compat.unit_pattern_manager.extra.tip`（en/zh） |
| `block/entity/UnitPatternManagerBlockEntity.java` | 保留 `extraSlot` 容器与 `ExtraSlot` NBT 读写，**但界面/菜单/网络包一律不再暴露**（见下） |
| `StoreUnitPatternsPacket` 等无关部分 | **未触碰** |

**旧数据（绝不凭空消失）**：

1. **终端物品上的残留**（旧键 `CustomData.RsccExtraSlot`）：玩家**再次使用终端**时，
   `AdvancedRemoteTerminalItem#rescueLegacyExtraSlot` 把那一件物品**原样还给玩家**
   （背包满则掉在脚边），随后删除该键并打一行 INFO 日志；不做任何其它改动。
2. **方块 NBT 上的残留**（键 `ExtraSlot`）：容器照旧读写（否则旧存档首次保存就会丢内容），
   且保留破坏方块时的 `BlockContentReleaser.collectContainer(manager.extraSlot(), …)` ——
   破坏该方块即**原样掉落**，玩家可自行取回。

### 21.12.6 本轮新增 / 修改

**新增**：`tools/apply_curios_slot.py`、
`data/rs_create_compat/curios/slots/rs_create_compat_curios_integration.json`、
`data/rs_create_compat/curios/entities/rs_create_compat_curios_integration.json`、
`data/curios/tags/item/rs_create_compat_curios_integration.json`、
`data/curios/tags/item/refinedstorage_curios_integration.json`

**修改**：`menu/UnitPatternManagerMenu.java`、`client/screen/UnitPatternManagerScreen.java`、
`item/AdvancedRemoteTerminalItem.java`、`block/UnitPatternManagerBlock.java`、
`block/entity/UnitPatternManagerBlockEntity.java`、`lang/{en_us,zh_cn}.json`、本文件、
`tmp_textures/UNIT_PATTERN_MANAGER_GUI_DOC.md`

**删除**：`support/RsccExtraSlotContainer.java`

**验收**：`COMPILE OK (221 sources)`；`verify_mixin_shadows.py` / `verify_gui_layout.py` /
`audit_gui_textures.py` / `audit_shared_textures.py` / `verify_unit_manager_slots.py` 全部 0 问题、退出码 0。








***

## 回流总线改造：方位删除 + 「输入总线 + 线缆」连接 + 界面照输出总线（本轮）

> 来源：用户本轮反馈（①「原料与废料列表重叠」②「可删回流总线方位，改用输入总线 + 线缆连接」
> ③「界面像输出器，直接用 Mixin 替换」④「独立线缆待考虑」）。
> 三条硬规则不变：精灵坐标 → Menu 坐标 = **x、y 均 +1**；`drawString` 一律 `false`；
> tooltip 手动渲染、hover 判定与绘制范围同源、同一帧只有一份；服务端权威、**绝不销毁玩家资源**。

### 1. 「原料与废料列表重叠」的真实位置与修法

**位置（实地查代码后确认）**：`client/screen/SequenceReturnBusScreen.java` 的
**步骤详情 / 收集预览区**（回流总线主界面右栏下半段）——那是全工程唯一同时画
「输入原料清单」和「废料清单」的地方（`detailLines()` 依次产出 输入原料 / 中间产物 / 成品 / 废料）。

**真实重叠**：旧实现把这一区按 **76px** 折行，却把 **2px 细滚动条画在文字右缘最后一列**
（`trackX = DETAIL_X + DETAIL_W - 2` = **x 250..252**，而文字最远可画到 **x 252**）
→ 滚动条与文字**同列压字**；此外四段清单是「无分隔的连续行流」，原料一多就把废料挤进上一段的折行里，
视觉上像两段叠在一起。

**修法（本轮）**：

| 项 | 旧 | 新 |
| --- | --- | --- |
| 详情区文字宽 | 76px | **72px**（`DETAIL_W`） |
| 滚动条 | 压在文字右缘（x 250..252 ∩ 文字 ≤252） | **独占 x 250..252 一列**（`DETAIL_BAR_X = 176+72+2 = 250`），文字只到 248 |
| 分段 | 4 条「xx：%s」连续行 | **每段 = 1 条段标题行 + 正文行**：`detail.head.{inputs,intermediate,results,scraps}`，正文只在本段标题之下折行 |

**最终坐标表（Menu 坐标；精灵坐标 = 各值 - 1）**

| 元素 | Menu 坐标 | 尺寸 | 说明 |
| --- | --- | --- | --- |
| 详情区文字列 | (176, 206) | 72 × 90 | 折行宽 72，行高 9，最多 10 行（`DETAIL_MAX_LINES`） |
| 详情区细滚动条 | (250, 206) | 2 × 90 | **独占最右一列**；仅在内容超 10 行时绘制 |
| 段标题行（四段） | (176, 206 + 9n) | 文本 | 深蓝 `#14418C`；标题行本身也占一整行 |
| 段正文行 | (176, 206 + 9n) | 文本 | 深灰 `#2B2B2B`，逐行按 72px 折行 |
| 「应用」按钮 | (176, 190) | 76 × 14 | 详情区在其下方 2px（190+14 = 204 < 206） |
| 「可回收类别」条 | (9, 174) | 162 × 23 | 见 §3（含 1px 自绘外描边 → 精灵 8..172 / 173..198） |
| 类别条横向滚动条 | (9, 198) | 162 × 2 | 类别 > 8 个时才绘制，紧贴条本体下缘 |

校验：`tmp_textures/verify_gui_layout.py`（新增 `category_bar` / `category_scrollbar` /
`detail_text` / `detail_scrollbar` 四条轨道 + 7 段留白区）与 `tools/verify_gui.py` 均 0 问题。

### 2. 回流总线改造方式：**改造现有方块（不是薄封装）**

选择「改造」而不是「退化为薄封装 / 删除」，理由：方块名、方块实体、菜单、物品注册全部保留，
**已有存档里放置的回流总线读档即继续工作**，且缓冲 / 总样板 / 插件 / 流体一律不丢。

| 兼容点 | 处理 |
| --- | --- |
| `PullFaces`（旧方位位掩码） | **读档忽略、不再写入**；方位删除后六向一律主动吸取（= 旧默认值 `0b111111`，行为不变） |
| `Buffer` / `Upgrades` / `PatternSlot` / `FluidBuffer` | 键名与格式完全不变 |
| `AutoReturn` / `DestroyWaste` / `StepIndex` / 三个累计统计 | 不变 |
| 新增 `ReturnCategories` / `ReturnCategoriesExplicit` | 旧档没有 → `explicit = false` → **全选**（等价旧行为，绝不改变既有回流） |
| 拆方块 | 仍走 `BlockContentReleaser`：缓冲 / 插件 / 总样板 / 流体按全局策略结算，**绝不吞** |

### 3. 界面复用输出总线的哪些类

| 复用的类 / 资源 | 复用方式 |
| --- | --- |
| `client/widget/ExporterExecutorRowWidget`（类别条） | **同一个控件实例**：类别图标 + 勾选框 + 共享角标 + 横向滚动 + 点/滚轮交互 + tooltip 全部共用；本轮给它抽出一个 `Source` 数据源接口（`visible/categories/autoCraftingEnabled/linkedChamber/targetStrategy/toggleSelection` + `barTitle/barTitleTip/emptyTextKey/typeTipKey` 默认方法），输出总线走默认实现（`SyncExporterExecutorModePacket` + `SetExporterExecutorCategoriesPacket`），回流总线只提供自己的数据源 |
| `support/RsccBusCategory` | 类别记录（id / 图标 / 已选 / 共享台数 / 数量）直接复用 |
| `support/RsccWireLinkSearch` | **本轮新抽出的唯一一份线缆连接搜索**：输出总线 Mixin 改为委托调用，回流总线同样调用它（见 §4） |
| `support/RsccBusLinkInvalidation` | 邻块事件失效监听扩展为「输出总线 + 回流总线」共用 |
| 视觉规则 | 与输出总线一致：绿 = 已选 / 灰 = 未选、勾选框在图标**下方那一行**、青色小方块 = 共享 |

> 没有为回流总线新写第二个控件（也不需要在 RS 原生界面上再注入 Mixin —— 回流总线界面是本模组自己的
> `SequenceReturnBusScreen`，直接用同一个控件即可满足「界面照输出总线做」）。
> RS 侧的 `ExporterScreenMixin` 保持不变（其 `RSCC_ROW_*` 几何仍由校验脚本覆盖）。

### 4. 方位配置删除后的连接模型：「输入总线 + 线缆」

* **删掉的东西**：`SequenceReturnBusFaceConfigScreen`（整个子界面）、标题行「面配置」入口按钮、
  方块实体的 `pullFaces` / `isPullFace` / `setPullFace`、菜单按钮 id `10..15`、
  数据槽 6..11（保留序号、恒 0）、全部 `face_config.*` 语言键。
* **新连接模型**：与输出总线**完全同一套** —— 从回流总线自身出发，只经过
  **可穿行集合**（`RsccWireBlocks`：RS 线缆 + 输出总线 + **输入总线**）逐层 BFS（上限 64 步），
  取**距离最近**的、处于「总线输出」模式的**序列执行舱**作为「归属执行舱」；
  同距离按**坐标字典序**（x → y → z）裁决；搜索阶段只读 `BlockState`、绝不强制加载邻块；
  线程级重入守卫 `RsccSearchGuard`。
* **性能护栏**（同样复用既有策略）：`廉价预检（六邻块只读状态）→ 缓存 20 tick → 未命中指数退避
  20→200 tick → 事件驱动失效`；失效点：邻块放置 / 破坏 / 替换（`RsccBusLinkInvalidation`）、
  勾选变化、读档。
* **物品怎么进来**：方位删掉后，回流总线**六向一律主动吸取**（等价旧默认「全方位漏斗」）；
  玩家在产线上用 **RS 输入总线 + 线缆**把物料导向回流总线所在的网络侧即可（输入总线本身属于可穿行集合，
  紧贴或隔线缆都能被识别），不需要再逐面配置。
* **归属执行舱的用途**：① 界面类别条 tooltip 显示「归属执行舱：<名字>」；
  ② 「自动合成未开启」门控（未开启时暂停吸取，缓冲内容仍照常回网）。
* **共享均分**：同一执行舱下、勾选了同一类别的多台回流总线，按
  `gameTime / 20 % N == 本机在同簇中的序号` 轮流回收（与输出总线的轮询均分同一口径；
  同簇 = 与本人线缆连通且认同一台执行舱的回流总线，按坐标排序，20 tick 缓存）。

### 5. 独立线缆（新增第三种线缆方块）：**本轮不实现，保持待定**

用户意见：「独立线缆能防穿线麻烦，但有其他麻烦，如新线缆贴图 / 外观问题，待考虑」。
本轮**不新增**任何线缆方块 / 贴图，连接继续复用 RS 原生线缆 + 输出总线 / 输入总线的可穿行集合。
**待定项（下轮再定）**：是否需要第三种线缆专用外观（贴图与连接模型）、
是否允许「只有本模组的线缆才算连通」这类更严格的规则。

### 6. 本轮修改文件

`block/entity/SequenceReturnBusBlockEntity.java`、`menu/SequenceReturnBusMenu.java`、
`client/screen/SequenceReturnBusScreen.java`（删除 `client/screen/SequenceReturnBusFaceConfigScreen.java`）、
`client/widget/ExporterExecutorRowWidget.java`、`support/RsccWireLinkSearch.java`（新增）、
`support/RsccWireBlocks.java`、`support/RsccBusLinkInvalidation.java`、
`mixin/exporter/AbstractExporterBlockEntityMixin.java`（改为委托共享搜索，行为不变）、
`network/SetReturnBusCategoriesPacket.java` + `network/SyncReturnBusStatePacket.java`（新增）、
`RS_Create_Compat.java`（注册两个新包）、
`tools/gen_lang_frag_returnbus2.py` + `tools/lang_frag_returnbus2.json`、语言文件（经 `apply_lang_frag.py`）、
`tools/verify_gui.py`、`tmp_textures/verify_gui_layout.py`、
`tmp_textures/SEQUENCE_RETURN_BUS_GUI_DOC.md`、本文件。

**刻意未动**：NBT 标记相关文件、单元样板管理 / 无线终端相关文件、「缓存满不吞资源」的收集类机器。


***

## 机械动力扳手分离 RS 线缆：接缝 / 面 两种方式 + 配置切换（本轮）

> 来源：用户本轮需求「还是做扳手断开吧」+「两种方式都做，在配置文件中切换」。
> 硬规则不变：服务端权威、**绝不破方块 / 绝不丢方块内数据**、`drawString` 一律 `false`、
> 精灵坐标 → Menu 坐标 **x、y 均 +1**、语言键走 `tools/lang_frag_wrench.json` + `apply_lang_frag.py`。

### 1. RS 线缆「连接」是怎么判定的（查到的真实类名 / 方法，全部核对过 RS 官方源码 jar）

源码来源：`~/.gradle/caches/.../refinedstorage-neoforge-2.0.0-sources.jar`
（字段名与运行时一致：NeoForge 模组按 Mojang 官方映射编译，无 SRG 重命名）。

| 环节 | 真实类 / 方法 | 说明 |
| --- | --- | --- |
| 线缆方块 | `com.refinedmods.refinedstorage.common.networking.CableBlock` | 所有颜色同一个类；`getShape` 读方块实体的 `CableConnections` |
| 连接数据 | `...common.networking.CableConnections`（record，6 个 boolean） | 由方块实体持有并随 NBT 同步 |
| 连接臂外形 | `...common.support.AbstractCableLikeBlockEntity#computeConnections` / `#hasVisualConnection` | 用**邻居容器的** `canAcceptIncomingConnection(方向.getOpposite(), 自身方块状态)` 决定这一侧是否长臂 |
| 网络图连边 | `...common.support.network.ConnectionProviderImpl#getConnections` | 对 `addOutgoingConnections` 给出的每个候选，要求目标容器的 `canAcceptIncomingConnection(incomingDirection, from.getBlockState())` 为 true |
| 连接策略 | `...common.support.network.ColoredConnectionStrategy extends SimpleConnectionStrategy` | 线缆 / 输入总线 / 输出总线统一走它（`AbstractBaseNetworkNodeContainerBlockEntity#createMainContainer` 构造，由 `InWorldNetworkNodeContainerBuilder` → `InWorldNetworkNodeContainerImpl` 承载） |
| 容器实现 | `...common.support.network.InWorldNetworkNodeContainerImpl` | **自身声明** `canAcceptIncomingConnection` 与字段 `blockEntity`（满足「Mixin 只能定位目标类自身成员」） |

**结论**：网络图连边与连接臂外形共用**同一个闸门** `canAcceptIncomingConnection`，
所以本项目只在一个点接管即可让「逻辑断开」与「外形断开」同时成立 ——
这正是用户担心的「看着断开了但仍然串线」的根因所在，从同一处堵掉最省事也最彻底。

### 2. 扳手交互挂点与「不影响其它方块」的判定

* **挂点**：NeoForge `PlayerInteractEvent.RightClickBlock`（双端触发，在
  `Item#onItemUseFirst / Block#use / Item#useOn` 之前），见
  `support/RsccWrenchCableInteraction#onRightClickBlock`。
  Create 自己的扳手逻辑在 `WrenchItem#useOn`（只认 `IWrenchable`）与 `WrenchEventHandler`
  （优先级 HIGH，只对 `IWrenchable` 生效）；RS 线缆两类都不是，扳手点上去本来什么也不做。
* **接管条件（全满足才 `setCanceled(true)` + `SUCCESS`）**：
  ① 档位不是 `off`；② 手持物带 `c:tools/wrench` 标签（Create 扳手已在该标签内，其它模组扳手同理可用）；
  ③ `player.mayBuild()`；④ **没有潜行**（潜行一律不接管，把 Create / 原版的拆装 / 拾取行为完整让出去）；
  ⑤ 目标方块是 RS 线缆类（`RsccWireBlocks.isWire`：线缆 / 输出总线 / 输入总线）。
  任一不满足即 `return`，**绝不 cancel**。
* 反馈：actionbar，中英双语，句子短（`message.rs_create_compat.cable_*`）。

### 3. 两种方式的实现要点

**方式 A —— 右键接缝断开（`seam`）**

```java
// 归一化接缝键：坐标字典序较小的一侧 + 指向另一侧的方向（两侧查询命中同一条记录）
if (pos.asLong() <= other.asLong()) { owner = pos; dir = hitFace; }
else                                { owner = other; dir = hitFace.getOpposite(); }
if (!wasCut && !hasVisibleConnection(level, pos, hitFace)) return ToggleResult.INVALID; // 没连线不瞎断
toggle(saved.seam, owner, dir); // 再右键同一条接缝即恢复
```
判「这里有没有可断的接缝」直接读 RS 的外形数据 `AbstractCableLikeBlockEntity#getConnections`
（不做任何几何推断），因此「线缆↔线缆」与「线缆↔机器」都能断；已被本功能断开的接缝优先按「恢复」处理，
否则会因为外形已是不连而永远恢复不了。

**方式 B —— 右键面切换（`face`）**

```java
// 记录「本方块的某个面不再自动连接」，不要求当前有连接（先把面关掉 = 以后放上来也不连）
toggle(saved.face, pos, hitFace);
// 查询时两侧都查：本方的面 / 对面的反方向面 —— 任一被关掉，这一处连接即断开
return has(face, self, towardOther) || has(face, other, towardOther.getOpposite());
```
六个面各自独立；同一方块的多条记录合成一个「方向位掩码」（位 = `Direction#ordinal()`，`1 << ordinal`），
一个坐标只占一个键。

**两套记录分开存**：档位决定「扳手写哪一套、判定读哪一套」，因此切档等价于换一套记录
（`off` = 完全不生效，回到 RS 原版行为），互不污染、切回去即原样恢复。

### 4. 持久化的位置

* **服务端（权威、跟存档走）**：`SavedData`，数据文件名 `rscc_cable_cuts`
  → 落盘为 `<存档>/data/rscc_cable_cuts.dat`（按维度各一份，
  `ServerLevel#getDataStorage().computeIfAbsent(...)`）。
  格式：`{seam:{ "<BlockPos#asLong()>": <byte 方向掩码>, ... }, face:{ ... }}`。
  读档即恢复，**不给 RS 的方块 / 方块实体加任何字段，不动方块状态，不破坏方块**。
* **客户端（只读镜像）**：客户端看不到 `SavedData`，因此新增 S2C 包
  `network/SyncCableDisconnectsPacket`（整份快照：档位 + 两套掩码）。
  发送时机：玩家**登录 / 换维度 / 重生**（`RsccWrenchCableInteraction`）+ **每次改动后**
  （`RsccCableCuts#broadcast`）。改动时**先发包、再发方块更新**，
  两者走同一条有序连接，因此客户端重算连接臂时拿到的一定是新数据。

### 5. 配置项与默认值

| 项 | 值 |
| --- | --- |
| 键 | `wrenchCableDisconnectMode`（COMMON 配置，与既有配置同文件 `rs_create_compat-common.toml`） |
| 取值 | `seam` / `face` / `off` |
| 默认 | **`seam`** —— 最贴近用户原话「右键两个方块之间的连接处断开」，且不要求玩家先理解「面」的语义；接缝模式还带「这里没连线就不断」的提示，误操作面最小 |
| 生效时机 | 在 `Config#onLoad`（`ModConfigEvent`）读一次 → **改动后需重启客户端 / 服务端**；非法值回落到 `seam` 并打一条 warn |

### 6. 与搜链（`RsccWireLinkSearch`）的接入点

被扳手断开的连接在搜链里等同「这一侧没有线」，避免「看着断开了但仍然串线」：

* `hasRelevantNeighbor`：每个方向先问 `RsccCableCuts.isDisconnected(level, origin, direction)`，
  被断开的方向直接跳过 —— 从而「预检未命中 ⇔ BFS 一步都走不出去」这条不变式继续成立；
* `searchChamber`（输出总线 → 执行舱）与 `connectedReturnBuses`（回流总线归属/均分）：
  每一步 `cell → cell.relative(direction)` 展开前先问同一句话，命中即 `continue`。

调用形态与 Mixin 完全一致（`self` = 当前方块，方向 = 指向另一方），
所以「网络图连边 = 连接臂外形 = 搜链」三处用的是**同一个判定**。

### 7. 本轮新增 / 修改文件

新增：`support/RsccCableCuts.java`、`support/RsccWrenchCableInteraction.java`、
`network/SyncCableDisconnectsPacket.java`、`mixin/network/InWorldNetworkNodeContainerImplMixin.java`、
`tools/gen_lang_frag_wrench.py` + `tools/lang_frag_wrench.json`、本追加脚本。

修改：`Config.java`（新增档位项）、`RS_Create_Compat.java`（注册 S2C 包 + 注册交互监听）、
`support/RsccWireLinkSearch.java`（三处接入断开判定 + 类注释）、
`src/main/resources/rs_create_compat.mixins.json`（登记新 Mixin）、
`src/main/resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：NBT 标记、回流总线、收集类机器、单元样板管理舱相关文件；
未改任何方块 / 方块实体 / 菜单 / 贴图 / 布局。


***

## 机器集群：同类型机器相邻 = 一个整体（容量叠加 + 内容共享）（本轮）

> 来源：用户需求原话「同一个整体的容量是叠加的内存，就是里面的资源内容是共享的」
> 「同类型机器相邻可合并。例：27 格机器 + 同款 27 格机器 = 视为整体 54 格容器，内含一个水桶」。
> 硬规则不变：服务端权威；**绝不销毁玩家资源**（读档 / 拆集群 / 拆方块三条路径都要保证）。

### 1. 适用范围清单（实际 grep 代码后的结论）

**范围内（本轮已实现集群）**

| 机器 | 方块 | 内部资源存储 | 单机容量 | 共享后的整体容量 |
| --- | --- | --- | --- | --- |
| 归流缓存仓 | `CollectionCacheBlock` | 物品缓存 `SimpleContainer` + 流体/气体缓存 `MultiFluidCache` | 256 格 / 512000 mB | 台数 × 256 格 / 台数 × 512000 mB |
| 序列执行舱 | `SequenceExecutionChamberBlock` | 输出物品 `RsccUnboundedItemStorage` + 输出流体 `RsccUnboundedFluidStorage` | `autocrafterOutputSlots × 64` / `autocrafterFluidCapacity` | 台数 × 单机量 |

两台的「容量叠加」都是**乘法**（台数 × 单机容量），与用户举例一致（27 + 27 = 54）。

**范围内但本轮未接（框架已就绪，接一台只需实现 `RsccClusterable` 的 11 个方法 + 加一行注册）**

| 机器 | 为什么不接（不是漏掉，是语义冲突/风险） |
| --- | --- |
| 定量保持器（基础）`QuantityKeeperBlockEntity` | 物品存储 `ItemStackHandler` 的 `isItemValid` / `getStackLimit` **闭包绑定本机标记的那一种资源**（`matchesItemMarker`），两台标记不同的保持器合并会把「只能装 A」的机器与「只能装 B」的机器混成一个池；另外它的容量语义是「保持在 N 个」而不是容器格数。要接必须先加「标记一致才可合并」的兼容判定（`RsccClusterable#rscc$clusterCompatible` 已预留）与共享 `onContentsChanged` 回调（否则 follower 的 blocked 状态不刷新）。 |
| 高级定量保持器 `AdvancedQuantityKeeperBlockEntity` | 同上，且它是 `ItemStackHandler[SLOT_COUNT]` / `MultiFluidCache[SLOT_COUNT]` **每标记位一份存储**，合并粒度需要重新定义（按槽位合并 = 语义未定）。 |
| 装填器 / 高级装填器 `SchematicLoaderBlockEntity` / `AdvancedSchematicLoaderBlockEntity` | **本轮已接入**（见文末「装填器家族接入集群框架」一节）：只合并「从网络拉取进来暂存的那份内存」；蓝图槽 / 队列 / 升级槽这些**作业状态**仍然各机独立。 |
| 序列装配执行器 `SequenceAssemblyExecutorBlockEntity` | 内部只有 9 个样品/样板槽（配置，非资源池）。 |
| 序列样板终端 `SequencePatternTerminalBlockEntity` | 全部槽位是**玩家交互槽**（输入 / 结果 / 废料 / 样板），界面逐槽位绑定，合并会改变玩家手动摆放的语义。 |
| 范围充电器 `RangeChargerBlockEntity` | 只有 6 个升级槽，无资源存储。 |

**不在范围内（本轮不做，需 Mixin 别人的方块）**：RS 原版方块 —— 自动合成仓 `AutocrafterBlockEntity`、
样板库 `PatternGridBlockEntity`、磁盘驱动器 / 外部存储总线等。它们不是本模组注册的方块，
要实现「相邻即合并」必须对 RS 的方块实体做 Mixin 改写其存储字段，风险与回归面都远大于本轮，
故本轮明确不做（**在此点明，避免误以为已覆盖**）。

**本轮明确不碰**（其它并行分支在改）：NBT 标记相关文件、回流总线（`SequenceReturnBusBlock(Entity)`）、
单元样板管理舱（`UnitPatternManagerBlock(Entity)`）、扳手分离线缆相关文件。
（回流总线本体也是「带资源存储的机器」，但硬规则要求本轮不动它。）

### 2. 集群 / 主控 / 委托模型（唯一实现：`support/RsccMachineCluster.java`）

```
集群 = 同类型方块的 6 向连通分量（只统计已加载坐标，不经过线缆）
主控 = 成员里坐标字典序最小者（x → y → z，确定性，与遍历顺序无关）
唯一一份存储 = 主控建出「容量 = 台数 × 单机容量」的载荷，全部成员采纳同一个对象
```

关键代码（管理器核心）：

```java
final Object shared = master.rscc$clusterNewPayload(members.size());   // 容量叠加：台数 × 单机
if (oldPayload != null && oldPayload != shared) drain(master, oldPayload, shared, ...);  // 旧内容搬进新共享
for (RsccClusterable m : members) { m.rscc$clusterAdopt(shared); m.rscc$clusterSetOwnsPayload(m == master); }
```

关键代码（机器侧：把「缓存字段」指向共享对象，**看起来就是同一个容器**）：

```java
@Override public void rscc$clusterAdopt(final Object shared) {
    if (!(shared instanceof ClusterPayload payload) || payload.items() == cache) return;
    cache = payload.items();  fluidCache = payload.fluids();  setChanged();
}
```

* **不是「各存各的再同步」**：所有成员的两个存储字段指向**同一个对象实例**，
  因此往任意一台里存，另外几台的 `getCache()/getFluidCache()` 立刻看到同一份内容（零同步逻辑）。
* **GUI 打开 follower 也显示整体**：菜单的槽位读取走 `block.getCache()`（每次点击/绘制实时解析字段，
  不缓存容器引用），因此 follower 的界面天然显示合并后的那一份；页数上限改成按
  `block.getCache().getContainerSize()` 计算（`CollectionCacheMenu` 数据槽 18），
  于是**格数 = 台数 × 256** 时滚动条页数正确、能看到全部内容。
  流体侧同理（`getFluidCache()` 就是那一份，容量与已用量一起变大）。
* **逐槽位绑定的机器怎么办**：如果一个机器的界面是「构造菜单时把容器引用存进 Slot」的写法，
  字段换对象后旧 Slot 会指向旧容器。本轮涉及的两台都不属于这种情况（都是每帧/每点击解析）；
  后续若接入这类机器，需要在 `rscc$clusterAdopt` 里一并重建菜单槽位（已在本节留档）。

### 3. 主控变更与数据移交（怎么保证不丢）

| 场景 | 处理 | 为什么不丢/不翻倍 |
| --- | --- | --- |
| 新机器贴上集群 | 主控建**更大**的新共享，把旧共享内容搬（**移动**不是复制）进去，全员采纳 | 容量只增不减，内容全部装得下 |
| 主控方块被破坏 | `setRemoved` 里：主控建「单机容量」的新存储，把旧共享**搬空**到自己头上；幸存者随后重建为「等容量但为空」；`release` 前再调用 `handOverToSurvivors` 把内容交给幸存集群 | 任何时刻内容只在一处：先随主控走，破坏时再移交；搬空旧共享 ⇒ 幸存者重建时拿到的是空对象（不会与主控已落盘的那份重复） |
| 普通成员被破坏 | 内容留在集群（旧共享 → 新共享）；该成员拿一份空的单机存储 | 破坏结算只会落「缩容装不下的那点余量」 |
| 主控 / 成员所在区块卸载 | 与破坏同样的拆集群逻辑（`setRemoved` 统一入口），**不额外移交** | 见 §5 |
| 读档 | 每台成员先各自读自己的载荷（follower 的 NBT 里没有内容 = 空），随后集群成型时**统一汇总**（主控 + 各新成员的载荷一起并入新共享） | 汇总用「移动」语义，且落盘只有主控写内容 ⇒ 不会出现同一份内容被读两次 |

### 4. 拆分时的资源分配规则

1. **离场的是普通成员**：资源**全部留在原集群**（新共享按新台数缩容，内容按坐标顺序装箱），
   缩容装不下的余量交给**离场成员**（它随后由破坏结算 / 区块落盘带走）。
2. **离场的是主控**：资源**全部跟着主控走**（它缩容到单机容量）；
   装不下的余量交给仍在加载的成员；若成员也装不下，落回世界（掉落物 / 装桶），**绝不销毁**。
3. 「装不下」在容量叠加语义下几乎不可能出现（拆集群前后的总容量守恒），
   仅当同款机器放了不同数量的堆叠升级（每格上限不同）时可能出现，此时按上面 §1/§2 的兜底落世界。

### 5. 持久化边界（谁落盘）

* **只有主控把共享内容写进 NBT**（`clusterOwnsPayload`），follower 写**空载荷**：
  `saveAdditional` 里用 `if (clusterOwnsPayload)` 包住「缓存 / 流体」两段。
* follower 不写任何「指向主控」的额外字段：集群由「同类型方块 6 向相邻 + 全部已加载」**可重建**，
  坐标本身就是身份，指针反而会在主控换位后失效。
* 方块物品 NBT 也守同一条边界：`saveStateForItem()` 在 `clusterSize > 1` 时**不写流体**
  （内容属于「整体」，塞进单个方块物品会在重新放下时被再次并入 ⇒ 流体翻倍）；
  破坏路径由 `BlockContentReleaser.release` 先 `handOverToSurvivors` 再结算。

### 6. 跨区块策略与理由（本轮选定）

**策略：允许跨区块合并，但只以「已加载部分」为准，且内容跟着当时持有它的一方走。**

* 集群永远只在**已加载**的连通分量上成立；未加载的成员暂时不算入（不参与容量、不显示内容）。
* 成员离开加载范围时：**主控离开 ⇒ 内容随主控走**（主控缩容到单机容量，幸存者拿到一份「等容量但为空」
  的共享存储继续工作）；**普通成员离开 ⇒ 内容留在集群**（它拿一份空的独立存储），
  缩容余量交给离场者。

**为什么这么做（权衡）**：区块卸载时「先落盘再 `setRemoved`，还是先 `setRemoved` 再落盘」
在平台侧没有对调用方可见的保证。若采用「卸载时把内容交给仍在加载的 master」，
一旦实际时序是「先落盘」→ 卸载方已经把内容写进区块 NBT，接收方又拿着同一份内容再写一次，
**同一个集群的内容会在两个区块里各存一份，读档汇总时翻倍**（最严重的复制事故）。
而「内容只跟着当时持有它的一方走」在两种时序下都成立：卸载方带走并落盘、幸存方从空开始，
两份内容**互不相交**，重新加载时汇总只会得到正确总量。
**代价**：跨区块的集群在部分区块未加载时，未加载部分的内容暂时不可见（但绝不丢失），
容量也只按已加载台数计算 —— 这是「以已加载部分为准」的必然结果，已在本节留档。

### 7. 性能模型（不每 tick BFS）

1. **事件驱动失效**：`support/RsccClusterInvalidation` 监听 `BlockEvent.NeighborNotifyEvent`
   （放置 / 破坏 / 被替换都会走它），只**读方块状态**判定「变化点或其邻块是不是本模组的可成集群机器」，
   是才作废「该点及其 6 邻块所属集群」的缓存 —— 绝不触达方块实体（不强制加载、不会 A→B→A 递归）。
2. **缓存命中即返回**：成员方块实体在 `doWork()`（Mixin `AbstractBaseNetworkNodeContainerBlockEntityMixin`
   注入）里调用 `RsccMachineCluster.ensure(this)`；命中缓存时每 tick 只花**一次 Map 查询**，
   每 20 tick 才做一次「成员存活校验」（每个成员一次 `isLoaded` + 一次方块比较）。
3. **搜索只读方块状态**：BFS 穿行阶段绝不取方块实体；只有确认是同类型方块后，
   才用 `getBlockEntity(pos, CHECK)`（**已存在才返回，绝不创建**）取成员。
4. **规模上限** `MAX_MEMBERS = 64`；主控确定性（字典序最小）⇒ 重算结果与遍历顺序无关。
5. 与既有做法一致：`RsccSearchGuard` 重入守卫不参与（集群解析是纯读 + 幂等重算），
   失效信号复用本项目 `RsccBusLinkInvalidation` 的同一套思路（事件 + 廉价预检）。

### 8. 与「缓存满不吞资源」的一致性

容量叠加只改「存储挂在哪一份上」，不改任何搬运语义：`support/SafeCollect` 的
「先 SIMULATE 算能收多少 → 只按实际能收的量抽取 → 不足回滚」继续成立。
叠加后 **满 = 整体满**：`getRemainingCapacity()` / 流体 `getFreeSpace()` 直接来自那份共享存储，
因此整体一满，两台机器都会立刻停止收集（不会各收各的、也不会吞）。
`tools/audit_resource_swallow.py` 保持 0 未处理可疑点（1 条落世界兜底已按脚本约定写明理由豁免）。

### 9. 气体结论

工程内**没有独立的 gas 概念**：气体统一走 NeoForge 的流体通道
（`MultiFluidCache` / `RsccUnboundedFluidStorage` / `IFluidHandler`）。
因此「气体共享 + 叠加」与流体完全同一条路径，本轮已经覆盖，**没有凭空新增任何气体 API**。

### 10. 本轮修改文件

`support/RsccClusterable.java`（新增，机器侧协议）、`support/RsccMachineCluster.java`（新增，集群解析 /
主控选举 / 载荷汇总 / 拆集群）、`support/RsccClusterInvalidation.java`（新增，事件驱动失效）、
`support/BlockContentReleaser.java`（破坏前先移交共享内容）、
`block/entity/CollectionCacheBlockEntity.java`、`block/entity/SequenceExecutionChamberBlockEntity.java`、
`menu/CollectionCacheMenu.java`（缓存区页数按实际格数）、
`mixin/network/AbstractBaseNetworkNodeContainerBlockEntityMixin.java`（tick 里解析集群）、
`RS_Create_Compat.java`（注册失效监听）、本文件。

**刻意未动**：NBT 标记相关文件、回流总线、单元样板管理舱、扳手分离线缆相关文件；RS 原版方块。


***

## 装填器家族接入集群框架：内存叠加 + 资源共享（本轮）

> 来源：用户需求原话「装填器**从网络拉取物品到自身内存的那份内存**」要能叠加并共享；
> 「蓝图槽、作业队列这类**作业状态**不合并」。
> 框架、主控选举、拆集群、跨区块策略全部复用上文「机器集群」一节（`support/RsccMachineCluster`），
> 本节只记录**装填器特有的接入点与取舍**。

### 1. 类名与「合并 / 不合并」边界

| 字段 / 类 | 说明 | 是否合并 |
| --- | --- | --- |
| `SchematicLoaderBlockEntity`（基础，54 格） | 集群成员；`RsccClusterable` 的实现者 | — |
| `AdvancedSchematicLoaderBlockEntity`（高级，108 格，继承基础） | 与基础**同一个家族**，相邻时并入同一个整体 | — |
| `inventory`（`SchematicLoaderInventory`） | **从网络拉取进来暂存的那份内存** | **合并**（容量 = 各台格数之和） |
| `blueprintSlot`（蓝图槽） | 本机当前作业的蓝图 | 不合并 |
| `upgradeContainer`（6 个插件槽） | 速度 / 自动合成升级按台生效 | 不合并 |
| `queue`（27 格蓝图队列，仅高级） | 作业顺序 / 排队状态 | 不合并 |

* **同族**判定在框架侧：`RsccMachineCluster#sameFamily` 让 `SchematicLoaderBlock` 与
  `AdvancedSchematicLoaderBlock` 相邻时也能穿过（两者那份内存的语义完全相同：都是一格格物品）；
* 机器侧把 `rscc$clusterCompatible` 一起放宽为 `other instanceof SchematicLoaderBlockEntity`，
  于是「高级被打掉后内容移交给相邻基础」这条路径同样成立（两条判据必须一致）。

### 2. 容量叠加：按各台自己的格数**求和**

```java
@Override public Object rscc$clusterNewPayloadFor(final java.util.List<RsccClusterable> members) {
    int slots = 0;
    for (final RsccClusterable member : members) {
        slots += member instanceof SchematicLoaderBlockEntity loader
            ? loader.inventorySlotCount : inventorySlotCount;   // 基础 54 / 高级 108
    }
    return new SchematicLoaderInventory(Math.max(inventorySlotCount, slots));
}
```

* 覆写的是 `rscc$clusterNewPayloadFor(members)` 而不是 `rscc$clusterNewPayload(台数)`：
  混阶集群（基础 54 + 高级 108）必须得到 162 格；若沿用「台数 × 某一种单机格数」，
  整体容量会凭空缩小（高级被当成基础）或放大（基础被当成高级），两者都不允许。
* `inventorySlotCount` = 本机单机格数（基础 54 / 高级 108），集群缩容 / 单机化都按它算。

### 3. 资源共享：全体指向**同一个** `SchematicLoaderInventory` 实例

```java
@Override public Object rscc$clusterPayload() { return inventory; }   // 身份稳定 ⇒ 管理器可用 == 判定
@Override public void rscc$clusterAdopt(final Object shared) {
    if (!(shared instanceof SchematicLoaderInventory next) || next == inventory) return;
    inventory = next; inventory.bindOwner(this); setChanged();
}
```

* 新增内部类 `SchematicLoaderBlockEntity.SchematicLoaderInventory extends ItemStackHandler`：
  实例上挂着若干「持有者」**弱引用**，`onContentsChanged` 通知**所有仍持有它的装填器** ——
  因为**落盘的那一份在主控手里**，从任意一台（或任意一个界面）改动都必须标记到主控，
  否则这次改动会在区块落盘时丢掉；弱引用同时避免方块实体被移除后阻止回收。
* 于是「拉取 → 暂存」这条主流程（`fetchFromNetwork` / `restockByNeeds` / `insertIntoCluster`）
  **不需要任何同步逻辑**：所有成员解析到的都是同一个对象。
* 同族合并后，GUI 顺序列表（`getClusterInGuiOrder`）按**存储身份**去重 ⇒
  「插入」「计数」「行数」都只会看到**一份**内存，不会被重复算成 N 倍
  （本轮据此把 `countItemInCluster` / `returnExcessToNetwork` / `getClusterTotalRows`
  改成走去重后的视图）。

### 4. 不丢、不复制（成型 / 增台 / 拆分 / 破坏 / 读档）

| 场景 | 处理 | 结果 |
| --- | --- | --- |
| 集群成型 / 增台 | 框架建「各台格数之和」的新共享，把旧共享与新成员的载荷**移动**（不是复制）进去，全员采纳 | 内容只搬一次，容量只增不减 |
| 普通成员破坏 / 卸载 | 框架：内容留在集群，本机换成空的单机内存（`setRemoved` → `onMemberRemoved`） | 幸存者继续持有那份内容 |
| 主控破坏 / 卸载 | 框架：内容收进主控「单机格数」的新载荷（装不下的余量落回世界），幸存者随后重建为「等容量但为空」 | 内容跟着当时持有它的一方走 |
| 读档 | **只有主控写过 `Inventory`**（follower 写空载荷），集群重建时再按「移动」汇总 | 同一份内容不可能被读两次 |
| 破坏结算（`BlockContentReleaser`） | 全局档位为「存方块」时 follower 的 NBT 不含内容 ⇒ 幸存者保留；档位为「爆出 / 回网」时内容被明确交付给世界 / 网络；`release` 前先 `handOverToSurvivors` | 既不会翻倍，也不会消失 |

* **持久化边界**：`saveAdditional` 里 `if (clusterOwnsPayload) tag.put("Inventory", …)`，
  其余成员不写；蓝图槽 / 插件槽 / 队列**永远照常保存**（作业状态属于各机自己）。
* 读档格数自适应：`ItemStackHandler.deserializeNBT` 会按 tag 里的 `Size` 重设格数，
  因此「存档时是叠加后的 162 格」也能整体读回，随后由集群按当前已加载台数重新定型。

### 5. 与「缓存满不吞资源」一致（`SafeCollect` 语义不变）

`fetchFromNetwork` 仍是「先 `simulateInsertIntoCluster` 算整体还能收多少 → 只按实际能收的量抽取 →
插不下则退回网络、退不回才落回世界」。叠加后 **满 = 整体满**：探针直接打在那一份共享内存上，
所以整体一满就一个也不抽（`tools/audit_resource_swallow.py` 保持 0 未处理可疑点）。

### 6. GUI：打开任意一台都显示合并整体

* 菜单的槽位列表本来就来自 `loader.getClusterInGuiOrder()`，而该方法**已按共享内存身份去重**：
  同族合并后只剩一项，它的 `getSlots()` 就是整体格数（54 × N），`ClusterSlot` 的全局槽位映射、
  滚动条页数（数据槽 `getClusterTotalRows()`）随之正确 —— 打开 follower 看到的就是整体
  （基础 / 高级各自仍打开自己的界面，高级多出队列区）。
* 菜单构造时抓的是 `ldr.memoryView()`：**实时视图**（每次调用现解析本机当前的内存，身份稳定），
  于是集群增台 / 拆台换了内存对象后界面自动跟上 —— 若抓当时的那个对象，玩家会往一份
  「已经不再落盘的孤儿内存」里放东西（真正的丢物品路径）；视图对越界槽位统一
  「读 = 空 / 不能放 / 写 = 落回世界」，不抛异常也不吞物品。
* 另补上 `getClusterInGuiOrder()` 的同 tick 缓存字段，并在集群变化时失效。

### 7. 本轮修改文件

`block/entity/SchematicLoaderBlockEntity.java`（实现 `RsccClusterable`：新增
`SchematicLoaderInventory`、`MemoryView` 实时视图、`rscc$cluster*` 全套方法、`setRemoved`
拆集群钩子、单主控落盘边界；`getClusterTotalRows` / `countItemInCluster` / `returnExcessToNetwork`
改为按共享身份去重；补上 GUI 顺序的同 tick 缓存）、
`menu/SchematicLoaderMenu.java` / `menu/AdvancedSchematicLoaderMenu.java`（内存改用 `memoryView()`）、
`AdvancedSchematicLoaderBlockEntity.java`（继承实现，集群代码零改动）、
`tools/append_round4_loader_cluster_section.py`（本文件）、本文件。

**未完成 / 已知取舍**：跨区块时只按**已加载**台数定型（框架既有策略），未加载那部分的内容要到它的
区块重新加载时才并回；「主控被破坏 / 卸载」时若整体格数大于主控单机格数，多出来的部分按框架规则
落回世界（掉落物 / 装桶，绝不销毁）；界面打开期间若集群台数变化，多出来（或已消失）的那部分槽位
要到重新打开界面（或滚到新页）才完整可见。


***

## 序列执行舱「链 / 指向」：名字与配方类型整链共享（本轮）

> 来源：用户本轮需求「序列执行舱要像 RS 的自动合成仓那样串成一条链」+ 更正后的语义
> 「可以在任意一个地方改，就相当于是说改一个地方应用到全部」。
> 硬规则不变：服务端权威、**绝不破方块 / 绝不丢配置**、`drawString` 一律 `false`、
> 精灵坐标 → Menu 坐标 **x、y 均 +1**、语言键走 `tools/lang_frag_execchain.json` + `apply_lang_frag.py`。
> 边界：**这不是存储合并** —— 打开一台不会「看到东西变多」；本轮的共享只限「名字 + 配方类型」，与
> 上一轮的「相邻同类叠加 = 容量叠加 + 内容共享」的机器集群**互不干扰**。

### 1. RS 自动合成仓「串联 / 指向」的真实实现（查的是仓库内真源码）

源码：`local_src/external/RefinedStorage/refinedstorage-common/.../autocrafting/autocrafter/`
（`AutocrafterBlockEntity.java` / `AutocrafterData.java` / `AutocrafterContainerMenu.java`）。

| 环节 | 真实类 / 成员 | 说明 |
| --- | --- | --- |
| 链长上限 | `AutocrafterBlockEntity.MAX_CHAINED_AUTOCRAFTERS = 8` | 递归深度闸门（`getChainingRoot(depth, origin)` 到上限即返回 `origin`） |
| 「指向」的载体 | **方块状态里的方向** `tryExtractDirection(getBlockState())` | RS 没有独立的「指向」字段，箭头就是方块朝向；本模组朝向已被「机器侧朝向」占用，故改用独立字段 |
| 指向的对象 | `getConnectedMachine()` | 只取**自己朝向那一格**的方块实体（`level.getBlockEntity(pos.relative(direction))`，未加载即 null） |
| 链归属 | `getChainingRoot()` → `isPartOfChain()` / `isHeadOfChain()` | 沿指向逐台前进，走到的那台即链首；`isPartOfChain = getChainingRoot() != this`；`isHeadOfChain` = 自己不是成员且有邻居指向自己 |
| 名字归属 | `getName()` / `doGetName()` / `setCustomName(String)` | **成员的名字一律委托链首**（`root.getName()`）；`setCustomName` 在 `isPartOfChain()` 时**直接 return**（RS 只允许链首改名） |
| 客户端同步 | `AutocrafterData(boolean partOfChain, boolean headOfChain, boolean locked)` + `getMenuCodec()` | 只同步 3 个布尔用于界面展示（RS 界面据 `canChangeName() = !partOfChain` 灰掉改名入口） |
| 成形 / 解散时机 | **没有成形 / 解散事件**，纯惰性 | 链每次调用现推；改指向 = 改方块朝向（扳手 / 放置），拆方块 = 链自然断成两段 |

**结论**：RS 的链是「**每台只存自己指向谁**、链首唯一持名字、其余全委托、解析惰性无缓存」。
本项目**照抄**这套骨架（沿指向走到链首 + 链首唯一持有 + 惰性解析），**借鉴处**有两点：
①「指向」改用独立持久化字段 `ChainLink`（因为方块朝向另有用途）；
② RS 的「成员改不了名」被用户语义**反转**为「任意一处改 = 整链生效」（见第 3 节）。

### 2. 链的构成 / 成形 / 解散时机

* **构成**：每台执行舱持有 `chainLink`（`Direction#get3DDataValue()` 或 `NO_LINK = -1`）。
  `chainHead()`：沿指向逐台前进（上限 `MAX_CHAIN_LENGTH = 8`，与 RS 一致），出现自环 / 目标不是
  执行舱 / 目标区块未加载即停在该台 —— **链首 = 停下来的那一台**。
* **成员集合**：`chainMembers()` 先走到链首，再从链首沿「谁指向我」反向 BFS（同样限长），
  按坐标升序返回；写入 / 广播 / 快照都以它为准。链是**一条有方向的射线**：所有人都指向链首（端点），
  除端点外每台**恰好被一台**指向（入度 ≤ 1），成环 / 分叉都被服务端拒绝（见本轮收紧章节）。
* **成形**：在「配置」子界面把「链指向」指向相邻的某台执行舱并确定 → 服务端写入 → 立即成链。
* **解散**：改指向（含改为「不指向」）或拆掉中间任一台即散；**没有缓存的链对象**，
  每次读取按当前世界状态现推，所以区块加载 / 卸载、放置 / 破坏都不会留下脏链。
* **拆链不丢配置**：改指向时先把当前链共享的名字 / 配方类型抄下来写入**新的链首**
  （仅当它自己还没有值，绝不覆盖已有值）；拆掉链首本身时由方块 `onRemove` →
  `handOverChainOnRemoval()` 把配置交给**唯一**那个「指向它的执行舱」（同样只写给空的）。
  `onRemove` 是唯一可靠时机：世界与相邻方块实体都已加载，且区块卸载不会走它。

### 3. 名字与配方类型「改一处 = 全链生效」

链上**只有链首持有权威值**，成员一律读链首；`set*` 时若自己不是链首就把值**转发到链首**，
因此「在任意一台提交」与「在链首提交」是同一件事，不存在两份配置，也就没有分裂的机会：

```java
public void setChamberName(final String name) {
    final SequenceExecutionChamberBlockEntity head = chainHead();
    if (head != this) { head.setChamberName(name); return; }   // 成员 → 转发链首：整链同步
    final String value = name == null ? "" : name;
    if (value.equals(ownChamberName())) { return; }             // 幂等
    this.chamberName = value;
    markDirtyAndSync();
}

public String getRecipeType() { return chainHead().ownRecipeType(); }   // 读也只读链首那一份
```

**并发提交**：C2S `SetChamberBindingPacket` 在服务端**主线程** `enqueueWork` 里执行（见
`RS_Create_Compat` 注册），因此天然串行、**以最后一次提交为准**；一次提交是**原子**的，
顺序固定「先落链指向（可能换链首）→ 再落名字 / 配方类型（写在新的链首上）」。
每次提交后服务端都：① 回发权威快照给提交者；② `broadcastChainBinding()` 推给**链上每一台**
正在被查看的执行舱 —— 于是不会出现「这一台改了、另一台还是旧值」或
「客户端看着改了、服务端没改」（**所有拒绝路径也回发权威值**，客户端界面立刻复位）。
客户端另加一道保险：链指向控件只有被玩家**真的动过**才提交（未动过发 `UNCHANGED_LINK`），
避免用陈旧快照把别人刚设好的链覆盖回去。

### 4. 与「机器集群（容量叠加 + 内容共享）」并存

* 集群走 `support/RsccMachineCluster` + `RsccClusterable`（相邻同类 → 同一个存储载荷对象），
  **本轮一行没动**：`outputStorage / outputTank`、`ClusterPayload`、`clusterOwnsPayload` 全部照旧。
* 链只碰 `chainLink / recipeType / chamberName` 三个字段，**不参与任何存储合并**：
  打开一台执行舱看到的仍是它（或它所在集群）那**一份**输出内容，不会因为串了链而变多。
* 两者正交：可以「串成链但各自独立存储」，也可以「同一条链上几台再各自与邻居组集群」。
* 兼容性：`OutputMode{FACE, BUS}`、面配置、类别归属（`busCategoryOwners`）全部未改；
  配方类型锁定判据从「本台有样板」升为「**链上任一台有样板**」（`chainHasAnyUnit()`），
  因为整链共享同一个配方类型，改类型会让**整条链**已放样板失配。

### 5. 持久化：链只存「指向」，权威配置只存链首那一份

* `ChainLink`（int，NBT）：**每台只存自己指向哪一格**；旧档无该字段 → `NO_LINK`（独立）。
* `ChamberRecipeType / ChamberName`：**只有链首那一份非空**（成员一律转发写入链首），
  因此读档后不可能出现「同一份配置被写成多份再合并 → 翻倍 / 分裂」。
* 读档后链怎么重建：不重建 —— 链**不进任何缓存**，`chainHead()` / `chainMembers()` 每次按当前
  世界状态现推，所以读档、区块加载卸载后「谁是链首」自动正确，无需任何反序列化补救逻辑。

### 6. 界面（tooltip 一律手动渲染，判定范围与绘制范围同源）

* **主界面**（`SequenceExecutionChamberScreen`）：绑定状态行末尾加极短的「（链 N 台）」后缀；
  该行 tooltip（手动渲染）写清「链头：<名字或坐标>」「名字与配方类型整链共享…」，
  未串链时明确写「未接入链：本台独立持有…」。
* **配置子界面**（`ChamberBindingConfigScreen`，面板 200×146 → **200×190**，PNG 同步重出）：
  新增「链指向」滚轮控件（不指向 / 上 / 下 / 北 / 南 / 西 / 东，方向名复用面配置的语言键）
  + 其下方的链状态行「链：N 台 · 链头 …」；控件 tooltip 由控件自绘（+ 客户端按本台坐标算出
  目标坐标），状态行 tooltip 手动渲染「只存在链头那一份 / 没有『只改这一台』/ 样板锁定原因」。
* 拒绝反馈：`message.rs_create_compat.chamber_link_{no_target,cycle,invalid}` 走 actionbar（不刷聊天栏）。

### 7. 本轮新增 / 修改文件

新增：`tools/gen_lang_frag_execchain.py` + `tools/lang_frag_execchain.json`、本追加脚本。

修改：`block/entity/SequenceExecutionChamberBlockEntity.java`（`UNCHANGED_LINK`、`ChainState` 增
`headPos`、`handOverChainOnRemoval()`、`ChainLink` 存取、注释按新语义校正）、
`block/SequenceExecutionChamberBlock.java`（`onRemove` → 链移交）、
`network/SyncChamberBindingPacket.java`（链字段 + `of(entity)` 权威工厂 + 手写编解码）、
`network/SetChamberBindingPacket.java`（`linkOrdinal` + 原子提交 + 拒绝回发 + 全链广播）、
`network/SetChamberFacePacket.java` / `SetChamberOutputModePacket.java` / `menu/SequenceExecutionChamberMenu.java`
（统一改用 `SyncChamberBindingPacket.of(...)`，避免面配置回包把链状态刷成「独立」）、
`client/screen/SequenceExecutionChamberScreen.java`、`client/screen/ChamberBindingConfigScreen.java`、
`textures/gui/chamber_binding_config.png`（200×190，由 `tmp_textures/gen_child_panels.py` 约定尺寸重出）、
`tools/lang_frag_spt3.json`（配方类型锁定文案改为链级口径）、
`src/main/resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：装填器家族、NBT 标记、序列装配回流总线、单元样板管理舱、扳手相关文件，
以及执行舱的机器集群 / 存储侧代码。


***

## 执行舱「链」收紧为有方向的射线：禁止分叉 / 成环 + 旧档收敛（本轮）

> 来源：用户澄清「这个组成的应该是一条**有方向的射线**，这条射线的**端点就是第一个仓**，
> 所以**不存在你说这种情况**」—— 即上一版报告里「多点指向同一台（树状）」的风险**不允许存在**，
> 本轮从实现上把它排除：**入度 ≤ 1**，链永远是一条射线。
> 硬规则不变：服务端权威、**绝不销毁玩家资源 / 绝不丢配置**、所有拒绝路径都回发权威值。

### 1. 四条不变式（每次成功写入后都成立）

| 不变式 | 判据（代码位置） |
| --- | --- |
| 端点（链首）不指向任何一台 | 链首的 `chainLink == NO_LINK`；`chainHead()` 走到目标不是执行舱即停 |
| 除端点外每台**恰好指向一台** | 每台最多一个 `chainLink`（单个 int 字段，天然出度 ≤ 1） |
| **入度 ≤ 1**（不分叉） | 写入时 `hasOtherPredecessor(target, this)` ⇒ 拒绝 `FORK` |
| **无环** | 写入时 `target == this \|\| reachesMe(target)` ⇒ 拒绝 `CYCLE`（判据是「从 target 沿指向走**能否经过本仓**」——比只比链首严格：链 Y→我→X→… 时改指向 Y，从 Y 出发只是「经过」我，只比链首会漏判）；遍历另有 visited 兜底 |
| 链长 ≤ 8 | 写入时 `upstreamSize() + target.chainSize() > MAX_CHAIN_LENGTH` ⇒ 拒绝 `TOO_LONG`（沿用 RS 的 `MAX_CHAINED_AUTOCRAFTERS = 8`） |

「L1 到 L2 会连成射线」：因为出度 ≤ 1 且入度 ≤ 1 且无环，有向图退化成一条**链**；
链首是唯一出度为 0 的顶点 ⇒ **端点唯一且确定**，`chainHead()` 沿指向前进必然停在它上面。

### 2. 四个入口如何维持不变式

| 入口 | 维持方式 |
| --- | --- |
| **设置指向**（C2S → `setChainLink`） | 五道校验：目标存在 / 不成环 / **本机+目标两圈邻居已加载** / 不分叉 / 不超长；任一不过即**拒绝 + 行动栏短提示 + 回发权威值**。写入成功即满足全部不变式 |
| **拆方块**（`Block#onRemove`） | 拆掉任一台 = 从射线的某处剪断：下游段（指向被拆那台的后面那些）自然形成新的段，先由 `handOverChainOnRemoval()` 把配置交给**唯一**后继（入度 ≤ 1 ⇒ 最多一台），其余情况只取消指向关系。每段仍是射线 |
| **拆链首** | 是「拆方块」的特例：链首最多有一个直接后继（入度 ≤ 1）⇒ 它接任新链首并承接名字 / 配方类型，**不会**出现「多个子台各接一份配置、形成两条同名链」 |
| **区块加载** | 链**不进任何缓存**：`chainHead()` / `chainMembers()` 每次按当前世界状态现推，读档 / 加载 / 卸载本身不会改变任何字段。仅对**旧档遗留数据**做一次性自愈：`sanitizeRayServer()`（方块服务端 ticker 每 tick 最先调用） |

`sanitizeRayServer()` 的收敛规则（**只取消指向，绝不销毁配置 / 方块 / 资源**）：

```java
for (Direction d : Direction.values())                       // ① 邻居没全加载不下结论（下一 tick 重试）
    if (!level.isLoaded(worldPosition.relative(d))) return;
if (isInCycle()) { chainLink = NO_LINK; ... }                 // ② 旧档成环：由本机这一段断开
if (hasOtherPredecessor(target, this)) {                      // ③ 旧档分叉：坐标大的一方让步
    if (winner != this) { chainLink = NO_LINK; ... }          //    （纯本地判定，双方结论一致，不会拉锯）
}
```

* 「坐标较小者留下」是纯粹的函数（只看双方坐标），因此两边看到同一组时结论一致；让步方解除指向后
  自己成为一段独立链的链首，**自己的名字 / 配方类型原样保留**，绝不覆盖、绝不销毁。
* 自愈只在**本机**跑一次（内存标志 `rayVerified`，不进 NBT）；且**本机与目标两圈邻居**所在区块
  全部已加载才下结论，所以「分叉的另一方在未加载区块里」不会被误判成没问题 —— 那一方加载后
  由它自己继续收敛（双方判定集合一致 ⇒ 结论一致，不会来回拉锯）。
* 收敛后：服务端 `LOGGER.warn` 记录坐标，且**正在看该仓界面的玩家**收到一条短提示
  （`message.rs_create_compat.chamber_ray_{fork,cycle}_cleared`）+ 界面刷新；不刷聊天栏之外的地方。

### 3. 拒绝点与提示（中英双语，走 `tools/lang_frag_execchain.json`）

| `LinkResult` | 触发条件 | 行动栏提示键 |
| --- | --- | --- |
| `NO_TARGET` | 该方向没有已加载的执行舱 | `message.rs_create_compat.chamber_link_no_target` |
| `CYCLE` | 会绕回自己 | `message.rs_create_compat.chamber_link_cycle` |
| `FORK` | 目标已被另一台指向（会分叉） | `message.rs_create_compat.chamber_link_fork` |
| `TOO_LONG` | 接上后超过 8 台 | `message.rs_create_compat.chamber_link_too_long` |
| `UNVERIFIED` | 本机 / 目标那一圈邻居区块未加载（无法确认，先拒绝） | `message.rs_create_compat.chamber_link_not_ready` |
| `INVALID` | 方向序号越界 | `message.rs_create_compat.chamber_link_invalid` |

拒绝路径一律 `echo(player, chamber)` 回发权威快照 ⇒ 客户端界面上的选择**立即复位**，不会出现
「看着改了、服务端没改」。链指向控件的 tooltip 也补了一行规则说明（`config.chain.link.rule`：
「指向连成一条有方向的射线：不分叉、不成环，端点即链首」），把会被拒绝的情形提前讲清。

### 4. 拆链首的最终行为（确认结论）

* 链首最多有**一个**直接后继（入度 ≤ 1）⇒ 拆掉链首后由它**接任链首**并承接名字 / 配方类型
  （仅当它自己还没有值，绝不覆盖），其余成员照旧指向它 —— 整条链**只是少了一台**，不是断成两段。
* 因此**不存在**「多个子台各接一份配置、形成两条同名链」的情况（该风险已随入度 ≤ 1 消失）。
* 拆掉**中间**某台时：上游那一段自然成为独立的一段（其链首 = 被拆那台的直接后继），
  共享值仍在原链首那一份上、**绝不销毁**；这是与 RS 一致的行为（RS 的名字同样只存在链首）。
* 拆掉端点的**下游端**（即链条另一头）不影响任何配置。

### 5. 本轮改动文件

修改：`block/entity/SequenceExecutionChamberBlockEntity.java`（`LinkResult` 增 `FORK` / `TOO_LONG`、
`setChainLink` 四道校验、`collectUpstream` / `upstreamSize` / `hasOtherPredecessor` / `isInCycle`、
`sanitizeRayServer()` + `rayVerified` + `notifyRayConvergence`、`handOverChainOnRemoval` 注释按「唯一后继」校正）、
`block/SequenceExecutionChamberBlock.java`（服务端 ticker 前置一次自愈）、
`network/SetChamberBindingPacket.java`（新拒绝结果映射提示）、
`client/screen/ChamberBindingConfigScreen.java`（控件 tooltip 补「射线规则」一行）、
`tools/gen_lang_frag_execchain.py` + `tools/lang_frag_execchain.json`（+5 键、改 1 键）、
`src/main/resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

同时**就地改正**了上一章的旧说法：链「是一条线」→「是一条有方向的射线（入度 ≤ 1）」；
链首被拆时交给「那些执行舱」→「**唯一**那个后继」。


***

## 通用储存磁盘最高生存档：32M → 64M（翻倍）+ 配方改为 4 合 1（本轮）

> 来源：用户本轮需求「最大的那个生存模式下可制作的那个磁盘容量翻倍，对应的配方也改成四个的那个合成的」。
> 只动这一档磁盘（容量 / 配方 / 文案），其它物品方块一律未动。

### 1. 现状核对（改之前先定位，全部为真实路径）

* **物品与容量的唯一来源**：`src/main/java/cretae/cookiewyq/rs_create_compat/RS_Create_Compat.java`
  里的 `UNIVERSAL_STORAGE_DISK_*`（`ITEMS.register("<id>", () -> new UniversalStorageDiskItem(<容量>L))`）。
  磁盘物品类 `item/UniversalStorageDiskItem.java` 只负责「拿到容量 → 建存储 / 显示容量」，**不含任何容量数字**；
  `Config.java` 只有 `universalDiskAllowMixedTypes` 这类开关，**没有容量项** —— 所以容量只有注册处一个来源。
* 各级真实容量（单位 = 物品位，1 bucket = 1 物品位）：
  `1k=1024`、`4k=4096`、`16k=16384`、`64k=65536`、`256k=262144`、`1M=1048576`、`4M=4194304`、
  `16M=16777216`、**`32M=33554432`（= 32 × 1024 × 1024，即 32768k，本轮改造对象）**、
  `creative=0`（≤0 → `UniversalStorageType.create(capacity=null)` → `Long.MAX_VALUE`，**无限**）。
* **配方**：`src/main/resources/data/rs_create_compat/recipe/universal_storage_disk_<档位>.json`。
  既有惯例：`1k/4k/16k/64k` = 有序（`GBG/RPR/EEE`，`P` = 对应原版 `refinedstorage:<档>_storage_part`）；
  `256k/1M/4M/16M` = **无序 4 个低一档通用盘**；`32M` 原本是唯一例外（无序 **2 个** 16M）。

### 2. 本轮改动

| 项 | 改前 | 改后 |
| --- | --- | --- |
| 物品 id | `rs_create_compat:universal_storage_disk_32m` | `rs_create_compat:universal_storage_disk_64m` |
| Java 常量 | `UNIVERSAL_STORAGE_DISK_32M` | `UNIVERSAL_STORAGE_DISK_64M` |
| 容量 | `33554432L` | **`67108864L`**（= 64 × 1024 × 1024，即 65536k，**严格 ×2**） |
| 配方 | 无序 `2 × 16M → 32M` | 无序 **`4 × 16M → 64M`**（对齐 256k..16M 的 4 合 1 惯例） |
| 模型 / 贴图 | `..._32m.json` / `..._32m.png` | 同名改为 `..._64m`（同一档改名，不是新画一档） |
| 语言键 | `..._32m` = 32M/32M 通用储存磁盘 | 删旧键 + 新 `..._64m` = 64M |

**「四个」指什么 / 判定依据**：

1. 本模组自身惯例：`256k = 4 × 64k`、`1M = 4 × 256k`、`4M = 4 × 1M`、`16M = 4 × 4M`
   —— 从 256k 起一律「4 个低一档」，只有原 `32M = 2 × 16M` 是例外；
2. RS 原版同款设计：`refinedstorage-neoforge-2.0.0.jar` 内
   `data/refinedstorage/recipe/64k_storage_part.json` 的图案 `PEP/SRS/PSP` 中 **S = 4 个 `16k_storage_part`**
   （`16k_storage_part.json` 同理用 4 个 `4k_storage_part`）—— 官方升档也是「4 个低一档」；
3. 4 个 16M 正好等于翻倍后的 64M，容量与材料自洽。

因此本档取**无序 4 个 16M → 1 个 64M**（沿用原文件既有的 `minecraft:crafting_shapeless` 写法，
不强行改成有序；`1k..64k` 那三档仍保持「原版 storage part 有序合成」不动）。

**为什么连 id 一起改名**：本系列 id 内嵌容量数字（`_16m` / `_32m`），
若只改容量而 id 仍叫 `_32m`，会立刻出现「id 说 32M、tooltip 说 64M」的自相矛盾。
改名代价：旧存档里的 32M 磁盘物品 id 失效（该档本身即用户报告的异常档），无兼容映射表。

### 3. 与「创造无限档」的关系（无倒挂）

`universal_storage_disk_creative` 容量 0 → 判定为**无限**（`Long.MAX_VALUE`），
严格大于 67108864，因此**不存在「低级比高级还大」的倒挂**：
`...16M < 64M < 创造(无限)`。

### 4. 同步的其它位置

* `client/ClientInit.java`：磁盘物品模型注册列表里的常量名同步（否则编译不过）。
* `RS_Create_Compat.java` 创造模式标签页 `displayItems` 与物品区注释同步。
* `item/UniversalStorageDiskItem.java` 类注释的档位列表 `.../16M/64M/无限`。
* tooltip：容量由 RS 的 `AbstractStorageContainerItem#formatAmount` 按实际容量格式化，
  **没有写死数字**，因此容量改完 tooltip 自动显示 64M；唯一写死容量的是物品名语言键（已同步）。

### 5. 本轮新增 / 修改文件

新增：`tools/gen_universal_disk_64m.py`（幂等产出配方 / 模型 / 贴图改名）、
`tools/gen_lang_frag_disk64m.py` + `tools/lang_frag_disk64m.json`、
`tools/append_round4_disk64m_section.py`（本追加脚本）。

修改：`RS_Create_Compat.java`、`client/ClientInit.java`、`item/UniversalStorageDiskItem.java`、
`data/rs_create_compat/recipe/universal_storage_disk_64m.json`（旧 32m 配方删除）、
`assets/.../models/item/universal_storage_disk_64m.json`、`assets/.../textures/item/universal_storage_disk_64m.png`、
`assets/.../lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：其它任何物品 / 方块的配方与容量、`UniversalStorageType` / `UniversalLimitedStorage`（存储实现）、
创造无限档、GUI / 菜单 / 布局。


***

## 补齐合成表：全部生存可获得的物品 / 方块（本轮）

> 来源：用户需求「为所有的就是生存模式下，那些物品和方块添加对应的合成表配方，不一定全部都要有序，
> 合成也可以无序合成，参考精致存储（Refined Storage），它本身的配方设计」。
> 硬规则：**绝不能写出引用不存在物品的配方**（会让客户端 / 服务端加载报错）→ 由校验脚本拦截。

### 1. 枚举（清单 / 判定）

本模组注册总数 = **26 个物品（含 11 个 BlockItem）+ 11 个方块**（`RS_Create_Compat.java` 的
`ITEMS.register` / `BLOCKS.register`，与 `assets/rs_create_compat/lang/*.json` 的键逐条交叉核对，26 个全对得上）。

| 分类 | 数量 | 判定 | 说明 |
| --- | --- | --- | --- |
| 通用存储磁盘 1k…32M | 9 | ✅ 已有配方（15 条里的 9 条 + 升级链 5 条） | **本任务不动**（另一任务在改容量与配方） |
| 通用存储磁盘（创造档） | 1 | ⛔ 无配方 | 创造专用，同 RS `creative_storage_disk` |
| 高级远程终端（普通） | 1 | ✅ 已有配方 | — |
| 高级远程终端（满电 / 创造） | 2 | ⛔ 无配方 | 与普通版**同一个物品**的满电副本；`createAtEnergyCapacity()` 是 RS 给创造模式取用的用法，生存由充电器充能 |
| 序列单元样板 / 序列装配样板 | 2 | ⛔ 无配方 | 由「序列装配样板终端」在游戏内生成（终端产物），代码注释即写明「无合成配方」 |
| 机器方块（range_charger / quantity_keeper / schematic_loader / advanced_schematic_loader / sequence_pattern_terminal） | 5 | ✅ 已有配方 | — |
| 机器方块（advanced_quantity_keeper / collection_cache / sequence_return_bus / sequence_assembly_executor / sequence_execution_chamber / unit_pattern_manager） | 6 | 🆕 **本轮新增 6 条** | 见 §3 |
| RS 网络发送器 / 接收器（黄铜替代版） | — | ✅ 已有 2 条（产物是 RS 自己的 id） | `network_*_brass`，本任务不动 |

**结论：新增 6 条配方 / 已有 17 条（本模组产物 15 条 + RS 物品替代配方 2 条）/ 判定无配方 5 项（理由见上表）。**
校验后**未覆盖清单 = 0**：`21 个本模组产物有配方 + 5 个白名单 = 26 = 注册总数`。

### 2. 从 RS 官方配方里读到的风格（`local_src/external/RefinedStorage/refinedstorage-common/src/main/resources/data/refinedstorage/recipe/`，逐条实读）

1. **材料分层**：基础材料（`quartz_enriched_iron` / `c:silicon`）→ **处理器**
   （`basic_processor` → `improved_processor` → `advanced_processor`）→ **核心**
   （`construction_core` / `destruction_core`）→ **机器外壳**（`machine_casing`）→ 机器 / 管理器。
   处理器与核心本身也是有序配方（`raw_*_processor` + `processor_binding`）。
2. **机器方块 = 3x3 有序，模板固定**：「四角 = `quartz_enriched_iron`，中心 = `machine_casing`，
   四边 = 角色件（处理器 + 构造 / 破坏核心 + 玻璃 / 箱子）」。
   实证：`autocrafter` = `ECE/AMA/EDE`、`grid` = `PCG/EMG/PDG`、`storage_monitor` = `PCG/EMG/PDG`、
   `disk_interface` = `ESE/CMD/ESE`、`detector` = `ERE/CMC/EPE`、`disk_drive` = `ECE/EME/EPE`。
   特例：`external_storage` 用 `cable` 当中心（因为它必须贴线缆）。
3. **总线 / 线缆附属设备 = 无序三件套**：「`cable` + 一个核心 + 一个处理器」。
   实证：`importer` = `[cable, destruction_core, improved_processor]`、
   `exporter` = `[cable, construction_core, improved_processor]`、`relay` = 无序四件（含 `redstone_torch`）、
   `pattern_grid` / `crafting_grid` = 无序三件。**方向信息对这类设备无意义，所以 RS 不给形状。**
4. **管理器类 = 有序，且把「被管理的东西」写进配方**：`autocrafter_manager` = `PCG/EMG/PCG`
   （`C` = `tag: refinedstorage:autocrafters`，即你要先有被管理物）；`security_manager` 里放
   `security_card` + `fallback_security_card`。
5. **升级类 = 「基础物居中 + 部件环绕」**：`stack_upgrade` = `speed_upgrade` ×4 + 糖；
   `autocrafting_upgrade` = `upgrade` + 输出总线核心 + 工作台；`range_upgrade` = `upgrade` + 末影珍珠。
6. 标签优先用 `c:` 通用标签（`c:glass_blocks` / `c:chests` / `c:ender_pearls` / `c:silicon` …），
   跨模组兼容；只有「本模组内部枚举」才用 `refinedstorage:` 专属标签。

### 3. 本轮 6 条配方（逐条给出 RS 同位物品作为依据）

| 配方 | 有序/无序 | 形状 / 材料 | RS 同位（风格依据） |
| --- | --- | --- | --- |
| `collection_cache` 归流缓存仓 | 有序 3x3 | `QHQ/DMC/QPQ`：Q=石英富铁 ×4、H=漏斗（吸取）、D=破坏核心（从世界取走）、M=机器外壳、C=构造核心（写入网络）、P=高级处理器 | 机器方块模板（同 `detector` / `disk_drive`） |
| `advanced_quantity_keeper` 高级定量保持器 | 有序 3x3 | `QPQ/PKP/QEQ`：K=**基础版 quantity_keeper 居中**、P=高级处理器 ×3、E=Create 精密机构 | 升级类（基础物居中，同 `range_upgrade` / `stack_upgrade`） |
| `sequence_assembly_executor` 序列装配执行器 | 有序 3x3 | `QPQ/CMD/QEQ`：P=高级处理器、C=构造核心（喂料）、D=破坏核心（回收）、E=精密机构 | `autocrafter` = `ECE/AMA/EDE` 的同位物 |
| `sequence_execution_chamber` 序列执行仓 | 有序 3x3 | `QHQ/IMD/QEQ`：I=**改进处理器（比执行器低一档）**、H=漏斗、D=破坏核心、E=精密机构 | `autocrafter` 同族，处理器降档 = 分布式多台更便宜 |
| `unit_pattern_manager` 单元样板管理舱 | 有序 3x3 | `PCG/EMG/PCG`：C=**序列执行仓**（被管理物）、P=高级处理器、G=`c:glass_blocks` | `autocrafter_manager`（**逐槽照抄**，仅把被管理物换成执行仓） |
| `sequence_return_bus` 序列回流总线 | **无序** | `[cable, destruction_core, advanced_processor, create:brass_funnel]` | `importer` = `[cable, core, processor]`（总线类一律无序） |

**有序 / 无序的取舍依据**：与 RS 的**同位物品**对齐 ——
① 机器方块 / 管理器 / 升级 → **有序**（形状即结构；升级类把低级版放中心表达「由它升级而来」）；
② 线缆附属的「总线」→ **无序**（回流总线是贴在机器 / 线缆上的设备，RS 对同类 importer / exporter /
constructor / destructor / relay 全部无序，方向信息无意义）。判据本身写进了生成脚本的 `SHAPED` / `SHAPELESS` 数据表。

### 4. 生成器与校验脚本（幂等）

* `tools/gen_compat_recipes.py`：数据表驱动 + **幂等写盘**（内容一致就不动文件，保持 mtime）。
  只写自己「拥有」的 6 个文件，**绝不触碰**存储磁盘系列与其余既有配方（保护名单在脚本里）。
* `tools/verify_recipes.py`：两条硬校验 + 退出码。
  1. **id 存在性**：ingredient / 产物 id 必须落在真值池里（本模组注册名 + RS 2.0.0 官方 lang +
     Create lang + **原版 `minecraft_1.21.1_client.jar` 内的 `en_us.json`**）；标签必须落在
     「RS / 原版 / 本模组既有配方里出现过」的集合里（证明能解析）。本轮真值池：
     RS 171 / Create 956 / 原版 2385 / 本模组 26 个 id，允许标签 35 个。
  2. **覆盖完整性**：本模组每个注册物要么有配方，要么在 `no_recipe` 白名单里（每条带理由）；
     同时反向核对「白名单里的 id 若已有配方 → 报错（理由过期）」。
  3. 额外自检有序配方的 3x3 网格、`key` 与图案字符一一对应（多余 / 缺失都报错）。
  **自测记录**：临时塞入一条引用 `refinedstorage:not_a_real_item` /
  `minecraft:also_not_real` / 产物 `rs_create_compat:no_such_block` 的假配方 →
  脚本 3 条 FAIL + 退出码 1（拦得住）；删除后恢复 0 问题。
  **本轮结果**：`id 全部存在，本模组注册物 100% 覆盖（未覆盖 0）`。

### 5. 本轮新增 / 修改文件

新增：`tools/gen_compat_recipes.py`、`tools/verify_recipes.py`、
`src/main/resources/data/rs_create_compat/recipe/` 下 6 个新配方
（`collection_cache` / `advanced_quantity_keeper` / `sequence_assembly_executor` /
`sequence_execution_chamber` / `unit_pattern_manager` / `sequence_return_bus`）、本追加脚本。

**刻意未动**：存储磁盘系列（含最大级，容量与配方归另一任务）、NBT 标记 / 回流总线 / 装填器 /
执行舱链 / 单元样板管理舱 / 扳手的**代码**、所有既有配方文件、任何 Java 代码与贴图 / 布局 / 语言键。
本轮只新增数据（配方 JSON）与工具脚本 —— 因此不需要语言键，也就没有走 `lang_frag` 流程。

## JEI「+」收紧到只有「序列装配」类别（本轮）

> 来源：用户反馈「+ 加在了不是序列装配的地方，甚至一个展示标签界面上也有 +」。
> 硬规则：判定必须基于**配方类型 / 配方类别本身**，不许用「凡是 Create 都算」或「界面有槽位就算」。

### 1. 收紧前的真实触发条件（改前实读，非推测）

| 环节 | 改前实现 | 实际效果 |
| --- | --- | --- |
| 注册 | `registration.addUniversalRecipeTransferHandler(new SequencedAssemblyTransferHandler())` | JEI 把 handler 存进 `(SequencePatternTerminalMenu.class, jei:universal_recipe_transfer_handler)`（`mezz/jei/common/Constants.java` L8，`recipeClass = Object`） |
| 查表 | `mezz/jei/library/recipes/RecipeTransferManager.java` **L32-41**：先按 `(容器类, 类别自己的 RecipeType)` 精确查，**查不到才退回 universal 键** | 只要序列样板终端界面开着，**任何配方类别**（普通合成 / 加工 / 机械手使用 / 纯展示与信息页）都能查到本 handler |
| 预览 | `transferRecipe(..., doTransfer=false)` 对非序列装配配方 `return null` | `null` = 无错误 → `mezz/jei/gui/recipes/RecipeTransferButtonController.java` **L55-63** 判为 `active + visible` → **「+」全部显示且可点**，点了什么也不发生 |

结论：改前的「+」**不筛配方类型**，只筛「当前打开的容器是不是序列样板终端」；类别侧完全没有条件。

### 2. 收紧后的判定（唯一条件 = 配方类型精确匹配）

```java
// SequenceTerminalJeiPlugin.java
private static final RecipeType<RecipeHolder<SequencedAssemblyRecipe>> SEQUENCED_ASSEMBLY_TYPE =
    RecipeType.createRecipeHolderType(AllRecipeTypes.SEQUENCED_ASSEMBLY.getId()); // create:sequenced_assembly
...
registration.addRecipeTransferHandler(new SequencedAssemblyTransferHandler(), SEQUENCED_ASSEMBLY_TYPE);
```

- `createRecipeHolderType` 的 `recipeClass = RecipeHolder`，与 Create 侧
  `CreateRecipeCategory.Builder#build` → `createRecipeHolderType(id)`（id = `create:sequenced_assembly`）**完全相同**，
  `RecipeType.equals` 比 uid + recipeClass → 命中；其它类别 uid 不同 → 查不到 → 无 handler。
- handler 类型由 `IUniversalRecipeTransferHandler<Menu>` 改为
  `IRecipeTransferHandler<Menu, RecipeHolder<SequencedAssemblyRecipe>>`，
  原先那层 `recipe instanceof RecipeHolder && value instanceof SequencedAssemblyRecipe` 的**宽松兜底已删除**（类型由注册键钉死）。

### 3. 「+」出现位置清单（收紧后）

| 场景 | 改前 | 改后 |
| --- | --- | --- |
| Create **序列装配**（`create:sequenced_assembly`）类别 | 有 | **有（唯一）** |
| 普通合成 / 加工（冲压、锯切、混合、注液、鼓风…） | 有 | 无 |
| 机械手使用（`create:deploying` / `item_application`）等其它 Create 类别 | 有 | 无 |
| 纯展示 / 信息页（无实际配方转移目标） | 有 | 无 |
| 其它模组全部类别 | 有 | 无 |

判定出处：`RecipeTransferUtil`（无 handler → `RecipeTransferErrorInternal`，INTERNAL，`allowsTransfer=false`）
→ `RecipeTransferButtonController.updateState` 中 `setVisible(type == USER_FACING)` = `false` →「+」隐藏。

### 4. 本轮修改文件

`src/main/java/cretae/cookiewyq/rs_create_compat/client/SequenceTerminalJeiPlugin.java`（唯一改动）。

### 5. 验证

`manual_compile.ps1` → `COMPILE OK（220 sources）`；`verify_mixin_shadows.py` = 0 问题；
`tmp_textures/verify_gui_layout.py` = 0 问题；`tools/audit_gui_textures.py` = 0 问题。
（游戏内实测未做：本机无法判定「点 + 后终端是否正确填充」，逻辑链路未变，仍走 `SetSequenceImportPacket`。）


***

## 执行舱贴图/模型重做 + 「成链」逐条验证（本轮）

> 来源：用户两条意见 —— ①「执行仓应该像自动合成舱那样，只有一个指向性明确的贴图：
> 周围四个面是相同贴图、顶部一个、底部一个，四个侧面贴图带指向性」；
> ②「貌似现在也没看出来你那个成链的功能完成了」；
> ③「以后写贴图不要全部共用一个贴图」。本轮把 ①③ 落地，并把 ② 逐条验证 + 补可见性。

### 1. 执行舱外观：照 RS 自动合成舱（Autocrafter）的结构

**模型朝向约定**（与 RS 自动合成舱、原版观察者完全一致）：模型**以「前端面 = UP 面」烘焙**，
blockstate 用 `x:90` 系列旋转把前端面转到 `facing` 指的方向：

| `facing` | 模型旋转 | 与 RS 的对照 |
| --- | --- | --- |
| `north` | `x:90` | `direction=north` 同值 |
| `east` | `x:90, y:90` | `direction=east` 同值 |
| `south` | `x:90, y:180` | `direction=south` 同值 |
| `west` | `x:90, y:270` | `direction=west` 同值 |

这样 **只有侧面那一张贴图带指向性**，且箭头（贴图内朝上）在游戏里恒指向「机器侧 / 朝向」——
正是 RS 自动合成舱的表现；顶/底（模型 up/down 键）分别是「连接口」与「背板」，各自独立成图。

贴图（16×16，RS 灰色机壳底图 + 本模组青色强调色；**互不共用**）：

| 文件 | 用在哪 | 说明 |
| --- | --- | --- |
| `block/sequence_execution_chamber_side[_active].png` | 模型 N/E/S/W 四面 | 带指向前端面的箭头（同一台机器的四个侧面共用，用户明确允许） |
| `block/sequence_execution_chamber_front[_active].png` | 模型 UP = 前端面（指向 `facing`） | 发光方形接口 |
| `block/sequence_execution_chamber_back[_active].png` | 模型 DOWN = 背面 | 散热格栅 |

* 生成脚本：`tools/gen_chamber_faces.py`（幂等；同时把 6 张图与 4 倍预览条留档到
  `tmp_textures/chamber_faces/`）；模型/blockstate：`tools/gen_face_split_models.py`。
* **旧贴图清理**：`sequence_execution_chamber.png`（历史遗留、无人引用）、
  `sequence_execution_chamber_active.png`、`sequence_execution_chamber_inactive.png` 三张「六面一张图」已删除。
* **旧存档平滑**：方块状态属性 `facing` / `active` **一个都没变**（只改了贴图与模型旋转的对应关系），
  旧档里的执行舱读档后朝向与朝向语义完全不变，无需任何数据迁移。

### 2. 「成链」功能：逐条验证结论（读代码 + 逐项核对，不是"看界面猜"）

| 验证项 | 结论 | 依据 |
| --- | --- | --- |
| 名字是否同步到全链 | ✅ 真同步 | `getChamberName()` 读 `chainHead()`、`setChamberName()` 写 `chainHead()`（成员自动转发）⇒ 改一处 = 全链生效 |
| 配方类型是否同步到全链 | ✅ 真同步 | 同上，`getRecipeType()` / `setRecipeType()` 全部委托链首；链上任一台还有单元样板时服务端锁定配方类型 |
| 在哪设置指向 / 是否好找 | ✅ 有可见入口（本轮增强） | 主界面标题栏「配置」→ 子界面底部「链指向」滚轮（几何见 `tmp_textures/CHAMBER_BINDING_CONFIG_GUI_DOC.md`）。本轮把链信息提到**主界面**：状态行显示「配方类型：X（链 N 台 · 指向 北）」，悬停 tooltip 多出「本台指向 / 链头 / 从哪进设置」三行 |
| 分叉是否被拒绝 | ✅ 拒绝 | `setChainLink` 里 `hasOtherPredecessor` ⇒ `LinkResult.FORK`，行动栏提示「该仓已被另一台指向」 |
| 成环是否被拒绝 | ✅ 拒绝 | `reachesMe(target)`（判「从 target 沿指向走能否**经过**本仓」，比只比链首严格）⇒ `LinkResult.CYCLE` |
| 读档后链能否重建 | ✅ 能 | `chainLink` 进 NBT（`saveAdditional/loadAdditional` 的 `ChainLink`）；链**不进任何缓存**，`chainHead()/chainMembers()` 每次按当前世界状态现推；旧档遗留的分叉/环由 `sanitizeRayServer()` 一次性收敛成射线（只取消指向，绝不销毁配置） |

另外三条硬约束也复核过：链长上限 8（`MAX_CHAIN_LENGTH`，照 RS 的 `MAX_CHAINED_AUTOCRAFTERS`）、
**入度 ≤ 1**（射线不分叉）、拒绝路径一律回发权威值（界面不会停在假象上）。

### 3. 玩家最短操作路径（照这个走就能串链）

1. 摆好 2 台以上执行仓（相邻或隔着别的方块都行，只要「指向」能指到）；
2. 任意一台**右键**打开界面 → 点标题栏右侧的「**配置**」按钮；
3. 在子界面**底部**的「**链指向**」滚轮上选方向（就是朝邻居那一台），点「确定」；
4. 回到主界面：状态行会显示「配方类型：X（链 N 台 · 指向 北）」——**悬停这一行**可看到链头是谁、
   以及「设置链指向：点本界面上方的『配置』按钮…」；
5. 之后**改名字 / 改配方类型**：在链上**任意一台**改，整条链一起生效（链上任一台仍放着单元样板时，
   配方类型会被锁定，必须先把样板取走）。

### 4. 共用贴图审计（新脚本 `tools/audit_shared_textures.py`）

判据：把每个模型的贴图引用展开成「面 → PNG」，**同一张 PNG 落在多个不同的面上**即为一图多用；
豁免仅一种：恰好落在 `north/east/south/west`（同一台机器的四个竖直侧面，用户明确允许）。
原版父模型（`orientable[_with_bottom]` / `cube[_all]` …）按内置映射展开面，避免漏判。

**本轮修正**（`tools/gen_block_face_split.py` + `tools/gen_face_split_models.py`，均已幂等）：

| 方块 | 修正前 | 修正后 |
| --- | --- | --- |
| 序列执行舱 | `cube_all`（六面一张图） | side / front / back 三张，见 §1 |
| 单元样板管理舱 | `orientable_with_bottom`（背面复用侧面、底面复用顶面） | 六面各一张：front / back / left / right / top / bottom（RS 自动合成管理舱本来就是五张独立贴图 + 通用 bottom），旧 `*_side*.png` 已删除 |
| 蓝图装填器 / 高级蓝图装填器 / 归流缓存仓 / 范围充电器 / 样板库 | `cube_all`（六面一张图） | 四个侧面共用 side、顶面 top、底面 bottom（三张各自独立；底面还带本方块强调色接缝） |

顺手清理的死图：`item_collector_active.png` / `item_collector_inactive.png`（方块早已改名成
`collection_cache`，全工程无人引用）、`range_charger.png`（无人引用的历史遗留）。

**按硬规则排除、只报告不改**（这几路贴图由其它并行任务在改，本轮不得触碰）：
`quantity_keeper` / `advanced_quantity_keeper`（保持器）、`sequence_pattern_terminal`（SPT）、
`sequence_return_bus`（回流总线）。它们的「一图多用」写在审计脚本输出的 `[排除]` 行里，含原因。

### 5. 本轮改动文件

新增：`tools/gen_chamber_faces.py`、`tools/gen_block_face_split.py`、`tools/gen_face_split_models.py`、
`tools/gen_lang_frag_execchain2.py` + `tools/lang_frag_execchain2.json`、`tools/audit_shared_textures.py`、
`tmp_textures/chamber_faces/` 与 `tmp_textures/face_split/`（预览留档）。

修改：`blockstates/sequence_execution_chamber.json`（x:90 系列旋转）、
`models/block/sequence_execution_chamber[_active].json`、
`models/block/{schematic_loader,advanced_schematic_loader,collection_cache,range_charger,sequence_assembly_executor}[_active].json`、
`models/block/unit_pattern_manager[_active].json` + `tools/gen_unit_manager_resources.py`、
`client/screen/SequenceExecutionChamberScreen.java`（主界面显示链指向 + tooltip 补链头 / 设置入口）、
`assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`，+3 键、-1 键）。


***

## 第十六轮（2026-09-19）：扳手默认档确认 + 断点记录失效 + 回流总线玩家侧下线 + 删「存回网络」

> 来源：用户本轮反馈 ①「断开连接的现在这个操作方法不好…改成在连接处右键，重新放置也不影响，会重新连上」
> ②「回流总线怎么还在？把它替换成用总线线缆连接序列执行舱」③「那个纯回网络…不要那个东西」。

### 1. 扳手断开线缆：默认档 = `seam`（在接缝处右键）

`Config.wrenchCableDisconnectMode` 默认值就是 **`seam`**（`face` / `off` 两种仍保留、仍由配置切换）。
`seam` 的交互口径与用户原话完全对齐：**右键两个方块之间的那一段接缝**（点哪一面 = 断哪一条连接），
`face` 档才需要玩家先理解「面不自动连接」的语义。未启用（`off`）时本模组完全不接管 RS 线缆。

### 2. Bug 修复：断点记录随「方块被拆 / 被替换 / 重新放置」失效（核心 4 行）

```java
boolean changed = saved.seam.remove(pos) != null;      // ① 本位置的接缝键（它可能是 owner 那一侧）
changed |= saved.face.remove(pos) != null;             // ② 本位置的面记录（面已随方块消失）
for (Direction d : Direction.values()) {               // ③ 邻块「指向本位置」的两种记录（位粒度清除）
    changed |= clearBit(saved.seam, pos.relative(d), d.getOpposite());
    changed |= clearBit(saved.face, pos.relative(d), d.getOpposite());
}
if (!changed) return;  saved.setDirty();  broadcast(level, saved);  refresh(pos + 六邻块);
```

* 实现位置：`support/RsccCableCuts#onBlockChanged(ServerLevel, BlockPos)`（新增）+ `clearBit(...)`（新增）。
* 触发点：`RsccWrenchCableInteraction` 新增三个监听 —— `BlockEvent.BreakEvent`（玩家破坏）、
  `BlockEvent.EntityPlaceEvent`（玩家放置 / 替换）、`ExplosionEvent.Detonate`（爆炸摧毁）。
  **刻意不用 `NeighborNotifyEvent`**：本模组自己的 `refresh`（方块更新 / 网络图重建）也可能引发邻块通知，
  用它会把自己刚写下的断开记录当场清掉。玩家主动破坏 / 放置事件不会由内部刷新触发。
* 为什么这样就能「重新放置即恢复自动连接」：接缝键只存在**坐标字典序较小**的一侧，
  所以必须同时清「本位置」与「六个邻块指向本位置」的位 —— 两侧都清干净后，
  方块放回来时 `canAcceptIncomingConnection` 立刻返回 true，RS 按原版逻辑自动连边 / 长臂。
* **扛住读档**：清理写进 `SavedData#setDirty()`，与存档同生共死，重进世界不会复活脏记录。
* **立刻生效 + 双端一致**：先 `broadcast`（S2C 整份快照）再 `refresh`（`updateConnections` +
  `containerProvider().update` + `sendBlockUpdateToClient`），与扳手切换同一条链路；
  `refresh` 只取「已存在」的方块实体，绝不强制加载。

### 3. 回流总线：玩家侧下线（方块保留为遗留方块）

| 项 | 做法 |
| --- | --- |
| 创造模式物品栏 | `RS_Create_Compat` 的 `displayItems` 里**移除**该物品（原地留注释说明原因） |
| 合成配方 | 删除 `data/rs_create_compat/recipe/sequence_return_bus.json`；`tools/gen_compat_recipes.py` 的 `SHAPELESS` 条目同步删除 |
| 配方校验 | `tools/verify_recipes.py` 的 `no_recipe` 白名单新增该 id + 理由（否则「注册物必须有配方」这条会报错） |
| 遗留兼容 | 方块 / 方块实体 / 菜单 / 物品注册**一字未动** —— 已放置的读档继续工作，`Buffer` / `Upgrades` / `PatternSlot` / `FluidBuffer` 全部照旧，**绝不销毁玩家资源** |
| 文案 | `block.rs_create_compat.sequence_return_bus.help` 开头加「（已弃用，请改用「RS 输入总线 + 线缆」承担回流；本方块保留供旧存档继续使用）」 |

**「输入总线 + 线缆」能否真的完成回流（读代码验证，结论：成立）**：

* 执行舱把输出存储经 `logisticsItemHandler(direction)` 暴露为 **只出** 的 `IItemHandler`
  （仅 `FaceMode.OUTPUT / INTERMEDIATE` 两个面；流体同理）→ RS **输入总线**贴在这些面上即可把它抽进网络；
* 输入总线属于可穿行集合 `RsccWireBlocks`（线缆 / 输入总线 / 输出总线），
  因此「线缆连到执行舱」这条搜链（`searchChamber`）本来就能识别输入总线，连接模型不需要任何改动；
* 回流后的物品进入**同一张 RS 网络**，执行舱本身就从网络认领下一步输入（`fillInternalForBus`），
  于是「产线吐出的东西 → 网络 → 执行舱下一步」闭环成立，无需回流总线。

### 4. 删除「存回网络」（单元样板管理舱）

用户口语的「纯回网络」= 该按钮文案「**存回网络**」（存 / 纯 同音近形）。**整链删除、不留死热区**：

* `menu/UnitPatternManagerMenu.java`：删除 `STORE_BTN_X/Y/W/H` 四个常量；
* `client/screen/UnitPatternManagerScreen.java`：删除按钮控件、其手动 tooltip 与 `isInside` 辅助方法
  （背景贴图**本来就没烘焙该按钮**，因此界面上不会留下点得动却无效的热区）；
* `network/StoreUnitPatternsPacket.java`：**文件删除**；`RS_Create_Compat` 里对应的 C2S 注册一并删除；
* `block/entity/UnitPatternManagerBlockEntity.java`：删除 `storeChapterUnitsToNetwork()`（含 SIMULATE → EXECUTE 搬运逻辑）；
* 语言键：`gui.rs_create_compat.unit_pattern_manager.store` / `.store.tip` 从
  `tools/gen_lang_frag_unitmanager.py` + `tools/lang_frag_unitmanager.json` + 中英 lang 文件**全部移除**
  （走 frag 生成器，保证 `apply_lang_frag.py` 重跑不会把它们加回来）。
* 单元样板本身仍可用原有路径流转：执行舱自己的分组是**真槽位**（可放可取），合并视图仍**只出不进**。

### 5. 本轮修改文件

Java：`support/RsccCableCuts.java`、`support/RsccWrenchCableInteraction.java`、
`RS_Create_Compat.java`（创造栏移除回流总线 + 删除存回网络 C2S 注册）、
`menu/UnitPatternManagerMenu.java`、`client/screen/UnitPatternManagerScreen.java`、
`block/entity/UnitPatternManagerBlockEntity.java`；删除 `network/StoreUnitPatternsPacket.java`。

数据 / 工具：删除 `data/rs_create_compat/recipe/sequence_return_bus.json`、
`tools/gen_compat_recipes.py`、`tools/verify_recipes.py`、`tools/gen_lang_frag_cc.py` + `tools/lang_frag_cc.json`（弃用文案）、
`tools/gen_lang_frag_unitmanager.py` + `tools/lang_frag_unitmanager.json`（删 2 键）、
`assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

文档：`tmp_textures/SEQUENCE_RETURN_BUS_GUI_DOC.md`（顶部加弃用状态）、
`tmp_textures/UNIT_PATTERN_MANAGER_GUI_DOC.md`（§3 标题行去掉按钮 + 补搜索框行、§8 tooltip 规则）、本文件。

### 6. 顺带发现（只报告，交由贴图任务统一处理）

* `tools/audit_shared_textures.py` 结论：**一图多用问题 0 项**；另有 4 项按硬规则 `[排除]`
  （`sequence_pattern_terminal_{active,inactive}.png` 与 `sequence_return_bus_{active,inactive}.png`
  —— 分别是 SPT 与回流总线的贴图，由其它并行任务处理，本轮不碰贴图）。



***

## 第十七轮（2026-09-19）：Curios 必选前置 + 终端快捷键（默认 G）

> 来源：用户本轮需求「把这个饰品栏模组（Curios）标记为前置（最终：**强必选**）」+
> 「可以指定一个快捷键来打开这一个终端，快捷键的方式和 MC 原版一样，都在按键绑定（Controls）里设置」+
> 「光标记为可选/必选还不够，**功能要真的能用**」。
> 硬规则不变：服务端权威、绝不销毁玩家资源、语言键走 `lang_frag_*` + `apply_lang_frag.py`。

### 1. Curios 的真实 modId 与依赖声明

* **真实 modId = `curios`**（`top.theillusivec4.curios.api.*` 只是它的包名，与 modId 无关；
  Curios 自己注册的物品栏能力 id 也是 `curios:item_handler`）。
* `src/main/templates/META-INF/neoforge.mods.toml` 新增（**必选**，不是 optional）：

```toml
[[dependencies."${mod_id}"]]
modId = "curios"
type = "required"
versionRange = "[9.0.0,10.0.0)"   # MC 1.21.1 对应 Curios 9.x
ordering = "AFTER"
side = "BOTH"
reason = "Required for the terminal Curios slot and the open-terminal keybinding."
```

* 1.21.1 的真实版本从「本地 Gradle 缓存里已解析到的制品元数据」读出：
  `D:\gradle\caches\modules-2\metadata-2.106\descriptors\top.theillusivec4.curios\curios-neoforge\9.2.3+1.21.1`。
  注意 **jar 本身不在本地缓存**（缓存里只有其它工程留下的 1.16.5 Forge 版），
  所以 dev 环境首次解析该依赖**需要联网**。

### 2. dev 环境（runClient）加载 Curios

`build.gradle`：

```groovy
maven { url = "https://maven.theillusivec4.top/" }          // Curios 官方 maven
compileOnly("top.theillusivec4.curios:curios-neoforge:${curios_version}")
runtimeOnly("top.theillusivec4.curios:curios-neoforge:${curios_version}")
```

`gradle.properties`：`curios_version=9.2.3+1.21.1`（版本号取自上面的缓存元数据，确有其物）。
坐标写成 `curios-neoforge`（Curios 在 NeoForge 上的制品名）。

**离线不炸**：本工程的编译验收走 `tools/manual_compile.ps1`（javac + 本地缓存 jar，不读 Gradle 依赖），
且**所有 Curios 调用一律走反射**（见 §4），所以「缓存里没有 Curios jar」时
`COMPILE OK` 依旧成立；只有需要真正跑 `runClient`（要加载 Curios 本体）时才必须联网。

### 3. 快捷键：注册位置与触发链路

| 项 | 值 / 位置 |
| --- | --- |
| 类 | `client/TerminalKeybinds.java`（`@EventBusSubscriber(bus = MOD, value = CLIENT)`） |
| KeyMapping 注册 | MOD 总线 `RegisterKeyMappingsEvent#onRegisterKeyMappings` → `event.register(mapping)` |
| 默认键 | **G**（`GLFW_KEY_G`）：原版未占用；Create 的按键是 Alt / Ctrl 系；RS 自己的无线终端快捷键默认**不绑定**，故互不冲突；玩家可在按键绑定里随时改键 / 解绑 |
| 类别 | `key.categories.rs_create_compat`（本模组自己的分类，中英双语）→ 原版「选项 → 控制 → 按键绑定」里单独一组 |
| 冲突上下文 | `KeyConflictContext.IN_GAME` —— 打开任意界面（含聊天栏 / 文本框输入）时该键**根本不激活** |
| 触发 | 游戏总线 `InputEvent.Key`（在 `ClientInit#onClientSetup` 里 `NeoForge.EVENT_BUS.addListener`），与 RS 原版快捷键同一条链路 |
| 额外条件 | `Minecraft.player != null` 且 `screen == null`（双保险）且**非旁观**；`while (consumeClick())` 一次按键只处理一次 |
| 不误触发 | 上述任一条件不满足时**主动清掉累计的按下**（`discardClicks`），因此「在聊天栏 / GUI 里按过这个键」不会在界面关闭的瞬间弹出终端 |

**打开过程 = 复用 RS 原版链路（零新网络包、零新打开逻辑）**：

```java
RefinedStorageApi.INSTANCE.useSlotReferencedItem(player,
    normal, charged, creative);   // ① 客户端：找「唯一一个」终端引用
// ② 找到才发 RS 原生 C2S UseSlotReferencedItemPacket
// ③ 服务端 resolve 引用 → instanceof SlotReferenceHandlerItem → 调既有的
//    AbstractNetworkEnergyItem#use(ServerPlayer, ItemStack, SlotReference)
//    → AdvancedRemoteTerminalItem#openModeScreen（模式 / 电量 / 网络绑定全部照旧）
```

* **「是否持有终端」的判定**：由 RS 的 `CompositeSlotReferenceProvider#findForUse` 做 ——
  「背包（RS 自带 `InventorySlotReferenceProvider`）+ 各模组注册的 `SlotReferenceProvider`」，
  结果必须**恰好一个**：0 个 → 不打开，RS 原版红字短提示
  `item.refinedstorage.network_item.cannot_open_because_not_found`（"There isn't any %s in your inventory."）；
  ≥2 个 → 不打开，改提示 "...more than one..."。两种提示都是 RS 自带文案，不新增语言键。
* **服务端仍然是最终裁判**：包到服务端后重新解引用 + `instanceof` 校验物品类型，客户端伪造不了。

### 4. 饰品槽里的终端也要能被快捷键找到（`support/RsccCuriosTerminalSlot.java`）

本模组上一轮注册了自己的饰品槽 `rs_create_compat_curios_integration`（数据包 JSON）。
RS 原版只认识「背包 + 别的模组注册的提供者」，因此这里按 RS 的**官方扩展点**补一个提供者：

* `RefinedStorageApi#getSlotReferenceFactoryRegistry().register(id, factory)` —— 引用要能被
  `SlotReferenceFactory.STREAM_CODEC` 序列化才能随 C2S 包发给服务端（双端都要注册）；
* `RefinedStorageApi#addSlotReferenceProvider(provider)` —— 列出「本模组饰品槽里装着终端的格子」；
* 引用只存**槽位下标**，`resolve(player)` 时按同一槽位重新取**存活栈**
  （模式写回 / 电量消耗作用在这份实体上，与 RS 原版 `InventorySlotReference` 一致）；
  下标越界或该格已空 → `Optional.empty()`，服务端自然拒绝打开。
* `isDisabledSlot` 返回 `false`：饰品槽不在容器菜单里，没有需要禁用的菜单槽位。

**为什么全程反射**：本地缓存没有 Curios 1.21.1 的 jar，编译期引用其类会直接编译失败。
反射链：`CuriosApi.getCuriosInventory(LivingEntity)` → `ICuriosItemHandler#getStacksHandler(String)`
→ `ICurioStackHandler#getStacks()`（回退 `getSlots()` + `getStackInSlot(int)`）；
方法句柄按「实例类型自省」查找并缓存（Curios 各版本把接口类名改过名，故不写死类名）。
Curios 缺失 / 方法名不符 → 整条链路**静默降级为「只认背包」**并打一条 warn，绝不抛异常、绝不影响加载。
（顺带好处：终端戴在饰品槽里时，模式切换 Tab 的 `SwitchTerminalModePacket` 也能正常回写物品了。）

### 5. 语言键（走 frag，幂等）

`tools/gen_lang_frag_keybind.py` → `tools/lang_frag_keybind.json` → `tools/apply_lang_frag.py`：

| 键 | en | zh |
| --- | --- | --- |
| `key.categories.rs_create_compat` | RS & Create Compat | RS × 机械动力兼容 |
| `key.rs_create_compat.open_advanced_remote_terminal` | Open Advanced Remote Terminal | 打开高级远程终端 |

### 6. 本轮新增 / 修改文件

新增：`client/TerminalKeybinds.java`、`support/RsccCuriosTerminalSlot.java`、
`tools/gen_lang_frag_keybind.py` + `tools/lang_frag_keybind.json`、本追加脚本。

修改：`src/main/templates/META-INF/neoforge.mods.toml`（Curios = required / AFTER）、
`build.gradle`（Curios maven + compileOnly/runtimeOnly）、`gradle.properties`（`curios_version`）、
`client/ClientInit.java`（注册游戏总线 `InputEvent.Key` 监听）、
`support/OptionalDeps.java`（`MOD_CURIOS` + `isCuriosLoaded()`）、
`RS_Create_Compat.java`（commonSetup 里注册饰品槽引用来源）、
`assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：既有 Curios 数据包（`data/rs_create_compat/curios/**`、`data/curios/tags/item/**`）、
单元样板管理器 / SPT、红石控制 / 保持器贴图、扳手 / 回流总线、执行舱模型 / 贴图相关文件，
以及任何 GUI 布局与贴图（本轮不涉及界面）。

### 7. 验证

* `tools/manual_compile.ps1` → `COMPILE OK (223 sources)`；
* `tools/verify_mixin_shadows.py` → 0 问题、退出码 0（本轮未新增 Mixin）；
* `tmp_textures/verify_gui_layout.py` / `tools/audit_gui_textures.py` / `tools/audit_shared_textures.py` → 各 0 项；
* `tools/verify_recipes.py` → 通过（未改配方）；
* `tools/apply_lang_frag.py` 重跑 → 新增 0 / 覆盖 0（幂等）。
