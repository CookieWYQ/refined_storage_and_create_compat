# -*- coding: utf-8 -*-
"""tooltip「文本堆叠 / 两框叠画」防回归自检（round 69）。

用户原话：
- 「序列装配终端那个样板终端还是出现这种文本堆叠的问题，你检查一遍所有的这种 too（tooltip）看看他会不会堆叠」
- 「这个重叠问题好像很多地方都有，你检查一下」

本模组界面基于原版 `AbstractContainerScreen`（**1.21.1 的 `AbstractContainerScreen.render` 不会自动画槽位
tooltip**，已用 `javap` 在真实 MC jar 上核对），因此「同一帧同一片像素出现两份 tooltip」只可能来自
**我们自己在同一次渲染里调用了两个渲染出口**：

  ① 原版槽位路径：`renderTooltip(guiGraphics, mouseX, mouseY)` / `super.renderTooltip(...)`
     —— 它只为 `hoveredSlot` 的<b>物品</b>画一份（`hoveredSlot == null` 或槽内为空 ⇒ 什么都不画）；
  ② 手绘路径：`RsccTooltipLayers.render / renderAttached`、`GhostMarkerRenderer.renderLines /
     renderFluidTooltip`、`UpgradeSlotTooltips.render`、`ScrollSelectWidget.renderTooltip`、
     `guiGraphics.renderTooltip(...)`。

两份 tooltip 的**行数不同** ⇒ `DefaultTooltipPositioner` 在贴边时按高度反算 y
（`y = max(4, mouseY - 16 - height)`）⇒ 两套文字锚点不同 ⇒ **互相压（用户说的「文本堆叠」）**。

断言（全部是源码级、可复跑的机器判据）：
① 站点清单与源码一致（文件级白名单；新增带 tooltip 的文件必须显式登记到本表）；
② 「同帧只画一份」的阶梯不变式：同一个方法里若出现 ≥2 个 tooltip 渲染出口，则相邻两个出口之间
   必须存在 `return;`（即「命中即 return」），并且最后一个出口之外的全部出口都必须是分支尾部；
③ 与 `hoveredSlot` **不同源**（几何命中）的自绘出口必须问
   `RsccTooltipLayers.vanillaSlotTooltipWillDraw(...)`（原版这一帧会不会已经画了一份）；
④ 全工程不存在「把多行文字塞进一个单行 tooltip 行」的写法：任何 java 字符串字面量里不得出现 `\\n`，
   两个语言文件的值里也不得出现换行（行高按单行算 ⇒ 后一行会压在上一行上）；
⑤ 每个自定义 `ClientTooltipComponent` 的 `getHeight()` 与 `renderImage()` 用**同一批可见性条件**
   （高度算少了 ⇒ 下一行文字压上来）；
⑥ `UpgradeSlotTooltips.renderBody`（全工程唯一手写多行 tooltip 渲染循环）必须同时满足
   「背景高度 = Σ getHeight()」与「逐行推进 lineY += getHeight()」。

反例自证（section_counterexample）：把**修前**的真实写法喂给同一条检查器，断言它一定被抓到。
用法：python tools/selfcheck_round69_tooltip_stack.py
退出码 0 = 全部通过。
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
CLIENT = os.path.join(PKG, "client")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

problems = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(ok, label, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + label + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(label)


# =====================================================================
# 工具：把 java 源码切成「方法 → 行号区间」
# =====================================================================
METHOD_HEAD = re.compile(
    r"^[ \t]*(?:@\w+[^\n]*\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized|abstract|"
    r"@Override[\s\n]*)*)[\w<>\[\],.?\s]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w.,\s]+)?\{",
    re.MULTILINE)


def methods(source):
    """返回 [(method_name, start_line_index, end_line_index)]（按 `{` / `}` 配平）。"""
    out = []
    for match in METHOD_HEAD.finditer(source):
        name = match.group(1)
        brace = source.find("{", match.end() - 1)
        depth = 0
        index = brace
        while index < len(source):
            char = source[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        start_line = source.count("\n", 0, match.start())
        end_line = source.count("\n", 0, index)
        out.append((name, start_line, end_line))
    return out


# 全部「tooltip 渲染出口」——站点清单就是由这一组正则枚举出来的。
# ⚠ 顺序有意义：`super.renderTooltip` 必须排在通用 `renderTooltip(` 之前（否则会被归成通用一类）。
DECLARATION = re.compile(r"\b(void|protected|public|private)\b[^\n]*\brenderTooltip\s*\(")
ANNOTATION = re.compile(r"@Inject\s*\(\s*method\s*=\s*\"renderTooltip\"")
RENDER_CALLS = [
    (re.compile(r"\bsuper\.renderTooltip\s*\("), "vanilla/super"),
    (re.compile(r"\brenderTooltip\s*\("), "vanilla/slot-or-custom"),
    (re.compile(r"RsccTooltipLayers\.render\s*\("), "hand-drawn"),
    (re.compile(r"RsccTooltipLayers\.renderAttached\s*\("), "hand-drawn"),
    (re.compile(r"RsccTooltipLayers\.append\s*\("), "item-append"),
    (re.compile(r"RsccTooltipLayers\.appendAttached\s*\("), "item-append"),
    (re.compile(r"RsccTooltipLayers\.appendComponents\s*\("), "component-append"),
    (re.compile(r"GhostMarkerRenderer\.renderLines\s*\("), "hand-drawn"),
    (re.compile(r"GhostMarkerRenderer\.renderFluidTooltip\s*\("), "hand-drawn"),
    (re.compile(r"UpgradeSlotTooltips\.render\s*\("), "hand-drawn"),
    (re.compile(r"ScrollSelectWidget\.renderTooltip|picker\.renderTooltip"), "hand-drawn"),
    (re.compile(r"Platform\.INSTANCE\.renderTooltip\s*\("), "hand-drawn"),
]

# 方法名里带这些词 = 这个方法的职责就是「给自绘元素补 tooltip」⇒ 必须满足阶梯不变式。
TOOLTIP_METHOD_HINT = re.compile(r"[Tt]ooltip")


def classify(line):
    for pattern, kind in RENDER_CALLS:
        if pattern.search(line):
            return kind
    return None


def render_sites(lines):
    """返回 {行下标: kind}（只保留真正的渲染出口；方法声明 / @Inject 注解 / 修饰键行都不算）。"""
    found = {}
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        if "vanillaSlotTooltipWillDraw" in line:
            continue
        if DECLARATION.search(line) or ANNOTATION.search(line):
            continue
        kind = classify(line)
        if kind is not None:
            found[index] = kind
    return found


def ladder_violations(lines, start, end):
    """阶梯不变式：同一方法体里相邻两个<b>渲染出口</b>之间必须存在 `return`（命中即 return）、
    `else`（if/else 天然互斥）或一个显式的互斥条件（`isEmpty()` / `vanillaSlotTooltipWillDraw`：
    「原版这一帧不会画」）。`append*` 只是拼装列表、不是渲染出口，不参与本判定。"""
    sites = sorted(i for i in render_sites(lines)
                   if start <= i <= end and not render_sites(lines)[i].endswith("append")
                   and render_sites(lines)[i] != "item-append")
    bad = []
    for prev, nxt in zip(sites, sites[1:]):
        between = "\n".join(lines[prev + 1:nxt])
        mutex = ("return" in between or "else" in between
                 or "isEmpty()" in between or "vanillaSlotTooltipWillDraw" in between)
        if not mutex:
            bad.append((prev + 1, nxt + 1))
    return bad, sites


# =====================================================================
# ① 站点清单（文件级白名单：新增带 tooltip 的文件必须登记）
# =====================================================================
# 说明：这里只冻结「哪些文件里有 tooltip 出口」。文件内部新增/移动站点不触发本表（那由 ② ③ 管），
# 但**新出现一个带 tooltip 的文件**一定会失败，提示作者把新站点补进审计表。
EXPECTED_SITE_FILES = [
    "client/TerminalModeTabOverlay.java",
    # 第 96 轮新增：请求器的网格主界面（client/grid）自己画四类 hover tooltip
    #（工具条 / 订单行 / 数量 ± / 清单搜索框），并在 render() 里手动补原版槽位 tooltip。
    "client/grid/ResourcePickerScreen.java",
    "client/screen/AdvancedQuantityKeeperScreen.java",
    # 第 90 轮新增：高级归流缓存仓界面在基础版 tooltip 之外多了一个「区域选定器槽」站点
    #（空槽提示 / 有物品时追加区域摘要），命中判定与基础版同源（hoveredSlot.index == 138）。
    "client/screen/AdvancedCollectionCacheScreen.java",
    "client/screen/AdvancedSchematicLoaderScreen.java",
    # 第 96 轮新增：区域选定器列表界面重做后自己画 tooltip（列表行悬停显示**完整坐标**，
    # 这是「坐标被挡住看不清」投诉的最终兜底：行内按 font.width 折行 / 裁剪，悬停一定给全文）。
    "client/screen/AreaSelectorScreen.java",
    # 第 125 轮新增：高级归流缓存仓的「区域条」侧栏（Java 自绘，不继承 Screen）——
    # 行悬停 tooltip（完整坐标 / 尺寸 / 区域名）、过滤图标 tooltip、组头 tooltip 全部手绘；
    # 它由 AdvancedCollectionCacheScreen#render 每帧调用 renderTooltip(...)，不经原版自动渲染。
    "client/screen/AreaStripPanel.java",
    "client/screen/AssemblyPatternRebindScreen.java",
    "client/screen/BusCategoryConfigScreen.java",
    "client/screen/ChamberBindingConfigScreen.java",
    "client/screen/ChamberFaceConfigScreen.java",
    "client/screen/ChamberUnitsSummaryScreen.java",
    # 第 98 轮新增：充电过滤子界面自己画全部 tooltip（行 / 页签 / 模式按钮 / 搜索框 /
    # 滚动条 / 玩家页添加行 / 底部按钮 / 提示行），每帧只画一个（命中即 return），
    # 不依赖原版自动渲染（本模组 GUI 没有自动 tooltip）。
    "client/screen/ChargerFilterScreen.java",
    # 第 99 轮新增：FE 能量立方界面（电量条的悬浮 tooltip —— 正文为了不溢出只显示存量，
    # 精确的存量 / 容量 / 百分比在悬停电量条时给出）。它同样继承 AbstractContainerScreen
    # （原版不会替子类调 renderTooltip），因此必须自己覆写 render() 并手动调用。
    "client/screen/FEEnergyCubeScreen.java",
    # 第 101 轮新增：终端「能耗监视」页（列表行 / 待机灰行 / 按钮的 tooltip 全部手绘；
    # 它继承 ChildConfigScreen 而不是容器界面，因此更要显式登记，否则「未登记站点」会漏掉它）。
    "client/screen/EnergyAuditScreen.java",
    # 第 101 轮新增：降解室发电机界面（进度条 / 电量条 / 分页按钮 / 无效槽位数 的 tooltip 全部手绘）。
    "client/screen/DecompositionGeneratorScreen.java",
    # 第 112 轮新增：「区域标记」界面（左边 = 某区域已有的 ghost 标记，右边 = 玩家背包镜像）。
    # 标记格悬停 = 物品名 + 完整注册 id + 需求数量 + 「左键移除」；背包格悬停 = 「加入该区域标记」；
    # 它继承 ChildConfigScreen（原版不会替子类调 renderTooltip），所以必须自己覆写 render() 手绘。
    "client/screen/AreaMarkScreen.java",
    "client/screen/CollectionCacheScreen.java",
    "client/screen/CollectionDeleteModeConfirmScreen.java",
    "client/screen/CollectionInputFaceConfigScreen.java",
    "client/screen/CollectionMarkerConfigScreen.java",
    "client/screen/CollectionRangeConfigScreen.java",
    "client/screen/IntermediateCacheScreen.java",
    # 第 95 轮新增：容器代理器界面终于覆写 render() 并手动 renderTooltip（此前是全工程唯一
    # 0 次 tooltip 调用的容器界面 ⇒ 槽位悬停无提示，用户实机反馈「容器代理器其实没有绘制的」）。
    "client/screen/InventoryProxyScreen.java",
    "client/screen/NetworkResourceReceiverScreen.java",
    "client/screen/NetworkResourceRequesterScreen.java",
    "client/screen/QuantityKeeperScreen.java",
    "client/screen/RangeChargerScreen.java",
    "client/screen/SchematicLoaderScreen.java",
    "client/screen/SequenceAssemblyExecutorScreen.java",
    "client/screen/SequenceExecutionChamberScreen.java",
    "client/screen/SequencePatternTerminalScreen.java",
    "client/screen/SequenceResultConfigScreen.java",
    "client/screen/StepMachineSelectScreen.java",
    "client/screen/UnitPatternConfigScreen.java",
    "client/screen/UnitPatternManagerScreen.java",
    "client/tooltip/ClipboardTooltipHandler.java",
    "client/tooltip/RsccTooltipLayers.java",
    "client/tooltip/UpgradeSlotTooltips.java",
    "client/widget/ExporterExecutorRowWidget.java",
    "client/widget/GhostMarkerRenderer.java",
    "client/widget/RsccRedstoneModeButton.java",
    "client/widget/RecipeTypeSelect.java",
    "client/widget/ScrollSelectWidget.java",
    "item/SequenceAssemblyPatternItem.java",
    "item/SequenceUnitPatternItem.java",
    "mixin/client/AutocraftingMonitorScreenMixin.java",
    "mixin/client/ExporterScreenMixin.java",
    "mixin/client/ImporterScreenMixin.java",
]


def enumerate_site_files():
    out = []
    for base, _dirs, files in os.walk(JAVA):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            text = read(path)
            if "Tooltip" not in text and "tooltip" not in text:
                continue
            lines = text.split("\n")
            if render_sites(lines):
                out.append(os.path.relpath(path, PKG).replace("\\", "/"))
    return sorted(out)


def section_inventory():
    print("=== ① tooltip 站点清单（文件级白名单） ===")
    found = enumerate_site_files()
    missing = [f for f in found if f not in EXPECTED_SITE_FILES]
    extra = [f for f in EXPECTED_SITE_FILES if f not in found]
    check(not missing, "源码里没有未登记的 tooltip 站点文件", str(missing))
    check(not extra, "审计表里没有已经消失的站点文件", str(extra))
    print("       登记站点文件数 = %d" % len(EXPECTED_SITE_FILES))
    return found


# =====================================================================
# ② 阶梯不变式（同帧只画一份）
# =====================================================================
def ladder_report(files):
    print("=== ② 「命中即 return」阶梯不变式（同帧只画一份） ===")
    total_sites = 0
    bad_all = []
    for rel in files:
        path = os.path.join(PKG, rel)
        text = read(path)
        lines = text.split("\n")
        for name, start, end in methods(text):
            if not TOOLTIP_METHOD_HINT.search(name):
                continue
            bad, sites = ladder_violations(lines, start, end)
            total_sites += len(sites)
            for first, second in bad:
                bad_all.append("%s#%s: L%d 与 L%d 之间没有 return（两框会叠画）"
                               % (rel, name, first, second))
    check(not bad_all, "每个 tooltip 方法内部最多只有一个出口能执行到", str(bad_all[:4]))
    print("       扫描到的 tooltip 出口 = %d" % total_sites)
    return bad_all


# =====================================================================
# ③ 几何命中的自绘出口必须问 vanillaSlotTooltipWillDraw
# =====================================================================
# (文件, 方法名) —— 这些方法的命中判定与 hoveredSlot 不同源（几何矩形 / 控件 isMouseOver），
# 因此必须显式问「原版这一帧会不会已经画了一份」。
GEOMETRY_KEYED = [
    ("client/screen/SequenceExecutionChamberScreen.java", "renderSptTooltips"),
    ("client/screen/IntermediateCacheScreen.java", "renderCacheTooltips"),
    ("client/screen/RangeChargerScreen.java", "renderHoverTooltips"),
]
# 这些站点在「几何命中」之后还有一条槽位身份判据（等于同一格时才继续），也必须有该守卫：
GEOMETRY_WITH_SLOT_EXCEPTION = [
    ("client/screen/SequencePatternTerminalScreen.java", "renderSptTooltips"),
    ("client/screen/QuantityKeeperScreen.java", "renderTooltip"),
    ("client/screen/AdvancedQuantityKeeperScreen.java", "renderTooltip"),
]


def section_geometry_guard():
    print("=== ③ 几何命中的自绘出口已挡原版那份 ===")
    layers = read(os.path.join(CLIENT, "tooltip", "RsccTooltipLayers.java"))
    check("public static boolean vanillaSlotTooltipWillDraw(" in layers,
          "唯一判据 RsccTooltipLayers#vanillaSlotTooltipWillDraw 存在（与 hoveredSlot 不同源时必问）")
    check("hoveredSlot != null && hoveredSlot.hasItem()" in layers
          or "hoveredSlot.hasItem()" in layers,
          "该判据 = 「hoveredSlot 有物品 ⇒ 原版一定会画一份」")
    for rel, name in GEOMETRY_KEYED + GEOMETRY_WITH_SLOT_EXCEPTION:
        source = read(os.path.join(PKG, rel))
        found = False
        for method_name, start, end in methods(source):
            if method_name != name:
                continue
            body = "\n".join(source.split("\n")[start:end + 1])
            found = "vanillaSlotTooltipWillDraw" in body
        check(found, "%s#%s 已挡原版那份（否则同格两框）" % (rel.split("/")[-1], name))


# =====================================================================
# ④ 没有「多行塞单行」的写法
# =====================================================================
def section_no_newline_in_component():
    print("=== ④ 不存在「Component 里塞 \\n ⇒ 行高按单行算」的写法 ===")
    offenders = []
    # 只扫「会进 tooltip 的包」：client/ 与 item/（support/ 里的 \\n 是诊断日志，与本议题无关）。
    for root in (os.path.join(PKG, "client"), os.path.join(PKG, "item")):
        for base, _dirs, files in os.walk(root):
            for name in files:
                if not name.endswith(".java"):
                    continue
                path = os.path.join(base, name)
                for index, line in enumerate(read(path).split("\n")):
                    stripped = line.strip()
                    # 只查真正的代码行：`//` 行注释与 `*` 续行（javadoc）里的 `\n` 是说明文字，
                    # 不是塞进 Component 的字符串。
                    if "\\n" in line and not stripped.startswith("//") \
                            and not stripped.startswith("*"):
                        offenders.append("%s:%d" % (os.path.relpath(path, PKG), index + 1))
    check(not offenders, "client/ 与 item/ 里没有把换行塞进 tooltip 文本", str(offenders[:4]))
    lang_bad = []
    for name in ("zh_cn.json", "en_us.json"):
        text = read(os.path.join(LANG, name))
        for index, line in enumerate(text.split("\n")):
            if "\\n" in line:
                lang_bad.append("%s:%d" % (name, index + 1))
    check(not lang_bad, "语言文件里没有换行（tooltip 行高按单行算）", str(lang_bad[:4]))


# =====================================================================
# ⑤ 自定义 ClientTooltipComponent：getHeight 与 renderImage 同条件
# =====================================================================
COMPONENTS = [
    ("client/tooltip/UnitPatternTooltipComponent.java",
     ["machineVisible()", "inputVisible()", "fluidVisible()"], "ICON_SIZE"),
    ("client/tooltip/AssemblyInputsTooltipComponent.java",
     ["ingredientVisible()", "itemsVisible()", "fluidsVisible()"], "ICON_SIZE"),
]


def section_component_heights():
    print("=== ⑤ 组件行 getHeight 与 renderImage 用同一批可见性条件 ===")
    for rel, flags, unit in COMPONENTS:
        source = read(os.path.join(CLIENT, rel.replace("client/", "")))
        name = rel.split("/")[-1]
        bodies = {}
        for method_name, start, end in methods(source):
            bodies[method_name] = "\n".join(source.split("\n")[start:end + 1])
        render_body = bodies.get("renderImage")
        # 高度计算允许走一层私有助手（visibleRows()：把三个可见性条件收在一处）
        height_body = (bodies.get("getHeight") or "") + "\n" + (bodies.get("visibleRows") or "")
        check(bool(bodies.get("getHeight")) and render_body is not None,
              "%s 有 getHeight 与 renderImage" % name)
        if not bodies.get("getHeight") or render_body is None:
            continue
        for flag in flags:
            check(flag in height_body and flag in render_body,
                  "%s 的 %s 同时出现在 getHeight(含助手) 与 renderImage" % (name, flag))
        check(unit in height_body, "%s 的高度按 %s 计价（行高与绘制行距同源）" % (name, unit))
        # 行推进必须是 row * ICON_SIZE（不是写死 10 / 9）
        check("row * ICON_SIZE" in render_body or "row * unit" in render_body,
              "%s 的 renderImage 逐行推进 y（row * ICON_SIZE）" % name)


# =====================================================================
# ⑥ 唯一手写多行渲染循环：背景高度 = Σ 高度、逐行推进
# =====================================================================
def section_manual_loop():
    print("=== ⑥ UpgradeSlotTooltips.renderBody：高度与 y 推进同源 ===")
    source = read(os.path.join(CLIENT, "tooltip", "UpgradeSlotTooltips.java"))
    check("TooltipRenderUtil.renderTooltipBackground" in source,
          "手写多行 tooltip 只有这一处（照搬 MC 流程）")
    body = source
    for method_name, start, end in methods(source):
        if method_name == "renderBody":
            body = "\n".join(source.split("\n")[start:end + 1])
    check("height += component.getHeight()" in body,
          "背景高度 = Σ 各行 getHeight()（算少了 ⇒ 文字溢出框外）")
    check("lineY += component.getHeight()" in body,
          "逐行推进 lineY += getHeight()（不推进 ⇒ 后一行压在前一行上）")
    check(body.count("component.getHeight()") >= 2,
          "高度计算与绘制用的是同一个 getHeight()",
          str(body.count("component.getHeight()")))


# =====================================================================
# 反例自证：修前的真实写法一定被同一条检查器抓到
# =====================================================================
BEFORE_ADVANCED_KEEPER = """
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot == null) {
            return;
        }
        final int row = menu.markerRowOf(hoveredSlot);
        if (row < 0) {
            return;
        }
        if (menu.getMarkerForm(row) >= 1) {
            final ResourceLocation id = syncedFluidId(row);
            if (id != null) {
                GhostMarkerRenderer.renderFluidTooltip(guiGraphics, font, id, false, null, mouseX, mouseY);
                return;
            }
        }
    }
"""

BEFORE_SPT_GEN = """
    private void renderSptTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (isHoveringRect(GEN_X, CONTROL_Y, GEN_W, CONTROL_H, mouseX, mouseY)) {
            renderLines(guiGraphics, generateTooltipLines(), mouseX, mouseY);
            return;
        }
        if (isHoveringRect(LOOP_LABEL_X, LOOP_LABEL_Y - 1, LOOP_LABEL_W, 10, mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "loops", loopsValue()),
                Component.translatable(LANG + "loops.tip")), mouseX, mouseY);
        }
    }
"""


def section_counterexample():
    print("=== 反例自证（修前的写法必须被同一条检查器抓到） ===")
    # (a) 修前的基础/高级定量保持器：super 画一份（槽里有物品）+ 我们画一份流体 tip —— 同帧两框。
    lines = BEFORE_ADVANCED_KEEPER.split("\n")
    kinds = [classify(lines[i]) for i in sorted(render_sites(lines))]
    check("vanilla/super" in kinds and "hand-drawn" in kinds,
          "反例 a 被识别为「原版一份 + 手绘一份」", str(kinds))
    check("hoveredSlot.getItem().isEmpty()" not in BEFORE_ADVANCED_KEEPER,
          "反例 a 确实缺少「槽内为空 / 原版不会画」判据 ⇒ 修法可被这条判据抓到")
    # (b) 几何命中但没有 vanillaSlotTooltipWillDraw 守卫的站点必须被 ③ 抓到。
    check("vanillaSlotTooltipWillDraw" not in BEFORE_SPT_GEN,
          "反例 b 确实缺少 vanillaSlotTooltipWillDraw ⇒ ③ 会报 FAIL")
    # (c) 「Component 里塞 \n」必须被 ④ 的正则命中。
    before_newline = 'lines.add(Component.literal("a' + chr(92) + 'nb"));'
    check(chr(92) + "n" in before_newline, "反例 c（Component 里塞 \\n）会被 ④ 的正则命中")
    # (d) 高度写死（没走 getHeight）必须被 ⑥ 抓到。
    before_height = "int height = components.size() * 10;"
    check("component.getHeight()" not in before_height,
          "反例 d（写死 height = N*10）会被 ⑥ 抓到")
    # (e) 阶梯不变式必须真的会报：造一个「两个出口之间没有 return」的方法。
    bad_source = """
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.renderTooltip(font, List.of(Component.literal("a")), mouseX, mouseY);
        RsccTooltipLayers.renderAttached(guiGraphics, font, Component.literal("b"), mouseX, mouseY);
    }
"""
    bad_lines = bad_source.split("\n")
    bad, sites = ladder_violations(bad_lines, 0, len(bad_lines) - 1)
    check(len(sites) == 2 and len(bad) == 1,
          "反例 e（两个出口之间没有 return）会被 ② 的阶梯不变式抓到", str(bad))


def pre_fix_source(rel, name):
    """取**真·修前**源码：git 历史里「最近一个目标方法仍缺 vanillaSlotTooltipWillDraw」的版本。

    第 130 轮修：原来用 `git show HEAD:`（隐含 HEAD = 修前）。修复提交进 HEAD 后这里拿到的就是修后代码，
    head_failures 恒为空 ⇒ 这条反例自证变成假失败（见 tools/_prefix_history.py 的说明）。
    返回 (sha8, 文本) 或 (None, None)。
    """
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from _prefix_history import find_last_version

    def still_broken(text):
        for method_name, start, end in methods(text):
            if method_name != name:
                continue
            body = "\n".join(text.split("\n")[start:end + 1])
            return "vanillaSlotTooltipWillDraw" not in body
        return False

    return find_last_version(ROOT, rel, predicate=still_broken)


def section_head_counterexample():
    """真·反例：把**修前（git HEAD）**的同一批文件喂给 ③ 的判据，必须报出一批 FAIL。

    这就是「修前的写法会被这条自检抓到」的可复跑证据（不在 git 工作树里时自动跳过）。
    """
    print("=== 反例自证 · 真源码（git 历史里最近一个「仍缺判据」的版本） ===")
    targets = GEOMETRY_KEYED + GEOMETRY_WITH_SLOT_EXCEPTION
    checked = 0
    head_failures = []
    for rel, name in targets:
        sha, text = pre_fix_source(rel, name)
        if text is None:
            continue
        checked += 1
        body = None
        for method_name, start, end in methods(text):
            if method_name == name:
                body = "\n".join(text.split("\n")[start:end + 1])
        if body is None or "vanillaSlotTooltipWillDraw" not in body:
            head_failures.append("%s#%s@%s" % (rel.split("/")[-1], name, sha))
    if checked == 0:
        print("       （git 历史里也找不到「仍缺判据」的版本 ⇒ 跳过；合成反例见上一节）")
        return
    check(len(head_failures) == checked,
          "修前的全部 %d 个几何命中站点都会被 ③ 抓到（这就是修前会 FAIL 的证据）" % checked,
          str(head_failures[:3]))


if __name__ == "__main__":
    files = section_inventory()
    print("-" * 70)
    ladder_report(files)
    print("-" * 70)
    section_geometry_guard()
    print("-" * 70)
    section_no_newline_in_component()
    print("-" * 70)
    section_component_heights()
    print("-" * 70)
    section_manual_loop()
    print("-" * 70)
    section_counterexample()
    print("-" * 70)
    section_head_counterexample()
    print("=" * 70)
    print("问题总数: %d" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1 if problems else 0)
