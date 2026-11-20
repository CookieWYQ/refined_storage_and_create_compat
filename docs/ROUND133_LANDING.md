# 第 133 轮 落地说明 —— 序列装配"多发一块金板且不回去"：收尾/清扫改按整工位判定 + diag 能拍到现场

> 配套契约：`docs/ROUND133_CONTRACT.md`。本文是**实现完成后的事实记录**：根因、改了什么、验证证据、还剩什么。
> 版本：`1.2.0-beta.34`（`gradle.properties:48`）。
> jar：`build/libs/rs_create_compat-1.2.0-beta.34.jar`，2,953,273 B，sha256 `4dc50931b4d2618f4cff5e2dcbe8d054cf224efe51f7d8b4a7d433913542dcc1`。
> 源码：`SequenceExecutionChamberBlockEntity.java` 改后 18,446 行；`RsccDiag.java` 新增玩家周边区块并集。

---

## 0. 用户原话 → 本轮结果

用户 2026-11-07（m00627）：「那个序列装配大抵是没有问题了，但是我刚刚下单的时候，他多发了一个金板……就我下单 20 个然后他给我做完之后多发了一个金板，然后这个金板完成了一次装配然后就没有回去。这个就是一个小瑕疵。我已经保存了 diag，自己去服务器上面查看」

| # | 用户所说 | 本轮查到的真相 |
| - | --- | --- |
| 1 | 下单 20 个，做完后多 1 个金板 | **不是多做了一件产品**，而是「订单交付后，工位上还留着一件已经在加工/刚加工完的金板起步原料（已被换成过渡件）」。精度配方 p=0.8 ⇒ 下单 20 时在制上限 `ceil(20/0.8)=25` 是设计行为（见 `startCapacityForRecipe` 注释），订单提前交付满时多出来的在制件本来就该由「任务结束收尾」收回。 |
| 2 | 该金板"完成了一次装配然后就没有回去" | 它确实在同一工位被 Create 机械手加工了一步（带 `SEQUENCED_ASSEMBLY` 组件的过渡件），从此它不是"金板"而是"未完成件"。收尾清扫的三条路径（`flushStationStartIngredients` / `clearDeadStationPieces` / `reclaimRuntimeStationResidue`）**都只遍历 `inFlightUnits` 的登记坐标**；登记坐标通常是"机械手"，而过渡件落在"置物台"（`stationKey`）。两者不是同一格 ⇒ 看不见、收不到。 |
| 3 | "已经保存了 diag" | 服务器 `/rscc_diag/20261107-225228/snapshot.json` 里 `chambers=[]`。根因见 §1.2：`RsccDiag.blockEntities()` 只遍历 `AssemblyWatchdog.LOADED_CHUNKS`，而那份表只含出生点附近区块，基地（x 360~421）从来没被枚举过；10-24 的旧快照结构一模一样（相隔两周仍是同一批 60 台试炼密室方块）。 |

---

## 1. 根因链（四步，全部有代码/日志证据）

### 1.1 登记键 = 推料目标（机械手），产出落在置物台
- 推料成功时 `rscc$noteUnitPushed` 记 `inFlightUnits.put(target, …)`（`SequenceExecutionChamberBlockEntity.java:3353-3365`），`target` 来自 `busSupplyTargets()`，即总线实际向谁推（常常是对着置物台的机械手/机械臂）。
- `stationKey(level,target)` = `operatingPosOf(level,target)`（机械手的操作对象 = 置物台）或 target 自己（`:9387`）。装配完成后过渡件留在**置物台**。

### 1.2 diag 拍不到基地，事故没有现场证据
- `RsccDiag.blockEntities(ServerLevel)`（`support/RsccDiag.java:898-911`）唯一数据源 = `AssemblyWatchdog.rscc$loadedChunks(dimension)`；`LOADED_CHUNKS` 只由 `ChunkEvent.Load/Unload` 维护（`support/AssemblyWatchdog.java:644, 689-722`）。
- 两次快照（2026-10-24、2026-11-07）里 `machines` 完全一样：60 台，坐标 x=-1..47、z=-29..45，全是 `lootr:*` + `sculk_*` + `trial_spawner` ⇒ 出生点/试炼密室区块，**没有任何 create/rscc 机器**。
- 服务器 latest.log 的 `[rscc-collect]` 每 100 tick 一条，位置 `@BlockPos{x=390,y=107,z=98}` / `{x=360,y=65,z=69}` / `{x=409,y=103,z=89}` ⇒ 基地确实在 360~421 一带，且区块是加载的。结论：区块表本身漏了这些区块（而非基地没加载）。

### 1.3 结束边沿把"那一刻恰好空着"的工位登记删掉了
- 结束边沿：`busTaskEndFired=true` → 发残留令牌 → `flushResidualInputs()` → `hasRetainableUnitRegistrations() ? rscc$retainResidualUnits() : rscc$clearAllUnits()`（`:12115-12118`）。
- `stationHoldsPipelineResidue`（原 `:3442`）只扫**登记坐标那一个容器**：登记那件在 / 槽里出现本仓过渡件 / 工位未加载 ⇒ 保留，否则删。
- 结束那一刻工位上"恰好空"（机器已把原料吃进去、成品还没吐回置物台）是常规时序，不是异常 ⇒ 保留判定 false ⇒ 登记被删。

### 1.4 三条清扫路径都靠登记表，删了就永远没人收
- `clearDeadStationPieces()`（`:3766`）、`reclaimRuntimeStationResidue()`（`:4027`）、`flushStationStartIngredients()`（`:13812`）都只遍历 `inFlightUnits`。
- 之后机器把过渡件吐回**置物台**；登记已删、扫描只看机械手格 ⇒ 那件永久留在台上。这正是用户说的「完成了一次装配然后就没有回去」。

> 旁证：`:12129-12144` 的注释早就记过一次同族事故「2026-10-05 修掉『多出一个金板且永久滞留』」——那次修的是"令牌只活 40 tick，边沿之后 `recoverBlockingTargetItem()` 还会把输入类搬进来"；这次是"凭据在边沿被删"，同一个坑的另一半。

---

## 2. 改动明细

文件：`src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/SequenceExecutionChamberBlockEntity.java`

1. **新增 `stationGroupPositions(Level, BlockPos registered)`（`:9488`，只读）**：`LinkedHashSet` 保序 = 登记坐标 → `stationKeyCached`（置物台）→ `stationSiblings`（同工位全部供料目标）。javadoc 写清「登记键 = 推给谁（可能是机械手），装配产出落在置物台」这一 2026-11-07 实测根因。
2. **`stationHoldsPipelineResidue`（`:3442`）改按整工位判定**：登记坐标未加载 ⇒ true（与旧行为一致，保守）；逐坐标 `isLoaded` 检查 + `itemHandlerAt` 为空则 continue（机械手格不是容器）；任一格有"登记那件"或"本仓过渡件" ⇒ true。
3. **`clearDeadStationPieces`（`:3766`）主扫 + 整工位补扫**：先 `clearDeadStationPieceAt(level, station, station, true)`；没搬动再遍历 `stationGroupPositions(level, station)` 的**其它**坐标，`clearDeadStationPieceAt(level, other, station, false)`，命中即 break。仍然受 `DEAD_STATION_CLEAR_INTERVAL_TICKS` 限速、每轮至多 1 件。
4. **`clearDeadStationPieceAt(Level, BlockPos pos, BlockPos registered, boolean allowStartIngredient)`（`:3835`）**：容器扫 `pos`、登记表读 `inFlightUnits.get(registered)`；非过渡件分支先 `if (!allowStartIngredient) continue;` —— **非登记坐标只允许收过渡件**，绝不碰玩家手放/别的仓推的起步原料；其余判据（同物品命中、`unitInFlightAt`、`inputMaterialWantedNow`）原样保留。过渡件分支保留两道闸：`assembly != null && recipeOrdered(...)`，以及 `busTargetAccepts(pos) || busTargetAccepts(registered)` 时再判 `transitionRecipeOrdered`。搬运失败原样 `insertItem` 还回；成功后按**登记键**清 `inFlightUnits / abandonedInFlightAt / refusedTargetAt / refusedTargetHits / unitLivenessAt`，并 `flushResidualInputs()`。
5. **`rscc$diagReport()` 补 `inFlightUnits[]`（`:18184-18190`）**：每行 `{station, item, recipe, step, age, group}`，`group = stationGroupPositions(...)` 的坐标串（JSON 里叫 `group`），一眼能看出"登记在哪、产物该落在哪"。

文件：`src/main/java/cretae/cookiewyq/rs_create_compat/support/RsccDiag.java`

6. **`blockEntities()`（`:898-931`）区块来源并集**：先并入 `AssemblyWatchdog.rscc$loadedChunks(dimension)`，再对同维度每名玩家 `player.chunkPosition()` + `radius = max(2, min(16, viewDistance))` 双层 `dx/dz` 并入（`getChunkNow`，绝不主动加载）。新增 `packChunk(int,int)`（高 32 位 x / 低 32 位 z，与看门狗同格式）。这样导出瞬间玩家身边/基地的仓一定能进快照。

**明确不做**（写进契约，避免下一轮误改）：不改概率配方的在制上限（p=0.8 ⇒ 25 是数学期望，不是 bug）；不改登记键语义（推料目标仍作键，改键是协议级改动）；不扩大 `reclaimRuntimeStationResidue` 的扫描范围（它只治运行期，收尾归 `clearDeadStationPieces`）；不从日志反推（本次日志无 trace）。

---

## 3. 验证证据

| 项目 | 结果 |
| --- | --- |
| `powershell -File tools/_lead_compile.ps1` | **`LEAD_COMPILE_OK sources=526`**（`LEAD_COMPILE_VERSION_SUMMARY rs=refinedstorage-neoforge=2.0.9, refinedstorage-quartz-arsenal-neoforge=1.0.6`；前面 `LEAD_COMPILE_EXCLUDED` 是依赖顶替说明，既有行为） |
| `D:\Python313\python.exe tools/_run_all_selfchecks.py` | **selfcheck 总数 201，失败 0** |
| 新增 `tools/selfcheck_round133_station_group_residue.py` | **44 项，失败 0**（源码锚点 16 / 安全锚点 8 / diag 锚点 15 / 反例自证 4） |
| `gradlew.bat build --no-daemon` | **BUILD SUCCESSFUL in 6m 43s**，`modVersion 1.2.0-beta.34` |
| `tools/verify_dedicated_server.ps1` | **PASS**，`Done (1.270s)!`，无 Mixin / dist 致命标记，`run/` 前后清单一致（只读自证 OK） |
| `deploy_rscc.py --apply` + `deploy_rscc_local.py --apply` | `/mods`、`/client_files/mods`、本地实例三处均 `beta.34`，旧 `beta.33` 就地 `.disabled` |
| `D:\Python313\python.exe tools/_verify_deploy_round133.py` | **`DEPLOY_VERIFY_OK`**（build/libs、本地实例、SFTP `/mods`、SFTP `/client_files/mods` 四处同 sha256 `4dc50931…`） |

---

## 4. 自检口径变化（谁接管了谁）

- 新增 `tools/selfcheck_round133_station_group_residue.py`（44 项）：
  - 核心判据：`stationHoldsPipelineResidue` 必须遍历 `stationGroupPositions`；`clearDeadStationPieces` 必须主扫 + 整工位补扫；非登记坐标的扫描必须带 `allowStartIngredient == false` 且该闸门在"同物品命中"之前；成功路径必须按 `registered` 清登记并调 `flushResidualInputs()`。
  - diag 判据：`rscc$diagReport` 必须有 `inFlightUnits` 数组、含 `group`；`RsccDiag.blockEntities` 必须并 `LOADED_CHUNKS` + 玩家周边 `getChunkNow`。
  - 反例自证 4 条：只扫登记坐标 / 允许非登记坐标收起步原料 / 不按登记键清表 / diag 只读看门狗 —— 四种错误写法都会被判负。
- `tools/selfcheck_round62_no_permanent_stop.py`：A5c 锚点 `abandonedInFlightAt.remove(station)` → `…remove(registered)`；A7f 锚点 `busTargetAccepts(station, item)` → `busTargetAccepts(pos, item) || busTargetAccepts(registered, item)`（语义未放宽，反而多判一格）。
- `tools/selfcheck_round85_request_selfheal_cancel_reclaim.py`：`clearDeadStationPieceAt` 签名改为 4 参锚点；C1-⑦b `registered` → `registeredUnit`（坐标改名后的同名冲突）；C1-⑦d `inFlightUnits.remove(station)` → `…remove(registered)`。
- 其余 198 个脚本零改动、零失败。

---

## 5. 已知限制 / 未实机验证

1. **仍没在实机复现过**：全部证据是服务器日志 + diag + 源码时序推演 + 自检锚点。真正的验收是玩家再下单一次 20 件精密构件，看订单完成后置物台上是否还留着带进度组件的过渡件；以及重导 diag 后 `chambers[]` 非空、`inFlightUnits[]` 里 `group` 是否包含置物台。
2. **`inFlightUnits` 落盘早在第 121 轮 F4 就已落地（2026-11-08 更正本文件原表述）**：`saveAdditional` → `writeInFlightUnits`（`:17603` / `:17701`）、`loadAdditional` → `readInFlightUnits`（`:17614` / `:17756`），NBT 键 `TAG_IN_FLIGHT_UNITS = "InFlightUnits"`（`:3113`），每条只存 5 个字段 `Station(long 打包坐标) / Recipe / Step / Item(注册名) / Since`，条目非法即淘汰并 `LOGGER.warn`，空表不写键、旧档无键时不补写，读侧上限 `MAX_PERSISTED_IN_FLIGHT_UNITS = 64`（`:3122`）；读回后三条回收路径复用同一份 `inFlightUnits`（判据未变）。证据：`tools/selfcheck_round121_inflight_persist.py` **48 断言 / 15 反例 / 0 失败**；beta.34 jar 的 `SequenceExecutionChamberBlockEntity.class` 常量池含 `InFlightUnits` / `writeInFlightUnits` / `readInFlightUnits`。**仍欠的**：没有做过"停服重启 → 读档 → 残留被收回"的实机验证（只能算代码级 + 自检级证据）。
3. **清扫速度有界**：`DEAD_STATION_CLEAR_INTERVAL_TICKS=40`、每轮 1 件 ⇒ 最坏约 4 件/分钟；如果玩家一次留下十几件残留，要等几分钟才清完。这是有意的（宁可慢，不可乱搬），不是 bug。
4. **diag 的玩家半径上限 16 区块**（`min(16, viewDistance)`）：如果导出时玩家离基地超过 16 区块，仍然拍不到；正确做法是站在基地里导。
5. 本次服务器 `devLogs` 只在导出瞬间开了约 40ms（22:52:28.524→.563），所以日志里没有任何装配 trace；下次实机复现请先 `/rs_create_compat devlogs on` 再下这单，`/rscc-assembly` 行才是完整证据链。

---

## 6. 给玩家的操作速查

- 复现验收：站到基地执行 `/rs_create_compat devlogs on` → 下 20 件精密构件 → 做完后 `/rs_create_compat diag run` → `/rs_create_compat devlogs off`。看两处：①置物台上有没有"未完成精密构件/未完成坚固板"之类带进度条的件；②新 diag 里 `chambers[].inFlightUnits[]`。
- 如果旧世界里已经有一块卡住的过渡件（升级前留下的）：本轮**不会自动清历史残留**（重启后登记已丢，属于 §5.2 的限制）；暂时只能手动挖掉置物台上的件。
