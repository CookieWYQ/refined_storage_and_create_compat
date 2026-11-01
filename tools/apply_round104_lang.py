# -*- coding: utf-8 -*-
"""第 104 轮 Lead 落盘：A/B 组去省略号需要的语言键（新增 5 条 + 改值 15 条）+ 序列装配 B-4 的 3 条。

只做「按行改值 / 按锚点插行」，不重排整个文件（保持既有排版与键序）。
用法：python tools/apply_round104_lang.py [--dry]
"""
from __future__ import annotations

import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# key -> {zh, en}     只改值（键必须已存在）
CHANGED = {
    "gui.rs_create_compat.network_resource_requester.status.no_network": {
        "zh": "未接入可用的 RS 网络",
        "en": "Not connected to an RS network",
    },
    "gui.rs_create_compat.network_resource_requester.target.known.none": {
        "zh": "还没有已命名的接收器",
        "en": "No named receiver yet",
    },
    "gui.rs_create_compat.network_resource_requester.target.hint.missing": {
        "zh": "所选接收器已不在此网络",
        "en": "Selected receiver is gone",
    },
    "gui.rs_create_compat.network_resource_receiver.status.unnamed": {
        "zh": "未命名（命名后请求器才能选到）",
        "en": "Unnamed (name it to be pickable)",
    },
    "gui.rs_create_compat.network_resource_receiver.name.label": {
        "zh": "接收器名字（同网络唯一）",
        "en": "Receiver name (unique)",
    },
    "gui.rs_create_compat.network_resource_receiver.name.hint.empty": {
        "zh": "名字不能为空（输入后点「确定」）",
        "en": "Name must not be empty",
    },
    "gui.rs_create_compat.network_resource_receiver.name.hint.invalid": {
        "zh": "名字含非法字符（控制符 / §）",
        "en": "Name has illegal characters",
    },
    "gui.rs_create_compat.network_resource_receiver.name.hint.taken": {
        "zh": "这个名字已被占用",
        "en": "That name is already taken",
    },
    "gui.rs_create_compat.network_resource_requester.redstone.hint": {
        "zh": "红石上升沿触发一次投递",
        "en": "Rising edge = one delivery",
    },
    "gui.rs_create_compat.network_resource_requester.order.none": {
        "zh": "订单：空（点「网格」选资源）",
        "en": "Order: empty (pick in Grid)",
    },
    "gui.rs_create_compat.schematic_loader.recycle": {
        "zh": "自动回收蓝图",
        "en": "Recycle",
    },
    "gui.rs_create_compat.schematic_loader.gunpowder": {
        "zh": "自动填火药",
        "en": "Gunpowder",
    },
    "gui.rs_create_compat.sequence_execution_chamber.chain.suffix.directed": {
        "zh": "（链 %s 台 · 指向%s）",
        "en": "(chain %s → %s)",
    },
    # 三个「插件」栏标题：19px 可用宽度，英文必须压到 3 字符
    "gui.rs_create_compat.network_resource_receiver.upgrades": {"zh": "插件", "en": "Upg"},
    "gui.rs_create_compat.network_resource_requester.upgrades": {"zh": "插件", "en": "Upg"},
    "gui.rs_create_compat.range_charger.upgrades": {"zh": "插件", "en": "Upg"},
    # ---- A 组（proxy-ui）P1：不落地会越出面板 ----
    "gui.rs_create_compat.sequence_pattern_terminal.summary.poll": {
        "zh": "样板多时轮换显示",
        "en": "Rotates when many",
    },
    "gui.rs_create_compat.decomposition_generator.page": {
        "zh": "%s/%s 页",
        "en": "%s/%s",
    },
    # ---- A 组 P2：折两行也能兜住，但缩到下限仍偏紧，直接缩短 ----
    "gui.rs_create_compat.decomposition_generator.invalid_slots": {
        "zh": "有 %s 格物品无法降解，请取出",
        "en": "%s slots can't be decomposed",
    },
    "gui.rs_create_compat.decomposition_generator.buffer_full": {
        "zh": "缓存已满，发电暂停",
        "en": "Buffer full - paused",
    },
    "gui.rs_create_compat.decomposition_generator.reject": {
        "zh": "该物品无法降解（只接受植物）",
        "en": "This item can't be decomposed",
    },
    # ---- A 组 P3：能量审计两行长句 ----
    "gui.rs_create_compat.energy_audit.cross_dim_hint": {
        "zh": "只显示终端绑定的这张网络",
        "en": "Only this terminal's own network",
    },
    "gui.rs_create_compat.energy_audit.no_network": {
        "zh": "未连接网络",
        "en": "Not connected to a network",
    },
    # ---- 用户口径「界面不要出现 …」：把 lang 值里所有省略号字符清掉 ----
    "gui.rs_create_compat.assembly.monitor.pending": {"zh": "正在处理", "en": "Working"},
    "gui.rs_create_compat.bus_config.button": {"zh": "详细配置", "en": "Details"},
    "gui.rs_create_compat.bus_config.search.pick_hint": {"zh": "搜索配方", "en": "Search recipes"},
    "gui.rs_create_compat.clipboard_tooltip.bus.filter.items_more": {
        "zh": "另有 %s 项",
        "en": "%s more",
    },
    "gui.rs_create_compat.clipboard_tooltip.other.more": {"zh": "另有 %s 项", "en": "%s more"},
    "gui.rs_create_compat.intermediate_cache.disk.pending": {
        "zh": "磁盘存储同步中",
        "en": "Disk storage syncing",
    },
    "gui.rs_create_compat.resource_picker.selected.more": {
        "zh": "另有 %s 种未显示",
        "en": "%s more not shown",
    },
    "gui.rs_create_compat.sequence_execution_chamber.syncing": {
        "zh": "绑定同步中",
        "en": "Syncing binding",
    },
    "gui.rs_create_compat.exporter_executor.click": {
        "zh": "条上只读展示；改勾选请点「详细配置」",
        "en": "Read-only bar; use the Details button",
    },
    "gui.rs_create_compat.exporter_executor.filtered.tip": {
        "zh": "条上只显示已勾选的类别；未勾选的请到「详细配置」里看",
        "en": "Only selected ones are listed; open Details for all",
    },
    "gui.rs_create_compat.exporter_executor.no_selected_action": {
        "zh": "点「详细配置」勾选",
        "en": "Use Details to select",
    },
    "gui.rs_create_compat.importer_executor.filtered.tip": {
        "zh": "条上只显示已勾选的类别；未勾选的请到「详细配置」里看",
        "en": "Only selected ones are listed; open Details for all",
    },
    "gui.rs_create_compat.importer_executor.no_selected_action": {
        "zh": "点「详细配置」勾选",
        "en": "Use Details to select",
    },
    "gui.rs_create_compat.importer_executor.click": {
        "zh": "条上只读展示；改勾选请点「详细配置」",
        "en": "Read-only bar; use the Details button",
    },
    "gui.rs_create_compat.importer_executor.auto.locked.tip": {
        "zh": "自动模式：本格仅展示；勾选在「详细配置」里改，切手动后生效",
        "en": "Auto mode: read-only here; change it in Details (applies after switching to manual)",
    },
    "gui.rs_create_compat.importer_executor.locked.tip": {
        "zh": "已连到总线输出执行仓。条上只读；勾选请点「详细配置」",
        "en": "Linked to a chamber exporter. Read-only here; use Details to select",
    },
}

# key -> {zh, en, after}   新增（插在 after 那一行之后）
ADDED = {
    "gui.rs_create_compat.widget.scroll_select.more_above": {
        "zh": "上面还有 %s 项（共 %s 项）",
        "en": "%s more above (of %s)",
        "after": '"gui.rs_create_compat.widget.scroll_select.none"',
    },
    "gui.rs_create_compat.widget.scroll_select.more_below": {
        "zh": "下面还有 %s 项（共 %s 项）",
        "en": "%s more below (of %s)",
        "after": '"gui.rs_create_compat.widget.scroll_select.none"',
    },
    "gui.rs_create_compat.bus_config.station.facing": {
        "zh": "本总线朝向：%s @%s",
        "en": "Facing: %s @%s",
        "after": '"gui.rs_create_compat.bus_config.',
    },
    "gui.rs_create_compat.bus_config.station.derived": {
        "zh": "该工位推导：%s · 第 %s 步",
        "en": "Station: %s · step %s",
        "after": '"gui.rs_create_compat.bus_config.station.facing"',
    },
    "gui.rs_create_compat.bus_config.station.unknown": {
        "zh": "该工位推导：判不出（按旧版全选）",
        "en": "Station: unknown (select-all)",
        "after": '"gui.rs_create_compat.bus_config.station.derived"',
    },
}

KEY_LINE = re.compile(r'^\s*"([^"]+)"\s*:\s*"')


def load(path: str) -> list[str]:
    with io.open(path, encoding="utf-8") as fh:
        return fh.read().split("\n")


def save(path: str, lines: list[str]) -> None:
    text = "\n".join(lines)
    json.loads(text)  # 语法自检（失败就不写盘）
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def apply(name: str, dry: bool) -> tuple[int, int]:
    path = os.path.join(LANG, name)
    zh = name.startswith("zh")
    lines = load(path)
    changed = added = 0

    # 1) 改值
    for i, line in enumerate(lines):
        m = KEY_LINE.match(line)
        if not m:
            continue
        key = m.group(1)
        if key not in CHANGED:
            continue
        value = CHANGED[key]["zh" if zh else "en"]
        new_line = '  "%s": %s,' % (key, json.dumps(value, ensure_ascii=False))
        if lines[i] != new_line:
            lines[i] = new_line
            changed += 1

    # 2) 新增（按意图顺序插，锚点可以是「已插入的上一条」）
    for key, spec in ADDED.items():
        if any(KEY_LINE.match(l) and KEY_LINE.match(l).group(1) == key for l in lines):
            continue
        value = spec["zh" if zh else "en"]
        anchor = spec["after"]
        pos = -1
        for i, line in enumerate(lines):
            if anchor in line:
                pos = i
                if anchor.endswith('"'):  # 精确锚点：命中即用
                    break
        if pos < 0:
            raise SystemExit("%s: 找不到锚点 %s" % (name, anchor))
        lines.insert(pos + 1, '  "%s": %s,' % (key, json.dumps(value, ensure_ascii=False)))
        added += 1

    if not dry:
        save(path, lines)
    return changed, added


def main() -> int:
    dry = "--dry" in sys.argv
    for name in ("zh_cn.json", "en_us.json"):
        c, a = apply(name, dry)
        print("%-12s 改值 %2d 条，新增 %2d 条%s" % (name, c, a, "（dry）" if dry else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
