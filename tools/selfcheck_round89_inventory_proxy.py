#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 89 轮自检：容器代理器（inventory_proxy）+ RS 磁盘物品能力适配器。

判据全部来自工程内真实来源（Java 源码 / blockstate / model / 贴图 / 配方 / 语言文件），
不依赖人工维护清单：改了代码忘了改资源、或把某条约束悄悄改回去，都会被这里抓住。

断言分组（任一 FAIL ⇒ 退出码 1）：
  ① 方块 / 物品 / 方块实体 / 菜单 / 模型 / 贴图 / 配方 / 掉落表 / 语言键齐全；
  ② 六面配置逐面存 NBT，且默认值恰为 NORTH=内容物、SOUTH=代理对象、其余禁用；
  ③ 不做 RS 网络节点（源码级：新类里没有 NetworkNode 容器能力 / 没有继承网络节点基类）；
  ④ 内容物面按「面 → 配置」返回对应能力（null 面 = 内容物；对象面只有物品；禁用面 null）；
  ⑤ 换掉代理对象时内层缓存失效（身份判据 + 唯一写入口清缓存），外层视图实例稳定；
  ⑥ RS 磁盘适配器：registerItem + StorageContainerItem 遍历 + ServerLifecycleHooks/isSameThread 门禁；
  ⑦ 界面说明的承载方式：常驻红字已删除（第 97 轮），信息改由「悬停六面按钮」的 face_tip tooltip 提供；
     旧 `inventory_proxy.warning` 键中英都已删除，4 条 `face_tip.*` 中英齐全非空且是短句；
  ⑧ 反例自证：把「注册 NetworkNode / 靠物品类型判面 / 缓存不失效 / 去掉线程门禁 / 常驻红字回归 /
     face_tip 文案缺失」这些坏实现注入同一批检测函数，检测函数必须判负 —— 否则这些检测本身没有判别力。
  ⑨ 「任何路径都不许静默吞掉代理槽里的物品」（本轮 bug 的回归）：
     a 代理槽视图必须 IItemHandlerModifiable（NeoForge SlotItemHandler#set 无条件强制转型）；
     b 没有任何写路径「先清空再写」/ 没有 clearContent()；
     c insertItem 已占用 ⇒ 原样退回，不覆盖；d 取物/拆除先取后清；e 拆方块弹出；
     f 读档覆盖前先弹出（无世界时入队落盘）；g 换面绝不碰槽内容；
     h/i 可复跑模型（放入/取出/漏斗/换面/存读档/拆除）恒等式 + 反例判负；
     j..o 六个反例自证（视图只实现 IItemHandler / 先清空再写 / onRemove 无条件 clear /
     insert 覆盖 / 读档直接覆盖 / 换面清空）都必须判负。

用法：python tools/selfcheck_round89_inventory_proxy.py
退出码：0 = 全部 PASS。
"""

from __future__ import annotations

import io
import json
import os
import re
import struct
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat")
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

IDENT = "inventory_proxy"
BE = "InventoryProxyBlockEntity"
BLOCK = "InventoryProxyBlock"
MENU = "InventoryProxyMenu"
SCREEN = "InventoryProxyScreen"
ROLE = "ProxyFaceRole"
DISK_HANDLER = "RsccDiskItemHandler"
DISK_CAPS = "RsccDiskItemCapabilities"

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if (detail and not ok) else ""))


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def read_json(path):
    return json.loads(read(path))


def code_of(rel):
    path = os.path.join(JAVA, rel)
    return read(path) if os.path.isfile(path) else ""


def strip_comments_and_strings(text):
    """把注释与字符串字面量换成空格（行号不变）。判「代码里有没有用某样东西」时必须先剥，
    因为本工程的类注释会**故意**提到要避免的坏写法（NetworkNode / 物品类型判面…）。"""
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


def png_size(path):
    """只读 PNG 头（IHDR）拿宽高；不依赖 Pillow。"""
    with open(path, "rb") as handle:
        head = handle.read(24)
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


# ============================================================ 可复用的检测函数
# 这些函数同时被「真实现」与「反例」使用：反例必须判负，否则检测没有判别力（见 check_negative_controls）。

NETWORK_NODE_MARKERS = (
    "AbstractNetworkNodeContainerBlockEntity",
    "AbstractNetworkNodeContainerBlock",
    "NetworkNodeContainerProvider",
    "getNetworkNodeContainerProviderCapability",
    "AbstractNetworkNode",
    "NetworkNodeBlockEntityTicker",
)


def no_network_node_ok(source):
    """③ 源码级「不是 RS 网络节点」：代码（剥注释/字符串后）里没有任何网络节点标记。"""
    code = strip_comments_and_strings(source)
    return not any(marker in code for marker in NETWORK_NODE_MARKERS)


def face_config_driven_ok(be_source):
    """④ 面角色必须由「每面配置」决定（side -> faces[ordinal]），且四档分别映射到各自的视图。

    第 96 轮（用户第 3 条「接两个外部总线会翻倍」）把内容物拆成「主（完整读写）」与
    「备·仅进（读恒空 / 写真实）」两档 ⇒ 这里从三档锚点更新为四档锚点，**加严**而不是放宽：
    两套映射（物品 + 流体）都必须分主/备，任何一档漏掉都判负。
    """
    code = strip_comments_and_strings(be_source)
    return (
        "side == null ? ProxyFaceRole.CONTENTS : faces[side.ordinal()]" in code
        and "case PROXY_OBJECT -> objectView;" in code
        and "case CONTENTS -> contentsItemView;" in code
        and "case CONTENTS_INPUT -> contentsInputItemView;" in code
        and "case DISABLED -> null;" in code
        and "case CONTENTS -> contentsFluidView;" in code
        and "case CONTENTS_INPUT -> contentsInputFluidView;" in code
    )


def cache_invalidation_ok(be_source):
    """⑤ 内层缓存按 ItemStack 身份失效，且唯一写入口清缓存。

    第 108 轮的口径调整（用户第 108 条「只显示流体、物品不显示」）：
      * 身份缓存还在（同一个 stack 且不是退化态 ⇒ 直接复用），但早退条件里多了
        `!needsReprobe(now)` —— 判据因此钉「身份 + 重试门」，不是裸的身份比较；
      * 清缓存的三行收进了 `clearInnerCache()`（第 108 轮还多清了格数 / 罐数 / 失败标志 /
        重试时刻），所以判据改成「三行都在 clearInnerCache 体内 + 唯一写入口调用它」。
    判据强度不变：仍然要求「按 stack 身份失效」+「唯一写入口真的清掉内层引用」。
    """
    code = strip_comments_and_strings(be_source)
    has_clear = ("private void clearInnerCache()" in code
                 and "this.innerFor = null;" in code
                 and "this.innerItem = null;" in code
                 and "this.innerFluid = null;" in code)
    return (
        "if (innerFor == proxyStack && !needsReprobe(now)) {" in code
        and has_clear
        and "this.proxyStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);" in code
        and "setProxyStack" in code and "clearInnerCache();" in code
    )


def disk_adapter_ok(caps_source):
    """⑥ 磁盘适配器必须是 registerItem + 遍历 StorageContainerItem + 线程门禁 + overworld 仓库。"""
    code = strip_comments_and_strings(caps_source)
    return (
        "event.registerItem(Capabilities.ItemHandler.ITEM," in code
        and "item instanceof StorageContainerItem" in code
        and "ServerLifecycleHooks.getCurrentServer()" in code
        and "server.isSameThread()" in code
        and "server.getLevel(Level.OVERWORLD)" in code
        and "RefinedStorageApi.INSTANCE.getStorageRepository(overworld)" in code
        and "registerBlockEntity" not in code
        and "event.isItemRegistered(Capabilities.ItemHandler.ITEM, item)" in code
    )


def stable_outer_views_ok(be_source, handlers_source):
    """⑤ 外层视图必须是 final 字段（稳定实例），不能每次查询 new。

    第 96 轮新增主/备两套内容物视图后，这里一并要求它们也是 final 稳定实例 ——
    外部（RS 的 BlockCapabilityCache）缓存的是 handler 实例本身，视图不稳定会让
    「换面 / 换代理对象」之后外部仍读旧视图。
    """
    be_code = strip_comments_and_strings(be_source)
    handler_code = strip_comments_and_strings(handlers_source)
    return (
        "private final InventoryProxyHandlers.ObjectView objectView;" in be_code
        and "private final InventoryProxyHandlers.ContentsItemView contentsItemView;" in be_code
        and "private final InventoryProxyHandlers.ContentsFluidView contentsFluidView;" in be_code
        and "private final InventoryProxyHandlers.ContentsInputItemView contentsInputItemView;" in be_code
        and "private final InventoryProxyHandlers.ContentsInputFluidView contentsInputFluidView;" in be_code
        and "this.objectView = new InventoryProxyHandlers.ObjectView(this);" in be_code
    ) and "proxy.innerItem()" in handler_code and "proxy.innerFluid()" in handler_code


def face_tip_ok(zh, en, screen_source):
    """⑦ 界面说明的承载方式：常驻红字已删，改由「悬停六面按钮」的 tooltip 提供。

    口径沿革：第 95 轮把长文压成一行（用户「这么长一坨」）；第 96 轮语义改成「内容物分主/备两档」；
    第 97 轮用户第 3 条「字太小了，而且（就算）修复的话，也不喜欢那行字了」—— 那条说明被整行等比
    缩小到约 0.4 倍（一撮蚂蚁字），他要的是**不要这行字**。因此现在钉：
      · 旧键 `inventory_proxy.warning` 必须在 zh / en **两边都已删除**（留着不画就是孤儿键，
        「已删干净」也无从验证）；
      · 4 条 `face_tip.*`（键名 = `ProxyFaceRole#id()`）中英齐全、非空、是短句（zh ≤60 / en ≤120）、
        zh 不得是纯 ASCII、en 不得含中日韩字符；
      · 界面源码不再引用 warning 键 / 不再有 renderWarning，必须按角色 id 取 face_tip 并用
        renderTooltip 画出来（措辞与几何归 Lead，这里只钉「信息有承载点」）。
    反例仍能判负：把 warning 键加回去 / 删掉一条 face_tip / 把 renderWarning 写回源码 ⇒ 判负。
    """
    code_only = strip_comments_and_strings(screen_source)
    old_key = "gui.rs_create_compat.inventory_proxy.warning"
    if old_key in zh or old_key in en:
        return False
    if "renderWarning" in code_only or 'LANG + "warning"' in screen_source:
        return False
    if 'LANG + "face_tip."' not in screen_source:
        return False
    if "renderTooltip" not in code_only:
        return False
    for role in ("disabled", "proxy_object", "contents", "contents_input"):
        key = "gui.rs_create_compat.inventory_proxy.face_tip." + role
        zh_tip = str(zh.get(key, ""))
        en_tip = str(en.get(key, ""))
        if not zh_tip.strip() or not en_tip.strip():
            return False
        if len(zh_tip) > 60 or len(en_tip) > 120:
            return False
        if zh_tip.isascii():
            return False
        if any("\u4e00" <= char <= "\u9fff" for char in en_tip):
            return False
    return True


# ============================================================ ① 资源齐全
def check_resources():
    main = code_of("RS_Create_Compat.java")
    for marker in (
        'BLOCKS.register("%s"' % IDENT,
        'ITEMS.register("%s"' % IDENT,
        "INVENTORY_PROXY_BLOCK_ENTITY",
        "INVENTORY_PROXY_MENU",
        "BlockEntityType.Builder.of(InventoryProxyBlockEntity::new",
    ):
        check("①a 注册：%s" % marker, marker in main, "主类里找不到")
    check("①b 方块物品进了创造标签", "INVENTORY_PROXY_ITEM.get()" in main)

    blockstate = read_json(os.path.join(ASSETS, "blockstates", IDENT + ".json"))
    check("①c blockstate 恰好 1 个变体（无 facing/active 属性）",
          set(blockstate.get("variants", {}).keys()) == {""},
          "实际 %s" % sorted(blockstate.get("variants", {}).keys()))

    model = read_json(os.path.join(ASSETS, "models", "block", IDENT + ".json"))
    check("①d 方块模型 = cube_column（side + top/end）",
          model.get("parent") == "minecraft:block/cube_column"
          and model.get("textures", {}).get("side") == "rs_create_compat:block/inventory_proxy_side"
          and model.get("textures", {}).get("end") == "rs_create_compat:block/inventory_proxy_top",
          str(model.get("parent")))
    item_model = read_json(os.path.join(ASSETS, "models", "item", IDENT + ".json"))
    check("①e 物品模型 parent 指向方块模型",
          item_model.get("parent") == "rs_create_compat:block/" + IDENT)

    for suffix in ("side", "top"):
        path = os.path.join(ASSETS, "textures", "block", "%s_%s.png" % (IDENT, suffix))
        size = png_size(path) if os.path.isfile(path) else None
        check("①f 贴图 %s_%s.png 存在且是 16x16" % (IDENT, suffix), size == (16, 16), str(size))
    gui = os.path.join(ASSETS, "textures", "gui", IDENT + ".png")
    check("①g 界面背景贴图存在", os.path.isfile(gui) and png_size(gui) is not None, gui)

    loot = read_json(os.path.join(DATA, "loot_table", "blocks", IDENT + ".json"))
    names = [entry.get("name") for pool in loot.get("pools", []) for entry in pool.get("entries", [])]
    check("①h 掉落表掉自己", names == ["rs_create_compat:" + IDENT], str(names))

    recipe_path = os.path.join(DATA, "recipe", IDENT + ".json")
    recipe = read_json(recipe_path) if os.path.isfile(recipe_path) else {}
    check("①i 有合成配方且产物是自己",
          recipe.get("type") == "minecraft:crafting_shaped"
          and recipe.get("result", {}).get("id") == "rs_create_compat:" + IDENT,
          str(recipe.get("result")))
    ingredients = sorted(entry.get("item", "")
                         for entry in recipe.get("key", {}).values())
    check("①j 配方原料都是既有配方验证过的 id（RS 原版件 + 原版箱/漏斗）",
          ingredients == sorted([
              "refinedstorage:quartz_enriched_iron", "refinedstorage:construction_core",
              "minecraft:shulker_box", "refinedstorage:machine_casing", "minecraft:hopper"]),
          str(ingredients))

    zh = read_json(os.path.join(ASSETS, "lang", "zh_cn.json"))
    en = read_json(os.path.join(ASSETS, "lang", "en_us.json"))
    keys = [
        "block.rs_create_compat.inventory_proxy",
        # 第 95 轮：用户要求去掉常显长提示 ⇒ `inventory_proxy.help` 已从语言文件删除（不再有引用点，
        # 留着就是孤儿键）；①o 反过来断言主类里不再引用它。
        # 第 97 轮：常驻红字整条删除 ⇒ `inventory_proxy.warning` 也从中英两边删掉（见 ⑦a）；
        # 信息改挂在 4 条 `face_tip.*` 上，所以这里查的是这 4 条。
        "gui.rs_create_compat.inventory_proxy.face_tip.disabled",
        "gui.rs_create_compat.inventory_proxy.face_tip.proxy_object",
        "gui.rs_create_compat.inventory_proxy.face_tip.contents",
        "gui.rs_create_compat.inventory_proxy.face_tip.contents_input",
        "gui.rs_create_compat.inventory_proxy.face_row",
        "gui.rs_create_compat.inventory_proxy.slot",
        "gui.rs_create_compat.inventory_proxy.status.empty",
        "gui.rs_create_compat.inventory_proxy.status.ready",
        "gui.rs_create_compat.inventory_proxy.status.no_contents",
        "message.rs_create_compat.inventory_proxy.face_set",
    ]
    keys += ["gui.rs_create_compat.inventory_proxy.face." + role for role in ("proxy_object", "contents", "disabled")]
    keys += ["gui.rs_create_compat.inventory_proxy.direction." + d
             for d in ("down", "up", "north", "south", "west", "east")]
    missing_zh = [k for k in keys if k not in zh]
    missing_en = [k for k in keys if k not in en]
    check("①k 语言键 zh_cn 齐全", not missing_zh, str(missing_zh[:4]))
    check("①l 语言键 en_us 齐全", not missing_en, str(missing_en[:4]))
    empty = [k for k in keys if not str(zh.get(k, "")).strip() or not str(en.get(k, "")).strip()]
    check("①m 语言键都非空", not empty, str(empty[:4]))
    ascii_zh = [k for k in keys if str(zh.get(k, "")).isascii()]
    check("①n zh_cn 侧确实是中文（不是英文占位）", not ascii_zh, str(ascii_zh[:4]))
    check("①o 主类仍用 RsccHelpBlockItem，但**不再**给它传 help 常显长文"
          "（第 95 轮用户实机反馈：「这个提示还是这么长一坨……你就把它去除不行吗」；"
          "BlockItem 改成传 null，语言键保留给文档/旧存档）",
          "RsccHelpBlockItem" in main
          and 'Component.translatable("block.rs_create_compat.inventory_proxy.help")' not in main)


# ============================================================ ② 面配置 NBT + 默认值
def check_face_config():
    role = code_of("block/%s.java" % ROLE)
    code = strip_comments_and_strings(role)
    check("②a 四档角色齐全（代理对象 / 主内容物 / 备内容物·仅进 / 禁用；"
          "第 96 轮把内容物拆成主备两档以避免同一库存被 RS 数两遍）",
          all(('PROXY_OBJECT("proxy_object")' in role,
               'CONTENTS("contents")' in role,
               'CONTENTS_INPUT("contents_input")' in role,
               'DISABLED("disabled")' in role)))
    check("②b 默认值：NORTH=内容物、SOUTH=代理对象，其余先置禁用",
          "map.put(Direction.NORTH, CONTENTS);" in code
          and "map.put(Direction.SOUTH, PROXY_OBJECT);" in code
          and "map.put(direction, DISABLED);" in code)
    check("②c 未知 NBT 值退回禁用（不崩存档）",
          "return DISABLED;" in code and "public static ProxyFaceRole byId(" in code)

    be = code_of("block/entity/%s.java" % BE)
    be_code = strip_comments_and_strings(be)
    check("②d 方块实体按方向序号持有六面数组，构造时套默认值",
          "private final ProxyFaceRole[] faces = new ProxyFaceRole[6];" in be_code
          and "faces[direction.ordinal()] = defaults.get(direction);" in be_code)
    check("②e 保存：六面逐面写字符串（Faces 复合标签）",
          "facesTag.putString(ProxyFaceRole.nbtKey(direction), faces[direction.ordinal()].id());" in be_code
          and "tag.put(TAG_FACES, facesTag);" in be_code)
    check("②f 读取：六面逐面读字符串，缺失才用默认值",
          "facesTag.contains(key)" in be_code
          and "ProxyFaceRole.byId(facesTag.getString(key))" in be_code
          and "faces[direction.ordinal()] = " in be_code)
    check("②g 保存 / 读取都遍历 Direction.values()（六面一个不漏）",
          be_code.count("for (final Direction direction : Direction.values())") >= 2)
    check("②h 代理对象 stack 单独存 Proxy 键（含组件，随 BE NBT 保存）",
          "tag.put(TAG_PROXY, proxyStack.save(registries));" in be_code
          and "ItemStack.parseOptional(registries, tag.getCompound(TAG_PROXY))" in be_code)

    block = code_of("block/%s.java" % BLOCK)
    wrench = code_of("support/RsccInventoryProxyWrench.java")
    wrench_code = strip_comments_and_strings(wrench)
    check("②i 扳手右键：事件挂点上按命中面循环角色 + 服务端权威 + 不碰方块 useItemOn",
          "Tags.Items.TOOLS_WRENCH" in wrench_code
          and "event.getFace()" in wrench_code
          and "proxy.cycleFace(face)" in wrench_code
          and "if (!level.isClientSide()" in wrench_code
          and "useItemOn" not in strip_comments_and_strings(block))
    check("②i2 方块类里没有任何扳手 / 潜行 / 手持物判定（与既有 selfcheck_round53 同一条硬规则）",
          "TOOLS_WRENCH" not in block
          and "isCrouching" not in block
          and "getItemInHand" not in block)
    check("②j 界面也能设：clickMenuButton → cycleFace",
          "proxy.cycleFace(directionOf(id))" in strip_comments_and_strings(code_of("menu/%s.java" % MENU)))


# ============================================================ ③ 不做网络节点
def check_no_network_node():
    rels = [
        "block/%s.java" % BLOCK,
        "block/entity/%s.java" % BE,
        "block/%s.java" % ROLE,
        "support/InventoryProxyHandlers.java",
        "support/RsccInventoryProxyWrench.java",
        "menu/%s.java" % MENU,
        "support/%s.java" % DISK_CAPS,
        "storage/%s.java" % DISK_HANDLER,
        "storage/RsccDiskSlotCache.java",
    ]
    for rel in rels:
        text = code_of(rel)
        check("③a %s 不是网络节点（源码级）" % rel, bool(text) and no_network_node_ok(text))

    be = code_of("block/entity/%s.java" % BE)
    check("③b 只注册方块物品/流体能力，不注册网络节点容器能力",
          "Capabilities.ItemHandler.BLOCK" in be
          and "Capabilities.FluidHandler.BLOCK" in be
          and "refinedstorage" in be)
    main = code_of("RS_Create_Compat.java")
    check("③c 主类挂的是本类的 registerCapabilities（没有网络节点监听器）",
          "modEventBus.addListener(InventoryProxyBlockEntity::registerCapabilities);" in main
          and "InventoryProxyBlockEntity::registerCapabilities" in main)
    # 反向：主类里本方块相关行不能出现网络节点能力
    proxy_lines = [line for line in main.splitlines() if "INVENTORY_PROXY" in line or "InventoryProxy" in line]
    check("③d 主类中与本方块相关的行没有网络节点能力字样",
          not any("NetworkNodeContainerProviderCapability" in line for line in proxy_lines),
          str([line.strip() for line in proxy_lines if "NetworkNode" in line][:2]))


# ============================================================ ④ 内容物面按配置
def check_face_mapping():
    be = code_of("block/entity/%s.java" % BE)
    handlers = code_of("support/InventoryProxyHandlers.java")
    check("④a 面 → 角色：null 面 = 内容物，其余读六面配置", face_config_driven_ok(be))
    check("④b 对象面只有 1 槽、只收可代理物品",
          "public int getSlots() {\n            return 1;" in handlers
          and "InventoryProxyBlockEntity.isProxyable(stack)" in handlers)
    check("④c 内容物物品视图委托内层（空库存时 getSlots()=0，不是 null）",
          "return handler == null ? 0 : handler.getSlots();" in handlers
          and "handler.insertItem(slot, stack, simulate)" in handlers
          and "handler.extractItem(slot, amount, simulate)" in handlers)
    check("④d 内容物流体视图委托内层（getTanks / fill / drain 都转发）",
          all(marker in handlers for marker in (
              "handler.getTanks()", "handler.fill(resource, action)",
              "handler.drain(resource, action)", "handler.drain(maxDrain, action)")))
    check("④e 对象面不暴露流体（只有内容物面返回流体视图；第 96 轮起主/备各一套）",
          "case CONTENTS -> contentsFluidView;" in be
          and "case CONTENTS_INPUT -> contentsInputFluidView;" in be
          and "default -> null;" in be)
    check("④f 流体/物品能力都注册为 BLOCK 能力（按面查询）",
          "Capabilities.FluidHandler.BLOCK" in be and "Capabilities.ItemHandler.BLOCK" in be)
    check("④g 可代理判据 = 物品能力 / 流体能力 / StorageContainerItem（不写死 id）",
          "stack.getCapability(Capabilities.ItemHandler.ITEM) != null" in be
          and "stack.getCapability(Capabilities.FluidHandler.ITEM) != null" in be
          and "stack.getItem() instanceof StorageContainerItem" in be)
    check("④h 换面必须作废能力缓存（面可能从 null 变成有）",
          "invalidateCapabilities();" in be)


# ============================================================ ⑤ 缓存失效
def check_cache():
    be = code_of("block/entity/%s.java" % BE)
    handlers = code_of("support/InventoryProxyHandlers.java")
    check("⑤a 内层按 ItemStack 身份失效 + 唯一写入口清缓存", cache_invalidation_ok(be))
    check("⑤b 外层三个视图是 final 稳定实例（外部缓存不会悬空）",
          stable_outer_views_ok(be, handlers))
    check("⑤c 视图每次调用都重新问内层（不缓存内层引用）",
          handlers.count("proxy.innerItem()") >= 1
          and handlers.count("proxy.innerFluid()") >= 1
          and handlers.count("final IItemHandler handler = inner();") >= 6
          and handlers.count("final IFluidHandler handler = inner();") >= 7)
    check("⑤d 读档 / onLoad 后一律重解析内层",
          "innerFor = null;" in be and "public void onLoad()" in be)
    check("⑤e 存入用 copyWithCount(1)（防调用方入参别名）",
          "stack.copyWithCount(1)" in be)
    check("⑤f 拆方块时把代理对象弹出（不吞内容）",
          "takeProxyStackForDrop()" in be
          and "popResource(level, pos, dropped)" in code_of("block/%s.java" % BLOCK))


# ============================================================ ⑥ 磁盘适配器
def check_disk_adapter():
    caps = code_of("support/%s.java" % DISK_CAPS)
    handler = code_of("storage/%s.java" % DISK_HANDLER)
    cache = code_of("storage/RsccDiskSlotCache.java")
    check("⑥a registerItem + 遍历 StorageContainerItem + isSameThread 门禁 + 跳过已有 provider",
          disk_adapter_ok(caps))
    check("⑥b provider 拿真实 ItemStack，经 resolve(repo, stack) 取这一张盘的存储",
          "disk.resolve(repository, stack)" in caps
          and "instanceof StorageContainerItem disk" in caps)
    check("⑥c 未格式化 / UUID 不在仓库时返回 null（不是空 handler）",
          "if (storage == null)" in caps and "return null;" in caps)
    check("⑥d 固定 N 槽资源种类窗口（N=64，槽数恒定）",
          "public static final int WINDOW_SLOTS = 64;" in cache
          and "return RsccDiskSlotCache.WINDOW_SLOTS;" in handler)
    check("⑥e 粘性分配：保留仍在的资源 / 释放消失的 / 新资源补最小空槽",
          "alive.contains(next[slot])" in cache
          and "indexOf(next, null)" in cache
          and "BINDINGS.put(storage, next);" in cache)
    check("⑥f getStackInSlot 返回真实总量（允许超堆叠），extractItem 每次 ≤ 64",
          "toItemStack(Math.min(amount, Integer.MAX_VALUE))" in handler
          and "Math.min(amount, perOperation)" in handler
          and "MAX_EXTRACT = 64" in handler)
    check("⑥g 只暴露 ItemResource（过滤流体 / 气体，防 toItemStack 崩）",
          "amount.resource() instanceof ItemResource resource" in handler
          and "instanceof ItemResource resource" in handler)
    check("⑥h 插入裁剪交给存储自己（用 insert 返回值），Actor 非 null",
          "storage.insert(resource, stack.getCount()" in handler
          and "Actor.EMPTY" in handler)
    check("⑥i 自己的写立即失效快照（invalidateSnapshot）",
          "invalidateSnapshot();" in handler and "SNAPSHOT_TTL_MS" in handler)
    check("⑥j 停服清空粘性槽位表",
          "ServerStoppedEvent" in caps and "RsccDiskSlotCache.clear()" in caps)


# ============================================================ ⑦ 界面说明（face_tip tooltip）
def check_warning():
    zh = read_json(os.path.join(ASSETS, "lang", "zh_cn.json"))
    en = read_json(os.path.join(ASSETS, "lang", "en_us.json"))
    be = code_of("block/entity/%s.java" % BE)
    screen = code_of("client/screen/%s.java" % SCREEN)
    check("⑦a 常驻红字已删除（无 renderWarning / 无 warning 键），说明改由悬停六面按钮的 face_tip tooltip 承载",
          face_tip_ok(zh, en, screen))
    # 第 97 轮：4 条 tooltip 文案本身也不许变成「长文墙」—— 它们只在悬停时出现，长一点可以接受，
    # 但仍要短句（zh ≤ 60 / en ≤ 120，见 face_tip_ok），且旧长文的两句现场描述不得回到界面语言值里
    # （它们留在方块实体注释里解释「为什么主内容物面只能有一个」）。
    tips_zh = [str(zh.get("gui.rs_create_compat.inventory_proxy.face_tip." + role, ""))
               for role in ("disabled", "proxy_object", "contents", "contents_input")]
    check("⑦b 4 条面提示都是短句、且不含旧长文两句现场描述（具体措辞由 Lead 决定）",
          all(tip.strip() and len(tip) <= 60 for tip in tips_zh)
          and all("RS 2.0.9 不按源方块去重" not in tip and "取走 1 个掉落 2 个" not in tip
                  for tip in tips_zh))
    check("⑦c 默认只开一个主内容物面 + 一个对象面（新方块连上 RS 不会一放下去就双计）",
          "map.put(Direction.NORTH, CONTENTS);" in strip_comments_and_strings(code_of("block/%s.java" % ROLE))
          and "map.put(Direction.SOUTH, PROXY_OBJECT);" in strip_comments_and_strings(code_of("block/%s.java" % ROLE)))


# ============================================================ ⑨ 「吞代理对象」：任何路径都不许静默丢东西
# 根因（本轮修复）：代理槽视图原先只 `implements IItemHandler`，而菜单用 `SlotItemHandler`。
#   NeoForge 21.1.248 的 `SlotItemHandler#set` 是**无条件强制转型**：
#       ((IItemHandlerModifiable) this.getItemHandler()).setStackInSlot(index, stack);
#   ⇒ 玩家每次「放进 / 取出」都在 set 处抛 ClassCastException；而原版 `Slot#safeInsert` /
#   `Slot#tryRemove` 都是**先把 stack 从玩家那边扣掉**（split / extractItem）才调用 set，
#   于是背包从玩家手里消失、又没进代理槽 —— 字面意义上的「吞掉」。
# 本节断言：① 该视图必须可写（否则 GUI 写入路径整条崩）；② 任何写路径都不许「先清空再写」/
#   无条件 clear；③ 换面 / 读档 / 拆除 必须保留或弹出（源码级判据 + 可复跑模型 + 反例自证）。


def _block_at(text, open_index):
    """取出 text[open_index] 处 '{' 对应的整段花括号内容（支持嵌套）。"""
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
    """取出 `class <simple_name> ... { ... }` 的类体（source 必须是已剥注释/字符串的代码）。"""
    match = re.search(r"\bclass\s+%s\b[^{]*\{" % re.escape(simple_name), source)
    if not match:
        return ""
    return _block_at(source, source.find("{", match.start()))


def method_body(source, signature):
    """取出以 `signature` 开头的方法体（source 必须是已剥注释/字符串的代码）。"""
    start = source.find(signature)
    if start < 0:
        return ""
    opening = source.find("{", start)
    if opening < 0:
        return ""
    return _block_at(source, opening)


CLEAR_PATTERN = re.compile(
    r"setProxyStack\(ItemStack\.EMPTY\)|clearContent\(\)|proxyStack\s*=\s*ItemStack\.EMPTY")


def blind_clear_evidence(method_text):
    """返回「未先取出就清空」的位置（相对方法体的行号）。

    「清空」只有两种写法是允许的：
      * 清空 + 立刻 return（这是一个终止分支，后面没有「再写新值」）；
      * 清空之前，旧值已经被交给局部变量 / 调用方（takeProxyStackForDrop / copyWithCount / extractItem）。
    其余一律算「先清空再写」或「无条件清空」—— 那正是会把旧对象吞掉的写法。
    """
    hits = []
    for match in CLEAR_PATTERN.finditer(method_text):
        tail = method_text[match.end():match.end() + 160]
        if re.match(r"\s*;\s*\}?\s*return\b", tail, re.S):
            continue
        head = method_text[:match.start()]
        if ("takeProxyStackForDrop()" in head
                or re.search(r"final ItemStack \w+ = proxy\.proxyStack\(\)", head)
                or re.search(r"final ItemStack \w+ = proxyStack;", head)
                or "extractItem(" in head):
            continue
        hits.append(method_text[:match.start()].count("\n") + 1)
    return hits


def proxy_slot_writable_ok(handlers_source, menu_source):
    """⑨a 代理槽视图必须是 IItemHandlerModifiable（SlotItemHandler#set 强制转型），且菜单用会打脏标记的槽。"""
    handlers = strip_comments_and_strings(handlers_source)
    menu = strip_comments_and_strings(menu_source)
    view = class_body(handlers, "ObjectView")
    return (
        "public static final class ObjectView implements IItemHandlerModifiable" in handlers
        and "public void setStackInSlot(final int slot, final ItemStack stack)" in view
        and "proxy.setProxyStack(stack.copyWithCount(1));" in view
        # 超过单槽容量的部分必须交给世界，不能就地截断
        and "proxy.ejectStack(stack.copyWithCount(stack.getCount() - 1));" in view
        # 菜单槽位必须是「setChanged 打到 BE」的子类（否则原地 shrink/grow 不落盘）
        and "class ProxySlot extends SlotItemHandler" in menu
        and "new ProxySlot(objectView, 0," in menu
        and "proxy.setChanged();" in menu
    )


def no_clear_then_write_ok(handlers_source, be_source):
    """⑨b 所有写路径都不许「先清空再写」/ clearContent。

    逐个方法检查：ObjectView 的 insertItem / extractItem / setStackInSlot，
    方块实体的 setProxyStack / takeProxyStackForDrop / loadAdditional / onLoad。
    """
    handlers = strip_comments_and_strings(handlers_source)
    be = strip_comments_and_strings(be_source)
    if "clearContent(" in handlers or "clearContent(" in be:
        return False
    view = class_body(handlers, "ObjectView")
    for signature in ("public ItemStack insertItem(",
                      "public ItemStack extractItem(",
                      "public void setStackInSlot("):
        body = method_body(view, signature)
        if not body or blind_clear_evidence(body):
            return False
    for signature in ("public void setProxyStack(",
                      "public ItemStack takeProxyStackForDrop(",
                      "protected void loadAdditional(",
                      "public void onLoad("):
        body = method_body(be, signature)
        if not body or blind_clear_evidence(body):
            return False
    return True


def insert_never_overwrites_ok(handlers_source):
    """⑨c insertItem：已占用 ⇒ 原样退回，绝不覆盖已有对象。"""
    view = class_body(strip_comments_and_strings(handlers_source), "ObjectView")
    body = method_body(view, "public ItemStack insertItem(")
    if not body:
        return False
    guard = body.find("if (!proxy.proxyStack().isEmpty())")
    write = body.find("proxy.setProxyStack(stack.copyWithCount(1))")
    if guard < 0 or write < 0 or guard > write:
        return False
    guard_block = _block_at(body, body.find("{", guard))
    return "return stack;" in guard_block


def take_first_ok(handlers_source, be_source):
    """⑨d 取物 / 拆除是「先取后清」：先拿到返回值再清空。"""
    view = class_body(strip_comments_and_strings(handlers_source), "ObjectView")
    extract = method_body(view, "public ItemStack extractItem(")
    be = strip_comments_and_strings(be_source)
    drop = method_body(be, "public ItemStack takeProxyStackForDrop(")
    if not extract or not drop:
        return False
    extracted = extract.find("proxy.proxyStack().copyWithCount(1)")
    cleared = extract.find("proxy.setProxyStack(ItemStack.EMPTY)")
    if extracted < 0 or cleared < 0 or extracted > cleared:
        return False
    captured = drop.find("final ItemStack out = proxyStack;")
    dropped = drop.find("this.proxyStack = ItemStack.EMPTY;")
    return 0 <= captured < dropped


def break_keeps_or_drops_ok(block_source):
    """⑨e 拆方块：先取出代理对象再弹出，且只在「真的换成别的方块」+ 服务端执行。"""
    code = strip_comments_and_strings(block_source)
    body = method_body(code, "public void onRemove(")
    if not body or blind_clear_evidence(body):
        return False
    return ("takeProxyStackForDrop()" in body
            and "popResource(level, pos, dropped)" in body
            and "!state.is(newState.getBlock())" in body
            and "!level.isClientSide()" in body)


def load_keeps_or_ejects_ok(be_source):
    """⑨f 读档不静默覆盖内存里已有的对象：先取旧值、换掉前先弹出；没有世界时入队并落盘。"""
    be = strip_comments_and_strings(be_source)
    load = method_body(be, "protected void loadAdditional(")
    save = method_body(be, "protected void saveAdditional(")
    if not load or not save:
        return False
    captured = load.find("final ItemStack previous = proxyStack;")
    replaced = load.find("proxyStack = tag.contains(TAG_PROXY)")
    return (
        captured >= 0 and replaced >= 0 and captured < replaced
        and "ejectStack(previous);" in load
        and "TAG_PENDING_EJECT" in save
        and "public void ejectStack(final ItemStack stack)" in be
        and "private void flushPendingEject()" in be
        and "public void setLevel(final Level level)" in be
        and "flushPendingEject();" in method_body(be, "public void onLoad(")
    )


def face_config_touches_slot(be_source):
    """⑨g 换面只改 faces[]，绝不碰代理槽（返回 True = 有误伤）。"""
    be = strip_comments_and_strings(be_source)
    for signature in ("public void setFace(",
                      "public ProxyFaceRole cycleFace(",
                      "private void invalidateFaceCapabilities("):
        body = method_body(be, signature)
        if body and re.search(r"proxyStack|setProxyStack|takeProxyStackForDrop|ejectStack", body):
            return True
    return False


class ProxySlotModel:
    """可复跑模型：按 `ObjectView` / 原版 `Slot` / 拆除 / 读档 / 换面 的真实语义搬运 token。

    一个 token = 一个「背包」。恒等式：每个被创造的 token 在任何时刻都**恰好出现一次**
    （槽 / 光标 / 玩家背包 / 漏斗退回 / 世界掉落 / 待弹出队列）—— 少了是「吞」，多了是「复制」。

    mode="fixed"：修复后的语义；mode="buggy"：修复前的三处坏写法（set 抛异常时物品半路消失、
    insert 先清空再写、拆方块无条件清空），用来证明这套判据有判别力。
    """

    FIXED = "fixed"
    BUGGY = "buggy"
    NEXT_ROLE = {"contents": "proxy_object", "proxy_object": "disabled", "disabled": "contents"}

    def __init__(self, mode):
        self.mode = mode
        self.slot = None
        self.cursor = None
        self.player = []
        self.hopper_returned = []
        self.world = []
        self.pending = []
        self.faces = ["contents"] * 6
        self.saved = None
        self.lost = []

    # ---------- 通道 ----------
    def born(self, token):
        self.player.append(token)
        return token

    def pick_up(self, token):
        if token in self.player:
            self.player.remove(token)
        self.cursor = token

    def stash(self):
        if self.cursor is not None:
            self.player.append(self.cursor)
            self.cursor = None

    def click_place(self):
        """光标 → 空槽（原版 safeInsert → setByPlayer → setStackInSlot）。"""
        if self.cursor is None:
            return
        if self.mode == self.BUGGY:
            # split() 已经从光标扣掉 1 个，然后 set() 转型失败 ⇒ 物品既不在光标也不在槽
            self.lost.append(("click_place", self.cursor))
            self.cursor = None
            return
        if self.slot is not None:
            return
        self.slot, self.cursor = self.cursor, None

    def click_take(self):
        """槽 → 光标（原版 tryRemove：extract 成功后还要 setByPlayer(EMPTY) 收尾）。"""
        if self.slot is None:
            return
        if self.mode == self.BUGGY:
            # extract 已经把对象取出来了，随后 setByPlayer(EMPTY) 抛异常 ⇒ 光标没拿到 ⇒ 丢
            self.lost.append(("click_take", self.slot))
            self.slot = None
            return
        self.cursor, self.slot = self.slot, None

    def insert_hopper(self, token):
        """漏斗 / 管道走 IItemHandler#insertItem（没有 Slot，不经过 set）。"""
        if token in self.player:
            self.player.remove(token)
        if self.slot is not None:
            if self.mode == self.BUGGY:
                # 反例：先清空再写 ⇒ 旧对象被吞
                self.lost.append(("insert_overwrite", self.slot))
                self.slot = None
            self.hopper_returned.append(token)
            return
        self.slot = token

    def cycle_faces(self, times):
        for _ in range(times):
            self.faces = [self.NEXT_ROLE[role] for role in self.faces]

    def save(self):
        self.saved = {"slot": self.slot, "faces": list(self.faces), "pending": list(self.pending)}

    def load(self):
        data = self.saved or {"slot": None, "faces": ["contents"] * 6, "pending": []}
        previous = self.slot
        self.slot = data["slot"]
        self.faces = list(data["faces"])
        self.pending = list(data.get("pending", []))
        if previous is not None and previous != self.slot:
            if self.mode == self.BUGGY:
                self.lost.append(("load_overwrite", previous))
            else:
                self.world.append(previous)

    def break_block(self):
        if self.slot is None:
            return
        if self.mode == self.BUGGY:
            self.lost.append(("break_clear", self.slot))
            self.slot = None
            return
        self.world.append(self.slot)
        self.slot = None

    # ---------- 判据 ----------
    def copies_of(self, token):
        total = 1 if self.slot == token else 0
        total += 1 if self.cursor == token else 0
        total += self.player.count(token)
        total += self.hopper_returned.count(token)
        total += self.world.count(token)
        total += self.pending.count(token)
        return total

    def violations(self, created):
        bad = []
        for token in created:
            count = self.copies_of(token)
            if count != 1:
                bad.append("%s 出现 %d 次（%s）" % (token, count, "吞掉" if count == 0 else "复制"))
        if self.lost and not bad:
            bad.append("有 %d 处静默丢失但恒等式没抓到" % len(self.lost))
        return bad


def model_violations(mode):
    """跑一条覆盖全部写路径的固定序列，返回恒等式违反列表（fixed 必须为空）。"""
    model = ProxySlotModel(mode)
    created = [model.born("b1")]
    # 1) 放入 → 取出 → 再放入
    model.pick_up("b1")
    model.click_place()
    model.click_take()
    model.stash()
    model.pick_up("b1")
    model.click_place()
    # 2) 槽已占用时漏斗再送一个 ⇒ 必须原样退回，b1 留在槽里
    created.append(model.born("b2"))
    model.insert_hopper("b2")
    # 3) 六面各循环一圈（不许碰槽内容）
    model.cycle_faces(6)
    # 4) 存档 / 读档
    model.save()
    model.load()
    # 5) 拆方块 ⇒ 必须弹出
    model.break_block()
    # 6) 空槽放 b3，然后读一份「槽为空」的旧档 ⇒ 内存里那个必须先弹出
    created.append(model.born("b3"))
    model.pick_up("b3")
    model.click_place()
    model.saved = {"slot": None, "faces": list(model.faces), "pending": []}
    model.load()
    return model.violations(created)


def check_no_swallow_paths():
    be = code_of("block/entity/%s.java" % BE)
    handlers = code_of("support/InventoryProxyHandlers.java")
    menu = code_of("menu/%s.java" % MENU)
    block = code_of("block/%s.java" % BLOCK)

    check("⑨a 代理槽视图可写（ObjectView implements IItemHandlerModifiable + setStackInSlot + 菜单槽 setChanged 打到 BE）",
          proxy_slot_writable_ok(handlers, menu))
    check("⑨b 所有写路径都不「先清空再写」、全工程没有 clearContent()",
          no_clear_then_write_ok(handlers, be))
    check("⑨c insertItem：已占用 ⇒ 原样退回，绝不覆盖槽里已有的对象",
          insert_never_overwrites_ok(handlers))
    check("⑨d 取物 / 拆除都是「先取后清」（extractItem / takeProxyStackForDrop）",
          take_first_ok(handlers, be))
    check("⑨e 拆方块：先取出再 popResource，且只在「真换成别的方块」+ 服务端执行",
          break_keeps_or_drops_ok(block))
    check("⑨f 读档覆盖前先 ejectStack(旧值)；没有服务端世界时入队（TAG_PENDING_EJECT）并落盘",
          load_keeps_or_ejects_ok(be))
    check("⑨g 换面 / 循环角色只改 faces[]，一行都不碰代理槽",
          not face_config_touches_slot(be))


def check_no_swallow_model():
    fixed = model_violations(ProxySlotModel.FIXED)
    buggy = model_violations(ProxySlotModel.BUGGY)
    check("⑨h 可复跑模型：放入/取出/漏斗/换面×6/存读档/拆除/读档残留 ⇒ 物品恒等式恒成立",
          not fixed, "；".join(fixed[:3]))
    check("⑨i 反例模型（set 半路抛异常 / insert 先清空再写 / 拆方块无条件清空）必须判负",
          bool(buggy), "反例也守恒 ⇒ 这套模型没有判别力")


def check_no_swallow_controls():
    be = code_of("block/entity/%s.java" % BE)
    handlers = code_of("support/InventoryProxyHandlers.java")
    menu = code_of("menu/%s.java" % MENU)
    block = code_of("block/%s.java" % BLOCK)

    # 根因反例：代理槽视图退回「只实现 IItemHandler」⇒ 菜单写入路径整条崩
    bad_view = handlers.replace(
        "public static final class ObjectView implements IItemHandlerModifiable {",
        "public static final class ObjectView implements IItemHandler {")
    check("⑨j 反例「代理槽视图只实现 IItemHandler」必须判负（本轮根因）",
          bad_view != handlers
          and proxy_slot_writable_ok(handlers, menu)
          and not proxy_slot_writable_ok(bad_view, menu))

    # 反例：setStackInSlot 先清空再写
    bad_clear_first = handlers.replace(
        "            if (stack.isEmpty()) {",
        "            proxy.setProxyStack(ItemStack.EMPTY);\n            if (stack.isEmpty()) {")
    check("⑨k 反例「setStackInSlot 先清空再写」必须判负",
          bad_clear_first != handlers
          and no_clear_then_write_ok(handlers, be)
          and not no_clear_then_write_ok(bad_clear_first, be))

    # 反例：onRemove 无条件清空
    bad_break = block.replace(
        "            final ItemStack dropped = proxy.takeProxyStackForDrop();",
        "            proxy.setProxyStack(ItemStack.EMPTY);\n"
        "            final ItemStack dropped = ItemStack.EMPTY;")
    check("⑨l 反例「onRemove 无条件 clear」必须判负",
          bad_break != block
          and break_keeps_or_drops_ok(block)
          and not break_keeps_or_drops_ok(bad_break))

    # 反例：insertItem 去掉「已占用」守卫（会覆盖旧对象）
    bad_insert = handlers.replace(
        "            if (!proxy.proxyStack().isEmpty()) {",
        "            if (false) {")
    check("⑨m 反例「insertItem 覆盖已占用槽」必须判负",
          bad_insert != handlers
          and insert_never_overwrites_ok(handlers)
          and not insert_never_overwrites_ok(bad_insert))

    # 反例：读档直接覆盖（不先取旧值）
    bad_load = be.replace(
        "        final ItemStack previous = proxyStack;",
        "        final ItemStack previous = ItemStack.EMPTY;")
    check("⑨n 反例「读档直接覆盖内存里的代理对象」必须判负",
          bad_load != be
          and load_keeps_or_ejects_ok(be)
          and not load_keeps_or_ejects_ok(bad_load))

    # 反例：换面时顺手清空代理槽
    bad_face = be.replace(
        "        faces[direction.ordinal()] = role;",
        "        faces[direction.ordinal()] = role;\n        this.proxyStack = ItemStack.EMPTY;")
    check("⑨o 反例「换面顺手清空代理槽」必须判负",
          bad_face != be
          and not face_config_touches_slot(be)
          and face_config_touches_slot(bad_face))


# ============================================================ ⑧ 反例自证
def check_negative_controls():
    """把四种坏实现注入检测函数：检测必须判负。否则这些断言只是「碰巧为真」。"""
    be = code_of("block/entity/%s.java" % BE)
    handlers = code_of("support/InventoryProxyHandlers.java")
    caps = code_of("support/%s.java" % DISK_CAPS)

    bad_node = be.replace("public class InventoryProxyBlockEntity extends BlockEntity {",
                          "public class InventoryProxyBlockEntity extends AbstractNetworkNodeContainerBlockEntity {")
    check("⑧a 反例「注册 NetworkNode」必须判负",
          no_network_node_ok(be) and not no_network_node_ok(bad_node))

    bad_face = be.replace(
        "return side == null ? ProxyFaceRole.CONTENTS : faces[side.ordinal()];",
        "return proxyStack.getItem() instanceof StorageContainerItem"
        " ? ProxyFaceRole.CONTENTS : ProxyFaceRole.DISABLED;")
    check("⑧b 反例「靠物品类型判面」必须判负",
          face_config_driven_ok(be) and not face_config_driven_ok(bad_face))

    # 反例：把「按身份失效」改成恒命中缓存（if (true)）并抽掉 clearInnerCache 的清引用。
    bad_cache = be.replace("if (innerFor == proxyStack && !needsReprobe(now)) {", "if (true) {").replace(
        "this.innerFor = null;", "")
    check("⑧c 反例「缓存不失效」必须判负",
          cache_invalidation_ok(be) and not cache_invalidation_ok(bad_cache))

    bad_thread = caps.replace("if (server == null || !server.isSameThread()) {",
                              "if (server == null) {")
    check("⑧d 反例「去掉 isSameThread 线程门禁」必须判负",
          disk_adapter_ok(caps) and not disk_adapter_ok(bad_thread))

    bad_outer = be.replace("private final InventoryProxyHandlers.ContentsItemView contentsItemView;",
                           "private InventoryProxyHandlers.ContentsItemView contentsItemView;")
    check("⑧e 反例「外层视图不是稳定实例」必须判负",
          stable_outer_views_ok(be, handlers) and not stable_outer_views_ok(bad_outer, handlers))

    # ⑦ 的面说明判据同样要做反例自证（第 97 轮：常驻红字 → 悬停 face_tip tooltip）
    zh = read_json(os.path.join(ASSETS, "lang", "zh_cn.json"))
    en = read_json(os.path.join(ASSETS, "lang", "en_us.json"))
    screen = code_of("client/screen/%s.java" % SCREEN)
    back_warning = dict(zh)
    back_warning["gui.rs_create_compat.inventory_proxy.warning"] = "旧红字回来了"
    check("⑧f 反例「把 warning 键加回去」必须判负",
          face_tip_ok(zh, en, screen) and not face_tip_ok(back_warning, en, screen))

    missing_tip = dict(zh)
    missing_tip.pop("gui.rs_create_compat.inventory_proxy.face_tip.contents", None)
    check("⑧g 反例「删掉一条 face_tip 文案」必须判负",
          face_tip_ok(zh, en, screen) and not face_tip_ok(missing_tip, en, screen))

    warning_back = screen.replace(
        "    private void renderFaceTip(",
        "    private void renderWarning() {\n    }\n\n    private void renderFaceTip(", 1)
    check("⑧h 反例「把 renderWarning 方法写回源码（常驻红字回归）」必须判负",
          face_tip_ok(zh, en, screen) and warning_back != screen
          and not face_tip_ok(zh, en, warning_back))


def main():
    print("=== 第 89 轮自检：容器代理器（inventory_proxy）+ RS 磁盘物品能力 ===")
    check_resources()
    check_face_config()
    check_no_network_node()
    check_face_mapping()
    check_cache()
    check_disk_adapter()
    check_warning()
    check_no_swallow_paths()
    check_no_swallow_model()
    check_no_swallow_controls()
    check_negative_controls()
    print("")
    print("断言总数 %d，失败 %d" % (CHECKS[0], len(FAILURES)))
    for failure in FAILURES:
        print("  FAIL %s" % failure)
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
