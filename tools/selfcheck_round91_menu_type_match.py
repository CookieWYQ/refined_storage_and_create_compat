#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round91 自检：菜单子类必须使用自己的 MenuType（高级归流缓存仓「一开界面就被踢」回归）。

线上日志证实的原始 bug：
    AdvancedCollectionCacheMenu extends CollectionCacheMenu，而基础版 4 参构造器里写死
    ``super(RS_Create_Compat.COLLECTION_CACHE_MENU.get(), id)`` ⇒ 服务端
    ``new AdvancedCollectionCacheMenu(...)`` 的 ``getType()`` 是**基础版** MenuType，
    开界面下发的 ClientboundOpenScreenPacket 让客户端建基础版菜单（138 槽 / 29 数据槽），
    而服务端是高级版（139 槽 / 35 数据槽）⇒ 首次全量同步 initializeContents 越界
    ``IndexOutOfBoundsException: Index 138 out of bounds for length 138`` ⇒ 客户端断线。

    修法：父类新增 ``protected CollectionCacheMenu(MenuType<?> type, int id, Inventory, block)``，
    子类改为 ``super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get(), id, inventory, block)``。

检查分组（全部读源码静态可证伪，不启动游戏）
  ①a 扫 menu/*.java 找所有「继承另一个 Menu 类」的子类；断言其构造器 super 首参
      = 它自己在 RS_Create_Compat.java 里注册的 MenuType holder，且 ≠ 父类 holder；
      任何隐式 3 参 super(id, inventory, block)（= 原始 bug 形态）都判负
  ①b 父类存在 protected、首参 MenuType<?> 的构造器，且真的把它转发给 super(type, id)
  ①c RS_Create_Compat.java 的客户端工厂 lambda 建的类 = 注册泛型里的类（两侧同类）
  ①d 槽位 / 数据槽算术：GRID_COLS / VISIBLE_ROWS / INV_COLS / UPGRADE_SLOTS /
      DATA_SLOT_COUNT / areaPanelDataCount 全部**从源码解析**后求值，
      并对照 addSlot / addDataSlots 的循环结构 ⇒ 138 / 139 / 29+6=35
  ①e 反例自证：原始 bug 形态、显式传父类类型、父类无 type 构造器、工厂建错类、
      槽位算术漂移、数据槽漂移 —— 同一个 audit() 必须各自判负

用法：python tools/selfcheck_round91_menu_type_match.py
"""
import glob
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
MENU_DIR = os.path.join(SRC, "menu")
MAIN = os.path.join(SRC, "RS_Create_Compat.java")
BASE_ENTITY = os.path.join(SRC, "block", "entity", "CollectionCacheBlockEntity.java")
ADV_ENTITY = os.path.join(SRC, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
BASE_MENU = os.path.join(MENU_DIR, "CollectionCacheMenu.java")
ADV_MENU = os.path.join(MENU_DIR, "AdvancedCollectionCacheMenu.java")

problems = []
checks = 0
ARITH_NOTES = []


def check(name, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print("PASS %s" % name)
    else:
        problems.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def code_only(text):
    """去掉注释再解析：注释里允许出现 super(...) / 构造器签名这类对照说明。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def balanced(text, open_index, opening, closing):
    """从 text[open_index] 的 opening 起，返回 (括号内文本, 配对闭括号下标)。"""
    depth = 0
    for i in range(open_index, len(text)):
        ch = text[i]
        if ch == opening:
            depth += 1
        elif ch == closing:
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i], i
    raise ValueError("括号不配对：%s at %d" % (opening, open_index))


def split_args(text):
    """按顶层逗号切参数（忽略 () [] <> {} 里的逗号）。"""
    args = []
    depth = 0
    start = 0
    for i, ch in enumerate(text):
        if ch in "([<{":
            depth += 1
        elif ch in ")]>}":
            depth -= 1
        elif ch == "," and depth == 0:
            args.append(text[start:i])
            start = i + 1
    tail = text[start:]
    if tail.strip():
        args.append(tail)
    return args


def super_arg_lists(text):
    """返回文件里所有 super(...) 调用的「已切分参数列表」；super.xxx(...) 不算。"""
    code = code_only(text)
    out = []
    for match in re.finditer(r"\bsuper\s*\(", code):
        inner, _ = balanced(code, match.end() - 1, "(", ")")
        out.append([arg.strip() for arg in split_args(inner)])
    return out


def iter_constructors(code, cls):
    """迭代类 cls 的构造器：(access, 参数文本, 方法体文本)。code 需已去注释。"""
    pattern = re.compile(r"\b(public|protected|private)\s+" + re.escape(cls) + r"\s*\(")
    for match in pattern.finditer(code):
        params, close_paren = balanced(code, match.end() - 1, "(", ")")
        brace = code.find("{", close_paren + 1)
        if brace < 0:
            continue
        body, _ = balanced(code, brace, "{", "}")
        yield match.group(1), params, body


def simple(name):
    return name.split(".")[-1]


REGISTRATION_RE = re.compile(
    r"DeferredHolder\s*<\s*MenuType\s*<\s*\?\s*>\s*,\s*MenuType\s*<\s*([\w.]+)\s*>\s*>\s*"
    r"(\w+)\s*=\s*MENUS\.register\(\s*\"([^\"]+)\"\s*,\s*"
    r"\(\)\s*->\s*new\s+MenuType\s*<>\s*\(\s*\(\s*id\s*,\s*inventory\s*\)\s*->\s*new\s+([\w.]+)\s*\(",
    re.S,
)


def parse_registrations(text):
    """解析 MENUS.register：返回 {类简单名: {holder, menu_id, factory(工厂 lambda 新建的类)}}。"""
    out = {}
    for match in REGISTRATION_RE.finditer(code_only(text)):
        out[simple(match.group(1))] = {
            "holder": match.group(2),
            "menu_id": match.group(3),
            "factory": simple(match.group(4)),
        }
    return out


def menu_classes(raw_sources):
    """返回 {类简单名: (父类简单名, 文件键)}，只含 menu/ 目录下的类。"""
    declared = {}
    for key, text in raw_sources.items():
        if not key.startswith("menu/"):
            continue
        match = re.search(r"\bclass\s+(\w+)\s+extends\s+([\w.]+)", code_only(text))
        if match:
            declared[match.group(1)] = (simple(match.group(2)), key)
    return declared


def static_expr(text, name):
    """取 `static final int NAME = <expr>;` 的表达式文本。"""
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*([^;]+);", text)
    if not match:
        raise KeyError("源码里找不到常量 %s" % name)
    return re.sub(r"\s+", " ", match.group(1)).strip()


EVAL_IDENT = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*")


def eval_expr(expr, env):
    """只允许整数四则运算 + env 里的常量；绝不 eval 任意代码。"""
    def substitute(match):
        token = match.group(0)
        return "(%d)" % env[token] if token in env else token

    body = EVAL_IDENT.sub(substitute, expr)
    if not re.fullmatch(r"[\d\s\+\-\*/\(\)]+", body):
        raise ValueError("无法求值的常量表达式：%r -> %r" % (expr, body))
    return int(eval(body, {"__builtins__": {}}, {}))


def references(expr, *names):
    return all(re.search(r"\b" + re.escape(n) + r"\b", expr) for n in names)


def slot_numbers(raw_sources):
    """从源码解析槽位 / 数据槽算术；任何解析失败都会抛异常（audit 会判负）。"""
    code = {name: code_only(text) for name, text in raw_sources.items()}
    base = code.get("menu/CollectionCacheMenu.java", "")
    adv = code.get("menu/AdvancedCollectionCacheMenu.java", "")
    entity = code.get("block/entity/CollectionCacheBlockEntity.java", "")
    adv_entity = code.get("block/entity/AdvancedCollectionCacheBlockEntity.java", "")
    if not (base and adv and entity and adv_entity):
        raise ValueError("缺少参与算术自证的源码文件")

    env = {}
    grid_expr, env["GRID_COLS"] = static_expr(base, "GRID_COLS"), eval_expr(static_expr(base, "GRID_COLS"), {})
    rows_expr, env["VISIBLE_ROWS"] = static_expr(base, "VISIBLE_ROWS"), eval_expr(static_expr(base, "VISIBLE_ROWS"), {})
    inv_expr, env["INV_COLS"] = static_expr(base, "INV_COLS"), eval_expr(static_expr(base, "INV_COLS"), {})
    env["CollectionCacheBlockEntity.UPGRADE_SLOTS"] = eval_expr(static_expr(entity, "UPGRADE_SLOTS"), {})
    env["CollectionCacheBlockEntity.MATCH_PAGES"] = eval_expr(static_expr(entity, "MATCH_PAGES"), {})
    env["CollectionCacheBlockEntity.CACHE_SLOTS"] = eval_expr(static_expr(entity, "CACHE_SLOTS"), {})

    match_expr = static_expr(base, "MATCH_WINDOW")
    cache_expr = static_expr(base, "CACHE_WINDOW")
    upgrade_expr = static_expr(base, "UPGRADE_COUNT")
    data_expr = static_expr(base, "DATA_SLOT_COUNT")
    env["MATCH_WINDOW"] = eval_expr(match_expr, env)
    env["CACHE_WINDOW"] = eval_expr(cache_expr, env)
    env["UPGRADE_COUNT"] = eval_expr(upgrade_expr, env)
    env["WINDOW_SLOTS"] = eval_expr(static_expr(base, "WINDOW_SLOTS"), env)
    env["UPGRADE_START"] = eval_expr(static_expr(base, "UPGRADE_START"), env)
    env["PLAYER_START"] = eval_expr(static_expr(base, "PLAYER_START"), env)
    env["DATA_SLOT_COUNT"] = eval_expr(data_expr, env)
    env["CollectionCacheBlockEntity.MATCH_CAPACITY"] = eval_expr(
        static_expr(base, "MATCH_CAPACITY"), env) if "MATCH_CAPACITY" in base else 0

    # ---- 高级版：BASE_SLOT_COUNT / AREA_SLOT_INDEX / AREA_DATA_COUNT ----
    adv_env = dict(env)
    base_slot_expr = static_expr(adv, "BASE_SLOT_COUNT")
    adv_env["BASE_SLOT_COUNT"] = eval_expr(base_slot_expr, adv_env)
    area_index_expr = static_expr(adv, "AREA_SLOT_INDEX")
    adv_env["AREA_SLOT_INDEX"] = eval_expr(area_index_expr, adv_env)
    area_count_expr = static_expr(adv, "AREA_DATA_COUNT")
    if "areaPanelDataCount()" not in area_count_expr:
        raise ValueError("AREA_DATA_COUNT 没有走 areaPanelDataCount()：%r" % area_count_expr)
    count_match = re.search(r"areaPanelDataCount\s*\(\s*\)\s*\{\s*return\s+([^;]+);", adv_entity)
    if not count_match:
        raise ValueError("解析不到 AdvancedCollectionCacheBlockEntity#areaPanelDataCount 的返回值")
    area_data_count = eval_expr(count_match.group(1), {})
    # 第 101 轮：AREA_DATA_COUNT = areaPanelDataCount() + 1（编辑上下文）+ AREA_LIST_SLOT_MAX（逐区域计数）。
    # 必须从源码表达式算出来（不许写死 23），所以把三部分分别求值再代入。
    list_max_match = re.search(r"AREA_LIST_SLOT_MAX\s*=\s*(\d+);", adv)
    if not list_max_match:
        raise ValueError("解析不到 AREA_LIST_SLOT_MAX")
    adv_env["AREA_LIST_SLOT_MAX"] = int(list_max_match.group(1))
    area_count_total_expr = (area_count_expr
                             .replace("AdvancedCollectionCacheBlockEntity.areaPanelDataCount()",
                                      str(area_data_count))
                             .replace("AREA_LIST_SLOT_MAX", str(adv_env["AREA_LIST_SLOT_MAX"])))
    area_count_total = eval_expr(area_count_total_expr, adv_env)
    adv_env["AREA_DATA_COUNT"] = area_count_total
    area_consts = {m.group(1): int(m.group(2))
                   for m in re.finditer(r"DATA_AREA_(\w+)\s*=\s*(\d+);", adv)}

    # ---- 138 / 139 / 29 / 35 ----
    player_slots = 3 * env["INV_COLS"] + env["INV_COLS"]
    base_total = env["MATCH_WINDOW"] + env["CACHE_WINDOW"] + env["UPGRADE_COUNT"] + player_slots
    advanced_total = adv_env["BASE_SLOT_COUNT"] + 1
    data_total = env["DATA_SLOT_COUNT"] + area_count_total
    base_add_slots = base.count("addSlot(")
    adv_add_slots = adv.count("addSlot(")
    base_add_data = base.count("addDataSlots(")
    adv_add_data = adv.count("addDataSlots(")
    loop_headers = (
        "for (int i = 0; i < MATCH_WINDOW; i++)",
        "for (int i = 0; i < CACHE_WINDOW; i++)",
        "for (int i = 0; i < UPGRADE_COUNT; i++)",
        "for (int row = 0; row < 3; row++)",
    )
    return {
        "GRID_COLS": env["GRID_COLS"],
        "VISIBLE_ROWS": env["VISIBLE_ROWS"],
        "INV_COLS": env["INV_COLS"],
        "UPGRADE_SLOTS": env["CollectionCacheBlockEntity.UPGRADE_SLOTS"],
        "MATCH_WINDOW": env["MATCH_WINDOW"],
        "CACHE_WINDOW": env["CACHE_WINDOW"],
        "UPGRADE_COUNT": env["UPGRADE_COUNT"],
        "player_slots": player_slots,
        "DATA_SLOT_COUNT": env["DATA_SLOT_COUNT"],
        "AREA_PANEL_COUNT": area_data_count,
        "AREA_LIST_SLOT_MAX": adv_env["AREA_LIST_SLOT_MAX"],
        "BASE_SLOT_COUNT": adv_env["BASE_SLOT_COUNT"],
        "AREA_SLOT_INDEX": adv_env["AREA_SLOT_INDEX"],
        "AREA_DATA_COUNT": area_count_total,
        "base_total": base_total,
        "advanced_total": advanced_total,
        "data_total": data_total,
        "base_add_slots": base_add_slots,
        "adv_add_slots": adv_add_slots,
        "base_add_data": base_add_data,
        "adv_add_data": adv_add_data,
        "loop_ok": (
            all(header in base for header in loop_headers)
            and base.count("for (int col = 0; col < INV_COLS; col++)") == 2
        ),
        "data_getcount_ok": ("return DATA_SLOT_COUNT;" in base and "return AREA_DATA_COUNT;" in adv),
        "area_slot_ok": "new SlotItemHandler(areaSlot, 0, AREA_SLOT_X, AREA_SLOT_Y)" in adv,
        "area_consts": area_consts,
        "expr": {
            "GRID_COLS": grid_expr,
            "VISIBLE_ROWS": rows_expr,
            "INV_COLS": inv_expr,
            "MATCH_WINDOW": match_expr,
            "CACHE_WINDOW": cache_expr,
            "UPGRADE_COUNT": upgrade_expr,
            "DATA_SLOT_COUNT": data_expr,
            "BASE_SLOT_COUNT": base_slot_expr,
            "AREA_SLOT_INDEX": area_index_expr,
            "AREA_DATA_COUNT": area_count_expr,
        },
    }


def audit(raw_sources):
    """核心审计：返回问题码集合；空集 = 通过。真实修法必须 0 问题，反例必须命中对应码。"""
    result = set()
    del ARITH_NOTES[:]
    code = {name: code_only(text) for name, text in raw_sources.items()}
    declared = menu_classes(raw_sources)
    registrations = parse_registrations(code.get("RS_Create_Compat.java", ""))

    # ---------------- ①a 子类 super 的 MenuType ----------------
    subclasses = [(child, parent, key) for child, (parent, key) in declared.items()
                  if parent in declared and parent != child]
    if not subclasses:
        result.add("menu-subclass-parsing")
    for child, parent, key in subclasses:
        child_reg = registrations.get(child)
        parent_reg = registrations.get(parent)
        if not child_reg or not parent_reg:
            result.add("menu-registration")
            continue
        if child_reg["holder"] == parent_reg["holder"]:
            result.add("menu-registration")
        supers = super_arg_lists(raw_sources[key])
        if not supers:
            result.add("child-menu-type")
            continue
        for args in supers:
            first = args[0] if args else ""
            holder_match = re.fullmatch(r"RS_Create_Compat\.(\w+)\.get\(\)", first)
            if len(args) != 4 or not holder_match or holder_match.group(1) != child_reg["holder"]:
                result.add("child-menu-type")
                break

    # ---------------- ①b 父类 protected type 构造器 ----------------
    for child, (parent, _key) in declared.items():
        if parent not in declared or parent == child:
            continue
        parent_code = code[declared[parent][1]]
        found = False
        for access, params, body in iter_constructors(parent_code, parent):
            first_param = split_args(params)[0] if params.strip() else ""
            if access == "protected" and re.search(r"MenuType\s*<\s*\?\s*>", first_param):
                if re.search(r"super\s*\(\s*type\s*,\s*id\s*\)", body):
                    found = True
                    break
        if not found:
            result.add("parent-type-ctor")

    # ---------------- ①c 工厂 lambda 与注册泛型同类 ----------------
    for cls, reg in registrations.items():
        if cls in declared and reg["factory"] != cls:
            result.add("factory-mismatch")

    # ---------------- ①d 槽位 / 数据槽算术 ----------------
    try:
        numbers = slot_numbers(raw_sources)
        expr = numbers["expr"]
        # 常量必须真的由别的常量算出（不是写死一个数字假装解析）
        if not references(expr["MATCH_WINDOW"], "GRID_COLS", "VISIBLE_ROWS"):
            result.add("slot-arithmetic")
            ARITH_NOTES.append("MATCH_WINDOW 没引用 GRID_COLS / VISIBLE_ROWS")
        if not references(expr["CACHE_WINDOW"], "GRID_COLS", "VISIBLE_ROWS"):
            result.add("slot-arithmetic")
            ARITH_NOTES.append("CACHE_WINDOW 没引用 GRID_COLS / VISIBLE_ROWS")
        if not references(expr["UPGRADE_COUNT"], "CollectionCacheBlockEntity.UPGRADE_SLOTS"):
            result.add("slot-arithmetic")
            ARITH_NOTES.append("UPGRADE_COUNT 没引用 CollectionCacheBlockEntity.UPGRADE_SLOTS")
        if not references(expr["BASE_SLOT_COUNT"], "MATCH_WINDOW", "CACHE_WINDOW", "UPGRADE_COUNT"):
            result.add("slot-arithmetic")
            ARITH_NOTES.append("BASE_SLOT_COUNT 没引用 MATCH_WINDOW / CACHE_WINDOW / UPGRADE_COUNT")
        if not references(expr["AREA_SLOT_INDEX"], "BASE_SLOT_COUNT"):
            result.add("slot-arithmetic")
            ARITH_NOTES.append("AREA_SLOT_INDEX 没引用 BASE_SLOT_COUNT")

        # 算术自证：138 / 139
        if numbers["MATCH_WINDOW"] != numbers["GRID_COLS"] * numbers["VISIBLE_ROWS"]:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("MATCH_WINDOW != GRID_COLS * VISIBLE_ROWS")
        if numbers["CACHE_WINDOW"] != numbers["GRID_COLS"] * numbers["VISIBLE_ROWS"]:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("CACHE_WINDOW != GRID_COLS * VISIBLE_ROWS")
        if numbers["base_total"] != 138:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("基础版槽位合计 %d != 138" % numbers["base_total"])
        if numbers["BASE_SLOT_COUNT"] != 138:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("BASE_SLOT_COUNT %d != 138" % numbers["BASE_SLOT_COUNT"])
        if numbers["advanced_total"] != 139:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("高级版槽位合计 %d != 139" % numbers["advanced_total"])
        if numbers["AREA_SLOT_INDEX"] != numbers["BASE_SLOT_COUNT"]:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("AREA_SLOT_INDEX != BASE_SLOT_COUNT")
        if numbers["base_add_slots"] != 5 or numbers["adv_add_slots"] != 1:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("addSlot 次数 base=%d adv=%d（应 5 / 1）"
                               % (numbers["base_add_slots"], numbers["adv_add_slots"]))
        if not numbers["loop_ok"]:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("addSlot 循环结构不符合 MATCH/CACHE/UPGRADE/3×INV/HOTBAR")
        if not numbers["area_slot_ok"]:
            result.add("slot-arithmetic")
            ARITH_NOTES.append("高级版唯一新增槽不是 SlotItemHandler(areaSlot, 0, ...)")

        # 数据槽（第 101 轮）：基础 31 + 区域 71 = 102；区域 71 = 面板 6 + 编辑上下文 1 + 逐区域计数 64
        # （第 121 轮 C-4：逐区域计数从 16 抬到 64，与 ABSOLUTE_MAX_AREAS 对齐）
        if numbers["DATA_SLOT_COUNT"] != 31:
            result.add("data-arithmetic")
            ARITH_NOTES.append("DATA_SLOT_COUNT %d != 31" % numbers["DATA_SLOT_COUNT"])
        if numbers["AREA_PANEL_COUNT"] != 6:
            result.add("data-arithmetic")
            ARITH_NOTES.append("areaPanelDataCount %d != 6" % numbers["AREA_PANEL_COUNT"])
        if numbers["AREA_DATA_COUNT"] != numbers["AREA_PANEL_COUNT"] + 1 + numbers["AREA_LIST_SLOT_MAX"]:
            result.add("data-arithmetic")
            ARITH_NOTES.append("AREA_DATA_COUNT %d != 面板 %d + 1 + 逐区域计数 %d"
                               % (numbers["AREA_DATA_COUNT"], numbers["AREA_PANEL_COUNT"],
                                  numbers["AREA_LIST_SLOT_MAX"]))
        if numbers["data_total"] != 102:
            result.add("data-arithmetic")
            ARITH_NOTES.append("数据槽合计 %d != 102" % numbers["data_total"])
        if numbers["base_add_data"] != 1 or numbers["adv_add_data"] != 1:
            result.add("data-arithmetic")
            ARITH_NOTES.append("addDataSlots 次数 base=%d adv=%d（应 1 / 1）"
                               % (numbers["base_add_data"], numbers["adv_add_data"]))
        if not numbers["data_getcount_ok"]:
            result.add("data-arithmetic")
            ARITH_NOTES.append("ContainerData.getCount() 没有分别返回 DATA_SLOT_COUNT / AREA_DATA_COUNT")
        consts = numbers["area_consts"]
        panel_ok = sorted(v for k, v in consts.items()
                          if k in ("COUNT", "VOLUME", "STATUS", "POS_X", "POS_Y", "POS_Z")) == [0, 1, 2, 3, 4, 5]
        layout_ok = (consts.get("EDITING") == numbers["AREA_PANEL_COUNT"]
                     and consts.get("FILTER_BASE") == numbers["AREA_PANEL_COUNT"] + 1
                     and consts.get("FILTER_BASE") + numbers["AREA_LIST_SLOT_MAX"]
                     == numbers["AREA_DATA_COUNT"])
        if not panel_ok or not layout_ok:
            result.add("data-arithmetic")
            ARITH_NOTES.append("DATA_AREA_* 常量布局不成立：%r（应面板 0..5 连续、EDITING_AREA=6、"
                               "AREA_FILTER_BASE=7、7+%d=%d）"
                               % (consts, numbers["AREA_LIST_SLOT_MAX"], numbers["AREA_DATA_COUNT"]))
    except Exception as exc:  # 解析失败本身就是问题：不许「解析不到 ⇒ 跳过」
        result.add("slot-arithmetic")
        ARITH_NOTES.append("算术自证解析失败：%s" % exc)

    return result


def load_sources():
    sources = {
        "RS_Create_Compat.java": read(MAIN),
        "block/entity/CollectionCacheBlockEntity.java": read(BASE_ENTITY),
        "block/entity/AdvancedCollectionCacheBlockEntity.java": read(ADV_ENTITY),
    }
    for path in sorted(glob.glob(os.path.join(MENU_DIR, "*.java"))):
        sources["menu/" + os.path.basename(path)] = read(path)
    return sources


def mutate(raw_sources, key, old, new):
    copy = dict(raw_sources)
    if old not in copy[key]:
        raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (key, old))
    copy[key] = copy[key].replace(old, new, 1)
    return copy


# ==================================================================== 主流程
for path, label in ((BASE_MENU, "CollectionCacheMenu.java"),
                    (ADV_MENU, "AdvancedCollectionCacheMenu.java"),
                    (MAIN, "RS_Create_Compat.java"),
                    (BASE_ENTITY, "CollectionCacheBlockEntity.java"),
                    (ADV_ENTITY, "AdvancedCollectionCacheBlockEntity.java")):
    check("① 源码存在：%s" % label, os.path.isfile(path))

sources = load_sources()
declared = menu_classes(sources)
subclasses = [(child, parent) for child, (parent, _key) in declared.items()
              if parent in declared and parent != child]
print("[info] menu/ 下解析到的菜单子类：%s" % (subclasses or "无"))
check("①a menu/ 下存在 AdvancedCollectionCacheMenu extends CollectionCacheMenu（当前唯一菜单子类）",
      ("AdvancedCollectionCacheMenu", "CollectionCacheMenu") in subclasses)

real_problems = audit(sources)
check("①a 子类构造器 super 首参 = 自己注册的 MenuType holder（且 ≠ 父类 holder）",
      "child-menu-type" not in real_problems,
      "命中了 implicit/父类 MenuType 的 super 调用")
check("①a 两侧 MenuType 注册都能解析且 holder 不同",
      "menu-registration" not in real_problems and "menu-subclass-parsing" not in real_problems)
check("①b 父类存在 protected + 首参 MenuType<?> 的构造器，且真转发 super(type, id)",
      "parent-type-ctor" not in real_problems)
check("①c RS_Create_Compat 客户端工厂 lambda 建的类 = 注册泛型里的类（高级/基础两侧同类）",
      "factory-mismatch" not in real_problems)
check("①d 槽位算术（源码解析值）BASE=138 / 高级=139 / 数据槽 31+71=102",
      "slot-arithmetic" not in real_problems and "data-arithmetic" not in real_problems,
      "；".join(ARITH_NOTES))

# 把解析出的数字 / 表达式打出来，避免「写死数字后假装解析」
try:
    numbers = slot_numbers(sources)
    print("[info] 解析常量：GRID_COLS=%d VISIBLE_ROWS=%d INV_COLS=%d UPGRADE_SLOTS=%d"
          % (numbers["GRID_COLS"], numbers["VISIBLE_ROWS"], numbers["INV_COLS"], numbers["UPGRADE_SLOTS"]))
    print("[info] 槽位：MATCH_WINDOW=%d CACHE_WINDOW=%d UPGRADE_COUNT=%d 玩家=%d"
          " => BASE_SLOT_COUNT=%d / 高级版=%d（+1 区域选定器槽）"
          % (numbers["MATCH_WINDOW"], numbers["CACHE_WINDOW"], numbers["UPGRADE_COUNT"],
             numbers["player_slots"], numbers["BASE_SLOT_COUNT"], numbers["advanced_total"]))
    print("[info] 数据槽：DATA_SLOT_COUNT=%d + AREA_DATA_COUNT=%d => %d"
          % (numbers["DATA_SLOT_COUNT"], numbers["AREA_DATA_COUNT"], numbers["data_total"]))
    print("[info] 表达式：MATCH_WINDOW=%r；UPGRADE_COUNT=%r；BASE_SLOT_COUNT=%r；AREA_SLOT_INDEX=%r"
          % (numbers["expr"]["MATCH_WINDOW"], numbers["expr"]["UPGRADE_COUNT"],
             numbers["expr"]["BASE_SLOT_COUNT"], numbers["expr"]["AREA_SLOT_INDEX"]))
    check("①d BASE_SLOT_COUNT == MATCH_WINDOW + CACHE_WINDOW + UPGRADE_COUNT + 9*4 == 138",
          numbers["BASE_SLOT_COUNT"] == numbers["MATCH_WINDOW"] + numbers["CACHE_WINDOW"]
          + numbers["UPGRADE_COUNT"] + 9 * 4 == 138)
    check("①d 高级版槽位 = BASE_SLOT_COUNT + 1 == 139（多 1 个区域选定器槽）",
          numbers["advanced_total"] == numbers["BASE_SLOT_COUNT"] + 1 == 139)
    check("①d 数据槽：基础 31 + 区域 71 = 102（区域 = 面板 6 + 编辑上下文 1 + 逐区域计数 64）",
          numbers["DATA_SLOT_COUNT"] + numbers["AREA_DATA_COUNT"] == 102
          and numbers["AREA_DATA_COUNT"] == numbers["AREA_PANEL_COUNT"] + 1 + numbers["AREA_LIST_SLOT_MAX"])
except Exception as exc:
    check("①d 解析并打印槽位算术", False, str(exc))

# 精确锚定 holder 名（需求点名的名字；改名会让这条变红，提醒同步检查）
registrations = parse_registrations(sources["RS_Create_Compat.java"])
check("①a 解析到的 holder：高级版=ADVANCED_COLLECTION_CACHE_MENU / 基础版=COLLECTION_CACHE_MENU",
      registrations.get("AdvancedCollectionCacheMenu", {}).get("holder") == "ADVANCED_COLLECTION_CACHE_MENU"
      and registrations.get("CollectionCacheMenu", {}).get("holder") == "COLLECTION_CACHE_MENU")
check("①c 两个菜单的注册 id 分别是 advanced_collection_cache / collection_cache",
      registrations.get("AdvancedCollectionCacheMenu", {}).get("menu_id") == "advanced_collection_cache"
      and registrations.get("CollectionCacheMenu", {}).get("menu_id") == "collection_cache")

# ==================================================================== ①e 反例自证
# 反例 1（原始 bug 形态）：子类构造器只写 super(id, inventory, block)，隐式沿用父类 MenuType。
case = mutate(sources, "menu/AdvancedCollectionCacheMenu.java",
              "super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get(), id, inventory, block);",
              "super(id, inventory, block);")
check("①e 反例（原始 bug：子类沿用基础版 MenuType）判负", "child-menu-type" in audit(case))
print("      反例：子类沿用基础版 MenuType → 判负（这正是线上「开界面即踢」的源码形态）")

# 反例 2：子类显式传父类 MenuType
case = mutate(sources, "menu/AdvancedCollectionCacheMenu.java",
              "RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get()",
              "RS_Create_Compat.COLLECTION_CACHE_MENU.get()")
check("①e 反例（子类 super 显式传父类 MenuType）判负", "child-menu-type" in audit(case))
print("      反例：子类显式传基础版 MenuType → 判负")

# 反例 3：父类没有 protected 的 MenuType 构造器（子类无从指定类型）
case = mutate(sources, "menu/CollectionCacheMenu.java",
              "protected CollectionCacheMenu(final net.minecraft.world.inventory.MenuType<?> type,",
              "private CollectionCacheMenu(final net.minecraft.world.inventory.MenuType<?> type,")
check("①e 反例（父类 type 构造器不是 protected）判负", "parent-type-ctor" in audit(case))
print("      反例：父类 type 构造器改成 private → 判负")

# 反例 4：注册的客户端工厂建了另一个类（客户端仍会建基础版菜单）
case = mutate(sources, "RS_Create_Compat.java",
              "() -> new MenuType<>((id, inventory) -> new AdvancedCollectionCacheMenu(id, inventory),",
              "() -> new MenuType<>((id, inventory) -> new CollectionCacheMenu(id, inventory),")
check("①e 反例（高级版工厂 lambda 建基础版菜单）判负", "factory-mismatch" in audit(case))
print("      反例：高级版 MenuType 的工厂建 CollectionCacheMenu → 判负")

# 反例 5：槽位算术漂移（UPGRADE_COUNT 多 1）
case = mutate(sources, "menu/CollectionCacheMenu.java",
              "UPGRADE_COUNT = CollectionCacheBlockEntity.UPGRADE_SLOTS;",
              "UPGRADE_COUNT = CollectionCacheBlockEntity.UPGRADE_SLOTS + 1;")
check("①e 反例（槽位算术漂移：基础版 139）判负", "slot-arithmetic" in audit(case))
print("      反例：UPGRADE_COUNT 多 1 ⇒ 基础版 139 → 判负")

# 反例 6：数据槽漂移（DATA_SLOT_COUNT 多 1）
case = mutate(sources, "menu/CollectionCacheMenu.java",
              "DATA_SLOT_COUNT = 31;", "DATA_SLOT_COUNT = 32;")
check("①e 反例（数据槽漂移：31→32）判负", "data-arithmetic" in audit(case))
print("      反例：DATA_SLOT_COUNT 31→32 → 判负")

print("=" * 78)
if problems:
    print("[result] 自检失败：%d 个问题" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 6 个反例自证）；菜单子类使用自己的 MenuType，"
      "基础版 138 槽 / 29 数据槽、高级版 139 槽 / 35 数据槽，与源码解析值一致" % checks)
sys.exit(0)
