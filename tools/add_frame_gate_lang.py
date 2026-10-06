# -*- coding: utf-8 -*-
"""本轮（框架：默认只认线缆/管道 + 退还看得见）的语言键增改。

用户要求（原话要点）
--------------------
1. 「把它作为一个<b>可选功能</b>，<b>默认是关闭的</b>，在<b>配置里可以选择打开</b>」→
   不支持的方块必须给<b>简短反馈</b>（不要静默），因此两句「只能套在哪」的提示要改成
   「只支持线缆 / 流体管道（任意完整方块用配置开启）」；
2. 「你说他会退还，但我并没有看见他退还」→ 创造模式 / 无限版当初<b>没有消耗</b>，
   取下时本来就一个都不还；这种「没得还」不能再静默，于是补三句「为什么没退还」的反馈。

做法
----
`json.load` → 改键 → `json.load` 回读校验 → 中文单条 ≤ 40 字断言 → 写回。
若原文件本来就是按键名排序的，保持排序（与仓库既有排版一致）。
用法：`python tools/add_frame_gate_lang.py`
"""

import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LIMIT = 40

ZH = {
    # ① 默认只认线缆 / 流体管道：不支持的方块给一句简短反馈（并指出可以开配置）
    "message.rs_create_compat.frame_need_pipe":
        "只支持线缆与流体管道，任意完整方块需在配置里开启",
    "block.rs_create_compat.camouflage_frame.hint.pipe_only":
        "只支持线缆与流体管道，任意完整方块需在配置里开启",
    # ② 「退还看得见」：当初没消耗（创造模式 / 无限版）时把原因说清楚，绝不静默
    "message.rs_create_compat.frame_unsheathed_free":
        "已取下分隔框架；创造模式/无限版未消耗，故不退回",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped_free":
        "已取下伪装；创造模式未消耗物品，故不退回",
    "block.rs_create_compat.camouflage_frame.hint.shell_removed_free":
        "已取回外壳方块；创造模式未消耗，故不退回",
}

EN = {
    "message.rs_create_compat.frame_need_pipe":
        "(hint) Only cables and fluid pipes (enable arbitrary full blocks in the config)",
    "block.rs_create_compat.camouflage_frame.hint.pipe_only":
        "(hint) Only cables and fluid pipes (enable arbitrary full blocks in the config)",
    "message.rs_create_compat.frame_unsheathed_free":
        "Frame removed; nothing returned (it was not consumed in creative / infinite)",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped_free":
        "Unwrapped; nothing returned (the shell was not consumed in creative)",
    "block.rs_create_compat.camouflage_frame.hint.shell_removed_free":
        "Shell taken back; nothing returned (it was not consumed in creative)",
}


def apply(lang_file, table):
    path = os.path.join(LANG_DIR, lang_file)
    with io.open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    data = json.loads(text)
    sorted_before = list(data) == sorted(data)
    for key, value in table.items():
        data[key] = value
    if sorted_before:
        data = {key: data[key] for key in sorted(data)}
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    # 回读校验 + 中文长度断言
    with io.open(path, "r", encoding="utf-8") as handle:
        loaded = json.load(handle)
    for key, value in table.items():
        loaded_value = loaded.get(key)
        assert loaded_value == value, "%s 回读不一致：%r" % (key, loaded_value)
        if lang_file.startswith("zh"):
            assert len(value) <= LIMIT, "%s 中文 %d 字（上限 %d）" % (key, len(value), LIMIT)
    print("[OK] %-11s 已写入 %d 个键（原文件%s排序）"
          % (lang_file, len(table), "本就有序，保持" if sorted_before else "未排序，保持原序"))
    return True


def main():
    ok = apply("zh_cn.json", ZH) and apply("en_us.json", EN)
    if ok:
        print("语言键增改完成（json.load 回读一致；中文单条 ≤ %d 字）" % LIMIT)
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
