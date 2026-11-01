# 第 124 轮（beta.25）：用户实机反馈四组的落地记录

> 需求来源：`docs/ROUND122_USER_FEEDBACK.md` 第 1-1 / 2-1 / 2-2 / 3-1 / 3-2 / 3-3 / 4 条
> （用户 beta.22 实机试玩时的口述 + 两张截图）。本文件记录**根因 → 修法 → 证据 → 未验证项**，
> 供下一次审计逐条回查。四条任务在共享任务板上分别是 `task-11` / `task-12` / `task-13` / `task-14`。

## 0. 一轮话总结

| # | 用户原话要点 | 结论 | 自检 |
| --- | --- | --- | --- |
| 1-1 | 「网格的第 2 行上压着一行说明文字」「(标记 0 / 288 条) 与 (空的区域什么都不吸) 两行贴得过近」 | **修了**：三处几何 bug，空状态提示搬出网格、行距改 12、面板缩高消除底部死区、同义文案去重 | `selfcheck_round124_area_mark_layout.py` 68 断言 / 19 反例 |
| 2-1 | 「改名点确定了、看着改了，重新打开还是没改」 | **修了，根因 4 个**，其中主因是「打开界面首帧就把名字清空」 | `selfcheck_round124_area_rename.py` 38 断言 / 16 反例 |
| 2-2 | 「输入鼓风机 → 点下一个，区域二名字直接变成鼓风机」 | **修了，三个缺陷叠加**：焦点不转移 + 同步早退 + 提交下标取即时值 | 同上 |
| 3-1/3-2 | 「为什么会有应力发电机 / 控制器，它不能存电」 | **修了**：候选池判据从 `storage != null` 改成 `canReceive + 容量>0`；控制器按命名规约单列黑名单（反编译证据见 §3） | `selfcheck_round124_charger_candidates.py` 44 断言 / 12 反例 |
| 3-3 | 「我们模组里的范围充电器怎么都没有」 | **修了**：候选登记从「跳过自身之后」搬到「扫描前单独登记自身」，登记与充电彻底分离 | 同上 |
| 4（F1） | 「输入输出针对整个集群；只有挖下来才分自己那一块」 | **审计：6 条不变量全部已正确，源码 0 改动**；用 71 断言钉死。另发现并转修一个真 bug（R-1，int 饱和导致提前判满） | `selfcheck_round124_energy_cluster.py` 71 断言 / 21 反例 |

**本轮验收**：`LEAD_COMPILE_OK sources=505`；全量自检 **185 个脚本 / 0 失败**；四个新脚本合计
**221 断言 / 68 反例**；专服探针 **PASS**（`Done (1.545s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）；
`rs_create_compat-1.2.0-beta.25.jar`（**2,842,458 B**，sha256 `54250b9a43b3ad901a009c19d232b566681aebdee62f2edef26bb3deb0b1a35c`）
四处回读一致。**未实机验证**（见 §6）。

---

## 1. 区域标记界面文字重叠（124-A，`AreaMarkScreen.java`）

### 1.1 三处几何 bug（都在真源码里算出来的，不是目测）

| # | 旧代码 | 后果 |
| --- | --- | --- |
| a | 副标题 `SUBTITLE_Y = 22`，列标签 `py + GRID_Y - 10 = 24` | 两行字**互压 7px**（字体行高 9） |
| b | 空状态提示 `py + GRID_Y + GRID_H / 2 - 4 = 66`，而格子行 y = 34/52/70/88（PITCH = `McGui.SLOT_FRAME_SIZE` = 18） | 提示直接画进网格 = 用户说的「第 2 行压着一行说明文字」 |
| c | 数量行 `INFO_Y = 140`、状态行 `140 + 9 = 149` | 行距正好等于行高，顶死（用户「贴得过近」） |

### 1.2 修法（**网格一格没挪**）

- 网格常量 `COLS/ROWS/PITCH/LEFT_X/RIGHT_X/GRID_Y=34/GRID_H` 全部保留（自检 B1 反向钉死，防后人再挪）。
- 板面上下只有 33px（面板上沿 → 槽框上沿 33），放不下「标题 + 副标题 + 列标签」三行 ⇒
  **标题与副标题并成一行**（`标题 · 区域名 · 体积`，宽度按行尾算，放不下走 `drawFitLine` 整行缩字号，
  **绝不截断**）；列标签上移到 `LABEL_Y = 20`。
- **空状态提示移出网格**，与「数量摘要 / 状态行」一起排成三行：`INFO_Y = 109`、`HINT_Y = 121`、
  `EMPTY_Y = 133`，统一行距 12（行高 9 + 间隙 3）。
- **顺手修掉同类 bug**：`drawTooltips` 的命中区原锚在「早已没人画的 110..128 空区」，改成真正画了字的
  两行（`HINT_Y` / `EMPTY_Y`）。
- **同义文案去重**（收尾补丁 ②）：空状态时第 2 行「还没有标记 ⇒ 这个区域什么都不吸」与第 3 行
  「空的区域什么都不吸（安全默认）；标记跟着选定器走」在说同一件事。`hint.blocks_all` 改成
  「标记跟着选定器走（换一个选定器 = 另一套标记）。」——去重但不删信息（「不吸」仍在状态行、
  「选定器」仍在空状态行），并加断言 K1–K4 钉死：两行不得同时出现「什么都不吸」，去标点后最长公共子串
  ≤ zh 5 / en 8 字（真值 zh 2 / en 5）；反例 C-12a/b 塞回旧文案必须判负。
- **面板死区收口**（用户「非常丑」的一部分）：`PANEL_H 232 → 177`、`BUTTON_Y 208 → 152`、
  新增 `BUTTON_H = 18`。总账 **177 = 最后一行文字底 142 + 10 间隙 + 18 按钮 + 7 底留白**；
  旧版这里是 **67px 纯空白**（自检 F2/F3/F4 + 反例 C-5/C-6b 判负）。

### 1.3 证据

- 修后几何：文字行 `8..16 / 20..28 / 109..117 / 121..129 / 133..141`；格子行 `33..50 / 51..68 / 69..86 / 87..104`；
  **5 行 × 4 行 = 20 组两两求交，全部为空**（自检 C1 用真源码正则解析 + 矩形求交，不是截图目测）。
- 离屏 mock：`build/mock/area_mark_mock_3x.png`（干净版）、`area_mark_mock_3x_boxes.png`
  （红=文字行 / 蓝=格子 / 绿=按钮 / 品红=面板底边 / 青带=10px 间隙 / 黄带=7px 底留白）、
  `area_mark_title_worst_3x.png`（24 字最坏标题行按真实缩放画出来）。
- 24 字最坏宽：`Area.MAX_NAME_LENGTH = 24`、体积上限 1048576 时，en_us 最坏需 311px / 可用 255px
  ⇒ 缩到 **0.820×≈7.4px 字高**，仍高于 `MIN_SCALE = 0.6` 的兜底线；`drawFitLine` 只有「整行等比缩放」
  一条分支（自检 ⑦-5 断言方法体内无 `…`/`substring`/`truncat`），所以「· 体积」**不可能被挤掉**。

---

## 2. 区域选定器「改名两连」（124-B）

### 2.1 2-2「名字串到下一个区域」= 三个缺陷叠加

1. `mouseClicked` 在 `super.mouseClicked` **之前** `return true` ⇒ 点列表行时原版没机会把焦点从 `EditBox` 移走，
   名称框一直 focused。
2. `syncFields()` 旧实现「任何输入框有焦点就 return」⇒ 名称框不换成新行的名字，`pendingRename` 还是旧字符串。
3. `flushRename()` 的提交下标读**即时** `selection.selectedIndex()`，切行后就是新行；**且提交后
   `renameAtMs` 不复位 ⇒ 这条错位每帧重投**（不是「偶发一次」）。

行为模型自检 C-2 按同一时序断言：旧语义 `sends = [(0,"鼓风机"), (1,"鼓风机")]`（正是用户看到的现象）；
新语义只有 `[(0,"鼓风机")]`。

### 2.2 2-1「看着改了、重开没改」= 四处

| # | 根因 | 说明 |
| --- | --- | --- |
| ①（主因） | **打开界面首帧就把名字清空** | `pendingRename` 初值 `""` + `renameAtMs` 初值 `0` + `render` 每帧调 `flushRename()` ⇒ 首帧 `wanted=""` ≠ `current="鼓风机"` 立刻发 `ACTION_RENAME(index, "")`；服务端**无 CAS、无确认**照单全收；`Area` 归一化把 `""` ⇒ `null` 落盘 |
| ② | **`ACTION_REPLACE` 抹名字** | `readAreaFromFields` 只拼 6 个 int（无名字），服务端旧代码裸 `replace(index, area)` 覆盖；而客户端乐观副本先 `withName(旧名字)` ⇒「看着改了」但服务端已无名。第二入口：`AreaSelectorHandler.resizeSelected`（Ctrl+滚轮改尺寸） |
| ③（放大器） | 去重判据读**客户端乐观副本** | 副本一旦领先（①/②/被拒无回滚都能造成），之后每次「点确定」都被判「没变化」⇒ **永不重发** |
| ④ | 静默丢弃无回执 | 旁观者 / 目标解析失败都是裸 `return`；主手路径 `reject()` 只 `broadcastFullState()`，玩家零感知 |

**排除**：(c) NBT / codec 链无缺陷（`Area.CODEC` / `Area.STREAM_CODEC` / `AreaSelection.CODEC` /
`AREA_SELECTION` 注册 `persistent+networkSynchronized` / BE `saveAdditional`+`loadAdditional` 整份走区域槽）；
(d) 快照包只存 `last`，从不写物品副本。
**部分成立**：(a) 服务端 RENAME 确实无 compare-and-set，但错位在客户端，不是「旧快照被丢」；
(b) `AreaSelectorTargets.resolve` 与 `targetStack()` 同口径，只是失败分支静默 → 归 ④。

### 2.3 修法

- 新增 `pendingRenameIndex`（**打字时**捕获）+ `pendingApplyIndex`（坐标框同病同修）+ `nameBoxIndex`，
  并用 `renamePending` 布尔取代「靠 `renameAtMs` 时间戳猜」；`flushRename()` 只在「捕获行 == 当前选中行」
  时提交且**一次性**；行已被删则 `discardRename()` + 状态提示。
- `syncFields()`：`selectedIndex != nameBoxIndex` 时**无条件刷新**名称框（`focused` 早退只留「同一行」），
  **选择变化时保持焦点**（玩家动作是「点一行 → 接着改这一行」；旧行名字已在切行前提交，不丢数据）。
- 五个切换入口（点行 / 建 / 复制 / 删 / Ctrl+滚轮）统一「先提交旧行，再重读 `currentSelection()`」
  —— 不重读会被切换前那份快照把乐观改名写回去。
- 服务端 `AreaSelectorEditPacket`：`ACTION_REPLACE` 改成 `withName(previous.name())`（换几何不许抹名字）；
  旁观者 / 解析失败改走 `reject()`；`reject()` 主手路径补**客户端可见**回执（复用既有 `gui.*.err.*` 键，
  **不新增语言键**）。

---

## 3. 范围充电器过滤界面「世界中的方块」候选池（124-C）

### 3.1 根因

候选池 `seenBlocks` 的唯一写入点是 `recordSeenBlock(...)`，唯一调用点在 `chargeBlock(...)`，而旧代码：

- 只判 `storage != null` 就登记 ⇒ **「有 FE 能力但只出不进 / 零容量」的方块全被列进界面**
  （其它模组的发电机、只放电的方块、网络控制器…）。
- `scanBlocks` 先 `if (pos.equals(worldPosition)) continue;` **跳过自身**，而登记只在
  `chargeBlock`（只对非自身调用）⇒ **自家范围充电器永远缺席**。

### 3.2 修法

- 新增唯一判据 `isChargeableBlock(IEnergyStorage)` = `storage != null && getMaxEnergyStored() > 0 && canReceive()`，
  与客户端物品池 `ChargerFilterCandidates#all` 的表达式**逐字同源**；`chargeBlock` 先过它（顺带省掉
  对「只出不进」方块的每 tick 空调用）。
- `doCharging` 在 `seenBlocks.clear()` 之后、扫描之前调 `recordSeenSelf(level)` —— **候选登记与充电彻底分离**：
  自身进候选池，但两条扫描的「跳过自身充电」原样保留。
- 登记收口到 `recordSeenCandidate(state, storage)` 一个方法（判据 + 黑名单都在这里），
  `recordSeenBlock` 只负责「取方块注册名 + `addBounded(MAX_BLOCK_ENTRIES)`」。

### 3.3 RS 控制器反编译证据（**不是猜**）

RS 2.0.9（`refinedstorage-neoforge-2.0.9.jar`，`javap -p -c`）：

- **暴露 `Capabilities.EnergyStorage.BLOCK`：是**。`ModInitializer#registerCapabilities` offset 373-387：
  `getstatic Capabilities$EnergyStorage.BLOCK` → `BlockEntities.getController()` → `registerBlockEntity`，
  provider = `new EnergyStorageAdapter(controllerBlockEntity.getEnergyStorage())`。
- **`canReceive()`：恒 `true`（硬编码 `iconst_1; ireturn`）**，`canExtract()` 反而是 `false`。
- **容量恒 > 0**（`new EnergyStorageImpl(clamp(Config.controller.energyCapacity, 1, Long.MAX))`）。

⇒ 控制器会被 `canReceive + 容量>0` 判据放行，必须单列黑名单。实现按**命名规约**识别
`refinedstorage` 命名空间下路径 `controller` / `*_controller`（16 色 × 普通/创造 = 32 个 id 全部命中，
别的模组的 `foo:controller` 不误伤）。**黑名单只影响候选展示，不影响真充电**
（控制器仍可被充 —— 若以后要「连充都不充」，那是另一笔产品决策）。

### 3.4 R-1（由 124-D 审计发现、124-C 修）

立方能力视图是 **int 口径**，43×50M = 2.15B > `INT_MAX` ⇒ 存量/容量同时饱和到 2147483647，
于是充电器的旧满电判据 `getEnergyStored() >= getMaxEnergyStored()` 在饱和区间**恒真**：
真实还剩 2,516,352 FE 却被判满、永远充不进去。
修法：判满改成 `storage.receiveEnergy(transfer, true) <= 0`（simulate 不写世界，立方视图内部空间是
long 精确值）；顺序仍是「`transferBudget()`（纯函数）→ 判满 → 真取电 → 扣电 → 高亮」，
判满失败**不扣电、不登记高亮**。副产物语义微差：`transfer <= 0` 早退排在判满之前 ⇒ 自身没电时
满电目标不再累计 `lastSkippedFullTargets`（只影响界面读数，不动守恒）。

---

## 4. 能量立方「集群」语义（124-D）—— 审计结论：**已正确，源码 0 改动**

| # | 不变量 | 结论 | 证据 |
| --- | --- | --- | --- |
| ① | 输入按集群结算 | PASS | `ClusterEnergyCapability#receiveEnergy`（long 空间）→ `receiveIntoCluster` → `Snapshot#receive`：**先填接收方自己，再按坐标字典序填其它有空位的成员** |
| ② | 输出按集群、不双计 | PASS | `Snapshot#extract` 字典序扣、只累计**实际扣掉量**；网络节点只报本块（网络组件逐块求和，不平方级重复计数） |
| ③ | 充电器覆盖任一块即整簇 | PASS | `chargeBlock` 取 `Capabilities.EnergyStorage.BLOCK` → 立方能力对**全方向 + null** 返回集群视图；满电判定先于 `transferBudget()` ⇒ 集群视图下 = 整簇满才跳过 |
| ④ | 挖下只保留自身 | PASS | `FEEnergyCubeBlock#getDrops` 用 `getOwnEnergyStored()` + `saveToItem(...)`，**全文无 `getClusterEnergyStored`**；`loadAdditional` 用 `restoreEnergy`（设定值写本块，期间无移交） |
| ⑤ | 不复制 / 不丢失 | PASS | 快照**只存坐标**（不缓存 BE 引用）；`cubeAt` 走 `isLoaded`；取不到的成员**两边都不计**；`MAX_MEMBERS = 64`；`invalidateAround` 挂放置/邻块/破坏/`setRemoved`/`loadAdditional`；写路径 `isClientSide` 早退 |
| ⑥ | 显示 = 整簇 | PASS | 数据槽 2..5 = 集群存/容 lo-hi → 菜单 join → 界面电量条；客户端退回本块 |

**用户三条抱怨的归因（不是 bug，是观感 / 速率）**：

1. 「只充靠近的那一个」= **观感**。模型实证：只覆盖 1 块时**未覆盖的块也在涨**；但 `preferred-first`
   意味着前 ~10,000 tick 主要填被覆盖那 1 块 —— 这正是「一池电池」的正确实现，不建议改均摊（会引入新守恒边界）。
2. 「应该瞬间充满」= **纯速率**，与集群无关。默认 `rangeChargerChargeRate = 500 FE/t/目标`，
   12 块墙全被覆盖 ≈ 6000 FE/t，20M 缺口 ≈ 2.8 分钟；只盖 1 块 ≈ 33 分钟。
   四层上限：`rangeChargerChargeRate` → 缓存 `rangeChargerEnergyCapacity`(50M) →
   `rangeChargerNetworkPullPerTick`(100k) → `feEnergyCubeMaxTransfer`(100k)。速度升级每级 ×2、
   堆叠升级 = ×8（`RsccRateLimiter`），实际持续总吞吐上限 = 网络抽取 100k/t。
   **本轮按纪律未改任何默认值/倍率** —— 要不要动是产品决策。
3. 「挖下才分自己那一块」= **已正确**。守恒实证：580M →（挖走 45M）535M/550M 容量 →（放回）580M/600M，无复制无丢失。

**已知上限（非 bug）**：R-2 >64 块被 `MAX_MEMBERS` 截断（100 块的墙只算 64 块、容量 3.2B），
这是防止超大结构拖慢重算的刻意上限。

---

## 5. 因本轮改动需要更新的旧自检锚点（透明登记）

| 脚本 | 谁改的 | 改了什么 | 原意是否放宽 |
| --- | --- | --- | --- |
| `selfcheck_round110_charger_block_filter.py` | 124-C | `recordSeenBlock` 锚点 → `recordSeenCandidate`；取值锚点 → `receiveEnergy(transfer, false)`；顺序判据改成 record→gate→budget(纯)→满电→真收→高亮 | 否 |
| `selfcheck_round102_charger_highlight.py` | 124-C | 方块档的「判满 / 跳过计数」拆成「早于真取电且不扣电」；③-6 的 O(1) 判据允许 simulate、禁止 chargeBlock 内遍历 | 否（判据更严） |
| `selfcheck_round110_area_selector_name.py` | 124-B | 反例「切行不 flush」的锚点补上新增的 `selection = currentSelection();` | 否 |
| `selfcheck_round113_area_mark_e2e.py` | 124-B | ⑦ 的 `carriedMarks` 锚点改到 `previous`；⑦-2 抽出 `replace_carries_marks_and_name()` 并**新增**「REPLACE 不得抹名字」半个判据；反例⑦-5 改成重跑该判据，**新增**反例⑦-6（退回裸 `area` 必须判负） | 否（**更严**，141 项 / 39 反例） |
| `selfcheck_round124_energy_cluster.py` | 124-D | R-1 落地后重写 C-2（simulate 判满唯一 + 跳过计数早于真取电 + 「判满失败」段不含扣电/高亮 + 扣电只在真取电之后），新增 C-5/C-6/C-7、反例 X-6a/6b/6c/X-17/18/19 | 否（判据更严） |

全仓 grep 确认**没有其它脚本**还锚着旧写法（`AreaMarks.get(target, selection.areas()` /
`selection.replace(index, area)` / `AreaMarkScreen` 的 `PANEL_H`/`BUTTON_Y`）。

---

## 6. 仍未实机验证（诚实清单）

1. **没有真机开过这三个界面**：区域标记（文字避让 / 缩高后观感 / 24 字最坏宽）、区域选定器（改名四入口、
   焦点转移、拒绝回执可见性）、充电过滤器（候选池是否真的不再出现发电机 / 控制器、自家充电器是否出现）。
   只有离屏 mock、像素/矩形断言、以及源码级行为模型。
2. **焦点语义是推的**：`EditBox` 在「点行 / 点确定按钮」时的实际焦点转移按 1.21
   `AbstractContainerEventHandler.mouseClicked → setFocused(clicked child)` 推演；若实机点行本来就会 unfocus，
   2-2 的「名称框显示旧值」那层会更轻，但**索引归属修正仍然必要**。
3. **主手路径的拒绝回执走 chat**：界面开着时被 Screen 盖住，关界面后才可见（菜单路径的拒绝序号通道不受影响）
   —— 实机要确认玩家能否感知。
4. **多人场景**：两个玩家同时开同一台机器的区域列表时，「捕获索引」的新语义未测。
5. **跨存档往返**：名字保存↔读档只有 codec / 注册链的静态证据，没有真跑一次存读。
6. **集群语义的实机验证**：580M 的墙 + 充电器只盖 1 块时整簇读数缓慢上涨、挖下→放回总量不翻倍
   （普通破坏 + 区块重载两条路径）、43+ 块的墙（7×7=49）接近满时是否还会提前停充（R-1 现场复现）。
7. **速率口径没动**：默认 500 FE/t/目标下面板「瞬间充满」不可能实现，需产品决策（见 §4-2）。

---

## 7. 下一轮待办（承接 `ROUND122_USER_FEEDBACK.md` 第 8 节）

1. **1-3 / 1-4**（把标记能力挂回 RS 原过滤器界面 + 接 JEI）
2. **1-2**（区域数量——用户要「能标记很多区域」，不是多一屏十几个）
3. **3-4**（多颜色总线「合并显示 + 展开」折叠分组）

> 这三条是**界面级重构**，动手前必须先跟用户确认形态（用户第 123 轮已明确要求「先拍板再动手」）。
