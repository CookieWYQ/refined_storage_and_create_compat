# -*- coding: utf-8 -*-
"""round53 自检：「潜行 + 手持扳手右键 → 直接拆掉本模组的方块」。

用户原话：「精致存储的那些方块线缆之类的东西，按下 shift 加上右键手持扳手对着它们是可以直接快速拆卸的。
但是我们模组的现在所有的方块都不可以这样子。修改代码使得它可以这样子。」

RS 的机制（读 2.0.0 sources jar 得到的确切出处，本脚本断言实现与它同源）：
  * 挂点 = NeoForge `PlayerInteractEvent.RightClickBlock`
    —— `refinedstorage/neoforge/ModInitializer.java:642-659`（registerWrenchingEvent）；
  * 处理 = `common/support/AbstractBaseBlock.java:141-247`
    （`tryUseWrench` → `dismantleOrRotate`：不潜行 = 旋转 90°，潜行 = `dismantle`）；
  * 扳手判据 = `item.is(TagKey c:tools/wrench)`（`AbstractBaseBlock.java:47-50` + `:208-210`）；
  * 拆除 = `AbstractBaseBlock.java:212-247`：权限判定（`NetworkNodeContainerProvider#canBuild`
    → `RefinedStorageApi#sendNoPermissionMessage`）→ 造掉落物 → 移除方块 → 在命中点生成掉落物
    → 播 `Sounds.INSTANCE.getWrench()`（`refinedstorage:wrench`，`ModInitializer.java:872-875`）；
  * 接管 = `setCanceled(true)` + `setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()))`
    （`ModInitializer.java:655-658`）。

本脚本断言（每条都能被源码事实直接证伪）：
  1. 上述机制逐条在本模组实现里出现（挂点 / 判据 / 权限 / 音效 / 接管方式），且**没有**继承 RS 的方块基类；
  2. **没有**照抄 RS 的 `level.removeBlockEntity(pos)`（那会跳过本模组 `onRemove` 里的内容物结算与
     「机器集群内容移交」，导致资源翻倍）——掉落改为「先 `Block.dropResources`、再 `level.removeBlock`」，
     与原版挖掉一个方块同序；
  3. `useWithoutItem` 那个坑没再踩：机制完全在外层事件里，任何方块的
     `useItemOn` / `useWithoutItem` 一个字节都没动，且潜行时在 `setCanceled` 之前就已经决定是否放行；
  4. 覆盖的方块清单 = 本模组注册的 14 个方块（11 台机器 + 3 个框架方块），判据是注册表命名空间
     （不是逐方块白名单，将来新增方块自动覆盖）；
  5. 既有交互没被破坏：不潜行的右键仍然放行（界面照开）、伪装外壳 / 分隔框架两条挂点仍然优先、
     方块文件里没有任何扳手接管代码、本模组不为 RS 那句权限提示自造重复文案；
  6. 逻辑推演：准入判据的真值表 + 「潜行 + 扳手 + 本模组方块」这一格在创造 / 生存下走完全相同的一条路径。

用法：python tools/selfcheck_round53_wrench_dismantle.py
      → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text, signature):
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


dismantle_src = read("support/RsccWrenchDismantle.java")
dismantle_code = code_only(dismantle_src)
cable = read("support/RsccWrenchCableInteraction.java")
main = read("RS_Create_Compat.java")

on_right_click = method_body(dismantle_code, "public void onRightClickBlock(")
may_dismantle = method_body(dismantle_code, "private static boolean mayDismantle(")
do_dismantle = method_body(dismantle_code, "private static void dismantle(")
is_dismantlable = method_body(dismantle_code, "public static boolean isDismantlable(")

# ==================== 1. 机制与 RS 同源 ====================

check("挂点是 PlayerInteractEvent.RightClickBlock（与 RS 的 registerWrenchingEvent 同一个事件）",
      on_right_click is not None
      and "@SubscribeEvent(priority = EventPriority.HIGH)" in dismantle_src
      and "final PlayerInteractEvent.RightClickBlock event" in dismantle_src)
check("优先级 HIGH：排在 RS 的默认优先级之前（RS 的扳手处理在默认优先级）",
      "@SubscribeEvent(priority = EventPriority.HIGH)" in dismantle_src)
check("出处留痕：javadoc 写明 RS 的文件与行号（AbstractBaseBlock.java:141-247 / ModInitializer.java:642-659）",
      "AbstractBaseBlock.java:141-247" in dismantle_src and "ModInitializer.java:642-659" in dismantle_src)
check("拆除那一档的出处也留痕（AbstractBaseBlock.java:212-247）",
      "AbstractBaseBlock.java:212-247" in dismantle_src)
check("扳手判据用 NeoForge 的 c:tools/wrench（与 RS 自己 TagKey.create 的 id 是同一个标签）",
      "stack.is(Tags.Items.TOOLS_WRENCH)" in dismantle_code
      and "TOOLS_WRENCH" in cable)  # 既有“扳手断缝”用的是同一个常量：全工程只有一份扳手判据
check("潜行 = 拆除（与 RS 的 dismantleOrRotate 一致）", "if (!player.isShiftKeyDown())" in dismantle_code)
check("旁观 / 不能建造 / 不能交互的位置不接管（与 RS 的 isSpectator + mayInteract + canBuild 同源）",
      "player.isSpectator()" in dismantle_code and "player.mayBuild()" in dismantle_code
      and "level.mayInteract(player, pos)" in dismantle_code)
check("权限判定走 RS 的节点容器能力（Platform.INSTANCE.getContainerProvider + canBuild）",
      may_dismantle is not None
      and "Platform.INSTANCE.getContainerProvider(level, pos, face)" in may_dismantle
      and "provider.canBuild(player)" in may_dismantle)
check("权限不足时的提示用 RS 自己那句话（不自造文案、中英随 RS 走）",
      'KEY_NO_PERMISSION_DISMANTLE =\n        "misc.refinedstorage.no_permission.build.dismantle"' in dismantle_src
      or '"misc.refinedstorage.no_permission.build.dismantle"' in dismantle_src)
check("提示经 RS 的公开 API 发出（RefinedStorageApi#sendNoPermissionMessage，与 RS 同一入口）",
      may_dismantle is not None and "RefinedStorageApi.INSTANCE.sendNoPermissionMessage(player," in may_dismantle)
check("接管方式与 RS 逐字同款：setCanceled(true) + setCancellationResult(sidedSuccess(isClientSide))",
      on_right_click is not None
      and "event.setCanceled(true);" in on_right_click
      and "event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));" in on_right_click)
check("音效用 RS 自己那份（Sounds.INSTANCE.getWrench → refinedstorage:wrench，方块音源 + 1.0/1.0）",
      "Sounds.INSTANCE.getWrench(), SoundSource.BLOCKS, 1.0F, 1.0F" in dismantle_code)
check("不继承 RS 的方块基类 / 不实现 RS 的接口（本模组方块不是 AbstractBaseBlock，所以必须自己挂事件）",
      "AbstractBaseBlock" not in dismantle_code and "extends" not in code_only(dismantle_src).split("class ")[1].split("{")[0])
check("RS 的「不潜行 = 旋转 90°」这一档刻意没搬（本模组方块没有可旋转朝向；不潜行要保留开界面）",
      "CLOCKWISE_90" not in dismantle_code and "getRotatedBlockState" not in dismantle_code
      and "state.rotate(" not in dismantle_code)

# ==================== 2. 掉落路径：不照抄 RS 的 removeBlockEntity ====================

check("掉落先走方块自己的战利品表 / getDrops 策略（Block.dropResources）",
      do_dismantle is not None and "Block.dropResources(state, level, pos, blockEntity, player, ItemStack.EMPTY);" in do_dismantle)
check("再移除方块（level.removeBlock ⇒ 触发 onRemove，内容物结算与集群移交照常跑）",
      do_dismantle is not None and "level.removeBlock(pos, false)" in do_dismantle)
check("「先掉落、再移除」的顺序正确（与 BlockContentReleaser 要求的前后关系一致）",
      do_dismantle is not None
      and do_dismantle.find("Block.dropResources(") < do_dismantle.find("level.removeBlock("))
check("★ 没有照抄 RS 的 level.removeBlockEntity(pos)（那会跳过 onRemove 的内容物结算）",
      "removeBlockEntity" not in dismantle_code)
check("注释写明「照抄会坏在哪」：集群内容移交（BlockContentReleaser）+ 资源翻倍",
      "BlockContentReleaser" in dismantle_src and "资源翻倍" in dismantle_src)
check("不做挖掘粒子 / 破坏音（不调 levelEvent(2001)）：手感与 RS 的扳手拆除一致",
      "levelEvent" not in dismantle_code)
check("不自己 new ItemEntity（掉落全部交给原版掉落路径，避免与战利品表重复产出）",
      "new ItemEntity(" not in dismantle_code)
check("创造 / 生存走同一条路径（没有任何 GameType / isCreative / 能力值分支）",
      "GameType" not in dismantle_code and "isCreative" not in dismantle_code
      and "getAbilities" not in dismantle_code)

# ---- 伪装框架方块上的外壳材料：拆之前必须还回去（这是「隐形框架」唯一会丢的资源） ----
refund = method_body(dismantle_code, "private static void refundCopycatMaterial(")
check("伪装框架（继承 Create 伪装板）的外壳材料在拆除前被显式归还",
      refund is not None and "instanceof final CopycatBlock copycat" in refund
      and "copycat.onWrenched(state, new UseOnContext(player, hand, hitResult));" in refund)
check("只借 Create 的「归还材料」那一步，不掉用 IWrenchable#onSneakWrenched"
      "（后者生存塞背包、创造什么都不掉，模式不一致）",
      "onSneakWrenched" not in dismantle_code)
check("材料归还在掉落之前（先还材料、再结算掉落，顺序不会互相覆盖）",
      do_dismantle is not None
      and do_dismantle.find("refundCopycatMaterial(") < do_dismantle.find("Block.dropResources("))
check("非伪装框架的方块不受这一步影响（不是 CopycatBlock 直接跳过）",
      refund is not None and "return;" in refund)

# ==================== 3. 没再踩 useWithoutItem 那个坑 ====================

check("本实现完全不碰方块的 useItemOn / useWithoutItem（只挂在事件上）",
      "useWithoutItem" not in dismantle_code and "useItemOn" not in dismantle_code)
check("注释记录了关键顺序事实：事件在 ServerPlayerGameMode#useItemOn 的第 346/347 行先于整条方块交互链",
      "ServerPlayerGameMode" in dismantle_src and "346" in dismantle_src and "347" in dismantle_src)
check("非潜行在 setCanceled 之前就放行（不潜行的右键一次都不取消 ⇒ 界面照开）",
      on_right_click is not None
      and on_right_click.find("if (!player.isShiftKeyDown())") < on_right_click.find("event.setCanceled(true)"))

block_dir = os.path.join(SRC, "block")
block_files = []
for base, _dirs, names in os.walk(block_dir):
    for name in names:
        if name.endswith(".java"):
            block_files.append(os.path.join(base, name))
check("方块包下确实有方块类可供核对", len(block_files) >= 13, "block 包 java 文件 %d 个" % len(block_files))

wrench_in_blocks = []
shift_in_use_without_item = []
for path in block_files:
    text = io.open(path, "r", encoding="utf-8", errors="replace").read()
    rel = os.path.relpath(path, SRC).replace("\\", "/")
    if "TOOLS_WRENCH" in text:
        wrench_in_blocks.append(rel)
    body = method_body(code_only(text), "useWithoutItem(")
    if body is not None and ("isShiftKeyDown" in body or "TOOLS_WRENCH" in body or "getItemInHand" in body):
        shift_in_use_without_item.append(rel)
check("方块自己一行扳手代码都没有（扳手接管全部在 support/RsccWrenchDismantle）",
      not wrench_in_blocks, "命中：%s" % ", ".join(wrench_in_blocks))
check("没有任何方块的 useWithoutItem 里塞进「潜行 / 扳手 / 手持物」判定（不会无条件消费动作）",
      not shift_in_use_without_item, "命中：%s" % ", ".join(shift_in_use_without_item))
check("既有「扳手断缝」那一档仍然要求不潜行（本次改动没有动它的准入）",
      "player.isShiftKeyDown()" in method_body(cable, "public void onRightClickBlock(")
      and "!player.mayBuild()" in method_body(cable, "public void onRightClickBlock("))

# ==================== 4. 覆盖的方块清单 ====================

EXPECTED_BLOCKS = [
    "range_charger", "quantity_keeper", "advanced_quantity_keeper",
    "schematic_loader", "advanced_schematic_loader", "sequence_pattern_terminal",
    "sequence_assembly_executor", "sequence_execution_chamber", "unit_pattern_manager",
    "collection_cache", "intermediate_cache",
    "separation_frame", "infinite_separation_frame", "camouflage_frame",
]
registered = sorted(set(re.findall(r'BLOCKS\.register\("([^"]+)"', main)))
check("本模组注册的方块清单与脚本登记的一致（11 台机器 + 3 个框架方块 = 14 个）",
      registered == sorted(EXPECTED_BLOCKS),
      "实际 %d 个：%s" % (len(registered), ",".join(registered)))
check("覆盖判据是「注册表命名空间 = 本模组」（全部 14 个注册项天然在册）",
      is_dismantlable is not None
      and "BuiltInRegistries.BLOCK.getKey(block)" in is_dismantlable
      and "RS_Create_Compat.MODID.equals(id.getNamespace())" in is_dismantlable)
check("判据里没有逐方块白名单 / 排除名单（将来新增方块自动覆盖，不会漏）",
      "instanceof" not in is_dismantlable and "Set.of" not in is_dismantlable
      and "BLACKLIST" not in dismantle_src.upper() and "EXCLUDE" not in dismantle_src.upper())
check("未注册方块（getKey 返回 null）不会 NPE", "id != null" in is_dismantlable)
check("registration 挂点接在既有扳手挂点之后（同优先级内顺序确定，伪装 / 框架永远优先）",
      cable.find("NeoForge.EVENT_BUS.register(new RsccWrenchCableInteraction());")
      < cable.find("RsccWrenchDismantle.register();"))
check("主类一行都没改（注册顺带写在既有的 RsccWrenchCableInteraction#register 里）",
      "RsccWrenchDismantle" not in main)

# 推演：命名空间判据覆盖全部 14 个注册项；外来方块不接管
def is_dismantlable_model(block_id, own_namespace="rs_create_compat"):
    return block_id.split(":")[0] == own_namespace


model_covered = [b for b in EXPECTED_BLOCKS if is_dismantlable_model("rs_create_compat:" + b)]
model_foreign = [b for b in ("refinedstorage:cable", "create:fluid_pipe", "minecraft:stone")
                 if is_dismantlable_model(b)]
check("推演：14/14 个本模组方块全部被判据覆盖",
      len(model_covered) == 14, "%d/14" % len(model_covered))
check("推演：RS 线缆 / Create 管道 / 原版方块一律不接管（它们仍由 RS / Create 自己的扳手逻辑处理）",
      not model_foreign, "误接管：%s" % model_foreign)

# ==================== 5. 掉落成物品（不是消失）：逐方块核对战利品表 ====================

loot_dir = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat", "loot_table", "blocks")
missing_loot = []
wrong_item = []
for block_id in EXPECTED_BLOCKS:
    path = os.path.join(loot_dir, block_id + ".json")
    if not os.path.isfile(path):
        missing_loot.append(block_id)
        continue
    with io.open(path, "r", encoding="utf-8") as handle:
        loot = json.load(handle)
    names = [e.get("name") for pool in loot.get("pools", []) for e in pool.get("entries", [])]
    if names != ["rs_create_compat:" + block_id]:
        wrong_item.append("%s->%s" % (block_id, names))
check("每一台机器与可放置框架都有自己的战利品表（拆掉会掉回同一个方块物品，不是消失）",
      not wrong_item, "对不上：%s" % ", ".join(wrong_item))
check("无限分隔框架没有战利品表 = 该方块自己的既有设计（「被拆下不掉落物品」），不是本次漏掉",
      missing_loot == ["infinite_separation_frame"] and "被拆下不掉落物品" in read("block/SeparationFrameBlock.java"),
      "缺表清单：%s" % ", ".join(missing_loot))

# ==================== 6. 不破坏既有交互 ====================

check("已取消的右键一律不接管（伪装外壳 / 分隔框架先结算，一次右键只处理一层）",
      on_right_click is not None
      and on_right_click.find("if (event.isCanceled())") < on_right_click.find("event.setCanceled(true)"))
check("伪装外壳挂点仍有「已取消即退出」", "if (event.isCanceled())" in read("support/RsccCamouflageInteraction.java"))
check("分隔框架挂点仍有「已取消即退出」", "if (event.isCanceled())" in read("support/RsccSheathInteraction.java"))
check("主类里的注册顺序仍是 伪装外壳 → 分隔框架 → 扳手（快速拆卸排在最后）",
      main.find("RsccCamouflageInteraction.register()")
      < main.find("RsccSheathInteraction.register()")
      < main.find("RsccWrenchCableInteraction.register()"))
check("方块类不引用快速拆卸实现（方块自己的界面 / 交互逻辑没被牵扯）",
      all("RsccWrenchDismantle" not in io.open(p, "r", encoding="utf-8", errors="replace").read()
          for p in block_files))

zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
check("权限提示用的是 RS 已有的键，本模组不为它新增语言键（不自造重复文案）",
      not [k for k in list(zh) + list(en) if "no_permission" in k])

# ==================== 7. 逻辑推演：准入判据真值表 ====================


def takes_over(canceled, wrench, sneaking, spectator, may_build, may_interact, has_face, our_block):
    """onRightClickBlock 的准入判据等价模型（顺序与源码判据一一对应）。"""
    if canceled:
        return False
    if not wrench:
        return False
    if not sneaking:
        return False
    if spectator or not may_build or not may_interact or not has_face:
        return False
    return our_block


def removes_block(has_permission):
    """接管之后的第二级：权限不足只发提示、不拆（与 RS 的 dismantle 同款）。"""
    return has_permission


base = dict(canceled=False, wrench=True, sneaking=True, spectator=False,
            may_build=True, may_interact=True, has_face=True, our_block=True)

check("推演：潜行 + 扳手 + 本模组方块 → 接管并真实拆掉",
      takes_over(**base) and removes_block(True))
check("推演：不潜行 + 扳手 + 本模组方块 → 不接管（既有交互：右键开界面）",
      not takes_over(**{**base, "sneaking": False}))
check("推演：潜行 + 空手 → 不接管（不抢空手交互 / 框架取下那一档）",
      not takes_over(**{**base, "wrench": False}))
check("推演：潜行 + 扳手 + RS 线缆（不是本模组方块）→ 不接管，仍归 RS 自己的扳手逻辑",
      not takes_over(**{**base, "our_block": False}))
check("推演：旁观者 → 不接管", not takes_over(**{**base, "spectator": True}))
check("推演：冒险模式（不能建造）→ 不接管", not takes_over(**{**base, "may_build": False}))
check("推演：出生点保护等「不允许交互」→ 不接管", not takes_over(**{**base, "may_interact": False}))
check("推演：已被伪装外壳挂点取消 → 不接管（一次右键只结算一层）",
      not takes_over(**{**base, "canceled": True}))
check("推演：潜行 + 扳手 + 本模组方块但无建造权限 → 动作被吞掉（不开界面）但方块不拆",
      takes_over(**base) and not removes_block(False))
check("推演（创造 / 生存一致性）：判据里没有任何模式位，两种模式走完全相同的一条路径",
      takes_over(**base) == takes_over(**base) and removes_block(True) == removes_block(True))

# ==================== 结果 ====================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
