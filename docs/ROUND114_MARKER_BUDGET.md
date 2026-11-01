# 第 114 轮：标记体积「整表预算」统一拦截（1.2.0-beta.16）

> 基线：用户实机跑的是 `1.2.0-beta.15`（第 113 轮，2026-10-31T15:30 构建，sources=499）。
> 本轮**没有新的用户实机反馈**，做的是第 113 轮**明确记账、但当时按「不改旧语义」放掉**的那一类缺陷：
> 标记体积只拦了新入口、只拦了单条，而真正会撑爆物品同步的是**整张表的累计体积**。
> 本轮同时跑了一次独立只读对抗复核（`cross-review`），复核报出的 2 条高危 + 4 条中危 + 2 条低危
> **全部修完**（见 §4）。

---

## 1. 本轮要解决的问题

第 112 轮把「每区域过滤标记」从方块实体搬到了**区域选定器物品**上，存在物品的 `CUSTOM_DATA`
（`RsccAreaMarks`）。代价是：这份数据**随物品一起网络同步**（背包 / 菜单槽 / 手持 / 快捷栏），
它的大小不再只是存档问题，而是**每个包的大小问题**。

第 113 轮发现「单条标记没有大小上限」并加了 `MAX_MARKER_BYTES`，但如实记了三笔账：

1. **单条上限挡不住累计**：`MATCH_CAPACITY = 288`，每条都接近上限时整表仍然能到几百 KB；
2. **机器侧 48 格 ghost 窗口（第 101 轮旧路径）完全没有拦截**：它写的是**同一份数据**（选定器物品）；
3. **`SetCollectionMarkerConfigPacket` 的 `nbt` / `tags` 是客户端原样回传的不透明负载**：
   服务端 `setMarkerConfig` 直接落库，**没有任何大小 / 条数校验**（改包可绕过所有界面拦截）。

本轮的定调：**把体积预算变成一份统一口径，覆盖所有会写标记的入口；并且按实测把口径校准。**

---

## 2. 设计

### 2.1 两条上限 + 口径校准（`support/MarkerBudget.java`，新建）

| 常量 | 值 | 含义 |
|---|---|---|
| `MAX_ENTRY_BYTES` | `16384` | **单条**标记的 `sizeInBytes`（堆估算）上限 ≈ **线上 2 KB** |
| `MAX_TOTAL_BYTES` | `524288` | **整张表**的 `sizeInBytes` 上限 ≈ **线上 64 KB** |
| `WIRE_RATIO` | `8` | 堆估算 → 线上字节的经验倍率（复核实测 648 / 79 ≈ 8.2） |
| `MAX_ENTRY_WIRE_BYTES` | `2048` | 给玩家看的单条线上字节上限 |
| `MAX_TOTAL_WIRE_BYTES` | `65536` | 给玩家看的整表线上字节上限（文案一律用这个） |

**为什么必须校准**：`CompoundTag#sizeInBytes()` 是 **JVM 堆估算**（48 基数；每键 `28+2·len+36`；
Int 12 / Long 16 / Byte 9 / String `36+2L` / List `37+4n`），约是线上字节的 **8 倍**。
最初把「2 KB / 64 KB」直接当 `sizeInBytes` 阈值写，等于**线上只有 256 B / 8 KB**：

- 一条普通标记（无组件）= **648 B 堆**（线上 79 B）；288 条 ≈ **188 KB 堆**（线上 23 KB）⇒
  旧阈值 65536 会把「默认 16 区域、平均 6 条」的正常配置判成超限 —— 那会变成新一轮「加标记没反应」；
- 单条 2048 堆 ≈ 线上 256 B，连一本十几页的成书都标不进去，与「2 KB」的承诺不符。

本轮按「线上字节意图 × `WIRE_RATIO`」重标定，并单独暴露 `*_WIRE_BYTES` 给文案 / 日志用
（`CompoundTag.sizeInBytes` 便宜、不用真的序列化，作为上界估算足够）。

### 2.2 字节数怎么算：**按原始 NBT 子树**，不逐条重编码

高级版（选定器物品）的预算用 `root.sizeInBytes()`（`RsccAreaMarks` 子树）算：

```
after = 现有子树原始字节
      − 该区域旧记录的原始字节        （recordBytes(root, areaKey)）
      + 新记录的编码大小              （writeArea(areaKey, next).sizeInBytes()）
```

**为什么不逐条 `encodeMarkerTag` 求和**：逐条重编码要么需要注册表上下文（客户端没有），
要么对**旧格式**（`Item` = 整份 ItemStack）条目「读不出来 ⇒ 跳过」而**漏算**——它们恰恰可能是大 NBT。
原始 NBT 大小不需要解码，任何格式的条目都算得进去，而且它就是「随物品同步的那份数据」的直接度量。

基础版（归流缓存仓匹配区，数据在方块实体上）没有「原始子树」这个概念，用
`MarkerBudget.entryBytes` 逐条求和（`CollectionCacheBlockEntity#markerTableBytes`）。

### 2.3 哪些入口被拦、哪些**故意不拦**

| 入口 | 拦截 | 说明 |
|---|---|---|
| `client/screen/AreaMarkScreen`（手持选定器配标记） | ✅ 客户端先拦 + 文案 | 单条 → `notice.too_complex`；整表 → `notice.too_many`（**64 KB**，线上口径） |
| `network/AreaMarkEditPacket`（ADD，服务端） | ✅ 服务端兜底 | 单条 → 静默丢弃（客户端已提示）；整表 → `AreaMarks.isOverBudget` |
| `AdvancedCollectionCacheBlockEntity#addAreaMarker`（机器侧 48 格） | ✅ 返回 `TOO_COMPLEX` | 第 101 轮旧路径第一次被收编，口径 = 整件选定器 |
| `CollectionCacheBlockEntity#tryAddMarker`（基础版匹配区） | ✅ 兜底返回 `false` | 所有调用方（含 ghost 槽点击）都经过它 |
| `CollectionCacheBlockEntity#addMarkerForUi`（Shift+左键） | ✅ 新增 `MarkerAddResult.TOO_COMPLEX` | 界面能说人话：以前这种情况误报「匹配区满」，玩家删光也腾不出空间 |
| `CollectionCacheBlockEntity#setMarkerConfig`（**客户端自带 nbt**） | ✅ 校验 + WARN | 防改包 / 旧客户端回传超大 `nbt` |
| `SetCollectionMarkerConfigPacket`（**客户端自带 tags 计数**） | ✅ `TAG_CAP = 256` | 见 §4-H1：原来直接 `new ArrayList<>(tagCount)`，谎报计数就能 OOME |
| 读档 / 旧档迁移 / 换区域搬运既有标记 | ❌ **故意不拦** | 原则：**不删玩家既有数据**；迁移的「消费 / 冻结」语义见 §4-H2 |
| 删除（清空某区域 / 删一条） | ❌ 永远允许 | `isOverBudget(..., next=List.of())` 恒 `false`（§4-M4 修过） |

### 2.4 边界与语义细节

- **严格大于**才拒绝：恰好 `MAX_TOTAL_BYTES` 通过、`+1` 拒绝；单条同理；
- 预算按**整件**算，不按单区域：`CUSTOM_DATA` 是整棵子树同步的；
- `sanitizeEntries` 在预算计算里也生效（空条目 / 超容量不占预算），与真正写入口径一致；
- 拒绝时**无副作用**：不写条目、不改数量、不消耗物品、不产生半写状态。

---

## 3. 代码改动（本轮总计）

| 文件 | 改动 |
|---|---|
| `support/MarkerBudget.java` | **新增**：两条上限 + `WIRE_RATIO` + `*_WIRE_BYTES` + `entryBytes` / `entriesBytes` / `isEntryTooBig` / `isTotalTooBig` |
| `support/AreaMarks.java` | `MAX_MARKER_BYTES`/`MAX_TOTAL_BYTES` 委托、`MAX_TOTAL_WIRE_BYTES`、`itemTableBytes`、`isOverBudget`（原始 NBT 口径，三分支）、`isIndexPrefix`（§4-M1）、`prune` 批量化（§4-L2）、`migrateByIndex` 消费/冻结语义（§4-H2） |
| `network/AreaMarkEditPacket.java` | ADD 分支：单条之后加整表校验（在 `put` 之前） |
| `client/screen/AreaMarkScreen.java` | `candidate` 只构造一次；整表提示分支（64 KB 口径） |
| `block/entity/CollectionCacheBlockEntity.java` | `MarkerAddResult.TOO_COMPLEX`、`wouldExceedMarkerBudget`、`markerTableBytes`；`tryAddMarker` / `addMarkerForUi` / `setMarkerConfig` 三处接入 + WARN |
| `menu/CollectionCacheMenu.java` | `TOO_COMPLEX` actionbar 文案（区别于「匹配区满」） |
| `block/entity/AdvancedCollectionCacheBlockEntity.java` | `addAreaMarker` 接同一套预算；迁移重写（位次校验 / 消费冻结 / 没进展不动快照） |
| `menu/AdvancedCollectionCacheMenu.java` | **5 参 `applyMarkerConfig` 覆写**（§4-M3：原来区域标记的规则写到了没人读的全局表） |
| `network/SetCollectionMarkerConfigPacket.java` | `TAG_CAP` + 解码不预分配（§4-H1） |
| `lang/zh_cn.json`、`lang/en_us.json` | `collection_cache.shift_mark.too_complex`、`area_mark.notice.too_many` |

---

## 4. 独立复核（`cross-review`，只读）报出的缺陷与修法

| 级别 | 缺陷 | 修法 |
|---|---|---|
| **高 H1** | `SetCollectionMarkerConfigPacket` 解码 `new ArrayList<>(tagCount)`，客户端谎报 `Integer.MAX_VALUE` 级计数 ⇒ 解码阶段（menu 校验之前）OOME / GB 级分配（第 98 轮在 `SyncChargerFilterPacket` 修过同类，这个包漏了） | `TAG_CAP = 256`：按 count 循环消费字节、只暂存前 256 条 |
| **高 H2** | 旧档迁移**会把超大标记搬进选定器**（全链路无体积检查，且升级后区块一加载自动迁移）；后果不止体积——整表一旦超预算，该选定器**永久不能再加标记** | `migrateByIndex` 逐条判：单条超 `MAX_ENTRY_BYTES` ⇒ `skippedTooLarge` 冻结；`isOverBudget` ⇒ `skippedOverBudget` 冻结；**谁消费谁删**（旧条目只在真写进物品 / 物品已有标记 / 空条目时移除），调用方不再 `removeIf(index < areaCount)` |
| 中 M1 | 迁移用 **sanitize 后**的区域表做序号映射：Config 调小 / 几何超限时序号整体前移 ⇒ 旧标记**套到别的区域**（比丢更糟） | 新增 `AreaMarks.isIndexPrefix(raw, usable)`（usable 必须是 raw 的几何前缀）；不成立 ⇒ 整体冻结 + WARN，不消费 |
| 中 M2 | 部分迁移成功时 `legacyFiltersMigrated` 永不置位 ⇒ 每 tick `setChanged` + `invalidateAreaSnapshot` ⇒ 区块每 tick 标脏、菜单每 tick 重发快照包 | 只有 `changed`（有消费或有写入）时才做三件副作用；新增 `legacyMigrationPinnedWarned`（读档重置） |
| 中 M3 | 高级版「区域标记」的**数量门槛 / 严格 NBT / 标签**配置走 5 参包 → 基类 6 参 `applyMarkerConfig`，而高级菜单只覆写了 3 参版 ⇒ 写到**高级版根本不读的全局匹配区**，界面「保存了」但行为没变 | `AdvancedCollectionCacheMenu` 补 6 参覆写：有区域上下文且非流体 ⇒ 转发 `setAreaMarkerConfig`，否则 `super` |
| 中 M4 | `isOverBudget` 的删除分支返回「超预算 = true」，与 javadoc「删除永远允许」相反（超限物品连自救都做不到） | 三分支：`next == null` 纯查询；`next` 空 ⇒ `false`；否则替换后比较 |
| 低 L1 | REPLACE 合并后 `put` 截到 288 条，尾部静默丢 | 未改（下一轮）：先记在 §6 |
| 低 L2 | `prune` 每剪一个区域调一次 `remove()`，每次整树 copy ⇒ O(n²) | 先计数，0 直接返回；否则一次 `update()` 批量重建 |

复核同时**正面确认**了：`CUSTOM_DATA` 确实随物品同步（`DataComponentType.Builder#build()` 未声明
`networkSynchronized` 时回退 `fromCodecWithRegistries`）；`CustomData.update` 确实 `tag.copy()`；
`AreaMarks.itemTableBytes` 不再受 `ABSOLUTE_MAX_AREAS` 截断影响。

---

## 5. 自检与验收

### 5.1 新增自检：`tools/selfcheck_round114_marker_budget.py`（1408 行，纯 stdlib）

**93 断言 / 19 条反例自证 / KNOWN-DEVIATION 0**。行为模型**逐字复刻** 1.21.1 的
`Tag#sizeInBytes` 公式（从工程 sources jar 抽取）与 `encodeMarkerTag` / `writeArea` / `RsccAreaMarks`
编码，再对 `isOverBudget` 三分支、迁移「消费 / 冻结」做孪生模型；源码锚点只用于防回退。覆盖：

1. **常量 / 倍率**：`WIRE_RATIO=8`、堆 16384 / 524288、线上 2048 / 65536 的委托关系、
   `AREA_RECORD_BYTES` 已不存在；**严格 `>` 边界**（恰好 16384 / 524288 通过、+1 拒绝）。
2. **字节算法**：普通标记 **658 B 堆 ≈ 82 B 线上**（id `minecraft:iron_ingot`、空 NBT）；
   NBT 变大 ⇒ 字节单调增；Tags 额外占字节；**288 条整树 = 191102 B ≤ 524288 ⇒ 正常配置通过**
   （容量约 791 / 796 条）；按整件算；旧格式 `Item` 记录计入。
3. **删除契约**：`isOverBudget(stack, area, List.of())` ⇒ 恒 `false`；`next == null` ⇒ 纯查询。
4. **五个入口**：源码锚点 + 顺序（校验在写之前）+ 拒绝时无副作用 + 不消耗物品。
5. **TOO_COMPLEX / 语言键**：新界面 `too_many` 与菜单 `too_complex` 文案都断言 **64 KB 线上**。
6. **本轮复核修复组**（24 断言 / 8 反例）：H2 六元组 + 3 参兼容 + `it.remove()` 恰好三处 +
   超大 / 超预算 / 越界**冻结不消费** + 物品已有标记消费且不覆盖 + 空条目消费；
   M1 `isIndexPrefix` 前缀真 / 中间丢假 + BE 在 `migrateByIndex` 前 return；
   M2 三行 side-effect 在 `if (changed)` 内、`invalidateAreaSnapshot()` 全方法只 1 次；
   L2 prune `doomed == 0` 早退 + 单次 `update`、无逐条 `remove`；
   M3 6 参覆写存在 + 无上下文 / 流体走 `super` + 非流体转发；H1 `TAG_CAP` + `Math.min` + 无预分配。

**判别力自证**：在 `/tmp` 源码副本上做 **10 处打坏实现**（少 `it.remove()` / 冻结分支也消费 /
`isIndexPrefix` 恒真 / side-effect 移出 `if` / prune 改回逐条 / 覆写不转发 / `TAG_CAP` 失效 /
删除分支改回拦 / 文案回 512 KB / 上限漏乘倍率）⇒ **10/10 全部判负**（迁移类同时被 round112/113 抓到）。

### 5.2 既有自检更新

- `selfcheck_round112_area_marks.py`：**100 断言 / 12 反例**（⑤ 组按「谁消费谁删 + 冻结 +
  `isIndexPrefix` + raw/usable」更新，判据加强未削弱）；
- `selfcheck_round113_area_mark_e2e.py`：**140 断言 / 38 反例**（② 组按六元组 / 三类计数 /
  `it.remove()` / `changed` 块 / 位次冻结重写；⑦ prune 锚点更新）；
- `selfcheck_round106_adv_cache_areas.py`（**超出本轮 write scope，但不改无法全绿**）：其
  `_has_invalidating()` 原来用「固定 2000 字符窗口」截方法体，M2 让迁移方法变长后窗口截断 ⇒ 括号配不平、
  误报；改成「从签名配平到真实结束大括号」（判据不变：仍要求方法体含 `invalidateAreaSnapshot()`），
  并更新 `loadAdditional(` 签名 hint。

### 5.3 本轮验收

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=500`
2. 全量自检 → **160 个脚本 / 0 失败**
   （须用**带 Pillow 的运行时 python**；系统 `python` 缺 PIL 会让 `selfcheck_advancements.py`
   报 `ModuleNotFoundError`——解释器环境差异，不是本轮回归）
3. `tools/verify_dedicated_server.ps1 -ModJar build\libs\rs_create_compat-1.2.0-beta.16.jar`
   → **PASS**（`Done (1.939s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `gradlew build` → `rs_create_compat-1.2.0-beta.16.jar`（**2,793,209 B**，
   sha256 `29bc61c27f9c1ee4390cb8fef2320d48b8ea6da0ba378d66413bc792e43c6ed2`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round114.py` → `DEPLOY_VERIFY_OK`：
   本地实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.15 就地改名 `.disabled`

---

## 6. 未验证 / 主动披露

1. **没有实机点界面**：判据是「源码锚点 + 行为模型 + 反例自证」；真实的 `CUSTOM_DATA` 同步体积
   没有抓包实测，`WIRE_RATIO = 8` 是复核按 MC 堆公式手算 + 一条普通标记的估算得到的经验值，
   内容里字符串占比不同会漂移（当上界估算用）。
2. **旧数据可能「一出生就超限」**：迁移 / 搬运不拒，老存档若本就有超大标记，整表可能 > 64 KB（线上），
   此时所有**新增**都会被拒（删除仍允许）；迁移路径会冻结那些区域并 WARN，但没有实机验证过
   玩家会先删标记而不是以为坏了。
3. **重标签可增长路径未全收编**：`AdvancedCollectionCacheBlockEntity#setAreaMarkerConfig`（标签集合）
   与 `tryAddFluidMarker`（流体 nbt 恒空）未接预算（复核 L3：无网络入口 / 无体积风险，暂不动）。
4. **L1 未修**：REPLACE 合并后超过 288 条会被 `sanitizeEntries` 截断且无日志（只有「旧区域 >288 条」
   这种手改 NBT 才可能触发）。
5. 基础版匹配区的 `TOO_COMPLEX` 只在 Shift+左键路径有文案；ghost 槽直接点击沿用既有
   「失败即静默」约定（与 DUPLICATE / FULL 一致）。
6. 其他机器（数量守恒器 / 高级数量守恒器 / 序列执行仓…）的标记表不在本轮范围。
7. 旧档 `Index` 究竟是「当时可用位次」还是「原始下标」仍无旧源码佐证；本轮用 `isIndexPrefix`
   把「能安全映射」的情况限制在「sanitize 只砍尾巴」，不确定时**冻结而不是猜**。
