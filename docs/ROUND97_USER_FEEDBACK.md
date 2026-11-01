# 第 97 轮用户反馈契约（唯一口径）

> 用户原话（三条，按严重度排序）：
> 1. **「你貌似把输入总线输出总线……所有这样子的总线，它的黑名单那些按钮什么全都删了，然后只剩下一个这一个红石开关了。我让你的是把我添加的那一些东西部分改一下，但你却他妈的把这都改。这是个非常严重的问题。」**
> 2. 「而且你这个物品栏有点矮，就是这个背包和物品栏有点矮，而且上面这么大空位，所以说你往上移一点。」
> 3. 「字太小了，而且（就算）修复的话，也不喜欢那行字了。」（指容器代理器界面里那条深红色说明行）

本轮**只修这三件事**。任何「顺手优化」都必须先回报、经 Lead 批准，否则视为越界（用户已明确反感上一轮的越界改动）。

---

## 0. 已经定位的根因（Lead 复核过证据，不再重复调查）

### 0.1 输入总线侧边按钮被删 —— 真 bug，1.0.0 起就存在

- `src/main/java/cretae/cookiewyq/rs_create_compat/mixin/client/AbstractBaseScreenMixin.java`
  的 `rscc$hideImporterFilterButtons` 在 `AbstractBaseScreen#addSideButton` 的 `HEAD` 处
  **无条件** `ci.cancel()`：只要界面是 `ImporterScreen`，且按钮是 `FilterModeSideButtonWidget`
  （白/黑名单，即用户说的「黑名单按钮」）或 `FuzzyModeSideButtonWidget`（模糊模式），就**不挂**。
- RS 2.0.9 源码（`refinedstorage-neoforge-2.0.9-sources.jar`，`ImporterScreen#init`）原本挂两个：
  `FilterModeSideButtonWidget` + `FuzzyModeSideButtonWidget`；红石模式按钮由基类 `AbstractBaseScreen`
  统一挂。两个被删 ⇒ 输入总线界面上**只剩一颗红石模式按钮**，与用户截图描述逐字一致。
- 该删除是**无条件**的：普通模式（非「延长型」）也删 —— 而普通模式本应与 RS 原版**逐像素一致**。
  这是本轮必须纠正的语义错误：**本模组不得删除任何 RS 原生控件**。
- 输出总线（`ExporterScreen`）侧：RS 原生挂 `FuzzyModeSideButtonWidget` + `SchedulingModeSideButtonWidget`
  （+ 基类红石模式）；**我们的代码里没有任何一处屏蔽它们**（全仓 `addSideButton` 只出现在该
  importer 分支与注释里）。→ 修复时必须**如实复核并回报**「输出总线三颗按钮应全部在」，不得为了
  迎合用户话术去编造一个不存在的删除，也不得顺手改输出总线。

### 0.2 容器代理器那条「字太小」的红字

- `InventoryProxyScreen#renderWarning` 把 `gui.rs_create_compat.inventory_proxy.warning`（中文字符串
  56 字）画成**单行**，宽度超出时整行等比缩放（`scale = available / width`）⇒ 实际缩放约 **0.4x**，
  所以在 2/3 倍 GUI 缩放下看起来是「一撮蚂蚁字」。
- 用户明确说**不喜欢这行字**（不是「再放大点」）。⇒ 结论：**删掉这行常驻文字**，
  信息改为**悬停六面按钮时的 tooltip**（不做常驻文本；不做长警告块）。

### 0.3 「上面这么大空位 + 物品栏有点矮」

当前几何（`InventoryProxyScreen` + `InventoryProxyMenu` + `inventory_proxy.png`）：

| 元素 | 现在 | 问题 |
|---|---|---|
| 面板 | 224 × **248** | 底部没有留白 |
| 六面按钮 | y=20 起，步长 18，最后一颗底边 y=126 | — |
| 红字说明 | y=130（缩放后） | 位于 130..172 的空带里 |
| 玩家背包 3 行 | y=**172 / 190 / 208**，x=26+18c | 与按钮之间有 46px 空带 |
| 快捷栏 | y=**230**，格子底边 248 | **紧贴面板最底边，看起来被切掉** |

即：面板上大半是空的、最下面却挤到边 —— 用户说的「上面大空位、物品栏矮」就是这个。

---

## 1. 验收标准 A：原生侧边按钮全部回来（最严重，优先）

1. **删除** `AbstractBaseScreenMixin#rscc$hideImporterFilterButtons` 整个注入方法及其专用 import
   （`FilterModeSideButtonWidget` / `FuzzyModeSideButtonWidget` / `ImporterScreen` /
   `AbstractSideButtonWidget`），保留该类另外两个注入（`mouseClicked` 抢点击、`renderTooltip` 抬 tooltip）。
2. `ImporterScreenMixin` 的 javadoc 里「本轮同时移除了两个语义不再适用的原生控件」那段必须改写成
   「原生侧边按钮一律保留（白/黑名单、模糊、红石模式），本模组只**追加**自绘类别条，**绝不删除** RS 原生控件」。
3. **全仓审计**并回报：`src/main/java` 里不得再有任何
   - 对 `addSideButton` / `addRenderableWidget` / `addWidget` 的 `ci.cancel()`；
   - 对 RS 原生控件的 `removeWidget` / `clearWidgets` 后不重建的做法（`TerminalModeTabOverlay`
     那种「先摘自己上一排、再挂自己这一排」不算，但要在回报里点名说明）；
   - 其它「把原生控件藏起来」的写法（`visible = false` 之类）。
   把审计结论逐条写进回报（文件 + 行 + 判定）。
4. 新增自检 `tools/selfcheck_round97_bus_side_buttons.py`（Python，纯源码/语言文件检查，不跑 gradle），至少断言：
   - `AbstractBaseScreenMixin.java` 里**没有** `addSideButton` 注入、**没有** `FilterModeSideButtonWidget` /
     `FuzzyModeSideButtonWidget` import、没有对这两个类名的引用；
   - 全 `src/main/java` 中 `"addSideButton"` 字面量出现次数为 **0**（注释里说明可以，但必须不出现该注入）；
   - `ImporterScreenMixin.java` 不引用上述两个按钮类；
   - RS 侧期望集合被记录在文件头注释里（importer = FilterMode + FuzzyMode + RedstoneMode；
     exporter = FuzzyMode + SchedulingMode + RedstoneMode），并**校验两条中文说明**存在：
     断言 `ImporterScreenMixin`/`ExporterScreenMixin` 未出现任何 `cancel` 型注入（只允许 `init`/`renderTooltip` TAIL/HEAD 的追加）。
   - 自检必须能**真的抓到回归**：脚本要按「行级正则 + 方法块扫描」实现，并在文件头注释里写明
     「把 `rscc$hideImporterFilterButtons` 还原回去，本脚本必须失败」，Lead 会实测这一点。
5. 不许动 `ExporterScreenMixin` 的既有行为（不删、不加按钮）；如复核发现输出总线确实少了按钮，
   **只回报、不改**，由 Lead 与用户确认。

## 2. 验收标准 B：容器代理器界面（红字删除 + 背包上移 + 贴图重制）

### B1 删掉常驻红字，改悬停 tooltip

- 删除 `renderWarning` 方法、`WARNING_X/WARNING_Y/COLOR_WARNING` 常量、`LANG + "warning"` 的绘制调用。
- `assets/.../lang/{zh_cn,en_us}.json` 里**删除** `gui.rs_create_compat.inventory_proxy.warning`
  （两边同时删，保持键集相等）。
- 新增悬停 tooltip（**只画在某颗六面按钮上时**，用 `guiGraphics.renderComponentTooltip(...)` 或
  `renderTooltip(font, List<Component>, ...)`，必须在 `super.render` 之后画，别被背景盖住）：
  - `gui.rs_create_compat.inventory_proxy.face_tip.disabled`：该面不接入任何库存
  - `gui.rs_create_compat.inventory_proxy.face_tip.proxy_object`：该面读写「代理对象」本身（袋子/磁盘），
    对外只暴露一份，避免被数两遍
  - `gui.rs_create_compat.inventory_proxy.face_tip.contents`：**主**内容物面，RS 会读它；
    说明「同一底层库存只应有一个主面，否则 RS 会把两份相加」
  - `gui.rs_create_compat.inventory_proxy.face_tip.contents_input`：**备·仅进**面，只接受输入
    （漏斗/管道/溜槽可推进来），对外读恒空 ⇒ 不参与 RS 求和
  - 每条 ≤ 60 个中文字符、en 对应翻译齐全、非空、zh 不得是纯 ASCII、en 不得含中日韩字符。
    
### B2 几何（这是本轮的**唯一**布局口径，两边代码必须逐字一致）

`imageWidth = 224` 保持不变，`imageHeight = 222`（原 248）。

| 元素 | 新坐标 |
|---|---|
| 标题 | `titleLabelY = 6`（不变） |
| 代理对象槽 | 菜单槽 `(10, 22)`（不变） |
| 「代理对象槽」标签 | 文本 `(32, 20)`（不变） |
| 状态文字 | 改为 `(10, 46)`，用 `font.split(component, 98)` 折行，最多 4 行、行高 9px；**任何一行都不得进入 x ≥ 108 的按钮列** |
| 六面按钮 | 起点 `(108, 20)`、宽 110、高 16、步长 18（不变，最后一颗底边 y=126） |
| 玩家背包 3 行 | 菜单槽 y = **136 / 154 / 172**（`26 + 18c`，行首 `136 + 18r`） |
| 快捷栏 | 菜单槽 y = **196**（`26 + 18c`） |
| 面板底边 | 222（快捷栏底 214 ⇒ 底部留白 8px） |

- `inventoryLabelY` 继续用一个超大值（不画原版「物品栏」标签，避免压住 10px 窄缝）。
- 不改槽位数量 / 不改 `PROXY_SLOT_INDEX` / 不改数据槽 / 不改任何服务端逻辑。

### B3 贴图重制 `assets/rs_create_compat/textures/gui/inventory_proxy.png`

- 新尺寸必须**等于** `imageWidth × imageHeight = 224 × 222`（`tools/audit_gui_textures.py` 的硬判据）。
- 用脚本生成（新增 `tools/gen_inventory_proxy_gui.py`，可复现；不要手工点点点）：
  - 调色板沿用原贴图：外框 `55`、内高光 `255`、面板 `198`、按钮区底板 `176`、槽位底 `139`；
  - 槽位**整格 18×18** 从旧贴图裁一块（例如 `(26,172)-(44,190)`）平铺到新坐标，
    保证倒角/高光与旧界面逐像素一致；
  - 顶部区域（标题 / 代理槽 / 按钮底板 `x104..219, y16..129`）从旧贴图整块搬过来；
  - 底部补一条外框（y=221 = `55`，y=220 = `255`，x 方向同顶部规则），
    让面板四边闭合、快捷栏下方有留白。
- 生成脚本要打印自检：尺寸、每格槽位的左上角坐标、边框闭合性（四边各 1px 外框 + 顶部/左侧 1px 高光）。
- `tools/audit_gui_textures.py` 若未收录本界面，照现有条目格式补一条 `(224, 222)`。

### B4 自检更新（**不许削弱**）

- `tools/selfcheck_round93_proxy_cache_area.py`：原来断言「warning 键存在 + 界面画 `renderWarning` +
  不折行」，改为断言「**warning 键在两边都已删除** + 四条 `face_tip.*` 键齐全且在中英都非空 +
  界面用 `renderComponentTooltip`/`renderTooltip` 画按钮提示 + 不存在 `renderWarning`」。
  原有「主/备两套视图都在」等结构断言**一条都不许删**。
- `tools/selfcheck_round96_proxy_dedup.py` 第 ⑥c 条同样改为「说明改由 face_tip 承载」。
- 新增断言（可放 round93 或新建 `tools/selfcheck_round97_proxy_layout.py`）：
  - `InventoryProxyMenu.java` 的槽位 y 常量必须逐字等于 `136` / `196`（扫描 `addSlot` 调用）；
  - `InventoryProxyScreen.java` 的 `imageHeight` 必须是 `222`、`imageWidth` 必须是 `224`；
  - 贴图 PNG 实际尺寸必须等于 224×222（用结构体读 IHDR，不依赖 PIL）；
  - 界面源码里**不得**再出现 `warning` 这个绘制键；
  - 状态文字必须走 `font.split`，且 x=10 起、可用宽度 98（防止又跑回按钮列下面）。

## 3. 交付与验证口径（Lead 统一跑，teammate 不跑构建）

- teammate **不要**运行 `tools/_lead_compile.ps1`、**不要**运行 `gradlew`、**不要**跑专用服务端探针
  （并发会互相踩：真实事故有「编译撕裂 / 假 NO_TERMINAL / 日志被删 / ENOSPC」）。
  只允许跑**纯 Python 自检脚本**（selfcheck / audit / verify_mixin_shadows 之外的轻量脚本）。
- Lead 收尾串行跑：编译 → 全量自检 → mixin 影子（CLIENT + DEDICATED_SERVER）→ 语言键审计 →
  专用服务端真启动（`verify_dedicated_server.ps1`，PID 锁）→ 构建 beta.5。
- 回报必须包含：改了哪些文件、每条验收标准对应证据（命令 + 输出摘要）、**未验证项**（诚实列出）。

## 4. 明确禁止

- 禁止顺手改输出总线界面、禁止再删任何 RS 原生控件、禁止改 `BusInterferenceOverlay` 的语义。
- 禁止为了让自检变绿而放宽断言；断言口径变化只能「因为实现口径变了」并且新断言仍能抓到旧 bug。
- 禁止把测试构建发到 GitHub。
