# 第 110 轮：拼音搜索 / 方块类别 / 能量立方手持 / 区域命名（beta.12 合集）

本轮把用户第 110 条实机反馈的四件事逐条落地；第 109 轮的两件（背包精确电量显示、低电阈值按分钟）
一并进 beta.12。本文记录**结论、证据与验收口径**；像素/观感类结论如实标注「未实机验证」。

---

## A. 范围充电器过滤：接入「通用拼音搜索」（Just Enough Characters）

### A.1 事实（反编译 + 实机模组证据）

- 用户口中的「通用拼音搜索」= **Just Enough Characters (JEC)**，`modid=jecharacters`，
  本机 `jecharacters-1.21.1-neoforge-4.5.29.jar`（Modrinth 4.5.29，MIT，客户端模组）。
- `Searchables` 只是 BlameJared 的搜索栏库（Controlling 的 CLIENT 依赖），**与拼音无关**。
- JEC **不是 mixin 到 `EditBox`**：它是 coremod，按 `targets.json` 的「类名+方法名+描述符」清单
  逐方法把 `String.contains/equals/startsWith/regex` 调用改写成 `Match.contains(...)`；
  原版创造背包 / JEI 另走 `SearchTree.plainText → FakeArray`。我们的手写搜索循环不在清单里，
  所以以前完全不会被注入 —— 这就是「为什么拼音只管原版搜索框」。
- JEC 的公开入口：`me.towdium.jecharacters.utils.Match.contains(CharSequence, CharSequence)`
  （内部 = `PinIn.contains(text, query)`，带字典 / 多音字 / 简拼 / 玩家配置的模糊音）。
- **实测坑**：JEC 对 ASCII **大小写敏感**（`Match.contains("Controller","controller") = false`，
  `Match.contains("控制器","KZQ") = false`）。不先把两边 lower 就传进去，会出现
  「装了拼音模组后英文名 / 大写首字母搜不到，没装反而能搜到」的行为回归。

### A.2 实现

新增 `client/PinyinSearch.java`（**零编译期依赖**）：

- 运行时 `Class.forName("me.towdium.jecharacters.utils.Match", false, loader)`
  （先 context classloader 再自身 classloader）；
- 签名按 `(String, CharSequence)` → `(CharSequence, CharSequence)` → `(String, String)` 依次尝试；
- **两边先 `toLowerCase(Locale.ROOT)`** 再 `invoke`（中文不受影响；见 A.1 实测）；
- 每次调用 `try/catch Throwable`，失败静默退回不区分大小写子串；解析结果静态缓存（只反射一次）。

接线：`ChargerFilterCandidates#search`（掉落物 / 玩家身上物品两页）与
`ChargerFilterScreen` 的方块页搜索，名字一路走 `PinyinSearch.contains(name, needle)`，
注册 id 一路仍是不区分大小写子串（ASCII，没有拼音语义）。搜索框提示文案补了「支持拼音」。

**不做的**：不加 compileOnly 依赖（JEC 不在 Gradle 缓存，会破坏离线 javac 校验链路）、
不 mixin `EditBox`（JEC 根本没这么做）、不把 Searchables 当拼音库。

自检：`tools/selfcheck_round110_charger_pinyin_search.py`（20 断言 / 10 反例，含本机 jar 内
`Match.class` 存在性证据；模组缺席时只 WARN）。

---

## B. 范围充电器过滤：新增「世界中的方块」类别（第 4 页签）

### B.1 用户口径

> 「你现在有几个类别：玩家身上的（物品）以及掉落物，但还有一个东西你忘了 —— 世界中的方块，
>  就是放在地上的方块，比如控制器、放在地上的能量立方。这个类别也很重要。」

### B.2 现状与修法

方块充电功能其实**早已存在**（`Config.rangeChargerChargeBlocks` + `scanBlocks`/`chargeBlock`），
但过滤只有三类名单（掉落物 / 玩家身上物品 / 玩家），`chargeBlock` 也**没有走任何过滤**，
界面上更没有方块页签。

- `ChargingFilter`：新增第 4 类名单 `blocks`（Block 注册名）+ `allowsBlock(Block)`（与物品两类
  共用同一个私有 `allows()`，黑白名单语义一致）；NBT 增加 `blocks` 键（缺字段 = 空 = 什么都不拦，
  旧存档不惊讶）；保留五参 `of(...)` 重载（既有调用方与 JVM 探针不受影响）。
- `ChargerFilterLimits.MAX_BLOCK_ENTRIES = 1024`（兼作界面候选池上界）。
- `RangeChargerBlockEntity#chargeBlock`：顺序改为「登记候选 → `allowsBlock` 门控 → 满电跳过 →
  预算 → 充电 → 高亮」；被拦的方块**连目标都不算**（不登记高亮、不计 `lastChargedTargets`）。
  既有 `transferBudget` / 满电跳过 / 守恒语义**一字未改**。新增有界 `seenBlocks` 候选池每 tick 重建。
- 网络：`SyncChargerFilterPacket` 增加 `blocks` + `seenBlocks`（编码 clamp、解码只留前 N、
  不预分配）；`SetChargerFilterPacket` 复用同一份编解码。
- 界面：新增 `Tab.BLOCKS`（四个页签，单签宽度 74→56，右缘 240<242）；行 = 候选池 ∪ 已配置
  （去重 —— 否则拉黑后行消失、取消不了勾选）；图标用 `new ItemStack(block.asItem())`（空气跳过）；
  搜索名字走 `PinyinSearch`；tooltip / 空态 / 搜索提示齐全。

### B.3 已知取舍（如实说明）

1. 候选池只含**本轮扫描到的**方块（扫描在 `maxTargets` / 电量耗尽处会提前 break，既有行为）；
2. 快照不实时刷新：界面开着时新放下的方块要重开界面才出现（未扩协议新增请求包）；
3. `asItem()` 是空气的方块列不出来；已配置但无物品的 id 仍会被服务端过滤生效，但界面上取消不了
   （只能改 NBT）—— 若用户需要「手动删除入口」，下一轮补。

自检：`tools/selfcheck_round110_charger_block_filter.py`（19 断言 / 11 反例 + 真代码 JVM 探针 21 例）；
`round98` 仅把「页签数 3→4」「`allows*` 调用点 5→6」两处锚点按合法扩展更新（判据未削弱）。

---

## C. FE 能量立方：物品形态手持显示 / 充电

### C.1 用户口径

> 「能量立方应该和控制器一样有那个能量条（显示在下面、类似耐久条、储存的是电量），
>  像终端一样有电量条。现在拿在手上没有电量条，也充不了电、看不到它有多少电量。
>  拿在手上应该能显示当前电量，而且不管有没有按 Shift 都要显示 —— 看 tooltip 就行。」

### C.2 关键事实（逐环核实）

- 方块破坏时 `FEEnergyCubeBlock#getDrops` 用 `BlockEntity#saveToItem` 把电量写进物品的
  `BLOCK_ENTITY_DATA`，放置时原版 `BlockItem.updateCustomBlockEntityTag → loadWithComponents /
  loadAdditional` 还原 —— **物品上本来就有电量数据**，只是物品形态不显示、不提供能力。
- 1.21.1 的 `block_entity_data` 组件 codec 是 `CustomData.CODEC_WITH_ID`（缺 `id` 直接解码失败）
  ⇒ 写回**必须**走 `BlockItem.setBlockEntityData`（它负责 remove("id")→addEntityType），
  手搓 `CustomData.of(tag)` 会写出「放下丢电」的坏物品。
- 该组件是 `networkSynchronized` ⇒ 客户端 tooltip 读到的就是服务端真实电量，不需要另开同步包。

### C.3 实现

- 新类 `item/FEEnergyCubeItem`（extends `RsccHelpBlockItem`，注册名 / help 文本 / 模型均不变）：
  - 注册 `Capabilities.EnergyStorage.ITEM`（只注册一次，主类 MOD 总线监听一次）；
  - 电量读写**与方块共用同一个键** `FEEnergyCubeBlockEntity.TAG_ENERGY`（不另开存储，放下即带过去）；
  - 物品栏电量条：`isBarVisible`（未满才画）/ `getBarWidth`（13px 比例）/ `getBarColor`
    `0x18C8AA`（= `tools/gen_fe_energy_cube_assets.py` 的 ACCENT 青绿，注释写清出处）；
  - tooltip：先 `super.appendHoverText`（保留 RS 常显帮助），再**无条件**追加精确电量
    （`2,000,000 / 50,000,000 FE`，复用第 109 轮 `formatFeExact`，**没有** Shift / `isAdvanced` 门控），
    另两行说明「物品只代表这一块、放下后才与相邻立方并池」「可被充电器充电 / 被抽取，上限 %s FE/t」。
- 新类 `support/EnergyCubeItemEnergy`（IEnergyStorage 视图，同 `JetpackEnergyStorage` 写法）。
- **堆叠守卫（新发现并堵死的复制漏洞）**：电量在组件上 = 整堆共享一份。允许一堆充放电会被
  「拆堆（copyWithCount 复制组件）/ 放置（读整堆那份 + shrink(1) 不清组件）」放大 ⇒
  一堆 64 块 × 100,000 FE 能变成 64 个 100,000 FE 的方块。现在 `count > 1` 时
  `canReceive/canExtract` 与两个读写口**全部拒绝**，tooltip 提示「拆成单块才能充」；
  **堆叠上限未改**（空立方照旧能成堆）。
- 范围充电器**无需改动**即生效（`chargeItemStack` 只看能力 + 是否满 + 本机是否没电）。

自检：`tools/selfcheck_round110_energy_cube_item.py`（27 断言 / 31 反例，含「自有键 ⇒ 放下变 0」
行为模型与堆叠守卫）；`round99_energy_cube`（74/60）锚点一条未改、全绿。

---

## D. 区域选定器：区域命名真正提交 + 「确定」按钮

### D.1 用户口径

> 「区域命名不能正常生效，而且并没有一个按钮让我点一下确定。按回车它显示改了，
>  但再次进入还是没有改。」

### D.2 根因

旧实现只有 `scheduleRename()` 的 **500ms 防抖** 提交：`flushRename()` 只在 `render()` 里驱动，
未到点就 return；而原版 `EditBox` **不处理回车**、`removed()` **没有** flush。
因此「改名字 → 回车 → 关界面」只要关得比防抖快，`AreaSelectorEditPacket(ACTION_RENAME, ...)`
**根本没发出去**；界面列表显示的是客户端乐观副本，于是「看起来改了」，重新打开（从服务端权威
数据重建）就变回旧名字。

### D.3 修法

- 名称框右侧新增「确定」按钮（`name.apply`，名称框宽度 106→78、按钮 26×14，右缘 310<320，
  带 tooltip 说明「立即提交 / 回车同效 / 关界面也会自动提交」）；
- `applyNameNow()`：把名称框文本写进 `pendingRename`、`renameAtMs = 0`、立刻 `flushRename()` ——
  与防抖路径**共用同一个发包函数**，不分叉；
- 回车 / 小键盘回车（名称框聚焦时）→ `applyNameNow()`（原版 EditBox 不处理回车）；
- `removed()`（关界面）在 `super.removed()` 之前 `applyNameNow()`；
- 切换选中行时先把上一行未提交的名字 flush 掉（`selectRow`）；
- 提交链路不变：仍是 `ACTION_RENAME` → 服务端 `selection.rename(index, packet.name())` →
  `AreaSelection.set(target, selection)` 写回物品（服务端权威）。

自检：`tools/selfcheck_round110_area_selector_name.py`（20 断言 / 11 反例，含行为模型
「300ms 就关界面：旧实现 0 个包（名字丢）→ 新实现 1 个包」）。

---

## E. README 鸣谢

按用户要求，`README.md` / `README_en.md` 的「依赖与致谢」章节重写为三张表：

1. **直接依赖 / 调用 API / 复用其代码或资源** —— Refined Storage 2（MIT）、Quartz Arsenal、
   Create（MIT）、Ponder（MIT）、Catnip（MIT）、Jade（CC-BY-NC-SA-4.0）、JEI（MIT）、
   Curios（LGPL-3.0-or-later）、RS Curios Integration、Just Enough Characters（MIT）、
   NeoForge、FTB Ultimine —— 每行标注作者、许可证、**具体用法**与仓库链接；
2. **只做反编译对照 / 排障（未使用其代码或资源）** —— Sophisticated Backpacks / Core
   （All Rights Reserved，仅排障）、Minecraft（Mojang）；
3. **资源说明** —— 方块贴图均为本仓库生成器程序化绘制；唯一直接引用的既有贴图资源是
   Refined Storage 的 GUI 精灵（MIT）。

---

## F. 验收（beta.12）

- 编译：`LEAD_COMPILE_OK sources=495`
- 全量自检：**156 个脚本 / 0 失败**（使用带 Pillow 的 python；新增
  `round110_charger_pinyin_search` 20 断言 / 10 反例、`round110_charger_block_filter`
  19/11 + JVM 探针 21 例、`round110_energy_cube_item` 27/31、`round110_area_selector_name` 20/11；
  `round109_jetpack_fe_display` 34/16；`round99_jetpack` 87/42、`round99_energy_cube` 74/60 全绿）
- 专服探针：**PASS**（`Done (3.015s)! For help, type "help"`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
- 部署：本地实例 / SFTP 服务端 / SFTP 客户端三处 sha256 一致（`DEPLOY_VERIFY_OK`，
  `379376103216551fe919f4a0f8fc62a74146095381209c3749ffadc0ab9ca0ad`，2 762 628 B），旧 jar 就地改名 `.disabled`

## G. 未实机验证（如实交代）

- 拼音搜索：代码路径 + 行为模型 + JEC jar 证据齐全，但**没有在游戏里真敲一次**（需要客户端热加载）；
  最坏情况是 JEC 反射失败 ⇒ 自动退回子串搜索，不会崩。
- 方块页签：页签压到 56px 后中文 6 字「玩家身上物品」是否贴边只有几何推算；
  候选池不实时刷新、无物品方块列不出来（见 B.3）。
- 能量立方：物品电量与方块同键已由源码级逐环核实，但「拿在手上充 → 放下方块电量还在」
  没有实机跑过；堆叠守卫会让「一堆立方」不能充放电（tooltip 会提示）。
- 区域命名：「关界面强制提交」由行为模型证明，没有实机重命名一次再重开。
