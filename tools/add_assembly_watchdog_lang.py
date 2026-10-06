#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为「序列装配任务停滞 / 掉线」批次追加语言键（zh_cn / en_us）。

为什么用脚本而不是手改：JSON 一旦写错（尾逗号 / 编码 / 缩进），整个语言文件会失效，
且 200+ 行的文件手工编辑容易破坏行序。本脚本用 json.load 读、dict 保序追加、json.dump 写回，
并在写回后立刻再 json.load 校验 + 打印新增键，可反复执行（幂等）。

用法：python tools/add_assembly_watchdog_lang.py
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

# 键 → (中文, 英文)。中文单条 ≤ 40 字（硬规则）。
KEYS = {
    # ---- 横幅（复用既有完成横幅实现；纯展示，无点击）----
    "gui.rs_create_compat.assembly.banner.suspended": ("序列装配任务已挂起", "Sequence assembly task is suspended"),
    "gui.rs_create_compat.assembly.banner.product": ("序列装配 %s 个 %s", "Assembling %s x %s"),
    "gui.rs_create_compat.assembly.banner.material_prefix": ("由于 ", "Because "),
    "gui.rs_create_compat.assembly.banner.separator": ("、", ", "),
    "gui.rs_create_compat.assembly.banner.material_suffix": (
        " 缺少或自动合成失败", " is missing or its autocrafting failed"),
    "gui.rs_create_compat.assembly.banner.material_more": ("等 %s 种", " and %s more"),
    "gui.rs_create_compat.assembly.banner.offline_prefix": ("由于 ", "Because the executor for "),
    "gui.rs_create_compat.assembly.banner.offline_suffix": (" 的执行器掉线", " is offline"),
    "gui.rs_create_compat.assembly.banner.offline_more": (" 等 %s 个步骤", " and %s more steps"),
    "gui.rs_create_compat.assembly.banner.paused": ("装配已暂停", "Assembly paused"),
    "gui.rs_create_compat.assembly.banner.hint": (
        "在自动合成管理器中决定下一步", "Decide the next step in the autocrafting manager"),
    # ---- 自动合成管理器里的三个处置按钮 ----
    "gui.rs_create_compat.assembly.monitor.resume": ("继续", "Resume"),
    "gui.rs_create_compat.assembly.monitor.resume.tip": (
        "清除挂起状态并让检测器重新计时（仍缺料时会再次挂起）",
        "Clear the suspension and restart the watchdog timer"),
    "gui.rs_create_compat.assembly.monitor.cancel": ("取消", "Cancel"),
    "gui.rs_create_compat.assembly.monitor.cancel.tip": (
        "取消这条序列装配任务（与监视器原版取消同一条路径）",
        "Cancel this sequence assembly task (same path as the monitor's cancel)"),
    "gui.rs_create_compat.assembly.monitor.change_machine": ("更换机器", "Change machine"),
    "gui.rs_create_compat.assembly.monitor.change_machine.tip": (
        "为掉线的那一步改派一台同配方类型的执行仓",
        "Reassign an offline step to another executor of the same recipe type"),
    # ---- 动作反馈（仅在做出动作的玩家聊天栏显示）----
    "message.rs_create_compat.assembly.resumed": (
        "已继续该序列装配任务（检测器重新计时）",
        "Resumed the sequence assembly task (watchdog timer restarted)"),
    "message.rs_create_compat.assembly.cancelled": (
        "已取消该序列装配任务", "Cancelled the sequence assembly task"),
    "message.rs_create_compat.assembly.machine_changed": (
        "已把该步改派到所选执行仓", "Reassigned that step to the selected executor"),
    "message.rs_create_compat.assembly.action_failed": (
        "操作失败：该任务已不存在或执行器不可达",
        "Action failed: the task no longer exists or the executor is unreachable"),
}


def main() -> int:
    problems = []
    for name, index in (("zh_cn.json", 0), ("en_us.json", 1)):
        path = LANG_DIR / name
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        before = len(data)
        added = []
        for key, values in KEYS.items():
            value = values[index]
            if key not in data:
                added.append(key)
            data[key] = value
            if index == 0 and len(value) > 40:
                problems.append("中文过长(%d): %s" % (len(value), key))
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        # 写回后立刻重新解析校验（保证文件仍然合法）
        with open(path, "r", encoding="utf-8") as handle:
            check = json.load(handle)
        missing = [key for key in KEYS if key not in check]
        if missing:
            problems.append("%s 写回后缺键: %s" % (name, missing))
        print("%s: %d -> %d 键，本次新增 %d 个" % (name, before, len(check), len(added)))
        for key in added:
            print("   + %s = %s" % (key, KEYS[key][index]))
        # 键序校验：既有键必须保持原有相对顺序（json.load 保序 + 末尾追加）
        old_order = [key for key in data if key not in added]
        check_order = [key for key in check if key not in added]
        if old_order != check_order:
            problems.append("%s 既有键序被打乱" % name)

    print("-" * 60)
    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    print("语言键写入校验通过（键总数一致、既有键序未变、中文均 ≤ 40 字）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
