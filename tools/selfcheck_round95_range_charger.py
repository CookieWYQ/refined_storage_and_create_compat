#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round95-D 自检：范围充电器「显示范围」修复 + 充电目标高亮（配置开关）。

用户实机反馈（1.2.0-beta.1）：
  「那个显示范围的不管是哪一个……充电器还是什么的它都没有正常的工作」
  「充电器再加一个额外功能……充电对象高亮：方块加渲染框、掉落物发光、玩家发光……
    可以在配置中开关；关闭的话简单提醒用户一句」

本轮修的两个落点（都能被本脚本判负）：
  A. 显示范围：旧实现 `showAABB(...).colored(...)` 既没有面贴图、也没有显式线宽
     （Outliner 默认 1/32 格 ≈ 半个像素），且 `removed()`（关界面）里**立刻** remove
     ⇒ 玩家点一下、关掉 GUI 去世界里看，什么也看不到。现在：棋盘面贴图 + 1/16 线宽 +
     显示窗口内每 tick 重喂 + 关界面后按剩余 TTL 继续画完再淡出。
  B. 充电目标高亮：服务端只把「本 tick 真充了谁」（accepted > 0）按 5 tick 节拍同步给
     同维度 64 格内玩家；Config.rangeChargerHighlightTargets=false 时**一个包都不发**；
     客户端给三类目标各画一个不同颜色的框（方块橙 / 掉落物青 / 玩家绿），掉落物与玩家另外发光。

第 102 轮按用户实机反馈重做（本脚本的 ③⑥ 判据同步翻新，旧判据写的是「唯一颜色 ≠ 范围橙」）：
  「显示那个正在给谁充电：掉落物需要高亮边框、玩家需要高亮玩家、方块就高亮方块」⇒ 三类目标
  现在都有框、三种颜色；并**核实出旧实现的发光是彻底无效的**：
  `Entity#setGlowingTag(boolean)` 在客户端是空操作（先写 hasGlowingTag，再用「客户端读共享位」
  的 isCurrentlyGlowing() 回写那个还没被写过的位），所以旧代码从未点亮过任何实体。现在改成直接
  写共享标志位 6（缓存的反射句柄，失败则退化为「只有框」），并锚住「不许再退回 setGlowingTag」。

检查分组
  ① 注册 / 双端安全（S2C 包走 ClientPayloadHooks，network 包无客户端类型）
  ② 配置开关（关闭不发包、开界面只提示一句、默认 true）
  ③ 客户端不写世界（发光走共享位 6、不退回客户端空操作，且只还原自己点亮的）
  ④ 范围框与充电扫描同直径口径（rangeX/Y/Z 是直径，half = range/2）
  ⑤ 显示范围修复（面贴图 / 线宽 / 每 tick 重喂 / 关界面后继续画）
  ⑥ 高亮节拍与低开销（5 tick、64 格、集合变化才重建、每 tick 只 chaseAABB、三色三类都有框）
  ⑦ 反例自证 ×7（去掉配置闸门 / 客户端写世界 / 直径口径写错 / 关界面立刻清 /
     删注册行 / 去掉线宽 / 配置默认值改成 false —— 同一个 audit() 必须各自判负）

用法：python tools/selfcheck_round95_range_charger.py
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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FILES = {
    "MAIN": os.path.join(SRC, "RS_Create_Compat.java"),
    "CONFIG": os.path.join(SRC, "Config.java"),
    "BE": os.path.join(SRC, "block", "entity", "RangeChargerBlockEntity.java"),
    "MENU": os.path.join(SRC, "menu", "RangeChargerMenu.java"),
    "SCREEN": os.path.join(SRC, "client", "screen", "RangeChargerScreen.java"),
    "SHOW_PACKET": os.path.join(SRC, "network", "ShowChargerRangePacket.java"),
    "HIGHLIGHT_PACKET": os.path.join(SRC, "network", "ChargerHighlightPacket.java"),
    "HOOKS": os.path.join(SRC, "network", "ClientPayloadHooks.java"),
    "SINK": os.path.join(SRC, "client", "ClientPayloadSink.java"),
    "HIGHLIGHT": os.path.join(SRC, "client", "ChargerTargetHighlight.java"),
    "ZH": os.path.join(RES, "zh_cn.json"),
    "EN": os.path.join(RES, "en_us.json"),
}

problems = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print("PASS %s" % name)
    else:
        problems.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as handle:
        return handle.read()


def code_only(text):
    """去掉注释再断言（注释里允许出现「旧实现」「不要 remove」这类对照说明）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def balanced(text, open_index, opening, closing):
    depth = 0
    for i in range(open_index, len(text)):
        ch = text[i]
        if ch == opening:
            depth += 1
        elif ch == closing:
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i], i
    raise ValueError("括号不配对")


def method_body(code, name):
    """取「名为 name 的方法声明」的方法体（code 需已去注释）；跳过同名调用点，找不到返回 None。"""
    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
        before = code[max(0, match.start() - 160):match.start()]
        # 形如 `private void foo(` / `protected int foo(` / `public static void foo(`；
        # 调用点前面没有「修饰符 + 返回类型」，用这条排除，否则会把调用处后面最近的花括号当成方法体。
        if not re.search(r"(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\].]+\s+$", before):
            continue
        try:
            _params, close_paren = balanced(code, match.end() - 1, "(", ")")
        except ValueError:
            return None
        brace = code.find("{", close_paren + 1)
        if brace < 0:
            return None
        try:
            body, _ = balanced(code, brace, "{", "}")
        except ValueError:
            return None
        return body
    return None


def load_sources():
    sources = {}
    for key, path in FILES.items():
        sources[key] = read(path) if os.path.isfile(path) else ""
    return sources


def lang_keys(text):
    try:
        return set(json.loads(text).keys())
    except Exception:
        return set()


# ==================================================================== 审计核心
def audit(raw_sources):
    """返回问题码集合；空集 = 通过。真实源码必须 0 问题，7 个反例必须各自命中对应码。"""
    result = set()
    s = {name: code_only(text) for name, text in raw_sources.items()}

    # ---------------- ① 注册 / 双端安全 ----------------
    main = s.get("MAIN", "")
    if "import cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket;" not in main:
        result.add("registration")
    if not re.search(
        r"registrar\.playToClient\(\s*ChargerHighlightPacket\.TYPE\s*,"
        r"\s*ChargerHighlightPacket\.STREAM_CODEC\s*,\s*ChargerHighlightPacket::handle", main):
        result.add("registration")

    packet = s.get("HIGHLIGHT_PACKET", "")
    if not packet:
        result.add("registration")
    else:
        if "net.minecraft.client" in packet or "Minecraft" in packet:
            result.add("dedicated-safe")
        if "ClientPayloadHooks.get().syncChargerHighlight(packet)" not in packet:
            result.add("dedicated-safe")
        if "CustomPacketPayload" not in packet or "STREAM_CODEC" not in packet:
            result.add("registration")
    if "default void syncChargerHighlight(final ChargerHighlightPacket packet)" not in s.get("HOOKS", ""):
        result.add("dedicated-safe")
    if "ChargerTargetHighlight.accept(packet)" not in s.get("SINK", ""):
        result.add("dedicated-safe")

    # ---------------- ② 配置开关 ----------------
    config = s.get("CONFIG", "")
    if 'define("rangeChargerHighlightTargets", true)' not in config:
        result.add("config-default")
    if "public static boolean rangeChargerHighlightTargets;" not in config:
        result.add("config-default")
    if "rangeChargerHighlightTargets = RANGE_CHARGER_HIGHLIGHT_TARGETS.get();" not in config:
        result.add("config-default")

    be = s.get("BE", "")
    sync = method_body(be, "syncHighlightTargets")
    if sync is None:
        result.add("config-gate")
    else:
        guard = sync.find("!Config.rangeChargerHighlightTargets")
        send_packet = sync.find("new cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket(")
        send_player = sync.find("PacketDistributor.sendToPlayer")
        if guard < 0 or send_packet < 0 or guard > send_packet:
            result.add("config-gate")
        elif "return;" not in sync[guard:send_packet]:
            result.add("config-gate")
        if send_player < 0:
            result.add("notify-radius")
    if be.count("new cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket(") != 1:
        result.add("config-gate")  # 只允许在高亮同步这一个方法里构造包

    # 开界面只提示一句（actionbar；不刷屏）
    menu = s.get("MENU", "")
    if "!cretae.cookiewyq.rs_create_compat.Config.rangeChargerHighlightTargets" not in menu:
        result.add("menu-notice")
    if "displayClientMessage(" not in menu or "highlight_disabled" not in menu:
        result.add("menu-notice")
    if menu.count("displayClientMessage(") != 1:
        result.add("menu-notice")

    # ---------------- ③ 客户端不写世界 ----------------
    highlight = s.get("HIGHLIGHT", "")
    for forbidden in ("addFreshEntity", "setPos(", "setDeltaMovement", "setBlock(",
                      "removeBlock(", "sendToServer", "PacketDistributor", "saveAdditional",
                      "setChanged(", "ServerPlayer", "ServerLevel"):
        if forbidden in highlight:
            result.add("client-world-write")
            break
    for needle in ("setClientGlow(entity, true)", "setClientGlow(entity, false)",
                   "setClientGlow(player, false)", "isCurrentlyGlowing()"):
        if needle not in highlight:
            result.add("glow-restore")
            break
    # 第 102 轮核实结论：`Entity#setGlowingTag(boolean)` 在**客户端是空操作**
    # （它先写 hasGlowingTag，再用「客户端读共享位」的 isCurrentlyGlowing() 回写那个还没被写过的位）
    # ⇒ 旧实现从未点亮过任何实体，用户「似乎没看到」是对的。现在改走共享标志位 6 的反射写入，
    # 这里反过来锚住「不许再退回那个空操作」。
    if "setGlowingTag(" in highlight:
        result.add("glow-restore")
    # 还原前必须先判断「还在不在目标集合里」（只还原离开集合的那些）
    if "itemIds.contains(id)" not in highlight or "playerIds.contains(id)" not in highlight:
        result.add("glow-restore")
    # 服务端不许把「高亮集合」写进存档。
    # 第 98 轮锚点收窄（旧契约被本轮契约合法推翻，不是放宽）：本轮**要求**把每台机器自己的
    # 开关 highlightEnabled 落盘（玩家配置），所以不能再按「saveAdditional 里出现 highlight
    # 字样就判负」。真正要禁止的是**每 tick 的高亮目标集合**（tickCharged* / lastHighlight /
    # highlightBlocks）——它们属于瞬态运行数据，落盘既无意义也会随存档膨胀。
    save = method_body(be, "saveAdditional")
    if save is not None and any(needle in save for needle in
                               ("tickCharged", "lastHighlight", "highlightBlocks")):
        result.add("client-world-write")

    # ---------------- ④ 直径口径（框 = 充电扫描） ----------------
    for needle in ("rangeX / 2", "rangeY / 2", "rangeZ / 2",
                   "worldPosition.getX() + halfX + 1", "worldPosition.getY() + halfY + 1",
                   "worldPosition.getZ() + halfZ + 1"):
        if needle not in be:
            result.add("diameter")
            break
    screen = s.get("SCREEN", "")
    for needle in ("packet.rangeX() / 2", "packet.rangeY() / 2", "packet.rangeZ() / 2",
                   "pos.getX() + halfX", "pos.getY() + halfY", "pos.getZ() + halfZ",
                   ".expandTowards(1, 1, 1)"):
        if needle not in screen:
            result.add("diameter")
            break

    # ---------------- ⑤ 显示范围修复 ----------------
    feed = method_body(screen, "feedRangeOutline")
    if feed is None:
        result.add("range-visible")
    else:
        if ".withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)" not in feed:
            result.add("range-visible")
        if ".lineWidth(RANGE_OUTLINE_LINE_WIDTH)" not in feed:
            result.add("range-visible")
        if "AllSpecialTextures" not in screen:
            result.add("range-visible")
    if "RANGE_OUTLINE_LINE_WIDTH = 1 / 16f" not in screen:
        result.add("range-visible")
    if "RANGE_OUTLINE_REFRESH_TTL = 2" not in screen or "RANGE_OUTLINE_TTL = 100" not in screen:
        result.add("range-visible")
    container_tick = method_body(screen, "containerTick")
    if container_tick is None or "updateRangeOutline();" not in container_tick:
        result.add("range-visible")
    update = method_body(screen, "updateRangeOutline")
    if update is None or "feedRangeOutline(RANGE_OUTLINE_REFRESH_TTL)" not in update:
        result.add("range-visible")
    # 关界面后不能立刻清：removed() 必须按剩余 TTL 重新武装；hideChargerRange() 只允许出现在
    # 「剩余时间已过」的 else 分支里 —— 出现在 else 之前就是退化成「关界面立刻清框」的旧 bug。
    removed = method_body(screen, "removed")
    if removed is None:
        result.add("keep-after-close")
    else:
        if "rangeOutlineUntil - gameTime()" not in removed:
            result.add("keep-after-close")
        if "if (remaining > 0) {" not in removed:
            result.add("keep-after-close")
        if "showAABB(RANGE_OUTLINE_SLOT, rangeBox, (int) remaining)" not in removed:
            result.add("keep-after-close")
        else_at = removed.find("} else {")
        hide_at = removed.find("hideChargerRange();")
        if hide_at < 0:
            result.add("keep-after-close")       # 到期分支应走统一移除路径（no RemovePath）
        elif else_at < 0 or hide_at < else_at:
            result.add("keep-after-close")       # 无条件清框 = 旧 bug

    # ---------------- ⑥ 高亮节拍 / 低开销 ----------------
    if "HIGHLIGHT_SYNC_INTERVAL_TICKS = 5" not in be:
        result.add("sync-interval")
    if "highlightSyncCooldown = HIGHLIGHT_SYNC_INTERVAL_TICKS;" not in be:
        result.add("sync-interval")
    if "HIGHLIGHT_NOTIFY_RADIUS = 64.0" not in be or "serverLevel.players()" not in be:
        result.add("notify-radius")
    # 只登记「真的充进去了」的目标
    if "tickChargedBlocks.add(pos);" not in be:
        result.add("tracking")
    if be.count("tickChargedItemIds.add(itemEntity.getId());") != 2:
        result.add("tracking")  # 有限范围 / 无限范围两条掉落物扫描都要登记
    # 玩家登记必须挂在「chargeItemStack 真的返回 true」的两个分支里（背包一圈 + 饰品槽一圈）：
    # 既不能无脑每 tick 登记，也不能只在一个包装方法里登记（那样会漏掉提前 return 的路径）。
    if be.count("tickChargedPlayers.add(player.getUUID());") != 2:
        result.add("tracking")
    _player_branches = be.split("if (chargeItemStack(stack)) {")
    if len(_player_branches) != 3:
        result.add("tracking")
    elif any("tickChargedPlayers.add" not in part[:240] for part in _player_branches[1:]):
        result.add("tracking")
    if "if (accepted > 0) {" not in be:
        result.add("tracking")

    # 客户端：集合变化才重建 + 每 tick 只 chaseAABB；三种目标三种颜色（第 102 轮重做）
    # 旧判据是「唯一颜色 ≠ 范围橙 FF9F1C」；本轮用户要求「三类目标各自一眼分得清」，
    # 因此契约变成「三色齐全、两两不同、方块就是橙色、三类都真的有框」——判据更强了，不是放宽。
    if "if (!blocksChanged && !itemsChanged && !playersChanged)" not in highlight:
        result.add("highlight")
    if "styledBlocks.add(outline.key())" not in highlight:
        result.add("highlight")
    colors = dict(re.findall(r"COLOR_(BLOCK|ITEM|PLAYER) = 0x([0-9A-Fa-f]{6})", highlight))
    if len(colors) != 3 or colors.get("BLOCK", "").upper() != "FF9F1C":
        result.add("highlight")
    elif len({value.upper() for value in colors.values()}) != 3:
        result.add("highlight")  # 三种目标必须用三种颜色（否则「分得清」就是假的）
    # 三类目标都要有轮廓：方块一路 + 实体（掉落物/玩家）一路，两处都必须走 chaseAABB。
    if highlight.count("chaseAABB(") < 2:
        result.add("highlight")
    if "TIMEOUT_TICKS = 20" not in highlight:
        result.add("highlight")
    highlight_flat = re.sub(r"\s+", " ", highlight)
    if "@EventBusSubscriber" not in highlight_flat or "value = Dist.CLIENT" not in highlight_flat:
        result.add("highlight")  # 客户端专属类：游戏总线 tick + 仅 CLIENT 加载

    # 语言键（Lead 落）：关闭提示 + 修好的显示范围 tooltip
    zh = lang_keys(raw_sources.get("ZH", ""))
    en = lang_keys(raw_sources.get("EN", ""))
    for key in ("gui.rs_create_compat.range_charger.highlight_disabled",
                "gui.rs_create_compat.range_charger.show_range.tip"):
        if key not in zh or key not in en:
            result.add("lang")
            break

    return result


# ==================================================================== 主流程
missing = [key for key, path in FILES.items() if not os.path.isfile(path)]
check("① 参与自检的源码 / 语言文件都存在", not missing, ", ".join(missing))

sources = load_sources()
real = audit(sources)
check("① S2C 包 ChargerHighlightPacket 已在 RS_Create_Compat 注册（playToClient + TYPE/STREAM_CODEC/handle）",
      "registration" not in real)
check("① 双端安全：packet 类零客户端类型、处理体走 ClientPayloadHooks、Sink 已实现",
      "dedicated-safe" not in real)
check("② Config.rangeChargerHighlightTargets 默认 true、静态字段与 onLoad 同步齐全",
      "config-default" not in real)
check("② 配置关闭时 syncHighlightTargets 先 return：一个包都不发（且包只在这一处构造）",
      "config-gate" not in real)
check("② 关闭配置时开界面只提示一句（actionbar，displayClientMessage 全文仅 1 处）",
      "menu-notice" not in real)
check("③ 客户端不写世界 / 不写存档（发光走共享标志位 6、不退回客户端空操作的 setGlowingTag，且只还原自己点亮的实体）",
      "client-world-write" not in real and "glow-restore" not in real)
check("④ 范围框与充电扫描同直径口径（half = range / 2；max = pos + half + 1）",
      "diameter" not in real)
check("⑤ 显示范围：棋盘面贴图 + 1/16 线宽 + 每 tick 重喂",
      "range-visible" not in real)
check("⑤ 关界面后不再立刻清框：按剩余 TTL 重新武装、继续画完再淡出",
      "keep-after-close" not in real)
check("⑥ 高亮节拍 5 tick、接收半径 64 格、只登记 accepted > 0 的真充电目标",
      "sync-interval" not in real and "notify-radius" not in real and "tracking" not in real)
check("⑥ 客户端集合变化才重建、每 tick 只 chaseAABB、三类目标三种颜色（方块橙/掉落物青/玩家绿）且三类都有框、20 tick 超时",
      "highlight" not in real)
check("⑦ 语言键：关闭提示 + 更新后的 show_range.tip（中英成对，Lead 落）",
      "lang" not in real)

# 打印关键解析值，避免「写死数字假装解析」
be_code = code_only(sources["BE"])
screen_code = code_only(sources["SCREEN"])
interval = re.search(r"HIGHLIGHT_SYNC_INTERVAL_TICKS = (\d+)", be_code)
radius = re.search(r"HIGHLIGHT_NOTIFY_RADIUS = ([\d.]+)", be_code)
ttl = re.search(r"RANGE_OUTLINE_TTL = (\d+)", screen_code)
color = dict(re.findall(r"COLOR_(BLOCK|ITEM|PLAYER) = 0x([0-9A-Fa-f]{6})",
                        code_only(sources["HIGHLIGHT"])))
print("[info] 高亮同步节拍 = %s tick；接收半径 = %s 格；三色（方块/掉落物/玩家）= %s/%s/%s；"
      "范围框 TTL = %s tick"
      % (interval.group(1) if interval else "?", radius.group(1) if radius else "?",
         color.get("BLOCK", "?"), color.get("ITEM", "?"), color.get("PLAYER", "?"),
         ttl.group(1) if ttl else "?"))

# ==================================================================== ⑦ 反例自证
def mutate(raw_sources, key, old, new, count=1):
    copy = dict(raw_sources)
    text = copy[key]
    if old not in text:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (key, old))
    copy[key] = text.replace(old, new, count)
    return copy


# 反例 1：去掉配置闸门（关闭配置也发包）
case = mutate(sources, "BE", "if (!Config.rangeChargerHighlightTargets) {",
              "if (false) {", 1)
check("⑦a 反例（去掉 rangeChargerHighlightTargets 闸门 ⇒ 关闭时仍发包）判负",
      "config-gate" in audit(case))

# 反例 2：客户端写世界（把高亮实现改成往世界里加实体）
case = dict(sources)
case["HIGHLIGHT"] = case["HIGHLIGHT"].replace(
    "private ChargerTargetHighlight() {",
    "private ChargerTargetHighlight() { /* */ }\n"
    "    private static void badWrite(net.minecraft.client.multiplayer.ClientLevel level) {\n"
    "        level.addFreshEntity(null);\n    }\n"
    "    private ChargerTargetHighlight(int unused) {", 1)
check("⑦b 反例（客户端往世界写实体）判负", "client-world-write" in audit(case))
print("      反例：客户端实现里出现 addFreshEntity → 判负（高亮绝不允许写世界）")

# 反例 3：直径口径写错（框用整段 range 当半宽 ⇒ 画成 2 倍）
case = mutate(sources, "SCREEN", "final int halfX = packet.rangeX() / 2;",
              "final int halfX = packet.rangeX();", 1)
check("⑦c 反例（范围框把直径当半宽 ⇒ 与扫描不同口径）判负", "diameter" in audit(case))

# 反例 4：关界面立刻清框（回到旧实现：removed() 里无条件 hide、不再按剩余 TTL 重新武装）
case = mutate(sources, "SCREEN", "if (rangeSnapshot != null && rangeBox != null) {",
              "hideChargerRange();\n        if (false) {", 1)
check("⑦d 反例（removed() 回到「关界面立刻移除」）判负", "keep-after-close" in audit(case))
print("      反例：关界面直接清框（旧 bug）→ 判负")

# 反例 5：删掉注册行（Lead 漏落注册）
case = mutate(sources, "MAIN",
              "        registrar.playToClient(\n            ChargerHighlightPacket.TYPE,",
              "        registrar.playToServer(\n            ChargerHighlightPacket.TYPE,", 1)
check("⑦e 反例（注册成 playToServer / 客户端收不到）判负", "registration" in audit(case))

# 反例 6：去掉线宽（回到 1/32 极细线）
case = dict(sources)
case["SCREEN"] = case["SCREEN"].replace(".lineWidth(RANGE_OUTLINE_LINE_WIDTH)", "", 1)
check("⑦f 反例（范围框没有显式 1/16 线宽）判负", "range-visible" in audit(case))

# 反例 7：配置默认值改成 false（用户没得选 / 行为倒退）
case = mutate(sources, "CONFIG", 'define("rangeChargerHighlightTargets", true)',
              'define("rangeChargerHighlightTargets", false)', 1)
check("⑦g 反例（rangeChargerHighlightTargets 默认 false）判负", "config-default" in audit(case))

print("=" * 78)
if problems:
    print("[result] 自检失败：%d 个问题" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 7 个反例自证）；范围充电器「显示范围」可见性 + "
      "关界面后继续淡出 + 充电目标高亮（配置开关 / 只发附近 / 客户端不写世界 / 同直径口径）锚定完成"
      % checks)
sys.exit(0)
