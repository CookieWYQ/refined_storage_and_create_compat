#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 110 轮自检：区域选定器「重命名」真的提交 + 有一个「确定」按钮。

用户第 110 条实机反馈（原话要点）：
  > 「高级规格缓存器的那个区域命名并不能正常生效，而且并没有一个按钮让我点一下、然后确定。
  >  按一下回车键它显示改了，但是再次进入还是没有改。」

## 根因（为什么「显示改了、再进来还是旧的」）
旧实现只有 `scheduleRename()` 的 **500ms 防抖** 提交：`AreaSelectorScreen#flushRename()` 只在
`render()` 里被驱动，且 `System.currentTimeMillis() < renameAtMs` 时直接 return。
而原版 `EditBox` **不处理回车**、`removed()` 也**没有** flush —— 于是玩家的真实操作
「改名字 → 回车 → 关界面」只要关得比防抖快，`AreaSelectorEditPacket(ACTION_RENAME, ...)`
**根本没发出去**；界面列表显示的是客户端乐观副本（`AreaSelection.set`），所以看起来「改了」，
重新打开（从服务端权威数据重建）就变回旧名字。

## 修法（三个「不等防抖」的提交入口 + 一个可见按钮）
  ① 名称框右侧新增「确定」按钮（`name.apply`）→ `applyNameNow()`；
  ② 名称框聚焦时按回车 / 小键盘回车 → `applyNameNow()`（原版 EditBox 不处理回车）；
  ③ `removed()`（关界面）先 `applyNameNow()` 再走原来的描边保留逻辑；
  ④ 切换选中行时先把上一行未提交的名字 flush 掉（`selectRow`）。
  `applyNameNow()` = 把名称框当前文本写进 `pendingRename`、`renameAtMs = 0`、立刻
  `flushRename()` —— 与防抖路径共用同一个发包函数，避免两套提交逻辑分叉。

本脚本只读源码 / 语言文件，只用 Python 标准库。

断言清单：
  ① 按钮：`nameApplyButton` 字段 + `init()` 里真的创建（bounds 在名称框右侧、绑 applyNameNow、
     带 `name.apply.tip` tooltip）+ `refreshButtonLabels()` 里按「有选中且可编辑」启停。
  ② 回车：`keyPressed` 覆写里对 `GLFW_KEY_ENTER` / `GLFW_KEY_KP_ENTER` 调 `applyNameNow()`。
  ③ 关界面：`removed()` 在 `super.removed()` 之前调 `applyNameNow()`。
  ④ 切行：`selectRow` 在换行时先 flush。
  ⑤ `applyNameNow()` 行为：`pendingRename = nameBox.getValue()` + `renameAtMs = 0L` + `flushRename()`，
     且拒绝在「没选中 / 维度不符」时发包（与既有编辑闸门一致）。
  ⑥ 提交链路没变：`flushRename()` 仍发 `ACTION_RENAME`（服务端 `AreaSelectorEditPacket` 仍是权威写入）。
  ⑦ 语言键：`name.apply` / `name.apply.tip` zh/en 成对、en 无中日韩、tip 提到「回车」与「服务端/立即」。
  ⑧ 行为模型：旧「只有防抖」在 300ms 内关界面 ⇒ 0 个重命名包（名字丢）；新「关界面 flush」⇒ 1 个。
  反例自证 ≥8 条：删按钮 / 删回车 / 删 removed flush / 删切行 flush / applyNameNow 不清零防抖 /
  语言键缺 en / 模型退回旧行为 等，必须判负。

用法：python tools/selfcheck_round110_area_selector_name.py
退出码：0 = 全部通过。
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
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
SCREEN = os.path.join(PKG, "client", "screen", "AreaSelectorScreen.java")
PACKET = os.path.join(PKG, "network", "AreaSelectorEditPacket.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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
    return ok


def counterexample(name, ok):
    COUNTER[0] += 1
    check("反例 %s" % name, ok)


def strip_comments(text):
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


def load_lang(name):
    try:
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            return json.load(handle)
    except Exception:
        return {}


# ======================================================================================
# ① 按钮
# ======================================================================================

def apply_button_ok(text):
    code = strip_comments(text)
    if "private Button nameApplyButton;" not in code:
        return False
    return ("nameApplyButton = addRenderableWidget(new Button.Builder(" in code
            and 'Component.translatable(LANG + "name.apply")' in code
            and "button -> applyNameNow()" in code
            and "px + NAME_APPLY_X, py + NAME_BOX_Y, NAME_APPLY_W, NAME_APPLY_H" in code
            and 'LANG + "name.apply.tip"' in code
            and "nameApplyButton.active = hasSelected && editableHere();" in code)


def name_box_width_room_for_button_ok(text):
    """名称框右缘 + 按钮右缘都必须落在面板内（PANEL_W=320；名称框 x=204）。"""
    code = strip_comments(text)
    sizes = {}
    for key in ("PANEL_W", "AXIS_LABEL_X", "NAME_BOX_W", "NAME_APPLY_W"):
        match = re.search(r"static final int %s = (\d+);" % key, code)
        if not match:
            return False
        sizes[key] = int(match.group(1))
    apply_x = sizes["AXIS_LABEL_X"] + sizes["NAME_BOX_W"] + 2
    return (apply_x + sizes["NAME_APPLY_W"] <= sizes["PANEL_W"] - 4
            and sizes["NAME_BOX_W"] >= 60)


# ======================================================================================
# ②③④ 三个「不等防抖」的提交入口
# ======================================================================================

def enter_key_ok(text):
    code = strip_comments(text)
    body = method_body(code, r"public boolean keyPressed\(final int keyCode, final int scanCode, final int modifiers\)")
    if body is None:
        return False
    return ("GLFW.GLFW_KEY_ENTER" in body
            and "GLFW.GLFW_KEY_KP_ENTER" in body
            and "applyNameNow();" in body
            and "nameBox.isFocused()" in body)


def close_flush_ok(text):
    code = strip_comments(text)
    body = method_body(code, r"public void removed\(\)")
    if body is None:
        return False
    apply_at = body.find("applyNameNow();")
    super_at = body.find("super.removed();")
    return apply_at >= 0 and super_at >= 0 and apply_at < super_at


def select_row_flush_ok(text):
    code = strip_comments(text)
    body = method_body(code, r"private void selectRow\(final int index, final boolean announce\)")
    if body is None:
        return False
    return ("if (selection.selectedIndex() != raw) {" in body
            and "applyNameNow();" in body)


def apply_name_now_ok(text):
    code = strip_comments(text)
    body = method_body(code, r"private void applyNameNow\(\)")
    if body is None:
        return False
    return ("pendingRename = nameBox.getValue();" in body
            and "renameAtMs = 0L;" in body
            and "flushRename();" in body
            and "selection.selected() == null || !editableHere()" in body)


def submit_chain_ok(screen_text, packet_text):
    """重命名最终仍走 ACTION_RENAME 的服务端权威写入（不是只在客户端改显示）。"""
    code = strip_comments(screen_text)
    body = method_body(code, r"private void flushRename\(\)")
    if body is None:
        return False
    packet_code = strip_comments(packet_text)
    return (("AreaSelectorEditPacket.ACTION_RENAME" in body)
            and ("selection.rename(index, packet.name())" in packet_code)
            and ("AreaSelection.set(target, selection);" in packet_code))


# ======================================================================================
# ⑦ 语言键
# ======================================================================================

def lang_ok(zh, en):
    cjk = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")
    key_apply = "gui.rs_create_compat.area_selector.name.apply"
    key_tip = "gui.rs_create_compat.area_selector.name.apply.tip"
    for key in (key_apply, key_tip):
        if key not in zh or key not in en:
            return False
        if not zh[key].strip() or not en[key].strip():
            return False
        if cjk.search(en[key]):
            return False
    # tip 必须说清楚「立即提交 + 回车同效」，否则玩家仍然不知道按钮/回车是干什么的
    return ("回车" in zh[key_tip] and "enter" in en[key_tip].lower()
            and ("立即" in zh[key_tip] or "马上" in zh[key_tip]))


# ======================================================================================
# ⑧ 行为模型：防抖 vs 关界面 flush
# ======================================================================================

def model_renames(closed_at_ms, debounce_ms=500, flush_on_close=True, enter_at_ms=None,
                  dedupe=True):
    """返回一次「改名字 → 关界面」操作里真正发出去的 ACTION_RENAME 包数量。

    * `closed_at_ms`：关界面的时刻（相对改完最后一字的毫秒数）；
    * `enter_at_ms`：按回车的时刻（None = 没按；模型里回车 = 立即发送）；
    * `flush_on_close`：关界面时是否强制提交（本轮修法 = True；旧实现 = False）；
    * `dedupe`：关界面 flush 是否会与回车/防抖重复发包（True = 本轮实现，最多 1 包）。
    """
    sent = 0
    if enter_at_ms is not None and enter_at_ms <= closed_at_ms:
        sent += 1
    elif debounce_ms <= closed_at_ms:
        sent += 1  # 防抖到期后 render 里 flush 了
    if flush_on_close and (sent == 0 or not dedupe):
        sent += 1  # 关界面强制 flush（本轮新增：即使比防抖快也不丢；dedupe 时只在没发过时补）
    return sent


def model_ok():
    cases = [
        # 旧实现（只在防抖到期 / 不 flush）：300ms 就关 ⇒ 0 个包 ⇒ 名字丢（用户看到的 bug）
        (dict(closed_at_ms=300, flush_on_close=False), 0),
        # 旧实现：等到 600ms 再关 ⇒ 1 个包
        (dict(closed_at_ms=600, flush_on_close=False), 1),
        # 新实现：300ms 就关 ⇒ 关界面 flush，1 个包（修复）
        (dict(closed_at_ms=300, flush_on_close=True), 1),
        # 新实现 + 回车：立即 1 个包，关界面不重复发
        (dict(closed_at_ms=100, enter_at_ms=50, flush_on_close=True), 1),
        # 新实现：慢慢关（防抖已发）⇒ 仍只 1 个包
        (dict(closed_at_ms=900, flush_on_close=True), 1),
    ]
    for kwargs, expected in cases:
        got = model_renames(**kwargs)
        if got != expected:
            print("    模型不符: %r got=%d expected=%d" % (kwargs, got, expected))
            return False
    return True


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    screen = read(SCREEN)
    packet = read(PACKET)
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")

    print("=== ① 「确定」按钮真的存在、位置在名称框右侧、按「有选中且可编辑」启停 ===")
    check("①-a nameApplyButton 字段 + init 创建 + 绑 applyNameNow + tooltip + refreshButtonLabels 启停",
          apply_button_ok(screen))
    counterexample("删掉按钮的创建 ⇒ 按钮判据判负",
                   apply_button_ok(screen.replace(
                       "nameApplyButton = addRenderableWidget(new Button.Builder(",
                       "nameApplyButton = null; if (false) new Button.Builder(")) is False)
    check("①-b 名称框收窄后，「名称框 + 确定按钮」仍完整落在 320px 面板内（右缘留 ≥4px）",
          name_box_width_room_for_button_ok(screen))
    counterexample("把名称框宽度改回 106（按钮挤出面板）⇒ 几何判据判负",
                   name_box_width_room_for_button_ok(screen.replace(
                       "private static final int NAME_BOX_W = 78;",
                       "private static final int NAME_BOX_W = 106;")) is False)

    print("=== ②③④ 三个「不等防抖」的提交入口：回车 / 关界面 / 切行 ===")
    check("②-a 名称框聚焦时回车 / 小键盘回车 → applyNameNow()", enter_key_ok(screen))
    counterexample("删掉回车分支（原版 EditBox 不处理回车）⇒ 判负",
                   enter_key_ok(screen.replace("org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER",
                                               "org.lwjgl.glfw.GLFW.GLFW_KEY_F1")) is False)
    check("③-a removed() 在 super.removed() 之前调 applyNameNow()（关界面强制提交）", close_flush_ok(screen))
    counterexample("关界面不 flush（用户「改完立刻关 = 名字丢」的原始 bug）⇒ 判负",
                   close_flush_ok(screen.replace(
                       "        // 关界面前强制提交名称：否则「改名字 → 立刻关界面」会丢掉重命名包（用户第 110 条实机反馈）\n        applyNameNow();\n", "")) is False)
    check("④-a 切换选中行时先 flush 上一行未提交的名字", select_row_flush_ok(screen))
    # 第 124 轮：selectRow 里 applyNameNow() 之后多了一行 `selection = currentSelection();`
    # （提交里有乐观改名，必须重读物品再 select，否则名字会被切换前那份快照写回去）。
    # 反例锚点跟着改，判据本身没放宽：删掉「切行先 flush 旧行」仍然必须判负。
    counterexample("切行不 flush（改完名字点别的行 ⇒ 名字丢）⇒ 判负",
                   select_row_flush_ok(screen.replace(
                       "            applyNameNow();\n            selection = currentSelection();\n",
                       "")) is False)

    print("=== ⑤ applyNameNow：清零防抖 + 走同一个 flushRename + 尊重既有编辑闸门 ===")
    check("⑤-a applyNameNow 写 pendingRename、renameAtMs=0、调 flushRename；没选中/维度不符时不发",
          apply_name_now_ok(screen))
    counterexample("applyNameNow 不清零 renameAtMs（照样被防抖挡住）⇒ 判负",
                   apply_name_now_ok(screen.replace("renameAtMs = 0L;", "renameAtMs = System.currentTimeMillis() + 500L;")) is False)

    print("=== ⑥ 提交链路仍是服务端权威写入（ACTION_RENAME → selection.rename → 写回物品） ===")
    check("⑥-a flushRename 发 ACTION_RENAME；服务端 rename 后 AreaSelection.set(target, selection)",
          submit_chain_ok(screen, packet))
    counterexample("客户端只改本地副本、不发 ACTION_RENAME（重进就还原）⇒ 判负",
                   submit_chain_ok(screen.replace(
                       "send(AreaSelectorEditPacket.ACTION_RENAME, index, selected, wanted);",
                       "AreaSelection.set(stack, selection.rename(index, wanted));"), packet) is False)

    print("=== ⑦ 语言键：name.apply / name.apply.tip（tip 说明「立即 + 回车同效」） ===")
    check("⑦-a zh/en 成对、en 无中日韩、tip 提到回车与立即提交", lang_ok(zh, en))
    counterexample("删掉 en 的 name.apply.tip ⇒ 判负",
                   lang_ok(zh, {k: v for k, v in en.items()
                                if k != "gui.rs_create_compat.area_selector.name.apply.tip"}) is False)
    counterexample("tip 不提回车（玩家不知道回车也能提交）⇒ 判负",
                   lang_ok(dict(zh, **{"gui.rs_create_compat.area_selector.name.apply.tip": "把名称提交到服务端"}),
                           en) is False)

    print("=== ⑧ 行为模型：「改名字 → 关界面」到底发出几个重命名包 ===")
    check("⑧-a 旧实现 300ms 就关 ⇒ 0 个（名字丢）；新实现 ⇒ 1 个；回车 ⇒ 1 个且关界面不重发", model_ok())
    counterexample("模型退回「关界面不 flush」⇒ 300ms 关界面 0 包（名字丢）⇒ 判负",
                   model_renames(closed_at_ms=300, flush_on_close=False) == 0)
    counterexample("模型让回车 + 关界面各发一次（重复包）⇒ 判负",
                   model_renames(closed_at_ms=100, enter_at_ms=50, flush_on_close=True,
                                 dedupe=False) == 2)

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
    sys.exit(main())
