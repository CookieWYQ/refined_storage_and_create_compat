# -*- coding: utf-8 -*-
"""把「成片套壳只在 FTB Ultimine 的连锁模式下发生」这一约定写进中英文语言。

为什么要脚本而不是手改：本仓库规则要求「有格式要求（json）的文件用 python 脚本改写」，
并且中文单条 ≤ 40 字；脚本在写回前与写回后都用 json.load 校验，绝不把语言文件写坏。

改动内容（只改这两条 help，其余行逐字保留）：
  * block.rs_create_compat.separation_frame.help   —— 清掉上一版的「沿视线连套一片」，
    改成「默认右键只套命中那一格；成片要靠 FTB 的连锁键」；
  * block.rs_create_compat.camouflage_frame.help   —— 同上（伪装框架与分隔框架同一条约定），
    并保留「同种方块右键转向」这条既有提示。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

SEPARATION_HELP = "block.rs_create_compat.separation_frame.help"
CAMOUFLAGE_HELP = "block.rs_create_compat.camouflage_frame.help"

# 中文单条 ≤ 40 字；英文沿用本仓库既有脚本的口径 ≤ 60（新写的那一条）
EDITS = {
    "zh_cn.json": {
        SEPARATION_HELP: "右键套住线缆/管道；潜行右键取下；按住 FTB 连锁键可成片套壳。",
        CAMOUFLAGE_HELP: "伪装框架：右键裹上；同种方块右键转向；FTB 连锁键可成片裹壳。",
    },
    "en_us.json": {
        SEPARATION_HELP: "Frame one cell; hold FTB Ultimine to frame a run.",
        CAMOUFLAGE_HELP: "Camouflage: wrap one cell; hold FTB Ultimine to wrap a run.",
    },
}


def patch(filename, edits, limit):
    path = os.path.join(LANG_DIR, filename)
    with open(path, "rb") as handle:
        raw = handle.read()
    terminator = "\r\n" if b"\r\n" in raw else "\n"
    text = raw.decode("utf-8")
    # 写回前先校验：内容非法（比如上次改坏了）就直接退出，不叠加新的损坏
    data = json.loads(text)
    lines = text.replace("\r\n", "\n").split("\n")

    for key, value in edits.items():
        if len(value) > limit:
            print("[X] %s 文案过长（%d > %d）: %s" % (key, len(value), limit, value))
            return False
        if key not in data:
            print("[X] %s 语言文件里缺少 %s" % (filename, key))
            return False

    rewritten = 0
    for key, value in edits.items():
        quoted = '"%s":' % key
        for i, line in enumerate(lines):
            if line.lstrip().startswith(quoted):
                indent = line[:len(line) - len(line.lstrip())]
                comma = "," if line.rstrip().endswith(",") else ""
                lines[i] = "%s%s: %s%s" % (indent, json.dumps(key, ensure_ascii=False),
                                           json.dumps(value, ensure_ascii=False), comma)
                rewritten += 1
                break
        else:
            print("[X] %s 找不到 %s 那一行" % (filename, key))
            return False

    new_text = "\n".join(lines)
    check = json.loads(new_text)  # 写回前再校验一次（保证仍是合法 json）
    for key, value in edits.items():
        if check.get(key) != value:
            print("[X] %s %s 写入不匹配: %r" % (filename, key, check.get(key)))
            return False
    if len(check) != len(data):
        print("[X] %s 键数量变化（%d -> %d），说明有行被误改" % (filename, len(data), len(check)))
        return False

    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(new_text.replace("\n", terminator))
    print("  [OK] %s 改写 %d 行（共 %d 键，上限 %d 字），写入值 = %s"
          % (filename, rewritten, len(check), limit,
             " / ".join("%d 字" % len(v) for v in edits.values())))
    return True


if __name__ == "__main__":
    ok = patch("zh_cn.json", EDITS["zh_cn.json"], 40) and patch("en_us.json", EDITS["en_us.json"], 60)
    sys.exit(0 if ok else 1)
