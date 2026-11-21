# 第 139 轮落地（1.2.0-beta.40）：带组件流体真的能取出来 + 「>288 条」不再静默丢配置

本轮只做两件**会真丢功能 / 丢数据**的事，都是第 138 轮「未做清单」里用户点名的那两条
（用户原话 2026-11-21 00:12：「可以」——同意按「先 L3、再 >288」的顺序开下一轮）。

---

## 1. A：带组件流体「取不出来」（第 118 轮复核 L3）

### 1.1 现象 / 根因（三处不对称，缺一不可）

两台定量保持器的流体能力（`IFluidHandler`）里，**同一份带数据组件补丁的流体**在
「存进去」与「取出来」两侧不是同一个键：

| # | 位置 | 旧写法 | 后果 |
|---|---|---|---|
| 1 | `drain(FluidStack)` 的组件编码 | `MarkerEntry.encodeComponents(patch)`（**不带**注册表上下文） | 与 `fill`（带 `level.registryAccess()`）可能编出不同 NBT ⇒ 按键查本机条目查不到 |
| 2 | `getFluidInTank` / `drainById` 的返回值 | `new FluidStack(fluid, amount)`（**组件补丁被丢掉**） | 外部管道拿到「裸流体」，再拿它回来 `drain` 时按「无组件」查本机的带组件条目必然落空；即便取出成功，变体数据也被当普通流体送出去 |
| 3 | 本机查不到时的网络回退 | 只比流体 id（基础版） | 请求**别的变体**也会把标记流体当作请求流体交还（变体错乱） |

三者叠加的可见结果就是用户报告的那类问题：**本机缓冲里明明有带组件的流体，管道/机器却一滴都
抽不走，日志没有任何一句话**（静默回退网络；网络里恰好也没有该变体时就是彻底「取不出来」）。

### 1.2 修法

- **唯一编码上下文**：两台保持器各加 `registryHint()`（= `level == null ? null : level.registryAccess()`）。
  `fill` / `drain` / 入网 / 仲裁 / 存储回流**全部**走它 —— 同一个补丁在同一个上下文里编解码，
  键逐字相同（自检按「恰好 2 处 `encodeComponents(patch, registryHint())`」钉住）。
- **返回值一律带组件**：基础版新增 `toFluidStack(fluid, amount, nbt)`、高级版新增
  `stackOf(id, nbt, amount)`，`getFluidInTank` / `drainById` / `drain(int)` 的网络回退全部改走它们
  （`FluidStack(Holder<Fluid>, int, DataComponentPatch)`）。
- **网络回退收紧**（基础版）：仅当「请求的 id **且** 组件 NBT 与直接流体标记完全一致」才回退，
  交还的 FluidStack 带标记的组件补丁 —— 不再把标记流体冒充成请求变体。
- 顺手把高级版里 `networkResourceKey(slot)` / `tickStorage` / `extractNetworkFluidRaw` 的
  `level == null ? null : level.registryAccess()` 也统一到 `registryHint()`（同一轮同一处判据，避免下次又分叉）。

### 1.3 边界（如实写明）

- 只改「组件补丁的携带与编码上下文」，**不改**标记匹配口径（`matchesFluidMarker` 仍按流体 id）——
  标记变更后遗留的其它变体仍可照旧取出，不因本轮收口而被拒收；
- 旧存档里已经写进 `MultiFluidCache` 的条目**不需要迁移**：条目存的本来就是 NBT 键，本轮只是
  让「请求侧」也能算出同一个键；
- `CollectionCacheBlockEntity` 的流体能力也做了核对：它的 `fill` / `drain` 两侧**都用**
  `encodeComponents(patch)`（同上下文），彼此自洽，不属于本次三处不对称；本轮不动它（避免改坏
  已落盘条目的键），只在文档里留下这条「同源但自洽」的结论。

---

## 2. B：「>288 条」不再静默截断（第 114/115 轮复核 L1）

### 2.1 现象

每区域过滤标记上限 `CollectionCacheBlockEntity.MATCH_CAPACITY = 288`（48 格 × 6 页）。
`AreaSelectorEditPacket` 的 REPLACE（改区域几何）会把**旧几何的标记搬到新几何**；如果新几何上
**本来就有标记**（这块地以前配过），两块地合并后可能超过 288 条。旧实现是：

```java
// 旧：先换几何，再把合并结果交给 put —— 超限部分被 sanitizeEntries 截掉
if (merged.size() > MATCH_CAPACITY) { LOGGER.warn("… 已按上限截断 …"); }
AreaMarks.put(target, newArea, merged);
```

尾部被截掉的标记**永久消失**，而 `AreaMarks.put` 本身（数据层兜底）在其它调用点被截断时
**一个字都不打**（`sanitizeEntries` 静默 `break`）。

### 2.2 修法

1. **REPLACE 改为「先算、再判、最后才写」**：
   - 合并逻辑抽成纯函数 `mergeAreaMarks(existing, carried)`（新几何标记在前、旧几何按
     `(类别, id)` 去重追加、先去空 —— 与旧实现逐字同语义）；
   - 合并结果超过 288 条 ⇒ **在 `AreaSelection.set`（唯一写入点）之前**
     `reject(rejectMenu, player, packet, REJECT_MARK_OVERFLOW)` + `return`：
     旧区域、旧标记、新几何上原有标记**一个字节都不动**，玩家删掉一些标记再来；
   - 通过校验的合并结果在 `switch` 里暂存（`pendingAreaMarks`），写回阶段只写「确定装得下」的那一份。
2. **拒绝原因可见**：新码 `REJECT_MARK_OVERFLOW = 6`（网络契约只增不改），
   `gui.rs_create_compat.area_selector.err.reject.mark_overflow` 进 zh_cn / en_us；
   主手路径走聊天提示、菜单路径走 `AreaStripPanel#handleServerReject` 的同一句文案。
3. **数据层兜底不再静默**：
   - `AreaMarks.put`：`countNonEmpty(entries) > MATCH_CAPACITY` 时打 WARN（写入前，说明保留前 N 条、丢 N 条）；
   - `AreaMarks.migrateByIndex`：旧表某区域超过 288 条时打 WARN（迁移是「写完就消费」的一次性动作，
     不告警就等于蒸发）。

### 2.3 边界（如实写明）

- 正常玩法入口（新界面加标记 / 机器 48 格窗口 / 标记包）本来就会先查容量并给出「已满」提示，
  本轮改的是**合并 / 迁移 / 改包**这三条能绕过前端的路径；
- 选择「拒绝改几何」而不是「尽量多搬」：任何一种「搬一部分」都必然丢配置，
  而拒绝只让玩家多做一步（先删标记），且**旧数据零改动**；
- `AreaMarkEditPacket` 的 ADD 在满 288 条时仍是「服务端静默忽略」的兜底（客户端
  `AreaMarkScreen` 已先拦并提示「已满」）；本轮没有改它 —— 它不丢任何已有数据。

---

## 3. 自检

新增 `tools/selfcheck_round139_fluid_components_and_mark_cap.py`：**36 条断言 / 0 失败**：

| 组 | 内容 |
|---|---|
| ① 基础保持器 | 返回值带组件 / fill+drain 同上下文（恰好 2 处）/ drainById 带补丁 / 严格网络回退 / 裸 FluidStack 清零 |
| ② 高级保持器 | 每槽 tank 走 `toStack` / `stackOf` / 网络回退带组件 / 同上下文 / 裸 FluidStack 清零 |
| ③ 行为模型 | 新路径命中；旧「丢组件」与「上下文不一致」两条路径都判「取不出来」；无组件流体不误伤 |
| ④ >288 条 | `put` / `migrateByIndex` 截断告警；REPLACE 拒绝顺序在 `AreaSelection.set` 之前；文案键三处（代码 / 面板 / zh+en）；200+100 拒绝 / 200+80 放行 / 恰好 288 放行 / 完全相同去重后放行 |
| ⑤ 反例自证 | 旧基础版 drain / 旧高级版 toStack / 旧 put（无告警）/ 旧 REPLACE（先 set 再截断）/ 去掉容量闸门 / 去掉 put 告警 —— 判据全部判负 |

既有自检同步：`tools/selfcheck_round115_cross_machine_budget.py` 的 ⑤ 组按新形状更新
（第 115 轮是「补 WARN」，第 139 轮起是「拒绝写入」；判据没有放松，反例照旧判负）。

---

## 4. 构建 / 探针 / 部署

| 项 | 结果 |
|---|---|
| 独立 javac（不信任 gradlew 结论） | `LEAD_COMPILE_OK sources=527`（`build/round139_lead_compile.log`） |
| 构建 | `BUILD SUCCESSFUL in 6m 47s`；`rs_create_compat-1.2.0-beta.40.jar`（**2,963,545 B**，sha256 `c4eac5d76c356f3b81eb9afce3172de21b0857a6fae4f0b20a00975c879dd36c`）；`build_info.properties`：`modVersion=1.2.0-beta.40` / `sources=527`（`build/round139_gradle_build.log`） |
| **专用服务端真启动** | **PASS**：`Done (2.590s)! For help, type "help"`；启动日志里有 `[rscc-build] version=1.2.0-beta.40 revision=1dd3924+dirty sources=527 mc=1.21.1 neoforge=21.1.248 java=21.0.10`；`run/` 前后清单完全一致（只读自证）；日志 442,140 B（`build/dedicated_server_probe.log`） |
| **客户端真启动** | **PASS**：`Found valid mod file main with {rs_create_compat} mods - versions {1.2.0-beta.40}` + `Backend library: LWJGL version 3.3.3+5`（渲染初始化完成）；`run/` 前后清单 0 变化；日志 625,138 B（`build/client_boot_probe.log`） |
| 全量自检 | **207 脚本 / 0 失败**（新增的本轮自检 36 断言 / 0 失败也在其中；`build/round139_selfchecks_final.log`） |
| 部署 | **四处 sha256 全等 `c4eac5d7…dd36c`**（build/libs / 本地实例 `D:\minecraft\.minecraft\versions\Create：Enjoy\mods` / SFTP `/mods` / SFTP `/client_files/mods`）；beta.39 在三处就地 `.disabled`（`tools/_deploy_round139.py` → `DEPLOY_OK`） |

部署时发现并顺手修正的一处**既有问题**：SFTP `/client_files/mods` 里 `beta.37` 一直是**启用**状态
（上一轮部署对它的改名没有生效，也没有留下 `.disabled`），与 beta.39/beta.40 会构成同 mod 两个 jar。
本轮部署脚本改成「停用该目录里所有旧 beta jar」并断言「每个远端目录恰好只有 beta.40 一个启用 jar」，
现在 `/mods` 与 `/client_files/mods` 都只剩 `rs_create_compat-1.2.0-beta.40.jar` 一个启用文件。

---

## 5. 未做 / 已知边界

1. **没有实机存档复现**：A 的结论来自 1.21.1 / NeoForge `FluidStack` 语义 + 源码路径；
   B 的结论来自容量常量与调用链。beta.40 起若仍出现「带组件流体取不出」，请提供
   `MultiFluidCache` 条目 NBT（方块实体 NBT 里的 `FluidStorage`）与该流体来源，可据此判定是
   编码上下文还是别的第三方能力层。
2. 仍挂起：「高级远程多功能终端偶尔消失的『选择』按钮」（需要截图或界面名）。
3. 第 138 轮清单里其余项（保持器 L2 / H2 / M1 / M2、`ROUND117` M1 标记库体积限、
   `ROUND120` F2 跨资源超容）仍未做，需要产品决策或另开一轮。**48 格窗口 JEI 拖入
   「新 id 被忽略」已在第 140 轮修掉**（空格子那一半第 130 轮已修）；见 `docs/ROUND140_LANDING.md`。
