# 齿轮堵塞 / 供料卡死 —— 可执行自检判据

> 目的：让「齿轮堵塞到底好了没有」不再靠肉眼看刷屏日志猜。
> 本文给出**一条命令 + 一组 grep + 一组期望分布**，以及"什么算回归"。
>
> 适用范围：序列执行仓（`SequenceExecutionChamberBlockEntity`）的总线输出模式
> （原料 / 中间产物 / 产物由输出总线在「执行仓 ↔ 机器」之间搬运）与
> 输入总线收回模式（`RsccChamberImportStrategy`）。

---

## 0. 一键自检（推荐先跑这个）

```
python tools/verify_single_unit_supply.py
```

脚本会做三件事：

1. **源码锚点（硬断言）**：确认每一项修法都在代码里，且"同一件事只有一份判定"
   （工位归并 / `unitInFlightAt` / 起步原料保护 / 逐步抽取批上限 / 缺料带单位）；
2. **分布打印**：打印 `create:cogwheel` / `create:golden_sheet` 的 `event/reason` **加权分布**
   （加权 = 该行末尾 `(same cause repeated n times in 5s)` 的 `n`），以及金板收支账目；
3. **日志断言**：当日志**晚于**最近一次编译产物时，自动断言 ①a~⑥（含"无持续无效重复"）。

> 若脚本提示 `[SKIP] 日志早于本次修复的编译产物`，说明这份日志是**修复前**的基线：
> 让游戏重跑一次（下单 1 个 / 各 64 个坚固板、精密构件），再跑本脚本即可。

---

## 1. 前置：打开结构化诊断

游戏内执行（或写进 `run/config/` 的启动参数）：

```
/rscc-assembly debug on
```

之后所有诊断行前缀是 `[rscc-assembly]`（`event`）与 `[rscc-trace]`（`trace`），落在
`run/logs/latest.log`。

---

## 2. 判据 A —— "每 tick 重复打印的稳态噪声"必须没有

**要 grep 什么**（PowerShell，在工程根目录执行）：

```powershell
Select-String -Path run\logs\latest.log -Pattern 'same cause repeated' |
  Select-String -Pattern 'cogwheel|large_cogwheel|golden_sheet|sturdy_sheet'
```

**期望**：`(same cause repeated n times in 5s)` 里的 `n` **每个 (仓|总线 + 件 + 因由) ≤ 8**。

- `n` 的含义：这一条**同一因由**在 5 秒窗口内又发生了 `n` 次。
- 为什么阈值是 8：导出清单（`busExportFilters`）每 1 秒**有界重算一次**，因此
  "本机没分到份 / 仓里没这种料"这类**设计内的稳态**最多每秒被判定一次；
  5 秒窗口 ≤ 8 属于正常重算节奏。
- **回归信号**：出现 `n ≥ 20`（修复前实测齿轮 37 次 / 5 秒、金板 machine_busy 2576 次）
  ⇒ 说明又退回了"每 tick 判一次"，需要检查
  `tickBusScheduler()` 里的 `busExportRefreshCooldown` 有界重算是否被改坏。

关键因由（`reason=`）与含义：

| reason | 含义 | 期望 |
| --- | --- | --- |
| `machine_full` / `DESTINATION_DOES_NOT_ACCEPT` | 目标机器此刻收不下（手 / 台上还压着东西） | 只在**状态变化**时各一条；不出现 `n ≥ 20` |
| `machine_busy_with_my_step` | 起步原料的闸门：工位上还有在制件，不开新件 | 同上（这是**正确**的拒绝，不是堵塞） |
| `chamber_empty` / `RESOURCE_MISSING` | 仓里没有这种料，推不出去 | 同上（清单已过滤掉推不出去的类别） |
| `machine_holds_input` | 机器上还压着一件同类投入物 | 同上 |
| `not_wanted_this_step` | 本仓当前待加工步**不要**这件（齿轮废料的判定） | **加权必须为 0**（修复前 488 次） |
| `step_extra_share_exhausted` / `share_exhausted_order_remaining` | 份额内机器在干活，本机让位 | 只在**状态翻转**时各一条 |

---

## 3. 判据 B —— "真实的无效重复搬运"必须为 0

**要 grep 什么**：

```powershell
# ① 无效往返：同一件东西既被推出去、又被收回来（网络存量来回跳）
Select-String -Path run\logs\latest.log -Pattern 'event=bus_push'        -Context 0,0
Select-String -Path run\logs\latest.log -Pattern 'event=take_from_machine'

# ② 缺料提示（必须成对：有 short 就一定有 alert）
Select-String -Path run\logs\latest.log -Pattern 'short \{|alert reason=missing_material'

# ③ 半成品（过渡件）绝不能被任务计为交付 / 被边沿收进网络
Select-String -Path run\logs\latest.log -Pattern 'incomplete_|unprocessed_.*task_finished_edge'
```

**期望（硬指标）**：对同一种原料，`bus_push` 的**加权次数 ≤ 成品数 × 1.05 + 2**。

- 坚固板：`bus_push(create:powdered_obsidian)` ≤ `insert_network(create:sturdy_sheet) × 1.05`
  （修复前实测 1.18~1.20 = 同一件在制品被多投一份主原料）；
- 精密构件：`bus_push(create:golden_sheet)` ≤ `insert_network(create:precision_mechanism) × 1.05`
  （修复前实测 1.271 = 金板被反复"推出去又被收回来"）。
- **回归信号**：比值 > 1.05 + 容差 ⇒ 先看有没有 `take_from_machine` 的**起步原料**行
  （起步原料在任务期间绝不该被收回，见判据 C）。

---

## 4. 判据 C —— 三条"结构不变式"（改坏了会立刻表现为堵塞 / 多耗）

1. **同一台机器同一时刻最多一份未消耗的投入物**
   —— 判据只有一份实现：`SequenceExecutionChamberBlockEntity#wantingTargetCount`
   （按 `supplyStations` 工位归并 + `supplyTargetHoldsItem`），
   推料侧在 `RsccChamberExportStrategy#transferItem` 再判一次同口径的 `holdsSameItem`。
2. **工位上还有在制件 ⇒ 不开新件（起步原料）**
   —— `isStartIngredient(item) && unitInFlightAt(targetPos)`；
   备料侧 `wantingTargetCount` 用同一个 `unitInFlightAt`。
3. **起步原料在任务运行期间绝不被输入总线收回**（任务结束的那一次边沿例外，收一遍）
   —— `RsccChamberImportStrategy#autoAcceptsItem`
   的 `if (chamber.isStartIngredient(stack.getItem()) && !residualEdge) return false;`。

**回归信号**：日志里出现"某份起步原料被 `take_from_machine` 收走、随后又被 `bus_push` 推回"
（同一坐标、同一物品、间隔 < 5 秒）⇒ 判据 C.3 被改坏。

---

## 5. 判据 D —— 总量守恒（不许复制 / 销毁）

对每一件成品做收支账目（脚本已打印）：

```
拉进执行仓(take_to_chamber) − 推给机器(bus_push) − 从机器收回(take_from_machine) − 入网(insert_network)
```

- **回归信号**：`insert_network` 行里出现 `not_retained > 0`
  （网络"假收下"：上报成功但既不在容器也不在网络聚合存量）⇒ 检查网络里的外部存储
  （挂在 `create:creative_crate` 上的 `refinedstorage:external_storage` 会接受任意物品却不存任何东西）。

---

## 6. 一次完整自检的最小步骤（给用户）

1. 进游戏，`/rscc-assembly debug on`；
2. 放好产线（执行仓 + 输出 / 输入总线 + 机器），**只放刚好够的量**：
   下单 **1 个**坚固板、再下单 **64 个**坚固板；同样各跑一次精密构件；
3. 跑完（或中途挂起 / 取消各一次）后退出，回到工程根目录执行：

   ```
   python tools/verify_single_unit_supply.py
   ```

4. 看结尾：`VERIFY OK (N checks)` = 全绿；
   任何 `FAIL` 都会直接写出"哪一件东西、期望多少、实际多少"，照上面判据 A~D 定位即可。

---

## 7. 本轮（2026-09-30）新增的三道闸门 + 取证口径

> 背景：`run/logs/latest.log` 常常是**刚启动、还没跑产线**的会话（只有 `strategy_installed` 建链行）。
> 脚本因此改成：**优先 latest.log，没有生产事件就自动回退到最近一份真的有生产事件的轮转会话**
> （`*.log.gz`），并打印「选了哪一份、为什么」。断言分两层且**都真的跑**：
> `H*` 是任何日志都必须成立的**硬不变式**（违反即 FAIL）；
> `F*` 是**修复后行为**，若日志文件早于最新编译产物（= 那份日志跑的是旧构建）会打印实测值并标
> `FIXED-OLD-LOG`（不是"跳过"，而是"归因"）——用户重跑一次产线后同样两条自动变成 PASS / FAIL。

| 闸门 | 代码落点 | 治的是什么 |
| --- | --- | --- |
| **流体侧「一次一份」**（与物品侧对称） | `SequenceExecutionChamberBlockEntity#holdsInputFluidAt`（导出清单）+ `#machineHeldFluid` → `#pullFluid` 的 `effectiveTarget`（备料） | 用户第 ③ 条「岩浆没有被正常消耗」：注液机罐里还有 500 mB 时不再抽、不再推（实测 `DESTINATION_DOES_NOT_ACCEPT/machine_full` 126 次加权、单条最高 10 次 / 5 秒；抽取 30000 mB 而机器净得 23000 mB 全是「抽了不用」） |
| **备料份额闸门** | `SequenceExecutionChamberBlockEntity#wantingTargetCount` 尾部的 `min(want, remainingOrderUnits())` | 用户第 ④ 条「一个一个下单时中间产物还是存在额外之类的」：下单 1 个 ⇒ 目标量 = 1（修复前 = 要它的工位数 = 2，实测 6~7 次 / 5 秒，多出来的那份在任务结束时以 `task_finished_residual` 退回） |
| **让位判据补「正在干活」** | `SequenceExecutionChamberBlockEntity#machineBusyOnPipeline` → `#shareFrontBusyFor` | 用户第 ② 条「齿轮还是堵塞」：机械手手里握着本步的**另一样**投入物（因此 SIMULATE 必然拒收本件）不再被误判成「这台干不了活」，份额外的第二台总线不再顶上来把齿轮推给它（实测 `exporter@(-6,-58,6) push {item=create:cogwheel} to=machine@(-7,-58,6)`，那件齿轮永久卡死） |

三者的共同口径只有一句：**「一件东西同一时刻只在一个地方、只服务一台机器」** ——
物品侧早就是这么做的，本轮把流体侧补齐、并让份额真正管住备料与让位两个执行点。
