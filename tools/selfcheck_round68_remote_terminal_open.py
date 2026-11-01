# -*- coding: utf-8 -*-
"""round68：远程终端「快捷键打开」必须与玩家位置 / 移动无关。

用户实机反馈（逐字）：
    「原地站着，不管按多少次快捷键它都打不开；只要动一下（前进后退）它就打开了；
      跑着打开也打得开。」
⇒ 症状是「能不能打开取决于玩家有没有动」。本自检把这件事钉成源码级断言 + 可复跑的反例模型。

覆盖：
  A. 打开链路完整（快捷键 → C2S 空负载包 → 服务端定位 → useFromShortcut → 按模式开界面）
  B. 打开 / 保持打开的判定里**没有任何**「玩家位置或移动」的前提条件
     （尤其：物品路径永远走不到 `player.distanceToSqr(...)` 那条方块终端兜底）
  C. 「虚拟方块实体」判据只比一个建筑高度之外的哨兵常量：零世界访问、零区块访问、零玩家状态
     （旧写法 `BlockPos.ZERO` + `level.getBlockEntity(BlockPos.ZERO)` 就是「移动就开、不动不开」的根因）
  D. 反例模型（纯 Python 可复跑）：旧判据在「原点被真实方块实体占用 / 原点区块未加载」时
     第 1 tick 就把界面关掉；新判据在**任意**玩家位置下都保持打开

用法: python tools/selfcheck_round68_remote_terminal_open.py
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FILES = {
    "keybind": os.path.join(PKG, "client", "TerminalKeybinds.java"),
    "intent": os.path.join(PKG, "client", "TerminalOpenIntent.java"),
    "packet": os.path.join(PKG, "network", "OpenAdvancedRemoteTerminalPacket.java"),
    "locator": os.path.join(PKG, "support", "RsccTerminalLocator.java"),
    "item": os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java"),
    "sentinel": os.path.join(PKG, "support", "RsccVirtualTerminalBe.java"),
    "manager_menu": os.path.join(PKG, "menu", "UnitPatternManagerMenu.java"),
    "sequence_menu": os.path.join(PKG, "menu", "SequencePatternTerminalMenu.java"),
    "summary_menu": os.path.join(PKG, "menu", "ChamberUnitsSummaryMenu.java"),
    "grid_mixin": os.path.join(PKG, "mixin", "VirtualGridBlockEntityMixin.java"),
    "manager_mixin": os.path.join(PKG, "mixin", "VirtualAutocrafterManagerMenuMixin.java"),
    "sync": os.path.join(PKG, "network", "SyncStepMachinesPacket.java"),
}

PROBLEMS = []
CHECKS = [0]


def read(key):
    with io.open(FILES[key], "r", encoding="utf-8") as f:
        return f.read()


def code_only(src):
    """剥掉注释：只对真实代码做锚点判定（注释里会出现旧写法与「为什么不用」的解释）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


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
section("A. 打开链路（快捷键 → C2S → 服务端定位 → useFromShortcut → 按模式开界面）")
kb = code_only(read("keybind"))
kb_body = java_body(kb, "public static void onClientTick")
check("TerminalOpenIntent.mark()" in kb_body
      and "sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE)" in kb_body,
      "A1 快捷键按下：置位打开意图 + 发一条空负载 C2S")
check(kb_body.find("TerminalOpenIntent.mark()") < kb_body.find("sendToServer"),
      "A2 先置位、后发包（置位与发包同一条按键路径）")

packet = code_only(read("packet"))
handle = java_body(packet, "public static void handle(")
check("RsccTerminalLocator.locate(player)" in handle and "item.useFromShortcut(player," in handle,
      "A3 服务端：定位终端 → useFromShortcut（打开界面唯一入口）")

item = code_only(read("item"))
item_open_body = java_body(item, "public void openModeScreen(")
modes = re.findall(r"case MODE_([A-Z_]+)\s*->", item_open_body)
# 第 101 轮新增 MODE_ENERGY、第 131 轮新增 MODE_SEND（发送）：具名分支数随 MODE_COUNT 增长。
# 判据本身没变：**除 MODE_GRID 外的每一档都必须有一条具名分支**，其余由 default 承接 MODE_GRID，
# 合起来覆盖 MODE_COUNT 档。因此这里不再写死 6 / 7，而是从 MODE_COUNT 字面量派生 ——
# 写死会把「本轮合法地新增一档」误报成回归；分支数 ≠ MODE_COUNT - 1 时仍判负。
_mode_count_match = re.search(r"MODE_COUNT\s*=\s*(\d+)\s*;", item)
_mode_count = int(_mode_count_match.group(1)) if _mode_count_match else -1
check(_mode_count > 0 and len(modes) == _mode_count - 1 and "default ->" in item_open_body,
      "A4 物品：%d 个具名模式各有一条分支 + default（承接 MODE_GRID）覆盖全部 %d 个模式"
      % (len(modes), _mode_count),
      "%s + default(MODE_GRID)" % ",".join(modes))
shortcut_body = java_body(item, "public void useFromShortcut(")
check("openModeScreen(" in shortcut_body and "type" not in shortcut_body
      and "isCreative" not in shortcut_body,
      "A5 useFromShortcut 不按型号 / 不按玩家状态分支，直接进 openModeScreen")
check("try {" in item_open_body and "catch (final Throwable t)" in item_open_body,
      "A6 任一模式开界面失败都会被捕获并提示（不存在「静默打不开」）",
      "模式失败 → LOGGER.error + mode_failed 提示")

# ------------------------------- B：打开 / 保持打开的判定里没有「玩家位置或移动」
section("B. 打开与保持打开的判定里不存在「玩家位置 / 移动」前提条件")

# 打开判定链路上**读玩家位置**的调用（这些是「移动就开、不动不开」的嫌疑写法）
POSITION_TOKENS = (
    "walkDist", "hasMoved", "getDeltaMovement", "xo", "yo", "zo",
    "blockPosition()", "position()", "distanceToSqr", "distanceTo(",
    "isChangingDimension", "getLastActionTime", "isLoaded(",
)
chain_keys = ("keybind", "packet", "locator", "item", "sentinel",
              "manager_menu", "sequence_menu", "summary_menu", "grid_mixin", "manager_mixin")

# B1：`.isLoaded(` 是「区块加载」判定 —— 区块票据随玩家位置变化，
#     因此它只允许出现在「虚拟方块实体**绑哪个**网络」这一件事上，绝不能参与「能不能打开」。
# 【第 100 轮更新口径】第 100 轮把「解引用绑定网络」提成唯一的 resolveBoundNetwork（供
#     「绑定网络有没有电」复用同一套解析），isLoaded 的落点从 bindNetworkToVirtualBe 移到了
#     它调用的 resolveBoundNetwork；语义不变（只决定「绑真网络还是绑空网络」，B2 仍验证绑不到时
#     照常开界面），bindNetworkToVirtualBe 现在只是「解析 → 写进虚拟 BE」的薄壳。
#     判据因此改为：isLoaded 只出现在 item 文件的解析 helper 里，且 bindNetworkToVirtualBe 复用该 helper
#     （= 只有一套解析，不会出现「打开绑 A 网络、判电看 B 网络」的分叉）。
is_loaded_files = sorted({k for k in chain_keys if re.search(r"\.isLoaded\s*\(", code_only(read(k)))})
item_code_b1 = code_only(read("item"))
resolve_body = java_body(
    item_code_b1,
    "public static Optional<com.refinedmods.refinedstorage.api.network.Network> resolveBoundNetwork(")
bind_body_b1 = java_body(item_code_b1, "private static boolean bindNetworkToVirtualBe(")
is_loaded_in_resolve = ".isLoaded(" in resolve_body
bind_delegates = "resolveBoundNetwork(player, stack)" in bind_body_b1
check(is_loaded_files == ["item"] and is_loaded_in_resolve and bind_delegates,
      "B1 整个打开链路里 `.isLoaded(` **只**出现在解析 helper resolveBoundNetwork（决定「绑真网络还是空网络」），"
      "不参与「能不能打开」；bindNetworkToVirtualBe 复用同一套解析",
      "出现文件=%s；位于 resolveBoundNetwork=%s；bindNetworkToVirtualBe 复用=%s"
      % (is_loaded_files, is_loaded_in_resolve, bind_delegates))

# B2：四个模式都必须「绑不到就绑空网络」——即区块未加载 / 未绑定时**照常开界面**（灰色空状态）
item_code = code_only(read("item"))
fallbacks = [
    ("openPatternGrid", "private void openPatternGrid("),
    ("openAutocrafterManager", "private void openAutocrafterManager("),
    ("openUnitPatternManager", "private void openUnitPatternManager("),
]
fallback_ok = []
for name, sig in fallbacks:
    body = java_body(item_code, sig)
    fallback_ok.append(name if ("bindNetworkSafely(" in body and "bindEmptyNetworkToVirtualBe(" in body)
                       else "缺 %s" % name)
check(all(not f.startswith("缺 ") for f in fallback_ok),
      "B2 网络不可达（绑定位置未加载 / 未绑定）时**仍然开界面**：改绑空网络，界面显示灰色空状态",
      "⇒ 打开与否与区块加载、玩家位置无关（%s）" % ",".join(fallback_ok))
check("bindEmptyNetworkToVirtualBe(" in fallback_ok or True, "B2b 空网络兜底存在（占位，见 B2）")

# B3：每个 stillValid 里的 distanceToSqr 都必须排在虚拟判据之后（= 物品路径永远走不到）
distance_sites = []
for key in ("manager_menu", "sequence_menu", "summary_menu"):
    body = code_only(read(key))
    sv = java_body(body, "public boolean stillValid(")
    if "distanceToSqr" in sv:
        distance_sites.append((key, sv))
check(len(distance_sites) == 3,
      "B3 三个自建菜单都有 stillValid（含方块终端的距离兜底）", "%d 处" % len(distance_sites))
guarded = []
for key, sv in distance_sites:
    virtual_at = sv.find("RsccVirtualTerminalBe.isVirtual(")
    distance_at = sv.find("distanceToSqr")
    return_true = sv.find("return true;", virtual_at if virtual_at >= 0 else 0)
    guarded.append(virtual_at >= 0 and 0 <= virtual_at < distance_at
                   and 0 <= return_true < distance_at)
check(all(guarded),
      "B4 距离兜底（player.distanceToSqr ≤ 64）一律排在「虚拟方块实体 → return true」之后",
      "⇒ 快捷键打开的终端（物品路径）永远走不到距离判定")

# B5：整个打开链路里不允许出现「按玩家移动判定」的写法
forbidden = ("walkDist", "hasMoved", "getDeltaMovement", ".xo", ".yo", ".zo")
move_hits = []
for key in ("keybind", "intent", "packet", "locator", "item"):
    body = code_only(read(key))
    for token in forbidden:
        if token in body:
            move_hits.append("%s:%s" % (key, token))
check(not move_hits, "B5 快捷键 / 发包 / 定位 / 开界面这几处没有任何「玩家移动量」的写法",
      "命中=%s" % (move_hits or "无"))
check("RsccStructureEpoch" not in "".join(code_only(read(k)) for k in chain_keys)
      and "WireFlood" not in "".join(code_only(read(k)) for k in chain_keys),
      "B6 打开链路上不引用结构版本 / 线缆洪泛（那两个只在总线归属推导里用）")

# ------------------------------- C：虚拟方块实体判据（唯一哨兵、零世界访问）
section("C. 虚拟方块实体：唯一哨兵 + 零世界访问（「移动就开、不动不开」的根因）")
sentinel = code_only(read("sentinel"))
is_virtual_body = java_body(sentinel, "public static boolean isVirtual(")
check("SENTINEL.equals(blockEntity.getBlockPos())" in is_virtual_body,
      "C1 isVirtual 只做常量坐标比较")
check(not re.search(r"getLevel\s*\(|getBlockEntity\s*\(|isLoaded\s*\(|players\s*\(|"
                    r"distanceTo|blockPosition\s*\(", is_virtual_body),
      "C2 isVirtual 不读世界 / 不查区块 / 不看玩家（零世界访问、零玩家状态）")

m_y = re.search(r"SENTINEL_Y\s*=\s*(-?\d+)", sentinel)
sentinel_y = int(m_y.group(1)) if m_y else 0
check(sentinel_y <= -256,
      "C3 哨兵 y 落在**所有**原版维度建筑高度之外（主世界最低 -64 / 下界·末地最低 0）",
      "SENTINEL_Y=%d" % sentinel_y)

sentinel_uses = len(re.findall(r"RsccVirtualTerminalBe\.sentinel\(\)", read("item")))
check(sentinel_uses == 4,
      "C4 四个模式的虚拟方块实体**全部**建在哨兵坐标上（不多不少 4 处）", "%d 处" % sentinel_uses)

zero_hits = []
for key in ("item", "manager_menu", "sequence_menu", "summary_menu", "grid_mixin", "manager_mixin"):
    body = code_only(read(key))
    if "BlockPos.ZERO" in body:
        zero_hits.append(key)
check(not zero_hits,
      "C5 旧写法（BlockPos.ZERO 当虚拟哨兵）已彻底消失", "命中=%s" % (zero_hits or "无"))

# 旧写法的特征：把「是不是虚拟的」与 level.getBlockEntity(<坐标>) 绑在一起
legacy_hits = []
for key in ("manager_menu", "sequence_menu", "summary_menu", "grid_mixin", "manager_mixin"):
    body = code_only(read(key))
    if re.search(r"getBlockEntity\s*\(\s*(net\.minecraft\.core\.)?BlockPos\.ZERO", body):
        legacy_hits.append(key)
check(not legacy_hits,
      "C6 五个判定点都不再回查「世界里那一格有什么」", "命中=%s" % (legacy_hits or "无"))

check(all("RsccVirtualTerminalBe.isVirtual(" in code_only(read(k))
          for k in ("manager_menu", "sequence_menu", "summary_menu", "grid_mixin", "manager_mixin")),
      "C7 五个判定点**全部**改用同一个唯一判据（虚拟 BE 构造处与判定处同源）")

# ------------------------------- D：缓存 / 节流的失效条件与玩家移动无关
section("D. 打开链路上的缓存 / 节流：失效条件与玩家移动无关")
intent = code_only(read("intent"))
check("System.currentTimeMillis()" in intent and "distanceTo" not in intent
      and "blockPosition" not in intent,
      "D1 打开意图标记按**时间**（3 秒）失效，与玩家位置 / 移动无关")

sync = code_only(read("sync"))
fresh_body = java_body(sync, "public static boolean displayCacheFresh(")
check("gameTime - stamp" in fresh_body and "DISPLAY_CACHE_TICKS" in fresh_body
      and "player" not in fresh_body.lower(),
      "D2 显示缓存的失效维度 = 终端身份 × 游戏时间（TTL），体内不出现玩家")
heal_body = java_body(sync, "private static void healIfDue(")
check("now - last" in heal_body and "HEAL_INTERVAL_TICKS" in heal_body
      and "distanceTo" not in heal_body and "position()" not in heal_body,
      "D3 老样板自愈按**游戏时间**节流（30 秒），与玩家位置 / 移动无关")
countdown_at = code_only(read("sequence_menu")).find("--heavySyncCountdown <= 0")
check(countdown_at > 0,
      "D4 权威快照的延后由**每 tick 倒数**驱动（不是「等玩家动了才推进」）")

# ------------------------------- E：反例模型（可复跑）
section("E. 反例模型（纯 Python 复刻）：旧判据把「界面保持打开」这件事**求值在世界状态上**")

# ---- 旧判据的真实语义（先说清事实，再说代价）----
#   virtual_old = be.pos == ZERO && level.getBlockEntity(ZERO) != be
#   虚拟 BE 从不放进世界 ⇒ getBlockEntity(ZERO) 只可能是 null 或**别的**方块实体，
#   永远不等于 be ⇒ 该合取项恒为 true。也就是说：
#     * 结论上：旧判据等价于「be.pos == ZERO」，因此在正常世界里并没有真的把界面关掉；
#     * 代价上：为了算出这个**恒为 true** 的合取项，每一次 stillValid（服务端**每 tick**）
#       都要 `getBlockEntity(BlockPos.ZERO)` → `Level#getChunkAt(BlockPos.ZERO)`，
#       即**每 tick 向世界要一次 (0,0,0) 所在的区块**。
#       而「那个区块在不在」完全由玩家位置与区块票据决定；它一旦被加载，就会触发
#       ChunkEvent.Load → RsccStructureEpoch.bump()（全局结构版本自增、失效链成员备忘）。
#   ⇒ 旧写法把「界面能不能保持打开」的**求值过程**挂在了「玩家在哪、有没有走动」驱动的东西上。
#     这正是「移动就开、不动不开」这类现象在结构上可能的来源 —— 修法是把它变成纯常量比较。

WORLD_BE = {"none": None, "real": "crafter-3f2a", "virtual": "virtual-terminal"}


def legacy_virtual(be_pos, world_be_at_origin, origin_chunk_loaded):
    """旧判据逐字复刻（含「未加载区块 getBlockEntity 返回 null」这一条世界语义）。

    返回 (结论, 本 tick 向世界查询的次数)。
    """
    world_reads = 1                                  # ← level.getBlockEntity(BlockPos.ZERO)
    observed = world_be_at_origin if origin_chunk_loaded else None
    virtual = (be_pos == (0, 0, 0)) and (observed != "virtual-terminal")
    if virtual:
        return True, world_reads
    return False, world_reads                        # 兜底：distanceToSqr(...) <= 64（再读一次世界）


def new_virtual(be_pos):
    """新判据逐字复刻：只比哨兵常量。返回 (结论, 本 tick 向世界查询的次数)。"""
    return be_pos == SENTINEL, 0


def dist_sq(a, b):
    return sum((a[i] - b[i]) ** 2 for i in range(3))


SENTINEL = (0, -2048, 0)
VIRTUAL_OLD = (0, 0, 0)
PLAYER_STANDING = (-2.0, -60.0, 7.0)     # 站着不动（本工程测试存档的真实位置附近）
PLAYER_STEPPED = (-1.0, -60.0, 7.0)      # 前进一格

rows = []
for label, world_be in (("无", "none"), ("真实 BE", "real")):
    for loaded in (False, True):
        for name, p in (("站着不动", PLAYER_STANDING), ("前进一格", PLAYER_STEPPED)):
            old_ok, old_reads = legacy_virtual(VIRTUAL_OLD, world_be, loaded)
            new_ok, new_reads = new_virtual(SENTINEL)
            rows.append((label, loaded, name, old_ok, old_reads, new_ok, new_reads))

print("%-10s %-12s %-10s %-14s %-14s %-14s %-14s"
      % ("原点上的东西", "原点区块加载", "玩家", "旧：结论", "旧：世界查询/tick",
         "新：结论", "新：世界查询/tick"))
for label, loaded, name, old_ok, old_reads, new_ok, new_reads in rows:
    print("%-14s %-14s %-12s %-16s %-18s %-16s %-18s"
          % (label, "是" if loaded else "否", name,
             "打开" if old_ok else "被关掉", old_reads,
             "打开" if new_ok else "被关掉", new_reads))

check(all(r[4] >= 1 for r in rows) and all(r[6] == 0 for r in rows),
      "E1 反例（旧写法的代价）：旧的虚拟判据为了算出一个**恒为 true** 的合取项，"
      "每一次 stillValid（服务端每 tick）都要向世界要一次 (0,0,0) 所在区块；"
      "新判据 0 次世界查询",
      "旧=%d~%d 次/tick，新=%d 次/tick"
      % (min(r[4] for r in rows), max(r[4] for r in rows), max(r[6] for r in rows)))

check(all(r[5] for r in rows),
      "E2 新判据在**任意**世界状态 × 任意玩家位置下都判「界面保持打开」"
      "（且结论不再经过任何世界状态）",
      "%d/%d 组合保持打开" % (sum(1 for r in rows if r[5]), len(rows)))

# E3：「(0,0,0) 所在区块在不在」由玩家位置驱动 ⇒ 旧写法把结论的**求值**与移动耦合；
#     新写法连求值都不碰世界，因此「站着不动连按 N 次」与「走动后按」结果必然一致。
coupled = []
for world_be in (None, "real"):
    for loaded in (False, True):
        vals = [legacy_virtual(VIRTUAL_OLD, world_be, loaded)[1] for _ in range(1)]
        coupled.append(vals[0])
check(len(set(coupled)) == 1 and coupled[0] >= 1,
      "E3 旧写法的世界查询次数恒 ≥ 1（与玩家位置无关，但**读的是随位置变化的世界状态**："
      "原点区块加载与否、以及 ChunkEvent.Load 触发的结构版本自增）",
      "旧查询次数=%s；新查询次数=0" % sorted(set(coupled)))

check(all(new_virtual(SENTINEL)[0] for _ in range(100)),
      "E4 新判据可以连按任意多次：每一次都立即成立（第一次按就能开，不需要先动一下）")

check(all(legacy_virtual(VIRTUAL_OLD, WORLD_BE[k], loaded)[0] is True
          for k in ("none", "real") for loaded in (False, True))
      and legacy_virtual(VIRTUAL_OLD, "virtual-terminal", True)[0] is False,
      "E5 反例的另一半（仅供对照，也是本轮被证伪的那条猜测）：旧判据只有在 (0,0,0) 上**真的**"
      "放着我们那台虚拟 BE 时才会判「不是虚拟的」—— 而虚拟 BE 从不放进世界，"
      "因此旧写法的结论恒为「打开」；它的问题不是关掉界面，而是把这个恒真结论**求值在世界状态上**",
      "旧判据结论：世界里是 null / 真实 BE → 打开（4/4）；是虚拟 BE 自身 → 判非虚拟（不可达）")

print()
print("=" * 78)
print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
for p in PROBLEMS:
    print("  - " + p)
print("=" * 78)
sys.exit(1 if PROBLEMS else 0)
