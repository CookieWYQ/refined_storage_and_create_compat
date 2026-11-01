# 第 98 轮：范围充电器「充电目标高亮按钮 + 充电过滤（黑/白名单）」契约

> 用户原话（语音转写，已按语义整理，未被曲解的部分保留原句）：
> 「我之前说的那一个高亮显示正在充电的对象的功能，你貌似没有做 —— 单独搞一个按钮出来，就是高亮正在充电的对象。
> 然后再加上一个按钮黑名单或白名单之类的。这一个就是配置：首先它会列出（对象），首先分成几类……根据这几类把他们所属的那些所有能充电的……可以阻止或单独允许这一些对象进行充电。
> 不需要添加什么高级版本，全在一个版本中完成就可以。」
>
> 用户后续澄清（本轮**唯一定案**）：
> - 过滤只有 **3 类**：**掉落物** / **玩家身上的物品** / **玩家**（**没有方块类**）。
> - 黑/白名单：**全局一个开关**（黑名单 = 名单里的不充；白名单 = 只充名单里的），三类共用。
> - 玩家名单：**本存档曾经进过服的所有玩家**（落盘，离线也在）+ **手动按名称/UUID 添加**。
> - 高亮：**每台充电器一个开关**（落盘到方块实体、默认开），主界面加一颗「高亮充电目标」按钮；原配置项
>   `rangeChargerHighlightTargets` 降级为「总闸」（总闸关 = 所有充电器都不高亮、一个包都不发）。
>
> **本轮只做这两件事**：①高亮按钮；②充电过滤配置。不新增方块/物品版本（用户明确「全在一个版本中完成」）。
> 不改充电数值、不改「显示范围」、不改红石模式、不改升级槽。

---

## 0. 现状（Lead 已核，2026-10-25）

- 高亮本身**已经有实现**：`RangeChargerBlockEntity#syncHighlightTargets`（每 5 tick 发
  `ChargerHighlightPacket`，只发充电器 64 格内的玩家）+ `client/ChargerTargetHighlight`
  （方块画青绿框、掉落物/玩家客户端发光）。但它**只能**由配置项
  `Config.rangeChargerHighlightTargets`（默认 true）控制，**界面上没有任何按钮**；
  用户没跑过 1.1.0 之后的新构建，所以在他眼里「没做」。本轮把它做成按钮。
- 充电对象只有 3 档（`doCharging`）：玩家身上物品（`chargePlayer`，含背包 41 格 + Curios）、
  掉落物（`scanItems` / `scanItemsInfinite`）、方块实体（`scanBlocks` / `scanBlocksInfinite`，本轮不过滤）。
- 菜单数据槽现为 12 个（0..11，见 `RangeChargerMenu`），按钮 id 0..6（6 = `BUTTON_SHOW_RANGE`）。
- 子界面基类：`client/screen/ChildConfigScreen`（默认原版九宫格面板，返回父界面）。
  可复用控件：`client/widget/McGui`、`SptScrollbarWidget`、`SptTabButton`、`RangeChargerScreen` 里的 `RepeatButton`。

---

## 1. 数据模型（server 权威，落盘到方块实体 NBT）

新增 `support/ChargingFilter.java`（**纯数据 + NBT + 判定**，不放界面）：

```java
public final class ChargingFilter {
    public static final int NBT_VERSION = 1;
    private boolean whitelist;                              // false = 黑名单（默认）
    private final Set<ResourceLocation> droppedItems;       // 掉落物：按物品类型
    private final Set<ResourceLocation> carriedItems;       // 玩家身上物品：按物品类型
    private final Set<String> playerNames;                  // 小写玩家名
    private final Set<UUID> playerIds;

    public ChargingFilter copy();
    public boolean whitelist();
    public void setWhitelist(boolean value);
    public Set<ResourceLocation> droppedItems();  // 只读视图
    public Set<ResourceLocation> carriedItems();
    public Set<String> playerNames();
    public Set<UUID> playerIds();

    /** 黑名单：命中 ⇒ false；白名单：不命中 ⇒ false。 */
    public boolean allowsDroppedItem(Item item);
    public boolean allowsCarriedItem(Item item);
    public boolean allowsPlayer(Player player);   // 名字（小写）或 UUID 任一命中即算命中

    public void save(CompoundTag tag);            // tag 形如 {v:1, whitelist:0/1, dropped:[...], carried:[...], names:[...], ids:[[i,i,i,i]...]}
    public static ChargingFilter load(CompoundTag tag);   // 缺字段 = 默认（黑名单 + 空集）
}
```

NBT 键名就是 `chargingFilter`（方块实体根 tag 下的一个 compound）。

**判定语义的唯一实现**：三个 `allows*` 之外任何地方都不得再写一遍「白名单/黑名单」判断
（自检会查：`RangeChargerBlockEntity` 里不得出现 `whitelist` 字面量）。

新增 `support/ChargerKnownPlayers.java`（存档级 `SavedData`，主世界 `getDataStorage()`，
名字 `rscc_charger_known_players`）：
- 存 `Map<UUID,String>`（最近一次见到的名字）；
- `@EventBusSubscriber(GameBus)` 监听 `PlayerEvent.PlayerLoggedInEvent` → 记录 UUID+名字并 `setDirty()`；
- 打开过滤界面时也 `record(player)`（防止专服刚启动、事件还没触发）；
- 对外：`List<Entry> snapshot(ServerLevel level)`，`Entry(String name, UUID id)`，按名字不区分大小写排序；
- **不得**在类里引用任何客户端类型（`network/**` 与 `support/**` 的 dist 规则）。

---

## 2. 网络包（3 个，全部 `network/` 包，dist-safe：不得 import 本模组 `client` 包）

1. `OpenChargerFilterPacket(int containerId)` — C2S。服务端：
   `player.containerMenu` 必须是 `RangeChargerMenu` 且 `containerId` 相同且 `stillValid(player)`
   → 取菜单里的充电器（**服务端菜单才有 BE**）→ `record` 玩家进已知名单 → 回 S2C 快照。
2. `SyncChargerFilterPacket(int containerId, boolean whitelist, List<ResourceLocation> droppedItems,
   List<ResourceLocation> carriedItems, List<String> playerNames, List<UUID> playerIds,
   List<KnownPlayer> knownPlayers)` — S2C；`KnownPlayer(String name, UUID id, boolean online)`。
   `handle` 里 `context.enqueueWork(() -> ClientPayloadHooks.get().openChargerFilter(packet))`。
3. `SetChargerFilterPacket(...)` — C2S，字段同 2 但无 knownPlayers/online；服务端校验同 1，
   然后整体覆盖 BE 的 `ChargingFilter` + `setChanged()`；**不**回包（客户端乐观更新，服务端权威）。
4. 不需要额外的 ack 包。

`ResourceLocation` 用 `ResourceLocation.STREAM_CODEC`；列表用
`StreamCodec.apply(ByteBufCodecs.list(...))`；`UUID` 用 `UUIDUtil.STREAM_CODEC`（与仓库现有包一致）。
四个包全部在 `RS_Create_Compat#registerPayloads` 注册（`playToServer` ×2、`playToClient` ×1，
快照包在 `Set` 之后不再回发）。

`client/ClientPayloadHooks`（接口）+ `client/ClientPayloadSink`（实现）新增
`void openChargerFilter(SyncChargerFilterPacket packet)` → 调用
`cretae.cookiewyq.rs_create_compat.client.screen.ChargerFilterScreen.openOrRefresh(packet)`
（**该静态方法由 UI 侧提供，签名固定**）。

---

## 3. 方块实体（`block/entity/RangeChargerBlockEntity`）

新增字段：
- `private final ChargingFilter chargingFilter = new ChargingFilter();`
- `private boolean highlightEnabled = true;`（默认开）

NBT（`saveAdditional` / `load`，与现有字段同一处）：`chargingFilter`（compound，见 §1）、
`highlightEnabled`（BOOLEAN）。

ContainerData：**12 → 13**，索引 **12** = `highlightEnabled ? 1 : 0`；`SimpleContainerData(13)`
（菜单侧同步改）。其它索引一律不动。

充电门控（**这是本轮最容易漏的地方，三处都必须有**）：
1. `chargePlayer`：先 `if (!chargingFilter.allowsPlayer(player)) { return currentTargets; }`
   （整名玩家跳过：连他的饰品槽也不扫）；每个 `ItemStack` 充电前
   `if (!chargingFilter.allowsCarriedItem(stack.getItem())) { continue; }`。
2. `scanItems` / `scanItemsInfinite`：对每个 `ItemEntity` 的
   `stack.getItem()` 判 `allowsDroppedItem`，不许充的 `continue`（**不得**计入 `targets`、
   **不得**登记高亮）。
3. 方块两档（`scanBlocks` / `scanBlocksInfinite`）：**不过滤**（用户定案只有 3 类）。
4. 高亮：`syncHighlightTargets` 开头
   `if (!Config.rangeChargerHighlightTargets || !highlightEnabled) { resetHighlightSyncMemory(); return; }`
   —— 总闸与单机开关都要能停包。

新增公开入口（供菜单调用）：
`ChargingFilter getChargingFilter()`、`void setChargingFilter(ChargingFilter filter)`（内部 copy + `setChanged()`）、
`boolean isHighlightEnabled()`、`void toggleHighlightEnabled()`（`setChanged()`）、
`void sendFilterSnapshot(ServerPlayer player)`（组包 + 发 S2C；`containerId` 从 `player.containerMenu` 取，
取不到就发 `-1`，客户端只用来校验刷新目标）。

---

## 4. 菜单（`menu/RangeChargerMenu`）

- 常量：`BUTTON_TOGGLE_HIGHLIGHT = 7`、`BUTTON_OPEN_FILTER = 8`（保持 6 不变）。
- `clickMenuButton`：7 → `charger.toggleHighlightEnabled()`；8 → `charger.sendFilterSnapshot(serverPlayer)`
  （**同样先 `stillValid`**）。
- `SimpleContainerData(13)`；新增 `public boolean isHighlightEnabled() { return data.get(12) == 1; }`。
- 不新增物品槽，`quickMoveStack` 不动。

---

## 5. 界面（UI 侧两件事）

### 5.1 主界面 `client/screen/RangeChargerScreen`（面板 210×166，已占用见 §0）

在「查看」按钮（`(150,20,36,14)`）**正下方**加两颗同宽按钮（都带 tooltip，文字无阴影）：
- `(150, 37, 36, 14)`「高亮」：文案随 `menu.isHighlightEnabled()` 变
  `gui.rs_create_compat.range_charger.highlight.on` =「高亮」/ `...off` =「高亮」+ 颜色或后缀
  ⇒ **实现口径**：按钮文字固定两字「高亮」，`isHighlightEnabled()` 为 false 时用灰色字
  （`0xFF777777`）绘制覆盖标记（例如在按钮内右下角画一个 3×3 红点，或直接让标题后加「·关」）。
  更简单且不越界的做法：`Button` 文案用 `on` =「高亮」/`off` =「高亮关」（3 字 = 27px ≤ 32px 可用宽，
  实测 `font.width` 决定，装不下时退化成 2 字 + tooltip 说明）。点击发
  `handleInventoryButtonClick(containerId, BUTTON_TOGGLE_HIGHLIGHT)`。
- `(150, 54, 36, 14)`「过滤」：点击 `PacketDistributor.sendToServer(new OpenChargerFilterPacket(menu.containerId))`。
- 两颗按钮的 tooltip 必须写清：总闸关时高亮不生效（`Config.rangeChargerHighlightTargets == false`
  时按钮 tooltip 追加一行「配置中已全局关闭」）。

### 5.2 新子界面 `client/screen/ChargerFilterScreen extends ChildConfigScreen`

面板 **252 × 262**，居中（`ChildConfigScreen` 已处理）。硬性几何（自检会钉）：

| 元素 | 坐标（面板内相对） |
|---|---|
| 标题「充电过滤」 | `(10, 8)` |
| 模式按钮（黑/白名单） | `(10, 26, 100, 16)`，文案 `黑名单` / `白名单`，点击全局切换 |
| 搜索框 | `(114, 26, 92, 16)`（玩家页隐藏） |
| 提示行（模式说明） | `(10, 46)`，宽度 ≤ 232，单行截断 |
| 页签 3 个 | `(10, 60, 74, 16)` 起，间隔 2：掉落物 / 玩家身上物品 / 玩家 |
| 列表 | `(10, 80, 232, 144)`，行高 18 ⇒ **8 行/页** |
| 滚动条 | `(10+232-10, 80, 10, 144)`（`SptScrollbarWidget`） |
| 玩家页「添加」输入框 | `(10, 228, 150, 16)`（仅玩家页显示） |
| 玩家页「添加」按钮 | `(164, 228, 42, 16)` |
| 底部「完成」 | `(10, 244, 108, 16)` 居中偏左；右侧「返回」`(134, 244, 108, 16)` |

- 列表行：`[✓ 复选框 9×9] [物品图标 16×16 或玩家头像] [名字（超宽截断）] [id 后缀（灰）]`；
  行内左键 = 勾选/取消（立即生效）；悬停显示 tooltip（完整 id / 玩家 UUID / 在线状态）。
- **列出什么**（这是用户「它会列出所有能充电的」的落点）：
  - 掉落物页 / 玩家身上物品页：列出**全部「能充电的物品类型」**（候选池，见 5.3），
    搜索框按名字 / 注册 id 过滤（不区分大小写；`@mod`、`#tag` 不必支持）；
    **两页各自独立**勾选集合（同一个候选池，两套选择）。
  - 玩家页：列出 `knownPlayers`（名字 + 在线标记）+ `playerNames` / `playerIds` 里
    「不在已知名单」的手动条目（名字条目显示 `?`，UUID 条目显示短 UUID），每行一个复选框；
    底部输入框接受「玩家名」或「UUID」（大小写不敏感；名字存小写）→「添加」；
    勾选/添加/删除都即时发 `SetChargerFilterPacket`。
- 每次改动立即发 `SetChargerFilterPacket(menu.containerId, …)`（**全量**快照，服务端整体覆盖）。
- 刷新：`public static void openOrRefresh(SyncChargerFilterPacket packet)`：若当前界面已是
  `ChargerFilterScreen` 且 containerId 相同 → 只刷数据；否则 `setScreen(new ChargerFilterScreen(parent, packet))`，
  `parent = Minecraft.getInstance().screen`。
- 关闭/完成 → 返回父界面（`ChildConfigScreen#returnToParent`）。
- 全部文字 `dropShadow=false`；tooltip 手动渲染（本工程硬规则）。

### 5.3 候选池 `client/screen/ChargerFilterCandidates`（新，client 包）

- 懒构建一次并缓存（静态）：遍历 `BuiltInRegistries.ITEM`，对每个 item：
  `ItemStack stack = new ItemStack(item); IEnergyStorage st = stack.getCapability(Capabilities.EnergyStorage.ITEM);`
  判 `st != null && st.canReceive() && st.getMaxEnergyStored() > 0` ⇒ 可充电候选；
- 按显示名排序；提供 `List<Item> all()` 与 `List<Item> search(String query)`（名字 / 注册 id 子串，
  不区分大小写，最多返回 512 条）；
- **构建只允许在客户端主线程、只构建一次**；若候选为空（极端情况）界面必须显示
  「没有找到可充电物品」而不是空白。

---

## 6. 自检（第 3 位 teammate，A/B 完成后做）

新增 `tools/selfcheck_round98_charger_filter.py`，**必须真的抓回归**（每条都配反例自证）：
1. `ChargingFilter` 三个 `allows*` 的黑/白名单语义（源码断言 + 纯 Python 行为模型：黑名单命中⇒拒绝、
   白名单未命中⇒拒绝、空名单黑名单⇒全放行、空名单白名单⇒全拒绝）；
2. BE NBT：`chargingFilter` + `highlightEnabled` 的 save 与 load 键名都对、load 缺失时回落默认；
3. 充电门控三处都在：`chargePlayer`（玩家 + 每个 stack）、`scanItems`、`scanItemsInfinite`；
   **反例**：删掉任一处门控 ⇒ 判负；
4. 高亮：`syncHighlightTargets` 同时受 `Config.rangeChargerHighlightTargets` 与 `highlightEnabled` 约束；
   data slot 12 存在且 `SimpleContainerData(13)`；菜单按钮 7/8 与两个 `clickMenuButton` 分支；
   `RangeChargerScreen` 里两颗按钮的 bounds 与点击动作；
5. 3 个包都在 `registerPayloads` 里注册（TYPE + STREAM_CODEC + handle 方向正确），
   且 `network/**` 不引用本模组 `client` 包（沿用现有 round47 判据，别重复实现，直接调用/复用）；
6. 界面几何：面板 252×262；3 个页签 + 列表 8 行 + 玩家页输入框 + 底部按钮**全部在面板内**；
   行内文字不越过滚动条左缘（不靠文案短来假设）；玩家页隐藏时搜索框不参与点击；
7. 语言键：新增键在 zh/en **成对**、非空、zh 非纯 ASCII、en 无中日韩；
8. 不越界：`RangeChargerBlockEntity` 里不得出现 `"whitelist"` 字面量（判定只许在 `ChargingFilter`）；
   `client/**` 不得出现在 `support/**` / `network/**` 的 import；`rangeChargerChargeBlocks` 等既有配置语义不变。

## 7. 验证与交付（Lead）

- Lead 串行：`tools/_lead_compile.ps1` → `tools/_run_all_selfchecks.py`（含新脚本）→
  `tools/verify_mixin_shadows.py`（两遍）→ `tools/audit_lang_keys.py` → 专用服务端真启动
  （`tools/verify_dedicated_server.ps1`，PID 锁）→ `gradlew build` 出 beta.5 jar。
- teammate **禁止**跑编译 / gradlew / 专用服务端探针（并发会踩：真实事故有编译撕裂、假 NO_TERMINAL、
  日志被删、ENOSPC）。只允许跑纯 Python 自检 + 读文件。
- 语言键一律由 UI 侧（B）改，core 侧（A）不得改 lang；docs/CHANGELOG 由 Lead 写。

## 8. 未验证项（必须如实写进回报）

- 无实机：按钮位置/子界面观感/搜索与滚动的实际手感、候选池扫描耗时（首次打开可能有一次性卡顿，
  A 侧要打印扫描条数，B 侧要在 tooltip/标题里体现「正在建立候选列表」以外的兜底）。
- 高亮颜色/发光在光影下是否可辨，仍未实机确认。
