# 第 129 轮（beta.30）：降解室发电机接上 JEI —— 点箭头就能看「吃什么 / 发多少电 / 耗多久」

> 用户原话（本轮，2026-11-01 10:10）：**「将这个发电机完善一下……把他的这个发电添加 JEI 兼容。
> 那个熔炉中间那个箭头点了之后不是会跳转到 JEI 界面去查看吗，我们这个箭头也要这样子，
> 并且查看的（要能看出）用什么东西、可以发多少电、消耗多久。」**

## 0. 一轮话总结

| # | 结论 | 证据 |
|---|---|---|
| ① | 本工程此前 **4 个 `@JeiPlugin` 全是 ghost / transfer，没有注册过任何 JEI 配方分类** —— 用户要的「跳过去看」在 JEI 里根本没有落脚点，本轮必须新建分类 | `client/*JeiPlugin.java` 全量审计（§2） |
| ② | 新建「降解发电」分类页：输入槽 + 发电量 + 耗时 + 平均 FE/t + 副产物槽（概率在 tooltip） | `DecompositionGeneratorJeiCategory`（§3） |
| ③ | 机器界面那个**熔炉箭头**变成 JEI 可点区域：输入格有东西 → 直接定位到那一件的配方；空着 → 打开整个分类 | `DecompositionGeneratorJeiPlugin`（§4） |
| ④ | **数据必须下发**：降解表只在服务端加载，专用服务器上客户端那张表恒空（JEI 会静默变空页）。本轮加 S2C 整表快照（`OnDatapackSyncEvent` = 进服 + `/reload`） | `network/SyncDecompositionDisplayPacket`、`support/DecompositionDisplayCache`（§5） |
| ⑤ | 像素示意图当场抓到两处真实布局缺陷（标签压槽 / 一格里铺 4 个候选撞箭头），已改布局并写成**算术判据**进自检 | `build/round129_jei_mock.png`、`selfcheck_round129_decomp_jei.py` ②d-几何（§6） |

**本轮验收一句话**：`BUILD SUCCESSFUL sources=516`；全量自检 **191 个脚本 / 0 失败**；新增自检
**139 检查 / 22 反例 / ALL_PASS**；`rs_create_compat-1.2.0-beta.30.jar` 部署四处并回读一致
（build/libs / 本地客户端实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.29 就地 `.disabled`。

---

## 1. 用户到底要什么（拆成三条可验收的行为）

1. **像熔炉那样**：鼠标移到机器界面中间那个**熔炉箭头**上点一下 → JEI 打开，显示这一页配方。
2. **看得到「用什么东西」**：哪些物品能放进去（含 tag 条目 = 整组候选）。
3. **看得到「发多少电、消耗多久」**：单件发电量（FE）、耗时（刻 + 秒）、平均功率（FE/t），
   顺带把副产物（物品 / 数量 / 概率）也列出来 —— 数据包里本来就配了这些，玩家却没地方看。

## 2. 为什么不能只加一个 `addRecipeClickArea`

`addRecipeClickArea` 只是「点这里 → 打开某个配方类型」。**本工程此前根本没有这个配方类型**：
4 个 `@JeiPlugin` 分别只做「拖物品进槽（ghost）」与「Create 序列装配配方上的 +（transfer）」，
`registerCategories` / `registerRecipes` 一次都没实现过。所以本轮新增的是一整套「分类 + 配方数据 +
分类页 + 点击链路」，而不是给已有页面挂个按钮。

## 3. 分类页长什么样

```
+------------------------------------------------------------------+  WIDTH = 200
| [输入]  ->  发电：50 FE                                            |  y = 6
|         ->  耗时：20 刻（1.0 秒）                                  |  y = 17
|         ->  平均：2.5 FE/t                                         |  y = 28
| 副产物：                                                           |  y = 42
| [槽] [槽] [槽] [槽]                                                 |  y = 52
+------------------------------------------------------------------+  HEIGHT = 72
```

* **输入只有一格**：tag 条目（「任意树叶」）的整组候选由 JEI 在这一格里**轮播**。
  像素示意图第一版把 4 个候选铺成 4 格 —— 当场撞到箭头与文字列，这条已经写进自检的几何判据。
* **能量画成文字，不伪造物品**：JEI 没有「能量」这一种 ingredient（原版 / RS 都没注册过）。
  拿假物品当能量图标会让玩家以为能把它抠出来。副产物才是真输出槽。
* **概率在 tooltip**：JEI 19 的 ingredient 没有「chance」字段，副产物概率写在槽的 rich tooltip 上
  （100% 时说「必定掉落」，其余给百分比）。
* **箭头用 JEI 自己的 `addRecipeArrow()`**：不新增贴图，与 GUI 里那枚原版熔炉箭头同观感。
* **副产物标签独占一行**：中文「副产物：」≈36px、英文 `Byproducts:` ≈60px —— 与第一格槽同排时，
  英文必然压到槽上（像素示意图第二版抓到）。标签单独一行后，槽从 `BYPRODUCT_X` 起算，与语言无关。

像素示意图：`build/round129_jei_mock.png`（由 `tools/render_round129_jei_mock.py` 生成；
几何**从源码常量解析**、数值/文案取自数据包与 `zh_cn`、图标取自本机客户端 jar；不是游戏截图）。

## 4. 箭头 → JEI 的链路

| 步骤 | 落点 | 说明 |
|---|---|---|
| ① 声明可点区域 | `DecompositionGeneratorJeiPlugin#registerGuiHandlers` | `addGuiContainerHandler(DecompositionGeneratorScreen.class, ...)`；区域 = `DecompositionGeneratorSpec.ARROW_X/Y/WIDTH/HEIGHT`（**与界面画箭头同一份常量**，不写 86/45 这类裸数字） |
| ② 关掉 JEI 默认 tooltip | `IGuiClickableArea#isTooltipEnabled() == false` | 界面自己已经在箭头位置画「进度 + 点击提示」；不关就会出现两个 tooltip 框叠在同一个鼠标位置 |
| ③ 点击分支 | `openRecipes(...)` | 机器输入格里有**能查到配方**的东西 → `IRecipesGui#showRecipes(category, List.of(recipe), focus)` 直接定位；否则 `showTypes(List.of(RECIPE_TYPE))` 打开整个分类 |
| ④ 界面提示 | `DecompositionGeneratorScreen#drawArrowTooltip` | 空闲也显示提示（否则玩家不知道能点）；**只有装了 JEI 才显示**（没装时点了什么都不会发生，提示反而误导） |

> 「哪一格正在被降解」客户端并不知道（方块实体没把这个下标同步给界面）。因此点击时取
> **第一个能找到配方的输入格** —— 对玩家等价于「这台上现在放着的这东西」，比「随便拿第一格」稳：
> 输入格里混着不可降解物时也不会跳进空页面。

## 5. 数据链路：为什么必须下发（本轮最容易漏的坑）

```
服务端 datapack data/<ns>/decomposition/*.json
      │  DecompositionDataLoader（AddReloadListenerEvent，只有服务端跑）
      ▼
DecompositionTable.state  ──displayEntries()──▶ 展开 tag / 换注册 id / 带副产物
      │                                              │
      │  OnDatapackSyncEvent（进服 + /reload）        │
      ▼                                              ▼
SyncDecompositionDisplayPacket（S2C 整表快照）→ ClientPayloadHooks → ClientPayloadSink
      │
      ▼
DecompositionDisplayCache（客户端只读镜像）
      │
      ▼
DecompositionJeiRecipe.all() ──▶ JEI 分类页
```

* **为什么不能只读本机表**：`AddReloadListenerEvent` 只有服务端分发。专用服务器上客户端那张表
  **恒为空**，JEI 分类页会一条都没有、**且不报错**（最难查的静默失效）。
* **降级**：单人游戏 / 镜像还没到达时，`DecompositionDisplayCache.entries()` 直接退回
  `DecompositionTable.displayEntries()`（同 JVM，本机表就是权威）—— 单人整局都不会走到网络路径。
* **形状**：`key + items(注册 id 列表) + time + energy + results(item/count/chance)`；
  解码侧对条目数 / 物品数 / 副产物数有上限（坏包不至于撑爆内存），超出的**仍完整读出**（否则流错位）。
* **专服安全**：包在 `network/`、处理体只经 `ClientPayloadHooks` 空实现转发；`support/` 与
  `network/` 里 5 个新文件**一个客户端类型都没有**（`selfcheck_round47` + 本轮⑥组双保险）。

## 6. 数值口径：只有一份真相

* 分类页显示的是**数据包原值**：`energy`（单件完成时一次性写进缓存的 FE）、`time`（未装速度升级的 tick）。
* **不给「装了速度升级之后」的假想耗时**：升级换算在 `DecompositionGeneratorSpec#effectiveTime`，
  屏幕上再算一遍就变成第二天真相，数据包一改就对不上。升级的影响写在「悬停箭头」的 tooltip 里（纯定性）。
* `DecompositionJeiRecipe` 里**没有** `effectiveTime` / `RsccRateLimiter` 引用 —— 自检 ②f 盯这一条。

## 7. 改了哪些文件

**新增（8 个 Java）**

| 文件 | 作用 |
|---|---|
| `support/DecompositionDisplayEntry.java` | 展示条目（纯数据：key / items / time / energy / results） |
| `support/DecompositionDisplayCache.java` | 客户端只读镜像 + 单人降级 |
| `support/DecompositionTable#displayEntries()` | 把表展开成条目（tag 现查展开、注册 id、副产物） |
| `network/SyncDecompositionDisplayPacket.java` | S2C 整表快照（手写编解码 + 上限） |
| `network/DecompositionDisplaySync.java` | `OnDatapackSyncEvent` 订阅，进服 / `/reload` 推送 |
| `client/DecompositionGeneratorJeiPlugin.java` | `@JeiPlugin`：分类 / 配方 / 催化剂 / info 页 / 点击区域 |
| `client/DecompositionGeneratorJeiCategory.java` | 分类页（布局 / 文字 / 副产物 / tooltip） |
| `client/DecompositionJeiRecipe.java`、`client/RsccJeiText.java` | 配方 record、数字格式唯一落点 |

**改动**

| 文件 | 改了什么 |
|---|---|
| `RS_Create_Compat#registerPayloads` | 注册 S2C 包（+1 行块） |
| `network/ClientPayloadHooks` + `client/ClientPayloadSink` | 新增 default 空实现 + 客户端落点（写镜像） |
| `client/screen/DecompositionGeneratorScreen` | 箭头 tooltip 改成「进度 + 点击进 JEI」；新增 `jeiPresent()`（只问 ModList，不 import JEI） |
| `lang/zh_cn.json` / `lang/en_us.json` | 新增 15 个键（JEI 页 14 + 界面提示 1） |
| `gradle.properties` | `1.2.0-beta.29` → `1.2.0-beta.30` |
| `tools/audit_lang_keys.py` | `HEADS` 加 `jei`（JEI 分类键也是本模组语言键，否则被当孤儿键） |
| `tools/selfcheck_round123_decomp_redesign.py` | 一条反例的锚点从旧 javadoc 换成方法签名（判据没放宽） |

## 8. 验收

| 项 | 结果 |
|---|---|
| 独立编译 | `LEAD_COMPILE_OK sources=516`（javac 对 gradle 缓存，不信 gradlew 的结论） |
| Gradle 构建 | `BUILD SUCCESSFUL`，`sources=516`，`modVersion=1.2.0-beta.30` |
| 全量自检 | **191 个脚本 / 0 失败**（`tools/_run_all_selfchecks.py`） |
| 本轮新自检 | `tools/selfcheck_round129_decomp_jei.py`：**139 检查 / 0 失败 / 22 反例全过**（六组：数据通道 / 分类页 / 箭头点击 / 语言键 / 专服安全 / 反例） |
| 像素示意图 | `build/round129_jei_mock.png`（3 页样例：tag×2 + 单物品×1） |
| jar | `rs_create_compat-1.2.0-beta.30.jar`（**2,918,198 B**，sha256 `34a10d276ec052f623cd9aeccd7331572be4523f4b0cff8a405e7eac3f2fc237`）四处回读一致（build/libs / 本地客户端实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.29 就地 `.disabled` |
| 专服探针 | **PASS**（`Done (1.831s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK，探针目录被写入 = 预期） |

## 9. 已知限制（写清楚，不装作没有）

1. **没有配方转移（`+` 按钮）**：JEI 的 `+` 是把配方材料搬进容器槽，而发电机是「物品进、电出」，
   搬进去没有意义 —— 用户要的是「看」，不是「一键填」。要的话可以另做（把输入搬进 16 格里）。
2. **副产物概率只出现在 tooltip**（JEI 19 ingredient 没有 chance 字段）。
3. **没装 JEI 时箭头不可点**（JEI 不在场就没有可点区域），界面提示也随之隐藏 —— 有意的：不误导。
4. **`/reload` 后 JEI 页刷新依赖 JEI 自身重载**：本包与配方同一次数据同步发出（`OnDatapackSyncEvent`），
   正常会在同一次 JEI 重建里生效；极端时序下若页面仍旧，`F3+T` 或重开界面即可。
5. **未实机验证**：JEI 页实际观感（字体宽度、tag 轮播、tooltip 位置）只有像素示意图 + 编译 + 自检证据；
   需要用户进游戏按 `<JEI 配方键>` 或点箭头确认。
