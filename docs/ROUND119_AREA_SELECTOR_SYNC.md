# 第 119 轮：区域选定器「静默失效」三连修复（C-1 / C-2 / C-3）

| 项 | 内容 |
| --- | --- |
| 依据 | `docs/ROUND119_AUDIT_AREA_MARKS.md` C 节（只读对抗式审查），第 221～272 行 |
| 范围 | 只修 C-1 / C-2 / C-3 三条「静默失效」；不新增功能、不改产品行为、不动存档格式 / 网络契约 |
| 自检 | `tools/selfcheck_round119_area_selector_sync.py`：**49 条断言（其中 21 条反例），退出码 0** |
| 编译 | **未跑 gradle**（按纪律由 Lead 统一编译）。另用 `javac -proc:none` + 本机依赖 classpath 对 4 个改动的 Java 文件做了单文件类型检查（exit 0，产物只写临时目录，已删除）；这不等于完整构建通过。本文件行号以本轮改完后的源码为准 |
| 实机 | **未实机点界面**（见文末「未验证」） |

三条问题共用同一个根：**客户端为了「点完立刻有反应」会先改自己那份物品副本（乐观显示），
而服务端才是权威**。只要「被拒之后客户端副本没有被纠正」或「成功提示比服务端确认更早」，
玩家看到的就是「点了没反应」或「提示与事实相反」。因此本轮的验收口径是三条：

1. **菜单路径绝不回退主手**（两个入口同一条判据）；
2. **被拒一定回发权威状态**（不是「记录一下原因」就算）；
3. **成功提示只在成功路径上出现**（预检拦住的路径不再声称成功）。

---

## C-1【中】菜单路径被拒后客户端乐观副本残留

### 现象

在机器界面里打开区域列表（`menu != null`），点「改坐标 / 改名」后界面上的数字 / 名字变了，
但服务端其实拒绝了（维度不符 / 几何重复 / 区域不存在 / 越界 / 上限）。之后给这个「改过的区域」
配过滤标记，界面弹「已加入」，可标记并没有落到任何区域上 —— 玩家感知是「标记坏了」。

### 根因

链路（第 119 轮审计原文，行号为本轮修改前）：

1. 客户端发包前先乐观改本地副本：`AreaSelectorScreen.flushApply` / `flushRename`（`AreaSelectorScreen.java:509-518` 等）；
2. 服务端拒绝：`AreaSelectorEditPacket.reject`（`AreaSelectorEditPacket.java:311-319`）
   → `menu.noteAreaReject(reason)` + `sync(...)`；
3. `sync` 的菜单分支只做 `onAreaSelectorEdited()` + `broadcastChanges()`
   （`AreaSelectorTargets.java:66-72`）；
4. **`broadcastChanges()` 只发「槽内容与上次发出的快照不同」的槽**
   （原版 `AbstractContainerMenu#broadcastChanges` → `synchronizeSlotToRemote`，
   本机反编译源 `build/probe_jars/mcneo/net/minecraft/world/inventory/AbstractContainerMenu.java:167-186`）。
   拒绝时服务端物品**本来就没变** ⇒ 一个槽包都不发 ⇒ 客户端那份乐观副本留在界面上；
5. 接着配标记：`AreaMarkEditPacket` 用服务端**不存在**的几何 → `findArea` 返回 `null`
   → 静默 `return`（`AreaMarkEditPacket.java:101-104`、`:163-170`），而界面已经弹了「已加入」
   （`AreaMarkScreen.java:244-248`）。

### 修法

`AreaSelectorEditPacket.reject` 的**菜单分支**补一次 `menu.broadcastFullState()`
（`AreaSelectorEditPacket.java:318-329`，实际调用在 `:328`）：

```java
menu.noteAreaReject(reason);
sync(player, packet.containerId());
// 菜单路径的 sync 只调 broadcastChanges()；被拒时槽内容没变 ⇒ 不发槽包 ⇒ 客户端乐观副本残留。
// 补一次全量回发（ClientboundContainerSetContentPacket）把客户端副本纠回服务端权威值。
menu.broadcastFullState();
```

- 选 `broadcastFullState()` 而不是 `player.connection.send(...)`：与第 113 轮 D7 的**主手分支**
  （`player.inventoryMenu.broadcastFullState()`，`AreaSelectorEditPacket.java:320-322`）同一套做法，
  不新增网络契约。原版实现里 `broadcastFullState()` → `sendAllDataToRemote()` → 整份
  `ClientboundContainerSetContentPacket`（本机反编译源 `AbstractContainerMenu.java:188-202`）。
- **只在拒绝时调一次**：没有放进 `AreaSelectorTargets#sync`（`:66-77`），因为那条路径每次**成功**写入
  也会走；成功时 `broadcastChanges()` 已经能把变了的槽发出去，不需要全量刷。
- 拒绝原因仍走原来的快照通道（`noteAreaReject` → `AdvancedCollectionCacheMenu#broadcastChanges`
  `:228-259` 在 `areaRejectSeq` 变大时补发 `AdvancedAreaSnapshotPacket`），
  所以「原因一句话」和「权威物品」现在会一起到客户端。

### 边界

- 只影响**服务端拒绝**这一条支路；成功写入的带宽不变（仍是 `broadcastChanges`）。
- 连续被拒 N 次 = 最多 N 次全量回发（每次都是玩家点了一下），不是每 tick。
- 不改变包结构、字段、存档格式；旧客户端 / 旧存档不受影响。
- 主手路径行为不变（第 113 轮 D7 已经在做同一件事）。

### 未验证

- 没有实机确认「拒绝后界面数字在一帧内回弹」的体感（只做了源码锚点 + 行为模型）。
- 没有实机验证 NeoForge 1.21.1 下 `broadcastFullState()` 与同一 tick 内
  `AdvancedAreaSnapshotPacket` 的到达顺序（模型只断言「最终客户端副本 == 服务端权威」，不断言帧数）。

---

## C-2【中】菜单开着、区域槽为空时，区域列表「读主手、写菜单槽」

### 现象

机器界面里的「区域」按钮是**无条件**可点的（`AdvancedCollectionCacheScreen.java:128-175`）。
专用槽空着也能打开区域列表；列表里显示的是**主手**那支选定器的区域（如果主手正好拿着），
但所有编辑包带的是**菜单 id** ⇒ 服务端按菜单解析到空槽 ⇒ 每个编辑都静默丢包，
而且连 sync 都没有（`AreaSelectorEditPacket.java:137-140`）。玩家看到的是「列表有内容、点了没反应」。

### 根因

- `AreaSelectorScreen.targetStack()`（本轮修改前 `:240-254`）：`menu != null` 且槽里有选定器 → 读槽；
  **否则回退主手**；
- `containerId()` 仍返回 `menu.containerId`（`:261-263`），写包带的就是它；
- 服务端 `AreaSelectorTargets.resolve`：`containerId >= 0` ⇒ 只认菜单槽，槽空 ⇒ `EMPTY` ⇒ 丢包
  （`AreaSelectorTargets.java:39-44`）；
- 目标为空时处理体直接 `return`，连 sync 都不做（`AreaSelectorEditPacket.java:137-140`）。

对照：`AreaMarkScreen` 在 D10-f 已经改成「菜单路径不回退主手」（`AreaMarkScreen.java:132-145`），
**区域列表界面没跟着改** ⇒ 两个入口口径不一致。

### 修法

1. `AreaSelectorScreen.targetStack()` 在 `menu != null` 时**只认菜单专用槽**：
   槽空 / 槽里不是选定器 / 菜单已经不是当前打开的菜单 ⇒ 返回 `null`，**不回退主手**
   （`AreaSelectorScreen.java:242-268`）。后半条与服端 `AreaSelectorTargets.menuOf`
   （`:49-55`）的「必须正是当前打开的菜单」对齐。
2. `selectionStack()` 改为**委托** `targetStack()`，删掉原来那份独立的「菜单槽优先、否则主手」逻辑
   （`AreaSelectorScreen.java:731-740`）——两份判据分叉正是 C-2 的成因。
3. 槽空时**不再发注定被丢的包**：
   - 「新建」按钮要求 `targetStack() != null`（`:380-385`）；
   - `createArea()` 先判 `targetStack() == null` 再继续（`:596-600`）。
4. 界面提示：状态 0（菜单专用槽里没有选定器）画 `status.no_slot`
   ——「请把区域选定器放进机器左下角的专用槽」（`AreaSelectorScreen.java:1032-1036`；
   语言键 `gui.rs_create_compat.area_selector.status.no_slot`，zh/en 都有）。
5. 标记界面同口径：`AreaMarkScreen.targetStack()` 也校验「槽里确实是选定器」
   （`AreaMarkScreen.java:127-145`）；新增 `menuTargetMissing()`（`:147-160`），
   加 / 删 / 清三个入口都先拦（`:218`、`:274`、`:297`）——否则菜单槽空时仍会发意图并弹假的
   「已加入 / 已移除」。

关于「复用 `area_slot.empty`」：这个键是**机器界面槽位的名称**（`AdvancedCollectionCacheScreen.java:515`，
zh 值就是「区域选定器槽」），`area_slot.empty.tip` 讲的是吸收语义；把名词短语当列表底部的整句提示
会读起来不成立。因此新增一句 `status.no_slot`，并让标记界面复用**同一个**键（不复制文案）。

### 边界

- 主手路径（`menu == null`）完全不变：仍然读主手，仍然能打开列表。
- 菜单路径正常（槽里有选定器）时行为不变：仍读专用槽、仍用服务端快照。
- 槽空时界面现在是「空列表 + 明确提示 + 新建置灰」，而不是「显示主手数据、点了没反应」。
- 没装/不是选定器的槽内容：客户端按服务端 `resolve` 同一条判据（`inSlot.is(AREA_SELECTOR)`）拒绝。
- 不改网络契约、不改存档。

### 未验证

- 没有实机验证「从机器打开、槽空」时提示文字在 176px 列表宽度内的实际缩放 / 排版
  （`drawListNote` 走 `RsccTextFit.drawFitLine`，只缩字号不省略，但没看实机）。
- 没有实机验证「菜单被别的路径关掉、界面还开着」这一帧的边界（客户端现在直接返回 null）。

---

## C-3【低】手持路径不做「去重 / 维度」预检，且先弹「已提交」

### 现象

手持选定器，右键方块两次框同一片地（最自然的手滑）→ 服务端以 `REJECT_DUPLICATE` 拒绝 →
主手分支只 `broadcastFullState()`、**不弹任何文字**（`AreaSelectorEditPacket.java:320-322`）⇒
玩家看到的是「已提交区域：N 格」。维度不符时同理（服务端 `REJECT_DIMENSION`）。

### 根因

- `AreaSelectorHandler.onMouseButton`（本轮修改前 `:228-250`）：只校验 `invalidReason` 与区域个数，
  **没有** `hasGeometry` / 维度预检，发完包立刻 `message(MSG + "committed", ...)`；
- 服务端 ADD 分支会按「上限 / 几何非法 / 重复」逐条拒绝（`AreaSelectorEditPacket.java:167-184`），
  维度闸门更早（`:146-150`）；主手路径没有快照通道，拒绝时没有任何文字反馈。

### 修法

在 `AreaSelectorHandler.onMouseButton` 的 ADD 发包前补「与服务端同源」的预检
（`AreaSelectorHandler.java:243-255`）：

```java
final ResourceLocation here = minecraft.level.dimension().location();
if (!selection.matchesDimension(here)) { message(..., MSG + "dimension", ...); return true; }
if (selection.hasGeometry(area, -1))  { message(..., MSG + "duplicate"); return true; }
```

- 判据与 `AreaSelectorScreen.createArea`（`:618-630`，上限 / 去重）和
  `editableHere()`（`:292-300`，维度）同源；服务端仍是最终权威（伪造包照样被拒）。
- 「已提交」只在走完上述全部预检、真正发包之后才出现（`AreaSelectorHandler.java:256-266`）；
  预检拦住的路径改为就地说明「重复 / 维度不符」，不再声称成功。
- 同一类问题的第二个入口 `resizeSelected`（Ctrl + 滚轮调整选中区域）也补了维度 / 去重预检
  （`:301-306`、`:326-330`）——它原来同样是「先发包、立刻宣告已调整」。
- 新增语言键：`message.rs_create_compat.area_selector.duplicate` /
  `message.rs_create_compat.area_selector.dimension`（zh/en 成对，
  `zh_cn.json:1425-1426`、`en_us.json:1425-1426`）。

### 边界

- 预检失败时**不清掉已记下的 A 角**（与 `invalid` / `limit` 分支一致），玩家可以调整 B 角再提交。
- 服务端校验没有删除、没有放宽：预检只是「客户端提前说人话」，改包 / 版本漂移仍由服务端拒绝。
- 没有新增 S2C 包（审计里成本更高的「主手拒绝原因回传」方案没有采用）。
- 极端竞态（例如发包同一 tick 物品被别的路径改了）仍可能被服务端拒绝；此时服务端走 D7 的
  `inventoryMenu.broadcastFullState()` 把物品纠正回来，客户端「已提交」文案在那种竞态下仍可能短暂出现
  —— 这是本修法的诚实边界，不是新引入的问题。

### 未验证

- 没有实机验证 ActionBar 文案的实际观感（只保证键存在、zh/en 成对）。
- 没有实机验证「重复框 → 提示重复 → 调整 B 角再提交」这条完整手势链。
- 没有覆盖旁观者路径（服务端 `isSpectator` 早退，客户端没有对应预检；本轮未改动）。

---

## 一致性验收表

| 场景 | 区域列表（`AreaSelectorScreen`） | 标记界面（`AreaMarkScreen`） | 服务端（`AreaSelectorTargets`） |
| --- | --- | --- | --- |
| 菜单路径 + 槽里有选定器 | 读专用槽 | 读专用槽 | 读专用槽 |
| 菜单路径 + 槽空 / 非选定器 | **不回退主手**；空列表 + `status.no_slot`；新建置灰 | **不回退主手**；加/删/清直接拦住并提示 | `resolve` 返回空 ⇒ 丢包 |
| 菜单已不是当前打开的菜单 | **不回退主手**；视为没有目标 | 容器 id 对不上 ⇒ 视为没有目标 | `menuOf` 返回 null ⇒ 丢包 |
| 主手路径 | 读主手 | 读主手 | 读主手 |
| 服务端拒绝 | 菜单分支 `broadcastFullState()` + 快照原因 | （标记包无拒绝回传；空槽由客户端预拦） | 主手分支 `inventoryMenu.broadcastFullState()` |
| 「成功」文案 | 无（界面只在快照拒绝时给原因） | 空槽 / 过大 / 超预算 / 重复 / 维度都在发前拦 | 写入后 `sync` |

## 自检

```text
"c:/users/70432/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe" tools/selfcheck_round119_area_selector_sync.py
...
断言 49 条（其中反例 21 条），失败 0 条
ALL_PASS
[exit code: 0]
```

自检分三组：

1. **源码锚点**：每条修法都有正例（关键语句 + 顺序），并断言没有回退成坏写法
   （例如 `broadcastFullState` 不在 `sync` 里、`targetStack` 菜单分支没有 `getMainHandItem`、
   手持预检在 `sendToServer` 之前）。
2. **行为模型**：客户端乐观副本 vs 服务端权威、菜单槽空时「读没读主手」、成功提示与
   服务端实际写入是否一致；包含「多组权威状态 × 乐观副本」的组合断言。
3. **反例自证**：把坏写法拼回源码副本，断言同一条判据真的判负 —— 21 条反例，证明判据不是恒真。

## 改动文件（本轮）

| 文件 | 改动 |
| --- | --- |
| `src/main/java/cretae/cookiewyq/rs_create_compat/network/AreaSelectorEditPacket.java` | `reject()` 菜单分支补 `menu.broadcastFullState()`（`:318-329`），注释改成「两条落点都全量回发」 |
| `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AreaSelectorScreen.java` | `targetStack()` 菜单路径不回退主手 + 当前菜单校验（`:242-268`）；`selectionStack()` 委托（`:731-740`）；新建按钮 / `createArea` 目标判据（`:380-385`、`:596-600`）；`status.no_slot` 提示（`:1032-1036`）；类注释同步 |
| `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AreaMarkScreen.java` | 菜单路径校验「槽里是选定器」（`:127-145`）；`menuTargetMissing()`（`:147-160`）+ 加/删/清三处拦截（`:218`、`:274`、`:297`）；注释同步 |
| `src/main/java/cretae/cookiewyq/rs_create_compat/client/AreaSelectorHandler.java` | 手持 ADD 维度 / 去重预检（`:243-255`）；`resizeSelected` 同款预检（`:301-306`、`:326-330`）；顶部手势表注释同步 |
| `src/main/resources/assets/rs_create_compat/lang/zh_cn.json` / `en_us.json` | 新增 `status.no_slot`、`message...duplicate`、`message...dimension`（成对） |
| `tools/selfcheck_round119_area_selector_sync.py` | 新增自检（49 断言 / 21 反例） |
| `docs/ROUND119_AREA_SELECTOR_SYNC.md` | 本文档 |

## 未验证（总）

1. **没有实机点界面**：C-1 的「拒绝后数字回弹」、C-2 的空槽提示排版、C-3 的 ActionBar 文案观感，
   都只有源码锚点 + 行为模型，没有 GUI 实机。
2. **没有跑 gradle**（按纪律由 Lead 统一编译）。只对 4 个改动的 Java 文件做了
   `javac -proc:none` 单文件类型检查（exit 0），没有覆盖其它源文件 / 注解处理 / 完整构建，
   因此仍不能声称「构建通过」。
3. 没有起专服验证包到达顺序 / 多人同菜单场景；`broadcastFullState()` 的行为是从本机原版反编译源
   `AbstractContainerMenu.java:188-202` 推断的。
4. 没有覆盖旁观者、改包、旧客户端等对抗场景（服务端原有校验未改动）。
