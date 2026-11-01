# 第 117 轮（1.2.0-beta.19）：归流缓存仓「阻塞名单」收口 + 上一轮复核的 H1 / M2

> 基线：用户实机跑的是 `1.2.0-beta.18`（第 116 轮）。
> 本轮**没有新的用户实机反馈**，做的是两件事：
> ① 清第 116 轮 §22.6-6 自己记的账 —— 全仓最后一类「名单 / 字符串列表」C2S 写入口
> （`SetCollectionBlockedPacket.tags` 解码无上限 + 归流缓存仓四个阻塞集合可被无限撑大）；
> ② 一位独立只读复核员把第 116 轮的四个写入面重审了一遍，报出 **1 高 / 3 中 / 5 低**，
> 其中的高与两个中也在本轮修掉（HIGH 的放大倍率量级是「33 MB 落盘」，不能拖着）。

---

## 1. 阻塞名单：为什么值得单独收口

「阻塞」是用户在 beta.x 实机反馈里提过的语义：被阻塞的资源**仍可被本机收集 / 物流输入 / 玩家取出**，
但不再 `insert` 回 RS 网络。自「按标签阻塞」上线后，这份配置变成**四个集合**：

| 集合 | 持久化键 | 增长来源 |
| --- | --- | --- |
| 物品 id | `BlockedItems` | `SetCollectionBlockedPacket`（每条一个 id） |
| 流体 id | `BlockedFluids` | 同上 |
| 物品标签 | `BlockedItemTags` | 同一条包的 `tags` 列表 |
| 流体标签 | `BlockedFluidTags` | 同上 |

两条口子（都是第 98 / 107 / 114～116 轮反复修过的同一类）：

1. **条数无上限**：反复发「每包换一个新 id / 新标签」就能把四个集合无限撑大。它们会进
   `writeBlocked` **落盘**，并随每次打开界面的 `SyncCollectionMarkersPacket` **全量回发**。
2. **标签包解码无上限**：原来是 `ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list())`，
   条数完全由对端给（`ResourceLocation` 还只校验字符、不限长度）。

---

## 2. 四个改动点

### 2.1 新类 `support/CollectionBlockedLimits`：数字只定义一次

- `MAX_BLOCKED_IDS = 1024`（具体 id 名单；物品 / 流体各一份独立计数）
- `MAX_BLOCKED_TAGS = 256`（按标签名单；一条标签就挡住一整族资源，名额理应更少）
- `MAX_TAGS_PER_PACKET = 128`（解码层闸门；真实物品标签数通常 < 30，留 4 倍余量）
- `MAX_TAG_NAME_LENGTH = 128`（写入前丢弃超长「标签」；真实标签 id 通常 < 40 字符）
- `fits(Set, Iterable, max)`：**有界加入的唯一算法** —— 已存在的条目不占新名额（幂等），
  `null` 忽略，超出即返回 false（调用方必须**整体拒绝**，不许「能塞几条塞几条」）
- `clamp(...)` **委托** `ChargerFilterLimits.clamp`（有界截断的实现全仓只有那一份）
- 纯 JDK、无任何 MC 类型 ⇒ 自检可以 `javac` + `java` **真跑**它（见 §4 ⑧ 组）

### 2.2 方块实体：唯一写入口 + 原子拒绝 + 只约束增长

新增 `CollectionCacheBlockEntity#applyBlockedEdit(fluid, id, blocked, tags)` 与

```java
public enum BlockEditResult { CHANGED, UNCHANGED, REJECTED_IDS_FULL, REJECTED_TAGS_FULL }
```

- **先判后写**：id 名单 → 标签名单，两道 `fits` 全过才写；任何一道不过 ⇒ **一格不写**（原子）。
  旧实现的 `setBlocked` + `setBlockedTags` 两次独立写入，可能「id 写进去、标签被拒」——半写状态
  会让界面显示与提示对不上。
- `sanitizeBlockedTags`：丢弃 `null` / 超长（> 128 字符）/ 重复，保序、最多 128 条。
  超长标签不可能存在于任何注册表，但会按原样落盘 + 回发 —— 纯负担。
- **只约束增长**：`blocked = false`（解除阻塞）永远允许；**读档 `loadBlocked` 不判上限**。
  旧存档（或本轮之前被改包撑大的存档）里的名单**原样读回**，绝不截断 —— 截断等于「玩家明确说过
  不要回流的那批资源突然又开始回流」，比「暂时不能再增长」严重得多。恢复路径是删几条。
  这条规则与第 116 轮单元样板库复核定下的完全一致（读档不截断 / 任何增长不许越限）。
- 旧的 `setBlocked(fluid, id, blocked)` / `setBlockedTags(fluid, tags, blocked)` 保留，改为委托
  唯一写入口，因此同样受上限约束。

### 2.3 网络包：手写 `TAGS_CODEC`（消费全部字节、只暂存前 128 条）

```java
private static final StreamCodec<RegistryFriendlyByteBuf, List<ResourceLocation>> TAGS_CODEC = ...
// decode: 读 declared → 循环 declared 次全部解码（保流对齐）→ 只保留前 min(declared, 128)
//         预分配容量 = min(declared, 上限)，绝不拿对端计数直接开数组
```

### 2.4 「名单已满」必须被玩家看见

`handle` 里区分两种拒绝并回**动作栏**提示（文案带真实上限值）：

- `message.rs_create_compat.collection_cache.blocked_ids_full`（"阻塞名单已满（上限 %s 条）…"）
- `message.rs_create_compat.collection_cache.blocked_tags_full`

同时每进程各打一条 WARN（改包刷包不会刷日志）。菜单无论结果如何都会 `syncMarkers()` 回传权威名单，
所以客户端那份「乐观更新」的勾选状态会自己改回来 —— 界面不会撒谎。

---

## 3. 独立只读复核（第 116 轮那四个写入面）与本轮修复

复核员只读、未改任何文件、未跑编译 / gradle / 专服。结论：**正常玩法未见误拒**（正常导入远低预算、
四个拒绝点都在写入之前、生成预检确实在扣料之前），但报出 1 高 / 3 中 / 5 低 + 8 条「陈述≠代码」。

### 3.1 HIGH：`recipeId` 完全没进预算，且被逐张复制进每一步样板（已修）

`SetSequenceImportPacket:134` 解码 `recipeId = ResourceLocation.tryParse(buf.readUtf())`，
而 `budgetViolation` 只累加 `machines` / 物品 / 流体 —— `recipeId` **既不限长、也不计入字符串合计**。
它会被 `SequencePatternData#TAG_RECIPE` 复制进**每一步**的单元样板（菜单 → 单元样板 → 总样板
`DisplayArrangement` → 方块实体 NBT 并随同步回发）。

- **触发**：改包发 `recipeId = "a" × 32700`（`readUtf` 上限 32767，`ResourceLocation` 只校验字符不限长）
  + 1024 个互不相同的短 `machines`（绕过 `sameSteps` 合并）⇒ 1024 张样板各带 32.7 KB
  ⇒ **约 33 MB 落盘**，而客户端单包只有约 30 KB。
- **修法（两道独立闸）**：
  1. `recipeId.length() > MAX_IMPORT_STRING_LENGTH(256)` ⇒ 整包拒绝；
  2. 把 `(36 + 2·recipeId.length()) × 步数` 计入字符串合计（按步数放大，因为它是**被复制**的）。
  两道闸各自都能单独挡住这种形状（自检 ⑧-1 / ⑧-2 分别验证），正常 Create 配方 id 远短于 256 字符。

### 3.2 中：解码阶段「静默截断」（已修）

第 116 轮给解码加了「每列表 4096 / 整包 8192」的暂存上限。复核指出：一旦真的触发，各列表就**互相错位**
（候选组比输入少、流体被清空、概率与产物对不上），而 `budgetViolation` 与菜单都看不见 ——
玩家收到 `import.done`，导进去的是半张配方。这是**静默失效**，比拒绝糟得多。

修法：`SetSequenceImportPacket` 新增一个**不上线格式**的 record 分量 `boolean truncated`：
- `decode` 在任何一处「丢掉了一条」时置位（`staged[1] = 1`）；
- `encode` **不写**它（客户端构造时传 `false`）；
- `handle` 在**任何写入之前**检查 `packet.truncated()` ⇒ 整包拒绝 + 动作栏提示 + WARN 留证。

### 3.3 中：判据重复实现（已修）

同一个 `> MAX_ENTRY_BYTES` 比较以前在 `isTagTooBig` / `isStackTooBig` / `isFluidTooBig` /
导入包的整包预算里**各写了一遍**。现在统一成 `MarkerBudget#isEntryBytesTooBig(int)`：
调用方只负责算出字节数，比较只有一份。

### 3.4 低：其余四条已修 / 已记

- `CreateUnitPatternPacket` 覆盖输出槽时旧样板**不返还** ⇒ 现在先把旧样板交给玩家（背包放不下就掉脚下）。
- 非法负 `targetSlot`（改包可达）被 `Math.max(0, ·)` 写到 0 号格、覆盖已有样板 ⇒ 现在**拒绝 + WARN**。
- 三处日志把「2 KB 线上」写成精确值 ⇒ 改成「堆估算阈值 + ≈2 KB 线上意图」。
- 三处「陈述≠代码」逐条改正：`SetSequenceGhostPacket`（没有「普通槽直接放入」分支，且当前无发送方）、
  `CreateUnitPatternPacket`（越界**不是**收敛到末格，是拒绝）、`TARGET_FIRST_EMPTY_LIBRARY`（不再自动扩容）、
  `SequencePatternTerminalBlockEntity` 库容量注释（1024 格 ≈ 150–300 KB；读档走 `rscc$resizeRaw`；
  `serializeNBT` 按「最后一个非空槽 + 1」落盘 ⇒ 槽位数会压缩但条目不丢）、`MarkerBudget` 的
  `WIRE_RATIO` 口径（字符串主导负载下是**下界**，成书 / 满潜影盒不会被 2 KB 挡住 —— 复核实测口径）。

### 3.5 未修（记入 §6）

- **M1 库只有条数限、没有体积限**：单张允许 ≈2 KB 物品 + 256 名 + 256 配方类型 ⇒ 1024 格理论上
  ≈2.5 MB 线上，是导入预算的 10 倍。要修得给库整体加一份「合计体积预算」，属于下一轮的设计活。
- **`ArrangementSize` 读档无上限**（旧有，不在本轮写域）。
- **`SetSequenceGhostPacket` / `SetStepFluidPacket` 目前没有正常发送方**（界面已不提供 JEI ghost 目标）
  ⇒ 两个闸事实上只对改包生效；保留是因为写进标记槽的数据会永久落盘。

---

## 4. 自检

新增 `tools/selfcheck_round117_blocked_limits.py`：**34 断言 / 13 反例**，四条腿：

1. **源码锚点**：常量取值与量级、纯 JDK、`fits` 存在、`clamp` 委托、包不再用
   `ByteBufCodecs.list()`、解码 keep 上限 / 不预分配 / 按声明条数消费、`handle` 两种拒绝都提示、
   无 `warnFull` 就判负、BE 两个上限都在写入之前判 + 拒绝分支在写入之前 return + `setChanged()` 只在写后、
   读档不判上限、菜单返回结果并 resync、两处上限都引用常量（不许写死数字）；
2. **真代码 JVM 探针**：把纯 JDK 的 `CollectionBlockedLimits` + `ChargerFilterLimits` 用
   `javac` 编译后真跑 17 条断言（`fits` 边界 / 幂等 / null、`isFull`、`clamp`、`addBounded`、
   `sanitizeTagNames` 的 null / 空 / 超长 / 去重 / 上限）；
3. **行为模型**：`applyBlockedEdit` 的 8 条语义（加入 / 幂等 / 满即拒 / 解除永远允许 / 原子性 /
   超长丢弃 / 单包上限）；
4. **反例自证 13 条**：包退回 `ByteBufCodecs.list()`、解码按对端计数预分配、解码不消费声明字节、
   `handle` 静默丢弃满员拒绝、读档截断、先写 id 后判上限、菜单两次独立写入、BE 写死上限值、
   满员时不再幂等、语言键缺失、H1 两道闸都去掉、M2 `handle` 忽略截断标记、H1 去掉单条限长。

配套更新（只是把锚点改成新实现，判据没放水）：

- `tools/selfcheck_round115_cross_machine_budget.py`：①-1 / ①-2 按「比较统一到 `isEntryBytesTooBig`」更新。
- `tools/selfcheck_round116_sequence_write_budget.py`：①-1 / ①-3 / ①-5 同上（并要求「比较只有一份实现」）。
- `tools/selfcheck_round9_feedback.py`：阻塞名单写入入口的锚点从 `block.setBlockedTags(...)` 改成
  `block.applyBlockedEdit(fluid, id, blocked, tags)`（并断言旧入口仍然委托它）。

## 5. 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=501`**
2. 全量自检 → **163 个脚本 / 0 失败**（新增 `selfcheck_round117_blocked_limits.py`：34 断言 / 13 反例）
3. 专服探针 → **PASS**（`Done (1.901s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `rs_create_compat-1.2.0-beta.19.jar`：**2,808,433 B**，
   sha256 `021d39cef65a38a5dae5f8b408601bb7586a0bd0ee3a6327ee518ca3b629dcd1`
   （`build_info`：`modVersion=1.2.0-beta.19` / `sources=501` / `builtAt=2026-10-31T17:53:03+0800`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round117.py` → **`DEPLOY_VERIFY_OK`**：
   本地实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.18 就地改名 `.disabled`
   > ⚠ 部署脚本要用**带 paramiko 的 python**（本机 `D:\Python313\python.exe`）；
   > 运行时 python（`.dsh/.../dependencies/python`）没有 paramiko，会 `ModuleNotFoundError`。

## 6. 未验证 / 待确认（诚实清单）

1. **没有实机点界面，也没有真机改包**：判据 = 源码锚点 + 行为模型 + JVM 探针 + 反例自证；
   改包的真实放大倍率、「被拒后玩家 / 攻击者看到什么」都没有在运行环境观测。
2. **上限数值是估算**：1024 / 256 / 128 / 128 字符都是按「正常玩法用量级 × 余量」定的，
   没有拿真实大型整合包统计过「一个玩家会阻塞多少种资源」。
3. **超限旧存档**：只在行为模型 / 源码上验证「读档不截断 + 只拒增长 + 删除永远允许」，
   没有真实旧档实例。
4. **`NeoForge` 对已注册 payload 是否仍限 32767 字符**未核实（vanilla 的限制只作用 `DiscardedPayload`）
   ⇒ H1 的放大倍率是**下限**。
5. **M1（库只有条数限、没有体积限）未修**、`ArrangementSize` 读档无上限（旧有）。
6. 仍开着、需产品拍板的：`/rs_create_compat diag` 无权限；区域标记 36 格是否分页；
   数量门槛 / 标签匹配是否搬进「区域标记」界面；「打开区域列表」快捷键。

## 7. 下一轮候选

- **用户对 beta.14～beta.19 的实机反馈（最高优先）**。
- M1：单元样板库的「合计体积预算」（条数已有 1024 上限，体积还没有）。
- 用户三项待定 + `diag` 权限（需产品拍板）。
