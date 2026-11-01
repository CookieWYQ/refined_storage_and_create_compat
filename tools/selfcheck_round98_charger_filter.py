#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 98 轮自检：范围充电器「充电过滤（黑/白名单）」+「每台高亮开关」的源码级锚点。

契约：docs/ROUND98_CHARGER_FILTER.md（§1/§3/§4 是实现口径，§6 是本脚本的交付口径）。
本脚本只读源码 / 语言文件，**不改任何 src/main**；只用标准库。

断言清单（与文档 §6 一一对应）：
  ① ChargingFilter：三个 allows* 的黑/白名单语义是**唯一判定出口**（源码级），
     并配一份纯 Python 行为模型（黑名单命中拒、白名单未命中拒、空黑/白名单两态、
     玩家名大小写、玩家 UUID 命中、名字/UUID 任一命中）。
  ② 方块实体 NBT：chargingFilter（compound）+ highlightEnabled 的 save/load 键名一致；
     load 缺字段回落默认（黑名单 + 空集；高亮默认 true）。
  ③ 充电门控三处都在（chargePlayer 玩家级 + 每个 stack、scanItems、scanItemsInfinite），
     且被跳过的不计入 targets、不登记高亮；反例：删掉任一处 ⇒ 判负。
  ④ 高亮：syncHighlightTargets 同时受 Config.rangeChargerHighlightTargets（总闸）
     与 highlightEnabled（每台开关）约束；ContainerData 13 / 索引 12；菜单按钮 7/8 与
     两个 clickMenuButton 分支；RangeChargerScreen 两颗按钮的几何与点击动作。
  ⑤ 3 个包都在 registerPayloads 注册（TYPE + STREAM_CODEC + 方向），且 network/** 不引用
     本模组 client 包 —— 直接复用 tools/selfcheck_round47_dedicated_server_safety.py 的判据。
  ⑥ 界面几何：ChargerFilterScreen 面板 252×262、列表 8 行/页、控件全在面板内、
     行文字按像素截断不越滚动条左缘、玩家页隐藏搜索框且不参与点击/tooltip。
  ⑦ 语言键：本轮新增键 zh/en 成对、非空、zh 非纯 ASCII、en 无中日韩
     —— 复用 tools/audit_lang_keys.py 的判据（CJK_RE / ascii_tokens_without_placeholder）。
  ⑧ 不越界：BE 里无 `whitelist` 字面量（判定只在 ChargingFilter）；support/**、network/**
     不引用 client；既有充电配置语义未变。

反例自证：至少 6 条「旧写法 / 错误写法」拼进虚拟源码，断言判据判负（打印 `反例 ... PASS`）。

用法：python tools/selfcheck_round98_charger_filter.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import importlib.util
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老 Python / 重定向
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
PKG_REL = "cretae/cookiewyq/rs_create_compat"
NET = os.path.join(PKG, "network")
SUPPORT = os.path.join(PKG, "support")
CLIENT = os.path.join(PKG, "client")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
TOOLS = os.path.dirname(os.path.abspath(__file__))

BE = os.path.join(PKG, "block", "entity", "RangeChargerBlockEntity.java")
MENU = os.path.join(PKG, "menu", "RangeChargerMenu.java")
SCREEN = os.path.join(CLIENT, "screen", "RangeChargerScreen.java")
FILTER = os.path.join(SUPPORT, "ChargingFilter.java")
KNOWN = os.path.join(SUPPORT, "ChargerKnownPlayers.java")
FILTER_SCREEN = os.path.join(CLIENT, "screen", "ChargerFilterScreen.java")
CANDIDATES = os.path.join(CLIENT, "screen", "ChargerFilterCandidates.java")
MAIN = os.path.join(PKG, "RS_Create_Compat.java")
CONFIG = os.path.join(PKG, "Config.java")

PACKETS = {
    "OpenChargerFilterPacket": ("playToServer", os.path.join(NET, "OpenChargerFilterPacket.java")),
    "SetChargerFilterPacket": ("playToServer", os.path.join(NET, "SetChargerFilterPacket.java")),
    "SyncChargerFilterPacket": ("playToClient", os.path.join(NET, "SyncChargerFilterPacket.java")),
}
PAYLOAD_IDS = {
    "OpenChargerFilterPacket": "open_charger_filter",
    "SetChargerFilterPacket": "set_charger_filter",
    "SyncChargerFilterPacket": "sync_charger_filter",
}

FAILS = []
CHECKS = [0]
COUNTER = [0]


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok):
    CHECKS[0] += 1
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        FAILS.append(name)


def counterexample(name, ok):
    """反例自证：参数是「判据对坏写法的结论」，必须为 True（即真的判负了）。"""
    COUNTER[0] += 1
    check("反例 %s" % name, ok)


# ======================================================================================
# 通用工具：剥注释 / 取方法体 / 解析 int 常量
# ======================================================================================

def strip_comments(text):
    """把 // 与 /*...*/ 注释替换成空格（保留换行与字符串字面量）。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in ("\"", "'"):
                quote = char
                out.append(char)
                i += 1
                continue
            out.append(char)
            i += 1
            continue
        # 字符串内部：不把 // 当注释
        if char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        if char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def method_body(text, signature_regex):
    """signature_regex 命中后，从其后第一个 `{` 做括号配平，返回方法体（含 `{`...`}`）。"""
    code = strip_comments(text)
    match = re.search(signature_regex, code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    if brace < 0:
        return None
    depth = 0
    for index in range(brace, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():index + 1]
    return None


def slice_between(text, start_token, end_token):
    code = strip_comments(text)
    start = code.find(start_token)
    if start < 0:
        return ""
    end = code.find(end_token, start + len(start_token))
    return code[start:] if end < 0 else code[start:end]


def int_consts(text):
    """收集 `static final int NAME = <表达式>;`，按多轮替换解析成整数（不依赖第三方）。"""
    code = strip_comments(text)
    raw = []
    for match in re.finditer(r"static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);", code):
        raw.append((match.group(1), match.group(2).strip()))
    values = {}
    for _ in range(10):
        progress = False
        for name, expr in raw:
            if name in values:
                continue
            resolved = expr
            for known, known_value in values.items():
                resolved = re.sub(r"\b%s\b" % re.escape(known), str(known_value), resolved)
            try:
                values[name] = int(eval(resolved, {"__builtins__": {}}, {}))  # noqa: S307 - 只解析数字表达式
                progress = True
            except Exception:
                continue
        if not progress:
            break
    return values


# ======================================================================================
# ① ChargingFilter：黑/白名单语义唯一出口 + 行为模型
# ======================================================================================

LIMITS_REL = PKG_REL + "/support/ChargerFilterLimits.java"


def limits_source_text(all_sources):
    """取 ChargerFilterLimits 的源码文本（第 99 轮把名字归一化 / 上限搬到那里）。"""
    src = all_sources.get(LIMITS_REL) if all_sources else None
    if src is None:
        return ""
    return src.text if hasattr(src, "text") else src


def filter_source_ok(text, limits_text=""):
    """三个 allows* 存在；判定只在一个私有 allows() 里；玩家判定名/ uuid 任一命中。"""
    code = strip_comments(text)
    limits_code = strip_comments(limits_text)
    return (
        "public boolean allowsDroppedItem(final Item item)" in code
        and "public boolean allowsCarriedItem(final Item item)" in code
        and "public boolean allowsPlayer(final Player player)" in code
        and "return allows(droppedItems, idOf(item));" in code
        and "return allows(carriedItems, idOf(item));" in code
        and "private boolean allows(final Set<ResourceLocation> list, final ResourceLocation id)" in code
        and code.count("return whitelist ? hit : !hit;") == 2
        and "playerIds.contains(id)" in code
        and "playerNames.contains(lowerName(" in code
        and "private boolean whitelist;" in code
        and "private final Set<ResourceLocation> droppedItems" in code
        and "private final Set<ResourceLocation> carriedItems" in code
        and "private final Set<String> playerNames" in code
        and "private final Set<UUID> playerIds" in code
        # 名字一律 trim + 小写后再入库（Steve / steve 不许当成两个人）。第 99 轮把这段规则
        # 抽到 ChargerFilterLimits.normalizeName（界面与服务端共用同一份），因此两种写法都认；
        # 但「委托」这一支必须同时看到 normalizeName 里真的在 trim+lowercase，
        # 否则「改成 normalizeName 但其实什么都没洗」会蒙混过关。
        and (
            "rawName.trim().toLowerCase(Locale.ROOT)" in code
            or ("ChargerFilterLimits.normalizeName(rawName)" in code
                and "trim().toLowerCase(Locale.ROOT)" in limits_code)
        )
    )


class FilterModel:
    """ChargingFilter 行为模型的纯 Python 孪生（同一语义：命中 ⇒ 黑名单拒 / 白名单放）。"""

    def __init__(self, whitelist=False, dropped=(), carried=(), names=(), ids=()):
        self.whitelist = whitelist
        self.dropped = set(dropped)
        self.carried = set(carried)
        self.names = {n.strip().lower() for n in names if n}
        self.ids = set(ids)
        self.invert = False          # 反例开关：把语义取反
        self.ignore_lists = False    # 反例开关：把空名单两态混同

    def _allows(self, hit):
        if self.ignore_lists:
            return True
        if self.invert:
            return (not hit) if self.whitelist else hit
        return hit if self.whitelist else not hit

    def allows_item(self, item_id, kind="dropped"):
        bucket = self.dropped if kind == "dropped" else self.carried
        return self._allows(item_id in bucket)

    def allows_player(self, name="", uuid=None):
        hit = (uuid is not None and uuid in self.ids) or (name.strip().lower() in self.names)
        return self._allows(hit)


def behaviour_ok(model_cls=FilterModel):
    """黑/白名单 × 空/命中/未命中 × 玩家名大小写/UUID 的真值表。"""
    cases = [
        # (构造参数, kind/玩家, 期望)
        (dict(whitelist=False), ("item", "minecraft:diamond"), True),          # 空黑名单 ⇒ 全放
        (dict(whitelist=False, dropped=[("minecraft:diamond")]),
         ("item", "minecraft:diamond"), False),                                 # 黑名单命中 ⇒ 拒
        (dict(whitelist=False, dropped=[("minecraft:diamond")]),
         ("item", "minecraft:gold"), True),                                     # 黑名单未命中 ⇒ 放
        (dict(whitelist=True), ("item", "minecraft:diamond"), False),           # 空白名单 ⇒ 全拒
        (dict(whitelist=True, dropped=[("minecraft:diamond")]),
         ("item", "minecraft:diamond"), True),                                  # 白名单命中 ⇒ 放
        (dict(whitelist=True, dropped=[("minecraft:diamond")]),
         ("item", "minecraft:gold"), False),                                    # 白名单未命中 ⇒ 拒
        (dict(whitelist=True, carried=[("minecraft:netherite_pickaxe")]),
         ("carried", "minecraft:netherite_pickaxe"), True),                     # carried 独立名单
        (dict(whitelist=False, carried=[("minecraft:netherite_pickaxe")]),
         ("carried", "minecraft:netherite_pickaxe"), False),
        # 玩家：空名单（黑）⇒ 放；名字大小写不敏感；UUID 命中；白名单未命中 ⇒ 拒
        (dict(whitelist=False), ("player", "Steve", "uuid-1"), True),
        (dict(whitelist=False, names=["Steve"]), ("player", "STEVE", "uuid-2"), False),
        (dict(whitelist=True, names=["steve"]), ("player", "sTeVe", "uuid-3"), True),
        (dict(whitelist=False, ids=["uuid-9"]), ("player", "alex", "uuid-9"), False),
        (dict(whitelist=True, ids=["uuid-9"]), ("player", "alex", "uuid-9"), True),
        (dict(whitelist=True, names=["steve"]), ("player", "alex", "uuid-9"), False),
    ]
    for kwargs, case, expected in cases:
        model = model_cls(**kwargs)
        if case[0] == "player":
            actual = model.allows_player(case[1], case[2])
        else:
            actual = model.allows_item(case[1], "dropped" if case[0] == "item" else "carried")
        if actual != expected:
            return False
    return True


def semantics_variant(invert=False, ignore_lists=False):
    class Variant(FilterModel):
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            self.invert = invert
            self.ignore_lists = ignore_lists

    return Variant


def judge_only_here_ok(charing_filter_text, be_text, all_sources):
    """判定语义只许在 ChargingFilter 里：BE 无 whitelist 字面量；别处无 `? hit : !hit`。"""
    if filter_source_ok(charing_filter_text, limits_source_text(all_sources)) is False:
        return False
    be_code = strip_comments(be_text)
    if "whitelist" in be_code:
        return False
    for rel, src in all_sources.items():
        code = strip_comments(src.text if hasattr(src, "text") else src)
        if "? hit : !hit" in code and not rel.endswith("support/ChargingFilter.java"):
            return False
    return True


# ======================================================================================
# ② NBT：save/load 键名一致 + 缺字段回落默认
# ======================================================================================

class FilterNbtTwin:
    """ChargingFilter#save / #load 的纯 Python 孪生，用来验「键名 roundtrip」。"""

    VERSION_KEY = "v"
    WHITELIST_KEY = "whitelist"
    DROPPED_KEY = "dropped"
    CARRIED_KEY = "carried"
    NAMES_KEY = "names"
    IDS_KEY = "ids"

    def __init__(self, whitelist=False, dropped=(), carried=(), names=(), ids=()):
        self.whitelist = whitelist
        self.dropped = sorted(set(dropped))
        self.carried = sorted(set(carried))
        self.names = sorted({n.strip().lower() for n in names if n})
        self.ids = sorted(set(ids))

    def save(self):
        return {
            self.VERSION_KEY: 1,
            self.WHITELIST_KEY: bool(self.whitelist),
            self.DROPPED_KEY: list(self.dropped),
            self.CARRIED_KEY: list(self.carried),
            self.NAMES_KEY: list(self.names),
            self.IDS_KEY: list(self.ids),
        }

    @staticmethod
    def load(tag):
        if not tag:
            return FilterNbtTwin()
        return FilterNbtTwin(
            whitelist=bool(tag.get(FilterNbtTwin.WHITELIST_KEY, False)),
            dropped=tag.get(FilterNbtTwin.DROPPED_KEY, []),
            carried=tag.get(FilterNbtTwin.CARRIED_KEY, []),
            names=tag.get(FilterNbtTwin.NAMES_KEY, []),
            ids=tag.get(FilterNbtTwin.IDS_KEY, []),
        )


def nbt_roundtrip_ok(twin_cls=FilterNbtTwin):
    sample = twin_cls(whitelist=True,
                      dropped=["minecraft:diamond"],
                      carried=["minecraft:netherite_pickaxe"],
                      names=["Steve", "steve"],
                      ids=["uuid-1"])
    loaded = twin_cls.load(sample.save())
    if (loaded.whitelist, loaded.dropped, loaded.carried, loaded.names, loaded.ids) != (
            True, ["minecraft:diamond"], ["minecraft:netherite_pickaxe"], ["steve"], ["uuid-1"]):
        return False
    default = twin_cls.load({})
    return default.whitelist is False and default.dropped == [] and default.carried == [] \
        and default.names == [] and default.ids == []


def nbt_source_ok(filter_text, be_text):
    code = strip_comments(filter_text)
    be = strip_comments(be_text)
    constants_ok = all(needle in code for needle in (
        'private static final String TAG_VERSION = "v";',
        'private static final String TAG_WHITELIST = "whitelist";',
        'private static final String TAG_DROPPED = "dropped";',
        'private static final String TAG_CARRIED = "carried";',
        'private static final String TAG_NAMES = "names";',
        'private static final String TAG_IDS = "ids";',
        "public static final int NBT_VERSION = 1;",
    ))
    save_ok = all(needle in code for needle in (
        "tag.putInt(TAG_VERSION, NBT_VERSION);",
        "tag.putBoolean(TAG_WHITELIST, whitelist);",
        "tag.put(TAG_DROPPED, idsToTag(droppedItems));",
        "tag.put(TAG_CARRIED, idsToTag(carriedItems));",
        "tag.put(TAG_NAMES, names);",
        "tag.put(TAG_IDS, ids);",
    ))
    load_ok = all(needle in code for needle in (
        "tag.getBoolean(TAG_WHITELIST)",
        "tag.getList(TAG_DROPPED, Tag.TAG_STRING)",
        "tag.getList(TAG_CARRIED, Tag.TAG_STRING)",
        "tag.getList(TAG_NAMES, Tag.TAG_STRING)",
        "tag.getList(TAG_IDS, Tag.TAG_INT_ARRAY)",
        "NbtUtils.loadUUID(",
        "ResourceLocation.tryParse(",
    ))
    # 方块实体：chargingFilter 是 compound、highlightEnabled 是 BOOLEAN；load 缺键回落默认
    be_ok = (
        "chargingFilter.save(filterTag);" in be
        and 'tag.put("chargingFilter", filterTag);' in be
        and 'tag.putBoolean("highlightEnabled", highlightEnabled);' in be
        and 'if (tag.contains("chargingFilter")) {' in be
        and "ChargingFilter.load(tag.getCompound(\"chargingFilter\"))" in be
        and 'highlightEnabled = !tag.contains("highlightEnabled") || '
            'tag.getBoolean("highlightEnabled");' in be
    )
    return constants_ok and save_ok and load_ok and be_ok


# ======================================================================================
# ③ 充电门控三处 + 「跳过的不是目标、不登记高亮」
# ======================================================================================

DROPPED_GATE = r"if \(!chargingFilter\.allowsDroppedItem\(stack\.getItem\(\)\)\) \{\s*continue;\s*\}"
PLAYER_GATE = r"if \(!chargingFilter\.allowsPlayer\(player\)\) \{\s*return targets;\s*\}"
CARRIED_GATE = r"if \(!chargingFilter\.allowsCarriedItem\(stack\.getItem\(\)\)\) \{\s*continue;\s*\}"


def _gate_before_effects(body, gate_regex, effect_needles):
    match = re.search(gate_regex, body)
    if not match:
        return False
    gate_at = match.start()
    for needle in effect_needles:
        if needle not in body:
            return False
        if body.index(needle) < gate_at:
            return False
    return True


def dropped_gate_ok(text, method_regex=r"private int scanItems\(final Level level\)"):
    body = method_body(text, method_regex)
    if body is None:
        return False
    return _gate_before_effects(
        body, DROPPED_GATE, ["targets++", "tickChargedItemIds.add("])


def dropped_gate_infinite_ok(text):
    return dropped_gate_ok(text, r"private int scanItemsInfinite\(final Level level\)")


def player_gate_ok(text):
    body = method_body(text, r"private int chargePlayer\(")
    if body is None:
        return False
    if not re.search(PLAYER_GATE, body):
        return False
    carried = [m.start() for m in
               re.finditer(r"chargingFilter\.allowsCarriedItem\(stack\.getItem\(\)\)", body)]
    targets = [m.start() for m in re.finditer(r"targets\+\+", body)]
    highlight = [m.start() for m in re.finditer(r"tickChargedPlayers\.add\(", body)]
    if len(carried) != 2 or len(targets) < 2 or len(highlight) < 2:
        return False
    if any(re.search(CARRIED_GATE, body) is None for _ in (0,)):
        return False
    if len(re.findall(CARRIED_GATE, body)) != 2:
        return False
    return all(carried[i] < targets[i] for i in range(2)) \
        and all(carried[i] < highlight[i] for i in range(2))


def gate_callsites_ok(be_text):
    """全工程只有这 6 处调用 allows*（1 玩家级 + 2 carried + 2 dropped + 1 block），且都在 BE。

    第 110 轮锚点更新（契约合法扩展）：新增「世界中的方块」一类 ⇒ 多一处
    `chargingFilter.allowsBlock(state.getBlock())`。判据强度不变，仍然是「调用点总数恰好等于
    类别判定点的应收数量」—— 多一处（别处又偷偷判一次名单）或少一处（门控漏了）都会判负。
    """
    code = strip_comments(be_text)
    callsites = re.findall(
        r"chargingFilter\.allows(?:DroppedItem|CarriedItem|Player|Block)\(", code)
    return len(callsites) == 6


def gates_ok(be_text):
    return (dropped_gate_ok(be_text)
            and dropped_gate_infinite_ok(be_text)
            and player_gate_ok(be_text)
            and gate_callsites_ok(be_text))


# ======================================================================================
# ④ 高亮：总闸 + 本机开关；数据槽 12；按钮 7/8；主界面两颗按钮
# ======================================================================================

def highlight_gate_ok(text):
    body = method_body(text, r"private void syncHighlightTargets\(final Level level\)")
    if body is None:
        return False
    if "!Config.rangeChargerHighlightTargets" not in body or "!highlightEnabled" not in body:
        return False
    if body.count("resetHighlightSyncMemory();") < 2:
        return False
    config_gate = body.index("!Config.rangeChargerHighlightTargets")
    mine_gate = body.index("!highlightEnabled")
    send = body.index("PacketDistributor.sendToPlayer(")
    return config_gate < mine_gate < send


def data_slot_ok(be_text, menu_text):
    be = strip_comments(be_text)
    menu = strip_comments(menu_text)
    return (
        "case 12 -> highlightEnabled ? 1 : 0;" in be
        # 第 102 轮锚点更新（契约合法扩展，不是放宽）：ContainerData 13 → 14，
        # 索引 13 = 上一次扫描「因已满被跳过」的目标数；判据仍是「槽数 = 最大索引 + 1」
        # 且新槽必须真的接线（BE 给值 + 菜单有 getter）。
        and "case 13 -> lastSkippedFullTargets;" in be
        and "return 14;" in be
        and "new SimpleContainerData(14)" in menu
        and "data.get(13)" in menu
        and "public int getSkippedFullTargets()" in menu
        and "data.get(12) == 1" in menu
        and "public boolean isHighlightEnabled()" in menu
        and "BUTTON_SHOW_RANGE = 6" in menu
        and "BUTTON_TOGGLE_HIGHLIGHT = 7" in menu
        # 第 98 轮复核：旧的 BUTTON_OPEN_FILTER = 8 是**死分支**（全仓无发送点），已删除。
        # 这里反过来锚住「它不许再回来」——一条功能只留一条通道（过滤界面走独立 C2S 包）。
        and "BUTTON_OPEN_FILTER" not in menu
    )


def menu_branches_ok(menu_text):
    code = strip_comments(menu_text)
    toggle = slice_between(code, "case BUTTON_TOGGLE_HIGHLIGHT", "default ->")
    return (
        "stillValid(player)" in toggle
        and "charger.toggleHighlightEnabled();" in toggle
        # 过滤界面只能有「独立 C2S 包」这一条通道：菜单分支里不得再出现它的服务端调用
        and "sendFilterSnapshot" not in toggle
    )


def screen_buttons_ok(screen_text, consts):
    code = strip_comments(screen_text)
    return (
        consts.get("SIDE_BTN_X") == 150
        and consts.get("SIDE_BTN_W") == 36
        and consts.get("SIDE_BTN_H") == 14
        and consts.get("HIGHLIGHT_BTN_Y") == 37
        and consts.get("FILTER_BTN_Y") == 54
        and "topPos + 20, 36, 14" in code
        and ".bounds(leftPos + SIDE_BTN_X, topPos + HIGHLIGHT_BTN_Y, SIDE_BTN_W, SIDE_BTN_H)" in code
        and ".bounds(leftPos + SIDE_BTN_X, topPos + FILTER_BTN_Y, SIDE_BTN_W, SIDE_BTN_H)" in code
        and "menu.isHighlightEnabled()" in code
        and "RangeChargerMenu.BUTTON_TOGGLE_HIGHLIGHT" in code
        and "new cretae.cookiewyq.rs_create_compat.network.OpenChargerFilterPacket(menu.containerId)" in code
        and "refreshHighlightButton();" in code
    )


def highlight_wiring_ok(be_text, menu_text, screen_text, consts):
    return (highlight_gate_ok(be_text)
            and data_slot_ok(be_text, menu_text)
            and menu_branches_ok(menu_text)
            and screen_buttons_ok(screen_text, consts))


# ======================================================================================
# ⑤ 网络包：注册（TYPE + STREAM_CODEC + 方向）+ network/** 不引用 client（复用 round47）
# ======================================================================================

def registration_map(main_text):
    body = method_body(main_text, r"private static void registerPayloads\(")
    if body is None:
        return None
    entries = []
    for match in re.finditer(r"registrar\.(playToServer|playToClient)\s*\(", body):
        rest = body[match.end():]
        nxt = re.search(r"registrar\.(?:playToServer|playToClient)\s*\(", rest)
        segment = rest[:nxt.start()] if nxt else rest
        payload = re.search(r"\b([A-Z][A-Za-z0-9_]*Packet)\b", segment)
        if payload:
            entries.append((payload.group(1), match.group(1)))
    return entries


def registration_ok(main_text):
    entries = registration_map(main_text)
    if entries is None:
        return False
    seen = {}
    for name, direction in entries:
        if name in seen:
            return False
        seen[name] = direction
    return all(seen.get(name) == direction for name, (direction, _path) in PACKETS.items())


def uuid_codec_ok(set_text, sync_text):
    """Set 包的 playerIds 编解码走 Sync 包的共用辅助，且该辅助内部确实用 UUIDUtil 编/解码。"""
    set_code = strip_comments(set_text)
    sync_code = strip_comments(sync_text)
    return ("SyncChargerFilterPacket.encodeUuids(" in set_code
            and "SyncChargerFilterPacket.decodeUuids(" in set_code
            and "UUIDUtil.STREAM_CODEC.encode(" in sync_code
            and "UUIDUtil.STREAM_CODEC.decode(" in sync_code)


def packet_ok(name, direction, text):
    code = strip_comments(text)
    if not code.strip():
        return False
    if "public record %s(" % name not in code:
        return False
    if "implements CustomPacketPayload" not in code:
        return False
    if "public static final Type<%s> TYPE" % name not in code:
        return False
    if "public static final StreamCodec<RegistryFriendlyByteBuf, %s> STREAM_CODEC" % name not in code:
        return False
    if re.search(r'ResourceLocation\.fromNamespaceAndPath\(RS_Create_Compat\.MODID,\s*"'
                 + re.escape(PAYLOAD_IDS[name]) + r'"\)', code) is None:
        return False
    # 方向：C2S 两个包必须在服务端校验（containerId + 菜单类型 + stillValid）；S2C 走 hooks
    if direction == "playToServer":
        # Open 包只有 containerId；Set 包带 playerIds ⇒ UUID 编解码必须走 UUIDUtil。
        # 第 99 轮把四种名单（物品 id / 名字 / UUID / 已知玩家）的编解码收成 Sync 包里的**一份**
        # 共用实现（两包各写一遍的话，改一侧漏一侧就是「客户端写 STRING、服务端读 UUID」那种
        # 线格式错位），因此这里的锚点从「Set 包里出现 UUIDUtil」改成「Set 包调用共用辅助」，
        # 具体「辅助内部确实用 UUIDUtil」由 ⑤-c2 用 uuid_codec_ok() 判。
        if name != "OpenChargerFilterPacket" and not (
                "SyncChargerFilterPacket.encodeUuids(" in code
                and "SyncChargerFilterPacket.decodeUuids(" in code):
            return False
        server_ok = all(needle in code for needle in (
            "player.containerMenu.containerId != packet.containerId()",
            "player.containerMenu instanceof RangeChargerMenu menu",
            "menu.stillValid(player)",
            "public static void handle(final %s packet, final ServerPlayer player)" % name,
        ))
        if not server_ok:
            return False
    else:
        if "UUIDUtil.STREAM_CODEC" not in code:  # Sync 包带 playerIds + KnownPlayer id
            return False
        if "public static void handle(final %s packet, final IPayloadContext ctx)" % name not in code:
            return False
        if "ctx.enqueueWork(() -> ClientPayloadHooks.get().openChargerFilter(packet));" not in code:
            return False
    return True


def load_round47():
    path = os.path.join(TOOLS, "selfcheck_round47_dedicated_server_safety.py")
    spec = importlib.util.spec_from_file_location("rscc_round47", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def network_client_safety_ok(round47, sources):
    """复用 round47 判据：network/** 与 support/**（除既有 ALLOWLIST）不引用客户端类型。"""
    bad = []
    for rel, src in sources.items():
        if not (rel.startswith(PKG_REL + "/network/") or rel.startswith(PKG_REL + "/support/")):
            continue
        if rel in round47.ALLOWLIST:
            continue
        if src.external_refs or src.client_class_refs:
            bad.append(rel)
    return bad


# ======================================================================================
# ⑥ 界面几何：面板 252×262 / 8 行 / 控件在面板内 / 行文字不越滚动条 / 玩家页搜索框
# ======================================================================================

def geometry_ok(consts, text):
    need = [
        "PANEL_W", "PANEL_H", "TITLE_X", "TITLE_Y",
        "MODE_X", "MODE_Y", "MODE_W", "MODE_H",
        "SEARCH_X", "SEARCH_Y", "SEARCH_W", "SEARCH_H",
        "HINT_X", "HINT_Y", "HINT_MAX_W",
        "TAB_X", "TAB_Y", "TAB_W", "TAB_H", "TAB_GAP", "TAB_COUNT",
        "LIST_X", "LIST_Y", "LIST_W", "LIST_H", "ROW_H", "VISIBLE_ROWS",
        "SCROLL_X", "SCROLL_W",
        "ADD_X", "ADD_Y", "ADD_W", "ADD_H", "ADD_BTN_X", "ADD_BTN_W",
        "DONE_X", "DONE_Y", "DONE_W", "BACK_X", "BACK_W", "BTN_H",
        "ROW_SUFFIX_RIGHT", "ROW_NAME_X", "NAME_SUFFIX_GAP",
    ]
    if any(name not in consts for name in need):
        return False
    w, h = consts["PANEL_W"], consts["PANEL_H"]
    if (w, h) != (252, 262):
        return False
    if consts["ROW_H"] <= 0 or consts["LIST_H"] % consts["ROW_H"] != 0:
        return False
    if consts["VISIBLE_ROWS"] != consts["LIST_H"] // consts["ROW_H"]:
        return False
    if consts["VISIBLE_ROWS"] != 8:
        return False
    if consts["LIST_Y"] + consts["VISIBLE_ROWS"] * consts["ROW_H"] != consts["LIST_Y"] + consts["LIST_H"]:
        return False
    # 页签 4 个要装得进面板（左右各 10 边距）。
    # 第 110 轮锚点更新（契约合法扩展，不是放宽）：用户要求补「世界中的方块」这一类，
    # 页签从 3 个变 4 个、单签宽 74 → 56（4×56 + 3×2 = 230 ≤ 面板内沿 232，右缘 240 < 242）。
    # 判据本身仍是「个数=类数 + 右缘不越界」，并且仍由下面的通用 rects 循环逐个校验在面板内。
    if consts["TAB_COUNT"] != 4:
        return False
    right = consts["TAB_X"] + (consts["TAB_COUNT"] - 1) * (consts["TAB_W"] + consts["TAB_GAP"]) \
        + consts["TAB_W"]
    if right > w - 10:
        return False
    # 列表贴右侧 10 边距；滚动条占列表右端 10px
    if consts["LIST_X"] + consts["LIST_W"] != w - 10:
        return False
    if consts["SCROLL_X"] != consts["LIST_X"] + consts["LIST_W"] - 10 or consts["SCROLL_W"] != 10:
        return False
    # 行尾后缀右缘必须在滚动条左缘左侧至少 2px（不靠文案短来保证）
    if consts["ROW_SUFFIX_RIGHT"] != consts["SCROLL_X"] - consts["LIST_X"] - 2:
        return False
    if consts["ROW_SUFFIX_RIGHT"] <= 0 or consts["ROW_SUFFIX_RIGHT"] >= consts["SCROLL_X"] - consts["LIST_X"]:
        return False
    # 添加行在列表下方、底部按钮在添加行下方，且都在面板内
    if consts["ADD_Y"] + consts["ADD_H"] > consts["DONE_Y"]:
        return False
    if consts["DONE_Y"] + consts["BTN_H"] > h - 2:
        return False
    if consts["DONE_X"] + consts["DONE_W"] > consts["LIST_X"] + consts["LIST_W"]:
        return False
    if consts["BACK_X"] + consts["BACK_W"] > consts["LIST_X"] + consts["LIST_W"]:
        return False
    rects = [
        (consts["MODE_X"], consts["MODE_Y"], consts["MODE_W"], consts["MODE_H"]),
        (consts["SEARCH_X"], consts["SEARCH_Y"], consts["SEARCH_W"], consts["SEARCH_H"]),
        (consts["LIST_X"], consts["LIST_Y"], consts["LIST_W"], consts["LIST_H"]),
        (consts["SCROLL_X"], consts["LIST_Y"], consts["SCROLL_W"], consts["LIST_H"]),
        (consts["ADD_X"], consts["ADD_Y"], consts["ADD_W"], consts["ADD_H"]),
        (consts["ADD_BTN_X"], consts["ADD_Y"], consts["ADD_BTN_W"], consts["ADD_H"]),
        (consts["DONE_X"], consts["DONE_Y"], consts["DONE_W"], consts["BTN_H"]),
        (consts["BACK_X"], consts["DONE_Y"], consts["BACK_W"], consts["BTN_H"]),
    ]
    for i in range(consts["TAB_COUNT"]):
        rects.append((consts["TAB_X"] + i * (consts["TAB_W"] + consts["TAB_GAP"]),
                      consts["TAB_Y"], consts["TAB_W"], consts["TAB_H"]))
    for x, y, rw, rh in rects:
        if x < 0 or y < 0 or x + rw > w or y + rh > h:
            return False
    # 行内文字：后缀右缘按常量定位；名字按「剩余像素」完整显示。
    # 第 104 轮（用户口径：不要省略号）：这处不再「按像素截断补 ...」，而是
    # RsccTextFit.drawFitLine 整体缩字号完整显示（下限 RsccTextFit.MIN_SCALE = 0.6）。
    # 因此判据从「截断到放得下」换成「一个字符都不省 + 几何仍不相交、不压滚动条」——
    # 强度不降反升：旧判据允许把名字砍掉，新判据禁止砍，只允许缩。
    code = strip_comments(text)
    return (
        "final int suffixX = left + ROW_SUFFIX_RIGHT - suffixW;" in code
        and "final int nameMax = Math.max(8, suffixX - (left + ROW_NAME_X) - NAME_SUFFIX_GAP);" in code
        and "RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(displayNameOf(data)),"
            in code
        and "trim(displayNameOf" not in code
        and "while (s.length() > 1" not in code
        and 'font.width(s + "...")' not in code
    )


def player_search_hidden_ok(text):
    code = strip_comments(text)
    return (
        "final boolean players = tab == Tab.PLAYERS;" in code
        and "searchBox.visible = !players;" in code
        and "searchBox.active = !players;" in code
        and "tab != Tab.PLAYERS && inBox(mouseX, mouseY, SEARCH_X, SEARCH_Y" in code
    )


def filter_screen_wiring_ok(text):
    code = strip_comments(text)
    return (
        "public static void openOrRefresh(final SyncChargerFilterPacket packet)" in code
        and "minecraft.setScreen(new ChargerFilterScreen(screen.parent, packet));" in code
        and "minecraft.setScreen(new ChargerFilterScreen(current, packet));" in code
        and "super(Component.translatable(LANG + \"title\"), parent, PANEL_W, PANEL_H);" in code
        and "renderTooltips(guiGraphics, mouseX, mouseY);" in code
        and "rowAt(mouseX, mouseY)" in code
    )


def screen_geometry_ok(screen_text):
    consts = int_consts(screen_text)
    return geometry_ok(consts, screen_text)


# ======================================================================================
# ⑦ 语言键（复用 audit_lang_keys 判据）
# ======================================================================================

def load_audit():
    path = os.path.join(TOOLS, "audit_lang_keys.py")
    spec = importlib.util.spec_from_file_location("rscc_audit_lang_keys", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def new_lang_keys(filter_screen_text, range_screen_text, menu_text):
    code = strip_comments(filter_screen_text)
    match = re.search(r'private static final String LANG = "([^"]+)"', code)
    keys = set()
    if match:
        prefix = match.group(1)
        for literal in re.findall(r'"([a-z][a-z0-9_.]*)"', code):
            if literal == prefix.rstrip(".") or literal.startswith("gui."):
                continue
            keys.add(prefix + literal)
    range_code = strip_comments(range_screen_text)
    keys.update(re.findall(
        r'"(gui\.rs_create_compat\.range_charger\.(?:highlight|filter)[a-z_.]*)"', range_code))
    keys.update(re.findall(
        r'"(gui\.rs_create_compat\.range_charger\.highlight_disabled)"',
        strip_comments(menu_text)))
    return keys


def lang_ok(keys, zh, en, audit):
    if len(keys) < 25:
        return False
    for key in keys:
        if key not in zh or key not in en:
            return False
        zh_value, en_value = str(zh[key]), str(en[key])
        if not zh_value.strip() or not en_value.strip():
            return False
        if not audit.CJK_RE.search(zh_value):
            suspicious = [word for word in audit.ascii_tokens_without_placeholder(zh_value)
                          if word not in audit.ASCII_ALLOWED_TOKENS]
            if suspicious:
                return False
        if audit.CJK_RE.search(en_value):
            return False
    return True


# ======================================================================================
# ⑧ 不越界 + 既有配置语义未变 + 已知玩家 SavedData
# ======================================================================================

def known_players_ok(text):
    code = strip_comments(text)
    return (
        'public static final String DATA_NAME = "rscc_charger_known_players";' in code
        and "extends SavedData" in code
        and "server.overworld().getDataStorage().computeIfAbsent(" in code
        and "PlayerEvent.PlayerLoggedInEvent" in code
        and "public static void record(final Player player)" in code
        and "public static List<Entry> snapshot(final ServerLevel level)" in code
        and "String.CASE_INSENSITIVE_ORDER" in code
        and "setDirty();" in code
    )


def config_unchanged_ok(config_text, be_text, menu_text):
    config = strip_comments(config_text)
    be = strip_comments(be_text)
    menu = strip_comments(menu_text)
    definitions = all(needle in config for needle in (
        '.defineInRange("rangeChargerChargeRate", 500, 1, 1000000)',
        '.defineInRange("rangeChargerEnergyCapacity", 50000000, 1000, Integer.MAX_VALUE)',  # 第 99 轮：用户要求缓存改 5000 万，与 FE 能量立方一致
        '.defineInRange("rangeChargerMaxTransfer", 100000, 1, Integer.MAX_VALUE)',
        '.defineInRange("rangeChargerMaxTargets", 50, 1, 1000)',
        '.define("rangeChargerChargeBlocks", true)',
        '.define("rangeChargerChargeItems", true)',
        '.define("rangeChargerChargePlayerItems", true)',
        '.define("rangeChargerHighlightTargets", true)',
    ))
    load_hook = all(needle in config for needle in (
        "rangeChargerChargeRate = RANGE_CHARGER_CHARGE_RATE.get();",
        "rangeChargerHighlightTargets = RANGE_CHARGER_HIGHLIGHT_TARGETS.get();",
    ))
    usage = (
        "RsccRateLimiter.rateSaturating(Config.rangeChargerChargeRate," in be
        and "Config.rangeChargerMaxTargets" in be
        and "new RestorableEnergyStorage(Config.rangeChargerEnergyCapacity)" in be
        and "Config.rangeChargerMaxTransfer" in be
        and "Config.rangeChargerChargePlayerItems" in be
        and "Config.rangeChargerChargeItems" in be
        and "Config.rangeChargerChargeBlocks" in be
        and "Config.rangeChargerHighlightTargets" in be
        and "Config.rangeChargerHighlightTargets" in menu
    )
    return definitions and load_hook and usage


def candidates_ok(text):
    code = strip_comments(text)
    return (
        "Capabilities.EnergyStorage.ITEM" in code
        and "storage.canReceive()" in code
        and "storage.getMaxEnergyStored() <= 0" in code
        and "MAX_SEARCH_RESULTS = 512" in code
        and "new ItemStack(item)" in code
    )


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    filter_text = read(FILTER)
    known_text = read(KNOWN)
    be_text = read(BE)
    menu_text = read(MENU)
    screen_text = read(SCREEN)
    filter_screen_text = read(FILTER_SCREEN)
    candidates_text = read(CANDIDATES)
    main_text = read(MAIN)
    config_text = read(CONFIG)

    round47 = load_round47()
    sources = round47.load_sources()
    audit = load_audit()
    zh = audit.load("zh_cn.json")
    en = audit.load("en_us.json")

    # ---------------- ① ChargingFilter ----------------
    print("=== ① ChargingFilter：黑/白名单唯一判定出口 + 行为模型 ===")
    check("①-a 三个 allows* 都在，且判定只经过一个私有 allows()",
          filter_source_ok(filter_text, limits_source_text(sources)))
    counterexample("把 normalizeName 里的小写化删掉（Steve / steve 被当成两个人）⇒ 判据判负",
                   filter_source_ok(
                       filter_text,
                       limits_source_text(sources).replace(
                           "trim().toLowerCase(Locale.ROOT)", "trim()")) is False)
    check("①-b 判定语义只许在 ChargingFilter（BE 无 whitelist 字面量，别处无 `? hit : !hit`）",
          judge_only_here_ok(filter_text, be_text, sources))
    check("①-c 行为模型：黑名单命中⇒拒 / 未命中⇒放 / 空黑名单⇒全放", behaviour_ok())
    check("①-d 行为模型：白名单命中⇒放 / 未命中⇒拒 / 空白名单⇒全拒",
          behaviour_ok() and FilterModel(whitelist=True).allows_item("minecraft:diamond") is False)
    check("①-e 行为模型：玩家名大小写不敏感 + UUID 命中 + 名字/UUID 任一命中",
          FilterModel(names=["Steve"]).allows_player("STEVE", "uuid-x") is False
          and FilterModel(ids=["uuid-9"]).allows_player("alex", "uuid-9") is False
          and FilterModel(whitelist=True, ids=["uuid-9"]).allows_player("alex", "uuid-9") is True)
    counterexample("黑/白名单语义取反 ⇒ 行为真值表判负",
                   behaviour_ok(semantics_variant(invert=True)) is False)
    counterexample("空名单两态混同（空名单一律放行）⇒ 行为真值表判负",
                   behaviour_ok(semantics_variant(ignore_lists=True)) is False)

    # ---------------- ② NBT ----------------
    print("=== ② 方块实体 NBT：chargingFilter compound + highlightEnabled ===")
    check("②-a NBT 键名常量（v/whitelist/dropped/carried/names/ids）与 NBT_VERSION", (
        'private static final String TAG_VERSION = "v";' in strip_comments(filter_text)
        and 'private static final String TAG_WHITELIST = "whitelist";' in strip_comments(filter_text)
        and "public static final int NBT_VERSION = 1;" in strip_comments(filter_text)))
    check("②-b save/load 双向键名一致（缺字段回落默认：黑名单 + 空集）",
          nbt_source_ok(filter_text, be_text))
    check("②-c 纯 Python 孪生：save→load roundtrip + 空 tag 回落默认", nbt_roundtrip_ok())
    check("②-d BE：chargingFilter 走 compound；highlightEnabled 用 BOOLEAN 且缺键默认 true",
          'tag.put("chargingFilter", filterTag);' in strip_comments(be_text)
          and 'highlightEnabled = !tag.contains("highlightEnabled") || '
              'tag.getBoolean("highlightEnabled");' in strip_comments(be_text))
    counterexample("load 把 carried 键写成 dropped（键名串台）⇒ NBT 判据判负",
                   nbt_source_ok(
                       filter_text.replace("tag.put(TAG_CARRIED, idsToTag(carriedItems));",
                                           "tag.put(TAG_DROPPED, idsToTag(carriedItems));"),
                       be_text) is False)

    # ---------------- ③ 三处充电门控 ----------------
    print("=== ③ 充电门控三处：chargePlayer（玩家级 + 每 stack）/ scanItems / scanItemsInfinite ===")
    check("③-a scanItems 有掉落物门控，且门控在 targets++ / 高亮登记之前", dropped_gate_ok(be_text))
    check("③-b scanItemsInfinite 同上", dropped_gate_infinite_ok(be_text))
    check("③-c chargePlayer：玩家级整名跳过 + 背包/饰品两个循环各有一道 carried 门控",
          player_gate_ok(be_text))
    check("③-d 全工程 allows* 调用点恰好 6 处（1 玩家级 + 2 carried + 2 dropped + 1 block），无其它判定出口",
          gate_callsites_ok(be_text))
    be_no_dropped = re.sub(
        r"\s*if \(!chargingFilter\.allowsDroppedItem\(stack\.getItem\(\)\)\) \{\s*continue;\s*\}",
        "", be_text)
    be_no_player = re.sub(
        r"\s*if \(!chargingFilter\.allowsPlayer\(player\)\) \{\s*return targets;\s*\}", "", be_text)
    be_one_carried = be_text.replace(
        "if (!chargingFilter.allowsCarriedItem(stack.getItem())) {\n                continue;\n            }",
        "", 1)
    counterexample("删掉掉落物门控（scanItems / scanItemsInfinite 一起）⇒ 判据判负",
                   gates_ok(be_no_dropped) is False)
    counterexample("删掉玩家级门控 ⇒ 判据判负", gates_ok(be_no_player) is False)
    counterexample("删掉两条 carried 门控中的一条 ⇒ 判据判负", gates_ok(be_one_carried) is False)

    # ---------------- ④ 高亮开关 ----------------
    print("=== ④ 高亮：总闸 + 每台开关；数据槽 12；菜单按钮 7；主界面两颗按钮 ===")
    consts = int_consts(screen_text)
    check("④-a syncHighlightTargets 同时受 Config.rangeChargerHighlightTargets 与 highlightEnabled 约束",
          highlight_gate_ok(be_text))
    check("④-b ContainerData 13 / 索引 12 = highlightEnabled（BE + 菜单两端口径一致）",
          data_slot_ok(be_text, menu_text))
    check("④-c 菜单按钮 7 分支先 stillValid；死分支 BUTTON_OPEN_FILTER 已删除（不许回来）",
          menu_branches_ok(menu_text))
    check("④-d 主界面「高亮」「过滤」两颗按钮：同列同宽同高、在「查看」正下方、点击走各自通道",
          screen_buttons_ok(screen_text, consts))
    gate_no_config = re.sub(
        r"\s*if \(!Config\.rangeChargerHighlightTargets\) \{\s*resetHighlightSyncMemory\(\);\s*return;\s*\}",
        "", be_text)
    gate_no_machine = re.sub(
        r"\s*if \(!highlightEnabled\) \{\s*resetHighlightSyncMemory\(\);\s*return;\s*\}",
        "", be_text)
    counterexample("删掉配置总闸 ⇒ 高亮判据判负", highlight_gate_ok(gate_no_config) is False)
    counterexample("删掉本机开关 ⇒ 高亮判据判负", highlight_gate_ok(gate_no_machine) is False)
    counterexample("把高亮按钮挪到 + 按钮列（x=128）⇒ 按钮几何判据判负",
                   screen_buttons_ok(screen_text.replace(
                       "SIDE_BTN_X = 150", "SIDE_BTN_X = 128"), dict(consts, SIDE_BTN_X=128)) is False)

    # ---------------- ⑤ 网络包 ----------------
    print("=== ⑤ 网络包：3 个都在 registerPayloads 注册（TYPE + STREAM_CODEC + 方向） ===")
    packet_ok_all = all(packet_ok(name, direction, read(path))
                        for name, (direction, path) in PACKETS.items())
    check("⑤-a 3 个包类都有 TYPE / STREAM_CODEC / MODID 载荷名 / UUID 编解码", packet_ok_all)
    check("⑤-b registerPayloads 里方向正确（Open/Set = playToServer，Sync = playToClient）",
          registration_ok(main_text))
    check("⑤-c C2S 两个包都做三道服务端校验（containerId / RangeChargerMenu / stillValid）",
          packet_ok("OpenChargerFilterPacket", "playToServer",
                    read(PACKETS["OpenChargerFilterPacket"][1]))
          and packet_ok("SetChargerFilterPacket", "playToServer",
                        read(PACKETS["SetChargerFilterPacket"][1])))
    set_packet_text = read(PACKETS["SetChargerFilterPacket"][1])
    sync_packet_text = read(PACKETS["SyncChargerFilterPacket"][1])
    check("⑤-c2 Set 包的 playerIds 编解码复用 Sync 包的共用辅助，且辅助内部确实用 UUIDUtil",
          uuid_codec_ok(set_packet_text, sync_packet_text))
    counterexample("把 encodeUuids 里的 UUIDUtil 换成 STRING_UTF8（线格式错位）⇒ 判据判负",
                   uuid_codec_ok(set_packet_text,
                                 sync_packet_text.replace(
                                     "UUIDUtil.STREAM_CODEC.encode(buf, id);",
                                     "ByteBufCodecs.STRING_UTF8.encode(buf, id.toString());", 1))
                   is False)
    bad_net = network_client_safety_ok(round47, sources)
    check("⑤-d 复用 round47 判据：network/** 与 support/**（既有例外除外）不引用客户端类型", not bad_net)
    main_src = sources.get(PKG_REL + "/RS_Create_Compat.java")
    body, start_line = (round47.method_body(main_src.code,
                                            r"private\s+static\s+void\s+registerPayloads\s*\(")
                        if main_src is not None else (None, 0))
    check("⑤-e 复用 round47 判据：registerPayloads 方法体内无客户端类型引用",
          body is not None and not round47.refs_in(body, main_src, start_line))
    check("⑤-f 客户端落地链：ClientPayloadHooks.openChargerFilter（默认空实现）→ "
          "ClientPayloadSink 调 ChargerFilterScreen.openOrRefresh",
          "default void openChargerFilter(final SyncChargerFilterPacket packet)" in
          strip_comments(read(os.path.join(NET, "ClientPayloadHooks.java")))
          and "cretae.cookiewyq.rs_create_compat.client.screen.ChargerFilterScreen.openOrRefresh(packet);"
          in strip_comments(read(os.path.join(CLIENT, "ClientPayloadSink.java"))))
    counterexample("把 Sync 包误注册成 playToServer ⇒ 注册方向判据判负",
                   registration_ok(main_text.replace(
                       "registrar.playToClient(\n            SyncChargerFilterPacket.TYPE",
                       "registrar.playToServer(\n            SyncChargerFilterPacket.TYPE")) is False)

    # ---------------- ⑥ 界面几何 ----------------
    print("=== ⑥ ChargerFilterScreen 几何：252×262 / 8 行 / 控件在面板内 / 文字不越滚动条 ===")
    check("⑥-a 面板 252×262、4 页签、列表 8 行/页、控件全在面板内、底部按钮不越界",
          screen_geometry_ok(filter_screen_text))
    check("⑥-b 行文字完整显示：后缀右缘距滚动条左缘 2px，名字按剩余像素「缩字号」而非截断"
          "（第 104 轮：源码里一个省略号都不许有）",
          geometry_ok(int_consts(filter_screen_text), filter_screen_text))
    check("⑥-c 玩家页隐藏搜索框（visible/active 都置 false），且搜索 tooltip 只在非玩家页",
          player_search_hidden_ok(filter_screen_text))
    check("⑥-d openOrRefresh / setScreen / 手绘 tooltip / 行命中判定 都在",
          filter_screen_wiring_ok(filter_screen_text))
    tiles = dict(int_consts(filter_screen_text))
    counterexample("把行高改成 16（每页 9 行，列表装不下整行）⇒ 几何判据判负",
                   geometry_ok(dict(tiles, ROW_H=16), filter_screen_text) is False)
    counterexample("把行尾后缀右缘推到滚动条上（+8px）⇒ 几何判据判负",
                   geometry_ok(dict(tiles, ROW_SUFFIX_RIGHT=tiles["ROW_SUFFIX_RIGHT"] + 8),
                               filter_screen_text) is False)
    counterexample("玩家页搜索框仍然 active ⇒ 隐藏/点击判据判负",
                   player_search_hidden_ok(filter_screen_text.replace(
                       "searchBox.active = !players;", "searchBox.active = true;")) is False)
    counterexample("把行内名字改回「按像素截断补省略号」（第 104 轮明令禁止）⇒ 几何判据判负",
                   geometry_ok(tiles, filter_screen_text.replace(
                       "RsccTextFit.drawFitLine", "trimToWidth")) is False)
    counterexample("名字不再按「后缀右缘 − 名字起点」算可用宽度（改成固定宽）⇒ 几何判据判负",
                   geometry_ok(
                       tiles,
                       filter_screen_text.replace(
                           "final int nameMax = Math.max(8, suffixX - (left + ROW_NAME_X) - NAME_SUFFIX_GAP);",
                           "final int nameMax = 160;")) is False)

    # ---------------- ⑦ 语言键 ----------------
    print("=== ⑦ 本轮新增语言键：zh/en 成对、非空、zh 有中文、en 无中日韩 ===")
    keys = new_lang_keys(filter_screen_text, screen_text, menu_text)
    print("       本轮抽到的语言键 = %d 条" % len(keys))
    check("⑦-a 新增键中英成对、非空、zh 非纯 ASCII、en 无中日韩（复用 audit_lang_keys 判据）",
          lang_ok(keys, zh, en, audit))
    counterexample("en_us 少一条键 ⇒ 语言判据判负",
                   lang_ok(keys, zh, {k: v for k, v in en.items() if k != sorted(keys)[0]}, audit) is False)

    # ---------------- ⑧ 不越界 + 既有配置 ----------------
    print("=== ⑧ 不越界：BE 无 whitelist 字面量；既有配置语义未变；已知玩家 SavedData ===")
    check("⑧-a BE 里没有 whitelist 字面量（判定只在 ChargingFilter）",
          "whitelist" not in strip_comments(be_text))
    check("⑧-b ChargerKnownPlayers：主世界 SavedData + 登录事件 + record + 名字排序",
          known_players_ok(known_text))
    check("⑧-c 既有充电配置项仍按原默认值定义、onLoad 仍同步、BE/菜单仍按原语义使用",
          config_unchanged_ok(config_text, be_text, menu_text))
    check("⑧-d 候选池：只收可接收能量且容量>0 的物品，搜索上限 512", candidates_ok(candidates_text))

    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条）" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
