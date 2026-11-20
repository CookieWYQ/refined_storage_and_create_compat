# 第 137 轮落地记录（1.2.0-beta.38）

日期：2026-11-14　触发：用户提供的远程服务端崩溃 `crash-2026-11-14_22.43.50-server.txt`
（`D:\SEVER_MAKEING_WORKSPACE\Create：Enjoy\SERVER\`），以及一份流传的分析「`EnergyCubeCluster.collect`
第 299 行死循环」。

## 0. 结论先说

**不是死循环，但确实是我们的锅 —— 错在「读世界的方式」，而且正好被看门狗判死。**

- `collect` 的 BFS 本身**可证有界**：循环条件 `!queue.isEmpty() && found.size() < MAX_MEMBERS(64)`，
  每次入队都先过 `visited.add` ⇒ 单次重算最多 64 个成员、≤ 385 次出队、≤ ~2300 次邻格判定。
  它不可能自己转 60 秒。
- 崩溃栈尖 `collect(:299)` 是 `visited.add(neighbor)` —— 看门狗 dump 线程栈时线程**恰好在**这里；
  在同一条栈上，往下一层是 `rebuild(:262)` → `resolve(:255)` → `snapshot(:346)`
  → `getClusterEnergyStored(:326)` → `ClusterEnergyCapability.getEnergyStored(:835)`
  → `create_simple_generator: StressGeneratorEntity.extractPowerTo(:109)` / `tick(:47)`。
- 真正的问题：1.21.1 的 `Level#isLoaded` 对「已在加载流水线里、但**还没 FULL**」的区块**同样返回 true**，
  而 `Level#getBlockState` 内部走 `getChunk(cx, cz, FULL)`（require = true）——
  **会在主线程同步等这个区块生成**。旧的 `isCubeBlock` 正是「先 `isLoaded` 再 `getBlockState`」，
  所以那道闸门根本拦不住它。

## 1. 现场证据链（为什么不是「一次 60 秒死循环」）

看门狗的判据是「`nextTickTimeNanos` 落后现在 ≥ 60 秒」，**不是**「单个 tick 的耗时」：

`MinecraftServer.runServer` 每轮先 `nextTickTimeNanos += 50ms` 再 tick；tick 慢，落后就累积。
而「Can't keep up」的追赶有闸门：`nextTickTimeNanos - lastOverloadWarningNanos >= 10s + 100×50ms = 15s`
才允许打印并追赶 —— 也就是说，**持续慢 tick 时，落后会在闸门打开前先涨到 60 秒，看门狗直接判死**。

本次崩溃的数字完全对得上：

| 项 | 值 |
|---|---|
| 最后一次「Can't keep up」 | `22:42:36.308`（`Running 4173ms or 83 ticks behind`，随后追赶重置） |
| 看门狗报的落后 | `60.011432 s` ⇒ 被判的 `nextTickTimeNanos` = `22:42:46.34` |
| 从重置到判死 | `70.04 s`，其间 `nextTickTimeNanos` 只推进了 **10.03 s = ~200 tick** |
| 换算 | 200 tick / 70 s ≈ **350 ms/tick**（持续超载，不是单 tick 卡死） |

若真是「单个 tick 卡住 60 秒」，`22:42:46 → 22:43:46` 之间主线程不可能有任何日志；但日志里
`22:42:53`、`22:43:07`、`22:43:30` 全是 **Server thread** 的行（打开终端包处理、归流缓存仓 tick 日志）。
⇒ 那段时间 tick 一直在跑，只是每个都很慢。看门狗 70 秒后按「落后 60 秒」判死，dump 恰好落在我们的 `collect`。

## 2. 为什么是我们的锅：350 ms/tick 从哪来

`22:42:33` 玩家登录 ⇒ 服务器开始在玩家附近生成 / 加载区块（数百个 chunk 同时在流水线里）。
我们的能量立方集群就在基地里；`create_simple_generator` 的发电机每 tick 查一次立方体的
`getEnergyStored()`，于是**每 tick** 都会走 BFS。BFS 每扫到一个「在流水线里但还没 FULL」的邻块，
`Level#getBlockState` 就让主线程**同步等这一块生成完**（还会加 `UNKNOWN` 票据，继续拉区块）。

结果：主线程一次等几百毫秒、一个接一个，tick 时间被拉到 350 ms，持续到看门狗判死。
这也解释了为什么两次「Can't keep up」都发生在登录 / 开服后的区块加载窗口里，而平时没有。

## 3. 修法（`support/EnergyCubeCluster.java`）

| 项 | 改动 |
|---|---|
| 读区块 | 新增 `readyChunkAt()`：`level.getChunkSource().getChunkNow(cx, cz)` —— **只返回已 FULL 的区块**，不加载、不加票据、不阻塞；`isOutsideBuildHeight` 先行 |
| 读方块 | `isCubeBlock` / `collect` 改为 `LevelChunk#getBlockState(pos)`（纯读，绝不加载）；**全文件不再出现** `level.isLoaded(` / `level.getBlockState(` / `level.getBlockEntity(` |
| 取方块实体 | `cubeAt` 改为 `chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)` —— 只取已存在的，不创建 |
| 未就绪区块 | BFS 遇到「`hasChunk` 为真、`getChunkNow` 为 null」的邻格 ⇒ 记 `partial`，本次不并入；`partial` 快照 **永不视为 fresh** ⇒ 下次 `resolve` 重算（区块就绪后自动补上，不会永久少算） |
| 每 tick 预算 | `MAX_REBUILDS_PER_TICK = 8`：纯读路径（能力查询 / 界面）同一 tick 最多重算 8 次，超出退化「上次快照 / 只报自己」—— 读路径永远不可能拖死 tick |
| 实机证据 | 单次重算 ≥ `SLOW_REBUILD_NANOS(20ms)` 记一条 WARN（origin / members，5 分钟冷却）：`[rs_create_compat] 能量立方集群重算耗时 N ms …` |

语义不变：电量仍各存自己、拆块零迁移、取电按字典序跨成员扣；慢路径下退化为单成员视图只影响
「这一次读到的数」，不做任何错误写入。

## 4. 自检

`tools/selfcheck_round137_energy_cluster_safe_read.py` —— **18 条断言 / 0 失败**：

1. 源码锚点：`getChunkNow` 在；`level.isLoaded(` / `level.getBlockState(` / `level.getBlockEntity(`
   三条危险读**一条都不在**；`CHECK` 取方块实体；注释写清机制（防止后人删闸门）；
2. 有界性锚点：BFS 的 `found.size() < MAX_MEMBERS` 仍在、预算与退化分支仍在、`partial` 传播到位、慢重算 WARN 在；
3. 调用链锚点：崩溃栈那条链（能力 → `getClusterEnergyStored` → `snapshot` → `resolve`）仍在走本类；
4. 反例自证：把旧写法（`isLoaded` + `getBlockState`）、删掉预算分支、把 `getChunkNow` 换成会加载的
   `getChunk` 三种写法拼回去，判据必须判负。

## 5. 未做 / 已知边界

- **没有实机复现**：本机没有这份存档的运行实例；「350 ms/tick 是区块同步等待造成的」是从
  源码语义 + 崩溃数字 + 日志时间线推出来的，不是火焰图。beta.38 起若再出现同类慢 tick，
  日志里会带 `能量立方集群重算耗时 … ms`；若那条 WARN 出现，即证明这条读路径仍在被拖。
- **同类隐患仍在别处（本轮未动）**：`RsccMachineCluster` 的 `isLoaded` + `getBlockState`、
  以及第 119 轮给 `CollectionCacheBlockEntity#fluidSourceIdAt` 加的 `isLoaded` 闸门，都是同一种
  「拦不住未 FULL 区块」的写法。它们不在这次崩溃的栈上，改动面大（机器集群的语义依赖
  「已加载 = 可参与」），留待单独一轮，先出审计清单。
- **`isLoaded` 的语义来自 1.21.1 反编译源码**：`ServerChunkCache#hasChunk` = 「holder 存在且
  ticketLevel ≤ FULL 档」，因此正在生成的区块也算「已加载」。这是本轮判断的基石，已记录在
  `docs/ROUND137_LANDING.md` §1–§2。

## 6. 验证（2026-11-14 23:00–23:35，本机）

| 项 | 结果 |
|---|---|
| 独立 javac（不信任 gradlew 结论） | `LEAD_COMPILE_OK sources=526` |
| 新增自检 `selfcheck_round137_energy_cluster_safe_read.py` | 18 断言 / 0 失败 |
| **专用服务端真启动**（`tools/verify_dedicated_server.ps1`） | **PASS**：`Done (2.499s)!`，日志带 `[rscc-build] version=1.2.0-beta.38 … sources=526`，exit 0 |
| **客户端真启动**（新增 `tools/verify_client_boot.ps1`） | 第 1 次 **OUR_FAILURE**（见 §7，真抓到问题）；修复后重跑 **PASS**（exit 0） |
| 全量自检 | 205 脚本 / 0 失败（`round99` / `round124` 的锚点已同步到新的 `ScanResult` / `LevelCache` 形状，判据未放宽） |
| 构建 | `BUILD SUCCESSFUL`，`rs_create_compat-1.2.0-beta.38.jar` |
| 部署 | 四处 sha256 全等 `ae3119eb…b2bafb38`（build/libs / 本地实例 / SFTP `/mods` / `/client_files/mods`），beta.37 在三处 `.disabled` |

### 6.1 新增：客户端真启动探针（用户「服务端要测一遍、客户端也要测一遍」）

`tools/verify_client_boot.ps1` + `tools/client_probe.init.gradle`，与服务端探针同构：

- 独立游戏目录 `build/client_probe/game`（`run/` 只读，前后各拍一次文件清单自证 0 变化）；
- init script 改 moddev 的 `client` run 的 `gameDirectory`（只改 `workingDir` 不管用，同服务端教训）；
- 复制 `run/options.txt`（关键是 `onboardAccessibility:false`，否则会停在无障碍引导界面等人点按钮）；
- 轮询日志判定四态：**PASS**（`[rscc-build]` + 渲染初始化标记）/ **OUR_FAILURE**（本模组 Mixin /
  类加载失败，或 crash-report 里出现本模组包名）/ **INCONCLUSIVE**（别人的前置 / GLFW / 显卡）/
  **TIMEOUT**；终态后 `taskkill /T` 结束整棵进程树，绝不用「进程还活着」当通过。

## 7. 客户端探针第一次运行就抓到真问题（已修）

**现场**（`build/client_probe/game/crash-reports/crash-2026-11-14_23.16.55-client.txt`）：

```
java.lang.AssertionError: Missing config translation: config.jade.plugin_rs_create_compat.proxy
    at snownee.jade.JadeClient.onGui(JadeClient.java:175)
```

**根因**：第 108 轮新增的「容器代理器 / 内部缓存」两条 Jade provider（UID `rs_create_compat:proxy`
与 `rs_create_compat:proxy_data`）只加了代码，**没加语言键**。Jade 用断言校验「每个注册 UID 都有
`config.jade.plugin_rs_create_compat.<uid>`」，而 Gradle 的 JavaExec 默认开断言（`-ea`）⇒
开发环境客户端在标题界面初始化时**直接崩**；正式环境不崩，但 Jade 配置界面会显示原始 key。

**修法**：`en_us.json` / `zh_cn.json` 各补两条（`proxy` = 容器代理器内容 / `proxy_data` = 容器代理器内容数据）；
新增 `tools/selfcheck_jade_lang_keys.py`（19 断言：从 `client/jade/`、`compat/jade/`、`support/*Jade*.java`
解析**所有真实注册的 UID**（含常量引用），逐个断言两个语言文件都有键；反例自证 = 删掉 `proxy` 键后
聚合判据必须判负）。这一条是「客户端真的跑一遍」才可能发现的，编译期 / 服务端视角都看不到。

**修复后重跑（第 3 次运行，最终版）**：`CLIENT_BOOT_VERDICT=PASS`，exit 0。证据：
加载器 `mods - versions {1.2.0-beta.38}`（本模组已加载）、`Backend library: LWJGL 3.3.3+5` /
`Sound engine started`（渲染初始化完成）、**本次没有新 crash-report**、`run/ 前后清单 0 变化`。
同批日志里仍有 `let_me_see_see` 的 `ClassNotFoundException: LMSAgent`，那是第三方模组的
dev 环境问题（与 `run/mods` 里的那个 jar 有关），不影响判定。

**探针自身在三次运行里修掉的三个坑**（写下来防止下一轮重复踩）：
1. `cmd /c ... > log 2>&1` 的日志被**独占打开**，`ReadAllText` 抛「正由另一进程使用」⇒ 必须像
   服务端探针那样用 `File::Open(..., FileShare.ReadWrite)` 只读尾部（否则探针永远读不到日志、
   只会超时）；
2. 客户端首次启动停在「无障碍引导」界面等人点按钮 ⇒ 从 `run/options.txt` 复制
   `onboardAccessibility:false` 过去，探针才能无人值守走到标题界面；
3. 探针目录里**上一次**留下的 crash-report 会让后续每次运行都误判 `OUR_FAILURE` ⇒ 只统计
   「启动前不存在」的新报告，并把旧报告留档到 `build/client_probe_evidence/`。

## 8. 关于用户给的 `yan-mc/minecraft-mod-dev`

已核对（2026-11-14，仓库 `main` @ `bb5be3bc`，release `v0.5.1`）：它是一个 **DSH 插件 + MC 1.20.1
Forge 47.4.x / Java 17 探针**，`docs/STATUS.md` 明确写「仅 Windows + MC 1.20.1 + Forge 47.4.x +
Java 17 + DSH 0.2.1-rc.2」「探针 Mixin 是 dev-only」「只在 `lab/forge-1.20.1-test` 实测」。
本工程是 **1.21.1 NeoForge 21.1.248 / Java 21**，其 `mcdev_probe-0.1.0-dev.jar`（1.20.1 Forge dev-mapped）
**无法加载**到本环境；本会话也没有可安装 DSH 插件的 `plugin_manager` 工具。⇒ **不能直接使用**。

但它的方法论（「真的启动服务端 / 客户端 + 探针读取 + 终态判定」）本轮已经用自家实现落地：
服务端探针是既有的 `verify_dedicated_server.ps1`，客户端探针是本轮新增的 `verify_client_boot.ps1`。
若后续需要「客户端里点 UI / 截图 / 读控件树」，再单开一轮做 1.21.1 NeoForge 版探针模组；
本轮不引入，避免把一次崩溃修复变成跨版本移植工程。

