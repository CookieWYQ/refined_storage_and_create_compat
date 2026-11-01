# 第 128 轮（beta.29）：把「还没工具化」的 6 个界面背景改用 `make_gui_bg` 重绘

> 用户原话（本轮，2026-11-01 08:04）：**「还记得我之前那个工具吧？然后有很多界面其实是没有用
> 工具化的，你去找一下哪些（界面）没有，然后重新画一下。这一轮就只画就可以了，当然必要的改一下
> slot 之类的东西也可以。」**

## 0. 一轮话总结

| # | 结论 | 证据 |
|---|---|---|
| ① | 用户说的「那个工具」= `tools/make_gui_bg.py`（`pic/bg.png` 九宫格 + `pic/slot.png` 烘焙槽框） | 上一轮（第 123 轮）用户原话「我绝对是放了一个工具在你这个目录下面，专门用来拼装生成这种图片背景」；当时已确认就是 `make_gui_bg.py` |
| ② | 全仓库 18 个容器界面逐张审计：**12 张已经是工具产物**（其中 3 张工具底 + 额外装饰），**6 个界面没工具化** | §2 审计表（按像素判定，不靠印象） |
| ③ | 6 个界面全部重绘成本轮工具背景，**只动画面 / 不改交互**；槽位坐标一个没变（尺寸、Menu 坐标原样） | §3；自检 15 张 PNG 全对位 |
| ④ | 新增生成脚本 + 6 张 PNG；登记表 / 自检同步；`135` 条断言 / 7 反例全过 | §4 |
| ⑤ | 构建 / 全量自检 / 专服探针 / 四处部署全部通过 | §5 |

**本轮验收一句话**：`BUILD SUCCESSFUL sources=508`；全量自检 **190 个脚本 / 0 失败**；
`rs_create_compat-1.2.0-beta.29.jar`（**2,891,838 B**，sha256
`dbdc981a6d5bacf03981880c5e86e911373fee1ef225909e795a7d125c1578b9`）四处回读一致
（build/libs / 本地客户端实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.28 就地 `.disabled`。

---

## 1. 「工具化」到底指什么（口径钉死）

用户说的「工具」= 仓库里这个生成器，不是「AI 手绘图」：

```bash
python tools/make_gui_bg.py --bg pic/bg.png --slot pic/slot.png \
    --out src/main/resources/assets/rs_create_compat/textures/gui/xxx.png \
    --width W --height H --slots-menu "x,y,cols,rows" ...
```

它做两件事，一件不多：

1. 用 `pic/bg.png` **9-slice** 拼出 MC 原版容器底（4px 容器边框 + 中间平铺底色）；
2. 按 `pic/slot.png` 的 **17×17** 槽框，在 `slot 左上角 = Menu 坐标 − 1`（`SLOT_INSET = 1`）的位置烘焙槽框。

因此「工具化」= **背景是一张由它生成、槽框已烘焙、坐标与 Menu 同源（−1）的 PNG**，
`renderBg` 里一次 `blit` 画完（不许 `fill` 现画面板、不许逐槽 `slotFrame` 现画槽框）。

---

## 2. 审计：18 个容器界面逐张判定

判定方法（零猜测）：`tools/_audit_toolify2.py`（一次性审计，跑完已删）用
`make_gui_bg.draw_container` + 登记表里的 Menu 分组**重建**每张 PNG，再**逐像素比较**：

| 判定 | 含义 |
|---|---|
| `TOOL` | 与工具重建**逐像素完全相同**（0 px 差） |
| `近工具` | 工具底 + 额外烘焙内容（箭头 / 行列高亮 / 圆角面板），差异 1.6–2.3% |
| `非工具` | 手绘 / AI 绘制 / 原版贴图 / 代码自绘，差异 7–71% |

| # | 界面 | PNG | 尺寸 | 审计结论 | 本轮 |
|---|---|---|---|---|---|
| 1 | 定量保持器 | `quantity_keeper.png` | 210×166 | **TOOL（0 px）** | 不动 |
| 2 | 范围充电器 | `range_charger.png` | 210×166 | **TOOL（0 px）** | 不动 |
| 3 | 降解室发电机 | `decomposition_generator.png` | 182×212 | **TOOL（0 px）** | 不动 |
| 4 | 蓝图加农炮装填器 | `schematic_loader.png` | 210×262 | 近工具（1.94%：额外烘焙内容） | 不动 |
| 5 | 高级装填器 | `advanced_schematic_loader.png` | 210×326 | 近工具（2.32%） | 不动 |
| 6 | 高级定量保持器 | `advanced_quantity_keeper.png` | 210×210 | 非工具（7.06%，按此前 GUI 文档交付） | **不动**（见 §6 待拍板） |
| 7 | 归流缓存仓 | `collection_cache.png` | 256×324 | 非工具（25.46%，按 V4 文档交付） | **不动**（同上） |
| 8 | 容器代理器 | `inventory_proxy.png` | 224×222 | 非工具（32.75%，`gen_inventory_proxy_gui.py` 版） | **不动**（同上） |
| 9 | 序列装配样板终端 | `sequence_pattern_terminal.png` | 256×324 | 非工具（47.31%，按 V2 文档交付） | **不动**（同上） |
| 10 | 单元样板管理器 | `unit_pattern_manager.png` | 256×256 | 非工具（71.22%，RS 拉伸界面自带底图） | **不动**（同上） |
| 11 | **中间产物缓存仓** | 无 PNG（代码现画） | 176×198 | 代码自绘 | **✅ 本轮工具化** |
| 12 | **网络资源接收器** | 无 PNG（代码现画） | 236×252 | 代码自绘 | **✅ 本轮工具化** |
| 13 | **网络资源请求器** | 无 PNG（代码现画） | 210×238 | 代码自绘 | **✅ 本轮工具化** |
| 14 | **序列装配样板库** | 无 PNG（原版 `generic_54`） | 176×222 | 原版贴图 | **✅ 本轮工具化** |
| 15 | **序列执行仓** | 无 PNG（原版 `generic_54`） | 176×222 | 原版贴图 | **✅ 本轮工具化** |
| 16 | **F1 能量立方** | `fe_energy_cube.png` | 176×166 | 非工具（43.31%，手绘浅灰面板） | **✅ 本轮工具化** |
| 17 | 执行舱汇总 | `chamber_units_summary.png` | 244×250 | 近工具（1.60%，槽框代码自绘） | 不动（已是工具底） |
| 18 | 资源选择器 | 无 PNG | — | RS 框架自画（`AbstractGridContainerMenu`） | 不动（RS 的界面，不归我们画） |

> 另外 2 张「工具生成」的边角贴图：`advanced_remote_terminal.png`（176×120，零引用）、
> `collection_cache/marker_config.png`（1.67%，工具底 + 圆角面板）。

---

## 3. 本轮改的 6 个界面（只画，坐标一个没动）

生成脚本 = **`tools/gen_round128_gui_bg.py`**（内部直接调 `make_gui_bg.draw_container`，
与 `--slots-menu` 逐字同一口径；生成后自校验「与工具重建逐像素一致 + 每格槽心色 (139,139,139)」）。
6 张全部是 **Menu 坐标原样** ⇒ 玩家背包 / 快捷栏 / 机器槽位坐标一处都没改。

| 界面 | 新 PNG | 尺寸 | `--slots-menu`（= Menu 坐标，逐字） |
|---|---|---|---|
| 中间产物缓存仓 | `intermediate_cache.png` | 176×198 | `8,36,9,3` `8,116,9,3` `8,174,9,1` |
| 网络资源接收器 | `network_resource_receiver.png` | 236×252 | `214,7,1,6` `9,176,9,3` `9,230,9,1` |
| 网络资源请求器 | `network_resource_requester.png` | 210×238 | `188,16,1,6` `9,164,9,3` `9,218,9,1` |
| 序列装配样板库 | `sequence_assembly_executor.png`（旧孤儿图**转正**，尺寸改为 176×222） | 176×222 | `8,18,9,6` `8,140,9,3` `8,198,9,1` |
| 序列执行仓 | `sequence_execution_chamber.png` | 176×222 | `8,18,9,6` `8,140,9,3` `8,198,9,1` |
| F1 能量立方 | `fe_energy_cube.png`（覆盖旧手绘图） | 176×166 | （无槽位：Menu 故意 0 个 `addSlot`） |

### 3.1 Java 侧改动（每个界面 1 个 `renderBg` + 1 个常量）

```java
// 三个「代码自绘」界面（IntermediateCache / NetworkResourceReceiver / NetworkResourceRequester）
// 之前：
McGui.panel(guiGraphics, leftPos, topPos, imageWidth, imageHeight);
for (final Slot slot : menu.slots) {
    McGui.slotFrame(guiGraphics, leftPos + slot.x - 1, topPos + slot.y - 1);
}
// 现在：
guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
```

* 三个界面删除 `McGui` / `Slot` import 与逐槽循环（槽框已烘焙），新增 `TEXTURE` 常量
  （`ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/xxx.png")`）；
* 两个序列界面：`TEXTURE` 从 `minecraft:textures/gui/container/generic_54.png` 换成模组贴图；
  **blit 从 7 参改 9 参**（176×222 不是 256×256，7 参会按 256 取样画错 —— 与 `FEEnergyCubeScreen`
  里那条「必须 9 参」的硬约定同源）；
* F1 能量立方：不用改代码（原本就是 9 参 `blit`），只换 PNG；电量条轨道仍由
  `RsccEnergyBar.drawTrack` 现画（旧图里的 14×60 凹槽没了，注释已同步）。

> 说明：**没有改任何 Menu / 方块实体 / 交互逻辑** —— 这一轮只换画面，符合用户「就只画」的口径。

---

## 4. 登记表 / 自检同步（判据只增不减）

`tools/gui_slot_registry.py`：

| 表 | 改动 |
|---|---|
| `PAINTED` | +5 条（5 张新烘焙背景，含 `menu_slots` / `groups` / 源码行号 / 锚点）；原 `sequence_assembly_executor.png` 的 `orphan` 条目**删除**（已转正为 plain） |
| `MENUS` | 5 支 Menu 的 `bg` 从 `code` / `vanilla` 改成 `png`（IntermediateCache / NetworkResourceReceiver / NetworkResourceRequester / SequenceAssemblyExecutor / SequenceExecutionChamber） |
| `CODE_FRAMES` | **−3 条**（上面三个界面不再有 `McGui.slotFrame` 调用点） |
| `ORPHAN` | 清空（仓库当前无孤儿贴图） |
| `DECORATIVE` | `fe_energy_cube.png` 的 `why` 更新（工具生成底、无槽框） |

`tools/selfcheck_gui_slot_alignment.py`：

* **C-6 反例换靶子**：原来「把 `IntermediateCacheScreen` 的 `slot.x - 1` 去掉」——该界面已不再自绘，
  换成仍自绘的 `ChamberUnitsSummaryScreen`（**判据原意没放宽**：仍是「与 Menu 槽位同源的必须 −1」）。
* 结果：**135 条断言（128 正判 + 7 反例）/ 0 失败 / `ALL_PASS`**；
  覆盖 **15 张烘焙槽框背景 / 19 支 Menu / 9 个代码自绘点（13 处 slotFrame + 1 处 slotSprite）/ 85 条锚点**。
* 15 张 PNG **逐格全对**（新 5 张各 63 / 42 / 42 / 90 / 90 格；每格槽框左上角 = Menu 坐标 − 1）。

---

## 5. 本轮验收

| 项 | 结果 |
|---|---|
| 编译 | `BUILD SUCCESSFUL`，`sources=508`，`modVersion=1.2.0-beta.29` |
| 全量自检 | **190 个脚本 / 0 失败**（`tools/_run_all_selfchecks.py`） |
| GUI 对位自检 | 135 断言 / 7 反例 / `ALL_PASS`（新 5 张 PNG 全部逐格对位） |
| 专服探针 | **PASS**：`Done (2.074s)! For help, type "help"`，无 Mixin / dist 致命标记，`run/` 前后清单一致（只读自证 OK） |
| jar | `rs_create_compat-1.2.0-beta.29.jar` **2,891,838 B**，sha256 `dbdc981a6d5bacf03981880c5e86e911373fee1ef225909e795a7d125c1578b9` |
| 部署 | 四处 sha256 全等 → `DEPLOY_VERIFY_OK`（`tools/_verify_deploy_round128.py`）；beta.28 就地 `.disabled` |

---

## 6. 未实机验证 + 待你拍板

**未实机验证**：6 张新背景在游戏里的实际观感（尤其 176×222 的两张序列界面：玩家背包 y=140 /
快捷栏 y=198 与烘焙槽框是否严丝合缝、标题 / 配置按钮是否还压在边框上）。本轮的证据是
**PNG 像素对位 + 编译 + 专服探针**，不是截图。

**待拍板（本轮刻意没动）**：另外 4 个「非工具」界面 —— 高级定量保持器、归流缓存仓、容器代理器、
序列装配样板终端 —— 它们的背景是**之前按 GUI 文档交付的手绘/AI 图**（不是这次的工具生成），
其中归流缓存仓 / 样板终端的背景里烘焙了分区面板、滚动条轨道等结构元素。要不要**也统一换成
纯工具底图**（会更「MC 原版」，但那些分区/轨道要么消失、要么得在 Java 里补画）？
另外 15 个子配置界面（`ChildConfigScreen`）用的是**原版九宫格精灵**面板（不是 PNG），要不要也
生成 PNG？这两件事等你一句话，下一轮再做。

---

## 7. 以后新增界面怎么工具化（3 步）

1. 先定 Menu 坐标（`addSlot` 的数字），**原样**传给生成器：

   ```bash
   python tools/make_gui_bg.py --bg pic/bg.png --slot pic/slot.png \
       --out src/main/resources/assets/rs_create_compat/textures/gui/xxx.png \
       --width 176 --height 198 --slots-menu "8,36,9,3" "8,116,9,3" "8,174,9,1"
   ```

   **永远传 Menu 坐标**（工具自动 −1），不要手算精灵坐标。

2. `renderBg` 里一次 9 参 `blit`（贴图尺寸 ≠ 256×256 时**必须** 9 参），并删掉任何
   `fill` / `McGui.panel` / 逐槽 `slotFrame` 的自绘面板。

3. 在 `tools/gui_slot_registry.py` 登记 `PAINTED` + 把该 Menu 的 `bg` 改成 `png`，
   跑 `python tools/selfcheck_gui_slot_alignment.py`（期望 `ALL_PASS`）。
