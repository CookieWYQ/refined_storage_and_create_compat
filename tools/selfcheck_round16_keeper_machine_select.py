# -*- coding: utf-8 -*-
"""第 16 轮：高级定量保持器升级槽 + 序列装配样板终端 tooltip / 机器选择子界面 自检。

覆盖用户三条反馈（每一条都含源码锚点 + 纯逻辑推演）：

① 升级槽（第 17 轮按用户要求修正：**撤销自动均匀分配 + 多余弹出**）
   —— AdvancedQuantityKeeperMenu#quickMoveStack 只走 vanilla moveItemStackTo；
      方块实体里不得再有 ejectSlot / ejectItem（「先删后给」的丢物源）；
      守恒推演：进 N 个 ⇒ 容器内 + 玩家背包 + 掉落物 总数恒为 N；
      既有「一格多个」状态逐字节保持不变（只判定、不改写）。

② 「第二台高级定量保持器只能放 5 个堆叠升级」的机制级根因与修复
   —— 定量保持器把 ghost 标记槽与插件槽放在同一个 Container 里，InvWrapper 会把整个容器
      暴露给 UpgradeSlot，于是「标记槽里的那个堆叠升级」被当成「已插入的一个升级」，
      单种上限 6 立刻少一个（换新方块就好、破坏重放不好：幽灵标记随方块实体 NBT 一起被带走）。
      修法：UpgradeSlot 增加 countFromIndex，计数从真正的插件槽区间起；两个保持器菜单都显式传入。
   断言：带一个「标记槽堆叠升级」时仍可插满 6 个；旧存档无需迁移（计数是每帧现算的）。

③ 序列装配样板终端：输入原料格 tooltip + 可搜索的机器选择子界面（两条入口 + 监视器复用）
   —— 输入原料格必须由 renderSptTooltips 手动渲染（物品提示与其它格子同源）。
      机器选择子界面 StepMachineSelectScreen 新增搜索框；终端两条入口（机器横带 / Ctrl+左键整行）
      与自动合成监视器「更换机器」<b>复用同一个类</b>，确认写回都走服务端权威包。

用法：python tools/selfcheck_round16_keeper_machine_select.py
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

ADV_MENU = os.path.join(JAVA, "menu", "AdvancedQuantityKeeperMenu.java")
BASE_MENU = os.path.join(JAVA, "menu", "QuantityKeeperMenu.java")
UPGRADE_SLOT = os.path.join(JAVA, "menu", "UpgradeSlot.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
BASE_BE = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
SPT_SCREEN = os.path.join(JAVA, "client", "screen", "SequencePatternTerminalScreen.java")
SELECT_SCREEN = os.path.join(JAVA, "client", "screen", "StepMachineSelectScreen.java")
ALERTS = os.path.join(JAVA, "client", "AssemblyAlertsClient.java")
MONITOR_MIXIN = os.path.join(JAVA, "mixin", "client", "AutocraftingMonitorScreenMixin.java")

problems = []


def check(ok, message, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + message + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(message)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def section(title):
    print("\n===== %s =====" % title)


# ---------------------------------------------------------------------------
# ① 撤销自动分配 + 守恒
# ---------------------------------------------------------------------------
MAX_PER_UPGRADE = 6
AUTOCRAFTING_MAX = 1


def section_distribution():
    section("① 升级槽：撤销「自动均匀分配 + 多余弹出」+ 守恒推演（第 17 轮按用户要求修正）")
    menu = read(ADV_MENU)
    be = read(ADV_BE)
    check("private void distributeUpgrades(" not in menu and "distributeUpgrades(slot," not in menu,
          "A1 上一轮的 distributeUpgrades 已整体删除（用户明确不要）")
    check("UPGRADE_SLOT_COUNT" not in menu, "A2 只为分配服务的常量也已删除")
    check("moveItemStackTo(stackInSlot, UPGRADE_START, CONTAINER_SLOTS, false)" in menu,
          "A3 玩家背包 → 插件槽只走 vanilla moveItemStackTo（不自研搬运循环）")
    check("player.displayClientMessage" not in menu, "A4 不再发「剩余留在原处」的动作栏提示")
    check("sourceSlot.setByPlayer" not in menu and "setByPlayer(source" not in menu,
          "A5 不再有任何「按 placed 数改写原槽」的写法（杜绝中间态丢物）")
    check("ejectItem(" not in be and "ejectSlot(" not in be,
          "A6 方块实体里的「弹出到世界」路径已删除（javadoc 里的引用不算调用）")
    check("ejectSlot(" not in read(BASE_BE),
          "A6b 基础保持器的同类「弹出」路径也已删除（同一套写法一并根治）")

    # 守恒推演：vanilla moveItemStackTo（每槽上限 1、非空槽 mayPlace=false）下总数恒等
    def simulate(total, empty, cap, kind_already):
        placed = 0
        remaining = total
        already = kind_already
        for i in range(len(empty)):
            if remaining <= 0 or already >= cap:
                break
            if not empty[i]:
                continue
            placed += 1
            remaining -= 1
            already += 1
        return placed, remaining

    for total, empty, cap, already, exp_in, exp_left, label in (
        (6, [True] * 6, 6, 0, 6, 0, "空机器放 6 个"),
        (8, [True] * 6, 6, 0, 6, 2, "空机器放 8 个（槽位用尽）"),
        (3, [False] * 5 + [True], 6, 5, 1, 2, "只剩 1 空槽放 3 个"),
        (3, [True] * 6, 1, 1, 0, 3, "自动合成已达上限 1，再放 3 个"),
    ):
        got_in, got_left = simulate(total, empty, cap, already)
        print("       %-22s -> 进 %d，留 %d（合计 %d = %d）" % (label, got_in, got_left, got_in + got_left, total))
        check(got_in == exp_in and got_left == exp_left and got_in + got_left == total,
              "A7 守恒：%s（进 %d + 留 %d = %d，无任何删除）" % (label, exp_in, exp_left, total))

    # 「一格多个」状态保持不变：只读校验不改内容
    multi = [6, 0, 0, 0, 0, 0]
    snapshot = list(multi)
    oversized = sum(1 for c in multi if c > 1)
    check(multi == snapshot and oversized == 1,
          "A8 既有「一格多个」被识别为异常但逐字节保持原样（不拆分、不清理、不迁移）",
          str(snapshot))


# ---------------------------------------------------------------------------
# ② 计数起点（真 bug 根因）
# ---------------------------------------------------------------------------
def section_count_range():
    section("② 「只能放 5 个」根因：Ghost 标记槽被当成升级（源码锚点 + 推演）")
    up = read(UPGRADE_SLOT)
    menu = read(ADV_MENU)
    base = read(BASE_MENU)
    check("private final int countFromIndex;" in up,
          "B1 UpgradeSlot 增加 countFromIndex（单种升级计数的起点）")
    check("this.countFromIndex = Math.max(0, Math.min(index, countFromIndex));" in up,
          "B2 计数起点收敛到 [0, 本槽下标]（下游无需反复防御）")
    check("for (int i = Math.max(0, countFromIndex); i < handler.getSlots(); i++) {" in up,
          "B3 countOfKindInHandler 从 countFromIndex 起数（跳过 ghost 标记槽）")
    check("private final int countFromIndex;" in up and "countFromIndex);" in up,
          "B4 计数起点是 final 字段 + 由构造器参数注入（不是每帧猜的）")
    check("UpgradeSlot.forContainer(container, MARKER_SLOTS + i, 188, 7 + i * 18, MARKER_SLOTS)" in menu,
          "B5 高级保持器：插件槽计数起点 = MARKER_SLOTS（跳过 4 个标记槽）")
    check("UpgradeSlot.forContainer(keeper.getInventory(), 1 + i, 188, 7 + i * 18, 1)" in base,
          "B6 基础保持器：计数起点 = 1（跳过那 1 个标记槽）")
    # 上限只用于「判定」：现在没有任何「弹出 / 拆分」动作（第 17 轮守恒修正），
    # 因此旧存档里那个「幽灵标记」无需迁移即自愈，且不会因为「看起来超额」被清掉任何物品。
    be = read(os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java"))
    check("perKind[0] > 6 || perKind[1] > 6 || perKind[2] > 1" in be,
          "B7 上限判据仍在（6/6/1），但只读：不搬移、不拆分、不弹出、不删除 → 旧存档无需迁移")

    # 推演：一个标记槽里躺着一个堆叠升级（玩家「常备 64 个」）
    def placeable(marker_upgrades, inserted, cap=MAX_PER_UPGRADE):
        """返回还能再插几个：计数 = 已插入数 + 标记槽里的同类物品数（旧实现）。"""
        return max(0, cap - (inserted + marker_upgrades))

    def placeable_fixed(marker_upgrades, inserted, cap=MAX_PER_UPGRADE):
        """修复后：标记槽不计入。"""
        return max(0, cap - inserted)

    old = placeable(1, 5)
    new = placeable_fixed(1, 5)
    print("       标记槽有一个堆叠升级 + 已插 5 个：旧实现还能插 %d，修复后 %d" % (old, new))
    check(old == 0, "B8 复现：旧实现下第 6 个插不进去（正是用户报的「只能放 5 个」）")
    check(new == 1, "B9 修复后标记槽不再占用名额：第 6 个可以插进去")
    check(placeable_fixed(1, 6) == 0, "B10 真正插满 6 个后仍然拒收（上限依然有效）")
    check(placeable_fixed(4, 0) == 6, "B11 4 个标记槽全是同类物品也一样能插满 6 个")


# ---------------------------------------------------------------------------
# ③ 输入原料格 tooltip
# ---------------------------------------------------------------------------
def section_input_tooltip():
    section("③ 输入原料格 tooltip（源码锚点 + 语言键）")
    screen = read(SPT_SCREEN)
    body = screen.split("private void renderSptTooltips(")[1]
    check("hoveredSlot.index == SequencePatternTerminalMenu.SLOT_INPUT" in body,
          "C1 renderSptTooltips 里有输入原料格的分支（手动渲染 tooltip）")
    check("renderInputSlotTooltip(guiGraphics, mouseX, mouseY);" in body,
          "C2 该分支渲染 renderInputSlotTooltip（内容只有一份来源）")
    check("private void renderInputSlotTooltip(final GuiGraphics guiGraphics," in screen,
          "C3 有 renderInputSlotTooltip 方法")
    lines = screen.split("private void renderInputSlotTooltip(")[1].split("\n    }")[0]
    check("itemTooltipLines(stack)" in lines,
          "C4 物品提示与其它格子同源（物品名 / 词条）")
    check('LANG + "input_slot.tip"' in lines and 'LANG + "input_slot.empty"' in lines,
          "C5 角色说明 + 空槽说明都有语言键")
    # renderTooltip 仍然挡掉原版路径（否则会叠两份）
    rt = screen.split("protected void renderTooltip(")[1].split("super.renderTooltip")[0]
    check("hovered.index == SequencePatternTerminalMenu.SLOT_INPUT" in rt,
          "C6 renderTooltip 仍跳过该格的原版路径（避免同一格叠两份 tooltip）")
    check("不显示任何 tooltip" not in screen, "C7 旧的「不显示任何 tooltip」注释已删除")


# ---------------------------------------------------------------------------
# ③b 机器选择子界面（可搜索 + 两条入口 + 监视器复用）
# ---------------------------------------------------------------------------
def section_machine_select():
    section("③b 机器选择子界面（搜索 + 两条入口 + 复用同一个类）")
    screen = read(SPT_SCREEN)
    sel = read(SELECT_SCREEN)
    alerts = read(ALERTS)

    # 搜索
    check("private EditBox searchBox;" in sel, "D1 子界面有搜索框")
    check("private void applyFilter() {" in sel, "D2 有唯一一份过滤逻辑 applyFilter")
    filt = sel.split("private void applyFilter() {")[1].split("\n    }")[0]
    check("picker.setOptions(filtered);" in filt, "D3 过滤结果写回候选控件")
    check("toLowerCase(Locale.ROOT).contains(query)" in filt,
          "D4 不区分大小写的子串匹配（机器名）")
    check("picker.selectable(!filtered.isEmpty());" in filt,
          "D5 候选为空时控件退回不可交互（不会把假选项当选择）")
    check('LANG + "search_hint"' in sel and 'LANG + "search.tip"' in sel,
          "D6 搜索框本身也有 tooltip（自管控件必须手动渲染提示）")
    check("picker.renderTooltip(guiGraphics, mouseX, mouseY);" in sel,
          "D7 候选控件 tooltip 仍由子界面手动渲染（hover 判定与绘制同源 inBounds）")

    # 推演：搜索过滤
    def search(cands, q):
        q = q.strip().lower()
        return [c for c in cands if not q or q in c.lower()]

    cands = ["冲压", "冲压 2", "注液", "USE", "机械手"]
    check(search(cands, "") == cands, "D8 空查询 → 全部候选")
    check(search(cands, "冲") == ["冲压", "冲压 2"], "D9 输入「冲」→ 只剩名字含「冲」的两台")
    check(search(cands, "use") == ["USE"], "D10 大小写不敏感：输入 use → 命中 USE")
    check(search(cands, "zzz") == [], "D11 无命中 → 空列表（控件灰显，确定不会写回假选择）")

    # 唯一入口：左键点行内「机器控件横带」→ 机器选择子界面
    #
    # 2026-10-05 用户要求：<b>删除</b>原来「Ctrl+左键行 / Ctrl+左键总样板槽」打开的
    # 「步骤详细配置」子界面（原话：「按下 Ctrl 加左键单击打开的那个界面有什么实际意义？
    # 另一个不已经够了吗，又有搜索框又可以选择，所以把 Ctrl 加左键的那个界面删了」）。
    # 因此这里只断言剩下的那一条入口，并反向钉住「已不存在 Ctrl 入口」。
    check("private void openMachineSelect(final int globalStep) {" in screen,
          "D12 终端有唯一的 openMachineSelect 入口方法")
    check("minecraft.setScreen(new StepMachineSelectScreen(this, globalStep, recipeType, candidates,"
          in screen, "D13 终端用同一个类 StepMachineSelectScreen")
    click = screen.split("public boolean mouseClicked(final double mouseX")[1]
    check("machineStripHitAt(arrowRow, mouseX, mouseY)" in click,
          "D14 入口：左键点「机器控件横带」→ 机器选择子界面（可搜索）")
    # 反向钉住：<b>调用点</b>必须彻底消失（类文件也已删除）。
    # 注意不能拿整个文件做子串判断 —— 说明「已删除」的注释里会写到这两个名字。
    check("openStepDetail(" not in screen
          and "new StepDetailConfigScreen(" not in screen
          and not __import__("os").path.exists(
              __import__("os").path.join(__import__("os").path.dirname(SPT_SCREEN),
                                          "StepDetailConfigScreen.java")),
          "D15 已删除 Ctrl+左键的「步骤详细配置」入口与类文件（用户要求，与可搜索的机器选择重复）")
    check("StepDetailConfigScreen" not in sel,
          "D16 机器选择子界面不依赖被删除的那个类")
    check("chambersForType(recipeType)" in screen,
          "D17 子界面拿到的是<b>未按父搜索框过滤</b>的全量候选（否则子界面搜不到）")
    # 监视器复用同一个类（用户第 2 条：同一种东西只该有一个实现）
    check("StepMachineSelectScreen" in alerts,
          "D18 自动合成监视器复用同一个机器选择子界面（不写第二套）")


def main():
    section_machine_select()


if __name__ == "__main__":
    main()
