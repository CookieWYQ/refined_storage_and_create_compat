# 第 116 轮（1.2.0-beta.18）：序列装配终端家族的「物品 / 流体 / 字符串」写入面收口

> 基线：用户实机跑的是 `1.2.0-beta.17`（第 115 轮，2026-10-31T16:40 构建，`sources=500`）。
> 本轮**没有新的用户实机反馈**，清的是第 115 轮 §21.6-5 / §21.7 **自己记下的账**：
> 「序列终端 4 个写入入口只做了条数保护，没有逐条 ItemStack 体积预算」+
> 「`UnitLibrary` 自动扩容仍无条数上限（复核 L3）」。

---

## 1. 为什么值得单独一轮

第 114 / 115 轮把「客户端给的数据会落盘 ⇒ 必须按线上字节限体积」这条纪律一路铺到了：
区域标记（整表预算）、两台定量保持器（单条预算）、导入包的解码预分配（按对端计数 OOME）。

但**序列装配终端家族**的 4 个 C2S 写入面一直是敞开的：客户端给的 `ItemStack` / `FluidStack`
被直接写进方块实体 NBT 与单元样板的 `CustomData`，**永久落盘**，并随方块实体同步 / 每次开界面回发。
它们与「把物品设为标记」是同一类风险（改包塞 KB 级数据 ⇒ 存档膨胀 + 同步膨胀），只是之前没轮到。

改完这一轮，全仓「客户端给的物品 / 流体 / NBT 会落盘」的 C2S 写入口**全部接上了同一把尺**
（`MarkerBudget`）。仍然开着、但属于**产品口径**需要用户拍板的两类见 §6。

---

## 2. 六个改动点（以及为什么不误伤正常玩法）

### 2.1 共用尺：`MarkerBudget` 抽出 `stackBytes` / 新增 `fluidBytes`

- `stackBytes(ItemStack, Provider)` / `fluidBytes(FluidStack, Provider)`：判据仍是
  **保存成 NBT 之后的 `sizeInBytes()` 堆估算**，与第 114 轮复核实测的「约 8 倍线上字节」口径相同；
  空物品 / 空流体 / 拿不到注册表 / 保存抛异常 ⇒ 一律返回 0（**判不了就不拒**，绝不误伤正常路径）。
- `isStackTooBig` 改为委托 `stackBytes`；新增 `isFluidTooBig`。
- 抽出 `*Bytes` 的原因：一次导入要算**整包合计**（单条上限挡不住「很多条各自都刚好合法」——
  这正是第 114 轮区域标记「整表预算」的同一条推理）。

### 2.2 `SetSequenceGhostPacket`：JEI ghost 拖拽 → 输入原料标记槽

客户端给的 `ItemStack` 会写进终端输入原料标记槽并落盘。现在先过单条尺（线上 ≈ 2 KB），
超限则**不写、旧标记不动**，动作栏提示 + 每进程一条 WARN。

### 2.3 `SetStepFluidPacket`：步骤输入流体（灌注 / 注液）

1.21.1 的 `FluidStack` 带 `DataComponentPatch`（可含任意数据组件），同样会被写进单元样板
`CustomData`。现在用同一把尺判 `fluid.saveOptional(registries)` 的体积，超限不写。
**正常的水 / 岩浆 / 模组流体远低于上限**。

### 2.4 `CreateUnitPatternPacket`：创建 / 覆盖一张单元样板

三项都是客户端给的、都会进 `CustomData`：

- `input` 物品 → 单条尺；
- `name` / `recipeType` 字符串 → 上限 256 字符（界面 `EditBox` 上限是 64，配方类型 id 是命名空间
  路径 < 100 字符，**对合法值零影响**）；
- 写入单元样板库时：`targetSlot >= max(库容量, 硬上限)` 或首空位返回 `-1` ⇒ **拒绝写入**，
  动作栏提示「库已满」+ 每进程一条 WARN。

### 2.5 `SetSequenceImportPacket`：JEI「+」整条配方转移（最大的一处）

两层：

1. **解码层（第 115 轮 H1 的补完）**：单列表 4096 条上限之外，再加**整包全局暂存计数 8192**。
   对端字节仍按 `count` 全部消费（流对齐必须保持），但整包最多只留 8192 条 ——
   否则「4096 个候选组 × 每组 4096 条」这种形状在**解码阶段**就能造出上百万 `ItemStack` 对象。
2. **网络边界（`handle`，写入之前）**：新 `budgetViolation(...)` 判三项
   —— 单条物品 / 流体（线上 ≈ 2 KB）、物品 + 流体**合计**（线上 ≈ 256 KB）、
   客户端给的字符串（`machines` / 配方 id：单条 256 字符 + 合计）。
   超限**整包拒绝（原子）**：一格都不写、旧编排 / 旧原料保持原样，动作栏提示 + 每进程一条 WARN。

**为什么是 256 KB 而不是 64 KB**：导入负载里有「每步输入原料组的全部候选」（标签型 ingredient，
如「任意台阶」整组）。一条普通物品标记线上约 79 B，256 KB ≈ **3300 条普通物品**；
真实 Create 序列配方（几十步 × 每组几十个候选）远低于此，正常导入不会被拦。

### 2.6 `GrowingUnitLibrary`：容量硬上限 1024 格（+ 只约束增长）

第 115 轮复核 L3：库「满即扩容、无上限」，反复「生成样板」/「汇总取回」就能让终端 NBT 无限变大，
而这份 NBT 随方块实体每次同步回发。现在：

- 新增 `MAX_UNIT_LIBRARY_SLOTS = 1024`（初始 108 格 = 18 页；一张单元样板约 150–300 B，
  1024 格已远超正常玩法所需）；
- `firstEmptySlot()`：全满且到上限 ⇒ 返回 **-1**（以前恒返回 `getSlots()` = 允许继续扩容）；
- **所有写入方都「拒绝 + 保留原物」**：`putUnitIntoLibrary` 返回 false、
  `pullUnitsFrom` 把样板**留在执行仓**、`CreateUnitPatternPacket` 拒绝、
  「生成样板」在**写总样板 / 让菜单扣 RS 样板之前**用 `canAccept(unitsToStore)` 预检并报错
  （所以「扣了材料却丢样板」不可能发生）；
- **读档不判上限**：旧实现无上限扩容留下的超限旧库原样读回，**绝不截断玩家数据**；
  上限只约束「将来的增长」，而且**任何增长**都不许越过上限（见 §3 的 M1 修正）。

### 2.7 文案

新增 3 条（zh_cn / en_us 各一份）：拒绝写真标记、拒绝导入、库已满（带 `%s` 上限值）。

---

## 3. 独立只读复核（谁报的、改了什么）

由一位独立只读复核员（review subagent，未改任何文件、未跑编译）只审「单元样板库容量上限」这一块，
结论：**无高严重度缺陷**（不丢档 / 不崩溃 / 不会扣料后丢样板），报出 1 中 + 1 中偏低 + 2 低，全部处理：

| 编号 | 报告 | 处理 |
| --- | --- | --- |
| **M1**（中） | `rscc$resize` 的守卫写成「仅在从 ≤ 上限跨过时拒绝」，于是**旧存档里已 > 1024 格的库**在 `ensureTrailingEmpty` 每次写入时仍能再 +1（形同没有上限） | 改成**任何增长都不许越过上限**（`newSize > getSlots() && newSize > MAX` 才拒绝；缩小仍允许，含超限旧库缩到仍 > 上限） |
| **M2**（中偏低） | `canAccept` 对 > 1024 的旧库只算空槽，常见旧库任何 ≥2 步生成会被判「库已满」 | M1 修正后这是**正确**语义（那种库确实不能再扩，只能先取走样板）；文案与类注释已说明「取出一些即可」，不再出现「不会被锁死」的矛盾表述 |
| **L1**（低） | packet 的 `targetSlot >= 1024` 会拒绝旧库里槽位 ≥ 1024 的编辑写回，且只打日志 | 上限改成 `max(库容量, MAX_UNIT_LIBRARY_SLOTS)`（旧库已有格仍可编辑）；拒绝时补动作栏提示 |
| **L2**（低，文档） | 类注释仍写「可增长、无上限」「不会因为库满而丢样板」 | 重写为「有硬上限 / 到上限返回 -1 / 调用方必须拒绝 / 读档不判上限」 |

---

## 4. 自检

- **新增** `tools/selfcheck_round116_sequence_write_budget.py`：**32 项断言 / 8 条反例自证**（纯 stdlib）。
  不靠字符串 grep 撑判据：单条 / 合计 / 字符串长度、解码整包暂存、库容量与扩容上限、
  `canAccept` 预检计数都做**行为模型逐值断言**；
  反例把源码片段改坏（删体积检查 / 删整包暂存上限 / 把 `firstEmptySlot` 改回恒 `getSlots()` /
  把 resize 守卫改回「仅跨过时拒绝」/ 不调用 `budgetViolation`）⇒ 对应判据必须判负。
- **更新** `tools/selfcheck_round115_cross_machine_budget.py`：①-2 按「`isStackTooBig` 委托
  `stackBytes`」的新实现诚实更新（判据不变，仍是「判不了不拒 + 严格大于才拒」）；
  ③-2 / ③-6 / ③-7 按 `readStacks` 初始容量 64 + 整包 8192 的新口径更新（**判据加强**：
  仍要求「不按对端计数预分配 + 仍按 count 消费字节」）。
- **更新** `tools/selfcheck_candidate_pipeline.py`：`readStacks(buf, staged)` 新签名（判据不变）。
- **全量**：`tools/_run_all_selfchecks.py` → **162 个脚本 / 0 失败**。
  > ⚠ 必须用**带 Pillow 的运行时 python**
  > （`C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe`）。

---

## 5. 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=500`**
2. 全量自检 → **162 个脚本 / 0 失败**
3. 专服探针 → **PASS**（`Done (1.805s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `rs_create_compat-1.2.0-beta.18.jar` → **2,802,193 B**，
   sha256 `aed7c5934af721ecb45564d5a57fb3e05c1d75a942aa1f8c91d4e2102829f08f`
   （`build_info`：`modVersion=1.2.0-beta.18` / `sources=500` / `builtAt=2026-10-31T17:12:45+0800`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round116.py` → **`DEPLOY_VERIFY_OK`**：
   本地实例 / SFTP `/mods` / SFTP `/client_files/mods` 均为
   `aed7c5934af721ecb45564d5a57fb3e05c1d75a942aa1f8c91d4e2102829f08f`），beta.17 就地改名 `.disabled`
   > ⚠ 部署脚本要用**带 paramiko 的 python**（本机 `D:\Python313\python.exe`）；
   > 运行时 python（`.dsh/.../dependencies/python`）没有 paramiko，会 `ModuleNotFoundError`。

---

## 6. 未验证 / 待确认（诚实清单）

1. **没有实机点界面，也没有真机改包**：判据是「源码锚点 + 行为模型 + 反例自证」。
   改包的真实放大倍率、被拒后玩家 / 攻击者看到什么，都没有在运行环境里观测。
2. **数值口径**：`MAX_ENTRY_BYTES`（线上 ≈ 2 KB）与 256 KB 合计预算都是按
   「`sizeInBytes()` 堆估算 ≈ 8 倍线上字节」的经验换算；256 KB 对应「≈ 3300 条普通物品」
   是估算，**没有拿真实大型 modpack 的标签型 ingredient 实测过**。
3. **1024 格上限没有实机跑到**：初始 108 格、生成默认跳过重复，正常玩法远达不到；
   只在行为模型上验证了「到上限 ⇒ 拒绝 + 保留原物 + 扣料前预检」。
4. **旧存档 > 1024 格的库**：只在行为模型 / 源码上验证「读档不截断 + 增长被拒」，
   没有真实旧档实例。
5. **仍开着（属于产品口径，需要用户拍板）**：
   - **M1** `SetCollectionBlockedPacket.tags` 的「阻塞标签」名单无上限、不校验 tag 是否存在；
   - **M2** `/rs_create_compat diag` 无权限（普通玩家可反复触发全量遍历世界 + 大 JSON 落盘）；
   - 用户三项待定：区域标记界面 36 格是否分页、数量门槛 / 标签匹配是否搬进「区域标记」界面、
     「打开区域列表」是否加快捷键入口。

---

## 7. 下一轮候选

- **用户对 beta.14～beta.18 的实机反馈（最高优先）**。
- 用户三项待定 + M1 / M2（需要产品拍板）。
- 剩下未接体积预算的写入口只剩「**字符串列表**」类：
  `SetCollectionBlockedPacket.tags`（阻塞标签）、`SetChargerFilterPacket` 的过滤名单 ——
  它们同样是「客户端给的一串 ResourceLocation 落盘」，与 M1 一起做更合适。
