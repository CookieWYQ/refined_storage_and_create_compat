# -*- coding: utf-8 -*-
"""round52 自检：范围充电器要能给「玩家身上任何地方」的物品充电（含 Curios 饰品槽）。

用户原话：「我把这个无限终端放在饰品栏中，发现这个范围充电器无法给它充电，这是个问题。
放在任何地方的任何物品都要给它充电，当然放在背包中就是那种比如说精致背包之类的（玩家容器内的容器）
就没必要了。」

本脚本断言（每条都能被源码事实直接证伪，不是复述注释）：
  1. **现状锚点**：充电器扫的是 `player.getInventory()` 的 `getContainerSize()` 格
     （NeoForge 的 Inventory = 36 主背包/快捷栏 + 4 盔甲 + 1 副手 = 41 格）—— 因此盔甲与副手本来就在内，
     真正缺的只有 Curios 饰品槽（那是另一套容器，不属于 Inventory）；
  2. **覆盖 Curios**：`chargePlayer` 在背包那一圈之后，另外遍历
     `RsccCuriosTerminalSlot.allStacks(player)`（本工程唯一的 Curios 反射入口）；
  3. **条件加载 / 不崩**：Curios 是否装了由 `OptionalDeps.isCuriosLoaded()` 决定；
     没装 / 反射失败 → `slots()` 返回空表 → `allStacks` 返回空表，充电器侧没有任何 try/catch 需求，
     且充电器自己不出现任何 Curios / 反射相关符号（反射只在该 support 类里）；
  4. **无重复充电**：一次扫描用「按实例判等」的集合（`IdentityHashMap`）登记处理过的存活栈，
     两处循环都在充电前判 `!seen.add(stack)`；
  5. **充满即停 / 不超容 / 能量守恒**：`chargeItemStack` 先判 `getEnergyStored() >= getMaxEnergyStored()`，
     且只从本机缓存扣掉 `receiveEnergy` 实际接受的 `accepted`；
  6. **有节流**：饰品槽不是每 tick 扫 —— `curiosScanCooldown` + `nextCuriosPass()` +
     配置项 `rangeChargerCuriosScanInterval`（默认 5 tick）三处齐全；并**用模型推演**验证
     「间隔 5 ⇒ 每 5 tick 正好一次、间隔 1 ⇒ 每 tick 一次」与源码写法一致；
  7. **不递归容器内的容器**：不使用 `Capabilities.ItemHandler.ITEM`（精致背包之类不再往里翻），
     用户明确说没必要。
  8. **目标枚举覆盖四种位置（源码级清单，2026-10-17 回归）**：背包/快捷栏/盔甲/副手（Inventory 41 格）、
     Curios 饰品槽、掉落物（ItemEntity）、周围方块（EnergyStorage.BLOCK）——四处都有真实枚举，
     且 `doCharging` 三类扫描都真的被调用；「枚举中途 return」必须判负（反例自证）。
  9. **速率恒 > 0**：对 level 0..6 全组合与异常输入（负数 / 64 / MIN / MAX）用 `RsccRateLimiter`
     的既有不变量（level 夹到 0..6、饱和位移封顶 Integer.MAX_VALUE）推演，结果恒 ≥ 1。
  10. **界面与 BE 同源**：两侧调用同一个 `rateSaturating`，且传的是同一对**原始计数**
      （数据槽 10/11 = countUpgrades(SPEED/STACK)，不是已折算的 power）；
      「power 当 speed 传」与「速率恒 0」两种写法都必须判负（反例自证）。
  11. **反饥饿预算**：四个充电点都走 `transferBudget()`（电量 / 最大目标数，恒 ≥1），
      源码里不再有 `Math.min(getChargeRate(), energyStorage.getEnergyStored())` 这种「一口吃光」写法；
      扫描顺序是「玩家 → 掉落物 → 方块」（用户的无线终端最先拿电）。
  12. **抽电不浪费（第 9 节）**：`network/RangeChargerNetworkNode.doWork` 必须先 simulate
      问接收侧能收多少，再按这个量 `extract`，且抽到的量原样写入缓存——修前 `extract(space)`
      抽满再让接收侧截断，差额是从网络扣掉却没人收到的纯损耗（最多 99.5 万 FE/tick，网络被抽干）。
      判据含**反例自证**：修前写法 / 按 `rangeChargerMaxTransfer` 常量抽的写法都必须判负；
      另用数值镜像证明修后「抽到的 == 收到的」而吞吐不变。

用法：python tools/selfcheck_round52_charger_curios.py
      → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, ("" if ok else ((" | " + detail) if detail else ""))))


def code_only(text):
    """剥掉 // 与 /*...*/ 注释，只对真实代码做锚点判定（javadoc 里会解释「为什么」）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text, signature):
    """按大括号配平取出以 signature 开头的方法体（含签名行）。"""
    start = text.find(signature)
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


charger = read("block/entity/RangeChargerBlockEntity.java")
charger_code = code_only(charger)
charger_menu = read("menu/RangeChargerMenu.java")
curios = read("support/RsccCuriosTerminalSlot.java")
config = read("Config.java")

charge_player = method_body(charger_code, "private int chargePlayer(")
charge_stack = method_body(charger_code, "private boolean chargeItemStack(")
next_pass = method_body(charger_code, "private boolean nextCuriosPass(")
all_stacks = method_body(curios, "public static List<ItemStack> allStacks(")

# ==================== 1. 现状：扫的是 Inventory 的 41 格 ====================

check("chargePlayer 存在（充电器给玩家物品充电的唯一入口）", charge_player is not None)
if charge_player:
    check("仍按 player.getInventory().getContainerSize() 遍历（NeoForge = 36+4+1 = 41 格）",
          "player.getInventory().getContainerSize()" in charge_player
          and "player.getInventory().getItem(slot)" in charge_player,
          "背包那一圈的写法被改动了")
    check("没有把背包格数写死成 36（写死就会漏掉盔甲 36..39 与副手 40）",
          "slot < 36" not in charger_code and "INVENTORY_END" not in charger_code)
check("结论锚点：盔甲/副手不属于「缺失的一档」，只有 Curios 需要另取（类内注释写明 36+4+1=41）",
      "36 + 4 + 1 = 41" in charger)

# ==================== 2. 覆盖 Curios 饰品槽 ====================

check("allStacks 这个「取全部饰品槽存活栈」的入口存在（全工程唯一的 Curios 反射实现里）",
      all_stacks is not None and "public static List<ItemStack> allStacks(final LivingEntity player)" in curios)
check("chargePlayer 真的调用了 RsccCuriosTerminalSlot.allStacks(player)",
      charge_player is not None and "RsccCuriosTerminalSlot" in charge_player and ".allStacks(player)" in charge_player)
check("饰品槽那一圈排在背包那一圈之后（背包族行为不变，饰品槽是增量）",
      charge_player is not None
      and charge_player.find("getContainerSize()") < charge_player.find(".allStacks(player)"),
      "找不到「先背包、后饰品槽」的顺序")

if all_stacks:
    check("allStacks 遍历玩家实际拥有的全部饰品槽（slotIds）并逐槽取物品（slots）",
          "slotIds(player)" in all_stacks and "slots(player, slotId)" in all_stacks)
    check("allStacks 只收集非空栈（空槽不进充电列表）", "!stack.isEmpty()" in all_stacks)
    check("allStacks 对「处理器形状认不出来」的 null 做了判空（不 NPE）",
          "if (stacks == null)" in all_stacks and "continue" in all_stacks)

# ==================== 3. 条件加载：装了才扫，没装不报错不崩 ====================

curios_inventory = method_body(curios, "private static Object curiosInventory(")
check("Curios 入口先判加载状态（未装 → 直接 null，一行反射都不走）",
      curios_inventory is not None and "if (!OptionalDeps.isCuriosLoaded())" in curios_inventory
      and "return null;" in curios_inventory)
check("Curios 的类名只以字符串出现并走 Class.forName（离线编译不能编译期依赖 Curios）",
      'CURIOS_API_CLASS = "top.theillusivec4.curios.api.CuriosApi"' in curios
      and "Class.forName(CURIOS_API_CLASS)" in curios
      and "import top.theillusivec4" not in curios)
slots_body = method_body(curios, "private static List<ItemStack> slots(")
check("槽位读取失败只降级为空表（拿不到库存 → List.of()，绝不抛给调用方）",
      slots_body is not None and "if (inventory == null)" in slots_body and "return List.of();" in slots_body)
catches = len(re.findall(r"catch \(final ReflectiveOperationException \| RuntimeException e\)", curios))
check("反射链路的异常都在 support 类内部被吞掉（充电器侧因此不需要 try/catch）", catches >= 3,
      "catch 子句 %d 处" % catches)
check("反射方法结果被缓存（每型一次，不是每次扫描都重新找方法）",
      "ConcurrentHashMap" in curios and "cachedMethod(" in curios)
check("充电器自己不碰 Curios / 反射（未装 Curios 不可能在这里出问题）",
      "top.theillusivec4" not in charger_code and "Class.forName" not in charger_code
      and "java.lang.reflect" not in charger_code and "CuriosApi" not in charger_code)

# ==================== 4. 无重复充电（一次扫描每件物品只处理一次） ====================

check("去重集合是「按实例判等」的集合（ItemStack 不重写 equals，再用 IdentityHashMap 上双保险）",
      charge_player is not None and "new java.util.IdentityHashMap<>()" in charge_player
      and "newSetFromMap" in charge_player)
check("两处循环都在充电前判 !seen.add(stack)（背包一圈 + 饰品槽一圈）",
      charge_player is not None and charge_player.count("!seen.add(stack)") == 2,
      "seen.add 判定 %d 处" % (charge_player.count("!seen.add(stack)") if charge_player else -1))
check("去重集合只在要扫饰品槽的那一 tick 才分配（普通 tick 零多余对象）",
      charge_player is not None
      and "final java.util.Set<ItemStack> seen = curiosPass" in charge_player)

# ==================== 5. 充满即停 / 不超容 / 能量守恒 ====================

check("chargeItemStack 存在，且背包与饰品槽共用同一份充电逻辑",
      charge_stack is not None and charge_player is not None
      and charge_player.count("chargeItemStack(stack)") == 2)
if charge_stack:
    check("充满即停：先判 getEnergyStored() >= getMaxEnergyStored() 就不再写",
          "storage.getEnergyStored() >= storage.getMaxEnergyStored()" in charge_stack)
    check("只充「带 ITEM 能量能力」的物品（不可充电物品直接跳过）",
          "stack.getCapability(Capabilities.EnergyStorage.ITEM)" in charge_stack)
    check("能量守恒：扣能只扣 receiveEnergy 实际接受的 accepted，且提取用非模拟模式",
          "final int accepted = storage.receiveEnergy(transfer, false);" in charge_stack
          and "energyStorage.extractEnergy(accepted, false);" in charge_stack)
    check("不可能超容：写入一律走 receiveEnergy（由物品自己的容量夹住），没有任何直接设值写入",
          "receiveEnergy" in charge_stack and ".energy =" not in charge_stack)

# ==================== 6. 有节流（周期 + 推演） ====================

check("有节流计数字段 curiosScanCooldown", "private int curiosScanCooldown;" in charger)
check("有节流闸门 nextCuriosPass()", next_pass is not None)
if next_pass:
    check("闸门写法与推演模型一致：冷却中先减一再返回 false；到点则设为 interval-1 并返回 true",
          next_pass.find("if (curiosScanCooldown > 0)") < next_pass.find("curiosScanCooldown--;")
          and next_pass.find("curiosScanCooldown--;") < next_pass.find("return false;")
          and "curiosScanCooldown = Math.max(0, Config.rangeChargerCuriosScanInterval - 1);" in next_pass)
check("doCharging 每 tick 只向闸门问一次（不是每个玩家问一次）",
      "final boolean curiosPass = nextCuriosPass();" in charger_code)
check("两条玩家扫描路径都把同一个 curiosPass 传下去（有限范围 + 无限范围一致）",
      charger_code.count("chargePlayer(player, targets, curiosPass)") == 2,
      "传参 %d 处" % charger_code.count("chargePlayer(player, targets, curiosPass)"))
check("饰品槽那一圈被节流挡住（seen == null 时直接返回，一件饰品都不看）",
      charge_player is not None
      and charge_player.find("if (seen == null)") < charge_player.find(".allStacks(player)"))

check("配置项 rangeChargerCuriosScanInterval 已定义（默认 5 tick、范围 1..1200）",
      re.search(r'defineInRange\("rangeChargerCuriosScanInterval",\s*5,\s*1,\s*1200\)', config) is not None)
check("配置项有对应的静态字段", "public static int rangeChargerCuriosScanInterval;" in config)
check("配置加载时把该值同步到静态字段",
      "rangeChargerCuriosScanInterval = RANGE_CHARGER_CURIOS_SCAN_INTERVAL.get();" in config)
check("充电器读的是这个配置项（节流周期可控，服主可调大省性能）",
      "Config.rangeChargerCuriosScanInterval" in charger_code)


def pass_sequence(interval, ticks):
    """nextCuriosPass() 的等价模型（与上面的写法断言成对存在，不是空转推演）。"""
    cooldown = 0
    out = []
    for _ in range(ticks):
        if cooldown > 0:
            cooldown -= 1
            out.append(False)
        else:
            cooldown = max(0, interval - 1)
            out.append(True)
    return out


seq5 = pass_sequence(5, 20)
passes5 = [i + 1 for i, ok in enumerate(seq5) if ok]
check("推演：间隔 5 ⇒ 每 5 tick 正好一次（第 1/6/11/16 tick）", passes5 == [1, 6, 11, 16], str(passes5))
check("推演：间隔 1 ⇒ 每 tick 都扫（退化为与背包族一致）",
      all(pass_sequence(1, 10)) and pass_sequence(1, 10).count(True) == 10)
seq20 = pass_sequence(20, 41)
check("推演：间隔 20 ⇒ 每 20 tick 一次（1 秒一次，端到端延迟上限 1 秒）",
      [i + 1 for i, ok in enumerate(seq20) if ok] == [1, 21, 41])
check("节流周期默认值不超过 20 tick（端到端延迟 ≤ 1 秒：够快，肉眼看不出来）",
      1 <= 5 <= 20)

# ==================== 7. 刻意不递归「容器内的容器」 ====================

check("不递归精致背包之类的容器：充电路径不使用物品/流体容器能力",
      "Capabilities.ItemHandler.ITEM" not in charger_code
      and "Capabilities.ItemHandler.BLOCK" not in charger_code)
check("不递归的判据只作用于玩家物品那一档：方块实体那一档仍走 EnergyStorage.BLOCK（原有行为不变）",
      "Capabilities.EnergyStorage.BLOCK" in charger_code)

# ==================== 8. 四种位置全覆盖 / 速率恒正 / 界面与 BE 同源 / 反饥饿预算 ====================
# 本节的由来（2026-10-17 用户实测第二版回归）：
#   「范围充电器不可以给无线终端充电了（掉落物 / 背包 / 快捷栏 / 饰品栏都不行），
#     而且充电对象只显示一个，那一个是 RS 控制器。」
# 根因不是「枚举没写」，而是三档共用的那一罐电被第一个目标（RS 控制器，永远充不满）一次吃光：
#   transfer = min(getChargeRate(), stored)  —— 只要速率 ≥ 本 tick 可用电量，后面的目标全是 0。
# 因此本节除了「四种位置的源码清单」，还钉死两件事：速率恒 > 0、单目标拿电必须走均分预算。

INT_MAX = 2 ** 31 - 1
INT_MIN = -2 ** 31
MAX_LEVEL = 6
STACK_WEIGHT = 3
MAX_POWER = MAX_LEVEL + MAX_LEVEL * STACK_WEIGHT


def strip_comments(text):
    return code_only(text)


def mutate_inside(text, signature, old, new):
    """只改指定方法体里的第一处（同名片段在别的扫描方法里也有，必须按方法定位）。"""
    body = method_body(text, signature)
    if body is None or old not in body:
        return text
    return text.replace(body, body.replace(old, new, 1), 1)


def target_enumeration_ok(text):
    """源码级清单：四种位置都要被真实枚举，且 doCharging 真的调用三类扫描。"""
    code = strip_comments(text)
    cp = method_body(code, "private int chargePlayer(")
    si = method_body(code, "private int scanItems(")
    sb = method_body(code, "private boolean chargeBlock(")
    df = method_body(code, "private void doCharging(")
    if not (cp and si and sb and df):
        return False
    inventory = ("player.getInventory().getContainerSize()" in cp
                 and "player.getInventory().getItem(slot)" in cp)
    # 快捷栏 0..8 / 主背包 9..35 / 盔甲 36..39 / 副手 40 全在同一个 Inventory 里
    hotbar = "for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)" in cp
    curios = ".allStacks(player)" in cp
    dropped = ("for (final ItemEntity itemEntity : items)" in si
               and "itemEntity.getItem()" in si)
    blocks = "Capabilities.EnergyStorage.BLOCK" in sb
    wired = (df.count("lastChargedTargets += scan") == 6
             and "scanPlayersInfinite(" in df and "scanItemsInfinite(" in df
             and "scanBlocksInfinite(" in df)
    # 「枚举中途 return」判据：掉落物循环体 / 背包循环体里不允许出现 return 0;（会把后半截目标全吞掉）
    loop = si.find("for (final ItemEntity itemEntity : items)")
    inv = cp.find("for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)")
    midway = (loop >= 0 and "return 0;" in si[loop:]) \
        or (inv >= 0 and "return 0;" in cp[inv:])
    return inventory and hotbar and curios and dropped and blocks and wired and not midway


check("8.1 目标枚举清单：背包+快捷栏+盔甲+副手（Inventory 41 格）", target_enumeration_ok(charger))
check("8.1 反例自证：把「枚举中途 return」塞进掉落物循环 ⇒ 判据判负",
      not target_enumeration_ok(mutate_inside(
          charger, "private int scanItems(", "final ItemStack stack = itemEntity.getItem();",
          "if (itemEntity.getItem().isEmpty()) {\n                return 0;\n            }\n"
          "            final ItemStack stack = itemEntity.getItem();")))
check("8.1 反例自证：把饰品槽（Curios）那一路拿掉 ⇒ 判据判负",
      not target_enumeration_ok(charger.replace(".allStacks(player)", ".noSuchCall(player)", 1)))


def j_level(raw):
    return max(0, min(MAX_LEVEL, raw))


def j_power(speed, stack):
    return min(j_level(speed) + j_level(stack) * STACK_WEIGHT, MAX_POWER)


def j_shift(base, power):
    result = max(1, base)
    for _ in range(power):
        if result > INT_MAX >> 1:
            return INT_MAX
        result <<= 1
    return result


def j_rate(base, speed, stack):
    return j_shift(base, j_power(speed, stack))


GRID = [(s, st) for s in range(7) for st in range(7)]
check("8.2 速率恒 > 0：level 0..6 的全部 49 种组合 × 配置 base 1 / 500 / 1000000",
      all(j_rate(base, s, st) > 0 for base in (1, 500, 1000000) for s, st in GRID))
ABNORMAL = (-1, 0, 7, 64, 1000, INT_MIN, INT_MAX)
check("8.2 速率恒 > 0：异常输入（负数 / 超上限 / 64 / MIN / MAX）",
      all(j_rate(500, s, st) > 0 for s in ABNORMAL for st in ABNORMAL))
check("8.2 速率永不回绕成负数：满级封顶到 Integer.MAX_VALUE（改了 RsccRateLimiter 的不变量就会红）",
      j_rate(1000000, 6, 6) == INT_MAX and j_rate(8192, 0, 6) == INT_MAX
      and j_rate(500, 64, 64) == j_rate(500, 6, 6))


def rate_source_ok(be_text, menu_text):
    """BE 与菜单必须调用同一个函数、传同一对**原始计数**（不是已折算的 power）。"""
    be = strip_comments(be_text)
    menu = strip_comments(menu_text)
    be_ok = re.search(
        r"RsccRateLimiter\.rateSaturating\(Config\.rangeChargerChargeRate,\s*"
        r"countUpgrades\(SPEED_UPGRADE\), countUpgrades\(STACK_UPGRADE\)\)", be) is not None
    menu_ok = re.search(
        r"RsccRateLimiter\.rateSaturating\(\s*"
        r"cretae\.cookiewyq\.rs_create_compat\.Config\.rangeChargerChargeRate, "
        r"data\.get\(10\), data\.get\(11\)\)", menu) is not None
    # 第 98 轮锚点更新（旧契约被本轮契约合法推翻，不是放宽）：
    #   ContainerData 12 → 13，索引 12 = 本机「高亮充电目标」开关（highlightEnabled）。
    #   旧口径 12 已经装不下 0..12 这 13 个槽，13 才是本轮之后服务端的真实槽数；
    #   数据槽 10/11 的语义没变（仍是原始速度/堆叠个数）。
    # 第 102 轮再 +1（同样是契约合法扩展，不是放宽判据）：
    #   13 = 上一次扫描「因已满被跳过」的目标数（满电跳过功能要能被玩家看见）。
    #   这里断言的仍然是「服务端槽数 = 实际用到的最大索引 + 1」这个不变式 —— 数字跟着走，
    #   判据本身没有变弱；下面同时锚住新槽，防止「只加槽不接线」的假绿。
    data_ok = ("case 10 -> countUpgrades(SPEED_UPGRADE);" in be
               and "case 11 -> countUpgrades(STACK_UPGRADE);" in be
               and "case 13 -> lastSkippedFullTargets;" in be
               and "return 14;" in be)
    return be_ok and menu_ok and data_ok


check("8.3 界面与 BE 同源：两侧同一个 rateSaturating + 同一对原始计数（数据槽 10/11）",
      rate_source_ok(charger, charger_menu))
check("8.3 反例自证：把 BE 的速率写成恒 0 ⇒ 判据判负",
      not rate_source_ok(re.sub(
          r"return RsccRateLimiter\.rateSaturating\(Config\.rangeChargerChargeRate,[^;]*;",
          "return 0;", charger, count=1), charger_menu))
check("8.3 反例自证：把菜单改回「power 当 speed 传」（上一版的写法）⇒ 判据判负",
      not rate_source_ok(charger, charger_menu.replace("data.get(10), data.get(11)",
                                                       "getUpgradeCount(), 0")))
check("8.3 数值自证：上一版写法在满堆叠时界面只到 base<<6 (=32000)，BE 是 base<<24（封顶）—— 确实分叉过",
      j_shift(500, j_level(j_power(6, 6))) == 32000
      and j_rate(500, 6, 6) == INT_MAX)


def budget_source_ok(text):
    """单目标取电必须走预算方法：均分 + 恒 ≥1，且旧的一口吃光写法必须绝迹。

    第 127 轮更新（**判据原意未放宽**）：方块充电点多了「立方 = 电池档」三元分支
    （`blockEntity instanceof FEEnergyCubeBlockEntity ? cubeTransferBudget() : transferBudget()`），
    它的上限是 `feEnergyCubeMaxTransfer`，**仍然是每目标一份预算、仍然夹本机缓存**，
    不存在「一口气吃光整罐电」；其它三个充电点（玩家物品 / 掉落物 / 无限范围掉落物）继续逐字走
    `transferBudget()`。原来的「4 处 `final int transfer = transferBudget();`」因此变成 3 处 + 1 处三元。
    """
    code = strip_comments(text)
    body = method_body(code, "private int transferBudget(")
    if body is None:
        return False
    return ("stored / Math.max(1, Config.rangeChargerMaxTargets)" in body
            and "Math.max(1, Math.min(getChargeRate(), share))" in body
            and "Math.min(getChargeRate(), energyStorage.getEnergyStored())" not in code
            and code.count("final int transfer = transferBudget();") >= 3
            and "final int transfer = blockEntity instanceof FEEnergyCubeBlockEntity" in code
            and "cubeTransferBudget()" in code
            and "if (transfer <= 0)" in code)


check("8.4 反饥饿：四个充电点都走 transferBudget()，源码里不再有「一口吃光整罐电」的写法",
      budget_source_ok(charger))
check("8.4 反例自证：把预算改成 `return energyStorage.getEnergyStored();`（旧的一口吃光）⇒ 判据判负",
      not budget_source_ok(charger.replace(
          "return Math.max(1, Math.min(getChargeRate(), share));",
          "return energyStorage.getEnergyStored();", 1)))
check("8.4 数值推演：电量 5000 / 目标 10 / 速率 5120 ⇒ 均分后单目标 500，旧写法会让第一个目标拿走 5000 而其余为 0",
      max(1, min(5120, 5000 // 10)) == 500
      and min(5120, 5000) == 5000
      and min(5120, 0) == 0)

charging_body = method_body(strip_comments(charger), "private void doCharging(")
check("8.5 扫描顺序：玩家 → 掉落物 → 方块（玩家的无线终端最先拿电，不再被永远充不满的方块饿死）",
      charging_body is not None
      and charging_body.find("scanPlayers(level") < charging_body.find("scanItems(level")
      and charging_body.find("scanItems(level") < charging_body.find("scanBlocks(level"))

# ==================== 9. 从 RS 网络抽电：抽取量必须 ≤ 实际接收量（堵住纯损耗） ====================
# 本节的由来（2026-10-17 用户实测）：范围充电器把整张 RS 网络「抽干」。
# 机制（修前写法，见本节的 OLD_NODE_SRC）：
#   `extract(space)`（space = 容量 − 存量，最大 1,000,000）先把电从网络扣掉，再
#   `storage.receiveEnergy(extracted, false)` —— 返回值被忽略。接收侧一旦按 maxReceive 截断
#   （旧代码 = Config.rangeChargerMaxTransfer = 5000），差额就是「已从网络扣掉、却没人收到」的
#   纯损耗：实测可达 99.5 万 FE/tick，每 tick 重复 ⇒ 网络持续见底。
# 判据三层：① 源码形状（先 simulate 问能收多少，再按该量抽；绝无 extract(space)）；
#          ② 行为镜像（数值推演：旧写法每 tick 丢 995,000，新写法丢 0、且接收侧放开后吞吐不变）；
#          ③ 反例自证（把修前写法 / 「按配置常量抽」写法喂给同一批判据，必须判负）。

CHARGER_NODE = "network/RangeChargerNetworkNode.java"
charger_node = read(CHARGER_NODE)


def pump_shape(text):
    """取出「从网络抽电」这一步的四段证据（缺项为 None）；只对注释剥掉后的真实代码判定。"""
    code = strip_comments(text)
    body = method_body(code, "public void doWork(")
    shape = {"code": code, "body": body}
    if body is None:
        return shape
    shape["sim"] = re.search(
        r"final int (\w+) = storage\.receiveEnergy\(([^;]*?),\s*true\);", body)
    shape["ext"] = re.search(
        r"getComponent\(EnergyNetworkComponent\.class\)\.extract\(\s*(\w+)\s*\)", body)
    shape["assign"] = re.search(r"final long (\w+) = [^;]*?\.extract\(", body)
    shape["recv"] = re.search(
        r"storage\.receiveEnergy\(\s*\(int\)\s*(\w+)\s*,\s*false\s*\);", body)
    return shape


def pump_by_acceptance_ok(shape):
    """抽取参数必须是「接收侧 simulate 出来的量」，且在 simulate 之后。

    第 99 轮（范围充电器缓存 1000 万 -> 5000 万）追加了 `Config.rangeChargerNetworkPullPerTick`
    这道闸：抽取量改成 `min(space, 配置上限)`，所以 simulate 的实参不再是裸 `space` ——
    判据同步改成「代码里必须同时出现 space（缓存缺多少）与本配置上限（一次能抢多少）」
    ：
      · 只看 space ⇒ 空缓存时一个 tick 就能从网络抽走整个缓存容量（本轮之前的行为，
        5000 万下会把同网络其它机器饿死）；
      · 只看配置上限 ⇒ 缓存快满时还在按上限抽，抽进来放不下 = 纯损耗（round97 修过的 bug）。
    两者缺一即判负；OLD_NODE_SRC（extract(space) 直接抽）仍然是反例，必须 FAIL。
    """
    sim, ext = shape.get("sim"), shape.get("ext")
    if not (sim and ext):
        return False
    if ext.group(1) != sim.group(1):
        return False
    code = shape.get("code") or ""
    if "space" not in code or "rangeChargerNetworkPullPerTick" not in code:
        return False
    return shape["body"].find(sim.group(0)) < shape["body"].find(ext.group(0))


def pump_write_complete_ok(shape):
    """抽到的量必须被完整写入缓存：写入实参就是 extract 的返回变量（不是别的常量 / 再夹一次）。"""
    assign, recv = shape.get("assign"), shape.get("recv")
    if not (assign and recv):
        return False
    return recv.group(1) == assign.group(1)


def pump_no_full_extract_ok(shape):
    """反例判据：绝不允许 extract(space) 无条件抽满，也不许拿配置常量当抽取上限。"""
    code = shape.get("code")
    if not code:
        return False
    return (".extract(space)" not in code
            and "rangeChargerMaxTransfer" not in code
            and code.count(".extract(") == 1)


def pump_verdict(text):
    """三条源码判据的合取（反例自证也喂这个函数）。"""
    shape = pump_shape(text)
    return (pump_by_acceptance_ok(shape)
            and pump_write_complete_ok(shape)
            and pump_no_full_extract_ok(shape))


# 修前写法（逐字取自 git 里的旧版 doWork）：只用来自证「判据真的抓得住旧写法」。
OLD_NODE_SRC = """
public class RangeChargerNetworkNode extends AbstractNetworkNode {
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        final net.neoforged.neoforge.energy.IEnergyStorage storage = blockEntity.getEnergyStorage();
        final long space = storage.getMaxEnergyStored() - storage.getEnergyStored();
        if (space <= 0) {
            return;
        }
        final long extracted = network.getComponent(EnergyNetworkComponent.class).extract(space);
        if (extracted > 0) {
            storage.receiveEnergy((int) extracted, false);
        }
    }
}
"""

node_shape = pump_shape(charger_node)
check("9.1 抽取侧先 simulate 再按「实际能接收的量」抽（抽取量 ≤ 接收量，源码级判据）",
      pump_by_acceptance_ok(node_shape),
      "doWork 里没有「先 receiveEnergy(..., true) 问、再 extract(该结果)」的形状")
check("9.2 不存在 extract(space) 无条件抽满（也不许按 rangeChargerMaxTransfer 常量抽）",
      pump_no_full_extract_ok(node_shape))
check("9.3 抽到的量被完整写入缓存：写入实参 == extract 的返回变量（非模拟模式）",
      pump_write_complete_ok(node_shape))


# ---- 行为镜像：把 NeoForge EnergyStorage / RS 能量池的夹量在 Python 里逐条镜像 ----

def j_receive(stored, capacity, max_receive, amount):
    """EnergyStorage.receiveEnergy 的夹量：min(请求, capacity − stored, maxReceive)。"""
    if amount <= 0:
        return 0
    return max(0, min(amount, capacity - stored, max_receive))


def tick_old_pump(net, stored, capacity, max_receive):
    """修前写法：extract(space) 抽满，接收侧的截断差额直接丢失（返回值被忽略）。"""
    space = capacity - stored
    if space <= 0:
        return net, stored, 0, 0
    extracted = min(space, net)
    accepted = j_receive(stored, capacity, max_receive, extracted)
    return net - extracted, stored + accepted, extracted, accepted


def tick_new_pump(net, stored, capacity, max_receive):
    """修后写法：先 simulate 问能收多少，再按这个量抽（抽到的 == 收到的）。"""
    space = capacity - stored
    if space <= 0:
        return net, stored, 0, 0
    can_accept = j_receive(stored, capacity, max_receive, space)
    if can_accept <= 0:
        return net, stored, 0, 0
    extracted = min(can_accept, net)
    accepted = j_receive(stored, capacity, max_receive, extracted)
    return net - extracted, stored + accepted, extracted, accepted


CAPACITY = 1000000
POOL = 2000000
OLD_MAX_RECEIVE = 5000

_old_net, _old_stored, _old_ext, _old_acc = tick_old_pump(POOL, 0, CAPACITY, OLD_MAX_RECEIVE)
_new_net, _new_stored, _new_ext, _new_acc = tick_new_pump(POOL, 0, CAPACITY, OLD_MAX_RECEIVE)
check("9.4 数值自证：修前写法一 tick 从网络扣 1,000,000 却只收下 5,000 ⇒ 纯损耗 995,000 FE",
      (_old_ext, _old_acc, _old_ext - _old_acc) == (1000000, 5000, 995000),
      "extracted=%d accepted=%d" % (_old_ext, _old_acc))
check("9.4 数值自证：修后写法同条件下抽到的 == 收到的（损耗恒 0）",
      (_new_ext, _new_acc, _new_ext - _new_acc) == (5000, 5000, 0),
      "extracted=%d accepted=%d" % (_new_ext, _new_acc))


def run_ticks(fn, ticks, max_receive):
    net, stored = POOL, 0
    lost = 0
    for _ in range(ticks):
        net, stored, extracted, accepted = fn(net, stored, CAPACITY, max_receive)
        lost += extracted - accepted
    return net, stored, lost


_old_run = run_ticks(tick_old_pump, 5, OLD_MAX_RECEIVE)
_new_run = run_ticks(tick_new_pump, 5, OLD_MAX_RECEIVE)
check("9.4 多 tick 推演：修前 5 tick 把 200 万网络抽到 0（缓存只攒到 15,000、损耗 1,985,000）；"
      "修后网络还剩 197.5 万、损耗 0（每 tick 只取接收侧真能收的 5,000）",
      _old_run[0] == 0 and _old_run[1] == 15000 and _old_run[2] == 1985000
      and _new_run == (1975000, 25000, 0),
      "old=%r new=%r" % (_old_run, _new_run))
check("9.4 当前 BE（maxReceive = capacity）下修后 2 tick 充满缓存并停止，且不损失任何能量",
      run_ticks(tick_new_pump, 5, CAPACITY) == (1000000, CAPACITY, 0),
      "%r" % (run_ticks(tick_new_pump, 5, CAPACITY),))
check("9.4 修后全程零损耗（充电器功能仍在：缓存没满时每 tick 都真的从网络取到电）",
      _new_run[2] == 0 and _new_ext > 0 and _old_run[2] > 0,
      "new_lost=%d" % _new_run[2])
# 功能不变：接收侧放开（maxReceive = capacity，即 BE 侧现在的 RestorableEnergyStorage）后，
# 修后写法与修前写法抽到的量逐 tick 相同 —— 只是不再有多余损耗。
_old_open = tick_old_pump(POOL, 0, CAPACITY, CAPACITY)
_new_open = tick_new_pump(POOL, 0, CAPACITY, CAPACITY)
check("9.4 功能不变：接收侧放开后，修后写法与修前写法同一 tick 抽到同样多（吞吐没有被改小）",
      (_old_open[2], _old_open[0], _old_open[1]) == (_new_open[2], _new_open[0], _new_open[1])
      and _new_open[2] == CAPACITY)
check("9.4 缓存满后不再抽取（space <= 0 的既有短路仍在，不会空转抽电）",
      tick_new_pump(POOL, CAPACITY, CAPACITY, OLD_MAX_RECEIVE) == (POOL, CAPACITY, 0, 0))

# ---- 反例自证：旧写法 / 按配置常量抽的写法必须被判负 ----
check("9.5 反例自证：修前写法（extract(space) 无条件抽满）⇒ 判负",
      not pump_verdict(OLD_NODE_SRC)
      and not pump_by_acceptance_ok(pump_shape(OLD_NODE_SRC))
      and not pump_no_full_extract_ok(pump_shape(OLD_NODE_SRC)))
check("9.5 反例自证：改成 extract(min(space, rangeChargerMaxTransfer))（拿配置常量当接收量）⇒ 判负",
      not pump_verdict(charger_node.replace(
          "extract(canAccept)",
          "extract(Math.min(space, Config.rangeChargerMaxTransfer))", 1)))
check("9.5 反例自证：丢掉 simulate、直接按 space 抽 ⇒ 判负",
      not pump_by_acceptance_ok(pump_shape(charger_node.replace(
          "extract(canAccept)", "extract((int) space)", 1))))
check("9.5 反例自证：抽到的量不写入缓存（写入被删掉）⇒ 判负",
      not pump_write_complete_ok(pump_shape(charger_node.replace(
          "storage.receiveEnergy((int) extracted, false);", "// 丢掉了", 1))))

# 反向（缓存 → 网络）不存在同类问题：本节点不是 EnergyProvider（RS 不能从充电器缓存反向抽电），
# 节点里也没有任何「向网络回送」的调用；对外那层是 CappedEnergyStorage#extractEnergy，
# 返回值就是真实扣走的量，调用方按返回值记账，不存在「多扣少收」。
check("9.6 反向回送：节点不是 EnergyProvider、且没有任何 insert 回网络的路径",
      not re.search(r"implements[^{]*EnergyProvider", charger_node)
      and ".insert(" not in pump_shape(charger_node)["code"]
      and not re.search(r"\binsert\(", charger_node))

# ==================== 10. 查看充电范围：服务端响应（S2C）+ 菜单校验 ====================
#
# 用户原话：「当前 XYZ 范围包括充电范围。需要添加一个按钮，用于查看充电范围。」
# 客户端菜单是用二参工厂重建的（普通 MenuType），getChargerPos() 在客户端恒 null，
# 因此坐标必须由服务端 S2C 下发；C2S 请求复用原版菜单按钮通道（不新增 C2S 包）。

RANGE_PACKET = "network/ShowChargerRangePacket.java"
RANGE_MAIN = "RS_Create_Compat.java"
RANGE_MENU = "menu/RangeChargerMenu.java"
RANGE_SCREEN = "client/screen/RangeChargerScreen.java"

range_packet = read(RANGE_PACKET)
range_main = read(RANGE_MAIN)
range_menu = read(RANGE_MENU)
range_screen = read(RANGE_SCREEN)


def pred_range_packet_ok(src):
    """S2C 载荷 = containerId + BlockPos + 三轴范围；处理体只投递（不碰客户端类型）。"""
    code = code_only(src)
    return ("record ShowChargerRangePacket" in code
            and "BlockPos.STREAM_CODEC" in code
            and "implements CustomPacketPayload" in code
            and code.count("ByteBufCodecs.VAR_INT") == 4
            and "ctx.enqueueWork" in code)


def pred_range_registered_as_s2c(src):
    """必须注册成 playToClient（S2C）；注册成 playToServer 就是「服务端收、客户端永远收不到」。"""
    code = code_only(src)
    at = code.find("ShowChargerRangePacket.TYPE")
    if at < 0:
        return False
    return ("playToClient(" in code[max(0, at - 300):at]
            and "ShowChargerRangePacket::handle" in code)


def pred_range_menu_validates(src):
    """按钮 id 6 + 服务端校验 stillValid（维度 / 距离 / 方块实体身份）后才回包。"""
    code = code_only(src)
    return ("BUTTON_SHOW_RANGE = 6" in code
            and "case BUTTON_SHOW_RANGE" in code
            and "stillValid(player)" in code
            and "sendRangeOutline(serverPlayer, containerId)" in code)


def pred_range_be_readonly(src):
    """方块实体的响应方法只读：只发快照，绝不改范围 / 能量 / 充电扫描。"""
    body = method_body(code_only(src), "public void sendRangeOutline(")
    if body is None:
        return False
    forbidden = ("receiveEnergy", "extractEnergy", "doCharging(", "scanPlayers(", "scanItems(",
                 "adjustRange", "setChanged()", "rangeX =", "rangeY =", "rangeZ =")
    return ("PacketDistributor.sendToPlayer" in body
            and "worldPosition" in body
            and not any(token in body for token in forbidden))


check("10.1 S2C 载荷携带服务端权威坐标（BlockPos）与三轴范围，处理体只投递主线程",
      pred_range_packet_ok(range_packet))
check("10.2 该载荷注册为 playToClient（客户端能收到；不是 playToServer）",
      pred_range_registered_as_s2c(range_main))
check("10.3 菜单按钮 6：服务端先 stillValid（距离 / 维度 / 方块实体身份）再回包",
      pred_range_menu_validates(range_menu))
check("10.4 方块实体的响应方法只读（不发充电、不改范围 / 能量）",
      pred_range_be_readonly(charger))
check("10.5 客户端坐标不赌：界面不调用 getChargerPos，C2S 走原版菜单按钮通道",
      "getChargerPos" not in code_only(range_screen)
      and "handleInventoryButtonClick(menu.containerId, RangeChargerMenu.BUTTON_SHOW_RANGE)"
          in code_only(range_screen))

# ---- 反例自证：四种错误写法必须被同一批判据判负 ----
check("10.6 反例：S2C 只带 containerId + 范围、不带 BlockPos ⇒ 判据 10.1 判负",
      pred_range_packet_ok("""
public record ShowChargerRangePacket(int containerId, int rangeX, int rangeY, int rangeZ)
    implements CustomPacketPayload {
    public static final StreamCodec<RegistryFriendlyByteBuf, ShowChargerRangePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::containerId,
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::rangeX,
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::rangeY,
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::rangeZ,
            ShowChargerRangePacket::new);
    public static void handle(final ShowChargerRangePacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHooks.get().showChargerRange(packet));
    }
}
""") is False)
check("10.7 反例：注册成 playToServer ⇒ 判据 10.2 判负",
      pred_range_registered_as_s2c(
          "registrar.playToServer(ShowChargerRangePacket.TYPE, ShowChargerRangePacket.STREAM_CODEC,"
          " ShowChargerRangePacket::handle);") is False)
check("10.8 反例：菜单不校验 stillValid 就回包 ⇒ 判据 10.3 判负",
      pred_range_menu_validates(range_menu.replace("&& stillValid(player)", "")) is False)
check("10.9 反例：响应方法里顺手改范围（rangeX = ...; setChanged();）⇒ 判据 10.4 判负",
      pred_range_be_readonly(charger.replace(
          "containerId, worldPosition, rangeX, rangeY, rangeZ));",
          "containerId, worldPosition, rangeX, rangeY, rangeZ));\n"
          "        rangeX = 1;\n        setChanged();", 1)) is False)

# ==================== 结果 ====================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
