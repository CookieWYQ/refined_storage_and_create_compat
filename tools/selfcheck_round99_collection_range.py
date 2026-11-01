#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 99 轮自检：归流缓存仓（基础 + 高级）「查看吸取范围」的服务端权威链路。

用户原话（针对 1.2.0-beta.6）：
  「还有就是你这个归流缓存器也像这一个范围充电器一样可以显示它这个吸取的范围包括基础和高级的」

本轮口径（写死在本脚本里，改一处就必须同步改这里）：
  · **基础版** = 三轴半径框（唯一会画半径口径的机器）：几何 = 方块实体真正在扫的那一个 AABB；
  · **高级版 区域生效** = 选定区域（去重后的若干 AABB，区域**取代**半径，绝不叠加）；
  · **高级版 区域为空 / 维度不符** = **不画框** + 行动栏提示「区域模式已开启但没有可用区域」；
  · **高级版 未装区域选定器** = **不画框** + 行动栏提示「未装区域选定器」。
    （事实依据：`AdvancedCollectionCacheBlockEntity#areaAbsorptionBlocked()` 是
      `selectionForTick() == null`，没有选定器时**也**为真 ⇒ 高级版没有「按半径吸」这条路径，
      画半径框就是谎报。Lead 已裁决按此交付。）

本脚本钉住（任一 FAIL ⇒ 退出码 1）：
  ① 链路齐全：S2C 包（containerId/mode/boxes，handle 只走 ClientPayloadHooks）+ Hooks 默认槽
     + Sink 实现（只路由给 containerId 对得上的 CollectionCacheScreen）+ 主类 playToClient 注册行；
  ② C2S 走原版菜单按钮通道：`CollectionCacheMenu.BUTTON_SHOW_RANGE` + `clickMenuButton` 里
     `stillValid(player)` 校验后再让方块实体组快照；
  ③ 两台界面都有按钮与点击路径（基础版自带一颗；高级版用它自己的「范围」按钮）；
  ④ 几何口径唯一：`collectionRangeBox()` 是整个方块实体里**唯一**写 `inflate(scanX, scanY, scanZ)`
     的地方，实体查询 / 经验球查询 / 快照三处共用；基础版 mode 恒为 MODE_RADIUS；
  ⑤ 高级版源码**永不出现 MODE_RADIUS**（四态里只有 AREAS 会给区域）；
  ⑥ 客户端只画：界面源码里没有任何「读方块实体 / 读半径 / 自己 inflate / 读选定器物品」的写法，
     且「不画」的两种 mode 在**任何 showForView 调用之前**就 return（= 四态里只有 AREAS/RADIUS 画框）；
  ⑦ 5 秒自动消失（TTL = AreaOutline.VIEW_TTL = 100 tick）+ 每 tick 重喂 + 再点一次立即移除
     + 关界面按剩余 TTL 重新武装；
  ⑧ 「不可用」提示只在点按钮那一刻发一次（调用点恰好 1 处，且不在 tick / render 路径里）。

**反例自证**（脚本末尾 7 组，全部必须在被变异的源码上判负）：
  ① 客户端自己算半径 / ② 高级版把区域与半径叠加 / ③ 高级版在 NO_AREA/NO_SELECTOR 也画半径框 /
  ④ 只做基础版 / ⑤ 删掉按钮的点击路径 / ⑥ removed() 退回「关界面立刻删框」/ ⑦ 不可用提示删掉。

用法：python tools/selfcheck_round99_collection_range.py    （退出码 0 = 全部 PASS）
"""

from __future__ import annotations

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
NEWLINE = chr(10)

PACKET = os.path.join(JAVA, "network", "ShowCollectionRangePacket.java")
HOOKS = os.path.join(JAVA, "network", "ClientPayloadHooks.java")
SINK = os.path.join(JAVA, "client", "ClientPayloadSink.java")
BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
MENU = os.path.join(JAVA, "menu", "CollectionCacheMenu.java")
BASE_SCREEN = os.path.join(JAVA, "client", "screen", "CollectionCacheScreen.java")
ADV_SCREEN = os.path.join(JAVA, "client", "screen", "AdvancedCollectionCacheScreen.java")
MAIN = os.path.join(JAVA, "RS_Create_Compat.java")
OUTLINE = os.path.join(JAVA, "client", "AreaOutline.java")
CLIENT_INIT = os.path.join(JAVA, "client", "ClientInit.java")

PROBLEMS = []
CHECKS = [0]


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释（这些文件里没有会把 '//' 放进字符串的写法）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.split(NEWLINE):
        cut = line.find("//")
        out.append(line if cut < 0 else line[:cut])
    return NEWLINE.join(out)


def method_body(text, sig_pattern):
    """按签名正则找到方法，返回其 {...} 之间的正文（大括号计数）；找不到返回 None。"""
    match = re.search(sig_pattern, text)
    if not match:
        return None
    start = text.find("{", match.end())
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return None


def check(desc, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS  %02d  %s" % (CHECKS[0], desc))
    else:
        PROBLEMS.append("%s%s" % (desc, ("  <- " + detail) if detail else ""))
        print("FAIL  %02d  %s%s" % (CHECKS[0], desc, ("  <- " + detail) if detail else ""))
    return ok


RAW = {}
for name, path in (
    ("packet", PACKET), ("hooks", HOOKS), ("sink", SINK), ("be", BE), ("adv_be", ADV_BE),
    ("menu", MENU), ("base", BASE_SCREEN), ("adv", ADV_SCREEN), ("main", MAIN),
    ("outline", OUTLINE), ("client_init", CLIENT_INIT),
):
    RAW[name] = read(path) if os.path.isfile(path) else ""
SRC = {name: strip_comments(text) for name, text in RAW.items()}

# ====================================================================== ① 链路齐全
print("=" * 78)
print("[1] S2C 权威快照链路（包 / Hooks / Sink / 注册行）")
print("=" * 78)

check("①1 新包存在：ShowCollectionRangePacket(containerId, mode, List<Area> boxes)",
      "public record ShowCollectionRangePacket(int containerId, int mode, List<Area> boxes)"
      in SRC["packet"])

check("①2 四种口径常量齐全（0 半径 / 1 区域 / 2 区域不可用 / 3 未装选定器）",
      all(needle in SRC["packet"] for needle in (
          "MODE_RADIUS = 0", "MODE_AREAS = 1", "MODE_NO_AREA = 2", "MODE_NO_SELECTOR = 3")))

check("①3 未知 mode 按「不画」处理（绝不猜成半径），且条目数在构造器夹到 ABSOLUTE_MAX_AREAS",
      "mode > MODE_MAX" in SRC["packet"] and "MODE_NO_AREA;" in SRC["packet"]
      and "ABSOLUTE_MAX_AREAS" in SRC["packet"])

PACKET_HANDLE = method_body(SRC["packet"], r"public static void handle\s*\(")
check("①4 包处理体只经 ClientPayloadHooks（不在 network 包碰任何客户端类型）",
      PACKET_HANDLE is not None
      and "ClientPayloadHooks.get().showCollectionRange(packet)" in PACKET_HANDLE
      and "net.minecraft.client" not in SRC["packet"]
      and "Minecraft" not in SRC["packet"])

check("①5 ClientPayloadHooks 有 showCollectionRange 默认空实现",
      re.search(r"default void showCollectionRange\s*\(\s*final ShowCollectionRangePacket packet\s*\)",
                SRC["hooks"]) is not None)

SINK_BODY = method_body(SRC["sink"], r"public void showCollectionRange\s*\(")
check("①6 Sink 实现只路由给「正开着且 containerId 对得上」的归流缓存仓界面",
      SINK_BODY is not None
      and "CollectionCacheScreen" in SINK_BODY
      and "containerId" in SINK_BODY
      and "showCollectionRange(packet)" in SINK_BODY)

check("①7 Sink 不读方块实体 / 不算几何（客户端只画，铁律）",
      SINK_BODY is not None
      and not re.search(r"getBlockEntity|inflate\s*\(|getScanRadius|getCollectRadius", SINK_BODY))

REG = method_body(SRC["main"], r"private static void registerPayloads\s*\(")
check("①8 主类 registerPayloads 已 playToClient 注册本包（TYPE + STREAM_CODEC + ::handle）",
      REG is not None
      and "ShowCollectionRangePacket.TYPE" in REG
      and "ShowCollectionRangePacket.STREAM_CODEC" in REG
      and "ShowCollectionRangePacket::handle" in REG)

# ====================================================================== ② C2S 通道
print("=" * 78)
print("[2] C2S 请求通道（原版菜单按钮 + 服务端校验）")
print("=" * 78)

check("②1 菜单暴露 BUTTON_SHOW_RANGE（且与红石模式 900 不撞号）",
      re.search(r"public static final int BUTTON_SHOW_RANGE = (\d+);", SRC["menu"]) is not None
      and "BUTTON_SHOW_RANGE = 900" not in SRC["menu"])

CLICK = method_body(SRC["menu"], r"public boolean clickMenuButton\s*\(")
check("②2 clickMenuButton：先 stillValid(player) 再让方块实体组快照（服务端权威）",
      CLICK is not None
      and "BUTTON_SHOW_RANGE" in CLICK
      and "stillValid(player)" in CLICK
      and "sendCollectionRangeOutline(serverPlayer, containerId)" in CLICK)

check("②3 快照入口是方块实体方法（菜单自己不拼几何）",
      "public void sendCollectionRangeOutline(final ServerPlayer player, final int containerId)"
      in SRC["be"])

# ====================================================================== ③ 两台界面
print("=" * 78)
print("[3] 两台界面都有「查看范围」按钮与点击路径")
print("=" * 78)


def base_button_problems(base_src):
    problems = []
    init_body = method_body(base_src, r"protected void init\s*\(\s*\)")
    if init_body is None or 'LANG + "show_range"' not in init_body:
        problems.append("基础版 init() 没有创建「查看范围」按钮（LANG + \"show_range\"）")
    elif "toggleRange()" not in init_body:
        problems.append("基础版按钮没有接到 toggleRange()")
    toggle = method_body(base_src, r"protected void toggleRange\s*\(\s*\)")
    if toggle is None:
        problems.append("没有 toggleRange()")
        return problems
    if "handleInventoryButtonClick(" not in toggle or "BUTTON_SHOW_RANGE" not in toggle:
        problems.append("toggleRange() 没有走原版菜单按钮通道（BUTTON_SHOW_RANGE）")
    if "isRangeVisible()" not in toggle or "hideRange()" not in toggle:
        problems.append("toggleRange() 没有「显示中 → 立即移除」分支")
    return problems


def adv_button_problems(adv_src, base_src):
    problems = []
    # 第 125 轮 §5：两颗「单显 / 全显」按钮并入侧栏 ⇒ 高级版不再有 rangeTriggerButton / range_button。
    # 但 usesOwnRangeButton() **必须仍覆写成 true**：基类 init 里是 `if (!usesOwnRangeButton())` 才造按钮，
    # 不覆写就等于放基类在 (8,219) 再造一颗「查看范围」、压住区域选定器槽 —— 契约 §5 的
    # 「GUI 上不再需要任何范围显示按钮」会被破坏。所以这一条是「删了两颗、但不许把基类那颗放回来」。
    own = method_body(adv_src, r"protected boolean usesOwnRangeButton\s*\(\s*\)")
    if own is None:
        problems.append("高级版没覆写 usesOwnRangeButton()：基类会在 (8,219) 再造一颗「查看范围」")
    elif "return true;" not in own:
        problems.append("高级版 usesOwnRangeButton() 不是 true（基类会再造一颗挤掉「区域」按钮）")
    if method_body(adv_src, r"protected Button rangeTriggerButton\s*\(\s*\)") is not None:
        problems.append("rangeTriggerButton() 应随两颗按钮一起删除（第 125 轮 §5）")
    init_body = method_body(adv_src, r"protected void init\s*\(\s*\)")
    if init_body is not None and 'LANG + "range_button"' in init_body:
        problems.append("高级版 init() 又建了「范围 / 全显」按钮（契约 §5 已删除）")
    # 两台界面都必须把「点一次画 / 再点取消」共用同一份实现，不许各写一套
    if "toggleRange" not in strip_comments(base_src):
        problems.append("基类根本没有 toggleRange()，两台界面无法共用")
    return problems


base_problems = base_button_problems(SRC["base"])
adv_problems = adv_button_problems(SRC["adv"], SRC["base"])
check("③1 基础版：按钮 + toggleRange + 菜单按钮通道 + 再点立即移除",
      not base_problems, "; ".join(base_problems))
check("③2 高级版：两颗「单显 / 全显」按钮已删除、不再覆写 rangeTriggerButton（第 125 轮 §5；"
      "usesOwnRangeButton 仍须 true，防止基类补回一颗）",
      not adv_problems, "; ".join(adv_problems))

check("③3 两台界面都已注册（ClientInit 的屏幕工厂）",
      "CollectionCacheScreen::new" in SRC["client_init"]
      and "AdvancedCollectionCacheScreen::new" in SRC["client_init"])

# ====================================================================== ④ 基础 = 半径
print("=" * 78)
print("[4] 基础版 = 三轴半径框，且几何口径全工程唯一")
print("=" * 78)


def geometry_single_source_problems(be_src):
    """半径几何必须只在 collectionRangeBox() 里 inflate 一次；扫描与快照都调它。"""
    problems = []
    box_body = method_body(be_src, r"protected AABB collectionRangeBox\s*\(\s*\)")
    if box_body is None:
        problems.append("没有 collectionRangeBox()")
    elif "new AABB(worldPosition)" not in box_body \
            or "inflate(getScanRadiusX(), getScanRadiusY(), getScanRadiusZ())" not in box_body:
        problems.append("collectionRangeBox() 不是「new AABB(worldPosition).inflate(三轴扫描半径)」")
    # 整个方块实体里 inflate(...) 只允许出现一次（就是上面那处）
    inflate_pattern = re.compile(r"\.inflate\(getScanRadiusX\(\), getScanRadiusY\(\), getScanRadiusZ\(\)\)")
    hits = len(inflate_pattern.findall(be_src))
    if hits != 1:
        problems.append("扫半径 inflate 出现 %d 次（必须恰好 1 次，否则「框画这里、吸那里」）" % hits)
    for name, sig in (("itemEntitiesInRadius", r"private List<ItemEntity> itemEntitiesInRadius\s*\("),
                      ("experienceOrbsInRadius", r"private List<[^;{]*> experienceOrbsInRadius\s*\(")):
        body = method_body(be_src, sig)
        if body is None:
            problems.append("找不到 %s()" % name)
        elif "collectionRangeBox()" not in body:
            problems.append("%s() 没有走 collectionRangeBox()（另算了一套几何）" % name)
    send = method_body(be_src, r"public void sendCollectionRangeOutline\s*\(")
    if send is None:
        problems.append("没有 sendCollectionRangeOutline()")
    elif "areaOfBox(collectionRangeBox())" not in send:
        problems.append("快照不是由 collectionRangeBox() 换算（会与扫描口径漂移）")
    return problems


geo_problems = geometry_single_source_problems(SRC["be"])
check("④1 半径几何唯一来源：collectionRangeBox()（扫描 / 经验球 / 快照三处共用，inflate 恰好 1 次）",
      not geo_problems, "; ".join(geo_problems))

BASE_MODE = method_body(SRC["be"], r"protected int collectionRangeMode\s*\(\s*\)")
check("④2 基础版口径恒为 MODE_RADIUS（基础版只会画半径框）",
      BASE_MODE is not None
      and "return ShowCollectionRangePacket.MODE_RADIUS;" in BASE_MODE
      and "MODE_AREAS" not in BASE_MODE)

AREA_OF_BOX = method_body(SRC["be"], r"private static Area areaOfBox\s*\(")
check("④3 AABB → 方块区域：floor(min) / ceil(max)-1，且坐标夹到世界边界（创造范围升级不会 int 回绕）",
      AREA_OF_BOX is not None
      and "Math.floor(box.minX)" in AREA_OF_BOX
      and "Math.ceil(box.maxX) - 1.0D" in AREA_OF_BOX
      and "clampWorldCoord(" in AREA_OF_BOX
      and "Area.WORLD_LIMIT" in SRC["be"])

# ====================================================================== ⑤ 高级 = 区域优先
print("=" * 78)
print("[5] 高级版 = 区域优先（取代半径，绝不叠加；四态里只有 AREAS 给区域）")
print("=" * 78)


def adv_mode_problems(adv_be_src):
    problems = []
    mode = method_body(adv_be_src, r"protected int collectionRangeMode\s*\(\s*\)")
    if mode is None:
        problems.append("高级版没有覆写 collectionRangeMode()（会继承半径口径 = 谎报）")
        return problems
    if "MODE_RADIUS" in mode:
        problems.append("高级版 collectionRangeMode() 里出现 MODE_RADIUS（区域模式与半径叠加）")
    for needle in ("MODE_AREAS", "MODE_NO_AREA", "MODE_NO_SELECTOR"):
        if needle not in mode:
            problems.append("高级版 collectionRangeMode() 没有覆盖 %s" % needle)
    areas = method_body(adv_be_src, r"protected List<Area> collectionRangeAreas\s*\(\s*\)")
    if areas is None:
        problems.append("高级版没有 collectionRangeAreas()")
    elif "distinctAreas()" not in areas:
        problems.append("高级版没有用 distinctAreas() 去重（同一块地会叠画两份）")
    if re.search(r"inflate\(getScanRadius", adv_be_src):
        problems.append("高级版方块实体里出现半径 inflate（区域取代半径，不许再算半径框）")
    return problems


adv_mode_problems_list = adv_mode_problems(SRC["adv_be"])
check("⑤1 高级版口径：AREAS / NO_AREA / NO_SELECTOR，源码永不出现 MODE_RADIUS",
      not adv_mode_problems_list, "; ".join(adv_mode_problems_list))

ABSORB_BLOCKED = method_body(SRC["adv_be"], r"protected boolean areaAbsorptionBlocked\s*\(\s*\)")
check("⑤2 高级版「没装选定器也不吸」这一事实仍成立（areaAbsorptionBlocked 返回 selectionForTick()==null）",
      ABSORB_BLOCKED is not None and "return selectionForTick() == null;" in ABSORB_BLOCKED)

# ====================================================================== ⑥ 客户端只画
print("=" * 78)
print("[6] 客户端只画（不读方块实体 / 不读半径 / 不自己算几何；不可用状态先 return）")
print("=" * 78)

FORBIDDEN_CLIENT = ("inflate(", "getScanRadius", "getCollectRadius", "getBlockEntity",
                    "AreaSelection.get(", "getAreaSelectorStack")


def client_geometry_problems(screen_src):
    """showCollectionRange 里只允许「Area → AABB」，任何自算几何 / 读 BE / 读半径都判负。"""
    problems = []
    body = method_body(screen_src, r"public void showCollectionRange\s*\(")
    if body is None:
        problems.append("界面没有 showCollectionRange()（S2C 快照无处落地）")
        return problems
    for needle in FORBIDDEN_CLIENT:
        if needle in body:
            problems.append("showCollectionRange() 里出现 %r（客户端不许自己算几何 / 读权威数据）" % needle)
    if "AreaOutline.showForView" not in body:
        problems.append("showCollectionRange() 没有把区域喂给统一 Outliner 出口（AreaOutline.showForView）")
    return problems


client_problems = client_geometry_problems(SRC["base"])
check("⑥1 客户端只做 Area → AABB：不 inflate / 不读半径 / 不读方块实体 / 不读选定器物品",
      not client_problems, "; ".join(client_problems))


def show_areas_problems(screen_src):
    """第 106 轮新增：showAreas() 是「单显 / 全显」的喂框实现，同样只允许 Area → AABB。

    它与 showCollectionRange() 共用同一套「客户端不自算几何」纪律；若不单独钉住，
    将来有人在这个方法里加一句 inflate(半径) 就绕过了 ⑥1（判据只覆盖 S2C 那条路径）。
    """
    problems = []
    body = method_body(screen_src, r"protected final void showAreas\s*\(")
    if body is None:
        problems.append("基类没有 showAreas（高级版单显 / 全显无统一喂框出口）")
        return problems
    for needle in FORBIDDEN_CLIENT:
        if needle in body:
            problems.append("showAreas() 里出现 %r（客户端不许自己算几何 / 读权威数据）" % needle)
    if "AreaOutline.showForView" not in body:
        problems.append("showAreas() 没有把区域喂给统一 Outliner 出口")
    return problems


check("⑥1b showAreas()（单显 / 全显的喂框实现）同样只做 Area → AABB",
      not show_areas_problems(SRC["base"]), "; ".join(show_areas_problems(SRC["base"])))


def draw_order_problems(screen_src):
    """四态里只有 AREAS / RADIUS 画框：NO_AREA / NO_SELECTOR 必须**在绘制之前** return。"""
    problems = []
    body = method_body(screen_src, r"public void showCollectionRange\s*\(")
    if body is None:
        return ["没有 showCollectionRange()"]
    no_area_at = body.find("MODE_NO_AREA")
    no_selector_at = body.find("MODE_NO_SELECTOR")
    draw_at = body.find("AreaOutline.showForView")
    if draw_at < 0:
        return ["没有绘制调用"]
    if no_area_at < 0 or no_selector_at < 0:
        problems.append("没有分别处理 MODE_NO_AREA / MODE_NO_SELECTOR（不画框的两种状态）")
    elif no_area_at > draw_at or no_selector_at > draw_at:
        problems.append("「不画框」的判断排在绘制之后 ⇒ 不可用状态也会画出框（谎报）")
    return problems


draw_problems = draw_order_problems(SRC["base"])
check("⑥2 四态里只有 AREAS / RADIUS 画框：NO_AREA / NO_SELECTOR 在绘制之前就 return",
      not draw_problems, "; ".join(draw_problems))

check("⑥3 高级版界面已无「客户端自己读选定器物品画框」的旧实现",
      "AreaSelection.get(" not in SRC["adv"]
      and "AreaOutline.showForView" not in SRC["adv"]
      and 'LANG + "range_empty"' not in SRC["adv"])

# ====================================================================== ⑦ 生命周期
print("=" * 78)
print("[7] 5 秒自动消失 + 每 tick 重喂 + 再点立即移除 + 关界面按剩余 TTL 重新武装")
print("=" * 78)

# 第 99 轮：本界面沿用原版 isPauseScreen()==true ⇒ 单机开界面时 gameTime 不前进，
# 因此「5 秒」必须用**墙钟毫秒**（RANGE_SHOW_TTL_MS = VIEW_TTL × 50ms），不能用 tick 计时。
_update_body = method_body(SRC["base"], r"private void updateRangeOutline\s*\(\s*\)")
check("⑦1 TTL = AreaOutline.VIEW_TTL × 50ms = 5000ms（与范围充电器同一个可见时长；墙钟口径）",
      "int VIEW_TTL = 100;" in SRC["outline"]
      and "RANGE_SHOW_TTL_MS = AreaOutline.VIEW_TTL * 50L" in SRC["base"]
      and _update_body is not None and "nowMs()" in _update_body
      and "getGameTime()" not in _update_body)

UPDATE = method_body(SRC["base"], r"private void updateRangeOutline\s*\(\s*\)")
check("⑦2 显示窗口内每 tick 重喂、到期停止重喂（交给 Outliner 自己淡出）",
      UPDATE is not None
      and "RANGE_REFRESH_TTL" in UPDATE
      and "RANGE_SHOW_TTL" in UPDATE
      and "shownAreas.clear()" in UPDATE)

check("⑦3 每 tick 驱动已挂上（containerTick 调 updateRangeOutline，与充电器同一套）",
      (lambda body: body is not None and "updateRangeOutline();" in body)(
          method_body(SRC["base"], r"protected void containerTick\s*\(\s*\)")))


def removed_problems(screen_src):
    """关界面：必须按剩余 TTL 重新武装，不许无条件删框、也不许干脆不管。"""
    problems = []
    body = method_body(screen_src, r"public void removed\s*\(\s*\)")
    if body is None:
        return ["没有 removed()（关界面后框的行为没有定义）"]
    if "remaining" not in body:
        problems.append("removed() 没有算「剩余 TTL」")
    if not re.search(r"showForView\([^;]*remaining", body):
        problems.append("removed() 没有按剩余 TTL 重新 showForView（一关界面框就没了）")
    if "shownAreas" not in body:
        problems.append("removed() 没有用 shownAreas 重新武装")
    first_hide = body.find("hideRange(")
    if first_hide >= 0 and "else" not in body[:first_hide]:
        problems.append("removed() 在 remaining 判断之前就无条件 hideRange()")
    return problems


removed_issues = removed_problems(SRC["base"])
check("⑦4 关界面后继续画完再淡出（按剩余 TTL 重新武装，不无条件删框）",
      not removed_issues, "; ".join(removed_issues))

# ====================================================================== ⑧ 提示只发一次
print("=" * 78)
print("[8] 不可用提示：只在点按钮那一刻发一次（不 tick 刷屏）")
print("=" * 78)


def hint_problems(screen_src):
    problems = []
    calls = screen_src.count("showRangeUnavailableHint(")
    # 1 处定义 + 1 处调用 = 2
    if calls != 2:
        problems.append("showRangeUnavailableHint 出现 %d 次（应为定义 1 + 调用 1）" % calls)
    show = method_body(screen_src, r"public void showCollectionRange\s*\(")
    if show is None or "showRangeUnavailableHint(" not in show:
        problems.append("不可用提示不是在 S2C 到达（点按钮那一刻）触发的")
    for name, sig in (("containerTick", r"protected void containerTick\s*\(\s*\)"),
                      ("render", r"public void render\s*\(\s*GuiGraphics"),
                      ("updateRangeOutline", r"private void updateRangeOutline\s*\(\s*\)")):
        body = method_body(screen_src, sig)
        if body is not None and "displayClientMessage" in body:
            problems.append("%s 里也在发消息（会 tick 刷屏）" % name)
    return problems


hint_issues = hint_problems(SRC["base"])
check("⑧ 不可用提示恰好 1 个调用点、在 S2C 到达时发一次，且不在 tick / render 路径里",
      not hint_issues, "; ".join(hint_issues))

# ====================================================================== 反例自证
print("=" * 78)
print("[9] 反例自证（把实现改回错误写法，上面的判据必须判负）")
print("=" * 78)

# ① 客户端自己算半径
# 第 106 轮诚实更新：基类新增了「把一组区域喂给显示窗口」的 showAreas()（高级版单显 / 全显用），
# 里面也有同一句 AreaOutline.showForView ⇒ 全局 replace(..., 1) 会打到 showAreas 而不是
# showCollectionRange，反例就失效了。因此这里改为**只在 showCollectionRange 的方法体里**注入
# 同一段错误代码（判据 client_geometry_problems 一个字都没改）。
_SHOW_BODY = method_body(SRC["base"], r"public void showCollectionRange\s*\(")
assert _SHOW_BODY, "找不到 showCollectionRange()"
MUT_CLIENT_GEOMETRY = SRC["base"].replace(
    _SHOW_BODY,
    _SHOW_BODY + NEWLINE + "        this.shownAreas.add(boxOf(new net.minecraft.world.phys.AABB(0, 0, 0)"
    + ".inflate(menu.getCollectRadius(0))));", 1)
check("⑨1反例：客户端自己 inflate 半径（读菜单半径）⇒ 判负",
      len(client_geometry_problems(MUT_CLIENT_GEOMETRY)) >= 1)

# ③ 高级版在 NO_AREA / NO_SELECTOR 也画半径框（= 把「不画」分支挪到绘制之后）
MUT_DRAW_ORDER = SRC["base"].replace(
    "if (packet.mode() == ShowCollectionRangePacket.MODE_NO_AREA",
    "AreaOutline.showForView(AreaOutline.slot(\"x\"), null);"
    + NEWLINE + "        if (packet.mode() == ShowCollectionRangePacket.MODE_NO_AREA", 1)
check("⑨2反例：把绘制提到「不画」判断之前（NO_AREA/NO_SELECTOR 也画框）⇒ 判负",
      len(draw_order_problems(MUT_DRAW_ORDER)) >= 1)

# ② 高级版把区域与半径叠加
MUT_ADV_MODE = SRC["adv_be"].replace(
    "        if (selectionForTick() != null) {",
    "        if (selectionForTick() == null) {" + NEWLINE
    + "            return ShowCollectionRangePacket.MODE_RADIUS;" + NEWLINE
    + "        }" + NEWLINE + "        if (selectionForTick() != null) {", 1)
check("⑨3反例：高级版加一条 MODE_RADIUS 分支（区域与半径叠加）⇒ 判负",
      len(adv_mode_problems(MUT_ADV_MODE)) >= 1)

# ④ 只做基础版（高级版不做按钮 / 不做口径）
MUT_ONLY_BASE_ADV = re.sub(
    r"@Override\s*protected boolean usesOwnRangeButton\s*\(\s*\)\s*\{[^}]*\}", "", SRC["adv"], flags=re.S)
MUT_ONLY_BASE_ADV = re.sub(
    r"@Override\s*protected int collectionRangeMode\s*\(\s*\)\s*\{.*?\n    \}", "",
    MUT_ONLY_BASE_ADV, flags=re.S)
check("⑨4反例：只做基础版（高级版没有自己的按钮 / 没有口径覆写）⇒ 判负",
      len(adv_button_problems(MUT_ONLY_BASE_ADV, SRC["base"])) >= 1
      and len(adv_mode_problems(MUT_ONLY_BASE_ADV)) >= 1)

# ⑤ 删掉按钮的点击路径
MUT_NO_CLICK = SRC["base"].replace(
    "minecraft.gameMode.handleInventoryButtonClick(menu.containerId,",
    "if (false) minecraft.gameMode.handleInventoryButtonClick(menu.containerId,", 1)
MUT_NO_CLICK = MUT_NO_CLICK.replace("CollectionCacheMenu.BUTTON_SHOW_RANGE);", "0);", 1)
check("⑨5反例：按钮不再走菜单按钮通道（点击路径断）⇒ 判负",
      len(base_button_problems(MUT_NO_CLICK)) >= 1)

# ⑥ removed() 退回「关界面立刻删框」（把重新武装整段换成旧写法）
MUT_REMOVED = SRC["base"].replace(
    "    public void removed() {", "    public void removedLegacy() {", 1)
check("⑨6反例：removed() 不再按剩余 TTL 重新武装（关界面框立刻消失）⇒ 判负",
      len(removed_problems(MUT_REMOVED)) >= 1)

# ⑦ 不可用状态不给提示
MUT_NO_HINT = SRC["base"].replace("            showRangeUnavailableHint(packet.mode());",
                                  "            // 提示被删掉", 1)
check("⑨7反例：删掉「不可用」提示（按钮点了没有任何反应）⇒ 判负",
      len(hint_problems(MUT_NO_HINT)) >= 1)

# ⑧ 几何口径又写成两处（框画这里、吸那里）
MUT_TWO_INFLATE = SRC["be"].replace(
    "        final AABB area = collectionRangeBox();",
    "        final AABB area = new AABB(worldPosition)\n"
    "            .inflate(getScanRadiusX(), getScanRadiusY(), getScanRadiusZ());", 1)
check("⑨8反例：实体查询里再写一遍 inflate（几何口径分裂）⇒ 判负",
      len(geometry_single_source_problems(MUT_TWO_INFLATE)) >= 1)

# ====================================================================== 汇总
print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 8 组反例自证）" % CHECKS[0])
sys.exit(0)
