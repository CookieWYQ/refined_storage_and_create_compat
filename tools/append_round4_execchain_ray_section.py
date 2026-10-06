# -*- coding: utf-8 -*-
"""把「执行舱链 = 有方向的射线」收紧章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

背景：用户澄清「链应该是一条有方向的射线，端点就是第一个仓」，因此上一版「可能存在树状分叉」
的说法被推翻，本章记录收紧后的实现（禁止分叉 / 成环 + 旧档收敛）。

用法：
    python tools/append_round4_execchain_ray_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 执行舱「链」收紧为有方向的射线：禁止分叉 / 成环 + 旧档收敛（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户澄清「这个组成的应该是一条**有方向的射线**，这条射线的**端点就是第一个仓**，
> 所以**不存在你说这种情况**」—— 即上一版报告里「多点指向同一台（树状）」的风险**不允许存在**，
> 本轮从实现上把它排除：**入度 ≤ 1**，链永远是一条射线。
> 硬规则不变：服务端权威、**绝不销毁玩家资源 / 绝不丢配置**、所有拒绝路径都回发权威值。

### 1. 四条不变式（每次成功写入后都成立）

| 不变式 | 判据（代码位置） |
| --- | --- |
| 端点（链首）不指向任何一台 | 链首的 `chainLink == NO_LINK`；`chainHead()` 走到目标不是执行舱即停 |
| 除端点外每台**恰好指向一台** | 每台最多一个 `chainLink`（单个 int 字段，天然出度 ≤ 1） |
| **入度 ≤ 1**（不分叉） | 写入时 `hasOtherPredecessor(target, this)` ⇒ 拒绝 `FORK` |
| **无环** | 写入时 `target == this \\|\\| reachesMe(target)` ⇒ 拒绝 `CYCLE`（判据是「从 target 沿指向走**能否经过本仓**」——比只比链首严格：链 Y→我→X→… 时改指向 Y，从 Y 出发只是「经过」我，只比链首会漏判）；遍历另有 visited 兜底 |
| 链长 ≤ 8 | 写入时 `upstreamSize() + target.chainSize() > MAX_CHAIN_LENGTH` ⇒ 拒绝 `TOO_LONG`（沿用 RS 的 `MAX_CHAINED_AUTOCRAFTERS = 8`） |

「L1 到 L2 会连成射线」：因为出度 ≤ 1 且入度 ≤ 1 且无环，有向图退化成一条**链**；
链首是唯一出度为 0 的顶点 ⇒ **端点唯一且确定**，`chainHead()` 沿指向前进必然停在它上面。

### 2. 四个入口如何维持不变式

| 入口 | 维持方式 |
| --- | --- |
| **设置指向**（C2S → `setChainLink`） | 五道校验：目标存在 / 不成环 / **本机+目标两圈邻居已加载** / 不分叉 / 不超长；任一不过即**拒绝 + 行动栏短提示 + 回发权威值**。写入成功即满足全部不变式 |
| **拆方块**（`Block#onRemove`） | 拆掉任一台 = 从射线的某处剪断：下游段（指向被拆那台的后面那些）自然形成新的段，先由 `handOverChainOnRemoval()` 把配置交给**唯一**后继（入度 ≤ 1 ⇒ 最多一台），其余情况只取消指向关系。每段仍是射线 |
| **拆链首** | 是「拆方块」的特例：链首最多有一个直接后继（入度 ≤ 1）⇒ 它接任新链首并承接名字 / 配方类型，**不会**出现「多个子台各接一份配置、形成两条同名链」 |
| **区块加载** | 链**不进任何缓存**：`chainHead()` / `chainMembers()` 每次按当前世界状态现推，读档 / 加载 / 卸载本身不会改变任何字段。仅对**旧档遗留数据**做一次性自愈：`sanitizeRayServer()`（方块服务端 ticker 每 tick 最先调用） |

`sanitizeRayServer()` 的收敛规则（**只取消指向，绝不销毁配置 / 方块 / 资源**）：

```java
for (Direction d : Direction.values())                       // ① 邻居没全加载不下结论（下一 tick 重试）
    if (!level.isLoaded(worldPosition.relative(d))) return;
if (isInCycle()) { chainLink = NO_LINK; ... }                 // ② 旧档成环：由本机这一段断开
if (hasOtherPredecessor(target, this)) {                      // ③ 旧档分叉：坐标大的一方让步
    if (winner != this) { chainLink = NO_LINK; ... }          //    （纯本地判定，双方结论一致，不会拉锯）
}
```

* 「坐标较小者留下」是纯粹的函数（只看双方坐标），因此两边看到同一组时结论一致；让步方解除指向后
  自己成为一段独立链的链首，**自己的名字 / 配方类型原样保留**，绝不覆盖、绝不销毁。
* 自愈只在**本机**跑一次（内存标志 `rayVerified`，不进 NBT）；且**本机与目标两圈邻居**所在区块
  全部已加载才下结论，所以「分叉的另一方在未加载区块里」不会被误判成没问题 —— 那一方加载后
  由它自己继续收敛（双方判定集合一致 ⇒ 结论一致，不会来回拉锯）。
* 收敛后：服务端 `LOGGER.warn` 记录坐标，且**正在看该仓界面的玩家**收到一条短提示
  （`message.rs_create_compat.chamber_ray_{fork,cycle}_cleared`）+ 界面刷新；不刷聊天栏之外的地方。

### 3. 拒绝点与提示（中英双语，走 `tools/lang_frag_execchain.json`）

| `LinkResult` | 触发条件 | 行动栏提示键 |
| --- | --- | --- |
| `NO_TARGET` | 该方向没有已加载的执行舱 | `message.rs_create_compat.chamber_link_no_target` |
| `CYCLE` | 会绕回自己 | `message.rs_create_compat.chamber_link_cycle` |
| `FORK` | 目标已被另一台指向（会分叉） | `message.rs_create_compat.chamber_link_fork` |
| `TOO_LONG` | 接上后超过 8 台 | `message.rs_create_compat.chamber_link_too_long` |
| `UNVERIFIED` | 本机 / 目标那一圈邻居区块未加载（无法确认，先拒绝） | `message.rs_create_compat.chamber_link_not_ready` |
| `INVALID` | 方向序号越界 | `message.rs_create_compat.chamber_link_invalid` |

拒绝路径一律 `echo(player, chamber)` 回发权威快照 ⇒ 客户端界面上的选择**立即复位**，不会出现
「看着改了、服务端没改」。链指向控件的 tooltip 也补了一行规则说明（`config.chain.link.rule`：
「指向连成一条有方向的射线：不分叉、不成环，端点即链首」），把会被拒绝的情形提前讲清。

### 4. 拆链首的最终行为（确认结论）

* 链首最多有**一个**直接后继（入度 ≤ 1）⇒ 拆掉链首后由它**接任链首**并承接名字 / 配方类型
  （仅当它自己还没有值，绝不覆盖），其余成员照旧指向它 —— 整条链**只是少了一台**，不是断成两段。
* 因此**不存在**「多个子台各接一份配置、形成两条同名链」的情况（该风险已随入度 ≤ 1 消失）。
* 拆掉**中间**某台时：上游那一段自然成为独立的一段（其链首 = 被拆那台的直接后继），
  共享值仍在原链首那一份上、**绝不销毁**；这是与 RS 一致的行为（RS 的名字同样只存在链首）。
* 拆掉端点的**下游端**（即链条另一头）不影响任何配置。

### 5. 本轮改动文件

修改：`block/entity/SequenceExecutionChamberBlockEntity.java`（`LinkResult` 增 `FORK` / `TOO_LONG`、
`setChainLink` 四道校验、`collectUpstream` / `upstreamSize` / `hasOtherPredecessor` / `isInCycle`、
`sanitizeRayServer()` + `rayVerified` + `notifyRayConvergence`、`handOverChainOnRemoval` 注释按「唯一后继」校正）、
`block/SequenceExecutionChamberBlock.java`（服务端 ticker 前置一次自愈）、
`network/SetChamberBindingPacket.java`（新拒绝结果映射提示）、
`client/screen/ChamberBindingConfigScreen.java`（控件 tooltip 补「射线规则」一行）、
`tools/gen_lang_frag_execchain.py` + `tools/lang_frag_execchain.json`（+5 键、改 1 键）、
`src/main/resources/assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

同时**就地改正**了上一章的旧说法：链「是一条线」→「是一条有方向的射线（入度 ≤ 1）」；
链首被拆时交给「那些执行舱」→「**唯一**那个后继」。
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
