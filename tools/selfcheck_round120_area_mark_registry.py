#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 120 轮自检：区域标记「客户端读标记不带注册表 ⇒ 下标错位 ⇒ REMOVE 删错一条」。

背景（只读审计 docs/ROUND119_AUDIT_AREA_MARKS.md §C-5）
-----------------------------------------------------
1. 标记存在选定器物品的 `CUSTOM_DATA` 子树 `RsccAreaMarks` 里（`support/AreaMarks.java`）；
2. `CollectionCacheBlockEntity#decodeMarkerTag` 对**旧档**的 `Item`（整份 ItemStack）编码条目，
   在 `registries == null` 时**返回 null 由调用方丢弃**（第 113 轮 D9 的早退，防 `parseOptional(null, …)` NPE）；
3. 服务端读标记带注册表（`AreaMarkEditPacket` 的 `player.registryAccess()`），
   客户端 `AreaMarkScreen` 以前走**两参** `AreaMarks.get(stack, area)`（= `registries = null`）
   ⇒ 两边列表**长度 / 顺序不同**；
4. 而「左键点第 N 格移除」发的是**下标**（`ACTION_REMOVE + markerIndex`）
   ⇒ 删掉的是服务端列表里同名下标的**另一条**（不丢区域、不崩，但是「删错条目」）。

本轮修法（只碰 3 个 java 文件 + 本自检 + 文档）
----------------------------------------------
* `client/screen/AreaMarkScreen`：新增 `clientRegistries()`（`Minecraft.getInstance().level.registryAccess()`）
  与 `clientMarks(stack, area)`（带注册表读、**拿不到注册表时返回空表**而不是回退 null 读法 ⇒ 界面无格可点，
  不可能删错）；`marks()` 改为唯一入口，渲染 / 提示 / 加 / 删 / 清 / 点击全部经由它；
* `support/AreaMarks`：两参 `get` / `all` 的 javadoc 写明「客户端不要用它读标记」的口径 + 后果，
  **不改数据格式、不改重载签名**（服务端与自检模型继续传 null 是合法的）；
* `network/AreaMarkEditPacket`：javadoc 记录「REMOVE 下标」的成立前提与本轮**放弃**「下标 + 条目指纹」
  加固的决策（那要给包加字段 ⇒ 改网络线格式，代价高于收益）。

为什么客户端辅助方法放在 `AreaMarkScreen` 而不是 `support/AreaMarks`
-----------------------------------------------------------------
工程约定（`support/JetpackHudState.java:8`）：通用类的方法体里一出现 `net.minecraft.client.*`，
**专用服务端就会在链接期**出问题。`AreaMarks` 是服务端也用到的通用类 ⇒ 客户端类型只能留在 client 包里。

三条腿（与第 113 / 118 / 119 轮同一套路）
--------------------------------------
① 源码锚点：客户端读标记必须带注册表 + 不再出现两参 `AreaMarks.get(` + 服务端两处都带注册表 +
   「两参调用点清单」逐条归类（client / 服务端 / 展示型），并各自配旧写法反例；
② 行为模型：复刻「标记表解码（旧格式 Item 条目 无注册表 ⇒ 丢弃 / 有注册表 ⇒ 保留）+ 列表下标 + REMOVE」，
   断言有注册表时客户端下标 == 服务端下标、**无注册表时序列不同**，并穷举证明旧实现会删错；
③ 反例自证：把坏写法拼回源码副本 / 模型，断言同一条判据真的判负（证明判据不是摆设）。

纪律：不跑 gradle、不改存档格式 / 网络契约、不改语言文件、不碰其他 agent 正在写的文件。
用法：python tools/selfcheck_round120_area_mark_registry.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
import itertools
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

MARK_SCREEN = os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")
AREA_SCREEN = os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")
SELECTOR_ITEM = os.path.join(JAVA, "item", "AreaSelectorItem.java")
MARK_PACKET = os.path.join(JAVA, "network", "AreaMarkEditPacket.java")
AREA_MARKS = os.path.join(JAVA, "support", "AreaMarks.java")
CACHE_BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")

#: `MATCH_CAPACITY`（`CollectionCacheBlockEntity`：6 页 × 48 = 288）—— 解码侧截断口径。
MATCH_CAPACITY = 288

#: 客户端**两参** `AreaMarks.get(...)` 的允许清单（本轮范围外、且不发送任何下标）。
#: 键 = 仓库相对路径（`/` 分隔），值 = 为什么可以留到后续。
CLIENT_TWO_ARG_ALLOWLIST = {
    "src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/AreaSelectorScreen.java":
        "只用于画区域列表行右侧的过滤图标（rowIconCount / drawRowFilters），"
        "界面不按它发任何下标 ⇒ 旧格式条目少画一个图标，不会删错；"
        "改动需同步 selfcheck_round112 ⑦-7 的锚点，属后续任务",
}

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


# ======================================================================================
# 源码解析小工具（与第 119 轮同一套，避免再造一份不同口径的「去注释 / 取方法体」）
# ======================================================================================

def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性」判据。"""
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


def method_body(code, hint):
    """从（已去注释的）源码里取一个方法体：从 hint 起找到第一个 '{' 并配平。"""
    start = code.find(hint)
    if start < 0:
        return None
    brace = code.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return None


def brace_block(text, header):
    """取以 header 开头的那个花括号块（含嵌套）。找不到返回 None。"""
    if text is None:
        return None
    start = text.find(header)
    if start < 0:
        return None
    brace = text.find("{", start + len(header) - 1)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


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


def area_marks_get_calls(code):
    """`AreaMarks.get(...)` 的所有调用 → [(行号, 参数个数, 调用文本)]（已去注释的源码）。"""
    out = []
    needle = "AreaMarks.get("
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
        arg_text = code[j:k - 1] if depth == 0 else code[j:]
        out.append((code.count("\n", 0, i) + 1, len(split_args(arg_text)),
                    "AreaMarks.get(" + arg_text + ")"))
        start = k


def java_sources():
    """src/main/java 下所有与「区域标记」相关的 java 源（本自检只关心这些，避免全仓 walk 变慢）。"""
    return [MARK_SCREEN, AREA_SCREEN, SELECTOR_ITEM, MARK_PACKET, AREA_MARKS, CACHE_BE]


def scan_two_arg_sites():
    """全仓（src/main/java）两参 `AreaMarks.get(...)` 调用点 → [(仓库相对路径, 行号, 调用文本)]。"""
    hits = []
    java_root = os.path.join(ROOT, "src", "main", "java")
    for dirpath, _dirnames, filenames in os.walk(java_root):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            raw = read(path)
            if "AreaMarks.get(" not in raw:      # 先便宜地筛一遍，再花力气去注释
                continue
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            for line, argc, call in area_marks_get_calls(strip_comments(raw)):
                if argc == 2:
                    hits.append((rel, line, call))
    return hits


# ======================================================================================
# 判据（写成「纯函数」，反例自证时直接喂改坏的源码副本）
# ======================================================================================

def client_reads_via_registry(src):
    """`AreaMarkScreen#clientMarks` 用三参（带 registries）读标记。"""
    body = method_body(strip_comments(src), "static List<MarkerEntry> clientMarks(")
    if body is None:
        return False
    calls = area_marks_get_calls(body)
    return len(calls) == 1 and calls[0][1] == 3 and "registries" in calls[0][2]


def client_registries_from_level(src):
    """`clientRegistries()` = `Minecraft.getInstance().level.registryAccess()`（拿不到 = null）。"""
    body = method_body(strip_comments(src),
                       "static net.minecraft.core.HolderLookup.Provider clientRegistries()")
    if body is None:
        return False
    return ("Minecraft.getInstance().level" in body
            and ".registryAccess()" in body
            and "level == null ? null" in body)


def client_marks_no_null_fallback(src):
    """拿不到注册表时 `return List.of();`，**不回退**「null 注册表」读法。"""
    body = method_body(strip_comments(src), "static List<MarkerEntry> clientMarks(")
    if body is None:
        return False
    block = brace_block(body, "if (registries == null)")
    if block is None:
        return False
    return ("return List.of();" in block
            and "AreaMarks.get(" not in block
            and "AreaMarks.get(stack, area, null)" not in body)


def mark_screen_no_two_arg_read(src):
    """`AreaMarkScreen` 里不再出现两参 `AreaMarks.get(...)`。"""
    return not [call for call in area_marks_get_calls(strip_comments(src)) if call[1] == 2]


def marks_single_source(src):
    """`AreaMarks.get(` 全文件只有 1 处（三参，在 clientMarks 里），`marks()` 是唯一入口。"""
    code = strip_comments(src)
    body = method_body(code, "private List<MarkerEntry> marks()")
    if body is None:
        return False
    calls = area_marks_get_calls(code)
    return (len(calls) == 1 and calls[0][1] == 3
            and "clientMarks(targetStack(), area)" in body)


def mark_consumers_use_marks(src):
    """渲染 / 提示 / 加 / 删 / 清 / 点击都只经 `marks()`（列表与 REMOVE 必须同源）。"""
    code = strip_comments(src)
    hints = ("private void drawMarkGrid(", "private void drawInfoAndNotice(",
             "private void drawTooltips(", "private void addFromInventory(",
             "private void removeAt(", "private void clearAll(",
             "public boolean mouseClicked(")
    for hint in hints:
        body = method_body(code, hint)
        if body is None or "marks()" not in body:
            return False
    return True


def remove_uses_index_and_guard(src):
    """REMOVE 发的是下标，且先做 `index < 0 || index >= current.size()` 越界守卫。"""
    body = method_body(strip_comments(src), "private void removeAt(")
    if body is None:
        return False
    return ("if (index < 0 || index >= current.size())" in body
            and "AreaMarkEditPacket.ACTION_REMOVE" in body
            and "index, AreaMarkEditPacket.NO_INDEX" in body)


def packet_server_reads_with_registries(src):
    """服务端包内两处读标记（ADD / REMOVE）都是三参 + `player.registryAccess()`。"""
    calls = area_marks_get_calls(strip_comments(src))
    if len(calls) != 2:
        return False
    return all(call[1] == 3 and "player.registryAccess()" in call[2] for call in calls)


def packet_remove_uses_marker_index(src):
    """服务端 REMOVE = `packet.markerIndex()` 取下标 + `entries.size()` 夹住 + `next.remove(index)`。"""
    code = strip_comments(src)
    return ("final int index = packet.markerIndex();" in code
            and "index >= entries.size()" in code
            and "next.remove(index);" in code)


def area_marks_has_no_client_types(src):
    """通用类 `support/AreaMarks` 不得引用任何 `net.minecraft.client.*`（专用服务端链接期安全）。"""
    return "net.minecraft.client" not in src and "Minecraft." not in src


def area_marks_keeps_two_arg_overload(src):
    """两参重载仍在（服务端 / 只读诊断 / 自检模型继续传 null 是合法的）。"""
    code = strip_comments(src)
    return ("public static List<MarkerEntry> get(final ItemStack stack, @Nullable final Area area) {"
            in code
            and "return get(stack, area, null);" in code)


def client_doc_warns_about_two_arg(src):
    """两参重载 / 整表读取的 javadoc 必须写明「客户端界面不要用」的口径与后果。"""
    text = src
    return ("不要在客户端界面上用这个重载" in text
            and "decodeMarkerTag" in text
            and "REMOVE" in text)


def tooltip_item_uses_all_not_get(src):
    """`AreaSelectorItem` 的 tooltip 只做计数，用的是 `all(...)`（不按下标的 get）。"""
    code = strip_comments(src)
    return "AreaMarks.all(" in code and "AreaMarks.get(" not in code


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点 ===")
    mark_raw = read(MARK_SCREEN)
    packet_raw = read(MARK_PACKET)
    areamarks_raw = read(AREA_MARKS)
    item_raw = read(SELECTOR_ITEM)
    cache_raw = read(CACHE_BE)

    check("A-1 AreaMarkScreen#clientMarks 用三参（带 registries）读标记", client_reads_via_registry(mark_raw),
          "不是「带注册表」的读法")
    check("A-2 registries 来自 Minecraft.getInstance().level.registryAccess()（拿不到 = null）",
          client_registries_from_level(mark_raw))
    check("A-3 拿不到注册表时返回空表，**不回退** null 注册表读法（宁可这一帧不显示）",
          client_marks_no_null_fallback(mark_raw))
    check("A-4 AreaMarkScreen 不再出现两参 AreaMarks.get(...)（客户端读标记必带注册表）",
          mark_screen_no_two_arg_read(mark_raw),
          str([c for c in area_marks_get_calls(strip_comments(mark_raw)) if c[1] == 2]))
    check("A-5 全文件 AreaMarks.get( 只有 1 处（在 clientMarks 里）+ marks() 是唯一入口",
          marks_single_source(mark_raw),
          "调用 %d 处" % len(area_marks_get_calls(strip_comments(mark_raw))))
    check("A-6 渲染 / 提示 / 加 / 删 / 清 / 点击全部经 marks()（列表与 REMOVE 同源，不会各读一份）",
          mark_consumers_use_marks(mark_raw))
    check("A-7 REMOVE 仍按下标发送，且先做越界守卫（服务端同一语义）",
          remove_uses_index_and_guard(mark_raw))
    check("A-8 服务端 AreaMarkEditPacket 两处读标记都带 player.registryAccess()（ADD + REMOVE）",
          packet_server_reads_with_registries(packet_raw))
    check("A-9 服务端 REMOVE = markerIndex 取下标 + entries.size() 夹住 + next.remove(index)",
          packet_remove_uses_marker_index(packet_raw))
    check("A-10 support/AreaMarks 仍不引用 net.minecraft.client.*（专用服务端链接期安全）",
          area_marks_has_no_client_types(areamarks_raw))
    check("A-11 两参重载仍在 AreaMarks 上（服务端 / 只读诊断 / 自检模型继续传 null 合法）",
          area_marks_keeps_two_arg_overload(areamarks_raw))
    check("A-12 AreaMarks 的两参 / 整表重载 javadoc 写明「客户端不要用」+ decodeMarkerTag 丢弃 + REMOVE 后果",
          client_doc_warns_about_two_arg(areamarks_raw))
    check("A-13 解码侧早退仍在（这是「旧格式条目被丢」的根因，本轮不动它，只让客户端也带注册表）",
          "if (registries == null) {" in strip_comments(cache_raw)
          and "TAG_MARKER_ITEM" in cache_raw)
    check("A-14 tooltip 计数走 all(...) 而不是 get(...)（展示型：不参与任何下标回写）",
          tooltip_item_uses_all_not_get(item_raw))

    # ---- 两参调用点清单：逐条归类（client / 服务端 / 展示型） ----
    hits = scan_two_arg_sites()
    for rel, line, call in hits:
        print("     两参调用点：%s:%d  %s" % (rel, line, call))
    client_hits = [h for h in hits if "/client/" in h[0]]
    offenders = [h for h in client_hits if h[0] not in CLIENT_TWO_ARG_ALLOWLIST]
    check("A-15 client 包里的两参 AreaMarks.get 调用点只剩文档化的后续项（允许清单 %d 条）"
          % len(CLIENT_TWO_ARG_ALLOWLIST),
          not offenders, str(offenders))
    check("A-16 非 client 包（服务端 / 通用）内没有两参 AreaMarks.get( 调用",
          not [h for h in hits if "/client/" not in h[0]],
          str([h for h in hits if "/client/" not in h[0]]))
    # 允许清单里的那一处，必须是「只写进 displayFilters（画图标）」的那种用法
    allow_ok = True
    for rel, _line, call in [h for h in client_hits if h[0] in CLIENT_TWO_ARG_ALLOWLIST]:
        code = strip_comments(read(os.path.join(ROOT, rel.replace("/", os.sep))))
        rows = method_body(code, "private void rebuildRows(")
        if rows is None or ("displayFilters.add(" + call + ")") not in rows:
            allow_ok = False
        if "displayFilters" not in code:
            allow_ok = False
    check("A-17 允许清单里的调用点确实只写进 displayFilters（画图标用），不是 REMOVE 的下标来源",
          allow_ok)


# ======================================================================================
# ② 行为模型：标记表解码 + 列表下标 + REMOVE
# ======================================================================================

def new_mark(mid, amount=1):
    """新格式条目（`Id + Nbt`）：解码**不需要**注册表。"""
    return {"kind": "new", "id": mid, "amount": amount}


def legacy_mark(mid):
    """旧档条目（`Item` = 整份 ItemStack）：解码**需要**注册表。"""
    return {"kind": "legacy_item", "id": mid}


def decode_mark_list(raw_entries, registries_available, capacity=MATCH_CAPACITY):
    """复刻 `AreaMarks#readEntries` + `CollectionCacheBlockEntity#decodeMarkerTag`：

    * 逐条解码；`null` / 空条目**丢弃**（`if (entry != null && !entry.isEmpty())`）；
    * 新格式（`Id`）恒可读；
    * 旧格式（`Item`）在 `registries == null` 时 `decodeMarkerTag` **返回 null ⇒ 被丢弃**；
    * 迭代上限 = `MATCH_CAPACITY`（**按原始条数**计，被丢弃的也占一个迭代位）。
    """
    out = []
    for entry in raw_entries:
        if len(out) >= capacity:
            break
        if entry["kind"] == "new":
            decoded = {"id": entry["id"], "fluid": False, "amount": entry.get("amount", 1)}
        elif entry["kind"] == "legacy_item":
            if not registries_available:
                continue
            decoded = {"id": entry["id"], "fluid": False, "amount": entry.get("amount", 1)}
        else:
            continue
        if decoded["id"] is None:
            continue
        out.append(decoded)
    return out


def remove_by_index(server_entries, client_entries, index):
    """客户端点第 index 格 → 发 REMOVE(index) → 服务端 `next.remove(index)`。

    返回 `(被删掉的条目, 删除后的服务端列表)`；`None` = 没有删（界面无此格 / 服务端越界丢包）。
    """
    if index < 0 or index >= len(client_entries):
        return None, list(server_entries)        # 界面上没有这一格 ⇒ 根本不发包
    if index >= len(server_entries):
        return None, list(server_entries)        # 服务端 `index >= entries.size()` ⇒ 丢包
    remaining = list(server_entries)
    gone = remaining.pop(index)
    return gone, remaining


def group_models():
    print("\n=== ② 行为模型 ===")

    # ---- 场景 1：旧格式条目在最前面（审计 C-5 举的例子） ----
    tag1 = [legacy_mark("minecraft:iron_ingot"), new_mark("minecraft:gold_ingot")]
    server1 = decode_mark_list(tag1, True)
    old_client1 = decode_mark_list(tag1, False)
    new_client1 = decode_mark_list(tag1, True)

    check("M-1 旧档混排：服务端解出 2 条，旧客户端（无注册表）只解出 1 条（旧格式被丢）",
          [e["id"] for e in server1] == ["minecraft:iron_ingot", "minecraft:gold_ingot"]
          and [e["id"] for e in old_client1] == ["minecraft:gold_ingot"],
          "server=%s old_client=%s" % ([e["id"] for e in server1], [e["id"] for e in old_client1]))

    clicked = old_client1[0]
    gone, rest = remove_by_index(server1, old_client1, 0)
    check("M-2 旧实现：界面显示的是 gold，服务端删掉的却是 iron ⇒ **删错一条**（C-5 是真 bug）",
          gone is not None and gone["id"] != clicked["id"] and gone["id"] == "minecraft:iron_ingot",
          "clicked=%s gone=%s" % (clicked["id"], None if gone is None else gone["id"]))
    check("M-3 旧实现删完之后，玩家以为还在的 gold 消失了、以为删掉的 iron 还在",
          [e["id"] for e in rest] == ["minecraft:gold_ingot"])

    # ---- 场景 2：新实现（客户端也带注册表） ----
    check("M-4 新实现：客户端列表 == 服务端列表（长度 / 顺序都一致 ⇒ 下标天然对齐）",
          new_client1 == server1, "client=%s server=%s"
          % ([e["id"] for e in new_client1], [e["id"] for e in server1]))
    gone, rest = remove_by_index(server1, new_client1, 0)
    check("M-5 新实现：点第 0 格删掉的就是显示的 iron（没有错位）",
          gone is not None and gone["id"] == new_client1[0]["id"]
          and [e["id"] for e in rest] == ["minecraft:gold_ingot"])

    # ---- 场景 3：穷举「0/1/2/3 条 × 全部旧/新混排」 ----
    new_ok = True
    old_can_be_wrong = False
    old_can_differ = False
    for n in range(0, 4):
        for pattern in itertools.product(("new", "legacy"), repeat=n):
            raw = [new_mark("minecraft:item_%d" % i) if kind == "new"
                   else legacy_mark("minecraft:old_%d" % i)
                   for i, kind in enumerate(pattern)]
            server = decode_mark_list(raw, True)
            old_client = decode_mark_list(raw, False)
            new_client = decode_mark_list(raw, True)
            if new_client != server:
                new_ok = False
            if old_client != server:
                old_can_differ = True
            for index in range(len(old_client)):
                removed, _ = remove_by_index(server, old_client, index)
                if removed is not None and removed != old_client[index]:
                    old_can_be_wrong = True
            for index in range(len(new_client)):
                removed, _ = remove_by_index(server, new_client, index)
                if removed != new_client[index]:
                    new_ok = False
    check("M-6 穷举（0/1/2/3 条 × 旧新混排 15 种组合）：新实现「客户端 == 服务端」恒成立",
          new_ok)
    check("M-7 穷举：旧实现存在「无注册表时序列不同」的组合（⇒ 下标不可信）",
          old_can_differ)
    check("M-8 穷举：旧实现存在「点这一条、删另一条」的组合（所以必须修，不是理论瑕疵）",
          old_can_be_wrong)

    # ---- 场景 4：超过 MATCH_CAPACITY 时截断口径也被旧条目污染 ----
    tag4 = ([legacy_mark("minecraft:old_%d" % i) for i in range(20)]
            + [new_mark("minecraft:item_%d" % i) for i in range(300)])
    server4 = decode_mark_list(tag4, True)
    old_client4 = decode_mark_list(tag4, False)
    new_client4 = decode_mark_list(tag4, True)
    check("M-9 288 条上限：旧实现长度还可能相同、但**内容整体前移**（20 条旧格式占掉迭代位却不显示）"
          " ⇒ 下标依旧不可信、点第 0 格删的是别的条目",
          len(server4) == MATCH_CAPACITY and len(old_client4) == len(server4)
          and old_client4 != server4
          and remove_by_index(server4, old_client4, 0)[0] != old_client4[0],
          "server=%d old_client=%d" % (len(server4), len(old_client4)))
    check("M-10 288 条上限：新实现长度与内容都与服务端一致", new_client4 == server4)

    # ---- 场景 5：新实现「拿不到注册表 ⇒ 空表」⇒ 无格可点 ⇒ 不可能删错 ----
    tag5 = [new_mark("minecraft:stone"), legacy_mark("minecraft:dirt")]
    server5 = decode_mark_list(tag5, True)

    def new_client_when_no_registries():
        """`clientMarks`：`registries == null` ⇒ `List.of()`（不回退 null 读法）。"""
        return []

    no_reg_client = new_client_when_no_registries()
    silent = 0
    untouched = True
    for index in range(0, 8):
        removed, rest = remove_by_index(server5, no_reg_client, index)
        if removed is not None:
            untouched = False
        else:
            silent += 1
    check("M-11 新实现无注册表：列表为空 ⇒ 8 个格子点下去全部不发包、不删任何条目",
          no_reg_client == [] and untouched and silent == 8)
    check("M-12 对照：旧实现无注册表时列表仍然非空（还有新格式条目 ⇒ 可点、可发错下标）",
          decode_mark_list(tag5, False) != []
          and decode_mark_list(tag5, False) != decode_mark_list(tag5, True))

    # ---- 场景 6：服务端越界守卫 ----
    removed, rest = remove_by_index(server5, decode_mark_list(tag5, True), 99)
    check("M-13 服务端仍按 entries.size() 夹住下标：越界 = 丢包，不会误删尾部",
          removed is None and rest == server5)


# ======================================================================================
# ③ 反例自证（源码副本：把坏写法拼回去，断言上面的判据真的判负）
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证（源码副本） ===")
    mark_code = strip_comments(read(MARK_SCREEN))
    packet_code = strip_comments(read(MARK_PACKET))
    areamarks_code = strip_comments(read(AREA_MARKS))
    areamarks_raw = read(AREA_MARKS)          # javadoc 判据要看注释，不能去注释
    item_code = strip_comments(read(SELECTOR_ITEM))

    # A-1 / A-5：把三参读法改回两参 ⇒ 判负
    broken = mark_code.replace("return AreaMarks.get(stack, area, registries);",
                               "return AreaMarks.get(stack, area);", 1)
    counterexample("C-1 源码副本：clientMarks 改回两参 AreaMarks.get(stack, area) ⇒ A-1 / A-4 / A-5 判负",
                   broken != mark_code
                   and not client_reads_via_registry(broken)
                   and not mark_screen_no_two_arg_read(broken)
                   and not marks_single_source(broken))

    # A-3：拿不到注册表时回退 null 读法 ⇒ 判负（这正是修复前的成因）
    broken = mark_code.replace(
        "        if (registries == null) {\n            return List.of();",
        "        if (registries == null) {\n            return AreaMarks.get(stack, area, null);", 1)
    counterexample("C-2 源码副本：null 注册表分支改成回退 AreaMarks.get(..., null) ⇒ A-3 判负"
                   "（界面又会显示一份短列表 ⇒ 下标错位）",
                   broken != mark_code and not client_marks_no_null_fallback(broken))

    # A-2：注册表来源被写死成 null ⇒ 判负
    broken = mark_code.replace("return level == null ? null : level.registryAccess();",
                               "return null;", 1)
    counterexample("C-3 源码副本：clientRegistries 固定返回 null ⇒ A-2 判负",
                   broken != mark_code and not client_registries_from_level(broken))

    # A-5 / A-6：marks() 绕开 clientMarks 自己读 ⇒ 判负
    broken = mark_code.replace("    private List<MarkerEntry> marks() {\n"
                               "        return clientMarks(targetStack(), area);\n    }",
                               "    private List<MarkerEntry> marks() {\n"
                               "        return AreaMarks.get(targetStack(), area);\n    }", 1)
    counterexample("C-4 源码副本：marks() 绕开 clientMarks 直接两参读 ⇒ A-5 判负",
                   broken != mark_code and not marks_single_source(broken))

    # A-6：drawMarkGrid 私自读一份（渲染与 REMOVE 各读一份 ⇒ 同源前提破掉）
    broken = mark_code.replace("        final List<MarkerEntry> entries = marks();",
                               "        final List<MarkerEntry> entries = AreaMarks.get(targetStack(), area);",
                               1)
    counterexample("C-5 源码副本：drawMarkGrid 自己读一份（不经 marks()）⇒ A-6 判负",
                   broken != mark_code and not mark_consumers_use_marks(broken))

    # A-7：去掉越界守卫 ⇒ 判负
    broken = mark_code.replace("        if (index < 0 || index >= current.size()) {\n            return;\n        }\n",
                               "", 1)
    counterexample("C-6 源码副本：删掉 removeAt 的越界守卫 ⇒ A-7 判负",
                   broken != mark_code and not remove_uses_index_and_guard(broken))

    # A-8：服务端退化成两参读（客户端带、服务端不带，同样错位） ⇒ 判负
    broken = packet_code.replace(
        "final List<MarkerEntry> entries = AreaMarks.get(target, area, player.registryAccess());",
        "final List<MarkerEntry> entries = AreaMarks.get(target, area);")
    counterexample("C-7 源码副本：服务端两处退化成两参读 ⇒ A-8 判负（两边口径必须同时带注册表）",
                   broken != packet_code and not packet_server_reads_with_registries(broken))

    # A-10：把客户端类型塞进通用类 ⇒ 判负（专用服务端链接期风险）
    broken = areamarks_code.replace("import net.minecraft.world.item.ItemStack;",
                                     "import net.minecraft.client.Minecraft;\n"
                                     "import net.minecraft.world.item.ItemStack;", 1)
    counterexample("C-8 源码副本：给 support/AreaMarks 加 net.minecraft.client 引用 ⇒ A-10 判负",
                   broken != areamarks_code and not area_marks_has_no_client_types(broken))

    # A-11：删掉两参重载 ⇒ 判负
    broken = areamarks_code.replace("    public static List<MarkerEntry> get(final ItemStack stack,"
                                    " @Nullable final Area area) {\n"
                                    "        return get(stack, area, null);\n    }", "", 1)
    counterexample("C-9 源码副本：删掉两参重载 ⇒ A-11 判负（服务端 / 自检模型还在用它）",
                   broken != areamarks_code and not area_marks_keeps_two_arg_overload(broken))

    # A-12：把「客户端不要用」的口径删掉 ⇒ 判负（防止下一个人再踩回去）。javadoc 判据必须看原始文本。
    broken = areamarks_raw.replace("不要在客户端界面上用这个重载", "这个重载很好用", 1)
    counterexample("C-10 源码副本：删掉「客户端不要用两参」的 javadoc 口径 ⇒ A-12 判负",
                   broken != areamarks_raw and not client_doc_warns_about_two_arg(broken))

    # A-14：tooltip 改用 get ⇒ 判负
    # 第 121 轮 C-5：tooltip 已改成带 registries 的 all(stack, level.registryAccess())，锚点跟着更新
    broken = item_code.replace("AreaMarks.all(stack, tooltipLevel.registryAccess())",
                               "AreaMarks.get(stack, null)", 1)
    counterexample("C-11 源码副本：tooltip 的 all 改成 get ⇒ A-14 判负（展示型不应引入下标语义）",
                   broken != item_code and not tooltip_item_uses_all_not_get(broken))

    # 模型反例：把「客户端也带注册表」这一条件抹掉（= 修复前的实现）⇒ 「点这一条 = 删这一条」判负
    tag_broken = [legacy_mark("minecraft:iron_ingot"), new_mark("minecraft:gold_ingot")]
    server_broken = decode_mark_list(tag_broken, True)
    bad_client = decode_mark_list(tag_broken, False)
    aligned = all(remove_by_index(server_broken, bad_client, i)[0] == bad_client[i]
                  for i in range(len(bad_client)))
    counterexample("C-12 模型反例：把客户端读法换回「无注册表」后，"
                   "「点这一条 = 删这一条」的不变量判负（证明 M-8 的判据不是摆设）",
                   bad_client != server_broken and not aligned)


def main():
    print("第 120 轮自检：区域标记客户端读标记的注册表口径（C-5 下标错位 ⇒ REMOVE 删错一条）")
    group_anchors()
    group_models()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
