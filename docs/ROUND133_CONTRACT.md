# 第 133 轮 契约 —— 序列装配「多发一件金板且不回网」收尾根治 + diag 拍到现场

> 生成时间：2026-11-07（Asia/Shanghai）。版本目标：`1.2.0-beta.34`。
> 用户原话（m00627）：「我们的项目差不多可以收尾了。不过现在还有个小问题…… 我下单20个然后他给我做完之后多发了一个金板然后这个金板完成了一次装配然后就没有回去。……我已经保存了diag，自己去服务器上面查看」。

## 0. 问题定义（以实机证据为准）

| 事实 | 证据 |
| --- | --- |
| 服务器跑的是 `1.2.0-beta.33`（`revision=109e44b`，与工作树一致） | `diag/pull_20261107/server_latest.log` 的 `[rscc-build]` 行 |
| 用户 22:52 保存的 diag 里 **chambers=0 / buses=0**，machines 只有出生点试炼密室的 60 个方块实体 | `diag/pull_20261107/snapshot.json`、`prev_snapshot.json`（2026-10-24 那份结构完全相同） |
| 基地/产线在 x≈360~421、y≈65~107（`[rscc-collect]` 坐标 390,107,98 / 360,65,69 / 409,103,89） | 同一份 `server_latest.log` |
| devLogs 只在 22:52:28.524–28.563 开了约 40ms（导出那一下），日志里没有任何装配 trace | `[rscc-assembly] devLogs=on/off (source=command)` + `[rscc-diag] BEGIN/END` 相邻四行 |
| 装配链起步原料 = `#c:plates/gold`（金板）；精密构件 `p=0.8` ⇒ 下单 20 时在制上限 `ceil(20/0.8)=25` 是设计行为 | `docs/SEQUENCE_CHAIN_REDESIGN.md`、`startCapacityForRecipe` |

⇒ 用户看到的是**两件事**：①合法的「按概率期望多开几件」让最后一份起步原料已经推给机器；
②任务结束后这一份**完成了一次装配（变成过渡件）却没有任何路径收回**，永久压在置物台上。
本轮要修的是 ②（显示层的「多发」是概率配方的正常代价，收得回来就不是 bug）。

## 1. 根因链（代码级）

1. 在制登记 `inFlightUnits` 的键 = **推料成功的那一格**（`RsccChamberExportStrategy` → `rscc$noteUnitPushed(target, ...)`）。
   对「机械手 + 它操作对象（置物台）」这种工位，推料目标可能是**机械手**，而不是置物台。
2. 装配完成后，过渡件留在**置物台**（`stationKey(level, target) = operatingPosOf(...)`），此时**登记坐标（机械手）已空**。
3. 任务确认结束边沿：`hasRetainableUnitRegistrations()` → `stationHoldsPipelineResidue()` 只扫**登记坐标那一个 handler**
   ⇒ 判「工位已空」⇒ `rscc$retainResidualUnits()` 删掉凭据。
4. 之后三条回收路径（`clearDeadStationPieces` / `reclaimRuntimeStationResidue` / `flushStationStartIngredients`）
   **都只遍历 `inFlightUnits`、且只扫登记坐标** ⇒ 置物台上那件过渡件再也没有任何路径收得回来。

## 2. 修法（只动收尾/清扫口径与诊断，不改配方行为 / 不改供料目标量）

### 2.1 `SequenceExecutionChamberBlockEntity`

- 新增只读 `stationGroupPositions(Level, BlockPos registered)`：登记坐标 → 工位本体（`stationKeyCached`）→
  同工位其它供料目标（`stationSiblings`），去重保序，**登记坐标第一位**。
- `stationHoldsPipelineResidue`：改为遍历整工位；任一格未加载 ⇒ 保留凭据（看不见 ≠ 没有）。
- `clearDeadStationPieces`：先按登记坐标扫（起步原料 / 步骤投入物 / 过渡件三种残留都放行），
  再扫描同工位其它容器（**只收过渡件**）。
- `clearDeadStationPieceAt(Level, BlockPos pos, BlockPos registered, boolean allowStartIngredient)`：
  实际扫 `pos`、登记表查询/撤销用 `registered`；非过渡件分支必须 `allowStartIngredient` 且同物品命中登记表
  （避免误收玩家手放在置物台上的金板 / 齿轮）；过渡件仍要求「该配方没有活跃订单」；
  `busTargetAccepts(pos) || busTargetAccepts(registered)` 都判；搬运仍是 SIMULATE → extract → insert，
  装不下原样还回，收进本仓后立即 `flushResidualInputs()` 写回网络。
- `rscc$diagReport()` 补 `inFlightUnits` 明细：`station / item / recipe / step / age / group`。
  （此前只有 `inFlight` 布尔值，无法回答「这一件登记在哪、属于哪条配方哪一步、待了多久」。）

### 2.2 `support/RsccDiag`

- `blockEntities()` 从「只看 `AssemblyWatchdog.LOADED_CHUNKS`」改为**并集**「玩家周围 view-distance 个已加载区块」，
  只用 `level.getChunkSource().getChunkNow(...)`，**绝不主动加载区块**；半径夹在 `[2,16]`；区块键打包与看门狗同格式。
  理由：两次快照都只拍到出生点试炼密室（机器 x=-1..47 / z=-29..45），玩家基地 0 台机器入镜 ——
  「保存 diag 让我去看」看到的是空现场。

### 2.3 自检

- 新增 `tools/selfcheck_round133_station_group_residue.py`：44 项断言（源码锚点 16 + 安全锚点 8 + diag 锚点 15 + 反例 4 + 汇总额外）。

## 3. 不做（本轮明确不改）

1. ~~**不改概率配方的在制上限**（`ceil(remaining/p)`）：那是「按期望轮次多开」的正确语义，窄化会导致订单永远做不满。~~
   **已被第 134 轮推翻（2026-11-08）**：用户明确驳回「按期望轮次预开」——订单 20 件、p=0.8 时一上来投 25 份起步原料，
   资源紧张时等于多占。新口径 = 「名额 = 订单剩余 R − 在制件数，与 p 无关；失败一件才补发一件」，
   见 `docs/ROUND134_ON_DEMAND_SPEC.md`。本轮第 133 轮的其余条款（整工位收尾 / 清扫 / diag）不受影响。
2. **不改 `inFlightUnits` 的登记键语义**（改键需要反查「这一格是谁推的」，会牵动供料目标量 / 让位判据三处口径）。
3. **不扩大 `reclaimRuntimeStationResidue` 的扫描面**：它服务于「运行中 machine_full 死锁」，语义与任务结束清扫不同；
   整工位化留给后续需要时再做（本轮先保证结束后的残留一定能收回）。
4. 服务器日志已证明**没有装配 trace 可查**（devLogs 只开了 40ms），因此本轮不尝试从日志反推多出来的那一件，
   而是修「无论它怎么留在台上都能被收回」+ 让下一次 diag 拍得到现场。

## 4. 验收口径

1. `_lead_compile.ps1` → `LEAD_COMPILE_OK`。
2. 全量自检 0 失败（含新增 round133 脚本）。
3. 专服探针 `verify_dedicated_server.ps1` → PASS。
4. `gradlew build` 成功，jar 三方部署（本地 / `/mods` / `/client_files/mods`）sha256 全等。
5. 实机判据（交给用户）：再下单 20 → 订单完成后，置物台上不应再留下任何本仓过渡件；
   若留下，`/rs_create_compat diag run` 的新快照里 `chambers[]` 应能看到执行仓，
   `inFlightUnits[]` 应给出该件的登记坐标与整工位坐标组。
