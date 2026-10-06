# -*- coding: utf-8 -*-
"""本轮新增语言键：**手动挂起**（监视器上的「挂起」动作）。

背景（用户原话要点）：现在跑着的任务里有些东西要特别做 / 想再造一条产线，可以先挂起、让出位置，
让别人先做。这个「手动挂起」既可用于序列装配的合成任务，也可用于原版 RS 自动合成任务 ——
语义与自动挂起完全一致（不再占用执行器 / 不阻塞后续任务、已加工中间件原地冻结），
只是「原因标记」要能区分是玩家自己挂的。

需要 4 条键：
  * 监视器上的「挂起」按钮标签与其 tooltip（本工程硬规则：GUI 必须给 tooltip，且要手动渲染）；
  * 挂起标记的第四条原因文案「已挂起：玩家手动挂起」（与既有的掉线 / 缺料 / 无进展并列）；
  * 手动挂起失败时的一次性极简提示（复用 assembly.action_failed 的措辞风格）。

本轮追加 1 条键（用户上一轮的疑问）：
  * `gui.rs_create_compat.assembly.monitor.suspended.inflight_tip` —— 挂起标记 tooltip 的**第二行**，
    用来解释物品行里 RS 自己画的「处理中：N」是**在途件数**（挂起期间冻结、点继续后才推进并归零），
    避免玩家把「已经不动了却还写着 N 个金板正在处理」误读成「还在跑 / 料被吞」。
    该数字是 RS 的真实计数（`ExternalTaskPattern#appendStatus`），我们**不改**它，只把语义讲清楚。

写法：按 key 的字典序**插入到正确位置**（不动既有键的相对顺序），写回后重新 json.load 校验；
中文单条 ≤ 40 字；中英成对。可反复执行（幂等）。

用法：python tools/add_assembly_manual_suspend_lang.py
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

NEW_KEYS = {
    "zh_cn": {
        "gui.rs_create_compat.assembly.monitor.suspend": "挂起",
        "gui.rs_create_compat.assembly.monitor.suspend.tip":
            "让出执行器与原料，先做别的任务；之后点「继续」才恢复",
        "gui.rs_create_compat.assembly.monitor.suspended.manual": "已挂起：玩家手动挂起",
        "gui.rs_create_compat.assembly.monitor.suspended.inflight_tip":
            "「处理中」是在途件数：挂起期间冻结不变，点继续后归零",
        "message.rs_create_compat.assembly.suspend_failed": "操作失败：该任务已不在运行中",
    },
    "en_us": {
        "gui.rs_create_compat.assembly.monitor.suspend": "Suspend",
        "gui.rs_create_compat.assembly.monitor.suspend.tip":
            "Free the executor and materials for other tasks; click Resume to continue",
        "gui.rs_create_compat.assembly.monitor.suspended.manual": "Suspended: manually by player",
        "gui.rs_create_compat.assembly.monitor.suspended.inflight_tip":
            "\"Processing\" = in-flight units; frozen while suspended, cleared after Resume",
        "message.rs_create_compat.assembly.suspend_failed": "Action failed: the task is no longer running",
    },
}


def load(name):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, encoding="utf-8") as handle:
        return path, json.load(handle)


def dump(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def put_sorted(data, key, value):
    """按字典序插入（已存在则原地改值），不改动其它键的相对顺序。"""
    if key in data:
        data[key] = value
        return
    items = list(data.items())
    for index, (existing, _value) in enumerate(items):
        if existing > key:
            items.insert(index, (key, value))
            break
    else:
        items.append((key, value))
    data.clear()
    data.update(items)


def main():
    problems = []
    for name in ("zh_cn.json", "en_us.json"):
        lang = name[:-5]
        path, data = load(name)
        before = len(data)
        added = []
        for key, value in NEW_KEYS[lang].items():
            if key not in data:
                added.append(key)
            put_sorted(data, key, value)
        if lang == "zh_cn":
            too_long = ["%s(%d)" % (k, len(v)) for k, v in NEW_KEYS[lang].items() if len(v) > 40]
            if too_long:
                problems.append("中文单条超过 40 字：%s" % too_long)
        dump(path, data)
        # 写回后立刻重新解析校验（保证文件仍然合法）
        _p, again = load(name)
        if again != data:
            problems.append("%s 写回后重新解析不一致" % name)
            continue
        print("  [OK] %s: %d -> %d 键，本次新增 %d 个" % (name, before, len(again), len(added)))
        for key in added:
            print("       + %s = %s" % (key, NEW_KEYS[lang][key]))

    zh = load("zh_cn.json")[1]
    en = load("en_us.json")[1]
    need_zh = sorted(k for k in zh if k.startswith("gui.rs_create_compat.assembly.")
                     or k.startswith("message.rs_create_compat.assembly."))
    need_en = sorted(k for k in en if k.startswith("gui.rs_create_compat.assembly.")
                     or k.startswith("message.rs_create_compat.assembly."))
    if need_zh != need_en:
        problems.append("中英键集合不一致：%s / %s" % (need_zh, need_en))
    else:
        print("  [OK] 装配相关键共 %d 条，中英一致（本轮之前 29 → 本轮 4）" % len(need_zh))

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
