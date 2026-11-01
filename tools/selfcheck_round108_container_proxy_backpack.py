#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 108 轮自检：容器代理器识别「精妙背包（带储罐升级）」时「只显示流体、物品不显示」。

用户原话（第 108 条，针对 1.2.0-beta.7 实机）：
  「精妙背包还有个问题：容器代理器貌似无法正常识别精妙背包。我有一个背包被正常识别了 ——
    那个背包没有装储罐升级；另外一个背包装了储罐升级，里面存了一点经验，还有很多其它物品。
    把装了储罐升级的那个放进去，用 Jade 查看的时候它只显示有经验、只显示有流体，
    里面的物品却没有显示。」

## 定性结论（反编译证据，本脚本用「源码锚点 + 行为模型」两条腿钉住）

**Sophisticated Backpacks 3.26.3.2158 / Sophisticated Core 1.5.17 的结论：物品能力与流体能力是
两条互不相干的能力**，装储罐升级<b>不会</b>让物品那路消失：
  * 物品：`ModItems.lambda$registerCapabilities$81` =
    `wrapper.getContentsUuid().isEmpty() ? EmptyItemHandler.INSTANCE : wrapper.getInventoryForInputOutput()`
    （**每次调用都重新判 storage UUID**）；
  * 流体：`lambda$registerCapabilities$82` = `wrapper.getItemFluidHandler().orElse(null)`，
    而 `BackpackWrapper.getFluidHandler()` 是**懒建 + 缓存**（`fluidHandlerInitialized`）。

⇒ 能把「物品 0 格 + 流体 N 罐」同时造出来的只有两种形态：
  ① 物品 provider 因 storage UUID 不在物品上而返回 `EmptyItemHandler`（0 格），
     而此前已建好的流体 handler 仍可读 —— **正是用户看到的指纹**；
  ② 物品那路<b>抛异常</b>（存档 / 存储数据未就绪）。
两种形态在旧实现里都会<b>被永久粘住</b>：`resolveInner()` 里
`innerFor = proxyStack;` 写在两次 `getCapability` **之前**，异常一抛身份就记死，之后每次调用
第一行就 `return`；而 0 格被当成「解析成功的合法结果」，同样永不重试。
SB 侧还有一条会把 storage UUID 摘掉的真实路径：`UUIDDeduplicator.isFirstBackpackPreferred`
比的是 `getInventoryHandler().getSlots()`，而 `BackpackWrapper.getInventoryHandler()` 建的是
`slots - rows*getColumnsTaken()`，储罐升级 `TankUpgradeItem.getInventoryColumnsTaken()==2`
⇒ 装了储罐升级的同款背包在重复 UUID 去重里必定输掉、丢掉 storage UUID。

## 本轮钉住的不变式（只读源码 + 纯 Python 行为模型，不启动游戏）

  A. **两路能力各自独立、各自 try/catch**：一路抛异常绝不连坐另一路，且绝不因为「有流体」就
     把物品那路判成可读（不谎报）。
  B. **退化态（抛异常 / 只读到一路）按 tick 自动重试**：容器稍后恢复可读时，代理器不拆方块、
     不重放代理对象就自己恢复；两路都通之后不再重试（热路径不许退化成每 tick 重解析）。
  C. **事实必须可显示**：`InnerState` 四态（NONE/ITEM/FLUID/BOTH）+ 两路格数随数据槽下发，
     界面与 Jade 一律报事实；「只有流体」这一态单独成句并给可操作提示。
  D. **Jade 只补事实、不自己列内容物**：内容仍由 Jade 的 Universal 插件按代理器暴露的两路能力
     采集；本模组只补「认到几路 / 每路多少格」，且客户端不碰方块实体。

反例自证（每条都复用同一份判据函数，避免两处漂移）：把 A/B/C 任一条改回旧写法
（解析前写身份、不重试、有流体就当有物品、一路抛异常整体置空、状态里没有 FLUID 档）时，
对应断言必须判负。

用法：python tools/selfcheck_round108_container_proxy_backpack.py
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
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")

BE_PATH = os.path.join(JAVA, "block", "entity", "InventoryProxyBlockEntity.java")
MENU_PATH = os.path.join(JAVA, "menu", "InventoryProxyMenu.java")
SCREEN_PATH = os.path.join(JAVA, "client", "screen", "InventoryProxyScreen.java")
PLUGIN_PATH = os.path.join(JAVA, "client", "jade", "RsccJadePlugin.java")
PROVIDER_PATH = os.path.join(JAVA, "client", "jade", "RsccProxyJadeProvider.java")
PAYLOAD_PATH = os.path.join(JAVA, "support", "InventoryProxyJadePayload.java")
SERVER_DATA_PATH = os.path.join(JAVA, "support", "InventoryProxyJadeServerData.java")

FAILS = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILS.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       (" | " + detail) if (detail and not ok) else ""))


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def strip_comments(text):
    """去注释（保留字符串字面量）：判断「代码里有没有这个写法」时不被注释误导。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                elif text[j] == '"':
                    break
                else:
                    j += 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def method_body(code, signature_regex):
    m = re.search(signature_regex, code)
    if not m:
        return None
    depth = 0
    started = False
    for i in range(m.end() - 1, len(code)):
        if code[i] == "{":
            depth += 1
            started = True
        elif code[i] == "}":
            depth -= 1
            if started and depth == 0:
                return code[m.end():i]
    return None


# ============================================================================
# 一、源码锚点：A 两路各自独立 + 不谎报
# ============================================================================
print("=" * 78)
print("一、源码锚点：物品 / 流体两路能力各自独立（第 108 轮根因修复）")
print("=" * 78)

be_raw = read(BE_PATH)
be = strip_comments(be_raw)

# A1) 两次能力查询必须在**各自**的 try 块里。
#     注意：`isProxyable()` 里也有同样的两行调用（它是「能不能当代理对象」的判据），
#     所以这里必须<b>只在 resolveInner 的方法体</b>里定位，不能被前面的同名调用带偏。
rb_early = method_body(be, r"private\s+void\s+resolveInner\s*\(\s*\)\s*\{")
item_pos = (rb_early or "").find("stack.getCapability(Capabilities.ItemHandler.ITEM)")
fluid_pos = (rb_early or "").find("stack.getCapability(Capabilities.FluidHandler.ITEM)")
check("A1 物品 / 流体两路能力查询都在 resolveInner 里（两条独立的 getCapability）",
      item_pos > 0 and fluid_pos > 0 and item_pos < fluid_pos,
      "item=%d fluid=%d" % (item_pos, fluid_pos))


def try_block_around(code, needle):
    """needle 是否落在「它前面最近的 try {」块里（同一段方法的粗粒度判定）。"""
    pos = code.find(needle)
    if pos < 0:
        return False
    before = code[:pos]
    last_try = before.rfind("try {")
    if last_try < 0:
        return False
    # try 块与 needle 之间不能出现「}}} 结束整个 try + catch」
    between = before[last_try:]
    return between.count("catch") == 0


check("A2 两路各有自己的 try 块（一路抛异常不连坐另一路）",
      rb_early is not None
      and try_block_around(rb_early, "stack.getCapability(Capabilities.ItemHandler.ITEM)")
      and try_block_around(rb_early, "stack.getCapability(Capabilities.FluidHandler.ITEM)"))


def resolve_body():
    return method_body(be, r"private\s+void\s+resolveInner\s*\(\s*\)\s*\{")


rb = resolve_body()
check("A3 resolveInner 可定位", rb is not None)
check("A4 身份（innerFor）在两路都问完之后才写（旧实现是写在前面 ⇒ 异常永久粘住）",
      rb is not None and rb.find("this.innerFor = stack;") > rb.find("Capabilities.FluidHandler.ITEM"),
      "innerFor 写入位置必须晚于两次能力查询")
check("A5 旧写法（先写身份再解析）已经不存在",
      not re.search(r"innerFor\s*=\s*proxyStack;\s*\n\s*if\s*\(\s*proxyStack\.isEmpty\(\)", be),
      "仍是「解析前写身份」的旧结构")
check("A6 流体视图不依赖物品那路（内容物面两路互不遮蔽）",
      "case CONTENTS -> contentsFluidView;" in be
      and "case CONTENTS -> contentsItemView;" in be,
      "fluidHandler / itemHandler 的 switch 必须各自独立")
check("A7 解析异常只记标志 + 一次性日志（不许把异常抛给 RS / Jade 的调用方）",
      "catch (final Throwable" in be and "warnOnce(" in be)

# ============================================================================
# 二、源码锚点：B 退化态自动重试（不粘住、也不在热路径上反复解析）
# ============================================================================
print("=" * 78)
print("二、源码锚点：退化态按 tick 自动重试 + 热路径保持便宜")
print("=" * 78)

check("B1 重试间隔常量存在且是有限正数",
      re.search(r"RESOLVE_RETRY_TICKS\s*=\s*(\d+);", be) is not None
      and int(re.search(r"RESOLVE_RETRY_TICKS\s*=\s*(\d+);", be).group(1)) > 0,
      "RESOLVE_RETRY_TICKS 缺失或非正数")
check("B2 needsReprobe(...) 存在，且「两路都通」时立即返回 false（热路径不重解析）",
      "private boolean needsReprobe(" in be
      and re.search(r"if\s*\(\s*!innerResolveFailed\s*&&\s*innerItemSlots\s*>\s*0\s*&&\s*innerFluidTanks\s*>\s*0\s*\)\s*\{\s*return false;", be) is not None)
check("B3 早退条件 = 身份命中 + 不需要重试",
      re.search(r"if\s*\(\s*innerFor\s*==\s*proxyStack\s*&&\s*!\s*needsReprobe\s*\(\s*now\s*\)\s*\)", be) is not None)
check("B4 每次解析都刷新下次重试时刻（innerRetryAt）",
      "this.innerRetryAt =" in be and "RESOLVE_RETRY_TICKS" in be)
for caller, label in (("public void setProxyStack(", "换代理对象"),
                      ("public ItemStack takeProxyStackForDrop(", "拆方块取走"),
                      ("protected void loadAdditional(", "读档"),
                      ("public void onLoad(", "进世界")):
    body = method_body(be, re.escape(caller).replace(r"\ ", " ") + r"[^{]*\{")
    check("B5 %s路径会清掉内层缓存（clearInnerCache）" % label,
          body is not None and "clearInnerCache();" in body)
check("B6 clearInnerCache 清掉的字段是完整的一套（handler ×2 + 格数 ×2 + 失败标志 + 重试时刻）",
      all(needle in method_body(be, r"private\s+void\s+clearInnerCache\s*\(\s*\)\s*\{")
          for needle in ("this.innerItem = null;", "this.innerFluid = null;",
                         "this.innerItemSlots = 0;", "this.innerFluidTanks = 0;",
                         "this.innerResolveFailed = false;", "this.innerRetryAt = Long.MIN_VALUE;")))

# ============================================================================
# 三、源码锚点：C 事实可显示（四态 + 数据槽 + 界面 / Jade）
# ============================================================================
print("=" * 78)
print("三、源码锚点：四态与两路格数必须可显示（不谎报「就绪」）")
print("=" * 78)

check("C1 InnerState 四态齐备（NONE / ITEM / FLUID / BOTH）",
      re.search(r"public\s+enum\s+InnerState\s*\{(.*?)\}", be, re.S) is not None
      and all(t in re.search(r"public\s+enum\s+InnerState\s*\{(.*?)\}", be, re.S).group(1)
              for t in ("NONE", "ITEM", "FLUID", "BOTH")))
check("C2 innerState() 由「两路格数」判定，而不是「有没有任一 handler」",
      "innerItemSlots > 0" in method_body(be, r"public\s+InnerState\s+innerState\s*\(\s*\)\s*\{")
      and "innerFluidTanks > 0" in method_body(be, r"public\s+InnerState\s+innerState\s*\(\s*\)\s*\{"))
check("C3 数据槽 8/9/10 = 四态 / 物品格数 / 流体罐数，DATA_SLOTS = 11",
      "DATA_SLOTS = 11" in be
      and all(needle in be for needle in ("DATA_INNER_STATE = 8", "DATA_ITEM_SLOTS = 9",
                                          "DATA_FLUID_TANKS = 10")))
data_body = method_body(be, r"public\s+ContainerData\s+getContainerData\s*\(\s*\)\s*\{")
check("C4 getContainerData 真的把三格都下发（不是只声明常量）",
      data_body is not None
      and all(needle in data_body for needle in ("innerState().id()", "innerItemSlots()", "innerFluidTanks()")))

menu = strip_comments(read(MENU_PATH))
check("C5 菜单只读数据槽、不碰方块实体的能力（客户端权威口径）",
      all(needle in menu for needle in ("public InventoryProxyBlockEntity.InnerState innerState()",
                                        "public int innerItemSlots()", "public int innerFluidTanks()"))
      and "getCapability" not in menu and "proxy.innerItem()" not in menu)

screen = strip_comments(read(SCREEN_PATH))
status_body = method_body(screen, r"private\s+Component\s+statusLine\s*\(\s*\)\s*\{")
check("C6 界面状态行按四态分别成句，并写出两路格数",
      status_body is not None
      and all(t in status_body for t in ("case BOTH", "case ITEM", "case FLUID", "case NONE"))
      and "menu.innerItemSlots()" in status_body and "menu.innerFluidTanks()" in status_body)
check("C7 「只有流体」单独成句 + 给可操作提示（不许只报一句笼统的“就绪”）",
      'LANG + "status.contents_fluid_only"' in screen
      and 'LANG + "status.contents_both"' in screen)
check("C8 界面不再用旧的「就绪 / 未就绪」两句掩盖缺失",
      'LANG + "status.ready"' not in screen and "hasResolvedContents()" not in screen)

# ---- Jade：只补事实 -------------------------------------------------------
plugin = strip_comments(read(PLUGIN_PATH))
provider = strip_comments(read(PROVIDER_PATH))
payload = strip_comments(read(PAYLOAD_PATH))
server_data = strip_comments(read(SERVER_DATA_PATH))

check("C9 Jade 服务端数据 provider 只引用公共类型（专服安全）",
      re.search(r"implements\s+IServerDataProvider\s*<\s*BlockAccessor\s*>", server_data) is not None
      and not re.search(r"net\.minecraft\.client\.|com\.mojang\.blaze3d", server_data))
check("C10 服务端数据 provider 注册给容器代理器方块",
      "new InventoryProxyJadeServerData()" in plugin and "InventoryProxyBlock.class" in plugin)
check("C11 客户端 provider 只读 getServerData()，一次都不碰方块实体",
      "getServerData()" in provider and "getBlockEntity" not in provider)
check("C12 公共载荷类不引用任何 Jade 类型（Jade 缺席也能加载）",
      "snownee" not in payload and "jade.api" not in payload)
check("C13 客户端 provider 注册给容器代理器方块",
      "new RsccProxyJadeProvider()" in plugin and "InventoryProxyBlock.class" in plugin)
check("C14 载荷只写「形态 + 两路格数」，不自己搬内容物（内容物仍由 Jade 按能力采集）",
      "ProxyItemSlots" in payload and "ProxyFluidTanks" in payload
      and "getStackInSlot" not in payload and "IItemHandler" not in payload)

# 语言键：代码引用到的键 = 交给 Lead 的清单，必须一一对上
referenced = set(re.findall(r'LANG \+ "(jade\.[a-z_]+|status\.[a-z_]+)"', be_raw + screen + payload))
referenced = {"gui.rs_create_compat.inventory_proxy." + k for k in referenced}
NEW_KEYS = {
    "gui.rs_create_compat.inventory_proxy.status.contents_both":
        ("物品 %s 格 · 流体 %s 罐", "Items %s slots · Fluids %s tanks"),
    "gui.rs_create_compat.inventory_proxy.status.contents_item_only":
        ("物品 %s 格", "Items %s slots"),
    "gui.rs_create_compat.inventory_proxy.status.contents_fluid_only":
        ("流体 %s 罐 · 物品 0 格（物品库存不可读）", "Fluids %s tanks · Items 0 slots (unreadable)"),
    "gui.rs_create_compat.inventory_proxy.jade.both":
        ("内容物：物品 %s 格 · 流体 %s 罐", "Contents: items %s slots · fluids %s tanks"),
    "gui.rs_create_compat.inventory_proxy.jade.item_only":
        ("内容物：物品 %s 格", "Contents: items %s slots"),
    "gui.rs_create_compat.inventory_proxy.jade.fluid_only":
        ("内容物：流体 %s 罐 · 物品 0 格", "Contents: fluids %s tanks · items 0 slots"),
    "gui.rs_create_compat.inventory_proxy.jade.fluid_only_hint":
        ("物品栏不可读：该容器的物品库存此刻为 0 格（精妙背包常见于存储绑定不在物品上；"
         "取出后在背包栏打开一次再放回）",
         "Item slots unreadable: this container's item inventory is 0 slots right now "
         "(for Sophisticated Backpacks the storage binding is often missing from the item; "
         "take it out, open it once in your inventory, then put it back)"),
    "gui.rs_create_compat.inventory_proxy.jade.none":
        ("内容物：没有可读库存", "Contents: nothing readable"),
}
# 界面侧：LANG + "status.xxx"；Jade 载荷侧：LANG + "xxx"（LANG 已经含 .jade.）
used_suffixes = set(re.findall(r'"(status\.[a-z_]+)"', be_raw + screen))
used_suffixes |= {"jade." + m for m in
                  re.findall(r'"(both|item_only|fluid_only|fluid_only_hint|none)"', payload)}
declared_suffixes = {k[len("gui.rs_create_compat.inventory_proxy."):] for k in NEW_KEYS}
# 这两个是界面既有的键（第 97 轮就已经落库，本轮保留：代理槽为空 / 四态 NONE 时用它们）。
PREEXISTING = {"status.empty", "status.no_contents"}
check("C15 代码里出现的 status.* / jade.* 键后缀与交给 Lead 的清单一致（不多不少）",
      used_suffixes == declared_suffixes | PREEXISTING,
      "代码=%s 清单=%s" % (sorted(used_suffixes), sorted(declared_suffixes | PREEXISTING)))
check("C16 清单里每一对语言键都真的被代码引用（不是许愿清单）",
      declared_suffixes.issubset(used_suffixes), str(sorted(declared_suffixes - used_suffixes)))
zh_path = os.path.join(ASSETS, "lang", "zh_cn.json")
en_path = os.path.join(ASSETS, "lang", "en_us.json")
zh = json.load(io.open(zh_path, encoding="utf-8"))
en = json.load(io.open(en_path, encoding="utf-8"))
pending = [k for k in NEW_KEYS if k not in zh or k not in en]
print("[info] 待 Lead 落库的语言键 %d 条（本轮不许改 lang/*）：%s"
      % (len(pending), ", ".join(k.rsplit(".", 1)[-1] for k in pending) if pending else "无"))
check("C17 已落库的语言键 zh/en 都非空（未落库的在上面的 info 里列给 Lead）",
      all(str(zh.get(k, "")).strip() and str(en.get(k, "")).strip()
          for k in NEW_KEYS if k in zh and k in en))

# ============================================================================
# 四、纯 Python 行为模型：复刻 resolveInner / 两路视图 / RS + Jade 读数
# ============================================================================
print("=" * 78)
print("四、行为模型：四种代理对象形态 + 恢复 / 异常路径")
print("=" * 78)

RETRY_TICKS = int(re.search(r"RESOLVE_RETRY_TICKS\s*=\s*(\d+);", be).group(1))


class ItemHandler(object):
    """模拟一个 ItemHandler：格数 + 每格里的 stack 数（用数量代表“有东西”）。"""

    def __init__(self, slots, counts):
        self.slots = slots
        self.counts = list(counts) + [0] * (slots - len(counts))

    def get_slots(self):
        return self.slots

    def stack_in_slot(self, i):
        return self.counts[i] if 0 <= i < self.slots else 0


class FluidHandler(object):
    """模拟一个 IFluidHandler：罐数 + 每罐 mB。"""

    def __init__(self, tanks, amounts):
        self.tanks = tanks
        self.amounts = list(amounts) + [0] * (tanks - len(amounts))

    def get_tanks(self):
        return self.tanks

    def fluid_in_tank(self, i):
        return self.amounts[i] if 0 <= i < self.tanks else 0


class RaisingProbe(object):
    """模拟「provider 抛异常」：getSlots() / getTanks() 直接抛。"""

    def __init__(self, kind):
        self.kind = kind

    def get_slots(self):
        raise RuntimeError("item handler not ready")

    def get_tanks(self):
        raise RuntimeError("fluid handler not ready")


def safe_slots(handler):
    """复刻 InventoryProxyBlockEntity.slotsOf：null / 抛异常一律 0。"""
    if handler is None:
        return 0
    try:
        return max(0, handler.get_slots())
    except Exception:
        return 0


def safe_tanks(handler):
    """复刻 InventoryProxyBlockEntity.tanksOf：null / 抛异常一律 0。"""
    if handler is None:
        return 0
    try:
        return max(0, handler.get_tanks())
    except Exception:
        return 0


class Container(object):
    """代理槽里的容器物品：按 tick 给出两路能力（None = 没有该能力）。"""

    def __init__(self, item_at, fluid_at):
        self.item_at = item_at      # callable(tick) -> ItemHandler / RaisingProbe / None
        self.fluid_at = fluid_at

    def probe(self, tick, channel):
        fn = self.item_at if channel == "item" else self.fluid_at
        return fn(tick)


class Proxy(object):
    """复刻 InventoryProxyBlockEntity.resolveInner（含本轮修复 / 可取反例的开关）。"""

    def __init__(self, container, latch_before_resolve=False, reprobe=True,
                 fluid_implies_item=False, shared_except=False):
        self.container = container
        self.latch_before_resolve = latch_before_resolve
        self.reprobe = reprobe
        self.fluid_implies_item = fluid_implies_item
        self.shared_except = shared_except
        self.resolved = False
        self.retry_at = -1
        self.item = None
        self.fluid = None
        self.item_slots = 0
        self.fluid_tanks = 0

    # ---- resolveInner ----
    def _needs_reprobe(self, tick):
        if not self.reprobe and self.resolved:
            return False
        if self.item_slots > 0 and self.fluid_tanks > 0:
            return False
        return tick >= self.retry_at

    def resolve(self, tick):
        if self.resolved and not self._needs_reprobe(tick):
            return
        if self.latch_before_resolve and self.resolved:
            return                        # ← 旧实现：身份先写死 ⇒ 之后连解析都不再发生
        if self.latch_before_resolve:
            self.resolved = True
        item = fluid = None
        if self.shared_except:
            try:
                item = self.container.probe(tick, "item")
                fluid = self.container.probe(tick, "fluid")
            except Exception:
                item = fluid = None       # ← 反例：一路抛异常连坐另一路
        else:
            try:
                item = self.container.probe(tick, "item")
            except Exception:
                item = None
            try:
                fluid = self.container.probe(tick, "fluid")
            except Exception:
                fluid = None
        self.item = item
        self.fluid = fluid
        # 复刻 Java 的 slotsOf / tanksOf：第三方 handler 的 getSlots() 自己也可能抛，
        # 代理器一律按 0 处理（= 这一路此刻读不到，下一窗口重试），绝不把异常抛给 RS / Jade。
        self.item_slots = safe_slots(item)
        self.fluid_tanks = safe_tanks(fluid)
        if self.fluid_implies_item:
            # ← 反例：「有流体所以物品应该也可读」的谎报
            self.item_slots = max(self.item_slots, self.fluid_tanks)
        self.resolved = True
        self.retry_at = tick + RETRY_TICKS

    # ---- 对外视图 / 读数（RS / Jade 走的就是这几条） ----
    def inner_state(self, tick):
        self.resolve(tick)
        if self.item_slots > 0 and self.fluid_tanks > 0:
            return "BOTH"
        if self.item_slots > 0:
            return "ITEM"
        return "FLUID" if self.fluid_tanks > 0 else "NONE"

    def item_view_slots(self, tick):
        self.resolve(tick)
        return self.item_slots

    def fluid_view_tanks(self, tick):
        self.resolve(tick)
        return self.fluid_tanks

    def rs_item_amounts(self, tick):
        """RS 的 CapabilityCache#getItemAmountIterator 等价物：逐个 getStackInSlot 读数量。"""
        self.resolve(tick)
        if self.item is None:
            return []
        try:
            return [self.item.stack_in_slot(i) for i in range(self.item.get_slots())
                    if self.item.stack_in_slot(i) > 0]
        except Exception:
            return []

    def rs_fluid_amounts(self, tick):
        self.resolve(tick)
        if self.fluid is None:
            return []
        try:
            return [self.fluid.fluid_in_tank(i) for i in range(self.fluid.get_tanks())
                    if self.fluid.fluid_in_tank(i) > 0]
        except Exception:
            return []


def form_tank_backpack():
    """形态 1（用户那个「装了储罐升级」的背包，两路都正常）：物品 21 格 + 1 罐经验。"""
    return Container(
        item_at=lambda t: ItemHandler(21, [3, 5, 1]),          # 三种物品
        fluid_at=lambda t: FluidHandler(1, [500]),             # 500 mB 经验
    )


def form_fluid_only_backpack():
    """形态 2（用户实测的症状态）：物品那路 0 格（storage UUID 不在物品上）+ 1 罐经验。"""
    return Container(
        item_at=lambda t: ItemHandler(0, []),
        fluid_at=lambda t: FluidHandler(1, [500]),
    )


# ---- 形态 1：两路都有 ⇒ 物品必须真的进 RS 外部存储 ----
p = Proxy(form_tank_backpack())
check("D1 形态①（带储罐升级的背包，两路正常）⇒ 四态 = BOTH",
      p.inner_state(0) == "BOTH", p.inner_state(0))
check("D2 形态①：物品面 21 格、RS 读到 3 种（物品真的进了外部存储，没有被流体那路顶掉）",
      p.item_view_slots(0) == 21 and p.rs_item_amounts(0) == [3, 5, 1],
      "slots=%s items=%s" % (p.item_view_slots(0), p.rs_item_amounts(0)))
check("D3 形态①：流体面 1 罐、RS 读到 500 mB（两路并存，不是二选一）",
      p.fluid_view_tanks(0) == 1 and p.rs_fluid_amounts(0) == [500],
      "tanks=%s fluids=%s" % (p.fluid_view_tanks(0), p.rs_fluid_amounts(0)))

# ---- 形态 2：用户实测态 ⇒ 如实报 FLUID，不许谎报有物品 ----
p = Proxy(form_fluid_only_backpack())
check("D4 形态②（用户那个「只显示流体」的背包）⇒ 四态 = FLUID（这一态必须能被显示出来）",
      p.inner_state(0) == "FLUID", p.inner_state(0))
check("D5 形态②：物品面如实报 0 格、RS 读到 0 种（不谎报，也不抛异常）",
      p.item_view_slots(0) == 0 and p.rs_item_amounts(0) == [],
      "slots=%s items=%s" % (p.item_view_slots(0), p.rs_item_amounts(0)))
check("D6 形态②：流体面照常可读 1 罐（物品那路 0 格不影响流体）",
      p.fluid_view_tanks(0) == 1 and p.rs_fluid_amounts(0) == [500])

# ---- 形态 2 → 1：容器恢复可读后，代理器必须自己好起来 ----
def recover_at(trigger_tick, retry_ticks):
    """物品那路在 trigger_tick 之后才可读（模拟 storage UUID 恢复 / 玩家重开背包）。"""

    def item_at(t):
        if t < trigger_tick:
            return ItemHandler(0, [])
        return ItemHandler(21, [3, 5, 1])

    return Container(item_at=item_at, fluid_at=lambda t: FluidHandler(1, [500]))


p = Proxy(recover_at(100, RETRY_TICKS))
p.inner_state(0)
check("D7 恢复路径：t=0 时如实报 FLUID（此时物品确实读不到）", p.inner_state(0) == "FLUID")
check("D8 恢复路径：等一个重试窗口后自动变成 BOTH —— 不拆方块、不重放代理对象",
      p.inner_state(100 + RETRY_TICKS) == "BOTH",
      "t=%d 时仍是 %s" % (100 + RETRY_TICKS, p.inner_state(100 + RETRY_TICKS)))
check("D9 恢复路径：恢复后 RS 真的读到了三种物品",
      p.rs_item_amounts(100 + RETRY_TICKS) == [3, 5, 1], str(p.rs_item_amounts(100 + RETRY_TICKS)))

# ---- 形态 3：物品那路抛异常（流体不许连坐） ----
def throw_until(trigger_tick, retry_ticks):
    """模拟第三方 provider 在「存储数据未就绪」时从 getCapability 里直接抛异常。"""

    def item_at(t):
        if t < trigger_tick:
            raise RuntimeError("item provider not ready")
        return ItemHandler(9, [2])

    return Container(item_at=item_at, fluid_at=lambda t: FluidHandler(1, [250]))


p = Proxy(throw_until(50, RETRY_TICKS))
check("D10 异常形态：物品那路抛异常时，流体仍然可读（绝不连坐）",
      p.inner_state(0) == "FLUID" and p.rs_fluid_amounts(0) == [250],
      "state=%s fluids=%s" % (p.inner_state(0), p.rs_fluid_amounts(0)))
check("D11 异常形态：异常不是终态 —— 一个窗口后物品自动恢复（旧实现会永久粘住）",
      p.inner_state(50 + RETRY_TICKS) == "BOTH" and p.rs_item_amounts(50 + RETRY_TICKS) == [2],
      "state=%s items=%s" % (p.inner_state(50 + RETRY_TICKS), p.rs_item_amounts(50 + RETRY_TICKS)))

# ---- 形态 4：两路都没有 ----
p = Proxy(Container(item_at=lambda t: None, fluid_at=lambda t: None))
check("D12 形态④（两路都没有）⇒ NONE，且两边视图都安全返回 0 / 空",
      p.inner_state(0) == "NONE" and p.item_view_slots(0) == 0
      and p.fluid_view_tanks(0) == 0 and p.rs_item_amounts(0) == [] and p.rs_fluid_amounts(0) == [])

# ---- 热路径：两路都通之后不许每 tick 重解析 ----
counter = {"n": 0}


class CountingContainer(Container):
    def probe(self, tick, channel):
        counter["n"] += 1
        return Container.probe(self, tick, channel)


p = Proxy(CountingContainer(form_tank_backpack().item_at, form_tank_backpack().fluid_at))
for t in range(0, 200):
    p.inner_state(t)
check("D13 两路都通之后不再重解析（200 tick 只问 2 次能力；热路径不许退化成每 tick 查）",
      counter["n"] == 2, "探针被调用 %d 次" % counter["n"])

# ============================================================================
# 五、反例自证：把不变式改回旧写法，对应断言必须判负
# ============================================================================
print("=" * 78)
print("五、反例自证（同一份判据，改回旧写法必须判负）")
print("=" * 78)

# 反例 1：解析前写身份（旧实现）⇒ 异常后永久粘住
bad = Proxy(throw_until(50, RETRY_TICKS), latch_before_resolve=True)
bad.inner_state(0)
check("E1 反例①：解析前写身份 ⇒ 异常后永久粘住、恢复不了（判负 = 断言成立）",
      bad.inner_state(50 + RETRY_TICKS * 3) != "BOTH",
      "旧实现竟然恢复了？")

# 反例 2：解析成功一次就不再重试（0 格被当成终态）
bad = Proxy(recover_at(100, RETRY_TICKS), reprobe=False)
bad.inner_state(0)
check("E2 反例②：只解析一次不重试 ⇒ 容器恢复后仍是 FLUID（永远看不到物品）",
      bad.inner_state(100 + RETRY_TICKS * 3) != "BOTH")

# 反例 3：有流体就当有物品（谎报）
bad = Proxy(form_fluid_only_backpack(), fluid_implies_item=True)
check("E3 反例③：有流体就当有物品可读 ⇒ 界面会报「有物品」而 RS 读到 0 种（谎报）",
      bad.item_view_slots(0) > 0 and bad.rs_item_amounts(0) == [])

# 反例 4：一路抛异常连坐另一路
bad = Proxy(throw_until(50, RETRY_TICKS), shared_except=True)
check("E4 反例④：共用 try/catch ⇒ 物品那路一抛，流体也一起消失（连坐）",
      bad.inner_state(0) == "NONE" and bad.rs_fluid_amounts(0) == [],
      "state=%s" % bad.inner_state(0))

# 反例 5：状态里没有 FLUID 档（沿用旧的两态）
def legacy_state(proxy, tick):
    return "OK" if (proxy.item_view_slots(tick) > 0 or proxy.fluid_view_tanks(tick) > 0) else "EMPTY"


p = Proxy(form_fluid_only_backpack())
check("E5 反例⑤：旧的两态（就绪/未就绪）会把用户的症状态报成「就绪」—— 界面因此无话可说",
      legacy_state(p, 0) == "OK" and p.inner_state(0) == "FLUID")

# ============================================================================
print("=" * 78)
if FAILS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(FAILS), CHECKS[0]))
    for f in FAILS:
        print("  - " + f)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 %d 个反例自证 + 13 项行为模型）；"
      "容器代理器在「物品+流体并存」与「只有流体」两种代理对象形态下都如实、可恢复"
      % (CHECKS[0], 5))
sys.exit(0)
