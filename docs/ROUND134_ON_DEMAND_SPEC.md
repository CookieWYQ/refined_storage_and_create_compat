# 第 134 轮契约：概率配方不再按期望轮次预开，改为「按需补发」

> 生成时间：2026-11-08（Asia/Shanghai）。版本目标：`1.2.0-beta.35`（基线 `1.2.0-beta.34`，第 133 轮）。
> 本轮是**用户驳回上一轮设计辩护**后的一轮；只改「开工名额」这一处判定，收尾 / 清扫 / diag 沿用第 133 轮。

## 1. 用户原话（2026-11-08）

> 「但有个问题你没有想过：虽然说你的那个设计好像说得很好，就是这个 20 除以 0.8 等于 25……但是你没有想过我的真正目的就只是做 20 件，但是你现在却多搞了一个。打个比方来说，我现在这个资源很紧张，你给我多搞了一个，那岂不是坏了我的事？所以说最关键的就是说：多的是针对于——如果说我现在 20 个金板没有完成，你再多发，而不是说不管怎么样都发。」

拆成三条硬约束：

1. **起点不许预占**：订单 20 件时，同时在制的起步原料不得超过 20 件（p=0.8 也不例外）。
2. **只有真失败才补**：多出来的那一件必须对应「已经确实损失了一件」，而不是「按期望值算，将来可能会损失」。
3. **资源紧张优先**：任何时刻「在制件数 > 订单还差件数」都算违约，即使回收路径能把它收回来。

## 2. 被驳回的旧设计（beta.34 及更早）

```java
// 旧：概率配方按期望轮次放大（本轮删除）
if (!guaranteedResult(recipeId)) {
    final float p = mainResultChance(recipeId);
    if (p > 0F && p < 1F) {
        allowed = (long) Math.ceil(remaining / (double) p);
    }
}
capacity = allowed - inFlight;
```

- 推导没错（第 R 次成功的投料次数期望 = R/p），但它优化的是**期望完成时间**，代价是**起点多占**：
  p=0.8、R=20 ⇒ 一上来就投 25 份起步原料。
- 用户要的是相反的东西：**先按订单量开工，缺了再补**。资源紧张时，期望值优化是负收益。

## 3. 新契约（本轮唯一改动）

`src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/SequenceExecutionChamberBlockEntity.java#startCapacityForRecipe(String)`：

```java
} else {
    final long allowed = remaining;   // 必得 / 非必得同一个公式
    budgetForLog = allowed;
    capacity = allowed - inFlight;    // 与 p 无关
}
```

形式化（每 tick、每条配方）：

```
capacity = max(0, R − inFlight)
R        = max(0, ordered − delivered)        // computeRemainingOrderUnits()
inFlight = 跨执行舱在制件数(reg + store + pool + settled)   // inFlightUnitsForRecipe()
```

- **p 不再参与名额判定**。`mainResultChance` / `guaranteedResult` 保留，但只喂诊断行（`;P=0.800` / `p=0.800 guaranteed`）。
- 边界照旧：`R == NO_ORDER_READABLE`（读得到但没需求）⇒ 名额 0；`R < 0`（判不出来）⇒ 有在制就 0、没有就 1；
  `R == 0`（交付满）⇒ 0。

## 4. 为什么「不预开」也能把订单做满（本轮论证的核心）

1. 一件在制件**落空**（轮盘没出主产物 / 被销毁）⇒ 它从工位消失 ⇒ `inFlightBreakdownForRecipe` 的四分量都不再计它
   ⇒ `inFlight` 减 1。
2. `R` 只在**成品被认领**（`delivered` 增加）时才减 1。
3. 因此失败发生后 `capacity = R − inFlight` 当 tick 就回到 1 ⇒ 备料侧立刻补一份起步原料、再开一件。

**终止性**：每件在制件只有两种归宿 —— 交付（`R` 减 1）或损失（`inFlight` 减 1）；
所以补发次数 = 实际损失次数，不会自我放大，也不会出现「越补越多」。

**代价（如实写明）**：p < 1 时，完成时间不再受「R/p 次轮盘」的理论下界支配 ——
每次失败后都要等一两次推料 tick 才补上，等于把失败的代价放到关键路径上。
换来的是用户要的硬不变量：**任何时刻同时占用的起步原料 ≤ 订单还差的件数**。

## 5. 不做（本轮明确不改）

1. **不改 `inFlight` 的四分量口径**（reg / store / pool / settled）：它们回答的是「这件还在不在产线上」，
   与「该不该预开」是两件事。
2. **不改登记键语义**（推料目标仍作键）—— 那是第 133 轮修好的收尾路径的前提。
3. **不改第 133 轮的整工位收尾 / 清扫 / diag**：本轮不碰 `stationGroupPositions`、`stationHoldsPipelineResidue`、
   `clearDeadStationPieceAt`、`rscc$diagReport`。
4. **不给概率配方加"预测补发"窗口**（例如看到 p 就提前补）：那等于换个方式预占，与用户口径直接冲突。

## 6. 验收口径

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=526`。
2. 全量自检 0 失败；其中：
   - 新增 `tools/selfcheck_round134_on_demand_replenish.py`（源码锚点 + 模型 + 补发模拟 + 反例）；
   - 五条旧自检的 `ceil(R/p)` 锚点与模型改判为「按需补发」：
     `selfcheck_round13_feedback.py`、`selfcheck_round23_machine_queue.py`、
     `selfcheck_round26_delivered_authority.py`、`selfcheck_round27_parked_product_capacity.py`、
     `selfcheck_round29_refill_machine_mode.py`。
3. 专服探针 `tools/verify_dedicated_server.ps1` → PASS。
4. `gradlew.bat build --no-daemon` 成功，jar 四处部署（build/libs / 本地实例 / SFTP `/mods` / SFTP `/client_files/mods`）sha256 全等。
5. 实机判据（交给用户）：下单 20 件精密构件（p=0.8）→ 观察任意时刻在制件数 **不超过 20**；
   失败一件后应当看到「又补一件」而不是「一次性多开」；最终交付正好 20 件。
