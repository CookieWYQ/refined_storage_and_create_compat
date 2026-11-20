# 第 134 轮落地记录：概率配方改「按需补发」（1.2.0-beta.35）

- 日期：2026-11-08
- 规格：`docs/ROUND134_ON_DEMAND_SPEC.md`（契约、闭环/终止性推导、不做清单）
- 前情：第 133 轮（beta.34）修的是「多出的件收不回来」；本轮修的是**为什么会有多出的件**。
- 版本：`1.2.0-beta.35`

## 1. 用户原话（2026-11-08 01:14）

> 「但有个问题你没有想过，虽然说你的那个设计类型为好像说的很好，就是这个 20 除以 0.8 等于 25，但是你没有想过我的真正目的就只是做 20 件。但是你现在却多搞了一个。打个比方来说，我现在这个资源很紧张，你给我多搞了一个，那岂不是坏了我的事。所以说最关键的就是说**多的是针对于**（如果）我**现在 20 个金板没有完成你再多发**，而不是说不管怎么样都发。」

判据：允许的「多开」只有一种 —— 已有件失败、订单还差，才补发；不允许"按期望轮次"一次性预占起步原料。

## 2. 被驳回的旧设计

| 旧行为 | 出处 | 问题 |
| - | - | - |
| 非必得配方 `allowed = ceil(remaining / p)`，下单 20、p=0.8 ⇒ 在制上限 25 | `SequenceExecutionChamberBlockEntity#startCapacityForRecipe`（beta.34 及更早） | 开局多占 5 份起步原料；p=0.5 时可到 40 份。资源紧张时直接坏事 |
| 文档把它写成「设计行为 / 数学期望，不是 bug」 | `docs/ROUND133_CONTRACT.md:60`（原句）、`TECHNICAL_HANDOFF.md` §27.2（原句） | 与用户目标相反 |

两处文档已在第 134 轮更正：`docs/ROUND133_CONTRACT.md:60` 加删除线并指向本轮规格；`TECHNICAL_HANDOFF.md:2298` 加「【2026-11-08 更正，第 134 轮】当时写成设计行为是错的」；`CHANGELOG.md` 的 beta.34 条目与「不做」清单同步标注被推翻。

## 3. 新契约与代码落点

`capacity = max(0, R − inFlight)`，与 p 无关（`R` = 订单剩余件数，`inFlight` = 跨舱在制件数）。

代码（`src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/SequenceExecutionChamberBlockEntity.java`）：

| # | 位置 | 内容 |
| - | --- | --- |
| 1 | `startCapacityForRecipe` `:8393-8399` | 删除 `if (!guaranteedResult(recipeId)) { … Math.ceil(remaining / (double) p) … }` 整段；`final long allowed = remaining;`（`:8393`）、`budgetForLog = allowed;`（`:8394`）、`capacity = allowed - inFlight;`（`:8397`）、`final long result = Math.max(0L, capacity);`（`:8399`） |
| 2 | 诊断 `:8409` / `:8417` / `:8423` | `mainResultChance` 只在 `RsccAssemblyDebug.isEnabled()` 之后调用；trace 行尾 `;P=` + `pLog`，机器行 ` p=` + `pLog` + ` guaranteed`；p 只进诊断 |
| 3 | javadoc `:8318` / `:8324` / `:8330` | 标题「② 非必得（p < 100%）配方：不预开，按实际损失补发」；写明「按需补发」「与 p 无关」「不参与任何名额判定」；`:8330` 保留旧设计历史说明 |
| 4 | 注释 7 处 `:7274`/`:7278`/`:8395-8396`/`:9102-9103`/`:10123-10124`/`:10147`/`:11045` | 旧「概率放大 / ceil(R/p)」措辞全部改写；Java 语句 0 改动 |

保底边界：`remaining < 0`（订单不可读）⇒ `capacity = inFlight <= 0L ? 1L : 0L;`（`:8389`），只探路不放量。

## 4. 闭环与终止性（摘要）

- 失败件离开工位后，`inFlightBreakdownForRecipe`（`:8543`）四个分量都不再计它（①要求 `registrationCounts` 为真且工位/机械手真被占着；②③④只数真在存储/池/未交付清单里的件）⇒ `inFlight` 减 1。
- `R` 只在成品被认领时减 ⇒ 每失败一件，`R − inFlight` 当 tick 回到 1 ⇒ 补开一件；不会出现「名额 0 且无件在制」的僵局。
- 总开工数 = 订单件数 + 失败数（期望 `R/p` 轮，**串行**发生）；任何时刻 `inFlight ≤ remaining ≤ R`。
- 代价：p 极小时工期变长。这是用户选择的取舍（宁慢不多占料）。

## 5. 验收（Lead 串行执行）

| 项 | 结果 | 证据文件 |
| - | --- | --- |
| 编译 | **`LEAD_COMPILE_OK sources=526`** | — |
| 全量自检 | **202 个脚本 / 0 失败**（上轮 201） | `build\round134_selfcheck_all.log` |
| 新增 round134 自检 | **61 断言 / 8 反例 / 0 失败 / `ALL_PASS`** | `tools/selfcheck_round134_on_demand_replenish.py` |
| 旧自检（5 条） | 13 `75 checks` / 23 `63` / 26 `42` / 27 `41` / 29 `73`，全绿 | — |
| 专服探针 | **PASS**（`Done (1.489s)!`，无 Mixin/dist 致命标记，`run/` 只读） | `build\dedicated_server_probe.log`（442,097 B） |
| 构建 | **`BUILD SUCCESSFUL in 6m 56s`** | `build\round134_build.log` |
| jar | `rs_create_compat-1.2.0-beta.35.jar`，**2,953,437 B**，sha256 `8234b79e82e1fa6e63e7117fe205ee4f854c87b2aa48f777e6fdac9188af2ec8` | `build\libs\` |
| 部署 | **`DEPLOY_VERIFY_OK`**（四处 sha256 全等：build/libs / 本地实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.34 就地 `.disabled` | `tools\_verify_deploy_round134.py` |

自检怎么钉住新口径（不是删断言）：

- 源码正锚点 `final long allowed = remaining;` / `capacity = allowed - inFlight;` / `final long result = Math.max(0L, capacity);`；负锚点：无 `Math.ceil(remaining`、无 `(double) p)`、无 `if (!guaranteedResult(recipeId)) {`、去注释后无 `ceil`。
- 模型 `capacity = max(0, R − inFlight)`；p∈{1.0,0.8,0.5,0.25,0.1} 全扫描结果恒等（**对照旧口径会随 p 变化**，证明断言非永真）。
- 用户场景：(20,5,0.8)→15（旧 20）；(20,0,0.8)→20（旧 25 —— 正是"多占 5 份"）。
- 固定种子（LCG 134）补发模拟：t=0 只开 20；任意时刻 `inFlight ≤ remaining ≤ 20`；**每 tick 补发数 = 本 tick 失败数**；终局 `delivered=20`；同一事件流下旧口径 t=0 开 25 并违反不变量。
- 反例 A–H：(20,0,.8) 25≠20、(20,5,.8) 20≠15、capacity(0,0)=0、capacity(5,7)=0、订单不可读⇒0、p=1.0 新旧一致（必得语义没被改坏）、p=0.5 旧口径开 40、误写 `R − I·p` 被抓（16≠15）。

## 6. 未实机验证（诚实清单）

1. **没跑过真实订单**：以上只有源码锚点、模型/模拟、编译、自检、独立服务端探针级证据。
2. 建议验收动作（服务器）：
   - 站基地 `/rs_create_compat devlogs on`；
   - 下单 20 件精密构件（p=0.8）；
   - 开局看起步原料是否**只有 20 份**（旧版本是 25；`inflight=` 与 `cap=` 在 devlog 的 `[rscc-assembly]` 名额行里）；
   - 中途观察失败后是否**只补一件**（`C=` 名额在 1 与 0 之间摆动，而不是长期有余量）；
   - 完成后确认交付恰好 20 件、置物台无残留；再 `/rs_create_compat diag run` 看 `chambers[].inFlightUnits[]`。
3. beta.34 遗留项仍未验证：置物台残留是否消失、diag 是否拍到执行仓、停服重启后 `restored N in-flight unit(s) from NBT` 与三条回收路径。
4. p 很小时工期会变长（固有代价）。若要调"更快"，只能动**补发节奏**，不能重新引入"预开额度"（那会重新触碰本次驳回的红线，须先问用户）。

## 7. 交付物清单

- 代码：`SequenceExecutionChamberBlockEntity.java`（§3 四处）。
- 自检：`tools/selfcheck_round134_on_demand_replenish.py`（新，598 行）、`tools/selfcheck_round13_feedback.py`、`selfcheck_round23_machine_queue.py`、`selfcheck_round26_delivered_authority.py`、`selfcheck_round27_parked_product_capacity.py`、`selfcheck_round29_refill_machine_mode.py`（改守新口径）。
- 规格/记录：`docs/ROUND134_ON_DEMAND_SPEC.md`、本文件。
- 工具：`tools/_verify_deploy_round134.py`。
- 文档更正：`CHANGELOG.md`（beta.35 条 + beta.34 条两处纠错）、`TECHNICAL_HANDOFF.md`（§27.2 更正段 + 新增 §28）、`docs/ROUND133_CONTRACT.md:60`（删除线 + 指向本轮）。
- 版本：`gradle.properties:48` `mod_version=1.2.0-beta.35`、`README.md:11`、`README_en.md:11`。
- 未 commit / 未 push（用户硬约束）。
