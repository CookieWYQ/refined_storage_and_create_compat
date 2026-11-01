# 第 95 轮：用户反馈 15 条 —— 分派、判定口径与「什么算修好了」

> 本文是这一轮的**唯一任务清单**。每条都抄了用户原话（含口误，别改写原意），
> 并写清「我（Lead）拍板的口径」与「交给谁」。动代码前先读这一节，
> 不确定就去 `TEAM` 消息里问 Lead，**不要自己改口径**。

## 0. 本轮基线

- 用户实机版本：`rs_create_compat-1.2.0-beta.1.jar`（服务器 `/mods` 里就是它，构建于 2026-10-24T11:18:36，
  `sources=420`）。用户的所有抱怨都针对这个 jar。
- 证据：`diag/pull_2310/latest.log`（服务器 20:53–23:10 会话）、`diag/pull_2310/snapshot.json`（23:07:49 导出）、
  `diag/pull_2310/crash-2051.txt`（20:51 的 ServerHangWatchdog，`60000004 秒` = 宿主时钟跳变，**不是**本模组代码路径）。
- 本轮目标版本：`1.2.0-beta.3`（Lead 改 `gradle.properties` 与 CHANGELOG）。

## 1. 用户原话与分派

| # | 用户原话（节选） | 口径（Lead 拍板） | 负责 |
|---|---|---|---|
| 1 | 「那既然这玩意是默认存在的还是把它搞成可配置的吧」 | 网格搜索裸文本搜 tooltip 加配置开关 `gridSearchMatchesTooltip`（默认 **true**），唯一读点在 `RsccGridTooltipSearch#matches` 入口；`textMatches` 保持纯函数。**Lead 已改完**（Config + selfcheck round94 已同步） | Lead ✅ |
| 2 | 「资源接收和资源发送他们的界面并没有发生多大的改变……这个设计非常不好」 | 接收器 = `network_resource_receiver`，发送 = `network_resource_requester`。两个界面都必须真正**显示内容**：接收器显示缓存内容并可取出；请求器显示目标接收器 / 订单内容 / 已投·剩余·缺口 / 节拍。不是「状态行 + 升级槽」就交差 | WS-A |
| 3 | 「貌似它这个还是不能正常提取里面的物品和资源」 | 接收器缓存里的物品/流体必须能从界面取出（左键取一组 / Shift 取全部，取不走的留在缓存并给提示）；取出必须走缓存真实扣减，不许复制 | WS-A |
| 4 | 「所有这种……让 Jade 显示里面有什么东西的这些方块，你的作用只是把这一个接口暴露出去，并不需要你自己写……按下 Shift 显示详情……这玩意 Jade 已经帮你实现了」 | 判定顺序：①方块暴露 `Capabilities.ItemHandler.BLOCK` / `FluidHandler.BLOCK`（已经全部暴露，逐个复核）；②Jade 的 Universal 插件自己会显示容器内容 —— 因此**删掉**自定义 provider 里自己写的「按住 Shift 才显示明细」那套；③只有 Jade 结构上做不到的东西（例如多资源类型混合、服务端才有的缓存）才允许保留一个**只加文本、不自己判按键**的 provider，并在注释里写清「为什么 Jade 自己不够」 | WS-A/B/C + Lead 复核 |
| 5 | 「那个显示范围的不管是哪一个……充电器还是什么的它都没有正常的工作」 | 范围显示 = 充电器界面「显示范围」按钮 + Create `Outliner` 的橙色框。要查清为什么玩家看不到（事件没挂 / 包没到 / 画在 (0,0,0) / TTL 太短 / 被 `removed()` 立刻清掉），修到「点一下就能看到框，5 秒后淡出」 | WS-D |
| 6 | 「充电器再加一个额外功能……充电对象高亮……方块加渲染框……掉落物发光……玩家身上的东西正在充电就给玩家发光……可以在配置中开关……关闭的话简单提醒用户一下」 | 新增 `rangeChargerHighlightTargets`（默认 **true**）。服务端只把「本 tick 真充了谁」同步给附近玩家；客户端：方块 = 高亮框、掉落物 = 发光、玩家 = 发光。关闭时服务端一个包都不发；玩家打开界面时给**一句**（actionbar / 界面一行）「该功能已在配置中关闭」 | WS-D |
| 7 | 「增加一个新的自检文件检测所有界面的 tooltip 是否手动进行绘制」 | 新自检扫 `client/screen/*.java` 全部 `AbstractContainerScreen` 子类：有槽位却没有可达的 `renderTooltip` / `renderComponentTooltip` 调用即判负。已有反例自证（删掉某界面的调用必须判负） | Lead |
| 8 | 「这一个 tooltip 它 MC 是不会自动绘制的……容器代理器其实没有绘制的」 | `InventoryProxyScreen` 确实一条都没画（唯一一个 0 命中的界面）。修好并纳入 #7 的自检 | WS-B |
| 9 | 「为什么我收纳袋放不进去收纳袋，应该也是可以这样子进行读取操作」 | 按「**容器代理器要能读写 `minecraft:bundle`（收纳袋）的组件式容器**」实现：`BundleContents` 不是 `IItemHandler`，所以现在代理器把它当成「没有内容物的物品」。用组件读写包一层只读+可写 handler。**不承诺**「收纳袋套收纳袋」是原版允许的；如果原版 `BundleContents` 拒绝嵌套，就照实说，并把「能读能取」做出来 | WS-B |
| 10 | 「容器代理器你这里这个提示……还是这么长一坨……你就把它去除不行吗」 | 删掉容器代理器物品的常显 help 长文（`block.rs_create_compat.inventory_proxy.help` 不再作为 BaseBlockItem 的 help）；界面里那条「不双计」长警告压成**一行短句**（信息不丢，但不再糊满屏） | WS-B（代码） + Lead（注册处传参 / lang） |
| 11 | 「把背包放在代理槽这里……无法通过快捷键打开……放在背包那些槽位里就会正常打开」 | 根因去 Sophisticated Backpacks 的按键处理里找（jar 在 `D:\minecraft\.minecraft\versions\<pack>\mods`，用 glob 找）。修到「鼠标悬停代理对象槽 + 按背包快捷键 = 打开背包」；**不改**别人模组，不反射私有字段（必要时用它的公开 API） | WS-B |
| 12 | 「归流缓存器潜在存在严重问题部分情况下无法正常的吸收物品……包括高级和普通的」 | 真 bug。要找出「什么条件下收不进网络」：能量 / 阻塞名单 / 缓存满 / 节流预算 / 区域模式 / 物流输入 / 网络节点失效 / `insert` 返回 0 被当成失败等等。修完要在自检里给出「该条件的反例会被抓住」 | WS-C |
| 13 | 「更改一个默认行为……他在处理 shift 加单击转移物品操作时……默认操作应该是标记」 | 归流缓存仓（基础 + 高级）界面里，Shift+左键从玩家背包点到缓存 = **写标记**（ghost），不是把物品塞进去。要塞物品走普通左键（或明确说明的另一种手势）。改完更新界面提示语言键 | WS-C |
| 14 | 「连锁总线莫名其妙……右键应用能正常……左键不行……为什么不能把左键搞好」 | 左键粘贴必须能用（这是文档里的原始手势）。查：客户端取消事件是否把 `START_DESTROY_BLOCK` 包吞了 / 副手剪贴板没认 / 别的 handler 先取消 / FTB 连锁键状态。修好并加一条诊断日志（`[rscc-chain]` 里区分「客户端没发」「服务端没收到」「收到但没载荷」） | WS-E |
| 15 | 「序列装配还存在一些瑕疵（日志已导出）」 | 日志实证：①`exporter@(412,70,87) linked=- reason=no_chamber reachable=1`（可达却判无归属）；②`[rscc-ledger] chamber@(419,100,98) unbalanced (sustained 3 samples) peak=1 on item:create:incomplete_precision_mechanism` 每 60s 重复；③`importer ... result=RECLAIM_ONLY reason=no_player_order_scrap_reclaim` 是否符合设计。逐条给结论：是真 bug 就修，是设计就写清楚 | WS-E |

## 2. 全员硬约束（沿用前几轮，违规会被 Lead 打回）

1. 只改自己写域内的文件；`Config.java`、`RS_Create_Compat.java`、`src/main/resources/**`、`lang/*.json`、`CHANGELOG.md`、
   `TECHNICAL_HANDOFF.md`、`client/jade/RsccJadePlugin.java` 归 Lead（需要改就列出来发 Lead，别自己动）。
2. 编译只跑 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/_lead_compile.ps1`，看到 `LEAD_COMPILE_OK sources=N`。
   **不跑** `gradlew build`、**不跑** `tools/verify_dedicated_server.ps1`（有 PID 锁，Lead 串行跑）、**绝不用** `tools/manual_compile.ps1`。
3. 不自建自检来「凑绿」：每个工作流新增 `tools/selfcheck_round95_<名字>.py`，里面必须有**反例自证**
   （把修复回退 / 把判据写错时自检必须判负），并在回报里写断言数。
4. 客户端专有类型只许出现在 `client/` 包；mixin 注册到正确数组（`client` / `common`）；专服安全。
5. 不新增 GUI PNG（工程硬规则：用 `McGui` 的原版精灵）。
6. 任何「本批未实现 / 后续批次 / 恒为 1 / 按住 Shift 查看」这类**与现状不符**的注释必须同步改掉 —— 用户最反感这一类。
7. 不 git add/commit/push；不碰 `.sftp*` / `sftp_*.py` / `diag/` / `assets/` / `__pycache__`；
   **永远不把自检、绘图、SFTP 相关资产上传到任何地方**。
8. 回报模板：①缺陷清单（file:line / 为什么错 / 怎么改 / 证据）②新增自检与断言数 ③编译结果最后一行原文
   ④仍需 Lead 代改的条目（语言键 / 注册 / 资源）⑤**没验证到的部分要诚实写**。

## 3. 验证顺序（Lead 串行执行）

1. 所有工作流回报后：`tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=N`
2. 全量自检（`tools/selfcheck_*.py` 全跑，含新增 round95_*），失败数必须 0
3. `tools/verify_mixin_shadows.py`（两遍：CLIENT / DEDICATED_SERVER）
4. `tools/verify_dedicated_server.ps1`（PID 锁，串行）
5. `gradlew.bat build --offline --no-watch-fs -q` → `build/libs/rs_create_compat-1.2.0-beta.3.jar`
6. 交叉复核：只读审计员独立核一遍本轮 diff；Lead 亲自复核关键 diff 后交付
