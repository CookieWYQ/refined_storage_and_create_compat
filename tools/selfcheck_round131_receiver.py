#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮 B 组自检：网络资源接收器改成「取物终端」网格（物品 / 流体格子 + 按行滚动 + 点一下取出）。

规格来源
--------
`docs/ROUND131_CONTRACT.md` §0（用户原话）、§2（B 组写作用域）、§3.B（验收硬判据）。
失败信息里写「契约 §3.B.x：期望 …；实际 …」，判据名统一以 `§` 开头，便于按编号汇报。

本脚本怎么证明「改对了」（不是「文件里有这个字符串」）
----------------------------------------------------
① **几何**：从**真源码**解析常量（`GRID_COLS/GRID_ROWS/CELL/GRID_X0/GRID_Y0/SCROLLBAR_*`），
   用算术证明「网格在面板里、不压背包标签、不压滚动条、不压升级槽、名字行在网格之上」；
   再用 **PNG 像素**证明「槽框真的烘焙在图里、位置与 Java 常量逐格对齐」（槽框左上角 = Menu 坐标 − 1，
   因此槽心像素 = Menu 坐标 + 7 必须是槽位底色 139,139,139）；最后证明 `renderBg` **只有一次 blit**
   且**没有 fill**（第 128 轮铁律）。
② **交互**：滚轮 → 按行滚动请求；滚动条 listener → 同一个请求；点格子 → 取出请求（左键 / Shift）；
   界面里**没有**页码箭头 / 上一页下一页 / 「取全部」按钮 / 任何状态句（改名结果改走动作条 + 输入框 tooltip）。
③ **服务端权威与守恒**（本轮最容易被写坏的语义）：
   - 客户端**只报身份 + 要不要全部**，不报数量；三道守卫（containerId / 菜单类型 / stillValid）逐条在；
   - 物品：「先真扣减 → 只把背包真收下的算取走 → 放不下回填缓存」，四步顺序由**代码位置**断言；
   - 流体：容器来源顺序必须 **光标 → 网络 → 背包 → 都没有**（契约 §3.B.4；RS 2.0.9 证据见 EVIDENCE 段），
     网络桶必须**先 SIMULATE 确认存在**再真扣；「都没有」分支要在**任何 drain 之前**返回（一个 mB 都不扣）；
     每条真扣路径失败后都必须 `refillFluidCache(...)` 回滚（不存在「扣了没给」）。
④ **协议线格式没被改**：`SyncReceiverConfigPacket` 的 13 个分量仍是原样（本轮**不改**这个文件），
   只是 `page` / `pageCount` 的语义从「页码 / 总页数」变成「页首行 / 总行数」；
   接收器界面不再读 `itemTypes/itemTotal/fluidTypes/fluidTotal/facing`（那些状态句已经删了）。
⑤ **回归**：升级槽（速度 / 叠加）与名字链路（服务端权威改名）一个字都没动；
   缓存数据源仍是方块实体的 `cacheEntries()` / `getFluidSnapshot()`。
⑥ **反例自证**：把旧写法 / 坏语义拼回去（空树、旧状态句、取全部按钮、页码箭头、点了不扣、
   扣了不回填、无容器先扣、顺序颠倒、客户端报数量、PNG 缺槽框 …）**必须判负**，证明判据有区分力。
   反例只在内存里改字符串 / 改像素映射，**不写任何文件**。

EVIDENCE（RS 2.0.9 流体取出顺序的真实出处；本脚本的第 ③ 组判据就是照它写的）
--------------------------------------------------------------------------
* 类：`com.refinedmods.refinedstorage.neoforge.grid.strategy.FluidGridExtractionStrategy`
  （本地源码检出：`local_src/rs_src/com/refinedmods/refinedstorage/neoforge/grid/strategy/
  FluidGridExtractionStrategy.java`）
* `onExtract(PlatformResourceKey, GridExtractMode, boolean)`（:47-65）：
  `containerOnCursor`（:55）→ `bucketInInventory`（:57）→ `bucketInStorage`（:59）；
  三者都不成立时**只 `return true`，什么都不做** ⇒ 「取不出就一个 mB 都不扣」。
* 光标路径：`extractWithContainerOnCursor`（:72-88）——目标是 `menu.getCarried()` 的
  `Capabilities.FluidHandler.ITEM`，灌满后 `menu.setCarried(destination.getContainer())`。
* 网络桶路径：`hasBucketInStorage`（:148-150）= `itemStorage.extract(BUCKET, 1, SIMULATE, EMPTY) == 1`
  ⇒ **先模拟**；`extractSourceBucket(false)`（:118）= `itemStorage.extract(BUCKET, 1, EXECUTE, actor)`。
* 背包桶路径：`hasBucketInInventory`（:152-154）→ `extractBucket(playerInventoryStorage, ...)`（:156-168）。
* 失败回滚：`insertResultingBucket` 失败时 `insertSourceBucket(...)` 把空桶塞回去（:105-108）
  —— 即「绝不存在扣了没给」。
* **顺序结论（第 131 轮验收拍板）**：`:57`（背包）在 `:59`（网络）**之前** ⇒ RS 是「背包优先、
  网络兜底」。用户第 131 轮补话「这个顺序我不明白，你按照 RS 的来」⇒ 本组按 RS 实现：
  **光标 → 背包 → 网络 → 都没有**。「光标容器」是契约 §3.B.4 没写的一步，但 RS 有（:55），
  少了它「手上拿着空桶点流体」会取不出来，故保留。

纪律：不跑 gradle / javac（Lead 统一编译）、不 git、不改 `src/**`（本脚本只读）、不写任何文件。
用法：python tools/selfcheck_round131_receiver.py
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
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)
LANG_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "lang")
LANG = os.path.join(ROOT, LANG_REL)
TEXTURES_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "textures", "gui")

CONTRACT = "docs/ROUND131_CONTRACT.md"

FILES = {
    "screen": os.path.join(JAVA, "client", "screen", "NetworkResourceReceiverScreen.java"),
    "menu": os.path.join(JAVA, "menu", "NetworkResourceReceiverMenu.java"),
    "packet": os.path.join(JAVA, "network", "ReceiverCacheActionPacket.java"),
    "sync": os.path.join(JAVA, "network", "SyncReceiverConfigPacket.java"),
    "blockentity": os.path.join(JAVA, "block", "entity", "NetworkResourceReceiverBlockEntity.java"),
    "lang_zh": os.path.join(LANG, "zh_cn.json"),
    "lang_en": os.path.join(LANG, "en_us.json"),
    "png": os.path.join(ROOT, TEXTURES_REL, "network_resource_receiver.png"),
}

#: 本轮**新增**的语言键（Lead 统一落盘；B 组只报这一个 —— 物品手势复用既有 cache.slot.hint）。
NEW_LANG_KEYS = (
    "gui.rs_create_compat.network_resource_receiver.grid.fluid_hint",
)

#: 界面里**不许**再出现的旧状态句（语言键字面量，含引号，避免误伤 cache.fluid.hint 这类同前缀键）。
BANNED_LANG_LITERALS = (
    '"throttle"',
    '"cache.summary"',
    '"cache.fluid"',
    '"cache.fluid.none"',
    '"cache.take_all"',
    '"cache.take_all.hint"',
    '"cache.empty"',
    '"status.unnamed"',
    '"status.named"',
    '"status.named.facing"',
    '"cache.more"',
    '"cache.fluid.page"',
)

#: 界面里**不许**再出现的旧状态机 / 旧控件符号。
BANNED_SCREEN_SYMBOLS = (
    "throttleLine",
    "cacheSummary",
    "fluidSummary",
    "statusLine",
    "renderPageHeader",
    "pageArrowAt",
    "ARROW_PREV",
    "ARROW_NEXT",
    "takeAllButton",
    "TAKE_ALL_X",
    "entryIndexAt",
    "PAGE_ROWS",
    "PAGE_SIZE",
)

#: 菜单里**不许**再出现的「取全部」通道（契约 §3.B.1：按钮删掉）。
BANNED_MENU_SYMBOLS = (
    "BUTTON_TAKE_ALL_ITEMS",
    "takeAllCacheItems",
    "firstNonEmptySlot",
    "clickMenuButton",
)

#: 流体容器来源顺序的四个锚点（必须按这个先后出现）。
#: 第 131 轮验收拍板：**按 RS 2.0.9 的真实顺序**（用户原话「这个顺序我不明白，你按照 RS 的来」），
#: 即 `FluidGridExtractionStrategy#onExtract` 的 :55 光标 → :57 背包 → :59 网络 → :62 都没有。
#: 注意「背包」在「网络」**之前**（RS 是背包优先、网络兜底），本脚本的判据就是这个顺序。
FLUID_ORDER_ANCHORS = (
    ("① 光标上的容器", "fillCarriedContainer(player, identity)"),
    ("② 玩家背包里的空桶", "countInventoryBuckets(player)"),
    ("③ 网络里的空桶", "networkBucketCount()"),
    ("④ 都没有 ⇒ 取不出", "MSG_FLUID_NO_CONTAINER"),
)

FAILS = []
COUNTER_TOTAL = [0]
COUNTER_PASS = [0]


# ======================================================================================
# 基础工具（与 round129 / round130 同一套口径）
# ======================================================================================

def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def read(path):
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去注释、保留字符串字面量（判据要看语言键字面量与 `"(" +` 这类代码特征）。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


METHOD_HEAD = re.compile(r"\b(?:public|private|protected)\s+[^;{}()]*?\b(\w+)\s*\(")


def java_methods(code):
    """→ [(名字, 起点, 终点+1, 方法体文本)]：正则找方法头 + 括号配对（不写死行号）。"""
    out = []
    for match in METHOD_HEAD.finditer(code):
        paren = match.end() - 1
        depth = 0
        end_params = -1
        i = paren
        while i < len(code):
            if code[i] == "(":
                depth += 1
            elif code[i] == ")":
                depth -= 1
                if depth == 0:
                    end_params = i
                    break
            i += 1
        if end_params < 0:
            continue
        j = end_params + 1
        while j < len(code) and code[j] in " \t\r\n":
            j += 1
        if code.startswith("throws", j):
            brace = code.find("{", j)
            if brace < 0:
                continue
            j = brace
        if j >= len(code) or code[j] != "{":
            continue
        depth = 0
        k = j
        while k < len(code):
            if code[k] == "{":
                depth += 1
            elif code[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        if k >= len(code) or depth != 0:
            continue
        out.append((match.group(1), match.start(), k + 1, code[match.start():k + 1]))
    return out


def method_body_of(code, name):
    for item in java_methods(code):
        if item[0] == name:
            return item[3]
    return None


def const_int(code, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*(-?\d+)\s*;", code)
    return int(match.group(1)) if match else None


def const_expr(code, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*([^;]+);", code)
    return match.group(1).strip() if match else None


IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
SAFE_EXPR_RE = re.compile(r"^[0-9+\-*/() .]+$")


def _eval_int(expr, table):
    """把 `GRID_COLS * CELL` 这类常量表达式求值（只认已解析常量 + 字面量）。"""
    text = IDENT_RE.sub(lambda m: str(table[m.group(0)]) if m.group(0) in table else m.group(0), expr)
    left = sorted(set(IDENT_RE.findall(re.sub(r"0[xX][0-9a-fA-F]+", "0", text))))
    if left:
        raise NameError("未解析：" + ", ".join(left))
    if not SAFE_EXPR_RE.match(text):
        raise ValueError("非算术表达式：%r" % expr)
    return int(eval(text, {"__builtins__": {}}, {}))  # noqa: S307 —— 只喂过白名单字符


def resolve_consts(code, names):
    """解析一组 `int 名字 = 表达式;`（顺序无关：依赖没解析出来就下一轮再试）。"""
    table = {}
    pending = {}
    for name in names:
        expr = const_expr(code, name)
        if expr is not None:
            pending[name] = expr
    progress = True
    while pending and progress:
        progress = False
        for name in list(pending):
            try:
                table[name] = _eval_int(pending[name], table)
            except NameError:
                continue
            except Exception:
                del pending[name]
                continue
            del pending[name]
            progress = True
    return table


def placeholders(text):
    return len(re.findall(r"%(?:\d+\$)?[sd]", str(text)))


def add(out, name, ok, detail=""):
    out.append((name, bool(ok), detail))


# ======================================================================================
# PNG 采样（槽框是否烘焙、是否与 Java 坐标对齐）
# ======================================================================================

def sample_png(expected_centers, control_points):
    """→ {"size": (w,h), "slot_ok": {(x,y): bool}, "control_ok": {...}, "error": str|None}。

    槽心像素判据：`pic/slot.png` 的 17×17 槽框内部是槽位底色 `(139,139,139)`；
    槽框左上角 = Menu 坐标 − 1 ⇒ **槽心 = Menu 坐标 + 7**。
    """
    path = FILES["png"]
    result = {"size": None, "slot_ok": {}, "control_ok": {}, "error": None}
    if not os.path.isfile(path):
        result["error"] = "文件不存在：%s" % rel(path)
        return result
    try:
        from PIL import Image  # noqa: PLC0415  —— 只在需要像素时导入
    except Exception as exc:  # pragma: no cover
        result["error"] = "PIL 不可用（%s）⇒ 无法核对像素" % exc
        return result
    with Image.open(path) as raw:
        image = raw.convert("RGBA")
    result["size"] = image.size
    pixels = image.load()
    for point in expected_centers:
        color = pixels[point[0], point[1]][:3] if (
            0 <= point[0] < image.size[0] and 0 <= point[1] < image.size[1]) else None
        result["slot_ok"][point] = color == (139, 139, 139)
    for point in control_points:
        color = pixels[point[0], point[1]][:3] if (
            0 <= point[0] < image.size[0] and 0 <= point[1] < image.size[1]) else None
        result["control_ok"][point] = color
    return result


# ======================================================================================
# 快照（原文 + 语言键 + PNG 采样）
# ======================================================================================

def load_snapshot():
    snap = {}
    for key, path in FILES.items():
        if key in ("lang_zh", "lang_en"):
            text = read(path)
            try:
                snap[key] = json.loads(text) if text.strip() else {}
            except Exception:
                snap[key] = {}
        elif key == "png":
            snap[key] = {}  # 先占位，几何常量解析完再采样
        else:
            snap[key] = read(path)
    snap["_png_sampled"] = False
    return snap


def prepare(snap):
    """原文 → 去注释文本；并在几何常量可解析时补上 PNG 采样。"""
    out = dict(snap)
    for key in ("screen", "menu", "packet", "sync", "blockentity"):
        out[key + "_code"] = strip_comments(snap.get(key) or "")
    menu = out["menu_code"]
    screen_code = out["screen_code"]
    constants = resolve_consts(menu, (
        "GRID_COLS", "GRID_ROWS", "CELL", "GRID_X0", "GRID_Y0", "GRID_W", "GRID_H",
        "WINDOW_SIZE", "SCROLLBAR_X", "SCROLLBAR_Y", "SCROLLBAR_H", "SCROLLBAR_W",
        "INV_X0", "INV_Y0", "HOTBAR_Y", "UPGRADE_X", "UPGRADE_Y0", "UPGRADE_COUNT",
        "PANEL_W", "PANEL_H"))
    constants.update(resolve_consts(screen_code, (
        "BOX_X", "BOX_Y", "BOX_W", "BOX_H", "OK_X", "OK_W",
        "UPGRADES_LABEL_X", "UPGRADES_LABEL_Y", "UPGRADES_LABEL_MAX_W")))
    constants.setdefault("UPGRADE_COUNT", 6)  # = RsccSpeedStackUpgradeContainer.SLOTS（契约 §3.B.6）
    out["constants"] = constants
    if not snap.get("_png_sampled"):
        centers = expected_slot_centers(constants)
        controls = [(constants.get("GRID_X0", 9) + 4, constants.get("GRID_Y0", 38) - 6)]
        out["png"] = sample_png(centers, controls)
        out["_png_sampled"] = True
    return out


def expected_slot_centers(c):
    """由 **Java 常量** 推出 PNG 里应当存在的槽心（槽框左上角 = Menu − 1 ⇒ 槽心 = Menu + 7）。"""
    centers = []
    cell = c.get("CELL", 18)
    # 网格：GRID_ROWS × GRID_COLS
    for row in range(c.get("GRID_ROWS", 0)):
        for col in range(c.get("GRID_COLS", 0)):
            centers.append((c.get("GRID_X0", 0) + col * cell + 7,
                            c.get("GRID_Y0", 0) + row * cell + 7))
    # 升级槽：1 列 × UPGRADE_COUNT
    for index in range(c.get("UPGRADE_COUNT", 0)):
        centers.append((c.get("UPGRADE_X", 0) + 7,
                        c.get("UPGRADE_Y0", 0) + index * cell + 7))
    # 玩家背包 3×9 + 快捷栏 1×9
    for row in range(3):
        for col in range(9):
            centers.append((c.get("INV_X0", 0) + col * cell + 7,
                            c.get("INV_Y0", 0) + row * cell + 7))
    for col in range(9):
        centers.append((c.get("INV_X0", 0) + col * cell + 7,
                        c.get("HOTBAR_Y", 0) + 7))
    return centers


# ======================================================================================
# §0 文件存在
# ======================================================================================

def section_files(s):
    out = []
    for key, label in (("screen", "接收器界面"), ("menu", "接收器菜单"),
                       ("packet", "取物包"), ("sync", "同步包"), ("blockentity", "方块实体")):
        add(out, "§0 %s 存在且非空（%s）" % (label, os.path.basename(FILES[key])),
            bool((s.get(key) or "").strip()), "文件不存在或为空：%s" % rel(FILES[key]))
    for key, label in (("lang_zh", "zh_cn.json"), ("lang_en", "en_us.json")):
        add(out, "§0 %s 能解析成 JSON 对象" % label,
            isinstance(s.get(key), dict) and bool(s.get(key)), "解析失败或为空：%s" % rel(FILES[key]))
    add(out, "§0 背景贴图存在（%s）" % os.path.basename(FILES["png"]),
        os.path.isfile(FILES["png"]), "文件不存在：%s" % rel(FILES["png"]))
    return out


# ======================================================================================
# §A 几何（网格在面板里、不压别的控件、PNG 与 Java 常量逐格对齐、renderBg 一次 blit）
# ======================================================================================

def section_geometry(s):
    out = []
    menu = s["menu_code"]
    screen = s["screen_code"]
    c = s["constants"]

    # ---------- A1 常量字面量 ----------
    for name, want, why in (("GRID_COLS", 9, "9 列 = RS 终端 / 原版背包的列数"),
                            ("GRID_ROWS", 7, "7 行 = 名字行下沿到背包标签之间的 126px"),
                            ("CELL", 18, "MC 标准槽位 18px"),
                            ("GRID_X0", 9, "与背包列左对齐"),
                            ("GRID_Y0", 38, "名字行（18…34）之下"),
                            ("PANEL_W", 236, "面板宽（PNG 同尺寸）"),
                            ("PANEL_H", 252, "面板高（PNG 同尺寸）")):
        got = const_int(menu, name)
        add(out, "§A %s = %d（%s）" % (name, want, why), got == want,
            "契约 §3.B.1：期望 `%s = %d`；实际 %s" % (name, want, got))

    add(out, "§A GRID_W = GRID_COLS * CELL（宽度由列数推导，不写第二个数字）",
        re.search(r"GRID_W\s*=\s*GRID_COLS\s*\*\s*CELL\s*;", menu) is not None,
        "期望 `GRID_W = GRID_COLS * CELL;`；实际 %r" % const_expr(menu, "GRID_W"))
    add(out, "§A GRID_H = GRID_ROWS * CELL（高度由行数推导）",
        re.search(r"GRID_H\s*=\s*GRID_ROWS\s*\*\s*CELL\s*;", menu) is not None,
        "期望 `GRID_H = GRID_ROWS * CELL;`；实际 %r" % const_expr(menu, "GRID_H"))
    add(out, "§A WINDOW_SIZE = GRID_COLS * GRID_ROWS（一屏 = 63 格）",
        re.search(r"WINDOW_SIZE\s*=\s*GRID_COLS\s*\*\s*GRID_ROWS\s*;", menu) is not None,
        "期望 `WINDOW_SIZE = GRID_COLS * GRID_ROWS;`；实际 %r" % const_expr(menu, "WINDOW_SIZE"))
    add(out, "§A 滚动条贴在网格右侧：SCROLLBAR_X = GRID_X0 + GRID_W + 3 且 SCROLLBAR_Y = GRID_Y0 / "
             "SCROLLBAR_H = GRID_H",
        re.search(r"SCROLLBAR_X\s*=\s*GRID_X0\s*\+\s*GRID_W\s*\+\s*3\s*;", menu) is not None
        and re.search(r"SCROLLBAR_Y\s*=\s*GRID_Y0\s*;", menu) is not None
        and re.search(r"SCROLLBAR_H\s*=\s*GRID_H\s*;", menu) is not None,
        "三条推导式任一缺失：SCROLLBAR_X=%r SCROLLBAR_Y=%r SCROLLBAR_H=%r"
        % (const_expr(menu, "SCROLLBAR_X"), const_expr(menu, "SCROLLBAR_Y"),
           const_expr(menu, "SCROLLBAR_H")))

    # ---------- A2 算术：网格不压任何东西 ----------
    grid_x1 = c.get("GRID_X0", 0) + (c.get("GRID_COLS", 0) * c.get("CELL", 0))
    grid_y1 = c.get("GRID_Y0", 0) + (c.get("GRID_ROWS", 0) * c.get("CELL", 0))
    scroll_x = c.get("SCROLLBAR_X", 0)
    scroll_w = const_int(menu, "SCROLLBAR_W") or 12
    upgrade_x = c.get("UPGRADE_X", 0)
    inv_y = c.get("INV_Y0", 0)
    add(out, "§A 网格右缘 %d ≤ 滚动条左缘 %d（不压滚动条）" % (grid_x1, scroll_x),
        grid_x1 <= scroll_x, "网格 %d…%d / 滚动条 %d…%d"
        % (c.get("GRID_X0", 0), grid_x1, scroll_x, scroll_x + scroll_w))
    add(out, "§A 滚动条右缘 %d ≤ 升级槽框左沿 %d（不压升级槽）" % (scroll_x + scroll_w, upgrade_x - 1),
        scroll_x + scroll_w <= upgrade_x - 1,
        "滚动条 %d…%d / 升级槽物品坐标 %d（槽框左沿 %d）"
        % (scroll_x, scroll_x + scroll_w, upgrade_x, upgrade_x - 1))
    add(out, "§A 网格下缘 %d ≤ 背包标签上沿 %d（不压「物品栏」字样）" % (grid_y1, inv_y - 10),
        grid_y1 <= inv_y - 10, "网格 %d…%d / 背包槽 %d（标签通常在其上 10px）"
        % (c.get("GRID_Y0", 0), grid_y1, inv_y))

    label_y = None
    match = re.search(r"inventoryLabelY\s*=\s*(\d+)\s*;", screen)
    if match:
        label_y = int(match.group(1))
    add(out, "§A 界面把背包标签放在 %s（= 网格下缘 %d 之下）" % (label_y, grid_y1),
        label_y is not None and label_y >= grid_y1,
        "inventoryLabelY = %s；网格下缘 %d" % (label_y, grid_y1))

    box_y = c.get("BOX_Y")
    box_h = c.get("BOX_H")
    box_x = c.get("BOX_X")
    box_w = c.get("BOX_W")
    ok_x = c.get("OK_X")
    ok_w = c.get("OK_W")
    add(out, "§A 名字输入行在网格之上：BOX_Y(%s) + BOX_H(%s) ≤ GRID_Y0(%s)"
        % (box_y, box_h, c.get("GRID_Y0")),
        box_y is not None and box_h is not None and box_y + box_h <= c.get("GRID_Y0", 0),
        "名字行 %s…%s / 网格顶 %s" % (box_y, (box_y or 0) + (box_h or 0), c.get("GRID_Y0")))
    add(out, "§A 「确定」按钮在输入框同一行、且在面板内：OK_X = BOX_X + BOX_W + 4（%s），"
             "OK_X + OK_W ≤ PANEL_W − 4" % ok_x,
        None not in (ok_x, ok_w, box_x, box_w)
        and ok_x == box_x + box_w + 4 and ok_x + ok_w <= c.get("PANEL_W", 0) - 4,
        "BOX %s+%s / OK_X=%s OK_W=%s PANEL_W=%s"
        % (box_x, box_w, ok_x, ok_w, c.get("PANEL_W")))

    # ---------- A3 PNG：槽框烘焙 + 与 Java 常量逐格对齐 ----------
    png = s.get("png") or {}
    expected = len(expected_slot_centers(c))
    add(out, "§A 背景 PNG 尺寸 = 面板尺寸（%s×%s）"
        % (c.get("PANEL_W"), c.get("PANEL_H")),
        png.get("size") == (c.get("PANEL_W"), c.get("PANEL_H")),
        "PNG 尺寸 = %s（错误：%s）" % (png.get("size"), png.get("error")))
    slot_ok = png.get("slot_ok") or {}
    bad_slots = [point for point, ok in slot_ok.items() if not ok]
    add(out, "§A PNG 里 %d 个槽框全部已烘焙且与 Java 坐标逐格对齐（槽心 = Menu + 7 = 槽位底色 139,139,139）"
        % expected,
        len(slot_ok) == expected and not bad_slots,
        "应为 %d 个；实际采样 %d 个；对不上 %d 个，例：%s"
        % (expected, len(slot_ok), len(bad_slots), bad_slots[:5]))
    grid_cells = (c.get("GRID_COLS", 0) * c.get("GRID_ROWS", 0))
    cell = c.get("CELL", 18)
    grid_centers = [(c.get("GRID_X0", 0) + col * cell + 7, c.get("GRID_Y0", 0) + row * cell + 7)
                    for row in range(c.get("GRID_ROWS", 0)) for col in range(c.get("GRID_COLS", 0))]
    grid_ok = [point for point in grid_centers if slot_ok.get(point)]
    add(out, "§A 网格那 %d 个格子（终端样式）的槽框都在图里" % grid_cells,
        len(grid_ok) == grid_cells,
        "网格槽心对上 %d / %d 个" % (len(grid_ok), grid_cells))

    render_bg = method_body_of(screen, "renderBg") or ""
    add(out, "§A renderBg 只有一次 blit、没有 fill（第 128 轮铁律：槽框烘在图里，不手画面板）",
        render_bg.count("blit(") == 1 and "fill(" not in render_bg,
        "renderBg 里 blit=%d 处、fill=%d 处"
        % (render_bg.count("blit("), render_bg.count("fill(")))
    add(out, "§A 界面不自己画槽框（不出现 McGui.slotFrame / slotFrame）",
        "slotFrame" not in screen, "界面里仍在手画槽框 ⇒ 与 PNG 烘焙重复")

    # ---------- A4 画格子的位置与命中同源 ----------
    labels = method_body_of(screen, "renderLabels") or ""
    render_grid = method_body_of(screen, "renderGrid") or ""
    add(out, "§A 网格画在 GUI 坐标里（renderLabels → renderGrid；图标 +1 居中，槽框在 PNG 的 −1 处）",
        "renderGrid(guiGraphics" in labels and "renderItem(entry.item(), x + 1, y + 1)" in render_grid
        and "renderFluid(guiGraphics, x + 1, y + 1" in render_grid,
        "renderLabels 调 renderGrid=%s；图标 +1=%s"
        % ("renderGrid(guiGraphics" in labels, "x + 1, y + 1" in render_grid))
    cell_at = method_body_of(screen, "cellIndexAt") or ""
    add(out, "§A 命中判定与绘制同源（都用 GRID_X0/GRID_Y0/CELL/GRID_COLS）",
        all(token in cell_at for token in ("GRID_X0", "GRID_Y0", "CELL", "GRID_COLS")),
        "cellIndexAt 方法体里缺坐标常量")
    add(out, "§A 格子下标口径 = 行优先（row * GRID_COLS + col），与服务端切片一致",
        re.search(r"row\s*\*\s*NetworkResourceReceiverMenu\.GRID_COLS\s*\+\s*col", cell_at) is not None
        and re.search(r"from\s*\+\s*WINDOW_SIZE", s["menu_code"]) is not None
        and re.search(r"final int from = Math\.max\(0, firstRow\) \* GRID_COLS;", s["menu_code"])
        is not None,
        "客户端下标式 / 服务端切片起点任一不匹配")
    return out


# ======================================================================================
# §B 交互（滚轮 / 滚动条 / 点击取出 / 没有页码 / 没有状态句 / 没有取全部）
# ======================================================================================

def section_interaction(s):
    out = []
    screen = s["screen_code"]
    menu = s["menu_code"]

    scrolled = method_body_of(screen, "mouseScrolled") or ""
    add(out, "§B 滚轮在网格上 = 按行滚动（requestScroll(firstRow ∓ 1)），其它区域交给原版",
        "isOverGrid(" in scrolled and "requestScroll(firstRow" in scrolled,
        "mouseScrolled 没走网格滚动路径")

    request_scroll = method_body_of(screen, "requestScroll") or ""
    add(out, "§B 滚动请求发 ACTION_PAGE（= 服务端页首行），且夹到 [0, maxFirstRow]",
        "ReceiverCacheActionPacket.page(" in request_scroll
        and "maxFirstRow()" in request_scroll
        and "Math.min(" in request_scroll and "Math.max(" in request_scroll,
        "requestScroll 没发滚动包 / 没夹行号")
    add(out, "§B 滚动条 listener 指向同一个滚动请求（滚轮与拖动同一条路）",
        "scrollbar.setListener(offset -> requestScroll(" in screen,
        "滚动条 listener 没接到 requestScroll")

    refresh = method_body_of(screen, "refreshScrollbar") or ""
    add(out, "§B 滚动条上限 = 总行数 − 可见行数（不足一屏 ⇒ 禁用，不出现「能拖但拖不动」）",
        "setEnabled(" in refresh and "setMaxOffset(max)" in refresh and "setOffset(" in refresh
        and "maxFirstRow()" in refresh,
        "refreshScrollbar 没按 maxFirstRow 设上限 / 没设 offset")
    add(out, "§B 服务端把「页首行」夹到 [0, maxFirstRow]（边界也回快照，避免两端分叉）",
        re.search(r"firstRow\s*=\s*Math\.max\(0, Math\.min\(max, requested\)\)", menu) is not None,
        "applyScrollRequest 没夹行号")

    clicked = method_body_of(screen, "mouseClicked") or ""
    add(out, "§B 点格子 = 取出（左键一组 / Shift 全部 → requestTake(entry, hasShiftDown())）",
        "cellIndexAt(mouseX, mouseY)" in clicked and "requestTake(entries.get(" in clicked
        and "hasShiftDown()" in clicked,
        "mouseClicked 没走格子取出路径")
    add(out, "§B 界面里没有页码箭头 / 上一页下一页（滚轮 + 滚动条就够了）",
        not any(token in screen for token in ("ARROW_PREV", "ARROW_NEXT", "pageArrowAt",
                                              "renderPageHeader", "PAGE_TEXT_X")),
        "仍存在页码箭头相关符号")

    add(out, "§B 格子 tooltip 与角标同源（第 126 轮工具链要求：数量文本只有 amountText 一个工厂）",
        "amountText(entry, false)" in screen and "amountText(entry, true)" in screen
        and screen.count("private static String amountText(") == 1,
        "角标 / tooltip 没有共用同一个文本工厂方法（round126 会把角标判成未覆盖）")

    banned_hits = [token for token in BANNED_SCREEN_SYMBOLS if token in screen]
    add(out, "§B 界面里没有任何旧状态句 / 旧分页机械（%d 个符号全部为 0）"
        % len(BANNED_SCREEN_SYMBOLS),
        not banned_hits, "仍残留：%s" % banned_hits)
    banned_lang = [token for token in BANNED_LANG_LITERALS if token in screen]
    add(out, "§B 界面不再引用旧状态句的语言键（节拍 / 缓存摘要 / 流体摘要 / 已命名 / 取全部 / 页码句）",
        not banned_lang, "仍引用：%s" % banned_lang)

    banned_menu = [token for token in BANNED_MENU_SYMBOLS if token in menu]
    add(out, "§B 菜单里没有「取全部」通道（BUTTON_TAKE_ALL_ITEMS / takeAllCacheItems / clickMenuButton）",
        not banned_menu, "仍残留：%s" % banned_menu)
    window_body = method_body_of(menu, "window") or ""
    add(out, "§B 菜单保留 `window(...)` 一屏切片（起点 = firstRow × 列数，长度 = WINDOW_SIZE）",
        re.search(r"Math\.max\(0, firstRow\)\s*\*\s*GRID_COLS", window_body) is not None
        and "from + WINDOW_SIZE" in window_body,
        "window(...) 缺失或起点/长度不是「页首行 × 列数 / WINDOW_SIZE」")
    return out


# ======================================================================================
# §C 服务端权威与守恒（扣减与给予同一笔账 / 流体顺序 / 无容器不扣）
# ======================================================================================

def section_authority(s):
    out = []
    screen = s["screen_code"]
    menu = s["menu_code"]

    # ---------- C1 守卫 ----------
    handle = method_body_of(menu, "handleCacheAction") or ""
    add(out, "§C C2S 三道守卫：containerId 对上 / 菜单类型是本类 / stillValid（任一不过什么都不写）",
        "open.containerId != packet.containerId()" in handle
        and "instanceof NetworkResourceReceiverMenu menu" in handle
        and "menu.stillValid(player)" in handle,
        "守卫缺失（containerId / instanceof / stillValid 三者缺一）")

    request_take = method_body_of(screen, "requestTake") or ""
    add(out, "§C 客户端只报「取谁 + 要不要全部」：requestTake 里只发 takeItem/takeFluid（不含任何数量参数）",
        "ReceiverCacheActionPacket.takeItem(" in request_take
        and "ReceiverCacheActionPacket.takeFluid(" in request_take
        and "amount()" not in request_take
        and "entry.amount" not in request_take,
        "requestTake 里出现了数量（客户端不能决定数量）")

    # ---------- C2 物品：扣减 → 给予 → 回填 ----------
    take_item = method_body_of(menu, "takeItem") or ""
    positions = []
    for token in ("cache.extractItem(", "player.getInventory().add(",
                  "cache.insertItem(", "player.drop("):
        index = take_item.find(token)
        positions.append((token, index))
    add(out, "§C 物品取出四步顺序：真扣减 → 塞背包 → 放不下的回填缓存 → 极端兜底掉落（绝不销毁）",
        all(index >= 0 for _token, index in positions)
        and positions[0][1] < positions[1][1] < positions[2][1] < positions[3][1],
        "四步位置：%s" % positions)
    add(out, "§C 物品扣减走 `extractItem(..., false)`（EXECUTE，真扣）而不是只读镜像",
        re.search(r"extractItem\(slot, Math\.min\(remaining, each\.getCount\(\)\), false\)", take_item)
        is not None,
        "takeItem 里的 extractItem 不是真扣（第 3 参必须是 false）")

    # ---------- C3 流体：容器来源顺序 ----------
    take_fluid = method_body_of(menu, "takeFluid") or ""
    order = []
    for label, token in FLUID_ORDER_ANCHORS:
        order.append((label, take_fluid.find(token)))
    add(out, "§C 流体容器来源顺序 = 光标 → 背包 → 网络 → 都没有（RS 2.0.9 :55/:57/:59/:62 的真实顺序）",
        all(index >= 0 for _label, index in order)
        and all(order[i][1] < order[i + 1][1] for i in range(len(order) - 1)),
        "四个锚点位置：%s" % order)
    add(out, "§C 「都没有」分支在**任何 drain 之前**返回（一个 mB 都不扣）",
        "drainOneBucket(" not in take_fluid and "drain(" not in take_fluid,
        "takeFluid 方法体里出现了 drain ⇒ 可能在判断容器之前就扣了流体")
    add(out, "§C 网络桶先 SIMULATE 确认存在再真扣（networkBucketCount 用 Action.SIMULATE）",
        re.search(r"storage\.extract\(BUCKET_ITEM_RESOURCE, 64L, Action\.SIMULATE, Actor\.EMPTY\)",
                  s["menu_code"]) is not None,
        "networkBucketCount 没有用 Action.SIMULATE 先确认")

    # ---------- C4 流体：真扣 + 回滚 ----------
    drain_body = method_body_of(menu, "drainOneBucket") or ""
    add(out, "§C 流体真扣减只发生在 drainOneBucket（恰好一桶，EXECUTE）",
        "receiver.getFluidCache().drain(" in drain_body
        and "IFluidHandler.FluidAction.EXECUTE" in drain_body,
        "drainOneBucket 不是「恰好一桶 + EXECUTE」")
    # 每个「真扣一桶」的调用点都必须在**同一个方法**里带两条回滚分支：
    # ① 扣到的不足一桶（缓存被同时取空）；② 拿不到容器（网络桶被别处取走 / 背包桶消失）。
    # 只统计「全文件回滚次数」会被「删掉一条分支还剩别的分支」骗过去（反例 C-11 就抓这个）。
    drain_helpers = [item for item in java_methods(menu)
                     if item[0] != "drainOneBucket" and "drainOneBucket(" in item[3]]
    rollback_per_method = dict((item[0], (item[3].count("drainOneBucket("),
                                         item[3].count("refillFluidCache(")))
                               for item in drain_helpers)
    add(out, "§C 每条流体扣减路径失败都回滚（每个 drain 调用点所在方法里都有 ≥2 条 refill 分支）",
        len(drain_helpers) >= 2
        and all(drains >= 1 and refills >= 2
                for drains, refills in rollback_per_method.values()),
        "各方法 (drain 次数, refill 次数)：%s" % rollback_per_method)
    add(out, "§C 网络桶从 RS 网络物品存储真取（ItemResource(Items.BUCKET) + Action.EXECUTE + Actor.EMPTY）",
        "BUCKET_ITEM_RESOURCE" in s["menu_code"]
        and re.search(r"storage\.extract\(BUCKET_ITEM_RESOURCE, 1L, Action\.EXECUTE, Actor\.EMPTY\)",
                      s["menu_code"]) is not None,
        "网络桶提取没走 RS 网络存储（或没真取）")
    add(out, "§C 源码写明顺序出处（RS 类名 + :55/:57/:59 行号 + 「背包优先」的口径）",
        "FluidGridExtractionStrategy" in s["menu"]
        and ":55" in s["menu"] and ":57" in s["menu"] and ":59" in s["menu"]
        and "背包优先" in s["menu"],
        "注释里没有 RS 取证说明（顺序判据就变成了无出处的约定）")
    return out


# ======================================================================================
# §D 协议与回归（线格式没改 / 升级槽与名字链路保留 / 数据源没变）
# ======================================================================================

def section_regression(s):
    out = []
    screen = s["screen_code"]
    menu = s["menu_code"]
    packet = s["packet_code"]
    sync = s["sync_code"]

    components = re.search(r"public record ReceiverCacheActionPacket\(([^)]*)\)", packet)
    add(out, "§D 取物包 record 仍是 6 分量（containerId/action/page/item/fluid/all）",
        components is not None and len(components.group(1).split(",")) == 6,
        "实际 = %s" % (components.group(1) if components else "（没找到 record 头）"))
    for name, value in (("ACTION_TAKE_ITEM", 0), ("ACTION_TAKE_FLUID", 1), ("ACTION_PAGE", 2)):
        add(out, "§D %s = %d（线格式不变）" % (name, value),
            re.search(r"%s\s*=\s*%d\s*;" % (name, value), packet) is not None,
            "常量被改 / 缺失")
    add(out, "§D 取物包的 handle 只转交菜单（包本身不写缓存）",
        "NetworkResourceReceiverMenu.handleCacheAction(" in packet
        and "getItemCache()" not in packet and "getFluidCache()" not in packet,
        "包处理体里出现了缓存写操作")

    add(out, "§D 同步包没被本轮改动（仍 13 分量；page/pageCount 语义改由接收器侧解释）",
        re.search(r"public record SyncReceiverConfigPacket\(([^)]*)\)", sync) is not None
        and len(re.search(r"public record SyncReceiverConfigPacket\(([^)]*)\)",
                          sync).group(1).split(",")) == 13
        and "GRID_" not in sync and "WINDOW_SIZE" not in sync,
        "同步包被改了（线格式或语义常量混进来了）")

    used_packet_fields = [field for field in ("itemTypes()", "itemTotal()", "fluidTypes()",
                                              "fluidTotal()", "facing()")
                          if field in screen]
    add(out, "§D 界面不再读「种类 / 件数 / 流体总量 / 输出面」这些被删掉的状态句数据",
        not used_packet_fields, "仍在读：%s" % used_packet_fields)

    add(out, "§D 升级槽保留：白名单 = 速度 + 叠加，6 槽，槽位坐标与 PNG 同源",
        "ALLOWED_UPGRADES = List.of(\"speed_upgrade\", \"stack_upgrade\")" in menu
        and "UPGRADE_COUNT" in menu and "new UpgradeSlot(upgrades, i, UPGRADE_X, UPGRADE_Y0 + i * 18"
        in menu,
        "升级槽通道被改坏 / 删掉了")
    add(out, "§D 名字链路保留（服务端权威）：applyNameFromUi + isNameTakenInNetwork + 形状判据同源",
        "applyNameFromUi(" in menu and "receiver.isNameTakenInNetwork(name)" in menu
        and "checkNameShape(" in menu and "checkNameShape(" in screen,
        "名字链路缺失或客户端不再用同一段形状判据")
    add(out, "§D 界面改名仍发 SetReceiverNamePacket（客户端不直接写名字）",
        "new SetReceiverNamePacket(menu.containerId, nameBox.getValue())" in screen,
        "改名的 C2S 出口被改了")

    add(out, "§D 网格数据源仍是方块实体的 cacheEntries()（物品 + 流体聚合，服务端权威）",
        "receiver.cacheEntries()" in menu
        and method_body_of(s["blockentity_code"], "cacheEntries") is not None
        and "return FluidResource" in s["blockentity"] or "FluidResource" in s["blockentity"],
        "缓存快照来源不在 cacheEntries()")
    add(out, "§D 流体格子取图标/身份仍走 FluidStack（不是把流体硬塞成 ItemStack）",
        "GhostMarkerRenderer.renderFluid(" in screen and "entry.fluid()" in screen,
        "流体格子没有按流体渲染")

    add(out, "§D 名字反馈仍在（改名结果 / 形状提示 / 已占用集合走动作条 + 输入框 tooltip）",
        "displayClientMessage(" in screen and "renderNameBoxTooltip(" in screen
        and "name.hint." in screen and "name.occupied" in screen,
        "名字反馈被删掉了（用户要求删的是废话，不是反馈）")
    return out


# ======================================================================================
# §E 语言键
# ======================================================================================

def section_lang(s):
    out = []
    zh = s["lang_zh"] if isinstance(s["lang_zh"], dict) else {}
    en = s["lang_en"] if isinstance(s["lang_en"], dict) else {}

    missing = [(key, ("zh" if key not in zh or not str(zh.get(key, "")).strip()
                      else ("en" if key not in en or not str(en.get(key, "")).strip() else "")))
               for key in NEW_LANG_KEYS]
    missing = [(key, where) for key, where in missing if where]
    add(out, "§E 本轮新键两边都有且非空（Lead 落盘）：%s" % ", ".join(NEW_LANG_KEYS),
        not missing, "缺 / 空：%s" % missing)

    screen = s["screen_code"]
    prefix = "gui.rs_create_compat.network_resource_receiver."
    used = [key for key in NEW_LANG_KEYS
            if ('"' + key[len(prefix):] + '"') in screen]
    add(out, "§E 新键真的被界面用上（不是孤儿键）", len(used) == len(NEW_LANG_KEYS),
        "界面引用 %d/%d" % (len(used), len(NEW_LANG_KEYS)))

    # 既有键不许被顺手删掉（Lead 删键时必须知道这些还在用）
    kept = ("gui.rs_create_compat.network_resource_receiver.name.label",
            "gui.rs_create_compat.network_resource_receiver.name.button",
            "gui.rs_create_compat.network_resource_receiver.upgrades",
            "gui.rs_create_compat.network_resource_receiver.cache.slot.hint",
            "gui.rs_create_compat.network_resource_receiver.msg.name_applied",
            "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_container")
    gone = [key for key in kept if key not in zh or key not in en]
    add(out, "§E 界面仍在用的既有键两边都还在（%d 个）" % len(kept), not gone,
        "被删了：%s" % gone)
    return out


# ======================================================================================
# 全部断言
# ======================================================================================

SECTIONS = (
    ("§0 文件", section_files),
    ("§A 几何 / PNG", section_geometry),
    ("§B 交互 / 无废话", section_interaction),
    ("§C 服务端权威 / 守恒", section_authority),
    ("§D 协议 / 回归", section_regression),
    ("§E 语言键", section_lang),
)


def run_all(raw):
    snap = prepare(raw)
    out = []
    for _label, section in SECTIONS:
        out.extend(section(snap))
    return out


def failures_of(raw):
    return [name for name, ok, _detail in run_all(raw) if not ok]


# ======================================================================================
# §9 反例自证（内存改字符串 / 改像素映射；不写任何文件）
# ======================================================================================

class AnchorMissing(Exception):
    """真源码里找不到锚点 ⇒ 该反例改用「纯字符串构造」的样本，保证反例永远有区分力。"""


def copy_with(raw, **overrides):
    out = dict(raw)
    out.update(overrides)
    return out


def repl(raw, key, old, new):
    text = raw.get(key) or ""
    if not isinstance(text, str) or old not in text:
        raise AnchorMissing("%s 里找不到锚点 %r" % (key, old[:60]))
    return copy_with(raw, **{key: text.replace(old, new, 1)})


def repl_re(raw, key, pattern, new):
    text = raw.get(key) or ""
    if not isinstance(text, str):
        raise AnchorMissing("%s 不是文本" % key)
    broken, hits = re.subn(pattern, new, text, count=1)
    if hits == 0:
        raise AnchorMissing("%s 里找不到锚点 /%s/" % (key, pattern))
    return copy_with(raw, **{key: broken})


def method_replace(raw, key, method_name, new_text):
    text = raw.get(key) or ""
    code = strip_comments(text)
    item = None
    for candidate in java_methods(code):
        if candidate[0] == method_name:
            item = candidate
            break
    if item is None:
        raise AnchorMissing("%s 里没有方法 %s()" % (key, method_name))
    snippet = code[item[1]:item[2]]
    if snippet in text:
        return copy_with(raw, **{key: text.replace(snippet, new_text, 1)})
    return copy_with(raw, **{key: code[:item[1]] + new_text + code[item[2]:]})


def counterexample_specs(raw):
    specs = []

    # ---- 空树 ----
    def empty_tree():
        return copy_with(raw, screen="", menu="", packet="", sync="", blockentity="",
                         lang_zh={}, lang_en={})

    specs.append(("C-01 空树（所有文件置空）⇒ 必须成片判负，不许「什么都没检查」也绿",
                  ("§0 ", "§A ", "§B ", "§C ", "§D ", "§E "), empty_tree, None))

    # ---- 旧状态句复活 ----
    def old_status_lines():
        return repl_re(raw, "screen", r"(\n    /\*\* 数量角标（右下角)",
                       "\n    private Component throttleLine() {\n"
                       "        return Component.translatable(LANG + \"throttle\");\n"
                       "    }\n\n    /** 数量角标（右下角)", )

    specs.append(("C-02 把「节拍」状态句拼回界面（用户说的废话）", ("§B 界面里没有任何旧状态句",),
                  old_status_lines,
                  lambda: copy_with(raw, screen=(raw.get("screen") or "")
                                    + "\n    private Component throttleLine() { return null; }\n"
                                    + "    private String x = \"throttle\";\n")))

    # ---- 取全部按钮复活 ----
    def take_all_button():
        broken = repl(raw, "menu", "public static final int MAX_NAME_LENGTH",
                      "public static final int BUTTON_TAKE_ALL_ITEMS = 0;\n    public static final int MAX_NAME_LENGTH")
        return repl(broken, "screen", "    private void requestTake(",
                    "    private void takeAllButton() { LANG.toString(); }\n\n    private void requestTake(")

    specs.append(("C-03 把「取全部」按钮通道拼回（契约 §3.B.1 明令删除）",
                  ("§B 菜单里没有「取全部」通道",), take_all_button,
                  lambda: copy_with(raw,
                                    menu=(raw.get("menu") or "")
                                    + "\n// BUTTON_TAKE_ALL_ITEMS takeAllCacheItems clickMenuButton\n")))

    # ---- 页码箭头复活 ----
    def page_arrows():
        return repl_re(raw, "screen", r"(\n    private void renderGrid\()",
                       "\n    private static final int ARROW_PREV_X = 9;\n"
                       "    private void renderPageHeader() { }\n\n    private void renderGrid(")

    specs.append(("C-04 把页码箭头（上一页/下一页）拼回（用户要的是滚动）", ("§B 界面里没有页码箭头",),
                  page_arrows,
                  lambda: copy_with(raw, screen=(raw.get("screen") or "")
                                    + "\n// ARROW_PREV ARROW_NEXT pageArrowAt renderPageHeader\n")))

    # ---- 点了不扣（只给不扣） ----
    def take_without_deduct():
        return repl(raw, "menu",
                    "final ItemStack got = cache.extractItem(slot, Math.min(remaining, each.getCount()), false);",
                    "final ItemStack got = each.copyWithCount(Math.min(remaining, each.getCount()));")

    specs.append(("C-05 取物品时只复制不发扣减（复制物品事故）", ("§C 物品扣减走",), take_without_deduct,
                  lambda: copy_with(raw, menu=(
                      "private void takeItem(final Player player) {\n"
                      "        final ItemStack got = cache.getStackInSlot(0);\n"
                      "        player.getInventory().add(got);\n    }\n"))))

    # ---- 扣了不给（不回填） ----
    def no_refill_item():
        return repl(raw, "menu", "final ItemStack refused = cache.insertItem(0, rest, false);",
                    "final ItemStack refused = ItemStack.EMPTY;")

    specs.append(("C-06 物品放不下时不回填缓存（等于销毁）", ("§C 物品取出四步顺序",), no_refill_item,
                  lambda: copy_with(raw, menu=(
                      "private void takeItem(final Player player) {\n"
                      "        cache.extractItem(0, 64, false);\n"
                      "        player.getInventory().add(ItemStack.EMPTY);\n    }\n"))))

    # ---- 无容器先扣 ----
    def drain_before_container_check():
        return repl(raw, "menu", "    private void takeFluid(final Player player, final FluidStack identity, final boolean all) {",
                    "    private void takeFluid(final Player player, final FluidStack identity, final boolean all) {\n"
                    "        drainOneBucket(identity);")

    specs.append(("C-07 先扣流体再找容器（都没有时也扣了 ⇒ 用户说的「取不出来也不能扣」被破坏）",
                  ("§C 「都没有」分支在",), drain_before_container_check,
                  lambda: copy_with(raw, menu=(
                      "private void takeFluid(final Player player, final FluidStack identity, final boolean all) {\n"
                      "        drainOneBucket(identity);\n"
                      "        fillCarriedContainer(player, identity);\n"
                      "        networkBucketCount();\n"
                      "        countInventoryBuckets(player);\n"
                      "        MSG_FLUID_NO_CONTAINER.toString();\n    }\n"))))

    # ---- 顺序颠倒（网络优先：本轮被驳回的旧写法） ----
    def wrong_order():
        return repl(raw, "menu",
                    "        final int inventoryBuckets = countInventoryBuckets(player);",
                    "        final int networkFirst = networkBucketCount();\n"
                    "        final int inventoryBuckets = countInventoryBuckets(player);")

    specs.append(("C-08 把「先网络再背包」的旧顺序拼回（RS 真实顺序是背包优先：:57 在 :59 之前）",
                  ("§C 流体容器来源顺序",), wrong_order,
                  lambda: copy_with(raw, menu=(
                      "private void takeFluid(final Player player, final FluidStack identity, final boolean all) {\n"
                      "        fillCarriedContainer(player, identity);\n"
                      "        networkBucketCount();\n"
                      "        countInventoryBuckets(player);\n"
                      "        MSG_FLUID_NO_CONTAINER.toString();\n    }\n"))))

    # ---- 客户端上报数量 ----
    def client_reports_amount():
        return repl(raw, "screen",
                    "            : ReceiverCacheActionPacket.takeItem(menu.containerId, entry.item(), all));",
                    "            : ReceiverCacheActionPacket.takeItem(menu.containerId, entry.item(), all);\n"
                    "        final long ignored = entry.amount();")

    specs.append(("C-09 客户端把数量也报给服务端（数量必须由服务端算）",
                  ("§C 客户端只报",), client_reports_amount,
                  lambda: copy_with(raw, screen=(
                      "private void requestTake(final ReceiverCacheEntry entry, final boolean all) {\n"
                      "        final long amount = entry.amount();\n"
                      "        ReceiverCacheActionPacket.takeItem(menu.containerId, entry.item(), all);\n"
                      "        ReceiverCacheActionPacket.takeFluid(menu.containerId, entry.fluid(), all);\n"
                      "    }\n"))))

    # ---- 网络桶不先模拟 ----
    def no_simulate():
        return repl(raw, "menu",
                    "storage.extract(BUCKET_ITEM_RESOURCE, 64L, Action.SIMULATE, Actor.EMPTY)",
                    "storage.extract(BUCKET_ITEM_RESOURCE, 64L, Action.EXECUTE, Actor.EMPTY)")

    specs.append(("C-10 网络桶不先 SIMULATE 就真取（可能扣了流体却没桶）",
                  ("§C 网络桶先 SIMULATE",), no_simulate,
                  lambda: copy_with(raw, menu=(
                      "private int networkBucketCount() {\n"
                      "        return (int) storage.extract(BUCKET_ITEM_RESOURCE, 64L, Action.EXECUTE, Actor.EMPTY);\n"
                      "    }\n"))))

    # ---- 回滚缺失 ----
    def no_rollback():
        return repl_re(raw, "menu", r"refillFluidCache\(drained\);", ";")

    specs.append(("C-11 流体真扣后失败不回滚（「扣了没给」）", ("§C 每条流体扣减路径失败都回滚",),
                  no_rollback,
                  lambda: copy_with(raw, menu=(
                      "drainOneBucket(identity);\n        drainOneBucket(identity);\n"
                      "        // 没有任何 refill\n"))))

    # ---- PNG 缺网格槽框 ----
    def png_missing_grid():
        png = dict(raw.get("png") or {})
        slot_ok = dict(png.get("slot_ok") or {})
        c = raw.get("constants") or {}
        grid = [(c.get("GRID_X0", 9) + col * 18 + 7, c.get("GRID_Y0", 38) + row * 18 + 7)
                for row in range(c.get("GRID_ROWS", 7)) for col in range(c.get("GRID_COLS", 9))]
        for point in grid:
            if point in slot_ok:
                slot_ok[point] = False
        png["slot_ok"] = slot_ok
        return copy_with(raw, png=png, constants=c, _png_sampled=True)

    specs.append(("C-12 背景图没有重生成（网格那 63 格槽框不在图里）",
                  ("§A PNG 里", "§A 网格那"), png_missing_grid,
                  lambda: copy_with(raw, png={"size": (236, 252), "slot_ok": {}, "control_ok": {},
                                               "error": None}, _png_sampled=True)))
    return specs


def run_counterexamples(raw, base_bad):
    print("\n=== §9 反例自证（内存改字符串 / 改像素映射；判据必须判负，不写文件） ===")
    for label, targets, builder, fallback in counterexample_specs(raw):
        COUNTER_TOTAL[0] += 1
        try:
            broken = builder()
            mode = ""
        except AnchorMissing as exc:
            if fallback is None:
                print("FAIL 反例 %s（构造失败：%s）" % (label, exc))
                continue
            broken = fallback()
            mode = "，合成样本"
        fresh = [name for name in failures_of(broken) if name not in base_bad]
        hit = [name for name in fresh if any(name.startswith(prefix) for prefix in targets)]
        if hit:
            COUNTER_PASS[0] += 1
            print("PASS 反例 %s%s ⇒ 新增判负 %d 项（例：%s）" % (label, mode, len(fresh), hit[0]))
            continue
        base_hit = [name for name in base_bad if any(name.startswith(prefix) for prefix in targets)]
        if base_hit:
            COUNTER_PASS[0] += 1
            print("PASS 反例 %s%s（基线该判据本就红：%s，天然证明有区分力）"
                  % (label, mode, base_hit[0]))
            continue
        print("FAIL 反例 %s%s（改了却没多出目标判负；新增失败：%s）" % (label, mode, fresh[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 131 轮 B 组自检：网络资源接收器 = 取物终端（网格 + 按行滚动 + 点一下取出）")
    print("规格：%s §3.B" % CONTRACT)
    print("源码根：%s" % JAVA_REL.replace(os.sep, "/"))

    missing = [rel(path) for key, path in FILES.items() if not os.path.isfile(path)]
    if missing:
        print("\n[注意] 以下文件还不存在：")
        for item in missing:
            print("  - %s" % item)

    raw = load_snapshot()
    results = run_all(raw)
    for name, ok, detail in results:
        if ok:
            print("PASS " + name)
        else:
            print("FAIL " + name + (("  <- " + detail) if detail else ""))

    base_bad = set(name for name, ok, _detail in results if not ok)
    print("\n检查项：%d，失败：%d" % (len(results), len(base_bad)))
    if base_bad:
        print("（下面按 § 编号列失败项）")
        for name in sorted(base_bad):
            print("  FAIL " + name)

    run_counterexamples(raw, base_bad)

    print("\n检查项：%d，反例：%d/%d，失败：%d"
          % (len(results), COUNTER_PASS[0], COUNTER_TOTAL[0], len(base_bad)))
    if base_bad or COUNTER_PASS[0] != COUNTER_TOTAL[0]:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
