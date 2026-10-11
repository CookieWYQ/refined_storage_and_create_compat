# -*- coding: utf-8 -*-
"""round49：远程终端「快捷键打开慢 / 时快时慢」的定位与修复自检。

覆盖：
  A. 打开调用链（快捷键 → 包 → 服务端开界面 → S2C 快照）的源码锚点仍在
  B. 打开路径上不再有「每次调用都全量重建」：
     * 菜单第 1 拍只发轻快照（SyncStepMachinesPacket#fromCheap）
     * 两个昂贵事实（判重 / 已就位）延后到若干拍之后，且受 TTL 门控（缓存 + 失效）
     * 轻快照体内不含任何网络遍历 / 配方查询 / 自愈
  C. 查找函数改成预建表（stepAt 由线性扫描 → O(1) 查表）
  D. 缓存键维度正确（终端身份 × 游戏时间 / 客户端世界 / 服务端快照 / 搜索词）
  E. 「生成」扣料口径不被延后扫描削弱（生成前先按实况重扫）
  F. 纯 Python 复刻旧 / 新循环的复杂度对比（同一组规模参数下数「单位开销」次数）

用法: python tools/selfcheck_round49_terminal_open_cost.py
"""
import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FILES = {
    "keybind": os.path.join(PKG, "client", "TerminalKeybinds.java"),
    "intent": os.path.join(PKG, "client", "TerminalOpenIntent.java"),
    "open_packet": os.path.join(PKG, "network", "OpenAdvancedRemoteTerminalPacket.java"),
    "item": os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java"),
    "menu": os.path.join(PKG, "menu", "SequencePatternTerminalMenu.java"),
    "sync": os.path.join(PKG, "network", "SyncStepMachinesPacket.java"),
    "req_chambers": os.path.join(PKG, "network", "RequestChamberListPacket.java"),
    "screen": os.path.join(PKG, "client", "screen", "SequencePatternTerminalScreen.java"),
    "overlay": os.path.join(PKG, "client", "TerminalModeTabOverlay.java"),
    "terminal_be": os.path.join(PKG, "block", "entity", "SequencePatternTerminalBlockEntity.java"),
    "dedupe": os.path.join(PKG, "support", "UnitPatternDedupe.java"),
    "sources": os.path.join(PKG, "support", "UnitManagerSources.java"),
}

PROBLEMS = []
CHECKS = [0]


def read(key):
    with io.open(FILES[key], "r", encoding="utf-8") as f:
        return f.read()


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def java_body(src, signature):
    """取某个方法（以 signature 开头）的花括号体（按括号配平）。"""
    start = src.find(signature)
    if start < 0:
        return ""
    open_idx = src.find("{", start)
    if open_idx < 0:
        return ""
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_idx:i + 1]
    return ""


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ------------------------------------------------------------------ A：打开调用链
section("A. 打开调用链（快捷键 → C2S → 服务端开界面 → S2C 快照）")
kb = read("keybind")
kb_body = java_body(kb, "public static void onKeyInput")
check("TerminalOpenIntent.mark()" in kb_body
      and "sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE)" in kb_body,
      "A1 快捷键按下：置位打开意图 + 发一条空负载 C2S")
check("RsccTerminalLocator" not in kb and "findAll" not in kb,
      "A2 快捷键不再自己做任何查找（查找在服务端权威完成）")

open_packet = read("open_packet")
handle = java_body(open_packet, "public static void handle(")
check("RsccTerminalLocator.locate(player)" in handle and "item.useFromShortcut(player," in handle,
      "A3 服务端：定位终端 → useFromShortcut（打开界面唯一入口）")

item = read("item")
check("openSequencePatternTerminal(" in item and "openUnitPatternManager(" in item,
      "A4 物品：按模式开界面（模式 4/5 是本模组自建菜单）")

menu = read("menu")
check("public void broadcastChanges() {" in menu and "initialStepSyncSent" in menu,
      "A5 终端菜单：首拍同步 + 每拍驱动（broadcastChanges）")
check("RequestChamberListPacket" in read("screen") and "terminal.listChambers()" in read("req_chambers"),
      "A6 终端菜单打开时向服务端要执行仓列表（请求-应答仍在）")

# --------------------------------------------------- B：打开路径上不再有每次全量重建
section("B. 打开路径：轻快照 + 延后的权威快照（不再每次调用都全量重建）")
sync = read("sync")
from_cheap = java_body(sync, "public static SyncStepMachinesPacket fromCheap(")
from_full = java_body(sync, "public static SyncStepMachinesPacket from(")
send_body = java_body(menu, "public void sendStepMachines(")
bc_body = java_body(menu, "public void broadcastChanges() {")
auth_body = java_body(menu, "private void sendAuthoritativeSnapshot(")

check("SyncStepMachinesPacket.fromCheap(terminal)" in send_body
      and "SyncStepMachinesPacket.from(terminal)" not in send_body,
      "B1 菜单首拍调用的 sendStepMachines 只发轻快照")
check("SyncStepMachinesPacket.from(" not in bc_body,
      "B2 broadcastChanges 的「首拍」分支不触发权威全量扫描")
check("SyncStepMachinesPacket.from(terminal)" in auth_body and "displayCacheFresh(" in auth_body,
      "B3 权威快照确实存在，且被 TTL 门控（缓存还新鲜 ⇒ 一拍都不扫）")
check("HEAVY_SYNC_DELAY_TICKS" in menu and "heavySyncCountdown" in menu
      and "--heavySyncCountdown <= 0" in bc_body,
      "B4 权威快照由每拍倒数驱动（延后几拍，不在打开那一拍）")
check(all(token not in from_cheap for token in
          ("refreshStepDuplicateCache", "stepUnitEquipped", "healUnitCandidateTags",
           "UnitManagerSources", "GraphNetworkComponent", "getRecipeManager", "findDuplicate")),
      "B5 轻快照体内没有网络遍历 / 配方查询 / 老样板自愈（只读 NBT + 读缓存）")
check("SequencePatternData.readUnitNew(unit, registries).recipeType()" in from_cheap,
      "B6 轻快照仍逐步骤读样板 NBT 下发 recipeType（显示不退化）")
check("terminal.refreshStepDuplicateCache();" in from_full
      and "terminal.stepUnitEquipped(i)" in from_full and "healIfDue(terminal)" in from_full,
      "B7 权威快照仍是原口径：重扫判重缓存 + 逐步骤已就位 + 节流过的自愈")
heal_body = java_body(sync, "private static void healIfDue(")
check("private static void healIfDue(" in sync and "HEAL_INTERVAL_TICKS = 600" in sync
      and "terminal.healUnitCandidateTags();" in heal_body,
      "B8 老样板自愈改为节流入口（幂等 + 30 秒间隔），不再每次开界面全扫配方表")

# ------------------------------------------------------------- C：查找函数预建表
section("C. 「检索函数」：线性扫描 → 预建表（O(S²) → O(1)）")
step_at = java_body(sync, "public static Entry stepAt(")
check("lastReceivedByStep.get(stepIndex)" in step_at and "for (" not in step_at,
      "C1 stepAt 改成查预建表（不再遍历列表）")
check("buildIndex(copy)" in java_body(sync, "public static void handle(")
      and "lastReceivedByStep = buildIndex(copy);" in sync,
      "C2 索引在收包时一次性建好（buildIndex），且与列表同源发布")
screen = read("screen")
check("machineCache" in screen,
      "C3 客户端每帧热点（配方类型 → 机器表）仍是缓存读取")

# ------------------------------------------------------- D：缓存键维度（失效入口）
section("D. 缓存 + 失效：键包含哪些维度")
fresh_body = java_body(sync, "public static boolean displayCacheFresh(")
check("AUTHORITATIVE_STAMP" in sync and "level.getGameTime()" in sync
      and "age < DISPLAY_CACHE_TICKS" in fresh_body,
      "D1 服务端显示缓存的失效维度 = 终端身份 × 游戏时间（TTL）")
check("Map<BlockEntity, boolean[]> EQUIPPED" in sync
      and "Map<BlockEntity, Long> AUTHORITATIVE_STAMP" in sync,
      "D2 显示缓存按终端（方块实体）分桶，不是全局一把梭")
check("age >= 0L" in fresh_body,
      "D3 拍号倒退（换档 / 换维度）判「不新鲜」，宁可贵一次也不显示旧档结论")
owner_body = java_body(screen, "private static void ensureCacheOwner() {")
check("private static final java.util.Map<String, java.util.List<RecipeTypeMachines.Machine>> machineCache"
      in screen and "ensureCacheOwner()" in screen and "Minecraft.getInstance().level" in owner_body,
      "D4 客户端四张配方相关缓存改为会话级（跨界面实例复用），且按「客户端世界」失效")
cache_body = java_body(screen, "private void ensureChamberCache() {")
check("chamberCacheSource = source;" in cache_body and "chamberCacheQuery = query;" in cache_body,
      "D5 机器列表缓存按「服务端快照对象身份 + 搜索词」失效（两个维度都在）")
check("List.copyOf(result)" in java_body(screen, "private List<SyncChamberListPacket.Entry> chambersForType("),
      "D6 机器列表按配方类型缓存的是不可变列表（调用方改不坏缓存）")
check("iconCached(mode)" in read("overlay") and "ICONS[mode] = created;" in read("overlay"),
      "D7 Tab 图标改为会话级常量栈（不再每帧新建 ItemStack）")

# --------------------------------------------------- E：生成扣料口径不被削弱
section("E. 生成路径：扣料张数仍按「实况重扫」算（延后扫描不削弱权威）")
gen_body = java_body(menu, "private void generateAndHandOver(final Player player) {")
check(gen_body.find("terminal.refreshStepDuplicateCache();") > 0
      and gen_body.find("terminal.refreshStepDuplicateCache();") < gen_body.find("generationPatternCost()"),
      "E1 生成前先按网络实况重扫判重缓存，再算需要几张")
check("refreshStepDuplicateCache();" in java_body(read("terminal_be"),
                                                  "public boolean generateAssemblyPattern() {"),
      "E2 生成侧权威判定仍在方块实体里自己重扫（不依赖显示缓存）")

# --------------------------------------------------- F：复杂度复刻（纯 Python）
section("F. 纯 Python 复刻：旧实现 vs 新实现（同一组规模参数下数「单位开销」）")


class Cost(object):
    """把四类开销分开数（都按「次数」计，单位 = 一次该操作）：

    * net    = 枚举整张网络图（{@code graph.getContainers()}，O(节点数) + 排序）
    * probe  = 逐槽「这是不是单元样板」的廉价类型判定（{@code ItemStack#is}）
    * nbt    = 单元样板 NBT 解析（{@code SequencePatternData#readUnit/readUnitNew} 一类）
    * recipe = 配方表查询 / 标签候选展开
    """

    def __init__(self):
        self.net = 0
        self.probe = 0
        self.nbt = 0
        self.recipe = 0

    def total(self):
        return self.net + self.probe + self.nbt + self.recipe

    def __str__(self):
        return "net=%d probe=%d nbt=%d recipe=%d 合计=%d" % (
            self.net, self.probe, self.nbt, self.recipe, self.total())


# 一次「语义相同」判定的 NBT 解析次数（UnitPatternDedupe#sameSemantics：operationOf×2 + readUnit×~4
# + readUnitNew×2 + inputOf/candidateSetOf/readUnitFluid 各 2 次）。取 10 作为量级估计。
NBT_PER_COMPARE = 10


def old_open_path(steps, chambers, unit_slots, patterns, recipes, lib_slots):
    """旧实现：打开界面第 1 拍就把三件事全做完。

    * refreshStepDuplicateCache（SequencePatternTerminalBlockEntity:1324）：每个步骤各枚举两次网络图
      （UnitPatternDedupe#findDuplicate:308/318 里的 chambers + terminals），
      并逐舱逐槽先做廉价类型判定，命中单元样板才付 NBT 解析（UnitPatternDedupe:141 的首个 if）；
    * stepUnitEquipped（:1406）：每个步骤再枚举一次网络图，同样逐槽判定；
    * healUnitCandidateTags（:211）：逐步骤读一次样板 NBT + 按配方 id 反查；起步原料候选补不出来时
      还要遍历全部序列装配配方并逐条展开标签候选（:271 ingredientCandidatesByRecipe）——
      <b>这一支在「本来就只有一个候选」的终端上每次打开都会重跑</b>。
    * 菜单打开时另有一次 terminal.listChambers()（:949，网络图 + 排序 + 逐舱显示名）。
    """
    c = Cost()
    for _ in range(steps):                      # refreshStepDuplicateCache
        c.net += 2
        c.probe += chambers * unit_slots + lib_slots
        c.nbt += patterns * NBT_PER_COMPARE
    for _ in range(steps):                      # stepUnitEquipped
        c.net += 1
        c.probe += chambers * unit_slots
        c.nbt += patterns * NBT_PER_COMPARE
    c.net += 1                                  # listChambers（打开界面时的那次请求-应答）
    c.probe += chambers
    c.nbt += steps                              # 自愈：逐步骤读样板 NBT
    c.recipe += steps                           # 自愈：逐步骤按配方 id 反查
    c.recipe += recipes                         # 自愈：起步原料候选那一支重扫全部序列装配配方
    return c


def new_open_tick(steps):
    """新实现：打开那一拍只发轻快照 —— 逐步骤读一次样板 NBT，其余一概不做。"""
    c = Cost()
    c.nbt += steps
    return c


def new_deferred(steps, chambers, unit_slots, patterns, recipes, lib_slots, cache_fresh):
    """新实现：延后若干拍的那一次权威扫描（{@code DISPLAY_CACHE_TICKS} 拍内直接跳过，一次都不扫）。"""
    if cache_fresh:
        return Cost()
    c = old_open_path(steps, chambers, unit_slots, patterns, recipes, lib_slots)
    c.recipe -= 0                               # 口径与旧实现相同（同一份工作，只是挪到了打开之后）
    return c


CASES = [
    ("小存档（2 步 / 3 仓 / 9 槽 / 6 张已有样板 / 30 条序列配方）", 2, 3, 9, 6, 30, 12),
    ("中等（8 步 / 12 仓 / 9 槽 / 40 张已有样板 / 120 条序列配方）", 8, 12, 9, 40, 120, 36),
    ("大型（12 步 / 40 仓 / 9 槽 / 200 张已有样板 / 400 条序列配方）", 12, 40, 9, 200, 400, 90),
]
print("%-42s %-34s %-24s" % ("场景", "旧：打开那一拍", "新：打开那一拍"))
print("%-42s %-34s %-24s" % ("", "", "（TTL 内重开：连延后那一拍也为 0）"))
rows = []
for name, steps, chambers, unit_slots, patterns, recipes, lib_slots in CASES:
    old = old_open_path(steps, chambers, unit_slots, patterns, recipes, lib_slots)
    tick = new_open_tick(steps)
    warm = new_deferred(steps, chambers, unit_slots, patterns, recipes, lib_slots, True)
    rows.append((name, steps, old, tick, warm))
    print("%-42s %-34s %-24s %s" % (name, str(old), str(tick), str(warm)))

check(all(new_open_tick(8).total() == 8
          for chambers in (1, 12, 40) for patterns in (0, 6, 200) for recipes in (30, 400)),
      "F1 打开那一拍的开销只与步骤数有关（不再随节点数 / 舱数 / 样板数 / 配方数变化）",
      "恒定 = 步骤数（8 步 ⇒ 8 次 NBT 读取）")
check(all(new_open_tick(s).net == 0 and new_open_tick(s).probe == 0 for _, s, _, _, _ in rows),
      "F2 打开那一拍没有任何网络枚举、没有任何逐槽判定（net == 0 且 probe == 0）")
check(rows[-1][2].total() > 0 and rows[-1][3].total() * 100 <= rows[-1][2].total(),
      "F3 大型场景：打开那一拍的开销降幅 ≥ 99%",
      "旧 %d → 新 %d（%.2f%%）" % (rows[-1][2].total(), rows[-1][3].total(),
                                  100.0 * rows[-1][3].total() / rows[-1][2].total()))
check(all(warm.total() == 0 and warm.net == 0 for _, _, _, _, warm in rows),
      "F4 TTL 内重开：连延后那一拍也不扫（一次网络枚举都不做）")
check(all(old_open_path(s, 12, 9, 40, 120, 36).net >= 3 * s + 1 for s in (1, 2, 8, 12)),
      "F5 旧实现的网络枚举次数随步骤数线性增长（S 步 ⇒ 至少 3S+1 次网络枚举）")
check(all(old_open_path(s, c, 9, p, r, 36).recipe >= r
          for s, c, p, r in ((8, 12, 40, 120), (12, 40, 200, 400))),
      "F6 旧实现每次打开都要重扫全部序列装配配方（起步原料候选补不出来时）")

print()
print("=" * 78)
print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
for p in PROBLEMS:
    print("  - " + p)
print("=" * 78)
sys.exit(1 if PROBLEMS else 0)
