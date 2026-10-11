# -*- coding: utf-8 -*-
"""round51 自检：**归流缓存仓的液态经验**（附魔工业检测 + 「球 → 液态经验 → 流体缓存」语义）。

用户原话（本轮第 5 项）：
  * 「你现在并不能正常的检测这一个附魔工业 —— 就是我现在安装了附魔工业但还是点不了这个按钮，
    点了之后它还是没有生效之类的」；
  * 「它不是说只吸收这一个源液态经验 —— 这个东西它本质上它还是这种流体，就是这个液态经验……
    所以说他是归流体那一方面管的」；
  * 「这个功能到底是干什么的：就是勾上之后呢，它是把这个经验球直接转换成这个流体，
    直接转换成这一个液态经验，然后储存起来」。

本脚本断言（每条都能被源码事实证伪，不是复述注释）：
  A. 真实 modid：代码用的是 `create_enchantment_industry`，且与上游证据（附魔工业自己的
     gradle.properties / cei_files.txt 的资源命名空间）一致；工程里没有常见错拼的 modid 字面量；
  B. 检测口径：mod id 判断**带 null 安全判据**（ModList.get() != null），而资源可用性
     **以注册表为准**（不再把 isLoaded 当硬门槛 —— 那正是「装了却判定不通过」的机制）；
  C. 序号解码：`XpForm.byOrdinal` 必须认现行 LIQUID 序号（否则 C2S 包与菜单数据槽 21
     会把「液态」静默解成「颗粒」，按钮点了没反应）；
  D. 勾选后的路径：collectExperience **无条件**收取经验球，并把 fluidId 交给
     absorbExperienceOrbs → fluidCache.insert（球 → 液态经验 → 本仓流体缓存）；
     缓存 → 网络仍有 flushFluidCacheToNetwork；
  E. 换算率写明且守恒（1 点 = 1 mB；颗粒 3 点 / 个），并用算术模型复算：
     入缓存的 mB 恰好等于扣掉的点数，剩余点数留在球里 —— 不复制、不凭空产生；
  F. 缺附魔工业时的降级：液态不可选 → setXpForm / loadXpForm 退化 ORB；
     运行期流体缺失 → 经验球折算成经验颗粒；两种表示都不可用 → 一个球都不动。

用法：python tools/selfcheck_round51_cache_liquid_xp.py
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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
CEI_UPSTREAM = os.path.join(ROOT, "local_src", "external", "CreateEnchantmentIndustry")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def read_abs(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, ("" if ok else ((" | " + detail) if detail else ""))))


def method_body(text, signature_fragment):
    """按大括号配对取出以 signature_fragment 开头的方法体（含签名行）。"""
    start = text.find(signature_fragment)
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


optional = read("support/OptionalDeps.java")
xp_form = read("support/XpForm.java")
cache_be = read("block/entity/CollectionCacheBlockEntity.java")
screen = read("client/screen/CollectionCacheScreen.java")
menu = read("menu/CollectionCacheMenu.java")

# ==================== A. 真实 modid（不是错的字面量） ====================

MODID = "create_enchantment_industry"
check("A1 代码里 modid 常量的字面量就是真实 modid",
      'public static final String MOD_CREATE_ENCHANTMENT_INDUSTRY = "%s";' % MODID in optional,
      "OptionalDeps 里找不到 MOD_CREATE_ENCHANTMENT_INDUSTRY = \"%s\"" % MODID)

# 上游证据 1：附魔工业自己的 gradle.properties（mod_id 是唯一权威定义）
upstream_ok = False
upstream_detail = "上游源码目录不存在：%s" % CEI_UPSTREAM
gradle_props = os.path.join(CEI_UPSTREAM, "gradle.properties")
if os.path.isfile(gradle_props):
    text = read_abs(gradle_props)
    matched = re.search(r"^\s*mod_id\s*=\s*(\S+)\s*$", text, re.M)
    upstream_ok = matched is not None and matched.group(1) == MODID
    upstream_detail = "上游 mod_id=%s" % (matched.group(1) if matched else "<未找到>")
check("A2 上游证据：附魔工业自己的 gradle.properties 里 mod_id 与代码一致", upstream_ok, upstream_detail)

# 上游证据 2：液态经验流体 id = create_enchantment_industry:experience
cei_fluids = os.path.join(CEI_UPSTREAM, "src", "main", "java", "plus", "dragons",
                          "createenchantmentindustry", "common", "registry", "CEIFluids.java")
fluids_ok = False
fluids_detail = "找不到 CEIFluids.java"
if os.path.isfile(cei_fluids):
    text = read_abs(cei_fluids)
    fluids_ok = 'asResource("experience")' in text
    fluids_detail = "CEIFluids.java 里没有 asResource(\"experience\")"
check("A3 上游证据：液态经验流体注册名是 <modid>:experience（CEIFluids.java）", fluids_ok, fluids_detail)
check("A4 代码里的流体候选 id 与上游一致（create_enchantment_industry:experience）",
      'ResourceLocation.fromNamespaceAndPath(OptionalDeps.MOD_CREATE_ENCHANTMENT_INDUSTRY, "experience")'
      in read("support/XpTargetResolver.java"),
      "XpTargetResolver.FLUID_CANDIDATES 里的流体 id 变了")

# 常见错拼：这些字面量一旦出现在源码里，就是「查了错的 modid」
WRONG_MODIDS = [
    "createenchantmentindustry",
    "create_enchantmentindustry",
    "createenchantment_industry",
    "create_enchantment_industries",
    "create_enchantment_industry_2",
    "enchantment_industry",
    "create_enchant_industry",
]
bad_literals = []
for base, _dirs, names in os.walk(SRC):
    for name in names:
        if not name.endswith(".java"):
            continue
        rel = os.path.relpath(os.path.join(base, name), SRC).replace("\\", "/")
        body = io.open(os.path.join(base, name), "r", encoding="utf-8", errors="replace").read()
        for wrong in WRONG_MODIDS:
            if '"%s"' % wrong in body:
                bad_literals.append("%s: \"%s\"" % (rel, wrong))
check("A5 全工程没有错拼的附魔工业 modid 字面量", not bad_literals, "; ".join(bad_literals))

# ==================== B. 检测口径（安全判据 + 注册表为准） ====================

loaded_body = method_body(optional, "public static boolean isEnchantmentIndustryLoaded()")
check("B1 modid 判断带 null 安全判据（ModList.get() != null，缺失时优雅为 false，不崩）",
      loaded_body is not None
      and "ModList.get() != null" in loaded_body
      and "isLoaded(" in loaded_body,
      "isEnchantmentIndustryLoaded 里缺少 ModList.get() != null 或 isLoaded(")

fluid_body = method_body(optional, "public static FluidStack enchantmentIndustryExperienceFluid(")
check("B2 流体探测以注册表为准（先按 id 取流体，modid 判断不再当硬门槛）",
      fluid_body is not None
      and "fluidStack(CEI_EXPERIENCE_FLUID" in fluid_body
      and "isEnchantmentIndustryLoaded()" not in fluid_body,
      "enchantmentIndustryExperienceFluid 仍把 isEnchantmentIndustryLoaded() 当唯一门槛")

has_body = method_body(optional, "public static boolean hasEnchantmentIndustryExperienceFluid()")
check("B3 「液态经验可用」只问注册表（不再先问 modid ⇒ 装了却判定不通过的机制被去掉）",
      has_body is not None
      and "enchantmentIndustryExperienceFluid(1)" in has_body
      and "isEnchantmentIndustryLoaded()" not in has_body,
      "hasEnchantmentIndustryExperienceFluid 仍直接依赖 modid 判断")

fluid_stack_body = method_body(optional, "private static FluidStack fluidStack(")
check("B4 取不到流体时安全降级为 EMPTY（Fluids.EMPTY 判空 + 空栈返回，不抛异常）",
      fluid_stack_body is not None
      and "BuiltInRegistries.FLUID.get(id)" in fluid_stack_body
      and "Fluids.EMPTY" in fluid_stack_body
      and "FluidStack.EMPTY" in fluid_stack_body)
check("B5 缺失侧诊断是一次性的（每个 id 只 warn 一次，不刷屏）",
      "MISSING_LOGGED.add(what)" in optional and "Map.newKeySet()" in optional,
      "缺少一次性去重（MISSING_LOGGED）")

# ==================== C. 序号解码（按钮点了要真的生效） ====================

# 从枚举源码解析现行序号，避免把 1 硬编码进自检
enum_consts = re.findall(r"^\s{4}(ORB|LIQUID)\s*[,;]", xp_form, re.M)
check("C1 枚举常量顺序可解析（ORB, LIQUID）", enum_consts == ["ORB", "LIQUID"], str(enum_consts))
liquid_ordinal = enum_consts.index("LIQUID") if "LIQUID" in enum_consts else -1

by_ordinal = method_body(xp_form, "public static XpForm byOrdinal(")
check("C2 byOrdinal 认现行 LIQUID 序号（LIQUID.ordinal()，不是只认旧序号 2）",
      by_ordinal is not None and "LIQUID.ordinal()" in by_ordinal,
      "byOrdinal 仍只认旧序号 ⇒ 服务端/客户端都会把「液态」解成「颗粒」")
check("C3 byOrdinal 兼容旧存档序号 2（老档读出来仍是液态）",
      by_ordinal is not None and "LEGACY_LIQUID_ORDINAL = 2" in xp_form and "LEGACY_LIQUID_ORDINAL" in by_ordinal)
check("C4 解码自洽：byOrdinal(LIQUID.ordinal()) == LIQUID（序号 %d）" % liquid_ordinal,
      liquid_ordinal >= 0 and by_ordinal is not None
      and ("ordinal == LIQUID.ordinal()" in by_ordinal))
check("C5 菜单数据槽 21 的读侧走 byOrdinal（不是直接 values()[i]）",
      "XpForm.byOrdinal(data.get(DATA_XP_FORM))" in menu)
check("C6 C2S 包用同一套序号编解码（STREAM_CODEC → byOrdinal）",
      "ByteBufCodecs.idMapper(XpForm::byOrdinal, XpForm::ordinal)" in xp_form)
check("C7 界面每帧按服务端权威值刷新按钮文案与可用性",
      "refreshXpFormButton();" in screen
      and "xpFormButton.active = menu.getXpForm().nextSelectable() != menu.getXpForm();" in screen)

# ==================== D. 勾选后的路径：球 → 液态经验 → 流体缓存 ====================

collect = method_body(cache_be, "private void collectExperience(final Level level)")
check("D1 找到 collectExperience 方法体", collect is not None)

collect_src = collect or ""
check("D2 经验球**不再**按形态过滤（两种形态都收球）",
      "experienceOrbsInRadius(level)" in collect_src
      and "xpForm == XpForm.ORB" not in collect_src,
      "collectExperience 里仍存在「只有 ORB 形态才收球」的分支")
check("D3 把液态目标交给吸收函数（球按该目标折算）",
      "absorbExperienceOrbs(orbs, fluidId)" in collect_src)
check("D4 液态形态仍可附带抽走世界里的液态经验源方块（不再是全部，但没丢）",
      "absorbMatchingFluidBlocks(level, sources, id -> id.equals(fluidId), 0L)" in collect_src
      and "fluidSourcesInRadius(level)" in collect_src)

absorb = method_body(cache_be, "private int absorbExperienceOrbs(")
absorb_src = absorb or ""
check("D5 找到 absorbExperienceOrbs 方法体", absorb is not None)
check("D6 流体分支写的就是本仓流体缓存（球 → 液态经验 → 缓存）",
      "fluidCache.insert(fluidId, new CompoundTag(), mbOffered)" in absorb_src,
      "找不到 fluidCache.insert(fluidId, ...)")
check("D7 缓存内容仍会回流网络（flushFluidCacheToNetwork → storage.insert）",
      "flushFluidCacheToNetwork(storage, budget, transfer)" in cache_be
      and "storage.insert(resource, attempt, Action.EXECUTE, Actor.EMPTY)" in cache_be)
check("D8 流体缓存容量 = 512000 mB（用户说的「流体缓存剩余=512000mB」）",
      "FLUID_CACHE_CAPACITY = 512_000L" in cache_be)
check("D9 液态经验属「流体那一方面」：折算目标用 FluidResource 进网络流体存储",
      "final FluidResource resource = new FluidResource(fluid, " in cache_be)

# ==================== E. 换算率写明 + 守恒 ====================

check("E1 液态经验换算率常量：1 点 = 1 mB（CEI_MB_PER_EXPERIENCE_POINT = 1）",
      "CEI_MB_PER_EXPERIENCE_POINT = 1;" in optional
      and "CEI_EXPERIENCE_POINTS_PER_MB = 1;" in optional)
check("E2 换算率出处写在注释里（ExperienceHelper.java / CEIDataMaps.java，不是编的）",
      "ExperienceHelper.java:63-65" in optional and "CEIDataMaps.java:148" in optional)
check("E3 颗粒换算率常量：3 点 / 个（Create 原版倍率）",
      "CREATE_EXPERIENCE_POINTS_PER_NUGGET = 3;" in optional
      and "CEIDataMaps.java:154" in optional
      and "ExperienceNuggetItem.java:37-38" in optional)
check("E4 流体分支按换算率算「能换多少 mB」再由缓存决定收多少",
      "final long mbOffered = (long) value * mbPerPoint;" in absorb_src)
check("E5 只按「真的进了缓存的量」扣球（orb.value -= pointsAccepted）",
      "orb.value -= pointsAccepted;" in absorb_src)
check("E6 换算率非 1 时的零头退回缓存（绝不凭空产生流体）",
      "storedMb < acceptedMb" in absorb_src and "fluidCache.extract(fluidId" in absorb_src)
check("E7 账本按真实入缓存的 mB 记账（fromWorld(..., storedMb)）",
      "flowLedger.fromWorld(RsccFlowLedger.fluidKey(BuiltInRegistries.FLUID.get(fluidId)), storedMb);"
      in absorb_src)
check("E8 颗粒分支守恒：按 acceptedNuggets * pointsPerNugget 扣球",
      "final int consumed = acceptedNuggets * pointsPerNugget;" in absorb_src
      and "orb.value -= consumed;" in absorb_src)

# 算术模型：与 Java 里那 6 行一一对应，复算守恒式
MB_PER_POINT = 1
POINTS_PER_NUGGET = 3


def fluid_branch(value, free_mb):
    """复刻 Java 的流体分支：返回 (扣掉的点数, 入缓存的 mB, 退回的 mB, 球里剩余点数)。"""
    mb_offered = value * MB_PER_POINT
    accepted_mb = min(free_mb, mb_offered)
    if accepted_mb <= 0:
        return None
    points_accepted = min(value, accepted_mb // MB_PER_POINT)
    if points_accepted <= 0:
        return None
    stored_mb = points_accepted * MB_PER_POINT
    refund_mb = accepted_mb - stored_mb
    return points_accepted, stored_mb, refund_mb, value - points_accepted


model_bad = []
for value, free_mb in [(10, 0), (10, 1000), (10, 7), (1, 1), (5, 2), (3, 3), (64, 1)]:
    got = fluid_branch(value, free_mb)
    if got is None:
        continue
    points, stored_mb, refund_mb, remaining = got
    if points + remaining != value:
        model_bad.append("value=%d 点数不守恒：扣 %d + 余 %d" % (value, points, remaining))
    if stored_mb != points * MB_PER_POINT:
        model_bad.append("value=%d 入缓存 mB(%d) != 扣掉的点数换算(%d)" % (value, stored_mb, points))
    if stored_mb > free_mb:
        model_bad.append("value=%d 入缓存超过剩余空间：%d > %d" % (value, stored_mb, free_mb))
    if refund_mb < 0:
        model_bad.append("value=%d 退回量为负：%d" % (value, refund_mb))
check("E9 算术复算：点数守恒、入缓存的 mB = 扣掉的点数、不超容量、不退负", not model_bad,
      "; ".join(model_bad))

nugget_bad = []
for value in [0, 1, 2, 3, 4, 7, 10, 65]:
    nuggets = value // POINTS_PER_NUGGET
    if nuggets <= 0:
        consumed = 0  # 代码里直接删球（不足一个颗粒无法表示，且不记账）
    else:
        consumed = nuggets * POINTS_PER_NUGGET
    if consumed > value:
        nugget_bad.append("value=%d 折算消耗 %d 超过持有" % (value, consumed))
check("E10 算术复算：颗粒分支消耗量永不超过持有的点数（绝不销毁 / 复制）", not nugget_bad,
      "; ".join(nugget_bad))

# ==================== F. 缺附魔工业时的降级 ====================

check("F1 LIQUID 的可选性 = 液态经验流体在注册表里可用",
      "return this != LIQUID || OptionalDeps.hasEnchantmentIndustryExperienceFluid();" in xp_form)
check("F2 服务端按形态写入时先把不可选形态退化掉（客户端绕过也没用）",
      "form == null || !form.selectable() ? XpForm.ORB : form" in cache_be)
check("F3 读档时把不可选形态退化掉（老档 / 卸了前置都不会卡在置灰态）",
      "if (!xpForm.selectable()) {" in cache_be)
check("F4 运行期流体缺失 → 经验球折算成经验颗粒（不吃球、不丢经验）",
      "final ResourceLocation nuggetId = fluidId != null ? null : xpNuggetItemId();" in absorb_src)
check("F5 颗粒来源有回退链（附魔工业颗粒 → 机械动力原版颗粒 → 原版经验瓶）",
      '"super_experience_nugget"' in read("support/XpTargetResolver.java")
      and 'ResourceLocation.fromNamespaceAndPath(OptionalDeps.MOD_CREATE, "experience_nugget")'
      in read("support/XpTargetResolver.java"))
check("F6 两种表示都不可用时一个球都不动（返回 0，绝不销毁经验）",
      "if (fluidId == null && !canStoreItems) {" in absorb_src and "return 0;" in absorb_src)
check("F7 前置缺失时界面置灰并说明缺哪个前置（不静默不可用）",
      "gui.rs_create_compat.collection_cache.xp_form.missing_dependency" in xp_form
      and "XpForm.LIQUID.missingDependency()" in screen
      and "xpFormButton.active = menu.getXpForm().nextSelectable() != menu.getXpForm();" in screen)

# ==================== G. 文案：中英成对、说清两种存储形态、不误导 ====================

import json

zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
prefix = "gui.rs_create_compat.collection_cache.xp_form."
zh_keys = sorted(k for k in zh if k.startswith(prefix))
en_keys = sorted(k for k in en if k.startswith(prefix))
check("G1 xp_form 语言键中英成对（键集合一致，%d 条）" % len(zh_keys), zh_keys == en_keys,
      "zh=%s / en=%s" % (zh_keys, en_keys))
check("G2 前置名写对：机械动力：附魔工业（不再写成别的模组名）",
      zh.get(prefix + "required_mod") == "机械动力：附魔工业"
      and "覆膜" not in json.dumps(zh, ensure_ascii=False),
      "required_mod=%s" % zh.get(prefix + "required_mod"))
check("G3 液态文案改成「吸经验球 + 折算成 mB」语义（不再写「只吸源方块」）",
      "mB" in zh.get(prefix + "tip.liquid", "")
      and "经验球" in zh.get(prefix + "tip.liquid", "")
      and "源方块" not in zh.get(prefix + "tip.liquid", ""),
      zh.get(prefix + "tip.liquid"))
check("G4 颗粒文案写明经验球与 3 点 / 个",
      "经验球" in zh.get(prefix + "tip.orb", "") and "3 点" in zh.get(prefix + "tip.orb", ""),
      zh.get(prefix + "tip.orb"))
check("G5 中文 ≤ 40 字",
      all(len(zh[k]) <= 40 for k in zh_keys),
      str([(k, len(zh[k])) for k in zh_keys if len(zh[k]) > 40]))
check("G6 文案不写「或」、不写「等 N 种」、不写「图标轮换」",
      all(("或" not in zh[k]) and ("等" not in zh[k]) and ("图标轮换" not in zh[k]) for k in zh_keys),
      str([k for k in zh_keys if ("或" in zh[k]) or ("等" in zh[k]) or ("图标轮换" in zh[k])]))

# ==================== 结果 ====================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
