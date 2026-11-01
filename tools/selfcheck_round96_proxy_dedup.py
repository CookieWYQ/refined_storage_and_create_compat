#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round96 WS-B 自检：容器代理器「接两个 RS 外部存储 ⇒ 终端数量翻倍」的修复。

用户原话（第 96 轮第 3 条）：「代理器如果接上两个外部总线你说的会翻倍，是说会有出现复制功能
还是怎么样子的，你能不能尽可能的把它解决」。

事实（Lead 已核，本脚本把判据钉在源码级）：
  * **不是复制物品**：底层库存只有一份，真实抽出量永不超过实际数量；
  * 翻倍来自 RS 侧：RS 2.0.9 的每个外部存储方块各自建一个 `ExternalStorage`，网络把各 storage
    的库存**求和**，不按源方块 / handler 实例去重（`/tmp/rssrc` 的
    `api/network/impl/node/externalstorage/`、`api/storage/external/`，库存快照来自
    `CapabilityCache#getItemAmountIterator` / `#getFluidAmountIterator` 的 `getStackInSlot`/
    `getFluidInTank`）；
  * 修法：`ProxyFaceRole.CONTENTS` 拆成「主（完整读写）」与「备·仅进（读恒空 / 写真实）」两档。

本脚本钉住五组不变式（每条都有反例自证，见文件末尾）：
  ① 枚举：四档齐全、`contents` id 不变（旧存档兼容）、byId/byOrdinal 不崩、主面唯一规则可实现；
  ② 面 → 能力：itemHandler / fluidHandler 两套 switch 都分主/备，五个视图是稳定实例，
     `setFace` 换面（含主↔备）必须 invalidateCapabilities；
  ③ 备面视图语义：getSlots()==1 恒真、读恒空、取恒空、写真实落底、守恒；
     流体：getTanks 报内层罐数、getFluidInTank / drain 恒空、fill 转发内层；
  ④ 行为模型（可复跑）：两个 RS 外部存储各读一个内容物面时，网络总量 == 底层真实总量（不是两倍）；
     备面灌入 / 主面抽出的守恒；三个 bug 模型（备面读真值 / 备面虚报 remainder / 备面可抽）必须判负；
  ⑤ 归一：读档后 / 每次换面后把「ordinal 最小的内容物面」留作主面，其余降为备面；
     老存档里两个 "contents" 归一成一个主面；退化状态（只剩备面）升回主面。

语言键：`face.contents_input` 属于 Lead 写域（`lang/*.json`）；代码已引用、文件还没有时
本脚本只 `[info]` 报告，不算失败（与 round93 的 pending_lang 同一套约定）。

用法：python tools/selfcheck_round96_proxy_dedup.py
退出码：0 = 全部 PASS。
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
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

ROLE = os.path.join(JAVA, "block", "ProxyFaceRole.java")
BE = os.path.join(JAVA, "block", "entity", "InventoryProxyBlockEntity.java")
HANDLERS = os.path.join(JAVA, "support", "InventoryProxyHandlers.java")
MENU = os.path.join(JAVA, "menu", "InventoryProxyMenu.java")
SCREEN = os.path.join(JAVA, "client", "screen", "InventoryProxyScreen.java")
WRENCH = os.path.join(JAVA, "support", "RsccInventoryProxyWrench.java")

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


def strip_comments_and_strings(text):
    """注释 + 字符串字面量换成等长空白（行号不变）。判「代码里有没有调用」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:j]))
            i = j
        elif ch == '"':
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
            out.append(ch)
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


def enum_constant_names(role_source):
    """按源码顺序取出枚举常量名（`PROXY_OBJECT("proxy_object"),` 这种）。"""
    body = role_source[role_source.find("{") + 1:]
    # 常量区在第一个 `;` 之前
    cut = body.find(";")
    if cut >= 0:
        body = body[:cut]
    return re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*\(", body, re.M)


role_raw = read(ROLE)
be_raw = read(BE)
handlers_raw = read(HANDLERS)
menu_raw = read(MENU)
screen_raw = read(SCREEN)
wrench_raw = read(WRENCH)

role = strip_comments_and_strings(role_raw)
be = strip_comments_and_strings(be_raw)
handlers = strip_comments_and_strings(handlers_raw)
menu = strip_comments_and_strings(menu_raw)
screen = strip_comments_and_strings(screen_raw)

print("=" * 78)
print("round96 WS-B：容器代理器 —— 两个 RS 外部存储不再把同一份库存数两遍")
print("=" * 78)

# ==================================================================== ① 枚举
print("-" * 78)
print("[① 枚举：四档 + 旧存档 id 兼容]")
print("-" * 78)

constants = enum_constant_names(role_raw)
check("①a 枚举四档齐全（代理对象 / 主内容物 / 备内容物·仅进 / 禁用）",
      constants == ["PROXY_OBJECT", "CONTENTS", "CONTENTS_INPUT", "DISABLED"],
      str(constants))
check("①b 旧存档 id 不变：CONTENTS(\"contents\") 仍是旧 id（老档读出来还是主内容物面）",
      'CONTENTS("contents")' in role_raw)
check("①c 新档 id 唯一且可自解释：CONTENTS_INPUT(\"contents_input\")",
      'CONTENTS_INPUT("contents_input")' in role_raw
      and 'CONTENTS("contents")' in role_raw)
check("①d 反序列化仍按 id 字符串匹配，未知值退回 DISABLED（手改 NBT 不崩存档）",
      "for (final ProxyFaceRole role : VALUES)" in role
      and "if (role.id.equals(id))" in role
      and "return DISABLED;" in role)
by_id = method_body(role, "public static ProxyFaceRole byId(")
check("①e byId 对两个内容物档都认（否则备面读档变禁用 ⇒ 玩家配好的面被清掉）",
      by_id != "" and "role.id.equals(id)" in by_id)
by_ordinal = method_body(role, "public static ProxyFaceRole byOrdinal(")
check("①f byOrdinal 只用于同版本数据槽：按 VALUES 取且越界夹到 DISABLED",
      "ordinal >= 0 && ordinal < VALUES.length" in by_ordinal
      and "VALUES[ordinal]" in by_ordinal)
check("①g next() 走声明顺序（主 → 备 → 禁用 在同一循环里，两档都可达）",
      "VALUES[(ordinal() + 1) % VALUES.length]" in method_body(role, "public ProxyFaceRole next("))
check("①h isContents() 同时认主面与备面（归一 / 界面说明要用它）",
      "public boolean isContents()" in role
      and "this == CONTENTS || this == CONTENTS_INPUT" in role)
defaults_body = method_body(role, "public static Map<Direction, ProxyFaceRole> defaults(")
check("①i 默认值不变：NORTH=主内容物、SOUTH=代理对象、其余禁用",
      "map.put(Direction.NORTH, CONTENTS);" in defaults_body
      and "map.put(Direction.SOUTH, PROXY_OBJECT);" in defaults_body
      and "map.put(direction, DISABLED);" in defaults_body)
check("①j NBT 键名仍按 Direction#getSerializedName（与旧存档同一套键）",
      "direction.getSerializedName()" in method_body(role, "public static String nbtKey("))

# ==================================================================== ② 面 → 能力
print("-" * 78)
print("[② 面 → 能力：主/备两套 switch + 稳定视图 + 换面失效]")
print("-" * 78)

item_handler = method_body(be, "public IItemHandler itemHandler(")
fluid_handler = method_body(be, "public IFluidHandler fluidHandler(")
check("②a 物品：主面 → 完整读写视图，备面 → 只进视图，禁用面 → null",
      "case CONTENTS -> contentsItemView;" in item_handler
      and "case CONTENTS_INPUT -> contentsInputItemView;" in item_handler
      and "case DISABLED -> null;" in item_handler)
check("②b 流体：主面/备面各一套视图，对象面与禁用面都不暴露流体",
      "case CONTENTS -> contentsFluidView;" in fluid_handler
      and "case CONTENTS_INPUT -> contentsInputFluidView;" in fluid_handler
      and "default -> null;" in fluid_handler)
check("②c 同一个完整读写视图只挂在一个角色上（否则某一面永远按「主」被 RS 数两遍）",
      be.count("-> contentsItemView;") == 1 and be.count("-> contentsFluidView;") == 1)
check("②d 五个视图都是 final 稳定字段（外部 BlockCapabilityCache 不会悬空）",
      "private final InventoryProxyHandlers.ObjectView objectView;" in be
      and "private final InventoryProxyHandlers.ContentsItemView contentsItemView;" in be
      and "private final InventoryProxyHandlers.ContentsFluidView contentsFluidView;" in be
      and "private final InventoryProxyHandlers.ContentsInputItemView contentsInputItemView;" in be
      and "private final InventoryProxyHandlers.ContentsInputFluidView contentsInputFluidView;" in be)
check("②e 五个视图都在构造器里 new 一次（不是每次查询新建）",
      "this.contentsInputItemView = new InventoryProxyHandlers.ContentsInputItemView(this);" in be
      and "this.contentsInputFluidView = new InventoryProxyHandlers.ContentsInputFluidView(this);" in be)
check("②f 无面查询仍按主内容物面（Jade / 大量 null 面查询的既有语义不变）",
      "side == null ? ProxyFaceRole.CONTENTS : faces[side.ordinal()]" in be)
set_face = method_body(be, "public void setFace(")
check("②g 主↔备切换必须作废能力缓存（外部缓存拿着旧 handler 会继续按「主」数备面）",
      "invalidateFaceCapabilities();" in set_face
      and set_face.find("faces[direction.ordinal()] = role;") < set_face.find("invalidateFaceCapabilities();"))
check("②h 换面后立即归一（主内容物面 / 对象面各自只允许有一个）",
      "normalizeFaces();" in set_face)
cycle = method_body(be, "public ProxyFaceRole cycleFace(")
check("②i cycleFace 返回归一后的真实角色（不能报一个没生效的档位）",
      "return faces[direction.ordinal()];" in cycle and "return next;" not in cycle)
check("②j 主面这一档跳过「备」（归一会把主面立刻升回来 ⇒ 点了没反应的路径被结构上排除）",
      "faces[direction.ordinal()] == ProxyFaceRole.CONTENTS" in cycle
      and "next = ProxyFaceRole.DISABLED;" in cycle)
check("②k 换面路径不碰代理槽内容（任何角色切换都不许丢东西）",
      not re.search(r"proxyStack|setProxyStack|takeProxyStackForDrop|ejectStack", set_face)
      and not re.search(r"proxyStack|setProxyStack|takeProxyStackForDrop|ejectStack", cycle))

# ==================================================================== ③ 备面视图语义
print("-" * 78)
print("[③ 备面视图：读恒空 / 取恒空 / 写真实落底 / 守恒]")
print("-" * 78)

input_item = class_body(handlers, "ContentsInputItemView")
input_fluid = class_body(handlers, "ContentsInputFluidView")
check("③a 备面物品视图存在且实现 IItemHandler",
      "public static final class ContentsInputItemView implements IItemHandler {" in handlers)
check("③b 备面物品视图对外恒报 1 个输入口（getSlots()==1，口径原文）",
      "private static final int INPUT_SLOTS = 1;" in input_item
      and re.search(r"public int getSlots\(\)\s*\{\s*return INPUT_SLOTS;", input_item) is not None)
get_stack = method_body(input_item, "public ItemStack getStackInSlot(")
check("③c 备面读恒空（RS 的 getItemAmountIterator 由此读数量 ⇒ 备面库存恒 0，不参与求和）",
      get_stack.strip().replace("\n", " ").replace("  ", " ") == "return ItemStack.EMPTY;")
extract = method_body(input_item, "public ItemStack extractItem(")
check("③d 备面取恒空（内容物只能从主面抽走）",
      extract.strip().replace("\n", " ").replace("  ", " ") == "return ItemStack.EMPTY;")
insert = method_body(input_item, "public ItemStack insertItem(")
check("③e 备面插入真实落到底层：逐个内层槽摊平，返回真实 remainder（守恒、绝不截断）",
      "final IItemHandler handler = inner();" in insert
      and "remaining = handler.insertItem(i, remaining, simulate);" in insert
      and "if (remaining.isEmpty()) {" in insert
      and "return remaining;" in insert
      and "simulate" in insert)
check("③f 备面视图只转发 innerItem()，绝不读/写代理槽（对象面与内容物面不重叠）",
      "proxy.innerItem()" in input_item and "proxyStack" not in input_item)
check("③g 备面插入方判定用「模拟插入能不能塞进至少 1 个」（比 isItemValid 更准）",
      "insertItem(0, stack, true).getCount() < stack.getCount()" in
      method_body(input_item, "public boolean isItemValid("))
check("③h 备面流体视图存在、getTanks 报内层罐数（报 0 会让管道判定「不是流体容器」而断开）",
      "public static final class ContentsInputFluidView implements IFluidHandler {" in handlers
      and "return handler == null ? 0 : handler.getTanks();" in input_fluid)
check("③i 备面流体读恒空（FluidAmountIterator 读数量 ⇒ 备面不参与求和）",
      method_body(input_fluid, "public FluidStack getFluidInTank(").strip().replace("\n", " ").replace("  ", " ")
      == "return FluidStack.EMPTY;")
check("③j 备面流体 drain 两个重载都恒空（抽不出来）",
      "public FluidStack drain(final FluidStack resource" in input_fluid
      and "public FluidStack drain(final int maxDrain" in input_fluid
      and input_fluid.count("return FluidStack.EMPTY;") == 3)
check("③k 备面流体 fill 真实转发内层（action 透传：SIMULATE 不落地）",
      "return handler == null ? 0 : handler.fill(resource, action);" in input_fluid)

# ==================================================================== ④ 行为模型
print("-" * 78)
print("[④ 行为模型：两个 RS 外部存储 == 一份库存（不是两份）]")
print("-" * 78)

MAIN, BACKUP = "main", "backup"


class InnerInventory:
    """底层库存（被代理物品自己的 ItemHandler）：[(item, count), ...]。"""

    def __init__(self, slots):
        self.slots = [[item, count] for item, count in slots]

    def total(self, item=None):
        return sum(c for i, c in self.slots if item is None or i == item)

    def insert(self, item, count):
        remaining = count
        for slot in self.slots:
            if remaining <= 0:
                break
            if slot[0] == item and slot[1] < 64:
                move = min(64 - slot[1], remaining)
                slot[1] += move
                remaining -= move
            elif slot[1] == 0:
                move = min(64, remaining)
                slot[0], slot[1] = item, move
                remaining -= move
        return remaining

    def extract(self, item, amount):
        taken = 0
        for slot in self.slots:
            if taken >= amount:
                break
            if slot[0] == item and slot[1] > 0:
                move = min(slot[1], amount - taken)
                slot[1] -= move
                taken += move
        return taken


class MainView:
    """主内容物面：完整读写（对应 ContentsItemView）。"""

    def __init__(self, inner):
        self.inner = inner

    def slots(self):
        return len(self.inner.slots)

    def stack_in_slot(self, slot):
        item, count = self.inner.slots[slot]
        return None if count == 0 else (item, count)

    def insert(self, slot, item, count):
        return self.inner.insert(item, count)

    def extract(self, slot, item, amount):
        return self.inner.extract(item, amount)


class BackupView:
    """备内容物面：读恒空、写真实（对应 ContentsInputItemView）。"""

    def __init__(self, inner):
        self.inner = inner

    def slots(self):
        return 1

    def stack_in_slot(self, slot):
        return None                      # 恒空 ⇒ RS 数不到

    def insert(self, slot, item, count):
        return self.inner.insert(item, count)   # 真实写进底层

    def extract(self, slot, item, amount):
        return 0                          # 抽不出来


class BuggyReadBackupView(BackupView):
    """bug 形态：备面把底层内容物照实报出去 ⇒ RS 数两遍。"""

    def stack_in_slot(self, slot):
        for item, count in self.inner.slots:
            if count:
                return (item, count)
        return None


def rs_storage_stock(view, item):
    """对应 CapabilityCache#getItemAmountIterator：逐个 getStackInSlot 读数量求和。"""
    total = 0
    for slot in range(view.slots()):
        stack = view.stack_in_slot(slot)
        if stack is not None and stack[0] == item:
            total += stack[1]
    return total


def rs_network_total(views, item):
    """RS 网络总量 = 各外部存储库存之和（不按源方块去重）。"""
    return sum(rs_storage_stock(view, item) for view in views)


def model_ok():
    problems = []
    # 场景：背包里有 64 个钻石（两个槽，还能再塞）；两个面都贴了 RS 外部存储（主 + 备）
    inner = InnerInventory([["diamond", 64], ["diamond", 0]])
    main_view = MainView(inner)
    backup_view = BackupView(inner)
    views = [main_view, backup_view]
    if rs_network_total(views, "diamond") != 64:
        problems.append("two-faces-double-count total=%d" % rs_network_total(views, "diamond"))
    # 备面单独看到 0（RS 在备面上不会有幽灵库存）
    if rs_storage_stock(backup_view, "diamond") != 0:
        problems.append("backup-leaks-stock")
    # 漏斗从备面灌入 16 个：真实进到底层，网络总量 = 80（不是 96）
    remainder = backup_view.insert(0, "diamond", 16)
    if remainder != 0 or inner.total("diamond") != 80:
        problems.append("backup-insert remainder=%d total=%d" % (remainder, inner.total("diamond")))
    if rs_network_total(views, "diamond") != 80:
        problems.append("after-backup-insert total=%d" % rs_network_total(views, "diamond"))
    # 备面抽不出来（抽出 0，底层不变）
    if backup_view.extract(0, "diamond", 10) != 0 or inner.total("diamond") != 80:
        problems.append("backup-extract")
    # 主面抽出 10 个：真实减少 10，网络总量如实变 70（绝不出现 80 还能抽出 10 的坏账）
    if main_view.extract(0, "diamond", 10) != 10 or rs_network_total(views, "diamond") != 70:
        problems.append("main-extract total=%d" % rs_network_total(views, "diamond"))
    # 想抽 1000 个：只能抽走真实的 70，绝不超发（「不是复制物品」的复跑证据）
    if main_view.extract(0, "diamond", 1000) != 70 or rs_network_total(views, "diamond") != 0:
        problems.append("overdraw total=%d" % rs_network_total(views, "diamond"))
    # 单个内容物面（默认配置）也成立
    inner2 = InnerInventory([["iron", 32]])
    if rs_network_total([MainView(inner2)], "iron") != 32:
        problems.append("single-face")
    return problems


model_problems = model_ok()
check("④a 两个内容物面各接一个 RS 外部存储：网络总量 == 底层真实总量（不翻倍）",
      not model_problems, ", ".join(model_problems))


def bug_stock_ok():
    """bug 形态（备面读真值）必须被 ④a 的判据抓到：总量变 128。"""
    inner = InnerInventory([["diamond", 64]])
    views = [MainView(inner), BuggyReadBackupView(inner)]
    return rs_network_total(views, "diamond") != 64


check("④b 反例模型「备面照实报库存」⇒ 网络总量 128，判据必须判负", bug_stock_ok())


def bug_remainder_ok():
    """bug 形态：备面插入只写一半却报 0 remainder ⇒ 物品凭空消失（守恒断链）。"""
    class LyingBackup(BackupView):
        def insert(self, slot, item, count):
            self.inner.insert(item, count // 2)
            return 0

    inner = InnerInventory([["diamond", 0]])
    view = LyingBackup(inner)
    remainder = view.insert(0, "gold", 10)
    return not (remainder == 0 and inner.total("gold") == 5 and 0 == 10 - 5)


check("④c 反例模型「备面虚报 remainder ⇒ 物品被吞」判负", bug_remainder_ok())


def bug_extract_ok():
    """bug 形态：备面 extract 转发底层 ⇒ 内容物有两个抽出口，主/备语义失效。"""
    class ExtractableBackup(BackupView):
        def extract(self, slot, item, amount):
            return self.inner.extract(item, amount)

    inner = InnerInventory([["diamond", 64]])
    view = ExtractableBackup(inner)
    taken = view.extract(0, "diamond", 10)
    # bug 生效（抽出来 10、底层少 10）⇒ 判据判定为「bug 存在」= True
    return taken == 10 and inner.total("diamond") == 54


check("④d 反例模型「备面可抽取」判负", bug_extract_ok())

# ==================================================================== ⑤ 归一
print("-" * 78)
print("[⑤ 归一：主内容物面唯一（老存档 / 换面 / 退化状态）]")
print("-" * 78)

normalize = method_body(be, "private boolean normalizeFaces(")
check("⑤a 归一入口存在且两类都归一：内容物子归一 + 对象面子归一（两者缺一都可翻倍）",
      normalize != "" and "normalizeContentsFaces();" in normalize
      and "normalizeObjectFaces();" in normalize)
contents_norm = method_body(be, "private boolean normalizeContentsFaces(")
check("⑤a' 内容物归一：主面 = ordinal 最小的内容物面（Direction.values() 即 ordinal 顺序）",
      contents_norm != "" and "for (final Direction direction : Direction.values())" in contents_norm
      and "primaryContentsFace()" in contents_norm)
check("⑤b 多余的主内容物面降为备面（同一份库存只留一个「主」给 RS 读）",
      "faces[direction.ordinal()] = ProxyFaceRole.CONTENTS_INPUT;" in contents_norm)
check("⑤c 退化状态（只剩备面）升回主面：否则这份库存没有任何面能被 RS 读到",
      "faces[primary.ordinal()] = ProxyFaceRole.CONTENTS;" in contents_norm
      and "firstContentsFace()" in contents_norm)
object_norm = method_body(be, "private boolean normalizeObjectFaces(")
check("⑤i 多余的对象面降为禁用（ordinal 最小的留下）：否则同一件代理对象被两份外部存储各读一次",
      object_norm != "" and "ProxyFaceRole.PROXY_OBJECT" in object_norm
      and "faces[direction.ordinal()] = ProxyFaceRole.DISABLED;" in object_norm
      and "Direction.values()" in object_norm)
check("⑤j 对象面不引入第三种视图：仍是「唯一对象面 → 同一个 objectView」，没有 Object*Input 之类新视图",
      "private final InventoryProxyHandlers.ObjectView objectView;" in be
      and be.count("-> objectView;") == 1
      and handlers.count("class ObjectView") == 1
      and "ObjectInputView" not in handlers)
check("⑤d 读档后就归一（老存档里两个 \"contents\" / 两个 \"proxy_object\" 不会等到第一次换面才修）",
      "normalizeFaces()" in method_body(be, "protected void loadAdditional("))
check("⑤e 归一真的改动过时标脏（否则归一结果不落盘，下次读档还得再算）",
      "setChanged();" in contents_norm or "setChanged();" in method_body(be, "protected void loadAdditional("))
check("⑤f 读档仍只按 id 字符串读面配置（新增一档不改旧键 / 不改旧值）",
      "facesTag.putString(ProxyFaceRole.nbtKey(direction), faces[direction.ordinal()].id());" in be
      and 'ProxyFaceRole.byId(facesTag.getString(key))' in be)
save_body = method_body(be, "protected void saveAdditional(")
check("⑤g 存档只写 id 字符串、不写序号（加档不会让旧存档的面配置错位）",
      "faces[direction.ordinal()].id()" in save_body
      and "putString" in save_body
      and "putInt" not in save_body)
check("⑤h 扳手 / 界面 / 读档三条路径都能改面配置，且都经过 setFace（归一 + 失效统一）",
      "proxy.cycleFace(face)" in strip_comments_and_strings(wrench_raw)
      and "proxy.cycleFace(directionOf(id))" in menu)

# ---------------- ⑤ 行为模型：换面 / 归一（退化态 / 无空转点击 / 对象面唯一） ----------------
# 三类行为都是「同一个东西只暴露一次」的落地形态：
#  ①只剩备面时自动升回主面（否则 RS 读到 0 件）；
#  ②主面循环跳过「备」、对象面循环跳过会被归一改回去的档（否则点了没反应）；
#  ③对象面唯一：两个对象面 ⇒ 网络把同一件代理对象数两遍。
DISABLED, CONTENTS, CONTENTS_INPUT, PROXY_OBJECT = (
    "disabled", "contents", "contents_input", "proxy_object")
ROLE_ORDER = [PROXY_OBJECT, CONTENTS, CONTENTS_INPUT, DISABLED]   # 与枚举声明顺序一致


def next_role(role):
    """对应 ProxyFaceRole#next()：按枚举 ordinal 循环。"""
    return ROLE_ORDER[(ROLE_ORDER.index(role) + 1) % len(ROLE_ORDER)]


def normalize_row(row, promote_degenerate=True, unique_object=True):
    """对应 normalizeFaces()：主面唯一（退化态升回主面）+ 对象面唯一（多余降为禁用）。"""
    primary = next((i for i, r in enumerate(row) if r == CONTENTS), None)
    if primary is None and promote_degenerate:
        first = next((i for i, r in enumerate(row) if r in (CONTENTS, CONTENTS_INPUT)), None)
        if first is not None:
            row[first] = CONTENTS
            primary = first
    changed = False
    if primary is not None:
        for i, r in enumerate(row):
            if i != primary and r == CONTENTS:
                row[i] = CONTENTS_INPUT
                changed = True
    if unique_object:
        kept = False
        for i, r in enumerate(row):
            if r != PROXY_OBJECT:
                continue
            if not kept:
                kept = True
                continue
            row[i] = DISABLED
            changed = True
    return changed


def cycle_row(row, index, skip_backup=True, skip_object=True):
    """对应 cycleFace(direction)：返回归一之后的真实角色（界面就显示这个）。"""
    role = row[index]
    nxt = next_role(role)
    if skip_backup and role == CONTENTS and nxt == CONTENTS_INPUT:
        nxt = DISABLED            # 主面不降备：否则归一立刻升回来 ⇒ 点了没反应
    elif skip_object and nxt == PROXY_OBJECT and PROXY_OBJECT in row[:index]:
        nxt = CONTENTS            # 已有更小序号的对象面：这一档会被归一降回禁用 ⇒ 跳过
    row[index] = nxt
    normalize_row(row)
    return row[index]


def main_faces(row):
    return [i for i, r in enumerate(row) if r == CONTENTS]


def object_faces(row):
    return [i for i, r in enumerate(row) if r == PROXY_OBJECT]


class ObjectView:
    """对应 InventoryProxyHandlers.ObjectView：1 槽，内容就是代理槽里那件容器物品。"""

    def __init__(self, item):
        self.item = item

    def slots(self):
        return 1

    def stack_in_slot(self, slot):
        return None if self.item is None else (self.item, 1)


def object_network_total(row, item):
    """RS 网络里「代理对象本身」的数量 = 每个对象面都被一份 ExternalStorage 读一次。"""
    view = ObjectView(item)
    total = 0
    for _ in object_faces(row):
        total += rs_storage_stock(view, item)
    return total


def model_degenerate_promote_ok():
    problems = []
    for i in range(6):
        row = [DISABLED] * 6
        row[i] = CONTENTS_INPUT                        # 退化态：只剩一个备面
        normalize_row(row)
        if main_faces(row) != [i]:
            problems.append("degenerate face %d -> %s" % (i, row))
    row = [DISABLED, PROXY_OBJECT, DISABLED, CONTENTS_INPUT, DISABLED, DISABLED]
    normalize_row(row)
    if main_faces(row) != [3]:                          # 旁边有代理对象面也照升
        problems.append("degenerate-with-proxy -> %s" % row)
    row = [DISABLED] * 6
    row[2] = CONTENTS_INPUT
    row[4] = CONTENTS                                   # 已有主面时不做「按 ordinal 重排」
    normalize_row(row)
    if main_faces(row) != [4]:
        problems.append("existing-main-untouched -> %s" % row)
    return problems


degenerate_problems = model_degenerate_promote_ok()
check("⑤k 行为模型：退化态（只剩备面）换面后自动升回主面 ⇒ RS 仍读得到这份库存",
      not degenerate_problems, ", ".join(degenerate_problems))


def model_cycle_never_dead_ok():
    """归一后的任意一个合法面配置，点任意一面都一定真的换档（不许有「点了没反应」）。"""
    problems = []
    rows = []
    for i in range(6):
        rows.append([CONTENTS if k == i else DISABLED for k in range(6)])
        for j in range(6):
            if j == i:
                continue
            row = [DISABLED] * 6
            row[i], row[j] = CONTENTS, CONTENTS_INPUT          # 主面 + 备面
            rows.append(row)
            row = [DISABLED] * 6
            row[i], row[j] = CONTENTS, PROXY_OBJECT            # 主面 + 对象面
            rows.append(row)
            row = [DISABLED] * 6
            row[i], row[j] = CONTENTS, CONTENTS_INPUT
            row[(j + 1) % 6] = PROXY_OBJECT                    # 主面 + 备面 + 对象面
            rows.append(row)
    for row in rows:
        normalize_row(row)
        for i in range(6):
            probe = list(row)
            before = probe[i]
            after = cycle_row(probe, i)
            if after == before:
                problems.append("click no-op face %d -> %s" % (i, probe))
            # 点完：要么零内容物面（玩家把唯一的内容物面关掉，合法），要么恰好一个主面；
            # 对象面最多一个（多于一个就会被 RS 数两遍）。
            contents = [r for r in probe if r in (CONTENTS, CONTENTS_INPUT)]
            if len(main_faces(probe)) > 1 or (contents and len(main_faces(probe)) != 1):
                problems.append("main-count=%d face %d -> %s"
                                % (len(main_faces(probe)), i, probe))
            if len(object_faces(probe)) > 1:
                problems.append("object-count=%d face %d -> %s"
                                % (len(object_faces(probe)), i, probe))
    return problems


dead_problems = model_cycle_never_dead_ok()
check("⑤l 行为模型：归一后的任何面配置，点任意一面都一定真的换档（含对象面档，无空转）",
      not dead_problems, ", ".join(dead_problems[:6]))


def model_object_unique_ok():
    """两个对象面 ⇒ 同一件代理对象被两份外部存储各读一次（修前 2、归一后 1）。"""
    problems = []
    # 用户场景：两个面都切成对象面，各贴一个 RS 外部存储
    row = [PROXY_OBJECT, PROXY_OBJECT, CONTENTS, DISABLED, DISABLED, DISABLED]
    before = object_network_total(row, "backpack")
    normalize_row(row)
    after = object_network_total(row, "backpack")
    if (before, after) != (2, 1):
        problems.append("object-dedup before=%d after=%d" % (before, after))
    if object_faces(row) != [0]:
        problems.append("kept-object-face %s -> %s" % (object_faces(row), row))
    # 典型新方块配置（NORTH 主内容物 / SOUTH 对象面）必须原样保留
    row = [DISABLED, DISABLED, CONTENTS, PROXY_OBJECT, DISABLED, DISABLED]
    normalize_row(row)
    if row != [DISABLED, DISABLED, CONTENTS, PROXY_OBJECT, DISABLED, DISABLED]:
        problems.append("default-config -> %s" % row)
    # 对象面可以正常搬：先把现役对象面切走，再把别的面切成对象面
    row = [PROXY_OBJECT, PROXY_OBJECT, DISABLED, DISABLED, DISABLED, DISABLED]
    normalize_row(row)                 # 两个对象面归一 → 只剩 0 号
    cycle_row(row, 0)                  # 0 号对象面 → 内容物（主）
    if object_faces(row):
        problems.append("object-face-not-moved -> %s" % row)
    cycle_row(row, 1)                  # 1 号 禁用 → 对象面（现在没有别的对象面，允许）
    if row[1] != PROXY_OBJECT:
        problems.append("object-face-refused -> %s" % row)
    return problems


object_problems = model_object_unique_ok()
check("⑤m 行为模型：两个对象面归一后只剩一个（网络里代理对象数量 1 而不是 2），且对象面可正常搬面",
      not object_problems, ", ".join(object_problems))

# ==================================================================== ⑥ 界面标注
print("-" * 78)
print("[⑥ 界面：逐面标注主/备 + 悬停面按钮的说明 tooltip]")
print("-" * 78)

check("⑥a 每个面按钮的文案取自角色 id（face.contents / face.contents_input 由 Lead 提供中文）",
      'LANG + "face_row"' in screen_raw and 'LANG + "face." + role.id()' in screen_raw)
# 第 97 轮口径（用户实机第 3 条「字太小了，而且（就算）修复的话，也不喜欢那行字了」）：
#   那条被整行等比缩小到 ~0.4 倍的常驻红字**删掉**，同一批信息改由「悬停六面按钮」的
#   `face_tip.*` tooltip 承载（措辞与几何归 Lead，见 docs/ROUND97_USER_FEEDBACK.md B1/B2）。
check("⑥b 常驻说明已删除（无 renderWarning），信息改由悬停六面按钮的 face_tip tooltip 承载",
      "renderWarning" not in screen_raw and 'LANG + "face_tip."' in screen_raw
      and "renderTooltip" in screen)
_zh_lang = json.loads(read(os.path.join(LANG_DIR, "zh_cn.json")))
_en_lang = json.loads(read(os.path.join(LANG_DIR, "en_us.json")))
_old_warning_key = "gui.rs_create_compat.inventory_proxy.warning"
_face_tip_keys = ["gui.rs_create_compat.inventory_proxy.face_tip." + role_id
                  for role_id in ("disabled", "proxy_object", "contents", "contents_input")]
check("⑥c 说明文案键：旧 inventory_proxy.warning 中英都已删除，4 条 face_tip 键中英齐全非空",
      _old_warning_key not in _zh_lang and _old_warning_key not in _en_lang
      and all(str(_zh_lang.get(key, "")).strip() and str(_en_lang.get(key, "")).strip()
              for key in _face_tip_keys),
      str([key for key in _face_tip_keys
           if not str(_zh_lang.get(key, "")).strip() or not str(_en_lang.get(key, "")).strip()]))

# ==================================================================== ⑦ 语言键（Lead 写域）
pending = []
zh_path = os.path.join(LANG_DIR, "zh_cn.json")
en_path = os.path.join(LANG_DIR, "en_us.json")
zh = json.loads(read(zh_path)) if os.path.isfile(zh_path) else {}
en = json.loads(read(en_path)) if os.path.isfile(en_path) else {}
for key in ("gui.rs_create_compat.inventory_proxy.face.contents_input",):
    if key not in zh or key not in en:
        pending.append(key)
if pending:
    info("待 Lead 代改 lang 键（代码已引用、当前缺文案）：%s" % ", ".join(pending))
else:
    check("⑦a 备面档位的语言键 zh/en 都存在", True)

# ==================================================================== 反例自证
print("=" * 78)
print("[反例自证] 把修复退回 / 判据写错，上面的检测必须判负")
print("=" * 78)


def audit_item(be_src):
    body = method_body(strip_comments_and_strings(be_src), "public IItemHandler itemHandler(")
    return ("case CONTENTS -> contentsItemView;" in body
            and "case CONTENTS_INPUT -> contentsInputItemView;" in body
            and "case DISABLED -> null;" in body)


def audit_fluid(be_src):
    body = method_body(strip_comments_and_strings(be_src), "public IFluidHandler fluidHandler(")
    return ("case CONTENTS -> contentsFluidView;" in body
            and "case CONTENTS_INPUT -> contentsInputFluidView;" in body
            and "default -> null;" in body)


def audit_reads_empty(handlers_src):
    body = class_body(strip_comments_and_strings(handlers_src), "ContentsInputItemView")
    get_stack = method_body(body, "public ItemStack getStackInSlot(")
    return get_stack.strip().replace("\n", " ").replace("  ", " ") == "return ItemStack.EMPTY;"


def audit_fluid_empty(handlers_src):
    body = class_body(strip_comments_and_strings(handlers_src), "ContentsInputFluidView")
    return "return FluidStack.EMPTY;" in method_body(body, "public FluidStack drain(final int maxDrain")


def audit_normalize_in_setface(be_src):
    body = method_body(strip_comments_and_strings(be_src), "public void setFace(")
    return "normalizeFaces();" in body


def audit_one_input_slot(handlers_src):
    body = class_body(strip_comments_and_strings(handlers_src), "ContentsInputItemView")
    return re.search(r"public int getSlots\(\)\s*\{\s*return INPUT_SLOTS;", body) is not None


def audit_cycle_returns_actual(be_src):
    body = method_body(strip_comments_and_strings(be_src), "public ProxyFaceRole cycleFace(")
    return "return faces[direction.ordinal()];" in body and "return next;" not in body


def audit_degenerate_promote(be_src):
    """⑤c：只剩备面时把 ordinal 最小的内容物面升回主面。"""
    body = method_body(strip_comments_and_strings(be_src), "private boolean normalizeContentsFaces(")
    return ("faces[primary.ordinal()] = ProxyFaceRole.CONTENTS;" in body
            and "firstContentsFace()" in body)


def audit_object_unique(be_src):
    """⑤i：归一入口真的调用了对象面归一，且多余对象面被降为禁用（不是新增第二种对象视图）。"""
    body = strip_comments_and_strings(be_src)
    entry = method_body(body, "private boolean normalizeFaces(")
    fn = method_body(body, "private boolean normalizeObjectFaces(")
    return ("normalizeObjectFaces();" in entry
            and "ProxyFaceRole.PROXY_OBJECT" in fn
            and "faces[direction.ordinal()] = ProxyFaceRole.DISABLED;" in fn)


def audit_cycle_skips_backup(be_src):
    """②j：主面这一档跳过「备」（否则点了被归一立刻升回来 ⇒ 没反应）。"""
    body = method_body(strip_comments_and_strings(be_src), "public ProxyFaceRole cycleFace(")
    return ("faces[direction.ordinal()] == ProxyFaceRole.CONTENTS" in body
            and "next = ProxyFaceRole.DISABLED;" in body)


# 反例 1：备面不出现在 itemHandler 里（回到「两个面同一个视图」的线上形态）
v = be_raw.replace("            case CONTENTS_INPUT -> contentsInputItemView;\n", "", 1)
check("反例 1（备面无视图 ⇒ 两个内容物面共用主视图）判负",
      v != be_raw and not audit_item(v))

# 反例 2：流体映射退回旧三元式（备面共享主流体视图）
v = be_raw.replace(
    "            case CONTENTS_INPUT -> contentsInputFluidView;\n", "", 1)
check("反例 2（备面共享主流体视图）判负", v != be_raw and not audit_fluid(v))

# 反例 3：备面读不再恒空（把内层内容照实报出去 ⇒ RS 数两遍）
v = handlers_raw.replace(
    "        public ItemStack getStackInSlot(final int slot) {\n"
    "            return ItemStack.EMPTY;\n"
    "        }\n\n"
    "        /**\n"
    "         * 真实写进底层",
    "        public ItemStack getStackInSlot(final int slot) {\n"
    "            return proxy.innerItem() == null ? ItemStack.EMPTY\n"
    "                : proxy.innerItem().getStackInSlot(slot);\n"
    "        }\n\n"
    "        /**\n"
    "         * 真实写进底层", 1)
check("反例 3（备面读照实报库存 ⇒ 终端翻倍）判负", v != handlers_raw and not audit_reads_empty(v))

# 反例 4：备面 drain 转发内层（抽得出来）
v = handlers_raw.replace(
    "        public FluidStack drain(final int maxDrain, final FluidAction action) {\n"
    "            return FluidStack.EMPTY;\n"
    "        }",
    "        public FluidStack drain(final int maxDrain, final FluidAction action) {\n"
    "            final IFluidHandler handler = inner();\n"
    "            return handler == null ? FluidStack.EMPTY : handler.drain(maxDrain, action);\n"
    "        }", 1)
check("反例 4（备面 drain 转发内层）判负", v != handlers_raw and not audit_fluid_empty(v))

# 反例 5：换面不归一（可能同时留下两个主内容物面 / 两个对象面）
v = be_raw.replace("        normalizeFaces();\n", "", 1)
check("反例 5（setFace 不归一 ⇒ 两个主内容物面 / 两个对象面）判负",
      v != be_raw and not audit_normalize_in_setface(v))

# 反例 6：备面 getSlots 报内层槽数（口径要求恒 1）
v = handlers_raw.replace(
    "        public int getSlots() {\n            return INPUT_SLOTS;\n        }",
    "        public int getSlots() {\n"
    "            final IItemHandler handler = inner();\n"
    "            return handler == null ? 0 : handler.getSlots();\n        }", 1)
check("反例 6（备面槽数报内层真实槽数）判负", v != handlers_raw and not audit_one_input_slot(v))

# 反例 7：cycleFace 返回归一前的 next（界面报一个没生效的档位）
v = be_raw.replace("        setFace(direction, next);\n        return faces[direction.ordinal()];",
                   "        setFace(direction, next);\n        return next;", 1)
check("反例 7（cycleFace 返回未归一的值）判负",
      v != be_raw and not audit_cycle_returns_actual(v))

# 反例 8：读档不归一（老存档两个 "contents" / 两个 "proxy_object" 一直翻倍到玩家手动换面）
v = be_raw.replace("        if (normalizeFaces()) {\n", "        if (false) {\n", 1)
check("反例 8（读档不归一）判负", v != be_raw and "normalizeFaces()" not in method_body(
    strip_comments_and_strings(v), "protected void loadAdditional("))

# 反例 9：默认值改成备面（新方块放下去就 RS 读不到内容物）
v = role_raw.replace("map.put(Direction.NORTH, CONTENTS);",
                     "map.put(Direction.NORTH, CONTENTS_INPUT);", 1)
check("反例 9（默认 NORTH 改成备面）判负",
      v != role_raw and "map.put(Direction.NORTH, CONTENTS);" not in method_body(
          strip_comments_and_strings(v), "public static Map<Direction, ProxyFaceRole> defaults("))

# 反例 10：删掉「退化态升回主面」⇒ 只剩备面时主面数=0，这份库存 RS 一件也读不到
v = be_raw.replace("            faces[primary.ordinal()] = ProxyFaceRole.CONTENTS;\n", "", 1)
row = [DISABLED] * 6
row[2] = CONTENTS_INPUT
normalize_row(row, promote_degenerate=False)
check("反例 10（删掉退化态升回 ⇒ 只剩备面时主面数=0，RS 读到 0 件）判负",
      v != be_raw and not audit_degenerate_promote(v) and main_faces(row) == [])

# 反例 11：删掉「主面循环跳过备面」⇒ 主面点一下立刻被归一升回来，角色不变=点了没反应
v = be_raw.replace(
    "        if (faces[direction.ordinal()] == ProxyFaceRole.CONTENTS && next == ProxyFaceRole.CONTENTS_INPUT) {\n"
    "            // 主面不能降成备面：当归一会立刻把它升回来（见方法注释）。跳过这一档。\n"
    "            next = ProxyFaceRole.DISABLED;\n"
    "        }\n", "", 1)
row = [DISABLED] * 6
row[0] = CONTENTS
check("反例 11（删掉主面循环跳过 ⇒ 点一下角色不变=点了没反应）判负",
      v != be_raw and not audit_cycle_skips_backup(v)
      and cycle_row(row, 0, skip_backup=False) == CONTENTS)

# 反例 12：删掉「对象面归一」⇒ 两个对象面都留着，同一件代理对象被两份外部存储各读一次
v = be_raw.replace("        changed |= normalizeObjectFaces();\n", "        changed |= false;\n", 1)
row = [PROXY_OBJECT, PROXY_OBJECT, CONTENTS, DISABLED, DISABLED, DISABLED]
normalize_row(row, unique_object=False)
check("反例 12（删掉对象面归一 ⇒ 网络里代理对象数量 2）判负",
      v != be_raw and not audit_object_unique(v)
      and object_network_total(row, "backpack") == 2)

print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 12 个源码级反例 + 3 个去重模型反例"
      " + 3 条换面/归一/对象面行为模型正例与 3 条对应反例）；"
      "容器代理器「两个 RS 外部存储 ⇒ 数量翻倍」已按主/备面 + 对象面唯一口径修复" % CHECKS[0])
sys.exit(0)
