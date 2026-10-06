# -*- coding: utf-8 -*-
"""把本轮的「机械动力扳手分离 RS 线缆」章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过」的方式追加，重复执行结果一致。

用法：
    python tools/append_round4_wrench_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 机械动力扳手分离 RS 线缆：接缝 / 面 两种方式 + 配置切换（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户本轮需求「还是做扳手断开吧」+「两种方式都做，在配置文件中切换」。
> 硬规则不变：服务端权威、**绝不破方块 / 绝不丢方块内数据**、`drawString` 一律 `false`、
> 精灵坐标 → Menu 坐标 **x、y 均 +1**、语言键走 `tools/lang_frag_wrench.json` + `apply_lang_frag.py`。

### 1. RS 线缆「连接」是怎么判定的（查到的真实类名 / 方法，全部核对过 RS 官方源码 jar）

源码来源：`~/.gradle/caches/.../refinedstorage-neoforge-2.0.0-sources.jar`
（字段名与运行时一致：NeoForge 模组按 Mojang 官方映射编译，无 SRG 重命名）。

| 环节 | 真实类 / 方法 | 说明 |
| --- | --- | --- |
| 线缆方块 | `com.refinedmods.refinedstorage.common.networking.CableBlock` | 所有颜色同一个类；`getShape` 读方块实体的 `CableConnections` |
| 连接数据 | `...common.networking.CableConnections`（record，6 个 boolean） | 由方块实体持有并随 NBT 同步 |
| 连接臂外形 | `...common.support.AbstractCableLikeBlockEntity#computeConnections` / `#hasVisualConnection` | 用**邻居容器的** `canAcceptIncomingConnection(方向.getOpposite(), 自身方块状态)` 决定这一侧是否长臂 |
| 网络图连边 | `...common.support.network.ConnectionProviderImpl#getConnections` | 对 `addOutgoingConnections` 给出的每个候选，要求目标容器的 `canAcceptIncomingConnection(incomingDirection, from.getBlockState())` 为 true |
| 连接策略 | `...common.support.network.ColoredConnectionStrategy extends SimpleConnectionStrategy` | 线缆 / 输入总线 / 输出总线统一走它（`AbstractBaseNetworkNodeContainerBlockEntity#createMainContainer` 构造，由 `InWorldNetworkNodeContainerBuilder` → `InWorldNetworkNodeContainerImpl` 承载） |
| 容器实现 | `...common.support.network.InWorldNetworkNodeContainerImpl` | **自身声明** `canAcceptIncomingConnection` 与字段 `blockEntity`（满足「Mixin 只能定位目标类自身成员」） |

**结论**：网络图连边与连接臂外形共用**同一个闸门** `canAcceptIncomingConnection`，
所以本项目只在一个点接管即可让「逻辑断开」与「外形断开」同时成立 ——
这正是用户担心的「看着断开了但仍然串线」的根因所在，从同一处堵掉最省事也最彻底。

### 2. 扳手交互挂点与「不影响其它方块」的判定

* **挂点**：NeoForge `PlayerInteractEvent.RightClickBlock`（双端触发，在
  `Item#onItemUseFirst / Block#use / Item#useOn` 之前），见
  `support/RsccWrenchCableInteraction#onRightClickBlock`。
  Create 自己的扳手逻辑在 `WrenchItem#useOn`（只认 `IWrenchable`）与 `WrenchEventHandler`
  （优先级 HIGH，只对 `IWrenchable` 生效）；RS 线缆两类都不是，扳手点上去本来什么也不做。
* **接管条件（全满足才 `setCanceled(true)` + `SUCCESS`）**：
  ① 档位不是 `off`；② 手持物带 `c:tools/wrench` 标签（Create 扳手已在该标签内，其它模组扳手同理可用）；
  ③ `player.mayBuild()`；④ **没有潜行**（潜行一律不接管，把 Create / 原版的拆装 / 拾取行为完整让出去）；
  ⑤ 目标方块是 RS 线缆类（`RsccWireBlocks.isWire`：线缆 / 输出总线 / 输入总线）。
  任一不满足即 `return`，**绝不 cancel**。
* 反馈：actionbar，中英双语，句子短（`message.rs_create_compat.cable_*`）。

### 3. 两种方式的实现要点

**方式 A —— 右键接缝断开（`seam`）**

```java
// 归一化接缝键：坐标字典序较小的一侧 + 指向另一侧的方向（两侧查询命中同一条记录）
if (pos.asLong() <= other.asLong()) { owner = pos; dir = hitFace; }
else                                { owner = other; dir = hitFace.getOpposite(); }
if (!wasCut && !hasVisibleConnection(level, pos, hitFace)) return ToggleResult.INVALID; // 没连线不瞎断
toggle(saved.seam, owner, dir); // 再右键同一条接缝即恢复
```
判「这里有没有可断的接缝」直接读 RS 的外形数据 `AbstractCableLikeBlockEntity#getConnections`
（不做任何几何推断），因此「线缆↔线缆」与「线缆↔机器」都能断；已被本功能断开的接缝优先按「恢复」处理，
否则会因为外形已是不连而永远恢复不了。

**方式 B —— 右键面切换（`face`）**

```java
// 记录「本方块的某个面不再自动连接」，不要求当前有连接（先把面关掉 = 以后放上来也不连）
toggle(saved.face, pos, hitFace);
// 查询时两侧都查：本方的面 / 对面的反方向面 —— 任一被关掉，这一处连接即断开
return has(face, self, towardOther) || has(face, other, towardOther.getOpposite());
```
六个面各自独立；同一方块的多条记录合成一个「方向位掩码」（位 = `Direction#ordinal()`，`1 << ordinal`），
一个坐标只占一个键。

**两套记录分开存**：档位决定「扳手写哪一套、判定读哪一套」，因此切档等价于换一套记录
（`off` = 完全不生效，回到 RS 原版行为），互不污染、切回去即原样恢复。

### 4. 持久化的位置

* **服务端（权威、跟存档走）**：`SavedData`，数据文件名 `rscc_cable_cuts`
  → 落盘为 `<存档>/data/rscc_cable_cuts.dat`（按维度各一份，
  `ServerLevel#getDataStorage().computeIfAbsent(...)`）。
  格式：`{seam:{ "<BlockPos#asLong()>": <byte 方向掩码>, ... }, face:{ ... }}`。
  读档即恢复，**不给 RS 的方块 / 方块实体加任何字段，不动方块状态，不破坏方块**。
* **客户端（只读镜像）**：客户端看不到 `SavedData`，因此新增 S2C 包
  `network/SyncCableDisconnectsPacket`（整份快照：档位 + 两套掩码）。
  发送时机：玩家**登录 / 换维度 / 重生**（`RsccWrenchCableInteraction`）+ **每次改动后**
  （`RsccCableCuts#broadcast`）。改动时**先发包、再发方块更新**，
  两者走同一条有序连接，因此客户端重算连接臂时拿到的一定是新数据。

### 5. 配置项与默认值

| 项 | 值 |
| --- | --- |
| 键 | `wrenchCableDisconnectMode`（COMMON 配置，与既有配置同文件 `rs_create_compat-common.toml`） |
| 取值 | `seam` / `face` / `off` |
| 默认 | **`seam`** —— 最贴近用户原话「右键两个方块之间的连接处断开」，且不要求玩家先理解「面」的语义；接缝模式还带「这里没连线就不断」的提示，误操作面最小 |
| 生效时机 | 在 `Config#onLoad`（`ModConfigEvent`）读一次 → **改动后需重启客户端 / 服务端**；非法值回落到 `seam` 并打一条 warn |

### 6. 与搜链（`RsccWireLinkSearch`）的接入点

被扳手断开的连接在搜链里等同「这一侧没有线」，避免「看着断开了但仍然串线」：

* `hasRelevantNeighbor`：每个方向先问 `RsccCableCuts.isDisconnected(level, origin, direction)`，
  被断开的方向直接跳过 —— 从而「预检未命中 ⇔ BFS 一步都走不出去」这条不变式继续成立；
* `searchChamber`（输出总线 → 执行舱）与 `connectedReturnBuses`（回流总线归属/均分）：
  每一步 `cell → cell.relative(direction)` 展开前先问同一句话，命中即 `continue`。

调用形态与 Mixin 完全一致（`self` = 当前方块，方向 = 指向另一方），
所以「网络图连边 = 连接臂外形 = 搜链」三处用的是**同一个判定**。

### 7. 本轮新增 / 修改文件

新增：`support/RsccCableCuts.java`、`support/RsccWrenchCableInteraction.java`、
`network/SyncCableDisconnectsPacket.java`、`mixin/network/InWorldNetworkNodeContainerImplMixin.java`、
`tools/gen_lang_frag_wrench.py` + `tools/lang_frag_wrench.json`、本追加脚本。

修改：`Config.java`（新增档位项）、`RS_Create_Compat.java`（注册 S2C 包 + 注册交互监听）、
`support/RsccWireLinkSearch.java`（三处接入断开判定 + 类注释）、
`src/main/resources/rs_create_compat.mixins.json`（登记新 Mixin）、
`src/main/resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：NBT 标记、回流总线、收集类机器、单元样板管理舱相关文件；
未改任何方块 / 方块实体 / 菜单 / 贴图 / 布局。
"""


def main():
    with open(DOC, "r", encoding="utf-8") as handle:
        text = handle.read()
    if MARK in text:
        print("[跳过] 章节已存在: %s" % MARK)
        return 0
    with open(DOC, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(SECTION)
    print("[追加] %s（+%d 字符）" % (os.path.relpath(DOC, ROOT), len(SECTION)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
