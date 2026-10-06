# -*- coding: utf-8 -*-
"""生成/刷新 归流缓存仓「输入面配置」子窗口 GUI 技术文档。

几何与 client/screen/CollectionInputFaceConfigScreen.java 一一对应
（本轮已与 client/screen/ChamberFaceConfigScreen.java 对齐：40×40 十字网 + 原版字号 + 右侧图例）。
运行：python tools/gen_collection_input_face_doc.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tmp_textures", "COLLECTION_INPUT_FACE_GUI_DOC.md")

# ===== 与 Java 常量一一对应的几何（面板内相对坐标） =====
PANEL_W, PANEL_H = 250, 176
TITLE = (10, 10)
CELL, GAP = 40, 2
NET_MID_X0, NET_UP_Y = 8, 26
NET_UP_X = NET_MID_X0 + 3 * (CELL + GAP) // 2      # 71
NET_MID_Y = NET_UP_Y + CELL + GAP                  # 68
NET_DOWN_Y = NET_MID_Y + CELL + GAP                # 110
LEGEND_X, LEGEND_Y = 178, 34
LEGEND_SWATCH, LEGEND_STEP = 8, 14
CLOSE_X, CLOSE_Y, CLOSE_W, CLOSE_H = 178, 152, 66, 16

DOC = """# 归流缓存仓「输入面配置」子窗口 GUI 技术文档

> ## 生成方式（必读）
>
> 本背景**必须由用户的工具按本文件的精确几何 + 规定配色生成**（若交付贴图的话），脚本化输出、逐像素可控。
> **禁止 AI 手绘 / 自创画风**，禁止加入任何个人审美元素（额外花纹、渐变、噪点、阴影、发光等）。
> **背景不得烘焙任何文字**：所有文案（标题、面名、状态名、图例字）一律由 Java 用原版字体在运行时绘制。
>
> 交付贴图路径（可选）：`src/main/resources/assets/rs_create_compat/textures/gui/collection_input_face.png`
> 代码接入点：`client/screen/CollectionInputFaceConfigScreen.java`（如需自绘面板，覆写 `customPanel()` 返回该
> ResourceLocation 即可整图 `blit`，其余坐标 / 逻辑不动）。
> **在 PNG 到位前，代码使用原版九宫格精灵** **`minecraft:recipe_book/overlay_recipe`** **作为等价外观**
> （见 §2，本文件规定的边框几何与该原版精灵逐像素一致），因此**本界面不阻塞交付**。

***

## 0. 本轮（与执行仓面配置对齐）变更

| # | 变更 | 说明 |
|---|---|---|
| 1 | 面格子由 **30×30 + 0.5 缩放小字** 放大到 **40×40 + 原版字号** | 与 `ChamberFaceConfigScreen` 完全一致，格内文字直接可读、不再依赖 tooltip |
| 2 | 格内文字加 **半透明白底条**（`#55FFFFFF`） | 深色字在任何状态色上都清晰 |
| 3 | 右侧改为「**图例**（色块 + 完整状态名）」 | 与执行仓面配置同款；移除了原先底部那一长串说明文字（避免压住「关闭」按钮） |
| 4 | 顶部/底部格子 x 由 62 改为 **71** | 让 4 格中间行严格水平居中（`8 + 3×(40+2)/2`） |

***

## 1. 窗口与坐标口径

| 项                  | 值                                                                                 |
| ------------------ | --------------------------------------------------------------------------------- |
| 窗口尺寸               | **{pw} × {ph}**（`CollectionInputFaceConfigScreen.PANEL_W / PANEL_H`，与执行仓面配置子界面完全一致） |
| 本界面是否为原版 `Menu` 界面 | **否**（纯 `Screen` 子窗口，无槽位）                                                         |
| 坐标口径               | 表中 x / y 均为**面板内相对坐标**（左上角 = 面板原点）；Java 中即 `px + x`、`py + y`                      |
| 精灵 → Menu 的 +1 偏移  | **不适用**（本界面没有槽位，不需要 +1）                                                           |
| 面板在屏幕上的位置          | 水平 / 垂直居中：`px = (screenW - {pw}) / 2`、`py = (screenH - {ph}) / 2`                   |
| 交付 PNG 尺寸          | **{pw} × {ph}**（与窗口 1:1，整图 `blit`）                                                  |

***

## 2. 容器边框规格（4px 斜角，MC 原版观感）

| 层     | 位置              | 颜色        | 说明                     |
| ----- | --------------- | --------- | ---------------------- |
| 外描边   | 面板最外 1px        | `#000000` | 闭合矩形，四角做 1px 圆角（角像素透明） |
| 内斜角高光 | 上边 / 左边第 2–3 px | `#FFFFFF` | **2px**，只在**上、左**两侧    |
| 内斜角暗边 | 下边 / 右边第 2–3 px | `#555555` | **2px**，只在**下、右**两侧    |
| 面板底色  | 边框以内的全部区域       | `#C6C6C6` | 纯色，无渐变 / 无噪点           |

> 若按九宫格出图，`border = 4`，四个 `4×4` 角原样不拉伸，四边中段只做 1 维拉伸。

***

## 3. 面板分区与精确坐标表

| #  | 元素                | 左上角 (x, y)       | 尺寸 w×h        | 由谁绘制                           | 备注                 |
| -- | ----------------- | ---------------- | ------------- | ------------------------------ | ------------------ |
| 1  | 标题「归流缓存仓 · 输入面配置」 | {tx!r}         | 文本            | Java                           | `#333333`，**无阴影**  |
| 2  | 面格子：**上**（UP）     | {up!r}         | {c} × {c}    | Java `fill` + 1px `#2B2B2B` 描边 | 颜色 = 该面状态          |
| 3  | 面格子：**西**（WEST）   | {west!r}         | {c} × {c}    | 同上                             | 中间行第 1 个           |
| 4  | 面格子：**北**（NORTH）  | {north!r}         | {c} × {c}    | 同上                             | 中间行第 2 个           |
| 5  | 面格子：**东**（EAST）   | {east!r}         | {c} × {c}    | 同上                             | 中间行第 3 个           |
| 6  | 面格子：**南**（SOUTH）  | {south!r}         | {c} × {c}    | 同上                             | 中间行第 4 个           |
| 7  | 面格子：**下**（DOWN）   | {down!r}         | {c} × {c}    | 同上                             | 十字网底部              |
| 8  | 图例色块（2 个，竖排）      | {legend!r} | {sw} × {sw}      | Java `fill`                    | i = 0（可输入）/ 1（已关闭） |
| 9  | 图例文字（2 行）         | {legend_text!r} | 文本            | Java                           | `#404040`，**无阴影**  |
| 10 | 「关闭」按钮            | {close!r}       | {cw} × {ch}      | Java（原版 `Button`，自带原版贴图）       | 留白                 |

### 3.1 格子内文字（Java 运行时绘制，背景不得烘焙）

| 元素              | 位置（相对格子左上角）          | 字号     | 颜色        |
| --------------- | -------------------- | ------ | --------- |
| 面名（上/下/北/南/西/东） | 水平居中，y = +4           | 原版 8px | `#1F1F1F` |
| 状态名（可输入 / 已关闭）  | 水平居中，y = +CELL−12    | 原版 8px | `#1F1F1F` |

> 两行文字下各垫一条 **半透明白底条** `#55FFFFFF`：面名条 `(+1, +3) — (+CELL−1, +12)`，
> 状态名条 `(+1, +CELL−13) — (+CELL−1, +CELL−4)`。

### 3.2 垂直 / 水平排布校验（互不重叠）

| 区间        | 起     | 止（含）    | 下一区间起点        | 空隙 |
| --------- | ----- | ------- | ------------- | -- |
| 标题行       | y=10  | y=18    | y=26          | 7  |
| 上 面格      | y=26  | y=65    | y=68          | 2  |
| 中间行 4 面   | y=68  | y=107   | y=110         | 2  |
| 下 面格      | y=110 | y=149   | y=152（关闭按钮）   | 2  |
| 图例 2 行    | y=34  | y=56    | —（右侧独立 x 区间）  | —  |
| 关闭按钮      | y=152 | y=167   | 面板内边 173      | 6  |

| 水平区间       | 起     | 止（含）  | 下一区间起点    | 空隙 |
| ---------- | ----- | ----- | --------- | -- |
| 十字网（中间行 4 面） | x=8   | x=173 | x=178（图例） | 4  |
| 图例 / 关闭按钮  | x=178 | x=243 | 面板内边 247  | 3  |

***

## 4. 配色规范（代码 `CollectionInputFaceConfigScreen`）

| 状态       | 颜色        | 语义                    |
| -------- | --------- | --------------------- |
| 可输入（ON）  | `#4CAF50` | 该面允许漏斗 / 管道把物品与流体塞进本仓 |
| 已关闭（OFF） | `#9E9E9E` | 该面不接收输入（仍可抽出仓内资源）     |

| 用途         | 颜色          |
| ---------- | ----------- |
| 格子 1px 描边  | `#2B2B2B`   |
| 格内文字底条     | `#55FFFFFF` |
| 标题文字       | `#333333`   |
| 图例文字       | `#404040`   |
| 格子内文字      | `#1F1F1F`   |

> 与序列执行仓面配置子界面共用同一套「十字网 + 颜色即状态」的视觉语言，
> 只是本界面是**两档**（可输入 / 已关闭），执行仓是四档。

***

## 5. 留白区（背景中**不得**出现图形 / 文字，全部由 Java 运行时绘制）

| #  | 留白区    | 区域内径                    | 说明           |
| -- | ------ | ----------------------- | ------------ |
| B1 | 标题区    | (10, 10, 220, 10)       | 标题文字         |
| B2 | 六个面格子区 | (8, 26, 166, 124)       | 格子色块与格子内文字   |
| B3 | 图例区    | (178, 34, 66, 26)       | 2 个色块 + 2 行文字 |
| B4 | 按钮区    | (178, 152, 66, 16)      | 原版按钮（自带贴图）   |

***

## 6. tooltip 文案（全部手动渲染；`drawString` 一律 `false`）

| 悬停对象       | 行 1                      | 行 2        | 行 3                                | 行 4      |
| ---------- | ------------------------ | ---------- | ---------------------------------- | -------- |
| 面格子        | `...input_face.dir.<方向>` | `.current` | `.state.on.tip` / `.state.off.tip` | `.click` |
| 主界面「输入面」按钮 | `...collection_cache.input_face.tip` | `.state`（`%s` = 当前允许输入的面数） | —                                  | —        |

***

## 7. 交付 PNG 清单

| 文件                          | 尺寸          | 必需性    | 说明                         |
| --------------------------- | ----------- | ------ | -------------------------- |
| `collection_input_face.png` | {pw} × {ph} | **可选** | 不交付时自动使用原版九宫格，外观同为 MC 自带风格 |

***

## 8. 代码替换点

| 位置                                                                      | 说明                                       |
| ----------------------------------------------------------------------- | ---------------------------------------- |
| `CollectionInputFaceConfigScreen.PANEL_W / PANEL_H`                     | 面板尺寸（本文件 §1）                             |
| `CollectionInputFaceConfigScreen.customPanel()`                         | 覆写返回 PNG 的 `ResourceLocation` 即整图 `blit` |
| `CollectionInputFaceConfigScreen.cellRect(int)`                         | 六个面格子的几何（本文件 §3，绘制 / 命中共用）               |
| `CollectionInputFaceConfigScreen.COLOR_ON / COLOR_OFF`                  | 颜色表（本文件 §4）                              |
| `CollectionInputFaceConfigScreen.CLOSE_X / CLOSE_Y / CLOSE_W / CLOSE_H` | 关闭按钮几何                                   |
| `CollectionCacheScreen.INPUT_FACE_BTN_X / _Y / _W / _H`                 | 主界面入口按钮几何（Menu (160,4) 42×14，标题行内）       |

> 校验：`python tmp_textures/verify_gui_layout.py`（`InputFace` 段几何 0 问题）、
> `python tools/audit_gui_textures.py`（成品图 0 问题）。

***

## 9. 自检清单

1. 面板尺寸 = {pw} × {ph}，四角 1px 透明圆角、`#000000` 外描边、`#C6C6C6` 底；
2. 背景**不含**任何文字、色块、按钮图形（§5 留白区全部纯净）；
3. 六个面格子共 6 个 {c}×{c} 区域，行内间距 {gap}px，位置与 §3 逐一对应；
4. 图例 2 行起点 y = {ly} / {ly2}，与十字网（右缘 x=173）不重叠；
5. 关闭按钮区域不得有槽位框 / 面板凹陷；
6. 所有 tooltip 由 Java 手动渲染，背景中不得出现提示框图形；
7. 状态一律以服务端权威值为准（菜单数据槽 19），本界面不做本地持久化。
"""


def main():
    text = DOC.format(
        pw=PANEL_W, ph=PANEL_H,
        tx=TITLE,
        c=CELL, gap=GAP,
        up=(NET_UP_X, NET_UP_Y),
        down=(NET_UP_X, NET_DOWN_Y),
        west=(NET_MID_X0, NET_MID_Y),
        north=(NET_MID_X0 + (CELL + GAP), NET_MID_Y),
        east=(NET_MID_X0 + 2 * (CELL + GAP), NET_MID_Y),
        south=(NET_MID_X0 + 3 * (CELL + GAP), NET_MID_Y),
        legend=(LEGEND_X, LEGEND_Y),
        legend_text=(LEGEND_X + LEGEND_SWATCH + 4, LEGEND_Y + 1),
        sw=LEGEND_SWATCH,
        ly=LEGEND_Y, ly2=LEGEND_Y + LEGEND_STEP,
        close=(CLOSE_X, CLOSE_Y), cw=CLOSE_W, ch=CLOSE_H,
    )
    with open(OUT, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("[OK] 已生成 %s" % os.path.relpath(OUT, ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
