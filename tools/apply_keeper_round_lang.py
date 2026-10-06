# -*- coding: utf-8 -*-
"""定量保持器本轮改动的语言键增删（用 python 脚本改 JSON，不手写整份文件）。

改动内容
--------
1. 删除「已存 / 目标」那一段文案（用户要求：高级版界面不再显示这一部分）：
     gui.rs_create_compat.advanced_quantity_keeper.stored  （「已存 %s」）
     gui.rs_create_compat.advanced_quantity_keeper.target  （「目标：%s」）
   顺带删掉同一界面里早已不用的历史遗留键（「开 / 关」「合成 / 销毁 开关态」整行文字）：
     ...advanced_quantity_keeper.off / .on / .state.autocraft / .state.destroy
     ...advanced_quantity_keeper.state_off / .state_on
2. 新增「同一资源多台保持器」的仲裁说明（中英成对，中文 <= 40 字）：
     gui.rs_create_compat.advanced_quantity_keeper.overflow_shared.tip
     gui.rs_create_compat.quantity_keeper.destroy_on.tip / destroy_off.tip / destroy_shared.tip
3. 基础版「销毁过量」开关补上 tooltip（用户硬规则：GUI 上的功能必须能悬停看到说明），
   并说明「开启 = 只销毁超出目标的那部分；关闭 = 一个都不销毁」。

校验：json.load 读回；中英键集合必须完全一致；新增中文文案长度 <= 40。

用法：python tools/apply_keeper_round_lang.py
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

ADV = "gui.rs_create_compat.advanced_quantity_keeper."
BASIC = "gui.rs_create_compat.quantity_keeper."

REMOVE = [
    ADV + "stored",
    ADV + "target",
    ADV + "off",
    ADV + "on",
    ADV + "state.autocraft",
    ADV + "state.destroy",
    ADV + "state_off",
    ADV + "state_on",
]

SHARED_ZH = "同一资源由多台保持时，仅坐标最小的一台生效"
SHARED_EN = "With several keepers on the same resource, only the lowest-coordinate one acts"

ADD = {
    ADV + "overflow_shared.tip": (SHARED_ZH, SHARED_EN),
    BASIC + "destroy_on.tip": ("已开启：超出目标的那部分会被销毁", "ON: the surplus above the target is destroyed"),
    BASIC + "destroy_off.tip": ("已关闭：一个都不销毁，超出部分原样保留", "OFF: nothing is destroyed, surplus is kept"),
    BASIC + "destroy_shared.tip": (SHARED_ZH, SHARED_EN),
}

ZH_MAX = 40


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def newline_of(name):
    """沿用文件原有的行尾（本工程语言文件是 CRLF，改写时不能悄悄变成 LF）。"""
    with open(os.path.join(LANG_DIR, name), "rb") as handle:
        return "\r\n" if b"\r\n" in handle.read(4096) else "\n"


def dump(name, data):
    text = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    text = text.replace("\n", newline_of(name))
    with io.open(os.path.join(LANG_DIR, name), "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def main():
    zh = load("zh_cn.json")
    en = load("en_us.json")
    problems = []

    # 幂等：已经删过的键直接跳过（重复运行不会报错）
    for key in REMOVE:
        for data in (zh, en):
            data.pop(key, None)

    for key, (zh_text, en_text) in ADD.items():
        if len(zh_text) > ZH_MAX:
            problems.append("中文超 %d 字（%d）：%s" % (ZH_MAX, len(zh_text), key))
        zh[key] = zh_text
        en[key] = en_text

    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    for key in only_zh:
        problems.append("仅 zh_cn 有该键：%s" % key)
    for key in only_en:
        problems.append("仅 en_us 有该键：%s" % key)

    if problems:
        print("存在问题，未写回：")
        for problem in problems:
            print("  - %s" % problem)
        return 1

    dump("zh_cn.json", zh)
    dump("en_us.json", en)
    print("[OK] zh_cn %d 键 / en_us %d 键；删除 %d 个、新增 %d 个，键集合一致"
          % (len(zh), len(en), len(REMOVE), len(ADD)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
