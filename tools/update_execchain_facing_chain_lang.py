# -*- coding: utf-8 -*-
"""序列执行舱「链指向」改为方块朝向推导：清理 / 改写的语言键。

规则：
  * 行序 / 缩进 / 逗号一律保持原样，只按 key 精确删除或整行改写（不做 json.dump 重排）；
  * 写回后用 json.load 复验，并断言中文字数 / 英文长度上限。
"""
import io
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
P = "gui.rs_create_compat.sequence_execution_chamber."
M = "message.rs_create_compat."

# key -> 删除
DELETIONS = {
    P + "config.chain.link",
    P + "config.chain.link.hint",
    P + "config.chain.link.none",
    P + "config.chain.link.none.tip",
    P + "config.chain.link.dir",
    P + "config.chain.link.target",
    P + "config.chain.link.rule",
    P + "chain.tip.entry",
    M + "chamber_link_no_target",
    M + "chamber_link_cycle",
    M + "chamber_link_invalid",
    M + "chamber_link_fork",
    M + "chamber_link_too_long",
    M + "chamber_link_not_ready",
    M + "chamber_ray_fork_cleared",
    M + "chamber_ray_cycle_cleared",
}

# key -> (zh, en)
REWRITES = {
    P + "config.tip": (
        "打开配置子界面：设置配方类型与名字（链上改动整链生效）",
        "Open config: recipe type and name (chain-wide)",
    ),
    P + "config.confirm.tip": (
        "把配方类型 / 名字发给服务端保存，服务端校验后回发权威值。",
        "Send recipe type / name to server (reply is authoritative).",
    ),
    P + "config.chain.head.tip": (
        "链首 = 沿方块朝向走到的那一台；链上没有「只改这一台」。",
        "Head = chamber along facing; edits apply to the chain.",
    ),
}

LINE_RE = re.compile(r'^(\s*)"([^"\\]+)"\s*:\s*(.*?)(,?)\s*$')


def rewrite(path, index):
    with io.open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")

    out = []
    seen = set()
    removed, changed = [], []
    for line in lines:
        m = LINE_RE.match(line)
        if not m:
            out.append(line)
            continue
        indent, key, _value, comma = m.group(1), m.group(2), m.group(3), m.group(4)
        seen.add(key)
        if key in DELETIONS:
            removed.append(key)
            continue
        if key in REWRITES:
            value = REWRITES[key][index]
            out.append('%s"%s": %s%s' % (indent, key, json.dumps(value, ensure_ascii=False), comma))
            changed.append(key)
            continue
        out.append(line)

    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))

    # 复验：JSON 合法 + 删除键确实不存在 + 改写键的新值到位
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    missing = sorted(DELETIONS - seen)
    assert not missing, "待删键原本就不存在: %s" % missing
    for key in DELETIONS:
        assert key not in data, "删除失败: %s" % key
    for key, (zh, en) in REWRITES.items():
        assert data[key] == (zh if index == 0 else en), "改写失败: %s" % key
    return removed, changed, len(data)


def check_limits():
    limits = ((P + "config.tip", 30, 60),
              (P + "config.confirm.tip", 30, 60),
              (P + "config.chain.head.tip", 30, 60))
    for name, index in (("zh_cn.json", 0), ("en_us.json", 1)):
        with io.open(os.path.join(LANG_DIR, name), "r", encoding="utf-8") as f:
            data = json.load(f)
        for key, zh_max, en_max in limits:
            text = data[key]
            limit = zh_max if index == 0 else en_max
            assert len(text) <= limit, "%s %s 长度 %d > %d" % (name, key, len(text), limit)


if __name__ == "__main__":
    r0, c0, n0 = rewrite(os.path.join(LANG_DIR, "zh_cn.json"), 0)
    r1, c1, n1 = rewrite(os.path.join(LANG_DIR, "en_us.json"), 1)
    check_limits()
    print("zh_cn.json: 删除 %d 键, 改写 %d 键, 现有 %d 键" % (len(r0), len(c0), n0))
    print("  删除:", ", ".join(sorted(r0)))
    print("en_us.json: 删除 %d 键, 改写 %d 键, 现有 %d 键" % (len(r1), len(c1), n1))
    print("  json.load 复验通过；文案长度上限校验通过")
