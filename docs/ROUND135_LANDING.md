# 第 135 轮落地记录（1.2.0-beta.36）

日期：2026-11-13　触发：用户四条实机反馈 + 一条机制口径确认。

## 0. 这一轮修什么（用户原话 → 判定）

1. 能耗监视列表里「圈起来的数字和黑色的数字贴在一起」；纯英文名（`Interdimensional Wireless Transmitter`）不许强加中文译名，但也不许叠字；
2. 能耗监视的「高亮」根本用不了 —— 原话：「**你想着我打开这个高亮之后，我是不是关掉这个界面，那关掉界面之后高亮是不是也就关了。所以说你这完全就用不了。**」；
3. 高级远程多功能终端的「选择」按钮偶尔整块消失；
4. 精密构件仍有一件卡住无法继续组装（已导出服务端 devlog）；
5. 延长型总线的「六个面」归属口径：箭头面 + 4 侧面里的线缆算延长段，**箭头背面（尾部 / 底部）那条不算**；侧/顶线缆与底部线缆有接触 = **串线 ⇒ 判归属未确定**。

## 1. 能耗监视：右列叠字（已修）

### 根因（两处几何错误叠加）

- 「×N 台数」列右缘写死在 `ROW_RIGHT − 78`，而用量串（`79,436 FE/t（每刻）`）实测约 100px 宽 ⇒ 用量串左端越过台数右缘，两段数字压在同一段像素列上。截图里「黑字压灰字」就是这两段；
- 第三方模组超长英文名缩到 `RsccTextFit.MIN_SCALE = 0.6` 仍放不下时 `drawFitLine` 会**硬画出去**（第 104 轮已知边界），继续盖住右列。

### 新几何（不用写死列宽）

右列从右往左按**实测文本宽度**排：

```
usageX = px + ROW_RIGHT − width(usage)
countX = usageX − COLUMN_GAP − width(count)          // COLUMN_GAP = 6
showCount = countX − nameLeft − COLUMN_GAP ≥ MIN_NAME_W   // MIN_NAME_W = 32
rightEdge = showCount ? countX − COLUMN_GAP : usageX − COLUMN_GAP
nameLimit = rightEdge − nameLeft
```

- 放不下时优先级：**先撤台数、再撤用量**（两者都仍在行 tooltip 里），名字只占真正剩下的宽度；
- 缩到下限仍放不下的超长名（`nameTooLong`）走「**整行只画名字**」模式（画到行右缘，台数 / 用量进 tooltip）—— 宁可这一行少一列数字，也不让两段字共享像素列；
- **没有**给英文名加中文译名，也没有截断 / 省略号（第 104 轮口径不变）；
- 子行（`维度 + 坐标` vs 用量）同一套规则；两处都只保留**一处** `drawFitLine` 调用（第 126 轮 tooltip 覆盖扫描按调用点计数）。

`COUNT_RIGHT` 常量连同「写死 78px」的注释一并删除。

## 2. 能耗监视：高亮关界面就没了（已修）

### 根因

`EnergyAuditScreen.removed()` / `returnToTerminal()` 都直接 `EnergyAuditHighlight.clear()`。而能耗页是**整屏 GUI**：界面开着根本看不到世界 ⇒ 「选中 → 关界面 → 立刻撤框」这条链路等于永远看不到高亮。

### 修法

- 新增 `EnergyAuditHighlight.linger()`：**不撤框**，把到期时刻续到 `now + Config.energyAuditHighlightLingerTicks`（默认 **1200 tick = 60 秒**，范围 100–7200）；
- `removed()` / `returnToTerminal()` 改调 `linger()`；到期仍由原 TTL 逻辑自动灭，不留永久残影；
- 要立刻撤：重开监视页点「清除高亮」（走原 `requestClear()`，本地 + 服务端一起清）；
- 世界里的框仍只由**服务端回显**驱动（客户端不自己猜坐标），TTL 只是「关界面后的残留时长」。

## 3. 延长型总线：六个面归属 + 串线（已实现，口径待实机确认）

用户确认（2026-11-13）：

> 「肯定是视觉上看上去那个侧面的那个样子……如果说它是箭头朝上的话呢，那就是尾部，也就是底部……那个会串线的，然后那个肯定判定是未确定，不然的话这一切就没有意义了。」

### 判据（`RsccWireLinkSearch`）

- `SequenceExecutionChamberBlock.FACING` 就是模型箭头指向的那一面（对接机器 / 下游）。设 `attach` = 舱 → 线缆的方向：
  - `attach != FACING.getOpposite()`（箭头面 + 4 侧面）⇒ 计入 `bodyAttached`，照旧算可达执行舱；
  - `attach == FACING.getOpposite()`（尾部 / 箭头背面）⇒ 只记入 `tailAttached`，**不算本总线延长段**（那条是回主网络的上游干线）；
- 发布时：同一台舱**既**在 `bodyAttached` **又**在 `tailAttached` ⇒ `crossTalk = true` ⇒ `LinkWalk#ambiguous()` 为真 ⇒ 服务端判「归属未确定」并停用延长型（与「≥2 条链」同一出口，`Report.disabled`）；
- 扫描顺序调整：执行舱邻格在 `visited` 去重**之前**分类（否则同一台舱只会在第一次碰到的那一面被登记，串线永远发现不了）；非舱非线缆邻格仍走原 `visited` 去重；
- `LinkWalk` 新增第 5 个分量 `crossTalk`；`restart/publish` 清理两张新表（内存有界，不跨趟残留）。

### 待实机确认的唯一一点

若实机发现方向反了（即「箭头面」实际渲染在 `FACING.getOpposite()` 一侧），只需把 `step()` 里 `attach == facing.getOpposite()` 改成 `attach == facing` 一处，其它判据不动。

## 4. 精密构件卡住：证据（服务端 devlog，远程 `create_enjoy`）

- 拉取：`rscc_diag/20261113-211858/snapshot.json`（21:18:58 导出），落到 `diag/pull_20261113/snapshot.json`；
- 任务：`create:precision_mechanism` ×5，`RUNNING`，`delivered=3`，`percentageCompleted=1.00`；
- 执行舱 `5*5-机械手使用`（419,100,98）的在制登记 12 件，其中两件 **step=-1 的金板**：

| 工位 | 物品 | step | age（tick） |
|---|---|---|---|
| (420,100,102) | `create:golden_sheet` | -1 | 9,409,580 |
| (419,100,102) | `create:golden_sheet` | -1 | 9,409,805 |

9.4M tick ≈ 5.4 天（= 本次开服 11-08 10:38 当天推进去），从未进入第一个 deploying 工序。按第 134 轮的
`capacity = max(0, remaining − inFlight)`：`remaining = 2`、`inFlight(golden_sheet) = 2` ⇒ 名额 0 ⇒ **永远不再补料**，任务死在这里。

### 为什么这一轮还没有改代码

两条候选修法：

- **只解除占额**（把这两件当成死件不计入名额）：名额回到 2，会**再发两片金板**；如果那两片金板日后突然被机器拉进工序，就会多出两件 —— 正是第 134 轮刚被用户驳回的「多占料」，不能默认这么干；
- **先收回台上那件再撤登记**（原样搬回网络 / 舱内存储，再删 `inFlightUnits` 条目）：物品守恒、名额自愈，是正确方向；但回收路径要接 `RsccChamberImportStrategy` + `reclaimStationResidueAt` 的既有判据（残留三分类 / `machine_full` 强旁证 / 观察窗口），而这两件是 **step=-1 的裸原料**，不在既有三分类里，硬接有搬错风险。

因此本轮只交付**证据 + 判据**（本文件 §4），回收代码留到下一轮单独做，并配「守恒 + 不误搬 + 超龄自愈」三条自检。

## 5. 「选择」按钮偶尔消失（未修，待一份现场）

截图里「总样板」右侧的控件在界面打开期间变成空按钮。现有信息不足以区分「状态同步空窗导致标签为空」与「`init()` 重建时控件未重加」；需要界面正好复现时的一份 devlog / 截图（或说明是「第一次打开就有」还是「放/取总样板之后才有」）。

## 6. 本轮验证

- `LEAD_COMPILE_OK sources=526`（独立 javac 复算，`D:\gradle\caches` 跟随 `GRADLE_USER_HOME`）；
- 全量自检：202 个脚本 / 0 失败（`build/selfcheck_round135b.log`；本机补装 `numpy` 后 `selfcheck_gui_slot_alignment.py` 恢复 ALL_PASS）；
- 受影响脚本改守新口径：`selfcheck_round34_chain_dedupe.py` ⑪、`selfcheck_round60_wirelink_exhaustive.py` ③（`ambiguous()` 现在含 `crossTalk`，仍不含 `exhaustive`）；
- 新增语言 / 配置项：`energyAuditHighlightLingerTicks`（默认 1200，范围 100–7200）。

## 7. 构建 / 部署（本轮实际值）

| 项 | 值 |
|---|---|
| 版本 | `1.2.0-beta.36`（`gradle.properties` 由 beta.35 升上来） |
| 构建 | `BUILD SUCCESSFUL`（`--offline -x test`） |
| jar | `rs_create_compat-1.2.0-beta.36.jar`，**2,954,906 B**，sha256 `6a3b665f6bc0925f832e064daaf1c01c47e36b9bc0a62af322898f8d6b172e2c` |
| 部署 | 四处 sha256 全等：`build/libs` / 本地实例 `D:\minecraft\.minecraft\versions\Create：Enjoy\mods` / SFTP `/mods` / SFTP `/client_files/mods`；beta.35 已 `.disabled` |
| 最终自检 | **202 个脚本 / 0 失败**（`build/selfcheck_round135c.log`） |

**注意**：服务端 `/mods` 已换新 jar，但**正在跑的服务端进程仍是旧版本**，要等你重启服务器才生效。

## 8. 本轮明确没有做的事（避免误以为已修）

- 精密构件的**回收代码**（§4：正确修法是守恒回收，不是解除占额）；
- 「选择」按钮消失（§5：缺现场）；
- 真实游戏内验证：高亮关界面后的 60 秒残留、六面归属的真实接线、列表右列的新排版，均只有编译 + 自检 + 像素级几何推导，没有实机截图。实机验收点见 §9。

## 9. 实机验收清单（下一轮开始时按这个对）

1. **高亮**：监视页点开某类型 → 点某一台子行 → 关界面（Esc）→ 应看到橙色框持续约 60 秒；重开界面点「清除高亮」应立刻灭；
2. **右列叠字**：列表字体重叠应消失；纯英文名那一行允许「整行只显示名字」，数值看 tooltip；
3. **六面归属**：总线经「箭头面 / 侧面」接执行舱 → 正常延长型；若把线同时接到箭头背面（尾部）→ 总线应判「归属未确定」并停用（tooltip 文案仍是旧的「可达 N 台」口径，下一轮补「串线」专用说法）；
4. **方向确认**：若你发现「箭头面」其实是 `FACING` 的**反面**（即我理解反了），告诉我，改 `RsccWireLinkSearch#step` 里一个比较即可。

