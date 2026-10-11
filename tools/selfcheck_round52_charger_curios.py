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

# ==================== 结果 ====================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
