# 本轮工作报告 — 2026-10-04（第二轮）

两项任务：① 修「列车轨道单元样板：铁粒/锌粒候选未显示」；② 核查「精密构件齿轮堵塞产生多余中间产物」是否已解决。

---

## 一、列车轨道「铁粒 / 锌粒候选未显示」—— 已修

### 1. 根因（实机日志 + 存档双重实证，**不是查重问题**）

Create 的 `create:sequenced_assembly/track` 机械手步投入物是标签 `[c:nuggets/iron, c:nuggets/zinc]`（铁粒 **或** 锌粒）。
但整条「**样板物化**」链只有单值 `TAG_INPUT`，而它由 `SequencedRecipeProbe#stepInput` 取
`getItems()[0]`（= 铁粒）写入 ⇒ 候选组在**三层**同时丢失：

| 层 | 旧表现 | 为什么 |
| - | ----- | ----- |
| ① 单元样板物品 tooltip / 单元样板管理舱 | 只有铁粒 | `getTooltipImage(ItemStack)` 拿不到 `Level`，结构上无法回查配方 |
| ② 总样板 tooltip | 只有石头台阶 + 铁粒 | 与 RS 样板共用 `collectInputs` |
| ③ **RS EXTERNAL 样板（真正致命）** | 只登记 `minecraft:iron_nugget` | 实机日志 `PatternLayout[ingredients=[stone_slab×1, iron_nugget×2]]`，**全日志无一次 `zinc_nugget`** |

对照：执行舱 / 输出总线类别那一层（`assemblyStepInputGroups`）**本来就是对的** ——
于是「备料能拉锌粒」但「RS 样板不认锌粒」，两条路口径分叉。

查重不是元凶（「同配方同步骤」已挡住跨配方误判），但它**会掩盖修复**：`stepSkipDuplicate` 默认恒开，
日志三次出现 `terminal generate: all 2 unit step(s) skipped as duplicates`。

### 2. 修复（候选组随样板落盘 + 进 RS 样板的多候选 ingredient）

| 文件 | 改动 |
| ---- | ---- |
| `data/SequencePatternData.java` | 新增 `TAG_INPUT_CANDIDATES`；`UnitData` / `UnitEntry` 各加 `inputCandidates`（`candidatesOrRepresentative()` 兜底）；`normalizeCandidates` / `readCandidates` / `writeCandidates` / `candidatesOfItems`。**候选数 < 2 时不写 tag** ⇒ 老存档格式与语义一字不变 |
| `support/SequencedRecipeProbe.java` | 新增 `applicationCandidatesOf(recipe, declared)`：取「declared 所属那一组」的全部候选（多 ingredient 的步不会把不同组混在一起） |
| `client/SequenceTerminalJeiPlugin.java` | JEI 转移时逐步骤算好候选组（那里拿得到 `Ingredient#getItems()` 全部候选） |
| `network/SetSequenceImportPacket.java` | 新增与 `stepInputs` **逐下标平行**的 `stepCandidates`（encode/decode 成对） |
| `menu/SequencePatternTerminalMenu.java` | 候选组随单元样板 NBT 写入 |
| `block/entity/SequencePatternTerminalBlockEntity.java` | 生成总样板时把候选组带进 `UnitEntry` |
| `item/SequenceAssemblyPatternItem.java` | `collectInputs` 从「按单个物品聚合」改成「**按候选组聚合**」；RS 样板改用 `PatternBuilder.IngredientBuilder`（`ingredient(amount).input(r1).input(r2).end()`）⇒ 铁粒与锌粒是**同一项 ingredient 的两个候选**；tooltip 标「（或 锌粒）」 |
| `item/SequenceUnitPatternItem.java` + `client/tooltip/UnitPatternTooltipComponent.java` + `client/ClientInit.java` | 单元样板 tooltip 带出并**轮播**整组候选（多候选显示「等 N 种（任一皆可）」） |
| `support/UnitPatternDedupe.java` | 查重把候选组纳入比较键；**任一方「候选未知」时不算差异** ⇒ 升级前的旧样板不会把新样板判重跳过，也绝不因此漏生成 |
| `client/screen/SequencePatternTerminalScreen.java` | 回落顺序改为「配方候选 → **样板落盘候选** → 单件代表物」；`stepRecipeByType` 多条命中时取「候选组更宽」的那条 |
| `resources/.../zh_cn.json`、`en_us.json` | 新增 `sequence_assembly_pattern.input_any`、`sequence_unit_pattern.input_any` |
| `tools/selfcheck_track_candidate_group.py` | 新增回归断言（45 checks） |

### 3. RS 侧依据（已反编译核对 `refinedstorage-neoforge-2.0.0`）

RS 的 `Ingredient` 本就是「**一个需求量 + 多个可选输入**」：

- `CraftingTree#calculateIngredient` → `IngredientState` 把候选排成可能性数组，逐个尝试（资源不足时 `cycle()` 换下一个）；
- `AbstractTaskPattern#calculateIterationInputs` 按输入顺序取候选 `min(needed, available)` 直到凑够需求量。

⇒ 把整组候选放进**同一项** ingredient 是 RS 语义内的用法；「代表物放首位」= 优先消耗顺序与旧行为完全一致。

### 4. ⚠️ 升级注意

RS 按 `(level, pattern UUID)` 缓存样板（UUID 由样板 NBT 决定）。
**已放出去的旧样板必须重新生成一次**（或重载世界）才会登记锌粒。

---

## 二、精密构件「齿轮堵塞 ⇒ 多余中间产物」—— 部分解决（零运行期验证）

### 1. 先更正旧交接文档的三处误记（全部经原文核对）

| 旧文档说法 | 代码实际 |
| --------- | ------- |
| 修复在 `AssemblyWatchdog`，加入 W1-W5 | 实现在 `SequenceExecutionChamberBlockEntity#hasWaitReason`（L2043-2076）；`AssemblyWatchdog` 类注释 L88-92 明写「**绝不改执行舱**」，它只管 RS 任务挂起 |
| 连续 **200 tick** 无推进才判卡住 | 判定阈值 = `STATION_STUCK_TICKS = 60`（L326）；**200 = `STATION_STUCK_REARM_TICKS`（L355），是判卡结论「粘住后」的慢重试间隔** |
| W1-W5 五条豁免 | 实际 W1-W4（挂起冻结 / 缺料等待档 / 补合成在途 / 可合成但网络缺它），**W5 已被显式删除**（L2006、L2073-2086） |

### 2. 判定：**部分解决**

- **BUS 通路（用户实机配置）**：推料侧 6 道互不重叠的闸门（`RsccChamberExportStrategy#transferItem` L289 / L292 / L308 / L320 / L327 / L345）+ 配额夹到 1 件；备料侧另有 5 处收紧。**未找到未设防路径。**
- 但**修复版代码从未在有产出的产线上跑过**：唯一加载过它的会话（`latest.log` 15:29-15:43）零生产流量（`bus_push=0` / `stuck=0`），且最新源码编译时间晚于该会话 ⇒ 严格口径下「问题消失」无法判定。

### 3. 残余风险（已写入 §6.1）

1. **FACE 输出模式整条未设防**：`tickEngine` 的 FACE 分支只有 `isInputBlockedByStep` / `holdsItem`，缺 BUS 那 6 道闸门 ⇒ 起步原料在「已被机器转成过渡件」时会再次投放，可能多开一件在制件。用户实机走 BUS，切 FACE 前必须先补。
2. **`EXECUTOR_OFFLINE` 仍误报**：`latest.log:575/604/746/765/777/806` 反复指向 `(-5,-60,10)` —— 该坐标上**并没有执行仓**（实机仓在 `-5,-60,6` / `-16,-60,10` / `-10,-60,10`）；玩家点继续后 2.4 秒再次判掉线。
3. 「200 tick 慢重试」会每 10 秒/工位故意再放行一次（有界，javadoc 承认）；缺料处置切「一直等待」会让卡住闸门永久失效（设计内）。

### 4. 关键取证局限（下一位接手必须知道）

- `RsccFlowLedger` **没有任何 Logger**，只经 `/rs_create_compat diag` 导出（`run/rscc_diag/latest/report.md` 停在 2026-10-02）⇒ **「没有失衡告警」= 根本没写日志，不是「没失衡」**。
- **该不变量结构上测不出「多余中间产物」**：多喂一份起步原料（`fromNetwork+1` 与 `toMachine+1`）与多产一件过渡件（`fromMachine+1` 与 `toNetwork+1`）都**恰好平账**。要测它只能新增指标「**在制过渡件数量 vs 订单量**」。
- `tools/verify_single_unit_supply.py` 对 `STATION_STUCK_TICKS` / `hasWaitReason` / `stationStuckOn` / `RsccFlowLedger` / `blockedByForeignStepExtra` 命中 **0**，且部分断言在旧日志下永不 FAIL ⇒ **不要把它当作运行期验证**。
- `build/diag_selftest/**/run/logs/latest.log` 是 `tools/gen_diag_fixture.py` 造的**合成夹具**，读日志的脚本可能跑在假数据上。
- 日志里没有 git hash / 构建时间，只靠 mtime 猜源码对应关系。

### 5. 要 100% 确认需做的实验（未做）

① 先编译再启动游戏（顺序不能反）；② BUS 模式跑精密构件，下单 1 件与 64 件各一次，并**故意**让机械手手里压着「错的那一步投入物」制造堵塞做对照；③ 断言 `bus_push create:cogwheel` 每 5 秒加权 ≤1、cogwheel 网络存量无单调递减、无 `stuck ... waited=` 的 66/120/180/240 阶梯、在制 `incomplete_precision_mechanism` 恒 ≤1 件；④ 边界实验：缺料处置切「一直等待」、输出模式切 FACE。

---

## 三、验证结果

```
manual_compile.ps1                    → COMPILE OK (323 sources)
audit_lang_keys.py                    → 问题总数: 0
check_payload_registration.py         → [OK] 80 个包全部注册
selfcheck_track_candidate_group.py    → SELFCHECK OK (45 checks)   ← 本轮新增
selfcheck_track_support.py            → SELFCHECK OK (40 checks)
selfcheck_round21_pattern_dedupe.py   → SELFCHECK OK (26 checks)
selfcheck_assembly_push_gate.py       → SELFCHECK OK (33 checks)
selfcheck_assembly_step_amounts.py    → SELFCHECK OK (17 checks)
selfcheck_assembly_bus_routing.py     → SELFCHECK OK (49 checks)
selfcheck_flow_conservation.py        → SELFCHECK OK (41 checks)
selfcheck_bus_scrap_and_pull.py       → SELFCHECK OK (88 checks)
selfcheck_round18_feedback.py         → SELFCHECK OK (58 checks)
```

**实机验证仍未做**：需要在编译之后启动游戏，重新生成一次列车轨道样板，确认 RS 任务行出现
`Ingredient[amount=2, inputs=[minecraft:iron_nugget, create:zinc_nugget]]`。
