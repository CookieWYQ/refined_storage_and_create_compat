# 第 107 轮：充电过滤列表的显示口径（只显示物品名 · id 只进 tooltip · 搜索支持 id）

用户原话（第 107 条反馈）：

> 你就直接显示它这个物品名字就可以了，它这个 id 的话呢你 tooltip 中加入就可以了，
> 或者说你根本就不需要加入他的物品 id，你显示他的中文名就可以了，但是要支持搜索 id。

## 改前是什么样

`ChargerFilterScreen` 的列表行（掉落物 / 玩家身上物品两页）本来就是：

```
[勾选框] [16×16 图标] [本地化物品名]            [灰色后缀 = 注册 id 的 path]
```

名字确实是本地化名（`ItemStack#getHoverName().getString()`），但行尾那串灰色小字是
`ResourceLocation#getPath()`（例如 `energy_tablet`）—— 玩家在界面上看到的「id」就是它：
它既占掉了名字能用的像素（名字放不下就整体缩字号），又不是玩家用来认东西的信息。

## 改后是什么样

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| 行内名字 | 本地化物品名 | **不变**（本地化物品名，`getHoverName()`） |
| 行尾灰色小字 | 注册 id 的 path | **物品行不再显示**（玩家行仍是「在线 / 离线 / 手动添加」） |
| 名字可用宽度 | 被 id 后缀挤占 | 放开到滚动条左缘（少一次缩字号） |
| 完整注册 id | 行内后缀 + tooltip 都有 | **只在行 tooltip**（`row.id` =「物品 ID：%s」） |
| 搜索 | 物品名子串 或 注册 id 子串（不区分大小写） | **不变**（本来就有，这次写进 tooltip 文案让玩家知道） |
| 搜索框 tooltip | 「按物品名或注册 ID 过滤（不区分大小写）」 | 「按物品名或物品 ID 搜索（不区分大小写）；列表只显示物品名，完整 ID 见每行的悬浮提示」 |

「界面上只认名字、要找 id 就打字搜」这两件事互不冲突：显示口径与搜索口径是分开的。

## 实现落点

- `client/screen/ChargerFilterScreen.java`
  - `suffixOf(Row)`：物品分支 `return "";`（方法注释写清为什么）。
  - `drawList(...)`：仍按 `suffixW / suffixX / nameMax` 三行反推名字可用像素（名字与后缀
    在玩家行仍互不重叠），空后缀走 `if (!suffix.isEmpty())` 不绘制。
  - `renderRowTooltip(...)`：物品行 tooltip 仍是「名字 → 物品 ID：<registry id> → 操作说明」。
- `client/screen/ChargerFilterCandidates.java`：**没改**（名字来源 `getHoverName()`、
  `search` 双分支匹配名字与 id 本来就在；这次只是把口径钉进自检）。
- `lang/zh_cn.json` / `lang/en_us.json`：`gui.rs_create_compat.charger_filter.search.tip` 文案更新
  （`search.hint`「搜索物品名 / ID」保留）。

## 验收

`python tools/selfcheck_round107_charger_filter_names.py`（7 条断言 + 6 条反例）：

① 名字来自 `getHoverName()`（不是 id）；② `suffixOf` 物品分支返回空串、方法体内无 `getPath()`；
③ 名字宽度仍按后缀实宽反推、空后缀不绘制、玩家在线色不变；④ 物品行 tooltip 仍给完整 id；
⑤ `search` 双分支 + 小写比较，并配纯 Python 行为模型（中文名 / id / 命名空间前缀 / 大小写 / 无命中）；
⑥ 语言键 `row.id`（带 `%s`）与 `search.tip` 的 zh/en 成对；⑦ 反例自证 6 条各自判负。

跑法：`python tools/selfcheck_round107_charger_filter_names.py`（退出码 0 = 通过）。

## 未验证 / 已知限制

- 界面观感（名字放开后是否更易读、长名字缩字号的实际效果）**未实机验证**，只能靠代码级几何断言。
- 物品名是「客户端当前语言」的翻译：某个模组没有中文语言文件时，Minecraft 自己会显示英文名
  （个别缺失翻译的物品会显示成 `item.modid.xxx` 这种键名）—— 这不是本模组能替它翻译的，
  但搜索可以打它的注册 id 直接找到。
