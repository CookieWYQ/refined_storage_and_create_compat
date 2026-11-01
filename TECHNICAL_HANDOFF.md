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
| 模组版本          | `1.2.0-beta.29`（唯一权威来源是 `gradle.properties` 的 `mod_version`） |
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
16. **网格搜索的裸文本也搜 tooltip（2026-10-24）**：RS 2.0.9 本来就采了 tooltip 全文（`GridResourceAttributeKeys.TOOLTIP`，来源 `ItemStack#getTooltipLines(..., TooltipFlag.ADVANCED)`），只是裸文本叶子 `GridQueryParser#parseLiteral` 只比 `getSearchableNames()`，玩家必须写 `$水下呼吸`（`#` 标签、`@` 模组同理）。做法：`mixin/client/GridQueryParserTooltipSearchMixin` 在 `parseLiteral` 的 `RETURN` 处把「名字命中」与 `client/grid/RsccGridTooltipSearch#matches`（读同一个 TOOLTIP 属性）做**逻辑或**，名字先判、命中即短路 ⇒ 已命中的物品不生成 tooltip。不变量：① 注入点必须是**叶子**而不是 `parse`（否则 `&&`/`#` 会被当成搜索词，`!` 也会被整串 OR 破坏）；② `$`/`#`/`@` 走 `parseUnaryOp → attributeMatch`，与本次注入**不共用一条路**（`tools/selfcheck_round94_grid_tooltip_search.py` 用 RS 的 bin + sources 双证钉住）；③ 必须按「资源类型 + 资源键」做缓存（LRU 4096 条 + 200 万字符总量预算；语言码或 `Minecraft#level` 对象变化整表作废；超长文本不进缓存；**读取失败不缓存**）—— RS 的 tooltip 取值器是 per-`GridResource` 实例（物品 memoize、流体即时），实例在同一 repository 内被 `CachingResourceRepositoryMapper` 复用、但 repository 每个菜单重建 ⇒ 不缓存就是「每开一次界面把全网络 tooltip 重算一遍」。没有配置项（用户明确要求）。**两条语义后果**：① ADVANCED 的进阶行（物品 id / 耐久 / 组件数）也参与裸文本搜索（英文玩家可用 id 搜）；② `!foo` 变成「名字与 tooltip 都不含 foo」（`!minecraft` 在中文客户端会藏起全部原版物品）—— 这是口径的必然结果，CHANGELOG 已如实写给玩家。**已知代价**：首次搜索在主线程为名字未命中的物品生成 tooltip（与 `$` 同源），要再快只能做后台预热索引。

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

- **默认关闭**（2026-10-06 起：`Config#devLogs` 默认 `false`）。用户实测这一族日志在没敲任何指令时
  一直输出（`[rscc-assembly]` 单会话 1.2 万行 / 峰值 128 行每秒，`[loader]` 单会话 3.9 万行），
  发布版必须安静 ⇒ 要抓诊断日志得先 `/rs_create_compat devlogs on`（等价旧写法 `assemblydebug` /
  `debug assembly`）。开关**关闭时 WARN / 错误、启动版本行 `[rscc-build]`、会话锚点 `[rscc]` 照常输出**。

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

## 11. 第 95 轮（2026-10-25）：用户实机反馈 15 条 —— 落地与验证

> 任务清单与逐条口径见 `docs/ROUND95_USER_FEEDBACK.md`（本轮唯一任务清单）。
> 基线：用户实机跑的是 `1.2.0-beta.1`（2026-10-24T11:18 构建，sources=420），所有抱怨都针对它；
> 本轮交付 `1.2.0-beta.3`。证据源：`diag/pull_2310/latest.log`（20:53–23:10 服务器会话）与同目录 `snapshot.json`。

### 11.1 15 条口径与落地位置

| # | 用户反馈 | 落地（文件 / 自检） |
|---|---|---|
| 1 | 裸文本搜 tooltip 要可配置 | `Config.gridSearchMatchesTooltip`（默认 true），读点唯一在 `client/grid/RsccGridTooltipSearch#matches`；`selfcheck_round94_grid_tooltip_search.py` |
| 2 | 接收器 / 请求器界面「没发生多大改变」 | 接收器 16 格只读缓存视图 + 流体条目 + 摘要（`NetworkResourceReceiverMenu/Screen`）；请求器 210×238 面板 + 订单 4×2 + 节拍 / 状态行（`NetworkResourceRequesterMenu/Screen`）；`selfcheck_round95_receiver_requester_ui.py`（44 断言 / 20 反例） |
| 3 | 缓存里的东西取不出来 | `CacheSlot#remove` 是唯一扣减点（`extractItem`，`mayPlace=false`）、`takeAllCacheItems` 先扣再塞、放不下回填；流体左键换 1 桶（先模拟后执行） |
| 4 | Jade 只需暴露接口，不要自己写 Shift 详情 | 接收器 Jade 载荷重写成**只写名字 / 未命名**，provider 不再判按键、不列内容；`selfcheck_round88_storage_inventory_exposure.py` ④b–④l（含新反例） |
| 5 | 「显示范围」不管哪个都不工作 | `removed()` 改为按剩余 TTL 重新武装 + 棋盘面 + 1/16 线宽 + 直径口径重建；`selfcheck_round95_range_charger.py`（20 断言 / 7 反例）、`selfcheck_round73_screen_mode_layout.py` ⑧-e（已加固） |
| 6 | 充电目标高亮（配置开关） | `Config.rangeChargerHighlightTargets`（默认 true）+ `network/ChargerHighlightPacket` + `client/ChargerTargetHighlight`；关闭时零发包、开界面只提示一句 |
| 7 | 所有界面 tooltip 必须手动绘制 | `tools/selfcheck_round95_screen_tooltips.py`（9 断言 / 5 反例，扫全部容器界面） |
| 8 | 容器代理器界面根本没画 tooltip | `InventoryProxyScreen#render` 覆写 + 无条件 `renderTooltip`；纳入 #7 的自检 |
| 9 | 收纳袋放不进代理器 | `support/InventoryProxyHandlers.BundleContentsView`（读 `minecraft:bundle_contents`，插入走原版 `tryInsert`、部分取出写回、空袋给 1 虚位）；`selfcheck_round95_inventory_proxy_ui.py`（22 断言 / 12 反例） |
| 10 | 代理器提示太长一坨 | 物品常显 help 不再传（语言键 `inventory_proxy.help` 已删）；界面警告压成一行短句（删 `font.split`） |
| 11 | 背包在代理槽里打不开 | 反编译实证 SB 结构不支持（客户端硬判 `slot.container instanceof Inventory`，服务端只解析 main/offhand/armor）⇒ 本轮做 tooltip 说明 + 替代路径，真修复需加 SB 可选依赖，**留待后续** |
| 12 | 归流缓存仓部分情况收不进网络 | `flushCacheToNetwork` 跨 tick 游标 + 失败不扣预算 + 单 tick 上限；流体独立预算 / 游标 / 快照遍历；诊断日志节流；`selfcheck_round95_collection_cache.py`（43 断言 / 8 组反例） |
| 13 | Shift+左键应改为「写标记」 | `CollectionCacheMenu#quickMoveStack`（升级物品例外，其余写 ghost 标记并分原因提示）；高级版继承 + 区域槽提前截住 |
| 14 | 连锁总线左键不能应用 | 左键事件构造器写死 MAIN_HAND（副手看不见）+ FTB 只有右键扩展点 ⇒ 改「主手→副手、有载荷优先」+ FTB public `cachedPositions()` 连锁 + 上游取消观测 + 四态诊断；`selfcheck_round95_clipboard_assembly.py`（82 断言 / 16 反例） |
| 15 | 序列装配日志瑕疵 | exporter `no_chamber` 是**日志说谎**（短路未重算，现分 `force_normal` / `filter_non_empty` / `chamber_gone`）；守恒账本 +1 是工位回流**漏记 `fromMachine`**（已成对记账 + 路径拆解 + 判定）；`RECLAIM_ONLY` 是有意设计、日志本就状态翻转才打，不改 |

### 11.2 本轮验证（Lead 串行执行，顺序不可颠倒）

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/_lead_compile.ps1   # 必须 LEAD_COMPILE_OK sources=N
python tools/_run_all_selfchecks.py      # 全量；必须「失败 0」
python tools/verify_mixin_shadows.py     # 两遍 CLIENT / DEDICATED_SERVER；问题总数必须 0
powershell -File tools/verify_dedicated_server.ps1   # PID 锁，独占；不要与别人并发
gradlew.bat build --offline --no-watch-fs -q         # 产出 build/libs/rs_create_compat-1.2.0-beta.3.jar
```

- `tools/_lead_compile.ps1` 的 argfile 已按 PID 隔离（`build/lead_javac_args.<pid>.txt`），多人并发不会再互相截断；
  它仍会把「用了哪个缓存 / 哪份 MC / 排除了哪些同族版本」全部打印出来（`LEAD_COMPILE_CACHE/MC/CLASSPATH/EXCLUDED`）。
- `tools/verify_mixin_shadows.py` 两遍各自计数：client 段 mixin 在专服视界里视为「目标不存在」，common 段引用
  `net.minecraft.client.**` / `com.mojang.blaze3d.**` / `org.lwjgl.**` 一律判负（`net.neoforged.neoforge.client.**` 只告警）。
- 本轮新增自检：`selfcheck_round95_screen_tooltips.py` / `_range_charger.py` / `_receiver_requester_ui.py` /
  `_inventory_proxy_ui.py` / `_collection_cache.py` / `_clipboard_assembly.py`；另按新口径更新过锚点的旧自检：
  round48 / round51-D7 / round66 / round73 / round74 / round88 / round89 / round93 / collection_delete_mode（每条都在文件里注明了新旧口径）。

### 11.3 已知未验证 / 已知限制（交付时必须如实转述给用户）

1. **全部改动都没有真机画面验证**：本轮执行者都跑不了 Minecraft，只有静态锚点 + 字节码证据 + 行为模型。
   具体待玩家确认：范围框 / 高亮是否看得见（尤其光影下）、接收器 16 格与流体条目观感、请求器按钮宽度与 238 高在 GUI scale 3 下是否顶屏、Jade 面板实际内容、左键连锁粘贴的 `[rscc-chain]` 两行日志。
2. **精妙背包快捷键**：结构上不支持（见 §11.1 #11），本轮只给 tooltip 说明；真修复要加 SB 可选依赖并实机验证。
3. **Left-click 粘贴的根因未二选一**：修了两条可证明的缺口（副手不可见、FTB 只有右键扩展点）并加了能分辨四态的日志；
   若玩家仍遇到「左键没反应」，先看 `[rscc-chain] 左键·…` 那一行是哪种情况。
4. **账本 +1 不会追溯清零**：计数器是内存态，重启即归零；修好后新发生的搬运应当立刻对平。
5. **`RECLAIM_ONLY / no_player_order_scrap_reclaim`** 是有意设计（自动模式无玩家订单时只回收成品 / 废料），
   且日志只在状态翻转时打（用户 2 小时日志里只有 11 条）；若要让 `took=-` 更准确，需另开一条工作流。
6. **专服 / 客户端行为差异**只在 `verify_dedicated_server.ps1` 与 `verify_mixin_shadows.py` 两处静态/探针级验证；
   真正的多人（两个客户端、高延迟）竞态未测（例如「一边取缓存一边请求器投递」）。
7. `ChargerTargetHighlight` 用客户端 `setGlowingTag`：只改视觉、不写存档，但**与别的模组也改发光时**的叠加行为未实测。

***

## 12. 第 96 轮（2026-10-25）：用户实机反馈 6 条 —— 落地与验证

> 任务清单与逐条口径见 `docs/ROUND96_USER_FEEDBACK.md`（本轮唯一任务清单）。
> 基线：用户实机跑的是 `1.2.0-beta.3`（2026-10-25T09:21 构建，sources=424），反馈时间 12:08；
> 本轮交付 `1.2.0-beta.4`（sources=426）。

### 12.1 6 条口径与落地位置

| # | 用户反馈 | 落地（文件 / 自检） |
|---|---|---|
| 1 | 归流缓存仓「还是无法与任何物品交互」 | **两个真因**：①窗口分页越界（256/512 格的最后一页读到 287/527，`AbstractContainerMenu#clicked` 的 `try/catch` 把 `IndexOutOfBounds` 吞掉 ⇒ 点击像死了一样）⇒ `CollectionCacheMenu#cacheGlobalIndex` 越界返回 -1、`WindowSlot` 六个入口全判、页数/格数走 `getContainerSize()`；②Shift 写标记把「手持件数」写进 `MarkerEntry.amount`，而吸收侧把它当**硬门槛** ⇒ 逐件掉落永远收不进 ⇒ 界面写标记的默认需求数量改为 1。新增 `[rscc-collect]` 九路径诊断（状态变化才记）。`selfcheck_round96_cache_area.py`（22 断言 / 7 组反例） |
| 2 | 区域选定器：重复区域 / 坐标被挡 / 界面难编辑 | `Area.sameGeometry`（名字不参与判等）+ ADD/REPLACE/COPY 三条写入路径拒收 + 三处描边用 `distinctAreas()`；`AreaSelectorScreen` 行高按 `font.width` 在 2/3 行切换消除截断、悬停给完整两角；点行回填六字段+名称、复制/重命名（500ms 防抖）/删除二次确认/维度/体积/尺寸/自绘滚动条/选中高亮/错误描红；`Area` 新增可选 `name`（空串编码，零迁移）。`selfcheck_round96_cache_area.py` B 组 |
| 3 | 代理器接两个外部存储会翻倍 | 不是复制物品，是 RS 不给 storage 去重 ⇒ 同一份库存被求和两次。`ProxyFaceRole` 新增 `CONTENTS_INPUT`（备面：`getSlots()==1` / 读与取恒空 / `insert`+`fill` 真写同一底层），`normalizeFaces()` 归一（内容物面与**代理对象面**各自 ordinal 最小者保留、退化态升回、读档归一；对象面不唯一时同一件代理对象也会被数两遍）。`selfcheck_round96_proxy_dedup.py`（66 断言） |
| 4 | 接收器不能与溜槽之类的交互 | 对外能力从只读改**可写**：`ViewOnlyHandlers.WritableItem/WritableFluid`（只进不出），写入只转交 `applyResource`（与请求器投递同一入口 ⇒ 容量/守恒一套账），`extract`/`drain` 恒空；`[rscc-receiver-input]` 三态状态化日志。`selfcheck_round96_receiver.py` + round88（11）按新口径更新 |
| 5 | 请求器界面要「网格 + 清单」 | 右键请求器**直接开网格**；`ResourcePickerScreen` 重写为顶部工具条（目标/立即发送/清空/节流/设置）+ 右侧可搜索订单清单（图标+名称+数量+×、滚轮+滚动条、**无上限**、数量框回车/±步进、过滤态发权威下标）；小面板 4×2 订单格删除→摘要+大按钮；`RequesterOrderPacket.ACTION_OPEN_SETTINGS`（不新增 payload）、`SyncRequesterConfigPacket` 增节拍字段。`selfcheck_round96_requester_ui.py`（47 项）+ round86（147/0）+ round95（49/0）；另修 `openFor` 无条件返回 true（`openMenu` 失败也当成功 ⇒ 两处「界面没能打开」提示不可达）与 `openSettingsFor` 静默失败 |
| 6 | 接收器：流体取不出 / 格子太少装不完整 | 16 固定镜像槽 → **分页资源列表**（**12 条/页 = 2 列 × 6 行**、行高 12px、图标缩放到 12px，滚轮/滚动条/`< >` 翻页，摘要显示种类与总量；`SyncReceiverConfigPacket` 带 page/pageCount/totalTypes/entries）；取出走新 C2S `ReceiverCacheActionPacket`（已注册进 `RS_Create_Compat.registerPayloads`）：物品左键一组/Shift 全部（放不下回填、再掉脚下），**流体 1000 mB/桶且必须有空桶（没有就不扣+提示）**。`selfcheck_round96_receiver.py`（19 断言 + 23 反例） |

### 12.2 本轮验证（Lead 串行执行）

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/_lead_compile.ps1   # LEAD_COMPILE_OK sources=426
python tools/_run_all_selfchecks.py      # 130 个脚本，失败 0
python tools/verify_mixin_shadows.py     # 两遍 CLIENT / DEDICATED_SERVER；问题总数 0
python tools/audit_lang_keys.py          # 问题 0 / 孤儿键 0（本轮 zh+en 各 +32 条）
python tools/check_payload_registration.py  # 96 个自定义包全部已注册
powershell -File tools/verify_dedicated_server.ps1   # PID 锁，独占；本轮拿 beta.4 jar 跑
gradlew.bat build --offline --no-watch-fs -q         # 产出 build/libs/rs_create_compat-1.2.0-beta.4.jar
```

- 本轮新增自检：`selfcheck_round96_cache_area.py` / `_proxy_dedup.py` / `_receiver.py` / `_requester_ui.py`。
  按新口径更新过锚点的旧自检（每条都在文件里注明了新旧口径，未削弱抓 bug 的能力）：
  round69（登记 `AreaSelectorScreen` / `ResourcePickerScreen` 两个 tooltip 站点）、round86（5 条：①-7 收紧、
  ⑦-2 改代码不改断言、⑦-5 补回 `openFor` 直调、⑨-6 灰按钮→提示、⑦-13 连带）、round88（接收器可写）、
  round89 / round91 / round93（代理器四档枚举 + 区域新增 name 分量）、round90（区域界面双上限入口）、
  round92（接收器可写）、round95（接收器 16 格→分页、请求器 4×2→摘要+大按钮）。

### 12.3 已知未验证 / 已知限制（交付时必须如实转述给用户）

1. **全部改动都没有真机画面验证**（执行环境跑不了 Minecraft）。待玩家确认：接收器分页列表与滚动条观感
   （**12px 行高 + 0.75 缩放图标在 GUI scale 2/3/4 下是否够清楚，是本轮唯一的审美/可读性风险点**）、
   请求器网格工具条在 GUI scale ≥4 下的位置、订单行 × 的热区（118px 面板下约 11px）、区域选定器两/三行坐标的可读性。
2. **溜槽交互**只有源码证据链（Create 6.0.10 用**非空**侧别查询、先 SIMULATE 后 EXECUTE），没有在存档里摆过溜槽；
   模组流体（无桶形态）与光标上整叠空桶的行为未验。
3. **代理器备面「读空 ⇒ 不参与 RS 求和」** 是按 RS 读法反推的，需要玩家贴两个外部存储看一次终端数字；
   备面写入后主面 RS 缓存最长约 40 tick（2 s，RS 自身 workRate）才同步，未计时。
4. **归流缓存仓「点击恢复」** 的依据是原版 `clicked` 的 `try/catch` 契约 + 行为模型；`气体` 吸取开关**本来就没有世界来源**
   （`collectGas` 空实现），不是本轮回归。
5. **高延迟 / 双客户端**竞态（一边翻页取物一边请求器投递）未测；服务端每 10 tick 补发 + 动作后立即补发，理论上不错位。
6. `[rscc-collect]` / `[rscc-receiver-input]` 两条新诊断的实际日志行没有在真机看到；玩家下次报「收不进去」时先看 `[rscc-collect]`
   里「哪些路径没动过」。
7. **订单「无上限」的代价**：`SyncRequesterConfigPacket` 每次增 / 删 / 改都**全量重发**整张订单（开屏 payload 也带整张）。
   几百条没问题，上千条会明显占带宽 / 卡顿；没有分页与上限保护。若线上出现卡顿，第一件事是把这条改成增量同步。
8. **代理对象面唯一化的迁移**：旧存档若有两个对象面，较大 ordinal 那个读档即变「禁用」（面配置被归一，代理槽里的物品不受影响）；
   想换对象面位置要先切走现役对象面。未实机验证。

***

## 13. 第 97 轮（2026-10-25）：用户实机反馈 3 条（含 1 条 P0 回归）—— 落地与验证

> 契约与逐条口径见 `docs/ROUND97_USER_FEEDBACK.md`。基线：用户实机仍是 1.1.0（他没跑过新构建）。
> 本轮目标只有三件事：①恢复被删的输入总线原生侧边按钮（P0）；②容器代理器界面删掉那条「蚂蚁字」说明、
> 背包上移、贴图重制；③不越界（用户对上一轮「顺手改」非常反感）。

### 13.1 根因与落地

| # | 用户反馈 | 根因（已复核） | 落地（文件 / 自检） |
|---|---|---|---|
| 1 | 「输入总线/输出总线……黑名单那些按钮全删了，只剩一个红石开关」（P0） | `mixin/client/AbstractBaseScreenMixin` 里的 `rscc$hideImporterFilterButtons` 在 `AbstractBaseScreen#addSideButton` 的 HEAD 处**无条件** `ci.cancel()` 掉 `FilterModeSideButtonWidget` + `FuzzyModeSideButtonWidget`（1.0.0 起就存在；RS 2.0.9 的 `ImporterScreen#init` 只挂这两颗，红石由基类挂）。输出总线（RS 原生：模糊 + 调度 + 红石）**从未**被本模组删除，本轮未动 | 整段注入与 4 个专用 import 删除；`AbstractBaseScreenMixin` / `ImporterScreenMixin` 的 javadoc 改成铁律「只追加、绝不删除 RS 原生控件」；新自检 `tools/selfcheck_round97_bus_side_buttons.py`（17 断言 + 6 反例；实测「把注入加回去 ⇒ 判负」） |
| 2 | 容器代理器：红字太小且「不喜欢那行字」 | `renderWarning` 把 56 字中文单行等比缩到 ~0.4x | 删 `renderWarning` / `WARNING_*` / 语言键；信息改「悬停六面按钮」的 `face_tip.*`（`renderTooltip` 画在 `super.render` 之后）；`selfcheck_round97_proxy_layout.py` + round89/93/95/96 按新口径改写（未删结构断言） |
| 3 | 「上面这么大空位、物品栏有点矮」 | 面板 224×248、按钮带止于 y=126、背包行 y=172/190/208、快捷栏 y=230（格子底边 = 面板底边） | 面板 224×**222**；背包行 y=136/154/172、快捷栏 196（底部留白 8px）；状态文字 (10,46) 折行 ≤4 行、右界 ≤108；贴图由 `tools/gen_inventory_proxy_gui.py` 重制（5 色、18×18 槽位格、四边闭合） |

### 13.2 验证（Lead 串行）

```text
tools/_lead_compile.ps1                      # LEAD_COMPILE_OK sources=426
python tools/_run_all_selfchecks.py          # 132 个脚本，失败 0
python tools/verify_mixin_shadows.py         # CLIENT + DEDICATED_SERVER 两遍，问题 0
python tools/audit_lang_keys.py              # 问题 0 / 孤儿 0
```

### 13.3 未验证（必须如实转述）

1. 三颗原生按钮「肉眼回到界面上」只有源码链证据（注入已删 + RS 原生无条件挂），无实机。
2. 代理器新布局（224×222）与 12px 行高工具提示在 GUI 缩放 2/3/4 下的观感未实机；`en` 的 `status.no_contents` 按 98px 窄列重写过（361px ≈ 4 行），中文未改。
3. 输出总线没有任何改动；若玩家看到输出总线仍「只有红石」，那是另一次现象，需要截图/日志重新取证。

***

## 14. 第 98 轮（2026-10-25）：范围充电器「高亮按钮 + 充电过滤」—— 落地与验证

> 契约见 `docs/ROUND98_CHARGER_FILTER.md`。用户定案：过滤只有 **3 类**（掉落物 / 玩家身上物品 / 玩家，**没有方块类**）；
> 黑/白名单**全局一个开关**；玩家名单 = 本存档曾经进服过的所有玩家（落盘）+ 手动名称/UUID；
> 高亮 = **每台充电器一个开关**（落盘，旧存档默认开），配置项 `rangeChargerHighlightTargets` 降级为总闸。

### 14.1 落地

| 层 | 内容 |
|---|---|
| 数据 | `support/ChargingFilter`（黑/白名单 + 三类名单 + NBT v1，**唯一判定出口**：`allowsDroppedItem` / `allowsCarriedItem` / `allowsPlayer`=小写名 OR UUID）；`support/ChargerKnownPlayers`（主世界 SavedData `rscc_charger_known_players`，登录事件记录 + 开界面补记） |
| 方块实体 | `chargingFilter` + `highlightEnabled` 落盘；ContainerData 12→13（索引 12）；**三处门控**：`chargePlayer` 整名跳过 + 背包/饰品槽每 stack、`scanItems`、`scanItemsInfinite`；`syncHighlightTargets` 同时受总闸与本机开关；方块两档**不**过滤 |
| 网络 | C2S `OpenChargerFilterPacket`（要快照）/ `SetChargerFilterPacket`（全量覆盖，均做 menu 归属 + `stillValid`）、S2C `SyncChargerFilterPacket`（含 `KnownPlayer(name,id,online)`）；S2C 只经 `ClientPayloadHooks` → `ChargerFilterScreen.openOrRefresh`（dist-safe） |
| 界面 | `RangeChargerScreen` 新增 (150,37)/(150,54) 两颗 36×14 按钮（高亮 / 过滤）；新子界面 `ChargerFilterScreen`（252×262：模式按钮 + 3 页签 + 搜索 + 8 行/页列表 + 玩家添加行 + 完成/返回）；`ChargerFilterCandidates` 懒构建「可充电物品类型」池（`getCapability(EnergyStorage.ITEM)` → `canReceive() && max>0`，缓存 + 搜索上限 512） |
| 自检 | `tools/selfcheck_round98_charger_filter.py`（46 断言 / 14 反例）；5 处旧锚点按新契约更新（round52/71 的 12→13、round95_range 收窄为「禁止落盘高亮集合」、round69 登记新 tooltip 站点） |

### 14.2 验证（Lead 串行）

```text
tools/_lead_compile.ps1                      # LEAD_COMPILE_OK sources=433
python tools/_run_all_selfchecks.py          # 133 个脚本，失败 0
python tools/verify_mixin_shadows.py         # 两遍，问题 0
python tools/audit_lang_keys.py              # 问题 0（本轮 zh+en 各 +40 条）
python tools/check_payload_registration.py   # 全部自定义包已注册（+3）
tools/verify_dedicated_server.ps1 -ModJar build\libs\rs_create_compat-1.2.0-beta.5.jar   # 真启动 PASS
gradlew.bat build --offline --no-watch-fs -q  # 产出 1.2.0-beta.5 jar
```

### 14.3 未验证 / 已知限制

1. **无实机**：过滤子界面的观感、8 行/页列表与搜索手感、候选池首次构建耗时（一次 O(物品数) 能力查询，
   已在界面暴露 `lastBuildCount()`）、玩家「头像占位」是否够认、`en` 的 "Filter" 在 36px 按钮里是否走马灯 —— 全部待实机。
2. **玩家过滤是「整名跳过」**（连饰品槽），这是契约语义不是 bug；黑名单勾了某玩家 ⇒ 他整台装备都不充。
3. **方块不参与过滤**是用户定案；若将来要加第 4 类，先改契约与 `ChargingFilter` 的 NBT 版本。
4. `ChargingFilter#load` 现在**真的读** NBT 版本（只接受 v1，其余 WARN + 回落默认）—— 此前只写不读。
5. 高亮关闭后旧高亮多久消失：由「不再发心跳 ⇒ 客户端 20 tick 过期」推断，未实机计时。

### 14.4 独立复核后的收尾（Lead，同日）

独立只读复核（task-21）A–G 全 PASS，并提出三点，已处理：

1. **死分支删除**：`RangeChargerMenu.BUTTON_OPEN_FILTER = 8` 与它的 `clickMenuButton` 分支全仓**没有发送点**
   （「过滤」按钮实际走独立 C2S 包），已删除；`selfcheck_round98` ④-b/④-c 改为「反向锚定：该常量不许回来 +
   菜单分支里不得再出现 `sendFilterSnapshot`」。
2. **归因校正**：`ChargingFilter#load` 版本闸的 javadoc 原写「独立复核提出」，实际是第 98 轮自检方指出
   「注释与代码不符」，已改；行为仍是只接受 `v == 1`，其余 WARN + 回落默认。
3. **解码硬化**：`Sync/SetChargerFilterPacket` 解码不再按对端给的 `count` 预分配 `ArrayList`
   （`count = 2^31-1` 的恶意 C2S 包会直接 OOM）；列表逐条追加，上限由「包里真有那么多字节」+ 网络帧兜住。

复核报的家族性风险（C2S 列表长度无硬上限、32 位无横线 UUID 被当玩家名静默接收、`en_us` 旧成就键遗留中文）
**本轮（第 98 轮）未修改**，已列入已知限制 —— 前两条已在**第 99 轮关闭**（见 §15），`en_us` 旧成就键的中文问题仍在。

### 14.5 交付与部署记录（2026-10-25 16:0x）

- 产物：`build/libs/rs_create_compat-1.2.0-beta.5.jar`，2,451,555 B，
  `sha256=3f4b23ac73ea89a55dfa415f069b860d0234cf22d10e860380ab085d00ba431a`，`sources=433`，`builtAt=15:55:01`。
- 专用服务端探针（拿该 jar 真跑）：**PASS**（`Done (1.689s)`，无 Mixin/dist 致命标记）。
- 部署（用户新规矩：默认「禁用旧版 = 改名，再拷新版」）：
  - 服务端 SFTP `/mods`：`beta.4` → `.disabled`，`beta.5` 启用；uploading → rename 后**回读 sha256** 一致。
  - 客户端（本地实例）`D:\minecraft\.minecraft\versions\Create：Enjoy\mods`：`beta.4` → `.disabled`，`beta.5` 启用，sha256 一致。
  - 另：SFTP `/client_files/mods` 里同时存在 `beta.1/beta.3/beta.4` 三个启用中的旧版（历史遗留），
    已一并禁用，只留 `beta.5`；若该目录不是活动客户端目录，不影响游戏（真实客户端目录见上一条）。
- 服务器重启由用户执行（我无法重启）。

## 15. 第 99 轮：充电过滤「硬上限」+ 32 位裸 UUID（2026-10-25）

> 来源：用户看完第 98 轮交付说明「五、必须如实说的未验证 / 需确认」后的回复 ——
> 第 2 条（黑 / 白名单模式是**每台充电器各一份**、三类共用一个模式）**保持不变**（用户：「第二个就这样子」）；
> 第 3 条（两条已知限制）**要改**（用户：「第三个改了他」）。

### 15.1 落地

| 层 | 内容 |
|---|---|
| 新增 | `support/ChargerFilterLimits`（**纯 JDK，不 import 任何 MC / NeoForge 类型**）：5 个上限常量 + `addBounded` / `toggleBounded` / `clamp` / `normalizeName` / `parsePlayerId` / `withDashes` / `isHex`。纯 JDK 是刻意的：自检可以 `javac` 单文件 + `java` 直跑，于是「解析 / 截断」是**真执行过**的 |
| 网络 | `SyncChargerFilterPacket` 新增 `encodeNames / decodeNames / encodeUuids / decodeUuids / encodeKnownPlayers / decodeKnownPlayers`，`ResourceCodecList` 加封顶；`SetChargerFilterPacket` 改为**复用**这些辅助（两包不再各写一份循环）。**编码** = 先截断到上限再写条数；**解码** = 顺着声明条数把字节全读完、只保留前 N 条（既不 `new ArrayList<>(count)`，也不提前 `break` —— 后者会让后续字段错位） |
| 存档 / 清洗 | `ChargingFilter.addIds / addName / addId / readIds` 全部走 `addBounded`（**最下游的闸**：绕过网络层的手改 NBT 也拦得住）；名字归一化改调 `ChargerFilterLimits.normalizeName`；超限 WARN **每进程只打一次** |
| 界面 | `parseUuid` → `ChargerFilterLimits.parsePlayerId`（36 带横线 + 32 裸十六进制）；`onAdd` 用同一个 `normalizeName`；物品勾选走 `toggleBounded`；满员时 `addStatus` 提示「名单已满（最多 N 条）」；`addStatus` 由「语言键字符串」升级为 `Component`（支持 `%s` 参数）；新增语言键 `gui.rs_create_compat.charger_filter.list.full`（zh / en 各 1 条） |
| 自检 | 新增 `tools/selfcheck_round99_charger_filter_hardening.py`（17 断言 / 14 反例 + **两组真代码 JVM 探针**）；`tools/selfcheck_round98_charger_filter.py` 两处锚点随实现搬家同步（见 15.3） |

上限值：物品 **1024** / 玩家名 **256** / UUID **256** / 单条名字 **32** / 已知玩家候选 **1024**。
依据：界面一次最多列 `ChargerFilterCandidates.MAX_SEARCH_RESULTS = 512` 条候选 ⇒ 1024 = 连续两次满页全勾；
最坏一包几十 KB，远低于网络帧上限；玩家名 32 = 原版 16 的两倍余量。

### 15.2 验证（Lead 串行）

```text
tools/_lead_compile.ps1                      # LEAD_COMPILE_OK sources=434
python tools/_run_all_selfchecks.py          # 134 个脚本，失败 0
python tools/verify_mixin_shadows.py         # 两遍（CLIENT + DEDICATED_SERVER），问题 0
python tools/audit_lang_keys.py              # 问题 0，孤儿键 0（zh+en 各 +1 条）
python tools/check_payload_registration.py   # 99 个自定义包全部已注册
tools/verify_dedicated_server.ps1 -ModJar build\libs\rs_create_compat-1.2.0-beta.6.jar   # PASS
gradlew.bat build --offline --no-watch-fs -q # 产出 beta.6（sources=434，builtAt=16:21:07）
```

> 第一次跑全量自检时有 **1 个失败**：round98 ⑤-c（UUID 编解码搬进共用辅助后，旧锚点找不到
> `UUIDUtil.STREAM_CODEC`）。同步锚点后重跑 134/0。这不是「放宽」—— 见 15.3。

### 15.3 自检锚点同步（都是「随实现搬家」，且仍能抓住它点名的 bug）

1. round98 `packet_ok(..., "playToServer")`：原先要求 `SetChargerFilterPacket` 源码里出现
   `UUIDUtil.STREAM_CODEC`；现在 UUID 编解码在 `SyncChargerFilterPacket.encodeUuids / decodeUuids` 里，
   锚点改为「Set 包**必须调用**这两个共用辅助」，新增 ⑤-c2 判「辅助内部确实用 UUIDUtil 编 / 解码」，
   并新增反例：把 `encodeUuids` 里的 UUIDUtil 换成 `STRING_UTF8`（线格式错位）⇒ **实测判负**。
2. round98 `filter_source_ok`：原先要求 `ChargingFilter` 里内联 `rawName.trim().toLowerCase(Locale.ROOT)`；
   现在委托 `ChargerFilterLimits.normalizeName`，锚点改为「内联 或（委托 + `normalizeName` 里真的有
   `trim().toLowerCase`）」，并新增反例：把 `normalizeName` 的小写化删掉 ⇒ **实测判负**。
3. round99 的两组 JVM 探针把两件事变成可执行证据：
   `ChargerFilterLimits` 36 个用例（36/32 位 UUID、大写、非法串、名字归一化、`addBounded` / `toggleBounded` / `clamp`）；
   `ChargingFilter.of / load` 带 MC classpath 实跑（1500 条物品 ×2、400 条名字、400 条 UUID、2000 条 NBT）
   并断言集合**恰好**被截到上限。

### 15.4 交付与部署记录（2026-10-25 16:2x）

- 产物：`build/libs/rs_create_compat-1.2.0-beta.6.jar`，2,454,565 B，
  `sha256=d053cffee7103bdace4499c1d0e229860009470e78521ed27f3b166e667dd8dd`，`sources=434`，`builtAt=16:21:07`。
- 专用服务端探针（拿该 jar 真跑）：**PASS**（`Done (2.068s)`，无 Mixin / dist 致命标记；`run/` 只读自证 OK）。
- 部署（禁用旧版 = 改名，再拷新版；上传后回读 sha256 一致 + rename 后复验）：
  - SFTP `/mods`：`beta.5` → `.disabled`，`beta.6` 启用；
  - 本地客户端实例 `D:\minecraft\.minecraft\versions\Create：Enjoy\mods`：`beta.5` → `.disabled`，`beta.6` 启用；
  - SFTP `/client_files/mods`：`beta.5` → `.disabled`，`beta.6` 启用（`1.1.0` / `beta.1` / `beta.3` / `beta.4` 早已是 `.disabled`）。
- 服务器重启由用户执行（我无法重启）。
- 顺手：`tools/deploy_rscc.py` / `deploy_rscc_local.py` 的 jar 路径从写死改成「取 `build/libs` 里最新的
  `rs_create_compat-*.jar`」，避免每轮手改文件名。

### 15.5 本轮之后仍然存在的限制 / 未验证

1. **无实机**（执行环境跑不了 MC）：「名单已满」提示的观感、32 位裸 UUID 的添加手感、勾选切换都要玩家确认。
2. 超限一律「**保留前 N 条、丢尾部**」；WARN 每进程只打一次，所以日志里看不出「丢了哪几条」（有意为之：不刷屏）。
3. 「已知玩家」候选 > 1024 条时界面只列前 1024 条（手动输入名字 / UUID 不受影响，不会「配置不到」）。
4. 上限是编译期常量，**没有**做成配置项；将来要放开请先改 round99 自检的数量级判据
   （物品上限必须 ≥ 候选搜索上限，且 ≤ 65536）。

***

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

## 16. 2026-10-30~31 交付批次（beta.7）：三台新机器 + 终端第 7 档 + 多条实机修复

### 16.1 本轮新增的「机器」与数据源

| 东西 | 注册名 / 类 | 关键数据 | 谁在管 |
| --- | --- | --- | --- |
| FE 能量立方 | `fe_energy_cube` / `block/entity/FEEnergyCubeBlockEntity` | 每块自身 FE（落盘）、集群 = 六向连通分量（`support/EnergyCubeCluster`）、网络登记（`support/EnergyCubeNetworkRegistry`，供门控「减掉所有立方」） | `client/screen/FEEnergyCubeScreen` 176×166 / 电量条凹槽 (14,18) 14×60 |
| 电动飞行背包 | `electric_jetpack` / `item/ElectricJetpackItem` | 电量在物品组件（`CustomData`）、飞行状态机 `support/JetpackFlightHandler`、HUD `client/JetpackHudOverlay`、S2C `network/JetpackStatusPacket`（自注册 `TerminalPowerPayloads` 同款模式） | 生效槽位：胸甲 → Curios back → Curios chest |
| 降解室发电机 | `decomposition_generator` / `block/entity/DecompositionGeneratorBlockEntity` | **第 123 轮重做**：20 格 `ItemStackHandler`（16 输入 + 4 副产物，输入 `isItemValid` 查可降解表、副产物只出不进）、6 格升级（`RsccSpeedStackUpgradeContainer`，只收速度/堆叠）、进度 + 当前槽落盘、内部 FE（只出）、旧 256 格存档自动迁移（塞不下的入 `pendingDump` 首个 tick 掉出）、`support/DecompositionTable` 数据包表 + 副产物 `results` | 数据包 `data/<ns>/decomposition/*.json`；`support/DecompositionDataLoader` 自注册 `AddReloadListenerEvent`（game bus）；全部几何/槽位常量在 `support/DecompositionGeneratorSpec`；设计契约 `docs/ROUND123_DECOMPOSITION_GENERATOR_REDESIGN.md` |
| 终端能耗监视 | 终端第 7 档 `MODE_ENERGY` | 枚举走 RS 官方 `GraphNetworkComponent#getContainers()` + `InWorldNetworkNodeContainer#getPosition(): GlobalPos`（**不要**再自建容器表 / 不用 mixin）；`support/NetworkEnergyAudit` 服务端快照；`client/screen/EnergyAuditScreen` + `client/EnergyAuditHighlight` | payload 自注册 `network/EnergyAuditPayloads` |

### 16.2 本轮修掉的「机制级」缺陷（都有 file:line 证据，别再改回去）

1. **归流缓存仓吸不到地板上的掉落物**：`CollectionCacheBlockEntity` 旧代码用实体整数格判区域，且实体查询盒上界与实体 AABB 下界**相切**；修法 = 查询盒**向上**扩 1 格 + `containsEntity`（占用格 / 所站格 / 上方格）。**向下扩是错的**（不改上界，依旧不相交）。
2. **旧存档标记的「凑够 N 件」门槛**：旧 Shift 标记把手持堆叠数当门槛；现在按存档版本键 `MarkerAmountV2` 只对旧档归一为 1。
3. **每区域独立过滤器**（NBT `AreaFilters`，下标 = sanitize 后区域下标；空集 = 不吸）：解决「相邻机器重复吸放」。C2S `SetAreaFilterContextPacket` 在主类 `registerPayloads` 里注册（**漏注册不崩**：界面侧 try/catch 降级 WARN）。
4. **接收器 × Create 溜槽**：Create 溜槽对上方容器**只抽不推**（`ChuteBlockEntity` 空槽 → `ItemHelper.extract`），第 96 轮把 `extractItem` 写死恒空才是真因。修法 = `ViewOnlyHandlers` 新增显式外抽落点（默认仍只进不出），接收器接上；`simulate` 零写入、`execute` 才写 + `setChanged()`。
5. **范围充电器缓存 5000 万**：只改默认值对已装实例无效（NeoForge 语义）⇒ `Config.migrateLegacyRangeChargerCapacity()` 在 `commonSetup` 做一次性迁移（只迁「恰好等于历史默认 100 万 / 1000 万」的实例）；同时新增 `rangeChargerNetworkPullPerTick`（默认 10 万）闸住网络抽电。
6. **`AbstractContainerScreen#render` 不会替子类调 `renderTooltip`**（javap 逐字节确认）：新界面若要 tooltip，必须覆写 `render()` 手动调（`FEEnergyCubeScreen` / `InventoryProxyScreen` 都是这么做的）。**7 参 `blit` 把纹理尺寸写死 256×256**，整图 = 界面尺寸的贴图必须用 9 参。
7. **语言键实参契约**：`lang` 的 `%s` 个数必须 == Java 实参个数。两个方向都有硬断言：`tools/selfcheck_round99_energy_cube.py`（Java→lang，扫源码）与 `tools/gen_fe_energy_cube_assets.py#check_lang_contract`（lang→契约表）。**review 资源名拼接不算违规**（守卫已排除 .png/.json 等后缀）。
8. **自动合成仓 → Create 机器**：接管点提到 `PatternProviderNetworkNode#accept`（`mixin/createcraft/AutocrafterCreateCraftMixin`，priority 1500、字段 `@Shadow` 拿 sink），**正对 Create 机器时绕过内部存储**、其它情况完全放行；Create 特化路径上非 NEVER 的锁模式等价 NEVER（已写进类注释与交付说明）。

### 16.3 本轮验证（Lead 串行执行）

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=487`（第 104 轮新增 `RsccTextFit` / `RsccStationBadge` / `RsccBusStationSync` / `RsccStationProbe` 等）。
2. 全量自检 `tools/_run_all_selfchecks.py` → **147 个，0 失败**（必须用带 Pillow 的 python：`C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe`；系统 `C:\Python315\python.exe` 缺 PIL 会在 `selfcheck_advancements.py` 假失败）。新增：`selfcheck_no_ellipsis.py`（109 checks，扫 100 个客户端源文件）与 `selfcheck_round104_station_distribution.py`（17 断言 + 10 反例）。
3. `tools/verify_dedicated_server.ps1` → **PASS**（`Done (1.330s)`，无 Mixin / dist 致命标记；run/ 前后清单一致）。
4. `gradlew build --offline --no-watch-fs` → `build/libs/rs_create_compat-1.2.0-beta.9.jar`（2,720,065 B，sha256 `1fa7d70a…2d5afd`）。
5. 部署：SFTP `/mods` + `/client_files/mods` + 本地客户端实例；旧版 beta.8 一律改名 `.disabled`、新版 sha256 三方一致（部署后读回校验通过）。SFTP 部署必须用带 paramiko 的 `D:\mods\pyenv-staritem\scripts\python.exe`（bundled python 没有 paramiko）。

### 16.4 本轮「未验证 / 已知限制」的权威清单

见 `CHANGELOG.md` 的「已知限制」两节（中英各一份），交付说明必须原样转述，不许只挑好听的。要点：Create 特化路径的锁模式、能耗页只显示绑定网络、飞行背包身上无模型与 4 区块扫描、降解室发电机农夫乐事未实测 + 满仓存档体积与 `/reload` 未实测、区域过滤器下标不跟随重排。（1.2.0-beta.9 起：降解室发电机**正式贴图已交付**、不再有「占位美术」；能量立方第 4 行「absolute 模式文案截断」也已随「界面文本完整显示」改造消失 —— 见 `docs/TEXT_NO_ELLIPSIS_RULES.md`。）

### 16.5 下一轮（若要继续）优先事项

1. 序列装配「物品分配架构」B 方案：B-1..B-4 在第 104 轮落地（`task-36`）；B-5..B-8（流体 / 过渡件 / 落盘工位登记表 / machinePos 排序）仍是下一轮。
2. 归流缓存仓：区域重排后过滤器跟随（把下标键改成「区域几何哈希 + 序号」双键）。
3. 界面「缩字号」兜底路径的实机观感（0.6 倍下限、英文长行折行位置）需要客户端实机确认。
4. 降解室发电机（第 123 轮重做后）：旧 256 格存档迁移**实机**走一遍（目前只有模型推演 + 守恒断言）、`/reload` 实机、农夫乐事标签实测、界面贴图 / 箭头 / 文字避让实机看一眼、插拔升级看进度与 FE/t。
5. 第 124 轮（1.2.0-beta.25）三个界面**都没真机开过**：区域标记界面（文字避让 / 缩高后观感 / 24 字最坏宽）、区域选定器（改名四入口 / 焦点转移 / 拒绝回执可见性）、充电过滤器（候选池是否真的不再出现发电机 / 控制器、自家充电器是否出现）。另：能量立方「集群」语义经审计已正确，但「整簇缓慢上涨 / 挖下放回不翻倍 / 43+ 块 int 饱和现场」仍需实机；默认充电速率（500 FE/t/目标）下用户期待的「瞬间充满」需产品决策。详见 `docs/ROUND124_USER_FEEDBACK_LANDING.md`。

## 17. 第 105 轮（1.2.0-beta.10）：电动飞行背包两项修复

### 17.1 用户反馈（原话要点）

1. 「更改电量不足喷气背包的提示只在那个玩家飞行的时候才提示；如果说玩家并没有处在飞行状态，那么即使电量低也不要提示」（指 title）。
2. 「我把它扔出去，扔出去的时候它会掉一份在地上，手上还拿着一份，我手上拿着那一份如果我再扔出去它是扔不出去的，相当于有点像是幽灵物品」——**真因两条**（见 17.2），本模组能修的是第 1 条，第 2 条是包内前置 ItemPhysic 的 Q 丢重写。

### 17.2 根因（`task-40` 只读审计，证据见任务回报）

- **A（本模组，已修）**：`ElectricJetpackItem#use` 直接调原版 `Equipable#swapWithEquipmentSlot`；1.21.1 该方法在**创造模式**用 `held.copy()` 写胸甲槽、**手上保留原栈**（「创造模式穿甲不消耗」的原版语义）⇒ 创造模式右键穿一次 = **真复制**（手上 1 + 胸甲 1）；按 Q 只丢快捷栏选中格，胸甲那份永远丢不掉。修法：创造模式单独接管，按生存语义**移动**（`setItemSlot(slot, held.copy())` 后 `setItemInHand(hand, worn)`，后者覆盖手槽本体 ⇒ 净移动 1 件）。
- **B（前置 mod，本模组无法根治）**：包内 `[物品物理掉落] ItemPhysic`（`config/itemphysic.json` → `"throw": {"enabled": true}`）用 `LocalPlayerMixin` 把原版 `LocalPlayer#drop` 短路，改由自己的蓄力 + `DropPacket` 投掷，而服务端 `ServerPlayer#drop` 里 `setRemoteSlot` 会**抑制冗余槽同步**（前提是「客户端真的自己扣了」）⇒ 两端任一次不一致就留下**永久客户端幽灵**（看得见、服务端已空、再按 Q 不发包）。规避：`itemphysic.json` 的 `throw.enabled = false`（或临时移除此 mod），并用「丢一组泥土」复现验证是否为全局现象。
- 「低电 title 只在飞行中提示」：`JetpackFlightHandler` 第 ⑦ 步加 `player.getAbilities().flying` 前置条件；不在飞时不发送、也不写 `lowPowerNotified`（下次真的起飞仍低电会立刻补一条）；耗尽 title 原本就只在「飞行中被收回权限」时发。

### 17.3 本轮验证（Lead 串行执行）

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=487`。
2. 全量自检 `tools/_run_all_selfchecks.py` → **147 个，0 失败**（`selfcheck_round99_jetpack.py` 从 76 条扩到 85 条 / 41 反例，新增 ①-h/①-i 创造模式移动 vs 复制判据、⑤-d/⑤-e 在飞才提示）。
3. `tools/verify_dedicated_server.ps1` → **PASS**（`Done (3.274s)`，无 Mixin / dist 致命标记）。
4. `gradlew build --offline --no-watch-fs` → `build/libs/rs_create_compat-1.2.0-beta.10.jar`（2,720,580 B，sha256 `c65033bf…156deb0`）。
5. 部署：SFTP `/mods` + `/client_files/mods` + 本地实例；beta.9 全部改名 `.disabled`，三方（构建 / 两处远端 / 本地）sha256 一致。

### 17.4 未验证 / 待确认（诚实清单）

1. **没有真机复现**：A/B 都是源码 + 字节码 + patched 原版源码推断；未确认用户游戏模式（A 只在创造模式成立）与具体动作（Q / 丢 GUI / 右键）。
2. 未拿到用户端 ItemPhysic 是否被改过配置（已确认 `itemphysic.json` 当前 `throw.enabled=true`）。
3. ItemPhysic 的客户端本地扣减与 `DropPacket` 的完整时序未逐 tick 复现（只有类/字节码层证据）。

### 17.5 下一轮（第 106 轮，已开工）

- `task-41`：高级归流缓存仓「区域列表真实化 + 过滤器直显 + 清无用红字 + 区域描边单显/全显」UX 重做（owner `proxy-cache-area`）。
- `task-42`：FE 能量立方「传递介质/缓存」语义（相邻主动供能 + 被抽/被注不拦 + 最低优先级；`Config.feEnergyCubePushPerTick` 已加）(owner `rx-tx-chain`)。

## 18. 第 112 轮（1.2.0-beta.14）：每区域过滤标记「随身带」+ 独立标记界面

### 18.1 用户反馈（原话，语音转写）

> 「而且现在貌似无法正常的单独给某一个区域标记嗯就非得把这个标记放在方块中进行吗这样子感觉很麻烦
>  而奇幻的时候更麻烦感觉」

（「奇幻」= 语音识别把**切换**听错；同一批的前一句是「你那个区域呢这个过滤器在这一个方块里面进行设置
而且这样子有点不太直观」。）上一轮交付后用户没有新的实机反馈，本条是 beta.13 交付同一批里的最后一条，
本轮把它当主任务。

### 18.2 根因（第 101 轮遗留的设计债）

每区域过滤存在**方块实体**（`AreaFilters`，键 = 区域序号）⇒ ①只能在机器界面里配（手持选定器无效）；
②序号一变过滤器串位；③换机器 / 拆选定器即丢；④区域列表的「编辑过滤」只是切 48 格窗口上下文并退回机器界面；
⑤快照过滤图标按**原始下标**查「按可用位次存」的表 ⇒ 有区域被排除时图标串位。

另外：打开区域列表只有「潜行 + 右键空气」，而玩家在基地里几乎总对着方块 ⇒ 列表打不开，只能走机器。

### 18.3 修法（一句话）

过滤标记搬到**选定器物品**（`support/AreaMarks`，`CUSTOM_DATA` 的 `RsccAreaMarks`，键 = 六坐标几何，
编码复用 `CollectionCacheBlockEntity#encodeMarkerTag/decodeMarkerTag`，旧档自动迁移）；新增
`network/AreaMarkEditPacket`（服务端按「背包第 N 格」自己取物品）+ `support/AreaSelectorTargets`
（两个写包共用落点判据）+ `client/screen/AreaMarkScreen`（左 = 区域标记、右 = 背包，点一下加 / 删）；
区域列表「编辑过滤」直接开它；`AreaSelectorHandler` 补「不潜行右键空气也开列表」。

### 18.4 本轮验证

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=499`。
2. 全量自检（`D:\mods\pyenv-staritem\scripts\python.exe tools/_run_all_selfchecks.py`，**必须用这个
   解释器**：`selfcheck_advancements.py` 依赖 PIL，系统 `python` 没有）→ **158 个，0 失败**。
3. `tools/verify_dedicated_server.ps1` → **PASS**（`Done (1.674s)`）。
4. `gradlew build` → `rs_create_compat-1.2.0-beta.14.jar`（2,784,982 B，
   sha256 `8821d502007fa5f42275d255c67f58cdddef2e4fcf3e871736f0c2b049e7205c`）。
5. 三处部署（本地实例 / SFTP `/mods` / SFTP `/client_files/mods`）回读 sha256 一致
   （`tools/_verify_deploy_round112.py` → `DEPLOY_VERIFY_OK`），beta.13 就地改名 `.disabled`。

### 18.5 未验证 / 待确认（诚实清单）

1. 双网格界面**实际观感**（间距 / 36 格够不够）没有截图复核。
2. 「手持配标记 → 放进机器 → 吸到该物品」的**端到端**链路只有源码 + 行为模型，没有实机吸收。
3. 旧档 `AreaFilters → 几何键` 迁移没有用真实旧存档跑过（代码路径：`loadAdditional` →
   `migrateLegacyAreaFilters`；选定器不在槽里时会等待，放进来再迁）。
4. 数量门槛 / NBT / 标签匹配这些细调仍只在机器界面的 48 格窗口里（两边同一份数据）。

### 18.6 下一轮候选

- 用户对 beta.14 的实机反馈（优先）。
- 「区域标记」界面的 36 格上限是否要分页；是否把数量门槛 / 标签匹配也搬进这个界面。
- 是否给「打开区域列表」加一个快捷键入口。

---

## 19. 第 113 轮（1.2.0-beta.15）：区域标记链路加固（独立复核 + 端到端模型）

### 19.1 为什么做

第 112 轮（beta.14）由单代理一轮写成，它的 96 条自检**全是源码锚点 + 孪生模型**。本工程已有两次
「编译过 + 自检绿但真机出事」的先例，所以本轮在等用户实机反馈期间补两件事：
① 只读**对抗复核**（不信任何既有结论，按「写 → 存 → 读 → 用」走完整链路）；② **端到端行为模型自检**
（覆盖「手持配标记 → 放进机器 → 按标记吸收」与「旧档迁移」两条第 112 轮没覆盖的链）。

复核报出 **3 高 + 3 中 + 6 低** 共 12 条缺陷（没有会崩 / 断线 / 丢真实物品的），全部修完。

### 19.2 三条高危（都是「丢玩家配置」）

1. **`ACTION_REPLACE` 改坐标会删掉该区域全部过滤标记**：标记以几何为键，REPLACE 换几何后
   `AreaMarks.prune` 把旧几何当已删除区域剪掉（beta.13 键是序号 ⇒ 不丢，属回归）。
   修法：REPLACE 前后搬运标记，新几何已有标记则按 `(id, fluid)` 去重合并、不覆盖。
2. **旧档迁移「0 条映射上」仍清空旧表**：插一把空白 / 区域为空的选定器进旧机器 ⇒ 旧表被清并
   写回存档 ⇒ 每区域过滤永久消失（只留「已迁 0 个区域」的 INFO）。
   修法：只删已映射条目（`index < areaCount`），未映射的原样保留 + WARN（`legacyMigrationWarned` 节流，
   读档重置），区域表恢复后自动重试。
3. **迁移用 `sanitize` 后的区域表**：Config 调小 / 区域超限时对应标记被跳过、随后（2 的）清表毁掉。
   修法随 2：未映射条目保留在 NBT 里，Config 调回即恢复。

中危 3 条：`prune(null)` 会清空全部标记（改 null 早退 + 原始列表遍历）；单条标记无大小上限
（`MAX_MARKER_BYTES = 2048` + `isOversized`，服务端 / 客户端两处拦）；主手路径被拒后不回同步
（补 `inventoryMenu.broadcastFullState()`）。低危 6 条见 `docs/ROUND113_AREA_MARK_HARDENING.md` 第 2 节。

### 19.3 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=499`**
2. 全量自检（`tools/_run_all_selfchecks.py`）→ **159 个，0 失败**
   （新增 `selfcheck_round113_area_mark_e2e.py`：**140 断言 / 38 反例**；
   `selfcheck_round112_area_marks.py` 更新到 **100 断言 / 12 反例**）
3. `tools/verify_dedicated_server.ps1` → **PASS**（`Done (2.024s)`）
4. `gradlew build` → `rs_create_compat-1.2.0-beta.15.jar`（**2,788,186 B**，
   sha256 `5311d7e3129a70637b2c0ea6a23fabd6d37f97d35a44a89e0da3b734e6d4cc91`）
5. 三处部署（本地实例 / SFTP `/mods` / SFTP `/client_files/mods`）回读 sha256 一致
   （`tools/_verify_deploy_round113.py` → `DEPLOY_VERIFY_OK`），beta.14 就地改名 `.disabled`

### 19.4 未验证 / 待确认（诚实清单）

1. 本轮**没有实机验证**：REPLACE 携带标记、旧档「保留未映射条目」、单条上限提示都只有源码锚点 + 行为模型。
2. `CustomData#update` 是否返回新实例未能 javap 证明（按契约推断）；`ItemStack.parseOptional(null, …)`
   的 NPE 也是按契约推断（已用 `registries == null` 早退兜住）。
3. beta.13 旧 `AreaFilters` 的 `Index` 是「sanitize 后可用位次」还是「原始下标」仍未拿到旧源码佐证。
4. 机器侧 48 格 ghost 窗口仍可写入大 NBT 标记（第 101 轮旧路径，本轮按「不改旧语义」处理）。
5. 重叠区域「先命中先算」（靠前的空标记区域挡住后面区域）是既有行为，未改，UI 也无提示。

### 19.5 下一轮候选

- **用户对 beta.14 / beta.15 的实机反馈（最高优先）**。
- 36 格上限是否分页；数量门槛 / 标签匹配是否搬进「区域标记」界面。
- 「打开区域列表」快捷键入口。
- 拿到真实 beta.13 存档后跑一次真实迁移（含删过区域的老档），确认 WARN 与保留逻辑。

---

## 20. 第 114 轮（1.2.0-beta.16）：标记体积「整表预算」统一拦截 + 独立复核修复 8 条

> 基线：用户实机跑的是 `1.2.0-beta.15`（第 113 轮，2026-10-31T15:30 构建，sources=499）。
> 本轮**没有新的用户实机反馈**，做的是第 113 轮明确记账、当时按「不改旧语义」放掉的那类缺陷；
> 同时跑了一次独立只读对抗复核（`cross-review`），报出的 **2 高 + 4 中 + 2 低全部修完**。

### 20.1 为什么需要「整表」预算

区域标记存在**选定器物品**的 `CUSTOM_DATA`（`RsccAreaMarks`）上，**随物品一起网络同步**
（背包 / 菜单槽 / 手持 / 快捷栏）。第 113 轮只加了「单条 2 KB」上限，三条缺口：

1. `MATCH_CAPACITY = 288` 条 × 每条接近上限 ⇒ 整表仍可到几百 KB；
2. 机器侧 48 格 ghost 窗口（第 101 轮旧路径）**零拦截**，写的是同一份数据；
3. `SetCollectionMarkerConfigPacket` 的 `nbt` / `tags` 是**客户端原样回传的不透明负载**，
   服务端直接落库（改包可绕过所有界面拦截）。

### 20.2 设计（`support/MarkerBudget.java`，新建）

| 常量 | 值 | 含义 |
|---|---|---|
| `MAX_ENTRY_BYTES` | `16384` | 单条 `sizeInBytes` 上限 ≈ **线上 2 KB** |
| `MAX_TOTAL_BYTES` | `524288` | 整张表 `sizeInBytes` 上限 ≈ **线上 64 KB** |
| `WIRE_RATIO` | `8` | 堆估算 → 线上字节的经验倍率（复核实测 648/79 ≈ 8.2，**取整偏保守**） |
| `MAX_ENTRY_WIRE_BYTES` / `MAX_TOTAL_WIRE_BYTES` | `2048` / `65536` | 文案 / 日志用（界面显示 **64 KB**） |

- **口径校准**：`CompoundTag#sizeInBytes()` 是 **JVM 堆估算**（48 基数；每键 `28+2·len+36`；
  Int 12 / Long 16 / Byte 9 / String `36+2L` / List `37+4n`），约线上字节 × 8。
  最初把「2 KB / 64 KB」当 `sizeInBytes` 阈值写 = 线上只有 256 B / 8 KB，
  会把「默认 16 区域 × 平均 6 条」的正常配置判成超限（新一轮「加标记没反应」）。
- **高级版算法**：`现有子树 sizeInBytes − 该区域旧记录 + 新记录编码（writeArea）`；
  不逐条重编码（旧格式 `Item` 条目在 `registries == null` 下会被漏算，而这些恰恰可能是大 NBT）。
  基础版（数据在 BE）用 `entryBytes` 逐条求和（`markerTableBytes`）。
- **拦截入口**：新界面（客户端提示 + 服务端兜底）、`AreaMarkEditPacket`、`addAreaMarker`（48 格）、
  `tryAddMarker` / `addMarkerForUi`（`TOO_COMPLEX`）、`setMarkerConfig`（客户端自带 nbt，WARN 拒绝）。
- **不拦**：读档、旧档迁移、换区域搬运（纪律：不静默删玩家数据）；**删除永远允许**。

### 20.3 独立复核报出的缺陷与修法

| 级别 | 缺陷 | 修法 |
|---|---|---|
| 高 H1 | `SetCollectionMarkerConfigPacket` 解码 `new ArrayList<>(tagCount)` ⇒ 谎报计数即可在 menu 校验前 OOME | `TAG_CAP = 256`：按 count 消费字节、只暂存前 256 条 |
| 高 H2 | 旧档迁移把超大标记搬进选定器；整表超预算后该选定器**永久不能再加标记** | `migrateByIndex` 逐条判：超单条 / 会超整表 ⇒ **冻结在旧表**；**谁消费谁删**；调用方不再 `removeIf(index < areaCount)` |
| 中 M1 | 迁移用 sanitize 后区域表做序号映射 ⇒ 序号漂移、标记**套到别的区域** | `AreaMarks.isIndexPrefix`：usable 必须是 raw 的几何前缀，否则整体冻结 + WARN |
| 中 M2 | 部分迁移成功时每 tick `setChanged` + `invalidateAreaSnapshot` ⇒ 每 tick 重发快照 | 只有 `changed`（有消费 / 有写入）才做三件副作用 |
| 中 M3 | 高级仓区域标记的「数量 / NBT / 标签」配置写到**没人读的全局匹配区**（5 参包走基类 6 参覆写，高级菜单只覆写 3 参） | `AdvancedCollectionCacheMenu` 补 6 参覆写：有区域上下文且非流体 ⇒ `setAreaMarkerConfig`，否则 `super` |
| 中 M4 | `isOverBudget` 删除分支返回「超预算 = true」，与「删除永远允许」相反 | 三分支：`next == null` 查询；`next` 空 ⇒ `false`；否则替换后比较 |
| 低 L1 | REPLACE 合并后 `put` 截到 288 条且无日志 | 未改（只有「旧区域 >288 条」的手改 NBT 才可能触发），记入待办 |
| 低 L2 | `prune` 每剪一个区域整树 copy ⇒ O(n²) | 先计数、0 直接返回、否则一次 `update` 批量重建 |

复核同时**正面确认**：`CUSTOM_DATA` 确实随物品同步（`DataComponentType.Builder#build()` 未声明
`networkSynchronized` 时回退 `fromCodecWithRegistries`）；`CustomData.update` 确实 `tag.copy()`。

### 20.4 自检

- 新增 `tools/selfcheck_round114_marker_budget.py`（1408 行，纯 stdlib）：**93 断言 / 19 反例**，
  行为模型逐字复刻 1.21.1 `Tag#sizeInBytes` 公式 + `encodeMarkerTag`/`writeArea` + `isOverBudget`
  三分支 + 迁移「消费 / 冻结」；反例自证：`/tmp` 副本上 **10 处打坏 ⇒ 10/10 判负**。
- `selfcheck_round112` **100/12**、`selfcheck_round113` **140/38**：按新迁移语义诚实更新（判据加强）。
- `selfcheck_round106`：`_has_invalidating()` 的「固定 2000 字符窗口」在方法变长后误报，
  改成「从签名括号配平到真实结束」，判据不变。
- 全量：**160 个脚本 / 0 失败**。
  > ⚠ 跑全量自检要用**带 Pillow 的运行时 python**
  > （`C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe`）：
  > 系统 `python` 没有 PIL，`selfcheck_advancements.py` 会 `ModuleNotFoundError`（**不是本轮回归**，
  > 是解释器环境差异；本轮复现过一次 160/1，换运行时 python 后 160/0）。

### 20.5 本轮验收

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=500`
2. 全量自检 → **160 / 0 失败**
3. 专服探针 → **PASS**（`Done (1.939s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `rs_create_compat-1.2.0-beta.16.jar`（**2,793,209 B**，
   sha256 `29bc61c27f9c1ee4390cb8fef2320d48b8ea6da0ba378d66413bc792e43c6ed2`）
5. 三处部署 sha256 一致（`tools/_verify_deploy_round114.py` → `DEPLOY_VERIFY_OK`），beta.15 就地 `.disabled`

### 20.6 未验证 / 待确认（诚实清单）

1. **没有实机点界面**：真实 `CUSTOM_DATA` 同步体积没有抓包实测；`WIRE_RATIO = 8` 是按 MC 堆公式估算，
   内容里字符串占比不同会漂移（当上界估算用）。
2. **旧数据可能「一出生就超限」**：迁移 / 搬运不拒；老存档若本就有超大标记，整表可能 > 线上 64 KB，
   此时新增全被拒（删除仍允许），迁移会冻结那些区域并 WARN —— 没有实机验证过玩家的反应。
3. `MergeResult.pinned` 目前恒为 `false`（**API 预留位**）：位次冻结由 BE 自己用 `isIndexPrefix`
   判断后直接 return；字段与 `touched()` 里的 `|| pinned` 暂时是死代码，下一轮要么让
   `migrateByIndex` 接收 raw/usable 自己置位，要么删字段。
4. H1 仍按客户端 `count` 循环解码（不预分配，无 CPU 放大；count 极大时走到包尾抛异常，可接受）。
5. 文档量级：普通标记堆估算 teammate 模型算 **658 B**（id `minecraft:iron_ingot`），
   `MarkerBudget` javadoc 写「约 648 B（复核实测 648/79）」—— 同一量级，未统一（不影响判据）。
6. 冻结条目会随 `saveAdditional` 长期留在存档里（符合「不删玩家数据」），每次加载 WARN 已节流。

### 20.7 下一轮候选

- **用户对 beta.15 / beta.16 的实机反馈（最高优先）**。
- L1：REPLACE 合并后超 288 条补 WARN；`MergeResult.pinned` 收尾（见 20.6-3）。
- 36 格上限是否分页；数量门槛 / 标签匹配是否搬进「区域标记」界面；「打开区域列表」快捷键。
- 其他机器（数量守恒器 / 序列执行仓…）的标记表体积与同步路径未测（本轮范围外）。


---

## 21. 第 115 轮（1.2.0-beta.17）：跨机器「标记 / 导入」写入口的体积与计数上限

> 基线：用户实机跑的是 `1.2.0-beta.16`（第 114 轮，2026-10-31T15:52 构建，sources=500）。
> 本轮**没有新的用户实机反馈**，清的是第 114 轮 §20.6 / §20.7 自己记的账：**其他机器的标记表
> 体积与同步路径未测**。由独立只读复核员 `cross-review` 盘完，按其报告修了三组缺陷。

### 21.1 复核的总体结论（为什么值得单独一轮）

> 除区域选定器标记外，**全仓没有任何第二处标记 / 过滤表接入体积预算**；且 C2S 侧还剩
> **一处**「谎报计数 ⇒ 解码阶段 OOME」与第 114 轮 H1 完全同源（`SetSequenceImportPacket`）。

盘点表（机器 / 负载 / 上限 / 任意大 NBT 写入口）见 `docs/ROUND115_CROSS_MACHINE_BUDGET.md` §2。
PASS 且未动：序列执行仓、单元样板管理舱（仅 1 格旧档抢救槽、界面已移除）、装填器
（字符串全由服务端写）、资源请求器（`orders` 无上限是刻意的，且键必须是已注册资源）。

### 21.2 四项修法（对应复核 H1 / H2 / H3 / L1）

1. **H1 `SetSequenceImportPacket` 解码不预分配**：`MAX_IMPORT_LIST = 4096` /
   `MAX_IMPORT_STEPS = 1024`；仍按 `count` 逐个解码（流对齐），只暂存前 N 条；
   `machines` 不再用 `buf.readList`（MC 实现同样按计数预分配）改手工循环；负计数 `Math.max(0, …)`。
2. **H2 终端导入步数 clamp**：`SequencePatternTerminalBlockEntity.MAX_IMPORT_STEPS = 1024`，
   菜单 `importSequencedRecipe` 在**生成单元样板之前** `Math.min(machines.size(), MAX)` + WARN。
   **不裁在 `applyRecipeToArrangement`**（那里会连既有列一起丢）。
3. **H3 两台定量保持器接同一把尺**：`MarkerBudget.isTagTooBig(CompoundTag)` /
   `isStackTooBig(ItemStack, registries)`；四个服务端入口（两台机器 × `setFluidMarker` /
   `setItemMarker`）**先判后写**，超限 `return`（旧标记原样保留、不清另一形态、不同步放大），
   每台机器只 WARN 一次（`markerTooLargeWarned`）。`registries == null` ⇒ 判不了 ⇒ **不拒**。
4. **L1 REPLACE 合并超 288 条补 WARN**：`AreaSelectorEditPacket` 合并后
   `merged.size() > MATCH_CAPACITY` ⇒ WARN（说明保留新几何原有标记在前、被截掉的需手动重加）。

### 21.3 `pinned` 收尾（第 114 轮 §20.6-3）

- `AreaMarks#migrateByIndex` 现在**自己** `AreaSelection.get(stack)` 读原始区域表 → `isIndexPrefix`；
  不成立 ⇒ `MergeResult(0,0,0,0,0,true)`（0 迁移 / 0 消费）。BE 的前置判断保留（能打带坐标的 WARN），
  数据层这层是纵深防御。
- `MergeResult#touched()` 改成 `migrated > 0 || skippedExisting > 0` —— **冻结不算「动过」**
  （第 114 轮它恒 false 所以看不出问题；现在它是可达状态，算进去会让调用方对没变的表标脏 / 失效快照）。
- 自检：`selfcheck_round114_marker_budget.py` 更新 `⑥-1` + 新增 `⑥-25`～`⑥-28`，现 **97 断言 / 20 反例**。

### 21.4 自检

- 新增 `tools/selfcheck_round115_cross_machine_budget.py`：**27 断言 / 6 反例**（纯 stdlib，
  行为模型判边界：`> MAX_ENTRY_BYTES`、`steps` clamp、`min(count, 4096)`、拒收后状态不变）。
- 全量：`tools/_run_all_selfchecks.py` → **161 个脚本 / 0 失败**。
  > ⚠ 必须用**带 Pillow 的运行时 python**（`selfcheck_advancements.py` 需要 PIL）。

### 21.5 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=500`**
2. 全量自检 → **161 个脚本 / 0 失败**
3. 专服探针 → **PASS**（`Done (1.928s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `rs_create_compat-1.2.0-beta.17.jar`：**2,796,072 B**，
   sha256 `034929faba518b2bac3d0163b734aefe24e92f5771537596adea77b9b1367917`
   （`build_info`：`modVersion=1.2.0-beta.17` / `sources=500` / `builtAt=2026-10-31T16:39:56+0800`）
5. 三处部署 sha256 一致（`tools/_verify_deploy_round115.py` → `DEPLOY_VERIFY_OK`：本地实例 /
   SFTP `/mods` / SFTP `/client_files/mods`），beta.16 就地 `.disabled`
   > ⚠ 部署脚本要用**带 paramiko 的 python**（本机 `D:\Python313\python.exe`）；
   > 运行时 python（`.dsh/.../dependencies/python`）没有 paramiko，会 `ModuleNotFoundError`。

### 21.6 未验证 / 待确认（诚实清单）

1. **没有实机点界面，也没有真机改包**：判据是「源码锚点 + 行为模型 + 反例自证」；改包的真实放大倍率
   与「被拒后玩家/攻击者看到什么」都没有在运行环境观测。
2. **单包真实帧长上限没实测**：H2 的「约 2 MB 的包」按 MC 协议 VarInt 理论上界估算。
3. **H3 只覆盖两台定量保持器**：序列终端的 4 个写入口只做了条数保护（H1/H2），没有逐条 ItemStack
   体积预算；`UnitLibrary` 自动扩容仍无条数上限（复核 L3）。本轮有意收窄写域。
4. **M1 未修**：`SetCollectionBlockedPacket.tags` 的四个阻塞标签名单无上限、不校验 tag 存在
   （每件被收集物品线性扫 + 逐个 `TagKey.create`）。加 cap 前要定「要不要拒绝不存在的 tag」（产品口径）。
5. **M2 未修**：`/rs_create_compat diag` 无权限，普通玩家可反复触发全量遍历世界 + 大 JSON 落盘；
   改动会影响既有诊断工作流，需要拍板。
6. `isStackTooBig` 在 `registries == null` 时**放行**（宁可放过不误拒）；目前所有 C2S 入口都有 level。

### 21.7 下一轮候选

- **用户对 beta.14～beta.17 的实机反馈（最高优先）**。
- 用户三项待定：36 格上限是否分页；数量门槛 / 标签匹配是否搬进「区域标记」界面；「打开区域列表」快捷键。
- M1（阻塞标签 cap + 是否存在 tag 校验口径）、M2（diag 权限）—— 需要用户拍板。
- 序列终端 4 个写入口的逐条 ItemStack 体积预算 + `UnitLibrary` 条数上限。


---

## 22. 第 116 轮（1.2.0-beta.18）：序列装配终端家族的「物品 / 流体 / 字符串」写入面收口

> 基线：用户实机跑的是 `1.2.0-beta.17`（第 115 轮，2026-10-31T16:40 构建，sources=500）。
> 本轮**没有新的用户实机反馈**，清的是 §21.6-5 / §21.7 自己记的账：序列终端 4 个 C2S 写入面没接体积预算 + 复核 L3「单元样板库无条数上限」。

### 22.1 为什么值得单独一轮

第 114/115 轮把体积预算铺到了区域标记、两台定量保持器、导入包解码；但序列装配终端家族的 4 个 C2S 写入面一直直接吃客户端的 `ItemStack` / `FluidStack`，
写进方块实体 NBT 与单元样板 `CustomData` 并**永久落盘**，随每次同步回发 —— 与「把物品设为标记」同一类风险。
改完这一轮，全仓「客户端给的物品 / 流体 / NBT 会落盘」的 C2S 写入口**全部接入同一把尺**（`MarkerBudget`）。

### 22.2 六个改动点

| # | 位置 | 改法 |
| --- | --- | --- |
| 1 | `support/MarkerBudget.java` | 抽出 `stackBytes` / 新增 `fluidBytes`（`saveOptional`）；`isStackTooBig` 改为委托；新增 `isFluidTooBig`；判不了 ⇒ 0 ⇒ 不拒 |
| 2 | `network/SetSequenceGhostPacket.java` | JEI ghost 拖拽→输入原料标记槽：单条尺（线上 ≈ 2 KB）先判后写，拒绝时旧标记不动 + 动作栏提示 |
| 3 | `network/SetStepFluidPacket.java` | 步骤输入流体（FluidStack 带 `DataComponentPatch`）：同一把尺 |
| 4 | `network/CreateUnitPatternPacket.java` | `input` 过尺；`name` / `recipeType` ≤ 256 字符（界面上限 64）；写库时 `targetSlot >= max(库容量, 硬上限)` 或首空位 = -1 ⇒ 拒绝 + 动作栏提示 |
| 5 | `network/SetSequenceImportPacket.java` | 解码：整包暂存 8192（每列表仍 4096，字节仍全消费）；`handle`：`budgetViolation` 判单条 2 KB / 合计 256 KB / 字符串 256 与合计，超限**整包拒绝（原子）** |
| 6 | `block/entity/SequencePatternTerminalBlockEntity.java` | `MAX_UNIT_LIBRARY_SLOTS=1024`；`firstEmptySlot()` 到上限返回 -1；`freeSlots/canAccept`；`putUnitIntoLibrary` 返回 boolean；`pullUnitsFrom` 到上限 break（留在执行仓）；生成前 `canAccept(unitsToStore)` 预检（在写总样板与扣 RS 样板之前）；`rscc$resize` 拆出 `rscc$resizeRaw`，**读档不判上限**、**任何增长不许越限** |

另新增 3 条文案（`message.rs_create_compat.sequence.marker_too_large` / `.import_too_large` /
`gui.rs_create_compat.sequence_pattern_terminal.generate.library_full`，zh_cn + en_us 各一份）。

### 22.3 独立只读复核（只审库容量上限）与四条修正

结论：**无高严重度缺陷**（不丢档 / 不崩溃 / 不会扣料后丢样板），但报出四条，全部已修：

- **M1（中）** `rscc$resize` 守卫只在「从 ≤ 上限跨过」时拒绝 ⇒ 旧存档里已 > 1024 格的库每次 `ensureTrailingEmpty` 仍能 +1（形同没上限）。⇒ 改成**任何增长**不许越限（缩小仍允许）。
- **M2（中偏低）** `canAccept` 对 > 1024 旧库只算空槽 ⇒ 常见旧库任何 ≥2 步生成被判「库已满」。M1 修正后这是**正确**语义（那种库确实不能再扩），文案 / 类注释已说明「取出一些即可」。
- **L1（低）** packet 对旧库 ≥1024 槽的编辑写回被拒且只打日志 ⇒ 上限改成 `max(库容量, 硬上限)`，拒绝时补动作栏提示。
- **L2（低，文档）** 类注释仍写「可增长、无上限」「不会因为库满而丢样板」 ⇒ 已重写。

### 22.4 自检

- **新增** `tools/selfcheck_round116_sequence_write_budget.py`：**32 断言 / 8 反例**（纯 stdlib）。
  判据 = 行为模型（单条 / 合计 / 字符串 / 解码暂存 / 库容量与扩容上限 / `canAccept`）
  + 源码锚点（「先判后写」顺序）+ 反例自证（删体积检查 / 删整包暂存上限 / 改回 `return getSlots();` /
  把 resize 守卫改回「仅跨过时拒绝」/ 不调用 `budgetViolation`）。
- **更新** `tools/selfcheck_round115_cross_machine_budget.py`：①-2 按「`isStackTooBig` 委托 `stackBytes`」心实更新；
  ③-2 / ③-6 / ③-7 按 `readStacks` 初始容量 64 + 整包 8192 的新口径更新（判据加强：仍要求「不按对端计数预分配 + 仍按 count 消费字节」）。
- **更新** `tools/selfcheck_candidate_pipeline.py`：`readStacks(buf, staged)` 新签名（判据不变）。
- **全量**：`tools/_run_all_selfchecks.py` → **162 个脚本 / 0 失败**。

### 22.5 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=500`**
2. 全量自检 → **162 个脚本 / 0 失败**
3. 专服探针 → **PASS**（`Done (1.805s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `rs_create_compat-1.2.0-beta.18.jar`：**2,802,193 B**，
   sha256 `aed7c5934af721ecb45564d5a57fb3e05c1d75a942aa1f8c91d4e2102829f08f`
   （`build_info`：`modVersion=1.2.0-beta.18` / `sources=500` / `builtAt=2026-10-31T17:12:45+0800`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round116.py` → **`DEPLOY_VERIFY_OK`**：
   本地实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.17 就地改名 `.disabled`
   > ⚠ 部署脚本要用**带 paramiko 的 python**（本机 `D:\Python313\python.exe`）。

### 22.6 未验证 / 待确认（诚实清单）

1. **没有实机点界面，也没有真机改包**：判据是「源码锚点 + 行为模型 + 反例自证」；
   改包的真实放大倍率、被拒后玩家 / 攻击者看到什么，都没有在运行环境里观测。
2. **数值口径**：`MAX_ENTRY_BYTES`（线上 ≈ 2 KB）与 256 KB 合计预算都是按「`sizeInBytes()` 堆估算 ≈ 8 倍线上字节」的经验换算；
   256 KB 对应「≈ 3300 条普通物品」是估算，**没有拿真实大型 modpack 的标签型 ingredient 实测过**。
3. **1024 格上限没有实机跑到**：初始 108 格、生成默认跳过重复，正常玩法远达不到。
4. **旧存档 > 1024 格的库**：只在行为模型 / 源码上验证「读档不截断 + 增长被拒」，没有真实旧档实例。
5. **仍开着（需产品拍板）**：M1 `SetCollectionBlockedPacket.tags` 阻塞标签名单无上限 / 不校验 tag 存在；
   M2 `/rs_create_compat diag` 无权限；用户三项待定（36 格分页 / 数量门槛搬进区域标记界面 / 区域列表快捷键）。
6. **剩下未接体积预算的写入口只剩「字符串列表」类**：`SetCollectionBlockedPacket.tags`、
   `SetChargerFilterPacket` 的过滤名单 —— 与 M1 一起做更合适。

### 22.7 下一轮候选

- **用户对 beta.14～beta.18 的实机反馈（最高优先）**。
- 用户三项待定 + M1 / M2（需产品拍板）。
- 字符串列表类写入口（阻塞标签 / 充电器过滤名单）的整表体积与 tag 存在性校验。


---

## 23. 第 117 轮（1.2.0-beta.19）：归流缓存仓阻塞名单收口 + 上一轮复核的 H1 / M2

> 基线：用户实机跑的是 `1.2.0-beta.18`（第 116 轮）。本轮**没有新的用户实机反馈**。
> 两件事：① 清 §22.6-6 自记的账 —— 全仓最后一类「名单 / 字符串列表」C2S 写入口；
> ② 独立只读复核第 116 轮的四个写入面（1 高 / 3 中 / 5 低），高 + 两个中在本轮修掉。

### 23.1 为什么值得单独一轮

「阻塞」自「按标签阻塞」上线后是**四个集合**（物品 id / 流体 id / 物品标签 / 流体标签），
增长完全由客户端驱动，而两条口子都是前几轮反复修过的同一类：

1. **条数无上限**：每包换一个新 id / 新标签就能无限撑大 —— 会进 `writeBlocked` 落盘，
   并随每次开界面的 `SyncCollectionMarkersPacket` 全量回发；
2. **标签包解码无上限**：`ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list())` 的条数由对端给。

### 23.2 四个改动点

| # | 位置 | 改法 |
| --- | --- | --- |
| 1 | `support/CollectionBlockedLimits.java`（新） | 上限唯一出处：`MAX_BLOCKED_IDS=1024` / `MAX_BLOCKED_TAGS=256` / `MAX_TAGS_PER_PACKET=128` / `MAX_TAG_NAME_LENGTH=128`；`fits()` 有界加入（幂等：已存在不占名额）；`clamp()` 委托 `ChargerFilterLimits.clamp`；纯 JDK ⇒ 自检可 javac 真跑 |
| 2 | `block/entity/CollectionCacheBlockEntity.java` | 新增唯一写入口 `applyBlockedEdit` + `BlockEditResult{CHANGED,UNCHANGED,REJECTED_IDS_FULL,REJECTED_TAGS_FULL}`：**先判后写、原子拒绝**；`sanitizeBlockedTags` 丢 null / 超长 / 重复；旧 `setBlocked` / `setBlockedTags` 委托它；**读档不判上限**（超限旧档原样读回） |
| 3 | `menu/CollectionCacheMenu.java` | `setBlocked` 返回 `BlockEditResult`（不再 void），无论结果都 `syncMarkers()` 回传权威名单 |
| 4 | `network/SetCollectionBlockedPacket.java` | 手写 `TAGS_CODEC`：按声明条数**消费全部字节**但**只暂存前 128 条**、不按对端计数预分配；`handle` 区分两种拒绝 → 动作栏提示（带真实上限值）+ 每进程一条 WARN |

新增 2 条文案（`message.rs_create_compat.collection_cache.blocked_ids_full` / `.blocked_tags_full`，zh/en 各一份）。

### 23.3 独立只读复核（第 116 轮四个写入面）与本轮修复

- **HIGH（已修）** `SetSequenceImportPacket` 的 `recipeId` 完全没进预算，而它被 `SequencePatternData#TAG_RECIPE`
  **逐张复制进每一步的单元样板** ⇒ 32 KB recipeId × 1024 步 ≈ **33 MB 落盘**（客户端单包仅约 30 KB）。
  修法：单条限长 256 字符 **+** 按步数计入字符串合计（两道闸各自独立可挡，自检 ⑧-1 / ⑧-2 分别验证）。
- **中（已修）** 解码暂存上限触发时各列表**互相错位**却仍成功导入（玩家收到 `import.done` 得到半张配方）。
  修法：`truncated` 分量（**不上线格式**：`encode` 不写、客户端构造传 false）→ `handle` 在写入前整包拒绝。
- **中（已修）** `> MAX_ENTRY_BYTES` 比较四处各写一遍 ⇒ 统一 `MarkerBudget#isEntryBytesTooBig`。
- **低（已修）** 覆盖输出槽不返还旧样板 ⇒ 先交给玩家（背包满则掉脚下）；非法负 `targetSlot` 拒绝 + WARN；
  三处日志「≈2 KB 线上」改成「堆估算阈值 + ≈2 KB 线上意图」；六处「陈述≠代码」逐条改正
  （Ghost 无「普通槽直接放入」分支且当前无发送方 / CreateUnit 越界是拒绝不是收敛 /
  `TARGET_FIRST_EMPTY_LIBRARY` 不再自动扩容 / 库容量注释（1024 格 ≈150–300 KB、读档走 `rscc$resizeRaw`、
  `serializeNBT` 按最后非空槽 + 1 落盘 ⇒ 槽位数会压缩）/ `MarkerBudget.WIRE_RATIO` 在字符串主导负载下是**下界**）。
- **未修**：M1 库只有条数限、没有体积限（1024 格理论 ≈2.5 MB 线上）；`ArrangementSize` 读档无上限（旧有）。

### 23.4 自检

- **新增** `tools/selfcheck_round117_blocked_limits.py`：**34 断言 / 13 反例**。
  四条腿：源码锚点 + **真代码 JVM 探针**（javac 编译纯 JDK 的 `CollectionBlockedLimits` 后真跑 17 条）
  + 行为模型（`applyBlockedEdit` 8 条语义）+ 反例自证（含 H1 两道闸、M2 截断标记、读档截断、半写等）。
- **更新**（只改锚点、判据没放水）：`selfcheck_round115_cross_machine_budget.py`（①-1 / ①-2）、
  `selfcheck_round116_sequence_write_budget.py`（①-1 / ①-3 / ①-5，并要求「比较只有一份实现」）、
  `selfcheck_round9_feedback.py`（阻塞名单写入入口锚点改成 `applyBlockedEdit`，并断言旧入口仍委托它）。
- **全量**：`tools/_run_all_selfchecks.py` → **163 个脚本 / 0 失败**。

### 23.5 本轮验收

1. `tools/_lead_compile.ps1` → **`LEAD_COMPILE_OK sources=501`**
2. 全量自检 → **163 个脚本 / 0 失败**
3. 专服探针 → **PASS**（`Done (1.901s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `rs_create_compat-1.2.0-beta.19.jar`：**2,808,433 B**，
   sha256 `021d39cef65a38a5dae5f8b408601bb7586a0bd0ee3a6327ee518ca3b629dcd1`
   （`build_info`：`modVersion=1.2.0-beta.19` / `sources=501` / `builtAt=2026-10-31T17:53:03+0800`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round117.py` → **`DEPLOY_VERIFY_OK`**），beta.18 就地 `.disabled`
   > ⚠ 部署脚本要用**带 paramiko 的 python**（`D:\Python313\python.exe`）。

### 23.6 未验证 / 待确认（诚实清单）

1. **没有实机点界面，也没有真机改包**：判据 = 源码锚点 + 行为模型 + JVM 探针 + 反例自证。
2. **上限数值是估算**（1024 / 256 / 128 / 128 字符）：按正常玩法用量级 × 余量定，没有真实整合包统计。
3. **超限旧存档**：只在行为模型 / 源码上验证「读档不截断 + 只拒增长 + 删除永远允许」，没有真实旧档实例。
4. **NeoForge 对已注册 payload 是否仍限 32767 字符**未核实 ⇒ H1 的放大倍率是**下限**。
5. **M1 未修**：单元样板库只有条数上限、没有体积上限；`/rs_create_compat diag` 无权限（需产品拍板）。

### 23.7 下一轮候选

- **用户对 beta.14～beta.19 的实机反馈（最高优先）**。
- M1：单元样板库的「合计体积预算」（条数已有 1024，体积还没有）。
- 用户三项待定（36 格分页 / 数量门槛搬进区域标记界面 / 区域列表快捷键）+ `diag` 权限。

---

## 24. 第 118 轮（1.2.0-beta.20）：单元样板库体积预算 + 两路独立只读复核（装填器 / 保持器）

> 本轮**没有新的用户实机反馈**。三件事：① 清第 117 轮 §23.6-5 的账（单元样板库只有条数没有体积）；
> ② 请一位只读复核员**对抗式**复核此前没专项审过的**蓝图加农炮装填器**家族（2 高 / 4 中 / 3 低）；
> ③ 请另一位只读复核员复核**中间缓存仓 + 定量保持器**家族（2 高 / 3 中 / 4 低）。

### 24.1 单元样板库：合计体积预算（遗留 M1）

| 改动点 | 说明 |
| --- | --- |
| `support/UnitLibraryLimits.java`（新，纯 JDK） | `MAX_LIBRARY_WIRE_BYTES = 1 MiB` / `MAX_LIBRARY_HEAP_BYTES = ×8` / `MAX_LIBRARY_WIRE_KB = 1024`；判据只有一份 `fits(used, add)`，**减法形式**（加法在 `Long.MAX_VALUE` 下回绕成负数 ⇒ 误放行）；`remaining` / `usedWireKb` |
| `GrowingUnitLibrary#trySetStackInSlot`（唯一写入口） | 先判后写：`delta > 0` 且 `fits` 不过 ⇒ **返回 false、一格不动**；`setStackInSlot` 只委托它（整个类 `super.setStackInSlot` 只剩一处）；`ensureSlot` 后仍越界 ⇒ 返回 false（**不继续索引**，避免 `IndexOutOfBoundsException` 崩服）；`extractItem` 只维护缓存、不判上限；`insertItem` 空格插入先判预算、不过整张退回 |
| 体积缓存 `rscc$usedHeapBytes` | `-1` = 失效（读档 / 缩小 / 注入注册表上下文），否则增量维护。**自检随机模型抓到真 bug**：旧写法「失效时也做增量」会把 `-1 + delta` 钳成 0 = 把「不知道」当成「已用 0」⇒ 整条预算被废；现改为缓存失效时保持失效 |
| 四条写入路径 | 生成 `canAcceptUnits`（格数 + 体积，在写总样板 / 扣 RS 样板之前）／`putUnitIntoLibrary`／`pullUnitsFrom`（不过就 break，样板留在执行仓）／`CreateUnitPatternPacket`（被拒有动作栏提示）／菜单格 `mayPlace` |
| 新增文案 | `gui.…generate.library_full_bytes`（zh/en，带 KB 上限，数字随常量走） |

### 24.2 装填器：修 H1（丢蓝图）/ H2（每 tick 全量刷新 + 20 包/秒）/ M1（队列空转 + 卡死）/ M3（全服刷屏）

- **H1**：队列掌管共享蓝图槽时，`BlueprintView#setStackInSlot` 静默 return = 丢蓝图（原版 SWAP / Shift 点击先摘物）。
  修法两层：菜单 `mayPlace` 加 `isSharedBlueprintSlotQueueLocked()`（原版不 detach）；视图拒收时 `dropIntoWorld`。
- **H2**：`cannon.updateChecklist()` 从「每 tick」改成「蓝图真的换了 / 队列部署下一张」两处；
  它是 Create 最重的操作（遍历蓝图全部坐标 + 重扫邻块容器）且内部置 `sendUpdate` ⇒ 旧行为 = 20 包/秒 + 大量世界查询。
- **M1**：`takeFromQueue()` 移到「刚打完 / 队列刚启动」门控内（取出即部署）；删除死代码 `returnToQueue()`；
  新增「中途取空 ⇒ 自动退回开始 + 64 格行动栏提示」分支（队列原样保留；新语言键 `…queue_paused_empty`）。
- **M3**：「网络无火药」从全服广播改为 `notifyNearby`（64 格，与完成横幅同口径）。
- **未修（记录）**：M2 `attachRoundBlueprint` 不入档 ⇒ 读档 / 区块重载后把已拉材料全部推回网络再重拉；
  M4 每 tick 现做集群 BFS；L1–L3 文档不符（横幅兜底 / `isCannonAdjacent` 恒假分支 / 升级上限口径）。

### 24.3 保持器：修 H1（目标数量 short 截断）/ H2 的重入崩溃部分

- **H1**：MC 1.21.1 `ClientboundContainerSetDataPacket` 线上是 **short**，目标 int > 32767 被截断 ——
  流体按 mB，**> 32.7 桶（33b）就中招**：界面回填 0，点 ± 把服务端目标静默改小（开注销时按错目标销毁资源）。
  修法：目标拆「低 16 位（原 index）+ 高 16 位（**追加在末尾**的新 index）」，
  基础（`DATA_TARGET_HIGH = 9` / `DATA_COUNT = 10`）与高级（`DATA_TARGET_HIGH_BASE` 整块，后续下标顺延）都改；
  两端按无符号 16 位掩码拼回。客户端占位 `SimpleContainerData` 长度跟常量走。
- **H2（部分）**：保持器故意把网络镜像暴露给相邻方块（设计功能），RS 外部存储会把它注册成网络存储源 ⇒ 无限递归。
  新增纯 JDK `support/KeeperNetworkReadGuard`（同线程重入闸），四个网络抽取入口内层返回 0。
  **存量虚高（双计）未修**：属产品口径（是否继续暴露网络镜像），见 `docs/ROUND118_KEEPER_TARGET_SYNC.md` §4。
- **未修（记录）**：目标无服务端上限；基础版「注销默认开 + 目标 64 mB」；未标记仍认领资源；
  `QUANTITY_KEEPER_MULTI_MARK_DESIGN.md` 过期（max 口径 vs 单权威）；死代码与假注释；`drain/fill` 编码不对称；`poolFreeSpace` 溢出。

### 24.4 自检与验收

- 新增 `selfcheck_round118_unit_library_bytes.py`（**50 断言 / 6 反例**，含 `UnitLibraryLimits` 的 **22 条 JVM 探针**）；
  `selfcheck_round118_schematic_loader_fixes.py`（**30 / 12**）；
  `selfcheck_round118_keeper_target_sync.py`（**25 / 9**，含 `KeeperNetworkReadGuard` 的 **8 条 JVM 探针**）。
- 更新 `selfcheck_round116_sequence_write_budget.py` ④-10 的预检锚点（格数 → 格数 + 体积，判据没放水）。
- **全量自检：166 个脚本 / 0 失败**（用带 Pillow 的 bundled python 跑；宿主 `python` 缺 PIL 会让
  `selfcheck_advancements.py` 在 import 处报错，那是环境问题不是代码问题）。

### 24.5 未验证 / 待确认

1. **没有实机点界面、没有真机改包**：判据 = 源码锚点 + 行为模型 + JVM 探针 + 反例自证。
2. **1 MiB 体积预算、1024 格、2 KB 单条**都是估算 + 余量，没有真实整合包统计。
3. **short 截断**的依据是 MC 源码（`writeShort/readShort`），没有真机复现「33 桶 → 界面回填 0」。
4. **保持器递归 / 双计**：组合是按 RS 2.0.9 源码推导，没有实机摆外存验证；双计需用户拍板。
5. **装填器 H1 触发摆法**（基础 + 高级同时贴一台炮、彼此不相邻）是按判据推导，没有实机复现。

### 24.6 下一轮候选

- **用户对 beta.14～beta.20 的实机反馈（最高优先）**。
- 装填器 M2（`attachRoundBlueprint` 入档）/ M4（集群 BFS 缓存）/ L1–L3 文档清理。
- 保持器：目标上限口径、基础版注销默认值 + 流体目标初始化（64 mB 危险）、`claimedResources` 与 `isMarked` 同源、
  文档重写与死代码删除、`drain/fill` 编码统一、`poolFreeSpace` 饱和加法。
- `diag` 权限口径（用户此前明确要求该指令不要权限 ⇒ 更适合做冷却 / 轮转，而不是加权限）。
- 用户三项待定（36 格分页 / 数量门槛搬进区域标记界面 / 区域列表快捷键）。

---

## 25. 第 119 轮（1.2.0-beta.21）：装填器读档「假换图」+ 单元样板整叠丢张 + 两路只读复核

> 本轮**没有新的用户实机反馈**。三件事：① 清第 118 轮 §24.6 自己记的账（装填器 M2 / M4、L1–L3）；
> ② 只读复核**区域标记 / 区域选定器**家族（顺带回答「用户那句『无法单独给某一个区域标记』可能已不成立」）；
> ③ 只读复核**序列执行仓**家族运行时链路（1 高 / 3 中 / 4 低），高危在本轮修掉。

### 25.1 装填器 M2：本轮蓝图身份落盘（读档不再白跑「回流 + 重拉」）

| 改动点 | 说明 |
| --- | --- |
| 新 NBT key `AttachRoundBlueprint` | `attachRoundBlueprint.save(registries)`（ItemStack 全量含 data components；只在该身份非空时写）。选全量而不是「只存文件名 / 只存物品 id」：判据是 `isSameItemSameComponents`，存少了会在「同名但锚点/部署标记不同」的蓝图间误判同一张 |
| `loadAdditional` | `ItemStack.parseOptional(registries, tag.getCompound(...))`（仓库既有写法，非法 id 只得到 EMPTY、不抛异常）；只有 `isValidBlueprintStack` 通过才认身份，否则 `attachRoundIdentityUnknown = true` |
| `doLoaderWork` 四路判定（`:681-712`） | 槽空 ⇒ 清身份 + reset（旧行为）；身份未知（旧档/非法 key）⇒ 以槽里那张 **接管**身份、**不回流不复位**、`attachRoundComplete` 沿用存档；同图 ⇒ 什么都不做；真换图 ⇒ 回流 + reset + updateChecklist（旧行为） |
| 安全性 | 身份缓存只做比较；赋值只有 EMPTY / `parseOptional` 新对象 / `currentCannonBlueprint.copy()`，**任何 insert / setStack 都不用它**（自检 M2-5 + 反例 M2-C6 拦住复制物品的写法） |

### 25.2 装填器 M4：同 tick 只做一次集群 BFS

- 新实例字段 `clusterCache` / `clusterCacheTick`，key = `level.getGameTime()`；命中条件要求「非空 + 同 tick」，
  **跨 tick 一律不复用**（区块加载/卸载边界上邻块可能整 tick 查不到，缓存久了会把错误拓扑固化）。
- 未命中才 BFS，存 `List.copyOf(finalizeCluster(all))` —— **不可变副本**，调用方改不到缓存内部。
- 失效：`invalidateClusterCaches()` 一次清 BFS + GUI 两级缓存，调用点 `rscc$clusterChanged` / `setRemoved` / `loadAdditional`。
- `level == null`（构造期 / 客户端占位）返回 `List.of(this)` 且**不写缓存**（避免 `Long.MIN_VALUE` 哨兵撞 tick）。
- 缓存只存**成员引用**，不存库存/队列实例（集群重建换的是 `inventory` 对象，成员列表仍有效）。

### 25.3 打印完成哨兵落盘 + GUI 顺序缓存不可变（复核的顺手发现，本轮修）

- 新增落盘 `LastEmptySchematicCount`（int）/ `LastPrintingDone`（boolean）；新增瞬态 `printBaselinePrimed`：
  新档（有 key）基线可信；**旧档（无 key）首个 work tick 用当时输出槽数量重建基线、该 tick 不判「刚完成」**。
  `printJustFinished` 判据改为 `printBaselineKnown && count > lastCount`。效果：读档不再补播完成横幅，
  但**真实的**下一次打印仍正常播报。
- `guiOrderCache` 赋值改成 `java.util.List.copyOf(computeClusterInGuiOrder())`：**写入时**即不可变；
  **不在读取时拷贝**（`getClusterInGuiOrder()` 每帧按槽位反复调用，逐次拷贝会变成新热点）。

### 25.4 第 118 轮 L1 / L2 清理与 **L3 勘误**

- **L2（真死代码，已删）**：`isCannonAdjacent` 里的 `level.getBlockEntity(worldPosition) == cannon` ——
  `worldPosition` 是本机坐标，那里的方块实体恒为本装填器 ⇒ 恒假。删掉后只剩「曼哈顿距离 == 1」，行为等价。
- **L1（注释不符，已按实现改写）**：完成横幅注释原写「绝不回退到 `cannon.checklist`」，但
  `cacheRequirementsFromBlueprint` 在「解析不出材料清单」时确实会**临时**兜底成 checklist 拷贝
  （`cachedRequirementsFull == false`），之后每个 work tick 重试全量统计并在成功后升级。
- **L3（复核误报，本轮勘误）**：第 118 轮写「注释称速度 / 堆叠升级各最多 6 个，实现按槽保留 1 个」。
  实测 `menu/UpgradeSlot.MAX_PER_UPGRADE = 6`，`dropBeyondKindCap` 是 `kept >= max` 才弹出、否则 `kept++`
  ⇒ **保留前 6 个，注释是对的**。已补自检 ⑤-6 / ⑤-7 钉住「常量 6 + 保留前 max 个」，避免再被误报。

### 25.5 序列执行仓 F1（高）：单元样板「一叠」搬运静默丢 N−1 张

| 路径 | 修前 | 修后 |
| --- | --- | --- |
| 取回（`pullUnitsFrom`） | 把**整叠**写进单元样板库**一格** | 逐张：`firstEmptySlot()`（格数上限）→ `canAcceptMore`（体积预算）→ `trySetStackInSlot(empty, 单张)` → 成功才 `shrink(1)`；任一闸拒绝就 break，剩余**原样留在执行仓** |
| 存入（`pushUnitsTo`） | `copyWithCount(1)` 后把整格 `setStackInSlot(i, EMPTY)` ⇒ 丢 N−1 张 | 逐张 `extractItem(i, 1)`；**先写执行仓、再扣库**；仓满（无空格）就停在库里 |
| 可见性 | 无提示，界面按「格」计数显示 1 | 新增瞬态 `unitTransferBlockedByLibrary` + 动作栏提示 `summary.pull.blocked`；汇总界面「张数」改按 `sum(getCount())` |
| 旧档 | 同格多张无法取出 | 逐张取出（旧档不会丢） |

不进档的瞬态标志（`unitTransferBlockedByLibrary`）只用于「本 tick 是否提示」，不参与任何数量判定。

### 25.6 区域选定器 C-1 / C-2 / C-3 + 区域扫描 C-6

- **C-1（中）** 菜单路径被拒绝时补 `menu.broadcastFullState()`（**不放进** `AreaSelectorTargets#sync`，
  成功写入不跟着全量发）。修前：拒绝后一个包都不发 ⇒ 客户端乐观副本残留 ⇒ 之后配标记被静默丢包却弹「已加入」。
- **C-2（中）** `AreaSelectorScreen.targetStack()` 在 `menu != null` 时**只认专用槽、不回退主手**（与
  `AreaMarkScreen` 的 D10-f 口径一致）；`selectionStack()` 改为委托；无目标时不发注定被丢的包；
  槽空提示 `status.no_slot`（zh/en）。`AreaMarkScreen` 的加/删/清三处同样先拦。
- **C-3（低）** 手持 ADD 发包前补「维度 + 去重」预检、`resizeSelected`（Ctrl+滚轮）同源预检；
  「已提交」只在全部预检通过、真正发包之后出现；新增 `duplicate` / `dimension` 语言键（zh/en 成对）。
- **C-6（中·性能）** `CollectionCacheBlockEntity#fluidSourceIdAt` 开头加 `if (!level.isLoaded(pos)) return null;`。
  区域扫描每 tick 上千次查询、`Level#getBlockState` 对未加载区块会**同步加载** ⇒ 玩家离开后反复拉区块。
  只读探测，未加载当「没有流体」，不丢资源。同类调用点（充电器看邻块 / 执行仓看自身或邻块）**有意不动**。

### 25.7 缓存池读数求和改为饱和加法（`support/SaturatingMath`）

- `poolFreeSpace` / `poolStored` / `poolContents` / `describe` 旧实现是裸 `+=` / `Long::sum`；
  「无限容量盘」每块报 `UNLIMITED_REMAINING = Long.MAX_VALUE / 4` ⇒ **5 块以上**和变负 ⇒
  闸门 A 把「几乎无限空位」读成「池子满了」⇒ 中间产物一件都不搬（无异常、无日志）。
- 新增纯 JDK `SaturatingMath#add`：**减法判溢出**（`left > MAX - right`，加法形式会先自己回绕），
  非负输入永不回绕、负输入按 0。四处求和全部改走它；`UNLIMITED_REMAINING` 的错误注释一并改正。
- 全仓 `Long::sum` 扫描结论：其余合并的都是真实件数 / mB（不可能到 2⁶³），**不需要改**；
  唯一把哨兵大数放进求和的只有这一处。

### 25.8 两路只读复核的完整清单

**区域标记 / 区域选定器**（`docs/ROUND119_AUDIT_AREA_MARKS.md`）
- 关键事实：区域 = 物品数据组件里的 6 个绝对坐标（`support/Area.java:37-38` + 组件注册）；
  每区域过滤标记自 beta.14（第 112 轮）起已在**选定器物品**的 `CUSTOM_DATA` 上，方块实体里只剩旧档迁移缓冲。
  ⇒ 用户那句「无法单独给某一个区域标记、非得把标记放在方块里」**在这个版本上已经不成立**，
  第一件事应是确认用户跑的是哪个版本；若是「只想高亮某一个区域」，则需要 per-area 显示开关（动 `Area` 契约）。
- 已修：C-1 / C-2 / C-3 / C-6。**未修（记录）**：C-4（`AREA_LIST_SLOT_MAX=16` 与 Config 64 不一致，
  区域 17+ 的「只吸 N 种」恒显示 0 —— 界面说谎，修法要拍板分页还是抬上限）、C-5（旧格式标记按索引删除
  可能删错一条）、C-7（区域远离时并集 AABB 巨大，未 profile）。

**序列执行仓**（`docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md`）
- 已修：F1。**未修（记录）**：F2（`accept()` 的 SIMULATE 不动状态 ⇒ 一批多资源时各自模拟都过，
  EXECUTE 后半批插不进却只返回 REJECTED，而 RS 在 EXECUTE 阶段只打 warn 就当成功 ⇒ 网络近满时半批材料被销毁；
  最小修 = 同批内累计占用做模拟，彻底修 = 给样板库加缓冲，属产品行为变更）、
  F3（汇总窗口每 20 tick 自动前移，只在「手里拿着东西」时暂停）、F4（`inFlightUnits` 不落盘 ⇒
  重启后工位残留失去回收凭据，机器被占死只能手动挖）、F5（`SetStepMachinePacket.name` 无长度上限直接落盘）、
  F6–F8（`putUnit`/`takeUnit` 死代码、"容量 6"/"3×2" 注释错、`dropOutputContents` 无人调用、`writeCandidates` 连写两遍）。
- 已证伪（不要再查）：集群落盘边界不会写两份翻倍、各 C2S 下标/枚举都夹住了、
  `RsccChamberItemStorage` 不会错位抽到别的物品、各容器 `clearContent` 空实现。

### 25.9 自检与验收

- 新增 5 个脚本：`selfcheck_round119_loader_round_state.py`（**71 断言 / 17 反例**）、
  `selfcheck_round119_unit_stack_preserve.py`（**40 / 9**）、`selfcheck_round119_area_selector_sync.py`（**49 / 21**）、
  `selfcheck_round119_pool_saturate.py`（**30 / 3**，含 `SaturatingMath` 的 **17 条真代码 JVM 探针**）、
  `selfcheck_round119_area_scan_isloaded.py`（**17 / 3**）。
- **全量自检：171 个脚本 / 0 失败**（带 Pillow 的 bundled python；宿主 python 缺 paramiko 不影响自检，
  但 **SFTP 部署要用 `D:\Python313\python.exe`**，bundled python 没有 paramiko —— 本轮踩过一次）。
- `LEAD_COMPILE_OK sources=504`；专服探针 **PASS**（`Done (2.083s)`）；jar 三处 sha256 一致
  （`D:\Python313\python.exe tools\_verify_deploy_round119.py` ⇒ `DEPLOY_VERIFY_OK`）。

### 25.10 未验证

1. **没有实机点界面、没有真机读档往返**：M2 / 打印哨兵 / 区域选定器三条的行为判据都是源码锚点 + 行为模型 + 反例。
2. **没有真机摆 5 块无限盘**：饱和加法的触发面按 `Long` 溢出推导，普通整合包大概率摸不到
   （对普通盘是零风险）。
3. **没有实机复现「一叠样板丢张」与「区域跨未加载区块」**：前者按界面/容器槽的实际可堆叠数推导，
   后者依赖 `Level#getBlockState` 对未加载区块会同步加载这一（工程内已有注释支持的）事实。
4. **没有 profiler 数据**量化 M4 / C-6 的收益；「12 次 → 1 次」是模型口径，不代表真实调用数恒为 12。
5. 没有做故障注入（`extractItem` 异常回滚、写库中途失败）与多人同菜单 / 改包 / 旧客户端场景。

### 25.11 下一轮候选

- **用户对 beta.14～beta.21 的实机反馈（最高优先）**。
- 序列执行仓：F2（SIMULATE 累计占用，先做「一条都插不进去就整批不插」的最小修）/ F4（`inFlightUnits` 落盘）/
  F3 / F5 / F6–F8；以及 F1 子代理顺手报的 `pullUnitsFrom` 不筛 `isUnitPattern`（旧档总样板会被搬进单元库）。
- 保持器：目标上限口径、基础版注销默认值 + 流体目标初始化（64 mB 危险）、`claimedResources` 与 `isMarked` 同源、
  文档重写与死代码、`drain/fill` 编码统一（`poolFreeSpace` 饱和加法**本轮已修**）。
- 区域标记：C-4（分页 vs 抬上限，需拍板）、C-5（按内容删除而不是按下标）、per-area 显示开关（若用户确认是「只想高亮一个」）。
- `diag` 权限口径（用户此前明确要求该指令不要权限 ⇒ 更适合做冷却 / 轮转）。
- 用户三项待定（36 格分页 / 数量门槛搬进区域标记界面 / 区域列表快捷键）。

## 26. 第 120 轮（1.2.0-beta.22）：清第 119 轮两路只读复核的账（F2 / F3 / F5–F8 / C-5）

> 本轮**没有新的实机反馈**。第 119 轮两路只读复核（`docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md`、
> `docs/ROUND119_AUDIT_AREA_MARKS.md`）把 1 高 / 2 中 / 4 低（序列家族）与 2 中 / 5 低（区域家族）记成账，
> 其中高危与两条中已在上轮修掉，本轮把「留到下一轮」的 7 条清完，并顺带修掉 3 条同源小偏差。
> **不改产品行为、不改玩法语义**。逐条细节见 `docs/ROUND120_SEQ_AUDIT_DEBT.md` /
> `docs/ROUND120_SUMMARY_WINDOW.md` / `docs/ROUND120_AREA_MARK_REGISTRY.md`。

### 26.1 F2：外部样板 sink 的「整批原子性」（中）

- 位置：`block/entity/SequenceAssemblyExecutorBlockEntity#accept`（`:194-240`）。
- **RS 2.0.9 的真实语义**（`ExternalTaskPattern#acceptsIterationInputs`，源码在
  `local_src/rs_src/.../ExternalTaskPattern.java:184-215`）：先对 sinks 调 `accept(..., SIMULATE)` 选 sink；
  选中后先 `extractAll(iterationInputs, internalStorage, EXECUTE)` **把输入从任务内部暂存扣掉**，
  再调 `accept(..., EXECUTE)`；后者非 ACCEPTED **只打一条 warn 就当成功**（源码原话
  *"we lie, do as if the insertion was successful, and potentially void the extracted resources"*）。
  ⇒ **`REJECTED` 不会让任务重试**，旧 javadoc 的「让任务稍后再试」是错的。
- 旧实现：逐条 `insert`，一条放不下就 REJECTED ⇒ 前面已插进去的不回滚、后面的被 RS 吞掉。
- 新实现：① 按 `ResourceKey` **聚合**总量（同一批里同一种资源出现多次时，逐条 SIMULATE 各自都看到
  「未变的网络状态」而全过 —— 这是旧实现放行的根因）；② 按聚合总量**全量 SIMULATE**，任何一条不满
  ⇒ **一条都不插** + REJECTED；③ 全过才 EXECUTE（写不满**不 break**，继续抢救后续资源、诚实 REJECTED）；
  ④ `amount <= 0` 直接跳过（旧 `Math.max(1, …)` 会凭空插 1 件）。
- **如实边界（本轮没修，别当已修）**：(b) 同一类存储里**多种不同资源合计**超容（各自模拟都能过）；
  (c) SIMULATE 与 EXECUTE 之间空间被抢。`StorageNetworkComponent` 没有「预留容量 / 事务」API，
  这两种只能「不劣于旧实现」。真正「绝不丢」需要执行仓自建上游缓冲（`accept` 只写缓冲、由 tick 慢慢
  转进网络，缓冲要落盘 + 破坏掉落）—— **产品行为变更，等用户拍板**。
- **勘误**：`docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md` §F2 的示例推理有误 ——
  「64 件空间 + 64 物品 + 500 mB 流体 ⇒ 两次模拟都通过」只在物品/流体**各自都有空间**时成立
  （RS 的物品存储与流体存储是两套独立存储）。真实损失形状是上面的 (a)(b)(c)；
  (a) 本轮彻底堵死。**下轮不要按那个错误示例推理**。

### 26.2 F3：汇总窗口「看着 A 点到 B」（中·界面）

- 位置：`menu/ChamberUnitsSummaryMenu.java`（513 → 774 行）；`client/screen/ChamberUnitsSummaryScreen.java` **零改动**。
- 旧行为：窗口每 20 tick 把 `rowStart[row]++`，**只在 `getCarried()` 非空时暂停** ⇒ 盯住某格看一秒再点，
  点到的是轮转后的相邻样板；`WindowContainer#setItem` 还会先 `rebuildWindow()` 再按新映射写，
  取用/写回各用一份映射 ⇒「取出来又放回去」落到另一个单元槽。
- 新行为（方案 A + 安全窗，**不做**手动翻页 B/C）：
  1. `INTERACT_QUIET_TICKS = 8`：玩家最近 8 tick 内点过本行 ⇒ 该行暂停轮转（`shouldAdvanceRow` 纯函数）；
  2. `ROTATE_SETTLE_TICKS = 4`：本行刚轮转完 4 tick 内，该行窗口格上的点击**不执行**并
     `broadcastFullState()` 全量重同步（`clicked` 里，QUICK_CRAFT 额外 `resetQuickCraft()`）——
     **宁可不响应一次点击，也绝不换给玩家另一张样板**；不选「按轮转前映射执行」，
     因为服务端无法区分玩家看到的是轮转前还是轮转后（原版 1.21.1 对 `stateId` 落后的点击
     **只纠正不拒绝**，`ServerGamePacketListenerImpl#handleContainerClick` 反编译确认）；
  3. 一次点击一份**映射快照**（`beginClickSnapshot` / `endClickSnapshot`，`clickDepth` 处理
     `clicked → quickMoveStack` 嵌套，逐行 `arraycopy` 深拷贝）；
  4. 取走一格留「洞」（`rowHoles`），放回去落回**原槽**；轮转 / 换页 / 换行时清洞。
- 顺带修的同源偏差：`windowIndex` 旧 `floorMod` 在样板 < 4 张时「一张显示四遍 / 两张两两重复」；
  轮转判据旧为 `rowSlotCount > 1`，与 `DESIGN_DECISIONS_ROUND4 §7.3`「多于 4 张才轮询」不符。
- **残余风险**：单程延迟 > 2 tick（RTT > 200 ms）时安全窗仍可能不够长（理论上 `2×单程 − 4 tick` 的
  错样板窗口）；彻底消除要协议层回执（方案 B 强化版），本轮未做。
- 既有偏差（未改）：`pollCounter` 按 `broadcastChanges()` **调用次数**计，原版每次点击后会再调一次
  ⇒ 频繁点击时轮转周期略短于 1 秒；要严格 1 s 得改用游戏时间。

### 26.3 C-5：客户端读标记不带注册表 ⇒ 下标错位 ⇒ REMOVE 删错条（低）

- 位置：`client/screen/AreaMarkScreen.java`（新增 `clientRegistries()` / `clientMarks(...)`，`marks()` 成唯一入口）、
  `support/AreaMarks.java`（两参 `get` 的 javadoc 警告）、`network/AreaMarkEditPacket.java`（下标契约记录）。
- 旧行为：旧档里还有「整份 `ItemStack`」编码的标记，`decodeMarkerTag` 在 `registries == null` 时
  **读不出 → 丢弃**；服务端带 `player.registryAccess()`、客户端走两参重载 ⇒ 两边列表长度/顺序不同，
  而移除发的是**下标** ⇒ 删到服务端列表里同下标的**另一条**。在 288 条上限处还可能
  「长度相同、内容整体前移」，比「列表变短」更难肉眼发现。
- 新行为：客户端统一带 `ClientLevel#registryAccess()` 读；**拿不到注册表时返回空表、绝不回退**
  （不可点 ⇒ 不可能删错）。辅助方法放在 **client 类**而不是 `AreaMarks`（工程约定：support 通用类
  出现 `net.minecraft.client.*` 会让专用服务端在链接期崩溃，见 `support/JetpackHudState.java:8`）。
- 放弃「按内容指纹删除」：需给 record 加字段 ⇒ 改 `StreamCodec` 线格式（旧客户端连新服务端整包错位）；
  根因已消，写成后续建议。未改的两处客户端读取：`client/screen/AreaSelectorScreen.java:826`
  （只画行图标、不发下标）、`item/AreaSelectorItem.java:59`（tooltip 计数走两参 `all`，只影响显示）。

### 26.4 F5–F8：名字信任边界与注释/死代码（低）

- **F5** `network/SetStepMachinePacket.java` + `SequencePatternTerminalBlockEntity#setStepMachine`：
  服务端按 `pos` 找到执行仓、**直接用它的显示名**，客户端 name 只作兜底；落盘统一按码点夹到 256
  （复用 `MAX_IMPORT_STRING_LENGTH`）。解绑 / 空名语义与诚实客户端可见文本零变化。
- **F6** 删死代码 `putUnit` / `takeUnit`（全仓零调用、非覆写、无反射/mixin 引用）；三处错注释改成实际值
  （**54 格 = 6×9**；类型限制只在菜单 / 容器包装层；右键**无条件**开 GUI）。
- **F7** `dropOutputContents()` → `rscc$legacyDropOutputContents` + `@Deprecated` + 「已被
  `BlockContentReleaser.release` 取代、禁止调用（接回 = 双重结算 / 绕过集群移交）」。
- **F8** 终端 `saveAdditional` 里重复写的 `writeCandidates` 删掉一处，只留唯一写入点。

### 26.5 本轮新发现（只记录，未改）

1. `SetChamberBindingPacket.name`（执行仓自定义名）同样**无长度上限**直接落盘，且它正是 F5 里
   服务端显示名的来源 ⇒ 执行仓自己的 NBT 仍可存超长名字（下轮按 `MAX_IMPORT_STRING_LENGTH` 收口）。
2. `tools/simplify_lang_texts.py:36` 的硬编码文案仍写「右键放入/取出单元样板（6 格）」
   （当前语言文件已是「存取单元样板（54 格）」）—— 谁再跑那个脚本会把错误文案写回 lang。
3. 原版 1.21.1 对 `stateId` 落后的容器点击**只纠正不拒绝**（反编译确认）⇒ 任何「滑动窗口 + 点击」
   界面都必须服务端自己设闸（同一范式若出现在别的界面，可套本轮「交互时间戳 + 安全窗 + 快照」）。
4. 旧档同格多张单元样板（`stacksTo(64)`）在窗口格点击仍是「一格一张」的 Slot 语义，
   `removeItem` 可能只拿走一部分 ⇒ 该槽不会变空 ⇒ 不留洞（行为正确，观感未验证）。

### 26.6 自检与验收

- 新增 3 个脚本：`selfcheck_round120_seq_audit_debt.py`（**53 断言 / 16 反例**）、
  `selfcheck_round120_summary_window.py`（**58 / 15**）、`selfcheck_round120_area_mark_registry.py`（**42 / 12**）。
- 更新 2 处**字面量锚点**（实现形态变了，判据原意未放宽）：`tools/selfcheck_round37_pattern_kind_gates.py`
  （`ChamberUnitsSummaryMenu` 的 `isUnitPattern` 分支写法）、`tools/selfcheck_round112_area_marks.py` ⑥-4
  （`AreaMarks.get` 两参 → 三参）。
- **全量自检：174 个脚本 / 0 失败**（带 Pillow 的 bundled python 跑 `tools/_run_all_selfchecks.py`；
  **SFTP 部署要用 `D:\Python313\python.exe`**，bundled python 没有 paramiko）。
- `LEAD_COMPILE_OK sources=504`；专服探针 **PASS**（`Done (1.681s)`）；jar 三处 sha256 一致
  （`D:\Python313\python.exe tools\_verify_deploy_round120.py` ⇒ `DEPLOY_VERIFY_OK`）。

### 26.7 未验证

1. **没有进游戏点过界面**：汇总窗口的「安全窗内点击无反应」「洞留下的空位」观感、区域标记列表
   在旧档下的实际一致性，都只有源码锚点 + 行为模型 + 反例自证。
2. **没有真机把网络填到快满再让产线迭代**：F2 的 (a) 形状是模型推演（自检 ②-7b 显式断言了 (b)(c)
   仍可能发生），触发频率没有数据。
3. **没有改包伪造 `SetStepMachinePacket`**，也没有旧客户端 / 旧存档兼容性实机验证
   （C-5 的旧格式条目在真实存档里是否还存在**未知** ⇒ 该 bug 可能本就不在现有档上可达）。
4. **没有多人 / 高延迟**：F3 的残余窗口按 RTT > 200 ms 推导，未测。

### 26.8 下一轮候选

- **用户对 beta.14～beta.22 的实机反馈（最高优先）**；以及「实机跑的是哪个版本」这一澄清。
- 序列执行仓：**F4**（`inFlightUnits` 落盘；或至少读档后对「工位压着本仓类别内物品且无在制件」
  做一次性可见播报，而不是静默不管）；F2 真修法（上游缓冲，需拍板）；`SetChamberBindingPacket.name` 收口。
- 区域标记：**C-4**（分页 vs 抬上限，需拍板）/ **C-7**（实体查询并集盒聚类）/ C-5 后续（按内容删除、
  `AreaSelectorItem` tooltip 计数）；以及用户长期未澄清的「只高亮某一个区域」（需 per-area 显示开关，
  动 `Area` 数据契约，必须先确认）。
- 汇总窗口：`pollCounter` 改游戏时间；方案 B 强化版（协议层回执）消除高延迟残余窗口。
- 保持器三项产品口径、`/rs_create_compat diag` 权限口径、`tools/simplify_lang_texts.py` 过时文案。
