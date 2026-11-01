# 第 108 轮：能量条统一 / 容器代理器识别精妙背包 / 喷气背包耗电

本页记录 1.2.0-beta.11 里「第 108 轮」三件事的落点与验收口径，供接手者与交付说明引用。

## A. 所有「纯电」GUI 能量条统一复用 RS 原版实现

**用户口径**：「所有那种纯电的能量条 —— FE 能量立方的、发电器的 —— 全都不符合要求；应该保持和范围充电器、以及控制器（精妙存储自己带的）一样。应该直接复用他们的代码，而不是自己画一坨那么丑的。」

**参考实现（反编译实证，不是猜）**：

| 项 | 内容 | 出处 |
| --- | --- | --- |
| widget | `com.refinedmods.refinedstorage.common.support.widget.ProgressWidget`（RS 2.0.9 自家类，直接 import） | RS sources jar `.../common/support/widget/ProgressWidget.java:17-49` |
| sprite | `refinedstorage:widget/progress_bar`（16×70、6 色：横向 3 段 + 1px 纵向交替纹理） | 同上 `:18`；`assets/refinedstorage/textures/gui/sprites/widget/progress_bar.png` |
| 控制器对照 | `ControllerScreen:39-52` 用同一 widget（16×70）；那圈灰底/白下边是 `controller.png` 的像素，我们引用不到也不需要 | RS sources jar |

**共享类** `client/widget/RsccEnergyBar`：

```java
public static final int WIDTH = 16;               // = RS sprite 宽，不许改
public static final int DEFAULT_HEIGHT = 51;      // 参考实现（范围充电器）高度
public static final int COLOR_FRAME = 0xFF000000; // = 范围充电器 renderBg 第 1 行 fill
public static final int COLOR_TRACK = 0xFF2C2C2C; // = 范围充电器 renderBg 第 2 行 fill
public static void drawTrack(GuiGraphics g, int x, int y, int height);
public static ProgressWidget create(int x, int y, int height, LongSupplier stored,
                                    LongSupplier capacity, Supplier<List<Component>> tooltip);
public static double ratio(long stored, long capacity);         // 0 除 + >100% 夹紧，全仓唯一算法
public static Component valueLine(long stored, long capacity);   // "1,234 / 5,000,000 FE"（千分位）
```

调用点与几何（**像素级**）：

| 界面 | 改前 | 改后 |
| --- | --- | --- |
| 范围充电器 `RangeChargerScreen` | 内联 `new ProgressWidget` + 2 行内联 `fill`（魔数 26/20 两处） | 调共享类，**外观像素零变化**（同坐标/尺寸/色值），仅 tooltip 数字加千分位 |
| 分解发电机 `DecompositionGeneratorScreen` | 12×64 琥珀 `#E0B32A` 全自绘 | 16×51 @ (193,50)，外框 18 宽贴面板右缘、与左侧进度条共 1px 黑线；进度条保持自绘；tooltip 继续用既有 `decomposition_generator.energy` 键 + 千分位 |

> **第 123 轮更新（2026-10-31）**：本行已作废 —— 分解发电机界面整面重做后，**自绘竖直进度条被删除**，进度改由原版熔炉箭头表达；能量条改到 `(158,18)` 16×51；整个界面**只剩这一条条状控件**。以 `docs/ROUND123_DECOMPOSITION_GENERATOR_REDESIGN.md` 与 `support/DecompositionGeneratorSpec.java` 为准。
| FE 能量立方 `FEEnergyCubeScreen` | 凹槽在贴图里 14×60 @ (14,18)，填充自绘 12×58 `#0D8FA5` + 顶部 1px `#18B6D1` | 16×58 @ (14,19)，外框 18×60 @ (13,18) **整条盖住旧凹槽**（逐像素证明 x=28..30 / y=18..77 是纯底板 `#C0C0C0`、不遮任何元素）；界面内**零 `guiGraphics.fill`** |

**tooltip 不叠**：RS 的 `ProgressWidget` 自己会在 hover 时画 tooltip，所以立方 `isOverReadout()` 先 `isOverEnergyBar()` 让位、发电机删掉手画的能量 tooltip 分支。

**不纳入统一**：电动飞行背包 HUD 的 96×8 横向条 —— 不是 GUI 能量条、无 hover/tooltip 语义、sprite 是竖向 16×70，硬套会变形。理由写在 `selfcheck_round108_energy_bar_unified.py`。

**验收**：`selfcheck_round108_energy_bar_unified.py`（8 断言 + 9 反例：共享类锚点、参考实现自己也走共享类、三家零自绘、几何矩形、tooltip 让位、无新增 PNG、HUD 不动、network/support 不引用）；`selfcheck_round99_energy_cube.py` ⑦-a **反签**（旧判据「必须自绘、不许 ProgressWidget」→ 新判据「必须走 `RsccEnergyBar`、禁任何 `guiGraphics.fill`、tooltip 不叠」），74 断言 / 60 反例。**观感未实机验证。**

## B. 容器代理器识别精妙背包（带储罐升级）「只有流体没有物品」

**根因（SB 3.26.3 / SC 1.5.17 反编译）**：

1. 物品与流体是**两条独立能力**（`ModItems#lambda$registerCapabilities$81/$82`），装储罐升级不会让物品那路消失 —— 我们代理器也确实是两路各查一次，不存在「第一个非空能力顶掉另一个」。
2. 能造出「物品 0 格 + 流体 N 罐」的只有两态：物品 provider 因 `contentsUuid` 为空返回 `EmptyItemHandler`；或物品那路抛异常。SB 的流体 handler 是**懒建 + 字段缓存**，所以「物品没了、流体还在」正是这个指纹。
3. 储罐升级是决定性变量：`TankUpgradeItem#getInventoryColumnsTaken() == 2` ⇒ `BackpackWrapper#getInventoryHandler()` 少 2 行 ⇒ `UUIDDeduplicator#isFirstBackpackPreferred`（比 `getSlots()`）里**装储罐的同款背包更容易输掉去重、被摘掉 storage UUID**。
4. **我们自己的真 bug**：旧 `resolveInner()` 解析前就写死身份、两路共用一个失败路径（异常连坐）、0 格被当成终态 ⇒ 退化一次就**永久粘住**，只能拆方块。

**修复**：两路各自 try/catch；身份在两路问完后才写；`slotsOf/tanksOf` 连第三方 `getSlots()/getTanks()` 抛异常也兜住；退化态 40 tick 自动重探（两路都通则立即停止重探，热路径不重解析）；新增 `InnerState` 四态 + 两路格数（数据槽 8/9/10），界面与 Jade 一律报事实；`FLUID` 态明说「物品栏不可读」+ 可操作提示（取出、在背包栏打开一次再放回）。

**验收**：`selfcheck_round108_container_proxy_backpack.py`（51 断言 / 5 反例 / 13 行为模型，含「两路并存时物品真进外部存储」「用户态 fluid-only 如实 0 格」「0 格→恢复后自动 BOTH」「物品抛异常时流体不连坐」）；既有 round54/89/93/95 的 4 处锚点诚实更新（判据未削弱）。**实机未验**：触发链最后一环（SB 去重是否真摘了那只背包的 UUID）置信中，实机若仍「只有流体」请取日志 `[rscc-proxy]`。

## C. 电动飞行背包耗电 32 → 8 FE/t（可配置）

- `Config.electricJetpackFlightCostPerTick` 默认 **8**（范围 1..Int.MAX，仍可配置）；
- `ElectricJetpackItem.DEFAULT_FLIGHT_COST`（配置未加载时回退值）同步 8；
- 口径不变：只在 `abilities.flying == true` 时按本值扣电（上升/悬停/下降同价），电量不够一次 tick 成本即断飞坠落；
- 注释 / CHANGELOG / `selfcheck_round99_jetpack.py`（新增 ①-e2 断言 + 反例「默认值改回 32 ⇒ 判负」）同步。

## 验收线（1.2.0-beta.11）

- `LEAD_COMPILE_OK sources=492`
- 全量自检 **151 个 / 0 失败**（`D:\mods\pyenv-staritem\scripts\python.exe tools/_run_all_selfchecks.py`；用缺 PIL 的默认解释器跑 `selfcheck_advancements` 会假红）
- 专服探针 **PASS**（`Done (2.206s)`，无 Mixin / dist 致命标记）
- 三处部署 sha256 一致：`b039c91a420b23364f0ff607ba9250f20f9cc0a47d1896839c50dadaf8d0ac8b`（2 751 171 B）
