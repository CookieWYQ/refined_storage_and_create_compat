# 本轮工作报告 — 2026-10-04（第三轮）：把「日志取证盲区」逐条堵上

**任务**：用户要求「继续完善当前的日志」。附件（上一轮会话记录）里 §7.3 审计对自己列出的
**四条取证局限一条都没做**，因此本轮不新增玩法功能，只做「让修复效果可被日志证明」这一件事，
外加读代码时撞见的一个真 bug。

---

## 1. 为什么这件事值得单独做一轮

上一轮的结论是「精密构件齿轮堵塞 ⇒ 多余中间产物：**部分解决**（代码层防御真实且互补，
但**零运行期验证**）」。这条结论**不是因为修复没用，而是因为日志无法证明任何事**：

| 上一轮的原话 | 本轮处置 |
| ------------ | -------- |
| 「`RsccFlowLedger` **没有任何 Logger**，`unbalanced()` 只被 `/diag` 导出读取 ⇒ **「没有失衡告警」= 根本没写日志，不是「没失衡」**」 | 加**运行期审计日志**（`[rscc-ledger]`） |
| 「查重的 `Match(source,slot)` 被丢弃 ⇒ 实机那三次『2 步全跳过』**事后不可复现**」 | 加**查重判定日志**（`[rscc-dedupe]`），带命中来源 / 槽位 / 判据摘要 |
| 「**日志与源码的对应关系不可自证**：日志里没有 git hash / 编译时间，只有 `0.0.1-SNAPSHOT`」 | 加**构建指纹**（`[rscc-build]`）+ 一条校验命令 |
| 「`verify_single_unit_supply.py` … 无日志或无 rscc 行时提前退出仍报 `SELFCHECK OK`；`hard=not stale` 让部分断言在旧日志下永不 FAIL」 | **假绿修正**：三种情况一律 FAIL |

---

## 2. 新增的四族 / 一行日志

### 2.1 `[rscc-build]`：这份日志是哪一版代码跑出来的

启动时**恰好一行**：

```
[rscc-build] mod=rs_create_compat version=0.0.1-SNAPSHOT revision=e347372+dirty branch=main \
             built=2026-10-04T18:54:48+0800 sources=324 mc=1.21.1 neoforge=21.1.248 java=21.0.4
```

* 指纹由 `tools/gen_build_info.py` 生成到 `src/main/resources/build_info.properties`，
  由 **Gradle 的 `generateBuildInfo` 任务**与 **`tools/manual_compile.ps1`** 两条构建路径共同刷新
  （`RsccBuildInfo` 只读它，读不到一律降级 `?` 并额外打一条 `WARN build stamp missing`，绝不抛）。
* 字段固定顺序，`dirty=true` 表示编译那一刻工作区有未提交改动 —— 这是**正常状态**（边改边测），
  但它意味着「同一 revision 的两份构建可能不同」，所以改完代码必须**重新编译**。

### 2.2 `[rscc-ledger]`：守恒账本终于会说话

`RsccFlowLedger#tickAudit(origin, millis)`，由执行仓**每秒**调用一次（在引擎节流 `return` 之后，只读）：

| 情形 | 输出 |
| ---- | ---- |
| 对平 | **零输出**（稳态不刷屏）；若上一次是失衡，补一条 `balanced again (was N sample(s) of imbalance, peak=… on …)` |
| 首次失衡 | 立刻一条 `unbalanced (first) peak=… on … :: item:…=3, fluid:…=-1000` |
| 同一失衡连续 ≥3 次采样 | 转 `WARN unbalanced (sustained 3 samples)`（瞬时抖动不会误报） |
| 之后每 30 秒 | 合并一条 `unbalanced (same imbalance repeated n times, peak=…)` |
| 签名变化（资源 / 方向 / 差额变了） | 当作**新**失衡，重新走「首次」 |

**`peak` 是本轮特意加的**：某个尖峰若在两次采样之间自我恢复，签名不会保留它，但 `peak` 会 ——
「瞬时多打了一份」这类错误因此第一次变得可观测。审计表按来源坐标（软上限 512，对平即清除）。

### 2.3 `[rscc-dedupe]`：某一步为什么被跳过

```
[rscc-dedupe] dedupe terminal generate step=1 SKIPPED (duplicate of 序列装配执行仓 @-5,-60,6 slot 3) \
              | recipe=create:sequenced_assembly/track step=0 op=create:deploying \
                input=minecraft:iron_nugget candidates=[minecraft:iron_nugget,minecraft:zinc_nugget] requiresInput=true
```

* **只在真决策点写**：终端「生成」（`generateAssemblyPattern`）与「新建单元样板」（`CreateUnitPatternPacket`）。
  界面刷新用的只读探测（`refreshStepDuplicateCache` ← `SyncStepMachinesPacket`）**不写**，稳态零输出。
* 反向证据也有：某步开着「跳过重复」却**没有**判成重复时打 `NOT duplicate (will generate)` ——
  这是「明明看到有相同样板却没跳过」这类怀疑的唯一反证。
* 走既有 5 秒同因合并闸（复用 `reject` 的窗口机制，但独立键空间与前缀）；
  **刻意不喂 `RsccDiag.observe`**：查重与流量守恒无关，不该污染那张计数表。
* 「全部跳过」的旧汇总行也补齐了「跳过了哪些步、各自跟谁重复」。

### 2.4 `tools/verify_build_stamp.py`：日志能不能作证

四条硬断言，几毫秒跑完：

| 断言 | FAIL 的含义 |
| ---- | ---------- |
| `B1` 日志存在且非空 | 没跑过游戏 ⇒ 没有任何运行期证据（`--allow-empty` 才降级为跳过） |
| `B2` 日志里有 `[rscc-build]` 行 | 跑的是「加指纹之前」的旧构建，版本不可自证 |
| `B3` 日志 `revision` == 当前 `git rev-parse --short HEAD` | 日志来自**另一版代码** ⇒ 只能当基线 |
| `B4` 日志 mtime ≥ 本次编译时间 | 构建发生在会话**之后** ⇒ 日志同样是基线 |

---

## 3. 顺带修掉的真 bug（不在原计划内）

`AssemblyWatchdog#changeStepMachine`（玩家在监视器上「换机器」）重建 `UnitEntry` 时走了 **10 参构造器**，
漏传 `inputCandidates` ⇒ 紧凑构造器把候选组归一化成空表 ⇒ `candidatesOrRepresentative()` 退回
「只有代表物」。

**后果与 §7.1 的 bug 完全同症状，但走另一条路复发**：列车轨道的「铁粒 或 锌粒」在玩家换过一次机器
之后又变回「只要铁粒」，而且**不落任何日志** —— 玩家只会看到「锌粒又没了」，然后重新走一遍
上一轮那套排查。修法 = 把 `unit.inputCandidates()` 原样带过去（1 行）。

> 这条正好印证了本轮的主题：**没有日志的代码路径，等于没有修过。**

---

## 4. 假绿修正（`verify_single_unit_supply.py`）

| 旧行为 | 新行为 |
| ------ | ------ |
| `run/logs` 下没有任何日志 → `SELFCHECK OK (...)` + `exit 0` | **FAIL**（`--allow-empty` 才降级） |
| 日志里没有 rscc 诊断行 → `SELFCHECK OK (...)` + `exit 0` | **FAIL**（`--allow-empty` 才降级） |
| 会话早于构建 → 所有修复后断言降级 `FIXED-OLD-LOG`，**永不 FAIL** | **FAIL**（`--allow-stale` 才只看基线），并打印 `BASELINE-ONLY` 横幅 |
| 版本不可自证 | 新增 `B1` 断言：必须有 `[rscc-build]` 行 |

实测（当前磁盘上的旧日志，跑的是 14:31 的会话、本次构建 18:54）：

```
VERIFY FAILED (2/53)
  - B1 日志自证版本（日志里出现启动指纹行 [rscc-build] …）
  - B2 选中日志的会话必须晚于本次构建（… 会话 JVM 启动 2026-10-04 14:31:15 vs 本次构建 2026-10-04 18:54:09）
```

**这正是本轮要的效果**：升级前那份日志再也无法「全绿」。

---

## 5. 顺带发现并修掉的两类基础设施问题

### 5.1 20 个自检脚本在 GBK 控制台下会「崩在最后一行」

`tools/selfcheck_*.py` 大量打印 `⇒` / `−` / `∉` / 圈码，Windows 控制台默认 GBK 会抛
`UnicodeEncodeError`，把脚本**打断在最后一行** —— 于是「检查其实全过」看起来也是失败。
本轮给 45 个 `selfcheck_*/verify_*` 脚本统一加了 stdout UTF-8 安全网，现在
**37 / 37 全部 exit 0**（`verify_single_unit_supply.py` 与 `verify_build_stamp.py` 按设计判 FAIL）。

> **教训**：判断自检结论必须**看退出码**，不要只看有没有刷红。

### 5.2 一条长期 FAIL 的过期锚点

`tools/selfcheck_assembly_cancel_immediate.py` 钉的是
`items.merge(data.ingredient().getItem(), …)`，而 §7.1 已把 `collectInputs` 改成按候选组聚合
（`Map<InputGroupKey, Long>` + `mergeInput`）⇒ 该断言**长期红**。已改为钉当前实现
（单候选组并入 + 不随 loops 放大），并补一条「按候选组聚合而非按单物品」的守护断言。

### 5.3 PowerShell 脚本必须带 BOM（新硬约定）

`tools/manual_compile.ps1` 在本轮被编辑工具保存成 **UTF-8 无 BOM** 后直接坏掉：
Windows PowerShell 5.1 按 GBK 解析中文注释 → `$root` 变成 `null` → 第 17 行崩。
已恢复 BOM 并写进 `TECHNICAL_HANDOFF.md` §5 硬约定第 11 条。

---

## 6. 验证记录（本轮实跑）

```
tools\manual_compile.ps1             → COMPILE OK (324 sources)
python tools\gen_build_info.py       → BUILD STAMP WRITTEN（revision=e347372 dirty=true sources=324）
python tools\gen_build_info.py --check → BUILD STAMP OK
python tools\verify_build_stamp.py   → FAIL(1/2)：旧日志无 [rscc-build] 行（设计内）
python tools\verify_single_unit_supply.py → FAIL(2/53)：B1 / B2（设计内）
python tools\diagnose_all.py         → exit 0；报告第 0 节出现构建指纹与「是否等于当前 HEAD」
37 个 selfcheck_*.py                 → 0 失败 / 37（修完 20 处编码 + 1 处过期锚点之后）
```

---

## 7. 仍未做（明确交接）

1. **「在制过渡件数量 vs 订单量」指标** —— §7.3 指出守恒式**结构上测不出**「多余中间产物」
   （多喂一份起步原料与多产一件过渡件都恰好平账）。本轮只让账本会说话，**没有**改变这条数学事实。
2. **`FACE` 输出模式补闸门**（`TECHNICAL_HANDOFF.md` §6.1 第 4 条）。
3. **`EXECUTOR_OFFLINE` 指向不存在坐标 `(-5,-60,10)` 的根因**（§6.1 第 5 条）。
4. **`SequencePatternTerminalScreen` 两个候选缓存加清空**（§6.1 第 6 条）。
5. **实机验证**（只有你能做，脚本侧已就绪，步骤见 §8）。

---

## 8. 你需要做的最小动作

```
1) powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1   # 刷新构建指纹
2) 关掉游戏、重新启动（必须晚于第 1 步）
3) 确认日志出现 [rscc-build]；跑一遍列车轨道 + 精密构件产线
4) python tools\verify_build_stamp.py            # 应该 OK
5) python tools\verify_single_unit_supply.py     # 看 B1/B2 是否转绿 + 行为断言
```

想快速确认新增的三族日志有没有生效：

```powershell
Select-String -Path run\logs\latest.log -Pattern '\[rscc-build\]|\[rscc-ledger\]|\[rscc-dedupe\]'
```

预期：`[rscc-build]` **一定有**（1 行）；`[rscc-ledger]` 只在**真的失衡**时才出现；
`[rscc-dedupe]` 只在**生成 / 新建样板**时出现。
