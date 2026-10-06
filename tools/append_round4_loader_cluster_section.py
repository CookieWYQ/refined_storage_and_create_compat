# -*- coding: utf-8 -*-
"""把本轮的「装填器家族接入集群框架（内存叠加 + 资源共享）」章节**幂等**追加进
docs/DESIGN_DECISIONS_ROUND4.md，并纠正上一节里「装填器本轮未接」的过时结论。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过 / 旧行精确匹配才替换」的方式工作，重复执行结果一致。

用法：
    python tools/append_round4_loader_cluster_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 装填器家族接入集群框架：内存叠加 + 资源共享（本轮）"

# 上一节「适用范围清单」里的过时行（该行写的是「本轮未接」）
OLD_ROW = (
    "| 装填器 / 高级装填器 `SchematicLoaderBlockEntity` / `AdvancedSchematicLoaderBlockEntity` | "
    "内部是「蓝图槽 + 队列 + 升级槽」的**作业状态**（作业进度、排队顺序），不是可叠加的资源池；"
    "合并会让两台机器共用同一条作业队列，行为无法定义。 |"
)
NEW_ROW = (
    "| 装填器 / 高级装填器 `SchematicLoaderBlockEntity` / `AdvancedSchematicLoaderBlockEntity` | "
    "**本轮已接入**（见文末「装填器家族接入集群框架」一节）：只合并「从网络拉取进来暂存的那份内存」；"
    "蓝图槽 / 队列 / 升级槽这些**作业状态**仍然各机独立。 |"
)

# 章节已追加过（首轮版本）时，用下面的成对替换把其中两处过时结论就地更新
PATCHES = [
    (
        """滚动条页数（数据槽 `getClusterTotalRows()`）随之正确 —— 打开 follower 看到的就是整体，
  **不需要任何界面改动**（基础 / 高级各自仍打开自己的界面，高级多出队列区）。
* 本轮只把 `getClusterInGuiOrder()` 加的「同 tick 缓存」字段补齐，并在集群变化时失效。""",
        """滚动条页数（数据槽 `getClusterTotalRows()`）随之正确 —— 打开 follower 看到的就是整体
  （基础 / 高级各自仍打开自己的界面，高级多出队列区）。
* 菜单构造时抓的是 `ldr.memoryView()`：**实时视图**（每次调用现解析本机当前的内存，身份稳定），
  于是集群增台 / 拆台换了内存对象后界面自动跟上 —— 若抓当时的那个对象，玩家会往一份
  「已经不再落盘的孤儿内存」里放东西（真正的丢物品路径）；视图对越界槽位统一
  「读 = 空 / 不能放 / 写 = 落回世界」，不抛异常也不吞物品。
* 另补上 `getClusterInGuiOrder()` 的同 tick 缓存字段，并在集群变化时失效。""",
    ),
    (
        """`SchematicLoaderInventory`、`rscc$cluster*` 全套方法、`setRemoved` 拆集群钩子、单主控落盘边界；
`getClusterTotalRows` / `countItemInCluster` / `returnExcessToNetwork` 改为按共享身份去重；
补上 GUI 顺序的同 tick 缓存）、`AdvancedSchematicLoaderBlockEntity.java`（继承实现，集群代码零改动）、""",
        """`SchematicLoaderInventory`、`MemoryView` 实时视图、`rscc$cluster*` 全套方法、`setRemoved`
拆集群钩子、单主控落盘边界；`getClusterTotalRows` / `countItemInCluster` / `returnExcessToNetwork`
改为按共享身份去重；补上 GUI 顺序的同 tick 缓存）、
`menu/SchematicLoaderMenu.java` / `menu/AdvancedSchematicLoaderMenu.java`（内存改用 `memoryView()`）、
`AdvancedSchematicLoaderBlockEntity.java`（继承实现，集群代码零改动）、""",
    ),
    (
        """区块重新加载时才并回；「主控被破坏」时若整体格数大于主控单机格数，多出来的部分按框架规则落回世界
（掉落物 / 装桶，绝不销毁）。""",
        """区块重新加载时才并回；「主控被破坏 / 卸载」时若整体格数大于主控单机格数，多出来的部分按框架规则
落回世界（掉落物 / 装桶，绝不销毁）；界面打开期间若集群台数变化，多出来（或已消失）的那部分槽位
要到重新打开界面（或滚到新页）才完整可见。""",
    ),
]

SECTION = """

***

""" + MARK + """

> 来源：用户需求原话「装填器**从网络拉取物品到自身内存的那份内存**」要能叠加并共享；
> 「蓝图槽、作业队列这类**作业状态**不合并」。
> 框架、主控选举、拆集群、跨区块策略全部复用上文「机器集群」一节（`support/RsccMachineCluster`），
> 本节只记录**装填器特有的接入点与取舍**。

### 1. 类名与「合并 / 不合并」边界

| 字段 / 类 | 说明 | 是否合并 |
| --- | --- | --- |
| `SchematicLoaderBlockEntity`（基础，54 格） | 集群成员；`RsccClusterable` 的实现者 | — |
| `AdvancedSchematicLoaderBlockEntity`（高级，108 格，继承基础） | 与基础**同一个家族**，相邻时并入同一个整体 | — |
| `inventory`（`SchematicLoaderInventory`） | **从网络拉取进来暂存的那份内存** | **合并**（容量 = 各台格数之和） |
| `blueprintSlot`（蓝图槽） | 本机当前作业的蓝图 | 不合并 |
| `upgradeContainer`（6 个插件槽） | 速度 / 自动合成升级按台生效 | 不合并 |
| `queue`（27 格蓝图队列，仅高级） | 作业顺序 / 排队状态 | 不合并 |

* **同族**判定在框架侧：`RsccMachineCluster#sameFamily` 让 `SchematicLoaderBlock` 与
  `AdvancedSchematicLoaderBlock` 相邻时也能穿过（两者那份内存的语义完全相同：都是一格格物品）；
* 机器侧把 `rscc$clusterCompatible` 一起放宽为 `other instanceof SchematicLoaderBlockEntity`，
  于是「高级被打掉后内容移交给相邻基础」这条路径同样成立（两条判据必须一致）。

### 2. 容量叠加：按各台自己的格数**求和**

```java
@Override public Object rscc$clusterNewPayloadFor(final java.util.List<RsccClusterable> members) {
    int slots = 0;
    for (final RsccClusterable member : members) {
        slots += member instanceof SchematicLoaderBlockEntity loader
            ? loader.inventorySlotCount : inventorySlotCount;   // 基础 54 / 高级 108
    }
    return new SchematicLoaderInventory(Math.max(inventorySlotCount, slots));
}
```

* 覆写的是 `rscc$clusterNewPayloadFor(members)` 而不是 `rscc$clusterNewPayload(台数)`：
  混阶集群（基础 54 + 高级 108）必须得到 162 格；若沿用「台数 × 某一种单机格数」，
  整体容量会凭空缩小（高级被当成基础）或放大（基础被当成高级），两者都不允许。
* `inventorySlotCount` = 本机单机格数（基础 54 / 高级 108），集群缩容 / 单机化都按它算。

### 3. 资源共享：全体指向**同一个** `SchematicLoaderInventory` 实例

```java
@Override public Object rscc$clusterPayload() { return inventory; }   // 身份稳定 ⇒ 管理器可用 == 判定
@Override public void rscc$clusterAdopt(final Object shared) {
    if (!(shared instanceof SchematicLoaderInventory next) || next == inventory) return;
    inventory = next; inventory.bindOwner(this); setChanged();
}
```

* 新增内部类 `SchematicLoaderBlockEntity.SchematicLoaderInventory extends ItemStackHandler`：
  实例上挂着若干「持有者」**弱引用**，`onContentsChanged` 通知**所有仍持有它的装填器** ——
  因为**落盘的那一份在主控手里**，从任意一台（或任意一个界面）改动都必须标记到主控，
  否则这次改动会在区块落盘时丢掉；弱引用同时避免方块实体被移除后阻止回收。
* 于是「拉取 → 暂存」这条主流程（`fetchFromNetwork` / `restockByNeeds` / `insertIntoCluster`）
  **不需要任何同步逻辑**：所有成员解析到的都是同一个对象。
* 同族合并后，GUI 顺序列表（`getClusterInGuiOrder`）按**存储身份**去重 ⇒
  「插入」「计数」「行数」都只会看到**一份**内存，不会被重复算成 N 倍
  （本轮据此把 `countItemInCluster` / `returnExcessToNetwork` / `getClusterTotalRows`
  改成走去重后的视图）。

### 4. 不丢、不复制（成型 / 增台 / 拆分 / 破坏 / 读档）

| 场景 | 处理 | 结果 |
| --- | --- | --- |
| 集群成型 / 增台 | 框架建「各台格数之和」的新共享，把旧共享与新成员的载荷**移动**（不是复制）进去，全员采纳 | 内容只搬一次，容量只增不减 |
| 普通成员破坏 / 卸载 | 框架：内容留在集群，本机换成空的单机内存（`setRemoved` → `onMemberRemoved`） | 幸存者继续持有那份内容 |
| 主控破坏 / 卸载 | 框架：内容收进主控「单机格数」的新载荷（装不下的余量落回世界），幸存者随后重建为「等容量但为空」 | 内容跟着当时持有它的一方走 |
| 读档 | **只有主控写过 `Inventory`**（follower 写空载荷），集群重建时再按「移动」汇总 | 同一份内容不可能被读两次 |
| 破坏结算（`BlockContentReleaser`） | 全局档位为「存方块」时 follower 的 NBT 不含内容 ⇒ 幸存者保留；档位为「爆出 / 回网」时内容被明确交付给世界 / 网络；`release` 前先 `handOverToSurvivors` | 既不会翻倍，也不会消失 |

* **持久化边界**：`saveAdditional` 里 `if (clusterOwnsPayload) tag.put("Inventory", …)`，
  其余成员不写；蓝图槽 / 插件槽 / 队列**永远照常保存**（作业状态属于各机自己）。
* 读档格数自适应：`ItemStackHandler.deserializeNBT` 会按 tag 里的 `Size` 重设格数，
  因此「存档时是叠加后的 162 格」也能整体读回，随后由集群按当前已加载台数重新定型。

### 5. 与「缓存满不吞资源」一致（`SafeCollect` 语义不变）

`fetchFromNetwork` 仍是「先 `simulateInsertIntoCluster` 算整体还能收多少 → 只按实际能收的量抽取 →
插不下则退回网络、退不回才落回世界」。叠加后 **满 = 整体满**：探针直接打在那一份共享内存上，
所以整体一满就一个也不抽（`tools/audit_resource_swallow.py` 保持 0 未处理可疑点）。

### 6. GUI：打开任意一台都显示合并整体

* 菜单的槽位列表本来就来自 `loader.getClusterInGuiOrder()`，而该方法**已按共享内存身份去重**：
  同族合并后只剩一项，它的 `getSlots()` 就是整体格数（54 × N），`ClusterSlot` 的全局槽位映射、
  滚动条页数（数据槽 `getClusterTotalRows()`）随之正确 —— 打开 follower 看到的就是整体
  （基础 / 高级各自仍打开自己的界面，高级多出队列区）。
* 菜单构造时抓的是 `ldr.memoryView()`：**实时视图**（每次调用现解析本机当前的内存，身份稳定），
  于是集群增台 / 拆台换了内存对象后界面自动跟上 —— 若抓当时的那个对象，玩家会往一份
  「已经不再落盘的孤儿内存」里放东西（真正的丢物品路径）；视图对越界槽位统一
  「读 = 空 / 不能放 / 写 = 落回世界」，不抛异常也不吞物品。
* 另补上 `getClusterInGuiOrder()` 的同 tick 缓存字段，并在集群变化时失效。

### 7. 本轮修改文件

`block/entity/SchematicLoaderBlockEntity.java`（实现 `RsccClusterable`：新增
`SchematicLoaderInventory`、`MemoryView` 实时视图、`rscc$cluster*` 全套方法、`setRemoved`
拆集群钩子、单主控落盘边界；`getClusterTotalRows` / `countItemInCluster` / `returnExcessToNetwork`
改为按共享身份去重；补上 GUI 顺序的同 tick 缓存）、
`menu/SchematicLoaderMenu.java` / `menu/AdvancedSchematicLoaderMenu.java`（内存改用 `memoryView()`）、
`AdvancedSchematicLoaderBlockEntity.java`（继承实现，集群代码零改动）、
`tools/append_round4_loader_cluster_section.py`（本文件）、本文件。

**未完成 / 已知取舍**：跨区块时只按**已加载**台数定型（框架既有策略），未加载那部分的内容要到它的
区块重新加载时才并回；「主控被破坏 / 卸载」时若整体格数大于主控单机格数，多出来的部分按框架规则
落回世界（掉落物 / 装桶，绝不销毁）；界面打开期间若集群台数变化，多出来（或已消失）的那部分槽位
要到重新打开界面（或滚到新页）才完整可见。
"""


def main():
    with open(DOC, "r", encoding="utf-8") as handle:
        text = handle.read()

    patched = False
    # 1) 纠正上一节里「装填器本轮未接」的过时行（只替换精确匹配的那一行）
    if OLD_ROW in text:
        text = text.replace(OLD_ROW, NEW_ROW, 1)
        patched = True
    # 2) 章节若已按首轮版本追加过，把其中过时的结论就地更新
    for old, new in PATCHES:
        if old in text:
            text = text.replace(old, new, 1)
            patched = True

    # 3) 未追加过则追加新章节（标记存在即跳过）
    if MARK in text:
        if patched:
            with open(DOC, "w", encoding="utf-8") as handle:
                handle.write(text)
            print("[修正] 过时结论已就地更新")
        print("[跳过] 章节已存在: %s" % MARK)
        return 0

    text += SECTION
    with open(DOC, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("[追加] %s（+%d 字符）%s"
          % (os.path.relpath(DOC, ROOT), len(SECTION), "，并修正过时行" if patched else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
