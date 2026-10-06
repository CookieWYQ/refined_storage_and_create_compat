# -*- coding: utf-8 -*-
"""给「输入总线延长型输入（全自动收回）」界面增补 / 更新语言键。

要求（用户规则）：语言键增删必须用 python 脚本改 assets/rs_create_compat/lang/*.json，
保持行序与缩进，写回前用 json.load 校验。

做法：
  1. 逐行文本处理（不重排、不重排缩进），新键插在既有 `importer_executor` 键块的最后一行之后；
  2. 需要改文案的既有键用正则原位替换（只动值，不动键与逗号）；
  3. 写回前 json.load 校验，并把「键集一致性 / 中文长度」一起打出来。

用法：python tools/add_lang_importer_auto.py
"""
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
ANCHOR = "gui.rs_create_compat.importer_executor.scroll.tip"

# 新增键（顺序 = 写入顺序）：(语言键后缀, zh, en)
NEW = [
    ("auto.on", "自动收回", "Auto reclaim"),
    ("auto.off", "手动收回", "Manual reclaim"),
    ("auto.title", "自动收回：中间产物 / 成品 / 废料",
     "Auto reclaim: intermediates, products, scraps"),
    ("auto.tip", "全自动：收回中间产物 / 成品 / 废料，输入原料不收。",
     "Auto: reclaims intermediates, products, scraps; inputs stay."),
    ("auto.toggle.tip", "点击切换自动 / 手动（手动模式自己勾选类别）。",
     "Click: switch auto / manual (manual lets you pick)."),
    ("auto.skip.tip", "自动模式：输入类不收回（避免把刚喂进去的料抽回来）。",
     "Auto mode: inputs are not reclaimed (avoids re-pulling)."),
    ("auto.collect.tip", "自动模式：这一类（中间产物 / 成品 / 废料）自动收回。",
     "Auto mode: this category is reclaimed into the network."),
    ("auto.locked.tip", "全自动收回中：类别条只读，切到手动才能勾选。",
     "Auto reclaim is on; bar is read-only. Switch to manual."),
    ("search.hint", "搜索类别", "Search"),
    ("search.tip", "按类别显示名过滤（输入即筛选）",
     "Filters categories by display name as you type."),
    ("search.filtered", "搜索「%s」：显示 %s / %s 个类别",
     'Search "%s": showing %s of %s categories.'),
    ("search.empty", "没有匹配的类别", "No matching categories"),
]

# 既有键的文案更新（与全自动 / 机器输出侧取货的新语义对齐）
UPDATE = {
    "locked.tip": ("已连到总线输出执行仓。手动模式：勾选要收回的类别。",
                   "Linked to a Bus Output chamber. Manual: pick categories."),
    "source.tip": ("取货：从执行仓内部存储与它供料的机器收回 RS 网络。",
                   "Source: the chamber's storage and the machines it feeds."),
    "strategy.materials": ("收回方向：只把该收回的东西收进网络（不反向供料）。",
                           "Reclaim mode: only reclaimed items go back to the network."),
}

PREFIX = "gui.rs_create_compat.importer_executor."


def update_lines(lines, values, lang_index):
    """原位替换既有键的值（只改值），缺键即报错——避免静默漏改。"""
    for suffix, pair in values.items():
        value = pair[lang_index - 1]  # UPDATE 的元组是 (zh, en)
        pattern = re.compile(r'^(\s*"%s%s":\s*)".*"(,?)$' % (re.escape(PREFIX), re.escape(suffix)))
        hit = False
        for index, line in enumerate(lines):
            match = pattern.match(line)
            if match:
                lines[index] = '%s%s%s' % (match.group(1), json.dumps(value, ensure_ascii=False),
                                           match.group(2))
                hit = True
                break
        if not hit:
            raise SystemExit("缺少既有键，无法更新: %s%s" % (PREFIX, suffix))
    return lines


def insert_lines(lines, lang_index):
    """把新增键插到锚点行之后（保持行序，只在既有键块末尾追加）。

    幂等：已经存在的键跳过，重复运行不会重复插入（也不会破坏 JSON 逗号）。
    """
    anchor_index = None
    for index, line in enumerate(lines):
        if '"%s"' % ANCHOR in line:
            anchor_index = index
            break
    if anchor_index is None:
        raise SystemExit("找不到锚点行: %s" % ANCHOR)
    # 锚点行必须带尾逗号（它后面还有其它键），否则插入会破坏 JSON
    if not lines[anchor_index].rstrip().endswith(","):
        raise SystemExit("锚点行没有尾逗号，插到它后面会破坏 JSON: %s" % lines[anchor_index])
    text = "\n".join(lines)
    block = []
    for suffix, zh_text, en_text in NEW:
        key = '"%s%s"' % (PREFIX, suffix)
        if key in text:
            continue  # 已经存在（重复运行）：不重复插入
        value = zh_text if lang_index == 1 else en_text
        block.append('  "%s%s": %s,' % (PREFIX, suffix, json.dumps(value, ensure_ascii=False)))
    return lines[:anchor_index + 1] + block + lines[anchor_index + 1:]


def patch(name, lang_index):
    path = os.path.join(LANG, name)
    with open(path, "r", encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    # 去掉末尾空行带来的空串，最后统一补回（split("\n") 会留一个尾部 ""）
    trailing = lines.pop() if lines and lines[-1] == "" else None
    lines = update_lines(lines, UPDATE, lang_index)
    lines = insert_lines(lines, lang_index)
    if trailing is not None:
        lines.append(trailing)
    text = "\n".join(lines)
    data = json.loads(text)  # 校验：解析不过就抛异常，绝不写坏文件
    for suffix, pair in list(UPDATE.items()):
        assert data[PREFIX + suffix] == pair[lang_index - 1], "%s 值不符: %s" % (name, suffix)
    for suffix, zh_text, en_text in NEW:
        expected = zh_text if lang_index == 1 else en_text
        assert data[PREFIX + suffix] == expected, "%s 值不符: %s" % (name, suffix)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("[OK] %s：%d 个键" % (name, len(data)))
    return data


if __name__ == "__main__":
    zh = patch("zh_cn.json", 1)
    en = patch("en_us.json", 2)
    problems = []
    if set(zh) != set(en):
        problems.append("zh / en 键集不一致")
    long_zh = [(k, len(v)) for k, v in zh.items() if k.startswith(PREFIX) and len(v) > 30]
    if long_zh:
        problems.append("中文超 30 字: %s" % long_zh)
    long_en = [(k, len(v)) for k, v in en.items() if k.startswith(PREFIX) and len(v) > 60]
    if long_en:
        problems.append("英文超 60 字符: %s" % long_en)
    print("zh 键数=%d  en 键数=%d" % (len(zh), len(en)))
    print("问题总数: %d" % len(problems))
    for problem in problems:
        print("  - " + problem)
    sys.exit(1 if problems else 0)
