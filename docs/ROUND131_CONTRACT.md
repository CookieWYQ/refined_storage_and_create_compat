# 第 131 轮 · 契约书：缓存仓收敛 / 请求器去设置页与插件 / 接收器改成「取物终端」/ 终端加发送模式 / 自动合成询问 / 选定器复制

> 本文件是**规格 + 分工**，不是实现记录。任何实现方要改这里的类名 / 常量 / 判据，必须先给 Lead 发消息。
> 落地说明与验收由 Lead 在 `docs/ROUND131_LANDING.md` 统一写。

## 0. 用户原话（第 130 轮验收后，2026-11-01 下午）

> 「前四张图。中的前三张图中框起的部分全部删除。
> 然后第三张图我详细说一下，就是这个**物品图标不需要显示**。因为已经可以直接打开编辑了。
> 然后呢，第四张图就是指的那**一行提示文本非常丑，而且也是非常废话**。
> 然后呢，就是说**打开的时候记住上一次选择的那个条目，然后自动切换**。
> 你看到第五张图，第五张图这个界面**可以直接删除了**，因为我发现它并没有任何的作用。这个设置界面其实并不能提供更多的什么用途。
> 然后呢，我再重新强调一遍。这一个**接收器的选择界面**它必须：点击之后会打开一个**子界面**，那个子界面可以选择**可以搜索**，并不是像现在这样子，就一个按钮在点来点去。
> 以及，给那个**我们的终端**再加一个功能，就是也支持这一个**网络资源发送器**的功能。
> 然后再看第六张图。这个网络资源接收器这个**显示的很莫名其妙**。首先他好多废话（「已命名」「没有」这些全是废话）。**「取全部」这个按钮也是废话**。**输入框还可以**。
> 我要求你这里做这个网络，这就相当于是一个**取东西的地方**……它应该是**保持和那个终端是一样的**，就是终端它不是可以**滚动**嘛，和终端一样可以滚动。
> 然后终端它那个流体怎么取的呢？取的时候**先检测网络之中有没有这种可以装这流体的物品**（取岩浆时看有没有空桶，有的话直接变成一个岩浆桶）；如果网络中没有的话，**再看玩家背包**里面。顺序**你按照 RS 的来**。
> 如果啥也没有的话，那就取不出来了嘛，就是无法通过玩家这种点击来取出，只能通过别的元件交互来取出。
> 再追加一点：**发送物品的时候，如果发送的数量超过网络中所包含的，并且这个东西又可以被自动合成，那么优先向用户询问是否自动合成**，并弹出那个自动合成的界面。
> 数量表达（用户拍板选 A）：「目标 100 · 现有 50 · 最多可合成 25 · 还缺 25」，两个按钮「合成 25 并发送 75」/「只发送 50」。
> 再加一个小功能：**区域选定器可以复制**（不是物品复制，是里面的 NBT 内容复制）：拿一个已经标记好的 + 一个空白的，在工作台或玩家背包里合成 ⇒ 得到**两个包含相同 NBT 的标记物品**；如果两个都带 NBT ⇒ **合成不出东西**。
> （补充拍板）**发送器功能保留，但不需要插件槽** —— 它跟插件槽没关系，**红石激活的速度决定发送速度**；速度多快就代表激活多快，所以根本不需要插件槽。」

## 1. 用户拍板记录（本轮 4 问，Lead 已问、用户已答）

| # | 问题 | 结论 |
| - | ---- | ---- |
| Q1 | 第五张图删除后请求器方块去留 | **留方块**；**删掉 6 个升级槽**（不再吃速度/叠加升级）；设置界面整页删除；右键直接进网格 |
| Q2 | 「我们的终端」是谁、发送加在哪 | **高级远程多功能终端**（`AdvancedRemoteTerminalItem`）；**新增第 8 个「发送」模式页**（`MODE_SEND = 7`，`MODE_COUNT = 8`） |
| Q3 | 超过库存且可合成时的数字表达 | 「目标 100 · 现有 50 · 最多可合成 25 · 还缺 25」+ 两按钮「合成 25 并发送 75」/「只发送 50」 |
| Q4 | 接收器界面形态 | 做成**终端网格的样子**：可滚动格子（物品/流体），点一下取出；顶部只留名字输入；删掉所有状态句与「取全部」 |

## 2. 交付分组与写作用域（冲突即错）

| 组 | 内容 | 归属 | 写作用域（只许改这些） |
| - | ---- | ---- | ---- |
| **A** | 缓存仓界面收敛（图 1-4）+ 记住上次编辑区域 | `cachegui` | `client/screen/AdvancedCollectionCacheScreen.java`、`client/screen/AreaStripPanel.java`、`menu/AdvancedCollectionCacheMenu.java`、`block/entity/AdvancedCollectionCacheBlockEntity.java`、`tools/selfcheck_round131_cache_gui.py` |
| **E** | 区域选定器 NBT 复制配方 | `cachegui` | 新建 `item/AreaSelectorCopyRecipe.java`、`data/rs_create_compat/recipe/area_selector_copy.json`、`tools/selfcheck_round131_selector_copy.py` |
| **B** | 接收器界面重做（终端网格 + 流体容器顺序） | `receiver` | `client/screen/NetworkResourceReceiverScreen.java`、`menu/NetworkResourceReceiverMenu.java`、`block/entity/NetworkResourceReceiverBlockEntity.java`、`network/ReceiverCacheActionPacket.java`、`textures/gui/network_resource_receiver.png`、`tools/selfcheck_round131_receiver.py` |
| **C** | 请求器：删设置界面 / 去插件槽 / 目标选择子界面 / 发送源后端 | `sender` | `client/screen/NetworkResourceRequesterScreen.java`（**删除**）、`menu/NetworkResourceRequesterMenu.java`、`block/entity/NetworkResourceRequesterBlockEntity.java`、`client/grid/ResourcePickerScreen.java`、`client/grid/ResourcePickerMenu.java`、新建 `client/screen/SenderTargetSelectScreen.java`、新建 `support/RequesterSenderBackend.java`、`support/SenderBackend.java`（联合维护）、`network/*Requester*.java`、`tools/selfcheck_round131_sender.py` |
| **F** | 超过库存 + 可自动合成 ⇒ 询问 + 合成 + 补投 | `sender` | 新建 `support/SenderShortage.java`、`support/PendingSenderSends.java`、`client/screen/SenderCraftPromptScreen.java`、`network/SenderShortage*.java`；`tools/selfcheck_round131_shortage.py` |
| **D** | 终端「发送」模式 | `terminal-send` | `item/AdvancedRemoteTerminalItem.java`、`client/TerminalModeTabOverlay.java`、新建 `support/TerminalSenderBackend.java`、`tools/selfcheck_round131_terminal_send.py` |
| — | `RS_Create_Compat.java`（菜单/包/配方注册）、`client/ClientInit.java`（界面注册）、`lang/zh_cn.json`、`lang/en_us.json`、`docs/*`、`gradle.properties`、git / 构建 / 部署 | **Lead 独占** | 别人一个字都不许改 |

**共享接口**：`support/SenderBackend.java` 骨架由 Lead 已提交（本轮开工前）。`sender` 可以扩方法但**必须同步通知 `terminal-send`**；`terminal-send` 只实现、不改接口。

## 3. 各组硬判据（验收看这些，不看「差不多」）

### A. 缓存仓（图 1-4）
1. **状态带 hover tooltip 整条删除**（图 1 那团花字是「过滤图标 tooltip」+「状态带 tooltip」两次 `renderTooltip` 叠在同一点导致的）：本屏不再有 `renderStatusBandTooltip` / `renderFilterIconTooltip`。
2. **删除匹配区空态提示**（图 2「先在右边点一个区域，再来配它的过滤」）：不再有 `drawMatchAreaHint` 覆写。
3. **删除区域条每行的过滤物品图标**（图 3）：`AreaStripPanel` 不再画 `drawFilterIcons`，行文字可用宽度按删除后重算（不许留空洞）。
4. **删除橙色摘要行**（图 4「区域 5/5 · 只吸 1 种」）：状态带**只**在「真问题」时出字（无选定器 / 选定器无区域 / 区域不可用 / 堵塞 / 投不出去），正常时**一个字都不画**；右端过滤图标一并删除。
5. **记住上次编辑区域**：关界面后重开，匹配区直接是上次那个区域的过滤（标题带「区域 N · 名字」），不需要再点一次。持久化在方块实体 NBT（键 `LastEditingArea`），越界回退 -1。
6. 基础版（`CollectionCacheScreen`）画面与行为**逐字不变**（若必须动基类，只能加钩子且 Lead 复核）。

### B. 接收器（图 6）
1. 布局 = **名字输入 + 确定** 一条，然后**可滚动网格**（物品/流体的格子，格内图标 + 数量），下面是玩家背包；**不再有**「已命名 / 缓存输出到朝向 / 节奏 / 缓存 / 流体 / 取全部 / 页码句」等任何状态句。
2. 滚动：滚轮 + 滚动条（终端手感），键盘/翻页键不要求。
3. 点击格子 = 取出（左键一组 / Shift 全部，保持既有服务端权威口径，客户端只报「取谁、要不要全部」）。
4. **流体取出顺序**（照 RS 2.0.9 的来，先调研 `local_src/rs_src` + javap，把证据写进完成消息）：
   - 先看**网络**里有没有能装该流体的容器（空桶等）→ 有：从网络取容器、装满、给玩家；
   - 网络没有 → 看**玩家背包**里的容器 → 有：装满；
   - 都没有 → 取不出，给一句提示，**一个 mB 都不扣**。
5. 背景必须用 `tools/make_gui_bg.py` 重新生成（第 128 轮铁律：槽框烘进 PNG，`renderBg` 一次 blit，不许 `fill` 画面板）。
6. 升级槽保留（接收器仍吃速度/叠加升级），槽位坐标与菜单同源。

### C. 请求器
1. `NetworkResourceRequesterScreen` **删除**（含菜单类型/界面注册/「设置」按钮/入口）；右键请求器 = 直接开网格（现状保持）。
2. **6 个升级槽整条删除**：菜单槽位、方块实体 `RsccSpeedStackUpgradeContainer`、NBT 键、相关 lang 全删；节拍固定（`batchesPerTick()` / `batchSize()` 返回固定值，语义 = 红石脉冲多快就发多快）。
3. **目标选择改成子界面**：网格工具栏的「目标」按钮点击 → 打开 `SenderTargetSelectScreen`（`ChildConfigScreen` + 搜索框 + `ScrollSelectWidget`，外观/搜索逻辑与 `StepMachineSelectScreen` 同源），列出本网络已命名接收器；确定 = 设置目标，取消 = 返回。`ResourcePickerScreen` 不再有「左键切下一台 / Shift 左键上一台」的轮换路径。
4. 发送源抽象：`ResourcePickerMenu` 只认 `SenderBackend`；请求器实现 `RequesterSenderBackend`（包住方块实体）。
5. 界面行为不回退：选资源/改数量/删除订单/清空/发送全部保留（RS 网格「只选不搬」三层保证不许破坏）。

### D. 终端发送模式
1. `MODE_SEND = 7`、`MODE_COUNT = 8`；`isModeSupported` / `openModeScreen` / `TerminalModeTabOverlay.iconFor` 全部覆盖到 8；Tab 一排 8 颗不重叠、不越界（像素判据见自检）。
2. 打开的是**同一个发送界面**（复用 `ResourcePickerScreen` + `ResourcePickerMenu`），后端 = `TerminalSenderBackend`；网络 = 终端物品解析出的 RS 网络（可复用 `bindNetworkSafely` 的取网络路径）。
3. 订单 / 目标存**终端物品 `CustomData`**（跟着物品走；键 `RsccSenderOrders` / `RsccSenderTarget`），开界面读回、改了就写回。
4. 终端失效（不在背包/饰品槽、没电、网络断开）⇒ 界面照开但发送被拒并说明（不静默）。

### E. 选定器复制
- 无形状配方：输入恰好 2 个 `rs_create_compat:area_selector`，其中**恰好一个**带区域数据（`AreaSelection` 非空或 `AreaMarks` 非空）⇒ 输出 **2 个**「内容与那个带区域的一模一样」的选定器。
- 两个都带区域、或两个都空 ⇒ **无输出**（不是「合出 2 个空的」）。
- 工作台与玩家 2×2 都生效；`assemble` 后背包/合成格的状态必须正确（不复制物品、不吞物品）。
- 与既有 `stacksTo(1)` 不冲突（输出是两个**独立** ItemStack）。

### F. 自动合成询问
1. 发送时逐条订单算 `have`（网络存量）；有缺口且该资源**可自动合成**（`AutocraftingNetworkComponent` 有匹配样板）⇒ **先不发**，回 S2C 询问包，客户端弹 `SenderCraftPromptScreen`。
2. 询问文案三数：`目标 %s · 现有 %s · 最多可合成 %s · 还缺 %s`；「最多可合成」用 RS 的 `PreviewProvider#getMaxAmount(resource, CancellationToken)` 求（**不许**自己遍历配方瞎猜）。
3. 用户选「合成 X 并发送 Y」：立刻投递现有部分，同时对缺口启动 RS 合成任务（`startTask`），剩余缺口登记 `PendingSenderSends`（服务端 tick 补投：凑齐即投；超时 10 分钟放弃并提示，绝不无限挂起）。
4. 用户选「只发送现有」：只投现有，缺口用 actionbar / 提示行说明。
5. 不可合成 / 无缺口的发送路径**行为不变**（回归判据）。

## 4. 协作纪律（每个实现方都必须遵守）

1. **只改自己写作用域里的文件**；要动别人的文件 / 共享文件（§2 最后一行）⇒ 先发消息给 Lead。
2. **不许跑 `gradlew`、不许跑 git 写操作、不许改 `gradle.properties`、不许改 `lang/*.json`、不许部署**。构建 / 部署 / 提交 / tag 全部由 Lead 做。
3. **编译自检**：跑 `pwsh -NoProfile -File tools/_compile_<你的名字>.ps1`（Lead 已按名字准备好，输出到 `build/compile_<你的名字>/`，互不干扰）。看到 `LEAD_COMPILE_OK sources=N` 才算过；只在自己负责的代码上修。
4. **每个工作流写一份自检脚本** `tools/selfcheck_round131_<流>.py`（照抄 `tools/selfcheck_round130_*.py` 的骨架：`ALL_PASS` / 计数 / 反例有判别力）。自检必须：
   - 断言语义（不是「文件里有这个字符串」这种空话，要钉住几何 / 顺序 / 守卫）；
   - 有 **≥5 条反例**（把旧写法拼回去必须判负；空树必须判负）。
5. **完成时给 Lead 发一条消息**（`send_message`），内容固定四段：
   - `DONE <组>` 或 `BLOCKED <组> <原因>`；
   - 改了哪些文件（完整路径 + 一句话）；
   - 新增 / 删除的**语言键**（键名 + 中文 + 英文，Lead 统一写进 lang）；
   - 需要 Lead 做的**注册项**（包 / 菜单 / 界面 / 配方，给出类名与注册位置）；
   - 自检脚本路径 + 输出末行。
6. 时间盒：**先交付可编译的最小闭环**，再补自检与文案收敛。卡住超过 20 分钟没有进展 ⇒ 发消息说明卡点，不要闷头硬猜。

## 5. Lead 待办（不在上面写作用域里的事）

1. 准备 `tools/_compile_<名字>.ps1`（每个人一份，输出隔离）。
2. 添加本轮全部 lang 键（按各组的完成消息汇总）。
3. `RS_Create_Compat.java` 注册：新包、新菜单（若需要）、配方序列化器/RecipeType；`ClientInit` 注册新界面。
4. 全量编译 / Gradle 构建 / 全量自检（含历史 192 份）/ 专服探针 / 四处部署 / git commit + tag。
5. 写 `docs/ROUND131_LANDING.md`；交付后按用户要求**关机**。
