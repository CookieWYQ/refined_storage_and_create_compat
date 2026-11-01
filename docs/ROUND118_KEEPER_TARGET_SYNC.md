# 第 118 轮（1.2.0-beta.20）之三：定量保持器「目标数量」short 截断 + 网络抽取重入保护

> 基线：`1.2.0-beta.19`（第 117 轮）。本轮另请一位独立只读复核员对抗式复核
> **中间缓存仓 + 定量保持器家族**（两台保持器 + 中间缓存 + 记分/仲裁/溢出/多流体等 support）。
>
> 复核员报 **2 高 / 3 中 / 4 低（另有 1 组文档不符共 6 小条）**。本轮修掉两条**高**中可安全落地的部分：
> H1 的 short 截断（完整修）与 H2 的递归崩溃（修重入；双计涉及产品口径，见 §5）。
> 中 / 低留到下一轮（§4）。

---

## 1. H1（高）目标数量走 `ContainerData` 被 short 截断 ⇒ 界面显示 0、点 ± 静默改小目标、开注销时误销毁

### 机制（复核员给出的 MC 源码依据）

- MC 1.21.1 的 `ClientboundContainerSetDataPacket` 线上就是 **short**
  （`writeShort(value)` / `readShort()`），`AbstractContainerMenu.setData` 把截断值直接塞进客户端 `ContainerData`。
- 两台保持器把整个 `int` 目标（基础：`targetAmount`；高级：`targets[slot]`）放在**一个** `ContainerData` 索引上
  ⇒ 值 > 32767 时客户端读到的是低 16 位（符号扩展）。
- 流体目标按 **mB** 计（1 桶 = 1000 mB）⇒ **目标 > 32.7 桶（输入 `33b`）就中招**：
  界面输入框回填 `"0"`（负值被 `RsccNumberField` 夹到 0）；玩家点 `+` 时按 0 计算并发出 `target=1`
  ⇒ **服务端目标被静默改小**。若开着「过量销毁」，就按这个错误目标销毁网络里的真实资源。

### 修复：目标数量拆成「低 16 位 + 高 16 位」两个索引

- **服务端（`QuantityKeeperBlockEntity`）**：新增 `DATA_TARGET_LOW = 0`（就是原来的 0）、
  `DATA_TARGET_HIGH = 9`（**追加在末尾**，不动既有 0..8 任何下标）、`DATA_COUNT = 10`；
  ContainerData `get(9) = targetAmount >>> 16`（高半段 0..32767，正好放进 short）。
- **客户端（`QuantityKeeperMenu#getTargetAmount`）**：
  `low = data.get(0) & 0xFFFF; high = data.get(9) & 0xFFFF; return (high << 16) | low;`
  两个半段都按**无符号 16 位**掩码拼回（写进线上时是 short 截断，读回来时会被符号扩展）。
- **高级版（`AdvancedQuantityKeeperBlockEntity`）**：新增整块 `DATA_TARGET_HIGH_BASE = SLOT_COUNT * DATA_PER_SLOT`
  （每槽一个高半段），`DATA_SPEED_UPGRADES = DATA_TARGET_HIGH_BASE + SLOT_COUNT` 之后的下标整体顺延；
  `get(DATA_TARGET_HIGH_BASE + row) = targets[row] >>> 16`；`AdvancedQuantityKeeperMenu#getTarget(row)` 同样拼回。
- **客户端占位数据**：`SimpleContainerData(9)` 改为 `SimpleContainerData(QuantityKeeperBlockEntity.DATA_COUNT)`；
  高级版本来就用 `DATA_COUNT` 常量，随常量自动变长。

> 为什么不新开一个 S2C 包：`ContainerData` 的索引本来就是「服务端权威 → 客户端只读」的既有通道，
> 拆半段只动编码、不动协议面（不加包、不改注册表），风险最小；两端算式同一份掩码口径，自检锁定。

---

## 2. H2（高）RS 外部存储贴着保持器 ⇒ 网络抽取无限递归（StackOverflowError）

### 机制

保持器故意把「网络里与标记同类的资源」通过能力暴露给相邻方块（供漏斗 / 管道取出，**这是设计功能**）。
RS 2.0.9 的**外部存储**会把相邻方块的 `IItemHandler` / `IFluidHandler` 注册成网络存储源。于是成环：

```
外部存储读保持器能力 → 保持器 extractItem 从网络抽 → 网络的存储源里又包含那台外部存储
→ 外部存储再读保持器能力 → …（无限递归 ⇒ StackOverflowError）
```

### 修复：`support/KeeperNetworkReadGuard`（同线程重入闸，纯 JDK）

- `enter()`：当前线程已在抽取段 ⇒ 返回 false；否则置位并返回 true。
- `exit()`：复位（幂等）。
- 四个网络抽取入口（基础物品 / 基础流体 / 高级物品 / 高级流体）各加一层 wrapper：
  进入失败 ⇒ **直接返回 0**（本层让位，不递归、不重复抽）；主体搬到 `…Raw` 方法，`finally` 复位。
- **不改变正常路径语义**：单层抽取就是 enter → 抽 → exit 各一次。
- 自检含**真代码 JVM 探针**（javac 编译 `KeeperNetworkReadGuard` 后真跑 8 条：首次进入 / 重入被拒 /
  退出复位 / 重复 exit 幂等 / 跨线程隔离）。

---

## 3. 自检

**新增** `tools/selfcheck_round118_keeper_target_sync.py`：**25 断言 / 9 反例**。

- 源码锚点：两端三个常量、`>>> 16` 高半段、菜单掩码拼回、客户端占位长度跟常量、高级版整块下标顺延、
  四个抽取入口各有 enter/exit、`…Raw` 方法存在；
- 行为模型：14 个取值（0 / 1 / 32767 / **32768** / 65535 / **65536** / 10 万 / 100 万 / Integer.MAX）
  的「低 16 + 高 16」往返；旧单索引编码从 32768 起全部失败；
  **33000 mB（33 桶）在旧编码下被截断成 -32536 ⇒ 输入框夹成 0**，修复后往返正确；
  重入模型：无闸 ⇒ RecursionError，有闸 ⇒ 只走一层且内层返回 0；
- JVM 探针：8 条（见 §2）；
- 反例自证：菜单改回「直接返回低位」、服务端不提供高半段、抽掉重入闸 —— 三种坏写法逐一判负。

---

## 4. 未修（复核员原报，下一轮候选）

| 编号 | 严重度 | 内容 | 说明 |
| --- | --- | --- | --- |
| H2 剩余 | 高（部分） | 「外部存储把网络镜像再算一遍」导致**存量虚高**（虚拟槽最多 +64/槽），保持器按虚高值销毁真实资源 | 与本方块**故意暴露网络镜像**的功能直接冲突，需产品拍板：只暴露本机缓冲 / 加开关 / 换实现 |
| M1 | 中 | C2S 目标数量无服务端上限（输入 `2t` = 21 亿缺口直接交给 RS 自动合成） | 需要一个「目标上限」配置口径 |
| M2 | 中 | 基础版默认目标 64、**过量销毁默认开**；流体按 mB ⇒ 拖入岩浆不改目标会按 64 mB 持续销毁 | 涉及默认值口径，需用户确认 |
| M3 | 中 | 目标=0（未标记）的保持器仍「认领」资源，可让真正生效的那台永远让位（同一资源两台保持器时功能静默失效） | `claimedResources` 与 `KeeperTarget.isMarked` 同源即可，下一轮 |
| L1 | 低 / 文档 | `docs/QUANTITY_KEEPER_MULTI_MARK_DESIGN.md` 仍写「有效标定 = max(各保持器)」，实现是「坐标全序单权威」；另有 `saveStateForItem/isStateDropped/hasAnyFluidState` 死代码与「战利品表已调用」的假注释 | 文档 + 死代码清理，下一轮 |
| L2 | 低 | 缓存仓无电仍执行「存量迁移」（只网络内搬运，不复制不销毁） | 一行 `isActive()` 闸，下一轮 |
| L3 | 低 | 流体 `drain` 与 `fill` 的组件编码不对称（带组件流体「取不出来」，静默回退网络） | 统一传 `registryAccess()`，下一轮 |
| L4 | 低 | `RsccSharedCache#poolFreeSpace` 累加可溢出为负（≥5 块创造级盘） | 饱和加法，下一轮 |

---

## 5. 未验证（诚实清单）

1. **没有实机开界面**：H1 的「界面回填 0 / 点 + 改成 1」全部来自源码路径
   （`ClientboundContainerSetDataPacket` 的 short 读写 → `ContainerData` → `RsccNumberField` 的负值夹 0），
   没有真机复现一遍。
2. **H2 重入闸只挡递归**：双计（存量虚高）**未修**，因为「暴露网络镜像」是本方块的设计功能；
   是否改成「只暴露本机缓冲」需要用户拍板（§4）。
3. 「外部存储贴上去」这个组合是按 RS 2.0.9 源码（`AbstractExternalStorageBlockEntity#loadStorage`、
   `ItemHandlerExtractableStorage#extract`）推导的，没有实机摆出来验证虚高数值。
