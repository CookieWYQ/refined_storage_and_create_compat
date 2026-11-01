#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round93 自检：容器代理器 + 高级归流缓存仓 + 区域选定器（审计轮回归）。

只读源码 / 配置 / 语言文件 + 编译产物字节码，不启动游戏。每条断言都对应任务里的一句硬话，
末尾 12 个**反例自证**：把真实源码改回「线上 bug 的形态 / 交集语义 / 去掉守卫 / 写回 sanitize /
菜单类型错配 / 常驻红字回归」，本脚本必须判负 —— 否则说明断言是空转的。

检查分组
  ① 容器代理器
     a 代理槽视图必须可写（`SlotItemHandler#set` 无条件强转 `IItemHandlerModifiable`，线上吞物品的根因）；
     b 内容物视图「空槽 / 槽号越界 / 错类型」一律返回安全值，绝不抛异常；
     c 六面角色逐面存 NBT、角色变化 `invalidateCapabilities()`、禁用面 null、对象面不暴露流体；
     d 任何写路径不丢东西：替换语义 / 已占用不覆盖 / 超容量弹世界 / 待弹出队列落盘 + 三条 flush 路径
       + 拆方块前也 flush；
     e 不双计：默认只开 1 内容物面 + 1 对象面；说明不再常驻（第 97 轮删掉红字），改由悬停六面按钮的
       `face_tip.*` tooltip 承载 —— 旧 `warning` 键中英都必须已删除；
     f 菜单：代理槽 `SlotItemHandler` + `setChanged` 打到 BE、数据槽 8、客户端占位 handler 可写。
  ② 高级归流缓存仓
     a 512 格 / 32768 总容量（512×64）/ 1_024_000 mB；吞吐只走 `RsccRateLimiter`（不自己写公式）；
     b 菜单类型（线上「开界面即踢」回归）+ 139 槽 / 35 数据槽从源码常量**独立复算**；
     c 区域模式是「取代」不是「交集」：`areaAbsorptionBlocked()` 是唯一入口守卫，
       **所有**世界吸收路径（掉落物 / 相邻容器 / 世界流体 / 气体 / 经验）都在守卫之后；
       三条扫描路径先问 `areaScanHook()`，非 null 时绝不落到半径分支；
     d 界面四态（没装 / 生效 / 空 / 维度不符）与真实行为同源；
     e 性能：实体按簇拆分查询盒（区域全相邻时 = 单盒，每盒一次查询）+ contains 过滤；
        方块每 tick ≤2048 次查询、跨 tick 游标、区域改动归零、同 tick 复用；
     f NBT（区域槽 + 三轴半径 + 升级 + 阻塞 + 经验形态）/ 破坏结算 / 非集群（不并进基础版集群）。
  ③ 区域选定器
     a **P0 回归**：写回路径绝不 `sanitize`（否则配置调小后只是点选一行就把历史区域永久删掉）；
        `sanitize` 只出现在消费侧；ADD/REPLACE 时才校验新几何；
     b 服务端权威：旁观者拒绝、菜单 + containerId + `stillValid`（距离 / 方块存在性）、
       世界手势只认同一个主手、菜单不匹配时丢包（不退回主手）；
     c 区域记录：绝对坐标、min/max 归一化、单边/体积/世界边界上限走 Config、1×1×1 合法；
     d 维度：绑定在选定器上、跨维度只接受「改绑」、机器侧维度不符整段不吸收；
     e 客户端只在客户端读 / 发请求：不查能力、不写世界；空区域 / 无选中 / 单点区域不崩；
     f 不与既有范围显示重复实现（统一走 `AreaOutline`，不另发 ShowChargerRangePacket）。
  ④ 反例自证 ×12（ObjectView 只实现 IItemHandler / 写回 sanitize / 去掉吸收守卫 / 去掉 stillValid /
     工具提示用 Slot#index / 高级菜单传基础版 MenuType / 预算不夹取 / 区域模式退回半径 / 换面不失效能力 /
     维度不符不拦编辑 / 常驻红字回归 / 面提示键拼错）。

用法：python tools/selfcheck_round93_proxy_cache_area.py
退出码：0 = 全部 PASS。
"""
import io
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "rs_create_compat")
CLS = os.path.join(ROOT, "build", "lead_compile", "cretae", "cookiewyq", "rs_create_compat")
JAVAP = "D:/java21/bin/javap.exe"

PROBLEMS = []
CHECKS = [0]
INFO = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % name)
    else:
        PROBLEMS.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def info(text):
    INFO.append(text)
    print("[info] " + text)


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def rel(*parts):
    return os.path.join(SRC, *parts)


def load_sources():
    files = {
        "InventoryProxyHandlers.java": rel("support", "InventoryProxyHandlers.java"),
        "InventoryProxyBlockEntity.java": rel("block", "entity", "InventoryProxyBlockEntity.java"),
        "InventoryProxyBlock.java": rel("block", "InventoryProxyBlock.java"),
        "ProxyFaceRole.java": rel("block", "ProxyFaceRole.java"),
        "InventoryProxyMenu.java": rel("menu", "InventoryProxyMenu.java"),
        "InventoryProxyScreen.java": rel("client", "screen", "InventoryProxyScreen.java"),
        "RsccInventoryProxyWrench.java": rel("support", "RsccInventoryProxyWrench.java"),
        "ViewOnlyHandlers.java": rel("support", "ViewOnlyHandlers.java"),
        "AdvancedCollectionCacheBlockEntity.java":
            rel("block", "entity", "AdvancedCollectionCacheBlockEntity.java"),
        "AdvancedCollectionCacheBlock.java": rel("block", "AdvancedCollectionCacheBlock.java"),
        "AdvancedCollectionCacheMenu.java": rel("menu", "AdvancedCollectionCacheMenu.java"),
        "AdvancedCollectionCacheScreen.java":
            rel("client", "screen", "AdvancedCollectionCacheScreen.java"),
        "CollectionCacheBlockEntity.java": rel("block", "entity", "CollectionCacheBlockEntity.java"),
        # 第 99 轮：「查看吸取范围」的绘制 / 生命周期从高级界面<b>上移到基类</b>（两台机器共用
        # 同一条 S2C 权威链路），因此把基类界面也读进来 —— 判据不变，落点跟着实现搬家。
        "CollectionCacheScreen.java": rel("client", "screen", "CollectionCacheScreen.java"),
        "CollectionCacheMenu.java": rel("menu", "CollectionCacheMenu.java"),
        "RsccMachineCluster.java": rel("support", "RsccMachineCluster.java"),
        "BlockContentReleaser.java": rel("support", "BlockContentReleaser.java"),
        "AreaSelectorEditPacket.java": rel("network", "AreaSelectorEditPacket.java"),
        # 第 112 轮：两个写包（区域 / 每区域标记）的落点判据与写后同步收敛到一份实现，
        # 因此「服务端权威」的判断也跟着搬到这个文件（判据本身不变，只换落点）。
        "AreaSelectorTargets.java": rel("support", "AreaSelectorTargets.java"),
        "AreaMarkEditPacket.java": rel("network", "AreaMarkEditPacket.java"),
        "AreaSelectorItem.java": rel("item", "AreaSelectorItem.java"),
        "Area.java": rel("support", "Area.java"),
        "AreaSelection.java": rel("support", "AreaSelection.java"),
        "AreaScanHook.java": rel("support", "AreaScanHook.java"),
        "AreaSelectorHandler.java": rel("client", "AreaSelectorHandler.java"),
        "AreaSelectorScreen.java": rel("client", "screen", "AreaSelectorScreen.java"),
        "AreaOutline.java": rel("client", "AreaOutline.java"),
        "Config.java": rel("Config.java"),
        "RS_Create_Compat.java": rel("RS_Create_Compat.java"),
        "RangeChargerScreen.java": rel("client", "screen", "RangeChargerScreen.java"),
    }
    sources = {}
    for name, path in files.items():
        sources[name] = read(path) if os.path.isfile(path) else ""
    return sources


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def strip_comments_and_strings(text):
    """注释 + 字符串字面量都换成空格（行号不变）。判「代码里有没有用某样东西」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if char == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif char == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif char == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(char)
            i += 1
    return "".join(out)


def block_at(text, open_index):
    depth = 0
    for j in range(open_index, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:j]
    return ""


def class_body(source, simple_name):
    match = re.search(r"\bclass\s+%s\b[^{]*\{" % re.escape(simple_name), source)
    return "" if not match else block_at(source, source.find("{", match.start()))


def method_body(source, signature):
    start = source.find(signature)
    if start < 0:
        return ""
    opening = source.find("{", start)
    return "" if opening < 0 else block_at(source, opening)


def lang(path):
    return json.loads(read(path)) if os.path.isfile(path) else {}


# ==================================================================== 审计核心
# 返回「问题码」列表。真实源码必须为空；反例自证要求对应问题码出现。
AUDIT_CALLS = [0]


def audit(raw):
    problems = []
    emit_info = AUDIT_CALLS[0] == 0
    AUDIT_CALLS[0] += 1

    def code(name):
        return strip_comments_and_strings(raw.get(name, ""))

    def text(name):
        return strip_comments(raw.get(name, ""))

    def missing(name, *needles):
        body = code(name)
        return [needle for needle in needles if needle not in body]

    # ---------------- ① 容器代理器：代理槽可写（吞物品 bug 回归） ----------------
    handlers_code = code("InventoryProxyHandlers.java")
    object_view = class_body(handlers_code, "ObjectView")
    if missing("InventoryProxyHandlers.java",
               "public static final class ObjectView implements IItemHandlerModifiable"):
        problems.append("bug1-not-modifiable")
    if missing("InventoryProxyHandlers.java",
               "public void setStackInSlot(final int slot, final ItemStack stack)",
               "proxy.setProxyStack(stack.copyWithCount(1));"):
        problems.append("bug1-set-not-writing-proxy")
    set_body = method_body(object_view, "public void setStackInSlot(")
    if not set_body:
        problems.append("bug1-set-missing")
    else:
        # 空 stack 只允许「清空 + return」（终止分支）；超容量必须 ejectStack（绝不截断）。
        if "proxy.ejectStack(stack.copyWithCount(stack.getCount() - 1));" not in set_body:
            problems.append("bug1-overflow-dropped")
        if not re.search(r"if\s*\(stack\.isEmpty\(\)\)\s*\{[^}]*setProxyStack\(ItemStack\.EMPTY\);[^}]*return;",
                         set_body, re.S):
            problems.append("bug1-empty-branch")
        # 不允许「清空后（没有 return）再写新值」的中间态
        if re.search(r"setProxyStack\(ItemStack\.EMPTY\);(?:(?!return).)*?setProxyStack\(", set_body, re.S):
            problems.append("bug1-clear-then-write")
    insert_body = method_body(object_view, "public ItemStack insertItem(")
    if not insert_body:
        problems.append("bug1-insert-missing")
    else:
        guard = insert_body.find("if (!proxy.proxyStack().isEmpty())")
        write = insert_body.find("proxy.setProxyStack(stack.copyWithCount(1))")
        if guard < 0 or write < 0 or guard > write:
            problems.append("bug1-insert-overwrites")
        elif "return stack;" not in block_at(insert_body, insert_body.find("{", guard)):
            problems.append("bug1-insert-overwrites")
        if "stack.copyWithCount(stack.getCount() - 1)" not in insert_body:
            problems.append("bug1-insert-truncates")
    extract_body = method_body(object_view, "public ItemStack extractItem(")
    if not extract_body:
        problems.append("bug1-extract-missing")
    else:
        copied = extract_body.find("proxy.proxyStack().copyWithCount(1)")
        cleared = extract_body.find("proxy.setProxyStack(ItemStack.EMPTY)")
        if copied < 0 or cleared < 0 or copied > cleared:
            problems.append("bug1-extract-order")

    # ---------------- ①b 内容物视图：空槽 / 越界 / 错类型 = 安全值 ----------------
    contents_item = class_body(handlers_code, "ContentsItemView")
    contents_fluid = class_body(handlers_code, "ContentsFluidView")
    if not contents_item or not contents_fluid:
        problems.append("contents-views-missing")
    else:
        for needle in ("return handler == null ? 0 : handler.getSlots();",
                       "handler == null || slot < 0 || slot >= handler.getSlots()",
                       "handler.getSlots() || amount <= 0",
                       "? 0 : handler.getSlotLimit(slot)",
                       "slot >= 0 && slot < handler.getSlots() && handler.isItemValid(slot, stack)"):
            if needle not in contents_item:
                problems.append("contents-item-unsafe")
                break
        for needle in ("handler == null || tank < 0 || tank >= handler.getTanks()",
                       "handler == null ? 0 : handler.getTanks()"):
            if needle not in contents_fluid:
                problems.append("contents-fluid-unsafe")
                break

    # ---------------- ①c 六面角色 / 能力 / 失效 / 不暴露额外面 ----------------
    be_code = code("InventoryProxyBlockEntity.java")
    face_needles = (
        "return side == null ? ProxyFaceRole.CONTENTS : faces[side.ordinal()];",
        "case PROXY_OBJECT -> objectView;",
        "case CONTENTS -> contentsItemView;",
        "case CONTENTS_INPUT -> contentsInputItemView;",
        "case DISABLED -> null;",
        "case CONTENTS -> contentsFluidView;",
        "case CONTENTS_INPUT -> contentsInputFluidView;",
    )
    if any(needle not in be_code for needle in face_needles):
        problems.append("proxy-face-mapping")
    if "invalidateCapabilities();" not in be_code:
        problems.append("proxy-invalidate")
    set_face = method_body(be_code, "public void setFace(")
    if not set_face or "invalidateFaceCapabilities();" not in set_face:
        problems.append("proxy-invalidate")
    # 第 108 轮：身份缓存仍然在（同一个 stack + 不是退化态 ⇒ 直接复用，不在热路径上重解析），
    # 只是退化态改成了「按 tick 重试」（见 selfcheck_round108）。这里同时钉住两件事：
    # ① 存在「identity 命中就早退」的快路径；② 早退条件里带 needsReprobe，而不是裸的身份比较。
    if ("if (innerFor == proxyStack && !needsReprobe(now)) {" not in be_code
            or "private boolean needsReprobe(" not in be_code):
        problems.append("proxy-inner-cache")
    for field in ("private final InventoryProxyHandlers.ObjectView objectView;",
                  "private final InventoryProxyHandlers.ContentsItemView contentsItemView;",
                  "private final InventoryProxyHandlers.ContentsFluidView contentsFluidView;",
                  "private final InventoryProxyHandlers.ContentsInputItemView contentsInputItemView;",
                  "private final InventoryProxyHandlers.ContentsInputFluidView contentsInputFluidView;"):
        if field not in be_code:
            problems.append("proxy-view-instability")
            break
    # 逐面 NBT：六面都要写、按键名读回
    save_body = method_body(be_code, "protected void saveAdditional(")
    load_body = method_body(be_code, "protected void loadAdditional(")
    if not save_body or not load_body:
        problems.append("proxy-nbt")
    else:
        if ("for (final Direction direction : Direction.values())" not in save_body
                or "facesTag.putString(ProxyFaceRole.nbtKey(direction), faces[direction.ordinal()].id());"
                not in save_body):
            problems.append("proxy-nbt")
        if ("ProxyFaceRole.byId(facesTag.getString(key))" not in load_body
                or "faces[direction.ordinal()] = " not in load_body):
            problems.append("proxy-nbt")
    role_code = code("ProxyFaceRole.java")
    if ("map.put(Direction.NORTH, CONTENTS);" not in role_code
            or "map.put(Direction.SOUTH, PROXY_OBJECT);" not in role_code):
        problems.append("proxy-defaults")
    default_body = method_body(role_code, "public static Map<Direction, ProxyFaceRole> defaults(")
    if not default_body or "map.put(direction, DISABLED);" not in default_body or "return map;" not in default_body:
        problems.append("proxy-defaults")
    if "return DISABLED;" not in method_body(role_code, "public static ProxyFaceRole byId("):
        problems.append("proxy-nbt-unknown-key")

    # ---------------- ①d 任何路径不丢东西（PendingEject 兜底链） ----------------
    if "clearContent(" in be_code or "clearContent(" in handlers_code:
        problems.append("proxy-clear-content")
    if "tag.put(TAG_PENDING_EJECT, pending);" not in save_body:
        problems.append("proxy-pending-not-saved")
    if "tag.getList(TAG_PENDING_EJECT, Tag.TAG_COMPOUND)" not in load_body:
        problems.append("proxy-pending-not-loaded")
    if "final ItemStack previous = proxyStack;" not in load_body:
        problems.append("proxy-load-overwrites")
    if "ejectStack(previous);" not in load_body:
        problems.append("proxy-load-overwrites")
    if "flushPendingEject();" not in method_body(be_code, "public void setLevel("):
        problems.append("proxy-flush-on-level")
    if "flushPendingEject();" not in method_body(be_code, "public void onLoad("):
        problems.append("proxy-flush-on-load")
    if "public void flushPendingEjectBeforeRemoval()" not in be_code:
        problems.append("proxy-flush-before-removal")
    block_code = code("InventoryProxyBlock.java")
    remove_body = method_body(block_code, "public void onRemove(")
    if not remove_body:
        problems.append("proxy-remove-missing")
    else:
        if "flushPendingEjectBeforeRemoval();" not in remove_body:
            problems.append("proxy-flush-before-removal")
        if "takeProxyStackForDrop()" not in remove_body or "popResource(level, pos, dropped)" not in remove_body:
            problems.append("proxy-remove-drop")
        if "!level.isClientSide()" not in remove_body or "!state.is(newState.getBlock())" not in remove_body:
            problems.append("proxy-remove-drop")
    # 换面绝不碰槽内容
    for signature in ("public void setFace(", "public ProxyFaceRole cycleFace("):
        body = method_body(be_code, signature)
        if body and re.search(r"proxyStack|setProxyStack|takeProxyStackForDrop|ejectStack", body):
            problems.append("proxy-face-touches-slot")

    # ---------------- ①e 不双计：界面说明改由「悬停六面按钮」的 tooltip 承载 ----------------
    # 口径沿革（每次都是用户实机推翻上一版，判据跟着口径走、但判别力不能降）：
    #   · 第 95 轮：常显长文压成一行（用户「这么长一坨……你就把它去除不行吗」）；
    #   · 第 96 轮：语义从「警告别接两个外部存储」改成「内容物拆主/备两档，备面读恒空 ⇒ RS 不求和」；
    #   · 第 97 轮：用户第 3 条「字太小了，而且（就算）修复的话，也不喜欢那行字了」⇒ **删掉常驻红字**，
    #     信息挪进 `face_tip.*`，只在悬停六面按钮时出现（docs/ROUND97_USER_FEEDBACK.md B1）。
    # 因此这里改成：
    #   · 旧键 `inventory_proxy.warning` 必须在 zh / en **两边都已删除** —— 留着不画就是孤儿键，
    #     「已经删干净」这件事本身也就无从验证；
    #   · 4 条 `face_tip.*`（键名 = `ProxyFaceRole#id()`）中英齐全、非空、是短句；
    #   · 界面源码不再引用 warning 键、不再有 renderWarning，必须按角色 id 取 face_tip 并用
    #     renderTooltip 画出来（具体措辞与几何归 Lead，这里只钉「信息有承载点、且不会常驻糊屏」）；
    #   · 结构判据**一条不动**：CONTENTS_INPUT 两套视图都在（否则备面共享主视图 ⇒ 又翻倍）。
    zh = lang(os.path.join(ASSETS, "lang", "zh_cn.json"))
    en = lang(os.path.join(ASSETS, "lang", "en_us.json"))
    warn_key = "gui.rs_create_compat.inventory_proxy.warning"
    if warn_key in zh or warn_key in en:
        problems.append("proxy-face-tip")
    for role in ("disabled", "proxy_object", "contents", "contents_input"):
        key = "gui.rs_create_compat.inventory_proxy.face_tip." + role
        zh_tip = str(zh.get(key, ""))
        en_tip = str(en.get(key, ""))
        if (not zh_tip.strip() or not en_tip.strip()
                or len(zh_tip) > 60 or len(en_tip) > 120):
            problems.append("proxy-face-tip")
    screen_code = text("InventoryProxyScreen.java")
    screen_code_only = strip_comments_and_strings(screen_code)
    # 注：strip_comments_and_strings 会把字符串内容清空，所以「代码里有这个键」只在原文里找，
    # 「代码里没有这个方法名」在去注释后的代码里找；两者互补，避免注释/文档字样误伤判据。
    if 'LANG + "warning"' in screen_code or "renderWarning" in screen_code_only:
        problems.append("proxy-face-tip")
    if 'LANG + "face_tip."' not in screen_code:
        problems.append("proxy-face-tip")
    if "renderTooltip" not in screen_code_only:
        problems.append("proxy-face-tip")
    if "font.split" not in screen_code_only:
        problems.append("proxy-face-tip")
    if ("case CONTENTS_INPUT -> contentsInputItemView;" not in be_code
            or "case CONTENTS_INPUT -> contentsInputFluidView;" not in be_code):
        problems.append("proxy-face-tip")
    # 对象面不暴露流体 / 内容物面不包含代理槽（Contents*View 只转发 inner，不碰 proxyStack）
    contents_all = contents_item + contents_fluid
    if "proxy.proxyStack()" in contents_all:
        problems.append("proxy-contents-includes-object-slot")

    # ---------------- ①f 菜单：代理槽 + 数据槽 ----------------
    menu_code = code("InventoryProxyMenu.java")
    if not missing("InventoryProxyMenu.java",
                   "class ProxySlot extends SlotItemHandler",
                   "new ProxySlot(objectView, 0,",
                   "proxy.setChanged();",
                   "proxy.getObjectView()",
                   "new ItemStackHandler(1)"):
        pass
    else:
        problems.append("proxy-menu-slot")
    if ("addDataSlots(data);" not in menu_code
            # 第 108 轮：数据槽 8 → 11（新增内层四态 8 / 物品格数 9 / 流体罐数 10），
            # 因为用户第 108 条要求界面能直说「物品 0 格 / 流体 N 罐」。判据本身不变：
            # 声明个数、getCount() 返回同一个常量、菜单真的 addDataSlots。
            or "public static final int DATA_SLOTS = 11" not in be_code
            or "return DATA_SLOTS;" not in method_body(be_code, "public ContainerData getContainerData(")):
        problems.append("proxy-menu-data")
    if ("proxy.getLevel() != null && !proxy.getLevel().isClientSide()" not in
            method_body(menu_code, "public boolean clickMenuButton(")):
        problems.append("proxy-menu-button-authority")
    wrench_code = code("RsccInventoryProxyWrench.java")
    if not all(needle in wrench_code for needle in
               ("Tags.Items.TOOLS_WRENCH", "proxy.cycleFace(face)", "event.setCanceled(true)",
                "player.isCrouching()")):
        problems.append("proxy-wrench")
    if "RsccInventoryProxyWrench.register();" not in code("RS_Create_Compat.java"):
        problems.append("proxy-wrench-unregistered")

    # ---------------- ②a 容量 / 吞吐 ----------------
    adv_code = code("AdvancedCollectionCacheBlockEntity.java")
    config_code = code("Config.java")
    config_text = text("Config.java")  # 需要看字符串字面量（defineInRange 的键名 / 默认值）
    if "CACHE_SLOTS_ADVANCED = 512" not in adv_code:
        problems.append("adv-capacity")
    if "FLUID_CACHE_CAPACITY_ADVANCED = 1_024_000L" not in adv_code:
        problems.append("adv-capacity")
    if ('defineInRange("collectionCacheSlotCapacity", 64' not in config_text
            or 'defineInRange("collectionCacheSlotCapacityPerStackUpgrade", 64' not in config_text):
        problems.append("adv-capacity")
    # 吞吐不许自己写：高级版里不许出现「<<」/「* 64」/ 自己实现 batch / rate
    for forbidden in ("<<", "getTransferBatch(", "getProcessRate(", "* 64"):
        if forbidden in adv_code:
            problems.append("adv-own-formula")
            break
    base_code = code("CollectionCacheBlockEntity.java")
    if ("return (int) RsccRateLimiter.batchSaturating(64L, countUpgrades(STACK_UPGRADE));"
            not in method_body(base_code, "public int getTransferBatch(")):
        problems.append("adv-own-formula")
    if ("return 1 + RsccRateLimiter.level(countUpgrades(SPEED_UPGRADE));"
            not in method_body(base_code, "public int getProcessRate(")):
        problems.append("adv-own-formula")
    # 集群容量走实例字段（高级版不会被当成 256 格 / 512000 mB）
    if ("new SimpleContainer(cacheSlots * count)" not in base_code
            or "new MultiFluidCache(fluidCacheCapacity * count)" not in base_code):
        problems.append("adv-cluster-capacity")
    # 高级方块不与基础版成集群（RsccMachineCluster 用 instanceof CollectionCacheBlock 判定）
    adv_block = code("AdvancedCollectionCacheBlock.java")
    if re.search(r"class\s+AdvancedCollectionCacheBlock\s+extends\s+CollectionCacheBlock", adv_block):
        problems.append("adv-clustered-with-base")
    if "state.getBlock() instanceof CollectionCacheBlock" not in code("RsccMachineCluster.java"):
        problems.append("adv-clustered-with-base")

    # ---------------- ②b 菜单类型 + 139 / 35 独立复算 ----------------
    adv_menu = code("AdvancedCollectionCacheMenu.java")
    base_menu = code("CollectionCacheMenu.java")
    if "super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get(), id, inventory, block);" not in adv_menu:
        problems.append("bug2-menu-type")
    if "protected CollectionCacheMenu(final net.minecraft.world.inventory.MenuType<?> type," not in base_menu:
        problems.append("bug2-menu-type")
    if "super(type, id);" not in base_menu:
        problems.append("bug2-menu-type")
    if ("MENUS.register(\"advanced_collection_cache\"" not in text("RS_Create_Compat.java")
            or "new AdvancedCollectionCacheMenu(id, inventory)" not in code("RS_Create_Compat.java")):
        problems.append("bug2-menu-type")

    def const(source, name):
        match = re.search(r"\b%s\s*=\s*([^;]+);" % re.escape(name), source)
        return None if not match else match.group(1).strip()

    def eval_int(expression, env):
        """只允许整数四则运算 + 已解析的常量名（用于 `MATCH_WINDOW = GRID_COLS * VISIBLE_ROWS`）。"""
        value = expression or ""
        for name, number in env.items():
            value = re.sub(r"\b%s\b" % re.escape(name), str(number), value)
        if not re.fullmatch(r"[0-9+\-*/() ]+", value):
            return None
        try:
            return int(eval(value, {"__builtins__": {}}, {}))  # noqa: S307（自有源码里的常量表达式）
        except Exception:
            return None

    grid = const(base_menu, "GRID_COLS")
    rows = const(base_menu, "VISIBLE_ROWS")
    inv_cols = const(base_menu, "INV_COLS")
    upgrade = const(base_menu, "UPGRADE_COUNT")
    upgrade_slots = const(base_code, "UPGRADE_SLOTS")
    data_slots = const(base_menu, "DATA_SLOT_COUNT")
    match_window = const(base_menu, "MATCH_WINDOW")
    cache_window = const(base_menu, "CACHE_WINDOW")
    base_slot_expr = const(adv_menu, "BASE_SLOT_COUNT")
    expr = re.sub(r"\s+", " ", base_slot_expr or "").strip()
    env = {
        "GRID_COLS": int(grid), "VISIBLE_ROWS": int(rows), "INV_COLS": int(inv_cols),
        "UPGRADE_SLOTS": int(upgrade_slots), "UPGRADE_COUNT": int(upgrade_slots),
        "DATA_SLOT_COUNT": int(data_slots),
    }
    env["MATCH_WINDOW"] = eval_int(match_window, env)
    env["CACHE_WINDOW"] = eval_int(cache_window, env)
    expected_base = eval_int(expr, env)
    expected_advanced = expected_base + 1 if expected_base is not None else None
    # 第 101 轮：区域数据槽 = 面板 6 + 编辑上下文 1 + 逐区域计数 64（AREA_LIST_SLOT_MAX；第 121 轮 C-4 抬到 64）
    adv_area_expr = const(adv_menu, "AREA_DATA_COUNT") or ""
    adv_area_expr = adv_area_expr.replace(
        "AdvancedCollectionCacheBlockEntity.areaPanelDataCount()", "6")
    adv_env = {"AREA_LIST_SLOT_MAX": int(const(adv_menu, "AREA_LIST_SLOT_MAX") or 64)}
    area_data_slots = eval_int(adv_area_expr, adv_env)
    data_total = env["DATA_SLOT_COUNT"] + (area_data_slots or 0)
    if (int(grid) != 12 or int(rows) != 4 or int(inv_cols) != 9 or int(upgrade_slots) != 6
            or int(data_slots) != 31):
        problems.append("bug2-slot-arithmetic")
    if (expr != "MATCH_WINDOW + CACHE_WINDOW + UPGRADE_COUNT + 9 * 4"
            or env["MATCH_WINDOW"] != 48 or env["CACHE_WINDOW"] != 48):
        problems.append("bug2-slot-arithmetic")
    if expected_base != 138 or expected_advanced != 139:
        problems.append("bug2-slot-arithmetic")
    if "public static final int AREA_SLOT_INDEX = BASE_SLOT_COUNT;" not in adv_menu:
        problems.append("bug2-slot-arithmetic")
    # 数据槽总数 = 基础 DATA_SLOT_COUNT(31) + 区域 AREA_DATA_COUNT(6 面板 + 1 编辑上下文 + 64 逐区域计数 = 71)
    # ⇒ 102。区域数据槽必须由源码常量算出（不许写死数字），且仍然包含 areaPanelDataCount()。
    if data_total != 102 or area_data_slots != 71:
        problems.append("bug2-slot-arithmetic")
    if ("AdvancedCollectionCacheBlockEntity.areaPanelDataCount()" not in adv_menu
            or "AREA_LIST_SLOT_MAX" not in adv_menu or "+ 1" not in adv_menu):
        problems.append("bug2-slot-arithmetic")
    if "return 6;" not in method_body(adv_code, "public static int areaPanelDataCount("):
        problems.append("bug2-slot-arithmetic")
    # 槽位真的被 addSlot 出来：基础版 5 个 addSlot 循环（匹配 / 缓存 / 升级 / 背包 / 快捷栏）+ 区域槽 1 个
    addslot_count = base_menu.count("addSlot(") + adv_menu.count("addSlot(")
    if addslot_count != 6:
        problems.append("bug2-slot-arithmetic")
    if emit_info:
        info("菜单槽位复算：MATCH=%s CACHE=%s UPGRADE=%s→%s 玩家=%s×4 ⇒ 基础=%s / 高级=%s（+1 区域槽）；"
             "数据槽 %s + 6 = %s" % (env["MATCH_WINDOW"], env["CACHE_WINDOW"], upgrade, upgrade_slots,
                                     inv_cols, expected_base, expected_advanced, data_slots, data_total))

    # ---------------- ②c 区域模式「取代」：唯一守卫 + 无半径绕过 ----------------
    absorb = method_body(base_code, "private void absorbAll(")
    if not absorb:
        problems.append("area-guard-missing")
    else:
        # 第 101 轮：守卫块里多了「记状态码」两行（界面状态行），仍是「整段返回、绝不落到任何收集调用」。
        # 判据收紧成结构化的：守卫必须在方法头部（每轮重置语句之后）且在任何 collect* 调用之前返回。
        calls = ("collectFromDroppedItems(level);", "collectFromAdjacentContainers(level);",
                 "collectFromWorldFluids(level);", "collectGas(level);", "collectExperience(level);")
        guard_at = absorb.find("if (areaAbsorptionBlocked())")
        first_collect = min((absorb.find(c) for c in calls if absorb.find(c) >= 0), default=-1)
        if guard_at < 0 or first_collect < 0 or guard_at > first_collect or guard_at > 600:
            problems.append("area-guard-missing")
        else:
            guard_block = absorb[guard_at:first_collect]
            if "return;" not in guard_block:
                problems.append("area-guard-missing")
        for call in ("collectFromDroppedItems(level);", "collectFromAdjacentContainers(level);",
                     "collectFromWorldFluids(level);", "collectGas(level);",
                     "collectExperience(level);"):
            if call not in absorb:
                problems.append("area-entry-escapes-guard")
                break
            if base_code.count(call) != 1:
                # 有第二个调用点 = 存在绕过 absorbAll 守卫的吸收入口
                problems.append("area-entry-escapes-guard")
                break
        # 所有吸收入口只由 tickCache 驱动，且 tickCache 只调 absorbAll
        if base_code.count("absorbAll(level);") != 1:
            problems.append("area-entry-escapes-guard")
    # 三条扫描路径必须先问 hook；hook 为 null 才走半径
    for name in ("itemEntitiesInRadius", "experienceOrbsInRadius", "fluidSourcesInRadius"):
        body = method_body(base_code, "private List<" + ("ItemEntity" if name == "itemEntitiesInRadius"
                                                           else "net.minecraft.world.entity.ExperienceOrb" if name == "experienceOrbsInRadius"
                                                           else "BlockPos") + "> " + name + "(")
        if not body:
            problems.append("area-radius-path")
            break
        hook_at = body.find("final AreaScanHook areas = areaScanHook();")
        if hook_at < 0:
            problems.append("area-radius-path")
            break
        if "if (areas != null) {" not in body:
            problems.append("area-radius-path")
            break
        # 半径分支只能在 hook 分支之后
        radius_at = max(body.find("getScanRadiusX()"), body.find("resyncFluidScanCursorIfMoved();"))
        if radius_at >= 0 and radius_at < hook_at:
            problems.append("area-radius-path")
            break
    # 实体：第 121 轮 C-7 起 = 按簇拆分的多个查询盒（每簇盒向上扩 1 格；区域全相邻时仍是 1 个盒，
    # 否则「落在区域底板上的掉落物」在查询阶段就被整批丢掉 —— 第 101 轮 P0 根因）+ containsEntity 过滤
    if base_code.count("final List<AABB> boxes = areas.entityQueryAreas();") != 2:
        problems.append("area-entity-union")
    if base_code.count("found.removeIf(entity -> !areas.containsEntity(entity));") != 2:
        problems.append("area-entity-union")
    hook_code = sources.get("AreaScanHook.java", "")
    if ("default List<AABB> entityQueryAreas()" not in hook_code
            or "expandTowards(0.0D, 1.0D, 0.0D)" not in hook_code
            or "contains(pos) || contains(pos.below()) || contains(pos.above())" not in hook_code):
        problems.append("area-entity-union")
    if base_code.count("level.getEntitiesOfClass(ItemEntity.class, box,") != 1:
        problems.append("area-entity-union")
    # 高级版：无半径 / 交集调用
    for forbidden in ("getScanRadiusX()", "getScanRadiusY()", "getScanRadiusZ()",
                      "getCollectRadius(", ".intersects(", "betweenClosed"):
        if forbidden in adv_code:
            problems.append("area-intersection")
            break
    if "return selectionForTick() == null;" not in method_body(adv_code, "protected boolean areaAbsorptionBlocked("):
        problems.append("area-only-fallback")
    hook_body = method_body(adv_code, "protected AreaScanHook areaScanHook(")
    if not hook_body or "cursor.update(selection.areas());" not in hook_body:
        problems.append("area-hook")
    # 区域集合 / 选定器变化 ⇒ 游标归零（两条路径：换物品 & 编辑数据组件）
    if "cursor.reset();" not in adv_code:
        problems.append("area-cursor-reset")
    if "public void areaSelectorEdited()" not in adv_code:
        problems.append("area-cursor-reset")
    edited_body = method_body(adv_code, "public void areaSelectorEdited()")
    if not edited_body or "cursor.reset();" not in edited_body or "selectionTick = Long.MIN_VALUE;" not in edited_body:
        problems.append("area-cursor-reset")

    # ---------------- ②d 四态与真实行为同源 ----------------
    adv_status = method_body(adv_code, "public int getAreaStatus()")
    for needle in ("AREA_STATUS_NONE", "AREA_STATUS_EMPTY", "AREA_STATUS_DIMENSION_MISMATCH",
                   "AREA_STATUS_ACTIVE"):
        if needle not in adv_status:
            problems.append("area-status")
            break
    resolve = method_body(adv_code, "private AreaSelection resolveSelection(")
    for needle in ("AreaSelection.sanitize(AreaSelection.get(stack))",
                   "selection.dimension() == null || !selection.matchesDimension(",
                   "return null;"):
        if needle not in resolve:
            problems.append("area-status")
            break
    if "if (raw.dimension() == null) {" not in adv_status:
        # 「有区域但没绑定维度」必须与 resolveSelection 同一条判据（不吸收 + 状态如实），
        # 否则手工改 NBT 造出来的选定器会在任意维度生效。
        problems.append("area-status")
    selection_tick = method_body(adv_code, "private AreaSelection selectionForTick(")
    if "if (tick == selectionTick) {" not in selection_tick or "return cachedSelection;" not in selection_tick:
        problems.append("area-status")
    for needle in ("AREA_STATUS_NONE = 0", "AREA_STATUS_ACTIVE = 1", "AREA_STATUS_EMPTY = 2",
                   "AREA_STATUS_DIMENSION_MISMATCH = 3"):
        if needle not in adv_code:
            problems.append("area-status")
            break
    adv_screen = strip_comments(raw.get("AdvancedCollectionCacheScreen.java", ""))
    if adv_screen.count('LANG + "info.active"') != 1:
        problems.append("area-status")
    for needle in ('LANG + "info.empty"', 'LANG + "info.dimension"', 'LANG + "info.none"'):
        if needle not in adv_screen:
            problems.append("area-status")
            break
    for key in ("info.active", "info.empty", "info.dimension", "info.none"):
        full = "gui.rs_create_compat.advanced_collection_cache." + key
        if full not in zh or full not in en:
            problems.append("area-status-lang")
            break
    # 状态文案只有一个来源：render 的状态行与区域槽 tooltip 都调 statusLine()
    if adv_screen.count("statusLine(") < 3:
        problems.append("area-status")

    # ---------------- ②e 预算 / 游标 ----------------
    if "AREA_QUERY_BUDGET_PER_TICK = 2048" not in adv_code:
        problems.append("area-budget")
    for needle in ("final int remaining = AREA_QUERY_BUDGET_PER_TICK - queriesThisTick;",
                   "Math.min(remaining, Math.max(0, budget))",
                   "queriesThisTick += allowed;",
                   "if (windowTick == tick) {",
                   "if (budgetTick != tick) {",
                   "cursorIndex",
                   "if (next.equals(areas)) {"):
        if needle not in adv_code:
            problems.append("area-budget")
            break

    # ---------------- ②f NBT / 破坏结算 ----------------
    adv_save = method_body(adv_code, "public void saveAdditional(")
    adv_load = method_body(adv_code, "public void loadAdditional(")
    if not adv_save or "tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(registries));" not in adv_save:
        problems.append("area-nbt")
    if not adv_load or "areaSlot.deserializeNBT(registries, tag.getCompound(TAG_AREA_SLOT));" not in adv_load:
        problems.append("area-nbt")
    if not adv_load or "cursor.reset();" not in adv_load:
        problems.append("area-nbt")
    base_save = method_body(base_code, "public void saveAdditional(")
    for needle in ("writeRadii(tag);", "tag.put(TAG_UPGRADES,", "writeBlocked(tag);",
                   'tag.putString(TAG_XP_FORM, xpForm.name());', "writeAbsorb(tag);"):
        if needle not in base_save:
            problems.append("area-nbt")
            break
    adv_block_remove = method_body(code("AdvancedCollectionCacheBlock.java"), "public void onRemove(")
    if "collectHandler(cache.getAreaSlot(), out);" not in adv_block_remove:
        problems.append("area-destroy-settle")
    release = code("BlockContentReleaser.java")
    if ("final CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());" not in release
            or "BlockItem.setBlockEntityData(blockItem, be.getType(), tag);" not in release):
        problems.append("area-destroy-settle")
    if "if (plan.items() != BlockContentMode.BLOCK) {" not in release:
        problems.append("area-destroy-settle")

    # ---------------- ③a P0：写回不 sanitize（配置调小不丢区域） ----------------
    packet_code = code("AreaSelectorEditPacket.java")
    packet_handle = method_body(packet_code, "public static void handle(final AreaSelectorEditPacket packet, final ServerPlayer player)")
    if not packet_handle:
        problems.append("selector-packet-missing")
    else:
        if "AreaSelection.sanitize(" in packet_handle:
            problems.append("selector-sanitize-writeback")
        if "AreaSelection.set(target, selection);" not in packet_handle:
            problems.append("selector-sanitize-writeback")
        if "AreaSelection.sanitize(" in packet_code:
            problems.append("selector-sanitize-writeback")
        # ADD / REPLACE 才校验新几何；SELECT / DELETE 只动选中 / 列表
        for needle in ("selection.areaCount() >= AreaSelection.maxAreas()",
                       "area.invalidReason(AreaSelection.maxVolume())",
                       "selection = selection.remove(index);",
                       "selection = selection.select(index);"):
            if needle not in packet_handle:
                problems.append("selector-action-guard")
                break
    # sanitize 只在消费侧（高级仓解析扫描范围 / 状态）
    if "AreaSelection.sanitize(AreaSelection.get(stack))" not in resolve:
        problems.append("selector-consumer-sanitize")
    if "AreaSelection.sanitize(raw).isEmpty()" not in adv_status:
        problems.append("selector-consumer-sanitize")
    screen_code = strip_comments(raw.get("AreaSelectorScreen.java", ""))
    if "AreaSelection.sanitize(" in screen_code:
        problems.append("selector-client-sanitize")
    # 维度不符时界面必须自己先拦（否则服务端静默拒收 = 玩家看到「本地改了、其实没写」）
    if "private boolean editableHere()" not in screen_code:
        problems.append("selector-dimension-guard")
    if 'LANG + "err.dimension"' not in screen_code:
        problems.append("selector-dimension-guard")
    for signature in ("private void scheduleApply(", "private void flushApply(",
                      "private void createArea(", "private void deleteSelected("):
        body = method_body(screen_code, signature)
        if not body or "editableHere()" not in body:
            problems.append("selector-dimension-guard")
            break
    # 第 96 轮：输入框的启用条件多了「超限时也允许编辑（把超限区域改回合法值）」这一个分支，
    # 因此锚点从整行相等改成**前缀**：仍然要求「六个输入框的 active 完全由 editable 决定」，
    # 只是允许后面再与一个附加条件相与（这正是本轮新增的语义，不是放宽判据）。
    if "box.active = editable" not in screen_code:
        problems.append("selector-dimension-guard")
    # Config 承诺的判据：上限只有消费侧截断
    if 'defineInRange("areaSelectorMaxAreas", 16, 1, 64)' not in config_text:
        problems.append("selector-config")
    if 'defineInRange("areaSelectorMaxVolume", 32768, 1, 1048576)' not in config_text:
        problems.append("selector-config")

    # ---------------- ③b 服务端权威 ----------------
    if "if (player.isSpectator())" not in packet_handle:
        problems.append("selector-authority")
    # 第 112 轮：落点 / 同步收敛到 support/AreaSelectorTargets（区域包与标记包共用），判据一条没变。
    targets_code = code("AreaSelectorTargets.java")
    if "menu.stillValid(player)" not in targets_code:
        problems.append("selector-authority")
    if "if (containerId < 0) {" not in targets_code:
        problems.append("selector-authority")
    if "player.inventoryMenu.broadcastFullState();" not in targets_code:
        problems.append("selector-authority")
    if "menu.onAreaSelectorEdited();" not in targets_code:
        problems.append("selector-authority")
    if "AreaSelectorTargets.resolve(containerId, player)" not in packet_code:
        problems.append("selector-authority")
    if "AreaSelectorTargets.resolve(packet.containerId(), player)" not in code("AreaMarkEditPacket.java"):
        problems.append("selector-authority")
    if "public void onAreaSelectorEdited()" not in adv_menu:
        problems.append("selector-authority")
    # 菜单路径不匹配时丢包（绝不退回主手）
    resolve_body = method_body(targets_code, "public static ItemStack resolve(")
    if not resolve_body or resolve_body.find("return ItemStack.EMPTY;") < 0:
        problems.append("selector-authority")
    elif resolve_body.find("player.getMainHandItem()") < resolve_body.find("if (containerId < 0) {"):
        problems.append("selector-authority")

    # ---------------- ③c 区域记录：绝对坐标 / 归一化 / 上限 ----------------
    area_code = code("Area.java")
    # 第 96 轮：Area 多了一个可选 name 分量（重命名功能），因此这里不再要求「右括号紧跟 maxZ」，
    # 但仍然**逐个钉住前六个 int 分量的名字与顺序**（这才是本判据要抓的东西：有人把坐标字段改名 /
    # 重排 / 换成相对偏移 ⇒ 立刻判负）。
    if "record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ" not in area_code:
        problems.append("area-record")
    if "MAX_SIDE = 128" not in area_code or "WORLD_LIMIT = 30_000_000" not in area_code:
        problems.append("area-record")
    for needle in ("if (minX > maxX) {", "if (minY > maxY) {", "if (minZ > maxZ) {",
                   "public static Area single(final BlockPos pos)",
                   "private static long saturatingMultiply("):
        if needle not in area_code:
            problems.append("area-record")
            break
    invalid = method_body(text("Area.java"), "public String invalidReason(")
    for needle in ('return "out_of_world";', 'return "side_too_long";', 'return "volume_too_large";'):
        if needle not in invalid:
            problems.append("area-limit")
            break
    if "volume() > Math.max(1L, maxVolume)" not in invalid:
        problems.append("area-limit")
    if "public boolean matchesDimension(@Nullable final ResourceLocation current) {" not in code("AreaSelection.java"):
        problems.append("selector-dimension")
    if "return dimension == null || dimension.equals(current);" not in code("AreaSelection.java"):
        problems.append("selector-dimension")
    if "!selection.dimension().equals(dimension)" not in packet_code:
        problems.append("selector-dimension")

    # ---------------- ③e 客户端只读 / 空区域不崩 ----------------
    for name in ("AreaSelectorHandler.java", "AreaSelectorScreen.java", "AreaOutline.java"):
        client = code(name)
        for forbidden in ("getCapability(", "Capabilities.", ".setBlock(", "ServerLevel"):
            if forbidden in client:
                problems.append("selector-client-impure")
                break
    for name in ("AreaSelectorHandler.java", "AreaSelectorScreen.java"):
        if "PacketDistributor.sendToServer(" not in code(name):
            problems.append("selector-client-no-request")
            break
    outline = code("AreaOutline.java")
    if ("COLOR = 0xFF9F1C" not in outline or "try {" not in outline or "disabled = true;" not in outline):
        problems.append("area-outline")
    screen_sel = strip_comments(raw.get("AreaSelectorScreen.java", ""))
    if "final Area selected = selection.selected();" not in screen_sel:
        problems.append("area-null-guard")
    if "if (selected != null) {" not in screen_sel or "selected == null ? 0L : selected.volume()" not in screen_sel:
        problems.append("area-null-guard")
    handler_code = code("AreaSelectorHandler.java")
    if "if (pendingFirst == null) {" not in handler_code:
        problems.append("area-null-guard")
    # 空区域 / 单区域：volume 至少 1，cursor 对 total<=0 有守卫
    if "sizeX() > MAX_SIDE" not in area_code:
        problems.append("area-record")
    if "if (allowed <= 0 || total <= 0L || areas.isEmpty()) {" not in adv_code:
        problems.append("area-empty-guard")

    # ---------------- ③f 不与既有范围显示重复实现 ----------------
    for name in ("AreaSelectorHandler.java", "AreaSelectorScreen.java", "AreaOutline.java"):
        if "ShowChargerRangePacket" in code(name):
            problems.append("area-duplicate-packet")
            break
    outline_ui = adv_screen + strip_comments(raw.get("CollectionCacheScreen.java", ""))
    if "AreaOutline.chase(" not in handler_code or "AreaOutline.showForView(" not in outline_ui:
        problems.append("area-outline")
    # 统一色：充电器（既有范围显示）与本轮新代码必须是同一个常量
    if "RANGE_OUTLINE_COLOR = 0xFF9F1C" not in code("RangeChargerScreen.java"):
        problems.append("area-outline-color")

    # ---------------- P1：区域槽 tooltip 用「菜单内下标」+ 四态分支 ----------------
    if "menu.slots.indexOf(hoveredSlot) == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX" not in adv_screen:
        problems.append("selector-tooltip")
    if "hoveredSlot.index == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX" in adv_screen:
        problems.append("selector-tooltip")
    if adv_screen.count("statusLine(") < 2:
        problems.append("selector-tooltip")

    return problems


def audit_resources():
    problems = []
    wanted = [
        "assets/rs_create_compat/textures/gui/inventory_proxy.png",
        # 高级仓界面整类继承基础版界面（同一张背景图），所以这里查基础版贴图
        "assets/rs_create_compat/textures/gui/collection_cache.png",
        "assets/rs_create_compat/textures/item/area_selector.png",
    ]
    for rel_path in wanted:
        if not os.path.isfile(os.path.join(RES, *rel_path.split("/"))):
            problems.append("missing:" + rel_path)
    zh = lang(os.path.join(ASSETS, "lang", "zh_cn.json"))
    en = lang(os.path.join(ASSETS, "lang", "en_us.json"))
    if set(zh) != set(en):
        problems.append("lang-pair")
    for key in ("gui.rs_create_compat.advanced_collection_cache.area_slot.empty",
                "gui.rs_create_compat.advanced_collection_cache.area_slot.empty.tip",
                "gui.rs_create_compat.area_selector.title",
                "item.rs_create_compat.area_selector.help"):
        if key not in zh or key not in en:
            problems.append("lang:" + key)
    return problems


def audit_bytecode():
    """编译产物字节码证据（可选：未编译则跳过，不判负）。"""
    problems = []
    object_view = os.path.join(CLS, "support", "InventoryProxyHandlers$ObjectView.class")
    if os.path.isfile(JAVAP) and os.path.isfile(object_view):
        result = subprocess.run([JAVAP, "-p", object_view], capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
        if "implements net.neoforged.neoforge.items.IItemHandlerModifiable" not in result.stdout:
            problems.append("bytecode:ObjectView")
    else:
        info("字节码校验跳过（先跑 tools/_lead_compile.ps1）")
    menu_cls = os.path.join(CLS, "menu", "AdvancedCollectionCacheMenu.class")
    if os.path.isfile(JAVAP) and os.path.isfile(menu_cls):
        result = subprocess.run([JAVAP, "-c", "-p", menu_cls], capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
        if "ADVANCED_COLLECTION_CACHE_MENU" not in result.stdout:
            problems.append("bytecode:menu-type")
        if "CollectionCacheMenu.\"<init>\":(Lnet/minecraft/world/inventory/MenuType;I" not in result.stdout:
            problems.append("bytecode:menu-type")
    return problems


# ==================================================================== P0 模型（可复跑）
class SelectionModel:
    """`AreaSelection` 的语义模型：16 个区域 → 配置调小到 4 → 只做 SELECT → 数据必须还在。

    模型的行为与源码一一对应：
      * `write_back`（AreaSelectorEditPacket）= 把动作应用到不可变记录，**不 sanitize**；
      * `scan_view`（AdvancedCollectionCacheBlockEntity.resolveSelection / getAreaStatus）= sanitize；
      * sanitize 只截断「消费视图」，绝不回写。
    """

    def __init__(self, areas, max_areas):
        self.areas = list(areas)             # 物品上的原始数据（持久化）
        self.selected = -1
        self.max_areas = max_areas           # 当前 Config（可被管理员改小 / 改大）

    def select(self, index):
        """SELECT 动作：只改 selectedIndex，几何与条目数一个字节都不动。"""
        self.selected = -1 if not self.areas else max(-1, min(index, len(self.areas) - 1))
        return self

    def scan_view(self):
        """消费侧 sanitize：超限部分不参与扫描。"""
        kept = self.areas[:max(1, self.max_areas)]
        return {"count": len(kept), "selected": -1 if not kept else min(self.selected, len(kept) - 1)}


def model_p0_ok():
    model = SelectionModel([i for i in range(16)], max_areas=16)
    before = len(model.areas)
    model.max_areas = 4
    model.select(2)                                   # 玩家只是点选一行
    preserved = len(model.areas) == before            # 数据不丢
    active_while_small = model.scan_view()["count"] == 4
    model.max_areas = 16
    recovered = model.scan_view()["count"] == 16      # 配置调回大值 ⇒ 全部重新参与扫描
    return preserved and active_while_small and recovered


# ==================================================================== 主流程
sources = load_sources()

for name in ("InventoryProxyHandlers.java", "InventoryProxyBlockEntity.java", "ProxyFaceRole.java",
             "InventoryProxyMenu.java", "AdvancedCollectionCacheBlockEntity.java",
             "AdvancedCollectionCacheMenu.java", "AdvancedCollectionCacheScreen.java",
             "AreaSelectorEditPacket.java", "Area.java", "AreaSelection.java",
             "AreaSelectorHandler.java", "AreaSelectorScreen.java", "AreaOutline.java"):
    check("① 源码存在：%s" % name, sources[name] != "")

real_problems = sorted(set(audit(sources)))
check("①②③ 源码审计 0 问题（%d 项判据合一）" % 1, not real_problems, ", ".join(real_problems))

# 展开关键断言（失败时能直接定位，而不是只有一个大断言）
proxy_code = strip_comments_and_strings(sources["InventoryProxyHandlers.java"])
check("①a 代理槽视图实现 IItemHandlerModifiable（SlotItemHandler#set 强转的回归）",
      "public static final class ObjectView implements IItemHandlerModifiable" in proxy_code)
be_code = strip_comments_and_strings(sources["InventoryProxyBlockEntity.java"])
check("①c 换面会 invalidateCapabilities()（外部能力缓存不悬空）",
      "invalidateCapabilities();" in be_code)
check("①d 拆方块前先 flush 兜底队列，再取走代理对象",
      "flushPendingEjectBeforeRemoval();" in strip_comments_and_strings(sources["InventoryProxyBlock.java"]))
check("①d 待弹出队列落盘（PendingEject）",
      "tag.put(TAG_PENDING_EJECT, pending);" in be_code
      and "tag.getList(TAG_PENDING_EJECT, Tag.TAG_COMPOUND)" in be_code)
check("①e 默认只开 1 内容物面 + 1 对象面（从根上避免双计）",
      "map.put(Direction.NORTH, CONTENTS);" in strip_comments_and_strings(sources["ProxyFaceRole.java"])
      and "map.put(Direction.SOUTH, PROXY_OBJECT);" in strip_comments_and_strings(sources["ProxyFaceRole.java"]))

adv_code_s = strip_comments_and_strings(sources["AdvancedCollectionCacheBlockEntity.java"])
base_code_s = strip_comments_and_strings(sources["CollectionCacheBlockEntity.java"])
check("②a 512 格 × 每格 64（Config 默认）= 32768 总容量；流体 1_024_000 mB",
      "CACHE_SLOTS_ADVANCED = 512" in adv_code_s
      and "FLUID_CACHE_CAPACITY_ADVANCED = 1_024_000L" in adv_code_s
      and 512 * 64 == 32768)
check("②a 吞吐只走 RsccRateLimiter（高级版里没有任何自己写的位移 / 乘法公式）",
      "RsccRateLimiter.batchSaturating(64L, countUpgrades(STACK_UPGRADE))" in base_code_s
      and "RsccRateLimiter.level(countUpgrades(SPEED_UPGRADE))" in base_code_s
      and all(t not in adv_code_s for t in ("<<", "getTransferBatch(", "* 64")))
check("②b 高级菜单 super 传自己的 MenuType（线上「开界面即踢」回归）",
      "super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get(), id, inventory, block);"
      in strip_comments_and_strings(sources["AdvancedCollectionCacheMenu.java"]))
# 第 101 轮：守卫块里多了「记状态码」两行（界面状态行），因此不再要求字面「第一句」，
# 而是要求：守卫在任何 collect* 之前、且守卫块内 return（结构等价，强度不降）。
absorb_c_s = method_body(base_code_s, "private void absorbAll(")
_guard_c = absorb_c_s.find("if (areaAbsorptionBlocked())")
_calls_c = ("collectFromDroppedItems(level);", "collectFromAdjacentContainers(level);",
            "collectFromWorldFluids(level);", "collectGas(level);", "collectExperience(level);")
_first_c = min((absorb_c_s.find(c) for c in _calls_c if absorb_c_s.find(c) >= 0), default=-1)
check("②c 吸收总入口的区域守卫在任何收集调用之前 return（区域模式绝不退回半径）",
      _guard_c >= 0 and _first_c > _guard_c and "return;" in absorb_c_s[_guard_c:_first_c])
check("②c 区域模式：三条扫描路径都先问 areaScanHook()",
      base_code_s.count("final AreaScanHook areas = areaScanHook();") == 3)
check("②c 实体查询：按簇多盒各查一次 + containsEntity 过滤（每簇盒向上扩 1 格）",
      base_code_s.count("final List<AABB> boxes = areas.entityQueryAreas();") == 2
      and base_code_s.count("found.removeIf(entity -> !areas.containsEntity(entity));") == 2)
check("②e 方块查询硬上限 2048/tick 且按「剩余预算」夹取",
      "AREA_QUERY_BUDGET_PER_TICK = 2048" in adv_code_s
      and "Math.min(remaining, Math.max(0, budget))" in adv_code_s)
check("②e 同 tick 第二次调用复用窗口、区域改动游标归零",
      "if (windowTick == tick) {" in adv_code_s and "if (next.equals(areas)) {" in adv_code_s
      and "cursor.reset();" in adv_code_s)
check("②f 区域槽随方块实体落盘 / 读档",
      "tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(registries));" in adv_code_s
      and "areaSlot.deserializeNBT(registries, tag.getCompound(TAG_AREA_SLOT));" in adv_code_s)
check("②f 破坏时区域选定器按内容物策略结算（不会凭空消失）",
      "collectHandler(cache.getAreaSlot(), out);" in
      strip_comments_and_strings(sources["AdvancedCollectionCacheBlock.java"]))
check("②f 高级仓不并进基础版机器集群（容量不会时大时小）",
      "state.getBlock() instanceof CollectionCacheBlock" in
      strip_comments_and_strings(sources["RsccMachineCluster.java"])
      and not re.search(r"class\s+AdvancedCollectionCacheBlock\s+extends\s+CollectionCacheBlock",
                        strip_comments_and_strings(sources["AdvancedCollectionCacheBlock.java"])))

packet_s = strip_comments_and_strings(sources["AreaSelectorEditPacket.java"])
targets_s = strip_comments_and_strings(sources["AreaSelectorTargets.java"])
mark_packet_s = strip_comments_and_strings(sources["AreaMarkEditPacket.java"])
check("③a [P0] 写回路径不 sanitize（配置调小 + 点选一行不得删区域）",
      "AreaSelection.set(target, selection);" in packet_s
      and "AreaSelection.sanitize(" not in packet_s)
check("③a [P0] 可复跑模型：16 区域 → 上限调 4 → SELECT → 数据仍 16；调回 16 ⇒ 全部重新参与扫描",
      model_p0_ok())
check("③a 上限校验只发生在 ADD / REPLACE 的新几何上",
      "selection.areaCount() >= AreaSelection.maxAreas()" in packet_s
      and packet_s.count("area.invalidReason(AreaSelection.maxVolume())") == 2)
check("③b 服务端权威：旁观者拒绝 + stillValid（距离 / 方块）+ 落点解析",
      "if (player.isSpectator())" in packet_s
      and "menu.stillValid(player)" in targets_s
      and "if (containerId < 0) {" in targets_s
      and "AreaSelectorTargets.resolve(containerId, player)" in packet_s
      and "AreaSelectorTargets.resolve(packet.containerId(), player)"
      in strip_comments_and_strings(sources["AreaMarkEditPacket.java"]))
check("③b 区域数据写回后显式通知方块实体（标脏 + 游标失效）",
      "menu.onAreaSelectorEdited();" in targets_s
      and "public void areaSelectorEdited()" in adv_code_s)
check("③c 区域归一化 + 单边/体积/世界边界上限 + 1×1×1 合法",
      "if (minX > maxX) {" in strip_comments_and_strings(sources["Area.java"])
      and "MAX_SIDE = 128" in strip_comments_and_strings(sources["Area.java"])
      and "public static Area single(final BlockPos pos)" in strip_comments_and_strings(sources["Area.java"]))
check("③d 维度绑定在选定器上；维度不符 ⇒ 机器整段不吸收",
      "return dimension == null || dimension.equals(current);"
      in strip_comments_and_strings(sources["AreaSelection.java"])
      and "!selection.dimension().equals(dimension)" in packet_s
      and "!selection.matchesDimension(currentLevel.dimension().location())" in adv_code_s)
check("③e 客户端不查能力 / 不写世界（只发请求）",
      all(forbidden not in strip_comments_and_strings(sources[name])
          for name in ("AreaSelectorHandler.java", "AreaSelectorScreen.java", "AreaOutline.java")
          for forbidden in ("getCapability(", "Capabilities.", ".setBlock(", "ServerLevel"))
      and "PacketDistributor.sendToServer(" in strip_comments_and_strings(sources["AreaSelectorHandler.java"]))
check("③e 空区域 / 无选中 / 单点区域都有判空守卫",
      "if (selected != null) {" in strip_comments_and_strings(sources["AreaSelectorScreen.java"])
      and "selected == null ? 0L : selected.volume()" in
      strip_comments_and_strings(sources["AreaSelectorScreen.java"])
      and "final boolean has = selected != null;" in
      strip_comments_and_strings(sources["AreaSelectorScreen.java"])
      and "if (allowed <= 0 || total <= 0L || areas.isEmpty()) {" in adv_code_s)
check("③f 范围显示统一走 AreaOutline（橙色 0xFF9F1C），不另发 ShowChargerRangePacket",
      "COLOR = 0xFF9F1C" in strip_comments_and_strings(sources["AreaOutline.java"])
      and all("ShowChargerRangePacket" not in strip_comments_and_strings(sources[name])
              for name in ("AreaSelectorHandler.java", "AreaSelectorScreen.java", "AreaOutline.java")))
adv_screen_s = strip_comments_and_strings(sources["AdvancedCollectionCacheScreen.java"])
check("P1 区域槽 tooltip 用「菜单内下标」定位（Slot#index 恒为 0 的坑）",
      "menu.slots.indexOf(hoveredSlot) == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX" in adv_screen_s
      and "hoveredSlot.index == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX" not in adv_screen_s)
check("P1 区域槽 tooltip 与状态行共用四态文案（不再无条件写「生效 N 个区域」）",
      adv_screen_s.count("statusLine(") >= 3
      and all(('LANG + "%s"' % key) in strip_comments(sources["AdvancedCollectionCacheScreen.java"])
              for key in ("info.active", "info.empty", "info.dimension", "info.none")))
handler_s = strip_comments_and_strings(sources["AreaSelectorHandler.java"])
check("P1「潜行 + 右键方块但没有未完成框」会给出提示（而不是静默消费右键）",
      'MSG + "sneak_block_hint"' in strip_comments(sources["AreaSelectorHandler.java"]))
check("③d 客户端也按「维度不符」闸住编辑（否则服务端静默拒收 = 玩家以为改了）",
      "private boolean editableHere()" in
      strip_comments_and_strings(sources["AreaSelectorScreen.java"])
      and "editableHere()" in
      method_body(strip_comments_and_strings(sources["AreaSelectorScreen.java"]),
                  "private void flushApply(")
      and 'LANG + "err.dimension"' in strip_comments(sources["AreaSelectorScreen.java"]))
check("P2 拆方块前补上 PendingEject 的 flush（兜底链不缺环）",
      "flushPendingEjectBeforeRemoval();" in
      strip_comments_and_strings(sources["InventoryProxyBlock.java"])
      and "public void flushPendingEjectBeforeRemoval()" in be_code)
check("P2 ViewOnlyHandlers 注释与事实一致（默认 null 面；接收器是无方向限制的例外；"
      "第 96 轮起接收器那份是「可写包装、只进不出」）",
      "接收器例外" in sources["ViewOnlyHandlers.java"]
      and "direction == null" in sources["ViewOnlyHandlers.java"]
      and "都返回同一个视图" in sources["ViewOnlyHandlers.java"]
      and "可写包装" in sources["ViewOnlyHandlers.java"]
      and "writableItem" in sources["ViewOnlyHandlers.java"]
      and "writableFluid" in sources["ViewOnlyHandlers.java"])

resource_problems = audit_resources()
check("① 资源 / 语言：代理器与高级仓 GUI 贴图、区域选定器图标、中英键成对",
      not resource_problems, ", ".join(resource_problems))

# 待 Lead 补的 lang 键（代码已引用、语言文件还没有）：只报告，不算失败 —— 这两条文案
# 属于 Lead 写域（lang/*.json），本脚本在这里把它列出来，避免「代码引用了一个不存在的键」被忽略。
pending_lang = []
_zh = lang(os.path.join(ASSETS, "lang", "zh_cn.json"))
for key in ("message.rs_create_compat.area_selector.sneak_block_hint",
            "gui.rs_create_compat.area_selector.hint.over_limit",
            "gui.rs_create_compat.area_selector.err.dimension"):
    if key not in _zh:
        pending_lang.append(key)
if pending_lang:
    info("待 Lead 代改 lang 键（代码已引用、当前缺文案）：%s" % ", ".join(pending_lang))

bytecode_problems = audit_bytecode()
if bytecode_problems:
    check("①b 字节码证据：ObjectView 真的实现 IItemHandlerModifiable / 高级菜单真的传高级 MenuType",
          False, ", ".join(bytecode_problems))
else:
    check("①b 字节码证据：ObjectView 真的实现 IItemHandlerModifiable / 高级菜单真的传高级 MenuType",
          True)

# ==================================================================== ④ 反例自证
def mutate(name, old, new):
    if old not in sources[name]:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (name, old))
    copy = dict(sources)
    copy[name] = sources[name].replace(old, new, 1)
    return copy


# 1. 线上 bug 形态：代理槽视图只实现 IItemHandler
case = mutate("InventoryProxyHandlers.java",
              "public static final class ObjectView implements IItemHandlerModifiable {",
              "public static final class ObjectView implements IItemHandler {")
check("④a 反例（ObjectView 只实现 IItemHandler，即线上吞物品的形态）判负",
      "bug1-not-modifiable" in audit(case))

# 2. 线上 bug 形态：高级菜单沿用基础版 MenuType
case = mutate("AdvancedCollectionCacheMenu.java",
              "super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get(), id, inventory, block);",
              "super(RS_Create_Compat.COLLECTION_CACHE_MENU.get(), id, inventory, block);")
check("④b 反例（高级菜单沿用基础版 MenuType，即线上开界面踢人）判负",
      "bug2-menu-type" in audit(case))

# 3. P0 回归：写回时顺手 sanitize
case = mutate("AreaSelectorEditPacket.java",
              "AreaSelection.set(target, selection);",
              "AreaSelection.set(target, AreaSelection.sanitize(selection));")
check("④c 反例（写回 sanitize ⇒ 配置调小 + 一次点选就永久删区域）判负",
      "selector-sanitize-writeback" in audit(case))

# 4. 去掉吸收总入口的区域守卫（区域模式静默退回半径）
case = mutate("CollectionCacheBlockEntity.java",
              "if (areaAbsorptionBlocked()) {\n            absorbStatusReason = absorbStatusOverride();\n            absorbStatusArg = 0;\n            return;\n        }",
              "")
check("④d 反例（去掉 areaAbsorptionBlocked 守卫 ⇒ 区域模式按半径吸收）判负",
      "area-guard-missing" in audit(case))

# 5. 三条扫描路径里把 hook 判断删掉（某一入口偷偷走半径）
case = mutate("CollectionCacheBlockEntity.java",
              "final AreaScanHook areas = areaScanHook();\n        if (areas != null) {",
              "final AreaScanHook areas = areaScanHook();\n        if (false) {")
check("④e 反例（扫描路径绕过区域几何 ⇒ 退回半径）判负",
      "area-radius-path" in audit(case))

# 6. 去掉菜单路径的 stillValid 距离校验（第 112 轮：落点搬到 AreaSelectorTargets）
case = mutate("AreaSelectorTargets.java",
              "|| menu.containerId != containerId || !menu.stillValid(player)) {",
              "|| menu.containerId != containerId) {")
check("④f 反例（C2S 包不校验距离 / 方块存在性）判负",
      "selector-authority" in audit(case))

# 7. 区域槽 tooltip 退回 Slot#index（死代码 + 与状态行矛盾）
case = mutate("AdvancedCollectionCacheScreen.java",
              "menu.slots.indexOf(hoveredSlot) == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX",
              "hoveredSlot.index == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX")
check("④g 反例（tooltip 用 Slot#index：永久为假 / base 界面误报标记 0）判负",
      "selector-tooltip" in audit(case))

# 8. 预算不夹取（每 tick 全扫）
case = mutate("AdvancedCollectionCacheBlockEntity.java",
              "Math.min(remaining, Math.max(0, budget))",
              "Math.max(0, budget)")
check("④h 反例（去掉 2048/tick 预算夹取）判负",
      "area-budget" in audit(case))

# 9. 换面不作废能力缓存
case = mutate("InventoryProxyBlockEntity.java",
              "        faces[direction.ordinal()] = role;\n        setChanged();\n        invalidateFaceCapabilities();",
              "        faces[direction.ordinal()] = role;\n        setChanged();")
check("④i 反例（换面不 invalidateCapabilities ⇒ 外部缓存永远旧结论）判负",
      "proxy-invalidate" in audit(case))

# 10. 维度不符时界面不拦编辑（服务端静默拒收 ⇒ 玩家以为改了）
# 第 96 轮：界面重写后这条守卫出现在 flushApply / createArea / copySelected / deleteSelected 四处，
#   且函数体里多了「把出错的字段描红」等语句，逐字样替换的片段已经站不住。
#   反例改成「把所有 editableHere() 守卫一次性变成 if (false)」——
#   等价于「界面完全不拦」，语义比只删一处更强（四处都松掉），判据一个字没放宽。
case = dict(sources)
case["AreaSelectorScreen.java"] = case["AreaSelectorScreen.java"].replace(
    "if (!editableHere()) {", "if (false) {")
check("④j 反例（维度不符时界面不拦编辑 ⇒ 静默失效）判负",
      "selector-dimension-guard" in audit(case))

# 11. 常驻红字回归（第 97 轮用户第 3 条明确否掉那行字）
case = mutate("InventoryProxyScreen.java",
              "    private void renderFaceTip(",
              "    private void renderWarning() {\n    }\n\n    private void renderFaceTip(")
check("④k 反例（把 renderWarning 方法加回去 ⇒ 常驻红字回归）判负",
      "proxy-face-tip" in audit(case))

# 12. 面说明的键没跟上（键名拼错 / 退回不存在的键 ⇒ 玩家悬停只看到 raw key）
case = mutate("InventoryProxyScreen.java",
              'LANG + "face_tip."',
              'LANG + "face_note."')
check("④l 反例（面提示键拼错成 face_note ⇒ tooltip 只会显示原始键）判负",
      "proxy-face-tip" in audit(case))

# ==================================================================== 汇总
print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 12 个反例自证 + 1 个 P0 可复跑模型）；"
      "容器代理器 / 高级归流缓存仓 / 区域选定器 满足本轮审计的不变式" % CHECKS[0])
sys.exit(0)
