# -*- coding: utf-8 -*-
"""生成本轮交付文档：tmp_textures/REDSTONE_AND_TEXTURE_ROUND5.md（幂等：每次覆盖重写）。

内容：① RS 原版红石模式的实现要点；② 本模组接入方式与逐机器接入清单（含未接入项与原因）；
③ 模式持久化与「条件不满足即停机、不销毁资源」；④ 保持器贴图拆面与高级版差异化；
⑤ 共用贴图审计结论；⑥ 定量保持器文字颜色修正。

用法：python tools/gen_round5_redstone_texture_doc.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tmp_textures", "REDSTONE_AND_TEXTURE_ROUND5.md")

DOC = """# Round 5：机器红石模式 / 保持器贴图 / 共用贴图审计

> 本文由 `tools/gen_round5_redstone_texture_doc.py` 生成（可重复执行，覆盖重写）。

## 1. RS 原版「红石模式」的真实实现要点（照抄，不自己发明）

| 环节 | RS 原版实现 |
| --- | --- |
| 枚举 | `com.refinedmods.refinedstorage.common.support.RedstoneMode`：`IGNORE` / `HIGH` / `LOW`；`isActive(boolean powered)`、`toggle()`（忽略 → 高电平 → 低电平） |
| 编码 | `com.refinedmods.refinedstorage.common.support.RedstoneModeSettings`：枚举 ↔ `0/1/2` |
| 存储字段 | `common.support.network.AbstractBaseNetworkNodeContainerBlockEntity#redstoneMode`（默认 `IGNORE`） |
| 持久化 | 同基类 `writeConfiguration/readConfiguration`，NBT 键 `rm`；由 `saveAdditional/loadAdditional` 调用 → 读档保留 |
| 生效判定 | 同基类 `calculateActive()`：`redstoneModeActive = !hasRedstoneMode() \\|\\| redstoneMode == IGNORE \\|\\| redstoneMode.isActive(level.hasNeighborSignal(pos))`，再与「已接入网络 + 能量充足」相与 |
| 驱动时机 | `common.support.network.NetworkNodeBlockEntityTicker#tick` → `updateActiveness(state, activenessProperty)` → 节点 `setActive(...)` |
| 停机语义 | 节点 `active=false` → 各机器 `doWork()` 开头 `return`（只停止作业，**不动任何资源**） |
| 界面控件 | `common.support.widget.RedstoneModeSideButtonWidget`（继承 `AbstractSideButtonWidget`）+ 菜单属性 `PropertyTypes.REDSTONE_MODE` / `ClientProperty<RedstoneMode>`；贴图 `widget/side_button/redstone_mode/{ignore,high,low}`，文案 `gui.refinedstorage.redstone_mode*` |

## 2. 本模组接入方式（复用 RS 的控件与语义）

代码位置：

* `support/RsccRedstoneMode` —— 按钮 id（`900`）+ 语义说明；模式编解码仍走 RS 的 `RedstoneModeSettings`。
* `support/RsccRedstoneModeHolder` —— 菜单侧读取接口 `rscc$getRedstoneMode()`。
* `client/widget/RsccRedstoneModeButton` —— **直接继承 RS 原版控件**（贴图 / 文案 / tooltip 全部复用 RS）。
  唯一差异：本模组界面基于原版 `AbstractContainerScreen`（不是 RS 的 `AbstractBaseScreen`），
  父类的 `setDeferredTooltip` 那一步不会生效，因此在 hover 时用
  `Platform.INSTANCE.renderTooltip(graphics, buildTooltip(), mouseX, mouseY)` **手动渲染同一份 tooltip**
  （hover 判定与绘制范围同源 = 控件自身命中范围，同一格只渲染一份）。
* 切换通道：原版 `MultiPlayerGameMode#handleInventoryButtonClick(containerId, 900)`
  → 菜单 `clickMenuButton` → `blockEntity.setRedstoneMode(getRedstoneMode().toggle())`（**服务端权威**）。
  本模组菜单继承原版 `AbstractContainerMenu`，RS 的 `PropertyChangePacket` 会被 `instanceof AbstractBaseContainerMenu` 拦掉，故不复用该包。
* 显示同步：各机器既有的 `ContainerData` 数据槽新增一格存放 `0/1/2`；
  界面每 tick 用 `property.set(ordinal)` 刷新控件显示。

### 接入清单

| 机器 | 方块实体 | 数据槽索引 | 菜单 | 界面 | 停机点 |
| --- | --- | --- | --- | --- | --- |
| 定量物品保持器 | `QuantityKeeperBlockEntity` | 8 | `QuantityKeeperMenu` | `QuantityKeeperScreen` | `QuantityKeeperNetworkNode#doWork` 的 `isActive()` |
| 高级定量物品保持器 | `AdvancedQuantityKeeperBlockEntity` | `DATA_REDSTONE_MODE`（全局槽） | `AdvancedQuantityKeeperMenu` | `AdvancedQuantityKeeperScreen` | `AdvancedQuantityKeeperNetworkNode#doWork` 的 `isActive()` |
| 范围充电器 | `RangeChargerBlockEntity` | 9 | `RangeChargerMenu` | `RangeChargerScreen` | `RangeChargerBlockEntity#doWork` 增加 `mainNetworkNode.isActive()` 闸门 |
| 装填器 | `SchematicLoaderBlockEntity` | 4 | `SchematicLoaderMenu` | `SchematicLoaderScreen` | `SchematicLoaderNetworkNode#doWork` 增加 `isActive()` 闸门 |
| 高级装填器 | `AdvancedSchematicLoaderBlockEntity`（继承装填器） | 5 | `AdvancedSchematicLoaderMenu` | `AdvancedSchematicLoaderScreen` | 同上（同一节点） |
| 归流缓存仓 | `CollectionCacheBlockEntity` | 16 | `CollectionCacheMenu` | `CollectionCacheScreen` | `CollectionCacheNetworkNode#doWork` 的 `isActive()` |
| 序列装配执行器 | `SequenceAssemblyExecutorBlockEntity` | 0（新建 `getContainerData`） | `SequenceAssemblyExecutorMenu` | `SequenceAssemblyExecutorScreen` | `PatternProviderNetworkNode#doWork` 的 `isActive()` |

未接入（**原因：对应文件由其它三路并行修改，本任务硬规则禁止触碰**；它们仍保持 `hasRedstoneMode() == false` 的既有行为）：

| 机器 | 原因 |
| --- | --- |
| 序列样板终端（SPT） | `SequencePatternTerminalBlockEntity` 正在被并行改造（本轮编译错误即源于该分支未完成） |
| 序列装配回流总线 | `SequenceReturnBusBlockEntity` 属「回流总线」路线 |
| 序列执行舱 | `SequenceExecutionChamberBlockEntity` 属「执行舱/模型」路线 |
| 单元样板管理器 | `UnitPatternManagerBlockEntity` 属「单元样板/管理器」路线 |
| 蓝图加农炮装填器的扳手交互 | 属「扳手」路线 |

## 3. 持久化与「红石条件不满足即停机（不销毁资源）」

* **持久化**：7 台机器的方块实体都继承 RS 的 `AbstractBaseNetworkNodeContainerBlockEntity`，
  其 `saveAdditional` 会调用 `writeConfiguration`（写 NBT 键 `rm`），`loadAdditional` 会调用
  `readConfiguration`（读回 `rm`）。因此模式**无需额外代码**即可随存档保留。
* **服务端权威**：模式只在服务端修改（`clickMenuButton`）；客户端拿到的是数据槽回传的副本，
  点击只是发起一次请求。
* **停机**：红石条件不满足 → `calculateActive()` 为 false → 节点 `active=false`
  → 每台机器的作业入口 `doWork()` 在最前面 `return`：
  - 定量保持器：不再触发自动合成、不再销毁过量（`storage.extract` 不会执行）；
  - 范围充电器：不再扫描充电（仅停止，不回收已充进去的能量）；
  - 装填器：不再补货 / 不再推进队列（蓝图与库存物品原样保留）；
  - 归流缓存仓：不再吸取与入网；
  - 序列装配执行器：`tasks.step` 不执行，任务留在网络上。

## 4. 定量物品保持器贴图（基础 / 高级）

* 现状问题：`advanced_quantity_keeper[_active]` 的模型直接引用 `quantity_keeper_inactive/active`
  → **基础版与高级版完全同图**；且旧图是 `cube_all`（六个面共用一张 PNG）。
* 修正（`tools/gen_keeper_faces.py`，幂等）：
  - 基础版：`quantity_keeper_side[_active]`（四个竖直侧面共用原图）+ `quantity_keeper_top[_active]`
    + `quantity_keeper_bottom[_active]`（顶为螺钉 + 指示灯条，底为散热格栅；与其它机器同一套模板）；
  - 高级版：`advanced_quantity_keeper_side[_active]`（原图强调色**色相位移到品红** +
    四角铆钉 + 内侧强调色包边，一眼可辨「加固的高级版」）+ 同款顶 / 底模板（品红强调色）；
  - 4 个模型改写为 `minecraft:block/cube`，六个面显式指到各自贴图；
  - 删除已无引用的旧图 `quantity_keeper_inactive.png` / `quantity_keeper_active.png`。

## 5. 共用贴图审计

`python tools/audit_shared_textures.py`（解析模型 → 展开「面 → PNG」，判定一图多用）：

* 一图多用：**0 项**；字节级重复：**0 组**；引用缺失：**0 项**；退出码 `0`；
* 豁免：同一台机器的四个竖直侧面（`*_side`）刻意共用（用户明确允许）；
* 排除（其它路线在改，不计入）：`sequence_pattern_terminal_*`、`sequence_return_bus_*`；
* 已修正的共用：
  - `advanced_quantity_keeper_*` 不再引用 `quantity_keeper_*`（本轮）；
  - 原先 `collection_cache_*` 与 `item_collector_*` 字节完全相同 → `item_collector_*` 为无引用残留，
    已随拆面一并消失；
  - 单元样板管理器的「背面 = 侧面」已改为六个面各自独立（`unit_pattern_manager_{front,back,left,right,top,bottom}`）。

## 6. 定量保持器界面文字颜色

浅色背景上白字不可读，故统一为工程既有深色常量（与高级版界面同一套取值，`shadow = false` 不变）：

| 文案 | 键 | 旧色 | 新色 |
| --- | --- | --- | --- |
| 标题（方块名） | `block.rs_create_compat.quantity_keeper` | `0xFFFFFF` | `0xFF333333` |
| 目标数量标签 | `gui.rs_create_compat.quantity_keeper.target_label` | `0xFFFFFF` | `0xFF404040` |
| 目标数量单位 | `...quantity_keeper.unit_item` / `unit_mb` | `0xFFFFFF` | `0xFF404040` |
| 销毁过量标签 | `...quantity_keeper.destroy_label` | `0xFFFFFF` | `0xFF404040` |
| 自动合成标签 | `...quantity_keeper.autocraft_label` | `0xFFFFFF` | `0xFF404040` |

「堵塞」提示保持红色 `0xFF5555`（语义色，非标题）。高级版界面（`AdvancedQuantityKeeperScreen`）
原本就用 `COLOR_TITLE = 0xFF333333` / `COLOR_TEXT = 0xFF404040`，无需改动。

## 7. 界面控件位置（GUI 文档同步）

红石模式按钮是**运行时控件**（复用 RS 原版侧边按钮），不进入背景 PNG，也不改变任何槽位几何，
因此 `tools/audit_gui_textures.py` 与 `tmp_textures/verify_gui_layout.py` 的布局表**无需变动**。
按钮位置：面板左侧外缘顶端 —— `x = leftPos - Button.SIZE - 2`，`y = topPos + 6`（与 RS 原版 `getSideButtonX/Y` 同口径）。
"""


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(DOC)
    print("wrote", os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
