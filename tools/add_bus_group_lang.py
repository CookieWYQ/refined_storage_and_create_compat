# -*- coding: utf-8 -*-
"""语言键补丁：总线「详细配置」子界面的 4 个新分组相关键（本轮 A/B/C/D 的 D 项）。

背景（为什么必须补这 4 个键）：
  本轮子界面把分组从 3 组扩成 5 组（物品输入 / 流体输入 / 中间产物（按步）/ 成品 / 废料），
  并把「第 N 步用哪台机器」写进**标签行文本**（用户硬要求：步骤区分信息放标签文本，
  不要自研物品 tooltip）。代码已经在用这些键：
    * ``group.collapse.tip``     —— 标签行 tooltip 里的「三角 = 折叠 / 展开本组」
    * ``group.results``          —— 成品组标题（空着会在游戏里直接显示语言键，用户看到乱码键）
    * ``group.scraps``           —— 废料组标题（用户原话：「废料肯定要显示」）
    * ``group.step.machine``     —— 标签行「第 N 步 · 机器名」（机器名由服务端从
      ``IAssemblyRecipe#addRequiredMachines`` 取来，见 ``BusCategoryInfo#stepMachine``）
  缺键时 ``Component.translatable`` 会把键名原样显示，因此这是必须补的界面缺陷，不是锦上添花。

做法：按「插入到指定锚点键之前」写入，保持文件原有的**字典序**与缩进；其余键一字不动；
      写完用 ``json.load`` 校验，并断言「中文单条 ≤ 40 字」。

用法：python tools/add_bus_group_lang.py
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
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
PREFIX = "gui.rs_create_compat.bus_config."

# (锚点键, 新键后缀, 新文本)：新键插入到「锚点键那一行之前」（因此锚点必须是字典序上紧随其后的键）
INSERTIONS = {
    "zh_cn.json": (
        ("group.fluids", "group.collapse.tip", "点左侧三角 = 折叠 / 展开本组（不影响勾选）"),
        ("group.step", "group.results", "成品"),
        ("group.step", "group.scraps", "废料"),
        ("group.step.unknown", "group.step.machine", "第 %s 步 · %s"),
    ),
    "en_us.json": (
        ("group.fluids", "group.collapse.tip", "Click the triangle to fold / unfold a group"),
        ("group.step", "group.results", "Results"),
        ("group.step", "group.scraps", "Scrap"),
        ("group.step.unknown", "group.step.machine", "Step %s · %s"),
    ),
}


def insert(name, anchor_suffix, key_suffix, value):
    path = os.path.join(LANG, name)
    with io.open(path, "r", encoding="utf-8") as handle:
        lines = handle.readlines()
    anchor = '"%s%s"' % (PREFIX, anchor_suffix)
    full_key = PREFIX + key_suffix
    if any(('"%s"' % full_key) in line for line in lines):
        print("[SKIP] %s：%s 已存在" % (name, full_key))
        return 0
    index = next((i for i, line in enumerate(lines) if anchor in line), -1)
    if index < 0:
        raise SystemExit("[X] %s：找不到锚点键 %s（未改动）" % (name, anchor))
    indent = lines[index][:len(lines[index]) - len(lines[index].lstrip())]
    lines.insert(index, '%s"%s": "%s",\n' % (indent, full_key, value))
    text = "".join(lines)
    data = json.loads(text)             # 结构校验：必须仍是合法 JSON（且无重复键覆盖）
    assert data[full_key] == value, "[X] %s：%s 写回值不一致" % (name, full_key)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("[OK] %s：+ %s = %s" % (name, full_key, value))
    return 1


def main():
    added = 0
    for name, entries in INSERTIONS.items():
        for anchor_suffix, key_suffix, value in entries:
            added += insert(name, anchor_suffix, key_suffix, value)
    zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
    assert set(zh) == set(en), "[X] 中英键集不一致"
    for _, key_suffix, _ in INSERTIONS["zh_cn.json"]:
        key = PREFIX + key_suffix
        assert key in zh and key in en, "[X] 缺键：%s" % key
        assert len(zh[key]) <= 40, "[X] 中文超长（%d 字）：%s" % (len(zh[key]), key)
    # 分组标题必须是纯名词（界面会在它后面追加「已选 / 全部」，带标点会显得脏）
    for key_suffix in ("group.results", "group.scraps"):
        assert zh[PREFIX + key_suffix] == zh[PREFIX + key_suffix].strip()
    print("[OK] 完成：新增 %d 行；键集中英一致（各 %d 键）；中文单条 <= 40 字" % (added, len(zh)))


if __name__ == "__main__":
    main()
