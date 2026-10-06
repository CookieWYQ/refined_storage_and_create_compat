# -*- coding: utf-8 -*-
"""本轮「框架批量取回 + 删掉废话提示」的语言文件改动（幂等，可重复执行）。

用户原话（验收标准）：
  * 「像什么创造模式、什么风格框架什么的，你不需要说明什么『已套上风格框架后，后面跟上那个
    什么已读、请连接』，没必要说明。」
  * 「取下来的时候也不需要说什么创造模式什么的、会消耗什么的、不返还什么的。这些废话都不需要说。」

判定标准（删除 / 保留）：
  * 删：套壳结果后面的补充说明后缀、以及取下时关于「创造模式 / 是否消耗 / 是否返还 / 无限版不返还」
    的解释性播报（连同那三个 `*_free` 键）；
  * 留：① 极简的操作结果（「已套上」「已取下」）；② 真正的失败 / 前置原因（不支持该方块、无权限）；
    ③ 「下一步怎么操作」的提示（潜行右键取下）——那是操作指引，不是收支解释。

用法：python tools/patch_frame_batch_takeoff_lang.py
"""

import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 统一改写（截断解释性后缀、去掉「是否返还」的措辞）
REWRITE = {
    # 分隔框架：结果只剩一句，删掉「（冻结当前的连接）」「：连接已恢复」
    "message.rs_create_compat.frame_sheathed": "已套上分隔框架",
    "message.rs_create_compat.frame_unsheathed": "已取下分隔框架",
    # 「怎么取下」的操作指引保留，但去掉「可取回框架 / 并回收」这类收支解释
    "message.rs_create_compat.frame_remove_hint": "这一格已套住；潜行右键取下",
    "message.rs_create_compat.frame_take_off_hint": "这一格已套住分隔框架；潜行右键取下",
    # 伪装框架：同上
    "block.rs_create_compat.camouflage_frame.hint.wrapped": "已裹上伪装",
    "block.rs_create_compat.camouflage_frame.hint.remove_hint": "这一格已经裹着伪装；潜行右键取下",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped": "已取下伪装",
    "block.rs_create_compat.camouflage_frame.hint.shell_removed": "已取下外壳方块",
}

REWRITE_EN = {
    "message.rs_create_compat.frame_sheathed": "Frame applied",
    "message.rs_create_compat.frame_unsheathed": "Frame removed",
    "message.rs_create_compat.frame_remove_hint": "Already framed; sneak + right-click to take it off",
    "message.rs_create_compat.frame_take_off_hint":
        "Separated frame here; sneak-right-click to take it off",
    "block.rs_create_compat.camouflage_frame.hint.wrapped": "(hint) Wrapped",
    "block.rs_create_compat.camouflage_frame.hint.remove_hint":
        "(hint) Already wrapped; sneak-right-click to take it off",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped": "(hint) Unwrapped",
    "block.rs_create_compat.camouflage_frame.hint.shell_removed": "Shell block taken back",
}

# 整条删除：这三句的全部内容都是「为什么没有东西可归还」的解释（用户明确要求删掉）
DELETE = [
    "message.rs_create_compat.frame_unsheathed_free",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped_free",
    "block.rs_create_compat.camouflage_frame.hint.shell_removed_free",
]

# 必须仍然存在的键（操作结果 / 失败原因 / 操作指引 —— 本轮保留清单）
KEEP = [
    "message.rs_create_compat.frame_sheathed",
    "message.rs_create_compat.frame_unsheathed",
    "message.rs_create_compat.frame_need_pipe",
    "message.rs_create_compat.frame_remove_hint",
    "message.rs_create_compat.frame_take_off_hint",
    "block.rs_create_compat.camouflage_frame.hint.wrapped",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped",
    "block.rs_create_compat.camouflage_frame.hint.remove_hint",
    "block.rs_create_compat.camouflage_frame.hint.take_off_hint",
    "block.rs_create_compat.camouflage_frame.hint.shell_removed",
    "block.rs_create_compat.camouflage_frame.hint.no_shell",
    "block.rs_create_compat.camouflage_frame.hint.pipe_only",
    "block.rs_create_compat.camouflage_frame.hint.shelled",
    "block.rs_create_compat.camouflage_frame.hint.occupied",
    "block.rs_create_compat.camouflage_frame.hint.rotated",
]

PROBLEMS = []


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def write(path, data):
    """与仓库既有 lang 脚本同一格式：ensure_ascii=False + 4 空格缩进 + 结尾换行。"""
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def patch(name, rewrite):
    path = os.path.join(LANG, name)
    data = json.loads(read(path))  # 先校验：文件必须是合法 JSON
    for key, value in rewrite.items():
        if key not in data:
            PROBLEMS.append("%s 缺少待改写键 %s" % (name, key))
            continue
        data[key] = value
    for key in DELETE:
        data.pop(key, None)
    write(path, data)
    # 写回后再读一次：落地内容必须是合法 JSON 且改动全部生效
    check = json.loads(read(path))
    for key, value in rewrite.items():
        if check.get(key) != value:
            PROBLEMS.append("%s 改写未生效：%s" % (name, key))
    for key in DELETE:
        if key in check:
            PROBLEMS.append("%s 删除未生效：%s" % (name, key))
    for key in KEEP:
        if key not in check or not check[key]:
            PROBLEMS.append("%s 保留键缺失或为空：%s" % (name, key))
    return check


print("=" * 80)
print("改写 / 删除框架相关语言键（中英成对）")
print("=" * 80)
zh = patch("zh_cn.json", REWRITE)
en = patch("en_us.json", REWRITE_EN)

print("删除清单（%d 条）：" % len(DELETE))
for key in DELETE:
    print("  - %s" % key)
print("改写清单（%d 条）：" % len(REWRITE))
for key, value in REWRITE.items():
    print("  - %s = %s" % (key, value))

# 中英成对 + 中文单条 ≤ 40 字
for key in list(REWRITE) + KEEP:
    if key not in zh or key not in en:
        PROBLEMS.append("中英未成对：%s" % key)
        continue
    if len(zh[key]) > 40:
        PROBLEMS.append("中文超过 40 字：%s（%d 字）" % (key, len(zh[key])))
if set(zh) != set(en):
    PROBLEMS.append("中英键集合不一致（差异 %s）" % sorted(set(zh) ^ set(en)))
for key in DELETE:
    if key in zh or key in en:
        PROBLEMS.append("删除键仍残留：%s" % key)

print()
print("本次涉及键的中文最长：%d 字（上限 40）"
      % max(len(zh[key]) for key in list(REWRITE) + KEEP))
print("问题总数: %d" % len(PROBLEMS))
for entry in PROBLEMS:
    print("  - %s" % entry)
print("=" * 80)
sys.exit(1 if PROBLEMS else 0)
