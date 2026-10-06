# rs_create_compat 发布图标素材说明

本目录是模组「机械动力&精致存储：兼容与改善」的发布用图标素材。
所有图都由**仓库内已有贴图**拼贴合成，**没有联网下载任何图片，没有外绘图**。

---

## 1. 产出文件

| 文件 | 尺寸 | 模式 | 字节 | 用途 |
| --- | --- | --- | --- | --- |
| `icon_512.png` | 512×512 | RGBA | 6989 | Modrinth / CurseForge / mcmod 主图标 |
| `icon_256.png` | 256×256 | RGBA | 4579 | 常用尺寸 |
| `icon_128.png` | 128×128 | RGBA | 3437 | 常用尺寸 |
| `icon_64.png` | 64×64 | RGBA | 3289 | 列表页 / 小尺寸 |

辅助文件：

* `make_icons.py` —— 合成脚本（Pillow）。`python icon/make_icons.py` 一键重跑，输出可复现。
* `verify_icon.py` —— 自检脚本。`python icon/verify_icon.py`，退出码 0 = 全通过。
* `inventory_assets.py` —— 素材盘点脚本（列出仓库所有 png 的实测尺寸/包围盒/主色）。
* `preview_scales.png` —— 人眼检查用：左＝512 原图，右上＝128，右下＝**64 原生大小**。

> **5 张图是同一张像素画**。逻辑母版是 64×64，512/256/128 都是它的整数倍
> **NEAREST（最近邻）** 放大（8× / 4× / 2×），64 就是母版本身。所以既没有平滑，
> 也没有"大图和小图长得不一样"的问题。

---

## 2. 用了哪些素材

### 2.1 主体方块（画面中心，最重要）

全部取自本模组自己的方块贴图 `src/main/resources/assets/rs_create_compat/textures/block/`，
原图都是 **16×16 RGBA**：

| 素材 | 实测尺寸 | 用处 |
| --- | --- | --- |
| `sequence_execution_chamber_front_active.png` | 16×16 | 等轴测**左侧面**（带青色同心方口的"连接口"） |
| `sequence_execution_chamber_back_active.png` | 16×16 | 等轴测**右侧面**（带青色条的散热格栅） |
| `unit_pattern_manager_top_active.png` | 16×16 | 等轴测**顶面**（无彩色标记，最干净的顶面贴图） |

三张 16×16 分别被 `Image.NEAREST` 拉伸到侧面 16×22、顶面 32×18，再按等轴测
菱形/平行四边形逐像素铺贴（`make_icons.py` 的 `fill_rhombus` / `fill_quad`），
最后统一黑描边 + 分面提亮/压暗，拼成一枚立体方块。

> 选「序列执行仓」的原因：它是本模组最核心的新方块（Create 序列装配 ⟷ RS 自动合成的
> 接合点），青色同心方口在 64px 下仍是唯一清晰可辨的"小方块图案"。
> 顶面没用执行仓自己的贴图，是因为该方块**没有独立的 top 贴图**，
> 而 `sequence_assembly_executor_top_active.png` 在菱形铺贴后会露出 3 条紫色条纹，
> 视觉噪点偏大，故改用同工程的无标记顶面。

### 2.2 背景左半：机械动力 / 黄铜

来自 Create 自己的贴图 `local_src/create_src/assets/create/textures/block/`：

| 素材 | 实测尺寸 | 用处 |
| --- | --- | --- |
| `brass_casing.png` | 16×16 | **配色基准**（黄铜底/暗缝/中调），背景左上按同一配色手绘噪点机壳 |
| `brass_block.png` | 16×16 | 黄铜亮面 `#D7AA5E` |
| `brass_encased_cogwheel_side.png` | 16×16 | 黄铜高光 `#FFEB8C`（角铆钉） |
| `cogwheel.png` | 32×32 | **配色基准**（轮体 `#684E2E`、暗部、亮齿），齿轮按同配色手绘 |

左上角那枚齿轮是**按 `cogwheel.png` 的配色直接点像素画出来的**（半径 13，
8 齿，中心轴孔），不是缩放 Create 的 32×32 —— 缩放会破坏硬边像素风。

### 2.3 背景右半：精致存储 / 青色科技

来自 RS 参考贴图与本模组自己的贴图：

| 素材 | 实测尺寸 | 用处 |
| --- | --- | --- |
| `tmp_rs_ref2/block/side.png` | 16×16 | RS 自动合成舱机壳灰（`tmp_rs_ref3`/`rs_ref` 同源），背景右下暗色机壳基准 |
| `src/.../block/sequence_assembly_executor.png` | 16×16 | 青色饰条 `#3478A0`、橙色进料箭头 `#FFA800` |
| `src/.../block/sequence_execution_chamber_front_active.png` | 16×16 | 模组签名强调色 **`#02DDE6`**（青色） |
| `src/.../block/sequence_execution_chamber_front_inactive.png` | 16×16 | 强调色暗版 `#004D50` |
| `pic/rs_ref/cyan.png` | 16×16 | RS 线缆青色参考（仅作配色比对） |

右下角的青色电路走线（暗边 2px + 半亮 1px + 亮芯 1px）和"对角分界线"都是用
项目签名色 `#02DDE6` 手绘的硬边像素，不是贴图缩放。

### 2.4 仓库里其余可用素材（本次没用，但可作替代方案）

实测（Pillow 读 PNG 头 + 解码）盘点，仓库共 **172 个 png**：

* `pic/`（GUI 素材，30 张）：`bg.png` 256×256、`slot.png` 256×256、
  `slot_s.png` 18×18(P)、`widget/slot_frame.png` 80×80(P)、
  `widget/button.png` 200×20(L)、`widget/tab.png` 130×24(P) 等。
  → 都是 GUI 控件，做图标只能当边框/槽位的装饰，本次未用。
* `pic/rs_ref/`：`back.png` 16×16(RGB)、`side.png`/`top.png` 16×16、
  16 种颜色的线缆参考 `cyan.png`/`blue.png`/… 均 16×16。
* 本模组 `src/main/resources/assets/rs_create_compat/textures/`（117 张）：
  * `block/` 全部 **16×16**，每个机器都有 `_active` / `_inactive` 两套：
    序列执行仓 6 张（front/back/side × active/inactive）、序列装配执行仓 6 张、
    样板装载器 6 张、高级样板装载器 6 张、收集缓存 6 张、数量保持器 6 张、
    高级数量保持器 6 张、范围充能器 6 张、单位样板管理器 7 张、
    `sequence_pattern_terminal[_active|_inactive].png`、`separation_frame.png`、
    `camouflage_frame_side|top|bottom.png`。
  * `item/` 全部 **16×16**：`sequence_assembly_pattern.png`、`sequence_unit_pattern.png`、
    通用存储磁盘 1k…64m + creative 共 10 张、`advanced_remote_terminal*.png` 5 张。
  * `gui/`：`sequence_assembly_executor.png` 210×166、`sequence_pattern_terminal.png` 256×324、
    `collection_cache.png` 256×324、`advanced_schematic_loader.png` 210×326、
    `schematic_loader.png` 210×262、`chamber_units_summary.png` 244×250、
    `unit_pattern_manager.png` 256×256、`chamber_binding_config.png` 200×190 等。
* Create 原版贴图 `local_src/create_src/assets/create/textures/`：
  `brass_casing.png` 16×16、`brass_block.png` 16×16、`cogwheel.png` 32×32、
  `large_cogwheel.png` 32×32、`cogwheel_axis.png` 16×16、
  `brass_encased_cogwheel_side.png` 16×16、`andesite_casing.png` 16×16、
  `precision_mechanism.png` 16×16、`flywheel.png` 等（`local_src/external/Create/` 有一份镜像）。
* RS 参考贴图 `tmp_rs_ref/`、`tmp_rs_ref2/`、`tmp_rs_ref3/`：
  `block/side.png` 16×16、`block/machine_casing/side|end.png` 16×16、
  `block/controller/on|off.png`、`block/cable/*.png` 16 色、`block/disk/disk.png`、
  `item/pattern/*.png` 等。
* 其它：`assets/minecraft/textures/gui/container/generic_54.png` 256×256(P)、
  `local_src/rs_src/icon.png` 与 `build/rs_src_full/icon.png` 均 365×365（**RS 本体的 logo**，
  只作参考，未使用 —— 避免与 RS 官方视觉混淆）。

---

## 3. 设计说明（怎么想、为什么这样构图）

### 3.1 一眼看懂「机械动力 × 精致存储」

* **对角线二分**，从左下到右上一条**青色硬边分界线**：
  * 左上 = Create：黄铜噪点机壳 + 一枚铜色齿轮 → 机械/黄铜语汇。
  * 右下 = RS：暗色机壳 + 青色电路走线 → 青色科技语汇。
* 分界线用「暗 1px / 青 1px / 暗青 1px」三段，**硬边、无渐变**。
  斜线本身就在说"两边接起来"，比单纯左右对半更像"兼容/桥接"。
* 四角黄铜铆钉（`#FFEB8C` 高光 + `#3A2411` 暗座）把外框"钉住"，
  是机械动力的铆接语汇，同时让 RS 那半边也带一点黄铜，暗示两者结合。

### 3.2 主体用真实方块贴图，而不是抽象几何

* 中心是**序列执行仓**的等轴测立体方块（三面真实贴图）。
  左面是青色同心方口（"连接口"），右面是青色格栅（散热），顶面留净。
* 方块**刻意放大到约占画布宽 2/3**，并且**斜跨分界线**——左右两边都占，
  所以它既在 Create 侧也在 RS 侧，正好是"结合点"的隐喻。
* 方块外圈一定有 1px 纯黑描边，保证压在任何背景色上都抠得出来。

### 3.3 配色依据（全部是仓库里的真实像素值）

| 色值 | 来源 | 作用 |
| --- | --- | --- |
| `#02DDE6` | `sequence_execution_chamber_front_active.png` | **主强调色**，RS 侧电路 + 分界线 + 发光点缀（RS 主色 `#00FFFF` 系，同色相偏暖一点以贴合本模组） |
| `#0096A0` | 同上 SOFT 变体 | 电路半亮层 / 内框亮线 |
| `#004D50` | `sequence_execution_chamber_front_inactive.png` | 强调色暗版（灰色机壳上的"未通电"青） |
| `#3478A0` | `sequence_assembly_executor.png` | 电路暗青（执行仓/执行器共用饰条色） |
| `#FFA800` | `sequence_assembly_executor.png` | 唯一的暖色点缀（正面那 1px），呼应"进料箭头" |
| `#72472F` `#3A2411` `#5F3B28` | `brass_casing.png` | 黄铜底/暗缝/中调 |
| `#D7AA5E` | `brass_block.png` | 黄铜亮面 |
| `#FFEB8C` | `brass_encased_cogwheel_side.png` | 黄铜高光（铆钉、亮齿） |
| `#684E2E` `#8C683C` | `cogwheel.png` | 齿轮体/亮齿 |
| `#494949` `#858585` | 所有机器贴图共用的 RS 机壳中灰/亮边 | 方块主体 |
| `#16100F`→`#101010` | 机壳描边 | 外框、方块轮廓 |

**没有**使用平滑渐变、没有使用抗锯齿。

### 3.4 64px 可读性

* 母版就是 64×64，所以 64px 版本**不存在重采样损失**。
* 为了 64px 可读，做了这些取舍：
  * 主体放大到占宽 2/3（不再是 1/3），细节只保留「青色方口 + 格栅 + 立体感」。
  * 电路走线**只留 2 条**主干 + 1 条横线，其余留空；早期版本线路更密，缩到 64px 会糊。
  * **不放任何文字**（中文/英文都不放），改用"色块 + 图标"传达信息。
  * 曾试过底部加一条橙青斜纹色带（增加"双色结合"的辨识度），实测在 64px 下
    占比过大、抢主体，最终删掉。
* 实测：`preview_scales.png` 右下角是 64px 原生大小，能认出「灰色立体方块 + 青色方口
  + 左上黄铜齿轮 + 青色斜线」。`verify_icon.py` 第 4 节还把 512 用 LANCZOS 平滑降到
  64 与真实 64px 比对：4×4 网格平均色最差仅 **0.2/255**，最亮 4 格位置完全一致。

---

## 4. 重新生成 / 自检

```powershell
# 依赖：Pillow（本机已验证 12.3.0）
& "C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" icon\make_icons.py
& "C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" icon\verify_icon.py
```

调参入口都在 `make_icons.py` 顶部：色板常量、`TX/TY/HW/QV/DZ`（方块位置与大小）、
`draw_cog(...)`（齿轮位置半径齿数）、`trace(...)`（电路走线）。

---

## 5. 建议的替代方案

### 5.1 如果你要手绘（推荐，最终效果一定比拼贴好）

* **画布**：直接画 **64×64**，再整数倍 NEAREST 放大到 128/256/512。
  这样天然不会糊，比在 512 画布上画像素风要省事得多。
* **构图**：沿用本方案的对角线二分 + 中心立体方块，因为它已经验证过 64px 可读。
  手绘时可以把方块换成**序列装配样板（`item/sequence_assembly_pattern.png`）**
  ——它是 16×16 的物品贴图，画成"倾斜的样板 + 青色高光"会比方块更轻快。
* **取色**：直接把上面 §3.3 的表当色板用，或从
  `textures/block/sequence_execution_chamber_front_active.png` 用吸管取。
* **纪律**：只用 1px 硬边，不描 2px 以上的轮廓；不要模糊、不要外发光渐变、
  不要 1px 半透明羽化。任何"发光"都用 2~3 级实色代替（本方案就是这么做的）。
* **检查**：画完按 `verify_icon.py` 跑一遍；再自己缩到 64px 看一眼。

### 5.2 如果你要换成别的素材

* 想更强调「自动合成」：把主体换成 `block/sequence_pattern_terminal_active.png`
  （16×16，深色面板 + 青色），或 `block/sequence_assembly_executor.png`（带橙/青双箭头）。
* 想更强调「存储」：把主体换成 `item/universal_storage_disk_64k.png` 或
  `block/collection_cache_top_active.png`。
* 想更强调「机械动力」：背景那枚齿轮可以换成 `large_cogwheel.png` 的配色
  （`#684E2E` 系），或加一条 `create:block/brass_belt_casing.png` 的皮带纹。
* 想加字：**最多 1~2 个字母**（例如 `RS` 或 `C`），放在右下角、字号 ≥ 8px、
  用纯色 + 1px 黑描边。本方案选择完全不放字。

### 5.3 接入发布流程（本次**没有**改动）

`src/main/templates/META-INF/neoforge.mods.toml` 第 28 行的 `logoFile` 仍然是注释状态：

```toml
#logoFile="rs_create_compat.png" #optional
```

若要启用，需要把某张图标复制到 `src/main/resources/` 根目录（例如
`src/main/resources/rs_create_compat.png`，建议用 **256×256**，Minecraft 内部会缩放到
16×16 显示），再取消该行注释。**这属于改动已有文件，本次任务约束不允许，故未执行。**

---

## 6. 本次的局限

* **必须知道的一件事**：仓库 `.gitignore` 第 57 行是 GitHub 模板自带的 `Icon` 规则，
  在 Windows/macOS 上大小写不敏感，会把**新建的 `icon/` 目录整个吞掉**（`git status`
  里看不到）。因此本次在 `.gitignore` **末尾追加了一条 `!icon/` 反忽略**。
  这是本次唯一改动的已有文件（1 行有效规则 + 4 行注释），没有动任何 Java / gradle / md。
* 只能拼贴 + 手绘像素：**没有实机预览**，也**没有在 Modrinth/CurseForge 真实页面上
  看过深浅两种背景下的观感**（已通过"全不透明圆角方块"降低风险）。
* 输出是**不透明**的（alpha 全 255），圆角处填深色描边 `#101010`。
  如果你更喜欢透明圆角，可在 `make_icons.py` 里把"圆角填深色"那一段删掉，
  然后相应放宽 `verify_icon.py` 第 3 节的四角断言。
* 64px 下齿轮和方块会略微"顶到一起"，这是为了保主体占比做的妥协；
  手绘时可以各自缩小 1~2px 留出呼吸位。
