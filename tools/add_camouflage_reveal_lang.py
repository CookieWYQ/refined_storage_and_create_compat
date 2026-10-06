# -*- coding: utf-8 -*-
"""把「伪装框架第 7 轮」新增的语言键写进 zh_cn / en_us（幂等，可重复执行）。

为什么要脚本而不是手改：
  * 语言文件是「一行一键」的格式，手插一行很容易漏掉/多加逗号（JSON 立刻坏掉）；
  * 中英必须成对（tools/verify_lang_simplify.py 会检查键集合完全一致、占位符数量一致）；
  * 本脚本每次都先用 json.load 复核语法，再打印最终长度（中文目标 <=40 字）。

本轮新增（含出处）：
  1) key.rs_create_compat.toggle_camouflage_reveal            → client/CamouflageKeybinds（K 键，可在原版按键绑定界面改）
  2) message.rs_create_compat.camouflage_reveal.hidden        → support/RsccCamouflage + network/ToggleCamouflageRevealPacket
  3) message.rs_create_compat.camouflage_reveal.visible       → 同上（再次按 K 恢复）
  4) message.rs_create_compat.camouflage_reveal.no_material   → 还是「空壳」，没有可隐藏的填充方块
  5) message.rs_create_compat.camouflage_reveal.need_goggles  → 没戴护目镜（create:goggles，Create 的 GogglesItem）
  6) message.rs_create_compat.camouflage_reveal.no_target     → 准星没指向「已填充方块的伪装格」
  7) jade.rs_create_compat.camouflaged                        → support/RsccCamouflageDisplay（「原部件类型」+ 这一句）

用法: python tools/add_camouflage_reveal_lang.py
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

# (键, 中文, 英文, 插入锚点：插在同一前缀的最后一条之后；None = 追加到文件末尾)
NEW = [
    ("key.rs_create_compat.toggle_camouflage_reveal",
     "隐藏/恢复填充方块",
     "Hide/Restore Filled Block",
     "key.rs_create_compat."),
    ("message.rs_create_compat.camouflage_reveal.hidden",
     "已隐藏填充方块：只显示框架",
     "Filled block hidden: frame only",
     "message.rs_create_compat."),
    ("message.rs_create_compat.camouflage_reveal.visible",
     "已恢复显示填充方块",
     "Filled block shown again",
     "message.rs_create_compat."),
    ("message.rs_create_compat.camouflage_reveal.need_goggles",
     "需要佩戴护目镜",
     "Requires goggles",
     "message.rs_create_compat."),
    ("jade.rs_create_compat.camouflaged",
     "%s（被伪装）",
     "%s (Camouflaged)",
     None),
]

MAX_ZH = 40

# 本轮被取代 / 不再被任何代码引用的语言键（K 改成全局切换后：
#   no_target  = 旧的「准星没有指向已伪装的方块」（不再按准星取格）
#   no_material= 旧的「这一格还没有填充方块」（不再按单格判定）
# 留在这里会让「语言里声明但没人用」的孤儿键越积越多，因此显式删除（幂等）。
REMOVE = [
    "message.rs_create_compat.camouflage_reveal.no_target",
    "message.rs_create_compat.camouflage_reveal.no_material",
]


def split_key(line):
    """键值行 → (键, 结束符)；不是键值行返回 (None, 结束符)。"""
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    stripped = body.strip()
    if not stripped.startswith('"') or '":' not in stripped:
        return None, ending
    return stripped[1:stripped.index('":')], ending


def normalise_commas(lines):
    """规范化行尾逗号：除最后一个键行外都必须带逗号，最后一个键行必须不带。

    插入/追加都可能把「原本的最后一行」或「刚插入的一行」变成最后一行；
    与其在每条插入分支里各判一次，不如收尾统一修一遍（也与文件既有格式一致）。
    """
    key_pos = [i for i, line in enumerate(lines) if split_key(line)[0] is not None]
    if not key_pos:
        return lines
    last = key_pos[-1]
    out = []
    for i, line in enumerate(lines):
        if i not in key_pos:
            out.append(line)  # '{' / '}'
            continue
        body = line.rstrip("\r\n")
        ending = line[len(body):]
        body = body.rstrip().rstrip(",").rstrip()
        if i != last:
            body += ","
        out.append(body + (ending or os.linesep))
    return out


def apply(path, column):
    """把 NEW 的第 column 列（0=中文 / 1=英文）写进该文件，并删掉 REMOVE 里的键。返回 (新增数, 字典)。"""
    with io.open(path, encoding="utf-8", newline="") as f:
        lines = f.readlines()

    # 先删：整行匹配「该键 + 冒号」，删完由下面的逗号规范化收尾
    removed = 0
    kept = []
    for line in lines:
        key, _ = split_key(line)
        if key is not None and key in REMOVE:
            removed += 1
            continue
        kept.append(line)
    lines = kept

    existing = {}
    for i, line in enumerate(lines):
        key, _ = split_key(line)
        if key is not None:
            existing[key] = i

    written = 0
    for key, zh, en, anchor in NEW:
        value = zh if column == 0 else en
        text = json.dumps(value, ensure_ascii=False)
        if key in existing:
            # 幂等：键已存在则只重写值（保持行序），不重复插入
            i = existing[key]
            _, ending = split_key(lines[i])
            lines[i] = '  "%s": %s,%s' % (key, text, ending or os.linesep)
            continue

        if anchor is None:
            insert_at = len(lines) - 1  # 追加到最后一个键之后（']}' 之前）
        else:
            hits = [pos for k, pos in existing.items() if k.startswith(anchor)]
            if not hits:
                raise SystemExit("找不到插入锚点：%s" % anchor)
            insert_at = max(hits) + 1
        # 上一行必须带逗号（它后面马上要有新键）
        prev = lines[insert_at - 1].rstrip("\r\n")
        if not prev.rstrip().endswith(","):
            lines[insert_at - 1] = prev + ",\n"
        ending = lines[insert_at - 1][len(lines[insert_at - 1].rstrip("\r\n")):]
        lines.insert(insert_at, '  "%s": %s,%s' % (key, text, ending or os.linesep))
        # 重新计算索引（后面还要继续插）
        existing = {}
        for i, line in enumerate(lines):
            k, _ = split_key(line)
            if k is not None:
                existing[k] = i
        written += 1

    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.writelines(normalise_commas(lines))

    # 复核：语法 + 键数 = 行数 - 2（文件结构：'{' + 每键一行 + '}'）
    with io.open(path, encoding="utf-8") as f:
        loaded = json.load(f)
    assert len(loaded) == len(lines) - 2, "键数量与行数不匹配：%s" % path
    return written, removed, loaded


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")
    n_zh, r_zh, zh = apply(zh_path, 0)
    n_en, r_en, en = apply(en_path, 1)
    print("zh_cn.json 新增 %d 键 / 删除 %d 键（共 %d）；en_us.json 新增 %d 键 / 删除 %d 键（共 %d）"
          % (n_zh, r_zh, len(zh), n_en, r_en, len(en)))

    problems = []
    if sorted(zh) != sorted(en):
        problems.append("键集合不一致：仅中文 %s / 仅英文 %s"
                        % (sorted(set(zh) - set(en)), sorted(set(en) - set(zh))))
    print("\n%4s %4s  %s" % ("zh", "en", "key"))
    for key, zh_value, en_value, _anchor in NEW:
        lz, le = len(zh.get(key, "")), len(en.get(key, ""))
        flag = ""
        if zh.get(key, "") != zh_value or en.get(key, "") != en_value:
            flag += "  <== 值被改动过（脚本不覆盖已存在键之外的情况，请人工确认）"
        if lz > MAX_ZH:
            flag += "  <== 超出中文目标 %d" % MAX_ZH
            problems.append("中文过长：%s（%d 字）" % (key, lz))
        if len(zh.get(key, "").split("%s")) != len(en.get(key, "").split("%s")):
            problems.append("占位符数量不一致：%s" % key)
        print("%4d %4d  %s%s" % (lz, le, key, flag))

    if problems:
        print("\n问题：")
        for p in problems:
            print("  - %s" % p)
        return 1
    print("\n全部键已写入，中英成对、占位符一致、中文长度合规。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
