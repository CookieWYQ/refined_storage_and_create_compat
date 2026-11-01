# 第 118 轮（1.2.0-beta.20）之二：蓝图加农炮装填器家族（独立只读复核 + 4 处修复）

> 基线：`1.2.0-beta.19`（第 117 轮）。本轮没有新的用户实机反馈，除 M1（单元样板库体积预算，见
> `ROUND118_UNIT_LIBRARY_BYTES.md`）外，另请一位独立只读复核员**对抗式**复核了一个此前没被专项
> 审过的家族：**蓝图加农炮装填器**（`SchematicLoaderBlockEntity` 约 3100 行 + 菜单 / 界面 / 方块 / 配方）。
>
> 复核员报 **2 高 / 4 中 / 3 低**；本轮修掉 2 高 + 2 中（M1 / M3），其余记录在 §4 留给下一轮。

---

## 1. 修掉的问题

### H1（丢物品）「队列掌管共享蓝图槽」时静默拒收 = 把蓝图直接销毁

- **位置**：`menu/SchematicLoaderMenu.java` 的蓝图槽 `mayPlace`；`SchematicLoaderBlockEntity.BlueprintView#setStackInSlot`。
- **机制**：蓝图槽绑定的是**加农炮那张蓝图本身**（共享视图）。队列流水线运行期间视图会拒收改动，
  旧实现是**静默 `return`**。但原版点击逻辑在调用 `slot.set` **之前**就已经把物品从玩家背包摘掉了：
  - 数字键 SWAP：`inventory.setItem(hotbar, EMPTY)` → `slot.setByPlayer(itemstack2)`；
  - Shift 点击：`split(1)` 先把 1 张从玩家背包分离出去。

  所以「静默 `return`」= 把已经离手的蓝图扔掉。
- **可达场景（正常玩法）**：加农炮北面一台**高级**装填器在跑队列、南面一台**基础**装填器
  （两台都贴着同一台炮但彼此不相邻 ⇒ 基础那台的 `clusterHasAdvanced=false` ⇒ 菜单 `mayPlace` 放行）。
  这时对基础装填器「数字键 / Shift 点击」塞蓝图就会丢。
- **修复**：
  1. 菜单 `mayPlace` 新增 `!loader.isSharedBlueprintSlotQueueLocked()` —— 原版**根本不会** detach；
  2. 视图 `setStackInSlot` 的拒收分支改为 `dropIntoWorld(stack)`（第二道防线：物品落回世界，绝不销毁，
     并留一条日志说明「被拒 + 已落地」）。
- **代价**：队列运行期间，贴着同一台炮的基础装填器不能从界面换蓝图（本来就该由队列掌管），
  文案与日志说明了原因；停止队列后可正常操作。

### H2（卡服 / 带宽）每 tick 无条件 `cannon.updateChecklist()`

- **位置**：`SchematicLoaderBlockEntity#doLoaderWork`。
- **机制**：Create 的 `updateChecklist()` 会 `printer.markAllBlockRequirements(...)` 遍历蓝图**全部坐标**、
  逐点读世界方块、`findInventories()` 重扫邻块容器，并在末尾置 `sendUpdate = true`
  ⇒ 加农炮随后 **每 tick 向周围客户端发一次方块实体更新包（20 包/秒，空闲也发）**。
  5000 方块的蓝图 ⇒ 每秒 10 万次 `shouldPlace` 世界查询。
- **修复**：只在「蓝图真的换了」分支刷新一次；队列部署下一张时也会刷新一次
  （我们自己调 `printer.resetSchematic()`，Create 下一个 tick 走 `initializePrinter` 时本来也会刷新 ——
  与 Create 自己的节奏一致：它只在 `initializePrinter` / 写字板打印完成时调用）。

### M1（空转 + 状态卡死）`takeFromQueue()` 写在「该不该部署」的判断外面

- **位置**：`SchematicLoaderBlockEntity#doLoaderWork` 的队列分支。
- **机制**：「加农炮蓝图槽空 + 队列有货」时每 tick「取出一张 → 原样放回」。玩家从**加农炮自己的界面**
  把蓝图取走即可进入该状态（Create 会把炮置 `STOPPED`/`idle`）：打印不开始、队列不推进、
  每 tick 还 `setChanged()` 标脏。
- **修复**：
  1. `takeFromQueue()` 只在「刚打完 / 队列刚启动」两个 tick 调用（**取出即部署**，不存在取出了却没归宿的中间态）；
  2. 因此删除 `returnToQueue()`（死代码 + 过期注释）；
  3. 新增「中途被取空」分支：队列还有货、炮已 `STOPPED`、蓝图槽空 ⇒ **自动退回「开始」状态**，
     并向 64 格内玩家发行动栏提示（新语言键 `…schematic_loader.queue_paused_empty`），
     **队列里的蓝图原样保留**（不销毁、不消耗）。
  4. 重启服务器后的正常续打不受影响：首 tick 的 `queueJustStarted` 为真，会先部署下一张。

### M3（全服刷屏）「网络无火药」提示发给全服

- **位置**：`restockGunpowderDirect`。
- **机制**：走 `getServer().getPlayerList().getPlayers().forEach(...)`，每 200 tick 一次，全服可见；
  与同文件里「距离口径 4096 = 64 格平方，看得到机器的人才收得到提示」自相矛盾。基地 6 台 = 每 10 秒 6 条。
- **修复**：改走 `notifyNearby(..., true)`（行动栏 + 64 格），与完成横幅同一口径。

---

## 2. 复核员已核对为 PASS 的面（本轮未动）

- **持久化**：`saveAdditional/loadAdditional` 字段一一成对；`Inventory`/`Queue` 受 `clusterOwnsPayload`
  单点落盘；格数按 NBT `Size` 自适应；无「读档截断玩家数据」路径。
- **C2S 写入面**：家族唯一自定义 C2S 是 `SetClusterRowPacket`（只有两个 `VAR_INT`，服务端双向钳制），
  没有接受客户端物品 / NBT / 字符串 / 坐标的入口 ⇒ 无「改包撑大落盘」路径。
- **破坏结算**：走统一 `BlockContentReleaser`（三档策略 + `handOverToSurvivors` 优先移交）；
  蓝图槽用**本机**槽而非共享视图 ⇒ 不会把加农炮那张蓝图当自己财产掉出来。
- **能量与红石**：RS 的 `calculateActive()` 要求能量足够，不存在「没电也白嫖」。

---

## 3. 自检

**新增** `tools/selfcheck_round118_schematic_loader_fixes.py`：**30 断言 / 12 反例**。

- 源码锚点：H1 两层拦截（菜单 + 视图落地）、H2 调用点只在两处且都在条件内、M1 门控/删除死代码/
  中途暂停分支、M3 无全服广播、语言键 zh/en 成对；
- 行为模型：H1 物品守恒（模拟原版 SWAP 先摘物：旧写法丢 1 张，新写法「背包 + 世界 = 1」）；
  M1 空转次数（旧 60 tick 空转 60 次 → 新 0 次）且队列一张不少、重启首 tick 仍能续打、打完那 tick 仍会部署；
  H2 调用频次（100 tick 从 100 次降到 1 次，换图仍刷新）；
- 反例自证：删掉 `dropIntoWorld` / 菜单队列锁 / 把无条件调用加回去 / 把 take 放回条件外 /
  把全服广播加回去 —— 五种坏写法逐一断言判据会判负。

---

## 4. 未修（下一轮候选，复核员原报）

| 编号 | 严重度 | 内容 | 为什么本轮没修 |
| --- | --- | --- | --- |
| M2 | 中 | `attachRoundBlueprint` 不入档：读档 / 区块重载后首 tick 必判「换蓝图」→ 把已拉材料**全部推回网络**再重拉；`AttachRoundComplete` 存了却总被下一 tick 覆盖（自相矛盾的落盘字段） | 涉及读档语义与「跨区块重载」的行为口径，需单独一轮 + 实机确认 |
| M4 | 中 | `collectCluster()` 每次调用都现做 BFS（每 tick 每台 2–3 次，集群最大 64 成员 ⇒ 单 tick 上万次 `getBlockEntity`） | 纯性能优化，改动面大（要给集群成员列表加同 tick 缓存并处理拓扑失效），单独一轮 |
| L1 | 低 / 文档 | 横幅注释称「绝不回退到 `cannon.checklist`」，但 `:2052-2069` 的兜底恰恰会拷贝它 | 文档不符，随 M2 一起改更合适 |
| L2 | 低 / 文档 | `isCannonAdjacent` 里 `level.getBlockEntity(worldPosition) == cannon` 恒为假（`worldPosition` 是本机坐标），真正生效的只有曼哈顿距离判据 | 不影响行为（判据冗余），随下一轮清理 |
| L3 | 低 / 文档 | 注释称「速度 / 堆叠升级各最多 6 个」，实现是「按槽保留 1 个」（正常玩法槽位上限 1，碰不到） | 文档不符，下一轮统一 |

---

## 5. 未验证（诚实清单）

1. **没有实机点界面、没有真机改包**：判据 = 源码锚点 + 行为模型 + 反例自证（本家族没有 JVM 探针：
   相关类全部依赖 MC 类型）。
2. H2 的「20 包/秒」来自 Create 源码路径（`updateChecklist` → `sendUpdate` → `level.sendBlockUpdated`），
   没有在真实世界里抓包计数。
3. H1 的触发摆法（基础 + 高级同时贴一台炮、彼此不相邻）是按 `collectCluster()` / `isBlueprintSlotManagedByQueue`
   的判据推导的，没有实机摆出来复现。
4. M1 的「中途取空 → 自动暂停」是行为选择（复核员建议「停止或什么都不做」，这里选了「停止 + 提示」）：
   若用户希望「取走一张就跳到队列下一张」，需要改口径。
