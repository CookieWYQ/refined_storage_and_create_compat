# -*- coding: utf-8 -*-
"""本轮（输入总线界面重排 + 自动回流语义）语言键增删（中英同步）。

用法：python tools/update_lang_bus_bar.py

覆盖三块改动：
  1. **删掉搜索框**：删除 gui.rs_create_compat.importer_executor.search.*（4 个键，
     对应的 EditBox / 渲染 / 点击转发 / 过滤逻辑已从控件与两个界面 Mixin 里移除）；
  2. **条下方「◀ ▶ + 页码」翻页控件**：新增共用前缀 gui.rs_create_compat.bus_bar.* 4 个键
     （两个宿主界面共用，因此不能挂在任一宿主前缀上 —— 与既有的 bus_interference.* 同一做法）；
  3. **自动模式的展示与禁用说明**：新增 auto.list（列出正在回收的类别）、把 auto.on / auto.off
     压成短标签（开关按钮只有 62px 宽，英文标题会溢出）、按新语义重写 auto.title / auto.tip /
     auto.collect.tip / auto.locked.tip（未过阈值的过渡件不再收回），并去掉 scroll.tip 里
     已不存在的「滚动条」说法（改成 ◀ ▶ / 滚轮）。

说明：语言 json 一律通过脚本写入（不手写整份 json），保持 UTF-8 + 无尾逗号 +
既有键顺序（本工程的 lang 文件按键名排序，故写入时用 sort_keys 复现同一顺序）。
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

REMOVED = [
    "gui.rs_create_compat.importer_executor.search.hint",
    "gui.rs_create_compat.importer_executor.search.tip",
    "gui.rs_create_compat.importer_executor.search.filtered",
    "gui.rs_create_compat.importer_executor.search.empty",
]

ZH = {
    # ===== 1. 翻页控件（两个宿主共用前缀；中文单条 <= 30 字） =====
    "gui.rs_create_compat.bus_bar.title": "第 %s / %s 页",
    "gui.rs_create_compat.bus_bar.tip": "< > 翻页；滚轮逐格翻看",
    "gui.rs_create_compat.bus_bar.single": "只有一页，无需翻页",
    "gui.rs_create_compat.bus_bar.sep": "、",

    # ===== 2. 自动模式：短标签 + 「正在回收哪些类别」 =====
    "gui.rs_create_compat.importer_executor.auto.on": "自动",
    "gui.rs_create_compat.importer_executor.auto.off": "手动",
    "gui.rs_create_compat.importer_executor.auto.list": "自动回收中：%s",
    "gui.rs_create_compat.importer_executor.auto.title": "自动收回：成品 / 废料 / 过阈值过渡件",
    "gui.rs_create_compat.importer_executor.auto.tip": "全自动：收回成品 / 废料 / 过阈值的过渡件。",
    "gui.rs_create_compat.importer_executor.auto.collect.tip": "自动：这一类自动收回（成品 / 废料 / 过阈值件）。",
    "gui.rs_create_compat.importer_executor.auto.locked.tip": "自动模式：类别按钮已禁用，切手动才能勾选。",
    "gui.rs_create_compat.importer_executor.auto.skip.tip": "自动：输入类不收回（避免把刚喂进去的料抽回来）。",
    "gui.rs_create_compat.importer_executor.scroll.tip":
        "滚轮翻看更多类别（显示第 %s-%s 项，共 %s 项）。",
    "gui.rs_create_compat.exporter_executor.scroll.tip":
        "滚轮翻看更多类别（当前显示第 %s-%s 项，共 %s 项）。",
}

EN = {
    "gui.rs_create_compat.bus_bar.title": "Page %s / %s",
    "gui.rs_create_compat.bus_bar.tip": "Click < > to page; wheel scrolls one cell",
    "gui.rs_create_compat.bus_bar.single": "Single page; nothing to flip",
    "gui.rs_create_compat.bus_bar.sep": ", ",

    "gui.rs_create_compat.importer_executor.auto.on": "Auto",
    "gui.rs_create_compat.importer_executor.auto.off": "Manual",
    "gui.rs_create_compat.importer_executor.auto.list": "Auto reclaiming: %s",
    "gui.rs_create_compat.importer_executor.auto.title": "Auto: products, scraps, past-threshold items",
    "gui.rs_create_compat.importer_executor.auto.tip": "Auto: reclaims products, scraps, past-threshold items.",
    "gui.rs_create_compat.importer_executor.auto.collect.tip": "Auto: this category is reclaimed automatically.",
    "gui.rs_create_compat.importer_executor.auto.locked.tip": "Auto mode: buttons disabled. Switch to manual.",
    "gui.rs_create_compat.importer_executor.auto.skip.tip": "Auto: inputs are not reclaimed (avoids re-pulling).",
    "gui.rs_create_compat.importer_executor.scroll.tip": "Wheel: browse more categories (showing %s-%s of %s).",
    "gui.rs_create_compat.exporter_executor.scroll.tip": "Wheel: browse more categories (showing %s-%s of %s).",
}


def patch(filename, table, removed):
    path = os.path.join(LANG_DIR, filename)
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    # 既有键顺序就是排序后的顺序：先断言，确保写入时可以用 sort_keys 复现同一顺序
    keys = list(data)
    if keys != sorted(keys):
        raise SystemExit("%s 的键顺序不是排序后的顺序，脚本拒绝整表重排" % filename)
    dropped = 0
    for key in removed:
        if key in data:
            del data[key]
            dropped += 1
        else:
            raise SystemExit("%s 缺少待删除键：%s" % (filename, key))
    added = 0
    for key, value in table.items():
        if key not in data:
            added += 1
        data[key] = value
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    # 回读校验：JSON 必须仍然可解析，且键集与写入时一致
    with open(path, "r", encoding="utf-8") as handle:
        check = json.load(handle)
    if set(check) != set(data):
        raise SystemExit("%s 回读校验失败" % filename)
    if list(check) != sorted(check):
        raise SystemExit("%s 写入后键顺序不是排序后的顺序" % filename)
    print("%s: 新增 %d 键 / 删除 %d 键，共 %d 键" % (filename, added, dropped, len(check)))


def main():
    patch("zh_cn.json", ZH, REMOVED)
    patch("en_us.json", EN, REMOVED)
    # 中英键集一致性 + 关键长度上限（与本工程既有自检同口径）
    zh = json.load(open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
    if set(zh) != set(en):
        raise SystemExit("zh / en 键集不一致")
    for key in list(ZH) + list(EN):
        if not key.startswith("gui.rs_create_compat.bus_bar.") and len(zh[key]) > 40:
            raise SystemExit("中文超过 40 字：%s" % key)
        if len(en[key]) > 60:
            raise SystemExit("英文超过 60 字符：%s" % key)
    for key in ("gui.rs_create_compat.importer_executor.auto.title",
                "gui.rs_create_compat.importer_executor.auto.tip",
                "gui.rs_create_compat.importer_executor.auto.collect.tip",
                "gui.rs_create_compat.importer_executor.auto.locked.tip",
                "gui.rs_create_compat.importer_executor.scroll.tip"):
        if len(zh[key]) > 30:
            raise SystemExit("自动模式相关中文超过 30 字：%s (%d)" % (key, len(zh[key])))
    print("OK: 中英键集一致（共 %d 键）" % len(zh))


if __name__ == "__main__":
    main()
