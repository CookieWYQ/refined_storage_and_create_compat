# -*- coding: utf-8 -*-
"""第 63 轮自检：序列装配样板终端的「样板槽」对网络 / Jade 暴露。

用法：python tools/selfcheck_round63_pattern_slot_exposed.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

═══════════════════════════════════════════════════════════════════════════════
用户报告（原话）
═══════════════════════════════════════════════════════════════════════════════
「我之前要求你对那个序列（装配）样板终端的那一个样板槽进行暴露，但是你并没有进行暴露。
  我的意思就是：精致存储原版的样板终端可以直接使用输出总线往里面输入那个样板，但是现在并不行，
  因为我用 Jade 并不能查看到里面的样板槽。」

⇒ 要求：让序列装配样板终端的样板槽像 RS 原版样板终端一样**对网络暴露**，
   使 **RS 输出总线（Exporter）能把样板插进去**，并让 **Jade 能列出槽内容**。

═══════════════════════════════════════════════════════════════════════════════
RS 原版（2.0.9）的暴露机制 —— 本脚本 A 组逐条复算（不按注释，按事实）
═══════════════════════════════════════════════════════════════════════════════
① **唯一的对外接口就是 NeoForge 的 `Capabilities.ItemHandler.BLOCK`**（不是 RS 自己的
   `NetworkNodeContainerProvider`，也不是某个菜单）：
   * 注册点 `ModInitializer#registerCapabilities`：
     `Capabilities.ItemHandler.BLOCK + BlockEntities#getPatternGrid ⇒ new InvWrapper(be.getPatternInput())`
     （本机 RS 2.0.9 检出源码 `.../neoforge/ModInitializer.java`，内容断言见 A2；
     2.0.9 制品字节码里同一处 = `lambda$registerCapabilities$17(PatternGridBlockEntity, Direction)
     → IItemHandler`，见 A6）；
   * 准入判据落在**那个内部容器**上：`patternInput = new FilteredContainer(1, ::isValidPattern)`，
     `isValidPattern = stack.getItem() instanceof PatternItem`；`FilteredContainer#canPlaceItem` = filter
     （A3 / A4）。RS 只暴露 `patternInput`，**不**暴露 `patternOutput`。
② **输出总线（Exporter）取目标容器用的是同一个能力**：
   `AbstractExporterBlockEntity#createStrategy` 取 `worldPosition.relative(direction)`（总线正面那一格），
   `CapabilityCacheImpl` → `BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, 目标格, 朝向)`，
   插入走 `ItemHandlerInsertableStorage` → `ItemHandlerHelper.insertItem`（A5 / A5b）。
③ **Jade 看到的也是同一个能力**：Jade 自己的 `UniversalPlugin` 把 `ItemStorageProvider` 注册给
   `Block.class`（所有方块），其 `CommonProxy#findItemHandler` 做的是
   `Level#getCapability(Capabilities.ItemHandler.BLOCK, pos, state, be, side=null)`（A7）。
   ⇒ **一次注册同时喂饱输出总线与 Jade**，不需要任何 Jade 专属代码；
   也正因为如此，空容器在 Jade 里不画提示框（Jade 只在有内容时渲染物品格）——
   这就是用户「Jade 看不到样板槽」在「槽是空的」时的成因。

═══════════════════════════════════════════════════════════════════════════════
本轮做了什么（B/C/D/E 组断言它）
═══════════════════════════════════════════════════════════════════════════════
B. **暴露清单**：一个方块实体类型只能有一个同能力 provider ⇒ 把两段样板槽拼成同一个 handler
   （`support/PatternSlotExposure`，工程既有 `ExtractOnlyHandlers` 的同一套「薄委托」写法）：
     * 对外 0..8   → `patternSlots`（总样板槽；只收本模组序列装配总样板，一格一张，9 格全暴露
                     以保证老存档第 2~9 格的样板仍能被物流搬走，否则 = 丢物品）；
     * 对外 9..11  → `rsPatternSlots`（终端自有 RS 样板输入槽；只收 `refinedstorage:pattern`）。
   **不**暴露单元样板库 / 流程编排 / 生成中转槽 / 三个幽灵标记容器（后三者不是真实资源）。
C. **真值表**（Python 复刻同一个分段视图，判据取自源码常量与两条判定方法，不另写语义）：
   序列装配总样板 → 只进 0..8；RS 空白样板 → 只进 9..11；单元样板 / 普通物品 / 空栈 → 一律拒收；
   越界下标 → 原样退回；段间不串格；栈上限（总样板一格一张）如实生效。
D. **Jade / 显示路径**：锚点 = 同一个能力（A7 + B2），且本模组**没有**为终端另注册任何
   item storage provider（不遮蔽 Jade 的默认路径）。
E. **既有逻辑未被破坏**：生成扣料仍直接读真实 `rsPatternSlots`、判重（UnitPatternDedupe）、
   NBT 键、掉落清单、菜单「总样板槽只出不进」、网络节点容器能力都逐条钉住；
   暴露视图**不**实现 `IItemHandlerModifiable`（没有 `setStackInSlot` ⇒ 判据无旁路）。

**没有实机验证**：本脚本只做源码交叉验证 + Jar 字节码锚点 + 模型真值表，不能替代游戏内验证。
"""
import io
import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
BE = os.path.join(PKG, "block", "entity", "SequencePatternTerminalBlockEntity.java")
EXPOSURE = os.path.join(PKG, "support", "PatternSlotExposure.java")
MENU = os.path.join(PKG, "menu", "SequencePatternTerminalMenu.java")
JADE_PLUGIN = os.path.join(PKG, "client", "jade", "RsccJadePlugin.java")
RS_DIR = os.path.join(ROOT, "local_src", "external", "RefinedStorage")

CHECKS = [0]
FAILURES = []


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    # 细节只在失败时打出来：通过时打「未找到…」这类反例说明会让人误读结果
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       (" | " + detail) if (detail and not ok) else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def hasnt(text, needle, name):
    ok = needle not in text
    check(name, ok, "" if ok else ("unexpected: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def method_body(text, needle):
    """取 `text` 里第一个 `needle` 起的整个方法体（花括号配对）。"""
    idx = text.index(needle)
    begin = text.rindex("\n", 0, idx)
    cursor = text.index("{", idx)
    depth = 0
    while True:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
            if depth == 0:
                break
        cursor += 1
    return text[begin:cursor + 1]


def code_only(text):
    """去掉块注释与行注释：源码里的「为什么不这样做」说明文字不该被当成代码断言命中。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def int_const(text, name):
    match = re.search(r"int\s+%s\s*=\s*([0-9_]+)\s*;" % re.escape(name), text)
    return None if match is None else int(match.group(1).replace("_", ""))


def rs_find(name):
    """在 RS 2.0.9 检出里按文件名找源码（返回 (正文, 相对路径)）。"""
    if not os.path.isdir(RS_DIR):
        return None, None
    for base, _dirs, files in os.walk(RS_DIR):
        for entry in files:
            if entry == name:
                path = os.path.join(base, entry)
                with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
                    return handle.read(), os.path.relpath(path, ROOT)
    return None, None


def class_bytes(jar, entry):
    with zipfile.ZipFile(jar) as archive:
        return archive.read(entry)


def find_rs_jar():
    """用工程自己的缓存定位规则找 RS 2.0.9 制品（pin 优先，见 tools/_gradle_cache.py）。"""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    try:
        import _gradle_cache as cache
    except Exception:
        return None
    path, _notes = cache.find_jar("com.refinedmods.refinedstorage", "refinedstorage-neoforge")
    return path


def find_jade_jar():
    libs = os.path.join(ROOT, "libs")
    if not os.path.isdir(libs):
        return None
    names = sorted(n for n in os.listdir(libs) if n.startswith("jade-") and n.endswith(".jar"))
    return None if not names else os.path.join(libs, names[-1])


be = read(BE)
exposure = read(EXPOSURE)
menu = read(MENU)
jade_plugin = read(JADE_PLUGIN)

# 方法体（只取「注册入口 / 两个判定 / 视图字段」等关键处，避免全文误命中）
register_body = method_body(be, "public static void registerCapabilities(")
accepts_body = method_body(be, "public static boolean acceptsPattern(")
rs_pattern_body = method_body(be, "public static boolean isRefinedStoragePattern(")
exposed_decl = be[be.index("public final net.neoforged.neoforge.items.IItemHandler exposedPatternSlots"):]
exposed_decl = exposed_decl[:exposed_decl.index(";")]

# ======================================================================================
section("A. RS 2.0.9 原版样板终端的暴露机制（同源锚点）")

changelog, changelog_rel = rs_find("CHANGELOG.md")
rs_ok = changelog is not None
check("A0 本机存在 RS 检出（%s）" % os.path.relpath(RS_DIR, ROOT), rs_ok,
      "RS 检出缺失，无法核对原版机制（见 tools/selfcheck_round58 的同类依赖）")
if rs_ok:
    check("A0b 该检出确实是 2.0.9（CHANGELOG 含 [2.0.9]；与 2.0.9 制品字节码复算见 A6）",
          re.search(r"^##\s*\[2\.0\.9\]", changelog, re.M) is not None, changelog_rel)

modinit, modinit_rel = rs_find("ModInitializer.java")
check("A1 找到 RS 的 neoforge ModInitializer（%s）" % (modinit_rel or "-"), modinit is not None)
if modinit:
    grid = re.search(
        r"Capabilities\.ItemHandler\.BLOCK,\s*BlockEntities\.INSTANCE\.getPatternGrid\(\),\s*"
        r"\(be, side\)\s*->\s*new InvWrapper\(be\.getPatternInput\(\)\)", modinit)
    check("A2 RS 用 Capabilities.ItemHandler.BLOCK 暴露样板网格的 patternInput"
          "（new InvWrapper(be.getPatternInput())）", grid is not None,
          "未找到样板网格那一段 ItemHandler 注册")
    body = method_body(modinit, "private void registerCapabilities(")
    check("A2b RS 对同一个能力只注册一次 patternGrid（一个方块实体类型 = 一个 provider）",
          body.count("getPatternGrid()") == 2, "count=%d（期望 1 次网络节点 + 1 次物品能力）"
          % body.count("getPatternGrid()"))
    hasnt(body, "getPatternOutput()", "A2c RS **不**暴露 patternOutput（只暴露输入那个容器）")

pgrid, pgrid_rel = rs_find("PatternGridBlockEntity.java")
check("A3 找到 RS 样板网格方块实体（%s）" % (pgrid_rel or "-"), pgrid is not None)
if pgrid:
    has(pgrid, "new FilteredContainer(1, PatternGridBlockEntity::isValidPattern)",
        "A3 准入判据绑在容器上：patternInput = new FilteredContainer(1, ::isValidPattern)")
    has(pgrid, "public FilteredContainer getPatternInput()", "A3b 对外入口就是 getPatternInput()")
    valid = method_body(pgrid, "static boolean isValidPattern(")
    check("A3c isValidPattern 的判据 = 物品类型属于 RS 样板（instanceof PatternItem）",
          "instanceof PatternItem" in valid, valid.strip()[:120])

filtered, filtered_rel = rs_find("FilteredContainer.java")
check("A4 找到 RS 的 FilteredContainer（%s）" % (filtered_rel or "-"), filtered is not None)
if filtered:
    has(filtered, "extends SimpleContainer",
        "A4 FilteredContainer 就是原版 Container（InvWrapper 包的是它，不是 RS 自造接口）")
    has(filtered, "public boolean canPlaceItem(final int slot, final ItemStack stack) {\n        return filter.test(stack);",
        "A4b 准入判据落在 canPlaceItem = filter.test（唯一落点）")

capcache, capcache_rel = rs_find("CapabilityCacheImpl.java")
check("A5 找到 RS 的 CapabilityCacheImpl（%s）" % (capcache_rel or "-"), capcache is not None)
if capcache:
    has(capcache, "BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, pos, direction)",
        "A5 输出总线取目标容器 = 同一个 Capabilities.ItemHandler.BLOCK（带朝向上下文）")

exporter, exporter_rel = rs_find("AbstractExporterBlockEntity.java")
check("A5b 找到 RS 的 AbstractExporterBlockEntity（%s）" % (exporter_rel or "-"), exporter is not None)
if exporter:
    has(exporter, "final BlockPos sourcePosition = worldPosition.relative(direction);",
        "A5c 输出总线的目标格 = 它正面那一格（worldPosition.relative(direction)）")

factory, factory_rel = rs_find("ItemHandlerExporterTransferStrategyFactory.java")
check("A5d 找到 RS 的输出总线物品搬运策略工厂（%s）" % (factory_rel or "-"), factory is not None)
if factory:
    has(factory, "new CapabilityCacheImpl(level, pos, direction)",
        "A5e 物品搬运用的就是上面那份能力缓存 ⇒ 暴露与否只取决于该能力")
    has(factory, "destination::getAmount", "A5f 配额仍按目标容器的实收量算（没被旁路）")

# A6：不依赖源码检出，直接从 2.0.9 制品字节码复算同一件事
rs_jar = find_rs_jar()
check("A6 用工程缓存规则定位到 RS 制品（%s）" % (os.path.basename(rs_jar) if rs_jar else "-"),
      rs_jar is not None, "缓存里没有 refinedstorage-neoforge 制品")
if rs_jar:
    mod_class = class_bytes(rs_jar, "com/refinedmods/refinedstorage/neoforge/ModInitializer.class")
    check("A6b 2.0.9 字节码：ModInitializer 里的样板网格 lambda 形如"
          " (PatternGridBlockEntity, Direction) -> IItemHandler",
          b"(Lcom/refinedmods/refinedstorage/common/autocrafting/patterngrid/PatternGridBlockEntity;"
          b"Lnet/minecraft/core/Direction;)Lnet/neoforged/neoforge/items/IItemHandler;" in mod_class)
    check("A6c 2.0.9 字节码：同一个类里既有 capabilities/Capabilities$ItemHandler 又有 PatternGridBlockEntity",
          b"net/neoforged/neoforge/capabilities/Capabilities$ItemHandler" in mod_class
          and b"com/refinedmods/refinedstorage/common/autocrafting/patterngrid/PatternGridBlockEntity"
          in mod_class)

# A7：Jade 侧走的是同一个能力（Jade 制品字节码锚点）
jade_jar = find_jade_jar()
check("A7 找到工程内 Jade 制品（%s）" % (os.path.basename(jade_jar) if jade_jar else "-"),
      jade_jar is not None)
if jade_jar:
    with zipfile.ZipFile(jade_jar) as archive:
        names = set(archive.namelist())
    check("A7b Jade 有通用物品存储提供者（UniversalPlugin + ItemStorageProvider$Extension）",
          "snownee/jade/addon/universal/UniversalPlugin.class" in names
          and "snownee/jade/addon/universal/ItemStorageProvider$Extension.class" in names)
    proxy = class_bytes(jade_jar, "snownee/jade/util/CommonProxy.class")
    check("A7c Jade 的 CommonProxy 引用 Capabilities$ItemHandler（= 与 RS 同一个能力）",
          b"neoforged/neoforge/capabilities/Capabilities$ItemHandler" in proxy)
    plugin = class_bytes(jade_jar, "snownee/jade/addon/universal/UniversalPlugin.class")
    check("A7d Jade 把 item storage 注册给 Block.class（所有方块）⇒ 我们注册能力即自动可见",
          b"net/minecraft/world/level/block/Block" in plugin
          and b"registerBlockDataProvider" in plugin)

# ======================================================================================
section("B. 我们暴露的槽清单 + 逐段准入判据")

has(register_body, "net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK",
    "B1 用的是 RS 同源的 Capabilities.ItemHandler.BLOCK（不是自造接口 / 菜单）")
has(register_body, "RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_BLOCK_ENTITY.get()",
    "B1b 注册在序列装配样板终端这一个方块实体类型上")
has(register_body, "(blockEntity, direction) -> blockEntity.exposedPatternSlots",
    "B2 provider 返回拼好的样板槽视图（并且忽略朝向 ⇒ 任何一面 / side=null 都看同一份）")
hasnt(register_body, "-> blockEntity.patternSlots",
    "B2b 不再只返回 patternSlots（用户报的就是「只有总样板槽可见」）")
has(register_body, "blockEntity.getContainerProvider()",
    "B2c RS 网络节点容器能力仍在同一方法里注册（接入网络的行为未变）")

check("B3 视图 = 两段拼接（patternSlots → rsPatternSlots），且只有这一处拼接",
      "PatternSlotExposure.of(patternSlots, rsPatternSlots)" in exposed_decl
      and be.count("PatternSlotExposure.of(") == 1,
      "decl=%s count=%d" % (exposed_decl.strip()[:110], be.count("PatternSlotExposure.of(")))
check("B3b 视图声明为对外字段（供能力 lambda 引用），类型是 IItemHandler",
      "public final net.neoforged.neoforge.items.IItemHandler exposedPatternSlots" in exposed_decl)

# 段①：总样板槽
pattern_slots_decl = be[be.index("public final ItemStackHandler patternSlots"):]
pattern_slots_decl = pattern_slots_decl[:pattern_slots_decl.index("\n    };")]
has(pattern_slots_decl, "return acceptsPattern(stack);",
    "B4 段① 准入判据 = acceptsPattern（不新造第二套判定）")
has(pattern_slots_decl, "return 1;", "B4b 段① 一格一张（getSlotLimit = 1）")
has(pattern_slots_decl, "compactPatternSlots();",
    "B4c 段① 仍保留「可见格被取空 ⇒ 隐藏格前移」的钩子（onContentsChanged）")
check("B4d acceptsPattern = 只收本模组的序列装配总样板，空栈一律拒收",
      "!stack.isEmpty()" in accepts_body and "stack.is(RS_Create_Compat.SEQUENCE_ASSEMBLY_PATTERN.get())" in accepts_body,
      accepts_body.strip()[:140])

# 段②：终端自有 RS 样板输入槽
rs_slots_decl = be[be.index("public final ItemStackHandler rsPatternSlots"):]
rs_slots_decl = rs_slots_decl[:rs_slots_decl.index("\n    };")]
has(rs_slots_decl, "return isRefinedStoragePattern(stack);",
    "B5 段② 准入判据 = isRefinedStoragePattern（沿用既有语义）")
check("B5b isRefinedStoragePattern = 只收 refinedstorage:pattern（空栈拒收），"
      "且按注册名解析（RS 是可选依赖，编译期引用会崩）",
      "stack.isEmpty()" in rs_pattern_body
      and 'fromNamespaceAndPath("refinedstorage", "pattern")' in rs_pattern_body
      and "BuiltInRegistries.ITEM.get(" in rs_pattern_body,
      rs_pattern_body.strip()[:180])
check("B5c 与 RS 原版同口径：RS 用 instanceof PatternItem（同一物品类型），我们用注册名判定同一物品",
      "instanceof PatternItem" in (pgrid or ""), "RS 侧锚点缺失")

# 只暴露这两段
for other, label in (("unitLibrary", "单元样板库"), ("arrangement", "流程编排"),
                     ("displayArrangement", "流程展示数据"), ("assemblyPatternSlot", "生成中转槽"),
                     ("ingredientSlot", "原料标记槽"), ("resultSlots", "产物标记槽"),
                     ("scrapSlots", "废料标记槽")):
    hasnt(register_body, "blockEntity." + other, "B6 %s 未被暴露（不在能力 provider 里）" % label)
    hasnt(exposed_decl, other, "B6b %s 不在拼接视图的实参里" % label)

# 视图本身：无判据旁路、无客户端类型
exposure_code = code_only(exposure)
has(exposure_code, "implements IItemHandler", "B7 视图实现的是 IItemHandler")
hasnt(exposure_code, "IItemHandlerModifiable", "B7b 刻意不实现 IItemHandlerModifiable"
                                               "（否则 setStackInSlot 会绕开 isItemValid）")
hasnt(exposure_code, "setStackInSlot", "B7c 视图里没有 setStackInSlot（判据无旁路）")
has(exposure_code, "segments[segment].insertItem(slot - startOf(segment), stack, simulate)",
    "B7d 插入一律委托给那一段自己的 handler ⇒ 每段用自己的 isItemValid")
hasnt(exposure_code, "private final int[] starts", "B8 段边界不缓存下标表（段容量现算，改容量也不会算错）")
check("B8b 段边界按 getSlots() 现算（startOf + segmentOf 逐段累加）",
      "private int startOf(final int segment)" in exposure
      and "private int segmentOf(final int slot)" in exposure)
for forbidden in ("net.minecraft.client", "snownee.jade", "net.neoforged.api.distmarker"):
    hasnt(exposure, forbidden, "B9 暴露视图不引用客户端类型：%s（注册期安全）" % forbidden)
    hasnt(register_body, forbidden, "B9b 注册方法体不引用客户端类型：%s" % forbidden)

# ======================================================================================
section("C. 真值表：只有该接受的物品能进（与源码常量 / 两条判定同源）")

SIZE_MAIN = int_const(be, "PATTERN_SLOT_SIZE")
SIZE_RS = int_const(be, "RS_PATTERN_SLOT_SIZE")
VISIBLE = int_const(be, "PATTERN_VISIBLE")
check("C0 段容量与源码常量同源：PATTERN_SLOT_SIZE=%s、RS_PATTERN_SLOT_SIZE=%s（PATTERN_VISIBLE=%s）"
      % (SIZE_MAIN, SIZE_RS, VISIBLE),
      (SIZE_MAIN, SIZE_RS, VISIBLE) == (9, 3, 1))

ASSEMBLY = "rs_create_compat:sequence_assembly_pattern"
RS_BLANK = "refinedstorage:pattern"
UNIT = "rs_create_compat:sequence_unit_pattern"
STONE = "minecraft:stone"


def accept_main(item):
    """与 acceptsPattern 同义：非空 且 是序列装配总样板。"""
    return item is not None and item == ASSEMBLY


def accept_rs(item):
    """与 isRefinedStoragePattern 同义：非空 且 是 refinedstorage:pattern。"""
    return item is not None and item == RS_BLANK


class Segment(object):
    def __init__(self, name, size, limit, accept):
        self.name = name
        self.size = size
        self.limit = limit          # 每格上限（段① 1 张；段② 默认 64）
        self.accept = accept
        self.stacks = [None] * size


class Exposure(object):
    """PatternSlotExposure.Item 的同构模型（同样的下标换算、同样的原样退回语义）。"""

    def __init__(self, segments):
        self.segments = segments

    def get_slots(self):
        return sum(s.size for s in self.segments)

    def start_of(self, segment):
        return sum(self.segments[i].size for i in range(segment))

    def segment_of(self, slot):
        if slot < 0:
            return -1
        for i in range(len(self.segments)):
            if slot < self.start_of(i + 1):
                return i
        return -1

    def is_item_valid(self, slot, item):
        seg = self.segment_of(slot)
        return seg >= 0 and item is not None and self.segments[seg].accept(item)

    def insert(self, slot, item, count):
        """返回实际进入的件数（越界 / 判据拒绝 / 已满 ⇒ 0）。"""
        seg = self.segment_of(slot)
        if seg < 0:
            return 0
        segment = self.segments[seg]
        local = slot - self.start_of(seg)
        if not segment.accept(item):
            return 0
        current = 0 if segment.stacks[local] is None else segment.stacks[local][1]
        room = segment.limit - current
        taken = max(0, min(room, count))
        if taken > 0:
            segment.stacks[local] = (item, current + taken)
        return taken

    def insert_like_bus(self, item, count):
        """ItemHandlerHelper.insertItem 的语义：从下标 0 起逐格塞，返回剩余未进量。"""
        rest = count
        for slot in range(self.get_slots()):
            if rest <= 0:
                break
            rest -= self.insert(slot, item, rest)
        return rest


def fresh():
    return Exposure([
        Segment("总样板槽(9)", SIZE_MAIN, 1, accept_main),
        Segment("RS 样板输入槽(3)", SIZE_RS, 64, accept_rs),
    ])


# 真值表：逐物品 × 逐段
TABLE = (
    (ASSEMBLY, "序列装配总样板", True, False),
    (RS_BLANK, "RS 空白样板", False, True),
    (UNIT, "单元样板", False, False),
    (STONE, "普通物品", False, False),
    (None, "空栈", False, False),
)
for item, label, want_main, want_rs in TABLE:
    view = fresh()
    in_main = view.is_item_valid(0, item)
    in_rs = view.is_item_valid(SIZE_MAIN, item)
    check("C1 真值表 %s：总样板槽段 %s / RS 样板输入槽段 %s"
          % (label, "✓收" if want_main else "✗拒", "✓收" if want_rs else "✗拒"),
          (in_main, in_rs) == (want_main, want_rs),
          "实际 = (%s, %s)" % (in_main, in_rs))

# 落点：能进的东西落到哪一段（段间不串格）
view = fresh()
check("C2 序列装配总样板经输出总线插入 ⇒ 落在对外下标 0（总样板槽段）",
      view.insert_like_bus(ASSEMBLY, 1) == 0 and view.segments[0].stacks[0] == (ASSEMBLY, 1))
view = fresh()
check("C2b RS 空白样板经输出总线插入 ⇒ 落在对外下标 %d（RS 样板输入槽段第一格），"
      "而不是被总样板槽拒收后就丢掉" % SIZE_MAIN,
      view.insert_like_bus(RS_BLANK, 1) == 0
      and view.segments[1].stacks[0] == (RS_BLANK, 1)
      and view.segments[0].stacks == [None] * SIZE_MAIN)
view = fresh()
check("C2c 单元样板 / 普通物品经输出总线插入 ⇒ 全量退回（一个都不收）",
      view.insert_like_bus(UNIT, 64) == 64 and view.insert_like_bus(STONE, 3) == 3)

# 上限：总样板一格一张 ⇒ 64 张只进 9 张（9 格 × 1），其余原路退回；RS 样板 64 张一次进一格
view = fresh()
rest = view.insert_like_bus(ASSEMBLY, 64)
check("C3 总样板一格一张 × 9 格 ⇒ 插入 64 张只收下 %d 张、退回 %d 张（不复制、不超格）"
      % (SIZE_MAIN, rest), rest == 64 - SIZE_MAIN
      and all(view.segments[0].stacks[i] == (ASSEMBLY, 1) for i in range(SIZE_MAIN)))
view = fresh()
check("C3b RS 样板段是普通 3 格（每格 64 上限）⇒ 64 张一次全进第一格",
      view.insert_like_bus(RS_BLANK, 64) == 0 and view.segments[1].stacks[0] == (RS_BLANK, 64))

# 越界与提取：不抛异常、不串段
view = fresh()
total = view.get_slots()
check("C4 对外总格数 = %d + %d = %d（与两个源码常量一致）" % (SIZE_MAIN, SIZE_RS, total),
      total == SIZE_MAIN + SIZE_RS == 12)
check("C4b 越界下标（-1 / %d）一律：insert 原样退回、isItemValid=false、getSlotLimit=0" % total,
      view.insert(-1, ASSEMBLY, 1) == 0 and view.insert(total, ASSEMBLY, 1) == 0
      and view.insert(total, RS_BLANK, 1) == 0
      and not view.is_item_valid(total, RS_BLANK) and not view.is_item_valid(-1, RS_BLANK))
check("C4c 段边界不串格：下标 %d 归 RS 样板段、下标 %d 归总样板段"
      % (SIZE_MAIN, SIZE_MAIN - 1),
      view.segment_of(SIZE_MAIN) == 1 and view.segment_of(SIZE_MAIN - 1) == 0)

# 逆向：界面取走 / 物流抽出仍走真实 handler（模型上体现为「同一份状态」）
view = fresh()
view.insert(0, ASSEMBLY, 1)
check("C5 视图是同一份状态的委托（不是副本）：插入后 getStackInSlot 立即可见",
      view.segments[0].stacks[0] == (ASSEMBLY, 1))

# ======================================================================================
section("D. Jade / 显示路径：由通用物品能力自动获得（本轮不新增任何 Jade 代码）")

has(jade_plugin, "registration.registerBlockComponent(new CamouflageProvider(), Block.class);",
    "D1 本模组的 Jade 插件仍只做「伪装图标 / 文本」")
has(jade_plugin, "registration.registerBlockComponent(bus, ImporterBlock.class);",
    "D1b 以及两条总线的状态文本（未给样板终端注册任何 item storage provider）")
hasnt(jade_plugin, "registerItemStorage", "D2 本模组没有注册 item storage provider（不遮蔽 Jade 默认路径）")
hasnt(jade_plugin, "SequencePatternTerminal", "D2b 插件里一个字都没提样板终端 ⇒ 显示完全由通用能力驱动")
check("D3 结论锚点：同一能力（B1/B2）+ Jade 对所有方块查它（A7d）⇒ "
      "输出总线插进去的样板，Jade 在下一次瞄准时就能列出（空槽则不画提示框，这是 Jade 自身行为）",
      "Capabilities.ItemHandler.BLOCK" in register_body
      and "exposedPatternSlots" in register_body)

# ======================================================================================
section("E. 既有逻辑未被破坏")

# E1 生成扣料仍直接读真实 rsPatternSlots
count_body = method_body(be, "public int countRsPatterns()")
consume_body = method_body(be, "public int consumeRsPatterns(")
has(count_body, "rsPatternSlots.getStackInSlot(i)", "E1 耗材统计仍直接读真实 rsPatternSlots")
has(consume_body, "rsPatternSlots.setStackInSlot(i,", "E1b 耗材扣除仍直接写真实 rsPatternSlots")
hasnt(be, "exposedPatternSlots.getStackInSlot", "E1c 终端的内部逻辑不经暴露视图（视图只是对外投影）")
has(be, "public int generationPatternCost()", "E1d 生成消耗估算仍在（唯一权威）")

# E2 总样板槽压缩 / 常量
has(be, "public void compactPatternSlots()", "E2 总样板槽「隐藏格前移」仍在")
has(be, "if (slot >= 0 && slot < PATTERN_VISIBLE && getStackInSlot(slot).isEmpty()) {",
    "E2b 压缩触发时机未改（可见格被取空时）")
check("E2c 常量未变：PATTERN_SLOT_SIZE=9 / PATTERN_VISIBLE=1 / UNIT_WINDOW=6 / RS_PATTERN_SLOT_SIZE=3",
      (SIZE_MAIN, VISIBLE, int_const(be, "UNIT_WINDOW"), SIZE_RS) == (9, 1, 6, 3))

# E3 NBT / 掉落
has(be, 'tag.put("RsPatterns", rsPatternSlots.serializeNBT(registries));',
    "E3 RS 样板槽落盘键未变（RsPatterns）")
drops = method_body(be, "public List<ItemStackHandler> getDroppableHandlers()")
has(drops, "assemblyPatternSlot, patternSlots, rsPatternSlots);",
    "E3b 掉落清单仍含总样板槽 + RS 样板输入槽（拆方块不丢东西）")

# E4 界面交互未变（总样板槽仍只出不进；RS 样板槽仍 3 个界面槽）
total_slot = method_body(menu, "private static final class TotalPatternSlot")
check("E4 菜单里总样板槽仍 mayPlace 恒 false（只出不进，本轮未放宽）",
      re.search(r"public boolean mayPlace\(final ItemStack stack\) \{\s*return false;", total_slot) is not None)
has(menu, "new net.neoforged.neoforge.items.SlotItemHandler(rsPatterns, i,",
    "E4b 3 格 RS 样板界面槽照旧（交互口径未变）")

# E5 判重 / 生成
has(be, "UnitPatternDedupe", "E5 UnitPatternDedupe 判重仍在终端生成路径上")
has(be, "public void refreshStepDuplicateCache()", "E5b 判重缓存刷新入口仍在")

# E6 本轮改动不越界（不碰被点名的文件 / 类）
for forbidden in ("AssemblyWatchdog", "RsccWireLinkSearch", "RsccBusInterference",
                  "RsccWireBlocks", "SequenceAssemblyExecutorScreen",
                  "net.minecraft.client"):
    hasnt(exposure, forbidden, "E6 暴露视图不引用被点名 / 客户端类：%s" % forbidden)
    hasnt(register_body, forbidden, "E6b 注册方法体不引用被点名 / 客户端类：%s" % forbidden)

# ======================================================================================
section("F. 结果")
print()
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
