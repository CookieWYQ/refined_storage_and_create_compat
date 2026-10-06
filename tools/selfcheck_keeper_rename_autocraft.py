# -*- coding: utf-8 -*-
"""定量保持器「改名 + 自动合成默认关」静态自检（只读源码 / 语言文件，不跑游戏）。

对应本轮用户两条反馈：

① 改名：「物品定量保持器」→「资源定量保持器」（基础版 / 高级版，中英成对）
   —— **展示文案**里不得再出现旧名；「全工程」按两层检查：
      · 运行时资源（`src/main/resources/**`）的整文件文本里无旧名（这是真正会被玩家看到的层）；
      · Java 源码里的**字符串字面量**（"..."）不得含旧名（注释不计，注释不是展示文案）。

② 放入「自动合成升级」时：按钮启用，但自动合成**默认仍是关闭**，须玩家手动开
   —— 新机器的 `autoCraftEnabled` / `autoCraft[]` 初值必须为 `false`；
      「能力可用（hasAutocraftingUpgrade → 按钮 active）」与「开关状态」是两个概念；
      已有存档里已打开的开关必须由 NBT **原样读回**（绝不强制关掉）。

用法：python tools/selfcheck_keeper_rename_autocraft.py
末行固定为 `问题总数: N`。
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

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")
LANG_DIR = os.path.join(RES, "assets", "rs_create_compat", "lang")

BASIC_BE = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
BASIC_SCREEN = os.path.join(JAVA, "client", "screen", "QuantityKeeperScreen.java")
ADV_SCREEN = os.path.join(JAVA, "client", "screen", "AdvancedQuantityKeeperScreen.java")

OLD_ZH = "物品定量保持器"
NEW_ZH_BASIC = "资源定量保持器"
NEW_ZH_ADV = "高级资源定量保持器"
NEW_EN_BASIC = "Resource Quantity Keeper"
NEW_EN_ADV = "Advanced Resource Quantity Keeper"

STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')

problems = []
notes = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def require(ok, message):
    if ok:
        print("  [OK] %s" % message)
    else:
        problems.append(message)
        print("  [X]  %s" % message)


def load_lang(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def scan_residuals():
    """返回 (运行时资源命中, Java 字符串字面量命中, Java 注释/其它命中)。"""
    res_hits, literal_hits, java_raw_hits = [], [], []
    for base, _dirs, files in os.walk(RES):
        for name in files:
            if not name.endswith((".json", ".toml", ".mcmeta")):
                continue
            path = os.path.join(base, name)
            if OLD_ZH in read(path):
                res_hits.append(os.path.relpath(path, ROOT))
    for base, _dirs, files in os.walk(JAVA):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            text = read(path)
            if OLD_ZH not in text:
                continue
            for literal in STRING_LITERAL.findall(text):
                if OLD_ZH in literal:
                    literal_hits.append("%s: %s" % (os.path.relpath(path, ROOT), literal))
            java_raw_hits.append(os.path.relpath(path, ROOT))
    return res_hits, literal_hits, java_raw_hits


def main():
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")
    basic_be = read(BASIC_BE)
    adv_be = read(ADV_BE)
    basic_screen = read(BASIC_SCREEN)
    adv_screen = read(ADV_SCREEN)

    print("===== ① 改名：资源定量保持器（中英成对）=====")
    require(zh.get("block.rs_create_compat.quantity_keeper") == NEW_ZH_BASIC,
            "zh 基础方块名 = %s" % NEW_ZH_BASIC)
    require(zh.get("block.rs_create_compat.advanced_quantity_keeper") == NEW_ZH_ADV,
            "zh 高级方块名 = %s" % NEW_ZH_ADV)
    require(en.get("block.rs_create_compat.quantity_keeper") == NEW_EN_BASIC,
            "en 基础方块名 = %s" % NEW_EN_BASIC)
    require(en.get("block.rs_create_compat.advanced_quantity_keeper") == NEW_EN_ADV,
            "en 高级方块名 = %s" % NEW_EN_ADV)
    for key in ("advancements.rs_create_compat.quantity_keeper.description",
                "advancements.rs_create_compat.advanced_quantity_keeper.description",
                "advancements.rs_create_compat.keeper_autocraft.description"):
        require(NEW_ZH_BASIC in zh.get(key, ""), "zh 成就描述已用新名：%s" % key)
        require(en.get(key, "").find("Quantity Keeper") >= 0
                and "Item Quantity Keeper" not in en.get(key, ""),
                "en 成就描述已用一致译名：%s" % key)
    for value in list(zh.values()) + list(en.values()):
        if isinstance(value, str) and OLD_ZH in value:
            problems.append("语言值仍含旧名：%s" % value)

    res_hits, literal_hits, java_raw_hits = scan_residuals()
    require(not res_hits, "运行时资源无旧名（%d 个文件命中）" % len(res_hits))
    require(not literal_hits, "Java 字符串字面量无旧名（%d 处命中）" % len(literal_hits))
    if java_raw_hits:
        notes.append("Java 注释里仍有旧名（非展示文案，属其它 agent 拥有的文件）：%s"
                     % ", ".join(sorted(java_raw_hits)))

    print("===== ② 自动合成默认关闭：能力可用 ≠ 开关状态 =====")
    require("private boolean autoCraftEnabled = false;" in basic_be,
            "基础版新机开关初值 = false（默认关）")
    require("autoCraftEnabled = true" not in basic_be,
            "基础版源码不再存在「开关 = true」的初值 / 赋值")
    require("autoCraft[i] = false;" in adv_be,
            "高级版新机每槽开关初值 = false（默认关）")
    require("autoCraft[i] = true;" not in adv_be,
            "高级版源码不再存在「开关 = true」的初值")
    require("public boolean shouldAutoCraft() {\n        return autoCraftEnabled && hasAutocraftingUpgrade();"
            in basic_be,
            "基础版区分「能力（升级）」与「开关」：shouldAutoCraft = 开关 && 升级")
    require("return hasAutocraftingUpgrade() && isAutoCraft(slot);" in adv_be,
            "高级版区分「能力（升级）」与「开关」：shouldAutoCraft = 升级 && 开关")

    print("===== ② 按钮启用取决于「能力」，且已有存档开关原样保留 =====")
    require("autoCraftButton.active = hasUpgrade;" in basic_screen
            and "menu.hasAutocraftingUpgrade()" in basic_screen,
            "基础版按钮 active 取决于是否装有自动合成升级")
    require("autocraftButtons[row].active = menu.hasAutocraftingUpgrade();" in adv_screen,
            "高级版每槽按钮 active 取决于是否装有自动合成升级")
    require('autoCraftEnabled = tag.getBoolean("AutoCraftEnabled");' in basic_be,
            "基础版：缺字段（旧档 / 旧方块物品）一律按「关」读回 —— 放入升级只让按钮由禁用变可点，"
            "绝不自动打开；玩家手动打开过的 true 仍原样保留")
    require("autoCraft[i] = entry.getBoolean(TAG_CONFIG_AUTOCRAFT);" in adv_be,
            "高级版：每槽开关缺字段一律按「关」读回（同上），手动打开的 true 原样保留")
    require('!tag.contains("AutoCraftEnabled") || tag.getBoolean' not in basic_be
            and "!entry.contains(TAG_CONFIG_AUTOCRAFT) || entry.getBoolean" not in adv_be,
            "「缺字段 ⇒ 默认开」的旧回退已彻底删除（那正是用户报的「偶发性自动打开」来源）")

    print("===== ② 状态同步（ContainerData）+ 持久化 =====")
    require('tag.putBoolean("AutoCraftEnabled", autoCraftEnabled);' in basic_be
            and "case 5 -> autoCraftEnabled ? 1 : 0;" in basic_be,
            "基础版：开关写 NBT + 走 ContainerData 同步客户端")
    require("entry.putBoolean(TAG_CONFIG_AUTOCRAFT, autoCraft[i]);" in adv_be
            and "case DATA_SLOT_AUTOCRAFT -> shouldAutoCraft(slot) ? 1 : 0;" in adv_be,
            "高级版：每槽开关写 NBT + 走 ContainerData 同步客户端")

    print("=" * 60)
    for note in notes:
        print("提示：%s" % note)
    if problems:
        for problem in problems:
            print("[X] %s" % problem)
    print("问题总数: %d" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
