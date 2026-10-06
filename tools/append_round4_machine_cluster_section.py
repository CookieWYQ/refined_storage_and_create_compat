# -*- coding: utf-8 -*-
"""把本轮的「同类型机器相邻 = 一个整体（容量叠加 + 内容共享）」章节**幂等**追加进
docs/DESIGN_DECISIONS_ROUND4.md。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过」的方式追加，重复执行结果一致。

用法：
    python tools/append_round4_machine_cluster_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 机器集群：同类型机器相邻 = 一个整体（容量叠加 + 内容共享）（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户需求原话「同一个整体的容量是叠加的内存，就是里面的资源内容是共享的」
> 「同类型机器相邻可合并。例：27 格机器 + 同款 27 格机器 = 视为整体 54 格容器，内含一个水桶」。
> 硬规则不变：服务端权威；**绝不销毁玩家资源**（读档 / 拆集群 / 拆方块三条路径都要保证）。

### 1. 适用范围清单（实际 grep 代码后的结论）

**范围内（本轮已实现集群）**

| 机器 | 方块 | 内部资源存储 | 单机容量 | 共享后的整体容量 |
| --- | --- | --- | --- | --- |
| 归流缓存仓 | `CollectionCacheBlock` | 物品缓存 `SimpleContainer` + 流体/气体缓存 `MultiFluidCache` | 256 格 / 512000 mB | 台数 × 256 格 / 台数 × 512000 mB |
| 序列执行舱 | `SequenceExecutionChamberBlock` | 输出物品 `RsccUnboundedItemStorage` + 输出流体 `RsccUnboundedFluidStorage` | `autocrafterOutputSlots × 64` / `autocrafterFluidCapacity` | 台数 × 单机量 |

两台的「容量叠加」都是**乘法**（台数 × 单机容量），与用户举例一致（27 + 27 = 54）。

**范围内但本轮未接（框架已就绪，接一台只需实现 `RsccClusterable` 的 11 个方法 + 加一行注册）**

| 机器 | 为什么不接（不是漏掉，是语义冲突/风险） |
| --- | --- |
| 定量保持器（基础）`QuantityKeeperBlockEntity` | 物品存储 `ItemStackHandler` 的 `isItemValid` / `getStackLimit` **闭包绑定本机标记的那一种资源**（`matchesItemMarker`），两台标记不同的保持器合并会把「只能装 A」的机器与「只能装 B」的机器混成一个池；另外它的容量语义是「保持在 N 个」而不是容器格数。要接必须先加「标记一致才可合并」的兼容判定（`RsccClusterable#rscc$clusterCompatible` 已预留）与共享 `onContentsChanged` 回调（否则 follower 的 blocked 状态不刷新）。 |
| 高级定量保持器 `AdvancedQuantityKeeperBlockEntity` | 同上，且它是 `ItemStackHandler[SLOT_COUNT]` / `MultiFluidCache[SLOT_COUNT]` **每标记位一份存储**，合并粒度需要重新定义（按槽位合并 = 语义未定）。 |
| 装填器 / 高级装填器 `SchematicLoaderBlockEntity` / `AdvancedSchematicLoaderBlockEntity` | 内部是「蓝图槽 + 队列 + 升级槽」的**作业状态**（作业进度、排队顺序），不是可叠加的资源池；合并会让两台机器共用同一条作业队列，行为无法定义。 |
| 序列装配执行器 `SequenceAssemblyExecutorBlockEntity` | 内部只有 9 个样品/样板槽（配置，非资源池）。 |
| 序列样板终端 `SequencePatternTerminalBlockEntity` | 全部槽位是**玩家交互槽**（输入 / 结果 / 废料 / 样板），界面逐槽位绑定，合并会改变玩家手动摆放的语义。 |
| 范围充电器 `RangeChargerBlockEntity` | 只有 6 个升级槽，无资源存储。 |

**不在范围内（本轮不做，需 Mixin 别人的方块）**：RS 原版方块 —— 自动合成仓 `AutocrafterBlockEntity`、
样板库 `PatternGridBlockEntity`、磁盘驱动器 / 外部存储总线等。它们不是本模组注册的方块，
要实现「相邻即合并」必须对 RS 的方块实体做 Mixin 改写其存储字段，风险与回归面都远大于本轮，
故本轮明确不做（**在此点明，避免误以为已覆盖**）。

**本轮明确不碰**（其它并行分支在改）：NBT 标记相关文件、回流总线（`SequenceReturnBusBlock(Entity)`）、
单元样板管理舱（`UnitPatternManagerBlock(Entity)`）、扳手分离线缆相关文件。
（回流总线本体也是「带资源存储的机器」，但硬规则要求本轮不动它。）

### 2. 集群 / 主控 / 委托模型（唯一实现：`support/RsccMachineCluster.java`）

```
集群 = 同类型方块的 6 向连通分量（只统计已加载坐标，不经过线缆）
主控 = 成员里坐标字典序最小者（x → y → z，确定性，与遍历顺序无关）
唯一一份存储 = 主控建出「容量 = 台数 × 单机容量」的载荷，全部成员采纳同一个对象
```

关键代码（管理器核心）：

```java
final Object shared = master.rscc$clusterNewPayload(members.size());   // 容量叠加：台数 × 单机
if (oldPayload != null && oldPayload != shared) drain(master, oldPayload, shared, ...);  // 旧内容搬进新共享
for (RsccClusterable m : members) { m.rscc$clusterAdopt(shared); m.rscc$clusterSetOwnsPayload(m == master); }
```

关键代码（机器侧：把「缓存字段」指向共享对象，**看起来就是同一个容器**）：

```java
@Override public void rscc$clusterAdopt(final Object shared) {
    if (!(shared instanceof ClusterPayload payload) || payload.items() == cache) return;
    cache = payload.items();  fluidCache = payload.fluids();  setChanged();
}
```

* **不是「各存各的再同步」**：所有成员的两个存储字段指向**同一个对象实例**，
  因此往任意一台里存，另外几台的 `getCache()/getFluidCache()` 立刻看到同一份内容（零同步逻辑）。
* **GUI 打开 follower 也显示整体**：菜单的槽位读取走 `block.getCache()`（每次点击/绘制实时解析字段，
  不缓存容器引用），因此 follower 的界面天然显示合并后的那一份；页数上限改成按
  `block.getCache().getContainerSize()` 计算（`CollectionCacheMenu` 数据槽 18），
  于是**格数 = 台数 × 256** 时滚动条页数正确、能看到全部内容。
  流体侧同理（`getFluidCache()` 就是那一份，容量与已用量一起变大）。
* **逐槽位绑定的机器怎么办**：如果一个机器的界面是「构造菜单时把容器引用存进 Slot」的写法，
  字段换对象后旧 Slot 会指向旧容器。本轮涉及的两台都不属于这种情况（都是每帧/每点击解析）；
  后续若接入这类机器，需要在 `rscc$clusterAdopt` 里一并重建菜单槽位（已在本节留档）。

### 3. 主控变更与数据移交（怎么保证不丢）

| 场景 | 处理 | 为什么不丢/不翻倍 |
| --- | --- | --- |
| 新机器贴上集群 | 主控建**更大**的新共享，把旧共享内容搬（**移动**不是复制）进去，全员采纳 | 容量只增不减，内容全部装得下 |
| 主控方块被破坏 | `setRemoved` 里：主控建「单机容量」的新存储，把旧共享**搬空**到自己头上；幸存者随后重建为「等容量但为空」；`release` 前再调用 `handOverToSurvivors` 把内容交给幸存集群 | 任何时刻内容只在一处：先随主控走，破坏时再移交；搬空旧共享 ⇒ 幸存者重建时拿到的是空对象（不会与主控已落盘的那份重复） |
| 普通成员被破坏 | 内容留在集群（旧共享 → 新共享）；该成员拿一份空的单机存储 | 破坏结算只会落「缩容装不下的那点余量」 |
| 主控 / 成员所在区块卸载 | 与破坏同样的拆集群逻辑（`setRemoved` 统一入口），**不额外移交** | 见 §5 |
| 读档 | 每台成员先各自读自己的载荷（follower 的 NBT 里没有内容 = 空），随后集群成型时**统一汇总**（主控 + 各新成员的载荷一起并入新共享） | 汇总用「移动」语义，且落盘只有主控写内容 ⇒ 不会出现同一份内容被读两次 |

### 4. 拆分时的资源分配规则

1. **离场的是普通成员**：资源**全部留在原集群**（新共享按新台数缩容，内容按坐标顺序装箱），
   缩容装不下的余量交给**离场成员**（它随后由破坏结算 / 区块落盘带走）。
2. **离场的是主控**：资源**全部跟着主控走**（它缩容到单机容量）；
   装不下的余量交给仍在加载的成员；若成员也装不下，落回世界（掉落物 / 装桶），**绝不销毁**。
3. 「装不下」在容量叠加语义下几乎不可能出现（拆集群前后的总容量守恒），
   仅当同款机器放了不同数量的堆叠升级（每格上限不同）时可能出现，此时按上面 §1/§2 的兜底落世界。

### 5. 持久化边界（谁落盘）

* **只有主控把共享内容写进 NBT**（`clusterOwnsPayload`），follower 写**空载荷**：
  `saveAdditional` 里用 `if (clusterOwnsPayload)` 包住「缓存 / 流体」两段。
* follower 不写任何「指向主控」的额外字段：集群由「同类型方块 6 向相邻 + 全部已加载」**可重建**，
  坐标本身就是身份，指针反而会在主控换位后失效。
* 方块物品 NBT 也守同一条边界：`saveStateForItem()` 在 `clusterSize > 1` 时**不写流体**
  （内容属于「整体」，塞进单个方块物品会在重新放下时被再次并入 ⇒ 流体翻倍）；
  破坏路径由 `BlockContentReleaser.release` 先 `handOverToSurvivors` 再结算。

### 6. 跨区块策略与理由（本轮选定）

**策略：允许跨区块合并，但只以「已加载部分」为准，且内容跟着当时持有它的一方走。**

* 集群永远只在**已加载**的连通分量上成立；未加载的成员暂时不算入（不参与容量、不显示内容）。
* 成员离开加载范围时：**主控离开 ⇒ 内容随主控走**（主控缩容到单机容量，幸存者拿到一份「等容量但为空」
  的共享存储继续工作）；**普通成员离开 ⇒ 内容留在集群**（它拿一份空的独立存储），
  缩容余量交给离场者。

**为什么这么做（权衡）**：区块卸载时「先落盘再 `setRemoved`，还是先 `setRemoved` 再落盘」
在平台侧没有对调用方可见的保证。若采用「卸载时把内容交给仍在加载的 master」，
一旦实际时序是「先落盘」→ 卸载方已经把内容写进区块 NBT，接收方又拿着同一份内容再写一次，
**同一个集群的内容会在两个区块里各存一份，读档汇总时翻倍**（最严重的复制事故）。
而「内容只跟着当时持有它的一方走」在两种时序下都成立：卸载方带走并落盘、幸存方从空开始，
两份内容**互不相交**，重新加载时汇总只会得到正确总量。
**代价**：跨区块的集群在部分区块未加载时，未加载部分的内容暂时不可见（但绝不丢失），
容量也只按已加载台数计算 —— 这是「以已加载部分为准」的必然结果，已在本节留档。

### 7. 性能模型（不每 tick BFS）

1. **事件驱动失效**：`support/RsccClusterInvalidation` 监听 `BlockEvent.NeighborNotifyEvent`
   （放置 / 破坏 / 被替换都会走它），只**读方块状态**判定「变化点或其邻块是不是本模组的可成集群机器」，
   是才作废「该点及其 6 邻块所属集群」的缓存 —— 绝不触达方块实体（不强制加载、不会 A→B→A 递归）。
2. **缓存命中即返回**：成员方块实体在 `doWork()`（Mixin `AbstractBaseNetworkNodeContainerBlockEntityMixin`
   注入）里调用 `RsccMachineCluster.ensure(this)`；命中缓存时每 tick 只花**一次 Map 查询**，
   每 20 tick 才做一次「成员存活校验」（每个成员一次 `isLoaded` + 一次方块比较）。
3. **搜索只读方块状态**：BFS 穿行阶段绝不取方块实体；只有确认是同类型方块后，
   才用 `getBlockEntity(pos, CHECK)`（**已存在才返回，绝不创建**）取成员。
4. **规模上限** `MAX_MEMBERS = 64`；主控确定性（字典序最小）⇒ 重算结果与遍历顺序无关。
5. 与既有做法一致：`RsccSearchGuard` 重入守卫不参与（集群解析是纯读 + 幂等重算），
   失效信号复用本项目 `RsccBusLinkInvalidation` 的同一套思路（事件 + 廉价预检）。

### 8. 与「缓存满不吞资源」的一致性

容量叠加只改「存储挂在哪一份上」，不改任何搬运语义：`support/SafeCollect` 的
「先 SIMULATE 算能收多少 → 只按实际能收的量抽取 → 不足回滚」继续成立。
叠加后 **满 = 整体满**：`getRemainingCapacity()` / 流体 `getFreeSpace()` 直接来自那份共享存储，
因此整体一满，两台机器都会立刻停止收集（不会各收各的、也不会吞）。
`tools/audit_resource_swallow.py` 保持 0 未处理可疑点（1 条落世界兜底已按脚本约定写明理由豁免）。

### 9. 气体结论

工程内**没有独立的 gas 概念**：气体统一走 NeoForge 的流体通道
（`MultiFluidCache` / `RsccUnboundedFluidStorage` / `IFluidHandler`）。
因此「气体共享 + 叠加」与流体完全同一条路径，本轮已经覆盖，**没有凭空新增任何气体 API**。

### 10. 本轮修改文件

`support/RsccClusterable.java`（新增，机器侧协议）、`support/RsccMachineCluster.java`（新增，集群解析 /
主控选举 / 载荷汇总 / 拆集群）、`support/RsccClusterInvalidation.java`（新增，事件驱动失效）、
`support/BlockContentReleaser.java`（破坏前先移交共享内容）、
`block/entity/CollectionCacheBlockEntity.java`、`block/entity/SequenceExecutionChamberBlockEntity.java`、
`menu/CollectionCacheMenu.java`（缓存区页数按实际格数）、
`mixin/network/AbstractBaseNetworkNodeContainerBlockEntityMixin.java`（tick 里解析集群）、
`RS_Create_Compat.java`（注册失效监听）、本文件。

**刻意未动**：NBT 标记相关文件、回流总线、单元样板管理舱、扳手分离线缆相关文件；RS 原版方块。
"""


def main():
    with open(DOC, "r", encoding="utf-8") as handle:
        text = handle.read()
    if MARK in text:
        print("[跳过] 章节已存在: %s" % MARK)
        return 0
    with open(DOC, "a", encoding="utf-8") as handle:
        handle.write(SECTION)
    print("[追加] %s（+%d 字符）" % (os.path.relpath(DOC, ROOT), len(SECTION)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
