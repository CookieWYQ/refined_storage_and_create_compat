#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 130 轮契约自检：区域条去掉「标记网格」+ 搜索 / 删除确认 / 世界框重新武装 / 字体与废话收敛。

规格来源
--------
`docs/ROUND130_AREA_STRIP_REWORK.md`（唯一权威）。本脚本逐条落地它 §7 的九条验收，
失败信息里写「契约第几节 / 期望 / 实际」，名字统一以 `§N` 开头，便于按 §7 编号汇报。

本脚本是**规格判定程序**，不是实现
---------------------------------
* 只读 `src/**`（一个字节都不写），写作用域只有 `tools/`；
* 纯 stdlib python3，从**真源码**解析，不 import 游戏代码、不跑 gradlew / javac；
* 定位方法 / 类 / 字段一律用「正则找头 + 括号配对」，**不写死行号**（实现方随时改行号都不影响）；
* 实现在另一条线上并行写，所以**现在跑红是正常的**：断言失败 = 规格尚未落地，脚本本身没坏。

覆盖（对应 §7 九条）
--------------------
§1 几何：`W=176` / `MIN_W=140` / `HARD_MIN_W=108` / `GAP` / `SCREEN_MARGIN` / `ROW_H` / `PAD` 字面量；
  内部几何 `TITLE_H / SEARCH_H / SEARCH_GAP / FOOTER_H / FOOTER_GAP`；`rowsAreaTop()` 的公式里**必须**
  出现 `SEARCH_H`（搜索框占位，否则行列表会压住搜索框）；搜索框的 `new EditBox(..., SEARCH_H, ...)`；
  以及**用解析出来的常量算总账**：面板高 `BG_H-8` 扣掉底部 `PAD+FOOTER_H` 与顶部
  `PAD+TITLE_H+2+SEARCH_H+SEARCH_GAP` 后，行区高度必须 ≥ 6×`ROW_H`（不然搜索框一加就没地方放行）。
§2 标记网格彻底删除：`AreaStripPanel` 里
  `markGrid|markCells|openMarkGrid|acceptGhost|isMarkGridOpen|markCellAt|turnMarkPage` 出现次数 = 0
  （**按原文统计，javadoc / 注释里也不许留**——留一句就是留着一条复活路径）；
  `MARK_*` 常量一个不剩；`CollectionCacheJeiPlugin` 里 `AreaStripPanel` = 0 次（类型名连 import 都不许）；
  `AdvancedCollectionCacheScreen` 里 `closeMarkGrid / isMarkGridOpen` = 0 次（§3.4 删掉那行转发）；
  同时**防删过头**：JEI 的 48 格通道（`SetCollectionMarkerConfigPacket`）与避让通道
  （`getGuiExtraAreas` → `AreaStripBounds.get()`）必须都还在。
§3 搜索：`searchBox` 字段（原版 `EditBox`）+ 一个 token 过滤方法（优先认 `matchesQuery`，名字不同时
  在「含 search/match 且含 query/filter/token」的方法里找）+ 过滤真的接在「建行」路径上
  （`rebuildRows` 类方法里调用它）+ 过滤变化时 `scrollRows` 归零 + hint 用 `area_strip.search.hint`
  + `mouseClicked / keyPressed / charTyped` 三个方法体里都有 `searchBox` + Esc 清空搜索（`setValue("")`）。
§4 删除二次确认：全文件 `ACTION_DELETE` 恰好 1 处，且**它所在的那个方法体**里有 armed 判据
  （`deleteArmedRaw` / `armed`，大小写不敏感——`isArmed()` 也算）；`expireArming()` 仍把 armed 归零；
  武装入口还在；`CONFIRM_MS` = 3000；外加 §4.1/§4.2 的两个钩子（`matchRegionLabel()` /
  `drawMatchAreaHint(...)`）与高级版覆写（`match_label` / `match_label.none` / `pick_hint`）。
§5 世界框：`FOCUS_SLOT` 是 `static final` 且值为 `AreaOutline.slot("strip/focus")`；
  全文件 `AreaOutline.slot("strip/focus")` **只出现 1 次**（不再每帧新建 String）；
  `feedWorldOutline()` 用 `FOCUS_SLOT` + `chase`；`removed()` 里出现 `showForView` + `FOCUS_SLOT` + `VIEW_TTL`
  （关界面后按 5 秒重新武装 —— 用户「显示边框还是没用」的正解）；每行 ◎ 走独立 `strip/peek/N` slot。
§6 字体 / 废话：`AreaStripPanel` 里不许出现旧括号坐标拼接 `"(" +` 或 `")→("`；
  坐标紧凑串由 `minX()…maxZ()` 六个 getter 自己拼（不再用带空格的 `describeMin/describeMax`）；
  所有 `RsccTextFit.drawFitLine(...)` 调用都是 7 参且 `maxWidth` 是常量表达式 / 方法调用（不是裸数字）；
  源码字符串里没有省略号。
§7 数据归属：`AdvancedCollectionCacheBlockEntity` 的**实例字段**里，除「冻结集」之外不许出现新的
  Area* / 标记字段（§5：区域几何与标记只存在选定器物品上）；`legacyAreaFilters` 仍在（旧档迁移源不许删）；
  `AreaMarks.put(` 的调用点只允许出现在白名单文件（见 `AREA_MARKS_PUT_WHITELIST` 的注释：§7-7 写了三个，
  树里另有**第 125 轮就存在**的两个标记编辑包，一并登记并逐个打印，任何**新**文件都会判负）；
  客户端（`client/**`）不写 `AreaMarks`。
§8 lang：`zh_cn` 与 `en_us` 键集合完全相同（不等时打印对称差）；§6 的 9 个新键两边都有且非空；
  新键占位符个数中英一致；4 个 glyph 与 §6 表格逐字一致；`gui.rs_create_compat.area_strip.mark.` 前缀
  两边都没有；`area_strip.tip.howto` 两边都没有；`gui.rs_create_compat.area_mark.` 前缀两边都还在
  （那是独立界面 `AreaMarkScreen` 的键，§6 明令**不要删**）；区域条代码引用的 6 个新键在两边都存在。
§9 反例自证：把规格改坏（`W=128`、`isMarkGridOpen` 复活、`ACTION_DELETE` 裸发送、
  `removed()` 只 remove 不 showForView …）后，上面的判据**必须判负**。反例只在内存里改字符串 /
  构造样本，**不写任何文件**；真源码里找不到锚点时退化成「纯字符串构造的样本」，所以反例永远可跑、
  不会因为实现未落地而静默跳过（区分力证明不会被「跳过」吃掉）。

纪律：不跑 gradlew / javac（Lead 统一编译）、不 git、不改 `src/**`、不改 docs / lang。
用法：python tools/selfcheck_round130_area_strip_rework.py
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

CONTRACT = "docs/ROUND130_AREA_STRIP_REWORK.md"

#: 本轮涉及的文件（键名稳定；反例 / 断言都按这个键取内容）。
FILES = {
    "panel": os.path.join(JAVA, "client", "screen", "AreaStripPanel.java"),
    "adv": os.path.join(JAVA, "client", "screen", "AdvancedCollectionCacheScreen.java"),
    "jei": os.path.join(JAVA, "client", "CollectionCacheJeiPlugin.java"),
    "base": os.path.join(JAVA, "client", "screen", "CollectionCacheScreen.java"),
    "blockentity": os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java"),
    "areamarks": os.path.join(JAVA, "support", "AreaMarks.java"),
    "lang_zh": os.path.join(LANG, "zh_cn.json"),
    "lang_en": os.path.join(LANG, "en_us.json"),
}

#: §2 / §7-2：标记网格的整族 token —— 出现次数必须为 0（含 javadoc）。
MARK_TOKENS = ("markGrid", "markCells", "openMarkGrid", "acceptGhost",
               "isMarkGridOpen", "markCellAt", "turnMarkPage")

#: §2 / §7-2 的外层几何字面量（键 = 常量名，值 = 字面量，注释 = 为什么是这个数）。
GEOMETRY_OUTER = (
    ("W", 176, "理想宽；128 宽必然把坐标行缩到 0.6 倍 = 玩家说的「字很小」"),
    ("MIN_W", 140, "屏幕挤的时候的收窄下限"),
    ("HARD_MIN_W", 108, "低于此宽就不画侧栏（宁可没有，也不画半条）"),
    ("GAP", 6, "与 GUI 右缘的间隙"),
    ("SCREEN_MARGIN", 4, "距屏幕右缘至少留这么多"),
    ("ROW_H", 22, "行高（两行：名字 + 坐标）"),
    ("PAD", 4, "侧栏内边距"),
)

#: §2 的内部纵向几何（Lead 实现，自检可断言）。
GEOMETRY_INNER = (
    ("TITLE_H", 12, "标题行高"),
    ("SEARCH_H", 12, "搜索框（EditBox）高"),
    ("SEARCH_GAP", 3, "标题下沿 → 搜索框 / 搜索框 → 行列表的缝"),
    ("FOOTER_H", 16, "底部按钮行高"),
    ("FOOTER_GAP", 3, "按钮之间的缝"),
)

#: §6 的 9 个新语言键（两边都必须有且非空）。
NEW_LANG_KEYS = (
    "gui.rs_create_compat.area_strip.search.hint",
    "gui.rs_create_compat.area_strip.peer.glyph",
    "gui.rs_create_compat.area_strip.confirm.glyph",
    "gui.rs_create_compat.area_strip.cancel.glyph",
    "gui.rs_create_compat.area_strip.title.filtered",
    "gui.rs_create_compat.area_strip.delete.confirm.this",
    "gui.rs_create_compat.collection_cache.match_area.pick_hint",
    "gui.rs_create_compat.advanced_collection_cache.match_label",
    "gui.rs_create_compat.advanced_collection_cache.match_label.none",
)

#: §6 表格里三个 glyph 的逐字值（中英一致；改字形 = 改契约）。
#: 注意：既有键 `area_strip.delete.glyph`（✕ / en 的 "X"）是第 125 轮就有的，§6 表格没重定义它，
#: 所以不在这里断言 —— 本轮只认 §6 表格新增的 ◎ / ✓ / ✗。
NEW_LANG_GLYPHS = {
    "gui.rs_create_compat.area_strip.peer.glyph": "\u25ce",      # ◎
    "gui.rs_create_compat.area_strip.confirm.glyph": "\u2713",   # ✓
    "gui.rs_create_compat.area_strip.cancel.glyph": "\u2717",    # ✗
}

#: §6 删除的键族 / 单键（两边都不许留）。
DELETED_LANG_PREFIX = "gui.rs_create_compat.area_strip.mark."
DELETED_LANG_KEYS = ("gui.rs_create_compat.area_strip.tip.howto",)

#: §6 明令**不要删**的键族（独立界面 `AreaMarkScreen` 用）。
KEPT_LANG_PREFIX = "gui.rs_create_compat.area_mark."

#: 区域条代码里引用的新键（`LANG + "xxx"` 的 xxx；代码引用了就必须两边都有，否则界面上是裸键名）。
PANEL_NEW_KEY_SUFFIXES = (
    "search.hint",
    "peer.glyph",
    "confirm.glyph",
    "cancel.glyph",
    "title.filtered",
    "delete.confirm.this",
)

#: §7-7：方块实体里**已存在**的 Area*/标记 实例字段（第 125/129 轮就在的）。
#: 判据是「冻结集之外一律判负」—— 不是「必须一刀切成 0 个」：`cachedSelection` / `marksCache`
#: 是既有只读缓存，本轮谁都不许动它们，但谁都不许**再加**新的。
FROZEN_AREA_FIELDS = frozenset({
    "areaSlot",          # 专用槽（选定器物品）
    "cursor",            # AreaCursor
    "cachedSelection",   # AreaSelection 快照缓存
    "legacyAreaFilters", # 旧档迁移源（§5：不许删也不许新增写入）
    "marksCache",        # Map<Area, List<MarkerEntry>> 只读缓存
    "marksCacheTick",    # 上面那份缓存的时间戳
    "areaSnapshotVersion",
})

#: §7-7：`AreaMarks.put(` 允许出现的文件（相对 `src/main/java/cretae/cookiewyq/rs_create_compat/`）。
#: §7-7 原文写的是「方块实体 / AreaSelectorEditPacket / AreaMarks 自己」三处；树里另有
#: `AreaMarkEditPacket` 这个**第 112 轮就存在**的标记编辑包（主手世界路径 `AreaMarkScreen` 走它，
#: 服务端权威写入，不是本轮新增的数据落点），故一并登记。
#: 第 130 轮收尾：`AreaMarkAddItemPacket`（第 125 轮为侧栏标记网格 JEI 拖入而开）在标记网格整页删除后
#: **没有任何发送方**，已连同注册行一并删除（否则 round92 的「新增死通道」判据会判负）。
#: 判据只看「有没有**新**文件跑出来写 AreaMarks」，任何未登记文件都会判负。
AREA_MARKS_PUT_WHITELIST = frozenset({
    "block/entity/AdvancedCollectionCacheBlockEntity.java",
    "network/AreaSelectorEditPacket.java",
    "network/AreaMarkEditPacket.java",
    "support/AreaMarks.java",
})

#: 反例「方块实体新增 Area 字段」用的人造声明（纯字符串）。
SYNTH_FIELD = "    private Area brandNewArea;\n"

#: 旧括号坐标拼接的两个字面量特征（§6：出现即判负）。
OLD_BRACKET_OPEN = '"(" +'
OLD_BRACKET_ARROW = '")→("'

FAILS = []
COUNTER_TOTAL = [0]
COUNTER_PASS = [0]


# ======================================================================================
# 基础工具（与 round124 / round125 / round129 同一套口径）
# ======================================================================================

def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def read(path):
    """读 UTF-8 文本；文件不存在返回空串（实现未就绪时不让脚本崩）。"""
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去注释、保留字符串字面量（`"(" +` / `")→("` / 语言键都要看得到）。"""
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


def has(source, pattern):
    return re.search(pattern, source) is not None


def const_int(code, name):
    """取 `int NAME = 123;` 的字面量（不存在返回 None）。`\\b` 保证 `W` 不会误命中 `MIN_W`。"""
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*(-?\d+)\s*;", code)
    return int(match.group(1)) if match else None


METHOD_HEAD = re.compile(r"\b(?:public|private|protected)\s+[^;{}()]*?\b(\w+)\s*\(")


def java_methods(code):
    """→ [(名字, 起点, 终点+1, 方法体文本)]：正则找方法头，再用括号配对取方法体。

    不写死行号（实现方随便挪行都不影响）；`[^;{}()]*?` 保证标识符紧贴 `(` 才是方法名。
    """
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


def method_names(code):
    return [item[0] for item in java_methods(code)]


def find_method(code, name):
    for item in java_methods(code):
        if item[0] == name:
            return item
    return None


def method_body_of(code, name):
    item = find_method(code, name)
    return item[3] if item else None


def enclosing_method(code, index):
    """→ 包含 index 的**最内层**方法（(名字, 起点, 终点, 体)）；找不到返回 None。"""
    best = None
    for item in java_methods(code):
        if item[1] <= index < item[2]:
            if best is None or (item[2] - item[1]) < (best[2] - best[1]):
                best = item
    return best


def split_args(arg_text):
    """按顶层逗号切参数（忽略嵌套括号里的逗号）。"""
    args = []
    depth = 0
    cur = []
    for ch in arg_text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        args.append(tail)
    return args


def paren_call_args(code, needle):
    """扫出 `needle`（自带左括号）的每一次调用 → [(行号, [参数...], 调用原文)]。"""
    out = []
    start = 0
    while True:
        i = code.find(needle, start)
        if i < 0:
            return out
        j = i + len(needle)
        depth = 1
        k = j
        while k < len(code) and depth > 0:
            if code[k] == "(":
                depth += 1
            elif code[k] == ")":
                depth -= 1
            k += 1
        inner = code[j:k - 1] if depth == 0 else code[j:]
        out.append((code.count("\n", 0, i) + 1, split_args(inner), needle + inner + ")"))
        start = k


CLASS_HEAD = re.compile(r"class\s+\w+[^{]*\{")


def class_body_span(code):
    """→ (body_start, body_end)：第一个 `class X ... {` 与它配平的 `}` 之间（含端点）。"""
    match = CLASS_HEAD.search(code)
    if not match:
        return None
    brace = match.end() - 1
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return brace + 1, i
    return None


FIELD_RE = re.compile(
    r"^(?:(?:private|protected|public)\s+)?"
    r"(?P<mods>(?:static\s+|final\s+|transient\s+|volatile\s+)*)"
    r"(?P<type>[\w.$][\w.$]*(?:\s*<[^;=]*>)?(?:\s*\[\])*)\s+"
    r"(?P<name>\w+)\s*(?:=|;)")


def class_instance_fields(code):
    """→ [(类型, 名字, 声明原文)]：**类体第一层**的非 static 字段（按行跟踪花括号深度）。

    深度跟踪是刻意的：局部变量（例如方法里的 `List<Area> areas`）不能算进「方块实体新增字段」。
    """
    span = class_body_span(code)
    if span is None:
        return []
    start, end = span
    out = []
    depth = 0
    for raw_line in code[start:end].split("\n"):
        stripped = raw_line.strip()
        if depth == 0 and stripped:
            match = FIELD_RE.match(stripped)
            if match and "static" not in match.group("mods"):
                out.append((match.group("type").strip(), match.group("name"), stripped))
        depth += raw_line.count("{") - raw_line.count("}")
    return out


# ======================================================================================
# 源码装载（快照 = 原文 + 语言键 + 全树 AreaMarks 写点）
# ======================================================================================

def scan_area_marks_put_sites():
    """全树扫 `AreaMarks.put(` 的调用点 → [(相对路径( posix), 行号)]。"""
    sites = []
    for dirpath, _dirs, filenames in os.walk(JAVA):
        for filename in sorted(filenames):
            if not filename.endswith(".java"):
                continue
            path = os.path.join(dirpath, filename)
            text = read(path)
            for match in re.finditer(r"AreaMarks\.put\s*\(", text):
                sites.append((os.path.relpath(path, JAVA).replace("\\", "/"),
                              text.count("\n", 0, match.start()) + 1))
    return sites


def load_snapshot():
    snap = {}
    for key, path in FILES.items():
        if key.startswith("lang_"):
            text = read(path)
            try:
                snap[key] = json.loads(text) if text.strip() else {}
            except Exception:
                snap[key] = {}
        else:
            snap[key] = read(path)
    snap["put_sites"] = scan_area_marks_put_sites()
    return snap


def prepare(snap):
    """原文 → 同时带「去注释文本」的快照（`<key>_code`），判据两种口径都要用。"""
    out = dict(snap)
    for key in FILES:
        if key.startswith("lang_"):
            continue
        out[key + "_code"] = strip_comments(snap.get(key, ""))
    return out


# ======================================================================================
# §0 文件存在性
# ======================================================================================

def section_files(s):
    out = []
    for key, label in (("panel", "AreaStripPanel.java"),
                       ("jei", "CollectionCacheJeiPlugin.java"),
                       ("adv", "AdvancedCollectionCacheScreen.java"),
                       ("base", "CollectionCacheScreen.java"),
                       ("blockentity", "AdvancedCollectionCacheBlockEntity.java")):
        out.append(("§0 %s 存在且非空" % label,
                    bool((s.get(key) or "").strip()),
                    "文件不存在或为空：%s" % rel(FILES[key])))
    for key, label in (("lang_zh", "zh_cn.json"), ("lang_en", "en_us.json")):
        out.append(("§0 %s 能解析成 JSON 对象" % label,
                    isinstance(s.get(key), dict) and bool(s.get(key)),
                    "解析失败或为空：%s" % rel(FILES[key])))
    return out


# ======================================================================================
# §1 几何（契约 §2）
# ======================================================================================

def section_geometry(s):
    out = []
    panel = s["panel_code"]

    for name, want, note in GEOMETRY_OUTER:
        got = const_int(panel, name)
        out.append(("§1 %s = %d（%s）" % (name, want, note),
                    got == want,
                    "契约 §2：期望 `%s = %d`；实际 %s" % (name, want, got)))

    declared = set(re.findall(
        r"public\s+static\s+final\s+int\s+(W|MIN_W|HARD_MIN_W|GAP|SCREEN_MARGIN|ROW_H|PAD)\s*=", panel))
    out.append(("§1 7 个外层常量都是 public static final（JEI / 界面共用同一份）",
                len(declared) == 7,
                "只找到 %d 个：%s" % (len(declared), sorted(declared))))

    for name, want, note in GEOMETRY_INNER:
        got = const_int(panel, name)
        out.append(("§1 %s = %d（%s）" % (name, want, note),
                    got == want,
                    "契约 §2：期望 `%s = %d`；实际 %s" % (name, want, got)))

    top_body = method_body_of(panel, "rowsAreaTop")
    out.append(("§1 rowsAreaTop() 的公式里用到 SEARCH_H（搜索框占位，否则行列表压住搜索框）",
                top_body is not None and "SEARCH_H" in top_body,
                "rowsAreaTop() 方法体 = %r" % ((top_body or "（没找到 rowsAreaTop()）")[:120],)))

    edits = paren_call_args(panel, "new EditBox(")
    out.append(("§1 搜索框用 SEARCH_H 当高度（new EditBox(..., SEARCH_H, ...)）",
                any("SEARCH_H" in args for _line, args, _text in edits),
                "找到 %d 处 new EditBox(...)，参数里都没有 SEARCH_H" % len(edits)))

    bg_h = const_int(panel, "BG_H")
    pad = const_int(panel, "PAD")
    title = const_int(panel, "TITLE_H")
    search = const_int(panel, "SEARCH_H")
    sgap = const_int(panel, "SEARCH_GAP")
    footer = const_int(panel, "FOOTER_H")
    row_h = const_int(panel, "ROW_H")
    if None in (bg_h, pad, title, search, sgap, footer, row_h):
        out.append(("§1 FOOTER_H 与面板高的关系成立（解析常量算总账）", False,
                    "常量解析不完整：BG_H=%s PAD=%s TITLE_H=%s SEARCH_H=%s SEARCH_GAP=%s FOOTER_H=%s ROW_H=%s"
                    % (bg_h, pad, title, search, sgap, footer, row_h)))
    else:
        panel_h = bg_h - 8                      # layout 里的 h = BG_H - 8
        rows_bottom = panel_h - pad - footer    # 底部按钮带上沿
        rows_top = pad + title + 2 + search + sgap   # 标题 → 1px 分隔线 → 搜索框 → 行列表
        rows_h = rows_bottom - rows_top
        want = 6 * row_h
        out.append(("§1 FOOTER_H 与面板高的关系成立：行区高 = %d ≥ 6×ROW_H = %d" % (rows_h, want),
                    rows_bottom > rows_top and rows_h >= want,
                    "面板高 %d（BG_H-8）→ 行区 [%d, %d] 高 %d；下限 %d"
                    % (panel_h, rows_top, rows_bottom, rows_h, want)))

    out.append(("§1 h = BG_H - 8（面板高仍与 GUI 同源，不另起一套）",
                has(panel, r"(?:BG_H|imageHeight)\s*-\s*8\b"),
                "没找到 `BG_H - 8`"))
    return out


# ======================================================================================
# §2 标记网格彻底删除（契约 §0-1 / §2-2 / §4.3）
# ======================================================================================

def section_mark_grid(s):
    out = []
    panel_raw = s["panel"]
    panel = s["panel_code"]
    jei = s["jei_code"]
    adv = s["adv_code"]

    counts = dict((token, panel_raw.count(token)) for token in MARK_TOKENS)
    offenders = dict((token, value) for token, value in counts.items() if value)
    out.append(("§2 AreaStripPanel 里 markGrid|markCells|openMarkGrid|acceptGhost|isMarkGridOpen|"
                "markCellAt|turnMarkPage 出现次数 = 0（含 javadoc / 注释）",
                not offenders,
                "残留：%s（每留一处就是留着一条复活路径）" % (offenders or "无")))

    mark_consts = sorted(set(re.findall(r"\bMARK_[A-Z0-9_]+\b", panel_raw)))
    out.append(("§2 标记网格常量 MARK_* 全部删除（不留死代码）",
                not mark_consts, "残留常量：%s" % mark_consts))

    out.append(("§2 CollectionCacheJeiPlugin 里 AreaStripPanel 出现次数 = 0（类型名连 import 都不许）",
                jei.count("AreaStripPanel") == 0,
                "出现 %d 次" % jei.count("AreaStripPanel")))

    jei_tokens = dict((token, jei.count(token))
                      for token in ("isMarkGridOpen", "markGridCellAreas", "acceptGhost"))
    out.append(("§2 CollectionCacheJeiPlugin 里 isMarkGridOpen|markGridCellAreas|acceptGhost 次数 = 0",
                not any(jei_tokens.values()), "残留：%s" % jei_tokens))

    adv_tokens = dict((token, adv.count(token))
                      for token in ("closeMarkGrid", "isMarkGridOpen", "openMarkGrid"))
    out.append(("§2 AdvancedCollectionCacheScreen 里 closeMarkGrid / isMarkGridOpen 次数 = 0（§3.4 删那行转发）",
                not any(adv_tokens.values()), "残留：%s" % adv_tokens))

    kept = {
        "getGuiExtraAreas": jei.count("getGuiExtraAreas"),
        "AreaStripBounds.get(": jei.count("AreaStripBounds.get("),
        "SetCollectionMarkerConfigPacket": jei.count("SetCollectionMarkerConfigPacket"),
        "getTargetsTyped": jei.count("getTargetsTyped"),
    }
    out.append(("§2 防删过头：JEI 的 48 格通道与避让通道都还在（§4.3 只删侧栏分支）",
                all(value >= 1 for value in kept.values()),
                "计数：%s" % kept))
    return out


# ======================================================================================
# §3 搜索（契约 §3.1）
# ======================================================================================

def find_filter_method(code):
    """→ token 过滤方法名（优先 `matchesQuery`；名字不同时按「含 search/match 且含 query/filter/token」找）。"""
    names = method_names(code)
    if "matchesQuery" in names:
        return "matchesQuery"
    loose = []
    for name in names:
        low = name.lower()
        if ("search" in low or "match" in low or "filter" in low) and (
                "query" in low or "filter" in low or "token" in low or "keyword" in low):
            loose.append(name)
    return loose[0] if loose else None


def section_search(s):
    out = []
    panel = s["panel_code"]

    field = re.search(r"(?:private|protected)\s+(?:final\s+)?EditBox\s+searchBox\b", panel) or \
        re.search(r"\bEditBox\s+searchBox\b", panel)
    out.append(("§3 searchBox 字段存在（原版 EditBox，常驻而不是点了才出现）",
                field is not None,
                "没找到 `EditBox searchBox` 字段声明"))

    token = find_filter_method(panel)
    out.append(("§3 token 过滤方法存在（matchesQuery 或等价：含 search/match 且含 query/filter/token）",
                token is not None,
                "候选方法名里没找到过滤方法；现有方法：%s" % method_names(panel)[:40]))

    wired = None
    if token:
        for candidate in ("rebuildRows", "rebuild", "buildRows", "refreshRows",
                          "recomputeRows", "applyQuery", "filterRows"):
            body = method_body_of(panel, candidate)
            if body and (token + "(") in body:
                wired = candidate
                break
        if wired is None:
            for name, _start, _end, body in java_methods(panel):
                if (token + "(") in body and ("continue" in body or "keep" in body):
                    wired = name
                    break
    out.append(("§3 过滤真的接在「建行」路径上（rebuildRows 类方法里调用 %s）" % (token or "过滤方法"),
                wired is not None,
                "没有任何建行方法调用过滤方法（过滤存在但不会被用）"))

    rebuild = method_body_of(panel, wired) if wired else None
    filtered = (rebuild is not None and token is not None and (token + "(") in rebuild
                and ("continue" in rebuild or ".add(" in rebuild))
    out.append(("§3 过滤结果就是行列表本身（filteredRows 等价：重建行时只收命中行）",
                filtered,
                "建行方法 = %s；条件放行 = %s"
                % (wired or "（没找到）",
                   ("continue" in (rebuild or "")) or (".add(" in (rebuild or "")))))

    out.append(("§3 过滤中标题显示「· 命中 N」（title.filtered）",
                "title.filtered" in panel, "panel 里没有 title.filtered"))

    out.append(("§3 过滤变化时 scrollRows 归零 + 搜索框有 responder",
                has(panel, r"scrollRows\s*=\s*0") and "setResponder(" in panel,
                "scrollRows=0：%s；setResponder：%s"
                % (has(panel, r"scrollRows\s*=\s*0"), "setResponder(" in panel)))

    out.append(("§3 搜索框 hint 用 area_strip.search.hint（setHint）",
                "search.hint" in panel and "setHint(" in panel,
                "search.hint：%s；setHint：%s" % ("search.hint" in panel, "setHint(" in panel)))

    for method_name in ("mouseClicked", "keyPressed", "charTyped"):
        body = method_body_of(panel, method_name)
        out.append(("§3 %s 里有搜索框路径（searchBox 先给它）" % method_name,
                    body is not None and "searchBox" in body,
                    "方法%s；searchBox：%s"
                    % ("没找到" if body is None else "体找到了",
                       "searchBox" in (body or ""))))

    key_body = method_body_of(panel, "keyPressed") or ""
    out.append(("§3 Esc 清空搜索而不是关界面（keyPressed 里 setValue(\"\")）",
                'setValue("")' in key_body and "searchBox" in key_body,
                "setValue(\"\")：%s" % ('setValue("")' in key_body)))
    return out


# ======================================================================================
# §4 删除二次确认 + 匹配区钩子（契约 §3.3 / §4.1 / §4.2）
# ======================================================================================

def section_delete_and_match(s):
    out = []
    panel_raw = s["panel"]
    panel = s["panel_code"]
    base = s["base_code"]
    adv = s["adv_code"]

    count = panel_raw.count("ACTION_DELETE")
    out.append(("§4 ACTION_DELETE 在全文件恰好出现 1 处（没有第二条裸发送路径）",
                count == 1,
                "实际 %d 处；每多一处就多一条「未武装也能删」的路" % count))

    index = panel.find("ACTION_DELETE")
    owner = enclosing_method(panel, index) if index >= 0 else None
    owner_body = owner[3] if owner else ""
    armed_ok = owner is not None and re.search(r"(?i)armed", owner_body) is not None
    out.append(("§4 唯一发送点所在方法里有 armed 判据（deleteArmedRaw / armed）",
                armed_ok,
                "所在方法 = %s" % (owner[0] if owner else "（找不到外层方法）")))

    expire = method_body_of(panel, "expireArming")
    out.append(("§4 expireArming() 仍然把 armed 归零（3 秒后自己回到安全态）",
                expire is not None and re.search(r"deleteArmedRaw\s*=\s*-1", expire) is not None,
                "expireArming() 方法体 %s" % ("里没有 deleteArmedRaw = -1"
                                             if expire is not None else "不存在")))

    arm_names = [name for name in method_names(panel) if re.search(r"arm", name, re.I)]
    out.append(("§4 武装入口还在（armOrDelete / armDelete / isArmed 之类）",
                bool(arm_names), "方法名里没有 arm* / *Armed：%s" % method_names(panel)[:40]))

    confirm_ms = re.search(r"CONFIRM_MS\s*=\s*(\d+)", panel)
    out.append(("§4 二次确认窗口 = 3000ms（CONFIRM_MS）",
                confirm_ms is not None and int(confirm_ms.group(1)) == 3000,
                "CONFIRM_MS = %s" % (confirm_ms.group(1) if confirm_ms else "没找到")))

    out.append(("§4.1 基础版有 protected Component matchRegionLabel() 钩子",
                has(base, r"protected\s+Component\s+matchRegionLabel\s*\(\s*\)"),
                "没找到该钩子签名"))

    labels = method_body_of(base, "renderLabels") or ""
    out.append(("§4.1 renderLabels 用 matchRegionLabel() 画匹配区标题（基础版画面逐字不变）",
                "matchRegionLabel(" in labels and "MATCH_LABEL_X" in labels,
                "renderLabels 里 matchRegionLabel=%s MATCH_LABEL_X=%s"
                % ("matchRegionLabel(" in labels, "MATCH_LABEL_X" in labels)))

    out.append(("§4.2 基础版有 protected void drawMatchAreaHint(final GuiGraphics) 空实现钩子",
                has(base, r"protected\s+void\s+drawMatchAreaHint\s*\(\s*(?:final\s+)?GuiGraphics\s+\w+\s*\)"),
                "没找到该钩子签名"))

    adv_ok = ("match_label" in adv and "match_label.none" in adv
              and "pick_hint" in adv and "drawMatchAreaHint" in adv
              and "matchRegionLabel" in adv)
    out.append(("§4.1/4.2 高级版覆写两个钩子（match_label / match_label.none / pick_hint）",
                adv_ok,
                "match_label=%s none=%s pick_hint=%s drawMatchAreaHint=%s matchRegionLabel=%s"
                % ("match_label" in adv, "match_label.none" in adv, "pick_hint" in adv,
                   "drawMatchAreaHint" in adv, "matchRegionLabel" in adv)))
    return out


# ======================================================================================
# §5 世界框（契约 §3.5）
# ======================================================================================

def section_world_outline(s):
    out = []
    panel = s["panel_code"]

    decl = re.search(r"(private|protected|public)\s+static\s+final\s+[\w.<>\[\]]+\s+"
                     r"FOCUS_SLOT\s*=\s*([^;]+);", panel)
    out.append(("§5 FOCUS_SLOT 是 static final 常量（不再每帧新建 String）",
                decl is not None,
                "没找到 `static final … FOCUS_SLOT = …;`（§3.5 的常量形态）"))

    out.append(("§5 FOCUS_SLOT 的值是 AreaOutline.slot(\"strip/focus\")",
                decl is not None and re.search(r'AreaOutline\.slot\(\s*"strip/focus"\s*\)',
                                               decl.group(2)) is not None,
                "常量值 = %r" % (decl.group(2).strip() if decl else "（没找到声明）")))

    live = len(re.findall(r'AreaOutline\.slot\(\s*"strip/focus"\s*\)', panel))
    out.append(("§5 AreaOutline.slot(\"strip/focus\") 全文件只出现 1 次（只在常量声明处）",
                live == 1, "出现 %d 次（>1 = 仍有现场新建）" % live))

    feed = method_body_of(panel, "feedWorldOutline") or ""
    out.append(("§5 feedWorldOutline() 用 FOCUS_SLOT + chase（界面开着时常亮）",
                "FOCUS_SLOT" in feed and "AreaOutline.chase(" in feed,
                "FOCUS_SLOT=%s chase=%s" % ("FOCUS_SLOT" in feed, "AreaOutline.chase(" in feed)))

    removed = method_body_of(panel, "removed") or ""
    out.append(("§5 removed() 里出现 showForView（关界面后按剩余 TTL 重新武装，框不会立刻消失）",
                "showForView" in removed,
                "removed() 方法体里 %d 处 showForView（只有 remove = 用户说的「边框还是没用」）"
                % removed.count("showForView")))

    out.append(("§5 removed() 用 FOCUS_SLOT + VIEW_TTL（5 秒口径与 AreaSelectorScreen 一致）",
                "FOCUS_SLOT" in removed and "VIEW_TTL" in removed,
                "FOCUS_SLOT=%s VIEW_TTL=%s" % ("FOCUS_SLOT" in removed, "VIEW_TTL" in removed)))

    peek_ok = False
    for _name, _start, _end, body in java_methods(panel):
        if 'strip/peek' in body and "showForView" in body:
            peek_ok = True
            break
    out.append(("§5 每行 ◎「看」按钮走独立 slot strip/peek/N（showForView）",
                peek_ok, "没有任何方法同时出现 strip/peek 与 showForView"))

    # 第 130 轮收尾改口径：动作区从「看 / 改名 / 删除」三个图标收成「改名 / 删除」两个
    # （多出的 10px 让常见三位数坐标串 1.0 倍放下，见 §6 字号纪律），「看这块地」改绑**右键行**。
    # 因此这里断言的是「右键 → peekArea」，而不再是「peer.glyph 被画出来」。
    # 右键分支在 mouseClicked 里：`button == 1` 那一段调 peekArea(...)
    right_click_peek = False
    for _name, _start, _end, body in java_methods(panel):
        if _name == "mouseClicked" and "button == 1" in body and "peekArea(" in body:
            right_click_peek = True
            break
    out.append(("§5 「看这块地」绑在右键行（peekArea + strip/peek/N），动作区只留改名 / 删除",
                right_click_peek and "ACT_COUNT = 2" in panel,
                "右键 peekArea=%s ACT_COUNT=2=%s" % (right_click_peek, "ACT_COUNT = 2" in panel)))
    return out


# ======================================================================================
# §6 字体 / 废话（契约 §2 字号纪律 + §0-6）
# ======================================================================================

def section_fonts(s):
    out = []
    panel = s["panel_code"]

    out.append(("§6 不出现旧括号坐标拼接 `\"(\" +`（判负：坐标行改回括号版）",
                OLD_BRACKET_OPEN not in panel,
                "出现 %d 次" % panel.count(OLD_BRACKET_OPEN)))

    out.append(("§6 不出现 `)→(` 拼接（判负：括号版坐标串）",
                OLD_BRACKET_ARROW not in panel,
                "出现 %d 次" % panel.count(OLD_BRACKET_ARROW)))

    coords_body = None
    for _name, _start, _end, body in java_methods(panel):
        if '"→"' in body or "'→'" in body:
            coords_body = body
            break
    getters = set(re.findall(r"\.(min[XYZ]|max[XYZ])\(\)", coords_body or ""))
    out.append(("§6 坐标紧凑串由 6 个 getter 自己拼（minX..maxZ，无空格无括号）",
                len(getters) == 6,
                "坐标串方法里用到的 getter：%s" % sorted(getters)))

    out.append(("§6 坐标紧凑串不用 describeMin / describeMax 的带空格串（tooltip 的「从 …」仍可用）",
                coords_body is not None
                and not has(coords_body, r"\bdescribe(Min|Max)\s*\(")
                and not has(coords_body, r"\bdescribeSize\s*\("),
                "坐标串方法体里仍在用 describe*（返回值本身带空格 / 括号）"))

    calls = paren_call_args(panel, "RsccTextFit.drawFitLine(")
    bad_arity = [text[:40] for _line, args, text in calls if len(args) != 7]
    out.append(("§6 所有 drawFitLine 调用都是 7 参（graphics/font/text/x/y/maxWidth/color）",
                not bad_arity and len(calls) >= 3,
                "共 %d 处调用；参数个数不是 7 的：%s" % (len(calls), bad_arity)))

    bad_width = [args[5] for _line, args, _text in calls
                 if len(args) == 7 and re.fullmatch(r"-?\d+", args[5].strip())]
    out.append(("§6 drawFitLine 的 maxWidth 都是常量表达式或方法调用（不是裸数字）",
                not bad_width, "裸数字 maxWidth：%s" % bad_width))

    out.append(("§6 源码字符串里没有省略号（放不下就改排列 / 折行，不截断）",
                "…" not in panel and not re.search(r'"[^"]*\.\.\.[^"]*"', panel),
                "出现了省略号"))
    return out


# ======================================================================================
# §7 数据归属（契约 §5）
# ======================================================================================

def section_data_home(s):
    out = []
    blockentity = s["blockentity_code"]

    fields = class_instance_fields(blockentity)
    areaish = [(typename, name) for typename, name, _decl in fields
               if "Area" in typename or re.search(r"(?i)area|mark", name)]
    extra = sorted(name for _typename, name in areaish if name not in FROZEN_AREA_FIELDS)
    out.append(("§7 AdvancedCollectionCacheBlockEntity 没有新增 Area*/标记 实例字段"
                "（冻结集 %d 个之外一律判负）" % len(FROZEN_AREA_FIELDS),
                not extra,
                "未登记字段：%s；当前 Area* 实例字段：%s"
                % (extra, sorted(name for _typename, name in areaish))))

    out.append(("§7 legacyAreaFilters 仍在（旧档迁移源：不许删也不许新增写入）",
                has(blockentity, r"\blegacyAreaFilters\b"),
                "方块实体里没有 legacyAreaFilters"))

    put_files = sorted(set(path for path, _line in s["put_sites"]))
    outside = [path for path in put_files if path not in AREA_MARKS_PUT_WHITELIST]
    out.append(("§7 AreaMarks.put( 调用点只在白名单文件里（全树扫 src/main/java）",
                not outside,
                "白名单外：%s；实际写点：%s" % (outside, put_files)))

    client_writes = [(path, line) for path, line in s["put_sites"] if path.startswith("client/")]
    out.append(("§7 客户端不写 AreaMarks（client/** 里 AreaMarks.put 0 次）",
                not client_writes, "客户端写点：%s" % client_writes))
    return out


# ======================================================================================
# §8 lang（契约 §6 + §7-8）
# ======================================================================================

def placeholders(text):
    return len(re.findall(r"%(?:\d+\$)?[sd]", str(text)))


def section_lang(s):
    out = []
    zh = s["lang_zh"] if isinstance(s["lang_zh"], dict) else {}
    en = s["lang_en"] if isinstance(s["lang_en"], dict) else {}

    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    out.append(("§8 zh_cn 与 en_us 键集合完全相同",
                not only_zh and not only_en,
                "只在 zh：%s ；只在 en：%s" % (only_zh[:8], only_en[:8])))

    missing = [(key, "zh" if key not in zh or not str(zh.get(key, "")).strip()
                else ("en" if key not in en or not str(en.get(key, "")).strip() else ""))
               for key in NEW_LANG_KEYS]
    missing = [(key, where) for key, where in missing if where]
    out.append(("§8 §6 的 9 个新键两边都有且非空",
                not missing, "缺 / 空：%s" % missing))

    mismatched = []
    for key in NEW_LANG_KEYS:
        if key in zh and key in en and placeholders(zh[key]) != placeholders(en[key]):
            mismatched.append((key, placeholders(zh[key]), placeholders(en[key])))
    out.append(("§8 新键的占位符个数中英一致（String.format 不会抛）",
                not mismatched, "占位符不一致：%s" % mismatched))

    bad_glyphs = {}
    for key, want in NEW_LANG_GLYPHS.items():
        for label, table in (("zh", zh), ("en", en)):
            got = table.get(key)
            if got != want:
                bad_glyphs["%s/%s" % (label, key)] = (got, want)
    out.append(("§8 glyph 键与 §6 表格逐字一致（◎ / ✓ / ✗）",
                not bad_glyphs, "不一致：%s" % bad_glyphs))

    mark_keys = sorted([key for key in set(zh) | set(en) if key.startswith(DELETED_LANG_PREFIX)])
    out.append(("§8 area_strip.mark.* 前缀两边都没有（整族删除）",
                not mark_keys, "还留着 %d 个：%s" % (len(mark_keys), mark_keys[:8])))

    howto = [key for key in DELETED_LANG_KEYS if key in zh or key in en]
    out.append(("§8 area_strip.tip.howto 两边都没有（tooltip 从 6 行砍到 3 行）",
                not howto, "还留着：%s" % howto))

    kept_zh = [key for key in zh if key.startswith(KEPT_LANG_PREFIX)]
    kept_en = [key for key in en if key.startswith(KEPT_LANG_PREFIX)]
    out.append(("§8 area_mark.* 前缀两边都还在（独立界面 AreaMarkScreen 的键，不许误删）",
                bool(kept_zh) and len(kept_zh) == len(kept_en),
                "zh %d 个 / en %d 个" % (len(kept_zh), len(kept_en))))

    panel = s["panel_code"]
    used = ['"' + suffix + '"' for suffix in PANEL_NEW_KEY_SUFFIXES
            if ('"' + suffix + '"') in panel]
    absent = []
    for suffix, literal in zip(PANEL_NEW_KEY_SUFFIXES, used):
        key = "gui.rs_create_compat.area_strip." + suffix
        if key not in zh or key not in en:
            absent.append((suffix, key in zh, key in en))
    out.append(("§8 区域条代码引用的新键在两边都存在（%d 个引用处检查）" % len(used),
                len(used) >= 4 and not absent,
                "引用 %d 个；缺键：%s" % (len(used), absent)))
    return out


# ======================================================================================
# 全部断言
# ======================================================================================

SECTIONS = (
    ("§0 文件", section_files),
    ("§1 几何", section_geometry),
    ("§2 标记网格删除", section_mark_grid),
    ("§3 搜索", section_search),
    ("§4 删除确认 / 匹配区", section_delete_and_match),
    ("§5 世界框", section_world_outline),
    ("§6 字体 / 废话", section_fonts),
    ("§7 数据归属", section_data_home),
    ("§8 lang", section_lang),
)


def run_all(raw):
    """原文快照 → [(判据名, 是否通过, 详情)]（纯函数：反例自证直接喂改坏的副本）。"""
    snap = prepare(raw)
    out = []
    for _label, section in SECTIONS:
        out.extend(section(snap))
    return out


def failures_of(raw):
    return [name for name, ok, _detail in run_all(raw) if not ok]


# ======================================================================================
# 反例自证（§9）：内存里改坏 / 纯字符串构造，断言判据真的判负（不写任何文件）
# ======================================================================================

class AnchorMissing(Exception):
    """真源码里找不到锚点（实现未就绪 / 写法与契约不同）⇒ 反例退化成纯字符串样本。"""


def copy_with(raw, **overrides):
    out = dict(raw)
    out.update(overrides)
    return out


def repl(raw, key, old, new):
    """在 raw[key] 里替换 1 处；锚点不存在抛 AnchorMissing。"""
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


def append(raw, key, extra):
    return copy_with(raw, **{key: (raw.get(key) or "") + extra})


def method_replace(raw, key, method_name, new_text):
    """把某个方法的整段（含声明）换成 new_text；锚点不存在抛 AnchorMissing。"""
    text = raw.get(key) or ""
    code = strip_comments(text)
    item = find_method(code, method_name)
    if item is None:
        raise AnchorMissing("%s 里没有方法 %s()" % (key, method_name))
    snippet = code[item[1]:item[2]]
    if snippet in text:
        return copy_with(raw, **{key: text.replace(snippet, new_text, 1)})
    return copy_with(raw, **{key: code[:item[1]] + new_text + code[item[2]:]})


def move_field_into_class(raw, key, field_text):
    """把一段字段声明插到类体开头（用来模拟「方块实体新增 Area 字段」）；锚点不存在抛 AnchorMissing。"""
    text = raw.get(key) or ""
    # 直接在**原文**里找类头：去注释文本与原文下标不同，混用下标会插错位置（反例假通过）。
    match = CLASS_HEAD.search(text)
    if not match:
        raise AnchorMissing("%s 里没有类声明" % key)
    brace = match.end() - 1
    return copy_with(raw, **{key: text[:brace + 1] + "\n" + field_text + text[brace + 1:]})


def drop_lang(raw, key, lang_key):
    table = dict(raw.get(key) or {})
    table.pop(lang_key, None)
    return copy_with(raw, **{key: table})


def set_lang(raw, key, lang_key, value):
    table = dict(raw.get(key) or {})
    table[lang_key] = value
    return copy_with(raw, **{key: table})


def counterexample_specs(raw):
    """→ [(标签, 目标判据前缀, 构造器)]；构造器抛 AnchorMissing 时由外层退化成合成样本。"""
    panel_key_pressed = method_body_of(strip_comments(raw.get("panel") or ""), "keyPressed")
    removed_body_text = ("public void removed()\n"
                         "    {\n"
                         "        AreaOutline.remove(FOCUS_SLOT);\n"
                         "    }")
    synth_panel_key = ("public boolean keyPressed(final int keyCode, final int scanCode, "
                       "final int modifiers)\n    {\n        return false;\n    }")

    specs = []

    # ---- §1 几何 ----
    def cex_w():
        return repl(raw, "panel", "public static final int W = 176;",
                    "public static final int W = 128;")

    specs.append(("C-01 把 W 改回 128（坐标行必被缩到 0.6 倍 = 玩家说的字小）",
                  ("§1 W = 176",), cex_w,
                  lambda: copy_with(raw, panel="public static final int W = 128;\n")))

    def cex_min_w():
        return repl(raw, "panel", "public static final int MIN_W = 140;",
                    "public static final int MIN_W = 96;")

    specs.append(("C-02 把 MIN_W 改回 96（挤的时候收得比契约更窄）",
                  ("§1 MIN_W = 140",), cex_min_w,
                  lambda: copy_with(raw, panel="public static final int MIN_W = 96;\n")))

    def cex_hard():
        return repl(raw, "panel", "public static final int HARD_MIN_W = 108;",
                    "public static final int HARD_MIN_W = 72;")

    specs.append(("C-03 把 HARD_MIN_W 改回 72（屏幕很窄时仍硬画侧栏）",
                  ("§1 HARD_MIN_W = 108",), cex_hard,
                  lambda: copy_with(raw, panel="public static final int HARD_MIN_W = 72;\n")))

    def cex_rows_top():
        return move_change_rows_area_top(raw, "12")

    specs.append(("C-04 rowsAreaTop() 不再用 SEARCH_H（行列表上移，压住搜索框）",
                  ("§1 rowsAreaTop()",), cex_rows_top,
                  lambda: copy_with(raw, panel=(
                      "private int rowsAreaTop()\n"
                      "    {\n"
                      "        return y + PAD + TITLE_H + 2 + 12 + 3;\n"
                      "    }\n"))))

    # ---- §2 标记网格 ----
    specs.append(("C-05 isMarkGridOpen 复活（JEI 侧栏分支的判据回来）",
                  ("§2 AreaStripPanel 里 markGrid",),
                  lambda: append(raw, "panel",
                                 "\n    public boolean isMarkGridOpen() { return false; }\n"),
                  None))

    specs.append(("C-06 markCellAt 复活（格子命中测试回来）",
                  ("§2 AreaStripPanel 里 markGrid",),
                  lambda: append(raw, "panel",
                                 "\n    private int markCellAt(final double mx, final double my) "
                                 "{ return -1; }\n"),
                  None))

    specs.append(("C-07 JEI 里 AreaStripPanel 复活（侧栏类型名连 import 都不许）",
                  ("§2 CollectionCacheJeiPlugin 里 AreaStripPanel",),
                  lambda: append(raw, "jei",
                                 "\nimport cretae.cookiewyq.rs_create_compat.client.screen."
                                 "AreaStripPanel;\n"),
                  None))

    # ---- §3 搜索 ----
    def cex_no_field():
        return repl_re(raw, "panel", r"(?:private|protected)\s+(?:final\s+)?EditBox\s+searchBox\b",
                       "private Object searchBoxGone")

    specs.append(("C-08 删掉 searchBox 字段（搜索整页消失）",
                  ("§3 searchBox 字段",), cex_no_field,
                  lambda: copy_with(raw, panel="private Object searchBoxGone;\n")))

    def cex_key_no_search():
        if panel_key_pressed is None:
            raise AnchorMissing("没有 keyPressed()")
        if "searchBox" in panel_key_pressed:
            return method_replace(raw, "panel", "keyPressed", synth_panel_key)
        raise AnchorMissing("keyPressed() 里本来就没有 searchBox（实现未落地）")

    specs.append(("C-09 keyPressed 不给搜索框（打字时按键被 GUI 抢走）",
                  ("§3 keyPressed 里有搜索框路径",), cex_key_no_search,
                  lambda: copy_with(raw, panel=synth_panel_key)))

    # ---- §4 删除确认 ----
    specs.append(("C-10 未 armed 就裸发 ACTION_DELETE（多一条删除路径）",
                  ("§4 ACTION_DELETE 在全文件恰好出现 1 处",),
                  lambda: append(raw, "panel",
                                 "\n    private void bareDelete()\n    {\n"
                                 "        send(new AreaSelectorEditPacket(0, "
                                 "AreaSelectorEditPacket.ACTION_DELETE, null));\n    }\n"),
                  None))

    def cex_armed_gone():
        code = strip_comments(raw.get("panel") or "")
        index = code.find("ACTION_DELETE")
        if index < 0:
            raise AnchorMissing("panel 里没有 ACTION_DELETE")
        item = enclosing_method(code, index)
        if item is None:
            raise AnchorMissing("ACTION_DELETE 没有外层方法")
        bare = ("private void onDeleteNoGate()\n    {\n"
                "        send(new AreaSelectorEditPacket(0, "
                "AreaSelectorEditPacket.ACTION_DELETE, null));\n    }")
        return method_replace(raw, "panel", item[0], bare)

    specs.append(("C-11 唯一发送点移出 armed 判据（数量仍是 1，但不再需要二次确认）",
                  ("§4 唯一发送点所在方法里有 armed 判据",), cex_armed_gone,
                  lambda: copy_with(raw, panel=(
                      "private void onDeleteNoGate()\n"
                      "    {\n"
                      "        send(new AreaSelectorEditPacket(0, "
                      "AreaSelectorEditPacket.ACTION_DELETE, null));\n"
                      "    }\n"))))

    # ---- §5 世界框 ----
    def cex_removed_only_remove():
        return method_replace(raw, "panel", "removed", removed_body_text)

    specs.append(("C-12 removed() 只 remove 不 showForView（关界面框立刻消失 = 用户「边框没用」）",
                  ("§5 removed() 里出现 showForView",), cex_removed_only_remove,
                  lambda: copy_with(raw, panel=removed_body_text)))

    def cex_focus_not_static():
        return repl_re(raw, "panel", r"(private\s+)static\s+final(\s+[\w.<>\[\]]+\s+FOCUS_SLOT)",
                       r"\1final\2")

    specs.append(("C-13 FOCUS_SLOT 去掉 static final（每帧/每处新建 slot 键）",
                  ("§5 FOCUS_SLOT 是 static final",), cex_focus_not_static,
                  lambda: copy_with(raw, panel='private final Object FOCUS_SLOT = '
                                                'AreaOutline.slot("strip/focus");\n')))

    specs.append(("C-14 现场新建 AreaOutline.slot(\"strip/focus\")（不再走常量）",
                  ("§5 AreaOutline.slot",),
                  lambda: append(raw, "panel",
                                 "\n    private Object peekSlot() "
                                 "{ return AreaOutline.slot(\"strip/focus\"); }\n"),
                  None))

    # ---- §6 字体 ----
    specs.append(("C-15 坐标行改回括号拼接（\"(\" + … + \")→(\"）",
                  ("§6 不出现旧括号坐标拼接",),
                  lambda: append(raw, "panel",
                                 '\n    private String legacy(final Area a)\n    {\n'
                                 '        return "(" + a.minX() + "," + a.minY() + ")→(" '
                                 '+ a.maxX() + ")";\n    }\n'),
                  None))

    specs.append(("C-16 drawFitLine 的 maxWidth 写成裸数字（靠缩字号硬塞）",
                  ("§6 drawFitLine 的 maxWidth",),
                  lambda: append(raw, "panel",
                                 "\n    private void tiny()\n    {\n"
                                 "        RsccTextFit.drawFitLine(null, null, null, 0, 0, 160, 0);\n"
                                 "    }\n"),
                  None))

    # ---- §7 数据归属 ----
    def cex_new_field():
        return move_field_into_class(raw, "blockentity", SYNTH_FIELD)

    specs.append(("C-17 方块实体新增 Area 字段（数据从选定器物品跑到方块里）",
                  ("§7 AdvancedCollectionCacheBlockEntity",), cex_new_field,
                  lambda: copy_with(raw, blockentity=(
                      "public final class AdvancedCollectionCacheBlockEntity\n{\n"
                      + SYNTH_FIELD + "}\n"))))

    def cex_put_site():
        sites = list(raw.get("put_sites") or [])
        sites.append(("client/screen/CheatingScreen.java", 1))
        return copy_with(raw, put_sites=sites)

    specs.append(("C-18 AreaMarks.put 出现在白名单外的新文件（客户端自己写标记）",
                  ("§7 AreaMarks.put",), cex_put_site, None))

    # ---- §8 lang ----
    specs.append(("C-19 en_us 少一个键（键集合不等 = 界面上裸键名）",
                  ("§8 zh_cn 与 en_us 键集合完全相同", "§8 §6 的 9 个新键"),
                  lambda: drop_lang(raw, "lang_en",
                                    "gui.rs_create_compat.area_strip.search.hint"),
                  None))

    specs.append(("C-20 area_strip.mark.* 键族复活（标记网格的废话文案回来）",
                  ("§8 area_strip.mark.*",),
                  lambda: set_lang(set_lang(raw, "lang_zh",
                                            "gui.rs_create_compat.area_strip.mark.title",
                                            "标记：区域 %s"),
                                   "lang_en",
                                   "gui.rs_create_compat.area_strip.mark.title",
                                   "Marks: area %s"),
                  None))

    specs.append(("C-21 area_mark.* 键被误删（独立界面 AreaMarkScreen 的键，§6 明令保留）",
                  ("§8 area_mark.*",),
                  lambda: copy_with(raw,
                                    lang_zh=dict((k, v) for k, v in (raw.get("lang_zh") or {}).items()
                                                 if not k.startswith(KEPT_LANG_PREFIX)),
                                    lang_en=dict((k, v) for k, v in (raw.get("lang_en") or {}).items()
                                                 if not k.startswith(KEPT_LANG_PREFIX))),
                  None))

    specs.append(("C-22 9 个新键缺一个（title.filtered 没落盘）",
                  ("§8 §6 的 9 个新键",),
                  lambda: drop_lang(raw, "lang_zh",
                                    "gui.rs_create_compat.area_strip.title.filtered"),
                  None))
    return specs


def move_change_rows_area_top(raw, literal):
    """把 rowsAreaTop() 里的 SEARCH_H 换成字面量（模拟「公式不再占搜索框」）。"""
    code = strip_comments(raw.get("panel") or "")
    item = find_method(code, "rowsAreaTop")
    if item is None:
        raise AnchorMissing("没有 rowsAreaTop()")
    body = item[3]
    if "SEARCH_H" not in body:
        raise AnchorMissing("rowsAreaTop() 里没有 SEARCH_H（实现未落地）")
    return method_replace(raw, "panel", "rowsAreaTop", body.replace("SEARCH_H", literal, 1))


def run_counterexamples(raw, base_bad):
    print("\n=== §9 反例自证（内存里改坏 / 纯字符串构造；判据必须判负，不写文件） ===")
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
            print("PASS 反例 %s%s ⇒ 新增判负 %d 项（例：%s）"
                  % (label, mode, len(fresh), hit[0]))
            continue
        base_hit = [name for name in base_bad if any(name.startswith(prefix) for prefix in targets)]
        if base_hit:
            COUNTER_PASS[0] += 1
            print("PASS 反例 %s%s（基线该判据本就红：%s，天然证明有区分力）"
                  % (label, mode, base_hit[0]))
            continue
        print("FAIL 反例 %s%s（改了却没多出目标判负；新增失败：%s）"
              % (label, mode, fresh[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 130 轮契约自检：区域条去「标记网格」+ 搜索 / 删除确认 / 世界框重武装 / 字体收敛")
    print("规格：%s §7" % CONTRACT)
    print("源码根：%s" % JAVA_REL.replace(os.sep, "/"))

    missing = [rel(path) for key, path in FILES.items() if not os.path.isfile(path)]
    if missing:
        print("\n[注意] 以下文件还不存在（实现未就绪时正常）：")
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
        print("（实现与规格尚未对齐时跑红是正常的；下面按 §7 编号列失败项）")
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
