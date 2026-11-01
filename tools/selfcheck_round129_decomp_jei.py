#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 129 轮自检：降解室发电机「JEI 兼容」—— 点箭头跳 JEI，看「用什么 → 发多少电 → 耗多久」。

用户原话
--------
「将这个发电机完善一下……添加 JEI 兼容：那个熔炉中间那个箭头点了之后不是会跳转到 JEI 界面去查看吗，
我们这个箭头也要这样子，并且查看的用什么东西、可以发多少电、消耗多久。」

本轮做成两件事
--------------
1. **JEI 真的有了一个分类页**（不是 ghost / transfer，本工程此前 4 个 @JeiPlugin 都没有注册分类）：
   输入槽（可放什么）+ 发电量 + 耗时 + 平均 FE/t + 副产物槽（带概率）。
2. **机器界面中间那个熔炉箭头变成了 JEI 可点区域**：输入格有东西就直接定位到那一件的配方，
   空着就打开整个分类。

为什么要连「数据下发」一起做（本轮最容易被漏的坑）
------------------------------------------------
降解表由 `data/<ns>/decomposition/*.json` 驱动，加载器只挂在**服务端**的
`AddReloadListenerEvent` 上。专用服务器上客户端那张表恒空 ⇒ 如果 JEI 直接读本机表，
分类页会**一条都没有**而且不报错（静默失效）。因此本轮加了 S2C 整表快照
（`network/SyncDecompositionDisplayPacket`，发送时机 `OnDatapackSyncEvent` = 进服 + /reload），
客户端落到只读镜像 `support/DecompositionDisplayCache`，JEI 只读镜像；单人游戏镜像还没到的时候
退回本机表（同 JVM，数据本来就是权威）。

六组断言（每组都只认**源码 token / 数据形状**，不认注释）
--------------------------------------------------------
① 数据通道：displayEntries 展开 tag ↔ 包形状 ↔ 注册 ↔ 客户端只读镜像 ↔ 降级路径。
② JEI 分类页：类型 id / 四个注册钩子 / 催化剂 / 页面布局 / 配方 id / 数值口径（只有一份真相）。
③ 箭头 → JEI：可点区域坐标取自 `DecompositionGeneratorSpec`（不许裸数字）、
   关掉 JEI 默认 tooltip（防两个框叠一起）、两条分支（有输入 → showRecipes，无 → showTypes）、
   界面侧提示只在装了 JEI 时出现（JEI 是可选依赖，界面不得 import 它）。
④ 语言键：15 个新键中英成对、`%s` 个数一致、调用处实参个数 == 占位符个数（否则 String.format 抛异常）。
⑤ 反例自证：把坏写法拼回源码副本，断言上面的判据真的判负（证明判据有区分力）。
⑥ 专服安全：`support/**` 与 `network/**` 里新增的 5 个文件一个客户端类型都不许出现。

纪律：不跑 gradle / javac、不 git、不改源码；只读 + 纯 Python 模型。
用法：python tools/selfcheck_round129_decomp_jei.py
退出码：0 = 全部通过；1 = 有失败。最后一行：ALL_PASS / FAILED。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")
MODID = "rs_create_compat"

FILES = {
    "entry": os.path.join(JAVA, "support", "DecompositionDisplayEntry.java"),
    "cache": os.path.join(JAVA, "support", "DecompositionDisplayCache.java"),
    "table": os.path.join(JAVA, "support", "DecompositionTable.java"),
    "packet": os.path.join(JAVA, "network", "SyncDecompositionDisplayPacket.java"),
    "sync": os.path.join(JAVA, "network", "DecompositionDisplaySync.java"),
    "hooks": os.path.join(JAVA, "network", "ClientPayloadHooks.java"),
    "sink": os.path.join(JAVA, "client", "ClientPayloadSink.java"),
    "main": os.path.join(JAVA, "RS_Create_Compat.java"),
    "plugin": os.path.join(JAVA, "client", "DecompositionGeneratorJeiPlugin.java"),
    "category": os.path.join(JAVA, "client", "DecompositionGeneratorJeiCategory.java"),
    "recipe": os.path.join(JAVA, "client", "DecompositionJeiRecipe.java"),
    "text": os.path.join(JAVA, "client", "RsccJeiText.java"),
    "screen": os.path.join(JAVA, "client", "screen", "DecompositionGeneratorScreen.java"),
    "lang_zh": os.path.join(RES, "assets", MODID, "lang", "zh_cn.json"),
    "lang_en": os.path.join(RES, "assets", MODID, "lang", "en_us.json"),
}

#: 本轮新增的语言键（JEI 页 + 界面箭头提示）。值里 `%s` 的个数由 ④ 组逐一核对。
JEI_KEYS = (
    "jei.rs_create_compat.decomposition_generator.title",
    "jei.rs_create_compat.decomposition_generator.energy",
    "jei.rs_create_compat.decomposition_generator.time",
    "jei.rs_create_compat.decomposition_generator.rate",
    "jei.rs_create_compat.decomposition_generator.byproduct",
    "jei.rs_create_compat.decomposition_generator.no_byproduct",
    "jei.rs_create_compat.decomposition_generator.more_byproduct",
    "jei.rs_create_compat.decomposition_generator.chance",
    "jei.rs_create_compat.decomposition_generator.chance_always",
    "jei.rs_create_compat.decomposition_generator.arrow_tip_speed",
    "jei.rs_create_compat.decomposition_generator.arrow_tip_stack",
    "jei.rs_create_compat.decomposition_generator.info_energy",
    "jei.rs_create_compat.decomposition_generator.info_time",
    "jei.rs_create_compat.decomposition_generator.info_upgrade",
    "gui.rs_create_compat.decomposition_generator.jei_hint",
)

#: 允许出现客户端类型的目录前缀（其余新增文件一律不许）。本轮新增的 5 个双端文件都在前两组里。
CLIENT_ONLY_PREFIX = "cretae/cookiewyq/rs_create_compat/client/"
CLIENT_TYPE_RE = re.compile(
    r"net\.minecraft\.client\.|com\.mojang\.blaze3d\.|org\.lwjgl\.|net\.neoforged\.neoforge\.client\."
    r"|cretae\.cookiewyq\.rs_create_compat\.client\.")


def read(path):
    if not os.path.exists(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """只去注释、保留字符串字面量（判据要看得到语言键与 `"..."` 这类代码特征）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def load_sources():
    return {key: read(path) for key, path in FILES.items()}


# ======================================================================================
# ① 数据通道
# ======================================================================================

def section_channel(src):
    out = []
    table = strip_comments(src["table"])
    entry = strip_comments(src["entry"])
    cache = strip_comments(src["cache"])
    packet = strip_comments(src["packet"])
    sync = strip_comments(src["sync"])
    hooks = strip_comments(src["hooks"])
    sink = strip_comments(src["sink"])
    main = strip_comments(src["main"])

    # displayEntries：tag 必须「查询时展开」而不是在重载时固化（与原表的懒匹配口径一致）
    out.append(("①a displayEntries 存在且从 state 快照出发展开",
                "public static List<DecompositionDisplayEntry> displayEntries()" in table
                and "final State snapshot = state;" in table))
    out.append(("①a tag 条目用 getTagOrEmpty 现查展开（不做重载期固化）",
                "BuiltInRegistries.ITEM.getTagOrEmpty(binding.tag())" in table))
    out.append(("①a 精确条目只放它自己（List.of(id)）", "List.of(id)" in table))
    out.append(("①a 物品 id 走注册表（BuiltInRegistries.ITEM.getKey）",
                "BuiltInRegistries.ITEM.getKey(" in table))
    out.append(("①a 返回不可变快照（List.copyOf）", "return List.copyOf(entries);" in table))

    # 条目形状：纯数据（key/items/time/energy/results）
    out.append(("①b 条目是纯数据 record（key/items/time/energy/results 五个字段）",
                "public record DecompositionDisplayEntry(String key, List<String> items, int time, int energy,"
                in entry
                and "List<Result> results)" in entry))
    out.append(("①b 条目没有第二套数值换算（不引用 effectiveTime / 升级）",
                "effectiveTime" not in entry and "Speed" not in entry))

    # 包：上限 + enqueueWork + 间接层
    out.append(("①c 包有解码上限（MAX_ENTRIES / MAX_ITEMS / MAX_RESULTS）",
                all(token in packet for token in
                    ("MAX_ENTRIES = 4096", "MAX_ITEMS = 4096", "MAX_RESULTS = 64"))))
    out.append(("①c 处理体 enqueueWork 到客户端主线程",
                "ctx.enqueueWork(" in packet))
    out.append(("①c 处理体经唯一间接层 ClientPayloadHooks（不在 network 包碰客户端）",
                "ClientPayloadHooks.get().syncDecompositionDisplay(packet)" in packet))
    out.append(("①c 包 id 固定（改它等于让旧客户端收不到数据）",
                '"sync_decomposition_display"' in packet))

    # 服务端发送时机
    out.append(("①d 发送挂在 OnDatapackSyncEvent（进服 + /reload 都覆盖）",
                "OnDatapackSyncEvent" in sync and "@SubscribeEvent" in sync))
    out.append(("①d 用 getRelevantPlayers（进服只发该玩家、/reload 发全体）",
                "event.getRelevantPlayers().forEach" in sync))
    out.append(("①d 发送的数据来自 DecompositionTable.displayEntries()",
                "DecompositionTable.displayEntries()" in sync))
    out.append(("①d 订阅在 game bus（@EventBusSubscriber + modid）",
                "@EventBusSubscriber(modid = RS_Create_Compat.MODID)" in sync))

    # 注册
    out.append(("①e 主类注册了 playToClient（S2C 方向）",
                "SyncDecompositionDisplayPacket.TYPE" in main
                and "SyncDecompositionDisplayPacket.STREAM_CODEC" in main
                and "SyncDecompositionDisplayPacket::handle" in main))
    out.append(("①e 注册点在 registerPayloads 方法体内",
                _inside_method(main, "registerPayloads", "SyncDecompositionDisplayPacket.TYPE")))
    out.append(("①e handle 静态方法存在", "public static void handle(final SyncDecompositionDisplayPacket packet"
                in packet))

    # 客户端镜像
    out.append(("①f ClientPayloadHooks 是 default 空实现（专服不加载客户端）",
                "default void syncDecompositionDisplay(final SyncDecompositionDisplayPacket packet)" in hooks))
    out.append(("①f ClientPayloadSink 写只读镜像",
                "public void syncDecompositionDisplay(" in sink
                and "DecompositionDisplayCache.accept(packet.entries())" in sink))
    out.append(("①g 镜像可写可清（accept / clear）",
                "public static void accept(" in cache and "public static void clear()" in cache))
    out.append(("①g 没收到镜像时退回本机表（单人游戏 / 镜像未到达）",
                "if (received) {" in cache and "return DecompositionTable.displayEntries();" in cache))
    out.append(("①g 收到镜像后以镜像为准（哪怕是空表）",
                "received = true;" in cache and "return synced;" in cache))
    return out


def _inside_method(code, method_name, token):
    """token 是否出现在某个方法体内（用大括号配平粗定位；只用于「注册点必须在 registerPayloads 里」这类判据）。"""
    match = re.search(r"%s\s*\([^)]*\)\s*\{" % re.escape(method_name), code)
    if match is None:
        return False
    start = match.end() - 1
    depth = 0
    for index in range(start, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return token in code[start:index]
    return False


# ======================================================================================
# ② JEI 分类页
# ======================================================================================

def section_category(src):
    out = []
    plugin = strip_comments(src["plugin"])
    category = strip_comments(src["category"])
    recipe = strip_comments(src["recipe"])
    text = strip_comments(src["text"])

    out.append(("②a 插件带 @JeiPlugin（JEI 才会加载它）", "@JeiPlugin" in plugin))
    out.append(("②a 插件 uid 唯一（decomposition_generator_jei）",
                '"decomposition_generator_jei"' in plugin))
    out.append(("②a 配方类型 id = rs_create_compat:decomposition_generator",
                'ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "decomposition_generator")'
                in plugin))
    out.append(("②a RecipeType.create 用同一个 id + 自己的 record 类（不是原版 Recipe）",
                "RecipeType.create(RECIPE_TYPE_ID.getNamespace(), RECIPE_TYPE_ID.getPath(),"
                in plugin
                and "DecompositionJeiRecipe.class)" in plugin))

    for hook in ("registerCategories", "registerRecipes", "registerRecipeCatalysts", "registerGuiHandlers"):
        out.append(("②b 实现了 %s" % hook, "public void %s(" % hook in plugin))
    out.append(("②b 分类实例只存一份（点击处理体复用它去 showRecipes）",
                "category = created;" in plugin))
    out.append(("②b 配方表在注册时缓存（点击时按物品查）",
                "registeredRecipes = recipes;" in plugin))

    out.append(("②c 催化剂 = 机器物品本身（物品页能反查「它吃什么」）",
                "registration.addRecipeCatalyst(new ItemStack(" in plugin
                and "DECOMPOSITION_GENERATOR_ITEM" in plugin))
    out.append(("②c 机器物品带一条 info 页（三条最容易误解的规则）",
                "registration.addIngredientInfo(" in plugin
                and all(key in plugin for key in ("info_energy", "info_time", "info_upgrade"))))

    out.append(("②d 分类继承 AbstractRecipeCategory", "extends AbstractRecipeCategory<DecompositionJeiRecipe>"
                in category))
    out.append(("②d 页面尺寸常量存在（WIDTH = 200：英文最长行装得下）",
                "public static final int WIDTH = 200;" in category))
    out.append(("②d 输入槽放 recipe.inputs()（tag 条目 = 整组候选轮播）",
                "addInputSlot(INPUT_X, INPUT_Y)" in category
                and ".addItemStacks(recipe.inputs())" in category))
    out.append(("②d 副产物是输出槽（带上数量），且格数有上限",
                "builder.addOutputSlot(" in category
                and "BYPRODUCT_MAX" in category
                and "byproduct.stack()" in category))
    out.append(("②d 概率写在槽 tooltip 上（100% 说「必定掉落」）",
                "addRichTooltipCallback((view, tooltip) -> tooltip.add(chanceLine))" in category
                and "chancePercent() >= 100" in category))
    out.append(("②d 箭头用 JEI 自己的 addRecipeArrow（不新造贴图）",
                "builder.addRecipeArrow().setPosition(ARROW_X, ARROW_Y)" in category))
    out.append(("②d 三行文字 = 发电 / 耗时 / 平均",
                all(key in category for key in ("KEY_ENERGY", "KEY_TIME", "KEY_RATE"))))
    out.append(("②d 无副产物时明说「只发电」", "KEY_NO_BYPRODUCT" in category))
    out.append(("②d 副产物超过 4 项时给出「另有 N 项」",
                "KEY_MORE_BYPRODUCT" in category and "BYPRODUCT_MORE_X" in category))
    out.append(("②d 配方 id 供 JEI 书签使用（getRegistryName → recipe.id()）",
                "public net.minecraft.resources.ResourceLocation getRegistryName(" in category
                and "return recipe.id();" in category))
    out.append(("②d 悬停箭头给升级说明（纯定性，不给假想数值）",
                "KEY_ARROW_TIP_SPEED" in category and "KEY_ARROW_TIP_STACK" in category))

    # ②d-几何：把「会不会互相压到」变成可判定的算术（这套判据是像素示意图抓出来的）
    out.extend(_geometry_checks(category))


    # 配方数据：从镜像来、空输入跳过、id 稳定
    out.append(("②e 配方从客户端镜像构建", "DecompositionDisplayCache.entries()" in recipe))
    out.append(("②e 输入解析不出来就整条跳过（JEI 不允许空输入槽）",
                "if (inputs.isEmpty()) {" in recipe and "continue;" in recipe))
    out.append(("②e 副产物解析失败只跳过那一项（不是整条丢）",
                "if (!stack.isEmpty()) {" in recipe))
    out.append(("②e 配方 id 由 key 推导（item → decomposition/ns/path，tag → decomposition/tag/ns/path）",
                '"decomposition/tag/"' in recipe and '"decomposition/"' in recipe))
    out.append(("②e id 里的 ':' 换成 '/'（ResourceLocation path 不允许冒号）",
                "} else if (c == ':') {" in recipe and "path.append('/');" in recipe))
    out.append(("②e 点击定位用 matches()（同物品即可，比 NBT 全等更宽容）",
                "ItemStack.isSameItem(input, stack)" in recipe))

    # 数值口径：只有一份真相
    out.append(("②f 分类页 / 配方都不做速度升级换算（不给「升级后」的第二天真相）",
                "effectiveTime" not in category and "effectiveTime" not in recipe
                and "RsccRateLimiter" not in category and "RsccRateLimiter" not in recipe))
    out.append(("②f 没有往 JEI 注册自定义 ingredient（能量画成文字，不伪造物品）",
                "IModIngredientRegistration" not in plugin and "registerIngredients" not in plugin))

    # 数字格式：唯一落点
    out.append(("②g 数字格式集中在 RsccJeiText（千分位 / 1 位小数 / 去零）",
                "static String grouped(" in text and "static String oneDecimal(" in text
                and "static String rate(" in text))
    out.append(("②g 格式串带 Locale.ROOT（避免区域设置把小数点写成逗号）",
                text.count("Locale.ROOT") >= 2))
    out.append(("②g 发电量走千分位（与 GUI 的缓存读数同格式）",
                "RsccJeiText.grouped(recipe.energy())" in category))
    out.append(("②g 平均功率走去零小数", "RsccJeiText.rate(" in category))
    return out


# ======================================================================================
# ②d-几何：页面布局的算术判据
# ======================================================================================

_CONST_RE = re.compile(r"static\s+final\s+int\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);")
#: 原版配方箭头（JEI `addRecipeArrow`）的精灵宽度：24×17。
ARROW_SPRITE_WIDTH = 24
#: 「副产物：」标签里最长的那个词（中文 4 个全角字 / 英文 `Byproducts:` 10 个 ASCII）取宽的一边。
BYPRODUCT_LABEL_MAX_WIDTH = 60
#: 「另有 N 项」的估算宽度（6 个全角字 ≈ 54）。
BYPRODUCT_MORE_MAX_WIDTH = 54
#: 原版槽的边长（18，含边框）。
SLOT_SIZE = 18
#: 三行文字里最长一行的宽度（实测 ≈131px：英文 `Time: 180 ticks (9.0 s)`；MC 字宽 ASCII 6 / 空格 4 /
#: 全角 9）。页面宽度必须让 `WIDTH - TEXT_X - 2 >= 本值`，否则英文页面会被 JEI 截成省略号。
LONGEST_TEXT_LINE_WIDTH = 131


def int_consts(text):
    """解析 `static final int NAME = <纯算术/常量引用>`（十六进制也支持）。"""
    code = strip_comments(text)
    raw = {}
    for match in _CONST_RE.finditer(code):
        raw[match.group(1)] = match.group(2).strip()
    resolved = {}

    def resolve(name, depth=0):
        if name in resolved:
            return resolved[name]
        if depth > 8 or name not in raw:
            raise KeyError(name)
        expr = raw[name]
        for ident in set(re.findall(r"(?<![\w$])([A-Za-z_$][\w$]*)", expr)):
            expr = re.sub(r"\b%s\b" % re.escape(ident), str(resolve(ident, depth + 1)), expr)
        if not re.fullmatch(r"[0-9a-fA-Fx+\-*/ ().]+", expr):
            raise ValueError(raw[name])
        value = int(eval(expr, {"__builtins__": {}}, {}))  # noqa: S307 - 只允许数字与四则运算
        resolved[name] = value
        return value

    for name in raw:
        resolve(name)
    return resolved


def _geometry_checks(category):
    """页面上的方块会不会互相压到 —— 纯算术（不跑游戏、不渲染）。"""
    out = []
    try:
        geom = int_consts(category)
        need = ("WIDTH", "HEIGHT", "INPUT_X", "INPUT_Y", "ARROW_X", "ARROW_Y", "TEXT_X",
                "TEXT_ENERGY_Y", "TEXT_TIME_Y", "TEXT_RATE_Y", "BYPRODUCT_LABEL_Y",
                "BYPRODUCT_X", "BYPRODUCT_Y", "BYPRODUCT_STEP", "BYPRODUCT_MAX",
                "BYPRODUCT_MORE_X")
        missing = [name for name in need if name not in geom]
        if missing:
            return [("②d-几何 所有几何常量都能解析", False, ",".join(missing))]
    except (KeyError, ValueError) as error:
        return [("②d-几何 所有几何常量都能解析", False, str(error))]

    out.append(("②d-几何 输入槽与箭头不重叠",
                geom["INPUT_X"] + SLOT_SIZE <= geom["ARROW_X"],
                "%d+%d vs %d" % (geom["INPUT_X"], SLOT_SIZE, geom["ARROW_X"])))
    out.append(("②d-几何 箭头与文字列不重叠",
                geom["ARROW_X"] + ARROW_SPRITE_WIDTH <= geom["TEXT_X"],
                "%d+%d vs %d" % (geom["ARROW_X"], ARROW_SPRITE_WIDTH, geom["TEXT_X"])))
    out.append(("②d-几何 三行文字行距够（每行 8px 不叠字）",
                all(geom[later] - geom[earlier] >= 9 for earlier, later in
                    (("TEXT_ENERGY_Y", "TEXT_TIME_Y"), ("TEXT_TIME_Y", "TEXT_RATE_Y")))))
    out.append(("②d-几何 文字列装得下最长一行（≈%dpx，英文耗时行）" % LONGEST_TEXT_LINE_WIDTH,
                geom["WIDTH"] - geom["TEXT_X"] - 2 >= LONGEST_TEXT_LINE_WIDTH,
                "%d vs %d" % (geom["WIDTH"] - geom["TEXT_X"] - 2, LONGEST_TEXT_LINE_WIDTH)))
    out.append(("②d-几何 第三行文字没有超出页面底边",
                geom["TEXT_RATE_Y"] + 8 <= geom["HEIGHT"]))
    out.append(("②d-几何 「副产物」标签独占一行（不与槽同排）",
                geom["BYPRODUCT_LABEL_Y"] + 8 <= geom["BYPRODUCT_Y"],
                "%d+8 vs %d" % (geom["BYPRODUCT_LABEL_Y"], geom["BYPRODUCT_Y"])))
    last_right = geom["BYPRODUCT_X"] + geom["BYPRODUCT_STEP"] * (geom["BYPRODUCT_MAX"] - 1) + SLOT_SIZE
    out.append(("②d-几何 副产物槽没超出页面右边", last_right <= geom["WIDTH"] - 2,
                "%d vs %d" % (last_right, geom["WIDTH"] - 2)))
    out.append(("②d-几何 槽底没超出页面底边",
                geom["BYPRODUCT_Y"] + SLOT_SIZE <= geom["HEIGHT"] - 2,
                "%d+%d vs %d" % (geom["BYPRODUCT_Y"], SLOT_SIZE, geom["HEIGHT"] - 2)))
    out.append(("②d-几何 「另有 N 项」文字宽度装得下",
                geom["BYPRODUCT_MORE_X"] + BYPRODUCT_MORE_MAX_WIDTH <= geom["WIDTH"] - 2,
                "%d+%d vs %d" % (geom["BYPRODUCT_MORE_X"], BYPRODUCT_MORE_MAX_WIDTH,
                                 geom["WIDTH"] - 2)))
    out.append(("②d-几何 「副产物」标签（最宽按英文 Byproducts: 算）装得下",
                BYPRODUCT_LABEL_MAX_WIDTH <= geom["WIDTH"] - 2,
                "%d vs %d" % (BYPRODUCT_LABEL_MAX_WIDTH, geom["WIDTH"] - 2)))
    return out


# ======================================================================================
# ③ 箭头 → JEI
# ======================================================================================

def _click_area_block(plugin):
    """取出可点区域 getArea() 的方法体（③ 组的裸数字判据只看这一块）。"""
    return _method_body(plugin, r"public Rect2i getArea\(\)") or ""


def _method_body(code, signature_regex):
    """按签名正则取出方法体（含大括号内部文本；找不到返回 None）。"""
    match = re.search(signature_regex + r"\s*\{", code)
    if match is None:
        return None
    start = match.end() - 1
    depth = 0
    for index in range(start, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[start:index]
    return None


def section_click(src):
    out = []
    plugin = strip_comments(src["plugin"])
    screen = strip_comments(src["screen"])

    out.append(("③a 可点区域挂在 DecompositionGeneratorScreen 上",
                "registration.addGuiContainerHandler(DecompositionGeneratorScreen.class," in plugin))
    block = _click_area_block(plugin)
    out.append(("③a 可点区域坐标全部取自 Spec（ARROW_X / ARROW_Y / ARROW_WIDTH / ARROW_HEIGHT）",
                all(token in block for token in (
                    "DecompositionGeneratorSpec.ARROW_X", "DecompositionGeneratorSpec.ARROW_Y",
                    "DecompositionGeneratorSpec.ARROW_WIDTH", "DecompositionGeneratorSpec.ARROW_HEIGHT"))))
    # 机器界面的箭头是 (86,45,24,16)：这些数字出现在可点区域里就等于「第二份几何」，必须判负
    naked = [number for number in ("86", "45", "24", "16") if re.search(r"\b%s\b" % number, block)]
    out.append(("③a 可点区域里没有裸坐标数字", not naked, ",".join(naked)))

    tip_body = _method_body(plugin, r"public boolean isTooltipEnabled\(\)")
    out.append(("③b 关掉 JEI 的默认 tooltip（否则与界面自己的进度框叠在一起）",
                tip_body is not None and "return false;" in tip_body and "return true" not in tip_body,
                "" if tip_body is None else tip_body.strip().replace("\n", " ")[:80]))

    out.append(("③c 有输入 → showRecipes 直接定位到那一件",
                "recipesGui.showRecipes(currentCategory, List.of(match)," in plugin))
    out.append(("③c 无输入 / 不在表里 → showTypes 打开整个分类",
                "recipesGui.showTypes(List.of(RECIPE_TYPE))" in plugin))
    out.append(("③c 焦点是「输入物品栈」（JEI 才知道要突出哪个槽）",
                "focusFactory.createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, target)" in plugin))
    out.append(("③c 逐个输入格找「有配方的第一件」（不靠未同步的当前槽下标）",
                "for (int index = 0; index < DecompositionGeneratorSpec.INPUT_COUNT; index++)" in plugin))

    # 界面侧提示
    out.append(("③d 界面箭头 tooltip 同时给进度与点击提示",
                "gui.rs_create_compat.decomposition_generator.progress_short" in screen
                and "gui.rs_create_compat.decomposition_generator.jei_hint" in screen))
    out.append(("③d 点击提示只在装了 JEI 时出现（没装就别误导）",
                "if (jeiPresent()) {" in screen and "isLoaded(\"jei\")" in screen))
    out.append(("③d 界面不 import 任何 JEI 类型（JEI 是可选依赖）",
                "mezz.jei" not in strip_comments(src["screen"])))
    out.append(("③d 进度读数仍然存在（没有为了 JEI 把箭头读数删掉）",
                "decomposition_generator.progress_short" in screen
                and "drawProgressReadout(guiGraphics);" in screen))
    return out


# ======================================================================================
# ④ 语言键
# ======================================================================================

def _translate_calls(src, const_name):
    """`Component.translatable(KEY_X` 调用里的实参个数（不含常量本身）。"""
    code = strip_comments(src)
    match = re.search(r"Component\.translatable\(\s*%s\b" % re.escape(const_name), code)
    if match is None:
        return None
    start = match.end()
    depth = 0
    index = start
    while index < len(code):
        char = code[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if depth == 0:
                break
            depth -= 1
        index += 1
    body = code[start:index]
    body = body.lstrip()
    if not body.startswith(","):
        return 0
    body = body[1:]
    # 统计顶层逗号 + 1（粗略但足够：这里只会是 int / 方法调用 / 简单字符串）
    depth = 0
    commas = 0
    in_string = False
    previous = ""
    for char in body:
        if in_string:
            if char == '"' and previous != "\\":
                in_string = False
        elif char == '"':
            in_string = True
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            commas += 1
        previous = char
    return commas + 1 if body.strip() else 0


def section_lang(src):
    out = []
    try:
        zh = json.loads(src["lang_zh"])
        en = json.loads(src["lang_en"])
    except json.JSONDecodeError as error:
        return [("④ zh_cn / en_us 是合法 JSON", False, str(error))]

    for key in JEI_KEYS:
        out.append(("④ %s 在 zh_cn 存在" % key, key in zh))
        out.append(("④ %s 在 en_us 存在" % key, key in en))

    def placeholders(value):
        return len(re.findall(r"(?<!%)%s", value))

    for key in JEI_KEYS:
        zh_value = zh.get(key, "")
        en_value = en.get(key, "")
        out.append(("④ %s 的 %%s 个数 zh/en 一致" % key,
                    placeholders(zh_value) == placeholders(en_value),
                    "%d vs %d" % (placeholders(zh_value), placeholders(en_value))))

    # 调用处实参个数 == 占位符个数（错一个就 String.format 抛 MissingFormatArgumentException）
    mapping = {
        "energy": ("KEY_ENERGY", "jei.rs_create_compat.decomposition_generator.energy", ("category",)),
        "time": ("KEY_TIME", "jei.rs_create_compat.decomposition_generator.time", ("category",)),
        "rate": ("KEY_RATE", "jei.rs_create_compat.decomposition_generator.rate", ("category",)),
        "more_byproduct": ("KEY_MORE_BYPRODUCT",
                           "jei.rs_create_compat.decomposition_generator.more_byproduct", ("category",)),
        "chance": ("KEY_CHANCE", "jei.rs_create_compat.decomposition_generator.chance", ("category",)),
    }
    for label, (const_name, key, owners) in mapping.items():
        expected = placeholders(zh.get(key, ""))
        calls = [_translate_calls(src[owner], const_name) for owner in owners]
        out.append(("④ %s 的调用处实参个数 == 占位符个数" % label,
                    all(call == expected for call in calls),
                    "%s vs %d" % (calls, expected)))

    # chance 里的字面百分号必须写成 %%（否则 String.format 把 % 当占位符开头直接抛异常）
    chance_zh = zh.get("jei.rs_create_compat.decomposition_generator.chance", "")
    chance_en = en.get("jei.rs_create_compat.decomposition_generator.chance", "")
    out.append(("④ chance 文案的字面百分号写成 %%", "%%" in chance_zh and "%%" in chance_en))
    # 不补省略号（第 104 轮口径：放不下缩字号，不截断）
    ellipsis = [key for key in JEI_KEYS if "…" in zh.get(key, "") or "..." in zh.get(key, "")]
    out.append(("④ 新文案没有省略号", not ellipsis, ",".join(ellipsis)))
    # 中文值必须有中文（英文占位 = 漏翻译）
    no_cjk = [key for key in JEI_KEYS if key in zh and not re.search(r"[\u4e00-\u9fff]", str(zh[key]))]
    out.append(("④ 中文值里都真的有中文", not no_cjk, ",".join(no_cjk)))
    return out


# ======================================================================================
# ⑥ 专服安全（本轮新增文件）
# ======================================================================================

NEW_DUAL_FILES = {
    "entry": "support/DecompositionDisplayEntry",
    "cache": "support/DecompositionDisplayCache",
    "table": "support/DecompositionTable",
    "packet": "network/SyncDecompositionDisplayPacket",
    "sync": "network/DecompositionDisplaySync",
}


def section_dedicated(src):
    out = []
    for key, label in NEW_DUAL_FILES.items():
        code = strip_comments(src[key])
        hit = CLIENT_TYPE_RE.search(code)
        out.append(("⑥ %s 无客户端类型引用" % label, hit is None, hit.group(0) if hit else ""))
    hooks = strip_comments(src["hooks"])
    out.append(("⑥ ClientPayloadHooks 里新增的只是 default 空实现（专服无副作用）",
                "default void syncDecompositionDisplay" in hooks
                and "net.minecraft.client." not in hooks))
    return out


# ======================================================================================
# 主流程
# ======================================================================================

def run_all(src):
    results = []
    for section in (section_channel, section_category, section_click, section_lang, section_dedicated):
        for item in section(src):
            name, ok = item[0], item[1]
            detail = item[2] if len(item) > 2 else ""
            results.append((name, bool(ok), detail))
    return results


def failures(results):
    return [name for name, ok, _detail in results if not ok]


def main() -> int:
    sources = load_sources()
    missing = [key for key, path in FILES.items() if not os.path.exists(path)]
    for key in missing:
        print("MISSING 文件 %s：%s" % (key, FILES[key]))
    if missing:
        print("FAILED")
        return 1

    print("第 129 轮自检：降解室发电机 JEI 兼容")
    print("")
    results = run_all(sources)
    for name, ok, detail in results:
        if ok:
            print("PASS " + name)
        else:
            print("FAIL " + name + (("  <- " + detail) if detail else ""))
    bad = failures(results)
    print("")
    print("检查项：%d，失败：%d" % (len(results), len(bad)))
    if bad:
        for name in bad:
            print("  FAIL " + name)

    # ---------------- ⑤ 反例自证 ----------------
    print("")
    print("=== 反例自证：把坏写法拼回源码副本，断言判据真的判负 ===")

    # 「把坏写法拼回去 ⇒ 必须多出失败项」：基线失败集合先算出来，反例只认**新增**的失败，
    # 否则「本来就有失败」会让任何反例都假通过（这是本组自己的区分力保障）。
    base_bad = set(bad)

    def mutate(key, old, new, label):
        if old not in sources[key]:
            print("FAIL 反例 %s（锚点不存在：%s）" % (label, old[:60]))
            return False
        mutated = dict(sources)
        mutated[key] = sources[key].replace(old, new, 1)
        broken = set(failures(run_all(mutated)))
        fresh = sorted(broken - base_bad)
        if fresh:
            print("PASS 反例 %s ⇒ 新增判负 %d 项（%s）" % (label, len(fresh), fresh[0]))
            return True
        print("FAIL 反例 %s（改了却没多出任何失败：判据没有区分力）" % label)
        return False

    counters = [
        mutate("table", "BuiltInRegistries.ITEM.getTagOrEmpty(binding.tag())",
               "List.<net.minecraft.core.Holder<Item>>of()", "① tag 不再展开"),
        mutate("table", "List.of(id), entry.getValue())", "List.of(), entry.getValue())",
               "① 精确条目丢掉自己的物品 id"),
        mutate("packet", "ctx.enqueueWork(", "((Runnable) () -> {}).run(); (", "① 不再 enqueueWork"),
        mutate("packet", "ClientPayloadHooks.get().syncDecompositionDisplay(packet)",
               "cretae.cookiewyq.rs_create_compat.client.ClientPayloadSink.direct(packet)",
               "① 处理体直接引用客户端类"),
        mutate("sync", "event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, packet));",
               "// 不发包", "① 数据包不再下发"),
        mutate("cache", "if (received) {", "if (false) {", "① 镜像不会被采用"),
        mutate("cache", "return DecompositionTable.displayEntries();", "return List.of();",
               "① 单人游戏降级路径丢失"),
        mutate("plugin", '"decomposition_generator")', '"decomposition_generator_renamed")',
               "② 配方类型 id 改名"),
        mutate("plugin", "RecipeType.create(RECIPE_TYPE_ID.getNamespace(), RECIPE_TYPE_ID.getPath(),",
               "RecipeType.create(RECIPE_TYPE_ID.getNamespace(), RECIPE_TYPE_ID.getPath() + \"_v2\",",
               "② 注册的类型与常量 id 不一致"),
        mutate("plugin", "registration.addRecipeCatalyst(new ItemStack(",
               "registration.addRecipeCatalyst(ItemStack.EMPTY, ", "② 催化剂不是机器物品"),
        mutate("category", ".addItemStacks(recipe.inputs())", ".addItemStacks(java.util.List.of())",
               "② 输入槽不放配方输入"),
        mutate("category", "builder.addRecipeArrow().setPosition(ARROW_X, ARROW_Y)",
               "builder.addDrawable(null, ARROW_X, ARROW_Y)", "② 箭头不再是 JEI 配方箭头"),
        mutate("recipe", "if (inputs.isEmpty()) {", "if (false) {", "② 空输入配方不再跳过"),
        mutate("category", "private static final int BYPRODUCT_LABEL_Y = 42;",
               "private static final int BYPRODUCT_LABEL_Y = 52;",
               "②d 副产物标签挪回槽那一行（会压到槽）"),
        mutate("category", "private static final int ARROW_X = 26;",
               "private static final int ARROW_X = 58;", "②d 箭头压到文字列"),
        mutate("category", "public static final int WIDTH = 200;",
               "public static final int WIDTH = 180;",
               "②d 页面缩回 180（英文耗时行会被截断）"),
        mutate("plugin", "public Rect2i getArea() {",
               "public Rect2i getArea() { if (true) { return new Rect2i(86, 45, 24, 16); }",
               "③ 可点区域写裸坐标"),
        mutate("plugin", "public boolean isTooltipEnabled() {",
               "public boolean isTooltipEnabled() { if (true) { return true; }",
               "③ 打开 JEI 默认 tooltip（会两个框叠一起）"),
        mutate("plugin", "recipesGui.showTypes(List.of(RECIPE_TYPE));", ";", "③ 空输入时没有兜底页面"),
        mutate("lang_en", '"jei.rs_create_compat.decomposition_generator.chance": "Drop chance: %s%%",\n',
               "", "④ 删掉英文的 chance 键"),
        mutate("lang_zh", '"jei.rs_create_compat.decomposition_generator.rate": "平均：%s FE/t"',
               '"jei.rs_create_compat.decomposition_generator.rate": "平均：%s / %s FE/t"',
               "④ 中文 rate 多一个占位符"),
        mutate("entry", "public record DecompositionDisplayEntry(",
               "import net.minecraft.client.Minecraft;\npublic record DecompositionDisplayEntry(",
               "⑥ support 包里塞进客户端类型"),
    ]
    counter_ok = sum(1 for value in counters if value is True)
    counter_total = len(counters)
    print("")
    print("反例自证：%d/%d 通过" % (counter_ok, counter_total))

    if bad or counter_ok != counter_total:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
