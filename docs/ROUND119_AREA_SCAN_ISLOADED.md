# 第 119 轮：区域扫描的流体探测加 `isLoaded` 闸门（审计 C-6）

## 1. 现象（玩家侧）

没有报错、没有崩溃，只是**服务器 tick 时间变高 / 偶发卡顿**，而且往往在玩家**离开基地之后**
才出现（人在地图另一头，基地里的高级归流缓存仓还在跑）：

- 区域跨到被卸载的区块时，每 tick 上千次世界查询会反复触发**同步区块加载**；
- 结果是被卸载的区块被反复拉回内存（区块加载/卸载抖动），主线程时间被吃掉；
- 这类问题最容易被误判成「这个模组性能不好」，因为日志里什么都没有。

## 2. 根因

高级归流缓存仓的「区域」来源每 tick 用**跨 tick 游标**扫描区域内的坐标
（每 tick ≤ 2048 次查询）：

```
AdvancedCollectionCacheBlockEntity.AreaCursor.scanFluidWindow   // :979
   └─ isSource.test(pos)                                        // :1011
        └─ CollectionCacheBlockEntity#fluidSourceIdAt(level, pos)  // :3028
             └─ level.getBlockState(pos)                          // ← 第一行
```

`Level#getBlockState` 对**未加载**区块会触发同步加载（本工程
`support/SeparationFrameGuard` 里早有同一条警告注释）。而 `fluidSourceIdAt` 第一行就直接查世界，
**没有** `level.isLoaded(pos)` 判断 —— 与本工程其它所有「读世界」的地方口径不一致
（例：`CollectionCacheBlockEntity.java:2297-2300` 先判 `isLoaded`）。

## 3. 修法

`fluidSourceIdAt` 开头加一道闸门：

```java
if (!level.isLoaded(pos)) {
    return null;   // 未加载 ⇒ 当作「这里没有流体」
}
final BlockState state = level.getBlockState(pos);
```

**为什么「未加载返回 null」是安全的**：

- 这是**只读探测**，不写世界、不抽资源（自检 ②-3 断言方法体里不含
  `setBlock` / `setBlockAndUpdate` / `destroyBlock` / `removeBlock`）；
- 区块重新加载后，下一轮扫描会正常看到它 —— 只是「这一 tick 没看见」，不丢任何东西；
- 谓词只在 `CollectionCacheBlockEntity#fluidSourcesInRadius` 里接一次
  （`pos -> fluidSourceIdAt(level, pos) != null`），跨类通过
  `AreaScanHook#scanFluidWindow(Level, int, Predicate<BlockPos>)` 传递 ⇒ **闸门只需在源头加一处**，
  区域内扫描与半径扫描两条路径同时受益。

## 4. 边界

| 场景 | 改动前 | 改动后 |
| --- | --- | --- |
| 区域全在已加载区块 | 正常 | 正常（结果完全相同） |
| 区域跨到卸载区块 | 每 tick 触发同步区块加载 | 该坐标当「没有流体」跳过，不加载 |
| 区块随后被加载 | — | 下一轮扫描正常收集，不丢资源 |
| 半径模式（基础版） | 正常 | 正常 |
| 「移除流体方块」路径 | 用同一个 `fluidSourceIdAt` | 未加载坐标不会被误判成「有源头」，因此也不会去 `setBlock` |

**没有动的同类调用点（记录，不改）**：`FEEnergyCubeBlockEntity.java:706`（充电器看邻块，
位置总在玩家附近、必然加载）、`SequenceExecutionChamberBlockEntity.java:4862 / 14188`
（机器自身/邻块判定，机器在跑就说明区块已加载）。三处都不是「跨区域大范围扫描」，
加闸门反而可能改变既有语义，因此本轮不碰。

## 5. 自检

`tools/selfcheck_round119_area_scan_isloaded.py` —— **17 条断言 / 0 失败**：

1. **源码锚点**：`isLoaded` 闸门必须出现在 `getBlockState` **之前**（顺序断言 —— 顺序反了等于没修）；
   未加载分支确实 `return null`；注释写清「getBlockState 会同步加载区块」的机制（防止后人删掉）。
2. **范围锚点**：区域扫描调用链（`AreaCursor` → `Predicate<BlockPos>` → `fluidSourceIdAt`）确实接上；
   该方法只读（4 个写世界的方法名逐个断言不存在）。
3. **反例自证**：删掉闸门的旧写法判负；把闸门挪到 `getBlockState` **语句之后**也判负。
   （写这条反例时自己先踩了一次坑：从「已带闸门」的原文插入第二道闸门，原来的闸门会把它「救」成正例，
   所以反例必须从**删掉闸门的副本**出发 —— 这一点写进了脚本注释。）

## 6. 未验证

- **没有 javap 验证** MC 1.21.1 的 `Level#getBlockState` 对未加载区块的具体行为，
  依据是工程内 `SeparationFrameGuard` 的既有注释 + 上游常识；也没有实机 profile
  证明「卡顿真的少了」。
- 触发频率未测：区域多大、跨多少未加载区块、机器是否常驻加载（区块加载器）都会影响实际收益。
