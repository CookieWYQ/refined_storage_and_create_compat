# 第 141 轮 · 落地说明（1.2.0-beta.42）：清「不需要产品拍板」的两条遗留 + 收尾核对

> 触发：用户 2026-11-21 14:17 原话「不是还剩下几个问题没修吗？把那几个问题中最重要的那几个给修了，
> 然后呢这个模组差不多就已经开发完毕了。」
> 本轮先**核对清单本身**（有几条其实早就修了，文档列表过期），再修真正剩下且不需要产品拍板的账。

## 0. 先对账：第 138/139/140 轮「未做清单」里哪些是过期条目

| 清单里的条目 | 文档说的 | 代码实际 | 结论 |
| --- | --- | --- | --- |
| `ROUND117` M1：单元样板库只有条数限、没有体积限 | 未做（第 139 轮 §30.4） | 第 118 轮已落地 `support/UnitLibraryLimits`（1 MiB 线上意图 / 8 MiB 堆估算，`fits/remaining/usedWireKb`），`SequencePatternTerminalBlockEntity` / `CreateUnitPatternPacket` 都已接入 | **已修，文档过期** |
| `ROUND117` M1：`SetCollectionBlockedPacket.tags` 四个阻塞标签名单无上限 | 未做（§21.6） | 第 117 轮已落地 `support/CollectionBlockedLimits`（id 1024 / tag 256 / 单包 128 / 单条 128 字符）+ 解码按声明条数消费、只暂存前 128 条 | **已修，文档过期**（只剩「要不要拒绝不存在的 tag」这一产品口径没定） |
| `ROUND120` F2 (b)(c)：跨资源合计超容 / SIMULATE→EXECUTE 竞态 | 未做（第 139 轮 §30.4） | 第 121 轮已落地「执行仓上游缓冲」：`accept(EXECUTE)` 只写本机缓冲且**恒 ACCEPTED**（RS 再也吞不到料），tick 再慢慢转进网络；缓冲落盘 + 拆方块结算 | **已修，文档过期**（只剩「缓冲容量 9 格物品 + 1 罐流体」的容量口径可选） |
| `ROUND125` §4.6 / 48 格 JEI 新 id | — | 第 130 轮（空格子）+ 第 140 轮（已有条目整条替换） | 已修 |
| **保持器 L2：缓存仓无电仍执行存量迁移** | 未做 | `IntermediateCacheBlockEntity#doWork` 直接 `flowOnce`，无 `isActive()` 闸 | **本轮修** |
| **保持器 M1：C2S 目标数量无服务端上限** | 未做 | 两个 setter 只有 `Math.max(0, value)` | **本轮修** |
| 保持器 H2 剩余（外部存储把网络镜像再算一遍 ⇒ 存量虚高） | 未做 | 与「故意暴露网络镜像」是同一设计 | **需产品拍板**（只暴露本机缓冲 / 加开关 / 换实现） |
| 保持器 M2（基础版默认目标 64 + 过量销毁默认开；流体按 mB ⇒ 拖岩浆会按 64 mB 销毁） | 未做 | `QuantityKeeperBlockEntity#destroyOverflow = true`、`targetAmount` 默认 `Config.quantityKeeperDefaultTarget`（对流体 = 64 mB） | **需产品拍板**（默认值口径） |
| 「高级远程多功能终端偶尔消失的『选择』按钮」 | 挂起 | 第 136 轮已源码级排除「按钮被条件跳过」，缺现场截图 | **缺一份现场**（截图 / 界面名） |

> 结论：真正「不需要拍板、又真会出问题」的只剩 L2 与 M1 两条，本轮把它们修掉。
> H2 / M2 是产品口径（改默认值 / 改设计），不擅自改；「选择」按钮需要一张现场图，不盲改。

## 1. 改动

| 文件 | 改动 |
| --- | --- |
| `block/entity/IntermediateCacheBlockEntity.java` | **L2**：`doWork()` 在节流之前加 `node == null || !node.isActive()` 闸门；未激活时 `flowCooldown` 不推进（重新上电第一 tick 就能搬，不用再等 20 tick）；`flowOnce` 复用同一个 `node`（不再二次 `getNode()`） |
| `support/KeeperTarget.java` | **M1**：新增 `MAX_SET_TARGET = 1_000_000_000`（与 `Config.quantityKeeperDefaultTarget` 的 `defineInRange(..., 1, 1000000000)` 同口径，不另造数字）+ 唯一钳制入口 `clampSetTarget(int)`（`[0, MAX_SET_TARGET]`） |
| `block/entity/QuantityKeeperBlockEntity.java` | **M1**：`setTargetAmount` 改走 `KeeperTarget.clampSetTarget` |
| `block/entity/AdvancedQuantityKeeperBlockEntity.java` | **M1**：`setTarget(slot, value)` 改走 `KeeperTarget.clampSetTarget` |
| `tools/selfcheck_round141_remaining_closure.py` | 新增自检：**26 断言 / 4 组反例自证**（见 §2） |

两条都遵守工程既有纪律：**只拦「增长」**——读档不经过闸门（旧存档里超限的目标值原样读回，不静默改小；
旧存档里未激活的缓存仓也不会丢任何缓存内容，只是「不上电不搬」）。

## 2. 判据（防回退）

- **L2**：闸门必须存在、必须在 `flowOnce` 之前、必须在节流之前；`flowOnce` 必须用同一个 `node`；
  反例：删掉闸门 ⇒ 判据判负。
- **M1**：`MAX_SET_TARGET` 与配置 `defineInRange` 上限一致；两个 setter 都走 `clampSetTarget`；
  setter 里不许再出现旧的 `Math.max(0, …)`；读档路径（`tag.getInt("TargetAmount")`）不经过钳制；
  行为模型 9 个取值（负值 / 0 / 1 / 64 / 100 万 / 恰好 1e9 / 1e9+1 / 20 亿 / `Integer.MAX_VALUE`）；
  反例：setter 改回旧写法、钳制只留上限不留下限 ⇒ 判据判负。

## 3. 验证（本轮实际值）

| 项 | 结果 |
| --- | --- |
| 新增自检 | `python tools/selfcheck_round141_remaining_closure.py` → **26 通过 / 0 失败** |
| 全量自检 | **209 脚本 / 0 失败**（`build/round141_selfchecks_final.log`） |
| 构建 | `BUILD SUCCESSFUL in 59s`（`--offline -x test --console=plain`，`build/round141_gradle_build.log`）；`rs_create_compat-1.2.0-beta.42.jar` = **2,964,309 B**，sha256 `c119a893cfa522266b7763023f98044d889a275fbb1a70131192837d765a7124`；`build_info` = `1.2.0-beta.42` / `sources=527` |
| 专用服务端真启动 | **PASS**：`Done (1.913s)! For help, type "help"`；`[rscc-build] version=1.2.0-beta.42 revision=1dd3924+dirty sources=527 mc=1.21.1 neoforge=21.1.248 java=21.0.10`；`run/` 前后清单完全一致（只读自证）；日志 442,181 B（`build/dedicated_server_probe.log`） |
| 客户端真启动 | **PASS**（`build/client_probe_stdout.txt` → `CLIENT_BOOT_VERDICT=PASS`）：`Found valid mod file main with {rs_create_compat} mods - versions {1.2.0-beta.42}` + `Backend library: LWJGL version 3.3.3+5`（渲染初始化完成）；探针目录 0 个 crash-report。同批日志里的 `let_me_see_see` `ClassNotFoundException: LMSAgent` 是第 137 轮已记录的第三方模组问题，与本模组无关 |
| 部署 | **四处 sha256 全等**（build/libs / 本地实例 `D:\minecraft\.minecraft\versions\Create：Enjoy\mods` / SFTP `/mods` / SFTP `/client_files/mods`）；beta.41 三处就地 `.disabled`；`tools/_deploy_round141.py` → `DEPLOY_OK`，两个远端目录都断言「恰好只有 beta.42 一个启用 jar」 |

## 4. 未做 / 边界（如实写明）

1. **没有实机存档复现**：两条结论都来自源码调用链 + 行为模型 + 反例自证，没有在游戏里断电看缓存仓停搬、
   也没有改包发一个 21 亿的目标值看服务端夹回 10 亿。
2. **保持器 H2 剩余 / M2 未动**：都是产品口径（是否把网络镜像暴露给相邻方块 / 是否改基础版默认值），
   需要用户拍板后再改，不擅自改玩法语义。→ **第 142 轮已拍板落地**：H2 按「最优方案」改为
   「整份对外能力对 RS 隐藏」（`docs/ROUND142_LANDING.md`），M2 流体默认目标改为 64000 mB。
3. **「选择」按钮**仍未修：第 136 轮已排除「按钮被条件跳过」，需要复现时的一张截图或界面名。
4. 第 139 轮部署时发现的「远端旧 jar 仍启用」问题已由部署脚本的「停用所有非新 beta jar + 断言每目录只剩一个启用」兜住，
   本轮沿用。
