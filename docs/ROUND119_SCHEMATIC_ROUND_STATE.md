# 第 119 轮：装填器「本轮蓝图身份」落盘（M2）+ 同 tick 集群 BFS 缓存（M4）

> 文件：`src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/SchematicLoaderBlockEntity.java`
> （本轮改后约 3200 行；下文行号均指改后的当前文件）
> 自检：`tools/selfcheck_round119_loader_round_state.py`（41 断言 / 10 反例 / 退出码 0）

---

## 1. 现象（M2）

每读一次档 / 每重载一次区块，紧贴加农炮的装填器就把**已经收集好的一整份材料全部回流网络**，
然后**从头再整份重拉一遍**。玩家看到的近似过程是：

1. 网络里的材料数量突然 `+N`（回流）；
2. 下一秒又 `-N`（重拉进装填器）；
3. 开了自动合成升级的机器还可能**再报一轮合成任务**（多花材料/时间）；
4. 如果开了开发日志（`RsccAssemblyDebug`），会看到一条
   `[loader attach] blueprint changed -> new round: xxx.nbt`，但玩家**根本没换图**。

材料最终不会丢（回流与重拉都走安全语义），但每次读档都白跑一轮：网络抖动、合成白做、
加农炮可能因为「材料刚被抽走」短暂缺料。单人重进存档、服务器重启、走远让区块卸载再回来，
都能稳定触发。

## 2. 根因（M2）

- `SchematicLoaderBlockEntity.java:162` `attachRoundBlueprint` 记录「本轮绑定的是哪张蓝图」，
  只在内存里存在；`attachRoundComplete`（`:166`）决定本轮是否已收尾。
- 旧 `saveAdditional`（现 `:3030` 附近）只写了 `AttachRoundComplete`；
  旧 `loadAdditional`（现 `:3087` 附近）也只读回它 —— **身份从来没有落盘**，读档后恒为 `ItemStack.EMPTY`。
- `doLoaderWork` 的换图判定（现 `:685-712`）：读档后第一个 work tick，
  `currentCannonBlueprint` 是加农炮槽里那张**物理蓝图**（共享视图，读档后当然还在），
  而 `attachRoundBlueprint` 是 `EMPTY`，于是
  `!ItemStack.isSameItemSameComponents(当前, EMPTY)` 为真 ⇒ 被当成「玩家换了蓝图」：
  - `:701` `recycleBlueprintMaterialsToNetwork(network)`：库存里全部材料回流网络；
  - `:703` `resetRoundCollection()`：`attachRoundComplete = false`、清空
    `autocraftRequested` / `standalonePullBudget`；
  - 随后 `:714` `cacheRequirementsFromBlueprint` + 补料分支把整份材料重新拉回来。

一句话：**「身份不落盘」+「空身份等于换图」= 每次读档一次假换图。**
蓝图槽本身是加农炮那张物理蓝图的共享视图，读档并不会让它变，所以这个判定从前提上就是错的。

## 3. 修法（M2）

### 3.1 身份落盘 / 读回

- 新 key `AttachRoundBlueprint`，写的是 **ItemStack 全量**（物品 id + count + data components）：
  `attachRoundBlueprint.save(registries)`（`:3034-3036`，只在该身份非空时写）。
  选全量而不是「只存文件名 / 只存物品 id」：判据 `ItemStack.isSameItemSameComponents` 看的就是
  item + components，存少了会在「同名但锚点/部署标记不同」的蓝图上误判成同一张。
- 读回用仓库既有写法 `ItemStack.parseOptional(registries, tag.getCompound(...))`（`:3093-3100`），
  与 `InventoryProxyBlockEntity` 等一致：**非法物品 id 只会得到 `ItemStack.EMPTY`，不会抛异常**；
  只有 `isValidBlueprintStack(...)`（Create `schematic` 物品）通过时才承认这是「本轮身份」，
  否则置 `attachRoundIdentityUnknown = true`。

### 3.2 读档后不再误判「换图」

`doLoaderWork` 的身份判定改成四路（`:685-712`）：

| 加农炮槽 | 身份状态 | 行为 |
| --- | --- | --- |
| 空 | 任意 | 清空身份 + `resetRoundCollection()`（**与旧行为一致**） |
| 非空 | 身份未知（旧档缺 key / key 非法） | 以槽里这张 `.copy()` **接管**身份；不回流、不复位；`attachRoundComplete` 沿用存档值 |
| 非空 | 已知且 `isSameItemSameComponents` 为真 | **什么都不做**：沿用存档的 `attachRoundComplete`，不回流、不重拉 |
| 非空 | 已知且不同 | 真换图：`recycleBlueprintMaterialsToNetwork` + 更新身份 + `resetRoundCollection` + `updateChecklist`（**与旧行为一致**） |

「接管」分支（`:691-698`）是给旧档的兼容路径：旧档没有任何身份证据，此时**不能**把
`EMPTY != 当前蓝图` 当成换图（否则旧档依旧每次读档回流一次）。接管只写身份缓存并 `setChanged()`
让它尽快落盘，之后的真实换图仍走原分支。
为什么接管是安全的：存档是一次全量快照，加农炮槽里的蓝图与本轮身份是同一次保存的，
不存在「槽里是图 A、身份是图 B」的正常状态；缺 key 只说明「缺证据」，不说明「换过图」。
注意空槽分支**不会**调用 `recycleBlueprintMaterialsToNetwork`（旧行为也不调用；
材料回流由后面的 `autoRecycle` / `recycleLeftoverMaterials` 负责），所以「读档时槽是空的」
不会触发这条误回流。

### 3.3 身份缓存绝不回填库存

`attachRoundBlueprint` 只用于比较（字段注释 `:155-161` 已写明）。所有赋值只有三种：
`ItemStack.EMPTY`、`ItemStack.parseOptional(...)` 的新对象、`currentCannonBlueprint.copy()`；
没有任何 `insertInto*` / `setStackInSlot` / `pushItemsToNetwork` 用到它（自检 M2-5 + 反例 M2-C6
会拦住「把身份写回库存」这种复制物品的写法）。

## 4. 现象 / 根因 / 修法（M4：`collectCluster()` 每 tick 多次 BFS）

- **现象（性能面，无玩家可见报错）**：一次 server tick 里 `collectCluster()` 被调用十几次 ——
  补料计数（`countItemInCluster` → `getClusterInGuiOrder`）、自动打印判定
  （`isRoundCollectedForCannon`）、`findClusterCannon`、共享内存判定、开关同步、集群锁定判定等
  （`:406/421/436/842/981/1691/1709/2342/2936`，另有 `AdvancedSchematicLoaderBlockEntity:32/125`）。
  每次调用都做一次六向 BFS（每台机器 6 次 `Level#getBlockEntity`），集群大、机器多时是纯浪费。
- **根因**：`collectCluster()` 无缓存，但同一 tick 内「已加载方块的相邻关系」不会变。
- **修法**：
  - 新增实例字段 `clusterCache` / `clusterCacheTick`（`:176-188`），按
    `level.getGameTime()` 缓存**本 tick** 的 BFS 结果（`:2447-2479`）；
  - 命中条件必须同时满足「缓存非空」与「gameTime 相等」⇒ **跨 tick 一律不复用**。
    区块加载/卸载边界上邻块可能整 tick 都查不到，缓存久了会把错误拓扑固化；
  - 缓存值存 `java.util.List.copyOf(finalizeCluster(all))`（`:2476`）—— **不可变副本**，
    调用方拿到的是同一份不可变列表，改不到缓存内部；需要排序/去重请自己
    `new ArrayList<>(...)`（`computeClusterInGuiOrder` 已经是这么做的，`:2342`）；
  - `level == null`（读档构造期 / 客户端占位）直接返回 `List.of(this)` 且**不写缓存**
    （`:2449-2453`），避免 `Long.MIN_VALUE` 哨兵与缓存 tick 撞上。
- **失效点**：`invalidateClusterCaches()`（`:2486-2491`）一次清掉 BFS 与 GUI 两级缓存，调用处：
  - `rscc$clusterChanged`（`:2918`，集群重建回调 = 拓扑变化）；
  - `setRemoved`（`:2972`，方块被破坏 / 区块卸载）；
  - `loadAdditional`（`:3121`，读档 / BE 复用）。
- **与 `getClusterInGuiOrder` 的关系（谁复用谁）**：`getClusterInGuiOrder` 是**下游** ——
  自己的同 tick 缓存 `guiOrderCache` 未命中时才调用 `computeClusterInGuiOrder()`，
  后者再调用 `collectCluster()`（命中本方法的 BFS 缓存）。所以同一 tick 内 BFS 真正只跑一次、
  GUI 顺序最多再算一次；拓扑变化时两级缓存一起作废，跨 tick 两级都不复用。
  这段关系已写进两处注释（`:176-188`、`:2315-2327`）。
- 缓存只存**成员引用**，不存库存/队列实例：集群重建换的是 `inventory` 对象，成员列表仍有效；
  各台当前的库存字段仍然每次现取（与 `guiOrderCache` 原有注释同一口径）。

## 5. 边界

1. **旧档**：没有 `AttachRoundBlueprint` 时按「身份未知」接管，不回流、不复位；
   接管发生在第一个有加农炮且槽里有蓝图的 work tick（加农炮未加载时自然不触发）。
   已知的代价：若旧档期间玩家在装填器不 tick 的时段（停机/区块边界）把蓝图换成了另一张，
   接管会沿用旧的 `attachRoundComplete`，可能认为「已收尾」而不补料；玩家把蓝图取出再放回
   即可按真正的新一轮重建（与现有换图路径一致）。这一取舍是为了满足「旧档不得误触发回流」。
2. **非法身份数据**：`parseOptional` 失败 → EMPTY → `isValidBlueprintStack` 失败 → 身份未知 →
   接管，不抛异常；损坏的 key 不会变成「换图」。
3. **加农炮槽为空**：身份清空 + `resetRoundCollection`（旧行为）；之后放入蓝图按新一轮处理
   （回流 + reset，旧行为）。
4. **集群镜像成员**（不紧贴加农炮）：`doLoaderWork` 在更早处 `return`，不维护本轮身份；
   身份只由紧贴加农炮的那台维护，与 `attachRoundComplete` 的既有归属一致。
5. **队列部署**：同一 tick 里队列把下一张部署进加农炮槽，随后的身份判定仍按新蓝图走「换图」分支
   （更新身份 + 回流 + reset），不受本次改动影响。
6. **打印期间身份是否漂移**：Create 的 `SchematicannonBlockEntity#initializePrinter` /
   `finishedPrinting` 只**读**蓝图槽 0 的组件（失败/完成时清槽），不会原地改组件；
   本仓库解析 blueprint 时一律先 `.copy()`。因此同一轮内身份稳定，不会出现「每 tick 都换图」。
   若第三方模组在打印中原地改蓝图组件，读档后仍可能被判成换图 —— 与旧行为一致，不会复制物品。
7. **计数**：`isSameItemSameComponents` 不比较 count；蓝图本身不可堆叠，不影响判据。
8. **M4 跨 tick**：`gameTime` 不前进时（`/tick freeze`）方块实体也不 tick，不存在「缓存无限期复用」
   的可达路径；客户端 `ClientLevel` 的 gameTime 同样每 tick 前进。
9. **没有**新增语言键 / 配置项 / 网络包 / C2S 入口；没有改落盘字段的语义
   （`AttachRoundBlueprint` 是本机作业状态，不受 `clusterOwnsPayload` 的单点落盘边界影响，
   每台各存各的，读回也绝不回填库存）。

## 6. 未验证（诚实说明）

- **没有实机读档复现**：本轮没有启动 Minecraft 客户端/服务端，没有真实存档的「保存 → 退出 →
  进入 → 重载区块」验证；「读档后不再回流 / 不再重拉」目前只有源码锚点与行为模型支撑。
- **没有编译**：按本轮流程由 Lead 统一编译；本文件只做了静态自检，未跑 `gradle`。
- **没有性能采样**：M4 的收益（每 tick BFS 次数、`getBlockEntity` 调用数）没有 profiler / Spark 数据；
  自检里的「12 次调用 → 1 次 BFS」是行为模型，不代表真实调用次数固定为 12。
- **没有实机坏档测试**：`ItemStack.parseOptional` 对「未知物品 id / 坏 components」的容错来自仓库既有
  用法与 MC 实现约定，本轮没有手工构造坏 NBT 存档验证。
- **没有覆盖组合场景**：集群（基础+高级相邻）与队列运行中读档的时序、读档后继续打印时材料是否够、
  自动合成升级在读档后的额度行为，均未实机验证。
- **打印中途读档的补料语义变了（按要求）**：如果存档时正在打印、材料已被 Create 消耗掉一部分，
  读档后因 `attachRoundComplete=true` 不会补料（旧实现靠假换图复位反而会补满）。这是「沿用存档判据」
  的直接结果，但「打印中途读档能否顺利打完」没有实机验证，若实机出现缺料停住，需要另行决定是否
  在「本轮已收尾但加农炮仍在打印」时做一次性补料。
- **未测试客户端**：客户端 `loadAdditional`（若 BE 同步路径触发）也会置身份/作废缓存，
  客户端不跑 `doLoaderWork`，理论上无影响，但未实机确认菜单表现。

## 7. 自检

```bash
"c:/users/70432/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe" \
  tools/selfcheck_round119_loader_round_state.py
```

结果：**41 条断言（其中反例 10 条），失败 0，退出码 0，末行 `ALL_PASS`**。覆盖：

- **M2 源码锚点**：新 key 落盘（非空才写）、`parseOptional` 读回、四路分支顺序、真换图仍是旧行为、
  身份缓存不进任何写库存/写网络调用、`AttachRoundComplete` 仍成对、`isSameItemSameComponents` 判据；
- **M2 行为模型**：读档后同一张 20 tick「回流 0 / 复位 0 / complete 保持」；components 不同算换图；
  读档后真换图「回流 1 + 复位 1」；旧档缺 key 接管；旧档 `complete=false` 继续本轮；
  空槽只复位不回流、再放入按新轮；身份比较不触碰库存；**反例**：旧实现模型读档后误回流 1 次；
- **M4 源码锚点**：命中条件带 gameTime、`List.copyOf` 不可变、三处失效点、实例字段非 static、
  只有一处 BFS 循环、GUI 顺序注释写明缓存关系、`computeClusterInGuiOrder` 先拷贝；
- **M4 行为模型**：同 tick 12 次调用 → BFS 1 次；跨 tick 重新 BFS；拓扑变化作废后重算、
  之后本 tick 内再复用；返回列表不可变（`append` 抛错）；GUI 顺序链路 12 次 → BFS 1 次 + 排序 1 次；
  **反例**：缓存不看 gameTime 时 5 个 tick 只 BFS 1 次（坏写法）。

## 8. 顺手发现（本轮未改，供后续排期）

1. **读档后可能重复播报「打印完成」**：`lastCannonEmptySchematicCount`（`:107`）与
   `lastPrintingDone`（`:124`）都没有落盘。若存档时加农炮输出槽里还留着一张没被回收的空白蓝图
   （自动回收关闭 / 网络满），读档后第一个 tick `emptySchematicCount(1) > last(0)` ⇒
   `printJustFinished = true`；Create 又把 `statusMsg` 落盘（`"finished"`），
   `cannonPrintPlacedAnyBlock`（`:1944`）会判成「真的放了方块」⇒ 再发一次完成横幅
   （`:622-635`），成就虽幂等但横幅会重复。若存档时 `statusMsg` 是 `schematicErrored` /
   `schematicExpired`，则会重复弹「未打印」提示（`:1951`）。建议把这两个哨兵一并落盘/首 tick 重建基线。
2. **`getClusterInGuiOrder` 返回的是可变缓存本身**（`:2328-2354`，`guiOrderCache` 由
   `computeClusterInGuiOrder` 里的 `ArrayList` 赋值）：当前调用方都只读，但任何调用方一旦
   `sort` / `removeIf` 就会污染本 tick 的缓存。可以改成返回 `List.copyOf(guiOrderCache)`
   （与 `collectCluster` 同口径），代价是每次多一次小列表拷贝。

---

## 9. Lead 收尾补充（同一轮内完成，§8 的两条 + 第 118 轮遗留的 L1 / L2 / L3）

> 本节由 Lead 在子代理交付后追加：§8 的两条「顺手发现」在**同一轮**就修掉了，
> 另外把第 118 轮复核留下的 L1 / L2 清掉，并对 **L3 做了勘误**（复核当时判断错了）。
> 自检脚本 `tools/selfcheck_round119_loader_round_state.py` 相应扩到 **71 条断言 / 17 条反例**。

### 9.1 打印完成哨兵落盘（§8-1：读档后重复播报完成横幅）

- 新增落盘字段：`LastEmptySchematicCount`（int）、`LastPrintingDone`（boolean）；
  `saveAdditional` 写入、`loadAdditional` 读回。
- 新增瞬态字段 `printBaselinePrimed`：**新档**有两个 key ⇒ 基线可信（true）；
  **旧档没有 key** ⇒ false，第一个 work tick 用**当时的输出槽数量**重建基线，且该 tick **不**判「刚完成」。
- `printJustFinished` 的判据改为 `printBaselineKnown && emptySchematicCount > lastCannonEmptySchematicCount`。
- 行为对照（自检 ④-9～④-13 + 反例 ④-C4 的模型）：
  | 场景 | 修前 | 修后 |
  | --- | --- | --- |
  | 新档正常打印一张 | 横幅 1 次 | 横幅 1 次（不变） |
  | 新档读档（输出槽残留 1 张、存档 done=true） | **横幅再播 1 次** | 0 次 |
  | 旧档读档（无 key、输出槽残留 1 张） | **横幅再播 1 次** | 0 次（首 tick 重建基线） |
  | 读档后再打印一张 | 1 次 | 1 次（修复没把正常播报吞掉） |

### 9.2 GUI 顺序缓存写入时即不可变（§8-2）

`guiOrderCache` 的赋值点改成 `java.util.List.copyOf(computeClusterInGuiOrder())` ——
**写入时**就不可变，调用方 `sort` / `removeIf` 再也污染不到本 tick 缓存；
**不在每次读取时拷贝**（本方法每帧会被按槽位反复调用，逐次拷贝反而变成新热点）。
自检 ④b-3 顺带核对了全部调用点（实体本体 + 两个菜单）都只读。

### 9.3 L2：删掉 `isCannonAdjacent` 里的恒假分支（死代码）

旧实现：`level.getBlockEntity(worldPosition) == cannon || 曼哈顿距离 == 1`。
`worldPosition` 是**本机**坐标 ⇒ 该方块实体恒为本装填器 ⇒ 前半段永远为假（死代码）。
删掉后只剩距离判据，**行为与旧实现等价**（自检 ⑤-1～⑤-3 + 反例 ⑤-C1）。

### 9.4 L1：完成横幅注释与实现对齐

旧注释写「绝不回退到 cannon.checklist」，但 `cacheRequirementsFromBlueprint` 在
「蓝图解析不出材料清单」时确实会**临时**把快照兜底成 checklist 的拷贝（`cachedRequirementsFull == false`），
之后每个 work tick 重试全量统计并在成功后升级。现在注释如实写明这个层次
（自检 ⑤-4 / ⑤-5 + 反例 ⑤-C2）。

### 9.5 L3 勘误：第 118 轮复核的这一条**判断错了**

第 118 轮复核写「注释称『速度 / 堆叠升级各最多 6 个』，实现是按槽保留 1 个」。
实测：`menu/UpgradeSlot.java` 的 `MAX_PER_UPGRADE = 6`，而 `dropBeyondKindCap` 的逻辑是
`kept >= max` 才弹出、否则 `kept++` ⇒ **保留前 6 个**。所以
**注释是对的、复核误报**。本轮补了自检 ⑤-6 / ⑤-7 把「常量 6 + 保留前 max 个」钉住，
避免下一轮又被误报一次。

### 9.6 未验证（与 §7 同口径）

本节四条同样**没有实机验证**：没有做真机「存档 → 退出 → 进入」验证横幅不重复、
没有实机摆一片区域看是否还会拉区块、没有实机验 GUI 缓存不可变（这条只有静态 + 调用点核对）。
