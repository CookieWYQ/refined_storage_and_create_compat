# 第 96 轮：用户反馈 —— 分派、判定口径与「什么算修好了」

> 本文是本轮**唯一任务清单**。每条抄了用户原话（含口误，别改写原意），
> 并写清「Lead 拍板的口径」与「交给谁」。动代码前先读分配给你的那一节；
> 不确定就去 TEAM 消息里问 Lead，**不要自己改口径**。

## 0. 本轮基线

- 用户实机版本：`rs_create_compat-1.2.0-beta.3.jar`（构建于 2026-10-25T09:21:25，`sources=424`）。
- 反馈时间：2026-10-25 12:08（交付后约 2h47m）。
- 本轮目标版本：`1.2.0-beta.4`。
- 上一轮（round95）的 15 条已经全部落地；本轮是**新一轮实测反馈**，不是 round95 的返工清单。

## 1. 用户原话与分派

| # | 用户原话（节选） | Lead 拍板的口径 | 负责 |
|---|---|---|---|
| 1 | 「首先就归流缓存期现在还是无法与任何的物品」 | **真 bug，必须定位到具体路径**。round95 刚大改过「缓存 → 网络」的回写（轮转游标 + 失败不扣预算 + 流体独立预算）与 Shift=写标记，用户说「还是」不行 ⇒ 要逐条证伪：①界面放入/取出（普通左键拖入、Shift 现在写标记、缓存窗口 `mayPlace`）②世界吸收（掉落物 / 容器 / 流体 / 经验）③回写网络（`flushCacheToNetwork` / `flushFluidCacheToNetwork`，含 `flushCursor` 推进、`isBlockedStack`、`shouldDestroyUnmatched`、`collectAll`/`invertMatch` 与标记的交互、机器集群路径）④手动放置路径到底还剩哪一条。**基础版与高级版都要查**。修完必须让「哪条路径坏了」在日志里**一次就能看出来**（一条 `[rscc-collect]` 诊断，状态变化才记）。 | WS-A |
| 2 | 「那个标记范围的那个物品他的去重，比如说标记两个一样的地方它还是会这样子显示，并且还显示的那个坐标因为太长了然后被挡住看不清，然后呢就是那个界面还是有点丑而且不方便编辑」 | 「标记范围的物品」= **区域选定器**（`item/AreaSelectorItem` + `AreaSelectorScreen`，用于高级归流缓存仓的选定区域）。三件事：①**去重**：同维度、同坐标、同名字（名字不参与判等）的完全相同的区域，创建时就拒绝并明确提示「已存在相同区域」，世界描边也只画一份，不叠着画；②**坐标不再被挡住**：列表行坐标必须完整可读（单行放不下就两行 / 紧凑格式 / 悬停显示完整），禁止把坐标截成 `12,64,3 → 20,70,…` 这种看不出终点；③**界面可编辑**：点行即选中并回填字段、可复制/删除/重命名、显示体积与维度、滚动条、选中高亮、错误原因就在输入框旁边。 | WS-A |
| 3 | 「代理器如果接上两个外部总线你说的会翻倍，是说会有出现复制功能还是怎么样子的，你能不能尽可能的把它解决」 | **先回答**：不是复制物品（真实存储只有一份，抽不出来的量就是抽不出来），是**同一份库存被两个 RS 外部存储各算一遍 ⇒ 终端显示数量翻倍**。**解决**：`ProxyFaceRole.CONTENTS` 拆成「主内容物面（完整读写）」与「备内容物面（仅接受输入：`getSlots=1`、`insertItem` 真实写入底层、但 `getStackInSlot` 恒空、`extractItem` 恒空）」——RS 外部存储看不到备面（读到空库存 ⇒ 不参与求和），漏斗/溜槽仍能往备面推。第一个内容物面自动成为主面；界面逐面标注「内容物（主）/ 内容物（备·仅进）」并用**一行**说明为什么；老存档有多个内容物面时读档归一（`Direction` ordinal 最小者为主）。 | WS-B |
| 4 | 「网络资源接收器还是不能跟溜槽之类的进行交互」 | **接收器要能被外部"推入"**：现在它对外只暴露**只读**视图（`ViewOnlyHandlers` 的 `insert`/`fill` 一律退回），所以溜槽 / 漏斗 / 管道 / 任何相邻容器都**写不进来**。改成对外暴露**可写**视图（物品 + 流体），唯一写入口 = 缓存（与请求器投递同一个 `insertIntoCache`/`fillCache` 入口，走容量上限与守恒），`doWork()` 朝 `FACING` 输出到溜槽那条路也要用真机证据核一遍（`ChuteItemHandler` 的 `canAcceptItem`/simulate 语义、`RsccChamberImportStrategy.itemHandlerAt` 的 side 查询）。 | WS-C |
| 5 | 「现在其实那两个界面我还是不满意……发送器他就不要用那种额外的东西，就是网格，然后打开网格之后旁边就有一个清单，会显示要发送什么，然后可以方便地搜索已经加了什么，然后可以删除，然后可以更改数量」 | 「发送器」= 网络资源请求器。**主界面改成网格 + 清单**：右键请求器**直接打开网格界面**（服务端 `openMenu` 打开扩展后的 `ResourcePickerMenu`）；左侧 RS 网格（搜索 / 点一下加入清单），右侧**订单清单**：每行 图标+名称+数量，**清单自己能搜索**（只过滤已加入的条目）、单行删除（×）、单行改数量（± / 输入框）、滚动，条目数**无上限**（BE 里本来就是 `List`，现在的界面只有 8 格是人为限制）。顶部保留：目标接收器 / 立即发送 / 清空订单 / 节拍信息；一个小按钮进「设置」（原请求器小界面）。 | WS-D |
| 6 | 「接收器不仅它不能交互，而且你现在那个它那个内容是可以直接打开取出了，但是你那个流体该怎么办呢？而且你这个格子这么少，因为发送的物品和加东西……资源是没有上限的，你这么一点怎么装得完」 | 接收器的缓存是 `RsccUnboundedItemStorage` / `RsccUnboundedFluidStorage`（**种类无上限**），但界面只有 16 个镜像槽 + 4 个流体条目 ⇒ **第 17 种物品、第 5 种流体在界面里根本看不见也取不出**（这是真缺陷，不是审美问题）。改成**可滚动的资源列表**（物品与流体混合或分页，每行：图标 + 名称 + 数量 + 取出入口），覆盖全部种类；流体必须能取出（**1000 mB/桶**：背包里有空桶就换一桶、没有就不扣并提示；Shift = 尽可能多的整桶，剩余继续留在缓存）。列表分页同步（不要一口气把上千种塞进一个包）。 | WS-C |

## 2. 全员硬约束（沿用前几轮，违规会被 Lead 打回）

1. 只改自己写域内的文件。`Config.java`、`RS_Create_Compat.java`、`client/ClientInit.java`、`src/main/resources/**`、
   `lang/*.json`、`CHANGELOG.md`、`TECHNICAL_HANDOFF.md`、`client/jade/RsccJadePlugin.java` 归 Lead；
   需要改就把**精确的键名 / 注册行 / 插入位置**列给 Lead（zh/en 原文一起给）。
2. 编译只跑 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/_lead_compile.ps1`，看到 `LEAD_COMPILE_OK sources=N`。
   **不跑** `gradlew build`、**不跑** `tools/verify_dedicated_server.ps1`（有 PID 锁，Lead 串行跑）、**绝不用** `tools/manual_compile.ps1`。
   若报「argfile 截断 / 找不到源」类怪错，是与别人并发写 `build/lead_javac_args.txt` 所致，隔 30 秒重试一次。
3. 每个工作流新增 `tools/selfcheck_round96_<名字>.py`，里面必须有**反例自证**（把修复回退 / 把判据写错时自检必须判负），
   回报里写断言数。不许为了变绿删/放宽既有自检。
4. 客户端专有类型只许出现在 `client/` 包；`network/**` 不许引用本模组 `client/**`；mixin 注册到正确数组；专服安全。
5. 不新增 GUI PNG（工程硬规则：用 `McGui` 的原版精灵）。
6. 任何「本批未实现 / 后续批次 / 恒为 1 / 按住 Shift 查看」这类**与现状不符**的注释必须同步改掉。
7. 不 git add/commit/push；不碰 `.sftp*` / `sftp_*.py` / `diag/` / `assets/` / `__pycache__`；
   **永远不把自检、绘图、SFTP 相关资产上传到任何地方**。
8. 回报模板：①缺陷清单（file:line / 为什么错 / 怎么改 / 证据）②新增自检与断言数 ③编译结果最后一行原文
   ④仍需 Lead 代改的条目（语言键 zh/en 原文 / 注册行 / 资源）⑤**没验证到的部分要诚实写**。

## 3. 界面设计口径（本轮两个界面重做，必须照这个来）

- 不许再出现「只有 8 / 16 个格子、多余内容看不见」的固定格子列表；凡是「资源种类」都按**可滚动列表**做。
- 文本一律走工程既有的 `trim(text, maxWidth)` / `font.width` 量宽后再画，**不许写死像素宽度去硬塞长字符串**；
  放不下的完整文本必须能用 tooltip 看到。
- 所有容器界面必须有手动 tooltip 绘制（自检 round95 会扫），新控件要有 hover 提示。
- 点击语义要一致：左键 = 主操作（取一组 / 加入清单 / 选中），Shift+左键 = 全部（或说明的另一档），
  滚轮 = 列表滚动；**任何"点了没反应"的路径都必须给一句 actionbar 或界面消息**。
- 数值一律走既有 `RsccRateLimiter` / `RsccSpeedStackUpgradeContainer`，不许自己写 `<<`/`*` 公式。

## 4. 验证顺序（Lead 串行执行）

1. 四个工作流全部回报后：`tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=N`
2. 全量自检（`tools/selfcheck_*.py` 全跑，含新增 round96_*），失败数必须 0
3. `tools/verify_mixin_shadows.py`（两遍：CLIENT / DEDICATED_SERVER）
4. `tools/verify_dedicated_server.ps1`（PID 锁，串行）
5. `gradlew.bat build --offline --no-watch-fs -q` → `build/libs/rs_create_compat-1.2.0-beta.4.jar`
6. 只读交叉复核（独立 agent）→ Lead 亲自复核关键 diff → 交付
