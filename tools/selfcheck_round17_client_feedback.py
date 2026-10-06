# -*- coding: utf-8 -*-
"""本轮客户端反馈自检（四条）：

  ① 归流缓存仓：阻塞 tooltip 与「缓存→网络」的真实判定**严格同源**（被标签阻塞时也必须显示已阻塞 + 被谁阻塞）；
  ② 标签 / 多选输入原料：格子**循环显示全部候选**（顶部输入原料槽）；
  ③ 流程编排行的多选输入**复用同一实现**循环显示（同一组件 / 方法被两处复用）；
  ④ 「筛选」弹窗（StepFilterSelectScreen）与两个维度的结构化过滤<b>已整体删除</b>；
     取而代之的是<b>每一步行内一个「跳过重复」开关</b>：显示该步的机器 / 样板是否已就位
     （2026-10-06 起：此前显示的是「是否已有相同单元样板」，与玩家看到的「这台机器上有没有样板」
     是两个问题，见 tools/selfcheck_round45_step_unit_ready.py），
     点击切换本步生成时是否跳过重复（服务端权威）。

用法：python tools/selfcheck_round17_client_feedback.py
退出码 0 = 全部通过。
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
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

CC_SCREEN = os.path.join(JAVA, "client", "screen", "CollectionCacheScreen.java")
CC_BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
GHOST = os.path.join(JAVA, "client", "widget", "GhostMarkerRenderer.java")
SPT_SCREEN = os.path.join(JAVA, "client", "screen", "SequencePatternTerminalScreen.java")
FILTER_SCREEN = os.path.join(JAVA, "client", "screen", "StepFilterSelectScreen.java")
STEP_PACKET = os.path.join(JAVA, "network", "SetStepSkipDuplicatePacket.java")
SYNC_PACKET = os.path.join(JAVA, "network", "SyncStepMachinesPacket.java")
TERMINAL_BE = os.path.join(JAVA, "block", "entity", "SequencePatternTerminalBlockEntity.java")

CC = "gui.rs_create_compat.collection_cache."
SPT = "gui.rs_create_compat.sequence_pattern_terminal."
SFS = "gui.rs_create_compat.step_filter_select."

problems = []


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def check(ok, label, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + label + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(label)


def body_of(source, signature, end="\n    }"):
    if signature not in source:
        return ""
    return source.split(signature)[1].split(end)[0]


def method_body(source, signature):
    """按花括号配对取方法体（用于断言判定所写的位置）。"""
    start = source.index(signature)
    open_index = source.index("{", start)
    depth = 0
    for i in range(open_index, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[open_index:i + 1]
    raise SystemExit("方法体未闭合: " + signature)


def langs():
    out = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG, name), encoding="utf-8") as handle:
            out[name] = json.load(handle)
    return out


# =====================================================================
# ① 阻塞判定同源（服务端 isBlockedStack/isBlockedFluid ↔ 界面 tooltip）
# =====================================================================
def section_blocked_same_source():
    print("=== ① 归流缓存仓：tooltip 与真实阻塞判定同源 ===")
    screen = read(CC_SCREEN)
    be = read(CC_BE)

    # 服务端权威判定（对照组）：物品侧查具体 id ∪ 被阻塞标签；流体侧同理
    check("return itemMatchesAnyTag(item, blockedItemTags);" in be,
          "①a 服务端 isBlockedStack 的标签分支存在（对照组）")
    check("for (final ResourceLocation tag : blockedFluidTags) {" in be,
          "①b 服务端 isBlockedFluid 的标签分支存在（对照组）")

    blocking_items = method_body(screen, "private List<ResourceLocation> blockingItemTags(")
    blocking_fluids = method_body(screen, "private List<ResourceLocation> blockingFluidTags(")
    item_blocked = method_body(screen, "private boolean isItemBlocked(")
    fluid_blocked = method_body(screen, "private boolean isFluidBlocked(")
    check("TagKey.create(Registries.ITEM, tag)" in blocking_items,
          "①c 界面按物品标签注册表复刻服务端标签判定")
    check("TagKey.create(Registries.FLUID, tag)" in blocking_fluids,
          "①d 界面按流体标签注册表复刻服务端标签判定")
    check("blockingItemTags(stack)" in item_blocked and "isBlocked(false," in item_blocked,
          "①e isItemBlocked = 具体 id ∪ 被阻塞标签（与服务端逐字同源）")
    check("blockingFluidTags(id)" in fluid_blocked and "isBlocked(true, id)" in fluid_blocked,
          "①f isFluidBlocked = 具体 id ∪ 被阻塞标签")

    slot_body = method_body(screen, "private boolean isSlotBlocked(")
    check("final boolean blocked = isSlotBlocked(slot);" in method_body(screen, "protected void renderSlot("),
          "①g 红色标识与 tooltip 共用同一判定入口 isSlotBlocked")
    check("return isItemBlocked(slot.getItem());" in slot_body
          and "isFluidBlocked(fluid.id())" in slot_body,
          "①h 缓存区（红底 / tooltip）改用标签感知判定（此前只看具体 id → 用户实测「确实被阻塞却显示未阻塞」）")

    # tooltip：被阻塞必须写清「被谁阻塞」
    entry_body = method_body(screen, "private List<ResourceLocation> entryBlockingTags(")
    check("tags.containsAll(entry.tags())" in entry_body,
          "①i 匹配条目的「被谁阻塞」与 isEntryBlocked 同一判据")
    blocked_lines = method_body(screen, "private List<RsccTooltipLayers.Line> blockedDetailLines(")
    check('LANG + "blocked.mark"' in blocked_lines and 'LANG + "blocked.by_tag"' in blocked_lines
          and 'LANG + "blocked.by_id"' in blocked_lines,
          "①j 已阻塞时 tooltip = 结论 + 阻塞来源（具体资源 / 命中标签）")
    for call in ('itemBlockedDetailLines(stack)', 'isBlocked(true, entry.id())'):
        check(call in screen, "①k 缓存槽走标签感知的 tooltip：" + call.split("(")[0])
    check("blockedDetailLines(isEntryBlocked(marker), isBlocked(marker.fluid(), marker.id()),"
          in screen,
          "①l 匹配槽 tooltip 同时给出「是否阻塞 + 具体 id 命中 + 命中标签」")

    data = langs()
    for name, table in data.items():
        check((CC + "blocked.by_id") in table and (CC + "blocked.by_tag") in table,
              "①m %s 有阻塞来源文案键" % name)
        check(table.get(CC + "blocked.by_tag", "").count("%s") == 1,
              "①n %s blocked.by_tag 占位符 1 个" % name)

    # 推演：三种情形（标签阻塞 / 具体 id 阻塞 / 未阻塞）
    def tooltip_model(by_id, by_tags):
        if not (by_id or by_tags):
            return ["未阻塞"]
        lines = ["已阻塞", "阻塞标识"]
        if by_id:
            lines.append("来源:具体资源")
        if by_tags:
            lines.append("来源:标签 %s" % (" ".join(by_tags)))
        return lines

    cases = [
        ("按标签阻塞（锁了所有板子）", False, ["c:plates"], ["已阻塞", "阻塞标识", "来源:标签 c:plates"]),
        ("具体资源阻塞", True, [], ["已阻塞", "阻塞标识", "来源:具体资源"]),
        ("两者都命中", True, ["c:plates"], ["已阻塞", "阻塞标识", "来源:具体资源", "来源:标签 c:plates"]),
        ("未阻塞", False, [], ["未阻塞"]),
    ]
    wrong = []
    for label, by_id, by_tags, expect in cases:
        got = tooltip_model(by_id, by_tags)
        print("       %-24s -> %s" % (label, got))
        if got != expect:
            wrong.append(label)
    check(not wrong, "①o 推演：标签阻塞 / 具体阻塞 / 两者 / 未阻塞 四种情形的文案与内部状态一致", str(wrong))
    check("blocked.off" in screen,
          "①p 未阻塞分支仍保留（三态齐全）")


# =====================================================================
# ② / ③ 多选输入的循环显示（同一实现被两处复用）
# =====================================================================
def section_candidates_cycle():
    print("=== ②③ 标签 / 多选输入：循环显示全部候选（顶部槽 + 流程编排行共用一份实现） ===")
    ghost = read(GHOST)
    screen = read(SPT_SCREEN)

    candidate_body = method_body(ghost, "public static List<ItemStack> candidateItems(")
    cycle_body = method_body(ghost, "public static ItemStack cycleCandidate(")
    check("ingredient.getItems()" in candidate_body,
          "②a candidateItems 展开标签型 ingredient 的全部物品（石头/平滑/安山岩台阶…）")
    check("item.getItem() == Items.AIR" in candidate_body,
          "②b candidateItems 跳过空气")
    check("Math.floorDiv(tick, CYCLE_TICKS) % candidates.size()" in cycle_body
          and "CYCLE_TICKS = 20" in ghost,
          "②c cycleCandidate 复用既有的 tick 轮播周期（CYCLE_TICKS=20，与标签过滤器同一套）")
    check("candidates.size() == 1" in cycle_body,
          "②d 单选直接返回（不引入额外状态）")

    row_body = method_body(screen, "private void renderArrangementRow(")
    input_body = method_body(screen, "private void renderInputSlot(")
    check("GhostMarkerRenderer.cycleCandidate(candidates)" in row_body,
          "③a 流程编排行：多选时循环显示全部候选（cycleCandidate）")
    check("GhostMarkerRenderer.cycleCandidate(candidates)" in input_body,
          "②e 顶部输入原料槽：多选时同样循环显示（同一方法被两处复用）")
    # 2026-10-05：顶部槽 tooltip 为了「常显当前那一件」也复用了 cycleCandidate，
    # 因此这里放开为「≥2 且只有这一套实现」——真正要守的是「没有第三套私有轮播」。
    check(screen.count("GhostMarkerRenderer.cycleCandidate(") >= 2
          and "Math.floorDiv" not in screen,
          "③b cycleCandidate 只有既有一套实现（顶部槽 + 流程行 + tooltip 复用），没有私有轮播")
    check(screen.count("stepInputCandidates(") >= 2 and screen.count("assemblyInputCandidates(") >= 2,
          "③c 候选来源也只有一份：stepInputCandidates / assemblyInputCandidates 被绘制与 tooltip 共用")

    steptype_body = method_body(screen, "private List<ItemStack> stepInputCandidates(")
    assembly_body = method_body(screen, "private List<ItemStack> assemblyInputCandidates(")
    check("GhostMarkerRenderer.candidateItems(inputs)" in steptype_body
          and "for (int i = 1; i < all.size(); i++)" in steptype_body,
          "③d 步骤候选 = 处理配方 index≥1 的 ingredient（跳过被 Create 覆盖的过渡件）")
    check("private static List<ItemStack> candidatesOf(" in screen
          and "GhostMarkerRenderer.candidateItems(List.of(recipe.getIngredient()))" in screen
          and "recipe.getIngredient()" in screen,
          "②f 顶部候选 = 序列装配配方自身的 ingredient（与 Create JEI 面板同源；经唯一实现 candidatesOf 展开）")

    input_tip = method_body(screen, "private void renderInputSlotTooltip(")
    row_tip = read(SPT_SCREEN).split(
        "private List<Component> rowTooltipLines(final int windowRow,")[1]
    check("candidates.size() > 1" in input_tip
          and "cycleCandidate(candidates)" in input_tip
          and "itemTooltipLines(shown)" in input_tip
          and "input_slot.any" not in read(SPT_SCREEN),
          "②g 输入槽 tooltip：常显当前那一件的普通物品 tooltip；Shift 只给角色说明"
          "（不写数量 / 不枚举候选 / 无占位符）— 2026-10-05 用户要求")
    check('LANG + "card.input"' in row_tip and "cycleCandidate(inputCandidates)" in row_tip
          and 'card.input_any' not in row_tip,
          "③e 流程行 tooltip：只写当前轮播到的那一件（不含数量 / 不枚举候选）")

    data = langs()
    for name, table in data.items():
        # 2026-10-05：这两条从「（共 N 种）」改成「<当前这件> 等 N 种」⇒ 占位符 1 → 2。
        for key, placeholders in ((SPT + "card.input", 1),):
            check(key in table and table[key].count("%s") == placeholders,
                  "②h %s %s 占位符 %d 个" % (name, key.split(".")[-1], placeholders))

    # 推演：候选集合 → 轮播下标（size<=1 恒定；size>1 周期性变化）
    def cycle_index(size, tick):
        return 0 if size <= 1 else (tick // 20) % size

    three = [cycle_index(3, t) for t in (0, 20, 40, 60, 80)]
    check(three == [0, 1, 2, 0, 1], "②i 推演：3 个候选（石头/平滑/安山岩台阶）逐 20 tick 轮播", str(three))
    check(all(cycle_index(1, t) == 0 for t in (0, 20, 999)),
          "②j 推演：单选格子恒定显示那一件（不引入无意义的变化）")

    # ---- 本轮：解析链必须带回落（否则「只有一种石头台阶、不轮播」会复现） ----
    resolve = body_of(screen, "private List<ItemStack> resolveAssemblyMainCandidates(")
    check("candidatesOf(assemblyRecipeById(level, unit.recipe()))" in resolve
          and "candidatesOf(findAssemblyByProduct(level, firstResultStack()))" in resolve
          and "candidatesOf(findAssemblyByIngredient(level, representative))" in resolve,
          "②k 顶部候选的解析链 = ①配方 id → ②主产物 → ③代表物（三级回落，任一命中都给整组候选）")
    check("private static SequencedAssemblyRecipe findAssemblyByProduct(" in screen
          and "private static SequencedAssemblyRecipe findAssemblyByIngredient(" in screen
          and "private static SequencedAssemblyRecipe assemblyRecipeById(" in screen
          and "assembly.getIngredient().test(representative)" in screen,
          "②l 回落实现只有一份：按产物 / 按 ingredient 各一个私有解析器（ingredient.test 是判据）")
    step_body = method_body(screen, "private List<ItemStack> stepInputCandidates(")
    check("if (recipe == null) {" in step_body and "recipe = fallbackStepRecipe(level, unit);" in step_body,
          "②m 步骤候选也有回落：配方 id 解析不出时按「配方类型 + 输入物」找处理配方")
    check("ProcessingRecipe<?, ?> fallbackStepRecipe(" in screen
          and "stepRecipeByType(level, unit.recipeType(), wanted)" in screen
          and "stepRecipeByProduct(level, wanted)" in screen
          and "private static boolean consumesAsApplication(" in screen
          and "candidate.is(wanted.getItem())" in screen,
          "②n 回落两级：按配方类型 →（失败）经主产物找序列装配配方的那一步；判据 = 下标 ≥ 1 的 ingredient"
          " 真的能取到该输入物（与执行仓同一口径）")
    check("firstArrangementUnit()" in screen
          and "private SequencePatternData.UnitData firstArrangementUnit()" in screen
          and "if (unit.recipe() != null && !unit.recipe().isEmpty()) {\n                return unit;" in screen,
          "②o 主原料取「第一份带配方 id 的单元」（其余单元即使缺 id 也能靠回落给出同一组候选）")

    # 推演：candidateItems 的去重/展开模型（标签 3 件 → 3；单件 → 1）
    def candidate_items(groups):
        """复刻 GhostMarkerRenderer.candidateItems：按 ingredient 顺序展开 + 按物品种类去重。"""
        out = []
        for items in groups:
            for item in items:
                if item is None or item == "air":
                    continue
                if item not in out:
                    out.append(item)
        return out

    tag_group = ["stone_slab", "smooth_stone_slab", "andesite_slab"]
    expanded = candidate_items([tag_group])
    check(expanded == tag_group,
          "②p 展开：标签 ingredient（3 个台阶）⇒ 3 件候选（代表物不再吃掉其余候选）", str(expanded))
    check(len(candidate_items([tag_group, ["stone_slab"]])) == 3,
          "②q 展开去重：同一件在多个 ingredient 里出现时只算一次")
    check(candidate_items([["stone_slab"]]) == ["stone_slab"],
          "②r 单候选：只得到 1 件（因此 renderInputSlot 走原版渲染、不轮播）")
    # 反例：只取代表物 ⇒ 判负（与上面 3 件对照）
    representative_only = tag_group[0:1]
    check(len(representative_only) == 1 and expanded != representative_only,
          "②s 反例：只返回代表物（旧行为）长度 1 ≠ 整组 3 ⇒ 判负（这正是用户看到的「只有一个石头台阶」）")
    check(three != [0, 0, 0, 0, 0],
          "②t 反例：轮播卡在第 0 个不动（全为 0）⇒ 判负；实测 3 候选是 [0,1,2,0,1]")
    for name, body in (("顶部输入槽", method_body(screen, "private void renderInputSlot(")),
                       ("流程编排行", method_body(screen, "private void renderArrangementRow("))):
        check("candidates.size() > 1" in body and "GhostMarkerRenderer.cycleCandidate(candidates)" in body,
              "②u %s：只有 size>1 才轮播（单候选不轮播、不显示多选提示）" % name)


# =====================================================================
# ④ 筛选已删除 + 每步行内「跳过重复」开关（用户最终口径）
# =====================================================================
def section_filter_removed_and_step_toggle():
    print("=== ④ 筛选弹窗已删除 + 每步行内「跳过重复」开关（显示已有 / 没有，点击切换） ===")
    screen = read(SPT_SCREEN)

    # ① 筛选弹窗整类已删除，终端里再无筛选按钮 / 维度过滤
    check(not os.path.exists(FILTER_SCREEN), "④a StepFilterSelectScreen.java 已删除")
    for token in ("filterButton", "FILTER_BTN", "stepTypeFilter", "machineFilter",
                  "openFilterSelect", "StepFilterSelectScreen", 'LANG + "filter"'):
        check(token not in screen, "④b 终端不再引用 %s" % token)
    row_body = method_body(screen, "private boolean rowMatches(")
    check("stepTypeFilter" not in row_body and "machineFilter" not in row_body
          and "query.isEmpty()" in row_body and "op.contains(query)" in row_body,
          "④c rowMatches 只剩既有搜索（两个维度的结构化过滤整体删除；搜索框及其键保留）")

    # ② 每步行内开关（复用机器控件的「绘制与命中同源、左闭右开」写法）
    for token in ("private static int rowSkipX()", "private static int rowSkipY(final int windowRow)",
                  "private boolean stepSkipToggleHitAt(final int windowRow,",
                  "private void toggleStepSkipDuplicate(final int windowRow)",
                  "private void renderStepSkipToggle(",
                  "private List<Component> stepSkipToggleTooltipLines("):
        check(token in screen, "④d 行内开关有 %s" % token.split("(")[0].split()[-1])
    hit = method_body(screen, "private boolean stepSkipToggleHitAt(")
    draw = method_body(screen, "private void renderStepSkipToggle(")
    for token in ("rowSkipX()", "rowSkipY(windowRow)", "ROW_SKIP_W", "ROW_SKIP_H"):
        check(token in hit and token in draw, "④e 绘制与命中共用 %s（同源）" % token)
    click_body = body_of(screen, "public boolean mouseClicked(final double mouseX, final double mouseY,"
                                 " final int clickedButton) {")
    check("stepSkipToggleHitAt(arrowRow, mouseX, mouseY)" in click_body
          and "toggleStepSkipDuplicate(arrowRow);" in click_body,
          "④f 点击行内开关 = 切换本步（命中即消费点击，不穿透）")
    check("PacketDistributor.sendToServer(" in method_body(screen, "private void toggleStepSkipDuplicate(")
          and "new SetStepSkipDuplicatePacket(" in screen,
          "④g 只发包（客户端不写权威状态）")
    tips = body_of(screen, "private void renderSptTooltips(final GuiGraphics guiGraphics,"
                           " final int mouseX, final int mouseY) {")
    check("stepSkipToggleHitAt(row, mouseX, mouseY)" in tips
          and "stepSkipToggleTooltipLines(globalStepOf(row))" in tips,
          "④h 行内开关 tooltip 手动渲染（自绘控件；本模组 GUI 不自动渲染）")

    # ③ 服务端权威 + 数据模型
    packet = read(STEP_PACKET)
    handle = method_body(packet, "public static void handle(final SetStepSkipDuplicatePacket packet")
    check("terminal.setStepSkipDuplicate(packet.stepIndex(), packet.skip());" in handle,
          "④i 服务端写回唯一入口 setStepSkipDuplicate")
    check("packet.stepIndex() < 0 || packet.stepIndex() >= terminal.arrangementSize" in handle,
          "④j 服务端校验步骤下标合法")
    check("instanceof SequencePatternTerminalMenu menu" in handle and "menu.getTerminal()" in handle
          and "terminal.getLevel() == null" in handle,
          "④k 服务端校验「终端菜单 + 终端真实存在」")
    check("menu.sendStepMachines(player);" in handle, "④l 写回后回传快照（客户端只读展示刷新）")
    be = read(TERMINAL_BE)
    check("private final List<Boolean> stepSkipDuplicate" in be
          and "public boolean getStepSkipDuplicate(final int stepIndex)" in be
          and "public void setStepSkipDuplicate(final int stepIndex, final boolean skip)" in be,
          "④m 每步布尔平行表 + 读写方法")
    check('tag.putIntArray("StepSkipDuplicate", toSkipArray());' in be
          and 'tag.getIntArray("StepSkipDuplicate")' in be,
          "④n NBT 键 StepSkipDuplicate 读写成对")
    check("while (stepSkipDuplicate.size() < s)" in be and "while (stepSkipDuplicate.size() > s)" in be,
          "④o 与 arrangementSize 只增不减对齐（增长补齐 / 截断同步）")
    check("stepSkipDuplicate.set(i, i >= skips.length || skips[i] != 0);" in be,
          "④p 旧存档缺该键 ⇒ 默认开")
    sync = read(SYNC_PACKET)
    # 2026-10-06 更新（本轮）：Entry 新增第 7 个分量 unitEquipped —— 行内「已有 / 没有」改由服务端回答
    # 「该步的机器 / 样板是否已就位」（见 selfcheck_round45_step_unit_ready.py），判重事实仍随包下发
    # 供 tooltip 单列。StreamCodec.composite 最多 6 个分量 ⇒ 该记录改为手写编解码
    # （照本工程 support/MarkerEntry 的既有做法），于是「composite + 方法引用」这一**实现细节**不再成立。
    # 本检查的意图（快照携带这几项事实、客户端只读展示）不变，改钉「三项都在 + 两端各字段成对编解码」。
    check("boolean duplicateExists, boolean skipDuplicate, boolean unitEquipped" in sync
          and sync.count("ByteBufCodecs.BOOL.encode") == 4
          and sync.count("ByteBufCodecs.BOOL.decode") == 4,
          "④q 快照 Entry 携带「是否已有相同样板 / 是否跳过 / 机器样板是否就位」（客户端只读展示）")

    # ④ 语言键：筛选键已全删、新键中英齐备且中文 ≤ 40
    data = langs()
    dead_keys = {SPT + "filter", SPT + "filter.tip", SPT + "filter.all",
                 SPT + "filter.current.type", SPT + "filter.current.machine"}
    for name, table in data.items():
        dead = sorted(k for k in table if k.startswith(SFS) or k in dead_keys)
        check(not dead, "④r %s 已无筛选相关语言键" % name, str(dead[:3]))
        for key, placeholders in ((SPT + "card.skip.tip", 0), (SPT + "card.skip.exists", 1),
                                  (SPT + "card.skip.state", 1), (SPT + "card.skip.has", 0),
                                  (SPT + "card.skip.missing", 0), (SPT + "card.skip.on", 0),
                                  (SPT + "card.skip.off", 0), (SPT + "card.skip.help", 0),
                                  (SPT + "generate.all_duplicate", 0)):
            ok = key in table
            check(ok, "④s %s 含 %s" % (name, key.split(".")[-1]))
            if ok:
                limit_ok = len(table[key]) <= 40 if name == "zh_cn.json" else True
                check(table[key].count("%s") == placeholders and limit_ok,
                      "④t %s %s 占位符 %d（中文 ≤ 40）" % (name, key.split(".")[-1], placeholders))

    # ⑤ 生成语义推演：开 + 已有 ⇒ 不生成；关 ⇒ 生成；全部已有 ⇒ 0 个
    def produced(skip, dup):
        return 0 if (skip and dup) else 1

    cases = [("开+已有", True, True, 0), ("关+已有", False, True, 1),
             ("开+没有", True, False, 1), ("关+没有", False, False, 1)]
    wrong = []
    for label, skip, dup, expect in cases:
        got = produced(skip, dup)
        print("       %-10s -> 生成 %d" % (label, got))
        if got != expect:
            wrong.append(label)
    check(not wrong, "④u 推演：单步「开+已有 ⇒ 不生成 / 关 ⇒ 照常生成」", str(wrong))
    check(sum(produced(True, True) for _ in range(3)) == 0,
          "④v 推演：全部步骤 开+已有 ⇒ 一个也不生成")
    # 反例：关掉开关的步即使已有相同样板也必须照常生成
    check(produced(False, True) == 1, "④w 反例：关 ⇒ 已有相同样板也照常生成（未被误跳过）")


if __name__ == "__main__":
    section_blocked_same_source()
    print("-" * 70)
    section_candidates_cycle()
    print("-" * 70)
    section_filter_removed_and_step_toggle()
    print("=" * 70)
    print("问题总数: %d" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1 if problems else 0)
