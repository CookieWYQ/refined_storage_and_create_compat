# 复用简报：既有「选择界面」与「升级槽」

> **文档状态**：**纯调研**。本轮**未修改任何 Java 源码**，未改动 `run/`，未新增依赖，未解压任何 jar 到工作区。
>
> **任务来源（用户原话）**：
> 「那个**选择的那一个部分**，以及**升级槽**啊这样子的部分**我们已经实现过了**，你应该**重点排查**……
> 寻找一下我们是**之前怎么实现的**。」
>
> **交付目标**：不要从零设计。找出既有实现，说明**能不能直接复用、怎么复用、有哪些坑**。
>
> **证据口径**：每条结论都带「类名 + `文件:行号`」或 `javap` 输出。**找不到证据的一律写「未证实」**。
>
> **依赖版本**：Minecraft 1.21.1 / NeoForge 21.1.248 / Refined Storage **2.0.9** / Create 6.0.10。
>
> **RS 证据来源（重要，先说清楚）**：
> - 本机**没有 RS 2.0.9 的 sources jar**（只有 2.0.0 的）。
> - **2.0.9 的证据全部来自字节码**：
>   `D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar`
>   的 `javap -p -c` 输出（该 jar 的 `META-INF/neoforge.mods.toml` 里 `version = "2.0.9"`）。
> - 工程内 `local_src/external/RefinedStorage/` 是 **post-2.0.9 的 master 检出**，**不是 2.0.9**
>   （`git log -1` = `0f89121b25936f8a65821683ce2d52497df50af0  Fri Jul 10 12:34:06 2026`；
>   `CHANGELOG.md` 顶部是 `## [Unreleased]`，其下才是 `## [2.0.9] - 2026-06-07`）。
>   源码检出**只用于读起来顺**；凡引用它的**行号**，见 §⑦.1 第 7 条的免责说明。
>
> **与既有文档的关系（重要，避免重复劳动）**：
> 工程内已有一份 `docs/P2P_SEND_UI_OPTIONS.md`（691 行，「发送配置」界面的 **5 个方案对比 + 推荐**），
> 它的 **§1.1 / §1.2 已经覆盖了本文件的 ① 与 ②**，结论与本文件**一致**：
> - ① 选机器界面 = `StepMachineSelectScreen`（该文档 §1.1，`:42-56`）；
> - ② 选资源界面 = **工程里没有**（该文档 §1.2，`:58-71`）。
>
> **两份文档的分工**（避免把它们当成互相矛盾的两套说法）：
>
> | 文档 | 负责回答 |
> | ---- | -------- |
> | `docs/P2P_SEND_UI_OPTIONS.md` | 「发送配置**界面该做成什么方案**」——5 个方案、入口选型（物品 / 既有方块 / 终端模式）、推荐主方案 5a、以及 **`StepMachineSelectScreen` 泛型化 vs 同源新写的取舍**（该文档 §6，`:583-588`） |
> | `docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md` | 「接收器/请求器的**功能设计**」——名字注册表、数据流、失败处理、气体 |
> | **本文件** | 「**既有实现的复用清单与数值语义**」——① ② 的**逐行证据 + 通用性判定**、③ **升级槽全量清点（6 台机器）**、④ **RS 2.0.9 字节码实证的速度/叠加语义与原样照抄的陷阱**、⑤ 朝向输出 |
>
> **本文件相对于上两份的净增量**（不是重复）：
> - **① 的通用性判定更硬**：既有文档只说「这个界面被复用了」（`P2P_SEND_UI_OPTIONS.md:52-56`），
>   本文件进一步用**代码证据**区分「`ScrollSelectWidget<T>` = 完全通用」与
>   「`StepMachineSelectScreen` = 半通用（候选类型被钉死 `:64`/`:74`）」，
>   并指出**唯一的解耦口子**是 `confirmSink`（`:88-92`）。
> - **③ 升级槽全量清点**：上两份都**没有**「哪 6 台机器有升级槽 + 各自槽位数/容器/白名单」的完整表（见 §3.2）。
> - **④ RS 2.0.9 字节码实证**：既有文档给的是**post-2.0.9 源码**的行号，本文件把
>   **每一个关键数值与判据**都回到 **2.0.9 字节码**逐条核对（见 §4），并发现**坑 A**。
> - **坑 A / 坑 B / 坑 C**：三个既有文档都**没有**提到（见 §⑥.3）。

---

## ⓿ 结论摘要（先看这 8 行）

| # | 问题 | 结论 | 一句话依据 |
| - | ---- | ---- | ---------- |
| 1 | 有没有「选机器」界面？ | **有，而且已经一屏三用** | `client/screen/StepMachineSelectScreen.java:36` |
| 2 | 它的搜索是自带的还是通用控件？ | **搜索是自带的（原版 `EditBox` + 子串过滤）；选择列表是通用控件** | `:116-122` / `:138-152`；控件 `client/widget/ScrollSelectWidget.java:28` |
| 3 | 它通用吗？ | **控件 `ScrollSelectWidget<T>` = 完全通用；界面 `StepMachineSelectScreen` = 半通用（候选类型被钉死）** | `:64`/`:74` 钉死 `SyncChamberListPacket.Entry`；解耦口 = `:88-92` 的 `confirmSink` |
| 4 | 有没有「选物品/流体」界面？ | **没有**。只有 4 个 JEI 拖拽 handler + 1 个「选类别」界面 | `SequenceTerminalJeiPlugin.java:46`、`QuantityKeeperJeiPlugin.java:31`、`AdvancedQuantityKeeperJeiPlugin.java:32`、`CollectionCacheJeiPlugin.java:39`、`BusCategoryConfigScreen.java:135` |
| 5 | 升级槽实现了没有？ | **有，6 个方块在用，而且有 3 层准入校验 + 4 套数值口径** | `menu/UpgradeSlot.java:27`；表格见 §③ |
| 6 | 升级槽能直接用到新方块上吗？ | **能，逐字照抄级**（4 步，见 §③ 末） | `UpgradeSlot.java:66-77` |
| 7 | RS 速度/叠加升级在 2.0.9 里是什么？ | 速度 = **改 `doWork()` 周期**（`workTickRate = 9 − 2n`）；叠加 = **单次量 ×64 的布尔量** | `UpgradeContainer.java:148-149`、`:202-207`、`ExporterTransferQuotaProvider.java:20-22` |
| 8 | 「资源流动速度」该照哪一套？ | **照 RS 的「速度改节拍 + 叠加改单批」骨架，数值口径跟本工程（每级线性，不用 RS 的 ×64 布尔）；但「一个一个过」本工程已有现成答案** | `RsccChamberExportStrategy.java:86-90`（`INPUT_FEED_UNIT = 1L`） |

**顺手发现的 3 个坑**（详见 §⑥.3）：
- **坑 A**：RS 的 `workTickRate = 9 − 2n` 在 `n = 5/6` 时变**负**，行为**非单调**（5 个 → 每 tick，6 个 → 每 3 tick，**反而更慢**）。RS 自己把速度上限夹到 4，但**本工程 `MAX_PER_UPGRADE = 6`**，所以**不能直接把节拍交给 RS 的 ticker**。
- **坑 B**：`RegisterUpgradeDestinations.java:51-53` 把 `sequence_assembly_executor` 注册成吃 `speed_upgrade`(6) + `autocrafting_upgrade`(1)，但**该方块根本没有升级槽**（`SequenceAssemblyExecutorMenu.java` 只有 `PatternSlot`/玩家槽）。⇒ RS 升级物品的 tooltip 会**谎报**一台不存在的升级目的地。
- **坑 C**：`StepMachineSelectScreen` 的另外两条入口（自动合成监视器 / 样板改绑）**必须传入 `confirmSink`** —— 否则默认路径会发 `SetStepMachinePacket(globalStep, ...)`（`StepMachineSelectScreen.java:177`），把「监视器里的任务步骤号」当成「样板终端的全局步骤号」写进去。

---

## ① 「选机器」界面（选择 + 搜索）—— 重点

### 1.1 确切类名与位置

| 角色 | 类名 | 文件:行号 |
| ---- | ---- | --------- |
| **子界面（弹窗）** | `cretae.cookiewyq.rs_create_compat.client.screen.StepMachineSelectScreen` | `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/StepMachineSelectScreen.java:36` |
| **通用选择控件** | `cretae.cookiewyq.rs_create_compat.client.widget.ScrollSelectWidget<T>` | `src/main/java/cretae/cookiewyq/rs_create_compat/client/widget/ScrollSelectWidget.java:28` |
| **子窗口基类** | `cretae.cookiewyq.rs_create_compat.client.screen.ChildConfigScreen` | `src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/ChildConfigScreen.java:24` |
| 服务端 → 客户端候选包 | `SyncChamberListPacket` / `SyncChamberListPacket.Entry` | `src/main/java/cretae/cookiewyq/rs_create_compat/network/SyncChamberListPacket.java:23` / `:25` |
| 默认回传包 | `SetStepMachinePacket` | `StepMachineSelectScreen.java:176-177` |

`StepMachineSelectScreen extends ChildConfigScreen`（`:36`）。面板尺寸 `200 × 128`（`:40-41`），
`PAD = 10`（`:42`），`BOX_H = 16`（`:43`）。
纵向布局常量（**要改版面只需改这些**）：`TITLE_Y = 10`（`:44`）、`STEP_Y = 26`（`:45`）、
`RECIPE_Y = 38`（`:46`）、`SEARCH_Y = 54`（`:48`）、`WIDGET_Y = 76`（`:49`）、`ACTION_Y = 102`（`:51`）。

> 面板比「无搜索版」高了 20px，就是为了多出**一整行搜索框**且「其余行整体下移，绝不重叠」
> —— `:39` 的原注释。

### 1.2 它怎么被打开（**全部 3 个调用点**）

`grep StepMachineSelectScreen` 只找到 3 处 `new`，**一处都不是多余的**：

| # | 入口 | 打开代码 | 触发点 | 是否传 `confirmSink` |
| - | ---- | -------- | ------ | ------------------- |
| 1 | **序列装配样板终端**：点行内「机器控件横带」，或 Ctrl+左键点该步骤整行 | `client/screen/SequencePatternTerminalScreen.java:790-799`（`new` 在 `:797`） | 点击处理 `:2377`（`openMachineSelect(global)`） | ❌ 不传（走默认 `SetStepMachinePacket`） |
| 2 | **RS 自动合成监视器**的「更换机器」 | `client/AssemblyAlertsClient.java:191-211`（`new` 在 `:202`） | `mixin/client/AutocraftingMonitorScreenMixin.java:711` → `requestMachines`（`AssemblyAlertsClient.java:180-184`）→ 服务端回 `AssemblyMachineCandidatesPacket` → `network/AssemblyMachineCandidatesPacket.java:41` → `client/ClientPayloadSink.java:52-53` | ✅ 传（自己发 `AssemblyStepMachinePacket`，`AssemblyAlertsClient.java:208-209`） |
| 3 | **样板改绑界面**「改绑机器」按钮 | `client/screen/AssemblyPatternRebindScreen.java:166-180`（`new` 在 `:176`） | 按钮 `:101` | ✅ 传（`:178-180`） |

原文注释明确写了「**同一个类被两处复用**（用户明确要求『不要写两套』）」
—— `StepMachineSelectScreen.java:26-32`。

**调用方拿到的候选从哪来**：
- 入口 1：`SequencePatternTerminalScreen#chambersForType(recipeType)`（`:796` 调用），
  「候选 = 该步配方类型下的全部执行仓（**不受父界面搜索框过滤**）」—— `:795` 原注释。
- 入口 3：`AssemblyPatternRebindScreen#candidatesFor(recipeType)`（`:143-154`）按 `chamber.recipeType()` 再筛一次。
- 入口 2：服务端按需下发 `AssemblyMachineCandidatesPacket`。
- 客户端的**全局候选缓存**：`SyncChamberListPacket.getLastReceived()`（`:47-49`），
  由 `handle`（`:60-63`）写入；数据源 = 终端方块实体的 `ChamberInfo` 列表（`from`，`:52-58`）。

### 1.3 构造参数

**5 参构造器**（`:78-82`）—— 样板终端用这条，**行为零变化**（`:85` 原注释）：

```java
public StepMachineSelectScreen(Screen parent, int globalStep, String recipeType,
                               List<SyncChamberListPacket.Entry> candidates,
                               String currentMachineName)
```

**6 参带回调构造器**（`:88-92`）—— 复用同一套 UI：

```java
public StepMachineSelectScreen(Screen parent, int globalStep, String recipeType,
                               List<SyncChamberListPacket.Entry> candidates,
                               String currentMachineName,
                               @Nullable Consumer<SyncChamberListPacket.Entry> confirmSink)
```

| 参数 | 字段 | 用途 | 证据 |
| ---- | ---- | ---- | ---- |
| `parent` | `ChildConfigScreen.parent`（`:45`） | `returnToParent()` 用 | `ChildConfigScreen.java:104-106` |
| `globalStep` | `:60` | 「全局步骤下标（与终端 arrangement 下标一致）」 | `:59` 原注释；渲染 `:190-191` |
| `recipeType` | `:62` | 仅用于**展示**（+ Alt 层显示内部 id） | 渲染 `:192-194`；tooltip `:218-224` |
| `candidates` | `:64` | **未经搜索过滤**的完整候选 | `:63` 原注释 |
| `currentMachineName` | `:66` | 初始高亮（未指派 = 空串） | `indexOfCurrent` `:155-162` |
| `confirmSink` | `:72` | 非 null 时**替代**默认发包 | `:165-180` |

防御性拷贝：`candidates == null ? List.of() : List.copyOf(candidates)`（`:96`）、
`recipeType == null ? "" : ...`（`:95`）、`currentMachineName == null ? "" : ...`（`:97`）。

### 1.4 它的搜索是怎么实现的 —— **自带，不是通用控件**

**结论：搜索是 `StepMachineSelectScreen` 自己实现的，`ScrollSelectWidget` 完全不参与搜索。**

- 搜索框 = **原版 `net.minecraft.client.gui.components.EditBox`**，不是自绘控件：
  `StepMachineSelectScreen.java:76`（字段声明）、`:116-121`（构造 + 注册）：
  ```java
  searchBox = new EditBox(font, px + PAD, py + SEARCH_Y, WIDGET_W, BOX_H, ...);  // :116
  searchBox.setMaxLength(64);          // :118
  searchBox.setTextShadow(false);      // :119  ← 工程硬规则：所有文字无阴影
  searchBox.setResponder(text -> applyFilter());  // :120  ← 输入即过滤
  addRenderableWidget(searchBox);      // :121
  ```
- 过滤算法 = **不区分大小写的子串匹配，空查询 = 全部**：`applyFilter()`（`:138-152`）：
  ```java
  final String query = searchBox.getValue().trim().toLowerCase(Locale.ROOT);   // :142
  for (final SyncChamberListPacket.Entry entry : candidates) {
      if (query.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(query)) {  // :145
          filtered.add(entry);
      }
  }
  picker.setOptions(filtered);                        // :149  ← 只换候选，不重建控件
  picker.setState(indexOfCurrent(filtered));          // :150
  picker.selectable(!filtered.isEmpty());             // :151  ← 候选空 ⇒ 灰显不可交互
  ```
- **过滤后仍尽量保持「当前已指派的那台」被选中**；它被过滤掉时收敛到第一条匹配（`:132-137` 原注释）。
- 搜索框自己的 tooltip 会报「命中几台 / 共几台」：`:201-208`
  （`picker.getOptions().size()` / `candidates.size()`）。

**通用控件那一半**（`ScrollSelectWidget<T>`，`client/widget/ScrollSelectWidget.java:28`）提供的是：
- **链式配置**：`forOptions`(`:63`)、`titled`(`:68`)、`displaying`(`:73`)、`writingTo`(`:81`)、
  `hinting`(`:86`)、`showingExtraTooltip`(`:95`)、`selectable`(`:100`)。
- **交互**：滚轮下滚 = 下一项 / 上滚 = 上一项（`:143-151`）；
  左键点**左半边 = 上一项、右半边 = 下一项**（`:154-161`，Create 原版没有的鼠标支持）；
  仅当「可用 + 候选 > 1」时才接管事件（`canInteract`，`:164-166`）。
- **状态**：`setOptions`(`:108-111`)、**静默** `setState`(`:114-120`，不触发回调)、
  `getState`(`:122-124`)、`getSelected`(`:127-130`，无候选返回 `null`)、`getOptions`(`:132-134`)。
- **外观**：自绘 1px 描边 + 更深内填充（`:189-190`），当前项文字居中 + `◀`/`▶` 提示（`:207-213`），
  宽度不足自动省略号（`trimToWidth`，`:284-296`）。**不新增贴图**。
- **tooltip 必须由所属界面手动调用** `renderTooltip`（`:220-230`），
  内容 = 标题 + 最多 16 行窗口（当前项 `-> `，其余 `> `）+ 提示（`:233-275`）；
  `inBounds`（`:278-281`）与绘制范围同源。

**界面里怎么把它和搜索框缝起来**（`init()`，`:101-130`）：
```java
picker = new ScrollSelectWidget<SyncChamberListPacket.Entry>(font, px+PAD, py+WIDGET_Y, WIDGET_W, BOX_H)  // :106
    .forOptions(candidates)                                       // :108
    .titled(...).displaying(entry -> Component.literal(entry.name()))  // :109-110
    .hinting(...).selectable(!candidates.isEmpty());              // :111-112
picker.setState(indexOfCurrent(candidates));                       // :113  ← 静默，不回调
addRenderableWidget(picker);                                       // :114
// ……建完搜索框后立刻 applyFilter() 套一次（初始查询为空 ⇒ 与旧行为一致）  // :122
```
`:105` 的注释把顺序原因写明了：「先建候选控件（含完整候选），再建搜索框并立刻套一次过滤」。

### 1.5 选中后怎么回传 —— **两条路，一条直接发包、一条走回调**

```java
private void confirmAndReturn() {                                             // :165
    final SyncChamberListPacket.Entry selected = picker == null ? null : picker.getSelected();  // :166
    if (confirmSink != null) {                                                // :167
        if (selected != null) { confirmSink.accept(selected); }               // :169-170
        returnToParent();                                                     // :172
        return;
    }
    if (selected != null) {
        PacketDistributor.sendToServer(
            new SetStepMachinePacket(globalStep, true, selected.pos(), selected.name()));  // :176-177
    }
    returnToParent();                                                         // :179
}
```

- **路径 1（默认）**：直接 `PacketDistributor.sendToServer(new SetStepMachinePacket(...))`（`:176-177`）。
  客户端**不写任何权威状态**（`:787-788` 原注释：「服务端会校验『机器必须真实存在于网络中』」）。
- **路径 2（复用）**：`confirmSink.accept(selected)`（`:170`），由调用方发自己的包
  —— `AssemblyAlertsClient.java:204-210`（发 `AssemblyStepMachinePacket`）、
  `AssemblyPatternRebindScreen.java:178-180`。
- **两侧都不会「把假选项写回去」**：`selected == null` 时直接返回（`:166-175`），
  配合 `selectable(false)` + 灰显（`:151`、`ScrollSelectWidget.java:205-206`）。
- **返回父界面**：`returnToParent()` → `Minecraft.getInstance().setScreen(parent)`
  （`ChildConfigScreen.java:104-106`）；Esc 走 `onClose()`（`:113-116`）。
- **回车 = 确定**：`keyPressed`（`:241-248`，`GLFW_KEY_ENTER` / `GLFW_KEY_KP_ENTER`）。
- **取消按钮**：`:127-129`（`(px+PAD+ACTION_W+4, py+ACTION_Y)`）。
- 子窗口**不渲染父界面**，只由原版背景把世界暗化（`ChildConfigScreen.java:66-74` 的注释 + `:67-71`），
  与 RS 的 `AbstractAmountScreen` 一致。

### 1.6 所有用到 `ScrollSelectWidget` 的地方（**证明控件通用**）

| # | 位置 | 用来选什么 | 证据 |
| - | ---- | ---------- | ---- |
| 1 | `StepMachineSelectScreen.java:106` | **选执行仓**（本主题） | 字段 `:74` |
| 2 | `client/screen/ChamberBindingConfigScreen.java:166` | **选配方类型**（执行仓绑定） | 字段 `:119`；类 `:55` |
| 3 | `client/screen/UnitPatternConfigScreen.java:138` | **选配方类型**（单元样板） | 字段 `:85`；类 `:39` |
| 4 | `client/screen/AssemblyPatternRebindScreen.java:82` | **选「哪一步」** | 字段 `:66`；类 `:37` |

—— 同一个控件被用在**三种完全不同的语义**上（机器 / 配方类型 / 步骤），
且 `AssemblyPatternRebindScreen.java:88-95` 还演示了 `showingExtraTooltip` 进阶用法
（Alt 层显示内部 id，按键状态只在 `RsccTooltipLayers` 里读一次）。

### 1.7 判断：它通用吗？**分两层回答**

#### (a) `ScrollSelectWidget<T>` —— **完全通用，可直接拿去用**

**证据**：
- 类声明是**纯泛型** `public class ScrollSelectWidget<T> extends AbstractWidget`（`:28`）。
- 它对 `T` 的**唯一要求**是「能提供一个 `Component` 用于显示」——
  通过 `Function<T, Component> display` 注入（字段 `:48`，默认 `String::valueOf`），
  **没有任何 `instanceof` / 类型判断 / 业务 import**（全文 305 行，import 只有 MC + `java.util.function`）。
- 已被 4 个语义完全不同的界面复用（§1.6）。
- 状态与回调分离干净：`setState` 静默（`:114-120`）、`writingTo` 才回调（`:81-84`）——
  这正是「服务端权威回填不误触发写入」的关键。

**⇒ 结论**：要选「任意一台方块 / 任意一个目标 / 任意一行文本」，
**`ScrollSelectWidget<T>` 可以直接 `new` 出来用，一行都不用改**。

#### (b) `StepMachineSelectScreen` —— **半通用**：骨架可复用，但类型与语义被钉死

**强耦合证据**：

| 耦合点 | 证据 | 说明 |
| ------ | ---- | ---- |
| **候选类型被钉死为 `SyncChamberListPacket.Entry`** | 字段 `:64`（`List<SyncChamberListPacket.Entry> candidates`）、`:74`（`ScrollSelectWidget<SyncChamberListPacket.Entry> picker`）、构造器 `:79`/`:90`、`displaying(entry -> ...entry.name())` `:110` | **不是泛型类**（`public class StepMachineSelectScreen extends ChildConfigScreen`，`:36`） |
| **三行文字是「机器」的语义** | 标题 `:189`、`step`（第 N 步）`:190-191`、`recipe_type`（配方类型）`:192-194`；对应的 tooltip `:213-224` | 换语义就得改这几处 |
| **默认回传是「样板第 N 步指派机器」** | `new SetStepMachinePacket(globalStep, true, selected.pos(), selected.name())` `:176-177` | 包名与字段语义都是机器/步骤 |
| `Entry` 自带 `pos()` / `recipeType()` | `SyncChamberListPacket.java:25` | 界面只用到 `name()`，但类型上带着另两个字段 |

**⇒ 要选「任意一台方块 / 任意一个目标」，有两条路**：

- **路 A（最小改动，推荐先试）**：**只用 `ScrollSelectWidget<T>` + `ChildConfigScreen`，自己写一个 30 行的弹窗**。
  因为 `StepMachineSelectScreen` 里真正有价值的、难写的部分（搜索框接、过滤、空候选灰显、
  Enter 确认、返回父界面、手动 tooltip）**全部可以照抄这 249 行的骨架**，
  而 `ScrollSelectWidget` 本身已经零耦合。
- **路 B（改既有类）**：把 `StepMachineSelectScreen` 的候选类型**泛型化**，
  或抽一个 `Entry` 的最小接口（只要 `name()`；`pos()`/`recipeType()` 变成可选），
  并把三行说明文字改成构造参数。**代价 = 动一个已被 3 处复用的类，回归面较大。**

**唯一的既成解耦口子** = 6 参构造器的 `confirmSink`（`:88-92` + `:167-173`）。
它让**回传**与调用方解耦，但**候选类型仍然必须是 `Entry`** —— 所以它只解决了一半。

---

## ② 「选资源」界面（物品 / 流体）—— **没有**

### 2.1 明确结论

**工程里没有任何「从列表/网格里挑一个物品或流体」的自有界面。**
`docs/` 下也没有这类界面的设计文档（`docs/` 全清单见 §⑦ 第 8 条）。

现有与「选资源」相关的只有**三类**，都不是资源浏览器：

### 2.2 真正的「选资源」入口 = **JEI 拖拽**（4 个 `IGhostIngredientHandler`）

| 目标界面 | 插件类 | 注册行 | handler 内部类 |
| -------- | ------ | ------ | -------------- |
| `SequencePatternTerminalScreen` | `client/SequenceTerminalJeiPlugin.java` | `:46` | `GhostHandler` `:228-229` |
| `QuantityKeeperScreen` | `client/QuantityKeeperJeiPlugin.java` | `:31` | `MarkerGhostHandler` `:35-36` |
| `AdvancedQuantityKeeperScreen` | `client/AdvancedQuantityKeeperJeiPlugin.java` | `:32` | `MarkerGhostHandler` `:36-37` |
| `CollectionCacheScreen` | `client/CollectionCacheJeiPlugin.java` | `:39` | `MarkerGhostHandler` `:43-44` |

- 写入通道（样板终端那一路）：`SetSequenceGhostPacket`（`network/SetSequenceGhostPacket.java:17`）
  → 服务端 `handle`（`:28-33`）→ `SequencePatternTerminalMenu#placeGhost`（`menu/SequencePatternTerminalMenu.java:823`）。
- 其余三路是各界面自己的 C2S 包（如 `SetCollectionMarkerConfigPacket`、`SetQuantityMarkerPacket`）。
- 标记的**绘制**统一在一处：`client/widget/GhostMarkerRenderer.java:46`
  （物品 / 流体 / 气体统一，`:37-40` 原注释；
  关键 API：`toFluidStack`、`fluidName`、`fluidAmount`、`displayItem`(`:179`)、
  `candidateItems`、`cycleCandidate`、`tagMatchedItems`、`renderFluid`、`renderLines`）。

**⇒ 「从 JEI 面板里选」不是我们自己的选择界面。**
若新方块（请求器的「设定什么资源」）想**不依赖 JEI**，**必须新写界面**（见 §⑥）。

### 2.3 「选类别」界面 = `BusCategoryConfigScreen`（不是选单个资源）

- 类：`client/screen/BusCategoryConfigScreen.java:135`（`extends ChildConfigScreen`）。
- 它选的是「这条总线勾选哪些**类别 id**」（`selected` 是编辑态唯一真相，`committedIds()` 提交）
  —— `:111-112` 原注释。
- **自带搜索**：搜索框几何 `:151-154`；能力含「物品/流体名过滤当前页 + 配方名跳转行 +
  分节名/工序机器名跳转行 + 其它页命中时给切页行」—— `:114-115` 原注释。
- **自带标签页 + 可滚动清单 + 弹出选择器**：`TABS_*` `:179-189`、`PICK_*` `:191-204`
  （`PICK_POP_W=150`、`PICK_ROW_H=12`、`PICK_VISIBLE=12`）、
  弹出层**自带搜索框**（`PICK_SEARCH_H` `:211`，注释引用了用户原话）。
- **它没有用 `ScrollSelectWidget`**（`grep ScrollSelectWidget` 不含本文件）——
  列表/搜索/滚动全部**内联自绘**（`:123-126` 原注释：标签页/列表底/勾选框/分节表头/折叠三角
  全部 Java `fill` 运行时绘制）。
- 条目图标走通用绘制工具：`GhostMarkerRenderer.candidateItems` `:749`、`:769`，
  `cycleCandidate` `:2393`、`:2409`。

**⇒ 可复用的是「思路 + `GhostMarkerRenderer` 绘制工具」，不是控件。**

### 2.4 「标记详情编辑」界面 = `CollectionMarkerConfigScreen`（图标只显示不可选）

- 类：`client/screen/CollectionMarkerConfigScreen.java:44`（`extends ChildConfigScreen`）。
- 作用：对**某一条已存在的** ghost 标记编辑「需求数量 + 匹配规则」—— `:32` 类注释。
- 右上角资源图标**只显示、不可选**：`:531-540`
  （流体走 `GhostMarkerRenderer.renderFluid` `:537-538`，物品走 `displayItem` `:540`）。
- 与主界面**同源**（`:41` 原注释）。

**⇒ 它是「编辑已有标记」，不是「选一个新资源」。**

### 2.5 与「保持器」相关的既有资源承载

- `QuantityKeeperBlockEntity`：容器 `SimpleContainer(7)`（`block/entity/QuantityKeeperBlockEntity.java:77`）
  = **1 个 ghost 标记槽 + 6 个升级槽**；`getInventory()` `:233`。
- `AdvancedQuantityKeeperBlockEntity`：`SimpleContainer(SLOT_COUNT + 6)`（`:114`，`SLOT_COUNT = 4` 见 `:60`）
  = **4 个 ghost 标记槽 + 6 个插件槽**；`:113` 原注释写明了分段；`getInventory()` `:199`。
- 标记写入路径（保持器）：`QuantityKeeperMenu.java:251-252`（「不是升级物品时，直接把标记槽（0）
  设为该物品的 ghost 标记 …… 只标记、不消耗玩家手里的物品」）、
  `AdvancedQuantityKeeperMenu.java:244`（`ghost` 标记槽点击语义）、`:305`。
- 流体标记的同步：`SyncQuantityFluidMarkerPacket.java:13`（注释说明
  「`ContainerData` 只能传数值，传不了资源 id」⇒ 必须单独发包）、
  `SyncAdvKeeperConfigPacket.java:17`。界面绘制 `AdvancedQuantityKeeperScreen.java:300-308`。

**⇒ 「选资源」在本工程目前 = 「往 ghost 槽里拖/点一个东西」，没有中间的选择界面。**

---

## ③ 升级槽（用户说已实现）—— 重点

### 3.1 槽位类：`menu/UpgradeSlot.java`

`public class UpgradeSlot extends SlotItemHandler`（`:27`）。

| 机制 | 实现 | 行号 |
| ---- | ---- | ---- |
| **默认白名单** | `DEFAULT_ALLOWED = List.of("speed_upgrade", "stack_upgrade", "autocrafting_upgrade")` | `:36-37` |
| **白名单判定** | `mayPlace(ItemStack)`：`pathOf` 为 null 或不在 `allowed` 里 ⇒ false | `:114-119` |
| **每槽仅 1 个** | `getMaxStackSize()` = 1；`getMaxStackSize(ItemStack)` = 1；`mayPlace` 里非空槽再拒一次（双保险） | `:104-107`、`:109-112`、`:120-123` |
| **单种上限** | `MAX_PER_UPGRADE = 6`；`AUTOCRAFTING_MAX = 1`；`maxForPath(path)`：`autocrafting_upgrade` 与 `creative_range_upgrade` 各 1，其余 6 | `:29`、`:31`、`:90-92` |
| **上限对拾取/Shift 都生效** | `countOfKindInHandler(stack, path) < maxPerUpgrade(path)` | `:124`、`:127-140` |
| **计数起点（避 ghost 槽）** | `countFromIndex` 字段 + 三段构造器夹紧 `Math.max(0, Math.min(index, countFromIndex))` | `:52`、`:66-77`、`:130` |
| **path 解析（唯一口径）** | `pathOf`：非 `refinedstorage` 命名空间 ⇒ `null` ⇒ 拒收；否则返回 `id.getPath()` | `:186-197` |
| **容器包装版** | `forContainer(...)` × 4 个重载，内部 `new InvWrapper(container)` | `:151-174`、`:176-184` |
| **允许清单 → 物品实例** | `allowedStacks()`（`:100-102`）、`stacksFor(paths)`（`:95-97`）、`getAllowedUpgradeStacks()`（`:200-202`）、`toStacks`（`:204-214`，按 `refinedstorage:<path>` 查注册表） | 同左 |

**`countFromIndex` 的由来（真 bug 根因，值得一读）**：定量保持器把 ghost 标记槽与插件槽
放在**同一个 `Container`** 里，`InvWrapper` 会把整个容器暴露给本槽 ⇒ 玩家在标记槽里标记过一个
「堆叠升级」就会被误当成已插入一个 ⇒ **同一方块只能再插 5 个**。把计数限制在真正的插件槽区间后，
「标记槽里放什么」与「能插几个」彻底解耦 —— `:41-51` 原注释。

### 3.2 哪些方块有升级槽（**槽位数 / 容器 / 菜单 / 白名单** 逐条给行号）

| # | 方块 | 槽位数 | 容器（字段 + getter） | 菜单建槽 | 白名单 |
| - | ---- | ------ | --------------------- | -------- | ------ |
| 1 | **范围充电器** | **6** | `ItemStackHandler(6)` `RangeChargerBlockEntity.java:93`；`getUpgradeContainer()` `:136-138` | `RangeChargerMenu.java:41-46` | `speed`/`stack`/`range`/`creative_range`（`:44-45`） |
| 2 | **归流缓存仓** | **6** | `UPGRADE_SLOTS = 6` `CollectionCacheBlockEntity.java:103`；`SimpleContainer(UPGRADE_SLOTS)` `:188`；`getUpgradeContainer()` `:329` | `CollectionCacheMenu.java:59`（`UPGRADE_COUNT`）、`:63-64`（`ALLOWED_UPGRADES`）、`:293-296` | `speed`/`stack`/`range`/`creative_range`（`:63-64`） |
| 3 | **蓝图装填器** | **6** | `ItemStackHandler(6)` `SchematicLoaderBlockEntity.java:212`；`getUpgradeContainer()` `:364` | `SchematicLoaderMenu.java:99-100` | `speed`/`stack`/`autocrafting`（`:100`） |
| 4 | **高级蓝图装填器** | **6** | 继承 #3 的容器 | `AdvancedSchematicLoaderMenu.java:113-114` | `speed`/`stack`/`autocrafting`（`:114`） |
| 5 | **定量保持器** | **6** | `SimpleContainer(7)` = 1 ghost + 6 插件 `QuantityKeeperBlockEntity.java:77`；`getInventory()` `:233` | `QuantityKeeperMenu.java:45`（`index = 1 + i`，`countFromIndex = 1`）、`:52`（无方块实体时的空容器分支） | 默认三类；`isUpgradeItem` `:288` |
| 6 | **高级定量保持器** | **6** | `SimpleContainer(SLOT_COUNT + 6)` = 4 ghost + 6 插件 `AdvancedQuantityKeeperBlockEntity.java:114`（`SLOT_COUNT = 4` `:60`）；`getInventory()` `:199` | `AdvancedQuantityKeeperMenu.java:66`（`MARKER_SLOTS + i`，`countFromIndex = MARKER_SLOTS`） | `:366`（`speed`/`stack`/`autocrafting`） |

**⇒ 统一口径的槽位数常量 = `UpgradeSlot.MAX_PER_UPGRADE = 6`**（`:29`），
被 `CollectionCacheBlockEntity.UPGRADE_SLOTS = 6`（`:103`）与
`SchematicLoaderBlockEntity.storageSlotCapacity`（`:447-450`）复用。
其余方块是直接写 `6`（`RangeChargerBlockEntity.java:93`、`SchematicLoaderBlockEntity.java:212`、
`QuantityKeeperBlockEntity.java:77`、`AdvancedQuantityKeeperBlockEntity.java:114`）。

**⚠️ 坑 B（本次新发现）**：`RegisterUpgradeDestinations.java:51-53` 把
`sequence_assembly_executor` 注册为吃 `speed_upgrade`(6) + `autocrafting_upgrade`(1)，但
**该方块根本没有升级槽**：
- 容器只有样板槽 —— `SequenceAssemblyExecutorBlockEntity.java:68`
  （`new SimpleContainer(PATTERN_SLOTS)`），`getInventory()` `:104`；
- 菜单只有 `PatternSlot` + 玩家槽 —— `SequenceAssemblyExecutorMenu.java:65`、`:76`、`:81`；
- 界面里 `grep Upgrade` **零命中**（`client/screen/SequenceAssemblyExecutorScreen.java`）；
- 方块实体里 `grep speed_upgrade` **零命中**。

⇒ **RS 升级物品的 tooltip 会列出「序列装配执行器」，但玩家放不进去。**
新方块注册目的地时**必须确认「注册表里的机器」与「真有槽位的机器」一致**。

### 3.3 准入白名单的三层写法（**三层都要，缺一层就有漏洞**）

**第 1 层：界面层**（拦鼠标交互）
- `UpgradeSlot.mayPlace`（`menu/UpgradeSlot.java:114-125`）+ `getMaxStackSize`（`:104-112`）。

**第 2 层：方块实体层兜底**（拦存档直改 / 版本变更 / 管道灌入）
- 范围充电器：`RangeChargerBlockEntity.validateUpgradeSlots()`（`:304-349`），
  `upgradeKind(stack)`（`:352-365`，`0=speed / 1=stack / 2=range / 3=creative_range / -1=非法`），
  非法或超限一律 `dropAndClear(i)`（`:367-376`，**弹出到世界而不是销毁**）；
  触发点 = `ItemStackHandler.onContentsChanged`（`:95-100`）+ `doWork()` 每 tick（`:294`）。
- 装填器：`SchematicLoaderBlockEntity.validateUpgradeSlots()`（`:274-291`），
  `dropBeyondKindCap(KIND_SPEED, ...)` / `(KIND_STACK, ...)`（`:288-289`），
  常量 `KIND_SPEED = 0`(`:64`) / `KIND_STACK = 2`(`:66`)；触发点 `:217`、`:546`。
- 归流缓存仓：`CollectionCacheBlockEntity.enforceUpgradeCaps()`（`:2508-2556`），
  逐槽 `keep` 判定（`:2526-2544`）；**注意它硬写了 `6` 与 `1`**（`:2527`、`:2532`、`:2537`、`:2542`）。

**第 3 层：RS 升级注册表**（决定 **RS 原生升级物品的 tooltip 里显示哪些机器**）
- `RegisterUpgradeDestinations.java:28-54`（机器 → 允许升级 → 上限 的表）、
  `:56-66`（`register` 私有工具）、
  `:72-98`（`registerAll()`：为每台机器 new 一个**匿名 `UpgradeDestination`**（`:80-90`），
  再 `registry.forDestination(destination)`（`:91`）+ `builder.add(upgradeItem, max)`（`:95`））。
- 该表与 `UpgradeSlot` 的白名单**刻意保持一致**（`:20-21` 原注释），
  效果是「**升级物品悬停提示由 RS 原生渲染，外观与 RS 原版完全一致，无需自定义 tooltip 文本**」（`:21`）。
- 调用时机：`FMLCommonSetup`（`:71` 注释）。

### 3.4 升级的**实际作用怎么生效** —— 具体代码位置（4 套数值口径）

| 升级 | 机制 | 公式 | 定义处 | 使用处 |
| ---- | ---- | ---- | ------ | ------ |
| **速度** → 缩短扫描间隔 | 扫描冷却 | `base / ((1 + speed) × (1 + (radius−1)/2))`，下限 1 | `CollectionCacheBlockEntity.java:445-450` | `:1269`（`scanCooldown = getScanInterval()`） |
| **速度** → 每 tick 处理组数 | 回流速率 | `1 + speedCount` | `CollectionCacheBlockEntity.java:2572-2574` | `:2004`、`:2355`、`:2406`、`:3180`（诊断） |
| **叠加** → 单次搬运量 | 单次吞吐 | `64 × (1 + stackCount)`，下限 1 | `CollectionCacheBlockEntity.java:2577-2579` | `:1389`（逐面抽料）、`:2005`、`:2356`、`:2406`、`:3181` |
| **叠加** → 每格容量 | 缓存容量 | `max(1, 配置基础值 + stackCount × 每级加成)` | `CollectionCacheBlockEntity.java:2479-2482` | — |
| **速度** → 充电速率 | 位左移 | `Config.rangeChargerChargeRate << getUpgradeCount()`；`getUpgradeCount() = speed + stack × 3` | `RangeChargerBlockEntity.java:154-161` | `:453`、`:606`、`:679`、`:710` |
| **速度** → 装填器拉取上限 | 位左移 | `speed >= 6 ? Integer.MAX_VALUE : (8 << speed)` | `SchematicLoaderBlockEntity.java:436-439` | — |
| **速度** → 装填器拉取速率 | 累计预算 | `speed >= 6 ? Double.MAX_VALUE : (0.5 × (1 << speed))` | `SchematicLoaderBlockEntity.java:1225-1226` | `:1242-1244` |
| **叠加** → 装填器每格容量 | 统一公式 | `64 × (1 + min(n, MAX_PER_UPGRADE))` | `SchematicLoaderBlockEntity.java:447-450`（`:442-446` 注释写明是「**唯一实现**」且与其它机器同口径）；`getStorageSlotCapacity()` `:461-463` | `insertIntoInventory` / `memoryView`（`:458-459` 注释） |
| **范围** → 单轴上限 | 线性 | `100 + rangeCount × Config.rangeChargerRangePerUpgrade`；creative ⇒ `Integer.MAX_VALUE − 1` | `RangeChargerBlockEntity.java:175-178` | `adjustRange*` `:233-246`、`clampRangesToMax` `:249-258` |
| **创造范围** → 无限 | 布尔 | `countUpgrades(CREATIVE_RANGE_UPGRADE) > 0` | `RangeChargerBlockEntity.java:169-171` | `doCharging` 分支 `:385-397`、`scanBlocksInfinite` `:464-507`、`scanItemsInfinite` `:438-461`、`scanPlayersInfinite` `:426-435` |
| **创造范围** 取下后回落 | 状态机 | `hadInfiniteRangeUpgrade` + 归到上限 | `RangeChargerBlockEntity.java:260-277` | `:345` |
| **速度** → 保持器节拍 | — | `Math.min(countUpgrades(SPEED_UPGRADE), 6)` | `QuantityKeeperBlockEntity.java:575-576` | 同处 |
| **速度/叠加** → 数据槽同步给界面 | — | `case 0 -> countUpgrades(SPEED_UPGRADE)` / `case 1 -> countUpgrades(STACK_UPGRADE)` | `CollectionCacheBlockEntity.java:2638-2639` | 界面显示 |
| **速度** → 网络耗电 | 线性 | `base + radiusCost + speedCount × Config.collectionCacheEnergyPerSpeedUpgrade` | `CollectionCacheBlockEntity.java:2610-2619` | — |
| **叠加** → 缓存每格上限 | — | 见上（`:2479-2482`），配置项 `COLLECTION_CACHE_SLOT_CAPACITY_PER_STACK_UPGRADE` | `Config.java:124`、`:356` | — |
| **速度** → 耗电配置项 | — | `COLLECTION_CACHE_ENERGY_PER_SPEED_UPGRADE` | `Config.java:111`、`:353` | — |

**通用 `countUpgrades` 实现**（按 `ResourceLocation` 数物品）：
`RangeChargerBlockEntity.java:140-151`、`CollectionCacheBlockEntity.java:2486-2496`、
`SchematicLoaderBlockEntity.java:465-475`、`QuantityKeeperBlockEntity.java`（`SPEED_UPGRADE` `:52`、
`STACK_UPGRADE` `:53`、`upgradeKind` `:222-223`）、
`AdvancedQuantityKeeperBlockEntity.java`（`:95-98`、`:585-586`）。

**升级物品 id 常量**（各机器重复声明 `ResourceLocation.fromNamespaceAndPath("refinedstorage", ...)`）：
`RangeChargerBlockEntity.java:39-46`、`SchematicLoaderBlockEntity.java:54-59`、
`CollectionCacheBlockEntity.java:78-81`、`QuantityKeeperBlockEntity.java:52-53`、
`AdvancedQuantityKeeperBlockEntity.java:95-98`。

### 3.5 空槽 tooltip（已有，可直接复用）

| 用途 | 方法 | 行号 |
| ---- | ---- | ---- |
| 按**槽实例**构建（≤允许清单 + 单种上限） | `UpgradeSlotTooltips.build(UpgradeSlot)` | `client/tooltip/UpgradeSlotTooltips.java:45-59` |
| 按**槽实例**渲染 | `render(UpgradeSlot, ...)` | `:95-101` |
| 按**允许清单**构建（给「容器包装槽」用） | `build(List<String>)` / `render(List<String>, ...)` | `:81-92` / `:104-110` |
| **常显**（不按 Shift，照搬 RS `AbstractBaseScreen#getUpgradeTooltip`） | `:36-43` 原注释（引用了用户原话） | 同左 |
| 已放入**叠加升级**时追加「**本机实际作用**」行 | `renderFilledStackUpgradeEffect(...)` | `:117-156` |
| 图标 + 名称（18px 高）组件 | `client/tooltip/UpgradeItemTooltipComponent.java:13`、`:17` | 同左 |
| 效果行语言键 | `LOADER_STACK_EFFECT_KEY` | `:29-30` |

**界面调用点**（判断空槽 ⇒ 弹升级 tooltip）：
`RangeChargerScreen.java:348-351`、`CollectionCacheScreen.java:1023-1027`
（另有 `:1026-1027` 的「容器包装槽」分支 + `isUpgradeSlotIndex` `:1089-1090`）、
`AdvancedQuantityKeeperScreen.java:502-504`、`QuantityKeeperScreen.java:378`、
`SchematicLoaderScreen.java:277-280`、`AdvancedSchematicLoaderScreen.java:365-368`。
**统一写法是 `hoveredSlot instanceof UpgradeSlot`** —— 这正是 `UpgradeSlot.forContainer`
必须返回真 `UpgradeSlot` 而不能返回匿名 `Slot` 的原因（`:142-150` 原注释记录了这个 bug）。

### 3.6 判断：**这套能不能直接用到新方块（接收器/请求器）上？**

**能，而且是逐字照抄级。** 四步：

1. **界面层建 6 个槽、白名单只有两类**：
   ```java
   // 照 RangeChargerMenu.java:44-45 的写法，只把 allowed 换成两类
   addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18,
       java.util.List.of("speed_upgrade", "stack_upgrade")));
   ```
   —— `UpgradeSlot.java:66-77` 的 6 参构造器（`countFromIndex` 传 0 即可，
   因为新方块的容器里插件槽之前没有 ghost 槽）。
2. **容器**：二选一，两种本工程都在用
   - `new ItemStackHandler(6) { onContentsChanged ⇒ validateUpgradeSlots() }`
     —— `RangeChargerBlockEntity.java:93-101`（**推荐**：`onContentsChanged` 能立刻校验）；
   - `new SimpleContainer(6)` + `UpgradeSlot.forContainer(...)`
     —— `CollectionCacheMenu.java:291-296`（**推荐给需要 `Container` 语义的场景**）。
3. **方块实体层兜底**：照抄 `SchematicLoaderBlockEntity.java:274-291`（`dropBeyondKindCap` 模式）
   或 `RangeChargerBlockEntity.java:304-349`（`upgradeKind` + `dropAndClear` 模式）。
   **必须做** —— 界面拦不住存档直改（`:646` 同义结论见既有文档）。
4. **注册 RS 目的地**：`RegisterUpgradeDestinations.java:29-53` 加一段（照 `register(...)` 写法），
   RS 升级物品的 tooltip 自动列出新机器 + 上限（`:20-21` 原注释）。

**要改什么**：
- 白名单字符串换成你的两类（`List.of("speed_upgrade", "stack_upgrade")`）；
- `RegisterUpgradeDestinations` 加一行（**同时确认新方块真的有槽位** —— 见 §3.2 坑 B）；
- 语言文件加空槽标题（`gui.rs_create_compat.upgrade_slot.empty`，`UpgradeSlotTooltips.java:50`）
  —— 已在，不用新增；
- ❌ **不要**把节拍直接交给 RS 的 `UpgradeContainer` ticker（见 §④ 坑 A）。

**升级数值口径的选择**（速度改节拍 / 叠加改单批）见 §④ 末。

---

## ④ RS 升级物品的**确切类名与语义**（2.0.9 字节码实证）

### 4.1 类名 —— **速度/叠加/丝触/自动合成 都是同一个类 `SimpleUpgradeItem`**

这是最容易搞错的一点：**RS 2.0.9 里没有 `SpeedUpgradeItem` / `StackUpgradeItem` 这两个类**。
它们是 `SimpleUpgradeItem` 的**静态工厂返回的实例**。

`javap com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem`（2.0.9 jar）：

```
public final class com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem
    extends com.refinedmods.refinedstorage.common.api.upgrade.AbstractUpgradeItem {
  private final java.util.function.LongSupplier energyUsageResolver;
  private final boolean foil;
  private com.refinedmods.refinedstorage.common.upgrade.SimpleUpgradeItem(UpgradeRegistry, LongSupplier, boolean, Component);
  public long getEnergyUsage();
  public boolean isFoil(ItemStack);
  public static SimpleUpgradeItem speedUpgrade();        // ← 速度升级
  public static SimpleUpgradeItem stackUpgrade();        // ← 叠加升级
  public static SimpleUpgradeItem silkTouchUpgrade();    // ← 丝触升级
  public static SimpleUpgradeItem autocraftingUpgrade(); // ← 自动合成升级
}
```

**完整升级物品类清单（2.0.9 jar 条目 + `javap`）**：

| 升级（物品 id） | 实现类 | 证据 |
| --------------- | ------ | ---- |
| `refinedstorage:speed_upgrade` 速度 | `...common.upgrade.SimpleUpgradeItem`（工厂 `speedUpgrade()`） | `javap SimpleUpgradeItem`；源码 `SimpleUpgradeItem.java:39-46` |
| `refinedstorage:stack_upgrade` 叠加 | 同上（工厂 `stackUpgrade()`） | `javap`；源码 `:48-55` |
| `refinedstorage:silk_touch_upgrade` 丝触 | 同上（工厂 `silkTouchUpgrade()`） | `javap`；源码 `:57-64` |
| `refinedstorage:autocrafting_upgrade` 自动合成 | 同上（工厂 `autocraftingUpgrade()`） | `javap`；源码 `:66-73` |
| `refinedstorage:range_upgrade` 范围 | `...common.upgrade.RangeUpgradeItem` | jar 条目 `com/refinedmods/refinedstorage/common/upgrade/RangeUpgradeItem.class` |
| `refinedstorage:regulator_upgrade` 调节 | `...common.upgrade.RegulatorUpgradeItem`（含内部类 `ExtendedMenuProviderImpl`、`RegulatorTooltipComponent`） | jar 条目清单 |
| `fortune_1/2/3_upgrade` 时运 | `...common.upgrade.FortuneUpgradeItem` | jar 条目清单 |
| `refinedstorage:creative_range_upgrade` 创造范围 | **⚠️ 未证实**：jar 里**没有** `CreativeRangeUpgradeItem.class`，`javap SimpleUpgradeItem` 也只列出 4 个静态工厂 | 见 §⑦ 第 1 条 |

**⇒ 对工程的实际影响**：本工程**从不用类名判断升级**，一律按**物品 `ResourceLocation` 的 path** 判
（`menu/UpgradeSlot.java:186-197` 的 `pathOf`）。**这是对的做法** —— 因为
「速度/叠加/丝触/自动合成」在 RS 里**共享同一个 Java 类**，用 `instanceof` 根本区分不开。

### 4.2 速度升级怎么起作用（影响什么？每 tick 几次？）

**结论：速度升级改的是 `doWork()` 的「调用周期」（多少 tick 调一次），
默认 9 tick 一次，每个速度升级 −2 tick。**

**证据链（全部 2.0.9 字节码）**：

1. **默认周期常量 = 9**
   `javap -p UpgradeContainer` → `private static final int DEFAULT_WORK_TICK_RATE;`，
   各便捷构造器里 `4: bipush 9` / `3: bipush 9` 后转发到主构造器。
   （源码：`UpgradeContainer.java:29` `private static final int DEFAULT_WORK_TICK_RATE = 9;`）
   本工程内部也记着这个数：`SequenceExecutionChamberBlockEntity.java:1065`
   （「必须 ≥ 输入总线默认搬运节拍（RS `UpgradeContainer.DEFAULT_WORK_TICK_RATE = 9`）」）。

2. **默认槽数 = 4**：`javap UpgradeContainer` 主构造器 `1: iconst_4`
   （源码 `UpgradeContainer.java:30` `DEFAULT_SIZE = 4`）。
   ⇒ **RS 官方升级容器默认只有 4 槽**，我们要 6 槽**必须走显式 size 的构造器**。

3. **速度升级 → 缩短周期**
   源码 `UpgradeContainer.java:148-149`：
   ```java
   final int amountOfSpeedUpgrades = getAmount(Items.INSTANCE.getSpeedUpgrade());   // :148
   ticker.workTickRate = defaultWorkTickRate - (amountOfSpeedUpgrades * 2);         // :149
   ```
   字节码（`notifyListener`）逐位对上：
   ```
   34: getfield  defaultWorkTickRate:I
   37: iload_1
   38: iconst_2
   39: imul
   40: isub
   41: putfield  ThrottledNetworkNodeTicker.workTickRate:I
   ```
   ⇒ **公式 = `workTickRate = defaultWorkTickRate − 2 × speedCount`**。

4. **节流语义 = 取模**
   源码 `UpgradeContainer.java:202-207`：
   ```java
   public void tick(final AbstractNetworkNode networkNode) {          // :203
       if (workTicks++ % workTickRate == 0) {                          // :204
           networkNode.doWork();                                       // :205
       }
   }
   ```
   字节码 `UpgradeContainer$ThrottledNetworkNodeTicker.tick`：
   ```
   2: getfield  workTicks:I
   7: iadd  /  8: putfield workTicks:I
   12: getfield workTickRate:I
   15: irem            // ← workTicks % workTickRate
   16: ifne 23
   20: invokevirtual ...AbstractNetworkNode.doWork:()V
   ```
   ⇒ **`workTickRate` = 「每多少 tick 工作一次」**（不是「每 tick 工作几次」）。

5. **算例**（`defaultWorkTickRate = 9`）：

   | speed 数 | `workTickRate` | 实际效果 |
   | -------- | -------------- | -------- |
   | 0 | 9 | 每 9 tick 一次（基线） |
   | 1 | 7 | 快 9/7 ≈ 1.29× |
   | 2 | 5 | 1.8× |
   | 3 | 3 | 3× |
   | 4 | 1 | **每 tick**（9×，官方上限） |
   | 5 | **−1** | ⚠️ `x % -1` 恒为 0 ⇒ **仍然每 tick**（但已越界） |
   | 6 | **−3** | ⚠️ `x % -3` ⇒ **每 3 tick**，**比 5 个更慢** |

6. **RS 自己把速度上限夹到 4**（所以上表 5/6 行在官方路径上不可达）：
   `AbstractModInitializer`（源码 `:514-546`）对每个目的地都是 `add(Items.INSTANCE.getSpeedUpgrade(), 4)`：
   IMPORTER `:515`、EXPORTER `:520`、DESTRUCTOR `:526`、CONSTRUCTOR `:533`、DISK_INTERFACE `:542`、AUTOCRAFTER `:546`
   —— 字节码里对应位置全是 `iconst_4`（已逐条核对）。
   ⇒ 官方路径下 `workTickRate ≥ 1`，**`% 0` 不可达**。

7. **自动合成仓是另一套**（不走 `UpgradeContainer` ticker）：
   `AutocrafterBlockEntity.getTickRate(int)`（源码 `:490-499`）：
   ```java
   return switch (amountOfSpeedUpgrades) {
       case 0 -> 10;  case 1 -> 8;  case 2 -> 6;  case 3 -> 4;  case 4 -> 2;
       default -> 0;      // ← 0！  ⚠️
   };
   ```
   `getSteps(int)`（源码 `:480-488`）：`1→2 / 2→3 / 3→4 / 4→5 / default→1`。
   字节码 `tableswitch { // 0 to 4 }` + `default: 59` + `bipush 10/8/6` 逐字对上。
   构造函数里 `steps = getSteps(0)` / `tickRate = getTickRate(0)`（源码 `:88-89`）。
   **同样因为注册上限 = 4，`default -> 0` 不可达。**

### 4.3 叠加升级怎么起作用（单次搬运量 ×N？）

**结论：是「有没有」的布尔量 —— 有就 ×64，与个数无关；而且 RS 只允许放 1 个。**

**证据链（2.0.9 字节码）**：

1. **导出总线**：`ExporterTransferQuotaProvider`（源码 `:16-27`）
   ```java
   this.baseTransferQuota = upgradeState.has(Items.INSTANCE.getStackUpgrade())   // :20-21
       ? singleAmount * 64
       : singleAmount;                                                          // :22
   ```
   字节码 `javap -p -c ...ExporterTransferQuotaProvider`：
   ```
   12: invokeinterface UpgradeState.has:(UpgradeItem)Z    // ← 只看 has
   17: ifeq 28
   20: lload_1
   21: ldc2_w #23   // long 64l
   24: lmul                                                // ← ×64
   25: goto 29
   ```
   ⇒ **`has(...) ? base × 64 : base`**。

2. **导入总线**：`ImporterTransferQuotaProvider`（源码 `:18-20`）**逐字同款**；
   字节码同样 `UpgradeState.has` + `ldc2_w 64l`。

3. **NeoForge 侧工厂也写死了 64**：`ItemHandlerImporterTransferStrategyFactory.java:27`
   `upgradeState.has(Items.INSTANCE.getStackUpgrade()) ? 64 : 1`（字节码 `ldc2_w #35 // long 64l`）。

4. **叠加升级上限 = 1**：`AbstractModInitializer` 里 EXPORTER 用
   `add(Items.INSTANCE.getStackUpgrade())`（源码 `:521`）—— **单参重载**。
   该默认实现的字节码（`javap UpgradeRegistry$DestinationBuilder`）：
   ```
   public default DestinationBuilder add(Item);
      0: aload_0
      1: aload_1
      2: iconst_1                       // ← 默认上限 = 1
      3: invokeinterface add:(Lnet/minecraft/world/item/Item;I)...
   ```
   ⇒ **叠加升级在 RS 里天然「有/无」，不存在「2 个叠加升级」**。

5. **所以 RS 的两个旋钮是正交的**：
   - **速度**：多久搬一次（`workTickRate`，受 `UpgradeContainer` 统一管，`:148-149`）；
   - **叠加**：一次搬多少（quota × 64，受 quota provider 管，`:20-22`）。

### 4.4 RS 的**输入/输出总线**具体怎么用这两套（对照）

**输出总线（Exporter）**：
- 容器与 ticker：`AbstractExporterBlockEntity.java:56`（`UpgradeContainer upgradeContainer`）、
  `:67-74`（`new UpgradeContainer(UpgradeDestinations.EXPORTER, listener)`，
  listener 里把 `baseEnergyUsage + upgradeEnergyUsage` 写回节点并 `setChanged()` + `initialize()`）、
  **`:75` `this.ticker = upgradeContainer.getTicker();`** ← 速度升级在这一行接进 tick 循环。
- 到 `EXPORTER` 目的地的允许清单：`AbstractModInitializer:519-524`
  = speed(4) + stack(1) + regulator(4) + autocrafting(1)。
- 单次量：`ItemHandlerExporterTransferStrategyFactory.java:38-45`
  ```java
  41: new ExporterTransferQuotaProvider(1, upgradeState, destination::getAmount, true)
  43: if (upgradeState.has(Items.INSTANCE.getAutocraftingUpgrade())) { ... }
  ```
- 调节升级怎么夹量：`ExporterTransferQuotaProvider.applyAsLong`（`:30-44`）
  （`desiredAmount − currentAmount`，`respectTransferQuotaWhenRegulating` 决定要不要再夹 `baseTransferQuota`）。

**输入总线（Importer）**：
- `AbstractImporterBlockEntity.java:70`（`new UpgradeContainer(UpgradeDestinations.IMPORTER, ...)`）；
- `AbstractModInitializer:514-517`（IMPORTER = speed(4) + stack(1) + regulator(4)）；
- 单次量：`ItemHandlerImporterTransferStrategyFactory.java:27`
  （`has(stackUpgrade) ? 64 : 1`）+ `ImporterTransferQuotaProvider`（`:15-24`）。

**⇒ 一句话对照**：
> **RS 的输入/输出总线：速度升级改「节拍」（`UpgradeContainer` 统一改 `workTickRate`），
> 叠加升级改「单批」（`QuotaProvider` 里 `has ? ×64 : ×1`）。**

### 4.5 判断：我们要做「**资源流动速度**」（一组原石一个一个过去，速度/叠加提高流速）—— **该照 RS 哪一套？**

**结论：照 RS 的「语义骨架」（速度 = 节拍、叠加 = 单批量），
但数值口径跟本工程既有（每级线性），并且「一个一个过去」本工程已经有现成答案。**

**理由 + 证据（4 条）**：

1. **RS 的 exporter/importer 就是「流速」的两个正交旋钮** —— 这正是用户要的模型：
   - 速度 = `doWork` 周期（`UpgradeContainer.java:148-149` 定义 + `:202-207` 生效）；
   - 叠加 = 单次 quota（`ExporterTransferQuotaProvider.java:20-22`）。
   两者相乘 = 单位时间搬运量。**直接照这个语义分配两个升级的职责即可。**

2. **但叠加的数值口径要跟本工程，不要跟 RS**：
   - RS = **布尔**（有就 ×64，上限 1 个，`UpgradeRegistry$DestinationBuilder.add(Item)` 的 `iconst_1`）；
   - 本工程 = **每级线性**（`CollectionCacheBlockEntity.getTransferBatch()` `:2577-2579` = `64 × (1 + n)`；
     `SchematicLoaderBlockEntity.storageSlotCapacity` `:447-450` = `64 × (1 + min(n,6))`，
     且 `:442-446` 注释自称「唯一实现」）。
   **建议跟本工程**：新方块与既有 6 台机器的口径一致，玩家不用学两套；
   而且我们 `MAX_PER_UPGRADE = 6` 的槽位本来就能放 6 个，跟 RS 的「只能放 1 个」不兼容。

3. **「一组原石一个一个过去」本工程已经有现成答案 —— `INPUT_FEED_UNIT = 1L`**：
   `support/RsccChamberExportStrategy.java:86-90`：
   > 「**输入时原料**单次搬运量上限（**用户验收标准 #2.1**）：即使装了堆叠升级把 RS 的导出配额
   > 放大到 > 1，属于「输入性产物 / 流体输入」类别的资源也一律**一次一份** ——
   > 单台机械手 / 单台机器才不会被一次塞满而堵塞。」
   ```java
   private static final long INPUT_FEED_UNIT = 1L;    // :90
   ```
   **⇒「一个一个过去」与「升级提高流速」并不冲突，分工是**：
   - **速度升级** ⇒ 提高**节拍**（每秒过几批）→ 想「一个一个且越来越快」就**只装速度升级**；
   - **叠加升级** ⇒ 提高**单批量**（一批几个）→ 想「一次多个」才装叠加升级。
   **照这个分工写进 tooltip，玩家就不会误解**（这与用户原话「速度升级对叠升级这样子」一致）。

4. **⚠️ 不要照抄 RS 的 `UpgradeContainer` ticker 落地**（坑 A）：
   - RS 把速度上限夹在 **4**（`AbstractModInitializer:515`/`:520` 等，字节码 `iconst_4`），
     所以 `9 − 2n` 永远 ≥ 1；
   - **本工程 `UpgradeSlot.MAX_PER_UPGRADE = 6`**（`menu/UpgradeSlot.java:29`），
     而 `RegisterUpgradeDestinations.java:30-52` 全表用的就是这个 6；
   - 若把 6 个速度升级喂给 RS 的 ticker ⇒ `workTickRate = −3` ⇒ **每 3 tick 才工作一次（比 4 个慢 3 倍）**；
     更糟的情况（`defaultWorkTickRate` 为偶数时）`workTickRate` 可能为 **0** ⇒
     `workTicks % 0` ⇒ **`ArithmeticException`**。
   - **本工程既有做法一律先夹再算**：`Math.min(countUpgrades(SPEED_UPGRADE), 6)`
     —— `QuantityKeeperBlockEntity.java:575`、`SchematicLoaderBlockEntity.java:437`、
     `RangeChargerBlockEntity.java:325`（`kind == 3 ? 1 : UpgradeSlot.MAX_PER_UPGRADE`）。
     **新方块照这个走**：自己算节拍（照 `CollectionCacheBlockEntity.getScanInterval()` `:445-450`
     或 `getProcessRate()` `:2572-2574` 的写法），**不要**把 `workTickRate` 交给 RS 的 ticker 去减。

**⇒ 落地建议（给新方块的三个数）**：

| 项 | 建议 | 照哪一处 |
| -- | ---- | -------- |
| 节拍（多久一次） | `interval = max(1, base / (1 + speedCount))` 或 `每 tick 处理 (1 + speedCount) 批` | `CollectionCacheBlockEntity.java:445-450` / `:2572-2574` |
| 单批量 | `batch = 1 × (1 + stackCount)`（要「一个一个」就保持 1，不乘） | `RsccChamberExportStrategy.java:86-90`（`INPUT_FEED_UNIT = 1L`） |
| 速度计数上限 | `Math.min(countUpgrades(SPEED_UPGRADE), UpgradeSlot.MAX_PER_UPGRADE)` | `QuantityKeeperBlockEntity.java:575`、`SchematicLoaderBlockEntity.java:437` |

---

## ⑤ 既有方块的「朝向 + 输出到目标容器」实现（顺带）

### 5.1 四种既有范式（**都在用，按场景挑**）

**(a) 面配置驱动 —— 执行仓（按 `FaceMode` 逐面）**
`block/entity/SequenceExecutionChamberBlockEntity.java`：
```java
private List<IItemHandler> itemHandlersFor(final FaceMode mode) {                  // :15010
    for (final Direction direction : Direction.values()) {                         // :15016
        if (getFaceMode(direction) != mode) { continue; }                          // :15017-15019
        final BlockPos pos = worldPosition.relative(direction);                    // :15020
        final BlockEntity neighbor = level.getBlockEntity(pos);                    // :15021
        if (neighbor == null) { continue; }                                        // :15022-15024
        final IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
            pos, neighbor.getBlockState(), neighbor, direction.getOpposite());     // :15025-15026
        if (handler != null) { handlers.add(handler); }
    }
}
```
- 流体对称：`:15054-15076`（`Capabilities.FluidHandler.BLOCK`，同样 `direction.getOpposite()`）。
- **注意方向语义**：查**邻居**的能力时传 `direction.getOpposite()`（= 邻居看向我们的那一面）。

**(b) 单点相邻静态工具 —— 导入策略（**推荐给新方块**）**
`support/RsccChamberImportStrategy.java`：
```java
@Nullable public static IItemHandler itemHandlerAt(final Level level, final BlockPos pos) {   // :1577
    if (!level.isLoaded(pos)) { return null; }                    // :1578-1580（未加载不误判）
    final BlockEntity blockEntity = level.getBlockEntity(pos);    // :1581
    if (blockEntity == null) { return null; }                     // :1582-1584
    final BlockState state = blockEntity.getBlockState();         // :1585
    final IItemHandler unsided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, blockEntity, null);  // :1586-1587
    if (unsided != null) { return unsided; }                      // :1588-1590
    for (final Direction direction : Direction.values()) {        // :1591  ← 逐个面再试
        final IItemHandler side = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, blockEntity, direction);  // :1592-1593
        if (side != null) { return side; }
    }
    return null;                                                  // :1598
}
```
- 流体对称：`:1608-1630`。
- **为什么两种都试**（`:1570-1572` 原注释）：「Create 的置物台一类的方块对六个面返回同一个 handler，
  而无侧面查询在部分实现里可能为 null；反过来也有只按面暴露的方块。**逐个试是「不猜朝向也能收」的最稳做法**」。
- **public 的理由**（`:1573-1574`）：执行舱的待加工步探针读的是同一批供料目标的能力，
  「共用本方法才不会出现『收回读得到、探针读不到』的两套口径」。
- **已在实际使用**：归流缓存仓逐面抽料 —— `CollectionCacheBlockEntity.java:1400-1401`。

**(c) 输出策略（总线 → 朝向容器，**带 RS 配额**）**
`support/RsccChamberExportStrategy.java`：
```java
private final CapabilityCacheImpl destination;                                  // :140
private final ToLongFunction<ResourceKey> itemQuota;                            // :142
private final ToLongFunction<ResourceKey> fluidQuota;                           // :143

this.destination = new CapabilityCacheImpl(level, targetPos, targetFace);        // :187
final ItemHandlerInsertableStorage itemDestination = new ItemHandlerInsertableStorage(destination);   // :188
final FluidHandlerInsertableStorage fluidDestination = new FluidHandlerInsertableStorage(destination); // :189
this.itemQuota = new ExporterTransferQuotaProvider(1, upgrades, itemDestination::getAmount, true);     // :190
this.fluidQuota = new ExporterTransferQuotaProvider(
    Platform.INSTANCE.getBucketAmount(), upgrades, fluidDestination::getAmount, true);                 // :191-192
```
- **`:190` 就是把 RS 的叠加/调节升级配额直接接进自定义搬运的关键一行**。
- 守恒写法（**先模拟后执行 + 余量回写**）：
  `:689` `ItemHandlerHelper.insertItem(target, pick, true)`（模拟）→
  `:715` `ItemHandlerHelper.insertItem(target, extracted, false)`（执行）→
  `:716-717` 统计 `inserted` → `:721` `chamberStore.insertItem(0, remainder, false)`（**余量回写执行舱**）。
- 脱绑回退 = RS 原版 `ExporterTransferStrategyImpl`（`:199-204`），
  且配额过一道「在途任务预留」夹量（`:193-198` 原注释 + `:430-459` 实现）。
- 类注释把流向讲得最清楚（`:36-47`）：
  > `RS 网络 ──(执行舱搬运)──▶ 执行舱内部存储 ──(本策略)──▶ 本总线朝向的机器`

**(d) 朝向属性 + 取向**
- 执行仓的朝向 = **6 向** `BlockStateProperties.FACING`：
  `block/SequenceExecutionChamberBlock.java:52`（`public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;`）；
  放置时决定 `:105`、`:133-134`（视线朝下 ⇒ `UP`、朝上 ⇒ `DOWN`）、`:149-164`（为什么必须 6 向、
  以及「property 名都是 `facing` ⇒ 不影响老存档」）。
- 方块实体侧缓存朝向：`SequenceExecutionChamberBlockEntity.java:1409-1410`（构造时读 FACING）、
  `:1471-1472`（方块状态变化时更新）、`:12935-12936`（`chainFace()`）。
- **RS 输出总线怎么取朝向**（我们的 mixin）：
  `mixin/exporter/AbstractExporterBlockEntityMixin.java:487-491`：
  ```java
  final Direction direction = AbstractDirectionalBlock.tryExtractDirection(self.getBlockState());  // :489
  return direction == null ? null : self.getBlockPos().relative(direction);                        // :490
  ```
  —— 注释 `:481-483` 说明「与 `rscc$installChamberStrategy` 里算 target 的那一句完全同源，
  因此『策略实际往哪推』与『说明书告诉执行舱的供料目标』必然一致，**不存在两套朝向判定**」。
- 策略安装点：`:856-889`（`AbstractDirectionalBlock.tryExtractDirection` `:866`）；
  导入总线的对称实现：`mixin/importer/AbstractImporterBlockEntityMixin.java:9`、`:558`。

### 5.2 上层：`BusExport` 路径（总线导出清单怎么被我们接管）

- `SequenceExecutionChamberBlockEntity.busExportFilters(BlockPos exporterPos)`（`:6129`）——
  链级调度，返回「这条输出总线该导出哪些资源」；
  单仓实现 `ownBusExportFilters(...)`（`:6178`）；单类别判定（`:6200-...`）。
- 匹配判定：`busExportMatches(...)`（`:14504`）、`busExportAcceptsForPush(...)`（`:14549`）
  （`:14522-14542` 解释了推料侧为什么要比匹配侧宽一档）；过滤项的步序豁免（`:2929`）。
- 清单刷新节流：`busExportRefreshCooldown`（字段 `:575`）→ `:10802-10803`
  （`BUS_SCHEDULE_INTERVAL_TICKS`）。
- **接进 RS 节点**：`mixin/exporter/AbstractExporterBlockEntityMixin.java:798-812`
  （`rscc$applyExportFilters()`：绑定执行舱时 `exporterNode.setFilters(executor.busExportFilters(...))` `:811`；
  未绑定则恢复玩家自己的过滤器 `:808`）；
  接管 `setFilters` 的注入点 `:816-824`。
- 导出侧的策略实现：`support/RsccChamberExportStrategy.java`（类注释 `:36-83` 把
  「不丢不复制」「按步过滤放宽」「门控」「脱绑」四条都写明了）；
  该策略只**加**结构化日志（`:80-82`）。

### 5.3 判断：**这套能不能直接给「接收器」用？**

**能，两档可选**：

- **最省事（推荐先做）**：接收器只需「把缓存推到 `FACING` 那一格」⇒ **直接用 (b)**：
  ```java
  final BlockPos target = worldPosition.relative(facing);              // 照 SequenceExecutionChamberBlockEntity:12935-12936
  final IItemHandler h = RsccChamberImportStrategy.itemHandlerAt(level, target);   // :1577
  final IFluidHandler f = RsccChamberImportStrategy.fluidHandlerAt(level, target); // :1608
  ```
  —— 与归流缓存仓 `CollectionCacheBlockEntity.java:1400-1401` **同一个调用范式**，
  且自带「未加载不误判」（`:1578`）+「无侧面查询兜底」（`:1586-1597`）。
  **注意**：`itemHandlerAt` **不传面**（它先试 `null` 再试 6 面），所以「朝向那一格」由你算，
  「用哪一面」交给它兜底 —— 这对 Create 置物台那类方块是必需的（`:1570-1572`）。

- **要配额/要守恒审计时（加强版）**：照 (c) 用
  `CapabilityCacheImpl(level, targetPos, targetFace)` + `ItemHandlerInsertableStorage` +
  `ExporterTransferQuotaProvider`（`RsccChamberExportStrategy.java:187-192`），
  并照 `:689`/`:715`/`:721` 写「先模拟 → 再执行 → 余量回写」。
  **⚠️ 但要注意**：RS 的 `ExporterTransferQuotaProvider` 是「**有叠加就 ×64**」（§4.3），
  与我们要的「每级 ×N」不同 —— 要么自己写 quota（照 `CollectionCacheBlockEntity.getTransferBatch()` `:2577-2579`），
  要么接受 RS 的口径并写进 tooltip。

- **朝向属性**：直接照 `block/SequenceExecutionChamberBlock.java:52`（6 向 `FACING`）
  + `:149-164` 的「为什么 6 向、为什么不破老存档」论证；取向用
  `AbstractDirectionalBlock.tryExtractDirection` 的写法（`AbstractExporterBlockEntityMixin.java:489`）
  或直接 `state.getValue(FACING)`（`SequenceExecutionChamberBlockEntity.java:12935-12936`）。

- **不要照抄 RS 的 Exporter 注册表 / 策略链**：执行仓 / 缓存仓 / 归流缓存仓**一贯做法**是
  「自己的 tick 循环 + `Capabilities.*` 直接查」（这正是 (a)(b)(c) 三种范式的共同点）。

---

## ⑥ 可直接复用的清单 与 需要新写的部分

### 6.0 顺带盘点：新方块相关的**既有资产**（`git status` 显示的文件）

跑 `git status --porcelain` 时发现，**上一轮已经产出了接收器/请求器的方块贴图**（均为未跟踪的新文件）：

| 资产 | 路径 | 说明 |
| ---- | ---- | ---- |
| 接收器方块贴图 ×12 | `src/main/resources/assets/rs_create_compat/textures/block/network_resource_receiver_{front,back,left,right,top,bottom}_{active,inactive}.png` | 6 面 × 2 状态（active/inactive） |
| 请求器方块贴图 ×12 | `src/main/resources/assets/rs_create_compat/textures/block/network_resource_requester_{front,back,left,right,top,bottom}_{active,inactive}.png` | 同上 |
| 贴图生成/校验脚本 | `tools/gen_network_io_textures.py`、`tools/analyze_block_palette.py`、`tools/verify_network_io_textures.py` | 未跟踪 |

**两条可用的结论**：
1. **方块 id 已经定了**：`network_resource_receiver` / `network_resource_requester`
   —— 命名与 §③.2 表格里的机器同一套风格（`collection_cache`、`range_charger`…），
   因此 `RegisterUpgradeDestinations.java:29-53` 的 `register(...)` 直接加
   `register("network_resource_receiver", "speed_upgrade", ..., "stack_upgrade", ...)` 即可。
2. **「6 面 × active/inactive」这一贴图规格 = 一个有朝向 + 有工作状态的方块**，
   与 §⑤ 的 `FACING` + 既有机器完全同构（对照 `SequenceExecutionChamberBlock.java:52`）。

**⚠️ 缺口（影响「升级槽界面」的工作量）**：`src/main/resources/assets/rs_create_compat/textures/gui/`
下**没有**接收器/请求器的 GUI 贴图（该目录里只有既有机器与 `receiver`/`requester` 无关的贴图）；
`assets/rs_create_compat/lang/zh_cn.json` 里也**没有任何** `receiver` / `requester` 语言键（`Select-String` 零命中）。
⇒ 界面要么走 `ChildConfigScreen` 的**原版九宫格默认面板**（`ChildConfigScreen.java:41`/`:94-101`，
**零新贴图**），要么需要另出 GUI 贴图。

### 6.1 ✅ 可直接复用（一句话一条，带行号）

**选择界面（①/②）**
1. `ScrollSelectWidget<T>` 整套滚轮选择控件（**纯泛型、零业务耦合、链式配置、自带 tooltip**）—— `client/widget/ScrollSelectWidget.java:28`；已在 4 处复用：`StepMachineSelectScreen.java:106`、`ChamberBindingConfigScreen.java:166`、`UnitPatternConfigScreen.java:138`、`AssemblyPatternRebindScreen.java:82`。
2. `ChildConfigScreen` 子窗口骨架（父界面变暗、居中面板、`returnToParent`、Esc 返回）—— `client/screen/ChildConfigScreen.java:24`、`:60-74`、`:104-116`。
3. 「**原版 `EditBox` 搜索框 + `setResponder` 即时过滤 + 空候选灰显**」这套搜索写法 —— `StepMachineSelectScreen.java:116-122`、`:138-152`。
4. 「**过滤后仍保持当前已指派项被选中**」的收敛逻辑 —— `StepMachineSelectScreen.java:132-137`、`:155-162`。
5. 「**回车 = 确定**」—— `StepMachineSelectScreen.java:241-248`。
6. 「**调用方自带发包**」的 `confirmSink` 解耦口 —— `StepMachineSelectScreen.java:88-92`、`:165-180`；用法 `AssemblyAlertsClient.java:202-210`、`AssemblyPatternRebindScreen.java:176-180`。
7. 「**手动渲染 tooltip**（hover 判定与绘制范围同源）」的统一写法 —— `ScrollSelectWidget.java:220-230`、`:278-281`；界面侧 `StepMachineSelectScreen.java:201-234`。
8. 「**Alt/Shift/Ctrl 分层 tooltip**」的唯一入口 —— `client/tooltip/RsccTooltipLayers.java:53`（`enum Layer`）、`:107`（`componentVisible`）、`:113`/`:117`/`:121`（`shift`/`ctrl`/`alt`）、`:136`/`:157`（`append`）、`:166`/`:175`（`render`）、`:207`/`:219`（`renderAttached`）；用法示例 `AssemblyPatternRebindScreen.java:88-95`、`StepMachineSelectScreen.java:201-233`。
9. ghost 标记的**物品/流体/气体统一绘制与候选轮播**工具 —— `client/widget/GhostMarkerRenderer.java:46`（`:179` `displayItem`、`candidateItems`、`cycleCandidate`、`fluidName`、`fluidAmount`、`renderFluid`、`renderLines`）。
10. JEI 拖拽的 `IGhostIngredientHandler` 骨架（4 份同款，抄任意一份即可）—— `client/SequenceTerminalJeiPlugin.java:46`/`:228-229`、`QuantityKeeperJeiPlugin.java:31`/`:35-36`、`AdvancedQuantityKeeperJeiPlugin.java:32`/`:36-37`、`CollectionCacheJeiPlugin.java:39`/`:43-44`。

**升级槽（③）**
11. `UpgradeSlot` 整类（白名单 `mayPlace`、每槽 1 个、单种上限、ghost 计数起点、`forContainer` 四重载）—— `menu/UpgradeSlot.java:27`、`:114-125`、`:104-112`、`:90-92`、`:66-77`、`:151-184`。
12. 「**只收两类升级**」的写法 —— `new UpgradeSlot(handler, i, x, y, List.of("speed_upgrade", "stack_upgrade"))`（构造器 `menu/UpgradeSlot.java:58-64`）；对照 `RangeChargerMenu.java:44-45`。
13. 6 槽容器两种写法 —— `RangeChargerBlockEntity.java:93-101`（`ItemStackHandler(6)` + `onContentsChanged` 校验）；`CollectionCacheMenu.java:291-296`（`SimpleContainer(6)` + `forContainer`）。
14. 方块实体层**清退非法/超上限升级**（弹出到世界，不销毁）—— `SchematicLoaderBlockEntity.java:274-291`、`RangeChargerBlockEntity.java:304-349` + `:352-365` + `:367-376`、`CollectionCacheBlockEntity.java:2508-2556`。
15. 空升级槽 tooltip（图标 + 名称 + 单种上限，**常显**，照搬 RS 原版）—— `client/tooltip/UpgradeSlotTooltips.java:45-59`、`:95-101`；组件 `UpgradeItemTooltipComponent.java:13`、`:17`。
16. 已放**叠加升级**时的「本机实际作用」行 —— `UpgradeSlotTooltips.java:117-156`。
17. RS 升级**目的地注册**（让 RS 升级物品 tooltip 自动列出新机器 + 上限）—— `RegisterUpgradeDestinations.java:28-54`、`:72-98`。
18. 速度/叠加的**既有数值口径** —— 速度：`CollectionCacheBlockEntity.java:2572-2574`（`1 + n`）、`:445-450`（间隔缩放）；叠加：`CollectionCacheBlockEntity.java:2577-2579`（`64 × (1+n)`）、`SchematicLoaderBlockEntity.java:447-450`（自称「唯一实现」）。
19. 「**速度计数先夹再算**」的防坑写法 —— `QuantityKeeperBlockEntity.java:575`、`SchematicLoaderBlockEntity.java:437`、`RangeChargerBlockEntity.java:325`。

**朝向输出（⑤）**
20. 朝向容器查询（**推荐**）—— `support/RsccChamberImportStrategy.java:1577-1599`（物品）、`:1608-1630`（流体）。
21. 面配置驱动的逐面容器的查询 —— `SequenceExecutionChamberBlockEntity.java:15010-15032`（物品）、`:15054-15076`（流体）。
22. 「先模拟 → 再执行 → 余量回写」的守恒搬运 —— `RsccChamberExportStrategy.java:689`、`:715`、`:716-717`、`:721`。
23. RS 叠加/调节升级配额接进自定义搬运 —— `RsccChamberExportStrategy.java:190-192`。
24. 6 向 `FACING` 朝向属性（含「为什么不破老存档」的论证）—— `block/SequenceExecutionChamberBlock.java:52`、`:105`、`:133-134`、`:149-164`。
25. 从方块状态取朝向 / 算朝向目标 —— `mixin/exporter/AbstractExporterBlockEntityMixin.java:487-491`、`SequenceExecutionChamberBlockEntity.java:12935-12936`。
26. 网络侧骨架（继承 RS 基类 + 能力注册）—— `IntermediateCacheBlockEntity.java:56-57`、`:236-242`；以及 `getNode().getNetworkOrNull()`（`:128`）。
27. 名字唯一性 / 「未命名 = 不可用」的既有同型实现（接收器直接照抄）—— `SequenceExecutionChamberBlockEntity.java:13695-13700`、`:13711-13724`、`:13682-13686`。
28. 「只收中间产物」等存储装饰器的**思路**（返回 0 = 换下一个源是 RS 官方约定）—— `support/RsccIntermediateOnlyStorage.java:23-25`（「返回 0」是官方约定）、`:51-63`（`insert` 过滤：只收中间产物，其余返回 0）、`:65-70`（`extract` 不设限）。

### 6.2 ❌ / ✏️ 需要新写

1. **「选任意方块 / 任意目标」的通用选择器**。现状：`StepMachineSelectScreen` 的候选类型被钉死为
   `SyncChamberListPacket.Entry`（`:64`、`:74`），三行文字是机器语义（`:189-194`），
   默认回传发 `SetStepMachinePacket`（`:177`）。
   **做法**：**优先**用 `ScrollSelectWidget<T>` + `ChildConfigScreen` **另写一个薄弹窗**
   （搜索/过滤/回车/返回/手动 tooltip 全部照抄 `StepMachineSelectScreen` 的骨架）；
   **次选**才去泛型化既有类（回归面大，因为它已被 3 处复用）。
2. **选物品 / 流体的界面**。工程里**没有**（只有 4 个 JEI handler + 1 个「选类别」界面，见 §②）。
   若请求器要「设定一下什么资源」而**不依赖 JEI**，必须新写：
   可复用 `ScrollSelectWidget<T>`（列表）、`GhostMarkerRenderer.java:46`（图标）、
   以及 `PlatformResourceKey` / `ResourceCodecs.STREAM_CODEC` 做序列化
   （既有用法：`storage/UniversalStorageType.java:33`、`network/SyncAssemblyAlertsPacket.java:156`）。
3. **升级节拍器**。**不要**把 `workTickRate` 交给 RS 的 `UpgradeContainer` ticker
   （坑 A：`9 − 2n` 在 `n = 5/6` 时非单调；偶数 `defaultWorkTickRate` 下可能为 0 ⇒ `ArithmeticException`）。
   自己算，照 `CollectionCacheBlockEntity.java:445-450` / `:2572-2574`。
4. **名字注册表 + 判重 + 失败提示**（接收器/请求器）—— 见 `docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md` §④；
   既有同型实现见上表第 27 条。
5. **红石上升沿触发 + pending 持久化**（请求器）—— 同上文档 §⑦.3。
6. **气体 / 化学品**：**完全没有实现**（同文档 §⑥）。
7. **接收器缓存「给玩家看/取」的界面**（若要）—— 可参考 `CollectionCacheScreen.java` 的
   ghost 区 + 缓存区布局（`:36`、`:929-950`）与 `CollectionCacheMarkerConfigScreen` 的编辑范式。

### 6.3 ⚠️ 坑（复用前必须知道）

| # | 坑 | 证据 | 规避 |
| - | -- | ---- | ---- |
| **A** | RS 的 `workTickRate = 9 − 2n` 在 `n ≥ 5` 时为**负**，行为**非单调**（5 → 每 tick，6 → 每 3 tick，**更慢**）；若 `defaultWorkTickRate` 为偶数，`n` 恰好使其为 **0** ⇒ `workTicks % 0` ⇒ **`ArithmeticException`** | `UpgradeContainer.java:148-149`；`:202-207`（`irem`）；`DEFAULT_WORK_TICK_RATE = 9`（`:29`，字节码 `bipush 9`）；RS 官方上限 4（`AbstractModInitializer:515`/`:520` 等，字节码 `iconst_4`）；本工程上限 6（`menu/UpgradeSlot.java:29`） | 自己算节拍；速度计数先 `Math.min(n, 6)`（照 `QuantityKeeperBlockEntity.java:575`） |
| **B** | `RegisterUpgradeDestinations.java:51-53` 把 `sequence_assembly_executor` 注册成吃 `speed_upgrade`(6) + `autocrafting_upgrade`(1)，但**该方块根本没有升级槽** ⇒ RS 升级物品 tooltip **谎报**一个不存在的目的地 | 容器只有样板槽 `SequenceAssemblyExecutorBlockEntity.java:68`；菜单只有 `PatternSlot` + 玩家槽 `SequenceAssemblyExecutorMenu.java:65`/`:76`/`:81`；界面 `grep Upgrade` 零命中；方块实体 `grep speed_upgrade` 零命中 | 注册目的地前**逐台确认真有槽位**；建议顺手核对/修正该条 |
| **C** | 非样板终端的两个入口（监视器 `AssemblyAlertsClient.java:202`、改绑 `AssemblyPatternRebindScreen.java:176`）**必须传 `confirmSink`**；不传就会走默认路径发 `SetStepMachinePacket(globalStep, ...)`（`StepMachineSelectScreen.java:176-177`），把「任务里的步骤号」当成「样板终端的全局步骤号」 | `StepMachineSelectScreen.java:165-180` | 复用该界面时**永远**传 `confirmSink`；或另写薄弹窗避免这个陷阱 |
| **D** | 每槽只 1 个 + 单种上限 6，但**上限是「槽内物品个数之和」而不是「槽位数」** —— `countOfKindInHandler` 数的是 `inSlot.is(item)` 的**槽数**（每槽最多 1 个，所以等价），但 `RangeChargerBlockEntity.countUpgrades` 用的是 `count += stack.getCount()`（`:147`） | `UpgradeSlot.java:127-140` vs `RangeChargerBlockEntity.java:144-149` | 两处口径在「每槽 1 个」前提下等价；**若将来放开每槽堆叠，两处会不一致** |
| **E** | `CollectionCacheBlockEntity.enforceUpgradeCaps()` **硬写了 `6` 与 `1`**（`:2527`、`:2532`、`:2537`、`:2542`），没有引用 `UpgradeSlot.MAX_PER_UPGRADE` | 同左 | 新方块**不要再硬写**，引 `UpgradeSlot.MAX_PER_UPGRADE` |

---

## ⑦ 未证实 / 需要确认（诚实清单）

### 7.1 未证实（我没有证据，不敢猜）

| # | 未证实的点 | 我做了什么 / 还缺什么 |
| - | ---------- | -------------------- |
| 1 | **`creative_range_upgrade` 在 2.0.9 里由哪个类承载** | 2.0.9 jar 的升级类清单里**没有** `CreativeRangeUpgradeItem.class`；`javap SimpleUpgradeItem` 只列出 4 个静态工厂（speed/stack/silkTouch/autocrafting）。它可能是 `SimpleUpgradeItem` 的第 5 个工厂（但字节码里没有）或另一个现有类。**未证实**。工程侧不受影响：本工程一律按物品 id `refinedstorage:creative_range_upgrade` 处理（`menu/UpgradeSlot.java:33`、`:90-92`；`RangeChargerBlockEntity.java:45-46`） |
| 2 | **`FortuneUpgradeItem` / `RangeUpgradeItem` / `RegulatorUpgradeItem` 的方法签名与内部行为** | 我只从 jar 条目清单确认**类存在**，**没有逐一 `javap -c` 它们的构造器与方法体**。它们在 2.0.9 里怎么注册、`RangeUpgradeItem` 的「每级 +N」实现在哪，**未证实** |
| 3 | **`workTickRate = 0` 是否真的会抛 `ArithmeticException`** | `irem` 语义上会抛（Java `%` 对 0 抛）；RS 官方路径上限 4 ⇒ 不可达。但**若我们自己注册 max=6**，「偶数 `defaultWorkTickRate` + 恰好为 0」的组合**我没有实测**。⇒ **规避**：自己算节拍 + `Math.min(n, 6)`（§6.3 坑 A） |
| 4 | **`StepMachineSelectScreen` 是否存在 grep 找不到的第 4 个调用点** | 我用 `grep StepMachineSelectScreen` 找到 3 处 `new`（`SequencePatternTerminalScreen.java:797`、`AssemblyAlertsClient.java:202`、`AssemblyPatternRebindScreen.java:176`）。若将来通过反射/字符串构造，grep 找不到。**按现有代码为准** |
| 5 | **`ScrollSelectWidget` 是否有我漏掉的第 5 个使用点** | 同上，grep `ScrollSelectWidget` 命中 4 个界面 + 自身定义。**按现有代码为准** |
| 6 | **`RegisterUpgradeDestinations` 里「序列装配执行器有升级槽」是否是我误读** | 我用 4 条独立证据（容器、菜单、界面、方块实体）都指向「没有升级槽」。但**该注册项是不是有意为之的占位（例如将来要加槽）**，我**无法从代码判断**。⇒ 见 §7.2 Q1 |
| 7 | **RS 的 `UpgradeContainer` 与 `AbstractModInitializer` 在 2.0.9 与我引用的 post-2.0.9 源码之间是否有行为差异** | 我对**所有关键数值与判据**都做了 2.0.9 字节码比对（`bipush 9` / `iconst_2` / `imul` / `isub` / `irem` / `ldc2_w 64l` / `iconst_4` / `iconst_1`），**逐条一致**。但**行号来自 post-2.0.9 检出，不是 2.0.9 的行号** —— 若要 100% 严谨，需 RS 2.0.9 的 sources jar（本机没有） |
| 8 | **`docs/` 下既有文档的完整覆盖情况** | `docs/` 现有 16 个文件（含本文件）：`BLOCKAGE_EXPERIMENT_PROTOCOL.md`、`cc_rework_contract.md`、`dependency-policy.md`、`DESIGN_DECISIONS_ROUND4.md`、`GEAR_BLOCKAGE_AND_FLOW_CHECK.md`、`GUI_FEEDBACK_ROUND2.md`、`GUI_FEEDBACK_ROUND3.md`、`NETWORK_IO_PALETTE_REPORT.md`、`NETWORK_RESOURCE_RECEIVER_DESIGN.md`、`P2P_SEND_UI_OPTIONS.md`、`QUANTITY_KEEPER_MULTI_MARK_DESIGN.md`、`ROUND_LOG_INSTRUMENTATION_20261004.md`、`ROUND_TRACK_CANDIDATES_20261004.md`、`SEQUENCE_ASSEMBLY_LOG_GUIDE.md`、`SEQUENCE_CHAIN_REDESIGN.md`、`REUSE_SELECTION_UI_AND_UPGRADES.md`（本文件）。**已通读**：`NETWORK_RESOURCE_RECEIVER_DESIGN.md`（1139 行）、`P2P_SEND_UI_OPTIONS.md`（前 619 行 + 全部标题 + 推荐章节）、`NETWORK_IO_PALETTE_REPORT.md`（头部）。**未逐字通读**：其余 12 份（`DESIGN_DECISIONS_ROUND4.md` 体量很大，只在别处引用到 `:2124`、`:3228` 两处）。⇒ 「② 没有选资源界面」这条**以代码为准**（`grep` 全 `src/main/java` 确认），不以 docs 为准 |

### 7.2 需要用户确认（这些是**决策**，不是事实）

| # | 问题 | 我的建议 | 影响面 |
| - | ---- | -------- | ------ |
| **Q1** | `RegisterUpgradeDestinations.java:51-53` 的 `sequence_assembly_executor` 条目：**是修掉（删/改上限）还是补上槽位**？ | **先确认真实意图**；若序列装配执行器本就不该吃升级，**删掉该条目**（否则 RS tooltip 一直谎报）；若将来要加槽，**加注释标明「占位」** | 玩家对 RS 升级物品 tooltip 的信任 |
| **Q2** | 「选任意方块/目标」用**新写薄弹窗**（复用 `ScrollSelectWidget`）还是**泛型化 `StepMachineSelectScreen`**？ | **新写薄弹窗**（既有类已被 3 处复用，改它回归面大） | 工作量 vs 回归风险 |
| **Q3** | 请求器的「设定什么资源」**是否必须自带选择界面**（不依赖 JEI）？ | 若玩家可能不装 JEI ⇒ **必须自带**；否则可先用 JEI ghost 拖拽 + 手持物品点击（照 `QuantityKeeperMenu.java:251-252` 的语义） | 界面工作量 |
| **Q4** | 叠加升级的**数值口径**：跟 RS（有就 ×64、上限 1）还是跟本工程（每级 ×N、上限 6）？ | **跟本工程（每级 ×N）**，理由见 §4.5 第 2 条 | 数值平衡、tooltip 文案、`RegisterUpgradeDestinations` 里的上限 |
| **Q5** | 「一组原石一个一个过去」是**硬约束**（单批恒为 1）还是**默认值**（叠加升级可以放大）？ | 按 `RsccChamberExportStrategy.java:86-90` 的既有精神：**对「输入性原料」硬约束为 1，其余按配额** | 「流速」手感、是否需要叠加升级 |
| **Q6** | 接收器/请求器**两边都装 6 槽**，还是只接收器装？ | 见 `docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md` §1.4 / Q2（该文档建议「都装」，理由：速度=每 tick 几批、叠加=每批多少，两个旋钮缺一不可） | 界面、tooltip、批量投递能否加速 |

### 7.3 本轮的**未做**（遵守任务硬约束）

- ❌ **未修改任何 Java 源码**（本任务只做调研）。
- ❌ **未改动 `run/`**。
- ❌ **未解压任何 jar 到工作区**（RS 2.0.9 全部用 `javap` 直读；只读取 jar 条目清单）。
- ❌ **未新增任何依赖**。
- ✅ **只新增本文件**：`docs/REUSE_SELECTION_UI_AND_UPGRADES.md`。

---

## 附：本文件用到的关键命令（可复现）

```powershell
# RS 2.0.9 类清单（按 upgrade 过滤）
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar='D:\gradle\caches\modules-2\files-2.1\com.refinedmods.refinedstorage\refinedstorage-neoforge\2.0.9\b7beb5d418dd7f850c767e7716f6bae8bd63da2f\refinedstorage-neoforge-2.0.9.jar'
$z=[System.IO.Compression.ZipFile]::OpenRead($jar)
$z.Entries | Where-Object { $_.FullName -match 'upgrade|Upgrade' } | Select-Object -ExpandProperty FullName | Sort-Object
$z.Dispose()

# 速度升级的周期公式
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer'
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer$ThrottledNetworkNodeTicker'

# 叠加升级 = ×64 的布尔量
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.exporter.ExporterTransferQuotaProvider'
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.importer.ImporterTransferQuotaProvider'

# 官方注册上限（speed=4 / stack=1）
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.AbstractModInitializer'
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.api.upgrade.UpgradeRegistry$DestinationBuilder'

# 自动合成仓的独立节拍
& javap -p -c -classpath $jar 'com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity'
```
