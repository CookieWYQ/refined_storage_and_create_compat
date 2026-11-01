#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 123 轮自检：降解室发电机「界面 + 数据包 + 存档迁移」重做后的**新不变量**。

与 `selfcheck_round101_generator.py` 的分工
--------------------------------------------
round101 管「旧设计仍然成立的契约」（只出不可进 / 落盘 / 推送守恒 / 数据包容错 /
懒匹配 tag / 拆方块结算 / 语言键；它本轮只是把旧口径迁移到新设计）。
本脚本只钉**第 123 轮新引入**的东西，六组：

① 槽数对账：`DecompositionGeneratorSpec` ↔ `DecompositionGeneratorMenu` ↔ 构造器实际
   `addSlot` 次数，三处必须都是 **62 = 20 机器（16 输入 + 4 副产物）+ 6 升级 + 36 玩家**；
   槽位坐标全部引用 Spec（不许再写裸数字）。
② 数据包：`builtin_plants.json` 里 **没有** `minecraft:dead_bush`（用户硬约束「不做枯萎的」）、
   产物 id 形如 `ns:path`、`time/energy >= 1`、`chance ∈ [0,1]`、`count ∈ [1,64]`，
   且**没有**「压缩块 → 其原料」的无限电条目（干草块 / 海带块必须给骨粉）。
③ 副产物数值裁剪：`DecompositionProfile` 的 `time/energy/count/chance` 越界（含 NaN）一律夹住、
   **不抛异常**；这里既有 Java 文本断言，也有 Python 复刻的等价单测。
④ 旧存档迁移：256 格 → 20 格时物品总数守恒；放不下的进 `pendingDump` 并在首个 tick 用
   `Block.popResource` 掉出来；**绝不许**拿旧档自带的 `Size` 裸 `deserializeNBT`（会把 20 格撑回 256
   或把超界条目静默丢弃）。
⑤ 界面：贴图存在且尺寸 == Spec 面板尺寸、62 个槽格像素与 Spec 坐标逐格对齐、`.fill(` 0 处、
   `RsccEnergyBar.create` 恰 1 处、原版熔炉箭头 blit 恰 2 处（空 `(79,34)` / 亮 `(176,14)`）、
   文字走 `RsccTextFit.drawFitLine` 且无省略号。
   **第 126 轮更新**：用户把「状态 / 缓存 / 升级」三行文字整段删了（原话「这三行都是废话」），
   所以本脚本原先的「文字区 3 行、不压玩家背包槽框」两条改为判负的反向断言：
   `drawStatusTexts` 不许回来、`TEXT_*` 五个常量不许回来、三行文案的键不许再被引用。
⑥ 语言键：仍在用的 `energy`（能量条 tooltip）/ `progress_short`（箭头读数）在 zh_cn 与 en_us
   都存在、`%s` 个数一致且等于调用处实参个数；**三行文案的 5 个键**
   （`idle` / `working` / `buffer_full` / `output_full` / `upgrades`）已随三行文字一起删除、不许回潮；
   Lead 更早删的 3 个死键（`invalid_slots` / `page` / `progress`）同样不许回来。

用法：python tools/selfcheck_round123_decomp_redesign.py
退出码：0 = 全部通过；1 = 有问题。

注意：本脚本**只读**源码与资源，不改任何文件；也不需要启动游戏 / 编译。
"""
from __future__ import annotations

import json
import os
import re
import struct
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
    "spec": os.path.join(JAVA, "support", "DecompositionGeneratorSpec.java"),
    "profile": os.path.join(JAVA, "support", "DecompositionProfile.java"),
    "table": os.path.join(JAVA, "support", "DecompositionTable.java"),
    "menu": os.path.join(JAVA, "menu", "DecompositionGeneratorMenu.java"),
    "screen": os.path.join(JAVA, "client", "screen", "DecompositionGeneratorScreen.java"),
    "be": os.path.join(JAVA, "block", "entity", "DecompositionGeneratorBlockEntity.java"),
    "block": os.path.join(JAVA, "block", "DecompositionGeneratorBlock.java"),
    "builtin": os.path.join(RES, "data", MODID, "decomposition", "builtin_plants.json"),
    "lang_zh": os.path.join(RES, "assets", MODID, "lang", "zh_cn.json"),
    "lang_en": os.path.join(RES, "assets", MODID, "lang", "en_us.json"),
}
PNG = os.path.join(RES, "assets", MODID, "textures", "gui", "decomposition_generator.png")

# 旧设计（第 101–122 轮）的关键词：新设计里一个都不许留
DEAD_TOKENS = ("PAGE_SIZE", "PAGE_COUNT", "PAGE_ROWS", "PAGE_COLS", "PagedContainer",
               "PagedSlot", "DisplaySlot", "isLocalIndexUsable", "getDisplayHandler",
               "drawVerticalBar", "COLOR_ENERGY", "0xFFE0B32A", "invalid_slots", "drawCarriedHint")


def read(path: str) -> str:
    if not os.path.exists(path):
        return ""
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text: str) -> str:
    """只去注释、保留字符串字面量（判据要看得到 lang 键与 `.fill(` 这类代码特征）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


# =====================================================================================
# 一、极简 Java 常量求值（`static final int NAME = 纯算术/常量引用;`）
# =====================================================================================

_CONST_RE = re.compile(r"\bstatic\s+final\s+int\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);")
_IDENT_RE = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")
_JAVA_WORDS = {"int", "long", "short", "byte", "final"}


class Consts:
    """从多份 Java 源码里收 `static final int`，支持跨类常量引用（`Spec.X`）。"""

    def __init__(self):
        self.tables: dict[str, dict[str, str]] = {}

    def add(self, text: str) -> str:
        """登记一份源码，返回类名。"""
        text = strip_comments(text)
        match = re.search(r"\bclass\s+([A-Za-z_$][\w$]*)", text)
        cls = match.group(1) if match else "?"
        table = self.tables.setdefault(cls, {})
        for found in _CONST_RE.finditer(text):
            table[found.group(1)] = found.group(2).strip()
        return cls

    def get(self, cls: str, name: str, depth: int = 0) -> int:
        return self._eval_expr(self._raw(cls, name), cls, depth)

    def _raw(self, cls: str, name: str) -> str:
        if name in self.tables.get(cls, {}):
            return self.tables[cls][name]
        # 允许按「唯一名字」回退（例如从别的类里引用同义常量）；有歧义才报错
        owners = [owner for owner, table in self.tables.items() if name in table]
        if len(owners) == 1:
            return self.tables[owners[0]][name]
        raise KeyError("%s.%s" % (cls, name))

    def eval_expr(self, expr: str, cls: str, depth: int = 0) -> int:
        return self._eval_expr(expr, cls, depth)

    def _eval_expr(self, expr: str, cls: str, depth: int) -> int:
        if depth > 24:
            raise KeyError("常量递归过深: %r" % expr)
        text = re.sub(r"\s*\.\s*", ".", expr)
        text = re.sub(r"(?<=\d)_(?=\d)", "", text)  # Java 的 100_000_000 下划线字面量
        text = re.sub(r"\s+", " ", text).strip()

        def repl(found):
            token = found.group(0)
            if token in _JAVA_WORDS:
                return token
            if "." in token:
                owner, const = token.rsplit(".", 1)
                owner = owner.split(".")[-1]
                return "(%d)" % self.get(owner, const, depth + 1)
            return "(%d)" % self.get(cls, token, depth + 1)

        text = _IDENT_RE.sub(repl, text)
        if not re.fullmatch(r"[-+*/%()\s\d]+", text):
            raise KeyError("表达式含非算术成分: %r" % expr)
        return int(eval(text, {"__builtins__": {}}, {}))  # noqa: S307 - 只含数字与四则运算


def _match_delim(text: str, start: int) -> int:
    closer = {"(": ")", "{": "}", "[": "]"}.get(text[start])
    if closer is None:
        return -1
    depth = 0
    for index in range(start, len(text)):
        if text[index] == text[start]:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index
    return -1


_FOR_HEAD_RE = re.compile(
    r"for\s*\(\s*(?:final\s+)?int\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]+;\s*\1\s*<\s*([^;]+);")
_KEYWORD_RE = re.compile(r"(for|if|else|while|switch|try|catch|synchronized)\b")
_WORD_RE = re.compile(r"[A-Za-z_$][\w$]*")


def count_constructor_slots(text: str, cls: str, consts: Consts):
    """数构造器里 `addSlot` 的**实际执行次数**（外层 for 的重数相乘）。

    返回 (次数, 说明列表)。条件分支里的 addSlot 静态次数不可信 ⇒ 记一条说明（调用方据此判负）。
    """
    text = strip_comments(text)
    body = ""
    for found in re.finditer(r"\b(?:public|protected|private)\s+" + re.escape(cls) + r"\s*\(", text):
        paren = text.find("(", found.end() - 1)
        close = _match_delim(text, paren)
        if close < 0:
            continue
        cursor = close + 1
        while cursor < len(text) and text[cursor].isspace():
            cursor += 1
        if cursor < len(text) and text[cursor] == "{":
            end = _match_delim(text, cursor)
            if end > 0:
                candidate = text[cursor + 1:end]
                if candidate.count("addSlot(") > body.count("addSlot("):
                    body = candidate
    if not body:
        return 0, ["找不到构造器"]

    total = 0
    notes: list[str] = []
    stack: list[tuple[str, int | None]] = []
    index = 0
    length = len(body)
    while index < length:
        char = body[index]
        if char == "{":
            stack.append(("block", 1))
            index += 1
            continue
        if char == "}":
            if stack:
                stack.pop()
            index += 1
            continue
        boundary = index == 0 or not (body[index - 1].isalnum() or body[index - 1] in "_$")
        keyword = _KEYWORD_RE.match(body, index)
        if keyword and boundary:
            word = keyword.group(1)
            cursor = keyword.end()
            while cursor < length and body[cursor].isspace():
                cursor += 1
            if cursor < length and body[cursor] == "(":
                close = _match_delim(body, cursor)
                after = close + 1
                while after < length and body[after].isspace():
                    after += 1
                if after < length and body[after] == "{":
                    if word == "for":
                        head = _FOR_HEAD_RE.match(body, index)
                        if not head:
                            notes.append("for 上界不可解析（for-each / 复合条件）")
                            stack.append(("for", None))
                        else:
                            try:
                                stack.append(("for", consts.eval_expr(head.group(2).strip(), cls)))
                            except KeyError:
                                notes.append("for 上界求值失败: %r" % head.group(2).strip())
                                stack.append(("for", None))
                    elif word in ("if", "while", "switch", "catch"):
                        stack.append(("cond", 1))
                    else:
                        stack.append(("block", 1))
                    index = after + 1
                    continue
            if word == "else" and cursor < length and body[cursor] == "{":
                stack.append(("cond", 1))
                index = cursor + 1
                continue
            index = keyword.end()
            continue
        word_match = _WORD_RE.match(body, index)
        if word_match and boundary:
            word = word_match.group(0)
            if word == "addSlot":
                multiplier = 1
                conditional = False
                for kind, value in stack:
                    if kind == "cond":
                        conditional = True
                    elif kind == "for":
                        if value is None:
                            conditional = True
                        else:
                            multiplier *= value
                if conditional:
                    notes.append("addSlot 位于条件分支 / 上界不可解析处，静态次数不可信")
                    total += 1
                else:
                    total += multiplier
            index = word_match.end()
            continue
        index += 1
    return total, notes


# =====================================================================================
# 二、审计框架
# =====================================================================================

class Audit:
    def __init__(self):
        self.total = 0
        self.failures: list[str] = []
        self.notes: list[str] = []

    def expect(self, ok: bool, name: str, detail: str = "") -> bool:
        self.total += 1
        if not ok:
            self.failures.append(name + (("（%s）" % detail) if detail else ""))
        return ok

    def skip(self, name: str, why: str) -> None:
        self.notes.append("SKIP %s：%s" % (name, why))
        print("  [--] SKIP %s：%s" % (name, why))


def png_size(path: str):
    """读 PNG 的 IHDR（不依赖 Pillow）。返回 (宽, 高) 或 None。"""
    if not os.path.exists(path):
        return None
    with open(path, "rb") as handle:
        head = handle.read(33)
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def load_pixels(path: str):
    """用 Pillow 读像素；没装就返回 None（调用方 SKIP，不算失败）。"""
    try:
        from PIL import Image  # noqa: PLC0415 - 可选依赖，按需导入
    except Exception:  # pragma: no cover
        return None
    try:
        return Image.open(path).convert("RGBA").load()
    except Exception:  # pragma: no cover
        return None


# =====================================================================================
# ① 槽数对账
# =====================================================================================

def section_slots(a: Audit, src: dict[str, str]) -> None:
    consts = Consts()
    spec_cls = consts.add(src["spec"])
    menu_cls = consts.add(src["menu"])
    be_cls = consts.add(src["be"])

    input_count = consts.get(spec_cls, "INPUT_COUNT")
    output_count = consts.get(spec_cls, "OUTPUT_COUNT")
    inventory_size = consts.get(spec_cls, "INVENTORY_SIZE")
    upgrade_slots = consts.get(spec_cls, "UPGRADE_SLOTS")
    spec_player = consts.get(spec_cls, "PLAYER_SLOT_COUNT")

    a.expect(input_count == consts.get(spec_cls, "INPUT_COLS") * consts.get(spec_cls, "INPUT_ROWS") == 16,
             "Spec：INPUT_COUNT == INPUT_COLS × INPUT_ROWS == 16")
    a.expect(output_count == consts.get(spec_cls, "OUTPUT_COLS") * consts.get(spec_cls, "OUTPUT_ROWS") == 4,
             "Spec：OUTPUT_COUNT == OUTPUT_COLS × OUTPUT_ROWS == 4")
    a.expect(inventory_size == input_count + output_count == 20,
             "Spec：INVENTORY_SIZE == 16 + 4 == 20", "inventory_size=%d" % inventory_size)
    a.expect(upgrade_slots == consts.get(spec_cls, "UPGRADE_COLS") * consts.get(spec_cls, "UPGRADE_ROWS") == 6,
             "Spec：UPGRADE_SLOTS == 3 × 2 == 6")
    a.expect(spec_player == 27 + 9 == 36,
             "Spec：PLAYER_SLOT_COUNT == 27 + 9 == 36（原版 Inventory 只有 36 格，122 轮崩在 45）")

    machine_start = consts.get(menu_cls, "MACHINE_SLOT_START")
    machine_end = consts.get(menu_cls, "MACHINE_SLOT_END")
    upgrade_start = consts.get(menu_cls, "UPGRADE_SLOT_START")
    upgrade_end = consts.get(menu_cls, "UPGRADE_SLOT_END")
    player_start = consts.get(menu_cls, "PLAYER_SLOT_START")
    player_end = consts.get(menu_cls, "PLAYER_SLOT_END")
    total = consts.get(menu_cls, "TOTAL_SLOT_COUNT")

    a.expect(machine_start == 0 and machine_end - machine_start == inventory_size,
             "Menu：机器槽 [0, %d) == Spec.INVENTORY_SIZE" % inventory_size,
             "[%d, %d)" % (machine_start, machine_end))
    a.expect(upgrade_start == machine_end and upgrade_end - upgrade_start == upgrade_slots,
             "Menu：升级槽紧跟机器槽，宽 == Spec.UPGRADE_SLOTS",
             "[%d, %d)" % (upgrade_start, upgrade_end))
    a.expect(player_start == upgrade_end and player_end - player_start == spec_player,
             "Menu：玩家区紧跟升级槽，宽 == 36", "[%d, %d)" % (player_start, player_end))
    a.expect(total == player_end == inventory_size + upgrade_slots + spec_player == 62,
             "Menu：TOTAL_SLOT_COUNT == 20 + 6 + 36 == 62", "total=%d" % total)

    ctor_total, ctor_notes = count_constructor_slots(src["menu"], menu_cls, consts)
    a.expect(ctor_total == total,
             "构造器 addSlot 实际次数 == 常量声明的 TOTAL_SLOT_COUNT",
             "实际 %d vs 常量 %d" % (ctor_total, total))
    a.expect(not ctor_notes, "构造器槽位计数无歧义（无 if 分支 / 无可变上界）", "; ".join(ctor_notes))
    addslot_calls = strip_comments(src["menu"]).count("addSlot(")
    a.expect(addslot_calls == 5,
             "构造器只有 5 处 addSlot（输入 / 副产物 / 升级 / 主背包 / 快捷栏）",
             "addSlot=%d" % addslot_calls)
    a.expect(consts.get(menu_cls, "DATA_SLOT_COUNT") == consts.get(be_cls, "DATA_SLOT_COUNT") == 14,
             "Menu.DATA_SLOT_COUNT == BlockEntity.DATA_SLOT_COUNT == 14")

    # 升级容器访问器：契约文档写 getUpgrades()，实际源码里被接口占成 List 版，容器叫 getUpgradeContainer()
    a.expect(re.search(r"public\s+RsccSpeedStackUpgradeContainer\s+getUpgradeContainer\s*\(\s*\)",
                       strip_comments(src["be"])) is not None,
             "方块实体提供容器访问器 getUpgradeContainer()（返回 RsccSpeedStackUpgradeContainer）")
    a.expect("upgradeHandler = generator.getUpgradeContainer();" in strip_comments(src["menu"]),
             "Menu 服务端槽位用的是 generator.getUpgradeContainer()（同一份容器，不是副本）")
    a.expect("generator.getInventory()" in strip_comments(src["menu"]),
             "Menu 服务端槽位用的是 generator.getInventory()（同一份 20 格库存）")

    # 槽位坐标必须全部来自 Spec（贴图生成脚本与 Menu 共用同一份常量 ⇒ 不可能「背景画 A、槽落 B」）
    menu_text = strip_comments(src["menu"])
    slot_args = []
    for found in re.finditer(r"addSlot\s*\(", menu_text):
        paren = menu_text.find("(", found.end() - 1)
        close = _match_delim(menu_text, paren)
        if close > 0:
            slot_args.append(menu_text[paren + 1:close])
    naked = [args for args in slot_args
             if "DecompositionGeneratorSpec." not in args or re.search(r"\d+", args)]
    a.expect(len(slot_args) == 5 and not naked,
             "5 处 addSlot 的坐标参数全部引用 DecompositionGeneratorSpec，且没有一个裸数字字面量",
             "有问题的处数=%d %s" % (len(naked), naked[:1]))

    # 几何常量必须齐备（面板 / 箭头 / 能量条 / 内容留白 / 玩家区）
    for name in ("PANEL_WIDTH", "PANEL_HEIGHT", "INPUT_X", "INPUT_Y", "OUTPUT_X", "OUTPUT_Y",
                 "UPGRADE_X", "UPGRADE_Y", "ARROW_X", "ARROW_Y", "ARROW_WIDTH", "ARROW_HEIGHT",
                 "ENERGY_X", "ENERGY_Y", "ENERGY_HEIGHT", "CONTENT_INSET",
                 "PLAYER_INV_X", "PLAYER_INV_Y", "HOTBAR_Y"):
        try:
            value = consts.get(spec_cls, name)
            ok = value >= 0
        except KeyError:
            ok = False
        a.expect(ok, "Spec 定义了 %s" % name)
    a.expect(consts.get(spec_cls, "ARROW_WIDTH") == 24 and consts.get(spec_cls, "ARROW_HEIGHT") == 16,
             "熔炉箭头精灵尺寸 24×16（原版 sprite 尺寸，不许改）")
    # ---- 第 126 轮：三行文字整段删除（用户原话「这三行都是废话」）----
    # 判负式断言比「存在性」更严：以后任何人把 TEXT_* 常量或三行文案加回来都会立刻红。
    resurrected_text = [name for name in ("TEXT_X", "TEXT_Y", "TEXT_LINE_STEP", "TEXT_MAX_WIDTH",
                                          "TEXT_LINES")
                        if any(re.search(r"\b%s\b" % re.escape(name), text)
                               for text in (strip_comments(src["spec"]),
                                            strip_comments(src["screen"])))]
    a.expect(not resurrected_text,
             "第 126 轮删掉的 TEXT_* 五个常量不许回潮（Spec 与 Screen 都不许再出现）",
             "回潮=%s" % resurrected_text)
    a.expect(consts.get(spec_cls, "CONTENT_INSET") == 8,
             "内容留白 CONTENT_INSET == 8（标题宽度 = 面板宽 - 2×本值）",
             str(consts.get(spec_cls, "CONTENT_INSET")))


# =====================================================================================
# ② 数据包
# =====================================================================================

def _products_of(entry: dict) -> list[dict]:
    has_results = entry.get("results") is not None
    raw = entry.get("results") if has_results else entry.get("result")
    if raw is None:
        return []
    return [product for product in (raw if isinstance(raw, list) else [raw]) if isinstance(product, dict)]


def section_datapack(a: Audit, src: dict[str, str]) -> None:
    try:
        pack = json.loads(src["builtin"])
    except json.JSONDecodeError as error:
        a.expect(False, "builtin_plants.json 是合法 JSON", str(error))
        return
    entries = pack.get("entries")
    a.expect(isinstance(entries, list) and len(entries) >= 40,
             "builtin_plants.json 有 entries 且条数 ≥ 40", str(len(entries) if entries else 0))
    if not isinstance(entries, list):
        return

    inputs: list[str] = []
    products: list[str] = []
    problems: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            problems.append("entries[%d] 不是对象" % index)
            continue
        item = entry.get("item")
        tag = entry.get("tag")
        if (isinstance(item, str)) == (isinstance(tag, str)):
            problems.append("entries[%d] item/tag 必须且只能有一个" % index)
        if isinstance(item, str):
            inputs.append(item.strip())
        if isinstance(tag, str) and not tag.strip():
            problems.append("entries[%d] tag 为空" % index)
        time = entry.get("time")
        energy = entry.get("energy")
        if not isinstance(time, int) or isinstance(time, bool) or time < 1:
            problems.append("entries[%d] time=%r < 1" % (index, time))
        if not isinstance(energy, int) or isinstance(energy, bool) or energy < 1:
            problems.append("entries[%d] energy=%r < 1" % (index, energy))
        if entry.get("results") is not None and entry.get("result") is not None:
            problems.append("entries[%d] 同时写了 results 与 result" % index)
        for product in _products_of(entry):
            product_id = product.get("item")
            if not isinstance(product_id, str) or ":" not in product_id:
                problems.append("entries[%d] 产物 id 不是 ns:path: %r" % (index, product_id))
                continue
            products.append(product_id.strip())
            count = product.get("count", 1)
            if isinstance(count, bool) or not isinstance(count, int) or not (1 <= count <= 64):
                problems.append("entries[%d] 产物 count=%r 不在 [1,64]" % (index, count))
            chance = product.get("chance", 1.0)
            if isinstance(chance, bool) or not isinstance(chance, (int, float)) \
                    or not (0.0 <= float(chance) <= 1.0):
                problems.append("entries[%d] 产物 chance=%r 不在 [0,1]" % (index, chance))
    a.expect(not problems, "数据包所有条目字段 / 数值区间合法（time,energy ≥ 1 / count 1..64 / chance 0..1）",
             "; ".join(problems[:4]))

    # 用户硬约束 1：枯萎类一律不收（输入与产物都不许出现）
    decay = [item for item in inputs + products if "dead_" in item]
    a.expect(not decay, "数据包不含任何枯萎类 id（用户硬约束：只做植物、且不是枯萎的）", str(decay[:4]))
    a.expect("minecraft:dead_bush" not in inputs + products,
             "minecraft:dead_bush 必须缺席（旧表有、第 123 轮删掉）")
    a.expect(all(":" in product_id and all(part.strip()
                                           for part in product_id.split(":", 1))
                 for product_id in products),
             "所有产物 id 形如 ns:path", str([pid for pid in products if ":" not in pid][:4]))

    # 用户硬约束 2：压缩块不许退回原料（否则「合成 → 降解 → 再合成」= 无限电）
    no_infinite = {"minecraft:hay_block": {"minecraft:wheat"},
                   "minecraft:dried_kelp_block": {"minecraft:dried_kelp"}}
    offenders = []
    compressed_products = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        source_id = entry.get("item")
        if not isinstance(source_id, str):
            continue
        raw_materials = no_infinite.get(source_id)
        entry_products = _products_of(entry)
        compressed_products[source_id] = [(p.get("item"), p.get("count")) for p in entry_products]
        if not raw_materials:
            continue
        for product in entry_products:
            if product.get("item") in raw_materials:
                offenders.append((source_id, product.get("item"), product.get("count")))
    a.expect(not offenders, "压缩块不退回原料（禁止无限电条目）", str(offenders))
    hay = compressed_products.get("minecraft:hay_block") or []
    kelp = compressed_products.get("minecraft:dried_kelp_block") or []
    a.expect(("minecraft:bone_meal", 9) in hay,
             "干草块 → 骨粉 ×9（不是 9 个小麦）", str(hay))
    a.expect(("minecraft:bone_meal", 9) in kelp,
             "海带块 → 骨粉 ×9（不是 9 个干海带）", str(kelp))

    # 副产物与重复条目：同一 item 在一个文件里只许出现一次（后写覆盖前写会让平衡表难读）
    duplicated = sorted({item for item in inputs if inputs.count(item) > 1})
    a.expect(not duplicated, "内置表没有重复的 item 条目", str(duplicated[:4]))


# =====================================================================================
# ③ 副产物数值裁剪
# =====================================================================================

PROFILE_MIN_TIME = 1
PROFILE_MAX_TIME = 20 * 60 * 10
PROFILE_MAX_ENERGY = 100_000_000
PROFILE_MIN_COUNT = 1
PROFILE_MAX_COUNT = 64
PROFILE_MIN_CHANCE = 0.0
PROFILE_MAX_CHANCE = 1.0


def _clamp(value, low, high):
    return max(low, min(high, value))


def clamp_profile(time: int, energy: int):
    """复刻 `DecompositionProfile` 紧凑构造器：time/energy 一律夹到合法区间。"""
    return (_clamp(int(time), PROFILE_MIN_TIME, PROFILE_MAX_TIME),
            _clamp(int(energy), 1, PROFILE_MAX_ENERGY))


def clamp_result(count: int, chance: float):
    """复刻 `DecompositionResult`：count 夹到 1..64、chance 夹到 0..1、NaN 按 0。"""
    if chance != chance:  # NaN：NaN != NaN
        chance = PROFILE_MIN_CHANCE
    return (_clamp(int(count), PROFILE_MIN_COUNT, PROFILE_MAX_COUNT),
            _clamp(float(chance), PROFILE_MIN_CHANCE, PROFILE_MAX_CHANCE))


def section_profile_clamp(a: Audit, src: dict[str, str]) -> None:
    code = strip_comments(src["profile"])
    a.expect("public record DecompositionProfile(int time, int energy" in code,
             "DecompositionProfile 是 record，且 results 已在第 123 轮加入")
    a.expect("time = clamp(time, MIN_TIME, MAX_TIME);" in code,
             "紧凑构造器把 time 夹到 [MIN_TIME, MAX_TIME]")
    a.expect("energy = clamp(energy, 1, MAX_ENERGY);" in code,
             "紧凑构造器把 energy 夹到 [1, MAX_ENERGY]")
    a.expect("results == null || results.isEmpty()" in code and "List.of()" in code,
             "results 的 null / 空列表统一成 List.of()（读方不必判 null）")
    a.expect("count = clampCount(count);" in code, "DecompositionResult 夹 count")
    a.expect("chance = clampChance(chance);" in code, "DecompositionResult 夹 chance")
    a.expect("Float.isNaN(value)" in code and "return MIN_CHANCE;" in code,
             "chance 为 NaN 时按 0 处理（「永远不出」比「永远出」安全）")
    a.expect("item = Objects.requireNonNull(item" in code, "产物 item 非空（防御性）")
    a.expect(not re.search(r"throw new", code),
             "裁剪路径**不抛异常**（数据包一个笔误不该让整台机器 / 整个重载炸掉）")
    for constant, expected in (("MIN_TIME = 1;", 1), ("MAX_TIME = 20 * 60 * 10;", 12000),
                               ("MAX_ENERGY = 100_000_000;", 100_000_000),
                               ("MIN_COUNT = 1;", 1), ("MAX_COUNT = 64;", 64)):
        a.expect(constant in code, "常量 %s" % constant)
    a.expect("MIN_CHANCE = 0.0F;" in code and "MAX_CHANCE = 1.0F;" in code,
             "chance 区间常量 0.0F / 1.0F")
    consts = Consts()
    profile_cls = consts.add(src["profile"])
    a.expect(consts.get(profile_cls, "MAX_TIME") == PROFILE_MAX_TIME
             and consts.get(profile_cls, "MAX_ENERGY") == PROFILE_MAX_ENERGY
             and consts.get(profile_cls, "MAX_COUNT") == PROFILE_MAX_COUNT,
             "Java 里的上限常量与自检复刻的数值一致")

    # Python 复刻单测：0 / 负数 / 超大 / NaN 都必须被夹住，且**不抛异常**
    cases = [
        ((0, 0), (1, 1)),
        ((-5, -100), (1, 1)),
        ((PROFILE_MAX_TIME + 1, PROFILE_MAX_ENERGY * 10), (PROFILE_MAX_TIME, PROFILE_MAX_ENERGY)),
        ((10, 25), (10, 25)),
        ((2 ** 31 - 1, 2 ** 31 - 1), (PROFILE_MAX_TIME, PROFILE_MAX_ENERGY)),
    ]
    a.expect(all(clamp_profile(*raw) == expected for raw, expected in cases),
             "time / energy 的 0 / 负数 / 超大值都被夹住（Python 复刻单测）",
             str([(raw, clamp_profile(*raw)) for raw, expected in cases if clamp_profile(*raw) != expected]))
    chance_cases = [(float("nan"), 0.0), (-1.0, 0.0), (2.0, 1.0), (0.25, 0.25), (1.0, 1.0)]
    a.expect(all(clamp_result(1, raw)[1] == expected for raw, expected in chance_cases),
             "chance 的 NaN / 负数 / >1 都被夹住",
             str([(raw, clamp_result(1, raw)[1]) for raw, expected in chance_cases
                  if clamp_result(1, raw)[1] != expected]))
    count_cases = [(0, 1), (-3, 1), (65, 64), (999, 64), (9, 9)]
    a.expect(all(clamp_result(raw, 1.0)[0] == expected for raw, expected in count_cases),
             "count 的 0 / 负数 / 超大值都被夹到 [1, 64]",
             str([(raw, clamp_result(raw, 1.0)[0]) for raw, expected in count_cases
                  if clamp_result(raw, 1.0)[0] != expected]))
    # 裁剪计数要能被作者看到（LoadReport.clamped）
    a.expect("clamped" in src["table"] and "LoadReport" in src["table"],
             "裁剪次数进重载汇总（LoadReport.clamped），作者能从一条日志知道数据包写超了")


# =====================================================================================
# ④ 旧存档迁移（256 格 → 20 格，物品守恒）
# =====================================================================================

MIGRATION_INPUT_COUNT = 16
MIGRATION_OUTPUT_COUNT = 4
MIGRATION_INPUT_LIMIT = 64
MIGRATION_OUTPUT_LIMIT = 64
MIGRATION_PENDING_LIMIT = 64


def migrate_model(legacy: list[tuple[str, int]], decomposable=None,
                  input_limit: int = MIGRATION_INPUT_LIMIT):
    """复刻 `readInventoryMigrating` + `migrateStack` + `depositIntoFour` + `addToPendingDump`。

    `legacy` = 旧档逐格物品（空槽已滤掉）。返回 (inputs, outputs, pending, 输入总数)。
    与 Java 同口径：可降解物 → 16 输入格（每格 input_limit）→ 其余 → 4 副产物格（每格 64、不混装）
    → 仍放不下 → pendingDump（同类并堆，受物品栈上限 64 约束）。**绝不销毁、绝不复制**。
    """
    if decomposable is None:
        decomposable = lambda _item: True  # noqa: E731 - 测试用
    inputs: list[list | None] = [None] * MIGRATION_INPUT_COUNT
    outputs: list[list | None] = [None] * MIGRATION_OUTPUT_COUNT
    pending: list[list] = []
    total = 0

    def deposit(slots, capacity, item, count):
        for index in range(len(slots)):
            existing = slots[index]
            if existing is not None and existing[0] != item:
                continue
            used = 0 if existing is None else existing[1]
            room = capacity - used
            if room <= 0:
                continue
            moved = min(room, count)
            slots[index] = [item, used + moved]
            count -= moved
            if count <= 0:
                return 0
        return count

    for item, count in legacy:
        total += count
        remaining = count
        if decomposable(item):
            remaining = deposit(inputs, input_limit, item, remaining)
        if remaining > 0:
            remaining = deposit(outputs, MIGRATION_OUTPUT_LIMIT, item, remaining)
        for index in range(len(pending)):  # addToPendingDump：先并堆
            if remaining <= 0:
                break
            if pending[index][0] != item:
                continue
            room = MIGRATION_PENDING_LIMIT - pending[index][1]
            if room <= 0:
                continue
            moved = min(room, remaining)
            pending[index][1] += moved
            remaining -= moved
        while remaining > 0:
            moved = min(MIGRATION_PENDING_LIMIT, remaining)
            pending.append([item, moved])
            remaining -= moved
    return inputs, outputs, pending, total


def _count_sum(stacks) -> int:
    return sum(0 if slot is None else slot[1] for slot in stacks)


def section_migration(a: Audit, src: dict[str, str]) -> None:
    be = strip_comments(src["be"])
    a.expect("private boolean readInventoryMigrating(" in be,
             "读库存走 readInventoryMigrating（旧档迁移的唯一入口）")
    a.expect("declaredSize > DecompositionGeneratorSpec.INVENTORY_SIZE" in be
             and "highestSlot >= DecompositionGeneratorSpec.INVENTORY_SIZE" in be,
             "旧档判据 = Size > 20 **或** Items 里出现下标 ≥ 20（两个判据任一命中）")
    a.expect("highestUsedSlot(inventoryTag)" in be and '"Size"' in be,
             "用「最高下标」兜底 Size 写错的情况")
    a.expect("new ItemStackHandler(legacySize)" in be,
             "旧档先按**旧尺寸**建临时 handler（否则 236 格物品被静默丢弃）")
    a.expect(re.search(r"legacyHandler\.deserializeNBT\(registries,\s*normalizedInventoryTag\(", be) is not None,
             "临时 handler 用「Size 已归位」的 NBT 反序列化")
    a.expect(re.search(r"inventory\.deserializeNBT\(registries,\s*normalizedInventoryTag\(", be) is not None,
             "新格式分支同样先把 Size 归位到 20（坏档 Size<20 会让布局错位）")
    a.expect(not re.search(r"\.deserializeNBT\(registries,\s*inventoryTag\)", be),
             "绝不用旧档自带的 Size 裸 deserializeNBT（会把 20 格撑回 256 / 静默丢条目）")
    a.expect('normalized.putInt("Size", targetSize)' in be,
             "normalizedInventoryTag 会复制一份并改写 Size（不改原 NBT）")
    a.expect("MAX_LEGACY_INVENTORY_SLOTS = 4096;" in be and "LOGGER.warn" in be,
             "旧档 Size 有硬上限（4096）并警告，防被改坏的档分配天文数组")
    a.expect("private ItemStack migrateStack(final ItemStack stack)" in be,
             "逐格搬运走 migrateStack（可降解 → 输入，其余 → 副产物）")
    a.expect("tag.put(TAG_PENDING_DUMP, list)" in be
             and "tag.getList(TAG_PENDING_DUMP, Tag.TAG_COMPOUND)" in be,
             "pendingDump 随 NBT 落盘并在读档时恢复（读档到首个 tick 之间崩溃也不丢）")
    a.expect("Block.popResource(level, worldPosition, stack.copy())" in be,
             "pendingDump 在服务端 tick 用 Block.popResource 掉到方块位置")
    a.expect("writePendingDump(tag, registries)" in be and "readPendingDump(tag, registries)" in be,
             "落盘 / 读档都走 writePendingDump / readPendingDump")

    # 迁移前后顺序（同一实例反复喂旧 NBT 不能叠加）
    migration_body = be[be.find("private boolean readInventoryMigrating("):]
    migration_body = migration_body[:migration_body.find("\n    private static int highestUsedSlot")]

    a.expect("addToPendingDump(leftover)" in migration_body,
             "迁移时仍放不下的余量进 pendingDump（不销毁）")
    a.expect(be.count("addToPendingDump(") >= 2,
             "pendingDump 的两个入口都在（迁移余量 + 结算时理论不可达的余量）",
             "count=%d" % be.count("addToPendingDump("))
    clear_at = migration_body.find("clearInventoryForMigration();")
    loop_at = migration_body.find("for (int slot = 0; slot < legacyHandler.getSlots(); slot++)")
    a.expect(0 <= clear_at < loop_at,
             "迁移前先清空 20 格库存（同一实例反复喂旧 NBT 不叠加）",
             "clear=%d loop=%d" % (clear_at, loop_at))
    a.expect(0 <= migration_body.find("pendingDump.clear();") < loop_at,
             "迁移前先清空 pendingDump")
    a.expect(migration_body.count("migratingInventory = true;") >= 1
             and "migratingInventory = false;" in migration_body,
             "迁移期间抑制逐格 setChanged（一批 setStackInSlot 只标一次脏）")

    # 读档完成后的进度处理：旧档 currentSlot 是 256 格下标，新布局无对应语义 ⇒ 一律作废
    load_body = be[be.find("public void loadAdditional("):]
    load_body = load_body[:load_body.find("\n    /**", 10)]
    a.expect("if (migrated) {" in load_body and "abortCurrent();" in load_body,
             "迁移后旧 currentSlot 一律作废（物品已全部搬进新库存，绝不因进度对不上丢东西）")
    read_call = load_body.find("readInventoryMigrating(tag, registries)")
    a.expect(0 <= load_body.find("upgrades.beginLoading();") < read_call,
             "先读升级容器再读库存（迁移时的每格上限要用真实堆叠升级数）")
    a.expect(read_call < load_body.find("upgrades.finishLoading();"),
             "升级容器的统一校验推迟到读完（finishLoading 在 try 之后）")

    # 纯函数式守恒模型：迁移 = 位置重排，物品总数一个不差
    cases = [
        ("旧档 256 格各 1 件可降解物（共 256 件）", [("plant", 1)] * 256, None),
        ("旧档 2000 件同类可降解物（输入 1024 + 副产物 256 + 待掉出 720）", [("plant", 2000)], None),
        ("旧档 300 件不可降解残留（副产物 256 + 待掉出 44）", [("stone", 300)],
         lambda item: item == "plant"),
        ("旧档混合：100 可降解 + 100 不可降解", [("plant", 100), ("stone", 100)],
         lambda item: item == "plant"),
        ("旧档 4096 件（硬上限以内的最坏情况）", [("plant", 4096)], None),
    ]
    bad = []
    for label, legacy, predicate in cases:
        inputs, outputs, pending, total = migrate_model(legacy, predicate)
        conserved = _count_sum(inputs) + _count_sum(outputs) + _count_sum(pending) == total
        no_lost = _count_sum(pending) >= 0 and all(slot is None or slot[1] <= MIGRATION_OUTPUT_LIMIT
                                                  for slot in outputs)
        if not (conserved and no_lost):
            bad.append((label, total, _count_sum(inputs), _count_sum(outputs), _count_sum(pending)))
    a.expect(not bad, "迁移只重排位置、物品总数守恒（输入 + 副产物 + pendingDump == 旧档总数）", str(bad[:2]))

    inputs, outputs, pending, total = migrate_model([("plant", 2000)])
    a.expect(_count_sum(inputs) == MIGRATION_INPUT_COUNT * MIGRATION_INPUT_LIMIT
             and _count_sum(outputs) == MIGRATION_OUTPUT_COUNT * MIGRATION_OUTPUT_LIMIT
             and _count_sum(pending) == 2000 - 1024 - 256,
             "塞爆时的分配符合预期：输入 1024 + 副产物 256 + 待掉出 720",
             "%d/%d/%d" % (_count_sum(inputs), _count_sum(outputs), _count_sum(pending)))
    inputs, outputs, _pending, _total = migrate_model([("plant", 100), ("stone", 100)],
                                                     lambda item: item == "plant")
    a.expect(inputs[0] == ["plant", 64] and _count_sum(inputs) == 100
             and _count_sum(outputs) == 100,
             "可降解物优先进输入格，不可降解残留进副产物格（玩家能原样取走）")
    inputs, _outputs, _pending, _total = migrate_model([("plant", 10)], input_limit=448)
    a.expect(inputs[0] == ["plant", 10],
             "输入格上限随堆叠升级（448 / 格）由调用方传入，迁移不写死 64")


# =====================================================================================
# ⑤ 界面（贴图 / 熔炉箭头 / 唯一能量条 / 文字不压槽）
# =====================================================================================

FONT_LINE_HEIGHT = 9  # 原版默认字体行高（文字区不压槽的判据要用）


def section_ui(a: Audit, src: dict[str, str], png_dimensions=None) -> None:
    consts = Consts()
    spec_cls = consts.add(src["spec"])
    panel_width = consts.get(spec_cls, "PANEL_WIDTH")
    panel_height = consts.get(spec_cls, "PANEL_HEIGHT")
    screen = strip_comments(src["screen"])

    # ---- 贴图 ----
    size = png_dimensions if png_dimensions is not None else png_size(PNG)
    a.expect(size == (panel_width, panel_height),
             "textures/gui/decomposition_generator.png 存在且尺寸 == Spec 面板尺寸",
             "实测 %r vs 期望 %r" % (size, (panel_width, panel_height)))
    a.expect("textures/gui/decomposition_generator.png" in screen,
             "Screen 引用的是这张面板贴图")
    a.expect(screen.count("guiGraphics.blit(TEXTURE,") == 1,
             "背景一次 blit 画完（不是逐槽 / 逐面板 fill）",
             "count=%d" % screen.count("guiGraphics.blit(TEXTURE,"))
    a.expect(".fill(" not in screen,
             "Screen 里 .fill( 为 0 处（面板 / 边框 / 槽框全在 PNG 里）")

    # ---- 62 个槽格与 Spec 坐标逐格对齐（贴图 = Menu 几何的唯一证据） ----
    slots = []
    regions = (
        (consts.get(spec_cls, "INPUT_X"), consts.get(spec_cls, "INPUT_Y"),
         consts.get(spec_cls, "INPUT_COLS"), consts.get(spec_cls, "INPUT_ROWS")),
        (consts.get(spec_cls, "OUTPUT_X"), consts.get(spec_cls, "OUTPUT_Y"),
         consts.get(spec_cls, "OUTPUT_COLS"), consts.get(spec_cls, "OUTPUT_ROWS")),
        (consts.get(spec_cls, "UPGRADE_X"), consts.get(spec_cls, "UPGRADE_Y"),
         consts.get(spec_cls, "UPGRADE_COLS"), consts.get(spec_cls, "UPGRADE_ROWS")),
        (consts.get(spec_cls, "PLAYER_INV_X"), consts.get(spec_cls, "PLAYER_INV_Y"),
         consts.get(spec_cls, "PLAYER_COLS"), consts.get(spec_cls, "PLAYER_INV_ROWS")),
        (consts.get(spec_cls, "PLAYER_INV_X"), consts.get(spec_cls, "HOTBAR_Y"),
         consts.get(spec_cls, "PLAYER_COLS"), 1),
    )
    pitch = consts.get(spec_cls, "SLOT_PITCH")
    for origin_x, origin_y, cols, rows in regions:
        for col in range(cols):
            for row in range(rows):
                slots.append((origin_x + col * pitch, origin_y + row * pitch))
    a.expect(len(slots) == 62,
             "Spec 几何算出的槽位总数 == 62（16 + 4 + 6 + 27 + 9）", "实测 %d" % len(slots))

    pixels = load_pixels(PNG)
    if pixels is None:
        a.skip("贴图 62 槽格像素对齐", "没有 Pillow 或贴图不可读")
    else:
        signatures = set()
        transparent = []
        for x, y in slots:
            border = pixels[x, y]
            inner = pixels[x + 1, y + 1]
            signatures.add((border, inner))
            if border[3] != 255 or inner[3] != 255:
                transparent.append((x, y))
        a.expect(not transparent,
                 "62 个槽格边框 / 内底全不透明（槽格真的贴在面板上）", str(transparent[:4]))
        a.expect(len(signatures) == 1,
                 "62 个槽格像素完全一致 ⇒ 同一张 slot.png 贴在 Spec 坐标上（不是「背景画 A、槽落 B」）",
                 "不同签名 %d 种" % len(signatures))

    # ---- 熔炉箭头 ----
    a.expect(screen.count("guiGraphics.blit(FURNACE_TEXTURE,") == 2,
             "原版熔炉箭头恰好 2 次 blit（空箭头底 + 亮起部分）",
             "count=%d" % screen.count("guiGraphics.blit(FURNACE_TEXTURE,"))
    a.expect("ARROW_EMPTY_U = 79;" in screen and "ARROW_EMPTY_V = 34;" in screen,
             "空箭头精灵 (79,34)（原版 AbstractFurnaceScreen 口径）")
    a.expect("ARROW_LIT_U = 176;" in screen and "ARROW_LIT_V = 14;" in screen,
             "亮起箭头精灵 (176,14)（原版口径）")
    a.expect("container/furnace.png" in screen,
             "引用 vanilla furnace.png（不新增任何进度贴图）")
    a.expect("final int filledWidth = 1 + (int) Math.round(" in screen
             and "progressFraction() * (DecompositionGeneratorSpec.ARROW_WIDTH - 1)" in screen,
             "亮起宽度 = 1 + round(fraction × (箭头宽 - 1))（0% 也亮 1px、100% 恰好铺满）")
    a.expect(re.search(r"blit\(FURNACE_TEXTURE,[^;]*?filledWidth", screen) is not None,
             "亮起那一 blit 用的是 filledWidth（不是写死 24 / 写死 0）")

    # ---- 唯一一条能量条 ----
    a.expect(screen.count("RsccEnergyBar.create(") == 1,
             "本界面只有一条 RsccEnergyBar（create 恰 1 处）",
             "count=%d" % screen.count("RsccEnergyBar.create("))
    a.expect(screen.count("RsccEnergyBar.drawTrack(") == 1,
             "能量条槽底只画一次（drawTrack 恰 1 处）",
             "count=%d" % screen.count("RsccEnergyBar.drawTrack("))
    a.expect("DecompositionGeneratorSpec.ENERGY_X" in screen
             and "DecompositionGeneratorSpec.ENERGY_Y" in screen
             and "DecompositionGeneratorSpec.ENERGY_HEIGHT" in screen,
             "能量条位置 / 高度全部取自 Spec")

    # ---- 文字（第 126 轮：三行固定读数已删，只留标题 + 箭头进度数字）----
    a.expect("RsccTextFit.drawFitLine(" in screen,
             "文字走 RsccTextFit.drawFitLine（放不下整体缩字号）")
    a.expect("…" not in screen and "..." not in screen,
             "Screen 里没有省略号（放不下就缩字号，绝不截断）")
    # 三行「状态 / 缓存 / 升级」是用户第 126 轮点名删掉的，判负式断言：任何一行回来都算退化。
    resurrected_lines = [key for key in
                         ("idle", "working", "buffer_full", "output_full", "upgrades")
                         if re.search(r'"gui\.%s\.decomposition_generator\.%s"'
                                      % (re.escape(MODID), re.escape(key)), screen)]
    a.expect(not resurrected_lines,
             "三行固定读数（状态 / 缓存 / 升级）不许回来",
             "回潮的键=%s" % resurrected_lines)
    a.expect("drawStatusTexts" not in screen,
             "drawStatusTexts 方法已整段删除（三行文本的唯一画法，不许留半截）")
    a.expect("menu.getSpeedCount()" not in screen and "menu.getLastGenerated()" not in screen,
             "Screen 不再读取升级数 / 上次发电量（这两项只属于那两行废文本）")
    a.expect("this.imageWidth = DecompositionGeneratorSpec.PANEL_WIDTH;" in screen
             and "this.imageHeight = DecompositionGeneratorSpec.PANEL_HEIGHT;" in screen,
             "Screen 的画布尺寸取自 Spec（不是硬编码 182×206/212）")
    a.expect("menu.getProgress()" in screen and "menu.getProgressTotal()" in screen,
             "进度读数来自数据槽（界面不自己算）")
    a.expect("Config." not in screen, "界面不读本地 Config（专服客户端可能不同）")


# =====================================================================================
# ⑥ 语言键
# =====================================================================================

LIVE_LANG_KEYS = {
    "energy": 2,          # 能量条 tooltip：缓存：%s / %s FE（第 126 轮后只剩 tooltip 在用）
    "progress_short": 2,  # 箭头读数：%s / %s 刻
}
# 三行废文本的键：第 126 轮随三行一起删掉，不许回潮（audit_lang_keys 也会判「孤儿键」）。
DEAD_LANG_KEYS = ("invalid_slots", "page", "progress",
                  "idle", "working", "buffer_full", "output_full", "upgrades")


def lang_placeholder_count(value: str) -> int:
    return value.count("%s")


def section_lang(a: Audit, src: dict[str, str]) -> None:
    try:
        zh = json.loads(src["lang_zh"])
        en = json.loads(src["lang_en"])
    except json.JSONDecodeError as error:
        a.expect(False, "zh_cn / en_us 是合法 JSON", str(error))
        return
    prefix = "gui.%s.decomposition_generator." % MODID
    screen = strip_comments(src["screen"])

    for key, expected_args in LIVE_LANG_KEYS.items():
        full = prefix + key
        a.expect(full in zh, "zh_cn 有新键 %s" % key)
        a.expect(full in en, "en_us 有新键 %s" % key)
        a.expect(lang_placeholder_count(zh.get(full, "")) == expected_args,
                 "zh_cn 的 %s 有 %d 个 %%s" % (key, expected_args),
                 str(zh.get(full, "")))
        a.expect(lang_placeholder_count(zh.get(full, "")) == lang_placeholder_count(en.get(full, "")),
                 "%s 的 %%s 个数 zh/en 一致" % key,
                 "%d vs %d" % (lang_placeholder_count(zh.get(full, "")),
                               lang_placeholder_count(en.get(full, ""))))

    # 调用处实参个数必须 == 语言文件占位符个数（否则 String.format 抛 MissingFormatArgumentException）
    for key, expected_args in LIVE_LANG_KEYS.items():
        call = re.search(r'Component\.translatable\(\s*"gui\.%s\.decomposition_generator\.%s"'
                         % (re.escape(MODID), re.escape(key)), screen)
        a.expect(call is not None, "Screen 里调用 %s 键" % key)
        if not call:
            continue
        paren = screen.find("(", screen.find("translatable", call.start()))
        close = _match_delim(screen, paren)
        args = screen[paren + 1:close]
        depth = 0
        commas = 0
        in_string = False
        previous = ""
        for char in args:
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
        a.expect(commas == expected_args,
                 "%s 的调用处实参个数 == %d（多/少都会 String.format 抛异常）" % (key, expected_args),
                 "实测 %d" % commas)

    # Lead 已删的孤儿键不许回来（audit_lang_keys 会判「孤儿键 > 0」）
    code_sources = strip_comments(src["screen"]) + strip_comments(src["menu"]) + strip_comments(src["be"])
    for dead in DEAD_LANG_KEYS:
        full = prefix + dead
        a.expect(full not in zh and full not in en,
                 "死键 %s 已从 zh/en 删除（不许回潮）" % dead)
        # 必须按「完整键名」匹配：progress 是 progress_short 的前缀，子串判断会误伤
        a.expect(re.search(r'"%s"' % re.escape(full), code_sources) is None,
                 "死键 %s 也没有被代码引用" % dead)


# =====================================================================================
# 主流程 + 反例自证
# =====================================================================================

SECTIONS = (
    ("① 槽数对账（Spec ↔ Menu ↔ 构造器实际 addSlot）", section_slots),
    ("② 数据包（枯萎类缺席 / 数值区间 / 禁止无限电）", section_datapack),
    ("③ 副产物数值裁剪（0 / 负 / 超大 / NaN 都不抛异常）", section_profile_clamp),
    ("④ 旧存档迁移（256 → 20 格，物品守恒）", section_migration),
    ("⑤ 界面（贴图 182×212 / 62 槽格 / 唯一能量条 / 熔炉箭头 / 三行废文本已删）", section_ui),
    ("⑥ 语言键（energy / progress_short 在用 / 三行文案键与旧死键不许回潮）", section_lang),
)


def load_sources() -> dict[str, str]:
    return {key: read(path) for key, path in FILES.items()}


def run_audits(sources: dict[str, str], png_dimensions=None):
    audit = Audit()
    for _title, function in SECTIONS:
        if function is section_ui:
            function(audit, sources, png_dimensions)
        else:
            function(audit, sources)
    return audit


def make_counterexamples(sources: dict[str, str]):
    """(说明, 变异后的 sources, png_dimensions, 期望被哪一组判负)。"""
    def mutate(key, old, new):
        changed = dict(sources)
        assert old in changed[key], "反例锚点过期：%r" % old
        changed[key] = changed[key].replace(old, new, 1)
        return changed

    return [
        ("① TOTAL_SLOT_COUNT 少 6（升级槽没算进去）",
         mutate("menu", "public static final int TOTAL_SLOT_COUNT = PLAYER_SLOT_END;",
                "public static final int TOTAL_SLOT_COUNT = PLAYER_SLOT_END - 6;"), None),
        ("① 构造器漏掉 6 个升级槽 addSlot",
         mutate("menu", "for (int index = 0; index < DecompositionGeneratorSpec.UPGRADE_SLOTS; index++) {",
                "for (int index = 0; index < 0; index++) {"), None),
        ("① 槽位坐标写裸数字（背景与槽位会错位）",
         mutate("menu", "slotX(DecompositionGeneratorSpec.INPUT_X, index, DecompositionGeneratorSpec.INPUT_COLS)",
                "slotX(8, index, DecompositionGeneratorSpec.INPUT_COLS)"), None),
        ("② 数据包里塞回 dead_bush",
         mutate("builtin", '"item": "minecraft:kelp",', '"item": "minecraft:dead_bush",'), None),
        ("② 干草块退回小麦 ×9（无限电）",
         mutate("builtin", '{ "item": "minecraft:hay_block", "time": 180, "energy": 450, '
                           '"result": { "item": "minecraft:bone_meal", "count": 9 } }',
                '{ "item": "minecraft:hay_block", "time": 180, "energy": 450, '
                '"result": { "item": "minecraft:wheat", "count": 9 } }'), None),
        ("② 产物 chance 写到 1.5",
         mutate("builtin", '"chance": 0.25 }', '"chance": 1.5 }'), None),
        ("② time 写成 0（每 tick 发一次电）",
         mutate("builtin", '{ "item": "minecraft:melon_seeds", "time": 10,',
                '{ "item": "minecraft:melon_seeds", "time": 0,'), None),
        ("③ 紧凑构造器不再夹 time（负数直接进 record）",
         mutate("profile", "time = clamp(time, MIN_TIME, MAX_TIME);", "time = time;"), None),
        ("③ chance 的 NaN 不再按 0 处理",
         mutate("profile", "if (Float.isNaN(value)) {\n                return MIN_CHANCE;\n            }",
                "if (Float.isNaN(value)) {\n                return MAX_CHANCE;\n            }"), None),
        ("④ 迁移前直接拿旧档 Size 裸 deserializeNBT",
         mutate("be", "inventory.deserializeNBT(registries, normalizedInventoryTag(inventoryTag, declaredSize,",
                "inventory.deserializeNBT(registries, inventoryTag); //"), None),
        ("④ 余量不落 pendingDump（物品被悄悄丢掉）",
         mutate("be", "dumpedItems += leftover.getCount();\n                    addToPendingDump(leftover);",
                "dumpedItems += leftover.getCount();"), None),
        ("④ pendingDump 不落盘（读档到首个 tick 之间崩溃就丢）",
         mutate("be", "tag.put(TAG_PENDING_DUMP, list)", "tag.put(\"Unused\", list)"), None),
        ("⑤ 界面又用 fill 拼面板",
         mutate("screen", "        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0,",
                "        guiGraphics.fill(leftPos, topPos, leftPos + 1, topPos + 1, 0);\n"
                "        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0,"), None),
        ("⑤ 界面再画第二条能量条",
         mutate("screen", "        addRenderableWidget(RsccEnergyBar.create(",
                "        addRenderableWidget(RsccEnergyBar.create(leftPos, topPos, 1, () -> 0, "
                "() -> 1, java.util.List::of));\n        addRenderableWidget(RsccEnergyBar.create("), None),
        ("⑤ 熔炉箭头只画底图、不画亮起部分（永远 0% 进度）",
         mutate("screen", "        final int filledWidth = 1 + (int) Math.round(",
                "        final int filledWidth = 0; final int unusedWidth = 1 + (int) Math.round("), None),
        ("⑤ 贴图尺寸退回旧的 182×206",
         sources, (182, 206)),
        ("⑤ 状态行又画回来（Screen 重新引用 idle 键）",
         # 第 129 轮：箭头 tooltip 改成「进度 + JEI 提示」后，原来那条 javadoc 锚点换了字，
         # 因此这里改锚在方法签名上（签名比注释稳）；判据本身没动。
         mutate("screen", "    private void drawArrowTooltip(",
                "    // 旧状态行（第 126 轮删掉的）\n"
                "    private static final String OLD_STATUS_KEY = \"gui.rs_create_compat."
                "decomposition_generator.idle\";\n"
                "    private void drawArrowTooltip("), None),
        ("⑤ Spec 把 TEXT_LINES 常量加回来（三行常量回潮）",
         mutate("spec", "    public static final int CONTENT_INSET = 8;",
                "    public static final int CONTENT_INSET = 8;\n"
                "    public static final int TEXT_LINES = 3;"), None),
        ("⑥ 三行废文本的 idle 键回潮",
         mutate("lang_zh", '  "gui.rs_create_compat.decomposition_generator.progress_short"',
                '  "gui.rs_create_compat.decomposition_generator.idle": "待机：没有可降解的植物",\n'
                '  "gui.rs_create_compat.decomposition_generator.progress_short"'), None),
        ("⑥ 死键 progress 回潮",
         mutate("lang_zh", '  "gui.rs_create_compat.decomposition_generator.progress_short"',
                '  "gui.rs_create_compat.decomposition_generator.progress": "旧 %s：%s / %s 刻",\n'
                '  "gui.rs_create_compat.decomposition_generator.progress_short"'), None),
    ]


def main() -> int:
    sources = load_sources()
    missing = [key for key, path in FILES.items() if not os.path.exists(path)]
    for key in missing:
        print("MISSING 文件 %s：%s" % (key, FILES[key]))

    print("=== 第 123 轮：降解室发电机重做（界面 / 数据包 / 迁移）===")
    for title, _function in SECTIONS:
        print("\n--- %s ---" % title)
    audit = run_audits(sources)
    print("\nCHECK_COUNT %d failures=%d" % (audit.total, len(audit.failures)))
    for note in audit.notes:
        print("NOTE %s" % note)
    for failure in audit.failures:
        print("FAIL %s" % failure)

    print("\n=== 反例自证（自检必须能判负） ===")
    passed = 0
    counterexamples = make_counterexamples(sources)
    for label, changed, dimensions in counterexamples:
        result = run_audits(changed, dimensions)
        if result.failures:
            passed += 1
            print("ok   反例 %s ⇒ 判负（%s）" % (label, result.failures[0]))
        else:
            audit.failures.append("反例未判负: " + label)
            print("FAIL 反例 %s：改错了但审计仍然全绿（断言无效）" % label)

    if audit.failures:
        print("\nSELFCHECK FAILED (%d checks, %d 反例自证, %d failures)"
              % (audit.total, len(counterexamples), len(audit.failures)))
        return 1
    print("\n断言 %d 条（含反例自证 %d 条，SKIP %d 条）"
          % (audit.total, passed, len(audit.notes)))
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (audit.total, passed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
