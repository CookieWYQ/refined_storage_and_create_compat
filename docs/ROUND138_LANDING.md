# 第 138 轮落地记录（1.2.0-beta.39）

> 本轮范围来自用户 2026-11-20 22:07 的原话：
> 「A肯定要先修。然后B的话呢肯定也要修。然后C的话呢，挑那些影响玩法比较大的。
> 比如说像是你说的保持器的那一条，这个就是一个问题了。」
>
> - **A** = 与第 137 轮崩溃**同一机制**的「`isLoaded` 挡不住未 FULL 区块 + 阻塞读」全工程收口；
> - **B** = 范围充电器「每 tick 全量遍历范围内所有区块」的 TPS 开销；
> - **C** = 影响玩法较大的未修项 —— 本轮选中**保持器 M3**（目标 0 仍认领，导致两台保持器时一台静默失效）。

---

## 1. A：区块安全读统一收口

### 1.1 机制（一页讲清，防止后人删闸门）

1.21.1 的 `Level#isLoaded(pos)` 走 `ChunkSource#hasChunk`，判断的是「**chunk holder 存在**」。
一个**正在生成 / 加载流水线里、但还没到 FULL** 的区块同样返回 `true`。紧跟其后的
`level.getBlockState / getBlockEntity / getChunkAt / getChunk(cx, cz)` 全是**阻塞读**：
区块没到 FULL 时会在主线程同步等它生成（还可能加票据继续拉区块）。玩家登录 / 传送 /
跨维度时区块批量加载，这一组合会把 tick 拖到几百毫秒；持续下去 `nextTickTimeNanos`
落后 60 秒，看门狗判死 —— 第 137 轮的服务端崩溃就是这个机制（当时修的是能量立方集群）。

第 137 轮只修了「崩溃栈上」的那一处；本轮把同一机制**在全工程收口**。

### 1.2 新增 `support/SafeChunkRead.java`（唯一判据）

| API | 实现 | 语义 |
|---|---|---|
| `chunkNow(level, pos)` | `level.getChunk(x>>4, z>>4, ChunkStatus.FULL, false)` + `instanceof LevelChunk` | **只返回已到 FULL 的区块**；不加载、不加票据、不阻塞 |
| `hasChunkHolder(level, pos)` | `level.hasChunkAt(pos)` | 非阻塞判断「区块在不在加载范围里」，用于区分「没加载（跳过）」与「加载中（下一 tick 再看）」 |
| `blockStateNow(level, pos)` | `chunkNow` 后 `chunk.getBlockState(pos)` | 未就绪返回 `null` |
| `blockEntityNow(level, pos)` | `chunkNow` 后 `chunk.getBlockEntity(pos, CHECK)` | 未就绪 / 不存在返回 `null`，**绝不创建方块实体** |

约定：逐 tick / 循环里读邻块一律走本类；读到 `null` 按「这一 tick 看不见」处理，
**不许**为了拿到值退回 `isLoaded + getBlockState`。

### 1.3 收口范围（逐文件）

| 文件 | 原写法 | 现写法 |
|---|---|---|
| `support/RsccMachineCluster.java` | `isLoaded` + `getBlockState` + `getChunkAt`（存活校验 / BFS / 卸块移交） | 安全读 + `partial` + 同 tick 只重算一次 |
| `block/entity/RangeChargerBlockEntity.java` | `isLoaded` + `getChunk`（普通 / 无限两条扫描） | `chunkNow` |
| `block/entity/SequenceExecutionChamberBlockEntity.java` | 12 处 `getChunkAt(...).getBlockEntity(CHECK)` + `isLoaded` 闸门 | `blockEntityNow` / `blockStateNow` |
| `block/entity/CollectionCacheBlockEntity.java` | `fluidSourceIdAt` 的 `isLoaded + getBlockState`（第 119 轮 C-6 遗留） | `blockStateNow` |
| `block/entity/FEEnergyCubeBlockEntity.java` | 邻块推电 `isLoaded + getBlockState + getBlockEntity` | `blockStateNow` + `blockEntityNow` |
| `block/entity/DecompositionGeneratorBlockEntity.java` | 邻块推电 `isLoaded + getBlockEntity` | `blockEntityNow` |
| `block/entity/NetworkResourceRequesterBlockEntity.java` | 待投目标 `isLoaded + getBlockEntity` | `blockEntityNow` |
| `support/RsccWireLinkSearch.java` | BFS / 预检的 `isLoaded + getBlockState`，`getChunkAt` | 安全读 |
| `support/RsccChamberImportStrategy.java` | 物品 / 流体能力查询 `isLoaded + getBlockEntity` | `blockEntityNow` |
| `support/RsccStationProbe.java` | 工位探测 4 处 `isLoaded + getBlockState` | `blockStateNow` |
| `support/SeparationFrameGuard.java` | 接缝判定 4 处 `isLoaded + getBlockState` | `blockStateNow` |
| `support/RsccSheathChain.java` | 族别判定 `isLoaded + getBlockState` | `blockStateNow` |
| `support/RsccCamouflage.java` | 附件读取 / 补发同步 `isLoaded + getBlockEntity` | `chunkNow` / `blockEntityNow` |
| `support/RsccCableCuts.java` | 可切断判定 `isLoaded + getBlockState` | `blockStateNow` |
| `support/RsccBusLinkInvalidation.java` | 邻块失效 `isLoaded + getBlockState` | `blockStateNow` |
| `compat/crafter/OutputReclaim.java` | `isLoaded + getBlockEntity` | `blockEntityNow` |
| `compat/crafter/MechanicalCraftPlan.java` | 动力合成器查找 `isLoaded + getBlockEntity` | `blockEntityNow` |
| `compat/crafter/CreateCraftTargets.java` | 自动合成仓链 / 目标解析 2 处 | `blockEntityNow` |
| `network/SetChamberBindingPacket.java` 等 3 个包 | `isLoaded + getBlockEntity` | `blockEntityNow` |
| `item/AssemblyPatternRebind.java` | 邻近执行舱搜索 `isLoaded + getBlockState + getBlockEntity` | 安全读 |
| `item/AdvancedRemoteTerminalItem.java` | 绑定位置解析 `isLoaded + getBlockEntity` | `blockEntityNow` |

**全工程 `getChunkAt(` 调用清零**（`grep -rn "\.getChunkAt(" src/main/java` 无结果；自检 ④-1 守着），
因为它就是「require=true 的阻塞读」最隐蔽的入口。

### 1.4 语义变化（只这一处，且方向安全）

**机器集群**：BFS 现在只认「所在区块已到 FULL」的成员；某个邻块在流水线里但没到 FULL 时，
本轮先把该集群记为 `partial`：

- `partial` 的集群恒不视为 fresh ⇒ **下一 tick 必然重算**，区块到 FULL 后成员自动补上；
- 同一 tick 内多个成员各调一次 `ensure` 时，`lastAttemptTick` 保证**只重算一次**（不会 N 台机器各跑一遍 BFS）；
- 起点自身区块还没到 FULL 时 `collect` 返回空，**但绝不清空旧缓存**（否则加载中的每一 tick 都会拆一次集群）。

其它路径（工位探测 / 充电 / 交互 / 包 / 物品）读到未就绪一律返回「这一 tick 看不见」，
与原先 `isLoaded == false` 的分支完全一致；调用方本来就在下一 tick 重试。

### 1.5 明确不改（避免误伤）

- `client/SheathRenderer.java`：客户端渲染路径，读的是 `ClientLevel` / 渲染层提供的区块，
  不存在「服务端同步等区块生成」的问题；
- `SchematicLoaderBlockEntity` 里的 `reader.getBlockState(...)`：`reader` 是**蓝图读取器**
  （`SchematicReader` 一类），不是 `Level`；`printer.isLoaded()` 是 Create 装填器的 contraption 状态，
  与区块加载无关 —— 这两个是第 137 轮自检反例里点名的「别按字符串误判」的例外。

---

## 2. B：范围充电器的区块扫描改为「每 tick 预算 + 轮转」

### 2.1 问题

`RangeChargerBlockEntity` 的两条方块扫描都是**每 tick 全量**遍历范围内所有区块、并逐个
遍历 `LevelChunk#getBlockEntities()`：

- 普通范围：单轴上限 100 格 ⇒ 每轴 7~8 个区块（最多 64 个区块/tick/台）；
- 无限范围（`creative_range_upgrade`）：**每个在线玩家 33×33 = 1089 个区块/tick/台**
  （10 人 ≈ 1 万区块/tick/台）。

`rangeChargerChargeBlocks` 默认 **true**，所以这不是「只有开配置才有」的开销。

### 2.2 修法

| 常量 | 值 | 语义 |
|---|---|---|
| `MAX_FINITE_SCAN_CHUNKS_PER_TICK` | **128** | 普通范围每 tick 最多扫 128 个区块；默认 50 格（16 区块）/ 100 格（最多 64 区块）⇒ **与旧行为逐 tick 等价**，只有叠满范围升级（单轴最多 250 格 ≈ 289 区块）才分 3 轮 |
| `MAX_INFINITE_SCAN_CHUNKS_PER_TICK` | **256** | 无限范围每 tick 的总预算（覆盖所有玩家）；1 人 1089 区块 ⇒ 约 5 tick 扫完一轮，10 人约 43 tick |

- `blockScanCursor`（字段，跨 tick 记忆）把「本轮从哪个区块下标开始」记下来，窗口扫完就前移；
- 提前因 `maxTargets` / 电量耗尽退出时，游标仍**前移整个窗口** —— 否则「每 tick 都在前几个区块达到上限」
  会把后面的区块永远饿死；
- 候选池（`seenBlocks`，供过滤界面「世界中的方块」页）**只在 `start == 0`（新一轮轮转开始）时清空 +
  登记自身**：旧实现每 tick 清空，分片后若照旧清就会让界面只剩「本 tick 的碎片」。

### 2.3 取舍（如实写）

- 普通范围：默认配置下**无行为变化**；
- 无限范围（创造升级专用，非生存物品）：单个目标的 FE/t 约为「每 tick 一次」的
  `1 / ceil(总区块数 ÷ 256)` —— 1 人约 1/5。这是为了把 1089 区块/tick 降到 256 而接受的代价；
  该档本来就是创造模式调试/整活用的，不参与生存经济。若后续认为不可接受，可把预算调到 512 或
  改成「每 tick 只扫玩家周围 5×5 的核心区 + 外围低频轮转」。

---

## 3. C：保持器 M3 —— 目标 0（未标记）不再认领

### 3.1 现象（来自第 118 轮复核清单 M3）

同一网络里两台保持器守**同一资源**时，仲裁按「坐标全序 → 机型 → 类名」选唯一权威：
坐标更靠前的那台赢，输的那台**让位**（不合成、不销毁，只搬运）。如果坐标更靠前的那台
**根本没标记该资源**（目标量 = 0），它仍然会「认领」这个资源键 ⇒ 真正标记了目标的那台永远让位 ⇒
**功能静默失效**（界面看起来正常，资源就是不被维持）。

### 3.2 根因

`claimedResources()` 只按「有没有解析出资源键」认领，没有看「目标量是否 > 0」：

- 基础版：`networkResourceKey()` 非空即认领；
- 高级版：每个槽 `networkResourceKey(slot)` 非空即认领。

### 3.3 修法

认领口径与 `KeeperTarget.isMarked(target)`（**唯一实现**：`target > 0` 才算标记）同源：

- 基础版：`targetAmount > 0` 才认领；
- 高级版：逐槽 `targets[slot] > 0` 才认领。

### 3.4 边界

- 只改「仲裁认领」，**不改**维持 / 销毁 / 补合成的任何判据 —— 未标记的保持器本来就不参与这些动作
  （它们用的是 `KeeperTarget.isMarked`），本轮只是把「让位」这一侧也对齐；
- 未标记槽在界面上仍显示原设定值（0 就是 0），不会因为不认领而丢失任何存档数据；
- 两台都未标记 ⇒ 都不认领 ⇒ 都不让位、都不动作，与旧行为一致（本来就不该动作）。

---

## 4. 自检

- 新增 `tools/selfcheck_round138_safe_chunk_read.py`：**22 条断言 / 0 失败**
  （SafeChunkRead 判据 / 集群 partial+节流 / 充电器预算+游标+候选池 / 全工程 getChunkAt 清零 /
  两台保持器的 isMarked 锚点 / 四组旧写法反例自证）；
- 第 137 轮自检（能量立方集群）仍通过；
- **11 个既有自检的「旧写法字面量锚点」按新形状同步**（判据没有放松，反例同步换写法）：
  `round100`（绑定解析含 isLoaded ⇒ 改 SafeChunkRead）、`round101`（D29/CE11 推送改安全读）、
  `round110`（候选池改为整轮重建）、`round119`（isLoaded 闸门升级为安全读，且把「旧 isLoaded 写法」
  本身列为反例）、`round124`（A-7/A-11/C-3/C-4/C-9 跟随分片语义）、`round25` / `round42` /
  `round59` / `round68` / `round71` / `round99`（集群/邻域/供料/续投/立方推送的安全读锚点）；
- 全量自检：**206 脚本 / 0 失败**（`build/round138_selfchecks.log`）。

## 5. 构建 / 探针 / 部署

| 项 | 结果 |
|---|---|
| 独立 javac（不信任 gradlew 结论） | `LEAD_COMPILE_OK sources=527`（新增 SafeChunkRead 后 526 → 527） |
| 构建 | `BUILD SUCCESSFUL`；`rs_create_compat-1.2.0-beta.39.jar`（2,962,217 B） |
| **专用服务端真启动** | **PASS**：`Done (9.279s)!`；`[rscc-build] version=1.2.0-beta.39 … sources=527`；exit 0 |
| **客户端真启动** | **PASS**：`mods - versions {1.2.0-beta.39}` + `Backend library: LWJGL 3.3.3+5` + 探针目录无 crash-report + `run/` 前后清单一致 |
| 部署 | 四处 sha256 全等 `08f57caf…0c3694`（build/libs / 本地实例 / SFTP `/mods` / `/client_files/mods`），beta.38 在三处 `.disabled` |


## 6. 未做 / 已知边界

1. **没有实机存档复现**：本机没有用户的存档；A 的结论来自 1.21.1 源码语义 + 第 137 轮崩溃数字 +
   本轮自检，B 的结论来自源码成本模型。beta.39 起若再出现慢 tick，日志里已有的
   `能量立方集群重算耗时 … ms` 仍是证据（能量立方那条链第 137 轮已修）。
2. **C 里其余项**（本轮未做，按用户「挑影响大的」口径留到后续）：
   - L2 缓存仓无电仍执行存量迁移（只网络内搬运，不复制不销毁）；
   - L3 带组件流体的 `drain` / `fill` 组件编码不对称（「取不出来」，静默回退网络）；
   - `ROUND114/115` L1：REPLACE 合并后超过 288 条被 `sanitizeEntries` **静默截断**（无日志）；
   - `ROUND117` M1：标记库只有条数限、没有体积限；
   - `ROUND120` F2 (b)(c)：跨资源「合计超容」与「模拟→执行」竞态仍会落地部分资源；
   - 48 格窗口 JEI 拖入「新 id 被忽略」（`ROUND125` §4.6，文档仍是 `<待填>`，未确认修没修）；
   - 保持器 H2 / M1 / M2：需要产品决策（网络镜像虚高是否改设计 / 目标上限 / 默认值口径）。
3. **C 之外的已知 UI 问题**：「高级远程多功能终端偶尔消失的『选择』按钮」按用户要求**先挂起**，
   仍需要一张截图或界面名才能定位。
4. **无限范围充电的 FE/t 取舍**见 §2.3。
