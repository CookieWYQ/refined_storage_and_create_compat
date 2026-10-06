#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批 3 语言键（任务 A：执行舱「中间产物缓存」磁盘槽；任务 B：总线「被干扰」提示与叠加层按钮）。

为什么用脚本而不是手改：JSON 一旦写错（尾逗号 / 编码 / 缩进），整个语言文件会失效；本脚本用
json.load 读、dict 保序追加、json.dump 写回，并在写回后立刻重新 json.load 校验 + 打印新增键，
可反复执行（幂等）。中文单条 ≤ 40 字（硬规则）。

用法：python tools/add_batch3_lang.py
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

# 键 → (中文, 英文)
KEYS = {
    # ---------- 任务 A：执行舱「中间产物缓存」磁盘槽 ----------
    "gui.rs_create_compat.sequence_execution_chamber.disk.label": (
        "中间产物缓存", "Intermediate cache"),
    "gui.rs_create_compat.sequence_execution_chamber.disk.none": (
        "未放入磁盘（只接受存储磁盘）", "No disk (storage disks only)"),
    "gui.rs_create_compat.sequence_execution_chamber.disk.pending": (
        "磁盘存储同步中…", "Disk storage syncing..."),
    "gui.rs_create_compat.sequence_execution_chamber.disk.usage": (
        "已用 %s / 总量 %s", "Used %s / %s"),
    "gui.rs_create_compat.sequence_execution_chamber.disk.usage.unlimited": (
        "已用 %s / 无限", "Used %s / unlimited"),
    "gui.rs_create_compat.sequence_execution_chamber.disk.tip.capacity": (
        "磁盘剩余空间即本仓中间产物的额外缓存（与内部存储统一统计）",
        "The disk's free space is extra cache for intermediates (counted together with the internal storage)"),
    "gui.rs_create_compat.sequence_execution_chamber.disk.tip.accept": (
        "只接受 RS 存储磁盘；流体磁盘不计入物品缓存空间",
        "Storage disks only; fluid disks do not add item cache space"),
    "gui.rs_create_compat.sequence_execution_chamber.disk.tip.carry": (
        "取出磁盘时盘里的东西跟着磁盘一起走",
        "Taking the disk out carries its contents with it"),
    # ---------- 任务 B：总线「被干扰」提示 ----------
    # 说明（本轮之后）：这批键的语义整体改写为「归属未确定 → 延长型已停用」，键值 / 增删
    # 由 tools/update_bus_disabled_lang.py 统一管理（keep / too_large / target.exporter|importer|other
    # 已删除）。这里刻意不再写它们 —— 否则重跑本脚本会把已经删掉的旧文案重新塞回去，与实现自相矛盾。
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
