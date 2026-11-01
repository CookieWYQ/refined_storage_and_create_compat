# 第 127 轮（beta.28）：能量立方「电池档」—— 一面墙就是一块电池，充电器接上去吸的是整簇

> 用户 beta.26 实机口述（本轮原话，2026-11-01 07:24 微信语音转写，逐句拆成可验收项）：
> 「继续。同时我之前提到过的那个——将 F1 能量立方视为一个完整集群。操作的时候，将所有相连的方块
> 视为一个整体，只有在破坏的时候，才把它单独看作一个个个体，准确分离它实际存的那个电量……
> 除了我破坏这个方块的时候，其他时候对这一个方块的输电量的输入输出，全都应该是所有这种方块
> 合在一起进行操作，就是集群操作嘛。」
> 「然后呢，它本身也相当于是一个导线，也可以传递能量的。就比如说我全部放在那么多，然后我随便
> 拿一个范围充电器接在它上面。实际上范围充电器吸取的是整个集群中的所有电量。」
> 「以及那个发电机——那个发电机如果说它副产物满了，那么他就无法再进行发电了，就是直接卡在了，
> 也相当于堵塞。」

## 0. 一轮话总结

| # | 用户要点 | 结论 | 证据 |
| --- | --- | --- | --- |
| ① | 立方输入输出按**整簇**结算；只有**破坏**才分个体 | **本来就是对的**（第 124 轮已审计 + 71 断言钉死）。本轮再补「导线」相关断言 | `tools/selfcheck_round124_energy_cluster.py`（71/21）+ 本轮 A-7 |
| ② | 立方本身是**导线**，可以传递能量 | **本来就是对的**：六面能力 = 整簇视图；向邻居推电时**取整簇存量、扣整簇**；不推给别的立方 | 本轮 A-4 / A-4b / A-5 / B-4 |
| ③ | 范围充电器接上去，**吸的是整个集群的电量**；一面墙应该**瞬间被充满** | **本轮真改**：立方目标从「工具档 500 FE/t」改成「**电池档 10 万 FE/t**」（`cubeTransferBudget()`）。12 块墙 20M 缺口从 **2.8 分钟 → 17 tick（≈0.85 秒）** | 本轮 A-1/A-2/A-3 + B-1/B-1b/B-2/B-3 |
| ④ | 发电机**副产物满了就不能再发电**，直接卡住（堵塞） | **本来就是对的**（第 123 轮 `outputFull`）：进度停在 `total-1`、不发电、不扣输入、不产出；清空后**自动恢复** | 本轮 C-1…C-5（模型 + 源码锚点） |

**本轮验收**：`LEAD_COMPILE_OK sources=508`；全量自检 **190 个脚本 / 0 失败**；新增
`tools/selfcheck_round127_cluster_conductor.py`（**34 断言 / 10 反例**）；受影响旧脚本 3 个补锚点
（`round52` 8.4 / `round124_charger_candidates` A-10 / `round124_energy_cluster` C-1），
并修好 `selfcheck_gui_slot_alignment.py` 的两个 `detect_all()` 调用签名（TypeError，见 §7）；
`rs_create_compat-1.2.0-beta.28.jar`（**2,889,179 B**，sha256
`c58a62237fd1bec19070b96df411e5a07ce7c78d89f73fca0243ff2fb5c214e1`）。**未实机验证**（见 §8）。

---

## 1. 先分清「容量口径」与「速率口径」—— 用户这次骂的其实是后者

### 1.1 容量口径（用户 ① ②）—— 第 124 轮就审计过，是对的

`FEEnergyCubeBlockEntity` 里有两套**故意不同**的数值口径（类注释 L31-38 写死了理由）：

| 口径 | 实现 | 报什么 | 为什么 |
| --- | --- | --- | --- |
| RS 网络节点 | `FEEnergyCubeNetworkNode#getStored/getCapacity` | **本块** | 网络能量组件会逐 provider 求和，节点再报整簇就是平方级虚增 |
| 六面 FE 能力 | `ClusterEnergyCapability`（`getExternalEnergyStorage()`） | **整簇** | 外部机器从任意一面看 = 一块大电池（用户口径） |

- 收电：`receiveEnergy` → `receiveIntoCluster` → `Snapshot#receive`（先填 preferred，再按字典序填其它成员）；
- 放电：`extractEnergy` → `extractFromCluster` → `Snapshot#extract`（按字典序跨成员扣，只累计**实际扣掉量**）；
- 拆方块：`FEEnergyCubeBlock#getDrops` 只把**本块** `getOwnEnergyStored()` 写进掉落物
  （`BlockEntity#saveToItem`），整簇其它成员一格不动。

⇒ ①② 三条**在源码里本来成立**。第 124 轮已经用 71 条断言（含 21 条反例）钉死；
本轮新增的 A-4/A-4b/A-5/A-6/A-7 只是把「导线」这一侧补进同一份账。

### 1.2 速率口径（用户 ③）—— 这才是「只看到一块在慢慢吸」的真身

第 124 轮的结论里已经写明（`docs/ROUND124_USER_FEEDBACK_LANDING.md` §4-2）：集群语义没问题，
卡住的是**默认速率**。旧代码里 `chargeBlock` 对所有目标一视同仁：

```java
final int transfer = transferBudget();   // = min(getChargeRate(), 本机缓存 / maxTargets)
                                         // 默认 = min(500, 缓存/50) ⇒ 500 FE/t/目标
```

于是一片 12 块的立方墙（充电器覆盖 12 块）合计只有 **6,000 FE/t**：

| 场景 | 旧口径 | 本轮电池档 |
| --- | --- | --- |
| 12 块墙，缺口 2000 万 | 3,334 tick（≈2.8 分钟） | **17 tick（≈0.85 秒）** |
| 只覆盖 1 块，缺口 2000 万 | 40,000 tick（≈33 分钟） | 200 tick（≈10 秒） |
| 单块立方从 0 充满（5000 万） | 100,000 tick（≈83 分钟，单块） | 500 tick（≈25 秒，单块） |

数字全是模型算的（本轮 B 组），不是拍脑袋。

---

## 2. 改了什么（唯一一处源码行为改动）

### 2.1 `RangeChargerBlockEntity#chargeBlock`：立方走「电池档」

```java
// 第 127 轮：立方是电池，不是要充的工具
final int transfer = blockEntity instanceof FEEnergyCubeBlockEntity
    ? cubeTransferBudget()     // = min(本机缓存, Config.feEnergyCubeMaxTransfer)  默认 10 万
    : transferBudget();        // 工具档不变：玩家物品 / 掉落物 / 非立方方块
```

新增 `cubeTransferBudget()`（与 `transferBudget()` 并列，**语义对齐**）：

- **纯函数**：只读本机缓存，不扣电 / 不标脏 / 不登记高亮 —— 「先算预算 → simulate 判满 → 真收」
  的顺序（第 124 轮 R-1）**一格没动**；
- 缓存见底返回 0（调用方 `transfer <= 0` 早退语义不变）；
- 一次最多给到本机缓存，绝不透支。

### 2.2 为什么是 `feEnergyCubeMaxTransfer`（10 万）而不是别的数

那是**立方自己六面的输入/输出上限**（`ClusterEnergyCapability#transferLimit`），
也就是「一块电池的充电接口速率」。充电器给电池充电，本来就该按电池的接口速率，而不是按
「给工具充电」的 500 FE/t。改这一个数就能同时调立方六面与充电器的立方档，不会出现两套上限打架。

### 2.3 影响面（故意收窄）

| 目标 | 速率 | 有没有变 |
| --- | --- | --- |
| 玩家物品 / 饰品 / 掉落物 | `transferBudget()`（500 FE/t，按 maxTargets 均分） | **没变** |
| 非立方的方块（其它模组 FE 方块） | `transferBudget()` | **没变** |
| **FE 能量立方** | `cubeTransferBudget()`（10 万 FE/t，每块一份） | **变了（本轮）** |

被覆盖的每块立方各拿一份 10 万/t，而它们都往**同一个集群池**里写（`receive` 先填自己再填别人）
⇒ 观感就是「整面墙一起涨」。总流量仍受两道上限：本机缓存（一次最多给光）+ 网络抽取速率
`rangeChargerNetworkPullPerTick`（默认 10 万/t，补回来的速度）——**不产生任何额外能量**，
仓库存量守恒（B-1/B-3 都是守恒断言，不是「差不多」）。

### 2.4 怎么关掉 / 调回去

- 想整体调慢立方充电：把 `feEnergyCubeMaxTransfer` 调小（它同时是立方六面输入输出上限）；
- 想让立方回到旧口径：源码里把三元分支删回 `final int transfer = transferBudget();` ——
  本轮自检 **X-1 反例**就是干这个的，删了自检会判负（防止以后有人「顺手简化」）。

---

## 3. 「导线」这一侧（已存在，本轮钉死）

| 性质 | 落点 | 断言 |
| --- | --- | --- |
| 立方六面能力 = 整簇视图（含 null 面） | `FEEnergyCubeBlockEntity.registerCapabilities` 所有方向 + null 都返回同一个 `ClusterEnergyCapability` | A-5 |
| 向相邻方块推电时**取整簇、扣整簇** | `pushToNeighbours`：`available = getClusterEnergyStored()`、`extractFromCluster(accepted)` | A-4 |
| 立方之间**不互推**（簇内已共享容量，互推只是左右口袋搬运） | `pushToNeighbours` 里 `instanceof FEEnergyCubeBlock ⇒ continue` | A-4b |
| 从任一块抽电 = 从整簇扣 | `Snapshot#extract` 按字典序跨成员 | B-4（模型） |
| 破坏只带走本块电量 | `getDrops` → `getOwnEnergyStored()` + `saveToItem` | A-6 / A-6b |

所以用户描述的「范围充电器接在任意一块上，吸的是整个集群的电量」在**能力层**一直成立；
本轮只是把「吸得有多快」改成了电池档。

---

## 4. 发电机「副产物满了就堵塞」—— 源码 0 改动，本轮补可执行证据

第 123 轮重做时已经实现（`tickDecomposition` 的「三判」）：**进度到点前先掷骰 + 在副本上试放
4 格副产物**，放不下 ⇒ `outputFull = true; return;` —— 不推进进度、不结算、不消耗输入。
本轮把这条**写成可执行的模型断言**（C-1…C-5）：

| 判据 | 内容 |
| --- | --- |
| C-1 | 源码顺序：`productsFit` 失败 ⇒ `outputFull = true` + `return`（在 `settle` 之前） |
| C-2 | `settle` 内部顺序：写缓存 → 写产物 → 扣 1 件（不会「吃了植物却没产出」） |
| C-3 | 模型：副产物 4 格全满 ⇒ 跑 200 tick 后进度停在 `total-1`、电量 0、消耗 0、输入 16 格没动 |
| C-4 | `isOutputFull()` / `FLAG_OUTPUT_FULL` 仍对外（界面/诊断能说清「为什么卡住」） |
| C-5 | 模型：清空副产物后**下一 tick 自动恢复**（不需要重放方块） |

⇒ 用户 ④ 要的口径**已经是现状**，本轮交付 = 34 条断言里的 C 组 + 文档，源码未改。

---

## 5. 自检与旧锚点迁移

新增 `tools/selfcheck_round127_cluster_conductor.py`：

- **A 组（源码锚点，11 条）**：立方 battery 分支 / `cubeTransferBudget()` 纯函数 / R-1 顺序 /
  BLOCK 能力入口 / 导线推送 / 六面注册 / 掉落只带本块 / 集群池先填 preferred；
- **B 组（行为模型，5 条）**：12 块墙 17 tick 灌满、1 块覆盖 200 tick 灌满、工具档对照 3,334 tick、
  缓存不足按缓存给（守恒）、从任一块抽电扣整簇；
- **C 组（发电机，5 条）**：源码顺序 + settle 顺序 + 模型「卡住」+ 数据槽 + 自动恢复；
- **X 组（反例自证，10 条）**：把分支删掉 / 换成工具档 / 塞副作用 / 推送改本块 / 掉落改整簇 /
  注册只给 null 面 / 副产物满仍结算，逐一断言「必须判负」。

旧脚本迁移（**判据原意一处没放宽**）：

| 脚本 | 改了什么 | 为什么 |
| --- | --- | --- |
| `selfcheck_round124_energy_cluster.py` | C-1 从「`chargeBlock` 里不出现 `FEEnergyCube`」改成「仍必须走 BLOCK 能力 + 真收」 | 本轮新增了立方分支，旧断言把「任何立方特判」都禁掉了；反例 X-5（改走 ITEM 能力）仍判负 |
| `selfcheck_round52_charger_curios.py` | 8.4 的「`final int transfer = transferBudget();` ≥ 4 处」改成「≥3 处 + 1 处三元分支」 | 方块充电点现在是三元分支，但**仍是每目标一份预算、仍夹本机缓存**，不存在「一口吃光整罐电」 |
| `selfcheck_round124_charger_candidates.py` | A-10 的预算来源接受三元分支 | 同上；`预算 → if(transfer<=0) → simulate 判满 → 真收` 的顺序判据没动 |

另外修了一个**本轮之前就坏掉的**脚本：`selfcheck_gui_slot_alignment.py` 里 `detect_all()`
定义是单参（`detect_all(sig)`），两个调用点还在传两参 ⇒ `TypeError`。改成单参调用后
**117 条断言全过**（第 126 轮记的 117 条现在是真跑得出来的）。

---

## 6. 为什么不改「网络抽取」与「立方自身充电」这两条速率

用户 ③ 说的是「范围充电器接上去吸的是整个集群」——那两条路径本来就是这个语义，且各有自己的闸：

- `RangeChargerNetworkNode#doWork`：每 tick 最多从网络抽 `rangeChargerNetworkPullPerTick`（10 万）——
  这是防止「空缓存一个 tick 抽走 5000 万把同网络其它机器饿死」的闸（第 99 轮加），**不动**；
- 立方从网络池抽电：`feEnergyCubeNetworkPullPerTick`（1 万/t/块，且受「控制器 ≥90% + 网络在涨」双门控），
  **不动**。这两条与本轮的「充电器 → 立方」是不同路径，混在一起改会让能量账没法解释。

---

## 7. 本轮验收

| 项 | 结果 |
| --- | --- |
| 编译 | `BUILD SUCCESSFUL`，`sources=508` |
| 全量自检 | **190 个脚本 / 0 失败**（`_run_all_selfchecks.py`，bundled python） |
| 新脚本 | `selfcheck_round127_cluster_conductor.py`：**34 断言 / 10 反例 / 0 失败** |
| 专服探针 | **PASS**：日志 `Done (1.852s)! For help, type "help"`，无 Mixin / dist 致命标记，`run/` 前后清单一致（只读自证 OK） |
| jar | `rs_create_compat-1.2.0-beta.28.jar` **2,889,179 B**，sha256 `c58a62237fd1bec19070b96df411e5a07ce7c78d89f73fca0243ff2fb5c214e1` |

---

## 8. 未实机验证（如实写清）

1. **没有真机跑「12 块墙 + 一个充电器」看 17 tick 充满**：所有速率数字都是源码 + 模型推的
   （与源码逐句对应，但仍不是实机）。
2. **没有真机看「充电器吸整面墙」的观感**（哪一块先掉、界面数字跳变）。
3. **没有真机验证发电机副产物满的画面**：第 126 轮已经把那三行状态文字删了，本轮也没加回来；
   玩家现在只能从「箭头不走 + 副产物格满」自己判断。**要不要给一个非文字的提示**（例如副产物格红框
   / 悬停 tooltip）需要用户拍板 —— 按「删废话」的口径，本轮不动界面。
4. **多人 / 区块卸载边界**：立方簇跨区块时只有已加载成员计入（第 124 轮已写），本轮没动这块。
