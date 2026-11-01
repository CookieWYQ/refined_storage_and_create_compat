# 第 123 轮：降解室发电机重做（契约文档）

> 本文件是**唯一权威契约**。任何实现与它冲突，以本文件为准；要改先改这里。
> 几何 / 下标的数字只在 `support/DecompositionGeneratorSpec.java` 里写一遍，本文件只做说明与引用。

## 0. 用户原话（需求来源，逐条可追溯）

1. 「不可降解的**无法输入**」（不是状态栏提示）—— 拒收，连放都放不进去。
2. 「确保资源包（数据包）可以添加配方」—— 表仍然是数据包驱动，格式要写清楚。
3. 「只做原版；只要是植物、且不是枯萎的（字面意义上生物学可降解的，比如树叶、小麦），看一下有没有这种 tag，有就直接用上」。
4. 「能量产出按种类分档，但别定太低，定个中间值：**消耗 1 个甘蔗耗时 20 tick，产生 50 FE，得到 1 个糖**」。
5. 「耗时也可以配置」（数据包字段）。
6. 「可以加装**堆叠升级**和**速度升级**：速度升级增加降解速度但**不改变发电量**」。
7. 界面（上一轮已确认的布局）：**左 16 格输入 / 中间熔炉式箭头 / 右 4 格副产物 / 只有一条能量条**。

## 1. 一句话设计

16 格输入 → 一次降解 1 件（熔炉式箭头走进度）→ 产出**副产物**进 4 格输出 + **发电量**进本机缓存；
升级槽 6 格只收速度 / 堆叠；界面用 `tools/make_gui_bg.py` 生成的九宫格背景 + **原版熔炉箭头精灵**，
不再自绘面板。

## 2. 库存布局

| 下标 | 语义 | 上限 |
| --- | --- | --- |
| `0..15` | 输入（4×4） | `DecompositionGeneratorSpec.inputSlotLimit(stackUpgrades)`（基础 64） |
| `16..19` | 副产物输出（2×2） | 64（**只出不进**：`isItemValid` 恒 false） |
| — | 升级（6，单独容器） | 1 个/格，白名单 `speed_upgrade` / `stack_upgrade` |

- 物品能力 `Capabilities.ItemHandler.BLOCK` 继续把**整个 20 格库存**交出去（可进可出，过滤在
  `isItemValid`）：输入格只收 `DecompositionTable.isDecomposable(stack)` 为真的物品，
  **不可降解物在能力层就被拒**（漏斗 / 输出总线 / 管道统统塞不进来）。
- 升级容器：直接复用 `support/RsccSpeedStackUpgradeContainer`（6 槽、白名单、超限弹出、读档校验），
  不要自己再写一份。
- 显示槽（旧「当前物品」1 格）**删除**：16 格全部可见，不再需要镜像展示槽。

## 3. 数据槽（ContainerData，`DATA_SLOT_COUNT = 14`）

| # | 含义 |
| --- | --- |
| 0 / 1 | 本机缓存电量 / 容量（FE） |
| 2 / 3 | 当前进度 tick / 当前这一件实际需要 tick（= `effectiveTime`，0 = 空闲） |
| 4 | 当前处理中的输入格下标（0..15，-1 = 空闲） |
| 5 | 状态位：bit0 `bufferFull`、bit1 `working`、bit2 `network`、bit3 `outputFull` |
| 6 / 7 | 速度升级个数 / 堆叠升级个数 |
| 8 | 上一次完成时的发电量（FE） |
| 9 / 10 | 相邻推送上限（服务端 Config 下发）/ 上一 tick 实际推送 |
| 11 | 非空输入格数 |
| 12 / 13 | `INPUT_COUNT`(16) / `OUTPUT_COUNT`(4) |

## 4. 降解循环（每一 tick，服务端）

```
① 从 scanCursor 起环形扫 16 格输入，取第一格「非空且可降解」
② 有效耗时 T = DecompositionGeneratorSpec.effectiveTime(profile.time(), speedUpgrades)
   进度 progress++，progress >= T 时结算
③ 结算前先判三件事，任何一件不满足就「不推进、不消耗物品」：
   a. 缓存剩余 >= 本次发电量      （否则 bufferFull）
   b. 4 个输出格的剩余空间能放下本条 results 的全部产物（否则 outputFull）
④ 结算：缓存 += energy（单件固定，不随速度升级变）；输入格 -1；
   产物写进输出格（按 results 顺序，能堆的堆一起）；进度清零；游标后移
```

- **绝不吞物品**：③ 不满足就原地等（用户第 118 轮定下的硬规则）。
- **绝不产不出就消费**：④ 是「先写缓存、再产出、最后扣物品」，中途抛出异常也不会重复结算。
- 产物投放要**先在副本上模拟**（`ItemStackHandler.insertItem(..., true)`）算清能不能全放下。

## 5. 副产物数据格式（数据包）

目录：`data/<namespace>/decomposition/*.json`（原样保留，**这就是「资源包/数据包可以加配方」的入口**）。

```json
{
  "replace": false,
  "mod_loaded": "farmersdelight",
  "remove": ["minecraft:dead_bush", "#minecraft:planks"],
  "entries": [
    {
      "tag": "minecraft:leaves",
      "time": 10,
      "energy": 25,
      "results": [
        { "item": "minecraft:stick",  "count": 1 },
        { "item": "minecraft:oak_sapling", "count": 1, "chance": 0.05 }
      ]
    },
    { "item": "minecraft:sugar_cane", "time": 20, "energy": 50,
      "results": [ { "item": "minecraft:sugar", "count": 1 } ] }
  ]
}
```

> 可直接抄的完整示范：`docs/decomposition_example.json`（含 `mod_loaded` 门、`results`、`chance`、tag 条目）。

字段语义（**新增的只有 `results` 与 `chance`**，其余一律沿用第 101 轮的既有语义：

- `item` / `tag` 二选一；`time` / `energy` 必填（缺失 = 该条作废 + 计入错误数）。
- `results`：可省略；是**独立掷骰列表**，每一项按 `chance`（默认 1.0，夹到 `[0,1]`）各自判定，
  命中就产出 `count` 个 `item`。空列表 = 只有电没有副产物。
- `chance` 用**服务端 `level.random`** 掷，结果不影响任何记账。
- `result`（单对象）作为 `results` 只有一项的语法糖，二选一，不允许同时出现。
- 覆盖顺序、`replace`、`remove`、`mod_loaded` 门、懒匹配 tag、错误容错 —— **全部沿用现有实现**，
  一个字都不改口径。

## 6. 平衡表（内置表给这套值，数据包可随意覆盖）

分 4 档，**甘蔗 = 20 tick / 50 FE / 1 个糖** 是用户钉的中位锚点。

| 档 | time | energy | 典型物品 | 副产物 |
| --- | --- | --- | --- | --- |
| T1 小件 | 10 | 25 | 树叶、花、草、蕨、藤蔓、睡莲、海草、海带、苔藓地毯、小型垂滴叶、浆果、海泡菜、种子 | 骨粉 ×1（chance 0.25）/ 树叶→木棍 ×1 |
| T2 中件 | 20 | 50 | **甘蔗**、仙人掌、竹子、蘑菇、树苗、小麦、胡萝卜、土豆、甜菜根、可可豆、下界疣、下界菌类 | 甘蔗→糖 ×1；小麦→小麦种子 ×1；其余骨粉 ×1（chance 0.5） |
| T3 大件 | 40 | 100 | 南瓜、西瓜、雕刻南瓜、蘑菇块、海带块、苔藓块、大型垂滴叶、瓶子草、火把花、紫颂花/植株 | 西瓜→西瓜片 ×3；南瓜→南瓜籽 ×2；蘑菇块→蘑菇 ×2；紫颂花→紫颂果 ×1；其余骨粉 ×2 |
| T4 压缩块 | 180 | 450 | 干草块 | 骨粉 ×9 |

**硬约束（自检会盯）**：

1. **`minecraft:dead_bush` 必须不在表里**（用户点名「枯萎的」不算）。
2. **任何条目的副产物都不许把压缩块的原料按 1:1 退回来**（否则「合成 → 降解 → 再合成」= 无限电）。
   - 干草块 → 骨粉 ×9（**不是** 9 个小麦）
   - 海带块 → 骨粉 ×9（**不是** 9 个干海带）
3. 所有 `item` / `tag` id **必须在 1.21.1 真实存在**（用 MC jar 里的
   `data/minecraft/tags/item/*.json` 与注册表核对，不许凭记忆写）。
4. 每个条目的 `time >= 1`、`energy >= 1`。

## 7. 界面（`client/screen/DecompositionGeneratorScreen`）

- 背景：`assets/rs_create_compat/textures/gui/decomposition_generator.png`，由
  `tools/make_gui_bg.py` 生成（`--bg pic/bg.png --slot pic/slot.png`），**不许再用 `fill` 画面板**。
- 槽位网格参数（必须与 `DecompositionGeneratorSpec` 逐像素一致）：
  输入 `8,18,4,4`、副产物 `118,37,2,2`、升级 `118,76,3,2`、主背包 `8,128,9,3`、快捷栏 `8,188,9,1`。
- 箭头：**原版熔炉精灵**，不新增贴图：
  ```java
  // 底图（空箭头）来自 furnace.png 的 (79,34)，亮起部分来自 (176,14)，宽按进度截取
  guiGraphics.blit(FURNACE_LOCATION, ax, ay, 79, 34, 24, 16, 256, 256);
  guiGraphics.blit(FURNACE_LOCATION, ax, ay, 176, 14, filledWidth, 16, 256, 256);
  ```
  `filledWidth = 1 + round(fraction * 23)`（原版 `getBurnProgress()+1` 口径，进度 0 时也画 1px）。
- 能量条：继续用 `RsccEnergyBar`（RS 原版 `ProgressWidget` + 同款黑框），
  **整个界面只允许有这一条「条」**（用户投诉的「两个条」= 旧的竖直进度条 + 能量条；
  进度条本轮被箭头取代，必须彻底删掉）。
- 文字：3 行（`TEXT_X/TEXT_Y/TEXT_LINE_STEP/TEXT_MAX_WIDTH`），一律走
  `RsccTextFit.drawFitLine`（放不下缩字号，**不补省略号**，`selfcheck_no_ellipsis.py` 会盯）。
  内容固定为：
  1. 状态：`working` / `idle` / `buffer_full` / `output_full`（优先级按此倒序）
  2. 缓存：`<stored> / <capacity> FE`
  3. 升级：`速度 ×N · 堆叠 ×N · 上次 <FE>/次`
- 进度数字（`12 / 20 刻`）画在箭头上方，`ARROW_Y - 11`，箭头 tooltip 同内容。
- 手上拿着不可降解物时：**不再需要红字提示**（放不进去是槽位自己的事），
  但 tooltip 仍然保留：把 `reject` 句继续挂在 `PagedSlot`→`InputSlot` 的 `mayPlace` 上没必要，
  **改为**在箭头上方的状态行显示一次即可（不强制）。

## 8. 存档迁移（**必须做，否则用户实机丢东西**）

旧存档（第 101–122 轮）的 `Inventory` 是 256 格；`ItemStackHandler.deserializeNBT` 对
`slot >= size` 的下标会**静默丢弃**。因此 `loadAdditional` 必须：

1. 读 `tag.getCompound("Inventory")`，若其 `Size` > `INVENTORY_SIZE`（或 `Items` 里最大下标 ≥ 20）
   ⇒ 先按 **旧尺寸**构造临时 `ItemStackHandler(旧Size)` 反序列化；
2. 逐格搬运到新库存：可降解物→优先输入格（遵守堆叠上限），其余→输出格（**先不判可降解**），
   仍然放不下的塞进 `pendingDump` 列表；
3. `pendingDump` 在**首次服务端 tick** 掉到方块位置（`Block.popResource`），绝不销毁、不落盘丢失；
4. 新格式（`Size == 20`）照常直读。

同样地，旧 NBT 里的 `Progress` / `ProgressTotal` / `CurrentSlot` 等字段语义保持兼容：
`currentSlot` 是旧 256 格下标 ⇒ 读档后一律 `abortCurrent()` + 清进度（物品仍在库存里）。

## 9. 分工与写权限（Lead 裁定，**只许改自己那一列的文件**）

| 成员 | 只许改 | 交付 |
| --- | --- | --- |
| `decomp-data` | `support/DecompositionProfile.java`、`support/DecompositionTable.java`、`support/DecompositionDataLoader.java`、`data/rs_create_compat/decomposition/*.json` | 第 5、6 节 |
| `decomp-machine` | `block/entity/DecompositionGeneratorBlockEntity.java`、`block/DecompositionGeneratorBlock.java` | 第 2、3、4、8 节 |
| `decomp-gui` | `menu/DecompositionGeneratorMenu.java`、`client/screen/DecompositionGeneratorScreen.java`、`textures/gui/decomposition_generator.png` | 第 2、3、7 节 |
| Lead | `Config.java`、`RegisterUpgradeDestinations.java`、`lang/*.json`、`CHANGELOG.md`、`docs/*`、`tools/selfcheck_*.py` | 集成 / 编译 / 自检 / 部署 |

**共同红线**：

- `DecompositionGeneratorSpec.java` 由 Lead 写好，**任何人不得修改**；缺字段就找 Lead 加。
- 不许跑 `gradlew` / `javac`（Lead 统一编译，避免并发锁与互相覆盖）。可以跑 `tools/selfcheck_*.py`。
- 不许改 `docs/ROUND122_USER_FEEDBACK.md`、`CHANGELOG.md`（Lead 统一写）。
- 不许删别人的注释块（本仓的注释是「为什么这么写」的唯一记录）。

## 10. 验收（Lead 会逐条查）

1. `javac` 全绿（`tools/_lead_compile.ps1` 打印 `LEAD_COMPILE_OK`）。
2. `python tools/_run_all_selfchecks.py` 全绿（含本轮新写/改写的断言）。
3. 静态自检：`grep` 不到旧的 256 格 / 分页 / 竖直进度条残留（`PAGE_SIZE`、`PAGE_COUNT`、
   `BIT_`、`drawVerticalBar`、`DisplaySlot`）。
4. 数据包：`dead_bush` 不在表里；所有 id / tag 在 MC 1.21.1 真实存在；`results` 解析容错
   （坏 id / 坏 count / chance 越界都只跳过自己）。
5. 存档迁移：假想 256 格旧 NBT → 物品种类与总数守恒（自检里写成纯函数断言）。
6. 界面：贴图存在且尺寸 = `PANEL_WIDTH × PANEL_HEIGHT`；槽位坐标与贴图网格逐像素一致；
   整个 Screen 只有 1 条 `RsccEnergyBar`、0 条自绘竖条。
