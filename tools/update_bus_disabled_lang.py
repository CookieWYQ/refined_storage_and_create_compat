#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""语言键：把总线的「被干扰（只提示不停用）」整体改写为「归属未确定 → 延长型已停用」。

为什么用脚本而不是手改：JSON 一旦写错（尾逗号 / 编码 / 缩进），整个语言文件会失效；
本脚本用 json.load 读、保序改写 / 删除 / 末尾追加、json.dump 写回，并在写回后立刻重新
json.load 校验 + 逐条打印，可反复执行（幂等）。中文单条 ≤ 40 字（硬规则）。

改写要点（与代码实现一一对应）：
  * 复用的键（语义变了但键名不变，避免大面积改代码）：
      banner / title / target.chamber / show / hide / show.tip / hide.tip
  * 必须删除的旧键（新语义下不再成立，留着就会自相矛盾）：
      keep（「仍会照常工作：归属按最近执行舱裁决，不静默停用」—— 现在是真停用）
      target.exporter / target.importer / target.other（可达对象现在只可能是执行舱）
      too_large（改名 truncated，说法也改成「可能还有更多可达执行舱」）
  * 新增的键：
      reason / hint / truncated（界面 tooltip 与横幅共用同一句，保证两处说法一致）
      banner.toast.title / banner.toast.exporter / banner.toast.importer（横幅四行的前三行，
      第四行复用 hint）

用法：python tools/update_bus_disabled_lang.py
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
PREFIX = "gui.rs_create_compat.bus_interference."

# 需要删除的旧键（新语义下不再成立）
REMOVED = [
    PREFIX + "keep",
    PREFIX + "target.exporter",
    PREFIX + "target.importer",
    PREFIX + "target.other",
    PREFIX + "too_large",
]

# 键 → (中文, 英文)：既有键在这里是「改值」，新键在末尾追加
KEYS = {
    "banner": (
        "已停用：归属未确定", "Disabled: unclear"),
    "title": (
        "延长型已停用：归属未确定", "Extension disabled: ownership unclear"),
    "reason": (
        "归属未确定：线缆可达 %s 台执行舱",
        "Ownership unclear: the cables reach %s executor(s)"),
    "hint": (
        "请用分隔框架隔离线缆，或把执行舱分开",
        "Separate the cables with a frame, or move the executors apart"),
    "target.chamber": (
        "可达执行舱 (%s, %s, %s)", "Reachable executor (%s, %s, %s)"),
    "truncated": (
        "探查未能穷尽：可能还有更多可达执行舱",
        "Search not exhaustive: more executors may be reachable"),
    "show": (
        "显示可达区域", "Show reachable area"),
    "hide": (
        "隐藏可达区域", "Hide reachable area"),
    "show.tip": (
        "按下后用半透明层标出可达的执行舱与本总线的线缆段",
        "Click to highlight the reachable executors and this bus's cable run"),
    "hide.tip": (
        "再按一次关闭半透明叠加层", "Click again to turn the translucent overlay off"),
    "banner.toast.title": (
        "总线延长型已停用", "Bus extension disabled"),
    "banner.toast.exporter": (
        "输出总线 @ (%s, %s, %s)", "Exporter bus @ (%s, %s, %s)"),
    "banner.toast.importer": (
        "输入总线 @ (%s, %s, %s)", "Importer bus @ (%s, %s, %s)"),
}


def main() -> int:
    problems = []
    loaded = {}
    for name, index in (("zh_cn.json", 0), ("en_us.json", 1)):
        path = LANG_DIR / name
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        before = len(data)

        # 1) 删除旧键（保持其余键的原有相对顺序）
        removed = [key for key in REMOVED if key in data]
        for key in removed:
            del data[key]

        # 2) 改值 / 末尾追加新键
        added = []
        for key, values in KEYS.items():
            full = PREFIX + key
            value = values[index]
            if full not in data:
                added.append(full)
            data[full] = value
            if index == 0 and len(value) > 40:
                problems.append("中文过长(%d): %s" % (len(value), full))

        with open(path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

        # 3) 写回后立刻重新解析校验（保证文件仍然合法）
        with open(path, "r", encoding="utf-8") as handle:
            check = json.load(handle)
        loaded[name] = check

        missing = [PREFIX + key for key in KEYS if (PREFIX + key) not in check]
        if missing:
            problems.append("%s 写回后缺键: %s" % (name, missing))
        leftover = [key for key in REMOVED if key in check]
        if leftover:
            problems.append("%s 写回后旧键仍在: %s" % (name, leftover))
        # 键序校验：既有键（未被删除 / 未被新增）必须保持原有相对顺序
        old_order = [key for key in data if key not in added]
        check_order = [key for key in check if key not in added]
        if old_order != check_order:
            problems.append("%s 既有键序被打乱" % name)

        print("%s: %d -> %d 键（删除 %d / 新增 %d）" % (name, before, len(check), len(removed), len(added)))
        for key in removed:
            print("   - %s" % key)
        for key in added:
            print("   + %s = %s" % (key, KEYS[key[len(PREFIX):]][index]))

    # 4) zh / en 键集合必须严格对齐
    zh_only = sorted(set(loaded["zh_cn.json"]) - set(loaded["en_us.json"]))
    en_only = sorted(set(loaded["en_us.json"]) - set(loaded["zh_cn.json"]))
    if zh_only or en_only:
        problems.append("zh/en 键不对齐：仅中文 %s，仅英文 %s" % (zh_only[:5], en_only[:5]))

    print("-" * 60)
    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    print("语言键写入校验通过（旧键已清除、键序未乱、zh/en 对齐、中文均 ≤ 40 字）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
