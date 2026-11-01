# 第 119 轮 F1：单元样板「按张搬运」，一叠 N 张存入不许丢 N−1 张

> 基线：工作树当前状态（第 119 轮复核报告 `docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md` §F1）。
> 本轮**只修 F1 这一条丢物品 bug**：不新增功能、不改产品玩法语义、不动存档格式、不跑 gradle、不做 git 操作。
> 复核报告本身未改动。

---

## 1. 现象（玩家视角）

单元样板物品可以叠到 64 张。执行仓的「单元槽」每格也能放 64 张。把一叠 N 张样板放进执行仓某一格后：

1. 打开序列样板终端 →「执行仓单元样板汇总」→ 选中该仓 → 点 **「取回」**：
   整叠样板进了终端单元样板库的**同一格**（库里一格叠 N 张）。
2. 再点 **「存入」**：执行仓只回来 **1 张**，库里那一格被清空。

⇒ **N−1 张样板凭空消失，没有任何提示**；汇总界面还按「格」显示成「1 张」，玩家连丢了都不知道。
这直接违反第 116 / 117 轮立下的「库满也不许销毁玩家物品」原则 —— 这里不是「满了拒绝」，而是
「没满也照丢」。

---

## 2. 根因（`文件:行`）

### 2.1 为什么一格里会有 N 张

| 位置 | 事实 |
| --- | --- |
| `src/main/java/cretae/cookiewyq/rs_create_compat/RS_Create_Compat.java:177-180` | 单元样板注册为 `stacksTo(64)`（总样板是 `stacksTo(1)`）。 |
| `src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/SequenceExecutionChamberBlockEntity.java:114`、`:150` | 执行仓单元槽 = `new SimpleContainer(UNIT_SLOTS)`（54 格），**没有覆写 `getMaxStackSize()` ⇒ 每格 64**。 |
| `src/main/java/cretae/cookiewyq/rs_create_compat/menu/SequenceExecutionChamberMenu.java:160-188` | `ChamberUnitSlot` 只覆写 `mayPlace`（判物品类型），**不限制数量**；`:131-134` 的 `quickMoveStack` 用 `moveItemStackTo(..., UNIT_START, PLAYER_START, false)` ⇒ Shift 一下就把整叠塞进一格。 |

### 2.2 丢在哪（修前代码）

| 位置（修前） | 事实 |
| --- | --- |
| `block/entity/SequencePatternTerminalBlockEntity.java:1303-1337`（`pullUnitsFrom` 取回） | 第 1325 行 `unitLibrary.trySetStackInSlot(empty, stack.copy())` —— **整叠原样写进库的一格**；第 1329 行整格清空执行仓。 |
| 同文件 `:1345-1369`（`pushUnitsTo` 存入） | 第 1360 行 `chamber.unitSlots.setItem(empty, stack.copyWithCount(1))` —— **只搬 1 张**；第 1361 行 `unitLibrary.setStackInSlot(i, ItemStack.EMPTY)` —— **整格清空**。 |
| 同文件 `:2936-2975`（`GrowingUnitLibrary.trySetStackInSlot`） | 写入时原样保留 `count`，没有「一格一张」归一化，所以 2.2 第一行能把 N 张塞进一格。 |

**丢物品的精确顺序**：`取回` 把 N 张写进库一格（数据还在，只是叠在一格）→ `存入` 只把
`copyWithCount(1)` 放回执行仓、却把库那一格**整格清空** ⇒ 其余 N−1 张没有任何代码路径接住它们。
`pushUnitsTo` 是真正的丢点；`pullUnitsFrom` 的「整叠写一格」是让这个丢点可达的前提。

### 2.3 附带口径问题

| 位置（修前） | 事实 |
| --- | --- |
| `SequencePatternTerminalBlockEntity.java:627`（`unitLibraryEntries`） | **按格**计数（最后一个非空槽 +1），用于分页窗口映射。 |
| 同文件 `:1222-1240`（`listChamberUnits`） | 返回「一格一项」的 `List<ItemStack>`，每项 `count` 可能 >1。 |
| `client/screen/ChamberUnitsSummaryScreen.java:400`（修前） | 汇总 tooltip 用 `entry.units().size()` 喂语言键「单元样板：%s 张」⇒ 一格 64 张显示成「1 张」。 |

---

## 3. 修法（不改变玩法语义）

### 3.1 取回：逐张写库，写一张才扣一张

`block/entity/SequencePatternTerminalBlockEntity.java:1328-1380`（`pullUnitsFrom`）：

- 外层仍按执行仓槽位遍历；内层 **`while (!whole.isEmpty())` 逐张搬**；
- 每张都重新 `unitLibrary.firstEmptySlot()`（第 116 轮格数上限）+ `unitLibrary.canAcceptMore(stack)`
  （第 118 轮合计体积预算）；
- **`whole.copyWithCount(1)`** 写进库（一格一张），`trySetStackInSlot(empty, stack)` 返回 `true` 之后
  才 `whole.shrink(1)`；整格空了再 `setItem(i, EMPTY)`；
- 任一闸拒绝 ⇒ `break` 并置 `unitTransferBlockedByLibrary = true`，剩余张数**原样留在执行仓**。

顺序：**先确认能写 → 再写 → 最后清空**。中途任何失败都不会让已确认的那张掉进「两边都不在」的缝隙。

### 3.2 存入：逐张取出，先写仓再扣库

`SequencePatternTerminalBlockEntity.java:1393-1437`（`pushUnitsTo`）：

- 每张都重新读 `unitLibrary.getStackInSlot(i)`（`extractItem` 会替换 `ItemStack` 实例，缓存旧引用会漏搬/死循环）；
- 找到执行仓第一个空单元槽后，**先** `chamber.unitSlots.setItem(empty, current.copyWithCount(1))`，
  **再** `unitLibrary.extractItem(i, 1, false)` 扣掉这一张；
- 旧存档里同一格叠了 N 张（历史数据 / 手工堆叠）⇒ `while` 逐张取，能取几张取几张，
  仓满时剩下的**留在库格**，绝不整格清空；
- `extractItem` 意外返回空（理论上不可达）时把刚写进执行仓的那 1 张回滚，绝不留下双份。

### 3.3 可见提示（第 5 条要求）

- 终端新增瞬态状态位 `SequencePatternTerminalBlockEntity.java:248`（`unitTransferBlockedByLibrary`，**不持久化**），
  拒绝分支置 `true`（`:1371`），getter `:1444-1446`；
- `network/TransferChamberUnitPacket.java:56-58`：只要「本次因库满 / 体积预算停下」（无论有没有搬出一部分）
  就给玩家动作栏提示，而不是旧实现只在 `moved <= 0` 时提示「没有样板 / 仓已满」；
- 新语言键（zh/en 成对，1 个 `%s` = 实际搬回张数，不写死数字）：
  - `gui.rs_create_compat.sequence_pattern_terminal.summary.pull.blocked`
    （`lang/zh_cn.json:1016` / `lang/en_us.json:1016`）。

### 3.4 界面张数口径

- `client/screen/ChamberUnitsSummaryScreen.java:319-325` 新增 `unitItemCount(entry)`：
  **`sum(ItemStack.getCount())`**，即真实「张数」；
- `:416` 用 `unitItemCount(entry)` 喂语言键「单元样板：%s 张」；
  不再用 `entry.units().size()`（那是「有样板的格数」）。
- `unitLibraryEntries`（`:641`）**保持按格计数**并在注释里写明口径：它服务于分页 / 窗口映射
  （`unitLibraryIndex` / `unitCellActive`），改成按张会让「界面上第 k 格 ↔ 库第几个槽」错位；
  新语义下库内一格一张，正常情况下「格数 == 张数」，旧档同格多张时按格算、逐张取。
- `ChamberUnits` record 注释（`:1167-1169`）写明 `units` 是「一格一项」，张数必须把 `count` 相加。

### 3.5 明确没有改的东西

- 单元样板库的存储结构 / NBT 键 / 落盘格式：**一字未动**；
- `GrowingUnitLibrary.trySetStackInSlot` 的格数 / 体积判据、读档不判上限：**一字未动**；
- 执行仓单元槽仍然允许一格叠 64 张（玩法语义不变；本轮只保证搬运不再丢）；
- 版本号 / CHANGELOG / README / gradle.properties / 其它 agent 正在写的文件：**未触碰**。

---

## 4. 边界

### 4.1 库格数满（`MAX_UNIT_LIBRARY_SLOTS = 1024`）

`firstEmptySlot()` 返回 `-1` ⇒ 取回立即停：已搬走的张数照常计入，**剩下的原样留在执行仓**；
`unitTransferBlockedByLibrary = true` ⇒ 玩家看到「本次只搬回 N 张，其余原样留在原处，未销毁」。
不会出现「清空执行仓格之后才发现库写不进去」。

### 4.2 合计体积预算（第 118 轮 `UnitLibraryLimits`）

逐张判：每写一张，`usedHeapBytes()` 增量更新，下一张重新判。预算在写到一半时耗尽 ⇒ 停在原地，
已写入的保留、未写入的留在执行仓。与第 118 轮「预检不过就一张都不写」在生成路径上的语义一致；
取回路径天然是「能搬几张搬几张」，不会半写一张（每张都是独立单元）。

### 4.3 旧存档「同格多张」

- 单元样板库：历史 `pullUnitsFrom` 写进去的同格多张**原样读回**（读档不判、不截断）；
  `pushUnitsTo` 现在逐张 `extractItem(i, 1)`，能取几张取几张，剩下的留在库格；
- 执行仓单元槽：旧档 / Shift 整叠放入的 `count > 1` 由 `pullUnitsFrom` 逐张搬进库；
- 终端 6 格窗口的 `UnitWindowSlot#getMaxStackSize() = 1`：旧档同格多张在窗口里仍显示为
  「一格 N 张」（物品角标），玩家可逐次取出，不会因为本轮改动被清掉；
- 界面张数：`unitItemCount` 把这些旧数据按 `count` 求和显示，不再把 64 张显示成 1 张。

### 4.4 执行仓满（`rscc$firstEmptyUnitSlot < 0`，即 54 格没有任何空格）

`pushUnitsTo` 在找不到空单元槽时停：已搬进仓的保留、剩下的留在库里；
这是旧的「仓满则留在库里」语义，本轮未改。注意：存入**只使用空格**（沿用旧行为，
不会与「同一物品、还剩堆叠余量」的半满格合并），所以只要没有空格就按「仓满」处理，
剩下的留在库格；本轮没有改这个语义，也没有因此销毁任何一张。
另外，执行仓 54 格 × 每格 64 张上限意味着「按张」存入时一个 54 格全空的仓最多接 54 张
（一格一张），这是「库内一格一张 / 搬运粒度」的现有语义，不是本轮新引入的限制。

---

## 5. 自检与回归

新增 `tools/selfcheck_round119_unit_stack_preserve.py`（静态锚点 + 行为模型 + 反例自证）：

```
第 119 轮自检：单元样板按张搬运（一叠 N 张存入不许丢 N−1 张）
...
断言 40 条（其中反例 9 条），失败 0 条
ALL_PASS
退出码 0
```

覆盖：

- **源码正例**：逐张 `copyWithCount(1)`、先写后 `shrink(1)`、格数 / 体积两道闸、存入逐张 `extractItem`、
  先写仓后扣库、重读库槽、可见提示、界面 `sum(getCount())`、分页仍按格；
- **行为模型**：N = 1..64 的取回 / 存入 / 往返，断言总张数守恒；库格数不够、体积预算拒绝时
  剩余留在原处；旧档同格多张逐张取出；仓满 54 张后其余留库；
- **反例（必须判负）**：`whole.copy()` 整叠写一格、把清空提到写入之前、旧 `copyWithCount(1)` +
  `setStackInSlot(i, EMPTY)`、先扣库再写仓、界面改回 `units().size()`、去掉可见提示，
  以及两个行为模型反例（旧实现 N=2 丢 1 张；先清空再写在预算拒绝时丢 54 张）。

回归（全部通过，没有改任何既有锚点）：

| 脚本 | 结果 |
| --- | --- |
| `tools/selfcheck_round116_sequence_write_budget.py` | `round116 OK 断言 32 / 反例 8`，失败 0 |
| `tools/selfcheck_round117_blocked_limits.py` | `round117 OK 断言 34 / 反例 13`，失败 0 |
| `tools/selfcheck_round118_unit_library_bytes.py` | `断言 50 条（其中反例 6 条），失败 0` / `ALL_PASS` |

> 第 116 轮 `④-9` 与第 118 轮 `①-20` 的锚点（`final int empty = unitLibrary.firstEmptySlot();`、
> `canAcceptMore(stack)`、`warnUnitLibraryFullBytes()`、`break;`、`chamber.unitSlots.setItem(i, ItemStack.EMPTY);`
> 的先后顺序）在逐张改写后仍然成立，因此**没有放宽或改写任何既有判据**。

---

## 6. **未验证**（诚实清单）

1. **没有实机点过界面**：没有启动游戏 / 专服，没有真的点「取回 / 存入」、没有看动作栏提示是否显示、
   没有验证汇总 tooltip 的「张数」在真实客户端里的排版。
2. **没有跑 gradle / 编译**（按本轮纪律由 Lead 统一编译）：Java 改动的语法 / 类型正确性只做了人工复核 +
   静态自检，**没有经过编译器**。
3. **没有测存档往返**：没有把「库内同格多张」的旧档读进游戏再点「存入」验证逐张取出；
   行为模型只复刻了张数语义，不涉及 NBT / `ItemStackHandler` 的真实实现。
4. **没有测网络包**：`TransferChamberUnitPacket` 的新提示分支只做了源码锚点；没有实际发包看
   `displayClientMessage` 的表现。
5. **没有测并发 / 异常路径**：如 `extractItem` 异常返回空时的回滚分支（代码里自认「理论上不可达」）
   没有构造真实故障注入。
6. 没有评估「一叠 64 张全部取回后库格数 +64」对玩家体验 / 库体积预算的实际影响（行为上这是
   「一格一张」的必然结果，但没有实机感受过界面翻页速度）。

---

## 7. 顺手发现的其它问题（**未改**，给后续轮次）

1. **`pullUnitsFrom` 不筛物品类型**（`SequencePatternTerminalBlockEntity.java:1328-1380`）：
   它会把执行仓单元槽里的**任何非空物品**搬进单元样板库，而 `listChamberUnits`（`:1241`）用
   `SequenceUnitPatternItem.isUnitPattern` 过滤。旧存档若在单元槽里躺过**总样板**（复核 F6 提到读档
   不做类型校验），点「取回」会把总样板搬进单元样板库（不丢，但进错桶，`pushUnitsTo` 又因
   `acceptsUnit` 不会把它送回执行仓）。本轮只修 F1 丢物品，没有动这条语义。
2. **执行仓单元槽仍允许 64 张 / 格**（`SequenceExecutionChamberMenu.java:160-188`）：
   本轮刻意不改（玩法语义不变）。这意味着「一格 64 张」在界面窗口里仍显示为一格，玩家需要
   逐张点出 / 依赖本轮的逐张搬运；如果后续想从根上避免「一叠样板占一格」，需要产品层拍板是否
   给 `ChamberUnitSlot` 加 `getMaxStackSize() = 1`。
3. **`warnUnitLibraryFull()` 每台终端只打一条日志**（`SequencePatternTerminalBlockEntity.java` 内，
   第 116 轮既有行为）：玩家侧提示现在是每次都给，但服务端日志只第一条，排障时看不到「后来又被拒了几次」；
   这是既有设计，未改。
4. **`GrowingUnitLibrary.trySetStackInSlot` 仍接受 `count > 1`**（`:3013` 起）：
   本轮所有调用方都改成逐张写，因此不会再产生新的同格多张；但入口本身没有防御性归一化，
   将来若有人再接一条「整叠写一格」的调用仍会制造旧数据。没有改是为了不扩大本轮范围 / 不动库语义。
