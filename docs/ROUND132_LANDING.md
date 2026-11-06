# 第 132 轮 落地说明 —— 发送器界面四项小修 + 数量改用 RS 原生子界面 + 该界面隐藏 JEI + 接收器改造成 RS 网格界面

> 配套契约：`docs/ROUND132_CONTRACT.md`。本文是**实现完成后的事实记录**：改了什么、为什么这么改、验证证据、还剩什么。
> 版本：`1.2.0-beta.33`（`gradle.properties:48`）。
> jar：`build/libs/rs_create_compat-1.2.0-beta.33.jar`，2,951,568 B，sha256 `05f6f25db377a99b77cb9120099ad12c82d2caf10ec9d0df5379c6ff537cb675`。

---

## 0. 用户原话 → 本轮结果

用户 2026-11-06 晚 6 张截图 + 语音反馈（消息 m00008，截图为「高级远程多功能终端·发送模式」与「网络资源接收器」）。

| # | 用户原话（要点） | 本轮处理 |
| - | --- | --- |
| 1 | 「最左边那个地方……这一个选择按钮，你没发现它已经重叠了吗」 | **已修**。根因：RS 网格界面自带的侧键列占 `x ∈ [leftPos-20, leftPos-2]`（`AbstractSideButtonWidget.SIZE=18`，`AbstractBaseScreen.getSideButtonX()`），而第 131 轮把 8 个模式页签放在 `guiLeft-26`（宽 24），正好压在同一列。发送模式页签改为 `leftPos - TAB_W - SIZE - 4 = leftPos-46`，页签右缘 `leftPos-22`，与侧键左缘 `leftPos-20` 留 2px；其它模式分支一字节未动。 |
| 2 | 「我点加减号，输入框数字是不变的，但实际上能正常增加数量」 | **已修（控件整体删除）**。旧实现 `stepAmount()` 只发 `RequesterOrderPacket(ACTION_SET_AMOUNT)`，从不回写 `amountBox`，所以要等下一次服务端同步才刷新。本轮把手写数量框 + −/+ 一起删掉，改走第 3 条的数量子界面，问题从根上消失。 |
| 3 | 「想用 RS 原版那种数量选择器，点一下弹个子界面，在子界面里处理数量」 | **已做**。新增 `client/grid/OrderAmountScreen.java`：直接继承 RS `AbstractAmountScreen`，复用 `LongAmountOperations.INSTANCE` 与 RS 自己的 `resource_amount.png`（180×99），±1/±10/±64、确定/重置/取消、回车确定、Esc 取消全部是 RS 原生行为。确认后发 `ACTION_SET_AMOUNT` 并自动返回发送器界面。 |
| 4 | 「订单界面字的颜色有点诡异」 | **已改**。面板是深色底，旧常量用的是「浅底深字」（`0xFF333333/0xFF404040`），在深底上几乎看不清；改为浅色：标题 `0xFFFFE08A`、正文 `0xFFE8E8E8`、提示 `0xFFAFC8E8`、警告 `0xFFFF8A8A`、成功 `0xFF8FE08F`、流体 `0xFF7FC8FF`、删除符 `0xFFFF9A9A`。 |
| 5 | 「界面很长，和 JEI 重叠；这界面不需要 JEI 却显示了」 | **已做（隐藏 JEI）**。新增 `client/ResourcePickerJeiPlugin.java`，给 `ResourcePickerScreen` 注册 `IGuiContainerHandler`，`getGuiExtraAreas` 返回整屏矩形 ⇒ JEI 的 `IngredientGrid.hasRoom()` 为 false，列表自动收起；其它界面 JEI 行为完全不变。界面「很长」本身是 RS 网格界面的自动拉伸（`AbstractStretchingScreen`：`imageHeight = 19 + 18*visibleRows + 99`，随窗口高度变化），不是本模组多画了高度；JEI 收走后不再有东西压在右侧订单面板上。 |
| 6 | 「订单界面既然不能放物品，就不该有物品栏；实在麻烦就算了」 | **本轮不做**，原因写清：玩家背包 36 格是 RS `AbstractGridContainerMenu.resized()` 内部固定添加的，去掉需要改菜单父类或凭空删槽位，会牵动同步与 `quickMoveStack`，风险远大于收益。保留原版背包区；界面的格子点击与快捷移动已保证「放不进网络」（`GridOperations.insert` 恒 false）。 |
| 7 | 「接收器你完全理解错我意思，为什么自己写一套狗屎？像图一那样重写，再加点东西」 | **已重写**。`NetworkResourceReceiverScreen` 从手绘 `AbstractContainerScreen`（自绘贴图 + 9×7 手画格 + 手画数量角标 + 自带滚动条 + 左侧「插件」竖栏）改为 `extends GridScreen<NetworkResourceReceiverMenu>`；菜单 `extends AbstractGridContainerMenu`；内部 `ReceiverGrid implements Grid` 把方块实体缓存当成一张只读网络视图。搜索框、视图切换、排序、滚轮、数量角标、tooltip 全部交回 RS 原生实现。 |

---

## 1. 发送器（`ResourcePickerScreen`）改动明细

文件：`src/main/java/cretae/cookiewyq/rs_create_compat/client/grid/ResourcePickerScreen.java`

- **页签**：`client/TerminalModeTabOverlay.java` 仅发送模式分支改为
  `Math.max(SCREEN_MARGIN, containerScreen.getGuiLeft() - TAB_W - AbstractSideButtonWidget.SIZE - 4)`。
- **删除的手写数量控件**：`amountBox / minusButton / plusButton / buildAmountControls() / applyAmount() / stepAmount() / parseAmount() / currentAmount() / amountLoadedKey / parsedAmount / AMOUNT_ROW_H / amountY() / renderAmountTooltip() / 回车应用数量分支`。`layoutPanel()` 与 `panelHeight()` 同步回收那 18px，订单面板可视行数 +1。
- **新的交互**：
  - 左键点订单行 = 选中（仅高亮）；
  - 左键点订单行里的**数量文字** = 打开数量子界面（悬停时数量文字画 1px 下划线提示可点）；
  - 行尾 `×` 或右键 = 删除该条目；
  - 左键点网格资源 = 加入订单（默认 **1 件**；旧行为跟随数量框）；
  - 右键点网格资源 = 从订单移除。
- **颜色**：见上表；行选中底色、网格部件高亮（`COLOR_SELECTED_FILL/COLOR_SELECTED_BORDER`）未动。
- 新增 `client/grid/OrderAmountScreen.java` 与 `client/ResourcePickerJeiPlugin.java`。

## 2. 接收器改动明细

文件：`client/screen/NetworkResourceReceiverScreen.java`、`menu/NetworkResourceReceiverMenu.java`、`network/SyncReceiverConfigPacket.java`、`block/entity/NetworkResourceReceiverBlockEntity.java`。

- **屏幕**：`extends GridScreen<NetworkResourceReceiverMenu>`，`ResourcePickerScreen` 同款「右侧深色 extras 面板」：名称 `EditBox`（宽 70）+ 确定按钮（发 `SetReceiverNamePacket`，回车等同确定）、6 个升级槽（速度/堆叠，仍由 `UpgradeSlot` 管过滤与 tooltip）、状态/缓存/流体/能量/手势/「插件」文字。**不覆盖** `mouseClicked / mouseScrolled`：取物与滚动交给 RS 网格。
- **菜单**：`extends AbstractGridContainerMenu`。两个构造器（客户端 `(id, inventory)` 用 `EMPTY_GRID_DATA`；服务端 `(id, inventory, receiver, ReceiverGrid)`）。
  - `resized()` 里先 `super.resized(...)`（RS 内部 `resetSlots()` + 加 36 格玩家背包 ⇒ 槽 [0,36)），再追加 6 个 `UpgradeSlot`（槽 [36,42)）；两端都走这条路径，槽位账一致。
  - `ReceiverGrid implements Grid`：`GridNetworkNode(0L)` + watcher；`getResources` 聚合 `cacheEntries()`（物品在前、流体在后，插入序）；`isGridActive = 节点 active && isProperlyConfigured()`；`getAutocraftableResources = Set.of()`（接收器不自动合成）；`createOperations` 返回 `CacheGridOperations`。
  - `CacheGridOperations.extract(...)`：按 `GridExtractMode` 算量（ENTIRE=全部 / HALF=一半 / SINGLE=单批）→ 缓存**模拟扣** → 目标 `insert(SIMULATE)` → 真扣 accepted → 目标 `insert(EXECUTE)` → 目标只收了一部分就把 remainder 回填缓存（守恒）。`insert` 恒 `false`：界面上放不进任何东西。
  - 缓存变化：服务端 `broadcastChanges()` 比对 `snapshotAmounts()`，按 delta 走 `onResourceUpdate(...)`；`cacheVersion` 由方块实体 `setChanged()` 自增。客户端从 `SyncReceiverConfigPacket` 拿 `GridData`（`GridData.STREAM_CODEC`）→ `applyClientGridSnapshot()`（`onClear()` + 逐条 `onResourceUpdate` + `onActiveChanged`）。
- **点击语义（RS 原生，不是我们写的）**：左键 = `ENTIRE_RESOURCE` 取全部到手里；右键 = `HALF_RESOURCE` 取一半；按住 Shift 时取到背包（`shouldExtractToCursor = !Screen.hasShiftDown()`）。
- **清理**：旧自绘贴图 `textures/gui/network_resource_receiver.png` 已无 Java 引用（`gui_slot_registry.py` 的登记也移除了）；旧的 `ReceiverCacheActionPacket.takeItem/takeFluid/page` 服务端入口保留为**空壳**（注释写明「即使客户端伪造旧包也取不走一个物品」），只为旧包注册链路继续编译。

## 3. 验证证据

| 项目 | 结果 |
| --- | --- |
| `python tools/_run_all_selfchecks.py`（用**自带 runtime Python**，见下方注意） | `selfcheck 总数 200，失败 0` |
| `powershell -File tools/_lead_compile.ps1` | `LEAD_COMPILE_OK sources=526` |
| `gradlew build --offline` | `BUILD SUCCESSFUL in 46s` |
| jar 三方部署（`deploy_rscc.py --apply` + `deploy_rscc_local.py --apply`） | `/mods`、`/client_files/mods`、本地实例三处同 sha256 |
| `python tools/_verify_deploy_round132.py` | `DEPLOY_VERIFY_OK`（build/libs + 本地 + 两处 SFTP 四处一致） |
| `tools/verify_dedicated_server.ps1` 专服启动 | `PASS`，日志 `Done (1.580s)!`，无 Mixin/dist 致命标记，`run/` 前后只读自证 OK |

> **注意（环境坑）**：`python`（系统 Python 3.12）没有 `numpy`/`Pillow`，直接跑全量自检会有 2 个脚本因 `ModuleNotFoundError` 失败（`selfcheck_gui_slot_alignment.py`、`selfcheck_advancements.py`），那是**解释器缺包，不是代码红**。跑法：
> `C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe tools\_run_all_selfchecks.py`。
> 反过来，SFTP 部署脚本要用**系统 python**（自带 `paramiko`），自带 runtime python 没有。

## 4. 自检口径变化（谁接管了谁）

- 新增 `tools/selfcheck_round132_sender.py`（6 项）、`tools/selfcheck_round132_receiver.py`（46 项，含 4 条反例自证）。
- `round101_receiver_chute` 的 B4 改写为「resized 里 super 之后加 6 槽 + 两端各走一次 `resized(0,0,0)` + 请求器菜单不得有 UpgradeSlot」，mutation 同步换掉（真断言，不是放过）。
- `round122_quickmove_bounds` 增加 `MANUAL_SLOT_TOTALS["NetworkResourceReceiverMenu"] = 42`（[0,36) 玩家 + [36,42) 升级）。
- `round95_receiver_requester_ui` / `round96_receiver` 里描述旧手绘界面的判据标记为 `SUPERSEDED_BY_ROUND132`（条件：屏幕已 `extends GridScreen`），由 round132_receiver 接管；这是「设计被替换后旧断言退役」，不是把测试改绿。
- `round131_receiver` 整份换成新结构判据（41 项）；`round131_terminal_send` 的页签公式断言更新；`round86`、`round96_requester_ui` 里手写数量框相关断言改为数量子界面断言。
- lang：`en_us/zh_cn` 各新增 `gui.rs_create_compat.resource_picker.order.amount`（Order amount / 订单数量），`...order.amount.hint` 文案改为「点条目上的数量打开数量子界面」；`audit_lang_keys.py` 0 缺键 0 孤儿。

## 5. 本轮已知限制 / 下一轮候选

1. **反馈 6（去掉物品栏）未做**，原因见 §0.6。
2. 订单面板「字色」是按深色底设计的浅色字；如果以后面板背景改浅色，这套常量要一起翻。
3. 接收器的旧包 `ReceiverCacheActionPacket` / `SyncReceiverConfigPacket` 里仍带着旧字段（`page/pageCount/items/fluids` 之类），本轮只保证「不写任何东西」，**没有清理协议面**；下一轮可以整体瘦身。
4. `textures/gui/network_resource_receiver.png`（1181 B）与生成脚本 `tools/gen_round128_gui_bg.py` 里的对应项现在是死资产，可删（本轮只从登记表移除、没删文件）。
5. 发送器界面隐藏 JEI 是「整屏不显示」的粗粒度做法；如果以后想支持「从 JEI 搜物品直接加进订单」，需要把排除区改成只排除右侧面板，那属于功能扩展。
6. 「点数量文字才开子界面」是刻意保留的：整行左键仍是选中，避免每次点行都弹窗。

## 6. 操作变更速查（给玩家）

- **发送器**：点网格物品 → 订单 +1 件；点订单里的数量数字 → 弹 RS 数量子界面（±1/±10/±64，确定/重置/取消）；右键或 `×` → 删除条目；左上工具条 → 选目标接收器 / 发送 / 清空订单。
- **接收器**：直接点网格里的资源即可取（左键全部、右键一半、Shift 取进背包）；右侧填名字 + 确定改名；六个槽放速度/堆叠升级。
