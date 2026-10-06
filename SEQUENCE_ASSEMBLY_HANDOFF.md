# 序列装配（Sequenced Assembly）完整技术交接

> **来源声明**：本文档基于 `docs/` 目录下的设计文档（`SEQUENCE_CHAIN_REDESIGN.md`、
> `GEAR_BLOCKAGE_AND_FLOW_CHECK.md`、`DESIGN_DECISIONS_ROUND4.md`、
> `BLOCKAGE_EXPERIMENT_PROTOCOL.md`、`SEQUENCE_ASSEMBLY_LOG_GUIDE.md`、
> `ROUND_LOG_INSTRUMENTATION_20261004.md`）整理，**不依赖当前源码**
> （当前代码可能已被他人改动，以本文档描述的设计意图为准）。

***

## 一、三条样板配方的完整事实（从 Create 源码读出）

来源：`local_src/create_src/data/create/recipe/sequenced_assembly/{sturdy_sheet,precision_mechanism,track}.json`

### 1.1 `create:sturdy_sheet`（坚固板）

| 项       | 值                                   |
| ------- | ----------------------------------- |
| 起步原料    | `#c:dusts/obsidian`（黑曜石粉）           |
| 过渡件     | `create:unprocessed_obsidian_sheet` |
| loops   | 无（默认 1）                             |
| **总步数** | **3**                               |
| 结果池     | `create:sturdy_sheet` ×1（无概率）       |

| 步 | 类型                | 投入（下标 ≥1）               | 产出                           |
| - | ----------------- | ----------------------- | ---------------------------- |
| 0 | `create:filling`  | 500 mB `minecraft:lava` | `unprocessed_obsidian_sheet` |
| 1 | `create:pressing` | 无                       | `unprocessed_obsidian_sheet` |
| 2 | `create:pressing` | 无                       | `unprocessed_obsidian_sheet` |

**关键事实**：

- 三步的产出**都是同一个物品** `unprocessed_obsidian_sheet`（就是过渡件本身）。

- 第 1、2 步是**同一种处理器（冲压）**，且**都没有额外投入物**。

- 因此\*\*「当前走到第几步」只能由 `SEQUENCED_ASSEMBLY` 数据组件表达\*\*，**不能**靠物品本身区分。

### 1.2 `create:precision_mechanism`（精密构件）

| 项         | 值                                       |
| --------- | --------------------------------------- |
| 起步原料      | `#c:plates/gold`（金板）                    |
| 过渡件       | `create:incomplete_precision_mechanism` |
| **loops** | **5**                                   |
| **总步数**   | **3 × 5 = 15**                          |
| 结果池       | 精密构件 120%（主产物，非必得）+ 8 项概率废料             |

| 步（循环内下标） | 类型                 | 投入（下标 ≥1）                    |
| -------- | ------------------ | ---------------------------- |
| 0        | `create:deploying` | `create:cogwheel`（齿轮）        |
| 1        | `create:deploying` | `create:large_cogwheel`（大齿轮） |
| 2        | `create:deploying` | `#c:nuggets/iron`（铁粒）        |

**关键事实**：

- **三个步都是** **`deploying`（机械手）**，同一个机械手方块依次做齿轮 → 大齿轮 → 铁粒，循环 5 遍。

- 同一个机械手在**不同时刻要不同的投入物**，且**手里只能拿一件**（Create 的 `DeployerItemHandler` 对「手里已有不同物品」一律拒收）。

- **主产物不是必得**（120%）⇒ 交付 N 件要跑 >N 件，过渡件总量天然超过订单量。

### 1.3 `create:track`（列车轨道）

| 项       | 值                            |
| ------- | ---------------------------- |
| 起步原料    | `#create:sleepers`（石头台阶，多候选） |
| 过渡件     | `create:incomplete_track`    |
| loops   | 无（默认 1）                      |
| **总步数** | **3**                        |
| 结果池     | `create:track` ×1（无概率）       |

| 步 | 类型                    | 投入（下标 ≥1）                                         |
| - | --------------------- | ------------------------------------------------- |
| 0 | `create:deploying`    | `#c:nuggets/iron` **或** `#c:nuggets/zinc`（标签，二选一） |
| 1 | `create:deploying`    | 同上（标签二选一）                                         |
| 2 | **`create:pressing`** | 无                                                 |

**关键事实**：

- 第 0、1 步是**同一个机械手**、**同一组候选**（铁粒 / 锌粒）⇒ 这是「一个原料的多个候选」，不是「两份需求」。

- **第 2 步是冲压** —— 与坚固板的第 1、2 步**同一种处理器**，可能共用同一台冲压机。

- 结果池**无概率** ⇒ 下单 N 件就是 N 件。

***

## 二、三条配方的共性（绝大多数 bug 的总根源）

### 共性 1：过渡件是「同一个物品」，步序只存在于数据组件里

三条配方**全都**满足：`transitional_item` 就是每一步的 `results[0]`，所以**同一个物品在不同步之间流转，只能靠** **`SEQUENCED_ASSEMBLY`** **组件里的** **`step`** **区分**。

> **推论**：任何「把物品当成普通物品来搬运 / 匹配 / 过滤」的代码路径，**都会把不同步的同一物品混淆**。

### 共性 2：同一种处理器承担多个步 ⇒ 必须按「步」而不是按「物品」判定

- 坚固板：冲压 ×2（同配方内）

- 列车轨道：冲压 ×1 —— **与坚固板共用同一台冲压机**

- 精密构件：机械手 ×15（同配方内）

> **推论**：机器侧必须能回答「此刻轮到我做第几步」，而这个信息**只能**从台上那件在制件的 `step` 反推。

### 共性 3：一个工位一次只能有一件在制件（Create 硬约束）

- 冲压：一个加工位

- 机械手：一个手位（且**手里拿着 A 就拒收 B**）

- 注液：一个加工位

> **推论**：任何「同时推两份给同一工位」的行为都会导致 `machine_full` / 永久卡死。而「刚推上去又立刻抄走」会让加工**永远做不完**。

### 共性 4：多条链在**同一张 RS 网络**上跑，共享同一批总线与机器

用户的实测布局（蓝图 `test_place.nbt`，32×6×19）：

```
注液仓  (-10,-60,10)  → 置物台/机器 (-10,-60,12)
冲压仓  (-16,-60,10)  → 置物台/机器 (-16,-60,12)
USE 仓  (-5,-60,6)    → 置物台/机器 (-7,-60,5) 与 (-7,-60,6)   ← 两个工位！
```

**多台仓、多台机器、多条配方共网** ⇒ 归属判定必须**按 (配方, 步序)**，而不是「谁勾了这个物品的类别」。

***

## 三、原料如何拉取（Pull）

### 3.1 拉取的总入口：`fillInternalForBus`

执行仓的备料逻辑集中在 `SequenceExecutionChamberBlockEntity#fillInternalForBus`，由引擎 tick 驱动。

**前置闸门（全部为真才会拉料）**：

1. `busAutoCraftGate` —— 自动合成总开关
2. `busRelevantTaskGate` —— 本仓有相关任务在跑
3. `busGoalTaskGate` —— 目标任务闸门
4. `busConfigGate` —— 总线配置闸门
5. `!pushStalledOnDestination()` —— **推不动就不要再拉**（下游拒收时停手，否则齿轮净下降）

### 3.2 拉取的类别来源：`computeBusCategories`

执行仓的总线类别表由 `computeBusCategories()` 每 20 tick 重建一次。类别分五类：

| 类别前缀                     | 含义        | 完整 id 示例                                         |
| ------------------------ | --------- | ------------------------------------------------ |
| `input:`                 | 输入性产物（物品） | `input:minecraft:iron_nugget`                    |
| `fluid:`                 | 流体输入      | `fluid:minecraft:lava`                           |
| `intermediate:<配方>:<步序>` | 中间产物（过渡件） | `intermediate:create:sequenced_assembly/track:0` |
| `result:`                | 成品        | `result:create:sturdy_sheet`                     |
| `scrap:`                 | 废料        | `scrap:create:cogwheel`                          |

**类别生成来源**（按优先级）：

1. 单元样板自己记录的输入物（`UnitData#input()`）
2. 样板带配方 id 时，回查 Create 配方数据：取 `SequencedAssemblyRecipe#getSequence()` 第 `step % size` 步，**跳过下标 0（被 Create 覆盖为过渡件）**，取其后每个 ingredient 的首个物品
3. 中间产物 = 各配方 `getTransitionalItem()` 去重后的集合

### 3.3 所有权闸门：`categoryOwned`

带 `(配方, 步序)` 的类别（即 `intermediate:<配方>:<步序>`）必须命中 `ownedSteps()` 才允许生成备料需求。

**为什么需要这道闸门**：`computeBusCategories` 会补出「显示用」的中间产物类别（让玩家能看见 / 勾选），但如果不夹这道闸门，玩家勾中「总样板指派补出来的显示用类别」就等价于「没有订单也开工」（实测：没下单列车轨道，仓却去拉石头台阶 / 铁粒并一直产出过渡件）。

不带配方信息的类别（`input:` / `fluid:` / `result:` / `scrap:`）一律放行 —— 它们是既有口径（玩家把某个输入 / 成品类别勾到总线上，就代表要喂 / 要收它）。

### 3.4 拉取的具体动作

对每个通过所有权闸门的类别：

1. 计算该类别的目标量（`perBatch = Math.max(1, info.amount())`，即配方每批所需）
2. 从 RS 网络 `pullItem` / `pullFluid` 拉取差额（目标量 − 舱内存量）
3. 拉取失败时（网络无货）触发自动合成请求 + 缺料上报

**「推不动就不拉」的安全语义**：`pushStalledOnDestination()` 返回 true 时，备料侧一律停手。收回侧不受此闸门影响（把东西还给网络永远不会有害）。

### 3.5 备料份额闸门：`wantingTargetCount`

为防止「多开一件在制件」，备料侧有份额闸门：

- 按 `supplyStations` 工位归并 + `supplyTargetHoldsItem`

- `min(want, remainingOrderUnits())` —— 下单 1 个 ⇒ 目标量 = 1，而不是「要它的工位数 = 2」

***

## 四、产物如何发放（Push / Deliver）

### 4.1 两条输出模式

执行仓有两种输出模式，由 `OutputMode` 枚举控制（NBT 键 `OutputMode`，旧存档缺省 = 面输出）：

| 模式                    | 语义                                                               |
| --------------------- | ---------------------------------------------------------------- |
| `FACE` **面输出**（默认）    | 原料从「原料输入面」推给相邻机器；产物 / 中间产物从对应面收回                                 |
| `BUS` **总线输出（延长型输出）** | 整体忽略逐面配置，本机不再做任何面 I/O；改由相邻 RS **输出总线**把网络里的「输入原料 / 中间产物」推给它面对的机器 |

### 4.2 面输出模式（FACE）下的四档面配置

参考 Mekanism 的面配置交互，执行仓六个面各有 4 档 `FaceMode`：

| 模式                     | 语义                                          |
| ---------------------- | ------------------------------------------- |
| `NONE`                 | 该面不参与自动交互，也不对外暴露任何物流能力                      |
| `INPUT`（原料输入）          | 本仓把认领到的原料 / 过渡件从该面**推给**相邻机器                |
| `OUTPUT`（产物输出）         | 本仓从该面相邻容器**抽出产物**存入内部存储；只在该面对外暴露「只出不进」的物流能力 |
| `INTERMEDIATE`（中间产物输出） | 本仓从该面相邻容器抽出**过渡件**，直接回写 RS 网络供下一个执行仓认领      |

**默认值**：机器朝向面 = `INPUT`（与旧行为一致）。

### 4.3 面输出模式下的产物发放

1. **起步原料**：执行仓从 RS 网络拉起步原料 → 存入内部存储 → 从 `INPUT` 面推给相邻机器
2. **各步投入物**：执行仓从网络拉该步所需投入物（齿轮 / 大齿轮 / 铁粒等）→ 从 `INPUT` 面推给机器
3. **中间产物（过渡件）回收**：机器加工完一步后，过渡件留在机器上 → 执行仓从 `INTERMEDIATE` 面抽出 → **直接回写 RS 网络**（供下一个执行仓认领，不留在舱内）
4. **成品回收**：最后一步加工完成后，成品从机器 → 执行仓 `OUTPUT` 面抽出 → 存入内部存储 → 由回流总线或管道送回网络

### 4.4 总线输出模式（BUS）下的产物发放

总线输出模式下，执行仓 `tickEngine` 在节流判定后直接返回，本机完全不动手。原料与产物全部交给输出总线 / 回流总线。

**输出总线 ↔ 执行仓的连接判定**：

- 从**输出总线方块实体**出发做逐格 BFS

- 可穿行格：**只有 RS 线缆方块** `CableBlock`（机器不可穿行）

- 终点：碰到 `SequenceExecutionChamberBlockEntity` 且 `isBusOutput()` 为真

- 步数上限：**64**（`RSCC_LINK_MAX_STEPS`）

- 六向相邻 = 「0 步线缆」的特例

- 结果缓存 20 tick

**输出总线的导出类别**：

- 输入原料 = 执行仓各单元样板的 `input()`

- 中间产物 = 执行仓各单元样板所属配方的 `getTransitionalItem()`

- 由 `SetExporterExecutorCategoriesPacket` C2S 写入，服务端写 NBT `rscc_export_categories`

**多选与共享均分**：

- 一台输出总线可同时选中任意多个类别

- 同一类别可被 N 台输出总线同时选中（**取消了「被占用就禁用」**）

- 共享顺序 = 输出总线坐标升序

- 轮询：轮次 = `gameTime % N`，只有轮到的那一台在当帧导出清单里含该类别

- 粒度：按「输出总线的一次导出批次」（RS 默认每 tick 每个过滤项取 1 件，装堆叠升级后一批可达 64）

**「存在输入性产物」的判定**（`hasInputCategory`）：

- 遍历本仓全部单元样板，命中任一即算：

  1. 该单元样板 `requiresInput()` 为真，或 `input()` 非空；或
  2. 样板带配方 id 时回查 Create 配方数据，取该步 `ProcessingRecipe` 的 `stepInput`（跳过下标 0），非空即算

- **冲压没有输入性产物**（`pressing` 只有 1 个 ingredient = 过渡件）⇒ 该输出总线只显示「输出中间产物」按钮

### 4.5 输出总线的独占与归一化（旧版，已被共享模式取代）

> 历史注记：第五轮设计了「独占绑定 + >2 台禁用」，第六轮改为「多选共享均分」。
> 以下字段已删除：`exporterFilters` / `busVisibleMask` / `busEnabledMask` / `claimBusCategories`。

### 4.6 推送侧的三道安全闸门

| 闸门                        | 代码落点                                                   | 治的是什么                       |
| ------------------------- | ------------------------------------------------------ | --------------------------- |
| **同一工位同时最多一份未消耗投入物**      | `wantingTargetCount` + `transferItem#holdsSameItem`    | 多开件（两个置物台各一件）               |
| **工位上还有在制件 ⇒ 不开新件（起步原料）** | `isStartIngredient(item) && unitInFlightAt(targetPos)` | 起步原料被反复推                    |
| **推不动就不退避**               | `pushStalledOnDestination` + 3 秒窗口                     | `machine_full` 后下一 tick 继续推 |

***

## 五、中间产物（过渡件）的特殊处理

### 5.1 过渡件为什么不能当普通物品

三条配方的过渡件都是「同一个物品跨多步流转」，步序只存在于 `SEQUENCED_ASSEMBLY` 数据组件。因此：

- **起步原料在任务运行期间绝不被输入总线收回**（任务结束的那一次边沿例外）

- **半成品（过渡件）绝不能被任务计为交付 / 被边沿收进网络**

- 任何「把过渡件当普通物品匹配」的代码都会把不同步的同一物品混淆

### 5.2 过渡件的收回路径

1. 面输出模式：从 `INTERMEDIATE` 面抽出 → 直接回写 RS 网络
2. 总线输出模式：由输出总线从机器抽出 → 写入网络
3. 任务结束边沿：`task_finished_residual` 回收残留（但**不回收过渡件**，过渡件必须按正常路径走完）

### 5.3 起步原料的保护

`RsccChamberImportStrategy#autoAcceptsItem`：

```java
if (chamber.isStartIngredient(stack.getItem()) && !residualEdge) return false;
```

起步原料在任务运行期间绝不被输入总线收回，只有任务结束的边沿例外。

***

## 六、总线类别模型详解

### 6.1 类别 id 格式

| 类别         | id 格式                              | 示例                                               |
| ---------- | ---------------------------------- | ------------------------------------------------ |
| 输入性产物（物品）  | `input:` + 物品注册名                   | `input:minecraft:iron_nugget`                    |
| 流体输入       | `fluid:` + 流体注册名                   | `fluid:minecraft:lava`                           |
| 中间产物（带步序）  | `intermediate:` + 配方 id + `:` + 步序 | `intermediate:create:sequenced_assembly/track:0` |
| 中间产物（步序未知） | `intermediate`                     | `intermediate`                                   |
| 成品         | `result:` + 物品注册名                  | `result:create:sturdy_sheet`                     |
| 废料         | `scrap:` + 物品注册名                   | `scrap:create:cogwheel`                          |

### 6.2 中间产物步序解析

类别 id 现在可能带**配方**（`intermediate:create:track:2`），而配方 id 自身含一个冒号，因此步序只能从**最后**一个冒号之后解析：

```java
public int intermediateStep() {
    final int last = id.lastIndexOf(':');
    return Integer.parseInt(id.substring(last + 1));
}
```

### 6.3 类别归属表

执行仓上维护 `Map<String, List<BlockPos>> busCategoryOwners`（类别 id → 有序输出总线坐标），NBT 键 `BusCategoryOwners`。

**归一化时机**：

1. 每 20 tick（定期自愈）
2. 打开输出总线界面后每 tick
3. 客户端发来类别选择包
4. 执行仓切换输出模式
5. 读档

**归一三条规则**：

1. 类别必须在本仓类别表里
2. 选择方必须「与本仓相邻或线缆相连、且自身解析结果指向本仓」
3. 多台选择者一律保留（共享），坐标升序

### 6.4 自动模式

总线配置有「自动模式」开关：

- 开启时：类别列表灰显（只读），类别由服务端自动选择，「确定/清空」按钮禁用

- 关闭时：玩家手动勾选类别

***

## 七、单一事实源与单向数据流

### 7.1 核心思想

> **把「这一件现在是第几步、下一步该谁做」变成一个函数，全局只有它一个答案。**

```
StepKey   = (配方 id, 步序 step)
StepState = 由「在制件 / 过渡件的实际 step」推导，而不是由「物品」推导
```

### 7.2 唯一判定函数 `resolve`

```
resolve(候选物品, 工位) → {
    NONE            // 不是本模组的料
    START(配方)     // 起步原料（要开新件）
    IN_STEP(配方, 步)   // 正处在第 步，等它的处理器
    DONE(配方, 产物)    // 已完成（成品 / 废料）
}
```

规则：

1. 起步原料（`ingredient` 的候选）⇒ `START(配方)`。一件物品可能同时是 A 配方的起步原料、B 配方的投入物 ⇒ `resolve` 必须带「配方上下文」参数；没有上下文时返回 `AMBIGUOUS`
2. 带 `SEQUENCED_ASSEMBLY` 组件 ⇒ `IN_STEP(组件.id, 组件.step)`
3. 无组件但物品名 = 某配方的过渡件名 ⇒ `AMBIGUOUS`。只在「该配方此刻确实有活跃订单」且「该配方的下一个未被满足的步属于本工位」时，才当作 `IN_STEP(配方, 下一步)`
4. 否则 ⇒ `NONE`

### 7.3 单向数据流

```
RS 订单（用户下单 N 件产物 X）
    │ ① 计划：按配方展开成「步计划」
    ▼
步计划 (配方, 步) → 负责工位   ← 由总样板的 machinePos 决定，唯一
    │ ② 每个 (配方, 步) 有且只有一个「在制件名额」
    ▼
在制件 (配方, 步, 位置, 工位)   ← 全局登记表（本模组自己维护）
    │ ③ 只有「拥有该步」的总线能把料推给它
    ▼
推料：严格一次一件、拒收退避
    │ ④ 推上去后登记「留驻到 t+RESIDENCE」
    ▼
收回：留驻期内绝不收回
    │ ⑤ 步完成后推进 step，交给下一步的工位
    ▼
回到 ③（下一步）
```

**每一环的硬约束**：

| 环 | 约束                       | 违反后果            |
| - | ------------------------ | --------------- |
| ① | 一个订单展开出的 `(配方, 步)` 是有限集合 | 无限产（用户实测「一直按发」） |
| ② | 每个 `(配方, 步)` 同时最多 1 件在制  | 多开件（两个置物台各一件）   |
| ③ | 判定只认「我拥有这一步」             | 把料推给错的机器        |
| ④ | 推成功后留驻 `RESIDENCE_TICKS` | 加工做不完（坚固板一件不出）  |
| ⑤ | 完成后必须推进到下一步并交接           | 卡在中间步           |

***

## 八、守恒台账（FlowLedger）

### 8.1 守恒不变量

```
离开网络 + 世界收集 − 进入网络 = 留存 + 销毁
留存 = 舱内存量增量 + 推给机器 − 从机器取回

⇒ 未对平 = 留存 + 销毁 − (fromNetwork + fromWorld − toNetwork)
```

理想情况下 `未对平 == 0`：从网络 / 世界取走的东西，要么还在舱里 / 机器上，要么（任务收尾时）又还回了网络，要么被有意销毁。

### 8.2 六类记账字段

| 字段            | 含义                                        |
| ------------- | ----------------------------------------- |
| `fromNetwork` | 本仓从 RS 网络取出（离开网络）                         |
| `toNetwork`   | 本仓放入 RS 网络（进入网络）                          |
| `toMachine`   | 本仓推给机器 / 置物台                              |
| `fromMachine` | 本仓从机器 / 置物台取回                             |
| `fromWorld`   | 世界收集（掉落物 / 流体源方块 / 经验球）—— 执行舱恒 0，归流缓存仓用   |
| `destroyed`   | 有意销毁（缓存区清空 / 匹配槽「直接销毁」）—— 与「留存」同侧，绝不伪装成回流 |

### 8.3 为什么「未对平」只作诊断、不做硬崩溃

账本只挂在本模组自己的四个执行点（备料 / 推料 / 收集 / 收尾回流）上。世界里有本模组之外的搬运（玩家手动、别的模组、集群拆分 / 区块卸载 / 读档基线）同样会改变舱内存量，它们不该被误报成「本模组漏计」。因此把差额如实列出并标注可能的来源（`external_or_baseline`），供 `/rs_create_compat diag` 与日志取证；**任何判定都不读它**。

### 8.4 日志输出

`[rscc-ledger]` 对平时**零输出**，失衡时：

- 首次失衡：`unbalanced (first) peak=… on … :: item:…=3`

- 连续 ≥3 次采样：转 `WARN unbalanced (sustained 3 samples)`

- 之后每 30 秒：合并一条 `unbalanced (same imbalance repeated n times, peak=…)`

- 恢复时：`balanced again (was N sample(s) of imbalance, peak=…)`

***

## 九、装配看门狗（AssemblyWatchdog）

### 9.1 职责

- 观察自动合成任务是否卡住（停滞 / 掉线 / 缺料 / 无进展）

- 到达阈值后挂起任务 + 弹横幅

- 提供手动挂起 / 恢复入口

### 9.2 卡住原因分类

| 原因                 | 含义                                       |
| ------------------ | ---------------------------------------- |
| `EXECUTOR_OFFLINE` | 执行器掉线（指派的执行仓不在网络里）**或**目的地持续拒收 / 该步无机器认领 |
| `MISSING_MATERIAL` | 缺料（且不可自动合成、无在跑子任务）                       |
| `NO_PROGRESS`      | 无进展                                      |
| `NONE`             | 正常                                       |

**EXECUTOR\_OFFLINE 的两种子情形**（横幅文案不同）：

- **真掉线**：`offlineSteps` 非空 → 显示「第 N 步的执行器掉线」

- **输出阻塞**：`offlineSteps` 为空但 `pushStalled=true` → 显示「目的地持续拒收/已满，请检查下游机器」

### 9.3 挂起 / 恢复状态机

- 掉线、缺料、无进展按各自阈值进入 `SUSPENDED`

- 挂起后冻结任务不再抽料投料

- **恢复唯一入口是玩家点「继续」**（绝不自动恢复）

- 挂起时把任务内部暂存送回网络，恢复时再还原

### 9.4 `pushStalled` 的判定

`anyChamberPushStalled(chambers)` 检查**本任务实际用到的执行仓**（每步的 `machinePos` + `executorPos`），而不是网络里所有仓。

> **历史教训**：旧实现检查网络里所有仓，导致一台仓堵了、所有任务都报「设备掉线」。已修复为只检查本任务相关仓。

***

## 十、单元样板查重（UnitPatternDedupe）

### 10.1 判据：「同一条配方的同一个步骤」

两张单元样板语义完全相同需要同时满足：

1. **操作类型相同**（`RecipeType` 优先，回退 `Machine`）
2. **服务配方相同**（`Recipe` 字段，大小写不敏感）
3. **步序相同**（`Step` 字段）
4. **是否需要输入原料相同**（`RequiresInput`）
5. **输入式原料完全相同**（物品 + 数据组件，不比数量）
6. **输入流体完全相同**（种类 + 数据组件 + 数量）

### 10.2 老样板兼容

老样板缺 `Recipe` / `Step` 字段时**一律不判重**（宁可多生成一张，也绝不漏生成 —— 漏生成会让整条排线绑定不上、下单毫无反应）。

### 10.3 为什么必须纳入「配方 + 步序」

没有输入式原料的操作（冲压 / 切割）本来就长得一模一样。旧口径只比「操作类型 + 输入物」，导致「精密构件的冲压步」与「列车轨道的冲压步」被判成同一张，整条列车轨道排线的单元样板被全部跳过 → 执行仓的 `chamberSteps` 只剩另一条配方的步 → 该步 `stepOwner=NOBODY` → 拉料被拒 → **下单 1 个或 64 个都毫无反应**。

***

## 十一、产物概率显示（与 Create JEI 完全一致）

### 11.1 Create 的算法

| 内容        | 出处                                                                                   |
| --------- | ------------------------------------------------------------------------------------ |
| 权重 → 概率   | `getOutputChance() = resultPool.getFirst().getChance() / Σ resultPool[].getChance()` |
| 轮盘抽取      | `rollResult()`：`totalWeight = Σ weight; number = random * totalWeight; ...`          |
| 概率 → 显示数字 | `chanceComponent(float)`                                                             |
| 是否显示概率    | `noRandomOutput = recipe.getOutputChance() == 1` → 必得时不显示百分比                         |

### 11.2 本模组的换算与格式化

```
概率 = 该项权重 / 全部非空项权重之和
显示数字 = chance < 0.01 ? "<1" : chance > 0.99 ? ">99" : String.valueOf(Math.round(chance * 100))
显示文案 = Component.translatable("create.recipe.processing.chance", 显示数字).withStyle(GOLD)
```

- **主产物** = 池中第一个非空项（与 Create `getResultItem()` = `resultPool.getFirst()` 同口径）

- **废料** = 池中其余项

- **必得产物不显示百分比**（`chance >= 1` 时 tooltip 不追加概率行）

### 11.3 示例（`create:sequenced_assembly/precision_mechanism`）

池权重 `[120, 8, 8, 5, 3, 2, 2]`（Σ=148）：

- 主产物：**81%**（120/148）

- 废料：5%、5%、3%、2%、1%、1%

***

## 十二、挂起 / 取消时的资源回流

### 12.1 挂起

`AssemblyWatchdog.suspend()`：

1. 任务状态 → `SUSPENDED`，原因标记 = `MANUAL` 或自动判定
2. 执行仓 `flushChamberForSuspend()`：把舱内正在加工的物料送回网络
3. 600 tick 内不再被 step（释放占用、不阻塞同网络的后续任务）
4. **绝不自动恢复**（只有玩家点「继续」）

### 12.2 恢复

`AssemblyWatchdog.resume()`：

1. 任务状态 → `RUNNING`
2. 重新从网络拉取所需原料
3. 继续执行

### 12.3 取消

取消任务时：

1. 舱内残留物料送回网络（`task_finished_residual`）
2. **起步原料在边沿回收一次**
3. 过渡件按正常路径走完（不强行销毁）

***

## 十三、调试与取证

### 13.1 日志位置

| 文件                    | 内容                                                                                              |
| --------------------- | ----------------------------------------------------------------------------------------------- |
| `run/logs/latest.log` | INFO 级：`[rscc-build]`、`[rscc]`、`[rscc-assembly]`、`[rscc-trace]`、`[rscc-ledger]`、`[rscc-dedupe]` |
| `run/logs/debug.log`  | DEBUG 级：**含 RS 任务行**（`Created task …` / `Task … state changed …`）                               |

### 13.2 日志前缀

| 前缀                | 谁的                  | 回答什么                                                         |
| ----------------- | ------------------- | ------------------------------------------------------------ |
| `[rscc-build]`    | `RsccBuildInfo`     | 这份日志是哪一版代码跑出来的（revision / 编译时间）                              |
| `[rscc]`          | `RsccDiag`          | 会话起点、缺料档位、补发档位的默认值                                           |
| `[rscc-assembly]` | `RsccAssemblyDebug` | 序列装配事件流（机器 @ 坐标 \| 步骤 / 类别 \| 资源 \| 数量 \| 结果 / 原因）           |
| `[rscc-trace]`    | `RsccAssemblyDebug` | 端到端追踪（`item/amount \| from \| event \| to \| reason \| net`） |
| `[rscc-ledger]`   | `RsccFlowLedger`    | 守恒账本（对平零输出，失衡才告警）                                            |
| `[rscc-dedupe]`   | `UnitPatternDedupe` | 查重判定（每一步为什么被跳过 / 为什么不跳过）                                     |

### 13.3 一键自检

```powershell
# ① 编译
powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1

# ② 先编译、后开游戏 → 跑产线

# ③ 日志能不能作证
python tools\verify_build_stamp.py

# ④ 一键判定
python tools\verify_single_unit_supply.py
```

### 13.4 堵塞实验协议

6 个段落，每段制造一种已知失败模式，详见 `docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md`：

| 段 | 操作                        | 制造什么   |
| - | ------------------------- | ------ |
| A | 下单精密构件 x1                 | 正常基线   |
| B | 下单精密构件 x64                | 长跑稳态   |
| C | 挂起 + 恢复                   | 挂起冻结   |
| D | 下单精密构件 x1（装配失败留废料）        | 齿轮堵塞本体 |
| E | 切 `shortagemode wait` 后下单 | 等待档对照  |
| F | 下单列车轨道 x1                 | 候选组回归  |

***

## 十四、已知问题与待办

### 14.1 已修复的关键问题

| 问题           | 根因                                                        | 修复                                   |
| ------------ | --------------------------------------------------------- | ------------------------------------ |
| 列车轨道下单毫无反应   | `UnitPatternDedupe.sameSemantics` 只比操作类型+输入物，不同配方的冲压步被判重复 | 纳入「配方+步序」判重                          |
| 没下单也自动开工     | `patternAssignedSteps` 被并入 `computeOwnedSteps`（所有权集）      | 只补充显示类别，不进入所有权                       |
| 齿轮一直堵塞       | 收回侧无留驻期、推送侧无拒收退避、份额闸门过宽                                   | 留驻期 + 退避 + 在制名额闸门                    |
| 总线配置假阳性      | 内部复合键 vs 玩家选的类别 id 不一致                                    | 统一用 `RsccBusCategory.intermediateId` |
| 插件槽压缩到同一格    | `SimpleContainer#createTag` 不写 `Slot` 字段                  | `RsccSlotNbt` 逐槽写 `Slot` byte        |
| 所有任务误报「设备掉线」 | `anyChamberPushStalled` 检查网络所有仓                           | 只检查本任务相关仓；区分「输出阻塞」文案                 |
| 蓝图加农炮材料不回收   | `recycleCannonOutput` 只回收空白蓝图                             | 新增 `recycleLeftoverMaterials`        |

### 14.2 待验证项

- 总线输出模式下的均分效果（装堆叠升级后的「按批」粒度）

- BFS 连接在线缆分叉到两台仓时的归属

- `enabledMask` 依赖菜单打开（长时间不打开界面且拓扑变化可能短暂出现旧类别）

### 14.3 待办

- `BusCategoryConfigScreen` 类别详细配置的输入框（用户尚未明确输入框用途）

- 候选组（铁粒/锌粒、多种台阶）在所有界面的轮播显示一致性

***

## 十五、关键设计决策速查

| 决策             | 说明                                                                    |
| -------------- | --------------------------------------------------------------------- |
| 查重口径           | 同配方同步骤才算重复                                                            |
| 守恒不变式          | `留存 + 销毁 = 从网络 + 从世界 − 进入网络`                                          |
| 齿轮堵塞判定         | W1-W5 等待豁免 + 200 tick 粘住（`STATION_STUCK_REARM_TICKS`）                 |
| 补合成请求量         | `目标 − 网络 − 本仓 − 机器侧 − 在途`                                             |
| 概率配方           | 分批递进 + 40 tick 冷却                                                     |
| 输出总线流向         | 先搬进执行仓内部存储 → 再从执行仓输出至目标机器                                             |
| 挂起不自动恢复        | 只有玩家点「继续」才恢复                                                          |
| 集群主控唯一载荷       | 集群里只有主控仓持有真实库存                                                        |
| 起步原料保护         | 任务运行期间绝不被输入总线收回                                                       |
| 推不动就不拉         | `pushStalledOnDestination` 时备料侧停手                                     |
| 单元样板不绑定机器      | 流程编排才绑定机器                                                             |
| 总样板槽只出不进       | 生成的总样板留在生成槽，玩家自取                                                      |
| tooltip 分层     | Shift=用法 / Ctrl=数值 / Alt=内部 id                                        |
| BusCategory id | `input:` / `fluid:` / `intermediate:<配方>:<步序>` / `result:` / `scrap:` |
| 执行仓只出不进        | 对外能力包装为 `ExtractOnlyHandlers`                                         |

***

## 十六、参考文档索引

| 文档          | 路径                                           | 内容                      |
| ----------- | -------------------------------------------- | ----------------------- |
| 序列装配链路重建    | `docs/SEQUENCE_CHAIN_REDESIGN.md`            | 三条配方事实、共性问题、单一事实源、单向数据流 |
| 齿轮堵塞自检判据    | `docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md`       | 供料不变式、推/收闸门、守恒判据        |
| 日志取证指南      | `docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md`        | 日志位置、判定口径、自检命令          |
| 堵塞实验协议      | `docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md`       | 6 段实验操作 + 取证命令          |
| Round4 设计决策 | `docs/DESIGN_DECISIONS_ROUND4.md`            | 面配置、总线输出、类别模型、共享均分      |
| 日志取证盲区修复    | `docs/ROUND_LOG_INSTRUMENTATION_20261004.md` | 构建指纹、ledger 日志、查重日志     |
| 项目技术交接      | `TECHNICAL_HANDOFF.md`                       | 全项目概览                   |

