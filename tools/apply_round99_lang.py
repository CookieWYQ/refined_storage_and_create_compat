# -*- coding: utf-8 -*-
"""第 99 轮语言键落盘（Lead 专用一次性脚本）。

为什么要脚本而不是手改：zh/en 两份 JSON 必须**成对**且**同一个键名一个字都不能错**
（键名来自 Java 里的字面量，手打极易错一个字母 → 玩家看到原始 key）。
本脚本只做「在文件末尾追加」，不改动任何既有行，diff 最小；
写入前会断言：① 键名不重复 ② zh/en 键集合完全一致 ③ 占位符 %s 个数一致。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

ZH = {
    # ---- 方块 / 物品显示名（注册名 → 默认语言键） ----
    "block.rs_create_compat.fe_energy_cube": "FE 能量立方",
    "block.rs_create_compat.fe_energy_cube.help":
        "5000 万 FE 缓存；相邻同类共用一个容量池，网络低电时当最后一块电池。",
    "item.rs_create_compat.electric_jetpack": "电动飞行背包",
    # ---- 电动飞行背包 tooltip / HUD / 提示 ----
    "item.rs_create_compat.electric_jetpack.tooltip.energy": "电量：%s / %s FE",
    "item.rs_create_compat.electric_jetpack.tooltip.slots": "可装备：胸甲槽 / 饰品背饰槽 / 饰品胸饰槽（其它位置不生效）",
    "item.rs_create_compat.electric_jetpack.tooltip.flight": "空格起飞 · 双击空格起飞 · Shift 下降（消耗电量）",
    "item.rs_create_compat.electric_jetpack.tooltip.cost": "飞行时每 tick 消耗 %s FE",
    "item.rs_create_compat.electric_jetpack.tooltip.charge": "放进范围充电器的范围里即可充电（HUD 会显示「范围内 · 充电中」）",
    "item.rs_create_compat.electric_jetpack.hud.charging": "范围内 · 充电中",
    "item.rs_create_compat.electric_jetpack.hud.in_range": "范围内",
    "item.rs_create_compat.electric_jetpack.low_power.title": "电量不足",
    "item.rs_create_compat.electric_jetpack.depleted.title": "电量耗尽，飞行已关闭",
    # ---- FE 能量立方界面 ----
    "gui.rs_create_compat.fe_energy_cube.cluster": "集群：%s 块",
    "gui.rs_create_compat.fe_energy_cube.cluster_energy": "集群电量：%s / %s FE",
    "gui.rs_create_compat.fe_energy_cube.own": "本块电量：%s / %s FE",
    "gui.rs_create_compat.fe_energy_cube.transfer": "对外充放 %s FE/t，从网络抽取 %s FE/t",
    "gui.rs_create_compat.fe_energy_cube.gate.allow": "可充能（控制器电量 %s）",
    "gui.rs_create_compat.fe_energy_cube.gate.deny": "暂停充能（控制器 %s < 阈值 %s）",
    "gui.rs_create_compat.fe_energy_cube.gate.nonetwork": "未接入 RS 网络",
    # ---- 成就 ----
    "advancements.rs_create_compat.fe_energy_cube.title": "电力储备",
    "advancements.rs_create_compat.fe_energy_cube.description": "造出 FE 能量立方",
    "advancements.rs_create_compat.electric_jetpack.title": "凭电上天",
    "advancements.rs_create_compat.electric_jetpack.description": "造出电动飞行背包",
}

EN = {
    "block.rs_create_compat.fe_energy_cube": "FE Energy Cube",
    "block.rs_create_compat.fe_energy_cube.help":
        "50M FE buffer; adjacent cubes share one capacity pool and act as the network's last-resort battery.",
    "item.rs_create_compat.electric_jetpack": "Electric Jetpack",
    "item.rs_create_compat.electric_jetpack.tooltip.energy": "Energy: %s / %s FE",
    "item.rs_create_compat.electric_jetpack.tooltip.slots": "Equippable: chest slot / Curios back / Curios chest (other slots do nothing)",
    "item.rs_create_compat.electric_jetpack.tooltip.flight":
        "Jump to take off, double-tap jump, hold Shift to descend (consumes power)",
    "item.rs_create_compat.electric_jetpack.tooltip.cost": "Consumes %s FE per tick while flying",
    "item.rs_create_compat.electric_jetpack.tooltip.charge":
        "Recharge it inside a range charger's field (HUD shows in range - charging)",
    "item.rs_create_compat.electric_jetpack.hud.charging": "In range - charging",
    "item.rs_create_compat.electric_jetpack.hud.in_range": "In range",
    "item.rs_create_compat.electric_jetpack.low_power.title": "Low Power",
    "item.rs_create_compat.electric_jetpack.depleted.title": "Power depleted - flight disabled",
    "gui.rs_create_compat.fe_energy_cube.cluster": "Cluster: %s cubes",
    "gui.rs_create_compat.fe_energy_cube.cluster_energy": "Cluster energy: %s / %s FE",
    "gui.rs_create_compat.fe_energy_cube.own": "This cube: %s / %s FE",
    "gui.rs_create_compat.fe_energy_cube.transfer": "I/O %s FE/t, network intake %s FE/t",
    "gui.rs_create_compat.fe_energy_cube.gate.allow": "Charging allowed (controller %s)",
    "gui.rs_create_compat.fe_energy_cube.gate.deny": "Charging paused (controller %s < threshold %s)",
    "gui.rs_create_compat.fe_energy_cube.gate.nonetwork": "Not connected to an RS network",
    "advancements.rs_create_compat.fe_energy_cube.title": "Power Reserve",
    "advancements.rs_create_compat.fe_energy_cube.description": "Craft an FE Energy Cube",
    "advancements.rs_create_compat.electric_jetpack.title": "Sky High",
    "advancements.rs_create_compat.electric_jetpack.description": "Craft an Electric Jetpack",
}

PLACEHOLDER_RE = re.compile(r"%\d*\$?s|%s")


def append(path, additions):
    """在 JSON 文件末尾追加键（保持既有行一字不动）。返回 (原有键数, 真正新增数)。"""
    with io.open(path, encoding="utf-8") as handle:
        text = handle.read()
    data = json.loads(text)
    dupes = [k for k in additions if k in data]
    assert not dupes, "已有同名键，拒绝覆盖：%s" % dupes
    # 语言文件形如 "{\n  ...\n}\n"：在最后一个「换行 + }」处插入。
    # 之所以按文本插入而不是 json.dump 整份重写：重写会把既有 1400+ 行全部改写成另一种
    # 排版，diff 变成整文件替换，之后没人能看出「这一轮到底加了哪几行」。
    stripped = text.rstrip()
    assert stripped.endswith("}"), "语言文件结构异常（结尾不是 }）"
    cut = stripped.rfind("\n}")
    assert cut > 0, "语言文件结构异常（找不到结尾的对象闭合行）"
    head = stripped[:cut].rstrip()
    assert head.endswith(('"', "}", "]", "e", "l", "0", "1", "2", "3", "4", "5", "6", "7",
                          "8", "9")), head[-20:]
    lines = []
    for key in additions:
        lines.append('  %s: %s' % (json.dumps(key, ensure_ascii=False),
                                   json.dumps(additions[key], ensure_ascii=False)))
    new_text = head + ",\n" + ",\n".join(lines) + "\n}\n"
    merged = json.loads(new_text)
    assert len(merged) == len(data) + len(additions), "追加后键数不对"
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(new_text)
    return len(data), len(additions)


def main():
    zpath = os.path.join(LANG, "zh_cn.json")
    epath = os.path.join(LANG, "en_us.json")
    assert set(ZH) == set(EN), "zh/en 键集合不一致：zh-only=%s en-only=%s" % (
        sorted(set(ZH) - set(EN)), sorted(set(EN) - set(ZH)))
    for key in ZH:
        a = len(PLACEHOLDER_RE.findall(ZH[key]))
        b = len(PLACEHOLDER_RE.findall(EN[key]))
        assert a == b, "占位符个数不一致：%s zh=%d en=%d" % (key, a, b)
    zn, added = append(zpath, ZH)
    en, _ = append(epath, EN)
    print("[ok] zh_cn %d -> %d 键；en_us %d -> %d 键；本次新增 %d 条（中英成对）"
          % (zn, zn + added, en, en + added, added))


if __name__ == "__main__":
    main()
