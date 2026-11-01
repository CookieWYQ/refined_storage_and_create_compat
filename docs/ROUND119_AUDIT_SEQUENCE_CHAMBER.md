# 第 119 轮：序列装配家族**只读对抗式复核**（执行仓 / 样板终端 / 单元样板管理舱 / 样板库）

> 复核员：只读反对派（**未修改任何源文件、未跑 gradle、未做 git 操作**；本文件是唯一写入）。
> 基线：工作树当前状态（`build_info` 口径 `1.2.0-beta.18` 之后的未提交修改）。
> 目标家族：`block/entity/` 下 `SequenceExecutionChamberBlockEntity`（18165 行）、
> `SequencePatternTerminalBlockEntity`（3177 行）、`UnitPatternManagerBlockEntity`（193 行）、
> `SequenceAssemblyExecutorBlockEntity`（312 行）；配套读了 `menu/`、`network/`、`support/`
> （`UnitManagerSources` / `RsccMachineCluster` / `RsccChamberItemStorage` / `RsccChamberExportStrategy` /
> `BlockContentReleaser`）以及 **RS 2.0.9 真实源码**（`local_src/rs_src/...`）用于核对语义。
>
> **诚实前提**：以下全部是**读代码 + 读 RS 源码**得出的结论，**没有一条在实机 / 专服上复现过**。
> 凡涉及"客户端看到什么""点击落到哪一格"的地方都标了「实机未验证」。置信度写的是
> 「机制是否成立」，不是「玩家多久撞一次」。

---

## 0. 结论摘要

| 编号 | 严重度 | 一句话 | 类别 | 置信度 |
| --- | --- | --- | --- | --- |
| **F1** | **高** | 单元样板可堆叠（`stacksTo(64)`），而「取回 / 存入」两个按钮按"格"搬运：一叠 N 张进库后，**存入只放回 1 张、其余 N−1 张被静默销毁** | 丢物品 | 机制高 |
| **F2** | **中** | 外部样板 sink 的 `SIMULATE` 逐条判、`EXECUTE` 逐条插：**一批里有 ≥2 种资源且网络剩余空间装不下整批时，后半批被 RS 当作成功而销毁**；且该方法的注释写着"让任务稍后再试"，RS 实际**不会重试** | 丢物品 + 注释不符 | 机制高，触发中 |
| **F3** | **中** | 汇总界面的「滑动窗口」每 20 tick 自动前移一格映射，只在"手里拿着东西"时暂停 ⇒ **看着 A 点到 B**；轮转与点击同一 tick 到达时客户端预测必被服务器纠正 | 界面/错操作 | 高（实机未验证） |
| **F4** | **中** | 在制件登记表 `inFlightUnits` 不落盘（代码自认），重启后工位上那份残留**永久失去回收凭据**，只能手动清 | 状态机/永久卡住 | 高（代码 + 自认注释） |
| **F5** | **低** | `SetStepMachinePacket` 的 `name` 由客户端给、**无长度上限**直接落盘（与第 116 轮"客户端字符串都已上尺"的结论矛盾） | 改包可达 | 高 |
| **F6** | **低** | 注释与实现不符：执行仓"手持样板右键放入 / 空手右键取出"**已不存在**（`putUnit` / `takeUnit` 是死代码）；"容量 6""3×2 单元样板槽"两处注释也是错的 | 注释不符 | 高 |
| **F7** | **低** | `dropOutputContents()` 自称是方块破坏时的结算入口，**全仓无人调用**；真正入口是 `BlockContentReleaser.release` | 注释不符 + 僵尸代码 | 高 |
| **F8** | **低** | 终端 `saveAdditional` 把 `InputCandidates` **连写两遍**（同一调用重复），纯冗余 | 卫生 | 高 |

**没有发现**：跨机器共享存储的"写多份 → 读档翻倍"（`RsccMachineCluster` 的落盘边界是对的）、
`applyFaceModes` / `applyOutputMode` / 页面下标 / 结果概率等 C2S 字段的越界信任
（都被夹住了，第 116/117 轮那批是对的）、执行仓 `unitSlots` 的 `Math.max(0,·)` 覆盖问题
（本轮范围内未再找到第二例）。

---

## F1（高）单元样板堆叠 × 「取回 / 存入」= 静默销毁 N−1 张样板

### 位置

- `src/main/java/cretae/cookiewyq/rs_create_compat/RS_Create_Compat.java:177-180`
  —— 单元样板注册为 **`stacksTo(64)`**（第 180 行；对比总样板 `stacksTo(1)`，第 184 行）。
- `block/entity/SequenceExecutionChamberBlockEntity.java:149-150`
  —— 执行仓单元槽 = `new SimpleContainer(UNIT_SLOTS)`，**没有覆写 `getMaxStackSize()` ⇒ 每格 64**。
- `menu/SequenceExecutionChamberMenu.java:159-188`
  —— `ChamberUnitSlot` 只覆写 `mayPlace`（判物品类型），**不限制数量**；`quickMoveStack`（第 131-134 行）
  用 `moveItemStackTo(..., UNIT_START, PLAYER_START, false)`，而 `Slot#getMaxStackSize()` 取容器上限 64
  ⇒ **Shift 点一下就会把一整叠（最多 64 张）塞进同一格**。
- `block/entity/SequencePatternTerminalBlockEntity.java:1303-1337`（`pullUnitsFrom`，取回）
  - 第 1325 行 `unitLibrary.trySetStackInSlot(empty, stack.copy())` —— **整叠原样写进库的一个格**；
  - 第 1329 行 `chamber.unitSlots.setItem(i, ItemStack.EMPTY)` —— 整格清空。
- `block/entity/SequencePatternTerminalBlockEntity.java:1345-1369`（`pushUnitsTo`，存入）
  - 第 1360 行 `chamber.unitSlots.setItem(empty, stack.copyWithCount(1))` —— **只搬 1 张**；
  - 第 1361 行 `unitLibrary.setStackInSlot(i, ItemStack.EMPTY)` —— **整格清空**（其余 N−1 张就此消失）。
- `block/entity/SequencePatternTerminalBlockEntity.java:2936-2975`（`GrowingUnitLibrary.trySetStackInSlot`）
  —— 写入时**原样保留 `count`**，没有"一格一张"的归一化。
- 附带口径问题：`SequencePatternTerminalBlockEntity.java:627`（`unitLibraryEntries`）、`:1222-1240`
  （`listChamberUnits`）都是**按格计数**，一叠 N 张会显示成"1 张"。

### 触发步骤（可照做）

1. 在终端里对同一步骤生成 **两张内容完全相同的单元样板**：把该步的「跳过重复」关掉，
   点「生成样板」→ 取走总样板 → 再生成一次（或两条同类产线各生成一张）。
   两张样板 `CustomData` 完全相同 ⇒ 在背包里**自动叠成一叠 `count=2`**。
2. 对着执行仓 Shift+左键，把这一叠塞进某个单元槽（普通左键也行，会整叠放下）——
   这一格现在是 `count=2`。
3. 打开序列样板终端 → 「执行仓单元样板汇总」→ 选中这台仓 → 点 **「取回」**：
   两张进了终端单元样板库的**同一格**（`count=2`）。
4. 再点 **「存入」**：执行仓只回来 **1 张**，库里那一格被清空 ⇒ **另 1 张（N−1 张）凭空消失**。

> 更省事的一种：直接对"已经 `count=2` 的执行仓格"点「取回」，然后随便再点一次「存入」。

### 实际后果

- **销毁玩家物品**（单元样板本身记录了配方类型 / 步序 / 输入标记，是玩家手工配置的产物），
  且**没有任何提示**：日志只有 `WARN` 级别的库容量提示（与本路径无关），界面数量还按格显示，
  玩家只会看到"我明明取了 2 张，存回去只剩 1 张"。
- 与第 116 轮立的规矩（"库满了也不许销毁，原样留在原处"）**直接打架**：这里不是"满了拒绝"，
  而是"没满也照丢"。

### 建议修法（**不需要改产品行为**）

1. `pullUnitsFrom`：按张搬运 —— 对 `count>1` 的格循环 `copyWithCount(1)` 写不同空位
   （或先把整叠 `split` 成单张再逐张入库），保持"库内一格一张"不变量。
2. `pushUnitsTo`：不要用 `setStackInSlot(i, EMPTY)` 抹掉整格 —— 按 `count` 逐张搬，
   搬完多少张就 `shrink` 多少张（只搬 1 张时 `shrink(1)`）。
3. 兜底：在 `GrowingUnitLibrary.trySetStackInSlot` 里把 `count>1` 的入参 `copyWithCount(1)`
   并把余量退回调用方（需要改返回值 / 加一个 `resize` 语义），或者直接**拒绝** `count>1` 的写入
   并让调用方逐张写。前者更安全。
4. 顺带把 `listChamberUnits` / `unitLibraryEntries` 的张数口径改成"件数"（或在界面明确写"格"）。

---

## F2（中）外部样板 sink：一批多资源时 `SIMULATE` 会全部通过，`EXECUTE` 后半批被销毁

### 位置

- `block/entity/SequenceAssemblyExecutorBlockEntity.java:157-184`
  ```java
  for (final ResourceAmount amount : resources) {
      final long count = Math.max(1, amount.amount());
      final long inserted = storage.insert(resource, count, action, Actor.EMPTY);
      if (inserted < count) {
          return ExternalPatternSink.Result.REJECTED;   // ← 已经插进去的前半批不回滚
      }
      any = true;
  }
  ```
  javadoc 写的是「**网络放不下全部则让任务稍后再试**」。
- RS 侧真实语义（**这是本轮唯一必须读 RS 源码才能判的**）：
  `local_src/rs_src/com/refinedmods/refinedstorage/api/autocrafting/task/ExternalTaskPattern.java:184-215`
  - 第 224-228 行：对 `sinks` 逐个 `accept(..., SIMULATE)`，**每条资源都拿同一份未变的网络状态**去模拟；
  - 第 199-213 行：`extractAll(iterationInputs, internalStorage, Action.EXECUTE)` 先把输入从任务内部暂存**扣掉**，
    然后 `sink.accept(..., EXECUTE)`，**返回值只打一条 `LOGGER.warn`**，注释原文：
    *"The end result is that we lie, do as if the insertion was successful, and potentially void the
    extracted resources from the internal storage."*
  - 也就是说：**`REJECTED` 不会让任务重试，未插入的那部分被 RS 自己吞掉**。

### 为什么会真的发生（关键推理）

`SIMULATE` 不改变网络状态，所以**同一批里的每条资源各自模拟时都看到"还有空间"**：
网络只剩 64 件的空间、一批是「64 个物品 + 500 mB 流体」⇒ 两次模拟**都通过** ⇒ sink 被判为可用；
真正 `EXECUTE` 时第一条把空间吃满，第二条 `inserted=0 < count` ⇒ 返回 `REJECTED` ⇒
RS 打一条 warn 当作成功 ⇒ **后半批（那条流体 / 那批物品）凭空消失**。

本模组的样板确实会登记两类资源（sink 自己的注释就写着
"`PatternBuilder` 现在**同时登记物品与流体** ingredient"），所以"一批 ≥2 种资源"是常态。

### 触发步骤

1. 让一台执行仓产线跑起来（有订单、有总样板）。
2. 把 RS 网络的**剩余空间压到很小**：所有磁盘几乎写满 / 只留一个几乎满的存储块，
   剩余空间小于"一次迭代的输入总量"。
3. 等一次迭代被送进 sink：日志出现
   `Sink ... did not accept all inputs for pattern ...`（RS 的 warn）。
4. 任务仍然显示"已送出"，但网络里**只多出前半批**，后半批（通常是流体）消失。

### 实际后果

- 网络接近满时，**一次迭代的输入后半批被销毁**（不重试、不进网络、不掉落）。
- 注释与实现**互相矛盾**（"稍后再试" vs 实际销毁），是本轮最容易被主人反感的那一类。

### 建议修法

- **最小修改**：把注释改成 RS 的真实语义；并把 `accept` 改成"**整批原子**"——
  先在本方法内部对每条资源做一次 `SIMULATE` 并把**累计占用**算进去（同批内维护一个
  `Map<ResourceKey, Long> reserved`，每条模拟前把已模拟量当成"已被占用"从可用量里扣；
  物品/流体都用 `storage.get(resource)` 做减量），任何一条不过就**一条都不插**并返回 `REJECTED`。
  这样至少不会出现"插了一半再失败"的情形（剩下的纯"空间在 SIMULATE 与 EXECUTE 之间被别的机器抢走"
  属于极小窗口）。
- **真正的修法（改产品行为，需明说）**：执行仓/样板库内建一个**有限缓冲**（例如 9 格 + 1 罐），
  `accept(EXECUTE)` 只写缓冲（永远成功），由 tick 慢慢转进网络；这样"网络满"只会让缓冲堆起来，
  绝不会让 RS 认为输入已被消费而实际丢掉。代价是要给样板库加"缓冲内容"的持久化与破坏掉落
  （`BlockContentReleaser` 已有现成的 `collectItems` 钩子）。
- 顺手：`Math.max(1, amount.amount())` 在 `amount == 0` 时会**凭空插入 1 件**
  （模拟阶段通过、执行阶段真的插进去）——正常配方不会产生 0 数量，但既然写在这里就该改成
  `if (amount <= 0) continue;`。

---

## F3（中）汇总界面的滑动窗口 20 tick 自动前移 ⇒「看着 A 点到 B」

### 位置

- `menu/ChamberUnitsSummaryMenu.java:283-298`（`broadcastChanges`：`pollCounter >= POLL_TICKS(20)` 时
  `rowStart[row]++`，**只有 `getCarried().isEmpty()` 才暂停**）
- `:228-237`（`mapToUnitSlot`：窗口格 → 真实槽位由 `rowStart` 现算）
- `:377-384` / `:410-434`（`WindowContainer.getItem/setItem`：每次都用**此刻**的映射）
- `:456-460`（窗口格 `getMaxStackSize() = 1`）

### 触发步骤

1. 一台执行仓里放 **6 张以上不同的**单元样板（窗口只有 4 格，需要轮转才看得全）。
2. 打开「执行仓单元样板汇总」，盯住某一行第 2 格的样板（例如"机械手"那张）。
3. 等 1 秒（窗口自动前移一格），然后立刻左键点那一格。
4. 取到的是**相邻的另一张**样板；再点一次放回去时，落点又是**新的映射**位置。

### 实际后果

- **取错 / 放错样板**：玩家以为自己的单元样板"被换了 / 串位了"，尤其是"取出来又放回去"
  两次点击跨过一次轮转时，样板会落到同一台仓的**另一个槽**（不丢，但步序与槽位的对应关系变了）。
- 客户端预测与服务器结果会不一致一次（服务器下一次 `broadcastChanges` 会把光标内容纠正回真值）；
  若点击包恰好跨在轮转那一 tick 上，这一下点击在两端**指向不同样板**。
- 注释里已经写了"避免『看着 A 却点到 B』"，但**只处理了"手里拿着东西"这一种**，
  没有处理"看一秒再点"这个最常见的场景 —— 属于"注释声明了意图、实现没做到"。

### 建议修法（产品行为微调，需明说）

- 方案 A（最小）：**只在有交互时才轮转** —— `getCarried()` 非空、或玩家最近 N tick 内点过/悬停过本行
  （服务端可以在 `clicked()` 里打时间戳）就暂停轮转；
- 方案 B：**轮转后先下发再允许点击** —— 把 `rowStart` 随 S2C 下发并锁定一个 tick，
  保证"客户端看到的映射 == 服务器执行点击用的映射"；
- 方案 C（最稳，但去掉一个现有特性）：**取消自动轮转**，改成行内左右箭头 / 滚轮手动翻。
- 不论选哪个，`mapToUnitSlot` 的"取用"与"写回"应在**同一次点击内缓存一次映射**
  （目前 `setItem` 会先 `rebuildWindow()` 再返回，等于把映射换掉了）。

---

## F4（中）在制件登记表不落盘 ⇒ 重启后工位残留永久无人回收

### 位置

- `block/entity/SequenceExecutionChamberBlockEntity.java:3082-3084`（`inFlightUnits` 是普通内存 `Map`）
- 依赖它的两条回收路径：
  - `:3721-3730`（`clearDeadStationPieces`，任务结束 / 冻结后的有界清理）
  - `:3983-4046`（`reclaimRuntimeStationResidue`，运行期残留清理）
  - `:13758-13761`（`flushStationStartIngredients`，任务结束边沿的起步原料回收）
- `:3976-3979` 自己写着：**"`inFlightUnits` 是内存态（不落盘）：服务器重启后…本方法与
  `clearDeadStationPieces()` 都不会动它（保守方向：宁可不搬，绝不乱搬）。"**
- `:17467-17515`（`saveAdditional`）里确实**没有任何**在制件相关的键
  （只有 `UnitSlots` / `Facing` / 绑名 / `FaceModes` / `OutputMode` / `BusCategoryOwners` / 输出存储）。

### 触发步骤

1. 下单让产线跑到"材料已经推给机器、还没被消耗"的瞬间（置物台 / 注液机上压着一份黑曜石粉或
   一件未完成件）。
2. 停服（正常 `/stop` 即可）后重启。
3. 取消那条订单（或让 `AssemblyWatchdog` 挂起）。
4. 观察置物台：那份残留**仍在原地**，输出总线此后每次推料都 `machine_full`。

### 实际后果

- 工位被一份"没人认领"的原料永久占住 ⇒ 后续同一台机器**再也接收不到新料**（用户实测过的那类
  "部分恢复、部分永不恢复"会以此形态复发）。
- 玩家唯一的出路是手动把那一件挖走；界面上没有任何提示说明"这是本仓推过去的"。

### 建议修法（改产品行为，需明说"读档后仍会回收旧残留"）

- 把 `inFlightUnits` 写进 NBT：只存 `station`（long）、`recipe`（string）、`step`（int）、
  `item`（registry id）、`sinceTick`。条目数天然有界（= 本仓推过料的工位数，通常 1–4）。
  读档时保留，遇到坐标已失效的条目按现有 `reconcileInFlightUnits` 的规则自然淘汰。
- 不想落盘的话，至少**降级为玩家可见**：读档后对"工位上压着本仓类别内物品、且没有在制件"的工位
  做一次一次性播报（"检测到上次运行留下的残留，是否回收？"），而不是静默不管。

---

## F5（低）`SetStepMachinePacket.name` 无长度上限，直接落盘

### 位置

- `network/SetStepMachinePacket.java:36-40` —— `ByteBufCodecs.STRING_UTF8`（上限 32767 字符）
- `block/entity/SequencePatternTerminalBlockEntity.java:1403-1412`（`setStepMachine` 原样写进
  `stepMachineNames`）
- 落盘：`:2552-2566`（逐步写 `StepMachines`）；读回 `:2659-2669`

### 触发步骤

1. 打开序列样板终端。
2. 改包对任一步发 `SetStepMachinePacket(stepIndex=合法值, hasPos=true, pos=网络内真实执行仓坐标,
   name=64KB 随机串)`（`pos` 的合法性校验是真的，`name` 没有任何校验）。
3. 关界面再打开 / 看方块实体 NBT：`StepMachines[i].StepMachineName` 就是那 64KB。

### 实际后果

- 该方块实体的 NBT 随步数线性膨胀（每步最多 ~64KB），并且终端每次打开都要把这份 NBT 现算一遍
  （`sendStepMachines` 逐步骤读一次）；极端情况下是存档 / 同步膨胀。
- 显示名被伪造（服务端本可以自己取 `chamber.getChamberDisplayName()`，根本不需要客户端给）。
- 与第 116 轮的结论「客户端给的 `name` / `recipeType` 字符串 → 上限 256 字符」**口径不一致**：
  同一套界面里的另一条字符串通道漏了。

### 建议修法

- 服务端**不要信任** `name`：按 `pos` 找到那台执行仓，直接取它的 `getChamberDisplayName()`。
- 或至少 `Math.min(256, name.length())`（与 `MAX_IMPORT_STRING_LENGTH` 同源）。

---

## F6（低）注释与实现不符：执行仓"右键放入 / 取出样板"已不存在；"容量 6""3×2"也是错的

### 位置

- `block/SequenceExecutionChamberBlock.java:37-40`（类 javadoc）：
  *"手持单元样板右键放入样板槽，空手右键取出（见方块实体交互）"* ——
  但 `useWithoutItem`（`:219-230`）**无条件** `player.openMenu(...)`，从不调用这两个方法。
- `block/entity/SequenceExecutionChamberBlockEntity.java:15932-15933`（`putUnit` javadoc）
  与 `:15971`（`takeUnit` javadoc）。
- 全仓 `grep "\.putUnit(\|\.takeUnit("` —— **零调用点**（这两个方法是死代码；
  `takeUnit` 里"装不下则 `Block.popResource` 弹出到世界"的分支永远不会执行）。
- 同一批错误注释：
  - `SequenceExecutionChamberBlockEntity.java:149`："单元样板库存：只允许放本模组的单元样板，**容量 6**"
    —— 实际 `UNIT_SLOTS = 54`（`:114`），而且 `SimpleContainer` **本身没有任何类型限制**
    （"只允许放单元样板"只存在于菜单槽 / 管理舱容器这两层）。
  - `SequenceExecutionChamberBlock.java:219`："右键打开界面（**3×2** 单元样板槽）"
    —— 实际是 6×9 = 54 格（`SequenceExecutionChamberMenu.java:43-47`）。

### 实际后果

- 玩家（和下一个维护者）按注释去右键取样板 → 打开 GUI，行为本身没错，但**注释在说谎**；
- `takeUnit` 的"弹出到世界"分支若将来被接上，会与菜单路径重复处理（半接的状态最危险）。

### 建议修法

- 删掉 `putUnit` / `takeUnit`，或改成 `@Deprecated` + 注释"历史入口，现已改走 GUI，勿接"；
- 三处注释按实际值改写（54 格、6×9、类型限制只存在于菜单/容器包装层）。

---

## F7（低）`dropOutputContents()` 自称方块破坏时的结算入口，实际无人调用

### 位置

- `block/entity/SequenceExecutionChamberBlockEntity.java:11586-11647`：
  javadoc "方块被破坏时的内部存储掉落（**供方块调用**，绝不吞资源）"。
- 真实入口：`block/SequenceExecutionChamberBlock.java:255-291`（`onRemove`）→
  `BlockContentReleaser.release(...)`，其 `Host.collectItems` 用的是
  `BlockContentReleaser.collectHandler(chamber.outputStorage, out)`（`:270-273`），
  流体走 `collectFluidStorage` / `receiveFluidStorage`。
- 全仓 `grep dropOutputContents` —— **只有定义，没有调用**。

### 实际后果

- 当前只是僵尸代码（不影响运行）。但它**绕过了集群移交**（`handOverToSurvivors`）与
  `BlockContentReleaser` 的三档策略；谁按 javadoc 把它接回 `onRemove`，就会与
  `release()` 形成**双重结算 / 绕过落盘边界**的分歧 —— 那正是 `RsccMachineCluster`
  类注释里点名要避免的"资源翻倍"来源。
- 另外它自己也不完整：不处理"网络装不下的流体原样留在罐里"之外的单元样板 / 集群情形。

### 建议修法

- 删除；或改名为 `rscc$legacyDropOutputContents` 并加注释"**历史实现，已被
  `BlockContentReleaser.release` 取代，禁止调用**"。

---

## F8（低）终端 `saveAdditional` 把 `InputCandidates` 连写两遍

### 位置

- `block/entity/SequencePatternTerminalBlockEntity.java:2527-2536`：
  第 2529 行与第 2536 行是**同一个调用**
  `SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);`，注释也重复了一遍。

### 实际后果

- 无功能影响（第二遍写的是同一份数据，纯覆盖）。但它说明这块代码被改过多次却没清理，
  下次有人只改其中一处（例如加个过滤条件）就会出现"两份不同源的数据"。

### 建议修法

- 删掉一处（保留 2536 行那条带完整注释的即可）。

---

## 9. 复核过、**确认没问题**的点（避免下一轮重复查）

1. **集群落盘边界**（`RsccMachineCluster` + 执行仓 `saveAdditional:17506-17514`）：
   只有主控写 `OutputItems/OutputFluids`，非主控写空 ⇒ 不会"同一份内容写两份、读档翻倍"。
   一个**代码脆弱点**（不是当前可达 bug）：`rscc$clusterStandalone()` 用**流体容量**当
   "是否已是单机规格"的代理判据（`:17725-17728`），若将来 `autocrafterFluidCapacity * count`
   恰好等于单机值（只有 `count==1` 或"配置下限被改成 0"这两种可能）就会早退、不换成本机存储，
   于是拆集群成员后两台都指向同一份载荷且都 `clusterOwnsPayload=true` ⇒ 读档翻倍。
   现状是安全的：`Config.java:213-215` 的 `defineInRange(..., 1000, 100000000)` 保证 ≥ 1000，
   `count>=2` 时 `capacity*count != capacity`。建议还是把判据换成"载荷身份 == 单机新建的载荷"
   而不是"容量数值等于多少"，去掉这个隐形耦合。
2. C2S 越界信任：`SetChamberFacePacket` / `SetChamberOutputModePacket`（`direction` / `mode` 都夹）、
   `SetChamberUnitsPagePacket`（`setPage` 夹）、`SetSequenceCountPacket`（`index` 与 `value` 都夹）、
   `SetSequenceResultPacket`（窗口下标换算后夹）、`CreateUnitPatternPacket`（第 117 轮修过的负下标，
   现在是"拒绝 + 日志"而不是 `Math.max(0,·)` 覆盖）、`TransferChamberUnitPacket`
   （坐标经 `chamberAt` 二次校验）——**都正确**。
3. `applyFaceModes` / `applyOutputMode`（S2C 方向的越界回退）也是安全的。
4. `RsccChamberItemStorage.getSlots()` 只返回内部槽位、`extractItem` 也只用内部下标
   ⇒ `flushResidualInputs` 那套"先插网络、再按 inserted 精确抽取"**不会错位抽到别的物品**
   （这是本轮最想证伪的一条，结论是安全的）。
5. `ChamberUnitsSummaryMenu.WindowContainer.clearContent()` / `UnitManagerSources.*Container.clearContent()`
   全部刻意空实现 ⇒ 玩家死亡掉落不会抹掉执行仓 / 终端里的样板。
6. `TerminalLibraryContainer.canPlaceItem()` 恒 false + `SectionSlot.mayPlace` 判 `extractOnly`
   ⇒ 只出不进的分组不会出现"客户端假装放进去、服务端 no-op 吞掉"。

---

## 10. 未覆盖 / 未验证（诚实清单）

1. **全部结论都没有实机 / 专服复现**：没有启动游戏、没有改包联网、没有读 `latest.log` 实况。
   F1/F2/F4 的机制是从代码 + RS 源码推出来的，**"玩家多久撞一次"没有数据**。
2. `Config.autocrafterFluidCapacity` 的下限**已查**（`Config.java:213-215`，`defineInRange(..., 1000, …)`），
   所以第 9 节那条集群判据的脆弱点在当前配置下**不可达**，只是"写法耦合了配置数值"。
3. `RsccChamberImportStrategy`（1631 行）只读了入口与回收判据的注释和部分实现，
   **没有逐分支审**；`RsccChamberExportStrategy` 的轮转份额 / 退避也只扫读。
   `pushBusTurnToExporters`（输出总线轮转分派）未深读 —— 若下一轮要继续，这是最大的空白。
4. `RsccAssemblyMonitorBridge` / `AssemblyWatchdog` / `RsccFlowLedger` 只作为调用方出现，未独立审。
5. `UnitPatternDedupe`（判重）只读了调用点，未验证"判成重复但其实库里没有"这类方向。
6. 区块卸载时 `setRemoved()` 与 chunk 序列化的**先后顺序**没有实机验证
   （`RsccMachineCluster` 的注释承认平台侧没有可见保证）；F4 的"重启后"场景走的是
   "BE 被重新构造 ⇒ `inFlightUnits` 必然为空"这一条**不依赖顺序**的路径。

---

## 11. 本轮最该先修的三条

### ① F1 —— 单元样板堆叠 × 取回/存入 ⇒ 静默销毁 N−1 张（高，改 8 行就够）

理由：**唯一一条"正常玩法点两下按钮就永久丢玩家物品"**，而且与第 116 轮刚立的
"库满也不许销毁"规矩直接冲突；界面数量还是按格显示的，玩家连丢了都不知道。
修法不涉及产品行为（一格一张 + 按张搬运）。

### ② F2 —— 外部样板 sink 的整批原子性 + 那句错误注释（中，且是"注释骗人"）

理由：`REJECTED` 在 RS 里**不会重试**（源码原文 "we lie … potentially void"），
现有 javadoc 却写"让任务稍后再试" —— 这既是**网络满时丢一批材料**的真实路径，
也是主人最反感的那类"注释与实现不符"。最小修法是"SIMULATE 累计占用 + 一条都不插"，
彻底修法是给样板库加缓冲（属产品行为变更，需拍板）。

### ③ F4 —— 在制件登记表落盘（中，直接把"重启后永久卡住"这条已知边界补上）

理由：代码自己已经承认为边界，但后果是**机器工位被永久占死、只能手动挖**，
而且它是用户反复报过的"部分恢复、部分永不恢复"的同族现象。
落盘的条目数天然有界（本仓推过料的工位数），代价很小；
若不想改玩法语义，至少读档后要把"检测到上次残留"告知玩家，而不是静默。

> F3（汇总窗口轮转导致点错样板）排第四：它不丢东西，但"点了 A 得到 B"是很容易被当成新 bug 报上来的
> 体验问题，与 F1/F2 一起做界面侧顺手就能修。
