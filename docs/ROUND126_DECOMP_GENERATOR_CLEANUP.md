# 第 126 轮：降解室发电机「删三行废话 + 槽位/背景 1px 偏差 + 长文本 tooltip」落地

> 本文记录三件事，全部来自用户本轮原话，逐条对应「改了什么 / 为什么这么改 / 以后怎么防回潮」。
> 前两件在同一张界面上，第三件是**规则级**的（从本轮起所有 GUI 都受它约束）。

## 0. 用户原话（拆成三条可验收项）

1. 「待机那一行、缓存那一行、插件提示那一行，这三行文本全部删除，因为这三行都是废话。」
2. 「包括当前这一个界面在内，存在一个问题：槽位和背景存在错误的偏差。你可以浏览一下我们以往的那些 GUI 界面，看看他们的偏差是正一还是负一，我忘了。确定好之后把它添加到自检脚本中，以后添加的所有 GUI 界面、带槽位的都必须自检一遍。」
3. 「GUI 界面中带长文本的话，如果不手动进行渲染，那么它的 tooltip 是没有的。这一个也添加到自检脚本之中，比如我们现在这个发电机界面里就是没有的。」

## 1. 删掉三行文本（DecompositionGeneratorScreen）

### 改了什么

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| `DecompositionGeneratorScreen#render` | `drawStatusTexts()` + `drawProgressReadout()` + `drawArrowTooltip()` | 只剩 `drawProgressReadout()` + `drawArrowTooltip()` |
| `DecompositionGeneratorScreen#drawStatusTexts` | 状态 / 缓存 / 升级 3 行（`RsccTextFit.drawFitLine`） | **整段删除**（方法名与方法体都不存在） |
| 配色常量 | `COLOR_TEXT` / `COLOR_GOOD` / `COLOR_WARN` / `COLOR_TEXT_DIM` / `COLOR_TITLE` | 只留 `COLOR_TEXT_DIM`（箭头进度数字）与 `COLOR_TITLE`（标题），其余**删除**（没有状态行就没有绿/橙） |
| `DecompositionGeneratorSpec` | `TEXT_X` / `TEXT_Y` / `TEXT_LINE_STEP` / `TEXT_MAX_WIDTH` / `TEXT_LINES` | **五个常量全部删除**；新增 `CONTENT_INSET = 8`（标题宽度 = 面板宽 − 2×本值） |
| 语言文件 | `idle` / `working` / `buffer_full` / `output_full` / `upgrades` 五键 | **zh_cn / en_us 同步删除**（三行文字的文案不再被任何代码引用） |

### 为什么连语言键一起删

工程有 `audit_lang_keys`（孤儿键 > 0 即判负）。三行文字没了，这五个键就是孤儿；留着只会让
「代码没引用但玩家翻译还在」的债越滚越大。仍被引用的两个键保留了：

- `energy`：能量条 `RsccEnergyBar` 的 **hover tooltip**（「缓存：x / y FE」）——用户说的是删界面上那行文字，
  不是删悬停提示；
- `progress_short`：箭头上方的进度数字 + 悬停箭头的完整句。

### 为什么「删文本」要配一条**判负式**断言

`tools/selfcheck_round123_decomp_redesign.py` 原先断言「文字固定 3 行（`TEXT_LINES == 3`）、
文字区不压玩家背包槽框」——方向反了。本轮把它改成判负式（比存在性更严）：

- `TEXT_X/TEXT_Y/TEXT_LINE_STEP/TEXT_MAX_WIDTH/TEXT_LINES` **不许在 Spec / Screen 中出现**；
- `drawStatusTexts` **不许出现**；
- Screen 里**不许**再引用 `idle` / `working` / `buffer_full` / `output_full` / `upgrades` 五个键；
- Screen 里**不许**再读 `menu.getSpeedCount()` / `menu.getLastGenerated()`（这两项只服务于那两行废文本）；
- 上述四个死键/常量各自配了反例自检（把东西塞回去，自检必须判负）。

本轮该脚本最终：**145 条断言 / 20 条反例自证 / 0 SKIP / 0 失败**。

## 2. 槽位 vs 背景的 1px 偏差（本轮最容易被忽略的那件）

### 先把「正一还是负一」钉死

用户问「以往界面是正一还是负一，我忘了」。答案在工程自己的工具里，**不是猜的**：

- `tools/make_gui_gui.py:21`：「『生成 Java 控件代码』输出槽位/按钮/文本对应的代码（**精灵坐标 +1 = Menu 坐标**）」；
- `tools/make_gui_gui.py:905`：「槽位（精灵坐标）x=%d y=%d，**Menu 坐标** x=%d y=%d」；
- `tools/make_gui_gui.py:1365`：「**精灵坐标 → Menu/控件坐标 = 精灵 + 1**；请在 init() 中 addRenderableWidget」；
- `tools/make_gui_gui.py:1495`：「坐标说明：**精灵坐标 +1 = 游戏中 Menu / 控件坐标**（leftPos/topPos 偏移前）」。

⇒ **PNG 里 17×17 槽框的左上角 = Menu 槽位坐标 − 1**。原因也简单：MC 的 `Slot.x/Slot.y` 指的是
**物品图标**左上角，18×18 槽框背景在 `(x−1, y−1)`；`slot.png` 贴的正是槽框。

### 抽样实证（历史界面全部符合 −1）

| 贴图 | PNG 槽框 | 对应 Menu 的 `addSlot` | 结论 |
| --- | --- | --- | --- |
| `range_charger.png` | (8,84) | `RangeChargerMenu`：`9 + col*18, 85 + row*18` | ✔ −1 |
| `quantity_keeper.png` | (8,84) | `QuantityKeeperMenu`：`9 + col*18, 85 + row*18` | ✔ −1 |
| `schematic_loader.png` | (8,18) | `SchematicLoaderMenu`：`9, 19` | ✔ −1 |
| `inventory_proxy.png` | (9,21) | `InventoryProxyMenu`：`10, 22` | ✔ −1 |

### 坏在哪

`textures/gui/decomposition_generator.png` 是第 123 轮用
`--slots "8,18,4,4" "118,37,2,2" "118,76,3,2" "8,128,9,3" "8,188,9,1"` 生成的——**把 Menu 坐标直接当精灵坐标贴**，于是 62 个槽框整体**右下偏 +1px**：
背景的槽框比物品图标偏了 1px，看起来就是「槽位没落进框里」。

### 修法（两步，缺一不可）

1. **工具层**：`tools/make_gui_bg.py` 新增 `--slots-menu X,Y,COLS,ROWS`，语义是 **Menu / Spec 坐标**，
   工具内部统一减 `SLOT_INSET = 1`；旧的 `--slots`（精灵坐标）保留但不推荐。
   文件头与 `SLOT_INSET` 常量处都写清了「精灵 = Menu − 1」及其权威出处。
2. **数据层**：用 `--slots-menu` 重新生成 `decomposition_generator.png`：

   ```bash
   python tools/make_gui_bg.py --bg pic/bg.png --slot pic/slot.png \
       --out src/main/resources/assets/rs_create_compat/textures/gui/decomposition_generator.png \
       --width 182 --height 212 \
       --slots-menu "8,18,4,4" "118,37,2,2" "118,76,3,2" "8,128,9,3" "8,188,9,1"
   ```

   参数与 `DecompositionGeneratorSpec` 里喂给 `addSlot` 的数字**逐字相同**（不许手算 −1）。

### 以后怎么防

新增自检 `tools/selfcheck_gui_slot_alignment.py`（全仓库扫描）：

- 用 `pic/slot.png` 的 17×17 模板在每张 GUI PNG 上做像素级匹配，列出所有槽框；
- 与对应 Menu 的槽位坐标比对，断言 **PNG 槽框集合 == {Menu 坐标 − 1}**；
- **有槽框但没登记对应 Menu ⇒ 直接 FAIL**（提示「新界面必须登记并自检」）——这样「以后新增的
  所有带槽位 GUI」自动纳入，不靠人记得；
- 偏差打印到像素级，例如 `期望 (7,17) 实得 (8,18)，偏 +1`。

## 3. 自绘长文本必须手动渲染 tooltip（规则级）

### 用户口径

「GUI 界面中带长文本的话，如果不手动进行渲染，那么它的 tooltip 是没有的。」

背景：第 104 轮起界面文字禁止省略号，放不下走 `RsccTextFit.drawFitLine` 缩字号（下限 0.6）。
缩字号解决了「不截断」，但**没有 tooltip 的话，玩家看不到被缩小的全文**——这就是用户指的缺口。

### 规则

每条自绘文本必须满足其一：

1. **有 tooltip 覆盖**：文本 hover 时 `renderTooltip` 给出完整内容（同 `Component` / 同 lang 键 / 同变量）；
2. **显式豁免**：调用行或上一行写 `// rscc:no-tooltip <理由>`，理由仅限三类——
   固定短标签（如「速度」「面朝」）、`renderLabels` 里的固定标题、纯装饰。

现有未覆盖点一次性冻结进 `tools/gui_text_tooltip_exemptions.json`（带理由 + 文件 + 行号指纹）；
**以后新增的未覆盖点不在冻结表里就 FAIL**。

### 本轮对发电机界面的处理

删掉三行后，界面上剩下的自绘文本只有两处，都符合规则第 1 类：

- **标题**（`renderLabels`，固定文案）：走规则第 2 类「固定标题」豁免；
- **箭头上方进度数字**：`drawArrowTooltip` 在悬停箭头时给出同一句的完整内容（`progress_short`）。

因此发电机界面**不存在「长文本没 tooltip」的漏洞**——这正是用户举的例子的正解：不是给废文本补 tooltip，
而是把废文本删掉，剩下的可缩放文本全部有 tooltip 或属于固定短文本。

## 4. 验收与部署

| 项 | 值 |
| --- | --- |
| 编译 | `LEAD_COMPILE_OK sources=508`（独立 javac 复算） |
| 自检 | 全量 `selfcheck_*.py` 全部通过（含本轮迁移的 round123：145 断言 / 20 反例） |
| 新增自检 | `tools/selfcheck_gui_slot_alignment.py`（槽位对齐）、`tools/selfcheck_round126_gui_text_tooltip.py`（长文本 tooltip） |
| 贴图 | `decomposition_generator.png` 182×212，槽框已按 Menu−1 重生成 |
| 版本 | 见 `gradle.properties` / `CHANGELOG.md`（第 126 轮 beta.27） |

## 5. 以后新增 GUI 的 4 步（写进流程，别再靠记忆）

1. `tools/make_gui_bg.py --slots-menu "<Spec 坐标>"` 生成 PNG（**永远传 Menu 坐标**）；
2. 在 `tools/selfcheck_gui_slot_alignment.py` 的登记表里登记该界面（不登记 = 自检 FAIL）；
3. 自绘文本：能 hover 就给 tooltip；固定短标签/标题才允许写 `// rscc:no-tooltip 理由`；
4. 跑 `python tools/_run_all_selfchecks.py`，全绿才算完成。
