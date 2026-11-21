# 机械动力 & 精致存储：兼容与改善 `1.2.0`

> **一句话：这是把 1.2.0 的 43 轮 beta 收成一个正式版的版本 —— 新增了一批机器与界面，同时把这一路上「会崩服、会丢料、会静默失效」的问题收掉。**
>
> `1.1.0` 让长产线在规模下跑得动；`1.2.0` 把配套机器（区域选定器、高级归流缓存仓、容器代理器、网络资源接收器 / 请求器、FE 能量立方、电动飞行背包、降解室发电机）和它们的界面补齐，并把 beta 期间用户实机反馈与独立只读复核发现的严重缺陷修完。本版不改变样板 NBT，不要求拆掉重放任何已放置的机器。
>
> 适用于 Minecraft `1.21.1` / NeoForge `21.1.x`（构建对 `21.1.248`）。版本号 `1.2.0`。

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.248`（版本范围 `[21,)`） |
| 机械动力 Create | `6.0.10-280`（版本范围 `[6.0,)`） |
| 精致存储 Refined Storage 2 | `2.0.9`（版本范围 `[2.0.0,3.0.0)`） |
| 本模组 | `1.2.0` |
| 模组 ID | `rs_create_compat` |

> 说明：下文验收数字由两部分组成 —— 最后一轮 beta（`1.2.0-beta.43`）的完整落地记录，以及 `1.2.0` 正式构建**重跑**的全量自检 / 专用服务端真启动 / 客户端真启动结果（见「性能与稳定性」表）。正式版 jar 的 sha256 以发布附件为准；启动日志里的 `[rscc-build]` 行是判断「手里这份是哪个构建」的唯一凭据。

---

## 升级一句话

**从 `1.1.0` 升到 `1.2.0`：不用重建世界，不用拆掉重放任何已放置的机器，也不用重新生成样板。**

依据有三条：`1.2.0` 是**次版本号**（`VERSIONING.md` 的口径：向后兼容的新能力与行为改进，老存档、老样板直接可用，不需要重建世界、也不需要替换已放置的机器）；`1.2.0` 全周期没有「样板 NBT 字段新增 / 改名 / 改含义」的记录，唯一明确提到样板格式的地方写的是「样板格式与既有流程完全不变」；本版新增方块的旧档迁移都是自动的、只搬不改语义。

唯一需要主动做的一件事：**诊断日志默认是关的**。要抓日志先敲 `/rs_create_compat devlogs on`。

保守口径（材料里能确认的例外，逐条写在「升级注意」里）：基础版定量保持器**流体 / 气体**的默认目标从 64 mB 变成 64000 mB，但**旧存档里已存的 64 mB 不会自动改**，需要重新标一次；序列装配「输出总线默认导出类别」对**从未手动勾选过**的总线会按工位推导，`Config.sequenceStationDerivedCategories = false` 可退回旧口径；若你在 1.2.0 的 beta 期间用过旧版降解室发电机（256 格），读档会自动迁移，旧进度作废但物品不丢。

---

## 本次新增的能力

### 区域选定器与常驻「区域条」

- 手持物品划定一组区域，数据存在物品组件里（可跨维度携带、绑定维度），上限走 `Config`。插进高级归流缓存仓的专用槽后，该仓只在选定区域里吸收掉落物 / 经验球 / 世界流体，界面如实显示「生效中 / 区域为空 / 维度不符」。
- 区域条常驻在机器 GUI 右缘（`+6`、宽 **176**；屏幕挤时 128 → 96 → 72 自适应，低于 72 不画并提示一次）：点行选中、世界框常驻、当前区域三重标记（左侧亮橙竖条 + 行底橙带 + 行首 `▶`）。
- 行内就地改名、改六个坐标、删除（**3 秒确认态**，未 armed 不可能发出删除包）；顶部搜索框同时搜名字与坐标（空格分 token 全命中，坐标串去掉空格 / 逗号也能命中）；「显示全部 / 关框」。
- 每区域的过滤标记**跟着选定器物品走**（存在 `CUSTOM_DATA`，按六坐标几何为键，不再按区域序号），不再是「非得走到机器前、把选定器插进方块才能配」；另有一个独立标记界面（左网格 = 已有标记、右网格 = 背包镜像，支持 JEI 拖入）。机器界面的 48 格匹配区也能直接编辑当前选中区域。
- 旧档 `AreaFilters`（按序号）自动迁移成几何键；调小区域上限不再在下一次点击时把超限区域写死丢弃，超限区域标红「不参与扫描，配置调回大值即恢复」。

### 高级归流缓存仓

- **512 格**缓存（总容量 32768）、流体 / 气体缓存 **1,024,000 mB**；吸收范围只按区域选定器划定的区域（**取代**三轴半径，不是取交集：没有选定器 / 区域为空 / 维度不符 ⇒ 一段都不吸，绝不悄悄退回半径）。
- 区域列表只列服务端确认可用的区域，行内显示「序号. 名字」+ 两角坐标 + 该区域前 8 条过滤图标，**不印注册 id**；带搜索、改名、删除确认；区域行与「编辑过滤」两条直达编辑入口。
- 状态带直接显示「为什么没吸」，并删掉了四条无用药水红字（没有生效标记 / 范围内没有匹配 / 命中但没凑够数量 / 吸取开关关着），只保留真会让机器停摆的原因（未接网络 / 未激活 / 缓存满 / 区域三态）。
- 吞吐与基础版一致（单次 64×(1+堆叠升级)、每 tick 1+速度升级组），全部经既有容器与 `RsccRateLimiter`，没有第二套公式。

### 容器代理器

- 把一格里的「自带库存的东西」（潜影盒 / 背包 / RS 磁盘…）当作相邻容器暴露出去；六个面各自可选「内容物面 / 代理对象面 / 禁用」。
- 内容物面分**主 / 备**：主面完整读写；备面「仅进」（`getSlots()==1`、读 / 取恒空、`insertItem` / `fill` 真实写入同一底层库存），因此两个外部存储贴着不会把同一份库存算两遍。代理对象面也唯一（`Direction` ordinal 最小者保留，其余降为禁用）。
- 刻意**不是** RS 网络节点 —— 同一批物品被「网络节点存储」与「外部存储」各计一次就是自指环。
- 收纳袋（`minecraft:bundle`）可读可写可部分取出；精妙背包的快捷键只认玩家背包栏（结构限制），tooltip 常显说明；所有写路径遵守「绝不悄悄清掉」：替换是替换、放不下就弹到世界。

### 网络资源接收器 / 请求器

- **接收器** = 带名字的落地点 + 缓存（**1728 件物品 / 2000 mB 流体**）；名字在同一网络内唯一（重名时改名被拒绝、请求器拒绝投递，绝不随机挑一台）。作为 inventory 提供者**没有方向限制**（任何面与不带方向的查询都返回同一份视图；`FACING` 只决定成链输出与第三方资源直通），对溜槽 / 漏斗暴露可写视图，写入走请求器同一个 `applyResource(...)` 入口。
- 接收器界面是**分页资源列表**（每页 12 条，滚轮 / 拖动 / `< >`），不是固定 16 格：第 17 种资源也看得见、取得出。物品左键一组 / Shift 全部；流体 **1000 mB 换 1 桶**（背包里要有空桶，没有就不扣并提示）；取不走的一件都不扣，放不下的回填缓存、再不行掉到脚下，绝不复制 / 销毁。
- **请求器**按名字把资源投进接收器：红石上升沿或「立即发送」触发一次，整张订单排进计划、按「每 tick 批数」跨 tick 投完，**总量恒等于订单量**；缓存满 / 网络不够会明确提示并结束本次请求。
- 请求器界面 = **网格 + 订单清单**：右键直接打开网格，条目数无上限、可搜索、可删除、可改数量（Shift ×10、流体按桶步进）；顶部工具条有目标切换 / 立即发送 / 清空订单 / 节拍文字 / 设置；带「已投 / 剩余 / 缺口」进度行。
- 没电 / 离网的请求器不再继续干活（`doWork()` 与 `triggerRequest()` 同一道闸门），未投完的计划与回滚缓冲原样保留、一件不丢。

### FE 能量立方

- 单块默认 **5000 万 FE**（可配置），相邻六面容量共享、每块只按自身存储记账（破坏只带走本块电量，不做平均）。它是可进网络能量池的 `EnergyProvider`，网络消耗时排在控制器之后 = 天然低优先级。
- 每 tick 向相邻六面**主动推送** FE（`Config.feEnergyCubePushPerTick` 默认 10000 FE/t，六面分摊；先 `receiveEnergy(SIMULATE)` 问收方、按 min 扣集群、收不下退回差额；不推给相邻立方）；外部注入 / 抽取永不受控制器门控拦截；网络池里立方严格最后。
- 界面第 8 行「相邻供能：开 / 关 / 无相邻」；贴着的范围充电器会先被供能。
- 物品形态能显示电量、能被充电（电量就是方块实体的同一份 `TAG_ENERGY`，放下带过去）；整叠**不能**充放电（否则拆堆 / 放置会放大电量，现在能力双出口与双读写口都拒绝并提示「拆成单块才能充」）。

### 电动飞行背包

- **5000 万 FE**、无耐久、**不提供任何护甲值**；可放 Curios `back` / `chest` 槽，也可穿在胸甲槽；创造模式飞行（空格 / 双击起飞、Shift 下降），飞行时按 `electricJetpackFlightCostPerTick` 扣电（默认 **8 FE/t**，本版从 32 降下来），**付不起一 tick 就当场断飞并坠落**。
- 低电阈值按「剩余可飞分钟」：新配置 `electricJetpackLowPowerMinutes`（默认 30），阈值电量 = 30 × 1200 tick/min × 8 FE/t = **288,000 FE**，严格小于才提示。用户实机场景「充了 200 万 FE 仍提示低电」变成「剩余约 208 分钟，不再提示」。
- HUD / tooltip 用千分位精确值（例如 `2,000,000 / 50,000,000 FE`），不再用 `%.1fM` 缩写；低电 title 只在真的在飞时发，持续低电每 30 秒至多一条。
- 创造模式右键穿戴改为按生存语义**移动**，不再出现「手上 1 件 + 身上 1 件」的复制。

### 降解室发电机

- 只接受「可降解表」里的植物（木头 / 原木显式排除）；数据包驱动（`data/<ns>/decomposition/*.json`，支持 `item` / `tag` 两种匹配键与 `time` / `energy`、`replace` / `remove` / `mod_loaded`）。
- 界面整面重做：**16 输入 + 4 副产物**、原版熔炉箭头、全界面唯一一条 RS 能量条、6 格升级槽（只收速度 / 堆叠升级）；副产物格只出不进，不可降解物在能力层就被拒（漏斗 / 输出总线 / 管道都塞不进来）。
- 平衡四档：T1 小件 10 tick / 25 FE、T2 中件 20 tick / 50 FE（甘蔗锚点）、T3 大件 40 tick / 100 FE、T4 压缩块 180 tick / 450 FE；速度升级只缩耗时（0/1/6 个 ⇒ 20/10/1 tick，**单件发电量恒 50 FE 不变**），堆叠升级只抬每格上限（64/128/448）。
- JEI 新增「降解发电」分类：一页看「吃什么 / 发多少电 / 耗多久 / 副产物概率」，点箭头跳到 JEI；数据由 S2C 整表快照下发，专服客户端不会看到空页。
- 旧 beta 的 256 格机器自动迁移：可降解物优先入 16 输入格、余量入 4 副产物格、再放不下随 NBT 落盘并在**首个服务端 tick** 掉到方块位置；旧进度作废但物品不丢。

### 能耗监视（高级远程终端第 7 档）

- 列出**终端绑定的那一张网络**里正在耗电的方块：每台当前 FE/t 与标称值，相同方块类型合并一行、可展开看每台坐标 + 维度，按合计耗电从高到低排序，每秒刷新。点某一台在世界里用橙框高亮（再点取消）。
- 关界面后高亮不再立刻消失，改成 `energyAuditHighlightLingerTicks`（默认 1200 tick = 60 秒）后自动灭；重开界面点「清除高亮」立刻撤。
- 右列按实测文本宽度**从右往左**排，不再「数字和黑色数字贴在一起」；放不下先撤台数、再撤用量（两者仍在行 tooltip 里）。

### 自动合成仓 × Create 动力合成器 / 动力搅拌机

- `create:mechanical_crafting` 样板按**格位物理拓扑**把每件输入放进正确的合成器格（空格不塞东西）；`create:mixing` 支持多物品 + 多流体一次派发；产物（含流体）经网络唯一出口先 SIMULATE 再 EXECUTE 收回网络。
- **样板格式与既有流程完全不变**，只做补丁式接管。

### 范围充电器：四类过滤与「世界中的方块」

- 过滤界面从 3 类扩到 **4 类页签**：掉落物 / 玩家身上的物品 / 玩家 / 世界中的方块；方块页签列出范围内检测到的可充方块 ∪ 已配置，带方块图标、去重、拼音搜索。
- 全局黑 / 白名单一个开关；玩家名单落盘（`rscc_charger_known_players`，离线也在），按名字或 UUID 添加；32 位裸十六进制 UUID 与 36 位带横线都认。
- 搜索支持拼音（运行时反射调用 Just Enough Characters 的 `Match.contains`，没装 / 反射失败 / 字典未就绪一律退回不区分大小写子串）。
- 过滤名单有硬上限：物品 1024 / 玩家名 256 / UUID 256 / 名字长 32 / 已知玩家 1024，满员有提示。

### 界面与文本

- **界面文本一律完整显示，不再出现省略号**：全工程删除 `"..."` / 按像素截断 / 静默裁字，统一走 `RsccTextFit`（折行 → 改排列 → 缩短静态文案 → 整体缩字号（下限 0.6 倍）→ 自由文本进 tooltip，永不省略）；`selfcheck_no_ellipsis.py` 扫 100 个客户端源文件，零 offender。
- 6 个还没工具化的容器界面背景改用 `make_gui_bg` 重绘（中间产物缓存仓、接收器、请求器、两个序列界面、F1 能量立方）；「PNG 槽框左上角 = Menu 坐标 − 1」的约定由像素级自检钉死。
- 所有「纯电」GUI 能量条统一到 RS 原版 `ProgressWidget`；范围充电器的「显示范围」与能耗高亮在关界面后按剩余 TTL 重新武装，不再「点一下 → 关界面 → 抬头什么都没有」。
- 范围充电器的充电过滤玩家头像改成真实皮肤头（在线用 `PlayerSkin`、离线用 `DefaultPlayerSkin`），按 UUID 缓存、20 tick 低频补正。
- 新方块 5 条成就（高级归流缓存仓、容器代理器、接收器、请求器、区域选定器）；接收器 Jade 提示由服务端数据通道下发；4 个新方块补进 `minecraft:mineable/pickaxe`；高级归流缓存仓补进 RS 升级目的地表。

---

## 重要修复

按「会不会崩、会不会丢东西、会不会静默失效」分组。

### 一、会崩服 / 崩客户端

**1. 能量立方集群把服务端拖到看门狗判死（持续慢 tick，不是死循环）**

- 现场：远程服务端 `crash-2026-11-14_22.43.50-server.txt`，栈尖 `EnergyCubeCluster.collect(:299)`。数字 = 重置后 **70.04 秒只推进 10.03 秒调度时间（≈200 tick ≈ 350 ms/tick）**，60 秒窗口内 `22:42:53 / 22:43:07 / 22:43:30` 都有 Server thread 日志 ⇒ 是持续慢 tick，不是一次卡死。
- 根因：1.21.1 的 `Level#isLoaded` 对「在加载流水线里、还没到 FULL」的区块也返回 true；旧 `isCubeBlock` 先 `isLoaded` 再 `getBlockState`，后者内部走 `getChunk(..., FULL)` 会在主线程**同步等区块生成**。玩家登录 / 传送后的区块加载窗口里逐块等 ⇒ tick 被拉到约 350 ms，持续到看门狗判死。
- 修法：改用 `getChunkSource().getChunkNow(...)`（只返回 FULL、不加载 / 不加票据 / 不阻塞）+ `LevelChunk#getBlockEntity(pos, CHECK)`（不创建）；未就绪区块记 `partial`，该快照永不视为 fresh；`MAX_REBUILDS_PER_TICK = 8` 硬预算；单次重算 ≥ 20 ms 记 WARN（5 分钟冷却）。
- 收口：新增 `support/SafeChunkRead`，全工程 20+ 处改造（机器集群、范围充电器、执行仓 12 处 `getChunkAt`、缓存仓、能量立方 / 发电机 / 请求器的邻块读、线缆搜索、工位探针、C2S 包…），**全工程 `getChunkAt(` 调用清零**（自检 ④-1 守着）。

**2. 分解发电机界面 Shift+点击必崩客户端**

- 真因：`PLAYER_SLOT_COUNT = 36 + 9` 把快捷栏算了两遍 ⇒ `PLAYER_SLOT_END = 100`，实际只有 91 个槽；Shift 点任意玩家槽 → `slots.get(91)` ⇒ `IndexOutOfBoundsException: Index 91 out of bounds for length 91`（客户端硬崩，`QUICK_MOVE`）。
- 修法：常量链改为 27 + 9 = 36；三处区间改用 `playerStart / playerEnd / mainEnd` 且 `Math.min` 夹到 `slots.size()`；两个方向语义不变。

**3. 高级归流缓存仓一开界面就把玩家踢下线**

- 真因：`AdvancedCollectionCacheMenu` 继承基础版菜单，父类构造器把 `MenuType` 写死成基础版 ⇒ 服务端开高级版（139 物品槽 / 35 数据槽）却告诉客户端建基础版（138 / 29）；第一次全量同步 139 个 `ItemStack`，客户端 `initializeContents` 走到下标 138 越界，连接被原版断开（日志 `IndexOutOfBoundsException: Index 138 out of bounds for length 138`，紧跟数据槽 `Index 29 / length 29`）。
- 修法：父类加接受 `MenuType` 的受保护构造器，基础版传自己的、高级版传自己的；`selfcheck_round91_menu_type_match.py` 用 22 断言 + 6 反例钉住「子类不许沿用父类菜单类型」。

**4. 归流缓存仓 Shift+左键写标记触发无限循环（整合端冻结 / 专服看门狗崩服）**

- 真因：`quickMoveStack` 的 ghost 分支「什么都没搬动却返回了非空 stack」；原版 `AbstractContainerMenu#doClick` 的 QUICK_MOVE 是 `while (!r.isEmpty() && ItemStack.isSameItem(slot.getItem(), r)) ...`，`isSameItem` 只比种类、源槽又不变 ⇒ 无限循环。任意客户端发一个 QUICK_MOVE 包即可复现。
- 修法：所有「没搬动」分支一律返回 `ItemStack.EMPTY`；`selfcheck_round95_collection_cache.py` 新增 B5 判据 + 反例钉住。

**5. 保持器的流体能力暴露给 RS 外部存储会无限递归**

- 现象：`External Storage → Keeper → Network → External Storage` 形成循环 ⇒ `StackOverflowError`。
- 修法分两轮：先用 `KeeperNetworkReadGuard`（同线程重入闸，四个网络抽取入口内层返回 0）把崩溃止住；第 142 轮把**整份对外能力只对第三方物流可见、对 RS 自己的存储聚合隐藏**（`KeeperCapabilityVisibility`，`StackWalker` 只认 `com.refinedmods.refinedstorage` 前缀、24 帧上限；物品读 / 插 / 抽 + 流体读 / `fill` / `drain×2` 各 7 处，每处都是方法第一条判据）。对 Create 漏斗 / 管道 / 原版漏斗完全不变。

**6. 开发客户端在标题界面因 Jade 缺语言键而崩（新探针第一次运行就抓到）**

- `AssertionError: Missing config translation: config.jade.plugin_rs_create_compat.proxy`：容器代理器的两条 Jade provider 只加了代码、没加语言键；Gradle JavaExec 默认开断言（`-ea`）⇒ 开发环境客户端在标题界面初始化时崩溃，正式环境则是 Jade 配置界面显示原始 key。
- 修法：`en_us.json` / `zh_cn.json` 各补两条；新增 `selfcheck_jade_lang_keys.py`（19 断言：从 Jade 源码解析所有真实注册 UID 并逐个校验两个语言文件，删键判负）。

### 二、会丢数据 / 丢配置

**1. 单元样板「一叠」搬运时静默丢 N−1 张（复核报出的高危）**

- 单元样板可堆叠 64，执行仓单元槽每格也能放一叠；旧「取回」把整叠写进样板库一格、「存入」只 `copyWithCount(1)` 后把整格清空 ⇒ 一叠 N 张变 1 张，界面还按「格」计数显示成 1。
- 修法：两条路径都**按张搬运**（取回逐张写库、写成功才 `shrink(1)`，库满 / 体积预算拒绝时剩余原样留在执行仓；存入逐张 `extractItem(i, 1)`，先写执行仓再扣库）；库满给动作栏提示；汇总界面「张数」改按 `ItemStack.getCount()` 求和。旧档同格多张也能逐张取出，一张不丢。

**2. 装填器共享蓝图槽静默拒收 = 当场丢掉一张蓝图**

- 场景：基础 + 高级装填器同时贴一台炮、彼此不相邻时，队列流水线掌管加农炮蓝图槽，`BlueprintView#setStackInSlot` 静默 `return`；而原版数字键 SWAP / Shift 点击在调用 `set` 之前就已把物品从玩家背包摘掉 ⇒ 蓝图当场消失。
- 修法：菜单 `mayPlace` 先挡（原版根本不会 detach），视图拒收时把物品**落回世界**（第二道防线）。

**3. 定量保持器的目标数量被 short 截断，进而按错误目标销毁真实资源**

- 真因：MC 1.21.1 的 `ClientboundContainerSetDataPacket` 线上就是 short，目标 int > 32767 会被截断；流体按 mB 计，**目标 > 32.7 桶（输入 `33b`）就中招**：界面回填 0，玩家点 ± 时把服务端目标静默改小（开「过量销毁」时按错误目标销毁真实资源）。
- 修法：目标拆成「低 16 位 + 高 16 位」两个 `ContainerData` 索引（高半段追加在末尾，不动既有下标），两端按无符号 16 位掩码拼回；基础 / 高级两台都改。

**4. 带组件流体「取不出来」**

- 三处不对称叠加：`drain` 编码组件时不带注册表上下文而 `fill` 带了（同一补丁可能编出不同 NBT）；`getFluidInTank` / `drainById` 返回的 `FluidStack` 把数据组件补丁丢了；本机查不到时静默回退网络、且基础版只比流体 id（请求别的变体也会把标记流体冒充成请求流体交还）。
- 修法：两台保持器各加唯一入口 `registryHint()`（`level.registryAccess()`），`fill` / `drain` / 入网 / 仲裁 / 回流全走它；返回值一律带组件；基础版网络回退收紧为「请求的 id **且** 组件 NBT 与直接标记完全一致」。只改「补丁的携带与编码上下文」，不改标记匹配口径，旧存档条目不需要迁移。

**5. 「>288 条」不再静默截断**

- 场景：改区域几何（REPLACE）会把旧几何的标记搬到新几何；两块地都接近上限 `MATCH_CAPACITY = 288` 时，旧实现让 `AreaMarks.put → sanitizeEntries` 截断，尾部标记永久消失，而数据层截断时一个字都不打。
- 修法：合并抽成纯函数 `mergeAreaMarks`（与旧实现逐字同语义）；超 288 条在唯一写入点 `AreaSelection.set` **之前** `reject(REJECT_MARK_OVERFLOW = 6)` + `return` —— 旧区域与全部标记一个字节都不动，玩家删掉一些再来。拒绝原因进 zh / en 语言键，主手路径走聊天提示、菜单路径走区域面板同一句文案；`AreaMarks.put` 与 `migrateByIndex` 截断时打 WARN。

**6. 区域标记链的连环丢配置（beta.13 → beta.16 三轮复核）**

- 改区域坐标（REPLACE）会删掉该区域全部过滤标记（高危回归）⇒ REPLACE 前读旧几何标记、写回后搬到新几何、按 `(id, fluid)` 去重合并、不覆盖已有条目。
- 旧档迁移「一条都没映射上」时仍清空旧表 ⇒ 只删「已映射」条目，未映射原样保留在存档里 + WARN；物品上已有任意一条新标记就整张丢弃 ⇒ 改为逐区域合并。
- 迁移用 sanitize 后的区域表做序号映射 ⇒ 标记套到别的区域 ⇒ 新增 `AreaMarks.isIndexPrefix`：只有「sanitize 只砍尾巴」时才按位次迁移，否则整体冻结 + WARN。
- 单条标记没有大小上限（成书 / 带内容物容器一条就上 KB，288 条能把 `CUSTOM_DATA` 撑到 MB 级、随物品同步超包）⇒ 新增 `MarkerBudget`：单条 **2 KB（线上）** / 整表 **64 KB**，覆盖手持界面、`AreaMarkEditPacket`、机器 48 格、基础版匹配区、`setMarkerConfig` 等所有写入口；**删除永远允许**（超限物品必须能自救）。
- 口径校准（复核发现差 8 倍）：`CompoundTag#sizeInBytes()` 是 JVM 堆估算 ≈ 线上 × 8，最初把 2 KB / 64 KB 直接当堆阈值，等于线上只有 256 B / 8 KB，会把「默认 16 区域、平均 6 条」判成超限；现在阈值 ×8，玩家文案显示 64 KB。
- 旧档迁移会把超大标记搬进选定器（迁移跑在区块加载时，一旦超预算该选定器永久不能再加标记）⇒ 逐条判：单条过大 / 搬进会超整表 ⇒ 冻结在旧表（不消费、不删）；只有真写进物品 / 物品已有标记 / 空条目才消费。
- 把区域上限调小之后点一下区域列表就永久丢掉超限区域 ⇒ 写入侧不再 `sanitize`，上限只在 ADD / REPLACE 时校验新几何；已有区域原样保留，`sanitize` 只留在消费侧。
- 48 格窗口 JEI 拖入「新 id 被忽略」：第 130 轮只修了「拖到空格子」那一半；**拖到已有条目的格子**上，高级版 7 参覆写只调 `setAreaMarkerConfig`（只改数量 / 规则、不接受 item id），拖进来的 id + 数据组件被静默丢掉。第 140 轮新增 `replaceAreaMarkerEntry`：异 id ⇒ 替换该下标（去重跳过本下标自身、单条 + 整表预算同口径、失败走 `DUPLICATE` / `FULL` / `TOO_COMPLEX` 的 actionbar 文案）；同 id 仍只 update。

**7. RS 吞输入：外部样板 sink 整批原子性 + 执行仓上游缓冲**

- 真因：RS 2.0.9 的 `REJECTED` 不会让任务重试（源码注释原文 *"we lie … potentially void the extracted resources"*），EXECUTE 前输入已从任务内部暂存扣掉 ⇒ 网络剩余空间不足时**后半批材料被 RS 自己吞掉**；旧 javadoc 写的「让任务稍后再试」是假的。
- 修法（两步）：先把一批资源按总量聚合、**全量 SIMULATE 全过关才 EXECUTE**（任何一条放不下 ⇒ 一条都不插）；再给执行仓自建**有界上游缓冲**（9 格物品 × 64 + 9 格兜底、64,000 mB 流体 + 64,000 兜底，硬上限 1152 件 / 128,000 mB）：`accept(SIMULATE)` 只判缓冲空间，`accept(EXECUTE)` **只写缓冲且恒 `ACCEPTED`**，每服务器 tick 由 `doWork` 先 drain 回网络；缓冲随 NBT 落盘，拆方块走既有内容物结算。
- 代价（明说）：每轮 **+1 tick 延迟**；极端配方单次迭代超缓冲上限 ⇒ 任务停滞但**不丢料**（宁停不丢）。

**8. 序列装配：多发一块金板且不回去 / 精密构件永久卡住 / 凭空多占料**

- **多发金板（第 133 轮）**：真因不是「多做一件」，而是结束边沿那一刻工位「恰好空」（机器已吃进原料、成品未吐回）就判定「无残留」并删登记，之后机器吐回的过渡件再无路径能看见 ⇒ 永久留在台上。修法：收尾保留判定改按**整工位**（登记坐标 → 置物台 → 同工位其它容器）、清扫主扫 + 整工位补扫（非登记坐标只允许收过渡件，绝不碰起步原料）、diag 新增 `inFlightUnits[]` 并改用「看门狗 + 玩家周边区块」并集（只用 `getChunkNow`，绝不主动加载）——此前两份 diag 的 `chambers=[]` 是因为看门狗只枚举了出生点区块。
- **精密构件卡住（第 136 轮）**：现场 `create_enjoy` 快照里任务 `create:precision_mechanism` ×5 只交付 3，执行舱在制登记两条 `step=-1` 的金板 age **9,409,580 / 9,409,805 tick（≈5.4 天）**，从未进入第一个工序 ⇒ `capacity = remaining 2 − inFlight 2 = 0` ⇒ 不再补料。既有三条回收路径全到不了（名额为 0 时根本不推、金板是起步原料不是结果池、判据 ④「登记物品 == 台上物品 ⇒ 合法等待」没有时间上限）。修法：新增 `stale_bare_registration` 分支 ——「台上仍是裸件 + 就是本仓登记的那一件 + 自上次成功推送满 **60 秒**」= 可证死；走既有守恒通道（SIMULATE 夹量 → 精确抽 1 件 → 优先进网络 → 装不下才收本仓 → 两侧都装不下原样还回），先搬后撤登记；孤儿登记（整工位找不到那件也找不到过渡件）只删本模组账本、一个字节都不动；每 2 秒一轮、每轮至多 1 件、同一工位另加 5 分钟冷却。
- **按需补发（第 134 轮）**：旧口径把「下单 20 件、精度配方 p=0.8 ⇒ 在制上限 `ceil(20/0.8)=25`」当设计行为；用户驳回「资源紧张时凭空多占 5 份起步原料就是坏事」。新契约：`allowed = remaining`（订单还差几件）、`capacity = max(0, allowed − inFlight)`，**与 p 无关**；开局恰好只开 20 件，失败一件补一件。p 只进诊断（diag 行新增 `P=` / `p=` / `guaranteed`），不再参与任何名额判定。
- **在制登记表落盘（第 121 轮 F4）**：`inFlightUnits` 以前是内存态，重启后三条回收路径失去唯一凭据 ⇒ 工位被没人认领的原料永久占死、输出总线此后 `machine_full`。现在按条目落盘（`InFlightUnits`：station / recipe / step / item / sinceTick），读档回填并淘汰失效条目；只恢复「登记」不恢复运行期证据，重启后「卡死件」强旁证需本仓再拒收一次才成立。

**9. 单元样板库 / 导入包 / 名单的体积与计数上限（改包可达）**

- 单元样板库 **1024 格**硬上限 + **1 MiB 线上 / 8 MiB 堆**体积预算（`UnitLibraryLimits`，唯一写入口 `trySetStackInSlot`，原子拒绝、只拦增长、读档不判上限）；「生成样板」在写总样板与扣 RS 样板**之前**预检，扣了材料却丢样板不可能发生。
- `SetSequenceImportPacket` 解码不再按客户端报的条数预分配（谎报 `2³¹-1` 可在菜单校验前 OOME）；只暂存前 4096 条、整包 8192、单条 2 KB / 合计 256 KB / 字符串 256、步数上限 1024；`recipeId` 单条 256 字符并计入合计（复核发现它以前既不限长也不计入，32 KB recipeId + 1024 个不同机器名可让约 33 MB 落盘）；解码发现「丢过任何一条」整包拒绝。
- 归流缓存仓阻塞名单：id 1024 / tag 256；唯一写入口 `applyBlockedEdit`，原子拒绝、只拦增长；标签包解码按声明条数消费但只暂存前 128 条。
- 两台保持器的标记写入口接入同一把体积尺（≈线上 2 KB），超限拒收、旧标记原样保留、每台一条 WARN。
- 充电过滤名单硬上限：物品 1024 / 玩家名 256 / UUID 256 / 名字长 32 / 已知玩家 1024。

**10. 缓存池读数求和溢出（5 块以上无限盘 ⇒ 中间产物一件都不搬）**

- `poolFreeSpace` / `poolStored` / `poolContents` / 诊断摘要都是裸 `+=`，而「无限容量盘」每块报告 `Long.MAX_VALUE / 4` ⇒ 池子里同时插 **5 块以上**就回绕成负数，闸门 A 把「几乎无限的空位」读成「池子满了」，中间产物一件都不搬（不报错、不打日志）。现在统一走 `support/SaturatingMath#add`（减法判溢出，非负输入永不回绕）。

**11. 区域扫描的流体探测会拉动未加载区块**

- `fluidSourceIdAt` 第一行直接 `level.getBlockState(pos)`，而区域是跨区块大范围扫描（每 tick 上千次查询）⇒ 玩家离开基地、区块被卸载后，机器还在跑就反复触发**同步区块加载**（无异常、无日志，只表现为服务器卡顿）。现在先判 `level.isLoaded(pos)`，未加载坐标一律当「没有流体」（只读探测，不丢资源）。

### 三、静默失效与「界面说谎」

**1. 保持器的五项遗留口径在本版收口（H2 / M2 / M1 / L2 / M3）**

- **H2（外部存储把网络镜像再算一遍 ⇒ 存量虚高、多销毁）**：RS 2.0.9 的外部存储会把保持器对外暴露的 `IItemHandler` / `IFluidHandler` 注册成网络自己的存储源，导致「网络镜像」与「本机缓冲」被重复计数。按「最优方案」改为整份能力只对第三方物流可见、对 RS 自己的存储聚合隐藏（见上「会崩」第 5 条）。代价：玩家若在保持器旁贴 RS 外部存储，那台外部存储不再把保持器当存储源（正是双计来源，属预期行为）。
- **M2（流体 / 气体默认目标从 64 mB 改成 64000 mB）**：详见「升级注意」。
- **M1（目标数量服务端上限）**：旧 setter 只做 `Math.max(0, value)` ⇒ 改包 / 手输 `Integer.MAX_VALUE` 会按 21 亿缺口向 RS 要自动合成。现在 `MAX_SET_TARGET = 1_000_000_000`（与配置 `defineInRange(..., 1, 1000000000)` 同口径）+ 唯一钳制入口 `clampSetTarget`，**读档不经过钳制**（旧档超限值原样读回，只拦增长）。
- **L2（中间产物缓存仓无电仍执行存量迁移）**：`doWork` 在节流之前加 `node == null || !node.isActive()` 闸门；未激活时冷却不推进（重新上电第一 tick 就能搬）。
- **M3（未标记的保持器仍认领资源）**：同一网络两台保持器守同一资源时按坐标全序选唯一权威；旧 `claimedResources()` 只看「有没有资源键」不看目标量 ⇒ 未标记但坐标更靠前的会占住权威、让真正生效的那台永远让位。现在逐槽判 `KeeperTarget.isMarked(target)`（与维持 / 销毁 / 补合成同一判据）。

**2. 能量立方 int 饱和导致范围充电器提前判满**

- 立方能力视图是 int 口径，43×50M = 2.15B > `INT_MAX` ⇒ 存量 / 容量同时饱和到 2147483647，旧判据 `getEnergyStored() >= getMaxEnergyStored()` 于是恒真 —— 真实还剩 **2,516,352 FE** 却被判「满」、永远充不进去。修法：判满改用 `receiveEnergy(transfer, true) <= 0`（simulate 不写世界；立方视图内部空间是 long 精确值）。顺序仍是「纯函数算预算 → 判满 → 真取电 → 扣电 → 高亮」，判满失败不扣电、不登记高亮。

**3. 被删掉的原生按钮与「功能在、入口不在」**

- 输入总线的「白 / 黑名单」与「模糊模式」两颗按钮被删掉、只剩一颗红石模式按钮：真因是 1.0.0 起就存在的一处注入 —— `AbstractBaseScreenMixin` 在 RS 的 `AbstractBaseScreen#addSideButton` 的 HEAD 处**无条件** `ci.cancel()` 掉 `FilterModeSideButtonWidget` 与 `FuzzyModeSideButtonWidget`，连「普通模式本该与 RS 原版逐像素一致」的场合也一起删了。现在整段注入删除，类注释立下铁律「只追加自绘控件、绝不删除原生控件」；输出总线侧核实**从来没有**被删。
- 同类：范围充电器「显示范围」还是看不见、高亮「只有配置项、界面没按钮」、能耗监视「一片空白只有 tooltip 和按钮」（`renderContent` 在 `super.render` 之前，被不透明面板整块盖住）、容器代理器「一个 tooltip 都不画」、接收器「缓存看得见但取不出来」、请求器「并没有发生多大的改变」—— 都是功能在、入口或绘制不在。

**4. 高亮 / 显示范围 / 世界框「关界面就消失」**

- 三个原因叠在一起：`removed()` 直接 `Outliner.remove(...)`，而玩家的操作恰恰是「点一下 → 关掉界面 → 去世界里看」；框只有默认 1/32 的细线、没有面贴图；同一处生命周期 bug 也在高级仓「查看范围」与区域选定器描边里。现在关界面按剩余 TTL 重新武装（显示中再点一次仍立即移除）；框补棋盘面贴图 + 1/16 线宽（与 Create 蓝图框同款）；几何按充电扫描的**直径口径**（`half = range / 2`）重建，不会画成两倍大。

**5. 日志 / 诊断说谎与关不掉**

- 开发日志「关掉后仍在刷」：`diag run` 曾裸调 `RsccAssemblyDebug.setEnabled(true)` 且永不恢复，于是「配置里 `devLogs=off`」对当次会话完全无效（实机日志：`devLogs=on (source=command)` 之后连刷 **2956 行 / 12 分钟**）。现在唯一作用域入口 `withEnabledTemporarily(...)`（`finally` 必然恢复），4 个周期打印点接进 `isEnabled()`；`Config.devLogs` 明确标注为「配置镜像值，禁止当判据」。
- 导出器日志 `linked=- reason=no_chamber reachable=1`：不是归属坏了，是短路分支（过滤槽非空 / 强制普通）沿用了上一次结果，日志却把短路一律写成 `no_chamber`。现在原因分别命名并排序：`force_normal` / `filter_non_empty` / `chamber_gone`，只有 `reachable == 0` 才叫 `no_chamber`，不变量进自检。
- 守恒账本把「过渡件」记成永久失衡（`incomplete_precision_mechanism` 每 60 秒 +1）：漏记 `fromMachine`，现在两处成对记账；`RsccFlowLedger` 把守恒公式收成唯一实现，持续失衡 WARN 打印**路径拆解**（离开网络 / 进入网络 / 世界收集 / 推给机器 / 从机器取回 / 销毁 / 舱内 / 网络净额 / 机器Δ）+ 判定（如 `station_return_unbooked`），`diag` 导出同一份拆解。旧世界里已累计的 +1 不追溯清零（内存态，重启归零）。
- 磁盘代理器的数量快照用墙钟做过期判断：NTP 同步 / 手动改时间往回调时 `now - builtAt` 变负、恒「未过期」⇒ 磁盘内容变了却长期显示旧数量与旧槽位绑定。改用单调时钟 `System.nanoTime()`。
- 删掉一条永远发不出去的协议通道：`RequestRequesterResourcesPacket` 全工程没有构造点 ⇒ 对应回包、服务端 `resourceViews()`（约 90 行）与客户端处理函数永远不会被触发（「看起来有、其实永远空」）。整条通道连死代码删除，并新增通用自检「注册的每个 payload 都必须找得到构造点」。
- 成就发给「旁边站着的所有人」：用扳手断 / 接线缆缝、框架单格或连锁套壳都是玩家动作，触发点却用「半径 64 格内的所有玩家」⇒ 自己转扳手、旁边新人白拿成就。现在这些触发点必须带**操作者**，只记本人；拿不到操作者就不发。机器自己干活的事件（自动打印、吸取掉落物、自动合成补货、换盘、样板入库）保持原口径。

**6. 容器代理器与接收器 / 请求器的静默交互问题**

- 容器代理器接两个 RS 外部存储时终端数量翻倍（不是复制物品：底层库存只有一份）：RS 每个外部存储自建 storage、网络把各 storage 求和、不按源方块或 handler 实例去重，而旧代码对**所有**内容物面返回同一个读写视图。现在内容物面分主 / 备，备面「仅进」（读 / 取恒空、写入真实落到底层库存），RS 在备面读到空库存 ⇒ 不参与求和；漏斗 / 管道仍能围到备面推入；界面逐面标注「内容物（主）/ 内容物（备·仅进）」。
- 容器代理器往代理槽里放东西被静默吞掉：`ObjectView` 只实现 `IItemHandler`，而原版 `SlotItemHandler#set` 会强转 `IItemHandlerModifiable` ⇒ 每次点击都抛 `ClassCastException`，整次菜单点击被服务端吞掉（日志栈 `Slot#setByPlayer → Slot#safeInsert`，`Caused by: ... ObjectView cannot be cast to ... IItemHandlerModifiable`）。现在实现 `IItemHandlerModifiable`，写入真正落到代理对象，仍保持「替换而不是先清空再写」。
- 容器代理器识别精妙背包（带储罐升级）时「Jade 只显示经验、不显示物品」：真 bug 是 `resolveInner()` 解析前就写死身份、两路共用一个失败路径、0 格被当成终态 ⇒ 一退化就永久粘住只能拆方块。现在两路各自 try/catch、身份在两路问完后才写、`getSlots()/getTanks()` 自身异常也兜住、退化态 **40 tick** 自动重探；新增四态（BOTH / ITEM / FLUID / NONE）并下发两路格数。
- 接收器与 Create 溜槽：溜槽对上方容器**只抽不推**，而接收器 `extractItem` 恒空 ⇒ 溜槽 SIMULATE 拿不到东西、EXECUTE 根本不发生。现在给可写包装加显式外抽 / 外排落点（默认仍「只进不出」），抽出走缓存自身扣减、`simulate` 零写入、`execute` 才写并 `setChanged()`（不落盘会变成读档复制）。
- 请求器没电 / 离网仍继续干活（接收器有闸门、请求器漏了）⇒ 现在 `doWork()` 与 `triggerRequest()` 同一道闸门；未投完的计划与回滚缓冲原样保留、一件不丢，恢复供电后接着做。
- 请求器失败提示在英文客户端显示中文：方块实体用 `translatableWithFallback(..., 中文兜底)`，而语言文件里一条 `fail.*` 键都没有 ⇒ 英文客户端永远走中文兜底。现在所有 reason 中英成对补齐（含新增 `no_power` / `order_rejected` / `deliver_exception`），中文兜底只作防御层。

**7. 装填器 / 缓存 / 区域扫描的性能型静默问题**

- 装填器每 tick 无条件 `cannon.updateChecklist()`（Create 里最重的操作，遍历蓝图全部坐标 + 逐点读世界 + 重扫邻块容器，且内部置 `sendUpdate` ⇒ 加农炮**每 tick 向周围客户端发一次方块实体更新包，20 包/秒，空闲也发**）⇒ 只在「蓝图真的换了」与「队列部署下一张」时刷新。
- 队列空转：`takeFromQueue()` 写在「该不该部署」判断外面，「加农炮蓝图槽空 + 队列有货」时每 tick「取一张 → 原样放回」；现在只在「刚打完 / 队列刚启动」两个 tick 取出即部署，中途被取空 ⇒ 自动退回开始状态 + 64 格行动栏提示，队列里的蓝图一张不少。
- 一次 server tick 里重复做十几次集群 BFS ⇒ 同 tick 只 BFS 一次（key = `level.getGameTime()`），跨 tick 一律不复用，返回不可变副本。
- 装填器读档后必定白跑一轮「材料回流网络 + 再整份重拉」⇒ `attachRoundBlueprint` 随档保存，旧档没有 key 时以槽里那张「接管」身份（不回流、不复位），真换图仍走原来的回流 + 复位。
- 读档后重复播报「打印完成」横幅 ⇒ 两个哨兵随档保存；旧档走「首 tick 重建基线」，不补播、也不吞掉下一次真实打印。
- 「网络无火药」提示本来发给全服（基地 6 台 = 每 10 秒 6 条全服消息）⇒ 改走 `notifyNearby`（行动栏 + 64 格）。

**8. 数值 / 溢出 / 边界类**

- 范围充电器界面会显示**负数**充电速率：界面自己算 `Config.rangeChargerChargeRate << menu.getUpgradeCount()`，配置 ≥ 8192 FE/t + 满堆叠（等效 18 级）时溢出成负数；现在界面**只读**菜单的 `getChargeRate()`，它与方块实体调用**同一个** `RsccRateLimiter.rateSaturating(...)`，同一个数字不再有两处算法。
- 范围充电器对 FE 能量立方从「工具档 500 FE/t」改成「**电池档 10 万 FE/t**」：立方是电池不是工具，它的面速率本来就该是充电上限。同一 **2000 万**缺口从 **3,334 tick（≈2.8 分钟）** 降到 **17 tick（≈0.85 秒）**；只覆盖 1 块也只要 200 tick（≈10 秒）。玩家物品 / 掉落物 / 非立方方块的工具档（500 FE/t、按 maxTargets 均分）一格没变。
- 新方块的升级容器 `RsccSpeedStackUpgradeContainer` 原先数槽不数个数、未覆写 `getStackLimit`（NeoForge 默认 99）⇒ 一格塞 64 个升级可绕过上限；现在 `getStackLimit` / `getSlotLimit` 夹到 1、按物品个数计数、超出逐个弹出到世界。
- 两台「定量保持器」的旧档「凑够 N 件才吸」门槛（早年 Shift 标记写入手持堆叠数，常见 64，被当硬门槛 ⇒ 扔 1 件永远不吸）⇒ 载入旧档统一归一为 1（记一条 INFO），新标记保留玩家显式设的数量；失败重试退避从 100 tick 缩到 20 tick。
- 范围充电器缓存 5000 万 FE 的一次性迁移（值恰好等于历史默认 100 万 / 1000 万 ⇒ 改成 5000 万并打日志，玩家自己调过的一律不动）+ 新增 `rangeChargerNetworkPullPerTick`（默认 10 万 FE/t）闸住「从网络抽电」，否则空缓存一个 tick 就能把 5000 万抽走、饿死同网络其它机器。
- `fe_energy_cube.transfer` 语言键实参不匹配（语言里 2 个 `%s` 而 Java 只传 1 个 ⇒ 进游戏渲染成 `Format error:`）⇒ 改单占位 + 硬断言「语言占位符个数 == Java 实参个数」双向互锁。
- 区域标记界面文字重叠 / 空状态提示直接落在网格里 / 底部 67px 纯空白；区域改名「看着改了、重开没改」「名字串到下一个区域」；范围充电器过滤候选池把发电机 / 只放电方块当成「可充」、自家范围充电器永远缺席；容器代理器界面「蚂蚁字」、背包贴着底边 —— 这些都在本轮按几何 / 数据判据修掉。

---

## 性能与稳定性

**验证门槛（`1.2.0` 正式版 + 最后一轮 beta 的完整记录）**

| 项 | 结果 |
| --- | --- |
| 全量自检 | **210 脚本 / 0 失败**（1.2.0 源码树，`build/rel_work/selfchecks_120.log`；与 `1.2.0-beta.43` 同一份源码） |
| 独立 javac（不信任 gradlew 结论） | `LEAD_COMPILE_OK sources=528`（beta.43 轮执行，其后源码未变） |
| 构建（正式版） | `BUILD SUCCESSFUL in 9s`，工作树干净（`dirty=false`）；`rs_create_compat-1.2.0.jar` = **2,966,411 B**，sha256 `eb4c54e1…e39ee`；`build_info` = `revision=14009d2 modVersion=1.2.0 sources=528` |
| 专用服务端真启动（正式版） | **PASS**：`Done (1.306s)! For help, type "help"`；`[rscc-build] version=1.2.0 revision=14009d2 … sources=528`；`run/` 前后清单完全一致（只读自证） |
| 客户端真启动（正式版） | **PASS**：`mods - versions {1.2.0}` + **`Sound engine started`**（晚段标记）+ `Created: 2048x1024`；**0 个 crash-report**；无 `Mixin apply for mod rs_create_compat failed` |
| 部署（beta.43） | **四处 sha256 全等**（`build/libs` / 本地实例 / SFTP `/mods` / SFTP `/client_files/mods`）；上一版三处就地 `.disabled`；两个远端目录都断言「恰好只有一个启用 jar」。正式版以本页附件为准 |

自检脚本从 beta.11 的 **151** 条涨到 beta.43 的 **210** 条，0 失败；每一轮新增脚本都带反例自证（例如 beta.43 的 45 断言含 6 条 JVM 调用栈探针 + 前缀改坏反例 + 5 组源码反例；beta.40 的 36 断言 / 6 组反例；beta.39 的 22 断言含 4 组旧写法反例）。

**性能改动**

- **范围充电器不再每 tick 全量遍历范围区块**：每 tick 区块预算 + 跨 tick 轮转游标。普通范围 `MAX_FINITE_SCAN_CHUNKS_PER_TICK = 128`（默认 50 / 100 格 = 16 / 64 区块 ⇒ 与旧行为逐 tick 等价，只有叠满范围升级才分片）；无限范围（创造升级）`256`，1 人 1089 区块约 5 tick 一轮、10 人约 43 tick 一轮，替代旧「每 tick 1 万区块」。代价：无限档单目标 FE/t 约为原来的 1/5（创造模式专用）。
- **能量立方集群**：单次重算 ≤ 64 成员 / ≤ 385 次出队；`MAX_REBUILDS_PER_TICK = 8` 硬预算，超出退化「上次快照 / 只报自己」；单次 ≥ 20 ms 记 WARN（5 分钟冷却）。
- **区域实体查询**：高级仓原来对全部区域求**一个** AABB 并集（两块区域相隔 1000 格 ⇒ 查询盒跨度 1000+ 格）；现在按跨度阈值（2×`Area.MAX_SIDE` = 256）与体积阈值做 O(n) 贪心聚类，每簇一盒、逐簇 `getEntitiesOfClass` + 去重；区域全相邻时退化为 1 簇 1 盒，与旧行为逐字一致。
- **装填器**：`updateChecklist` 从每 tick 20 包/秒降到只在蓝图变化与队列部署时；集群 BFS 从每 tick 十几次到 1 次。
- **GUI**：15 张烘焙背景 / 19 支 Menu / 9 个自绘点 / 85 条锚点由 `selfcheck_gui_slot_alignment.py`（135 断言 / 7 反例）钉住；长文本 tooltip 规则扫描 52 个文件 / 237 处自绘文本（tooltip 54 / 自动豁免 79 / 冻结 101 / 未覆盖 0，17 条反例）。
- **客户端启动探针本身也修了**：版本行正则原来写死 `beta.38`、渲染证据接受早期就出现的 `Backend library: LWJGL`（会在 mod 还没构造完时就把客户端杀掉）⇒ 正则改为 `1\.2\.0(?:-beta\.\d+)?`（发布 1.2.0 时又去掉一次写死 beta），渲染证据只认晚段标记 `Sound engine started` / `Created: …`（本轮实际日志 `Created: 2048x1024`）；本轮客户端真的走到标题界面才判 PASS。

**稳定性上的一个事实**：210 条自检里 0 失败是「每条断言都能被源码事实与反例证伪」，不等于「所有场景都在真实存档里跑过」—— 见「已知限制」。

---

## 配置项与指令变化

### 配置项

本版**新增 / 变更 / 删除**的项（只列 CHANGELOG 明确写了默认值或语义的）：

| 配置项 | 变化 | 说明 |
| --- | --- | --- |
| `electricJetpackLowPowerMinutes` | **新增**，默认 `30`，范围 `1..100000` | 低电阈值改为「剩余可飞分钟」；阈值电量 = 分钟 × 1200 × 每 tick 耗电，默认 30×1200×8 = 288,000 FE，严格小于才提示。旧 `electricJetpackLowPowerPercent` **删除** |
| `electricJetpackFlightCostPerTick` | **默认值 32 → 8**，范围 `1..Int.MAX` | 飞行耗电；配置未加载时的回退值同步为 8 |
| `feEnergyCubePushPerTick` | **新增**，默认 `10000` FE/t | 能量立方每 tick 向相邻六面主动推送的总预算（六面分摊；不推给相邻立方） |
| `energyAuditHighlightLingerTicks` | **新增**，默认 `1200` tick（60 秒） | 能耗监视高亮在关界面后继续存在的时间；到期自动灭，重开界面点「清除高亮」立刻撤 |
| `rangeChargerNetworkPullPerTick` | **新增**，默认 `100000` FE/t | 闸住范围充电器「从网络抽电」的速度（否则空缓存一 tick 就能抽走 5000 万） |
| `rangeChargerHighlightTargets` | **语义降级**，默认 `true` | 从「唯一的开关」降为**总闸**；每台充电器另有一个自己的 `highlightEnabled`（旧存档默认开），总闸关 ⇒ 一个包都不发 |
| `quantityKeeperDefaultTarget` | **语义变化**，默认 `64`，范围 `1..1000000000` | 物品默认目标仍是 64 个；**流体 / 气体默认目标 = 物品默认值 × 1000 = 64000 mB**。服务端 setter 上限统一为 1,000,000,000 |
| `quantityKeeperDestroyRatePerSpeed` | **删除** | 全工程没有读取点（只有定义 / 字段 / 赋值），且线性语义与统一后的 `base << power` 曲线冲突；任何人手改过都从未生效 |
| `sequenceStationDerivedCategories` | **新增**（默认按工位推导；设 `false` 恢复旧默认集） | 输出总线未手动配置过时，默认导出「它朝向的机器在本链里负责的步」所需类别 |
| `rangeChargerChargeRate` | **注释更新** | 本项不管立方；要调整体立方充电请改 `feEnergyCubeMaxTransfer` |

其余配置项（`devLogs`、`jadeBusTooltipMode`、`rangeChargerCuriosScanInterval`、`assemblySuspendOverflowReclaim` 等）在 1.2.0 里**没有改默认值**；`devLogs` 仍然默认关闭。

### 指令

- **没有新增指令**。根指令仍是 `/rs_create_compat`。
- `/rs_create_compat diag`：显式不需要权限，新增 **10 秒冷却**与回执轮转（冷却判据在打开日志开关**之前**）。
- `/rs_create_compat diag run`：仍然「先强制打开诊断日志、再导出快照」，但改为**作用域内临时开启**（`finally` 恢复）—— 不再像以前那样把会话的 `devLogs` 永久打开、导致「配置里关着却一直刷」。
- `devlogs on|off` 与别名 `assemblydebug` / `debug assembly`：不变，三种写法仍接同一个开关、即时生效、不需要重启。

---

## 升级注意

1. **保持器流体 / 气体默认目标的语义变了**：默认值现在是「物品默认值 × 1000」= **64000 mB（64 桶）**，物品仍是 64 个。**只作用于新标记**：目标还是旧的物品默认值（64）时第一次标流体 / 气体 ⇒ 换成 64000；改回物品标记且目标还是 64000 ⇒ 换回 64；玩家手动设过的值不动。**旧存档里已存的 64 mB 流体目标原样读回，不会自动改大** —— 想让旧机器吃新默认值，需要重新标一次标记。高级版按槽独立套用。
2. **保持器服务端上限**：手输 / 改包超过 1,000,000,000 会被夹到 1,000,000,000（只拦增长；旧档超限值读档原样保留）。这条不是玩法改动，是防「按 21 亿缺口向 RS 要自动合成」。
3. **保持器旁贴 RS 外部存储的玩家**：H2 修复后，那台外部存储**不再把保持器当存储源**。这是双计来源、属预期行为；Create 漏斗 / 管道 / 原版漏斗对保持器的读 / 抽 / 送入完全不变。
4. **中间产物缓存仓没电不再搬中间产物**：L2 之后「机器不上电不干活」；未激活时冷却不推进，重新上电第一 tick 就能搬。旧存档内容一件不丢。
5. **序列装配输出总线的默认导出类别会变**：对**从未手动勾选过**（`categories_explicit == false`）的总线，默认只导出「它朝向的机器在本链里负责的步」所需的输入 / 过渡件 / 流体。想回到旧的「全输入 + 全中间产物」，把 `Config.sequenceStationDerivedCategories` 设为 `false`。判不出（空工位 / 非标准几何 / 第三方装配步）⇒ 逐字退回旧默认集，绝不因为推导而少喂。
6. **范围充电器缓存 5000 万 FE 是一次性迁移**：值恰好等于历史默认值（100 万 / 1000 万）时改为 5000 万并打日志；玩家自己调过的一律不动。配方中心格由铁块改为 FE 能量立方。
7. **电动飞行背包**：旧配置 `electricJetpackLowPowerPercent` 删除，改用 `electricJetpackLowPowerMinutes`；耗电默认 32 → 8 FE/t。阈值语义从「剩余百分比」变成「剩余可飞分钟」—— 同样 200 万 FE 从「一直提示低电」变成「剩余约 208 分钟，不提示」。
8. **FE 能量立方**：本版把语义定成「传递介质 / 缓存」—— 每 tick 向相邻六面推电、外部注入 / 抽取永不被控制器门控拦截、网络池里严格最后；只有「从 RS 网络能量池抽电」受控制器阈值门控。物品形态能显示 / 接受电量，但**整叠不能充放电** —— 先拆成单块，否则拆堆 / 放置会放大电量（现在能力双出口与双读写口都拒绝）。
9. **区域选定器 / 高级归流缓存仓**：旧档 `AreaFilters`（按序号）自动迁移成几何键，不需要手动操作；调小区域上限后，超限区域不再在下次点击时被写死丢弃（标红「不参与扫描，配置调回大值即恢复」）。区域过滤标记现在存在选定器物品上，**换机器 / 拆走选定器都不再丢**。
10. **归流缓存仓旧档「凑够 N 件才吸」**：载入时统一归一为 1（有就收），会记一条 INFO；新标记保留玩家显式设的数量。
11. **旧 beta 的降解室发电机（256 格）**：读档自动迁移到 16 输入 + 4 副产物，可降解物优先入输入格、余量入副产物格、再放不下在首个服务端 tick 掉到方块位置；旧进度（256 格坐标）作废但物品不丢。**从 1.1.0 直接升上来的玩家没有这台机器，不受影响。**
12. **诊断日志默认关闭**（沿用 1.0.0 起的默认）；`/rs_create_compat diag run` 现在只临时开，不会再把会话说成「日志一直开」。
13. **升级后建议重启一次游戏**：`[rscc-build]` 只在启动时打一次，重启后日志才能自证版本，也才能拿到有效的自检判定。

---

## 已知限制 / 未做事项

**功能上明确未做**

- **「高级远程多功能终端偶尔消失的『选择』按钮」仍未定位**。用户答复「整块都没有了、点不到、不稳定出现」，但源码级排查已排除「按钮被条件跳过」；`SequencePatternTerminalScreen` 在该区域只有一个每帧无条件创建的按钮（生成样板），无法解释「整块消失」。需要下次复现时的一张**现场截图或界面名**才能继续。
- **区域过滤器仍只支持物品标记**：高级版 7 参覆写里 `fluid` 分支仍按旧口径交回基类（写全局匹配区）；「把流体拖到高级版 48 格」的可见性问题是另一条产品决策，本版没有扩大这个边界。
- **面输出模式（FACE）仍未设置与总线输出模式（BUS）同等的多重闸门**：起步原料在「已经被机器转成过渡件」时可能被再次投放，产生多开一件在制件（表现为多余的中间产物）。实机验证走的是总线输出模式；切面输出前请先评估。这条从 1.0.0 起就写在已知限制里，1.2.0 未改变。
- **气体暂未纳入「阻塞」判定**：归流缓存仓的阻塞能力只覆盖物品与流体；气体在 RS 2.0 的资源体系里没有独立资源类型，相关入口目前是预留状态。
- **终端的「类别详细配置」还缺一个输入框**（上游待办，用户尚未明确用途）。
- **终端界面的候选缓存只写不清**：若首帧在配方 / 槽位未就绪时算出空表，会以空结果缓存整局；当前有「样板落盘候选」兜底，影响已被削弱，但仍记为健壮性隐患。
- **精妙背包（Sophisticated Backpacks）的快捷键只认玩家背包栏**：反编译证实它客户端硬判 `slot.container instanceof Inventory`、服务端只解析 main / offhand / armor，结构上不支持打开「放在容器槽里的背包」；在本轮「不改别人模组、不反射私有字段、不加编译依赖」的约束下无法功能修复，只在代理槽物品 tooltip 常显说明。
- **接收器作为 inventory 提供者不设方向限制的代价**：如果玩家主动把 RS 外部存储朝向接收器，终端可能看见这份缓存的数量但永远取不走（视图只读、`extract` 恒空）；不会数量翻倍（接收器不是 `StorageProvider`），也不会投递死循环。
- **能耗监视只列终端绑定的那一张网络**（RS 的一张 `Network` 只含同维度方块；跨维度是两张网络互联），主数字 = `isActive() ? 标称功耗 : 0`，待机行标灰。
- **电动飞行背包穿上后玩家身上没有模型**（不是 `ArmorItem`、无护甲值），只有物品栏图标与 HUD；「范围内」几何判定只扫周围 4 区块（64 格）已加载区块，超出范围的充电器可能漏报「范围内」（「充电中」不受影响）。
- **执行仓上游缓冲的代价**：每轮 +1 tick 延迟；极端配方单次迭代超缓冲上限 ⇒ 任务停滞但**不丢料**（宁停不丢）。
- **范围充电器无限范围（创造升级）**：分片后单目标 FE/t 约为原来的 1/5（创造模式专用，非生存物品）。
- **界面文本「完整显示」只做了静态几何 + 字宽模型验证**：英文最长的那几行会缩到 **0.60~0.75 倍**（约 6~7px 字高），能不能看清要进游戏确认；第三方超长名字在 0.6 倍仍放不下时会完整画出但可能越过可用带（按「宁可不省字」口径），完整原文在对应 tooltip 里。
- **线缆搜链「未穷尽」不等于「没问题」**、**结构缓存失效源是粗粒度的**、**剪贴板整簇粘贴上限 64 台**、**Jade 提示每类最多三件**、**饰品槽充电默认 5 tick 节流** —— 这几条都是 1.1.0 已写明的刻意取舍，1.2.0 未改变，详见 README「已知限制」。
- **`1.1.0` 的大部分修复与 `1.2.0` 的相当一部分修复，凭据是源码交叉验证 + 行为模型 + 反例自证，不是实机验证。** 下面单独列「没有实机复现」的部分。

**没有实机存档复现的部分（如实写明）**

- **保持器 H2 的「外部存储贴保持器 ⇒ 存量虚高」**：结论来自 RS 2.0.9 源码调用链 + 双计模型 + 反例自证，没有真的摆一台外部存储读虚高数值；`KeeperCapabilityVisibility` 的调用栈判据是**启发式**（只认 `com.refinedmods.refinedstorage` 包前缀，未来 RS 换包名 / 换调用路径会让闸门失效；JVM 探针只能证明「前缀命中 ⇒ 隐藏」这条机制）。
- **保持器 M2 的「拖岩浆看默认 64000」**：没有开界面点过；结论来自源码路径 + 6 个形态切换用例 + 反例自证。
- **「350 ms/tick 来自区块同步等待」**：来自源码语义 + 崩溃数字 + 日志时间线推断，本机没有该存档实机复现。
- **单元样板一叠丢张、区域跨未加载区块、5 块无限盘、带组件流体取不出、>288 条合并、48 格 JEI 拖入、区块安全读的现场**：都只有源码 + 行为模型 / 反例自证，没有真实存档复现。
- **大量新界面 / 贴图 / 观感**：区域条 176 宽的观感、标记网格的 JEI 端到端、6 张新背景（176×222 两张序列界面的背包 / 快捷栏与槽框是否严丝合缝）、降解发电机的 JEI 页与点箭头跳转、请求器工具条在 GUI 缩放 ≥4 时的位置 —— 只有离屏 mock / 像素与几何断言 / 源码级矩形断言，没有实机截图复核。
- **专用服务端 / 客户端「真启动」是实机级证据**（headless 真启动、真客户端走到标题界面、`run/` 只读自证、0 crash-report），但它只证明「起得来、加载得动」，不证明「进游戏操作一切正常」。
- **`/reload` 热重载、农夫乐事标签 / id、满仓 256×64 的存档体积**：开发环境没装农夫乐事，按源码核实未实测；`/reload` 未实机验证。
- **多人 / 高延迟场景**：执行仓汇总窗口的 4 tick 安全窗在单程延迟 > 2 tick（RTT > 200 ms）时仍可能有残余窗口；多人同时编辑同一台机器的区域列表未测。

---

## 反馈渠道

遇到问题请到 GitHub Issues 反馈：

<https://github.com/CookieWYQ/refined_storage_and_create_compat/issues>

报问题前请先做三件事：

1. 敲一次 **`/rs_create_compat devlogs on`**（本版默认关闭），让日志里有可用的诊断行；
2. 尽量附上 **`/rs_create_compat diag`** 导出的 `run/rscc_diag/<时间戳>/snapshot.json`（同时会覆盖 `run/rscc_diag/latest/snapshot.json`）与 `logs/latest.log`；
3. **把启动日志里的 `[rscc-build]` 那一行一并贴上** —— 它包含版本、git revision、编译时间、源码数、Minecraft / NeoForge / Java，是判断「你手里这份是哪个构建」的唯一凭据。

如果问题与具体机器有关，请在描述里写清方块坐标、朝向、总线 / 链的布局，以及你是在「总线输出」还是「面输出」模式下遇到的（后者有已知限制）。带 `[rscc-*]` 前缀的日志行（`[rscc-build]` / `[rscc-diag]` / `[rscc-ledger]` / `[rscc-proxy]` / `[rscc-receiver-input]` / `[rscc-chain]`）比截图更有用。

---

## English Summary

`rs_create_compat` `1.2.0` folds the whole 1.2.0 beta cycle (`1.2.0-beta.1` … `1.2.0-beta.43`) into one stable version: a set of new machines and screens, plus a long list of crash, data-loss and silent-failure fixes found through in-game reports, targeted self-checks and independent read-only reviews.

**Upgrading** — no world rebuild, no re-placing machines, no pattern regeneration is required when coming from `1.1.0`. `1.2.0` is a minor version (backward compatible by the project's versioning rules), no entry in the changelog changes the pattern NBT format, and every old-save migration is automatic and loss-preserving. The only thing to do actively: diagnostic logs are off by default — run `/rs_create_compat devlogs on` before capturing one.

**Upgrade caveats** (details in the Chinese "升级注意" section)

- The keeper's fluid / gas default target is now 64,000 mB; values already stored in old saves stay at 64 mB until the marker is set again.
- Keeper targets are server-capped at 1,000,000,000; the read path does not clamp old values.
- Never-configured exporters now derive their category set from the station; `sequenceStationDerivedCategories = false` restores the old set.
- A range-charger cache equal to the historical 1M / 10M default is migrated once to 50M.
- `electricJetpackLowPowerPercent` is replaced by `electricJetpackLowPowerMinutes`; FE cubes can no longer be charged as a stack.
- The old 256-slot Decomposition Generator is migrated automatically (progress reset, items preserved).

**New capabilities**

- **Area Selector + always-on area strip**: areas live on the item's components (geometry-keyed, dimension-bound); the strip sits at the GUI's right edge (176 px, adaptive down to 72) with row selection, in-place rename / six-coordinate edit, delete with a 3-second confirm, search over names and coordinates, and an independent marks screen with JEI drag-in. Per-area marks now travel with the selector instead of being stored in the block.
- **Advanced Collection Cache**: 512 slots / 1,024,000 mB fluid, absorbing only inside selector areas; a server-confirmed area list with search and delete confirmation; an honest status band.
- **Container Proxy**: exposes a shulker box / backpack / RS disk as an adjacent container; primary/backup contents faces (backup is input-only, so two external storages never double-count), a unique proxy-object face, bundle support, and an always-on tooltip for the Sophisticated Backpacks hotkey limitation.
- **Network Resource Receiver / Requester**: a named drop-off with a 1728-item / 2000 mB cache, direction-agnostic inventory exposure and a writable view for chutes; the receiver GUI is a paged resource list (12 rows per page) with item/fluid take-out. The requester delivers a whole order across ticks ("batches per tick"), with a grid + order-list GUI, search, delete, amount stepping, a delivered/left/short progress line and a power gate.
- **FE Energy Cube**: 50M FE per block, six-face capacity sharing, per-block accounting, lowest-priority network provider, active push into neighbours (10,000 FE/t default), chargeable item form with a stack guard.
- **Electric Jetpack**: 50M FE, 8 FE/t (down from 32), low-power threshold now expressed as remaining flight minutes (`electricJetpackLowPowerMinutes`, default 30 → 288,000 FE), exact grouped readouts, creative-mode equip now moves instead of duplicating.
- **Decomposition Generator**: 16 inputs / 4 by-products / 6 upgrade slots, datapack-driven table, four balance tiers, a full JEI category ("Decomposition Power") with arrow navigation, and automatic migration from the old 256-slot layout.
- **Energy Usage (7th terminal mode)**: aggregated per-block-type draw, expandable coordinates, one-second refresh, orange outline highlight that now lingers 60 s after closing the GUI.
- **Autocrafter x Create Mechanical Crafter / Mixer**: grid-position dispatch for `create:mechanical_crafting`, multi-item/multi-fluid dispatch for `create:mixing`, pattern format unchanged.
- **Range Charger filter**: four tabs (drops / carried items / players / world blocks), pinyin search, persisted player list, hard caps (1024 items / 256 names / 256 UUIDs).
- **UI/text**: no ellipsis anywhere in the client (100 source files, zero offenders); six GUI backgrounds redrawn with the project's generator; all pure-FE bars unified on RS's own widget; range-box and highlight lifetimes fixed so they survive closing the GUI.

**Severe fixes**

- **Server watchdog kill / sustained slow ticks from the Energy Cube cluster**: `Level#isLoaded` returns true for chunks still in the loading pipeline, and `getBlockState` then synchronously waits for generation. Measured: 70.04 s wall clock advanced only 10.03 s of scheduled time (about 200 ticks, about 350 ms/tick). Fixed with `getChunkNow` + `CHECK` block-entity reads, `partial` snapshots, an 8-rebuilds-per-tick budget and a 20 ms WARN; the same pattern was then closed project-wide (20+ sites, `getChunkAt(` reduced to zero).
- **Client crashes**: Decomposition Generator shift-click (`Index 91` of 91), Advanced Collection Cache menu-type mismatch (139 slots vs 138 -> disconnect), Collection Cache shift-click infinite QUICK_MOVE loop (integrated freeze / server watchdog), Container Proxy `ClassCastException` swallowing every click, and a dev-client assertion crash on missing Jade config translations (caught by the new client boot probe on its first run).
- **Data loss / configuration loss**: a stack of unit patterns silently losing N-1 items; a schematic loader silently dropping a blueprint; the keeper's target truncated by a network `short` above 32,767 (33 buckets displayed 0 and destroyed against a wrong target); component-carrying fluids that could never be drained; marker merges silently truncated above 288; area marks lost on REPLACE / legacy migration / area-limit lowering; JEI drag onto an occupied 48-slot cell ignoring the new id; RS voiding the second half of a batch (now an upstream buffer with `EXECUTE` always accepted, at the cost of +1 tick); the precision-mechanism deadlock (5.4-day-old bare items, `capacity = remaining 2 - inFlight 2 = 0`) now reclaimed through a conservative 60-second path; on-demand replenishment (`ceil(R/p)` no longer opens extra starting ingredients; a 20-item order opens exactly 20).
- **Silent failures / lying UI**: the keeper exposing its capability to RS external storage (double counting and recursion) now hidden from RS only while still visible to third-party logistics; unmarked keepers claiming resources; unpowered intermediate caches migrating stock; the energy cube's int saturation judging "full" with 2,516,352 FE left; the Import Bus's filter/fuzzy buttons deleted by a mixin; the Energy Audit screen blanked by an opaque panel; highlight/range boxes vanishing when the GUI closes; diagnostic logs that could not be turned off; exporter logs claiming `no_chamber` with `reachable=1`; the conservation ledger booking a permanent +1; advancements awarded to everyone nearby; the disk proxy's wall-clock TTL; a protocol channel that could never be sent.

**Performance / verification** — the last beta's acceptance: **210 self-check scripts / 0 failures**, independent `javac` `sources=528`, dedicated server `Done (1.807s)!`, client reaching `Sound engine started` with 0 crash reports, and identical sha256 in all four deployment locations. Notable performance work: chunk-budgeted range-charger scanning (128 finite / 256 infinite chunks per tick; 1,089 chunks about 5 ticks for one player, about 43 for ten, replacing "10,000 chunks per tick"), bounded cube-cluster rebuilds, one BFS per tick in the loader, `updateChecklist` no longer sending 20 packets/s while idle, and per-cluster entity query boxes.

**Config / commands** — no new commands; `diag` gained a 10-second cooldown and now restores the log switch after `diag run`. Config changes include `electricJetpackLowPowerMinutes` (new, old `electricJetpackLowPowerPercent` removed), jetpack cost 32 -> 8 FE/t, `feEnergyCubePushPerTick`, `energyAuditHighlightLingerTicks`, `rangeChargerNetworkPullPerTick`, per-charger highlight switches under a master gate, keeper fluid defaults of 64,000 mB and a 1,000,000,000 server cap, `sequenceStationDerivedCategories`, and the removal of the never-read `quantityKeeperDestroyRatePerSpeed`.

**Known limitations** — the occasionally missing "Select" button on the advanced remote terminal still lacks a screenshot; area filters are item-only; `FACE` output mode still lacks the `BUS` path's gates; gases are not part of blocking; the terminal candidate cache is write-only; the Sophisticated Backpacks hotkey limitation is structural; many GUI/visual conclusions and the keeper H2/M2 findings are self-check / source-level, not live-save reproductions; and a large share of the fixes are backed by source cross-checks and behaviour models rather than in-game replication.

**Feedback** — GitHub Issues; run `/rs_create_compat devlogs on` first, attach `/rs_create_compat diag` output and `logs/latest.log`, and paste the `[rscc-build]` line (the only reliable way to identify the build).
