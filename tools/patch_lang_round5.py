# -*- coding: utf-8 -*-
"""Round4 追加（第二轮）语言键：中英同步。

用法：python tools/patch_lang_round5.py
覆盖两块改动：
  1. 「执行仓单元样板汇总」按 RS 自动合成管理器理念重做为真槽位界面（新增按钮 / 选中 / 槽位提示文案）；
  2. 归流缓存仓「匹配设置」子窗口新增「阻塞」开关行（与主界面 Ctrl+左键同一开关）。

说明：语言 json 一律通过脚本写入（不手写整份 json），保证 UTF-8 + 无尾逗号 + 既有键顺序不变。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

ZH = {
    # ===== 执行仓单元样板汇总（真槽位版） =====
    "gui.rs_create_compat.sequence_pattern_terminal.summary.pull": "取回",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.push": "存入",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.select.none":
        "左键点一行选中一台执行仓，底部「取回 / 存入」对它生效",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.selected": "已选中：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.slot.tip":
        "左键取出整叠 / Shift+左键快速移动 / 拖拽放置（只收单元样板，且配方类型必须与该仓一致）",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.page.prev.tip": "上一页",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.page.next.tip": "下一页",

    # ===== 归流缓存仓：匹配设置子窗口的「阻塞」开关行 =====
    "gui.rs_create_compat.collection_cache.blocked": "阻塞",
    "gui.rs_create_compat.collection_cache.blocked.tip":
        "阻塞该资源：仍可被收集 / 输入 / 取出（绝不销毁），但不再回流进 RS 网络；与主界面 Ctrl+左键是同一个开关",
}

EN = {
    "gui.rs_create_compat.sequence_pattern_terminal.summary.pull": "Take out",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.push": "Put in",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.select.none":
        "Left-click a row to select a chamber; the two bottom buttons act on it",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.selected": "Selected: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.slot.tip":
        "Left-click to take / Shift+Left-click to quick-move / drag to place "
        "(unit patterns only, recipe type must match that chamber)",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.page.prev.tip": "Previous page",
    "gui.rs_create_compat.sequence_pattern_terminal.summary.page.next.tip": "Next page",

    "gui.rs_create_compat.collection_cache.blocked": "Blocked",
    "gui.rs_create_compat.collection_cache.blocked.tip":
        "Block this resource: it can still be collected/inserted/extracted (never destroyed), "
        "but it is no longer returned to the RS network; same switch as Ctrl+Left-click in the main screen",
}


def patch(filename, table):
    path = os.path.join(LANG_DIR, filename)
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    added = 0
    for key, value in table.items():
        if key not in data:
            added += 1
        data[key] = value
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("%s: 新增 %d 键，覆盖后共 %d 键" % (filename, added, len(data)))


def main():
    patch("zh_cn.json", ZH)
    patch("en_us.json", EN)


if __name__ == "__main__":
    main()
