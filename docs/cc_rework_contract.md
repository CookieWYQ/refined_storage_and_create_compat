# 归流缓存仓重构 —— 并行开发接口契约（冻结版）

> 本文件用于 3 个并行子 agent 对齐接口。**任何一方都不得修改本文件**；如需变更，在回报里提出。
> 原则：**按文件独占切分**，三方不得越界改别人的文件。

## 0. 三条硬规则（全工程适用）

1. 精灵坐标 → Menu 坐标 = x、y 均 +1。
2. 所有 `drawString` 传 `false`（无阴影）；自绘元素 tooltip 必须手动渲染，hover 判定与绘制范围严格一致。
3. **三个 agent 都不要运行编译器**（并发写同一输出目录会互相破坏）。只做静态自检，由主代理统一编译。

## 1. 文件归属（越界即冲突）

### Agent A —— 后端：存储 / 吸取 / 开关 / 升级 / 网络包 / Menu 数据桥

独占：

- `block/entity/CollectionCacheBlockEntity.java`

- `menu/CollectionCacheMenu.java`

- `block/entity/SequenceAssemblyExecutorBlockEntity.java`（自动合成仓种类无上限）

- `block/entity/SequenceExecutionChamberBlockEntity.java`（同上，若涉及内部存储）

- `network/SetCollectionAbsorbTogglePacket.java`（新增）

- `network/SetCollectionMarkerConfigPacket.java`（扩展）

- `network/SyncCollectionMarkersPacket.java`（扩展）

- `network/SetCollectionScrollPacket.java`（仅当需要）

- `RS_Create_Compat.java`（**只允许新增包注册与方块实体/物品注册**，不得改动其它区域）

- `support/` 下新增的存储/资源抽象类

禁止：任何 `client/**`、语言 json。

### Agent B —— 前端：界面 / 控件 / Ghost 槽 / 语言

独占：

- `client/screen/CollectionCacheScreen.java`

- `client/screen/CollectionMarkerConfigScreen.java`

- `client/screen/QuantityKeeperScreen.java`、`menu/QuantityKeeperMenu.java`（Ghost 槽支持流体/气体拖入）

- `client/widget/**`（按需新增控件）

- `src/main/resources/assets/rs_create_compat/lang/zh_cn.json` 与 `en_us.json`（**只有 B 可以改语言文件**）

- `tools/patch_lang_*.py`（B 自行新增脚本）

禁止：任何 `block/entity/**`、`network/**`、`RS_Create_Compat.java`。

### Agent C —— 自动合成仓 + 可选依赖 + GUI 重叠审计

独占：

- `mixin/AutocrafterStorageMixin.java`、`support/RsccAutocrafterStorage.java`、`network/SetAutocrafterStoragePacket.java`（种类无上限相关）

- `support/` 下新增的**可选依赖探测**类（附魔工业 / 机械动力经验物品）

- 其余**非 B 独占**的 Screen 的“重叠”修复：`client/screen/SequencePatternTerminalScreen.java`、`SequenceExecutionChamberScreen.java`、`SchematicLoaderScreen.java`、`AdvancedSchematicLoaderScreen.java`、`RangeChargerScreen.java`、`SequenceReturnBusScreen.java`

禁止：`CollectionCache*` 系列、`client/widget/**`、语言 json（需要新键就写进回报，让 B 加）。

> ⚠️ C 与 A 都可能想改 `SequenceExecutionChamberBlockEntity`：**归 A**。C 只在回报里提需求。

## 2. 冻结的接口（B 按此调用，A 按此实现）

### 2.1 吸取开关

```java
public enum AbsorbType { ITEM, FLUID, GAS, EXPERIENCE }   // 放在 support 包，A 实现
boolean isAbsorbEnabled(AbsorbType type);
void setAbsorbEnabled(AbsorbType type, boolean enabled);   // NBT 持久化 + setChanged()
```

- 对应 C2S 包（A 实现）：`SetCollectionAbsorbTogglePacket(int containerId, AbsorbType type, boolean enabled)`

- ContainerData 同步：`DATA_ABSORB_ITEM / _FLUID / _GAS / _EXP`（0/1），索引由 A 在 Menu 中定义并**在回报里公布**。

### 2.2 容量与资源

| 项     | 值                                   |
| ----- | ----------------------------------- |
| 物品    | 256 格 × 每格 64 = **16384**           |
| 流体/气体 | 总容量 **512000 mB**（= 512B），**种类无上限** |
| 匹配    | 物品与流体都支持 `数量 + NBT + tag` 三项匹配开关    |

- 升级：**只保留 速度升级 与 堆叠升级**；速度升级同时影响「吸取速度」与「缓存→网络回流速度」。

### 2.3 标记（Ghost）条目（A 定义数据结构，B 负责渲染与配置界面）

```java
record MarkerEntry(boolean fluid, net.minecraft.resources.ResourceLocation id,
                   net.minecraft.nbt.CompoundTag nbt, long amount,
                   boolean matchNbt, boolean matchTag) {}
```

- 沿用现有 `SyncCollectionMarkersPacket`（扩展 fluid/nbt/matchNbt/matchTag 字段）与 `SetCollectionMarkerConfigPacket`（扩展同样字段）。

### 2.4 吸取来源（A 实现逻辑；C 提供可选依赖探测）

- 掉落物：`AbsorbType.ITEM`

- 世界流体方块：一格 = **1000 mB**（1B），`AbsorbType.FLUID`

  - **只认「纯流体方块」的 source 状态**：方块状态里除 `LEVEL` 之外没有任何其它属性、且没有方块实体
    （原版水 / 岩浆与模组流体方块都满足；含水的楼梯 / 台阶 / 炼药锅一律排除，避免抹掉玩家建筑）；
    `FluidState#isSource()` 为 false 的 flowing / falling 一律不计。
  - 移除的是**源头方块本身**；水不做「无限水」判断，直接按格收。
  - **标记里的「数量」在这条路径上是「每轮最多抽走多少 mB」的上限，不是「凑够才动手」的门槛**
    （拿整片水域的总量当门槛会导致一格都收不走）；容量不足即停，方块留在世界，绝不丢流体。
  - 首次成功抽走时会打一条 `INFO` 日志（含 slot / 流体 / 半径 / 源方块数 / 本轮格数 / 缓存余量），
    未抽到时每 60 tick 打一条 `DEBUG`（含命中数与开关状态），便于排查「没命中 / 缓存满 / 开关没开」。

- 气体：仅当可选依赖（Mekanism）存在时启用，`AbsorbType.GAS`

- 经验：`AbsorbType.EXPERIENCE`

  - 装了附魔工业 → 可用其**液态经验（流体）或**经验颗粒（物品），由玩家在 GUI 选择

  - 没装附魔工业 → 回退**机械动力原版经验颗粒（物品）**

  - **任何情况下都不要出现「未安装 XX 所以无法吸取经验」这类提示**

### 2.5 数据流

吸取 → **先进入缓存** → 由速度升级控制节流 → 再 `insert` 进网络 `StorageNetworkComponent`。缓存本身不作为网络存储暴露。

## 3. 回报要求（三个 agent 统一）

- 你改了哪些文件（必须落在你的独占清单内）；

- 你**新定义/新依赖**的公开方法、字段、ContainerData 索引、包名（供另两方对齐）；

- 你需要别人做的事（若有）；

- 你**没有**编译（因为并发），以及你为自检做了什么。

