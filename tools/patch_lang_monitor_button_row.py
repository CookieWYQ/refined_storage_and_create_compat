# -*- coding: utf-8 -*-
"""监视器处置按钮「本轮改动」的语言键增删（用脚本改，不手改 JSON）。

本轮改动（用户要求）：
  1. 删除本模组自己加的「取消」按钮 → 连带删掉它的两个键
     `gui.rs_create_compat.assembly.monitor.cancel` / `.cancel.tip`
     （RS 监视器原生已有「取消 / 取消全部」，本模组不再提供第二条取消入口）；
  2. 删除随之失效的服务端反馈键 `message.rs_create_compat.assembly.cancelled`；
  3. 把英文的「更换机器」压短为 `Reassign`：按钮要与原生「取消 / 全部取消」并排放在同一行，
     英文下 `Change machine`（78px 文本）会把两个按钮挤出面板右缘；`Reassign`（44px）能并排放下。
     中文「更换机器」只有 4 个字（36px），本来就放得下，保持不变。

校验：写回前后各 json.load 一次；中文单条 ≤ 40 字；中英键集合必须一致。
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

REMOVE_KEYS = [
    "gui.rs_create_compat.assembly.monitor.cancel",
    "gui.rs_create_compat.assembly.monitor.cancel.tip",
    "message.rs_create_compat.assembly.cancelled",
]
# 英文标签压短（中文不动）
EN_OVERRIDES = {
    "gui.rs_create_compat.assembly.monitor.change_machine": "Reassign",
}


def load(name):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, encoding="utf-8") as handle:
        return path, json.load(handle)


def dump(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    problems = []
    zh_path, zh = load("zh_cn.json")
    en_path, en = load("en_us.json")

    for key in REMOVE_KEYS:
        for name, data in (("zh_cn", zh), ("en_us", en)):
            if key not in data:
                problems.append("%s 里没有待删键 %s" % (name, key))
                continue
            del data[key]

    for key, value in EN_OVERRIDES.items():
        if key not in en:
            problems.append("en_us 里没有待改键 %s" % key)
            continue
        print("  [改] en_us %s: %r -> %r" % (key, en[key], value))
        en[key] = value

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1

    for name, key in (("zh_cn", "gui.rs_create_compat.assembly.monitor.change_machine"),
                      ("en_us", "gui.rs_create_compat.assembly.monitor.change_machine")):
        data = zh if name == "zh_cn" else en
        print("  [留] %s %s = %r（%d 字符）" % (name, key, data[key], len(data[key])))

    # 中文单条 ≤ 40 字：只对本轮新增 / 改动的键做约束（历史键早已定稿，不在本轮范围内）
    touched = set(EN_OVERRIDES) | {
        "gui.rs_create_compat.assembly.monitor.resume",
        "gui.rs_create_compat.assembly.monitor.resume.tip",
        "gui.rs_create_compat.assembly.monitor.change_machine.tip",
    }
    too_long = [k for k in touched if k in zh and len(zh[k]) > 40]
    if too_long:
        problems.append("中文单条超过 40 字：%s" % too_long)
    monitor_zh = sorted(k for k in zh if k.startswith("gui.rs_create_compat.assembly.monitor."))
    monitor_en = sorted(k for k in en if k.startswith("gui.rs_create_compat.assembly.monitor."))
    if monitor_zh != monitor_en:
        problems.append("监视器键集合中英不一致：%s / %s" % (monitor_zh, monitor_en))

    if problems:
        for problem in problems:
            print("[X] " + problem)
        return 1

    dump(zh_path, zh)
    dump(en_path, en)

    # 写回后重新 json.load 校验
    _, zh2 = load("zh_cn.json")
    _, en2 = load("en_us.json")
    assert zh2 == zh and en2 == en, "写回后重新解析结果不一致"
    for key in REMOVE_KEYS:
        assert key not in zh2 and key not in en2, "删除失败: " + key
    print("  [OK] 监视器语言键现在为 %s" % monitor_zh)
    print("  [OK] 两个语言文件写回后重新 json.load 一致，待删键均已消失")
    return 0


if __name__ == "__main__":
    sys.exit(main())
