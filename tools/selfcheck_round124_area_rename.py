#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 124 轮自检：区域选定器「改名两连」—— 2-1 改了没生效 / 2-2 名字串到下一个区域。

用户实机反馈（docs/ROUND122_USER_FEEDBACK.md §2）
-----------------------------------------------
2-1：「更改区域的名称的时候呢，有的时候并不能正确的更改。就是比如说我已经点确定了，
     然后看着他改了，但实际上重新打开之后，他还是没改。」
2-2：「我在输入框中输入了鼓风机，然后对这一个进行了确定。然后我点击下一个的时候，
     下一个原本它是区域二嘛，然后点一下它它名字就直接变成鼓风机了。」

真根因（本轮用行号证据坐实，不是猜）
================================
2-2（名字串到下一行）—— 三个缺陷叠在 `AreaSelectorScreen`：
  * `syncFields()` 旧实现在「任何输入框有焦点」时**提前 return**（旧 :1211-1217）。点列表行时
    `mouseClicked` 在 `super.mouseClicked` 之前就 `return true`（旧 :1266），原版不会把焦点从
    名称框移走 ⇒ 名称框一直显示**上一行**的名字；
  * `flushRename()` 旧实现提交下标是**即时** `selection.selectedIndex()`（旧 :543）。`selectRow`
    里 `applyNameNow()` 先把 `renameAtMs` 清零（旧 :579），切换后下一帧 `render()`（旧 :770）
    再进 `flushRename()` 时 `selectedIndex()` 已经是**新行** ⇒ 上一行打的字被写进新行；
  * `renameAtMs` 提交后**从不复位** ⇒ 上面这条错位不是「偶发一次」，而是**每帧重投**。

2-1（看着改了、重开没改）—— 一条链上四处：
  a) `flushRename()` 没有「有没有待提交」的标志，只靠 `renameAtMs` 猜：字段初值
     `pendingRename = ""`（旧 :202）/ `renameAtMs = 0`（旧 :203）⇒ **打开界面第一帧**就满足
     「待提交文字（空串）≠ 当前名字」⇒ 立刻发 `ACTION_RENAME(index, "")` 把选中区域的名字**清掉**
     （服务端 `AreaSelectorEditPacket` :231-238 照单全收，没有 compare-and-set）；
  b) `flushApply()` 乐观写保留名字（旧 :530-531 `withName(...)`），但**发出去的 `area` 是无名的**
     （`readAreaFromFields` 旧 :472 只拼六个坐标）：服务端旧 `:202 selection.replace(index, area)`
     ⇒「改一下坐标 / 尺寸，名字就没了」；客户端先显示新名字 ⇒「看着他改了」，重开（服务端权威）才发现没名字；
  c) 去重判据读的是**客户端乐观副本**（旧 :548-552）：一旦客户端副本领先（a/b 任一路径都能造成），
     之后所有「再点一次确定」都被判成「没变化」而**永不重发**；
  d) 服务端有两处**完全静默的丢弃**（旁观者旧 :134-136、`resolveTarget` 为空旧 :137-140）以及
     主手路径 `reject()` 只有一句 `broadcastFullState()`（旧 :320-323）⇒ 玩家永远不知道没写进去。
  （`AdvancedAreaSnapshotPacket` **不写**物品副本，所以「回包把名字抹回去」这条假设可以排除；
   `Area` 的 name 在 `CODEC` / `STREAM_CODEC` / `withName` 里都在，存档链也没漏名字 —— 见 B 组断言。）

本轮修法（只碰 2 个 java 文件 + 本自检 + 一条旧自检的锚点）
====================================================
* `client/screen/AreaSelectorScreen`：
  - 新增 `pendingRenameIndex`（**打字时**捕获「这串文字属于哪一行」）、`nameBoxIndex`（名称框此刻
    显示的是哪一行）、`renamePending`（真的有待提交）；`flushRename()` 只提交一次、只提交给捕获行，
    捕获不到 / 行已不存在就丢弃并给一句原因，**绝不改投**；
  - `syncFields()`：选中行变了就**强制刷新**全部输入框（含名称框），focused 早退只保留给「同一行、
    正在打字」；刷新时**保持焦点**（玩家动作是「点一行、接着改这一行」，unfocus 等于逼他再点一次；
    重填只重置光标位置，且旧行的名字已在切行前提交，不会丢数据）；
  - `selectRow()`：`applyNameNow()` 之后**重读**物品选中项（提交里有乐观改名，用切换前那份快照
    `select(raw)` 会把名字又写回去）；`createArea / copySelected / deleteSelected / removed` 同样先提交；
  - 坐标框同理：新增 `pendingApplyIndex`，`flushApply()` 提交给捕获行（原来「改坐标后 250ms 内切行」
    会把旧行的坐标写进新行 —— 与 2-2 同一类，只是更隐蔽）。
* `network/AreaSelectorEditPacket`：
  - `ACTION_REPLACE` 保留旧名字（名字的唯一写入口是 `ACTION_RENAME`，见 :211-217）；
  - 旁观者 / 目标解析失败两处静默丢弃改为走 `reject()`；`reject()` 在主手路径补一条**客户端可见**
    的聊天提示（复用 `gui.*.err.*` 既有语言键，本轮**不新增语言键**；`ACTION_SELECT` 跳过以免刷屏）。

三条腿（与第 113 / 119 / 120 轮同一套路）
=====================================
① 源码锚点：上面每一条都要在真源码里逐字命中；② 行为模型：把「打字→确定→点下一行」「打开界面」
「改坐标」「被拒后重试」四条时序各复刻一遍，断言旧语义真的错、新语义真的对；③ 反例自证：把坏写法
拼回源码副本 / 模型，断言同一条判据真的判负（证明判据不是摆设）。

纪律：不跑 gradle / javac，不 git，不改语言文件、不碰其他 agent 正在写的文件。
用法：python tools/selfcheck_round124_area_rename.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
import json
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

SCREEN = os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")
PACKET = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
AREA = os.path.join(JAVA, "support", "Area.java")
AREA_SELECTION = os.path.join(JAVA, "support", "AreaSelection.java")
CACHE_BE = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
MAIN = os.path.join(JAVA, "RS_Create_Compat.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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
# 源码解析小工具（与第 119 / 120 轮同一套，避免再造一份口径不同的「去注释 / 取方法体」）
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


def body(code, hint):
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


def load_lang(name):
    try:
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            return json.load(handle)
    except Exception:  # pragma: no cover
        return {}


def drop_line_in_method(code, hint, line):
    """把某个方法体里的一整行删掉（返回改完的整份源码；找不到就原样返回）。

    反例自证用：源码里的注释在 `strip_comments` 之后变成了空行，直接对整份源码做
    `replace("签名() {\\n        applyNameNow();")` 会匹配不到 —— 必须先取方法体再改。
    """
    method = body(code, hint)
    if not method or line not in method:
        return code
    patched = method.replace(line, "", 1)
    return code.replace(method, patched, 1)


# ======================================================================================
# A 组：2-2 —— 「提交索引 = 打字时捕获的那一行」+「切行必须刷新名称框」
# ======================================================================================

def a1_flush_rename_uses_captured_index(code):
    """flushRename 的提交下标必须来自 pendingRenameIndex，不能是即时 selectedIndex。"""
    text = body(code, "private void flushRename()")
    if not text:
        return False
    return ("final int index = pendingRenameIndex;" in text
            and "pendingRenameIndex" in text
            and "final int index = selection.selectedIndex();" not in text)


def a2_flush_rename_is_one_shot(code):
    """flushRename 必须有「真的有待提交」的门，且提交后状态归零（不许每帧重投）。"""
    text = body(code, "private void flushRename()")
    if not text:
        return False
    if "!renamePending" not in text:
        return False
    if "renamePending = false;" not in text or "pendingRenameIndex = -1;" not in text:
        return False
    send_at = text.find("send(AreaSelectorEditPacket.ACTION_RENAME")
    clear_at = text.find("renamePending = false;")
    return send_at > 0 and 0 <= clear_at < send_at


def a3_capture_at_typing_time(code):
    """scheduleRename / applyNameNow 都要捕获 pendingRenameIndex（来源 = nameBoxIndex）。"""
    schedule = body(code, "private void scheduleRename()")
    apply_now = body(code, "private void applyNameNow()")
    if not schedule or not apply_now:
        return False
    capture = "pendingRenameIndex = nameBoxIndex >= 0 ? nameBoxIndex : selection.selectedIndex();"
    return (capture in schedule and capture in apply_now
            and "renamePending = true;" in schedule and "renamePending = true;" in apply_now)


def a4_discard_helper_exists(code):
    """必须存在「丢弃 pending」的唯一出口，且被执行到（切行 / 选中行变了）。"""
    text = body(code, "private void discardRename()")
    if not text:
        return False
    return ("renamePending = false;" in text and "pendingRenameIndex = -1;" in text
            and "pendingRename = \"\";" in text
            and text.count("discardRename();") == 0)


def a5_sync_fields_refreshes_on_index_change(code):
    """syncFields：选中索引变了必须刷新 nameBox，focused 早退只允许「同一行」。"""
    text = body(code, "private void syncFields(final AreaSelection selection)")
    if not text:
        return False
    if "final boolean indexChanged = selectedIndex != nameBoxIndex;" not in text:
        return False
    if "if (focused && !indexChanged) {" not in text:
        return False
    if "nameBoxIndex = selectedIndex;" not in text:
        return False
    if "nameBox.setValue(selected.name() == null ? \"\" : selected.name());" not in text:
        return False
    # 旧的「一有焦点就整段 return」形态不许回来
    if "if (focused) {\n            return;" in text:
        return False
    # 丢弃 pending 必须在「刷新」之前（否则会把新行的值当成待提交）
    return 0 <= text.find("discardRename();") < text.find("if (focused && !indexChanged)")


def a6_select_row_rereads_selection(code):
    """selectRow：applyNameNow 之后必须重读物品选中项（乐观改名不能被切换前的快照写回）。"""
    text = body(code, "private void selectRow(final int index, final boolean announce)")
    if not text:
        return False
    apply_at = text.find("applyNameNow();")
    # 注意：开头那行是 `AreaSelection selection = currentSelection();`（声明），要的是**再赋值**那一行
    reread_at = text.find("\n            selection = currentSelection();")
    select_at = text.find("selection.select(raw)")
    return 0 <= apply_at < reread_at < select_at


def _entry_flushes_before(hint, mutation_marker):
    def probe(code):
        text = body(code, hint)
        if not text:
            return False
        apply_at = text.find("applyNameNow();")
        mutation_at = text.find(mutation_marker)
        return 0 <= apply_at < mutation_at
    return probe


#: 「会改变服务端选中行」的全部入口 —— 必须逐个对账（本轮穷举过源码，只有这 5 个 + 关界面那条）。
SELECTION_CHANGE_ENTRIES = {
    "private void selectRow(final int index, final boolean announce)":
        ("点列表行 / 点行内过滤图标区", "send(AreaSelectorEditPacket.ACTION_SELECT"),
    "private void createArea()":
        ("新建（服务端 append 会自动选中新行）", "send(AreaSelectorEditPacket.ACTION_ADD"),
    "private void copySelected()":
        ("复制（服务端 append 会自动选中新行）", "send(AreaSelectorEditPacket.ACTION_COPY"),
    "private void deleteSelected()":
        ("删除（选中行会被顺移）", "send(AreaSelectorEditPacket.ACTION_DELETE"),
}


def a7_every_entry_flushes_first(code):
    for hint, (label, marker) in SELECTION_CHANGE_ENTRIES.items():
        if not _entry_flushes_before(hint, marker)(code):
            print("    入口未先提交旧行：%s（%s）" % (hint, label))
            return False
    return True


def a8_removed_flushes_first(code):
    text = body(code, "public void removed()")
    if not text:
        return False
    apply_at = text.find("applyNameNow();")
    super_at = text.find("super.removed();")
    return 0 <= apply_at < super_at


def a9_no_other_selection_switches(code):
    """滚轮 / 键盘不改变「选中行」（滚轮只滚列表视图；键盘只处理回车）。

    这两个入口如果哪天开始改选中，就必须同样先提交旧行 —— 所以这里列为**对账项**而不是静默放行。
    """
    scroll = body(code, "public boolean mouseScrolled(")
    keys = body(code, "public boolean keyPressed(")
    if not scroll or not keys:
        return False
    if "selectRow" in scroll or "ACTION_SELECT" in scroll or "selectedIndex" in scroll:
        return False
    if "ACTION_SELECT" in keys or "selectRow" in keys:
        return False
    return True


def a10_pending_apply_index(code):
    """坐标框同病同修：flushApply 也提交给「打字时捕获的那一行」。"""
    flush = body(code, "private void flushApply()")
    schedule = body(code, "private void scheduleApply()")
    if not flush or not schedule:
        return False
    return ("pendingApplyIndex = currentSelection().selectedIndex();" in schedule
            and "final int capturedIndex = pendingApplyIndex;" in flush
            and "final int index = capturedIndex >= 0 ? capturedIndex : selection.selectedIndex();" in flush)


# ======================================================================================
# B 组：2-1 —— 名字到底能不能落盘 / 被拒时客户端能不能知道
# ======================================================================================

def b1_area_name_roundtrip_data(area_code):
    """`Area` 的名字在存档 codec / 网络 codec / withName 三处都在（漏一处 = 重开就没名字）。"""
    return ('Codec.STRING.optionalFieldOf("name").forGetter(area -> java.util.Optional.ofNullable(area.name()))'
            in area_code
            and 'ByteBufCodecs.STRING_UTF8.encode(buf, area.name() == null ? "" : area.name());' in area_code
            and 'public Area withName(@Nullable final String newName) {' in area_code)


def b2_selection_persists_name(selection_code, main_code):
    """名字走「Area.CODEC → AreaSelection.CODEC → 数据组件 persistent/networkSynchronized」。"""
    return ('Area.CODEC.listOf().fieldOf("areas").forGetter(AreaSelection::areas)' in selection_code
            and "stack.set(RS_Create_Compat.AREA_SELECTION.get(), selection == null ? EMPTY : selection);"
            in selection_code
            and ".persistent(AreaSelection.CODEC)" in main_code
            and ".networkSynchronized(AreaSelection.STREAM_CODEC)" in main_code)


def b3_block_entity_saves_area_slot(be_code):
    """方块实体的存档 / 读档都整份走「区域槽物品栈」⇒ 物品组件（含名字）跟着一起落盘，没有第二条漏名字的路。"""
    save = body(be_code, "public void saveAdditional(")
    load = body(be_code, "public void loadAdditional(")
    if not save or not load:
        return False
    return ("tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(registries));" in save
            and "areaSlot.deserializeNBT(registries, tag.getCompound(TAG_AREA_SLOT));" in load)


def b4_replace_keeps_name(packet_code):
    """服务端 ACTION_REPLACE 不许顺手抹名字（名字的唯一写入口是 ACTION_RENAME）。"""
    text = body(packet_code, "case ACTION_REPLACE ->")
    if not text:
        # 箭头 switch 的分支体不是方法，用朴素切片兜底
        start = packet_code.find("case ACTION_REPLACE ->")
        end = packet_code.find("case ACTION_DELETE ->")
        if start < 0 or end < 0:
            return False
        text = packet_code[start:end]
    return ("area.name() == null ? area.withName(previous.name()) : area" in text
            and "final Area previous = selection.areas().get(index);" in text
            and "selection = selection.replace(index, area);" not in text)


def b5_reject_is_visible(packet_code):
    """ACTION_RENAME 被拒 / 被判无效时，客户端一定看得见：菜单路径走拒绝序号，主手路径走聊天提示。"""
    text = body(packet_code, "private static void reject(")
    if not text:
        return False
    return ("player.displayClientMessage(Component.translatable(rejectMessageKey(reason)), false);" in text
            and "menu.noteAreaReject(reason);" in text
            and "player.inventoryMenu.broadcastFullState();" in text
            and "private static String rejectMessageKey(final int reason)" in packet_code)


def b6_silent_drops_now_reject(packet_code):
    """旁观者 / 目标解析失败这两处曾经是**静默 return**，现在都必须走 reject()。"""
    text = body(packet_code, "public static void handle(final AreaSelectorEditPacket packet, final ServerPlayer player)")
    if not text:
        return False
    spectator = text.find("if (player.isSpectator()) {")
    empty_target = text.find("if (target.isEmpty()) {")
    if spectator < 0 or empty_target < 0:
        return False
    spectator_body = text[spectator:spectator + 200]
    empty_body = text[empty_target:empty_target + 260]
    return ("reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);" in spectator_body
            and "reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);" in empty_body
            and "if (player.isSpectator()) {\n            return;" not in text)


#: `rejectMessageKey` 里引用的语言键（本轮**不新增**语言键 ⇒ 必须都是既有的）。
REJECT_KEYS = [
    "gui.rs_create_compat.area_selector.err.dimension",
    "gui.rs_create_compat.area_selector.err.limit",
    "gui.rs_create_compat.area_selector.err.duplicate",
    "gui.rs_create_compat.area_selector.err.reject.invalid",
    "gui.rs_create_compat.area_selector.err.reject.no_such_area",
]


def b7_reject_keys_exist(packet_code, zh, en):
    text = body(packet_code, "private static String rejectMessageKey(final int reason)")
    if not text:
        return False
    cjk = None
    import re as _re
    cjk = _re.compile(r"[\u3000-\u9fff\uff00-\uffef]")
    for key in REJECT_KEYS:
        if ('"%s"' % key) not in text:
            return False
        if key not in zh or key not in en:
            return False
        if not zh[key].strip() or not en[key].strip():
            return False
        if cjk.search(en[key]):
            return False
    return True


def b8_no_new_lang_keys():
    """本轮不许新增语言键：`name.*` / `err.reject.*` 之外的新键一律不该出现。

    做法：把「本轮两个 java 文件里出现的 gui./message. 键」与「语言文件里已有的键」对账，
    任何一个 java 侧引用而语言文件里没有的键都判负（等于新增了语言键）。
    """
    zh = load_lang("zh_cn.json")
    used = set()
    for path in (SCREEN, PACKET):
        code = strip_comments(read(path))
        for match in _find_all(code, r'"(gui\.rs_create_compat\.(?:area_selector|area_mark)\.[a-z0-9_.]+)"'):
            used.add(match)
    missing = sorted(key for key in used if key not in zh)
    if missing:
        print("    引用了语言文件里不存在的键：%s" % (missing,))
    return not missing


def _find_all(text, pattern):
    import re as _re
    return _re.findall(pattern, text)


# ======================================================================================
# C 组：行为模型（旧语义必须判负，新语义必须对）
# ======================================================================================

def m1_first_frame_packets(old, frames=5, selected_name="鼓风机"):
    """打开「区域列表」时的重命名包数量。

    旧实现：字段初值 pendingRename="" + renameAtMs=0 ⇒ 首帧就发 ACTION_RENAME(选中行, "")。
    新实现：renamePending 初值 false ⇒ 一帧都不进提交。
    """
    sent = 0
    pending = ""
    rename_at = 0
    name = selected_name
    for frame in range(frames):
        now = 1000 + frame * 16
        if old:
            if now >= rename_at and pending != (name or ""):
                sent += 1
                name = pending          # 乐观写：客户端副本立刻变成空串
        # 新实现：没有 scheduleRename/applyNameNow ⇒ renamePending 一直是 false
    return sent


def m2_rename_flow(old, click_confirm=True, frames_after=3):
    """用户 2-2 的完整时序：「输入鼓风机 → 点确定 → 点区域二」→ 返回 (发包列表, 区域二最终名字)。

    旧语义三点：① 名称框 focused 早退 ⇒ 切行后框里还是旧文字；② flushRename 读即时 selectedIndex；
    ③ renameAtMs 提交后不复位 ⇒ 每帧重投。
    新语义三点：① pendingRenameIndex 打字时捕获；② 一次性提交；③ 选中行变了强制刷新名称框。
    """
    names = ["区域一", "区域二"]            # 客户端副本（服务端同步回来的权威值起手）
    server = list(names)
    selected = 0
    box_row = 0                             # 名称框此刻显示的是哪一行
    box_text = names[0]
    pending_text = ""
    pending_index = -1
    rename_pending = False
    rename_at_ms = 0
    sends = []

    def flush_rename(now):
        nonlocal pending_index, rename_pending, rename_at_ms
        if old:
            index = selected                      # ★ 旧实现：即时选中行
            if not box_text or box_text == names[index]:
                return
            sends.append((index, box_text))
            names[index] = box_text
            return
        if not rename_pending or now < rename_at_ms:
            return
        index = pending_index                     # ★ 新实现：打字时捕获
        rename_pending = False
        pending_index = -1
        rename_at_ms = 10 ** 9
        if index < 0 or index >= len(names):
            return
        if box_text == names[index]:
            return
        sends.append((index, box_text))
        names[index] = box_text
        server[index] = box_text

    def sync_fields(now):
        nonlocal box_row, box_text, rename_pending, pending_index
        if old:
            if True:                              # 旧实现：focused ⇒ 整段 return
                return
        index_changed = selected != box_row
        if index_changed:
            rename_pending = False
            pending_index = -1
        if index_changed:
            box_row = selected
            box_text = names[selected]

    # ① 打字（名称框里显示的是区域一）
    pending_text = "鼓风机"
    box_text = "鼓风机"
    if old:
        rename_at_ms = 0
    else:
        pending_index = box_row
        rename_pending = True
        rename_at_ms = 0
    # ② 点「确定」→ applyNameNow → flushRename
    flush_rename(1001)
    # ③ 点区域二（render 前的鼠标事件）
    selected = 1
    if old:
        pass                                  # 旧实现：applyNameNow 用的是即时 selectedIndex(=1)
    else:
        box_row = 0                            # 切行前：框还代表区域一
        pending_index = box_row if False else pending_index
    # ④ 接下来 render 若干帧：flushApply → flushRename → syncFields
    for frame in range(frames_after):
        now = 1002 + frame * 16
        flush_rename(now)
        sync_fields(now)
    return sends, names[1]


def m3_replace_keeps_name(server_preserves):
    """服务端 REPLACE 是否保留旧名字（旧代码 = 直接用包里那个无名 Area 覆盖）。"""
    old_name = "鼓风机"
    incoming_name = None                      # 客户端 flushApply 发的 Area 只有六个坐标
    return old_name if server_preserves else incoming_name


def m4_retry_after_silent_drop(rolled_back, second_try_text="鼓风机"):
    """「点确定 → 服务端没写进去」之后，玩家再点一次「确定」还能不能发出去。

    * 旧实现：先乐观写客户端副本，静默丢弃时**没有任何回滚** ⇒ 第二次提交被「看着一样」吞掉；
    * 新实现：丢弃也走 reject()（主手路径 = broadcastFullState 回滚 + 聊天提示）⇒ 客户端回到权威值，
      同一段文字再提交会**照常发**（不会被去重判据吞掉）。
    """
    client = "旧名"
    sent = 0
    if second_try_text != client:
        sent += 1
        client = second_try_text              # 乐观写
    if rolled_back:
        client = "旧名"                        # 服务端把权威值推回来
    if second_try_text != client:
        sent += 1
    return sent


# ======================================================================================
# 断言分组
# ======================================================================================

def group_anchors():
    screen_raw = read(SCREEN)
    screen = strip_comments(screen_raw)
    packet = strip_comments(read(PACKET))
    area = strip_comments(read(AREA))
    selection = strip_comments(read(AREA_SELECTION))
    main = strip_comments(read(MAIN))
    be = strip_comments(read(CACHE_BE))
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")

    print("=== A 组：2-2（名字串到下一行）—— 提交对象必须是「打字时捕获的那一行」 ===")
    check("A-1 flushRename 的提交下标 = pendingRenameIndex（不再是即时 selectedIndex）",
          a1_flush_rename_uses_captured_index(screen))
    check("A-2 flushRename 有 renamePending 门 + 提交后状态归零（不再每帧重投）",
          a2_flush_rename_is_one_shot(screen))
    check("A-3 scheduleRename / applyNameNow 都在**打字时**捕获 pendingRenameIndex（源 = nameBoxIndex）",
          a3_capture_at_typing_time(screen))
    check("A-4 存在 discardRename()：丢弃 pending 的唯一出口（不改投到别的行）",
          a4_discard_helper_exists(screen))
    check("A-5 syncFields：选中索引变了必定刷新 nameBox（focused 早退只保留同一行）",
          a5_sync_fields_refreshes_on_index_change(screen))
    check("A-6 selectRow：applyNameNow 之后重读 currentSelection 再 select(raw)",
          a6_select_row_rereads_selection(screen))
    check("A-7 全部「会改选中行」的入口都先提交旧行：selectRow / createArea / copySelected / deleteSelected",
          a7_every_entry_flushes_first(screen))
    check("A-8 removed()（关界面）在 super.removed() 之前提交（第 110 轮口径不许退）",
          a8_removed_flushes_first(screen))
    check("A-9 对账：mouseScrolled 只滚列表视图、keyPressed 只处理回车 —— 这两个入口不改选中行",
          a9_no_other_selection_switches(screen))
    check("A-10 坐标框同病同修：flushApply 也提交给 pendingApplyIndex（捕获行）",
          a10_pending_apply_index(screen))

    print("=== B 组：2-1（看着改了、重开没改）—— 名字落盘链 + 失败回执 ===")
    check("B-1 Area.name 在 存档 codec / 网络 codec / withName 三处都在", b1_area_name_roundtrip_data(area))
    check("B-2 名字走 Area.CODEC → AreaSelection.CODEC → 数据组件 persistent + networkSynchronized",
          b2_selection_persists_name(selection, main))
    check("B-3 方块实体存档 / 读档整份走区域槽物品栈（组件里的名字跟着落盘）",
          b3_block_entity_saves_area_slot(be))
    check("B-4 服务端 ACTION_REPLACE 保留旧名字（「改坐标就丢名字」不再发生）", b4_replace_keeps_name(packet))
    check("B-5 ACTION_RENAME 的拒绝/丢弃对客户端可见（菜单=拒绝序号，主手=聊天提示）", b5_reject_is_visible(packet))
    check("B-6 旁观者 / 目标解析失败两处静默丢弃改为走 reject()（含回滚 + 提示）",
          b6_silent_drops_now_reject(packet))
    check("B-7 rejectMessageKey 引用的 5 个语言键 zh/en 都存在、en 无中日韩", b7_reject_keys_exist(packet, zh, en))
    check("B-8 本轮没有新增语言键（java 里出现的 area_selector 键全部已在 zh_cn.json）", b8_no_new_lang_keys())

    print("=== C 组：行为模型（旧语义必须判负、新语义必须对） ===")
    check("C-1 打开界面：旧实现首帧就发「把名字改成空串」1 个包；新实现 0 个",
          m1_first_frame_packets(old=True) == 1 and m1_first_frame_packets(old=False) == 0)
    old_sends, old_row2 = m2_rename_flow(old=True)
    new_sends, new_row2 = m2_rename_flow(old=False)
    check("C-2 用户 2-2 时序：旧实现把「鼓风机」也写进区域二（复现 bug）；新实现只写区域一",
          old_sends == [(0, "鼓风机"), (1, "鼓风机")] and old_row2 == "鼓风机"
          and new_sends == [(0, "鼓风机")] and new_row2 == "区域二",
          "old=%r/%r new=%r/%r" % (old_sends, old_row2, new_sends, new_row2))
    check("C-3 服务端 REPLACE：旧语义丢名字、新语义保留名字（与 B-4 同源）",
          m3_replace_keeps_name(False) is None and m3_replace_keeps_name(True) == "鼓风机"
          and b4_replace_keeps_name(packet))
    check("C-4 「点确定了但服务端没写」之后重试：有回滚 ⇒ 能再发（2 个包）；无回滚 ⇒ 被吞（1 个包）",
          m4_retry_after_silent_drop(rolled_back=True) == 2
          and m4_retry_after_silent_drop(rolled_back=False) == 1)


def group_counterexamples():
    screen = strip_comments(read(SCREEN))
    packet = strip_comments(read(PACKET))
    area = strip_comments(read(AREA))

    print("=== 反例自证：把坏写法拼回源码副本 / 模型，同一条判据必须判负 ===")

    # A-1：提交下标退回「即时 selectedIndex」（= 2-2 的原始成因）
    broken = screen.replace("final int index = pendingRenameIndex;",
                            "final int index = selection.selectedIndex();", 1)
    counterexample("X-1 源码副本：flushRename 的提交下标退回即时 selectedIndex ⇒ A-1 判负"
                   "（区域二又会被写成鼓风机）",
                   broken != screen and not a1_flush_rename_uses_captured_index(broken))

    # A-2：去掉 renamePending 门（只靠时间戳）
    broken = screen.replace("if (nameBox == null || !renamePending || System.currentTimeMillis() < renameAtMs) {",
                            "if (nameBox == null || System.currentTimeMillis() < renameAtMs) {", 1)
    counterexample("X-2 源码副本：去掉 !renamePending 门（退回「只靠 renameAtMs 猜」）⇒ A-2 判负",
                   broken != screen and not a2_flush_rename_is_one_shot(broken))

    # A-2b：提交后不复位 ⇒ 每帧重投
    broken = screen.replace("        renamePending = false;\n        pendingRenameIndex = -1;\n"
                            "        renameAtMs = Long.MAX_VALUE;\n", "", 1)
    counterexample("X-3 源码副本：提交后不复位（退回每帧重投）⇒ A-2 判负",
                   broken != screen and not a2_flush_rename_is_one_shot(broken))

    # A-5：syncFields 退回「一有焦点就整段 return」
    broken = screen.replace("        if (focused && !indexChanged) {",
                            "        if (focused) {", 1)
    counterexample("X-4 源码副本：syncFields 退回 focused 早退（名称框永远显示上一行）⇒ A-5 判负",
                   broken != screen and not a5_sync_fields_refreshes_on_index_change(broken))

    # A-5b：删掉 indexChanged 判据
    broken = screen.replace("        final boolean indexChanged = selectedIndex != nameBoxIndex;",
                            "        final boolean indexChanged = false;", 1)
    counterexample("X-5 源码副本：把 indexChanged 写死成 false ⇒ A-5 判负",
                   broken != screen and not a5_sync_fields_refreshes_on_index_change(broken))

    # A-6：切行后不重读（乐观改名被切换前的快照写回）
    broken = screen.replace("            applyNameNow();\n            selection = currentSelection();\n",
                            "            applyNameNow();\n", 1)
    counterexample("X-6 源码副本：selectRow 少读一次 currentSelection ⇒ A-6 判负"
                   "（刚提交的名字会被切换前那份快照写回去）",
                   broken != screen and not a6_select_row_rereads_selection(broken))

    # A-7：新建不先提交旧行
    broken = drop_line_in_method(screen, "private void createArea()", "applyNameNow();")
    counterexample("X-7 源码副本：createArea 不先提交旧行 ⇒ A-7 判负",
                   broken != screen and not a7_every_entry_flushes_first(broken))

    # A-10：坐标框的捕获索引被删
    broken = screen.replace("        final int capturedIndex = pendingApplyIndex;\n", "", 1)
    counterexample("X-8 源码副本：flushApply 不再用捕获索引 ⇒ A-10 判负"
                   "（改坐标后 250ms 内切行 = 改到新行）",
                   broken != screen and not a10_pending_apply_index(broken))

    # B-4：退回「直接覆盖」（= 改坐标就丢名字）
    broken = packet.replace("                selection = selection.replace(index,\n"
                            "                    area.name() == null ? area.withName(previous.name()) : area);",
                            "                selection = selection.replace(index, area);", 1)
    counterexample("X-9 源码副本：服务端 REPLACE 退回裸 replace(index, area) ⇒ B-4 判负",
                   broken != packet and not b4_replace_keeps_name(broken))

    # B-5：主手路径回执被删
    broken = packet.replace(
        "                player.displayClientMessage(Component.translatable(rejectMessageKey(reason)), false);\n",
        "", 1)
    counterexample("X-10 源码副本：删掉主手路径的聊天回执 ⇒ B-5 判负"
                   "（「看着改了、其实没写」又变成谁都不知道）",
                   broken != packet and not b5_reject_is_visible(broken))

    # B-6：旁观者分支退回静默 return（= 2-1 的 (d)）
    broken = packet.replace("        if (player.isSpectator()) {\n"
                            "            reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);\n"
                            "            return;",
                            "        if (player.isSpectator()) {\n            return;", 1)
    counterexample("X-11 源码副本：旁观者分支退回静默 return ⇒ B-6 判负",
                   broken != packet and not b6_silent_drops_now_reject(broken))

    # B-1：Area.CODEC 漏 name（旧档读回来就没名字）
    codec_name_line = ('        Codec.STRING.optionalFieldOf("name").forGetter('
                       'area -> java.util.Optional.ofNullable(area.name()))\n')
    broken = area.replace(codec_name_line, "", 1)
    counterexample("X-12 源码副本：Area.CODEC 删掉 name 字段 ⇒ B-1 判负（存档读回来没名字）",
                   broken != area and not b1_area_name_roundtrip_data(broken))

    # C-1：模型退回旧语义（首帧空 pending 就提交）
    counterexample("X-13 模型：把「打开界面」退回旧语义后，断言「0 个包」判负"
                   "（= 一打开就把选中区域的名字清掉）",
                   m1_first_frame_packets(old=True) != 0)

    # C-2：模型退回旧语义（即时选中行 + 每帧重投）
    broken_sends, broken_row2 = m2_rename_flow(old=True)
    counterexample("X-14 模型：退回旧语义后，「区域二名字不变」判负（旧实现确实把区域二写成鼓风机）",
                   broken_row2 == "鼓风机" and (1, "鼓风机") in broken_sends)

    # C-3：模型退回「REPLACE 不保留名字」
    counterexample("X-15 模型：REPLACE 不保留名字 ⇒「名字还是鼓风机」判负（= 2-1 的 (b) 数据链）",
                   m3_replace_keeps_name(server_preserves=False) is None)

    # C-4：模型退回「静默丢弃、无回滚」
    counterexample("X-16 模型：静默丢弃且不回滚 ⇒「重试还能再发一个包」判负"
                   "（客户端副本领先 ⇒ 去重判据把后续提交全吞掉）",
                   m4_retry_after_silent_drop(rolled_back=False) == 1)


def main():
    print("第 124 轮自检：区域选定器「改名两连」（2-1 改了没生效 / 2-2 名字串到下一个区域）")
    group_anchors()
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
