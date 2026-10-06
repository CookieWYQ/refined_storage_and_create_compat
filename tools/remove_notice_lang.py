# -*- coding: utf-8 -*-
"""删除「过程性播报」语言键（用脚本改，不手改 JSON）。

用户明确要求删掉的播报（原话：「你先这些细，也就是我点击继续、检测器重新计时，这些话语不要有」）：
  * `message.rs_create_compat.assembly.resumed`      —— 点「继续」后的「已继续该序列装配任务（检测器重新计时）」
  * `message.rs_create_compat.cable_seam_cut`        —— 拨下线缆后的「已断开：此处不再连接」
  * `message.rs_create_compat.cable_seam_restored`   —— 「已恢复：此处重新连接」
  * `message.rs_create_compat.cable_seam_none`       —— 「这里没有可断开的连接」
  * `message.rs_create_compat.cable_face_cut`        —— 「该面已停止自动连接」
  * `message.rs_create_compat.cable_face_restored`   —— 「该面已恢复自动连接」

判定标准（保留清单见报告）：这些文案描述的都是**玩家自己刚做的动作**（一次右键 / 一次点击），
动作的结果在世界上本来就看得到（连接臂变了 / 挂起标记消失了），复述一遍只是噪声，
还会把真正必要的状态信息（「已挂起：执行器离线」这类解释「为什么不动」的一次性横幅）淹掉。
保留的键：`message.rs_create_compat.assembly.action_failed`（真实失败的一次性解释）、
`assembly.machine_changed`、`gui.rs_create_compat.assembly.monitor.*`（监视器上的静态状态标签）、
`gui.rs_create_compat.assembly.banner.*`（一次性挂起横幅）。

本脚本同时做**双向断言**：① 6 个键在两份语言文件里都不存在；② 全仓 .java 里不再出现这些键字面量；
③ 其余键一字未动（数量差恰为 6）；④ 中英键集合仍完全一致；⑤ 写回后重新 json.load 校验通过。

用法：python tools/remove_notice_lang.py   → 末行 `问题总数: 0`
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
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")

REMOVED_KEYS = [
    "message.rs_create_compat.assembly.resumed",
    "message.rs_create_compat.cable_seam_cut",
    "message.rs_create_compat.cable_seam_restored",
    "message.rs_create_compat.cable_seam_none",
    "message.rs_create_compat.cable_face_cut",
    "message.rs_create_compat.cable_face_restored",
]

# 必须仍然存在的「保留清单」（一次性状态 / 真实失败 / 监视器静态标签）。
KEPT_KEYS = [
    "message.rs_create_compat.assembly.action_failed",
    "message.rs_create_compat.assembly.machine_changed",
    "gui.rs_create_compat.assembly.monitor.resume",
    "gui.rs_create_compat.assembly.monitor.suspended.offline",
    "gui.rs_create_compat.assembly.banner.suspended",
]

PROBLEMS = []


def load(name):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, encoding="utf-8") as handle:
        return path, json.load(handle)


def dump(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def java_files():
    for root, _dirs, files in os.walk(JAVA_ROOT):
        for name in sorted(files):
            if name.endswith(".java"):
                yield os.path.join(root, name)


def main():
    before = {}
    for name in ("zh_cn.json", "en_us.json"):
        path, data = load(name)
        before[name] = len(data)
        for key in REMOVED_KEYS:
            if key in data:
                del data[key]
        removed = before[name] - len(data)
        dump(path, data)
        _p, again = load(name)
        if again != data:
            PROBLEMS.append("%s 写回后重新 json.load 不一致" % name)
        if removed not in (0, len(REMOVED_KEYS)):
            PROBLEMS.append("%s 删除键数异常（%d：既不是 6 条全删、也不是 0 条已删）"
                            % (name, removed))
        else:
            print("  [OK] %s：本次删除 %d 个键（%s），其余 %d 个键原样保留，重新 json.load 校验通过"
                  % (name, removed, "幂等：上次已经删过" if removed == 0 else "首次迁移",
                     len(again)))

    zh = load("zh_cn.json")[1]
    en = load("en_us.json")[1]

    # ① 双向断言：语言键侧
    for key in REMOVED_KEYS:
        if key in zh or key in en:
            PROBLEMS.append("键仍存在于语言文件：%s" % key)
    if not PROBLEMS:
        print("  [OK] 6 个「过程性播报」键在两份语言文件里都已不存在")

    # ① 双向断言：调用点侧
    hits = []
    for path in java_files():
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
        for key in REMOVED_KEYS:
            if key in text:
                hits.append("%s -> %s" % (os.path.relpath(path, ROOT), key))
    if hits:
        PROBLEMS.extend("调用点仍引用已删键：" + h for h in hits)
    else:
        print("  [OK] 全仓 .java 里不再出现这 6 个键的字面量（调用点已一并删除）")

    # ② 保留清单仍在
    missing = [key for key in KEPT_KEYS if key not in zh or key not in en]
    if missing:
        PROBLEMS.append("保留清单里的键被误删：%s" % missing)
    else:
        print("  [OK] 保留清单 %d 条（真实失败 / 监视器静态标签 / 一次性挂起横幅）都还在"
              % len(KEPT_KEYS))

    # ③ 中英键集合仍完全一致
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    if only_zh or only_en:
        PROBLEMS.append("中英键集合不一致：only_zh=%s only_en=%s" % (only_zh, only_en))
    else:
        print("  [OK] 中英键集合一致（各 %d 键）" % len(zh))

    if PROBLEMS:
        print()
        for problem in PROBLEMS:
            print("[X] " + problem)
    print("问题总数: %d" % len(PROBLEMS))
    return 1 if PROBLEMS else 0


if __name__ == "__main__":
    sys.exit(main())
