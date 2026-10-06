# -*- coding: utf-8 -*-
"""本轮（分隔框架 / 伪装框架 6 条反馈）的 JSON 补丁脚本：**只改 JSON，不手写**。

为什么必须是脚本（工程硬规则）：配方与语言键是「有格式要求的数据文件」，
手写容易漏键 / 漏语言 / 写出非法 JSON；本脚本逐项写入后用 ``json.load`` 回读校验，
并断言「本轮新增 / 改写的每一条中文 ≤ 40 字」。

改了三件事：
  A. 配方：伪装框架产出 2 → **32**（分隔框架保持 64，本脚本不碰它）；
  B/C. 语言键（中英同步）：
     * **删除** ``message.rs_create_compat.frame_chain_sheathed``（「已连锁套壳 %s 格」
       —— 用户要求不显示「已连续套壳 N 格」这类进度提示）；
     * 改写 ``frame_sheathed`` / ``frame_need_pipe`` / 两个框架的 help / 伪装的两条提示
       （文案与新行为对齐：批量套壳、可套范围放宽、同种方块右键转 90°）；
     * **新增** ``block.rs_create_compat.camouflage_frame.hint.rotated``。

用法：python tools/patch_round6_frames.py
退出码 0 = 写入成功且回读校验通过。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
DATA = os.path.join(RES, "data", "rs_create_compat")
ASSETS = os.path.join(RES, "assets", "rs_create_compat")
RECIPE = os.path.join(DATA, "recipe", "camouflage_frame.json")
LANG_ZH = os.path.join(ASSETS, "lang", "zh_cn.json")
LANG_EN = os.path.join(ASSETS, "lang", "en_us.json")

CAMO = "block.rs_create_compat.camouflage_frame"
SEP = "block.rs_create_compat.separation_frame"
INF_SEP = "block.rs_create_compat.infinite_separation_frame"
MSG = "message.rs_create_compat"

#: 删除的键（进度型提示：一律移除，不留孤儿）
REMOVED_KEYS = ["%s.frame_chain_sheathed" % MSG]

#: 改写 / 新增的键：key -> (中文, 英文)；中文单条 ≤ 40 字
LANG = {
    # 分隔框架：批量手势说清楚（不潜行 = 沿视线连套一片；潜行 = 只套这一格）
    "%s.help" % SEP:
        ("右键线缆/管道套住该格（沿视线连套一片）；潜行只套一格，潜行右键取下。",
         "Right-click a cable/pipe to frame it (chains along your view); sneak = single cell, sneak-right-click = remove."),
    "%s.help" % INF_SEP:
        ("同分隔框架，但套上不消耗、取下不回收，外壳带附魔光泽。",
         "Like the Separation Frame, but never consumed and never refunded; the shell glows."),
    # 伪装框架：把「批量 + 转壳 + 取外壳」三条手势写进 tooltip
    "%s.help" % CAMO:
        ("伪装框架：右键可套方块裹上（沿视线连裹一片）；同种方块右键转 90°。",
         "Camouflage Frame: right-click an applicable block to wrap it (chains along your view); same block again = rotate 90 deg."),
    # 不可套的方块（空气 / 流体 / 可替换方块 / 非整格外形）
    "%s.hint.pipe_only" % CAMO:
        ("只能套在完整方块或线缆/管道上（空气、流体、可替换方块不行）",
         "(hint) Only full blocks, cables and pipes can be wrapped (not air, fluids or replaceable blocks)"),
    # 同种外壳但没有可旋转的朝向（rotate() 返回原状态）
    "%s.hint.occupied" % CAMO:
        ("已是这种外壳（无朝向可转）；换壳请潜行右键先取下",
         "(hint) Same shell already (nothing to rotate); sneak-right-click to take it off first"),
    # 新增：外壳朝向已旋转
    "%s.hint.rotated" % CAMO:
        ("外壳朝向已转 90°（同种方块再右键继续转）",
         "(hint) Shell rotated 90 deg (right-click again with the same block to keep turning)"),
    # 结果型反馈：不带格数（用户要求不要「已连续套壳 N 格」）
    "%s.frame_sheathed" % MSG:
        ("已套上分隔框架（冻结当前的连接）",
         "Frame applied (current connections are frozen)"),
    "%s.frame_need_pipe" % MSG:
        ("只能套在完整方块或线缆/流体管道上",
         "(hint) Only full blocks, cables and fluid pipes can be framed"),
}


def load(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def dump(path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def patch_recipe():
    recipe = load(RECIPE)
    before = recipe["result"].get("count", 1)
    recipe["result"]["count"] = 32
    dump(RECIPE, recipe)
    again = load(RECIPE)                       # 回读校验：写坏了立刻发现
    assert again["result"]["count"] == 32, again["result"]
    assert again["result"]["id"] == "rs_create_compat:camouflage_frame", again["result"]
    assert again["type"] == "minecraft:crafting_shaped", again["type"]
    print("[recipe] camouflage_frame 产出 %s -> %s（回读一致）" % (before, again["result"]["count"]))

    sep = load(os.path.join(DATA, "recipe", "separation_frame.json"))
    assert sep["result"]["count"] == 64, sep["result"]
    print("[recipe] separation_frame 产出保持 %s（本脚本不碰分隔框架）" % sep["result"]["count"])


def patch_lang(path, language):
    lang = load(path)
    for key in REMOVED_KEYS:
        lang.pop(key, None)
    for key, texts in LANG.items():
        text = texts[0] if language == "zh" else texts[1]
        if language == "zh":
            assert len(text) <= 40, "中文超 40 字：%s -> %s" % (key, text)
        lang[key] = text
    dump(path, lang)
    again = load(path)                         # 回读校验
    for key in REMOVED_KEYS:
        assert key not in again, key
    for key, texts in LANG.items():
        expected = texts[0] if language == "zh" else texts[1]
        assert again.get(key) == expected, (key, again.get(key))
    print("[lang:%s] 删除 %d 键、写入 %d 键（回读一致）"
          % (language, len(REMOVED_KEYS), len(LANG)))
    return again


def main():
    assert os.path.exists(RECIPE), RECIPE
    patch_recipe()
    zh = patch_lang(LANG_ZH, "zh")
    en = patch_lang(LANG_EN, "en")
    missing = [key for key in LANG if key not in en]
    assert not missing, missing
    # 中英键集合一致性抽查（本轮涉及的键两种语言都必须有）
    for key in list(LANG) + REMOVED_KEYS:
        assert key not in REMOVED_KEYS or key not in zh, key
    print("[lang] zh=%d 键 / en=%d 键（中英同步）" % (len(zh), len(en)))
    print("[result] 全部写入并回读校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
