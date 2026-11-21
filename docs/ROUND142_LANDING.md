# 第 142 轮 · 落地说明（1.2.0-beta.43）：保持器 H2 剩余（按最优方案）+ M2 流体默认 64000 mB

> 触发：用户 2026-11-21 15:02 对第 141 轮「需要拍板」两项的回答：
> 1. 保持器 H2 剩余 ——「按最优方案」；
> 2. 保持器 M2 ——「改成 64000mb」；
> 3. 「选择」按钮 ——「目前没有」（没有现场截图，继续挂起）；
> 另：「检查 mcmod 百科提交状态的时候……跳过内容一致性检查」（见 §5）。

## 0. 两项决策（先把「为什么是这个方案」写死）

### 0.1 H2 剩余为什么选「对 RS 隐藏整份能力」

ROUND118 复核给过三个选项：只暴露本机缓冲 / 加开关 / 换实现。本轮按「最优方案」实现为
**换实现：整份对外能力只对第三方物流可见，对 RS 自己的存储聚合隐藏**。理由：

1. **双计有两处，不止镜像**：RS 2.0.9 的外部存储把相邻方块的 `IItemHandler` / `IFluidHandler`
   注册成**网络自己的存储源**。于是
   - `net.get(resource)` 里的「网络镜像」= 网络存量本身 ⇒ 虚高（每虚拟槽最多 +`STORAGE_SLOT_LIMIT`）；
   - **本机缓冲也被算进 `net.get`**，而 `KeeperOverflow.localDestroyAmount` 的池子是
     `net.get + 缓冲余量` ⇒ 缓冲被算两遍，销毁量同样偏多。
   只藏镜像（「只暴露本机缓冲」）只能消掉第一处；藏整份能力两处一起消。
2. **对 RS 来说整份能力都是冗余的**：RS 要读存量、要取资源，直接走网络即可，完全不需要经过
   保持器的能力；隐藏后可达路径一个不少。
3. **对第三方完全不变**：Create 漏斗 / 管道 / 原版漏斗仍能读、仍能抽、仍能送入（设计功能保留）。
4. **加开关**会让「同一个方块在两种配置下存量口径不同」，玩家很难理解，且默认档还是要选一边 ——
   不如把「RS 不该看到的永远看不到」写进实现。

判据实现 = `support/KeeperCapabilityVisibility`：调用栈里出现 `com.refinedmods.refinedstorage`
前缀的类 ⇒ 判定为 RS 自己的存储聚合（`IItemHandler` / `IFluidHandler` 的接口签名不带调用者身份，
能力查询也没有方向 / 来源参数，调用栈是唯一可靠判据）；只看最上面 24 帧，不做全栈遍历。
**隐藏是整份能力**：物品读 / 插入 / 抽取 + 流体读 / `fill` / `drain(resource)` / `drain(int)`
全部在方法第一条判据处短路（两边各 7 处），任一部分泄漏都会让 RS 把它注册成网络存储、双计回归。

### 0.2 M2 为什么是「64000 mB」而不是「默认值改成 64000」

用户口径「改成 64000mb」= **流体 / 气体的默认目标为 64000 mB（64 桶）**；物品默认目标仍是 64 个
（若把配置默认值直接改成 64000，物品默认目标会变成 6.4 万个，明显不是本意）。实现取
`流体默认目标 = 物品默认目标 × 1000`（1 桶 = 1000 mB），默认配置下**恰好 64000 mB**；
改配置 `quantityKeeperDefaultTarget=1000` 时流体默认自动变成 1,000,000 mB（1000 桶），保持同一口径。

- **只动「未改过的默认值」**：目标还等于物品默认值（64）时第一次标流体 / 气体 ⇒ 换成 64000 mB；
  从流体 / 气体改回物品标记且目标还等于 64000 ⇒ 换回 64。玩家手动设过的值（≠ 默认值）不动。
- **旧存档不动**：读档路径（`TargetAmount` / `TAG_CONFIG_TARGET`）不经过这套切换，旧档里已存的
  64 mB 流体目标原样读回，不静默改大；玩家重新标一次才会吃到新默认值。
- 高级版按槽独立套用同一规则（每个槽的目标各自判断）。

## 1. 改动

| 文件 | 改动 |
| --- | --- |
| `support/KeeperCapabilityVisibility.java` | **新增**（纯 JDK，可单独 javac 真跑）：`StackWalker` + RS 包前缀判据 + 24 帧上限；`hiddenFromCaller()` 语义入口 |
| `support/KeeperTarget.java` | **M2**：新增 `FALLBACK_ITEM_DEFAULT_TARGET=64`、`FLUID_MILLIBUCKETS_PER_DEFAULT_UNIT=1000`、`itemDefaultTarget()`（配置未加载回退 64）、`fluidDefaultTarget()`（×1000 后走唯一钳制入口 `clampSetTarget`） |
| `block/entity/QuantityKeeperBlockEntity.java` | **H2**：物品能力（读 / 插 / 抽）+ 流体能力（读 / `fill` / `drain×2`）各在方法首条判据短路；**M2**：字段初值走 `itemDefaultTarget()`，标流体 / 改回物品时按「未改过的默认值」切换 |
| `block/entity/AdvancedQuantityKeeperBlockEntity.java` | **H2**：同上 7 处（物品 3 + 流体 4）；**M2**：构造器初值 + 每槽 `setItemMarker` / `setFluidMarker` 的切换 |
| `tools/selfcheck_round142_keeper_capability_and_fluid_default.py` | **新增自检**：45 断言（含 6 条 JVM 探针 / 前缀改坏反例 / 5 组源码反例自证） |
| `tools/verify_client_boot.ps1` | 顺手修掉探针的两处**证据链缺陷**：① 版本行正则写死 `versions {1.2.0-beta.38}` → `versions {1.2.0-beta.\d+}`（第 139 轮之后一直只靠 `[rscc-*]` 后备判据通过）；② 渲染证据原来接受 `Backend library: LWJGL` —— 那一行在早期窗口创建时就出现（mod 还没构造完），实测会在 mod 列表刚打印时就把客户端杀掉；改为只认**晚段**标记 `Sound engine started` / `Created: 1024x512`。改完重跑客户端探针，客户端真的走到标题界面（Sound engine started）才判 PASS |
| `gradle.properties` / `build_info` | `mod_version=1.2.0-beta.43` |

## 2. 判据（防回退）

- **H2**：类存在 + 前缀 + 帧数上限 + `hiddenFromCaller` 只委托；两边各**恰好 7 处**闸门；
  每个入口的闸门必须是**第一条**判据（先隐藏、后碰本机 / 网络数据）；隐藏分支返回值必须安全
  （读 = 空 / 插入 = 原样退回 / 抽取 = 空 / `fill` = 0）；
  反例：删掉闸门 ⇒ 计数判负；前缀改坏 ⇒ JVM 探针判负。
- **M2**：64 → 64000 mB、1 → 1000、1000 → 1,000,000、配置读 0 回退 64/64000、配置 1e9 / 越界
  都被钳到 1e9；形态切换 6 个用例（新机器拖岩浆 / 改回物品 / 流体改气体 / 玩家设过 128 / 设过 500 mB /
  目标 0）；读档两条路径完全没动；反例：抽掉切换 / 换算改 1:1 / 去掉回退 ⇒ 判据判负。

## 3. 验证（本轮实际值）

| 项 | 结果 |
| --- | --- |
| 新增自检 | `python tools/selfcheck_round142_keeper_capability_and_fluid_default.py` → **45 通过 / 0 失败**（JVM 探针 `PROBE passed=6 failed=0`；前缀改坏副本 ⇒ 探针判负） |
| 全量自检 | **210 脚本 / 0 失败**（`build/round142_selfchecks_final.log`） |
| 独立 javac | `LEAD_COMPILE_OK sources=528`（`tools/_lead_compile.ps1`；RS 2.0.9 去重口径） |
| 构建 | `BUILD SUCCESSFUL in 5s`（`--offline -x test`）；`rs_create_compat-1.2.0-beta.43.jar` = **2,966,422 B**，sha256 `79708ad6517736d4da9642f108cd088cae46284d0b467cac560d9c8365aa4ae1`；`build_info` = `1.2.0-beta.43` / `sources=528` / `builtAt=2026-11-21T15:42:03+0800` / `revision=1dd3924+dirty` |
| 专用服务端真启动 | **PASS**：`Done (1.807s)! For help, type "help"`；`[rscc-build] version=1.2.0-beta.43 … sources=528`；`run/` 前后清单完全一致（只读自证），探针日志 442,186 B |
| 客户端真启动 | **PASS**（修好判据后重跑）：`mods - versions {1.2.0-beta.43}` + **`Sound engine started`**（标题界面音频初始化，晚段标记）+ `Created: 2048x1024`；探针目录 **0 个 crash-report**，无 `Mixin apply for mod rs_create_compat failed`。同批日志里的 Veil 着色器编译 ERROR 与 `let_me_see_see` 的 LMSAgent 异常都是别的模组问题（前者 Intel 核显 + Veil，后者第 137 轮已记录），与本模组无关 |
| 部署 | `DEPLOY_OK`：四处 sha256 全等（`build/libs` / 本地实例 `D:\minecraft\.minecraft\versions\Create：Enjoy\mods` / SFTP `/mods` / SFTP `/client_files/mods`）；beta.42 三处就地 `.disabled`；两个远端目录都断言「恰好只有 beta.43 一个启用 jar」 |

> 探针与部署的关系：服务端 / 客户端真启动用的是 **dev 类**（`build/classes`，与本轮源码同一份），
> 部署的是同一份源码构建出的 jar；探针跑完全量自检后重建 jar（`builtAt` 更新，代码字节不变），
> 再部署并回读四处 sha256。服务端指纹里的 `builtAt=15:18:45` 是探针那一次 `generateBuildInfo` 的时间戳，
> 与部署 jar 的 `15:42:03` 只差时间戳，版本号 / sources 数 / 源码一致。

## 4. 未做 / 边界（如实写明）

1. **没有实机存档复现**：H2 的「外部存储贴保持器 ⇒ 存量虚高」结论来自 RS 2.0.9 源码调用链 +
   双计模型 + 反例自证，没有真的摆一台外部存储读虚高数值；M2 的「拖岩浆看默认 64000」也没有开界面点过。
2. **调用栈判据**是启发式：判定「调用者是 RS」靠包前缀。若未来 RS 换包名 / 换调用路径，
   闸门会失效（自检的 JVM 探针只能证明「前缀命中 ⇒ 隐藏」这条机制，不能证明 RS 未来仍从这些路径调用）。
3. **隐藏是整份能力**：玩家若在保持器旁贴 RS 外部存储，那台外部存储不再把保持器当存储源
   （这正是双计来源，属预期行为）；第三方管道 / 漏斗完全不受影响。
4. **M2 只改「新设置的标记」**：旧存档里已有的 64 mB 流体目标不会自动变，需玩家重新标一次标记。
5. 「高级远程多功能终端偶尔消失的『选择』按钮」仍挂起（用户本轮答复：目前没有现场截图）。
6. 探针收尾会 `taskkill` 客户端进程，日志尾部可能截断在终态之后的一瞬；判定只看是否出现终态标记。

## 5. MC百科提交状态检查（本轮附带，按用户要求跳过内容一致性）

用户要求：检查提交状态时**不比较正文内容**（用户会自己改稿）。工具 `_mcmod_work/check_status_only.mjs`
只读「审核列表」（`https://www.mcmod.cn/verify.html?selfonly=1&order=createtime`），逐行取
状态 / 附言 / 提交时间，**不读也不 diff 草稿正文**，且在新后台标签页里打开（不影响已停住的添加页）。

本轮实测（2026-11-21 15:23）：列表 **50 条** —— 已通过 47 / 审核中 1 / 已退回 1 / 已撤回 1。

| 状态 | 对象 | 说明 |
| --- | --- | --- |
| 审核中（1） | **模组资料**：机械动力&精致存储：兼容与改善 | 21 天前提交、已催审；审核员附言：**「添加了 CFID 但是没有给地址..」**（退回 1 次后重新在审） |
| 已退回（1） | 旧教程：像素图放大教程 | 10 月前，与模组无关（非本轮内容） |
| 已撤回（1） | 物品 1014416 无限分隔框架（旧修订） | 已被后续提交覆盖 |
| 其余 47 | 全部物品资料 | 均已通过（审核人：重生是希望） |

> **注意**：本轮第 141 轮准备好的 8 条新资料（容器代理器 / 网络资源接收器 / 网络资源请求器 /
> 高级归流缓存仓 / 区域选定器 / FE 能量立方 / 电动飞行背包 / 降解室发电机）**还没有提交** ——
> 自动化脚本仍停在第一条「添加资料」页等验证码（`_mcmod_work/add-state.json` 尚未生成 ⇒ 一条都没提交成功），
> 所以列表里看不到它们。等验证码输入后才会进入审核队列。
