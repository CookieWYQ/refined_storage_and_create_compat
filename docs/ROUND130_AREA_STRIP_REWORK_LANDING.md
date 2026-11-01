# 第 130 轮 · 落地说明：区域条去标记网格 / 匹配区成唯一编辑入口 / 搜索 / 删除确认 / 看清边框

> 规格见 `docs/ROUND130_AREA_STRIP_REWORK.md`（契约）。本文是**实现与验收记录**：改了什么、
> 为什么这么改、哪些判据钉住了它、还剩什么需要实机确认。

## 0. 用户原话 → 本轮动作

| 原话（第 130 轮验收反馈） | 动作 |
| --- | --- |
| 「保留当前这一个选择列表」 | `AreaStripPanel` 的行列表骨架、点行选中、就地改名 / 改坐标全部保留 |
| 「增加一个搜索功能，可以搜索坐标，也可以搜索名字之类的」 | 顶部**常驻**搜索框（`searchBox`）：空格分 token、全 token 命中；名字 `contains` 或坐标串 `contains`；标题显示「· 命中 N」 |
| 「改名字按钮和这个删除按钮保留，同时确保删除按钮有删除前确认」 | 行内 3 个动作图标改为 **看（◎）/ 改名（✎）/ 删除（✕）**；✕ 进入 3 秒 armed 态，行变红字「确认删除 #N？」+ 出现 ✓ / ✗ 两颗确认按钮，**未 armed 不可能发 `ACTION_DELETE`** |
| 「让这一个编辑的时候在**匹配区**中编辑」 | 侧栏那一整页「标记网格」（3×4 / 清空 / 返回 / JEI 拖入接缝）**整页删除**；点行 = `SetAreaFilterContextPacket`，匹配区 48 格就是该区域的过滤器 |
| 「我在匹配器里匹配就应该是可以**直接拖进去**」 | 修了一个**真 bug**：`SetCollectionMarkerConfigPacket` 在高级版只走 `setAreaMarkerConfig`（只 update），空格子上拖入 ⇒ `return false` ⇒ **静默丢包**。新增 `AdvancedCollectionCacheBlockEntity#addAreaMarkerEntry(...)`，空格子走「去重 / 容量 / 体积预算 + 追加」，失败有 actionbar 文案 |
| 「显示边框……还是不行，还是没用」 | 第 125 轮契约要求「关界面就清框」⇒ 玩家「点行 → 关界面 → 抬头看」永远看不到框。改成**关界面按 5 秒重新武装**（`showForView` + `VIEW_TTL`），「看这块地」绑在**右键行**（`peekArea`，独立 slot `strip/peek/N`，5 秒，关界面也画完）；`FOCUS_SLOT` 固定为 static final（原来每帧 `new String` 当 Outliner 键） |
| 「有一些字很小」 | 侧栏 128 → **176**（128 宽时可用文字宽仅 114px，坐标串必然被 `RsccTextFit` 缩到 0.6 倍）；坐标行改紧凑串 `100,64,100→120,80,120`；动作图标 3 → **2**（省 10px）；坐标行「三段式择优」（整串 → 左右两端点 → 才缩字）；用示意图 `tools/render_round130_strip_mock.py` 把每行文字宽度算出来当判据：**典型坐标 1.0 倍放得下**，只有「三个轴都带负号的极值」缩到 0.92 |
| 「有些没必要的废话」 | tooltip 6 行 → 3 行（删掉「点行选中 / 双击改名 / 点坐标行改坐标」那行 howto）；空态文案分三种（等待 / 没搜到 / 还没有区域）不再混用一句 |
| 「数据应该存在物品里面，而不是缓存仓」 | 已是事实（`AreaSelection` 注册版组件 + `AreaMarks` 的 `RsccAreaMarks` 都在选定器物品上）；本轮**没有**在方块实体新增任何区域字段，判据见 round130 脚本 §7-7 |

## 1. 文件与改动

| 文件 | 改动 |
| --- | --- |
| `client/screen/AreaStripPanel.java` | 删标记网格整页（渲染 / 命中 / 键盘 / JEI 接缝 / 12 格布局 / `MARK_*` 常量，共约 500 行）；加常驻搜索框（`ensureSearchBox` / `matchesQuery` / `coordSearchText` / `renderSearchBox` / `searchY` / `searchW`）；行动作改为 看 / 改名 / 删除；删除确认改成「红字 + ✓/✗」；坐标紧凑串 `compactCoords`；`allRows`（全集）与 `rows`（过滤后）分离——搜索只影响显示，**不影响**当前行 / 改名 / 坐标 / 去重判定；世界框改 `FOCUS_SLOT` 常量 + `removed()` 按 5 秒重新武装 |
| `client/screen/AdvancedCollectionCacheScreen.java` | `removed()` 删掉 `closeMarkGrid()`；新增 `matchRegionLabel()` 覆写（匹配区标题带显示「区域 N · 名字」/「匹配区（未选区域）」）与 `drawMatchAreaHint()` 覆写（未选区域时在匹配区正中画半透明底 + 一句提示）；`RsccTextFit` import |
| `client/screen/CollectionCacheScreen.java` | （队友 matchgrid）新增 `matchRegionLabel()` / `drawMatchAreaHint()` 两个钩子；匹配区标题改走 `RsccTextFit.drawFitLine`（基础版画面逐字不变） |
| `client/CollectionCacheJeiPlugin.java` | （队友 matchgrid）删掉「侧栏标记网格」分支 / `stripOf` / `AreaStripPanel` import；`getGuiExtraAreas`（JEI 让位）与 48 格 ghost 通道不动 |
| `block/entity/AdvancedCollectionCacheBlockEntity.java` | 新增 `addAreaMarkerEntry(int areaIndex, MarkerEntry marker)`（条目级追加；同 id 去重 / `MATCH_CAPACITY` / 单条 + 整表体积预算，与 `addAreaMarker` 同口径） |
| `menu/AdvancedCollectionCacheMenu.java` | `applyMarkerConfig(...)` **空格子分支**改为真新增（`addAreaMarkerEntry` + `notifyMarkerResult`），修掉 JEI 拖入空格子静默丢包；`Component` import |
| `network/AreaMarkAddItemPacket.java` | **删除**（第 125 轮为侧栏标记网格 JEI 拖入而开，唯一发送方随标记网格一起消失 ⇒ 死通道；`RS_Create_Compat` 的注册行同步删除） |
| `lang/zh_cn.json`、`lang/en_us.json` | 删 18 个 `area_strip.mark.*` / `tip.howto` / `tip.to` / `tip.filters`；加 10 个（`search.hint`、`search.none`、`peer.glyph`、`confirm.glyph`、`cancel.glyph`、`title.filtered`、`tip.size.filters`、`collection_cache.match_area.pick_hint`、`advanced_collection_cache.match_label`、`.none`）；`delete.confirm.this` 改成带参数「确认删除 #%1$s？」 |

## 2. 修掉的两个**真 bug**（不是 UI 调整）

1. **JEI 拖到匹配区空格子 ⇒ 静默丢包**：`SetCollectionMarkerConfigPacket` → `AdvancedCollectionCacheMenu#applyMarkerConfig`
   只调 `setAreaMarkerConfig`，而它对「该下标还没有条目」直接 `return false`。玩家从 JEI 往空格子拖，
   界面没有任何反应 —— 正是用户说的「我在匹配器里匹配就应该是可以直接拖进去」。
   现在空格子走新增分支；失败（重复 / 满 / 数据太大 / 非法）都有 actionbar 一句话。
2. **关掉 GUI 世界框就消失**：第 125 轮契约写的是「关界面清框」，于是「点行 → 关界面 → 抬头看」
   永远看不到框。现在 `removed()` 按 5 秒 TTL 重新武装（与旧 `AreaSelectorScreen` 同一口径），
   并用 `AreaOutline.showForView` 的独立 slot 保证「显示全部」也一起续 5 秒。

## 3. 验收（全部可复跑）

| 项 | 结果 |
| --- | --- |
| 独立 javac | `LEAD_COMPILE_OK sources=515`（少 1 = 删掉的包） |
| Gradle | `BUILD SUCCESSFUL`，`1.2.0-beta.31` |
| 全量自检 | **192 个脚本 / 0 失败** |
| 新自检 | `tools/selfcheck_round130_area_strip_rework.py`：**77 检查 / 22 反例 / ALL_PASS**（空树 59 项判负、旧实现特征拼回 4 类判负 ⇒ 有判别力） |
| 旧自检维护 | `selfcheck_round125_area_strip.py`：几何 128/96/72 → 176/140/108，删掉的标记网格 21 条断言移交 round130（111 断言 / 26 反例 / 1 跳过） |
| 反例取证修法 | `round69 / 76 / 80` 的「git HEAD = 修前」反例自证在新 HEAD 下必然假失败 ⇒ 新增 `tools/_prefix_history.py`，改为在 git 历史里回溯「最近一个仍含旧写法」的版本（取证不再依赖 HEAD 恰好是修前） |
| 文案豁免 | `round126`：新增 2 处分词（匹配区标题带 / 空态提示句）、清掉 6 条失效冻结站点 |
| 槽框登记 | `gui_slot_registry.py` 删掉侧栏标记格的 slotFrame 登记（源里已无该调用点） |
| 死通道 | `round92`：`AreaMarkAddItemPacket` 删除后回到冻结基线（6 条既有死通道，属其它批次） |

## 4. 需要实机确认（我只有编译 / 像素 / 自检证据）

1. 侧栏 176 宽后，一行里「名字 + ◎✎✕ + 坐标紧凑串」是否都不缩字号（1080p / GUI 缩放 3）。
2. 搜索：输入 `100` 命中坐标、输入名字片段命中名称、`Esc` 清空、过滤中标题「· 命中 N」。
3. 删除：点 ✕ → 红字「确认删除 #N？」+ ✓ / ✗；点 ✗ / 3 秒后回退；点 ✓ 才真的删。
4. 匹配区：点一行 → 标题带变「区域 N · 名字」；从背包 / JEI 拖物品进空格子都能加进去。
5. 世界框：点行后关掉界面，框应继续亮约 5 秒；**右键一行**也亮 5 秒。
6. 选中器数据：拆下选定器去另一台机器，区域与过滤（匹配区条目）应跟着走。

## 5. 刻意没做

- **不改 `AreaSelectorScreen` / `AreaMarkScreen`**（主手世界路径仍可用；契约 §5「退役范围不许一刀切删能力」）。
  若用户后续只在机器前操作，可以再谈把这条世界路径也退役。
- **JEI 幽灵栈拖到空格子时追加到列表末尾**：`AreaMarks` 的编码会剔掉空条目，标记表没有「洞」，
  所以精确落在第 N 格在数据层无法表达；追加 + 服务端回传快照是唯一自洽解。
- 侧栏没有做「拖拽排序区域」：本轮用户没提，且区域顺序与过滤数据以几何为键，排序不影响语义。
