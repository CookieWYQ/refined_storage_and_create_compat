# -*- coding: utf-8 -*-
"""「附加 tooltip 按键分层显示」自检（物品级 + 屏幕级，共用同一实现）。

方向（用户最新原话）：
- **物品级的本模组附加信息也要按键分层**（`SequenceAssemblyPatternItem` / `SequenceUnitPatternItem`
  的 `appendHoverText`、以及 `UnitPatternTooltipComponent` 的图标行）：默认隐藏，按 Shift/Ctrl/Alt 分层显示；
- RS 原生常显帮助（`HelpTooltipComponent`、`RsccHelpBlockItem` 交给父类的 helpText）**保持不变**；
- 分层实现只有一处：`client/tooltip/RsccTooltipLayers`（物品级与屏幕级都调它）。

断言：
① 物品级附加行默认不可见（只留提示行）；唯一例外 = 空样板的常显结论行；
② 按 Shift/Ctrl/Alt 分别出现对应层，多键同按 = 并集；
③ 物品级与屏幕级共用 `RsccTooltipLayers`，且 RS 原生帮助未被改为按键；
④ RS 原生常显帮助仍在（disk / 远程终端返回 HelpTooltipComponent）；
⑤ 提示行文案中英成对（Shift / Ctrl / Alt 三个键名）；
⑥ 屏幕手绘 tooltip 的「物品自身行（常显）+ 附加行（分层）」模式正确。

用法：python tools/selfcheck_tooltip_layers.py
退出码 0 = 全部通过。
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
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LAYERS = os.path.join(JAVA, "client", "tooltip", "RsccTooltipLayers.java")
ITEM_DIR = os.path.join(JAVA, "item")
SCREEN_DIR = os.path.join(JAVA, "client", "screen")
TOOLTIP_DIR = os.path.join(JAVA, "client", "tooltip")
P = "gui.rs_create_compat.tooltip."

problems = []


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def check(ok, label, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + label + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(label)


# =====================================================================
# 模型：与 RsccTooltipLayers.append / hintLine 同构（按层过滤 + 提示行）
# =====================================================================
def model(always, layers, held):
    out = list(always)
    for layer, lines in layers.items():
        if layer in held:
            out.extend(lines)
    available = set(layers.keys())
    missing = [l for l in ("SHIFT", "CTRL", "ALT") if l in available and l not in held]
    if missing:
        out.append("HINT(" + "/".join(missing) + ")")
    return out


def section_a_cases():
    print("=== ① 分层语义（不按 / 单键 / 多键） ===")
    always = ["原版:物品名"]
    layers = {"SHIFT": ["S1", "S2"], "CTRL": ["C1"], "ALT": ["A1"]}
    cases = [
        ("①不按键", set(), ["原版:物品名", "HINT(SHIFT/CTRL/ALT)"]),
        ("②Shift", {"SHIFT"}, ["原版:物品名", "S1", "S2", "HINT(CTRL/ALT)"]),
        ("②Ctrl", {"CTRL"}, ["原版:物品名", "C1", "HINT(SHIFT/ALT)"]),
        ("②Alt", {"ALT"}, ["原版:物品名", "A1", "HINT(SHIFT/CTRL)"]),
        ("③Shift+Ctrl", {"SHIFT", "CTRL"}, ["原版:物品名", "S1", "S2", "C1", "HINT(ALT)"]),
        ("③三键同按", {"SHIFT", "CTRL", "ALT"},
         ["原版:物品名", "S1", "S2", "C1", "A1"]),
    ]
    for label, held, expect in cases:
        got = model(always, layers, held)
        print("       %-12s -> %s" % (label, got))
        check(got == expect, "%s：内容与预期一致" % label, str(got))
    got = model(always, layers, set())
    check(all(line in always or line.startswith("HINT(") for line in got)
          and sum(1 for line in got if line.startswith("HINT(")) == 1,
          "①不按键 ⇒ 附加信息全部不出现，且只多一条提示行")
    all_held = model(always, layers, {"SHIFT", "CTRL", "ALT"})
    check([l for l in all_held if l not in always] == ["S1", "S2", "C1", "A1"],
          "③三键同按 ⇒ 三层按 SHIFT→CTRL→ALT 顺序全出现")
    for label, held, _ in cases:
        got = model(always, layers, held)
        check(got[:len(always)] == always and len(always) == 1,
              "⑥%s：原版 tooltip 行仍在最前且不被重复" % label)
    check("HINT" not in "".join(model(always, layers, {"SHIFT", "CTRL", "ALT"})),
          "③全按住 ⇒ 不再加提示行（没有更多可看）")


# =====================================================================
# ② 唯一实现
# =====================================================================
def section_unique_implementation():
    print("=== ② 唯一实现（统一 API，不允许各处自己读按键） ===")
    layers = read(LAYERS)
    for token in ("public enum Layer", "public static boolean held(", "public static void append(",
                  "public static void render(", "public static Component hintLine(",
                  "public static void appendComponents(", "public static void renderAttached(",
                  "public static void appendAttached(", "public static boolean componentVisible("):
        check(token in layers, "唯一实现里有 %s" % token)
    check("Screen.hasShiftDown()" in layers and "Screen.hasControlDown()" in layers
          and "Screen.hasAltDown()" in layers,
          "按键状态只在 RsccTooltipLayers 里读取（Shift / Ctrl / Alt 三处各一次）")

    # 除唯一实现外，任何 .java 都不许出现 hasAltDown
    others = []
    for base, _dirs, files in os.walk(JAVA):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            if os.path.abspath(path) == os.path.abspath(LAYERS):
                continue
            if "hasAltDown" in read(path):
                others.append(path)
    check(not others, "Alt 只在唯一实现里读取（没有别处自己判断）", str(others[:3]))

    # 提示行文案只有一个来源
    hint_owners = []
    for base, _dirs, files in os.walk(JAVA):
        for name in files:
            if name.endswith(".java") and '"gui.rs_create_compat.tooltip.' in read(os.path.join(base, name)):
                hint_owners.append(name)
    check(hint_owners == ["RsccTooltipLayers.java"],
          "提示行文案只在唯一实现里拼装", str(hint_owners))


# =====================================================================
# ③ 物品级不使用分层；屏幕级使用分层
# =====================================================================
ITEM_FILES = [
    "SequenceUnitPatternItem.java",
    "SequenceAssemblyPatternItem.java",
    "AdvancedRemoteTerminalItem.java",
    "UniversalStorageDiskItem.java",
    "RsccHelpBlockItem.java",
    "CamouflageFrameItem.java",
    "SeparationFrameItem.java",
]

SCREEN_FILES = [
    "CollectionCacheScreen.java",
    "BusCategoryConfigScreen.java",
    "SequencePatternTerminalScreen.java",
    "ChamberUnitsSummaryScreen.java",
    "SequenceExecutionChamberScreen.java",
    "StepMachineSelectScreen.java",
    "SchematicLoaderScreen.java",
    "AdvancedSchematicLoaderScreen.java",
    "QuantityKeeperScreen.java",
    "RangeChargerScreen.java",
    "ChamberFaceConfigScreen.java",
    "CollectionInputFaceConfigScreen.java",
    "CollectionRangeConfigScreen.java",
    "SequenceResultConfigScreen.java",
    "StepDetailConfigScreen.java",
    "UnitPatternConfigScreen.java",
    "IntermediateCacheScreen.java",
    "CollectionMarkerConfigScreen.java",
]


def section_scope():
    print("=== ③ 物品级与屏幕级共用同一实现（RS 原生帮助仍常显） ===")
    # 明确的负面断言：筛选弹窗类已被删除（不是「文件找不到就跳过」）
    check(not os.path.exists(os.path.join(SCREEN_DIR, "StepFilterSelectScreen.java")),
          "③筛选弹窗 StepFilterSelectScreen 已删除（文件不存在）")
    check("StepFilterSelectScreen" not in read(os.path.join(SCREEN_DIR, "SequencePatternTerminalScreen.java")),
          "③流程编排终端不再引用 StepFilterSelectScreen（无筛选按钮）")
    # 本模组自己的样板物品 / 图标组件：必须使用 RsccTooltipLayers（默认收起，按键展开）
    for name in ("SequenceUnitPatternItem.java", "SequenceAssemblyPatternItem.java",
                 "UnitPatternTooltipComponent.java"):
        path = os.path.join(ITEM_DIR if name.endswith("Item.java") else TOOLTIP_DIR, name)
        source = read(path)
        check("RsccTooltipLayers" in source,
              "③%s 使用 RsccTooltipLayers（物品级与屏幕级共用同一实现）" % name)

    # RS 原生常显帮助（帮助文本不是本模组自研的附加细节）→ 不得改为按键分层
    for name in ("RsccHelpBlockItem.java", "UniversalStorageDiskItem.java",
                 "AdvancedRemoteTerminalItem.java", "CamouflageFrameItem.java",
                 "SeparationFrameItem.java"):
        path = os.path.join(ITEM_DIR, name)
        if not os.path.exists(path):
            continue
        source = read(path)
        check("RsccTooltipLayers" not in source,
              "③%s 保持 RS 原生常显帮助（未改为按键分层）" % name)

    # 屏幕级：必须使用 RsccTooltipLayers
    for name in SCREEN_FILES:
        path = os.path.join(SCREEN_DIR, name)
        if not os.path.exists(path):
            continue
        source = read(path)
        check("RsccTooltipLayers." in source,
              "③屏幕 %s 使用 RsccTooltipLayers（手绘物品 tooltip 分层）" % name)


# =====================================================================
# ⑦ ①② 物品级：默认不可见 + 单键 / 多键分层
# =====================================================================
def section_item_layers():
    print("=== ①② 物品级 tooltip：默认不可见、按 Shift/Ctrl/Alt 分层 ===")
    assembly = read(os.path.join(ITEM_DIR, "SequenceAssemblyPatternItem.java"))
    unit = read(os.path.join(ITEM_DIR, "SequenceUnitPatternItem.java"))
    comp = read(os.path.join(TOOLTIP_DIR, "UnitPatternTooltipComponent.java"))

    # ① 附加行不再「无条件 tooltip.add」：全部经统一出口过滤（唯一例外 = 空样板的常显结论行）
    check("RsccTooltipLayers.append(tooltip, layered);" in assembly,
          "①序列装配样板的附加行经统一出口（默认隐藏 + 提示行）")
    # 2026-10-05：总样板的「主原料行」按用户要求改成常显（原来只剩图标组件，鼠标放上去几乎没可读信息）
    # ⇒ 常显 add 由 1 处变为 3 处（空样板结论行 + 主原料行 + 所需输入条数）。
    check(assembly.count("tooltip.add(") >= 1,
          "①序列装配样板仍有常显结论 / 原料行，其余附加行经统一出口收起",
          str(assembly.count("tooltip.add(")))
    check("RsccTooltipLayers.append(tooltip, layered);" in unit,
          "①单元样板的附加行经统一出口（默认隐藏 + 提示行）")
    check(unit.count("tooltip.add(") == 1,
          "①单元样板只剩 1 处常显 add（空样板结论行），其余全部收起",
          str(unit.count("tooltip.add(")))
    check("private static boolean visible()" not in comp,
          "①图标组件不再有「恒 true 的 visible()」（改为逐行按键判定）")

    # ② 分层归属：Shift = 用途 / 机制；Ctrl = 数值 / 概率；Alt = 内部标识
    # 2026-10-05：总样板的「主原料 + 所需输入」不再写成文字行（用户要求原料一律轮播显示），
    # 改由图标组件渲染；Shift / Ctrl 的分层判定随之移进 AssemblyInputsTooltipComponent，仍走唯一实现。
    check("componentVisible(RsccTooltipLayers.Layer.SHIFT)"
          in read(os.path.join(TOOLTIP_DIR, "AssemblyInputsTooltipComponent.java")),
          "②序列装配样板的「主原料 + 所需输入」仍属 Shift 层（判定移到图标组件，仍走唯一实现）")
    check("RsccTooltipLayers.ctrl(" in assembly and "units" in assembly,
          "②序列装配样板把步数 / 循环与产物 · 废料概率放进 Ctrl 层（数值）")
    check("RsccTooltipLayers.shift(" in unit and "RsccTooltipLayers.alt(" in unit,
          "②单元样板：配方类型名 = Shift，内部 id = Alt")
    check("RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT)" in comp
          and "RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.CTRL)" in comp,
          "②图标组件：机器 / 输入原料行 = Shift，输入流体行 = Ctrl（含数量）")
    check("RsccTooltipLayers.componentVisible(" in comp
          and "Screen.hasShiftDown" not in comp and "hasControlDown" not in comp,
          "②图标组件不自己读按键（一律问唯一实现）")
    # 多键并显：三层内容都各自独立成行，唯一实现的过滤是「并集」
    layers = read(LAYERS)
    check("if (held.contains(line.layer()))" in layers,
          "②多键同按 ⇒ 各层并集（唯一实现的过滤语义）")

    # 删除的「废话」：单元样板里与图标区完全重复的 input_fluid 文本行已删（同一语言键）
    check('LANG + "input_fluid"' not in unit,
          "⑦单元样板删掉了与图标区重复的 input_fluid 文本行（同一语言键、同一内容）")


# =====================================================================
# ④ RS 常显帮助已恢复
# =====================================================================
def section_help_restored():
    print("=== ④ RS 原生常显帮助（HelpTooltipComponent）已恢复 ===")
    # RsccHelpBlockItem 把 helpText 交给父类 BaseBlockItem（父类 getTooltipImage 返回 HelpTooltipComponent）
    help_item = read(os.path.join(ITEM_DIR, "RsccHelpBlockItem.java"))
    check("super(block, helpText)" in help_item,
          "④RsccHelpBlockItem 把 helpText 交给父类（BaseBlockItem 常显 HelpTooltipComponent）")
    check("RsccTooltipLayers" not in help_item,
          "④RsccHelpBlockItem 不再覆写 appendHoverText 走分层")

    # UniversalStorageDiskItem / AdvancedRemoteTerminalItem 的 getTooltipImage 返回 HelpTooltipComponent
    for name in ("UniversalStorageDiskItem.java", "AdvancedRemoteTerminalItem.java"):
        source = read(os.path.join(ITEM_DIR, name))
        check("new HelpTooltipComponent(" in source,
              "④%s.getTooltipImage 返回 HelpTooltipComponent（常显帮助）" % name)
        check("return Optional.empty();" not in source or "new HelpTooltipComponent(" in source,
              "④%s 不再返回空帮助组件" % name)


# =====================================================================
# ⑤ 提示行语言键
# =====================================================================
def section_hint_lang():
    print("=== ⑤ 提示行文案（中英成对 + 正确列出可用层） ===")
    zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
    for key, placeholders in ((P + "hold", 1), (P + "layer.shift", 0),
                              (P + "layer.ctrl", 0), (P + "layer.alt", 0)):
        ok = key in zh and key in en
        check(ok, "⑤键中英齐备：%s" % key.split(".")[-1])
        if ok:
            check(zh[key].count("%s") == placeholders and en[key].count("%s") == placeholders,
                  "⑤占位符 %d 个：%s" % (placeholders, key.split(".")[-1]))
            check(len(zh[key]) <= 40, "⑤中文 ≤ 40 字：%s" % key.split(".")[-1])
    layers = read(LAYERS)
    check("for (final Layer layer : Layer.values())" in layers
          and "available.contains(layer) && !held.contains(layer)" in layers,
          "⑤提示行只列出「有内容但当前没按住」的层面")
    check("KEY_COLOR" in layers and "ChatFormatting.AQUA" in layers
          and "HINT_COLOR" in layers and "ChatFormatting.DARK_GRAY" in layers,
          "⑤视觉参考机械动力：灰正文 + 彩色按键名")
    names = [zh[P + "layer.shift"], zh[P + "layer.ctrl"], zh[P + "layer.alt"]]
    check(names == ["Shift", "Ctrl", "Alt"], "⑤三个层面各有按键名", str(names))


# =====================================================================
# ⑥ 屏幕手绘 tooltip 的常显 + 分层模式
# =====================================================================
def section_screen_pattern():
    print("=== ⑥ 屏幕手绘 tooltip：物品自身行常显 + 附加行分层 ===")
    spt = read(os.path.join(SCREEN_DIR, "SequencePatternTerminalScreen.java"))
    check("renderSectionTooltip" in spt,
          "⑥序列装配样板终端有产物/废料槽的手绘 tooltip")
    # 废料 / 产物格：物品自身提示常显（always），角色说明 / 概率数量分层
    check("always.addAll(itemTooltipLines" in spt,
          "⑥产物/废料格的物品自身 tooltip 常显（走 always 列表）")
    check('RsccTooltipLayers.shift(' in spt and 'RsccTooltipLayers.ctrl(' in spt,
          "⑥产物/废料格的角色说明（Shift）与概率/数量（Ctrl）分层显示")

    cc = read(os.path.join(SCREEN_DIR, "CollectionCacheScreen.java"))
    check("always.addAll" in cc,
          "⑥归流缓存仓的物品自身 tooltip 常显")
    check("RsccTooltipLayers.render(" in cc,
          "⑥归流缓存仓用 RsccTooltipLayers.render（常显 + 分层合并渲染）")


if __name__ == "__main__":
    section_a_cases()
    print("-" * 70)
    section_unique_implementation()
    print("-" * 70)
    section_scope()
    print("-" * 70)
    section_item_layers()
    print("-" * 70)
    section_help_restored()
    print("-" * 70)
    section_hint_lang()
    print("-" * 70)
    section_screen_pattern()
    print("=" * 70)
    print("问题总数: %d" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1 if problems else 0)
