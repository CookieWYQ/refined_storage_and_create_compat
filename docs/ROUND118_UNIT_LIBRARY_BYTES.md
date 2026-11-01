# 第 118 轮（1.2.0-beta.20）之一：单元样板库「合计体积预算」

> 基线：第 117 轮交付 `1.2.0-beta.19`。
> 本轮**没有新的用户实机反馈**，清的是第 117 轮交接文档 §23.6-5 / §23.7 自己记的账：
> **M1「单元样板库只有条数上限、没有合计体积上限」**。
> 顺带把上一版注释里「1024 格理论 ≈2.5 MB 线上」这句估算落成一个**可判定的闸**。
>
> 本轮同时交付的另两份文档：
> - `ROUND118_SCHEMATIC_LOADER_FIXES.md`（蓝图加农炮装填器：复核 2 高 / 4 中 / 3 低，修 2 高 + 2 中）
> - `ROUND118_KEEPER_TARGET_SYNC.md`（定量保持器：复核 2 高 / 3 中 / 4 低，修目标数量 short 截断 + 递归重入）
>
> **本轮验收**：`LEAD_COMPILE_OK sources=503`；全量自检 **166 个脚本 / 0 失败**；
> 专服探针 PASS（`Done (1.776s)`）；`rs_create_compat-1.2.0-beta.20.jar`（**2,813,877 B**，
> sha256 `96e54e635ef1a57ca3bade89a2538b95fb98e7375de220d69b38f0370d96bc00`）
> 三处部署回读一致（本地实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.19 就地 `.disabled`。

---

## 1. 为什么「条数上限」单独成立还不够

前几轮在同一条纪律上逐步收口：

| 轮次 | 加的那一道闸 | 管的是什么 |
| --- | --- | --- |
| 第 114 轮 | `MarkerBudget.MAX_TOTAL_BYTES` | 区域标记「整张表」 |
| 第 116 轮 | 单条尺 `MAX_ENTRY_BYTES`（≈线上 2 KB / 条） | 一次写入里的「一条」 |
| 第 116 轮 | `MAX_IMPORT_TOTAL_WIRE_BYTES`（256 KB） | JEI 导入「整包」 |
| 第 116 轮 | `MAX_UNIT_LIBRARY_SLOTS`（1024 格） | 单元样板库「条数」 |
| 第 117 轮 | `CollectionBlockedLimits`（1024 id / 256 标签） | 归流缓存仓名单「条数」 |
| **第 118 轮** | **`UnitLibraryLimits`（1 MiB 线上意图）** | **单元样板库「存量体积」** |

两个各自正确的上限**相乘**之后仍然很大：`1024 格 × 每格接近单条上限 ≈ 2 MB 线上`。
而单元样板库这份 NBT 的代价是三处：① 进方块实体 NBT **落盘**；② 打开终端 / 区块同步时**整库回发**；
③ 每次「放不放得下」的判断本身要遍历全库（所以本轮还额外做了体积缓存，见 §2.3）。

---

## 2. 四个改动点

### 2.1 新类 `support/UnitLibraryLimits`（纯 JDK ⇒ 自检可 javac 真跑）

| 常量 / 方法 | 值 / 语义 |
| --- | --- |
| `WIRE_RATIO` | `8` —— **必须与 `MarkerBudget.WIRE_RATIO` 同值**（自检 ①-4 锁定，允许两份字面量、不允许两个口径） |
| `MAX_LIBRARY_WIRE_BYTES` | `1024 * 1024`（1 MiB 线上意图） |
| `MAX_LIBRARY_WIRE_KB` | `1024`（文案 / 日志用，随常量走） |
| `MAX_LIBRARY_HEAP_BYTES` | `MAX_LIBRARY_WIRE_BYTES * WIRE_RATIO`（判据实际用的堆估算口径） |
| `fits(used, add)` | **减法形式** `used <= MAX - add`：加法形式在 `Long.MAX_VALUE` 下会回绕成负数 ⇒ 误放行 |
| `remaining(used)` | 剩余可写（负数 / 空库按空库处理，不许返回负数） |
| `usedWireKb(used)` | 用量换算成 KB（向上取整，非空库永远不显示 0 KB） |

量级：一张普通单元样板线上约 200–800 B ⇒ 1024 格全满约 200–800 KB 线上，
1 MiB 的预算对「整库都是普通样板」留了 1.3–5 倍余量 ⇒ **正常玩法（几页到十几页）远不会碰到**；
真正会被挡住的只有「整库平均每格都接近单条上限」的病态配置，那正是本闸要挡的。

### 2.2 唯一写入口 `GrowingUnitLibrary#trySetStackInSlot`（原子拒绝 + 只拦增长）

- **先判后写**：`delta = 新体积 − 旧体积`；`delta > 0` 且 `fits(used, delta)` 不过 ⇒ **直接返回 false，一格不动**。
- **只拦增长**：换小 / 清空 / 取出永远允许 —— 与第 116 / 117 轮（库容量上限、阻塞名单上限）同一条纪律：
  **宁可让超限旧数据留着，也不替玩家删数据**。
- `setStackInSlot` 改为**只委托**它（整个类里 `super.setStackInSlot` 只剩一处，自检 ①-13 锁定）；
  菜单格直写 / `CreateUnitPatternPacket` / `putUnitIntoLibrary` / `pullUnitsFrom` 全部经过它。
- 兜底：`ensureSlot` 之后若下标仍越界（扩容被 1024 格上限拒绝）⇒ 返回 false，
  **绝不继续 `getStackInSlot`**（那会抛 `IndexOutOfBoundsException` = 崩服务端；旧代码只靠调用方自己保证下标合法）。
- `insertItem`（空格插入 = 增长）先判预算、不过就**整张退回**（不半插）；已占用格只改数量 ⇒ 同步维护缓存。
- `extractItem` **只维护缓存、不判上限**（取出永远允许）。

### 2.3 体积缓存：一次 O(1)，而不是每次 O(1024)

- 缓存字段 `rscc$usedHeapBytes`：`-1` = 失效，下次查询整库重算；否则增量维护。
- **本轮自检当场抓到的一个真 bug**：旧写法「失效时也做增量」会把 `-1 + delta` 钳成 `0` ——
  等于把「不知道已用多少」静默变成「已用 0」，**之后 `canAcceptMore` 会以为库是空的，整条预算被废掉**。
  现在只有缓存有效时才增量更新，失效时保持失效（自检 ③-1 的随机 4000 步模型 + 反例 ④-3 双向盯住）。
- 失效时机只有三类：读档、缩小（`rscc$resizeRaw` 丢尾部）、注入注册表上下文。扩容不改变内容 ⇒ 缓存可留。

### 2.4 四条写入路径全部接同一道闸（都在「扣材料之前」）

| 路径 | 处理 |
| --- | --- |
| 生成样板 `generateAssemblyPattern` | `canAcceptUnits(unitsToStore)` 预检**格数 + 合计体积**，不过就**在写总样板 / 让菜单扣 RS 样板之前**返回 false；体积满用独立文案（带 KB 上限） |
| 单张入库 `putUnitIntoLibrary` | 格数满 → `warnUnitLibraryFull`；体积满 → `warnUnitLibraryFullBytes`（带真实用量 / 上限 / 格数 / 坐标，每台终端一条）；两个都不过 ⇒ 不写入、不销毁、返回 false |
| 从执行仓取回 `pullUnitsFrom` | 体积不过就 `break`：**剩下的样板留在执行仓**（绝不销毁）；不再裸写库 |
| 新建单元样板包 `CreateUnitPatternPacket` | 写入前判 `canAcceptMore`；被拒 → 动作栏提示 + 每进程一条 WARN；写入口返回值再兜一次底 |
| 菜单格放置 `UnitLibrarySlot#mayPlace` | 也看预算（返回 false ⇒ 原版点击逻辑把物品**原样留在光标 / 背包**，而不是「先挪走再被服务端拒绝」） |

新增文案（zh / en 各一条，占位符 = 体积上限 KB，数字随常量走）：
`gui.rs_create_compat.sequence_pattern_terminal.generate.library_full_bytes`

---

## 3. 自检

**新增** `tools/selfcheck_round118_unit_library_bytes.py`：**50 断言 / 6 反例**，四条腿：

1. **源码锚点**（①-1 ～ ①-27）：常量取值、纯 JDK、判据只有一份（减法形式）、唯一写入口、
   只拦增长、读档不判、四条写入路径 + 菜单 `mayPlace` 都过闸、语言键 zh/en 成对且不写死数字。
2. **真代码 JVM 探针**（②-1 / ②-2）：把纯 JDK 的 `UnitLibraryLimits` **javac 编译后真跑 22 条断言**，
   含 `Long.MAX_VALUE` 溢出反例（`fits(MAX, MAX)` 必须 false）。
3. **行为模型**（③-1 ～ ③-9）：Python 复刻库的体积记账（含缓存增量维护）跑**确定性随机 4000 步**，
   断言「缓存 == 真实合计」「任何增长都不越限」「换小 / 取出 / 读档永远允许」；
   另有满库 / 取出再写 / 超限旧库原样读回 三组定点断言。
4. **反例自证**（④-1 ～ ④-6）：删掉判据、读档判预算、加法形式回绕、`mayPlace` 不看预算、
   读档回退成判格数 —— 六种坏写法逐一断言**判据会判负**。

---

## 4. 未验证 / 待确认（诚实清单）

1. **没有实机点界面，也没有真机改包**：判据 = 源码锚点 + JVM 探针 + 行为模型 + 反例自证。
2. **上限数值是估算**（1 MiB 线上意图）：按「一张普通单元样板 200–800 B × 1024 格」的量级 × 余量定，
   没有真实整合包 / 真实终端的统计。
3. **堆估算 ≠ 线上字节**：`WIRE_RATIO = 8` 对**字符串主导**的负载（机器 id / 配方类型 / 候选组列表）
   是**下界**（第 117 轮对 `MarkerBudget` 的同一条更正）；真正保证的是「按堆估算的一个确定上限」。
4. **超限旧库**：只在行为模型 / 源码上验证「读档不截断 + 只拒增长 + 删除永远允许」，没有真实旧档实例。
5. 本轮**没有**改动 `/rs_create_compat diag` 的权限（用户明确要求该指令不要权限），
   该条以「冷却 / 轮转」形式另案处理；36 格区域标记分页等产品口径仍待用户拍板。
