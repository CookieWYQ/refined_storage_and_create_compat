# 机械动力 & 精致存储：兼容与改善 `1.1.0`

> **一次「让长产线真的跑得动」的版本。** 上一版把 Create 的序列装配接进了精致存储的自动合成；这一版把那条产线在**规模**下的问题收掉了：长线缆上的总线不再被误判、总线分配不再卡死、别人正在等的料不再被抢走、普通任务不再被误挂起。
>
> 适用于 Minecraft `1.21.1` / NeoForge `21.1.x`。版本号 `1.1.0`。

---

## 升级一句话

从 `1.0.0` 升到 `1.1.0`：**不用重建世界，不用重新生成样板，不用重放任何机器**。已有的样板、线缆与执行仓原样继续工作，而且升级之后，那些「以前莫名其妙变红条、变成普通总线、乃至干脆卡住不动」的产线会自己恢复正常。

唯一需要你主动做的一件事：**诊断日志是关闭的**。要抓日志排查问题，先敲一次 `/rs_create_compat devlogs on`。

> **本版附件替换过一次（2026-10-11，版本号仍是 `1.1.0`）**：最初上传的那份 `1.1.0` 附件会让**专用服务端在模组加载阶段直接崩掉**（Mixin `@Redirect` handler 签名不符），现已修好并替换上传。同一次替换还带来 **Refined Storage 2 依赖从 `2.0.0` 升到 `2.0.9`**。两件事的来龙去脉见下面「附件替换记录」与「依赖升级」两节。

> **关于 `1.0.0` 的两条旧闻（先看这里，免得白忙）**：「专用服务端一装就崩」的修复与「开发日志总开关」`devLogs` **都已经包含在 `v1.0.0` 的最终附件里**（发布后重新构建并替换上传的那一份），**不是 `1.1.0` 的新修复**。从 `1.0.0` 升级上来的玩家**不需要为它们做任何事**：你没有装过那份会崩的 jar，日志开关在 `1.0.0` 就已经是默认关闭。下面的正文里仍然保留这两节的说明，只为把 `1.0.0` 发布正文没写的事补齐。

---

## 附件替换记录：专用服务端「启动即崩」已修好

> **附件已于 `2026-10-11` 替换上传，版本号仍为 `1.1.0`。** 手里那份如果起不来专用服务端，重新下载同一个 `1.1.0` 附件即可。因为版本号没变，判断「手里这份是哪个构建」的唯一凭据是启动日志里的 `[rscc-build]` 那一行（见「反馈渠道」）。

**症状**：把模组装进专用服务端，**启动在模组加载阶段就停住**，日志里是一段确定性的崩溃：

```
Mixin apply for mod rs_create_compat failed
  rs_create_compat.mixins.json:AutocrafterManagerSlotMixin
  -> ...AutocrafterManagerContainerMenu
InvalidInjectionException: @Redirect factory method ... has an invalid signature.
  Found unexpected return type net.minecraft.world.inventory.Slot,
  expected com.refinedmods.refinedstorage.common.autocrafting.PatternSlot
```

不是进世界之后才出问题 —— 是**加载阶段**就崩，专用服务端根本起不来。

**根因**：本版把 Refined Storage 依赖从 `2.0.0` 升到 `2.0.9` 时，为适配 RS 的改动，把 `AutocrafterManagerSlotMixin` 里 `@Redirect` 的 `@At` 目标从普通 `Slot` 改成了 RS 自己的 `PatternSlot`，**却忘了同步改 handler 的返回类型**。Mixin 对「`@Redirect` 构造器注入」有一条硬规则：**handler 的返回类型必须精确等于被构造的那个类型**。返回类型还是 `Slot`，目标已经是 `PatternSlot`，于是模组加载期直接抛 `InvalidInjectionException`。

**修复**：handler 的返回类型改为 `PatternSlot`，构造同步改为 `new PatternSlot(container, index, 0, 0, level)`。返回类型与 `new` 出来的类型严格一致之后，注入重新成立，槽位语义一字未变。

**验收证据（真机）**：

- **真启动了一次专用服务端**（headless，独立目录，不是 `run/`）；
- 换回**修复前的旧 jar** ⇒ 判定 `OUR_MIXIN_FAILURE`，崩溃与上面的原文逐字一致（**确定性复现**，不是偶发）；
- 换成**修复后的新 jar** ⇒ 启动打印 `Done (1.177s)! For help, type "help"`，判定 `PASS`，日志里**没有任何 Mixin 与 dist 致命标记**。

**静态校验也一并补强**（`tools/verify_mixin_shadows.py`）：校验器现在分**「客户端一遍」与「专用服务端一遍」**各自报问题数；新增 `@Redirect` handler 签名校验（返回类型、形参、`@At` 目标逐项对真实字节码），并且留了**反例自证** —— 把返回类型改回旧值，**旧检查报 0 问题放行、新检查判负**，证明这条规则真的拦得住这一类错。

**这不是 `1.0.0` 那条崩溃。** `1.0.0` 修的是注册网络包时加载客户端类导致的 `NoClassDefFoundError`（见下面「属于 `1.0.0` 的两条」）；本条是 `@Redirect` handler 签名不符。两条都是专用服务端启动问题，成因与修法完全不同。

---

## 依赖升级：Refined Storage 2 从 `2.0.0` 升到 `2.0.9`

**实际值**：`gradle.properties` 里 `refinedstorage_version=2.0.9`，也就是本版**是对着 `2.0.9` 编译的**。

**为什么升**：大家实际跑的运行环境是 `2.0.9`。**「对着旧版编译、在新版上运行」是问题的温床** —— 编译期看不见的差异（方法形参变了、构造的类换人了）只会在运行期冒出来，而且往往以「静默不生效」的面目出现，不带任何报错。

**升级当场发现并修掉的静默失效**：升级立刻暴露了一个**早就不对目标**的 mixin —— `AutocrafterManagerSlotMixin` 挂在 `require = 0` 上，而 `2.0.9` 改动了它要注入的那个重载：`addServerSideSlots(Group)` 变成 `addServerSideSlots(Group, Level)`，建槽也从普通 `new Slot(...)` 换成了 RS 自己的 `PatternSlot(Container, int, int, int, Level)`。注入对不上目标，而 `require = 0` 让它**既不崩、也不生效** —— 那条「服务端样板槽放行校验」的修复就这么静默地没在工作，日志里一个字都不会提。本次把它对齐了：方法描述符补上 `Level`、`@At` 目标指向 `PatternSlot`、handler 的形参与返回类型逐项对上真实字节码（`javap` 反汇编核对：`new PatternSlot` 的构造器形参顺序是 `(Container, int, int, int, Level)`）。**把 `@At` 目标改对、却忘了同步 handler 返回类型，就是上一节那次启动崩溃的由来。**

**两个版本的 API 差异只有这一处**，`2.0.0` 升到 `2.0.9` 造成的 **Java 源码编译破坏 0 处**。

---

## 这个模组能做什么（新读者从这里开始）

把 Create 的**序列装配**接进 Refined Storage 2 的**自动合成**：在 RS 终端下一次单，整条 Create 产线自动跑完，成品与废料直接进网络。

- **在 RS 里下单 Create 的序列装配**：坚固板、精密构件、列车轨道这类多方块、多步骤、带概率产物的产线，注册成 RS 自动合成样板后就能像普通合成一样下单。
- **不用手写样板**：在序列装配样板终端里从 JEI 导入一条 Create 序列装配配方，步骤、处理器类型、重复次数、概率产物与废料都会自动展开成卡片。
- **执行仓替你搬料**：序列执行仓紧贴机器放置，自动从网络拉原料、通过输出总线投给相邻机器，并把中间产物、成品与废料收回网络。
- **成链扩容**：同一个配方的多台执行仓沿箭头排成一条链时，会被当成**一台**逻辑执行仓使用，总线接在链上任意一台旁都算接上了整条链。
- **按类别精细控制物流**：输入与输出总线的「类别详细配置」按配方分页，勾选原料、输入时原料、中间产物、成品、废料与流体。
- **卡住时会被明确告知**：任务被下游机器拒收、执行器掉线、缺料时，监视器上的横幅会分别说明原因与出错步序。

---

## 本次重点：四个会让产线「永远不动」的问题

这一版修的问题有一个共同点：它们都**不会报错**，只是让产线静静地停在那里，而且**越大的产线越容易撞上**。

> 本版**真正新增**的严重修复就是这四条。另有两条常被误当成 `1.1.0` 的新修复 —— 「专用服务端一装就崩」与「日志刷屏／`devLogs` 开关」—— 它们**已经包含在 `v1.0.0` 的最终附件里**，集中放在后面的「属于 `1.0.0` 的两条」一节，从 `1.0.0` 升级上来的玩家无需为它们做任何事。本版附件替换时修好的那条**专用服务端启动崩溃**（`@Redirect` handler 签名）是另一回事，见前面的「附件替换记录」。

### 一、长线缆上的总线被误判成「归属未确定」，直接停用

**你看到的现象**：总线界面突然变回普通的输入／输出总线界面，类别勾选不见了，红条加横幅；更隐蔽的一种是界面看起来正常，但那台总线实际上「什么都没连上」。

**根因**：判断「这条总线属于哪条执行舱链」要靠沿线缆做一次洪水搜索。旧实现在搜索上压了两道**静默上限**（BFS 层数上限 64、线缆格数上限 128），走到上限就**直接结束**，并把被砍掉尾巴的那半份结果当成完整结果。于是：

- 排在后面的总线**从来不在可达集合里** —— 它既不知道链在哪，也没人告诉它；
- 「恰好够到一条链、但这一趟没扫完」被当成了「归属未确定」⇒ **停用**；
- 截断点还取决于**哪些区块已加载**，所以同一套布局会随玩家走动而给出不同结论 —— 这就是那种「过一会儿又好了」的错觉；
- 布局固定时每次截断在同一处，就变成**永远不恢复**。

**修法**：把搜链改成**可续扫**。每 tick 只推进一个预算（256 格），一趟最多 4096 格，**只有整趟真的扫完才发布结论**；续扫期间对外继续沿用上一次的完整结果，绝不对外发布半份集合。同时把「暂时没扫完」与「真的够到了两条不同的链」彻底分开：**截断不再等于停用**，只有真的存在歧义才停用（红条口径一字未改），而撞上硬上限时会照实标成「未穷尽」并留一条可核对的日志，不静默丢弃。

**实测（模型推演，400 格线缆 + 200 台总线，线缆一端挂着一条链）**：

| | 判为「连着」 | 被误停用（红条） | 退回普通总线 |
| --- | --- | --- | --- |
| 旧实现 | **0 台** | 21 台 | 179 台 |
| 新实现 | **200 台** | 0 台 | 0 台 |

同一套布局、只有「哪些区块已加载」不同时，旧实现的结论会翻转，新实现的结论保持稳定。

### 二、总线「分配」会突然卡住，而且总线越多越容易卡

**你看到的现象**：产线跑到一半不动了，等一会儿有时会自己恢复、有时一直卡在那里；总线越多越容易撞上。

**根因**：执行仓要把「这一轮某个类别交给哪几条输出总线」算出来，并逐台下发。旧实现里有三条**可能永久不回退**的路径：

1. **连接检测被静默截断**（单次 64 格上限）：排在后面的总线**从来不会出现在可达集合里**，也就永远拿不到自己的那一份配置；
2. **刷新循环没有容错**：某一台总线抛一次异常，排在它后面的总线这一轮全部不刷；下一轮顺序相同，于是**永久饿死**；
3. **不在可达集合里的总线没有任何重试点**：既不会被刷到、也不会被点名，全程静默 —— 这就是「最后一直卡在那、啥也不动」里最难自愈的一种。

**修法**：三条一起改。连接检测改成跨 tick 续扫，只有扫完才发布；刷新循环改成**逐台隔离**（一台出错不带走后面的）加每 tick 最多处理 8 台的轮转预算与进度游标；再加一个**停滞看门狗**（60 tick 内没有任何链上成员刷过某台总线，就强制作废连接检测、重扫并点名）。原则是：**要么被刷到，要么被点名，绝不静默冻结。**

**实测（模型推演）**：

- 400 格线缆时，旧实现每次只发布 65 格 ⇒ 尾部 **168 台**总线永久缺席；跑 200 tick 发布集合逐字相同（是永久冻结，不是抖动）。新实现一趟 2 tick 扫完，尾部缺席 0 台。
- 让第 3 台总线一直抛异常：旧实现下排在它后面的 7 台在 1000 tick 内**一次都没刷到**；新实现下其余各台全部被刷到，坏的那台每次轮到都重试。
- 200 台总线全部被刷到，最慢一台在第 24 tick 首次刷到。

### 三、轮空、漏料：别人正在等的料被抢走了

**你看到的现象**：做精密构件的时候，网络里的齿轮与大齿轮**被提前消耗掉**，已经排在那里的齿轮与大齿轮合成任务**位置卡住不动**。整个网络并没有缺料，但那些任务就是不动。

**根因**：精致存储 2 **没有「库存已被预留」这个概念**。一个已经下单、还没把料抽到手的任务，它「想要的那部分量」只活在任务内部（表现为 `extracting`），RS 在别人抽料时**不会先扣掉这部分预留**。于是本模组的执行仓会用「网络存量」去抽料，把正在被在途任务等待的那一份抢走 ⇒ 那个任务永远等不到，界面上的位置就一直卡在那里。

**修法**：本模组自己算这本账。

- **可用量 = 网络存量 − 在途任务预留**；
- **取用侧夹量**：真正抽取的量由一份账本（`TakeLedger`）批准，多台仓、多条总线在同一个 tick 里各取，**合计不超过可用量**；获批 0 时进入等待并上报缺料，那一条分支里**没有任何抽取调用**（结构可验证，不是靠注释）；
- **释放侧归还**：仓里那些「此刻没有任何工位要它、而某条在途任务正等着它」的东西，会主动还回网络；而**起步原料与有工位正在等的料一律不动** —— 绝不抢机器正等的料，也绝不重启「买进来又退回去」的空转。

**收敛性**：任务在途期间它的「还差多少」只会单调减少（RS 只在真抽到的时候扣），所以「等」这件事必然收敛，不会死锁。已在途、已经不会再抽料的任务（回收中、已完成）的过期读数不计入预留，因此不会出现「一个已经不会再动的任务永久占住资源」。

**对既有产线的影响**：**没有任何预留时，可用量等于存量、获批等于想要** —— 也就是旧行为逐字不变。这条修复只在你**确实有别的任务在等料**的时候才说话。

### 四、普通（非序列装配）的自动合成任务被误挂起

**你的场景**：主线上挂着一批**普通的、用输入总线回收的**自动合成任务；你从这条主线接一条分支到序列执行舱。结果整条主线上的**所有**总线都被判成了那台执行舱的附属，那些普通任务**全部被提示挂起**。

**根因**：总线归属只看**线缆几何** —— 分支一旦接通，主线上的每一台总线在几何上都「够得到」那台执行舱的链，于是它们全被延长化了；而「自动挂起」与「挂起超限后兜底取消」这两件事原本没有区分「这条任务到底是不是本模组的产线在驱动」。

**修法**：给任务记录加上「是不是本模组的链在驱动」这个判据，并把两处**自动**动作用它闸住：

- 网络里只有普通任务 ⇒ **不挂起**；普通任务与序列装配任务并存 ⇒ 普通任务**不挂起**；序列装配真堵 ⇒ **仍然挂起**（不回归，仍是秒级）；
- 全工程唯一的自动取消（挂起超限的兜底回收）收窄成**只对本模组序列装配链真正驱动的任务生效**。普通任务即便被玩家手动挂起，超限之后也**绝不**被自动取消 —— 这一条是硬保证，因为取消不可逆。

**渲染侧一字未动**：按钮的显隐只看服务端给的动作位与界面几何，与「任务归谁管」无关；普通任务与序列装配任务在**同一状态下渲染同一组按钮**。

---

## 属于 `1.0.0` 的两条（不是本版的新修复）

> 下面两节**已经在 `v1.0.0` 的最终附件里**（`build/libs/rs_create_compat-1.0.0.jar`，sha256 与 GitHub Release `v1.0.0` 附件一致；该附件由 revision `909bd6c`、`version=1.0.0` 构建）。它们**不是 `1.1.0` 的新修复**，放在这里只是把 `1.0.0` 发布正文没写的事补齐。**从 `1.0.0` 升级上来的玩家无需为它们做任何事。**

### 附一、专用服务端一装就崩

把模组装到专用服务端上，**启动即崩**，日志报 `NoClassDefFoundError: net.minecraft.client.gui.components.toasts.Toast`。

**根因**：注册网络包的时候会读取每个包类的静态字段，这个动作会让包类被**初始化并链接**；而 JVM 链接期要校验方法体里的「实参能不能赋给形参」——那必须把两侧的类都**解析并加载**。于是只要某个包的处理体里出现了「把客户端类型交给客户端类型形参」这种写法，专用服务端就会去加载一个**专服上根本不存在**的类，然后崩。

**修法**：不在注册路径上出现任何客户端类型。新增一层服务端安全的间接层（形参一律是网络包自己的数据），真正的界面与渲染逻辑逐字搬到一个只在客户端注入的实现里，13 个载荷类的处理体统一改走这一层，线程语义与搬迁前逐字一致。**不用 `try/catch NoClassDefFoundError` 掩盖** —— 链接期报错之后类的状态已不可信，而且下一个包还会再炸一次。

**顺带排查了同类隐患**：注册段里另外那些包虽然没有引爆，但属于同一形状的风险，本轮一并收口。新增的专用服务端安全性自检在修复前报 13 个文件命中，修复后为 0，且现在扫描整个 `network` 包（98 个文件）确认没有任何客户端类型引用。

**关于附件的一句话（重要）**：这条修复**已经包含在 `v1.0.0` 的最终附件里**（那份附件是重新构建后替换上传的，sha256 `ab6d04bf39a4b3c28c07b5996bcfa0bc89ccc24c6a5c9d1d0fdbf5f6b5f47d9b`），**不属于 `1.1.0` 的新修复**。如果你当初是在发布后的头几分钟内下载的，那份 jar 会崩 —— 直接换成本版即可。`1.0.0` 的发布正文没有同步写入这条修复，本版把它补齐。从 `1.0.0` 升级上来的玩家手上已经是修好的那份，**无需为这条做任何事**。

### 附二、日志刷屏：一秒上百行

**你的现象**：装到正式环境之后，后台一直在刷日志，影响不好。

**审计结果（把 `run/logs` 下全部历史日志加在一起，本模组累计 320,285 行；出处是本仓库 round48 的历史日志审计产物 `build/round48_log_audit2.txt`，原始日志已被后续清理、无法再逐字节复现）**：

| 家族 | 行数 | 峰值 |
| --- | --- | --- |
| `[loader]` | 217,439 | 80 行／秒 |
| `[rscc-assembly]` | 56,760 | 128 行／秒 |
| `[rscc-trace]` | 32,476 | —— |
| `[rscc-keeper]` 销毁族 | 9,130 | —— |
| 其余家族合计 | 约 4,480 | —— |

**根因**：运行时开关是硬编码的「开」，配置项也默认「开」，两层都为真，所以**没有任何指令也在持续输出**。

**修法**：配置项 `devLogs`，**默认关闭**；旧的独立配置项删除，与 `assemblydebug` 合并成**同一个**开关，避免两个语义重叠的开关。关掉之后**必要日志一条都不会少**：

- 每次启动恰好一行的构建指纹；
- 会话锚点与启动档位；
- **全部 WARN 与 ERROR**（这一族不再受开关控制）；
- `/rs_create_compat diag` 与玩家操作的直接后果；
- 带节流的低频解释性 INFO。

**一个容易被漏掉的坑，`1.0.0` 里就专门修了**：把「该步没有执行仓认领」的时间戳记录移出了开关 —— 否则关掉日志会**连带让监视器上的横幅失效**，那就成了「日志清净了、但真出问题时也不告诉你」。

### 五、归流缓存仓的「经验」按钮点了没用

**你的现象**：装了附魔工业，也勾上了那个按钮，但**点了不生效**。

**根因有两层，主因不是你以为的那个**：

1. **枚举序号解码写错**：客户端与服务端之间的那个「收哪种经验」的编号，在解码时认错了序号，于是「液态经验」被静默解成了「经验颗粒」 —— 按钮看起来点了，实际请求的是另一种形态。
2. 检测口径过严：把「模组加载了没有」当成了硬门槛，于是「装了却判定不通过」。

**修法**：修正序号解码，把资源可用性改为**以注册表为准**（模组 id 判断保留 null 安全），并明确语义 —— **勾上之后，经验球直接折算成液态经验存进本仓的流体缓存**，不再绕道颗粒。

**换算率有上游依据**：`1` 点经验 = `1` mB 液态经验；经验颗粒每个折算 `3` 点。入缓存的毫桶数**恰好等于**扣掉的点数，剩下的点数留在球里 —— 不复制、不凭空产生（自检里用算术模型复算过）。

**降级路径**：没有附魔工业时液态不可选，自动退回颗粒；运行期流体缺失时把球折算成颗粒；两种表示都不可用时**一个球都不动**。

### 六、监视器里的「挂起／继续」按钮不渲染

**你的现象**：任务不管是什么类型，不都应该能挂起与继续吗？但那几个按钮**看不到**。

**根因**：按钮的显隐由「服务端给的动作位」与「界面几何」两个因子决定，而**服务端只会给本地已有快照的那条任务动作位**。界面里原本只在打开时拉一次快照，之后选中另一条任务时**不会补拉** —— 于是你选中一条没有快照的任务，客户端手里是空的，一个按钮都渲染不出来。

**修法**：补上这个结构性缺口 —— 选中任务但本地没有快照时，**按任务各补拉一次只读请求**，并在每名玩家每次会话里对同一条任务最多补拉一次。已有快照的任务**一个包都不多发**。

同时把这条链路逐段钉死（源码锚点加真值表）：按钮可见性**只**由服务端动作位与一次性算好的静态几何决定，与任务种类、与「归本模组管」都无关；两个主按钮共用一个槽位、放不下时**一起收起**（不会出现「画着却点不动」的半截状态）；挂起与继续共走**唯一**的一份实现；按钮行与 RS 原生的取消按钮**同一行同高**，横向起点取原生按钮行的右缘。

---

## 新增能力

### Jade：瞄准总线就能看清它归谁、在管什么

给输入总线与输出总线加了 Jade 提示：

- **绑定到哪台执行舱**；
- 按类别分节列出它**此刻负责什么**（原料、输入时原料、流体、中间产物、成品、废料）；
- 输入总线处于全自动时额外**强调**这一点。

显示方式可配置：默认**一直显示**，也可以切成「按住 Shift 才显示」。数据只读自方块实体，不在客户端碰方块实体，也没有新增同步包。每组最多列三件代表物，超出只写一个「…」，**不写数量、不枚举种类数**。没装 Jade 一定不崩（可选依赖，三个新类只被彼此引用）。

### Create 剪贴板：批量安排总线的配置

以前一条一条手动配总线太麻烦。现在可以用 Create 的剪贴板，走**与 Create 复制配置完全相同的那个手势**：

| 手势 | 作用 |
| --- | --- |
| 手持剪贴板**右击**总线 | 复制这条总线的全部玩家配置（RS 过滤槽与模糊模式、本模组的类别勾选、「全自动收回」、强制普通总线） |
| 手持剪贴板**左击**总线 | 把配置粘贴到**这一条**总线 |
| **潜行**加左击总线 | 粘贴到**同一条线缆簇里的全部同种总线**（「快速大量」那一档） |

边界是明确的：整簇粘贴只走**同一条线缆簇**（扳手断开的接缝与被分隔框架冻结的接缝都算断），只改**同一种**总线，最多 **64 台**，**绝不跨维度、绝不跨簇**；种类与版本不符时**一个字节都不写**。

两件安全上的事：剪贴板载荷只替换**本模组那一段键**，绝不抹掉别的模组已经复制进去的内容；读剪贴板、校验、写入与文案**全部只在服务端**执行（客户端只负责取消事件），因此不需要新增任何自定义网络包。

### 潜行加扳手右键：快速拆卸本模组的方块

精致存储自己的方块、线缆之类，按住 Shift 加右键、手持扳手是可以直接快速拆掉的；本模组以前所有方块都不行。现在可以了。

- 覆盖**本模组注册的全部 14 个方块**（11 台机器加 3 个框架方块）；判据是**注册表命名空间**，因此将来新增的方块会自动覆盖，不需要维护白名单。
- **与 RS 同机制**：扳手判据沿用工具标签，不潜行时照旧放行原有右键交互（界面照开），权限判定沿用 RS 自己的提示，拆掉的音效也一致。
- **刻意没有照抄 RS 的拆除写法**：RS 那种做法会跳过本模组在移除方块时做的内容物结算与机器集群内容移交（那会让资源翻倍）。这里的顺序是「先产出掉落物、再移除方块」，与原版挖掉一个方块一致。
- 任何方块的 `useItemOn` / `useWithoutItem` **一个字节都没动**，机制完全在外层事件里。

### 范围充电器：饰品栏里的东西也能充

**你的现象**：把无限终端放在饰品栏里，范围充电器**充不到它**。

**实际情况**：充电器扫的是玩家背包族（主背包、快捷栏、盔甲、副手）——盔甲与副手本来就在内，**真正缺的只有 Curios 饰品槽**（那是另一套容器，不属于背包）。本轮补上了饰品槽。

细节：一次扫描用「按实例判等」的集合登记处理过的物品，两处循环都判过，因此**不会重复充电**；拿到满电立刻跳过；只从本机缓存扣掉**实际接受**的那部分能量（能量守恒）；饰品槽**不是每 tick 扫**，而是按配置项 `rangeChargerCuriosScanInterval`（默认 5 tick）节流 —— 换算下来端到端延迟不超过一秒，肉眼看不出来。**刻意不递归容器内的容器**（精致背包之类不再往里翻），因为那没有必要。没装 Curios 时反射链路的异常都在内部被吞掉，充电器侧不需要任何容错代码。

### 伪装可以套上外部存储总线

伪装（分隔框架与伪装框架）以前能套 RS 线缆族与 Create 的管道族、也能套输入与输出总线，但**套不上外部存储总线**。现在补上了。

两处清单同时覆盖它（「能不能裹」与「给谁画外壳」），否则会出现「能裹上去、但那一格不显示外壳」。同时**刻意没有动「可穿行族」** —— 那一族决定「总线能不能隔着它够到执行舱」，把外部存储总线放进去会凭空改变延长型的归属判定，那是没人要求的语义改动。因此外部存储总线走的是**单独一个判据**。

### 过滤槽优先：总线上放了东西，就按普通总线走

**你的要求**：输入输出总线与执行仓绑定之后，它原本的过滤槽就没意义了。所以如果一条总线的**过滤槽里是有东西的**，那就**优先认为它是普通的**。

判据用的是 **RS 自己的过滤容器**（不是本模组的类别勾选 —— 那会变成自己判自己），并且收口在**两个**地方：界面走哪一套、搬运走哪一条路。两者必须一致，否则会出现「界面看起来是普通总线、实际还在按类别搬运」这种鬼影。判据**现算不缓存**，过滤槽每一次改动都会即时翻转两个方向。输入总线的「全自动收回」开关**一字未改**：过滤槽为空时它照旧生效，过滤槽非空时本条规则优先级更高（两者严格互斥，不会同时说话）。

### 序列装配样板库：总样板常显「它合成什么」

样板库里那些总样板，现在**总是显示它们具体合成什么物品**，也就是 RS 自动合成仓里那种「默认就显示产物」的样子，不需要按 Shift。

做法是**复用 RS 官方的钩子**：给样板库界面实现 RS 自己的那个「可以显示产物」接口，判据直接委托给菜单（「本库槽位加同一实例」），一行绘制都不加、tooltip 一个字都不改，因此也不会多占一个像素。全工程只有这一个界面这样做。

**同时补齐**：流体在定量保持器里的 tooltip 改为渲染**流体本身**（与精致存储自己的流体渲染同源），并且**不再渲染数量**；大数值目标（例如一亿毫桶，以前显示成一串 0 看不全）现在完整可读，输入框支持千分位写法，解析回来等于原值；输入框加宽之后与后面的按钮**几何上不可能重叠**（穷举验证过：两两不重叠、不越界、不压槽位）。

### 结构缓存：把「每次都要重算的结构」记下来

**你的提议**：能不能用缓存把这些目标存好、到时候直接发，而不是要发了再去找？以及关键问题 —— 变更了怎么更新？

这一版按「**宁可多失效、绝不漏失效**」的方向做了：

- **记什么**：只记**纯结构**的推导（链成员）。
- **什么时候失效**：一个**单调递增的结构版本号**。方块被放、被拆、被替换、扳手剪线与套壳、成链成员变化、网络节点增删、区块加载与卸载，都会推一次版本号。读取侧只记下「我这份结果是用哪个版本算的」，**对不上就重建**。
- **为什么用版本号而不是显式清理**：显式清理**漏掉一个调用点就等于永久冻结**（RS 自己那份「弱键加永不失效」的样板缓存就是踩在这个坑上）；版本号在同样漏钩子的情况下，下一次读取仍会重建。
- **动态量一律不缓存**：存货、机器忙闲、拒收、在途与名额计数都不在任何缓存结构里 —— 它们正是「检测失败」这一类 bug 的成因。
- **不泄漏**：备忘只活一个 tick、**不记进 NBT**，因此不存在跨区块与跨存档的强引用滞留；TTL（20 tick）的整表重建仍然保留。
- **代价诚实说明**：失效源用的是粗粒度事件，所以「与结构无关的方块变化」（例如远处放一块石头）也会让下一次读取重建一次。重建上限是 8 台以内的小推导，因此这个方向是划算的。

---

## 性能

| 场景 | 旧 | 新 | 变化 |
| --- | --- | --- | --- |
| **远程终端打开**（大型：12 步、40 仓、200 张已有样板、400 条序列配方） | 58,221 单位工作量 | **12** | **−99.98%**（0.02%） |
| 远程终端打开（中等：8 步、12 仓、40 张样板、120 条配方） | 8,589 | 8 | −99.91% |
| 远程终端打开（小存档：2 步、3 仓、6 张样板、30 条配方） | 416 | 2 | −99.5% |
| **总线分配的世界查询次数**（N=200 台总线） | 44,395 | **2,010** | **22.1×** |
| 总线分配（N=50） | 3,595 | 510 | 7.0× |
| 总线分配（N=10） | 315 | 110 | 2.9× |
| **长线缆 + 200 台总线** | **0 台**正常工作（21 台被误停用、179 台变普通） | **200 台全部正常** | —— |

**远程终端为什么能快这么多**：以前「打开」的那一拍要**枚举整张网络图**、**逐张解析样板 NBT**、还要**重扫全部序列装配配方表**。现在改成「先发一份轻快照、把昂贵的判定延后」：

- 打开那一拍的开销**只与步骤数有关**，不再随节点数、仓数、样板数、配方数变化；那一拍里**没有任何网络枚举、没有任何逐槽判定**；
- 在一段 TTL 内重开，**连延后那一拍也不会执行**（一次网络枚举都不做）；
- 查找函数从线性扫描改成预建表（O(1) 查询）；
- 「生成样板」的扣料口径**没有**被延后扫描削弱 —— 生成之前仍按网络实况重扫一遍，权威判定仍在方块实体里；
- 另一处同类问题也顺手收了：终端 Tab 的图标以前**每帧**新建物品栈，现在改成会话级缓存。

**总线分配为什么能降到近似线性**：旧实现的成本随总线台数**平方**增长（N 从 50 涨到 200，台数翻四倍，成本涨 12.3 倍）；新实现近似线性（同一区间只涨 3.9 倍）。压掉的是几处「每次都要重新遍历全部供料目标」的写法，改为每 tick 一次的备忘与预建分组。**刻意没有缓存容器内容** —— 那是「同一工位最多一份未消耗投入物」这条硬不变式的唯一凭据，必须能看见本 tick 自己刚推的那一份。

---

## 配置项与指令

### 配置项

本版**新增**的是 `jadeBusTooltipMode` 与 `rangeChargerCuriosScanInterval`；`devLogs` **不是本版新增**，它在 `1.0.0` 就已落地（并且从 `1.0.0` 起默认就是 `false`），列在这里只是让配置表完整。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `devLogs` | `false` | 开发日志总开关。关着的时候仍然输出全部 WARN 与 ERROR、启动指纹、会话锚点、`diag` 导出与玩家操作的直接后果。可在游戏内用指令即时切换，不需要重启 |
| `jadeBusTooltipMode` | `always` | 总线 Jade 提示的显示方式：`always` = 一直显示；`shift` = 按住 Shift 才显示（没按住时只留一行「按住 Shift 查看总线状态」）。纯客户端表现，专服读不到也用不到，因此单人世界与专服的行为不会分叉 |
| `rangeChargerCuriosScanInterval` | `5` | 饰品槽的扫描间隔（tick），范围 `1` 到 `1200`。背包族仍然每 tick 扫；这个周期只作用于新增的饰品槽那一路 |

### 指令

指令根为 `/rs_create_compat`，**任何玩家都可执行，不需要权限**。

| 指令 | 作用 |
| --- | --- |
| `/rs_create_compat autocrafter storage on` | 开启执行者周围 32 格内全部自动合成仓的内部存储 |
| `/rs_create_compat autocrafter storage off` | 关闭（先把仓内物品与流体写回网络，装不下则取消） |
| `/rs_create_compat supply target` | 原料供应：直到目标产物达标（默认，缺料自动补合成） |
| `/rs_create_compat supply materials` | 原料供应：只管输出原料，不看概率、也不主动发起自动合成 |
| `/rs_create_compat shortagemode suspend` | 缺料处置：缺料即挂起（默认，不堵塞后面的任务） |
| `/rs_create_compat shortagemode wait` | 缺料处置：一直等到有料再继续，缺料绝不自动挂起 |
| `/rs_create_compat refill off` | 补合成请求量：确定性配方一次一份、概率配方分批递进（默认） |
| `/rs_create_compat refill on` | 补合成请求量：确定性配方按缺口一次要足 |
| `/rs_create_compat refill machines` | 补合成请求量：本仓喂着几台机器就发几份 |
| `/rs_create_compat reuse on` | 开新件时优先复用网络中已有的该配方中间产物 |
| `/rs_create_compat reuse off` | 从头走（默认） |
| `/rs_create_compat blockcontent network` | 拆方块时内容物写回所在 RS 网络（无网络时降级为存进方块） |
| `/rs_create_compat blockcontent drop` | 拆方块时内容物以掉落物爆出（默认） |
| `/rs_create_compat blockcontent block` | 拆方块时内容物写进方块物品，重新放下原样恢复 |
| **`/rs_create_compat devlogs on`** | **打开开发日志总开关**（推荐写法） |
| **`/rs_create_compat devlogs off`** | **关闭开发日志总开关（默认）** |
| `/rs_create_compat assemblydebug on` | 等价别名（保持旧写法可用） |
| `/rs_create_compat assemblydebug off` | 同上 |
| `/rs_create_compat debug assembly on` | 等价别名 |
| `/rs_create_compat debug assembly off` | 同上 |
| `/rs_create_compat diag` | 导出当前全部相关状态为一份快照 JSON，供排查使用 |
| `/rs_create_compat diag run` | 先强制打开诊断日志，再导出快照并落分节标记（只观测，不改任何任务状态） |

三种日志开关写法接到**同一个**开关，即时生效；只输入到开关那一层（例如只敲 `/rs_create_compat devlogs`）会回报当前状态与用法。`supply` / `shortagemode` / `refill` / `reuse` / `blockcontent` 同样支持只敲一层回报档位。

---

## 升级注意：从 `1.0.0` 到 `1.1.0`

- **不需要重建世界**，也不需要替换已放置的机器与线缆。
- **不需要重新生成样板**。本版**没有改动样板的 NBT 结构**（`1.0.0` 那次「候选组要写进样板 NBT、旧样板必须重生成」的情况本版不会重演）。
- **升级后，以前被误停用的总线会自己恢复。** 长线缆上那些变成红条、乃至退回普通总线界面的总线，现在会被正确判为「连着」—— 你不需要拆线重连。升级后建议打开一次总线界面确认，但**不要**在此之前先把它们手动改成「普通」（那样会锁住手动档，需要点「恢复」才会重新自动判定）。
- **挂起中的任务不会自动恢复。** 本版只是让普通任务不再被**误**挂起；已经被挂起的任务仍然需要保留原有语义 —— 在监视器上点「继续」。这是刻意的：拆机器加机器往往是有意为之。
- **升级后请顺手看一眼在途任务。** 预留模型会让执行仓在「别人正在等这份料」时**先等一下**。如果你的产线本来就有大量并发的在途任务，升级后头几分钟看到执行仓「没有立刻抽料」是正常行为，不是卡住 —— 它在等前面的任务把料抽走。没有任何在途预留时，行为与 `1.0.0` 逐字一致。
- **诊断日志是关闭的**（`devLogs`，默认 `false`）。这不是 `1.1.0` 的新变更 —— 它在 `1.0.0` 就已经默认关闭、旧配置项 `rsccAssemblyDebug` 也已经删除并合并进 `devLogs`，本版只是沿用。要抓日志请敲 `/rs_create_compat devlogs on`（即时生效、不需要重启），也可以把配置里的 `devLogs` 改成 `true`。
- **本版附件在 `2026-10-11` 被替换过一次，版本号仍是 `1.1.0`。** 替换的是「专用服务端启动即崩」的修复（`@Redirect` handler 签名），以及依赖从 Refined Storage 2 `2.0.0` 升到 `2.0.9`。能正常启动的玩家不需要做任何事；专用服务端起不来的玩家重新下载同一个 `1.1.0` 附件即可。**因为版本号没变，请用启动日志里的 `[rscc-build]` 那一行确认手里这份是哪个构建**（见「反馈渠道」）。
- **`1.0.0` 那条专用服务端崩溃（`NoClassDefFoundError`）的修复**：这条修复已包含在 `v1.0.0` 的**最终附件**里（那份附件是重新构建后替换上传的，sha256 `ab6d04bf39a4b3c28c07b5996bcfa0bc89ccc24c6a5c9d1d0fdbf5f6b5f47d9b`），**所以从 `1.0.0` 升级上来的玩家无需为它做任何事**。只有当初在发布后头几分钟内下载到那份未替换的 jar 的人才会崩 —— 直接换成本版即可。
- **升级后建议重开一次游戏。** 构建指纹只在启动时打一次；重启之后日志才能自证版本，一键自检也才能给出有效判定。

---

## 已知限制 / 注意事项

沿用 `1.0.0` 已经写明的部分（**面输出模式未设防**、**缺料处置切到「一直等待」后卡住判定会失效**、**挂起不会自动恢复**、**长链上限 8 台**、**分隔框架与扳手断开不随蓝图走**、**使用总线输出模式时需确认总线只服务于一条链**、**执行仓与缓存仓的容量取决于插的储存盘**），再加上本版新增与仍然成立的几条：

- **「搜索未穷尽」不等于「没问题」。** 线缆搜链撞上单趟硬上限（4096 格）时会**照实标成「未穷尽」并发布一份结果**、留一条可核对的日志，不会静默丢弃。这种规模的线缆网络本身就很难维护，建议用分隔框架把拓扑切开。
- **结构缓存的失效源是粗粒度的。** 与结构无关的方块变化也会让下一次读取重建一次结果。重建上限是 8 台以内的小推导，因此是刻意的取舍（宁可多失效、绝不漏失效）。
- **剪贴板整簇粘贴有上限。** 只走同一条线缆簇、只改同一种总线、最多 64 台、不跨维度；超出范围的会被跳过，不会部分写入。
- **Jade 提示会截断。** 每类最多列三件代表物，超出只写一个「…」，不写数量 —— 这是刻意的（提示要放得下），想看全请打开总线界面。
- **饰品槽充电有节流。** 默认 5 tick 扫一次，「放进去之后立刻满电」不会发生，延迟不超过一秒。想更快可以把 `rangeChargerCuriosScanInterval` 调小（最小 1）。
- **本次「专用服务端启动即崩」的修复是实机验收过的。** 这一条与下一条不矛盾：下一条说的是**功能行为**（总线分配、预留模型、结构缓存、线缆搜链）没有实机验证；而「装进专用服务端能不能起来」这一件事已经实测 —— 一次真实的 headless 专用服务端启动里，旧 jar 判 `OUR_MIXIN_FAILURE`、新 jar 判 `PASS` 并打印 `Done (1.177s)! For help, type "help"`。
- **本版大部分修复的凭据是源码交叉验证加模型推演，不是实机验证。** 自检脚本里对总线分配、预留模型、结构缓存与线缆搜链这几处都明确写着「没有实机验证」。这不等于没验证 —— 每一条断言都能被源码事实、模型反例证伪，而且模型与实现是逐字同构的；但它确实**不等于**在真实世界里跑过。大规模线上产线升级前，建议先在一个小批量的订单上验证一遍。
- **本版的性能数字来自模型与静态推演。** 上表里的「单位工作量」与「世界查询次数」是同一套规模参数下的计数对比，用来证明**增长量级**的变化，不是某一台机器上的毫秒数。

---

## 反馈渠道

遇到问题请到 GitHub Issues 反馈，并尽量附上 `/rs_create_compat diag` 导出的快照与 `logs/latest.log`：

<https://github.com/CookieWYQ/refined_storage_and_create_compat/issues>

报问题之前，请先跑一次 `/rs_create_compat devlogs on`（本版默认关闭），这样日志里才会有可用的诊断行。

**请把启动日志里的 `[rscc-build]` 那一行一并贴上。** 本版附件被替换过而**版本号没有变**，所以这一行（版本、git revision、编译时间、源码数、Minecraft／NeoForge／Java）是判断「你手里这份是哪个构建」的**唯一凭据** —— 排查「专用服务端起不来」这类问题时，第一件事就是看它。

---

## English Summary

`rs_create_compat` `1.1.0` is the release that makes long production lines actually run. `1.0.0` bridged Create's Sequenced Assembly into Refined Storage autocrafting; this version removes the ways that bridge broke down at scale — silently, and without ever printing an error.

**Severe fixes**

- **Buses on long cable runs were misjudged as "ownership undetermined" and disabled.** The search for the chamber chain a bus belongs to had two silent limits (64 BFS levels, 128 cable cells); hitting them ended the search and passed the truncated half-result off as complete. Buses further down the run were never in the reachable set, and "reached exactly one chain but did not finish scanning" was treated as ambiguity, so the bus was disabled. The cut-off point also depended on which chunks were loaded, which is why it sometimes "recovered" when you walked around. The search is now resumable: a per-tick budget of 256 cells, a hard cap of 4096, and **a result is published only when the whole run has been scanned**; while resuming, the last complete result stays in force. Truncation no longer means disabled — only a genuine reach of two different chains does. Measured on 400 cable cells with 200 buses: the old build had **0** buses correctly linked (21 wrongly disabled, 179 silently plain); the new one has all **200** linked.
- **Bus allocation could stall, and stalled harder the more buses you had.** Three paths could fail to ever recover: the chamber-side flood was silently truncated at 64 cells so trailing buses never received their share; the refresh loop had no fault isolation, so one throwing bus starved every bus after it forever; and a bus outside the reachable set had no retry point at all, failing in complete silence. Now: cross-tick resumable scanning that publishes only complete results, per-bus exception isolation, a rotation budget of 8 buses per tick with a progress cursor, and a stall watchdog (60 ticks) that forces a rescan and names the bus. The rule is "either get refreshed, or get named — never frozen in silence". Trailing absence went from 168 buses to 0, and with bus 3 throwing forever the seven buses behind it now all get refreshed.
- **Materials another task was waiting for got stolen (the root cause of skipped and missing feed).** Refined Storage 2 has no concept of reserved stock: the amount an in-flight task wants lives only inside the task, and RS does not deduct it when someone else extracts. A chamber therefore took what an in-flight cogwheel or large-cogwheel craft was waiting for, and that craft sat there forever. This mod now keeps the ledger itself: available = stock − in-flight reservations, the take side is clamped by a ledger shared across chambers and buses in the same tick, and the release side returns only what no station wants while some in-flight task is waiting for it. Starting ingredients and anything a station wants are never touched. With no reservations at all, behaviour is identical to before.
- **Ordinary (non-sequenced-assembly) autocrafting tasks were wrongly suspended.** Attaching a branch from a main bus line to a chamber made every bus on that line count as attached to the chamber, so ordinary collection tasks were all suspended. Automatic suspension and the single automatic cancel now apply only to tasks genuinely driven by this mod's chains. Ordinary tasks are never auto-cancelled, even if a player suspends them manually — cancellation is irreversible, so that guarantee is hard.
- **The Collection Cache's experience button did nothing.** The real cause was a wrong enum ordinal in the decode path (not the mod id), plus an over-strict detection gate. The semantics are now explicit: experience orbs are converted straight into liquid experience stored in the cache's fluid buffer, at 1 point = 1 mB (nuggets: 3 points each), with graceful fallback.
- **The monitor's Suspend / Resume buttons never rendered.** Action bits are only sent for tasks the client already has a snapshot for, and selecting a task never re-requested one. A read-only re-request was added for tasks without a snapshot, at most once per task per session.

**1.0.0 items (not new in this version)** — these two are already inside the final `v1.0.0` attachment (sha256 identical to the GitHub Release `v1.0.0` asset, built from revision `909bd6c` with `version=1.0.0`). Players upgrading from `1.0.0` need to do nothing about them.

- **Dedicated servers crashed on startup.** `NoClassDefFoundError` on a client-only toast class, because payload registration links payload classes and the verifier must load both sides of an assignment check. Client logic has been moved out of the registration path behind a server-safe indirection layer, all 13 payload classes were switched over, and no `NoClassDefFoundError` catch is used to paper over it. The same class of hazard was hunted down across the project: the new dedicated-server safety check reported 13 files before the fix and 0 after.
- **Log spam, with the `devLogs` master switch already defaulting to off.** A historical total of 320,285 lines from this mod, with peaks of 80 and 128 lines per second. `devLogs` defaults to **off**, and all warnings and errors, the startup fingerprint, session anchors, `diag` exports and the direct consequences of player actions still print. One subtle trap was fixed on purpose: the "no chamber claims this step" timestamp was moved out of the switch, otherwise turning logs off would also kill the monitor banner.

**Attachment replacement (2026-10-11, version number still `1.1.0`)** — The first `1.1.0` upload crashed dedicated servers during mod loading: the `@Redirect` factory handler in `AutocrafterManagerSlotMixin` still declared `Slot` as its return type while its `@At` target had moved to Refined Storage's `PatternSlot`. The handler now returns `PatternSlot`. This was verified on a real headless dedicated server: the old jar reproduces `OUR_MIXIN_FAILURE` with the exact same stack trace, the new jar reaches `Done (1.177s)! For help, type "help"` and is judged `PASS`, with no fatal Mixin or dist markers. The static checker now reports problems separately for the client pass and the dedicated-server pass, validates `@Redirect` handler signatures against real bytecode, and carries a counterexample proving the old check let the bad signature through. The same replacement moved the Refined Storage 2 dependency from `2.0.0` to `2.0.9` (the version players actually run), which exposed one silently inactive mixin (`require = 0`); that mixin is now aligned, it was the only API difference between the two versions, and there were zero Java compile breaks. Because the version number did not change, the `[rscc-build]` fingerprint line is the only way to tell which build you have — please paste it when reporting an issue.

**New capabilities**

- **Jade bus tooltips** showing which chamber a bus is bound to and what it is responsible for, grouped by category, with the fully-automatic input state called out. Display mode is configurable (default: always shown).
- **Create clipboard bulk configuration** for buses: right-click to copy, left-click to paste onto one bus, sneak + left-click to paste onto every same-kind bus in the same cable cluster (up to 64, never across clusters or dimensions). Only this mod's own keys are replaced, and all reading, validation and writing happens server-side.
- **Sneak + wrench right-click dismantles this mod's blocks**, matching Refined Storage's own mechanism, covering all 14 registered blocks via the registry namespace. The removal order is deliberately "drop first, then remove", so content settlement is not skipped.
- **The Range Charger now charges Curios slots** as well as the player's inventory family, with identity-based de-duplication, energy-conservation accounting, and configurable throttling (default 5 ticks).
- **Camouflage can now sheath External Storage Buses**, while the "passable" family is deliberately untouched so ownership detection does not shift.
- **Filter priority**: a bus whose RS filter slots hold something is treated as a plain bus, with the verdict computed live and applied consistently in both the UI and the transport path.
- **The Sequence Assembly Pattern Vault now always shows what each master pattern crafts**, by implementing Refined Storage's own rendering hook — no extra drawing, no tooltip change.
- **Structure caching** (chain members) invalidated by a monotonic structure epoch, so a missed hook can never freeze a stale result. Dynamic quantities are never cached; the memo lives one tick and is never written to NBT.
- **Keeper fluid tooltips** now render the fluid itself with no amount line, large numeric targets display fully with thousand separators, and the geometry is exhaustively checked for overlap.

**Performance**

Remote terminal opening on a large save dropped from 58,221 cost units to 12 (−99.98%), because the opening tick no longer enumerates the network graph, parses every pattern's NBT, or rescans the recipe table; reopening within the TTL does no network enumeration at all. Bus allocation world queries at N=200 dropped from 44,395 to 2,010 (22.1×), turning quadratic growth into near-linear growth.

**Upgrading from `1.0.0`**

No world rebuild, no machine or cable replacement, and no pattern regeneration — this release does not change the pattern NBT layout. Buses previously mis-disabled will recover on their own. Suspended tasks still need a manual Resume. Diagnostic logging now defaults to off, so run `/rs_create_compat devlogs on` before reporting an issue. Requires Minecraft `1.21.1`, NeoForge `21.1.x`, Create `6.0.x`, Refined Storage 2 `2.0.9`, Curios API `9.0.0` and Refined Storage Curios Integration `1.0.0`. JEI, Jade and FTB Ultimine are optional.
