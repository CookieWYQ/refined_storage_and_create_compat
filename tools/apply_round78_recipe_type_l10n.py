# -*- coding: utf-8 -*-
"""第 78 轮：**配方类型显示名本地化**（含别的模组的配方类型）的语言键写入。

为什么需要这个脚本
------------------
用户截图里「配方类型」菜单中英混杂：{@code create:pressing} 是中文，而
{@code createbigcannons:big_cartridge_filling} 一类是英文。根因不是解析写错，而是
**对方 jar 里根本没有这些键**（实测 Create Big Cannons 5.11.7 的 zh_cn.json 只有
{@code recipe.createbigcannons.melting} 等 5 个配方类键，6 个装填 / 组装 / 引信类类型一个都没有）。
因此本轮在「对方官方翻译 → 从 id 派生」之间加一张**我们自己的词典**，并补一张**派生用的单词表**。

写入的键
--------
1. {@code machine.rs_create_compat.recipe_type.<命名空间>.<路径>} —— 我们自己的词典（优先级最高）。
   本轮只补「第三方确认没有翻译」的机械动力：火炮 12 条（其余 4 条它自己有官方键，让官方键优先）。
2. {@code machine.rs_create_compat.word.<英文词>} —— 派生单词表（中英各一份）。
   它只在「词典 + 对方官方键都没有」时才参与拼接；中文值决定「不加空格」的拼接形态。

规范：键的增删改一律走 python 脚本（保持既有行序 + 2 空格缩进），写回后立刻 json.load 回读校验。
用法: python tools/apply_round78_recipe_type_l10n.py
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

DICT = "machine.rs_create_compat.recipe_type."
WORD = "machine.rs_create_compat.word."

# ---------------------------------------------------------------- 1) 我们自己的词典
# 机械动力：火炮（createbigcannons）—— 它的 jar 里**没有**这些配方类型的翻译（见自检的 jar 证据段）。
# 命名沿用该模组自己的中文术语：big_cartridge=大型弹药、autocannon_ammo_container=机炮弹药箱、
# fuze=引信、tracer=曳光弹；`_deployer` 变体 = 同一条配方改由「机械手」自动执行（Create 官方中文就叫机械手）。
CBC = [
    ("autocannon_ammo_container_filling", "机炮弹药箱装填", "Autocannon Ammo Container Filling"),
    ("autocannon_ammo_container_filling_deployer", "机炮弹药箱装填（机械手）",
     "Autocannon Ammo Container Filling (Deployer)"),
    ("big_cartridge_filling", "大型弹药装填", "Big Cartridge Filling"),
    ("big_cartridge_filling_deployer", "大型弹药装填（机械手）", "Big Cartridge Filling (Deployer)"),
    ("cartridge_assembly", "弹药组装", "Cartridge Assembly"),
    ("cartridge_assembly_deployer", "弹药组装（机械手）", "Cartridge Assembly (Deployer)"),
    ("fuze_removal", "拆除引信", "Fuze Removal"),
    ("munition_fuzing", "安装引信", "Munition Fuzing"),
    ("munition_fuzing_deployer", "安装引信（机械手）", "Munition Fuzing (Deployer)"),
    ("tracer_application", "加装曳光弹", "Tracer Application"),
    ("tracer_application_deployer", "加装曳光弹（机械手）", "Tracer Application (Deployer)"),
    ("tracer_removal", "拆除曳光弹", "Tracer Removal"),
]

# ---------------------------------------------------------------- 2) 派生单词表
# 只在「我们没写整条词典 + 对方模组也没给翻译」时才用；单词命中就替换，未命中保留首字母大写的英文词。
WORDS = {
    "air": ("空气", "Air"),
    "alloying": ("合金化", "Alloying"),
    "ammo": ("弹药", "Ammo"),
    "application": ("施加", "Application"),
    "assembly": ("组装", "Assembly"),
    "autocannon": ("机炮", "Autocannon"),
    "beam": ("光束", "Beam"),
    "big": ("大型", "Big"),
    "blasting": ("高炉冶炼", "Blasting"),
    "boring": ("钻孔", "Boring"),
    "bullet": ("子弹", "Bullet"),
    "burning": ("燃烧", "Burning"),
    "campfire": ("营火", "Campfire"),
    "cannon": ("火炮", "Cannon"),
    "cartridge": ("弹药", "Cartridge"),
    "casting": ("铸造", "Casting"),
    "centrifugal": ("离心", "Centrifugal"),
    "charging": ("充能", "Charging"),
    "coating": ("镀膜", "Coating"),
    "coiling": ("绕线", "Coiling"),
    "compacting": ("压缩", "Compacting"),
    "container": ("容器", "Container"),
    "cooking": ("烹饪", "Cooking"),
    "crafting": ("合成", "Crafting"),
    "crushing": ("粉碎", "Crushing"),
    "cutting": ("切割", "Cutting"),
    "deployer": ("机械手", "Deployer"),
    "deploying": ("部署", "Deploying"),
    "discharging": ("放电", "Discharging"),
    "distilling": ("蒸馏", "Distilling"),
    "drilling": ("钻孔", "Drilling"),
    "drying": ("干燥", "Drying"),
    "electrolysis": ("电解", "Electrolysis"),
    "emptying": ("倒空", "Emptying"),
    "energy": ("能量", "Energy"),
    "enriching": ("富集", "Enriching"),
    "exposure": ("曝光", "Exposure"),
    "extruding": ("挤出", "Extruding"),
    "fermenting": ("发酵", "Fermenting"),
    "filling": ("装填", "Filling"),
    "freezing": ("冷冻", "Freezing"),
    "fuel": ("燃料", "Fuel"),
    "fuze": ("引信", "Fuze"),
    "gas": ("气体", "Gas"),
    "grinding": ("研磨", "Grinding"),
    "hammering": ("锤打", "Hammering"),
    "haunting": ("缠魂", "Haunting"),
    "heating": ("加热", "Heating"),
    "infusing": ("灌注", "Infusing"),
    "item": ("物品", "Item"),
    "copying": ("复制", "Copying"),
    "toolbox": ("工具箱", "Toolbox"),
    "dyeing": ("染色", "Dyeing"),
    "laser": ("激光", "Laser"),
    "liquid": ("液体", "Liquid"),
    "melting": ("熔融", "Melting"),
    "milling": ("研磨", "Milling"),
    "mixing": ("混合", "Mixing"),
    "munition": ("弹药", "Munition"),
    "packing": ("打包", "Packing"),
    "polishing": ("抛光", "Polishing"),
    "pressing": ("冲压", "Pressing"),
    "printing": ("打印", "Printing"),
    "processing": ("加工", "Processing"),
    "projectile": ("弹丸", "Projectile"),
    "removal": ("拆除", "Removal"),
    "rocket": ("火箭", "Rocket"),
    "rolling": ("轧制", "Rolling"),
    "sanding": ("打磨", "Sanding"),
    "sawing": ("锯切", "Sawing"),
    "seeding": ("播种", "Seeding"),
    "shaped": ("有序", "Shaped"),
    "shapeless": ("无序", "Shapeless"),
    "shell": ("炮弹", "Shell"),
    "small": ("小型", "Small"),
    "smelting": ("熔炼", "Smelting"),
    "smithing": ("锻造", "Smithing"),
    "smoking": ("烟熏", "Smoking"),
    "soaking": ("浸泡", "Soaking"),
    "splashing": ("洗涤", "Splashing"),
    "steam": ("蒸汽", "Steam"),
    "stonecutting": ("切石", "Stonecutting"),
    "tracer": ("曳光弹", "Tracer"),
    "turning": ("车削", "Turning"),
    "unpacking": ("拆包", "Unpacking"),
    "vibrating": ("振动", "Vibrating"),
    "water": ("水", "Water"),
    "weaving": ("编织", "Weaving"),
    "welding": ("焊接", "Welding"),
}


def build_add():
    """返回 {"zh_cn": {...}, "en_us": {...}}（键全部是新增的；已存在则覆盖为同一份文案）。"""
    zh, en = {}, {}
    for path, zh_name, en_name in CBC:
        key = DICT + "createbigcannons." + path
        zh[key], en[key] = zh_name, en_name
    for word, (zh_word, en_word) in WORDS.items():
        key = WORD + word
        zh[key], en[key] = zh_word, en_word
    return {"zh_cn": zh, "en_us": en}


def write_json(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=False)
        handle.write("\n")


def main():
    add = build_add()
    problems = []
    for locale in ("zh_cn", "en_us"):
        path = os.path.join(LANG_DIR, "%s.json" % locale)
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        before = len(data)
        # 保持既有行序：新键只在末尾追加；已有的键就地改值（幂等重跑不会打乱顺序）。
        for key, value in add[locale].items():
            data[key] = value
        write_json(path, data)
        with io.open(path, "r", encoding="utf-8") as handle:
            written = json.load(handle)
        for key, value in add[locale].items():
            if written.get(key) != value:
                problems.append("%s / %s 写入后回读不一致" % (locale, key))
        print("[%s] %d -> %d keys (%d new)"
              % (locale, before, len(written), len(written) - before))

    print("=" * 60)
    for problem in problems:
        print("[X] %s" % problem)
    print("[result] round78 recipe-type lang: %s"
          % ("%d problem(s)" % len(problems) if problems else "done"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
