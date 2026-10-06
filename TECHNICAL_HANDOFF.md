# 技术交接文档 — Refined Storage & Create Compat

> 本文档供下一位接手的 AI / 开发者快速上手。覆盖：项目目标、架构、关键文件、构建验证、已知问题、修复历史、约定。
> 所有路径均相对项目根 `d:\MODS\refined_storage_and_create_compat\`（除非另注绝对路径）。

***

## 1. 项目概述

| 项             | 值                                                    |
| ------------- | ---------------------------------------------------- |
| 中文名           | 精致存储&机械动力：兼容与改善                                      |
| 模组 ID         | `rs_create_compat`                                   |
| 包名            | `cretae.cookiewyq.rs_create_compat`                  |
| 主类            | `cretae.cookiewyq.rs_create_compat.RS_Create_Compat` |
| MC 版本         | 1.21.1                                               |
| NeoForge 版本   | 21.1.248                                             |
| Gradle 守护 JVM | `D:/java21`（必须 Java 21，本机 `JAVA_HOME` 是 Java 8 不可用）  |
| 模组版本          | `0.0.1-SNAPSHOT`                                     |
| 作者            | CallMeACookieWYQ                                     |

### 前置依赖版本（见 `gradle.properties`）

- **Create** `6.0.10-280`（maven.createmod.net）

- **Ponder** `1.0.82`、**Flywheel** `1.0.6`、**Registrate** `MC1.21-1.3.0+67`

- **Refined Storage 2** `2.0.0`（maven.creeperhost.net）

- **Quartz Arsenal** `1.0.6`（无线合成终端来源）

- **JEI** `19.27.0.336`

- **Curios** `9.2.3+1.21.1`（必装前置，maven.theillusivec4.top）

- **Jade** 通过本地制品 `libs/jade-15.10.5+neoforge.jar`（用户机器 TLS 证书链校验失败，不能走 Maven）

- **FTB Ultimine** 可选（连锁套壳集成）

### 核心功能

1. **序列装配自动化**：把机械动力的「序列装配」（SequencedAssembly）接入 RS 自动合成体系，支持概率产物与废料、多步骤流程编排。
2. **蓝图加农炮装填**：自动从 RS 网络获取蓝图加农炮所需资源，自身作为合法容器供其提取，多装填器集群合并容量。
3. **归流缓存仓**：按 ghost 标记从世界吸取掉落物 / 流体源 / 经验球 → 缓存 → 节流回流进 RS 网络。
4. **定量保持器**（基础 / 高级）：标记物品 / 流体 / 气体，不足时触发自动合成，过量时按策略销毁。
5. **中间产物缓存仓**：执行链中暂存中间产物，避免循环死锁。
6. **范围充电器**：给范围内所有可充电方块 / 物品充电。
7. **伪装框架 / 分隔框架 / 鞘链**：方块伪装与电缆遮蔽（CamouflageFrame、SeparationFrame、Sheath）。
8. **高级远程多功能终端**：可在合成终端 / 样板终端 / 自动合成仓管理器 / 监视器 / 序列装配样板终端之间切换。
9. **总线属性过滤**：输入 / 输出总线支持「类别详细配置」（按配方分页 + 类别勾选）。
10. **装配看门狗**：自动合成任务卡住时挂起、玩家手动点继续才恢复。

***

## 2. 核心概念与术语

| 术语                                  | 说明                                                                                                       |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------- |
| 序列装配样板（`sequence_assembly_pattern`） | 顶层样板，由流程编排生成，下单 1 张触发整条产线                                                                                |
| 单元样板（`sequence_unit_pattern`）       | 流程某一步的样板（冲压 / 切割 / 机械手装配 / 灌注 / 注液）                                                                      |
| 流程编排（Arrangement）                   | 终端中部显示的有序步骤列表，每步带重复次数与机器绑定                                                                               |
| 执行仓（SequenceExecutionChamber）       | 分布式「序列装配执行」核心方块，认领网络中的过渡件 / 原料并塞给相邻 Create 机                                                             |
| 样板终端（PatternTerminal）               | 玩家编辑流程 + 生成总样板 / 单元样板的入口                                                                                 |
| 总线类别（BusCategory）                   | 输出总线可导出的「类别」：`input:<item>` / `intermediate:<step>` / `result:<item>` / `scrap:<item>` / `fluid:<fluid>` |
| 集群（Cluster）                         | 同类型方块相邻 = 一个整体，主控（坐标字典序最小者）持有唯一一份共享载荷                                                                    |
| 归流缓存仓（CollectionCache）              | 从世界（掉落物 / 流体源 / 经验球）收集资源并回流网络                                                                            |
| 蓝图加农炮装填器（SchematicLoader）           | 自动为加农炮拉取所需资源 + 蓝图队列自动打印                                                                                  |
| 看门狗（AssemblyWatchdog）               | 自动合成任务「卡住 → 挂起 → 玩家手动恢复」的服务端权威控制器                                                                        |
| 流量台账（FlowLedger）                    | 执行舱边界守恒账本：`留存 + 销毁 = fromNetwork + fromWorld − toNetwork`                                                |

***

## 3. 架构与关键文件

### 3.1 目录结构（`src/main/java/cretae/cookiewyq/rs_create_compat/`）

| 子包               | 职责                                                                                                                                                   |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.`（根包）          | `RS_Create_Compat`（主类，所有 `DeferredRegister` 注册），`Config`（NeoForge 配置），`RegisterUpgradeDestinations`                                                  |
| `block`          | 方块定义（`SequenceExecutionChamberBlock`、`SchematicLoaderBlock`、`QuantityKeeperBlock` 等）                                                                 |
| `block/entity`   | 12 个方块实体（含 5 个核心机：执行仓、终端、装填器×2、归流缓存仓、定量保持器×2 等）                                                                                                      |
| `support`        | 服务端逻辑工具类（约 50+ 个：`AssemblyWatchdog`、`UnitPatternDedupe`、`RsccFlowLedger`、`RsccMachineCluster`、`RsccSlotNbt`、`RsccBusCategory`、`RsccAssemblyDebug` 等） |
| `menu`           | 容器菜单（`SequencePatternTerminalMenu`、`SchematicLoaderMenu` 等）                                                                                          |
| `network`        | 网络包与节点（约 90 个：C2S 配置包 + S2C 同步包 + RS 网络节点）                                                                                                           |
| `client/screen`  | 25 个 GUI 界面                                                                                                                                          |
| `client/widget`  | 自绘控件（`GhostMarkerRenderer`、`McGui`、`SptGuiTextures`、`ScrollSelectWidget` 等）                                                                          |
| `client/tooltip` | 7 个 tooltip 相关类（核心：`RsccTooltipLayers`）                                                                                                              |
| `client`         | 客户端初始化 / Overlay / JEI 插件 / 快捷键                                                                                                                      |
| `item`           | 物品（`AdvancedRemoteTerminalItem`、`UniversalStorageDiskItem`、`SequenceAssemblyPatternItem`、`CamouflageFrameItem` 等）                                    |
| `data`           | `SequencePatternData`（样板 NBT 编解码）、`RecipeTypeNames`                                                                                                  |
| `storage`        | `UniversalStorageData`、`UniversalLimitedStorage`、`UniversalStorageType`                                                                              |
| `command`        | `CompatCommands`（`/rs_create_compat` 指令根）                                                                                                            |
| `mixin`          | 4 个 Mixin（`TaskContainerMixin` 让挂起任务不被 step；`BlockEntityUpdateTagMixin`；`VirtualGridBlockEntityMixin`；`InWorldNetworkNodeContainerImplMixin`）        |
| `advancement`    | `RsccAdvancements` + `RsccCriterionTrigger`                                                                                                          |
| `report`         | `CompatCompletionSender` + `RsccBusDisabledBanner`                                                                                                   |

### 3.2 核心方块实体（`block/entity/`）

| 类                                                                 | 职责                                                                                 | 路径                                                                                                  |
| ----------------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `SequenceExecutionChamberBlockEntity`                             | **最核心**。认领应交给相邻 Create 机的过渡件 / 原料（每次 ≤16），20 tick 节流全表扫描 + 同资源 20 tick 冷却防争抢。      | `block/entity/SequenceExecutionChamberBlockEntity.java`                                             |
| `SequencePatternTerminalBlockEntity`                              | 样板编辑终端（无 RS 网络节点）。单元样板库 108 格、流程编排无上限（滚动窗口 8 行）、底部 1 格总样板槽 + 6 格单元样板窗口。            | `block/entity/SequencePatternTerminalBlockEntity.java`                                              |
| `SchematicLoaderBlockEntity`                                      | 基础装填器：54 格主库存、6 升级槽、1 蓝图槽、参与集群合并。                                                  | `block/entity/SchematicLoaderBlockEntity.java`                                                      |
| `AdvancedSchematicLoaderBlockEntity`                              | 高级装填器：继承基础版，主库存 108 格、蓝图队列 27 格、自动流水线打印。                                           | `block/entity/AdvancedSchematicLoaderBlockEntity.java`                                              |
| `CollectionCacheBlockEntity`                                      | 归流缓存仓：物品 256 格 × 64、流体 512000 mB、ghost 标记 6 页 × 48 格、4 类吸取来源（掉落物 / 流体源 / 气体 / 经验）。 | `block/entity/CollectionCacheBlockEntity.java`                                                      |
| `QuantityKeeperBlockEntity` / `AdvancedQuantityKeeperBlockEntity` | 定量保持器：单 / 4 槽位独立配置，每槽物品 27 格 × 64、流体 128000 mB。                                    | `block/entity/QuantityKeeperBlockEntity.java`、`block/entity/AdvancedQuantityKeeperBlockEntity.java` |
| `IntermediateCacheBlockEntity`                                    | 中间产物缓存仓。                                                                           | `block/entity/IntermediateCacheBlockEntity.java`                                                    |
| `UnitPatternManagerBlockEntity`                                   | 单元样板管理器（统一管理终端 + 各执行舱的样板，按执行舱分组显示）。                                                | `block/entity/UnitPatternManagerBlockEntity.java`                                                   |
| `SequenceAssemblyExecutorBlockEntity`                             | 序列装配执行器（与执行仓配合的「自动合成 + 内部存储」机器）。                                                   | `block/entity/SequenceAssemblyExecutorBlockEntity.java`                                             |
| `RangeChargerBlockEntity`                                         | 范围充电器（FE 充电）。                                                                      | `block/entity/RangeChargerBlockEntity.java`                                                         |
| `SeparationFrameBlockEntity`                                      | 分隔框架方块实体。                                                                          | `block/entity/SeparationFrameBlockEntity.java`                                                      |

### 3.3 服务端逻辑（`support/`）

| 类                                                                                     | 职责                                                                                                                                        | 路径                                         |
| ------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| `AssemblyWatchdog`                                                                    | 「自动合成任务卡住」观察者 + 挂起 / 恢复控制器（服务端权威）。1 秒扫描一次只读分类，每 tick 由 `advanceSuspendState` 计时；挂起后由 `TaskContainerMixin` 让 RS 跳过 step。**恢复的唯一入口是玩家点继续**。 | `support/AssemblyWatchdog.java`            |
| `UnitPatternDedupe`                                                                   | 单元样板查重：必须「同配方同步骤」才算重复；老样板缺 `Recipe` / `Step` 时**不判重**（宁可多生成绝不漏生成）。**2026-10-04 第三轮**：判定带上命中来源 + 槽位 + 判据摘要（`basisOf` / `describe`），由终端生成路径落 `[rscc-dedupe]` 日志（见 §7.4）。                                                                        | `support/UnitPatternDedupe.java`           |
| `RsccSlotNbt`                                                                         | 按**槽位下标**读写 `SimpleContainer` NBT。原版 `createTag` 不写 "Slot"，会导致玩家物品存档后被挤进同一格——本类修复此 bug。                                                   | `support/RsccSlotNbt.java`                 |
| `RsccFlowLedger`                                                                      | 执行舱边界守恒账本。不变量：`留存 + 销毁 = fromNetwork + fromWorld − toNetwork`，未对平只作诊断、不做硬崩溃。**2026-10-04 第三轮加运行期审计日志**（`tickAudit` + `[rscc-ledger]`：首次失衡立刻一条 / 持续失衡 WARN / 恢复一条 / 恒带 `peak`；见 §7.4）。                                                              | `support/RsccFlowLedger.java`              |
| `RsccBuildInfo`                                                                       | **构建指纹**（2026-10-04 第三轮新增）：启动时打唯一一行 `[rscc-build]`（git revision / 分支 / dirty / 编译时间 / 源码数 / mc / neoforge / java），让「这份日志是哪一版代码跑出来的」可自证。见 §7.4。                                                              | `support/RsccBuildInfo.java`               |
| `RsccMachineCluster`                                                                  | 「同类型方块相邻 = 一个整体」集群解析器（**唯一实现**）。事件驱动失效 + 20 tick 缓存命中校验。                                                                                  | `support/RsccMachineCluster.java`          |
| `RsccBusCategory`                                                                     | 输出总线「可导出类别」快照（S2C 下发）。id 规则：`input:` / `intermediate:<步序>` / `result:` / `scrap:` / `fluid:`。                                             | `support/RsccBusCategory.java`             |
| `RsccAssemblyDebug`                                                                   | 序列装配结构化诊断日志（前缀 `[rscc-assembly]` / `[rscc-trace]`），三道节流闸防刷屏。默认开启，可用 `/rs_create_compat assemblydebug` 切换。                                 | `support/RsccAssemblyDebug.java`           |
| `RsccChamberImportStrategy` / `RsccChamberExportStrategy`                             | 执行仓的输入 / 输出总线策略。                                                                                                                          | `support/RsccChamberImportStrategy.java` 等 |
| `RsccSupplyPolicy` / `RsccSupplyStrategy` / `RsccRefillPolicy` / `RsccShortagePolicy` | 供应 / 补合成 / 缺料处置策略（指令切换）。                                                                                                                  | `support/Rscc*.java`                       |
| `SequencedRecipeProbe`                                                                | 服务端展开 Create `sequenced_assembly` 配方标签型 ingredient 全部候选。                                                                                  | `support/SequencedRecipeProbe.java`        |
| `SequenceMaterialGuard`                                                               | 备料侧逐步骤原料判定。                                                                                                                               | `support/SequenceMaterialGuard.java`       |
| `RsccCamouflage*` / `RsccSheath*` / `RsccCableCuts` / `RsccWrenchCableInteraction`    | 伪装框架 / 鞘链 / 电缆切割 / 扳手交互。                                                                                                                  | `support/Rscc*.java`                       |

### 3.4 客户端界面（`client/screen/`）

| 类                                                                                      | 职责                                                                                                            | 路径                                                 |
| -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| `SequencePatternTerminalScreen`                                                        | 序列装配样板终端主界面（v10，约 125KB）。1 格总样板槽 + 6 格单元样板窗口（小滚动条 / 滚轮翻页）+ 全宽流程编排 + 输入 / 产物 / 废料 + 玩家背包。**只读**（机器可改，其他入口已撤销）。 | `client/screen/SequencePatternTerminalScreen.java` |
| `BusCategoryConfigScreen`                                                              | 总线「类别详细配置」子界面。**两级结构**：第一级配方标签页（列车轨道 / 坚固板 / 精密构件…），第二级页内分节（原料 → 输入时原料 → 流体 → 成品 → 废料 → 中间产物）。                | `client/screen/BusCategoryConfigScreen.java`       |
| `StepDetailConfigScreen`                                                               | 「某一步的详细配置」子界面（Ctrl+左键触发）。仅配置步号导航 + 机器绑定；**循环次数已移除**，由 Create 配方数据决定。                                          | `client/screen/StepDetailConfigScreen.java`        |
| `CollectionCacheScreen` / `CollectionMarkerConfigScreen`                               | 归流缓存仓主界面 + 匹配设置子窗口。                                                                                           | `client/screen/CollectionCacheScreen.java` 等       |
| `ChamberBindingConfigScreen` / `ChamberFaceConfigScreen` / `ChamberUnitsSummaryScreen` | 执行仓绑定 / 输出面 / 单元样板汇总。                                                                                         | `client/screen/Chamber*.java`                      |
| `AdvancedQuantityKeeperScreen` / `QuantityKeeperScreen`                                | 定量保持器界面。                                                                                                      | `client/screen/*QuantityKeeperScreen.java`         |
| `SchematicLoaderScreen` / `AdvancedSchematicLoaderScreen`                              | 装填器界面。                                                                                                        | `client/screen/*SchematicLoaderScreen.java`        |
| `UnitPatternManagerScreen` / `UnitPatternConfigScreen`                                 | 单元样板管理器主界面 + 配置子窗口。                                                                                           | `client/screen/UnitPattern*.java`                  |
| `RangeChargerScreen` / `IntermediateCacheScreen` / `SequenceExecutionChamberScreen`    | 各机主界面。                                                                                                        | `client/screen/*.java`                             |
| `ChildConfigScreen`                                                                    | 子窗口基类（背景暗化 + 子面板居中，行为与 RS 一致）。                                                                                | `client/screen/ChildConfigScreen.java`             |

### 3.5 客户端控件与 tooltip（`client/widget/`、`client/tooltip/`）

| 类                                                                                                                                                              | 职责                                                                                                           |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `GhostMarkerRenderer`                                                                                                                                          | ghost 槽「物品 / 流体 / 气体」统一绘制工具；标签型 ingredient 命中物每 20 tick 轮播一次。                                                |
| `McGui` / `SptGuiTextures` / `SptScrollbarWidget` / `ScrollSelectWidget` / `RepeatButton` / `SptTabButton` / `RsccRedstoneModeButton`                          | 自绘 GUI 公共工具（无阴影 `drawString`、9-slice 背景、滚动条、滚轮选择控件等）。                                                        |
| `RsccTooltipLayers`                                                                                                                                            | **唯一** tooltip 按键分层实现。`Shift` = 核心机制 / 用途、`Ctrl` = 数值 / 配方 / 统计、`Alt` = 调试 / 来源。各处不得自行判断 Shift / Ctrl / Alt。 |
| `ItemCycleTooltipComponent` / `UnitPatternTooltipComponent` / `UpgradeItemTooltipComponent` / `UpgradeSlotTooltips` / `RecipeTypeMachines` / `RecipeTypeIcons` | 各类专用 tooltip 组件。                                                                                             |

### 3.6 网络包（`network/`，约 90 个）

主要 C2S（客户端 → 服务端，配置写入）：

- `SetStepMachinePacket` / `SetStepSkipDuplicatePacket` / `SetSequenceCountPacket` / `SetSequenceChancePacket` / `SetSequenceImportPacket` / `SetSequenceResultPacket` / `SetSequenceGhostPacket` / `SetStepFluidPacket`

- `SetQuantityTargetPacket` / `SetQuantityMarkerPacket` / `SetQuantityFluidMarkerPacket` / `SetAdvKeeperTargetPacket` / `SetAdvKeeperMarkerPacket` / `SetAdvKeeperFluidMarkerPacket` / `SetAdvKeeperOverflowPacket` / `SetAdvKeeperAutocraftPacket`

- `SetRangePacket` / `SetClusterRowPacket` / `SetChamberFacePacket` / `SetChamberBindingPacket` / `SetChamberOutputModePacket` / `SetExecutorTargetPacket`

- `SetShortageModePacket` / `SetImporterExecutorCategoriesPacket` / `SetExporterExecutorCategoriesPacket` / `SetImporterForceNormalPacket` / `SetExporterForceNormalPacket` / `SetImporterAutoCollectPacket`

- `SetCollectionMarkerConfigPacket` / `SetCollectionAbsorbTogglePacket` / `SetCollectionDestroyPacket` / `SetCollectionMarkerDestroyPacket` / `SetCollectionInvertMatchPacket` / `SetCollectionRadiusPacket` / `SetCollectionInputFacePacket` / `SetCollectionCollectAllPacket` / `SetCollectionScrollPacket` / `SetCollectionBlockedPacket` / `SetCollectionXpFormPacket` / `ExtractCollectionFluidPacket`

- `SetArrangementScrollPacket` / `SetResultScrollPacket` / `TransferChamberUnitPacket` / `ClearSequenceResultPacket` / `RestoreCursorPacket` / `AssemblyTaskActionPacket` / `AssemblyStepMachinePacket` / `CreateUnitPatternPacket`

- `SwitchTerminalModePacket` / `OpenAdvancedRemoteTerminalPacket` / `OpenChamberUnitsSummaryPacket` / `CloseChamberUnitsSummaryPacket` / `RequestChamberListPacket` / `RequestChamberUnitsPacket` / `RequestRecipeTypesPacket` / `RequestShortageModePacket` / `RequestAssemblyAlertsPacket`

- `ToggleCamouflageRevealPacket` / `RefreshCamouflagePacket`

主要 S2C（服务端 → 客户端，状态同步）：

- `SyncChamberListPacket` / `SyncChamberBindingPacket` / `SyncChamberUnitsPacket` / `SyncStepMachinesPacket` / `SyncRecipeTypesPacket` / `SyncLibrarySectionsPacket` / `SyncAutocrafterNamesPacket`

- `SyncCollectionMarkersPacket` / `SyncShortageModePacket` / `SyncQuantityFluidMarkerPacket` / `SyncAdvKeeperConfigPacket`

- `SyncExporterExecutorModePacket` / `SyncImporterExecutorModePacket` / `SyncAssemblyAlertsPacket` / `SyncBusInterferencePacket` / `SyncCamouflageRevealPacket` / `SyncSheathPositionsPacket` / `SyncCableDisconnectsPacket`

- `CompletionBannerPayload` / `AssemblyMachineCandidatesPacket` / `UnitPatternManagerData` / `CollectionBlockedSnapshot`

### 3.7 指令（`command/CompatCommands.java`）

- `/rs_create_compat autocrafter storage on|off` —— 自动合成仓内部存储开关（半径 32 格）

- `/rs_create_compat assemblydebug on|off`（别名 `/rs_create_compat debug assembly`）—— 序列装配诊断日志开关

- `/rs_create_compat supply <policy>` —— 原料供应策略

- `/rs_create_compat shortage <policy>` —— 缺料处置策略（挂起 / 一直等待）

- `/rs_create_compat blockcontent <mode>` —— 拆方块时内容物去向

- 所有指令 `requires(source -> true)`，任何玩家可执行

***

## 4. 构建与验证

### 4.1 编译命令（无网络沙箱可用）

```powershell
powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1
```

- 用本地 Gradle 缓存 jar 编译，绕过沙箱网络限制

- 期望输出 `===== COMPILE OK (N sources) =====`

- 输出目录 `build/manual_compile/`，日志 `build/manual_compile.log`

- 自动加入 `libs/*.jar`（Jade 等本地制品），从 Gradle 缓存里排除同名 jade 制品

### 4.2 标准 Gradle 构建

```powershell
.\gradlew build          # 完整构建
.\gradlew runClient     # 启动客户端
.\gradlew runServer     # 启动服务端
.\gradlew runGameTestServer  # GameTest
```

- Java 工具链锁定为 21（`build.gradle` 第 29 行）

- 编码统一 UTF-8（`tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }`）

### 4.3 selfcheck 脚本（`tools/`）

| 脚本                                                   | 作用                        |
| ---------------------------------------------------- | ------------------------- |
| `manual_compile.ps1`                                 | 本地 javac 编译（无网络）；**顺带刷新构建指纹** |
| `gen_build_info.py`                                  | 生成 `build_info.properties` 构建指纹（`--check` 只校验） |
| `verify_build_stamp.py`                              | **日志能否自证版本**（指纹行 / revision / 会话时序，4 条硬断言） |
| `verify_single_unit_supply.py`                       | 供料不变式 + 修复后行为（无日志 / 无诊断行 / 旧日志一律 FAIL） |
| `analyze_blockage_experiment.py`                     | **齿轮堵塞 / 多余中间产物 / 序列装配** 的实验日志分析（按订单段给出 6 条带明文证据的断言；见 `docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md`） |
| `selfcheck_collection_delete_mode.py`                | 归流缓存仓「删除模式」全链路（43 checks：总闸默认关 / 双条件销毁 / 速率同源 / 确认位 / ContainerData 映射 / 中英键） |
| `selfcheck_track_candidate_group.py`                 | 列车轨道「铁粒/锌粒候选组」全链路回归（候选组落盘 + 进 RS 多候选 ingredient；45 checks） |
| `diagnose_all.py`                                    | 一键运行所有诊断（报告第 0 节含构建指纹）    |
| `audit_lang_keys.py`                                 | 校验语言键完整性（zh\_cn ↔ en\_us） |
| `audit_gui_textures.py` / `audit_shared_textures.py` | GUI 贴图像素级比对               |
| `scan_gui_sprites.py` / `scan_gui_layout.py`         | 扫描 GUI 精灵坐标与布局            |
| `check_payload_registration.py`                      | 校验所有网络包是否注册               |
| `check_invalid_paths.py`                             | 资源路径合法性                   |
| `audit_resource_swallow.py`                          | 资源吞物审计                    |
| `grep_assembly_log.py` / `analyze_loader_log.py`     | 日志分析                      |
| `gen_diag_fixture.py`                                | 诊断 fixture 生成             |

### 4.4 序列装配链路自检流程（`docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md`）

```powershell
# 1. 编译（拿到「本次构建」时间戳，并刷新构建指纹）
powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1

# 2. 启动游戏（JVM 必须在编译之后启动，否则日志只能作基线）
.\gradlew runClient

# 3. 确认诊断开关（默认 on，通常可跳过）
# 游戏内：/rs_create_compat assemblydebug on

# 4. 跑产线（下单 1 个 / 64 个坚固板、精密构件）

# 5. 先问「这份日志能不能作证」，再问「行为对不对」
python tools\verify_build_stamp.py
python tools\verify_single_unit_supply.py
```

***

## 5. 编码约定（用户硬规则）

1. **GUI 所有自绘元素的 tooltip 必须手动渲染**——Minecraft 不会自动渲染，必须显式调用 `guiGraphics.renderTooltip(...)` 或经 `RsccTooltipLayers` 派发。
2. **精灵图坐标 → Menu 槽位坐标需要 +1 偏移（x 和 y 都 +1）**——所有 Screen 常量除滚动条外全为 Menu 坐标；滚动条沿用精灵坐标口径且绘制 / 命中同源。
3. **有规则和形式要求的文件用 Python 脚本生成，不手动写**——所有 JSON（语言、模型、配方、advancement）由 `tools/*.py` 生成；`tools/` 下有 100+ 个生成脚本。
4. **遇到问题用详细但不刷屏的日志获取完整情况再修**——参考 `RsccAssemblyDebug` 的三道节流闸（transition / reason / reject）。
5. **禁止废话，一种方式不行就不要死磕**。
6. **所有** **`drawString`** **一律无阴影**——`guiGraphics.drawString(font, text, x, y, color, /* shadow = */ false)`；EditBox `setTextShadow(false)`。
7. **hover 判定与绘制范围共用同一批判定函数**——例如 `SequencePatternTerminalScreen#globalStepOf` / `rowAt`。
8. **服务端权威**——所有配置 / 状态变更走 C2S 包由服务端权威校验后落地；客户端只发请求。
9. **集群只有主控保存内容**——其余成员写空载荷，避免读档后内容翻倍。
10. **mixin 包不放过路类**——曾因 `RsccAutocrafterStorage` 放在 mixin 包被外部引用触发 `IllegalClassLoadError` 崩游戏，已迁回普通包。
11. **`tools/*.ps1` 必须存成 UTF-8 with BOM**（2026-10-04 第三轮新加的硬约定）——Windows PowerShell 5.1 对无 BOM 的脚本按系统 ANSI（本机 GBK）解析，中文注释会被解成乱码并**连带让后续语句失效**：实测 `$root` 变成 null，脚本在第 17 行就崩。用 `[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($true)))` 或编辑器里选「UTF-8 with BOM」。Python 脚本无此问题（读脚本按 UTF-8），但**打印**中文时需要 `sys.stdout.reconfigure(encoding='utf-8', errors='replace')`，否则控制台 GBK 会抛 `UnicodeEncodeError` 把脚本打断。

***

## 6. 当前已知问题与待办

### 6.1 待办（用户已要求但未完成）

1. **`BusCategoryConfigScreen`** **类别详细配置仍需添加输入框**——用户尚未明确输入框用途；当前结构是两级（配方标签页 + 页内分节）+ 勾选条目，但缺一个用户期望的输入框入口。
2. ~~**列车轨道单元样板生成问题**——铁粒 / 锌粒候选在生成时未正常显示~~ **已修（2026-10-04 第二轮，见 §7.1）**：根因是「样板物化」链只有单值输入、候选组从未落盘。**老存档需重新生成一次样板**才会把锌粒补进 RS 样板。
3. **`SequencePatternTerminalScreen`** **文件已 125KB**——后续重构应考虑拆分为多个子组件，但当前用户未要求。
4. **FACE 输出模式未设防（2026-10-04 审计新发现）**：`SequenceExecutionChamberBlockEntity#tickEngine` 的 FACE 分支只有 `isInputBlockedByStep` / `holdsItem` 两道判据，**没有** BUS 通路那 6 道闸门 ⇒ 起步原料在「已被机器转成过渡件」时会再次投放，可能多开一件在制件（= 多余中间产物）。用户实机走 BUS（`output=bus`），故未暴露；切 FACE 前必须先补闸门。
5. **`EXECUTOR_OFFLINE` 误报已被定位并修掉一部分（2026-10-05）**：真凶**不是**「虚拟方块实体 / 集群主控坐标」，而是 `pushStalledOnDestination()` 把<b>两种完全不同的原因</b>压进同一个布尔值，且它会在<b>没有订单的空闲期</b>被置位 —— 于是下一条订单刚绑定就被判掉线（实测 `stall=41` tick）。完整复现链、证据与修法见 §7.5。**旧文档里「指向 `(-5,-60,10)` 却没有执行仓」这半句是误记**：`at=` 打的是 `record.executorPos`（= 样板库坐标），运行日志本身<b>不</b>包含「掉线的是哪台仓」这个信息（真掉线时会打 `offlineSteps=`，那一次是 `-`）。现在 `stallChamber` / `stallCause` / `stallResource` 三个字段进了快照，指向问题不再靠猜。
6. **候选缓存整局不清空（健壮性隐患）**：`SequencePatternTerminalScreen` 的 `stepInputCache` / `assemblyInputCache` 只写不清（key = `recipe#step`）；若首帧在配方/槽位未就绪时算出空表，会以空结果缓存整局。当前有「样板落盘候选」兜底，故影响已被削弱，但仍是隐患。
7. ~~**换机器会静默清空该步的「输入原料组候选」**~~ **已修（2026-10-04 第三轮，见 §7.4）**：`AssemblyWatchdog#changeStepMachine` 重建 `UnitEntry` 时漏传 `inputCandidates`（10 参构造器），而 `UnitEntry` 的紧凑构造器会把它归一化成空表 ⇒ `candidatesOrRepresentative()` 退回「只有代表物」。后果与 §7.1 完全同症状但走另一条路复发：列车轨道的「铁粒 或 锌粒」在玩家**换过一次机器**之后又变回「只要铁粒」，而且当时不落任何日志，玩家只会看到「锌粒又没了」。

### 6.2 待验证项

- 多候选轮播（标签型 ingredient 全部候选）在所有配方上是否稳定

- 「EXECUTOR\_OFFLINE 误报」修复后是否在所有任务规模下稳定（**2026-10-05 已定位并修掉空闲期自锁那一条，见 §7.5；仍需实机复测**）

- **归流缓存仓 / 装填器 / 保持器 / 终端等机器的自述导出**：`RsccDiagnosable` 目前只有装填器与归流缓存仓
  给出「设置级」自述（其余机器靠 `machines[].nbt` 的完整 NBT 兜底，字段可读性差一档）。
  若下次仍需要人工解读 NBT，再按同样的模式补 `rscc$diagReport()`

- 蓝图加农炮装填器无活动蓝图时剩余建筑材料回收是否覆盖所有材料类型

- **候选组落盘修复的实机验证**：`python tools\selfcheck_track_candidate_group.py` 只做源码锚点 + 等价模型，
  真正的验证需要实机重放列车轨道样板，确认 RS 任务行里出现
  `Ingredient[amount=2, inputs=[iron_nugget, zinc_nugget]]`（当前唯一已知的实证方式）

- **本轮日志设施本身的实机验证（第三轮新增）**：下面四条都只能实机取证，脚本侧已就绪：
  1. 启动日志里出现 `[rscc-build]` 且 `revision` 等于编译时 `git rev-parse --short HEAD`
     → `python tools\verify_build_stamp.py` 应报 `VERIFY BUILD STAMP OK`；
  2. 产线跑起来后**不应**出现 `[rscc-ledger] unbalanced`；若出现，那条 `WARN (sustained …)` 就是
     第一份可用的守恒失衡证据（含资源、方向、差额与 `peak`）；
  3. 若某步被判重跳过，`[rscc-dedupe] … SKIPPED (duplicate of <来源> slot n)` 必须指出跟谁重复；
  4. 换一次机器后重新生成总样板，锌粒**不应**丢失（§6.1 第 7 条的修复验证）。

***

## 7. 近期修复历史

### 7.1 2026-10-04 第二轮：列车轨道「铁粒 / 锌粒候选未显示」

**症状**：列车轨道的单元样板生成后，「铁粒 / 锌粒」候选没有正常显示。

**根因（实机日志 + 存档双重实证，不是查重问题）**：
Create 的 `create:sequenced_assembly/track` 机械手步投入物是标签 `[c:nuggets/iron, c:nuggets/zinc]`，
但整条「**样板物化**」链只有单值 `TAG_INPUT`，且它由 `SequencedRecipeProbe#stepInput` 取
`getItems()[0]`（= 铁粒）写入 ⇒ 候选组在**三层**同时丢失：

| 层 | 旧表现 | 数据源 |
| - | ----- | ----- |
| ① 单元样板物品 tooltip / 单元样板管理舱 | 只有铁粒 | `getTooltipImage(ItemStack)` 拿不到 `Level`，结构上无法回查配方 |
| ② 总样板 tooltip | 只有石头台阶 + 铁粒 | 与 RS 样板共用 `collectInputs` |
| ③ **RS EXTERNAL 样板**（真正致命的一层） | 只登记 `minecraft:iron_nugget` | 实机日志 `PatternLayout[ingredients=[stone_slab×1, iron_nugget×2]]`，**全日志无一次 `zinc_nugget`** |

而执行舱 / 输出总线类别那一层（`assemblyStepInputGroups`）**本来就是对的**，所以「备料能拉锌粒」但
「RS 样板不认锌粒」——两条路口径分叉。查重不是元凶（跨配方已由「同配方同步骤」挡住），但它**会掩盖修复**：
`stepSkipDuplicate` 默认恒开，日志三次出现 `terminal generate: all 2 unit step(s) skipped as duplicates`。

**修复（候选组随样板一起落盘 + 进 RS 样板的多候选 ingredient）**：

| 文件 | 改动 |
| ---- | ---- |
| `data/SequencePatternData.java` | 新增 `TAG_INPUT_CANDIDATES`；`UnitData` / `UnitEntry` 各加 `inputCandidates` 分量（`candidatesOrRepresentative()` 兜底）；`normalizeCandidates` / `readCandidates` / `writeCandidates` / `candidatesOfItems`。**候选数 < 2 时不写 tag** ⇒ 老存档格式与语义一字不变 |
| `support/SequencedRecipeProbe.java` | 新增 `applicationCandidatesOf(recipe, declared)`：取「declared 所属那一组」的全部候选（多 ingredient 的步不会把不同组混在一起） |
| `client/SequenceTerminalJeiPlugin.java` | JEI 转移时逐步骤算好候选组（那里拿得到 `Ingredient#getItems()` 全部候选） |
| `network/SetSequenceImportPacket.java` | 新增与 `stepInputs` **逐下标平行**的 `stepCandidates`（encode/decode 成对） |
| `menu/SequencePatternTerminalMenu.java` | 候选组随单元样板 NBT 写入 |
| `block/entity/SequencePatternTerminalBlockEntity.java` | 生成总样板时把候选组带进 `UnitEntry` |
| `item/SequenceAssemblyPatternItem.java` | `collectInputs` 从「按单个物品聚合」改成「**按候选组聚合**」；RS 样板改用 `PatternBuilder.IngredientBuilder`（`ingredient(amount).input(r1).input(r2).end()`）⇒ 铁粒与锌粒成为**同一项 ingredient 的两个候选**。tooltip 标注「（或 锌粒）」 |
| `item/SequenceUnitPatternItem.java` + `client/tooltip/UnitPatternTooltipComponent.java` + `client/ClientInit.java` | 单元样板 tooltip 带出并**轮播**整组候选（多候选时标「等 N 种（任一皆可）」） |
| `support/UnitPatternDedupe.java` | 查重把候选组纳入比较键；**任一方「候选未知」时不算差异** ⇒ 升级前的旧样板不会把新样板判重跳过，同时绝不因此漏生成 |
| `client/screen/SequencePatternTerminalScreen.java` | 配方回查失败时的回落顺序改为「配方候选 → **样板落盘候选** → 单件代表物」；`stepRecipeByType` 多条命中时取「候选组更宽」的那条（旧实现取第一条 ⇒ 铁粒撞上「只要铁粒」的精密构件配方就永远只剩铁粒） |

**RS 侧依据（已反编译核对 `refinedstorage-neoforge-2.0.0`）**：RS 的 `Ingredient` 本就是
「一个需求量 + 多个可选输入」；`CraftingTree` 按可能性排序逐个尝试、`AbstractTaskPattern#calculateIterationInputs`
按输入顺序取候选直到凑够需求量 ⇒ 把整组候选放进**同一项** ingredient 是 RS 的语义内用法，
且「代表物放首位」= 优先消耗顺序与旧行为完全一致。

**回归断言**：`python tools\selfcheck_track_candidate_group.py`（45 checks：源码锚点 + 等价模型 + 兼容性）。

**⚠️ 升级注意**：RS 按 `(level, pattern UUID)` 缓存样板（UUID 由样板 NBT 决定）。
已放出去的旧样板**必须重新生成一次**（或重载世界）才会登记锌粒。

### 7.2 2026-10-04 第一轮修复

| # | 问题                                 | 根因                     | 修复方案                                                 | 文件路径                                                                                                                      |
| - | ---------------------------------- | ---------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 1 | EXECUTOR\_OFFLINE 误报               | 看门狗扫描所有执行仓，无关仓异常导致误报   | 改为仅检查当前任务实际使用的执行仓；新增「输出阻塞」明确提示                       | `support/AssemblyWatchdog.java`                                                                                           |
| 2 | BusCategoryConfigScreen 多候选轮播未显示   | 标签型 ingredient 未展开全部候选 | 服务端 `SequencedRecipeProbe` 展开 + 客户端轮播（每 20 tick 换一个） | `support/SequencedRecipeProbe.java`、`client/widget/GhostMarkerRenderer.java`、`client/screen/BusCategoryConfigScreen.java` |
| 3 | StepDetailConfigScreen 循环次数不该玩家配置  | 循环次数由 Create 配方数据决定    | 移除循环次数输入框，仅保留步号导航 + 机器选择                             | `client/screen/StepDetailConfigScreen.java`                                                                               |
| 4 | SchematicLoader 剩余建筑材料未回收（如樱花木活板门） | 归流缓存仓的设置错误应用到了装填器      | 修正策略：无活动蓝图时回收所有剩余材料至网络                               | `block/entity/SchematicLoaderBlockEntity.java`                                                                            |

### 2026-10-02 修复（来自 topics.md）

- 齿轮堵塞判定：添加等待豁免机制 **W1-W4**（**唯一实现在 `SequenceExecutionChamberBlockEntity#hasWaitReason`，
  不在 `AssemblyWatchdog`**），连续 **60 tick**（`STATION_STUCK_TICKS`）无推进才判卡住并收回；
  `STATION_STUCK_REARM_TICKS = 200` 是「判卡结论**粘住后**的慢重试间隔」，**不是判定阈值**
  （旧文档写「200 tick 无推进才判卡住」「W1-W5」「修复在 AssemblyWatchdog」三处均为误记，见 §7.3 更正）

- 补合成请求量：按缺口动态计算（目标 − 网络 − 本仓 − 机器侧 − 在途），概率配方分批递进 + 40 tick 冷却

- 缺料提示：区分资源是否可自动合成或存在在途任务，仅在不可合成且无任务时才显示

- 高级定量保持器升级槽：限制一槽一个，Shift+左键快速转移时仅填充第一个空槽

- 序列装配产物 / 废料：按配方独立分组，避免不同配方原料混在一起

### 更早的关键历史修复（来自 project\_memory.md / 历次 topics）

| 时间      | 修复                                                                                                                               | 文件                                                                                      | <br />                        |
| ------- | -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ----------------------------- |
| 2026-09 | **UnitPatternDedupe 跨配方误判**：旧口径只比「操作类型 + 输入」，导致「精密构件冲压」与「列车轨道冲压」被判成同一张，整条列车轨道排线的单元样板被全部跳过 ⇒ 下单毫无反应。修复：必须「同配方同步骤」才算重复，老样板缺字段时不判重。 | `support/UnitPatternDedupe.java`                                                        | <br />                        |
| 2026-09 | **patternAssignedSteps 污染所有权**：执行仓认领过渡件时按 `step % 序列长度` 匹配单元，避免越界污染其他配方的步骤所有权。                                                   | `block/entity/SequenceExecutionChamberBlockEntity.java`                                 | <br />                        |
| 2026-09 | **齿轮堵塞粘住**：根因为收回侧与备料侧口径不一致，空闲机械手误判为需要导致无法回收。修复：**W1-W4 等待豁免 + 60 tick 粘住判定**（`STATION_STUCK_TICKS`；200 是粘住后的慢重试间隔）。实现位于 `SequenceExecutionChamberBlockEntity`，`AssemblyWatchdog` 只做任务挂起。 | `block/entity/SequenceExecutionChamberBlockEntity.java`、`support/AssemblyWatchdog.java` | <br />                        |
| 2026-09 | **总线配置假阳性**：输出总线「类别详细配置」按配方分页 + 类别勾选，避免不同配方原料混在一起。                                                                               | `client/screen/BusCategoryConfigScreen.java`、`support/RsccBusCategory.java`             | <br />                        |
| 2026-09 | **插件槽压缩**：原版 `SimpleContainer#createTag` 不写 "Slot"，存档读回后插件被挤进同一格。修复：`RsccSlotNbt` 按槽位下标读写。                                       | `support/RsccSlotNbt.java`                                                              | <br />                        |
| 2026-09 | **自动合成默认开启**：自动合成仓内部存储总开关原本挂在管理器界面侧边按钮上易被误点，改为指令 \`/rs\_create\_compat autocrafter storage on                                    | off\`。                                                                                  | `command/CompatCommands.java` |
| 2026-09 | **IllegalClassLoadError 崩游戏**：`RsccAutocrafterStorage` 放在 mixin 包被外部引用触发。修复：迁回普通包 + 调整 accessor mixins + 清理 `mixins.json`。       | `support/RsccAutocrafterStorage.java`、`mixins.json`                                     | <br />                        |
| 2026-08 | **GUI 贴图 ±1 偏移**：`slot.png` 有效槽位是 17×17 而非 18×18，`crop(0,0,18,18)` 把右下 1px 白色高光误认为槽位内容。修复：`SLOT_PX=17` 裁剪 + `SLOT_GAP=18` 间距粘贴。  | `tools/audit_gui_textures.py`、`tools/make_gui_bg.py`                                    | <br />                        |
| 2026-08 | **bg.png 165 以下透明**：旧 `draw_inventory` 用它拼背包区产生「槽位悬浮在透明洞上」。修复：整幅背景由 `bg.png` 0-165 区域 9-slice 连续拉伸。                              | `tools/make_gui_bg.py`                                                                  | <br />                        |
| 2026-08 | **高级装填器升级槽与队列区重叠**：升级槽 2×3 (x=124,y=8..62) 与队列区 (x=8..170,y=30..84) 大面积重叠。修复：GUI 加高到 446，升级槽改独立一行横排 (8,326) 6 格。                 | `tools/make_gui_bg.py`、`client/screen/AdvancedSchematicLoaderScreen.java`               | <br />                        |
| 2026-08 | **回流总线移除**：用户要求改用「输入总线 + 线缆」替代。                                                                                                  | （已移除文件）                                                                                 | <br />                        |

### 7.3 2026-10-04 第二轮：精密构件「齿轮堵塞 ⇒ 多余中间产物」审计（只读，未改代码）

**起因**：用户要求复核该问题是否已解决。结论 = **部分解决**（代码层防御真实且互补，但**零运行期验证**）。

**先更正旧文档的三处误记**（全部经原文核对）：

| 旧文档说法 | 代码实际 |
| --------- | ------- |
| 修复在 `support/AssemblyWatchdog.java`，加入 W1-W5 | 实现在 `SequenceExecutionChamberBlockEntity#hasWaitReason`（L2043-2076）；`AssemblyWatchdog` 类注释 L88-92 明写「**绝不改执行舱**」，它只管 RS 任务挂起 |
| 连续 **200 tick** 无推进才判卡住 | 判定阈值 = `STATION_STUCK_TICKS = 60`（L326）；**200 = `STATION_STUCK_REARM_TICKS`（L355），是判卡结论「粘住后」的慢重试间隔** |
| W1-W5 五条豁免 | 实际 W1-W4，**W5 已被显式删除**（L2006 / L2073-2086） |

**BUS 通路（用户实机配置）**：推料侧 6 道互不重叠的闸门（`RsccChamberExportStrategy#transferItem`
L289 / L292 / L308 / L320 / L327 / L345）+ 配额夹到 1 件（L350-351）；备料侧 `wantingTargetCount` /
`fillInternalForBus` / `clampStartIngredientTargets` 另有 5 处收紧。**未找到未设防路径。**

**仍存在的残余风险**（见 §6.1 第 4、5 条）：FACE 输出模式整条未设防；`EXECUTOR_OFFLINE` 仍误报；
「200 tick 慢重试」会每 10 秒/工位故意再放行一次；缺料处置切「一直等待」会让卡住闸门永久失效（设计内）。

**关键取证局限（下一位接手必须知道）**：

- `RsccFlowLedger` **没有任何 Logger**，`unbalanced()` 只被 `/rs_create_compat diag` 导出读取（`run/rscc_diag/latest/report.md` 停在 2026-10-02）⇒ **「没有失衡告警」= 根本没写日志，不是「没失衡」**。→ **已修（第三轮，见 §7.4）**
- **该不变量结构上测不出「多余中间产物」**：多喂一份起步原料（`fromNetwork+1` 与 `toMachine+1`）与多产一件过渡件（`fromMachine+1` 与 `toNetwork+1`）都**恰好平账**。要测它只能新增指标「**在制过渡件数量 vs 订单量**」。（**仍成立**：第三轮只把账本变成会说话的账本，没有改变这条数学事实）
- 唯一加载过修复版代码的会话（`run/logs/latest.log`，15:29-15:43）**零生产流量**（`bus_push=0` / `stuck=0` / `[rscc-trace]=0`），而 `AssemblyWatchdog.java`（16:08）与最新编译（16:32）都**晚于**它 ⇒ 按项目自己的判据（`docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md` L18-20）最新日志**只是基线**。→ **现在这一步由机器判**：`python tools\verify_build_stamp.py`
- **日志与源码的对应关系不可自证**：日志里没有 git hash / 构建时间，只有 `0.0.1-SNAPSHOT`。建议启动时把二者打进日志（未做）。→ **已做（第三轮，见 §7.4）**
- `tools/verify_single_unit_supply.py` 是「源码字面串 + 日志正则」脚本，对 `STATION_STUCK_TICKS` / `hasWaitReason` / `stationStuckOn` / `RsccFlowLedger` / `blockedByForeignStepExtra` 命中 **0**；且 `hard=not stale` 会让部分断言在旧日志下永不 FAIL ⇒ **不要把它当作运行期验证**。→ **假绿已修（第三轮，见 §7.4）**：无日志 / 无 rscc 行 / 会话早于构建现在一律 **FAIL**；但「字面串命中 0」这条**仍然成立**（它测的是日志现象，不是这两个符号）。
- `build/diag_selftest/**/run/logs/latest.log` 是 `tools/gen_diag_fixture.py` 造的**合成夹具**（`task=aaaa/bbbb`），读日志的脚本可能跑在假数据上。

**要 100% 确认需做的实验**（未做）：① 先编译再启动游戏（顺序不能反）；② BUS 模式跑精密构件，下单 1 件与 64 件各一次，并**故意**让机械手手里压着「错的那一步投入物」制造堵塞做对照；③ 断言 `bus_push create:cogwheel` 每 5 秒加权 ≤1、cogwheel 网络存量无单调递减、无 `stuck ... waited=` 的 66/120/180/240 阶梯、在制 `incomplete_precision_mechanism` 恒 ≤1 件；④ 边界实验：缺料处置切「一直等待」（预期不再自动收回）、输出模式切 FACE（预期现有修复完全不起作用）。

### 7.4 2026-10-04 第三轮：把 §7.3 的「取证盲区」逐条堵上（日志设施 + 一个真 bug）

**起因**：用户要求「继续完善当前的日志」。附件里上一轮自己对日志提出的四条取证局限，**一条都还没做**，
因此本轮不新增功能，只做「让修复效果可被日志证明」这一件事（外加读代码时撞见的一个真 bug）。

| # | §7.3 的取证局限 | 本轮处置 |
| - | -------------- | ------- |
| ① | 日志里没有 git hash / 编译时间，日志与源码的对应关系只能靠 mtime 猜 | **`RsccBuildInfo` + `tools/gen_build_info.py`**：每次编译写入 `src/main/resources/build_info.properties`（revision / 分支 / dirty / builtAt / sourceEpochSeconds / sources），启动时打唯一一行 `[rscc-build]` |
| ② | `RsccFlowLedger` 没有任何 Logger ⇒「没有失衡告警」= 根本没写日志 | **`RsccFlowLedger#tickAudit`**：执行舱每秒审计一次，对平零输出 / 首次失衡立刻一条 / 持续 3 次采样转 WARN / 恢复一条收尾 / 恒带 `peak`（前缀 `[rscc-ledger]`） |
| ③ | 查重的 `Match(source,slot)` 被丢弃 ⇒「2 步全跳过」不可复现 | **`UnitPatternDedupe.Decision` + `basisOf` + `describe`**：所有真决策点（终端生成 / 新建单元样板）落 `[rscc-dedupe]`，含命中来源、槽位与判据摘要（配方 / 步序 / 操作类型 / 代表物 / **候选组**） |
| ④ | `verify_single_unit_supply.py` 假绿：无日志 / 无 rscc 行照样 `SELFCHECK OK`，旧日志下断言永不 FAIL | 前者改成 **FAIL**（`--allow-empty` 才降级）；会话早于构建改成 **FAIL**（`--allow-stale` 才只看基线）；新增 `B1` 指纹行断言；新增 `tools/verify_build_stamp.py`（4 条硬断言） |

**顺带修掉的真 bug（读代码时撞见，不在原计划内）**：
`AssemblyWatchdog#changeStepMachine`（玩家「换机器」）重建 `UnitEntry` 时走了 **10 参构造器**，
漏传 `inputCandidates` ⇒ 紧凑构造器归一化成空表 ⇒ `candidatesOrRepresentative()` 退回「只有代表物」。
后果与 §7.1 的 bug **同症状但走另一条路复发**：列车轨道的「铁粒 或 锌粒」在玩家换过一次机器之后
又变回「只要铁粒」，而且不落任何日志 —— 玩家只会看到「锌粒又没了」。修法 = 把 `unit.inputCandidates()`
原样带过去（1 行）。

**新增 / 改动的文件**：

| 文件 | 改动 |
| ---- | ---- |
| `support/RsccBuildInfo.java` | 新增。只读 jar 内 `build_info.properties`，字段取不到一律 `?`，绝不抛；`logOnce()` 幂等；资源缺失时额外打一条 `WARN build stamp missing` |
| `tools/gen_build_info.py` | 新增。生成构建指纹（Gradle 的 `generateBuildInfo` 任务与 `tools/manual_compile.ps1` 都会调用），另带 `--check` 只校验不写 |
| `build.gradle` | 新增 `generateBuildInfo` 任务（`outputs.upToDateWhen { false }`）并让 `processResources` 依赖它 |
| `tools/manual_compile.ps1` | javac 前先刷新指纹（Python 缺失只告警不失败）。**注意：该文件必须保存为 UTF-8 with BOM**，否则 Windows PowerShell 5.1 会按 GBK 解析中文注释并让变量变成 null（本轮实测踩到） |
| `support/RsccFlowLedger.java` | 新增 `tickAudit(origin, millis)` 与 `PREFIX`；静态审计表（按来源坐标，软上限 512，对平即清除） |
| `block/entity/SequenceExecutionChamberBlockEntity.java` | `tickEngine` 里每 20 tick 审计一次（在引擎节流 return 之后，只读、不参与判定） |
| `support/UnitPatternDedupe.java` | 新增 `Decision` 记录、`basisOf` / `describe`（判据摘要），只读 |
| `support/RsccAssemblyDebug.java` | 新增 `dedupe(...)` 与 `DEDUPE_PREFIX`（`[rscc-dedupe]`），复用 5 秒同因合并闸；**刻意不喂 `RsccDiag.observe`**（查重与流量守恒无关，不该污染那张计数表） |
| `block/entity/SequencePatternTerminalBlockEntity.java` | 新增与布尔缓存**逐下标平行**的 `stepDuplicateMatch` 缓存；生成路径对每一步落 `[rscc-dedupe]`；「全部跳过」汇总行带上「跳过了哪些步、各自跟谁重复」 |
| `network/CreateUnitPatternPacket.java` | 新建单元样板被判重时落 `[rscc-dedupe]`（玩家聊天提示保持原样，只多日志） |
| `support/AssemblyWatchdog.java` | **bug 修复**：`changeStepMachine` 带上传候选组 |
| `tools/verify_single_unit_supply.py` | 假绿修正 + `B1/B2` 断言 + `--allow-empty` / `--allow-stale` + stdout UTF-8 |
| `tools/verify_build_stamp.py` | 新增。`B1` 日志非空 / `B2` 有指纹行 / `B3` revision == 当前 HEAD / `B4` 会话晚于构建 |
| `tools/diagnose_all.py` | 报告第 0 节增打构建指纹与「是否等于当前 HEAD」；补 stdout UTF-8（原实现在最后一行 `print` 上抛 `UnicodeEncodeError`，报告虽落盘但退出码 1） |
| `docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md` | 补第 0 节第 3 条（自证口径）、日志族对照表、新命令、FAQ 两条 |
| `docs/ROUND_LOG_INSTRUMENTATION_20261004.md` | **本轮完整报告**（为什么做 / 四族日志 / 真 bug / 假绿修正 / 验证记录 / 实机步骤） |
| `tools/selfcheck_*.py` / `tools/verify_*.py`（45 个） | 补 stdout UTF-8 安全网（`sys.stdout.reconfigure`，插在「模块 docstring + 全部 import 之后」）。**这不是本轮引入的问题**：在 GBK 控制台下跑这些脚本会因 `⇒` / `−` / `∉` / 圈码等字符抛 `UnicodeEncodeError`，把脚本**打断在最后一行** —— 于是「检查其实全过」看起来也是失败。实测 37 个自检里有 **20 个**如此。 |
| `tools/selfcheck_assembly_cancel_immediate.py` | 修**过期锚点**：它钉的是 `items.merge(data.ingredient().getItem(), …)`，而 §7.1 已把 `collectInputs` 改成按候选组聚合（`Map<InputGroupKey, Long>` + `mergeInput`）⇒ 该断言长期 FAIL（自检永久红，不是代码坏）。改为钉当前实现（单候选组并入 + 不随 loops 放大），并补一条「按候选组聚合而非按单物品」的守护断言。 |

**噪声控制（为什么不会刷屏）**：

- `[rscc-build]`：整个进程**恰好一行**（`logOnce` 幂等），资源缺失时多一条 `WARN`；
- `[rscc-ledger]`：对平时**零输出**；失衡时首次 1 条 + 持续 1 条 WARN + 每 30 秒最多 1 条合并 + 恢复 1 条；
  审计每台仓每秒一次，但采样结果相同就只累加计数；
- `[rscc-dedupe]`：走既有 5 秒同因合并闸；且**只有真决策点**（生成 / 新建）会写，
  界面刷新用的只读探测（`refreshStepDuplicateCache` → `SyncStepMachinesPacket`）不写。

**验证（本轮实跑，全部为源码 / 脚本层面；运行期仍需实机）**：

```
manual_compile.ps1                  → COMPILE OK (324 sources)
python tools\gen_build_info.py       → BUILD STAMP WRITTEN（revision=e347372 dirty=true）
python tools\verify_build_stamp.py   → 对现有旧日志正确判 FAIL：
                                        B2 缺 [rscc-build] 行（旧构建）；B3/B4 亦为基线
python tools\verify_single_unit_supply.py → VERIFY FAILED (2/53)，两条失败正是新增的 B1/B2
                                        （即：旧日志不再能「全绿」——这就是本轮要的效果）
python tools\diagnose_all.py         → 报告第 0 节出现「构建指纹：日志里没有 [rscc-build] 行 …」，
                                        退出码 0（原本会因圈码 UnicodeEncodeError 崩在最后一行）
37 个 selfcheck_*.py                  → 全部 exit 0（修完 20 处控制台编码 + 1 处过期锚点之后）
```

> **顺手确认的一件事（对取证有价值）**：`selfcheck_assembly_cancel_immediate.py` 那条过期锚点说明
> 「自检全绿」这句话本身也可能是假的 —— 一个脚本只要**中途崩掉**（编码 / 异常），它前面所有
> `SELFCHECK OK` 都不会打印，但如果它崩在**最后一行**（本例正是），退出码是 1 而输出看上去「几乎跑完」。
> 因此看自检结论时**必须看退出码**，不要只看有没有刷红。

**⚠️ 老日志必然判 FAIL，这是设计内**：`[rscc-build]` 这一行只有「本轮之后编译并启动」的会话才有。
因此升级后第一次跑自检会看到 `B1` / `B2` 失败 —— 正确处置是**重新编译 + 重开游戏 + 重跑一次产线**，
而不是把断言改回去。

**仍未做（本轮没碰，下一轮或用户实机时再定）**：
① §7.3 建议的「在制过渡件数量 vs 订单量」这一指标（当前账本结构上测不出多余中间产物，本轮只让账本会说话）；
② `FACE` 输出模式补闸门（§6.1 第 4 条）；
③ `SequencePatternTerminalScreen` 的两个候选缓存加清空（§6.1 第 6 条）。

### 7.5 2026-10-05：定位并修掉「下单 2 秒就被判执行器掉线」（§6.1 第 5 条的真凶）

#### A. 严重 bug：`now - Long.MIN_VALUE` 溢出 ⇒ 每台执行舱恒定报「推不动」

**现象（用户实机）**：补货后下单精密构件，**第一个就失败**，提示「机器满了」，
而机械手与置物台**完全空着**。§7.5 修掉「空闲期证据自锁」之后**现象依旧**。

**根因**：`destinationRefusalAt` / `stepOwnerMissingAt` 用 `Long.MIN_VALUE` 当「从未发生」的哨兵，
判定写成 `now - stamp <= WINDOW`。64 位相减**溢出回绕成负数**：

```
1546120 - (-9223372036854775808) = -9223372036853229688   // 负数
-9223372036853229688 <= 60                                // 恒为 true！
```

于是**每一台执行舱从加载那一刻起、在从未尝试过任何搬运的情况下**都恒报「推不动」：
`fillInternalForBus()` 第一道闸门直接 return（一次都不试），看门狗 1 秒扫到就判
`OUTPUT_BLOCKED` 并挂起（实测 `stall=41`）。这解释了全部观察：
`offlineSteps="-"`、`missingMaterials=0`、`estimated=5`（料够）、`storedItems` 全空、
**会话日志里 `push {` / `[rscc-trace]` 行数为 0**。

**修复**：哨兵改为 `NEVER = -1_000_000L`（远早于任何存档 gameTime，相减不溢出，
`now - NEVER` 恒大于窗口）。同类模式**顺带修掉两处**（`SchematicLoaderBlockEntity` 的火药提示
与蓝图解析提示限频，同样会因溢出而永久抑制首条提示）。

**为什么 §7.5 的修复看起来「没生效」**：那条修复（新订单边沿清空证据）本身是对的、也确实执行了
（日志里有 `gate … relevant=true` 的边沿），但**清空之后算术又立刻把哨兵算成「刚刚发生」**，
所以判定仍为 true。两个 bug 叠在一起：先修掉可观测性，真凶才浮出水面。

**回归锚点**：`selfcheck_round21_pattern_dedupe.py` 的 2c-1 / 2c-2 —— 明确断言哨兵**不是**
`Long.MIN_VALUE`，且 `NEVER` 存在。任何人把哨兵改回去，自检立刻红。

### 7.6 2026-10-05 第二轮：归流缓存仓「删除模式」（用户要求的复选框式清资源功能）

（本节内容见下）

### 7.7 2026-10-05 第四轮：一仓多配方时「没人下单的配方被开工」（实机日志定位）

#### A. 用户实测（21:30 那次会话，日志 + 3 份快照）

| 现象（用户原话） | 日志/快照证据 |
| ---------------- | ------------- |
| 「我并没有下单列车轨道，他给我推掉了……开始装这个列车轨道」 | `chamber@(-5,-60,6) unblock_recovered {item=create:incomplete_track x1} from=(-7,-60,5)` ×3；快照 `materials` 里同时有 `create:incomplete_precision_mechanism` 与 `create:incomplete_track` |
| 「他给我发了这两台机器都给我发了一个石头台阶」 | `pull {item=minecraft:stone_slab x1} target=1` ×8；`flowLedger` 里 `stone_slab fromNetwork=8` |
| 「另外一个你们还可以继续跑但是它却自动挂起了」 | `shortage.suspended[0].reason=OUTPUT_BLOCKED stallCause=DESTINATION_REFUSED stallResource=create:incomplete_precision_mechanism`（**真正被堵的是精密构件**，却因为整仓一把闸而挂起） |
| 「他正常回收了……中间产物之类的东西在挂起的时候正常回收了这挺好的」 | `unblock_recovered` 共 5 条（`incomplete_precision_mechanism` ×2 + `incomplete_track` ×3） |
| 「文文还是只有铁然后集中一个物件这里他的铁力那一部分又显示铁力又显示心力显然是被带偏了」 | 两个输入类别在快照里是分开的（`input:minecraft:iron_nugget` 含 `[iron_nugget, zinc_nugget]`、`input:minecraft:stone_slab` 含三种台阶），但**两条配方共用同一个 `create:deploying` 类别** ⇒ 界面把两条配方的输入混在一起显示 |
| 「候选自愈生效」 | `terminal candidate-heal: 2 step(s)/ingredient patched from recipes` |

#### B. 根因：闸门是「整仓一把」，而用户按正当需求一仓放了两条配方

`relevantTaskState()` / `hasGoalTask()` 都是**整个执行舱级别**的判据：

- `hasGoalTask()` = 「本仓所负责的任意一条配方有活跃订单」；
- 用户把**列车轨道**与**精密构件**的单元样板放进**同一个** `create:deploying` 执行舱
  （两条配方的机械手步都是 `create:deploying`，物理上只能共用这台机械手，这是正当用法）；
- 于是他给精密构件下单 ⇒ 整仓闸门开 ⇒ 本仓「负责」`track#0`/`track#1` ⇒ **继续做没人下单的列车轨道**；
- 列车轨道的输入类别（石头台阶三种、铁粒/锌粒）被一并补料 ⇒ 仓里出现列车轨道起步原料 ⇒ 真的开件。

**修法不是禁止一仓多配方**（那是用户的正当用法），而是把闸门**从整仓细化为按配方**：

| 新增 | 作用 |
| ---- | ---- |
| `orderedRecipes()` | 返回「本仓此刻真的有活跃订单」的配方 id 集合。判据与 `hasGoalTask()` **同源**：只认该配方的**过渡件 / 产物**被活跃任务点到，投入物与起步原料绝不算有单（否则定量保持器维持库存又会被当成订单） |
| `recipeOrdered(recipeId)` | 成员判定；缓存 `orderedRecipesCache` 随门控复查（10 tick / 挂起-继续事件）重建 |
| `startStepAt()` 加闸 | 没有订单的配方**不开件**（原来只要「本仓负责」就开） |
| `categoryOwned()` 加闸 | 没有订单的配方**不生成备料需求**（原来只要「本仓负责该步」就拉料） |

两处用的是**同一把闸门**，因此「不补料」与「不开件」永远一致，不会出现「拉了料却不开工」。

#### C. 这一轮同时暴露并修掉的其它两处

1. **总样板 tooltip 把多种原料拼成一行文字**（用户明确禁止）：
   新增 `AssemblyInputsTooltipComponent` + `SequenceAssemblyPatternItem.InputsImage`，
   总样板 tooltip 的原料区改为**图标 + 名称一起轮播**（与单元样板同一口径、同一墙钟节拍）；
   旧拼接键 `input_any` / `ingredient` 已删除，新增 `input_count`。
   `tools/selfcheck_no_material_joining.py`（16 条）钉住「禁止拼或/括号」。
2. **主原料候选在总样板里只登记代表物一件**（第 3 轮已述）：`mergeInput(items, data.candidatesOrRepresentative(), …)`。

#### D. 仍然开放的一条（本轮未能定位）

`stuck {item=create:zinc_nugget station=(-7,-58,5)}` 只有一条、且**没有对应的回收记录**
（`selfcheck_flow_conservation.py` 的 5e 因此红着）。它来自**没人下单的列车轨道**那条配方，
因此按本轮修复应当不再发生；但「为什么这一件既没被自愈收走、也没被机器消耗」**尚未证实**。
下一次实机若仍出现 `stuck` 而没有 `unblock_recovered` / `unblock_unavailable` / `unblock_skipped`，
那就是一条新的、需要单独定位的路径。


### 7.9 2026-10-05 第五轮：**「改了没生效」的元级原因**（先看这一节）

#### A. `tools/manual_compile.ps1` 不是构建 —— 它只做语法检查

| 命令 | 输出目录 | 游戏是否加载 |
| ---- | -------- | ------------ |
| `tools\manual_compile.ps1` | `build/manual_compile/` | **否**（独立目录，只验证能否编译） |
| `gradlew.bat compileJava`（或 `runClient`） | `build/classes/java/main/` | **是**（NeoForge 开发环境从这里读类） |

**这一节存在的理由**：某一轮我在 22:2x 之后连续改了 5 个修复，每次都跑 `manual_compile.ps1` 看到
`COMPILE OK`，就以为已生效；而用户 22:34 那次实机跑的仍是 **22:34:54 的 Gradle 构建**，
class 文件时间戳（`build/classes/java/main/.../SequenceExecutionChamberBlockEntity.class = 22:34:58`）
证明我的改动**一个都没进去**。于是用户反复报同一个现象，我反复「已修」—— 双方都没错，是构建路径不同。

**判定方法（一条命令自证，别再靠感觉）**：

```powershell
$c = Get-ChildItem build\classes\java\main -Recurse -Filter 'SequenceExecutionChamberBlockEntity.class' | Select-Object -First 1
[System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($c.FullName)).Contains('anyRecipeDeclaringOrdered')
```

- `True` = 运行类里确实有新代码；`False` = 你在跑旧构建（先 `gradlew compileJava`）。

#### B. 本轮修掉的三个真问题（都已进运行类）

1. **老格式输入类别绕过闸门**（快照实证：类别 id 仍是 `input:minecraft:iron_nugget`）。
   我上一版对「没有配方段的旧 id」直接 `return true`，等于这道闸门对老存档**不存在** ⇒
   没下单列车轨道仍会 `pull stone_slab`。现在回退为
   `anyRecipeDeclaringOrdered(items)`：按「该物品在本仓哪几条配方里是投入物」判定，
   **只要任意一条有活跃订单就放行，一条都没有就拦下**。
2. **tooltip 里主原料与输入原料重复**（用户：「原料在原料里面有一个、在输入时原料里面又出现了一次」）。
   `collectInputs` 会把主原料并进 items，而上面已单独写过「原料：」一行 ⇒
   「输入原料」段现在**剔掉主原料候选**，两段严格互斥。
3. **输入类别按配方限定**（`input:<配方id>#<物品>`）：精密构件与列车轨道都在机械手步声明铁粒，
   旧 id 会把两条配方的候选并进同一类别 ⇒ 精密构件被显示成「铁粒或锌粒」。

#### C. 一个工位堵住 ⇒ 整仓停工（用户实测：x1 正常、x64 中途停，且「齿轮只堵一个，另一个也停」）

**快照实证**：`stallCause=DESTINATION_REFUSED`、`stallResource=create:golden_sheet`、
`stallChamber=-5,-60,6`、`stallTicks=172`、`suspended=true`，而 `missingMaterials=0`。

**根因**：`destinationRefusalAt` 是**全仓一个**时间戳，且**成功投料时不清它**（只在「新订单开始」时清）。
于是任何<b>一台</b>机器 / 置物台拒收一次 ⇒ `pushStalledOnDestination()` 为真 60 tick ⇒

- `fillInternalForBus()` 整段停手（不备料 / 不投料）；
- 看门狗 1 秒扫一次 ⇒ 判 `OUTPUT_BLOCKED` ⇒ **挂起整条任务**。

而本仓接**两台**机械手（用户配置）：一台手里压着一件、另一台空着能收 —— 却被一起停掉。

**修法（按目标分别判定，2026-10-05）**：

| 改动 | 说明 |
| ---- | ---- |
| `refusedTargetAt`（`Map<BlockPos, Long>`） | 每个供料目标各自的最近拒收时刻，取代「全仓一个」 |
| `rscc$notePushSucceeded(target)` | 输出总线**成功投料**时调用：清掉该目标记录 + 记 `lastSuccessfulPushAt` |
| `pushStalledOnDestination()` | `stepOwnerMissing` 仍是全仓性质（照旧成立）；`DESTINATION_REFUSED` 改问 `allKnownTargetsRefusing()` |
| `allKnownTargetsRefusing()` | ① 窗口内有成功投料 ⇒ 没堵；② 窗口内**只有一个**目标拒收 ⇒ 没堵（那台清一下即可，不是下游整体堵）；③ **≥2 个不同目标**都拒收 ⇒ 才算真堵 |
| `clearPushStallEvidence()` | 一并清 `refusedTargetAt` / `lastSuccessfulPushAt`（否则逐目标判定仍用旧记录） |

### 7.10 2026-10-05 第三轮：置物台堵塞自愈 + UI 按用户逐条反馈重排

#### A. 真正的「齿轮堵塞」：废料压在置物台上，没人回收

**用户实机描述（这次说清了机制）**：精密构件装配**有概率失败**，失败时废料（例如小齿轮）会留在
置物台上；而小齿轮又恰好是后面某一步的投入物 ⇒ 输出总线要推小齿轮时置物台**拒收**（它手里已有一件）
⇒ 本仓被判「推不动」⇒ 看门狗弹「机器满了」并挂起。用户实测：**做 12 个的期间被挂起 4~5 次**，
点「继续」正常一会儿又卡住。取消后金板回网、**中间产物却留在置物台上**（此时仍堵着工位）。

**拒收条件已从 Create 源码确认**（`local_src/create_src/.../deployer/DeployerItemHandler.java:63`）：

```java
if (!ItemStack.isSameItemSameComponents(held, stack))
    return stack;   // 手里已有件且不是同一件 ⇒ 原样退回 = DESTINATION_DOES_NOT_ACCEPT
```

即机械手（deployer）**一次只认一件**：手里压着「上一步 / 上一条配方留下的件」时，后面任何**不同**的
件都会被拒收 —— 这就是「推不动」的物理原因，也是「机器其实是空的、只是有一件占着工位」的准确含义。
`extractItem(slot, 1, false)` 能把手里那件取出来（唯一例外：玩家给机械手设了 filter 且命中该件，
那属于玩家显式锁定，本仓不去抢）。

**为什么输入总线收不走它**：输入总线走 RS 过滤器（类别勾选），一件「不在任何类别里」的废料
不命中过滤器 ⇒ 实测日志里它只输出 `took=- result=RECLAIM_ONLY`，一件都没真正收走。

**修复（`SequenceExecutionChamberBlockEntity`）**：

| 项 | 内容 |
| -- | ---- |
| 记证据 | `rscc$noteDestinationRefusal(target, resource)`：拒收时记下**哪台供料目标 + 哪一件**（原来只记时刻，无法定位） |
| 自愈 | `recoverBlockingTargetItem()`：从那个坐标的物品能力里取出占位件 → 进本仓内部存储（`flowLedger.fromMachine` 记账）→ 由既有回流写回网络 |
| 调用点 | `tickEngine` 最前面，**不受任何门控**（回收 = 还东西，不是开工） |
| 保守边界 | ① 只在 60 tick 窗口内真的拒收过才动手；② 只碰**本仓这条产线**的件（本配方过渡件按产物名反查 / 本仓输出类别的废料·成品），输入类别不算；③ **关键竞态保护**：置物台上那件 == 我方想推的那件时不碰（那是机械手正常放置，收走等于抹掉它的活）；④ 本仓装不下就原样留在来源，绝不销毁 |
| 解堵日志 | `unblock_recovered {item=…} from=(x,y,z) reason=target_held_item_blocking_next_push` |

**同时更正的实验协议**：`docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md` 的 D 段原来写「用创造栏拿小齿轮手动放到
置物台上」—— 那是我上一轮**误解**了用户的意思。实机形态是**装配失败自己留下的废料**，不需要人为摆件。

#### B. UI 按用户逐条反馈重排（全部照搬 RS 原版观感，不引入新风格）

| 用户反馈 | 处置 |
| -------- | ---- |
| 「开启删除模式这个弹窗文字超出背景了非常的丑……说明太废话了」 | 确认窗口从 5 行长句砍到 3 行短句；面板 236→208；**新增按像素宽度硬截断**（`trim()`），任何语言 / 改文案都不会再越界 |
| 「开启删除模式有这一个就够了，那个清除缓存区的按钮就删了」 | 「销毁缓存区」独立入口与删除模式复选框**合并成一颗按钮**：标签状态化（`销毁` / `删除：开`），点击打开**同一个**确认窗口（三选一：立即销毁 / 删除模式开·关 / 取消） |
| 「删除模式这几个字和这个按钮离得太远了……三种模式和它对应的那个按钮整体右边一」 | 三个模式控件（**全收 / 反转 / 删除**）与「销毁」按钮**并排一行**，标签与按钮间距统一 2px（`LABEL_GAP`）；原来「反转」按钮在 206、标签在 170（差 36px）也已收紧 |
| 「配置匹配设置那里那个直接销毁那几个字和那个按钮离的还是太远了」 | `CollectionMarkerConfigScreen`：按钮 210→184，标签右缘距按钮 2px，并加像素截断 |
| 「升级槽 / 插件槽不要按 shift 查看详情，全部模仿精致存储原版那样子的长显」 | `UpgradeSlotTooltips.build()` 去掉 `shiftComponent` 分层，改为**常显**。已反编译核对 RS `AbstractBaseScreen#getUpgradeTooltip`：它就是「空槽标题 + 逐个允许的升级」，**完全不带按键修饰** —— 本模组原来那个 Shift 分层才是「割裂」的来源 |
| 「禁止废话……任何原版已有的东西照搬」 | 本轮所有新增文案压到 ≤ 12 字（中文）/ ≤ 5 词（英文），全部走既有 `McGui.toggle` 三态控件与原版 `Button`，不新增控件风格 |

**新增自检**：`selfcheck_depot_unblock.py`（22 checks，U1~U7）钉住堵塞自愈的每一条边界，
特别是那条竞态保护 —— 没有它，自愈会把机械手刚放好的过渡件又收回去，变成来回搬运。

#### C. 候选组不轮换：断点是「老样板 NBT 里根本没有候选」

**决定性证据（不靠推断）**：

| 证据 | 事实 |
| ---- | ---- |
| `tools/inspect_save_candidates.py`（本轮新增，解压关服存档逐 chunk 搜键名） | 存档里有 `DisplayArrangement` / `sequence_unit_pattern`（10 次）/ `sequence_assembly_pattern`（2 次），但 **`InputCandidates` 出现 0 次** |
| 11 份 `run/rscc_diag/*/snapshot.json` | `InputCandidates` 一次都没出现 |
| 源码核对（子代理逐行） | 写入链**是通的**（JEI → 包 → 菜单 → `writeUnit` → `writeCandidates`），读取键也**完全一致**（`"InputCandidates"`）；轮播驱动也是好的（`GhostMarkerRenderer` 用 `gameTime/CYCLE_TICKS=20` 真的换图标） |

⇒ 真因：候选只可能在「JEI 导入那一刻」写进样板物，而 **2026-10-04 18:02 修复之前**导入 / 生成的样板
从来没有这个 tag，且**没有任何迁移**。界面回落顺序「配方回查 → 样板落盘候选 → 代表物一件」里，
落盘候选那一级对老数据恒空 ⇒ 列表长度恒为 1 ⇒ `size() > 1` 的轮播分支永远不进。
这就是「修复毫无成效」的原因 —— 修复本身是对的，只是永远没被喂到数据。

**本轮修复（四条）**：

| # | 内容 |
| - | ---- |
| 1 | **老样板自愈** `SequencePatternTerminalBlockEntity#healUnitCandidateTags()`：逐步骤对「已记配方 id 但候选 < 2」的步，用 `applicationCandidatesOf` 按配方重算，≥2 才写回（单件保持老格式）。幂等。调用点 = `SyncStepMachinesPacket#from`（界面一打开即补），**因此不必让玩家重新导入** |
| 2 | **起步原料候选落盘**（顶部「石头台阶」那一格的结构性缺口）：`AssemblyData` 新增 `ingredientCandidates` 分量（保留旧 5 参构造器）+ `writeAssembly`/`readAssembly` 对称读写；`SetSequenceImportPacket` 新增字段；JEI 用 `SequencedRecipeProbe.candidates(input)` 算整组；终端新增 `ingredientCandidates` 字段并随 NBT 持久化 |
| 3 | **修两个「重建抹字段」的真 bug**：`bindStepCrafter` / `unbindStepCrafter` 原来走 5 参 `UnitData` 构造器，把 `inputCandidates` / `requiresInput` / `displayName` 一起丢成默认值 —— 只要玩家绑定/解绑过一次执行仓，候选组就被抹掉（之前靠「`writeCandidates` 不删旧 tag」侥幸）。现在整条透传 |
| 4 | **空结果不入缓存**：`stepInputCache` / `assemblyInputCache` 只在非空时写入。首帧配方未就绪时算出的空表若被缓存，整局都只显示代表物 |

**新增自检**：`selfcheck_candidate_pipeline.py`（28 checks，C1~C5）覆盖上表四条 + 导入链 + 轮播驱动。

**⚠ 第三处（本轮才补上，之前一直漏了）**：主原料候选虽然落了盘，但**总样板登记时仍写死代表物一件** ——
`SequenceAssemblyPatternItem#collectInputs` 原本是 `mergeInput(items, List.of(data.ingredient()), …)`。
后果：RS 合成树只认「石头台阶」，平滑石 / 安山岩台阶被判成「不是这条配方的料」，
即使 NBT 里躺着整组候选也没用。现已改成 `data.candidatesOrRepresentative()`，与单元样板同一口径。
这一处正是「石头台阶」那个现象的**最后一跳**。

**⚠ 仍需玩家做一次的事**：执行器里那张**旧总样板**必须先拿走 / 换掉再重新生成 —— RS 按样板 NBT 的
UUID 缓存样板，不重新生成就不会重注册 `Ingredient` 的候选。自愈只解决「界面显示」，RS 样板那层必须重生成。

#### D. 「取消后中间产物没回网络」= 同一个置物台堵塞（(1) 与 (2) 是同一件事）
用户原话：「点取消之前左边的是金板右边的是中间产物，点取消之后金板回去但中间产物没有回去」。
机制：取消时 `flushResidualInputs()` 只回**本仓内部存储**（金板在仓里 ⇒ 回得去），
而中间产物**压在置物台上**，要靠输入总线回收 —— 输入总线走 RS 类别过滤器，
一件落在中间产物类别外的件不命中过滤器（实测 `took=- result=RECLAIM_ONLY`）⇒ 收不走 ⇒ 继续堵着工位。
因此 (1) 与 (2) 是同一个根因，`recoverBlockingTargetItem()` 一并解决。

**本轮补的收尾窗口**：原实现只在 `PUSH_STALL_WINDOW_TICKS = 60`（判定窗口）内回收。
任务结束 / 取消之后输出总线不再尝试 ⇒ 拒收时间戳变旧 ⇒ 那一件永远收不回来。
现在拆成两级：
- `age <= 60`：故障仍在持续（保留「手里那件 == 想推那件」的竞态保护，避免抹掉机械手的活）；
- `60 < age <= REFUSED_TARGET_RECOVER_TICKS = 200`：**收尾窗口** —— 任务已结束时不可能再有机械手推进，
  因此放宽竞态保护、照收（这正是「取消后中间产物卡在置物台上」的情形）；
- `age > 200`：坐标可能已被别人复用 ⇒ 清掉目标、不碰。

#### E. 顺带修掉的一个真问题：语言键「删了但代码还在用」

`destroy.button.on` 等 5 个键在改版时被清理脚本删掉，但 `CollectionDestroyConfirmScreen` 的第三个按钮
与 tooltip 仍引用 `delete_mode.action*` —— MC 只会显示**原始键名**，不报错。
因此新增 `tools/verify_lang_refs.py`：扫描源码里两种写法（字面量 + 前缀常量拼接），
断言每一个被引用的键在中英文里都存在（当前 430 个键，0 缺）。这条已进自检清单。


**用户原话**：「有一个状态就是一个复选框……选择之后可以开启清除资源功能……
首先先要开启那个删除模式，然后这个匹配的资源……要对匹配区的那些标记的物品再次进行这种标记
是否要表达他们删除，然后标记要删除的物品在开启了删除模式下才会被删除。然后这一个速度和删除的
速度和流经网络的速度保持一致，所以说这一个删除的速度也会受到速度升级和堆叠升级的影响。」

**改动前**：只有「每个匹配槽一个直接销毁勾选」，**没有任何总闸** —— 勾上就立刻删，不可逆。

**改动后**：

| 层 | 内容 |
| -- | ---- |
| 总闸 | `CollectionCacheBlockEntity#deleteMode`（NBT `DeleteMode`，**默认关闭**，老存档读出也是关闭）。界面上是「缓存区」标题带里的复选框（复用 `McGui.toggle` 三态控件，位置 `DELETE_MODE_BTN_X/Y`） |
| 槽级 | 原有「标记为删除」（`destroyMarkers`）保留，但现在**只记录意图**；总闸关闭时销毁路径一个字节都不动 |
| 判定 | 必须**同时**：① 资源命中某个匹配槽 ② 该槽勾了「标记为删除」③ 总闸开启 |
| 速率 | 与回流**同源**：每 tick 处理组数 = `getProcessRate()`（1 + 速度升级）、单次吞吐 = `getTransferBatch()`（64 × (1 + 堆叠升级)）。单次销毁量被吞吐夹住，不再整格一次删光 |
| 确认 | `SetCollectionDeleteModePacket(containerId, enabled, confirmed)`：**开启**未带确认位服务端直接忽略；**关闭**无需确认（恢复安全状态）。界面「开启」只开 `CollectionDeleteModeConfirmScreen`，不发包 |
| 同步 | `ContainerData` 新增槽 27/28（映射到方块实体 22/23）—— 菜单与方块实体槽号**必须显式映射**（红石模式曾因漏映射而恒显示「忽略」） |
| 导出 | 快照新增 `deleteMode` / `deleteRatePerTick` / `destroyMarkers` ——「我以为我开了，其实没开」不再靠猜 |

**不变的东西**：一键「销毁缓存区内容」按钮（`destroyAllCacheContent()` + 确认子窗口）**保持原样**：
它是玩家显式确认的**一次性全清**，按定义不受持续删除速率约束，也不需要总闸。

**新增自检**：`selfcheck_collection_delete_mode.py`（43 checks，D1~D8）—— 逐条钉住上面每一项，
含「总闸第一道闸门」「速率同源」「确认位硬校验」「菜单→方块实体映射」「中英键成对」。

#### A2. 该 bug 的实机取证链（第一版修复的原始记录）

**用户报告**（原话）：先用定量保持器补货，补货完成后合成精密构件，**第一个就失败**；
界面提示「机器满了」，但机械手与置物台**完全空着**。用户同时导出了两份快照
（`run/rscc_diag/20261004-195335` = 刚进游戏、`20261004-195627` = 下单失败之后）。

**取证（只用日志 + 两份快照，没有猜测）**：

| 证据 | 值 | 排除了什么 |
| ---- | -- | ---------- |
| `shortage.suspended[].reason` | `EXECUTOR_OFFLINE`（两条任务） | 确认走的是「掉线」通道 |
| 同一条记录的 `offlineSteps` | **`-`（空）** | **排除**「真的有步骤掉线」——真掉线时这里会列出第几步 / 哪台仓 |
| `missingMaterials` | `0` | **排除**缺料（横幅若走缺料会列原料） |
| 三条 `input:` 类别的 `estimated` | `5 / 5 / 5`（订单 x1，loops=5） | **排除**料不够 |
| 三个执行仓的 `storedItems` / `storedFluids` | 全空 | 确认「本仓从没尝试过搬料」 |
| 会话日志里 `push {` / `[rscc-trace]` 行数 | **0** | 确认从来没有一次推料尝试 ⇒ 拒收时间戳不可能来自本会话的推料 |
| `binding` 行 | `step=0/1/2 chamberExists=true nextStepOwner=chamber@(-5,-60,6)` | 排除「某步没机器认领」（三步都有属主） |

**根因（自锁链，五步）**：

1. `SequenceExecutionChamberBlockEntity#pushStalledOnDestination()` 只看两个时间戳的**60 tick 窗口**：
   `destinationRefusalAt`（下游拒收）与 `stepOwnerMissingAt`（某步无机器认领）；
2. 这两个时间戳会在**没有订单的空闲期**被置位（补货期间仓仍在收料 / 判步）；
3. `fillInternalForBus()` 的第一道闸门就是 `if (pushStalledOnDestination()) return;`
   ⇒ 本仓**整段停手，一次都不尝试**；
4. 玩家下单，门控刚开（`gate … relevant=true`），**但时间戳还没过期**；
5. 看门狗 1 秒扫一次读到 `pushStalled=true` ⇒ 判 `EXECUTOR_OFFLINE` 并挂起（实测 `stall=41`，
   即不到 1 秒）；本仓随之冻结 ⇒ 永不尝试 ⇒ 时间戳永续 ⇒ **任务永远起不来**。

并且 `pushStalledOnDestination()` 把**两种完全不同的原因**压进同一个布尔值，横幅只能说一句
「执行器掉线」—— 于是玩家被告知去查一台根本没坏的机器。这正是「我讲错你就修错」的机制。

**修复（三处，全部只改判定与文案，不改搬运行为）**：

| 文件 | 改动 |
| ---- | ---- |
| `SequenceExecutionChamberBlockEntity` | 新增 `StallReason`（`DESTINATION_REFUSED` / `STEP_OWNER_MISSING`）+ `stallResource` + `noteStall()`（原因签名变化时打一条 `push_stalled reason=… resource=…`，让「第一次为什么推不动」永远可见）；新增 `clearPushStallEvidence()`；`tickBusScheduler` 里新增**「新订单开始」边沿**（`relevant && !busRelevantTaskGate && !frozen`）⇒ 清空上一条空闲期的证据 |
| `AssemblyWatchdog` | `taskChamberPushStalled` 从 `boolean` 升级为 `StallHit`（哪台仓 / 什么原因 / 哪个资源）；「推不动」按原因分成两个 reason：某步无机器认领 ⇒ `EXECUTOR_OFFLINE`，下游拒收 ⇒ **新增 `OUTPUT_BLOCKED`**；横幅走各自的文案；快照新增 `pushStalled` / `stallCause` / `stallChamber` / `stallResource` |
| `SyncAssemblyAlertsPacket` + `AutocraftingMonitorScreenMixin` + 双语 lang | 新增 `REASON_OUTPUT_BLOCKED = 5`（追加在末尾，不动既有序号）与 `monitor.suspended.output_blocked` 文案：「下游机器满 / 不接受（机器在线）」 |

**为什么「新订单开始就清空证据」是语义正确的**：那两条时间戳回答的是「**当前**这一段为什么会卡」。
上一段空闲期的观察不能用来判定一条刚下单的任务 —— 判定必须是「这条订单自己也动了、还是推不动」。
清空后若阻塞仍然存在，本仓会在下一个引擎节拍（≤5 tick）重新观察到（真拒收会在 60 tick 窗口内再次置位），
因此**不会漏报真实故障**，只是不再用「上个订单的旧证据」把新订单直接判死。

**同时补的取证能力（用户直接要求）**：

| 新增 | 说明 |
| ---- | ---- |
| `machines` | **每一个方块实体**一条：方块 id / 类型 / 实现类 / 自述（若有）/ **完整 NBT**（截断 4000 字符）。NBT 就是存档里的真实设置（面模式 / 绑定 / 队列 / 升级槽 / 目标量…），永远与写入逻辑一致 |
| `containers` | **每一个容器**的非空槽逐格列出（槽位号 / 物品 / 数量 / 伤害 / CustomData 截断 1200 字符）+ 总容量 |
| `universalStorage` | 每个**通用储存盘**的容量与资源表（取物品 CustomData）—— 网络里到底有多少料的权威答案 |
| `playerInventory` | 玩家名 / 维度 / 坐标 / **手持物** / 副手 / 背包里所有 `rs_create_compat:` 与 `refinedstorage:` 物品（不做整包噪声） |
| `RsccDiagnosable`（新接口） | 机器自述：`SchematicLoaderBlockEntity`（自动打印 / 回收 / 填火药 / 拉取上限 / 集群行数 / 蓝图槽上锁 / 队列 / 升级 / 附着加农炮）与 `CollectionCacheBlockEntity`（三轴半径 / 扫描 / 吸收四开关 / 全收 / 反向 / 输入面掩码 / 经验形态 / 每个 ghost 标记的 id / 数量 / NBT / 标签 / 销毁 / 阻塞清单） |
| 顺带修的假阴性 | `verify_build_stamp.py` 的 `B3` 直接拿 `e347372+dirty` 与 `e347372` 比较 ⇒ 每次「边改边测」都被误报成「日志来自另一版代码」。现在比较去掉 `+dirty` 的 hash，dirty 单独作为一条 note |

**验证**：`manual_compile.ps1` → `COMPILE OK (325 sources)`；`selfcheck_*` → **37/37 exit 0**
（其中 `selfcheck_diag_facility.py` 由 99 → **133 checks**，新增 ③b 段专门钉住「全机器 / 全容器导出」；
`selfcheck_assembly_watchdog.py` 与 `selfcheck_round21_pattern_dedupe.py` 的两条旧锚点钉的是
「推不动一律叫 EXECUTOR_OFFLINE」这句已证伪的写法，已改为钉「两种原因各自成立」）。

**仍未验证**：修好后的行为**没有实机复测**（我无法启动游戏）。请按下面的最小步骤复测：

```
tools\manual_compile.ps1  →  关掉游戏重开  →  重复「补货 → 下单精密构件 x1」
→ python tools\verify_build_stamp.py        （应 OK）
→ python tools\analyze_blockage_experiment.py
→ 若仍被挂起：/rs_create_compat diag，然后把 run\rscc_diag\latest\snapshot.json 发回
   （这次它里面有 stallCause / stallChamber / stallResource，能直接指出是哪台仓、什么原因）
```


***

## 8. 关键设计决策

1. **单元样板查重口径**：必须「同配方同步骤」才算重复（`UnitPatternDedupe#sameSemantics`，见 `support/UnitPatternDedupe.java` 第 109-147 行）。理由：没有输入式原料的操作（冲压 / 切割）「看起来一样」，跨配方判重会让整条排线被跳过 ⇒ 绑定 NOBODY ⇒ 下单毫无反应。
2. **流量守恒不变式**：`retained + destroyed = fromNetwork + fromWorld − toNetwork`（`RsccFlowLedger` 类注释，第 13-50 行）。理想情况未对平 = 0；差额只作诊断、不做硬崩溃，因为世界里有本模组之外的搬运（玩家手动 / 别的模组 / 集群拆分 / 区块卸载 / 读档基线）。
3. **齿轮堵塞判定**：W1-W4 等待豁免（W1 任务挂起冻结 / W2 缺料等待档 / W3 补合成在途 / W4 可合成但网络缺它；「料已压在工位上」不算豁免理由）+ 连续 **60 tick**（`STATION_STUCK_TICKS`）无推进才判卡住并收回；判卡结论**粘住**，只有「步序签名变化」或「`STATION_STUCK_REARM_TICKS = 200` 慢重试窗口到期且工位不再压着该件」才撤销。唯一实现在 `SequenceExecutionChamberBlockEntity#stationStuckOn` / `#hasWaitReason`（**不在 `AssemblyWatchdog`**，后者只做 RS 任务挂起），见 `docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md`。
3.1 **标签型输入的「一组候选」语义**：一个 ingredient 的多个候选是**一个**原料 —— 备料任一即可、缺料任一有货即不缺、RS 样板里登记成**同一项 ingredient 的多个候选**（代表物在首位 = 优先消耗顺序）。候选组必须随单元样板 / 总样板落盘（`TAG_INPUT_CANDIDATES`），因为 `getTooltipImage(ItemStack)` 拿不到 `Level`、无法回查配方。
4. **补合成请求量公式**：`目标 − 网络 − 本仓 − 机器侧 − 在途`。确定性配方一次请求所需量；概率配方分批递进 + 40 tick 冷却（避免失败消耗导致缺料预填过量）。
5. **概率配方处理**：分批递进 + 40 tick 冷却，按成功率估算期望尝试次数。
6. **输出总线流向**：先搬进执行舱内部存储 → 再从执行舱输出至目标机器（避免直接从网络到机器，便于守恒与缓存）。
7. **挂起后不自动恢复**：玩家拆机器常常是故意的（要顺手加机器 / 改配置），自动恢复会让任务带着旧配置又跑起来。挂起超上限后按配置决定「继续挂起」或「安全回收」（调 RS 自己的取消路径，物品原样还回网络）。
8. **集群 = 主控唯一载荷**：主控（坐标字典序最小者）建出共享载荷，所有成员采纳同一对象；落盘边界只有主控保存内容，其余成员写空载荷，从根上排除「同一份内容被写两份、读档合并后翻倍」。
9. **集群 BFS 不每 tick 重算**：事件驱动失效（`RsccClusterInvalidation`，邻块变化时作废缓存）+ 缓存命中 20 tick 一次成员存活校验 + BFS 阶段只读方块状态绝不触达方块实体 + 规模上限 `MAX_MEMBERS` 防止超大结构拖慢。
10. **终端「导入 = 只展示，生成 = 才产出」**（v8 核心）：导入配方只把展开后的流程写进**展示数据**（`displayArrangement`），不产生任何真实物品；拆方块只会掉出玩家真正投入过的东西。展示数据可持久化（NBT 键 `DisplayArrangement`），老存档里 `arrangement` 已有的样板照常显示与掉落，一格不丢。
11. **总样板槽「只出不进」**：内部容量 9 格（兼容老存档），界面只暴露第 1 格；该格被取空时自动前移后面的样板（`compactPatternSlots()`）。用户要求「要再生成新的，必须先把现有的全部拿走」。
12. **tooltip 分层**：`Shift` = 核心机制 / 用途、`Ctrl` = 数值 / 配方 / 统计、`Alt` = 调试 / 来源；多键同按 = 多层同显。所有附加 tooltip 一律经 `RsccTooltipLayers` 过滤后再渲染，各处不得自行判断 Shift / Ctrl / Alt。
13. **总线类别 id 规则**：`input:<item>` / `intermediate:<step>`（带步序）/ `result:<item>` / `scrap:<item>` / `fluid:<fluid>`，**类别数量不设上限**（列表可任意长，界面靠滚动翻页）。
14. **类别共享语义**：同一类别可被多台输出总线同时选中（`sharedCount > 1`），由执行仓按轮询在它们之间均分。
15. **流量台账的世界收集与销毁必须单列**：归流缓存仓的资源来自世界（不是网络），若不记入「离开」一侧会平白多出无法解释的「留存」；销毁若伪装成回流会彻底丧失取证价值。

***

## 9. 调试技巧

### 9.1 日志位置

- `run/logs/latest.log` —— INFO 级，`[rscc-build]` / `[rscc]` / `[rscc-assembly]` / `[rscc-trace]` / `[rscc-ledger]` / `[rscc-dedupe]` 都在这里

- `run/logs/debug.log` —— DEBUG 级，**RS 自己的任务行在这里**（`Created task …`、`Task … state changed from RUNNING to COMPLETED`），只有它能证明「RS 任务真的跑完了」

### 9.2 关键日志标签

| 标签                | 含义                                                             |
| ----------------- | -------------------------------------------------------------- |
| `[rscc-build]`    | **构建指纹**（每次启动恰好一行）：git revision / 分支 / dirty / 编译时间 / 源码数 / mc / neoforge / java —— 「这份日志是哪一版代码跑出来的」 |
| `[rscc]`          | 诊断锚点：`diag logging ON … session=` 会话起点、缺料档位、补发档位；`[rscc-diag]` 是 `/diag` 导出的 BEGIN/END 分节标记 |
| `[rscc-assembly]` | 序列装配事件流（机器类型 @ 坐标 \| 步骤 / 类别 \| 资源 id \| 数量 \| 结果 / 原因）        |
| `[rscc-trace]`    | 端到端追踪（item/amount \| from \| event \| to \| reason \| net 六字段） |
| `[rscc-ledger]`   | **守恒账本审计**：对平零输出；失衡首次一条 / 持续 WARN / 恢复一条 / 恒带 `peak` |
| `[rscc-dedupe]`   | **单元样板查重判定**：某步为什么被跳过 / 为什么不跳过，含命中来源、槽位与判据摘要（配方 / 步序 / 操作类型 / 代表物 / 候选组） |
| `push_stalled`    | **「推不动」的原因**（2026-10-05 新增）：`reason=DESTINATION_REFUSED`（下游机器满 / 不接受）或 `reason=STEP_OWNER_MISSING`（某步没有任何在线执行仓认领），带 `resource=` 与两个时间戳的年龄。**只在原因签名变化时打一条**，因此既能看见「第一次为什么推不动」，又不刷屏 |
| `[loader work]`   | 蓝图加农炮装填器工作日志                                                   |
| `chamber@`        | 执行仓日志（坐标后缀）                                                    |
| `binding`         | 样板 → 机器绑定日志                                                    |
| `watchdog`        | 看门狗扫描 / 挂起 / 恢复日志                                              |

### 9.3 `RsccAssemblyDebug` 的启用与使用

- **默认开启**（`Config#rsccAssemblyDebug` 默认 `true`），重开游戏也是开的，不需每次重下指令。

- 查看当前状态：游戏内 `/rs_create_compat assemblydebug`（不带 on/off）

- 切换：`/rs_create_compat assemblydebug on` / `off`（等价 `/rs_create_compat debug assembly on` / `off`，**不需要权限**）

- 节流三道闸：

  1. `transition`：同一台机器的同一类事件只在状态翻转时各打一条（稳态零输出）
  2. `reason`：同一个「原因」字符串在 1 秒内只打一条
  3. `reject`：同一个「拒绝原因」首次立即一条、之后每 5 秒合并一条 `repeated n times`

- 稳态最坏输出速率 = 「每 5 秒 1 条摘要」+「每个因由键每秒 1 条」+「每台机器每次状态翻转 1 条」，与机器数量线性相关、与 tick 无关。

- **注意**：`[rscc-build]`（启动一行）、`[rscc-ledger]`（对平零输出、失衡才说话）、`[rscc-dedupe]`（只在真决策点、5 秒合并）**都不受这个开关控制** —— 它们是取证设施，关掉 `assemblydebug` 也照常工作（但 `dedupe` 会读 `isEnabled()` 守门以免做无用的字符串拼接；`ledger` 与 `build` 完全独立）。

### 9.4 一键自检

```powershell
# ① 这份日志能不能作证？（指纹行 / revision 对得上 / 会话晚于构建）
python tools\verify_build_stamp.py

# ② 齿轮堵塞 / 多余中间产物 / 序列装配的实验判定（按订单段，6 条带明文证据的断言）
#    实机要跑什么：docs\BLOCKAGE_EXPERIMENT_PROTOCOL.md（6 段，约 25 分钟）
python tools\analyze_blockage_experiment.py

# ③ 齿轮堵塞 / 供料卡死自检（无日志 / 无诊断行 / 旧日志一律 FAIL）
python tools\verify_single_unit_supply.py

# ④ 综合诊断（报告第 0 节也会打印构建指纹与「是否等于当前 HEAD」）
python tools\diagnose_all.py
```

`verify_single_unit_supply.py` 会做三件事：①源码锚点硬断言；②打印 `create:cogwheel` / `create:golden_sheet` 的 `event/reason` 加权分布；③日志断言（含「无持续无效重复」）。括号里的开关：`--allow-empty`（确实没有日志，只跑源码锚点）、`--allow-stale`（只核对旧日志的历史基线）。

`analyze_blockage_experiment.py` 会把日志按 `binding task=` 切成**订单段**，逐段给出 6 条断言：
① 有没有在 5 秒内把同一件**重复推给同一工位**（= 多开一件在制件的直接机制）；
② 在制过渡件「产出 − 完成」有没有超过订单量的 4 倍（= 在制件堆积 / 走不完）；
③ 同一 (件,工位) 的 `waited=` 是**单档**还是**多档**（多档 = 粘住后每 10 秒故意再放行一次）；
③b 有没有 `waited > 200`；④ 任务结束回收的残留里有没有**过渡件**；⑤ 成品有没有入网。
它**按事件数计数、绝不按数量加权** —— 岩浆一次 500 mB 是**一份**，不是 500 份（旧脚本用数量加权会报假超推）。

### 9.5 grep 示例

```powershell
# ① 这份日志是哪一版代码跑出来的（先看这一行再谈任何结论）
Select-String -Path run\logs\latest.log -Pattern '\[rscc-build\]'

# ② 守恒账本有没有失衡（对平时一条都不会有）
Select-String -Path run\logs\latest.log -Pattern '\[rscc-ledger\]'

# ③ 哪一步被查重跳过了、跟谁重复
Select-String -Path run\logs\latest.log -Pattern '\[rscc-dedupe\].*SKIPPED'

# ④ 找「同一因由在 5 秒内重复 n 次」的稳态噪声（n ≤ 8 正常，≥ 20 是回归信号）
Select-String -Path run\logs\latest.log -Pattern 'same cause repeated' |
  Select-String -Pattern 'cogwheel|large_cogwheel|golden_sheet|sturdy_sheet'

# ⑤ 「推不动」到底为什么（2026-10-05 新增；机器满与缺机器是两件事）
Select-String -Path run\logs\latest.log -Pattern 'push_stalled'
```

### 9.6 诊断快照（`/rs_create_compat diag`）——「不要再靠复述」

> 用户明确要求：**「导出当前网络所有这些机子里面的内部数据，比如说某台机子我对它的设置」
> —— 以防「我每一次都要跟你讲一下，或者说我有时候记错了，然后我讲错你也就修错」。**
> 本节就是那份导出的字段说明。

**位置**：`run/rscc_diag/<yyyyMMdd-HHmmss>/snapshot.json`，同时覆盖写一份 `run/rscc_diag/latest/snapshot.json`。

| 分组 | 内容 | 什么时候看它 |
| ---- | ---- | ------------ |
| `meta` | schema 版本 / 生成时刻 / **会话起点** / 维度列表 | 确认这份快照是哪一次会话的 |
| `chambers` | 每个执行舱：位置 / 名字 / 配方类型 / 输出模式 / 在制 / 认领的步 / 面模式 / 单元样板 / **现存物品与流体** / 五量（目标·网络·本仓·机器·在途）/ 类别（含 `estimated`）/ 守恒账本 | 判断「料够不够、仓里有什么、这一步归谁」 |
| `buses` | 每个输入 / 输出总线：执行器模式 / 强制普通 / 自动收 / **已勾选类别** / 可见类别（含 `amount` 与 `estimated`）/ 链接的执行器 / 干扰（可达数） | 判断「总线勾了哪些类别、能不能送到」 |
| **`machines`**（新） | **每一个方块实体**：方块 id / 类型 / 实现类 / 自述（若有）/ **完整 NBT**（截断 4000 字符） | 「这台机器我对它的设置是什么」——NBT 就是存档里的真实设置 |
| **`containers`**（新） | **每一个容器**的非空槽逐格列出（槽号 / 物品 / 数量 / 伤害 / CustomData）+ 总容量 | 「东西到底压在哪个格子里」 |
| **`universalStorage`**（新） | 每个通用储存盘的容量与资源表 | 「网络里到底有多少料」的权威答案 |
| **`playerInventory`**（新） | 玩家名 / 维度 / 坐标 / **手持物** / 副手 / 背包里的本模组与 RS 物品 | 「玩家此刻拿着什么」（手滑拿错是最常见的人为因素） |
| `counters` / `recentEvents` | 按「坐标 + 资源」的近期计数与最近推 / 收明细 | 谁在搬、搬了多少 |
| `shortage` | 缺料档位 / **挂起记录**（含 `reason` / `offlineSteps` / `missingMaterials` / `stallTicks` / **`stallCause` + `stallChamber` + `stallResource`**）/ 横幅历史（含被去重吞掉的次数） | 任务为什么被挂起 —— **这三个新字段就是 2026-10-05 那次误判的定位钥匙** |
| `conservation` | 按 (坐标, 资源) 的「进入 = 离开 + 留存」与不平账条目 | 有没有凭空生成 / 消失 |
| `keepers` | 定量保持器：目标 / 过量销毁开关 / 销毁计数 / 认领与让位 | 「补货」到底补了什么 |
| `camouflage` / `recipes` | 伪装格数与 K 键切换 / 每条在用配方的四类分类 | 伪装与类别分页 |

**怎么用（三步）**：① 出问题时敲 `/rs_create_compat diag`；② 把 `run/rscc_diag/latest/snapshot.json`
连同 `run/logs/latest.log` 一起发回；③ 我不需要你再描述任何一台机器的设置。
快照**只读、幂等**（同一状态两次导出除时间戳外逐字节一致），可在产线运行中随时导出。

**新增一台机器时要做什么**：什么都不用做 —— `machines` 是**通用兜底**（任何方块实体都在里面）。
若那台机器的设置值得用**可读字段**而不是 NBT 表达，就实现 `support/RsccDiagnosable` 的
`rscc$diagReport()`（示例见 `SchematicLoaderBlockEntity` / `CollectionCacheBlockEntity`），
自检 `selfcheck_diag_facility.py` 的 ③b 段会钉住「导出覆盖面」不缩水。

***

## 10. 参考文件索引


### 10.1 项目根

| 路径                      | 说明                                              |
| ----------------------- | ----------------------------------------------- |
| `模组开发.md`               | 模组需求汇总（方块 / 物品 / 配方 / 配置归属原则）—— 14 节，所有方块的设计源头  |
| `GUI_TEXTURE_ISSUES.md` | GUI 贴图问题报告 + 修复前后对照 + 坐标对照清单                    |
| `build.gradle`          | Gradle 构建脚本，所有 maven 仓库与依赖声明                    |
| `gradle.properties`     | 版本号、依赖版本、JVM 参数（含 `Windows-ROOT` truststore 解释） |
| `settings.gradle`       | Gradle 设置                                       |
| `libs/`                 | 本地制品（Jade 等，避免 TLS 证书链问题）                       |

### 10.2 `docs/`

| 路径                                          | 说明                     |
| ------------------------------------------- | ---------------------- |
| `docs/cc_rework_contract.md`                | 归流缓存仓重做契约              |
| `docs/dependency-policy.md`                 | 依赖管理策略                 |
| `docs/DESIGN_DECISIONS_ROUND4.md`           | 第 4 轮设计决策（约 316KB，最完整） |
| `docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md`      | 齿轮堵塞 / 供料卡死自检判据        |
| `docs/GUI_FEEDBACK_ROUND2.md` / `ROUND3.md` | GUI 反馈记录               |
| `docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md`       | 序列装配日志从开游戏到实判定的完整步骤（含构建指纹与假绿修正）    |
| `docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md`      | **实机实验协议**：6 个段落各制造一种已知失败模式（齿轮堵塞 / 缺料等待 / 候选组），跑完由 `analyze_blockage_experiment.py` 取证 |
| `docs/ROUND_LOG_INSTRUMENTATION_20261004.md` | 第三轮「日志取证」完整报告（四族日志 / 真 bug / 假绿修正 / 实机步骤） |

### 10.3 `tools/`（100+ 个脚本，主要分类）

| 类别        | 脚本示例                                                                                                                                                                                                           |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 编译 / 诊断   | `manual_compile.ps1`、`diagnose_all.py`、`grep_assembly_log.py`、`analyze_loader_log.py`                                                                                                                          |
| **日志取证（第三轮新增）** | `gen_build_info.py`（生成构建指纹）、`verify_build_stamp.py`（日志能否自证版本）、`verify_single_unit_supply.py`（不变式 + 修复后行为）                                                          |
| 语言生成      | `gen_lang_frag_*.py`、`apply_lang_frag.py`、`patch_lang_*.py`、`audit_lang_keys.py`、`edit_lang.py`                                                                                                                |
| GUI 贴图生成  | `make_gui_bg.py`、`make_gui_gui.py`、`gen_chamber_faces.py`、`gen_keeper_faces.py`、`gen_executor_textures.py`、`gen_sequence_textures.py`、`copy_*_textures.py`                                                     |
| 模型 / 资源生成 | `gen_block_item_models.py`、`gen_face_split_models.py`、`gen_active_models.py`、`gen_loot_tables.py`、`gen_advancements.py`、`gen_compat_recipes.py`、`gen_brass_network_recipes.py`                                 |
| 校验 / 审计   | `audit_gui_textures.py`、`audit_shared_textures.py`、`scan_gui_sprites.py`、`scan_gui_layout.py`、`check_payload_registration.py`、`check_invalid_paths.py`、`audit_resource_swallow.py`、`selfcheck_advancements.py` |
| 修复 / 补丁   | `fix_terminal.py`、`fix_screen_layout.py`、`fix_scrollbar.py`、`fix_slot_offset.py`、`fix_loader3.py`、`fix_layout2.py`、`fix_one.py`、`fix_refill_lang.py`                                                           |
| 拉取前置      | `fetch_dev_mods.ps1`                                                                                                                                                                                           |

> ⚠️ **写 PowerShell 脚本时注意编码**：`tools/*.ps1` 必须保存为 **UTF-8 with BOM**。
> Windows PowerShell 5.1 在没有 BOM 时按系统 ANSI（本机 = GBK）解析脚本，中文注释会被解成乱码并
> **连带让后续语句失效**（本轮实测：`$root` 变成 null，脚本在第 17 行就崩）。
> 同时 `manual_compile.ps1` 生成的 `src/main/resources/build_info.properties` 是**生成物**，
> 由 `generateBuildInfo`（Gradle）与 `manual_compile.ps1` 刷新，不要手改。

### 10.4 关键源码文件锚点

| 文件                                         | 关键行                                                                                    | 说明            |
| ------------------------------------------ | -------------------------------------------------------------------------------------- | ------------- |
| `RS_Create_Compat.java`                    | L74 `MODID`、L78-79 `BLOCKS` / `ITEMS` `DeferredRegister`                               | 主类入口          |
| `SequenceExecutionChamberBlockEntity.java` | L86-99 类 Javadoc                                                                       | 执行仓职责定义       |
| `SequencePatternTerminalBlockEntity.java`  | L29-48 类 Javadoc、L52 `ARRANGEMENT_WINDOW=8`、L60-61 `COUNT_MIN=1` / `COUNT_MAX=100_000` | 终端容量与上限       |
| `SchematicLoaderBlockEntity.java`          | L44-49 类 Javadoc、L60 `BASE_SLOT_CAPACITY=64`                                           | 装填器基础         |
| `CollectionCacheBlockEntity.java`          | L61-72 类 Javadoc                                                                       | 归流缓存仓         |
| `AdvancedQuantityKeeperBlockEntity.java`   | L43-52 类 Javadoc、L60 `SLOT_COUNT=4`、L66 `FLUID_STORAGE_CAPACITY=128_000L`              | 高级保持器         |
| `AssemblyWatchdog.java`                    | L62-99 类 Javadoc                                                                       | 看门狗模型         |
| `SequencePatternData.java`                 | L25-48 新增 `TAG_INPUT_CANDIDATES`（输入原料组候选）                                    | 候选组落盘      |
| `UnitPatternDedupe.java`                   | L19-50 类 Javadoc、L92-147 `sameSemantics` 注释与方法（含候选组比较键）                                         | 查重判据          |
| `RsccSlotNbt.java`                         | L11-31 类 Javadoc                                                                       | 槽位 NBT bug 根因 |
| `RsccFlowLedger.java`                      | L13-50 类 Javadoc；`PREFIX` / `tickAudit` 在文件末（第三轮新增的审计日志）                          | 流量守恒不变量       |
| `RsccBuildInfo.java`                       | 类 Javadoc（「日志自证」的三步判定）；`PREFIX="[rscc-build]"`、`logOnce()`                       | 构建指纹          |
| `RsccMachineCluster.java`                  | L27-50 类 Javadoc                                                                       | 集群模型          |
| `RsccBusCategory.java`                     | L7-60 类 Javadoc                                                                        | 类别 id 规则      |
| `RsccAssemblyDebug.java`                   | L14-50 类 Javadoc                                                                       | 诊断日志节流规则      |
| `SequencePatternTerminalScreen.java`       | L48-67 类 Javadoc（硬规则 +1 偏移与手动 tooltip）                                                 | 终端界面硬规则       |
| `BusCategoryConfigScreen.java`             | L36-80 类 Javadoc（两级结构）                                                                 | 总线类别配置        |
| `StepDetailConfigScreen.java`              | L23-49 类 Javadoc（循环次数已移除）                                                              | 步骤详细配置        |
| `GhostMarkerRenderer.java`                 | L36-46 类 Javadoc、L52-53 `CYCLE_TICKS=20`                                               | ghost 标记渲染    |
| `RsccTooltipLayers.java`                   | L16-40 类 Javadoc、L52-71 `Layer` 枚举                                                     | tooltip 分层    |
| `manual_compile.ps1`                       | L1-30                                                                                  | 本地编译脚本说明      |
| `模组开发.md`                                  | L1-217                                                                                 | 模组设计需求        |

### 10.5 项目记忆位置

| 路径                                                                                                                                | 说明                                                                                          |
| --------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `c:\Users\70432\.trae-cn\memory\projects\-d-MODS-refined-storage-and-create-compat--p2-56664400728e360a5dcc\project_memory.md`    | 硬约束 + 工程约定 + 经验教训（永久）                                                                       |
| `c:\Users\70432\.trae-cn\memory\projects\-d-MODS-refined-storage-and-create-compat--p2-56664400728e360a5dcc\<YYYYMMDD>\topics.md` | 每日 topics（按日期目录：20260830 / 20260831 / 20260905 / 20260912 / 20260919 / 20261002 / 20261004） |

***

## 附录 A：硬约束摘要（来自 `project_memory.md`）

- 齿轮堵塞判定需添加等待豁免机制（W1-W5），仅在无正当等待理由且连续 200 tick 无推进时才判定为卡住并收回

- 补合成请求量需按缺口动态计算（目标 − 网络 − 本仓 − 机器侧 − 在途），确定性配方一次请求所需量，概率配方分批递进且 40 tick 冷却

- 缺料提示需区分资源是否可自动合成或存在在途任务，仅在不可合成且无任务时才显示

- 高级定量物品保持器升级槽需限制一槽一个，Shift+左键快速转移时仅填充第一个空槽

- 序列装配产物和废料需按配方独立分组，避免不同配方原料混在一起

- 设备掉线判定需仅检查当前任务实际使用的执行仓，避免因其他无关仓异常导致误报

## 附录 B：工程约定摘要

- 执行舱链配置修改需在服务端主线程串行处理，确保数据一致性

- 所有红石控制模式不满足条件时需停机且不销毁资源

- 单元样板管理器需按执行舱分组显示，支持多仓合并视图并接入无线终端

- 输出总线流向必须为先将原料搬进执行舱内部存储，再从执行舱输出至目标机器

- 回流缓存仓需主动抽取被选中面的相邻容器物品，并逐步回网，阻塞项留在缓存

- 蓝图加农炮装填器在无活动蓝图时需回收所有剩余建筑材料至网络

## 附录 C：经验教训摘要

- 齿轮堵塞根因为收回侧与备料侧口径不一致，空闲机械手误判为需要导致无法回收

- 缺料误报因漏改看门狗横幅判定，需统一网络、执行舱、机器侧存量口径

- 概率配方预填原料需按成功率估算期望尝试次数，避免因失败消耗导致缺料

- 单元样板查重需纳入服务配方和步序信息，避免不同配方因操作类型相同被误判为重复

