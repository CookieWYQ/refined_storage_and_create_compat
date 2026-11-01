# 第 131 轮 · 落地说明：缓存仓收敛 / 请求器去设置页与插件 / 接收器改成「取物终端」/ 终端加发送模式 / 自动合成询问 / 选定器复制

> 规格与分工见 `docs/ROUND131_CONTRACT.md`；本文是**实现与验收记录**：改了什么、为什么这么改、
> 哪些判据钉住了它、还剩什么只能靠实机确认。

## 0. 用户原话 → 本轮动作（逐条对账）

| # | 用户原话（2026-11-01 下午，6 张截图） | 动作 |
| - | --- | --- |
| 1 | 「前三张图里框起的部分全部删除」 | ① 状态带 hover tooltip（图 1 那团花字 = 过滤图标 tooltip + 状态带 tooltip 两次 `renderTooltip` 叠在同一点）整条删除；② 匹配区空态提示「先在右边点一个区域，再来配它的过滤」删除；③ 区域条每行的过滤物品图标（图 3）删除 |
| 2 | 「第三张图……物品图标不需要显示，因为已经可以直接打开编辑了」 | `AreaStripPanel` 不再画 `drawFilterIcons`、不再为图标预留宽度；行文字可用宽按删除后重算（坐标行右对齐改 `textX + fullW - highW`） |
| 3 | 「第四张图那一行提示文本非常丑，而且也是非常废话」 | 状态带只在**真问题**时出字（无网络 / 未激活 / 缓存满 / 无选定器 / 区域空 / 维度不符，由基类 `isStatusVisible` 白名单），正常时 0 像素；「区域 N/M · 只吸 K 种」摘要行删除 |
| 4 | 「打开的时候记住上一次选择的那个条目，然后自动切换」 | `AdvancedCollectionCacheBlockEntity` 新增持久字段 NBT `LastEditingArea`；`AdvancedCollectionCacheMenu` 构造时读回并 clamp；`setEditingArea`（`SetAreaFilterContextPacket` 生效处）是唯一落盘点（写在「值没变就 return」之前） |
| 5 | 「第五张图这个界面可以直接删除了，这设置界面不能提供更多用途」 | `NetworkResourceRequesterScreen` **整页删除**（含 `ClientInit` 屏幕注册、网格里的「设置」按钮、`SyncRequesterConfigPacket` 收件人只剩信箱）；请求器右键直接进网格（第 96 轮既有） |
| 6 | 「发送器功能保留但不需要插件槽：红石激活的速度决定发送速度」 | 请求器 6 个升级槽整条删除：菜单槽位 / `ContainerData` / NBT `Upgrades` / 方块掉落升级结算 / `RsccSpeedStackUpgradeContainer` 引用全删；节拍固定 `FIXED_BATCHES_PER_TICK = 8`、`FIXED_BATCH_SIZE = 64`（红石脉冲多快就发多快） |
| 7 | 「接收器的选择界面必须点开一个可搜索的子界面，不是一个按钮点来点去」 | 网格工具栏「目标」按钮 → 新 `SenderTargetSelectScreen`（`ChildConfigScreen` + `EditBox` 搜索 + `ScrollSelectWidget`，与 `StepMachineSelectScreen` 同源）；「左键切下一台 / Shift 上一台」整条路径删除，`toolbar.target.hint` 文案改成「点开接收器列表，搜索并选用一台」 |
| 8 | 「给我们的终端也加一个网络资源发送器功能」 | `AdvancedRemoteTerminalItem`：`MODE_SEND = 7`、`MODE_COUNT = 8`；新 `TerminalSenderBackend`（订单/目标存终端物品 `CustomData`：`RsccSenderOrders` / `RsccSenderTarget`，网络取终端绑定的 RS 网络）；打开的是**与请求器同一个**发送界面（`ResourcePickerMenu.openFor`），Tab 一排 8 颗、发送界面的 Tab 挪到 GUI 左侧（右侧 193 网格 + 118 订单清单会被压住） |
| 9 | 「第六张图这个接收器显示得很莫名其妙：废话多、取全部没意义、输入框还可以、要像终端一样能滚、相当于取东西的地方」 | `NetworkResourceReceiverScreen` 重写成终端样式：顶部只留「名字输入 + 确定」，中间 9×7 = 63 格可滚动网格（图标 + 数量角标），下面是玩家背包；删掉 6 行状态句 / 页码箭头 / 「取全部」按钮；滚轮 + 滚动条按行滚动；点击格子取出（左键一组 / Shift 全部） |
| 10 | 「流体怎么取：先看网络里有没有能装它的物品（空桶），没有再背包，都没有就取不出；顺序按 RS 的来」 | `NetworkResourceReceiverMenu#takeFluid` 按 RS 2.0.9 `FluidGridExtractionStrategy` 真实顺序：**光标容器 → 玩家背包空桶 → 网络空桶 → 都没有**（`:55` / `:57` / `:59` / `:62`；`:57` 在 `:59` 前 = 背包优先、网络兜底）；网络桶先 `Action.SIMULATE` 再 EXECUTE、每条真扣路径 2 条回滚；都没有时**一个 mB 都不扣** |
| 11 | 「发送量超过网络存量且可自动合成时，先问用户要不要自动合成，弹出那个界面；数量表达（拍板 A）」 | 发送时逐条算 have；缺口且可合成 ⇒ **先不发**，S2C 询问（5 个数由服务端算好：目标 / 现有 / 最多可合成 / 还缺 / 合成后能发多少）；客户端 `SenderCraftPromptScreen` 按用户拍板文案「目标 100 · 现有 50 · 最多可合成 25 · 还缺 25」+ 两按钮「合成 25 并发送 75」/「只发送 50」；「最多可合成」走 RS `PreviewProvider#getMaxAmount`（300ms 有界等待，超时按不可合成退回旧路径，异常全 catch 并清中断位）；确认后投现有部分 + `ensureTask` 启动合成 + `PendingSenderSends` 补投（凑齐即投，10 分钟超时放弃并提示） |
| 12 | 「区域选定器可以复制（NBT 内容复制）：一个标记好的 + 一个空白合成 ⇒ 两个相同标记；两个都带 NBT ⇒ 合成不出东西」 | 新 `AreaSelectorCopyRecipe`（`CustomRecipe`，无形状，恰好 2 个选定器且恰好 1 个带区域数据 ⇒ `copyWithCount(2)`；两个都带 / 都空 / 混入别的物品 / 第 3 个 ⇒ 无产物）；JSON `data/rs_create_compat/recipe/area_selector_copy.json`；**不覆写 `getType()`**（留在 `RecipeType.CRAFTING` 桶，否则工作台与玩家 2×2 都找不到它）；序列化器注册 `RS_Create_Compat.AREA_SELECTOR_COPY_SERIALIZER` |

## 1. 文件与改动（按组）

| 组 | 文件 | 改动 |
| - | --- | --- |
| A | `client/screen/AdvancedCollectionCacheScreen.java` | 删状态带 tooltip 覆写 / 图标 tooltip / `frameIcons` 系列 / `drawStatusLine` 覆写 / `drawMatchAreaHint` 覆写 / `editAreaSummary` / 状态带右端图标命中区；`drawFilterIcons` 与 `filterIconTooltipLines` 保留（旧「区域列表」界面 `AreaSelectorScreen` / `AreaMarkScreen` 仍用），只断开本屏调用 |
| A | `client/screen/AreaStripPanel.java` | 行内过滤图标整条删除（调用 / `ROW_ICON_*` / `visibleItemFilters` / `iconW` 预留）；坐标行三档绘制按新可用宽重算 |
| A | `block/entity/AdvancedCollectionCacheBlockEntity.java` + `menu/AdvancedCollectionCacheMenu.java` | `LastEditingArea` 持久字段 + 读写 + clamp + 唯一落盘点 |
| B | `client/screen/NetworkResourceReceiverScreen.java`（重写 842→约 640 行） | 顶行名字输入 + 9×7 网格 + 滚动条 + 玩家背包；`amountText(entry, exact)` 唯一数量工厂（角标紧凑 / tooltip 千分位同一口径）；动作结果改走 actionbar（界面零状态句） |
| B | `menu/NetworkResourceReceiverMenu.java`（重写） | 分页 → 按行滚动窗口（`firstRow` + 一屏 63 格）；流体按 RS 顺序 + SIMULATE 先行 + 2 条回滚；删 `BUTTON_TAKE_ALL_ITEMS` / `takeAllCacheItems` / `clickMenuButton`；升级槽与名字权威链路不变 |
| B | `network/ReceiverCacheActionPacket.java` | 只改注释（线格式 6 分量 / 常量不变，`ACTION_PAGE` 语义 = 滚动到第 N 行） |
| B | `textures/gui/network_resource_receiver.png` | `tools/make_gui_bg.py` 重新生成：236×252，105 个烘焙槽框（9×7 网格 + 6 升级 + 36 背包/快捷栏），Menu 坐标 − 1 |
| C | `client/screen/NetworkResourceRequesterScreen.java` | **删除** |
| C | `menu/NetworkResourceRequesterMenu.java` + `block/entity/NetworkResourceRequesterBlockEntity.java` + `block/NetworkResourceRequesterBlock.java` | 删 6 升级槽（槽位 / ContainerData / NBT / 掉落结算）；节拍固定；方块类只删 `collectHandler(getUpgradeContainer(), out)` 一行（删槽的机械后果，已报备） |
| C | `client/grid/ResourcePickerMenu.java` / `ResourcePickerScreen.java` | 只认 `SenderBackend`；`openFor(player, requesterBE)` 旧签名保留 + 新 `openFor(player, backend, title)`；删「设置」按钮；目标按钮改为打开子界面；节拍提示改「固定节拍」 |
| C | `client/screen/SenderTargetSelectScreen.java`（新） | 可搜索的接收器选择子界面 |
| C | `support/SenderBackend.java`（新）+ `support/RequesterSenderBackend.java`（新）+ `support/SenderDelivery.java`（新） | 发送源抽象 + 请求器实现 + 唯一投递引擎（取→夹容量→写→回滚→暂存守恒账） |
| D | `item/AdvancedRemoteTerminalItem.java` + `client/TerminalModeTabOverlay.java` + `support/TerminalSenderBackend.java`（新 721 行） | 第 8 个「发送」模式；Tab 8 颗、发送界面 Tab 挪左侧；终端后端（订单/目标存 CustomData、七道闸门、`SenderDelivery`） |
| E | `item/AreaSelectorCopyRecipe.java`（新）+ `data/rs_create_compat/recipe/area_selector_copy.json`（新）+ `RS_Create_Compat.java` | 复制配方 + 序列化器注册 |
| F | `support/SenderShortage.java`（新）+ `support/PendingSenderSends.java`（新）+ `client/screen/SenderCraftPromptScreen.java`（新）+ `network/SenderShortagePromptPacket.java`（新 S2C）+ `network/SenderShortageReplyPacket.java`（新 C2S）+ `RS_Create_Compat.java` | 缺口询问 + 合成 + 补投（两个新包由 Lead 注册） |
| — | `lang/{zh_cn,en_us}.json` | 新增 32 键（sender_target_select 11 / sender_craft_prompt 6 / terminal_send 10 / rate.hint 1 / shortage 反馈 4）；改 1 键（`toolbar.target.hint`）；删 11 键（`resource_picker.toolbar.settings` / `.hint` + 9 条随界面删除的死数据） |
| — | `client/ClientInit.java` / `client/ClientPayloadSink.java` | 删请求器屏幕注册；`syncNetworkResourceRequester` 方法体留空（签名保留，Sink 接口不动） |

## 2. 修掉的**真 bug**（不是 UI 调整）

1. **状态带 tooltip 双层叠字**：`renderStatusBandTooltip` 先画过滤图标 tooltip，再继续画状态带 tooltip —— 两次 `renderTooltip` 落在同一坐标，玩家看到的就是图 1 那团「区域场 · 5 格 / 图标 …」花字。现在这个覆写整条删除。
2. **`RequesterSenderBackend.setTargetName` ↔ `applyTarget` 无限递归（P0）**：`setTargetName` 调 `applyTarget`、`applyTarget` 在写入前又调 `setTargetName`，且值还是旧的 ⇒ 每次「确定目标」都 `StackOverflowError` 打崩服务端。改成单向：`setTargetName` 直接写，`applyTarget` 唯一「校验 → 写 → 回快照」；自检 C10 组 6 条 + 反例钉死（旧写法拼回判负）。
3. **`OpenRequesterPickerPacket` 死通道**：唯一发送方（设置界面的「打开网格」按钮）随界面删除 ⇒ 包类 + 注册整段删除（round92 死通道审计回到基线）。
4. **`TerminalSenderBackend` 有一条未过闸的 debug 打印**：round122 E1 判负；改用工程唯一闸门 `RsccAssemblyDebug.isEnabled()`（`Config.devLogs` 只是配置镜像值，禁止当判据）。
5. **选定器复制配方的桶陷阱**（实现期规避）：`CustomRecipe` 若覆写 `getType()` 返回自定义 RecipeType，配方会掉出 `RecipeType.CRAFTING` 桶 ⇒ 工作台与 2×2 都找不到；本类不覆写，自检钉死。

## 3. 验收（全部可复跑）

| 项 | 结果 |
| --- | --- |
| 独立 javac | `LEAD_COMPILE_OK sources=524` |
| Gradle | `BUILD SUCCESSFUL in 7m 7s`，`modVersion=1.2.0-beta.32` |
| 全量自检 | **198 个脚本 / 0 失败**（`tools/_run_all_selfchecks.py`） |
| 本轮新自检 | 6 份：cache_gui 50 项 / selector_copy 24 项 / receiver 72 项 / sender 75 项 / shortage 42 项 / terminal_send 31 项；反例合计 **61 条**（含「空树必须判负」与「把旧写法拼回必须判负」） |
| 专服探针 | **PASS**：`Done (1.747s)! For help, type "help"`，无 Mixin / dist 致命标记，`run/` 前后清单一致（只读自证 OK） |
| jar | `rs_create_compat-1.2.0-beta.32.jar`，**2,945,672 B**，sha256 `b75f2d785709bc68a9342d4ac09edb29b660ccaa733c32d15c2dcd6f73915f14` |
| 部署 | 四处 sha256 全等 → `DEPLOY_VERIFY_OK`（`tools/_verify_deploy_round131.py`：build/libs / 本地实例 / SFTP `/mods` / SFTP `/client_files/mods`）；beta.31 就地 `.disabled` |


## 4. 需要实机确认（我只有编译 / 像素 / 自检证据）

1. **缓存仓**：关界面再开，匹配区应直接是上次那个区域（标题带「区域 N · 名字」）；状态带正常时**一个字都没有**；区域条行里不再有过滤物品图标，坐标行不缩字。
2. **接收器**：顶部只有名字输入 + 确定；9×7 网格可滚轮滚动；点物品取一组 / Shift 全部；点流体按「背包空桶 → 网络空桶」换桶，两边都没有时提示一句且不扣。
3. **请求器**：右键直接进网格；网格里没有「设置」按钮；点「目标」弹出可搜索的接收器列表；没有插件槽；红石脉冲多快发多快。
4. **终端**：右下角一排模式图标变成 8 颗；第 8 颗打开发送界面（订单清单 + 网格，与请求器同源）；订单/目标关界面再开还在（存在终端物品上）；终端不在背包 / 断网时界面仍能开，但点发送会给明确拒绝原因。
5. **自动合成询问**：发 100 个只有 50 个且可合成时，弹出「目标 100 · 现有 50 · 最多可合成 25 · 还缺 25」+ 两按钮；选「合成 25 并发送 75」后先投 50、合成完成后自动补投 25（请求器方块；终端暂不补投，见 §5）。
6. **选定器复制**：工作台任意位置 / 玩家 2×2 放一个已标记 + 一个空白 ⇒ 出两个内容相同的选定器；两个都带区域 ⇒ 不出东西。

## 5. 刻意没做 / 已知限制

- **终端暂不接「合成后补投」**：`PendingSenderSends` 的驱动挂在请求器方块的 `doWork` 上；终端没有 tick 源，所以终端弹窗选「合成 X 并发送 Y」时**现有部分立即投出、合成照常启动**，合成完成后那一段不会自动补投（再点一次发送即可）。不新增 ticker 是刻意的（避免为一个可选增强多一条常驻 tick 链）。
- **共享件的一处措辞**：`RequesterSenderBackend.triggerFor` 在调 `backend.trigger()` 前做了 `isConnected / targetName / resolveReceiversByName` 预检，终端「没绑定网络 / 未设目标」时点发送的第二句提示仍是请求器措辞（终端自己的正确原因在打开界面时已写进底部提示行）。彻底修法是「预检直接交给 backend.trigger()」，属共享件改动，留待下一轮。
- **基础版界面的基类 `CollectionCacheScreen.renderStatusBandTooltip` 未动**：它只在「真问题」时把同一句话再给一遍；要「本屏悬停绝对零 tooltip」需要给基类加空实现钩子，本轮不做（基础版画面必须逐字不变）。
- **请求器旧底图 `network_resource_requester.png` 成为零引用**：登记进 `gui_slot_registry` 的 ORPHAN（源码不许再引用），图保留不删（历史美术资产）。
- `NetworkResourceRequesterMenu` 保留为「瘦菜单壳」（玩家背包槽 + stillValid，永远打不开）：保它只是为了不动 `RS_Create_Compat` 的 MenuType 注册与方块层静态入口。

## 6. 越界改动报备（不在契约写作用域内，但是删除动作的机械后果）

| 文件 | 改动 | 原因 |
| - | --- | --- |
| `block/NetworkResourceRequesterBlock.java` | 只删 `BlockContentReleaser.collectHandler(requester.getUpgradeContainer(), out)` 一行 + 注释 | 升级槽删除后该调用必然编译失败；替代方案（给 BE 造空容器）等于「假装没删」 |
| `client/ClientInit.java` / `client/ClientPayloadSink.java` | 删请求器屏幕注册；Sink 方法体留空 | 请求器设置界面删除 |
| `tools/selfcheck_round68_remote_terminal_open.py` / `round100_energy_audit.py` | 「MODE_COUNT = 7」写死判据改为从 `MODE_COUNT` 派生 / `≥7` | 第 8 个模式是本轮合法变更，旧判据误报为回归 |
| `tools/gui_slot_registry.py` / `gen_round128_gui_bg.py` | 请求器槽位账删除 + 旧 PNG 登记 ORPHAN；接收器补 9×7 网格烘焙组 | 界面几何变更 |
| `RegisterUpgradeDestinations.java` | 删掉 `network_resource_requester` 的升级目的地登记 | 请求器已无升级槽；留着会让 RS 原生升级物品 tooltip 谎报「请求器可用」（误导性死数据） |
| `block/NetworkResourceRequesterBlock.java` | 删掉「没接网络 ⇒ 退回设置界面」分支里的 `player.openMenu(...)` 兜底 | 设置界面已删 ⇒ 这条兜底是死路径（`getMenuProvider` 注释也写明菜单永远不会被打开）；现在只给一句原因 |
| `lang/{zh_cn,en_us}.json` | 删 9 条随界面/槽位删除而变成死数据的键（6 条 `network_resource_requester.order.open_grid*` / `.order.summary.hint` + 3 条 `network_resource_receiver.upgrade.*`） | 全部核实 Java 侧 0 引用、tools 侧 0 引用；`network_resource_requester.order.summary` 因 1 处历史自检仍引用而保留 |
| `tools/selfcheck_round{69,95,96,101,106,126,130,...}.py` | 按新语义**反转**判据（旧「必须存在」→「必须不存在」）并补反例 | 被删除的界面元素；每个改过的脚本文件头都写了「第 131 轮：X 已删除 ⇒ 判据反转」 |

## 7. 环境备注

本轮 4 个并行队友的会话**无法写 `build/`**（Windows 文件权限，子会话沙箱），因此他们用「同一 classpath + TEMP 输出」的等价 javac 复算、并用逐脚本 + TEMP 汇总代替 `_run_all_selfchecks.py`。Lead 会话（本会话）对 `build/` 可写，所以最终的 `_lead_compile.ps1`、全量自检、Gradle 构建、专服探针、部署回读全部由 Lead 跑出正式凭据。
