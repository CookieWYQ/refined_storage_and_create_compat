# 第 115 轮：跨机器「标记 / 导入」写入口的体积与计数上限（1.2.0-beta.17）

> 交付形态：`rs_create_compat-1.2.0-beta.17.jar`（编译 / 全量自检 / 专服探针 / 三处部署 sha256 一致，
> 见 §5）。**无配置格式变更，替换 jar 重启即生效。**

---

## 1. 这一轮为什么做这件事

第 114 轮把「区域选定器物品上的 ghost 标记」按**整表**体积管住了（`MarkerBudget`，单条 ≈ 线上 2 KB /
整表 ≈ 线上 64 KB），并在交付说明里明确记了一条未验证项：

> 「其他机器（数量守恒器 / 高级数量守恒器 / 序列执行仓…）的标记表体积与同步路径未测（本轮范围外）。」

本轮没有新的用户实机反馈（beta.16 交付约 25 分钟），于是**把这条自己记的账清掉**：请一位独立只读
复核员（`cross-review`）把其余所有「玩家可写、会落盘、会回传客户端」的标记 / 过滤 / 导入写入口盘一遍，
按报告修最高价值的三组。复核的总体结论是：

> **除区域选定器标记外，全仓没有任何第二处标记 / 过滤表接入体积预算。**
> 而且 C2S（客户端→服务端）侧还有**一处**「谎报计数 ⇒ 解码阶段 OOME」与第 114 轮 H1 完全同源。

---

## 2. 复核盘点（只列与本轮修复相关的结论，行号以复核当时的源码为准）

| 机器 | 表 / 负载 | 上限 | 任意大 NBT 写入口 | 本轮 |
|---|---|---|---|---|
| 定量保持器（基础） | 标记槽 ItemStack（真实菜单槽）+ `fluidMarkerNbt` | 物品 1 条 / 流体 1 条，**单条无体积上限** | `SetQuantityMarkerPacket`、`SetQuantityFluidMarkerPacket` | **修（H3）** |
| 高级定量保持器 | 4 行 ×（物品标记 + `fluidMarkerNbts[i]`） | 4 条，**单条无体积上限** | `SetAdvKeeperMarkerPacket`、`SetAdvKeeperFluidMarkerPacket` | **修（H3）** |
| 序列装配终端 | `UnitLibrary`（自动扩容）、`Arrangement`、导入的原料 / 候选 / 产物 / 废料 | 库 / 编排**无条数上限** | `SetSequenceImportPacket`、`SetSequenceGhostPacket`、`CreateUnitPatternPacket` | **部分修（H1/H2）** |
| 归流缓存仓（基础 / 高级） | 除区域标记外还有 `blockedItems/Fluids/Tags` | 四个 blocked 名单无上限、不校验 tag 存在 | `SetCollectionBlockedPacket.tags` | 只记录（M1） |
| 序列执行仓 / 单元样板管理舱 / 装填器 / 资源请求器 | 真实容器槽 / 服务端写字符串 / 已注册资源键 | — | 无放大入口 | PASS，未动 |

复核同时**正面确认**第 114 轮的防护成立：`CollectionCacheBlockEntity#setMarkerConfig` 有服务端兜底
预算、`AreaMarks#migrateByIndex` 会冻结超大 / 超预算条目（本轮又把位次校验下移到数据层，见 §4.5）。

---

## 3. 修了什么（按复核严重度）

### 3.1 H1：`SetSequenceImportPacket` 解码阶段按客户端计数预分配（高）

- **缺陷**：`readStacks` / `readChances` 里 `final int count = buf.readVarInt();
  final List<ItemStack> stacks = new java.util.ArrayList<>(count);` —— `count` 由客户端给，
  谎报 `2³¹-1` 会在**解码阶段**（`handle` 的 containerId / 菜单校验之前）直接 OOME。
  与第 114 轮 `SetCollectionMarkerConfigPacket` 的 H1 **同源**。
- **修法**（照第 114 轮已验证的口径）：
  - 常量 `MAX_IMPORT_STEPS = 1024`、`MAX_IMPORT_LIST = 4096`；
  - **仍按 `count` 逐个解码**（保持流对齐，否则后面的字段全串位），但**只暂存前 N 条**：
    `new ArrayList<>(Math.min(count, MAX_IMPORT_LIST))`；
  - 所有对端计数先 `Math.max(0, buf.readVarInt())`（负计数不许绕过循环 / 触发怪异分配）；
  - `machines` 不再用 `buf.readList(...)`（MC 的实现同样按计数预分配），改手工循环 + 步数上限。
- **不做**：`count` 极大时仍会一路解码到包尾抛异常 —— 这一步只消费对端已经发来的字节，
  CPU 开销与包长同阶，没有「无中生有」的分配放大（与第 114 轮 20.6-4 的取舍一致，见 §6-4）。

### 3.2 H2：序列终端导入步数无上限（高）

- **缺陷链**：`SetSequenceImportPacket.machines` 是 `List<String>`，每项可以是**空串（1 字节）**；
  `SequencePatternTerminalMenu#importSequencedRecipe` 里 `final int steps = Math.max(1, machines.size())`，
  服务端据此**为每一步生成一张单元样板**（`SequencePatternData.writeUnit`）、`applyRecipeToArrangement`
  扩容器 / 写 `DisplayArrangement` / `Counts` / `StepMachines` NBT，随后整表随同步包回发。
  约 2 MB 的包 ⇒ 上百万步 ⇒ 存档爆炸 + 每 tick 重发。
- **修法**：`SequencePatternTerminalBlockEntity.MAX_IMPORT_STEPS = 1024`（解码层同值暂存上限 + 菜单入口
  再 clamp 一次，纵深防御），超限时 WARN。**为什么不裁在 `applyRecipeToArrangement`**：那里裁剪会
  连**既有的列**一起丢，导入语义应该是「只接受这么多个新步」。
  真实 Create 序列装配配方最多几十步，1024 不会拦住任何合法导入。

### 3.3 H3：两台定量保持器的标记写入口没有体积预算（中，与区域标记同类风险）

- **缺陷**：`SetQuantityFluidMarkerPacket` 的 `nbt` 是**不透明 `CompoundTag`**，`SetQuantityMarkerPacket` /
  `SetAdvKeeperMarkerPacket` 的 `stack` 是**客户端给的 `ItemStack`**；两条路径都直接
  `nbt.copy()` / `copyWithCount(1)` 落盘（`saveAdditional`），并按槽回传（`SyncQuantityFluidMarkerPacket`、
  `SyncAdvKeeperConfigPacket`、ghost 槽走原版 `ContainerSetSlot`）。**没有任何 size 检查** ——
  改包可以把成书 / KB 级 NBT 设为标记，此后每次开界面都要带上它。
- **修法**：`MarkerBudget` 新增两个「同一把尺」的判据：
  - `isTagTooBig(CompoundTag)`：`null` / 空标签永远通过，`sizeInBytes() > MAX_ENTRY_BYTES` 才拒；
  - `isStackTooBig(ItemStack, registries)`：`stack.save(registries).sizeInBytes()` 同口径；
    **没有注册表上下文（`registries == null`）⇒ 判不了 ⇒ 不拒**（绝不因为判不了而误拒正常路径）；
    保存抛异常 ⇒ 交回原路径，不在这里炸。
  - 四个服务端入口（两台机器 × `setFluidMarker` / `setItemMarker`）**先判后写**：超限直接 `return`，
    **旧标记原样保留**（不落盘、不清另一形态的标记、不同步放大），每台机器只打一条 WARN
    （`markerTooLargeWarned`，改包者刷包不许刷日志）。
- **为什么不接管「真实菜单槽」那一侧**：keeper 的标记槽是真实 `Slot`（`MarkerSlot.mayPlace` 恒 false、
  `mayPickup` 恒 false），正常放入路径本来就不通；能塞大 NBT 的只有 JEI 拖入的 C2S 包，已在上面堵住。

### 3.4 L1（第 114 轮复核遗留）：REPLACE 合并后超 288 条静默截断

`AreaSelectorEditPacket` 在 REPLACE 换几何时把旧几何标记搬到新几何，若新几何已有标记则按 `(类别, id)`
去重合并 —— 合并结果超过 `MATCH_CAPACITY = 288` 时 `AreaMarks.put → sanitizeEntries` 会截断且无日志。
现在补 WARN：说明「搬来多少 / 原有多少 / 合并后多少 / 上限多少 / 保留新几何原有标记在前 / 被截掉的
需手动重加」。正常玩法极难触发（要单区域 > 288 条，通常只有手改 NBT 才做得到），但「静默丢配置」
不能只靠运气。

### 3.5 `MergeResult.pinned` 收尾（第 114 轮 20.6-3 自记待办）

第 114 轮里 `pinned` 是**恒为 `false` 的 API 预留位**：位次校验只在调用方（高级归流缓存仓）里做。
本轮把它变成真判：

- `AreaMarks#migrateByIndex` 自己 `AreaSelection.get(stack)` 读**原始**区域表，与入参（sanitized）
  比 `isIndexPrefix`；不成立 ⇒ `return new MergeResult(0, 0, 0, 0, 0, true)`（**0 迁移 / 0 消费**）。
- **`touched()` 不再把 `pinned` 算成「动过」**：冻结的含义是「一个字节都没改」，算进去会让调用方
  对一张没变的表 `setChanged` / 失效快照。第 114 轮它恒 false 所以看不出来，现在它是可达状态。
- 调用方（BE）仍保留自己的 `isIndexPrefix` 前置判断 —— 那条能打出**带坐标**的 WARN，保留；
  数据层这层是纵深防御（任何别的调用方 / 将来重构都不会静默按序号乱搬）。

### 3.6 顺手：`MarkerEntry` 的 S2C 编解码也不再按计数预分配（复核 L1）

`MarkerEntry.STREAM_CODEC` 的 `new ArrayList<>(tagCount)` 目前只被 `playToClient` 使用（不是信任边界），
但形状与第 114 轮 H1 完全同源；本轮改成 `Math.min(tagCount, 32)`，防止将来被 C2S 复用时重演。

---

## 4. 自检

- **新增** `tools/selfcheck_round115_cross_machine_budget.py`：**27 断言 / 6 反例自证**（纯 stdlib）。
  不靠字符串 grep 撑判据：`isTagTooBig` / `isStackTooBig` / `isFluidMarker 拒收后状态不变` /
  `steps clamp` / `解码首趟容量 = min(count, 4096)` 都是**行为模型**逐值断言；
  反例把源码片段改坏（去掉 `!tag.isEmpty()`、去掉体积闸门、改回 `new ArrayList<>(count)`、
  去掉 `Math.min` clamp、去掉 REPLACE WARN、改回 `new ArrayList<>(tagCount)`）⇒ 对应判据必须判负。
- **更新** `tools/selfcheck_round114_marker_budget.py`：按 `pinned` 的新语义诚实更新（`⑥-1` 断言
  `touched()` 不再含 `pinned`），并新增 `⑥-25`～`⑥-28`（数据层位次校验 / pinned 行为模型 /
  「只砍尾巴」不误伤 / 反例）。现为 **97 断言 / 20 反例**（原 93 / 19）。
- **全量**：`tools/_run_all_selfchecks.py` → **161 个脚本 / 0 失败**。
  > ⚠ 跑全量要用**带 Pillow 的运行时 python**
  > （`C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe`）：
  > 系统 `python` 缺 PIL，`selfcheck_advancements.py` 会 `ModuleNotFoundError`（解释器环境差异，不是回归）。

---

## 5. 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=500`**
2. 全量自检 → **161 个脚本 / 0 失败**（`tools/_run_all_selfchecks.py`，运行时 python 带 Pillow）
3. `tools/verify_dedicated_server.ps1 -ModJar build\libs\rs_create_compat-1.2.0-beta.17.jar`
   → **PASS**（`Done (1.928s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `gradlew build` → `rs_create_compat-1.2.0-beta.17.jar`（**2,796,072 B**，
   sha256 `034929faba518b2bac3d0163b734aefe24e92f5771537596adea77b9b1367917`；
   `build_info` 自证：`modVersion=1.2.0-beta.17` / `sources=500` / `builtAt=2026-10-31T16:39:56+0800`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round115.py` → **`DEPLOY_VERIFY_OK`**：
   本地实例 / SFTP `/mods` / SFTP `/client_files/mods` 均为
   `034929faba518b2bac3d0163b734aefe24e92f5771537596adea77b9b1367917`），beta.16 就地改名 `.disabled`
6. 部署脚本：SFTP 侧 `tools/deploy_rscc.py --apply`、本地实例 `tools/deploy_rscc_local.py --apply`
   （两者都走「旧版改名 `.disabled` → 上传 `.uploading` → 读回校验 → rename」，不删除任何旧 jar）


---

## 6. 未验证 / 主动披露

1. **没有实机点界面**，也**没有真机改包**：以上判据是「源码锚点 + 行为模型 + 反例自证」。
   改包的真实放大倍率、以及「被拒后玩家/攻击者看到什么」都没有在运行环境里观测。
2. **单包真实帧长上限没实测**：NeoForge 层是否比 MC 的 3 字节 VarInt（≈2 MB）更紧，未验证；
   因此 H2 的「约 2 MB 的包」是按协议理论上界估的。
3. **`isStackTooBig` 没有注册表上下文时不判**：读档 / 早期路径若拿不到 registries，体积闸门是「放行」。
   这是刻意的（宁可放过也不误拒），代价是「那一条路径不设防」——目前所有 C2S 入口都有 level，走不到。
4. **`SetSequenceImportPacket` 的 count 极大时仍会解码到包尾**：只消费对端已发字节，不做无中生有的
   分配，但会占用与包长同阶的 CPU（与第 114 轮对 `SetCollectionMarkerConfigPacket` 的取舍一致）。
5. **H3 只覆盖两台「定量保持器」**：序列装配终端的 4 个写入口（`SetSequenceGhostPacket` /
   `SetSequenceImportPacket` 的 ItemStack 列表 / `CreateUnitPatternPacket`）只做了「条数」保护
   （H1/H2），**没有逐条 ItemStack 体积预算**；`UnitLibrary` 自动扩容也仍然无条数上限（复核 L3）。
   这是本轮有意收窄的写域，记入下一轮候选。
6. **未修（复核建议只记录）**：
   - **M1** `SetCollectionBlockedPacket.tags` 的四个「阻塞标签」名单无上限、不校验 tag 存在，
     且每个被收集物品都要线性扫一遍并逐个 `TagKey.create`。加 cap 前要先定「要不要拒绝不存在的 tag」
     —— 属于产品口径（与「按标签阻塞」这个新功能的边界有关），下一轮连 UI 一起定。
   - **M2** `/rs_create_compat diag` 无权限（普通玩家可反复触发全量遍历世界 + 大 JSON 落盘）：
     改动会影响既有的诊断工作流（一直靠它取证），加权限 / 预算需要拍板。
   - **L2** 一批 S2C 的 `new ArrayList<>(count)`（计数由服务端自己给，不构成本轮问题）。

---

## 7. 下一轮候选

- **用户对 beta.14～beta.17 的实机反馈（最高优先）**。
- 用户之前提的三项待定：「区域标记」界面 36 格上限要不要分页、数量门槛 / 标签匹配要不要搬进
  这个界面、「打开区域列表」要不要加快捷键入口 —— **按用户选择落地**。
- M1（阻塞标签名单 cap + 是否拒绝不存在的 tag）、M2（diag 权限）—— 需要产品口径 / 用户拍板。
- 序列终端 4 个写入口的逐条 ItemStack 体积预算 + `UnitLibrary` 条数上限。
