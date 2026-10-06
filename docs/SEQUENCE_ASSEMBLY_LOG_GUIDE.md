# 序列装配日志：从开游戏到「实判定」的完整步骤

> 这份文档回答一件事：**怎样做，才能让 `tools/verify_single_unit_supply.py` 对当前这份构建给出「实判定」
> （而不是「日志早于本次构建，只能作基线」）**。严格照做即可拿到实判定，没有任何歧义。
>
> **2026-10-04 更新（日志自证 + 假绿修正）**：本页第 3 句话原本靠「最新 `.class` 的写盘时间」推断
> 「本次构建」，而日志里既没有 git hash 也没有编译时间 ⇒ 「日志对应哪一版源码」只能靠 mtime 猜。
> 上一轮取证就栽在这里（把一份**晚于**修复代码的日志误当成了验证证据）。
> 现在每次编译都会写入构建指纹，游戏启动时打出唯一一行 `[rscc-build]`，判定脚本据此判版本；
> 同时把「没日志 / 没诊断行 / 日志早于构建」这三种情况从「照样报 OK」改成 **FAIL**。
> 判定「这份日志能不能作证」现在只需一条命令：

```
python tools\verify_build_stamp.py
```

---

## 0. 先记住三句话（这三句解释了以前为什么老是「日志不够」）

1. **诊断日志从 2026-10-06 起默认是关着的**（用户要求「发布版默认不该刷开发日志」）。
   `RsccAssemblyDebug` 的初值来自配置 `devLogs`，**默认 `false`** —— 所以现在要抓一份带证据的日志，
   得先显式打开：游戏内 `/rs_create_compat devlogs on`（旧写法 `/rs_create_compat assemblydebug on`、
   `/rs_create_compat debug assembly on` 等价），或把配置文件里的 `devLogs` 改成 `true` 后重载配置。
   想看当前状态：输入不带 on/off 的同一条指令即可回报 on / off。
   **必要日志不受这个开关影响**：全部 WARN / 错误、启动版本行 `[rscc-build]`、会话锚点 `[rscc]`
   始终输出 —— 所以「日志里一条诊断行都没有」现在首先意味着「开关没开」，不是「链路没问题」。
2. **日志分两个文件，判定脚本两个都要。**
   * `run/logs/latest.log`：INFO 级（`[rscc-assembly]` / `[rscc-trace]` 生产事件都在这里）；
   * `run/logs/debug.log`：DEBUG 级，**RS 自己的任务行在这里**（`Created task …`、
     `Task … state changed from RUNNING to COMPLETED`）——只有它能证明「RS 任务真的跑完了」。
   本仓库的判定脚本会自动在两者（以及轮转归档）之间挑最合适的那一份，并**打印它挑了哪一份、为什么**。
3. **「实判定」的前提是：这份日志所属的 JVM 是<u>在本次编译之后</u>启动的，而且日志能自证版本。**
   脚本读会话的 JVM 启动时刻（`[rscc] diag logging ON … session=…` 锚点行）与本次编译时间比；
   同时检查日志里有没有 `[rscc-build]` 指纹行、指纹里的 `revision` 是否等于当前
   `git rev-parse --short HEAD`。三条里任一条不满足 ⇒ **FAIL**（不再静默降级成 OK）。
   ```
   [rscc-build] mod=rs_create_compat version=0.0.1-SNAPSHOT revision=e347372+dirty branch=main \
                built=2026-10-04T18:45:16+0800 sources=324 mc=1.21.1 neoforge=21.1.248 java=21.0.4
   ```
   > 指纹里的 `dirty=true` 表示编译那一刻工作区有未提交改动 —— 这是**正常状态**（边改边测），
   > 但它意味着「同一 revision 的两份构建可能不同」，因此改完代码请**重新编译**再跑。

---

## 1. 完整步骤（照着做）

### 第 1 步：编译（拿到「本次构建」的时间戳 + 刷新构建指纹）
```
powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1
```
期望输出：`===== COMPILE OK (N sources) =====`。
（这一步会把 `build/manual_compile/*.class` 的时间戳刷成「现在」，**并且**重写
`src/main/resources/build_info.properties` 构建指纹 —— 两者都是「实判定」的基准。）

### 第 2 步：**先编译、后启动游戏**
```
gradlew runClient      （或你在用的启动方式）
```
要求：**本次 JVM 必须在第 1 步之后启动**。若你在第 1 步之前就开着游戏，请**关掉重开**。
启动后请在日志里确认那一行 `[rscc-build]` 已经出现（`Select-String -Path run\logs\latest.log -Pattern '\[rscc-build\]'`）。

### 第 3 步：（首次/被关过时）确认诊断开关是开的
游戏内输入：
```
/rs_create_compat assemblydebug on
```
（等价写法：`/rs_create_compat debug assembly on`。**不需要权限**。）
默认就是 on，所以这一步通常可跳过；但它可以让你在日志里得到一条明确的
`[rscc-assembly] debug=on (source=command)` 记录。

### 第 4 步：跑一遍序列装配产线
在游戏里实际下单 / 让序列装配链路跑起来（有 `[rscc-assembly]` / `[rscc-trace]` 行产生即可）。

### 第 5 步：跑一键自检
```
python tools\verify_build_stamp.py            # 先回答「这份日志能不能作证」
python tools\verify_single_unit_supply.py     # 再做不变式 / 修复后行为判定
```

---

## 2. 脚本会打印什么（自解释，不会再含糊地说「日志不够」）

脚本开头（B 段）会打印一张**候选会话表**与一段**判定依据**，形如：

```
B) 取证日志的选择（选中谁 / 为什么 / 这次会话是不是跑在本次构建之后）
   候选会话（按会话起点从新到旧；★ = 含生产事件；任务行 = RS 自建任务数，DEBUG 级，在 debug*.log）：
     ★ run\logs\debug-1.log.gz     会话起点=2026-10-01 01:12:03 生产事件=980   任务行=3
     ★ run\logs\debug.log          会话起点=2026-10-01 00:16:31 生产事件=1417  任务行=8
       run\logs\latest.log         会话起点=2026-10-01 00:16:31 生产事件=1417  任务行=0
   选中: run\logs\debug.log（生产事件 1417 条）
   为什么选它: 含生产事件；其中又优先取「含 RS 任务行（DEBUG 级）」的那一份，再按会话起点取最新
   判定依据: 会话 JVM 启动 2026-10-01 00:16:31 vs 本次构建 2026-10-01 01:46:58
   诊断开关: 未在日志里检出开关行 ⇒ 按默认处理（默认 on，重开游戏也是开）
   ⇒ 日志会话早于本次构建（修复后行为只能作基线）
```

四行含义：

| 行 | 含义 |
| --- | --- |
| `选中` / `为什么选它` | 用的是哪一份日志、按什么规则挑出来的（含生产事件 → 优先含 RS 任务行 → 会话起点最新） |
| `判定依据` | **该会话的 JVM 启动时刻** vs **本次构建（最新 .class）时刻** |
| `诊断开关` | 从日志里读出的开关状态；读不到就按默认（on）处理 |
| `⇒` | **实判定**（会话晚于构建）还是 **基线**（会话早于构建） |

判定分两层，**两层都会真的跑、都会打印实测值**：

* **硬不变式（H）**：任何日志都必须成立（不复制 / 不销毁 / 不成对刷屏）→ `PASS` / `FAIL`；
  其中「齿轮无真实无效往返（H5）」是 ② 的**修复目标**，因此会话早于构建时归入基线层（打 `FIXED-OLD-LOG`）。
* **修复后行为（F）**：只有「会话晚于本次构建」才有意义；否则打印实测值并标 `FIXED-OLD-LOG`
  （**不假装通过**，也不会含糊其辞）。
* **B1 / B2（新增，硬判）**：
  * `B1` 日志里必须有 `[rscc-build]` 指纹行（没有 = 跑的是「加指纹之前」的构建，无法自证版本）→ FAIL；
  * `B2` 会话必须晚于本次构建（早于 = 只能作基线）→ **FAIL**
    （旧实现在这种情况下把 F/H 断言全降级成永不 FAIL 的 `FIXED-OLD-LOG`，于是**用旧构建的日志也能「全绿」**；
    要只看基线请显式加 `--allow-stale`）。
* **无日志 / 日志里没有 rscc 诊断行（新增，硬判）**：都判 **FAIL**。
  旧实现这两处都打印 `SELFCHECK OK (n checks，日志断言无数据可判)` 并 `exit 0` —— 也就是
  「什么都没查也报绿」。如果确实只想跑源码锚点（CI / 纯编码），显式加 `--allow-empty`。
* 退出码：`0` = 没有任何 `FAIL`；`1` = 有 `FAIL`。

---

## 3. 日志落在哪 / 轮转规则

| 文件 | 内容 | 轮转 |
| --- | --- | --- |
| `run/logs/latest.log` | INFO 级：`[rscc-build]`、`[rscc]`、`[rscc-assembly]`、`[rscc-trace]`、`[rscc-ledger]`、`[rscc-dedupe]` | 每次启动游戏时把上一份转成 `run/logs/<日期>-N.log.gz` |
| `run/logs/debug.log` | DEBUG 级：**含 RS 任务行**（`Created task …` / `Task … state changed …`） | 每次启动游戏时把上一份转成 `run/logs/debug-N.log.gz` |

* `<日期>` 是**上一份日志所属的日期**（例如 `2026-09-30-2.log.gz`），后缀 `-N` 越大越新。
* 归档是 `.gz`，脚本会自己解压读取；你不用手动解压。
* **不要**为了「让日志干净」去删 `run/logs/`——删掉就没有候选会话了（脚本会报「没有任何可读日志」）。

### 3.1 每一族日志行负责回答什么（2026-10-04 新增两族）

| 前缀 | 谁的 | 回答什么 |
| --- | --- | --- |
| `[rscc-build]` | `RsccBuildInfo` | **这份日志是哪一版代码跑出来的**（revision / 编译时间 / mc / neoforge / java）。每次启动**恰好一行**。 |
| `[rscc]` | `RsccDiag` | 会话起点（`session=`）、缺料档位、补发档位的默认值；`/rs_create_compat diag` 的 BEGIN/END 分节标记。 |
| `[rscc-assembly]` | `RsccAssemblyDebug` | 序列装配事件流（机器 @ 坐标 \| 步骤 / 类别 \| 资源 \| 数量 \| 结果 / 原因），含 5 秒聚合摘要。 |
| `[rscc-trace]` | `RsccAssemblyDebug` | 端到端追踪（`item/amount \| from \| event \| to \| reason \| net` 六字段）。 |
| `[rscc-ledger]` | `RsccFlowLedger` | **守恒账本**（`留存 + 销毁 = 从网络 + 从世界 − 进入网络`）：对平时**零输出**；首次失衡一条 `unbalanced (first)`、持续 3 次采样后转 `WARN (sustained)`、之后每 30 秒合并一条、恢复时一条 `balanced again`，并始终带 `peak=` 记录本会话见过的最大差额。 |
| `[rscc-dedupe]` | `UnitPatternDedupe` | **查重判定**：每一步「为什么被跳过 / 为什么不跳过」，含命中来源、槽位与判据摘要（配方 / 步序 / 操作类型 / 代表物 / **候选组**）。同一结论 5 秒内合并一条。 |

> `[rscc-ledger]` 与 `[rscc-dedupe]` 是 2026-10-04 新增：在此之前「日志里没有失衡告警」其实等于
> 「**账本一个字都没写**」，「2 步被跳过」也查不到是跟哪一张判重 —— 两处都是取证盲区，现已被堵上。

---

## 4. 一键自检命令速查

```
# ① 编译（刷新「本次构建」时间戳 + 构建指纹）
powershell -ExecutionPolicy Bypass -File tools\manual_compile.ps1

# ② 先编译、后开游戏 → 跑一遍产线 → 关掉或保持运行都行

# ③ 日志能不能作证？（指纹行存在 + revision 对得上 + 会话晚于构建）
python tools\verify_build_stamp.py

# ④ 一键判定（自动选日志 + 自动判实判定/基线）
python tools\verify_single_unit_supply.py

# ⑤ 综合诊断报告（run/rscc_diag/latest/report.md，第 0 节也会打印构建指纹）
python tools\diagnose_all.py
```

**只跑一遍产线还不够** —— 如果你要查的是「齿轮堵塞 / 多余中间产物」这类**只在特定条件下出现**的问题，
必须照 `docs\BLOCKAGE_EXPERIMENT_PROTOCOL.md` 跑那 6 个段落（每段各制造一种已知失败模式），
然后用专门的分析器取证：

```
python tools\analyze_blockage_experiment.py      # 按订单段给出 6 条带明文证据的断言
```

其它与本条相关的脚本（也都是「读日志 → 给结论」，同样会打印选中了哪一份）：

```
python tools\selfcheck_round9_feedback.py        # 轮 9 的四条反馈总览
```

---

## 5. 常见追问

**Q：我明明一直开着日志，脚本为什么说「日志早于本次构建」？**
A：这与开关无关，是**时间顺序**问题 —— 那份日志所属的 JVM 是在本次编译**之前**启动的。
   照第 1、2 步做（**先编译、后启动游戏**）即可变成实判定。

**Q：重开游戏要不要重新敲 `/rs_create_compat assemblydebug on`？**
A：不用。配置默认 `true`，重开就是开着的。指令只在你手动关过之后才需要。

**Q：`latest.log` 里怎么一条 RS 任务行都没有？**
A：因为 RS 的任务行是 DEBUG 级，落在 `debug.log`。判定脚本会把 `debug.log`（及其归档）一起纳入候选，
   并优先挑「含 RS 任务行」的那一份 —— 你不需要做任何额外操作。

**Q：能不能只给我「一句话结论」？**
A：脚本最后一行就是结论：`VERIFY OK (N checks)`（全绿）或 `VERIFY FAILED (M/N)`（含失败清单）；
   B 段那四行则说明「这次判定到底算实判定还是基线」。

**Q：脚本报 `B2 选中日志的会话必须晚于本次构建` 就是 FAIL，可我确实先编译后开游戏了？**
A：看 `判定依据` 那两个时刻。最常见的原因有三种：
   ① 编译**之后**游戏没重开（还挂在旧 JVM 上）⇒ 关掉重开；
   ② 选中的是**归档里的旧会话**（候选表里那个 `会话起点` 比本次构建早）⇒ 关掉游戏让本次会话落盘成
      `latest.log` / `debug.log` 再跑，或直接 `--log` 指向本次会话；
   ③ 只是想让脚本核对旧日志的历史基线 ⇒ 显式加 `--allow-stale`。

**Q：`python tools/verify_build_stamp.py` 和 `verify_single_unit_supply.py` 有什么区别？**
A：前者只回答「**这份日志能不能作证**」（指纹 / revision / 会话时序，4 条硬断言，几毫秒）；
   后者在「能作证」的前提下才回答「**行为对不对**」（硬不变式 + 修复后行为，50+ 条断言）。
   两个都绿才算真的验过；第一个红的时候第二个的绿没有意义。
