# 第 140 轮 · 落地说明：48 格窗口 JEI 拖入「新 id 被忽略」——已有条目整条替换

> 触发：用户 2026-11-21 08:21 问「48 格窗口 JEI 拖入『新 id 被忽略』这个你修了吗」。
> 如实回答：**第 130 轮只修了空格子那一半，这一半没修**（`ROUND125` §4.6 一直挂着）。
> 本轮把它修掉并补上判据。

## 0. 问题与历史分层

`ROUND125` §4.6 记的「高级仓 48 格窗口 JEI 拖入 → 新 id 被忽略」其实是**两个**症状，
第 130 轮修了前一个，后一个一直没动：

| 症状 | 触发路径 | 状态 |
| --- | --- | --- |
| 拖到**空格子**上什么都不发生（静默丢包） | `applyMarkerConfig` → `setAreaMarkerConfig` 找不到条目就 `return false` | 第 130 轮已修（`addAreaMarkerEntry` + `notifyMarkerResult`） |
| 拖到**已有条目**的格子上，拖进来的 id / 数据组件被丢掉（只更新数量 / 规则，旧 id 保留） | 同一个 7 参覆写只调 `setAreaMarkerConfig`（按 id 位次改规则、**不接受 item id**） | **本轮修** |

基类 `CollectionCacheBlockEntity#setMarkerConfig` 的口径本来就很清楚：
「同 id ⇒ 保留旧 nbt、只 update 数量 / 规则；异 id ⇒ **替换该下标**」。
高级版 7 参覆写只做了前一半，所以「把另一个物品拖到这个格子」时服务端什么都没换。

## 1. 改法

| 文件 | 改动 |
| --- | --- |
| `block/entity/AdvancedCollectionCacheBlockEntity.java` | 新增 `replaceAreaMarkerEntry(areaIndex, markerIndex, marker)`：只允许替换**已有条目**的格子（空格子仍归 `addAreaMarkerEntry`）；去重时**跳过本下标自身**（否则替换自己会被误判 `DUPLICATE`）；单条体积 `MarkerBudget.isEntryTooBig` + 整表预算 `AreaMarks.isOverBudget(selectorStack(), area, next)` 与新增逐字同口径；`next.set(markerIndex, marker)` 替换、条数不变 |
| `menu/AdvancedCollectionCacheMenu.java` | 7 参覆写：`id / fluid` 与旧条目不同 ⇒ 用包里的 id + 组件补丁 + 数量 + 规则构 `MarkerEntry` 走 `replaceAreaMarkerEntry`，失败走 `notifyMarkerResult`（`DUPLICATE` / `FULL` / `TOO_COMPLEX` 有 actionbar 文案）；`id == null` ⇒ 与基类同义清空该下标；同 id 仍走原来的 `setAreaMarkerConfig`（只改规则） |
| `tools/selfcheck_round140_advanced_jei_replace.py` | 新增 14 断言 / 4 组反例自证（见 §3） |

客户端（`CollectionCacheJeiPlugin`）**不需要改**：它本来就把 `BuiltInRegistries.ITEM.getKey(...)`
与 `MarkerEntry.encodeComponents(...)` 放进了 `SetCollectionMarkerConfigPacket` ——
丢 id 的是服务端高级版这条覆写，不是 JEI 通道。

## 2. 判据（防回退）

- 菜单里「异 id 分支」必须存在，且位置在「空格子新增分支」之后、同 id `setAreaMarkerConfig` 之前；
- 替换调用必须包在 `notifyMarkerResult(...)` 里（失败不能静默）；
- 数据层四道闸门：条目合法性 / 下标越界 / 去重跳过本下标 / 预算（单条 + 整表）；
- 反例自证：把旧「异 id 也只 update」拼回去、去掉「跳过本下标」、去掉整表预算、把「替换」写成「追加」，
  判据必须全部判负。

## 3. 验证

- 新增自检：`python tools/selfcheck_round140_advanced_jei_replace.py` → **14 通过 / 0 失败**
  （含 4 组反例自证：旧「异 id 也只 update」/ 去掉「跳过本下标」/ 去掉整表预算 / 把替换写成追加）；
- 相关既有自检（本轮改动后重跑）：`round114` 99 项 / `round101` 47 项 / `round112` 100 项 /
  `round130` 77 项（含 23 反例）全部 0 失败；
- 全量自检：**208 脚本 / 0 失败**（`build/round140_selfchecks.log`）；
- 独立 javac：`LEAD_COMPILE_OK sources=527`（不信任 gradlew 的结论）。

## 4. 构建 / 探针 / 部署

| 项 | 结果 |
|---|---|
| 构建 | `BUILD SUCCESSFUL in 7m 17s`；`rs_create_compat-1.2.0-beta.41.jar` = **2,964,121 B**，sha256 `794df8d6399aeee41b07f69c5a21d431f98dec6dc919b00a60af734ab69e75fe`；`build_info` = `1.2.0-beta.41` / `sources=527`（`build/round140_gradle_build.log`） |
| 专用服务端真启动 | **PASS**：`Done (1.777s)! For help, type "help"`；`[rscc-build] version=1.2.0-beta.41 revision=1dd3924+dirty sources=527 mc=1.21.1 neoforge=21.1.248 java=21.0.10`；`run/` 前后清单完全一致；日志 442,188 B（`build/dedicated_server_probe.log`） |
| 客户端真启动 | **PASS**：`Found valid mod file main with {rs_create_compat} mods - versions {1.2.0-beta.41}` + `Backend library: LWJGL version 3.3.3+5`（渲染初始化完成）；0 个 crash-report；日志 625,275 B（`build/client_boot_probe.log`） |
| 部署 | **四处 sha256 全等 `794df8d6…e75fe`**（build/libs / 本地实例 `D:\minecraft\.minecraft\versions\Create：Enjoy\mods` / SFTP `/mods` / SFTP `/client_files/mods`）；beta.40 在三处就地 `.disabled`；`tools/_deploy_round140.py` → `DEPLOY_OK`，两个远端目录都断言「恰好只有 beta.41 一个启用 jar」 |

## 5. 边界

1. **没有实机存档复现**：结论来自 1.21.1 源码调用链 + 基类 / 高级版覆写口径对比
   （JEI 通道发的包本来就带 id）。
2. 区域过滤器目前只支持**物品**标记：7 参覆写里 `fluid` 仍按旧口径交回基类（写全局匹配区），
   本轮没有扩大这个边界 —— 没修「把流体拖到高级版 48 格」的可见性问题（那是另一条产品决策）。
3. 拖到已有标记载荷的格子时如果拖进来的 id 已经存在于该区域**别的下标**，按
   `addAreaMarkerEntry` 同口径返回 `DUPLICATE` 并提示（不会制造两条同 id 标记）。
4. 「选择」按钮与第 138/139 轮清单其余项继续挂起。
