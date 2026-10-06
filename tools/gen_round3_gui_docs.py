# -*- coding: utf-8 -*-
"""第三轮 GUI 背景技术文档生成器（按用户规则用 Python 生成带精确几何的文档）。

输出：
  tmp_textures/ADV_QUANTITY_KEEPER_GUI_DOC_V2.md     高级物品定量保持器主窗口（210x210）
  tmp_textures/COLLECTION_MARKER_CONFIG_GUI_DOC.md   归流缓存仓「匹配设置」子窗口（176x150）
  tmp_textures/ADV_KEEPER_SLOT_CONFIG_GUI_DOC.md     第三轮新增的两个子窗口
                                                     （产物/废料配置 190x132、三轴范围 200x126）

坐标全部与 Java 代码一一对应（Java 控件用「面板内精灵坐标」，槽位用「Menu 坐标 = 精灵 + 1」）。
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\tmp_textures"

HEADER = """# {title}

> **交付方式（必须先读）**：本文件的背景由**用户的绘图工具**按下面给出的**精确几何 + 规定配色**
> 生成，**禁止 AI 手绘 / 禁止自创画风 / 禁止改变分区与尺寸**。背景里**不得烘焙任何文字、数字、图标**
> （所有文字与图标都由 Java 运行时绘制，背景只负责面板、分区与槽位框）。
> 生成后请**按「交付 PNG 清单」的文件名与尺寸**放到对应路径；Java 侧已经留好替换点，
> PNG 到位后改一行常量即可生效（见 §6）。

***

## 1. 窗口与面板规格

| 项 | 值 |
| --- | --- |
| 窗口逻辑尺寸 | **{w} × {h}** px（GUI 缩放后的逻辑像素） |
| 面板边框 | **{border}px 九宫格**（四角原样、四边与中心拉伸，边框不随尺寸变粗） |
| 外描边 | 1px `#000000`（不透明度 100%） |
| 面板底 | `#C6C6C6` |
| 上/左高光 | 1px `#FFFFFF` |
| 下/右暗边 | 1px `#555555` |
| 圆角 | 2px（仅四角，与 MC 原版 `recipe_book/overlay_recipe` 一致） |
| 槽位框（仅主窗口） | 18×18：外圈 1px `#373737`、槽底 `#8B8B8B`、上/左 1px `#373737`、下/右 1px `#FFFFFF` |

**槽位坐标规则（硬规则）**：精灵图坐标 → Menu 坐标 = **x、y 均 +1**。下表「槽位」给的是 Menu 坐标，
如果把槽位框画进背景 PNG，请把每个槽位框画在 **Menu 坐标 - 1** 的位置（即表中坐标各减 1）。

***

## 2. 分区与坐标表

{regions}

***

## 3. 必须留白给 Java 绘制的区域（背景里这些区域不得有任何花纹/文字/图标）

{blanks}

***

## 4. 配色规范（只允许使用下列颜色及其 1px 过渡）

| 用途 | 颜色 |
| --- | --- |
| 面板底 | `#C6C6C6` |
| 面板描边 | `#000000` |
| 高光 | `#FFFFFF` |
| 暗边 | `#555555` |
| 槽底 | `#8B8B8B` |
| 槽描边 | `#373737` |
| 深色文字（Java 绘制） | `#404040` / 标题 `#333333` / 次要说明 `#6A6A6A` |
| 强调红（Java 绘制，❌） | `#C03030` 系 |
| 强调绿（Java 绘制，✔） | `#2E7D32` 系 |

***

## 5. 交付 PNG 清单

| 文件名 | 尺寸 | 说明 |
| --- | --- | --- |
{deliverables}

***

## 6. Java 侧替换点（PNG 到位后改这里即可）

{hooks}

***

## 7. 交付前自检清单

- [ ] 尺寸与 §1 完全一致（**不允许**任何 1px 误差）。
- [ ] 边框：1px 黑描边 + 1px 白高光（上/左）+ 1px 暗边（下/右），四角圆角 2px。
- [ ] 面板底一律 `#C6C6C6`，没有渐变、没有噪点、没有阴影。
- [ ] §3 的留白区**完全纯净**（无花纹、无暗角、无文字）。
- [ ] 背景中**没有任何文字 / 数字 / 图标**（全部由 Java 运行时绘制）。
- [ ] 槽位框位置 = 表中 Menu 坐标 - 1（不要忘记 +1 偏移规则）。
- [ ] 背景不含透明像素（除文档明确要求透明的区域）。
"""


def doc(title, w, h, border, regions, blanks, deliverables, hooks):
    return HEADER.format(title=title, w=w, h=h, border=border, regions=regions,
                         blanks=blanks, deliverables=deliverables, hooks=hooks)


# ============================================================
# 1) 高级物品定量保持器主窗口（210x210）
# ============================================================
adv_rows = []
for r in range(4):
    y = 22 + r * 24
    adv_rows.append(
        f"| 第 {r + 1} 行 | 标记槽 | Menu (9, {y + 3}) | 18×18 |\n"
        f"| | 目标数量输入框 | 精灵 ({30}, {y + 4}) 40×12 | 由 Java 画 |\n"
        f"| | `−` 按钮 | 精灵 ({72}, {y + 4}) 10×12 | 由 Java 画 |\n"
        f"| | `+` 按钮 | 精灵 ({84}, {y + 4}) 10×12 | 由 Java 画 |\n"
        f"| | 单位文字（个 / mB） | 精灵 ({97}, {y + 7}) | 由 Java 画 |\n"
        f"| | 已存数量文字 | 精灵 ({110}, {y + 7})，可用宽 36px | 由 Java 画 |\n"
        f"| | 自动合成 ✔/❌ 按钮 | 精灵 ({148}, {y + 3}) 16×12 | 由 Java 画 |\n"
        f"| | 过量销毁 ✔/❌ 按钮 | 精灵 ({166}, {y + 3}) 16×12 | 由 Java 画 |"
    )
adv_upgrades = "\n".join(
    f"| 插件槽 {i + 1} | 升级槽 | Menu (188, {7 + i * 18}) | 18×18 |" for i in range(6)
)
adv_inv_rows = []
for r in range(3):
    adv_inv_rows.append(f"| 玩家背包第 {r + 1} 行 | 槽位 9 格 | Menu (9, {133 + r * 18}) 起，步长 18 | 18×18 |")
adv_inv = "\n".join(adv_inv_rows)

adv_regions = (
    "| 区域 | 内容 | 坐标（Menu / 精灵） | 尺寸 | 谁画 |\n"
    "| --- | --- | --- | --- | --- |\n"
    "| 标题 | 文字「高级物品定量保持器」 | 精灵 (8, 6) | — | Java |\n"
    + "\n".join(adv_rows) + "\n"
    + adv_upgrades + "\n"
    + adv_inv + "\n"
    f"| 快捷栏 | 9 格 | Menu (9, 190) 起，步长 18 | 18×18 | 背景烘槽框 |\n"
)
adv_blanks = (
    "1. 标题带：精灵 (8, 4) → (200, 18)（除标题文字本身，不得有花纹）。\n"
    "2. 每行的「状态文字带」：精灵 x=110..146、y=行顶+4..+16（已存数量文字所在）。\n"
    "3. 每行的两个开关按钮区：精灵 x=148..182（自动合成）、x=166..182（过量销毁），y=行顶+3..+15。\n"
    "4. 输入框与 ± 按钮区：精灵 x=30..94、y=行顶+4..+16。\n"
    "5. 插件槽列表右侧（精灵 x=206..209）保持面板底色，不得画分隔线。\n"
    "6. **任何位置都不得烘焙文字**（特别是「目标 / 已存 / 个 / mB」这些标签）。"
)
adv_deliverables = (
    "| `textures/gui/advanced_quantity_keeper.png` | **210 × 210** | 主窗口面板 + 全部槽位框（"
    "4 个标记槽 + 6 个插件槽 + 27 格背包 + 9 格快捷栏）。替换现有的占位 PNG。 |"
)
adv_hooks = (
    "`src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AdvancedQuantityKeeperScreen.java`：\n"
    "\n"
    "```java\n"
    "private static final ResourceLocation CUSTOM_PANEL = null; // ← 填成\n"
    "// ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, \"textures/gui/advanced_quantity_keeper.png\")\n"
    "```\n"
    "\n"
    "填好后 `renderBg` 会自动改为整图 `blit`（槽位框此时由 PNG 提供）。"
)

# ============================================================
# 2) 归流缓存仓「匹配设置」子窗口（176x150）
# ============================================================
marker_regions = (
    "| 区域 | 内容 | 坐标（面板内，像素） | 尺寸 | 谁画 |\n"
    "| --- | --- | --- | --- | --- |\n"
    "| 标题 | 「匹配设置」 | (8, 8) | — | Java |\n"
    "| 资源图标 | 被标记物品 / 流体图标 | (152, 6) | 16×16 | Java |\n"
    "| 数量标签 | 「数量（个 / mB）」 | (8, 32) | — | Java |\n"
    "| 数量输入框 | 仅数字 | (44, 28) | 124×16 | Java |\n"
    "| 增量按钮 ×6 | `-64 -10 -1 +1 +10 +64` | (8 + i×28, 50) | 每个 26×16 | Java |\n"
    "| 匹配 NBT 标签 | 「匹配 NBT（此物品）」 | (8, 79) | — | Java |\n"
    "| 匹配 NBT 开关 | ✔ 绿 / ❌ 红（一行文本 + 一个按钮） | (148, 70) | 20×14 | Java |\n"
    "| 标签过滤标签 | 「标签过滤」+ 状态文字 | (8, 96) | — | Java |\n"
    "| 标签输入框 | 如 `minecraft:logs` | (8, 108) | 112×16 | Java |\n"
    "| 「应用」按钮 | 启用标签过滤 | (122, 108) | 24×16 | Java |\n"
    "| 「清除」按钮 | 取消标签过滤 | (148, 108) | 24×16 | Java |\n"
    "| 「确定」按钮 | 写入数量 + NBT 规则 | (8, 128) | 76×16 | Java |\n"
    "| 「取消」按钮 | 返回父界面 | (92, 128) | 76×16 | Java |\n"
)
marker_blanks = (
    "1. 标题带：(6, 4) → (170, 20)。\n"
    "2. 数量输入框与其标签区：(6, 26) → (170, 46)。\n"
    "3. 增量按钮行：(6, 48) → (170, 68)。\n"
    "4. 匹配 NBT 行：(6, 70) → (170, 86)。\n"
    "5. 标签过滤两行（标签 + 状态文字、输入框 + 两个按钮）：(6, 88) → (170, 126)。\n"
    "6. 底部按钮行：(6, 126) → (170, 146)。\n"
    "7. 图标区右侧 (140, 4) → (170, 22) 之外不要画任何装饰。\n"
    "8. **不得烘焙文字**（含 NBT / tag 字样、按钮文字）。"
)
marker_deliverables = (
    "| `textures/gui/collection_cache/marker_config.png` | **176 × 150** | 匹配设置子窗口面板（无槽位框）。 |"
)
marker_hooks = (
    "`CollectionMarkerConfigScreen` 继承 `ChildConfigScreen`，只需覆写：\n"
    "\n"
    "```java\n"
    "@Override\n"
    "protected ResourceLocation customPanel() {\n"
    "    return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,\n"
    "        \"textures/gui/collection_cache/marker_config.png\"); // ← PNG 到位后填这里\n"
    "}\n"
    "```\n"
    "\n"
    "未覆写时默认使用原版九宫格精灵 `minecraft:recipe_book/overlay_recipe`（现在的效果）。"
)

# ============================================================
# 3) 第三轮新增的两个子窗口
# ============================================================
slot_regions = (
    "### 3.1 序列装配样板终端「产物 / 废料配置」子窗口（190 × 132）\n"
    "\n"
    "| 区域 | 内容 | 坐标（面板内） | 尺寸 | 谁画 |\n"
    "| --- | --- | --- | --- | --- |\n"
    "| 标题 | 「产物配置」/「废料配置」 | (12, 10) | — | Java |\n"
    "| 条目图标 | 该产物的物品 / 流体图标 | (162, 6) | 16×16 | Java |\n"
    "| 概率标签 | 「产出概率」 | (12, 38) | — | Java |\n"
    "| 概率 `−` | 按钮 | (68, 34) | 20×16 | Java |\n"
    "| 概率输入框 | 0..100 | (90, 34) | 44×16 | Java |\n"
    "| 概率 `+` | 按钮 | (136, 34) | 20×16 | Java |\n"
    "| 概率区间提示 | 「0 - 100 %」 | (164, 38) | — | Java |\n"
    "| 数量标签 | 「产出数量」 | (12, 62) | — | Java |\n"
    "| 数量 `−` | 按钮 | (68, 58) | 20×16 | Java |\n"
    "| 数量输入框 | 1..64 | (90, 58) | 44×16 | Java |\n"
    "| 数量 `+` | 按钮 | (136, 58) | 20×16 | Java |\n"
    "| 数量区间提示 | 「1 - 64」 | (164, 62) | — | Java |\n"
    "| 「确定」 | 写入概率 + 数量 | (12, 104) | 60×18 | Java |\n"
    "| 「取消」 | 返回终端 | (76, 104) | 60×18 | Java |\n"
    "\n"
    "留白区：标题带 (10, 4)→(186, 20)；两行控件带 (10, 30)→(186, 74)；底部按钮带 (10, 100)→(186, 126)。\n"
    "\n"
    "### 3.2 归流缓存仓「收集范围（三轴）」子窗口（200 × 126）\n"
    "\n"
    "| 区域 | 内容 | 坐标（面板内） | 尺寸 | 谁画 |\n"
    "| --- | --- | --- | --- | --- |\n"
    "| 标题 | 「收集范围（三轴）」 | (12, 10) | — | Java |\n"
    "| X 行 | 标签 X + `−` + 输入框 + `+` + 上限提示 | y = 32：`−`(36,32) 20×14、输入框(60,32) 40×14、`+`(104,32) 20×14、提示 (132,35) | — | Java |\n"
    "| Y 行 | 同上 | y = 52 | — | Java |\n"
    "| Z 行 | 同上 | y = 72 | — | Java |\n"
    "| 说明文字 | 「范围升级 ×N」 | (12, 94) | — | Java |\n"
    "| 「关闭」 | 返回父界面 | (70, 106) | 60×18 | Java |\n"
    "\n"
    "留白区：标题带 (10, 4)→(196, 20)；三行控件带 (10, 28)→(196, 90)；说明带 (10, 90)→(196, 104)；按钮带 (10, 104)→(196, 126)。\n"
)
slot_blanks = (
    "两个窗口都必须满足：\n"
    "\n"
    "1. 背景只画面板与内凹分区，**不得画控件底**（输入框 / 按钮由 Java 用原版控件绘制）。\n"
    "2. **不得烘焙任何文字**，包括 `−` `+` `确定` `取消` `X` `Y` `Z` 等。\n"
    "3. 图标区（右上角 16×16）必须留白。\n"
    "4. 除面板的 1px 描边与九宫格高光/暗边外，不要有任何装饰线。"
)
slot_deliverables = (
    "| `textures/gui/spt/result_config.png` | **190 × 132** | 产物 / 废料配置子窗口面板。 |\n"
    "| `textures/gui/collection_cache/range_config.png` | **200 × 126** | 三轴范围子窗口面板。 |"
)
slot_hooks = (
    "两个界面都继承 `ChildConfigScreen`，覆写 `customPanel()` 返回对应 `ResourceLocation` 即可\n"
    "（`SequenceResultConfigScreen` / `CollectionRangeConfigScreen`）；\n"
    "未覆写时默认使用原版九宫格精灵 `minecraft:recipe_book/overlay_recipe`。"
)


def write(name, text):
    path = os.path.join(ROOT, name)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote", path, len(text), "chars")


write("ADV_QUANTITY_KEEPER_GUI_DOC_V2.md", doc(
    "高级物品定量保持器 GUI 背景技术文档 V2（重排后布局）", 210, 210, 4,
    adv_regions, adv_blanks, adv_deliverables, adv_hooks))

write("COLLECTION_MARKER_CONFIG_GUI_DOC.md", doc(
    "归流缓存仓「匹配设置」子窗口 GUI 背景技术文档", 176, 150, 4,
    marker_regions, marker_blanks, marker_deliverables, marker_hooks))

# 该文件同时覆盖第三轮新增的两个子窗口（产物/废料配置、三轴范围）
slot_doc = doc(
    "高级保持器单槽配置 / 产物废料配置 / 三轴范围子窗口 GUI 背景技术文档", 190, 132, 4,
    slot_regions, slot_blanks, slot_deliverables, slot_hooks)
slot_doc = slot_doc.replace(
    "| 窗口逻辑尺寸 | **190 × 132** px（GUI 缩放后的逻辑像素） |",
    "| 窗口逻辑尺寸 | **190 × 132**（产物/废料配置）与 **200 × 126**（三轴范围）两种 |")
write("ADV_KEEPER_SLOT_CONFIG_GUI_DOC.md", slot_doc)
