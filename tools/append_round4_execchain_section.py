# -*- coding: utf-8 -*-
"""把本轮的「序列执行舱 链 / 指向」章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过」的方式追加，重复执行结果一致。

用法：
    python tools/append_round4_execchain_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 序列执行舱「链 / 指向」：名字与配方类型整链共享（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户本轮需求「序列执行舱要像 RS 的自动合成仓那样串成一条链」+ 更正后的语义
> 「可以在任意一个地方改，就相当于是说改一个地方应用到全部」。
> 硬规则不变：服务端权威、**绝不破方块 / 绝不丢配置**、`drawString` 一律 `false`、
> 精灵坐标 → Menu 坐标 **x、y 均 +1**、语言键走 `tools/lang_frag_execchain.json` + `apply_lang_frag.py`。
> 边界：**这不是存储合并** —— 打开一台不会「看到东西变多」；本轮的共享只限「名字 + 配方类型」，与
> 上一轮的「相邻同类叠加 = 容量叠加 + 内容共享」的机器集群**互不干扰**。

### 1. RS 自动合成仓「串联 / 指向」的真实实现（查的是仓库内真源码）

源码：`local_src/external/RefinedStorage/refinedstorage-common/.../autocrafting/autocrafter/`
（`AutocrafterBlockEntity.java` / `AutocrafterData.java` / `AutocrafterContainerMenu.java`）。

| 环节 | 真实类 / 成员 | 说明 |
| --- | --- | --- |
| 链长上限 | `AutocrafterBlockEntity.MAX_CHAINED_AUTOCRAFTERS = 8` | 递归深度闸门（`getChainingRoot(depth, origin)` 到上限即返回 `origin`） |
| 「指向」的载体 | **方块状态里的方向** `tryExtractDirection(getBlockState())` | RS 没有独立的「指向」字段，箭头就是方块朝向；本模组朝向已被「机器侧朝向」占用，故改用独立字段 |
| 指向的对象 | `getConnectedMachine()` | 只取**自己朝向那一格**的方块实体（`level.getBlockEntity(pos.relative(direction))`，未加载即 null） |
| 链归属 | `getChainingRoot()` → `isPartOfChain()` / `isHeadOfChain()` | 沿指向逐台前进，走到的那台即链首；`isPartOfChain = getChainingRoot() != this`；`isHeadOfChain` = 自己不是成员且有邻居指向自己 |
| 名字归属 | `getName()` / `doGetName()` / `setCustomName(String)` | **成员的名字一律委托链首**（`root.getName()`）；`setCustomName` 在 `isPartOfChain()` 时**直接 return**（RS 只允许链首改名） |
| 客户端同步 | `AutocrafterData(boolean partOfChain, boolean headOfChain, boolean locked)` + `getMenuCodec()` | 只同步 3 个布尔用于界面展示（RS 界面据 `canChangeName() = !partOfChain` 灰掉改名入口） |
| 成形 / 解散时机 | **没有成形 / 解散事件**，纯惰性 | 链每次调用现推；改指向 = 改方块朝向（扳手 / 放置），拆方块 = 链自然断成两段 |

**结论**：RS 的链是「**每台只存自己指向谁**、链首唯一持名字、其余全委托、解析惰性无缓存」。
本项目**照抄**这套骨架（沿指向走到链首 + 链首唯一持有 + 惰性解析），**借鉴处**有两点：
①「指向」改用独立持久化字段 `ChainLink`（因为方块朝向另有用途）；
② RS 的「成员改不了名」被用户语义**反转**为「任意一处改 = 整链生效」（见第 3 节）。

### 2. 链的构成 / 成形 / 解散时机

* **构成**：每台执行舱持有 `chainLink`（`Direction#get3DDataValue()` 或 `NO_LINK = -1`）。
  `chainHead()`：沿指向逐台前进（上限 `MAX_CHAIN_LENGTH = 8`，与 RS 一致），出现自环 / 目标不是
  执行舱 / 目标区块未加载即停在该台 —— **链首 = 停下来的那一台**。
* **成员集合**：`chainMembers()` 先走到链首，再从链首沿「谁指向我」反向 BFS（同样限长），
  按坐标升序返回；写入 / 广播 / 快照都以它为准。链是**一条有方向的射线**：所有人都指向链首（端点），
  除端点外每台**恰好被一台**指向（入度 ≤ 1），成环 / 分叉都被服务端拒绝（见本轮收紧章节）。
* **成形**：在「配置」子界面把「链指向」指向相邻的某台执行舱并确定 → 服务端写入 → 立即成链。
* **解散**：改指向（含改为「不指向」）或拆掉中间任一台即散；**没有缓存的链对象**，
  每次读取按当前世界状态现推，所以区块加载 / 卸载、放置 / 破坏都不会留下脏链。
* **拆链不丢配置**：改指向时先把当前链共享的名字 / 配方类型抄下来写入**新的链首**
  （仅当它自己还没有值，绝不覆盖已有值）；拆掉链首本身时由方块 `onRemove` →
  `handOverChainOnRemoval()` 把配置交给**唯一**那个「指向它的执行舱」（同样只写给空的）。
  `onRemove` 是唯一可靠时机：世界与相邻方块实体都已加载，且区块卸载不会走它。

### 3. 名字与配方类型「改一处 = 全链生效」

链上**只有链首持有权威值**，成员一律读链首；`set*` 时若自己不是链首就把值**转发到链首**，
因此「在任意一台提交」与「在链首提交」是同一件事，不存在两份配置，也就没有分裂的机会：

```java
public void setChamberName(final String name) {
    final SequenceExecutionChamberBlockEntity head = chainHead();
    if (head != this) { head.setChamberName(name); return; }   // 成员 → 转发链首：整链同步
    final String value = name == null ? "" : name;
    if (value.equals(ownChamberName())) { return; }             // 幂等
    this.chamberName = value;
    markDirtyAndSync();
}

public String getRecipeType() { return chainHead().ownRecipeType(); }   // 读也只读链首那一份
```

**并发提交**：C2S `SetChamberBindingPacket` 在服务端**主线程** `enqueueWork` 里执行（见
`RS_Create_Compat` 注册），因此天然串行、**以最后一次提交为准**；一次提交是**原子**的，
顺序固定「先落链指向（可能换链首）→ 再落名字 / 配方类型（写在新的链首上）」。
每次提交后服务端都：① 回发权威快照给提交者；② `broadcastChainBinding()` 推给**链上每一台**
正在被查看的执行舱 —— 于是不会出现「这一台改了、另一台还是旧值」或
「客户端看着改了、服务端没改」（**所有拒绝路径也回发权威值**，客户端界面立刻复位）。
客户端另加一道保险：链指向控件只有被玩家**真的动过**才提交（未动过发 `UNCHANGED_LINK`），
避免用陈旧快照把别人刚设好的链覆盖回去。

### 4. 与「机器集群（容量叠加 + 内容共享）」并存

* 集群走 `support/RsccMachineCluster` + `RsccClusterable`（相邻同类 → 同一个存储载荷对象），
  **本轮一行没动**：`outputStorage / outputTank`、`ClusterPayload`、`clusterOwnsPayload` 全部照旧。
* 链只碰 `chainLink / recipeType / chamberName` 三个字段，**不参与任何存储合并**：
  打开一台执行舱看到的仍是它（或它所在集群）那**一份**输出内容，不会因为串了链而变多。
* 两者正交：可以「串成链但各自独立存储」，也可以「同一条链上几台再各自与邻居组集群」。
* 兼容性：`OutputMode{FACE, BUS}`、面配置、类别归属（`busCategoryOwners`）全部未改；
  配方类型锁定判据从「本台有样板」升为「**链上任一台有样板**」（`chainHasAnyUnit()`），
  因为整链共享同一个配方类型，改类型会让**整条链**已放样板失配。

### 5. 持久化：链只存「指向」，权威配置只存链首那一份

* `ChainLink`（int，NBT）：**每台只存自己指向哪一格**；旧档无该字段 → `NO_LINK`（独立）。
* `ChamberRecipeType / ChamberName`：**只有链首那一份非空**（成员一律转发写入链首），
  因此读档后不可能出现「同一份配置被写成多份再合并 → 翻倍 / 分裂」。
* 读档后链怎么重建：不重建 —— 链**不进任何缓存**，`chainHead()` / `chainMembers()` 每次按当前
  世界状态现推，所以读档、区块加载卸载后「谁是链首」自动正确，无需任何反序列化补救逻辑。

### 6. 界面（tooltip 一律手动渲染，判定范围与绘制范围同源）

* **主界面**（`SequenceExecutionChamberScreen`）：绑定状态行末尾加极短的「（链 N 台）」后缀；
  该行 tooltip（手动渲染）写清「链头：<名字或坐标>」「名字与配方类型整链共享…」，
  未串链时明确写「未接入链：本台独立持有…」。
* **配置子界面**（`ChamberBindingConfigScreen`，面板 200×146 → **200×190**，PNG 同步重出）：
  新增「链指向」滚轮控件（不指向 / 上 / 下 / 北 / 南 / 西 / 东，方向名复用面配置的语言键）
  + 其下方的链状态行「链：N 台 · 链头 …」；控件 tooltip 由控件自绘（+ 客户端按本台坐标算出
  目标坐标），状态行 tooltip 手动渲染「只存在链头那一份 / 没有『只改这一台』/ 样板锁定原因」。
* 拒绝反馈：`message.rs_create_compat.chamber_link_{no_target,cycle,invalid}` 走 actionbar（不刷聊天栏）。

### 7. 本轮新增 / 修改文件

新增：`tools/gen_lang_frag_execchain.py` + `tools/lang_frag_execchain.json`、本追加脚本。

修改：`block/entity/SequenceExecutionChamberBlockEntity.java`（`UNCHANGED_LINK`、`ChainState` 增
`headPos`、`handOverChainOnRemoval()`、`ChainLink` 存取、注释按新语义校正）、
`block/SequenceExecutionChamberBlock.java`（`onRemove` → 链移交）、
`network/SyncChamberBindingPacket.java`（链字段 + `of(entity)` 权威工厂 + 手写编解码）、
`network/SetChamberBindingPacket.java`（`linkOrdinal` + 原子提交 + 拒绝回发 + 全链广播）、
`network/SetChamberFacePacket.java` / `SetChamberOutputModePacket.java` / `menu/SequenceExecutionChamberMenu.java`
（统一改用 `SyncChamberBindingPacket.of(...)`，避免面配置回包把链状态刷成「独立」）、
`client/screen/SequenceExecutionChamberScreen.java`、`client/screen/ChamberBindingConfigScreen.java`、
`textures/gui/chamber_binding_config.png`（200×190，由 `tmp_textures/gen_child_panels.py` 约定尺寸重出）、
`tools/lang_frag_spt3.json`（配方类型锁定文案改为链级口径）、
`src/main/resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：装填器家族、NBT 标记、序列装配回流总线、单元样板管理舱、扳手相关文件，
以及执行舱的机器集群 / 存储侧代码。
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
