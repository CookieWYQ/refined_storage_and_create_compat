# 第 120 轮：序列装配家族「审计债」清理（F2 / F5 / F6 / F7 / F8）

> 依据：`docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md` §F2、§F5、§F6、§F7、§F8 与 §9。
> 本轮**不改产品行为 / 玩法语义**：只做「整批原子化 + 不做半批销毁」「服务端不信任客户端字符串」
> 与「注释/命名/死代码对齐实现」。
> 自检：`python tools/selfcheck_round120_seq_audit_debt.py` → **SELFCHECK OK（53 断言 / 16 反例 / 0 失败）**。
> 编译：`javac`（只编译本轮改动的 5 个文件、classpath 取 `build/lead_compile` + Gradle 缓存）**EXIT=0**；
> 仅 1 条既有 deprecation 提示（`BlockStateBase.rotate`），与本轮改动无关。**本轮没有跑 gradle、没有实机验证。**

---

## 本轮修了什么 / 没修什么（一页版）

> 给「下一轮接手的人」看的结论摘要；细节见下面各 F 小节与文末「未验证 / 未覆盖」。
> **不要只读本节就以为 F2 已全修** —— (b)(c) 两条边界是刻意留下的，理由在下面和 §F2 里。

### 修了什么

| 项 | 实改 | 落点 |
| --- | --- | --- |
| F2 主修 | `accept` 改成「先按 `ResourceKey` 聚合 → 全量 `SIMULATE`（任一条不满 ⇒ **一条都不插**，`REJECTED`）→ 全过才 `EXECUTE`」；`amount <= 0` 不再凭空 +1；`EXECUTE` 写不满**不 break**、诚实 `REJECTED` | `SequenceAssemblyExecutorBlockEntity.java:194-240` |
| F2(a) | **同一种资源在批内重复出现**（`40 + 40` 而只剩 64）导致的「前半批落地、后半批销毁」，已被「按 resource 聚合」**彻底堵死**（自检 ②-3…②-6、②-13…②-17、③-1…③-4） | 同上 |
| F5 | `SetStepMachinePacket` 服务端按 pos 取执行仓自己的显示名，客户端 name 仅兜底；`setStepMachine` 统一按码点夹到 256 | `SetStepMachinePacket.java:48`、`SequencePatternTerminalBlockEntity.java:1487` |
| F6 | 「容量 6 / 3×2 / 无 GUI / 右键放取」的注释改成「54 格 + 无条件开 GUI + 类型限制只在容器包装层」；删零调用死代码 `putUnit`/`takeUnit` | `SequenceExecutionChamberBlockEntity.java`、`SequenceExecutionChamberBlock.java` |
| F7 | `dropOutputContents()` → `rscc$legacyDropOutputContents` + `@Deprecated` + 禁令 javadoc | `SequenceExecutionChamberBlockEntity.java:11594` |
| F8 | 终端 `saveAdditional` 里重复的 `SequencePatternData.writeCandidates` 删掉，**全方法只保留一处**（`:2629`），注释不重复 | `SequencePatternTerminalBlockEntity.java:2629` |

### 没修什么（如实边界，别下轮当已修）

1. **(b) 同一类存储里多种不同资源合计超容**：如物品侧有 `A×100 + B×100`、网络只剩 64 件空间 —— 逐条
   `SIMULATE` 各自都通过（RS 的模拟按单次插入判断），`EXECUTE` 时第二条只落地一部分，**仍会部分落地**；
   与旧实现相同（不更差）。
2. **(c) `SIMULATE` 与 `EXECUTE` 之间空间被别的机器抢走**：`StorageNetworkComponent` 没有「预留容量 / 事务」API，
   `accept` 内部**无法预占**跨资源容量，因此无法原子化。
   ⇒ (b)(c) 要真正修，必须改成「执行仓自建有限缓冲、`accept` 只写缓冲」（`ROUND119` §F2 修法二），
   那属于**改产品行为 / 玩法语义**，需用户拍板，不在本轮范围。本轮对 (b)(c) 的结论只是「**不劣于旧实现**」，
   自检 ②-7b 把它写成显式断言（物品侧仍落地 96 件）。
3. **复核文档 §F2 的示例推理有误**：`docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md` §F2 写「64 件空间 + 64 物品 +
   500mB 流体 ⇒ 两次模拟都通过 ⇒ `EXECUTE` 全部成功（或全部损失）」——**这句只在物品/流体各自都有空间时成立**。
   RS 的物品存储与流体存储是**两套独立存储**，物品写满不吃流体空间，所以该示例**推不出**「会损失」；
   真实损失形状是 (a)(b)(c) 三种。**下轮不要按该错误示例推理**，详见 §F2 下「本轮对 §F2 推理的一处修正」。

---

## F2（中）外部样板 sink：`accept` 改成「聚合 → 全量 SIMULATE → 全过关才 EXECUTE」

**位置**：`src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/SequenceAssemblyExecutorBlockEntity.java`
- javadoc：`:159-192`（新增 §「整批原子性」小节）
- 方法：`:194-240`（聚合 `:200-213`、SIMULATE 门 `:217-223`、SIMULATE 分支 `:224-226`、EXECUTE `:230-239`）

**旧行为**（`:171-184` 原文）：逐条 `storage.insert(resource, Math.max(1, amount.amount()), action, Actor.EMPTY)`，
`inserted < count` 就 `return REJECTED`；javadoc 写「网络放不下全部则让任务稍后再试」。

**新行为**：
1. 先按 `ResourceKey` 聚合总量（`LinkedHashMap`，保持样板顺序；跳过非 `ItemResource`/`FluidResource`）；
   `amount <= 0` 直接 `continue`（旧 `Math.max(1, …)` 在 `amount == 0` 时会**凭空插入 1 件**）；
2. 按**聚合总量**逐条 `SIMULATE`；任何一条 `inserted < total` ⇒ **一条都不插**，直接 `REJECTED`；
3. 全批模拟通过后才 `EXECUTE`（`action == SIMULATE` 时在此返回 `ACCEPTED`）；
4. `EXECUTE` 阶段写不满时**不 break**：继续把其余资源尽量插进去，最后诚实返回 `REJECTED`；
5. 注释改成 RS 的真实语义（见下），删掉旧的假承诺。

**为什么**：RS 2.0.9 的 `ExternalTaskPattern#acceptsIterationInputs`（`local_src/rs_src/.../ExternalTaskPattern.java:184-215`）
先把本轮输入从任务内部暂存 **EXECUTE 扣掉**，再调 `sink.accept(..., EXECUTE)`；返回值只打一条 `LOGGER.warn`，
源码注释原文是 *"we lie, do as if the insertion was successful, and potentially void the extracted resources"*。
⇒ **`REJECTED` 不会重试，未插入的那部分被 RS 自己吞掉**。所以「边插边判」等价于「前半批落地、后半批销毁」，
必须把判定提到任何写入之前。`ExternalPatternSink` 的接口 javadoc 也明确要求
*"All resources MUST be able to be inserted for this method to return ACCEPTED"*。

**自检覆盖**：①-1…①-8（源码锚点 + 反例）；②-1…②-7b、②-13…②-17（行为模型）；③-1…③-4、③-14…③-16（反例）。
其中 ②-1 即本轮指定形状：网络只剩 64 件空间（且**没有流体存储**）时「64 物品 + 500mB 流体」整批被拒、
插入量 0；②-2 给出旧实现同场景下「先插 64 件再拒」的对照。

**附注（另一支自检的 tripwire）**：`tools/selfcheck_round58_reserved_stock_guard.py` 有一条锚点，要求执行器里
存在「把每轮投入物原样插回网络」的 insert 调用（它据此判定 `scheduled` / `processing` **不算**别人的预留）。
`EXECUTE` 循环用命名局部量（`resource` / `count`）调用 `storage.insert(resource, count, action, Actor.EMPTY)`，
该锚点仍然命中；语义结论已重查：`accept` 依旧把投入物**全部插回网络**（不扣留、不缓冲），
因此 round58 的口径不变（该脚本重跑 **79 checks OK**）。

### 本轮对 §F2 推理的一处修正（重要，别当成「已全修」）
- §F2 写「64 件空间 + 64 物品 + 500mB 流体 ⇒ 两次模拟都通过」。**这句只在两种资源各自都有空间时成立**：
  RS 的物品存储与流体存储是**两套独立存储**，物品写满不吃流体空间；若流体凑巧也有 500mB 空间，
  则旧实现 EXECUTE 其实**全部成功**。
- 真实的损失形状是：**(a) 同一种资源在批内重复出现**（`40 + 40` 而只剩 64，各自模拟都过、执行时第二条只插 24）；
  **(b) 同一类存储里多种不同资源合计超容**；**(c) 模拟与执行之间空间被别的机器抢走**。
  本轮把 (a) 用「按 resource 聚合」彻底堵住；(b)(c) 只能靠 `EXECUTE` 阶段「继续插 + 诚实 REJECTED」把损失压到最小。
- **(b)(c) 仍是未修边界**：`StorageNetworkComponent` 没有「预留容量 / 事务」API，`SIMULATE` 只能看到「此刻还有空间」，
  跨资源的合计容量在 accept 内部**无法预占**。要真正原子，需要 §F2 修法二（执行仓自建有限缓冲、`accept` 只写缓冲），
  那属于**改产品行为**，不在本轮范围。自检 ②-7b 把这条边界显式断言在案（物品侧仍会落地 96 件，与旧实现相同）。
- 本轮**不做回滚**是刻意的：RS 已把输入从内部暂存扣掉，「回滚 + REJECTED」= 整批被吞；
  而「尽量插 + REJECTED」至少让部分资源落地。二者都不完美，所以「继续插」严格不劣。

---

## F5（低）`SetStepMachinePacket.name`：服务端不再信任客户端字符串

**位置**：
- `network/SetStepMachinePacket.java:39-66`（javadoc 补充 `:18-24` 的「name 是冗余字段」说明）
- `block/entity/SequencePatternTerminalBlockEntity.java:1479-1496`（新增 `clampStepMachineName`）、`:1498-1512`（`setStepMachine` 调用它）

**旧行为**：`terminal.setStepMachine(stepIndex, pos, packet.name())` —— `name` 无任何上限（`ByteBufCodecs.STRING_UTF8`
上限 32767 字符，但 NBT 里按步线性膨胀），且完全可以冒充别的机器名。
**新行为**：
1. 按 `pos` 在 `listChambers()`（服务端现算，只有「已绑定（名字 + 配方类型）」的仓在列表里）中找到目标仓，
   直接用它自己的服务端显示名 `ChamberInfo#name`（`getChamberDisplayName()` 的派生值，
   与 `SyncChamberListPacket` 下发给界面的**同一个来源**）；
2. 只有「按 pos 临时取不到显示名」（`name` 为空串）才回退到客户端 `name`；
3. 落盘侧统一收口：`setStepMachine`（`stepMachineNames` 的**唯一**写入口，`clearStepMachine` 也走它）
   一律经 `clampStepMachineName(...)` —— 上限复用第 116 轮的 `MAX_IMPORT_STRING_LENGTH = 256`（`:166`），
   按**码点**截断（避免把代理对切成半个字符）。
4. `hasPos == false`（解绑）路径不变，空名字语义不变（解绑仍写 `""`；`getStepMachineName` 未指派仍返回 `""`）。

**为什么**：客户端给的字符串是**不可信输入**；服务端本来就能自己算出显示名，不需要客户端提供。
这与第 116 轮「客户端 `name` / `recipeType` → 上限 256」的口径统一；同时消掉「每步最多 ~64KB」的存档/同步膨胀面。
诚实客户端行为完全不变：客户端发的本来就是 `ChamberInfo#name`（`SequencePatternTerminalScreen:787`、
`StepMachineSelectScreen:177`），因此**玩家可见文本零变化**。

**自检覆盖**：①-9…①-12；反例 ③-5…③-7。

---

## F6（低）注释与实现对齐 + 删除零调用死代码

**位置 / 旧 → 新**：
1. `block/entity/SequenceExecutionChamberBlockEntity.java:149-159`（`unitSlots` 字段注释）
   - 旧：「只允许放本模组的单元样板，**容量 6**」
   - 新：`{@value #UNIT_SLOTS} 格（6 行 × 9 列，双箱规格）`；并写明 **`SimpleContainer` 本身无类型限制**，
     「只收单元样板 / 配方类型必须相等」的硬约束只存在于三处包装层：①`SequenceExecutionChamberMenu.ChamberUnitSlot`、
     ②`ChamberUnitsSummaryMenu` / `UnitManagerSources` 的容器包装、③经它们写入的搬运路径 —— 三者收敛到 `acceptsUnit`。
2. `block/SequenceExecutionChamberBlock.java:34-42`（类 javadoc）
   - 旧：「无 GUI。… 手持单元样板右键放入样板槽，空手右键取出（见方块实体交互）」
   - 新：**右键无条件打开管理界面**（6×9 = 54 格单元样板槽，放入/取出全部走 GUI，方块没有「右键放/取」旁路），
     并把旧说法以「历史」形式留在注释里便于追溯。
3. `block/SequenceExecutionChamberBlock.java:221-227`（`useWithoutItem` 注释）
   - 旧：「右键打开界面（**3×2** 单元样板槽，正常放入/取出）」
   - 新：6 行 × 9 列 = 54 格，与 `SequenceExecutionChamberMenu` 的实际布局一致；注明旧「3×2」错误。
4. **删除死代码** `putUnit(...)` / `takeUnit(...)`（原 `:15930-15982` 一带）：全仓逐文件复核（`src/` + `tools/`）
   只有定义、**零调用点**，不是接口覆写、无反射 / mixin 字符串引用。顺带删掉随之不再使用的
   `import net.minecraft.world.entity.player.Player;`；原来的小节标题
   「右键交互：放/取单元样板（无 GUI）」改成「单元样板槽：准入判定（已无右键放/取入口，只经 GUI / 管理舱容器）」。

**为什么**：注释是给玩家（经 JEI/文档）与下一个维护者的约定；`takeUnit` 的「装不下 `Block.popResource` 弹出到世界」
分支若将来被接回，会与菜单路径重复结算（半接状态最危险），故直接删而不是 `@Deprecated`。

**自检覆盖**：①-13…①-16；反例 ③-8…③-10。

---

## F7（低）`dropOutputContents()` → `rscc$legacyDropOutputContents` + 醒目禁令

**位置**：`block/entity/SequenceExecutionChamberBlockEntity.java:11594-11665`（javadoc `:11594-11607`、签名 `:11608-11609`）。

**旧行为**：`public void dropOutputContents(Level, BlockPos)`，javadoc 自称「方块被破坏时的内部存储掉落（**供方块调用**）」，
但全仓**只有定义、没有调用**；真实入口是 `SequenceExecutionChamberBlock#onRemove` → `BlockContentReleaser.release(...)`
（`collectItems` / `collectPatterns` / `collectFluids` / `receiveFluids` + `handOverChainOnRemoval` 集群移交）。
**新行为**：改名 `rscc$legacyDropOutputContents` + `@Deprecated(forRemoval = false)`，javadoc 写明
「**历史实现，已被 `BlockContentReleaser.release` 取代，禁止调用**」，并解释「接回会造成双重结算 / 绕过集群移交」。
方法体**一字未改**（保留考古价值，且不影响运行；本轮不动产品行为）。

**为什么**：僵尸代码本身无害，但它的 javadoc 是一个**错误入口指引**：谁按它接回 `onRemove`，就会与
`release()` 形成双重结算 / 绕过落盘边界（`RsccMachineCluster` 类注释点名的「资源翻倍」来源）。
改名后名字自带警告，且 `rscc$` 前缀与工程里其它「历史/内部」成员一致。

**自检覆盖**：①-17、①-18；反例 ③-11、③-12。

---

## F8（低）终端 `saveAdditional` 不再把候选连写两遍

**位置**：`block/entity/SequencePatternTerminalBlockEntity.java:2624-2629`（唯一写入点 `:2629`）。

**旧行为**：`:2606` 与 `:2613` 是同一个调用 `SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);`
（同键、同数据，第二遍纯覆盖），注释也重复一遍。
**新行为**：只保留一处（带完整注释：键 / 格式 / 老档缺键读回空表 / 客户端镜像靠同一份 NBT 轮播顶部「输入原料」格），
并把重复那处删除；注释里留一行说明「旧实现连写两遍、现已收敛到唯一写入点」。

**为什么**：无功能影响，但下次只改其中一处就会出现「两份不同源的数据」。`loadAdditional` 侧
`readCandidates` 本就是单次读取（`:2695`），写读口径现在一致。

**自检覆盖**：①-19（在 `saveAdditional` 方法体内计次，整文件里 `writeCandidates` 的文档提及不会误伤）；反例 ③-13。


## 本轮改动文件清单（均在 task-55 写域内；**没有动** CHANGELOG / HANDOFF / 语言文件 / gradle.properties / build/ / 其它队友文件）

| 文件 | 改动 |
| --- | --- |
| `block/entity/SequenceAssemblyExecutorBlockEntity.java` | F2：`accept` 重写 + javadoc；新增 `Map` / `LinkedHashMap` import |
| `network/SetStepMachinePacket.java` | F5：`handle` 取服务端显示名；javadoc 说明 name 是冗余字段 |
| `block/entity/SequencePatternTerminalBlockEntity.java` | F5：`clampStepMachineName` + `setStepMachine` 收口；F8：删重复 `writeCandidates` |
| `block/entity/SequenceExecutionChamberBlockEntity.java` | F6：`unitSlots` 注释、删 `putUnit`/`takeUnit`、删无用 `Player` import、小节标题；F7：改名 + `@Deprecated` |
| `block/SequenceExecutionChamberBlock.java` | F6：类 javadoc + `useWithoutItem` 注释 |
| `tools/selfcheck_round120_seq_audit_debt.py` | 新增自检（53 断言 / 16 反例） |
| `docs/ROUND120_SEQ_AUDIT_DEBT.md` | 本文件 |


## 未验证 / 未覆盖（诚实清单）

1. **没有实机验证**：没有进游戏、没有跑订单、没有跑 gradle 构建（纪律要求）。编译校验仅覆盖本轮 5 个改动文件
   （`javac` + `build/lead_compile` 的类作为依赖），**不等于** `gradlew build` 通过。
2. **F2 的 (b)(c) 边界未修**（见上）：跨资源「合计超容」和「模拟→执行被抢空间」仍会落地部分资源；
   真正原子需要上游缓冲（改产品行为），自检 ②-7b 只断言「不劣于旧实现」。另**未覆盖**「RS 在 SIMULATE 与 EXECUTE
   之间换了迭代输入」（本方法与 RS 内部暂存无共享状态，无法检测）。
3. **F5 未覆盖真实服务端报文**：没有网络抓包 / 双端联调；只验证了「服务端取显示名的代码路径」与「256 收口」。
   另注：执行仓自己的名字（`SetChamberBindingPacket.name`）**同样没有长度上限**，本轮范围外，只报告（见下）。
4. **F6 删除的 `putUnit` / `takeUnit` 的引用面**：用全仓文本检索（`src/`、`tools/`）确认零调用、无反射/mixin 字符串；
   若存在运行时按名字反射的外部依赖（本工程未发现），不在检查范围内。
5. **F7 保留的方法体**：`rscc$legacyDropOutputContents` 体内逻辑仍与 `release()` 语义不一致（只是历史快照），
   本轮**没有**评测其行为，只要没人调用就无影响。**F8 只做去重**：未验证 NBT 写入顺序是否被其它 mixin 观察（同键同数据，覆盖无副作用）。

## 新发现（只报告，未顺手改）

1. **`SetChamberBindingPacket.name`（执行仓自定义名）同样没有长度上限**（`network/SetChamberBindingPacket.java:84` /
   `:92` → `setChamberName`），与 F5 同一类问题但**在本次写域外**。它还会成为 F5 的「服务端显示名」来源 ⇒
   极端情况下 `stepMachineNames` 里存的就是一段长名字（已被 clamp 到 256，但执行仓自己的 NBT 仍会存全长）。
   建议下一轮按同一口径（`MAX_IMPORT_STRING_LENGTH`）收口。
2. **`tools/simplify_lang_texts.py:36` 里的**历史**文案仍写「右键放入/取出单元样板（6 格）」**，
   与实际的「右键开 GUI + 54 格」不符。当前语言文件（`zh_cn.json:183` / `en_us.json:183`）已是
   「存取单元样板（54 格）」，所以只是生成脚本的陈旧硬编码 —— 谁再跑那个脚本就会把错误文案写回语言文件。**未改**（语言文件 + 其它队友文件）。
3. **§9 第 1 条的集群判据脆弱点**（`rscc$clusterStandalone()` 用流体容量当代理判据）与 `rscc$legacyDropOutputContents`
   的历史实现（若接回会绕过集群移交）都仍在、本轮未动：前者建议改成「载荷身份」判据，后者建议连同 `RsccMachineCluster` 复核后删除。
5. **`tools/selfcheck_round37_pattern_kind_gates.py` 当前是红的（1/37），与本轮改动无关**：它锚定
   `menu/ChamberUnitsSummaryMenu.java` 里 `if (SequenceUnitPatternItem.isUnitPattern(unitSlots.getItem(i))) {`
   这一整行，而该文件在本轮同时段被**另一组队友改成了** `final boolean pattern = …; if (pattern) {`（工作区未提交改动，
   文件 mtime 19:11）。本轮对 summary menu **一行未动**；请由该组或 Lead 更新这条过期锚点（改成断言
   `final boolean pattern = SequenceUnitPatternItem.isUnitPattern(unitSlots.getItem(i));` + `if (pattern) {`）。

## 复核过、确认没问题的点（本轮顺带看过的，避免下一轮重复）

- `listChambers()` 只列「已绑定」的仓、零缓存现算，`SetStepMachinePacket` 的坐标校验沿用它 ⇒ 指派目标语义未变；
  `stepMachineNames` 的消费面只有 `SyncStepMachinesPacket`（显示）与 `saveAdditional`（落盘）⇒ 本轮改名字来源**不可能**影响引擎认领逻辑。
- `getChamberDisplayName()` 在自定义名称为空时回退坐标字符串，永不返回空串 ⇒ F5 的「取不到显示名」只在
  坐标解析失败时出现（此时已 `return`），客户端 `name` 兜底实际是**防御性死路**；
  `SequenceExecutionChamberBlock#onRemove` / `getDrops` 与 `BlockContentReleaser` 的接线未动。
