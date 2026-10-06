# -*- coding: utf-8 -*-
"""把本轮「为全部生存可获得的物品 / 方块补齐合成表」章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过」的方式追加，重复执行结果一致。

用法：
    python tools/append_round4_recipes_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 补齐合成表：全部生存可获得的物品 / 方块（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户需求「为所有的就是生存模式下，那些物品和方块添加对应的合成表配方，不一定全部都要有序，
> 合成也可以无序合成，参考精致存储（Refined Storage），它本身的配方设计」。
> 硬规则：**绝不能写出引用不存在物品的配方**（会让客户端 / 服务端加载报错）→ 由校验脚本拦截。

### 1. 枚举（清单 / 判定）

本模组注册总数 = **26 个物品（含 11 个 BlockItem）+ 11 个方块**（`RS_Create_Compat.java` 的
`ITEMS.register` / `BLOCKS.register`，与 `assets/rs_create_compat/lang/*.json` 的键逐条交叉核对，26 个全对得上）。

| 分类 | 数量 | 判定 | 说明 |
| --- | --- | --- | --- |
| 通用存储磁盘 1k…32M | 9 | ✅ 已有配方（15 条里的 9 条 + 升级链 5 条） | **本任务不动**（另一任务在改容量与配方） |
| 通用存储磁盘（创造档） | 1 | ⛔ 无配方 | 创造专用，同 RS `creative_storage_disk` |
| 高级远程终端（普通） | 1 | ✅ 已有配方 | — |
| 高级远程终端（满电 / 创造） | 2 | ⛔ 无配方 | 与普通版**同一个物品**的满电副本；`createAtEnergyCapacity()` 是 RS 给创造模式取用的用法，生存由充电器充能 |
| 序列单元样板 / 序列装配样板 | 2 | ⛔ 无配方 | 由「序列装配样板终端」在游戏内生成（终端产物），代码注释即写明「无合成配方」 |
| 机器方块（range_charger / quantity_keeper / schematic_loader / advanced_schematic_loader / sequence_pattern_terminal） | 5 | ✅ 已有配方 | — |
| 机器方块（advanced_quantity_keeper / collection_cache / sequence_return_bus / sequence_assembly_executor / sequence_execution_chamber / unit_pattern_manager） | 6 | 🆕 **本轮新增 6 条** | 见 §3 |
| RS 网络发送器 / 接收器（黄铜替代版） | — | ✅ 已有 2 条（产物是 RS 自己的 id） | `network_*_brass`，本任务不动 |

**结论：新增 6 条配方 / 已有 17 条（本模组产物 15 条 + RS 物品替代配方 2 条）/ 判定无配方 5 项（理由见上表）。**
校验后**未覆盖清单 = 0**：`21 个本模组产物有配方 + 5 个白名单 = 26 = 注册总数`。

### 2. 从 RS 官方配方里读到的风格（`local_src/external/RefinedStorage/refinedstorage-common/src/main/resources/data/refinedstorage/recipe/`，逐条实读）

1. **材料分层**：基础材料（`quartz_enriched_iron` / `c:silicon`）→ **处理器**
   （`basic_processor` → `improved_processor` → `advanced_processor`）→ **核心**
   （`construction_core` / `destruction_core`）→ **机器外壳**（`machine_casing`）→ 机器 / 管理器。
   处理器与核心本身也是有序配方（`raw_*_processor` + `processor_binding`）。
2. **机器方块 = 3x3 有序，模板固定**：「四角 = `quartz_enriched_iron`，中心 = `machine_casing`，
   四边 = 角色件（处理器 + 构造 / 破坏核心 + 玻璃 / 箱子）」。
   实证：`autocrafter` = `ECE/AMA/EDE`、`grid` = `PCG/EMG/PDG`、`storage_monitor` = `PCG/EMG/PDG`、
   `disk_interface` = `ESE/CMD/ESE`、`detector` = `ERE/CMC/EPE`、`disk_drive` = `ECE/EME/EPE`。
   特例：`external_storage` 用 `cable` 当中心（因为它必须贴线缆）。
3. **总线 / 线缆附属设备 = 无序三件套**：「`cable` + 一个核心 + 一个处理器」。
   实证：`importer` = `[cable, destruction_core, improved_processor]`、
   `exporter` = `[cable, construction_core, improved_processor]`、`relay` = 无序四件（含 `redstone_torch`）、
   `pattern_grid` / `crafting_grid` = 无序三件。**方向信息对这类设备无意义，所以 RS 不给形状。**
4. **管理器类 = 有序，且把「被管理的东西」写进配方**：`autocrafter_manager` = `PCG/EMG/PCG`
   （`C` = `tag: refinedstorage:autocrafters`，即你要先有被管理物）；`security_manager` 里放
   `security_card` + `fallback_security_card`。
5. **升级类 = 「基础物居中 + 部件环绕」**：`stack_upgrade` = `speed_upgrade` ×4 + 糖；
   `autocrafting_upgrade` = `upgrade` + 输出总线核心 + 工作台；`range_upgrade` = `upgrade` + 末影珍珠。
6. 标签优先用 `c:` 通用标签（`c:glass_blocks` / `c:chests` / `c:ender_pearls` / `c:silicon` …），
   跨模组兼容；只有「本模组内部枚举」才用 `refinedstorage:` 专属标签。

### 3. 本轮 6 条配方（逐条给出 RS 同位物品作为依据）

| 配方 | 有序/无序 | 形状 / 材料 | RS 同位（风格依据） |
| --- | --- | --- | --- |
| `collection_cache` 归流缓存仓 | 有序 3x3 | `QHQ/DMC/QPQ`：Q=石英富铁 ×4、H=漏斗（吸取）、D=破坏核心（从世界取走）、M=机器外壳、C=构造核心（写入网络）、P=高级处理器 | 机器方块模板（同 `detector` / `disk_drive`） |
| `advanced_quantity_keeper` 高级定量保持器 | 有序 3x3 | `QPQ/PKP/QEQ`：K=**基础版 quantity_keeper 居中**、P=高级处理器 ×3、E=Create 精密机构 | 升级类（基础物居中，同 `range_upgrade` / `stack_upgrade`） |
| `sequence_assembly_executor` 序列装配执行器 | 有序 3x3 | `QPQ/CMD/QEQ`：P=高级处理器、C=构造核心（喂料）、D=破坏核心（回收）、E=精密机构 | `autocrafter` = `ECE/AMA/EDE` 的同位物 |
| `sequence_execution_chamber` 序列执行仓 | 有序 3x3 | `QHQ/IMD/QEQ`：I=**改进处理器（比执行器低一档）**、H=漏斗、D=破坏核心、E=精密机构 | `autocrafter` 同族，处理器降档 = 分布式多台更便宜 |
| `unit_pattern_manager` 单元样板管理舱 | 有序 3x3 | `PCG/EMG/PCG`：C=**序列执行仓**（被管理物）、P=高级处理器、G=`c:glass_blocks` | `autocrafter_manager`（**逐槽照抄**，仅把被管理物换成执行仓） |
| `sequence_return_bus` 序列回流总线 | **无序** | `[cable, destruction_core, advanced_processor, create:brass_funnel]` | `importer` = `[cable, core, processor]`（总线类一律无序） |

**有序 / 无序的取舍依据**：与 RS 的**同位物品**对齐 ——
① 机器方块 / 管理器 / 升级 → **有序**（形状即结构；升级类把低级版放中心表达「由它升级而来」）；
② 线缆附属的「总线」→ **无序**（回流总线是贴在机器 / 线缆上的设备，RS 对同类 importer / exporter /
constructor / destructor / relay 全部无序，方向信息无意义）。判据本身写进了生成脚本的 `SHAPED` / `SHAPELESS` 数据表。

### 4. 生成器与校验脚本（幂等）

* `tools/gen_compat_recipes.py`：数据表驱动 + **幂等写盘**（内容一致就不动文件，保持 mtime）。
  只写自己「拥有」的 6 个文件，**绝不触碰**存储磁盘系列与其余既有配方（保护名单在脚本里）。
* `tools/verify_recipes.py`：两条硬校验 + 退出码。
  1. **id 存在性**：ingredient / 产物 id 必须落在真值池里（本模组注册名 + RS 2.0.0 官方 lang +
     Create lang + **原版 `minecraft_1.21.1_client.jar` 内的 `en_us.json`**）；标签必须落在
     「RS / 原版 / 本模组既有配方里出现过」的集合里（证明能解析）。本轮真值池：
     RS 171 / Create 956 / 原版 2385 / 本模组 26 个 id，允许标签 35 个。
  2. **覆盖完整性**：本模组每个注册物要么有配方，要么在 `no_recipe` 白名单里（每条带理由）；
     同时反向核对「白名单里的 id 若已有配方 → 报错（理由过期）」。
  3. 额外自检有序配方的 3x3 网格、`key` 与图案字符一一对应（多余 / 缺失都报错）。
  **自测记录**：临时塞入一条引用 `refinedstorage:not_a_real_item` /
  `minecraft:also_not_real` / 产物 `rs_create_compat:no_such_block` 的假配方 →
  脚本 3 条 FAIL + 退出码 1（拦得住）；删除后恢复 0 问题。
  **本轮结果**：`id 全部存在，本模组注册物 100% 覆盖（未覆盖 0）`。

### 5. 本轮新增 / 修改文件

新增：`tools/gen_compat_recipes.py`、`tools/verify_recipes.py`、
`src/main/resources/data/rs_create_compat/recipe/` 下 6 个新配方
（`collection_cache` / `advanced_quantity_keeper` / `sequence_assembly_executor` /
`sequence_execution_chamber` / `unit_pattern_manager` / `sequence_return_bus`）、本追加脚本。

**刻意未动**：存储磁盘系列（含最大级，容量与配方归另一任务）、NBT 标记 / 回流总线 / 装填器 /
执行舱链 / 单元样板管理舱 / 扳手的**代码**、所有既有配方文件、任何 Java 代码与贴图 / 布局 / 语言键。
本轮只新增数据（配方 JSON）与工具脚本 —— 因此不需要语言键，也就没有走 `lang_frag` 流程。
"""


def main():
    with open(DOC, "r", encoding="utf-8") as handle:
        text = handle.read()
    if MARK in text:
        print("[跳过] 章节已存在: %s" % MARK)
        return 0
    with open(DOC, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(SECTION)
    print("[追加] %s（+%d 字符）" % (os.path.relpath(DOC, ROOT), len(SECTION)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
