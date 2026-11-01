# 第 120 轮 C-5：区域标记「客户端读标记不带注册表 ⇒ 下标错位 ⇒ REMOVE 删错一条」

> 类型：**代码修复 + 自检 + 文档**（没有进游戏、没有跑 gradle、没有 git 写操作、没有改存档格式 / 网络契约 / 语言文件）。
> 依据：`docs/ROUND119_AUDIT_AREA_MARKS.md` §C-5（另参考 §A-3 标记存在哪、§C-3 手持路径的静默失效风格）。
> 证据口径：所有结论给 `文件:行`；凡属**读代码推断**、没有实机证据的，另标 **〔推断〕**。

---

## 0. 一句话

服务端读区域标记带注册表、客户端以前**不带**；旧档里用 `Item`（整份 ItemStack）编码的标记在
「没有注册表」时会被当成「读不出来」丢掉 ⇒ 客户端列表比服务端**短 / 顺序不同**，
而「左键点第 N 格移除」发的是**下标** ⇒ 删掉的是服务端列表里同下标的**另一条**。
本轮把客户端读标记改成**同一份带注册表的解码口径**，让两边的列表同构、下标自然对齐。

---

## 1. 位置（改前 / 改后）

| 文件 | 改前 | 改后 |
|---|---|---|
| `client/screen/AreaMarkScreen.java` | `marks()` 走两参 `AreaMarks.get(stack, area)`（`:163-169`，`registries = null`） | 新增 `clientRegistries()`（`:167`）+ `clientMarks(stack, area)`（`:188`，三参带注册表）；`marks()`（`:204`）只转调它 |
| `support/AreaMarks.java` | 两参 `get` / `all` 只写「读」的语义，没有「客户端别用」的口径 | 两参 `get`（`:96`）与 `all` 的 javadoc 写明：`registries == null` 会丢旧格式条目 ⇒ 客户端界面必须带注册表；**签名 / 数据格式都没动** |
| `network/AreaMarkEditPacket.java` | REMOVE 按下标，但没有写「下标成立的前提」 | 类 javadoc 增补「REMOVE 的下标契约」与**放弃**「下标 + 条目指纹」加固的决策（`:55-66`）；解码口径 `player.registryAccess()` 不变（ADD `:120`、REMOVE `:155`） |
| 根因（**本轮不动**） | `CollectionCacheBlockEntity#decodeMarkerTag`：`registries == null` 时对 `Item` 条目返回 null（`:4141-4145`，第 113 轮 D9 的早退，防 `ItemStack.parseOptional(null, …)` NPE） | 保持不变 —— 它是**正确的防御**，错的是客户端的读法 |

客户端唯一的调用点（`AreaMarkScreen`）已改；另两处客户端 `AreaMarks` 读取见 §4.4。

---

## 2. 旧行为（完整链路，why it deletes the wrong entry）

1. 旧档（beta.13 之前）把每区域过滤存在**方块实体**上，逐条标记用 `Item` = 整份 `ItemStack` 序列化
   （`AreaMarks` 会把这些条目原样搬进物品，`AreaMarks.java` 类注释「编码」一节）；
2. `decodeMarkerTag` 遍历条目时：有 `Id` 键 → 直接解（**不需要**注册表）；只有 `Item` 键 →
   必须 `ItemStack.parseOptional(registries, …)`，而 `registries == null` 时**返回 null**，
   `readEntries` 于是**丢掉这一条**（`AreaMarks.java` 的 `readEntries`：`entry != null && !entry.isEmpty()`）；
3. 服务端（`AreaMarkEditPacket`）用 `player.registryAccess()` 读 ⇒ 旧格式条目**在**列表里；
   客户端（改前）用两参 `AreaMarks.get(stack, area)` ⇒ 同一批条目**不在**列表里；
4. 两边列表长度 / 顺序不同。界面画的是客户端那份（第 N 格 = 客户端列表第 N 条），
   发包发的也是这个 N（`ACTION_REMOVE + markerIndex`），服务端却按**自己的**列表 `next.remove(N)`；
5. 结果：玩家点「铁锭」，服务端删掉「金锭」。不丢区域、不崩、不报错 —— 属「删错一条」这类**静默数据事故**。

**触发条件（审计给的可达性）**：旧档里仍留着 `Item` 格式标记（是否真存在取决于用户档龄，见 §6），
且该条目不在客户端列表的尾部之外（等价于「它前面还有别的条目」或「列表长度不同」）。

**附加发现（本轮模型算出来的，审计没写）**：`MATCH_CAPACITY = 288` 的截断是「按**原始**条数迭代、
按**解出的**条数计满」，所以旧实现在 288 条上限处**可能连长度都一样**，但内容整体前移
（20 条旧格式占掉了迭代位却不显示 ⇒ 后面 20 条提前补位）⇒ 下标照样错。
见自检 `M-9`：长度相同、`remove_by_index(server, old_client, 0)` 删的不是界面上那一条。

---

## 3. 新行为

```
客户端 AreaMarkScreen#marks()  ──►  clientMarks(stack, area)
                                     ├─ registries = Minecraft.getInstance().level.registryAccess()
                                     ├─ registries == null  ⇒  return List.of()      // 不显示，不回退
                                     └─ 否则                ⇒  AreaMarks.get(stack, area, registries)
服务端 AreaMarkEditPacket#handle   ──►  AreaMarks.get(target, area, player.registryAccess())
```

* **两边同一套解码**：同一个 `AreaMarks` 子树 + 同一套注册表 ⇒ 列表长度 / 顺序 / 内容一致 ⇒
  「界面第 N 格」= 「服务端第 N 条」 ⇒ `REMOVE(N)` 删的就是玩家点的那一条；
* **渲染与动作同源**：`drawMarkGrid` / `drawTooltips` / `drawInfoAndNotice` / `addFromInventory` /
  `removeAt` / `clearAll` / `mouseClicked` 全部只经 `marks()`，不存在「画一份、删另一份」；
* **拿不到注册表时返回空表、不回退**：空表 ⇒ 界面不渲染任何标记格 ⇒ 玩家点不到任何一行 ⇒
  **不可能删错**。这是刻意的取舍：宁可这一帧看不见，也不显示一份**下标不可信**的列表；
* 数据格式、NBT 键、`MATCH_CAPACITY` 语义、界面布局、语言键、包线格式 **全部零改动**。

---

## 4. 取舍（决策都写清）

### 4.1 客户端辅助方法放 `AreaMarkScreen`，不放进 `support/AreaMarks`
工程约定（`support/JetpackHudState.java:8` 明写）：通用类的方法体里一出现 `net.minecraft.client.*`，
**专用服务端会在链接期**出问题。`AreaMarks` 是服务端也用的通用类 ⇒ 客户端注册表只能留在 client 包。
自检 `A-10` 锚住「`AreaMarks` 里没有 `net.minecraft.client` 引用」，`C-8` 反例自证该判据会判负。

### 4.2 拿不到注册表 ⇒ 空表（不是「退回旧读法」）
若回退两参重载，就**重新造出一份短列表** —— 那正是本 bug 的成因。空表是安全方向：
`removeAt` 的 `index >= current.size()` 守卫会拦住，不会发任何包（自检 `M-11`）。
代价：该窗口（断开 / 切维度瞬间，`level == null`）里概览行仍会显示「0 条 / 什么都不吸」，
这是**已知的短暂不一致**；因为本轮禁止改语言文件，没有新增「数据未就绪」文案。
注：正常游戏里客户端在世界中就一定有 `level`，这个窗口只在关界面那一两帧存在。

### 4.3 放弃了「REMOVE 传『下标 + 条目指纹』」这一半加固
那需要给 `AreaMarkEditPacket` **加字段** ⇒ 改网络线格式（`StreamCodec.composite` 5→6 段），
旧客户端连新服务端会整包错位；而根因（两边解码口径不同）已经被消掉，收益是「未知未知」级别的纵深防御。
⇒ **本轮不做**，写成后续建议（§7）。备选的零协议改法（把 `inventorySlot` 借用成指纹位）也不做：
字段名会与语义不符，属于给未来埋雷。

### 4.4 没有改的两处客户端读取（如实列出，不做也不假装做了）
| 位置 | 用途 | 为什么不修 | 影响 |
|---|---|---|---|
| `client/screen/AreaSelectorScreen.java:826` | 区域列表**行右侧的过滤图标**（`displayFilters` → `rowIconCount` / `drawRowFilters`） | ① 不在本轮写入范围（该文件很可能是本轮 C-1/C-2 修复的目标）；② 改它要同步 `selfcheck_round112` ⑦-7 的源码锚点 | 旧格式条目**少画一个图标**（显示型，不发送任何下标 ⇒ **不会删错**） |
| `item/AreaSelectorItem.java:59` | tooltip 的「N 个区域有标记」计数（走 `all(...)`，不是按下标的 `get`） | 不在本轮范围；修法 = 把 `all(stack)` 换成 `all(stack, context.level() == null ? null : context.level().registryAccess())`（`TooltipContext#level()` 有工程先例：`item/SequenceUnitPatternItem.java:55`） | 计数可能偏小（同上：纯显示） |

### 4.5 服务端那处 `null` 兜底没动
`AdvancedCollectionCacheBlockEntity#marksFor` 在 `level == null` 时有 `AreaMarks.get(stack, area, null)`
（`:303`）。那是**没有世界的方块实体**的兜底路径（不参与任何网络包 / 下标回写），保持原样。

---

## 5. 自检覆盖（`tools/selfcheck_round120_area_mark_registry.py`）

跑法：`python tools/selfcheck_round120_area_mark_registry.py`（退出码 0，最后一行 `ALL_PASS`）。
**42 条断言（其中反例 12 条）**，三组：

* **① 源码锚点（17 条，A-1…A-17）**：客户端读标记 = 三参带 `registries`；注册表来自
  `Minecraft.getInstance().level.registryAccess()`；`registries == null` ⇒ `List.of()` 且**没有**回退；
  `AreaMarkScreen` 不再出现两参 `AreaMarks.get(`；全文件只有 1 处 `AreaMarks.get(` 且 `marks()` 是唯一入口；
  6 个消费点都经 `marks()`；REMOVE 仍按下标 + 越界守卫；服务端 ADD / REMOVE 两处都带
  `player.registryAccess()`；`AreaMarks` 无 `net.minecraft.client`；两参重载保留；javadoc 口径存在；
  **两参调用点清单逐条归类**（client 只剩 §4.4 的允许项、非 client 包 0 处、允许项确实只写 `displayFilters`）。
* **② 行为模型（13 条，M-1…M-13）**：复刻「标记表解码 + 列表下标 + REMOVE」。
  旧档混排场景证明**旧实现点 gold 删 iron**；穷举 0/1/2/3 条 × 旧新混排 15 种组合，断言新实现
  「客户端列表 == 服务端列表」恒成立、旧实现存在「序列不同」与「点这删那」的组合；
  288 条上限的内容前移；无注册表时新实现**8 个格子点下去 0 发包 0 删除**；服务端越界丢包。
* **③ 反例自证（12 条，C-1…C-12）**：把坏写法拼回**源码副本**（两参读、null 分支回退、注册表固定 null、
  `marks()` 绕开、渲染私自读、删越界守卫、服务端退化成两参、给 `AreaMarks` 加 client 引用、删两参重载、
  删 javadoc 口径、tooltip 用 `get`）与**模型副本**（客户端换回无注册表口径），断言对应判据真的判负。

另外本轮回归跑了（均退出 0）：`selfcheck_round112_area_marks`(100/12)、
`selfcheck_round113_area_mark_e2e`(140/38)、`selfcheck_round114_marker_budget`(97/20)、
`selfcheck_round119_area_selector_sync`(49/21)、`selfcheck_round106_adv_cache_areas`(52)、
`selfcheck_round69_tooltip_stack`(0 问题)。
**对既有自检的唯一改动**：`selfcheck_round112` 的 ⑥-4 锚点，从 `AreaMarks.get(stack, area)`
改成 `AreaMarks.get(stack, area, registries)`（判据原意不变：界面只经 `AreaMarks` 读、不自己解析 NBT；
只是调用形态换成带注册表的三参）。这是「旧写法已删、锚点必须跟着搬」的合法更新，**不是**放宽断言。

---

## 6. 未验证清单（诚实标注）

1. **没有进游戏**：删错条目的场景**一次都没在实机复现**（本轮全部是源码阅读 + 模型推演）。
2. **旧格式 `Item` 标记在真实存档里是否还存在：未知**。若用户档龄很新（从没经过 beta.13 之前的迁移），
   C-5 在当前存档上就是**不可达**的；本轮修的是「可达即删错」这条路径。
3. **没有跑 gradle / 没有编译**（任务纪律）。改动的编译面靠人工核对：新增两个 `static` 方法、
   无新 import、`ClientLevel#registryAccess()` 是 `HolderLookup.Provider` 的子类型、
   三参 `AreaMarks.get` 签名未变；另做了去注释后的**括号 / 圆括号配平检查**（三个 java 文件均为 0 失衡）。
4. **「客户端与服务端注册表内容一致」是假设**（同版本、同模组集、同数据包）。若两端模组/数据包不同，
   旧格式条目仍可能只在一端解得出 —— 那是版本不匹配问题，不在本轮范围（§7 的指纹校验能兜住）。
5. `AreaMarks.get(stack, area, registries)` 在客户端读到的是**同步下来的物品**，
   与界面同一帧的服务端数据之间仍有正常的一帧延迟（原有性质，与本修复无关）。
6. §4.4 两处未修调用点的「仅显示型」结论是**读代码**得出的（`displayFilters` 只进
   `rowIconCount` / `drawRowFilters`；`AreaSelectorItem` 只进 tooltip 计数），未实机核对。
7. 本轮的 288 上限行为（M-9/M-10）是**模型**结论，未用真实 288 条 NBT 在游戏里验证。

---

## 7. 后续建议（按性价比排序）

1. **（小）统一 §4.4 的两处客户端读取**：把 `AreaSelectorScreen:826` 换成
   `AreaMarkScreen.clientMarks(selectionStack(), area)`、`AreaSelectorItem` 的 `all(stack)` 带上
   `context.level()?.registryAccess()`。两处都是显示型，但要**同一批**改掉
   `selfcheck_round112` ⑦-7 的锚点，适合单独一轮做。
2. **（中）REMOVE 的指纹校验**：若将来真的要为「两端注册表不一致」兜底，给 `AreaMarkEditPacket`
   加一个 `fingerprint`（entry 的 `id + fluid + amount + nbt` 哈希）字段，服务端校验后才删；
   这是**网络契约变更**，要按大版本节奏走（新旧客户端混连会整包错位）。
3. **（小）旧格式条目的「就地升级」**：读到 `Item` 条目时若注册表可用，可顺手重写成 `Id + Nbt` 新格式
   （一次性迁移，之后再也不依赖注册表）。本轮没做，避免在「解码」这条只读路径上引入写操作。
4. **（信息）把 C-5 的可达性做成诊断**：`AreaMarks` 可以加一个只读诊断方法，返回「这个选定器上还有几条
   `Item` 格式条目」，给 `/rscc diag` 一类命令用 —— 让「用户档里到底有没有旧格式」从猜测变成可查。
