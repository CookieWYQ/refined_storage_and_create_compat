# 第 119 轮：缓存池读数求和改为饱和加法（`support/SaturatingMath`）

## 1. 现象（玩家侧）

没有任何报错、没有崩溃、日志里也没有异常 —— 只是**中间缓存仓所在的机器「没有任何反应」**：

- 网络里明明有原料，中间产物一件都不往缓存池里搬；
- 缓存仓里插的是容量很大的盘（尤其「无限容量」类磁盘）时更容易出现；
- 把其中一块盘拿掉（块数变少）以后又能正常工作 —— 这是最典型的「看起来像玄学」的特征。

## 2. 根因

`RsccSharedCache#poolFreeSpace` 是 `RsccIntermediateFlow` 的**闸门 A**：

```java
long free = 0L;
for (final SerializableStorage each : storages(level, network)) {
    free += remainingOf(each);        // ← 裸加法
}
return free;                          // 调用方判 free > 0
```

`remainingOf()` 对「无限容量盘」返回常量 `UNLIMITED_REMAINING = Long.MAX_VALUE / 4`（≈ 2.3×10¹⁸）。
把 5 块这样的盘逐个相加就会**回绕成负数**（`Long` 溢出），于是 `free > 0` 判定为 false ⇒
闸门 A 认为「池子满了」⇒ 一件都不抽。溢出发生在加法本身，不会抛异常，也不会有日志。

同一文件里 `poolStored()`（对照读数）、`poolContents()`（按资源合并存量）、`describe()`（诊断摘要）
用的是同一种裸 `+=` / `Long::sum`，触发条件相同。

另外，`UNLIMITED_REMAINING` 上的原注释写着「足够大又不会在别处做加法时溢出」——
这句话本身就是错的（5 块盘就溢出），属于「注释与实现不符」这一类问题，本轮一并改正。

## 3. 修法

新增纯 JDK 类 `support/SaturatingMath`：

```java
public static long add(final long a, final long b) {
    final long left = Math.max(0L, a);
    final long right = Math.max(0L, b);
    if (left > Long.MAX_VALUE - right) {   // 减法判溢出：加法形式会先自己回绕
        return Long.MAX_VALUE;
    }
    return left + right;
}
```

- **为什么用减法判溢出**：`left + right > Long.MAX_VALUE` 这个写法本身就会先溢出成负数，永远判不成立；
  `left > Long.MAX_VALUE - right` 不依赖加法，是唯一安全的判定形式。
- **为什么负输入按 0**：本模组里容量 / 件数 / mB 都是非负量，负值只可能来自上游已经出错的读数，
  当 0 处理比把它传播进「够不够 / 满没满」的判断更安全。
- 四处求和全部改走它：`poolFreeSpace` / `poolStored` / `poolContents`（`SaturatingMath::add`）/ `describe`。

**没有改读数口径**：`UNLIMITED_REMAINING` 仍是 `Long.MAX_VALUE / 4`，界面上的「∞」判定、
`capacityOf` 返回 -1 的约定、闸门 A 的语义（`> 0` 才抽）全部不变。本次只改「怎么加」。

## 4. 边界与风险

| 场景 | 改动前 | 改动后 |
| --- | --- | --- |
| 1～4 块无限盘 | 正常 | 正常（结果数值完全相同） |
| ≥5 块无限盘 | **和变负 ⇒ 闸门 A 误判「满」⇒ 不搬** | 和饱和到 `Long.MAX_VALUE` ⇒ 正常搬 |
| 普通盘（有限容量） | 正常 | 正常（未接近 `Long.MAX_VALUE` 时数值相同） |
| 上游别处传入负读数 | 负数会在和里抵消别的盘 | 该读数按 0 计（更保守） |
| 真的要表达「池子满了」 | `free == 0` | `free == 0`（饱和只在极大值一侧，不影响 0） |

## 5. 自检

`tools/selfcheck_round119_pool_saturate.py` —— **30 条断言 / 0 失败**，四条腿：

1. **源码锚点**：三处读数 + 诊断摘要都走饱和加法；文件里不再有 `+= remainingOf` / `+= storedOf` /
   `Long::sum`；纯 JDK；减法判溢出；错误注释已改正且取值未变。
2. **真代码 JVM 探针**（javac 编译 `SaturatingMath` 后真跑，**17 条**）：`MAX+1`、`MAX+MAX`、
   5 / 64 块无限盘不为负、负输入按 0、随机 2 万组「结果不小于任一加数且永不为负」。
3. **行为模型**：1/2/4/5/8/16 块盘与混合盘下闸门 A 都不会翻转；真满了（全 0）仍判「满」。
4. **反例自证**：把旧的裸 `+=` 拼进模型，断言它在 **5 块**盘时确实溢出为负、在 **4 块**时不溢出
   —— 证明判据不是摆设，也解释了为什么这个 bug 只在盘数较多时出现。

## 6. 未验证

- **没有实机摆 5 块无限盘验证**。触发面是按 `Long` 溢出 + `remainingOf` 的返回值推导的，
  没有在真实整合包里确认「无限容量盘」的实际数量上限（多数整合包给的是有限容量磁盘，
  因此这个 bug 在普通存档里大概率摸不到；但修复本身对普通盘是零风险）。
- 没有统计除本源之外是否还有别处对同类读数做裸求和（本轮只扫了 `support/` 与
  `RsccIntermediateFlow` 的调用链）。

### 6.1 补充：全仓 `Long::sum` / 裸求和扫描结果（本轮已做）

`grep -rn "Long::sum|+= remainingOf|+= storedOf|Long.MAX_VALUE / 4" src/main/java/` 之后逐条确认：
除 `RsccSharedCache`（本轮已修）以外，其余 `Long::sum` 合并的都是**真实件数 / mB / 计数**
（`NetworkResourceReceiver`、`RsccDiskItemHandler`、`SequenceAssemblyPatternItem`、
`AssemblyWatchdog`、`NetworkEnergyAudit`、`SequenceMaterialGuard` 等），
要让它们溢出需要合计 2⁶³ 量级的物品，正常存档不可能达到 —— **不需要改**。
唯一把「哨兵大数」（`Long.MAX_VALUE / 4`）放进求和的只有 `RsccSharedCache` 这一处，
已在本轮全部改为饱和加法。

