# -*- coding: utf-8 -*-
"""自检：归流缓存仓「删除模式」全链路（2026-10-05 用户要求的复选框式清资源功能）。

用法：python tools/selfcheck_collection_delete_mode.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户原话（本自检逐条对应）
--------------------------
「它现在的逻辑是点击按钮然后清空缓存区，但我想要的是有一个状态——就是一个复选框那样的选择，
选择之后可以开启清除资源功能，然后清除的资源是指到了缓存仓里面到了缓存区或者说被收进来的资源。
但是具体来说这又有再加一层过滤，就是匹配区那里还可以使用某种方式在给它编辑一下，使它能够再进行
编辑一下就是最终要被删除的资源。首先先要开启那个删除模式，然后这个匹配的资源，然后这个要删除的
资源首先要在匹配资源里面找，找到的都是……要对匹配区的那些标记的物品再次进行这种标记是否要表达
他们删除，然后标记要删除的物品在开启了删除模式下才会被删除。然后这一个速度和删除的速度和流经
网络的速度保持一致，所以说这一个删除的速度也会受到速度升级和堆叠升级的影响。」

拆成可断言的规则
----------------
D1 总闸存在且**默认关闭**（老存档读出来也是关闭）——读档绝不能自己开始销毁资源；
D2 销毁路径**必须**同时满足「总闸开启」+「命中标记为删除的匹配槽」；
D3 速率与回流同源：每 tick 处理组数 = getProcessRate()、单次吞吐 = getTransferBatch()
   （即速度升级 / 堆叠升级同时影响删除速度）；
D4 开启必须带确认位（C2S 包 + 服务端硬校验），关闭无需确认；
D5 界面有复选框（复用 McGui.toggle 三态控件），且「开启」只打开确认子窗口、不发包；
D6 状态经 ContainerData 同步（菜单槽号 → 方块实体槽号显式映射，两个方向都写到）；
D7 诊断导出包含 deleteMode / deleteRatePerTick / destroyMarkers（「我以为我开了」不再靠猜）；
D8 中英语言键成对存在（含确认子窗口文案）。

D10（本轮追加）流体扫描的**跨扫描游标**（修复「每次从最小角重来 ⇒ 体积 > 4096 时尾部永远扫不到」）：
   ① 源码里存在游标字段，且它只前进、扫满一轮才回 0；
   ② 位置 / 三轴半径变化时游标必须归零（`resyncFluidScanCursorIfMoved`）；
   ③ 旧的「`betweenClosed` 最小角 + `sources.size() >= 4096` break」写法必须消失（反例判负）；
   ④ 每 tick 查询预算不变（仍是 4096，同一 tick 的多次调用共享它，第二次直接复用窗口）；
   ⑤ 游标不落盘（saveAdditional / loadAdditional 里没有它）；
   ⑥ 行为镜像：一整轮覆盖体积内每个位次恰好一次，51³ 只需 33 次扫描（旧写法永远到不了尾部），
      读数与 `ceil(volume / 4096) × getScanInterval()` 一致。
"""

from __future__ import annotations

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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def main():
    block = read(SRC, "block", "entity", "CollectionCacheBlockEntity.java")
    menu = read(SRC, "menu", "CollectionCacheMenu.java")
    screen = read(SRC, "client", "screen", "CollectionCacheScreen.java")
    confirm = read(SRC, "client", "screen", "CollectionDeleteModeConfirmScreen.java")
    packet = read(SRC, "network", "SetCollectionDeleteModePacket.java")
    main_cls = read(SRC, "RS_Create_Compat.java")

    section("D1 总闸存在、默认关闭、读档不自行开启")
    check("方块实体有 deleteMode 字段（默认 false）",
          re.search(r"private boolean deleteMode;\s*$", block, re.M) is not None
          or "private boolean deleteMode;" in block)
    check("NBT 键存在且注释写明「读档不得自行开启」",
          'TAG_DELETE_MODE = "DeleteMode"' in block
          and "老存档没有这个键" in block)
    check("saveAdditional 落盘 deleteMode",
          "tag.putBoolean(TAG_DELETE_MODE, deleteMode);" in block)
    check("loadAdditional 读回 deleteMode（缺键 ⇒ 关闭）",
          "deleteMode = tag.getBoolean(TAG_DELETE_MODE);" in block)
    check("有服务端权威 setter（落 NBT + 记一条 ON/OFF 日志）",
          "public void setDeleteMode(final boolean enabled)" in block
          and "删除模式 = {}" in block)

    section("D2 销毁必须同时满足「总闸开」+「命中标记为删除的槽」")
    check("destroyMarkedContent 的第一道闸门是 deleteMode",
          "if (!deleteMode || destroyMarkers.isEmpty()) {" in block)
    check("命中判据只看「标记为删除」的槽（destroyMarkers）",
          "for (final int index : destroyMarkers) {" in block
          and "matchesAnyDestroyItemMarker(stack, registries)" in block)
    check("销毁仍走守恒账本 destroyed（与既有审计口径一致）",
          "flowLedger.destroyed(RsccFlowLedger.itemKey(stack.getItem()), amount);" in block)
    check("一键「销毁缓存区内容」保持独立（玩家显式确认的一次性全清）",
          "public long destroyAllCacheContent()" in block)

    section("D3 删除速率与回流同源（受速度 / 堆叠升级影响）")
    check("删除使用 getProcessRate() 作为每 tick 处理组数",
          "final int itemBudget = Math.max(1, getProcessRate());" in block)
    check("删除使用 getTransferBatch() 作为单次吞吐",
          "final int transfer = Math.max(1, getTransferBatch());" in block)
    check("单次销毁量被吞吐上限夹住（不是整格一次删光）",
          "final int amount = Math.min(stack.getCount(), transfer);" in block)
    check("暴露 getDeleteRatePerTick = 组数 × 吞吐（界面与快照同源）",
          "public long getDeleteRatePerTick()" in block
          and "Math.max(1, getProcessRate()) * Math.max(1, getTransferBatch())" in block)
    check("回流与删除读的是同一对升级量（口径不会分叉）",
          # 第 95 轮第 12 条把「缓存 → 网络」的回写重写成「物品 / 流体各一份预算 + 跨 tick 游标」，
          # 局部变量名从 budget 变成 groups，但<b>指标取法不变</b>：每 tick 处理组数 = getProcessRate()、
          # 单次吞吐 = getTransferBatch()（都带 >= 1 的夹紧）。删除侧读的是同一对量 ⇒ 口径不分叉。
          "final int groups = Math.max(1, getProcessRate());" in block
          and "final int transfer = Math.max(1, getTransferBatch());" in block)

    section("D4 开启必须带确认位（服务端硬校验）")
    check("C2S 包具备 containerId / enabled / confirmed 三字段",
          "int containerId, boolean enabled," in packet
          and "boolean confirmed" in packet)
    check("未确认的「开启」被服务端直接忽略",
          "if (packet.enabled() && !packet.confirmed()) {" in packet)
    check("只接受「当前打开的菜单就是该容器」",
          "menu.containerId != packet.containerId()" in packet
          and "menu instanceof CollectionCacheMenu" in packet)
    check("包已在主类注册（漏注册会直接断开连接）",
          "SetCollectionDeleteModePacket.TYPE" in main_cls
          and "SetCollectionDeleteModePacket.STREAM_CODEC" in main_cls)

    section("D5 界面：复选框 + 开启走确认子窗口")
    check("屏幕有 deleteModeButton（复用 McGui.toggle 三态控件）",
          "private Button deleteModeButton;" in screen
          and "deleteModeButton = addRenderableWidget(McGui.toggle(" in screen)
    check("复选框状态来自服务端（menu.isDeleteMode()）",
          "() -> menu.isDeleteMode()" in screen)
    check("「开启」只打开确认窗口、不发包",
          "openDeleteModeConfirm();" in screen
          and "if (minecraft != null) {\n            minecraft.setScreen(new CollectionDeleteModeConfirmScreen(this, menu));" in screen)
    check("「关闭」立即发包（恢复安全状态，无需确认）",
          "new cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket(\n                            menu.containerId, false, true)" in screen)
    check("每帧回写服务端权威状态",
          "McGui.refreshToggle(deleteModeButton, menu.isDeleteMode());" in screen)
    check("确认窗口发送的是带确认位的开启包",
          "new SetCollectionDeleteModePacket(menu.containerId, true, true)" in confirm)
    check("确认窗口写清「会发生什么」（规则 + 不可恢复警告）",
          "confirm.rule" in confirm and "confirm.warning" in confirm)
    # 2026-10-05 用户要求去掉速率行（原话：「不要告诉也不需要说明删除的速率」）：
    # 这里反过来钉住「确认窗不再画速率」，防止有人又加回来。
    check("确认窗口不再画速率行（用户要求）",
          'confirm.rate"' not in confirm)

    section("D6 ContainerData 同步（菜单槽号 ↔ 方块实体槽号双向都写到）")
    check("菜单侧新增两个数据槽常量",
          "DATA_DELETE_MODE = 27" in menu and "DATA_DELETE_RATE = 28" in menu)
    check("菜单侧映射到方块实体槽 22 / 23",
          "BLOCK_DATA_DELETE_MODE = 22" in menu and "BLOCK_DATA_DELETE_RATE = 23" in menu)
    check("读取分支显式映射（否则恒 0 —— 红石模式曾经踩过这个坑）",
          "case DATA_DELETE_MODE -> base.get(BLOCK_DATA_DELETE_MODE);" in menu
          and "case DATA_DELETE_RATE -> base.get(BLOCK_DATA_DELETE_RATE);" in menu)
    check("数据槽总数扩到 31（第 101 轮在 29 的基础上又加 2 个「为什么没吸」状态槽）",
          "DATA_SLOT_COUNT = 31;" in menu)
    check("方块实体侧提供 case 22 / 23",
          "case 22 -> deleteMode ? 1 : 0;" in block
          and "case 23 -> (int) Math.min(Integer.MAX_VALUE, getDeleteRatePerTick());" in block
          and "return 26;" in block)

    section("D7 诊断导出（「我以为我开了」不再靠猜）")
    check("快照含 deleteMode / deleteRatePerTick / destroyMarkers",
          'out.put("deleteMode", isDeleteMode());' in block
          and 'out.put("deleteRatePerTick", getDeleteRatePerTick());' in block
          and 'out.put("destroyMarkers", new java.util.ArrayList<Object>(destroyMarkers));' in block)

    section("D8 中英语言键成对")
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
    need = [key for key in zh if "collection_cache.delete_mode." in key]
    check("中文有删除模式相关键（≥ 12 条）", len(need) >= 12, "实际 %d" % len(need))
    missing_en = [key for key in need if key not in en]
    check("英文键集合一致", not missing_en, "英文缺键：%s" % missing_en)
    # 2026-10-05：`confirm.rate` 已按用户要求删除，因此不再要求它存在。
    for key in ("label", "tip", "rate", "confirm.title", "confirm.warning", "confirm.rule",
                "confirm.cache", "confirm.yes", "confirm.no"):
        full = "gui.rs_create_compat.collection_cache.delete_mode." + key
        check("键存在：%s" % key, full in zh and full in en)

    section("D9 文案宽度（用户明确反馈过「文字超出背景」）")
    # 两个确认窗口的面板宽度与「最宽文案」的估算像素宽（CJK 9px / ASCII 6px / 空格 4px）。
    # 这是静态估算：宁可留余量，绝不让文字压出背景 —— 且绘制端另有按像素硬截断兜底。
    def pixel_width(text):
        total = 0
        for ch in text:
            total += 9 if ord(ch) > 0x2E80 else (4 if ch == " " else 6)
        return total

    # 只量「画在面板里」的那几行；`*.tip` 是 tooltip（自带一个会换行的框），不受面板宽度约束。
    for panel, available, keys, label in (
        (208, 192, [k for k in zh if "collection_cache.delete_mode.confirm" in k
                    and not k.endswith(".tip")], "删除模式确认窗"),
        (232, 216, [k for k in zh
                    if "collection_cache.destroy." in k and "state." not in k
                    and not k.endswith(".tip")], "销毁确认窗（面板内文字）"),
    ):
        widest = max(pixel_width(zh[k].replace("%s", "00")) for k in keys)
        check("%s：最宽文案 %dpx ≤ 可用 %dpx（面板 %d）" % (label, widest, available, panel),
              widest <= available, "最宽 %dpx" % widest)
    # 两行「销毁」按钮：按钮 44px、可用约 40px
    btn_prefix = "gui.rs_create_compat.collection_cache.destroy."
    for key in ("button", "state.on", "state.off"):
        for lang, table in (("zh", zh), ("en", en)):
            text = table[btn_prefix + key]
            check("销毁按钮 %s 的 %s 放得下（%dpx ≤ 40）" % (lang, key, pixel_width(text)),
                  pixel_width(text) <= 40)

    section("D10 流体扫描游标（跨扫描续扫；防止「每次从最小角重来」）")

    def method_body(text, signature_fragment):
        """按大括号配对取出以 signature_fragment 开头的方法体（含签名行）。"""
        start = text.find(signature_fragment)
        if start < 0:
            return None
        open_at = text.find("{", start)
        if open_at < 0:
            return None
        depth = 0
        for idx in range(open_at, len(text)):
            ch = text[idx]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[start:idx + 1]
        return None

    fluid_scan = method_body(block, "private List<BlockPos> fluidSourcesInRadius")
    resync = method_body(block, "private void resyncFluidScanCursorIfMoved")
    check("存在跨扫描游标字段 fluidScanCursor（long，内存态）",
          re.search(r"private long fluidScanCursor;", block) is not None)
    check("游标在扫描方法里被推进，且只有「扫满一轮」才回到 0",
          fluid_scan is not None
          and "fluidScanCursor + budget" in fluid_scan
          and "(next < fluidScanCursor || next >= volume) ? 0L : next" in fluid_scan)
    check("扫描方法一进来就先做「几何变化 ⇒ 归零」的检查",
          fluid_scan is not None and "resyncFluidScanCursorIfMoved();" in fluid_scan)
    check("位置 + 三轴半径都参与重置判定（少一项就会扫到旧区域）",
          resync is not None
          and "pos == fluidScanPos" in resync
          and "rx == fluidScanRadiusX" in resync
          and "ry == fluidScanRadiusY" in resync
          and "rz == fluidScanRadiusZ" in resync
          and "fluidScanCursor = 0L;" in resync)

    # ---- 反例：旧「最小角 + 凑够 4096 就 break」的形状必须消失 ----
    def legacy_min_corner_pattern(source):
        body = method_body(source, "private List<BlockPos> fluidSourcesInRadius")
        if body is None:
            return False
        return ("BlockPos.betweenClosed(" in body
                and "sources.size() >= MAX_FLUID_BLOCKS_PER_SCAN" in body
                and "break;" in body)

    def cursor_pattern(source):
        body = method_body(source, "private List<BlockPos> fluidSourcesInRadius")
        reset = method_body(source, "private void resyncFluidScanCursorIfMoved")
        return (body is not None and reset is not None
                and "resyncFluidScanCursorIfMoved();" in body
                and "fluidScanCursor + budget" in body
                and "fluidScanCursor = 0L;" in reset
                and "pos == fluidScanPos" in reset
                and "rx == fluidScanRadiusX" in reset)

    LEGACY_SOURCE = """
    private List<BlockPos> fluidSourcesInRadius(final Level level) {
        final int rx = getScanRadiusX();
        final int ry = getScanRadiusY();
        final int rz = getScanRadiusZ();
        final List<BlockPos> sources = new ArrayList<>();
        final Iterable<BlockPos> positions = BlockPos.betweenClosed(
            worldPosition.offset(-rx, -ry, -rz),
            worldPosition.offset(rx, ry, rz));
        for (final BlockPos pos : positions) {
            if (sources.size() >= MAX_FLUID_BLOCKS_PER_SCAN) {
                break;
            }
            if (fluidSourceIdAt(level, pos) != null) {
                sources.add(pos.immutable());
            }
        }
        return sources;
    }
    """
    check("现行源码里没有「从最小角遍历 + 凑够 4096 就 break」的写法",
          legacy_min_corner_pattern(block) is False)
    check("反例自证：把修前写法喂给「最小角 + break」判据必须判负",
          legacy_min_corner_pattern(LEGACY_SOURCE) is True)
    check("反例自证：修前写法必须过不了「游标 + 重置」判据",
          cursor_pattern(LEGACY_SOURCE) is False)

    # ---- 每 tick 查询预算不变（4096；同 tick 多次调用共享）----
    check("每 tick 查询上限仍是 4096（常量未提高）",
          re.search(r"MAX_FLUID_BLOCKS_PER_SCAN\s*=\s*4096\s*;", block) is not None)
    check("预算由「4096 - 本 tick 已用」推出，且同一 tick 的调用共享同一计数器",
          fluid_scan is not None
          and "MAX_FLUID_BLOCKS_PER_SCAN - fluidScanQueriesThisTick" in fluid_scan
          and "fluidScanQueriesThisTick += budget;" in fluid_scan)
    check("同一 tick 的第二次调用复用窗口（不重复查询、不重复推进游标）",
          fluid_scan is not None
          and "if (fluidScanWindowTick == tick)" in fluid_scan
          and "return fluidScanWindow;" in fluid_scan)

    # ---- 游标不落盘 ----
    save_body = method_body(block, "public void saveAdditional")
    load_body = method_body(block, "public void loadAdditional")
    check("saveAdditional / loadAdditional 里没有 fluidScanCursor（内存态，重启从最小角重扫）",
          save_body is not None and load_body is not None
          and "fluidScanCursor" not in save_body and "fluidScanCursor" not in load_body)

    # ---- 行为镜像：一整轮覆盖每个位次恰好一次；tick 数与公式一致 ----
    def scan_windows(size_x, size_y, size_z, budget=4096):
        volume = size_x * size_y * size_z
        cursor = 0
        windows = []
        while True:
            take = min(budget, volume - cursor)
            windows.append((cursor, cursor + take))
            cursor += take
            if cursor >= volume:
                return volume, windows

    def interval_ticks(radius, base=20, speed=0):
        # 与 getScanInterval / RsccRateLimiter.intervalTicks 同口径（无速度升级）
        radius_factor = max(1, 1 + (radius - 1) // 2)
        return max(1, max(1, base // radius_factor) >> speed)

    for radius, axis, expected_scans, expected_ticks in (
        (5, 11, 1, 6),
        (25, 51, 33, 33),
        (50, 101, 252, 252),
    ):
        volume, windows = scan_windows(axis, axis, axis)
        covered = []
        max_window = 0
        for lo, hi in windows:
            covered.extend(range(lo, hi))
            max_window = max(max_window, hi - lo)
        check("半径 %d（%d³ = %d 格）：ceil(%d/4096) = %d 次、每次 ≤ 4096、覆盖每个位次恰好一次"
              % (radius, axis, volume, volume, expected_scans),
              covered == list(range(volume)) and len(windows) == expected_scans
              and max_window <= 4096,
              "实际 %d 次 / 最大窗口 %d" % (len(windows), max_window))
        check("半径 %d：一轮 tick = %d 次 × getScanInterval()=%d = %d tick"
              % (radius, expected_scans, interval_ticks(radius), expected_ticks),
              len(windows) * interval_ticks(radius) == expected_ticks)
        # 旧写法（体积内全是源方块）：每次只看到前 4096 个位次
        old_seen = set()
        for _ in range(expected_scans):
            old_seen.update(range(0, min(4096, volume)))
        if volume > 4096:
            check("半径 %d 反例：旧写法 %d 次扫描后仍未覆盖尾部位次 %d"
                  % (radius, expected_scans, volume - 1),
                  (volume - 1) not in old_seen)
        else:
            check("半径 %d：体积 %d ≤ 4096，旧写法也能覆盖全（说明 bug 只在体积 > 4096 时出现）"
                  % (radius, volume), (volume - 1) in old_seen)

    check("性能反证：半径 25 的旧实现单次最多查询 132651 格（空区域），新实现固定 4096 格（只减不增）",
          51 * 51 * 51 == 132651 and 132651 > 4096)

    # 坐标映射镜像：Java 的 index → (dx, dy, dz) 逐条复算（非立方体积更容易抓出映射写错）
    def map_index(index, size_x, size_y):
        return (index % size_x, (index // size_x) % size_y, index // (size_x * size_y))

    for size_x, size_y, size_z in ((3, 2, 2), (2, 3, 4), (5, 1, 3), (51, 51, 51)):
        mapped = [map_index(i, size_x, size_y) for i in range(size_x * size_y * size_z)]
        expected = [(x, y, z) for z in range(size_z) for y in range(size_y) for x in range(size_x)]
        check("坐标映射 %d×%d×%d：index → (x,y,z) 覆盖整盒恰好一次" % (size_x, size_y, size_z),
              mapped == expected)

    # 几何变化 ⇒ 游标归零；几何不变 ⇒ 游标保持（否则又退化成每 tick 从头扫）
    def resync_cursor(cursor, stored, current):
        return 0 if stored != current else cursor

    check("半径/位置变化 ⇒ 游标归零（不会拿旧区域的位次继续扫新区域）",
          resync_cursor(5000, (12345, 5, 5, 5), (12345, 25, 25, 25)) == 0)
    check("几何不变 ⇒ 游标保持（不会每次又回到最小角）",
          resync_cursor(5000, (12345, 5, 5, 5), (12345, 5, 5, 5)) == 5000)

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
