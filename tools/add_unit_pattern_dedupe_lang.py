# -*- coding: utf-8 -*-
"""用 Python 写入「生成单元样板时自动跳过重复」相关的语言键（中英成对）。

覆盖：
  * 单元样板管理舱界面上的「设置开关」勾选框的标题 / 状态 / 帮助三行 tooltip；
  * 开关被点击后的动作栏反馈（开 / 关）；
  * 「发现完全相同的样板、本次不生成」的反馈（带来源与槽位）。

用法：python tools/add_unit_pattern_dedupe_lang.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = (r"d:\MODS\refined_storage_and_create_compat\src\main\resources"
        r"\assets\rs_create_compat\lang")
UI = "gui.rs_create_compat.unit_pattern_manager.dedupe."
MSG = "message.rs_create_compat.unit_pattern_dedupe."

ZH = {
    UI + "title": "生成单元样板时自动跳过重复",
    UI + "state": "当前：%s",
    UI + "on": "开（不生成重复样板）",
    UI + "off": "关（重复样板也照常生成）",
    UI + "help": "判据：操作类型 + 输入式原料完全相同",
    MSG + "skipped": "已有完全相同的样板：%s（第 %s 格），未重复生成",
    MSG + "on": "已开启：生成单元样板时自动跳过重复",
    MSG + "off": "已关闭：重复样板也会照常生成",
}

EN = {
    UI + "title": "Skip duplicate unit patterns when creating",
    UI + "state": "Currently: %s",
    UI + "on": "ON (do not create duplicates)",
    UI + "off": "OFF (duplicates are still created)",
    UI + "help": "Match: same operation + identical input ingredients",
    MSG + "skipped": "An identical pattern already exists: %s (slot %s); not created again",
    MSG + "on": "Enabled: duplicate unit patterns are skipped when creating",
    MSG + "off": "Disabled: duplicate unit patterns are created as usual",
}


def patch(name, updates):
    path = os.path.join(ROOT, name)
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    was_sorted = list(data) == sorted(data)
    data.update(updates)
    if was_sorted:
        data = {key: data[key] for key in sorted(data)}
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("patched %s (+%d keys, sorted=%s, total=%d)" % (name, len(updates), was_sorted, len(data)))


def check_lengths():
    for key, text in ZH.items():
        if len(text) > 40:
            raise SystemExit("中文超 40 字（%d）：%s -> %s" % (len(text), key, text))
    if set(ZH) != set(EN):
        raise SystemExit("中英键不一致（脚本内部错误）")


if __name__ == "__main__":
    check_lengths()
    patch("zh_cn.json", ZH)
    patch("en_us.json", EN)
