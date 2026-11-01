# 第 125 轮 · 旧自检锚点迁移清单（strip-verify 产出，草稿）

> 规格来源：`docs/ROUND125_AREA_STRIP_CONTRACT.md`（§2 / §3 / §4 / §4.5 / §5 / §6）。
> 本文只**登记**旧脚本里受本轮改动影响的断言与处置口径，**不动旧脚本本身**——
> 旧脚本由各自的 owner（或 Lead 派单）按本表迁移；新判据先由
> `tools/selfcheck_round125_area_strip.py` 接管（110 条契约断言 / 38 条反例）。
>
> 判定纪律：**误报（把没事的写成需改）与漏报（把会红的写成没事）都算失败**。
> 方法：`ops_grep` 扫 `tools/` 全部脚本，命中本轮受影响符号后再逐条读断言上下文判定。

## 0. 计数口径（先对数，别让下一轮以为「只有 9 处」）

| 口径 | 数字 | 明细 |
| --- | --- | --- |
| **必须改的断言组** | **9 条**（表 A） | `round106`×4（A1–A4）· `round99`×2（A7–A8）· `round101`×1（A5）· `round90`×1（A6）· `audit_lang_keys`×1（A9，全局联动） |
| **被删/替换的源码锚点与反例锚点** | **20 处** | A1:4 · A2:1 · A3:3 · A4:2 · A5:2 · A6:1 · A7:4 · A8:2 · A9:1 |
| 确认**不动** | **13 条**（表 B） | 每条给「为什么不用改」一句话 |
| 非破坏建议 | 3 条（表 C） | 不做也不会红 |
| 涉及文件 | 5 个 | 4 个自检脚本 + 1 个全局审计（`audit_lang_keys.py`） |

> **哪些脚本完全不在名单里**：`round95_range_charger` / `round96_cache_area` /
> `round110` / `round112` / `round113` / `round119` / `round120` / `round121` /
> `round124_area_mark_layout` / `round124_area_rename` / `round95_collection_cache` /
> `round93` / `round69` —— 理由见表 B。**除表 A 的 5 个文件外，其余脚本本轮 0 改动。**

> 另：Lead 已确认 §4.5 新包是 **6 分量**（此前口头「7 个」是笔误），契约书不改；
> `selfcheck_round125_area_strip.py` 按 6 分量断言。

## 0.1 两个「重点抽查项」的结论（先说清楚，避免误报）

| 抽查项 | 全仓 grep 结果 | 结论 |
| --- | --- | --- |
| 锚定「从机器路径打开 `AreaSelectorScreen`」 | `openAreaScreen` / `new AreaSelectorScreen` 在 `tools/**` 的命中 = **0 处**（唯一命中是 `selfcheck_round112_area_marks.py` 的**主手路径** `new AreaSelectorScreen(null)`，§5 保留） | **没有旧断言需要改**；`round125` §3.2「本文件 0 次」是**新增**判据，不是迁移 |
| 锚定 `(186,219)` / `(218,219)` 单显/全显按钮 | 坐标字面量在 `tools/**` 的命中 = **0 处**（`SINGLE_BTN_X` / `ALL_BTN_X` 同样 0 处） | 旧断言是**符号锚**（`toggleSingleShow` / `toggleRange` 覆写 / `range_button`），不是坐标锚；真正会红的是表 A 的 A1 / A5 / A6 / A7 |

---

## 1. 表 A：**必须改**（9 条断言组 / 20 处锚点；每条给「原文 → 替换成什么 → 为什么不放低判据」）

### A1 · `selfcheck_round106_adv_cache_areas.py` L460-464（⑤-11）· 4 处锚点

**原断言原文**：

```python
check("⑤-11 单显 / 全显：两颗按钮 + 覆写 toggleRange，几何全部来自服务端快照",
      "private void toggleSingleShow()" in adv_screen
      and "protected void toggleRange()" in adv_screen
      and "snapshot.entryFor(rawIndex)" in adv_screen
      and "showAreas(boxes);" in adv_screen)
```

**替换成**（翻成「旧按钮/旧覆写必须已删除」；侧栏的两条显示路径不在这里重复写第二份）：

```python
check("⑤-11 单显 / 全显已并入侧栏：机器界面不再有单显按钮、不再覆写 toggleRange（第 125 轮 §5）",
      "private void toggleSingleShow()" not in adv_screen
      and "protected void toggleRange()" not in adv_screen
      and "SINGLE_BTN_X" not in adv_screen and "ALL_BTN_X" not in adv_screen)
```

**为什么不放低判据**：原意是「高级界面真的能单显/全显、且几何只来自服务端快照」。按钮退役后，
这条原意被拆成两半：**删除**（本断言，翻成 not in，比原来更严）与**侧栏两条显示路径 + 快照口径**
（`selfcheck_round125_area_strip.py` §4：点行发 `AreaSetFilterContextPacket`、底部「显示全部」
走 `showForView`、数据源只读 `lastFor`）。**覆盖没减少，只是换了归属**；如果哪天有人把侧栏显示删了，
round125 会红。

### A2 · `selfcheck_round106_adv_cache_areas.py` L481-484（⑤-14）· 1 处锚点

**原断言原文**：

```python
check("⑤-14 空区域 / 没装 / 维度不符 ⇒ 不画框，并且给一句原因（不静默）",
      'hint(snapshot.status() == 3 ? LANG + "info.dimension" : LANG + "info.empty")' in adv_screen
      and 'hint(LANG + "show.single.none")' in adv_screen
      and "if (AreaOutline.isDisabled())" in adv_screen)
```

**替换成**（删中间那句；「单显没有当前区域」这条提示随按钮消失，但「不静默」的三条原因句还在）：

```python
check("⑤-14 空区域 / 没装 / 维度不符 ⇒ 不画框，并且给一句原因（不静默；单显专属提示已随按钮删除）",
      'hint(snapshot.status() == 3 ? LANG + "info.dimension" : LANG + "info.empty")' in adv_screen
      and "if (AreaOutline.isDisabled())" in adv_screen)
```

**为什么不放低判据**：原断言真正钉的是「**不静默**」（空/没装/维度不符都给原因、Outliner 不可用也要说），
不是钉「必须存在 `show.single.none` 这个键」。删掉的那半句只描述一颗已退役按钮的专属状态；
而「槽空 ⇒ 不画侧栏 + GUI 内保留『区域』入口提示」「维度不符 ⇒ 行内灰/状态句」由 round125 §4 钉死。

### A3 · `selfcheck_round106_adv_cache_areas.py` L510-513（`_KEYS`）+ L535（⑤-21）· 3 处语言键

**原断言原文**（`_KEYS` 里的三行 + 计数断言）：

```python
    "gui.rs_create_compat.advanced_collection_cache.show.single",
    "gui.rs_create_compat.advanced_collection_cache.show.single.tip",
    "gui.rs_create_compat.advanced_collection_cache.show.single.none",
...
check("⑤-21 本轮新增 22 个语言键中英成对存在", not _missing, _missing)
```

**替换成**——**二选一，必须与 A9 同步**：

方案 **(b) 推荐**（按钮删除后三键没有绘制点，留着就是 `audit_lang_keys` 的孤儿键）：

```python
# 从 _KEYS 删掉上面三行；把 ⑤-21 的名字从「22 个」改成「19 个」
check("⑤-21 本轮新增 19 个语言键中英成对存在", not _missing, _missing)
# 同时删 zh_cn.json / en_us.json 里的三条 show.single* 键
```

方案 (a)（若侧栏决定复用 `.tip` 做行点击/显示全部的 tooltip）：**三行原样保留**，
并确保代码里真的 `Component.translatable(LANG + "show.single.tip")` 有绘制点
（否则 audit 仍报孤儿）。

**为什么不放低判据**：「中英成对 + 键真的被用」这条原意一条不少——方案 (b) 只是把
「被删按钮的键」从清单里拿掉，`_missing` 的判据一字未改；方案 (a) 则要求侧栏真的消费这些键。

### A4 · `selfcheck_round106_adv_cache_areas.py` L607-611（⑥-b8）· 2 处锚点（断言 + 反例锚）

**原断言原文**：

```python
check("⑥-b8 反例：单显/全显改回「客户端自己读选定器物品算几何」⇒ ⑤-11 判负",
      "snapshot.entryFor(rawIndex)" not in mutated(
          adv_screen, "final AdvancedAreaSnapshotPacket.AreaEntry entry = rawIndex < 0 ? null : snapshot.entryFor(rawIndex);",
          "final AdvancedAreaSnapshotPacket.AreaEntry entry = null; "
          "final Area fallback = AreaSelection.get(menu.getAreaSelectorStack()).selected();"))
```

**为什么会崩**：`mutated()` 内部是 `assert old in text, "反例锚点不存在..."`（脚本 L548-550）。
`final ... entry = rawIndex < 0 ? null : snapshot.entryFor(rawIndex);` 这行随 `toggleSingleShow`
一起消失后，`assert` 直接抛 `AssertionError` ⇒ **整个脚本崩**（不是「一条红」）。

**替换成**：**整条删除**（⑤-11 已翻成「按钮必须不在」，这条反例没有可判负的对象），
由 round125 的等价反例接管（`selfcheck_round125_area_strip.py` 的 **C-19**
「点行不发 `SetAreaFilterContextPacket`」+ **C-23/C-24**「客户端自己判断可用性 / 自己读选定器算几何」）。
若一定要保留一条同型反例，就换成对**侧栏**的天真实现（读 `AreaStripPanel.java`）：

```python
_AREA_STRIP = read(os.path.join(JAVA, "client", "screen", "AreaStripPanel.java"))
check("⑥-b8 反例：侧栏改回「客户端自己读选定器物品算几何」⇒ round125 §4 判负（本脚本不重复）",
      "AreaSelection.get(" not in _AREA_STRIP)
```

**为什么不放低判据**：删除的是「反例的靶子」，不是判据。原意「单显/全显的几何不许客户端自算」
在 round125 §4 有**更强**的三条（数据源只读快照 / 不读方块实体 / 不做可用性判断）+ 两条反例。

### A5 · `selfcheck_round101_collection_absorb.py` L517-538（`anchor_19`）+ L544-546（④-19）· 2 处锚点

**原断言原文**（`anchor_19` 的返回表达式，节选）：

```python
    return ("private Component editAreaSummary(" in src
            and 'LANG + "filter.row.empty"' in src
            and "protected void drawStatusLine(" in src
            and "private void collectFrameIcons(" in src
            and "public static int drawFilterIcons(" in src
            and "private void toggleSingleShow(" in src
            and "protected void toggleRange()" in src
            and "Required" not in src
            and "trimToWidth" not in src)
```

**替换成**（删两行；④-19 的标题同步改名）：

```python
def anchor_19(src):
    return ("private Component editAreaSummary(" in src
            and 'LANG + "filter.row.empty"' in src
            and "protected void drawStatusLine(" in src
            and "private void collectFrameIcons(" in src
            and "public static int drawFilterIcons(" in src
            and "Required" not in src
            and "trimToWidth" not in src)

check("④-19 高级界面：显示「正在编辑哪个区域」（空集区域明说『不吸任何东西』）+ 过滤图标直显"
      "（单显/全显已并入侧栏，由 round125 §4 断言；第 124 轮口径：零省略号、只缩字号）",
      anchor_19(adv_screen))
```

**为什么不放低判据**：`anchor_19` 的原意（摘要 + 图标直显 + 零省略号）**全部保留**；
删掉的两行只描述「单显/全显用哪颗按钮实现」，而该实现已由 round125 §4 接管。

### A6 · `selfcheck_round90_area_selector.py` L362-367（⑦）· 1 处锚点

**原断言原文**：

```python
check("⑦ 查看范围按钮存在且用统一橙色（第 99 轮：绘制逻辑在基类，按钮仍在高级界面）",
      'LANG + "range_button"' in sources["AdvancedCollectionCacheScreen.java"]
      and "COLOR = 0xFF9F1C" in sources["AreaOutline.java"]
      and "RANGE_OUTLINE_COLOR = 0xFF9F1C" in sources["RangeChargerScreen.java"]
      and "AreaOutline.showForView" in (sources["AdvancedCollectionCacheScreen.java"]
                                        + sources["CollectionCacheScreen.java"]))
```

**替换成**（把「按钮在高级界面」改回「按钮在基础版」；高级版的显示改由侧栏承担）：

```python
check("⑦ 查看范围按钮仍在**基础版**（`range_button`）且用统一橙色；高级版改由侧栏「显示全部」承担",
      'LANG + "range_button"' in sources["CollectionCacheScreen.java"]
      and "COLOR = 0xFF9F1C" in sources["AreaOutline.java"]
      and "RANGE_OUTLINE_COLOR = 0xFF9F1C" in sources["RangeChargerScreen.java"]
      and "AreaOutline.showForView" in sources["CollectionCacheScreen.java"])
```

**为什么不放低判据**：原意是「查看范围真能画框 + 三处颜色统一」——**一个字没松**；
只是把「按钮在哪个类」从 adv 挪到 base（基础版仍用自己的范围按钮，契约 §5 没动它）。
若侧栏的「显示全部」也走 `showForView`，那是 round125 §4 的额外断言。

### A7 · `selfcheck_round99_collection_range.py` L214-231 + L236-239（③2）· 4 处锚点

**原断言原文**：

```python
def adv_button_problems(adv_src, base_src):
    problems = []
    if method_body(adv_src, r"protected boolean usesOwnRangeButton\s*\(\s*\)") is None:
        problems.append("高级版没有覆写 usesOwnRangeButton()（会与「区域」按钮叠在同一位置）")
    elif "return true;" not in method_body(adv_src, r"protected boolean usesOwnRangeButton\s*\(\s*\)"):
        problems.append("高级版 usesOwnRangeButton() 不是 true（基类会再造一颗挤掉「区域」按钮）")
    trigger = method_body(adv_src, r"protected Button rangeTriggerButton\s*\(\s*\)")
    if trigger is None or "viewRangeButton" not in trigger:
        problems.append("高级版 rangeTriggerButton() 没有返回自己那颗「范围」按钮")
    init_body = method_body(adv_src, r"protected void init\s*\(\s*\)")
    if init_body is None or 'LANG + "range_button"' not in init_body:
        problems.append("高级版 init() 没有创建「范围」按钮")
    elif "toggleRange()" not in init_body:
        problems.append("高级版「范围」按钮没有接到 toggleRange()（点击路径断）")
    ...
check("③2 高级版：用它自己那颗「范围」按钮走同一条 toggleRange 链路（不重复建按钮）",
      not adv_problems, "; ".join(adv_problems))
```

**替换成**（判据**翻转**：高级版必须没有这些按钮/覆写；基础版链路仍由 ③1 钉死）：

```python
def adv_button_problems(adv_src, base_src):
    problems = []
    if method_body(adv_src, r"protected boolean usesOwnRangeButton\s*\(\s*\)") is not None:
        problems.append("高级版还在覆写 usesOwnRangeButton()（按钮已删除，覆写是死代码）")
    if method_body(adv_src, r"protected Button rangeTriggerButton\s*\(\s*\)") is not None:
        problems.append("rangeTriggerButton() 应随两颗按钮一起删除")
    init_body = method_body(adv_src, r"protected void init\s*\(\s*\)")
    if init_body is not None and 'LANG + "range_button"' in init_body:
        problems.append("高级版 init() 又建了「范围/全显」按钮（契约 §5 已删除）")
    if "toggleRange" not in strip_comments(base_src):
        problems.append("基类根本没有 toggleRange()，基础版按钮链路断")
    return problems

check("③2 高级版：两颗「单显/全显」按钮已删除，不再覆写范围按钮（第 125 轮 §5；基础版链路见 ③1）",
      not adv_problems, "; ".join(adv_problems))
```

**为什么不放低判据**：这是**加强**——原来要求「必须存在」，现在要求「必须不存在」，
且额外钉住「基类 `toggleRange` 仍在」（基础版链路不能断）。无一条判据被删掉原意。

> **Lead 实施修正（定稿，2026-10-31）**：A7 原文「高级版还在覆写 `usesOwnRangeButton()` ⇒ 算问题」
> **是错的**。基类 `CollectionCacheScreen#init` 是 `if (!usesOwnRangeButton())` 才造「查看范围」按钮
> （`CollectionCacheScreen.java:576`）—— 高级版一旦删掉这个覆写，基类就会在 `(8,219)` 再造一颗、
> 压住区域选定器槽，正好破坏契约 §5「GUI 上不再需要任何范围显示按钮」。定稿判据：
> `usesOwnRangeButton()` **必须仍覆写且 `return true;`**（防止基类补回）、`rangeTriggerButton()`
> **必须不存在**、`init()` 里 **不得出现** `LANG + "range_button"`、基类 `toggleRange` 必须仍在。
> 实际补丁见 `tools/selfcheck_round99_collection_range.py` 的 `adv_button_problems`。
> 同理 **A8 不用改**：`usesOwnRangeButton` 的反例在新判据下**仍有判别力**（删覆写 ⇒ ③2 判负），保留原反例。
> 另：A6 里基础版的键是 `show_range`（`CollectionCacheScreen.java:578`），不是 `range_button` ——
> `range_button` / `.tip` 是高级版专用键，已随按钮退役删除。

### A8 · `selfcheck_round99_collection_range.py` L515-532（⑨4）· 2 处反例锚

**原断言原文**：

```python
MUT_ONLY_BASE_ADV = re.sub(
    r"@Override\s*protected boolean usesOwnRangeButton\s*\(\s*\)\s*\{[^}]*\}", "", SRC["adv"], flags=re.S)
MUT_ONLY_BASE_ADV = re.sub(
    r"@Override\s*protected int collectionRangeMode\s*\(\s*\)\s*\{.*?\n    \}", "",
    MUT_ONLY_BASE_ADV, flags=re.S)
check("⑨4反例：只做基础版（高级版没有自己的按钮 / 没有口径覆写）⇒ 判负",
      len(adv_button_problems(MUT_ONLY_BASE_ADV, SRC["base"])) >= 1
      and len(adv_mode_problems(MUT_ONLY_BASE_ADV)) >= 1)
```

**替换成**（删掉 `usesOwnRangeButton` 那半；保留仍存在的 `collectionRangeMode` 覆写）：

```python
MUT_ONLY_BASE_ADV = re.sub(
    r"@Override\s*protected int collectionRangeMode\s*\(\s*\)\s*\{.*?\n    \}", "",
    SRC["adv"], flags=re.S)
check("⑨4反例：删掉高级版 `collectionRangeMode()` 覆写 ⇒ ③2 的 mode 判据判负",
      len(adv_mode_problems(MUT_ONLY_BASE_ADV)) >= 1)
```

**为什么不放低判据**：`usesOwnRangeButton` 这条反例的靶子已不存在（按钮退役），
删它是删靶子；`collectionRangeMode` 覆写仍在、仍被测——**仍然有反例证明判据有判别力**
（round125 另有 38 条反例覆盖侧栏侧）。

### A9 · `tools/audit_lang_keys.py`（全局审计）· 1 处联动

**原判据**：「孤儿键 0 条 / 问题总数 0」。

**替换成**：代码不动，**改数据时必须三处同步**：

```bash
# 若 A3 选 (b)（删三键）：
#   1) 删 zh_cn.json / en_us.json 里的 show.single / show.single.tip / show.single.none
#   2) 删 round106 _KEYS 里对应三行（A3）
#   3) 重跑：
python tools/audit_lang_keys.py     # 必须仍「孤儿键 0 条 / 问题总数 0」
```

**为什么不放低判据**：审计本身就是判据，一个字不改；它会把「删了代码没删键」或
「删了键没删引用」两种不同步直接抓出来（这正是 §6.2「受影响的旧脚本全绿」的一部分）。

---

## 2. 表 B：**确认不动**（13 条，每条给「为什么不用改」）

| # | 脚本 | 为什么不用改（一句话） |
| --- | --- | --- |
| B1 | `selfcheck_round95_range_charger.py` | 全文件 0 处引用 `AreaSelectorScreen` / `AdvancedCollectionCacheScreen`——充电器范围框是自维护生命周期，与侧栏无交集。 |
| B2 | `selfcheck_round96_cache_area.py` | L66-67 读的是被**保留**的 `AreaSelectorScreen`；L452 的 `ADV_SCREEN_SRC` 只参与拼串，真正的判据 `distinctAreas()` 锚在 BE 上，不随 GUI 改。 |
| B3 | `selfcheck_round110_area_selector_name.py` | 锚 `AreaSelectorScreen#flushRename/applyNameNow`（L227/L234/L318-366）——契约 §5 明令该文件保留完整能力（主手路径），不改它就没事。**风险**：若实现方「收敛」删了 rename 会连带红（见 §5 风险 2）。 |
| B4 | `selfcheck_round112_area_marks.py` | L476-477 锚 `AreaSelectorScreen → new AreaMarkScreen(...)`、L509/514/673-674 锚 `AreaSelectorHandler` 的 `new AreaSelectorScreen(null)` ×2——全在 §5 保留范围（主手路径）。 |
| B5 | `selfcheck_round113_area_mark_e2e.py` | 全部锚 BE / `AreaMarkEditPacket` / 内容模型，不读 GUI 代码。 |
| B6 | `selfcheck_round119_area_selector_sync.py` | L309-315 锚 `AreaSelectorScreen#targetStack` 的菜单分支——文件保留，方法不动。 |
| B7 | `selfcheck_round120_area_mark_registry.py` | L72-77 的 allowlist 指向被保留的 `AreaSelectorScreen` 两参 `AreaMarks.get`，脚本注释已写明「属后续任务」。 |
| B8 | `selfcheck_round121_area_c4_c7.py` | 只读 `AdvancedCollectionCacheMenu` / `AdvancedCollectionCacheBlockEntity`，本轮不碰这两个文件。 |
| B9 | `selfcheck_round124_area_mark_layout.py` | 只读 `AreaMarkScreen`（§5 保留）；「机器路径不再打开它」不影响该界面自身的几何判据。 |
| B10 | `selfcheck_round124_area_rename.py` | 锚 `AreaSelectorScreen#flushRename/scheduleRename`（L191-216 / L606-624）——同 B3，文件保留。**风险**同 B3。 |
| B11 | `selfcheck_round95_collection_cache.py` | L612-622 的 E3/E4 锚的是**基类** `toggleRange` / `removed()` 重新武装——基础版仍用它（契约只删高级版按钮），基类一个字没动。 |
| B12 | `selfcheck_round93_proxy_cache_area.py` | adv_screen 的 `statusLine(` / 过滤图标 / `hoveredSlot` 判据本轮明令不动（只删两颗按钮、加侧栏）。 |
| B13 | `selfcheck_round69_tooltip_stack.py` | 只在「待扫描文件清单」里列 adv / area 屏，不锚任何具体语句。 |

## 3. 表 C：非破坏但**建议**顺手做（3 条）

| # | 项 | 说明 | 建议 |
| --- | --- | --- | --- |
| C1 | `selfcheck_no_ellipsis.py` | 用**显式白名单**扫 client 源码；新文件 `AreaStripPanel.java` / `AreaStripBounds.java` 不在名单（不会红，但也不会被「零省略号」约束） | 追加两个新文件（`AreaStripPanel` 有自绘文字，尤其该加） |
| C2 | `audit_gui_textures.py` | 契约未要求侧栏新增贴图；若实现方完全自绘则无需改 | 默认无需改；若新增 PNG 再登记新图 + 布局 |
| C3 | `audit_placeholder_keys.py` | 侧栏若新增 `%s` 文案键，键/占位符个数要成对 | 视实现；不新增文案键则无需改 |

## 4. 迁移原则（写死，防「放宽原意」）

1. 旧断言的**原意**（「查看范围能真的画框」「两台界面共用一条链路」「空态要说明」「零省略号」）
   一条都不许删；只许把**锚点**从「高级界面的按钮/方法」搬到「侧栏的等价落点」或翻成「旧按钮必须不存在」。
2. 新落点判据以 `tools/selfcheck_round125_area_strip.py` 为准（§2/§3/§4/§4.5），旧脚本不要再各写一份
   「侧栏断言」，避免三处漂移。
3. A3/A9 的语言键决策必须**同步四处**：代码里的 key 引用、`zh_cn.json` / `en_us.json`、
   round106 的 `_KEYS` 列表、`audit_lang_keys.py` 的孤儿键结果。
4. A4/A8 这类「反例锚点消失」是最隐蔽的红：`assert old in text` 在锚点消失时**直接崩**、
   `re.sub` 在锚点消失时**空转**（脚本仍 PASS 但没有判别力）——迁移后必须手动确认「反例仍能判负」。

## 5. 最高风险 3 条（Lead 优先处理顺序）

1. **`selfcheck_round106_adv_cache_areas.py`**：A1–A4 连着四条，其中 **A4（L607-611）会让脚本直接崩**
   （`assert old in text`），不是「红一条」；A3 还会牵动 `audit_lang_keys`。
2. **`selfcheck_round99_collection_range.py`**：A7–A8（L214-231 / L236-239 / L515-532）。
   **它不在 Lead 给的第 125 轮清单里**（清单写的是 `round95_range_charger`，容易看漏）——但它必红。
3. **`selfcheck_round90_area_selector.py` A6（L362-367）+ `selfcheck_round101_collection_absorb.py`
   A5（L517-538）**：两个老资格脚本，锚点是「第 99/106 轮就写死的高级界面按钮/覆写」，退役后必红。
