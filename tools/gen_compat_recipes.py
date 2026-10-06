# -*- coding: utf-8 -*-
"""为「本模组生存可获得的方块」补齐合成表（风格对齐精致存储 Refined Storage）。

背景 / 决策依据（详见 docs/DESIGN_DECISIONS_ROUND4.md 同名章节）：
- 逐条读过 RS 官方配方 JSON（`local_src/external/RefinedStorage/refinedstorage-common/
  src/main/resources/data/refinedstorage/recipe/`），归纳出的风格：
    1) 材料分层：基础材料（quartz_enriched_iron / 硅）→ 处理器（basic/improved/advanced）
       → 核心（construction_core / destruction_core）→ 机器（machine_casing 为壳）。
    2) 机器方块 = 3x3 有序：「四角 = quartz_enriched_iron，中心 = machine_casing，
       四边 = 角色件（处理器 + 构造/破坏核心 + 玻璃等）」。
       例：autocrafter `ECE/AMA/EDE`、grid `PCG/EMG/PDG`、storage_monitor `PCG/EMG/PDG`、
       disk_interface `ESE/CMD/ESE`。
    3) 线缆附属设备（bus 类）= 无序，只放「cable + 核心 + 处理器」三件。
       例：importer `[cable, destruction_core, improved_processor]`（无序）、
       exporter `[cable, construction_core, improved_processor]`（无序）。
    4) 管理器类 = 有序，且把「被管理的东西」放进配方（autocrafter_manager 用
       `tag: refinedstorage:autocrafters`；security_manager 用 security_card）。
- 有序 / 无序取舍：**与 RS 同位物品保持一致**。机器方块 / 管理器 / 升级 → 有序（形状即结构，
  且升级类把低级版放中心，语义是「由它升级而来」）；线缆附属的「总线」→ 无序。

只写这些文件，**绝不触碰**已存在的配方（尤其存储磁盘系列，另一任务正在改其容量与配方）。
重复执行结果完全一致（幂等）：内容相同则不重写。

用法：
    python tools/gen_compat_recipes.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat", "recipe")

# 本脚本「拥有」的配方（其余文件一律不动）
# 每条：文件基名、结果 id、有序/无序、材料、RS 同位物品（作为风格依据）
SHAPED = [
    # 归流缓存仓：吸取世界掉落物 → 入网。同位 RS 机器方块（四角石英富铁 + 中心外壳 + 四边角色件）
    {
        "name": "collection_cache",
        "result": "rs_create_compat:collection_cache",
        "pattern": ["QHQ", "DMC", "QPQ"],
        "key": {
            "Q": {"item": "refinedstorage:quartz_enriched_iron"},
            "H": {"item": "minecraft:hopper"},                       # 上：把物品「吸」进来
            "D": {"item": "refinedstorage:destruction_core"},         # 左：从世界取走
            "M": {"item": "refinedstorage:machine_casing"},
            "C": {"item": "refinedstorage:construction_core"},        # 右：写入网络
            "P": {"item": "refinedstorage:improved_processor"},
        },
        "note": "RS 同位：importer/detector（机器方块三件套：外壳 + 核心 + 处理器）",
    },
    # 高级物品定量保持器：升级类 → 低级版居中（RS 升级配方一律「基础物品 + 部件围一圈」）
    {
        "name": "advanced_quantity_keeper",
        "result": "rs_create_compat:advanced_quantity_keeper",
        "pattern": ["QPQ", "PKP", "QEQ"],
        "key": {
            "Q": {"item": "refinedstorage:quartz_enriched_iron"},
            "P": {"item": "refinedstorage:advanced_processor"},
            "K": {"item": "rs_create_compat:quantity_keeper"},        # 中心：基础版，语义「由它升级」
            "E": {"item": "create:precision_mechanism"},              # Create 侧材料（本模组主题）
        },
        "note": "RS 同位：upgrade 系列（基础物 + 部件环绕）；K 居中 = 由基础版升级而来",
    },
    # 序列装配执行器：驱动 Create 机器执行「总样板」的每一步
    {
        "name": "sequence_assembly_executor",
        "result": "rs_create_compat:sequence_assembly_executor",
        "pattern": ["QPQ", "CMD", "QEQ"],
        "key": {
            "Q": {"item": "refinedstorage:quartz_enriched_iron"},
            "P": {"item": "refinedstorage:advanced_processor"},       # 上：大脑（总样板调度）
            "C": {"item": "refinedstorage:construction_core"},        # 左：把原料喂出去
            "M": {"item": "refinedstorage:machine_casing"},
            "D": {"item": "refinedstorage:destruction_core"},         # 右：把产物收回来
            "E": {"item": "create:precision_mechanism"},              # 下：机械执行机构
        },
        "note": "RS 同位：autocrafter `ECE/AMA/EDE`（四角石英富铁 + 外壳 + 两个核心 + 处理器）",
    },
    # 序列执行仓：分布式执行单元，认领步骤并喂给相邻 Create 机
    {
        "name": "sequence_execution_chamber",
        "result": "rs_create_compat:sequence_execution_chamber",
        "pattern": ["QHQ", "IMD", "QEQ"],
        "key": {
            "Q": {"item": "refinedstorage:quartz_enriched_iron"},
            "H": {"item": "minecraft:hopper"},                        # 上：接料 / 喂料
            "I": {"item": "refinedstorage:improved_processor"},       # 左：比执行器低一档的处理器
            "M": {"item": "refinedstorage:machine_casing"},
            "D": {"item": "refinedstorage:destruction_core"},         # 右：取回未消耗原料
            "E": {"item": "create:precision_mechanism"},              # 下：机械执行机构
        },
        "note": "RS 同位：autocrafter（同族机器，处理器降一档 = 分布式多台更便宜）",
    },
    # 单元样板管理舱：管理器类 → 照抄 RS autocrafter_manager 的形状，把「被管理的东西」放进配方
    {
        "name": "unit_pattern_manager",
        "result": "rs_create_compat:unit_pattern_manager",
        "pattern": ["PCG", "EMG", "PCG"],
        "key": {
            "P": {"item": "refinedstorage:advanced_processor"},
            "C": {"item": "rs_create_compat:sequence_execution_chamber"},  # 被管理的执行舱（同 RS 放 autocrafter）
            "G": {"tag": "c:glass_blocks"},
            "E": {"item": "refinedstorage:quartz_enriched_iron"},
            "M": {"item": "refinedstorage:machine_casing"},
        },
        "note": "RS 同位：autocrafter_manager `PCG/EMG/PCG`（逐槽照抄，仅把被管理物换成执行舱）",
    },
]

SHAPELESS = [
    # 「序列装配回流总线」的配方已随玩家侧下线一并删除（该方块保留为遗留方块，仅旧存档继续可用）；
    # 其职责改由「RS 输入总线 + 线缆」承担，见 tools/verify_recipes.py 的 no_recipe 白名单。
]

def write_json(path, data):
    """幂等写盘：内容一致就不动文件（保持 mtime，便于 diff）。"""
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as handle:
            if handle.read() == text:
                return False
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return True


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # 保护名单：已存在的配方一律不动（尤其存储磁盘系列）
    protected = []
    for entry in SHAPED + SHAPELESS:
        target = os.path.join(OUT_DIR, entry["name"] + ".json")
        if os.path.exists(target):
            protected.append(entry["name"])

    written = 0
    for entry in SHAPED:
        data = {
            "type": "minecraft:crafting_shaped",
            "pattern": entry["pattern"],
            "key": entry["key"],
            "result": {"id": entry["result"]},
        }
        changed = write_json(os.path.join(OUT_DIR, entry["name"] + ".json"), data)
        written += 1 if changed else 0
        print("[%s] %-32s -> %s" % ("写入" if changed else "不变", entry["name"], entry["result"]))
        print("       风格：%s" % entry["note"])

    for entry in SHAPELESS:
        data = {
            "type": "minecraft:crafting_shapeless",
            "ingredients": entry["ingredients"],
            "result": {"id": entry["result"]},
        }
        changed = write_json(os.path.join(OUT_DIR, entry["name"] + ".json"), data)
        written += 1 if changed else 0
        print("[%s] %-32s -> %s" % ("写入" if changed else "不变", entry["name"], entry["result"]))
        print("       风格：%s" % entry["note"])

    print("[result] 本脚本负责 %d 条（有序 %d / 无序 %d），本次实际改动 %d 个文件"
          % (len(SHAPED) + len(SHAPELESS), len(SHAPED), len(SHAPELESS), written))
    if protected:
        print("[info] 执行前已存在（内容不一致才重写）：%s" % ", ".join(sorted(protected)))
    print("[info] 未触碰存储磁盘系列与其余既有配方（另一任务负责）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
