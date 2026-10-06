#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""序列装配样板终端「切换执行仓」批次的语言键（zh_cn / en_us）。

为什么用脚本而不是手改：JSON 一旦写错（尾逗号 / 编码 / 缩进），整个语言文件会失效，
且几百行的文件手工编辑容易破坏行序。本脚本用 json.load 读、保序插入、json.dump 写回，
并在写回后立刻重新 json.load 校验 + 校验既有键序未变，可反复执行（幂等）。

本次改动：
  * 改写 card.readonly：流程仍然只读，但「执行仓」本轮恢复为可切换（用户要求，见
    client/screen/SequencePatternTerminalScreen#switchStepMachine）；
  * 新增 card.machine_switch / card.machine_switch.to：行内 ◀ / ▶ 的 tooltip
    （本模组的 GUI 不会自动渲染 tooltip，必须手动渲染，所以文案必须存在）。

用法：python tools/add_sequence_terminal_machine_lang.py
"""
import json
import sys
from pathlib import Path
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parent.parent
LANG_DIR = ROOT / "src/main/resources/assets/rs_create_compat/lang"
PREFIX = "gui.rs_create_compat.sequence_pattern_terminal."
CHAMBER = "gui.rs_create_compat.sequence_execution_chamber."

# 就地改写（键的位置不变）
UPDATED = {
    PREFIX + "card.readonly": (
        "只读：流程由配方自动生成（机器可切换）",
        "Read-only: the flow is generated from the recipe (machine is switchable)",
    ),
}
# 新增：键 → (中文, 英文, 插在哪个既有键之后)
INSERTED = {
    PREFIX + "card.machine_switch": (
        "点击 ◀ / ▶ 切换执行仓（第 %s / %s 台）",
        "Click ◀ / ▶ to switch the executor (%s / %s)",
        PREFIX + "card.machine_kind",
    ),
    PREFIX + "card.machine_switch.to": (
        "◀ / ▶：切换到 %s",
        "◀ / ▶: switch to %s",
        PREFIX + "card.machine_switch",
    ),
    # 执行仓「绑定配置」子界面的机器图标位为空（该配方类型没有可用机器）时的说明
    # —— 供 client/screen/ChamberBindingConfigScreen 自绘的「空」标识做手动 tooltip。
    CHAMBER + "config.machine.none": (
        "该配方类型暂无可用机器",
        "No available machine for this recipe type",
        CHAMBER + "config.machine.tip",
    ),
}


def ordered_with_insertions(data, index):
    """按 INSERTED 的锚点位置把新键插进去（保持既有键的相对顺序，只在锚点后追加）。"""
    out = {}
    pending = dict(INSERTED)
    for key, value in data.items():
        out[key] = value
        for new_key, spec in list(pending.items()):
            if spec[2] == key:
                out[new_key] = spec[index]
                del pending[new_key]
    # 锚点不存在（不应发生）时兜底：追加到末尾，绝不丢键
    for new_key, spec in pending.items():
        out[new_key] = spec[index]
    return out


def main() -> int:
    problems = []
    for name, index in (("zh_cn.json", 0), ("en_us.json", 1)):
        path = LANG_DIR / name
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        before = len(data)
        # 1) 就地改写既有键
        for key, values in UPDATED.items():
            data[key] = values[index]
        # 2) 保序插入新键
        added = [key for key in INSERTED if key not in data]
        data = ordered_with_insertions(data, index)
        # 3) 中文长度硬约束（单条 ≤ 40 字）
        if index == 0:
            for key in list(UPDATED) + list(INSERTED):
                if len(data[key]) > 40:
                    problems.append("中文过长(%d): %s" % (len(data[key]), key))
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        # 4) 写回后立刻重新解析校验（保证文件仍然合法、键都在）
        with open(path, "r", encoding="utf-8") as handle:
            check = json.load(handle)
        missing = [key for key in list(UPDATED) + list(INSERTED) if key not in check]
        if missing:
            problems.append("%s 写回后缺键: %s" % (name, missing))
        print("%s: %d -> %d 键，本次新增 %d 个" % (name, before, len(check), len(added)))
        for key in added:
            print("   + %s = %s" % (key, check[key]))
        for key in UPDATED:
            print("   * %s = %s" % (key, check[key]))
        # 5) 键序校验：既有键（非本次新增）必须保持原有相对顺序
        old_order = [key for key in data if key not in added]
        check_order = [key for key in check if key not in added]
        if old_order != check_order:
            problems.append("%s 既有键序被打乱" % name)

    print("-" * 60)
    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    print("语言键写入校验通过（键序未变、中文均 ≤ 40 字、写回后仍可解析）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
