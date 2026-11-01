# 第 111 轮：能耗监视界面「一片空白、只有 tooltip」的渲染顺序回归

## 1. 用户反馈（截图）

打开高级远程多功能终端的「能耗监视」页：整块面板里**什么都不渲染**，但鼠标悬停能弹出行 tooltip
（「输出总线 · 主世界 (391,110,99) · 当前 30 FE/t | 标称 30 FE/t」），底部按钮（返回终端 / 刷新 /
清除高亮）与右侧滚动条也都在。用户原话：

> 「这他妈什么鬼，怎么啥也不渲染，但是我觉得里面的内容是有的。你看那个 tooltip，
>  但是他妈啥都没有是什么意思？」

## 2. 根因：内容画在了面板背景**之前**

`EnergyAuditScreen extends ChildConfigScreen`，而 `ChildConfigScreen#renderBackground` 会
`blitSprite` 一整块**不透明**的原版九宫格子面板（`minecraft:recipe_book/overlay_recipe`）。
`Screen#render`（= `super.render`）的顺序是「renderBackground → 控件」。

旧实现的 `render()`：

```java
renderContent(guiGraphics, mouseX, mouseY);              // ① 标题 / 摘要 / 列表
super.render(guiGraphics, mouseX, mouseY, partialTick);  // ② 背景（不透明，盖住①）+ 控件
renderTooltips(guiGraphics, mouseX, mouseY);             // ③ tooltip
```

于是：标题 / 摘要 / 列表被整块面板盖住；按钮、滚动条画在 ② 之后、tooltip 画在 ③ 之后 ⇒
**只有控件和 tooltip 可见** —— 与截图逐项对应。

## 3. 修法：重排渲染顺序 + 重画滚动条

```java
super.render(guiGraphics, mouseX, mouseY, partialTick);       // 背景 + 控件
renderContent(guiGraphics, mouseX, mouseY);                   // 内容画在背景之上
if (scrollbar != null) {
    scrollbar.render(guiGraphics, mouseX, mouseY, partialTick); // 内容会盖住列表右缘的滚动条 ⇒ 重画
}
renderTooltips(guiGraphics, mouseX, mouseY);                  // tooltip 永远最上层
```

选择「在内容之后重画滚动条」而不是把滚动条移出内容区：列表底 / 行背景会画到 `SCROLL_X` 附近，
重画一次才能保持「滚动条压住列表右缘」的既有观感，且不改任何坐标常量。

## 4. 同类回归扫描

`tools/selfcheck_round111_energy_audit_render_order.py` 会扫描 `client/screen` 下**所有**界面：
任何「`renderContent` 出现在 `super.render` 之前」的 `render()` 都会被判负。当前只有
`EnergyAuditScreen` 一处，现已修复；其余 29 个界面均为正确顺序。

## 5. 自检

`tools/selfcheck_round111_energy_audit_render_order.py`（9 断言 / 5 反例）：

- ① 四步顺序严格正确（`super.render` < `renderContent` < 滚动条重画 < `renderTooltips`）；
- ② 全工程扫描无同类回归；反例：把顺序换回旧写法后扫描器能重新抓到；
- ③ 行为模型：把子面板当不透明覆盖层，旧顺序 ⇒ 内容不可见、新顺序 ⇒ 可见；
  反例：把面板当透明、或把内容放在面板之后却判不可见，都必须判负。

## 6. 验收（beta.13）

- 编译：`LEAD_COMPILE_OK sources=495`
- 全量自检：**157 个脚本 / 0 失败**
- 专服探针：见 CHANGELOG（PASS）
- 部署：本地实例 / SFTP 服务端 / SFTP 客户端三处 sha256 一致

## 7. 未实机验证

渲染顺序由源码断言 + 「不透明覆盖」行为模型证明，**没有**在游戏里打开该界面截图复核；
但本次只改了绘制顺序与一次滚动条重画，不触碰数据 / 交互逻辑，回归面极小。
