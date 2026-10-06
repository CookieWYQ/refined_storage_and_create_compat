# -*- coding: utf-8 -*-
"""高级远程多功能终端（advanced_remote_terminal）的合成表 —— 本轮按要求「改难」。

设计依据（风格对齐精致存储 + 本模组主题）：
- RS 的「升级 / 派生」配方一律把基础物放<b>中心</b>，语义是「由它升级而来」→ 中心 = `refinedstorage:wireless_grid`
  （终端 = 无线终端的多功能版，仍然是「从无线网格派生」）。
- RS 机器方块的经典配方结构 = 「机器外壳 + 核心 + 处理器 + 四角基础材料」；本配方下中放 `machine_casing`，
  两侧各一枚 `advanced_processor`。
- 本模组主题材料 = Create 侧的 `create:precision_mechanism`（机械执行机构）。
- 「远程」语义 = `refinedstorage:network_transmitter`（远程链路，本模组已给它做了黄铜版配方）
  + 四角 `minecraft:ender_eye`（跨维度视距）。

与改前对比（改前：4 末影之眼 + 无线网格 + 钻石块 + 高级处理器）：
改后新增了 **网络传输器**（它自身需要构造核心 / 破坏核心 / 2 枚高级处理器 / 黄铜块 / 末影珍珠）、
**2 枚精密机构**（Create 序列装配产物）与 **机器外壳**，并且 9 格全满 —— 明显更贵、也更贴合主题。
结果 id 与配方类型（有序 3×3）保持不变，避免影响任何已有存档 / 进度。

用法（幂等：内容一致就不重写文件）：
    python tools/gen_advanced_terminal_recipe.py
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
NAME = "advanced_remote_terminal"

RECIPE = {
    "type": "minecraft:crafting_shaped",
    "pattern": [
        "ENE",
        "TWT",
        "CMC",
    ],
    "key": {
        "E": {"item": "minecraft:ender_eye"},                        # 四角：远程 / 跨维度
        "N": {"item": "refinedstorage:network_transmitter"},         # 上中：远程链路（无线打开网络界面）
        "T": {"item": "create:precision_mechanism"},                 # 中行两侧：Create 侧机械件
        "W": {"item": "refinedstorage:wireless_grid"},               # 中心：基础物（由它派生）
        "C": {"item": "refinedstorage:advanced_processor"},          # 下行两侧：大脑（多功能：6 种界面）
        "M": {"item": "refinedstorage:machine_casing"},              # 下中：机器外壳
    },
    "result": {"id": "rs_create_compat:advanced_remote_terminal"},
}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, NAME + ".json")
    text = json.dumps(RECIPE, indent=2, ensure_ascii=False) + "\n"
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as handle:
            if handle.read() == text:
                print("[不变] %s.json 已是最新" % NAME)
                return 0
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("[写入] %s.json：9 格全满（末影之眼 ×4 / 网络传输器 / 精密机构 ×2 / 无线网格 / "
          "高级处理器 ×2 / 机器外壳）" % NAME)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
